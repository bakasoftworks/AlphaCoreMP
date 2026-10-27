package cc.starsector.server.protocol;

/**
 * Registered server-&gt;client CONTROL message announcing an explicit next-round
 * {@code MATCH_RESET} on a persistent socket (L1, session-lifecycle).
 *
 * <p>This is a control-plane sibling of {@link ServerEntityDespawnEventMessage}: not a
 * per-tick replaceable {@link WorldSnapshotMessage} snapshot and not a client command or
 * its result, but a derived lifecycle event the server pushes inline, between two lethal
 * rounds streamed over the SAME connection, to mark "the round you were watching is over;
 * the frames that follow are a fresh round on a re-deployed roster."
 *
 * <p>Why it exists: the persistent-socket multi-round reset
 * ({@code PersistentDedicatedCombatServer.serveRoundsOnOneClient}) makes the on-wire round
 * boundary IMPLICIT — the read-only decoder infers it from the entity-count sequence
 * ({@code 2..0 -> 2..}). A read-only AI-vs-AI consumer infers that fine, but the
 * <em>predict</em> client runs its own local engine and reconciles against
 * snapshots; it cannot infer a reset from a snapshot it did not author, and it needs an
 * explicit signal to tear down + rebuild its local combat. This message is that explicit
 * signal. Its wire framing is {@code MatchResetFrameCodec} (a distinct {@code "MRST"}
 * magic so a {@code MatchAwareSnapshotStream} demuxer routes it apart from {@code "AWSS"}
 * snapshot frames); it is carried on the durable gameplay-event lane so it is never
 * dropped like a replaceable snapshot frame. Default-OFF on the server
 * ({@code -Dcc.server.emitMatchResetFrame}); the implicit-boundary read-only path stays
 * byte-identical.
 */
public final class MatchResetControlMessage {

    public static final String MESSAGE_ID = "server.match_reset";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.match_reset.codec";
    public static final String VALIDATION_OWNER = "server.protocol.match_reset.validation";
    public static final String RELEVANCE_RULE_ID = "match_reset.next_round_boundary";
    public static final String COMPATIBILITY_PREDICATE_ID = "match_reset_schema_v1";
    public static final String FAILURE_CODE = "MATCH_RESET_CONTROL_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private MatchResetControlMessage() {
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
