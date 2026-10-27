package cc.starsector.client;

/**
 * Foe replica FLUX-PIN law: a 2-cell dead band + a vent-drain guard in front of the per-frame
 * {@code setCurrFlux(pose.currFlux())} pin.
 *
 *
 * <p>WHY: {@code FoeStateLaneCoordinator.maintainFoeFlux} pinned the replica's flux to the
 * RTT-old wire value on EVERY frame with no band. (a) During a local NATIVE vent (the one
 * {@link FoeVentWindowLaw} begins) the drain runs ahead of the lagging wire, and each pin
 * re-filled it to the stale value = a stepped / stalled vent bar (the root).
 * (b) A local value one quantum off the wire (a straddle of the u8 grid) was
 * re-written every frame = sub-quantum jitter. The law: |local - wire| inside the band ⇒ skip
 * (nothing a reader can see); both sides venting and the wire ABOVE the local value ⇒ skip (the
 * local drain legitimately leads; the wire END and {@link FoeVentWindowLaw} own the tail); else
 * pin as before. A wire value BELOW a venting local one still pins (the wire is authority).
 *
 * <p>Band = {@link #DEAD_BAND_CELLS} AWSD flux cells: the AWSD keyframe carries flux as a u8
 * FRACTION of max ({@code AwsdSnapshotCodec}:
 * {@code fluxLevel = fracU8(flux.fluxLevel())}, {@code fracU8} = round(f*255); the
 * client dequant is {@code (fluxLevel/255f) * maxFlux}, in {@code AwsdSnapshotReader}), so
 * the grid is maxFlux/255 and the band 2/255 of max. A NaN / zero / negative max (a zero-capacity
 * module, a flux-less pose) or a non-finite value ⇒ apply (legacy). Client-side; adds
 * no latency (a skip never delays a value past the band; the pin edge is unchanged); the caller's
 * {@code foeFluxSamples}/{@code foeFluxMaxDelta} meters are computed BEFORE the decision so the
 * divergence stays visible. Caller: {@code FoeStateLaneCoordinator.maintainFoeFlux}. Smoke:
 * {@code FoeFluxPinLawSmoke}.
 */
final class FoeFluxPinLaw {

    /** Pin this frame (the legacy path: setCurrFlux + setHardFlux + boost-clock settle). */
    static final int PIN_APPLY = 0;
    /** |local - wire| <= band: inside the quantization dead band, no write. */
    static final int PIN_SKIP_BAND = 1;
    /** Both venting and the wire above local: the local drain leads the lagging wire, no refill. */
    static final int PIN_SKIP_VENT_DRAIN = 2;

    /** The AWSD u8 flux-fraction grid of {@code AwsdSnapshotCodec}. Widen WITH that codec. */
    static final int FLUX_CELLS = 255;
    /** Dead band in wire cells; 1 cell is the quantization straddle, 2 leaves margin (OwnCrPinLaw). */
    static final int DEAD_BAND_CELLS = 2;
    /** The band as a fraction of max flux: 2/255. */
    static final float FLOOR_FRAC = (float) DEAD_BAND_CELLS / (float) FLUX_CELLS;

    private FoeFluxPinLaw() {
    }

    /** The absolute band in flux units, never negative. */
    static float band(float maxFlux) {
        return Math.max(FLOOR_FRAC * maxFlux, 0f);
    }

    /**
     * The per-frame verdict. WHY = the class javadoc (the
     * vent-bar refill + straddle jitter). Correction:
     * the band skip needs BOTH axes inside the band — {@code wireHard} NaN = the wire carried no
     * hard flux (curr alone decides); a hard-only gap past the band pins the pair (the
     * {@code OwnFluxPinLaw.pairPinDue} rule: a skipped curr pin never lets hard drift under it).
     */
    static int action(float localCurr, float wireCurr, float localHard, float wireHard,
            float maxFlux, boolean localVenting, boolean authVenting) {
        if (!(maxFlux > 0f) || !Float.isFinite(maxFlux) || !Float.isFinite(localCurr)
                || !Float.isFinite(wireCurr)) {
            return PIN_APPLY; // legacy: no grid to band against
        }
        boolean hardKnown = !Float.isNaN(wireHard);
        if (hardKnown && (!Float.isFinite(wireHard) || !Float.isFinite(localHard))) {
            return PIN_APPLY; // legacy: a bad hard read never widens a skip
        }
        float band = band(maxFlux);
        if (Math.abs(localCurr - wireCurr) <= band
                && (!hardKnown || Math.abs(localHard - wireHard) <= band)) {
            return PIN_SKIP_BAND;
        }
        if (localVenting && authVenting && wireCurr > localCurr) {
            return PIN_SKIP_VENT_DRAIN;
        }
        return PIN_APPLY;
    }
}
