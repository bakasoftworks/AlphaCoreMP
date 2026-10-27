package cc.starsector.client;

/**
 * Pure law for the foe-hull-reconcile stale-feed SUSPEND latch clock.
 *
 *
 * <p>WHY: the latch counted RENDER frames (30) — at a measured ~460 fps that is ~65 ms ≈ a single
 * 4-tick snapshot gap, so ordinary snapshot jitter latched SUSPEND 19× in one match (seat 2) and
 * every RESUME cleared the replay deques, the known
 * rebound risk. The clock is now SIM SECONDS since the last fresh
 * snapshot, converted to whole ticks at the negotiated rate: 30 ticks = 0.5 s at 60 tps = what the
 * frame count meant at 60 fps, frame-rate-immune and pause-immune (a paused sim adds nothing, like
 * the replay-expiry clock).
 */
final class FoeHullStaleLatchLaw {

    /** Whole sim ticks without a fresh snapshot the feed may run before the write lane suspends
     *  ({@code >}: the 31st tick suspends, mirroring the old {@code frames > 30}). */
    static final int SUSPEND_TICKS = 30;

    private FoeHullStaleLatchLaw() {
    }

    /** The stale clock in whole sim ticks; NaN / negative seconds or a non-positive rate read as 0. */
    static int staleTicks(double staleSimSec, int ticksPerSecond) {
        if (!(staleSimSec > 0d) || ticksPerSecond <= 0) {
            return 0;
        }
        return (int) Math.floor(staleSimSec * ticksPerSecond);
    }

    /** True when the stale clock has crossed the threshold: strictly more than {@link #SUSPEND_TICKS}. */
    static boolean suspendDue(double staleSimSec, int ticksPerSecond) {
        return staleTicks(staleSimSec, ticksPerSecond) > SUSPEND_TICKS;
    }
}
