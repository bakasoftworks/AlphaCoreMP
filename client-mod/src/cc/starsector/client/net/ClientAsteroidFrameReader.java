package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code ASTP} batched asteroid-pose CTRL frame (
 * {@code server.asteroid.pose} schema v1): the receiving half of the server's
 * {@code cc.starsector.server.runtime.AsteroidPoseFrameCodec}. The {@link
 * MultiplexedServerStreamReader} routes the frame here by its leading magic; the session queues
 * it (bounded) and the render-thread {@code AsteroidLane} mints / adopts / kills the local twins.
 *
 *
 * <p>HAND-MIRRORED (the {@link ClientPilotAimReader} twin): the mod never loads the server codec
 * (its static registry initializer drags {@code NetworkMessageRegistry} into the game JVM, and it
 * uses {@code java.io.DataInputStream}, blocked by the mod-script sandbox classloader -- see
 * {@link WireBytes}). Envelope byte-identical to AIMP: {@code u32 magic | u16 schema(1) |
 * u16 headerBytes(16) | u32 wireTypeCode | u32 bodyLen | body | u32 CRC32(body)}; body =
 * {@code i64 simTick | u8 rowCount (1..29)} then rows x 39 B {@code i32 asteroidId | u8 kind |
 * u8 size | u8 ring | f32 radius | f32 hp | f32 locX | f32 locY | f32 facing | f32 velX | f32 velY
 * | f32 angVel}. The game-free cross-check ({@code CcClientSeatNameVerifier}'s ASTP leg) streams
 * real server codec bytes through this reader.
 *
 * <p>Fail-closed, every mismatch = {@link IOException}: schema, header size, wire type code (the
 * mirror of the server decode -- unlike AIMP this reader does NOT tolerate a foreign code), a body
 * length outside the fixed-width arithmetic ({@code bodyLen == 9 + 39*rowCount} EXACTLY, so a
 * trailing byte is refused), a CRC32 mismatch, a row count outside 1..29, or any row outside the
 * {@link ClientAsteroidRow} law. The only wire-sized allocation is the body, bounded by
 * {@link #MAX_BODY_BYTES} BEFORE it is allocated.
 */
public final class ClientAsteroidFrameReader {

    //
    /** Frame magic: ASCII "ASTP". Mirrors {@code AsteroidPoseFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x41535450;

    /** Registry message id this frame carries. Mirrors {@code AsteroidPoseControlMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.asteroid.pose";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code AsteroidPoseFrameCodec.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireTypeCode(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;

    /** Hard row cap per frame. Mirrors {@code AsteroidPoseFrameCodec.MAX_ROWS}. */
    public static final int MAX_ROWS = 29;

    /** Bytes per row: id(4) + kind/size/ring (u8 x3) + 8 x f32. Public for the verifier parity leg. */
    public static final int ROW_BYTES = 4 + 3 + 4 * 8; // 39

    /** Body prefix: simTick(8) + rowCount(1). */
    private static final int BODY_PREFIX_BYTES = 8 + 1;

    /** The fixed-width body bound implied by the row cap (29 rows = 1140 B). */
    private static final int MAX_BODY_BYTES = BODY_PREFIX_BYTES + MAX_ROWS * ROW_BYTES;

    /** The whole-frame max (16 + 1140 + 4 = 1160 B). */
    public static final int MAX_FRAME_BYTES = HEADER_BYTES + MAX_BODY_BYTES + 4;

    private ClientAsteroidFrameReader() {
    }

    /**
     * Decode one asteroid frame from {@code in} (magic included), or return {@code null} at a
     * clean end-of-stream (a frame boundary).
     */
    public static ClientAsteroidFrame read(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("asteroid frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readAfterMagic(in);
    }

    /**
     * Decode one asteroid frame whose 4-byte {@link #MAGIC} has <em>already</em> been read and
     * validated by the caller -- the {@link MultiplexedServerStreamReader} entry point. The frame
     * carries {@code roundEpoch} 0; the session re-wraps it at intake.
     */
    public static ClientAsteroidFrame readAfterMagic(InputStream in) throws IOException {
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("asteroid frame schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("asteroid frame header size mismatch: " + headerBytes);
        }
        int typeCode = WireBytes.readInt(in);
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("asteroid frame wire type code mismatch: "
                    + Integer.toUnsignedString(typeCode));
        }
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < BODY_PREFIX_BYTES + ROW_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("asteroid frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("asteroid frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static ClientAsteroidFrame decodeBody(byte[] body) throws IOException {
        long simTick = WireBytes.getLong(body, 0);
        int rowCount = body[8] & 0xff;
        if (rowCount < 1 || rowCount > MAX_ROWS) {
            throw new IOException("asteroid frame row count invalid: " + rowCount);
        }
        if (body.length != BODY_PREFIX_BYTES + rowCount * ROW_BYTES) {
            throw new IOException("asteroid frame body length " + body.length
                    + " does not match rowCount " + rowCount);
        }
        List<ClientAsteroidRow> rows = new ArrayList<ClientAsteroidRow>(rowCount);
        try {
            for (int i = 0; i < rowCount; i++) {
                int off = BODY_PREFIX_BYTES + i * ROW_BYTES;
                rows.add(new ClientAsteroidRow(
                        WireBytes.getInt(body, off),
                        body[off + 4] & 0xff,
                        body[off + 5] & 0xff,
                        body[off + 6] & 0xff,
                        f32(body, off + 7),
                        f32(body, off + 11),
                        f32(body, off + 15),
                        f32(body, off + 19),
                        f32(body, off + 23),
                        f32(body, off + 27),
                        f32(body, off + 31),
                        f32(body, off + 35)));
            }
            return new ClientAsteroidFrame(simTick, rows, 0);
        } catch (IllegalArgumentException badRow) {
            throw new IOException("asteroid frame row invalid", badRow);
        }
    }

    private static float f32(byte[] b, int off) {
        return Float.intBitsToFloat(WireBytes.getInt(b, off));
    }
}
