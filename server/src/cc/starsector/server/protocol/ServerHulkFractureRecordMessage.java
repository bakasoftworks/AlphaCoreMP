package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message carrying ONE hulk-fracture edge — the
 * {@code HKFR} record the server fans when its authoritative engine splits (or
 * explode-removes a piece of) a corpse, so clients stop DECIDING corpse fracture locally
 * and mirror the server's edges with engine-own verbs ({@code splitShip()} /
 * {@code removeEntity}). Cross-JVM split determinism is a measured dead end (method-local
 * RNGs); server-authoritative fracture EVENTS are the chosen design.
 *
 * <p>Fixed 41-byte frame (schema v4): simTick (i64) + rootEntityId (i64, the corpse
 * family's wire id, pinned at family creation — piece hulls inherit the parent's engine id) +
 * deathTick (i64, the family's wire identity: the detector's first-sight tick of the corpse,
 * within ~1-2 ticks of its EDSP death tick — the client binds families by NEAREST death tick,
 * so a late joiner that missed earlier deaths desyncs nothing; v1's familySeq death ordinal
 * required observing every death since tick 0 and is deleted. One wire id can still front two
 * coexisting corpse families across a T4 remount) + edgeKind (u8: 1=PIECE_ADDED,
 * 2=PIECE_REMOVED, 3=PIECE_REMOVED_DESTROYED — the engine vaporized the piece;
 * 4=ROOT_DETACHED — the family root observed module-detached (schema v4);
 * membership untouched, the client un-slots the bound wreck) + sizeRank (u8,
 * removals only — largest piece = 0 by bbox area, the engine's own H cut-largest metric;
 * 0 on adds and detaches) + familyPieceCount (u8, post-edge assert). Fanned in the {@code fanSeatName}
 * shape (encode once, ctrl-spool per live session); NO admission catch-up — a newcomer's
 * world contains no corpses at all (the pose lane never carried them), so fracture
 * catch-up would target entities the newcomer lacks. Wire codec:
 * {@code HulkFractureFrameCodec}..
 */
public final class ServerHulkFractureRecordMessage {

    public static final String MESSAGE_ID = "server.hulk_fracture_record";
    public static final int SCHEMA_VERSION = 4;
    public static final String CODEC_OWNER = "server.protocol.hulk_fracture_record.codec";
    public static final String VALIDATION_OWNER = "server.protocol.hulk_fracture_record.validation";
    public static final String RELEVANCE_RULE_ID = "hulk_fracture_record.session_fracture_fan";
    public static final String COMPATIBILITY_PREDICATE_ID = "hulk_fracture_record_schema_v4";
    public static final String FAILURE_CODE = "HULK_FRACTURE_RECORD_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerHulkFractureRecordMessage() {
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
