//
package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The headless PROOF of the fighter-wing REGISTRATION idiom the client
 * lane uses. On the live engine at the server's fixed dt: spawn one fresh {@code astral_Flash}
 * carrier on side 1, then register a deployed fighter wing per launch bay through the exact engine path
 * — {@code new FleetMember(owner, wingId, FIGHTER_WING)} → {@code oo} = the fleet manager's member wrapper built on it
 * → {@code mgr.addWing(oo, x, y, false)} (false = SKIP fighter minting) → {@code dfm.getWingIfFighter()}
 * → {@code wing.setSourceShip(carrier)} + {@code wing.setSourceBay(bay)}. {@code setWing} is NEVER
 * called on any ship. Replicates the vanilla HUD wing-panel filter
 * and the wing-panel icon constructor's precondition, then documents the pips + shared-rate laws.
 *
 * <p>Same product boot + step boundary as the serve loop ({@code RealEngineCombatBindingFactory
 * .create()} + {@code EngineBackedCombatStepBoundary.step}); reflection is fine (server-side harness).
 * Roster AI + carrier AI stripped so nothing perturbs the roster. A MEASUREMENT main, never wired
 * into the serve. Exit: 0 = every Check row passed (verdict WING_REGISTERED); 2 = a spawn/register
 * reflective call threw (boundary in log); 1 = infrastructure or a row mismatch. A wrapper
 * script runs it.
 */
