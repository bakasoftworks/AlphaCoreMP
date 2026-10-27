package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * D4.1 fly-in feasibility PROBE: deploy ONE extra ship into a LIVE, non-empty,
 * mid-combat engine at tick N&gt;0 through the engine's OWN mid-combat reserve-deploy lane —
 * {@code CombatFleetManager.spawnFleetMember -> deploy -> instantiateForCombat -> addObject}
 * on the EXISTING side-1 manager the t=0 roster install registered via {@code addFleet} —
 * then characterize the AFK shape (ship with a stripped AI receiving zero intents).
 *
 * <p><b>Why no product change is needed.</b> The "requires a fresh empty repository" throw
 * ({@code HeadlessCombatContext.installEngineDeployRoster}) guards the t=0 INSTALL
 * RITUAL (wholesale repository/fleet-manager swap), not the engine lane: the engine's
 * {@code deploy(DeploymentSpec)} is re-entrant by design (the command-UI reserve deploy is
 * this same path), so a mid-match deploy calls
 * {@code spawnFleetMember} on the existing manager and never enters the install ritual. This
 * probe is a MEASUREMENT main (never wired into the product serve loop): fail-closed by
 * construction, one process, exits when done.
 *
 * <p><b>The two load-bearing unknowns it measures</b> (static RE): steady-state
 * {@code isPreDeploy()} is FALSE during the tick loop (install restores the prior value), so a
 * mid-tick spawn reaches the {@code !isPreDeploy()} travel-drive fly-in branch
 * which derefs {@code getContext()}; whether {@code getContext()} is non-null in
 * our boot (the engine derefs it unconditionally on EVERY deploy and the t=0 installs never NPE,
 * so non-null is the inference) is read back directly here. An explicit burn duration
 * ({@code spawnFleetMember}'s 4th arg &gt;= 0) zeroes the {@code Math.random()} jitter, so the
 * fly-in is deterministic. Location {@code null} exercises the engine's OWN reinforcement
 * edge-placement math (explicit locations bypass it).
 *
 * <p><b>AFK half.</b> The roster pair's AI is stripped through the engine's own
 * {@code setShipAI(null)} — the SAME call {@code EngineCombatBinding.maybeSuppressShipAi} makes
 * for piloted seats — right after the pre-window (the first run left the 1v1 AI live for ~25 s
 * and one ship was killed: the hulk drifted at constant velocity+spin until engine cleanup
 * removed it, confounding the AFK read). The reinforcement keeps its deploy AI for the fly-in,
 * loses it at the AFK boundary; the AFK window then samples position/velocity/facing with zero
 * intents arriving, answering the D4 "leave = AFK pilotless" shape question.
 *
 * <p><b>Live-view caveat (first-run lesson).</b> {@code getObjects().getList(Ship.class)}
 * returns the repository's LIVE per-class list, not a copy — counts must be snapshotted as
 * ints and sampling sets copied, or before/after comparisons alias the same mutating list.
 *
 * <p>Exit codes: 0 = deploy landed + drained + alive + full AFK window stepped clean;
 * 2 = the spawn call itself threw (the exact boundary D4.1 exists to capture — stack printed);
 * 1 = infrastructure failure.
 *
 */
public final class RealEngineMidDeployProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineDemoRoster (Option R). ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String HULL_VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String VARIANT_REGISTRY_CLASS = "com.fs.starfarer.loading.B";
    private static final String VARIANT_REGISTRY_METHOD = "o00000";
    private static final String FLEET_MEMBER_CLASS = "com.fs.starfarer.campaign.fleet.FleetMember";
    private static final String FLEET_MEMBER_TYPE_API = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "mid-deploy-probe";

    private RealEngineMidDeployProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int preFrames = 300;      // 5 s at 60 fps: combat is live + settled, tick N is honest mid-match
        int flyinFrames = 600;    // 10 s: burn expiry + AI-driven fly-in trajectory
        int afkFrames = 900;      // 15 s: zero-intent drift characterization
        float burnDur = 4.0f;     // explicit -> deterministic (zeroes the Math.random() jitter)
        float fixedDt = SimTickRate.fixedDt();
        String variantId = System.getProperty("cc.server.demoVariant", "mudskipper2_cc_Hellbore");
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--pre-frames": preFrames = Integer.parseInt(args[++i]); break;
                case "--flyin-frames": flyinFrames = Integer.parseInt(args[++i]); break;
                case "--afk-frames": afkFrames = Integer.parseInt(args[++i]); break;
                case "--burn-dur": burnDur = Float.parseFloat(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin pre_frames=" + preFrames + " flyin_frames=" + flyinFrames
                + " afk_frames=" + afkFrames + " burn_dur=" + burnDur + " fixed_dt=" + fixedDt
                + " variant=" + variantId);

        // ---- Boot the SAME product lane the serve loop uses (L3 substrate + content + t=0 roster). ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEngineMidDeployProbeMain.class.getClassLoader();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
        Object engine = engineClass.getMethod("getInstance").invoke(null);
        if (engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after create()");
        }
        Method getObjects = engineClass.getMethod("getObjects");
        Method repoGetList = getObjects.invoke(engine).getClass().getMethod("getList", Class.class);
        Method isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
        Method getFleetManager = engineClass.getMethod("getFleetManager", int.class);
        Method isPreDeploy = engineClass.getMethod("isPreDeploy");
        Method getContext = engineClass.getMethod("getContext");
        // Public ShipAPI reads (identity-clean) + the binding's own suppression call.
        Method shipGetLocation = shipClass.getMethod("getLocation");
        Method shipGetVelocity = shipClass.getMethod("getVelocity");
        Method shipGetFacing = shipClass.getMethod("getFacing");
        Method shipGetTravelDrive = shipClass.getMethod("getTravelDrive");
        Method shipGetShipAI = shipClass.getMethod("getShipAI");
        Method shipSetShipAI = findOneArg(shipClass, "setShipAI");
        Class<?> vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        Field vecX = vec2Class.getField("x");
        Field vecY = vec2Class.getField("y");

        // ---- Pre-window: honest mid-combat state before the spawn. ----
        long tick = 0;
        for (int f = 0; f < preFrames; f++) {
            step.step(fixedDt, tick++);
        }
        // getList returns the repository's LIVE list — snapshot the count now, not the list.
        List<?> shipsBefore = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        int shipsBeforeCount = shipsBefore.size();
        boolean preDeployAtN = (Boolean) isPreDeploy.invoke(engine);
        Object context = getContext.invoke(engine);
        Object manager1 = getFleetManager.invoke(engine, 1);
        // Strip the roster pair's AI BEFORE the spawn: the pre-window stays honest live combat,
        // but the fly-in/AFK windows are kill-free (first run: live 1v1 hulked a ship mid-probe).
        int rosterStripped = 0;
        for (Object s : new ArrayList<Object>(shipsBefore)) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
                rosterStripped++;
            }
        }
        System.out.println(P + " pre_spawn tick=" + tick
                + " repo_ships=" + shipsBeforeCount
                + " isPreDeploy=" + preDeployAtN
                + " context_nonNull=" + (context != null)
                + " context_class=" + (context == null ? "null" : context.getClass().getName())
                + " manager1_nonNull=" + (manager1 != null)
                + " roster_ai_stripped=" + rosterStripped);
        if (manager1 == null) {
            throw new IllegalStateException("getFleetManager(1) null mid-combat — addFleet registration lost");
        }

        // ---- The reinforcement member: same faithful recipe as the t=0 roster (with the crew fix). ----
        Class<?> registryClass = Class.forName(VARIANT_REGISTRY_CLASS, true, loader);
        Object variant = registryClass.getMethod(VARIANT_REGISTRY_METHOD, String.class).invoke(null, variantId);
        Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
        if (variant == null || !hullVariantClass.isInstance(variant)) {
            throw new IllegalStateException("variant '" + variantId + "' did not resolve mid-probe");
        }
        Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
        Class<?> memberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
        Object member = fleetMemberClass
                .getConstructor(int.class, hullVariantClass, memberTypeClass)
                .newInstance(1, variant, memberTypeClass.getField("SHIP").get(null));
        fleetMemberClass.getMethod("setShipName", String.class).invoke(member, "CC Reinforce 3L");
        Object crewComp = fleetMemberClass.getMethod("getCrewComposition").invoke(member);
        float minCrew = ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(member)).floatValue();
        crewComp.getClass().getMethod("setCrew", float.class).invoke(crewComp, Math.max(minCrew, 1.0f));
        boolean canDeploy = (Boolean) fleetMemberClass.getMethod("canBeDeployedForCombat").invoke(member);
        System.out.println(P + " member_built owner=1 variant=" + variantId
                + " minCrew=" + minCrew + " canBeDeployedForCombat=" + canDeploy);

        // ---- The mid-combat spawn at tick N: engine-own lane, existing manager, null location
        // (engine's OWN reinforcement edge placement), explicit burn (deterministic fly-in). ----
        Class<?> fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
        Method spawnFleetMember = manager1.getClass().getMethod(
                "spawnFleetMember", fleetMemberApiClass, vec2Class, float.class, float.class);
        Method getDeployedCopy = manager1.getClass().getMethod("getDeployedCopy");
        Object newShip;
        try {
            newShip = spawnFleetMember.invoke(manager1, member, null, 0.0f, burnDur);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.out.println(P + "-end verdict=SPAWN_THREW tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        boolean shipReturned = newShip != null && shipClass.isInstance(newShip);
        List<?> deployedLedger = (List<?>) getDeployedCopy.invoke(manager1);
        boolean ledgerHasMember = deployedLedger.contains(member);
        List<?> shipsSameFrame = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        Object travelDrive = shipReturned ? shipGetTravelDrive.invoke(newShip) : null;
        boolean driveOn = travelDrive != null
                && (Boolean) travelDrive.getClass().getMethod("isOn").invoke(travelDrive);
        System.out.println(P + " spawned tick=" + tick
                + " ship_returned=" + shipReturned
                + " deployed_ledger_contains_member=" + ledgerHasMember
                + " repo_ships_same_frame=" + shipsSameFrame.size() + " (pending-add, pre-drain)"
                + " travel_drive_nonNull=" + (travelDrive != null)
                + " travel_drive_on=" + driveOn
                + " pos=" + vec(shipGetLocation.invoke(newShip), vecX, vecY)
                + " facing=" + f1(((Number) shipGetFacing.invoke(newShip)).floatValue())
                + " ai_nonNull=" + (shipGetShipAI.invoke(newShip) != null));

        // ---- One step: the engine's own executeAdds drain performs the two writes. ----
        step.step(fixedDt, tick++);
        List<?> shipsAfterDrain = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        boolean alive = (Boolean) isShipAlive.invoke(engine, newShip);
        boolean drained = shipsAfterDrain.size() == shipsBeforeCount + 1;
        System.out.println(P + " drained tick=" + tick
                + " repo_ships=" + shipsAfterDrain.size()
                + " drained=" + drained + " isShipAlive=" + alive);

        // ---- Fly-in window: the deployed ship's own trajectory (live deploy AI, travel drive). ----
        float[] spawnPos = vecF(shipGetLocation.invoke(newShip), vecX, vecY);
        for (int f = 0; f < flyinFrames; f++) {
            step.step(fixedDt, tick++);
            if (f % 60 == 0 || f == flyinFrames - 1) {
                Object td = shipGetTravelDrive.invoke(newShip);
                boolean on = td != null && (Boolean) td.getClass().getMethod("isOn").invoke(td);
                System.out.println(P + " flyin tick=" + tick
                        + " pos=" + vec(shipGetLocation.invoke(newShip), vecX, vecY)
                        + " vel=" + vec(shipGetVelocity.invoke(newShip), vecX, vecY)
                        + " facing=" + f1(((Number) shipGetFacing.invoke(newShip)).floatValue())
                        + " travel_drive_on=" + on);
            }
        }
        float[] flyinEnd = vecF(shipGetLocation.invoke(newShip), vecX, vecY);
        float flewSu = (float) Math.hypot(flyinEnd[0] - spawnPos[0], flyinEnd[1] - spawnPos[1]);

        // ---- AFK window: strip the remaining AI (the reinforcement's deploy AI; roster already
        // stripped pre-spawn), zero intents, and measure what the engine does with pilotless
        // ships. COPY the live list so indices stay stable if the engine removes a ship. ----
        List<Object> allShips = new ArrayList<Object>(
                (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass));
        int stripped = 0;
        for (Object s : allShips) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
                stripped++;
            }
        }
        float[][] afkStartVel = new float[allShips.size()][];
        for (int i = 0; i < allShips.size(); i++) {
            afkStartVel[i] = vecF(shipGetVelocity.invoke(allShips.get(i)), vecX, vecY);
        }
        System.out.println(P + " afk_begin tick=" + tick + " ships=" + allShips.size()
                + " ai_stripped=" + stripped);
        for (int f = 0; f < afkFrames; f++) {
            step.step(fixedDt, tick++);
            if (f % 90 == 0 || f == afkFrames - 1) {
                int repoNow = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
                StringBuilder sb = new StringBuilder(P + " afk tick=" + tick + " repo_ships=" + repoNow);
                for (int i = 0; i < allShips.size(); i++) {
                    Object s = allShips.get(i);
                    sb.append(" s").append(i)
                            .append("_pos=").append(vec(shipGetLocation.invoke(s), vecX, vecY))
                            .append(" s").append(i)
                            .append("_vel=").append(vec(shipGetVelocity.invoke(s), vecX, vecY))
                            .append(" s").append(i)
                            .append("_facing=").append(f1(((Number) shipGetFacing.invoke(s)).floatValue()));
                }
                System.out.println(sb);
            }
        }
        StringBuilder afkSummary = new StringBuilder();
        for (int i = 0; i < allShips.size(); i++) {
            float[] v = vecF(shipGetVelocity.invoke(allShips.get(i)), vecX, vecY);
            afkSummary.append(" s").append(i)
                    .append("_speed_at_strip=").append(f1(mag(afkStartVel[i])))
                    .append(" s").append(i)
                    .append("_speed_end=").append(f1(mag(v)));
        }

        boolean stillAlive = (Boolean) isShipAlive.invoke(engine, newShip);
        int repoEnd = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
        boolean pass = shipReturned && ledgerHasMember && drained && alive && stillAlive;
        System.out.println(P + "-end verdict=" + (pass ? "DEPLOY_AT_TICK_N_PROVEN" : "FAILED")
                + " ship_returned=" + shipReturned
                + " ledger=" + ledgerHasMember
                + " drained=" + drained
                + " alive_after_drain=" + alive
                + " alive_after_afk=" + stillAlive
                + " isPreDeploy_at_N=" + preDeployAtN
                + " context_nonNull=" + (context != null)
                + " flew_su=" + f1(flewSu)
                + " roster_ai_stripped=" + rosterStripped
                + " afk_ai_stripped=" + stripped
                + " repo_ships_end=" + repoEnd
                + afkSummary);
        System.exit(pass ? 0 : 1);
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

    private static float mag(float[] v) {
        return (float) Math.hypot(v[0], v[1]);
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }
}
