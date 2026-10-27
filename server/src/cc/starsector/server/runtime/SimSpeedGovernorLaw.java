package cc.starsector.server.runtime;

import java.util.Arrays;

/**
 * Auto sim-speed governor — the PURE control law over the
 * announced-dilation actuator. The serve
 * loop feeds every served frame's speed-INVARIANT work cost (loop-body nanos, independent of
 * the pacer interval) and asks for a decision; the law answers a quantized target speed or
 * NaN. Sizing on work against budgets derived from the frozen frame target — never on
 * overrun/stall counters, which are measured against the very interval the governor
 * manipulates — breaks the two-controllers feedback loop by construction.
 *
 * <p>Spike immunity (the "hysteresis past GC spikes" pin, generalized to ANY 1-2
 * frame spike incl. the 388 ms stall class): step DOWN only when at least
 * {@link #DOWN_FRAC} of a FULL {@link #WINDOW_FRAMES} window exceeds {@link #DOWN_OCCUPANCY}
 * of the CURRENT speed's frame budget; step UP only when the window p95 fits inside
 * {@link #UP_OCCUPANCY} of the NEXT-higher speed's (narrower) budget. Both directions honor
 * {@link #DWELL_FRAMES} between committed decisions, and {@link #reset()} (round start / arm)
 * restarts window AND dwell — a round boundary can never flap the speed, and the first
 * W+dwell frames of every round are ungoverned BY DESIGN (the roundStartGate
 * hold serves no frames, so its wall time never reaches the window — absorbed by the
 * per-round reset). Constants are FIRST-GUESS — the contract rows pin semantics
 * (spike immunity, dwell, bounds, quantization), not exact values; a real-load watch may
 * retune.
 *
 * <p>Pure arithmetic, zero engine/socket imports — contract-testable
 * ({@code SimSpeedGovernorLawContractRows}). Serve-thread only, like every pacer local.
 */
public final class SimSpeedGovernorLaw {

    /** Ring of the last W served frames' work nanos; decisions need a FULL window. */
    public static final int WINDOW_FRAMES = 120;
    /** Step DOWN when at least this fraction of a full window is over-budget. */
    public static final double DOWN_FRAC = 0.5;
    /** "Over-budget" for the down test = work &gt; this fraction of the CURRENT budget. */
    public static final double DOWN_OCCUPANCY = 0.9;
    /** Step UP when the window p95 fits inside this fraction of the NEXT speed's budget. */
    public static final double UP_OCCUPANCY = 0.8;
    /** Minimum fed frames between committed decisions (and after any {@link #reset()}). */
    public static final int DWELL_FRAMES = 600;
    /** Speed grid step — coarse on purpose (permille stays a 250-multiple; changes are loud
     * events, not trims). */
    public static final double STEP = 0.25;

    private final double floor;
    private final double ceiling;
    private final long frameTargetNanos;

    private final long[] window = new long[WINDOW_FRAMES];
    private int windowFilled;
    private int windowNext;
    private int framesSinceDecision;
    private int windowOverAtDecision;

    /**
     * True iff both bounds are finite {@link #STEP}-grid multiples with
     * {@code floor <= ceiling}, inside {@code [min, max]} — the knob-resolve validation
     * (an invalid pair makes the CALLER fall soft to defaults with a loud receipt).
     */
    public static boolean validBounds(double floor, double ceiling, double min, double max) {
        return onGrid(floor) && onGrid(ceiling)
                && floor <= ceiling && floor >= min && ceiling <= max;
    }

    private static boolean onGrid(double v) {
        if (!Double.isFinite(v)) {
            return false;
        }
        double steps = v / STEP;
        return Math.abs(steps - Math.round(steps)) < 1e-6;
    }

