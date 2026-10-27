package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatEntityView;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.protocol.NonShipEntitySnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * D5 star-fortress feasibility PROBE (the "headless station-base spawn
 * probe FIRST" step): spawn the Low-tech Star Fortress ({@code station3_Standard}) into a live
 * headless engine through the engine's OWN mid-combat deploy lane —
 * {@code CombatFleetManager.spawnFleetMember -> deploy -> new()} — whose station branch
 * pins the fixed location, auto-creates the child module ships from
 * {@code variant.getStationModules()}, and flushes them via
 * {@code executeAdds}. Station bases are the one hull class the NB.5 census never attempted
 * ({@code skip_station_module}) — this probe closes that gap on the real
 * server build path and characterizes the module lattice over a tick window.
 *
 * <p><b>Spawn-shape deltas vs {@code RealEngineMidDeployProbeMain}</b> (otherwise the same
 * recipe): owner side 0 (Bastion's own base), NON-null location (explicit spawn point overrides
 * edge placement; the station branch then pins exactly there), burn 0 (stations
 * skip the whole travel-drive block). The member CR is lifted BEFORE the spawn call:
 * module auto-create copies the PARENT MEMBER's CR at build time, so a
 * post-spawn ship-level lift would leave 24 modules in the CR&lt;0.4 malfunction band.
 *
 * <p><b>Known engine hazards this probe respects</b> (static RE):
 * the owner-0 flagship deploy path derefs {@code getCombatUI()} UNGUARDED — a
 * synthetic member is never flagship, asserted before spawn; {@code FleetMemberStatus} is
 * lazy-created (never null) and its per-slot defaults are benign, so the
 * null-status fear is already defused statically — the live question is whether
 * the whole lattice builds and stays sane, which this probe measures. Deploy itself runs 10
 * nested 0.2 s warm-up advances inside the spawn call; harmless here.
 *
 * <p><b>Optional mode — mine NULL-source walk + F1 dedupe verify</b> ({@code --mine-walk 1},
 * default OFF, drifts d1 + d3). Spawns ONE mine into this probe's own
 * engine through the vanilla spawner's exact API shape and reads it back through BOTH
 * {@code EngineCombatBinding.missileEntitiesOrNull()} and {@code projectileEntitiesOrNull()}:
 * d1 asks whether the wire tier's {@code getSource()}/{@code getWeapon()} reads survive a
 * source-less, weapon-less mine (they must degrade per missile, never nullify the record), and
 * d3 asks whether the ECB projectile pass's identity-skip really makes the double-harvested mine
 * cross exactly ONCE (missile tier only, stable id ≥ {@code MISSILE_NETWORK_ID_BASE}). Every
 * answer is a verdict row; the mode adds nothing to the station verdict itself, which is fully
 * measured BEFORE the mine is spawned.
 *
 * <p>Exit codes: 0 = verdict STATION_HEADLESS_PROVEN (spawn + 24-module census + pin + window
 * clean, + the mine walk when enabled); 2 = a spawn call threw (the exact census-gap boundary
 * this probe exists to capture — stack printed; {@code SPAWN_THREW} for the station,
 * {@code MINE_SPAWN_THREW} for the mine walk's {@code spawnProjectile} boundary); 1 = verdict
 * FAILED / infrastructure.
 *
 */
public final class RealEngineStationProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineDemoRoster (Option R). ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String MISSILE_CLASS = "com.fs.starfarer.combat.entities.Missile";
    private static final String HULL_VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String VARIANT_REGISTRY_CLASS = "com.fs.starfarer.loading.B";
    private static final String VARIANT_REGISTRY_METHOD = "o00000";
    private static final String FLEET_MEMBER_CLASS = "com.fs.starfarer.campaign.fleet.FleetMember";
    private static final String FLEET_MEMBER_TYPE_API = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String COMBAT_ENGINE_API = "com.fs.starfarer.api.combat.CombatEngineAPI";
    private static final String WEAPON_API = "com.fs.starfarer.api.combat.WeaponAPI";
    private static final String MISSILE_API = "com.fs.starfarer.api.combat.MissileAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "station-probe";

    /**
     * Mine-walk view matching tolerance (su): a wire view is "the mine" when its position sits
     * within this radius of the live mine's {@code getLocation()} read in the SAME frame — the
     * view tier copies that very vector, so the honest match is exact and this is slack only.
     * The mine is spawned far off the station on +X, so no natural minefield mine can collide
     * with the match.
     */
    private static final float MINE_MATCH_EPS_SU = 0.5f;

    /** station3_Standard modules map (data/variants/stations/, 24 live entries; WS000-005 decor commented out). */
    private static Map<String, Integer> expectedModuleHulls() {
        Map<String, Integer> m = new TreeMap<String, Integer>();
        m.put("module_armour2", 12);
        m.put("module_armour3", 3);
        m.put("module_armour4", 3);
        m.put("module_large1_lowtech", 1);
        m.put("module_large2_lowtech", 2);
        m.put("module_bastion1_lowtech", 2);
        m.put("module_citadel1_lowtech", 1);
        return m;
    }

    private RealEngineStationProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int settleFrames = 120;    // 2 s: live engine settled before the spawn
        int windowFrames = 900;    // 15 s: lattice stability + rotation/drone observation
        float fixedDt = SimTickRate.fixedDt();
        String variantId = System.getProperty("cc.server.stationVariant", "station3_Standard");
        int side = 0;
        // Mine walk d1/d3 — OFF unless asked for: absent flags leave the
        // probe shape byte-identical. minelayer1 = the weapon the station3 base's builtInMod
        // stealth_minefield_lt actually fires (StealthMinefieldLT.getWeaponId(), api impl
        // hullmods; an earlier finding named the hullmod, this names its weapon).
        boolean mineWalk = false;
        String mineWeaponId = "minelayer1";
        float mineOffsetSu = 900.0f;
        // Fuse probe — OFF unless asked for (absent flags keep the probe shape
        // byte-identical). One bait enemy + one serve-shape mine + per-frame fuse/grid metering.
        boolean fuseProbe = false;
        int fuseWindowFrames = 1080;
        float fuseOffsetSu = 2000.0f;
        float fuseGapSu = 120.0f;
        String fuseEnemyVariant = "mudskipper2_cc_Hellbore";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--settle-frames": settleFrames = Integer.parseInt(args[++i]); break;
                case "--window-frames": windowFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--side": side = Integer.parseInt(args[++i]); break;
                case "--mine-walk": mineWalk = Integer.parseInt(args[++i]) != 0; break;
                case "--mine-weapon": mineWeaponId = args[++i]; break;
                case "--mine-offset-su": mineOffsetSu = Float.parseFloat(args[++i]); break;
                case "--fuse-probe": fuseProbe = Integer.parseInt(args[++i]) != 0; break;
                case "--fuse-window-frames": fuseWindowFrames = Integer.parseInt(args[++i]); break;
                case "--fuse-offset-su": fuseOffsetSu = Float.parseFloat(args[++i]); break;
                case "--fuse-gap-su": fuseGapSu = Float.parseFloat(args[++i]); break;
                case "--fuse-enemy-variant": fuseEnemyVariant = args[++i]; break;
                default: break;
            }
        }
        System.out.println(P + "-begin settle_frames=" + settleFrames + " window_frames=" + windowFrames
                + " fixed_dt=" + fixedDt + " variant=" + variantId + " side=" + side
                + " mine_walk=" + mineWalk
                + (mineWalk ? " mine_weapon=" + mineWeaponId + " mine_offset_su=" + f1(mineOffsetSu) : "")
                + " fuse_probe=" + fuseProbe
                + (fuseProbe ? " fuse_window=" + fuseWindowFrames + " fuse_offset_su=" + f1(fuseOffsetSu)
                        + " fuse_gap_su=" + f1(fuseGapSu) + " fuse_enemy=" + fuseEnemyVariant : ""));

        // ---- Boot the SAME product lane the serve loop uses (L3 substrate + content + t=0 roster). ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEngineStationProbeMain.class.getClassLoader();
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
        Method shipGetLocation = shipClass.getMethod("getLocation");
        Method shipGetVelocity = shipClass.getMethod("getVelocity");
        Method shipGetFacing = shipClass.getMethod("getFacing");
        Method shipGetShipAI = shipClass.getMethod("getShipAI");
        Method shipSetShipAI = findOneArg(shipClass, "setShipAI");
        Method shipIsStation = shipClass.getMethod("isStation");
        Method shipGetChildModulesCopy = shipClass.getMethod("getChildModulesCopy");
        Method shipGetParentStation = shipClass.getMethod("getParentStation");
        Method shipGetStationSlot = shipClass.getMethod("getStationSlot");
        Method shipGetHullSpec = shipClass.getMethod("getHullSpec");
        Method shipGetAllWeapons = shipClass.getMethod("getAllWeapons");
        Class<?> vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        Field vecX = vec2Class.getField("x");
        Field vecY = vec2Class.getField("y");

        // ---- Settle window, then strip roster AI: the probe window stays kill-free. ----
        long tick = 0;
        for (int f = 0; f < settleFrames; f++) {
            step.step(fixedDt, tick++);
        }
        List<?> shipsBefore = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        int shipsBeforeCount = shipsBefore.size();
        int rosterStripped = 0;
        for (Object s : new ArrayList<Object>(shipsBefore)) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
                rosterStripped++;
            }
        }
        Object manager = getFleetManager.invoke(engine, side);
        System.out.println(P + " pre_spawn tick=" + tick
                + " repo_ships=" + shipsBeforeCount
                + " roster_ai_stripped=" + rosterStripped
                + " manager_nonNull=" + (manager != null));
        if (manager == null) {
            throw new IllegalStateException("getFleetManager(" + side + ") null — side not addFleet-registered");
        }

        // ---- Member mint: the faithful roster recipe + PRE-SPAWN CR lift (module CR copies the member's). ----
        Class<?> registryClass = Class.forName(VARIANT_REGISTRY_CLASS, true, loader);
        Object variant = registryClass.getMethod(VARIANT_REGISTRY_METHOD, String.class).invoke(null, variantId);
        Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
        if (variant == null || !hullVariantClass.isInstance(variant)) {
            throw new IllegalStateException("variant '" + variantId + "' did not resolve");
        }
        Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
        Class<?> memberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
        Object member = fleetMemberClass
                .getConstructor(int.class, hullVariantClass, memberTypeClass)
                .newInstance(side, variant, memberTypeClass.getField("SHIP").get(null));
        fleetMemberClass.getMethod("setShipName", String.class).invoke(member, "CC Star Fortress");
        Object crewComp = fleetMemberClass.getMethod("getCrewComposition").invoke(member);
        float minCrew = ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(member)).floatValue();
        crewComp.getClass().getMethod("setCrew", float.class).invoke(crewComp, Math.max(minCrew, 1.0f));
        Object repairTracker = fleetMemberClass.getMethod("getRepairTracker").invoke(member);
        repairTracker.getClass().getMethod("setCR", float.class).invoke(repairTracker, 0.7f);
        boolean flagship = (Boolean) fleetMemberClass.getMethod("isFlagship").invoke(member);
        boolean memberSaysStation = (Boolean) fleetMemberClass.getMethod("isStation").invoke(member);
        System.out.println(P + " member_built owner=" + side + " variant=" + variantId
                + " minCrew=" + minCrew + " cr=0.7 member_isStation=" + memberSaysStation
                + " isFlagship=" + flagship);
        if (flagship) {
            // The fleet manager's owner-0 flagship deploy derefs getCombatUI() UNGUARDED — fail loud here.
            throw new IllegalStateException("synthetic member reads isFlagship=true — would NPE headless");
        }

        // ---- The spawn: explicit location (0,0) -> station branch pins it there; burn irrelevant. ----
        Class<?> fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
        Method spawnFleetMember = manager.getClass().getMethod(
                "spawnFleetMember", fleetMemberApiClass, vec2Class, float.class, float.class);
        Object spawnLoc = vec2Class.getConstructor(float.class, float.class).newInstance(0.0f, 0.0f);
        Object station;
        try {
            station = spawnFleetMember.invoke(manager, member, spawnLoc, 0.0f, 0.0f);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.out.println(P + "-end verdict=SPAWN_THREW tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        boolean shipReturned = station != null && shipClass.isInstance(station);
        List<?> ledger = (List<?>) manager.getClass().getMethod("getDeployedCopy").invoke(manager);
        boolean ledgerHasMember = ledger.contains(member);
        // The fleet manager's station branch runs executeAdds itself: parent + 24 modules are already
        // in the repo THIS frame — and this is the only moment the +25 delta is assertable, because
        // drone_station_lt starts adding merlon drones within ticks of the spawn.
        int shipsSameFrame = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
        boolean repoDelta25 = shipsSameFrame == shipsBeforeCount + 25;
        boolean isStation = shipReturned && (Boolean) shipIsStation.invoke(station);
        System.out.println(P + " spawned tick=" + tick
                + " ship_returned=" + shipReturned
                + " is_station=" + isStation
                + " ledger=" + ledgerHasMember
                + " repo_ships_same_frame=" + shipsSameFrame
                + " (station branch runs executeAdds itself — modules may be pre-drained)");
        if (!shipReturned) {
            System.out.println(P + "-end verdict=FAILED reason=no_ship_returned");
            System.exit(1);
            return;
        }

        // ---- Drain + census. ----
        step.step(fixedDt, tick++);
        boolean alive = (Boolean) isShipAlive.invoke(engine, station);
        int shipsAfterDrain = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
        List<?> children = (List<?>) shipGetChildModulesCopy.invoke(station);
        Map<String, Integer> actualHulls = new TreeMap<String, Integer>();
        int parented = 0;
        int slotted = 0;
        int modulesAlive = 0;
        int moduleWeapons = 0;
        for (Object child : children) {
            Object spec = shipGetHullSpec.invoke(child);
            String hullId = String.valueOf(spec.getClass().getMethod("getHullId").invoke(spec));
            Integer prev = actualHulls.get(hullId);
            actualHulls.put(hullId, prev == null ? 1 : prev + 1);
            if (shipGetParentStation.invoke(child) == station) {
                parented++;
            }
            if (shipGetStationSlot.invoke(child) != null) {
                slotted++;
            }
            if ((Boolean) isShipAlive.invoke(engine, child)) {
                modulesAlive++;
            }
            moduleWeapons += ((List<?>) shipGetAllWeapons.invoke(child)).size();
        }
        Map<String, Integer> expected = expectedModuleHulls();
        boolean histogramMatch = expected.equals(actualHulls);
        System.out.println(P + " census tick=" + tick
                + " alive=" + alive
                + " modules=" + children.size()
                + " parented=" + parented
                + " slotted=" + slotted
                + " modules_alive=" + modulesAlive
                + " module_weapons_total=" + moduleWeapons
                + " repo_ships=" + shipsAfterDrain
                + " histogram_match=" + histogramMatch
                + " parent_ai_nonNull=" + (shipGetShipAI.invoke(station) != null)
                + " actual=" + histo(actualHulls));

        // ---- Window: pin + lattice stability + rotation/drone/mine observation. ----
        float[] pinPos = vecF(shipGetLocation.invoke(station), vecX, vecY);
        float lastFacing = ((Number) shipGetFacing.invoke(station)).floatValue();
        float facingAccum = 0.0f;
        boolean latticeSane = true;
        List<Object> childRefs = new ArrayList<Object>(children);
        for (int f = 0; f < windowFrames; f++) {
            step.step(fixedDt, tick++);
            float facingNow = ((Number) shipGetFacing.invoke(station)).floatValue();
            facingAccum += Math.abs(wrapDeg(facingNow - lastFacing));
            lastFacing = facingNow;
            if (f % 180 == 0 || f == windowFrames - 1) {
                float[] pos = vecF(shipGetLocation.invoke(station), vecX, vecY);
                float[] vel = vecF(shipGetVelocity.invoke(station), vecX, vecY);
                float maxModuleDist = 0.0f;
                for (Object child : childRefs) {
                    float[] cp = vecF(shipGetLocation.invoke(child), vecX, vecY);
                    if (Float.isNaN(cp[0]) || Float.isNaN(cp[1])) {
                        latticeSane = false;
                        continue;
                    }
                    float d = (float) Math.hypot(cp[0] - pos[0], cp[1] - pos[1]);
                    if (d > maxModuleDist) {
                        maxModuleDist = d;
                    }
                }
                if (Float.isNaN(pos[0]) || Float.isNaN(pos[1]) || maxModuleDist > 800.0f) {
                    latticeSane = false;
                }
                System.out.println(P + " window tick=" + tick
                        + " pos=" + vec(shipGetLocation.invoke(station), vecX, vecY)
                        + " vel=(" + f1(vel[0]) + "," + f1(vel[1]) + ")"
                        + " facing=" + f1(facingNow)
                        + " max_module_dist=" + f1(maxModuleDist)
                        + " repo_ships=" + ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size()
                        + " missiles=" + missileCount(loader, engine, getObjects, repoGetList));
            }
        }

        float[] endPos = vecF(shipGetLocation.invoke(station), vecX, vecY);
        float drift = (float) Math.hypot(endPos[0] - pinPos[0], endPos[1] - pinPos[1]);
        // Measured across runs: the fixed-location station CREEPS ~0.06-0.2 su/s (velocity reads 0;
        // position nudged by collision/mine activity — run 1: 0.96 su, run 2: 2.9 su per 15 s).
        // The assertable law is pinned-vs-drifting: an unpinned body travels hundreds of su here.
        boolean pinned = drift < 10.0f && Math.abs(pinPos[0]) < 1.0f && Math.abs(pinPos[1]) < 1.0f;
        int modulesAliveEnd = 0;
        int droneCount = 0;
        for (Object child : childRefs) {
            if ((Boolean) isShipAlive.invoke(engine, child)) {
                modulesAliveEnd++;
            }
        }
        for (Object s : (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)) {
            Object spec = shipGetHullSpec.invoke(s);
            if ("merlon".equals(String.valueOf(spec.getClass().getMethod("getHullId").invoke(spec)))) {
                droneCount++;
            }
        }
        boolean stillAlive = (Boolean) isShipAlive.invoke(engine, station);

        // ---- (B) d1 + d3: mine NULL-source walk + F1 double-harvest dedupe verify. ----
        // Ordering is deliberate: every station verdict value above is already measured, so the
        // lattice verdict describes an UNPERTURBED run and this block only ADDS one entity. The
        // repo law on mutating runtime probes is satisfied structurally, not by rollback: the
        // spawn lands in the throwaway headless engine this process created and owns, which dies
        // with the process — nothing outside the probe can observe it, and the installed game is
        // untouched either way. Engine-faithful: the mine is spawned and then only READ.
        String mineWalkVerdict = "off";
        boolean mineWalkPass = true;
        if (mineWalk) {
            Class<?> combatEngineApiClass = Class.forName(COMBAT_ENGINE_API, false, loader);
            Class<?> weaponApiClass = Class.forName(WEAPON_API, false, loader);
            Class<?> missileApiClass = Class.forName(MISSILE_API, false, loader);
            Class<?> missileEntityClass = Class.forName(MISSILE_CLASS, false, loader);
            Method engineSpawnProjectile = combatEngineApiClass.getMethod("spawnProjectile",
                    shipApiClass, weaponApiClass, String.class, vec2Class, float.class, vec2Class);
            Method engineGetMissiles = combatEngineApiClass.getMethod("getMissiles");
            Method engineGetProjectiles = combatEngineApiClass.getMethod("getProjectiles");

            float[] stationNow = vecF(shipGetLocation.invoke(station), vecX, vecY);
            Object mineSpawnLoc = vec2Class.getConstructor(float.class, float.class)
                    .newInstance(stationNow[0] + mineOffsetSu, stationNow[1]);
            List<CombatEntityView> missileViewBefore = binding.missileEntitiesOrNull();
            int missileViewBeforeCount = missileViewBefore == null ? -1 : missileViewBefore.size();
            long maxMissileIdBefore = maxNetworkId(missileViewBefore);

            Object mine;
            try {
                // The vanilla spawner's exact call shape (StealthMinefield.spawnMine, api impl
                // hullmods): spawnProjectile(source, NULL weapon, weaponId, loc, dir, null) — walked
                // here with the SOURCE null as well, which is d1's open question. Static ground
                // truth: a null source is substituted with a
                // synthetic Ship inside the engine, and a weapon arg not of the engine's own weapon class
                // resolves to a null firing weapon — so the read-back below should hit exactly
                // the null/foreign source path the wire tier must survive, not a literal null getSource().
                mine = engineSpawnProjectile.invoke(engine, null, null, mineWeaponId,
                        mineSpawnLoc, 0.0f, null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                System.out.println(P + "-end verdict=MINE_SPAWN_THREW tick=" + tick
                        + " mine_weapon=" + mineWeaponId
                        + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
                cause.printStackTrace(System.out);
                System.exit(2);
                return;
            }

            boolean mineNonNull = mine != null;
            boolean mineIsMissileApi = mineNonNull && missileApiClass.isInstance(mine);
            boolean mineIsCombatMissile = mineNonNull && missileEntityClass.isInstance(mine);
            // d1: these are the reads EngineCombatBinding.readPointEntities performs per missile (getSource,
            // then getWeapon). Each is captured separately so a THROW is named, never swallowed.
            StringBuilder readFailures = new StringBuilder();
            Object mineSource = mineNonNull ? readOrNote(mine, "getSource", readFailures) : null;
            Object mineWeapon = mineNonNull ? readOrNote(mine, "getWeapon", readFailures) : null;
            Object mineSpecId = mineNonNull ? readOrNote(mine, "getProjectileSpecId", readFailures) : null;
            Object mineOwnerValue = mineNonNull ? readOrNote(mine, "getOwner", readFailures) : null;
            List<?> repoShipsAtMine = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
            boolean sourceInRepo = identityContains(repoShipsAtMine, mineSource);
            System.out.println(P + " mine_walk_spawn tick=" + tick
                    + " mine_weapon=" + mineWeaponId
                    + " returned_nonNull=" + mineNonNull
                    + " is_MissileAPI=" + mineIsMissileApi
                    + " is_combat_Missile=" + mineIsCombatMissile
                    + " class=" + (mineNonNull ? mine.getClass().getName() : "null")
                    + " spec_id=" + mineSpecId
                    + " owner=" + (mineOwnerValue instanceof Number ? String.valueOf(((Number) mineOwnerValue).intValue()) : "n/a")
                    + " source_null=" + (mineSource == null)
                    + " source_class=" + (mineSource == null ? "null" : mineSource.getClass().getName())
                    + " source_in_repo=" + sourceInRepo
                    + " weapon_null=" + (mineWeapon == null)
                    + " spawn_loc=" + vec(mineSpawnLoc, vecX, vecY)
                    + " missile_view_before=" + missileViewBeforeCount);

            // One frame so the mine is a fully-installed live list member, the cadence the wire
            // tier actually reads at.
            step.step(fixedDt, tick++);

            List<?> rawMissiles = (List<?>) engineGetMissiles.invoke(engine);
            List<?> rawProjectiles = (List<?>) engineGetProjectiles.invoke(engine);
            boolean rawMissileHasMine = identityContains(rawMissiles, mine);
            boolean rawProjHasMine = identityContains(rawProjectiles, mine);
            int rawOverlap = identityOverlap(rawProjectiles, rawMissiles);
            List<CombatEntityView> missileViews = binding.missileEntitiesOrNull();
            List<CombatEntityView> projViews = binding.projectileEntitiesOrNull();
            boolean missileViewNonNull = missileViews != null;
            boolean projViewNonNull = projViews != null;
            Object mineLocObj = mineNonNull ? readOrNote(mine, "getLocation", readFailures) : null;
            float[] minePos = mineLocObj == null ? null : vecF(mineLocObj, vecX, vecY);
            CombatEntityView mineMissileView = minePos == null
                    ? null : nearestView(missileViews, minePos[0], minePos[1]);
            CombatEntityView mineProjView = minePos == null
                    ? null : nearestView(projViews, minePos[0], minePos[1]);

            // d3: the mine must cross exactly ONCE — missile tier only, stable id band.
            boolean inMissileView = mineMissileView != null
                    && mineMissileView.networkId() >= CombatEntityView.MISSILE_NETWORK_ID_BASE;
            boolean absentFromProjView = mineProjView == null;
            boolean crossedOnce = inMissileView && absentFromProjView;
            boolean newStableId = mineMissileView != null
                    && mineMissileView.networkId() > maxMissileIdBefore;
            // The identity-skip's arithmetic, list-wide: every projectile-list entry that is also a
            // missile-list entry (the type-closure double-harvest) must be the exact set
            // the PROJECTILE pass drops.
            int expectedProjViewSize = rawProjectiles.size() - rawOverlap;
            boolean dedupeSizeOk = projViewNonNull && projViews.size() == expectedProjViewSize;
            // d1: source/weapon reads must fail SOFT — the record still crosses, the pair degrades.
            long srcShipId = mineMissileView == null ? 0L : mineMissileView.sourcePositionShipId();
            int srcMount = mineMissileView == null ? -1 : mineMissileView.sourceMountIndex();
            boolean srcPairDegraded = srcShipId == 0L && srcMount == -1;
            int poseFlags = mineMissileView == null ? 0 : mineMissileView.missilePoseFlags();
            boolean poseValid = (poseFlags & NonShipEntitySnapshot.POSE_FLAG_VALID) != 0;
            String contentKey = mineMissileView == null ? "" : mineMissileView.contentKey();
            boolean readsFailSoft = readFailures.length() == 0;

            System.out.println(P + " mine_walk_lists tick=" + tick
                    + " raw_projectiles=" + rawProjectiles.size()
                    + " raw_missiles=" + rawMissiles.size()
                    + " raw_overlap=" + rawOverlap
                    + " raw_missile_has_mine=" + rawMissileHasMine
                    + " raw_proj_has_mine=" + rawProjHasMine
                    + " proj_view=" + (projViewNonNull ? String.valueOf(projViews.size()) : "null")
                    + " missile_view=" + (missileViewNonNull ? String.valueOf(missileViews.size()) : "null")
                    + " expected_proj_view=" + expectedProjViewSize
                    + " mine_pos=" + (minePos == null ? "null" : "(" + f1(minePos[0]) + "," + f1(minePos[1]) + ")"));

            mineWalkPass = mineNonNull && mineIsMissileApi && missileViewNonNull && projViewNonNull
                    && crossedOnce && dedupeSizeOk && readsFailSoft;
            mineWalkVerdict = mineWalkPass ? "PASS" : "FAILED";
            System.out.println(P + " mine_walk_verdict tick=" + tick
                    + " mine_walk=" + mineWalkVerdict
                    + " spawned_MissileAPI=" + mineIsMissileApi
                    + " missile_view_nonNull=" + missileViewNonNull
                    + " proj_view_nonNull=" + projViewNonNull
                    + " in_missile_view=" + inMissileView
                    + " missile_wire_id=" + (mineMissileView == null ? "none" : String.valueOf(mineMissileView.networkId()))
                    + " id_above_base=" + inMissileView
                    + " id_newly_minted=" + newStableId
                    + " absent_from_proj_view=" + absentFromProjView
                    + " crossed_once=" + crossedOnce
                    + " dedupe_size_ok=" + dedupeSizeOk
                    + " null_path_reads_fail_soft=" + readsFailSoft
                    + " read_failures=" + (readsFailSoft ? "none" : readFailures.toString())
                    + " source_pair=(" + srcShipId + "," + srcMount + ")"
                    + " source_pair_degraded=" + srcPairDegraded
                    + " pose_flags=" + poseFlags
                    + " pose_valid=" + poseValid
                    + " missile_facing_u16=" + (mineMissileView == null ? -1 : mineMissileView.missileFacingU16())
                    + " content_key='" + contentKey + "'"
                    + " view_owner=" + (mineMissileView == null ? "n/a" : String.valueOf(mineMissileView.owner())));
        }

        // ---- (C) mine proximity-fuse probe — is the ALL-OBJECT grid
        // (getAiGrid(), the scan's sole data source) populated headless?
        // One bait enemy (AI-stripped, parked in fuse range) + ONE serve-shape mine (non-null
        // station source), then per-frame: isMinePrimed/getUntilMineExplosion transitions plus
        // the three-way entry-count comparison at the mine's location. Runs AFTER every
        // station/mine-walk verdict is measured; same structural mutation law as (B) — the spawns
        // land in this process's throwaway engine, engine-faithful, read-only afterwards.
        String fuseVerdict = "off";
        int fuseExit = 0;
        if (fuseProbe) {
            Class<?> ceApiClass = Class.forName(COMBAT_ENGINE_API, false, loader);
            Class<?> wpnApiClass = Class.forName(WEAPON_API, false, loader);
            Class<?> missileClass = Class.forName(MISSILE_CLASS, false, loader);
            Method spawnProj = ceApiClass.getMethod("spawnProjectile",
                    shipApiClass, wpnApiClass, String.class, vec2Class, float.class, vec2Class);
            Method getAiGrid = engineClass.getMethod("getAiGrid");
            Method getAiGridShips = engineClass.getMethod("getAiGridShips");
            Method gridCheckIterator = null;
            Method shipGetOwner = shipClass.getMethod("getOwner");
            Method shipIsTargetable = shipClass.getMethod("isTargetable");
            Method shipGetCollisionClass = shipClass.getMethod("getCollisionClass");
            Method shipGetOriginalOwner = shipClass.getMethod("getOriginalOwner");
            Method shipGetHullLevel = shipClass.getMethod("getHullLevel");

            Object manager1 = getFleetManager.invoke(engine, 1);
            if (manager1 == null) {
                throw new IllegalStateException("getFleetManager(1) null — side 1 not addFleet-registered");
            }

            // Bait member: the station recipe minus station-ness; variant proven resolvable in
            // this boot by RealEngineMidDeployProbeMain (side-1 spawnFleetMember lane).
            Object baitVariant = registryClass.getMethod(VARIANT_REGISTRY_METHOD, String.class)
                    .invoke(null, fuseEnemyVariant);
            if (baitVariant == null || !hullVariantClass.isInstance(baitVariant)) {
                throw new IllegalStateException("fuse bait variant '" + fuseEnemyVariant + "' did not resolve");
            }
            Object bait = fleetMemberClass
                    .getConstructor(int.class, hullVariantClass, memberTypeClass)
                    .newInstance(1, baitVariant, memberTypeClass.getField("SHIP").get(null));
            fleetMemberClass.getMethod("setShipName", String.class).invoke(bait, "CC Fuse Bait");
            Object baitCrew = fleetMemberClass.getMethod("getCrewComposition").invoke(bait);
            float baitMinCrew = ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(bait)).floatValue();
            baitCrew.getClass().getMethod("setCrew", float.class).invoke(baitCrew, Math.max(baitMinCrew, 1.0f));
            Object baitRepair = fleetMemberClass.getMethod("getRepairTracker").invoke(bait);
            baitRepair.getClass().getMethod("setCR", float.class).invoke(baitRepair, 0.7f);
            if ((Boolean) fleetMemberClass.getMethod("isFlagship").invoke(bait)) {
                throw new IllegalStateException("fuse bait reads isFlagship=true — would NPE headless");
            }
            float[] stationNow2 = vecF(shipGetLocation.invoke(station), vecX, vecY);
            Object baitLoc = vec2Class.getConstructor(float.class, float.class)
                    .newInstance(stationNow2[0] + fuseOffsetSu, stationNow2[1]);
            Object enemy;
            try {
                enemy = spawnFleetMember.invoke(manager1, bait, baitLoc, 0.0f, 0.0f);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                System.out.println(P + "-end verdict=FUSE_BAIT_SPAWN_THREW tick=" + tick
                        + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
                cause.printStackTrace(System.out);
                System.exit(2);
                return;
            }
            if (enemy == null || !shipClass.isInstance(enemy)) {
                System.out.println(P + "-end verdict=FAILED reason=fuse_bait_no_ship");
                System.exit(1);
                return;
            }
            // Park it: AI-stripped (the roster-strip idiom) so native avoidance can't walk the
            // bait out of range; re-strip per frame in case the engine re-assigns.
            int baitStrips = 0;
            if (shipGetShipAI.invoke(enemy) != null) {
                shipSetShipAI.invoke(enemy, new Object[]{null});
                baitStrips++;
            }
            for (int f = 0; f < 60; f++) {
                step.step(fixedDt, tick++);
                if (shipGetShipAI.invoke(enemy) != null) {
                    shipSetShipAI.invoke(enemy, new Object[]{null});
                    baitStrips++;
                }
            }
            float[] enemyPos = vecF(shipGetLocation.invoke(enemy), vecX, vecY);
            System.out.println(P + " fuse_bait tick=" + tick
                    + " variant=" + fuseEnemyVariant
                    + " owner=" + shipGetOwner.invoke(enemy)
                    + " targetable=" + shipIsTargetable.invoke(enemy)
                    + " collision_class=" + shipGetCollisionClass.invoke(enemy)
                    + " hull=" + shipGetHullLevel.invoke(enemy)
                    + " pos=(" + f1(enemyPos[0]) + "," + f1(enemyPos[1]) + ")"
                    + " ai_strips=" + baitStrips);

            // The mine: serve shape (non-null station source, the StealthMinefieldLT call form).
            // Perpendicular gap so launch drift (+X, slowToMaxSpeed) stays range-neutral; the
            // in-range condition is metered per frame regardless.
            Object fuseMineLoc = vec2Class.getConstructor(float.class, float.class)
                    .newInstance(enemyPos[0], enemyPos[1] + fuseGapSu);
            Object fmine;
            try {
                fmine = spawnProj.invoke(engine, station, null, mineWeaponId, fuseMineLoc, 0.0f, null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                System.out.println(P + "-end verdict=FUSE_MINE_SPAWN_THREW tick=" + tick
                        + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
                cause.printStackTrace(System.out);
                System.exit(2);
                return;
            }
            if (fmine == null || !missileClass.isInstance(fmine)) {
                System.out.println(P + "-end verdict=FAILED reason=fuse_mine_not_missile class="
                        + (fmine == null ? "null" : fmine.getClass().getName()));
                System.exit(1);
                return;
            }
            Method mineIsMine = fmine.getClass().getMethod("isMine");
            Method mineIsMinePrimed = fmine.getClass().getMethod("isMinePrimed");
            Method mineGetUntil = fmine.getClass().getMethod("getUntilMineExplosion");
            Method mineGetLocation = fmine.getClass().getMethod("getLocation");
            StringBuilder fuseReadFailures = new StringBuilder();
            Object mineAi = readOrNote(fmine, "getAI", fuseReadFailures);
            Object fuseMineSource = readOrNote(fmine, "getSource", fuseReadFailures);
            System.out.println(P + " fuse_mine_spawn tick=" + tick
                    + " class=" + fmine.getClass().getName()
                    + " is_mine=" + mineIsMine.invoke(fmine)
                    + " owner=" + readOrNote(fmine, "getOwner", fuseReadFailures)
                    + " source_is_station=" + (fuseMineSource == station)
                    + " source_original_owner=" + (fuseMineSource == null || !shipClass.isInstance(fuseMineSource)
                            ? "n/a" : String.valueOf(shipGetOriginalOwner.invoke(fuseMineSource)))
                    + " ai_class=" + (mineAi == null ? "null" : mineAi.getClass().getName())
                    + " loc=" + vec(fuseMineLoc, vecX, vecY)
                    + " read_failures=" + (fuseReadFailures.length() == 0 ? "none" : fuseReadFailures.toString()));

            // Metering window: fuse observables every frame, grid three-way every 30 or on any
            // transition. Arming delay = 3 s (180 frames) after the mine spawn (behaviorSpec).
            int inRangeFrames = 0;
            int armedInRangeFrames = 0;
            int gridSamples = 0;
            int gridAllEnemySamples = 0;
            int gridShipsEnemySamples = 0;
            int gridAllEmptyShipsPop = 0;
            long mineSpawnTick = tick;
            long firstPrimedTick = -1;
            long mineGoneTick = -1;
            float hull0 = ((Number) shipGetHullLevel.invoke(enemy)).floatValue();
            float minHull = hull0;
            boolean prevPrimed = false;
            boolean prevInRange = false;
            for (int f = 0; f < fuseWindowFrames && mineGoneTick < 0; f++) {
                step.step(fixedDt, tick++);
                boolean mineInRepo = identityContains(
                        (List<?>) repoGetList.invoke(getObjects.invoke(engine), missileClass), fmine);
                boolean primed = mineInRepo && (Boolean) mineIsMinePrimed.invoke(fmine);
                float until = mineInRepo ? ((Number) mineGetUntil.invoke(fmine)).floatValue() : -1.0f;
                float[] mp = vecF(mineGetLocation.invoke(fmine), vecX, vecY);
                float[] ep = vecF(shipGetLocation.invoke(enemy), vecX, vecY);
                float dist = (float) Math.hypot(mp[0] - ep[0], mp[1] - ep[1]);
                boolean baitTargetable = (Boolean) shipIsTargetable.invoke(enemy);
                boolean inRange = mineInRepo && dist <= 175.0f && baitTargetable;
                if (inRange) {
                    inRangeFrames++;
                    if (f >= 180) {
                        armedInRangeFrames++;
                    }
                }
                float hull = ((Number) shipGetHullLevel.invoke(enemy)).floatValue();
                if (hull < minHull) {
                    minHull = hull;
                }
                if (primed && firstPrimedTick < 0) {
                    firstPrimedTick = tick;
                }
                if (!mineInRepo && mineGoneTick < 0) {
                    mineGoneTick = tick;
                }
                boolean transition = primed != prevPrimed || inRange != prevInRange || !mineInRepo;
                if (f % 30 == 0 || transition || f == fuseWindowFrames - 1) {
                    Object gridAll = getAiGrid.invoke(engine);
                    Object gridShips = getAiGridShips.invoke(engine);
                    if (gridCheckIterator == null && gridAll != null) {
                        gridCheckIterator = gridAll.getClass().getMethod("getCheckIterator",
                                vec2Class, float.class, float.class);
                    }
                    Object center = vec2Class.getConstructor(float.class, float.class)
                            .newInstance(mp[0], mp[1]);
                    int[] ga = gridScan(gridCheckIterator, gridAll, center, enemy, fmine);
                    int[] gs = gridScan(gridCheckIterator, gridShips, center, enemy, fmine);
                    int repoShips = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
                    int repoMines = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), missileClass)).size();
                    gridSamples++;
                    if (ga[1] == 1) {
                        gridAllEnemySamples++;
                    }
                    if (gs[1] == 1) {
                        gridShipsEnemySamples++;
                    }
                    if (ga[0] == 0 && gs[0] > 0) {
                        gridAllEmptyShipsPop++;
                    }
                    System.out.println(P + " fuse_s tick=" + tick
                            + " dist=" + f1(dist) + " in_range=" + inRange
                            + " mine[in_repo=" + mineInRepo + " primed=" + primed + " until=" + f1(until) + "]"
                            + " grid_all[n=" + ga[0] + " enemy=" + (ga[1] == 1) + " mine=" + (ga[2] == 1) + "]"
                            + " grid_ships[n=" + gs[0] + " enemy=" + (gs[1] == 1) + "]"
                            + " repo[ships=" + repoShips + " mines=" + repoMines + "]"
                            + " enemy[hull=" + hull
                            + " targetable=" + baitTargetable
                            + " alive=" + isShipAlive.invoke(engine, enemy) + "]");
                }
                prevPrimed = primed;
                prevInRange = inRange;
            }
            boolean enemyDamaged = minHull < hull0 - 0.001f;
            boolean explodedInference = mineGoneTick >= 0
                    && (firstPrimedTick >= 0 || enemyDamaged)
                    && (mineGoneTick - mineSpawnTick) < (long) (19.0f / fixedDt);
            if (firstPrimedTick >= 0) {
                fuseVerdict = "FUSE_FIRED_HEADLESS";
            } else if (armedInRangeFrames < 240) {
                fuseVerdict = "INCONCLUSIVE_GEOMETRY";
                fuseExit = 3;
            } else if (gridSamples > 0 && gridAllEnemySamples == 0 && gridShipsEnemySamples > 0) {
                fuseVerdict = "GRID_STARVED_CONFIRMED";
            } else if (gridSamples > 0 && gridAllEnemySamples == 0) {
                fuseVerdict = "BOTH_GRIDS_MISS_ENEMY";
            } else {
                fuseVerdict = "FUSE_DEAD_GRID_OK";
            }
            System.out.println(P + " fuse_verdict tick=" + tick
                    + " fuse=" + fuseVerdict
                    + " in_range_frames=" + inRangeFrames
                    + " armed_in_range_frames=" + armedInRangeFrames
                    + " grid_samples=" + gridSamples
                    + " grid_all_enemy_samples=" + gridAllEnemySamples
                    + " grid_ships_enemy_samples=" + gridShipsEnemySamples
                    + " grid_all_empty_ships_pop=" + gridAllEmptyShipsPop
                    + " mine_spawn_tick=" + mineSpawnTick
                    + " first_primed_tick=" + firstPrimedTick
                    + " mine_gone_tick=" + mineGoneTick
                    + " exploded_inference=" + explodedInference
                    + " enemy_hull_start=" + hull0
                    + " enemy_hull_min=" + minHull);
        }

        boolean pass = shipReturned && isStation && ledgerHasMember && alive && stillAlive
                && children.size() == 24 && parented == 24 && slotted == 24 && histogramMatch
                && repoDelta25 && modulesAliveEnd == 24 && pinned && latticeSane
                // OFF ⇒ mineWalkPass stays true ⇒ the verdict is unchanged.
                && mineWalkPass;
        System.out.println(P + "-end verdict=" + (pass ? "STATION_HEADLESS_PROVEN" : "FAILED")
                + " ship_returned=" + shipReturned
                + " is_station=" + isStation
                + " ledger=" + ledgerHasMember
                + " alive_after_drain=" + alive
                + " alive_after_window=" + stillAlive
                + " modules=" + children.size()
                + " parented=" + parented
                + " slotted=" + slotted
                + " histogram_match=" + histogramMatch
                + " repo_delta_25=" + repoDelta25
                + " modules_alive_end=" + modulesAliveEnd
                + " pinned=" + pinned
                + " drift_su=" + f1(drift)
                + " lattice_sane=" + latticeSane
                + " facing_total_deg=" + f1(facingAccum)
                + " axial_rotation_observed=" + (facingAccum > 1.0f)
                + " merlon_drones_end=" + droneCount
                + " module_weapons_total=" + moduleWeapons
                + " mine_walk=" + mineWalkVerdict
                + " fuse=" + fuseVerdict);
        System.exit(pass ? fuseExit : 1);
    }

    /**
     * Grid query in the scan's own shape ({@code getCheckIterator(loc, 2r, 2r)},
     * r=175): returns {entry count, needleA seen, needleB seen}; {-1,0,0} when the grid or
     * iterator method is unavailable.
     */
    private static int[] gridScan(Method checkIterator, Object grid, Object center,
            Object needleA, Object needleB) throws Exception {
        if (grid == null || checkIterator == null) {
            return new int[]{-1, 0, 0};
        }
        int count = 0;
        int hasA = 0;
        int hasB = 0;
        Iterator<?> it = (Iterator<?>) checkIterator.invoke(grid, center, 350.0f, 350.0f);
        while (it.hasNext()) {
            Object o = it.next();
            count++;
            if (o == needleA) {
                hasA = 1;
            }
            if (o == needleB) {
                hasB = 1;
            }
        }
        return new int[]{count, hasA, hasB};
    }

    private static int missileCount(ClassLoader loader, Object engine, Method getObjects, Method repoGetList) {
        try {
            Class<?> missileClass = Class.forName(MISSILE_CLASS, false, loader);
            return ((List<?>) repoGetList.invoke(getObjects.invoke(engine), missileClass)).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * One reflective no-arg read that NAMES its failure instead of swallowing it (the d1 walk:
     * the question is precisely whether these reads throw on a source-less mine). Returns the
     * value, or null after appending {@code method:ExceptionSimpleName} to {@code failures}.
     */
    private static Object readOrNote(Object target, String method, StringBuilder failures) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            Throwable cause = (t instanceof InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            if (failures.length() > 0) {
                failures.append(',');
            }
            failures.append(method).append(':').append(cause.getClass().getSimpleName());
            return null;
        }
    }

    /** Identity (==) membership, never equals(): engine list entries are live objects. */
    private static boolean identityContains(List<?> list, Object target) {
        if (list == null || target == null) {
            return false;
        }
        for (Object o : list) {
            if (o == target) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many entries of {@code a} are the SAME OBJECT as an entry of {@code b} — the size of the
     * repository's type-closure double-harvest and therefore exactly the set the
     * ECB projectile pass identity-skips.
     */
    private static int identityOverlap(List<?> a, List<?> b) {
        if (a == null || b == null) {
            return 0;
        }
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        set.addAll(b);
        int n = 0;
        for (Object o : a) {
            if (set.contains(o)) {
                n++;
            }
        }
        return n;
    }

    /** The view whose position sits within {@link #MINE_MATCH_EPS_SU} of (x,y); null if none. */
    private static CombatEntityView nearestView(List<CombatEntityView> views, float x, float y) {
        if (views == null) {
            return null;
        }
        CombatEntityView best = null;
        double bestDist = MINE_MATCH_EPS_SU;
        for (CombatEntityView v : views) {
            double d = Math.hypot(v.posX() - x, v.posY() - y);
            if (d <= bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best;
    }

    /** Highest network id in a view list (0 when empty/null) — the pre-spawn id watermark. */
    private static long maxNetworkId(List<CombatEntityView> views) {
        long max = 0L;
        if (views != null) {
            for (CombatEntityView v : views) {
                if (v.networkId() > max) {
                    max = v.networkId();
                }
            }
        }
        return max;
    }

    private static String histo(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : new LinkedHashMap<String, Integer>(m).entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static float wrapDeg(float d) {
        while (d > 180.0f) {
            d -= 360.0f;
        }
        while (d < -180.0f) {
            d += 360.0f;
        }
        return d;
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
