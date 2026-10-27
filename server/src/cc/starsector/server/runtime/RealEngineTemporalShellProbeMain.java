package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipSystemView;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.PilotApplyState;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.protocol.ShipSystemStateSnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * ANUBIS temporal-shell parity, the headless SEAT-LANE probe (verdict
 * {@code TEMPORAL_SHELL_PARITY}).
 *
 *
 * <p><b>Question:</b> does the owner's {@code anubis_Paladin} ship system ({@code temporalshell}:
 * per-ship {@code timeMult} 1→3 = extra {@code Ship.advance} passes, engine-GLOBAL mult only for
 * the engine's player ship) behave like vanilla when driven through OUR seat lane — bit-7 client
 * STATE mirror ⇒ {@code useSystem()} (pass 1, {@code EngineCombatBinding.applyPilotIntent}),
 * {@code FastTimeReinjectShim} re-issuing movement/fire on passes 2+, no world-slow, the wire
 * {@code systemStates} sequence, and the bit-LOW cancel press being a no-op on a non-toggle system?
 *
 * <p><b>Boot</b> = the serve loop's shape ({@code RealEngineAutofireFlyInProbeMain} precedent):
 * {@code bastion:0:1:anubis} descriptor ({@code -Dcc.match.descriptor}, minted game-free by the
 * wrapper) + {@link RealEngineCombatBindingFactory#create(java.util.SortedSet)} with EMPTY
 * occupancy, then the REAL seat fly-in {@link RealEngineDemoRoster#midDeploySeat(int)} (owned wire
 * id 1; the shim is then pinned through the binding's own {@code installFastTimeReinjectShim} —
 * the serve loop's grace-detach step — and asserted). Kill-free rig: every non-seat hull
 * gets its AI stripped + {@code setHoldFire(true)}; the seat hull is the binding's own idled AI.
 *
 * <p><b>Drive</b> = the serve loop's per-tick cadence: {@code beginReinjectTick()} →
 * {@code applyPilotIntent(frame)} → {@code step}. Frames carry the client-tracker autofire mask
 * (bit 0 set: the variant's lone {@code autofire:true} group) with group 0 SELECTED, so the mirror
 * suppresses autofire exactly as a human selecting the group would, and held FIRE fires the
 * guardians manually at a point 1000 su ahead.
 *
 * <p><b>Windows:</b> W0 baseline (unshelled): from rest ACCELERATE 20 ticks → {@code rampV20};
 * then FIRE 120 ticks → {@code fluxSlope}. W1 activation: bit 7 HIGH from T0, then mirrored
 * (HIGH while the server's system reads IN|ACTIVE); at ACTIVE onset the same 20+120 sub-windows.
 * W2 cancel no-op: bit 7 dropped at ACTIVE+60 — the server's one cancel press must leave the
 * system ACTIVE for its full wall length.
 *
 * <p><b>Rig lever (measurement-only):</b> the Anubis dissipates 1050 flux/s against 900/s of
 * guardian generation, so current flux would sit at 0 in both fire windows. The seat hull's
 * {@code getFluxDissipation()} stat is multiplied by 0 ONLY inside each 120-tick fire window
 * (restored with {@code unmodify} after) so the slope measures pure generation; both windows
 * carry the identical lever and the RATIO is the observable.
 *
 * <p>PASS rows P1–P7 + exit codes (0 = PARITY, 1 = a row FAILED / infra,
 * 2 = boot/fly-in/step threw — stack printed).
 * NEVER calls {@code getPlayerShip()}. Pure server tree, no product code touched.
 */
public final class RealEngineTemporalShellProbeMain {

    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIP_SYSTEM_API = "com.fs.starfarer.api.combat.ShipSystemAPI";
    private static final String SHIP_STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String MUTABLE_STAT_CLASS = "com.fs.starfarer.api.combat.MutableStat";
    private static final String FLUX_TRACKER_API = "com.fs.starfarer.api.combat.FluxTrackerAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "temporal-shell-probe";
    private static final String SYSTEM_ID = "temporalshell";
    private static final long SEAT_WIRE_ID = 1L;
    private static final int SELECTED_GROUP = 0;
    /** Client-tracker autofire mask: bit 0 = the variant's lone {@code autofire:true} group. */
    private static final int AUTOFIRE_MASK_GROUP0 = 1;
    private static final String DISS_MOD_ID = "cc_temporal_shell_probe_diss";
    private static final float AIM_AHEAD_SU = 1000f;
    private static final int RAMP_TICKS = 20;
    private static final int FIRE_TICKS = 120;
    private static final int SETTLE_TICKS = 300;
    private static final int PRESS_HOLD_TICKS = 5;
    private static final int W2_DROP_AFTER_ACTIVE_TICKS = 60;

    private RealEngineTemporalShellProbeMain() {
    }

    private static final class Rig {
        Object engine;
        Method engineGetTimeMult;
        Method getObjects;
        Method repoGetList;
        Class<?> shipClass;
        Method shipGetSystem;
        Method shipGetMutableStats;
        Method shipGetFluxTracker;
        Method shipGetVelocity;
        Method shipGetLocation;
        Method shipGetFacing;
        Method shipGetShipAI;
        Method shipSetShipAI;
        Method shipSetHoldFire;
        Method shipGetWeaponGroupsCopy;
        Method shipGetId;
        Method sysGetState;
        Method sysGetEffectLevel;
        Method sysGetCooldownRemaining;
        Method sysIsOn;
        Method statsGetTimeMult;
        Method statsGetFluxDissipation;
        Method statGetModified;
        Method statModifyMult;
        Method statUnmodify;
        Method fluxGetCurr;
        Field vecX;
        Field vecY;
        Method groupIsAutofiring; // lazily resolved off the first live group instance
    }

    private static final class Ctx {
        Rig rig;
        EngineCombatBinding binding;
        EngineBackedCombatStepBoundary step;
        PilotApplyState state;
        Object ship;
        float fixedDt;
        long tick;
        int seq;
        int cycleCap;
        int restCap;
    }

    private static final class Obs {
        String sysState;
        float effect;
        float cooldown;
        boolean isOn;
        float shipMult;
        float engineMult;
        float speed;
        float flux;
        long reinjects;
        int cancels;
        boolean activated;
        int wireCode;
    }

    private static final class Cycle {
        long t0 = -1;
        long tActivate = -1;
        boolean activated;
        float peakMult;
        float engineMaxDev;
        int inTicks;
        int activeTicks;
        int outTicks;
        int cooldownTicks;
        float rampV20 = Float.NaN;
        float fluxSlope = Float.NaN;
        int fireTicks;
        String fireEndState = "-";
        long reinjectsDelta;
        int cancelsIn;
        int cancelsActive;
        int cancelsOut;
        int cancelsCooldown;
        String wireSeq = "";
        boolean inMonotone = true;
        boolean outMonotone = true;
        boolean completed;
        long dropTick = -1;
        int cancelsAfterDrop = -1;
        String stateAfterDrop = "-";
        long endTick;
    }

    public static void main(String[] args) throws Exception {
        float fixedDt = 1f / 60f;
        int cycleCap = 1500;
        int restCap = 900;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--cycle-cap": cycleCap = Integer.parseInt(args[++i]); break;
                case "--rest-cap": restCap = Integer.parseInt(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin fixed_dt=" + fixedDt + " cycle_cap=" + cycleCap
                + " rest_cap=" + restCap + " ramp_ticks=" + RAMP_TICKS + " fire_ticks=" + FIRE_TICKS
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        Ctx c = new Ctx();
        c.fixedDt = fixedDt;
        c.cycleCap = cycleCap;
        c.restCap = restCap;
        try {
            boot(c);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=BOOT_OR_FLY_IN_THREW tick=" + c.tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        Cycle w0;
        Cycle w1;
        Cycle w2;
        try {
            // ---- W0 baseline (unshelled): the same 20-tick ramp + 120-tick fire windows. ----
            rest(c, "w0");
            w0 = new Cycle();
            w0.t0 = c.tick;
            baselineWindows(c, w0);
            System.out.println(P + " w0_done tick=" + c.tick + " ramp_v20=" + f2(w0.rampV20)
                    + " flux_slope=" + f3(w0.fluxSlope) + " fire_ticks=" + w0.fireTicks
                    + " peak_mult=" + f3(w0.peakMult) + " engine_max_dev=" + f4(w0.engineMaxDev));

            // ---- W1 activation cycle: bit-7 mirror + the measured sub-windows at ACTIVE onset. ----
            rest(c, "w1");
            w1 = runCycle(c, true, -1, "w1");

            // ---- W2 cancel no-op: drop bit 7 at ACTIVE+60. ----
            rest(c, "w2");
            w2 = runCycle(c, false, W2_DROP_AFTER_ACTIVE_TICKS, "w2");
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=STEP_THREW tick=" + c.tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        // ---- Verdict rows. ----
        long activateDelta = w1.tActivate < 0 ? -1 : w1.tActivate - w1.t0;
        boolean p1 = w1.tActivate >= 0 && activateDelta <= 1 && w1.activated;
        boolean p2 = w1.peakMult >= 2.95f && w1.peakMult <= 3.0f + 1e-3f
                && w1.activeTicks >= 180 && w1.activeTicks <= 220
                && w1.cooldownTicks >= 590 && w1.cooldownTicks <= 610;
        boolean p3 = Math.max(w0.engineMaxDev, Math.max(w1.engineMaxDev, w2.engineMaxDev)) <= 1e-4f;
        float rampRatio = w0.rampV20 > 0f ? w1.rampV20 / w0.rampV20 : Float.NaN;
        boolean p4 = rampRatio >= 2.0f && w1.reinjectsDelta > 0;
        float fluxRatio = w0.fluxSlope > 0f ? w1.fluxSlope / w0.fluxSlope : Float.NaN;
        boolean p5 = fluxRatio >= 2.0f;
        boolean p7 = w2.cancelsAfterDrop >= 1 && "ACTIVE".equals(w2.stateAfterDrop)
                && Math.abs(w2.activeTicks - w1.activeTicks) <= 12 && w2.completed;
        boolean parity = p1 && p2 && p3 && p4 && p5 && p7 && w1.completed;
        String verdict = parity ? "TEMPORAL_SHELL_PARITY" : "TEMPORAL_SHELL_MISMATCH";
        System.out.println(P + "-end verdict=" + verdict
                + " p1=" + p1 + " p2=" + p2 + " p3=" + p3 + " p4=" + p4 + " p5=" + p5 + " p7=" + p7
                + " t_activate_delta=" + activateDelta + " system_activated=" + w1.activated
                + " peak_mult=" + f3(w1.peakMult)
                + " in_ticks=" + w1.inTicks + " active_ticks=" + w1.activeTicks
                + " out_ticks=" + w1.outTicks + " cooldown_ticks=" + w1.cooldownTicks
                + " engine_mult_max_dev=" + f4(Math.max(w0.engineMaxDev, Math.max(w1.engineMaxDev, w2.engineMaxDev)))
                + " ramp_base=" + f2(w0.rampV20) + " ramp_active=" + f2(w1.rampV20)
                + " ramp_ratio=" + f3(rampRatio) + " reinjects_delta=" + w1.reinjectsDelta
                + " flux_slope_base=" + f3(w0.fluxSlope) + " flux_slope_active=" + f3(w1.fluxSlope)
                + " flux_ratio=" + f3(fluxRatio) + " fire_end_state=" + w1.fireEndState
                + " fire_ticks_active=" + w1.fireTicks
                + " wire_seq=" + w1.wireSeq + " in_monotone=" + w1.inMonotone
                + " out_monotone=" + w1.outMonotone
                + " cancels_by_state=" + w1.cancelsIn + "/" + w1.cancelsActive + "/" + w1.cancelsOut
                + "/" + w1.cancelsCooldown
                + " w2_cancels_after_drop=" + w2.cancelsAfterDrop
                + " w2_state_after_drop=" + w2.stateAfterDrop + " w2_active_ticks=" + w2.activeTicks
                + " w2_drop_tick=" + w2.dropTick + " w2_completed=" + w2.completed
                + " w1_completed=" + w1.completed + " end_tick=" + c.tick);
        System.exit(parity ? 0 : 1);
    }

    // ---------------------------------------------------------------- boot + rig

    private static void boot(Ctx c) throws Exception {
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        c.binding = binding;
        c.step = new EngineBackedCombatStepBoundary(world, binding);
        c.state = new PilotApplyState(0);
        long[] seatWireIds = RealEngineDemoRoster.descriptorSeatShipIds();
        if (seatWireIds.length < 1 || seatWireIds[0] != SEAT_WIRE_ID) {
            throw new IllegalStateException("descriptor seats unexpected: n=" + seatWireIds.length
                    + (seatWireIds.length > 0 ? " first=" + seatWireIds[0] : ""));
        }
        c.rig = buildRig();
        // The REAL seat fly-in (owned wire id 1; the shim installs on this path).
        c.ship = RealEngineDemoRoster.midDeploySeat(1);
        c.step.step(c.fixedDt, c.tick++);
        // Finding: midDeploySeat alone does NOT install the shim — the serve loop installs it
        // in the grace-detach step (EngineCombatBinding.graceDetach → installFastTimeReinjectShim).
        // Call the same public seam (the phase probe's V7 precedent), then assert it is pinned.
        boolean installed = binding.installFastTimeReinjectShim(c.ship);
        boolean shim = binding.fastTimeShimInstalled(c.ship);
        System.out.println(P + " seat_ready tick=" + c.tick + " id=" + c.rig.shipGetId.invoke(c.ship)
                + " shim_install_call=" + installed + " shim_installed=" + shim);
        if (!shim) {
            throw new IllegalStateException("fast-time reinject shim NOT installed on the seat hull");
        }
        // Kill-free rig: every non-seat hull loses its AI + holds fire.
        List<?> ships = (List<?>) c.rig.repoGetList.invoke(c.rig.getObjects.invoke(c.rig.engine), c.rig.shipClass);
        int stripped = 0;
        int held = 0;
        for (Object s : new ArrayList<Object>(ships)) {
            if (s == c.ship) {
                continue;
            }
            if (c.rig.shipGetShipAI.invoke(s) != null && c.rig.shipSetShipAI != null) {
                c.rig.shipSetShipAI.invoke(s, new Object[] {null});
                stripped++;
            }
            c.rig.shipSetHoldFire.invoke(s, true);
            held++;
        }
        System.out.println(P + " kill_free roster_ships=" + ships.size() + " ai_stripped=" + stripped
                + " hold_fire=" + held);
        Object sys = c.rig.shipGetSystem.invoke(c.ship);
        System.out.println(P + " seat_system id=" + (sys == null ? "(none)"
                : sys.getClass().getMethod("getId").invoke(sys)));
        // Settle under quiet frames: the autofire mirror converges (group 0 selected ⇒ toggled OFF).
        for (int f = 0; f < SETTLE_TICKS; f++) {
            drive(c, 0, false);
        }
        List<?> groups = (List<?>) c.rig.shipGetWeaponGroupsCopy.invoke(c.ship);
        String g0 = "(no groups)";
        if (!groups.isEmpty()) {
            Object g = groups.get(0);
            if (c.rig.groupIsAutofiring == null) {
                c.rig.groupIsAutofiring = g.getClass().getMethod("isAutofiring");
            }
            g0 = String.valueOf(c.rig.groupIsAutofiring.invoke(g));
        }
        Obs o = observe(c);
        System.out.println(P + " settled tick=" + c.tick + " groups=" + groups.size()
                + " group0_autofire=" + g0 + " sys=" + o.sysState + " speed=" + f1(o.speed)
                + " flux=" + f1(o.flux) + " ship_mult=" + f3(o.shipMult)
                + " engine_mult=" + f4(o.engineMult) + " wire_code=" + o.wireCode);
    }

    private static Rig buildRig() throws Exception {
        ClassLoader loader = RealEngineTemporalShellProbeMain.class.getClassLoader();
        Rig r = new Rig();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        r.engine = engineClass.getMethod("getInstance").invoke(null);
        if (r.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        r.engineGetTimeMult = engineClass.getMethod("getTimeMult");
        r.getObjects = engineClass.getMethod("getObjects");
        r.repoGetList = r.getObjects.invoke(r.engine).getClass().getMethod("getList", Class.class);
        r.shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        Class<?> sysApi = Class.forName(SHIP_SYSTEM_API, false, loader);
        Class<?> statsApi = Class.forName(SHIP_STATS_API, false, loader);
        Class<?> statClass = Class.forName(MUTABLE_STAT_CLASS, false, loader);
        Class<?> fluxApi = Class.forName(FLUX_TRACKER_API, false, loader);
        Class<?> vec2 = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        r.shipGetSystem = shipApi.getMethod("getSystem");
        r.shipGetMutableStats = shipApi.getMethod("getMutableStats");
        r.shipGetFluxTracker = shipApi.getMethod("getFluxTracker");
        r.shipGetVelocity = shipApi.getMethod("getVelocity");
        r.shipGetLocation = shipApi.getMethod("getLocation");
        r.shipGetFacing = shipApi.getMethod("getFacing");
        r.shipGetShipAI = shipApi.getMethod("getShipAI");
        r.shipSetShipAI = findOneArg(r.shipClass, "setShipAI");
        r.shipSetHoldFire = shipApi.getMethod("setHoldFire", boolean.class);
        r.shipGetWeaponGroupsCopy = shipApi.getMethod("getWeaponGroupsCopy");
        r.shipGetId = shipApi.getMethod("getId");
        r.sysGetState = sysApi.getMethod("getState");
        r.sysGetEffectLevel = sysApi.getMethod("getEffectLevel");
        r.sysGetCooldownRemaining = sysApi.getMethod("getCooldownRemaining");
        r.sysIsOn = sysApi.getMethod("isOn");
        r.statsGetTimeMult = statsApi.getMethod("getTimeMult");
        r.statsGetFluxDissipation = statsApi.getMethod("getFluxDissipation");
        r.statGetModified = statClass.getMethod("getModifiedValue");
        r.statModifyMult = statClass.getMethod("modifyMult", String.class, float.class);
        r.statUnmodify = statClass.getMethod("unmodify", String.class);
        r.fluxGetCurr = fluxApi.getMethod("getCurrFlux");
        r.vecX = vec2.getField("x");
        r.vecY = vec2.getField("y");
        return r;
    }

    // ---------------------------------------------------------------- drive + observe

    /**
     * One serve-loop tick for the seat: {@code beginReinjectTick} → one faithful frame through
     * {@code applyPilotIntent} (facing pinned to the hull's OWN facing so the pin is a no-op; aim
     * 1000 su dead ahead; autofire mask bit 0 + group 0 selected) → step. Returns the post-step
     * observation, with the apply's {@code systemActivated} folded in.
     */
    private static Obs drive(Ctx c, int held, boolean fire) throws Exception {
        c.binding.beginReinjectTick();
        float facing = (Float) c.rig.shipGetFacing.invoke(c.ship);
        facing = ((facing % 360f) + 360f) % 360f;
        Object loc = c.rig.shipGetLocation.invoke(c.ship);
        float lx = c.rig.vecX.getFloat(loc);
        float ly = c.rig.vecY.getFloat(loc);
        double rad = Math.toRadians(facing);
        float aimX = lx + (float) (Math.cos(rad) * AIM_AHEAD_SU);
        float aimY = ly + (float) (Math.sin(rad) * AIM_AHEAD_SU);
        PilotIntentFrame frame = new PilotIntentFrame(SEAT_WIRE_ID, c.seq++, facing, held, fire,
                SELECTED_GROUP, facing, aimX, aimY, AUTOFIRE_MASK_GROUP0,
                PilotIntentFrame.TARGET_ABSENT, 0, PilotIntentFrame.TUBE_ORDINAL_ABSENT);
        EngineCombatBinding.PilotApplyResult res = c.binding.applyPilotIntent(frame, c.tick, c.state);
        if (!res.accepted()) {
            throw new IllegalStateException("applyPilotIntent REJECTED at tick " + c.tick);
        }
        c.step.step(c.fixedDt, c.tick++);
        Obs o = observe(c);
        o.activated = res.systemActivated();
        return o;
    }

    private static Obs observe(Ctx c) throws Exception {
        Rig r = c.rig;
        Obs o = new Obs();
        Object sys = r.shipGetSystem.invoke(c.ship);
        if (sys == null) {
            o.sysState = "NONE";
        } else {
            o.sysState = ((Enum<?>) r.sysGetState.invoke(sys)).name();
            o.effect = (Float) r.sysGetEffectLevel.invoke(sys);
            o.cooldown = (Float) r.sysGetCooldownRemaining.invoke(sys);
            o.isOn = (Boolean) r.sysIsOn.invoke(sys);
        }
        Object stats = r.shipGetMutableStats.invoke(c.ship);
        o.shipMult = (Float) r.statGetModified.invoke(r.statsGetTimeMult.invoke(stats));
        o.engineMult = (Float) r.statGetModified.invoke(r.engineGetTimeMult.invoke(r.engine));
        Object vel = r.shipGetVelocity.invoke(c.ship);
        o.speed = (float) Math.hypot(r.vecX.getFloat(vel), r.vecY.getFloat(vel));
        o.flux = (Float) r.fluxGetCurr.invoke(r.shipGetFluxTracker.invoke(c.ship));
        o.reinjects = c.binding.fastTimeReinjects();
        o.cancels = c.state.systemCancelPresses();
        o.wireCode = wireCode(c.binding);
        return o;
    }

    /** The wire {@code systemStates} code for the shell (the product read model + mapping), −1 if absent. */
    private static int wireCode(EngineCombatBinding binding) {
        List<ShipSystemView> views = binding.shipSystemStatesOrNull();
        if (views == null) {
            return -1;
        }
        for (ShipSystemView v : views) {
            if (v != null && SYSTEM_ID.equals(v.systemId())) {
                return ShipSystemStateSnapshot.fromView(v, 1L).stateCode();
            }
        }
        return -1;
    }

    private static void setDissipationLever(Ctx c, boolean off) throws Exception {
        Object stat = c.rig.statsGetFluxDissipation.invoke(c.rig.shipGetMutableStats.invoke(c.ship));
        if (off) {
            c.rig.statModifyMult.invoke(stat, DISS_MOD_ID, 0f);
        } else {
            c.rig.statUnmodify.invoke(stat, DISS_MOD_ID);
        }
    }

    /** DECELERATE-held until still, flux-clear and the system IDLE (5 consecutive quiet ticks). */
    private static void rest(Ctx c, String label) throws Exception {
        int still = 0;
        int n = 0;
        Obs o = null;
        while (n++ < c.restCap) {
            o = drive(c, PilotIntentFrame.MOVE_DECELERATE, false);
            boolean quiet = o.speed < 1.0f && o.flux < 1.0f && "IDLE".equals(o.sysState)
                    && o.cooldown <= 0f;
            still = quiet ? still + 1 : 0;
            if (still >= 5) {
                break;
            }
        }
        System.out.println(P + " rest_" + label + " tick=" + c.tick + " n=" + n
                + " speed=" + f1(o.speed) + " flux=" + f1(o.flux) + " sys=" + o.sysState
                + " cooldown=" + f2(o.cooldown) + " quiet=" + (still >= 5));
    }

    // ---------------------------------------------------------------- windows

    /** W0: 20 ACCELERATE ticks from rest → rampV20; then 120 FIRE ticks (coasting) → fluxSlope. */
    private static void baselineWindows(Ctx c, Cycle w) throws Exception {
        Obs o = null;
        for (int i = 0; i < RAMP_TICKS; i++) {
            o = drive(c, PilotIntentFrame.MOVE_ACCELERATE, false);
            w.peakMult = Math.max(w.peakMult, o.shipMult);
            w.engineMaxDev = Math.max(w.engineMaxDev, Math.abs(o.engineMult - 1f));
        }
        w.rampV20 = o.speed;
        setDissipationLever(c, true);
        try {
            float flux0 = o.flux;
            for (int i = 0; i < FIRE_TICKS; i++) {
                o = drive(c, 0, true);
                w.engineMaxDev = Math.max(w.engineMaxDev, Math.abs(o.engineMult - 1f));
            }
            w.fireTicks = FIRE_TICKS;
            w.fluxSlope = (o.flux - flux0) / FIRE_TICKS;
            w.fireEndState = o.sysState;
        } finally {
            setDissipationLever(c, false);
        }
        w.endTick = c.tick;
    }

    /**
     * One shell cycle driven through the bit-7 client-STATE mirror: HIGH from T0 (held through
     * {@link #PRESS_HOLD_TICKS} ticks if the server has not activated yet), then HIGH iff the
     * server's system reads IN|ACTIVE. {@code measure} = run the 20+120 sub-windows at ACTIVE onset
     * (W1); {@code dropAfterActiveTicks} ≥ 0 = W2's cancel no-op (bit LOW from that ACTIVE tick on).
     * Ends when the system returns to IDLE after ACTIVE (a full IN→ACTIVE→OUT→COOLDOWN→IDLE cycle).
     */
    private static Cycle runCycle(Ctx c, boolean measure, int dropAfterActiveTicks, String label)
            throws Exception {
        Cycle cy = new Cycle();
        cy.t0 = c.tick;
        boolean bitHigh = true;
        boolean dropped = false;
        boolean seenActive = false;
        int activeSeen = 0;
        int rampLeft = 0;
        int fireLeft = 0;
        int fireN = 0;
        float fireFlux0 = 0f;
        boolean lever = false;
        long reinj0 = -1;
        String prev = "IDLE";
        float prevEffect = 0f;
        StringBuilder wire = new StringBuilder();
        int lastWire = Integer.MIN_VALUE;
        try {
            for (int n = 0; n < c.cycleCap; n++) {
                int held = bitHigh ? PilotIntentFrame.USE_SHIP_SYSTEM : 0;
                boolean fire = false;
                if (rampLeft > 0) {
                    held |= PilotIntentFrame.MOVE_ACCELERATE;
                } else if (fireLeft > 0) {
                    fire = true;
                }
                int cancelsBefore = c.state.systemCancelPresses();
                Obs o = drive(c, held, fire);
                int cancelDelta = o.cancels - cancelsBefore;
                String s = o.sysState;
                if (o.activated) {
                    cy.activated = true;
                }
                if (cy.tActivate < 0 && !"IDLE".equals(s)) {
                    cy.tActivate = c.tick - 1;
                }
                // Cancel presses land in the apply BEFORE the step — attribute to the pre-step state.
                switch (prev) {
                    case "IN": cy.cancelsIn += cancelDelta; break;
                    case "ACTIVE": cy.cancelsActive += cancelDelta; break;
                    case "OUT": cy.cancelsOut += cancelDelta; break;
                    case "COOLDOWN": cy.cancelsCooldown += cancelDelta; break;
                    default: break;
                }
                switch (s) {
                    case "IN":
                        cy.inTicks++;
                        if ("IN".equals(prev) && o.effect + 1e-4f < prevEffect) {
                            cy.inMonotone = false;
                        }
                        break;
                    case "ACTIVE": cy.activeTicks++; break;
                    case "OUT":
                        cy.outTicks++;
                        if ("OUT".equals(prev) && o.effect - 1e-4f > prevEffect) {
                            cy.outMonotone = false;
                        }
                        break;
                    case "COOLDOWN": cy.cooldownTicks++; break;
                    default: break;
                }
                cy.peakMult = Math.max(cy.peakMult, o.shipMult);
                cy.engineMaxDev = Math.max(cy.engineMaxDev, Math.abs(o.engineMult - 1f));
                if (o.wireCode != lastWire) {
                    if (wire.length() > 0) {
                        wire.append('>');
                    }
                    wire.append(o.wireCode);
                    lastWire = o.wireCode;
                }
                // Sub-window bookkeeping (the drive above already applied this tick's ramp/fire).
                if (rampLeft > 0) {
                    rampLeft--;
                    if (rampLeft == 0) {
                        cy.rampV20 = o.speed;
                        fireLeft = FIRE_TICKS;
                        fireFlux0 = o.flux;
                        setDissipationLever(c, true);
                        lever = true;
                        System.out.println(P + " " + label + "_ramp_done tick=" + c.tick
                                + " v20=" + f2(o.speed) + " sys=" + s + " mult=" + f3(o.shipMult));
                    }
                } else if (fireLeft > 0) {
                    fireLeft--;
                    fireN++;
                    boolean leftActive = !"ACTIVE".equals(s);
                    if (fireLeft == 0 || leftActive) {
                        cy.fluxSlope = (o.flux - fireFlux0) / fireN;
                        cy.fireTicks = fireN;
                        cy.fireEndState = s;
                        fireLeft = 0;
                        setDissipationLever(c, false);
                        lever = false;
                        System.out.println(P + " " + label + "_fire_done tick=" + c.tick + " n=" + fireN
                                + " flux0=" + f1(fireFlux0) + " flux1=" + f1(o.flux)
                                + " slope=" + f3(cy.fluxSlope) + " sys=" + s);
                    }
                }
                if ("ACTIVE".equals(s)) {
                    if (!seenActive) {
                        seenActive = true;
                        reinj0 = o.reinjects;
                        if (measure) {
                            rampLeft = RAMP_TICKS;
                        }
                        System.out.println(P + " " + label + "_active_onset tick=" + c.tick
                                + " in_ticks=" + cy.inTicks + " mult=" + f3(o.shipMult)
                                + " engine_mult=" + f4(o.engineMult) + " reinjects=" + o.reinjects);
                    }
                    activeSeen++;
                    if (dropped && cy.cancelsAfterDrop < 0) {
                        cy.cancelsAfterDrop = cancelDelta;
                        cy.stateAfterDrop = s;
                        System.out.println(P + " " + label + "_after_drop tick=" + c.tick
                                + " cancel_delta=" + cancelDelta + " sys=" + s
                                + " mult=" + f3(o.shipMult));
                    }
                    if (dropAfterActiveTicks >= 0 && activeSeen == dropAfterActiveTicks && !dropped) {
                        dropped = true;
                        cy.dropTick = c.tick;
                    }
                } else if (dropped && cy.cancelsAfterDrop < 0) {
                    cy.cancelsAfterDrop = cancelDelta;
                    cy.stateAfterDrop = s; // the cancel press CUT the window — P7 fails on state
                }
                if (seenActive && "OUT".equals(s) && cy.reinjectsDelta == 0 && reinj0 >= 0) {
                    cy.reinjectsDelta = o.reinjects - reinj0;
                }
                if (n % 30 == 0 || !s.equals(prev)) {
                    System.out.println(P + " " + label + " tick=" + c.tick + " sys=" + s
                            + " eff=" + f3(o.effect) + " cd=" + f2(o.cooldown) + " on=" + o.isOn
                            + " mult=" + f3(o.shipMult) + " engine_mult=" + f4(o.engineMult)
                            + " speed=" + f1(o.speed) + " flux=" + f1(o.flux)
                            + " reinj=" + o.reinjects + " cancels=" + o.cancels
                            + " wire=" + o.wireCode + " bit7=" + bitHigh);
                }
                // Client-STATE mirror for the NEXT tick.
                if (dropped) {
                    bitHigh = false;
                } else if (cy.tActivate < 0) {
                    bitHigh = n + 1 < PRESS_HOLD_TICKS;
                } else {
                    bitHigh = "IN".equals(s) || "ACTIVE".equals(s);
                }
                if (seenActive && "IDLE".equals(s)) {
                    cy.completed = true;
                    if (cy.reinjectsDelta == 0 && reinj0 >= 0) {
                        cy.reinjectsDelta = o.reinjects - reinj0;
                    }
                    break;
                }
                prev = s;
                prevEffect = o.effect;
            }
        } finally {
            if (lever) {
                setDissipationLever(c, false);
            }
        }
        cy.wireSeq = wire.toString();
        cy.endTick = c.tick;
        System.out.println(P + " " + label + "_done tick=" + c.tick + " completed=" + cy.completed
                + " t_activate=" + cy.tActivate + " t0=" + cy.t0 + " activated=" + cy.activated
                + " in/active/out/cooldown=" + cy.inTicks + "/" + cy.activeTicks + "/" + cy.outTicks
                + "/" + cy.cooldownTicks + " peak_mult=" + f3(cy.peakMult)
                + " engine_max_dev=" + f4(cy.engineMaxDev) + " ramp_v20=" + f2(cy.rampV20)
                + " flux_slope=" + f3(cy.fluxSlope) + " reinjects_delta=" + cy.reinjectsDelta
                + " cancels_by_state=" + cy.cancelsIn + "/" + cy.cancelsActive + "/" + cy.cancelsOut
                + "/" + cy.cancelsCooldown + " wire_seq=" + cy.wireSeq
                + " drop_tick=" + cy.dropTick + " cancels_after_drop=" + cy.cancelsAfterDrop
                + " state_after_drop=" + cy.stateAfterDrop);
        return cy;
    }

    // ---------------------------------------------------------------- helpers

    private static Method findOneArg(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }

    private static String f2(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }

    private static String f3(float f) {
        return String.format(Locale.ROOT, "%.3f", f);
    }

    private static String f4(float f) {
        return String.format(Locale.ROOT, "%.4f", f);
    }
}
