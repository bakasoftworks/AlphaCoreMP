package cc.starsector.server.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The P27 player-ban set — the model, the
 * {@code cc-server.bans} codec and its boot read. The door ({@link PilotIntentDatagramServer})
 * holds ONE instance and asks it at the HELLO seam; the serve's command lane mutates it (the one
 * code path — house file, GUI, web and chat all deposit a {@code Command}); persistence is the
 * off-frame {@link BanPersistSink} (never a file op on the frame).
 *
 * <p>Keys: a {@link Kind#CID} key is the cid's one-way digest ({@link CidDigest#of}, the
 * form every log row, reel row and listing prints a player key in) — whitespace- and {@code #}-free
 * by construction, so the on-disk line grammar stays token-exact, and a shared file hands out no
 * player key. The lowercase hex of the cid's UTF-8 bytes ({@link #cidKey}) stays the LEGACY / operator
 * form: an old file's hex row converts at load, and the edges take either form
 * ({@link #cidBanKeyOrNull}). A {@link Kind#SRC} key is a stored source key ({@link SourceRanges}):
 * the string {@link DoorSourceBudget#sourceKey} mints (IPv4 dotted as-is; IPv6 = its /56 key form),
 * or a RANGE (an IPv4 range like {@code 1.2.3.0/24}, an IPv6 byte key at /16../64) that
 * {@link #isBannedSource} matches by prefix through the range index — {@link #isSrcKey} is a
 * GRAMMAR check over those forms, never an IP parser over operator text (a hand-typed IPv6 not in
 * key form is refused, naming {@link #SRC_KEY_FORM} — A17; only the {@code ban-ip} verb converts one).
 *
 *
 * <p>Laws: thread-safe on its own monitor and NEVER calls out (no door, no lock outside itself —
 * the door may ask it under its own lock); capped at {@value #MAX_ENTRIES} entries, refused at
 * the cap, never evicted (A9); comment and malformed lines are preserved verbatim so a rewrite
 * never loses them (A9); every entry carries its {@link Origin} (A7 — chat {@code /unban} lifts
 * admin-origin entries only).
 *
 * <p>W16 timed bans: a chat-origin CID entry may carry a
 * wall-clock {@code until} and its paired source key {@code src} ({@link Entry}). An EXPIRED entry
 * is absent to every clocked ask ({@link #isBannedCid(String, long)}, {@link #find(Kind, String,
 * long)}, {@link #snapshot(long)}, {@link #add}); it leaves memory at the next sweep — a render
 * (= the save), an add at the cap, or the boot parse (which then owes one save). The un-clocked
 * {@code find} / {@code snapshot} / {@code size} stay RAW. Expired entries are the only ones ever
 * dropped to make room (a live ban is still never evicted).
 */
public final class BanSet {

    /** The entry cap: {@link #add} refuses past it; {@link #parse} ignores extras. */
    public static final int MAX_ENTRIES = 4_096;
    /** Preserved (comment / malformed) lines kept across rewrites; extras dropped + one log row. */
    public static final int MAX_PRESERVED_LINES = 1_024;
    /** A bans file larger than this refuses boot (fail-closed: 4,096 entries fit in ≈ 0.6 MB). */
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** U+FEFF, stripped from the first line (an editor's UTF-8 BOM). */
    static final char BOM = (char) 0xFEFF;
    /** The HELLO clientId bound is ≤ 64 UTF-16 chars ({@code HandshakeCodec}), i.e. ≤ 192 UTF-8
     *  bytes (3 per BMP char; a surrogate pair = 4 bytes / 2 chars): a cid key is 2..384 hex chars. */
    public static final int CID_MAX_BYTES = 192;
    /** The source-key shapes an operator may type, named in every refusal of a mistyped key (A17).
     *  Four shapes now — the RFC-form IPv6 range is typed to
     *  {@code ban-ip} only and stored as its byte key, never as typed. */
    public static final String SRC_KEY_FORM = "IPv4 dotted (1.2.3.4), an IPv4 range (1.2.3.0/24, /8 to /31),"
            + " an IPv6 key exactly as a bans listing prints it (e.g. 20:1:d:b8:0:12:34::/56 - copy it whole),"
            + " or for ban-ip an IPv6 provider range like 2a0b:f4c0::/32 (stored as its key 2a:b:f4:c0::/32)";
    /** The two player-key forms the edges take, named in every refusal of a
     *  mistyped one (the {@link #SRC_KEY_FORM} idiom). */
    public static final String CID_KEY_FORM = "the player key's fingerprint as the bans listings print it (h1: then"
            + " 32 chars of 0-9a-f) or the older hex player key (an even number of 0-9a-f chars, up to "
            + (2 * CID_MAX_BYTES) + ")";

    public enum Kind { CID, SRC }

    public enum Origin { OP, ADMIN }

    public enum AddResult { ADDED, ALREADY, CAP }

    /** The {@link #retime} outcome — the entry's
     *  {@code until} moved / no live chat-origin CID entry by that key / an operator entry
     *  (never retimed from chat). */
    public enum RetimeResult { RETIMED, ABSENT, OPERATOR }

    /** The label token's hex bound: the seat-name law's byte half × 2 (a DERIVED bound, no new
     *  number). */
    static final int LABEL_MAX_HEX_CHARS = 2 * ListingFrameCodec.OCCUPANT_LABEL_BUFFER_BYTES;

    /**
     * One ban: {@code atMillis} = epoch millis of the add (0 = unknown, a hand-typed line).
     * {@code label} = the banned seat's name at ban time
     * (nullable) — re-run through {@link VoiceMuteCommand#safeLabel} HERE, so every entry's
     * label is printable ASCII ≤ the occupant-label cap by construction (the /unban menu's
     * LSTD {@code occupantLabel} can never throw on it). Player text: it rides the LSTD
     * occupantLabel ONLY — never a chat reply, log row, status row or receipt.
     * {@code until} = the wall-clock epoch ms the ban ends
     * (0 = permanent; negative clamps to 0) and {@code src} = the paired door source key whose
     * hold the ban carries across restarts (nullable). {@code until} rides a chat-origin CID entry ONLY
     * ({@code kind == CID && origin == ADMIN}); any other entry is built with {@code until = 0}
     * (fail-closed: an SRC or operator ban never expires, so {@link #isBannedSrc} needs no clock).
     * {@code src} rides EVERY CID entry whose key passes {@link #isSrcKey} (a
     * console / command-file {@code ban <seat>} records the player's address exactly as a chat ban
     * does — a client id is one deletable file, the address is the bar that bites); an SRC entry carries none.
     */
    public record Entry(Kind kind, String key, long atMillis, Origin origin, String label, long until,
            String src) {
        public Entry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(origin, "origin");
            label = label == null ? null : VoiceMuteCommand.safeLabel(label);
            boolean chatCid = kind == Kind.CID && origin == Origin.ADMIN;
            until = chatCid ? Math.max(0L, until) : 0L;
            // the pair = a key the DOOR mints: a range key would install a hold no peer ever matches
            src = kind == Kind.CID && SourceRanges.isDoorKey(src) ? src : null;
        }

        /** The label-less permanent entry (every site that predates labels). */
        public Entry(Kind kind, String key, long atMillis, Origin origin) {
            this(kind, key, atMillis, origin, null, 0L, null);
        }

        /** The permanent entry with a label (every pre-W16 site). */
        public Entry(Kind kind, String key, long atMillis, Origin origin, String label) {
            this(kind, key, atMillis, origin, label, 0L, null);
        }

        /** The one on-disk line: {@code kind key millis origin [labelhex] [until=<ms>] [src=<key>]}
         *  in THAT order (all lowercase tokens; the 5th = the lowercase hex of the label's UTF-8
         *  bytes, only when present; {@code until=} only when timed, {@code src=} only when paired). */
        public String line() {
            return kindToken(kind) + " " + key + " " + atMillis + " " + originToken(origin)
                    + (label == null ? "" : " " + cidKey(label))
                    + (until == 0L ? "" : " until=" + until)
                    + (src == null ? "" : " src=" + src);
        }

        /** The ONE expiry predicate — timed and at or past its
         *  end ({@code until == wallNow} IS expired); a permanent entry never expires. */
        public boolean expired(long wallNow) {
            return until != 0L && until <= wallNow;
        }
    }

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> preserved = new ArrayList<>();
    /** An entry was ADDED or removed since the last
     *  {@link #render} (under the class monitor; a parsed/loaded set starts clean — unless the load
     *  dropped expired entries or converted legacy hex cid keys: then the
     *  boot save is owed). A retime, a src strip and an expiry sweep mark it too. */
    private boolean dirty;
    /**
     * The RANGE INDEX: every SRC entry whose key no door key equals ({@link SourceRanges#isRange}), by family,
     * then by prefix length → the stored keys at that length. {@link #isBannedSource} computes ONE masked key per
     * DISTINCT length present in the asking address's family (at most 24 IPv4 lengths, /8../31, and 48 IPv6 lengths,
     * /16../64 but the door's /56) and asks that length's set — never a scan of the up-to-{@value #MAX_ENTRIES}
     * entries. Maintained under the class monitor wherever an SRC entry enters or leaves ({@link #add},
     * {@link #remove}, {@link #sweepExpired}, the {@link #parse} rebuild); the exact keys stay in {@code entries}
     * only.
     */
    private final java.util.TreeMap<Integer, java.util.Set<String>> rangesV4 = new java.util.TreeMap<>();
    private final java.util.TreeMap<Integer, java.util.Set<String>> rangesV6 = new java.util.TreeMap<>();

    public BanSet() {
    }

    // ---------------------------------------------------------------- key grammar

    /** The hex key of a cid: lowercase hex of its UTF-8 bytes — the label token, the expected-occupant
     *  and admin-arm keys, and the LEGACY ban key form ({@link #cidBanKeyOrNull} converts it).
     *  Injective on every HELLO-admitted cid (the decode refuses a
     *  lone surrogate, which {@code getBytes} would map to {@code ?}); compare KEYS, never text. */
    public static String cidKey(String cid) {
        Objects.requireNonNull(cid, "cid");
        byte[] raw = cid.getBytes(StandardCharsets.UTF_8);
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** The cid a {@link #cidKey} encodes, or null when {@code hex} is not a cid key or its bytes
     *  are not valid UTF-8 (listings only — the door matches keys, never decoded text). */
    public static String cidOfKeyOrNull(String hex) {
        if (!isCidKey(hex)) {
            return null;
        }
        byte[] raw = new byte[hex.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException notUtf8) {
            return null; // a hand-typed key whose bytes are not text: listable as hex only
        }
    }

    /** A cid key: even length, 2..{@code 2*CID_MAX_BYTES} chars of lowercase {@code [0-9a-f]}. */
    public static boolean isCidKey(String s) {
        if (s == null || s.length() < 2 || s.length() > 2 * CID_MAX_BYTES || (s.length() & 1) != 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** A source key in a STORED form ({@link SourceRanges#isKey}): IPv4 dotted decimal (four 0..255
     *  parts, no leading zeros) = the door's IPv4 key; a canonical IPv4 range ({@code a.b.c.0/N}, N
     *  8..31, host bits zero); or an IPv6 byte key ({@code ceil(N/8)} 1..2-char lowercase hex bytes,
     *  no leading zero, joined by {@code :} + {@code ::/N}, N 16..64; the /56 key is the
     *  {@link DoorSourceBudget#sourceKey} form). Grammar only — never resolves a name; an RFC-form
     *  IPv6 range is NOT a stored key. Delegates; the length cap
     *  rose 40 → 60 (the longest stored key, an IPv6 /64, is 28 chars). */
    public static boolean isSrcKey(String s) {
        return s != null && !s.isEmpty() && s.length() <= 60 && SourceRanges.isKey(s);
    }

    /** The key grammar for {@code kind}: a CID entry key is the digest ({@link CidDigest#isDigest}), never hex. */
    public static boolean isKey(Kind kind, String key) {
        return kind == Kind.CID ? CidDigest.isDigest(key) : isSrcKey(key);
    }

    /**
     * A typed or deposited player key → the CID entry key, or null when it is
     * neither form ({@link #CID_KEY_FORM} names both in a refusal). Trimmed and ASCII-folded; a digest passes through;
     * a hex key that decodes to a client id becomes that id's digest; a hex key whose bytes are not text is refused
     * (no HELLO cid encodes to it, so a ban by it could bar nobody). Pure; one SHA-256 for a hex key, none for a digest.
     */
    public static String cidBanKeyOrNull(String token) {
        if (token == null) {
            return null;
        }
        String folded = token.strip().toLowerCase(Locale.ROOT);
        if (CidDigest.isDigest(folded)) {
            return folded;
        }
        String cid = cidOfKeyOrNull(folded);
        return cid == null ? null : CidDigest.of(cid);
    }

    /** A typed address or range → its stored SRC key, or null with the plain reason appended to {@code whyOrNull}.
     *  WHY public: the consoles mint {@code ban-ip} from a typed address and sit outside this package; the one
     *  normalizer stays {@link SourceRanges#canonicalOrNull}. */
    public static String srcBanKeyOrNull(String typed, StringBuilder whyOrNull) {
        return SourceRanges.canonicalOrNull(typed, whyOrNull);
    }

    public static String kindToken(Kind kind) {
        return kind == Kind.CID ? "cid" : "src";
    }

    public static String originToken(Origin origin) {
        return origin == Origin.OP ? "op" : "admin";
    }

    /** {@code cid}/{@code src} (ASCII-folded) → the kind, else null. */
    public static Kind kindOrNull(String token) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return "cid".equals(t) ? Kind.CID : "src".equals(t) ? Kind.SRC : null;
    }

    // ---------------------------------------------------------------- queries

    /** The seam ask on the WALL clock (tests and legacy callers; the HELLO seam passes its own
     *  {@code wallNow} to {@link #isBannedCid(String, long)}). */
    public boolean isBannedCid(String cidOrNull) {
        return isBannedCid(cidOrNull, System.currentTimeMillis());
    }

    /** The seam ask: a null/blank cid never matches (token mode, A6).
     * An EXPIRED entry does not bar (the one expiry predicate, {@link Entry#expired}).
     *  The cid's digest is computed BEFORE this set's monitor is taken (the
     *  hash never runs under it), then the O(1) lookup runs under it; the caller passes the raw HELLO cid. */
    public boolean isBannedCid(String cidOrNull, long wallNow) {
        if (cidOrNull == null || cidOrNull.isBlank()) {
            return false;
        }
        String key = mapKey(Kind.CID, CidDigest.of(cidOrNull));
        synchronized (this) {
            Entry e = entries.get(key);
            return e != null && !e.expired(wallNow);
        }
    }

    public synchronized boolean isBannedSrc(String sourceKey) {
        if (sourceKey == null || sourceKey.isEmpty() || entries.isEmpty()) {
            return false;
        }
        return entries.containsKey(mapKey(Kind.SRC, sourceKey));
    }

    /**
     * The HELLO seam's source ask: the O(1) exact {@link #isBannedSrc} on the door's key first, then the range
     * index on the peer's raw address ({@code rawOrNull}: 4 or 16 bytes, {@link java.net.InetAddress#getAddress};
     * null = exact only). Cost: one masked key ({@link SourceRanges#maskedKey}, ≤ 28 chars) per DISTINCT prefix
     * length present in the address's family — 0 when no range of that family is banned, ≤ 24 for IPv4, ≤ 48 for
     * IPv6 — never a scan of the entries. Runs on the door's reader thread under the door lock, then this
     * set's monitor (the set never calls out). SRC entries never expire ({@link Entry}), so no clock.
     *
     */
    public synchronized boolean isBannedSource(String exactKey, byte[] rawOrNull) {
        if (isBannedSrc(exactKey)) {
            return true;
        }
        java.util.TreeMap<Integer, java.util.Set<String>> index = rawOrNull == null ? null
                : rawOrNull.length == 4 ? rangesV4 : rawOrNull.length == 16 ? rangesV6 : null;
        if (index == null || index.isEmpty()) {
            return false;
        }
        for (java.util.Map.Entry<Integer, java.util.Set<String>> at : index.entrySet()) {
            if (at.getValue().contains(SourceRanges.maskedKey(rawOrNull, at.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /** The prefix compare a source ban sweeps live sessions by ({@link SourceRanges#covers}): true when the
     *  stored {@code key} covers the address {@code raw} (4 or 16 bytes); a key of the other family never does. */
    public static boolean coversSource(String key, byte[] raw) {
        return SourceRanges.covers(key, raw);
    }

    /** The range index's add / drop of one SRC entry key (a door key — never a range — is not indexed). */
    private void indexRange(Kind kind, String key, boolean add) {
        if (kind != Kind.SRC || !SourceRanges.isRange(key)) {
            return;
        }
        java.util.TreeMap<Integer, java.util.Set<String>> index = SourceRanges.isIpv6Key(key) ? rangesV6 : rangesV4;
        int bits = SourceRanges.bits(key);
        if (add) {
            index.computeIfAbsent(bits, b -> new java.util.HashSet<>()).add(key);
            return;
        }
        java.util.Set<String> at = index.get(bits);
        if (at != null && at.remove(key) && at.isEmpty()) {
            index.remove(bits);
        }
    }

    /** The range index rebuilt from {@code entries} (a parsed set: the one bulk entry path). */
    private void rebuildRangeIndex() {
        rangesV4.clear();
        rangesV6.clear();
        for (Entry e : entries.values()) {
            indexRange(e.kind(), e.key(), true);
        }
    }

    /** Immutable copy, insertion order — RAW (expired entries included until a sweep). */
    public synchronized List<Entry> snapshot() {
        return List.copyOf(entries.values());
    }

    /** The LIVE entries at {@code wallNow}, insertion order. */
    public synchronized List<Entry> snapshot(long wallNow) {
        List<Entry> live = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            if (!e.expired(wallNow)) {
                live.add(e);
            }
        }
        return List.copyOf(live);
    }

    public synchronized int size() {
        return entries.size();
    }

    /** The preserved (comment / malformed) line count. */
    public synchronized int preservedCount() {
        return preserved.size();
    }

    /** The stored entry, RAW (an expired entry is still returned until a sweep). */
    public synchronized Entry find(Kind kind, String key) {
        return entries.get(mapKey(kind, key));
    }

    /** The stored entry, or null when absent OR expired at
     *  {@code wallNow}. */
    public synchronized Entry find(Kind kind, String key, long wallNow) {
        Entry e = entries.get(mapKey(kind, key));
        return e == null || e.expired(wallNow) ? null : e;
    }

    // ---------------------------------------------------------------- mutation

    /** Add one permanent ban; an invalid key for {@code kind} throws (callers check {@link #isKey}). */
    public synchronized AddResult add(Kind kind, String key, long atMillis, Origin origin) {
        return add(kind, key, atMillis, origin, null, 0L, null);
    }

    /** {@link #add} + the nullable ban-time seat label
     *  (sanitized by {@link Entry}); {@code ALREADY} keeps the existing entry unchanged, label
     *  included. */
    public synchronized AddResult add(Kind kind, String key, long atMillis, Origin origin, String label) {
        return add(kind, key, atMillis, origin, label, 0L, null);
    }

    /**
     * The timed add — {@code until} (epoch ms, 0 = permanent)
     * and the paired source key {@code src} (nullable; both kept only on a chat-origin CID entry,
     * {@link Entry}). {@code atMillis} IS the wall clock of the add: a LIVE entry by that key =
     * {@code ALREADY} (unchanged, its {@code until} included); an EXPIRED one is replaced (moved to
     * the newest position) = {@code ADDED}, dirty; at the cap the expired entries are swept first,
     * then the cap re-checked ({@code CAP} only when the set is still full of live bans).
     */
    public synchronized AddResult add(Kind kind, String key, long atMillis, Origin origin, String label,
            long until, String src) {
        if (!isKey(kind, key)) {
            throw new IllegalArgumentException("not a " + kindToken(kind) + " key");
        }
        long at = Math.max(0L, atMillis);
        String mk = mapKey(kind, key);
        Entry old = entries.get(mk);
        if (old != null && !old.expired(at)) {
            return AddResult.ALREADY;
        }
        if (old != null) {
            entries.remove(mk);
        } else if (entries.size() >= MAX_ENTRIES && sweepExpired(at) == 0) {
            return AddResult.CAP; // full of LIVE bans: refused, never evicted (A9)
        }
        entries.put(mk, new Entry(kind, key, at, origin, label, until, src));
        indexRange(kind, key, true);
        dirty = true;
        return AddResult.ADDED;
    }

    /**
     * re-time a LIVE chat-origin CID ban to {@code until}
     * (epoch ms, 0 = permanent; the caller computes {@code wallNow + term}, never
     * {@code atMillis + term}). Absent or expired at {@code wallNow} ⇒ {@code ABSENT} (a retime
     * never re-arms a lifted or lapsed ban); an operator entry ⇒ {@code OPERATOR},
     * unchanged; a SRC entry (never timed, {@link Entry}) ⇒ {@code ABSENT}. Else the entry keeps
     * its position, {@code atMillis}, label and {@code src} (a retime never mints one),
     * takes the new {@code until}, and marks dirty (the save must follow).
     */
    public synchronized RetimeResult retime(Kind kind, String key, long until, long wallNow) {
        String mk = mapKey(kind, key);
        Entry e = key == null ? null : entries.get(mk);
        if (e == null || e.expired(wallNow)) {
            return RetimeResult.ABSENT;
        }
        if (e.origin() == Origin.OP) {
            return RetimeResult.OPERATOR;
        }
        if (e.kind() != Kind.CID) {
            return RetimeResult.ABSENT;
        }
        entries.put(mk, new Entry(e.kind(), e.key(), e.atMillis(), e.origin(), e.label(), until, e.src()));
        dirty = true;
        return RetimeResult.RETIMED;
    }

    /**
     * The {@link #add} twin for a ban that already stands: pair {@code src} with the LIVE CID entry by {@code key}
     * that carries no {@code src} yet. The entry keeps its position, {@code atMillis}, origin, label and
     * {@code until}; the range index is untouched (a pair is no range key); dirty, so the save that follows writes
     * the {@code src=}. False, unchanged, when the entry is absent or expired at {@code wallNow}, already paired
     * (with this source or another: a pair is never re-pointed here), or {@code src} is no door key (the
     * {@link Entry} law would drop it). WHY: a second ban of an id already barred finds the entry {@code ALREADY}
     * and installs its hold in memory only; without the pair on the line the address bar is gone at the next boot.
     *
     */
    public synchronized boolean attachSrc(Kind kind, String key, String src, long wallNow) {
        String mk = key == null ? null : mapKey(kind, key);
        Entry e = mk == null ? null : entries.get(mk);
        if (kind != Kind.CID || e == null || e.expired(wallNow) || e.src() != null || !SourceRanges.isDoorKey(src)) {
            return false;
        }
        entries.put(mk, new Entry(e.kind(), e.key(), e.atMillis(), e.origin(), e.label(), e.until(), src));
        dirty = true;
        return true;
    }

    /** The house {@code unban src <key>} strip — every entry
     *  paired with {@code srcKey} loses its {@code src=} (entry kept, position kept); returns the
     *  count; dirty iff &gt; 0 (a stripped pair must not re-hydrate its hold at the next boot). */
    public synchronized int stripSrc(String srcKey) {
        if (srcKey == null) {
            return 0;
        }
        int stripped = 0;
        for (java.util.Map.Entry<String, Entry> me : entries.entrySet()) {
            Entry e = me.getValue();
            if (srcKey.equals(e.src())) {
                me.setValue(new Entry(e.kind(), e.key(), e.atMillis(), e.origin(), e.label(), e.until(), null));
                stripped++;
            }
        }
        if (stripped > 0) {
            dirty = true;
        }
        return stripped;
    }

    /** Drop every entry expired at {@code wallNow}; returns
     *  the count; dirty iff &gt; 0. */
    public synchronized int sweepExpired(long wallNow) {
        int before = entries.size();
        entries.values().removeIf(e -> {
            boolean gone = e.expired(wallNow);
            if (gone) {
                indexRange(e.kind(), e.key(), false); // an SRC entry never expires today: the index keeps step anyway
            }
            return gone;
        });
        int swept = before - entries.size();
        if (swept > 0) {
            dirty = true;
        }
        return swept;
    }

    /** Literal-match removal; false when absent. */
    public synchronized boolean remove(Kind kind, String key) {
        boolean removed = key != null && entries.remove(mapKey(kind, key)) != null;
        if (removed) {
            indexRange(kind, key, false);
            dirty = true;
        }
        return removed;
    }

    /** r6: true when an {@code add} returned ADDED or a
     *  {@code remove} returned true since the last {@link #render} (the operator fault belt's
     *  settle reads it; a no-op never marks) — or a {@code retime} RETIMED, a {@code stripSrc} /
     *  {@code sweepExpired} returned &gt; 0, or a parse dropped expired entries. */
    public synchronized boolean dirty() {
        return dirty;
    }

    private static String mapKey(Kind kind, String key) {
        return kindToken(kind) + " " + key;
    }

    // ---------------------------------------------------------------- codec

    /** The file lines on the WALL clock ({@link #render(long)}). */
    public List<String> render() {
        return render(System.currentTimeMillis());
    }

    /** The file lines: the preserved lines (file order), then one {@link Entry#line} per entry.
     *  {@code parse(render())} reproduces this set exactly.
     *  the entries expired at {@code wallNow} are swept FIRST — they leave memory and the file
     *  together (the render IS the save snapshot, so {@code dirty} is clear after). */
    public synchronized List<String> render(long wallNow) {
        sweepExpired(wallNow);
        List<String> out = new ArrayList<>(preserved.size() + entries.size());
        out.addAll(preserved);
        for (Entry e : entries.values()) {
            out.add(e.line());
        }
        dirty = false; // r6: the render IS the save's snapshot
        return out;
    }

    /** {@link #parseEntry}'s drop counters, ONE
     *  {@code int[DROP_SLOTS]} per parse — a dropped label / {@code until=} / {@code src=} / other
     *  tail token (slot indices; the one counter shape). */
    private static final int DROP_LABEL = 0;
    private static final int DROP_UNTIL = 1;
    private static final int DROP_SRC = 2;
    private static final int DROP_TOKEN = 3;
    /** A legacy hex cid key converted to its digest (a count, not a drop). */
    private static final int CONVERTED_HEX = 4;
    private static final int DROP_SLOTS = 5;

    /** One entry line → the entry, or null when the line is not one ({@code cid|src key
     *  [millis] [op|admin] [labelhex] [until=<ms>] [src=<key>]}, single-space or tab separated,
     *  surrounding whitespace ignored). */
    public static Entry parseEntryOrNull(String line) {
        return parseEntry(line, new int[DROP_SLOTS]);
    }

    /**
     * The parser + the optional 5th LABEL token, admitted
     * ONLY after BOTH the millis and the origin token parsed; it must be even-length lowercase
     * hex ≤ {@link #LABEL_MAX_HEX_CHARS} that decodes (UTF-8, malformed input REPORTed) to a
     * string {@link VoiceMuteCommand#safeLabel} leaves UNCHANGED (a stored label is written
     * sanitized, so anything else was hand-edited). ANY label failure drops the LABEL, never the
     * ENTRY (a bad label must never lift a ban by skipping its line) and counts into
     * {@code drops[DROP_LABEL]}; 2..4-token lines keep their pre-label semantics exactly.
     * The TAIL law, behind the same millis + origin gate: a
     * token WITHOUT {@code =} at position 5 is the label; {@code until=<ms>} / {@code src=<key>}
     * follow it (or stand at position 5) in ANY order, each at most once, on a {@code cid … admin}
     * line only. Fail-closed, the ENTRY is always kept (a bad tail never lifts a ban): a
     * bad, duplicate or off-kind {@code until=} ⇒ PERMANENT + {@code drops[DROP_UNTIL]}; a
     * {@code src=} that is no door key or lies within loopback, a duplicate (the first is kept) or off-kind ⇒ that
     * token dropped + {@code drops[DROP_SRC]}; any other token, and every token past the 7th ⇒
     * dropped + {@code drops[DROP_TOKEN]} (counted per TOKEN).
     */
    private static Entry parseEntry(String line, int[] drops) {
        if (line == null) {
            return null;
        }
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) {
            return null;
        }
        String[] tok = t.split("[ \\t]+");
        if (tok.length < 2) {
            return null;
        }
        Kind kind = kindOrNull(tok[0]);
        if (kind == null) {
            return null;
        }
        // A cid key is the digest, folded to lowercase; a legacy hex key that
        // decodes converts to its cid's digest (counted: the load then owes one save); undecodable hex (uppercase or
        // odd-length hex too) is no entry: the caller keeps the line verbatim, unenforced. Counted only when the line
        // yields its entry (a converted key on a line refused below stays a kept line, never a save).
        boolean converted = false;
        if (kind == Kind.CID) {
            String folded = tok[1].toLowerCase(Locale.ROOT);
            if (CidDigest.isDigest(folded)) {
                tok[1] = folded;
            } else {
                String cid = cidOfKeyOrNull(tok[1]);
                if (cid == null) {
                    return null;
                }
                tok[1] = CidDigest.of(cid);
                converted = true;
            }
        } else if (!isKey(kind, tok[1])) {
            return null;
        }
        Entry parsed = parseEntryTail(kind, tok, drops);
        if (parsed != null && converted) {
            drops[CONVERTED_HEX]++;
        }
        return parsed;
    }

    /** {@link #parseEntry} after the kind + key gate: the millis, origin, label and tail tokens of {@code tok}
     *  ({@code tok[1]} = the entry key already in its digest / source form). */
    private static Entry parseEntryTail(Kind kind, String[] tok, int[] drops) {
        long millis = 0L;
        Origin origin = Origin.OP;
        int i = 2;
        if (i < tok.length && tok[i].matches("[0-9]{1,19}")) {
            try {
                millis = Long.parseLong(tok[i]);
            } catch (NumberFormatException overflow) {
                return null; // 19 digits past Long.MAX_VALUE
            }
            i++;
        }
        boolean millisParsed = i == 3;
        if (i < tok.length) {
            String o = tok[i].toLowerCase(Locale.ROOT);
            if ("op".equals(o)) {
                origin = Origin.OP;
            } else if ("admin".equals(o)) {
                origin = Origin.ADMIN;
            } else {
                return null;
            }
            i++;
        }
        if (i == tok.length) {
            return new Entry(kind, tok[1], millis, origin);
        }
        if (i != 4 || !millisParsed) {
            return null; // a 5th token rides ONLY after BOTH millis and origin
        }
        String label = null;
        int j = 4;
        if (tok[4].indexOf('=') < 0) {
            label = labelOfHexOrNull(tok[4]);
            if (label == null) {
                drops[DROP_LABEL]++;
            }
            j = 5;
        }
        boolean chatCid = kind == Kind.CID && origin == Origin.ADMIN;
        long until = 0L;
        boolean untilSeen = false;
        boolean untilBad = false;
        String src = null;
        boolean srcSeen = false;
        for (; j < tok.length; j++) {
            String k = tok[j];
            if (j >= 7) {
                drops[DROP_TOKEN]++; // past the 7th token: never read
            } else if (k.startsWith("until=")) {
                String v = k.substring(6);
                boolean ok = chatCid && !untilSeen && v.matches("[0-9]{1,19}");
                long parsed = 0L;
                if (ok) {
                    try {
                        parsed = Long.parseLong(v);
                    } catch (NumberFormatException overflow) {
                        ok = false; // 19 digits past Long.MAX_VALUE: counted below
                    }
                }
                if (ok) {
                    until = parsed;
                } else {
                    untilBad = true;
                    drops[DROP_UNTIL]++;
                }
                untilSeen = true;
            } else if (k.startsWith("src=")) {
                String v = k.substring(4);
                // src= on ANY cid line (op lines pair)
                // a loopback pair is dropped, counted (never bar the host's own windows)
                if (kind == Kind.CID && !srcSeen && SourceRanges.isDoorKey(v) && !SourceRanges.isLoopbackKey(v)) {
                    src = v;
                } else {
                    drops[DROP_SRC]++;
                }
                srcSeen = true;
            } else {
                drops[DROP_TOKEN]++;
            }
        }
        return new Entry(kind, tok[1], millis, origin, label, untilBad ? 0L : until, src);
    }

    /** The 5th token → its label, or null (the entry is kept label-less; see {@link #parseEntry}). */
    private static String labelOfHexOrNull(String hex) {
        if (hex.length() > LABEL_MAX_HEX_CHARS) {
            return null;
        }
        String decoded = cidOfKeyOrNull(hex); // even-length lowercase hex + UTF-8 REPORT decode
        if (decoded == null) {
            return null;
        }
        String safe = VoiceMuteCommand.safeLabel(decoded);
        return decoded.equals(safe) ? safe : null;
    }

    /**
     * The pure codec: {@code #} at the (whitespace-stripped) line start = a comment, blank lines
     * skipped, a leading U+FEFF stripped; a malformed line is PRESERVED verbatim and logged ONCE
     * (file + line number, never its content — the rewrite keeps it); a duplicate entry is
     * dropped with one row; entries past {@link #MAX_ENTRIES} are ignored with ONE summary row
     * (they do not survive the next rewrite). Never throws on content.
     */
    public static BanSet parse(List<String> lines, String pathLabel, Consumer<String> log) {
        return parse(lines, pathLabel, log, System.currentTimeMillis());
    }

    /**
     * {@link #parse(List, String, Consumer)} at {@code wallNow}.
     * An entry EXPIRED at {@code wallNow} is dropped BEFORE the cap check (a file of lapsed bans
     * never crowds out a live one) with ONE row {@code bans_file_expired path= dropped=} and the set
     * starts DIRTY (the boot save is owed — the caller queues it off {@link #dirty}); ONE row
     * {@code bans_file_clock_skew path= entries=} when any parsed {@code atMillis > wallNow} (a row
     * ONLY — a stored {@code until} is never shortened or moved); ONE count row each for dropped
     * {@code until=} / {@code src=} / other tail tokens ({@link #parseEntry}) — never the text.
     * ONE row {@code bans_file_converted path= count=} when legacy hex cid keys
     * were converted to their digests (the set starts DIRTY: one boot save); a duplicate key keeps the PERMANENT row
     * over a timed one, else the first.
     * A {@code src} line whose key lies within loopback
     * ({@link SourceRanges#isLoopbackKey}) is no entry: ONE row {@code bans_file_loopback_dropped path= count=}.
     */
    public static BanSet parse(List<String> lines, String pathLabel, Consumer<String> log, long wallNow) {
        BanSet set = new BanSet();
        int overCap = 0;
        int preservedDropped = 0;
        int expired = 0;
        int skewed = 0;
        int loopback = 0;
        int[] drops = new int[DROP_SLOTS];
        java.util.HashMap<String, Integer> keptLine = new java.util.HashMap<>(); // map key -> its kept row's line
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            if (raw == null) {
                continue;
            }
            if (n == 0 && !raw.isEmpty() && raw.charAt(0) == BOM) {
                raw = raw.substring(1);
            }
            String t = raw.strip();
            if (t.isEmpty()) {
                continue;
            }
            Entry e = t.startsWith("#") ? null : parseEntry(t, drops);
            if (e == null) {
                if (!t.startsWith("#")) {
                    String[] head = t.split("[ \\t]+");
                    Kind headKind = kindOrNull(head[0]);
                    // Any src line whose key is no stored key names the forms
                    String why = headKind == Kind.SRC && (head.length < 2 || !isSrcKey(head[1]))
                            ? "src key not in a stored key form - expected " + SRC_KEY_FORM
                            : headKind == Kind.CID && head.length > 1 && isCidKey(head[1])
                                    && cidOfKeyOrNull(head[1]) == null
                                    ? "cid key is hex whose bytes are no client id (it can bar nobody) - expected "
                                            + CID_KEY_FORM
                                    : "not 'cid <h1:key> [millis] [op|admin] [hexlabel] [until=<ms>] [src=<key>]'"
                                            + " or 'src <key> [millis] [op|admin] [hexlabel]'";
                    log.accept("bans_file_line_skipped path=" + pathLabel + " line=" + (n + 1)
                            + " reason=\"" + why + "\" (kept verbatim on rewrite)");
                }
                if (set.preserved.size() < MAX_PRESERVED_LINES) {
                    set.preserved.add(raw);
                } else {
                    preservedDropped++;
                }
                continue;
            }
            if (e.kind() == Kind.SRC && SourceRanges.isLoopbackKey(e.key())) {
                // A loopback src line is never loaded: never enforced, never rewritten
                loopback++;
                continue;
            }
            if (e.atMillis() > wallNow) {
                skewed++;
            }
            String mk = mapKey(e.kind(), e.key());
            Entry kept = set.entries.get(mk);
            if (kept != null) {
                // Two rows of ONE key (a legacy hex row and a digest row of one
                // cid meet here once converted): a permanent ban outranks a timed one, else the first row wins; the
                // winner keeps the first row's place. ONE row naming the dropped line + the key's digest prefix.
                boolean later = e.until() == 0L && kept.until() != 0L;
                int dropped = later ? keptLine.getOrDefault(mk, 0) : n + 1;
                if (later) {
                    set.entries.put(mk, e);
                    keptLine.put(mk, n + 1);
                }
                log.accept("bans_file_line_duplicate path=" + pathLabel + " line=" + dropped
                        + (e.kind() == Kind.CID ? " key=" + CidDigest.body(e.key()).substring(0, 8) : "")
                        + " kept=" + (later ? "permanent" : "first") + " (dropped on rewrite)");
                continue;
            }
            if (e.expired(wallNow)) {
                expired++;
                continue;
            }
            if (set.entries.size() >= MAX_ENTRIES) {
                overCap++;
                continue;
            }
            set.entries.put(mk, e);
            keptLine.put(mk, n + 1);
        }
        if (overCap > 0) {
            log.accept("bans_file_over_cap path=" + pathLabel + " cap=" + MAX_ENTRIES
                    + " ignored=" + overCap + " (dropped on the next rewrite)");
        }
        if (preservedDropped > 0) {
            log.accept("bans_file_preserved_over_cap path=" + pathLabel + " cap="
                    + MAX_PRESERVED_LINES + " dropped=" + preservedDropped);
        }
        if (drops[DROP_LABEL] > 0) {
            // ONE row per load, a count — never the text.
            log.accept("bans_file_label_dropped path=" + pathLabel + " bans_label_dropped="
                    + drops[DROP_LABEL] + " (entries kept; labels cleared on the next rewrite)");
        }
        if (drops[DROP_UNTIL] > 0) {
            log.accept("bans_file_until_dropped path=" + pathLabel + " count=" + drops[DROP_UNTIL]
                    + " (entries kept PERMANENT)");
        }
        if (drops[DROP_SRC] > 0) {
            log.accept("bans_file_src_dropped path=" + pathLabel + " count=" + drops[DROP_SRC]
                    + " (entries kept; no source hold from those tokens)");
        }
        if (loopback > 0) {
            // ONE row per load, a count (a hand-edited file never bars the host)
            log.accept("bans_file_loopback_dropped path=" + pathLabel + " count=" + loopback
                    + " (loopback is never banned; those lines are gone on the next rewrite)");
        }
        if (drops[DROP_TOKEN] > 0) {
            log.accept("bans_file_token_dropped path=" + pathLabel + " count=" + drops[DROP_TOKEN]
                    + " (entries kept; tokens cleared on the next rewrite)");
        }
        if (skewed > 0) {
            log.accept("bans_file_clock_skew path=" + pathLabel + " entries=" + skewed
                    + " (ban time after the wall clock; until kept as stored)");
        }
        if (expired > 0) {
            log.accept("bans_file_expired path=" + pathLabel + " dropped=" + expired + " (save owed)");
            set.dirty = true;
        }
        if (drops[CONVERTED_HEX] > 0) {
            // ONE row per load, a count; the boot save rewrites those rows keyed
            // by the digest (every other field as read), so the next load converts nothing.
            log.accept("bans_file_converted path=" + pathLabel + " count=" + drops[CONVERTED_HEX]
                    + " (hex player keys now stored as fingerprints; save owed)");
            set.dirty = true;
        }
        set.rebuildRangeIndex(); // the parse fills entries directly: the range index follows them once
        return set;
    }

    /**
     * The boot read: ABSENT ⇒ an empty set; present but unreadable, over
     * {@link #MAX_FILE_BYTES}, or not valid UTF-8 ⇒ {@link IllegalArgumentException} naming the
     * path (and the line, for bad bytes) — fail-closed, the {@code .adminpassword} law: a present
     * bans file the server cannot honor must never serve as "nobody banned". ALWAYS prints the
     * boot row {@code bans_file path=… bans=… state=absent|loaded}.
     * ABSENT = {@link Files#notExists} only; a path
     * whose existence cannot be determined (neither exists nor not-exists — an ACL-denied dir)
     * REFUSES boot like an unreadable file, never "nobody banned".
     */
    public static BanSet load(Path file, Consumer<String> log) {
        return load(file, log, System.currentTimeMillis());
    }

    /** {@link #load(Path, Consumer)} whose parse judges
     *  expiry and clock skew at {@code wallNow} ({@link #parse(List, String, Consumer, long)}). */
    public static BanSet load(Path file, Consumer<String> log, long wallNow) {
        Objects.requireNonNull(file, "file");
        if (Files.notExists(file)) {
            log.accept("bans_file path=" + file + " bans=0 state=absent");
            return new BanSet();
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("bans file " + file + " existence CANNOT BE DETERMINED"
                    + " (access denied on the path?) - a bans file the server cannot see is honored"
                    + " fail-closed: fix the permissions or the path");
        }
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("bans file " + file + " exceeds " + MAX_FILE_BYTES
                        + " bytes - fix or delete it (the server refuses to boot on a bans file it"
                        + " cannot honor)");
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("bans file " + file + " exists but is UNREADABLE"
                    + " (a present bans file is honored fail-closed - fix or delete it): " + e, e);
        }
        String text = decodeOrThrow(bytes, file);
        BanSet set = parse(text.lines().toList(), file.toString(), log, wallNow);
        log.accept("bans_file path=" + file + " bans=" + set.size() + " state=loaded preserved="
                + set.preservedCount());
        return set;
    }

    private static String decodeOrThrow(byte[] bytes, Path file) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length + 1);
        CoderResult r = decoder.decode(in, out, true);
        if (!r.isError()) {
            r = decoder.flush(out);
        }
        if (r.isError()) {
            int line = 1;
            for (int i = 0; i < in.position() && i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    line++;
                }
            }
            throw new IllegalArgumentException("bans file " + file + " line " + line
                    + " is not valid UTF-8 - fix or delete the file (the server refuses to boot on"
                    + " a bans file it cannot read exactly)");
        }
        out.flip();
        return out.toString();
    }
}
