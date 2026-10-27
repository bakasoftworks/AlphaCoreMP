package cc.starsector.server.protocol;

/**
 * Registered server control message carrying the first authoritative baseline a
 * snapshot client must accept before consuming streamed combat frames.
 */
public final class ServerInitialSyncMessage {

    public static final String MESSAGE_ID = "server.initial_sync.baseline";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.initial_sync.codec";
    public static final String VALIDATION_OWNER = "server.protocol.initial_sync.validation";
    public static final String RELEVANCE_RULE_ID = "initial_sync.requesting_client_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "initial_sync_schema_v1";
    public static final String FAILURE_CODE = "INITIAL_SYNC_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.STATIC_SETUP;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerInitialSyncMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_OWNER_ONLY_UPDATE,
                ReliabilityLane.CONTROL_SETUP,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
