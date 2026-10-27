package cc.starsector.client;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The Ziggurat's mote-attractor targeting VFX on a REMOTE activator
 * (the reported "attractor invisible remotely" defect). Vanilla's {@code MoteControlScript.apply}
 * paints it inside the system script, which never runs on a replica: at
 * {@code effectLevel == 1} (= charge-up complete) an EMP arc from the MEDIUM system slot to
 * {@code SharedMoteAIData.attractorTarget} — anchored to the locked ship's centre when one was
 * locked, else to a bare world point — with {@code setCoreWidthOverride(30)} +
 * {@code setSingleFlickerMode(true)}, the {@code mote_attractor_targeted_ship} sound, plus
 * {@code mote_attractor_targeted_empty_space} when nothing was locked; from the FIRST charge
 * frame ({@code calculateTargetData}) the locked ship jitters for
 * {@code chargeUp + chargeDown} seconds.
 *
 * <p>Wire: schema v12 — the foe {@code SYSTEM_WINDOW} ON edge carries the target world x/y on
 * the two float slots and the lock's wire id (or {@code TARGET_NONE}) on {@code targetWireId}
 * ({@code GameplayEdgeEventDetector} stamps it only for {@code mote_control} presses whose
 * {@code SharedMoteAIData} the bridge read that frame). The ON edge is the charge-up START, so
 * the arc is fired {@code getChargeUpDur()} later from ONE {@link BaseEveryFrameCombatPlugin}
 * that also runs the lock jitter. The arc is {@code spawnEmpArcVisual}:
 * damage-less by construction — vanilla's {@code spawnEmpArc(dam=0, emp=0)} would still
 * be a combat entity, and the throwaway asteroid it arcs to is not needed. HF colours derive
 * from the replica variant ({@code high_frequency_attractor}); a stripped variant only costs
 * the colour. PRESENTATION ONLY — no mote is launched, no shared data is written; fail-soft:
 * every throw is counted and logged, the round wrap removes live plugins.
 */
final class MoteAttractorFxLane {

    /** Census tag on the {@link CcReplicaEffectGate} line. */
    static final String TAG = "moteattract";

    /** Resolved {@code cc.predict.moteAttractorFx} mode (the shared {@code off|meter|apply} vocabulary). */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.moteAttractorFx", System.getProperty("cc.predict.moteAttractorFx", "apply"));
    static final boolean METER = !"off".equals(MODE);
    static final boolean APPLY = "apply".equals(MODE);

    /** Normal vs high-frequency EMP arc colours. */
    static final Color EMP_NORMAL = new Color(100, 165, 255, 255);
    static final Color EMP_HF = new Color(255, 100, 255, 255);
    /** Normal vs high-frequency lock jitter colours. */
    static final Color JITTER_NORMAL = new Color(100, 165, 255, 175);
    static final Color JITTER_HF = new Color(255, 100, 255, 175);
    static final Color ARC_CORE = new Color(255, 255, 255, 255);
    /** The hullmod whose presence selects the high-frequency colours. */
    static final String HF_HULLMOD = "high_frequency_attractor";
    /** The locked-ship sound: rides the arc spawn in vanilla; played at the target here. */
    static final String SOUND_TARGETED = "mote_attractor_targeted_ship";
    /** The no-lock press's sound. */
    static final String SOUND_EMPTY_SPACE = "mote_attractor_targeted_empty_space";
    /** Arc thickness. */
    static final float ARC_THICKNESS = 40f;
    /** The arc's core width override. */
    static final float ARC_CORE_WIDTH = 30f;
    /** The jitter's max range bonus at full level. */
    static final float JITTER_MAX_RANGE_BONUS = 50f;
    private static final int LOG_MAX = 16;

    /** One press in flight: charge-up wait → arc, jitter across charge-up + charge-down. */
    private final class Press extends BaseEveryFrameCombatPlugin {
        final ShipAPI activator;
        final ShipAPI lock;
        final Vector2f targetLoc;
        final float chargeUp;
        final float chargeDown;
        final boolean highFrequency;
        float elapsed;
        boolean fired;

