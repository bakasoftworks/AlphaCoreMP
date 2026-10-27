package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The ONE resolved root set every ops reader
 * reads — the web console, the Start chain ({@link ServerOpsLaunch}), the lane
 * follow ({@link LaneFollow.LaneLaunch#publicServe}) and, through the ops file it is handed, the
 * Kill order ({@link ServerOpsKill#killPublicServe} derives {@code run/} from {@link #opsFile}).
 * WHY: package-mode Start runs this same console straight from an unpacked server zip, where the
 * package, its config, its status file, its flight pointer and its start log all sit under ONE root
 * the main is handed ({@code --pkg <root>}); before, every reader resolved its own
 * repo-relative constant ({@link ServerOpsCore#OPS_PKG_ROOT}, {@link ServerOpsCore#PUBLIC_OPS_FILE},
 * {@link ServerOpsCore#PUBLIC_FLIGHT_POINTER}, {@link ServerOpsCore#START_LOG}) against the GUI's
 * cwd, and the selftest seam ({@code ServerOpsLaunch.PKG_ROOT_OVERRIDE}, RETIRED into this record)
 * re-pointed only two of them.
 *
 * <p>ONE record alive at any time ({@link #ROOTS}); precedence is by construction, never by a
 * null-check ladder: a main sets it ONCE before any reader runs (owner mode = the default, so the
 * owner-mode run sets nothing); the selftests set {@link #ownerUnder} around their flow rows and reset
 * {@link #owner()} after (a row never probes owner mode's live package nor appends its log).
 *
 * <p>Owner mode ({@link #owner()}) = today's constants made absolute against the cwd (= the repo
 * root, the GUI's contract); the two mains keep the constants' RELATIVE spelling for their own
 * ops-file arg default (the "watching …" strip and the web's listening line read as before) and
 * {@link #startLogText} keeps the relative spelling in detail texts. Package mode ({@link #packaged})
 * = everything under the package root; its readers are wired to this record.
 *
 * @param mode         owner mode (the stage tool runs first) or an unpacked server package (no stage)
 * @param pkg          the package root the launcher gets as {@code --pkg} (absolute, normalized)
 * @param config       the ONE {@code cc-server.properties} the form edits and the launcher reads
 * @param opsFile      the launcher's {@code <pkg>/run/ops/ops-status.txt} the window follows
 * @param pointer      the launcher's flight pointer {@code <pkg>/run/latest.txt}
 * @param startLog     where a Start's merged output (stage, launcher, the chain's own lines) lands
 * @param stageLock    the cross-JVM staging hold {@code <pkg>/stage.lock}
 * @param windowMemory the anchor file the Setup draft sits beside ({@link OpsSetupDraft#fileFor}; the name is the
 *                     carved operator window's remembered-rect file, kept so a host's draft stays found)
 * @param modsBase     the base a relative {@code mods.roots} row resolves under in the mods tab
 */
public record OpsRoots(Mode mode, Path pkg, Path config, Path opsFile, Path pointer, Path startLog,
        Path stageLock, Path windowMemory, Path modsBase) {

    /** Which console this is: owner mode stages a package first; a package has no stage. */
    public enum Mode {
        OWNER, PACKAGE
    }

    /** The chain's own start log under a root that has no owner-mode ops-log directory beside it. */
    static final String START_LOG_FILE = "ops-start.out.log";
    /** The {@link #windowMemory} file name under a package's {@code run/}. */
    static final String WINDOW_MEMORY_FILE = "ops-gui-window.properties";
    /** Owner mode's {@link #windowMemory} file, CWD-relative like the pid file; its directory is gitignored
     * (a package keeps its own under {@code run/}).
     *
     * */
    static final Path WINDOW_MEMORY_DEFAULT = Paths.get("generated", "ops", WINDOW_MEMORY_FILE);
    /** The packer-only {@code MANIFEST.txt} meta row {@link #packagedChecked} requires. */
    static final String PACK_STAMP = "pack.stamp";

    public OpsRoots {
        Objects.requireNonNull(mode, "mode");
        for (Path p : new Path[] {pkg, config, opsFile, pointer, startLog, stageLock, windowMemory,
                modsBase}) {
            Objects.requireNonNull(p, "every OpsRoots path");
        }
        if (!pkg.isAbsolute()) {
            throw new IllegalArgumentException("OpsRoots.pkg must be absolute: " + pkg);
        }
    }

    /** The live record: a main sets it once before any reader runs; the selftests set / reset it
     * around their flow rows. Default = owner mode. */
    public static final AtomicReference<OpsRoots> ROOTS = new AtomicReference<>(owner());

    /** The record every reader reads. */
    public static OpsRoots get() {
        return ROOTS.get();
    }

    /** Owner mode: the repo-relative constants made absolute against the cwd (the repo root). */
    public static OpsRoots owner() {
        Path pkg = abs(ServerOpsCore.OPS_PKG_ROOT);
        return new OpsRoots(Mode.OWNER, pkg, abs(ServeLauncher.DEFAULT_CONFIG),
                abs(ServerOpsCore.PUBLIC_OPS_FILE), abs(ServerOpsCore.PUBLIC_FLIGHT_POINTER),
                abs(ServerOpsCore.START_LOG), pkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE),
                WINDOW_MEMORY_DEFAULT.toAbsolutePath().normalize(),
                Paths.get("").toAbsolutePath());
    }

    /**
     * The selftest seam (what {@code PKG_ROOT_OVERRIDE} did, now the whole record): owner mode with
     * EVERY path under one temp root, so a probe or chain row reads / writes nothing of the live owner-mode roots.
     * The start log sits beside {@code run/} (not in it) like owner mode's own, which lives
     * OUTSIDE its package (an ops-log directory beside the package directory).
     */
    public static OpsRoots ownerUnder(Path root) {
        Path pkg = root.toAbsolutePath().normalize();
        return under(Mode.OWNER, pkg, pkg.resolve(START_LOG_FILE));
    }

    /**
     * The package-mode GATE both mains run on
     * {@code --pkg <root>} BEFORE setting {@link #ROOTS}. WHY: package mode is never
     * inferred (not from the stage tool's absence, not from the code source) - it is claimed by the
     * flag and then VERIFIED against the packer's own marker: {@code <root>/MANIFEST.txt} parsed by
     * {@link PackageManifest#read} (the whole file under its own cap) must carry a non-blank
     * {@value #PACK_STAMP} meta row, which only {@link PackageFolderWriter} writes, on a folder it placed whole (and,
     * until the packer is re-pointed onto it, the repository's package script) (owner
     * mode's stage writes {@code git.head} + {@code kit.jar.md5} only, so {@code --pkg} on the
     * owner-mode package directory is refused here). Pure apart from that one read: it never sets the live
     * record (the main does).
     *
     * @param root the unpacked package root as typed (made absolute + normalized here, ONCE)
     * @return {@link #packaged} of that root
     * @throws IllegalArgumentException one operator sentence: the manifest is missing / unreadable /
     *         malformed (the reader's reason in brackets), or it carries no {@value #PACK_STAMP} row
     */
    public static OpsRoots packagedChecked(Path root) {
        Path pkg = root.toAbsolutePath().normalize();
        Path manifest = pkg.resolve(ServeLauncher.MANIFEST_FILE);
        PackageManifest read;
        try {
            read = PackageManifest.read(manifest);
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new IllegalArgumentException(manifest + " is missing or malformed ("
                    + (unreadable instanceof NoSuchFileException ? "no such file"
                            : unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage())
                    + ")", unreadable);
        }
        String stamp = read.meta.get(PACK_STAMP);
        if (stamp == null || stamp.isBlank()) {
            throw new IllegalArgumentException(pkg + " is not a packed server (no " + PACK_STAMP
                    + " row in " + ServeLauncher.MANIFEST_FILE + ")");
        }
        return packaged(pkg);
    }

    /**
     * The OWNER-mode OPEN gate {@code OpsConsole --owner} runs
     * ({@link OpsConsole#main}; no {@code --pkg} flag claims a package). WHY: owner mode stages through {@link ServerOpsCore#STAGE_TOOL}, a
     * repository script the published source tree does not carry, so a source build that opened the window with
     * no arguments got a window whose Start, Watch, Dev editor and Joiner GUI each failed in their own way (two
     * dialogs, two silent spawns of an absent script into a log). ONE guard at open, not per-button
     * hiding - and package mode stays NEVER INFERRED (the {@link #packagedChecked} law): the tool's absence
     * refuses owner mode, it never switches the mode. Pure: one file-existence read under {@code cwd}, nothing set.
     *
     * @param cwd the working directory the console was opened from (owner mode's repo-relative paths resolve under it)
     * @return null when the stage tool is a file there (owner mode may open), else {@link #OWNER_OPEN_REFUSAL}
     */
    public static String ownerOpenRefusal(Path cwd) {
        return Files.isRegularFile(cwd.resolve(ServerOpsCore.STAGE_TOOL)) ? null : OWNER_OPEN_REFUSAL;
    }

    /** The one operator sentence {@link #ownerOpenRefusal} returns: how a source build gets a console (a server
     * folder's {@code ops-web.bat}, {@code sh ops-web.sh} on Linux: the browser console is the product's one console,
     * so the sentence names no other). An owner-mode open prints it verbatim when the stage tool is absent; the
     * release check matches it by its first words and {@code ops-web.bat}. A stranger's text: no repository path,
     * no id (row {@code src-owner-refusal-text}). */
    static final String OWNER_OPEN_REFUSAL = "This console's owner mode runs the maintainer's operator scripts, which"
            + " are not in this tree. To run a server from a source build: build.ps1 -ServerFolder <folder> -Package"
            + " <cc-server zip> lays out a server folder; open its ops-web.bat (sh ops-web.sh on Linux).";

    /** An unpacked server package: everything under the root, the start log under
     * its {@code run/}. The mains reach it through {@link #packagedChecked} (the gate). */
    public static OpsRoots packaged(Path root) {
        Path pkg = root.toAbsolutePath().normalize();
        return under(Mode.PACKAGE, pkg, pkg.resolve(ServeLauncher.RUN_DIR).resolve(START_LOG_FILE));
    }

    private static OpsRoots under(Mode mode, Path pkg, Path startLog) {
        Path run = pkg.resolve(ServeLauncher.RUN_DIR);
        return new OpsRoots(mode, pkg, pkg.resolve(ServeLauncher.DEFAULT_CONFIG),
                run.resolve(ServeLauncher.OPS_DIR).resolve(ServeLauncher.OPS_STATUS_FILE),
                run.resolve(ServeLauncher.POINTER_FILE), startLog,
                pkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE), run.resolve(WINDOW_MEMORY_FILE), pkg);
    }

    /** The start log as the operator reads it in detail texts (the busy refusal, the start-requested
     * line, the GONE detail): owner mode keeps the repo-relative spelling its operator knows
     * ({@link ServerOpsCore#START_LOG}; the selftest seam too, whose golden rows pin it), package
     * mode names the absolute file. */
    public String startLogText() {
        return mode == Mode.OWNER ? ServerOpsCore.START_LOG : startLog.toString();
    }

    private static Path abs(String repoRelative) {
        return Paths.get(repoRelative).toAbsolutePath().normalize();
    }
}
