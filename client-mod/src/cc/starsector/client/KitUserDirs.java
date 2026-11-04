package cc.starsector.client;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * The kit's per-user directory ladder OFF Windows — ONE pure text, mirrored line for line by the kit's Linux
 * launcher so the join window, the in-game credential reader and the shell script resolve the SAME paths.
 *
 * <p>Ladder: {@code runtimeBase} = {@code $XDG_RUNTIME_DIR} when set and absolute, else
 * {@code dataBase}; {@code dataBase} = {@code $XDG_DATA_HOME} when set and absolute, else {@code $HOME/.local/share}.
 * Under {@code runtimeBase/cc-starsector/} live the EPHEMERAL files (the credential handoff, the endpoint file,
 * the plan family); under {@code dataBase/cc-starsector/} the PERSISTENT ones (settings, the cid, the window log).
 *
 * <p>WHY pure (set-and-absolute tests only, no existence or ownership test): any filesystem probe here would let
 * the two resolvers disagree when the launcher has already created or refused a directory. HOME is read from the
 * environment, never from {@code user.home} (JDK 17 takes that from the passwd entry; a HOME that differs would
 * fork the handoff path between the launcher and the game). HOME unset or relative ⇒ every method returns null
 * and each caller keeps its today's null path. Windows callers never reach this class: every Windows expression
 * stays textually as it was ({@code isWindows ? <today> : KitUserDirs...}).
 *
 * <p>B-safe by construction: only {@code String}, {@code Path}, {@code Paths}, {@code Map} — no I/O.
 */
public final class KitUserDirs {
    /** The kit's directory name under either base. */
    public static final String DIR_NAME = "cc-starsector";
    /** Sub-directory of the runtime dir holding {@code <id>.txt} credential handoff files (0700 / 0600). */
    public static final String HANDOFF_SUBDIR = "handoff";
    /** Sub-directory of the runtime dir holding the join window's endpoint file. */
    public static final String JOIN_SUBDIR = "join";

    private KitUserDirs() {}

    /** True on Windows, where no caller uses this ladder. */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /** {@code $XDG_RUNTIME_DIR} when set and absolute, else {@link #dataBase}; null when HOME is unusable. */
    public static Path runtimeBase(Map<String, String> env) {
        Path p = absolute(env.get("XDG_RUNTIME_DIR"));
        return p != null ? p : dataBase(env);
    }

    /** {@code $XDG_DATA_HOME} when set and absolute, else {@code $HOME/.local/share}; null when HOME is unusable. */
    public static Path dataBase(Map<String, String> env) {
        Path p = absolute(env.get("XDG_DATA_HOME"));
        if (p != null) return p;
        Path home = absolute(env.get("HOME"));
        return home == null ? null : home.resolve(".local").resolve("share");
    }

    /** {@code <runtimeBase>/cc-starsector}, or null. */
    public static Path runtimeDir(Map<String, String> env) {
        Path b = runtimeBase(env);
        return b == null ? null : b.resolve(DIR_NAME);
    }

    /** {@code <dataBase>/cc-starsector}, or null. */
    public static Path dataDir(Map<String, String> env) {
        Path b = dataBase(env);
        return b == null ? null : b.resolve(DIR_NAME);
    }

    /** The value as a path iff set, non-empty and absolute (a leading {@code /}); else null. */
    static Path absolute(String v) {
        if (v == null || v.isEmpty() || v.charAt(0) != '/') return null;
        return Paths.get(v);
    }
}
