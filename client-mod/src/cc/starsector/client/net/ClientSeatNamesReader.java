package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code SNMS} seat-name table wire frame (the
 * seat-name handshake): the receiving half of the server's {@code SeatNameFrameCodec}
 * ({@code server.seat_names} v1). The server emits it ONCE per connected transport, right after
 * both seats are known (pre-sink, BEFORE the round's first {@code AWSS}), carrying BOTH seats'
 * resolved names in one frame so the client applies them atomically; the
 * {@link MultiplexedServerStreamReader} routes it here by its leading magic.
 *
 * <p>Like {@link ClientMatchResetReader}, the mod deliberately does not bundle the server codec
 * (whose static type-code initializers drag {@code NetworkMessageRegistry} into the game JVM —
 * see {@link SeatNameWriter}, the sending half of this handshake). It reads exactly the
 * fixed-width bytes the server writes — magic / wireTypeCode / schema / 2×(nameLen + 48B
 * zero-padded UTF-8 buffer) / CRC32-over-body — over plain {@link InputStream} primitives plus
 * the allowlisted {@link CRC32}. The game-free cross-check
 * streams real server {@code SeatNameFrameCodec}
 * bytes through this reader and asserts the decoded names match, keeping the mirrors in sync.
 *
 * <p>Fail-closed, mirroring the server decode exactly: a wire-type-code or schema mismatch, a
 * {@code nameLen} out of range, NONZERO padding beyond {@code nameLen}, a name that is not valid
 * UTF-8 or violates the {@code parseSeatName} contract, or a CRC32 mismatch all throw
 * {@link IOException}. {@code nameLen} 0 decodes as the empty-string "no override" sentinel —
 * a valid frame, per the codec contract.
 */
public final class ClientSeatNamesReader {

    /** Frame magic: ASCII "SNMS". Mirrors {@code SeatNameFrameCodec.SEAT_NAMES_MAGIC}. */
    public static final int MAGIC = 0x534E4D53;

    /** Registry message id this frame carries. Mirrors {@code ServerSeatNamesMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.seat_names";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerSeatNamesMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code SeatNameFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** Fixed zero-padded UTF-8 buffer per name. Mirrors {@code SeatNameFrameCodec.NAME_BUFFER_BYTES}. */
    public static final int NAME_BUFFER_BYTES = 48;
    /** 2 × (nameLen(1B) + name buffer), seat 1 first. Mirrors {@code SEAT_NAMES_PAYLOAD_BYTES}. */
    public static final int PAYLOAD_BYTES = 2 * (1 + NAME_BUFFER_BYTES); // 98
    /** Fixed frame width. Mirrors {@code SeatNameFrameCodec.SEAT_NAMES_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 112

    private ClientSeatNamesReader() {
    }

    /**
     * Decode one seat-names frame whose 4-byte {@link #MAGIC} has <em>already</em> been read and
     * validated by the caller — the {@link MultiplexedServerStreamReader} entry point. The CRC32
     * covers the frame INCLUDING the magic (the server codec checksums {@code [0, 108)} of the full
     * frame), so this reconstructs the full frame buffer before validating.
     */
    public static ClientSeatNames readAfterMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES];
        WireBytes.putInt(frame, 0, MAGIC);
        byte[] rest = new byte[FRAME_BYTES - 4];
        WireBytes.readFully(in, rest); // mid-frame EOF throws — the magic is already consumed
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code SNMS} frame — mirrors {@code SeatNameFrameCodec.decodeSeatNames}. */
    public static ClientSeatNames decodeFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IOException("seat names frame must be " + FRAME_BYTES + " bytes, got "
                    + (frame == null ? "null" : frame.length));
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("seat names bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("seat names wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("seat names unsupported schema version: " + schema);
        }
        String seat1 = readNameBuffer(frame, off, "seat names [seat1]");
        off += 1 + NAME_BUFFER_BYTES;
        String seat2 = readNameBuffer(frame, off, "seat names [seat2]");
        off += 1 + NAME_BUFFER_BYTES;
        int actualCrc = WireBytes.getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("seat names frame CRC mismatch");
        }
        try {
            return new ClientSeatNames(seat1, seat2);
        } catch (IllegalArgumentException badFields) {
            throw new IOException("seat names fields invalid: " + badFields.getMessage(), badFields);
        }
    }

    /**
     * Read + validate one nameLen + zero-padded buffer at {@code off} — mirrors the server codec's
     * {@code readNameBuffer} exactly (fail-closed padding, UTF-8 round-trip guard, the
     * {@code parseSeatName} contract via {@link SeatNameWriter#validateSeatName}); returns
     * {@code ""} for the len-0 sentinel.
     */
    private static String readNameBuffer(byte[] frame, int off, String what) throws IOException {
        int len = frame[off] & 0xff;
        if (len > NAME_BUFFER_BYTES) {
            throw new IOException(what + " nameLen out of range: " + len);
        }
        int nameStart = off + 1;
        for (int i = nameStart + len; i < nameStart + NAME_BUFFER_BYTES; i++) {
            if (frame[i] != 0) {
                throw new IOException(what + " nonzero padding beyond nameLen=" + len
                        + " at buffer index " + (i - nameStart));
            }
        }
        if (len == 0) {
            return ""; // the no-override sentinel
        }
        String value = new String(frame, nameStart, len, StandardCharsets.UTF_8);
        // Malformed UTF-8 decodes with replacement chars and would not re-encode byte-identical:
        // fail closed rather than render a mangled name (the server codec's exact guard).
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len) {
            throw new IOException(what + " name is not valid UTF-8");
        }
        for (int i = 0; i < len; i++) {
            if (reencoded[i] != frame[nameStart + i]) {
                throw new IOException(what + " name is not valid UTF-8");
            }
        }
        try {
            SeatNameWriter.validateSeatName(value);
        } catch (IllegalArgumentException bad) {
            throw new IOException(what + " name invalid: " + bad.getMessage(), bad);
        }
        return value;
    }
}