public final class RealEngineFighterWingProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineZeroFluxBandProbeMain. ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String HULL_VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String VARIANT_REGISTRY_CLASS = "com.fs.starfarer.loading.B";
    private static final String VARIANT_REGISTRY_METHOD = "o00000";
    private static final String FLEET_MEMBER_CLASS = "com.fs.starfarer.campaign.fleet.FleetMember";
    private static final String FLEET_MEMBER_TYPE_API = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIP_VARIANT_API = "com.fs.starfarer.api.combat.ShipVariantAPI";
    private static final String LAUNCH_BAY_API = "com.fs.starfarer.api.combat.FighterLaunchBayAPI";
    private static final String FLEET_MANAGER_CLASS = "com.fs.starfarer.combat.CombatFleetManager";
    private static final String OO_CLASS = "com.fs.starfarer.combat.CombatFleetManager$oo";
    private static final String O0_CLASS = "com.fs.starfarer.combat.CombatFleetManager$O0";
    private static final String M_CLASS = "com.fs.starfarer.combat.ai.M";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "fighter-wing-probe";
    private static final int EXPECTED_BAYS = 6; // astral_Flash: six flash_wing bays.

    private RealEngineFighterWingProbeMain() {
    }

    /** Row tally: [0]=passed, [1]=failed. */
    private static boolean row(String name, boolean cond, int[] tally) {
        tally[cond ? 0 : 1]++;
        System.out.println(P + " row name=" + name + " pass=" + cond);
        return cond;
    }

    public static void main(String[] args) throws Exception {
        int preFrames = 120;
        float fixedDt = SimTickRate.fixedDt();
        String variantId = "astral_Flash";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--pre-frames": preFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--variant": variantId = args[++i]; break;
                default: break;
            }
        }
        System.out.println(P + "-begin pre_frames=" + preFrames + " fixed_dt=" + fixedDt + " variant=" + variantId);

        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEngineFighterWingProbeMain.class.getClassLoader();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
        Class<?> shipVariantApiClass = Class.forName(SHIP_VARIANT_API, false, loader);
        Class<?> launchBayApiClass = Class.forName(LAUNCH_BAY_API, false, loader);
        Class<?> fleetManagerClass = Class.forName(FLEET_MANAGER_CLASS, false, loader);
        Class<?> ooClass = Class.forName(OO_CLASS, false, loader);
        Class<?> o0Class = Class.forName(O0_CLASS, false, loader);
        Class<?> mClass = Class.forName(M_CLASS, false, loader);
        Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
        Class<?> memberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
        Class<?> fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
        Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
        Class<?> vector2fClass = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);

        Object engine = engineClass.getMethod("getInstance").invoke(null);
        if (engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after create()");
        }
        Method getFleetManager = engineClass.getMethod("getFleetManager", int.class);
        Method getObjects = engineClass.getMethod("getObjects");
        Method repoGetList = getObjects.invoke(engine).getClass().getMethod("getList", Class.class);
        Method isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
        Method shipGetShipAI = shipClass.getMethod("getShipAI");
        Method shipSetShipAI = findOneArg(shipClass, "setShipAI");

        // ShipAPI reads used below.
        Method shipGetOriginalOwner = shipApiClass.getMethod("getOriginalOwner");
        Method shipGetVariant = shipApiClass.getMethod("getVariant");
        Method shipGetNumFighterBays = shipApiClass.getMethod("getNumFighterBays");
        Method shipGetLaunchBaysCopy = shipApiClass.getMethod("getLaunchBaysCopy");
        Method shipGetLocation = shipApiClass.getMethod("getLocation");
        Method shipGetSharedRate = shipApiClass.getMethod("getSharedFighterReplacementRate");
        Method shipGetWing = shipApiClass.getMethod("getWing");
        Method variantGetWingId = shipVariantApiClass.getMethod("getWingId", int.class);

        // CombatFleetManager / O0 / M handles.
        Method mgrAddWing = fleetManagerClass.getMethod("addWing", ooClass, float.class, float.class, boolean.class);
        Method mgrGetDeployed = fleetManagerClass.getMethod("getDeployed");
        Method mgrGetDeployedFleetMemberM = fleetManagerClass.getMethod("getDeployedFleetMember", mClass);
        Method mgrSpawnFleetMember = fleetManagerClass.getMethod("spawnFleetMember",
                fleetMemberApiClass, vector2fClass, float.class, float.class);
        java.lang.reflect.Constructor<?> ooCtor = ooClass.getConstructor(fleetMemberClass);
        Method o0IsFighterWing = o0Class.getMethod("isFighterWing");
        Method o0GetWingIfFighter = o0Class.getMethod("getWingIfFighter");
        Method o0GetMember = o0Class.getMethod("getMember");
        Method mSetSourceShip = mClass.getMethod("setSourceShip", shipApiClass);
        Method mSetSourceBay = mClass.getMethod("setSourceBay", launchBayApiClass);
        Method mGetSourceShip = mClass.getMethod("getSourceShip");
        Method mGetSource = mClass.getMethod("getSource");
        Method mGetMembers = mClass.getMethod("getMembers");
        Method mGetSpec = mClass.getMethod("getSpec");
        Method mAddMember = mClass.getMethod("addMember", shipApiClass);
        Method mRemoveMember = mClass.getMethod("removeMember", shipApiClass);
        java.lang.reflect.Constructor<?> fmWingCtor =
                fleetMemberClass.getConstructor(int.class, String.class, memberTypeClass);
        Object typeFighterWing = memberTypeClass.getField("FIGHTER_WING").get(null);
        Method registryResolve = Class.forName(VARIANT_REGISTRY_CLASS, true, loader)
                .getMethod(VARIANT_REGISTRY_METHOD, String.class);

        long tick = 0;
        step.withEngagedCarrierBandSnap(false);
        for (int f = 0; f < preFrames; f++) {
            step.step(fixedDt, tick++);
        }
        // Strip roster AI (nothing perturbs the roster during the register/step window).
        List<?> rosterShips = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        int stripped = 0;
        for (Object s : new ArrayList<Object>(rosterShips)) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[] {null});
                stripped++;
            }
        }
        System.out.println(P + " roster_ships=" + rosterShips.size() + " ai_stripped=" + stripped);

        // ---- Fresh carrier on side 1. ----
        Object variant = registryResolve.invoke(null, variantId);
        if (variant == null || !hullVariantClass.isInstance(variant)) {
            throw new IllegalStateException("variant '" + variantId + "' did not resolve — pass --variant");
        }
        Object member = mintMember(fleetMemberClass, memberTypeClass, hullVariantClass, variant, 1, "CC Wing Probe");
        Object ship;
        try {
            ship = mgrSpawnFleetMember.invoke(getFleetManager.invoke(engine, 1), member, null, 180.0f, 4.0f);
        } catch (InvocationTargetException e) {
            bail("SPAWN_THREW", e);
            return;
        }
        step.step(fixedDt, tick++); // engine executeAdds drain
        if (!(Boolean) isShipAlive.invoke(engine, ship)) {
            System.out.println(P + "-end verdict=FAILED reason=spawned carrier not alive rows_ok=0 rows_fail=1");
            System.exit(1);
        }
        shipSetShipAI.invoke(ship, new Object[] {null});

        int owner = ((Number) shipGetOriginalOwner.invoke(ship)).intValue();
        Object mgr = getFleetManager.invoke(engine, owner);
        Object shipVariant = shipGetVariant.invoke(ship);
        int numBays = ((Number) shipGetNumFighterBays.invoke(ship)).intValue();
        List<?> bays = (List<?>) shipGetLaunchBaysCopy.invoke(ship);
        Object loc = shipGetLocation.invoke(ship);
        float locX = vector2fClass.getField("x").getFloat(loc);
        float locY = vector2fClass.getField("y").getFloat(loc);

        // wingIds in slot order (bays are created one per non-null wing slot).
        List<String> wingIds = new ArrayList<String>();
        for (int i = 0; i < numBays; i++) {
            Object wid = variantGetWingId.invoke(shipVariant, i);
            if (wid != null && !((String) wid).isEmpty()) {
                wingIds.add((String) wid);
            }
        }
        System.out.println(P + " carrier owner=" + owner + " num_bays=" + numBays
                + " bays=" + bays.size() + " wing_ids=" + wingIds);

        int[] tally = {0, 0};
        row("bays_match_wings_eq_" + EXPECTED_BAYS,
                wingIds.size() == bays.size() && bays.size() == EXPECTED_BAYS, tally);

        // ---- (2) Register a deployed wing per bay through the exact engine path. ----
        List<Object> wings = new ArrayList<Object>(); // the M wing objects, in bay order
        try {
            for (int k = 0; k < wingIds.size(); k++) {
                Object fm = fmWingCtor.newInstance(owner, wingIds.get(k), typeFighterWing);
                Object oo = ooCtor.newInstance(fm);
                Object dfm = mgrAddWing.invoke(mgr, oo, locX, locY, false); // false = skip fighter minting
                Object wing = o0GetWingIfFighter.invoke(dfm);
                mSetSourceShip.invoke(wing, ship);
                mSetSourceBay.invoke(wing, bays.get(k));
                wings.add(wing);
            }
        } catch (InvocationTargetException e) {
            bail("REGISTER_THREW", e);
            return;
        }

        // ---- (3) HUD wing-panel filter replica + icon-ctor precondition.
        int hudMatch = 0;
        boolean allEmpty = true;
        boolean allIconPre = true;
        for (Object o0 : (Iterable<?>) mgrGetDeployed.invoke(mgr)) {
            if (!(Boolean) o0IsFighterWing.invoke(o0)) {
                continue;
            }
            Object wing = o0GetWingIfFighter.invoke(o0);
            if (wing == null) {
                continue;
            }
            if (mGetSourceShip.invoke(wing) == ship && mGetSource.invoke(wing) != null) {
                hudMatch++;
                int sz = ((List<?>) mGetMembers.invoke(wing)).size();
                if (sz != 0) {
                    allEmpty = false;
                }
            }
        }
        for (Object wing : wings) {
            Object dfm = mgrGetDeployedFleetMemberM.invoke(mgr, wing);
            if (dfm == null || o0GetMember.invoke(dfm) == null) {
                allIconPre = false;
            }
        }
        row("hud_filter_count_eq_" + EXPECTED_BAYS, hudMatch == EXPECTED_BAYS, tally);
        row("wings_empty_at_register", allEmpty && wings.size() == EXPECTED_BAYS, tally);
        row("icon_ctor_precondition_member_nonnull", allIconPre && wings.size() == EXPECTED_BAYS, tally);

        // ---- (4) Pips law: mint ONE fighter replica, add/step/remove; never setWing. ----
        Object wing0 = wings.isEmpty() ? null : wings.get(0);
        boolean fighterWingNull = true;
        boolean addOk = false;
        boolean stepOk = false;
        boolean removeOk = false;
        if (wing0 != null) {
            Object wingSpec = mGetSpec.invoke(wing0);
            String fVariantId = (String) wingSpec.getClass().getMethod("getVariantId").invoke(wingSpec);
            System.out.println(P + " wing0_spec_variant=" + fVariantId);
            Object fVariant = registryResolve.invoke(null, fVariantId);
            if (fVariant == null || !hullVariantClass.isInstance(fVariant)) {
                row("replica_variant_resolved", false, tally);
            } else {
                Object fMember = mintMember(fleetMemberClass, memberTypeClass, hullVariantClass, fVariant, owner, "CC Fighter");
                Object fighter;
                try {
                    fighter = mgrSpawnFleetMember.invoke(mgr, fMember, null, 180.0f, 4.0f);
                } catch (InvocationTargetException e) {
                    bail("REPLICA_SPAWN_THREW", e);
                    return;
                }
                step.step(fixedDt, tick++);
                boolean replicaAlive = (Boolean) isShipAlive.invoke(engine, fighter);
                shipSetShipAI.invoke(fighter, new Object[] {null});
                if (shipGetWing.invoke(fighter) != null) {
                    fighterWingNull = false;
                }
                row("replica_spawned_alive", replicaAlive, tally);

                mAddMember.invoke(wing0, fighter);
                addOk = ((List<?>) mGetMembers.invoke(wing0)).size() == 1;
                if (shipGetWing.invoke(fighter) != null) {
                    fighterWingNull = false;
                }
                for (int f = 0; f < 30; f++) {
                    step.step(fixedDt, tick++);
                }
                stepOk = ((List<?>) mGetMembers.invoke(wing0)).size() == 1
                        && (Boolean) isShipAlive.invoke(engine, ship);
                if (shipGetWing.invoke(fighter) != null) {
                    fighterWingNull = false;
                }
                mRemoveMember.invoke(wing0, fighter);
                removeOk = ((List<?>) mGetMembers.invoke(wing0)).size() == 0;
                if (shipGetWing.invoke(fighter) != null) {
                    fighterWingNull = false;
                }
                row("addMember_size_eq_1", addOk, tally);
                row("after30frames_size_eq_1_carrier_alive", stepOk, tally);
                row("removeMember_size_eq_0", removeOk, tally);
                row("fighter_getWing_null_throughout", fighterWingNull, tally);
            }
        } else {
            row("wing0_present", false, tally);
        }

        // ---- (5) Rate law: wing-less engine bays count as 1.0; bay has no wing setter.
        float sharedRate = ((Number) shipGetSharedRate.invoke(ship)).floatValue();
        Object bay0Wing = bays.get(0).getClass().getMethod("getWing").invoke(bays.get(0));
        row("shared_rate_eq_1.0", Math.abs(sharedRate - 1.0f) < 1.0e-3f, tally);
        row("bay0_getWing_null", bay0Wing == null, tally);

        boolean pass = tally[1] == 0;
        String verdict = pass ? "WING_REGISTERED" : "FAILED";
        System.out.println(String.format(Locale.ROOT,
                P + "-end verdict=%s rows_ok=%d rows_fail=%d bays=%d hud_match=%d shared_rate=%.3f"
                        + " bay0_wing_null=%b fighter_getWing_null=%b fixed_dt=%s",
                verdict, tally[0], tally[1], bays.size(), hudMatch, sharedRate,
                bay0Wing == null, fighterWingNull, fixedDt));
        System.exit(pass ? 0 : 1);
    }

    private static Object mintMember(Class<?> fleetMemberClass, Class<?> memberTypeClass,
            Class<?> hullVariantClass, Object variant, int owner, String name) throws Exception {
        Object member = fleetMemberClass
                .getConstructor(int.class, hullVariantClass, memberTypeClass)
                .newInstance(owner, variant, memberTypeClass.getField("SHIP").get(null));
        fleetMemberClass.getMethod("setShipName", String.class).invoke(member, name);
        Object crewComp = fleetMemberClass.getMethod("getCrewComposition").invoke(member);
        float minCrew = ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(member)).floatValue();
        crewComp.getClass().getMethod("setCrew", float.class).invoke(crewComp, Math.max(minCrew, 1.0f));
        return member;
    }

    private static Method findOneArg(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("no one-arg " + name + " on " + type.getName());
    }

    /** Boundary capture: the throwing edge is the finding, not an infra failure. */
    private static void bail(String verdict, InvocationTargetException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        System.out.println(P + "-end verdict=" + verdict
                + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
        cause.printStackTrace(System.out);
        System.exit(2);
    }
}
