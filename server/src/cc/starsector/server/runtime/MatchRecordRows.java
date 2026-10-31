package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;

/**
 * The APPLIED-INPUT record: row kinds a recording serve writes into the SAME reel as its DOWN / UP
 * records, each at the sim-thread site where the simulation CONSUMES the input (never where it
 * arrives), so an offline re-simulation can feed the sim exactly what it saw, tick for tick.
 *
 *
 * <p>The container framing is the sink's own (u8 kind, s64 tick, s64 epoch-ms, s32 length, payload);
 * every row payload here opens with the common 10-byte prefix {@code u32 magic · u8 rowVersion ·
 * u8 band · s32 round} so a reader refuses a misplaced payload by its own magic. The TICK rides the
 * container stamp: GATE rows stamp -1, PRE rows the tick about to be served ({@code lastSimTick + 1}),
 * POST rows the tick just served, WRAP rows the round's last served tick. Big-endian throughout; every
 * text value is modified UTF-8 capped at {@link #UTF_MAX_BYTES} encoded bytes, TRUNCATED and never
 * thrown (a throw on the record path would cost the match its record, or the match itself), and a
 * null text writes "". Count / ordinal fields SATURATE at their width; every other u8 field is
 * bounded by its domain (seats 0..255 = the descriptor's seat cap, enum codes &lt; 8).
 *
 * <p>Kinds 9, 13, 15 and 16 are RESERVED here (a magic and a number, no codec yet); kind 17 (END) is the
 * sink's own trailer. Every decoder re-checks magic + rowVersion + band and throws
 * {@link IllegalArgumentException} naming the kind on a mismatch, a truncation or a trailing byte.
 */
public final class MatchRecordRows {

    public static final byte KIND_TAKES = 3;
    public static final byte KIND_SESS = 4;
    public static final byte KIND_CMD = 5;
    public static final byte KIND_FLYIN = 6;
    public static final byte KIND_SUIC = 7;
    public static final byte KIND_VSUB = 8;
    public static final byte KIND_PROMO = 9;
    public static final byte KIND_FSPN = 10;
    public static final byte KIND_WRAP = 11;
    public static final byte KIND_ROSTER = 12;
    public static final byte KIND_VACATE = 13;
    public static final byte KIND_TRANSFER = 14;
    public static final byte KIND_MODULE = 15;
    public static final byte KIND_GRACE = 16;
    public static final byte KIND_END = 17;
    /** The row-kind range the sink reserves (1 = DOWN and 2 = UP keep their bytes and payloads). */
    public static final int KIND_ROW_MIN = 3;
    public static final int KIND_ROW_MAX = 31;

    public static final int MAGIC_TAKES = 0x4343544B;    // "CCTK"
    public static final int MAGIC_SESS = 0x43435345;     // "CCSE"
    public static final int MAGIC_CMD = 0x4343434D;      // "CCCM"
    public static final int MAGIC_FLYIN = 0x43434649;    // "CCFI"
    public static final int MAGIC_SUIC = 0x43435355;     // "CCSU"
    public static final int MAGIC_VSUB = 0x43435653;     // "CCVS"
    public static final int MAGIC_PROMO = 0x43435052;    // "CCPR"
    public static final int MAGIC_FSPN = 0x43434653;     // "CCFS"
    public static final int MAGIC_WRAP = 0x43435750;     // "CCWP"
    public static final int MAGIC_ROSTER = 0x4343524F;   // "CCRO"
    public static final int MAGIC_VACATE = 0x43435641;   // "CCVA"
    public static final int MAGIC_TRANSFER = 0x43435452; // "CCTR"
    public static final int MAGIC_MODULE = 0x43434D4F;   // "CCMO"
    public static final int MAGIC_GRACE = 0x43434752;    // "CCGR"
    public static final int MAGIC_END = 0x4343454E;      // "CCEN"

    public static final int ROW_VERSION = 1;
    public static final byte BAND_GATE = 0;
    public static final byte BAND_PRE = 1;
    public static final byte BAND_POST = 2;
    public static final byte BAND_WRAP = 3;
    public static final int PREFIX_BYTES = 10;
    public static final int UTF_MAX_BYTES = 255;

