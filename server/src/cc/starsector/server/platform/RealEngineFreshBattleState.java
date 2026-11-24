package cc.starsector.server.platform;

import cc.starsector.server.names.EngineRow;
import cc.starsector.server.names.EngineSymContentInit;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state a NEW engine opens a battle with, given to every round the serve opens on its ONE long-lived engine:
 * the round heap drain's correctives 6 and 7.
 *
 * <p><b>Why.</b> The windowed game builds a new {@code CombatEngine} for each battle, so two things are new every
 * time with no statement that resets them. (1) The engine's field-initialised stores: the custom-data map, its
 * timeout twin, the two corner-cheese trackers and the listener manager; a battle's end also
 * assigns a new custom-data map. (2) The script plugins: {@code setCombatMap} adds one NEW instance
 * of every every-frame plugin class the script store knows,
 * and {@code init()} then initialises them and the store's other engine plugins. A serve builds
 * the engine once and resets the roster in place, so both rode from each warm-up pass and round into the next.
 *
 * <p><b>What that carried</b> (the heap dump and the reads). Heap: the two skill scripts'
 * {@code needsCleanup} sets and two custom-data entries were the whole holder list of every dead round's fleet
 * managers, and through them of its deployed records and Ships (8 stale managers after four roster installs; with
 * those holders cut the reader finds the 2 live ones). Sim: a custom-data key the game writes once a battle was
 * written once a PROCESS: the Phase Anchor's one emergency dive,
 * the admiral's doubled deployment target once it has seen phase cheese,
 * a minefield whose dead source Ship stopped every later round's mines;
 * and a plugin instance carried its fields (the neural link's person).
 *
 * <p><b>What it does, by the engine's own statements.</b> {@link #freshStores()} assigns each DATA store's field a
 * new instance of its own class, the field initialiser a new engine runs. {@link #remintPlugins(Set)} removes every
 * script plugin on the combat map through the engine's public {@code removePlugin}, hands the script store's
 * newly minted every-frame set to the public {@code addPlugin} (the same wrap and add as {@code setCombatMap},
 * then {@code init}), and calls {@code init} on the store's newly minted engine plugins that are not every-frame
 * ones, as {@code init()} does. The stores go first: a new plugin's {@code init} may write custom data.
 *
 * <p><b>The listener manager rides the plugin half</b> (the lens's [A F1]). It is
 * the one store whose content is made by the plugins' {@code init}, so it is new only on a reset that re-runs
 * every registrar on new state: the re-mint is past its pre-read (new instances WILL be added) and
 * {@link #freshStores()} has just renewed every data store (a script may guard its one registration on a
 * custom-data key). Otherwise the manager is HELD with its listeners: a reset whose plugin half is off, whose mint
 * threw, or that kept a data store keeps the instances or the guard, and a new empty manager under them would drop
 * their listeners for the rest of the process with no row saying so.
 *
 * <p><b>No engine name but one.</b> A store's field is found by IDENTITY (the declared field holding the object
 * its public getter returns), the way the drain finds the fog map; the plugin calls are public api. The one
 * engine-name row is the script store's mint call.
 *
 * <p><b>Fail-soft, like the drain it rides.</b> A store that does not resolve is left alone and the others still
 * run. The plugin half mints BEFORE it touches the engine: a throw there changes nothing and hands the round back
 * to the drain's old sweep (script-added plugins only). Past that point every step is tried on its own, so one
 * plugin whose {@code init} throws costs that plugin alone; the row counts it and the half then stays off. Under
 * the drain's master switch ({@code cc.predict.correctivesOff}), with no knob of its own.
 */
public final class RealEngineFreshBattleState {

    /**
     * The public getters of the DATA stores a new engine news in a field initialiser:
     * {@link #freshStores()} renews exactly these.
     */
    private static final String[] STORE_GETTERS = {
        "getCustomData", "getCustomDataWithTimeout", "getRecentCornerCheeseTargets", "getVeryRecentCornerCheeseTargets"};

    /**
     * The listener manager's getter, a fifth field-initialised store
     * named apart because {@link #remintPlugins(Set)} renews it, never {@link #freshStores()} (the
     * class javadoc says when).
     *
     * <p>Why it is renewed at all: the re-mint runs {@code init} on a NEW instance of
     * every script plugin each round, so a mod plugin that registers an engine listener in {@code init} gained one
     * live registration a round on a kept manager (each replaced instance still called back and still held), where
     * the game holds one a battle. A new manager with the new instances drops them all with the round, and with
     * them the previous round's arc-hit listeners (the probe is minted per round and installs on its first tick,
     * after this reset). Nothing in the engine registers on it [grep: the RC8 mirror's {@code getListenerManager()}
     * callers under combat/ are the getter and the per-SHIP managers].
     */
    private static final String LISTENER_MANAGER_GETTER = "getListenerManager";

    private static final String EVERY_FRAME_PLUGIN_INTERFACE = "com.fs.starfarer.api.combat.EveryFrameCombatPlugin";
    private static final String ENGINE_PLUGIN_INTERFACE = "com.fs.starfarer.api.combat.CombatEnginePlugin";

    private final Object engine;

    private final List<Field> storeFields = new ArrayList<>();
    private final List<Constructor<?>> storeCtors = new ArrayList<>();
    private final List<String> storeMisses = new ArrayList<>();
    /** Every data store resolved: the standing half of the listener manager's condition. */
    private boolean dataStoresWhole;
    /** Set by a {@link #freshStores()} that renewed every data store, spent by the next {@link #remintPlugins(Set)}. */
    private boolean dataStoresFresh;
    /** The listener manager's field and constructor; null = no manager getter, a miss, or a failed renewal. */
    private Field managerField;
    private Constructor<?> managerCtor;
    private boolean managerNamed;

    private Method getCombatMap;
    private Method advanceables;
    private Class<?> wrapperClass;
    private Method unwrap;
    private Method removePlugin;
    private Method addPlugin;
    private Method mintAll;
    private Method pluginInit;
    private Class<?> everyFramePlugin;
    private Class<?> enginePlugin;
    private boolean pluginsEnabled;
    private String pluginsOff = "unresolved";

    /** What one plugin re-mint did: the drain prints {@link #scriptAdded} as its sweep count and {@link #outcome} beside it. */
    public static final class Remint {
        /** Plugins that were on the map and not in the baseline: what a round's script code added (the old sweep's count). */
        public final int scriptAdded;
        /**
         * {@code out_<removed>_in_<added>_kept_<on the map after>}, plus {@code _failed_<n>} / {@code _others_<n>}
         * when not zero, plus {@code _listeners_fresh} (the listener manager was renewed with the plugins) or
         * {@code _listeners_held} (it resolved and was kept) when a manager getter was named.
         */
        public final String outcome;

        Remint(int scriptAdded, String outcome) {
            this.scriptAdded = scriptAdded;
            this.outcome = outcome;
        }
    }

    private RealEngineFreshBattleState(Object engine) {
        this.engine = engine;
    }

    /** Resolve both halves against the live engine through the engine-name table. Never throws; prints one row. */
    public static RealEngineFreshBattleState resolve(Object engine) {
        RealEngineFreshBattleState state = new RealEngineFreshBattleState(engine);
        state.resolveStores(STORE_GETTERS, LISTENER_MANAGER_GETTER);
        try {
            ClassLoader loader = RealEngineFreshBattleState.class.getClassLoader();
            Object combatMap = engine.getClass().getMethod("getCombatMap").invoke(engine);
            EngineRow mintRow = EngineSymContentInit.CONTENT_INIT_SCRIPT_STORE_MINT_ALL_METHOD;
            state.resolvePlugins(
                    Class.forName(EVERY_FRAME_PLUGIN_INTERFACE, false, loader),
                    Class.forName(ENGINE_PLUGIN_INTERFACE, false, loader),
                    Class.forName(EngineSymContentInit.CONTENT_INIT_PLUGIN_WRAPPER_CLASS.className(), false, loader),
                    EngineSymContentInit.CONTENT_INIT_COMBAT_MAP_ADVANCEABLES_METHOD.method(combatMap.getClass()),
                    mintRow.method(Class.forName(mintRow.className(), false, loader)));
        } catch (Throwable t) {
            state.pluginsEnabled = false;
            state.pluginsOff = describe(t);
        }
        System.out.println("real-engine-fresh-battle-state resolve " + state.describeResolve());
        return state;
    }

    /**
     * The same resolve on handles the caller names: the game-free rows stand an engine in (the name table refuses
     * a stand-in). {@code storeGetters} = public zero-arg getters on the engine's class; the plugin handles =
     * the two plugin interfaces, the wrapper class {@code addPlugin} puts around a plugin, the combat map's
     * advanceable-list getter and the script store's static mint call {@code (Class) -> List}.
     */
    public static RealEngineFreshBattleState resolve(Object engine, String[] storeGetters, Class<?> everyFramePlugin,
            Class<?> enginePlugin, Class<?> wrapperClass, Method advanceables, Method mintAll) {
        return resolve(engine, storeGetters, null, everyFramePlugin, enginePlugin, wrapperClass, advanceables, mintAll);
    }

    /** The stand-in resolve with a listener manager: {@code managerGetter} = its public zero-arg getter, null = none. */
    public static RealEngineFreshBattleState resolve(Object engine, String[] storeGetters, String managerGetter,
            Class<?> everyFramePlugin, Class<?> enginePlugin, Class<?> wrapperClass, Method advanceables,
            Method mintAll) {
        RealEngineFreshBattleState state = new RealEngineFreshBattleState(engine);
        state.resolveStores(storeGetters, managerGetter);
        try {
            state.resolvePlugins(everyFramePlugin, enginePlugin, wrapperClass, advanceables, mintAll);
        } catch (Throwable t) {
            state.pluginsEnabled = false;
            state.pluginsOff = describe(t);
        }
        return state;
    }

    /**
     * {@code stores=<n> [store_misses=<getter:why,...>] [listener_manager=<ON|HELD|OFF>] plugins=<ON|OFF why>}: the
     * resolve row's tail. The manager cell is there when a manager getter was named: ON = renewed with every
     * re-mint, HELD = it resolved but a data store did not (so it is never renewed), OFF = it did not resolve.
     */
    public String describeResolve() {
        return "stores=" + storeFields.size()
                + (storeMisses.isEmpty() ? "" : " store_misses=" + String.join(",", storeMisses))
                + (!managerNamed ? "" : " listener_manager=" + (managerField == null ? "OFF"
                        : dataStoresWhole ? "ON" : "HELD"))
                + " plugins=" + (pluginsEnabled ? "ON" : "OFF " + pluginsOff);
    }

    /**
     * A store = the ONE declared instance field of the engine's class that holds the very object the getter
     * returns, plus the no-arg constructor of that object's class. Each getter on its own: a miss names itself.
     * The listener manager resolves the same way, into its own pair.
     */
    private void resolveStores(String[] getters, String managerGetter) {
        for (String getter : getters) {
            try {
                Field field = storeField(getter);
                Constructor<?> ctor = noArgConstructor(field.get(engine));
                storeFields.add(field);
                storeCtors.add(ctor);
            } catch (Throwable t) {
                storeMisses.add(getter + ":" + describe(t));
            }
        }
        dataStoresWhole = !storeFields.isEmpty() && storeMisses.isEmpty();
        managerNamed = managerGetter != null;
        if (managerNamed) {
            try {
                Field field = storeField(managerGetter);
                managerCtor = noArgConstructor(field.get(engine));
                managerField = field;
            } catch (Throwable t) {
                storeMisses.add(managerGetter + ":" + describe(t));
            }
        }
    }

    private Field storeField(String getter) throws Exception {
        Object value = engine.getClass().getMethod(getter).invoke(engine);
        if (value == null) {
            throw new IllegalStateException("null store");
        }
        Field match = null;
        for (Field f : engine.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !f.getType().isInstance(value)) {
                continue;
            }
            f.setAccessible(true);
            if (f.get(engine) == value) {
                if (match != null) {
                    throw new IllegalStateException("two fields hold it");
                }
                match = f;
            }
        }
        if (match == null) {
            throw new IllegalStateException("no field holds it");
        }
        return match;
    }

    private static Constructor<?> noArgConstructor(Object value) throws Exception {
        Constructor<?> ctor = value.getClass().getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor;
    }

    private void resolvePlugins(Class<?> everyFrame, Class<?> enginePluginIface, Class<?> wrapper,
            Method advanceablesGetter, Method mint) throws Exception {
        everyFramePlugin = everyFrame;
        enginePlugin = enginePluginIface;
        wrapperClass = wrapper;
        advanceables = advanceablesGetter;
        mintAll = mint;
        if (!Modifier.isStatic(mint.getModifiers()) || mint.getParameterCount() != 1
                || mint.getParameterTypes()[0] != Class.class || !List.class.isAssignableFrom(mint.getReturnType())) {
            throw new IllegalStateException("the mint call is not a static (Class) -> List");
        }
        getCombatMap = engine.getClass().getMethod("getCombatMap");
        removePlugin = engine.getClass().getMethod("removePlugin", everyFrame);
        addPlugin = engine.getClass().getMethod("addPlugin", everyFrame);
        unwrap = null;
        for (Method m : wrapper.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == everyFrame) {
                if (unwrap != null) {
                    throw new IllegalStateException("the plugin wrapper has two unwrap accessors");
                }
                unwrap = m;
            }
        }
        pluginInit = null;
        for (Method m : enginePluginIface.getMethods()) {
            if (m.getName().equals("init") && m.getParameterCount() == 1) {
                pluginInit = m;
            }
        }
        if (unwrap == null || pluginInit == null) {
            throw new IllegalStateException(unwrap == null ? "the plugin wrapper has no unwrap accessor"
                    : "the engine plugin interface has no one-arg init");
        }
        if (!pluginInit.getParameterTypes()[0].isInstance(engine)) {
            throw new IllegalStateException("init does not take this engine");
        }
        unwrap.setAccessible(true);
        advanceablesGetter.setAccessible(true);
        mint.setAccessible(true);
        pluginsEnabled = true;
        pluginsOff = null;
    }

    /**
     * Corrective 6: each resolved data store's field gets a new instance of its own class, the field initialiser a
     * new engine runs; the old object is dropped whole, with whatever a dead round left in it. Returns
     * {@code fresh_<stores>_dropped_<entries>} (entries = the map-typed stores' sizes, the only ones that can be
     * counted), {@code off} when no store resolved, or the drain's error shape; a throw turns the corrective off.
     * The listener manager is not renewed here: {@link #remintPlugins(Set)} does it, and only after a call of this
     * method that renewed every data store.
     */
    public synchronized String freshStores() {
        dataStoresFresh = false;
        if (storeFields.isEmpty()) {
            return "off";
        }
        try {
            long dropped = 0L;
            for (int i = 0; i < storeFields.size(); i++) {
                Field f = storeFields.get(i);
                Object old = f.get(engine);
                f.set(engine, storeCtors.get(i).newInstance());
                if (old instanceof Map) {
                    dropped += ((Map<?, ?>) old).size();
                }
            }
            dataStoresFresh = dataStoresWhole;
            return "fresh_" + storeFields.size() + "_dropped_" + dropped;
        } catch (Throwable t) {
            storeFields.clear();
            storeCtors.clear();
            return "error_fresh_disabled_" + unwrapThrow(t).getClass().getSimpleName();
        }
    }

    /**
     * Corrective 7: every script plugin on the combat map leaves through the engine's own {@code removePlugin},
     * the script store's newly minted every-frame set comes in through {@code addPlugin} in the store's order, and
     * the store's other engine plugins get their {@code init}. {@code baseline} (the drain's identity set: the
     * plugins a round legitimately opens with) is read to count what the round's scripts had added, then REPLACED
     * with the plugins now on the map, so it lets go of the instances that just left. Between the removes and the
     * adds the listener manager is renewed, when the stores before it were (the class javadoc): the new instances'
     * {@code init} then registers on the new manager, and the old one leaves with the instances it called.
     *
     * @return what happened, or {@code null} when nothing was touched (the half is off, or the mint threw: the
     *         caller's old sweep then runs in its place, and the listener manager is the one the kept plugins
     *         registered on).
     */
    public synchronized Remint remintPlugins(Set<Object> baseline) {
        boolean storesFresh = dataStoresFresh;
        dataStoresFresh = false;
        if (!pluginsEnabled) {
            return null;
        }
        List<?> minted;
        List<Object> onMap = new ArrayList<>();
        int others = 0;
        try {
            // Everything that can refuse is read BEFORE the first edit of the engine.
            minted = new ArrayList<>((List<?>) mintAll.invoke(null, everyFramePlugin));
            for (Object entry : new ArrayList<>(liveList())) {
                Object plugin = wrapperClass.isInstance(entry) ? unwrap.invoke(entry) : null;
                if (plugin != null) {
                    onMap.add(plugin);
                } else {
                    others++;
                }
            }
        } catch (Throwable t) {
            stopPlugins("before any change " + describe(t));
            return null;
        }
        int scriptAdded = 0;
        for (Object plugin : onMap) {
            if (!baseline.contains(plugin)) {
                scriptAdded++;
            }
        }
        int failed = 0;
        int removed = 0;
        for (Object plugin : onMap) {
            try {
                removePlugin.invoke(engine, plugin);
                removed++;
            } catch (Throwable t) {
                failed++;
                tellFailure("removePlugin", plugin, t);
            }
        }
        String listeners = renewListenerManager(storesFresh);
        int added = 0;
        for (Object plugin : minted) {
            try {
                addPlugin.invoke(engine, plugin);
                added++;
            } catch (Throwable t) {
                failed++;
                tellFailure("addPlugin", plugin, t);
            }
        }
        try {
            for (Object plugin : new ArrayList<>((List<?>) mintAll.invoke(null, enginePlugin))) {
                if (everyFramePlugin.isInstance(plugin)) {
                    continue;
                }
                try {
                    pluginInit.invoke(plugin, engine);
                } catch (Throwable t) {
                    failed++;
                    tellFailure("init", plugin, t);
                }
            }
        } catch (Throwable t) {
            failed++;
            tellFailure("mint", enginePlugin, t);
        }
        int kept = -1;
        try {
            List<Object> now = new ArrayList<>();
            for (Object entry : new ArrayList<>(liveList())) {
                Object plugin = wrapperClass.isInstance(entry) ? unwrap.invoke(entry) : null;
                if (plugin != null) {
                    now.add(plugin);
                }
            }
            baseline.clear();
            baseline.addAll(now);
            kept = now.size();
        } catch (Throwable t) {
            failed++;
            tellFailure("baseline", engine, t);
        }
        if (failed > 0) {
            stopPlugins("after " + failed + " failed step(s): the next round keeps these plugins");
        }
        return new Remint(scriptAdded, "out_" + removed + "_in_" + added + "_kept_" + kept
                + (failed > 0 ? "_failed_" + failed : "") + (others > 0 ? "_others_" + others : "") + listeners);
    }

    /**
     * The listener manager's renewal, called once the re-mint can no longer hand the round back.
     * The new manager is built before the field is written, so a throw changes nothing: it is told, the manager is
     * held from then on, and the re-mint goes on (its plugins register on the old manager, as before).
     *
     * @return the outcome's tail: empty when no manager resolved, else {@code _listeners_fresh} / {@code _listeners_held}
     */
    private String renewListenerManager(boolean storesFresh) {
        if (managerField == null) {
            return "";
        }
        if (!storesFresh) {
            return "_listeners_held";
        }
        try {
            Object fresh = managerCtor.newInstance();
            managerField.set(engine, fresh);
            return "_listeners_fresh";
        } catch (Throwable t) {
            managerField = null;
            tellFailure("listenerManager", engine, t);
            return "_listeners_held";
        }
    }

    /** Whether the plugin half will run at the next reset (off after a resolve miss or a failed round). */
    public synchronized boolean pluginsOn() {
        return pluginsEnabled;
    }

    private List<?> liveList() throws Exception {
        return (List<?>) advanceables.invoke(getCombatMap.invoke(engine));
    }

    private void stopPlugins(String why) {
        pluginsEnabled = false;
        pluginsOff = why;
        System.out.println("real-engine-fresh-battle-state plugins STOPPED " + why);
    }

    private static void tellFailure(String step, Object subject, Throwable t) {
        String name = subject instanceof Class ? ((Class<?>) subject).getName() : subject.getClass().getName();
        System.out.println("real-engine-fresh-battle-state plugins FAILED step=" + step + " class=" + name
                + " " + describe(t));
    }

    private static Throwable unwrapThrow(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }

    private static String describe(Throwable t) {
        Throwable cause = unwrapThrow(t);
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
