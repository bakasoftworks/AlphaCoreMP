package cc.starsector.client;

/**
 * Whole-grid armor FOLD law: a 2-cell dead-band plus a local-bite hold.
 *
 *
 * <p>WHY: the old fold had two defects.
 * {@link ArmorReconcileLane} folded the whole armor grid to the wire's u8 {@code armorFrac()} on
 * every fresh snapshot with a ONE-cell dead band ({@code localU8 == wireU8}) and no bite hold.
 * (1) Under 100-300 ms lag an RTT-old snapshot heals a fresh LOCAL hit UPWARD (the cells the
 * local engine just bit are re-filled from the stale wire), then the next snapshot bites them
 * again = the owner-visible armor flicker. (2) A local/wire pair less than one cell apart but
 * straddling a rounding edge lands in different cells, and the fold wrote the wire cell CENTER
 * back = the exact class {@link OwnCrPinLaw} fixed for CR. Model =
 * {@link ArmorCellLane} (per-cell bite window {@link ArmorCellLane#REPLAY_WINDOW_SEC} on the
 * SIM-seconds clock; a recent local bite with the wire ABOVE local = "snapshot predates the local
 * hit — prediction stands"). A fold DOWN always passes (truth-driven drops never wait), and the
 * law is a SKIP, never a wait: no presentation is postponed (the never-add-latency rule).
 *
 * <p>Client-side; the caller keeps every gap meter computed BEFORE the skip so the
 * divergence stays visible. Caller: {@code ArmorReconcileLane.maintain}. Smoke:
 * {@code ArmorFoldLawSmoke}.
 */
final class ArmorFoldLaw {

    /** Minimum u8 cell gap that folds; 1 cell is the quantization straddle, never a drift
     *  (the {@link OwnCrPinLaw#PIN_DEAD_BAND_CELLS} idiom). */
    static final int FOLD_DEAD_BAND_CELLS = 2;

    /** The local-bite hold, SIM seconds = {@link ArmorCellLane#REPLAY_WINDOW_SEC} (2.0, the
     *  per-cell lane's replay window — same clock law). Compile-time
     *  constant, inlined. */
    static final double BITE_HOLD_SEC = ArmorCellLane.REPLAY_WINDOW_SEC;

    /** Verdict: fold to the wire. */
    static final int FOLD_APPLY = 0;
    /** Verdict: local and wire are inside the 2-cell dead-band (quantization, not drift). */
    static final int FOLD_SKIP_BAND = 1;
    /** Verdict: the wire would heal a fresh local bite upward (the snapshot predates the hit). */
    static final int FOLD_SKIP_BITE_HOLD = 2;

    private ArmorFoldLaw() {
    }

    /**
     * The fold verdict for a local/wire u8 pair. WHY = the
     * class javadoc (1-cell straddle + stale upward heal of a fresh bite).
     */
    static int action(int localU8, int wireU8, boolean biteRecent) {
        if (Math.abs(localU8 - wireU8) < FOLD_DEAD_BAND_CELLS) {
            return FOLD_SKIP_BAND;
        }
        if (wireU8 > localU8 && biteRecent) {
            return FOLD_SKIP_BITE_HOLD;
        }
        return FOLD_APPLY;
    }

    /**
     * True when a local bite at {@code biteAtSec} is inside the hold window at {@code nowSec}
     * (both SIM seconds). {@code biteAtSec <= 0} = never bitten; NaN or negative dt = false.
     * WHY = the class javadoc.
     */
    static boolean biteRecent(double biteAtSec, double nowSec) {
        if (!(biteAtSec > 0d)) {
            return false;
        }
        double dt = nowSec - biteAtSec;
        return dt >= 0d && dt <= BITE_HOLD_SEC; // NaN fails both comparisons
    }
}
