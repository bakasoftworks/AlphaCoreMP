package cc.starsector.client;

/**
 * Own-CR pin 2-cell DEAD-BAND law.
 *
 * <p>WHY: under a corona the HUD CR readout flipped back
 * UP one digit for &lt;= 1 s while CR decayed. The seat-1 meter read
 * {@code predict_own_cr_pin_frames=49 predict_own_cr_gap_max=0.4% predict_own_dep_gap_max=0.5}
 * = every pin was ONE u8 cell (1/255 = 0.39 %). The local engine decays CR continuously from its
 * own deploy clock; the wire CR is u8-quantized and the serve's clock sits &lt;= 0.5 s off, so when
 * the local and wire values straddle a rounding boundary they land in different cells while
 * differing by &lt; 1 cell, and the old 1-cell pin (the same-cell guard) wrote the
 * wire cell CENTER back — up to half a cell above the true value — flipping the integer readout
 * up until the next decay. A 2-cell dead-band (&gt;= 0.78 %) never fires on that quantization
 * straddle; a real drift (like the x4 CR-loss on one side) crosses 2 cells
 * within seconds and still pins.
 *
 * <p>Scope: the CR-value pin AND the
 * {@code setTimeDeployed(wireDep)} deploy-clock pin via {@link #depPinDue}:
 * the clock was pinned to the wire's exact f32 {@code timeDeployedSec} on every fresh frame
 * while its CR twin had this band, so the PPT clock rewound 0.1-0.3 s per snapshot. Client-side;
 * adds no latency; the gap meters ({@code predict_own_cr_gap_max},
 * {@code predict_own_dep_gap_max}) are unchanged so the divergence stays visible. Caller:
 * {@code PredictReconcileCombatPlugin.maintainOwnCr}. Smoke: {@code OwnCrPinLawSmoke}.
 */
final class OwnCrPinLaw {

    /**
     * The AWSD u8 CR grid: server {@code AwsdSnapshotCodec.fracU8} = round(cr*255), dequant k/255.
     * Widen TOGETHER with that codec.
     */
    static final int CR_CELLS = 255;

    /** Minimum cell gap that triggers a pin; 1 cell is the quantization straddle, never a drift. */
    static final int PIN_DEAD_BAND_CELLS = 2;

    private OwnCrPinLaw() {
    }

    /** The u8 wire cell a CR value quantizes to (mirrors the server's round(cr*255)). */
    static int cell(float cr) {
        return Math.round(cr * (float) CR_CELLS);
    }

    /** Absolute distance in u8 cells between the local CR and the wire CR. */
    static int cellGap(float localCr, float wireCr) {
        return Math.abs(cell(localCr) - cell(wireCr));
    }

    /**
     * True when the local CR has drifted at least {@link #PIN_DEAD_BAND_CELLS} cells from the wire
     * CR and should be pinned. WHY = the class javadoc
     * (the one-cell flip-up).
     */
    static boolean pinDue(float localCr, float wireCr) {
        return cellGap(localCr, wireCr) >= PIN_DEAD_BAND_CELLS;
    }

    /**
     * Deploy-clock dead band, seconds. WHY:
     * the local clock is the engine's own (it advances only while a
     * significant enemy is in range, so a local/server RANGE disagreement now steps 0.5 s at a time
     * instead of 67 ms per snapshot), so a gap under the snapshot transit is
     * legitimate lead; 0.5 s is &gt;= 2x the 200-300 ms lag the flights show. The existing
     * {@code predict_own_dep_gap_max} meter calibrates it and shows any range-step.
     */
    static final float DEP_BAND_SEC = 0.5f;

    /**
     * True when the local deploy clock sits more than {@link #DEP_BAND_SEC} from the wire's and
     * should be pinned ({@code setTimeDeployed}). NaN ⇒ true (legacy pin).
     * WHY = {@link #DEP_BAND_SEC} (the per-snapshot PPT-clock rewind).
     */
    static boolean depPinDue(float localDep, float wireDep) {
        if (Float.isNaN(localDep) || Float.isNaN(wireDep)) {
            return true;
        }
        return Math.abs(localDep - wireDep) > DEP_BAND_SEC;
    }
}
