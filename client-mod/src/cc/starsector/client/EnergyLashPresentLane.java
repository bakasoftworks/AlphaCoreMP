package cc.starsector.client;

import java.awt.Color;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.combat.threat.AttackSwarmPhaseModeScript;
import com.fs.starfarer.api.impl.combat.threat.EnergyLashSystemScript;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.ThreatSwarmAI;
import com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect;
import com.fs.starfarer.api.util.Misc;

/**
 * fidelity-map M-G10: the overseer energy lash's PHASE MODE on the swarm it
 * lashed, presented locally on the replica swarm (the "blue-purple swarm" a player sees in vanilla,
 * never in ours). 0 wire bytes: derived from the foe {@code SYSTEM_WINDOW} open edge that already
 * rides the wire (the activating overseer's id), the target from the same law vanilla uses.
 *
 * <p>Vanilla ({@code EnergyLashSystemScript.applyEffectToTarget}): a lash whose target is a
 * SAME-OWNER attack swarm ({@code ThreatSwarmAI.isAttackSwarm}) installs
 * {@link AttackSwarmPhaseModeScript} on that swarm ship: fringe {@code PHASE_FRINGE_COLOR@60},
 * flash radius 180, the {@code SWARM_TAG_PHASE_MODE} tag, for {@code DURATION} = 20 s, then a 2 s
 * glow suppression, then the originals restored. An ENEMY target gets the
 * EMP arcs instead, never the glow. The wire carries no target id, so the local pick mirrors
 * {@code findTarget}: an enemy within lash reach is what vanilla lashes (no glow); else the
 * closest same-owner attack swarm within reach. A visibly wrong pick is the fidelity map's 8 B
 * wire fallback, not a client fix.
 *
 * <p>PRESENTATION ONLY: the vanilla script is never instantiated on a replica — its ctor also
 * writes weapon ammo, which would fight the wire-maintained weapon state. Only the
 * swarm params (colour, radius, tag, flash frequency) are touched; the tag alone also purples the
 * swarm's discharge arcs through {@code CcWrapVoltaicDischargeOnFireEffect}. Fail-soft: every
 * throw restores and logs; the round wrap restores everything.
 */
final class EnergyLashPresentLane {

    /** Census tag on the {@link CcReplicaEffectGate} line. */
    static final String TAG = "lash";

    /** Resolved {@code cc.predict.energyLashFx} mode (the shared {@code off|meter|apply} vocabulary). */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.energyLashFx", System.getProperty("cc.predict.energyLashFx", "apply"));
    static final boolean METER = !"off".equals(MODE);
    static final boolean APPLY = "apply".equals(MODE);

    /** The phase-mode flash radius of vanilla {@code AttackSwarmPhaseModeScript}. */
    static final float PHASE_FLASH_RADIUS = 180f;
    /** The glow-suppression fade after the 20 s. */
    static final float GLOW_FADE_DELAY = 2f;
    /** Vanilla's system id for the overseer lash ({@code ship_systems.csv}); the script test is primary. */
    static final String LASH_SYSTEM_ID_PREFIX = "energy_lash";
    private static final int LOG_MAX = 16;

    /** One glow in flight — vanilla's per-instance state, presentation half. */
    private static final class Glow {
        final ShipAPI swarmShip;
        final Color origColor;
        final float origRadius;
        final float origFlashFrequency;
        final float origFlashRateMult;
        float timeLeft = AttackSwarmPhaseModeScript.DURATION;
        float fadeLeft = GLOW_FADE_DELAY;
        boolean suppressed;

        Glow(ShipAPI swarmShip, RoilingSwarmEffect.RoilingSwarmParams p) {
            this.swarmShip = swarmShip;
            this.origColor = p.flashFringeColor;
            this.origRadius = p.flashRadius;
            this.origFlashFrequency = p.flashFrequency;
            this.origFlashRateMult = p.flashRateMult;
        }
    }

    private final ArrayList<Glow> glows = new ArrayList<Glow>();
    private final IdentityHashMap<ShipAPI, Glow> byShip = new IdentityHashMap<ShipAPI, Glow>();

