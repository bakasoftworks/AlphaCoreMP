package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's lifecycle/identity at a simulation step:
 * the engine's <em>stable</em> entity identity plus whether the ship is still a live
 * combatant, as opposed to its mutable motion ({@link ShipMotionView}), durability
 * ({@link ShipDurabilityView}), and content type ({@link ShipIdentityView}).
 *
 * <p>The crucial property is {@link #stableKey()}: a cross-tick identity that does
 * <em>not</em> change when the combat roster's membership or order changes. The
 * motion/durability/identity views all carry a {@code shipId} derived from the
 * engine repository's <em>iteration position</em>, which silently renumbers the
 * survivors when a ship is removed (ship #3 becomes #2 after #2 dies). Keying a
 * client-facing {@code entityId} on the position id therefore breaks identity
 * continuity across any roster change. This view exposes the engine's own stable id
 * ({@code ShipAPI.getId()}) so the server can assign a network
 * {@code entityId} that survives ships leaving (death/removal) or entering
 * (reinforcement) combat.
 *
 * <p>The engine object stays inside the bridge; this view carries only the
 * within-tick {@code shipId} (for pairing with the other per-ship views read in the
 * same tick), the version-independent {@code stableKey} string, and the
 * {@code alive} liveness flag ({@code ShipAPI.isAlive()}). The internal
 * reads that produce them are owned by {@code EngineCombatBinding}.
 */
public interface ShipLifecycleView {

    /**
     * Within-tick repository-order id, the same {@code shipId} the motion, durability,
     * and identity views read in the same tick use — for pairing the four views that
     * describe one ship at one step. This is <em>not</em> stable across ticks when the
     * roster changes; use {@link #stableKey()} for cross-tick identity.
     */
    long shipId();

    /**
     * The engine's stable per-entity id ({@code ShipAPI.getId()}). Stable across ticks
     * and roster changes for the lifetime of the ship in this combat. Never null or
     * blank: the bridge fails closed rather than emit an empty key.
     */
    String stableKey();

    /**
     * Whether this ship is still a live combatant ({@code ShipAPI.isAlive()}). A ship
     * that has been destroyed (a hulk, or removed from the active roster) reads
     * {@code false}; the server drops it from the authoritative snapshot so its
     * {@code entityId} simply stops appearing — the snapshot-authoritative despawn
     * signal.
     */
    boolean alive();
}
