package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerHulkPoseRecordMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getFloat;
import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putFloat;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for the corpse-pose keyframe — the server→both
 * {@code HKPS} record ({@link ServerHulkPoseRecordMessage}) fanned at a low cadence per
 * corpse-family piece so clients ADOPT the server's corpse kinematics (F4: aimed shots at a
 * diverged local corpse miss the authoritative one; hulk drift is deterministic ballistic
 * coast, so one exact adoption holds between keyframes).
 *
 * <p>The {@link HulkFractureFrameCodec} twin: magic + registered wire type code + schema
 * header, fixed width, CRC32 over everything before the trailer, fail-closed reads. The client
 * mod does NOT load this class (its static type-code initializer drags
 * {@link NetworkMessageRegistry} into the game JVM); it speaks the same bytes through a
 * hand-mirrored reader, byte-compared by the game-free verifier at the s-B client half.
 *
 * <p>Frame layout (64 bytes, schema v1): header 10 (magic u32, typeCode u32,
 * schema u16) + payload 50 ({@code simTick} i64, {@code rootEntityId} i64, {@code deathTick}
 * i64, {@code sizeRank} u8, {@code pieceCount} u8, {@code locX}/{@code locY}/{@code facing}/
 * {@code velX}/{@code velY}/{@code angVel} f32 ×6) + CRC32 u32 over {@code [0, 60)}.
 */
public final class HulkPoseFrameCodec {

    /** HKPS magic = ASCII "HKPS" (HulK PoSe keyframe, down). */
    public static final int POSE_MAGIC = 0x484B5053;

    /** Registered wire type code this frame carries. */
    public static final int POSE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverHulkPoseRecordWireEntry().wireTypeCode();

    public static final short POSE_SCHEMA_VERSION =
            (short) ServerHulkPoseRecordMessage.SCHEMA_VERSION;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick/rootEntityId/deathTick (i64 ×3) + sizeRank/pieceCount (u8 ×2) + pose (f32 ×6). */
    public static final int POSE_PAYLOAD_BYTES =
            Long.BYTES + Long.BYTES + Long.BYTES + 2 + Float.BYTES * 6; // 50
    public static final int POSE_FRAME_BYTES =
            HEADER_BYTES + POSE_PAYLOAD_BYTES + Integer.BYTES; // 64

    /**
     * One decoded {@code HKPS} corpse-pose keyframe. {@code rootEntityId} + {@code deathTick}
     * identify the corpse family exactly like the {@code HKFR} edge (the client binds by
     * NEAREST death tick and latches — an unsplit corpse's family latches off its FIRST
     * keyframe, the main case with no edges at all); {@code sizeRank} names the piece by bbox
     * area, largest = 0 (the REMOVE idiom); {@code pieceCount} is the family size this keyframe
     * set describes (the client drops on a ranked-size mismatch); the six floats are the exact
     * pose to adopt: location, facing (deg), velocity, angular velocity (deg/s).
     */
    public record PoseKeyframe(
            long simTick,
            long rootEntityId,
            long deathTick,
            int sizeRank,
            int pieceCount,
            float locX,
            float locY,
            float facing,
            float velX,
            float velY,
            float angVel) {
        public PoseKeyframe {
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            if (rootEntityId < 1L) {
                throw new IllegalArgumentException(
                        "rootEntityId must be >= 1 (a wire entity id); got " + rootEntityId);
            }
            if (deathTick < 0L) {
                throw new IllegalArgumentException("deathTick must be >= 0; got " + deathTick);
            }
            if (deathTick > simTick) {
                throw new IllegalArgumentException("deathTick must be <= simTick (a family's"
                        + " keyframes never precede its death); got deathTick=" + deathTick
                        + " simTick=" + simTick);
            }
            if (pieceCount < 1 || pieceCount > 0xFF) {
                throw new IllegalArgumentException(
                        "pieceCount must be 1..255 (a keyframe describes a live family); got "
                                + pieceCount);
            }
            if (sizeRank < 0 || sizeRank >= pieceCount) {
                throw new IllegalArgumentException("sizeRank must be 0.." + (pieceCount - 1)
                        + " (< pieceCount); got " + sizeRank);
            }
            if (!Float.isFinite(locX) || !Float.isFinite(locY) || !Float.isFinite(facing)
                    || !Float.isFinite(velX) || !Float.isFinite(velY) || !Float.isFinite(angVel)) {
                throw new IllegalArgumentException("pose components must all be finite; got locX="
                        + locX + " locY=" + locY + " facing=" + facing + " velX=" + velX
                        + " velY=" + velY + " angVel=" + angVel);
            }
        }
    }

