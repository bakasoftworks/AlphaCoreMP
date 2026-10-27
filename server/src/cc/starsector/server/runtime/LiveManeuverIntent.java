package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;

/**
 * One remote client's ship MANEUVER (movement) control intent for the live
 * authoritative round-trip.
 *
 * <p>The maneuver sibling of {@link LiveClientIntent}: where the fire intent closes
 * the round-trip on a weapon command, this one closes it on a
 * {@link ShipCommandSink.Movement} command. A separate-JVM client sends one of these
 * over the socket (encoded by {@link LiveManeuverIntentCodec}, bound to the registered
 * {@code client.ship_movement.requested} message), the bidirectional
 * {@link LiveWorldSnapshotSocketPublisher} reads it before the publish loop and threads
 * it into the live step via the command-authority maneuver seam (
 * {@code ShipAPI.giveCommand}), so the per-tick {@code AuthoritativeWorldSnapshot} that
 * crosses back carries the MOTION effect of the client's <em>own</em> maneuver (the
 * commanded ship accelerates/turns) — not a server script.
 *
 * <p>Held semantics: a single intent models the client holding the maneuver for the
 * whole publish window. {@code maneuver=true} means "hold this movement on my owned
 * ship"; {@code maneuver=false} is the control case (no command), under which the
 * server issues no maneuver and the commanded ship only drifts.
 */
public record LiveManeuverIntent(long shipId, boolean maneuver, int movementOrdinal) {

    public LiveManeuverIntent {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (movementOrdinal < 0 || movementOrdinal >= ShipCommandSink.Movement.values().length) {
            throw new IllegalArgumentException(
                    "movementOrdinal out of range [0," + ShipCommandSink.Movement.values().length
                            + "); got " + movementOrdinal);
        }
    }

    /** Client asks the server to hold {@code movement} on {@code shipId}. */
    public static LiveManeuverIntent maneuver(long shipId, ShipCommandSink.Movement movement) {
        return new LiveManeuverIntent(shipId, true, movement.ordinal());
    }

    /** Control intent: client holds no maneuver (no command crosses into the engine). */
    public static LiveManeuverIntent hold(long shipId) {
        return new LiveManeuverIntent(shipId, false, ShipCommandSink.Movement.ACCELERATE.ordinal());
    }

    /** The requested movement category this intent carries. */
    public ShipCommandSink.Movement movement() {
        return ShipCommandSink.Movement.values()[movementOrdinal];
    }
}
