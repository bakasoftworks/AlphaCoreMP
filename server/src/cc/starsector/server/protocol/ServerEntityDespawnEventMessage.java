package cc.starsector.server.protocol;

/**
 * Registered server-&gt;client message announcing one authoritative entity despawn
 * ({@link EntityDespawnEvent}).
 *
 * <p>This is a new <em>event</em> message, distinct in kind from every prior
 * registry entry: not a per-tick replaceable snapshot (the
 * {@link WorldSnapshotMessage} tier), not a client command or its result, and not
 * a session/admission control frame. It is a derived gameplay event the server
 * pushes when an entity crosses a world-membership transition (it leaves the
 * authoritative roster), carried on the durable gameplay-event lane so it is not
 * dropped like a replaceable snapshot frame.
 *
 * <p>Why it exists: under snapshot-authoritative replication a despawn is otherwise only the <em>absence</em>
 * of an entity from the next snapshot, which a relevance-scoped peer
 * cannot tell apart from the entity leaving its interest set.
 * The event resolves that ambiguity. Its producer is
 * {@code EntityDespawnEventDetector} (server-side, over the full authoritative
 * roster) and its wire framing is {@code EntityDespawnEventCodec}; the lethal
 * despawn it reports is the one proven on the real engine.
 *
 */
public final class ServerEntityDespawnEventMessage {

    public static final String MESSAGE_ID = "server.entity_despawn_event";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.entity_despawn_event.codec";
    public static final String VALIDATION_OWNER = "server.protocol.entity_despawn_event.validation";
    public static final String RELEVANCE_RULE_ID = "entity_despawn_event.world_membership_loss";
    public static final String COMPATIBILITY_PREDICATE_ID = "entity_despawn_event_schema_v1";
    public static final String FAILURE_CODE = "ENTITY_DESPAWN_EVENT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerEntityDespawnEventMessage() {
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
