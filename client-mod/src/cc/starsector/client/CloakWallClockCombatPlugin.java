package cc.starsector.client;

import java.util.List;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/**
 * Client half: the OWN hull's
 * phase-cloak charge fraction ticks WALL time while the ship is time-dilated — IN half only
 * (OUT engine-native both sides, see {@code advance}) — the coherent
 * twin of the server's {@code PilotedCloakWallClock} (server-only would make
 * press-at-client-ready/server-not-ready a REAL desync; both sides repace, so predicted READY
 * equals authoritative READY).
 *
 * <p>WHY: locally the player's phase dilation runs per-ship {@code timeMult 3x} (the faithful
 * relative-physics lane, deliberately preserved by {@code ClockServoLane}) while the
 * clock servo scrubs vanilla-SP's player-only GLOBAL 1/3 compensation as a correctness law —
 * so without this lane the local IN/OUT windows compress ~1.87-3x vs wall clock exactly like
 * the server's, as measured. Vanilla-player cycle FEEL (1.5 s press→re-phased) is
 * a ship-gate; AI/foe hulls stay engine-native BY DESIGN (vanilla never compensates
 * AI phase ships, and foe replicas render server truth via the foeCloak lane).
 *
 * <p>MECHANISM (Candidate A — decided at landing: client-mod has NO raw-reflection
 * precedent, and the public {@code forceState} fraction-rewrite idiom is proven twice, in
 * {@code seedFoeCloak} and {@code PhaseDivePresentLane}): per frame compare the cloak's
 * progress-into-state (IN: {@code effectLevel}; OUT: {@code 1-effectLevel}) against the last
 * visit; when the state is UNCHANGED, the hull is genuinely dilated (per-ship
 * {@code timeMult > 1}) and progress outran {@code amount/stateDur} (+eps), clamp via
 * {@code forceState(state, prev + amount/stateDur)} — callback-free (threshold
 * callbacks fire only in the advance cascade), same-state so the own-cloak agree meters
 * ({@code isOn()} compare) stay GREEN. ACTIVE is inert (toggle active dur = 1e6) and
 * COOLDOWN/IDLE already tick wall-true, so both are skipped by construction. State changes
 * resync without writing (one-frame slip accepted, mirrors the server). An implausible jump
 * (&gt; {@link #CAP_MULT}x the wall fraction — an external writer such as the emergency-dive
 * playout, a reconcile snap, or a hitch) resyncs without writing rather than fighting it.
 *
 * <p>Knob {@code cc.predict.cloakWall} = {@code off|meter|apply}, default {@code apply}
 * (kill-switch + A/B lever; {@code meter} counts would-clamp frames only); invalid values THROW
 * at class load. Fail-soft with a capped error log. NEW-LANE LAW collaborator: registered as
 * its own plugin beside {@code PredictReconcileCombatPlugin} in the {@code cc_predict} mission
 * (both branches) — zero PRCP delta. Render-thread only.
 */
public class CloakWallClockCombatPlugin extends BaseEveryFrameCombatPlugin {

    /** Resolved {@code cc.predict.cloakWall} mode. */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.cloakWall", System.getProperty("cc.predict.cloakWall", "apply"));
    private static final boolean METER = !"off".equals(MODE);
    private static final boolean APPLY = "apply".equals(MODE);

    /** Clamp only when progress outran the wall fraction by this much (float-noise guard). */
    private static final float EPS_FRAC = 0.25f;
    /** Resync-don't-fight ceiling: a jump beyond this many wall fractions is an external writer. */
    private static final float CAP_MULT = 6f;
    /** Only clamp genuine dilation — below this per-ship timeMult the natural pace is wall pace. */
    private static final float TIME_MULT_GATE = 1.05f;
    private static final int ERROR_LOG_MAX = 5;

    private CombatEngineAPI engine;
    private ShipAPI trackedShip;
    private ShipSystemAPI.SystemState prevState;
    private float prevProgress = Float.NaN;
    private long clamps;
    private long wouldClamps;
    private boolean armedLogged;
    private int errors;

