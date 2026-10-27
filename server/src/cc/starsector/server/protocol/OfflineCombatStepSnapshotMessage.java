package cc.starsector.server.protocol;

import cc.starsector.server.runtime.OfflineCombatStepResult;
import java.util.Objects;

/**
 * Versioned product-owned envelope for a published offline combat step result.
 */
public record OfflineCombatStepSnapshotMessage(
        ProtocolRegistryEntry registryEntry,
        OfflineCombatStepResult result) {

    public static final String MESSAGE_ID = "server.offline_combat.step_snapshot";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.offline_combat_step_snapshot.codec";
    public static final String VALIDATION_OWNER = "server.protocol.offline_combat_step_snapshot.validation";
    public static final String RELEVANCE_RULE_ID = "combat_snapshot.visibility_scoped_subscribers";
    public static final String COMPATIBILITY_PREDICATE_ID = "schema_v1_content_compatible";
    public static final String FAILURE_CODE = "OFFLINE_COMBAT_STEP_SNAPSHOT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.REPLACEABLE_SNAPSHOT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, true);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    public OfflineCombatStepSnapshotMessage {
        Objects.requireNonNull(registryEntry, "registryEntry");
        Objects.requireNonNull(result, "result");
        if (!MESSAGE_ID.equals(registryEntry.messageId())) {
            throw new IllegalArgumentException("unexpected messageId: " + registryEntry.messageId());
        }
        if (registryEntry.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unexpected schemaVersion: " + registryEntry.schemaVersion());
        }
        if (registryEntry.direction() != MessageDirection.SERVER_UPDATE) {
            throw new IllegalArgumentException("offline step snapshots must be server updates");
        }
        if (registryEntry.reliabilityLane() != ReliabilityLane.REPLACEABLE_SNAPSHOT) {
            throw new IllegalArgumentException("offline step snapshots must use replaceable snapshot reliability");
        }
        if (registryEntry.dataClass() != DATA_CLASS) {
            throw new IllegalArgumentException("offline step snapshots must be replaceable snapshot data");
        }
        if (!CODEC_VALIDATION_OWNER.equals(registryEntry.codecValidationOwner())) {
            throw new IllegalArgumentException("unexpected codec/validation owner");
        }
        if (!RELEVANCE_RULE.equals(registryEntry.relevanceRule())) {
            throw new IllegalArgumentException("unexpected relevance rule");
        }
        if (!COMPATIBILITY_RULE.equals(registryEntry.compatibilityRule())) {
            throw new IllegalArgumentException("unexpected compatibility rule");
        }
        if (!FAILURE_POLICY.equals(registryEntry.failurePolicy())) {
            throw new IllegalArgumentException("unexpected failure policy");
        }
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }

    public static ProtocolCodecValidationOwner codecValidationOwner() {
        return CODEC_VALIDATION_OWNER;
    }

    public static ProtocolRelevanceRule relevanceRule() {
        return RELEVANCE_RULE;
    }

    public static ProtocolCompatibilityRule compatibilityRule() {
        return COMPATIBILITY_RULE;
    }

    public static ProtocolFailurePolicy failurePolicy() {
        return FAILURE_POLICY;
    }

    public static OfflineCombatStepSnapshotMessage wrap(
            OfflineCombatStepResult result,
            SnapshotPublicationCadence snapshotCadence) {
        return new OfflineCombatStepSnapshotMessage(registryEntry(snapshotCadence), result);
    }
}
