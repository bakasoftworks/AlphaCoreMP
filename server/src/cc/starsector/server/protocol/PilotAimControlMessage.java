package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message carrying the batched per-piloted-seat aim
 * points — the {@code AIMP} lane.
 * Each due tick (~8 Hz change-gated, {@code cc.server.aimFanEveryTicks}) the serve loop fans ONE
 * shared frame of {@code shipWireId → quantized aim world point} rows to EVERY live session
 * (selection-not-minting: the client drops its OWN row — never a per-peer mint).
 * Clients feed each row into the replica's {@code mouseTarget} so the engine's own manual-group
 * chain slews turrets at vanilla rates; discharge snap (aim_rebase) stays the shot authority —
 * this lane is presentation-only rest facing between shots (the one-owner law).
 *
 * <p>DOWN-only by construction: the server never reads an AIMP off the wire — zero new input
 * surface. Wire codec: {@code PilotAimFrameCodec}
 * (fixed-width {@code AIMP} frame, no UTF).
 */
public final class PilotAimControlMessage {

    public static final String MESSAGE_ID = "server.pilot.aim";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.pilot_aim.codec";
    public static final String VALIDATION_OWNER = "server.protocol.pilot_aim.validation";
    public static final String RELEVANCE_RULE_ID = "pilot_aim.fan_all_sessions";
    public static final String COMPATIBILITY_PREDICATE_ID = "pilot_aim_schema_v1";
    public static final String FAILURE_CODE = "PILOT_AIM_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private PilotAimControlMessage() {
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
