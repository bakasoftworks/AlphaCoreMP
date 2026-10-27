package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message narrating a seat's command transfer — the W3
 * L3 {@code XFER} lane.
 * One frame per transfer edge: BEGIN (a pod flight minted), DENY (the row died with NO flight),
 * COMMIT (the dock-gated re-key executed — the seat's fixed wire id re-points, MMNT stale-map
 * semantics), ABORT (a flight existed and died). Fanned to EVERY live session at the serve
 * lane's emission sites; in-flight rows replay as BEGIN to late joiners AFTER the RAPP
 * catch-up (the deliberate MMNT-ordering inversion — a replayed BEGIN references roster ids
 * RAPP just taught; arbiter state IS the replay source, no append-log).
 *
 * <p>DOWN-only by construction: the server never reads an XFER off the wire — zero new input
 * surface. Human-readable reason lines KEEP
 * fanning on CHTD unchanged; XFER is the machine substrate (they coexist like MMNT+RAPP).
 * Wire codec: {@code CommandTransferFrameCodec} (fixed-shape {@code XFER} frame).
 *
 */
public final class CommandTransferControlMessage {

    public static final String MESSAGE_ID = "server.command_transfer";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.command_transfer.codec";
    public static final String VALIDATION_OWNER = "server.protocol.command_transfer.validation";
    public static final String RELEVANCE_RULE_ID = "command_transfer.fan_all_sessions";
    public static final String COMPATIBILITY_PREDICATE_ID = "command_transfer_schema_v1";
    public static final String FAILURE_CODE = "COMMAND_TRANSFER_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private CommandTransferControlMessage() {
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
