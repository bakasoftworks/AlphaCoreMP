package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message carrying ONE corpse-pose keyframe — the
 * {@code HKPS} record (F4): a low-rate server-authoritative pose snapshot of one
 * corpse-family piece (hulk or split piece), fanned so clients ADOPT the server's corpse
 * kinematics instead of coasting off their own death-instant estimate. Corpses live OUTSIDE
 * the snapshot tiers by design (four walls: split pieces hold no wire ids, AWSD refuses
 * dead ships, EDSP death IS snapshot-absence, the datagram budget) — so pose rides the reliable
 * CTRL fan as the {@code HKFR} twin. Hulk drift is deterministic ballistic coast (no drag, no
 * death impulse), so ONE exact adoption per keyframe holds between keyframes; the cadence
 * knob is {@code cc.server.hulkPoseEveryTicks} (0 = OFF).
 *
 * <p>Fixed 64-byte frame (schema v1): simTick (i64) + rootEntityId (i64, the corpse
 * family's wire id — pieces inherit the parent hull's engine id, pinned at family creation) +
 * deathTick (i64, the family's wire identity — the detector's first-sight tick; the client
 * binds by NEAREST death tick, the HKFR law) + sizeRank (u8, this piece's bbox-area rank,
 * largest = 0 — the HKFR REMOVE idiom) + pieceCount (u8, the family size this keyframe set
 * describes; the client's count assert) + locX/locY/facing/velX/velY/angVel (f32 ×6, the
 * exact-adoption pose). Keyframes for ALL pieces of a family are emitted in rank order on the
 * SAME ctrl spool as (and after) any same-tick {@code HKFR} edges, so rank interpretation is
 * race-free. NO admission catch-up — a newcomer's world contains no corpses (the HKFR
 * law); the periodic cadence self-heals late binds instead. Wire codec:
 * {@code HulkPoseFrameCodec}..
 */
public final class ServerHulkPoseRecordMessage {

    public static final String MESSAGE_ID = "server.hulk_pose_record";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.hulk_pose_record.codec";
    public static final String VALIDATION_OWNER = "server.protocol.hulk_pose_record.validation";
    public static final String RELEVANCE_RULE_ID = "hulk_pose_record.session_pose_fan";
    public static final String COMPATIBILITY_PREDICATE_ID = "hulk_pose_record_schema_v1";
    public static final String FAILURE_CODE = "HULK_POSE_RECORD_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerHulkPoseRecordMessage() {
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
