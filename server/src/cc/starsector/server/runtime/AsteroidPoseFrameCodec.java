package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AsteroidPoseControlMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the {@code ASTP} batched asteroid-field pose frame
 * ({@link AsteroidPoseControlMessage}). Envelope byte-identical to
 * {@link PilotAimFrameCodec} - {@code magic | schema | headerBytes | wireTypeCode | bodyLen | body |
 * CRC32} - registry-checked and fail-closed the same way: magic / schema / wire-identity /
 * declared-size mismatch and CRC32 corruption all throw {@link IOException}.
 *
 * <p>Body v1, fixed-width (NO UTF): {@code i64 simTick | u8 rowCount (1..29)} then per row (39 B)
 * {@code i32 asteroidId | u8 kind | u8 size | u8 ring | f32 radius | f32 hp | f32 locX | f32 locY |
 * f32 facing | f32 velX | f32 velY | f32 angVel}. {@code bodyLen == 9 + 39 * rowCount} exactly;
 * trailing bytes are refused. Worst case 29 rows = 1160 B frame, inside the 1164 B datagram cap
 * (checked statically below). The per-row law is enforced at record construction (encode) and
 * re-checked on decode (IOException). The server NEVER reads this frame off the wire - the decode
 * exists for the offline readers ({@code DownFrameFamily}) and the goldens only.
 *
 */
public final class AsteroidPoseFrameCodec {

    /** Frame magic: {@code "ASTP"}, distinct from every {@code DownFrameFamily} magic. */
    public static final int MAGIC = 0x41535450; // "ASTP"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverAsteroidPoseWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count

    /** Row kinds: 1 ALIVE (mint-or-adopt), 2 DESTROYED (hp reached 0 by damage), 3 EXPIRED (keeper). */
    public static final byte KIND_ALIVE = 1;
    public static final byte KIND_DESTROYED = 2;
    public static final byte KIND_EXPIRED = 3;

    /** Hard row cap per frame (29 rows = 1160 B frame, under the cap). */
    public static final int MAX_ROWS = 29;
    /** Fixed row width: i32 id + 3 x u8 + 8 x f32. */
    public static final int ROW_BYTES = Integer.BYTES + 3 + 8 * Float.BYTES;
    /** Body prefix: i64 simTick + u8 rowCount. */
    public static final int BODY_PREFIX_BYTES = Long.BYTES + 1;
    /** The datagram payload budget the whole frame must fit ({@code PilotAimFrameCodec} precedent). */
    public static final int MAX_FRAME_BYTES = 1164;
    private static final int MAX_BODY_BYTES = BODY_PREFIX_BYTES + MAX_ROWS * ROW_BYTES;
    /** Computed worst-case frame: 16 + 9 + 29*39 + 4 = 1160 B. */
    public static final int MAX_COMPUTED_FRAME_BYTES = HEADER_BYTES + MAX_BODY_BYTES + Integer.BYTES;

    /** Row plausibility caps (the fail-closed law). */
    public static final float MIN_RADIUS = 1f;
    public static final float MAX_RADIUS = 200f;
    public static final float MAX_HP = 1e5f;
    public static final float MAX_LOC_ABS = 100000f;
    public static final float MAX_VEL_ABS = 10000f;
    public static final float MAX_ANG_VEL_ABS = 3600f;
    public static final int MAX_SIZE_INDEX = 3;

    static {
        // The budget check is static, not a comment.
        if (MAX_COMPUTED_FRAME_BYTES > MAX_FRAME_BYTES) {
            throw new IllegalStateException("ASTP worst-case frame " + MAX_COMPUTED_FRAME_BYTES
                    + " B exceeds the wire budget " + MAX_FRAME_BYTES + " B");
        }
    }

    /**
     * One asteroid pose row. Validation is by construction (encode) and re-checked on decode (a wire
     * frame that fails it is an {@link IOException}, the RAPP/AIMP decode idiom).
     *
     */
    public record AsteroidRow(int asteroidId, byte kind, byte size, byte ring, float radius, float hp,
                              float locX, float locY, float facing, float velX, float velY, float angVel) {
        public AsteroidRow {
            // the exact-set law; NaN fails every range test.
            if (asteroidId < 1) {
                throw new IllegalArgumentException("asteroidId must be >= 1: " + asteroidId);
            }
            if (kind != KIND_ALIVE && kind != KIND_DESTROYED && kind != KIND_EXPIRED) {
                throw new IllegalArgumentException("asteroid kind invalid: " + kind);
            }
            if (size < 0 || size > MAX_SIZE_INDEX) {
                throw new IllegalArgumentException("asteroid size invalid: " + size);
            }
            if (ring != 0) {
                throw new IllegalArgumentException("asteroid ring must be 0 (v1): " + ring);
            }
            if (!(radius >= MIN_RADIUS && radius <= MAX_RADIUS)) {
                throw new IllegalArgumentException("asteroid radius implausible: " + radius);
            }
            if (!(hp >= 0f && hp <= MAX_HP)) {
                throw new IllegalArgumentException("asteroid hp implausible: " + hp);
            }
            if (kind == KIND_ALIVE ? !(hp > 0f) : hp != 0f) {
                throw new IllegalArgumentException("asteroid hp " + hp + " illegal for kind " + kind);
            }
            if (!(Math.abs(locX) <= MAX_LOC_ABS && Math.abs(locY) <= MAX_LOC_ABS)) {
                throw new IllegalArgumentException("asteroid loc implausible: " + locX + "," + locY);
            }
            if (!(Math.abs(velX) <= MAX_VEL_ABS && Math.abs(velY) <= MAX_VEL_ABS)) {
                throw new IllegalArgumentException("asteroid vel implausible: " + velX + "," + velY);
            }
            if (!Float.isFinite(facing)) {
                throw new IllegalArgumentException("asteroid facing not finite: " + facing);
            }
            if (!(Math.abs(angVel) <= MAX_ANG_VEL_ABS)) {
                throw new IllegalArgumentException("asteroid angVel implausible: " + angVel);
            }
        }
    }

