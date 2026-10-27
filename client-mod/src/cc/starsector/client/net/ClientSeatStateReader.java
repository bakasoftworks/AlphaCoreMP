package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code SEAT} seat-state event wire frame (seat-state
 * replication): the receiving half of the server's {@code SeatStateFrameCodec}
 * ({@code server.seat_state_event} v1). The N-peer serve loop emits a per-admit BASELINE burst
 * (one frame per descriptor seat, re-emitted post-{@code MATCH_RESET}) plus one frame per
 * seat-machine transition; the {@link MultiplexedServerStreamReader} routes it here by its
 * leading magic.
 *
 * <p>Like {@link ClientSeatNamesReader}, the mod deliberately does not bundle the server codec
 * (whose static type-code initializers drag {@code NetworkMessageRegistry} into the game JVM).
 * It reads exactly the fixed-width bytes the server writes — magic / wireTypeCode / schema /
 * {@code simTick i64, seatId u16, occupied u8, shipId i64, cause u8, seatSeq u32} /
 * CRC32-over-body — over plain {@link InputStream} primitives plus the allowlisted {@link CRC32}.
 * The game-free cross-check streams real server
 * {@code SeatStateFrameCodec} bytes through this reader and asserts the decoded events match,
 * keeping the mirrors in sync.
 *
 * <p>Fail-closed, mirroring the server decode exactly: a wire-type-code or schema mismatch, an
 * {@code occupied} byte other than 0/1, an unknown cause code, a field outside its frozen bounds,
 * or a CRC32 mismatch all throw {@link IOException}.
 */
public final class ClientSeatStateReader {

    /** Frame magic: ASCII "SEAT". Mirrors {@code SeatStateFrameCodec.SEAT_STATE_MAGIC}. */
    public static final int MAGIC = 0x53454154;

    /** Registry message id this frame carries. Mirrors {@code ServerSeatStateEventMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.seat_state_event";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerSeatStateEventMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code SeatStateFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(i64) + seatId(u16) + occupied(u8) + shipId(i64) + cause(u8) + seatSeq(u32). */
    public static final int PAYLOAD_BYTES = Long.BYTES + Short.BYTES + 1 + Long.BYTES + 1 + Integer.BYTES; // 24
    /** Fixed frame width. Mirrors {@code SeatStateFrameCodec.SEAT_STATE_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 38

    private ClientSeatStateReader() {
    }

    /**
     * Decode one seat-state frame whose 4-byte {@link #MAGIC} has <em>already</em> been read and
     * validated by the caller — the {@link MultiplexedServerStreamReader} entry point. The CRC32
     * covers the frame INCLUDING the magic (the server codec checksums {@code [0, 34)} of the full
     * frame), so this reconstructs the full frame buffer before validating.
     */
    public static ClientSeatStateEvent readAfterMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES];
        WireBytes.putInt(frame, 0, MAGIC);
        byte[] rest = new byte[FRAME_BYTES - 4];
        WireBytes.readFully(in, rest); // mid-frame EOF throws — the magic is already consumed
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code SEAT} frame — mirrors {@code SeatStateFrameCodec.decodeSeatState}. */
    public static ClientSeatStateEvent decodeFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IOException("seat state frame must be " + FRAME_BYTES + " bytes, got "
                    + (frame == null ? "null" : frame.length));
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("seat state bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("seat state wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("seat state unsupported schema version: " + schema);
        }
        long simTick = WireBytes.getLong(frame, off);
        off += 8;
        int seatId = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        int occupiedByte = frame[off] & 0xff;
        off += 1;
        if (occupiedByte > 1) {
            throw new IOException("seat state occupied flag out of range: " + occupiedByte);
        }
        long shipId = WireBytes.getLong(frame, off);
        off += 8;
        int causeCode = frame[off] & 0xff;
        off += 1;
        long seatSeq = WireBytes.getInt(frame, off) & 0xFFFF_FFFFL;
        off += 4;
        int actualCrc = WireBytes.getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("seat state frame CRC mismatch");
        }
        try {
            return new ClientSeatStateEvent(
                    simTick, seatId, occupiedByte == 1, shipId, causeCode, seatSeq);
        } catch (IllegalArgumentException badFields) {
            throw new IOException("seat state fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
