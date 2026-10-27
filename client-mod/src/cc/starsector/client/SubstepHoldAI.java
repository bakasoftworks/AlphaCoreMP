package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.List;

/**
 * The replay-driven replica's AI: the inert stubs (no fire, no
 * maneuver — the former PRCP {@code InertShipAI}, verbatim) PLUS the per-fast-time-sub-step hold
 * re-assert that fixes the W-FASTTIME autonomous-discharge leak at its SOURCE.
 * A time-dilated ship (the Tesseract {@code temporalshell}, up to 3×)
 * gets {@code ceil(mult)} {@code Ship.advance} passes per engine advance; the one-frame hold flags
 * are read-and-cleared by pass 1, and the plugin's {@code reassertReplayDrivenHold} runs ONCE per
 * frame (plugins, after every pass) — so passes 2..k fired unheld. The engine advances this AI per
 * fast pass BEFORE that pass's {@code Ship.advance}, which is the
 * engine-native per-sub-step seam; the gate {@link CombatEngineAPI#isInFastTimeAdvance()} is FALSE on
 * the every-frame AI advance, so pass 1 stays the plugin's (no double hold, no double meter).
 * Relay-owned beams ({@code setForceFireOneFrame}, consumed by pass 1) are re-asserted on the
 * same passes from the relay's per-frame set ({@link BeamPresentationLane#relayedThisFrame}) so the hold
 * never charges a wire-FIRING beam down.
 *
 * <p>Hot path (every replica, every frame): the knob boolean + one gate read, count-only meter.
 * Nanosecond probe only under {@code cc.predict.substepHoldNs=true}. Fail-soft: a throw
 * inside the hold counts {@code faults} and announces once — it never escapes into the engine's AI
 * advance. Lifecycle: an adopt's {@code setShipAI(null)} removes this AI; any other exit from the
 * replay-driven set makes it a no-op via the {@link PredictReconcileCombatPlugin#isReplayDriven}
 * predicate. Render-thread confined (the engine's own advance thread); no lock, no writer — the
 * meter rides the existing eventReplay-meter line ({@link #meterToken()}).
 */
final class SubstepHoldAI implements ShipAIPlugin {

    /** Knob {@code cc.predict.substepHold}, default ON; {@code false} = pass-1 hold only (A/B). */
    private static final boolean SUBSTEP_HOLD_ENABLED =
            !"false".equals(System.getProperty("cc.predict.substepHold"));
    /** Knob {@code cc.predict.substepHoldNs}, default OFF: the {@code System.nanoTime} probe. */
    private static final boolean SUBSTEP_HOLD_NS =
            "true".equals(System.getProperty("cc.predict.substepHoldNs"));

    // JVM-wide count-only meters (render thread only).
    private static long calls;
    private static long gateReads;
    private static long faults;
    private static long relayReasserts; // relay-owned beams re-force-fired on a sub-step
    private static long ns;
    private static long firstNs = -1L;

    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();
    private final PredictReconcileCombatPlugin plugin;
    private final ShipAPI ship;
    private final CombatEngineAPI engine; // cached at install: the gate-FALSE path runs per replica per frame

    SubstepHoldAI(PredictReconcileCombatPlugin plugin, ShipAPI ship, CombatEngineAPI engine) {
        this.plugin = plugin;
        this.ship = ship;
        this.engine = engine;
    }

    @Override public void advance(float amount) {
        if (!SUBSTEP_HOLD_ENABLED) {
            return;
        }
        gateReads++;
        CombatEngineAPI e = engine;
        if (e == null || !e.isInFastTimeAdvance()) {
            return; // pass 1 (and every 1× frame) stays the plugin's reassertReplayDrivenHold
        }
        calls++;
        try {
            if (SUBSTEP_HOLD_NS) {
                long t0 = System.nanoTime();
                reassertSubstepHold();
                long dt = System.nanoTime() - t0;
                ns += dt;
                if (firstNs < 0L) {
                    firstNs = dt;
                }
            } else {
                reassertSubstepHold();
            }
        } catch (Throwable t) {
            faults++;
            FallbackAnnounce.announceOnce("substep-hold", "sub-step hold threw "
                    + t.getClass().getSimpleName() + " -> passes 2..k unheld (the remover still counts+removes)");
        }
    }

    /** The per-ship body of PRCP {@code reassertReplayDrivenHold} minus its meters, the pruned-walk
     *  bookkeeping, the slew meter and the SameSideAim steer (those stay once-per-frame, pass 1). */
    private void reassertSubstepHold() {
        if (!plugin.isReplayDriven(ship) || plugin.mountedBodies.contains(ship) || !ship.isAlive()) {
            return; // not (or no longer) replay-driven / a seat-mounted module / dead
        }
        ship.setHoldFireOneFrame(true);
        // Same side only: the per-weapon no-fire flags (foe bodies keep the ship-level hold posture only;
        // legacyoff: the group toggle-off is persistent — no per-sub-step re-assert owed).
        boolean holdWeapons = ship.getOwner() == plugin.sideAuthority()
                && !PredictKnobConfig.SAME_SIDE_HOLD_LEGACY_OFF;
        List<WeaponAPI> weapons = plugin.weaponLists.weapons(ship); // per-frame cache, never rebuilt per sub-step
        if (weapons == null) {
            return;
        }
        for (int w = 0; w < weapons.size(); w++) {
            WeaponAPI weapon = weapons.get(w);
            if (weapon == null) {
                continue;
            }
            if (weapon.isBeam()) {
                // Beams stay relay-owned (forceNoFire would override the forceFire) — and the relay's
                // force-fire is consumed by pass 1, so a wire-FIRING beam is re-asserted here, BOTH sides, or the
                // ship-level hold above charges it down on passes 2..k (the relay set is the gate).
                if (plugin.beamPres.relayedThisFrame(weapon)) {
                    weapon.setForceFireOneFrame(true);
                    relayReasserts++;
                }
                continue;
            }
            if (!holdWeapons) {
                continue;
            }
            weapon.setForceNoFireOneFrame(true);
            if (weapon.isFiring()) {
                weapon.stopFiring(); // a committed burst ignores both hold flags
            }
        }
    }

    /** Rides the {@code eventReplay-meter} line beside the leak remover's tokens. */
    static String meterToken() {
        String s = " predict_substep_hold_calls=" + calls
                + " predict_substep_hold_gate_reads=" + gateReads
                + " predict_substep_hold_faults=" + faults
                + " predict_substep_hold_relay_beams=" + relayReasserts
                + " predict_substep_hold_gate=" + (SUBSTEP_HOLD_ENABLED ? "on" : "off");
        if (SUBSTEP_HOLD_NS) {
            s += " predict_substep_hold_ns=" + ns + " predict_substep_hold_first_ns=" + firstNs;
        }
        return s;
    }

    @Override public void setDoNotFireDelay(float amount) { /* no-op */ }
    @Override public void forceCircumstanceEvaluation() { /* no-op */ }
    @Override public boolean needsRefit() { return false; }
    @Override public ShipwideAIFlags getAIFlags() { return flags; }
    @Override public void cancelCurrentManeuver() { /* no-op */ }
    @Override public ShipAIConfig getConfig() { return config; }
}
