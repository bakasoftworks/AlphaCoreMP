package cc.starsector.client.net;

import java.util.Objects;

/**
 * One client-derived entity-despawn: the server entity {@code entityId} stopped
 * appearing in the authoritative snapshot roster at decoded tick {@code simTick},
 * classified {@code reason} from its last-seen hull.
 *
 * <p>This is the client-side analogue of the server's
 * {@code cc.starsector.server.protocol.EntityDespawnEvent}, but <em>derived</em>
 * by {@link RosterLifecycleTracker} from roster diff over the snapshot stream the
 * client already receives — not transported as a separate durable message. It is
 * the signal the puppet plugin acts on to remove the local puppet a vanished
 * server entity was driving (the lifecycle reaction the client previously lacked).
 *
 * <p>Roster-diff derivation is correct under the current full-roster broadcast
 * (every client receives every entity), where a missing entity unambiguously left
 * the world. It is <em>not</em> relevance-safe: once per-peer relevance scoping is
 * added to this path a missing entity could merely have left the peer's interest
 * set, which roster diff alone cannot distinguish from a real despawn. The
 * relevance-safe upgrade is to consume the server's explicit
 * {@code ServerEntityDespawnEventMessage} (emitted from the full authoritative
 * roster) over a durable lane — the documented follow-on.
 */
public final class ClientEntityDespawn {

    private final long entityId;
    private final long simTick;
    private final ClientDespawnReason reason;

    public ClientEntityDespawn(long entityId, long simTick, ClientDespawnReason reason) {
        this.entityId = entityId;
        this.simTick = simTick;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public long entityId() {
        return entityId;
    }

    public long simTick() {
        return simTick;
    }

    public ClientDespawnReason reason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientEntityDespawn other)) {
            return false;
        }
        return entityId == other.entityId
                && simTick == other.simTick
                && reason == other.reason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityId, simTick, reason);
    }

    @Override
    public String toString() {
        return "ClientEntityDespawn{entityId=" + entityId
                + ",simTick=" + simTick
                + ",reason=" + reason + "}";
    }
}
