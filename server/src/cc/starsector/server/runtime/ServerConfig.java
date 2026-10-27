package cc.starsector.server.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The ONE dedicated-server config file (B.6 embryo): consolidates the knobs that
 * were scattered across sysprops, env vars and script parameters ({@code cc.predict.mapHalf},
 * {@code cc.pilot.watch.frames}/{@code awaitSeconds}, the predict port file, the watch timeout pad,
 * the status-line surface, the B.7 mod-set hash line).
 *
 * <p>Resolution precedence per knob is <b>sysprop &gt; env var &gt; config file &gt; built-in
 * default</b> — every existing wrapper that passes {@code -D...} explicitly behaves exactly as
 * before; the file only supplies values nothing else did. The file location comes from
 * {@code -Dcc.server.config} / {@code CC_SERVER_CONFIG}. NOTE:
 * the SERVE lanes never actually passed the file — posture reached serve
 * JVMs only as wrapper-ferried sysprops, which is the root of the drift class
 * the lane-posture design fixes; predict/watch-tool lanes do pass it.
 * Per-value parsing is fail-soft (malformed values announce + default), but a
 * CONFIGURED file that is missing/unreadable is a wholesale-defaults boot — since (83)
 * that refuses under {@code cc.config.strict} (default STRICT;
 * sysprop/env only — a file-tier value could not save a file that failed to load);
 * {@code cc.config.strict=false} restores announce-and-degrade. The nothing-configured path is
 * untouched: no file expected, defaults are the intent.
 */
public final class ServerConfig {

    /** (83) SV-4: a configured-but-unloadable config file under strict (the default). */
    public static final class ServerConfigException extends RuntimeException {
        public ServerConfigException(String message) {
            super(message);
        }
    }

    public static final String CONFIG_PATH_PROPERTY = "cc.server.config";
    public static final String CONFIG_PATH_ENV = "CC_SERVER_CONFIG";

    /** L10 bounded-allocation cap on the config file itself — the ONE cap the premain's own read shares. */
    public static final long MAX_CONFIG_BYTES = cc.starsector.server.transform.PremainConfigFile.MAX_CONFIG_BYTES;

    private static volatile Properties file;
    private static volatile Path fileDir;
    private static volatile String source;

    private ServerConfig() {
    }

    /** Resolve a string knob: sysprop > env > config file > {@code fallback}. Blank counts as absent. */
    public static String resolve(String sysProp, String envVar, String fileKey, String fallback) {
        crossCheckRegistry(sysProp, envVar, fileKey);
        String v = externalOrNull(sysProp, envVar, fileKey);
        return v != null ? v : fallback;
    }

    /**
     * The external tiers of {@link #resolve} (sysprop &gt; env &gt; file), or null when nothing
     * external supplied a value — the strict-parse flip needs to know
     * whether a value CAME from an operator before deciding a parse failure is a boot refusal.
     */
    private static String externalOrNull(String sysProp, String envVar, String fileKey) {
        String v = System.getProperty(sysProp);
        if (v != null && !v.isBlank()) {
            return v;
        }
        v = envVar == null ? null : System.getenv(envVar);
        if (v != null && !v.isBlank()) {
            return v;
        }
        v = fileKey == null ? null : loaded().getProperty(fileKey);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return null;
    }

    /**
     * {@link #resolve} parsed as int. Fail-soft on an unparsable external value (announce +
     * default) — EXCEPT on a postured boot (a config file is configured,
     * the strict flip): there a malformed operator-supplied value is a boot THROW
     * ({@code cc.config.strict=false} restores announce-and-default).
     */
    public static int resolveInt(String sysProp, String envVar, String fileKey, int fallback) {
        String raw = externalOrNull(sysProp, envVar, fileKey);
        if (raw == null) {
            return fallback;
        }
        raw = raw.trim();
        crossCheckRegistry(sysProp, envVar, fileKey);
        try {
            return Integer.parseInt(raw);
        } catch (RuntimeException ignore) {
            if (postured() && configStrict()) {
                throw new ServerConfigException("posture strict-parse: " + sysProp
                        + " resolved '" + raw + "' from an external tier but it is not an int —"
                        + " fix the value (cc.config.strict=false restores announce-and-default)");
            }
            FallbackAnnounce.announceOnce("knob-parse:" + sysProp,
                    "unparsable int '" + raw + "'; using default " + fallback);
            return fallback;
        }
    }

