package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message carrying ONE hulk-cut-geometry record — the
 * {@code HKCG} record the server fans when its authoritative engine CARVES a
 * corpse, so clients stop rolling their own method-local tesselation RNG for the piece shapes
 * and instead feed the engine's own public two-arg {@code splitShip(oo, oo)} with the server's
 * measured result rings (the carve is RNG-free downstream of the tesselation result; a compact
 * param-carry was rejected as a float-exact reimplementation).
 *
 * <p>Four records per cut ({@code pieceSlot} u8 {0=parent,1=clone} × {@code ringKind} u8
 * {0=exact,1=visual}), latched by (rootEntityId, deathTick, cutSeq) — the exact HKFR/HKPS
 * family identity plus the per-family ADD ordinal (the frozen (rootId, cutSeq) key is
 * ambiguous across T4-remount coexisting same-rootId families). Emitted BEFORE the paired
 * {@code HKFR} PIECE_ADDED edge on the same strict per-session FIFO ctrl spool (drain-loop
 * order alone), so the client latch is always warm at edge arrival; ANY capture or apply
 * failure degrades to today's local no-arg cut (fail-soft, never blocks the edge). Rings ride
 * as OPEN i16-pair points quantized 1/64 u in the parent's PRE-SPLIT local frame; exact
 * records append the shared cut-run indices. NO admission catch-up — a newcomer's world
 * contains no corpses at all. Wire codec: {@code HulkCutGeometryFrameCodec}
 * (bounded-max length-prefixed, the recorded deviation)..
 */
public final class ServerHulkCutGeometryRecordMessage {

    public static final String MESSAGE_ID = "server.hulk_cut_geometry_record";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.hulk_cut_geometry_record.codec";
    public static final String VALIDATION_OWNER = "server.protocol.hulk_cut_geometry_record.validation";
    public static final String RELEVANCE_RULE_ID = "hulk_cut_geometry_record.session_fracture_fan";
    public static final String COMPATIBILITY_PREDICATE_ID = "hulk_cut_geometry_record_schema_v1";
    public static final String FAILURE_CODE = "HULK_CUT_GEOMETRY_RECORD_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerHulkCutGeometryRecordMessage() {
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
