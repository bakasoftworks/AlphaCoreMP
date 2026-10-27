package cc.starsector.server.protocol;

/**
 * Registered server control message announcing the accepted transport session
 * before authoritative snapshot bytes begin.
 */
public final class ServerTransportSessionOpenedMessage {

    public static final String MESSAGE_ID = "server.transport_session.opened";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.transport_session_opened.codec";
    public static final String VALIDATION_OWNER = "server.protocol.transport_session_opened.validation";
    public static final String RELEVANCE_RULE_ID = "transport_session.requesting_client_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "transport_session_opened_schema_v1";
    public static final String FAILURE_CODE = "TRANSPORT_SESSION_OPENED_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.STATIC_SETUP;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerTransportSessionOpenedMessage() {
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
