package cc.starsector.client;

/**
 * D-RESPAWNFACING instrument: pairs the OWN hull's
 * local facing against the wire (server) facing across every own fly-in hold, so the next
 * "respawn flew in facing sideways" mark (flight #9 mark #3, tick 60693 — UNMEASURED then) reads
 * off a real gap instead of a guess. Own facing is never wire-reconciled (position-only,
 * {@code OwnVelReconcileLaw}), so a divergence accrued during the burn persists after the hold.
 *
 * <p>Rows ({@code predict own-flyin-facing …}, sidelog): {@code START} at the hold's rising edge,
 * one every {@value #PERIOD_TICKS} sim ticks while held (fresh frames only), {@code END} at the
 * falling edge with the running max gap, and one {@code POST} {@value #POST_TICKS} ticks after
 * release (does the gap persist or converge?). Bounded: {@value #MAX_ROWS} rows per process, then
 * silent. Gap = shortest arc in degrees. Zero cost outside a hold beyond one boolean compare.
 * Own file per NEW-LANE-LAW (PRCP is past its WarnNb). The fix candidate (pin own facing to the
 * wire during the hold) waits for a marked instance that pairs a real gap here.
 */
final class FlyInFacingRowLane {
    static final int PERIOD_TICKS = 60;
    static final int POST_TICKS = 60;
    static final int MAX_ROWS = 400;

    private boolean held;
    private long startTick;
    private long lastRowTick;
    private long endTick = Long.MIN_VALUE;
    private float maxGap;
    private int frames;
    private int rows;

    /**
     * One call per advance frame that composed the fly-in read ({@code hold}); {@code fresh} =
     * an authoritative frame arrived this frame (the wire facing is current).
     */
    void sample(float ownFacing, float wireFacing, long simTick, boolean hold, boolean fresh) {
        float gap = gapDeg(ownFacing, wireFacing);
        if (hold && !held) {
            held = true;
            startTick = simTick;
            lastRowTick = simTick;
            maxGap = gap;
            frames = 0;
            endTick = Long.MIN_VALUE;
            row("START tick=" + simTick + " own=" + f1(ownFacing) + " wire=" + f1(wireFacing)
                    + " gap=" + f1(gap));
            return;
        }
        if (held) {
            if (fresh) {
                frames++;
                if (gap > maxGap) {
                    maxGap = gap;
                }
            }
            if (!hold) {
                held = false;
                endTick = simTick;
                row("END tick=" + simTick + " dur_ticks=" + (simTick - startTick) + " own="
                        + f1(ownFacing) + " wire=" + f1(wireFacing) + " gap=" + f1(gap)
                        + " max_gap=" + f1(maxGap) + " fresh_frames=" + frames);
            } else if (fresh && simTick - lastRowTick >= PERIOD_TICKS) {
                lastRowTick = simTick;
                row("HOLD tick=" + simTick + " own=" + f1(ownFacing) + " wire=" + f1(wireFacing)
                        + " gap=" + f1(gap) + " max_gap=" + f1(maxGap));
            }
            return;
        }
        if (endTick != Long.MIN_VALUE && simTick - endTick >= POST_TICKS) {
            endTick = Long.MIN_VALUE;
            row("POST tick=" + simTick + " own=" + f1(ownFacing) + " wire=" + f1(wireFacing)
                    + " gap=" + f1(gap));
        }
    }

    static float gapDeg(float a, float b) {
        return Math.abs(((b - a) % 360f + 540f) % 360f - 180f);
    }

    private void row(String text) {
        if (rows >= MAX_ROWS) {
            return;
        }
        rows++;
        PilotWatchLog.info("predict own-flyin-facing " + text
                + (rows == MAX_ROWS ? " (row cap reached; silent from here)" : ""));
    }

    private static String f1(float v) {
        if (Float.isNaN(v)) {
            return "NaN"; // Rider: Math.round(NaN) == 0 would print an honest gap as "0.0"
        }
        return String.valueOf(Math.round(v * 10f) / 10f);
    }
}
