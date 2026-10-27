package cc.starsector.server.bridge;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Spawn-time evidence of WHICH ship AI the engine picked for a hull and the personality that
 * AI resolves to — a spawn-log seam.
 *
 * <p><b>Why:</b> vanilla applies the Threat/dweller personalities ({@code threat_timid} ⇒ timid,
 * derelict/automated ⇒ reckless, {@code threat_swarm_ai} fighters ⇒ {@code ThreatSwarmAI}) in
 * {@code CoreLifecyclePluginImpl.pickShipAI}, reached ONLY through
 * {@code ModManager.pickShipAIPlugin} → {@code getEnabledModPlugins()}.
 * Headless, that list is whatever the engine lazily built (the
 * {@code coreLifecyclePlugin} settings key + every enabled mod's {@code modPlugin}); nothing in
 * the server ever logged it, so a served hive closing to point-blank could
 * not be attributed to a missing personality vs. stock-AI geometry. One row per spawned hull +
 * the plugin list once answers it from the serve log alone.
 *
 * <p>Read-only, fail-soft (a reflective miss prints {@code ai=?}), never on the frame loop
 * (spawn-time only). Personality law mirrored from the engine:
 * {@code ship.getPersonality()} (captain-or-fallback) unless the AI is a
 * {@code BasicShipAI} whose {@code ShipAIConfig.personalityOverride} is non-null. A non-engine
 * plugin (script-side AI such as {@code ThreatSwarmAI}) reads back wrapped in
 * {@code Ship$ShipAIWrapper}; its {@code getAI()} unwraps.
 */
public final class AiPickEvidence {
    private static volatile String pluginListOnce;

    private AiPickEvidence() {}

    /** {@code ai=<class> personality=<resolved> override=<override|->} for a live engine Ship. */
    public static String describe(Object ship) {
        if (ship == null) {
            return "ai=? (null ship)";
        }
        try {
            Object ai = call(ship, "getShipAI");
            Object inner = ai;
            if (ai != null && ai.getClass().getName().endsWith("$ShipAIWrapper")) {
                Object unwrapped = call(ai, "getAI");
                if (unwrapped != null) {
                    inner = unwrapped;
                }
            }
            String personality = String.valueOf(call(ship, "getPersonality"));
            String override = "-";
            if (inner != null) {
                Object config = callOrNull(inner, "getConfig");
                if (config != null) {
                    try {
                        Object o = config.getClass().getField("personalityOverride").get(config);
                        if (o != null) {
                            override = String.valueOf(o);
                            personality = override;
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // a config without the public field = not the api ShipAIConfig; keep "-"
                    }
                }
            }
            return "ai=" + (inner == null ? "none" : inner.getClass().getName())
                    + " personality=" + personality + " override=" + override;
        } catch (RuntimeException | ReflectiveOperationException e) {
            return "ai=? (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** The engine's enabled mod-plugin list as class names — printed ONCE per JVM, else "". */
    public static String pluginListOnce(ClassLoader loader) {
        if (pluginListOnce != null) {
            return "";
        }
        String text;
        try {
            Class<?> mm = Class.forName("com.fs.starfarer.launcher.ModManager", true, loader);
            Object instance = mm.getMethod("getInstance").invoke(null);
            List<?> plugins = (List<?>) mm.getMethod("getEnabledModPlugins").invoke(instance);
            StringBuilder sb = new StringBuilder(" mod-plugins=[");
            for (int i = 0; i < plugins.size(); i++) {
                Object p = plugins.get(i);
                sb.append(i == 0 ? "" : ",").append(p == null ? "null" : p.getClass().getName());
            }
            text = sb.append(']').toString();
        } catch (RuntimeException | ReflectiveOperationException e) {
            text = " mod-plugins=? (" + e.getClass().getSimpleName() + ")";
        }
        pluginListOnce = text;
        return text;
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object callOrNull(Object target, String name) {
        try {
            return call(target, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
