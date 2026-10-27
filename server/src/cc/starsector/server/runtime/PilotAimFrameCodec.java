package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.PilotAimControlMessage;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
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
 * Product-owned binary frame codec for the {@code AIMP} batched per-piloted-seat aim-point
 * control frame ({@link PilotAimControlMessage}). Envelope
 * byte-identical to {@link ModuleMountFrameCodec} — {@code magic | schema | headerBytes |
 * wireTypeCode | bodyLen | body | CRC32} — registry-checked and fail-closed the same way:
 * magic / schema / wire-identity / declared-size mismatch and CRC32 corruption all throw
 * {@link IOException}.
 *
 * <p>Body v1, fixed-width (NO UTF): {@code u8 rowCount (1..64)} then per row
 * {@code u64 shipWireId | i32 qx | i32 qy}. {@code qx/qy} are the seat's aim WORLD point on a
 * fixed-point 1/8-world-unit grid ({@code q = round(world * 8)}); plausibility is enforced at
 * record construction BOTH trees — {@code |world| ≤ 100000 u} ({@code |q| ≤ 800000}), the GPEE
 * {@code muzzleUsable} precedent. Worst case 64 rows = 1025 B body, inside the datagram
 * 1164 B frame cap by construction. Validation is by construction (encode) and re-checked on
 * decode.
 */
public final class PilotAimFrameCodec {

    /** Frame magic: {@code "AIMP"}, distinct from every {@code DownFrameFamily} magic. */
    public static final int MAGIC = 0x41494D50; // "AIMP"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverPilotAimWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count

    /** Fixed-point aim grid: wire steps per world unit ({@code q = round(world * 8)}). */
    public static final int GRID_STEPS_PER_UNIT = 8;
    /** Plausibility ceiling on the aim world point, in world units (GPEE muzzle precedent). */
    public static final int MAX_PLAUSIBLE_AIM_ABS_U = 100000;
    /** The same ceiling on the quantized wire value. */
    public static final int MAX_QUANT_ABS = MAX_PLAUSIBLE_AIM_ABS_U * GRID_STEPS_PER_UNIT;
    /** Hard row cap per frame (u8 count; 64 rows = 1025 B body, under the frame cap). */
    public static final int MAX_ROWS = 64;
    private static final int ROW_BYTES = Long.BYTES + Integer.BYTES + Integer.BYTES;
    /** The datagram payload budget the whole frame must fit. */
    public static final int MAX_FRAME_BYTES = 1164;
    private static final int MAX_BODY_BYTES = 1 + MAX_ROWS * ROW_BYTES;

    /**
     * One seat's aim row: {shipWireId, qx, qy} on the 1/8-u grid. Validation is by
     * construction (encode) and re-checked on decode (a wire frame that fails it is an
     * {@link IOException}, the RAPP decode idiom).
     */
    public record PilotAimRow(long shipWireId, int qx, int qy) {
        public PilotAimRow {
            if (shipWireId < 1L) {
                throw new IllegalArgumentException("shipWireId must be positive: " + shipWireId);
            }
            if (Math.abs(qx) > MAX_QUANT_ABS || Math.abs(qy) > MAX_QUANT_ABS) {
                throw new IllegalArgumentException("aim point implausible: qx=" + qx
                        + " qy=" + qy + " (|q| cap " + MAX_QUANT_ABS + ")");
            }
        }
    }

    private PilotAimFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    /** Quantize one aim world coordinate to the wire grid (caller pre-checks plausibility). */
    public static int quantize(float worldCoord) {
        return Math.round(worldCoord * GRID_STEPS_PER_UNIT);
    }

    /** Encode one AIMP frame to its full wire bytes. */
    public static byte[] encode(List<PilotAimRow> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, rows);
        return out.toByteArray();
    }

    /** Write one AIMP control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, List<PilotAimRow> rows) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new IOException("pilot-aim frame row count invalid: " + rows.size()
                    + " (1.." + MAX_ROWS + ")");
        }
        byte[] body = encodeBody(rows);
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
     * Read one AIMP control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed off
     * {@code data} and validated by the caller (the demux peeks the magic to route the frame).
     * Validates schema, header size, wire identity, declared body size (must equal the
     * fixed-width row arithmetic exactly), CRC32, and the per-row law; throws
     * {@link IOException} on any mismatch.
     */
    public static List<PilotAimRow> readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("pilot-aim frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("pilot-aim frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes < 1 + ROW_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("pilot-aim frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("pilot-aim frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static byte[] encodeBody(List<PilotAimRow> rows) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 + rows.size() * ROW_BYTES);
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(rows.size());
        for (PilotAimRow row : rows) {
            data.writeLong(row.shipWireId());
            data.writeInt(row.qx());
            data.writeInt(row.qy());
        }
        data.flush();
        return bytes.toByteArray();
    }

    private static List<PilotAimRow> decodeBody(byte[] body) throws IOException {
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(body));
        int rowCount = data.readUnsignedByte();
        if (rowCount < 1 || rowCount > MAX_ROWS) {
            throw new IOException("pilot-aim frame row count invalid: " + rowCount);
        }
        if (body.length != 1 + rowCount * ROW_BYTES) {
            throw new IOException("pilot-aim frame body length " + body.length
                    + " does not match rowCount " + rowCount);
        }
        List<PilotAimRow> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            long shipWireId = data.readLong();
            int qx = data.readInt();
            int qy = data.readInt();
            try {
                rows.add(new PilotAimRow(shipWireId, qx, qy));
            } catch (IllegalArgumentException badRow) {
                throw new IOException("pilot-aim frame row invalid", badRow);
            }
        }
        if (data.read() != -1) {
            throw new IOException("pilot-aim frame body has trailing bytes");
        }
        return rows;
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
                    .require(PilotAimControlMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported pilot-aim frame schema: "
                    + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("pilot-aim frame schema rejected by compatibility policy: "
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
