package cc.starsector.server.protocol;

/**
 * Registered client control message for requesting a headless snapshot stream.
 */
public final class SnapshotAdmissionRequestMessage {

    public static final String MESSAGE_ID = "client.snapshot_admission.requested";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.client.snapshot_admission_request.codec";
    public static final String VALIDATION_OWNER = "server.client.snapshot_admission_request.validation";
    public static final String RELEVANCE_RULE_ID = "snapshot_admission.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "snapshot_admission_request_schema_v1";
    public static final String FAILURE_CODE = "SNAPSHOT_ADMISSION_REQUEST_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.STATIC_SETUP;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private SnapshotAdmissionRequestMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.CLIENT_REQUEST,
                ReliabilityLane.CONTROL_SETUP,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
