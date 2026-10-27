package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipMotionView;

/**
 * Immutable {@link ShipMotionView} snapshot read from the live engine bridge.
 *
 * <p>The concrete ship object and vector classes stay inside
 * {@link EngineCombatBinding}; this value only carries project-owned scalar
 * motion state at the post-step snapshot point.
 */
public final class EngineBackedShipMotionView implements ShipMotionView {

    private final long shipId;
    private final float locX;
    private final float locY;
    private final float velX;
    private final float velY;
    private final float facing;
    private final float angularVelocity;
    private final float stepAmount;
    private final float timeMult;

    public EngineBackedShipMotionView(
            long shipId,
            float locX,
            float locY,
            float velX,
            float velY,
            float facing,
            float angularVelocity,
            float stepAmount,
            float timeMult) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        requireFinite("locX", locX);
        requireFinite("locY", locY);
        requireFinite("velX", velX);
        requireFinite("velY", velY);
        requireFinite("facing", facing);
        requireFinite("angularVelocity", angularVelocity);
        requireFinite("stepAmount", stepAmount);
        requireFinite("timeMult", timeMult);
        this.shipId = shipId;
        this.locX = locX;
        this.locY = locY;
        this.velX = velX;
        this.velY = velY;
        this.facing = facing;
        this.angularVelocity = angularVelocity;
        this.stepAmount = stepAmount;
        this.timeMult = timeMult;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public float locX() {
        return locX;
    }

    @Override
    public float locY() {
        return locY;
    }

    @Override
    public float velX() {
        return velX;
    }

    @Override
    public float velY() {
        return velY;
    }

    @Override
    public float facing() {
        return facing;
    }

    @Override
    public float angularVelocity() {
        return angularVelocity;
    }

    @Override
    public float stepAmount() {
        return stepAmount;
    }

    @Override
    public float timeMult() {
        return timeMult;
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
