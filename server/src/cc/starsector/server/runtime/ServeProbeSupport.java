package cc.starsector.server.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Shared skeleton of the runtime SERVE probes ({@link ArcHitServeProbe},
 * {@link PhaseDiveServeProbe}): the fail-soft line budget,
 * the observe-only API-listener {@link Proxy} handler base + install, the {@code CombatEngine}
 * singleton reach, the ship→positionShipId→wireId resolution and the three small reflective
 * readers every probe copied. Extracted from the four serve probes.
 * Behavior-preserving: every stdout row a probe prints is composed byte-identically
 * to its pre-extraction copy (the prefix is the only per-probe input). {@code CanisterFlakServeProbe}
 * carries no identical copy (view-fed, no reflection, no fail-soft budget) and stays as is.
 *
 * <p>Threading: none of this is synchronized on purpose — the serve loop and the engine advance
 * share one thread (the assumption every probe documents).
 */
final class ServeProbeSupport {

    static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";

    private ServeProbeSupport() {
    }

    /**
     * Fail-soft budget: at most {@link #FAIL_LOG_MAX} {@code "<prefix> probe FAIL #n …"} lines,
     * then {@link #quiet()} reads true and the owning probe stops doing work — it must never
     * break the serve loop.
     */
    static final class FailSoft {
        static final int FAIL_LOG_MAX = 3;

        private final String prefix;
        private int failLines;

        /** {@code prefix} = the row prefix without the trailing " probe" (e.g. {@code "arc-hit"}). */
        FailSoft(String prefix) {
            this.prefix = prefix;
        }

        boolean quiet() {
            return failLines >= FAIL_LOG_MAX;
        }

        int lines() {
            return failLines;
        }

        /** {@code "<prefix> probe FAIL #n tick=T Class: msg[ [probe going quiet]]"}. */
        void fail(long simTick, Throwable failure) {
            failLines++;
            System.out.println(prefix + " probe FAIL #" + failLines + " tick=" + simTick
                    + " " + failure.getClass().getName() + ": " + failure.getMessage()
                    + (failLines >= FAIL_LOG_MAX ? " [probe going quiet]" : ""));
        }

        /** {@code "<prefix> probe FAIL #n site=S tick=T Class: msg[ [probe going quiet]]"}. */
        void fail(String site, long simTick, Throwable failure) {
            failLines++;
            System.out.println(prefix + " probe FAIL #" + failLines + " site=" + site
                    + " tick=" + simTick + " " + failure.getClass().getName()
                    + ": " + failure.getMessage()
                    + (failLines >= FAIL_LOG_MAX ? " [probe going quiet]" : ""));
        }
    }

    /**
     * Base for the observe-only API-listener proxies: the Object trio is answered here
     * ({@code toString} = the marker — the dedupe key a re-install can match on — plus identity
     * {@code hashCode}/{@code equals}); every interface method routes to {@link #onCall} and
     * returns {@code null} (the listener interfaces the probes proxy are void-only).
     */
    abstract static class ListenerHandler implements InvocationHandler {
        private final String marker;

        ListenerHandler(String marker) {
            this.marker = marker;
        }

        @Override
        public final Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "toString":
                    return marker;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && args.length == 1 && proxy == args[0];
                default:
                    onCall(method.getName(), args);
                    return null;
            }
        }

        /** The listener call ({@code method} = its name); implementations filter by name. */
        abstract void onCall(String method, Object[] args);
    }

    /**
     * {@code Proxy.newProxyInstance(loader, {listenerInterface}, handler)} then
     * {@code addListener.invoke(host, proxy)}; returns the installed proxy.
     */
    static Object installListener(ClassLoader loader, Class<?> listenerInterface,
            InvocationHandler handler, Method addListener, Object host) throws Exception {
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{listenerInterface}, handler);
        addListener.invoke(host, proxy);
        return proxy;
    }

    /** The engine singleton reach: the (initialized) class + {@code getInstance()} result. */
    record EngineHandle(Class<?> type, Object instance) {
    }

    /** {@code Class.forName(ENGINE_CLASS, true, loader)} + {@code getMethod("getInstance").invoke(null)}. */
    static EngineHandle combatEngine(ClassLoader loader) throws Exception {
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        return new EngineHandle(engineClass, engineClass.getMethod("getInstance").invoke(null));
    }

    /**
     * positionShipId → wire id through the snapshot source's map; {@code -1} when the map is
     * absent or has no row. The key is passed through as-is (a {@code null} key reads the map
     * exactly as the inline copies did).
     */
    static long wireIdOfPosition(Long positionShipId, Map<Long, Long> wireIds) {
        if (wireIds == null) {
            return -1L;
        }
        Long wireId = wireIds.get(positionShipId);
        return wireId == null ? -1L : wireId;
    }

    /**
     * The Supplier-pair chain: ship → positionShipId (the binding's live map) → wire id (the
     * snapshot source's map); {@code -1} on any miss.
     */
    static long wireIdOf(Object ship, Map<Object, Long> ships, Map<Long, Long> wireIds) {
        if (ship == null || ships == null || wireIds == null) {
            return -1L;
        }
        Long positionShipId = ships.get(ship);
        if (positionShipId == null) {
            return -1L;
        }
        return wireIdOfPosition(positionShipId, wireIds);
    }

    /** Reflective no-arg call on the live engine object ({@code getMethod} + {@code setAccessible}). */
    static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    static float floatOf(Object v) {
        return v instanceof Number ? ((Number) v).floatValue() : Float.NaN;
    }

    /** {@code getHullSpec().getHullId()} for a log row; fail-soft to {@code "unreadable"}. */
    static String hullIdOf(Object ship) {
        try {
            Object spec = call(ship, "getHullSpec");
            Object id = spec == null ? null : call(spec, "getHullId");
            return id == null ? "null" : id.toString();
        } catch (Throwable t) {
            return "unreadable";
        }
    }
}
