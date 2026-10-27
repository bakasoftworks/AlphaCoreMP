package cc.starsector.client;

/**
 * Fail-soft knob parsing with a {@link FallbackAnnounce} confession:
 * {@code getProperty → trim → parse}; an UNSET/blank knob returns the
 * default silently (legitimate default, not a fallback), a MALFORMED knob returns the default AND
 * announces once — so a knob typo that silently changes behavior is visible at grading time.
 *
 * <p>Behavior-equivalent extraction of the former per-site parse methods in
 * {@code PredictReconcileCombatPlugin} ({@code parseMarkerKey}/{@code resolveFlyinBurnSec}); any
 * range clamp (e.g. flyin's {@code Math.max(1.0f, …)}) stays at the caller. Force-loaded at
 * application load beside {@link FallbackAnnounce} — callers parse from static init, which can
 * first run under the in-combat script SecurityManager where NEW class loading is forbidden.
 */
public final class KnobParse {

    private KnobParse() {
    }

    /** Force-load handle for the application-load pre-warm (see {@link FallbackAnnounce#describe}). */
    public static String describe() {
        return "armed (fail-soft, announce-on-malformed)";
    }

    /** Int knob: unset/blank → {@code dflt} silently; malformed → {@code dflt} + one announce. */
    public static int intOr(String prop, int dflt, String seamId) {
        String raw = readOrNull(prop);
        if (raw == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(raw);
        } catch (RuntimeException e) {
            FallbackAnnounce.announceOnce(seamId, "knob=" + prop + " raw=" + raw + " -> default=" + dflt);
            return dflt;
        }
    }

    /** Boolean knob ({@code true}/{@code false}, case-insensitive): unset/blank → {@code dflt}
     * silently; anything else malformed → {@code dflt} + one announce ((83) CL-1 strictSchema). */
    public static boolean boolOr(String prop, boolean dflt, String seamId) {
        String raw = readOrNull(prop);
        if (raw == null) {
            return dflt;
        }
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }
        FallbackAnnounce.announceOnce(seamId, "knob=" + prop + " raw=" + raw + " -> default=" + dflt);
        return dflt;
    }

    /** Long knob with an env-var alias ({@code prop} then {@code env} — the one variant that reads
     * the environment, for the legacy {@code cc.pilot.*}/{@code cc.predict.*} pairs): unset/blank →
     * {@code dflt} silently; malformed → {@code dflt} + one announce ((83) CL-7c — a
     * {@code cc.predict.shipId} typo used to pin seat 1 SILENTLY, the felt wrong-seat class). */
    public static long longOr(String prop, String env, long dflt, String seamId) {
        String raw = readOrNull(prop);
        if (raw == null) {
            raw = readEnvOrNull(env);
        }
        if (raw == null) {
            return dflt;
        }
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException e) {
            FallbackAnnounce.announceOnce(seamId, "knob=" + prop + " raw=" + raw + " -> default=" + dflt);
            return dflt;
        }
    }

    /** Float knob: unset/blank → {@code dflt} silently; malformed → {@code dflt} + one announce. */
    public static float floatOr(String prop, float dflt, String seamId) {
        String raw = readOrNull(prop);
        if (raw == null) {
            return dflt;
        }
        try {
            return Float.parseFloat(raw);
        } catch (RuntimeException e) {
            FallbackAnnounce.announceOnce(seamId, "knob=" + prop + " raw=" + raw + " -> default=" + dflt);
            return dflt;
        }
    }

    private static String readOrNull(String prop) {
        try {
            String raw = System.getProperty(prop);
            if (raw == null) {
                return null;
            }
            raw = raw.trim();
            return raw.isEmpty() ? null : raw;
        } catch (Throwable t) {
            return null; // unreadable property source = unset (fail-soft, no announce)
        }
    }

    private static String readEnvOrNull(String env) {
        try {
            String raw = System.getenv(env);
            if (raw == null) {
                return null;
            }
            raw = raw.trim();
            return raw.isEmpty() ? null : raw;
        } catch (Throwable t) {
            return null; // unreadable env source = unset (fail-soft, no announce)
        }
    }
}
