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
 * the every-frame AI advance, so pass 1's HOLD stays the plugin's (no double hold, no double meter).
 * Relay-owned beams ({@code setForceFireOneFrame}, consumed by pass 1) are re-asserted on the
 * same passes from the relay's per-frame set ({@link BeamPresentationLane#relayedThisFrame}) so the hold
 * never charges a wire-FIRING beam down.
 *
 * <p>THE GENERAL BEAM GUARD: on EVERY advance
 * (pass 1 included), every replica BEAM the relay did not force-fire this frame gets
 * {@code setForceNoFireOneFrame(true)}. WHY: the ship-level hold only gates the weapon group's
 * {@code shouldFire}; a mod effect's own {@code setForceFireOneFrame} on a replica beam (the Arma pila
 * blade, keyed on {@code getWing()!=null} — armed the moment {@link CarrierBornFighterFx} gives every
 * replica fighter its wing) fires that beam LOCALLY, a fire decision the server never made. The weapon
 * reads force-fire THEN force-no-fire (the no-fire wins), clears both, and only then runs the effect
 * — so the
 * no-fire set at this AI advance beats the effect's force-fire set one weapon advance earlier. ORDER PROOF for the relay:
 * the set read here at engine frame N+1 is the set the relay filled in plugin frame N
 * ({@link BeamPresentationLane#beginRelayFrame} clears it only at the NEXT plugin frame head), whose
 * force-fire the weapon consumes in this same frame's pass-1 advance — a relayed beam is never no-fired,
 * so no wire-FIRING beam loses a frame. Both sides. Vanilla loses nothing: autofire beams on a
 * replica were already held by the ship-level hold; only a non-relayed force-fire is blocked — the
 * general class ({@code CcWrapSwarmLauncherEffect} hand-rolled the same for one non-beam effect). DECORATIVE
 * mounts are EXEMPT — no damage ⇒ no fire decision to guard; the Lidar Array's dish beams (force-fired in
 * State.IN; the spawn-system gate leaves {@code lidararray} un-gated on
 * replicas on purpose) keep showing.
 *
 * <p>Hot path (every replica, every frame): the attach probe (an {@code isEmpty} read), the knob boolean,
 * one gate read, then the guard's walk over the per-frame weapon list ({@link WeaponListCache}: beams
 * pay one identity-set lookup, other mounts one {@code isBeam}); count-only meters. Nanosecond probe only
 * under {@code cc.predict.substepHoldNs=true} (MEASURE on the first flight — the game-free
 * warm-cost rows in {@code SubstepHoldAISelftest} print the Proxy-dispatch floor). Fail-soft: a throw
 * inside the hold counts {@code faults} and announces once — it never escapes into the engine's AI
 * advance. Lifecycle: an adopt's {@code setShipAI(null)} removes this AI; any other exit from the
 * replay-driven set makes it a no-op via the {@link PredictReconcileCombatPlugin#isReplayDriven}
 * predicate. Render-thread confined (the engine's own advance thread); no lock, no writer — the
 * meter rides the existing eventReplay-meter line ({@link #meterToken()}).
 *
 * <p>THE ATTACH HOOK: the first line of {@link #advance} is
 * {@link CarrierBornFighterFx#attachIfDue} — the engine advances this AI BEFORE the ship (and its weapons)
 * on every pass and promotion precedes it, so a carrier-born
 * replica's wing is present at its FIRST weapon advance. It runs before the knob gate: the attach is
 * not the hold's A/B.
 */
final class SubstepHoldAI implements ShipAIPlugin {

    /** Knob {@code cc.predict.substepHold}, default ON; {@code false} = this AI is inert (pass-1 hold
     *  only, no beam guard — the A/B; the attach hook runs either way). */
    private static final boolean SUBSTEP_HOLD_ENABLED =
            !"false".equals(System.getProperty("cc.predict.substepHold"));
    /** Knob {@code cc.predict.substepHoldNs}, default OFF: the {@code System.nanoTime} probe. */
    private static final boolean SUBSTEP_HOLD_NS =
            "true".equals(System.getProperty("cc.predict.substepHoldNs"));

    // JVM-wide count-only meters (render thread only).
    private static long calls;          // fast-pass hold re-asserts (gate TRUE)
    private static long gateReads;      // every advance past the knob
    private static long faults;
    private static long relayReasserts; // relay-owned beams re-force-fired on a sub-step
    private static long beamGuardHolds; // Advances on which >= 1 non-relayed replica beam was no-fired
    private static long ns;
    private static long firstNs = -1L;

    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();
    private final PredictReconcileCombatPlugin plugin;
    private final ShipAPI ship;
    private final CombatEngineAPI engine; // cached at install: the gate read runs per replica per frame

    SubstepHoldAI(PredictReconcileCombatPlugin plugin, ShipAPI ship, CombatEngineAPI engine) {
        this.plugin = plugin;
        this.ship = ship;
        this.engine = engine;
    }

    @Override public void advance(float amount) {
        plugin.fighterHud.fighterFx.attachIfDue(ship); // The zero-frame wing attach (class javadoc)
        if (!SUBSTEP_HOLD_ENABLED) {
            return;
        }
        gateReads++;
        CombatEngineAPI e = engine;
        boolean hold = e != null && e.isInFastTimeAdvance(); // pass 1 (and every 1× frame): the HOLD stays the plugin's
        if (hold) {
            calls++;
        }
        try {
            if (SUBSTEP_HOLD_NS) {
                long t0 = System.nanoTime();
                guardAndHold(hold);
                long dt = System.nanoTime() - t0;
                ns += dt;
                if (firstNs < 0L) {
                    firstNs = dt;
                }
            } else {
                guardAndHold(hold);
            }
        } catch (Throwable t) {
            faults++;
            FallbackAnnounce.announceOnce("substep-hold", "sub-step hold threw "
                    + t.getClass().getSimpleName() + " -> passes 2..k unheld + the beam guard off this advance"
                    + " (the remover still counts+removes)");
        }
    }

    /** The beam guard on every pass; with {@code hold} (a fast pass) also the per-ship body of PRCP
     *  {@code reassertReplayDrivenHold} minus its meters, the pruned-walk bookkeeping, the slew meter and
     *  the SameSideAim steer (those stay once-per-frame, pass 1). */
    private void guardAndHold(boolean hold) {
        if (!plugin.isReplayDriven(ship) || plugin.mountedBodies.contains(ship) || !ship.isAlive()) {
            return; // not (or no longer) replay-driven / a seat-mounted module / dead
        }
        if (hold) {
            ship.setHoldFireOneFrame(true);
        }
        // Same side only: the per-weapon no-fire flags (foe bodies keep the ship-level hold posture only;
        // legacyoff: the group toggle-off is persistent — no per-sub-step re-assert owed).
        boolean holdWeapons = hold && ship.getOwner() == plugin.sideAuthority()
                && !PredictKnobConfig.SAME_SIDE_HOLD_LEGACY_OFF;
        List<WeaponAPI> weapons = plugin.weaponLists.weapons(ship); // per-frame cache, never rebuilt per sub-step
        if (weapons == null) {
            return;
        }
        boolean guarded = false;
        for (int w = 0; w < weapons.size(); w++) {
            WeaponAPI weapon = weapons.get(w);
            if (weapon == null) {
                continue;
            }
            if (weapon.isBeam()) {
                if (plugin.beamPres.relayedThisFrame(weapon)) {
                    // Relay-owned: the relay's force-fire is consumed by pass 1, so a wire-FIRING beam is re-asserted on
                    // the fast passes, BOTH sides, or the ship-level hold above charges it down on passes 2..k.
                    // Never no-fired (forceNoFire would beat the forceFire).
                    if (hold) {
                        weapon.setForceFireOneFrame(true);
                        relayReasserts++;
                    }
                } else if (!weapon.isDecorative()) {
                    weapon.setForceNoFireOneFrame(true); // THE GENERAL BEAM GUARD (class javadoc): no local fire decision
                    guarded = true;
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
        if (guarded) {
            beamGuardHolds++;
        }
    }

    /** Rides the {@code eventReplay-meter} line beside the leak remover's tokens. */
    static String meterToken() {
        String s = " predict_substep_hold_calls=" + calls
                + " predict_substep_hold_gate_reads=" + gateReads
                + " predict_substep_hold_faults=" + faults
                + " predict_substep_hold_relay_beams=" + relayReasserts
                + " predict_substep_hold_beam_guard=" + beamGuardHolds
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
