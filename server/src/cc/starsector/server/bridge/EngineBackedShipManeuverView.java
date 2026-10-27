package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipManeuverView;

/**
 * Immutable {@link ShipManeuverView} snapshot read from the live engine bridge.
 */
public final class EngineBackedShipManeuverView implements ShipManeuverView {

    private final long shipId;
    private final float maxSpeed;
    private final float acceleration;
    private final float deceleration;
    private final float maxTurnRate;
    private final float turnAcceleration;

    public EngineBackedShipManeuverView(
            long shipId,
            float maxSpeed,
            float acceleration,
            float deceleration,
            float maxTurnRate,
            float turnAcceleration) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        requireFiniteNonNegative("maxSpeed", maxSpeed);
        requireFiniteNonNegative("acceleration", acceleration);
        requireFiniteNonNegative("deceleration", deceleration);
        requireFiniteNonNegative("maxTurnRate", maxTurnRate);
        requireFiniteNonNegative("turnAcceleration", turnAcceleration);
        this.shipId = shipId;
        this.maxSpeed = maxSpeed;
        this.acceleration = acceleration;
        this.deceleration = deceleration;
        this.maxTurnRate = maxTurnRate;
        this.turnAcceleration = turnAcceleration;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public float maxSpeed() {
        return maxSpeed;
    }

    @Override
    public float acceleration() {
        return acceleration;
    }

    @Override
    public float deceleration() {
        return deceleration;
    }

    @Override
    public float maxTurnRate() {
        return maxTurnRate;
    }

    @Override
    public float turnAcceleration() {
        return turnAcceleration;
    }

    private static void requireFiniteNonNegative(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
        if (value < 0.0f) {
            throw new IllegalArgumentException(label + " must be >= 0; got " + value);
        }
    }
}