    /** {@link #resolve} parsed as float; same fail-soft/postured-strict split as {@link #resolveInt}. */
    public static float resolveFloat(String sysProp, String envVar, String fileKey, float fallback) {
        String raw = externalOrNull(sysProp, envVar, fileKey);
        if (raw == null) {
            return fallback;
        }
        raw = raw.trim();
        crossCheckRegistry(sysProp, envVar, fileKey);
        try {
            return Float.parseFloat(raw);
        } catch (RuntimeException ignore) {
            if (postured() && configStrict()) {
                throw new ServerConfigException("posture strict-parse: " + sysProp
                        + " resolved '" + raw + "' from an external tier but it is not a float —"
                        + " fix the value (cc.config.strict=false restores announce-and-default)");
            }
            FallbackAnnounce.announceOnce("knob-parse:" + sysProp,
                    "unparsable float '" + raw + "'; using default " + fallback);
            return fallback;
        }
    }

    /**
     * {@link #resolve} parsed as a boolean: {@code true}/{@code 1}/{@code yes}/{@code on}
     * (case-insensitive) is true, the mirrored set false; anything else is {@code fallback} when
     * absent / false when a different value is set (announced). Same postured-strict split as
     * {@link #resolveInt}: on a postured boot a non-boolean external value THROWS.
     */
    public static boolean resolveBoolean(String sysProp, String envVar, String fileKey, boolean fallback) {
        String v = externalOrNull(sysProp, envVar, fileKey);
        if (v == null) {
            return fallback;
        }
        v = v.trim();
        crossCheckRegistry(sysProp, envVar, fileKey);
        boolean truthy = v.equalsIgnoreCase("true") || v.equals("1")
                || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
        if (!truthy && !(v.equalsIgnoreCase("false") || v.equals("0")
                || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off"))) {
            if (postured() && configStrict()) {
                throw new ServerConfigException("posture strict-parse: " + sysProp
                        + " resolved '" + v + "' from an external tier but it is not a boolean —"
                        + " fix the value (cc.config.strict=false restores announce-and-default)");
            }
            FallbackAnnounce.announceOnce("knob-parse:" + sysProp,
                    "non-boolean '" + v + "' reads as false");
        }
        return truthy;
    }

    /**
     * Guard: the {@code (sysprop, env, fileKey)} triple is
     * hand-typed at every consumer AND independently in its {@link PostureRegistry} row — a
     * consumer typo silently forks the dump from the consumer (the officerSkillsAll class).
     * Any resolve of a REGISTERED key must carry exactly the registry's triple;
     * unregistered keys (watch-tool / client-lane knobs the serve census does not govern) pass
     * untouched. Throws unconditionally — a mismatch is a code bug, never a config error.
     */
    private static void crossCheckRegistry(String sysProp, String envVar, String fileKey) {
        PostureRegistry.Knob k = PostureRegistry.byKey(sysProp);
        if (k == null) {
            return;
        }
        if (!java.util.Objects.equals(k.env(), envVar)
                || !java.util.Objects.equals(k.fileKey(), fileKey)) {
            throw new IllegalStateException("ServerConfig resolve triple forks from the"
                    + " PostureRegistry row for " + sysProp + ": consumer (env=" + envVar
                    + ", fileKey=" + fileKey + ") vs registry (env=" + k.env() + ", fileKey="
                    + k.fileKey() + ") — fix the consumer call or the registry row");
        }
    }

    /**
     * {@link #resolve} for path knobs. A relative path from the CONFIG FILE resolves against the
     * file's own directory (the file lives at the repo root, so repo-relative values like
     * the predict-watch output dir land where the operator expects); a relative path from a
     * sysprop/env resolves against the JVM working directory as it always did. Empty stays empty.
     *
     * <p>A relative non-empty COMPILED DEFAULT (the {@code custom.persistDir} default) is
     * repo-relative by intent, so it resolves like a file-tier value — against the config file's
     * directory when a file is loaded ({@link #configRelative}). The serve JVM's working directory
     * is the game's {@code starsector-core} ({@code run-real-pilot-server.ps1 -WorkingDirectory}),
     * never the repo: the raw fallback used to land product files INSIDE the game install.
     * File-less lanes (rigs, contract children) keep the raw value.
     */
    public static String resolvePath(String sysProp, String envVar, String fileKey, String fallback) {
        crossCheckRegistry(sysProp, envVar, fileKey);
        String direct = externalOrNull(sysProp, envVar, null);
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        String fromFile = fileKey == null ? null : loaded().getProperty(fileKey);
        if (fromFile != null && !fromFile.isBlank()) {
            Path p = Path.of(fromFile.trim());
            Path base = fileDir;
            if (!p.isAbsolute() && base != null) {
                p = base.resolve(p);
            }
            return p.toAbsolutePath().toString();
        }
        return fallback == null || fallback.isBlank() ? fallback : configRelative(fallback);
    }

    /**
     * A repo-relative path constant resolved the file-tier way: against the loaded config file's
     * directory when one is loaded and {@code path} is relative; otherwise (absolute, or a
     * file-less lane) returned as given — the caller's {@code new File(...)} then reads it against
     * the JVM working directory exactly as before.
     */
    public static String configRelative(String path) {
        String trimmed = path.trim();
        loaded();
        Path base = fileDir;
        Path p = Path.of(trimmed);
        if (p.isAbsolute() || base == null) {
            return trimmed;
        }
        return base.resolve(p).toAbsolutePath().toString();
    }

    /** The loaded config file's directory, or null on a
     *  file-less lane (rigs, contract children) — the home of the per-server {@code cc-server.bans}. */
    public static Path configFileDirOrNull() {
        loaded();
        return fileDir;
    }

    /** One line for the startup event: where the config came from ("none" when no file loaded). */
    public static String sourceDescription() {
        loaded();
        return source;
    }

    /**
     * Raw file-tier value for {@link PostureRegistry}'s dump — the registry re-derives the
     * sysprop&gt;env&gt;file precedence itself so it can report WHICH tier supplied the value
     * (the resolve() family collapses that provenance). Null when the file has no such row.
     */
    static String peekFile(String fileKey) {
        return fileKey == null ? null : loaded().getProperty(fileKey);
    }

    /**
     * Postured = a config file is CONFIGURED for this JVM (path non-blank; the file's readability
     * is the strict-load concern, not this predicate's). The M3 enforcement layer
     * ({@link PostureRegistry#enforce()}) and the strict-parse flip fire only on postured boots —
     * file-less lanes (rigs, A/B debug harnesses, contract-test children) stay untouched.
     */
    static boolean postured() {
        String configured = System.getProperty(CONFIG_PATH_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(CONFIG_PATH_ENV);
        }
        return configured != null && !configured.isBlank();
    }

    /**
     * TEST SEAM ONLY: drop the cached config file so a contract test can
     * point {@code cc.server.config} at a temp file and re-load. Production code must never call
     * this — the loaded file is deliberately immutable for the JVM's life.
     */
    public static void resetForTest() {
        synchronized (ServerConfig.class) {
            file = null;
            fileDir = null;
            source = null;
        }
    }

    private static Properties loaded() {
        Properties p = file;
        if (p != null) {
            return p;
        }
        synchronized (ServerConfig.class) {
            if (file != null) {
                return file;
            }
            Properties fresh = new Properties();
            String where = "none";
            String configured = System.getProperty(CONFIG_PATH_PROPERTY);
            if (configured == null || configured.isBlank()) {
                configured = System.getenv(CONFIG_PATH_ENV);
            }
            if (configured != null && !configured.isBlank()) {
                Path path = Path.of(configured.trim());
                if (Files.isReadable(path)) {
                    try (InputStream in = Files.newInputStream(path)) {
                        // A read-cap (the VariantCatalogStore.MAX_FILE_BYTES
                        // precedent): a .properties config is a few KB — over-cap is a config
                        // error and rides the unreadable path below (strict ⇒ refuse to boot).
                        if (Files.size(path) > MAX_CONFIG_BYTES) {
                            throw new IOException("config file exceeds " + MAX_CONFIG_BYTES + " B");
                        }
                        fresh.load(in);
                        fileDir = path.toAbsolutePath().getParent();
                        where = path.toAbsolutePath().toString();
                    } catch (IOException | RuntimeException ignore) {
                        if (configStrict()) {
                            throw new ServerConfigException("configured config file unreadable —"
                                    + " refusing wholesale-defaults boot (set"
                                    + " cc.config.strict=false to serve on defaults): " + path);
                        }
                        where = "unreadable: " + path;
                        FallbackAnnounce.announceOnce("config-file",
                                "configured file unreadable; ALL file-tier knobs fall to defaults: " + path);
                    }
                } else {
                    if (configStrict()) {
                        throw new ServerConfigException("configured config file missing —"
                                + " refusing wholesale-defaults boot (set cc.config.strict=false"
                                + " to serve on defaults): " + path);
                    }
                    where = "missing: " + path;
                    FallbackAnnounce.announceOnce("config-file",
                            "configured file missing; ALL file-tier knobs fall to defaults: " + path);
                }
            }
            source = where;
            file = fresh;
            return fresh;
        }
    }

    /**
     * (83) SV-4 strict knob — sysprop/env ONLY, never the file tier: reading the file here would
     * recurse into {@link #loaded()}, and a value inside a file that failed to load could not
     * govern that failure anyway. Default TRUE.
     */
    private static boolean configStrict() {
        String v = System.getProperty("cc.config.strict");
        if (v == null || v.isBlank()) {
            v = System.getenv("CC_CONFIG_STRICT");
        }
        if (v == null || v.isBlank()) {
            return true;
        }
        v = v.trim();
        return !(v.equalsIgnoreCase("false") || v.equals("0")
                || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off"));
    }
}
