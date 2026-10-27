package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's stable content identity at a simulation
 * step: which hull/content the entity <em>is</em>, as opposed to its mutable
 * motion ({@link ShipMotionView}) and durability ({@link ShipDurabilityView}).
 *
 * <p>The engine object and any engine-internal hull-spec class stay inside the bridge.
 * This view carries only the {@code shipId} (same repository-order identity as the
 * motion/durability views) and a version-independent {@code contentKey} string —
 * the engine hull-spec id (e.g. {@code "hound"}). The server interns that key into
 * the snapshot's numeric {@code contentId} (see
 * {@code cc.starsector.server.runtime.ContentIdRegistry}); the engine-internal read that
 * produces {@code contentKey} is {@code ShipAPI.getHullSpec().getHullId()},
 * owned by {@code EngineCombatBinding}.
 */
public interface ShipIdentityView {

    /** Stable ship identity in the same repository order as {@link ShipMotionView}. */
    long shipId();

    /**
     * Version-independent content key for this entity (the engine hull-spec id).
     * Never null or blank: the bridge fails closed rather than emit an empty key.
     */
    String contentKey();
}
