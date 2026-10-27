package cc.starsector.server.bridge;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import cc.starsector.server.runtime.AwsdSnapshotCodec;

/**
 * The server half of the side-totals mirror: read, per side, the SAME
 * roster + objective sums the three vanilla side-dynamic scripts derive every frame
 * ({@code CoordinatedManeuversScript} / {@code ElectronicWarfareScript} / {@code CommRelayScript},
 * mirrored verbatim), so the AWSD v9 trailing block can carry the RAW totals a
 * client's partial replica roster cannot derive (the desync class: server Σpct=31 vs
 * client-local 17.065 on maxSpeed).
 *
 * <p><b>Faithfulness laws (vanilla-verbatim, do not "fix"):</b> the commander maxes init at 0 — a
 * side with NO fleet commanders has max 0, which downstream gates every apply/HUD off (vanilla
 * {@code max <= 0 ⇒ cleanUpIfNeeded}); coord/EW sums skip fighter wings AND station modules while
 * the CP sum skips fighter wings ONLY; EW total/max are {@code (int)}-TRUNCATED before the centi
 * scale (vanilla {@code getTotalAndMaximum} returns {@code int[]}); CP is 0 when
 * {@code getFleetCommander()} is null (vanilla unmodifies — net-identical to a zero flat). The one
 * deliberate deviation: vanilla dereferences {@code member.getShip()} unguarded in its sum loops;
 * this capture skips a null ship fail-soft.
 *
 * <p><b>Boundary + threading:</b> reflection-only against PUBLIC API interfaces resolved by string
 * via the engine object's own classloader ({@link cc.starsector.server.runtime.ParityAuditWalker}
 * idiom: per-classloader handle cache, log-once, READ-only, never throws out) — compiles with no
 * game jars, touches no non-API engine identity. Serve-thread only, called inside the snapshot capture
 * so totals and snapshot are the same read instant. Any failure returns null and the caller holds
 * its last block ({@code EngineBackedLiveWorldSnapshotSource} pre-first = {@code ZERO}).
 */
public final class SideTotalsCapture {

    private static final String ENGINE_IFACE = "com.fs.starfarer.api.combat.CombatEngineAPI";
    private static final String MANAGER_IFACE = "com.fs.starfarer.api.combat.CombatFleetManagerAPI";
    private static final String DFM_IFACE = "com.fs.starfarer.api.combat.DeployedFleetMemberAPI";
    private static final String SHIP_IFACE = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIP_STATS_IFACE = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String DYNAMIC_IFACE = "com.fs.starfarer.api.util.DynamicStatsAPI";
    private static final String PERSON_IFACE = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String CHAR_STATS_IFACE =
            "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String OBJECTIVE_IFACE = "com.fs.starfarer.api.combat.BattleObjectiveAPI";

    /** Values pin their classloader (engine + fixtures process-wide) — walker-cache precedent. */
    private static final ConcurrentHashMap<ClassLoader, Handles> HANDLE_CACHE =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean UNAVAILABLE_LOGGED = new AtomicBoolean(false);

    private SideTotalsCapture() { }

    /** The resolved public-API method handles for one classloader; construction throws ⇒ no cache. */
    private static final class Handles {
        final Method engineGetFleetManager;
        final Method engineGetObjectives;
        final Method managerGetDeployedCopyDFM;
        final Method managerGetAllFleetCommanders;
        final Method managerGetFleetCommander;
        final Method managerGetOwner;
        final Method dfmIsFighterWing;
        final Method dfmIsStationModule;
        final Method dfmGetShip;
        final Method shipGetMutableStats;
        final Method shipStatsGetDynamic;
        final Method dynamicGetValue;
        final Method personGetStats;
        final Method charStatsGetDynamic;
        final Method objectiveGetOwner;
        final Method objectiveGetType;
        // The side's deployment-point pool (vanilla fabricator bound).
        final Method managerGetMaxStrength;
        final Method managerGetCurrStrength;

