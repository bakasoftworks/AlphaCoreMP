package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's flux + shield status at a simulation
 * step.
 *
 * <p>The engine objects, the flux tracker, the shield system, and any internal
 * classes stay inside the bridge. This view carries only scalar flux/shield facts
 * that can be mixed into authoritative snapshot state — the "flux"/"shieldState"
 * tier the snapshot schema lists as a design target alongside the already-mapped
 * hull/armor {@link ShipDurabilityView}.
 *
 * <p>Flux is central to Starsector combat: a ship near max flux cannot fire and
 * its shield drops, and an overload disables it entirely. Hull/armor durability
 * alone cannot tell a client whether a ship is combat-effective; flux + shield
 * state can. This view is what makes that distinction replicable.
 */
public interface ShipFluxView {

    /** Stable ship identity in the same repository order as {@link ShipMotionView}. */
    long shipId();

    /** Current (soft + hard) flux level. */
    float currFlux();

    /** Maximum flux capacity. */
    float maxFlux();

    /** Current flux as a fraction of capacity (0..1). */
    float fluxLevel();

    /** Hard-flux component (flux that does not vent passively). */
    float hardFlux();

    /** True when the ship is overloaded (cannot act). */
    boolean overloaded();

    /** True when the ship is actively venting flux. */
    boolean venting();

    /** True when the ship has a shield system at all (false for shieldless hulls). */
    boolean hasShield();

    /** True when the ship's shield is currently raised; false when down or absent. */
    boolean shieldOn();

    /**
     * Absolute shield facing in degrees (engine domain, {@code ShieldAPI.getFacing()});
     * 0 when the ship has no shield or the facing handle is unbound — consumers gate
     * on {@link #hasShield()}, never on this value.
     */
    float shieldFacing();
}
