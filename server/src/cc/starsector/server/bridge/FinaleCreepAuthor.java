package cc.starsector.server.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The derelict FINALE creep author — makes the
 * scheduled Survey Mothership ROOT creep toward the side-0 fortress root at
 * {@code wave.finaleCreepSu} su/s, and nothing else.
 *
 * <p><b>Seam:</b> the engine step advances
 * each entity as ship-advance (controller) → {@code loc += vel*dt} → facing →
 * {@code updateStationModuleLocations} re-pins the STATION_MODULE children → AI.
 * So a velocity authored on the ROOT before the engine step moves the whole cluster
 * coherently; weapons, module AIs, targeting and collision stay vanilla (the author never
 * touches commands or the AI). It ALSO owns the root's facing while armed —
 * slewed toward the fortress bearing ({@link #FACING_SLEW_DEG_PER_S}), angular velocity
 * zeroed pre-step AND post-step ({@link #postStep}: the hullmod re-arms ω inside the
 * step and the wire samples after it) — replacing the hull's builtin axial spin for the
 * finale boss ONLY, never a regular mothership in other scenarios.
 *
 *
 * <p><b>Max-speed lift (WHY):</b> the engine controller clamps any over-max velocity back to
 * its effective max speed every thrust/decelerate branch (a velocity at or over the max loses
 * {@code min(2*accel*dt, len - max)} of its length), and a ship
 * with no movement command and {@code |vel| > getMaxSpeed()} self-issues a DECELERATE;
 * the clamp's max derives from the ship's
 * {@code MutableShipStatsAPI.getMaxSpeed().getModifiedValue()}. The
 * vanilla station drive tops out ≈33 su/s, so a 40 su/s creep would be
 * shaved every tick. At root resolve the author lifts the stat by {@code modifyFlat} to
 * {@code creepSu + 1} (the +1 pad keeps float rounding from landing a hair over the clamp)
 * when the base is lower, and {@code unmodify}s on disarm — through the PUBLIC API
 * interfaces ({@code OrbitDroneDrive} precedent: an impl-class Method can be access-refused).
 *
 * <p><b>Engine burn-in first:</b> the finale spawns through the normal 3-arg edge wave spawn
 * (travel-drive burn-in, zero teleport). While
 * {@code isTravelDriveOn()} the author leaves the velocity to the engine; creeping starts the
 * tick the burn ends. A freshly spawned hull sits in the engine's pending-add list until the
 * next step, so the ROOT is resolved lazily by stable id on the first ticks after arm (budget
 * {@link #ROOT_RESOLVE_TICK_BUDGET}); the TARGET is resolved AT arm (it is already in play).
 *
 * <p><b>Arrival:</b> inside {@code rootRadius + targetRadius + }{@link #ARRIVE_PAD_SU} the root
 * HOLDS (velocity zeroed each tick — beside the fortress, never into it); a later separation
 * (a collision shove) resumes the creep.
 *
 * <p><b>Fail-soft by law:</b> every failure — root/target dead or hulked, root never found,
 * any Throwable — DISARMS loudly once and restores the stat; nothing propagates to the sim
 * thread; the finale then drifts vanilla. Per-tick work is the cached {@link Method} handles
 * only (frame-loop residency law); the reflective resolves happen once at arm/resolve.
 * Sim-thread only (arm from the wave drain, tick from the step decorator, disarm at the
 * round boundary — all on the serve loop's thread; no locking by design).
 */
public final class FinaleCreepAuthor {
    static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    static final String STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    static final String STAT_CLASS = "com.fs.starfarer.api.combat.MutableStat";
    /** The max-speed stat modifier source key (greppable in any engine stat dump). */
    static final String MAX_SPEED_MOD_KEY = "cc_finale_creep";
    /** Hold distance beyond the two collision radii (su). */
    static final float ARRIVE_PAD_SU = 300.0f;
    /** The lift pad over the authored speed (su/s) — float-rounding insurance vs the clamp. */
    static final float MAX_SPEED_LIFT_PAD_SU = 1.0f;
    /**
     * Ticks the author waits for the freshly-spawned root (and each escort) to leave the
     * pending-add list. The resolve is a full {@code getShips()} scan per pre-step
     * tick, so the budget is SHORT — the probe resolved on the arm tick itself
     * ({@code wait_ticks=0}; the drain arms before the step that publishes the pending list),
     * 120 ticks = 2 s of slack, then a loud disarm (was 1800).
     */
    static final int ROOT_RESOLVE_TICK_BUDGET = 120;
    /**
     * The authored facing slew rate: the boss points where it is going, never rotating as it flies
     * forward; SLEW, never snap; keep facing the station during HOLD.
     * The boss's heading IS the bearing
     * to the fortress, so creep and hold share one desired facing; the hull's builtin
     * {@code axialrotation} spin is overridden every pre-step tick (angular velocity zeroed +
     * the slewed facing written) — finale-only by construction (the author arms on the
     * finale row alone; {@code /spawnfoe} motherships keep the vanilla spin).
     */
    static final float FACING_SLEW_DEG_PER_S = 6.0f;
    /**
     * Escort re-give cadence: the stock
     * side-1 admiral ({@code BaseBattleStrategy}) calls {@code clearTasks()} ONCE when it
     * first sets up station defence — the finale boss IS the biggest side-1 station, so that
     * pass can land after our give and prune the escort order (the next reassign drops any
     * direct order whose task is gone). Cheap cure: every this-many ticks read back each
     * escort's assignment type and re-give when it is no longer LIGHT_ESCORT — a few
     * reflective reads per cadence, zero per-tick work. 600 → 120 (escorts were seen to wander —
     * the vanilla escort AI's leash plus
     * up to 10 s between re-gives; 2 s bounds the wander, cost = 4 escorts × 2 reads / 2 s).
     */
    static final int ESCORT_REASSERT_TICKS = 120;

    private static final class Arm {
        final String rootStableId;
        final float creepSu;
        final Object engine;
        final Object target;
        final float targetRadius;
        final Class<?> shipApi;
        final Method engineGetShips;
        final Method getId;
        final Method getLocation;
        final Method getVelocity;
        final Method isAlive;
        final Method isHulk;
        final Method getCollisionRadius;
        final Method getMutableStats;
        final Method statsGetMaxSpeed;
        final Method statModifiedValue;
        final Method statModifyFlat;
        final Method statUnmodify;
        final Method getFacing;
        final Method setFacing;
        final Method setAngularVelocity;
        final Method vecSet;
        final Field vecX;
        final Field vecY;
        Object root;
        /** The facing the author last wrote; NaN until the first creeping tick. */
        float authoredFacing = Float.NaN;
        /** Escort stable ids still waiting for their hull to leave pending-add. */
        final List<String> pendingEscorts = new ArrayList<>();
        /** Escorts that hold (or held) the order — re-asserted on a slow cadence. */
        final List<Object> orderedEscorts = new ArrayList<>();
        int escortResolveTicks;
        int escortsOrdered;
        int escortReasserts;
        int ticksSinceEscortCheck;
        // Resolved on the ROOT's impl class at root resolve (a live probe showed it is NOT on
        // ShipAPI — RealEngineDemoRoster.waveProbeSampleLine reads it off the Ship class too);
        // null = unresolvable = treat as "no burn-in" (fail-soft, the author still creeps).
        Method isTravelDriveOn;
        Object maxSpeedStat;
        float maxSpeedBase;
        boolean lifted;
        float arriveDist;
        int resolveTicks;
        boolean creeping;
        boolean arrivedLogged;

        Arm(String rootStableId, float creepSu, Object engine, Object target, float targetRadius,
                Class<?> shipApi, Method engineGetShips, Method getId, Method getLocation,
                Method getVelocity, Method isAlive, Method isHulk,
                Method getCollisionRadius, Method getMutableStats, Method statsGetMaxSpeed,
                Method statModifiedValue, Method statModifyFlat, Method statUnmodify,
                Method getFacing, Method setFacing, Method setAngularVelocity,
                Method vecSet, Field vecX, Field vecY) {
            this.rootStableId = rootStableId;
            this.creepSu = creepSu;
            this.engine = engine;
            this.target = target;
            this.targetRadius = targetRadius;
            this.shipApi = shipApi;
            this.engineGetShips = engineGetShips;
            this.getId = getId;
            this.getLocation = getLocation;
            this.getVelocity = getVelocity;
            this.isAlive = isAlive;
            this.isHulk = isHulk;
            this.getCollisionRadius = getCollisionRadius;
            this.getMutableStats = getMutableStats;
            this.statsGetMaxSpeed = statsGetMaxSpeed;
            this.statModifiedValue = statModifiedValue;
            this.statModifyFlat = statModifyFlat;
            this.statUnmodify = statUnmodify;
            this.getFacing = getFacing;
            this.setFacing = setFacing;
            this.setAngularVelocity = setAngularVelocity;
            this.vecSet = vecSet;
            this.vecX = vecX;
            this.vecY = vecY;
        }
    }

    private Arm arm;

    /**
     * Arm for the finale ROOT {@code rootStableId} at {@code creepSu} su/s. Resolves the
     * engine + API handles and the TARGET (the side-0 {@code isStation()} ship with no
     * {@code getStationSlot()} — the midline fortress root; alive, not a hulk). Returns the
     * target's pose text {@code "x,y"} when armed, null when it stayed DISARMED (no target,
     * no engine, or any resolve failure — logged once, vanilla drift). Never throws.
     */
    public String arm(String rootStableId, float creepSu) {
        disarm("re_arm");
        try {
            ClassLoader loader = FinaleCreepAuthor.class.getClassLoader();
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
            Class<?> shipApi = Class.forName(SHIP_API, false, loader);
            Class<?> statsApi = Class.forName(STATS_API, false, loader);
            Class<?> statClass = Class.forName(STAT_CLASS, false, loader);
            Object engine = engineClass.getMethod("getInstance").invoke(null);
            if (engine == null) {
                System.out.println("npeer-serve finale_creep arm_skip stable=" + rootStableId
                        + " reason=no_engine_instance (vanilla drift)");
                return null;
            }
            Method engineGetShips = engineClass.getMethod("getShips");
            Method getId = shipApi.getMethod("getId");
            Method getOwner = shipApi.getMethod("getOwner");
            Method isStation = shipApi.getMethod("isStation");
            Method getStationSlot = shipApi.getMethod("getStationSlot");
            Method getLocation = shipApi.getMethod("getLocation");
            Method getVelocity = shipApi.getMethod("getVelocity");
            Method isAlive = shipApi.getMethod("isAlive");
            Method isHulk = shipApi.getMethod("isHulk");
            Method getCollisionRadius = shipApi.getMethod("getCollisionRadius");
            Method getMutableStats = shipApi.getMethod("getMutableStats");
            Method statsGetMaxSpeed = statsApi.getMethod("getMaxSpeed");
            Method statModifiedValue = statClass.getMethod("getModifiedValue");
            Method statModifyFlat = statClass.getMethod("modifyFlat", String.class, float.class);
            Method statUnmodify = statClass.getMethod("unmodify", String.class);
            // CombatEntityAPI members (inherited by ShipAPI — getMethod sees them).
            Method getFacing = shipApi.getMethod("getFacing");
            Method setFacing = shipApi.getMethod("setFacing", float.class);
            Method setAngularVelocity = shipApi.getMethod("setAngularVelocity", float.class);
            Object target = null;
            for (Object candidate : (List<?>) engineGetShips.invoke(engine)) {
                if (!shipApi.isInstance(candidate)) {
                    continue;
                }
                if (((Number) getOwner.invoke(candidate)).intValue() != 0
                        || !Boolean.TRUE.equals(isStation.invoke(candidate))
                        || getStationSlot.invoke(candidate) != null
                        || !Boolean.TRUE.equals(isAlive.invoke(candidate))
                        || Boolean.TRUE.equals(isHulk.invoke(candidate))) {
                    continue;
                }
                target = candidate;
                break;
            }
            if (target == null) {
                return null; // the serve loop logs target=none
            }
            Object targetLoc = getLocation.invoke(target);
            Class<?> vecClass = targetLoc.getClass();
            Method vecSet = vecClass.getMethod("set", float.class, float.class);
            Field vecX = vecClass.getField("x");
            Field vecY = vecClass.getField("y");
            float targetRadius = ((Number) getCollisionRadius.invoke(target)).floatValue();
            Arm fresh = new Arm(rootStableId, creepSu, engine, target, targetRadius, shipApi,
                    engineGetShips, getId, getLocation, getVelocity, isAlive, isHulk,
                    getCollisionRadius, getMutableStats, statsGetMaxSpeed,
                    statModifiedValue, statModifyFlat, statUnmodify, getFacing, setFacing,
                    setAngularVelocity, vecSet, vecX, vecY);
            // The ROOT is resolved by tick() (same tick, pre-step) — ONE init path for the
            // arrive distance, the burn-in read and the max-speed lift, whether the hull is
            // already in getShips() (the live probe showed it was) or still in the pending-add list.
            this.arm = fresh;
            return vecText(fresh, targetLoc);
        } catch (Throwable t) {
            this.arm = null;
            System.out.println("npeer-serve finale_creep arm_error stable=" + rootStableId
                    + " err=" + t.getClass().getName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage()) + " (vanilla drift)");
            return null;
        }
    }

    /**
     * One PRE-STEP tick: resolve the root if still pending, then author its velocity toward
     * the target (or hold when arrived). A no-op while disarmed; never throws (any Throwable
     * disarms loudly once). {@code dtSeconds} is accepted for the hook contract only — the
     * author writes a VELOCITY and the engine integrates it with its own step.
     */
    public void tick(float dtSeconds) {
        Arm a = arm;
        if (a == null) {
            return;
        }
        try {
            if (a.root == null) {
                a.root = findRoot(a);
                if (a.root == null) {
                    if (++a.resolveTicks > ROOT_RESOLVE_TICK_BUDGET) {
                        disarm("root_not_found_after_" + a.resolveTicks + "_ticks");
                    }
                    return;
                }
                float rootRadius = ((Number) a.getCollisionRadius.invoke(a.root)).floatValue();
                a.arriveDist = rootRadius + a.targetRadius + ARRIVE_PAD_SU;
                try {
                    a.isTravelDriveOn = a.root.getClass().getMethod("isTravelDriveOn");
                } catch (NoSuchMethodException noBurnInRead) {
                    a.isTravelDriveOn = null;
                }
                liftMaxSpeed(a);
                System.out.println("npeer-serve finale_creep root_resolved stable="
                        + a.rootStableId + " wait_ticks=" + a.resolveTicks
                        + " max_speed_base=" + a.maxSpeedBase + " lifted=" + a.lifted
                        + " arrive_dist=" + a.arriveDist);
            }
            if (!Boolean.TRUE.equals(a.isAlive.invoke(a.root))
                    || Boolean.TRUE.equals(a.isHulk.invoke(a.root))) {
                disarm("root_dead");
                return;
            }
            orderResolvedEscorts(a); // one-shot per escort, no-op once the queue drains
            reassertEscortOrders(a); // A counter tick, real work every ESCORT_REASSERT_TICKS ticks
            if (!Boolean.TRUE.equals(a.isAlive.invoke(a.target))
                    || Boolean.TRUE.equals(a.isHulk.invoke(a.target))) {
                disarm("target_dead");
                return;
            }
            if (a.isTravelDriveOn != null
                    && Boolean.TRUE.equals(a.isTravelDriveOn.invoke(a.root))) {
                return; // engine burn-in owns the velocity (zero-teleport law)
            }
            Object rootLoc = a.getLocation.invoke(a.root);
            Object targetLoc = a.getLocation.invoke(a.target);
            float dx = a.vecX.getFloat(targetLoc) - a.vecX.getFloat(rootLoc);
            float dy = a.vecY.getFloat(targetLoc) - a.vecY.getFloat(rootLoc);
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            Object vel = a.getVelocity.invoke(a.root);
            // Creep AND hold share one desired facing — the bearing to the fortress
            // (heading while creeping, "keep facing the station" while holding). Slewed, never
            // snapped; the engine integrates facing += ω·dt AFTER this pre-step write, so ω is
            // zeroed too (the builtin axial spin's residual, if its hullmod re-arms ω before
            // the integrate, is one ω·dt step that the next tick's write overrides again).
            slewFacing(a, (float) Math.toDegrees(Math.atan2(dy, dx)), dtSeconds);
            if (dist <= a.arriveDist) {
                a.vecSet.invoke(vel, 0.0f, 0.0f);
                if (!a.arrivedLogged) {
                    a.arrivedLogged = true;
                    System.out.println("npeer-serve finale_creep arrived stable="
                            + a.rootStableId + " at=" + vecText(a, rootLoc)
                            + " dist=" + dist + " hold=" + a.arriveDist
                            + " (holding beside the fortress)");
                }
                return;
            }
            if (!a.creeping) {
                a.creeping = true;
                System.out.println("npeer-serve finale_creep creeping stable=" + a.rootStableId
                        + " from=" + vecText(a, rootLoc) + " target=" + vecText(a, targetLoc)
                        + " dist=" + dist + " creep_su=" + a.creepSu);
            }
            float k = a.creepSu / dist;
            a.vecSet.invoke(vel, dx * k, dy * k);
        } catch (Throwable t) {
            disarm("throw " + t.getClass().getName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    /**
     * The POST-STEP half of the facing law. The engine step
     * integrates {@code facing += ω·dt} and THEN runs
     * {@code ship.advance}, where the hull's axial-spin hullmod re-arms ω every tick; the foe
     * pose the wire carries is sampled after the step. So the pre-step zero in
     * {@link #tick} protected the SERVER facing but the replica still received ω≈2°/s and
     * spun between facing glides. Zeroing ω again right after the step
     * makes the sampled ω 0, and the client's per-frame ω write then holds the replica still.
     * Gated exactly like the pre-step facing write: armed, root resolved and a facing already
     * authored (i.e. past the engine burn-in, which keeps its vanilla spin). Never throws.
     */
    public void postStep() {
        Arm a = arm;
        if (a == null || a.root == null || Float.isNaN(a.authoredFacing)) {
            return;
        }
        try {
            a.setAngularVelocity.invoke(a.root, 0.0f);
        } catch (Throwable t) {
            disarm("post_step_throw " + t.getClass().getName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    /**
     * Queue a finale ESCORT by stable id. Resolved lazily by {@link #tick} (the hull
     * sits in the engine's pending-add list until the next step, like the root) and then
     * given ONE task-manager order — {@code LIGHT_ESCORT} on the root's deployed member —
     * through the public API interfaces ({@code CombatEngineAPI.getFleetManager(side)} →
     * {@code CombatFleetManagerAPI.getDeployedFleetMember(ShipAPI)} /
     * {@code getTaskManager(false)} → {@code CombatTaskManagerAPI.createAssignment(LIGHT_ESCORT,
     * rootMember, false)} + {@code giveAssignment(escortMember, info, false)}; precedent for
     * the manager chain = {@code SeatRebind.clearRetreatOrderAndState}). One-shot: zero per-tick
     * cost after the give; the engine's escort AI then keeps the drone with the boss. A no-op
     * while disarmed (an escort with no armed finale flies vanilla — the (c2c) adhoc lane never
     * reaches here anyway). Never throws.
     */
    public void enlistEscort(String escortStableId) {
        Arm a = arm;
        if (a == null || escortStableId == null || escortStableId.isEmpty()) {
            return;
        }
        a.pendingEscorts.add(escortStableId);
    }

    /**
     * Resolve every pending escort that has left pending-add and give it the
     * LIGHT_ESCORT order on the (already resolved) root. A resolve failure for ONE escort logs
     * and drops that escort (vanilla drift for it alone — never a disarm of the creep); the
     * whole queue is dropped loudly once the resolve budget runs out.
     */
    private static void orderResolvedEscorts(Arm a) throws Exception {
        if (a.pendingEscorts.isEmpty()) {
            return;
        }
        List<?> ships = (List<?>) a.engineGetShips.invoke(a.engine);
        for (Iterator<String> it = a.pendingEscorts.iterator(); it.hasNext();) {
            String stableId = it.next();
            Object escort = null;
            for (Object candidate : ships) {
                if (a.shipApi.isInstance(candidate)
                        && stableId.equals(a.getId.invoke(candidate))) {
                    escort = candidate;
                    break;
                }
            }
            if (escort == null) {
                continue;
            }
            it.remove();
            try {
                String path = giveLightEscortOrder(a, escort);
                a.escortsOrdered++;
                a.orderedEscorts.add(escort);
                System.out.println("npeer-serve finale_escort ordered stable=" + stableId
                        + " on_root=" + a.rootStableId + " path=" + path
                        + " ordered=" + a.escortsOrdered);
            } catch (Throwable t) {
                System.out.println("npeer-serve finale_escort order_fail stable=" + stableId
                        + " err=" + t.getClass().getName() + ": "
                        + (t.getMessage() == null ? "" : t.getMessage())
                        + " (this escort flies vanilla)");
            }
        }
        if (!a.pendingEscorts.isEmpty()
                && ++a.escortResolveTicks > ROOT_RESOLVE_TICK_BUDGET) {
            System.out.println("npeer-serve finale_escort resolve_budget stable="
                    + a.pendingEscorts + " ticks=" + a.escortResolveTicks
                    + " (never left pending-add; dropped, vanilla drift)");
            a.pendingEscorts.clear();
        }
    }

    /**
     * {@link #ESCORT_REASSERT_TICKS} cadence: for every ordered escort still alive, read back
     * {@code getAssignmentFor(escort).getType()} and re-give the order when it is no longer
     * LIGHT_ESCORT (the admiral's one-time {@code clearTasks()} pass). Dead/hulked escorts
     * leave the list. A read-back failure logs and drops that escort from the list.
     */
    private static void reassertEscortOrders(Arm a) throws Exception {
        if (a.orderedEscorts.isEmpty() || ++a.ticksSinceEscortCheck < ESCORT_REASSERT_TICKS) {
            return;
        }
        a.ticksSinceEscortCheck = 0;
        for (Iterator<Object> it = a.orderedEscorts.iterator(); it.hasNext();) {
            Object escort = it.next();
            try {
                if (!Boolean.TRUE.equals(a.isAlive.invoke(escort))
                        || Boolean.TRUE.equals(a.isHulk.invoke(escort))) {
                    it.remove();
                    continue;
                }
                if (!"LIGHT_ESCORT".equals(currentAssignmentType(a, escort))) {
                    String path = giveLightEscortOrder(a, escort);
                    a.escortReasserts++;
                    System.out.println("npeer-serve finale_escort reasserted stable="
                            + a.getId.invoke(escort) + " on_root=" + a.rootStableId
                            + " path=" + path + " reasserts=" + a.escortReasserts);
                }
            } catch (Throwable t) {
                it.remove();
                System.out.println("npeer-serve finale_escort reassert_fail err="
                        + t.getClass().getName() + ": "
                        + (t.getMessage() == null ? "" : t.getMessage())
                        + " (this escort flies vanilla from here)");
            }
        }
    }

    /** {@code CombatTaskManagerAPI.getAssignmentFor(ShipAPI).getType().name()} or "none". */
    private static String currentAssignmentType(Arm a, Object escort) throws Exception {
        ClassLoader loader = FinaleCreepAuthor.class.getClassLoader();
        Class<?> engineApi = Class.forName("com.fs.starfarer.api.combat.CombatEngineAPI", false,
                loader);
        Class<?> fleetManagerApi = Class.forName(
                "com.fs.starfarer.api.combat.CombatFleetManagerAPI", false, loader);
        Class<?> taskManagerApi = Class.forName(
                "com.fs.starfarer.api.combat.CombatTaskManagerAPI", false, loader);
        Class<?> assignmentInfo = Class.forName(
                "com.fs.starfarer.api.combat.CombatFleetManagerAPI$AssignmentInfo", false,
                loader);
        int side = ((Number) a.shipApi.getMethod("getOwner").invoke(escort)).intValue();
        Object manager = engineApi.getMethod("getFleetManager", int.class).invoke(a.engine, side);
        if (manager == null) {
            return "none";
        }
        Object taskManager = fleetManagerApi.getMethod("getTaskManager", boolean.class)
                .invoke(manager, false);
        Object info = taskManagerApi.getMethod("getAssignmentFor", a.shipApi)
                .invoke(taskManager, escort);
        if (info == null) {
            return "none";
        }
        Object type = assignmentInfo.getMethod("getType").invoke(info);
        return type == null ? "none" : String.valueOf(type);
    }

    /** The one-shot LIGHT_ESCORT give (javadoc on {@link #enlistEscort}); returns a path token. */
    private static String giveLightEscortOrder(Arm a, Object escort) throws Exception {
        ClassLoader loader = FinaleCreepAuthor.class.getClassLoader();
        Class<?> engineApi = Class.forName("com.fs.starfarer.api.combat.CombatEngineAPI", false,
                loader);
        Class<?> fleetManagerApi = Class.forName(
                "com.fs.starfarer.api.combat.CombatFleetManagerAPI", false, loader);
        Class<?> taskManagerApi = Class.forName(
                "com.fs.starfarer.api.combat.CombatTaskManagerAPI", false, loader);
        Class<?> memberApi = Class.forName(
                "com.fs.starfarer.api.combat.DeployedFleetMemberAPI", false, loader);
        Class<?> targetApi = Class.forName(
                "com.fs.starfarer.api.combat.AssignmentTargetAPI", false, loader);
        Class<?> assignmentType = Class.forName(
                "com.fs.starfarer.api.combat.CombatAssignmentType", false, loader);
        Class<?> assignmentInfo = Class.forName(
                "com.fs.starfarer.api.combat.CombatFleetManagerAPI$AssignmentInfo", false,
                loader);
        Method getOwner = a.shipApi.getMethod("getOwner");
        int side = ((Number) getOwner.invoke(escort)).intValue();
        Object manager = engineApi.getMethod("getFleetManager", int.class).invoke(a.engine, side);
        if (manager == null) {
            throw new IllegalStateException("no fleet manager for side " + side);
        }
        Method getMember = fleetManagerApi.getMethod("getDeployedFleetMember", a.shipApi);
        Object rootMember = getMember.invoke(manager, a.root);
        Object escortMember = getMember.invoke(manager, escort);
        if (rootMember == null || escortMember == null) {
            throw new IllegalStateException("deployed member missing: root="
                    + (rootMember != null) + " escort=" + (escortMember != null));
        }
        Object taskManager = fleetManagerApi.getMethod("getTaskManager", boolean.class)
                .invoke(manager, false);
        Object lightEscort = assignmentType.getMethod("valueOf", String.class)
                .invoke(null, "LIGHT_ESCORT");
        // createAssignment returns null unless the target is a deployed member — guard.
        Object info = taskManagerApi.getMethod("createAssignment", assignmentType, targetApi,
                boolean.class).invoke(taskManager, lightEscort, rootMember, false);
        if (info == null) {
            throw new IllegalStateException("createAssignment(LIGHT_ESCORT, rootMember)"
                    + " returned null (target not a task-manager target)");
        }
        taskManagerApi.getMethod("giveAssignment", memberApi, assignmentInfo, boolean.class)
                .invoke(taskManager, escortMember, info, false);
        return "side" + side + ":light_escort";
    }

    /** The round boundary's reset (and the hook contract's disarm): restores the stat. */
    public void disarm() {
        disarm("round_boundary");
    }

    private void disarm(String reason) {
        Arm a = arm;
        if (a == null) {
            return;
        }
        arm = null;
        try {
            if (a.lifted && a.maxSpeedStat != null) {
                a.statUnmodify.invoke(a.maxSpeedStat, MAX_SPEED_MOD_KEY);
            }
        } catch (Throwable ignore) {
            // a dead/removed root's stat is irrelevant; the disarm itself must not throw
        }
        System.out.println("npeer-serve finale_creep disarmed stable=" + a.rootStableId
                + " reason=" + reason + " lifted=" + a.lifted
                + (a.creeping ? " (was creeping)" : " (never creeped)"));
    }

    /** The root by stable id in the engine's live ship list, or null while still pending. */
    private static Object findRoot(Arm a) throws Exception {
        for (Object candidate : (List<?>) a.engineGetShips.invoke(a.engine)) {
            if (a.shipApi.isInstance(candidate)
                    && a.rootStableId.equals(a.getId.invoke(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Slew the root's facing toward {@code desiredDeg} by at most
     * {@link #FACING_SLEW_DEG_PER_S}·dt this tick, write it, zero the angular velocity. The
     * author tracks its OWN last-written facing (not {@code getFacing()}), so the engine's
     * post-write ω·dt residual can never accumulate into a drift; the first creeping tick
     * seeds it from the hull's live facing (the burn-in's end pose — no snap).
     */
    private static void slewFacing(Arm a, float desiredDeg, float dtSeconds) throws Exception {
        if (Float.isNaN(a.authoredFacing)) {
            a.authoredFacing = ((Number) a.getFacing.invoke(a.root)).floatValue();
        }
        float delta = desiredDeg - a.authoredFacing;
        delta -= 360.0f * (float) Math.floor((delta + 180.0f) / 360.0f); // → [-180, 180)
        float maxStep = FACING_SLEW_DEG_PER_S * Math.max(dtSeconds, 0.0f);
        float step = Math.max(-maxStep, Math.min(maxStep, delta));
        float facing = a.authoredFacing + step;
        facing -= 360.0f * (float) Math.floor(facing / 360.0f); // → [0, 360)
        a.authoredFacing = facing;
        a.setFacing.invoke(a.root, facing);
        a.setAngularVelocity.invoke(a.root, 0.0f);
    }

    /**
     * Max-speed lift (the class javadoc's WHY): raise the stat to {@code creepSu + pad} when lower.
     * The lift targets the MODIFIED value — a flat add lands under any percent/mult
     * modifier on the hull, so the write is re-read and topped up (the same source key
     * REPLACES its flat term) until the modified value reaches the target, a few passes at
     * most (each pass closes the remaining gap by the hull's multiplier; a hull whose
     * multiplier is 0 can never be lifted and is logged as such — the creep then rides the
     * clamp, vanilla-shaved).
     */
    private static void liftMaxSpeed(Arm a) throws Exception {
        Object stats = a.getMutableStats.invoke(a.root);
        a.maxSpeedStat = a.statsGetMaxSpeed.invoke(stats);
        a.maxSpeedBase = ((Number) a.statModifiedValue.invoke(a.maxSpeedStat)).floatValue();
        float wanted = a.creepSu + MAX_SPEED_LIFT_PAD_SU;
        if (wanted <= a.maxSpeedBase) {
            return;
        }
        float flat = 0.0f;
        float modified = a.maxSpeedBase;
        for (int pass = 0; pass < 4 && modified < wanted - 0.01f; pass++) {
            flat += wanted - modified;
            a.statModifyFlat.invoke(a.maxSpeedStat, MAX_SPEED_MOD_KEY, flat);
            a.lifted = true;
            modified = ((Number) a.statModifiedValue.invoke(a.maxSpeedStat)).floatValue();
        }
        if (modified < wanted - 0.01f) {
            System.out.println("npeer-serve finale_creep lift_short stable=" + a.rootStableId
                    + " wanted=" + wanted + " modified=" + modified + " flat=" + flat
                    + " (a percent/mult modifier on the hull defeats the flat lift;"
                    + " the creep rides the clamp)");
        }
    }

    private static String vecText(Arm a, Object vec) throws Exception {
        return a.vecX.getFloat(vec) + "," + a.vecY.getFloat(vec);
    }
}
