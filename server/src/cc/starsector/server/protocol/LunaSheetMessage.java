package cc.starsector.server.protocol;

/**
 * Registered server-&gt;prober message carrying ONE chunk of the server's LunaSettings sheet: the {@code LUNA}
 * schema-2 reply a password-proven join probe receives beside the match document (codec
 * {@code MatchDocFrameCodec} under its LUNA kind, sheet law {@code LunaSheetCodec}), so a joiner's kit runs the
 * host's effective mod settings. WHY a registry entry of its own: its wire type is what the LUNA envelope carries,
 * and a wire type distinct from {@link MatchDocumentMessage}'s is half of what keeps a sheet chunk from being
 * judged as a match-document chunk (the other half is the sheet's own HKDF label). A PRE-SESSION datagram
 * (the probe reply, unicast to the one source that proved the password), never a CTRL stream message.
 *
 * <p>Each chunk is sealed alone under a per-reply key (the codec's javadoc holds the frame layout and the
 * sealing law); a chunk that fails its tag is dropped and counted by the receiver, never a refusal.
 *
 *
 */
public final class LunaSheetMessage {

    public static final String MESSAGE_ID = "server.luna_sheet";
    public static final int SCHEMA_VERSION = 2;
    public static final String CODEC_OWNER = "server.protocol.luna_sheet.codec";
    public static final String VALIDATION_OWNER = "server.protocol.luna_sheet.validation";
    public static final String RELEVANCE_RULE_ID = "luna_sheet.unicast_proven_probe";
    public static final String COMPATIBILITY_PREDICATE_ID = "luna_sheet_schema_v2";
    public static final String FAILURE_CODE = "LUNA_SHEET_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private LunaSheetMessage() {
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
