package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.campaign.skills.NeuralLinkScript;
import com.fs.starfarer.api.util.FaderUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * The phase-anchor emergency-dive presentation lane, knob
 * {@code cc.predict.phaseDive} = {@code off|meter|apply} (default {@code apply}; a presentation-class gap:
 * the dive never played live).
 *
 * <p>The server's per-frame rising-edge read of a phase-anchor hull's {@code emergencyDive}
 * fans one {@code DIVE} ctrl frame at dive start ({@code PhaseDiveServeProbe}, F4c); the local
 * vanilla {@code PhaseAnchorScript} is deliberately fenced on replicas and server-decided kills
 * (F3b — never re-open), so this lane replays the vanilla playout on the local body instead:
 * the vanilla {@code PhaseAnchor$PhaseAnchorScript.advance} recipe verbatim — one-shot floaty
 * {@code "Emergency dive!"} + {@code system_phase_cloak_activate} if unphased at enroll, then
 * per-frame {@code getPhaseCloak().forceState(IN, max(alpha, progress))} with
 * {@code progress += amount * chargeUpDur} ({@code amount} = the SHIP's frame, world x the ship's
 * timeMult — vanilla's script advances on ship time), and at {@code progress >= 1} the
 * {@code phase_anchor_vanish} sound + {@link FaderUtil} fade + jitter, ending in the vanilla
 * off-map park {@code (0, -1e6)}. The sim-side verbs (setRetreating, blockCommand,
 * hullDamageTakenMult) stay server-side — this lane is presentation only.
 *
 * <p>The parked body is NOT removed here: the paired {@code EDSP DEPARTED} despawn takes it
 * via {@link #finishDeparture} from the kill lanes (corpse-free — the hull left alive; no
 * death FX, no fracture mint), keeping the killOwn/killRemote bookkeeping (killed latches,
 * ledger close, respawn flow) on its proven path. A parked body no despawn ever claims is
 * fail-soft reaped after {@link #PARK_REAP_SECONDS}.
 *
 * <p>{@code meter} = latch+log only (no visuals, kill lanes keep the verbatim path);
 * {@code off} = ignore. Invalid values THROW at class load. NEW-LANE LAW collaborator:
 * PRCP keeps touchpoints only — the drain/enroll relay, an
 * advance relay, the two departure takes, a reset relay. Render-thread only.
 */
final class PhaseDivePresentLane {

    /** Resolved {@code cc.predict.phaseDive} mode — the PRCP banner line reads this. */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.phaseDive", System.getProperty("cc.predict.phaseDive", "apply"));
    private static final boolean METER = !"off".equals(MODE);
    private static final boolean APPLY = "apply".equals(MODE);

    /** Fail-soft reap window for a parked (fully dived) body whose despawn never arrives. */
    private static final float PARK_REAP_SECONDS = 5f;
    /** Floaty size fallback when {@code NeuralLinkScript.getFloatySize} throws (no campaign). */
    private static final float FLOATY_SIZE_FALLBACK = 24f;
    private static final int LOG_MAX = 8;
    /** {@code phase-dive guard:} sample rows per dive (a dive's playout is ~1-2 s of fresh own poses). */
    private static final int GUARD_ROWS_MAX = 48;
    /** The vanilla off-map park y; pose writes hold a parked body. */
    private static final float PARK_Y = -1000000f;
    /**
     * The {@code engine.getCustomData()}
     * key publishing {@link #divingShips} — {@code CloakWallClockCombatPlugin}'s dive read with no
     * PRCP line ({@link #diveSignalHolds}).
     */
    static final String DIVING_SHIPS_KEY = "cc.phaseDive.diving";

    /** One diving body mid playout — the vanilla script's per-ship state, presentation half. */
    private static final class Diving {
        final ShipAPI ship;
        final java.awt.Color color;
        final boolean own;
        float progress;
        final FaderUtil fader = new FaderUtil(1f, 1f);
        boolean parked;
        float parkElapsed;
        /**
         * The park row's sums ({@link #parkRow}) —
         * playout frames (not parked) and their world / ship seconds, the last ship timeMult read,
         * frames spent parked, and on-map pose writes held off the parked body.
         */
        int frames;
        float worldSec;
        float shipSec;
        float timeMultLast = 1f;
        int parkFrames;
        int onMapPosesAfterPark;
        /**
         * The own-cloak force guard's witness ({@link #guardOwnCloakDisagree}): guarded disagree
         * samples, the streak the force law WOULD hold without the guard (its max; restarted where
         * that law would have forced), the forces it would have made, and the sample rows printed.
         */
        int guardSamples;
        int guardStreak;
        int guardStreakMax;
        int guardOverPatience;
        int guardRows;

        Diving(ShipAPI ship, java.awt.Color color, boolean own) {
            this.ship = ship;
            this.color = color;
            this.own = own;
        }
    }

    /** Bodies mid playout, by wire entity id (enrolled at the DIVE drain, taken at despawn). */
    private final java.util.HashMap<Long, Diving> divingByEntity =
            new java.util.HashMap<Long, Diving>();
    /** meter: dive-latched wire ids (log-only twin of the apply enrollment). */
    private final java.util.HashSet<Long> meterLatched = new java.util.HashSet<Long>();
    /**
     * The enrolled dive bodies (playing out
     * OR parked) as an IDENTITY set, published under {@link #DIVING_SHIPS_KEY} by every apply-mode
     * {@link #beginDive}; added at enroll, removed on every exit (despawn take, round reset, and the
     * {@link #advance} iterator exits: null ship, reap, throw).
     */
    private final java.util.Set<ShipAPI> divingShips = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<ShipAPI, Boolean>());
    private int logLines;

    /**
     * Enroll one {@code DIVE} start on its local body: first-frame FX now (the vanilla floaty +
     * cloak-activate sound if unphased), the per-frame playout in {@link #advance}. Under
     * {@code meter} latches+logs only; under {@code off} (or a missing/dead body) declines —
     * the despawn then takes the verbatim pre-F4 path.
     */
    boolean beginDive(long entityId, ShipAPI ship, boolean own, CombatEngineAPI engine) {
        if (!METER) {
            return false;
        }
        if (!APPLY) {
            meterLatched.add(Long.valueOf(entityId));
            log("phase-dive lane: meter latch entity=" + entityId + (own ? " (OWN)" : ""));
            return false;
        }
        // A DIVE ctrl frame means the server fired the battle's emergency dive: mirror vanilla's
        // permanent one-dive-per-battle plant on the LOCAL engine, so the
        // client-side PhaseAnchorScript intercept is fenced from the first dive on — even before
        // any death presentation plants the same key (the story lives on
        // AuthoritativePresenter.PHASE_ANCHOR_CAN_DIVE_KEY). Planted even when the local body is
        // missing/dead below — the dive still happened. Fail-soft: the playout must still run.
        try {
            java.util.Map<String, Object> customData =
                    engine != null ? engine.getCustomData() : null;
            if (customData != null) {
                customData.put(AuthoritativePresenter.PHASE_ANCHOR_CAN_DIVE_KEY, Boolean.TRUE);
                customData.put(DIVING_SHIPS_KEY, divingShips); // the wall-clock dive read
            }
        } catch (Throwable fenceOnly) {
            // the fence here is hardening — presentation never dies on it
        }
        if (ship == null || engine == null || !ship.isAlive()) {
            log("phase-dive lane: DECLINED entity=" + entityId + (own ? " (OWN)" : "")
                    + " (no live local body) -> despawn keeps the verbatim path");
            return false;
        }
        Long key = Long.valueOf(entityId);
        if (divingByEntity.containsKey(key)) {
            return true; // duplicate start frame — already animating
        }
        java.awt.Color c;
        try {
            c = ship.getPhaseCloak().getSpecAPI().getEffectColor2();
            c = Misc.setAlpha(c, 255);
            c = Misc.interpolateColor(c, java.awt.Color.white, 0.5f);
        } catch (Throwable noCloakColor) {
            c = java.awt.Color.white;
        }
        try {
            float floatySize = FLOATY_SIZE_FALLBACK;
            try {
                floatySize = NeuralLinkScript.getFloatySize(ship);
            } catch (Throwable noCampaign) {
                // registry/mission shape without a campaign — the vanilla size helper is a
                // nicety, not load-bearing (the live-throw suspect stays fenced out here)
            }
            float timeMult = ship.getMutableStats().getTimeMult().getModifiedValue();
            engine.addFloatingTextAlways(ship.getLocation(), "Emergency dive!", floatySize, c,
                    ship, 16f * timeMult, 3.2f / timeMult, 1f / timeMult, 0f, 0f, 1f);
            if (!ship.isPhased()) {
                Global.getSoundPlayer().playSound("system_phase_cloak_activate", 1f, 1f,
                        ship.getLocation(), ship.getVelocity());
            }
        } catch (Throwable fxOnly) {
            // first-frame FX is cosmetic — the per-frame playout still runs
        }
        divingByEntity.put(key, new Diving(ship, c, own));
        divingShips.add(ship);
        log("phase-dive lane: ACTIVE entity=" + entityId + (own ? " (OWN)" : "") + " dive playout begun");
        return true;
    }

    /**
     * Tick the playouts — the vanilla {@code PhaseAnchorScript.advance} emergency-dive block
     * verbatim, minus the sim verbs (class doc). Fail-soft per entry: any throw removes the
     * body immediately (no body may outlive its animation).
     */
    void advance(CombatEngineAPI engine, float amount) {
        if (divingByEntity.isEmpty() || engine == null) {
            return;
        }
        for (java.util.Iterator<java.util.Map.Entry<Long, Diving>> it =
                divingByEntity.entrySet().iterator(); it.hasNext();) {
            Diving d = it.next().getValue();
            try {
                if (d.ship == null) {
                    drop(it, d);
                    continue;
                }
                if (d.parked) {
                    d.parkFrames++;
                    d.parkElapsed += amount; // WORLD time on purpose: the reap is a fail-soft timer
                    if (d.parkElapsed >= PARK_REAP_SECONDS) {
                        log("phase-dive lane: parked body reaped (no despawn arrived in "
                                + PARK_REAP_SECONDS + "s)");
                        engine.removeEntity(d.ship);
                        drop(it, d);
                    }
                    continue;
                }
                // The dive clock runs on SHIP time —
                // vanilla's PhaseAnchorScript integrates the ship's own advance amount (world x the
                // phase timeMult, ~3 at full cloak) while this relay's amount is the plugin's WORLD
                // frame (measured: kit progress ~0.52 when the server's despawn landed).
                float mult = d.ship.getMutableStats().getTimeMult().getModifiedValue();
                float shipAmount = Float.isFinite(mult) && mult > 0f ? amount * mult : amount;
                d.frames++;
                d.worldSec += amount;
                d.shipSec += shipAmount;
                d.timeMultLast = mult;
                d.fader.advance(shipAmount);
                ShipSystemAPI cloak = d.ship.getPhaseCloak();
                float chargeUpDur = cloak != null ? cloak.getChargeUpDur() : 0.5f;
                d.progress += shipAmount * chargeUpDur;
                if (cloak != null) {
                    float curr = d.ship.getExtraAlphaMult();
                    cloak.forceState(ShipSystemAPI.SystemState.IN,
                            Math.min(1f, Math.max(curr, d.progress)));
                }
                if (d.progress >= 1f) {
                    if (d.fader.isIdle() && Global.getSoundPlayer() != null) { // null = game-free smoke
                        Global.getSoundPlayer().playSound("phase_anchor_vanish", 1f, 1f,
                                d.ship.getLocation(), d.ship.getVelocity());
                    }
                    d.fader.fadeOut();
                    d.fader.advance(shipAmount);
                    float b = d.fader.getBrightness();
                    d.ship.setExtraAlphaMult2(b);
                    float r = d.ship.getCollisionRadius() * 5f;
                    d.ship.setJitter(d, d.color, b, 20, r * (1f - b));
                    if (d.fader.isFadedOut()) {
                        d.ship.getLocation().set(0f, PARK_Y); // the vanilla off-map park
                        d.parked = true;
                    }
                }
            } catch (Throwable ignore) {
                try {
                    if (d.ship != null) {
                        engine.removeEntity(d.ship);
                    }
                } catch (Throwable ignore2) {
                    // the engine refused the remove too — drop the entry, never break the frame
                }
                drop(it, d);
            }
        }
    }

    /**
     * The one iterator exit of
     * {@link #advance} (null ship, reap, throw) — the entry leaves the map AND the
     * {@link #divingShips} signal together, so no exit leaves the wall-clock plugin reading a
     * stale dive.
     */
    private void drop(java.util.Iterator<?> it, Diving d) {
        it.remove();
        divingShips.remove(d.ship);
    }

    /** The enrolled {@link Diving} whose body IS {@code ship} (identity), else null; an empty map is one check. */
    private Diving divingFor(ShipAPI ship) {
        if (ship == null || divingByEntity.isEmpty()) {
            return null;
        }
        for (Diving d : divingByEntity.values()) {
            if (d.ship == ship) {
                return d;
            }
        }
        return null;
    }

    /**
     * true while {@code ship} is an enrolled dive
     * body still PLAYING OUT (not parked) — the guard the kit's other cloak writers read
     * ({@code OwnReadoutLane}'s own-cloak force, {@code FoeCloakLane}'s edge law and overload
     * restore) so they never fight this playout's forced IN. A parked body is off-map at alpha 0,
     * where those writers run harmlessly, so parked reads false. Compared by SHIP identity, never
     * the wire id: a respawn of the same wire id arrives as a NEW {@code ShipAPI}, so a stale entry
     * on the old body never guards the new one.
     */
    boolean isDiving(ShipAPI ship) {
        Diving d = divingFor(ship);
        return d != null && !d.parked;
    }

    /**
     * The own-cloak force guard WITH its witness:
     * {@link #isDiving} for {@code OwnReadoutLane}'s disagree branch, plus the record that makes the
     * guard gradable. The guard zeroes the plugin's disagree streak every guarded frame, so "0 own-cloak
     * forces during the dive" read the same whether the guard held off a force or was never challenged
     * (11 local-on samples vs patience 30, no per-sample row). Here the streak the force
     * law WOULD hold keeps counting: {@code streakBeforeReset} = the plugin's streak at this sample,
     * before the caller zeroes it (pre-dive run + 1 on the first guarded sample, 1 on each later one);
     * an agreeing sample zeroes it ({@link #ownCloakAgreed}). {@code forceArmed} = the force law's other
     * clauses ({@code OWN_CLOAK_APPLY} and the phased-bit backing); an armed sample at or past
     * {@code patience} is one force the unguarded law would have made — counted, and the would-be streak
     * restarts as that law's does. One {@code phase-dive guard:} row per
     * sample (capped per dive) and three tokens on the park row. Reader = the park bar's INFO text;
     * delete trigger = the park row's.
     * Returns false (nothing recorded) for a body that is not mid playout.
     */
    boolean guardOwnCloakDisagree(ShipAPI ship, int streakBeforeReset, boolean authOn, boolean forceArmed,
            int patience, long simTick) {
        Diving d = divingFor(ship);
        if (d == null || d.parked) {
            return false;
        }
        d.guardSamples++;
        d.guardStreak += streakBeforeReset;
        if (d.guardStreak > d.guardStreakMax) {
            d.guardStreakMax = d.guardStreak;
        }
        int streak = d.guardStreak;
        boolean over = forceArmed && streak >= patience;
        if (over) {
            d.guardOverPatience++;
            d.guardStreak = 0; // the unguarded law forces here and restarts its streak
        }
        if (d.guardRows < GUARD_ROWS_MAX) {
            d.guardRows++;
            PilotWatchLog.info("phase-dive guard: tick=" + simTick + " auth_on=" + authOn + " force_armed="
                    + forceArmed + " streak=" + streak + " over_patience=" + over
                    + (d.guardRows == GUARD_ROWS_MAX ? " [row cap reached]" : ""));
        }
        return true;
    }

    /** An agreeing own-cloak sample ends the would-be streak of a diving {@code ship} ({@link #guardOwnCloakDisagree}). */
    void ownCloakAgreed(ShipAPI ship) {
        Diving d = divingFor(ship);
        if (d != null) {
            d.guardStreak = 0;
        }
    }

    /**
     * The pose writers' park hold — true
     * when {@code ship} (identity) is a PARKED dive body (off-map at alpha 0, awaiting its
     * {@code DEPARTED} despawn), so the caller skips the write; else a fresh on-map pose would snap
     * the body back over the 300-su hatch. An on-map {@code authY} counts into the park row's
     * {@code onMapPosesAfterPark}; the wire's own off-map park pose is held without counting.
     */
    boolean holdParkedPose(ShipAPI ship, float authY) {
        Diving d = divingFor(ship);
        if (d == null || !d.parked) {
            return false;
        }
        if (authY > PARK_Y / 2f) {
            d.onMapPosesAfterPark++;
        }
        return true;
    }

    /**
     * {@code CloakWallClockCombatPlugin}'s
     * dive read — true when {@code ship} is (by identity: the published set is an identity set)
     * an enrolled dive body on {@code engine}; no key (no apply-mode dive yet) reads false.
     */
    static boolean diveSignalHolds(CombatEngineAPI engine, ShipAPI ship) {
        Object signal = engine != null ? engine.getCustomData().get(DIVING_SHIPS_KEY) : null;
        return signal instanceof java.util.Set && ((java.util.Set<?>) signal).contains(ship);
    }

    /**
     * ONE {@code phase-dive park:} row per
     * dive, printed when the {@code DEPARTED} despawn takes the body — outside the per-round
     * {@link #LOG_MAX} cap (the once-per-dive latch is the map removal in {@link #finishDeparture}).
     * {@code worldSec}/{@code shipSec} = the playout's world vs ship seconds (the ship-time clock's
     * grade), {@code frames} = playout frames, {@code progress} = the dive progress reached,
     * {@code timeMultLast} = the last ship timeMult read, {@code parkFrames} = frames parked before
     * the despawn ({@code -1} = the despawn took the body before the fade parked it),
     * {@code onMapPosesAfterPark} = on-map pose writes {@link #holdParkedPose} held off it,
     * {@code guardSamples} / {@code guardStreakMax} / {@code guardOverPatience} = the own-cloak force
     * guard's witness ({@link #guardOwnCloakDisagree}; {@code guardOverPatience} = forces the guard
     * held off, {@code 0} = it was never challenged).
     * Reader: the flight-grading bar main adds with this unit ({@code rg 'phase-dive park'} on the
     * kit sidelog). Delete trigger: after three graded dives with {@code parkFrames >= 0} and
     * {@code onMapPosesAfterPark = 0}, this row and its bar retire.
     */
    private static void parkRow(long entityId, Diving d) {
        PilotWatchLog.info("phase-dive park: entity=" + entityId + " own=" + d.own
                + " worldSec=" + PredictReconcileCombatPlugin.fmt2(d.worldSec)
                + " shipSec=" + PredictReconcileCombatPlugin.fmt2(d.shipSec)
                + " frames=" + d.frames
                + " progress=" + PredictReconcileCombatPlugin.fmt2(d.progress)
                + " timeMultLast=" + PredictReconcileCombatPlugin.fmt2(d.timeMultLast)
                + " parkFrames=" + (d.parked ? d.parkFrames : -1)
                + " onMapPosesAfterPark=" + d.onMapPosesAfterPark
                + " guardSamples=" + d.guardSamples
                + " guardStreakMax=" + d.guardStreakMax
                + " guardOverPatience=" + d.guardOverPatience);
    }

    /**
     * Despawn take (the kill lanes' one-shot): under {@code apply}, an enrolled entity's
     * {@code DEPARTED} despawn removes the body corpse-free and returns {@code true} — the
     * caller skips death FX and fracture mint. Under {@code meter} logs the would-have and
     * returns {@code false} (verbatim path); unenrolled ids return {@code false}.
     */
    boolean finishDeparture(CombatEngineAPI engine, long entityId, ShipAPI ship) {
        Long key = Long.valueOf(entityId);
        if (!APPLY) {
            if (meterLatched.remove(key)) {
                log("phase-dive lane: meter — dive-latched entity=" + entityId
                        + " despawned (apply would corpse-free remove)");
            }
            return false;
        }
        Diving d = divingByEntity.remove(key);
        if (d == null) {
            return false;
        }
        divingShips.remove(d.ship);
        parkRow(entityId, d);
        try {
            ShipAPI body = ship != null ? ship : d.ship;
            if (engine != null && body != null) {
                engine.removeEntity(body);
            }
        } catch (Throwable ignore) {
            // removal is fail-soft; the caller's bookkeeping already ran
        }
        return true;
    }

    /** Round wrap: mid-dive bodies die with the round (fail-soft catch for un-swept stragglers). */
    void resetRound(CombatEngineAPI engine) {
        for (Diving d : divingByEntity.values()) {
            try {
                if (engine != null && d.ship != null) {
                    engine.removeEntity(d.ship);
                }
            } catch (Throwable ignore) {
                // reset-time sweep; never break the round wrap
            }
        }
        divingByEntity.clear();
        divingShips.clear(); // every enrolled body leaves the dive signal with the map
        meterLatched.clear();
        logLines = 0; // The LOG_MAX cap is per round
    }

    /** Capped log (dives are ≤1/round by the vanilla latch, but the cap bounds a spoofed flood). */
    private void log(String line) {
        if (logLines < LOG_MAX) {
            logLines++;
            PilotWatchLog.info(line);
        }
    }
}
