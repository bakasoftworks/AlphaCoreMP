package cc.starsector.client;

import java.awt.Color;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;

/**
 * The engine-reading half of SERVING autopilot fire — the pure rules are
 * {@link AutopilotFireServeLaw}. Three seams into {@link PredictReconcileCombatPlugin}'s render-thread
 * input capture: {@link #deriveHeld} folds the AI's selected-group fire into the wire's held level,
 * {@link #aiAimPoint} replaces the idle physical cursor with the AI's aim while a ship-AI drives the
 * own hull, and {@link #heldRecency} lets the unwired-own-fire verdict read the derived level at the
 * discharge instant. Plus the ONE per-combat player-facing notice ({@link #noteUnwired}) the P19
 * measurement earned. Render-thread only; fail-soft throughout (a read hiccup = the mouse path, never
 * a thrown frame); B-legal: engine API + the plugin. Own file per the NEW-LANE LAW
 * ({@code deferred-refactors.md}; the plugin sits 28 nb under its size tier).
 */
final class AutopilotFireLane {
    private final PredictReconcileCombatPlugin plugin;
    /** Frames the derived level read true with the mouse UP (the AI's fire the wire now carries) — GAP2 fire-meter. */
    int synthHeldFrames;
    /** The last frame's aim source — the diagnostic dump / a log line, never a decision input. */
    AutopilotFireServeLaw.AimSource lastAimSource = AutopilotFireServeLaw.AimSource.CURSOR;
    private boolean noticeShown;

    AutopilotFireLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Any weapon of the own ship's SELECTED group non-idle this frame ({@code isFiring()}); fail-soft false. */
    boolean selectedGroupFiring() {
        try {
            ShipAPI own = plugin.ownShip;
            WeaponGroupAPI grp = own != null ? own.getSelectedGroupAPI() : null;
            if (grp == null) {
                return false;
            }
            List<WeaponAPI> weapons = grp.getWeaponsCopy();
            if (weapons == null) {
                return false;
            }
            for (WeaponAPI w : weapons) {
                if (w != null && w.isFiring()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** This frame's wire held level: mouse-0 (post-swallow) OR the AI's selected-group fire ({@link AutopilotFireServeLaw#deriveHeld}). */
    boolean deriveHeld(boolean mouseHeld) {
        boolean aiDriven = plugin.ownAiDriven();
        boolean synth = aiDriven && selectedGroupFiring();
        if (synth && !mouseHeld) {
            synthHeldFrames++;
        }
        return AutopilotFireServeLaw.deriveHeld(mouseHeld, aiDriven, synth);
    }

    /**
     * The held-lane recency the unwired-own-fire verdict reads AT a discharge instant: 0 when the
     * derived level holds NOW (the birth frame's weapon state — the next capture carries it and the
     * server's edge latch presses on it), else the frame-start sample's recency.
     */
    float heldRecency() {
        return AutopilotFireServeLaw.deriveHeld(plugin.fireHeld, plugin.ownAiDriven(), selectedGroupFiring())
                ? 0f : plugin.secondsSinceFireHeld;
    }

    /**
     * Under a ship-AI: the aim world point the wire should carry — the first non-idle selected-group
     * weapon's own ray (its slewed angle projected to its range: the twin's turret converges on the
     * same angle), else the AI's ship target; {@code null} = no AI or nothing better = the cursor path.
     */
    Vector2f aiAimPoint(ShipAPI own) {
        try {
            if (own == null || !plugin.ownAiDriven()) {
                lastAimSource = AutopilotFireServeLaw.AimSource.CURSOR;
                return null;
            }
            float[] ray = null;
            WeaponGroupAPI grp = own.getSelectedGroupAPI();
            List<WeaponAPI> weapons = grp != null ? grp.getWeaponsCopy() : null;
            if (weapons != null) {
                for (WeaponAPI w : weapons) {
                    if (w != null && w.isFiring()) {
                        Vector2f at = w.getLocation();
                        ray = at != null
                                ? AutopilotFireServeLaw.projectRay(at.x, at.y, w.getCurrAngle(), w.getRange())
                                : null;
                        if (ray != null) {
                            break;
                        }
                    }
                }
            }
            ShipAPI target = own.getShipTarget();
            Vector2f targetAt = target != null ? target.getLocation() : null;
            boolean targetFinite = targetAt != null && Float.isFinite(targetAt.x) && Float.isFinite(targetAt.y);
            lastAimSource = AutopilotFireServeLaw.aimSource(true, ray != null, targetFinite);
            switch (lastAimSource) {
                case WEAPON_RAY:
                    return new Vector2f(ray[0], ray[1]);
                case SHIP_TARGET:
                    return new Vector2f(targetAt.x, targetAt.y);
                default:
                    return null;
            }
        } catch (Throwable ignore) {
            lastAimSource = AutopilotFireServeLaw.AimSource.CURSOR;
            return null;
        }
    }

    /**
     * The player-facing line: ONE per combat, after
     * {@link AutopilotFireServeLaw#NOTICE_AFTER_UNWIRED} own discharges the wire never carried
     * (post-P22 that is a rebound fire key / a source mouse-0 never sees, or a derivation miss the
     * GAP2 counters still show). Static text — no ids, no player text; the detail stays in the log.
     */
    void noteUnwired(int unwiredTotal) {
        if (!AutopilotFireServeLaw.noticeDue(noticeShown, unwiredTotal)) {
            return;
        }
        noticeShown = true;
        try {
            CombatEngineAPI engine = Global.getCombatEngine();
            CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
            if (ui != null) {
                ui.addMessage(0, Color.ORANGE, "AlphaCoreMP: ", Color.LIGHT_GRAY,
                        "some of your shots are not reaching the server - fire with the mouse button"
                        + " (a key bound to fire is not carried)");
            }
            PilotWatchLog.info("predict own-fire notice shown: unwired=" + unwiredTotal
                    + " synthHeldFrames=" + synthHeldFrames + " aimSource=" + lastAimSource);
        } catch (Throwable ignore) {
            // the notice is presentation; it must never break the capture it rides beside
        }
    }
}
