package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.PilotApplyState;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.bridge.SeatRebind;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The game-free REAL-ENGINE proof of the refield stale-frame gate (verdict
 * {@code GATE_PROVED}).
 *
 * <p><b>Question:</b> in a live test run, frames sampled off the seat's DEAD hull (vent held) were
 * applied to the refielded hull and its fly-in burn ended after one tick. Does the real engine
 * cut the burn on such a frame, and does {@code PilotApplyState.refieldGateHolds} (armed through
 * the product's own hull-loss + fly-in seams) keep the burn at its control length?
 *
 * <p><b>Boot</b> = {@link RealEngineTemporalShellProbeMain}'s: a one-seat descriptor
 * ({@code -Dcc.match.descriptor}, minted game-free by the wrapper), EMPTY occupancy, then the
 * REAL seat fly-in {@link RealEngineDemoRoster#midDeploySeat(int)}. Every refield = the live
 * suicide verb {@link SeatRebind#forceKillLiveShipForWireId} → the engine's own death → {@link
 * RealEngineDemoRoster#vacateSeatHull} + {@link PilotApplyState#resetRetreatEdgeOnHullLoss} (the
 * serve's despawn drain) → a dead window → {@code midDeploySeat} again.
 *
 * <p><b>Tick order</b> = the serve's (in that run: fly-in snapshot 4095, first apply 4096): fly-in →
 * step(T) with NO apply → from T+1 on {@code applyPilotIntent(frame)} → step. {@code frameSeq}
 * = the client's snapshot echo, the gate's release evidence.
 *
 * <p><b>Arms, ONE boot, ONE apply state:</b>
 * <ul>
 * <li>{@code control} — first mount, neutral fresh frames: the baseline burn length.
 * <li>{@code ungated} — refield, gate NOT armed (the pre-fix server): {@code --stale-ticks}
 *     dead-hull frames (echo &lt; T, {@code --stale-held}), then neutral fresh frames. The defect.
 * <li>{@code gated} — refield, {@link PilotApplyState#armRefieldGateIfPending} at T (the PSS
 *     fly-in seam's call): the SAME stale frames, one capture-before-respawn frame (echo = T,
 *     still stale-held), then neutral fresh frames. The fix: burn ≈ control.
 * <li>{@code fresh_vent} — gated as above, but the fresh frames HOLD vent: the gate must release
 *     and the legitimate press cut the burn (vanilla: VENT is never blocked by the travel
 *     drive). INFO + the "releases, never holds forever" read.
 * </ul>
 * The arm call is the ONLY difference between {@code ungated} and {@code gated}.
 *
 * <p>Per-tick rows carry {@code remainingDriveDur} (private on the engine's {@code Ship}; read
 * reflectively, fail-loud) before the apply, after the apply and after the step, plus
 * {@code isTravelDriveOn} / {@code isTravelDriveWasOn} / venting / flux / velocity.
 *
 * <p>Exit: 0 = {@code GATE_PROVED}; 1 = {@code DEFECT_NOT_REPRODUCED} (the gated arm then proves
 * nothing) / {@code GATE_FAILED} / infra; 2 = boot / fly-in / step threw (stack printed).
 * A wrapper script runs it. NEVER calls {@code getPlayerShip()}.
 * Pure probe, no product code touched.
 */
public final class RealEngineFlyInVentProbeMain {

    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String FLUX_TRACKER_API = "com.fs.starfarer.api.combat.FluxTrackerAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "flyin-vent-probe";
    private static final long SEAT_WIRE_ID = 1L;
    private static final int SEAT_ID = 1;
    private static final float AIM_AHEAD_SU = 1000f;
    /** The drive must read off this many consecutive ticks before an arm ends. */
    private static final int OFF_SETTLE_TICKS = 30;
    private static final int VERBOSE_TICKS = 12;

    private RealEngineFlyInVentProbeMain() {
    }

    private static final class Rig {
        Object engine;
        Method getObjects;
        Method repoGetList;
        Class<?> shipClass;
        Method shipGetFluxTracker;
        Method shipGetVelocity;
        Method shipGetLocation;
        Method shipGetFacing;
        Method shipGetShipAI;
        Method shipSetShipAI;
        Method shipSetHoldFire;
        Method shipGetId;
        Method shipIsAlive;
        Method shipIsHulk;
        Method shipIsTravelDriveOn;
        Method shipIsTravelDriveWasOn;
        Field shipRemainingDriveDur;
        Method fluxGetCurr;
        Method fluxIsVenting;
        Field vecX;
        Field vecY;
    }

    private static final class Ctx {
        Rig rig;
        EngineCombatBinding binding;
        EngineBackedCombatStepBoundary step;
        PilotApplyState state;
        Object ship;
        float fixedDt;
        long tick;
        int armCap;
        int deadTicks;
        int staleTicks;
        int staleHeld;
    }

    /** One arm's read: the burn window in ticks since the fly-in step (k = 0). */
    private static final class Burn {
        String arm;
        long flyInTick;
        int burnTicks;      // post-step observations with isTravelDriveOn true
        int firstOffK = -1; // first k whose post-step read is drive OFF
        int rejected;       // applies the gate (or an unresolved hull) withheld
        int releaseK = -1;  // first accepted apply after a withheld one
        int ventsDelta;
        int represDelta;
        float speedAtOff = Float.NaN;
        boolean wasOnAtOff;
        boolean ventingAtOff;
        boolean gateArmedCall;
        boolean gateArmedAtEnd;
        int gateHeldTicks;
    }

    public static void main(String[] args) throws Exception {
        Ctx c = new Ctx();
        c.fixedDt = 1f / 60f;
        c.armCap = 400;
        c.deadTicks = 60;
        c.staleTicks = 3;
        c.staleHeld = PilotIntentFrame.VENT_FLUX;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--fixed-dt": c.fixedDt = Float.parseFloat(args[++i]); break;
                case "--arm-cap": c.armCap = Integer.parseInt(args[++i]); break;
                case "--dead-ticks": c.deadTicks = Integer.parseInt(args[++i]); break;
                case "--stale-ticks": c.staleTicks = Integer.parseInt(args[++i]); break;
                case "--stale-held": c.staleHeld = Integer.decode(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin fixed_dt=" + c.fixedDt + " arm_cap=" + c.armCap
                + " dead_ticks=" + c.deadTicks + " stale_ticks=" + c.staleTicks
                + " stale_held=0x" + Integer.toHexString(c.staleHeld)
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        Burn control;
        Burn ungated;
        Burn gated;
        Burn freshVent;
        try {
            boot(c);
            control = runArm(c, "control", false, false, false);
            ungated = runArm(c, "ungated", true, false, false);
            gated = runArm(c, "gated", true, true, false);
            freshVent = runArm(c, "fresh_vent", true, true, true);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=BOOT_OR_STEP_THREW tick=" + c.tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        // The defect: a stale vent frame ends the burn well short of control. The fix: the gated
        // arm withholds every dead-hull frame (stale + the one adopt frame), releases, and its
        // burn matches control to the tick grain.
        boolean controlSane = control.burnTicks >= 30 && control.firstOffK > 0;
        boolean defect = controlSane && ungated.burnTicks <= control.burnTicks / 2;
        boolean gateHeld = gated.gateArmedCall && gated.rejected == c.staleTicks + 1
                && !gated.gateArmedAtEnd && gated.ventsDelta == 0 && gated.represDelta == 0;
        boolean gateBurn = Math.abs(gated.burnTicks - control.burnTicks) <= 2;
        boolean released = freshVent.gateArmedCall && !freshVent.gateArmedAtEnd
                && freshVent.releaseK > 0 && freshVent.ventsDelta + freshVent.represDelta > 0;
        String verdict = !controlSane ? "CONTROL_BURN_UNREADABLE"
                : !defect ? "DEFECT_NOT_REPRODUCED"
                : (gateHeld && gateBurn && released) ? "GATE_PROVED" : "GATE_FAILED";
        System.out.println(P + "-end verdict=" + verdict
                + " control_burn=" + control.burnTicks + " ungated_burn=" + ungated.burnTicks
                + " gated_burn=" + gated.burnTicks + " fresh_vent_burn=" + freshVent.burnTicks
                + " defect_reproduced=" + defect + " gate_held=" + gateHeld
                + " gate_burn_matches=" + gateBurn + " gate_released_fresh_vent=" + released
                + " gated_rejected=" + gated.rejected + " gated_release_k=" + gated.releaseK
                + " fresh_vent_release_k=" + freshVent.releaseK
                + " ungated_first_off_k=" + ungated.firstOffK
                + " ungated_was_on_at_off=" + ungated.wasOnAtOff
                + " ungated_venting_at_off=" + ungated.ventingAtOff
                + " end_tick=" + c.tick);
        System.exit("GATE_PROVED".equals(verdict) ? 0 : 1);
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
    }

    private static Rig buildRig() throws Exception {
        ClassLoader loader = RealEngineFlyInVentProbeMain.class.getClassLoader();
        Rig r = new Rig();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        r.engine = engineClass.getMethod("getInstance").invoke(null);
        if (r.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        r.getObjects = engineClass.getMethod("getObjects");
        r.repoGetList = r.getObjects.invoke(r.engine).getClass().getMethod("getList", Class.class);
        r.shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        Class<?> fluxApi = Class.forName(FLUX_TRACKER_API, false, loader);
        Class<?> vec2 = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        r.shipGetFluxTracker = shipApi.getMethod("getFluxTracker");
        r.shipGetVelocity = shipApi.getMethod("getVelocity");
        r.shipGetLocation = shipApi.getMethod("getLocation");
        r.shipGetFacing = shipApi.getMethod("getFacing");
        r.shipGetShipAI = shipApi.getMethod("getShipAI");
        r.shipSetShipAI = findOneArg(r.shipClass, "setShipAI");
        r.shipSetHoldFire = shipApi.getMethod("setHoldFire", boolean.class);
        r.shipGetId = shipApi.getMethod("getId");
        r.shipIsAlive = shipApi.getMethod("isAlive");
        r.shipIsHulk = shipApi.getMethod("isHulk");
        r.shipIsTravelDriveOn = r.shipClass.getMethod("isTravelDriveOn");
        r.shipIsTravelDriveWasOn = r.shipClass.getMethod("isTravelDriveWasOn");
        // Private on the engine's Ship, no getter: fail loud if the name ever moves.
        r.shipRemainingDriveDur = r.shipClass.getDeclaredField("remainingDriveDur");
        r.shipRemainingDriveDur.setAccessible(true);
        r.fluxGetCurr = fluxApi.getMethod("getCurrFlux");
        r.fluxIsVenting = fluxApi.getMethod("isVenting");
        r.vecX = vec2.getField("x");
        r.vecY = vec2.getField("y");
        return r;
    }

    /** Kill-free rig: every non-seat hull loses its AI + holds fire (idempotent per fly-in). */
    private static void quietOthers(Ctx c) throws Exception {
        List<?> ships = (List<?>) c.rig.repoGetList.invoke(c.rig.getObjects.invoke(c.rig.engine),
                c.rig.shipClass);
        for (Object s : new ArrayList<Object>(ships)) {
            if (s == c.ship) {
                continue;
            }
            if (c.rig.shipGetShipAI.invoke(s) != null && c.rig.shipSetShipAI != null) {
                c.rig.shipSetShipAI.invoke(s, new Object[] {null});
            }
            c.rig.shipSetHoldFire.invoke(s, true);
        }
    }

    // ---------------------------------------------------------------- arms

    /**
     * One arm: (refield only) kill the seat hull through the live suicide verb, run the serve's
     * hull-loss calls, idle the dead window; fly the seat in; step T with no apply; then drive
     * frames per the arm until the drive has read off {@link #OFF_SETTLE_TICKS} ticks.
     */
    private static Burn runArm(Ctx c, String arm, boolean refield, boolean armGate,
            boolean freshVent) throws Exception {
        Burn b = new Burn();
        b.arm = arm;
        if (refield) {
            killAndVacate(c, arm);
        }
        long flyInTick = c.tick; // the first snapshot tick that carries the fresh hull
        b.flyInTick = flyInTick;
        c.ship = RealEngineDemoRoster.midDeploySeat(SEAT_ID);
        if (armGate) {
            // The PSS fly-in seam's call (PilotServeSession: armRefieldGateAtFlyIn(wire, lastSimTick + 1)).
            b.gateArmedCall = c.state.armRefieldGateIfPending(flyInTick);
        }
        quietOthers(c);
        int vents0 = c.state.ventsIssued();
        int repres0 = c.state.ventRePresses();
        System.out.println(P + " arm=" + arm + " fly_in tick=" + flyInTick
                + " id=" + c.rig.shipGetId.invoke(c.ship) + " gate_arm_call=" + b.gateArmedCall
                + " gate_armed=" + c.state.refieldGateArmed());
        // k = 0: the fly-in step itself, no apply (the serve's order).
        float rdd0 = rdd(c);
        c.step.step(c.fixedDt, c.tick++);
        // The serve pins the shim in its grace-detach step; same public seam, not asserted here.
        c.binding.installFastTimeReinjectShim(c.ship);
        observe(c, b, arm, 0, flyInTick, -1, "none", rdd0, rdd0);

        int offRun = 0;
        boolean sawReject = false;
        for (int k = 1; k <= c.armCap; k++) {
            int held;
            long echo;
            if (refield && k <= c.staleTicks) {
                held = c.staleHeld;                       // sampled off the dead hull
                echo = flyInTick - c.staleTicks + (k - 1); // < T: the client has not seen the fresh hull
            } else if (refield && armGate && k == c.staleTicks + 1) {
                held = c.staleHeld;                       // capture-before-respawn: echo = T, still the dead sample
                echo = flyInTick;
            } else {
                held = freshVent && k <= c.staleTicks + 4 ? PilotIntentFrame.VENT_FLUX : 0;
                echo = flyInTick + k;                     // fresh-hull samples, echo strictly rising
            }
            float rddPre = rdd(c);
            c.binding.beginReinjectTick();
            EngineCombatBinding.PilotApplyResult res =
                    c.binding.applyPilotIntent(frame(c, (int) echo, held), c.tick, c.state);
            float rddApplied = rdd(c);
            if (!res.accepted()) {
                b.rejected++;
                sawReject = true;
            } else if (sawReject && b.releaseK < 0) {
                b.releaseK = k;
            }
            c.step.step(c.fixedDt, c.tick++);
            boolean on = observe(c, b, arm, k, echo, held,
                    res.accepted() ? "applied" : "withheld", rddPre, rddApplied);
            offRun = on ? 0 : offRun + 1;
            if (offRun >= OFF_SETTLE_TICKS) {
                break;
            }
        }
        b.ventsDelta = c.state.ventsIssued() - vents0;
        b.represDelta = c.state.ventRePresses() - repres0;
        b.gateArmedAtEnd = c.state.refieldGateArmed();
        b.gateHeldTicks = c.state.refieldGateHeldTicks();
        System.out.println(P + " arm_done arm=" + arm + " fly_in_tick=" + flyInTick
                + " burn_ticks=" + b.burnTicks + " first_off_k=" + b.firstOffK
                + " was_on_at_off=" + b.wasOnAtOff + " venting_at_off=" + b.ventingAtOff
                + " speed_at_off=" + f1(b.speedAtOff) + " rejected=" + b.rejected
                + " release_k=" + b.releaseK + " vents=" + b.ventsDelta
                + " vent_represses=" + b.represDelta + " gate_arm_call=" + b.gateArmedCall
                + " gate_armed_at_end=" + b.gateArmedAtEnd + " gate_held_ticks=" + b.gateHeldTicks
                + " end_tick=" + c.tick);
        return b;
    }

    private static void killAndVacate(Ctx c, String arm) throws Exception {
        boolean killed = SeatRebind.forceKillLiveShipForWireId(SEAT_WIRE_ID);
        int n = 0;
        while (n++ < 600 && (Boolean) c.rig.shipIsAlive.invoke(c.ship)
                && !(Boolean) c.rig.shipIsHulk.invoke(c.ship)) {
            c.step.step(c.fixedDt, c.tick++);
        }
        boolean dead = !(Boolean) c.rig.shipIsAlive.invoke(c.ship)
                || (Boolean) c.rig.shipIsHulk.invoke(c.ship);
        if (!killed || !dead) {
            throw new IllegalStateException("seat hull not killed before arm " + arm
                    + ": kill_call=" + killed + " dead=" + dead + " steps=" + n);
        }
        c.step.step(c.fixedDt, c.tick++); // one settle step (the serve observes death on a snapshot)
        // The serve's despawn drain: vacate the live map, then the apply state's hull-loss reset
        // (which also sets the gate's pending flag — armed only if the arm asks at the fly-in).
        RealEngineDemoRoster.vacateSeatHull(SEAT_ID, SEAT_WIRE_ID);
        c.state.resetRetreatEdgeOnHullLoss();
        for (int i = 0; i < c.deadTicks; i++) {
            c.step.step(c.fixedDt, c.tick++);
        }
        System.out.println(P + " arm=" + arm + " hull_lost tick=" + c.tick + " kill_steps=" + n
                + " dead_ticks=" + c.deadTicks);
    }

    private static PilotIntentFrame frame(Ctx c, int echo, int held) throws Exception {
        float facing = (Float) c.rig.shipGetFacing.invoke(c.ship);
        facing = ((facing % 360f) + 360f) % 360f;
        Object loc = c.rig.shipGetLocation.invoke(c.ship);
        double rad = Math.toRadians(facing);
        float aimX = c.rig.vecX.getFloat(loc) + (float) (Math.cos(rad) * AIM_AHEAD_SU);
        float aimY = c.rig.vecY.getFloat(loc) + (float) (Math.sin(rad) * AIM_AHEAD_SU);
        return new PilotIntentFrame(SEAT_WIRE_ID, Math.max(0, echo), facing, held, false, 0,
                facing, aimX, aimY, 0, PilotIntentFrame.TARGET_ABSENT, 0,
                PilotIntentFrame.TUBE_ORDINAL_ABSENT);
    }

    private static float rdd(Ctx c) throws Exception {
        return c.rig.shipRemainingDriveDur.getFloat(c.ship);
    }

    /** Post-step read for tick k; folds it into the burn window and prints the row. */
    private static boolean observe(Ctx c, Burn b, String arm, int k, long echo, int held,
            String apply, float rddPre, float rddApplied) throws Exception {
        Rig r = c.rig;
        boolean on = (Boolean) r.shipIsTravelDriveOn.invoke(c.ship);
        boolean wasOn = (Boolean) r.shipIsTravelDriveWasOn.invoke(c.ship);
        Object flux = r.shipGetFluxTracker.invoke(c.ship);
        boolean venting = (Boolean) r.fluxIsVenting.invoke(flux);
        Object vel = r.shipGetVelocity.invoke(c.ship);
        Object loc = r.shipGetLocation.invoke(c.ship);
        float vx = r.vecX.getFloat(vel);
        float vy = r.vecY.getFloat(vel);
        boolean edge = false;
        if (on) {
            b.burnTicks++;
        } else if (b.firstOffK < 0) {
            b.firstOffK = k;
            b.wasOnAtOff = wasOn;
            b.ventingAtOff = venting;
            b.speedAtOff = (float) Math.hypot(vx, vy);
            edge = true;
        }
        if (k <= VERBOSE_TICKS || edge || k % 20 == 0) {
            System.out.println(P + " row arm=" + arm + " k=" + k + " tick=" + (c.tick - 1)
                    + " echo=" + echo + " held=" + (held < 0 ? "-" : "0x" + Integer.toHexString(held))
                    + " apply=" + apply + " rdd_pre=" + f3(rddPre) + " rdd_applied=" + f3(rddApplied)
                    + " rdd_stepped=" + f3(rdd(c)) + " drive_on=" + on + " drive_was_on=" + wasOn
                    + " venting=" + venting + " flux=" + f1((Float) r.fluxGetCurr.invoke(flux))
                    + " vel=" + f1(vx) + "," + f1(vy)
                    + " loc=" + f1(r.vecX.getFloat(loc)) + "," + f1(r.vecY.getFloat(loc))
                    + " gate_armed=" + c.state.refieldGateArmed());
        }
        return on;
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

    private static String f3(float f) {
        return String.format(Locale.ROOT, "%.3f", f);
    }
}
