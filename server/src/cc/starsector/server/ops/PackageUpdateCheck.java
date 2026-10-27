package cc.starsector.server.ops;

import cc.starsector.server.runtime.CustomVariantConfig;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The read-only check {@code update.bat} / {@code update.sh} run with the installed folder's OWN Java before the
 * operating system's unzip writes a byte over that folder. WHY a check and nothing more: the update is the
 * conventional extract-over (the next start of the console or the launcher finishes it from the two manifests),
 * so everything that could make the extract harmful is decided HERE, from the zip's declared names, counts and
 * sizes alone — a folder that is not a downloaded package, a zip that is not one, the other platform's zip, a zip
 * whose file list and manifest disagree, a name outside the program files (an extract-over has no conflict
 * branch: a listed host file name would be overwritten; the program files = this check's frozen set, widened only
 * by the zip's own {@value #PROGRAM_SET_KEY} row and never over a {@link #HOST_PATHS} path), an older zip, a
 * server or console using the folder, a
 * link the extract would write through. No entry is ever inflated by this class (the zip's own manifest is the
 * one exception, read by {@link PackageBaseZip#open} under its caps): the bytes are hashed at the next start
 * against the manifest the extract wrote, and the OS tool verifies each entry's CRC as it writes.
 *
 * <p>Exit codes: {@value #EXIT_PROCEED} = proceed (the LAST stdout line is the preview, or the already-installed
 * line for an equal stamp — the extract still runs so a half-extracted folder converges); {@value #EXIT_REFUSED}
 * = ONE {@value #REFUSED_PREFIX}line naming files, never a value from a host file; {@value #EXIT_ERROR} = an
 * unexpected failure, one line naming its exception class. Nothing under the folder is written and no file is
 * created: the busy probes open only a lock that already exists. A lock or status file this account cannot open
 * REFUSES (the check cannot tell whether a server or a console is running, so it does not proceed).
 *
 *
 *
 *
 *
 */
public final class PackageUpdateCheck {

    static final int EXIT_PROCEED = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_REFUSED = 2;
    static final String REFUSED_PREFIX = "update refused: ";
    /** The Windows package's java: its presence is the platform of a folder and of a zip. */
    static final String WINDOWS_JAVA = "jre/bin/java.exe";
    /** The stamp prefix a folder built from source carries ({@code PackageFolderWriter}'s SOURCE meta). */
    static final String SOURCE_STAMP_PREFIX = "source-";
    /** The folder is not a downloaded package (no manifest, a malformed one, or no release stamp in it). */
    static final String NOT_INSTALLED = "this folder is not an installed server package";
    /**
     * The program trees: every entry under one of these is a program file. A host never keeps its own files in
     * them (host mods live beside {@code CC_Client}, never inside its {@code data/} or {@code jars/}).
     */
    static final List<String> PROGRAM_PREFIXES = List.of("jre/", "classes/", PackageFolderWriter.DATA_PREFIX,
            PackageFolderWriter.MOD_PREFIX + "jars/", PackageFolderWriter.NATIVE_PREFIX, "match-defaults/", "jvm/");
    /**
     * The program files outside those trees, by exact name. {@code LICENSE.txt} at the root is the client mod's
     * licence the packer lists there; the host's live files ({@code cc-server.properties},
     * {@code launcher.properties}, {@code serve-mods/enabled_mods.json}, {@code match/}) are NOT here — a zip that
     * names one would overwrite the host's copy.
     */
    static final Set<String> PROGRAM_NAMES = Set.of(PackageBaseZip.MANIFEST_ENTRY,
            PackageFolderWriter.PREMAIN_REL, PackageFolderWriter.HOLDER_REL, PackageFolderWriter.ARGV_REL,
            // the package's root README: README.md.
            PackageFolderWriter.NOTICES_REL, PackageFolderWriter.LICENSE_REL, "README.md",
            "serve.bat", "stop.bat", "ops-web.bat", "update.bat", "serve.sh", "stop.sh", "ops-web.sh", "update.sh",
            PackageFolderWriter.PROPS_TEMPLATE, "launcher.template.properties",
            PackageFolderWriter.MOD_INFO_REL, PackageFolderWriter.MOD_PREFIX + "LICENSE.txt");
    /**
     * The manifest meta row a package zip declares its own program set in: comma-joined, no spaces, each element an
     * exact name or a tree ending in {@code /}. WHY: this check is frozen in every installed folder, so its own
     * {@link #PROGRAM_NAMES} alone would refuse, forever, the first later release that adds a root file; the zip
     * names its program files, the frozen check admits them, and the frozen {@link #HOST_PATHS} keep every host file
     * out of reach whatever the row says. A zip without the row (an older package) is judged by the frozen set
     * alone, exactly as before the row existed. The packer writes {@link #programSetRow}.
     *
     *
     */
    static final String PROGRAM_SET_KEY = "program.set";
    /** The row's element cap: the packer's union is a few dozen names, so past this the row is no packer's. */
    static final int PROGRAM_SET_MAX_ELEMENTS = 256;
    /** The row's length cap in chars (the union is a few hundred). */
    static final int PROGRAM_SET_MAX_CHARS = 4096;
    /** A malformed row's one sentence: it never echoes an element (a row past its caps could hold anything). */
    static final String PROGRAM_SET_MALFORMED = "the zip's program list is malformed";
    /** A declared host path's sentence; the element follows, masked. */
    static final String DECLARES_HOST_FILE = "the zip declares a host file as a program file: ";
    /** The bans writer's root file (a literal: its runtime owner is package-private; row puc-host-paths-pinned). */
    static final String BANS_FILE = "cc-server.bans";
    /** The hidden-ships writer's root file (a literal, pinned the same way). */
    static final String HIDDEN_SHIPS_FILE = "cc-server.hidden-ships";
    /** The sibling each of those two persist sinks writes first and then moves over its file. */
    static final String PERSIST_TMP_SUFFIX = ".tmp";
    /**
     * The paths the host's own programs write under the folder, exact root names and trees ending in {@code /}: the
     * live config and launcher settings, the bans and hidden-ships lists with their temp siblings, the debug-watch
     * lease, the console's stage lock, a failed write's manifest, and the trees of the host's mods (minus the client
     * mod's own folder, {@link #PROGRAM_MOD_TREE}), live match, run state, variant inbox, custom-variant store and
     * kits (the release kit's own row is the frozen set's, admitted before this list is read). FROZEN with the
     * check: a declared element the frozen set does not admit and that is one of these, lies under one, or holds
     * one ({@link #hostRelated}) refuses the zip.
     *
     * <p>Honest limits: a path the host CHOOSES (a mods root inside the folder, a match descriptor's password
     * sidecars, the custom-variant persist / export / policy paths, the net sink path, the command and ops file
     * rows) cannot sit on a frozen list, so a declaration could name one; the zip's symlink entries are not
     * inspected; the start's repair keeps its own program set, so a declared root file a later version drops stays
     * behind as a harmless leftover.
     *
     *
     */
    static final List<String> HOST_PATHS = List.of(ServeLauncher.DEFAULT_CONFIG,
            ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES, BANS_FILE, BANS_FILE + PERSIST_TMP_SUFFIX, HIDDEN_SHIPS_FILE,
            HIDDEN_SHIPS_FILE + PERSIST_TMP_SUFFIX, ServerOpsLaunch.WATCH_LEASE_FILE, ServerOpsLaunch.STAGE_LOCK_FILE,
            PackageFolderWriter.FAILED_REL, PackageModSync.MODS_DIR + "/", OpsMatchApply.MATCH_DIR + "/",
            ServeLauncher.RUN_DIR + "/", CustomVariantConfig.EXPORT_DIR_DEFAULT + "/",
            rootTree(CustomVariantConfig.PERSIST_DIR_DEFAULT), PackageFolderWriter.KITS_PREFIX);
    /**
     * The client mod's own folder inside the host's mods tree: PROGRAM in whole, carved out of {@link #HOST_PATHS}'
     * mods tree by {@link #hostRelated}. WHY: this check is frozen in every installed folder, so without the carve a
     * later release could never add a tree or file under the client mod beyond its frozen {@code data/} and
     * {@code jars/} (new graphics, a second jar): the declaration would read as a host path and refuse the update.
     * Safe because no host program writes there: hosts keep their own mods BESIDE it, the mod sync reserves its
     * case-folded name as a mirror target ({@link PackageModSync#CLIENT_FOLDER}), and every update replaces it.
     *
     *
     */
    static final String PROGRAM_MOD_TREE = PackageFolderWriter.MOD_PREFIX;
    /**
     * A name segment Windows would not store as written: a leading {@code ~} (a short-name or temp-file shape),
     * a trailing dot or space (stripped by Windows, so the name lands on another file), a device name with or
     * without an extension (the packer's own bad-segment law, plus the {@code ~}).
     */
    private static final Pattern BAD_SEGMENT =
            Pattern.compile("^~|[. ]$|^(?i:CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]|CONIN\\$|CONOUT\\$)(?:\\.|$)");
    /** Quiet failures of this check: a directory-entry listing that failed after the zip opened. */
    static final AtomicLong QUIET_FAILURES = new AtomicLong();

    private PackageUpdateCheck() {
    }

    /**
     * {@code PackageUpdateCheck --pkg <installed folder> --zip <new package zip>}: runs {@link #check} and exits
     * with its code; a malformed command line = one usage line, {@value #EXIT_ERROR}.
     *
     *
     */
    public static void main(String[] args) {
        Path pkg = null;
        Path zip = null;
        boolean usage = false;
        try {
            for (int i = 0; i < args.length && !usage; i++) {
                if ("--pkg".equals(args[i]) && i + 1 < args.length && pkg == null) {
                    pkg = Path.of(args[++i]);
                } else if ("--zip".equals(args[i]) && i + 1 < args.length && zip == null) {
                    zip = Path.of(args[++i]);
                } else {
                    usage = true;
                }
            }
        } catch (InvalidPathException unusable) {
            // the usage line below is this failure's record (a path the OS cannot name is no package)
            usage = true;
        }
        int code;
        if (usage || pkg == null || zip == null) {
            System.out.println("usage: PackageUpdateCheck --pkg <the installed server folder> --zip <the new server package zip>");
            code = EXIT_ERROR;
        } else {
            code = check(pkg, zip, System.out);
        }
        System.out.flush();
        System.exit(code);
    }

    /**
     * The whole check over {@code pkg} (the installed folder) and {@code zip} (the downloaded package): prints
     * zero or more lines to {@code out} (the launcher's orphan probe may print one warning) and, LAST, exactly one
     * verdict line; returns {@value #EXIT_PROCEED}, {@value #EXIT_REFUSED} or {@value #EXIT_ERROR}.
     *
     *
     */
    public static int check(Path pkg, Path zip, PrintStream out) {
        try {
            out.println(verdict(pkg.toAbsolutePath().normalize(), zip.toAbsolutePath().normalize(), out));
            return EXIT_PROCEED;
        } catch (Refused refused) {
            out.println(REFUSED_PREFIX + refused.getMessage());
            return EXIT_REFUSED;
        } catch (IOException | RuntimeException unexpected) {
            out.println("update check failed (" + unexpected.getClass().getSimpleName()
                    + "): nothing was changed - run the update again; if this repeats, download the package again");
            return EXIT_ERROR;
        }
    }

    /**
     * The refusals in their fixed order, then the verdict line. Each refusal is a {@link Refused} whose message
     * is the sentence after {@value #REFUSED_PREFIX}.
     *
     *
     */
    private static String verdict(Path root, Path zip, PrintStream out) throws Refused, IOException {
        boolean windowsFolder = Files.isRegularFile(root.resolve(WINDOWS_JAVA));
        String ext = windowsFolder ? ".bat" : ".sh";
        // The folder must be a downloaded package: the packer's MANIFEST.txt with a release stamp
        PackageManifest installed;
        try {
            installed = PackageManifest.read(root.resolve(ServeLauncher.MANIFEST_FILE));
        } catch (IOException | IllegalArgumentException notOne) {
            // the refusal line is this failure's record (a missing or malformed manifest = not a package)
            throw new Refused(NOT_INSTALLED);
        }
        String oldStamp = installed.meta.get(OpsRoots.PACK_STAMP);
        if (oldStamp == null || oldStamp.isBlank()) {
            throw new Refused(NOT_INSTALLED);
        }
        if (oldStamp.startsWith(SOURCE_STAMP_PREFIX)) {
            throw new Refused("this folder was built from source - rebuild it with build.ps1; update" + ext
                    + " is for downloaded packages");
        }
        // The zip must open as a server package under the base reader's caps and name law
        String notPackage = PackageManifest.mask(zip.toString()) + " is not an AlphaCoreMP server package";
        PackageBaseZip base;
        try {
            base = PackageBaseZip.open(zip);
        } catch (IOException | IllegalArgumentException notZip) {
            // the refusal line is this failure's record (unreadable, over a cap, a bad name, no manifest)
            throw new Refused(notPackage);
        }
        try (base) {
            return verdictOpened(root, zip, base, installed, oldStamp, windowsFolder, ext, notPackage, out);
        }
    }

    /**
     * The refusals after the zip opened, in order: platform, entry set, program set, version, busy, links; then
     * the preview from the two manifests' diff.
     *
     *
     */
    private static String verdictOpened(Path root, Path zip, PackageBaseZip base, PackageManifest installed,
            String oldStamp, boolean windowsFolder, String ext, String notPackage, PrintStream out)
            throws Refused, IOException {
        PackageManifest next = base.manifest();
        Set<String> files = new HashSet<>(base.names());
        // The same platform: the other platform's jre would replace this one's
        boolean windowsZip = files.contains(WINDOWS_JAVA);
        if (windowsZip != windowsFolder) {
            throw new Refused(windowsZip ? "this zip is the Windows package (this folder is the Linux one)"
                    : "this zip is the Linux package (this folder is the Windows one)");
        }
        // What the extract writes = what the manifest lists, both ways
        for (String name : base.names()) {
            if (!PackageBaseZip.MANIFEST_ENTRY.equals(name) && !next.sha256ByPath.containsKey(name)) {
                throw new Refused(disagree(name));
            }
        }
        for (String row : next.sha256ByPath.keySet()) {
            if (!files.contains(row)) {
                throw new Refused(disagree(row));
            }
        }
        // Every name the extract writes is a program file (directory entries: a parent of one); the
        // frozen set admits first, then the zip's own declared set (read whole before any name is judged)
        String kitRow = releaseKitRow(next.packKit());
        Declared declared = declared(next.meta, kitRow);
        Set<String> parents = new HashSet<>();
        for (String name : base.names()) {
            if (!inProgramSet(name, kitRow) && !declaredAdmits(name, declared)) {
                throw new Refused(outsideProgramSet(name));
            }
            for (int slash = name.indexOf('/'); slash > 0; slash = name.indexOf('/', slash + 1)) {
                parents.add(name.substring(0, slash));
            }
        }
        List<String> dirs = directoryEntries(zip, notPackage);
        for (String dir : dirs) {
            boolean frozenTree = segmentsOk(dir) && underProgramTree(dir + "/");
            // a declared tree extends the directory law the same way (its element was host-tested when read)
            boolean declaredTree = segmentsOk(dir) && PackageFolderWriter.portableRel(dir)
                    && underTree(dir + "/", declared.trees());
            if (!parents.contains(dir) && !frozenTree && !declaredTree) {
                throw new Refused(outsideProgramSet(dir + "/"));
            }
        }
        // Both stamps are release stamps; never a downgrade
        String newStamp = next.meta.get(PackageBaseZip.PACK_STAMP);
        if (!KitPackRules.STAMP.matcher(oldStamp).matches()) {
            throw new Refused(NOT_INSTALLED);
        }
        if (newStamp == null || !KitPackRules.STAMP.matcher(newStamp).matches()) {
            throw new Refused(notPackage);
        }
        if (newStamp.compareTo(oldStamp) < 0) {
            throw new Refused("the zip (" + newStamp + ") is older than the installed package (" + oldStamp
                    + ") - no downgrade");
        }
        refuseWhenBusy(root, ext, out);
        // The extract never writes through a link: every target and each parent up to the folder
        Set<Path> walked = new HashSet<>();
        List<String> targets = new ArrayList<>(base.names());
        targets.addAll(dirs);
        for (String name : targets) {
            for (Path p = root.resolve(name).normalize(); p != null && p.startsWith(root) && !p.equals(root)
                    && walked.add(p); p = p.getParent()) {
                if (PackageFolderWriter.isLink(p)) {
                    throw new Refused(PackageManifest.mask(root.relativize(p).toString())
                            + " is a link - the update does not write through links");
                }
            }
        }
        if (newStamp.equals(oldStamp)) {
            return "already installed (" + oldStamp + ") - the start repairs the folder";
        }
        int replace = 0;
        int add = 0;
        int remove = 0;
        for (Map.Entry<String, String> row : next.sha256ByPath.entrySet()) {
            String was = installed.sha256ByPath.get(row.getKey());
            if (was == null) {
                add++;
            } else if (!was.equals(row.getValue())) {
                replace++;
            }
        }
        for (String row : installed.sha256ByPath.keySet()) {
            if (!next.sha256ByPath.containsKey(row)) {
                remove++;
            }
        }
        return "update: " + oldStamp + " -> " + newStamp + ": " + replace + " files to replace, " + add
                + " to add, " + remove + " to remove";
    }

    /**
     * The busy refusals: a launcher holding {@code run/serve.lock}, a console holding {@code run/console.lock},
     * a console staging this folder ({@code stage.lock}), an orphaned serve still writing its status file (its
     * launcher gone). Each lock goes through {@link #hold}: a lock nobody created is held by nobody, and the check
     * creates no file of its own. A lock or status file that exists but this account cannot open (a server
     * started with sudo, a service account) REFUSES: the check cannot tell whether that server is running, so it
     * never reads "cannot open" as "free" (the launcher's own probe does, on purpose, for its own start).
     *
     *
     *
     *
     */
    private static void refuseWhenBusy(Path root, String ext, PrintStream out) throws Refused {
        boolean windows = ".bat".equals(ext);
        Path run = root.resolve(ServeLauncher.RUN_DIR);
        refuseOnHold(root, run.resolve(ServeLauncher.LOCK_FILE), windows,
                "the server is running: stop it (stop" + ext + ") first");
        refuseOnHold(root, run.resolve(OpsConsole.CONSOLE_LOCK_FILE), windows,
                "the console is open: close its window first");
        // the packaged record's stage lock, never the live record's: this JVM never sets the live record
        refuseOnHold(root, OpsRoots.packaged(root).stageLock(), windows, "a console is staging this folder");
        Path status = run.resolve(ServeLauncher.OPS_DIR).resolve(ServeLauncher.OPS_STATUS_FILE);
        // an unsearchable run/ops/ hides the file's existence: that is "cannot tell" too, never "absent"
        boolean undeterminable = !Files.exists(status) && !Files.notExists(status);
        if (Files.isRegularFile(status) || undeterminable) {
            try {
                Files.newInputStream(status).close();
            } catch (IOException unreadable) {
                // the refusal sentence is this catch's one record (no exception text: it may carry a host path)
                throw new Refused("cannot tell whether a server is still running - " + rel(root, status, windows)
                        + " is not readable (started by another account?)");
            }
        }
        long writer = ServeLauncher.liveStatusWriter(status, out);
        if (writer > 0L) {
            throw new Refused("a server is still running (pid " + writer + ") and writing " + status
                    + "; its launcher is gone, so stop" + ext + " cannot reach it - end process " + writer
                    + " yourself (Task Manager / taskkill, kill on Linux; the Ops console's Kill), then run the"
                    + " update again");
        }
    }

    /** One busy lock: {@code heldSentence} when another process holds it, the cannot-tell sentence when unreadable. */
    private static void refuseOnHold(Path root, Path lock, boolean windows, String heldSentence) throws Refused {
        Hold h = hold(lock);
        if (h == Hold.HELD) {
            throw new Refused(heldSentence);
        }
        if (h == Hold.UNREADABLE) {
            throw new Refused("cannot tell whether the server (or the console) is running - "
                    + rel(root, lock, windows) + " is not readable (started by another account?)");
        }
    }

    /**
     * What one lock file says. WHY this class's own probe and not the launcher's: the launcher's probe opens with
     * CREATE and reads an unopenable file as free (right for its own start, which the live holder refuses
     * anyway); an update over a running server is the harm this check exists to stop, so here an unopenable lock
     * is {@link #UNREADABLE}, never {@link #FREE}.
     *
     *
     */
    private enum Hold {
        /** Absent (nobody created it, so nobody holds it), or its lock was won here and released at once. */
        FREE,
        /** Another process holds its lock (or this JVM does: the rows' way of playing a server). */
        HELD,
        /** It exists but this account cannot open it for writing, or the lock call failed: the check cannot tell. */
        UNREADABLE
    }

    /**
     * Probes {@code lock} without creating it: confirmed absent = {@link Hold#FREE} (a path whose existence cannot
     * be determined is probed, so an unsearchable folder reads as unreadable, never as absent); else a
     * {@code WRITE} channel (no {@code CREATE}) and {@code tryLock}: no lock or a hold by this JVM =
     * {@link Hold#HELD}; a won lock is released at once = {@link Hold#FREE}; a failed open or lock call =
     * {@link Hold#UNREADABLE}.
     *
     *
     */
    private static Hold hold(Path lock) {
        if (Files.notExists(lock, LinkOption.NOFOLLOW_LINKS)) {
            return Hold.FREE;
        }
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock won;
            try {
                won = ch.tryLock();
            } catch (OverlappingFileLockException heldInThisJvm) {
                return Hold.HELD;
            }
            if (won == null) {
                return Hold.HELD;
            }
            won.release();
            return Hold.FREE;
        } catch (IOException | RuntimeException unopenable) {
            // the caller's refusal sentence is this catch's one record (it names the file, never the exception)
            return Hold.UNREADABLE;
        }
    }

    /**
     * {@code file} as the refusal names it: relative to the folder in the folder's platform separator
     * ({@code run\serve.lock} on Windows, {@code run/serve.lock} on Linux); a file outside the folder prints its
     * full path.
     *
     *
     */
    private static String rel(Path root, Path file, boolean windows) {
        if (!file.startsWith(root) || file.equals(root)) {
            return file.toString();
        }
        StringBuilder out = new StringBuilder();
        for (Path segment : root.relativize(file)) {
            if (out.length() > 0) {
                out.append(windows ? '\\' : '/');
            }
            out.append(segment);
        }
        return out.toString();
    }

    /**
     * True when the FROZEN set admits {@code name}: every segment passes {@link #BAD_SEGMENT}'s law, and it is one
     * of {@link #PROGRAM_NAMES}, the release kit's row, or under one of {@link #PROGRAM_PREFIXES} (the zip's own
     * declaration may admit more: {@link #declaredAdmits}).
     *
     *
     */
    static boolean inProgramSet(String name, String kitRow) {
        return segmentsOk(name)
                && (PROGRAM_NAMES.contains(name) || name.equals(kitRow) || underProgramTree(name));
    }

    private static boolean underProgramTree(String name) {
        return underTree(name, PROGRAM_PREFIXES);
    }

    /** True when {@code name} lies strictly under one of {@code trees} (each ends in {@code /}). */
    private static boolean underTree(String name, List<String> trees) {
        for (String prefix : trees) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@value #PROGRAM_SET_KEY} value the packer writes: {@link #PROGRAM_NAMES} and {@link #PROGRAM_PREFIXES},
     * sorted, comma-joined. Read from this check's own constants, so the declaration a release carries and the
     * frozen set its check ships can never be typed twice.
     *
     *
     */
    static String programSetRow() {
        List<String> all = new ArrayList<>(PROGRAM_NAMES);
        all.addAll(PROGRAM_PREFIXES);
        all.sort(null);
        return String.join(",", all);
    }

    /** A zip's declared program set: exact names, and trees ending in {@code /}. */
    private record Declared(Set<String> names, List<String> trees) {
        /** No {@value PackageUpdateCheck#PROGRAM_SET_KEY} row: the frozen set alone. */
        static final Declared NONE = new Declared(Set.of(), List.of());
    }

    /**
     * The zip's declared program set, from its {@value #PROGRAM_SET_KEY} row; {@link Declared#NONE} when the row is
     * absent. Refuses the whole zip, before any name is judged: a row past either cap, an empty or duplicate
     * element, or an element failing the name law ({@link #BAD_SEGMENT}, then the packer's portable path law, which
     * holds no {@code ~}, so a Windows short name never rides a declaration) = {@value #PROGRAM_SET_MALFORMED}; an
     * element the frozen set does not admit that {@link #hostRelated} ties to a {@link #HOST_PATHS} path = the
     * sentence naming it, never ignored. An element the frozen set already admits is never host-tested: the release
     * kit's row lies under the host's kits tree (the client mod's trees pass the host test on their own, by
     * {@link #PROGRAM_MOD_TREE}).
     *
     *
     */
    private static Declared declared(Map<String, String> meta, String kitRow) throws Refused {
        String row = meta.get(PROGRAM_SET_KEY);
        if (row == null) {
            return Declared.NONE;
        }
        if (row.length() > PROGRAM_SET_MAX_CHARS) {
            throw new Refused(PROGRAM_SET_MALFORMED);
        }
        String[] elements = row.split(",", -1);
        if (elements.length > PROGRAM_SET_MAX_ELEMENTS) {
            throw new Refused(PROGRAM_SET_MALFORMED);
        }
        Set<String> names = new HashSet<>();
        List<String> trees = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String element : elements) {
            boolean tree = element.endsWith("/");
            String path = tree ? element.substring(0, element.length() - 1) : element;
            // segmentsOk refuses an empty element and a bare "/" (an empty segment) as well
            if (!seen.add(element) || !segmentsOk(path) || !PackageFolderWriter.portableRel(path)) {
                throw new Refused(PROGRAM_SET_MALFORMED);
            }
            if (!frozenAdmits(element, kitRow) && hostRelated(element, HOST_PATHS)) {
                throw new Refused(DECLARES_HOST_FILE + PackageManifest.mask(element));
            }
            if (tree) {
                trees.add(element);
            } else {
                names.add(element);
            }
        }
        return new Declared(Set.copyOf(names), List.copyOf(trees));
    }

    /**
     * True when the frozen set already admits a declared element: an exact name {@link #inProgramSet} admits, or a
     * tree equal to or under one of {@link #PROGRAM_PREFIXES}.
     */
    private static boolean frozenAdmits(String element, String kitRow) {
        if (!element.endsWith("/")) {
            return inProgramSet(element, kitRow);
        }
        for (String prefix : PROGRAM_PREFIXES) {
            if (element.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code path} and one of {@code hosts} are related on the case-folded, slash-normalized path
     * line: {@code lower(path)+"/"} and {@code lower(host)+"/"} are prefix-related in EITHER direction. So a name
     * equal to a host path in any case, a name under a host tree, a tree over a host file (a directory the
     * extract would make over it) and a file over a host tree all relate; a mere string prefix ({@code serve}
     * against {@code serve-mods/}) does not. Case folds with {@link Locale#ROOT} on both sides because a Windows
     * extract writes {@code CC-SERVER.PROPERTIES} onto the host's {@code cc-server.properties}.
     *
     * <p>The one carve: a path that lies under the client mod's own folder ({@link #PROGRAM_MOD_TREE}, case-folded
     * the same way, the folder's own tree element included, its bare name not) relates to no host path OUTSIDE that
     * folder, so the host's mods tree above it never claims it; a host path inside the folder (none is frozen) still
     * relates. Every other path under the mods tree ({@code serve-mods/OtherMod/}, {@code enabled_mods.json}, the
     * mods tree itself) stays host.
     *
     *
     *
     *
     */
    static boolean hostRelated(String path, List<String> hosts) {
        String p = slashed(path.toLowerCase(Locale.ROOT));
        String modTree = PROGRAM_MOD_TREE.toLowerCase(Locale.ROOT);
        boolean inModTree = path.toLowerCase(Locale.ROOT).startsWith(modTree);
        for (String host : hosts) {
            String h = slashed(host.toLowerCase(Locale.ROOT));
            if (inModTree && !h.startsWith(modTree)) {
                continue; // an ancestor host tree (the mods tree) never claims the program's own folder
            }
            if (p.startsWith(h) || h.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String slashed(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    /**
     * True when the zip's declaration admits {@code name} (one the frozen set refused): it equals a declared name or
     * lies under a declared tree, and passes the name law and the portable path law. No host test here, by
     * construction: every element that can admit such a name was host-tested by {@link #declared}, and a host path
     * related to the name is related to the element too (two prefixes of one path are prefix-related). A name under
     * a declared EXACT name (a tree declared without its {@code /}) refuses as a malformed row.
     *
     *
     */
    private static boolean declaredAdmits(String name, Declared declared) throws Refused {
        if (declared.names().contains(name) || underTree(name, declared.trees())) {
            return segmentsOk(name) && PackageFolderWriter.portableRel(name);
        }
        for (String exact : declared.names()) {
            if (name.startsWith(exact + "/")) {
                throw new Refused(PROGRAM_SET_MALFORMED);
            }
        }
        return false;
    }

    /** The first segment of the relative directory {@code dir}, as a tree (ending in {@code /}). */
    private static String rootTree(String dir) {
        int slash = dir.indexOf('/');
        return (slash < 0 ? dir : dir.substring(0, slash)) + "/";
    }

    private static boolean segmentsOk(String name) {
        for (String seg : name.split("/", -1)) {
            if (seg.isEmpty() || BAD_SEGMENT.matcher(seg).find()) {
                return false;
            }
        }
        return true;
    }

    /** {@code kits/<pack.kit>} when the zip's manifest names a plain file name, else null (no kits/ row allowed). */
    private static String releaseKitRow(String packKit) {
        if (packKit == null || packKit.isBlank() || packKit.contains("/")) {
            return null;
        }
        return PackageFolderWriter.KITS_PREFIX + packKit;
    }

    /**
     * The zip's DIRECTORY entries (bare, no trailing slash): {@link PackageBaseZip#names} lists files only, and an
     * extractor creates a directory entry's folder too (over a host file of that name, for some tools), so the
     * program-set and link laws cover them. The base reader already applied the name law and the caps to them.
     */
    private static List<String> directoryEntries(Path zip, String notPackage) throws Refused {
        List<String> dirs = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (Enumeration<? extends ZipEntry> all = zf.entries(); all.hasMoreElements(); ) {
                String name = all.nextElement().getName();
                if (name.endsWith("/")) {
                    dirs.add(name.substring(0, name.length() - 1));
                }
            }
        } catch (IOException | RuntimeException reopened) {
            QUIET_FAILURES.incrementAndGet();
            throw new Refused(notPackage);
        }
        return dirs;
    }

    private static String disagree(String name) {
        return "the zip's file list and its contents disagree (" + PackageManifest.mask(name) + ")";
    }

    private static String outsideProgramSet(String name) {
        return "the zip lists a file outside the program set: " + PackageManifest.mask(name);
    }

    /** One refusal: its message is the sentence after {@value #REFUSED_PREFIX} (no stack: it is an answer). */
    private static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(String sentence) {
            super(sentence, null, false, false);
        }
    }
}
