package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipCrView;

/**
 * Immutable {@link ShipCrView} snapshot read from the live engine bridge.
 * The concrete ship object, its mutable stats, and the
 * hull spec stay inside {@link EngineCombatBinding}; this value carries only
 * project-owned scalars.
 */
public final class EngineBackedShipCrView implements ShipCrView {

    private final long shipId;
    private final float currentCr;
    private final float timeDeployed;
    private final float peakCr;
    private final int fighterReplacementPct;

    public EngineBackedShipCrView(long shipId, float currentCr, float timeDeployed, float peakCr,
            int fighterReplacementPct) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        requireFinite("currentCr", currentCr);
        requireFinite("timeDeployed", timeDeployed);
        requireFinite("peakCr", peakCr);
        if (fighterReplacementPct != NO_FIGHTER_REPLACEMENT_PCT
                && (fighterReplacementPct < 0 || fighterReplacementPct > 100)) {
            throw new IllegalArgumentException(
                    "fighterReplacementPct must be 0..100 or NONE; got " + fighterReplacementPct);
        }
        this.shipId = shipId;
        this.currentCr = currentCr;
        this.timeDeployed = timeDeployed;
        this.peakCr = peakCr;
        this.fighterReplacementPct = fighterReplacementPct;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public float currentCr() {
        return currentCr;
    }

    @Override
    public float timeDeployed() {
        return timeDeployed;
    }

    @Override
    public float peakCr() {
        return peakCr;
    }

    @Override
    public int fighterReplacementPct() {
        return fighterReplacementPct;
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
