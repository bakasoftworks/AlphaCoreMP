package cc.starsector.client.net;

/**
 * Client-side holder mirroring the server's {@code LiveManeuverIntent}:
 * one ship MANEUVER (movement) control intent the mod
 * captures from local input and sends to the dedicated server over the existing
 * maneuver seam ({@link ManeuverIntentWriter} / the server's
 * {@code LiveManeuverIntentCodec} + {@code client.ship_movement.requested} message).
 *
 * <p>Held semantics, exactly like the server record: a single intent models the
 * client holding the movement for the whole publish window. {@code maneuver=true}
 * means "hold {@link #movement()} on my owned ship"; {@code maneuver=false} is the
 * control case (no command — the server issues no maneuver and the ship only drifts),
 * built with {@link #hold(long)}.
 */
public record ClientManeuverIntent(long shipId, boolean maneuver, int movementOrdinal) {

    public ClientManeuverIntent {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (movementOrdinal < 0 || movementOrdinal >= ClientMovement.values().length) {
            throw new IllegalArgumentException(
                    "movementOrdinal out of range [0," + ClientMovement.values().length
                            + "); got " + movementOrdinal);
        }
    }

    /** Client asks the server to hold {@code movement} on {@code shipId}. */
    public static ClientManeuverIntent maneuver(long shipId, ClientMovement movement) {
        return new ClientManeuverIntent(shipId, true, movement.ordinal());
    }

    /** Control intent: client holds no maneuver (no command crosses into the engine). */
    public static ClientManeuverIntent hold(long shipId) {
        return new ClientManeuverIntent(shipId, false, ClientMovement.ACCELERATE.ordinal());
    }

    /** The requested movement category this intent carries. */
    public ClientMovement movement() {
        return ClientMovement.values()[movementOrdinal];
    }
}
