package cc.starsector.client.net;

import cc.starsector.server.runtime.WireKnob;
import cc.starsector.server.runtime.WireTextLaw;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code PSTB} posture wire block:
 * the receiving half of the server's
 * {@code PostureFrameCodec} ({@code server.posture_block} v1). The server spools it ONCE per
 * admission on the CTRL lane (the variant-catalog spool precedent), carrying the optional
 * experiment/diagnostic tag that marks this admission as intentional variance; the
 * {@link MultiplexedServerStreamReader} routes it here by its leading magic. The sending half of
 * the posture pair is {@link PostureEchoWriter}.
 *
 * <p>Mirrors {@code cc.starsector.server.runtime.PostureFrameCodec.decodeBlock} exactly (the
 * mod deliberately does not bundle the codec — its static type-code initializers drag
 * {@code NetworkMessageRegistry} into the game JVM; see {@link SeatNameWriter}). The game-free
 * verifier streams real server codec bytes
 * through this reader and byte-compares the mirrors — any drift breaks it.
 *
 * <p>Fail-closed, mirroring the server decode exactly: wire-type-code/schema mismatch, unknown
 * flag bits, a {@code knobCount} past {@link WireKnob#KNOB_SLOTS}, {@code tagLen} out of range,
 * nonzero padding beyond {@code tagLen} or past the used knob slots, an unknown / unsorted /
 * duplicate knob id or a value outside its choice table ({@link #knobPairsError}, the server
 * law's mirror), a tag that is not valid UTF-8 or violates the single-line-printable law, a
 * flags/tagLen disagreement, or a CRC32 mismatch all throw {@link IOException}. {@code tagLen} 0
 * decodes as the {@code ""} no-tag sentinel; {@code knobCount} 0 = every knob at its default.
 * Schema 2 = the foe-cloak lead/wait A/B knob (the census's first row).
 */
public final class ClientPostureBlockReader {

    /** Frame magic: ASCII "PSTB". Mirrors {@code PostureFrameCodec.BLOCK_MAGIC}. */
    public static final int MAGIC = 0x50535442;

    /** Registry message id this frame carries. Mirrors {@code ServerPostureBlockMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.posture_block";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerPostureBlockMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 2;

    /** {@code flags} bit0: experiment tag present. Mirrors {@code PostureFrameCodec.FLAG_EXPERIMENT_TAG}. */
    public static final int FLAG_EXPERIMENT_TAG = 0x01;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code PostureFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** Fixed zero-padded UTF-8 tag buffer. Mirrors {@code PostureFrameCodec.TAG_BUFFER_BYTES}. */
    public static final int TAG_BUFFER_BYTES = 64;
    /** KNOB_SLOTS × (u8 id, u8 value). Mirrors {@code PostureFrameCodec.KNOB_BUFFER_BYTES}. */
    public static final int KNOB_BUFFER_BYTES = WireKnob.KNOB_SLOTS * 2; // 16
    /** flags(1B) + knobCount(u16) + tagLen(1B) + tag buffer + knob buffer. Mirrors {@code BLOCK_PAYLOAD_BYTES}. */
    public static final int PAYLOAD_BYTES =
            1 + Short.BYTES + 1 + TAG_BUFFER_BYTES + KNOB_BUFFER_BYTES; // 84
    /** Fixed frame width. Mirrors {@code PostureFrameCodec.BLOCK_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 98

    private ClientPostureBlockReader() {
    }

    /**
     * Decode one posture block whose 4-byte {@link #MAGIC} has <em>already</em> been read and
     * validated by the caller — the {@link MultiplexedServerStreamReader} entry point. The CRC32
     * covers the frame INCLUDING the magic (the server codec checksums
     * {@code [0, HEADER_BYTES + PAYLOAD_BYTES)} of the full frame — 94 at schema 2; the literal
     * 78 that stood here was schema 1), so this reconstructs the full frame buffer
     * before validating.
     */
    public static ClientPostureBlock readAfterMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES];
        WireBytes.putInt(frame, 0, MAGIC);
        byte[] rest = new byte[FRAME_BYTES - 4];
        WireBytes.readFully(in, rest); // mid-frame EOF throws — the magic is already consumed
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code PSTB} frame — mirrors {@code PostureFrameCodec.decodeBlock}. */
    public static ClientPostureBlock decodeFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IOException("posture block frame must be " + FRAME_BYTES + " bytes, got "
                    + (frame == null ? "null" : frame.length));
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("posture block bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("posture block wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("posture block unsupported schema version: " + schema);
        }
        int flags = frame[off] & 0xff;
        off += 1;
        if ((flags & ~FLAG_EXPERIMENT_TAG) != 0) {
            throw new IOException("posture block unknown flag bits: 0x"
                    + Integer.toHexString(flags));
        }
        int knobCount = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
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
            pairs[i] = frame[off + i] & 0xff;
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
        int actualCrc = WireBytes.getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("posture block frame CRC mismatch");
        }
        return new ClientPostureBlock(tag, pairs);
    }

    /**
     * The knob-set law — mirrors {@code PostureFrameCodec.knobPairsError} exactly: even length,
     * at most {@link WireKnob#KNOB_SLOTS} pairs, every id registered in {@link WireKnob}, ids
     * strictly ascending, every value inside its knob's choice table. Returns the reason or
     * {@code null} when legal.
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
     * Read + validate the tagLen + zero-padded tag buffer at {@code off} — mirrors the server
     * codec's {@code readTagBuffer} exactly (fail-closed padding, UTF-8 round-trip guard, the
     * single-line-printable law = {@link WireTextLaw#isPrintableWireChar}, the server's
     * {@code validateExperimentTag} law; rejects name a length or an
     * index, never the tag); returns {@code ""} for the len-0 sentinel.
     */
    private static String readTagBuffer(byte[] frame, int off) throws IOException {
        int len = frame[off] & 0xff;
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
        // fail closed rather than adopt a mangled tag (the server codec's exact guard).
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len) {
            throw new IOException("posture block experiment tag is not valid UTF-8");
        }
        for (int i = 0; i < len; i++) {
            if (reencoded[i] != frame[tagStart + i]) {
                throw new IOException("posture block experiment tag is not valid UTF-8");
            }
        }
        if (!value.equals(value.trim())) {
            throw new IOException("posture block experiment tag must be trimmed ("
                    + value.length() + " chars)");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!WireTextLaw.isPrintableWireChar(c)) {
                throw new IOException("posture block experiment tag must be single-line "
                        + "printable (control char at index " + i + ")");
            }
        }
        return value;
    }
}
