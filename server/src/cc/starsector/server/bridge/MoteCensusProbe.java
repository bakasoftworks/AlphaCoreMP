package cc.starsector.server.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The headless MOTE CENSUS: per live {@code MoteControlScript} shared-data
 * entry, the exact vanilla spawn-gate inputs, sampled on the sim thread after the engine advance, so
 * the reported "Ziggurat mote generator stuck" (attractor FX
 * loop, no motes, survived /suicide + respawn, took no Omen EMP) is DIFFED against the gate instead
 * of guessed.
 *
 * <p>The law it measures, from vanilla's {@code MoteControlScript}:
 * <ul>
 * <li>shared data key = {@code ship + "_mote_AI_shared"} in engine customData, and the engine's
 *     {@code Ship.toString()} =
 *     {@code variantId + " " + name + " [" + hullId + "]"} — a RESPAWNED seat ship (same variant/name)
 *     INHERITS the dead ship's data; two same-named same-variant ships share ONE data object (both
 *     sides). The row therefore lists every live ship whose toString matches the key
 *     ({@code ships=}): 0 = an orphan entry, 2+ = a collision.</li>
 * <li>a spawn needs {@code motes.size() < maxMotes && attractorLock == null && !overloadedOrVenting}
 *     after the {@code engine.isMissileAlive} prune; {@code isHulk()} ⇒ motes
 *     flamed out + cleared + return; the lock clears ONLY inside the
 *     {@code attractorRemaining > 0} branch — a non-positive/NaN remaining with a live lock
 *     is a permanent stall.</li>
 * <li>the loop sound (the reported "FX loop") plays iff {@code motes.size() > 3}: a client
 *     whose LOCAL list holds > 3 alive-but-invisible motes hums with nothing on screen.</li>
 * </ul>
 *
 * <p>Armed ONLY by {@code -Dcc.probe.moteCensus=true} (or env {@code CC_PROBE_MOTE_CENSUS=true};
 * registered in {@code PostureRegistry}). OFF = one static boolean test per tick, zero
 * engine reads. Fail-soft: any reflective failure disarms the probe for the session and logs once; it
 * never mutates engine state. Sink = capped {@code System.out} rows ({@code server.out.log}): one
 * {@code mote-census} row per entry every {@link #CENSUS_EVERY_TICKS}, plus an {@code edge=true} row
 * the sample the gate SIGNATURE changes (collision count / alive / hulk / saturated / lock / zero
 * alive motes / overload-vent), plus one capped per-mote {@code mote-detail} burst per saturation
 * edge. Reflection is server-side only (never rides the client-mod jar); every handle is a public API
 * method resolved off the live instances' own loader.
 */
public final class MoteCensusProbe {
    public static final String PROP = "cc.probe.moteCensus";
    public static final String ENV = "CC_PROBE_MOTE_CENSUS";
    public static final boolean ARMED = resolveArmed(System.getProperty(PROP), System.getenv(ENV));
    static final int SAMPLE_EVERY_TICKS = 10;
    static final int CENSUS_EVERY_TICKS = 60;
    static final int ROW_CAP = 3000;
    static final int DETAIL_BURSTS_MAX = 12;
    static final int DETAIL_MOTES_MAX = 50;
    static final String KEY_SUFFIX = "_mote_AI_shared";
    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SCRIPT_CLASS = "com.fs.starfarer.api.impl.combat.MoteControlScript";
    private static final String DATA_CLASS = SCRIPT_CLASS + "$SharedMoteAIData";

    /** Arm / row-cap / disarm skeleton (carved to {@link CappedProbeSink}); the census logic stays here. */
    private static final CappedProbeSink SINK = new CappedProbeSink("mote-probe", PROP, ROW_CAP);
    private static int detailBursts;
    private static Api api;
    private static final Map<String, String> LAST_SIG = new HashMap<>();

    private MoteCensusProbe() {
    }

    /** The shared arm law ({@link CappedProbeSink#resolveArmed}): {@code true} (either tier) only. */
    public static boolean resolveArmed(String sysprop, String env) {
        return CappedProbeSink.resolveArmed(sysprop, env);
    }

    /** Bound once against the first ship's own loader; every handle is a public API member. */
    private static final class Api {
        final Method globalGetCombatEngine;
        final Method engineGetCustomData, engineGetMissiles, engineIsMissileAlive;
        final Method shipGetId, shipGetOwner, shipIsAlive, shipIsHulk, shipIsPhased, shipGetFluxTracker,
                shipGetSystem;
        final Method fluxIsOverloadedOrVenting;
        final Method systemGetState, systemGetEffectLevel;
        final Method entityGetLocation, entityGetHitpoints;
        final Method missileIsFizzling, missileGetFlightTime, missileGetMaxFlightTime, missileGetSource;
        /** The server's OWN light truth per mote — {@code MissileAPI.getEngineController()}
         *  + {@code ShipEngineControllerAPI.isAccelerating()} (= the last frame's ACCELERATE decision, the
         *  {@code POSE_FLAG_ACCELERATING} source) — so the row's {@code lit=N/M} grades
         *  S-vs-C agreement in ONE run instead of a self-measure of the wire bit. */
        final Method missileGetEngineController, controllerIsAccelerating;
        final Method scriptGetMaxMotes;
        final Class<?> dataClass;
        final Field dataMotes, dataLock, dataTarget, dataRemaining, dataElapsed;

        Api(Object ship) throws Exception {
            ClassLoader loader = ship.getClass().getClassLoader();
            Class<?> global = Class.forName(GLOBAL_CLASS, false, loader);
            Class<?> engineApi = Class.forName("com.fs.starfarer.api.combat.CombatEngineAPI", false, loader);
            Class<?> shipApi = Class.forName("com.fs.starfarer.api.combat.ShipAPI", false, loader);
            Class<?> fluxApi = Class.forName("com.fs.starfarer.api.combat.FluxTrackerAPI", false, loader);
            Class<?> systemApi = Class.forName("com.fs.starfarer.api.combat.ShipSystemAPI", false, loader);
            Class<?> entityApi = Class.forName("com.fs.starfarer.api.combat.CombatEntityAPI", false, loader);
            Class<?> missileApi = Class.forName("com.fs.starfarer.api.combat.MissileAPI", false, loader);
            Class<?> script = Class.forName(SCRIPT_CLASS, false, loader);
            dataClass = Class.forName(DATA_CLASS, false, loader);
            globalGetCombatEngine = global.getMethod("getCombatEngine");
            engineGetCustomData = engineApi.getMethod("getCustomData");
            engineGetMissiles = engineApi.getMethod("getMissiles");
            engineIsMissileAlive = engineApi.getMethod("isMissileAlive", missileApi);
            shipGetId = shipApi.getMethod("getId");
            shipGetOwner = shipApi.getMethod("getOwner");
            shipIsAlive = shipApi.getMethod("isAlive");
            shipIsHulk = shipApi.getMethod("isHulk");
            shipIsPhased = shipApi.getMethod("isPhased");
            shipGetFluxTracker = shipApi.getMethod("getFluxTracker");
            shipGetSystem = shipApi.getMethod("getSystem");
            fluxIsOverloadedOrVenting = fluxApi.getMethod("isOverloadedOrVenting");
            systemGetState = systemApi.getMethod("getState");
            systemGetEffectLevel = systemApi.getMethod("getEffectLevel");
            entityGetLocation = entityApi.getMethod("getLocation");
            entityGetHitpoints = entityApi.getMethod("getHitpoints");
            missileIsFizzling = missileApi.getMethod("isFizzling");
            missileGetFlightTime = missileApi.getMethod("getFlightTime");
            missileGetMaxFlightTime = missileApi.getMethod("getMaxFlightTime");
            missileGetSource = missileApi.getMethod("getSource");
            Class<?> controllerApi = Class.forName("com.fs.starfarer.api.combat.ShipEngineControllerAPI", false, loader);
            missileGetEngineController = missileApi.getMethod("getEngineController");
            controllerIsAccelerating = controllerApi.getMethod("isAccelerating");
            scriptGetMaxMotes = script.getMethod("getMaxMotes", shipApi);
            dataMotes = dataClass.getField("motes");
            dataLock = dataClass.getField("attractorLock");
            dataTarget = dataClass.getField("attractorTarget");
            dataRemaining = dataClass.getField("attractorRemaining");
            dataElapsed = dataClass.getField("elapsed");
        }
    }

    /**
     * One sample over the engine's live ship list ({@code binding.shipList()}) every
     * {@link #SAMPLE_EVERY_TICKS}, called after the engine advance (the same site as the
     * fragment-swarm census).
     */
    static void sample(List<?> ships, long simTick) {
        if (!ARMED || SINK.closed() || ships == null || ships.isEmpty()
                || simTick % SAMPLE_EVERY_TICKS != 0L) {
            return;
        }
        try {
            if (api == null) {
                api = new Api(ships.get(0));
            }
            if (!SINK.armedLogged()) {
                SINK.logArmed(" sampleEvery=" + SAMPLE_EVERY_TICKS + " censusEvery=" + CENSUS_EVERY_TICKS
                        + " detailBursts=" + DETAIL_BURSTS_MAX);
            }
            Object engine = api.globalGetCombatEngine.invoke(null);
            if (engine == null) {
                diag(simTick, "Global.getCombatEngine()=null (loader=" + api.dataClass.getClassLoader() + ")");
                return;
            }
            Map<?, ?> custom = (Map<?, ?>) api.engineGetCustomData.invoke(engine);
            if (custom == null || custom.isEmpty()) {
                diag(simTick, "customData=" + (custom == null ? "null" : "EMPTY") + " engine="
                        + engine.getClass().getName() + " ships=" + ships.size());
                return;
            }
            List<Object[]> entries = new ArrayList<>();
            StringBuilder suffixedMiss = new StringBuilder();
            for (Map.Entry<?, ?> e : custom.entrySet()) {
                Object k = e.getKey();
                Object v = e.getValue();
                if (k instanceof String && ((String) k).endsWith(KEY_SUFFIX)) {
                    if (v != null && api.dataClass.isInstance(v)) {
                        entries.add(new Object[] {k, v});
                    } else if (suffixedMiss.length() < 400) {
                        // A suffixed key whose value is NOT our dataClass = a
                        // classloader split (two MoteControlScript definitions) — the silent branch.
                        suffixedMiss.append(' ').append(k).append("->")
                                .append(v == null ? "null" : v.getClass().getName() + "@" + v.getClass().getClassLoader());
                    }
                }
            }
            if (entries.isEmpty()) {
                StringBuilder keys = new StringBuilder();
                int n = 0;
                for (Object k : custom.keySet()) {
                    if (n++ >= 8) {
                        keys.append(" ...");
                        break;
                    }
                    keys.append(' ').append(k);
                }
                diag(simTick, "no " + KEY_SUFFIX + " entry: customKeys=" + custom.size() + " ships=" + ships.size()
                        + " dataClassLoader=" + api.dataClass.getClassLoader() + " suffixedMiss=["
                        + suffixedMiss + " ] sample=[" + keys + " ]");
                return;
            }
            Map<String, List<Object>> byString = new HashMap<>();
            for (Object s : new ArrayList<>(ships)) {
                byString.computeIfAbsent(String.valueOf(s), x -> new ArrayList<>()).add(s);
            }
            Map<Object, Boolean> inList = new IdentityHashMap<>();
            List<?> missiles = (List<?>) api.engineGetMissiles.invoke(engine);
            if (missiles != null) {
                for (Object m : missiles) {
                    inList.put(m, Boolean.TRUE);
                }
            }
            boolean census = simTick % CENSUS_EVERY_TICKS == 0L;
            for (Object[] entry : entries) {
                if (SINK.full()) {
                    break;
                }
                censusEntry(engine, (String) entry[0], entry[1], byString, inList, simTick, census);
            }
        } catch (Throwable t) {
            SINK.disarm(t);
        }
    }

    private static void censusEntry(Object engine, String key, Object data, Map<String, List<Object>> byString,
            Map<Object, Boolean> inList, long simTick, boolean census) throws Exception {
        String prefix = key.substring(0, key.length() - KEY_SUFFIX.length());
        List<Object> matches = byString.get(prefix);
        int shipCount = matches == null ? 0 : matches.size();
        Object ship = shipCount == 0 ? null : matches.get(0);
        List<?> motes = (List<?>) api.dataMotes.get(data);
        Object lock = api.dataLock.get(data);
        Object target = api.dataTarget.get(data);
        float remaining = api.dataRemaining.getFloat(data);
        float elapsed = api.dataElapsed.getFloat(data);
        float[] shipXy = ship == null ? new float[] {Float.NaN, Float.NaN}
                : xyOrNaN(api.entityGetLocation.invoke(ship));
        List<Object> moteList = motes == null ? new ArrayList<>() : new ArrayList<Object>(motes);
        int size = moteList.size();
        int alive = 0;
        int listed = 0;
        int fizz = 0;
        int nonFinite = 0;
        int foreignSource = 0;
        int lit = 0;
        float maxDist = 0f;
        float oldest = 0f;
        for (Object m : moteList) {
            if (m == null) {
                continue;
            }
            if (((Boolean) api.engineIsMissileAlive.invoke(engine, m)).booleanValue()) {
                alive++;
            }
            Object controller = api.missileGetEngineController.invoke(m);
            if (controller != null && ((Boolean) api.controllerIsAccelerating.invoke(controller)).booleanValue()) {
                lit++;
            }
            if (inList.containsKey(m)) {
                listed++;
            }
            if (((Boolean) api.missileIsFizzling.invoke(m)).booleanValue()) {
                fizz++;
            }
            float[] xy = xyOrNaN(api.entityGetLocation.invoke(m));
            if (Float.isNaN(xy[0]) || Float.isNaN(xy[1]) || Float.isInfinite(xy[0]) || Float.isInfinite(xy[1])) {
                nonFinite++;
            } else if (ship != null && !Float.isNaN(shipXy[0])) {
                float dx = xy[0] - shipXy[0];
                float dy = xy[1] - shipXy[1];
                maxDist = Math.max(maxDist, (float) Math.sqrt(dx * dx + dy * dy));
            }
            oldest = Math.max(oldest, ((Number) api.missileGetFlightTime.invoke(m)).floatValue());
            if (ship != null && api.missileGetSource.invoke(m) != ship) {
                foreignSource++;
            }
        }
        int maxMotes = -1;
        boolean shipAlive = false;
        boolean hulk = false;
        boolean phased = false;
        boolean ovlVent = false;
        String sys = "-";
        String ids = "-";
        if (ship != null) {
            maxMotes = ((Number) api.scriptGetMaxMotes.invoke(null, ship)).intValue();
            shipAlive = ((Boolean) api.shipIsAlive.invoke(ship)).booleanValue();
            hulk = ((Boolean) api.shipIsHulk.invoke(ship)).booleanValue();
            phased = ((Boolean) api.shipIsPhased.invoke(ship)).booleanValue();
            Object flux = api.shipGetFluxTracker.invoke(ship);
            ovlVent = flux != null && ((Boolean) api.fluxIsOverloadedOrVenting.invoke(flux)).booleanValue();
            Object system = api.shipGetSystem.invoke(ship);
            if (system != null) {
                sys = api.systemGetState.invoke(system) + "/"
                        + String.format(Locale.ROOT, "%.2f",
                                ((Number) api.systemGetEffectLevel.invoke(system)).floatValue());
            }
            StringBuilder sb = new StringBuilder();
            for (Object s : matches) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(api.shipGetId.invoke(s)).append('@').append(api.shipGetOwner.invoke(s));
            }
            ids = sb.toString();
        }
        String lockDesc = "null";
        if (lock != null) {
            boolean lockAlive = ((Boolean) api.shipIsAlive.invoke(lock)).booleanValue();
            boolean lockHulk = ((Boolean) api.shipIsHulk.invoke(lock)).booleanValue();
            lockDesc = api.shipGetId.invoke(lock) + ":" + (lockAlive ? "alive" : "dead") + (lockHulk ? "/hulk" : "");
        }
        boolean saturated = maxMotes >= 0 && size >= maxMotes;
        String sig = shipCount + "|" + shipAlive + "|" + hulk + "|" + saturated + "|" + (lock != null) + "|"
                + (alive == 0) + "|" + ovlVent + "|" + (nonFinite > 0);
        String prev = LAST_SIG.put(key, sig);
        boolean edge = prev == null || !prev.equals(sig);
        if (!edge && !census) {
            return;
        }
        float[] targetXy = target == null ? null : xyOrNaN(target);
        SINK.row(String.format(Locale.ROOT,
                "mote-census tick=%d edge=%s key=\"%s\" ships=%d ids=[%s] alive=%s hulk=%s phased=%s ovlVent=%s"
                        + " sys=%s maxMotes=%d motes=%d moteAlive=%d inList=%d fizz=%d nonFinite=%d"
                        + " foreignSrc=%d lit=%d/%d maxDist=%.0f oldest=%.1f lock=%s remaining=%.2f target=%s elapsed=%.1f",
                simTick, edge, prefix, shipCount, ids, shipAlive, hulk, phased, ovlVent, sys, maxMotes, size,
                alive, listed, fizz, nonFinite, foreignSource, lit, size, maxDist, oldest, lockDesc, remaining,
                targetXy == null ? "null" : String.format(Locale.ROOT, "%.0f,%.0f", targetXy[0], targetXy[1]),
                elapsed));
        if (edge && saturated && detailBursts < DETAIL_BURSTS_MAX && !SINK.full()) {
            detailBursts++;
            int i = 0;
            for (Object m : moteList) {
                if (m == null || i >= DETAIL_MOTES_MAX || SINK.full()) {
                    break;
                }
                float[] xy = xyOrNaN(api.entityGetLocation.invoke(m));
                float dist = Float.NaN;
                if (ship != null && !Float.isNaN(shipXy[0]) && !Float.isNaN(xy[0])) {
                    float dx = xy[0] - shipXy[0];
                    float dy = xy[1] - shipXy[1];
                    dist = (float) Math.sqrt(dx * dx + dy * dy);
                }
                Object src = api.missileGetSource.invoke(m);
                SINK.row(String.format(Locale.ROOT,
                        "mote-detail tick=%d key=\"%s\" burst=%d #%d alive=%s inList=%s fizz=%s hp=%.0f"
                                + " flight=%.1f/%.1f dist=%.0f pos=%.0f,%.0f src=%s",
                        simTick, prefix, detailBursts, i, api.engineIsMissileAlive.invoke(engine, m),
                        inList.containsKey(m), api.missileIsFizzling.invoke(m),
                        ((Number) api.entityGetHitpoints.invoke(m)).floatValue(),
                        ((Number) api.missileGetFlightTime.invoke(m)).floatValue(),
                        ((Number) api.missileGetMaxFlightTime.invoke(m)).floatValue(),
                        dist, xy[0], xy[1], src == null ? "null" : String.valueOf(api.shipGetId.invoke(src))));
                i++;
            }
        }
    }

    static final int DIAG_EVERY_TICKS = 600;
    static final int DIAG_ROWS_MAX = 24;
    private static int diagRows;

    /**
     * The formerly SILENT early returns (engine null / customData empty / no
     * suffixed entry) each print a capped {@code mote-probe DIAG} row every {@link #DIAG_EVERY_TICKS},
     * so "zero census rows" is readable as "no generator ran" vs "the probe cannot see it" (a
     * classloader split shows as {@code suffixedMiss}).
     */
    private static void diag(long simTick, String msg) {
        if (simTick % DIAG_EVERY_TICKS != 0L || diagRows >= DIAG_ROWS_MAX) {
            return;
        }
        diagRows++;
        System.out.println("mote-probe DIAG tick=" + simTick + " " + msg
                + (diagRows == DIAG_ROWS_MAX ? " [diag cap]" : ""));
    }

    private static float[] xyOrNaN(Object vector) {
        try {
            return new float[] {
                    vector.getClass().getField("x").getFloat(vector),
                    vector.getClass().getField("y").getFloat(vector)};
        } catch (Throwable t) {
            return new float[] {Float.NaN, Float.NaN};
        }
    }
}
