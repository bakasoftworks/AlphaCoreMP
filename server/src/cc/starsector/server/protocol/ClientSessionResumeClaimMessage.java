package cc.starsector.server.protocol;

/**
 * Registered client control message that presents a previously issued
 * session/window cursor on a reconnecting authoritative session.
 */
public final class ClientSessionResumeClaimMessage {

    public static final String MESSAGE_ID = "client.session_resume.claim";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.client.session_resume_claim.codec";
    public static final String VALIDATION_OWNER = "server.client.session_resume_claim.validation";
    public static final String RELEVANCE_RULE_ID = "session_resume_claim.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "session_resume_claim_schema_v1";
    public static final String FAILURE_CODE = "SESSION_RESUME_CLAIM_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.AUTHORITY_INPUT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientSessionResumeClaimMessage() {
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
