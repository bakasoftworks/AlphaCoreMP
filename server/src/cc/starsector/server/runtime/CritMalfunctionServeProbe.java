package cc.starsector.server.runtime;

import cc.starsector.server.protocol.GameplayEdgeEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * CRIT_MALFUNCTION(10) serve probe (GPEE schema v14).
 * //
 *
 * <p>WHY: a critical malfunction is an engine-side roll on the authoritative body (a weapon or
 * engine dies, possibly permanently, plus a hull loss) that no other lane names; the client must
 * replay vanilla's own crit script from a server fact, never roll a second time locally.
 *
 * <p>Seam: the engine's crit roll is NESTED inside the ordinary
 * weapon/engine malfunction roll; at EVERY crit
 * decision it asks the {@code CombatReadinessPlugin} singleton
 * {@code isOkToPermanentlyDisable(ship, module)} (the ONLY caller tree-wide), then
 * runs {@code Ship.applyCriticalMalfunction(module, perm)}. That call
 * is the one engine-native seam that names the module. The singleton lives in the UNIQUE
 * {@code private static CombatReadinessPlugin} field of {@code StarfarerSettings},
 * served through a lazy static getter; every caller goes through the getter.
 * The probe resolves both BY TYPE (unique static field + unique public static no-arg getter
 * returning the interface), forces the engine's own lazy load, and swaps the field for a
 * {@link Proxy} over the public API interface that DELEGATES every method to the real plugin and,
 * on {@code isOkToPermanentlyDisable}, captures {@code (ship, module, permResult, hullBefore)}.
 * The engine keeps its own roll + {@code applyCriticalMalfunction} (single owner). Marker-dedupe
 * on {@code toString} (a re-minted probe on the same JVM adopts, never double-wraps); readback
 * through the getter; fail-soft quiet ({@link ServeProbeSupport.FailSoft}).
 *
 * <p>Branch + amount at drain (honest not exact): dead/hulk =&gt; LETHAL (amount 0 -
 * the kill lane owns the death); a SELF-sourced damage report in the {@link HullSourceLedger}
 * window =&gt; DAMAGE (the engine's {@code applyDamage(ENERGY)} branch; the TELL is the report's
 * hull+armor, the AMOUNT is its HULL part only - the client presents DAMAGE through
 * vanilla's floaty + direct {@code setHitpoints} door, so the wire number is the hull the server
 * engine took; the armor part is the armor-cell lane's); else DIRECT (the {@code setHitpoints} branch, amount = hull delta, same-tick hits
 * contaminate it - the hull-reconcile lane folds the residual). The drain MUST run before the
 * binding's {@code logHullDrops} (whose {@code endTick()} clears the ledger window).
 *
 * <p>Bypass residue: {@code LowCRShipDamageSequence} (the deploy-under-20 % CR burst) uses
 * the STATIC {@code isOkToPermanentlyDisableStatic} twin + the 1-arg {@code applyCriticalMalfunction}
 * and is NOT observed. The proxy stays installed for the serve JVM's lifetime.
 *
 * <p>Threading: the serve loop and the engine advance share one thread (the arc probe's law), so
 * {@link #pending} is unsynchronized on purpose.
 *
 * <p>Per-capture isolation + test seam: one throwing reflective read on
 * one capture costs THAT capture only ({@link #captureFailedCount}, logged once per distinct
 * message) - never the tick's other crits nor a fail-soft strike. The ship-side reads go through
 * {@link ShipReflection} (live = the install-resolved {@code Method} handles, byte-identical) so
 * the game-free contract rows can drive {@link #drain} via {@link #forTest}.
 *
 */
public final class CritMalfunctionServeProbe {

    /** {@code toString()} of the installed proxy — the dedupe key on (re)install. */
    public static final String MARKER = "cc-crit-malfunction-plugin";
    static final String SETTINGS_CLASS = "com.fs.starfarer.settings.StarfarerSettings";
    static final String PLUGIN_IFACE = "com.fs.starfarer.api.combat.CombatReadinessPlugin";
    static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    static final String WEAPON_API = "com.fs.starfarer.api.combat.WeaponAPI";
    static final String ENGINE_API = "com.fs.starfarer.api.combat.ShipEngineControllerAPI$ShipEngineAPI";
    static final String ENGINE_CTRL_API = "com.fs.starfarer.api.combat.ShipEngineControllerAPI";
    static final String DISABLE_METHOD = "isOkToPermanentlyDisable";
    /** First-N emits logged as rows, then meters only. */
    static final int LOG_MAX = 20;

    private final ServeProbeSupport.FailSoft failSoft = new ServeProbeSupport.FailSoft("crit-malfunction");
    private boolean installed;
    private Object real;
    private Field cacheField;
    private Method getter;
    private Delegating handler;
    private Class<?> weaponApi;
    private Class<?> engineApi;
    private Method getHitpoints;
    private Method isHulk;
    private Method getAllWeapons;
    private Method getEngineController;
    private Method getShipEngines;

    /** Sim-thread captures awaiting the serve-loop drain (take-and-clear). */
    private final List<Raw> pending = new ArrayList<>();
    private long emitted;
    private long skipUnresolved;
    private long skipModule;
    private long captureErrors;
    private int logRows;
    // per-capture isolation meter + its once-per-message log.
    /** Distinct capture-failure messages logged at most (the TerrainStatSeam.failuresLogged shape). */
    static final int CAPTURE_FAIL_LOG_MAX = 64;
    private long captureFailed;
    private final Set<String> captureFailuresLogged = new HashSet<>();
    /**
     * Hull reads that folded to NaN (the capture's pre-crit read + the live {@link #readHull}) -
     * a NaN side makes the DIRECT amount 0, so an uncounted miss was a silently lost hull number.
     * Round line {@code crit_malfunction_hull_read_miss=}.
     *
     */
    private long hullReadMiss;
    /** The ship-side reads; live = the install-resolved handles, replaced only by {@link #forTest}. */
    private ShipReflection reflection = new LiveReflection();

    /** The raw capture: engine refs + the plugin's answer + the pre-crit hull. */
    record Raw(Object ship, Object module, boolean permanent, float hullBefore) {
    }

    /**
     * The drain's ship-side reads, factored so the game-free rows (no api jar on the test
     * classpath) can drive {@link #drain} without FQCN resolution. Public only because the rows
     * live in {@code server.tests} (the {@link #handlerFor} precedent). {@code hull} may throw on a
     * fake; the live one folds a read miss to NaN exactly as before.
     *
     */
    public interface ShipReflection {
        boolean isWeapon(Object module);

        boolean isEngine(Object module);

        List<?> weapons(Object ship) throws Exception;

        /** The engine list, or {@code null} when the ship has no engine controller. */
        List<?> engines(Object ship) throws Exception;

        boolean isHulk(Object ship) throws Exception;

        float hull(Object ship) throws Exception;
    }

    /** Live {@link ShipReflection}: today's resolved handles, same calls in the same order.
     * */
    private final class LiveReflection implements ShipReflection {
        @Override
        public boolean isWeapon(Object module) {
            return weaponApi.isInstance(module);
        }

        @Override
        public boolean isEngine(Object module) {
            return engineApi.isInstance(module);
        }

        @Override
        public List<?> weapons(Object ship) throws Exception {
            return (List<?>) getAllWeapons.invoke(ship);
        }

        @Override
        public List<?> engines(Object ship) throws Exception {
            Object ctrl = getEngineController.invoke(ship);
            return ctrl == null ? null : (List<?>) getShipEngines.invoke(ctrl);
        }

        @Override
        public boolean isHulk(Object ship) throws Exception {
            return Boolean.TRUE.equals(isHulk.invoke(ship));
        }

        @Override
        public float hull(Object ship) {
            return readHull(ship);
        }
    }

    /**
     * Test factory (game-free rows): a probe marked installed over {@code r}, WITHOUT touching the
     * {@code CombatReadinessPlugin} field; the live ordering + fail-soft law are unchanged.
     *
     */
    public static CritMalfunctionServeProbe forTest(ShipReflection r) {
        CritMalfunctionServeProbe p = new CritMalfunctionServeProbe();
        p.reflection = r;
        p.installed = true;
        return p;
    }

    /** Test seam: the live handler's capture entry ({@code args = (ship, module)}, {@code result}
     *  = the plugin's answer). */
    public void captureForTest(Object[] args, Object result) {
        capture(args, result);
    }

    /** Test seam: {@link #drain} over fixed maps. */
    public List<GameplayEdgeEvent> drainForTest(long simTick, Map<Object, Long> ships,
            Map<Long, Long> wireIds, HullSourceLedger hullSources) {
        return drain(simTick, () -> ships, () -> wireIds, hullSources);
    }

    /**
     * Test seam for the install ADOPT path: {@code prior == null} mints a fresh
     * {@link Delegating} over {@code real} sinking into this probe (the install branch);
     * otherwise {@code prior} must be ours and is re-pointed here (the adopt branch, via the SAME
     * {@link #adopt} helper). Returns the owning handler.
     *
     */
    public InvocationHandler adoptForTest(Object real, InvocationHandler prior) {
        if (prior == null) {
            handler = new Delegating(real, this::capture);
        } else {
            adopt((Delegating) prior);
        }
        return handler;
    }

    /** Test seam: fail-soft strikes so far. */
    public int failSoftLinesForTest() {
        return failSoft.lines();
    }

    /** Test seam: captures awaiting a drain. */
    public int pendingCountForTest() {
        return pending.size();
    }

    /**
     * The DELEGATING proxy handler (not {@link ServeProbeSupport.ListenerHandler}, which answers
     * null for every method): the Object trio is answered here ({@code toString} = {@link #MARKER},
     * identity {@code hashCode}/{@code equals}); {@code isOkToPermanentlyDisable(ship, module)}
     * delegates FIRST, then hands {@code (args, result)} to {@code onCrit} inside its own
     * try/catch (a capture failure never breaks the engine call — counted); every other method
     * delegates with {@link InvocationTargetException} unwrapped so the engine sees the real
     * plugin's behaviour unchanged.
     */
    static final class Delegating implements InvocationHandler {
        private final Object real;
        /** Re-pointed on ADOPT: the serve re-mints the probe every round (mintRound) while the
         *  proxy lives for the JVM, so the newest probe must own the capture sink. */
        private BiConsumer<Object[], Object> onCrit;
        private long captureErrors;

        Delegating(Object real, BiConsumer<Object[], Object> onCrit) {
            this.real = real;
            this.onCrit = onCrit;
        }

        void repoint(BiConsumer<Object[], Object> sink) {
            this.onCrit = sink;
        }

        long captureErrors() {
            return captureErrors;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            int n = args == null ? 0 : args.length;
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(name) && n == 0) {
                    return MARKER;
                }
                if ("hashCode".equals(name) && n == 0) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name) && n == 1) {
                    return proxy == args[0];
                }
            }
            Object result;
            try {
                result = method.invoke(real, args);
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause() != null ? wrapped.getCause() : wrapped;
            }
            if (DISABLE_METHOD.equals(name) && n == 2) {
                try {
                    onCrit.accept(args, result);
                } catch (Throwable captureFailure) {
                    captureErrors++;
                }
            }
            return result;
        }
    }

    /** Test seam (game-free contract rows in {@code server.tests}, hence public): the SAME
     *  handler class the live proxy uses; {@code onCrit} receives {@code (args, result)}. */
    public static InvocationHandler handlerFor(Object real, BiConsumer<Object[], Object> onCrit) {
        return new Delegating(real, onCrit);
    }

    /** The sim-thread capture: pre-crit hull read (NaN on failure) + one pending row. */
    private void capture(Object[] args, Object result) {
        Object ship = args[0];
        float hullBefore = Float.NaN;
        try {
            // Through the seam; live = the same getHitpoints read.
            hullBefore = reflection.hull(ship);
        } catch (Throwable readMiss) {
            hullBefore = Float.NaN;
            hullReadMiss++; // No silent NaN
        }
        pending.add(new Raw(ship, args[1], Boolean.TRUE.equals(result), hullBefore));
    }

    /**
     * Serve loop, once per tick, AFTER the engine step and BEFORE {@code logHullDrops} (the
     * ledger window closes there): lazily installs on the first tick with a live ship, then
     * takes-and-clears this tick's captures, resolves the victim wire id through the sibling
     * probes' supplier pair, the module code from the ship's own weapon/engine lists, and the
     * branch + amount law ({@link #classifyBranch}/{@link #directAmount}). The ship map is read
     * ONLY on a tick with captures (or before install) — zero extra roster reads otherwise.
     */
    List<GameplayEdgeEvent> drain(long simTick, Supplier<Map<Object, Long>> shipsSupplier,
            Supplier<Map<Long, Long>> wireIdByPositionShipId, HullSourceLedger hullSources) {
        if (failSoft.quiet()) {
            pending.clear();
            return List.of();
        }
        try {
            if (installed && pending.isEmpty()) {
                return List.of();
            }
            Map<Object, Long> ships = shipsSupplier.get();
            if (!installed) {
                Object anyShip = firstShip(ships);
                if (anyShip == null) {
                    return List.of();
                }
                install(simTick, anyShip);
            }
            if (pending.isEmpty()) {
                return List.of();
            }
            List<Raw> taken = new ArrayList<>(pending);
            pending.clear();
            Map<Long, Long> wireIds = wireIdByPositionShipId.get();
            List<GameplayEdgeEvent> out = new ArrayList<>(taken.size());
            Map<Object, Boolean> amountPaid = new IdentityHashMap<>();
            for (Raw raw : taken) {
                // per-capture isolation - a throwing read on
                // THIS capture costs this capture only (captureFailed + a once-per-message log),
                // never the tick's other crits nor a fail-soft strike. The outer catch keeps the
                // supplier/roster/install failures.
                try {
                    long victim = ServeProbeSupport.wireIdOf(raw.ship(), ships, wireIds);
                    if (victim <= 0L) {
                        skipUnresolved++;
                        continue;
                    }
                    Object module = raw.module();
                    boolean engine;
                    int ordinal;
                    if (module != null && reflection.isWeapon(module)) {
                        engine = false;
                        ordinal = identityIndexOf(reflection.weapons(raw.ship()), module);
                    } else if (module != null && reflection.isEngine(module)) {
                        engine = true;
                        ordinal = identityIndexOf(reflection.engines(raw.ship()), module);
                    } else {
                        skipModule++;
                        continue;
                    }
                    int max = engine ? GameplayEdgeEvent.CRIT_ENGINE_ORDINAL_MAX : GameplayEdgeEvent.CRIT_WEAPON_ORDINAL_MAX;
                    if (ordinal < 0 || ordinal > max) {
                        skipModule++;
                        continue;
                    }
                    int code = GameplayEdgeEvent.critModuleCode(engine, ordinal);
                    float hullAfter = reflection.hull(raw.ship());
                    boolean dead = reflection.isHulk(raw.ship())
                            || (Float.isFinite(hullAfter) && hullAfter <= 0f);
                    // The branch TELL stays hull+armor (selfReportedDamage); the DAMAGE AMOUNT is
                    // the hull part only (the armor part is the
                    // armor-cell lane's; the client presents DAMAGE via the setHitpoints door).
                    float self = dead || hullSources == null ? 0f : hullSources.selfReportedDamage(raw.ship());
                    int branch = classifyBranch(dead, self, raw.hullBefore(), hullAfter);
                    // Payment AFTER ship
                    // (sum-code-crit-r1r2.md:21-23) - the first-carries mark is only READ here and
                    // committed by shipAndPay once out.add succeeded, so a throw in the amount reads
                    // or the factory no longer zeroes the next same-ship crit in this drain.
                    boolean carries = branch != GameplayEdgeEvent.CRIT_BRANCH_LETHAL
                            && !amountPaid.containsKey(raw.ship());
                    float amount;
                    if (!carries) {
                        amount = 0f; // LETHAL, or a second crit on the same ship this drain: the first carried it
                    } else if (branch == GameplayEdgeEvent.CRIT_BRANCH_DAMAGE) {
                        amount = hullSources.selfReportedHullDamage(raw.ship());
                    } else {
                        amount = directAmount(raw.hullBefore(), hullAfter);
                    }
                    amount = Float.isFinite(amount) && amount > 0f
                            ? Math.min(amount, GameplayEdgeEvent.CRIT_AMOUNT_CEIL) : 0f;
                    shipAndPay(out, GameplayEdgeEvent.critMalfunction(simTick, victim, code, raw.permanent(),
                            branch, amount), amountPaid, raw.ship(), carries);
                    emitted++;
                    if (logRows < LOG_MAX) {
                        logRows++;
                        System.out.println("crit-malfunction victim=" + victim
                                + " module=" + (engine ? "engine:" : "weapon:") + ordinal
                                + " perm=" + (raw.permanent() ? 1 : 0)
                                + " branch=" + branchName(branch)
                                + " amount=" + amount
                                + " hull=" + raw.hullBefore() + "->" + hullAfter
                                + " tick=" + simTick
                                + (logRows == LOG_MAX ? " [crit-malfunction row cap; meters only from here]" : ""));
                    }
                } catch (Throwable perCapture) {
                    captureFailed++;
                    logCaptureFailure(simTick, perCapture);
                }
            }
            return out;
        } catch (Throwable failure) {
            pending.clear();
            failSoft.fail("drain", simTick, failure);
            return List.of();
        }
    }

    /**
     * Commit-on-ship: add {@code event} to {@code out} FIRST, then - only if
     * the add returned and this capture {@code carries} the ship's amount - mark {@code ship} paid.
     * A throwing add leaves {@code amountPaid} untouched (the payment never shipped). Public for
     * the game-free rows in {@code server.tests}.
     *
     */
    public static void shipAndPay(List<GameplayEdgeEvent> out, GameplayEdgeEvent event,
            Map<Object, Boolean> amountPaid, Object ship, boolean carries) {
        out.add(event);
        if (carries) {
            amountPaid.put(ship, Boolean.TRUE);
        }
    }

    /** Branch law: dead =&gt; LETHAL; a self-sourced report =&gt; DAMAGE; else DIRECT. */
    public static int classifyBranch(boolean dead, float self, float hullBefore, float hullAfter) {
        if (dead) {
            return GameplayEdgeEvent.CRIT_BRANCH_LETHAL;
        }
        if (Float.isFinite(self) && self > 0f) {
            return GameplayEdgeEvent.CRIT_BRANCH_DAMAGE;
        }
        return GameplayEdgeEvent.CRIT_BRANCH_DIRECT;
    }

    /** DIRECT amount: {@code max(0, before - after)}; either side non-finite =&gt; 0. */
    public static float directAmount(float hullBefore, float hullAfter) {
        if (!Float.isFinite(hullBefore) || !Float.isFinite(hullAfter)) {
            return 0f;
        }
        return Math.max(0f, hullBefore - hullAfter);
    }

    /** {@code " crit_malfunction_emitted=N crit_malfunction_skip=unresolved/module/captureErrors
     *  crit_malfunction_capture_failed=N"} (the last token appended later -
     *  the existing tokens keep their shape); {@code crit_malfunction_hull_read_miss=N} appended after
     *  that. Public: the rows in {@code server.tests} read it.
     * */
    public String roundTokens() {
        return " crit_malfunction_emitted=" + emitted + " crit_malfunction_skip=" + skipUnresolved
                + "/" + skipModule + "/" + captureErrorCount()
                + " crit_malfunction_capture_failed=" + captureFailed //
                + " crit_malfunction_hull_read_miss=" + hullReadMiss; //
    }

    /** NaN-folded hull reads (cumulative). */
    public long hullReadMissCount() {
        return hullReadMiss;
    }

    long emitCount() {
        return emitted;
    }

    long skipUnresolvedCount() {
        return skipUnresolved;
    }

    /** Public: the drain-law rows in {@code server.tests} read it. */
    public long skipModuleCount() {
        return skipModule;
    }

    /** Captures lost to a per-capture throw in {@link #drain} (cumulative).
     * */
    public long captureFailedCount() {
        return captureFailed;
    }

    /** Once per distinct {@code String.valueOf(failure)}, capped at {@link #CAPTURE_FAIL_LOG_MAX}
     *  distinct messages (the meter keeps counting past the cap).
     * */
    private void logCaptureFailure(long simTick, Throwable failure) {
        String key = String.valueOf(failure);
        if (captureFailuresLogged.size() < CAPTURE_FAIL_LOG_MAX && captureFailuresLogged.add(key)) {
            System.out.println("crit-malfunction capture FAIL tick=" + simTick + " failed=" + captureFailed
                    + " " + key
                    + (captureFailuresLogged.size() == CAPTURE_FAIL_LOG_MAX
                            ? " [capture-fail log cap; meter only from here]" : ""));
        }
    }

    long captureErrorCount() {
        return captureErrors + (handler == null ? 0L : handler.captureErrors());
    }

    boolean isInstalled() {
        return installed;
    }

    /** One-time reflective resolution + the singleton swap (marker-dedupe first). */
    private void install(long simTick, Object anyShip) throws Exception {
        ClassLoader loader = anyShip.getClass().getClassLoader();
        Class<?> settings = Class.forName(SETTINGS_CLASS, true, loader);
        Class<?> iface = Class.forName(PLUGIN_IFACE, false, loader);
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        weaponApi = Class.forName(WEAPON_API, false, loader);
        engineApi = Class.forName(ENGINE_API, false, loader);
        Class<?> engineCtrlApi = Class.forName(ENGINE_CTRL_API, false, loader);
        getHitpoints = shipApi.getMethod("getHitpoints");
        isHulk = shipApi.getMethod("isHulk");
        getAllWeapons = shipApi.getMethod("getAllWeapons");
        getEngineController = shipApi.getMethod("getEngineController");
        getShipEngines = engineCtrlApi.getMethod("getShipEngines");
        Method foundGetter = null;
        int getters = 0;
        for (Method m : settings.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) && Modifier.isPublic(mod) && m.getParameterCount() == 0
                    && m.getReturnType() == iface) {
                foundGetter = m;
                getters++;
            }
        }
        if (getters != 1) {
            throw new IllegalStateException("plugin getter not unique by type: " + getters);
        }
        Field foundField = null;
        int fields = 0;
        for (Field f : settings.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == iface) {
                foundField = f;
                fields++;
            }
        }
        if (fields != 1) {
            throw new IllegalStateException("plugin cache field not unique by type: " + fields);
        }
        foundGetter.setAccessible(true);
        foundField.setAccessible(true);
        getter = foundGetter;
        cacheField = foundField;
        getter.invoke(null); // forces the engine's own lazy load
        Object current = cacheField.get(null);
        if (current != null && MARKER.equals(String.valueOf(current))) {
            // A prior probe on this JVM (the previous round's mint) already wrapped it: adopt,
            // never double-wrap, and re-point its capture sink HERE so this round's drain sees
            // the crits. A foreign-loader handler (not ours) cannot be re-pointed: fail-soft.
            InvocationHandler prior = Proxy.isProxyClass(current.getClass())
                    ? Proxy.getInvocationHandler(current) : null;
            if (!(prior instanceof Delegating)) {
                throw new IllegalStateException("marker plugin is not our delegating proxy: " + prior);
            }
            adopt((Delegating) prior);
            installed = true;
            System.out.println("crit-malfunction probe adopted tick=" + simTick
                    + " field=" + cacheField.getName() + " getter=" + getter.getName());
            return;
        }
        if (current == null) {
            throw new IllegalStateException("plugin cache field null after the getter's lazy load");
        }
        real = current;
        Delegating h = new Delegating(real, this::capture);
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{iface}, h);
        cacheField.set(null, proxy);
        if (getter.invoke(null) != proxy) {
            cacheField.set(null, real);
            throw new IllegalStateException("plugin getter readback did not return the proxy (restored)");
        }
        handler = h;
        installed = true;
        System.out.println("crit-malfunction probe installed tick=" + simTick
                + " field=" + cacheField.getName() + " getter=" + getter.getName());
    }

    /** The ADOPT step (live install + {@link #adoptForTest}): own the prior handler, sink here.
     * */
    private void adopt(Delegating prior) {
        handler = prior;
        handler.repoint(this::capture);
    }

    private float readHull(Object ship) {
        try {
            return ServeProbeSupport.floatOf(getHitpoints.invoke(ship));
        } catch (Throwable miss) {
            hullReadMiss++; // No silent NaN
            return Float.NaN;
        }
    }

    private static int identityIndexOf(List<?> list, Object item) {
        if (list == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == item) {
                return i;
            }
        }
        return -1;
    }

    private static String branchName(int branch) {
        switch (branch) {
            case GameplayEdgeEvent.CRIT_BRANCH_DAMAGE:
                return "damage";
            case GameplayEdgeEvent.CRIT_BRANCH_LETHAL:
                return "lethal";
            default:
                return "direct";
        }
    }

    private static Object firstShip(Map<Object, Long> ships) {
        if (ships == null) {
            return null;
        }
        for (Object ship : ships.keySet()) {
            if (ship != null) {
                return ship;
            }
        }
        return null;
    }
}
