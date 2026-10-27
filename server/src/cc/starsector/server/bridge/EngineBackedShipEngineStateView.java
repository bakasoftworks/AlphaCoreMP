package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipEngineStateView;

/**
 * Immutable {@link ShipEngineStateView} snapshot read from the live engine bridge:
 * per-engine {@code isDisabled()}/{@code isPermanentlyDisabled()} bits off the public
 * {@code getEngineController().getShipEngines()} surface plus the controller's derived
 * flameout state code, with the concrete {@code ShipAPI} kept behind the binding. A
 * plain value holder (like {@link EngineBackedShipAllegianceView}).
 */
public final class EngineBackedShipEngineStateView implements ShipEngineStateView {

    private final long shipId;
    private final int disabledMask;
    private final int permDisabledMask;
    private final int controllerState;

    public EngineBackedShipEngineStateView(
            long shipId, int disabledMask, int permDisabledMask, int controllerState) {
        this.shipId = shipId;
        this.disabledMask = disabledMask;
        this.permDisabledMask = permDisabledMask;
        this.controllerState = controllerState;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public int disabledMask() {
        return disabledMask;
    }

    @Override
    public int permDisabledMask() {
        return permDisabledMask;
    }

    @Override
    public int controllerState() {
        return controllerState;
    }
}
