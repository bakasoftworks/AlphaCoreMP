package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipProfileView;

/**
 * Immutable {@link ShipProfileView} snapshot read from the live engine bridge.
 */
public final class EngineBackedShipProfileView implements ShipProfileView {

    private final long shipId;
    private final String hullSize;
    private final float collisionRadius;
    private final float mass;

    public EngineBackedShipProfileView(
            long shipId,
            String hullSize,
            float collisionRadius,
            float mass) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (hullSize == null || hullSize.isEmpty()) {
            throw new IllegalArgumentException("hullSize must be non-blank");
        }
        requireFiniteNonNegative("collisionRadius", collisionRadius);
        requireFiniteNonNegative("mass", mass);
        this.shipId = shipId;
        this.hullSize = hullSize;
        this.collisionRadius = collisionRadius;
        this.mass = mass;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public String hullSize() {
        return hullSize;
    }

    @Override
    public float collisionRadius() {
        return collisionRadius;
    }

    @Override
    public float mass() {
        return mass;
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
