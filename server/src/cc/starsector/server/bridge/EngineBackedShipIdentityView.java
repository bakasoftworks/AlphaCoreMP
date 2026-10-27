package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipIdentityView;
import java.util.Objects;

/**
 * Immutable {@link ShipIdentityView} read from the live engine bridge: the ship's
 * repository-order id plus the hull-spec content key returned by
 * {@code ShipAPI.getHullSpec().getHullId()}.
 */
public final class EngineBackedShipIdentityView implements ShipIdentityView {

    private final long shipId;
    private final String contentKey;

    public EngineBackedShipIdentityView(long shipId, String contentKey) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        Objects.requireNonNull(contentKey, "contentKey");
        if (contentKey.isBlank()) {
            throw new IllegalArgumentException("contentKey must be non-blank");
        }
        this.shipId = shipId;
        this.contentKey = contentKey;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public String contentKey() {
        return contentKey;
    }
}
