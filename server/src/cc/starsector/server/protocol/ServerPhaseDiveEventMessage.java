package cc.starsector.server.protocol;

/**
 * Registered server-&gt;client message announcing one authoritative phase-dive
 * start ({@link PhaseDiveEvent}).
 *
 *
 * <p>Same message kind as {@link ServerEntityDespawnEventMessage}: a derived
 * gameplay event on the durable gameplay-event lane, pushed once when a
 * PhaseAnchor hull's emergency dive begins. The client presentation lane
 * ({@code cc.predict.phaseDive}) latches it to play the dive locally (cloak-in,
 * fade, vanish) and to route the entity's later {@code DEPARTED} despawn to a
 * corpse-free removal instead of a death presentation. Wire framing is
 * {@code PhaseDiveEventCodec}; the producer is the serve loop's
 * {@code PhaseDiveServeProbe} rising-edge read of the engine's own
 * {@code PhaseAnchorScript.emergencyDive}.
 */
public final class ServerPhaseDiveEventMessage {

    public static final String MESSAGE_ID = "server.phase_dive_event";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.phase_dive_event.codec";
    public static final String VALIDATION_OWNER = "server.protocol.phase_dive_event.validation";
    public static final String RELEVANCE_RULE_ID = "phase_dive_event.dive_start";
    public static final String COMPATIBILITY_PREDICATE_ID = "phase_dive_event_schema_v1";
    public static final String FAILURE_CODE = "PHASE_DIVE_EVENT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerPhaseDiveEventMessage() {
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
