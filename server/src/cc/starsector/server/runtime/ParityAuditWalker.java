package cc.starsector.server.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The PARITY-AUDIT walker (server half).
 *
 * <p>Mechanically walks the public Starsector API surface of a live combat at a snapshot tick and
 * emits one JSON line per walked subject (engine, each ship) to stdout, where the scout harness
 * already lands every stdout JSON line in {@code events.ndjson}. An offline differ
 * joins two such walk streams on (tick, subject, field) within
 * pre-registered float tolerances and reports covered-but-diverging vs UNCOVERED-and-diverging
 * fields — the unknown-unknowns detector (it would have caught STEP-14's ±1000-vs-mission map
 * bounds divergence mechanically: {@code engine.getMapWidth/getMapHeight} are in the scalar walk).
 *
 * <p><b>Reflection-only, READ-only, fail-soft.</b> Every getter invoked is a public zero-arg
 * {@code get*}/{@code is*} declared on a PUBLIC API interface ({@code com.fs.starfarer.api.combat.*}),
 * resolved against the walked object's own classloader, so this class compiles with no game jars on
 * the classpath and never touches the engine's internal (non-API) identities (descriptor-grade boundary). Getters are
 * {@code assumed} side-effect-free; the walked set is audited once from the inventory event (the
 * skipped/walked lists below). Any per-field throwable is rendered as an {@code err:} value, and
 * {@link #walkTick} swallows everything — the gate loop can never be broken by the instrument.
 *
 * <p><b>Capture/emit split.</b> The reflective READS stay on the calling
 * (serve) thread — live engine state is serve-thread-confined — but every downstream cost (field
 * sort, JSON string build, {@code System.out.println}) runs on a single bounded-queue daemon thread:
 * a measurement showed the on-thread emission as the sprint mid-class (~31 large JSON rows built+written
 * inside the frame on every tick%30, a guaranteed ~25 ms overrun twice a second, watch posture
 * only). Captured values are immutable (boxed scalars, Strings, enum constants, pre-extracted
 * Vector2f components), published via the queue handoff; the rendered bytes are IDENTICAL to the
 * pre-split walker, so the differ contract is untouched. Queue overflow (pathological only: bound
 * {@value #EMIT_QUEUE_BOUND} at 2 walks/s) drops the walk, counts it, and the emitter reports the
 * count on a {@code parity_walk_dropped} line — never a silent cap; a dropped inventory carrier
 * re-arms the once-per-JVM inventory collection. Disclosed residue: rows still queued at JVM exit
 * (normally ≤1 walk) are lost — the tail rows are not grading-load-bearing.
 *
 * <p><b>Why the CLIENT half is a separate class (session 2).</b> The in-game client mod runs under
 * the script classloader-B blocklist: {@code java.lang.reflect.*} AND file access are denied to
 * scripts ("File access and reflection are not allowed to scripts"). So the
 * client-side walk must be direct compiled API calls emitting via log4j ({@code PilotWatchLog}), at
 * a throttled cadence, with THIS walker's field-key naming as the shared contract. The differ's
 * "fields only one side emitted" report is the runtime lockstep guard between the two halves.
 */
public final class ParityAuditWalker {

    /** The API interfaces whose zero-arg public getters are walked on a ship, keyed by subject prefix. */
    private static final String SHIP_IFACE = "com.fs.starfarer.api.combat.ShipAPI";
    /** Nested ship subjects: getter on ShipAPI -> (prefix, interface walked on the result). */
    private static final String[][] SHIP_NESTED = {
        { "getMutableStats",     "stats",  "com.fs.starfarer.api.combat.MutableShipStatsAPI" },
        { "getFluxTracker",      "flux",   "com.fs.starfarer.api.combat.FluxTrackerAPI" },
        { "getSystem",           "system", "com.fs.starfarer.api.combat.ShipSystemAPI" },
        { "getEngineController", "ctrl",   "com.fs.starfarer.api.combat.ShipEngineControllerAPI" },
        { "getShield",           "shield", "com.fs.starfarer.api.combat.ShieldAPI" },
    };
    private static final String ENGINE_IFACE = "com.fs.starfarer.api.combat.CombatEngineAPI";
    /** Walked under ship.weapon.: a ship has a LIST of weapons (getAllWeapons), not a single getter,
     *  so the subject is SELECTED ({@link #selectPrimaryWeapon}: the first non-decorative BALLISTIC/ENERGY
     *  weapon, missile fallback) rather than declared in SHIP_NESTED. */
    private static final String WEAPON_IFACE = "com.fs.starfarer.api.combat.WeaponAPI";
    /** A base-less modifier bundle (hullmod range/armor/hull/system bonuses) — walked as sparse lanes only. */
    private static final String STAT_BONUS_CLASS = "com.fs.starfarer.api.combat.StatBonus";

    /**
     * Getters NEVER walked, by NAME — the name-level twin of
     * the type-level filter in {@link #captureInterface}. The once-per-JVM inventory still records
     * them ({@code skipped:noise}), so the audited walked-set stays honest about what was dropped
     * and why.
     *
     * <p>{@code getWingCRAtDeployment} is {@code wing.getLaunchCR()}, which NPEs for EVERY
     * non-fighter member ALWAYS — benign, zero defect signal. It is pure noise
     * with a cost on both sides of the ledger: a filled stack trace per non-carrier per walk on the
     * serve thread, and a DECOY in every exception census (11,883 strings;
     * 3185 of 3185 NPE hits) — the waiver
     * "36193 NPE = benign getWingCRAtDeployment dump" mis-attributed a real defect behind exactly
     * this hedge. Dropping the key makes the NEXT NPE in a census real.
     * Lockstep: the offline differ carries the matching {@code excluded} rule, and the
     * client half ({@code ClientParityWalker}) dropped its line in the same change.
     */
    private static final Set<String> SKIPPED_GETTERS = Set.of("getWingCRAtDeployment");

    /** Emit the field inventory (walked + skipped) exactly once per JVM, on the first walk tick. */
    private static final AtomicBoolean inventoryEmitted = new AtomicBoolean(false);

    /** Bounded handoff to the emitter daemon: at 2 walks/s a healthy emitter drains in ms. */
    private static final int EMIT_QUEUE_BOUND = 64;
    private static final ArrayBlockingQueue<CapturedWalk> EMIT_QUEUE =
            new ArrayBlockingQueue<>(EMIT_QUEUE_BOUND);
    private static final AtomicLong DROPPED_WALKS = new AtomicLong();
    private static final AtomicBoolean EMITTER_STARTED = new AtomicBoolean(false);

    /**
     * Per-classloader cache of (interface Class, filtered walkable Method[]) — the pre-split walker
     * re-ran {@code Class.forName} + {@code getMethods()} (a fresh copied array per call) for every
     * subject on every walk tick, ~200 metadata churns per walk on the serve thread. Values pin
     * their classloader (2 CLs process-wide: engine + fixtures) — acceptable for process lifetime.
     */
    private static final ConcurrentHashMap<ClassLoader, ConcurrentHashMap<String, CachedIface>>
            IFACE_CACHE = new ConcurrentHashMap<>();

    private ParityAuditWalker() { }

    /** One subject's captured (still unrendered) walk: field key -> immutable raw value. */
    private static final class CapturedSubject {
        final String subject;
        final String key;
        final List<Object[]> entries; // {String fieldKey, Object rawValue}
        CapturedSubject(String subject, String key, List<Object[]> entries) {
            this.subject = subject;
            this.key = key;
            this.entries = entries;
        }
    }

    /** One walk tick's full capture, handed to the emitter daemon. */
    private static final class CapturedWalk {
        final String side;
        final long tick;
        final List<CapturedSubject> subjects;
        final List<String[]> inventory; // non-null only on the once-per-JVM inventory carrier
        CapturedWalk(String side, long tick, List<CapturedSubject> subjects, List<String[]> inventory) {
            this.side = side;
            this.tick = tick;
            this.subjects = subjects;
            this.inventory = inventory;
        }
    }

    private static final class CachedIface {
        final Class<?> iface;
        final Method[] candidates; // zero-arg public get*/is*, non-bridge, non-static
        CachedIface(Class<?> iface, Method[] candidates) {
            this.iface = iface;
            this.candidates = candidates;
        }
    }

    /**
     * Walk the engine + each keyed ship at one synchronized snapshot tick and print one
     * {@code parity_walk} JSON line per subject. {@code keys[i]} is the wire identity of
     * {@code ships[i]} (the AWSS entityId, so the client half keys the same ships identically).
     * Reads happen here on the calling thread; rendering + stdout happen on the emitter daemon.
     * Never throws.
     */
    public static void walkTick(String side, long tick, Object engine, String[] keys, Object[] ships) {
        try {
            ensureEmitter();
            boolean carriesInventory = inventoryEmitted.compareAndSet(false, true);
            List<String[]> inventory = carriesInventory ? new ArrayList<>() : null;
            List<CapturedSubject> subjects = new ArrayList<>();
            if (engine != null) {
                List<Object[]> entries = new ArrayList<>();
                captureInterface(engine, ENGINE_IFACE, "engine.", entries, inventory);
                subjects.add(new CapturedSubject("engine", "engine", entries));
            }
            if (ships != null) {
                for (int s = 0; s < ships.length; s++) {
                    Object ship = ships[s];
                    if (ship == null) { continue; }
                    String key = (keys != null && s < keys.length && keys[s] != null) ? keys[s] : ("idx" + s);
                    List<Object[]> entries = new ArrayList<>();
                    captureInterface(ship, SHIP_IFACE, "ship.", entries, s == 0 ? inventory : null);
                    for (String[] nested : SHIP_NESTED) {
                        Object sub = tryInvokeByName(ship, SHIP_IFACE, nested[0]);
                        if (sub == null) {
                            entries.add(new Object[]{ "ship." + nested[1], null });
                        } else {
                            captureInterface(sub, nested[2], "ship." + nested[1] + ".", entries,
                                s == 0 ? inventory : null);
                        }
                    }
                    // The owned/foe weapon's cooldown phase. Selected from the LIST
                    // (getAllWeapons) rather than via SHIP_NESTED, then walked under ship.weapon. with the
                    // same reflective WeaponAPI contract — ship.weapon.getCooldownRemaining is the field the
                    // differ joins to size the own-discharge boundary offset (server-leads-client).
                    Object weapon = selectPrimaryWeapon(ship);
                    if (weapon == null) {
                        entries.add(new Object[]{ "ship.weapon", null });
                    } else {
                        captureInterface(weapon, WEAPON_IFACE, "ship.weapon.", entries,
                            s == 0 ? inventory : null);
                    }
                    subjects.add(new CapturedSubject("ship", key, entries));
                }
            }
            if (!EMIT_QUEUE.offer(new CapturedWalk(side, tick, subjects, inventory))) {
                DROPPED_WALKS.incrementAndGet();
                if (carriesInventory) {
                    // the inventory carrier was dropped — let a later walk re-collect and emit it
                    inventoryEmitted.set(false);
                }
            }
        } catch (Throwable ignore) {
            // the instrument must never break the gate loop
        }
    }

    private static void ensureEmitter() {
        if (EMITTER_STARTED.compareAndSet(false, true)) {
            Thread t = new Thread(ParityAuditWalker::emitLoop, "cc-parity-emit");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Emitter daemon: sort + render + println, one walk at a time, FIFO ⇒ rows stay in tick order. */
    private static void emitLoop() {
        long dropsReported = 0L;
        while (true) {
            CapturedWalk walk;
            try {
                walk = EMIT_QUEUE.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                for (CapturedSubject subject : walk.subjects) {
                    Map<String, String> fields = new TreeMap<>();
                    for (Object[] entry : subject.entries) {
                        fields.put((String) entry[0], renderScalar(entry[1]));
                    }
                    emit(walk.side, walk.tick, subject.subject, subject.key, fields);
                }
                if (walk.inventory != null) {
                    emitInventory(walk.side, walk.inventory);
                }
                long drops = DROPPED_WALKS.get();
                if (drops > dropsReported) {
                    System.out.println("{\"event\":\"parity_walk_dropped\",\"side\":"
                            + jsonString(walk.side) + ",\"total\":" + drops + "}");
                    dropsReported = drops;
                }
            } catch (Throwable ignore) {
                // per-walk fail-soft; the emitter must outlive any render surprise
            }
        }
    }

    /**
     * Capture every public zero-arg {@code get}/{@code is} method of the named interface (resolved via
     * the target's own classloader, so engine-implemented API objects resolve to the engine's API
     * classes) on {@code target} as (key, immutable raw value) entries. Values render off-thread via
     * {@link #renderScalar} into the exact bytes the pre-split walker produced. Non-walkable returns
     * are recorded in {@code inventory} (when collecting) and skipped.
     */
    private static void captureInterface(
            Object target, String ifaceName, String prefix,
            List<Object[]> out, List<String[]> inventory) {
        CachedIface cached = cachedIface(target.getClass().getClassLoader(), ifaceName);
        if (cached == null) {
            out.add(new Object[]{ prefix + "iface", "err:ClassNotFoundException" });
            return;
        }
        if (!cached.iface.isInstance(target)) {
            out.add(new Object[]{ prefix + "iface", "err:NotAnInstance" });
            return;
        }
        for (Method m : cached.candidates) {
            String name = m.getName();
            Class<?> rt = m.getReturnType();
            if (SKIPPED_GETTERS.contains(name)) {
                if (inventory != null) { inventory.add(new String[]{ prefix + name, rt.getName(), "skipped:noise" }); }
                continue;
            }
            if (isScalar(rt)) {
                out.add(new Object[]{ prefix + name, invokeSafe(m, target) });
            } else if ("org.lwjgl.util.vector.Vector2f".equals(rt.getName())) {
                Object v = invokeSafe(m, target);
                out.add(new Object[]{ prefix + name + ".x", vectorComponent(v, "x") });
                out.add(new Object[]{ prefix + name + ".y", vectorComponent(v, "y") });
                if (inventory != null) { inventory.add(new String[]{ prefix + name, rt.getName(), "walked-vector" }); }
                continue;
            } else if ("com.fs.starfarer.api.combat.MutableStat".equals(rt.getName())) {
                Object stat = invokeSafe(m, target);
                out.add(new Object[]{ prefix + name + ".mod", mutableStatModified(stat) });
                captureStatLanes(stat, prefix + name, out);
                name = name + ".mod";
            } else if (STAT_BONUS_CLASS.equals(rt.getName())) {
                // The 48 MutableShipStatsAPI StatBonus getters were "skipped" — so the
                // Escort Package range lane (StatBonus.modifyPercent on getBallisticWeaponRangeBonus +
                // getEnergyWeaponRangeBonus) was invisible to the
                // differ. A StatBonus has NO modified value, so it walks as SPARSE lanes only
                // (.pct/.flat/.mult, + .mods when attributed): absence on one side IS the signal, the
                // same law as the MutableStat lanes; a null/throwing getter lands as <key>.err.
                captureBonusLanes(invokeSafe(m, target), prefix + name, out);
                if (inventory != null) { inventory.add(new String[]{ prefix + name, rt.getName(), "walked-lanes" }); }
                continue;
            } else {
                if (inventory != null) { inventory.add(new String[]{ prefix + name, rt.getName(), "skipped" }); }
                continue;
            }
            if (inventory != null) { inventory.add(new String[]{ prefix + name, rt.getName(), "walked" }); }
        }
    }

    /** Resolve + filter an interface's walkable methods once per (classloader, name); null on failure. */
    private static CachedIface cachedIface(ClassLoader cl, String ifaceName) {
        ConcurrentHashMap<String, CachedIface> byName =
                cl == null ? null : IFACE_CACHE.computeIfAbsent(cl, k -> new ConcurrentHashMap<>());
        CachedIface hit = byName == null ? null : byName.get(ifaceName);
        if (hit != null) { return hit; }
        try {
            Class<?> iface = Class.forName(ifaceName, false, cl);
            List<Method> candidates = new ArrayList<>();
            for (Method m : iface.getMethods()) {
                String name = m.getName();
                if (m.getParameterCount() != 0 || m.isBridge() || Modifier.isStatic(m.getModifiers())
                        || !(name.startsWith("get") || name.startsWith("is"))) {
                    continue;
                }
                candidates.add(m);
            }
            CachedIface built = new CachedIface(iface, candidates.toArray(new Method[0]));
            if (byName != null) { byName.put(ifaceName, built); }
            return built;
        } catch (Throwable t) {
            return null; // not cached: same retry-per-walk behavior as the pre-split walker
        }
    }

    private static boolean isScalar(Class<?> rt) {
        return rt == float.class || rt == double.class || rt == int.class || rt == long.class
                || rt == short.class || rt == byte.class || rt == boolean.class || rt == char.class
                || rt == String.class || rt.isEnum()
                || rt == Float.class || rt == Double.class || rt == Integer.class || rt == Long.class
                || rt == Boolean.class;
    }

    /** Invoke a public interface getter; render any throwable as a sentinel object. */
    private static Object invokeSafe(Method m, Object target) {
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return new InvokeError(c.getClass().getSimpleName());
        }
    }

    private static final class InvokeError {
        final String kind;
        InvokeError(String kind) { this.kind = kind; }
    }

    /** A Vector2f component read ON-thread (the vector is live engine state) into an immutable value. */
    private static Object vectorComponent(Object vector, String fieldName) {
        if (vector == null || vector instanceof InvokeError) { return vector; }
        try {
            Field f = vector.getClass().getField(fieldName);
            return f.getFloat(vector);
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /** A MutableStat's live modified value read ON-thread into an immutable value. */
    private static Object mutableStatModified(Object stat) {
        if (stat == null || stat instanceof InvokeError) { return stat; }
        try {
            Method m = stat.getClass().getMethod("getModifiedValue");
            return m.invoke(stat);
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /**
     * Percent-lane fields: a measured coord-maneuvers desync lived entirely in
     * one stat's PERCENT lane (server Σpct=31 vs client 17.065 on maxSpeed) and cost two sessions of
     * log archaeology because walk rows carried only {@code .mod} (the collapsed modified value).
     * Every walked MutableStat now also emits, SPARSELY (all three maps empty ⇒ no fields — absence
     * is itself differ signal via the asymmetric-fields report): {@code .pct} = Σ percent-mod values,
     * {@code .flat} = Σ flat-mod values, {@code .mult} = Π mult-mod values. Stats named in
     * {@link #MODS_ATTRIBUTED} additionally emit {@code .mods} — the per-modifier {@code id:value}
     * attribution string (lanes sorted, ids sorted, fixed float rendering) that names the diverging
     * modifier directly. Reads stay ON-thread (serve-thread-confined live state) like
     * {@link #mutableStatModified}; all captured values are immutable. Fail-soft per stat.
     * The client half ({@code ClientParityWalker.putStat}) mirrors keys + rendering byte-for-byte.
     */
    private static void captureStatLanes(Object stat, String baseKey, List<Object[]> out) {
        if (stat == null || stat instanceof InvokeError) { return; }
        captureLanes(stat, baseKey, out, "getPercentMods", "getFlatMods", "getMultMods");
    }

    /**
     * The StatBonus twin of {@link #captureStatLanes} — same sparse lane keys and
     * rendering, read through StatBonus's own map getters ({@code get*Bonuses}); the null / err: case
     * is carried on {@code <key>.err} because a StatBonus has no {@code .mod} anchor to carry it.
     * The client half ({@code ClientParityWalker.putBonus}) mirrors keys + rendering byte-for-byte.
     */
    private static void captureBonusLanes(Object bonus, String baseKey, List<Object[]> out) {
        if (bonus == null || bonus instanceof InvokeError) {
            out.add(new Object[]{ baseKey + ".err", bonus });
            return;
        }
        captureLanes(bonus, baseKey, out, "getPercentBonuses", "getFlatBonuses", "getMultBonuses");
    }

    private static void captureLanes(Object stat, String baseKey, List<Object[]> out,
            String pctGetter, String flatGetter, String multGetter) {
        try {
            Map<?, ?> pct = statModMap(stat, pctGetter);
            Map<?, ?> flat = statModMap(stat, flatGetter);
            Map<?, ?> mult = statModMap(stat, multGetter);
            boolean pEmpty = pct == null || pct.isEmpty();
            boolean fEmpty = flat == null || flat.isEmpty();
            boolean mEmpty = mult == null || mult.isEmpty();
            if (pEmpty && fEmpty && mEmpty) { return; }
            out.add(new Object[]{ baseKey + ".pct", sumModValues(pct, false) });
            out.add(new Object[]{ baseKey + ".flat", sumModValues(flat, false) });
            out.add(new Object[]{ baseKey + ".mult", sumModValues(mult, true) });
            if (isModsAttributed(baseKey)) {
                out.add(new Object[]{ baseKey + ".mods", renderModsString(pct, flat, mult) });
            }
        } catch (Throwable ignore) {
            // lane capture is additive instrumentation — never degrade the base walk
        }
    }

    /** Getter names whose stats emit the per-modifier {@code .mods} attribution string.
     *  Default = the two known side-dynamics' target stats (coord maneuvers ⇒ maxSpeed percent lane;
     *  electronic warfare ⇒ the three weapon-range-bonus mult lanes) + the four movement stats
     *  (the SD/DEFECT-1 class hits accel/decel/turn hardest — server 150-vs-100 pct —
     *  and the cross-side modset bar needs them attributed); extend via the
     *  {@code cc.parity.walkMods} system property (comma-separated getter names) without a rebuild. */
    private static final Set<String> MODS_ATTRIBUTED = buildModsAttributed();

    private static Set<String> buildModsAttributed() {
        Set<String> names = new java.util.HashSet<>();
        String conf = System.getProperty("cc.parity.walkMods", "");
        if (conf.trim().isEmpty()) {
            // Lockstep: the client half falls back to CC_PARITY_WALK_MODS; an
            // env-only override used to gate .mods on one side only (asymmetric keys in the differ).
            String env = System.getenv("CC_PARITY_WALK_MODS");
            conf = env == null ? "" : env;
        }
        if (conf.trim().isEmpty()) {
            names.add("getMaxSpeed");
            names.add("getBallisticWeaponRangeBonus");
            names.add("getEnergyWeaponRangeBonus");
            names.add("getMissileWeaponRangeBonus");
            names.add("getAcceleration");
            names.add("getDeceleration");
            names.add("getMaxTurnRate");
            names.add("getTurnAcceleration");
        } else {
            for (String n : conf.split(",")) {
                if (!n.trim().isEmpty()) { names.add(n.trim()); }
            }
        }
        return names;
    }

    private static boolean isModsAttributed(String baseKey) {
        int dot = baseKey.lastIndexOf('.');
        return MODS_ATTRIBUTED.contains(dot >= 0 ? baseKey.substring(dot + 1) : baseKey);
    }

    /** Read one of the three public mod maps reflectively; null on any failure (fail-soft). */
    private static Map<?, ?> statModMap(Object stat, String getter) {
        try {
            Object m = stat.getClass().getMethod(getter).invoke(stat);
            return m instanceof Map ? (Map<?, ?>) m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Σ (or Π when {@code product}) of a mod map's StatMod.value fields, as an immutable Float.
     *  Accumulated in sorted-id order so float rounding is identical on both halves regardless of
     *  each end's HashMap insertion history. */
    private static Object sumModValues(Map<?, ?> mods, boolean product) {
        float acc = product ? 1.0F : 0.0F;
        if (mods != null && !mods.isEmpty()) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : mods.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            for (Object mod : sorted.values()) {
                float v = statModValue(mod);
                acc = product ? acc * v : acc + v;
            }
        }
        return acc;
    }

    private static float statModValue(Object mod) {
        if (mod == null) { return 0.0F; }
        try {
            return mod.getClass().getField("value").getFloat(mod);
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /**
     * The attribution string: {@code p[id:v,...]f[id:v,...]m[id:v,...]}, ids sorted within each lane,
     * values via {@code Double.toString((double) v)} — identical bytes to the client half whenever the
     * floats are identical, so an exact string mismatch pinpoints the diverging modifier by name.
     */
    private static Object renderModsString(Map<?, ?> pct, Map<?, ?> flat, Map<?, ?> mult) {
        StringBuilder sb = new StringBuilder(96);
        appendModsLane(sb, 'p', pct);
        appendModsLane(sb, 'f', flat);
        appendModsLane(sb, 'm', mult);
        return sb.toString();
    }

    private static void appendModsLane(StringBuilder sb, char lane, Map<?, ?> mods) {
        sb.append(lane).append('[');
        if (mods != null && !mods.isEmpty()) {
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : mods.entrySet()) {
                float v = statModValue(e.getValue());
                sorted.put(String.valueOf(e.getKey()), Double.toString((double) v));
            }
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) { sb.append(','); }
                first = false;
                sb.append(e.getKey()).append(':').append(e.getValue());
            }
        }
        sb.append(']');
    }

    /** Render a captured raw value as a JSON value (number, true/false, or quoted string). */
    private static String renderScalar(Object v) {
        if (v == null) { return "\"null\""; }
        if (v instanceof InvokeError) { return jsonString("err:" + ((InvokeError) v).kind); }
        if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) { return "\"NaN\""; }
            if (Double.isInfinite(d)) { return d > 0 ? "\"Inf\"" : "\"-Inf\""; }
            return String.valueOf(d);
        }
        if (v instanceof Number) { return String.valueOf(v); }
        if (v instanceof Boolean) { return String.valueOf(v); }
        return jsonString(String.valueOf(v)); // String, enum, char
    }

    /**
     * The weapon walked under {@code ship.weapon.}: the first non-decorative weapon in
     * {@code getAllWeapons()} whose {@code getType()} is BALLISTIC or ENERGY, else the first
     * non-decorative weapon of any type (the earlier rule). A ship carries a LIST of weapons, so the
     * subject is selected here rather than declared in {@link #SHIP_NESTED}; the client half mirrors
     * this exact selection so the differ joins the same weapon. Reflection-only, fail-soft:
     * {@code null} (-> {@code ship.weapon="null"}) when the list is empty/unreadable.
     *
     * <p>Why the type rule: the old first-non-decorative rule picked a
     * MISSILE on both escort hulls (grendel/conquest heatseeker), so {@code ship.weapon.getRange} agreed
     * at every joined tick while the Escort Package range lane — which modifies ballistic + energy
     * range only — went unmeasured. Weapon-list order is variant order, identical both sides.
     */
    private static Object selectPrimaryWeapon(Object ship) {
        try {
            ClassLoader cl = ship.getClass().getClassLoader();
            Class<?> shipIface = Class.forName(SHIP_IFACE, false, cl);
            Object weapons = shipIface.getMethod("getAllWeapons").invoke(ship);
            if (!(weapons instanceof List)) { return null; }
            Class<?> wIface = Class.forName(WEAPON_IFACE, false, cl);
            Method isDecorative = wIface.getMethod("isDecorative");
            Method getType = wIface.getMethod("getType");
            Object fallback = null;
            for (Object w : (List<?>) weapons) {
                if (w == null) { continue; }
                Object dec = isDecorative.invoke(w);
                if (!(dec instanceof Boolean) || ((Boolean) dec)) { continue; }
                String type = weaponTypeName(getType, w);
                if ("BALLISTIC".equals(type) || "ENERGY".equals(type)) { return w; }
                if (fallback == null) { fallback = w; }
            }
            return fallback;
        } catch (Throwable ignore) {
            // fail-soft: weapon unreadable -> ship.weapon = "null"
        }
        return null;
    }

    /** {@code WeaponAPI.getType().name()}; "" when unreadable (a type-less mount is never preferred). */
    private static String weaponTypeName(Method getType, Object weapon) {
        try {
            Object type = getType.invoke(weapon);
            return type instanceof Enum ? ((Enum<?>) type).name() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static Object tryInvokeByName(Object target, String ifaceName, String methodName) {
        try {
            Class<?> iface = Class.forName(ifaceName, false, target.getClass().getClassLoader());
            return iface.getMethod(methodName).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** One subject's walk as a single stdout JSON line (the harness lands stdout in events.ndjson). */
    private static void emit(String side, long tick, String subject, String key, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder(fields.size() * 40 + 128);
        sb.append("{\"event\":\"parity_walk\",\"side\":").append(jsonString(side))
          .append(",\"tick\":").append(tick)
          .append(",\"subject\":").append(jsonString(subject))
          .append(",\"key\":").append(jsonString(key))
          .append(",\"n\":").append(fields.size())
          .append(",\"fields\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) { sb.append(','); }
            first = false;
            sb.append(jsonString(e.getKey())).append(':').append(e.getValue());
        }
        sb.append("}}");
        System.out.println(sb);
    }

    /** The once-per-JVM field inventory: every getter seen, walked or skipped — the generator seed. */
    private static void emitInventory(String side, List<String[]> inventory) {
        StringBuilder sb = new StringBuilder(inventory.size() * 64 + 128);
        sb.append("{\"event\":\"parity_walk_inventory\",\"side\":").append(jsonString(side))
          .append(",\"n\":").append(inventory.size())
          .append(",\"entries\":[");
        for (int i = 0; i < inventory.size(); i++) {
            String[] e = inventory.get(i);
            if (i > 0) { sb.append(','); }
            sb.append("{\"field\":").append(jsonString(e[0]))
              .append(",\"type\":").append(jsonString(e[1]))
              .append(",\"disposition\":").append(jsonString(e[2])).append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private static String jsonString(String s) {
        if (s == null) { return "\"null\""; }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) { sb.append(String.format("\\u%04x", (int) c)); }
                    else { sb.append(c); }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