        Handles(ClassLoader cl) throws ReflectiveOperationException {
            Class<?> engine = Class.forName(ENGINE_IFACE, false, cl);
            Class<?> manager = Class.forName(MANAGER_IFACE, false, cl);
            Class<?> dfm = Class.forName(DFM_IFACE, false, cl);
            Class<?> ship = Class.forName(SHIP_IFACE, false, cl);
            Class<?> shipStats = Class.forName(SHIP_STATS_IFACE, false, cl);
            Class<?> dynamic = Class.forName(DYNAMIC_IFACE, false, cl);
            Class<?> person = Class.forName(PERSON_IFACE, false, cl);
            Class<?> charStats = Class.forName(CHAR_STATS_IFACE, false, cl);
            Class<?> objective = Class.forName(OBJECTIVE_IFACE, false, cl);
            engineGetFleetManager = engine.getMethod("getFleetManager", int.class);
            engineGetObjectives = engine.getMethod("getObjectives");
            managerGetDeployedCopyDFM = manager.getMethod("getDeployedCopyDFM");
            managerGetAllFleetCommanders = manager.getMethod("getAllFleetCommanders");
            managerGetFleetCommander = manager.getMethod("getFleetCommander");
            managerGetOwner = manager.getMethod("getOwner");
            dfmIsFighterWing = dfm.getMethod("isFighterWing");
            dfmIsStationModule = dfm.getMethod("isStationModule");
            dfmGetShip = dfm.getMethod("getShip");
            shipGetMutableStats = ship.getMethod("getMutableStats");
            shipStatsGetDynamic = shipStats.getMethod("getDynamic");
            dynamicGetValue = dynamic.getMethod("getValue", String.class, float.class);
            personGetStats = person.getMethod("getStats");
            charStatsGetDynamic = charStats.getMethod("getDynamic");
            objectiveGetOwner = objective.getMethod("getOwner");
            objectiveGetType = objective.getMethod("getType");
            managerGetMaxStrength = manager.getMethod("getMaxStrength");
            managerGetCurrStrength = manager.getMethod("getCurrStrength");
        }
    }

    /**
     * D-FABRATE total-count check: one side's deployment-point pool as
     * {@code {maxStrength, currStrength}} (public {@code CombatFleetManagerAPI} reads), or null
     * when unresolvable. Vanilla bounds a Threat fabricator's TOTAL output by
     * {@code dpLeft = max - curr >= 100} ({@code ConstructionSwarmSystemScript.enoughDP}); the
     * server never sets the pool (no {@code setMaxFleetPoints} call — recon),
     * so this row measures what the headless engine
     * minted: 100 = the {@code CombatFleetManager} field default, ~240 = vanilla's sim
     * default (battleSize 400 × 0.6). Never throws; not cached beyond the handle table.
     */
    public static int[] strengthPoolOrNull(Object engine, int side) {
        if (engine == null) {
            return null;
        }
        try {
            Handles h = handlesFor(engine.getClass().getClassLoader());
            if (h == null) {
                return null;
            }
            Object manager = h.engineGetFleetManager.invoke(engine, side);
            if (manager == null) {
                return null;
            }
            Object max = h.managerGetMaxStrength.invoke(manager);
            Object curr = h.managerGetCurrStrength.invoke(manager);
            if (!(max instanceof Number) || !(curr instanceof Number)) {
                return null;
            }
            return new int[] {((Number) max).intValue(), ((Number) curr).intValue()};
        } catch (Throwable t) {
            logUnavailableOnce("strength_pool_failed", t);
            return null;
        }
    }

    /**
     * Capture both sides' totals off the live engine, or null when anything is unresolvable or a
     * read fails (caller holds last). Never throws.
     */
    public static AwsdSnapshotCodec.SideTotalsBlock captureOrNull(Object engine) {
        if (engine == null) {
            return null;
        }
        try {
            Handles h = handlesFor(engine.getClass().getClassLoader());
            if (h == null) {
                return null;
            }
            Object objectives = h.engineGetObjectives.invoke(engine);
            List<?> objectiveList = objectives instanceof List ? (List<?>) objectives : List.of();
            AwsdSnapshotCodec.SideTotals side0 = captureSide(h, engine, objectiveList, 0);
            AwsdSnapshotCodec.SideTotals side1 = captureSide(h, engine, objectiveList, 1);
            if (side0 == null || side1 == null) {
                return null;
            }
            return new AwsdSnapshotCodec.SideTotalsBlock(side0, side1);
        } catch (Throwable t) {
            logUnavailableOnce("capture_failed", t);
            return null;
        }
    }

