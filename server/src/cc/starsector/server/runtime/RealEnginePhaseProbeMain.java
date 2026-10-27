package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipMotionView;
import cc.starsector.server.adapter.ShipPhaseCloakView;
import cc.starsector.server.bridge.EngineBackedCombatStateHashBoundary;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.match.MatchDescriptors;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The phase PROBE: does the real engine's phase-cloak fast-time machinery run
 * HEADLESS under the server's own gated boot — AI-flown cloak cycles, per-ship {@code timeMult}
 * ~3.0 visible through the B2 motion lane, PPT/flux draining at the multiplied rate (proof the
 * {@code advanceInner} extra passes ran), the offline-lane state hash moving with the live
 * timeMult, and the at-cap / vent forced-unphase edges (the phase-timescale facts, measured)?
 *
 * <p>Boot = the serveNPeer shape: {@code bastion:1:1} descriptor ({@code -Dcc.match.descriptor})
 * + {@link RealEngineCombatBindingFactory#create(java.util.SortedSet)} with EMPTY occupancy —
 * under the dynamic-seats rule the seat hull stays ABSENT and the field is 1 certified Mudskipper (Hellbore,
 * side 0, engine AI) vs 1 {@code grendel_Hypervelocity} (side 1, engine AI): the first-ever
 * cc_client-ENABLED registry resolve of the Grendel (Move 0 parsed file bytes with mods emptied).
 *
 * <p><b>Windows</b> (a MEASUREMENT main, never wired into the product serve loop):
 * <ul>
 *   <li><b>A — passive AI window:</b> step; the Grendel's BasicShipAI flies the cloak itself
 *       (its shieldAI slot). Collect V2 phased up/down edges, V3 live timeMult while ACTIVE,
 *       V5 hash differential, O7 OUT-window ticks. Early-exits once the essentials are seen.</li>
 *   <li><b>C — active edge window:</b> strip ALL ship AIs + autofire (kill-free, the zero-hull
 *       idiom), then drive the cloak through the engine's own faithful control verb
 *       ({@code ShipAPI.giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK / VENT_FLUX)}): C1 unphased
 *       baseline slopes, C2 hold-phased-to-flux-cap (V4 multiplied PPT/hard-flux slopes + V6a
 *       at-cap forced unphase), C3 re-phase then vent (V6b RE-MEASURE, informational —
 *       per-tick press posture + telemetry), C4 DD1 shim fast-time coverage
 *       (V7, GATING — control-vs-shim phased ACCELERATE displacement slope ratio ≥ 2.0). The
 *       overlap-blocks-dephase edge is NOT attempted (needs orbits the probe cannot arrange).</li>
 * </ul>
 *
 * <p><b>V5 differential:</b> replicates the {@link EngineBackedCombatStateHashBoundary} motion
 * sub-hash probe-locally (same FNV mix), asserts the replica matches
 * {@code lastMotionSubHash()} every sampled tick (proving the replica is exact), then recomputes
 * with every ship's timeMult forced to 1.0f — on a phased tick the forced hash MUST differ,
 * proving the B2 live value is hash-visible at the value level, not just structurally.
 *
 * <p><b>NEVER calls {@code getPlayerShip()}</b> (the pre-roster fallback fabricates a
 * shuttle that NULLS the combat map). All per-ship reads go through the repository list.
 *
 * <p>Exit codes: 0 = verdict {@code PHASE_HEADLESS_PROVEN} (V1–V5 + V6a + V7 all pass; V6b is
 * informational); 2 = a boundary captured (boot/resolve threw — the fail-closed variant resolve
 * names a missing Grendel — or a step threw mid-window; stack printed); 1 = FAILED verdict rows
 * or infrastructure.
 *
 */