    /** TAKES take kinds: PRISTINE = the door's one re-serve of a folded frame (no fresh deposit);
     *  FOLDED = a fresh deposit with the window's edge bits OR-ed on; FRESH = a fresh deposit as sent. */
    public static final int TAKE_PRISTINE = 1;
    public static final int TAKE_FOLDED = 2;
    public static final int TAKE_FRESH = 3;
    public static final int SESS_LIVE = 1;
    public static final int SESS_DEAD = 2;
    public static final int SESS_FLAG_OBSERVER = 1;
    public static final int SESS_FLAG_ADMIN = 2;
    /** SESS flag bits 2-3 = the seat's HELLO MagicLib report bits ({@code Handshake13.FLAG_MAGIC_UP} 0x08 → bit 2,
     *  {@code FLAG_MAGIC_REPLICA} 0x10 → bit 3), recorded so a re-sim re-mints the seat with the bits the mark's
     *  FOLLOW predicate ({@code MagicSeamFollow}) read live — without them a FOLLOW match re-simmed with FOLLOW off and
     *  was admitted. An older reel reads 0 = no report, which is what it recorded. */
    public static final int SESS_FLAG_MAGIC_UP = 4;
    public static final int SESS_FLAG_MAGIC_REPLICA = 8;
    public static final int FLYIN_HULL_DEPLOY = 1;
    public static final int FLYIN_MODULE_CLAIM = 2;
    public static final int FLYIN_DEFERRED_TRANSFER = 3;
    public static final int SUIC_REFUSED_NO_HOOK = 1;
    public static final int SUIC_REFUSED_MODULE_MOUNTED = 2;
    public static final int SUIC_KILLED = 3;
    public static final int SUIC_NOT_KILLED = 4;
    public static final int SUIC_SKIPPED_NOT_MOUNTED = 5;
    /** TRANSFER verbs: BEGIN = a command-transfer begin toward a hull; RETURN_HOME = a self-target while a
     *  flight exists (it parks a return-home request for the serve's drain).
     * */
    public static final int TRANSFER_VERB_BEGIN = 1;
    public static final int TRANSFER_VERB_RETURN_HOME = 2;
    /** TRANSFER outcomes: a fresh flight minted; a mid-flight retarget parked; denied (the deny reason rides
     *  beside it, 0 for an informational deny); a return-home request parked. */
    public static final int TRANSFER_GRANTED_FRESH = 1;
    public static final int TRANSFER_GRANTED_RETARGET = 2;
    public static final int TRANSFER_DENIED = 3;
    public static final int TRANSFER_RETURN_HOME_PARKED = 4;

    /** Where a lane that holds the round + tick hands its rows (the serve passes one over its door). */
    public interface RowSink {
        void row(byte kind, byte[] payload, long tick);
    }

    /** The u32 magic of row kind {@code kind} (reserved kinds included), 0 for a non-row kind. */
    public static int magicOf(int kind) {
        switch (kind) {
            case KIND_TAKES: return MAGIC_TAKES;
            case KIND_SESS: return MAGIC_SESS;
            case KIND_CMD: return MAGIC_CMD;
            case KIND_FLYIN: return MAGIC_FLYIN;
            case KIND_SUIC: return MAGIC_SUIC;
            case KIND_VSUB: return MAGIC_VSUB;
            case KIND_PROMO: return MAGIC_PROMO;
            case KIND_FSPN: return MAGIC_FSPN;
            case KIND_WRAP: return MAGIC_WRAP;
            case KIND_ROSTER: return MAGIC_ROSTER;
            case KIND_VACATE: return MAGIC_VACATE;
            case KIND_TRANSFER: return MAGIC_TRANSFER;
            case KIND_MODULE: return MAGIC_MODULE;
            case KIND_GRACE: return MAGIC_GRACE;
            case KIND_END: return MAGIC_END;
            default: return 0;
        }
    }

    /** The take kind of the session's LAST take: {@code takenStampTick} = its deposit stamp
     *  ({@code IntentTakeAge.NOT_FRESH} after a pristine re-take), {@code foldMask} = the edge bits
     *  the take OR-ed on (0 = none). */
    public static int takeKindOf(long takenStampTick, int foldMask) {
        if (takenStampTick == IntentTakeAge.NOT_FRESH) {
            return TAKE_PRISTINE;
        }
        return foldMask != 0 ? TAKE_FOLDED : TAKE_FRESH;
    }

    /** SESS flags: bit0 observer, bit1 admin; no HELLO MagicLib report (bits 2-3 clear). */
    public static int sessFlags(boolean observer, boolean admin) {
        return sessFlags(observer, admin, 0);
    }

    /** SESS flags: bit0 observer, bit1 admin, bits 2-3 = {@code helloMagicBits & 0x18 >> 1} (the seat's HELLO 0x08 /
     *  0x10 reports; every other HELLO bit is dropped here). */
    public static int sessFlags(boolean observer, boolean admin, int helloMagicBits) {
        return (observer ? SESS_FLAG_OBSERVER : 0) | (admin ? SESS_FLAG_ADMIN : 0)
                | ((helloMagicBits & (Handshake13.FLAG_MAGIC_UP | Handshake13.FLAG_MAGIC_REPLICA)) >> 1);
    }

    /** The HELLO MagicLib report bits (0x08 / 0x10) a SESS row's flags recorded; 0 on an older reel. */
    public static int sessHelloMagicBits(int sessFlags) {
        return (sessFlags & (SESS_FLAG_MAGIC_UP | SESS_FLAG_MAGIC_REPLICA)) << 1;
    }

    /** The prefix's band byte (0..3) of a row payload. */
    public static int bandOf(byte[] payload) {
        requirePrefix(payload);
        return payload[5] & 0xFF;
    }

    /** The prefix's s32 round of a row payload. */
    public static int roundOf(byte[] payload) {
        requirePrefix(payload);
        return ((payload[6] & 0xFF) << 24) | ((payload[7] & 0xFF) << 16)
                | ((payload[8] & 0xFF) << 8) | (payload[9] & 0xFF);
    }

    private static void requirePrefix(byte[] payload) {
        if (payload == null || payload.length < PREFIX_BYTES) {
            throw new IllegalArgumentException("row payload shorter than the "
                    + PREFIX_BYTES + "-byte prefix");
        }
    }

    /** {@code s} as the bounded writer keeps it: null = "", else its longest prefix whose modified
     *  UTF-8 form fits {@link #UTF_MAX_BYTES} bytes, never ending on a split surrogate pair. */
    public static String boundUtf(String s) {
        String v = s == null ? "" : s;
        return v.substring(0, keptChars(v));
    }

