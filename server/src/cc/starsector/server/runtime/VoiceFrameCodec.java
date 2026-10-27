package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * W12 voice chat — the two
 * VOICE-channel sub-frames that ride the datagram envelope ({@link DatagramFrameCodec.WireChannel#VOICE},
 * one Opus frame per datagram UP, one per-listener bundle part per datagram DOWN). The envelope
 * already supplies magic/version/roundId/sessionId/seq + a CRC32 tail, so these sub-frames carry
 * NO checksum of their own and the {@code seq} the client keys jitter/replay on is the ENVELOPE
 * seq. The server never reads the Opus bytes — it relays them opaque.
 *
 * <p><b>UP {@code VCUP} (talker → server), 10-byte header:</b> {@code magic u32 'VCUP'} ·
 * {@code schema u16} · {@code flags u8} (bit0 proximity, bit1 burst-start, bit2 = FLAG_ECHO (0x04), bits 3-7 refuse) ·
 * {@code burstId u8} (per PTT press) · {@code len u16} (1..{@link #MAX_OPUS_BYTES}) ·
 * {@code opus[len]}. No seat, no text, no ids — the seat comes from the session.
 *
 * <p><b>DOWN {@code VCDN} (server → listener), 9-byte header + 11-byte tag per talker:</b>
 * {@code magic u32 'VCDN'} · {@code schema u16} · {@code part u8} · {@code parts u8} ·
 * {@code talkerCount u8} (1..{@link #MAX_TALKERS_WIRE}) then per talker {@code seatId u8 ·
 * flags u8 · burstId u8 · seq u16 (the talker's envelope seq, low 16 bits) · anchorWireId u32
 * (the server-authored current body: hull / pod / wreck root) · len u16 · opus[len]}. A
 * listener's bundle may span several SELF-CONTAINED parts ({@link #encodeDownParts}) — each
 * decodes alone; a lost part costs only its talkers' frames.
 *
 * <p><b>Fit law:</b> ONE Opus frame fits ONE datagram on both directions:
 * {@code 10 + MAX_OPUS_BYTES} UP and {@code 9 + 11 + MAX_OPUS_BYTES} DOWN are both ≤
 * {@link DatagramFrameCodec#MAX_PAYLOAD_BYTES} (1164). The packet's draft cap of 1150 did NOT
 * satisfy the DOWN side (1170 B) — the cap is {@value #MAX_OPUS_BYTES} here and the config
 * bound ({@link #configMaxOpusBytes}) derives from it. Allocation is fixed-cap by construction:
 * ≤ {@code MAX_TALKERS_WIRE × MAX_OPUS_BYTES} per decoded part, {@code talkerCount}/{@code len}
 * are checked BEFORE any allocation.
 *
 * <p><b>Posture:</b> {@code encode*} runs on our own state and THROWS loud on any violation.
 * {@code decode*} THROWS {@link IllegalArgumentException} on any out-of-domain shape (bad
 * magic/schema/count/len/flag bits/trailing bytes/truncation). The envelope CRC has already
 * passed when a sub-frame reaches this codec, so a malformed VCUP is a CRC-VALID out-of-domain
 * UP — the post-admit UP law: the ingress catches, counts and KILLS the
 * session; UDP loss cannot produce that shape. The reader thread never dies. Client-side a
 * malformed VCDN is a server bug; L3 decides count-and-drop vs loud.
 *
 *
 * <p>The client mod speaks these bytes through hand-mirrored reader/writers (the CHTU/CHTD
 * mirror law, {@link ChatFrameCodec}); a game-free verifier byte-compares them at L3. Nothing in
 * this class is server-only (java.util + {@link WireByteOps}), so it may also fold into the mod
 * jar like {@link DatagramFrameCodec} — L3 decides mirror vs fold.
 */
public final class VoiceFrameCodec {

    /** VCUP magic = ASCII "VCUP". */
    public static final int UP_MAGIC = 0x56435550;
    /** VCDN magic = ASCII "VCDN". */
    public static final int DOWN_MAGIC = 0x5643444E;
    public static final int UP_SCHEMA = 1;
    public static final int DOWN_SCHEMA = 1;

    /** flags bit0: proximity (else global). */
    public static final int FLAG_PROXIMITY = 0x01;
    /** flags bit1: first frame of a PTT burst (jitter-buffer reset key beside burstId). */
    public static final int FLAG_BURST_START = 0x02;
    /**
     * flags bit2: VCUP "echo me" request — the relay echoes the talker's OWN frames back to
     * them iff set, and STRIPS the bit from every VCDN tag (client→server only; a listener
     * never sees it). The server GUI sets defaults: the sheet's
     * {@code echo} (VCFG bit1) is now only the DEFAULT the client adopts; the client's
     * effective choice rides this bit per frame.
     */
    public static final int FLAG_ECHO = 0x04;
    /**
     * flags bit3: STAGE STAMPS (probe-only, {@code -Dcc.probe.voiceStageStamps}):
     * the frame carries QPC-microsecond stamps right after {@code len} — UP {@code capMicros u32}
     * (the talker's capture hand-off), DOWN {@code capMicros u32 · outMicros u32} (the relay's
     * bundle instant appended). WHY: the m2e rig's capture→sink constant is BIMODAL (9.4 vs ≈ 14
     * ms) and only per-stage stamps can attribute it; on loopback every JVM reads ONE clock
     * (HotSpot Windows {@code nanoTime} = QPC / freq, no per-JVM origin), so cross-JVM differences
     * are exact there and meaningless across hosts. Best-effort + untrusted: the server only logs a
     * bounded difference, and strips the bit from a frame whose opus would not fit the part with
     * the stamps ({@link #MAX_STAMPED_OPUS_BYTES}). Unstamped frames stay byte-identical.
     */
    public static final int FLAG_STAMP = 0x08;
    /** Every flag bit with a meaning; any other bit (4-7) set refuses (fail-closed both directions). */
    public static final int FLAGS_DEFINED = FLAG_PROXIMITY | FLAG_BURST_START | FLAG_ECHO | FLAG_STAMP;

    /** Hard cap on one Opus frame's bytes — sized so one frame fits one datagram on BOTH
     * directions (the fit law above). Config-derived caps are always ≤ this. */
    public static final int MAX_OPUS_BYTES = 1140;
    /** Most talkers one VCDN part may carry (and the sheet's {@code maxTalkers} ceiling). */
    public static final int MAX_TALKERS_WIRE = 8;
    /** Most parts one bundle may span — 8 talkers at the cap need 8 parts, never more. */
    public static final int MAX_PARTS = MAX_TALKERS_WIRE;

    /** magic(4) + schema(2) + flags(1) + burstId(1) + len(2). */
    public static final int UP_HEADER_BYTES = 10;
    public static final int UP_MAX_FRAME_BYTES = UP_HEADER_BYTES + MAX_OPUS_BYTES; // 1150
    /** magic(4) + schema(2) + part(1) + parts(1) + talkerCount(1). */
    public static final int DOWN_HEADER_BYTES = 9;
    /** seatId(1) + flags(1) + burstId(1) + seq(2) + anchorWireId(4) + len(2). */
    public static final int DOWN_TAG_BYTES = 11;
    public static final int DOWN_MAX_TALKER_BYTES = DOWN_TAG_BYTES + MAX_OPUS_BYTES; // 1151
    /** {@link #FLAG_STAMP} extension widths (after {@code len}, before {@code opus}): UP
     *  {@code capMicros u32}; DOWN {@code capMicros u32 · outMicros u32}. A stamped DOWN talker's
     *  opus is capped so one max talker still fits one part: 9 + 11 + 8 + 1132 = 1160 ≤ 1164
     *  (UP: 10 + 4 + 1140 = 1154). The relay strips the bit above the cap; the codec refuses it. */
    public static final int UP_STAMP_BYTES = 4;
    public static final int DOWN_STAMP_BYTES = 8;
    public static final int MAX_STAMPED_OPUS_BYTES = MAX_OPUS_BYTES - DOWN_STAMP_BYTES; // 1132
    /** The payload ceiling every part obeys — the envelope's, restated for the fit tests. */
    public static final int MAX_PART_BYTES = DatagramFrameCodec.MAX_PAYLOAD_BYTES; // 1164

    /** Codec-native bitrate domain (Concentus/libopus: 6..510 kbps) — the design's ONLY ceiling. */
    public static final int MIN_BITRATE_KBPS = 6;
    public static final int MAX_BITRATE_KBPS = 510;
    /** Opus frame durations the sheet allows (ms). */
    public static final int[] FRAME_MS_ALLOWED = {10, 20, 40, 60};

    private static final long UNSIGNED_32_MAX = 0xFFFF_FFFFL;

    /** One decoded VCUP. {@code opus} is a fresh copy of exactly {@code len} bytes; {@code capMicros}
     *  = the {@link #FLAG_STAMP} capture stamp (0 and meaningless when unstamped). */
    public record VoiceUp(int flags, int burstId, byte[] opus, int capMicros) {
        public VoiceUp {
            requireFlags(flags, "VCUP flags");
            requireU8(burstId, "VCUP burstId");
            requireOpus(opus, "VCUP");
        }

        /** The unstamped shape (every call site that predates the stage stamps). */
        public VoiceUp(int flags, int burstId, byte[] opus) {
            this(flags, burstId, opus, 0);
        }

        public boolean proximity() {
            return (flags & FLAG_PROXIMITY) != 0;
        }

        public boolean burstStart() {
            return (flags & FLAG_BURST_START) != 0;
        }

        public boolean stamped() {
            return (flags & FLAG_STAMP) != 0;
        }
    }

    /** One talker's frame inside a VCDN part (all fields as they ride the wire); {@code capMicros}
     *  / {@code outMicros} = the {@link #FLAG_STAMP} stamps (0 and meaningless when unstamped). */
    public record TalkerFrame(int seatId, int flags, int burstId, int seq, long anchorWireId,
            byte[] opus, int capMicros, int outMicros) {
        public TalkerFrame {
            requireU8(seatId, "VCDN seatId");
            requireFlags(flags, "VCDN flags");
            requireU8(burstId, "VCDN burstId");
            if (seq < 0 || seq > 0xFFFF) {
                throw new IllegalArgumentException("VCDN seq must fit u16: " + seq);
            }
            if (anchorWireId < 0L || anchorWireId > UNSIGNED_32_MAX) {
                throw new IllegalArgumentException(
                        "VCDN anchorWireId must fit u32: " + anchorWireId);
            }
            requireOpus(opus, "VCDN");
            if ((flags & FLAG_STAMP) != 0 && opus.length > MAX_STAMPED_OPUS_BYTES) {
                throw new IllegalArgumentException("VCDN stamped opus len must be 1.."
                        + MAX_STAMPED_OPUS_BYTES + ": " + opus.length);
            }
        }

        /** The unstamped shape (every call site that predates the stage stamps). */
        public TalkerFrame(int seatId, int flags, int burstId, int seq, long anchorWireId,
                byte[] opus) {
            this(seatId, flags, burstId, seq, anchorWireId, opus, 0, 0);
        }

        public boolean stamped() {
            return (flags & FLAG_STAMP) != 0;
        }

        /** Bytes this talker occupies inside a part (the stamps included when stamped). */
        public int wireBytes() {
            return DOWN_TAG_BYTES + (stamped() ? DOWN_STAMP_BYTES : 0) + opus.length;
        }
    }

    /** One decoded VCDN part ({@code part} in {@code 0..parts-1}; {@code talkers} unmodifiable,
     * never empty). */
    public record VoiceDown(int part, int parts, List<TalkerFrame> talkers) {
        public VoiceDown {
            requireParts(part, parts);
            Objects.requireNonNull(talkers, "talkers");
            if (talkers.isEmpty() || talkers.size() > MAX_TALKERS_WIRE) {
                throw new IllegalArgumentException("VCDN talkerCount must be 1.."
                        + MAX_TALKERS_WIRE + ": " + talkers.size());
            }
            talkers = Collections.unmodifiableList(new ArrayList<>(talkers));
        }
    }

    private VoiceFrameCodec() {
    }

    // ---- UP ---------------------------------------------------------------------------------

    /** Encode one VCUP ({@code opus} copied; 1..{@link #MAX_OPUS_BYTES} bytes). Throws loud. */
    public static byte[] encodeUp(int flags, int burstId, byte[] opus) {
        return encodeUp(flags, burstId, opus, 0);
    }

    /** The {@link #FLAG_STAMP} shape: {@code capMicros} rides after {@code len} iff the bit is set
     *  (ignored otherwise — the unstamped bytes are unchanged). */
    public static byte[] encodeUp(int flags, int burstId, byte[] opus, int capMicros) {
        requireFlags(flags, "VCUP flags");
        requireU8(burstId, "VCUP burstId");
        requireOpus(opus, "VCUP");
        boolean stamped = (flags & FLAG_STAMP) != 0;
        byte[] out = new byte[UP_HEADER_BYTES + (stamped ? UP_STAMP_BYTES : 0) + opus.length];
        int off = 0;
        off = putInt(out, off, UP_MAGIC);
        off = putShort(out, off, UP_SCHEMA);
        out[off++] = (byte) flags;
        out[off++] = (byte) burstId;
        off = putShort(out, off, opus.length);
        if (stamped) {
            off = putInt(out, off, capMicros);
        }
        System.arraycopy(opus, 0, out, off, opus.length);
        return out;
    }

    /**
     * Decode one VCUP payload (the whole envelope payload). Throws {@link IllegalArgumentException}
     * on any out-of-domain shape — the caller applies the post-admit UP law (kill + count).
     * {@code len} is validated BEFORE the copy; the copy is exactly {@code len} bytes.
     */
    public static VoiceUp decodeUp(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < UP_HEADER_BYTES + 1) {
            throw new IllegalArgumentException("VCUP truncated: " + payload.length + " B");
        }
        int off = 0;
        int magic = getInt(payload, off); off += 4;
        if (magic != UP_MAGIC) {
            throw new IllegalArgumentException("VCUP bad magic 0x" + Integer.toHexString(magic));
        }
        int schema = getUnsignedShort(payload, off); off += 2;
        if (schema != UP_SCHEMA) {
            throw new IllegalArgumentException("VCUP unsupported schema " + schema);
        }
        int flags = payload[off++] & 0xFF;
        requireFlags(flags, "VCUP flags");
        int burstId = payload[off++] & 0xFF;
        int len = getUnsignedShort(payload, off); off += 2;
        if (len < 1 || len > MAX_OPUS_BYTES) {
            throw new IllegalArgumentException("VCUP len out of domain: " + len);
        }
        int capMicros = 0;
        if ((flags & FLAG_STAMP) != 0) {
            if (payload.length - off < UP_STAMP_BYTES) {
                throw new IllegalArgumentException("VCUP truncated inside the stamp");
            }
            capMicros = getInt(payload, off); off += 4;
        }
        if (payload.length != off + len) {
            throw new IllegalArgumentException("VCUP length mismatch: header says " + len
                    + " B, payload carries " + (payload.length - off));
        }
        return new VoiceUp(flags, burstId, Arrays.copyOfRange(payload, off, off + len), capMicros);
    }

    // ---- DOWN -------------------------------------------------------------------------------

    /** Bytes one part carrying exactly these talkers occupies. */
    public static int downPartBytes(List<TalkerFrame> talkers) {
        int n = DOWN_HEADER_BYTES;
        for (TalkerFrame t : talkers) {
            n += t.wireBytes();
        }
        return n;
    }

    /**
     * Encode ONE VCDN part. Throws loud when {@code talkers} is empty / over
     * {@link #MAX_TALKERS_WIRE}, {@code part}/{@code parts} are out of domain, or the part would
     * not fit one datagram ({@link #MAX_PART_BYTES}) — the bundler splits with
     * {@link #encodeDownParts} instead of ever sending an over-MTU part.
     */
    public static byte[] encodeDown(int part, int parts, List<TalkerFrame> talkers) {
        requireParts(part, parts);
        Objects.requireNonNull(talkers, "talkers");
        if (talkers.isEmpty() || talkers.size() > MAX_TALKERS_WIRE) {
            throw new IllegalArgumentException("VCDN talkerCount must be 1.." + MAX_TALKERS_WIRE
                    + ": " + talkers.size());
        }
        int total = downPartBytes(talkers);
        if (total > MAX_PART_BYTES) {
            throw new IllegalArgumentException("VCDN part over the datagram payload cap: " + total
                    + " B > " + MAX_PART_BYTES + " (split it — encodeDownParts)");
        }
        byte[] out = new byte[total];
        int off = 0;
        off = putInt(out, off, DOWN_MAGIC);
        off = putShort(out, off, DOWN_SCHEMA);
        out[off++] = (byte) part;
        out[off++] = (byte) parts;
        out[off++] = (byte) talkers.size();
        for (TalkerFrame t : talkers) {
            out[off++] = (byte) t.seatId();
            out[off++] = (byte) t.flags();
            out[off++] = (byte) t.burstId();
            off = putShort(out, off, t.seq());
            off = putInt(out, off, (int) t.anchorWireId());
            off = putShort(out, off, t.opus().length);
            if (t.stamped()) {
                off = putInt(out, off, t.capMicros());
                off = putInt(out, off, t.outMicros());
            }
            System.arraycopy(t.opus(), 0, out, off, t.opus().length);
            off += t.opus().length;
        }
        return out;
    }

    /**
     * Split an ordered talker list (the bundler's priority order) into self-contained parts,
     * each ≤ {@link #MAX_PART_BYTES}, first-fit in order, and encode them with {@code part}/
     * {@code parts} stamped. At most {@link #MAX_TALKERS_WIRE} talkers in total (the bundler
     * trims + counts BEFORE calling — a bigger list throws). Empty list ⇒ empty result (idle
     * voice sends nothing).
     */
    public static List<byte[]> encodeDownParts(List<TalkerFrame> talkers) {
        Objects.requireNonNull(talkers, "talkers");
        if (talkers.isEmpty()) {
            return List.of();
        }
        if (talkers.size() > MAX_TALKERS_WIRE) {
            throw new IllegalArgumentException("VCDN bundle over " + MAX_TALKERS_WIRE
                    + " talkers: " + talkers.size() + " (the bundler trims first)");
        }
        List<List<TalkerFrame>> groups = new ArrayList<>();
        List<TalkerFrame> cur = new ArrayList<>();
        int curBytes = DOWN_HEADER_BYTES;
        for (TalkerFrame t : talkers) {
            int need = t.wireBytes();
            if (!cur.isEmpty() && curBytes + need > MAX_PART_BYTES) {
                groups.add(cur);
                cur = new ArrayList<>();
                curBytes = DOWN_HEADER_BYTES;
            }
            cur.add(t);
            curBytes += need;
        }
        groups.add(cur);
        int parts = groups.size(); // ≤ MAX_TALKERS_WIRE by construction (one talker always fits)
        List<byte[]> out = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            out.add(encodeDown(i, parts, groups.get(i)));
        }
        return out;
    }

    /**
     * Decode one VCDN part (the whole envelope payload). Throws {@link IllegalArgumentException}
     * on any out-of-domain shape. {@code talkerCount} is checked first, every {@code len}
     * before its copy, and the payload must end exactly at the last talker (trailing bytes
     * refuse) — allocation ≤ {@code MAX_TALKERS_WIRE × MAX_OPUS_BYTES} by construction.
     */
    public static VoiceDown decodeDown(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < DOWN_HEADER_BYTES + DOWN_TAG_BYTES + 1) {
            throw new IllegalArgumentException("VCDN truncated: " + payload.length + " B");
        }
        if (payload.length > MAX_PART_BYTES) {
            throw new IllegalArgumentException("VCDN part over cap: " + payload.length + " B");
        }
        int off = 0;
        int magic = getInt(payload, off); off += 4;
        if (magic != DOWN_MAGIC) {
            throw new IllegalArgumentException("VCDN bad magic 0x" + Integer.toHexString(magic));
        }
        int schema = getUnsignedShort(payload, off); off += 2;
        if (schema != DOWN_SCHEMA) {
            throw new IllegalArgumentException("VCDN unsupported schema " + schema);
        }
        int part = payload[off++] & 0xFF;
        int parts = payload[off++] & 0xFF;
        requireParts(part, parts);
        int count = payload[off++] & 0xFF;
        if (count < 1 || count > MAX_TALKERS_WIRE) {
            throw new IllegalArgumentException("VCDN talkerCount out of domain: " + count);
        }
        List<TalkerFrame> talkers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (payload.length - off < DOWN_TAG_BYTES) {
                throw new IllegalArgumentException("VCDN truncated at talker " + i);
            }
            int seatId = payload[off++] & 0xFF;
            int flags = payload[off++] & 0xFF;
            requireFlags(flags, "VCDN flags");
            int burstId = payload[off++] & 0xFF;
            int seq = getUnsignedShort(payload, off); off += 2;
            long anchor = getInt(payload, off) & UNSIGNED_32_MAX; off += 4;
            int len = getUnsignedShort(payload, off); off += 2;
            if (len < 1 || len > MAX_OPUS_BYTES) {
                throw new IllegalArgumentException("VCDN len out of domain at talker " + i
                        + ": " + len);
            }
            int capMicros = 0;
            int outMicros = 0;
            if ((flags & FLAG_STAMP) != 0) {
                if (len > MAX_STAMPED_OPUS_BYTES) {
                    throw new IllegalArgumentException("VCDN stamped len over cap at talker " + i
                            + ": " + len);
                }
                if (payload.length - off < DOWN_STAMP_BYTES) {
                    throw new IllegalArgumentException("VCDN truncated inside the stamp at talker "
                            + i);
                }
                capMicros = getInt(payload, off); off += 4;
                outMicros = getInt(payload, off); off += 4;
            }
            if (payload.length - off < len) {
                throw new IllegalArgumentException("VCDN truncated inside talker " + i);
            }
            talkers.add(new TalkerFrame(seatId, flags, burstId, seq, anchor,
                    Arrays.copyOfRange(payload, off, off + len), capMicros, outMicros));
            off += len;
        }
        if (off != payload.length) {
            throw new IllegalArgumentException("VCDN trailing bytes: " + (payload.length - off));
        }
        return new VoiceDown(part, parts, talkers);
    }

    // ---- config bounds (the server boots against these, the ingress caps on them) --

    /**
     * The config-derived per-frame byte cap the ingress enforces on VCUP {@code len}:
     * {@code ceil(1.5 × kbps × frameMs / 8) + 8} — 1.5× the nominal constrained-VBR frame plus
     * TOC/padding slack, derived from the config the server itself dictated, never a bare
     * constant. Throws on an out-of-domain {@code kbps}/{@code frameMs}.
     */
    public static int configMaxOpusBytes(int kbps, int frameMs) {
        requireBitrate(kbps);
        requireFrameMs(frameMs);
        // nominal frame bytes = kbps × frameMs / 8; ×1.5 = 3 × kbps × frameMs / 16, rounded up.
        return (3 * kbps * frameMs + 15) / 16 + 8;
    }

    /** The joint boot bound: one frame at this config fits one datagram (both directions). */
    public static boolean fitsOneDatagram(int kbps, int frameMs) {
        return configMaxOpusBytes(kbps, frameMs) <= MAX_OPUS_BYTES;
    }

    // ---- laws -------------------------------------------------------------------------------

    private static void requireFlags(int flags, String what) {
        if (flags < 0 || flags > 0xFF || (flags & ~FLAGS_DEFINED) != 0) {
            throw new IllegalArgumentException(what + " out of domain: 0x"
                    + Integer.toHexString(flags));
        }
    }

    private static void requireU8(int v, String what) {
        if (v < 0 || v > 0xFF) {
            throw new IllegalArgumentException(what + " must fit u8: " + v);
        }
    }

    private static void requireOpus(byte[] opus, String what) {
        Objects.requireNonNull(opus, what + " opus");
        if (opus.length < 1 || opus.length > MAX_OPUS_BYTES) {
            throw new IllegalArgumentException(what + " opus len must be 1.." + MAX_OPUS_BYTES
                    + ": " + opus.length);
        }
    }

    private static void requireParts(int part, int parts) {
        if (parts < 1 || parts > MAX_PARTS) {
            throw new IllegalArgumentException("VCDN parts must be 1.." + MAX_PARTS + ": " + parts);
        }
        if (part < 0 || part >= parts) {
            throw new IllegalArgumentException("VCDN part must be 0..parts-1: " + part + "/" + parts);
        }
    }

    private static void requireBitrate(int kbps) {
        if (kbps < MIN_BITRATE_KBPS || kbps > MAX_BITRATE_KBPS) {
            throw new IllegalArgumentException("voice bitrate must be " + MIN_BITRATE_KBPS + ".."
                    + MAX_BITRATE_KBPS + " kbps: " + kbps);
        }
    }

    private static void requireFrameMs(int frameMs) {
        for (int allowed : FRAME_MS_ALLOWED) {
            if (allowed == frameMs) {
                return;
            }
        }
        throw new IllegalArgumentException("voice frameMs must be one of "
                + Arrays.toString(FRAME_MS_ALLOWED) + ": " + frameMs);
    }
}
