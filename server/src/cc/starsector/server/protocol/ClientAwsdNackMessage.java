package cc.starsector.server.protocol;

/**
 * Registered client authority-input message for one AWSD delta-reject NACK — the
 * loss-recovery lane, {@code SNNK} on the wire.
 *
 * <p>A client that must DROP an AWSD delta (missing anchor after keyframe loss, or an
 * epoch-mismatch/apply failure against the anchor it holds) reports the affected entityId +
 * the rejected delta's epoch (ADVISORY — diagnostics only) once per (id, epoch) on the
 * reliable CTRL lane; the server answers with an out-of-turn shared KEYFRAME for that entity
 * at the next compose (a selection of the shared stream, never per-peer bytes — the
 * encode-once law; latency ≤ RTT + 1 tick). Never triggered by a STALE keyframe (a fresh
 * anchor already exists). Wire codec: {@code AwsdNackFrameCodec} (SNNK, 17-B fixed width).
 */
public final class ClientAwsdNackMessage {

    public static final String MESSAGE_ID = "client.world_snapshot.awsd_nack";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.client.awsd_nack.codec";
    public static final String VALIDATION_OWNER = "server.client.awsd_nack.validation";
    public static final String RELEVANCE_RULE_ID = "awsd_nack.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "awsd_nack_schema_v1";
    public static final String FAILURE_CODE = "AWSD_NACK_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.AUTHORITY_INPUT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientAwsdNackMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.CLIENT_REQUEST,
                ReliabilityLane.DURABLE_GAMEPLAY_EVENT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
