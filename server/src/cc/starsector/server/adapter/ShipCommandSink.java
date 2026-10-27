package cc.starsector.server.adapter;

/**
 * Project-owned write surface for ship command intent.
 *
 * <p>Accepts ordered control requests from AI or authenticated player input and
 * exposes what was requested. Movement integration remains owned by the combat
 * engine and is observed through {@link ShipMotionView}; this sink never
 * publishes authoritative transforms. Unsupported or unmapped commands fail
 * closed until the owning engine boundary is mapped.
 *
 * <p>Derived from the ShipCommandSink contract.
 * AI and player input share this surface after authentication and server-side
 * validation.
 */
public interface ShipCommandSink {

    /** Movement intent categories. */
    enum Movement { ACCELERATE, DECELERATE, STRAFE_LEFT, STRAFE_RIGHT, TURN_LEFT, TURN_RIGHT, STOP }

    /** Where a command originated, for ordering and validation. */
    enum Source { NPC_AI, PLAYER }

    /** Result of attempting to apply a command for a tick. */
    enum Result { APPLIED, REJECTED_UNSUPPORTED, REJECTED_INVALID }

    /** Submit a movement intent for {@code shipId} on {@code simTick}. */
    Result submitMovement(long shipId, Source source, long simTick, Movement intent);

    /** Select a weapon group (0-based) as the active group for {@code shipId}. */
    Result selectWeaponGroup(long shipId, Source source, long simTick, int group);

    /** Request fire / hold-fire for the active weapon group. */
    Result submitFire(long shipId, Source source, long simTick, boolean fire);

    /** Request ship-system activation (vent, shield, phase, system) by mapped id. */
    Result activateSystem(long shipId, Source source, long simTick, int systemId);
}
