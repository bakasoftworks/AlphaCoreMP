package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message carrying the batched asteroid-field pose rows -
 * the {@code ASTP} lane. The server-side keeper
 * ({@code AsteroidFieldServeLane}) seeds, sweeps, re-targets and polls the round's asteroid bodies;
 * each tick with a change (a fresh mint, a bump impulse, a cadence-capped hp change, a death or a
 * keeper expiry, a join-sweep or refresh row) it fans ONE or more shared frames of
 * {@code asteroidId -> pose} rows to EVERY live session on the reliable CTRL spool (selection-not-minting:
 * a joiner converges from the same shared stream).
 *
 * <p>DOWN-only by construction: the server never reads an ASTP off the wire - zero new input
 * surface. Wire codec: {@code AsteroidPoseFrameCodec}
 * (fixed-width {@code ASTP} frame, no UTF). Registry twin of {@link PilotAimControlMessage}.
 *
 */
public final class AsteroidPoseControlMessage {

    public static final String MESSAGE_ID = "server.asteroid.pose";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.asteroid_pose.codec";
    public static final String VALIDATION_OWNER = "server.protocol.asteroid_pose.validation";
    public static final String RELEVANCE_RULE_ID = "asteroid_pose.fan_all_sessions";
    public static final String COMPATIBILITY_PREDICATE_ID = "asteroid_pose_schema_v1";
    public static final String FAILURE_CODE = "ASTEROID_POSE_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private AsteroidPoseControlMessage() {
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
