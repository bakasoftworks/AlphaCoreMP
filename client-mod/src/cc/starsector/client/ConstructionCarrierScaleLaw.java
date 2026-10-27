package cc.starsector.client;

/**
 * The PURE construction-carrier size law, engine-free ON PURPOSE so
 * {@code SyncDefectLawSelftest} drives it headless through {@link SyncDefectLawProbe} (the lane
 * class imports engine API types and cannot load game-free). Consumer =
 * {@link ConstructionPresentLane#onCarrierBound}.
 *
 * <p>Vanilla's fabricator system ({@code ConstructionSwarmSystemScript.launchSwarm}) sizes
 * the construction-swarm fighter to the hull it will build: {@code hpMult} (= {@code radiusMult}) is
 * 1 / 2 / 3.5 / 4 and {@code collisionMult} 2 / 4 / 6 / 8 for a frigate / destroyer / cruiser /
 * capital pick. The wire keyframe's max hull is the ONE scaled number the client
 * receives and {@code hpMult} is injective over the four classes, so {@code wireMax / localMax}
 * names the class and the two multipliers follow from vanilla's own table.
 */
final class ConstructionCarrierScaleLaw {

    /** {@link #carrierScaleClass} codes — vanilla's four {@code launchSwarm} size branches. */
    static final int SCALE_UNKNOWN = 0;
    static final int SCALE_FRIGATE = 1;
    static final int SCALE_DESTROYER = 2;
    static final int SCALE_CRUISER = 3;
    static final int SCALE_CAPITAL = 4;

    private ConstructionCarrierScaleLaw() {
    }

    /**
     * Which of vanilla's {@code launchSwarm} branches a {@code wireMax / localMax} hull ratio names.
     * The wire max is the server's {@code round(hp / level)}, so the windows are wide enough for
     * rounding and disjoint (3.35–3.65 vs 3.85–4.15); anything else — including NaN, ∞, ≤0 — is
     * {@link #SCALE_UNKNOWN} (fail-open: the body is left unscaled).
     */
    static int carrierScaleClass(float hpRatio) {
        if (!(hpRatio > 0f) || Float.isInfinite(hpRatio)) {
            return SCALE_UNKNOWN;
        }
        if (Math.abs(hpRatio - 1f) <= 0.05f) {
            return SCALE_FRIGATE;
        }
        if (Math.abs(hpRatio - 2f) <= 0.1f) {
            return SCALE_DESTROYER;
        }
        if (Math.abs(hpRatio - 3.5f) <= 0.15f) {
            return SCALE_CRUISER;
        }
        if (Math.abs(hpRatio - 4f) <= 0.15f) {
            return SCALE_CAPITAL;
        }
        return SCALE_UNKNOWN;
    }

    /** Vanilla's {@code collisionMult} per class; 1 for unknown. */
    static float carrierCollisionMult(int scaleClass) {
        switch (scaleClass) {
            case SCALE_FRIGATE: return 2f;
            case SCALE_DESTROYER: return 4f;
            case SCALE_CRUISER: return 6f;
            case SCALE_CAPITAL: return 8f;
            default: return 1f;
        }
    }

    /** Vanilla's {@code radiusMult} (= {@code hpMult}) per class; 1 for unknown. */
    static float carrierRadiusMult(int scaleClass) {
        switch (scaleClass) {
            case SCALE_DESTROYER: return 2f;
            case SCALE_CRUISER: return 3.5f;
            case SCALE_CAPITAL: return 4f;
            default: return 1f;
        }
    }
}
