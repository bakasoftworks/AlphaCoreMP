package cc.starsector.server.protocol;

/**
 * Registered server-&gt;prober message carrying ONE chunk of the server's current redacted match document:
 * the {@code DESC} schema-2 reply a password-proven join probe receives (codec
 * {@code MatchDocFrameCodec}), so a player's join window fetches the match itself instead of the player
 * pasting it. WHY a registry entry of its own: it is the reserved {@code DESC} magic's FIRST consumer, and
 * it is a PRE-SESSION datagram (the probe reply, unicast to the one source that proved the password), not a
 * CTRL stream message - so it takes schema 2 under its own message id, and
 * {@link MatchDescriptorControlMessage} (schema 1, the in-session descriptor-on-CTRL reservation) stays
 * dormant and keeps its frozen golden.
 *
 * <p>Each chunk is sealed alone under a per-reply key (the codec's javadoc holds the frame layout and the
 * sealing law); a chunk that fails its tag is dropped and counted by the receiver, never a refusal.
 *
 *
 */
public final class MatchDocumentMessage {

    public static final String MESSAGE_ID = "server.match_document";
    public static final int SCHEMA_VERSION = 2;
    public static final String CODEC_OWNER = "server.protocol.match_document.codec";
    public static final String VALIDATION_OWNER = "server.protocol.match_document.validation";
    public static final String RELEVANCE_RULE_ID = "match_document.unicast_proven_probe";
    public static final String COMPATIBILITY_PREDICATE_ID = "match_document_schema_v2";
    public static final String FAILURE_CODE = "MATCH_DOCUMENT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private MatchDocumentMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.DURABLE_GAMEPLAY_EVENT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
