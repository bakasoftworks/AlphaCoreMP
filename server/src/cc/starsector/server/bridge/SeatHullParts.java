package cc.starsector.server.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ONE walk over "a seat hull and everything that fights as part of it" — child
 * MODULES (recursive), every wing's launched FIGHTERS, the fighters flying HOME, and deployed
 * DRONES. Each is an independent engine {@code Ship} with its own owner, so every consumer that
 * treats a player seat as one body needs the same set: the live team flip
 * ({@link SeatTeamEnginePort}), the wave-clear count ({@link EngineCombatBinding
 * #shipAllegiancesOrNull(int)}) and the operator wave-skip sweep ({@link SeatRebind
 * #forceKillSideHulls}).
 *
 * <p>Reflective like the rest of the bridge (the product pass compiles with no engine/API jar):
 * {@code getMethod} on the live object's class, cached per class. Every name below is public
 * {@code ShipAPI} / {@code FighterWingAPI} surface. RETURNING fighters are a separate list by
 * the engine's own design — {@code FighterWingAPI.orderReturn} REMOVES the fighter from
 * {@code getWingMembers()} and parks it in {@code getReturning()} as a {@code ReturningFighter}
 * whose public {@code fighter} field holds the ship
 * — so a members-only walk loses every fighter on
 * its way back to the bay.
 */
final class SeatHullParts {

    private static final Map<Class<?>, Map<String, Method>> METHODS =
            new ConcurrentHashMap<Class<?>, Map<String, Method>>();
    private static volatile boolean partsErrorLogged;

    private SeatHullParts() {}

    /**
     * Adds {@code hull}'s parts to {@code out} (never {@code hull} itself; identity-deduplicated
     * against what {@code out} already holds when it is an identity set, by {@code contains}
     * otherwise). Throws on a reflective failure — the caller owns the fail-soft policy.
     */
    static void collect(Object hull, Collection<Object> out) throws Exception {
        Object modules = method(hull, "getChildModulesCopy").invoke(hull);
        if (modules instanceof List<?>) {
            for (Object module : (List<?>) modules) {
                if (module != null && !out.contains(module)) {
                    out.add(module);
                    collect(module, out);
                }
            }
        }
        Object wings = method(hull, "getAllWings").invoke(hull);
        if (wings instanceof List<?>) {
            for (Object wing : (List<?>) wings) {
                if (wing == null) {
                    continue;
                }
                Object members = method(wing, "getWingMembers").invoke(wing);
                if (members instanceof List<?>) {
                    for (Object fighter : (List<?>) members) {
                        if (fighter != null && !out.contains(fighter)) {
                            out.add(fighter);
                        }
                    }
                }
                Object returning = method(wing, "getReturning").invoke(wing);
                if (returning instanceof List<?>) {
                    for (Object entry : (List<?>) returning) {
                        if (entry == null) {
                            continue;
                        }
                        Field fighterField = entry.getClass().getField("fighter");
                        Object fighter = fighterField.get(entry);
                        if (fighter != null && !out.contains(fighter)) {
                            out.add(fighter);
                        }
                    }
                }
            }
        }
        Object drones = method(hull, "getDeployedDrones").invoke(hull);
        if (drones instanceof List<?>) {
            for (Object drone : (List<?>) drones) {
                if (drone != null && !out.contains(drone)) {
                    out.add(drone);
                }
            }
        }
    }

    /**
     * The identity set of every live SEAT hull whose engine owner reads {@code side}, plus its
     * parts — the ships a side-keyed sweep or count must treat as "a player, not a wave ship".
     * The shared EMPTY set (no allocation, no parts walk) when no seat hull reads that side: the
     * every-tick common case costs one {@code getOwner} per mounted seat (the frame-residency
     * law — the wave-clear feed calls this per capture).
     *
     * <p>Fail-soft per hull: a hull whose owner read fails is skipped; a hull whose PARTS walk
     * fails still rides the set alone (one {@code seat_hull_parts_error} row per process — a
     * stalled fighter count is the lesser failure than dropping the whole allegiance tier).
     */
    static Set<Object> seatOwnedOnSide(int side) {
        Set<Object> owned = null;
        synchronized (RealEngineDemoRoster.class) {
            for (Object hull : RealEngineDemoRoster.liveShipByWireId.values()) {
                if (hull == null) {
                    continue;
                }
                try {
                    Object owner = method(hull, "getOwner").invoke(hull);
                    if (!(owner instanceof Number) || ((Number) owner).intValue() != side) {
                        continue;
                    }
                } catch (Exception | LinkageError unreadable) {
                    continue;
                }
                if (owned == null) {
                    owned = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                }
                owned.add(hull);
                try {
                    collect(hull, owned);
                } catch (Exception | LinkageError partsFailure) {
                    if (!partsErrorLogged) {
                        partsErrorLogged = true;
                        System.out.println("npeer-serve seat_hull_parts_error err="
                                + partsFailure.getClass().getName() + ": "
                                + partsFailure.getMessage());
                    }
                }
            }
        }
        return owned == null ? Collections.<Object>emptySet() : owned;
    }

    /** The cached {@code getMethod}: one lookup per (class, no-arg name) for the process. */
    private static Method method(Object target, String name) throws NoSuchMethodException {
        Class<?> type = target.getClass();
        Map<String, Method> byName = METHODS.get(type);
        if (byName == null) {
            byName = new ConcurrentHashMap<String, Method>();
            Map<String, Method> raced = METHODS.putIfAbsent(type, byName);
            if (raced != null) {
                byName = raced;
            }
        }
        Method method = byName.get(name);
        if (method == null) {
            method = type.getMethod(name);
            byName.put(name, method);
        }
        return method;
    }
}
