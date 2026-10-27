package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ModuleMountControlMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the {@code MMNT} module-mount handover control frame
 * ({@link ModuleMountControlMessage}, W2b).
 * Envelope byte-identical to {@link RosterAppendFrameCodec} — {@code magic | schema |
 * headerBytes | wireTypeCode | bodyLen | body | CRC32} — registry-checked and fail-closed the
 * same way: magic / schema / wire-identity / declared-size mismatch and CRC32 corruption all
 * throw {@link IOException}.
 *
 * <p>Body v1: {@code u64 seatWireId | u64 moduleOldWireId | u8 kind | UTF slotToken}.
 * {@code slotToken} is the ENGINE slot id VERBATIM ({@code "WS 013"}, interior space included) —
 * the vocabulary the client's cluster census keys by; the friendly chat token ({@code citadel})
 * never rides the wire (the friendly-token cut parked every mount client-side).
 * Kind 1 = MOUNT (the seat's fixed wire id repointed at the live module; {@code moduleOldWireId}
 * = the module's retiring above-band id, or the {@code 0} admission-replay sentinel — the client
 * resolves the body by the slot id, old-id is stale-map cleanup only). Kind 2 = DISMOUNT
 * ({@code moduleOldWireId} = the module's FRESH above-band id, required &gt; 0). Validation is
 * by construction (encode) and re-checked on decode.
 */
public final class ModuleMountFrameCodec {

    /** Frame magic: {@code "MMNT"}, distinct from every {@code DownFrameFamily} magic. */
    public static final int MAGIC = 0x4D4D4E54; // "MMNT"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverModuleMountWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count

    public static final int KIND_MOUNT = 1;
    public static final int KIND_DISMOUNT = 2;

    /** Longest slot token the census law admits (mirrors {@code SeatRebindArbiter}). */
    public static final int MAX_TOKEN_CHARS = 24;
    /** The datagram payload budget the whole frame must fit. */
    public static final int MAX_FRAME_BYTES = 1164;
    private static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - Integer.BYTES;

    /**
     * One handover: {seatWireId, moduleOldWireId, kind, slotToken}. Validation is by
     * construction (encode) and re-checked on decode (a wire frame that fails it is an
     * {@link IOException}, the RAPP decode idiom).
     */
    public record MountHandover(long seatWireId, long moduleOldWireId, int kind,
            String slotToken) {
        public MountHandover {
            Objects.requireNonNull(slotToken, "slotToken");
            if (kind != KIND_MOUNT && kind != KIND_DISMOUNT) {
                throw new IllegalArgumentException("kind must be 1 (mount) or 2 (dismount), got "
                        + kind);
            }
            if (seatWireId < 1L) {
                throw new IllegalArgumentException("seatWireId must be positive: " + seatWireId);
            }
            if (moduleOldWireId < 0L) {
                throw new IllegalArgumentException(
                        "moduleOldWireId must be non-negative: " + moduleOldWireId);
            }
            if (kind == KIND_DISMOUNT && moduleOldWireId == 0L) {
                throw new IllegalArgumentException(
                        "dismount requires the module's fresh above-band id (got 0)");
            }
            if (slotToken.isBlank() || slotToken.length() > MAX_TOKEN_CHARS) {
                throw new IllegalArgumentException("slotToken must be non-blank and at most "
                        + MAX_TOKEN_CHARS + " chars");
            }
            for (int i = 0; i < slotToken.length(); i++) {
                char c = slotToken.charAt(i);
                if (c < 0x20 || c > 0x7E) {
                    throw new IllegalArgumentException(
                            "slotToken must be single-line printable ASCII");
                }
            }
        }
    }

    private ModuleMountFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    /** Encode one MMNT frame to its full wire bytes. */
    public static byte[] encode(MountHandover handover) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, handover);
        return out.toByteArray();
    }

    /** Write one MMNT control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, MountHandover handover) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(handover, "handover");
        byte[] body = encodeBody(handover);
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("module-mount frame body " + body.length
                    + " B exceeds the wire budget (" + MAX_BODY_BYTES + " B body cap)");
        }
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
     * Read one MMNT control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed off
     * {@code data} and validated by the caller (the demux peeks the magic to route the frame).
     * Validates schema, header size, wire identity, declared body size, CRC32, and the handover
     * law; throws {@link IOException} on any mismatch.
     */
    public static MountHandover readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("module-mount frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("module-mount frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes < 1 || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("module-mount frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("module-mount frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static byte[] encodeBody(MountHandover handover) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeLong(handover.seatWireId());
        data.writeLong(handover.moduleOldWireId());
        data.writeByte(handover.kind());
        data.writeUTF(handover.slotToken());
        data.flush();
        return bytes.toByteArray();
    }

    private static MountHandover decodeBody(byte[] body) throws IOException {
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(body));
        long seatWireId = data.readLong();
        long moduleOldWireId = data.readLong();
        int kind = data.readUnsignedByte();
        String slotToken = data.readUTF();
        MountHandover handover;
        try {
            handover = new MountHandover(seatWireId, moduleOldWireId, kind, slotToken);
        } catch (IllegalArgumentException badFrame) {
            throw new IOException("module-mount frame invalid", badFrame);
        }
        if (data.read() != -1) {
            throw new IOException("module-mount frame body has trailing bytes");
        }
        return handover;
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
                    .require(ModuleMountControlMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported module-mount frame schema: "
                    + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("module-mount frame schema rejected by compatibility policy: "
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
