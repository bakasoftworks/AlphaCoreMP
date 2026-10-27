package cc.starsector.server.protocol;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Product-owned protocol registry entry skeleton.
 *
 * <p>The registry is metadata only: it declares the message id, schema version,
 * direction, reliability lane, policy owners, relevance, compatibility, failure
 * behavior, and snapshot cadence without adding sockets or a broad serialization
 * layer.
 */
public record ProtocolRegistryEntry(
        String messageId,
        int schemaVersion,
        MessageDirection direction,
        ReliabilityLane reliabilityLane,
        ProtocolDataClass dataClass,
        ProtocolCodecValidationOwner codecValidationOwner,
        ProtocolRelevanceRule relevanceRule,
        ProtocolCompatibilityRule compatibilityRule,
        ProtocolFailurePolicy failurePolicy,
        SnapshotPublicationCadence snapshotCadence) {

    public ProtocolRegistryEntry {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must be non-blank");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(reliabilityLane, "reliabilityLane");
        Objects.requireNonNull(dataClass, "dataClass");
        Objects.requireNonNull(codecValidationOwner, "codecValidationOwner");
        Objects.requireNonNull(relevanceRule, "relevanceRule");
        Objects.requireNonNull(compatibilityRule, "compatibilityRule");
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        Objects.requireNonNull(snapshotCadence, "snapshotCadence");
    }

    /** Stable 32-bit wire identity derived from the registry message id. */
    public int wireTypeCode() {
        return wireTypeCode(messageId);
    }

    public static int wireTypeCode(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must be non-blank");
        }
        CRC32 crc = new CRC32();
        byte[] bytes = messageId.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }
}
