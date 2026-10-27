package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's static physical profile at a simulation
 * step: its hull size classification plus collision geometry/mass.
 *
 * <p>The engine objects and any internal classes stay inside the bridge. This
 * view carries only the scalar/classification facts that describe how big a ship
 * is and how it occupies space: the {@code HullSize} class (frigate/destroyer/
 * cruiser/capital/fighter), its collision radius, and its mass. These are the
 * physical-profile fields a client needs to weight relevance and priority (a
 * capital ship matters at a longer range than a frigate) and to reason about
 * collision footprint — none of which the per-tick motion, durability, flux, or
 * weapon-mount tiers express.
 *
 * <p>The hull size is carried as the {@link Enum#name()} of the engine's
 * {@code ShipAPI$HullSize} enum so no game type crosses the adapter boundary,
 * the same convention the ship-system {@code state} read uses.
 */
public interface ShipProfileView {

    /** Stable ship identity in the same repository order as {@link ShipMotionView}. */
    long shipId();

    /**
     * Hull size class name (e.g. {@code FRIGATE}, {@code DESTROYER}, {@code CRUISER},
     * {@code CAPITAL_SHIP}, {@code FIGHTER}, {@code DEFAULT}) — the {@link Enum#name()}
     * of the engine's {@code ShipAPI$HullSize}. Never blank.
     */
    String hullSize();

    /** Collision radius (engine world units); the ship's circular collision footprint. */
    float collisionRadius();

    /** Mass used by the engine's collision/physics resolution. */
    float mass();
}
