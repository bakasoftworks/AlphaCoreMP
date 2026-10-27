package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipLifecycleView;
import java.util.Objects;

/**
 * Immutable {@link ShipLifecycleView} read from the live engine bridge: the ship's
 * within-tick repository-order id, the engine's stable entity id returned by
 * {@code ShipAPI.getId()}, and the {@code ShipAPI.isAlive()} liveness flag.
 *
 */
public final class EngineBackedShipLifecycleView implements ShipLifecycleView {

    private final long shipId;
    private final String stableKey;
    private final boolean alive;

    public EngineBackedShipLifecycleView(long shipId, String stableKey, boolean alive) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        Objects.requireNonNull(stableKey, "stableKey");
        if (stableKey.isBlank()) {
            throw new IllegalArgumentException("stableKey must be non-blank");
        }
        this.shipId = shipId;
        this.stableKey = stableKey;
        this.alive = alive;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public String stableKey() {
        return stableKey;
    }

    @Override
    public boolean alive() {
        return alive;
    }
}
