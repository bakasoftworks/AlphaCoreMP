package cc.starsector.server.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Compact registry capability export advertised by a socket client.
 */
public record NetworkCapabilityManifest(
        int manifestVersion,
        int entryCount,
        int streamCount,
        long fingerprintCrc32) {

    public static final int CURRENT_VERSION = 1;

    public NetworkCapabilityManifest {
        if (manifestVersion <= 0) {
            throw new IllegalArgumentException("manifestVersion must be > 0; got " + manifestVersion);
        }
        if (entryCount <= 0) {
            throw new IllegalArgumentException("entryCount must be > 0; got " + entryCount);
        }
        if (streamCount < 0) {
            throw new IllegalArgumentException("streamCount must be >= 0; got " + streamCount);
        }
        if (fingerprintCrc32 < 0L || fingerprintCrc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("fingerprintCrc32 must be an unsigned 32-bit value");
        }
    }

    public static NetworkCapabilityManifest fromEntries(List<ProtocolRegistryEntry> entries) {
        return fromEntries(entries, List.of());
    }

    public static NetworkCapabilityManifest fromEntries(
            List<ProtocolRegistryEntry> entries,
            List<SnapshotStreamIdentity> streams) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(streams, "streams");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("network capability manifest must contain at least one entry");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(CURRENT_VERSION);
            data.writeInt(entries.size());
            data.writeInt(streams.size());
            for (ProtocolRegistryEntry entry : entries) {
                writeEntry(data, entry);
            }
            for (SnapshotStreamIdentity stream : streams) {
                writeStream(data, stream);
            }
        } catch (IOException ioe) {
            throw new IllegalStateException("failed to build network capability manifest", ioe);
        }
        CRC32 crc = new CRC32();
        byte[] fingerprintInput = bytes.toByteArray();
        crc.update(fingerprintInput, 0, fingerprintInput.length);
        return new NetworkCapabilityManifest(CURRENT_VERSION, entries.size(), streams.size(), crc.getValue());
    }

    private static void writeEntry(DataOutputStream data, ProtocolRegistryEntry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        writeString(data, entry.messageId());
        data.writeInt(entry.schemaVersion());
        data.writeInt(entry.wireTypeCode());
        writeString(data, entry.direction().name());
        writeString(data, entry.reliabilityLane().name());
        writeString(data, entry.dataClass().name());
        writeString(data, entry.codecValidationOwner().codecOwner());
        writeString(data, entry.codecValidationOwner().validationOwner());
        writeString(data, entry.relevanceRule().ruleId());
        data.writeBoolean(entry.relevanceRule().ownerScoped());
        data.writeBoolean(entry.relevanceRule().visibilityScoped());
        writeString(data, entry.compatibilityRule().predicateId());
        data.writeInt(entry.compatibilityRule().minimumSchemaVersion());
        data.writeInt(entry.compatibilityRule().maximumSchemaVersion());
        data.writeBoolean(entry.compatibilityRule().requiresContentCompatibility());
        writeString(data, entry.failurePolicy().failureCode());
        data.writeBoolean(entry.failurePolicy().failClosed());
        data.writeLong(entry.snapshotCadence().everyCompletedSimulationTicks());
    }

    private static void writeStream(DataOutputStream data, SnapshotStreamIdentity stream) throws IOException {
        Objects.requireNonNull(stream, "stream");
        writeString(data, stream.streamId());
        data.writeInt(stream.streamVersion());
        data.writeInt(stream.streamTypeCode());
        data.writeInt(stream.requiredShipCount());
        data.writeInt(stream.requiredFrames());
        data.writeBoolean(stream.requiresStateChanges());
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("manifest string values must be non-blank");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }
}
