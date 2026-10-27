package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.SeatStateCause;
import cc.starsector.server.protocol.SeatStateEvent;
import cc.starsector.server.protocol.ServerSeatStateEventMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for the server→client {@code SEAT} seat-state event
 * ({@link ServerSeatStateEventMessage}, payload {@link SeatStateEvent}) — the D4.4
 * seat-state replication family, wire shape frozen.
 *
 * <p>The SNMS twin ({@link SeatNameFrameCodec}): magic + registered wire type code + schema
 * header, fixed width, CRC32 over everything before the trailer, fail-closed reads. The client
 * mod does NOT load this class (its static type-code initializers drag
 * {@link NetworkMessageRegistry} into the game JVM); it speaks the same bytes through the
 * hand-mirrored {@code ClientSeatStateReader}, and the game-free verifier
 * byte-compares the mirror against this codec —
 * any drift breaks it.
 *
 * <p>Payload (24 bytes, big-endian): {@code simTick i64, seatId u16, occupied u8, shipId i64
 * (0 = no hull), cause u8 (}{@link SeatStateCause}{@code wire codes, frozen), seatSeq u32}.
 * Full-state per frame — {@code occupied}/{@code shipId} are the seat's CURRENT two-axis state
 * at emission, not a delta. Fail-closed decode: an {@code occupied} byte other than 0/1 or an
 * unknown cause code rejects the frame.
 */
public final class SeatStateFrameCodec {

    /** SEAT magic = ASCII "SEAT" (seat-state event, down). */
    public static final int SEAT_STATE_MAGIC = 0x53454154;

    /** Registered wire type code this frame carries. */
    public static final int SEAT_STATE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverSeatStateEventWireEntry().wireTypeCode();

    public static final short SEAT_STATE_SCHEMA_VERSION =
            (short) ServerSeatStateEventMessage.SCHEMA_VERSION;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(long) + seatId(u16) + occupied(u8) + shipId(long) + cause(u8) + seatSeq(u32). */
    public static final int SEAT_STATE_PAYLOAD_BYTES =
            Long.BYTES + Short.BYTES + 1 + Long.BYTES + 1 + Integer.BYTES; // 24
    public static final int SEAT_STATE_FRAME_BYTES =
            HEADER_BYTES + SEAT_STATE_PAYLOAD_BYTES + Integer.BYTES; // 38

    private SeatStateFrameCodec() {}

    /** Encode one {@code SEAT} frame ({@link SeatStateEvent} field bounds already hold by construction). */
    public static byte[] encodeSeatState(SeatStateEvent event) {
        Objects.requireNonNull(event, "event");
        byte[] buf = new byte[SEAT_STATE_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, SEAT_STATE_MAGIC);
        off = putInt(buf, off, SEAT_STATE_TYPE_CODE);
        off = putShort(buf, off, SEAT_STATE_SCHEMA_VERSION);
        off = putLong(buf, off, event.simTick());
        off = putShort(buf, off, event.seatId());
        buf[off++] = (byte) (event.occupied() ? 1 : 0);
        off = putLong(buf, off, event.shipId());
        buf[off++] = (byte) event.cause().wireCode();
        off = putInt(buf, off, (int) event.seatSeq());
        // off now == HEADER_BYTES + SEAT_STATE_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code SEAT} to {@code out} as exactly {@link #SEAT_STATE_FRAME_BYTES} bytes. */
    public static void writeSeatState(OutputStream out, SeatStateEvent event) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeSeatState(event);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code SEAT} from {@code in} (game-free tests / round-trip verifier; the
     * live client reads it via the mirrored {@code ClientSeatStateReader} off its demux). Returns
     * {@code null} at a clean end of stream (zero bytes); fail-closed otherwise.
     */
    public static SeatStateEvent readSeatState(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(SEAT_STATE_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != SEAT_STATE_FRAME_BYTES) {
            throw new IOException("seat state frame truncated: expected "
                    + SEAT_STATE_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decodeSeatState(frame);
    }

    /** Decode + validate one full {@code SEAT} frame (fail-closed; verifier + read path). */
    public static SeatStateEvent decodeSeatState(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != SEAT_STATE_FRAME_BYTES) {
            throw new IOException("seat state frame must be " + SEAT_STATE_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + SEAT_STATE_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != SEAT_STATE_MAGIC) {
            throw new IOException("seat state bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != SEAT_STATE_TYPE_CODE) {
            throw new IOException("seat state wire type code mismatch: expected "
                    + SEAT_STATE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != SEAT_STATE_SCHEMA_VERSION) {
            throw new IOException("seat state unsupported schema version: " + schema);
        }
        long simTick = getLong(frame, off); off += 8;
        int seatId = getUnsignedShort(frame, off); off += 2;
        int occupiedByte = frame[off++] & 0xFF;
        if (occupiedByte > 1) {
            throw new IOException("seat state occupied flag out of range: " + occupiedByte);
        }
        long shipId = getLong(frame, off); off += 8;
        int causeCode = frame[off++] & 0xFF;
        long seatSeq = getInt(frame, off) & 0xFFFF_FFFFL; off += 4;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("seat state frame CRC mismatch");
        }
        SeatStateCause cause;
        try {
            cause = SeatStateCause.fromWireCode(causeCode);
        } catch (IllegalArgumentException badCause) {
            throw new IOException("seat state " + badCause.getMessage(), badCause);
        }
        try {
            return new SeatStateEvent(simTick, seatId, occupiedByte == 1, shipId, cause, seatSeq);
        } catch (IllegalArgumentException badFields) {
            throw new IOException("seat state fields invalid: " + badFields.getMessage(), badFields);
        }
    }

}