    /** One decoded frame (offline readers + goldens). */
    public record Frame(long simTick, List<AsteroidRow> rows) {
        public Frame {
            //
            if (simTick < 0L) {
                throw new IllegalArgumentException("asteroid frame simTick negative: " + simTick);
            }
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        }
    }

    private AsteroidPoseFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    /** Encode one ASTP frame to its full wire bytes. */
    public static byte[] encode(long simTick, List<AsteroidRow> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                HEADER_BYTES + BODY_PREFIX_BYTES + rows.size() * ROW_BYTES + Integer.BYTES);
        writeFrame(out, simTick, rows);
        return out.toByteArray();
    }

    /** Write one ASTP control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, long simTick, List<AsteroidRow> rows) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rows, "rows");
        if (simTick < 0L) {
            throw new IOException("asteroid-pose frame simTick negative: " + simTick);
        }
        if (rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new IOException("asteroid-pose frame row count invalid: " + rows.size()
                    + " (1.." + MAX_ROWS + ")");
        }
        byte[] body = encodeBody(simTick, rows);
        long checksum = checksum(body);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(SCHEMA_VERSION);
        data.writeShort(HEADER_BYTES);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeInt(body.length);
        data.write(body);
        data.writeInt((int) checksum);
        data.flush();
        return HEADER_BYTES + body.length + Integer.BYTES;
    }

    /**
     * Read one ASTP control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed off
     * {@code data} and validated by the caller (the demux peeks the magic to route the frame).
     * Validates schema, header size, wire identity, declared body size (must equal the fixed-width
     * row arithmetic exactly), CRC32, and the per-row law; throws {@link IOException} on any mismatch.
     */
    public static Frame readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("asteroid-pose frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("asteroid-pose frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes < BODY_PREFIX_BYTES + ROW_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("asteroid-pose frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("asteroid-pose frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static byte[] encodeBody(long simTick, List<AsteroidRow> rows) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(BODY_PREFIX_BYTES + rows.size() * ROW_BYTES);
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeLong(simTick);
        data.writeByte(rows.size());
        for (AsteroidRow row : rows) {
            Objects.requireNonNull(row, "row");
            data.writeInt(row.asteroidId());
            data.writeByte(row.kind());
            data.writeByte(row.size());
            data.writeByte(row.ring());
            data.writeFloat(row.radius());
            data.writeFloat(row.hp());
            data.writeFloat(row.locX());
            data.writeFloat(row.locY());
            data.writeFloat(row.facing());
            data.writeFloat(row.velX());
            data.writeFloat(row.velY());
            data.writeFloat(row.angVel());
        }
        data.flush();
        return bytes.toByteArray();
    }

    private static Frame decodeBody(byte[] body) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(body));
        long simTick = data.readLong();
        if (simTick < 0L) {
            throw new IOException("asteroid-pose frame simTick negative: " + simTick);
        }
        int rowCount = data.readUnsignedByte();
        if (rowCount < 1 || rowCount > MAX_ROWS) {
            throw new IOException("asteroid-pose frame row count invalid: " + rowCount);
        }
        if (body.length != BODY_PREFIX_BYTES + rowCount * ROW_BYTES) {
            throw new IOException("asteroid-pose frame body length " + body.length
                    + " does not match rowCount " + rowCount);
        }
        List<AsteroidRow> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            int asteroidId = data.readInt();
            byte kind = data.readByte();
            byte size = data.readByte();
            byte ring = data.readByte();
            float radius = data.readFloat();
            float hp = data.readFloat();
            float locX = data.readFloat();
            float locY = data.readFloat();
            float facing = data.readFloat();
            float velX = data.readFloat();
            float velY = data.readFloat();
            float angVel = data.readFloat();
            try {
                rows.add(new AsteroidRow(asteroidId, kind, size, ring, radius, hp,
                        locX, locY, facing, velX, velY, angVel));
            } catch (IllegalArgumentException badRow) {
                throw new IOException("asteroid-pose frame row invalid", badRow);
            }
        }
        if (data.read() != -1) {
            throw new IOException("asteroid-pose frame body has trailing bytes");
        }
        return new Frame(simTick, rows);
    }

    private static long checksum(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    private static void requireRegisteredSchema(short schemaVersion) throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat()
                    .require(AsteroidPoseControlMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported asteroid-pose frame schema: "
                    + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("asteroid-pose frame schema rejected by compatibility policy: "
                    + unsignedSchemaVersion);
        }
    }

    private static short checkedSchemaVersion(ProtocolRegistryEntry registryEntry) {
        int schemaVersion = registryEntry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("wire schema version exceeds signed short range: " + schemaVersion);
        }
        return (short) schemaVersion;
    }
}
