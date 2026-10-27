package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's <em>phase cloak</em> at a simulation
 * step.
 *
 * <p>The cloak is the engine's separate {@code ShipAPI.getPhaseCloak()} slot —
 * NOT the primary {@code getSystem()} the {@link ShipSystemView} tier reads (a
 * Grendel's primary system is {@code ammofeed}; its cloak lives here). The cloak
 * object implements the same {@code ShipSystemAPI} surface, so this view mirrors
 * the {@link ShipSystemView} state fields, plus the one ship-level fact the cloak
 * slot cannot express: {@link #phased()} — the engine's collision-class truth
 * ({@code CollisionClass.NONE} + PHASED_SHIPS_LAYER while phased, including the
 * hittable-past-0.5 chargedown law), read from {@code ShipAPI.isPhased()} so no
 * client ever re-derives it.
 *
 * <p>This is the Move-1 probe surface and the pre-paid server half of the DD4
 * wire lane (a v8-shaped defense-state tier + a {@code phased} status bit);
 * the wire subset is chosen at Move 2. Ships without a cloak
 * contribute no entry; at most one entry per ship.
 */
public interface ShipPhaseCloakView {

    /**
     * Stable identity of the owning ship in the same within-tick repository order
     * as {@link ShipMotionView}/{@link ShipSystemView}.
     */
    long shipId();

    /** Cloak spec id ({@code ShipSystemAPI.getId()}, e.g. {@code "phasecloak"}); never null. */
    String cloakId();

    /**
     * The cloak's {@code ShipSystemAPI$SystemState} as its enum name — one of
     * {@code IDLE}, {@code IN}, {@code ACTIVE}, {@code OUT}, {@code COOLDOWN}.
     * Never null.
     */
    String state();

    /** True while the cloak effect is active ({@code isActive()}). */
    boolean active();

    /** True while the cloak is toggled on ({@code isOn()}); distinct from {@link #active()} across the ramp. */
    boolean on();

    /** Seconds of cooldown left before the cloak may be engaged again (0 when ready). */
    float cooldownRemaining();

    /** The cloak's full cooldown duration in seconds (vanilla phase cloak: 2). */
    float cooldown();

    /** Effect ramp level (0..1) across the 0.5 s charge-up/charge-down profile. */
    float effectLevel();

    /**
     * The engine's own phased truth ({@code ShipAPI.isPhased()}): true through IN/ACTIVE and
     * through the OUT window while {@code effectLevel > 0.5} — the ship is untargetable/
     * collisionless exactly while this is true.
     */
    boolean phased();

    /**
     * Charges left ({@code ShipSystemAPI.getAmmo()}), or {@code -1} when the ammo handle was
     * unavailable.: an ammo-charged zero-duration defense system
     * (canister_flak, max uses 2, regen 0.05) has ~no IN/ACTIVE dwell to sample — the ammo
     * drop + cooldown rise are the only per-tick press signals. Default -1 keeps pre-(62)
     * implementers/fakes compiling and honest.
     */
    default int ammo() {
        return -1;
    }
}
