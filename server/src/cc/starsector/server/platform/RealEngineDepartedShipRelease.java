package cc.starsector.server.platform;

import cc.starsector.server.names.EngineRow;
import cc.starsector.server.names.EngineSym;
import cc.starsector.server.names.EngineSymContentInit;
import cc.starsector.server.names.EngineSymProbesB;
import cc.starsector.server.runtime.ServerConfig;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lets a removed Ship go from the engine's sets that never let it go: both
 * fleet managers' all-ever-deployed list and disabled set, the shard map, the battle's custom-data keys, a wing
 * record's sample-fighter slot, the fog reveal maps, and the contrail emitters a removed body leaves behind.
 *
 * <p><b>Why it exists.</b> Each holder is add-only for the life of a battle. The windowed game opens a NEW engine for
 * every battle and drops the old one whole, so none of them ever needed a remove; the serve keeps ONE engine across
 * its rounds, so every Ship removed in a round stayed reachable from it for the life of the process:
 * <ul>
 * <li>{@code h1}: a fleet manager's all-ever-deployed list, one entry per deployed ship or wing, appended at deploy;
 *</li>
 * <li>{@code h2}: its disabled set;</li>
 * <li>{@code shard}: its shard-to-original map, keyed by the shard's entry;
 *</li>
 * <li>{@code custom}: the battle's custom-data keys built from a ship id: the cryoflux previous-state keys and the AI's
 * non-AI flag key;</li>
 * <li>{@code sample}: a wing record's sample fighter, written once while null and never cleared, so it keeps the
 * wing's first fighter;</li>
 * <li>{@code fog}: each side's fog tracker, whose reveal entries age out only in its own {@code advance}, which the
 * windowed game's map widget makes every frame and nothing made headless;</li>
 * <li>{@code anim}: a contrail emitter the ship's engine controller registers on the animation layer, which stays
 * registered and advanced every tick after its body is removed.</li>
 * </ul>
 * The measurement was all-or-nothing: a removed Ship stays reachable while ANY one of them still names it, so every
 * holder is individually necessary.
 *
 * <p><b>How a ship is released.</b> The growth seam's removal callback hands every removed object to
 * {@link #noteRemoved}; a Ship or a wing record is kept in a ledger (identity-keyed, insertion-ordered) stamped with
 * the pass count, and a re-add ({@link #noteAdded}) cancels it. Nothing inside the engine's own remove is edited:
 * observation only. Every {@value #PASS_PERIOD_TICKS} served ticks {@link #pass} releases each entry that is a whole
 * pass old and that the engine itself no longer counts: a Ship once {@code wasRemoved()} and NOT
 * {@code engine.isShipAlive(ship)} (that read also keeps a fighter docked in a bay alive, so it stays pending until its
 * relaunch cancels it); a wing record once it is gone from the engine's objects and its carrier is not alive (a live
 * carrier at no readiness still feeds the record). A released entry is then dropped from each armed holder by
 * identity, and each released Ship's departure reason is counted from the deployment record the engine kept for it.
 *
 * <p><b>Two modes per holder.</b> ARMED = its edit runs in every pass that releases something (fog: every tick).
 * OFF = the behaviour before this class: masked by {@code cc.server.releaseDepartedMask}, a member that did not
 * resolve at {@link #arm(Object, boolean)} (that holder alone), a throw (that holder latched, ONE row, the serve
 * continues), the whole release switched off by {@code cc.server.releaseDeparted=false}, or the correctives master
 * switch {@code cc.predict.correctivesOff} (read by the caller, which then never arms). A throw in the tap, a release
 * predicate or the reason read latches every ledger holder at once; the fog holder is not ledger-driven and stays.
 *
 * <p>Sim-thread cost: fog = two tracker fetches and two tracker advances a tick, 2 x O(live reveal entries),
 * which is the engine's own frame work; the tap = one volatile read per add or remove, two class tests for a live
 * ledger; the pass = O(1) with nothing pending, else O(|h1| + |h2| + custom keys + emitters) once every
 * {@value #PASS_PERIOD_TICKS} served ticks. It ends the per-tick advance of every orphaned emitter. No per-tick or
 * per-pass row: one boot row, one row per round close, one row per latch. Threading: {@link #arm} on the boot thread;
 * the tap, {@link #fogTick}, {@link #pass}, {@link #openLedger} and {@link #closeLedger} on the sim thread. The ledger
 * takes this class's own lock and never calls the growth seam while it holds it.
 */
public final class RealEngineDepartedShipRelease {

    /** The holder tokens, in boot-row order; the mask knob names them. */
    static final String[] HOLDERS = {"h1", "h2", "shard", "custom", "sample", "fog", "anim"};

    /** One release pass every this many served ticks. */
    public static final long PASS_PERIOD_TICKS = 64L;

    private static final int H1 = 0;
    private static final int H2 = 1;
    private static final int SHARD = 2;
    private static final int CUSTOM = 3;
    private static final int SAMPLE = 4;
    private static final int FOG = 5;
    private static final int ANIM = 6;

    private static final int DESTROYED = 0;
    private static final int DISABLED = 1;
    private static final int RETREATED = 2;
    private static final int NONE = 3;

    private static final String API_COMBAT = "com.fs.starfarer.api.combat.";
    private static final String CRYO_KEY_PREFIX = "cryo_prev_";
    private static final String AI_FLAG_KEY_PREFIX = "nonAI_SAIF_";
    private static final Object[][] SIDES = {{Integer.valueOf(0)}, {Integer.valueOf(1)}};
    private static final Object[] NO_MANAGERS = new Object[0];

    /** The ledger lock: this class itself. */
    private static final Object LOCK = RealEngineDepartedShipRelease.class;

    /** The armed holders, or null = OFF. */
    private static volatile RealEngineDepartedShipRelease armed;

    /** The open round's ledger the tap feeds; null = no round open, OFF, fog only, or latched. */
    private static volatile Ledger open;

    /** The open round's ledger, kept for the round-close row after a latch (guarded by {@link #LOCK}). */
    private static Ledger round;

    /** One weak reference per released Ship, for the process (guarded by {@link #LOCK}). */
    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();
    private static final Set<Reference<?>> WATCHED = new HashSet<>();
    private static long collectedTotal;
    private static long releasedTotal;

    private final Object engine;
    private final Members m;
    private final boolean[] live;
    private final boolean ledgerLive;
    private volatile boolean ledgerLatched;
    private final Set<String> latched = new LinkedHashSet<>();
    private Object[] fogDtArgs;
    private float fogDtFor;

    private RealEngineDepartedShipRelease(Object engine, Members m, boolean[] live, boolean ledgerLive) {
        this.engine = engine;
        this.m = m;
        this.live = live;
        this.ledgerLive = ledgerLive;
    }

    /**
     * The resolved engine members: one group per holder, plus {@code core} (the release predicates and the reason
     * read every ledger holder needs). {@link #arm(Object, boolean)} fills one from the engine; a contract row hands
     * in stand-ins. A group with a null member, or a miss recorded in {@link #missed}, reads OFF alone. Read at arm,
     * never edited after.
     */
    public static final class Members {
        /** Group ({@code core} or a holder token) to the throwable its resolve raised. */
        public final Map<String, Throwable> missed = new LinkedHashMap<>();

        // core: the release predicates and the reason read.
        public Method engineGetFleetManager;
        public Method engineIsShipAlive;
        public Method engineGetObjects;
        public Method repoContains;
        public Method entityWasRemoved;
        public Method wingIsCarrierAlive;
        public Method managerGetAllEverDeployed;
        public Method managerGetDisabled;
        public Method managerGetDestroyed;
        public Method managerGetRetreated;
        public Method memberGetMember;
        public Field memberShip;
        // h1 and shard: a wing's deployment entry.
        public Field memberWing;
        // shard
        public Method managerGetShardMap;
        // custom
        public Method engineGetCustomData;
        public Method shipGetId;
        // sample
        public Method shipGetWing;
        public Method wingGetSampleFighter;
        public Method wingGetMembers;
        public Field wingSampleSlot;
        // fog
        public Method engineGetFogOfWar;
        public Method fogAdvance;
        // anim
        public Method animManagerGetInstance;
        public Field animManagerAnimations;
        public Field animManagerAdded;
        public Class<?> contrailEmitterClass;
        public Field contrailDelegate;
        public Method animStop;
        public Method animIsDone;
        public Method engineRemoveAnimation;
        public Class<?> entityApi;

        Object[] group(String g) {
            switch (g) {
                case "core":
                    return new Object[] {engineGetFleetManager, engineIsShipAlive, engineGetObjects, repoContains,
                        entityWasRemoved, wingIsCarrierAlive, managerGetAllEverDeployed, managerGetDisabled,
                        managerGetDestroyed, managerGetRetreated, memberGetMember, memberShip};
                case "h1":
                    return new Object[] {memberWing};
                case "h2":
                    return new Object[0];
                case "shard":
                    return new Object[] {managerGetShardMap, memberWing};
                case "custom":
                    return new Object[] {engineGetCustomData, shipGetId};
                case "sample":
                    return new Object[] {shipGetWing, wingGetSampleFighter, wingGetMembers, wingSampleSlot};
                case "fog":
                    return new Object[] {engineGetFogOfWar, fogAdvance};
                case "anim":
                    return new Object[] {animManagerGetInstance, animManagerAnimations, animManagerAdded,
                        contrailEmitterClass, contrailDelegate, animStop, animIsDone, engineRemoveAnimation, entityApi};
                default:
                    throw new IllegalArgumentException("no member group " + g);
            }
        }
    }

    /**
     * Arm against the live engine: called once at the binding factory's create tail. Reads the release switch and
     * the mask, resolves each holder's members alone, and returns the outcome for the caller's boot row
     * ({@code armed holders=...}, or {@code OFF <why>}). {@code growthSeam} = whether the growth seam will install at
     * round open: without it nothing feeds the ledger, and only the fog holder arms. Never throws.
     */
    public static String arm(Object engine, boolean growthSeam) {
        try {
            if (!ServerConfig.resolveBoolean("cc.server.releaseDeparted", "CC_SERVER_RELEASE_DEPARTED",
                    "server.releaseDeparted", true)) {
                disarm();
                return "OFF releaseDeparted=false";
            }
            String mask = ServerConfig.resolve("cc.server.releaseDepartedMask", "CC_SERVER_RELEASE_DEPARTED_MASK",
                    "server.releaseDepartedMask", "");
            return arm(engine, growthSeam, mask, resolve(engine));
        } catch (Throwable t) {
            disarm();
            return "OFF " + describe(t);
        }
    }

    /**
     * {@link #arm(Object, boolean)} against already-resolved members (a contract row hands in stand-ins). {@code mask}
     * = a comma list of holder tokens to keep OFF, or {@code all}. A holder whose group (or the core group, for every
     * ledger holder) misses reads OFF alone, never a half-armed holder. Never throws.
     */
    public static String arm(Object engine, boolean growthSeam, String mask, Members members) {
        try {
            disarm();
            if (engine == null || members == null) {
                throw new IllegalStateException("no engine or no members");
            }
            Set<String> maskSet = new LinkedHashSet<>();
            int unknown = 0;
            for (String raw : (mask == null ? "" : mask).split(",")) {
                String token = raw.trim().toLowerCase(Locale.ROOT);
                if (token.isEmpty()) {
                    continue;
                }
                if (token.equals("all")) {
                    return "OFF mask=all";
                }
                if (Arrays.asList(HOLDERS).contains(token)) {
                    maskSet.add(token);
                } else {
                    unknown++;
                }
            }
            String unknownTail = unknown > 0 ? " mask_unknown=" + unknown : "";
            List<String> liveNames = new ArrayList<>();
            List<String> masked = new ArrayList<>();
            List<String> off = new ArrayList<>();
            Throwable coreMiss = growthSeam ? ready(members, "core") : null;
            if (coreMiss != null) {
                off.add("core:" + simpleName(coreMiss));
            }
            boolean[] live = new boolean[HOLDERS.length];
            for (int i = 0; i < HOLDERS.length; i++) {
                String holder = HOLDERS[i];
                if (maskSet.contains(holder)) {
                    masked.add(holder);
                    continue;
                }
                if (i != FOG && (!growthSeam || coreMiss != null)) {
                    continue;
                }
                Throwable miss = ready(members, holder);
                if (miss != null) {
                    off.add(holder + ":" + simpleName(miss));
                    continue;
                }
                live[i] = true;
                liveNames.add(holder);
            }
            String tail = list(" masked=", masked) + list(" off=", off) + unknownTail;
            if (!growthSeam) {
                if (!live[FOG]) {
                    return "OFF reason=no_growth_seam" + tail;
                }
                armed = new RealEngineDepartedShipRelease(engine, members, live, false);
                return "armed holders=fog_only reason=no_growth_seam" + unknownTail;
            }
            if (liveNames.isEmpty()) {
                return "OFF no_holder" + tail;
            }
            boolean ledgerLive = false;
            for (int i = 0; i < HOLDERS.length; i++) {
                ledgerLive |= i != FOG && live[i];
            }
            armed = new RealEngineDepartedShipRelease(engine, members, live, ledgerLive);
            return "armed holders=" + String.join(",", liveNames) + tail;
        } catch (Throwable t) {
            disarm();
            return "OFF " + describe(t);
        }
    }

    /**
     * Opens a round's ledger: the growth seam's install calls it with the Ship class and the wing-record class it
     * resolved. Clears any earlier ledger. A no-op unless a ledger holder is armed and the ledger never latched.
     */
    public static void openLedger(Class<?> shipClass, Class<?> wingClass) {
        try {
            synchronized (LOCK) {
                open = null;
                round = null;
                RealEngineDepartedShipRelease a = armed;
                if (a == null || !a.ledgerLive || a.ledgerLatched || shipClass == null || wingClass == null) {
                    return;
                }
                Ledger l = new Ledger(shipClass, wingClass);
                round = l;
                open = l;
            }
        } catch (Throwable t) {
            System.out.println("real-engine-departed-release ledger_open_failed " + describe(t));
        }
    }

    /**
     * Closes the round's ledger: the growth seam's uninstall calls it. Prints the ONE round row (nothing when no
     * ledger was open), then clears the ledger, its pass count and its pending wings. Never throws.
     */
    public static void closeLedger() {
        String row;
        try {
            synchronized (LOCK) {
                Ledger l = round;
                open = null;
                round = null;
                if (l == null) {
                    return;
                }
                pollCollected();
                RealEngineDepartedShipRelease a = armed;
                String latchedList = a == null || a.latched.isEmpty() ? "none" : String.join(",", a.latched);
                row = "real-engine-departed-release round released=" + l.released + " destroyed=" + l.destroyed
                        + " disabled=" + l.disabled + " retreated=" + l.retreated + " none=" + l.none
                        + " h1=" + l.h1 + " h2=" + l.h2 + " shard=" + l.shard + " custom=" + l.custom
                        + " anim=" + l.anim + " sample_swaps=" + l.sampleSwaps + " pending=" + l.pending.size()
                        + " latched=" + latchedList + " collected=" + collectedTotal
                        + " released_total=" + releasedTotal;
                l.pending.clear();
                l.pendingWings.clear();
                l.passCount = 0L;
            }
        } catch (Throwable t) {
            System.out.println("real-engine-departed-release round_close_failed " + describe(t));
            return;
        }
        System.out.println(row);
    }

    /** The growth seam's removal callback. OFF, no round open or latched = one volatile read. Never throws. */
    public static void noteRemoved(Object removed) {
        Ledger l = open;
        if (l == null) {
            return;
        }
        l.note(removed, true);
    }

    /** The growth seam's add callback: a re-add cancels a pending entry. Same cost law as {@link #noteRemoved}. */
    public static void noteAdded(Object added) {
        Ledger l = open;
        if (l == null) {
            return;
        }
        l.note(added, false);
    }

    /**
     * Each side's fog tracker, re-fetched every call (a round reset re-mints them), advanced by its own public
     * {@code advance} with the step boundary's raw frame dt, as the windowed game's map widget does. A null tracker
     * is skipped; a throw latches the fog holder alone. OFF = one volatile read. Called by the step boundary.
     */
    public static void fogTick(float dt) {
        RealEngineDepartedShipRelease a = armed;
        if (a == null || !a.live[FOG] || !Float.isFinite(dt) || dt < 0f) {
            return;
        }
        try {
            if (a.fogDtArgs == null || dt != a.fogDtFor) {
                a.fogDtArgs = new Object[] {Float.valueOf(dt)};
                a.fogDtFor = dt;
            }
            for (Object[] side : SIDES) {
                Object tracker = a.m.engineGetFogOfWar.invoke(a.engine, side);
                if (tracker != null) {
                    a.m.fogAdvance.invoke(tracker, a.fogDtArgs);
                }
            }
        } catch (Throwable t) {
            a.latchHolder(FOG, "the fog tracker advance", t);
        }
    }

    /**
     * One release pass, on the served tick (never a process counter: the warm-up drives the same boundary). Returns
     * unless {@code simTick} is a multiple of {@value #PASS_PERIOD_TICKS}; O(1) with nothing pending. Called by the
     * step boundary after its post-step reads.
     */
    public static void pass(long simTick) {
        if (simTick % PASS_PERIOD_TICKS != 0L) {
            return;
        }
        Ledger l = open;
        if (l == null) {
            return;
        }
        RealEngineDepartedShipRelease a = armed;
        if (a == null) {
            return;
        }
        a.runPass(l);
    }

    /** True while any holder is armed. */
    public static boolean live() {
        return armed != null;
    }

    /** True while the named holder is armed and not latched. */
    public static boolean live(String holder) {
        RealEngineDepartedShipRelease a = armed;
        int i = Arrays.asList(HOLDERS).indexOf(holder);
        return a != null && i >= 0 && a.live[i] && (i == FOG || !a.ledgerLatched);
    }

    /** The open round's pending entries; 0 with no round open. */
    public static int pending() {
        synchronized (LOCK) {
            return round == null ? 0 : round.pending.size();
        }
    }

    /** The open round's wings waiting on a sample swap; 0 with no round open. A waiting wing is HELD by this class. */
    public static int waitingWings() {
        synchronized (LOCK) {
            return round == null ? 0 : round.pendingWings.size();
        }
    }

    /** Back to OFF, any open ledger dropped. The contract rows' reset between cases; the serve never calls it. */
    public static void disarm() {
        synchronized (LOCK) {
            armed = null;
            open = null;
            round = null;
        }
    }

    private void runPass(Ledger l) {
        synchronized (LOCK) {
            if (open != l) {
                return;
            }
            long thisPass = l.passCount++;
            pollCollected();
            if (l.pending.isEmpty() && l.pendingWings.isEmpty()) {
                return;
            }
            List<Object> ships = new ArrayList<>();
            Set<Object> relShips = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Object> relWings = Collections.newSetFromMap(new IdentityHashMap<>());
            Object[] managers;
            int[] reasons = new int[4];
            try {
                Object objects = null;
                for (Iterator<Entry> it = l.pending.values().iterator(); it.hasNext(); ) {
                    Entry e = it.next();
                    if (e.stamp >= thisPass) {
                        break; // insertion order = stamp order: every later entry is younger than a whole pass
                    }
                    if (e.wing) {
                        if (objects == null) {
                            objects = m.engineGetObjects.invoke(engine);
                        }
                        if (!truth(m.repoContains.invoke(objects, e.obj))
                                && !truth(m.wingIsCarrierAlive.invoke(e.obj))) {
                            relWings.add(e.obj);
                            it.remove();
                        }
                    } else if (truth(m.entityWasRemoved.invoke(e.obj))
                            && !truth(m.engineIsShipAlive.invoke(engine, e.obj))) {
                        relShips.add(e.obj);
                        ships.add(e.obj);
                        it.remove();
                    }
                }
                managers = relShips.isEmpty() && relWings.isEmpty() ? NO_MANAGERS
                        : new Object[] {m.engineGetFleetManager.invoke(engine, SIDES[0]),
                            m.engineGetFleetManager.invoke(engine, SIDES[1])};
                tallyReasons(relShips, managers, reasons);
            } catch (Throwable t) {
                latchLedger(l, "a release predicate or the reason read", t);
                return;
            }
            if (!relShips.isEmpty() || !relWings.isEmpty()) {
                Set<Object> wingsOrNull = relWings.isEmpty() ? null : relWings;
                if (live[H1]) {
                    dropFromManagers(l, H1, managers, m.managerGetAllEverDeployed, relShips, wingsOrNull);
                }
                if (live[H2] && !relShips.isEmpty()) {
                    dropFromManagers(l, H2, managers, m.managerGetDisabled, relShips, null);
                }
                if (live[SHARD]) {
                    dropFromManagers(l, SHARD, managers, m.managerGetShardMap, relShips, wingsOrNull);
                }
                if (live[CUSTOM] && !ships.isEmpty()) {
                    releaseCustomKeys(l, ships);
                }
            }
            if (live[SAMPLE]) {
                swapSamples(l, ships, relWings, managers);
            }
            if (live[ANIM] && !ships.isEmpty()) {
                stopOrphanedEmitters(l);
            }
            l.destroyed += reasons[DESTROYED];
            l.disabled += reasons[DISABLED];
            l.retreated += reasons[RETREATED];
            l.none += reasons[NONE];
            for (Object ship : ships) {
                WATCHED.add(new WeakReference<>(ship, COLLECTED));
            }
            l.released += ships.size();
            releasedTotal += ships.size();
        }
    }

    /**
     * Each released Ship's departure reason, read off its deployment entry BEFORE any holder edit and whatever the
     * mask, in this order (a destroyed member is ALSO in the disabled set): destroyed, disabled, retreated, else none
     * (a removal with no report: a despawn, a pod, a fighter, which owns no deployment entry).
     */
    private void tallyReasons(Set<Object> relShips, Object[] managers, int[] reasons)
            throws ReflectiveOperationException {
        if (relShips.isEmpty()) {
            return;
        }
        Map<Object, Object> deployedOf = new IdentityHashMap<>();
        Map<Object, Object> managerOf = new IdentityHashMap<>();
        Class<?> memberClass = m.memberShip.getDeclaringClass();
        for (Object manager : managers) {
            Object all = manager == null ? null : m.managerGetAllEverDeployed.invoke(manager);
            if (!(all instanceof Collection)) {
                continue;
            }
            for (Object d : (Collection<?>) all) {
                if (!memberClass.isInstance(d)) {
                    continue;
                }
                Object ship = m.memberShip.get(d);
                if (ship != null && relShips.contains(ship) && !deployedOf.containsKey(ship)) {
                    deployedOf.put(ship, d);
                    managerOf.put(ship, manager);
                }
            }
        }
        for (Object ship : relShips) {
            Object d = deployedOf.get(ship);
            if (d == null) {
                reasons[NONE]++;
                continue;
            }
            Object manager = managerOf.get(ship);
            Object member = m.memberGetMember.invoke(d);
            if (holds(m.managerGetDestroyed.invoke(manager), member)) {
                reasons[DESTROYED]++;
            } else if (holds(m.managerGetDisabled.invoke(manager), d)) {
                reasons[DISABLED]++;
            } else if (holds(m.managerGetRetreated.invoke(manager), member)) {
                reasons[RETREATED]++;
            } else {
                reasons[NONE]++;
            }
        }
    }

    /**
     * h1 / h2 / shard: on both managers, drops by identity each deployment entry (a shard map: each entry whose KEY
     * is one) that names a released Ship, or a released wing record when {@code relWings} is non-null.
     */
    private void dropFromManagers(Ledger l, int holder, Object[] managers, Method getter, Set<Object> relShips,
            Set<Object> relWings) {
        try {
            Class<?> memberClass = m.memberShip.getDeclaringClass();
            for (Object manager : managers) {
                Object held = manager == null ? null : getter.invoke(manager);
                Collection<?> c = held instanceof Map ? ((Map<?, ?>) held).keySet()
                        : held instanceof Collection ? (Collection<?>) held : null;
                if (c == null || c.isEmpty()) {
                    continue;
                }
                int before = c.size();
                c.removeIf(d -> memberClass.isInstance(d) && (relShips.contains(read(m.memberShip, d))
                        || relWings != null && relWings.contains(read(m.memberWing, d))));
                int dropped = before - c.size();
                if (holder == H1) {
                    l.h1 += dropped;
                } else if (holder == H2) {
                    l.h2 += dropped;
                } else {
                    l.shard += dropped;
                }
            }
        } catch (Throwable t) {
            latchHolder(holder, "the " + HOLDERS[holder] + " drop", t);
        }
    }

    /** custom: the keys the battle's scripts built from a released Ship's id. A null or blank id releases none. */
    private void releaseCustomKeys(Ledger l, List<Object> ships) {
        try {
            Object held = m.engineGetCustomData.invoke(engine);
            if (!(held instanceof Map) || ((Map<?, ?>) held).isEmpty()) {
                return;
            }
            Set<String> exact = new HashSet<>();
            List<String> prefixes = new ArrayList<>();
            for (Object ship : ships) {
                Object id = m.shipGetId.invoke(ship);
                if (!(id instanceof String) || ((String) id).isBlank()) {
                    continue;
                }
                exact.add(AI_FLAG_KEY_PREFIX + id);
                prefixes.add(CRYO_KEY_PREFIX + id + "_");
            }
            if (exact.isEmpty()) {
                return;
            }
            Set<?> keys = ((Map<?, ?>) held).keySet();
            int before = keys.size();
            keys.removeIf(k -> k instanceof String && (exact.contains(k) || startsWithAny((String) k, prefixes)));
            l.custom += before - keys.size();
        } catch (Throwable t) {
            latchHolder(CUSTOM, "the custom-data keys", t);
        }
    }

    /**
     * sample: a wing whose slot names a departed fighter hands the slot to a live member of the same wing; with none
     * yet, the wing waits (retried each pass; dropped at its own release, once the engine has let go of it, or at
     * the round close).
     */
    private void swapSamples(Ledger l, List<Object> ships, Set<Object> relWings, Object[] managers) {
        try {
            Object[] objects = new Object[1];
            for (Iterator<Map.Entry<IdKey, Object>> it = l.pendingWings.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<IdKey, Object> e = it.next();
                Object wing = e.getKey().obj;
                if (relWings.contains(wing) || wingGone(wing, objects) || swapSample(l, wing, e.getValue())) {
                    it.remove();
                }
            }
            if (ships.isEmpty()) {
                return;
            }
            // The wings are found from the WING side (each manager's deployment entries, plus the wing a released
            // fighter still names), never from the fighter alone: a ship system clears a fighter's wing before it
            // is removed, and that fighter may be the sample a
            // live wing still holds.
            Set<IdKey> wings = new LinkedHashSet<>();
            for (Object fighter : ships) {
                Object wing = m.shipGetWing.invoke(fighter);
                if (wing != null) {
                    wings.add(new IdKey(wing));
                }
            }
            Class<?> memberClass = m.memberShip.getDeclaringClass();
            for (Object manager : managers) {
                Object all = manager == null ? null : m.managerGetAllEverDeployed.invoke(manager);
                if (!(all instanceof Collection)) {
                    continue;
                }
                for (Object d : (Collection<?>) all) {
                    Object wing = memberClass.isInstance(d) ? m.memberWing.get(d) : null;
                    if (wing != null) {
                        wings.add(new IdKey(wing));
                    }
                }
            }
            for (IdKey key : wings) {
                Object wing = key.obj;
                if (relWings.contains(wing) || l.pendingWings.containsKey(key)) {
                    continue;
                }
                Object sample = m.wingGetSampleFighter.invoke(wing);
                if (sample == null || !departed(l, sample)) {
                    continue;
                }
                if (!swapSample(l, wing, sample) && !wingGone(wing, objects)) {
                    l.pendingWings.put(key, sample);
                }
            }
        } catch (Throwable t) {
            latchHolder(SAMPLE, "the sample-fighter swap", t);
        }
    }

    /**
     * A sample fighter the engine no longer counts and the ledger no longer waits on: removed, not alive, and past
     * its whole pass (released in this pass or an earlier one).
     */
    private boolean departed(Ledger l, Object sample) throws ReflectiveOperationException {
        return truth(m.entityWasRemoved.invoke(sample)) && !truth(m.engineIsShipAlive.invoke(engine, sample))
                && !l.pending.containsKey(new IdKey(sample));
    }

    /**
     * A wing the engine has let go of (the wing release's own test: out of the object repository, its carrier dead).
     * It is never parked: a parked wing is held by this class, and with it the departed sample its slot names.
     * {@code objects} = a one-slot cache of the repository for the pass.
     */
    private boolean wingGone(Object wing, Object[] objects) throws ReflectiveOperationException {
        if (objects[0] == null) {
            objects[0] = m.engineGetObjects.invoke(engine);
        }
        return !truth(m.repoContains.invoke(objects[0], wing)) && !truth(m.wingIsCarrierAlive.invoke(wing));
    }

    /**
     * One swap: the first member R of the wing that is not {@code released}, not removed, alive and still of this
     * wing. The slot must read {@code released} before the write and R after it, else the holder latches; a slot that
     * already reads R counts no swap and is never written twice. Returns false with no such R (the wing waits).
     */
    private boolean swapSample(Ledger l, Object wing, Object released) throws ReflectiveOperationException {
        Object members = m.wingGetMembers.invoke(wing);
        if (!(members instanceof Collection)) {
            return false;
        }
        for (Object r : ((Collection<?>) members).toArray()) {
            if (r == null || r == released || truth(m.entityWasRemoved.invoke(r))
                    || !truth(m.engineIsShipAlive.invoke(engine, r)) || m.shipGetWing.invoke(r) != wing) {
                continue;
            }
            Object before = m.wingGetSampleFighter.invoke(wing);
            if (before == r) {
                return true;
            }
            if (before != released) {
                throw new IllegalStateException("the sample slot no longer reads the released fighter");
            }
            m.wingSampleSlot.set(wing, r);
            if (m.wingGetSampleFighter.invoke(wing) != r) {
                throw new IllegalStateException("the sample slot does not read back the written member");
            }
            l.sampleSwaps++;
            return true;
        }
        return false;
    }

    /**
     * anim: in a pass that released a Ship, every contrail emitter in the animation layer (live and pending lists)
     * whose body was removed and that is not done is collected FIRST (the engine's remove edits the pending list),
     * then given the engine's own two statements in order: {@code stop()}, then {@code engine.removeAnimation}.
     */
    private void stopOrphanedEmitters(Ledger l) {
        try {
            Object ce = m.animManagerGetInstance.invoke(null, RealEngineAnimLayerAdvance.ANIM_MANAGER_CE);
            if (ce == null) {
                return;
            }
            List<Object> orphans = new ArrayList<>();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Field listField : new Field[] {m.animManagerAnimations, m.animManagerAdded}) {
                Object list = listField.get(ce);
                if (!(list instanceof Collection)) {
                    continue;
                }
                for (Object animation : (Collection<?>) list) {
                    if (!m.contrailEmitterClass.isInstance(animation) || !seen.add(animation)) {
                        continue;
                    }
                    Object body = m.contrailDelegate.get(animation);
                    if (body != null && m.entityApi.isInstance(body) && truth(m.entityWasRemoved.invoke(body))
                            && !truth(m.animIsDone.invoke(animation)) && !countedAlive(body)) {
                        orphans.add(animation);
                    }
                }
            }
            for (Object emitter : orphans) {
                m.animStop.invoke(emitter);
                m.engineRemoveAnimation.invoke(engine, emitter);
                l.anim++;
            }
        } catch (Throwable t) {
            latchHolder(ANIM, "the orphaned-emitter stop", t);
        }
    }

    /**
     * A removed body the engine still counts alive is a fighter docked in a bay: the engine registers a fighter's
     * emitters once, so stopping them here would leave the relaunched fighter with none. Only a Ship can be asked
     * (a missile or projectile body that was removed is gone for good).
     */
    private boolean countedAlive(Object body) throws ReflectiveOperationException {
        return m.engineIsShipAlive.getParameterTypes()[0].isInstance(body)
                && truth(m.engineIsShipAlive.invoke(engine, body));
    }

    private void latchHolder(int holder, String call, Throwable t) {
        synchronized (LOCK) {
            if (!live[holder]) {
                return;
            }
            live[holder] = false;
            latched.add(HOLDERS[holder]);
            if (holder == SAMPLE && round != null) {
                round.pendingWings.clear();
            }
        }
        System.out.println("real-engine-departed-release LATCHED OFF " + HOLDERS[holder] + " at " + call
                + " (the serve continues without it, as before this class): " + describe(t));
    }

    /** A throw in the tap, a predicate or the reason read: every ledger holder off, the ledger dropped, ONE row. */
    private static void latchLedger(Ledger l, String call, Throwable t) {
        synchronized (LOCK) {
            if (l.latched) {
                return;
            }
            l.latched = true;
            if (open == l) {
                open = null;
            }
            l.pending.clear();
            l.pendingWings.clear();
            RealEngineDepartedShipRelease a = armed;
            if (a != null) {
                a.ledgerLatched = true;
                a.latched.add("ledger");
            }
        }
        System.out.println("real-engine-departed-release LATCHED OFF ledger at " + call
                + " (the serve continues without the ledger holders, as before this class): " + describe(t));
    }

    /** Counts every released Ship the collector has cleared (guarded by {@link #LOCK}). */
    private static void pollCollected() {
        for (Reference<?> r = COLLECTED.poll(); r != null; r = COLLECTED.poll()) {
            WATCHED.remove(r);
            collectedTotal++;
        }
    }

    /** Resolves every group alone; a miss is recorded against its group and never stops the others. */
    static Members resolve(Object engine) {
        Members m = new Members();
        ClassLoader loader = engine.getClass().getClassLoader();
        Class<?> engineClass = engine.getClass();
        resolveGroup(m, "core", () -> {
            Class<?> manager = load(EngineSym.COMBAT_FLEET_MANAGER_CLASS, loader);
            Class<?> member = load(EngineSym.DEPLOYED_MEMBER_CLASS, loader);
            Class<?> wing = load(EngineSymProbesB.PROBES_B_FIGHTER_WING_CLASS, loader);
            m.engineGetFleetManager = engineClass.getMethod("getFleetManager", int.class);
            m.engineIsShipAlive = engineClass.getMethod("isShipAlive",
                    Class.forName(API_COMBAT + "ShipAPI", false, loader));
            m.engineGetObjects = engineClass.getMethod("getObjects");
            m.repoContains = m.engineGetObjects.getReturnType().getMethod("contains", Object.class);
            m.entityWasRemoved = Class.forName(API_COMBAT + "CombatEntityAPI", false, loader).getMethod("wasRemoved");
            m.wingIsCarrierAlive = wing.getMethod("isCarrierAlive");
            m.managerGetAllEverDeployed = manager.getMethod("getAllEverDeployed");
            m.managerGetDisabled = manager.getMethod("getDisabled");
            m.managerGetDestroyed = manager.getMethod("getDestroyed");
            m.managerGetRetreated = manager.getMethod("getRetreated");
            m.memberGetMember = member.getMethod("getMember");
            m.memberShip = EngineSymContentInit.CONTENT_INIT_DEPLOYED_MEMBER_SHIP_FIELD.field(member);
        });
        resolveGroup(m, "h1", () -> m.memberWing = EngineSymContentInit.CONTENT_INIT_DEPLOYED_MEMBER_WING_FIELD
                .field(load(EngineSym.DEPLOYED_MEMBER_CLASS, loader)));
        resolveGroup(m, "shard", () -> {
            m.managerGetShardMap = load(EngineSym.COMBAT_FLEET_MANAGER_CLASS, loader)
                    .getMethod("getShardToOriginalShipMap");
            m.memberWing = EngineSymContentInit.CONTENT_INIT_DEPLOYED_MEMBER_WING_FIELD
                    .field(load(EngineSym.DEPLOYED_MEMBER_CLASS, loader));
        });
        resolveGroup(m, "custom", () -> {
            m.engineGetCustomData = engineClass.getMethod("getCustomData");
            m.shipGetId = load(EngineSym.SHIP_CLASS, loader).getMethod("getId");
        });
        resolveGroup(m, "sample", () -> {
            Class<?> wing = load(EngineSymProbesB.PROBES_B_FIGHTER_WING_CLASS, loader);
            m.shipGetWing = load(EngineSym.SHIP_CLASS, loader).getMethod("getWing");
            m.wingGetSampleFighter = wing.getMethod("getSampleFighter");
            m.wingGetMembers = wing.getMethod("getMembers");
            m.wingSampleSlot = EngineSymContentInit.CONTENT_INIT_FIGHTER_WING_SAMPLE_FIELD.field(wing);
        });
        resolveGroup(m, "fog", () -> {
            m.engineGetFogOfWar = EngineSymContentInit.CONTENT_INIT_ENGINE_FOG_OF_WAR_METHOD.method(engineClass);
            m.fogAdvance = EngineSymContentInit.CONTENT_INIT_FOG_TRACKER_ADVANCE_METHOD
                    .method(load(EngineSymContentInit.CONTENT_INIT_FOG_TRACKER_CLASS, loader));
        });
        resolveGroup(m, "anim", () -> {
            Class<?> manager = load(EngineSymContentInit.CONTENT_INIT_ANIMATION_MANAGER_CLASS, loader);
            Class<?> emitter = load(EngineSymContentInit.CONTENT_INIT_CONTRAIL_EMITTER_CLASS, loader);
            m.animManagerGetInstance = manager.getMethod("getInstance", String.class);
            m.animManagerAnimations = manager.getDeclaredField("animations");
            m.animManagerAdded = manager.getDeclaredField("added");
            m.contrailEmitterClass = emitter;
            m.contrailDelegate = EngineSymContentInit.CONTENT_INIT_CONTRAIL_EMITTER_DELEGATE_FIELD.field(emitter);
            m.animStop = emitter.getMethod("stop");
            m.animIsDone = emitter.getMethod("isDone");
            m.engineRemoveAnimation = oneArgMethod(engineClass, "removeAnimation");
            m.entityApi = Class.forName(API_COMBAT + "CombatEntityAPI", false, loader);
        });
        return m;
    }

    private interface Resolver {
        void run() throws ReflectiveOperationException;
    }

    private static void resolveGroup(Members m, String group, Resolver resolver) {
        try {
            resolver.run();
        } catch (Throwable t) {
            m.missed.put(group, t);
        }
    }

    /** The group's miss (recorded, a null member, or a member that refuses accessibility), else null. */
    private static Throwable ready(Members m, String group) {
        Throwable recorded = m.missed.get(group);
        if (recorded != null) {
            return recorded;
        }
        try {
            for (Object member : m.group(group)) {
                if (member == null) {
                    throw new IllegalStateException("the " + group + " group has an unresolved member");
                }
                if (member instanceof AccessibleObject) {
                    ((AccessibleObject) member).setAccessible(true);
                }
            }
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static Class<?> load(EngineRow row, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(row.className(), false, loader);
    }

    private static Method oneArgMethod(Class<?> owner, String name) throws NoSuchMethodException {
        for (Method candidate : owner.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == 1) {
                return candidate;
            }
        }
        throw new NoSuchMethodException("no public one-argument " + name + " on " + owner.getSimpleName());
    }

    private static Object read(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new Unchecked(e);
        }
    }

    private static boolean truth(Object o) {
        return Boolean.TRUE.equals(o);
    }

    private static boolean holds(Object collection, Object item) {
        return collection instanceof Collection && ((Collection<?>) collection).contains(item);
    }

    private static boolean startsWithAny(String s, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (s.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String list(String label, List<String> items) {
        return items.isEmpty() ? "" : label + String.join(",", items);
    }

    private static Throwable root(Throwable t) {
        Throwable c = t;
        while ((c instanceof InvocationTargetException || c instanceof Unchecked) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static String simpleName(Throwable t) {
        return root(t).getClass().getSimpleName();
    }

    private static String describe(Throwable t) {
        Throwable cause = root(t);
        return cause.getClass().getName() + ": " + cause.getMessage();
    }

    /** A reflective read's checked failure, carried out of a removeIf predicate. */
    private static final class Unchecked extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unchecked(Throwable cause) {
            super(cause);
        }
    }

    /** An identity key: the ledger keeps the engine's objects by identity, in insertion order. */
    private static final class IdKey {
        final Object obj;
        private final int hash;

        IdKey(Object obj) {
            this.obj = obj;
            this.hash = System.identityHashCode(obj);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdKey && ((IdKey) other).obj == obj;
        }
    }

    /** One removed Ship or wing record, stamped with the pass count at its removal. */
    private static final class Entry {
        final Object obj;
        final boolean wing;
        final long stamp;

        Entry(Object obj, boolean wing, long stamp) {
            this.obj = obj;
            this.wing = wing;
            this.stamp = stamp;
        }
    }

    /** One round's ledger and its counters (guarded by {@link #LOCK}). */
    private static final class Ledger {
        final Class<?> shipClass;
        final Class<?> wingClass;
        final LinkedHashMap<IdKey, Entry> pending = new LinkedHashMap<>();
        /** A wing whose released sample found no live member yet, to the released fighter its slot still names. */
        final LinkedHashMap<IdKey, Object> pendingWings = new LinkedHashMap<>();
        long passCount;
        boolean latched;
        long released;
        long destroyed;
        long disabled;
        long retreated;
        long none;
        long h1;
        long h2;
        long shard;
        long custom;
        long anim;
        long sampleSwaps;

        Ledger(Class<?> shipClass, Class<?> wingClass) {
            this.shipClass = shipClass;
            this.wingClass = wingClass;
        }

        /** The tap: keeps only a Ship or a wing record; a removal stamps, a re-add cancels. Never throws. */
        void note(Object o, boolean removed) {
            try {
                if (o == null) {
                    return;
                }
                boolean wing;
                if (shipClass.isInstance(o)) {
                    wing = false;
                } else if (wingClass.isInstance(o)) {
                    wing = true;
                } else {
                    return;
                }
                synchronized (LOCK) {
                    if (open != this) {
                        return;
                    }
                    IdKey key = new IdKey(o);
                    if (removed) {
                        pending.putIfAbsent(key, new Entry(o, wing, passCount));
                    } else {
                        pending.remove(key);
                    }
                }
            } catch (Throwable t) {
                latchLedger(this, "the removal tap", t);
            }
        }
    }
}
