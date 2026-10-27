package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code HKPS} corpse-pose keyframe wire frame:
 * the receiving half of the server's {@code HulkPoseFrameCodec}
 * ({@code server.hulk_pose_record} v1). The server's detector fans one per corpse-family piece
 * at a low cadence (plus on family creation and on edge ticks) on the reliable CTRL lane — the
 * {@code HKFR} fan shape, NO admission catch-up (a newcomer's world contains no corpses; the
 * periodic cadence self-heals late binds); the {@link MultiplexedServerStreamReader} routes it
 * here by its leading magic.
 *
 * <p>Like {@link ClientHulkFractureRecordReader}, the mod deliberately does not bundle the
 * server codec (whose static type-code initializers drag {@code NetworkMessageRegistry} into
 * the game JVM). It reads exactly the fixed-width 64 bytes the server writes — magic /
 * wireTypeCode / schema / simTick(i64) / rootEntityId(i64) / deathTick(i64) / sizeRank(u8) /
 * pieceCount(u8) / locX/locY/facing/velX/velY/angVel(f32 ×6) / CRC32-over-body — over plain
 * {@link InputStream} primitives plus the allowlisted {@link CRC32}. The game-free cross-check
 * ({@code CcClientSeatNameVerifier}'s HKPS legs) streams real server {@code HulkPoseFrameCodec}
 * bytes through this reader and asserts the decoded records match, keeping the mirrors in sync.
 *
 * <p>Fail-closed, mirroring the server decode exactly: a wire-type-code or schema mismatch, a
 * CRC32 mismatch, or any field outside the {@link ClientHulkPoseRecord} guards (rootEntityId
 * &lt; 1, a sizeRank ≥ pieceCount, a non-finite pose component) all throw {@link IOException}.
 */
public final class ClientHulkPoseRecordReader {

    /** Frame magic: ASCII "HKPS". Mirrors {@code HulkPoseFrameCodec.POSE_MAGIC}. */
    public static final int MAGIC = 0x484B5053;

    /** Registry message id this frame carries. Mirrors {@code ServerHulkPoseRecordMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.hulk_pose_record";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerHulkPoseRecordMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code HulkPoseFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(long) + rootEntityId(long) + deathTick(long) + sizeRank/pieceCount (u8 ×2) +
     * locX/locY/facing/velX/velY/angVel (f32 ×6). Mirrors
     * {@code HulkPoseFrameCodec.POSE_PAYLOAD_BYTES}. */
    public static final int PAYLOAD_BYTES = Long.BYTES * 3 + 2 + Float.BYTES * 6; // 50
    /** Fixed frame width. Mirrors {@code HulkPoseFrameCodec.POSE_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 64

    private ClientHulkPoseRecordReader() {
    }

    /**
     * Decode one corpse-pose frame whose 4-byte {@link #MAGIC} has <em>already</em> been read
     * and validated by the caller — the {@link MultiplexedServerStreamReader} entry point. The
     * CRC32 covers the frame INCLUDING the magic (the server codec checksums {@code [0, 60)} of
     * the full frame), so this reconstructs the full frame buffer before validating.
     */
    public static ClientHulkPoseRecord readAfterMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES];
        WireBytes.putInt(frame, 0, MAGIC);
        byte[] rest = new byte[FRAME_BYTES - 4];
        WireBytes.readFully(in, rest); // mid-frame EOF throws — the magic is already consumed
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code HKPS} frame — mirrors {@code HulkPoseFrameCodec.decode}. */
    public static ClientHulkPoseRecord decodeFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IOException("hulk pose frame must be " + FRAME_BYTES + " bytes, got "
                    + (frame == null ? "null" : frame.length));
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("hulk pose bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("hulk pose wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("hulk pose unsupported schema version: " + schema);
        }
        long simTick = WireBytes.getLong(frame, off);
        off += 8;
        long rootEntityId = WireBytes.getLong(frame, off);
        off += 8;
        long deathTick = WireBytes.getLong(frame, off);
        off += 8;
        int sizeRank = frame[off++] & 0xFF;
        int pieceCount = frame[off++] & 0xFF;
        float locX = Float.intBitsToFloat(WireBytes.getInt(frame, off));
        off += 4;
        float locY = Float.intBitsToFloat(WireBytes.getInt(frame, off));
        off += 4;
        float facing = Float.intBitsToFloat(WireBytes.getInt(frame, off));
        off += 4;
        float velX = Float.intBitsToFloat(WireBytes.getInt(frame, off));
        off += 4;
        float velY = Float.intBitsToFloat(WireBytes.getInt(frame, off));
        off += 4;
        float angVel = Float.intBitsToFloat(WireBytes.getInt(frame, off));
        off += 4;
        int actualCrc = WireBytes.getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("hulk pose frame CRC mismatch");
        }
        try {
            return new ClientHulkPoseRecord(simTick, rootEntityId, deathTick, sizeRank,
                    pieceCount, locX, locY, facing, velX, velY, angVel);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "hulk pose fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
