package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's <em>primary ship system</em> at a
 * simulation step.
 *
 * <p>The engine ship, its {@code getSystem()} result, the concrete internal
 * ship-system impl ({@code com.fs.starfarer.combat.systems.*}), and the
 * {@code ShipSystemAPI$SystemState} enum stay inside the bridge. This view carries
 * only scalar/boolean/String per-system facts that can be mixed into authoritative
 * snapshot state — the ship-system-readiness tier that sits alongside the
 * per-weapon {@link WeaponMountView} and the ship-level {@link ShipFluxView}.
 *
 * <p>This is the read complement to the activation-only command boundary
 * ({@code ShipAPI.useSystem()} via {@link ShipCommandSink}): the product
 * could already <em>fire</em> a primary system but could not <em>read</em> whether
 * one is active, charging, or on cooldown. A ship's primary system is frequently
 * combat-decisive — a fortress shield blocks damage entirely, a burn drive or
 * phase cloak changes what a ship can be hit by, fast missile racks reload — and
 * none of that is expressible by hull/armor durability, flux, or per-weapon mount
 * state. A ship has at most one primary system, so the bridge returns a flat list
 * with one entry per ship that <em>has</em> a system, each tagged with its owning
 * {@link #shipId()}; ships without a primary system contribute no entry.
 */
public interface ShipSystemView {

    /**
     * Stable identity of the owning ship in the same within-tick repository order
     * as {@link ShipMotionView}/{@link ShipFluxView}/{@link WeaponMountView}.
     */
    long shipId();

    /** Ship-system spec id ({@code ShipSystemAPI.getId()}, e.g. {@code "fortressshield"}); never null. */
    String systemId();

    /**
     * The engine's {@code ShipSystemAPI$SystemState} as its enum name — one of
     * {@code IDLE}, {@code IN}, {@code ACTIVE}, {@code OUT}, {@code COOLDOWN}.
     * Never null. The single richest field: it distinguishes the charge-up,
     * active-effect, charge-down, and cooldown phases that the booleans below only
     * partially capture.
     */
    String state();

    /** True when the system's effect is currently active ({@code isActive()}). */
    boolean active();

    /** True when the system is toggled on ({@code isOn()}); distinct from {@link #active()} for ramped systems. */
    boolean on();

    /** Seconds of cooldown left before the system may be used again (0 when ready). */
    float cooldownRemaining();

    /** The system's full cooldown duration in seconds. */
    float cooldown();

    /** Effect ramp level (0..1) for systems with a charge-up/charge-down profile. */
    float effectLevel();

    /** Current charges for charge-based systems ({@code getAmmo()}); engine-reported. */
    int ammo();

    /** Maximum charges ({@code getMaxAmmo()}). */
    int maxAmmo();

    /** True when a charge-based system has no charges left and cannot be used. */
    boolean outOfAmmo();

    /**
     * The attractor edge: world X of the {@code mote_control} attractor target
     * ({@code MoteControlScript.SharedMoteAIData.attractorTarget}, read off the engine's
     * customData by the bridge at the frame the press begins) — {@code NaN} for every other
     * system, when the read is unavailable, or when the target is stale (set by an earlier
     * press). Defaults keep the test/anonymous views compiling.
     */
    default float moteTargetX() {
        return Float.NaN;
    }

    /** World Y twin of {@link #moteTargetX()}. */
    default float moteTargetY() {
        return Float.NaN;
    }

    /**
     * Position shipId ({@code > 0}, the same within-tick order as {@link #shipId()}) of the ship
     * the attractor LOCKED onto ({@code attractorLock}); {@code 0} = empty-space target or no
     * lock resolvable.
     */
    default long moteTargetLockShipId() {
        return 0L;
    }
}
