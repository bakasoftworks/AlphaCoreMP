package cc.starsector.server.protocol;

/**
 * Registered server message delivering the lane-posture WIRE BLOCK — the down half of the
 * client-posture detection pair ({@code PSTB}).
 * Spooled ONCE per admission on
 * the post-admit CTRL lane (the {@code VariantCatalogControlMessage} spool precedent — NOT a
 * WELCOME tail, which is strict fail-closed and re-versions per growth), idempotent client apply.
 *
 * <p>Schema 2 carries the {@code WireKnob} census in a fixed 8-slot
 * {@code (id, value)} buffer — the first client-behavior knob that varies publicly (the
 * foe-cloak lead/wait A/B, flipped mid-match by the {@code /knob} admin verb, which RE-SPOOLS the
 * block to every live session; the per-admission spool stays). Schema 1 was knob-EMPTY by
 * design (the critique: a knob joins the block the first time a lane needs it to vary).
 * The optional experiment/diagnostic tag rides unchanged ({@code cc.posture.experiment}
 * server-side, the A/B stamp — un-hashed by the posture law, so it marks intended variance
 * without moving verdicts). The paired up half is {@link ClientPostureEchoMessage}. Wire codec:
 * {@code PostureFrameCodec} (fixed 98-byte {@code PSTB} frame).
 *
 */
public final class ServerPostureBlockMessage {

    public static final String MESSAGE_ID = "server.posture_block";
    public static final int SCHEMA_VERSION = 2;
    public static final String CODEC_OWNER = "server.posture_block.codec";
    public static final String VALIDATION_OWNER = "server.posture_block.validation";
    public static final String RELEVANCE_RULE_ID = "posture_block.per_admission";
    public static final String COMPATIBILITY_PREDICATE_ID = "posture_block_schema_v2";
    public static final String FAILURE_CODE = "POSTURE_BLOCK_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerPostureBlockMessage() {
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
