package cc.starsector.server.ops;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The player kit's per-user folder ladder on a Linux host, computed by the console for Join from this PC so the
 * press writes the join settings where the join window reads them and finds the live window's endpoint file
 * where the window writes it. A copy, read and never linked (the client tree is another build), of the client's
 * {@code KitUserDirs} and its shell twin in {@code join-cc.sh}: {@code dataBase} = {@code XDG_DATA_HOME} when set
 * and absolute, else {@code HOME/.local/share}; {@code runtimeBase} = {@code XDG_RUNTIME_DIR} when set and
 * absolute, else {@code dataBase}; the kit's folder {@value #DIR_NAME} under each.
 *
 * <p>WHY the STRING test for "absolute" (a leading {@code /}), never {@link Path#isAbsolute}: the client uses the
 * string test, and a Windows JVM reads {@code /home/u} as rooted but not absolute, so a {@code Path} test would
 * fork these paths from the client's on the box that runs the rows. HOME comes from the environment the press
 * spawns with, never {@code user.home} (the kit reads the env; a passwd HOME that differs would fork the paths).
 * Every value is the caller's raw env text; null / empty / relative = unset. HOME unusable AND no absolute
 * {@code XDG_DATA_HOME} = every answer null (the press then refuses before anything is written).
 *
 * <p>Pure: no file is read, no folder is tested (a probe here could disagree with what the kit already made).
 * Thread: any.
 */
record PosixKitDirs(String homeOrNull, String xdgDataHomeOrNull, String xdgRuntimeDirOrNull) {

    /** The kit's folder name under either base (the client's {@code KitUserDirs.DIR_NAME}). */
    static final String DIR_NAME = "cc-starsector";
    /** The runtime folder's sub-folder holding the join window's endpoint file (the client's {@code JOIN_SUBDIR}). */
    static final String JOIN_SUBDIR = "join";

    /** {@code XDG_DATA_HOME} when absolute, else {@code HOME/.local/share}; null when neither is usable. */
    Path dataBaseOrNull() {
        Path xdg = absoluteOrNull(xdgDataHomeOrNull);
        if (xdg != null) {
            return xdg;
        }
        Path home = absoluteOrNull(homeOrNull);
        return home == null ? null : home.resolve(".local").resolve("share");
    }

    /** {@code XDG_RUNTIME_DIR} when absolute, else {@link #dataBaseOrNull}. */
    Path runtimeBaseOrNull() {
        Path xdg = absoluteOrNull(xdgRuntimeDirOrNull);
        return xdg != null ? xdg : dataBaseOrNull();
    }

    /** {@code <dataBase>/cc-starsector}, or null. */
    Path dataDirOrNull() {
        Path b = dataBaseOrNull();
        return b == null ? null : b.resolve(DIR_NAME);
    }

    /** {@code <runtimeBase>/cc-starsector}, or null. */
    Path runtimeDirOrNull() {
        Path b = runtimeBaseOrNull();
        return b == null ? null : b.resolve(DIR_NAME);
    }

    /** {@code <runtimeBase>/cc-starsector/join}: the endpoint file's folder, where the press log sits too; or null. */
    Path joinDirOrNull() {
        Path d = runtimeDirOrNull();
        return d == null ? null : d.resolve(JOIN_SUBDIR);
    }

    /** {@code v} as a path iff set, non-empty and starting with {@code /} (the client's string test); else null. */
    static Path absoluteOrNull(String v) {
        if (v == null || v.isEmpty() || v.charAt(0) != '/') {
            return null;
        }
        return Paths.get(v);
    }
}
