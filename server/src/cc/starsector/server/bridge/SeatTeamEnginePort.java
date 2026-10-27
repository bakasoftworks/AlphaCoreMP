package cc.starsector.server.bridge;

import cc.starsector.server.runtime.SeatTeamLane;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reflective engine port behind {@link SeatTeamLane.HullPort} — the
 * {@link AdminSeatDebugEnginePort} shape (the product pass compiles with NO engine/API jar, so
 * every hull touch is {@code getMethod} on the live object's class; handles cached per class).
 * Fail-soft: any throw logs one {@code seat_team_port_error} row and reads as {@code false} (an
 * admin debug verb must never kill a serve with players on). SIM THREAD ONLY like the lane.
 *
 * <p><b>The live flip is OUR multi-step operation — never a bare {@code setOwner}</b>
 * (the engine's {@code Ship.setOwner} writes
 * {@code originalOwner} only while it is −1, so on a live hull it leaves {@code originalOwner}
 * stale, and the fleet-member / assault / task-manager lookups key on THAT). No vanilla path
 * flips a live ship's side, so there is no engine recipe to copy; the steps below are the
 * engine's own public setters applied to every entity that caches the side:
 * <ol>
 * <li>the hull: {@code setOwner} + {@code setOriginalOwner};</li>
 * <li>its child MODULES, launched FIGHTERS (wing members AND the ones flying home) and deployed
 * DRONES — each an independent {@code Ship} with its own cached owner pair (the ONE walk =
 * {@link SeatHullParts#collect});</li>
 * <li>the OLD side's deployed set ({@link #leaveOldSideDeployedSet} — the engine's own
 * {@code removeDeployed(ship, false)}, without which the hull's death is never booked);</li>
 * <li>its {@code FleetMember} owner (else {@code Ship.getFleetMember} re-stamps the old side);</li>
 * <li>the NEW side's deployed set ({@link #joinNewSideDeployedSet} — the spawn path's own
 * registration; without it the hull sits in NEITHER set, so vanilla's side scripts never
 * rewrite its coord/EW bonus and the wire side-totals skip its flat — a test run's measured
 * 358-vs-374 speed gap);</li>
 * <li>stale AI TARGETS: the engine re-validates a cached target only on hulk/expired/piece,
 * so the flipped hull's own target and every ship still
 * holding the flipped hull (or one of its parts) as its target are dropped — the next AI pass
 * re-picks by the LIVE owner.</li>
 * </ol>
 * In-flight ordnance keeps its fire-time side — transient and self-clearing;
 * retargeting it would be
 * the unfaithful move.
 */
public final class SeatTeamEnginePort implements SeatTeamLane.HullPort {

    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";

    private final Map<Class<?>, Map<String, Method>> methodsByClass =
            new HashMap<Class<?>, Map<String, Method>>();
    /** The last flip's old-side DP accounting, written by {@link #leaveOldSideDeployedSet} and
     * printed on the {@code seat_team_flip} row (sim thread only, like the whole port). */
    private int dpOldBefore = -1;
    private int dpOldAfter = -1;
    private int dpLeft;
    /** The last flip's NEW-side accounting, written by
     * {@link #joinNewSideDeployedSet}: {@code joined} = 1 when the fresh entry reads back through
     * the new manager's own {@code getDeployedFleetMember(hull)}, and the dp pair is that
     * manager's {@code getCurrStrength()} around the add (the rig asserts after − before ==
     * dp_left: the DP that left the old side arrived on the new one). */
    private int joined;
    private int dpNewBefore = -1;
    private int dpNewAfter = -1;
    /** The engine's two deployed ledgers, resolved ONCE per JVM by TYPE + contents against the
     * public copies (never by field name, {@link #resolveLedgerFields}): the
     * {@code Set} whose iteration order equals {@code getDeployedCopyDFM()} and the {@code List}
     * whose contents equal {@code getAllEverDeployedCopy()}. Resolution needs a manager with a
     * NON-empty deployed set (an empty one is content-equal to the death ledger, the other
     * DFM-typed set), so it is attempted on the old manager before the eviction too. */
    private static Field deployedSetField;
    private static Field everDeployedListField;

    @Override
    public int ownerOf(Object hull) {
        try {
            if (Boolean.TRUE.equals(method(hull, "isHulk").invoke(hull))
                    || !Boolean.TRUE.equals(method(hull, "isAlive").invoke(hull))) {
                return -1;
            }
            // A seat can be MOUNTED on something that is not a free-standing ship: a station
            // module (the module-seat lane) or a /transfer shuttle pod mid-flight. Flipping a
            // module away from its station — or a pod away from the hull it is about to dock
            // with — would tear one body across two teams, so neither is ever a flip target;
            // the lane degrades to the respawn toggle and says so.
            if (Boolean.TRUE.equals(method(hull, "isStationModule").invoke(hull))
                    || Boolean.TRUE.equals(method(hull, "isShuttlePod").invoke(hull))
                    || method(hull, "getParentStation").invoke(hull) != null) {
                return -1;
            }
            int owner = ((Number) method(hull, "getOwner").invoke(hull)).intValue();
            return owner == 0 || owner == 1 ? owner : -1;
        } catch (Throwable t) {
            logError("owner_of", t);
            return -1;
        }
    }

    @Override
    public boolean flipOwner(Object hull, int newOwner) {
        if (newOwner != 0 && newOwner != 1) {
            return false;
        }
        try {
            List<Object> parts = new java.util.ArrayList<Object>();
            parts.add(hull);
            SeatHullParts.collect(hull, parts);
            int oldOwner = ((Number) method(hull, "getOwner").invoke(hull)).intValue();
            for (Object part : parts) {
                method(part, "setOwner", int.class).invoke(part, newOwner);
                method(part, "setOriginalOwner", int.class).invoke(part, newOwner);
            }
            int undeployed = leaveOldSideDeployedSet(parts, oldOwner);
            // REPLACEMENT fighters read the WING's owner at launch, not the carrier's live one
            // (the engine's getWingOwner()) —
            // flip every wing too. The setter lives on the engine's internal wing class, so it is resolved by
            // NAME on the live object and skipped (logged) when absent.
            int wingsFlipped = flipWingOwners(hull, newOwner);
            // a player seat is never an "ally" fleet member on either side; this also re-pins
            // the cached computedAlly value
            method(hull, "setAlly", boolean.class).invoke(hull, false);
            Object member = method(hull, "getFleetMember").invoke(hull);
            if (member != null) {
                method(member, "setOwner", int.class).invoke(member, newOwner);
            }
            // The NEW side's deployed set gets the hull's entry AFTER the member owner
            // write (the entry's isAlly() reads the member) — the inverse of the eviction above.
            joinNewSideDeployedSet(hull, member, newOwner);
            int dropped = dropStaleTargets(parts);
            // The flip proves ITSELF on the row — verified= is a read-back of
            // owner AND originalOwner on every part (== parts when the engine took the flip),
            // and the dp_* trio is the old manager's own accounting around the all-parts
            // removeDeployed (the headless rig asserts before - after == left).
            int verified = 0;
            for (Object part : parts) {
                if (((Number) method(part, "getOwner").invoke(part)).intValue() == newOwner
                        && ((Number) method(part, "getOriginalOwner").invoke(part)).intValue()
                                == newOwner) {
                    verified++;
                }
            }
            System.out.println("npeer-serve seat_team_flip new_owner=" + newOwner
                    + " parts=" + parts.size() + " wings=" + wingsFlipped
                    + " undeployed=" + undeployed + " targets_dropped=" + dropped
                    + " verified=" + verified + " dp_old_before=" + dpOldBefore
                    + " dp_old_after=" + dpOldAfter + " dp_left=" + dpLeft
                    + " joined=" + joined + " dp_new_before=" + dpNewBefore
                    + " dp_new_after=" + dpNewAfter);
            return ((Number) method(hull, "getOwner").invoke(hull)).intValue() == newOwner;
        } catch (Throwable t) {
            logError("flip_owner", t);
            return false;
        }
    }

    /**
     * Take the flipped parts out of the OLD side's deployed set with the
     * engine's own {@code CombatFleetManager.removeDeployed(ship, false)} — the very call its
     * death / disable listeners make. It is REQUIRED after a flip, not tidiness: those listeners
     * gate on {@code ship.getOriginalOwner() == manager.getOwner()},
     * so once {@code originalOwner} reads the new
     * side the old manager never hears the hull die and its entry would sit in the deployed set
     * — DP still counted — until the round resets. With {@code false} the call removes no
     * engine object: it subtracts the DP, drops the entry, un-assigns the member's control
     * group ({@code getGroupManager()}) and saves the member status.
     * The NEW side's entry is added by {@link #joinNewSideDeployedSet}
     * (before that registration existed the hull sat in NEITHER set — a stale coord bonus).
     * Returns how many entries actually left the set.
     */
    private int leaveOldSideDeployedSet(List<Object> parts, int oldOwner) throws Exception {
        dpOldBefore = -1;
        dpOldAfter = -1;
        dpLeft = 0;
        if (oldOwner != 0 && oldOwner != 1) {
            return 0;
        }
        Object engine = engineOrNull();
        if (engine == null) {
            return 0;
        }
        Object manager = engine.getClass().getMethod("getFleetManager", int.class)
                .invoke(engine, oldOwner);
        if (manager == null) {
            return 0;
        }
        dpOldBefore = currStrengthOrNeg(manager);
        resolveLedgerFields(manager); // While the hull's entry still makes the set non-empty
        int left = 0;
        for (Object part : parts) {
            Method lookup = oneArgMethodFor(manager, "getDeployedFleetMember", part);
            Method remove = null;
            for (Method m : manager.getClass().getMethods()) {
                if (m.getName().equals("removeDeployed") && m.getParameterCount() == 2
                        && m.getParameterTypes()[1] == boolean.class
                        && m.getParameterTypes()[0].isInstance(part)) {
                    remove = m;
                    break;
                }
            }
            if (remove == null) {
                continue;
            }
            Object entry = lookup == null ? null : lookup.invoke(manager, part);
            int entryDp = entry == null ? 0 : entryDpOrZero(entry);
            remove.invoke(manager, part, false);
            if (entry != null && lookup.invoke(manager, part) == null) {
                left++;
                dpLeft += entryDp;
            }
        }
        dpOldAfter = currStrengthOrNeg(manager);
        return left;
    }

    /**
     * Register the flipped hull in the NEW side's deployed set — the engine's
     * own registration, the inverse of {@code removeDeployed}: the spawn path
     * builds ONE {@code DeployedFleetMember} entry
     * ({@code O0(FleetMember, Ship)}) and adds it to BOTH ledgers — the deployed
     * set {@code removeDeployed} iterates and {@code getCurrStrength()} sums, and
     * the all-ever-deployed list behind {@code getDeployedFleetMemberEvenIfDisabled} — after
     * booking the DP through {@code setCurrStrength(getCurrStrength() + dp)}.
     * WHY it is required: vanilla's side scripts
     * ({@code CoordinatedManeuversScript.updateForSide}, {@code ElectronicWarfareScript},
     * {@code CommRelayScript}) and our {@code SideTotalsCapture} walk {@code getDeployedCopyDFM()}
     * — a hull in NEITHER set keeps its last-written coord bonus forever (8 vs the side's 2 = the
     * measured 358-vs-374 speed gap) and its flat leaves both wire totals. HULL only: modules,
     * fighters and drones are never deployed-set entries of their own (undeployed=1 of 20
     * parts). No build-renamable internal name is used: the ledgers resolve by type + contents
     * ({@link #resolveLedgerFields}), the entry class by its public {@code (FleetMember, Ship)}
     * constructor. Sets {@code joined} = 1 when the new manager's own
     * {@code getDeployedFleetMember(hull)} reads the fresh entry back; fail-soft otherwise (one
     * {@code seat_team_port_error op=join_*} row, the flip itself stands).
     */
    private void joinNewSideDeployedSet(Object hull, Object member, int newOwner)
            throws Exception {
        joined = 0;
        dpNewBefore = -1;
        dpNewAfter = -1;
        if (member == null) {
            return;
        }
        Object engine = engineOrNull();
        if (engine == null) {
            return;
        }
        Object manager = engine.getClass().getMethod("getFleetManager", int.class)
                .invoke(engine, newOwner);
        if (manager == null) {
            return;
        }
        Method lookup = oneArgMethodFor(manager, "getDeployedFleetMember", hull);
        if (lookup == null) {
            logError("join_lookup", new NoSuchMethodException("getDeployedFleetMember(Ship)"));
            return;
        }
        if (lookup.invoke(manager, hull) != null) {
            return; // already an entry of the new side's set: nothing to add
        }
        resolveLedgerFields(manager);
        if (deployedSetField == null) {
            logError("join_resolve", new IllegalStateException(
                    "deployed set unresolved (no non-empty deployed set seen yet)"));
            return;
        }
        Constructor<?> entryCtor = dfmConstructorOrNull(manager.getClass(), member, hull);
        if (entryCtor == null) {
            logError("join_entry", new NoSuchMethodException(
                    "DeployedFleetMember(FleetMember, Ship) on " + manager.getClass().getName()));
            return;
        }
        dpNewBefore = currStrengthOrNeg(manager);
        Object entry = entryCtor.newInstance(member, hull);
        int dp = entryDpOrZero(entry);
        // the spawn path's order: book the DP, add to both ledgers
        method(manager, "setCurrStrength", int.class).invoke(manager,
                ((Number) method(manager, "getCurrStrength").invoke(manager)).intValue() + dp);
        Object set = deployedSetField.get(manager);
        if (set instanceof Set<?>) {
            @SuppressWarnings("unchecked")
            Set<Object> deployed = (Set<Object>) set;
            deployed.add(entry);
        }
        Object list = everDeployedListField == null ? null : everDeployedListField.get(manager);
        if (list instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Object> ever = (List<Object>) list;
            ever.add(entry);
        }
        dpNewAfter = currStrengthOrNeg(manager);
        joined = lookup.invoke(manager, hull) == entry ? 1 : 0;
    }

    /** Resolve the two ledger fields ONCE from a manager whose deployed set is NON-empty: the
     * {@code Set}-typed instance field whose iteration order equals {@code getDeployedCopyDFM()}
     * (the death ledger is the other DFM-typed set — content-distinct from a non-empty deployed
     * set) and the {@code List}-typed field whose contents equal
     * {@code getAllEverDeployedCopy()}. An ambiguous match (two fields equal) leaves the field
     * null — the join then reports itself unresolved rather than guess. */
    private void resolveLedgerFields(Object manager) throws Exception {
        if (deployedSetField != null) {
            return;
        }
        Object copyValue = method(manager, "getDeployedCopyDFM").invoke(manager);
        if (!(copyValue instanceof List<?>) || ((List<?>) copyValue).isEmpty()) {
            return;
        }
        List<?> copy = (List<?>) copyValue;
        Object everValue = method(manager, "getAllEverDeployedCopy").invoke(manager);
        List<?> ever = everValue instanceof List<?> && !((List<?>) everValue).isEmpty()
                ? (List<?>) everValue : null;
        Field setMatch = null;
        Field listMatch = null;
        int setMatches = 0;
        int listMatches = 0;
        for (Field f : manager.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (Set.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object v = f.get(manager);
                if (v instanceof Set<?> && new ArrayList<Object>((Set<?>) v).equals(copy)) {
                    setMatch = f;
                    setMatches++;
                }
            } else if (ever != null && List.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object v = f.get(manager);
                if (v instanceof List<?> && v.equals(ever)) {
                    listMatch = f;
                    listMatches++;
                }
            }
        }
        if (setMatches == 1) {
            deployedSetField = setMatch;
        }
        if (listMatches == 1) {
            everDeployedListField = listMatch;
        }
    }

    /** The manager's nested DeployedFleetMember class = the one with a public two-arg constructor
     * taking ({@code member}, {@code hull}) by instance — the (FleetMember, Ship) shape, never
     * its (FleetMember, wing) twin. */
    private static Constructor<?> dfmConstructorOrNull(Class<?> managerClass, Object member,
            Object hull) {
        for (Class<?> nested : managerClass.getDeclaredClasses()) {
            for (Constructor<?> c : nested.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0].isInstance(member) && p[1].isInstance(hull)) {
                    c.setAccessible(true);
                    return c;
                }
            }
        }
        return null;
    }

    /** The old manager's {@code getCurrStrength()} — the engine COMPUTES it as the DP sum of the
     * deployed set's non-fighter-wing entries;
     * −1 when unreadable. */
    private int currStrengthOrNeg(Object manager) {
        try {
            return ((Number) method(manager, "getCurrStrength").invoke(manager)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** One deployed-set entry's share of {@code getCurrStrength()} — the same terms the engine
     * sums: a fighter-wing entry counts 0, a ship entry its member's DP cost truncated to int. */
    private int entryDpOrZero(Object entry) {
        try {
            if (Boolean.TRUE.equals(method(entry, "isFighterWing").invoke(entry))) {
                return 0;
            }
            Object member = method(entry, "getMember").invoke(entry);
            return member == null ? 0
                    : (int) ((Number) method(member, "getDeploymentPointsCost").invoke(member))
                            .floatValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Method oneArgMethodFor(Object target, String name, Object arg) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(arg)) {
                return m;
            }
        }
        return null;
    }

    private static Object engineOrNull() throws Exception {
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true,
                SeatTeamEnginePort.class.getClassLoader());
        return engineClass.getMethod("getInstance").invoke(null);
    }

    /** {@code setWingOwner(newOwner)} on every wing of {@code hull}; returns how many took it. */
    private int flipWingOwners(Object hull, int newOwner) throws Exception {
        int flipped = 0;
        Object wings = method(hull, "getAllWings").invoke(hull);
        if (wings instanceof List<?>) {
            for (Object wing : (List<?>) wings) {
                if (wing == null) {
                    continue;
                }
                boolean took = false;
                for (Method m : wing.getClass().getMethods()) {
                    if (m.getName().equals("setWingOwner") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == int.class) {
                        m.invoke(wing, newOwner);
                        took = true;
                        break;
                    }
                }
                if (took) {
                    flipped++;
                } else {
                    System.out.println("npeer-serve seat_team_port_error op=wing_owner"
                            + " err=no setWingOwner(int) on " + wing.getClass().getName());
                }
            }
        }
        return flipped;
    }

    /** Null the flipped parts' own ship targets and every other ship's target that IS a
     * flipped part; returns how many references were dropped. */
    private int dropStaleTargets(List<Object> parts) throws Exception {
        int dropped = 0;
        for (Object part : parts) {
            if (method(part, "getShipTarget").invoke(part) != null) {
                setShipTargetNull(part);
                dropped++;
            }
        }
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true,
                SeatTeamEnginePort.class.getClassLoader());
        Object engine = engineClass.getMethod("getInstance").invoke(null);
        Object ships = engine == null ? null : engineClass.getMethod("getShips").invoke(engine);
        if (ships instanceof List<?>) {
            for (Object ship : (List<?>) ships) {
                if (ship == null || parts.contains(ship)) {
                    continue;
                }
                Object target = method(ship, "getShipTarget").invoke(ship);
                if (target != null && parts.contains(target)) {
                    setShipTargetNull(ship);
                    dropped++;
                }
            }
        }
        return dropped;
    }

    private void setShipTargetNull(Object ship) throws Exception {
        for (Method m : ship.getClass().getMethods()) {
            if (m.getName().equals("setShipTarget") && m.getParameterCount() == 1) {
                m.invoke(ship, new Object[] {null});
                return;
            }
        }
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
        System.out.println("npeer-serve seat_team_port_error op=" + op + " err="
                + cause.getClass().getName() + ": " + cause.getMessage());
    }
}