    /** The modified UTF-8 byte length of {@code s} (null = 0). */
    public static int utfEncodedLength(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            n += utfLen(s.charAt(i));
        }
        return n;
    }

    private static int utfLen(char c) {
        return (c >= 0x0001 && c <= 0x007F) ? 1 : (c <= 0x07FF ? 2 : 3);
    }

    private static int keptChars(String v) {
        int bytes = 0;
        int keep = 0;
        while (keep < v.length()) {
            int n = utfLen(v.charAt(keep));
            if (bytes + n > UTF_MAX_BYTES) {
                break;
            }
            bytes += n;
            keep++;
        }
        if (keep < v.length() && keep > 0 && Character.isHighSurrogate(v.charAt(keep - 1))) {
            keep--;
        }
        return keep;
    }

    // ------------------------------------------------------------------ TAKES (3)

    /** One take: the seat (1..255), its take kind, the OR-ed edge bits (0 unless FOLDED), whether the
     *  serve STAGED it (false = refused: unmounted, transition pending, or tick 0), and the frame
     *  verbatim as {@code PilotIntentWireCodec.encodeFrame} wrote it. */
    public record TakeEntry(int seat, int takeKind, int foldMask, boolean staged, byte[] frame) {
        public TakeEntry {
            Objects.requireNonNull(frame, "frame");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TakeEntry t && t.seat == seat && t.takeKind == takeKind
                    && t.foldMask == foldMask && t.staged == staged && Arrays.equals(t.frame, frame);
        }

        @Override
        public int hashCode() {
            return Objects.hash(seat, takeKind, foldMask, staged) * 31 + Arrays.hashCode(frame);
        }

        @Override
        public String toString() {
            return "TakeEntry[seat=" + seat + ", kind=" + takeKind + ", fold=" + foldMask + ", staged="
                    + staged + ", frameLen=" + frame.length + "]";
        }
    }

    /** One drained fire declaration: the seat and the frame verbatim as
     *  {@code FireDischargeDeclarationCodec.encodeFrame} wrote it. */
    public record DeclEntry(int seat, byte[] frame) {
        public DeclEntry {
            Objects.requireNonNull(frame, "frame");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DeclEntry d && d.seat == seat && Arrays.equals(d.frame, frame);
        }

        @Override
        public int hashCode() {
            return seat * 31 + Arrays.hashCode(frame);
        }

        @Override
        public String toString() {
            return "DeclEntry[seat=" + seat + ", frameLen=" + frame.length + "]";
        }
    }

    /** A TAKES body: {@code gateOrdinal} 0 = in-frame, else the 1-based round-start gate pass;
     *  takes ascending by seat; decls in poll order within ascending seat. */
    public record TakesRow(int gateOrdinal, List<TakeEntry> takes, List<DeclEntry> decls) {
        public TakesRow {
            takes = List.copyOf(takes);
            decls = List.copyOf(decls);
        }
    }

    /** Encode a whole row through a fresh {@link TakesBuilder} (canonical order; off the frame path). */
    public static byte[] encodeTakes(TakesRow row, byte band, int round) {
        TakesBuilder b = new TakesBuilder();
        for (TakeEntry t : row.takes()) {
            b.addTake(t.seat(), t.takeKind(), t.foldMask(), t.staged(), t.frame());
        }
        for (DeclEntry d : row.decls()) {
            b.addDecl(d.seat(), d.frame());
        }
        return b.encode(band, round, row.gateOrdinal());
    }

    public static TakesRow decodeTakes(byte[] payload) {
        In in = new In(payload, KIND_TAKES, "TAKES");
        int ordinal = in.u16();
        int count = in.u8();
        List<TakeEntry> takes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int seat = in.u8();
            int kind = in.u8();
            int mask = in.i32();
            boolean staged = in.bool();
            takes.add(new TakeEntry(seat, kind, mask, staged, in.bytes(in.u8())));
        }
        int declCount = in.u16();
        List<DeclEntry> decls = new ArrayList<>(declCount);
        for (int i = 0; i < declCount; i++) {
            int seat = in.u8();
            decls.add(new DeclEntry(seat, in.bytes(in.u8())));
        }
        in.end();
        return new TakesRow(ordinal, takes, decls);
    }

    /**
     * The per-frame TAKES builder the serve holds as a FIELD (allocated once at boot, never resolved
     * first on a frame): {@link #reset} before the take loop, {@link #addTake} / {@link #addDecl} at
     * each take and poll, {@link #encode} once after the loop = ONE {@code byte[]} sized exactly.
     * Entries are canonical ASCENDING BY SEAT whatever order the live set took them in (a seat-indexed
     * slot table, no sort); decls keep poll order within their seat (a stable counting pass).
     * Single-threaded (the sim thread).
     */
    public static final class TakesBuilder {
        public static final int CAPACITY = 256;
        public static final int MAX_DECLS = 0xFFFF;

        private final byte[][] frameBySeat = new byte[CAPACITY][];
        private final byte[] kindBySeat = new byte[CAPACITY];
        private final int[] maskBySeat = new int[CAPACITY];
        private final boolean[] stagedBySeat = new boolean[CAPACITY];
        private final int[] addedSeats = new int[CAPACITY];
        private int takeCount;
        private int[] declSeats = new int[16];
        private byte[][] declFrames = new byte[16][];
        private int[] declOrder = new int[16];
        private final int[] seatStarts = new int[CAPACITY + 1];
        private int declCount;
        private long declsDropped;

        public void reset() {
            for (int i = 0; i < takeCount; i++) {
                frameBySeat[addedSeats[i]] = null;
            }
            takeCount = 0;
            Arrays.fill(declFrames, 0, declCount, null);
            declCount = 0;
        }

        /** Add {@code seat}'s take. THROWS on a seat outside 0..255, a second take for the same seat
         *  (one take per seat per frame is the door's law), a full table or a frame over 255 bytes. */
        public void addTake(int seat, int takeKind, int foldMask, boolean staged, byte[] frame) {
            requireSeat(seat);
            requireFrame(frame);
            if (frameBySeat[seat] != null) {
                throw new IllegalStateException("TAKES: seat " + seat + " already took this frame");
            }
            if (takeCount == 0xFF) {
                throw new IllegalStateException("TAKES: 255 takes already this frame");
            }
            frameBySeat[seat] = frame;
            kindBySeat[seat] = (byte) takeKind;
            maskBySeat[seat] = foldMask;
            stagedBySeat[seat] = staged;
            addedSeats[takeCount++] = seat;
        }

        /** Add one polled declaration of {@code seat}. Past {@link #MAX_DECLS} in one frame the
         *  declaration is COUNTED into {@link #declsDropped} and left out (the u16 count's bound). */
        public void addDecl(int seat, byte[] frame) {
            requireSeat(seat);
            requireFrame(frame);
            if (declCount == MAX_DECLS) {
                declsDropped++;
                return;
            }
            if (declCount == declSeats.length) {
                int grown = Math.min(MAX_DECLS, declCount * 2);
                declSeats = Arrays.copyOf(declSeats, grown);
                declFrames = Arrays.copyOf(declFrames, grown);
                declOrder = Arrays.copyOf(declOrder, grown);
            }
            declSeats[declCount] = seat;
            declFrames[declCount] = frame;
            declCount++;
        }

        public int takeCount() {
            return takeCount;
        }

        public int declCount() {
            return declCount;
        }

        /** Declarations left out past {@link #MAX_DECLS} in one frame, over this builder's life. */
        public long declsDropped() {
            return declsDropped;
        }

        /** The record: prefix + body, ONE exactly-sized array; {@code gateOrdinal} saturates at 0xFFFF. */
        public byte[] encode(byte band, int round, int gateOrdinal) {
            int size = PREFIX_BYTES + 2 + 1 + 2;
            for (int i = 0; i < takeCount; i++) {
                size += 8 + frameBySeat[addedSeats[i]].length;
            }
            for (int i = 0; i < declCount; i++) {
                size += 2 + declFrames[i].length;
            }
            byte[] b = new byte[size];
            int at = putPrefix(b, MAGIC_TAKES, band, round);
            at = putU16(b, at, Math.max(0, Math.min(0xFFFF, gateOrdinal)));
            at = putU8(b, at, takeCount);
            if (takeCount > 0) {
                for (int seat = 0; seat < CAPACITY; seat++) {
                    byte[] frame = frameBySeat[seat];
                    if (frame == null) {
                        continue;
                    }
                    at = putU8(b, at, seat);
                    at = putU8(b, at, kindBySeat[seat] & 0xFF);
                    at = putI32(b, at, maskBySeat[seat]);
                    at = putU8(b, at, stagedBySeat[seat] ? 1 : 0);
                    at = putU8(b, at, frame.length);
                    System.arraycopy(frame, 0, b, at, frame.length);
                    at += frame.length;
                }
            }
            at = putU16(b, at, declCount);
            if (declCount > 0) {
                Arrays.fill(seatStarts, 0);
                for (int i = 0; i < declCount; i++) {
                    seatStarts[declSeats[i] + 1]++;
                }
                for (int s = 1; s <= CAPACITY; s++) {
                    seatStarts[s] += seatStarts[s - 1];
                }
                for (int i = 0; i < declCount; i++) {
                    declOrder[seatStarts[declSeats[i]]++] = i;
                }
                for (int k = 0; k < declCount; k++) {
                    int i = declOrder[k];
                    at = putU8(b, at, declSeats[i]);
                    at = putU8(b, at, declFrames[i].length);
                    System.arraycopy(declFrames[i], 0, b, at, declFrames[i].length);
                    at += declFrames[i].length;
                }
            }
            return b;
        }

        private static void requireSeat(int seat) {
            if (seat < 0 || seat >= CAPACITY) {
                throw new IllegalArgumentException("TAKES: seat " + seat + " outside 0..255");
            }
        }

        private static void requireFrame(byte[] frame) {
            Objects.requireNonNull(frame, "frame");
            if (frame.length > 0xFF) {
                throw new IllegalArgumentException("TAKES: frame of " + frame.length + " bytes > 255");
            }
        }
    }

    // ------------------------------------------------------------------ SESS (4)

    /** A session edge: event LIVE / DEAD; the SIGNED session id; seat 0 + ship 0 = observer; the
     *  departure reason is a server token (DEAD only); the variant override is the seat's pick at
     *  LIVE ("" none); {@code keymapLo} / {@code keymapHi} = the seat's HELLO keymap packed (control
     *  bytes 0..7 and 8..15), recorded so a re-sim answers the seat's redirected key reads as it ran —
     *  appended in place, an older reel reads 0/0 (= the serve's own map, which is what it recorded).
     *  Never the client id (player text). Main-written */
    public record SessRow(int event, long sessionId, int seat, long shipId, int flags,
            String departureReason, String variantOverride, long keymapLo, long keymapHi) {
    }

    public static byte[] encodeSess(SessRow r, byte band, int round) {
        Out o = new Out(MAGIC_SESS, band, round, 80);
        o.u8(r.event());
        o.i64(r.sessionId());
        o.u8(r.seat());
        o.i64(r.shipId());
        o.u8(r.flags());
        o.utf(r.departureReason());
        o.utf(r.variantOverride());
        o.i64(r.keymapLo());
        o.i64(r.keymapHi());
        return o.toBytes();
    }

    public static SessRow decodeSess(byte[] payload) {
        In in = new In(payload, KIND_SESS, "SESS");
        int event = in.u8();
        long sessionId = in.i64();
        int seat = in.u8();
        long shipId = in.i64();
        int flags = in.u8();
        String reason = in.utf();
        String override = in.utf();
        long lo = 0L, hi = 0L;
        if (in.remaining() >= 16) {
            lo = in.i64();
            hi = in.i64();
        }
        SessRow r = new SessRow(event, sessionId, seat, shipId, flags, reason, override, lo, hi);
        in.end();
        return r;
    }

    // ------------------------------------------------------------------ CMD (5)

    /** One operator / admin command at its apply: the verb NAME (never the ordinal), its seat, speed,
     *  auto flag, receipt session (0 = none) and its text (bounded; player text is data in a reel). */
    public record CmdRow(String verbName, int seatId, float simSpeed, boolean autoOn,
            long receiptSessionId, String text) {
    }

    public static byte[] encodeCmd(CmdRow r, byte band, int round) {
        Out o = new Out(MAGIC_CMD, band, round, 64);
        o.utf(r.verbName());
        o.i32(r.seatId());
        o.f32(r.simSpeed());
        o.u8(r.autoOn() ? 1 : 0);
        o.i64(r.receiptSessionId());
        o.utf(r.text());
        return o.toBytes();
    }

    public static CmdRow decodeCmd(byte[] payload) {
        In in = new In(payload, KIND_CMD, "CMD");
        CmdRow r = new CmdRow(in.utf(), in.i32(), in.f32(), in.bool(), in.i64(), in.utf());
        in.end();
        return r;
    }

    // ------------------------------------------------------------------ FLYIN (6)

    /** One fly-in decision: path 1 = hull deploy (the inputs read ONCE and handed to the deploy, then
     *  its result), 2 = a module claim consumed it, 3 = deferred for a transfer (first defer only;
     *  deferTarget = the hull it flies toward). Paths 2 / 3 write the output fields as 0 / "";
     *  sideIn is -1 off path 1; customBodyCrc = the accepted custom's body CRC, 0 for a stock pick. */
    public record FlyInRow(int seat, int path, String overrideIn, int sideIn, long deferTarget,
            long wireId, String stableId, String variantId, String descriptorVariantId, int sideOut,
            String shipName, int crPerMille, long customBodyCrc) {
    }

    public static byte[] encodeFlyIn(FlyInRow r, byte band, int round) {
        Out o = new Out(MAGIC_FLYIN, band, round, 96);
        o.u8(r.seat());
        o.u8(r.path());
        o.utf(r.overrideIn());
        o.i32(r.sideIn());
        o.i64(r.deferTarget());
        o.i64(r.wireId());
        o.utf(r.stableId());
        o.utf(r.variantId());
        o.utf(r.descriptorVariantId());
        o.i32(r.sideOut());
        o.utf(r.shipName());
        o.i32(r.crPerMille());
        o.i64(r.customBodyCrc());
        return o.toBytes();
    }

    public static FlyInRow decodeFlyIn(byte[] payload) {
        In in = new In(payload, KIND_FLYIN, "FLYIN");
        FlyInRow r = new FlyInRow(in.u8(), in.u8(), in.utf(), in.i32(), in.i64(), in.i64(), in.utf(),
                in.utf(), in.utf(), in.i32(), in.utf(), in.i32(), in.i64());
        in.end();
        return r;
    }

    // ------------------------------------------------------------------ SUIC (7)

    /** One drained /suicide request and its outcome ({@code SUIC_*}). */
    public record SuicRow(int seat, long shipId, int outcome) {
    }

    public static byte[] encodeSuic(SuicRow r, byte band, int round) {
        Out o = new Out(MAGIC_SUIC, band, round, 24);
        o.u8(r.seat());
        o.i64(r.shipId());
        o.u8(r.outcome());
        return o.toBytes();
    }

    public static SuicRow decodeSuic(byte[] payload) {
        In in = new In(payload, KIND_SUIC, "SUIC");
        SuicRow r = new SuicRow(in.u8(), in.i64(), in.u8());
        in.end();
        return r;
    }

    // ------------------------------------------------------------------ VSUB (8)

    /** One custom-variant submission at its verdict: the SIGNED session id, seat, the client's submit
     *  sequence, the minted canonical id, the body CRC computed at deposit, the engine verdict
     *  (reasonToken "" when accepted), and the replay tail — the author key the ledger gated under plus
     *  the submit frame verbatim as the client's codec framed it ({@code body}; EMPTY = written before
     *  the tail existed or the frame failed to encode, so the row cannot be re-admitted by a replay).
     *  {@link #toString} prints only the body LENGTH: the key is player-chosen text and the body holds
     *  the player's variant. */
    public record VsubRow(long sessionId, int seat, int submitSeq, String canonicalId, long bodyCrc,
            boolean accepted, String reasonToken, String authorKey, byte[] body) {
        private static final byte[] NO_BODY = new byte[0];

        public VsubRow {
            authorKey = authorKey == null ? "" : authorKey;
            body = body == null ? NO_BODY : body;
        }

        /** The pre-tail arity: a legacy row (empty author key, no body). */
        public VsubRow(long sessionId, int seat, int submitSeq, String canonicalId, long bodyCrc,
                boolean accepted, String reasonToken) {
            this(sessionId, seat, submitSeq, canonicalId, bodyCrc, accepted, reasonToken, "", NO_BODY);
        }

        public boolean hasBody() {
            return body.length > 0;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VsubRow r && r.sessionId == sessionId && r.seat == seat
                    && r.submitSeq == submitSeq && r.canonicalId.equals(canonicalId) && r.bodyCrc == bodyCrc
                    && r.accepted == accepted && r.reasonToken.equals(reasonToken)
                    && r.authorKey.equals(authorKey) && Arrays.equals(r.body, body);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionId, seat, submitSeq, canonicalId, bodyCrc, accepted, reasonToken, authorKey)
                    * 31 + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "VsubRow[sessionId=" + sessionId + ", seat=" + seat + ", submitSeq=" + submitSeq
                    + ", canonicalId=" + canonicalId + ", bodyCrc=" + bodyCrc + ", accepted=" + accepted
                    + ", reasonToken=" + reasonToken + ", bodyLen=" + body.length + "]";
        }
    }

    /** A body past the submit frame cap is written as length 0 (the record path never throws): the
     *  live decode bounds every frame it admits, so only drift reaches this branch. */
    public static byte[] encodeVsub(VsubRow r, byte band, int round) {
        byte[] body = r.body().length > VariantSubmitFrameCodec.MAX_FRAME_BYTES ? VsubRow.NO_BODY : r.body();
        Out o = new Out(MAGIC_VSUB, band, round, 64 + r.authorKey().length() + body.length);
        o.i64(r.sessionId());
        o.u8(r.seat());
        o.i32(r.submitSeq());
        o.utf(r.canonicalId());
        o.i64(r.bodyCrc());
        o.u8(r.accepted() ? 1 : 0);
        o.utf(r.reasonToken());
        o.utf(r.authorKey());
        o.u16(body.length);
        o.raw(body);
        return o.toBytes();
    }

    /** The seven fixed fields, then the tail ONLY while bytes remain (a legacy row ends after the
     *  verdict and decodes to an empty key + no body); {@link In#end} still refuses trailing bytes. */
    public static VsubRow decodeVsub(byte[] payload) {
        In in = new In(payload, KIND_VSUB, "VSUB");
        long sessionId = in.i64();
        int seat = in.u8();
        int submitSeq = in.i32();
        String canonicalId = in.utf();
        long bodyCrc = in.i64();
        boolean accepted = in.bool();
        String reasonToken = in.utf();
        String authorKey = "";
        byte[] body = VsubRow.NO_BODY;
        if (in.remaining() > 0) {
            authorKey = in.utf();
            int len = in.u16();
            if (len > VariantSubmitFrameCodec.MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("VSUB row: body length " + len + " past the "
                        + VariantSubmitFrameCodec.MAX_FRAME_BYTES + "-byte frame cap");
            }
            body = in.bytes(len);
        }
        in.end();
        return new VsubRow(sessionId, seat, submitSeq, canonicalId, bodyCrc, accepted, reasonToken, authorKey, body);
    }

    // ------------------------------------------------------------------ FSPN (10)

    /** One engine-born spawn at the growth drain: the fighter's wire id + stable key, its carrier
     *  (wire 0 = none, seat -1 = none / a wave carrier, key "" = none), the bay index, the 0-based
     *  ordinal of this ADDED row within its drain (saturates at 0xFFFF), side and variant. */
    public record FspnRow(long fighterWireId, String stableKey, long carrierWireId, int carrierSeat,
            String carrierStableKey, int bay, int ordinal, int side, String variantId) {
    }

    public static byte[] encodeFspn(FspnRow r, byte band, int round) {
        Out o = new Out(MAGIC_FSPN, band, round, 96);
        o.i64(r.fighterWireId());
        o.utf(r.stableKey());
        o.i64(r.carrierWireId());
        o.i32(r.carrierSeat());
        o.utf(r.carrierStableKey());
        o.i32(r.bay());
        o.u16(r.ordinal());
        o.i32(r.side());
        o.utf(r.variantId());
        return o.toBytes();
    }

    public static FspnRow decodeFspn(byte[] payload) {
        In in = new In(payload, KIND_FSPN, "FSPN");
        FspnRow r = new FspnRow(in.i64(), in.utf(), in.i64(), in.i32(), in.utf(), in.i32(), in.u16(),
                in.i32(), in.utf());
        in.end();
        return r;
    }

    // ------------------------------------------------------------------ WRAP (11)

    /** One deployable seat's pick at the wrap ("" = none: the descriptor row). */
    public record WrapPick(int seat, String pick) {
    }

    /** The wrap's pick snapshot: the round it opens and the deployable seats ascending. */
    public record WrapRow(int nextRound, List<WrapPick> picks) {
        public WrapRow {
            picks = List.copyOf(picks);
        }
    }

    /** The WRAP row of a wrap: {@code seats} ascending (a sorted set), each with its snapshotted pick. */
    public static WrapRow wrapOf(int nextRound, SortedSet<Integer> seats, Map<Integer, String> picks) {
        List<WrapPick> list = new ArrayList<>(seats.size());
        for (Integer seat : seats) {
            String pick = picks.get(seat);
            list.add(new WrapPick(seat, pick == null ? "" : pick));
        }
        return new WrapRow(nextRound, list);
    }

    public static byte[] encodeWrap(WrapRow r, byte band, int round) {
        Out o = new Out(MAGIC_WRAP, band, round, 16 + 24 * r.picks().size());
        o.i32(r.nextRound());
        int count = Math.min(0xFF, r.picks().size());
        o.u8(count);
        for (int i = 0; i < count; i++) {
            o.u8(r.picks().get(i).seat());
            o.utf(r.picks().get(i).pick());
        }
        return o.toBytes();
    }

    public static WrapRow decodeWrap(byte[] payload) {
        In in = new In(payload, KIND_WRAP, "WRAP");
        int next = in.i32();
        int count = in.u8();
        List<WrapPick> picks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            picks.add(new WrapPick(in.u8(), in.utf()));
        }
        in.end();
        return new WrapRow(next, picks);
    }

    // ------------------------------------------------------------------ ROSTER (12)

    /** The live-roster ratio the wave stage consumed (the supplier's return value) + the occupied
     *  seat count read beside it (informational; saturates at 255). */
    public record RosterRow(double ratio, int occupiedCount) {
    }

    public static byte[] encodeRoster(RosterRow r, byte band, int round) {
        Out o = new Out(MAGIC_ROSTER, band, round, 24);
        o.f64(r.ratio());
        o.u8(r.occupiedCount());
        return o.toBytes();
    }

    public static RosterRow decodeRoster(byte[] payload) {
        In in = new In(payload, KIND_ROSTER, "ROSTER");
        RosterRow r = new RosterRow(in.f64(), in.u8());
        in.end();
        return r;
    }

    // ------------------------------------------------------------------ TRANSFER (14)

    /**
     * One command-transfer input the arbiter took from OFF the sim thread, with the verdict it gave:
     * {@code verb} ({@code TRANSFER_VERB_*}); {@code seat} (u8, 1..255 for a pilot); {@code targetHullId}
     * = the target hull's wire id (&gt; 0) for a begin, and for a return-home the flight target it found
     * (0 = none); {@code hullLabel} = the caller-resolved ship label (text: recorded, never printed);
     * {@code nowTick} = the caller's sim tick (signed; -1 for a return-home, which takes none);
     * {@code outcome} ({@code TRANSFER_*}); {@code denyReason} = the {@code CommandTransferFrameCodec}
     * reason byte (0 = none, also on an informational deny).
     *
     *
     */
    public record TransferRow(int verb, int seat, long targetHullId, String hullLabel, long nowTick, int outcome,
            int denyReason) {
    }

    public static byte[] encodeTransfer(TransferRow r, byte band, int round) {
        Out o = new Out(MAGIC_TRANSFER, band, round, 48);
        o.u8(r.verb());
        o.u8(r.seat());
        o.i64(r.targetHullId());
        o.utf(r.hullLabel());
        o.i64(r.nowTick());
        o.u8(r.outcome());
        o.u8(r.denyReason());
        return o.toBytes();
    }

    public static TransferRow decodeTransfer(byte[] payload) {
        In in = new In(payload, KIND_TRANSFER, "TRANSFER");
        TransferRow r = new TransferRow(in.u8(), in.u8(), in.i64(), in.utf(), in.i64(), in.u8(), in.u8());
        in.end();
        return r;
    }

    // ------------------------------------------------------------------ byte plumbing

    private static int putPrefix(byte[] b, int magic, byte band, int round) {
        int at = putI32(b, 0, magic);
        at = putU8(b, at, ROW_VERSION);
        at = putU8(b, at, band);
        return putI32(b, at, round);
    }

    private static int putU8(byte[] b, int at, int v) {
        b[at] = (byte) v;
        return at + 1;
    }

    private static int putU16(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 8);
        b[at + 1] = (byte) v;
        return at + 2;
    }

    private static int putI32(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
        return at + 4;
    }

    /** A growable big-endian writer for the rare rows (no checked exception anywhere on the path). */
    private static final class Out {
        private byte[] b;
        private int n;

        Out(int magic, byte band, int round, int initial) {
            b = new byte[Math.max(PREFIX_BYTES + 8, initial)];
            n = putPrefix(b, magic, band, round);
        }

        private void room(int k) {
            if (n + k > b.length) {
                b = Arrays.copyOf(b, Math.max(b.length * 2, n + k));
            }
        }

        /** Saturating: the domain bounds every caller's value to 0..255. */
        void u8(int v) {
            room(1);
            n = putU8(b, n, Math.max(0, Math.min(0xFF, v)));
        }

        /** Saturating at 0..0xFFFF. */
        void u16(int v) {
            room(2);
            n = putU16(b, n, Math.max(0, Math.min(0xFFFF, v)));
        }

        void i32(int v) {
            room(4);
            n = putI32(b, n, v);
        }

        void i64(long v) {
            room(8);
            n = putI32(b, n, (int) (v >>> 32));
            n = putI32(b, n, (int) v);
        }

        /** {@code DataOutputStream.writeFloat} bits (NaN canonical). */
        void f32(float v) {
            i32(Float.floatToIntBits(v));
        }

        /** {@code DataOutputStream.writeDouble} bits (NaN canonical). */
        void f64(double v) {
            i64(Double.doubleToLongBits(v));
        }

        /** {@code writeUTF} framing (u16 length + modified UTF-8), bounded by {@link #boundUtf}. */
        void utf(String s) {
            String v = s == null ? "" : s;
            int keep = keptChars(v);
            int bytes = 0;
            for (int i = 0; i < keep; i++) {
                bytes += utfLen(v.charAt(i));
            }
            room(2 + bytes);
            n = putU16(b, n, bytes);
            for (int i = 0; i < keep; i++) {
                char c = v.charAt(i);
                if (c >= 0x0001 && c <= 0x007F) {
                    b[n++] = (byte) c;
                } else if (c <= 0x07FF) {
                    b[n++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
                    b[n++] = (byte) (0x80 | (c & 0x3F));
                } else {
                    b[n++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
                    b[n++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                    b[n++] = (byte) (0x80 | (c & 0x3F));
                }
            }
        }

        /** The bytes verbatim, no length prefix (the caller frames them). */
        void raw(byte[] v) {
            room(v.length);
            System.arraycopy(v, 0, b, n, v.length);
            n += v.length;
        }

        byte[] toBytes() {
            return n == b.length ? b : Arrays.copyOf(b, n);
        }
    }

    /** A strict reader: prefix checked at construction, every read bounds-checked, {@link #end}
     *  refuses a trailing byte; every refusal is an {@link IllegalArgumentException} naming the kind. */
    private static final class In {
        private final byte[] b;
        private final String name;
        private int at;

        In(byte[] payload, int kind, String name) {
            this.name = name;
            if (payload == null || payload.length < PREFIX_BYTES) {
                throw new IllegalArgumentException(name + " row: payload shorter than the prefix");
            }
            this.b = payload;
            int magic = i32();
            if (magic != magicOf(kind)) {
                throw new IllegalArgumentException(name + " row: magic 0x" + Integer.toHexString(magic)
                        + " is not 0x" + Integer.toHexString(magicOf(kind)));
            }
            int version = u8();
            if (version != ROW_VERSION) {
                throw new IllegalArgumentException(name + " row: rowVersion " + version + " is not "
                        + ROW_VERSION);
            }
            int band = u8();
            if (band > BAND_WRAP) {
                throw new IllegalArgumentException(name + " row: band " + band + " outside 0..3");
            }
            i32(); // round: read through bandOf / roundOf
        }

        private void need(int k) {
            if (at + k > b.length) {
                throw new IllegalArgumentException(name + " row: truncated at byte " + at);
            }
        }

        int u8() {
            need(1);
            return b[at++] & 0xFF;
        }

        boolean bool() {
            int v = u8();
            if (v > 1) {
                throw new IllegalArgumentException(name + " row: flag byte " + v + " is not 0 / 1");
            }
            return v == 1;
        }

        int u16() {
            need(2);
            int v = ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
            at += 2;
            return v;
        }

        int i32() {
            need(4);
            int v = ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8)
                    | (b[at + 3] & 0xFF);
            at += 4;
            return v;
        }

        long i64() {
            long hi = i32() & 0xFFFFFFFFL;
            long lo = i32() & 0xFFFFFFFFL;
            return (hi << 32) | lo;
        }

        float f32() {
            return Float.intBitsToFloat(i32());
        }

        double f64() {
            return Double.longBitsToDouble(i64());
        }

        byte[] bytes(int k) {
            need(k);
            byte[] out = Arrays.copyOfRange(b, at, at + k);
            at += k;
            return out;
        }

        /** Bytes not yet read (0 at a clean end): an additive tail is read only while this is > 0. */
        int remaining() {
            return b.length - at;
        }

        /**
         * One {@code writeUTF}-framed text field. A length past {@link #UTF_MAX_BYTES} is malformed: every
         * writer goes through {@link Out#utf}, whose {@code keptChars} bound keeps at most that many bytes, so
         * only a forged or corrupt payload carries one, and refusing it keeps the reader's text inside the
         * bound the record path wrote (a u16 length would otherwise admit 65,535 bytes).
         *
         */
        String utf() {
            int len = u16();
            if (len > UTF_MAX_BYTES) {
                throw new IllegalArgumentException(name + " row: text length " + len + " past the "
                        + UTF_MAX_BYTES + "-byte cap");
            }
            need(len);
            int end = at + len;
            StringBuilder sb = new StringBuilder(len);
            while (at < end) {
                int c = b[at] & 0xFF;
                if (c < 0x80) {
                    sb.append((char) c);
                    at++;
                } else if ((c & 0xE0) == 0xC0 && at + 1 < end) {
                    sb.append((char) (((c & 0x1F) << 6) | (b[at + 1] & 0x3F)));
                    at += 2;
                } else if ((c & 0xF0) == 0xE0 && at + 2 < end) {
                    sb.append((char) (((c & 0x0F) << 12) | ((b[at + 1] & 0x3F) << 6) | (b[at + 2] & 0x3F)));
                    at += 3;
                } else {
                    throw new IllegalArgumentException(name + " row: malformed modified UTF-8 at byte " + at);
                }
            }
            return sb.toString();
        }

        void end() {
            if (at != b.length) {
                throw new IllegalArgumentException(name + " row: " + (b.length - at) + " trailing byte(s)");
            }
        }
    }

    private MatchRecordRows() {
    }
}
