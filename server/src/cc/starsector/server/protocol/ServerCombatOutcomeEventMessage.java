package cc.starsector.server.protocol;

/**
 * Registered server-&gt;client message announcing one authoritative combat OUTCOME
 * ({@link CombatOutcomeEvent}): the match is decided and which side won.
 *
 * <p>Like {@link ServerEntityDespawnEventMessage} this is a derived gameplay
 * <em>event</em>, not a per-tick replaceable snapshot, a client command/result, or a
 * session/admission control frame — so it rides the durable gameplay-event lane and
 * is not dropped the way a replaceable snapshot frame is. But where the despawn event
 * is per-entity, this is the once-per-match terminal signal: a dedicated combat server
 * pushes it to end the session and declare the winner.
 *
 * <p>Why it exists: before this message nothing on the wire told a client the match
 * was over and who won. The snapshot tier ({@link WorldSnapshotMessage}) carries
 * evolving per-ship state, {@link ServerEntityDespawnEventMessage} carries individual
 * kills, and {@link SnapshotStreamCompletedMessage} only reports that the byte stream
 * ended (a transport fact, not a match result). This message closes that gap. Its
 * producer is {@code CombatOutcomeEventDetector} (server-side), fed by the roster-derived
 * {@code EngineCombatBinding.combatOutcomeFromRosterOrNull()}: the
 * authoritative outcome comes from the roster, NOT from the engine's {@code getWinningSideId()}
 * read, which proved unreliable headless and which is retained only as a
 * diagnostic/parity signal. Its wire framing is {@code CombatOutcomeEventCodec}.
 */
public final class ServerCombatOutcomeEventMessage {

    public static final String MESSAGE_ID = "server.combat_outcome_event";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.combat_outcome_event.codec";
    public static final String VALIDATION_OWNER = "server.protocol.combat_outcome_event.validation";
    public static final String RELEVANCE_RULE_ID = "combat_outcome_event.match_decided";
    public static final String COMPATIBILITY_PREDICATE_ID = "combat_outcome_event_schema_v1";
    public static final String FAILURE_CODE = "COMBAT_OUTCOME_EVENT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerCombatOutcomeEventMessage() {
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
