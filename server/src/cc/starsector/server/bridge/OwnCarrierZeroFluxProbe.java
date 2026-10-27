package cc.starsector.server.bridge;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The zero-flux measurement row — the server-side per-tick read of ONE owned hull's
 * zero-flux-boost inputs, so a test match can answer WHY the serve drops the carrier's boost
 * under fighter ENGAGE while the client keeps it.
 *
 * <p>Engine law it measures, in the ship's flux tracker: the boost needs either
 * the below-threshold clock (standard branch — flux/max ≤ {@code zeroFluxMinimumFluxLevel},
 * base 0.0) or, with {@code allowZeroFluxAtAnyLevel > 0} (elite Helmsmanship L3, the any-level
 * branch), ≥1 s since the last POSITIVE {@code increaseFlux}. Under ENGAGE a carrier's
 * flux is driven into a ~1 %-of-max band by {@code Ship.advance} — via {@code increaseFlux}
 * (resets both timers) below 95 % of the band, via {@code setCurrFlux} (resets nothing)
 * inside it. The row therefore carries the two stats, the pull-back level, the boost verdict, and a
 * flux delta per tick (a rise > {@link #ADD_DELTA_MIN} = a positive add landed between samples; the
 * in-band snap jitter is ±3 and dissipation only lowers), plus the tracker's own
 * {@code isAttemptedFluxIncreaseThisFrame} flag (reset each tracker advance).
 *
 * <p>Armed ONLY by {@code -Dcc.probe.ownCarrierZeroFlux=true} (a one-flight
 * {@code CC_SERVER_EXTRA_JVM_FLAGS} delta, never config); OFF = one static boolean test per tick,
 * zero binding reads. Fail-soft: any reflective failure disarms the probe for the session and logs
 * once. Sink = capped {@code System.out} rows ({@code server.out.log}), {@link #ROW_CAP} total.
 * Reflection is server-side only (never rides the client-mod jar).
 */
final class OwnCarrierZeroFluxProbe {
    static final String PROP = "cc.probe.ownCarrierZeroFlux";
    /** Sysprop-only (no env alias — the {@code PostureRegistry} knob carries none); the same read
     *  {@code Boolean.getBoolean(PROP)} gave, through the shared arm law. */
    static final boolean ARMED = CappedProbeSink.resolveArmed(System.getProperty(PROP), null);
    static final int ROW_CAP = 20000;
    static final int PERIODIC_EVERY_TICKS = 60;
    static final float ADD_DELTA_MIN = 10f;

    private static final Map<Long, Prev> PREV = new HashMap<>();
    /** Arm / row-cap / disarm skeleton (carved to {@link CappedProbeSink}); the measurement stays here. */
    private static final CappedProbeSink SINK = new CappedProbeSink("zf-probe", PROP, ROW_CAP);
    private static Api api;

    private OwnCarrierZeroFluxProbe() {
    }

    private static final class Prev {
        float curr = Float.NaN;
        boolean boost;
        boolean pulledBack;
        boolean seen;
    }

    /** Bound once against the first ship's own loader; every handle is a public API interface method. */
    private static final class Api {
        final Method shipGetFluxTracker, shipIsEngineBoostActive, shipIsPullBackFighters, shipGetVelocity,
                shipGetMutableStats, shipGetLaunchBays, shipGetMaxSpeedWithoutBoost;
        final Method fluxGetCurrFlux, fluxGetMaxFlux, fluxGetHardFlux, fluxIsOverloaded, fluxIsVenting;
        final Method statsAllowZeroFlux, statsZeroFluxMinLevel, statsFluxDissipation, statsZeroFluxSpeedBoost;
        final Method statGetModifiedValue;
        final Method vecLength;
        Method trackerAttempted; // public method on an engine-internal class; nullable (row prints '-')

        Api(Object ship) throws Exception {
            ClassLoader loader = ship.getClass().getClassLoader();
            Class<?> shipApi = Class.forName("com.fs.starfarer.api.combat.ShipAPI", false, loader);
            Class<?> fluxApi = Class.forName("com.fs.starfarer.api.combat.FluxTrackerAPI", false, loader);
            Class<?> statsApi = Class.forName("com.fs.starfarer.api.combat.MutableShipStatsAPI", false, loader);
            Class<?> statApi = Class.forName("com.fs.starfarer.api.combat.MutableStat", false, loader);
            Class<?> vec = Class.forName("org.lwjgl.util.vector.Vector2f", false, loader);
            shipGetFluxTracker = shipApi.getMethod("getFluxTracker");
            shipIsEngineBoostActive = shipApi.getMethod("isEngineBoostActive");
            shipIsPullBackFighters = shipApi.getMethod("isPullBackFighters");
            shipGetVelocity = shipApi.getMethod("getVelocity");
            shipGetMutableStats = shipApi.getMethod("getMutableStats");
            shipGetLaunchBays = shipApi.getMethod("getLaunchBaysCopy");
            shipGetMaxSpeedWithoutBoost = shipApi.getMethod("getMaxSpeedWithoutBoost");
            fluxGetCurrFlux = fluxApi.getMethod("getCurrFlux");
            fluxGetMaxFlux = fluxApi.getMethod("getMaxFlux");
            fluxGetHardFlux = fluxApi.getMethod("getHardFlux");
            fluxIsOverloaded = fluxApi.getMethod("isOverloaded");
            fluxIsVenting = fluxApi.getMethod("isVenting");
            statsAllowZeroFlux = statsApi.getMethod("getAllowZeroFluxAtAnyLevel");
            statsZeroFluxMinLevel = statsApi.getMethod("getZeroFluxMinimumFluxLevel");
            statsFluxDissipation = statsApi.getMethod("getFluxDissipation");
            statsZeroFluxSpeedBoost = statsApi.getMethod("getZeroFluxSpeedBoost");
            statGetModifiedValue = statApi.getMethod("getModifiedValue");
            vecLength = vec.getMethod("length");
        }
    }

    /**
     * One sample of {@code ship} at {@code simTick}; a row is printed on the first sample, on any
     * boost / pull-back flip, on a positive flux add, and every {@link #PERIODIC_EVERY_TICKS}.
     */
    static void sample(Object ship, long wireId, long simTick) {
        if (!ARMED || SINK.closed() || ship == null) {
            return;
        }
        try {
            if (api == null) {
                api = new Api(ship);
            }
            if (!SINK.armedLogged()) {
                SINK.logArmed(" addDeltaMin=" + ADD_DELTA_MIN + " periodicEvery=" + PERIODIC_EVERY_TICKS);
            }
            Object ft = api.shipGetFluxTracker.invoke(ship);
            if (ft == null) {
                return;
            }
            float curr = ((Number) api.fluxGetCurrFlux.invoke(ft)).floatValue();
            boolean boost = ((Boolean) api.shipIsEngineBoostActive.invoke(ship)).booleanValue();
            boolean pulledBack = ((Boolean) api.shipIsPullBackFighters.invoke(ship)).booleanValue();
            Prev p = PREV.get(wireId);
            if (p == null) {
                p = new Prev();
                PREV.put(wireId, p);
            }
            String why = null;
            if (!p.seen) {
                why = "init";
            } else if (boost != p.boost) {
                why = boost ? "boost-on" : "boost-off";
            } else if (pulledBack != p.pulledBack) {
                why = pulledBack ? "regroup" : "engage";
            } else if (curr > p.curr + ADD_DELTA_MIN) {
                why = "add";
            } else if (simTick % PERIODIC_EVERY_TICKS == 0L) {
                why = "periodic";
            }
            if (why != null) {
                printRow(ship, ft, wireId, simTick, why, curr, p.seen ? p.curr : Float.NaN, boost, pulledBack);
            }
            p.curr = curr;
            p.boost = boost;
            p.pulledBack = pulledBack;
            p.seen = true;
        } catch (Throwable t) {
            SINK.disarm(t);
        }
    }

    private static void printRow(Object ship, Object ft, long wireId, long simTick, String why, float curr,
            float prev, boolean boost, boolean pulledBack) throws Exception {
        Object stats = api.shipGetMutableStats.invoke(ship);
        float max = ((Number) api.fluxGetMaxFlux.invoke(ft)).floatValue();
        float hard = ((Number) api.fluxGetHardFlux.invoke(ft)).floatValue();
        boolean ovl = ((Boolean) api.fluxIsOverloaded.invoke(ft)).booleanValue();
        boolean vent = ((Boolean) api.fluxIsVenting.invoke(ft)).booleanValue();
        float azf = stat(stats, api.statsAllowZeroFlux);
        float zfl = stat(stats, api.statsZeroFluxMinLevel);
        float diss = stat(stats, api.statsFluxDissipation);
        float zfb = stat(stats, api.statsZeroFluxSpeedBoost);
        Object vel = api.shipGetVelocity.invoke(ship);
        float spd = vel == null ? Float.NaN : ((Number) api.vecLength.invoke(vel)).floatValue();
        float nob = ((Number) api.shipGetMaxSpeedWithoutBoost.invoke(ship)).floatValue();
        Object bays = api.shipGetLaunchBays.invoke(ship);
        int bayCount = bays instanceof List ? ((List<?>) bays).size() : -1;
        String att = attempted(ft);
        SINK.row(String.format(Locale.ROOT,
                "zf-probe tick=%d ship=%d why=%s pb=%b boost=%b curr=%.1f prev=%.1f d=%+.1f hard=%.1f"
                        + " max=%.0f diss=%.0f azf=%.2f zfl=%.3f zfb=%.0f att=%s ovl=%b vent=%b bays=%d"
                        + " spd=%.1f nob=%.1f",
                simTick, wireId, why, pulledBack, boost, curr, prev, Float.isNaN(prev) ? 0f : curr - prev,
                hard, max, diss, azf, zfl, zfb, att, ovl, vent, bayCount, spd, nob));
    }

    private static float stat(Object stats, Method getter) throws Exception {
        Object s = stats == null ? null : getter.invoke(stats);
        return s == null ? Float.NaN : ((Number) api.statGetModifiedValue.invoke(s)).floatValue();
    }

    /** The tracker's {@code isAttemptedFluxIncreaseThisFrame} — public on the engine's tracker class, not on the API. */
    private static String attempted(Object ft) {
        try {
            if (api.trackerAttempted == null) {
                Method m = ft.getClass().getMethod("isAttemptedFluxIncreaseThisFrame");
                m.setAccessible(true);
                api.trackerAttempted = m;
            }
            return String.valueOf(api.trackerAttempted.invoke(ft));
        } catch (Throwable t) {
            return "-";
        }
    }
}
