package cc.starsector.server.runtime;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * ONE client-side hs-13 join flight: the state
 * machine every sender drives — the dial, the join window's probe, the in-session re-HELLO pump and the server-tree
 * drill client — where today each hand-rolls the v12 cookie dance. WHY one class: the guess budget, the
 * nonce ↔ envelope binding and the "a forged packet never ends the flight" law must hold identically for every sender;
 * four copies drift. Callers: the server-tree drill client ({@code PilotIntentDatagramClient}), the contract test rig
 * and the kit's version-13 senders (the class is folded into the kit jar; {@code PredictDatagramDial} was the first).
 *
 * <p><b>The flight.</b> {@link #start} = the framed F1 {@code HELLO13-OPEN} carrying the caller's flags —
 * or, when {@link #adoptCookie} handed in a unit kept from an earlier flight on the SAME socket and the budget grants
 * its pair, the framed F2 {@code HELLO13-SEAL} at once (one flight). A {@code COOKIE13} echoing one of our
 * nonces makes the flight seal an F2 against it ({@link Event.Kind#SEND}); a {@code WELCOME13} ADMITTED that
 * opens is {@link Event.Kind#ADMITTED} and moves the flight to ADMITTED-KEPT (below); a plaintext REJECTED at version
 * byte {@link Handshake13#VERSION} echoing one of our nonces, BEFORE any ADMITTED, ends it ({@link Event.Kind#REJECTED} —
 * unauthenticated and terminal, exactly as today). The caller's resend timer calls {@link #retransmitOrNull()}: the
 * byte-identical latest F2 if one is sealed, else the F1, until the first ADMITTED; it never seals (at the cap it may
 * earn ONE further seal — The cap, below).
 *
 * <p><b>States</b>: NEW → LIVE ({@link #start}) → ADMITTED-KEPT (the first
 * opened ADMITTED) → ENDED (a REJECTED before any ADMITTED, or {@link #abandon()} from any state). ADMITTED-KEPT seals
 * nothing more (a {@code COOKIE13} is {@code IGNORE}, the resend is {@code null}), takes no REJECTED ({@code IGNORE}),
 * has its secret copy and generator already zeroed, and KEEPS its seal ring: a WELCOME13 for a NEWER seal of the same
 * flight (the door replaced the older seal's session with it) still opens and is {@code ADMITTED} again. Each opened
 * ADMITTED zeroes both roots of every kept seal at or below the new MARK (they can open nothing more); a seal newer
 * than the MARK keeps its roots. {@link #abandon()} is the one public terminal: it zeroes the secret copy, the generator
 * and both roots of every kept seal, then drops the ring. On the {@link Lane#TOKEN} lane every kept root EMBEDS the
 * token (a root = {@code concat(isk, mix)}, {@code Handshake13.sealHelloOnGeneratorOrNull}), so zeroing the secret
 * copy at ADMITTED does not end the flight's hold on it while a seal newer than the MARK is kept — only
 * {@link #abandon()} does; a caller that is done with the flight calls it.
 *
 * <p><b>Every seal draws a FRESH nonce</b> ({@code rng.nextLong()}): nonce and envelope are 1:1, so the
 * WELCOME13 that echoes a nonce names the one envelope that can open it. The flight remembers its F1 nonce and its last
 * {@value #SEALS_REMEMBERED} seals, each slot = (nonce, {@code ClientSeal}, the unit sealed against, its SEAL ORDER: the
 * value of {@link #seals()} at that seal — 1, 2, …, never reused); a {@code COOKIE13} or REJECTED echoing ANY of those
 * nonces is "ours". An ADMITTED is opened ONLY with the seal of the nonce it echoes, and only when that seal's ORDER is
 * greater than the MARK (the order of the last opened ADMITTED's seal; 0 = none, so before the first ADMITTED every
 * remembered seal qualifies); a seal not newer is {@code IGNORE} with no open attempt. Order, never ring index: a wrapped
 * ring never re-opens an older seal.
 *
 * <p><b>The budget</b> ({@link Handshake13PairBudget}): before every seal the flight asks its
 * {@link Handshake13PairBudget.Host} whether this door share pair may be sealed against; a refusal is
 * {@link Event.Kind#HELD} (no new F2; the resend keeps what it had), counted in {@link #pairDenied()}. The sealed-unit
 * check: a {@code COOKIE13} byte-equal (cookie, both shares, salt) to the unit of a REMEMBERED seal (the last
 * {@value #SEALS_REMEMBERED}; a unit the ring evicted is new again) is {@code IGNORE} and the resend keeps the latest
 * F2 — a duplicate or reordered older challenge earns no further seal. ONE exception (the delta):
 * an OLDER remembered unit whose challenge echoes the LATEST seal's nonce is sealed against again. A door
 * learns that nonce only from the latest F2, so the challenge is its answer to that F2: the client's source flapped
 * away and back inside one cookie bucket (S1 → S2 → S1) and the door re-issued S1's unit; ignoring it would resend an
 * F2 the door refuses until the bucket rolls (up to 60 s). The LATEST unit is {@code IGNORE} — bar ONE re-seal per
 * RESEND: the latest unit echoing the LATEST seal's nonce is the door's dead-nonce RE-CHALLENGE
 * (that F2's session died before its ADMITTED13 reached us; inside one cookie bucket the door's cookie is a pure
 * function of (source, bucket), so its challenge is byte-for-byte the unit already sealed) and is sealed against again
 * at a fresh nonce, once: a one-shot flag set when that seal is not {@code HELD} (a {@code HELD} — the cap or the
 * budget — leaves it clear, so the next re-challenge may still re-seal) and cleared by the flight's next
 * {@link #retransmitOrNull()} (the door re-challenges only in answer to a datagram this flight
 * sent, so each timer resend may earn one — a re-sealed session that ALSO dies is re-sealed after one resend
 * interval, never left to the caller's escalation; a door answering every F2 with a re-challenge still gets one seal
 * per resend, under the cap). Any other echo of the latest unit, and a further re-challenge before the next resend,
 * stays {@code IGNORE}; the flap re-seal never touches the flag.
 *
 * <p><b>A sibling's challenge</b> (r2; the version-12 twin = the PREVIOUS-echo COOKIE the session
 * stores for its current HELLO). A caller that keeps an older flight LISTEN-ONLY beside this one (the re-HELLO pump's
 * PREVIOUS, kept across its socket recreate: the older nonces then left the OLD socket, so on the new one only the
 * pump's ACCEPTED race — one older datagram sent from the new socket — or an on-path forger still delivers such a
 * challenge) may hand this flight a challenge that answered the older flight's datagram:
 * {@link #siblingChallengeOrNull} on the older flight says whether a payload is a {@code COOKIE13} echoing one of ITS
 * nonces (it seals nothing and counts nothing), and {@link #onSiblingChallenge} on this one seals against that unit
 * under the SAME cap and budget as an own challenge — a unit this flight already sealed against is {@code IGNORE}
 * (no flap answer, no dead-nonce re-seal: both need an echo of this flight's own latest nonce). WHY: when the path
 * changes during the older flight's last send, the door's answer to it carries the unit the new path needs, and
 * waiting for this flight's own answer costs up to one round trip. The echo law is unchanged: only a party that read
 * a nonce one of the two flights put on the wire can earn a seal, and only ONE flight ever seals.
 *
 * <p><b>The cap</b>: a remembered pair is free, so without a
 * count a hostile door (or an on-path sender reading the clear nonce) that answers with fresh cookie bytes on one pair
 * buys three X25519 per datagram. A flight seals — or tries, a refused share counts too — at most {@link #SEAL_CAP}
 * times on challenges alone; past that a NEW challenge is {@link Event.Kind#HELD} (no F2, no budget call, no pair
 * remembered; +{@link #sealCapped()}), checked AFTER the ignore checks (a duplicate or foreign challenge stays
 * {@code IGNORE}) and BEFORE the budget. WHY a resend credit and not a hard count: an honest door sends a
 * {@code COOKIE13} only in ANSWER to a datagram this flight sent, so a capped flight's own {@link #retransmitOrNull()}
 * earns the ONE seal that resend's answer may need ({@link #sealCredits()}) — never banked (at most one unspent), never
 * earned by a seal's own SEND or by {@link #start}, never by anything a peer sends. A capped flight loses one
 * challenge as if the network dropped it; the next resend's answer is sealed. ADMITTED, the resend bytes and
 * {@link #heldCookieOrNull()} are untouched; the count clears only with a new flight. INVARIANT:
 * {@code seals() + shareRefused() <= SEAL_CAP + sealCredits()}.
 *
 * <p><b>Lane → the codec's arrays</b>: {@link Lane#PASSWORD} seals with {@code prs = secret, mix = EMPTY},
 * {@link Lane#TOKEN} with {@code prs = EMPTY, mix = secret}. The lane is the credential FIELD the caller filled, never
 * anything a door said. A zero-length secret on either lane = nothing typed: the sealer sets {@code NO_SECRET}. The
 * constructor maps {@code prs} to its generator ONCE ({@link #generator}: WHY there); every seal hands the codec that
 * generator, never {@code prs}.
 *
 * <p><b>Failure law.</b> Caller bugs throw, network input never does: a null lane / secret / budget / rng, a flag
 * outside {@code PROBE | OBSERVER}, a print / cid / variant the codec would refuse (an A-string of its cap), a
 * negative {@code desiredShipId}, a {@code team} outside 0..2 = {@code IllegalArgumentException} from the CONSTRUCTOR naming the field, never its
 * value; {@link #start} twice or {@link #adoptCookie} after it = {@code IllegalStateException}, ended or not (those
 * guards are checked first). {@link #abandon()} is the one call another thread may make (a close) while the owning
 * thread sits between the constructor and {@link #start}; losing that race is not a caller bug, so after
 * {@link #abandon()} {@link #start} returns {@code null} (draws no nonce, frames nothing, seals nothing) and
 * {@link #adoptCookie} does nothing — neither throws. Every {@link #onWelcome} input — null, truncated, forged,
 * replayed — returns an {@link Event}; an unauthenticated or forged ADMITTED NEVER ends the flight or moves its MARK.
 * Once ENDED every {@link #onWelcome} is {@code IGNORE} and {@link #retransmitOrNull()} is {@code null}; the secret copy,
 * the generator and both roots of every kept seal are zeroed and the ring dropped. {@link #heldCookieOrNull()} answers in every state
 * (a unit is public wire data); the counters keep their values.
 *
 * <p><b>Threads.</b> Every public method is {@code synchronized} on the flight. CALLER LAW: {@link #start} and
 * {@link #onWelcome} run X25519 (first execution 40–90 ms), {@link #abandon()} waits on the same
 * monitor, and the constructor runs the password map ({@code BigInteger}; its first execution is paid by
 * {@code HandshakeCrypto.warm}) — a caller NEVER holds its session lock, and is never on the game's frame thread, when
 * it calls any of the four.
 *
 * <p><b>No secret in text.</b> {@link #toString()} of the flight and of {@link Event} prints the state, the kind and
 * the counters only.
 *
 * <p><b>Classloader B</b> (this class will ride the client jar): {@code java.lang},
 * {@code java.util.Arrays}, {@code java.security.SecureRandom} and the project classes {@link Handshake13},
 * {@link HandshakeCrypto}, {@link HandshakeCodec}, {@link DatagramFrameCodec}, {@link Handshake13PairBudget} only — no logging, no I/O, no
 * {@code java.util.concurrent}, no {@code java.net}; text is built with {@code StringBuilder} (no
 * {@code invokedynamic}).
 *
 * <p>VALUE-DOMAIN: nonces = any signed long (fresh per seal); {@code desiredShipId >= 0}; {@code nowMillis} = the
 * caller's monotonic millisecond clock, any sign (passed through to the budget).
 *
 * <p>
 *
 * <p>ADMITTED-KEPT, the seal order, the sealed-unit
 * check and {@link #abandon()}.
 *
 * <p>The cap — {@link #SEAL_CAP}, the credit in
 * {@link #retransmitOrNull()}, the check in {@code sealAgainst}, {@link #sealCapped()} / {@link #sealCredits()}.
 *
 * <p>The sibling's challenge and the per-resend re-arm of the dead-nonce re-seal are this flight's
 * own.
 */
public final class Handshake13Flight {

    /** Which credential FIELD the caller filled — never anything a door said. */
    public enum Lane { PASSWORD, TOKEN }

    /** How many sealed envelopes (nonce → ClientSeal) the flight can still open a WELCOME13 for. */
    public static final int SEALS_REMEMBERED = 4;

    /** The most F2 envelopes ONE flight seals before its seals are paced by its own resends (class javadoc, The cap). */
    public static final int SEAL_CAP = 2 * SEALS_REMEMBERED;

    private static final byte[] EMPTY = new byte[0];
    private static final int CALLER_FLAGS = Handshake13.CALLER_FLAGS;
    /** Door shares are 32 B X25519 u-coordinates. */
    private static final int SHARE_BYTES = 32;
    /** The ADMITTED nonce echo is the u64 at payload[2..10). */
    private static final int ECHO_END = 10;

    private final Lane lane;
    private final byte[] secret;
    private final byte[] prs;
    /**
     * {@code G = generator(prs, CI)}, mapped ONCE, in the constructor, and handed to every seal
     * ({@code Handshake13.sealHelloOnGeneratorOrNull}); zeroed wherever {@link #secret} is ({@link #onAdmitted},
     * {@link #end}). WHY: the map is not constant-time (its Elligator 2 branch is a function of the password alone)
     * and ANY door can make a flight seal and time the F2 (a COOKIE13 on a fresh share pair earns
     * one), so a per-seal map gave a hostile door one timing sample per challenge; mapped before the flight's first
     * datagram, no challenge times it (a fixed few-bit partition either way: sid is EMPTY, CI fixed).
     *
     */
    private final byte[] generator;
    private final byte[] mix;
    private final int flags;
    private final String print;
    private final String cid;
    private final long desiredShipId;
    private final String variantId;
    /** The sealed team byte, 0..{@code Handshake13.TEAM_MAX} (0 = none). */
    private final int team;
    private final Handshake13PairBudget.Host budget;
    private final SecureRandom rng;

    private Handshake13.Cookie13 adopted;
    private boolean started;
    private boolean ended;
    private long f1Nonce;
    private byte[] f1;
    private final long[] sealNonces = new long[SEALS_REMEMBERED];
    private final Handshake13.ClientSeal[] sealRing = new Handshake13.ClientSeal[SEALS_REMEMBERED];
    /** Per ring slot: the unit that slot's seal was made against (the sealed-unit check reads every slot). */
    private final Handshake13.Cookie13[] sealUnits = new Handshake13.Cookie13[SEALS_REMEMBERED];
    /** Per ring slot: that seal's ORDER = {@link #seals} right after it (1, 2, …; positive, never reused). */
    private final long[] sealOrders = new long[SEALS_REMEMBERED];
    private int sealCount;
    private int sealNext;
    /** The ORDER of the last opened ADMITTED's seal; 0 = no ADMITTED yet, non-zero = ADMITTED-KEPT (or ended after it). */
    private long mark;
    private Handshake13.Cookie13 heldUnit;
    /** The nonce of the latest seal ({@link #latestF2} carries it): the one echo that re-opens an OLDER sealed unit. */
    private long latestSealNonce;
    private byte[] latestF2;
    private long seals;
    private long pairDenied;
    private long shareRefused;
    private long openFailed;
    private long ignored;
    /** New challenges HELD because the flight was at its cap (class javadoc, The cap). */
    private long sealCapped;
    /** Resends that found the flight AT its cap: each lifts the cap by one seal (class javadoc, The cap). */
    private long sealCredits;
    /** The one same-unit (dead-nonce) re-seal is spent; cleared by the next {@link #retransmitOrNull()} (class javadoc, The budget). */
    private boolean sameUnitResealed;

    /**
     * @param secret the typed secret's bytes ({@code Handshake13.secretBytes}): the PRS on the PASSWORD lane, the mix
     *               on the TOKEN lane; zero-length = nothing typed. Copied; the copy is zeroed at the first opened
     *               ADMITTED, at a REJECTED and at {@link #abandon()} — and with it the generator mapped HERE from
     *               the PRS (once per flight; {@code G("")} on the TOKEN lane).
     * @param flags  a subset of {@code Handshake13.CALLER_FLAGS} = PROBE | OBSERVER | MAGIC_UP | MAGIC_REPLICA
     *               ({@code NO_SECRET} is the sealer's).
     * @param budget the host's bucket from {@link Handshake13PairBudget#host} (senders: {@code process()}).
     * @param rng    the platform's UNSEEDED {@code SecureRandom} (Handshake13's CALLER LAW: a seeded generator repeats
     *               the scalar and the content key).
     */
    public Handshake13Flight(Lane lane, byte[] secret, int flags, String print, String cid, long desiredShipId,
            String variantId, Handshake13PairBudget.Host budget, SecureRandom rng) {
        this(lane, secret, flags, print, cid, desiredShipId, variantId, 0, budget, rng);
    }

    /**
     * The flight with the joiner's {@code team} byte (0 = none: the door picks; 1 = side 0; 2 = side 1), sealed into
     * every F2 this flight sends; {@code IllegalArgumentException} "team" outside 0..{@code Handshake13.TEAM_MAX}. The
     * nine-argument constructor above is this one at team 0. Every other parameter as there.
     *
     */
    public Handshake13Flight(Lane lane, byte[] secret, int flags, String print, String cid, long desiredShipId,
            String variantId, int team, Handshake13PairBudget.Host budget, SecureRandom rng) {
        if (lane == null) {
            throw new IllegalArgumentException("lane");
        }
        if (secret == null) {
            throw new IllegalArgumentException("secret");
        }
        if ((flags & ~CALLER_FLAGS) != 0) {
            throw new IllegalArgumentException("flags");
        }
        requireAString(print, Handshake13.PRINT_MAX, "print");
        requireAString(cid, Handshake13.CID_MAX, "cid");
        requireAString(variantId, Handshake13.VARIANT_MAX, "variantId");
        if (desiredShipId < 0L) {
            throw new IllegalArgumentException("desiredShipId");
        }
        if (team < 0 || team > Handshake13.TEAM_MAX) {
            throw new IllegalArgumentException("team");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget");
        }
        if (rng == null) {
            throw new IllegalArgumentException("rng");
        }
        this.lane = lane;
        this.secret = Arrays.copyOf(secret, secret.length);
        this.prs = lane == Lane.PASSWORD ? this.secret : EMPTY;
        this.generator = HandshakeCrypto.generator(this.prs, Handshake13.ci());
        this.mix = lane == Lane.TOKEN ? this.secret : EMPTY;
        this.flags = flags;
        this.print = print;
        this.cid = cid;
        this.desiredShipId = desiredShipId;
        this.variantId = variantId;
        this.team = team;
        this.budget = budget;
        this.rng = rng;
    }

    /**
     * BEFORE {@link #start}: a {@code (cookie, shares, salt)} unit kept from an earlier flight on the SAME socket
     * ({@link #heldCookieOrNull()} of that flight), so {@link #start} can send the F2 at once. {@code null} = none (the
     * earlier flight sealed nothing). Copied. {@code IllegalArgumentException} naming the field on a cookie outside
     * 1..32 B, a share not 32 B or a salt not 16 B; {@code IllegalStateException} after {@link #start}. After
     * {@link #abandon()} (and not started): does nothing, never throws (class javadoc, Failure law).
     */
    public synchronized void adoptCookie(Handshake13.Cookie13 held) {
        if (started) {
            throw new IllegalStateException("adoptCookie");
        }
        if (ended) {
            return;
        }
        adopted = held == null ? null : copyUnit(held);
    }

    /**
     * The first datagram: the framed F1 (a fresh nonce, the caller's flags, never {@code NO_SECRET}) — or the framed
     * F2 when a cookie was adopted, the budget grants its pair and the seal succeeds (a denied pair counts
     * {@link #pairDenied()}, a refused share {@link #shareRefused()}; both fall back to the F1). Runs X25519 on the
     * adopted path (class javadoc: never under a session lock, never on the frame thread). {@code IllegalStateException}
     * when called twice, ended or not. After {@link #abandon()}: {@code null} — no nonce drawn, nothing framed or
     * sealed, no throw (the lost close race, class javadoc).
     */
    public synchronized byte[] start(long nowMillis) {
        if (started) {
            throw new IllegalStateException("start");
        }
        started = true;
        if (ended) {
            return null;
        }
        f1Nonce = rng.nextLong();
        f1 = frame(Handshake13.encodeOpen(f1Nonce, flags));
        if (adopted != null && sealAgainst(adopted, nowMillis).kind == Event.Kind.SEND) {
            return copy(latestF2);
        }
        return copy(f1);
    }

    /**
     * What to resend now: the latest F2 if one is sealed, else the F1 (byte-identical copies); {@code null} before
     * {@link #start}, from the first opened ADMITTED on (shape α (1)), and once the flight ended. NEVER seals. A resend
     * that finds the flight AT its cap earns one credit (the next new challenge may be sealed; class javadoc, The cap),
     * and every resend re-arms the one dead-nonce re-seal (class javadoc, The budget); it mutates nothing else.
     */
    public synchronized byte[] retransmitOrNull() {
        if (!started || ended || mark != 0L) {
            return null;
        }
        if (seals + shareRefused >= SEAL_CAP + sealCredits) {
            sealCredits++; // a capped flight's resend earns the ONE seal its answer may need; a credit never banks
        }
        sameUnitResealed = false; // this resend's answer may be the door's next dead-nonce re-challenge
        return copy(latestF2 != null ? latestF2 : f1);
    }

    /**
     * r2, the LISTEN-ONLY half (class javadoc, A sibling's challenge): the decoded unit when {@code payload} is a
     * {@code COOKIE13} echoing one of THIS flight's nonces and the flight is still LIVE (started, not ended, no
     * ADMITTED yet); else {@code null}. Seals nothing, spends nothing, counts nothing — the caller hands the unit to
     * the ONE flight that seals ({@link #onSiblingChallenge}). Never throws on the payload.
     */
    public synchronized Handshake13.Cookie13 siblingChallengeOrNull(byte[] payload) {
        if (!started || ended || mark != 0L) {
            return null;
        }
        Handshake13.Cookie13 c = Handshake13.decodeCookieOrNull(payload);
        return c != null && ours(c.nonceEcho()) ? c : null;
    }

    /**
     * r2, the SEALING half (class javadoc, A sibling's challenge): {@code unit} = what a listen-only older flight
     * returned from {@link #siblingChallengeOrNull}. {@code IGNORE} before {@link #start}, once ended,
     * from the first ADMITTED on, for {@code null} and for a unit this flight already sealed against; else the cap,
     * the budget and one seal at a fresh nonce exactly as an own challenge ({@code SEND} / {@code HELD} /
     * {@code IGNORE} on a refused share). Copies the unit ({@code IllegalArgumentException} naming the field on a
     * caller-built unit of a wrong length; a decoded one never is). Runs X25519: the CALLER LAW of {@link #onWelcome}.
     */
    public synchronized Event onSiblingChallenge(Handshake13.Cookie13 unit, long nowMillis) {
        if (!started || ended || mark != 0L || unit == null || unitAlreadySealed(unit)) {
            ignored++;
            return Event.IGNORED;
        }
        return sealAgainst(copyUnit(unit), nowMillis);
    }

    /**
     * One WELCOME-channel PAYLOAD (the caller already un-framed it), dispatched on
     * {@link Handshake13#statusOrNegative}: COOKIE13 → {@code SEND} / {@code HELD} / {@code IGNORE} ({@code IGNORE}
     * from the first ADMITTED on, and for a unit already sealed against, bar the flap answer and the one dead-nonce
     * re-seal — class javadoc, The
     * budget; {@code HELD} = the flight at its cap or the budget refused the pair — The cap / The budget); ADMITTED →
     * opened ONLY with the seal of the
     * nonce it echoes and only when that seal is newer than the MARK (else {@code IGNORE}, no open attempt) — it opens
     * = {@code ADMITTED} and the flight KEEPS its seals (class javadoc, States), a failed open is {@code IGNORE}
     * (+ {@link #openFailed()}); REJECTED (the plaintext grammar at byte {@link Handshake13#VERSION}, echoing one of our nonces) before the first
     * ADMITTED → {@code REJECTED}, ends the flight, after it → {@code IGNORE}; anything else → {@code IGNORE}. Never
     * throws on the payload. Runs X25519 on a new challenge (class javadoc: never under a session lock, never on the
     * frame thread).
     */
    public synchronized Event onWelcome(byte[] payload, long nowMillis) {
        if (!started || ended || payload == null) {
            ignored++;
            return Event.IGNORED;
        }
        int status = Handshake13.statusOrNegative(payload);
        if (status == HandshakeCodec.WELCOME_COOKIE) {
            return onCookie(payload, nowMillis);
        }
        if (status == HandshakeCodec.WELCOME_ADMITTED) {
            return onAdmitted(payload);
        }
        if (status == HandshakeCodec.WELCOME_REJECTED) {
            return onRejected(payload);
        }
        ignored++;
        return Event.IGNORED;
    }

    /**
     * The latest unit a seal was made against (a copy; the hand-off to the next flight on this socket), or {@code null}
     * when nothing was sealed. Answers in every state — after ADMITTED and after the end too (a unit is public wire
     * data, never key material).
     */
    public synchronized Handshake13.Cookie13 heldCookieOrNull() {
        return heldUnit == null ? null : copyUnit(heldUnit);
    }

    /**
     * The match document's one open: a DESC frame the door sealed under the exchange root of
     * one of this flight's live seals — tried under every kept seal's rootM, then rootA, as {@link #onAdmitted} opens
     * a WELCOME13 (a password probe seals under rootM, an admin-password probe under rootA), newest seal first. Returns
     * the authenticated chunk, or null: a structural refusal (BAD_ENVELOPE / BAD_HEADER / BAD_BOUNDS) ends the trial at
     * once — no root would mend it; a BAD_TAG under every root = a chunk of another flight, a forgery or a replay. No
     * root byte leaves the flight; a seal at or below the MARK (its roots zeroed) and an ENDED flight open nothing. The
     * caller counts each null once. Only the collector of a PROBE flight calls it, never with a WELCOME-channel payload.
     */
    public synchronized MatchDocFrameCodec.Chunk openDocumentOrNull(byte[] payload) {
        if (payload == null || ended) {
            return null;
        }
        for (int i = 1; i <= sealCount; i++) {
            int s = Math.floorMod(sealNext - i, SEALS_REMEMBERED);
            Handshake13.ClientSeal seal = sealRing[s];
            if (seal == null || sealOrders[s] <= mark) {
                continue;
            }
            for (byte[] root : new byte[][] {seal.rootM(), seal.rootA()}) {
                MatchDocFrameCodec.Decoded d = MatchDocFrameCodec.decode(root, payload);
                if (d.reason() == MatchDocFrameCodec.OK) {
                    return d.chunk();
                }
                if (d.reason() != MatchDocFrameCodec.BAD_TAG) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * The flight's one public terminal: zero the secret copy and the generator, zero both
     * roots of every kept seal ({@code ClientSeal.rootM()} / {@code rootA()} hand out the seal's live arrays), drop the
     * ring; ENDED. Legal
     * in every state, before {@link #start} included; idempotent (again, or after a REJECTED = nothing more, never
     * throws). Afterwards {@link #onWelcome} is {@code IGNORE}, {@link #retransmitOrNull()} {@code null} and — when the
     * flight was not yet started — {@link #start} {@code null} and {@link #adoptCookie} a no-op (on a started flight
     * both keep their {@code IllegalStateException}); {@link #heldCookieOrNull()} and the counters keep answering. No
     * X25519, but it waits on the monitor {@link #start} / {@link #onWelcome} hold through theirs — the same CALLER LAW
     * (class javadoc, Threads): never under a session lock, never on the frame thread.
     *
     *
     */
    public synchronized void abandon() {
        end();
    }

    /** F2 envelopes sealed (each with a fresh nonce). */
    public synchronized long seals() {
        return seals;
    }

    /** Challenges (or an adopted unit) the budget refused — {@code HELD}. */
    public synchronized long pairDenied() {
        return pairDenied;
    }

    /** Seals the JDK refused (a small-order / malformed door share) — no F2 left the flight. */
    public synchronized long shareRefused() {
        return shareRefused;
    }

    /** ADMITTED payloads echoing a sealed nonce that did not open — forged, corrupted or from a door without the secret. */
    public synchronized long openFailed() {
        return openFailed;
    }

    /**
     * Every other {@code IGNORE}: not ours, malformed, a unit already sealed against, a challenge or a reject after the
     * first ADMITTED, an ADMITTED for a seal not newer than the MARK, an unknown status, after the end.
     */
    public synchronized long ignored() {
        return ignored;
    }

    /** New challenges {@code HELD} because the flight was at its cap (never counted in {@link #pairDenied()}). */
    public synchronized long sealCapped() {
        return sealCapped;
    }

    /** Resends that found the flight at its cap, each lifting it by one seal (class javadoc, The cap). */
    public synchronized long sealCredits() {
        return sealCredits;
    }

    /** The lane, the state and the counters — never a byte of the secret, a share, a cookie or a root. */
    @Override
    public synchronized String toString() {
        StringBuilder b = new StringBuilder(144);
        b.append("Handshake13Flight[").append(lane.name()).append(' ')
                .append(ended ? "ended" : mark != 0L ? "admitted" : started ? "live" : "new")
                .append(" seals=").append(seals)
                .append(" pairDenied=").append(pairDenied)
                .append(" shareRefused=").append(shareRefused)
                .append(" openFailed=").append(openFailed)
                .append(" ignored=").append(ignored)
                .append(" sealCapped=").append(sealCapped)
                .append(" sealCredits=").append(sealCredits)
                .append(']');
        return b.toString();
    }

    // --- the three replies -------------------------------------------------------------------------------------------

    /**
     * A challenge: {@code IGNORE} once ADMITTED (shape α (1): no budget call, no seal) or for a unit already sealed
     * against — unless the echo names the LATEST seal and either that unit is an OLDER one (the door's answer to the
     * latest F2 after a source flap; class javadoc, The budget) or it is the LATEST unit and the one-shot dead-nonce
     * re-seal is unspent (the door's re-challenge: the flag is set only when that seal is not
     * {@code HELD}). {@code unitAlreadySealed} implies a seal exists, so {@code heldUnit} is non-null and
     * {@code latestSealNonce} is set where they are read.
     */
    private Event onCookie(byte[] payload, long nowMillis) {
        Handshake13.Cookie13 c = Handshake13.decodeCookieOrNull(payload);
        if (mark != 0L || c == null || !ours(c.nonceEcho())) {
            ignored++;
            return Event.IGNORED;
        }
        if (unitAlreadySealed(c)) {
            if (sameUnit(heldUnit, c)) {
                // The latest unit re-seals ONCE, only on the latest echo
                if (c.nonceEcho() != latestSealNonce || sameUnitResealed) {
                    ignored++;
                    return Event.IGNORED;
                }
                Event e = sealAgainst(c, nowMillis);
                if (e.kind != Event.Kind.HELD) {
                    sameUnitResealed = true;
                }
                return e;
            }
            if (c.nonceEcho() != latestSealNonce) {
                ignored++;
                return Event.IGNORED;
            }
        }
        return sealAgainst(c, nowMillis);
    }

    /**
     * Shape α (2) + (4): the echoed nonce's slot, and its seal ORDER above the MARK, BEFORE any open (a not-newer seal
     * is a plain {@code IGNORE}, never {@code openFailed}); an open sets the MARK to that order and zeroes the secret
     * copy and the generator (no seal follows an ADMITTED); the ring stays.
     *
     * <p>
     *
     * <p>The generator, and the ROOTS of every kept seal at or below the new
     * MARK. WHY: such a seal can open nothing again (the order check above IGNOREs it before any open; the challenge
     * and the resend stop at the first ADMITTED), yet on the {@link Lane#TOKEN} lane its roots embed the token. The
     * returned {@code Admitted13} holds no root array ({@code sessionRoot} is an HKDF output, {@code ybAuth} a copy),
     * so zeroing the opened seal's roots here leaves the event whole; seals NEWER than the MARK keep theirs (they may
     * still be ADMITTED, class javadoc, States).
     */
    private Event onAdmitted(byte[] payload) {
        int slot = payload.length < ECHO_END ? -1 : slotFor(u64(payload, 2));
        if (slot < 0 || sealOrders[slot] <= mark) {
            ignored++;
            return Event.IGNORED;
        }
        Handshake13.Admitted13 admitted = Handshake13.openAdmittedOrNull(payload, sealRing[slot]);
        if (admitted == null) {
            openFailed++;
            return Event.IGNORED;
        }
        mark = sealOrders[slot];
        Arrays.fill(secret, (byte) 0);
        Arrays.fill(generator, (byte) 0);
        for (int s = 0; s < sealCount; s++) {
            if (sealOrders[s] <= mark) {
                Arrays.fill(sealRing[s].rootM(), (byte) 0);
                Arrays.fill(sealRing[s].rootA(), (byte) 0);
            }
        }
        return new Event(Event.Kind.ADMITTED, null, admitted, null);
    }

    /** The plaintext REJECT: terminal before the first ADMITTED; {@code IGNORE} after it (shape α (3) — it is unauthenticated). */
    private Event onRejected(byte[] payload) {
        HandshakeCodec.WelcomePayload rejected = HandshakeCodec.WelcomePayload.decodeRejectedOrNull(payload, Handshake13.VERSION);
        if (mark != 0L || rejected == null || !ours(rejected.nonceEcho())) {
            ignored++;
            return Event.IGNORED;
        }
        end();
        return new Event(Event.Kind.REJECTED, null, null, rejected);
    }

    /**
     * The cap, the budget, then one seal at a FRESH nonce: {@code HELD} (+ sealCapped) when the flight is at its cap —
     * FIRST, so a capped flight spends no token and remembers no pair (class javadoc, The cap); {@code HELD}
     * (+ pairDenied) when the host refuses the pair; {@code IGNORE} (+ shareRefused, which counts toward the cap) when
     * the JDK refuses a share; else remember (nonce, seal, unit, order) in the ring, keep the unit, frame the F2 on
     * {@code WireChannel.HELLO} and {@code SEND} it. {@code unit} is already a private copy.
     */
    private Event sealAgainst(Handshake13.Cookie13 unit, long nowMillis) {
        if (seals + shareRefused >= SEAL_CAP + sealCredits) {
            sealCapped++;
            return Event.HELD_EVENT;
        }
        if (!budget.admit(unit.ybM(), unit.ybA(), nowMillis)) {
            pairDenied++;
            return Event.HELD_EVENT;
        }
        long nonce = rng.nextLong();
        Handshake13.ClientSeal seal = Handshake13.sealHelloOnGeneratorOrNull(nonce, flags, unit, print, cid, desiredShipId,
                variantId, team, generator, prs.length == 0, mix, rng);
        if (seal == null) {
            shareRefused++;
            return Event.IGNORED;
        }
        seals++;
        Handshake13.ClientSeal evicted = sealRing[sealNext];
        if (evicted != null) { // a full ring: the outgoing seal can open nothing more, so its roots go now, not at GC (item 53)
            Arrays.fill(evicted.rootM(), (byte) 0);
            Arrays.fill(evicted.rootA(), (byte) 0);
        }
        sealNonces[sealNext] = nonce;
        sealRing[sealNext] = seal;
        sealUnits[sealNext] = unit;
        sealOrders[sealNext] = seals;
        sealNext = (sealNext + 1) % SEALS_REMEMBERED;
        if (sealCount < SEALS_REMEMBERED) {
            sealCount++;
        }
        heldUnit = unit;
        latestSealNonce = nonce;
        latestF2 = frame(seal.payload());
        return new Event(Event.Kind.SEND, latestF2, null, null);
    }

    private boolean ours(long nonce) {
        return nonce == f1Nonce || slotFor(nonce) >= 0;
    }

    /** The ring slot whose seal drew {@code nonce}, or -1. */
    private int slotFor(long nonce) {
        for (int i = 0; i < sealCount; i++) {
            if (sealNonces[i] == nonce) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The sealed-unit scan: {@code c}'s unit ({@link #sameUnit}) equals the unit of ANY remembered seal (one unit per
     * ring slot; after a flap re-seal two slots hold the same unit) — the latest unit included, so it subsumes the
     * duplicate-challenge compare. {@link #onCookie} owns the flap exception.
     *
     *
     */
    private boolean unitAlreadySealed(Handshake13.Cookie13 c) {
        for (int i = 0; i < sealCount; i++) {
            if (sameUnit(sealUnits[i], c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ENDED — a REJECTED before any ADMITTED, or {@link #abandon()}: zero the secret copy, the generator and both roots
     * of every kept seal (item 53), drop the ring (seals, units, the count). Idempotent: a second call finds an empty
     * ring.
     */
    private void end() {
        ended = true;
        Arrays.fill(secret, (byte) 0);
        Arrays.fill(generator, (byte) 0);
        for (int s = 0; s < sealCount; s++) {
            Arrays.fill(sealRing[s].rootM(), (byte) 0);
            Arrays.fill(sealRing[s].rootA(), (byte) 0);
        }
        Arrays.fill(sealRing, null);
        Arrays.fill(sealUnits, null);
        sealCount = 0;
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** Every hs-13 message is ONE datagram, session 0 / round 0; C→S rides {@code WireChannel.HELLO}. */
    private static byte[] frame(byte[] payload) {
        return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.HELLO, 0, 0L, 0L, 0L, 0, 0L, payload);
    }

    private static byte[] copy(byte[] b) {
        return Arrays.copyOf(b, b.length);
    }

    /** A private copy of a unit, its lengths checked as the codec's {@code adb} would (a caller-built unit). */
    private static Handshake13.Cookie13 copyUnit(Handshake13.Cookie13 u) {
        byte[] cookie = u.cookie();
        if (cookie == null || cookie.length < 1 || cookie.length > Handshake13.COOKIE_MAX_BYTES) {
            throw new IllegalArgumentException("cookie");
        }
        if (u.ybM() == null || u.ybM().length != SHARE_BYTES) {
            throw new IllegalArgumentException("ybM");
        }
        if (u.ybA() == null || u.ybA().length != SHARE_BYTES) {
            throw new IllegalArgumentException("ybA");
        }
        if (u.laneSalt() == null || u.laneSalt().length != Handshake13.SALT_BYTES) {
            throw new IllegalArgumentException("laneSalt");
        }
        return new Handshake13.Cookie13(u.nonceEcho(), copy(cookie), copy(u.ybM()), copy(u.ybA()), copy(u.laneSalt()));
    }

    /** (cookie, both shares, salt) byte-equal; the nonce echo is not part of the unit. */
    private static boolean sameUnit(Handshake13.Cookie13 a, Handshake13.Cookie13 b) {
        return Arrays.equals(a.cookie(), b.cookie()) && Arrays.equals(a.ybM(), b.ybM()) && Arrays.equals(a.ybA(), b.ybA())
                && Arrays.equals(a.laneSalt(), b.laneSalt());
    }

    private static long u64(byte[] b, int off) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }

    /** A-string encode precondition, by the codec's own caps: non-null, {@code length <= max}, every char 0x21..0x7E. */
    private static void requireAString(String s, int max, String field) {
        if (s == null || s.length() > max) {
            throw new IllegalArgumentException(field);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new IllegalArgumentException(field);
            }
        }
    }

    /** One {@link #onWelcome} outcome; immutable (the datagram is copied in and out). */
    public static final class Event {

        /**
         * IGNORE = nothing to do; SEND = send {@link #datagram()} NOW; HELD = the budget refused this pair, or the flight
         * reached {@link Handshake13Flight#SEAL_CAP} (its next resend lifts it by one; nothing to send now); ADMITTED = a
         * WELCOME13 opened (the flight stops sealing and resending and keeps its seals: a NEWER seal's WELCOME13 can be
         * ADMITTED again); REJECTED = the flight ended.
         */
        public enum Kind { IGNORE, SEND, HELD, ADMITTED, REJECTED }

        private static final Event IGNORED = new Event(Kind.IGNORE, null, null, null);
        private static final Event HELD_EVENT = new Event(Kind.HELD, null, null, null);

        private final Kind kind;
        private final byte[] datagram;
        private final Handshake13.Admitted13 admitted;
        private final HandshakeCodec.WelcomePayload rejected;

        private Event(Kind kind, byte[] datagram, Handshake13.Admitted13 admitted, HandshakeCodec.WelcomePayload rejected) {
            this.kind = kind;
            this.datagram = datagram == null ? null : copy(datagram);
            this.admitted = admitted;
            this.rejected = rejected;
        }

        public Kind kind() {
            return kind;
        }

        /** SEND: a fresh copy of the framed F2 to send NOW; {@code null} for every other kind. */
        public byte[] datagram() {
            return datagram == null ? null : copy(datagram);
        }

        /** ADMITTED: the opened WELCOME13 (its kind is EVIDENCE, never authority — {@link Handshake13#openAdmittedOrNull}); else {@code null}. */
        public Handshake13.Admitted13 admitted() {
            return admitted;
        }

        /** REJECTED: status {@code WELCOME_REJECTED}, its reason free door TEXT (unauthenticated); else {@code null}. */
        public HandshakeCodec.WelcomePayload rejected() {
            return rejected;
        }

        /** The kind only. */
        @Override
        public String toString() {
            return new StringBuilder(32).append("Handshake13Flight.Event[").append(kind.name()).append(']').toString();
        }
    }
}