    private HulkPoseFrameCodec() {}

    /** Encode one {@code HKPS} keyframe as exactly {@link #POSE_FRAME_BYTES} bytes. */
    public static byte[] encode(PoseKeyframe keyframe) {
        Objects.requireNonNull(keyframe, "keyframe");
        byte[] buf = new byte[POSE_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, POSE_MAGIC);
        off = putInt(buf, off, POSE_TYPE_CODE);
        off = putShort(buf, off, POSE_SCHEMA_VERSION);
        off = putLong(buf, off, keyframe.simTick());
        off = putLong(buf, off, keyframe.rootEntityId());
        off = putLong(buf, off, keyframe.deathTick());
        buf[off++] = (byte) keyframe.sizeRank();
        buf[off++] = (byte) keyframe.pieceCount();
        off = putFloat(buf, off, keyframe.locX());
        off = putFloat(buf, off, keyframe.locY());
        off = putFloat(buf, off, keyframe.facing());
        off = putFloat(buf, off, keyframe.velX());
        off = putFloat(buf, off, keyframe.velY());
        off = putFloat(buf, off, keyframe.angVel());
        // off now == HEADER_BYTES + POSE_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code HKPS} to {@code out} as exactly {@link #POSE_FRAME_BYTES} bytes. */
    public static void write(OutputStream out, PoseKeyframe keyframe) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encode(keyframe);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code HKPS} from {@code in} (game-free tests / round-trip verifier; the
     * live client reads it via a mirrored reader off its demux). Returns {@code null} at a clean
     * end of stream; fail-closed otherwise.
     */
    public static PoseKeyframe read(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(POSE_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != POSE_FRAME_BYTES) {
            throw new IOException("hulk pose frame truncated: expected "
                    + POSE_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decode(frame);
    }

    /** Decode + validate one full {@code HKPS} frame (fail-closed; verifier + read path). */
    public static PoseKeyframe decode(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != POSE_FRAME_BYTES) {
            throw new IOException("hulk pose frame must be " + POSE_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + POSE_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != POSE_MAGIC) {
            throw new IOException("hulk pose bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != POSE_TYPE_CODE) {
            throw new IOException("hulk pose wire type code mismatch: expected "
                    + POSE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != POSE_SCHEMA_VERSION) {
            throw new IOException("hulk pose unsupported schema version: " + schema);
        }
        long simTick = getLong(frame, off); off += 8;
        long rootEntityId = getLong(frame, off); off += 8;
        long deathTick = getLong(frame, off); off += 8;
        int sizeRank = frame[off++] & 0xFF;
        int pieceCount = frame[off++] & 0xFF;
        float locX = getFloat(frame, off); off += 4;
        float locY = getFloat(frame, off); off += 4;
        float facing = getFloat(frame, off); off += 4;
        float velX = getFloat(frame, off); off += 4;
        float velY = getFloat(frame, off); off += 4;
        float angVel = getFloat(frame, off); off += 4;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("hulk pose frame CRC mismatch");
        }
        try {
            return new PoseKeyframe(simTick, rootEntityId, deathTick, sizeRank, pieceCount,
                    locX, locY, facing, velX, velY, angVel);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "hulk pose fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
