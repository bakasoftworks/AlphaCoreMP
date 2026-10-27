package cc.starsector.server.transform;

import java.util.Properties;

/**
 * The shared shape of the premain's own read of the server config file — the gate of a POSTURED
 * boot, where the premain decides the RNG pins and mints their master seed before any runtime class
 * is reachable. Lives in the transform package because the premain jar bundles only this package: it
 * cannot see the runtime's {@code ServerConfig}, so the two path keys below MIRROR
 * {@code ServerConfig}'s {@code CONFIG_PATH_PROPERTY} / {@code CONFIG_PATH_ENV} (a contract row pins
 * the mirror), and the byte cap is the ONE definition both sides read ({@code ServerConfig}
 * references it). The read itself (plain {@link Properties#load(java.io.InputStream)} over a
 * bounded read) lives in the premain's own class: this class carries no file I/O, so the runtime
 * tree's frame-loop residency law has nothing here to declare.
 *
 * <p>Shape: no file named ⇒ no result (a file-less boot: the premain adds nothing). A named file
 * that loads ⇒ its rows. A named file that is missing, unreadable or over the cap ⇒ a result with no
 * rows and the reason; the premain refuses the launch on it unless {@link #configStrict()} is false
 * (then the boot is not postured — the runtime's own non-strict shape, which serves on defaults).
 *
 */
public final class PremainConfigFile {

    /** Mirrors {@code ServerConfig.CONFIG_PATH_PROPERTY} (the runtime class is not premain-visible). */
    public static final String CONFIG_PATH_PROPERTY = "cc.server.config";
    /** Mirrors {@code ServerConfig.CONFIG_PATH_ENV}. */
    public static final String CONFIG_PATH_ENV = "CC_SERVER_CONFIG";
    /** The bounded-allocation cap on the config file itself (a real one is a few KB); the runtime reads this constant. */
    public static final long MAX_CONFIG_BYTES = 262144L;
    /** The strict knob's keys, read exactly as the runtime's {@code configStrict()} reads them (sysprop / env, no file tier). */
    public static final String CONFIG_STRICT_PROPERTY = "cc.config.strict";
    public static final String CONFIG_STRICT_ENV = "CC_CONFIG_STRICT";

    /**
     * A named config file's read: {@code props} non-null when it loaded, else {@code failure} names why
     * ({@code missing} / {@code unreadable: <exception SimpleName>} / {@code over the cap}); {@code path}
     * is the operator's configured path as given (trimmed).
     */
    public record Result(String path, Properties props, String failure) {
    }

    private PremainConfigFile() {
    }

    /** The configured path (sysprop &gt; env; blank = unset), trimmed, or {@code null} when no file is named. */
    public static String configuredPathOrNull() {
        String configured = System.getProperty(CONFIG_PATH_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(CONFIG_PATH_ENV);
        }
        return configured == null || configured.isBlank() ? null : configured.trim();
    }

    /**
     * The strict knob, the runtime's own law: sysprop &gt; env, default TRUE; only {@code false} /
     * {@code 0} / {@code no} / {@code off} (any case) turn it off.
     */
    public static boolean configStrict() {
        String v = System.getProperty(CONFIG_STRICT_PROPERTY);
        if (v == null || v.isBlank()) {
            v = System.getenv(CONFIG_STRICT_ENV);
        }
        if (v == null || v.isBlank()) {
            return true;
        }
        v = v.trim();
        return !(v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off"));
    }
}
