package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipFluxView;

/**
 * Immutable {@link ShipFluxView} snapshot read from the live engine bridge:
 * {@code ShipAPI.getFluxTracker()} scalars plus {@code ShipAPI.getShield()}
 * raised/absent state.
 */
public final class EngineBackedShipFluxView implements ShipFluxView {

    private final long shipId;
    private final float currFlux;
    private final float maxFlux;
    private final float fluxLevel;
    private final float hardFlux;
    private final boolean overloaded;
    private final boolean venting;
    private final boolean hasShield;
    private final boolean shieldOn;
    private final float shieldFacing;

    public EngineBackedShipFluxView(
            long shipId,
            float currFlux,
            float maxFlux,
            float fluxLevel,
            float hardFlux,
            boolean overloaded,
            boolean venting,
            boolean hasShield,
            boolean shieldOn,
            float shieldFacing) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        requireFinite("currFlux", currFlux);
        requireFinite("maxFlux", maxFlux);
        requireFinite("fluxLevel", fluxLevel);
        requireFinite("hardFlux", hardFlux);
        requireFinite("shieldFacing", shieldFacing);
        this.shipId = shipId;
        this.currFlux = currFlux;
        this.maxFlux = maxFlux;
        this.fluxLevel = fluxLevel;
        this.hardFlux = hardFlux;
        this.overloaded = overloaded;
        this.venting = venting;
        this.hasShield = hasShield;
        this.shieldOn = shieldOn;
        this.shieldFacing = shieldFacing;
    }

    /** Back-compat: a flux view with no shield-facing read ({@code shieldFacing=0}). */
    public EngineBackedShipFluxView(
            long shipId,
            float currFlux,
            float maxFlux,
            float fluxLevel,
            float hardFlux,
            boolean overloaded,
            boolean venting,
            boolean hasShield,
            boolean shieldOn) {
        this(shipId, currFlux, maxFlux, fluxLevel, hardFlux, overloaded, venting,
                hasShield, shieldOn, 0f);
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public float currFlux() {
        return currFlux;
    }

    @Override
    public float maxFlux() {
        return maxFlux;
    }

    @Override
    public float fluxLevel() {
        return fluxLevel;
    }

    @Override
    public float hardFlux() {
        return hardFlux;
    }

    @Override
    public boolean overloaded() {
        return overloaded;
    }

    @Override
    public boolean venting() {
        return venting;
    }

    @Override
    public boolean hasShield() {
        return hasShield;
    }

    @Override
    public boolean shieldOn() {
        return shieldOn;
    }

    @Override
    public float shieldFacing() {
        return shieldFacing;
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
