package cc.starsector.client.net;

/**
 * Client-side mirror of the server's {@code ShipCommandSink.Movement} enum.
 *
 *
 * <p>The mod does not bundle the server adapter classes (same rationale as
 * {@link AwssSnapshotReader} not bundling the snapshot codec): this duplicates the
 * enum's <em>ordinal order</em> so a captured key maps to the exact movement ordinal
 * the registered {@code client.ship_movement.requested} wire frame carries. The
 * ordinal is what crosses the wire (see {@link ManeuverIntentWriter}), so the order
 * here must match the server enum exactly.
 *
 * <p>Kept in sync by the game-free verifier,
 * which asserts each
 * constant's name and ordinal match {@code ShipCommandSink.Movement}. Any reorder or
 * rename on either side breaks that verifier.
 */
public enum ClientMovement {
    ACCELERATE,
    DECELERATE,
    STRAFE_LEFT,
    STRAFE_RIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    STOP
}
