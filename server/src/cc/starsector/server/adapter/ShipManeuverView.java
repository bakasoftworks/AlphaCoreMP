package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's maneuverability / movement-capability
 * envelope at a simulation step: how fast and how agile the ship is
 * able to move, as opposed to where it currently is.
 *
 * <p>The engine objects and any internal classes stay inside the bridge. This
 * view carries only the effective scalar maneuver stats the engine resolves from
 * the hull spec (and any modifiers): top speed, linear acceleration/deceleration,
 * and turn rate / turn acceleration. These describe the <em>capability</em> a ship
 * has to change its motion, which neither the per-tick motion tier (
 * current loc/vel/facing/angular velocity) nor the static physical-profile tier
 * (hull-size class, collision radius, mass) expresses.
 *
 * <p>A server-authoritative simulation needs this envelope to validate and
 * dead-reckon client movement intents: a client that requests a velocity the
 * ship's {@link #maxSpeed()} cannot reach, or a turn faster than its
 * {@link #maxTurnRate()}, is asking for state the server must clamp or reject.
 * The values are the engine's effective (modifier-applied) stat readings, so a
 * speed buff or engine malfunction is reflected here even though the base hull is
 * unchanged.
 */
public interface ShipManeuverView {

    /** Stable ship identity in the same repository order as {@link ShipMotionView}. */
    long shipId();

    /** Effective top speed (engine world units / second). Finite, &ge; 0. */
    float maxSpeed();

    /** Effective linear acceleration (units / second^2). Finite, &ge; 0. */
    float acceleration();

    /** Effective linear deceleration (units / second^2). Finite, &ge; 0. */
    float deceleration();

    /** Effective maximum turn rate (degrees / second). Finite, &ge; 0. */
    float maxTurnRate();

    /** Effective turn acceleration (degrees / second^2). Finite, &ge; 0. */
    float turnAcceleration();
}
