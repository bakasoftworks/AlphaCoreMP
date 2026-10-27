package cc.starsector.server.bridge;

import cc.starsector.server.runtime.AdminSeatDebugLane;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * The reflective engine port behind {@link AdminSeatDebugLane.HullPort} — the
 * server's adapter-boundary law (adapter boundaries over non-API engine identities; the
 * product pass compiles with NO engine/API jar, so every hull touch is {@code getMethod} on the
 * live object's class, the {@code FinaleCreepAuthor}/{@code CrDeploySeam} shape). Public API
 * members only ({@code ShipAPI}/{@code CombatEntityAPI}/{@code MutableShipStatsAPI}/
 * {@code MutableStat}/LWJGL {@code Vector2f}) — no engine-internal names. Fail-soft: any throw logs one
 * {@code admin_debug_port_error} row and reads as {@code false} (an admin debug verb must never
 * kill a serve with players on).
 *
 * <p>God mode = the five damage-taken mults under {@link AdminSeatDebugLane#GODMODE_STAT_ID}:
 * hull at {@link AdminSeatDebugLane#GODMODE_HULL_DAMAGE_TAKEN_MULT} (a ZERO
 * hull mult reads {@code isHullDamageable()} false and therefore {@code isTargetable()} false,
 * so foes stop shooting), the other four at exactly 0 (provenance in
 * the lane's javadoc: the engine's own {@code core_disable} idiom).
 *
 * <p>Reflective {@link Method} handles are CACHED per (class, name) at the
 * first touch of a class, so the per-tick hull top-up (per god seat per tick — the frame-loop
 * residency law) is three map reads, not three {@code getMethod} walks; a toggle / respawn
 * pays the lookups once per new class (one hull class, one stats class, one stat class, one
 * vector class in practice). SIM THREAD ONLY like the lane: a plain map, no lock.
 */
public final class AdminSeatDebugEnginePort implements AdminSeatDebugLane.HullPort {

    /** The hull getter — the ONE stat that rides the non-zero F3-1 mult. */
    private static final String HULL_DAMAGE_TAKEN_GETTER = "getHullDamageTakenMult";

    private static final String[] DAMAGE_TAKEN_STATS = {
        HULL_DAMAGE_TAKEN_GETTER, "getArmorDamageTakenMult", "getEmpDamageTakenMult",
        "getEngineDamageTakenMult", "getWeaponDamageTakenMult"};

    /** F3-4: resolved handles by target class then bare method name (every name used here has
     * exactly one arity on the API types, so the name alone keys it). */
    private final Map<Class<?>, Map<String, Method>> methodsByClass =
            new HashMap<Class<?>, Map<String, Method>>();

    @Override
    public boolean applyGodMode(Object hull) {
        try {
            Object stats = method(hull, "getMutableStats").invoke(hull);
            for (String getter : DAMAGE_TAKEN_STATS) {
                Object stat = method(stats, getter).invoke(stats);
                float mult = HULL_DAMAGE_TAKEN_GETTER.equals(getter)
                        ? AdminSeatDebugLane.GODMODE_HULL_DAMAGE_TAKEN_MULT : 0f;
                method(stat, "modifyMult", String.class, float.class)
                        .invoke(stat, AdminSeatDebugLane.GODMODE_STAT_ID, mult);
            }
            return true;
        } catch (Throwable t) {
            logError("apply_god_mode", t);
            return false;
        }
    }

    @Override
    public void releaseGodMode(Object hull) {
        try {
            Object stats = method(hull, "getMutableStats").invoke(hull);
            for (String getter : DAMAGE_TAKEN_STATS) {
                Object stat = method(stats, getter).invoke(stats);
                method(stat, "unmodify", String.class)
                        .invoke(stat, AdminSeatDebugLane.GODMODE_STAT_ID);
            }
        } catch (Throwable t) {
            logError("release_god_mode", t);
        }
    }

    @Override
    public float topUpHull(Object hull) {
        try {
            float hp = ((Number) method(hull, "getHitpoints").invoke(hull)).floatValue();
            float max = ((Number) method(hull, "getMaxHitpoints").invoke(hull)).floatValue();
            if (hp < max) {
                method(hull, "setHitpoints", float.class).invoke(hull, max);
                return max - hp; // The restored deficit (the lane meters it)
            }
        } catch (Throwable t) {
            logError("top_up_hull", t);
        }
        return 0f;
    }

    @Override
    public boolean setPose(Object hull, float x, float y) {
        try {
            Object loc = method(hull, "getLocation").invoke(hull);
            method(loc, "set", float.class, float.class).invoke(loc, x, y);
            Object vel = method(hull, "getVelocity").invoke(hull);
            method(vel, "set", float.class, float.class).invoke(vel, 0f, 0f);
            return true;
        } catch (Throwable t) {
            logError("set_pose", t);
            return false;
        }
    }

    /** F3-4 contract-row reads: classes with a resolved handle set / handles resolved. */
    public int cachedClassCount() {
        return methodsByClass.size();
    }

    public int cachedMethodCount() {
        int n = 0;
        for (Map<String, Method> byName : methodsByClass.values()) {
            n += byName.size();
        }
        return n;
    }

    /** The cached {@code getMethod}: one lookup per (class, name) for the port's lifetime. */
    private Method method(Object target, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> type = target.getClass();
        Map<String, Method> byName = methodsByClass.get(type);
        if (byName == null) {
            byName = new HashMap<String, Method>();
            methodsByClass.put(type, byName);
        }
        Method method = byName.get(name);
        if (method == null) {
            method = type.getMethod(name, params);
            byName.put(name, method);
        }
        return method;
    }

    private static void logError(String op, Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        System.out.println("npeer-serve admin_debug_port_error op=" + op + " err="
                + cause.getClass().getName() + ": " + cause.getMessage());
    }
}
