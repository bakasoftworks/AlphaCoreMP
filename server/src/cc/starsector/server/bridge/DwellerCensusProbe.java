package cc.starsector.server.bridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import cc.starsector.server.runtime.SimTickRate;

/**
 * The headless DWELLER CENSUS: once per second on
 * the sim thread after the engine advance, the Shrouded Dweller population the server actually
 * simulates — alive hulls, hulks, dead-not-hulk bodies, disabled / force-disabled mounts over the
 * alive hulls' non-decorative weapons, and deaths since the previous sample — so the fidelity rig's
 * shrouded pair ({@code run-mote-census-rig.ps1 -Category shrouded}) grades the SERVER engine against
 * the vanilla twin's identical client row ({@code DwellerCensusLane}, {@code predict dweller census})
 * instead of a frame-sheet impression.
 *
 * <p>The laws the row is read against: a dweller leaves NO
 * corpse — {@code BaseDwellerShipCreator.initBasicShipProperties} pins {@code setHulkChanceOverride(0f)},
 * so {@code hulks=} is vanilla law 0 (any non-zero served row
 * = the corpse class); the Maelstrom's creator force-disables every mount but one EVERY FRAME,
 * so its {@code forced=N/M} reads N = M-1 while it
 * lives and {@code mountEdges} counts the stagger. Hull law = the client's {@code DwellerHullLaw}
 * mirrored byte-for-byte: hull tag {@code dweller} (every {@code shrouded_*} row of
 * {@code ship_data.csv} carries it), else the {@code shrouded_} base-hull-id prefix.
 *
 * <p>Armed ONLY by {@code -Dcc.probe.dwellerCensus=true} (or env {@code CC_PROBE_DWELLER_CENSUS=true};
 * registered in {@code PostureRegistry}). OFF = one static boolean test per tick, zero
 * engine reads. Cadence = one sample per {@code SimTickRate.ticksPerSecond()} ticks (1 Hz at any tick
 * rate — the tickrate law, never a hardcoded 60). Fail-soft: any reflective failure disarms the probe
 * for the session and logs once; it never mutates engine state. Sink = capped {@code System.out} rows
 * ({@code server.out.log}), {@link #ROW_CAP} total. Same static {@code sample(List, long)} shape as
 * {@link FragmentSwarmCensusProbe} / {@link MoteCensusProbe}, ticked at the same
 * {@code EngineBackedCombatStepBoundary.step} site. Reflection is server-side only (never rides the
 * client-mod jar); every handle is a public API interface method resolved off the live instances' own
 * loader.
 */
public final class DwellerCensusProbe {
    public static final String PROP = "cc.probe.dwellerCensus";
    public static final String ENV = "CC_PROBE_DWELLER_CENSUS";
    public static final boolean ARMED = resolveArmed(System.getProperty(PROP), System.getenv(ENV));
    static final int ROW_CAP = 3000;
    /** Mirror of the client {@code DwellerHullLaw.DWELLER_TAG}: the vanilla hull tag every Dweller hull carries. */
    static final String DWELLER_TAG = "dweller";
    /** Mirror of the client {@code DwellerHullLaw.DWELLER_ID_PREFIX}: the base-hull-id fallback. */
    static final String DWELLER_ID_PREFIX = "shrouded_";

    /** Arm / row-cap / disarm skeleton (carved to {@link CappedProbeSink}); the census logic stays here. */
    private static final CappedProbeSink SINK = new CappedProbeSink("dweller-probe", PROP, ROW_CAP);
    private static int sampleEveryTicks; // 0 = not yet resolved off SimTickRate (first armed tick)
    private static Api api;
    private static String lastSig;
    private static long deathsTotal;
    /** Dweller ships that were ALIVE (isAlive && !isHulk) at the previous sample, by engine identity. */
    private static Map<Object, Boolean> prevAlive = new IdentityHashMap<>();
    /** Per non-decorative mount of an alive dweller: its {@code isDisabled()} at the previous sample. */
    private static Map<Object, Boolean> prevMountDisabled = new IdentityHashMap<>();

    private DwellerCensusProbe() {
    }

    /** The shared arm law ({@link CappedProbeSink#resolveArmed}): {@code true} (either tier) only. */
    public static boolean resolveArmed(String sysprop, String env) {
        return CappedProbeSink.resolveArmed(sysprop, env);
    }

    /** Bound once against the first ship's own loader; every handle is a public API interface method. */
    private static final class Api {
        final Method shipGetId, shipGetOwner, shipIsAlive, shipIsHulk, shipGetHullSpec, shipGetAllWeapons;
        final Method hullGetHullId, hullGetBaseHullId, hullGetTags;
        final Method weaponIsDisabled, weaponIsForceDisabled, weaponIsDecorative;

        Api(Object ship) throws Exception {
            ClassLoader loader = ship.getClass().getClassLoader();
            Class<?> shipApi = Class.forName("com.fs.starfarer.api.combat.ShipAPI", false, loader);
            Class<?> hullApi = Class.forName("com.fs.starfarer.api.combat.ShipHullSpecAPI", false, loader);
            Class<?> weaponApi = Class.forName("com.fs.starfarer.api.combat.WeaponAPI", false, loader);
            shipGetId = shipApi.getMethod("getId");
            shipGetOwner = shipApi.getMethod("getOwner");
            shipIsAlive = shipApi.getMethod("isAlive");
            shipIsHulk = shipApi.getMethod("isHulk");
            shipGetHullSpec = shipApi.getMethod("getHullSpec");
            shipGetAllWeapons = shipApi.getMethod("getAllWeapons");
            hullGetHullId = hullApi.getMethod("getHullId");
            hullGetBaseHullId = hullApi.getMethod("getBaseHullId");
            hullGetTags = hullApi.getMethod("getTags");
            weaponIsDisabled = weaponApi.getMethod("isDisabled");
            weaponIsForceDisabled = weaponApi.getMethod("isForceDisabled");
            weaponIsDecorative = weaponApi.getMethod("isDecorative");
        }
    }

