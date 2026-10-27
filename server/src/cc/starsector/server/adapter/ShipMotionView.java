package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's server-owned motion state at a
 * specific simulation step.
 *
 * <p>Exposes observed transforms and the movement-integration inputs needed to
 * reproduce them. It must not expose internal engine classes and must not let
 * command code mutate transforms directly; command intent goes through
 * {@link ShipCommandSink}.
 *
 * <p>Derived from the ShipMotionView contract.
 * Earlier analysis mapped the Hound facing transition to the engine's generic
 * movement-integration pass, which advances facing by
 * {@code angularVelocity * stepAmount * timeMult}; that boundary is the first
 * promotion target for this view.
 */
public interface ShipMotionView {

    /** Stable ship identity; version-specific engine identity stays hidden. */
    long shipId();

    /** Simulation-space X coordinate. */
    float locX();

    /** Simulation-space Y coordinate. */
    float locY();

    /** Simulation-space X velocity component. */
    float velX();

    /** Simulation-space Y velocity component. */
    float velY();

    /** Facing angle. */
    float facing();

    /** Angular velocity. */
    float angularVelocity();

    /** Fixed step amount used for the current movement integration, in seconds. */
    float stepAmount();

    /** Engine time multiplier applied to the current movement integration. */
    float timeMult();
}