    /**
     * @throws IllegalArgumentException on non-grid/inverted bounds or a non-positive frame
     *         target — the knob-resolve site validates via {@link #validBounds} and defaults
     *         LOUD before construction; reaching this throw is a programming error.
     */
    public SimSpeedGovernorLaw(double floor, double ceiling, long frameTargetNanos) {
        if (!validBounds(floor, ceiling, STEP, Double.MAX_VALUE)) {
            throw new IllegalArgumentException("governor bounds must be " + STEP
                    + "-grid multiples with floor <= ceiling; got floor=" + floor
                    + " ceiling=" + ceiling);
        }
        if (frameTargetNanos <= 0L) {
            throw new IllegalArgumentException(
                    "frameTargetNanos must be > 0; got " + frameTargetNanos);
        }
        this.floor = floor;
        this.ceiling = ceiling;
        this.frameTargetNanos = frameTargetNanos;
    }

    /** Round start / arm: forget the window AND restart dwell — the first decision after a
     * reset needs a full fresh window and a full dwell. */
    public void reset() {
        windowFilled = 0;
        windowNext = 0;
        framesSinceDecision = 0;
    }

    /** One served frame's speed-invariant WORK cost (loop-body nanos; pacer sleep excluded). */
    public void feed(long frameWorkNanos) {
        window[windowNext] = frameWorkNanos;
        windowNext = (windowNext + 1) % WINDOW_FRAMES;
        if (windowFilled < WINDOW_FRAMES) {
            windowFilled++;
        }
        if (framesSinceDecision < Integer.MAX_VALUE) {
            framesSinceDecision++;
        }
    }

    /** Nearest {@link #STEP}-grid point inside [floor, ceiling] (arm-snap:
     * manual speeds may be off-grid; auto only ever steps between grid points). */
    public double snapToGrid(double speed) {
        double snapped = Math.round(speed / STEP) * STEP;
        if (snapped < floor) {
            return floor;
        }
        if (snapped > ceiling) {
            return ceiling;
        }
        return snapped;
    }

    /**
     * Ask for a decision at the CURRENT speed. Returns the quantized target speed, or NaN for
     * no change. A non-NaN return is a COMMITTED decision — it restarts the dwell clock and
     * the caller MUST apply it (the verb's 4-statement actuator body).
     */
    public double decide(double currentSpeed) {
        if (windowFilled < WINDOW_FRAMES || framesSinceDecision < DWELL_FRAMES) {
            return Double.NaN;
        }
        double current = snapToGrid(currentSpeed);
        // Step down: sustained occupancy against the CURRENT budget. Work is speed-invariant
        // and the budget widens as speed drops, so a step down genuinely relieves the
        // condition — no oscillation channel back through the pacer.
        long currentBudgetNanos = (long) (frameTargetNanos / current);
        int over = 0;
        for (long work : window) {
            if (work > DOWN_OCCUPANCY * currentBudgetNanos) {
                over++;
            }
        }
        if (over >= (int) Math.ceil(DOWN_FRAC * WINDOW_FRAMES)) {
            double down = snapToGrid(current - STEP);
            if (down < current) {
                framesSinceDecision = 0;
                windowOverAtDecision = over;
                return down;
            }
            return Double.NaN; // already at floor: nothing left to shed
        }
        // Step up only from a calm window: p95 must fit the NEXT speed's narrower budget.
        double up = snapToGrid(current + STEP);
        if (up > current) {
            long upBudgetNanos = (long) (frameTargetNanos / up);
            if (windowP95() <= (long) (UP_OCCUPANCY * upBudgetNanos)) {
                framesSinceDecision = 0;
                windowOverAtDecision = over;
                return up;
            }
        }
        return Double.NaN;
    }

    /** Over-budget frame count at the LAST committed decision (the log's window_over= token). */
    public int windowOverAtDecision() {
        return windowOverAtDecision;
    }

    private long windowP95() {
        long[] sorted = window.clone(); // only called on a full window
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(0.95 * WINDOW_FRAMES) - 1];
    }
}
