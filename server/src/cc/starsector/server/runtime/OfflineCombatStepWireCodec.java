package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.OfflineCombatStepSnapshotMessage;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for {@link OfflineCombatStepResult}.
 *
 * <p>This is deliberately small and fixed-width: it gives the live rung-9 scout
 * a real byte artifact with magic/version framing and per-frame CRC32 readback
 * without reopening the frozen transport-neutral protocol facade chain.
 */
public final class OfflineCombatStepWireCodec {

    public static final String MESSAGE_ID = OfflineCombatStepSnapshotMessage.MESSAGE_ID;
    public static final int MAGIC = 0x4f435352; // "OCSR"
    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotWireEntry();
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final short HEADER_BYTES =
            Integer.BYTES + Short.BYTES + Short.BYTES + Integer.BYTES + Integer.BYTES;
    public static final int PAYLOAD_BYTES =
            Long.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Long.BYTES;
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES;

    private OfflineCombatStepWireCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    public static OfflineCombatStepWireFrame writeFrame(
            OutputStream output,
            long sequence,
            OfflineCombatStepResult result) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(result, "result");
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be >= 0; got " + sequence);
        }

        byte[] payload = encodePayload(sequence, result);
        long checksum = checksum(payload);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(checkedSchemaVersion(registryEntry()));
        data.writeShort(HEADER_BYTES);
        data.writeInt(registryEntry().wireTypeCode());
        data.writeInt(payload.length);
        data.write(payload);
        data.writeInt((int) checksum);
        data.flush();
        return new OfflineCombatStepWireFrame(sequence, result, payload.length, checksum);
    }

    public static List<OfflineCombatStepWireFrame> readFrames(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return readFrames(input);
        }
    }

    public static List<OfflineCombatStepWireFrame> readFrames(InputStream input) throws IOException {
        List<OfflineCombatStepWireFrame> frames = new ArrayList<>();
        readFrames(input, frames::add);
        return List.copyOf(frames);
    }

    public static void readFrames(
            InputStream input,
            Consumer<? super OfflineCombatStepWireFrame> decodedFrameConsumer) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(decodedFrameConsumer, "decodedFrameConsumer");
        DataInputStream data = new DataInputStream(input);
        while (true) {
            int first = data.read();
            if (first < 0) {
                return;
            }

            int magic = readIntAfterFirstByte(data, first);
            if (magic != MAGIC) {
                throw new IOException("offline step frame magic mismatch: " + Integer.toHexString(magic));
            }

            short version = data.readShort();
            requireRegisteredSchema(version);
            short headerBytes = data.readShort();
            if (headerBytes != HEADER_BYTES) {
                throw new IOException("offline step frame header size mismatch: " + headerBytes);
            }
            int messageTypeCode = data.readInt();
            requireRegisteredWireIdentity(messageTypeCode, version);
            int payloadBytes = data.readInt();
            if (payloadBytes != PAYLOAD_BYTES) {
                throw new IOException("offline step frame payload size mismatch: " + payloadBytes);
            }

            byte[] payload = new byte[payloadBytes];
            data.readFully(payload);
            long expectedChecksum = Integer.toUnsignedLong(data.readInt());
            long actualChecksum = checksum(payload);
            if (expectedChecksum != actualChecksum) {
                throw new IOException("offline step frame checksum mismatch");
            }
            decodedFrameConsumer.accept(decodePayload(payload, actualChecksum));
        }
    }

    private static byte[] encodePayload(long sequence, OfflineCombatStepResult result)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(PAYLOAD_BYTES);
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeLong(sequence);
        data.writeLong(result.startedTick());
        data.writeLong(result.completedTick());
        data.writeInt(Float.floatToIntBits(result.fixedStepAmount()));
        data.writeInt(Float.floatToIntBits(result.effectiveStepAmount()));
        data.writeInt(result.entityCount());
        data.writeInt(result.shipCount());
        data.writeInt(result.shipNetworkIdCount());
        data.writeLong(result.firstShipNetworkId());
        data.writeLong(result.lastShipNetworkId());
        data.writeLong(result.stateHash());
        data.writeLong(result.stepStartedAtNanos());
        data.writeLong(result.stepCompletedAtNanos());
        data.writeLong(result.snapshotCompletedAtNanos());
        data.flush();
        byte[] payload = bytes.toByteArray();
        if (payload.length != PAYLOAD_BYTES) {
            throw new IOException("offline step payload size bug: " + payload.length);
        }
        return payload;
    }

    private static OfflineCombatStepWireFrame decodePayload(byte[] payload, long checksum)
            throws IOException {
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(payload));
        long sequence = data.readLong();
        OfflineCombatStepResult result = new OfflineCombatStepResult(
                data.readLong(),
                data.readLong(),
                Float.intBitsToFloat(data.readInt()),
                Float.intBitsToFloat(data.readInt()),
                data.readInt(),
                data.readInt(),
                data.readInt(),
                data.readLong(),
                data.readLong(),
                data.readLong(),
                data.readLong(),
                data.readLong(),
                data.readLong());
        if (data.read() != -1) {
            throw new IOException("offline step payload has trailing bytes");
        }
        return new OfflineCombatStepWireFrame(sequence, result, payload.length, checksum);
    }

    private static long checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        return crc.getValue();
    }

    private static void requireRegisteredSchema(short schemaVersion) throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().require(MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported offline step frame schema: " + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("offline step frame schema rejected by compatibility policy: "
                    + unsignedSchemaVersion);
        }
    }

    private static void requireRegisteredWireIdentity(int messageTypeCode, short schemaVersion)
            throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    messageTypeCode,
                    unsignedSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "unsupported offline step frame message identity: "
                            + Integer.toUnsignedString(messageTypeCode)
                            + " v" + unsignedSchemaVersion,
                    badIdentity);
        }
        if (!MESSAGE_ID.equals(entry.messageId())) {
            throw new IOException("offline step frame message id mismatch: " + entry.messageId());
        }
    }

    private static short checkedSchemaVersion(ProtocolRegistryEntry registryEntry) {
        int schemaVersion = registryEntry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("wire schema version exceeds signed short range: " + schemaVersion);
        }
        return (short) schemaVersion;
    }

    private static int readIntAfterFirstByte(DataInputStream input, int first) throws IOException {
        try {
            return ((first & 0xff) << 24)
                    | (input.readUnsignedByte() << 16)
                    | (input.readUnsignedByte() << 8)
                    | input.readUnsignedByte();
        } catch (EOFException eof) {
            throw new EOFException("truncated offline step frame header");
        }
    }
}
