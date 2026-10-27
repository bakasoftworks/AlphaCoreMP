package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipDurabilityView;
import cc.starsector.server.adapter.ShipMotionView;
import java.util.Objects;

/**
 * One ship's authoritative per-entity state as it is replicated to clients.
 *
 * <p>This is the per-entity unit the snapshot-authoritative-only rule mandates:
 * the server is the sole simulation authority and replicates reconstructable
 * authoritative state, not an opaque per-tick aggregate hash and not state-input
 * for client re-simulation. It promotes the per-entity fields sketched in
 * the snapshot schema draft ("Per-entity snapshot fields") into a
 * consumed product type.
 *
 * <p>It is built from the project-owned {@link ShipMotionView} and
 * {@link ShipDurabilityView} adapter contracts (the same interfaces the live
 * {@code EngineBacked*} bridge views implement), so it carries only scalar,
 * version-independent state — never a non-API engine class. The
 * {@code entityId} is the stable network identity, distinct from the engine
 * object reference, which is never sent on the wire.
 *
 * <p>{@code armorLevel} arrives pre-normalized (0..1 of the ship's armor pool)
 * from the durability view, like {@code hullLevel} — the adapter owns the
 * engine-exact denominator.
 */
public record AuthoritativeEntitySnapshot(
        long entityId,
        int contentId,
        long spawnTick,
        int ownerConnectionId,
        float locX,
        float locY,
        float velX,
        float velY,
        float facing,
        float angularVelocity,
        float hitpoints,
        float hullLevel,
        float armorLevel,
        int armorCellCount,
        float armorTotal,
        long armorGridHash) {

    /** Sentinel owner for AI / neutral entities not owned by any client connection. */
    public static final int NO_OWNER_CONNECTION = -1;

    public AuthoritativeEntitySnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if (spawnTick < 0L) {
            throw new IllegalArgumentException("spawnTick must be >= 0; got " + spawnTick);
        }
        if (ownerConnectionId < NO_OWNER_CONNECTION) {
            throw new IllegalArgumentException(
                    "ownerConnectionId must be >= -1; got " + ownerConnectionId);
        }
        if (armorCellCount < 0) {
            throw new IllegalArgumentException("armorCellCount must be >= 0; got " + armorCellCount);
        }
        requireFinite("locX", locX);
        requireFinite("locY", locY);
        requireFinite("velX", velX);
        requireFinite("velY", velY);
        requireFinite("facing", facing);
        requireFinite("angularVelocity", angularVelocity);
        requireFinite("hitpoints", hitpoints);
        requireFinite("hullLevel", hullLevel);
        requireFinite("armorLevel", armorLevel);
        requireFinite("armorTotal", armorTotal);
    }

    /** True when this entity is owned by the given client connection. */
    public boolean ownedBy(int connectionId) {
        return ownerConnectionId != NO_OWNER_CONNECTION && ownerConnectionId == connectionId;
    }

    /**
     * Build the per-entity snapshot from the two project-owned read models for one
     * ship at the post-step snapshot point. {@code motion} and {@code durability}
     * must describe the same ship (matching {@code shipId()}); that shared id
     * becomes the replicated {@code entityId}.
     */
    public static AuthoritativeEntitySnapshot fromViews(
            ShipMotionView motion,
            ShipDurabilityView durability,
            int contentId,
            long spawnTick,
            int ownerConnectionId) {
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(durability, "durability");
        if (motion.shipId() != durability.shipId()) {
            throw new IllegalArgumentException(
                    "motion/durability views describe different ships: "
                            + motion.shipId() + " vs " + durability.shipId());
        }
        return fromViewsWithEntityId(
                motion.shipId(), motion, durability, contentId, spawnTick, ownerConnectionId);
    }

    /**
     * Build the per-entity snapshot from the two project-owned read models for one
     * ship, but with an explicit stable {@code entityId} rather than the
     * position-derived {@code motion.shipId()}.
     *
     * <p>This is the stable-roster path: the
     * within-tick {@code shipId} on the motion/durability views is the engine
     * repository's <em>iteration position</em>, which renumbers the survivors when a
     * ship leaves combat. The caller (the engine-backed live source in stable-roster
     * mode) resolves the engine's stable id ({@code ShipAPI.getId()}) into a network
     * {@code entityId} via {@code ShipNetworkIdRegistry} and passes it here, so the
     * replicated identity survives roster changes. The {@code motion.shipId() ==
     * durability.shipId()} check still guards that the two views describe the same
     * ship within this tick.
     */
    public static AuthoritativeEntitySnapshot fromViewsWithEntityId(
            long entityId,
            ShipMotionView motion,
            ShipDurabilityView durability,
            int contentId,
            long spawnTick,
            int ownerConnectionId) {
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(durability, "durability");
        if (motion.shipId() != durability.shipId()) {
            throw new IllegalArgumentException(
                    "motion/durability views describe different ships: "
                            + motion.shipId() + " vs " + durability.shipId());
        }
        return new AuthoritativeEntitySnapshot(
                entityId,
                contentId,
                spawnTick,
                ownerConnectionId,
                motion.locX(),
                motion.locY(),
                motion.velX(),
                motion.velY(),
                motion.facing(),
                motion.angularVelocity(),
                durability.hitpoints(),
                durability.hullLevel(),
                durability.armorLevel(),
                durability.armorCellCount(),
                durability.armorTotal(),
                durability.armorGridHash());
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
