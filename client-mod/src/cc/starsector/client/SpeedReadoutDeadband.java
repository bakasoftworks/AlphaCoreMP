package cc.starsector.client;

/**
 * HUD speed-readout de-flicker.
 * The status-panel speed lines hold their last shown integer until the raw speed has
 * moved at least {@link #DEADBAND} away from it, so a signal genuinely astride an x.5 rounding edge
 * (measurement CLOSED; this is the display-only fix) cannot flip 174/175 every
 * frame. Presentation-ONLY: callers must keep feeding RAW speeds to the watch log and the
 * divergence-highlight math — never the values returned here.
 *
 * Own-file per the PredictReconcileCombatPlugin growth-anchor law (the
 * SideTotalsOverrideLane pattern): PRCP carries only the
 * delegate calls.
 *
 * Agree-snap: the two lanes' latches are independent, so a raw speed inside the
 * bistable band (both n and n+1 within DEADBAND, e.g. 173.6) can freeze a persistent 174/173
 * split out of one transient sub-unit wobble even though the raws agree. While the SERVER raw
 * agrees with the LOCAL raw within {@link #AGREE_EPS}, the SERVER line adopts the LOCAL latch;
 * a genuine divergence &gt;= AGREE_EPS still renders its own dead-banded value, and the raw delta
 * keeps feeding the highlight/log truth channel unchanged.
 */
final class SpeedReadoutDeadband {

    /** Hysteresis half-width (u/s): 0.5 is the rounding edge itself; the +0.25 margin defeats
     *  astride-x.5 jitter while a real speed change still re-rounds within one frame (steady
     *  display error bound &lt; 0.75). */
    private static final float DEADBAND = 0.75f;

    /** Raw-agreement bound (u/s) under which both lines must show the SAME integer. */
    private static final float AGREE_EPS = 0.5f;

    private float shownLocal = Float.NaN;
    private float shownServer = Float.NaN;
    private float lastLocalRaw = Float.NaN;

    /** Shown integer for the LOCAL SPEED line, dead-banded against the previous shown value. */
    float local(float raw) {
        lastLocalRaw = raw;
        shownLocal = round(raw, shownLocal);
        return shownLocal;
    }

    /** Shown integer for the SERVER SPEED line: LOCAL's latch while the raws agree (the
     *  agree-snap), otherwise dead-banded against its own previous shown value. */
    float server(float raw) {
        if (Float.isFinite(shownLocal) && Float.isFinite(lastLocalRaw)
                && Math.abs(raw - lastLocalRaw) < AGREE_EPS) {
            shownServer = shownLocal;
            return shownServer;
        }
        shownServer = round(raw, shownServer);
        return shownServer;
    }

    /** Round-reset (mirrors the speed-meter reset site): next reading adopts immediately. */
    void reset() {
        shownLocal = Float.NaN;
        shownServer = Float.NaN;
        lastLocalRaw = Float.NaN;
    }

    private static float round(float raw, float shown) {
        if (!Float.isFinite(shown) || Math.abs(raw - shown) >= DEADBAND) {
            return Math.round(raw);
        }
        return shown;
    }
}
