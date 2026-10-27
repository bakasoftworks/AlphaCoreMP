package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientPostureEchoMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerPostureBlockMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for the lane-posture frame pair — M4-A2 of the posture rebuild:
 *
 * server→client {@code PSTB} posture wire block ({@link ServerPostureBlockMessage},
 * spooled once per admission on the CTRL lane) and client→server {@code PSTE} posture echo
 * ({@link ClientPostureEchoMessage}, sent once at attach).
 *
 * <p>The {@link SeatNameFrameCodec} twin: magic + registered wire type code + schema header,
 * fixed width, CRC32 over everything before the trailer, fail-closed reads. The client mod does
 * NOT load this class (its static type-code initializers drag {@link NetworkMessageRegistry}
 * into the game JVM); it speaks the same bytes through the hand-mirrored
 * {@code ClientPostureBlockReader} / {@code PostureEchoWriter}, and the game-free verifier
 * byte-compares the mirrors against this
 * codec — any drift breaks it.
 *
 * <p><b>Block schema 2 carries the {@link WireKnob} census</b> in a
 * fixed {@link #KNOB_BUFFER_BYTES} buffer after the tag: {@code knobCount} used slots of
 * {@code (u8 id, u8 value)}, ids ascending + registered, values inside the knob's choice table,
 * every unused slot byte zero — all fail-closed. Schema 1 was knob-EMPTY by design (a knob joins
 * the block the first time a lane needs it to vary; the foe-cloak lead/wait A/B was that first
 * time, and the schema bump kept the frame fixed-width). The live spool encodes the WHOLE census
 * (one row today) and re-spools every session on an operator {@code /knob} flip; a knob-less
 * block (count 0) stays legal = every client knob at its compiled default (verifier / tools).
 * The experiment tag rides as a 1-byte UTF-8 byte-count
 * prefix + a fixed {@link #TAG_BUFFER_BYTES}-byte zero-padded buffer under the posture
 * single-line-printable law; {@code flags} bit0 mirrors tag presence and every other flag bit is
 * fail-closed. The echo carries BOTH {@code ClientPostureRegistry} digests (full + behavior, 32
 * raw bytes each — verdicts key on behavior, full is informational drift) plus the row/unknown
 * counts for the mismatch diagnostics row.
 */
public final class PostureFrameCodec {

    /** PSTB magic = ASCII "PSTB" (PoSTure Block, down). */
    public static final int BLOCK_MAGIC = 0x50535442;
    /** PSTE magic = ASCII "PSTE" (PoSTure Echo, up). */
    public static final int ECHO_MAGIC = 0x50535445;

    /** Registered wire type codes these frames carry. */
    public static final int BLOCK_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverPostureBlockWireEntry().wireTypeCode();
    public static final int ECHO_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .clientPostureEchoWireEntry().wireTypeCode();

    public static final short BLOCK_SCHEMA_VERSION =
            (short) ServerPostureBlockMessage.SCHEMA_VERSION;
    public static final short ECHO_SCHEMA_VERSION =
            (short) ClientPostureEchoMessage.SCHEMA_VERSION;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** {@code flags} bit0: experiment tag present ({@code tagLen > 0}). All other bits refuse. */
    public static final int FLAG_EXPERIMENT_TAG = 0x01;
    /** Fixed zero-padded UTF-8 buffer for the experiment tag (cap = the plan D4 ≤64). */
    public static final int TAG_BUFFER_BYTES = 64;
    /** Schema 2: {@link WireKnob#KNOB_SLOTS} slots of (u8 id, u8 value), zero past knobCount. */
    public static final int KNOB_BUFFER_BYTES = WireKnob.KNOB_SLOTS * 2; // 16
    /** flags(1B) + knobCount(u16, ≤ KNOB_SLOTS) + tagLen(1B) + tag buffer + knob buffer. */
    public static final int BLOCK_PAYLOAD_BYTES =
            1 + Short.BYTES + 1 + TAG_BUFFER_BYTES + KNOB_BUFFER_BYTES; // 84
    /** Fixed block frame width: header + payload + CRC32 trailer. */
    public static final int BLOCK_FRAME_BYTES =
            HEADER_BYTES + BLOCK_PAYLOAD_BYTES + Integer.BYTES; // 98

    /** Raw digest width (SHA-256), both echo hashes. */
    public static final int HASH_BYTES = 32;
    /** rowCount(u16) + unknownCount(u16) + full hash + behavior hash. */
    public static final int ECHO_PAYLOAD_BYTES = Short.BYTES + Short.BYTES + 2 * HASH_BYTES; // 68
    /** Fixed echo frame width: header + payload + CRC32 trailer. */
    public static final int ECHO_FRAME_BYTES =
            HEADER_BYTES + ECHO_PAYLOAD_BYTES + Integer.BYTES; // 82

    /** Echo count sanity bounds (census is 84 rows today; headroom, still fail-closed). */
    public static final int ROW_COUNT_MIN = 1;
    public static final int COUNT_MAX = 4096;

    private static final byte[] NO_TAG = new byte[0];
    private static final int[] NO_KNOBS = new int[0];

    /**
     * One decoded {@code PSTB} block. {@code experimentTag} is {@code ""} for the no-tag
     * sentinel (flags bit0 clear); {@code knobPairs} = the carried {@link WireKnob} set as flat
     * {@code (id, value)} pairs (schema 2; empty = every knob at its compiled default).
     */
    public record PostureBlock(String experimentTag, int[] knobPairs) {
        public PostureBlock {
            Objects.requireNonNull(experimentTag, "experimentTag");
            Objects.requireNonNull(knobPairs, "knobPairs");
            String bad = knobPairsError(knobPairs);
            if (bad != null) {
                throw new IllegalArgumentException("knobPairs invalid: " + bad);
            }
            knobPairs = knobPairs.clone();
        }

        /** A knob-less block (schema-1 shape: tag only). */
        public PostureBlock(String experimentTag) {
            this(experimentTag, NO_KNOBS);
        }

        /** True when the block stamps an experiment/diagnostic tag (flags bit0). */
        public boolean hasTag() {
            return !experimentTag.isEmpty();
        }

        @Override
        public int[] knobPairs() {
            return knobPairs.clone();
        }

        /** The carried value of {@code knob}, or {@code -1} when the block does not carry it. */
        public int knobValue(WireKnob knob) {
            for (int i = 0; i + 1 < knobPairs.length; i += 2) {
                if (knobPairs[i] == knob.id()) {
                    return knobPairs[i + 1];
                }
            }
            return -1;
        }
    }

    /**
     * The knob-set law shared by encode (fail-fast, a server-local programming error) and decode
     * (fail-closed wire input) — and hand-mirrored in {@code ClientPostureBlockReader}: even
     * length, at most {@link WireKnob#KNOB_SLOTS} pairs, every id registered, ids strictly
     * ascending (no duplicates), every value inside its knob's choice table. Returns the reason,
     * or {@code null} when legal.
     */
    public static String knobPairsError(int[] pairs) {
        if (pairs.length % 2 != 0) {
            return "odd pair array length " + pairs.length;
        }
        if (pairs.length / 2 > WireKnob.KNOB_SLOTS) {
            return "knobCount " + (pairs.length / 2) + " exceeds " + WireKnob.KNOB_SLOTS;
        }
        int lastId = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            int id = pairs[i];
            int value = pairs[i + 1];
            WireKnob knob = WireKnob.byId(id);
            if (knob == null) {
                return "unknown knob id " + id;
            }
            if (id <= lastId) {
                return "knob ids not strictly ascending at id " + id;
            }
            if (value < 0 || value >= knob.choiceCount()) {
                return "knob " + knob.key() + " value " + value + " outside 0.."
                        + (knob.choiceCount() - 1);
            }
            lastId = id;
        }
        return null;
    }

    /**
     * One decoded {@code PSTE} echo: the client's {@code ClientPostureRegistry} row/unknown
     * counts and both raw digests (defensive copies both ways).
     */
    public record PostureEcho(int rowCount, int unknownCount, byte[] fullHash,
            byte[] behaviorHash) {
        public PostureEcho {
            if (rowCount < ROW_COUNT_MIN || rowCount > COUNT_MAX) {
                throw new IllegalArgumentException("rowCount must be " + ROW_COUNT_MIN + ".."
                        + COUNT_MAX + "; got " + rowCount);
            }
            if (unknownCount < 0 || unknownCount > COUNT_MAX) {
                throw new IllegalArgumentException(
                        "unknownCount must be 0.." + COUNT_MAX + "; got " + unknownCount);
            }
            fullHash = requireHash(fullHash, "fullHash").clone();
            behaviorHash = requireHash(behaviorHash, "behaviorHash").clone();
        }

        @Override
        public byte[] fullHash() {
            return fullHash.clone();
        }

        @Override
        public byte[] behaviorHash() {
            return behaviorHash.clone();
        }

        private static byte[] requireHash(byte[] hash, String what) {
            Objects.requireNonNull(hash, what);
            if (hash.length != HASH_BYTES) {
                throw new IllegalArgumentException(
                        what + " must be " + HASH_BYTES + " bytes; got " + hash.length);
            }
            return hash;
        }
    }

    private PostureFrameCodec() {
    }

    /**
     * The experiment-tag wire contract: trimmed, single-line printable (the shared
     * {@link WireTextLaw#isPrintableWireChar} family law), ≤{@link #TAG_BUFFER_BYTES} UTF-8 bytes. Throws
     * {@link IllegalArgumentException}; the no-tag sentinel is null/empty and is handled by the
     * encoder, not here.
     */
    public static void validateExperimentTag(String tag) {
        Objects.requireNonNull(tag, "tag");
        if (tag.isEmpty() || !tag.equals(tag.trim())) {
            throw new IllegalArgumentException(
                    "experiment tag must be trimmed and non-blank; got " + tag.length() + " chars");
        }
        int bad = WireTextLaw.firstUnprintableIndex(tag); // The shared family law
        if (bad >= 0) {
            throw new IllegalArgumentException(
                    "experiment tag must be single-line printable (control char at index "
                            + bad + ")");
        }
        if (tag.getBytes(StandardCharsets.UTF_8).length > TAG_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "experiment tag must encode to <= " + TAG_BUFFER_BYTES + " UTF-8 bytes");
        }
    }

    /**
     * Encode one knob-less {@code PSTB} block ({@code tag} null/empty = the no-tag sentinel,
     * flags 0; every client knob at its compiled default — verifier / tools / tag-only rigs).
     */
    public static byte[] encodeBlock(String experimentTagOrNull) {
        return encodeBlock(experimentTagOrNull, NO_KNOBS);
    }

    /**
     * Encode one {@code PSTB} block carrying {@code knobPairs} (flat {@code (id, value)} pairs
     * under {@link #knobPairsError}'s law; the live spool passes the whole {@link WireKnob}
     * census). A malformed set throws {@link IllegalArgumentException} — server-local, never
     * wire input.
     */
    public static byte[] encodeBlock(String experimentTagOrNull, int[] knobPairs) {
        Objects.requireNonNull(knobPairs, "knobPairs");
        String badKnobs = knobPairsError(knobPairs);
        if (badKnobs != null) {
            throw new IllegalArgumentException("knobPairs invalid: " + badKnobs);
        }
        byte[] tagBytes;
        if (experimentTagOrNull == null || experimentTagOrNull.isEmpty()) {
            tagBytes = NO_TAG;
        } else {
            validateExperimentTag(experimentTagOrNull);
            tagBytes = experimentTagOrNull.getBytes(StandardCharsets.UTF_8);
        }
        byte[] buf = new byte[BLOCK_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, BLOCK_MAGIC);
        off = putInt(buf, off, BLOCK_TYPE_CODE);
        off = putShort(buf, off, BLOCK_SCHEMA_VERSION);
        buf[off++] = (byte) (tagBytes.length > 0 ? FLAG_EXPERIMENT_TAG : 0);
        off = putShort(buf, off, (short) (knobPairs.length / 2)); // knobCount = used slots
        buf[off++] = (byte) tagBytes.length;
        System.arraycopy(tagBytes, 0, buf, off, tagBytes.length);
        off += TAG_BUFFER_BYTES; // bytes beyond tagLen stay zero — the fail-closed padding contract
        for (int i = 0; i < knobPairs.length; i++) {
            buf[off + i] = (byte) knobPairs[i]; // (id, value) pairs; unused slots stay zero
        }
        off += KNOB_BUFFER_BYTES;
        // off now == HEADER_BYTES + BLOCK_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /**
     * Read exactly one {@code PSTB} from {@code in} (game-free tests / round-trip verifier; the
     * live client reads it via the mirrored {@code ClientPostureBlockReader} off its demux).
     * Returns {@code null} at a clean end of stream; fail-closed otherwise.
     */
    public static PostureBlock readBlock(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(BLOCK_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != BLOCK_FRAME_BYTES) {
            throw new IOException("posture block frame truncated: expected " + BLOCK_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        return decodeBlock(frame);
    }

    /** Decode + validate one full {@code PSTB} frame (fail-closed; verifier + read path). */
    public static PostureBlock decodeBlock(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != BLOCK_FRAME_BYTES) {
            throw new IOException("posture block frame must be " + BLOCK_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + BLOCK_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != BLOCK_MAGIC) {
            throw new IOException("posture block bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != BLOCK_TYPE_CODE) {
            throw new IOException("posture block wire type code mismatch: expected "
                    + BLOCK_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != BLOCK_SCHEMA_VERSION) {
            throw new IOException("posture block unsupported schema version: " + schema);
        }
        int flags = frame[off++] & 0xFF;
        if ((flags & ~FLAG_EXPERIMENT_TAG) != 0) {
            throw new IOException("posture block unknown flag bits: 0x"
                    + Integer.toHexString(flags));
        }
        int knobCount = getUnsignedShort(frame, off); off += 2;
        if (knobCount > WireKnob.KNOB_SLOTS) {
            throw new IOException("posture block knobCount out of range: " + knobCount);
        }
        String tag = readTagBuffer(frame, off);
        off += 1 + TAG_BUFFER_BYTES;
        boolean tagFlagged = (flags & FLAG_EXPERIMENT_TAG) != 0;
        if (tagFlagged != !tag.isEmpty()) {
            throw new IOException("posture block flags bit0 (" + tagFlagged
                    + ") disagrees with tagLen (" + tag.length() + " chars)");
        }
        int[] pairs = new int[knobCount * 2];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = frame[off + i] & 0xFF;
        }
        for (int i = pairs.length; i < KNOB_BUFFER_BYTES; i++) {
            if (frame[off + i] != 0) {
                throw new IOException("posture block nonzero padding past knobCount=" + knobCount
                        + " at knob byte " + i);
            }
        }
        off += KNOB_BUFFER_BYTES;
        String badKnobs = knobPairsError(pairs);
        if (badKnobs != null) {
            throw new IOException("posture block knobs invalid: " + badKnobs);
        }
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("posture block frame CRC mismatch");
        }
        return new PostureBlock(tag, pairs);
    }

    /** Encode one {@code PSTE} echo from the client's resolved posture facts. */
    public static byte[] encodeEcho(int rowCount, int unknownCount, byte[] fullHash,
            byte[] behaviorHash) {
        PostureEcho checked = new PostureEcho(rowCount, unknownCount, fullHash, behaviorHash);
        byte[] buf = new byte[ECHO_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, ECHO_MAGIC);
        off = putInt(buf, off, ECHO_TYPE_CODE);
        off = putShort(buf, off, ECHO_SCHEMA_VERSION);
        off = putShort(buf, off, (short) checked.rowCount());
        off = putShort(buf, off, (short) checked.unknownCount());
        System.arraycopy(checked.fullHash(), 0, buf, off, HASH_BYTES);
        off += HASH_BYTES;
        System.arraycopy(checked.behaviorHash(), 0, buf, off, HASH_BYTES);
        off += HASH_BYTES;
        // off now == HEADER_BYTES + ECHO_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /**
     * Read exactly one {@code PSTE} from {@code in} — the server's CTRL-delivered decode (the
     * caller hands the exact payload; a session that sends corruption here fails LOUD, the
     * post-admit CTRL law). Returns {@code null} at a clean end of stream; fail-closed otherwise.
     */
    public static PostureEcho readEcho(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(ECHO_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != ECHO_FRAME_BYTES) {
            throw new IOException("posture echo frame truncated: expected " + ECHO_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        return decodeEcho(frame);
    }

    /** Decode + validate one full {@code PSTE} frame (fail-closed; verifier + read path). */
    public static PostureEcho decodeEcho(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != ECHO_FRAME_BYTES) {
            throw new IOException("posture echo frame must be " + ECHO_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + ECHO_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != ECHO_MAGIC) {
            throw new IOException("posture echo bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != ECHO_TYPE_CODE) {
            throw new IOException("posture echo wire type code mismatch: expected "
                    + ECHO_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != ECHO_SCHEMA_VERSION) {
            throw new IOException("posture echo unsupported schema version: " + schema);
        }
        int rowCount = getUnsignedShort(frame, off); off += 2;
        int unknownCount = getUnsignedShort(frame, off); off += 2;
        byte[] fullHash = Arrays.copyOfRange(frame, off, off + HASH_BYTES);
        off += HASH_BYTES;
        byte[] behaviorHash = Arrays.copyOfRange(frame, off, off + HASH_BYTES);
        off += HASH_BYTES;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("posture echo frame CRC mismatch");
        }
        try {
            return new PostureEcho(rowCount, unknownCount, fullHash, behaviorHash);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "posture echo fields invalid: " + badFields.getMessage(), badFields);
        }
    }

    /** Read + validate the tagLen + zero-padded tag buffer; returns "" for the len-0 sentinel. */
    private static String readTagBuffer(byte[] frame, int off) throws IOException {
        int len = frame[off] & 0xFF;
        if (len > TAG_BUFFER_BYTES) {
            throw new IOException("posture block tagLen out of range: " + len);
        }
        int tagStart = off + 1;
        for (int i = tagStart + len; i < tagStart + TAG_BUFFER_BYTES; i++) {
            if (frame[i] != 0) {
                throw new IOException("posture block nonzero padding beyond tagLen=" + len
                        + " at buffer index " + (i - tagStart));
            }
        }
        if (len == 0) {
            return ""; // the no-tag sentinel
        }
        String value = new String(frame, tagStart, len, StandardCharsets.UTF_8);
        // Malformed UTF-8 decodes with replacement chars and would not re-encode byte-identical:
        // fail closed rather than adopt a mangled tag.
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len
                || !Arrays.equals(frame, tagStart, tagStart + len, reencoded, 0, len)) {
            throw new IOException("posture block experiment tag is not valid UTF-8");
        }
        try {
            validateExperimentTag(value);
        } catch (IllegalArgumentException bad) {
            throw new IOException(
                    "posture block experiment tag invalid: " + bad.getMessage(), bad);
        }
        return value;
    }
}