    private static AwsdSnapshotCodec.SideTotals captureSide(
            Handles h, Object engine, List<?> objectives, int side)
            throws ReflectiveOperationException {
        Object manager = h.engineGetFleetManager.invoke(engine, side);
        if (manager == null) {
            return null;
        }
        Object ownerValue = h.managerGetOwner.invoke(manager);
        if (!(ownerValue instanceof Integer)) {
            return null;
        }
        int owner = (Integer) ownerValue;

        int buoys = 0;
        int sensors = 0;
        int relays = 0;
        for (Object obj : objectives) {
            if (obj == null) {
                continue;
            }
            Object objOwner = h.objectiveGetOwner.invoke(obj);
            if (!(objOwner instanceof Integer) || (Integer) objOwner != owner) {
                continue;
            }
            Object type = h.objectiveGetType.invoke(obj);
            if ("nav_buoy".equals(type)) {
                buoys++;
            } else if ("sensor_array".equals(type)) {
                sensors++;
            } else if ("comm_relay".equals(type)) {
                relays++;
            }
        }

        float coordFlat = 0.0F;
        float ewFlat = 0.0F;
        float cpFlat = 0.0F;
        Object deployedValue = h.managerGetDeployedCopyDFM.invoke(manager);
        List<?> deployed = deployedValue instanceof List ? (List<?>) deployedValue : List.of();
        for (Object member : deployed) {
            if (member == null) {
                continue;
            }
            Object fighter = h.dfmIsFighterWing.invoke(member);
            if (!(fighter instanceof Boolean) || (Boolean) fighter) {
                continue;
            }
            Object ship = h.dfmGetShip.invoke(member);
            if (ship == null) {
                continue; // vanilla dereferences unguarded; ours skips fail-soft
            }
            Object dynamic = h.shipStatsGetDynamic.invoke(h.shipGetMutableStats.invoke(ship));
            if (dynamic == null) {
                continue;
            }
            Object station = h.dfmIsStationModule.invoke(member);
            boolean stationModule = station instanceof Boolean && (Boolean) station;
            if (!stationModule) {
                coordFlat += dynValue(h, dynamic, "coord_maneuvers_flat");
                ewFlat += dynValue(h, dynamic, "electronic_warfare_flat");
            }
            // CP sum skips fighter wings ONLY — station modules count (vanilla CommRelayScript)
            cpFlat += dynValue(h, dynamic, "command_point_rate_flat");
        }

        // Commander maxes init 0 (vanilla): no commanders ⇒ max 0 ⇒ every downstream apply gates off
        float coordMax = 0.0F;
        float ewMax = 0.0F;
        Object commandersValue = h.managerGetAllFleetCommanders.invoke(manager);
        List<?> commanders = commandersValue instanceof List ? (List<?>) commandersValue : List.of();
        for (Object commander : commanders) {
            if (commander == null) {
                continue;
            }
            Object dynamic = h.charStatsGetDynamic.invoke(h.personGetStats.invoke(commander));
            if (dynamic == null) {
                continue;
            }
            coordMax = Math.max(coordMax, 20.0F + dynValue(h, dynamic, "coord_maneuvers_max"));
            ewMax = Math.max(ewMax, 10.0F + dynValue(h, dynamic, "electronic_warfare_max"));
        }

        float coordTotal = coordFlat + 5.0F * buoys;
        float ewTotal = ewFlat + 5.0F * sensors;
        float cpRate = h.managerGetFleetCommander.invoke(manager) == null
                ? 0.0F : cpFlat + 0.25F * relays;

        // Wire law (facts): coord/cp centi = round(×100); EW centi = (int)value × 100 (vanilla's
        // int[] truncation happens BEFORE the scale); saturate to the u16 carriage.
        return new AwsdSnapshotCodec.SideTotals(
                centiRound(coordTotal), centiRound(coordMax),
                centiTruncate(ewTotal), centiTruncate(ewMax),
                centiRound(cpRate));
    }

    private static float dynValue(Handles h, Object dynamic, String id)
            throws ReflectiveOperationException {
        Object v = h.dynamicGetValue.invoke(dynamic, id, 0.0F);
        return v instanceof Number ? ((Number) v).floatValue() : 0.0F;
    }

    private static int centiRound(float value) {
        return saturateU16(Math.round(value * 100.0F));
    }

    private static int centiTruncate(float value) {
        return saturateU16(((int) value) * 100);
    }

    private static int saturateU16(int centi) {
        return centi < 0 ? 0 : Math.min(centi, 0xFFFF);
    }

    private static Handles handlesFor(ClassLoader cl) {
        if (cl == null) {
            return null;
        }
        Handles cached = HANDLE_CACHE.get(cl);
        if (cached != null) {
            return cached;
        }
        try {
            Handles built = new Handles(cl);
            HANDLE_CACHE.put(cl, built);
            return built;
        } catch (Throwable t) {
            logUnavailableOnce("resolve_failed", t);
            return null; // not cached — same retry-per-call behavior as the walker
        }
    }

    private static void logUnavailableOnce(String stage, Throwable t) {
        if (UNAVAILABLE_LOGGED.compareAndSet(false, true)) {
            System.out.println("cc-sidetotals capture_unavailable stage=" + stage
                    + " cause=" + t.getClass().getSimpleName());
        }
    }
}
