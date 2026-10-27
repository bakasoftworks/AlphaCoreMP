package cc.starsector.server.protocol;

/**
 * Registered client message echoing the client's RESOLVED posture hashes — the M4-A2 up half of
 * the client-posture detection pair ({@code PSTE}).
 * Sent exactly ONCE at attach on the CTRL
 * lane (the {@code SNAM} announce precedent), independent of block arrival — schema-1 blocks are
 * knob-empty, so the client's class-load-resolved posture is already final at attach.
 *
 * <p>Carries the {@code ClientPostureRegistry} row/unknown counts plus BOTH 32-byte digests
 * (full + behavior — the working decision: verdicts key on the BEHAVIOR hash, instruments
 * legitimately vary per-client, the full hash is informational drift). Echoed-hash !=
 * server-computed expected baseline is machine proof the client was locally re-postured (or a
 * real bug) — v1 enforcement is DETECTION (loud {@code posture-mismatch} row);
 * admission-refusal stays a later decision. Wire codec: {@code PostureFrameCodec} (fixed
 * 82-byte {@code PSTE} frame)..
 */
public final class ClientPostureEchoMessage {

    public static final String MESSAGE_ID = "client.posture_echo";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.client.posture_echo.codec";
    public static final String VALIDATION_OWNER = "server.client.posture_echo.validation";
    public static final String RELEVANCE_RULE_ID = "posture_echo.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "posture_echo_schema_v1";
    public static final String FAILURE_CODE = "POSTURE_ECHO_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.AUTHORITY_INPUT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientPostureEchoMessage() {
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
