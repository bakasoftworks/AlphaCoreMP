package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message carrying the serve-end match verdict — the
 * {@code MOVR} record fanned at most ONCE per serve, BEFORE the goodbye BYE
 * (the CTRL spool is per-session FIFO and the goodbye only BYEs after spool-drain, so the
 * verdict rides ahead of the teardown, bounded best-effort). Clients end the match on the
 * SERVER's latched outcome (the ELWSS first-latch-wins carry) instead of
 * their local engine's auto-end guesser, which misfires on the fortress/wave shape.
 *
 * <p>Fixed 24-byte frame: {@code simTick} i64 + {@code outcome} u8 (1 fortress-cleared /
 * 2 base-hulk / 3 per-side / 4 roster-0 / 5 wave-clear / 6 operator-end) + {@code winnerSide}
 * u8 (engine FleetSide ordinal 0/1; 0xFF = no victor). The ELWSS tag STRING stays off the
 * wire — the code map is the codec's. A frame-budget serve end fans NOTHING (no outcome
 * exists — teardown as today). Wire codec: {@code MatchOverFrameCodec}..
 *
 */
public final class ServerMatchOverRecordMessage {

    public static final String MESSAGE_ID = "server.match_over_record";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.match_over_record.codec";
    public static final String VALIDATION_OWNER = "server.protocol.match_over_record.validation";
    public static final String RELEVANCE_RULE_ID = "match_over_record.session_verdict_fan";
    public static final String COMPATIBILITY_PREDICATE_ID = "match_over_record_schema_v1";
    public static final String FAILURE_CODE = "MATCH_OVER_RECORD_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerMatchOverRecordMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_BROADCAST,
                ReliabilityLane.DURABLE_GAMEPLAY_EVENT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
