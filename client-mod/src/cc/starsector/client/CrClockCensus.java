package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * own-ship CR/PPT clock census (the CR-HUD freeze discriminator).
 *
 * One greppable line per ~5 s of unpaused combat time: the own ship's
 * {@code getTimeDeployedForCRReduction()} (the local PPT/CR clock — accumulates ONLY while the
 * engine's own "in combat" scan passes) + {@code getCurrentCR()} + the effective
 * peak (the {@code CRPluginImpl} public-API expression). Reading at debrief:
 * {@code cr_dep} FROZEN across lines while fighting = the gate is false locally
 * (frozen-clock — replicas invisible to the enemy-weight scan); {@code cr_dep} GROWING under a
 * flat {@code cr_now} = peak simply unreached (slow-clock). Diagnostic only: capped, fail-silent,
 * zero behavior; first active frame emits (the in-watch emission check).
 *
 * <p>The census stays always-on beside the pin lane, and each
 * line also carries the last fresh AUTHORITATIVE {@code cr_wire}/{@code dep_wire} pair the
 * ownCr lane latched ({@code na} until the first CR-bearing frame), so a debrief separates
 * "local clock frozen" from "wire never carried" on one grep.</p>
 */
final class CrClockCensus {

    /** Emission cadence in unpaused combat seconds. */
    private static final float PERIOD_SECONDS = 5f;
    /** Hard line cap (~20 min of combat at the 5 s cadence); the flush line may add one more. */
    private static final int LINE_CAP = 240;

    private float combatTime;
    private float sinceLast = PERIOD_SECONDS; // pre-charged: first own-ship frame emits line 1
    private int lines;
    private float lastDep = Float.NaN;
    private boolean dead;

    /** Per active (unpaused) frame; {@code own} may be null pre-bind — no time or line burned. */
    void advance(float amount, ShipAPI own, float crWire, float depWire) {
        if (dead || lines >= LINE_CAP || own == null) {
            return;
        }
        combatTime += amount;
        sinceLast += amount;
        if (sinceLast < PERIOD_SECONDS) {
            return;
        }
        sinceLast = 0f;
        emit("tick", own, crWire, depWire);
    }

    /**
     * Stream-end/teardown backstop — one final sample past the cap so the debrief greps an end
     * state. The periodic {@code advance@N} meter flushes are
     * SKIPPED — the 5 s cadence already covers mid-round; kill-edge/teardown wheres emit here.
     */
    void logFlush(String where, ShipAPI own, float crWire, float depWire) {
        if (dead || own == null || lines > LINE_CAP) {
            return;
        }
        if (where != null && where.startsWith("advance@")) {
            return;
        }
        emit(where, own, crWire, depWire);
    }

    private void emit(String where, ShipAPI own, float crWire, float depWire) {
        try {
            float dep = own.getTimeDeployedForCRReduction();
            float cr = own.getCurrentCR();
            float peak = own.getMutableStats().getPeakCRDuration()
                    .computeEffective(own.getHullSpec().getNoCRLossTime());
            lines++;
            PilotWatchLog.info("cc-cr-census [" + where + "]: t=" + fmt(combatTime)
                    + " cr_dep=" + fmt(dep)
                    + " cr_dep_delta=" + fmt(Float.isNaN(lastDep) ? 0f : dep - lastDep)
                    + " cr_now=" + fmt(cr)
                    + " cr_peak=" + fmt(peak)
                    + " cr_wire=" + fmtOrNa(crWire)
                    + " dep_wire=" + fmtOrNa(depWire)
                    + " lines=" + lines);
            lastDep = dep;
        } catch (Throwable t) {
            dead = true; // census is diagnostic only — never break the frame
            PilotWatchLog.info("cc-cr-census DEAD: " + t);
        }
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Wire riders print {@code na} until the first fresh CR-bearing frame. */
    private static String fmtOrNa(float v) {
        return Float.isNaN(v) ? "na" : fmt(v);
    }
}