    private int opens;
    private int glowsBegun;
    private int rearmed;
    private int enemyFirst;
    private int noTarget;
    private int noSwarm;
    private int meterCandidates;
    private int throwsSeen;
    private int logLines;

    /** Foe {@code SYSTEM_WINDOW} OPEN edge relay — declines silently unless the activator lashes. */
    void onSystemOpen(ShipAPI activator, CombatEngineAPI engine) {
        if (!METER || activator == null || engine == null) {
            return;
        }
        try {
            if (!isLashShip(activator)) {
                return;
            }
            opens++;
            float reach = lashReach(activator);
            if (enemyWithinReach(activator, engine, reach)) {
                enemyFirst++; // vanilla lashes the enemy (EMP arcs) — no glow on this press
                return;
            }
            ShipAPI target = closestOwnAttackSwarm(activator, engine, reach);
            if (target == null) {
                noTarget++;
                log("NO-TARGET " + ident(activator) + " — no same-owner attack swarm within " + (int) reach);
                return;
            }
            if (!APPLY) {
                meterCandidates++;
                log("METER would-glow " + ident(target) + " from " + ident(activator) + " (mode=meter, nothing written)");
                return;
            }
            Glow g = byShip.get(target);
            if (g != null) {
                // a re-lash inside the 20 s re-arms the timer (vanilla stacks a second script; one glow is the look)
                g.timeLeft = AttackSwarmPhaseModeScript.DURATION;
                g.fadeLeft = GLOW_FADE_DELAY;
                if (g.suppressed) {
                    RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(target);
                    if (swarm != null) {
                        swarm.getParams().flashFrequency = g.origFlashFrequency;
                    }
                    g.suppressed = false;
                }
                rearmed++;
                return;
            }
            RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(target);
            if (swarm == null) {
                noSwarm++;
                return; // the replica's builtin cloud is not minted yet — nothing to colour
            }
            RoilingSwarmEffect.RoilingSwarmParams p = swarm.getParams();
            g = new Glow(target, p);
            p.flashFringeColor = Misc.setAlpha(VoltaicDischargeOnFireEffect.PHASE_FRINGE_COLOR, 60); //
            p.flashRadius = PHASE_FLASH_RADIUS; //
            p.tags.add(VoltaicDischargeOnFireEffect.SWARM_TAG_PHASE_MODE); //
            glows.add(g);
            byShip.put(target, g);
            glowsBegun++;
            CcReplicaEffectGate.replicaFrame(TAG);
            CcReplicaEffectGate.visualMinted(TAG);
            log("GLOW " + ident(target) + " from " + ident(activator) + " for "
                    + AttackSwarmPhaseModeScript.DURATION + "s (vanilla AttackSwarmPhaseModeScript, params only)");
        } catch (Throwable t) {
            throwsSeen++;
            log("open THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Tick — the vanilla script's {@code advance}: countdown, 2 s suppression, restore. */
    void advance(CombatEngineAPI engine, float amount) {
        if (!APPLY || engine == null || glows.isEmpty() || engine.isPaused()) {
            return;
        }
        for (Iterator<Glow> it = glows.iterator(); it.hasNext();) {
            Glow g = it.next();
            try {
                if (!g.swarmShip.isAlive() || g.swarmShip.isHulk()) {
                    it.remove();
                    byShip.remove(g.swarmShip);
                    continue; // the swarm died with its glow on — vanilla leaves it too
                }
                g.timeLeft -= amount;
                if (g.timeLeft > 0f) {
                    continue;
                }
                RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(g.swarmShip);
                if (swarm != null && !g.suppressed) {
                    swarm.getParams().flashFrequency = 0f; // as vanilla's suppressGlow
                    swarm.getParams().flashRateMult = 1f;
                    g.suppressed = true;
                }
                g.fadeLeft -= amount;
                if (g.fadeLeft <= 0f) {
                    restore(g);
                    it.remove();
                    byShip.remove(g.swarmShip);
                }
            } catch (Throwable t) {
                throwsSeen++;
                it.remove();
                byShip.remove(g.swarmShip);
                log("advance THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /** Round wrap: every glow dies with the round (fail-soft restore). */
    void resetRound() {
        for (Glow g : glows) {
            try {
                restore(g);
            } catch (Throwable ignore) {
                // presentation only
            }
        }
        glows.clear();
        byShip.clear();
    }

    /** Meter fragment (pinned key names; rides the spawn-gate meter line). */
    String meterTokens() {
        return " predict_lash_mode=" + MODE
                + " predict_lash_opens=" + opens
                + " predict_lash_glows=" + glowsBegun
                + " predict_lash_rearmed=" + rearmed
                + " predict_lash_enemy_first=" + enemyFirst
                + " predict_lash_no_target=" + noTarget
                + " predict_lash_no_swarm=" + noSwarm
                + " predict_lash_meter_candidates=" + meterCandidates
                + " predict_lash_throws=" + throwsSeen;
    }

    // ------------------------------------------------------------------ laws

    /** The activator carries the energy lash (script test first; the system id as the fallback). */
    static boolean isLashShip(ShipAPI s) {
        ShipSystemAPI sys = s.getSystem();
        if (sys == null) {
            return false;
        }
        if (sys.getScript() instanceof EnergyLashSystemScript) {
            return true;
        }
        String id = sys.getId();
        return id != null && id.startsWith(LASH_SYSTEM_ID_PREFIX);
    }

    /** As vanilla's {@code getRange}: the system range bonus over {@code MAX_LASH_RANGE}. */
    static float lashReach(ShipAPI s) {
        try {
            return s.getMutableStats().getSystemRangeBonus().computeEffective(EnergyLashSystemScript.MAX_LASH_RANGE);
        } catch (Throwable t) {
            return EnergyLashSystemScript.MAX_LASH_RANGE;
        }
    }

    /** Any live enemy hull within reach + radii = what vanilla lashes first (no glow). */
    static boolean enemyWithinReach(ShipAPI lasher, CombatEngineAPI engine, float reach) {
        for (ShipAPI s : engine.getShips()) {
            if (s == null || s == lasher || s.isHulk() || !s.isAlive() || s.getOwner() == 100
                    || s.getOwner() == lasher.getOwner() || s.isShuttlePod()) {
                continue;
            }
            float d = Misc.getDistance(lasher.getLocation(), s.getLocation());
            if (d <= reach + lasher.getCollisionRadius() + s.getCollisionRadius()) {
                return true;
            }
        }
        return false;
    }

    /** As vanilla's {@code isValidLashTarget}: the closest same-owner attack swarm within reach. */
    static ShipAPI closestOwnAttackSwarm(ShipAPI lasher, CombatEngineAPI engine, float reach) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI s : engine.getShips()) {
            if (s == null || s == lasher || !s.isFighter() || s.isHulk() || !s.isAlive()
                    || s.getOwner() != lasher.getOwner() || !ThreatSwarmAI.isAttackSwarm(s)) {
                continue;
            }
            float d = Misc.getDistance(lasher.getLocation(), s.getLocation());
            if (d <= reach + lasher.getCollisionRadius() + s.getCollisionRadius() && d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    private static void restore(Glow g) {
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(g.swarmShip);
        if (swarm == null) {
            return;
        }
        RoilingSwarmEffect.RoilingSwarmParams p = swarm.getParams(); // as vanilla's turnOffPhaseMode
        p.flashFringeColor = g.origColor;
        p.flashRadius = g.origRadius;
        p.flashFrequency = g.origFlashFrequency;
        p.flashRateMult = g.origFlashRateMult;
        p.tags.remove(VoltaicDischargeOnFireEffect.SWARM_TAG_PHASE_MODE);
    }

    private static String ident(ShipAPI s) {
        String hull = s.getHullSpec() != null ? s.getHullSpec().getHullId() : "?";
        return hull + "#" + s.getId();
    }

    private void log(String msg) {
        if (logLines >= LOG_MAX) {
            return;
        }
        logLines++;
        PilotWatchLog.info("predict threat energy-lash: " + msg
                + (logLines == LOG_MAX ? " [lane log cap; counters continue]" : ""));
    }
}