    // Cycle wall clock: System.nanoTime edges of the own-cloak state
    // machine, logged per COMPLETED window so the felt timing has numbers beside it (vanilla
    // targets press->phased ~500 ms, uncloak->ready ~1000 ms; the dilated BEFORE ~270/~770 ms).
    // Wall time is measured OUTSIDE the engine's credit stream on purpose — immune to any
    // servo/pacer accounting defect, which is exactly what this instrument adjudicates.
    private ShipAPI cycleShip;
    private ShipSystemAPI.SystemState cycleState;
    private long cycleInStartNanos;
    private long cycleOutStartNanos;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!METER || engine == null || amount <= 0f) {
            return;
        }
        try {
            ShipAPI own = engine.getPlayerShip();
            if (own == null || !own.isAlive()) {
                cycleClock(null, null);
                resync(null, null, Float.NaN);
                return;
            }
            ShipSystemAPI cloak = own.getPhaseCloak();
            if (cloak == null) {
                cycleClock(own, null);
                resync(own, null, Float.NaN);
                return;
            }
            ShipSystemAPI.SystemState state = cloak.getState();
            cycleClock(own, state);
            float dur;
            float progress;
            if (state == ShipSystemAPI.SystemState.IN) {
                dur = cloak.getChargeUpDur();
                progress = cloak.getEffectLevel();
            } else {
                // OUT is engine-native on BOTH sides (the server's
                // PilotedCloakWallClock.wallPaced twin) — wall-pacing the fade gave the pilot
                // ~1.0 s of weapon time inside it (D-PLASMAPHASE 10/18-tick bursts); native OUT
                // = vanilla's 0.5 s ship-time, the AI/world-relative feel. ACTIVE/COOLDOWN/IDLE
                // were already skipped by construction.
                resync(own, state, Float.NaN);
                return;
            }
            boolean freshWindow = own != trackedShip || state != prevState
                    || Float.isNaN(prevProgress) || dur <= 0f;
            if (freshWindow) {
                resync(own, state, progress);
                return;
            }
            float delta = progress - prevProgress;
            float wallFrac = amount / dur;
            float timeMult = own.getMutableStats().getTimeMult().getModifiedValue();
            boolean dilationSignature = delta > wallFrac * (1f + EPS_FRAC)
                    && delta <= wallFrac * CAP_MULT
                    && timeMult > TIME_MULT_GATE;
            // A diving own hull's IN is the
            // dive playout's ship-time clock — never wall-clamped (identity read, no PRCP line).
            if (!dilationSignature || PhaseDivePresentLane.diveSignalHolds(engine, own)) {
                resync(own, state, progress);
                return;
            }
            float clamped = prevProgress + wallFrac;
            if (APPLY) {
                cloak.forceState(state, clamped);
                clamps++;
                resync(own, state, clamped);
            } else {
                wouldClamps++;
                resync(own, state, progress);
            }
            if (!armedLogged) {
                armedLogged = true;
                PilotWatchLog.info("cloak-wall-clock " + (APPLY ? "CLAMPING" : "meter") + ": own"
                        + " cloak " + state + " outran wall (delta=" + delta + " wallFrac="
                        + wallFrac + " tm=" + timeMult + ") -> wall-paced");
            }
        } catch (Throwable t) {
            errors++;
            if (errors <= ERROR_LOG_MAX) {
                PilotWatchLog.error("cloak-wall-clock frame FAILED (fail-soft, lane retries;"
                        + " errors=" + errors + "): " + t, t);
            }
        }
    }

    private void resync(ShipAPI ship, ShipSystemAPI.SystemState state, float progress) {
        trackedShip = ship;
        prevState = state;
        prevProgress = progress;
    }

    /**
     * Fold one frame's raw own-cloak state into the cycle wall clock (see the field
     * block). Logs one {@code cloak-cycle:} line per COMPLETED window: IN&rarr;ACTIVE = press&rarr;phased,
     * COOLDOWN&rarr;IDLE = uncloak&rarr;ready (measured from the OUT entry). Aborted windows (re-cloak
     * during OUT, uncloak during IN, hull swap) clear their edge silently — only full windows grade.
     */
    private void cycleClock(ShipAPI ship, ShipSystemAPI.SystemState state) {
        if (ship != cycleShip) {
            cycleShip = ship;
            cycleState = state;
            cycleInStartNanos = 0L;
            cycleOutStartNanos = 0L;
            return;
        }
        if (state == cycleState) {
            return;
        }
        long now = System.nanoTime();
        if (state == ShipSystemAPI.SystemState.IN) {
            // Full window only: an OUT->IN re-cloak mirror starts at partial eff — a short IN
            // there is vanilla, not evidence; it must not grade.
            cycleInStartNanos = cycleState == ShipSystemAPI.SystemState.IDLE ? now : 0L;
            cycleOutStartNanos = 0L;
        } else if (state == ShipSystemAPI.SystemState.ACTIVE) {
            if (cycleState == ShipSystemAPI.SystemState.IN && cycleInStartNanos != 0L) {
                PilotWatchLog.info("cloak-cycle: press->phased " + ((now - cycleInStartNanos)
                        / 1_000_000L) + " ms wall (vanilla~500 dilated~270; clamps=" + clamps
                        + " would=" + wouldClamps + ")");
            }
            cycleInStartNanos = 0L;
        } else if (state == ShipSystemAPI.SystemState.OUT) {
            // Full window only: an IN->OUT abort mirror uncloaks from partial eff (same law).
            cycleOutStartNanos = cycleState == ShipSystemAPI.SystemState.ACTIVE ? now : 0L;
            cycleInStartNanos = 0L;
        } else if (state == ShipSystemAPI.SystemState.IDLE) {
            if (cycleState == ShipSystemAPI.SystemState.COOLDOWN && cycleOutStartNanos != 0L) {
                PilotWatchLog.info("cloak-cycle: uncloak->ready " + ((now - cycleOutStartNanos)
                        / 1_000_000L) + " ms wall (OUT engine-native: ~275+cd; clamps=" + clamps
                        + " would=" + wouldClamps + ")");
            }
            cycleOutStartNanos = 0L;
        }
        cycleState = state;
    }
}
