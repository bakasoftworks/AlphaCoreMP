package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipManeuverCommandView;

/**
 * Immutable {@link ShipManeuverCommandView} snapshot read from the live engine bridge:
 * the 7 {@code ShipEngineControllerAPI} maneuver booleans packed in canonical wire-bit
 * order, with the concrete {@code ShipAPI} kept behind the binding. A plain value
 * holder (like {@link EngineBackedShipEngineStateView}).
 */
public final class EngineBackedShipManeuverCommandView implements ShipManeuverCommandView {

    private final long shipId;
    private final int maneuverMask;

    public EngineBackedShipManeuverCommandView(long shipId, int maneuverMask) {
        this.shipId = shipId;
        this.maneuverMask = maneuverMask;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public int maneuverMask() {
        return maneuverMask;
    }
}
