package cc.starsector.server.bridge;

/**
 * Test-only public shim over the package-private pure law in {@link EngagedCarrierBandSnap}
 * (the contract rows live in {@code cc.starsector.server.tests}). No behavior; never called by
 * the product.
 */
public final class EngagedCarrierBandSnapTestAccess {
    public static final float BAND_EXIT_MARGIN = EngagedCarrierBandSnap.BAND_EXIT_MARGIN;

    private EngagedCarrierBandSnapTestAccess() {
    }

    public static float engageBand(float maxFlux, float dissipation, float dt, float zeroFluxLevel) {
        return EngagedCarrierBandSnap.engageBand(maxFlux, dissipation, dt, zeroFluxLevel);
    }

    public static boolean needsPreSnap(float curr, float band, float dissipationStep) {
        return EngagedCarrierBandSnap.needsPreSnap(curr, band, dissipationStep);
    }

    public static float preSnapValue(float band) {
        return EngagedCarrierBandSnap.preSnapValue(band);
    }
}
