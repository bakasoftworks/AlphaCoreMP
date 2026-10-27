package cc.starsector.server.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The admission door's per-source limiter — ONE pure, clock-injected, game-free shape with five
 * instances (was {@code DoorProbeBudget}, renamed at the second use; the count = the five
 * {@code for*()} factories below):
 * <ul>
 * <li>{@link #forProbe()} — the door PROBE reply budget. The
 *     endpoint asks {@link #admit(String, long)} once per PASSWORD-PASSED probe and sends the
 *     listing only on {@code true}; keyed ONLY after the password passed, so a wrong-password
 *     flood never touches this state (the reject reflector stays the existing ≤~105 B answer).
 *     (scan row 2 — the old "≤4 × 1073 B … ~137 KB/s"
 *     counted the ship frames only): a served probe is ≤ 12 listing frames × 1073 B
 *     ({@code ListingFrameCodec.MAX_FRAME_BYTES}; ≤ 8 mod-set {@code PROBE_MODSET_MAX_FRAMES} +
 *     ≤ 4 ship {@code PROBE_MAX_FRAMES}) per ≥~80 B request, so the global window caps it at
 *     ≈ 412 KB/s host upload + {@value #PROBE_GLOBAL_PER_SECOND} PROBE stdout rows/s regardless
 *     of source count; the reply goes only to a cookie-verified source (no third-party
 *     reflection).</li>
 * <li>{@link #forAdmission()} — the ADMISSION budget. Every admission fans
 *     ~2 SEAT CTRL frames to EVERY live peer (VACATE + CLAIM), so a credential holder churning
 *     HELLO→kill at ~450/s (a churn soak) overran an innocent peer's {@code CTRL_SPOOL_BOUND}
 *     in under a second — {@code ctrl_window_stall} on a peer that did nothing. The budget bounds
 *     admissions per source IP (a churner re-ports at will, so the key is the address, never the
 *     port) AND globally (a spoofed-source flood must not defeat the per-IP key). Asked AFTER the
 *     pure credential check (an unauthenticated flood must never spend the global window — that
 *     would be a door DoS) and BEFORE any seat/session/ledger mutation.</li>
 * <li>{@link #forCredentialFailure()} — the CREDENTIAL-FAILURE budget (closes the
 *     "No throttle" gap). Asked at the two GUESS-shaped
 *     reject sites only — {@code match_password_mismatch} and {@code seat_token_unknown} — never
 *     at {@code match_password_required}/{@code client_id_required}/fingerprint/table-full
 *     (those are not guesses). Over budget = the same SILENT drop: the client retransmits every
 *     250 ms, so a mistyped-password joiner still gets its terminal REJECT on the first
 *     budget-open retransmit (≤1 s, UX unchanged). It bounds answered wrong guesses to one per
 *     second per address and {@value #CREDENTIAL_FAIL_GLOBAL_PER_SECOND}/s total — REPLIES, not
 *     guesses (an earlier "≈ years" claim was wrong: a guesser that ignores replies was
 *     bounded by password entropy alone). Since the guess ledger landed the HELLO/probe/observer sites no
 *     longer consult it; only the in-session admin {@code /auth} guess does.</li>
 * <li>{@link #forVariantCheck()} / {@link #forVariantAnswer()} — the VCHK dry-run pair.
 *     VCHK carries no cookie, so it is gated FIRST on the
 *     {@link DoorGuessLedger#dryRunOpen} right (the source KEY passed a cookie-verified HELLO
 *     compare — a stranger never reaches a VCHK compare: 0 compares/s); then
 *     {@link #forVariantCheck()} paces a passed key at {@value #VARIANT_CHECK_PER_SOURCE_MIN_GAP_MILLIS}
 *     ms with NO global window (≤ 10 compares/s per passed key; a mismatch revokes the key's
 *     right, so a co-tenant of a passed key gets ≤ 1 wrong compare per honest PASS); and
 *     {@link #forVariantAnswer()} caps ANSWERS at {@value #VARIANT_ANSWER_GLOBAL_PER_SECOND}/s
 *     globally AFTER the compare (only credential holders can spend it). Residual: a spoofer
 *     knowing a victim's address + the public fingerprint can revoke or steal the victim's dry
 *     run (verdicts read UNKNOWN; the launch submit is the gate — UX only).</li>
 * </ul>
 * THE HELLO guess-rate law is NOT this class: it is {@link DoorGuessLedger} (
 * per-key backoff + the global first-seen and suspect windows; ≈ 0.125
 * compares/s steady per key, a ceiling of ≈ 74 compares/s over ANY number of keys, first-time
 * joiners held during a fresh-key flood; the mistyper residual: ≥ ~65 attacker keys (any free
 * /48) starve a streak ≥ 3 key's compares for ≤ 1 h per streak step (the global suspect window)
 * — the rates and the cliff live in its javadoc).
 * {@code false} = a SILENT drop the caller counts, never a reply (a reply per denied request would
 * be the amplifier this class exists to bound; a REJECT WELCOME is TERMINAL for the client —
 * {@code AdmissionRejected} — while an unanswered HELLO retransmits every 250 ms, so a legit
 * joiner colliding with the gap simply lands on its next retransmit).
 *
 * <p>Bounds are code constants (knob bar — default NO knob). The per-source
 * memory is a bounded access-ordered LRU of {@value #MAX_SOURCES} entries per instance (the
 * door's other pre-admission state is the {@link DoorGuessLedger}'s bounded key/lane LRUs); O(1)
 * per call, never grows past the cap (eldest evicted — a source cycling more keys than the cap
 * ages its own stamps out, so each instance's GLOBAL window, where it has one, is the bound).
 * Not thread-safe by design: the endpoint calls it under its admission lock.
 *
 *
 */
public final class DoorSourceBudget {

    /** Bounded LRU of remembered sources (the only pre-admission state the budget adds). */
    public static final int MAX_SOURCES = 256;
    /** PROBE: minimum gap between two served probes from ONE source. */
    public static final long PROBE_PER_SOURCE_MIN_GAP_MILLIS = 1_000L;
    /** PROBE: served probes per fixed one-second window, all sources together. */
    public static final int PROBE_GLOBAL_PER_SECOND = 32;
    /** VARIANT CHECK (the VCHK frame): minimum gap
     * between two COMPARES from ONE dry-run-passed source KEY (the joiner sends one VCHK per
     * local custom at a 125 ms pace, which fits) — ≤ 10 compares/s per passed key. */
    public static final long VARIANT_CHECK_PER_SOURCE_MIN_GAP_MILLIS = 100L;
    /** VARIANT CHECK: NO global window — the check budget runs
     * BEFORE the compare, where a global window would be a starve lever; only keys that passed a
     * cookie-verified HELLO compare reach it. {@code Integer.MAX_VALUE} = the ctor's "unbounded"
     * spelling (the window count is an int reset every second — it cannot reach it). */
    public static final int VARIANT_CHECK_GLOBAL_PER_SECOND = Integer.MAX_VALUE;
    /** VARIANT ANSWER: no per-source gap — the
     * per-key pacing is {@link #VARIANT_CHECK_PER_SOURCE_MIN_GAP_MILLIS} before the compare. */
    public static final long VARIANT_ANSWER_PER_SOURCE_MIN_GAP_MILLIS = 0L;
    /** VARIANT ANSWER: answered dry-runs per fixed one-second window, all sources together, asked
     * AFTER the compare — so only credential holders can spend it. The reader-thread decode +
     * pure policy walk this bounds is microseconds; the reply (≤ 121 B) goes only to a source
     * that proved the password, so the cap is CPU/log volume — the credential gate, not reply
     * size, is the anti-reflection law. */
    public static final int VARIANT_ANSWER_GLOBAL_PER_SECOND = 64;
    /** ADMISSION: minimum gap between two admissions from ONE source IP (= the client's own
     * HELLO retransmit cadence, so a budgeted first HELLO lands on its first retransmit). */
    public static final long ADMISSION_PER_SOURCE_MIN_GAP_MILLIS = 250L;
    /** ADMISSION: admissions per fixed one-second window, all sources together — ≤ 2 × this many
     * SEAT CTRL frames per second reach any one peer from churn (a 32-seat join wave still
     * admits inside ~2 s of 250 ms retransmits). */
    public static final int ADMISSION_GLOBAL_PER_SECOND = 16;
    /** ADMISSION: the slice of the global window only a NEVER-SEEN
     * source (no grant remembered in the LRU) may spend — an already-seen source is capped at
     * {@code GLOBAL − RESERVED} per second, so a credential holder churning from ≥ 4 known IPs
     * can never starve a first-time joiner's HELLO (the source-INDEPENDENT window residual). */
    public static final int ADMISSION_RESERVED_FOR_NEW = 4;
    /** CREDENTIAL FAILURE: minimum gap between two ANSWERED wrong guesses from ONE source IP
     * (4 × the HELLO retransmit cadence — a legit mistype still sees its REJECT inside ~1 s). */
    public static final long CREDENTIAL_FAIL_PER_SOURCE_MIN_GAP_MILLIS = 1_000L;
    /** CREDENTIAL FAILURE: answered wrong guesses per fixed one-second window, all sources
     * together — a ceiling on wrong-answer REPLIES (the reflector), NOT on the guess rate: a
     * guesser that ignores replies was never bounded by this class; since
     * the guess ledger landed the HELLO compares are paced by {@link DoorGuessLedger} (per-key backoff +
     * global first-seen / suspect windows, ≤ ≈ 74 compares/s over any number of keys) and this
     * instance serves only the in-session admin {@code /auth} guess. */
    public static final int CREDENTIAL_FAIL_GLOBAL_PER_SECOND = 8;
    /** MATCH DOCUMENT: minimum gap between two document replies to ONE {@link #sourceKey source key}. A
     * document reply is up to 8 sealed frames on top of the listing, so it gets its own term; the gap sits
     * under the kit's 1,500 ms spacing between probe tries, so a try that lost its frames is served again on
     * the next one. */
    public static final long MATCH_DOC_PER_SOURCE_MIN_GAP_MILLIS = 1_000L;
    /** MATCH DOCUMENT: document replies per fixed one-second window, all source keys together. */
    public static final int MATCH_DOC_GLOBAL_PER_SECOND = 8;
    /** MATCH DOCUMENT: the slice of that window only a NEVER-SEEN key may spend, so a stranger's first stale
     * probe stays servable while password holders churn from known addresses (the admission idiom). */
    public static final int MATCH_DOC_RESERVED_FOR_NEW = 4;

    private final long perSourceMinGapMillis;
    private final int globalPerSecond;
    private final int reservedForNew;
    private final int maxSources;
    private final Map<String, Long> lastGrantBySource;
    /** The sources ever budgeted (denied), the same bounded-LRU idiom — a WRITE-ONLY
     * door tally ({@link #budgetedSources()}), never reflected to the wire. */
    private final Map<String, Boolean> budgetedBySource;
    private boolean windowOpen;
    private long windowStartMillis;
    private int windowCount;

    /** Explicit bounds (contract rows + the probe / credential-failure factories);
     * {@code perSourceMinGapMillis >= 0}, {@code globalPerSecond >= 1}; no reserved slice. */
    public DoorSourceBudget(long perSourceMinGapMillis, int globalPerSecond) {
        this(perSourceMinGapMillis, globalPerSecond, 0);
    }

    /** Bounds + the never-seen reserved slice; {@code 0 <= reservedForNew <
     * globalPerSecond} (a seen source must always keep at least one slot). */
    public DoorSourceBudget(long perSourceMinGapMillis, int globalPerSecond, int reservedForNew) {
        this(perSourceMinGapMillis, globalPerSecond, reservedForNew, MAX_SOURCES);
    }

    /** Bounds + reserved slice + the per-source LRU width (every production factory uses
     * {@link #MAX_SOURCES}; the wider shape serves contract rows); {@code maxSources >= 1}. */
    public DoorSourceBudget(long perSourceMinGapMillis, int globalPerSecond, int reservedForNew,
            int maxSources) {
        if (perSourceMinGapMillis < 0L || globalPerSecond < 1) {
            throw new IllegalArgumentException("budget bounds: perSourceMinGapMillis >= 0 and"
                    + " globalPerSecond >= 1; got " + perSourceMinGapMillis + "/" + globalPerSecond);
        }
        if (reservedForNew < 0 || reservedForNew >= globalPerSecond) {
            throw new IllegalArgumentException("budget bounds: 0 <= reservedForNew < globalPerSecond;"
                    + " got " + reservedForNew + "/" + globalPerSecond);
        }
        if (maxSources < 1) {
            throw new IllegalArgumentException("budget bounds: maxSources >= 1; got " + maxSources);
        }
        this.perSourceMinGapMillis = perSourceMinGapMillis;
        this.globalPerSecond = globalPerSecond;
        this.reservedForNew = reservedForNew;
        this.maxSources = maxSources;
        this.lastGrantBySource = new LinkedHashMap<String, Long>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > DoorSourceBudget.this.maxSources;
            }
        };
        this.budgetedBySource = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > DoorSourceBudget.this.maxSources;
            }
        };
    }

    /** The door's source KEY
     * — the {@link DoorGuessLedger} key and the VCHK budgets' key — an IPv4 address as-is (an
     * IPv4-mapped IPv6 address arrives as IPv4); an IPv6 address by its /56 (the common
     * residential delegation — a single tenant's /64 rotation is ONE key: one backoff, one
     * first-seen spend). Co-tenants inside one key (CGNAT, NAT64, a shared /56, link-local LAN)
     * share its gap and streak — the ledger's PASS-opened (key, cid) lane is the fairness handle;
     * a first-time joiner on a hostile shared key is the recorded residual.
     * Null-safe ("" for a null address — never reached from a bound datagram source). */
    public static String sourceKey(java.net.InetAddress address) {
        if (address == null) {
            return "";
        }
        byte[] raw = address.getAddress();
        if (raw == null || raw.length != 16) {
            return address.getHostAddress();
        }
        StringBuilder key = new StringBuilder(24);
        for (int i = 0; i < 7; i++) {
            if (i > 0) {
                key.append(':');
            }
            key.append(Integer.toHexString(raw[i] & 0xFF));
        }
        return key.append("::/56").toString();
    }

    /** The door PROBE reply budget. */
    public static DoorSourceBudget forProbe() {
        return new DoorSourceBudget(PROBE_PER_SOURCE_MIN_GAP_MILLIS, PROBE_GLOBAL_PER_SECOND);
    }

    /** The ADMISSION budget (the admission bounds + the never-seen reserved slice). */
    public static DoorSourceBudget forAdmission() {
        return new DoorSourceBudget(ADMISSION_PER_SOURCE_MIN_GAP_MILLIS, ADMISSION_GLOBAL_PER_SECOND,
                ADMISSION_RESERVED_FOR_NEW);
    }

    /** The MATCH-DOCUMENT budget: the document term asked before the probe budget for a stale-term probe, keyed
     * by {@link #sourceKey} ({@value #MATCH_DOC_PER_SOURCE_MIN_GAP_MILLIS} ms per key,
     * {@value #MATCH_DOC_GLOBAL_PER_SECOND} / s with {@value #MATCH_DOC_RESERVED_FOR_NEW} reserved for never-seen
     * keys). A denial is a silent drop. */
    public static DoorSourceBudget forMatchDoc() {
        return new DoorSourceBudget(MATCH_DOC_PER_SOURCE_MIN_GAP_MILLIS, MATCH_DOC_GLOBAL_PER_SECOND,
                MATCH_DOC_RESERVED_FOR_NEW);
    }

    /** The VARIANT-CHECK budget: {@value
     * #VARIANT_CHECK_PER_SOURCE_MIN_GAP_MILLIS} ms per {@link #sourceKey source key}, NO global
     * window; asked AFTER the {@link DoorGuessLedger#dryRunOpen} gate and BEFORE the compare
     * (≤ 10 compares/s per passed key; a stranger never reaches it). A denial is a silent drop. */
    public static DoorSourceBudget forVariantCheck() {
        return new DoorSourceBudget(VARIANT_CHECK_PER_SOURCE_MIN_GAP_MILLIS,
                VARIANT_CHECK_GLOBAL_PER_SECOND);
    }

    /** The VARIANT-ANSWER budget —
     * 0 ms per source, {@value #VARIANT_ANSWER_GLOBAL_PER_SECOND} answers/s globally, asked AFTER
     * the compare (it paces ANSWERS, which only credential holders can spend). A denial is a
     * silent drop. */
    public static DoorSourceBudget forVariantAnswer() {
        return new DoorSourceBudget(VARIANT_ANSWER_PER_SOURCE_MIN_GAP_MILLIS,
                VARIANT_ANSWER_GLOBAL_PER_SECOND);
    }

    /** The CREDENTIAL-FAILURE budget. */
    public static DoorSourceBudget forCredentialFailure() {
        return new DoorSourceBudget(CREDENTIAL_FAIL_PER_SOURCE_MIN_GAP_MILLIS,
                CREDENTIAL_FAIL_GLOBAL_PER_SECOND);
    }

    /**
     * True iff a grant to {@code source} may go out at {@code nowMillis}; a grant is recorded
     * (per-source stamp + global window count), a denial records nothing but the LRU touch and
     * the write-only budgeted tally. Checks the global window first so a saturated second never
     * re-stamps a source. The window's ceiling depends on whether the source is
     * SEEN (a grant remembered in the LRU — probed with {@code containsKey}, which never touches
     * the access order): a seen source stops at {@code globalPerSecond − reservedForNew}, a
     * never-seen source may spend the whole window — the reserved slice is what keeps a
     * first-time joiner admissible under a known-IP churn.
     */
    public boolean admit(String source, long nowMillis) {
        return admit(source, nowMillis, false);
    }

    /**
     * {@link #admit(String, long)} with the per-source gap test skipped when {@code gapExempt} (the host's own
     * exempt addresses); the window ceiling, the seen / never-seen split and the grant stamp all still apply, so
     * an exempt grant counts in the window like any other.
     */
    public boolean admit(String source, long nowMillis, boolean gapExempt) {
        Objects.requireNonNull(source, "source");
        if (!windowOpen || nowMillis - windowStartMillis >= 1_000L
                || nowMillis < windowStartMillis) {
            windowOpen = true;
            windowStartMillis = nowMillis;
            windowCount = 0;
        }
        boolean seen = lastGrantBySource.containsKey(source);
        int ceiling = seen ? globalPerSecond - reservedForNew : globalPerSecond;
        if (windowCount >= ceiling) {
            budgetedBySource.put(source, Boolean.TRUE);
            return false;
        }
        Long last = seen ? lastGrantBySource.get(source) : null;
        if (!gapExempt && last != null && nowMillis - last < perSourceMinGapMillis) {
            budgetedBySource.put(source, Boolean.TRUE);
            return false;
        }
        lastGrantBySource.put(source, nowMillis);
        windowCount++;
        return true;
    }

    /** Sources currently remembered (≤ {@link #MAX_SOURCES}); contract-row observable. */
    public int trackedSources() {
        return lastGrantBySource.size();
    }

    /** Distinct sources ever budgeted, ≤ {@link #MAX_SOURCES} (bounded LRU — an
     * ancient one ages out); the door line's write-only {@code admission_budgeted_sources=}. */
    public int budgetedSources() {
        return budgetedBySource.size();
    }

    public long perSourceMinGapMillis() {
        return perSourceMinGapMillis;
    }

    public int globalPerSecond() {
        return globalPerSecond;
    }

    /** The never-seen reserved slice of the window (0 = none, the probe / credential
     * shapes). */
    public int reservedForNew() {
        return reservedForNew;
    }

    /** The per-source LRU width ({@link #MAX_SOURCES} unless constructed wider). */
    public int maxSources() {
        return maxSources;
    }
}
