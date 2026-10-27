package cc.starsector.client;

/**
 * Own flux / hard-flux pin DEAD-BAND law.
 *
 * <p>WHY:
 * two own-flux pins re-based the local tracker onto the wire on
 * EXACT inequality: {@code PredictReconcileCombatPlugin.maintainOwnFlux} set curr/hard flux to the
 * wire value on every fresh snapshot, and {@code FireLaneCoordinator.maintainOwnFireLock} (via
 * {@code OwnFireLockLaw.fluxAction}) at weapon quiescence. The wire value is an RTT old, so each
 * snapshot rewound the flux bar by an RTT's worth of weapon/shield flux and dissipation (100-300 ms
 * = a visible saw-tooth), and a sub-quantum straddle pinned too. The law: pin only when the local
 * tracker sits OUTSIDE a band of the wire value; inside it the local value is the better hypothesis
 * (the engine's own integration since the snapshot) and the write is skipped (metered).
 *
 * <p>Band = {@code max(FLOOR_FRAC × maxFlux, staleTicks / tps × dissipationPerSec)}. The staleness
 * term is the flux the local ship can legitimately have dissipated since the snapshot — the
 * measurable staleness (the fresh-frame inter-snapshot delta, the {@code OwnVelReconcileLaw} gate
 * idiom) times the ship's own flux dissipation stat. The one-way transit term is NOT
 * client-measurable; {@link #FLOOR_FRAC} is a DESIGN floor that carries it the same way the OwnVel
 * law's 80 u/s floor does. The flight's new gap meters ({@code predict_own_flux_gap_max_frac} /
 * {@code predict_own_flux_gap_mean_frac}, recorded pre-decision) calibrate it. A real drift (a
 * missed hit's flux, a desynced shield) exceeds the band and still pins exactly as before.
 *
 * <p>Client-side by the client-derivation rule; adds no latency (a skip, never a wait); the existing pin counters and
 * the new gap meters keep the divergence visible. Callers: {@code PredictReconcileCombatPlugin
 * .maintainOwnFlux}, {@code FireLaneCoordinator.maintainOwnFireLock}. Smoke: {@code OwnFluxPinLawSmoke}.
 */
final class OwnFluxPinLaw {

    /**
     * Design floor as a fraction of max flux (2 %). WHY: the
     * transit term is unmeasurable client-side; calibrate from {@code predict_own_flux_gap_*_frac}.
     */
    static final float FLOOR_FRAC = 0.02f;
    /**
     * Cap on the staleness term, in sim ticks (0.5 s at 60 tps — the foe-hull stale latch's own
     * threshold). An uncapped term after a 2 s feed stall made
     * the band 20 % of max and skipped a REAL divergence on the recovery frame; past this cap the gap
     * is a feed stall, never legitimate dissipation lead. Also bounds the first frame after a reset.
     */
    static final long STALE_TICKS_CAP = 30L;

    private OwnFluxPinLaw() {
    }

    /**
     * The dead band in flux units. WHY = the class javadoc
     * (the own-flux saw-tooth). Non-finite / negative inputs clamp to 0 so a bad read
     * degrades toward the legacy exact pin, never toward a wider skip. {@code tps <= 0} drops the
     * staleness term (floor only).
     */
    static float band(float maxFlux, long staleTicks, float dissipationPerSec, int tps) {
        float floor = Float.isFinite(maxFlux) && maxFlux > 0f ? FLOOR_FRAC * maxFlux : 0f;
        float stale = 0f;
        if (tps > 0 && staleTicks > 0L && Float.isFinite(dissipationPerSec) && dissipationPerSec > 0f) {
            stale = (Math.min(staleTicks, STALE_TICKS_CAP) / (float) tps) * dissipationPerSec;
        }
        return Math.max(floor, stale);
    }

    /**
     * True when the local value sits outside the band of the wire value and must be pinned.
     * WHY = the class javadoc. NaN anywhere ⇒ true (the legacy
     * pin — a bad read never silently disables the reconcile).
     */
    static boolean pinDue(float local, float wire, float band) {
        if (Float.isNaN(local) || Float.isNaN(wire) || Float.isNaN(band)) {
            return true;
        }
        return Math.abs(local - wire) > band;
    }

    /**
     * The curr+hard pair verdict: pin BOTH when either axis is outside the band (hard flux is pinned
     * iff curr is, and a hard-only gap pins both). {@code wireHard} NaN = the wire carried no hard
     * flux ⇒ curr alone decides. WHY: the brief's Guard A hard
     * rule — never let hard drift under a skipped curr pin, never pin curr without re-basing hard.
     */
    static boolean pairPinDue(float localCurr, float wireCurr, float localHard, float wireHard,
            float band) {
        return pinDue(localCurr, wireCurr, band)
                || (!Float.isNaN(wireHard) && pinDue(localHard, wireHard, band));
    }
}
