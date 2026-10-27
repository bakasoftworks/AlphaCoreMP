package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Zero-hull boot PROBE: does the real engine step a combat with ZERO
 * deployed hulls, and do seats then fly in one at a time onto that live, initially-empty
 * battlefield? This is the load-bearing unknown under the whole gauntlet posture —
 * the N-peer host boots pre-first-claim with an occupancy-gated deploy of the EMPTY set
 * ({@link RealEngineCombatBindingFactory#create(java.util.SortedSet)}) and every hull arrives via
 * claim&rArr;{@link RealEngineDemoRoster#midDeploySeat} — nothing in the engine was ever proven to
 * tolerate an advance loop over a shipless repository.
 *
 * <p><b>What it drives</b> (a MEASUREMENT main, never wired into the product serve loop):
 * gated boot under a {@code gauntlet:2} descriptor ({@code -Dcc.match.descriptor}) &rarr; window A:
 * {@code windowAFrames} steps over the zero-hull battlefield (repo count + combat-map sampled every
 * frame; any throw = the exact boundary this probe exists to capture, verdict
 * {@code ZERO_HULL_STEP_THREW} exit 2) &rarr; seat-2 fly-in (certified Mudskipper, side 1) + drain +
 * fly-in window &rarr; the deferred message-widget self-check
 * ({@link RealEngineCombatBindingFactory#runDeferredMessageWidgetShipPostSelfCheck()}, expect PASS)
 * &rarr; seat-1 fly-in (gauntlet Onslaught owner, side 0 — exercises the SECOND registered fleet
 * manager) + drain &rarr; strip ALL ship AIs (kill-free) &rarr; {@code dualFrames} dual-ship frames.
 *
 * <p><b>NEVER calls {@code getPlayerShip()}.</b> Under a gated boot the engine player seat stays
 * UNSET for the WHOLE match (the install's W3 player-seat write is conditional on &ge;1 deployed
 * ship and fly-ins never set it), and the engine's pre-roster {@code getPlayerShip()} fallback
 * fabricates a "Command Shuttle" whose creation path NULLS the combat map. The
 * combat-map sample here doubles as the trip-wire for any accidental such read.
 *
 * <p>Exit codes: 0 = verdict {@code ZERO_HULL_BOOT_PROVEN} (repo stayed 0 through window A, map
 * never nulled, both fly-ins alive at end); 2 = a boundary captured (zero-hull step threw, a
 * fly-in threw, or the deferred self-check threw — stack printed); 1 = infrastructure failure.
 *
 */
public final class RealEngineZeroHullProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineMidDeployProbeMain. ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "zero-hull-probe";

    private RealEngineZeroHullProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int windowAFrames = 300;  // 5 s at 60 fps of honest zero-hull stepping
        int flyinFrames = 600;    // 10 s: burn expiry + fly-in trajectory (probe-proven window)
        int dualFrames = 300;     // 5 s: both seats live, AI-stripped, kill-free
        float fixedDt = SimTickRate.fixedDt();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--window-a-frames": windowAFrames = Integer.parseInt(args[++i]); break;
                case "--flyin-frames": flyinFrames = Integer.parseInt(args[++i]); break;
                case "--dual-frames": dualFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin window_a_frames=" + windowAFrames
                + " flyin_frames=" + flyinFrames + " dual_frames=" + dualFrames
                + " fixed_dt=" + fixedDt
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        // ---- The gated boot the serveNPeer path will use: EMPTY occupancy, descriptor-driven. ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEngineZeroHullProbeMain.class.getClassLoader();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
        Object engine = engineClass.getMethod("getInstance").invoke(null);
        if (engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        Method getObjects = engineClass.getMethod("getObjects");
        Method repoGetList = getObjects.invoke(engine).getClass().getMethod("getList", Class.class);
        Method getCombatMap = engineClass.getMethod("getCombatMap");
        Method isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
        Method shipGetLocation = shipClass.getMethod("getLocation");
        Method shipGetVelocity = shipClass.getMethod("getVelocity");
        Method shipGetFacing = shipClass.getMethod("getFacing");
        Method shipGetShipAI = shipClass.getMethod("getShipAI");
        Method shipSetShipAI = findOneArg(shipClass, "setShipAI");
        Class<?> vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        Field vecX = vec2Class.getField("x");
        Field vecY = vec2Class.getField("y");

        int bootRepo = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
        boolean bootMapNonNull = getCombatMap.invoke(engine) != null;
        System.out.println(P + " boot repo_ships=" + bootRepo + " map_nonNull=" + bootMapNonNull);
        if (bootRepo != 0) {
            System.out.println(P + "-end verdict=FAILED reason=boot_repo_not_empty repo=" + bootRepo);
            System.exit(1);
            return;
        }
        if (!bootMapNonNull) {
            System.out.println(P + "-end verdict=FAILED reason=boot_map_null");
            System.exit(1);
            return;
        }

        // ---- Window A: step the shipless battlefield. Any throw IS the finding (exit 2). ----
        long tick = 0;
        boolean repoZeroThroughA = true;
        boolean mapNeverNull = true;
        try {
            for (int f = 0; f < windowAFrames; f++) {
                step.step(fixedDt, tick++);
                int repoNow = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
                boolean mapNow = getCombatMap.invoke(engine) != null;
                if (repoNow != 0) {
                    repoZeroThroughA = false;
                }
                if (!mapNow) {
                    mapNeverNull = false;
                }
                if (f % 60 == 0 || f == windowAFrames - 1) {
                    System.out.println(P + " windowA tick=" + tick + " repo_ships=" + repoNow
                            + " map_nonNull=" + mapNow);
                }
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=ZERO_HULL_STEP_THREW tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        System.out.println(P + " windowA_done tick=" + tick
                + " repo_zero_through_a=" + repoZeroThroughA + " map_never_null=" + mapNeverNull);

        // ---- Seat-2 fly-in (certified Mudskipper, side 1): the round's FIRST hull. ----
        Object ship2 = flyIn(2, step, fixedDt, tick);
        tick++; // the drain step flyIn() ran
        List<?> repoAfter2 = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        boolean drained2 = repoAfter2.size() == 1;
        boolean alive2 = (Boolean) isShipAlive.invoke(engine, ship2);
        boolean map2 = getCombatMap.invoke(engine) != null;
        if (!map2) {
            mapNeverNull = false;
        }
        System.out.println(P + " seat2_drained tick=" + tick + " repo_ships=" + repoAfter2.size()
                + " drained=" + drained2 + " alive=" + alive2 + " map_nonNull=" + map2);

        float[] spawnPos = vecF(shipGetLocation.invoke(ship2), vecX, vecY);
        for (int f = 0; f < flyinFrames; f++) {
            step.step(fixedDt, tick++);
            if (f % 60 == 0 || f == flyinFrames - 1) {
                System.out.println(P + " seat2_flyin tick=" + tick
                        + " pos=" + vec(shipGetLocation.invoke(ship2), vecX, vecY)
                        + " vel=" + vec(shipGetVelocity.invoke(ship2), vecX, vecY)
                        + " facing=" + f1(((Number) shipGetFacing.invoke(ship2)).floatValue()));
            }
        }
        float[] flyinEnd = vecF(shipGetLocation.invoke(ship2), vecX, vecY);
        float flewSu = (float) Math.hypot(flyinEnd[0] - spawnPos[0], flyinEnd[1] - spawnPos[1]);
        System.out.println(P + " seat2_flyin_done tick=" + tick + " flew_su=" + f1(flewSu));

        // ---- The deferred message-widget self-check: first fly-in landed, run it now (expect PASS). ----
        try {
            RealEngineCombatBindingFactory.runDeferredMessageWidgetShipPostSelfCheck();
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=DEFERRED_SELFCHECK_THREW tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        // ---- Seat-1 fly-in (gauntlet Onslaught owner, side 0): the SECOND registered manager. ----
        Object ship1 = flyIn(1, step, fixedDt, tick);
        tick++;
        List<?> repoAfter1 = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        boolean drained1 = repoAfter1.size() == 2;
        boolean alive1 = (Boolean) isShipAlive.invoke(engine, ship1);
        System.out.println(P + " seat1_drained tick=" + tick + " repo_ships=" + repoAfter1.size()
                + " drained=" + drained1 + " alive=" + alive1);

        // ---- Strip ALL ship AIs + autofire (kill-free dual window). The AI strip is the
        // mid-deploy-probe idiom; the autofire-off is the first-run lesson — autofire weapon
        // groups keep firing with the ship AI stripped (the XIV Elite carries five autofire
        // groups incl. targetingunit-boosted hveldrivers, and the first run's motionless hostile
        // Mudskipper ~840 su away was shot dead late in the dual window: an engine-FAITHFUL
        // kill, but a measurement confound here). Both use the engine's own surfaces
        // (setShipAI(null) / WeaponGroupAPI.toggleOff()). ----
        List<Object> allShips = new ArrayList<Object>(
                (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass));
        Method getWeaponGroupsCopy = shipClass.getMethod("getWeaponGroupsCopy");
        int stripped = 0;
        int autofireOff = 0;
        for (Object s : allShips) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
                stripped++;
            }
            for (Object group : (List<?>) getWeaponGroupsCopy.invoke(s)) {
                if ((Boolean) group.getClass().getMethod("isAutofiring").invoke(group)) {
                    group.getClass().getMethod("toggleOff").invoke(group);
                    autofireOff++;
                }
            }
        }
        System.out.println(P + " dual_begin tick=" + tick + " ships=" + allShips.size()
                + " ai_stripped=" + stripped + " autofire_groups_off=" + autofireOff);
        for (int f = 0; f < dualFrames; f++) {
            step.step(fixedDt, tick++);
            if (f % 60 == 0 || f == dualFrames - 1) {
                int repoNow = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
                StringBuilder sb = new StringBuilder(P + " dual tick=" + tick + " repo=" + repoNow);
                for (int i = 0; i < allShips.size(); i++) {
                    Object s = allShips.get(i);
                    sb.append(" s").append(i)
                            .append("_pos=").append(vec(shipGetLocation.invoke(s), vecX, vecY))
                            .append(" s").append(i)
                            .append("_vel=").append(vec(shipGetVelocity.invoke(s), vecX, vecY))
                            .append(" s").append(i)
                            .append("_alive=").append(isShipAlive.invoke(engine, s));
                }
                System.out.println(sb);
            }
        }

        boolean endAlive2 = (Boolean) isShipAlive.invoke(engine, ship2);
        boolean endAlive1 = (Boolean) isShipAlive.invoke(engine, ship1);
        boolean endMap = getCombatMap.invoke(engine) != null;
        if (!endMap) {
            mapNeverNull = false;
        }
        List<?> repoEndList = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        int repoEnd = repoEndList.size();
        Method shipGetId = shipClass.getMethod("getId");
        for (int i = 0; i < repoEndList.size(); i++) {
            Object s = repoEndList.get(i);
            System.out.println(P + " repo_end i=" + i + " id=" + shipGetId.invoke(s)
                    + " alive=" + isShipAlive.invoke(engine, s)
                    + " pos=" + vec(shipGetLocation.invoke(s), vecX, vecY));
        }
        boolean pass = repoZeroThroughA && mapNeverNull && drained2 && drained1
                && endAlive2 && endAlive1 && flewSu > 0f;
        System.out.println(P + "-end verdict=" + (pass ? "ZERO_HULL_BOOT_PROVEN" : "FAILED")
                + " repo_zero_through_a=" + repoZeroThroughA
                + " map_never_null=" + mapNeverNull
                + " seat2_drained=" + drained2
                + " seat1_drained=" + drained1
                + " seat2_alive_end=" + endAlive2
                + " seat1_alive_end=" + endAlive1
                + " flew_su=" + f1(flewSu)
                + " repo_ships_end=" + repoEnd
                + " ai_stripped=" + stripped);
        System.exit(pass ? 0 : 1);
    }

    /**
     * One seat fly-in through the product lane ({@link RealEngineDemoRoster#midDeploySeat}) plus
     * the single drain step that moves the spawned ship off the engine's pending-add queue. A
     * throw in the product lane is a captured boundary (verdict {@code FLY_IN_THREW}, exit 2).
     */
    private static Object flyIn(int seatId, EngineBackedCombatStepBoundary step, float fixedDt,
            long tick) {
        try {
            Object ship = RealEngineDemoRoster.midDeploySeat(seatId);
            step.step(fixedDt, tick);
            return ship;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=FLY_IN_THREW seat=" + seatId + " tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            throw new IllegalStateException("unreachable");
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
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

    private static String vec(Object v, Field x, Field y) throws Exception {
        return v == null ? "null" : "(" + f1(x.getFloat(v)) + "," + f1(y.getFloat(v)) + ")";
    }

    private static float[] vecF(Object v, Field x, Field y) throws Exception {
        return new float[]{x.getFloat(v), y.getFloat(v)};
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }
}
