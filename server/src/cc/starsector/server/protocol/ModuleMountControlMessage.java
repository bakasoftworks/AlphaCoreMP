package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message announcing a module-seat handover — the W2b
 * {@code MMNT} lane. A seat's fly-in consumed a
 * granted module claim (kind 1 MOUNT: the seat's FIXED wire id now points at the live fortress
 * module {@code Ship}, the module's old above-band id retires) or a voluntary dismount executed
 * (kind 2 DISMOUNT: the module returns to station duty under a FRESH above-band id). Fanned to
 * EVERY live session at the serve loop's mount/dismount drains, strictly BEFORE the same
 * repoint's RAPP row (single-writer CTRL FIFO carries the order law); replayed
 * per-mounted-seat to late joiners at the admission seam BEFORE the RAPP catch-up (kind 1 with
 * {@code moduleOldWireId=0} sentinel — the client resolves the body by slot TOKEN, old-id is
 * stale-map cleanup only).
 *
 * <p>DOWN-only by construction: the server never reads an MMNT off the wire — zero new input
 * surface. Wire codec: {@code ModuleMountFrameCodec}
 * (fixed-shape {@code MMNT} frame).
 */
public final class ModuleMountControlMessage {

    public static final String MESSAGE_ID = "server.module.mount";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.module_mount.codec";
    public static final String VALIDATION_OWNER = "server.protocol.module_mount.validation";
    public static final String RELEVANCE_RULE_ID = "module_mount.fan_all_sessions";
    public static final String COMPATIBILITY_PREDICATE_ID = "module_mount_schema_v1";
    public static final String FAILURE_CODE = "MODULE_MOUNT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ModuleMountControlMessage() {
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