public final class RealEnginePhaseProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineZeroHullProbeMain. ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIP_COMMAND_CLASS = "com.fs.starfarer.api.combat.ShipCommand";

    private static final String P = "phase-probe";

    // Replicated from EngineBackedCombatStateHashBoundary for the V5 differential ONLY — the
    // replica is verified against lastMotionSubHash() every sampled tick, so drift between the
    // two is caught as a probe failure, never silently wrong.
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** Grendel cloak upkeep in HARD flux per ship-local second (fraction × base max flux). */
    private static final float GRENDEL_UPKEEP_PER_SHIP_SECOND = 480f;

    private RealEnginePhaseProbeMain() {
    }

    // ---- reflective rig over the live engine (resolved once at boot) ----
    private static final class Rig {
        Object engine;
        Class<?> shipClass;
        Method getObjects;
        Method repoGetList;
        Method isShipAlive;
        Method getVariant;
        Method getPeakTimeRemaining;
        Method getFluxTracker;
        Method giveCommand;
        Method getShipAI;
        Method setShipAI;
        Method getWeaponGroupsCopy;
        Method getBlockedCommands; // nullable: telemetry-only, resolved best-effort
        Object cmdToggleCloak;
        Object cmdVentFlux;
        Object cmdAccelerate;
        Object cmdDecelerate;
        Method fluxGetHard;
        Method fluxGetMax;
        Method fluxIsVenting;
        Method fluxIsOverloaded;

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

        float ppt(Object ship) throws Exception {
            return ((Number) getPeakTimeRemaining.invoke(ship)).floatValue();
        }

        float hardFlux(Object ship) throws Exception {
            Object tracker = getFluxTracker.invoke(ship);
            if (fluxGetHard == null) {
                fluxGetHard = tracker.getClass().getMethod("getHardFlux");
                fluxGetMax = tracker.getClass().getMethod("getMaxFlux");
                fluxIsVenting = tracker.getClass().getMethod("isVenting");
                fluxIsOverloaded = tracker.getClass().getMethod("isOverloaded");
            }
            return ((Number) fluxGetHard.invoke(tracker)).floatValue();
        }

        float maxFlux(Object ship) throws Exception {
            hardFlux(ship); // ensures tracker methods resolved
            return ((Number) fluxGetMax.invoke(getFluxTracker.invoke(ship))).floatValue();
        }

        boolean venting(Object ship) throws Exception {
            hardFlux(ship);
            return (Boolean) fluxIsVenting.invoke(getFluxTracker.invoke(ship));
        }

        boolean overloaded(Object ship) throws Exception {
            hardFlux(ship);
            return (Boolean) fluxIsOverloaded.invoke(getFluxTracker.invoke(ship));
        }

        /** Telemetry-only: the ship's blocked-command set, or "-" when unreadable. */
        String blockedCommandsOrDash(Object ship) {
            if (getBlockedCommands == null) {
                return "-";
            }
            try {
                Object blocked = getBlockedCommands.invoke(ship);
                return blocked == null ? "null" : blocked.toString().replace(" ", "");
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "-";
            }
        }

        void pressToggleCloak(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdToggleCloak, null, 0);
        }

        void pressVent(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdVentFlux, null, 0);
        }

        void pressAccelerate(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdAccelerate, null, 0);
        }

        void pressDecelerate(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdDecelerate, null, 0);
        }
    }

    // ---- per-tick sample of the Grendel through the B2/B3 binding lanes ----
    private static final class Sample {
        ShipPhaseCloakView cloak;   // null when the cloak tier degraded this tick
        float timeMult = Float.NaN; // grendel motion.timeMult() (B2 live lane)
        boolean hashUsedMotion;
        boolean replicaMatched;     // probe FNV replica == lastMotionSubHash()
        boolean forcedDiffers;      // forced-1.0 replica != real sub-hash (timeMult hash-visible)
    }

    public static void main(String[] args) throws Exception {
        int windowAFrames = 7200;    // 120 s of AI-flown phase at 60 fps (early-exits when done)
        int baselineFrames = 300;    // 5 s unphased slope baseline (C1)
        int phasedCapFrames = 900;   // 15 s cap for hold-phased-to-flux-cap (C2; ~3.5 s expected)
        int settleCapFrames = 1800;  // 30 s cap for cooldown + hard-flux dissipation waits
        float fixedDt = SimTickRate.fixedDt();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--window-a-frames": windowAFrames = Integer.parseInt(args[++i]); break;
                case "--baseline-frames": baselineFrames = Integer.parseInt(args[++i]); break;
                case "--phased-cap-frames": phasedCapFrames = Integer.parseInt(args[++i]); break;
                case "--settle-cap-frames": settleCapFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin window_a_frames=" + windowAFrames
                + " baseline_frames=" + baselineFrames + " phased_cap_frames=" + phasedCapFrames
                + " settle_cap_frames=" + settleCapFrames + " fixed_dt=" + fixedDt
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        // ---- Boot: the serveNPeer gated shape. A throw here IS a finding — the fail-closed
        // variant resolve names the boundary if the registry lane misses the Grendel (V1). ----
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
        EngineBackedCombatStateHashBoundary hashBoundary =
                new EngineBackedCombatStateHashBoundary(binding);

        Rig rig = buildRig();

        long tick = 0;
        // One drain step first: the t=0 roster deploy parks ships on the engine's pending-add
        // queue until the first advance (factory: "pending-add until first advance"), same as
        // the fly-in lane's drain step.
        step.step(fixedDt, tick++);

        // ---- V1: the Grendel resolved from the LIVE registry and deployed with its cloak. ----
        List<?> bootShips = rig.ships();
        int grendelIdx = -1;
        for (int i = 0; i < bootShips.size(); i++) {
            if (MatchDescriptors.BASTION_GRENDEL_VARIANT_ID.equals(rig.variantId(bootShips.get(i)))) {
                grendelIdx = i;
            }
        }
        List<ShipPhaseCloakView> bootCloaks = binding.shipPhaseCloakStatesOrNull();
        boolean v1 = bootShips.size() == 2 && grendelIdx >= 0
                && bootCloaks != null && bootCloaks.size() == 1
                && "phasecloak".equals(bootCloaks.get(0).cloakId())
                && bootCloaks.get(0).shipId() == grendelIdx + 1;
        System.out.println(P + " v1 repo_ships=" + bootShips.size() + " grendel_idx=" + grendelIdx
                + " cloak_entries=" + (bootCloaks == null ? "null" : bootCloaks.size())
                + " cloak_id=" + (bootCloaks == null || bootCloaks.isEmpty()
                        ? "-" : bootCloaks.get(0).cloakId())
                + " pass=" + v1);
        if (!v1) {
            System.out.println(P + "-end verdict=FAILED reasons=v1_grendel_deploy");
            System.exit(1);
            return;
        }
        Object grendel = bootShips.get(grendelIdx);
        float maxFlux = rig.maxFlux(grendel);
        System.out.println(P + " grendel max_flux=" + f1(maxFlux)
                + " ppt=" + f1(rig.ppt(grendel)) + " hard_flux=" + f1(rig.hardFlux(grendel)));

        // ---- Window A: passive AI window. The engine flies phase for free. ----
        int upEdges = 0;
        int downEdges = 0;
        int phasedTicks = 0;
        int o7OutPhasedTicks = 0;
        float v3MaxTimeMult = Float.NaN;
        int v3IdleExactViolations = 0;
        int hashMotionViolations = 0;
        int replicaMismatches = 0;
        boolean v5DifferentialSeen = false;
        boolean grendelDied = false;
        boolean prevPhased = false;
        int aTicksRun = 0;
        try {
            for (int f = 0; f < windowAFrames; f++) {
                step.step(fixedDt, tick++);
                aTicksRun++;
                Sample s = sample(binding, hashBoundary, world);
                if (!s.hashUsedMotion) {
                    hashMotionViolations++;
                }
                if (!s.replicaMatched) {
                    replicaMismatches++;
                }
                if (s.cloak == null) {
                    continue; // degraded read this tick; counted implicitly by phased coverage
                }
                boolean phased = s.cloak.phased();
                if (phased && !prevPhased) {
                    upEdges++;
                    System.out.println(P + " a_edge tick=" + tick + " dir=up state="
                            + s.cloak.state() + " effect=" + f2(s.cloak.effectLevel()));
                }
                if (!phased && prevPhased) {
                    downEdges++;
                    System.out.println(P + " a_edge tick=" + tick + " dir=down state="
                            + s.cloak.state() + " effect=" + f2(s.cloak.effectLevel()));
                }
                prevPhased = phased;
                if (phased) {
                    phasedTicks++;
                    if (Float.isNaN(v3MaxTimeMult) || s.timeMult > v3MaxTimeMult) {
                        v3MaxTimeMult = s.timeMult;
                    }
                    if (s.forcedDiffers && s.replicaMatched) {
                        v5DifferentialSeen = true;
                    }
                    if ("OUT".equals(s.cloak.state())) {
                        o7OutPhasedTicks++;
                    }
                } else if ("IDLE".equals(s.cloak.state()) && s.timeMult != 1.0f) {
                    v3IdleExactViolations++;
                }
                if (f % 240 == 0) {
                    System.out.println(P + " a tick=" + tick + " state=" + s.cloak.state()
                            + " phased=" + phased + " effect=" + f2(s.cloak.effectLevel())
                            + " time_mult=" + f2(s.timeMult)
                            + " hard_flux=" + f1(rig.hardFlux(grendel))
                            + " ppt=" + f1(rig.ppt(grendel))
                            + " alive=" + rig.alive(grendel));
                }
                if (!rig.alive(grendel)) {
                    grendelDied = true;
                    break;
                }
                boolean essentials = upEdges >= 2 && downEdges >= 2
                        && !Float.isNaN(v3MaxTimeMult) && v3MaxTimeMult >= 2.5f
                        && v5DifferentialSeen;
                if (essentials && f >= 600) {
                    System.out.println(P + " a_early_exit tick=" + tick + " f=" + f);
                    break;
                }
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=PHASE_STEP_THREW tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        boolean v2 = upEdges >= 1 && downEdges >= 1 && !grendelDied;
        System.out.println(P + " a_done tick=" + tick + " ticks_run=" + aTicksRun
                + " up_edges=" + upEdges + " down_edges=" + downEdges
                + " phased_ticks=" + phasedTicks + " o7_out_phased_ticks=" + o7OutPhasedTicks
                + " grendel_died=" + grendelDied + " v2=" + v2);
        if (grendelDied) {
            System.out.println(P + "-end verdict=FAILED reasons=grendel_died_window_a"
                    + " up_edges=" + upEdges + " down_edges=" + downEdges);
            System.exit(1);
            return;
        }

        // ---- Window C: strip AI + autofire (kill-free), drive the edges ourselves. ----
        int stripped = 0;
        int autofireOff = 0;
        for (Object s : rig.ships()) {
            if (rig.getShipAI.invoke(s) != null) {
                rig.setShipAI.invoke(s, new Object[]{null});
                stripped++;
            }
            for (Object group : (List<?>) rig.getWeaponGroupsCopy.invoke(s)) {
                if ((Boolean) group.getClass().getMethod("isAutofiring").invoke(group)) {
                    group.getClass().getMethod("toggleOff").invoke(group);
                    autofireOff++;
                }
            }
        }
        System.out.println(P + " c_begin tick=" + tick + " ai_stripped=" + stripped
                + " autofire_groups_off=" + autofireOff);

        List<Float> baselinePptSlopes = new ArrayList<Float>();
        List<Float> phasedPptSlopes = new ArrayList<Float>();
        List<Float> phasedHardFluxSlopes = new ArrayList<Float>();
        boolean v6aForcedUnphase = false;
        float v6aFluxLevelAtDrop = Float.NaN;
        boolean v6bVentStarted = false;
        boolean v6bForcedOut = false;
        String v6bSkipReason = null;
        float v7ControlSlope = Float.NaN;
        float v7ShimSlope = Float.NaN;
        float v7ControlRampV20 = Float.NaN;
        float v7ShimRampV20 = Float.NaN;
        int v7ControlTicks = 0;
        int v7ShimTicks = 0;
        boolean v7ShimInstalled = false;
        long v7Reinjects = 0;

        try {
            // C1: settle unphased (press out if the AI left it phased), then baseline slopes.
            tick = settleUnphased(rig, grendel, binding, step, fixedDt, tick, settleCapFrames);
            float prevPpt = rig.ppt(grendel);
            for (int f = 0; f < baselineFrames; f++) {
                step.step(fixedDt, tick++);
                float ppt = rig.ppt(grendel);
                baselinePptSlopes.add(prevPpt - ppt);
                prevPpt = ppt;
            }
            System.out.println(P + " c1_done tick=" + tick
                    + " baseline_ppt_slope_med=" + f4(median(baselinePptSlopes))
                    + " hard_flux=" + f1(rig.hardFlux(grendel)));

            // C2: engage, hold with ZERO presses, ride hard flux to the cap (V4 slopes + V6a).
            long engagedAtTick = pressUntilPhased(rig, grendel, binding, step, fixedDt, tick, 240);
            tick = Math.abs(engagedAtTick);
            if (engagedAtTick < 0) {
                System.out.println(P + " c2 could_not_engage tick=" + tick);
            } else {
                prevPpt = rig.ppt(grendel);
                float prevHard = rig.hardFlux(grendel);
                boolean prevPhasedC = true;
                for (int f = 0; f < phasedCapFrames; f++) {
                    step.step(fixedDt, tick++);
                    Sample s = sample(binding, hashBoundary, world);
                    if (!s.replicaMatched) {
                        replicaMismatches++;
                    }
                    float ppt = rig.ppt(grendel);
                    float hard = rig.hardFlux(grendel);
                    boolean phased = s.cloak != null && s.cloak.phased();
                    if (phased && prevPhasedC) {
                        phasedPptSlopes.add(prevPpt - ppt);
                        phasedHardFluxSlopes.add(hard - prevHard);
                        if (Float.isNaN(v3MaxTimeMult) || s.timeMult > v3MaxTimeMult) {
                            v3MaxTimeMult = s.timeMult;
                        }
                        if (s.forcedDiffers && s.replicaMatched) {
                            v5DifferentialSeen = true;
                        }
                    }
                    if (!phased && prevPhasedC) {
                        // No probe press since engage: this drop is the ENGINE's forced OUT.
                        v6aFluxLevelAtDrop = prevHard / maxFlux;
                        v6aForcedUnphase = v6aFluxLevelAtDrop >= 0.85f;
                        System.out.println(P + " c2_forced_out tick=" + tick
                                + " hard_flux_level_at_drop=" + f2(v6aFluxLevelAtDrop)
                                + " state=" + (s.cloak == null ? "-" : s.cloak.state()));
                        break;
                    }
                    prevPpt = ppt;
                    prevHard = hard;
                    prevPhasedC = phased;
                    if (f % 120 == 0) {
                        System.out.println(P + " c2 tick=" + tick + " phased=" + phased
                                + " time_mult=" + f2(s.timeMult) + " hard_flux=" + f1(hard)
                                + " flux_level=" + f2(hard / maxFlux) + " ppt=" + f1(ppt));
                    }
                }
            }
            System.out.println(P + " c2_done tick=" + tick
                    + " phased_ppt_slope_med=" + f4(median(phasedPptSlopes))
                    + " phased_hard_flux_slope_med=" + f4(median(phasedHardFluxSlopes))
                    + " v6a_forced_unphase=" + v6aForcedUnphase
                    + " flux_level_at_drop=" + f2(v6aFluxLevelAtDrop));

            // C3 (V6b RE-MEASURE, informational): the Move-3 recon
            // OVERTURNED the old "vent is a toggle" belief (a second press is a strict no-op)
            // and found NO phase gate on ventFlux at code level — yet the 2026-07-17
            // Move-1 runs measured giveCommand(VENT_FLUX)-while-phased INERT on this AI-less
            // non-playerShip lane, while a playtester OBSERVED IN PLAY that player-lane vent works
            // and force-drops the cloak. Re-measure with a per-tick press posture (strobe-safe:
            // press-while-venting is an engine no-op) + full per-tick telemetry so the probe-lane
            // artifact is EXPLAINED, not just observed. NOT gated — seat-lane truth comes from
            // the serve lane's shim-covered apply path and the M3-B8 WATCH.
            tick = settleUnphased(rig, grendel, binding, step, fixedDt, tick, settleCapFrames);
            int waited = 0;
            while (rig.hardFlux(grendel) / maxFlux > 0.70f && waited < settleCapFrames) {
                step.step(fixedDt, tick++);
                waited++;
            }
            if (rig.hardFlux(grendel) / maxFlux > 0.70f) {
                v6bSkipReason = "hard_flux_never_dissipated";
            } else {
                long reengaged = pressUntilPhased(rig, grendel, binding, step, fixedDt, tick, 240);
                tick = Math.abs(reengaged);
                if (reengaged < 0) {
                    v6bSkipReason = "could_not_reengage";
                } else {
                    for (int f = 0; f < 30; f++) {
                        step.step(fixedDt, tick++); // brief steady phased hold
                    }
                    int presses = 0;
                    String prevSig = "";
                    int sinceLog = Integer.MAX_VALUE;
                    for (int f = 0; f < 240; f++) {
                        List<ShipPhaseCloakView> cloaks = binding.shipPhaseCloakStatesOrNull();
                        ShipPhaseCloakView cloak =
                                cloaks != null && cloaks.size() == 1 ? cloaks.get(0) : null;
                        boolean phased = cloak != null && cloak.phased();
                        String stateStr = cloak == null ? "-" : cloak.state();
                        boolean isOn = "IN".equals(stateStr) || "ACTIVE".equals(stateStr);
                        boolean venting = rig.venting(grendel);
                        boolean overloaded = rig.overloaded(grendel);
                        if (venting && !v6bVentStarted) {
                            v6bVentStarted = true;
                            System.out.println(P + " c3_vent_started tick=" + tick
                                    + " presses=" + presses + " phased=" + phased);
                        }
                        if (v6bVentStarted && venting && !phased && !v6bForcedOut) {
                            v6bForcedOut = true;
                            System.out.println(P + " c3_vent_forced_out tick=" + tick
                                    + " presses=" + presses);
                        }
                        String sig = stateStr + "|" + venting + "|" + overloaded + "|" + phased;
                        if (!sig.equals(prevSig) || sinceLog >= 30) {
                            System.out.println(P + " c3_remeasure tick=" + tick
                                    + " state=" + stateStr + " is_on=" + isOn
                                    + " phased=" + phased + " venting=" + venting
                                    + " overloaded=" + overloaded
                                    + " flux_level=" + f2(rig.hardFlux(grendel) / maxFlux)
                                    + " blocked=" + rig.blockedCommandsOrDash(grendel)
                                    + " presses=" + presses);
                            sinceLog = 0;
                            prevSig = sig;
                        } else {
                            sinceLog++;
                        }
                        if (v6bForcedOut) {
                            break;
                        }
                        if (!venting && phased) {
                            rig.pressVent(grendel);
                            presses++;
                        }
                        step.step(fixedDt, tick++);
                    }
                    System.out.println(P + " c3_remeasure_done tick=" + tick
                            + " presses=" + presses + " vent_started=" + v6bVentStarted
                            + " forced_out=" + v6bForcedOut);
                }
            }
            System.out.println(P + " c3_done tick=" + tick
                    + " v6b_remeasure=" + v6bRemeasureVerdict(v6bSkipReason, v6bVentStarted,
                            v6bForcedOut)
                    + (v6bSkipReason == null ? "" : " skip_reason=" + v6bSkipReason));

            // C4 (V7, VERDICT-GATING): does the
            // DD1 shim close the fast-time control-coverage gap? Two IDENTICAL phased
            // ACCELERATE-held windows: control FIRST (no shim — pass-1-only giveCommand
            // coverage, the starved fly-in seat reality), then the shim window (install +
            // per-tick reinject record; the engine's own ai.advance slot re-issues the set
            // during passes 2+). PASS: ramp-velocity ratio >= 2.0 + at least one live reinject
            // (nominal ~3x = thrust duty 3/3 vs 1/3; observable + why the displacement median
            // cannot discriminate: phasedAccelWindow javadoc).
            SlopeResult control = phasedAccelWindow(rig, grendel, grendelIdx, binding, step,
                    fixedDt, tick, phasedCapFrames, settleCapFrames, maxFlux, false, "control");
            tick = control.endTick;
            v7ControlSlope = control.slope;
            v7ControlRampV20 = control.rampV20;
            v7ControlTicks = control.phasedTicks;
            v7ShimInstalled = binding.installFastTimeReinjectShim(grendel);
            System.out.println(P + " c4_shim_install installed=" + v7ShimInstalled
                    + " registered=" + binding.fastTimeShimInstalled(grendel));
            long reinjectsBefore = binding.fastTimeReinjects();
            SlopeResult shim = phasedAccelWindow(rig, grendel, grendelIdx, binding, step,
                    fixedDt, tick, phasedCapFrames, settleCapFrames, maxFlux, true, "shim");
            tick = shim.endTick;
            v7ShimSlope = shim.slope;
            v7ShimRampV20 = shim.rampV20;
            v7ShimTicks = shim.phasedTicks;
            v7Reinjects = binding.fastTimeReinjects() - reinjectsBefore;
            System.out.println(P + " c4_done tick=" + tick
                    + " control_slope=" + f4(v7ControlSlope) + " control_ticks=" + v7ControlTicks
                    + " shim_slope=" + f4(v7ShimSlope) + " shim_ticks=" + v7ShimTicks
                    + " reinjects=" + v7Reinjects);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=PHASE_STEP_THREW tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        // ---- Verdicts. ----
        boolean v3 = !Float.isNaN(v3MaxTimeMult) && v3MaxTimeMult >= 2.5f
                && v3IdleExactViolations == 0;
        float basePpt = median(baselinePptSlopes);
        float phasedPpt = median(phasedPptSlopes);
        float pptRatio = basePpt > 0f ? phasedPpt / basePpt : Float.NaN;
        float hardSlopePerSecond = median(phasedHardFluxSlopes) / fixedDt;
        float fluxRatio = hardSlopePerSecond / GRENDEL_UPKEEP_PER_SHIP_SECOND;
        boolean v4 = (!Float.isNaN(pptRatio) && pptRatio >= 2.0f)
                || (!phasedHardFluxSlopes.isEmpty() && fluxRatio >= 2.0f);
        boolean v5 = replicaMismatches == 0 && hashMotionViolations == 0 && v5DifferentialSeen;
        // V6b is REPORTED, not gated (re-measure verdict string; see the C3 comment).
        // V7 IS gated: the discriminating observable is the velocity RAMP (v at the
        // 20th phased tick from rest), NOT the displacement median — extra passes integrate
        // position from current velocity in the engine loop itself (command-independent), so
        // starvation shows only as ~1/3 thrust duty during the ramp (see phasedAccelWindow
        // javadoc). Ratio floor 2.0 (nominal ~3x), an ABSOLUTE ramp floor so a 0/0 window
        // cannot pass, plus >=1 live reinject proving the extra passes entered the shim.
        // The median-slope ratio stays reported (informational; measured insensitive at 1.04).
        float v7Ratio = !Float.isNaN(v7ShimSlope) && !Float.isNaN(v7ControlSlope)
                ? v7ShimSlope / Math.max(v7ControlSlope, 0.001f) : Float.NaN;
        float v7RampRatio = !Float.isNaN(v7ShimRampV20) && !Float.isNaN(v7ControlRampV20)
                ? v7ShimRampV20 / Math.max(v7ControlRampV20, 0.001f) : Float.NaN;
        boolean v7 = v7ShimInstalled && v7Reinjects > 0 && v7ControlTicks >= 60
                && v7ShimTicks >= 60 && !Float.isNaN(v7RampRatio) && v7RampRatio >= 2.0f
                && v7ShimRampV20 >= 5.0f;
        boolean pass = v2 && v3 && v4 && v5 && v6aForcedUnphase && v7;
        StringBuilder reasons = new StringBuilder();
        if (!v2) reasons.append("v2_ai_cycles,");
        if (!v3) reasons.append("v3_time_mult,");
        if (!v4) reasons.append("v4_multiplied_rates,");
        if (!v5) reasons.append("v5_hash_lane,");
        if (!v6aForcedUnphase) reasons.append("v6a_at_cap,");
        if (!v7) reasons.append("v7_shim_coverage,");
        System.out.println(P + "-end verdict=" + (pass ? "PHASE_HEADLESS_PROVEN" : "FAILED")
                + (reasons.length() == 0 ? "" : " reasons=" + reasons)
                + " v1_deploy=" + v1
                + " v2_edges=" + upEdges + "/" + downEdges
                + " v3_max_time_mult=" + f2(v3MaxTimeMult)
                + " v3_idle_exact_violations=" + v3IdleExactViolations
                + " v4_ppt_ratio=" + f2(pptRatio)
                + " v4_hard_flux_per_s=" + f1(hardSlopePerSecond)
                + " v4_flux_ratio=" + f2(fluxRatio)
                + " v5_replica_mismatches=" + replicaMismatches
                + " v5_hash_motion_violations=" + hashMotionViolations
                + " v5_differential_seen=" + v5DifferentialSeen
                + " v6a_at_cap_forced_unphase=" + v6aForcedUnphase
                + " v6a_flux_level_at_drop=" + f2(v6aFluxLevelAtDrop)
                + " v6b_remeasure=" + v6bRemeasureVerdict(v6bSkipReason, v6bVentStarted,
                        v6bForcedOut)
                + " v7_shim_coverage=" + v7
                + " v7_ramp_control=" + f4(v7ControlRampV20)
                + " v7_ramp_shim=" + f4(v7ShimRampV20)
                + " v7_ramp_ratio=" + f2(v7RampRatio)
                + " v7_slope_control=" + f4(v7ControlSlope)
                + " v7_slope_shim=" + f4(v7ShimSlope)
                + " v7_ratio=" + f2(v7Ratio)
                + " v7_reinjects=" + v7Reinjects
                + " v7_shim_installed=" + v7ShimInstalled
                + " v6c_overlap=NOT_ATTEMPTED"
                + " o7_out_phased_ticks=" + o7OutPhasedTicks
                + " phased_ticks_a=" + phasedTicks);
        System.exit(pass ? 0 : 1);
    }

    /** The V6b re-measure verdict string surfaced on the c3_done + -end lines (informational). */
    private static String v6bRemeasureVerdict(String skipReason, boolean ventStarted,
            boolean forcedOut) {
        if (skipReason != null) {
            return "skipped_" + skipReason;
        }
        if (forcedOut) {
            return "vent_started_forced_out";
        }
        return ventStarted ? "vent_started_no_forced_out" : "inert_probe_lane";
    }

    /** One C4 displacement window's result (median per-tick displacement while phased). */
    private static final class SlopeResult {
        float slope = Float.NaN;
        float rampV20 = Float.NaN; // velocity magnitude (su/s) at the 20th phased tick from rest
        int phasedTicks;
        long endTick;
    }

    /** First-N phased ticks whose velocity series is printed for ramp adjudication. */
    private static final int RAMP_SERIES_TICKS = 48;

    /**
     * One C4 phased ACCELERATE-held displacement window (control and shim runs are IDENTICAL
     * except {@code shimRecord}): settle unphased, DECELERATE-held dissipate to ≤10% hard flux
     * (windows start from ~rest with a full flux budget — the phased hold self-terminates at the
     * V6a cap, so both windows get the same phased duration), engage the cloak, then per tick:
     * (shim run only) {@code beginReinjectTick()} + {@code recordFastTimeReinject(ACCELERATE)}
     * exactly at the serve loop's cadence, pass-1 ACCELERATE press, step, sample displacement.
     * The window ends at the engine's forced OUT (or the cap).
     *
     * <p>The DISCRIMINATING observable is the early velocity RAMP, not the
     * displacement median. CombatEngine's extra passes integrate position from CURRENT velocity
     * in the ENGINE loop itself (command-independent), and each
     * {@code Ship.advance} pass CONSUMES the command list (that is why the engine
     * capture/reinjects the AI-less playerShip's commands per extra pass).
     * So pass-1-only starvation slows only velocity CHANGE (~1/3 effective
     * thrust duty): a starved ship still coasts at full speed on passes 2+, and once past the
     * ramp both configurations ride the identical flux-decay speed curve (measured:
     * median ratio 1.04 with the shim provably live at 559 reinjects/284 ticks). Both windows
     * start from rest, so {@code rampV20} — velocity at the 20th phased tick — isolates the
     * thrust-duty ratio (~3x nominal) before either configuration can reach max speed. K=20,
     * not earlier: the first ~8 phased ticks are the cloak-engage transition (timeMult ramps
     * 1→3 with the cloak effect, so the duty differential is not yet expressed — measured
     * ratio at K=10 was 1.78 vs 2.63 at K=20 with the linear-region per-tick Δv ratio at
     * exactly 3.0: control 2.67 su/s/tick vs shim 8.0). The printed
     * {@link #RAMP_SERIES_TICKS}-tick series backs the adjudication if the accel assumption
     * breaks. The median slope stays reported as informational.
     */
    private static SlopeResult phasedAccelWindow(Rig rig, Object grendel, int grendelIdx,
            EngineCombatBinding binding, EngineBackedCombatStepBoundary step, float fixedDt,
            long tick, int capFrames, int settleCapFrames, float maxFlux, boolean shimRecord,
            String label) throws Exception {
        SlopeResult result = new SlopeResult();
        tick = settleUnphased(rig, grendel, binding, step, fixedDt, tick, settleCapFrames);
        int waited = 0;
        while (rig.hardFlux(grendel) / maxFlux > 0.10f && waited++ < settleCapFrames) {
            rig.pressDecelerate(grendel); // also brings the prior window's velocity to rest
            step.step(fixedDt, tick++);
        }
        long engaged = pressUntilPhased(rig, grendel, binding, step, fixedDt, tick, 240);
        tick = Math.abs(engaged);
        result.endTick = tick;
        if (engaged < 0) {
            System.out.println(P + " c4_" + label + " could_not_engage tick=" + tick);
            return result;
        }
        List<ShipMotionView> motions = binding.shipMotionsOrNull();
        int gIdx = grendelMotionIdx(binding.shipPhaseCloakStatesOrNull(), grendelIdx);
        if (motions == null || gIdx >= motions.size()) {
            // Name the collapse mode — null = a per-ship read failed (binding logs the
            // one-shot cause), a small size = a ship left the repository.
            System.out.println(P + " c4_" + label + " no_motion_lane tick=" + tick
                    + (motions == null ? " motions=null" : " motions_size=" + motions.size())
                    + " grendel_idx=" + gIdx);
            return result;
        }
        float prevX = motions.get(gIdx).locX();
        float prevY = motions.get(gIdx).locY();
        List<Float> perTick = new ArrayList<Float>();
        List<Float> rampSpeeds = new ArrayList<Float>();
        boolean skipReported = false;
        for (int f = 0; f < capFrames; f++) {
            if (shimRecord) {
                binding.beginReinjectTick();
                binding.recordFastTimeReinject(grendel, PilotIntentFrame.MOVE_ACCELERATE,
                        false, false, false, 0f, 0f);
            }
            rig.pressAccelerate(grendel);
            step.step(fixedDt, tick++);
            motions = binding.shipMotionsOrNull();
            List<ShipPhaseCloakView> cloaks = binding.shipPhaseCloakStatesOrNull();
            boolean phased = cloaks != null && cloaks.size() == 1 && cloaks.get(0).phased();
            gIdx = grendelMotionIdx(cloaks, gIdx);
            if (motions == null || gIdx >= motions.size()) {
                if (!skipReported) {
                    skipReported = true;
                    System.out.println(P + " c4_" + label + " motion_skip tick=" + tick + " f=" + f
                            + (motions == null ? " motions=null" : " motions_size=" + motions.size()));
                }
                continue;
            }
            ShipMotionView m = motions.get(gIdx);
            float dx = m.locX() - prevX;
            float dy = m.locY() - prevY;
            prevX = m.locX();
            prevY = m.locY();
            if (!phased) {
                System.out.println(P + " c4_" + label + " forced_out tick=" + tick + " f=" + f);
                break;
            }
            perTick.add((float) Math.hypot(dx, dy));
            if (perTick.size() <= RAMP_SERIES_TICKS) {
                float speed = (float) Math.hypot(m.velX(), m.velY());
                rampSpeeds.add(speed);
                if (perTick.size() == 20) {
                    result.rampV20 = speed;
                }
            }
            if (f % 60 == 0) {
                System.out.println(P + " c4_" + label + " tick=" + tick + " f=" + f
                        + " disp=" + f4((float) Math.hypot(dx, dy))
                        + " flux_level=" + f2(rig.hardFlux(grendel) / maxFlux));
            }
        }
        result.slope = median(perTick);
        result.phasedTicks = perTick.size();
        result.endTick = tick;
        StringBuilder ramp = new StringBuilder();
        for (Float v : rampSpeeds) {
            if (ramp.length() > 0) {
                ramp.append(';');
            }
            ramp.append(String.format(java.util.Locale.ROOT, "%.1f", v));
        }
        System.out.println(P + " c4_" + label + "_ramp n=" + rampSpeeds.size()
                + " v20=" + f4(result.rampV20) + " series=" + ramp);
        System.out.println(P + " c4_" + label + "_done tick=" + tick
                + " phased_ticks=" + result.phasedTicks + " slope_med=" + f4(result.slope));
        return result;
    }

    /**
     * The fix for the {@code no_motion_lane} INSTRUMENT-FAIL: the Mudskipper leaves the
     * repository mid-run (time-correlated ~tick 2000+, engine logs nothing headless), so the
     * deploy-time motion index goes stale. The grendel is the ONLY cloaked hull in bastion:1:1
     * and the cloak lane's {@code shipId} is repo order at read time, so its CURRENT motion
     * index is {@code cloaks[0].shipId()-1} whenever exactly one cloak entry exists; otherwise
     * keep the last known index (the size guard still fails the read closed).
     */
    private static int grendelMotionIdx(List<ShipPhaseCloakView> cloaks, int lastIdx) {
        if (cloaks != null && cloaks.size() == 1) {
            return (int) cloaks.get(0).shipId() - 1;
        }
        return lastIdx;
    }

    /** One post-step read of the Grendel through the B2/B3 lanes + the V5 hash differential. */
    private static Sample sample(EngineCombatBinding binding,
            EngineBackedCombatStateHashBoundary hashBoundary, EngineBackedCombatWorldState world) {
        Sample s = new Sample();
        hashBoundary.stateHash(world);
        s.hashUsedMotion = hashBoundary.lastHashUsedMotion();
        long realSub = hashBoundary.lastMotionSubHash();
        List<ShipMotionView> motions = binding.shipMotionsOrNull();
        List<ShipPhaseCloakView> cloaks = binding.shipPhaseCloakStatesOrNull();
        if (cloaks != null && cloaks.size() == 1) {
            s.cloak = cloaks.get(0);
            int idx = (int) s.cloak.shipId() - 1;
            if (motions != null && idx >= 0 && idx < motions.size()) {
                s.timeMult = motions.get(idx).timeMult();
            }
        }
        if (motions != null && s.hashUsedMotion) {
            long replica = motionSubHash(motions, false);
            long forced = motionSubHash(motions, true);
            s.replicaMatched = replica == realSub;
            s.forcedDiffers = forced != realSub;
        } else {
            s.replicaMatched = true; // no motion component this tick: nothing to verify
            s.forcedDiffers = false;
        }
        return s;
    }

    /** Probe-local replica of the boundary's motion sub-hash; forceOne pins timeMult at 1.0f. */
    private static long motionSubHash(List<ShipMotionView> motions, boolean forceOne) {
        long m = FNV_OFFSET;
        m = mix(m, motions.size());
        for (ShipMotionView motion : motions) {
            m = mix(m, Long.hashCode(motion.shipId()));
            m = mix(m, Float.floatToIntBits(motion.locX()));
            m = mix(m, Float.floatToIntBits(motion.locY()));
            m = mix(m, Float.floatToIntBits(motion.velX()));
            m = mix(m, Float.floatToIntBits(motion.velY()));
            m = mix(m, Float.floatToIntBits(motion.facing()));
            m = mix(m, Float.floatToIntBits(motion.angularVelocity()));
            m = mix(m, Float.floatToIntBits(motion.stepAmount()));
            m = mix(m, Float.floatToIntBits(forceOne ? 1.0f : motion.timeMult()));
        }
        return m;
    }

    private static long mix(long h, int value) {
        h ^= (value & 0xffffffffL);
        h *= FNV_PRIME;
        return h;
    }

    /**
     * Step until the Grendel is unphased with the cloak settled (IDLE, cooldown drained),
     * pressing the toggle only while the cloak is engaged (IN/ACTIVE — the engine's own
     * de-phase verb). Returns the advanced tick; gives up at {@code capFrames} (the caller's
     * next press-until guard then reports the stuck state).
     */
    private static long settleUnphased(Rig rig, Object grendel, EngineCombatBinding binding,
            EngineBackedCombatStepBoundary step, float fixedDt, long tick, int capFrames)
            throws Exception {
        for (int f = 0; f < capFrames; f++) {
            List<ShipPhaseCloakView> cloaks = binding.shipPhaseCloakStatesOrNull();
            ShipPhaseCloakView cloak = cloaks != null && cloaks.size() == 1 ? cloaks.get(0) : null;
            if (cloak != null && !cloak.phased() && "IDLE".equals(cloak.state())
                    && cloak.cooldownRemaining() <= 0f) {
                return tick;
            }
            if (cloak != null && ("IN".equals(cloak.state()) || "ACTIVE".equals(cloak.state()))) {
                rig.pressToggleCloak(grendel);
            }
            step.step(fixedDt, tick++);
        }
        return tick;
    }

    /**
     * Press the toggle each tick the cloak sits IDLE until the ship reads phased. Returns the
     * advanced tick, NEGATED when the cap elapsed without engaging (the flux-capacity gate or a
     * stuck cooldown — the caller reports it).
     */
    private static long pressUntilPhased(Rig rig, Object grendel, EngineCombatBinding binding,
            EngineBackedCombatStepBoundary step, float fixedDt, long tick, int capFrames)
            throws Exception {
        for (int f = 0; f < capFrames; f++) {
            List<ShipPhaseCloakView> cloaks = binding.shipPhaseCloakStatesOrNull();
            ShipPhaseCloakView cloak = cloaks != null && cloaks.size() == 1 ? cloaks.get(0) : null;
            if (cloak != null && cloak.phased()) {
                return tick;
            }
            if (cloak != null && "IDLE".equals(cloak.state()) && cloak.cooldownRemaining() <= 0f) {
                rig.pressToggleCloak(grendel);
            }
            step.step(fixedDt, tick++);
        }
        return -tick;
    }

    private static Rig buildRig() throws Exception {
        ClassLoader loader = RealEnginePhaseProbeMain.class.getClassLoader();
        Rig rig = new Rig();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        rig.shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
        Class<?> shipCommandClass = Class.forName(SHIP_COMMAND_CLASS, false, loader);
        rig.engine = engineClass.getMethod("getInstance").invoke(null);
        if (rig.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        rig.getObjects = engineClass.getMethod("getObjects");
        rig.repoGetList = rig.getObjects.invoke(rig.engine).getClass()
                .getMethod("getList", Class.class);
        rig.isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
        rig.getVariant = rig.shipClass.getMethod("getVariant");
        rig.getPeakTimeRemaining = rig.shipClass.getMethod("getPeakTimeRemaining");
        rig.getFluxTracker = rig.shipClass.getMethod("getFluxTracker");
        rig.giveCommand = rig.shipClass.getMethod("giveCommand",
                shipCommandClass, Object.class, int.class);
        rig.getShipAI = rig.shipClass.getMethod("getShipAI");
        rig.setShipAI = findOneArg(rig.shipClass, "setShipAI");
        rig.getWeaponGroupsCopy = rig.shipClass.getMethod("getWeaponGroupsCopy");
        rig.cmdToggleCloak = enumConst(shipCommandClass, "TOGGLE_SHIELD_OR_PHASE_CLOAK");
        rig.cmdVentFlux = enumConst(shipCommandClass, "VENT_FLUX");
        rig.cmdAccelerate = enumConst(shipCommandClass, "ACCELERATE");
        rig.cmdDecelerate = enumConst(shipCommandClass, "DECELERATE");
        try {
            rig.getBlockedCommands = rig.shipClass.getMethod("getBlockedCommands");
        } catch (NoSuchMethodException e) {
            rig.getBlockedCommands = null; // telemetry-only; "-" in the c3 re-measure lines
        }
        return rig;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConst(Class<?> cls, String name) {
        return Enum.valueOf((Class<Enum>) cls, name);
    }

    private static float median(List<Float> values) {
        if (values.isEmpty()) {
            return Float.NaN;
        }
        List<Float> sorted = new ArrayList<Float>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Method findOneArg(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("no one-arg " + name + " on " + cls.getName());
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }

    private static String f2(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }

    private static String f4(float f) {
        return String.format(Locale.ROOT, "%.4f", f);
    }
}
