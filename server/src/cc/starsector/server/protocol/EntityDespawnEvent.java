package cc.starsector.server.protocol;

import java.util.Objects;

/**
 * One server-authoritative entity-despawn event: the entity identified by
 * {@code entityId} left the authoritative world at completed simulation tick
 * {@code simTick} for {@code reason}.
 *
 * <p>This is the explicit counterpart of the implicit snapshot-authoritative
 * despawn: the per-tick {@link AuthoritativeWorldSnapshot} already
 * conveys a despawn by ceasing to carry the entity, but only as an absence a
 * client must infer — and which a relevance-scoped client
 * cannot distinguish from an entity merely leaving its interest set. A
 * {@code ServerEntityDespawnEventMessage} carries this record on a durable
 * gameplay-event lane so a client receives a reliable, unambiguous "entity N
 * left the world for reason R at tick T" signal (kill feed / scoring / despawn
 * FX) rather than guessing from roster diff.
 *
 * <p>The {@code entityId} is the stable network identity (keyed on
 * {@code ShipAPI.getId()}) the snapshot stream uses, so a despawn event
 * names exactly the entity whose {@code entityId} stops appearing — the same
 * identity that survives a roster renumber.
 */
public record EntityDespawnEvent(long entityId, long simTick, EntityDespawnReason reason) {

    public EntityDespawnEvent {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        Objects.requireNonNull(reason, "reason");
    }

    /** A combat-kill despawn for {@code entityId} at {@code simTick}. */
    public static EntityDespawnEvent destroyed(long entityId, long simTick) {
        return new EntityDespawnEvent(entityId, simTick, EntityDespawnReason.DESTROYED);
    }

    /** A non-lethal departure despawn for {@code entityId} at {@code simTick}. */
    public static EntityDespawnEvent departed(long entityId, long simTick) {
        return new EntityDespawnEvent(entityId, simTick, EntityDespawnReason.DEPARTED);
    }
}
