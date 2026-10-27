package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipMotionView;
import cc.starsector.server.adapter.ShipSystemView;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.protocol.ShipSystemStateSnapshot;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Aurora PROBE: does the {@code aurora_Heavyblaster} serve headless under the
 * server's own gated boot — registry resolve + deploy (V1), the engine's MANEUVERING_JETS AI
 * flying plasmajets unprompted (V2, the leg's one genuinely-unknown engine behavior /),
 * the speed oracle (sustained ~150, jets ACTIVE peak ~275, post-ACTIVE overspeed DECAYS
 * rather than clamps — V3), the flux oracle + SafetyOverrides no-vent (cap 13800,
 * dissipation ~2280/s, vent press adds nothing — V4), and the v8 wire tier carrying the
 * plasmajets states IN/ACTIVE/OUT/COOLDOWN + effectLevel through the PRODUCT mapping
 * ({@link ShipSystemStateSnapshot#fromView} — V5)?
 *
 * <p>Boot = the serveNPeer shape: {@code bastion:1:1:aurora} descriptor
 * ({@code -Dcc.match.descriptor}) + {@link RealEngineCombatBindingFactory#create(java.util.SortedSet)}
 * with EMPTY occupancy — under the dynamic-seat model the seat hull stays ABSENT and the field is 1 certified
 * Mudskipper (side 0, engine AI) vs 1 {@code aurora_Heavyblaster} (side 1, engine AI). The packet's
 * {@code bastion:0:2:aurora} alternative was NOT used: with the seat absent it fields two
 * same-side Auroras against an EMPTY side 0 — no combat stimulus for the jets AI, so V2 would
 * be unanswerable (hence the swap).
 *
 * <p><b>Windows</b> (a MEASUREMENT main, never wired into the product serve loop):
 * <ul>
 *   <li><b>A — passive AI window:</b> step; the Aurora's BasicShipAI flies plasmajets itself
 *       (aiType MANEUVERING_JETS). Collect V2 activation edges + the V5 state/effect coverage
 *       through the product wire mapping. Early-exits once the essentials are seen; breaks to
 *       C when the Mudskipper dies (jets evidence normally lands in the approach).</li>
 *   <li><b>C — active oracle window:</b> strip ALL ship AIs + autofire (kill-free, the
 *       zero-hull idiom), force the shield DOWN, then drive the engine's own faithful control
 *       verbs: C1 ACCELERATE-held sustained-speed plateau (~150 = 80 hull + 20 SO + 50
 *       zero-flux boost, permanently on under SO threshold 2); C2 {@code USE_SYSTEM} jets
 *       cycle under held ACCELERATE (ACTIVE peak ~275 = +125 flat, then the OUT edge: first
 *       post-ACTIVE speed must stay high — decay, not clamp — and fall thereafter); C3 flux
 *       oracle via the engine's own {@code FluxTrackerAPI.increaseFlux} (soft-flux injection,
 *       no-command dissipation slope ~2280/s) + a per-tick VENT_FLUX press window
 *       (SafetyOverrides ventRateMult ×0: the press must add ~nothing to the drain).</li>
 * </ul>
 *
 * <p><b>NEVER calls {@code getPlayerShip()}</b> (the pre-roster fallback fabricates
 * a shuttle that NULLS the combat map). All per-ship reads go through the repository list; the
 * Aurora's index is re-derived per tick (the mid-run repository-drift trap).
 *
 * <p>Exit codes: 0 = verdict {@code AURORA_HEADLESS_PROVEN} (V1–V5 all pass); 2 = a boundary
 * captured (boot/resolve threw — the fail-closed variant resolve names a missing Aurora — or a
 * step threw mid-window; stack printed); 1 = FAILED verdict rows or infrastructure.
 *
 */
public final class RealEngineAuroraProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEnginePhaseProbeMain. ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIP_COMMAND_CLASS = "com.fs.starfarer.api.combat.ShipCommand";

    private static final String P = "aurora-probe";
    private static final String PLASMAJETS_ID = "plasmajets";

    // ---- Oracles (engine- and data-pinned; tolerances are probe policy). ----
    private static final float EXPECTED_MAX_FLUX = 13800f;   // 11000 hull + 14 caps x 200
    private static final float EXPECTED_DISSIPATION = 2280f; // (800 + 34 vents x 10) x2 SO
    private static final float SUSTAINED_LO = 140f;          // 150 = 80 + 20 SO + 50 boost
    private static final float SUSTAINED_HI = 160f;
    private static final float JETS_PEAK_MIN = 260f;         // 275 = sustained + 125 flat
    private static final float OUT_FIRST_MIN = 200f;         // decay-not-clamp: no snap to ~150
    private static final float DECAY_DELTA_MIN = 20f;        // ...but it IS decaying

    private RealEngineAuroraProbeMain() {
    }

    // ---- reflective rig over the live engine (resolved once at boot) ----
    private static final class Rig {
        Object engine;
        Class<?> shipClass;
        Method getObjects;
        Method repoGetList;
        Method isShipAlive;
        Method getVariant;
        Method getFluxTracker;
        Method giveCommand;
        Method getShipAI;
        Method setShipAI;
        Method getWeaponGroupsCopy;
        Method getShield;
        Object cmdUseSystem;
        Object cmdAccelerate;
        Object cmdDecelerate;
        Object cmdTurnLeft;
        Object cmdTurnRight;
        Object cmdVentFlux;
        Object cmdToggleShield;
        Method fluxGetCurr;
        Method fluxGetMax;
        Method fluxIsVenting;
        Method fluxIsOverloaded;
        Method fluxIncrease; // nullable: resolved best-effort, C3 records a skip when absent

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

        private void resolveFlux(Object tracker) throws Exception {
            if (fluxGetCurr == null) {
                fluxGetCurr = tracker.getClass().getMethod("getCurrFlux");
                fluxGetMax = tracker.getClass().getMethod("getMaxFlux");
                fluxIsVenting = tracker.getClass().getMethod("isVenting");
                fluxIsOverloaded = tracker.getClass().getMethod("isOverloaded");
                try {
                    fluxIncrease = tracker.getClass().getMethod(
                            "increaseFlux", float.class, boolean.class);
                } catch (NoSuchMethodException e) {
                    fluxIncrease = null;
                }
            }
        }

        float currFlux(Object ship) throws Exception {
            Object tracker = getFluxTracker.invoke(ship);
            resolveFlux(tracker);
            return ((Number) fluxGetCurr.invoke(tracker)).floatValue();
        }

        float maxFlux(Object ship) throws Exception {
            Object tracker = getFluxTracker.invoke(ship);
            resolveFlux(tracker);
            return ((Number) fluxGetMax.invoke(tracker)).floatValue();
        }

        boolean venting(Object ship) throws Exception {
            Object tracker = getFluxTracker.invoke(ship);
            resolveFlux(tracker);
            return (Boolean) fluxIsVenting.invoke(tracker);
        }

        boolean overloaded(Object ship) throws Exception {
            Object tracker = getFluxTracker.invoke(ship);
            resolveFlux(tracker);
            return (Boolean) fluxIsOverloaded.invoke(tracker);
        }

        /** Engine's own flux verb (weapons call it); returns the engine's accepted flag. */
        boolean increaseSoftFlux(Object ship, float amount) throws Exception {
            Object tracker = getFluxTracker.invoke(ship);
            resolveFlux(tracker);
            if (fluxIncrease == null) {
                return false;
            }
            Object r = fluxIncrease.invoke(tracker, amount, false);
            return r instanceof Boolean ? (Boolean) r : true;
        }

        boolean fluxIncreaseAvailable(Object ship) throws Exception {
            resolveFlux(getFluxTracker.invoke(ship));
            return fluxIncrease != null;
        }

        boolean shieldOn(Object ship) {
            try {
                Object shield = getShield.invoke(ship);
                if (shield == null) {
                    return false;
                }
                return (Boolean) shield.getClass().getMethod("isOn").invoke(shield);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        void pressUseSystem(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdUseSystem, null, 0);
        }

        void pressAccelerate(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdAccelerate, null, 0);
        }

        void pressDecelerate(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdDecelerate, null, 0);
        }

        void pressTurn(Object ship, boolean left) throws Exception {
            giveCommand.invoke(ship, left ? cmdTurnLeft : cmdTurnRight, null, 0);
        }

        void pressVent(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdVentFlux, null, 0);
        }

        void pressToggleShield(Object ship) throws Exception {
            giveCommand.invoke(ship, cmdToggleShield, null, 0);
        }
    }

    /** Accumulated V5 evidence: the v8 tier's PRODUCT mapping driven on live reads. */
    private static final class V5Acc {
        final TreeSet<Integer> codesSeen = new TreeSet<Integer>();
        int mapFailures;
        int laneNullTicks;
        float effectMaxActive = Float.NaN;
        boolean effectIdleZeroSeen;

        /** Feed one plasmajets view through {@link ShipSystemStateSnapshot#fromView}. */
        void feed(ShipSystemView view) {
            try {
                ShipSystemStateSnapshot wire = ShipSystemStateSnapshot.fromView(view, view.shipId());
                codesSeen.add(wire.stateCode());
                if (wire.stateCode() == ShipSystemStateSnapshot.STATE_ACTIVE
                        && (Float.isNaN(effectMaxActive) || wire.effectLevel() > effectMaxActive)) {
                    effectMaxActive = wire.effectLevel();
                }
                if (wire.stateCode() == ShipSystemStateSnapshot.STATE_IDLE
                        && wire.effectLevel() == 0f) {
                    effectIdleZeroSeen = true;
                }
            } catch (RuntimeException e) {
                mapFailures++;
            }
        }

        boolean nonIdleCodesCovered() {
            return codesSeen.contains(ShipSystemStateSnapshot.STATE_CHARGEUP)
                    && codesSeen.contains(ShipSystemStateSnapshot.STATE_ACTIVE)
                    && codesSeen.contains(ShipSystemStateSnapshot.STATE_CHARGEDOWN)
                    && codesSeen.contains(ShipSystemStateSnapshot.STATE_COOLDOWN);
        }
    }

    public static void main(String[] args) throws Exception {
        int windowAFrames = 7200;   // 120 s of AI-flown combat at 60 fps (early-exits when done)
        int settleCapFrames = 1800; // 30 s cap for settle/decay/dissipation waits
        float fixedDt = SimTickRate.fixedDt();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--window-a-frames": windowAFrames = Integer.parseInt(args[++i]); break;
                case "--settle-cap-frames": settleCapFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin window_a_frames=" + windowAFrames
                + " settle_cap_frames=" + settleCapFrames + " fixed_dt=" + fixedDt
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        // ---- Boot: the serveNPeer gated shape. A throw here IS a finding — the fail-closed
        // variant resolve names the boundary if the registry lane misses the Aurora (V1). ----
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
        // One drain step first: the t=0 roster deploy parks ships on the engine's pending-add
        // queue until the first advance (factory: "pending-add until first advance").
        step.step(fixedDt, tick++);

        // ---- V1: the Aurora resolved from the LIVE registry, deployed, plasmajets present
        // in the primary-system lane (the v8 tier's source). ----
        List<?> bootShips = rig.ships();
        int auroraIdx = auroraIdx(rig);
        List<ShipSystemView> bootSystems = binding.shipSystemStatesOrNull();
        ShipSystemView bootJets = plasmajetsView(bootSystems, auroraIdx);
        boolean v1 = bootShips.size() == 2 && auroraIdx >= 0 && bootJets != null;
        System.out.println(P + " v1 repo_ships=" + bootShips.size() + " aurora_idx=" + auroraIdx
                + " system_entries=" + (bootSystems == null ? "null" : bootSystems.size())
                + " system_id=" + (bootJets == null ? "-" : bootJets.systemId())
                + " pass=" + v1);
        if (!v1) {
            System.out.println(P + "-end verdict=FAILED reasons=v1_aurora_deploy");
            System.exit(1);
            return;
        }
        Object aurora = bootShips.get(auroraIdx);
        float maxFlux = rig.maxFlux(aurora);
        System.out.println(P + " aurora max_flux=" + f1(maxFlux)
                + " curr_flux=" + f1(rig.currFlux(aurora))
                + " increase_flux_available=" + rig.fluxIncreaseAvailable(aurora));

        // ---- Window A: passive AI window. Does MANEUVERING_JETS AI fly the system? (V2/O1) ----
        V5Acc v5 = new V5Acc();
        int activationEdges = 0;
        int deactivationEdges = 0;
        int activeTicks = 0;
        float aWindowPeakSpeed = Float.NaN;
        boolean targetDied = false;
        boolean auroraDied = false;
        boolean prevActive = false;
        int aTicksRun = 0;
        try {
            for (int f = 0; f < windowAFrames; f++) {
                step.step(fixedDt, tick++);
                aTicksRun++;
                int idx = auroraIdx(rig);
                if (idx < 0) {
                    auroraDied = true;
                    break;
                }
                aurora = rig.ships().get(idx);
                if (!rig.alive(aurora)) {
                    auroraDied = true;
                    break;
                }
                List<ShipSystemView> systems = binding.shipSystemStatesOrNull();
                ShipSystemView jets = plasmajetsView(systems, idx);
                if (systems == null) {
                    v5.laneNullTicks++;
                }
                if (jets != null) {
                    v5.feed(jets);
                    boolean active = jets.active();
                    if (active && !prevActive) {
                        activationEdges++;
                        System.out.println(P + " a_edge tick=" + tick + " dir=up state="
                                + jets.state() + " effect=" + f2(jets.effectLevel()));
                    }
                    if (!active && prevActive) {
                        deactivationEdges++;
                        System.out.println(P + " a_edge tick=" + tick + " dir=down state="
                                + jets.state() + " effect=" + f2(jets.effectLevel()));
                    }
                    prevActive = active;
                    if (active) {
                        activeTicks++;
                        float speed = speedOrNaN(binding, idx);
                        if (!Float.isNaN(speed)
                                && (Float.isNaN(aWindowPeakSpeed) || speed > aWindowPeakSpeed)) {
                            aWindowPeakSpeed = speed;
                        }
                    }
                }
                if (f % 240 == 0) {
                    System.out.println(P + " a tick=" + tick
                            + " state=" + (jets == null ? "-" : jets.state())
                            + " effect=" + (jets == null ? "-" : f2(jets.effectLevel()))
                            + " speed=" + f1(speedOrNaN(binding, idx))
                            + " curr_flux=" + f1(rig.currFlux(aurora))
                            + " edges=" + activationEdges + "/" + deactivationEdges);
                }
                if (!targetAlive(rig, idx)) {
                    targetDied = true;
                    System.out.println(P + " a_target_died tick=" + tick
                            + " edges=" + activationEdges + "/" + deactivationEdges);
                    break;
                }
                boolean essentials = activationEdges >= 2 && deactivationEdges >= 1
                        && v5.nonIdleCodesCovered() && v5.effectMaxActive >= 0.9f;
                if (essentials && f >= 600) {
                    System.out.println(P + " a_early_exit tick=" + tick + " f=" + f);
                    break;
                }
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=AURORA_STEP_THREW tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        boolean v2 = activationEdges >= 1 && !auroraDied;
        System.out.println(P + " a_done tick=" + tick + " ticks_run=" + aTicksRun
                + " activation_edges=" + activationEdges + " deactivation_edges=" + deactivationEdges
                + " active_ticks=" + activeTicks + " a_peak_speed=" + f1(aWindowPeakSpeed)
                + " target_died=" + targetDied + " aurora_died=" + auroraDied + " v2=" + v2);
        if (auroraDied) {
            System.out.println(P + "-end verdict=FAILED reasons=aurora_died_window_a");
            System.exit(1);
            return;
        }

        // ---- Window C: strip AI + autofire (kill-free), force shield down, drive the oracles. ----
        float sustainedSpeed = Float.NaN;
        float jetsPeak = Float.NaN;
        float outFirstSpeed = Float.NaN;
        float decaySpeedAt120 = Float.NaN;
        int decayTicksTo165 = -1;
        String c2SkipReason = null;
        boolean fluxInjectAccepted = false;
        float dissipationPerS = Float.NaN;
        boolean ventStarted = false;
        boolean overloadedInVent = false;
        float ventSlopePerS = Float.NaN;
        String c3SkipReason = null;
        try {
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
            int idx = auroraIdx(rig);
            aurora = rig.ships().get(idx);
            // Shield DOWN + flux settled before the speed oracle (overload locks controls and
            // would kill the zero-flux boost; dissipation 2280/s makes this a short wait).
            for (int f = 0; f < settleCapFrames; f++) {
                if (!rig.shieldOn(aurora) && !rig.overloaded(aurora)
                        && rig.currFlux(aurora) / maxFlux < 0.5f) {
                    break;
                }
                if (rig.shieldOn(aurora)) {
                    rig.pressToggleShield(aurora);
                }
                step.step(fixedDt, tick++);
            }
            System.out.println(P + " c_settled tick=" + tick
                    + " shield_on=" + rig.shieldOn(aurora)
                    + " flux_level=" + f2(rig.currFlux(aurora) / maxFlux));
            // Aim at map center + kill drift before the speed windows: the first Move-2 run
            // measured the ACTIVE-window speed collapsing 275->78 at the map.half border drag
            // (not an engine clamp) — from anywhere, facing center gives >= map.half of runway.
            long aimStart = tick;
            tick = aimAtCenter(rig, binding, step, fixedDt, tick, settleCapFrames);
            idx = auroraIdx(rig);
            aurora = rig.ships().get(idx);
            System.out.println(P + " c_aimed tick=" + tick + " ticks=" + (tick - aimStart)
                    + " dist_center=" + f1(distCenter(binding, idx)));

            // C1: ACCELERATE-held sustained plateau (~150; boost is always on under SO after
            // the ~1 s randomized boost timer — the structural onset skew is irrelevant here).
            List<Float> plateau = new ArrayList<Float>();
            for (int f = 0; f < 300; f++) {
                rig.pressAccelerate(aurora);
                step.step(fixedDt, tick++);
                idx = auroraIdx(rig);
                float speed = speedOrNaN(binding, idx);
                if (f >= 240 && !Float.isNaN(speed)) {
                    plateau.add(speed);
                }
                if (f % 60 == 0) {
                    System.out.println(P + " c1 tick=" + tick + " f=" + f + " speed=" + f1(speed)
                            + " dist_center=" + f1(distCenter(binding, idx)));
                }
            }
            sustainedSpeed = median(plateau);
            System.out.println(P + " c1_done tick=" + tick + " sustained=" + f1(sustainedSpeed));

            // C2: USE_SYSTEM jets cycle under held ACCELERATE — ACTIVE peak, then the OUT edge
            // (decay-not-clamp) and the decay itself. V5 keeps accumulating through the manual
            // cycle (codes 1..4 land here even if the AI window was cut short).
            aurora = rig.ships().get(idx);
            int pressCap = 240;
            boolean engaged = false;
            for (int f = 0; f < pressCap; f++) {
                List<ShipSystemView> systems = binding.shipSystemStatesOrNull();
                ShipSystemView jets = plasmajetsView(systems, idx);
                if (jets != null) {
                    v5.feed(jets);
                    if (jets.active() || "IN".equals(jets.state())) {
                        engaged = true;
                        break;
                    }
                    if ("IDLE".equals(jets.state()) && jets.cooldownRemaining() <= 0f) {
                        rig.pressUseSystem(aurora);
                    }
                }
                rig.pressAccelerate(aurora);
                step.step(fixedDt, tick++);
            }
            if (!engaged) {
                c2SkipReason = "could_not_engage_jets";
                System.out.println(P + " c2 could_not_engage tick=" + tick);
            } else {
                System.out.println(P + " c2_engaged tick=" + tick);
                boolean sawOut = false;
                boolean collapseWarned = false;
                for (int f = 0; f < 600 && !sawOut; f++) {
                    rig.pressAccelerate(aurora);
                    step.step(fixedDt, tick++);
                    idx = auroraIdx(rig);
                    List<ShipSystemView> systems = binding.shipSystemStatesOrNull();
                    ShipSystemView jets = plasmajetsView(systems, idx);
                    float speed = speedOrNaN(binding, idx);
                    if (jets == null) {
                        continue;
                    }
                    v5.feed(jets);
                    if (jets.active() && !Float.isNaN(speed)
                            && (Float.isNaN(jetsPeak) || speed > jetsPeak)) {
                        jetsPeak = speed;
                    }
                    if (jets.active() && !collapseWarned && !Float.isNaN(jetsPeak)
                            && !Float.isNaN(speed) && speed < jetsPeak - 40f) {
                        // An ACTIVE-window speed collapse is an EXTERNAL brake (border drag,
                        // collision) — the decay numbers below are contaminated; say so loudly.
                        collapseWarned = true;
                        System.out.println(P + " c2_active_speed_collapse tick=" + tick
                                + " speed=" + f1(speed)
                                + " dist_center=" + f1(distCenter(binding, idx)));
                    }
                    if ("OUT".equals(jets.state()) || "COOLDOWN".equals(jets.state())) {
                        outFirstSpeed = speed;
                        sawOut = true;
                        System.out.println(P + " c2_out_edge tick=" + tick + " state="
                                + jets.state() + " speed=" + f1(speed)
                                + " dist_center=" + f1(distCenter(binding, idx)));
                    }
                    if (f % 30 == 0) {
                        System.out.println(P + " c2 tick=" + tick + " state=" + jets.state()
                                + " effect=" + f2(jets.effectLevel()) + " speed=" + f1(speed)
                                + " dist_center=" + f1(distCenter(binding, idx)));
                    }
                }
                // Decay watch: hold ACCELERATE, no further USE_SYSTEM press. The overspeed must
                // fall toward the sustained plateau on its own (decay), not have snapped already
                // (the outFirstSpeed floor catches a clamp).
                StringBuilder decaySeries = new StringBuilder();
                for (int f = 0; f < settleCapFrames; f++) {
                    rig.pressAccelerate(aurora);
                    step.step(fixedDt, tick++);
                    idx = auroraIdx(rig);
                    List<ShipSystemView> systems = binding.shipSystemStatesOrNull();
                    ShipSystemView jets = plasmajetsView(systems, idx);
                    if (jets != null) {
                        v5.feed(jets);
                    }
                    float speed = speedOrNaN(binding, idx);
                    if (f < 48) {
                        // Per-tick decay series: the overspeed shedding RATE is the Move-3
                        // feel-hazard number (client dead-reckoning overshoots by it per
                        // stale tick after a burst ends).
                        if (decaySeries.length() > 0) {
                            decaySeries.append(';');
                        }
                        decaySeries.append(f1(speed));
                    }
                    if (f == 48) {
                        System.out.println(P + " c2_decay_series n=48 series=" + decaySeries);
                    }
                    if (f == 120) {
                        decaySpeedAt120 = speed;
                    }
                    if (!Float.isNaN(speed) && speed <= 165f && decayTicksTo165 < 0) {
                        decayTicksTo165 = f + 1;
                        System.out.println(P + " c2_decayed tick=" + tick + " ticks=" + decayTicksTo165
                                + " speed=" + f1(speed));
                        if (f >= 120) {
                            break;
                        }
                    }
                    if (decayTicksTo165 >= 0 && f >= 120) {
                        break;
                    }
                }
                System.out.println(P + " c2_done tick=" + tick + " jets_peak=" + f1(jetsPeak)
                        + " out_first_speed=" + f1(outFirstSpeed)
                        + " decay_speed_at_120=" + f1(decaySpeedAt120)
                        + " decay_ticks_to_165=" + decayTicksTo165);
            }

            // C3: flux oracle — the engine's own increaseFlux verb (soft), a no-command
            // dissipation window, then the SafetyOverrides vent-press window.
            aurora = rig.ships().get(auroraIdx(rig));
            if (!rig.fluxIncreaseAvailable(aurora)) {
                c3SkipReason = "increase_flux_unavailable";
            } else {
                fluxInjectAccepted = rig.increaseSoftFlux(aurora, 8000f);
                step.step(fixedDt, tick++);
                float d0 = rig.currFlux(aurora);
                for (int f = 0; f < 120; f++) {
                    step.step(fixedDt, tick++);
                }
                float d1 = rig.currFlux(aurora);
                dissipationPerS = (d0 - d1) / (120f * fixedDt);
                System.out.println(P + " c3_dissipation tick=" + tick + " from=" + f1(d0)
                        + " to=" + f1(d1) + " per_s=" + f1(dissipationPerS)
                        + " inject_accepted=" + fluxInjectAccepted);
                // Vent window: top back up, then press VENT_FLUX every tick. Under SO
                // (ventRateMult x0) the press must add ~nothing over plain dissipation;
                // whether the venting FLAG ever engages is recorded, not gated (the
                // confirmed fact is rate-x0, not command removal).
                float cur = rig.currFlux(aurora);
                if (cur < 8000f) {
                    rig.increaseSoftFlux(aurora, 8000f - cur);
                    step.step(fixedDt, tick++);
                }
                float v0 = rig.currFlux(aurora);
                for (int f = 0; f < 120; f++) {
                    rig.pressVent(aurora);
                    step.step(fixedDt, tick++);
                    if (rig.venting(aurora)) {
                        ventStarted = true;
                    }
                    if (rig.overloaded(aurora)) {
                        overloadedInVent = true;
                    }
                }
                float v1end = rig.currFlux(aurora);
                ventSlopePerS = (v0 - v1end) / (120f * fixedDt);
                System.out.println(P + " c3_vent tick=" + tick + " from=" + f1(v0)
                        + " to=" + f1(v1end) + " per_s=" + f1(ventSlopePerS)
                        + " vent_started=" + ventStarted + " overloaded=" + overloadedInVent);
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=AURORA_STEP_THREW tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        // ---- Verdicts. ----
        boolean v3Sustained = !Float.isNaN(sustainedSpeed)
                && sustainedSpeed >= SUSTAINED_LO && sustainedSpeed <= SUSTAINED_HI;
        boolean v3Peak = !Float.isNaN(jetsPeak) && jetsPeak >= JETS_PEAK_MIN;
        boolean v3Decay = !Float.isNaN(outFirstSpeed) && outFirstSpeed >= OUT_FIRST_MIN
                && ((!Float.isNaN(decaySpeedAt120)
                        && outFirstSpeed - decaySpeedAt120 >= DECAY_DELTA_MIN)
                    || decayTicksTo165 >= 0);
        boolean v3 = v3Sustained && v3Peak && v3Decay && c2SkipReason == null;
        boolean v4Cap = Math.abs(maxFlux - EXPECTED_MAX_FLUX) < 0.5f;
        boolean v4Dissipation = !Float.isNaN(dissipationPerS)
                && dissipationPerS >= EXPECTED_DISSIPATION * 0.90f
                && dissipationPerS <= EXPECTED_DISSIPATION * 1.10f;
        boolean v4NoVent = !Float.isNaN(ventSlopePerS)
                && ventSlopePerS <= EXPECTED_DISSIPATION * 1.15f;
        boolean v4 = v4Cap && v4Dissipation && v4NoVent && c3SkipReason == null;
        boolean v5Pass = v5.mapFailures == 0 && v5.nonIdleCodesCovered()
                && v5.effectMaxActive >= 0.9f && v5.effectIdleZeroSeen;
        boolean pass = v2 && v3 && v4 && v5Pass;
        StringBuilder reasons = new StringBuilder();
        if (!v2) reasons.append("v2_jets_ai,");
        if (!v3) reasons.append("v3_speed_oracle,");
        if (!v4) reasons.append("v4_flux_oracle,");
        if (!v5Pass) reasons.append("v5_v8_tier,");
        System.out.println(P + "-end verdict=" + (pass ? "AURORA_HEADLESS_PROVEN" : "FAILED")
                + (reasons.length() == 0 ? "" : " reasons=" + reasons)
                + " v1_deploy=" + v1
                + " v2_activation_edges=" + activationEdges
                + " v2_deactivation_edges=" + deactivationEdges
                + " v2_active_ticks=" + activeTicks
                + " a_peak_speed=" + f1(aWindowPeakSpeed)
                + " target_died=" + targetDied
                + " v3_sustained=" + f1(sustainedSpeed)
                + " v3_jets_peak=" + f1(jetsPeak)
                + " v3_out_first_speed=" + f1(outFirstSpeed)
                + " v3_decay_speed_at_120=" + f1(decaySpeedAt120)
                + " v3_decay_ticks_to_165=" + decayTicksTo165
                + (c2SkipReason == null ? "" : " c2_skip=" + c2SkipReason)
                + " v4_max_flux=" + f1(maxFlux)
                + " v4_dissipation_per_s=" + f1(dissipationPerS)
                + " v4_vent_slope_per_s=" + f1(ventSlopePerS)
                + " v4_vent_started=" + ventStarted
                + " v4_overloaded_in_vent=" + overloadedInVent
                + " v4_inject_accepted=" + fluxInjectAccepted
                + (c3SkipReason == null ? "" : " c3_skip=" + c3SkipReason)
                + " v5_state_codes=" + v5.codesSeen
                + " v5_map_failures=" + v5.mapFailures
                + " v5_lane_null_ticks=" + v5.laneNullTicks
                + " v5_effect_max_active=" + f2(v5.effectMaxActive)
                + " v5_effect_idle_zero=" + v5.effectIdleZeroSeen);
        System.exit(pass ? 0 : 1);
    }

    /** Current Aurora repository index (re-derived per call — the mid-run drift trap). */
    private static int auroraIdx(Rig rig) throws Exception {
        List<?> ships = rig.ships();
        for (int i = 0; i < ships.size(); i++) {
            if (MatchDescriptors.BASTION_AURORA_VARIANT_ID.equals(rig.variantId(ships.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** True while any live non-Aurora ship remains in the repository (the Mudskipper target). */
    private static boolean targetAlive(Rig rig, int auroraIdx) throws Exception {
        List<?> ships = rig.ships();
        for (int i = 0; i < ships.size(); i++) {
            if (i != auroraIdx && rig.alive(ships.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** The Aurora's plasmajets entry from the primary-system lane, or null. */
    private static ShipSystemView plasmajetsView(List<ShipSystemView> systems, int auroraIdx) {
        if (systems == null) {
            return null;
        }
        for (ShipSystemView view : systems) {
            if (view != null && PLASMAJETS_ID.equals(view.systemId())
                    && (auroraIdx < 0 || view.shipId() == auroraIdx + 1)) {
                return view;
            }
        }
        return null;
    }

    /**
     * Rotate the Aurora toward map center with the engine's own TURN verbs, DECELERATE held to
     * kill drift, until it faces within 8° of the center bearing at near-rest. From anywhere on
     * the map that guarantees ≥ map.half of forward runway for the C speed windows (the first
     * Move-2 run hit the border drag mid-ACTIVE: 275 → 78 with jets still at effect 1.0).
     */
    private static long aimAtCenter(Rig rig, EngineCombatBinding binding,
            EngineBackedCombatStepBoundary step, float fixedDt, long tick, int capFrames)
            throws Exception {
        for (int f = 0; f < capFrames; f++) {
            int idx = auroraIdx(rig);
            List<ShipMotionView> motions = binding.shipMotionsOrNull();
            if (idx < 0 || motions == null || idx >= motions.size()) {
                step.step(fixedDt, tick++);
                continue;
            }
            ShipMotionView m = motions.get(idx);
            float bearing = (float) Math.toDegrees(Math.atan2(-m.locY(), -m.locX()));
            // Nudge the bearing off any nearby repo entity (the dead Mudskipper HULK stays in
            // the repository and in the motion lane): run 2 drove straight back through the
            // fight zone and ground into the hulk mid-ACTIVE (275 -> 55 at dist ~1000). From
            // near-center every direction has runway, so a 40° dodge costs nothing.
            for (int i = 0; i < motions.size(); i++) {
                if (i == idx) {
                    continue;
                }
                ShipMotionView other = motions.get(i);
                float dx = other.locX() - m.locX();
                float dy = other.locY() - m.locY();
                if (Math.hypot(dx, dy) > 2500d) {
                    continue;
                }
                float otherBearing = (float) Math.toDegrees(Math.atan2(dy, dx));
                float off = normDeg(otherBearing - bearing);
                if (Math.abs(off) < 25f) {
                    bearing = normDeg(bearing - Math.signum(off == 0f ? 1f : off) * 40f);
                }
            }
            float delta = normDeg(bearing - m.facing());
            float speed = (float) Math.hypot(m.velX(), m.velY());
            if (Math.abs(delta) <= 8f && speed < 10f) {
                return tick;
            }
            Object aurora = rig.ships().get(idx);
            if (Math.abs(delta) > 8f) {
                rig.pressTurn(aurora, delta > 0f);
            }
            rig.pressDecelerate(aurora);
            step.step(fixedDt, tick++);
        }
        return tick;
    }

    /** Signed degrees normalized into (-180, 180]. */
    private static float normDeg(float d) {
        d %= 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d <= -180f) {
            d += 360f;
        }
        return d;
    }

    /** The Aurora's distance from map center off the B2 motion lane, or NaN. */
    private static float distCenter(EngineCombatBinding binding, int auroraIdx) {
        List<ShipMotionView> motions = binding.shipMotionsOrNull();
        if (motions == null || auroraIdx < 0 || auroraIdx >= motions.size()) {
            return Float.NaN;
        }
        ShipMotionView m = motions.get(auroraIdx);
        return (float) Math.hypot(m.locX(), m.locY());
    }

    /** The Aurora's speed off the B2 motion lane, or NaN when unreadable this tick. */
    private static float speedOrNaN(EngineCombatBinding binding, int auroraIdx) {
        List<ShipMotionView> motions = binding.shipMotionsOrNull();
        if (motions == null || auroraIdx < 0 || auroraIdx >= motions.size()) {
            return Float.NaN;
        }
        ShipMotionView m = motions.get(auroraIdx);
        return (float) Math.hypot(m.velX(), m.velY());
    }

    private static Rig buildRig() throws Exception {
        ClassLoader loader = RealEngineAuroraProbeMain.class.getClassLoader();
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
        rig.getFluxTracker = rig.shipClass.getMethod("getFluxTracker");
        rig.giveCommand = rig.shipClass.getMethod("giveCommand",
                shipCommandClass, Object.class, int.class);
        rig.getShipAI = rig.shipClass.getMethod("getShipAI");
        rig.setShipAI = findOneArg(rig.shipClass, "setShipAI");
        rig.getWeaponGroupsCopy = rig.shipClass.getMethod("getWeaponGroupsCopy");
        rig.getShield = rig.shipClass.getMethod("getShield");
        rig.cmdUseSystem = enumConst(shipCommandClass, "USE_SYSTEM");
        rig.cmdAccelerate = enumConst(shipCommandClass, "ACCELERATE");
        rig.cmdDecelerate = enumConst(shipCommandClass, "DECELERATE");
        rig.cmdTurnLeft = enumConst(shipCommandClass, "TURN_LEFT");
        rig.cmdTurnRight = enumConst(shipCommandClass, "TURN_RIGHT");
        rig.cmdVentFlux = enumConst(shipCommandClass, "VENT_FLUX");
        rig.cmdToggleShield = enumConst(shipCommandClass, "TOGGLE_SHIELD_OR_PHASE_CLOAK");
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
}