        Press(ShipAPI activator, ShipAPI lock, Vector2f targetLoc, float chargeUp, float chargeDown,
                boolean highFrequency) {
            this.activator = activator;
            this.lock = lock;
            this.targetLoc = targetLoc;
            this.chargeUp = chargeUp;
            this.chargeDown = chargeDown;
            this.highFrequency = highFrequency;
        }

        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) {
                return;
            }
            try {
                elapsed += amount;
                if (!fired && elapsed >= chargeUp) {
                    fired = true;
                    fireArc(engine, this);
                }
                // As in createTargetJitterPlugin: ramp over chargeUp, fade (squared) over chargeDown.
                float level;
                if (elapsed < chargeUp) {
                    level = chargeUp > 0f ? elapsed / chargeUp : 1f;
                } else if (elapsed < chargeUp + chargeDown) {
                    level = 1f - (elapsed - chargeUp) / chargeDown;
                    level *= level;
                } else {
                    level = 0f;
                }
                if (lock != null && level > 0f && lock.isAlive()) {
                    Color jitter = highFrequency ? JITTER_HF : JITTER_NORMAL;
                    float rangeBonus = level * JITTER_MAX_RANGE_BONUS;
                    lock.setJitterUnder(this, jitter, level, 10, 0f, rangeBonus);
                    lock.setJitter(this, jitter, level, 4, 0f, rangeBonus);
                }
                if (fired && elapsed >= chargeUp + chargeDown) {
                    retire(engine, this);
                }
            } catch (Throwable t) {
                throwsSeen++;
                log("press THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
                retire(engine, this);
            }
        }
    }

    private final ArrayList<Press> live = new ArrayList<Press>();

    private int presents;
    private int locked;
    private int emptySpace;
    private int meterCandidates;
    private int arcsFired;
    private int lockLost;
    private int noSlot;
    private int throwsSeen;
    private int logLines;

    /**
     * Foe {@code SYSTEM_WINDOW} ON relay for a mote-target edge: {@code x}/{@code y} = the
     * attractor target (world), {@code lockOrNull} = the locked ship's replica (null = an
     * empty-space press, or a lock the wire could not resolve — the arc then goes to the point,
     * the fidelity fallback rather than a client fix). Declines silently when the payload is
     * not finite (a plain ON edge).
     */
    void present(ShipAPI activator, ShipAPI lockOrNull, float x, float y, CombatEngineAPI engine) {
        if (!METER || activator == null || engine == null
                || !Float.isFinite(x) || !Float.isFinite(y)) {
            return;
        }
        try {
            presents++;
            if (lockOrNull != null) {
                locked++;
            } else {
                emptySpace++;
            }
            boolean hf = isHighFrequency(activator);
            if (!APPLY) {
                meterCandidates++;
                log("METER would-arc " + ident(activator) + " -> "
                        + (lockOrNull != null ? ident(lockOrNull) : ((int) x + "," + (int) y))
                        + " hf=" + hf + " (mode=meter, nothing spawned)");
                return;
            }
            float chargeUp = 0f;
            float chargeDown = 0f;
            ShipSystemAPI sys = activator.getSystem();
            if (sys != null) {
                chargeUp = Math.max(0f, sys.getChargeUpDur());
                chargeDown = Math.max(0f, sys.getChargeDownDur());
            }
            Press p = new Press(activator, lockOrNull, new Vector2f(x, y), chargeUp, chargeDown, hf);
            live.add(p);
            engine.addPlugin(p);
            CcReplicaEffectGate.replicaFrame(TAG);
            log("PRESS " + ident(activator) + " -> "
                    + (lockOrNull != null ? ident(lockOrNull) : ("point " + (int) x + "," + (int) y))
                    + " hf=" + hf + " arc@+" + chargeUp + "s jitter " + (chargeUp + chargeDown) + "s");
        } catch (Throwable t) {
            throwsSeen++;
            log("present THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** At {@code effectLevel == 1}: the arc + sounds, as vanilla fires them. */
    private void fireArc(CombatEngineAPI engine, Press p) {
        Vector2f slotLoc = attractorSlotLoc(p.activator);
        if (slotLoc == null) {
            noSlot++;
            slotLoc = new Vector2f(p.activator.getLocation());
        }
        ShipAPI lock = p.lock;
        Vector2f to = p.targetLoc;
        if (lock != null) {
            if (lock.isAlive() && !lock.isHulk()) {
                to = new Vector2f(lock.getLocation()); // setTargetToShipCenter
            } else {
                lockLost++;
                lock = null; // vanilla clears a dead lock; the point still gets the arc
            }
        }
        EmpArcEntityAPI.EmpArcParams params = new EmpArcEntityAPI.EmpArcParams(); //
        params.segmentLengthMult = 8f;
        params.zigZagReductionFactor = 0.15f;
        params.brightSpotFullFraction = 0.5f;
        params.brightSpotFadeFraction = 0.5f;
        float dist = Misc.getDistance(slotLoc, to);
        params.flickerRateMult = Math.max(0.3f, 0.6f - dist / 3000f);
        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(slotLoc, p.activator, to, lock, ARC_THICKNESS,
                p.highFrequency ? EMP_HF : EMP_NORMAL, ARC_CORE, params);
        if (arc != null) {
            arc.setCoreWidthOverride(ARC_CORE_WIDTH);
            arc.setSingleFlickerMode(true);
        }
        Vector2f still = new Vector2f();
        Global.getSoundPlayer().playSound(SOUND_TARGETED, 1f, 1f, to, still);
        if (lock == null) {
            Global.getSoundPlayer().playSound(SOUND_EMPTY_SPACE, 1f, 1f, to, still);
        }
        arcsFired++;
        CcReplicaEffectGate.visualMinted(TAG);
    }

    private void retire(CombatEngineAPI engine, Press p) {
        live.remove(p);
        try {
            engine.removePlugin(p);
        } catch (Throwable ignore) {
            // presentation only
        }
    }

    /** Round wrap: mid-charge presses die with the round. */
    void resetRound(CombatEngineAPI engine) {
        if (engine != null) {
            for (Press p : live) {
                try {
                    engine.removePlugin(p);
                } catch (Throwable ignore) {
                    // presentation only
                }
            }
        }
        live.clear();
    }

    /** Meter fragment (pinned key names; rides the spawn-gate meter line). */
    String meterTokens() {
        return " predict_mote_mode=" + MODE
                + " predict_mote_presents=" + presents
                + " predict_mote_locked=" + locked
                + " predict_mote_empty=" + emptySpace
                + " predict_mote_arcs=" + arcsFired
                + " predict_mote_lock_lost=" + lockLost
                + " predict_mote_no_slot=" + noSlot
                + " predict_mote_meter_candidates=" + meterCandidates
                + " predict_mote_live=" + live.size()
                + " predict_mote_throws=" + throwsSeen;
    }

    // ------------------------------------------------------------------ laws

    /** Vanilla's {@code isHighFrequency}: the variant carries {@link #HF_HULLMOD}. */
    static boolean isHighFrequency(ShipAPI s) {
        try {
            return s.getVariant() != null && s.getVariant().hasHullMod(HF_HULLMOD);
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code findSlots}: the MEDIUM system slot's world position, null when absent. */
    static Vector2f attractorSlotLoc(ShipAPI s) {
        try {
            if (s.getHullSpec() == null) {
                return null;
            }
            for (WeaponSlotAPI slot : s.getHullSpec().getAllWeaponSlotsCopy()) {
                if (slot.isSystemSlot() && slot.getSlotSize() == WeaponAPI.WeaponSize.MEDIUM) {
                    return slot.computePosition(s);
                }
            }
        } catch (Throwable ignore) {
            // fall through: the caller substitutes the hull centre
        }
        return null;
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
        PilotWatchLog.info("predict threat mote-attractor: " + msg
                + (logLines == LOG_MAX ? " [lane log cap; counters continue]" : ""));
    }
}