    /**
     * One sample over the engine's live ship list ({@code binding.shipList()}) once per second of sim
     * ticks, called after the engine advance (the same site as the fragment-swarm and mote censuses).
     */
    static void sample(List<?> ships, long simTick) {
        if (!ARMED || SINK.closed() || ships == null || ships.isEmpty()) {
            return;
        }
        try {
            if (sampleEveryTicks == 0) {
                sampleEveryTicks = Math.max(1, SimTickRate.ticksPerSecond());
            }
            if (simTick % sampleEveryTicks != 0L) {
                return;
            }
            if (api == null) {
                api = new Api(ships.get(0));
            }
            if (!SINK.armedLogged()) {
                SINK.logArmed(" sampleEvery=" + sampleEveryTicks);
            }
            census(new ArrayList<Object>(ships), simTick);
        } catch (Throwable t) {
            SINK.disarm(t);
        }
    }

    /** The client {@code DwellerHullLaw.isDwellerHull} law over a live {@code ShipHullSpecAPI}. */
    private static boolean isDwellerHull(Object hull) throws Exception {
        Object tags = api.hullGetTags.invoke(hull);
        if (tags instanceof Set<?> && ((Set<?>) tags).contains(DWELLER_TAG)) {
            return true;
        }
        Object base = api.hullGetBaseHullId.invoke(hull);
        return base instanceof String && ((String) base).startsWith(DWELLER_ID_PREFIX);
    }

    private static void census(List<Object> ships, long simTick) throws Exception {
        int alive = 0;
        int hulks = 0;
        int dead = 0;
        int mounts = 0;
        int disabled = 0;
        int forced = 0;
        int mountEdges = 0;
        Map<Object, Boolean> nowAlive = new IdentityHashMap<>();
        Map<Object, Boolean> nowMounts = new IdentityHashMap<>();
        Map<String, Integer> hulls = new TreeMap<>();
        StringBuilder ids = new StringBuilder();
        for (Object ship : ships) {
            Object hull = api.shipGetHullSpec.invoke(ship);
            if (hull == null || !isDwellerHull(hull)) {
                continue;
            }
            boolean isAlive = ((Boolean) api.shipIsAlive.invoke(ship)).booleanValue();
            boolean isHulk = ((Boolean) api.shipIsHulk.invoke(ship)).booleanValue();
            char state;
            if (isHulk) {
                hulks++;
                state = 'h';
            } else if (isAlive) {
                alive++;
                state = 'a';
            } else {
                dead++;
                state = 'd';
            }
            hulls.merge(String.valueOf(api.hullGetHullId.invoke(hull)), 1, Integer::sum);
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(api.shipGetId.invoke(ship)).append('@').append(api.shipGetOwner.invoke(ship))
                    .append(':').append(state);
            if (state != 'a') {
                continue;
            }
            nowAlive.put(ship, Boolean.TRUE);
            for (Object w : (List<?>) api.shipGetAllWeapons.invoke(ship)) {
                if (((Boolean) api.weaponIsDecorative.invoke(w)).booleanValue()) {
                    continue;
                }
                mounts++;
                boolean dis = ((Boolean) api.weaponIsDisabled.invoke(w)).booleanValue();
                if (dis) {
                    disabled++;
                }
                if (((Boolean) api.weaponIsForceDisabled.invoke(w)).booleanValue()) {
                    forced++;
                }
                Boolean prev = prevMountDisabled.get(w);
                if (prev != null && prev.booleanValue() != dis) {
                    mountEdges++;
                }
                nowMounts.put(w, Boolean.valueOf(dis));
            }
        }
        // A death = a dweller alive at the previous sample that is now a hulk, dead, or GONE from the
        // list (vanilla's no-corpse law removes the body outright, so absence is the normal death).
        int deaths = 0;
        for (Object prev : prevAlive.keySet()) {
            if (!nowAlive.containsKey(prev)) {
                deaths++;
            }
        }
        deathsTotal += deaths;
        prevAlive = nowAlive;
        prevMountDisabled = nowMounts;
        String sig = alive + "|" + hulks + "|" + dead + "|" + disabled + "|" + forced;
        boolean edge = lastSig == null || !lastSig.equals(sig);
        lastSig = sig;
        SINK.row(String.format(Locale.ROOT,
                "dweller-census tick=%d edge=%s alive=%d hulks=%d dead=%d disabled=%d/%d forced=%d/%d"
                        + " mountEdges=%d deaths=%d deathsTotal=%d hulls={%s} ids=[%s]",
                simTick, edge, alive, hulks, dead, disabled, mounts, forced, mounts,
                mountEdges, deaths, deathsTotal, joinCounts(hulls), ids));
    }

    /** {@code k=v,k=v} with NO spaces (a whitespace-split row reader keeps the map as one token). */
    private static String joinCounts(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
