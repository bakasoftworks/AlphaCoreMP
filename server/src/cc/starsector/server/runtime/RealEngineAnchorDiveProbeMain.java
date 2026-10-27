package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.bridge.SeatRebind;
import cc.starsector.server.match.MatchDescriptors;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OFFLINE emergency-dive stall REPRO — the discriminator on the headless-ship substrate
 * for the live ghost defect. Live ground truth: PhaseAnchor's listener
 * intercepts any would-be lethal hull blow (sets HP=1, {@code emergencyDive=true}), then the
 * completion leg ({@code advance()}: {@code diveProgress += dt * chargeUpDur} → fade →
 * teleport (0,-1e6) → CFM removal) NEVER completes on our server — {@code diveProgress}
 * freezes and the hull ghosts forever. NO dive has ever completed here; the F1 sentinel now
 * suppresses the intercept for kill verbs, so the ONLY way to study the stall is this probe:
 * spawn the {@code phase_anchor} hull ({@code bastion:anchor} — the owner-exported HVD
 * Grendel), apply the same lethal {@code applyDamage} WITHOUT the sentinel, step, and dump
 * the script's own state per tick.
 *
 * <p><b>Discriminators</b> (the open micro-cause: chargeUpDur-0 vs listener-not-advanced vs
 * script-throw): (i) {@code getPhaseCloak().getChargeUpDur()} read directly (CSV
 * {@code phasecloak} row says charge-up 0.5 — MEASURED here, not assumed); (ii) a probe-owned
 * dynamic-proxy {@code AdvanceableListener} on the SAME ship counts engine-driven listener
 * advances; (iii) {@code PhaseAnchorScript}'s PUBLIC {@code emergencyDive}/{@code diveProgress}
 * fields are read per tick via {@code ShipAPI.getListeners}; (iv)
 * {@code hullDamageTakenMult} (the script pins it ×0 each completed advance) separates
 * "advance ran fully" from "advance threw mid-body" (the floaty block runs FIRST
 * while {@code diveProgress==0}); (v) when frozen, a ONE-SHOT manual reflective
 * {@code script.advance(dt)} either grows {@code diveProgress} (engine never calls it) or
 * throws the exact stall exception (stack printed).
 *
 * <p><b>F5 repro ladder</b> — the baseline probe COMPLETES the dive while the live
 * seat hull stalls at one advance; the four structural live-vs-probe deltas become
 * CUMULATIVE arms ({@code --arm baseline|s|w|k}, one per boot): <b>S</b> installs the DD1
 * {@code FastTimeReinjectShim} as the anchor's ShipAI (the #1 delta — live seat carries a
 * non-null ShipAI, the baseline probe nulls it); <b>W</b> = S + stages a live-fingerprint
 * {@code PilotIntentFrame} (pre-kill cloak bit-11 {@code 0x800}, post-kill retreat+cloak
 * {@code 0xA00} re-staged every tick — the replay-forever shape) through the REAL
 * {@code PersistentPilotIntentCombatServer$PilotIntentStep} per tick, fronting the AI-row
 * anchor behind seat wire id 1 via {@code RealEngineDemoRoster.recordBenchLiveHulls} (the
 * deliberate measurement-lane extension — the gated boot leaves {@code deployed=true}
 * with an EMPTY live map, and {@code bastion:anchor} seats wire id 1 occupancy-independent, so
 * {@code ownedShipIds=[1]} passes the {@code applyPilotIntent} owned-set gate); <b>K</b> = W +
 * kills via the exact live verb {@code SeatRebind.forceKillLiveShipForWireId(1L)}
 * ({@code allowDive=true}, no sentinel) instead of the inline sans-sentinel applyDamage. First
 * stalling arm names the stall driver; all-complete ⇒ the live walk rows decide.
 *
 * <p><b>W5 post-terminus reap watch</b> — every earlier run exited AT the teleport
 * terminus, so vanilla's SOLE remover ({@code CombatFleetManager.advance} →
 * {@code retreat(Ship)}, removal on both DFM branches) has never been observed in any
 * headless environment. {@code --post-terminus-frames} keeps stepping past the terminus (arm W
 * keeps re-staging the live replay frame) and samples the two live-unknown reaper gates per
 * frame: <b>G1</b> — does an {@code addFleet}-REGISTERED (= advanced-per-frame,
 * per the engine's frame loop) manager exist for the hull's raw owner (two-call
 * {@code getFleetManager(int)} identity — an unregistered owner mints a fresh DETACHED
 * throwaway per call); <b>G3</b> — the raw {@code ship.getOwner()} int (sentinel
 * class). The reap firing at all ⇒ {@code post_verdict=REAP_FIRED_OFFLINE}
 * (first headless removal verification); no reap ⇒ G3_OWNER_SENTINEL / G1_NO_ADVANCED_MANAGER
 * / GATES_TRUE_NO_REAP per the sampled gates.
 *
 * <p><b>Cloak-flip leg</b> ({@code --cloak-flip-watch true}, baseline arm only)
 * MEASURES whether vanilla's own VENTING emergency dive
 * re-trips the phase cloak IN→OUT, and so reaches its deactivate-sound call, on every ship
 * advance. The static chain it puts on the real engine: the script forces IN each advance;
 * the charge tracker's upkeep {@code raiseFlux} fails while
 * venting and
 * {@code canBeDeactivated} is true while venting ⇒ OUT +
 * {@code systemChargingDown} ⇒ the deactivate set's play
 * call. W1v holds the cloak until its own upkeep has built hard flux,
 * then (vent leg) issues VENT_FLUX until the flux tracker vents; the control leg
 * ({@code --vent-at-kill false}) dies phased and never vents. {@link CloakFlipWatch} carries the
 * two per-advance witnesses; the wrapper's {@code -CloakFlipLeg} boots both legs and prints the
 * verdict.
 *
 * <p>A MEASUREMENT main (never wired into the product serve loop); llvmpipe headless,
 * AWAY-legal, one repro arm per boot (the dive flag is a battle-global one-shot). Exit codes:
 * 0 = a discriminating verdict line printed (reproduction AND non-reproduction both count);
 * 2 = boot/step boundary captured; 1 = infrastructure (field/listener missing, arm not armed).
 *
 *
 */
public final class RealEngineAnchorDiveProbeMain {

    // ---- PINNED 0.98a-RC8 identities (probe-local pin convention; RealEnginePhaseProbeMain). ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIP_COMMAND_CLASS = "com.fs.starfarer.api.combat.ShipCommand";
    private static final String COMBAT_ENTITY_API = "com.fs.starfarer.api.combat.CombatEntityAPI";
    private static final String DAMAGE_TYPE_API = "com.fs.starfarer.api.combat.DamageType";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";
    private static final String PHASE_ANCHOR_SCRIPT =
            "com.fs.starfarer.api.impl.hullmods.PhaseAnchor$PhaseAnchorScript";
    private static final String ADVANCEABLE_LISTENER =
            "com.fs.starfarer.api.combat.listeners.AdvanceableListener";
    /** The vanilla battle-global one-shot key — asserted ABSENT pre-kill. */
    private static final String CAN_DIVE_KEY = "phaseAnchor_canDive";
    /** Teleport terminus: location.set(0,-1e6); detect well short of it. */
    private static final float TELEPORT_Y_THRESHOLD = -900_000f;

    private static final String P = "anchor-dive";

    private RealEngineAnchorDiveProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int frames = 900;          // 15 s post-kill: nominal dive completes in ~2 s + 1 s fade
        int phaseCapFrames = 240;  // press-toggle cap to reach phased before the kill
        boolean phaseFirst = true; // live ghost was PHASED at the kill — default-faithful
        float fixedDt = SimTickRate.fixedDt();
        String arm = "baseline";   // Cumulative ladder: baseline | s | w | k
        int heldBitsPostKill = 0xA00; // retreat bit-9 + cloak bit-11 — the live fingerprint
        int postTerminusFrames = 300; // 5 s past terminus — the reap is a ~next-frame act
        // The cloak-flip leg knobs (class javadoc); the
        // watch OFF leaves every leg above byte-for-byte as before.
        boolean cloakFlipWatch = false;
        boolean ventAtKill = false;
        float chargeFluxLevel = 0.25f; // hard-flux target in [0,1] of max flux before the vent/kill
        int chargeCapFrames = 600;
        int ventCapFrames = 30;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--frames": frames = Integer.parseInt(args[++i]); break;
                case "--phase-cap-frames": phaseCapFrames = Integer.parseInt(args[++i]); break;
                case "--phase-first": phaseFirst = Boolean.parseBoolean(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--arm": arm = args[++i].toLowerCase(Locale.ROOT); break;
                case "--held-bits-postkill": heldBitsPostKill = Integer.decode(args[++i]); break;
                case "--post-terminus-frames": postTerminusFrames = Integer.parseInt(args[++i]); break;
                case "--cloak-flip-watch": cloakFlipWatch = Boolean.parseBoolean(args[++i]); break;
                case "--vent-at-kill": ventAtKill = Boolean.parseBoolean(args[++i]); break;
                case "--charge-flux-level": chargeFluxLevel = Float.parseFloat(args[++i]); break;
                case "--charge-cap-frames": chargeCapFrames = Integer.parseInt(args[++i]); break;
                case "--vent-cap-frames": ventCapFrames = Integer.parseInt(args[++i]); break;
                default: break;
            }
        }
        boolean armS = "s".equals(arm) || "w".equals(arm) || "k".equals(arm);
        boolean armW = "w".equals(arm) || "k".equals(arm);
        boolean armK = "k".equals(arm);
        if (!armS && !"baseline".equals(arm)) {
            System.out.println(P + "-end verdict=INFRA reasons=unknown_arm_" + arm);
            System.exit(1);
            return;
        }
        if (cloakFlipWatch && armS) {
            // The flip leg measures VANILLA's dive — the
            // S/W/K arms add our shim/wire layers, so the combination is refused, never mixed.
            System.out.println(P + "-end verdict=INFRA reasons=cloak_flip_watch_needs_baseline_arm");
            System.exit(1);
            return;
        }
        System.out.println(P + "-begin arm=" + arm + " frames=" + frames
                + " cloak_flip_watch=" + cloakFlipWatch + " vent_at_kill=" + ventAtKill
                + " phase_first=" + phaseFirst
                + " phase_cap_frames=" + phaseCapFrames + " fixed_dt=" + fixedDt
                + " held_bits_postkill=0x" + Integer.toHexString(heldBitsPostKill)
                + " post_terminus_frames=" + postTerminusFrames
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        EngineCombatBinding binding;
        try {
            binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=BOOT_OR_RESOLVE_THREW boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        Rig rig = buildRig();

        long tick = 0;
        step.step(fixedDt, tick++); // drain the t=0 pending-add queue (factory contract)

        // ---- resolve the HVD grendel + kill-free the field (zero-hull idiom). ----
        List<?> ships = rig.ships();
        Object anchor = null;
        for (Object ship : ships) {
            if (MatchDescriptors.ANCHOR_GRENDEL_VARIANT_ID.equals(rig.variantId(ship))) {
                anchor = ship;
            }
        }
        if (anchor == null) {
            System.out.println(P + "-end verdict=INFRA reasons=anchor_grendel_not_fielded"
                    + " repo_ships=" + ships.size());
            System.exit(1);
            return;
        }
        int stripped = 0;
        for (Object s : ships) {
            if (rig.getShipAI.invoke(s) != null) {
                rig.setShipAI.invoke(s, new Object[]{null});
                stripped++;
            }
            for (Object group : (List<?>) rig.getWeaponGroupsCopy.invoke(s)) {
                if ((Boolean) group.getClass().getMethod("isAutofiring").invoke(group)) {
                    group.getClass().getMethod("toggleOff").invoke(group);
                }
            }
        }

        // ---- Arm S: the DD1 FastTimeReinjectShim as the anchor's ShipAI — the #1
        // live-vs-baseline delta. Installed AFTER the strip; the OBJECT overload
        // replaces, never refuses, and the shim's advance is a double no-op outside fast time
        // without a record, so a stall under S alone indicts the non-null-ShipAI
        // listener-dispatch interplay itself, not reinject content. ----
        boolean shimInstalled = false;
        if (armS) {
            shimInstalled = binding.installFastTimeReinjectShim(anchor);
            if (!shimInstalled) {
                System.out.println(P + "-end verdict=INFRA reasons=fast_time_shim_install_failed");
                System.exit(1);
                return;
            }
        }

        // ---- W1: pre-kill ground truth + the probe-owned advance counter. ----
        List<?> anchorScripts = rig.listenersOf(anchor, rig.phaseAnchorScriptClass);
        Object script = anchorScripts.size() == 1 ? anchorScripts.get(0) : null;
        Object cloak = rig.getPhaseCloak.invoke(anchor);
        float chargeUpDur = cloak == null ? Float.NaN
                : ((Number) cloak.getClass().getMethod("getChargeUpDur").invoke(cloak)).floatValue();
        AtomicLong proxyAdvances = new AtomicLong();
        Object counter = Proxy.newProxyInstance(rig.loader,
                new Class<?>[]{rig.advanceableListenerClass}, (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "advance": proxyAdvances.incrementAndGet(); return null;
                        case "toString": return "cc-anchor-dive-advance-counter";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == margs[0];
                        default: return null;
                    }
                });
        rig.addListener.invoke(anchor, counter);
        System.out.println(P + " w1 arm=" + arm + " shim_installed=" + shimInstalled
                + " repo_ships=" + ships.size() + " ai_stripped=" + stripped
                + " script_listeners=" + anchorScripts.size()
                + " phase_cloak=" + (cloak == null ? "null" : "present")
                + " charge_up_dur=" + f4(chargeUpDur)
                + " current_cr=" + f4(rig.floatOrNaN(anchor, "getCurrentCR"))
                + " hp=" + f1(rig.hp(anchor))
                + " show_floaty=" + rig.showFloatyOrDash(anchor)
                + " can_dive_key_present=" + rig.customData().containsKey(CAN_DIVE_KEY));
        if (script == null) {
            System.out.println(P + "-end verdict=INFRA reasons=phase_anchor_script_missing"
                    + " listeners=" + anchorScripts.size());
            System.exit(1);
            return;
        }

        // ---- Arm W: front the AI-row anchor behind seat wire id 1 (bastion:anchor
        // seats 1L occupancy-independent ⇒ ownedShipIds=[1]; the gated boot leaves deployed=true
        // + live map EMPTY, so the bench put makes wire 1 resolve to the anchor — a deliberate
        // measurement-lane mismatch, probe-only, never product), then drive staged frames
        // through the REAL PilotIntentStep (private nested; the live server's ctor values +
        // retry default 30 — its step body self-runs beginReinjectTick → apply staged →
        // baseStep.step, the one live pre-advance stack). ----
        CombatStepBoundary stepper = step;
        Object pilotStep = null;
        Method stageMethod = null;
        Method framesAppliedMethod = null;
        if (armW) {
            RealEngineDemoRoster.recordBenchLiveHulls(Map.of(1L, anchor));
            boolean mapActive = RealEngineDemoRoster.liveShipMapActive();
            boolean wire1Resolves = RealEngineDemoRoster.liveShipForWireIdOrNull(1L) == anchor;
            System.out.println(P + " w1w live_map_active=" + mapActive
                    + " wire1_resolves_anchor=" + wire1Resolves);
            if (!mapActive || !wire1Resolves) {
                System.out.println(P + "-end verdict=INFRA reasons=wire1_bench_map_not_authoritative"
                        + " live_map_active=" + mapActive);
                System.exit(1);
                return;
            }
            Class<?> stepClass = Class.forName(
                    "cc.starsector.server.runtime.PersistentPilotIntentCombatServer$PilotIntentStep");
            Constructor<?> ctor = stepClass.getDeclaredConstructor(CombatStepBoundary.class,
                    EngineCombatBinding.class, int.class, int.class, boolean.class);
            ctor.setAccessible(true);
            pilotStep = ctor.newInstance(step, binding, 30, 60, true);
            stageMethod = stepClass.getDeclaredMethod("stage", PilotIntentFrame.class);
            stageMethod.setAccessible(true);
            framesAppliedMethod = stepClass.getDeclaredMethod("framesApplied");
            framesAppliedMethod.setAccessible(true);
            stepper = (CombatStepBoundary) pilotStep;
        }

        // ---- W1c: floaty poke — the live-stall lead discriminator. The script's floaty block
        // runs FIRST on every advance while diveProgress==0, gated
        // ONLY by showFloaty() (false on this probe's fleet — may read true on a live
        // fleet-member seat hull): if addFloatingTextAlways NPEs on a headless engine, the
        // script's advance dies before its dive-progress accumulation EVERY frame — the exact
        // frozen-ghost fingerprint. One reflective poke, cosmetic-only, before the kill. ----
        String floatyPoke;
        try {
            Method addFloaty = null;
            for (Method m : rig.engine.getClass().getMethods()) {
                if (m.getName().equals("addFloatingTextAlways") && m.getParameterCount() == 11) {
                    addFloaty = m;
                }
            }
            if (addFloaty == null) {
                floatyPoke = "method_absent";
            } else {
                addFloaty.invoke(rig.engine, rig.getLocation.invoke(anchor), "cc_probe_floaty",
                        24f, java.awt.Color.white, anchor, 16f, 3.2f, 1f, 0f, 0f, 1f);
                floatyPoke = "ok";
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            floatyPoke = "threw_" + cause.getClass().getName();
            System.out.println(P + " w1c_floaty_poke_threw boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
        }
        System.out.println(P + " w1c floaty_poke=" + floatyPoke);

        // ---- W1b: phase the hull first (live-faithful: the ghost was phased at /suicide). ----
        boolean phasedAtKill = false;
        if (phaseFirst) {
            if (armW) {
                // cloak-mirror bit-11 (0x800) staged every tick through the real
                // PilotIntentStep replaces the pressUntilPhased loop — the apply lane's
                // mismatch law is strobe-safe (toggle only on isOn() disagreement).
                for (int f = 0; f < phaseCapFrames && !rig.phased(anchor); f++) {
                    float facing = rig.floatOrNaN(anchor, "getFacing");
                    Object aloc = rig.getLocation.invoke(anchor);
                    stageMethod.invoke(pilotStep, PilotIntentFrame.of(1L, (int) tick, facing,
                            0x800, false, 0, facing,
                            rig.vecX.getFloat(aloc), rig.vecY.getFloat(aloc)));
                    stepper.step(fixedDt, tick++);
                }
                int applied = (Integer) framesAppliedMethod.invoke(pilotStep);
                if (applied == 0) {
                    // The owned-set/resolver gate rejected every frame — the arm never armed;
                    // any verdict would be vacuous (the gate FIND exists exactly here).
                    System.out.println(P + "-end verdict=INFRA reasons=wire_applies_rejected"
                            + " frames_applied=0 tick=" + tick);
                    System.exit(1);
                    return;
                }
                System.out.println(P + " w1b_wire frames_applied=" + applied);
            } else {
                // Press only from IDLE with cooldown drained (RealEnginePhaseProbeMain
                // pressUntilPhased idiom — a per-frame press would toggle a charging cloak
                // back off).
                Method cloakState = cloak == null ? null : cloak.getClass().getMethod("getState");
                Method cloakCooldown = cloak == null ? null
                        : cloak.getClass().getMethod("getCooldownRemaining");
                for (int f = 0; f < phaseCapFrames && !rig.phased(anchor); f++) {
                    if (cloakState != null
                            && "IDLE".equals(String.valueOf(cloakState.invoke(cloak)))
                            && ((Number) cloakCooldown.invoke(cloak)).floatValue() <= 0f) {
                        rig.giveCommand.invoke(anchor, rig.cmdToggleCloak, null, 0);
                    }
                    step.step(fixedDt, tick++);
                }
            }
            phasedAtKill = rig.phased(anchor);
            System.out.println(P + " w1b tick=" + tick + " phased_at_kill=" + phasedAtKill
                    + " hp=" + f1(rig.hp(anchor)));
        }

        // ---- W1v: The flip leg's pre-kill posture, on
        // the engine's own paths only: keep the cloak up until ITS OWN upkeep (phasecloak
        // hardFlux=TRUE; PhaseAnchor zeroes the activation cost) has built
        // hard flux to --charge-flux-level — the floor a vent cannot drain while the cloak blocks
        // hard dissipation — then, vent leg only, the player's
        // VENT_FLUX command until the flux tracker vents. The
        // control leg never vents and dies phased. The carrier band pre-snap is a product layer
        // vanilla lacks: off for this leg (a no-op here — neither hull has launch bays). ----
        if (cloakFlipWatch) {
            step.withEngagedCarrierBandSnap(false);
            int chargeFrames = 0;
            while (chargeFrames < chargeCapFrames && rig.phased(anchor)
                    && rig.fluxLevel(anchor) < chargeFluxLevel) {
                step.step(fixedDt, tick++);
                chargeFrames++;
            }
            int ventCmdFrames = 0;
            if (ventAtKill) {
                while (ventCmdFrames < ventCapFrames && !rig.venting(anchor)) {
                    rig.giveCommand.invoke(anchor, rig.cmdVentFlux, null, 0);
                    step.step(fixedDt, tick++);
                    ventCmdFrames++;
                }
            }
            phasedAtKill = rig.phased(anchor);
            boolean ventingAtKill = rig.venting(anchor);
            System.out.println(P + " w1v tick=" + tick + " leg=" + (ventAtKill ? "vent" : "control")
                    + " charge_frames=" + chargeFrames + " vent_cmd_frames=" + ventCmdFrames
                    + " venting=" + ventingAtKill + " phased=" + phasedAtKill
                    + " cloak_state=" + rig.systemState(cloak)
                    + " hard=" + f1(rig.hardFlux(anchor)) + " curr=" + f1(rig.currFlux(anchor))
                    + " max=" + f1(rig.maxFlux(anchor)) + " flux_level=" + f4(rig.fluxLevel(anchor))
                    + " time_mult=" + f4(rig.timeMultOrNaN(anchor))
                    + " wall_clock_knob="
                    + System.getProperty("cc.server.pilotedCloakWallClock", "(unset)"));
            if (ventAtKill && !ventingAtKill) {
                System.out.println(P + " cloak-flip-leg leg=vent outcome=UNREACHED"
                        + " reason=vent_never_started_in_" + ventCmdFrames + "_frames");
                System.out.println(P + "-end verdict=CLOAK_FLIP_UNREACHED arm=" + arm);
                System.exit(0);
                return;
            }
        }

        // ---- W2: the lethal blow SANS sentinel — the pre-F1 kill-verb shape verbatim
        // (SeatRebind.forceKillHull minus the CAN_DIVE plant; same 1e6 HE
        // self-attributed bypass-shields applyDamage, same cosmetic-FX NPE swallow). ----
        if (rig.customData().containsKey(CAN_DIVE_KEY)) {
            System.out.println(P + "-end verdict=INFRA reasons=can_dive_key_pre_consumed");
            System.exit(1);
            return;
        }
        Object loc = rig.getLocation.invoke(anchor);
        float killFacing = rig.floatOrNaN(anchor, "getFacing");
        float killAimX = rig.vecX.getFloat(loc);
        float killAimY = rig.vecY.getFloat(loc);
        if (armK) {
            // ---- Arm K: the EXACT live kill verb — allowDive=true plants no sentinel,
            // gates only live-map+isAlive (no deployed read), shared forceKillHull body with the
            // cosmetic-NPE swallow inside. ----
            try {
                boolean killed = SeatRebind.forceKillLiveShipForWireId(1L);
                System.out.println(P + " w2 kill_verb=forceKillLiveShipForWireId killed=" + killed);
                if (!killed) {
                    System.out.println(P + "-end verdict=INFRA"
                            + " reasons=kill_verb_noop_wire1_not_live_or_dead");
                    System.exit(1);
                    return;
                }
            } catch (Throwable killThrow) {
                Throwable cause = unwrap(killThrow);
                System.out.println(P + "-end verdict=APPLY_DAMAGE_THREW boundary="
                        + cause.getClass().getName() + ": " + cause.getMessage());
                cause.printStackTrace(System.out);
                System.exit(2);
                return;
            }
        } else {
            Object point = rig.vec2Ctor.newInstance(killAimX, killAimY);
            try {
                rig.applyDamage.invoke(rig.engine, anchor, anchor, point, 1_000_000f,
                        rig.highExplosive, 0f, Boolean.TRUE, Boolean.FALSE, null, Boolean.FALSE);
            } catch (Throwable damageThrow) {
                Throwable cause = unwrap(damageThrow);
                boolean cosmetic = cause instanceof NullPointerException
                        && java.util.Arrays.stream(cause.getStackTrace())
                                .anyMatch(el -> el.getClassName().startsWith("com.fs.graphics"));
                if (!cosmetic) {
                    System.out.println(P + "-end verdict=APPLY_DAMAGE_THREW boundary="
                            + cause.getClass().getName() + ": " + cause.getMessage());
                    cause.printStackTrace(System.out);
                    System.exit(2);
                    return;
                }
                System.out.println(P + " w2 cosmetic_fx_npe_swallowed=true");
            }
        }
        boolean emergencyDive = rig.scriptBool(script, "emergencyDive");
        System.out.println(P + " w2 tick=" + tick + " emergency_dive=" + emergencyDive
                + " hp=" + f1(rig.hp(anchor))
                + " can_dive_key_consumed=" + rig.customData().containsKey(CAN_DIVE_KEY)
                + " dive_progress=" + f4(rig.scriptFloat(script, "diveProgress")));
        if (!emergencyDive) {
            // The intercept itself did not fire offline — a different (still discriminating)
            // finding; step a short tail so the death shape lands in the log, then report.
            for (int f = 0; f < 60; f++) {
                stepper.step(fixedDt, tick++);
            }
            System.out.println(P + "-end verdict=INTERCEPT_NOT_FIRED"
                    + " alive=" + rig.alive(anchor) + " hp=" + f1(rig.hp(anchor))
                    + " current_cr=" + f4(rig.floatOrNaN(anchor, "getCurrentCR")));
            System.exit(0);
            return;
        }

        // ---- W3: step + per-tick dump of the completion leg. ----
        long advancesAtKill = proxyAdvances.get();
        float lastProgress = rig.scriptFloat(script, "diveProgress");
        float maxProgress = lastProgress;
        boolean teleported = false;
        boolean removed = false;
        int stepThrows = 0;
        String stepThrowClass = null;
        int sinceLog = Integer.MAX_VALUE;
        int f = 0;
        // The flip watch is installed only AFTER the
        // intercept fired (the reorder must never touch the hull-damage listener order the
        // intercept reads) and is restored in the finally below — seed/restore-and-finally.
        CloakFlipWatch watch = null;
        if (cloakFlipWatch) {
            try {
                watch = CloakFlipWatch.install(rig, anchor, cloak);
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                System.out.println(P + "-end verdict=INFRA reasons=cloak_flip_watch_install_"
                        + cause.getClass().getSimpleName());
                System.exit(1);
                return;
            }
        }
        try {
            for (; f < frames; f++) {
                if (watch != null) {
                    watch.beginFrame();
                }
                if (armW) {
                    // the replay-forever shape — the live seat's LAST wire frame
                    // (retreat bit-9 + cloak bit-11, the fingerprint) re-staged EVERY tick
                    // with the kill-instant pose, exactly what the live staged map replays.
                    stageMethod.invoke(pilotStep, PilotIntentFrame.of(1L, (int) tick, killFacing,
                            heldBitsPostKill, false, 0, killFacing, killAimX, killAimY));
                }
                try {
                    stepper.step(fixedDt, tick++);
                } catch (Throwable t) {
                    Throwable cause = unwrap(t);
                    if (stepThrows++ == 0) {
                        stepThrowClass = cause.getClass().getName();
                        System.out.println(P + " w3_step_threw tick=" + tick + " boundary="
                                + stepThrowClass + ": " + cause.getMessage());
                        cause.printStackTrace(System.out);
                    }
                    continue;
                }
                float progress = rig.scriptFloat(script, "diveProgress");
                if (watch != null) {
                    watch.endFrame(f, progress);
                }
                boolean inRepo = rig.ships().contains(anchor);
                float locY = rig.vecY.getFloat(rig.getLocation.invoke(anchor));
                boolean edge = Math.abs(progress - lastProgress) > 0.005f;
                if (progress > maxProgress) {
                    maxProgress = progress;
                }
                if (f < 20 || f % 30 == 0 || edge || !inRepo || locY < TELEPORT_Y_THRESHOLD
                        || sinceLog >= 30) {
                    System.out.println(P + " w3 tick=" + tick + " f=" + f
                            + " dive_progress=" + f4(progress)
                            + " hp=" + f1(rig.hp(anchor))
                            + " phased=" + rig.phased(anchor)
                            + " retreating=" + rig.boolOrDash(anchor, "isRetreating")
                            + " alive=" + rig.alive(anchor)
                            + " in_repo=" + inRepo
                            + " loc_y=" + f1(locY)
                            + " alpha=" + f4(rig.floatOrNaN(anchor, "getExtraAlphaMult"))
                            + " hull_mult=" + rig.hullDamageTakenMultOrDash(anchor)
                            + " proxy_advances=" + (proxyAdvances.get() - advancesAtKill));
                    sinceLog = 0;
                } else {
                    sinceLog++;
                }
                lastProgress = progress;
                if (locY < TELEPORT_Y_THRESHOLD) {
                    teleported = true;
                    break;
                }
                if (!inRepo) {
                    removed = true;
                    break;
                }
            }
        } finally {
            if (watch != null) {
                watch.restore();
            }
        }
        long advancesDelta = proxyAdvances.get() - advancesAtKill;
        System.out.println(P + " w3_done tick=" + tick + " frames_run=" + f
                + " max_progress=" + f4(maxProgress) + " teleported=" + teleported
                + " removed=" + removed + " step_throws=" + stepThrows
                + " proxy_advances=" + advancesDelta
                + " frames_applied=" + (armW
                        ? String.valueOf((Integer) framesAppliedMethod.invoke(pilotStep)) : "-"));
        if (watch != null) {
            watch.printSummary(ventAtKill);
            System.out.println(deactivateSoundIdRow(rig, cloak));
        }

        // ---- W5: post-terminus reap watch — the class javadoc carries the G1/G3
        // law. One terminus snapshot, then step; every sampled gate is fail-soft so a reap
        // mid-sample can never crash the verdict. ----
        String postVerdict = "not_run";
        int framesToReap = -1;
        if (teleported && postTerminusFrames > 0) {
            int ownerAtTerminus = rig.owner(anchor);
            System.out.println(P + " w5_terminus tick=" + tick
                    + " owner=" + ownerAtTerminus
                    + " orig_owner=" + rig.intOrDash(anchor, "getOriginalOwner")
                    + " mgr0_attached=" + rig.mgrAttached(0)
                    + " mgr1_attached=" + rig.mgrAttached(1)
                    + " mgr_owner_attached=" + rig.mgrAttached(ownerAtTerminus)
                    + " dfm=" + rig.dfmOrDash(anchor));
            boolean reapFired = false;
            int w5Throws = 0;
            int lastOwner = ownerAtTerminus;
            boolean lastAttached = rig.mgrAttached(ownerAtTerminus);
            int quiet = Integer.MAX_VALUE;
            for (int pf = 0; pf < postTerminusFrames; pf++) {
                if (armW) {
                    stageMethod.invoke(pilotStep, PilotIntentFrame.of(1L, (int) tick, killFacing,
                            heldBitsPostKill, false, 0, killFacing, killAimX, killAimY));
                }
                try {
                    stepper.step(fixedDt, tick++);
                } catch (Throwable t) {
                    Throwable cause = unwrap(t);
                    if (w5Throws++ == 0) {
                        System.out.println(P + " w5_step_threw tick=" + tick + " boundary="
                                + cause.getClass().getName() + ": " + cause.getMessage());
                        cause.printStackTrace(System.out);
                    }
                    continue;
                }
                boolean inRepo = rig.ships().contains(anchor);
                int owner = rig.owner(anchor);
                boolean attached = rig.mgrAttached(owner);
                boolean edge = !inRepo || owner != lastOwner || attached != lastAttached;
                if (pf < 8 || pf % 30 == 0 || edge || quiet >= 30) {
                    System.out.println(P + " w5 tick=" + tick + " pf=" + pf
                            + " in_repo=" + inRepo
                            + " alive=" + rig.alive(anchor)
                            + " hp=" + f1(rig.hp(anchor))
                            + " retreating=" + rig.boolOrDash(anchor, "isRetreating")
                            + " loc_y=" + f1(rig.vecY.getFloat(rig.getLocation.invoke(anchor)))
                            + " owner=" + owner
                            + " mgr0_attached=" + rig.mgrAttached(0)
                            + " mgr_owner_attached=" + attached
                            + " dfm=" + rig.dfmOrDash(anchor));
                    quiet = 0;
                } else {
                    quiet++;
                }
                lastOwner = owner;
                lastAttached = attached;
                if (!inRepo) {
                    reapFired = true;
                    framesToReap = pf + 1;
                    break;
                }
            }
            int ownerFinal = rig.owner(anchor);
            boolean mgrOwnerFinal = rig.mgrAttached(ownerFinal);
            if (reapFired) {
                postVerdict = "REAP_FIRED_OFFLINE";
            } else if (ownerFinal != 0) {
                postVerdict = "G3_OWNER_SENTINEL";
            } else if (!mgrOwnerFinal) {
                postVerdict = "G1_NO_ADVANCED_MANAGER";
            } else {
                postVerdict = "GATES_TRUE_NO_REAP";
            }
            System.out.println(P + " w5_done tick=" + tick + " reap_fired=" + reapFired
                    + " frames_to_reap=" + framesToReap
                    + " owner_at_terminus=" + ownerAtTerminus
                    + " owner_final=" + ownerFinal
                    + " mgr0_attached_final=" + rig.mgrAttached(0)
                    + " mgr_owner_attached_final=" + mgrOwnerFinal
                    + " dfm_final=" + rig.dfmOrDash(anchor)
                    + " w5_throws=" + w5Throws
                    + " post_verdict=" + postVerdict);
        }

        // ---- W4 (frozen only): ONE manual script.advance — either the progress grows (the
        // engine never calls it) or the exact stall exception surfaces. Probe-lane poke on a
        // throwaway offline engine; never a product pattern. ----
        String manual = "not_run";
        if (!teleported && !removed && maxProgress < 0.005f) {
            try {
                rig.advanceMethod.invoke(script, fixedDt);
                float after = rig.scriptFloat(script, "diveProgress");
                manual = after > 0.0f ? "progress_grew_to_" + f4(after) : "ran_no_growth";
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                manual = "threw_" + cause.getClass().getName();
                System.out.println(P + " w4_manual_advance_threw boundary="
                        + cause.getClass().getName() + ": " + cause.getMessage());
                cause.printStackTrace(System.out);
            }
            System.out.println(P + " w4 manual_advance=" + manual
                    + " dive_progress=" + f4(rig.scriptFloat(script, "diveProgress")));
        }

        // ---- Verdict: one line the wrapper parses; every branch names its discriminant. ----
        String verdict;
        String cause;
        if (teleported || removed) {
            verdict = "DIVE_COMPLETED";
            cause = teleported ? "teleport_terminus_reached" : "removed_from_repo";
        } else if (maxProgress >= 0.005f) {
            verdict = "DIVE_STALLED_MID";
            cause = "progress_partial_max_" + f4(maxProgress);
        } else {
            verdict = "STALL_REPRODUCED";
            if (Float.isNaN(chargeUpDur) || chargeUpDur == 0.0f) {
                cause = "chargeup_zero_or_cloak_null";
            } else if (advancesDelta == 0) {
                cause = "listener_loop_suppressed";
            } else if (manual.startsWith("threw_")) {
                cause = "script_advance_" + manual;
            } else if (manual.startsWith("progress_grew")) {
                cause = "engine_skips_this_listener_manual_" + manual;
            } else {
                cause = "frozen_unexplained_manual_" + manual;
            }
        }
        System.out.println(P + "-end verdict=" + verdict + " arm=" + arm + " cause=" + cause
                + " post_verdict=" + postVerdict + " frames_to_reap=" + framesToReap
                + " shim_installed=" + shimInstalled
                + " phased_at_kill=" + phasedAtKill
                + " charge_up_dur=" + f4(chargeUpDur)
                + " max_progress=" + f4(maxProgress)
                + " proxy_advances=" + advancesDelta
                + " step_throws=" + stepThrows
                + (stepThrowClass == null ? "" : " step_throw_class=" + stepThrowClass)
                + " final_hp=" + f1(rig.hp(anchor))
                + " final_alive=" + rig.alive(anchor));
        System.exit(0);
    }

    // ---- reflective rig over the live engine (resolved once at boot) ----
    private static final class Rig {
        ClassLoader loader;
        Object engine;
        Class<?> shipClass;
        Class<?> shipApiClass;
        Class<?> phaseAnchorScriptClass;
        Class<?> advanceableListenerClass;
        Method getOwner;        // Ship.getOwner() — the reaper's gate is owner==mgr.owner
        Method getFleetManager; // CombatEngine.getFleetManager(int) — W5 two-call identity
        Method getObjects;
        Method repoGetList;
        Method isShipAlive;
        Method getVariant;
        Method getLocation;
        Method getPhaseCloak;
        Method getHitpoints;
        Method isPhased;
        Method getShipAI;
        Method setShipAI;
        Method getWeaponGroupsCopy;
        Method giveCommand;
        Method getListeners;
        Method addListener;
        Method getCustomData;
        Method applyDamage;
        Method advanceMethod; // AdvanceableListener.advance(float) — the W4 one-shot poke
        Object cmdToggleCloak;
        Object highExplosive;
        java.lang.reflect.Field vecX;
        java.lang.reflect.Field vecY;
        java.lang.reflect.Constructor<?> vec2Ctor;
        // Flip leg: public-API handles only (ShipAPI / FluxTrackerAPI / ShipSystemAPI).
        Method removeListener;
        Method getFluxTracker;
        Method fluxIsVenting;
        Method fluxGetHardFlux;
        Method fluxGetCurrFlux;
        Method fluxGetMaxFlux;
        Method fluxGetFluxLevel;
        Method systemGetState;
        Method systemGetSpecApi;
        Method specGetDeactivateSound;
        Object cmdVentFlux;

        List<?> ships() throws Exception {
            return (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        }

        boolean alive(Object ship) throws Exception {
            return (Boolean) isShipAlive.invoke(engine, ship);
        }

        String variantId(Object ship) {
            try {
                Object variant = getVariant.invoke(ship);
                if (variant == null) {
                    return "?";
                }
                Object id = variant.getClass().getMethod("getHullVariantId").invoke(variant);
                return id instanceof String ? (String) id : "?";
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "?";
            }
        }

        float hp(Object ship) throws Exception {
            return ((Number) getHitpoints.invoke(ship)).floatValue();
        }

        boolean phased(Object ship) throws Exception {
            return (Boolean) isPhased.invoke(ship);
        }

        List<?> listenersOf(Object ship, Class<?> cls) throws Exception {
            return (List<?>) getListeners.invoke(ship, cls);
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> customData() throws Exception {
            return (Map<Object, Object>) getCustomData.invoke(engine);
        }

        float scriptFloat(Object script, String field) throws Exception {
            return script.getClass().getField(field).getFloat(script);
        }

        boolean scriptBool(Object script, String field) throws Exception {
            return script.getClass().getField(field).getBoolean(script);
        }

        /** Best-effort no-arg float read; NaN when the method is absent or throws. */
        float floatOrNaN(Object ship, String method) {
            try {
                return ((Number) shipClass.getMethod(method).invoke(ship)).floatValue();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return Float.NaN;
            }
        }

        /** Best-effort no-arg boolean read; "-" when the method is absent or throws. */
        String boolOrDash(Object ship, String method) {
            try {
                return String.valueOf(shipClass.getMethod(method).invoke(ship));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "-";
            }
        }

        /** Best-effort no-arg int read; "-" when the method is absent or throws. */
        String intOrDash(Object ship, String method) {
            try {
                return String.valueOf(((Number) shipClass.getMethod(method).invoke(ship)).intValue());
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "-";
            }
        }

        int owner(Object ship) throws Exception {
            return ((Number) getOwner.invoke(ship)).intValue();
        }

        /**
         * W5 two-call identity (the {@link RealEngineDemoRoster#waveProbeSampleLine} idiom): a REGISTERED
         * manager is returned from the engine's addFleet list on BOTH calls; an unregistered
         * owner mints a fresh DETACHED throwaway per call. Attached ==
         * advanced every non-paused frame == reap-capable.
         */
        boolean mgrAttached(int owner) {
            try {
                return getFleetManager.invoke(engine, owner)
                        == getFleetManager.invoke(engine, owner);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        /** DFM presence on the raw-owner ATTACHED manager (G6, classification-only); "-" = unattached/unreadable. */
        String dfmOrDash(Object ship) {
            try {
                int own = owner(ship);
                if (!mgrAttached(own)) {
                    return "-";
                }
                Object mgr = getFleetManager.invoke(engine, own);
                Object dfm = mgr.getClass().getMethod("getDeployedFleetMember", shipApiClass)
                        .invoke(mgr, ship);
                return dfm == null ? "null" : "present";
            } catch (Exception e) {
                return "-";
            }
        }

        /** Telemetry: FluxTracker.showFloaty() (guards the script's floaty block), or "-". */
        String showFloatyOrDash(Object ship) {
            try {
                Object tracker = shipClass.getMethod("getFluxTracker").invoke(ship);
                return String.valueOf(
                        tracker.getClass().getMethod("showFloaty").invoke(tracker));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "-";
            }
        }

        /**
         * The script's hull-damage fingerprint: hullDamageTakenMult modified value is pinned 0.0 each
         * fully-completed advance — an unmodified 1.0 while frozen means the body threw before
         * reaching that pin (the floaty block runs first while diveProgress==0). "-" = unreadable.
         */
        String hullDamageTakenMultOrDash(Object ship) {
            try {
                Object stats = shipClass.getMethod("getMutableStats").invoke(ship);
                Object stat = stats.getClass().getMethod("getHullDamageTakenMult").invoke(stats);
                Object v = stat.getClass().getMethod("getModifiedValue").invoke(stat);
                return String.format(Locale.ROOT, "%.2f", ((Number) v).floatValue());
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "-";
            }
        }

        /** FluxTrackerAPI.isVenting() — the vent flag the cloak's upkeep raiseFlux fails on. */
        boolean venting(Object ship) throws Exception {
            return (Boolean) fluxIsVenting.invoke(getFluxTracker.invoke(ship));
        }

        float hardFlux(Object ship) throws Exception {
            return ((Number) fluxGetHardFlux.invoke(getFluxTracker.invoke(ship))).floatValue();
        }

        float currFlux(Object ship) throws Exception {
            return ((Number) fluxGetCurrFlux.invoke(getFluxTracker.invoke(ship))).floatValue();
        }

        float maxFlux(Object ship) throws Exception {
            return ((Number) fluxGetMaxFlux.invoke(getFluxTracker.invoke(ship))).floatValue();
        }

        float fluxLevel(Object ship) throws Exception {
            return ((Number) fluxGetFluxLevel.invoke(getFluxTracker.invoke(ship))).floatValue();
        }

        /** ShipSystemAPI.getState() enum name (IN / ACTIVE / OUT / COOLDOWN / IDLE); "null" = no cloak. */
        String systemState(Object system) throws Exception {
            return system == null ? "null" : String.valueOf(systemGetState.invoke(system));
        }

        /**
         * Ship-local time mult (MutableShipStats.getTimeMult modified value) — the engine runs
         * ceil(mult) Ship.advance passes per frame for it.
         * NaN = unreadable.
         */
        float timeMultOrNaN(Object ship) {
            try {
                Object stats = shipClass.getMethod("getMutableStats").invoke(ship);
                Object stat = stats.getClass().getMethod("getTimeMult").invoke(stats);
                Object v = stat.getClass().getMethod("getModifiedValue").invoke(stat);
                return ((Number) v).floatValue();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return Float.NaN;
            }
        }
    }

    /**
     * Per-advance cloak-flip witness on the vanilla dive.
     * Two independent counts per engine frame (one
     * {@code step} = one CombatEngine advance):
     *
     * <p><b>State flips.</b> Two probe-owned AdvanceableListener proxies bracket the engine's own
     * listeners — {@code [before, ...engine listeners in their original order..., after]}.
     * Ship.advance runs every AdvanceableListener first and the
     * cloak's own advance later, once in the main pass and once per
     * fast-time pass. {@code after} reads the
     * state the script just forced (IN) and arms {@code pending};
     * {@code before} at the NEXT pass (or the post-step read for a frame's last pass) reads what
     * the cloak's advance left: OUT ⇒ one IN→OUT flip inside that pass. A pass whose
     * {@code after} read is not IN is counted in {@code after_not_in} and never scored.
     *
     * <p><b>Sound-seam calls.</b> The cloak impl's {@code deacivateSoundSet} (sic; read at one site only)
     * is swapped, on THIS cloak only, for a fresh K holding the same
     * sounds in a {@link CountingList}; K's pick calls {@code isEmpty()}
     * exactly once per call, i.e. once per {@code systemChargingDown} that reaches the
     * sound play call. Same elements ⇒ the same pick and the same play call. When the
     * engine left the set null the seam is NOT installed
     * ({@code sound_seam=null_set}) and the sound count is inferred from the flips.
     *
     * <p>{@link #restore()} puts the original set back and removes both proxies (the W3 finally).
     */
    private static final class CloakFlipWatch {
        private final Rig rig;
        private final Object anchor;
        private final Object cloak;
        private Object before;
        private Object after;
        private java.lang.reflect.Field soundSetField;
        private Object originalSoundSet;
        private CountingList soundList;
        private String soundSeam = "unavailable";
        private String listenerOrder = "-";
        private boolean pending;
        private int subAdvances;
        private int flips;
        private int afterNotIn;
        private long soundAtFrameStart;
        private final StringBuilder seq = new StringBuilder();
        private final List<Integer> flipsPerFrame = new java.util.ArrayList<>();
        private final List<Integer> subsPerFrame = new java.util.ArrayList<>();
        private long flipsTotal;
        private long soundTotal;
        private long flipsOnVentFrames;
        private int soundEqFrames;
        private int ventFrames;
        private int afterNotInTotal;
        private int watchErrors;

        private CloakFlipWatch(Rig rig, Object anchor, Object cloak) {
            this.rig = rig;
            this.anchor = anchor;
            this.cloak = cloak;
        }

        /** Install both witnesses; on any failure undo what was installed, then rethrow. */
        static CloakFlipWatch install(Rig rig, Object anchor, Object cloak) throws Exception {
            if (cloak == null) {
                throw new IllegalStateException("anchor has no phase cloak");
            }
            CloakFlipWatch w = new CloakFlipWatch(rig, anchor, cloak);
            try {
                w.installListeners();
                w.installSoundSeam();
            } catch (Exception e) {
                w.restore();
                throw e;
            }
            System.out.println(P + " w3k_install listener_order=" + w.listenerOrder
                    + " sound_seam=" + w.soundSeam
                    + " sound_set_size=" + (w.soundList == null ? -1 : w.soundList.size())
                    + " cloak_state=" + rig.systemState(cloak));
            return w;
        }

        private void installListeners() throws Exception {
            before = proxy("cc-anchor-dive-flip-before", true);
            after = proxy("cc-anchor-dive-flip-after", false);
            List<?> engineOrder = new java.util.ArrayList<>(
                    rig.listenersOf(anchor, rig.advanceableListenerClass));
            for (Object l : engineOrder) {
                rig.removeListener.invoke(anchor, l);
            }
            rig.addListener.invoke(anchor, before);
            for (Object l : engineOrder) {
                rig.addListener.invoke(anchor, l);
            }
            rig.addListener.invoke(anchor, after);
            List<?> now = rig.listenersOf(anchor, rig.advanceableListenerClass);
            StringBuilder order = new StringBuilder();
            for (Object l : now) {
                order.append(order.length() == 0 ? "" : ",").append(l == before ? "probe_before"
                        : l == after ? "probe_after"
                        : Proxy.isProxyClass(l.getClass()) ? "probe_counter"
                        : l.getClass().getSimpleName());
            }
            listenerOrder = order.toString();
            if (now.size() != engineOrder.size() + 2 || now.get(0) != before
                    || now.get(now.size() - 1) != after
                    || !now.subList(1, now.size() - 1).equals(engineOrder)) {
                throw new IllegalStateException("listener reorder not as built: " + listenerOrder);
            }
        }

        private void installSoundSeam() throws Exception {
            java.lang.reflect.Field field = null;
            for (Class<?> c = cloak.getClass(); c != null && field == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field candidate : c.getDeclaredFields()) {
                    if (candidate.getName().equals("deacivateSoundSet")) {
                        field = candidate;
                    }
                }
            }
            if (field == null) {
                soundSeam = "field_absent";
                return;
            }
            field.setAccessible(true);
            Object original = field.get(cloak);
            if (original == null) {
                soundSeam = "null_set";
                return;
            }
            Class<?> setClass = field.getType();
            java.lang.reflect.Field listField = null;
            for (java.lang.reflect.Field candidate : setClass.getDeclaredFields()) {
                if (List.class.isAssignableFrom(candidate.getType())) {
                    if (listField != null) {
                        soundSeam = "set_shape_unexpected";
                        return;
                    }
                    listField = candidate;
                }
            }
            if (listField == null) {
                soundSeam = "set_shape_unexpected";
                return;
            }
            listField.setAccessible(true);
            Object counted = setClass.getConstructor().newInstance();
            CountingList list = new CountingList((java.util.Collection<?>) listField.get(original));
            listField.set(counted, list);
            soundSetField = field;
            originalSoundSet = original;
            field.set(cloak, counted);
            soundList = list;
            soundSeam = "wrapped";
        }

        private Object proxy(String name, boolean isBefore) {
            return Proxy.newProxyInstance(rig.loader, new Class<?>[]{rig.advanceableListenerClass},
                    (p, method, margs) -> {
                        switch (method.getName()) {
                            case "advance":
                                if (isBefore) {
                                    onBefore();
                                } else {
                                    onAfter();
                                }
                                return null;
                            case "toString": return name;
                            case "hashCode": return System.identityHashCode(p);
                            case "equals": return p == margs[0];
                            default: return null;
                        }
                    });
        }

        /** Start of a pass: score the PREVIOUS pass of this frame (pending ⇒ it began forced IN). */
        private void onBefore() {
            if (!pending) {
                return;
            }
            pending = false;
            try {
                score(rig.systemState(cloak));
            } catch (Throwable t) {
                countError(t);
            }
        }

        /** After the script's force: one ship pass; arm the flip check iff the cloak reads IN. */
        private void onAfter() {
            subAdvances++;
            try {
                if ("IN".equals(rig.systemState(cloak))) {
                    pending = true;
                } else {
                    afterNotIn++;
                }
            } catch (Throwable t) {
                countError(t);
            }
        }

        private void score(String endState) {
            seq.append(code(endState));
            if ("OUT".equals(endState)) {
                flips++;
            }
        }

        private void countError(Throwable t) {
            if (watchErrors++ == 0) {
                System.out.println(P + " w3k_watch_error first=" + unwrap(t).getClass().getName());
            }
        }

        void beginFrame() {
            pending = false;
            subAdvances = 0;
            flips = 0;
            afterNotIn = 0;
            seq.setLength(0);
            soundAtFrameStart = soundList == null ? 0L : soundList.emptyChecks;
        }

        void endFrame(int frame, float progress) throws Exception {
            String end = rig.systemState(cloak);
            if (pending) {
                pending = false;
                score(end);
            }
            long sound = soundList == null ? -1L : soundList.emptyChecks - soundAtFrameStart;
            boolean venting = rig.venting(anchor);
            flipsPerFrame.add(flips);
            subsPerFrame.add(subAdvances);
            flipsTotal += flips;
            afterNotInTotal += afterNotIn;
            if (sound >= 0L) {
                soundTotal += sound;
                if (sound == flips) {
                    soundEqFrames++;
                }
            }
            if (venting) {
                ventFrames++;
                flipsOnVentFrames += flips;
            }
            System.out.println(P + " w3v f=" + frame + " ship_advances=" + subAdvances
                    + " flips=" + flips + " sound_calls=" + (sound < 0L ? "-" : String.valueOf(sound))
                    + " pass_end_states=" + (seq.length() == 0 ? "-" : seq.toString())
                    + " end_state=" + end + " after_not_in=" + afterNotIn
                    + " venting=" + venting
                    + " hard=" + f1(rig.hardFlux(anchor)) + " curr=" + f1(rig.currFlux(anchor))
                    + " flux_level=" + f4(rig.fluxLevel(anchor))
                    + " time_mult=" + f4(rig.timeMultOrNaN(anchor))
                    + " dive_progress=" + f4(progress));
        }

        /**
         * The per-leg line the wrapper parses. Vent-leg outcome: UNREACHED when no dive frame
         * vented (the vent did not survive into the dive); RETRIGGER when the median frame flips
         * at least once and the leg flipped at least twice; else NO_RETRIGGER. Control: CONTROL.
         */
        void printSummary(boolean ventLeg) {
            int n = flipsPerFrame.size();
            String outcome;
            String reason = "-";
            double flipsMedian = median(flipsPerFrame);
            if (!ventLeg) {
                outcome = "CONTROL";
            } else if (n == 0) {
                outcome = "UNREACHED";
                reason = "no_dive_advance_recorded";
            } else if (ventFrames == 0) {
                outcome = "UNREACHED";
                reason = "vent_ended_before_the_first_dive_advance";
            } else if (flipsTotal >= 2 && flipsMedian >= 1.0) {
                outcome = "RETRIGGER";
            } else {
                outcome = "NO_RETRIGGER";
            }
            System.out.println(P + " cloak-flip-leg leg=" + (ventLeg ? "vent" : "control")
                    + " outcome=" + outcome + " reason=" + reason
                    + " advances=" + n + " flips_total=" + flipsTotal
                    + " flips_min=" + min(flipsPerFrame)
                    + " flips_median=" + String.format(Locale.ROOT, "%.1f", flipsMedian)
                    + " flips_max=" + max(flipsPerFrame)
                    + " ship_advances_min=" + min(subsPerFrame)
                    + " ship_advances_median="
                    + String.format(Locale.ROOT, "%.1f", median(subsPerFrame))
                    + " ship_advances_max=" + max(subsPerFrame)
                    + " vent_frames=" + ventFrames + " flips_on_vent_frames=" + flipsOnVentFrames
                    + " sound_seam=" + soundSeam
                    + " sound_calls_total=" + (soundList == null ? "-" : String.valueOf(soundTotal))
                    + " sound_eq_flips_frames=" + (soundList == null ? "-" : soundEqFrames + "/" + n)
                    + " after_not_in=" + afterNotInTotal + " watch_errors=" + watchErrors);
        }

        /** Undo both installs (idempotent; each step fail-soft and counted). */
        void restore() {
            try {
                if (soundSetField != null) {
                    soundSetField.set(cloak, originalSoundSet);
                    soundSetField = null;
                }
            } catch (Throwable t) {
                countError(t);
            }
            for (Object p : new Object[]{before, after}) {
                if (p == null) {
                    continue;
                }
                try {
                    rig.removeListener.invoke(anchor, p);
                } catch (Throwable t) {
                    countError(t);
                }
            }
            before = null;
            after = null;
        }

        private static char code(String state) {
            switch (state) {
                case "IN": return 'I';
                case "ACTIVE": return 'A';
                case "OUT": return 'O';
                case "COOLDOWN": return 'C';
                case "IDLE": return 'D';
                default: return '?';
            }
        }

        private static int min(List<Integer> xs) {
            return xs.isEmpty() ? -1 : java.util.Collections.min(xs);
        }

        private static int max(List<Integer> xs) {
            return xs.isEmpty() ? -1 : java.util.Collections.max(xs);
        }

        private static double median(List<Integer> xs) {
            if (xs.isEmpty()) {
                return -1.0;
            }
            List<Integer> sorted = new java.util.ArrayList<>(xs);
            java.util.Collections.sort(sorted);
            int n = sorted.size();
            return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }
    }

    /**
     * The sound set's list with a call meter: K's pick reads {@code isEmpty()} once per call,
     * so {@link #emptyChecks} = deactivate-sound play calls reached.
     *
     */
    private static final class CountingList extends java.util.ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        long emptyChecks;

        CountingList(java.util.Collection<?> source) {
            super(source);
        }

        @Override
        public boolean isEmpty() {
            emptyChecks++;
            return super.isEmpty();
        }
    }

    /**
     * The sound id the cloak's own spec plays on charge-down (ShipSystemSpecAPI
     * getDeactivateSound; literal fallback named in {@code id_source}). No file I/O here — the
     * probe class sits under the sim-residency gate; the wrapper resolves the id through the
     * install's {@code data/config/sounds.json} and reads the .ogg length itself.
     *
     */
    private static String deactivateSoundIdRow(Rig rig, Object cloak) {
        String soundId = "system_phase_cloak_deactivate";
        String idSource = "literal";
        try {
            Object id = rig.specGetDeactivateSound.invoke(rig.systemGetSpecApi.invoke(cloak));
            if (id instanceof String) {
                soundId = (String) id;
                idSource = "spec";
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            idSource = "literal_spec_read_" + unwrap(e).getClass().getSimpleName();
        }
        return P + " deactivate-sound sound_id=" + soundId + " id_source=" + idSource;
    }

    private static Rig buildRig() throws Exception {
        ClassLoader loader = RealEngineAnchorDiveProbeMain.class.getClassLoader();
        Rig rig = new Rig();
        rig.loader = loader;
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        rig.shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
        rig.shipApiClass = shipApiClass;
        Class<?> shipCommandClass = Class.forName(SHIP_COMMAND_CLASS, false, loader);
        Class<?> entityApi = Class.forName(COMBAT_ENTITY_API, false, loader);
        Class<?> damageTypeClass = Class.forName(DAMAGE_TYPE_API, false, loader);
        Class<?> vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        rig.phaseAnchorScriptClass = Class.forName(PHASE_ANCHOR_SCRIPT, false, loader);
        rig.advanceableListenerClass = Class.forName(ADVANCEABLE_LISTENER, false, loader);
        rig.engine = engineClass.getMethod("getInstance").invoke(null);
        if (rig.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        rig.getObjects = engineClass.getMethod("getObjects");
        rig.repoGetList = rig.getObjects.invoke(rig.engine).getClass()
                .getMethod("getList", Class.class);
        rig.isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
        rig.getCustomData = engineClass.getMethod("getCustomData");
        rig.applyDamage = engineClass.getMethod("applyDamage",
                Object.class, entityApi, vec2Class, float.class, damageTypeClass,
                float.class, boolean.class, boolean.class, Object.class, boolean.class);
        rig.getOwner = rig.shipClass.getMethod("getOwner");
        rig.getFleetManager = engineClass.getMethod("getFleetManager", int.class);
        rig.getVariant = rig.shipClass.getMethod("getVariant");
        rig.getLocation = rig.shipClass.getMethod("getLocation");
        rig.getPhaseCloak = rig.shipClass.getMethod("getPhaseCloak");
        rig.getHitpoints = rig.shipClass.getMethod("getHitpoints");
        rig.isPhased = rig.shipClass.getMethod("isPhased");
        rig.getShipAI = rig.shipClass.getMethod("getShipAI");
        rig.setShipAI = findOneArg(rig.shipClass, "setShipAI");
        rig.getWeaponGroupsCopy = rig.shipClass.getMethod("getWeaponGroupsCopy");
        rig.giveCommand = rig.shipClass.getMethod("giveCommand",
                shipCommandClass, Object.class, int.class);
        rig.getListeners = rig.shipClass.getMethod("getListeners", Class.class);
        rig.addListener = rig.shipClass.getMethod("addListener", Object.class);
        rig.advanceMethod = rig.advanceableListenerClass.getMethod("advance", float.class);
        rig.cmdToggleCloak = enumConst(shipCommandClass, "TOGGLE_SHIELD_OR_PHASE_CLOAK");
        rig.highExplosive = damageTypeClass.getField("HIGH_EXPLOSIVE").get(null);
        rig.vecX = vec2Class.getField("x");
        rig.vecY = vec2Class.getField("y");
        rig.vec2Ctor = vec2Class.getConstructor(float.class, float.class);
        // The flip leg's handles, public API only.
        Class<?> fluxApi = Class.forName("com.fs.starfarer.api.combat.FluxTrackerAPI", false, loader);
        Class<?> systemApi = Class.forName("com.fs.starfarer.api.combat.ShipSystemAPI", false, loader);
        Class<?> systemSpecApi =
                Class.forName("com.fs.starfarer.api.combat.ShipSystemSpecAPI", false, loader);
        rig.removeListener = rig.shipClass.getMethod("removeListener", Object.class);
        rig.getFluxTracker = rig.shipClass.getMethod("getFluxTracker");
        rig.fluxIsVenting = fluxApi.getMethod("isVenting");
        rig.fluxGetHardFlux = fluxApi.getMethod("getHardFlux");
        rig.fluxGetCurrFlux = fluxApi.getMethod("getCurrFlux");
        rig.fluxGetMaxFlux = fluxApi.getMethod("getMaxFlux");
        rig.fluxGetFluxLevel = fluxApi.getMethod("getFluxLevel");
        rig.systemGetState = systemApi.getMethod("getState");
        rig.systemGetSpecApi = systemApi.getMethod("getSpecAPI");
        rig.specGetDeactivateSound = systemSpecApi.getMethod("getDeactivateSound");
        rig.cmdVentFlux = enumConst(shipCommandClass, "VENT_FLUX");
        return rig;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConst(Class<?> cls, String name) {
        return Enum.valueOf((Class<Enum>) cls, name);
    }

    private static Method findOneArg(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("no one-arg " + name + " on " + cls.getName());
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }

    private static String f4(float f) {
        return String.format(Locale.ROOT, "%.4f", f);
    }
}
