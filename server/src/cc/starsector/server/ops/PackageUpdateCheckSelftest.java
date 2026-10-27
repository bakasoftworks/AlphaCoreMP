package cc.starsector.server.ops;

import cc.starsector.server.runtime.CustomVariantConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Rows for the update check ({@link PackageUpdateCheck#check}) the update scripts run before the operating
 * system's unzip writes over an installed folder: every refusal in its order (not an installed package, not a
 * server package zip, the other platform, a file list that disagrees with the manifest, a name outside the
 * program set, an older stamp, a server / console / staging console / orphaned serve using the folder, a link
 * the extract would write through, a folder built from source), the two proceed verdicts (the equal-stamp line,
 * the preview's three counts), the zip's declared program set (a declared new root file proceeds; a declared
 * host path, a malformed row or an undeclared name refuses; no row = the frozen law; the client mod's own folder
 * is the program's, every other path under the mods tree the host's) and the read-only promise. The check is driven through its programmatic entry with
 * its lines captured, never a child JVM (the one child is Windows' {@code mklink /J}, for the directory-link row
 * when symbolic links are refused). Fixtures only: every row builds its installed folder and its zips under its
 * own {@link Files#createTempDirectory} dir, deleted in {@code finally}; a lock a row holds is taken in THIS JVM
 * (the lock probes read a same-JVM hold as held) and released before the delete, and a read deny a row makes is
 * restored before it. Prints {@code PASS <row>} /
 * {@code FAIL <row>: <the regression that flips it>} / {@code SETUP-<row>: ...} (a fixture that never staged,
 * never a kill) / {@code SKIP <row>: ...} (the OS refused the link a row needs: a file symlink on Windows without
 * the privilege or developer mode; or this account reads through a read deny: root, a volume without ACLs) and
 * exits 0 iff no row failed.
 * Run: {@code java -cp server/classes cc.starsector.server.ops.PackageUpdateCheckSelftest}.
 *
 *
 *
 */
public final class PackageUpdateCheckSelftest {

    private static int failures;
    private static int skips;
    /** The lock channels the current row holds; the harness closes them before it deletes the row's dir. */
    private static final List<FileChannel> HELD = new ArrayList<>();
    /** The read denies the current row made; the harness restores them before it deletes the row's dir. */
    private static final List<Runnable> DENIED = new ArrayList<>();

    /** The installed folder's release stamp. */
    private static final String OLD = "20261001-120000";
    /** A newer release stamp (the update). */
    private static final String NEW = "20261024-090000";
    /** An older release stamp (the downgrade). */
    private static final String OLDER = "20260901-000000";
    /** The release kit's file name, as a zip's {@code pack.kit} row names it. */
    private static final String KIT = "cc-kit-20261024-090000.zip";
    private static final String LINUX_JAVA = "jre/bin/java";
    /** The bound on the one {@code mklink /J} child a Windows run makes (it returns in milliseconds). */
    private static final long JUNCTION_WAIT_SECONDS = 20;

    private PackageUpdateCheckSelftest() {
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** The build gate's entry: every row, the summary line, the failure count returned (SKIP rows do not count). */
    public static int run() {
        failures = 0;
        skips = 0;
        row("puc-not-installed", PackageUpdateCheckSelftest::notInstalled);
        row("puc-zip-not-ours", PackageUpdateCheckSelftest::zipNotOurs);
        row("puc-traversal-name", PackageUpdateCheckSelftest::traversalName);
        row("puc-over-cap-size", PackageUpdateCheckSelftest::overCapSize);
        row("puc-entry-unlisted", PackageUpdateCheckSelftest::entryUnlisted);
        row("puc-row-not-in-zip", PackageUpdateCheckSelftest::rowNotInZip);
        row("puc-outside-program-set", PackageUpdateCheckSelftest::outsideProgramSet);
        row("puc-platform-mismatch", PackageUpdateCheckSelftest::platformMismatch);
        row("puc-older-stamp", PackageUpdateCheckSelftest::olderStamp);
        row("puc-equal-stamp", PackageUpdateCheckSelftest::equalStamp);
        row("puc-newer-preview", PackageUpdateCheckSelftest::newerPreview);
        row("puc-serve-lock-held", PackageUpdateCheckSelftest::serveLockHeld);
        row("puc-console-lock-held", PackageUpdateCheckSelftest::consoleLockHeld);
        row("puc-stage-lock-held", PackageUpdateCheckSelftest::stageLockHeld);
        row("puc-orphan-status-writer", PackageUpdateCheckSelftest::orphanStatusWriter);
        row("puc-link-at-target", PackageUpdateCheckSelftest::linkAtTarget);
        row("puc-link-at-parent", PackageUpdateCheckSelftest::linkAtParent);
        row("puc-source-stamp", PackageUpdateCheckSelftest::sourceStamp);
        row("puc-lock-unreadable", PackageUpdateCheckSelftest::lockUnreadable);
        row("puc-status-unreadable", PackageUpdateCheckSelftest::statusUnreadable);
        row("puc-declared-root-proceeds", PackageUpdateCheckSelftest::declaredRootProceeds);
        row("puc-declared-host-file-refused", PackageUpdateCheckSelftest::declaredHostFileRefused);
        row("puc-declared-already-frozen-ok", PackageUpdateCheckSelftest::declaredAlreadyFrozenOk);
        row("puc-undeclared-root-refused", PackageUpdateCheckSelftest::undeclaredRootRefused);
        row("puc-declared-prefix-dirs", PackageUpdateCheckSelftest::declaredPrefixDirs);
        row("puc-declared-bad-name-refused", PackageUpdateCheckSelftest::declaredBadNameRefused);
        row("puc-no-row-old-law", PackageUpdateCheckSelftest::noRowOldLaw);
        row("puc-host-paths-pinned", PackageUpdateCheckSelftest::hostPathsPinned);
        row("puc-declared-mod-newdir-admitted", PackageUpdateCheckSelftest::declaredModNewdirAdmitted);
        row("puc-declared-mod-jar-admitted", PackageUpdateCheckSelftest::declaredModJarAdmitted);
        row("puc-declared-other-mod-refused", PackageUpdateCheckSelftest::declaredOtherModRefused);
        row("puc-enabled-mods-still-host", PackageUpdateCheckSelftest::enabledModsStillHost);
        row("puc-declared-mod-case-admitted", PackageUpdateCheckSelftest::declaredModCaseAdmitted);
        String skipped = skips == 0 ? "" : " (" + skips + " SKIP)";
        System.out.println(failures == 0 ? "PackageUpdateCheckSelftest: every row PASS" + skipped
                : "PackageUpdateCheckSelftest: " + failures + " row(s) did not pass" + skipped);
        return failures;
    }

    // ---- rows ----

    private static void notInstalled(Path dir) throws Exception {
        Path zip = stage("the new zip", () -> zip(dir, NEW, program(true)));
        Path bare = stage("a folder without MANIFEST.txt", () -> PackageZipSelftest.tree(dir.resolve("bare"), program(true)));
        expectRefused("a folder without MANIFEST.txt was not refused as not installed (flips if the installed-package"
                + " law is dropped)", bare, zip, PackageUpdateCheck.NOT_INSTALLED);
        Path stampless = stage("a folder whose manifest has no pack.stamp",
                () -> installed(dir, null, program(true)));
        expectRefused("a folder whose manifest has no pack.stamp was not refused as not installed (flips if a"
                + " stampless manifest passes)", stampless, zip, PackageUpdateCheck.NOT_INSTALLED);
        Path garbled = stage("a folder whose manifest does not parse", () -> {
            Path p = PackageZipSelftest.tree(dir.resolve("garbled"), program(true));
            Files.write(p.resolve(PackageBaseZip.MANIFEST_ENTRY), ascii("not a manifest row\n"));
            return p;
        });
        expectRefused("a folder whose manifest does not parse was not refused as not installed (flips if the"
                + " manifest's parse failure escapes as the error exit)", garbled, zip, PackageUpdateCheck.NOT_INSTALLED);
        Path oddStamp = stage("a folder whose stamp is no release stamp", () -> installed(dir, "1.0", program(true)));
        expectRefused("a folder whose pack.stamp is neither a release nor a source stamp was not refused as not"
                + " installed (flips if the release-stamp shape test of the installed stamp is dropped)",
                oddStamp, zip, PackageUpdateCheck.NOT_INSTALLED);
    }

    private static void zipNotOurs(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path ours = stage("the control zip", () -> zip(dir, NEW, program(true)));
        expectProceeds("the control zip of the not-a-package row was refused (the fixture, or a refusal that fires on"
                + " every zip)", pkg, ours, preview(OLD, NEW, 0, 0, 0));
        Path noManifest = stage("a zip without MANIFEST.txt", () -> PackageZipSelftest.baseZip(dir, program(true), (byte[]) null));
        expectRefused("a zip without MANIFEST.txt was not refused as not a server package (flips if the check stops"
                + " opening the zip through the base reader)", pkg, noManifest, notPackage(noManifest));
        LinkedHashMap<String, String> headOnly = new LinkedHashMap<>();
        headOnly.put(PackageManifest.GIT_HEAD, "0123456789abcdef0123456789abcdef01234567");
        Path stampless = stage("a zip whose manifest has no pack.stamp",
                () -> zip(dir, headOnly, program(true), rowsOf(program(true))));
        expectRefused("a zip whose manifest has no pack.stamp was not refused as not a server package (flips if a"
                + " stampless zip manifest passes)", pkg, stampless, notPackage(stampless));
        Path text = stage("a file that is no zip", () -> Files.write(dir.resolve("not-a-zip.zip"), ascii("hello\n")));
        expectRefused("a file that is not a zip was not refused as not a server package (flips if an unreadable zip"
                + " escapes as the error exit)", pkg, text, notPackage(text));
    }

    private static void traversalName(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        for (String bad : List.of("../x", "classes\\..\\..\\x")) {
            LinkedHashMap<String, byte[]> files = program(true);
            LinkedHashMap<String, String> rows = rowsOf(files);
            files.put(bad, ascii("x"));
            Path z = stage("a zip naming " + bad, () -> zip(dir, meta(NEW), files, rows));
            expectRefused("a zip entry named " + bad + " was not refused as not a server package (flips if the base"
                    + " reader's safe-name law no longer runs before the OS tool)", pkg, z, notPackage(z));
        }
    }

    private static void overCapSize(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        byte[] honest = stage("the honest zip", () -> Files.readAllBytes(zip(dir, NEW, program(true))));
        Path atCap = stage("a zip declaring an entry AT the cap", () -> Files.write(dir.resolve("at-cap.zip"),
                PackageZipSelftest.withDeclaredSize(honest, "classes/cc/A.class", PackageBaseZip.MAX_ENTRY_BYTES)));
        expectProceeds("a zip declaring an entry of exactly the per-entry cap was refused (flips if the cap became"
                + " inclusive, or if the check inflates an entry)", pkg, atCap, preview(OLD, NEW, 0, 0, 0));
        Path over = stage("a zip declaring an entry over the cap", () -> Files.write(dir.resolve("over-cap.zip"),
                PackageZipSelftest.withDeclaredSize(honest, "classes/cc/A.class", PackageBaseZip.MAX_ENTRY_BYTES + 1)));
        expectRefused("a zip declaring an entry one byte over the per-entry cap was not refused (flips if the declared"
                + " sizes are no longer checked before the OS tool writes)", pkg, over, notPackage(over));
    }

    private static void entryUnlisted(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        LinkedHashMap<String, byte[]> files = program(true);
        LinkedHashMap<String, String> rows = rowsOf(files);
        files.put("classes/cc/Extra.class", ascii("extra"));
        Path z = stage("a zip with an unlisted entry", () -> zip(dir, meta(NEW), files, rows));
        expectRefused("a zip entry its manifest does not list was not refused (flips if the zip-to-manifest half of"
                + " the entry-set law is dropped)", pkg, z,
                "the zip's file list and its contents disagree (classes/cc/Extra.class)");
    }

    private static void rowNotInZip(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        LinkedHashMap<String, byte[]> files = program(true);
        LinkedHashMap<String, String> rows = rowsOf(files);
        rows.put("classes/cc/Gone.class", sha256Hex(ascii("gone")));
        Path z = stage("a zip missing a listed row", () -> zip(dir, meta(NEW), files, rows));
        expectRefused("a manifest row the zip does not carry was not refused (flips if the manifest-to-zip half of"
                + " the entry-set law is dropped)", pkg, z,
                "the zip's file list and its contents disagree (classes/cc/Gone.class)");
    }

    private static void outsideProgramSet(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        LinkedHashMap<String, String> kitMeta = meta(NEW);
        kitMeta.put(PackageManifest.PACK_KIT, KIT);
        Path kitOk = stage("a zip carrying its release kit", () -> zipPlus(dir, kitMeta, PackageFolderWriter.KITS_PREFIX + KIT));
        expectProceeds("a zip carrying the kit its pack.kit row names was refused (flips if the release kit row is"
                + " no longer in the program set)", pkg, kitOk, preview(OLD, NEW, 0, 1, 0));
        for (String bad : List.of("kits/other.zip", "cc-server.properties", "classes/CON", "classes/cc/aux.txt",
                "classes/x.", "classes/~1.class")) {
            Path z = stage("a zip naming " + bad, () -> zipPlus(dir, kitMeta, bad));
            expectRefused("a zip naming " + bad + " was not refused as outside the program set (flips if the program"
                    + " set's names, its kits row or its segment law is widened)", pkg, z,
                    "the zip lists a file outside the program set: " + bad);
        }
        Path treeDir = stage("a zip with a directory entry under a program tree", () -> zipPlus(dir, meta(NEW), "jvm/extra/"));
        expectProceeds("a directory entry under a program tree was refused (flips if every directory entry is"
                + " refused)", pkg, treeDir, preview(OLD, NEW, 0, 0, 0));
        Path hostDir = stage("a zip with a host directory entry", () -> zipPlus(dir, meta(NEW), "match/"));
        expectRefused("a directory entry outside the program trees was not refused (flips if the directory entries"
                + " skip the program-set law)", pkg, hostDir, "the zip lists a file outside the program set: match/");
    }

    private static void platformMismatch(Path dir) throws Exception {
        Path windowsPkg = stage("the Windows folder", () -> installed(dir, OLD, program(true)));
        Path linuxPkg = stage("the Linux folder", () -> installed(dir, OLD, program(false)));
        Path windowsZip = stage("the Windows zip", () -> zip(dir, NEW, program(true)));
        Path linuxZip = stage("the Linux zip", () -> zip(dir, NEW, program(false)));
        expectProceeds("a Linux zip over a Linux folder was refused (flips if the platform test misreads a Linux"
                + " pair)", linuxPkg, linuxZip, preview(OLD, NEW, 0, 0, 0));
        expectRefused("the Linux zip over a Windows folder was not refused (flips if the platform law is dropped,"
                + " so the other jre would replace this one)", windowsPkg, linuxZip,
                "this zip is the Linux package (this folder is the Windows one)");
        expectRefused("the Windows zip over a Linux folder was not refused (flips if the platform law is dropped or"
                + " its two sentences swap)", linuxPkg, windowsZip,
                "this zip is the Windows package (this folder is the Linux one)");
    }

    private static void olderStamp(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path z = stage("an older zip", () -> zip(dir, OLDER, program(true)));
        expectRefused("an older zip was not refused (flips if the no-downgrade law is dropped or compares the wrong"
                + " way)", pkg, z, "the zip (" + OLDER + ") is older than the installed package (" + OLD
                + ") - no downgrade");
    }

    private static void equalStamp(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        LinkedHashMap<String, byte[]> files = program(true);
        files.put("classes/cc/A.class", ascii("class A, half extracted"));
        Path z = stage("a zip of the installed stamp", () -> zip(dir, OLD, files));
        expectProceeds("a zip of the installed stamp did not proceed with the already-installed line (flips if an"
                + " equal stamp is refused, or previews, so a half-extracted folder could not converge)", pkg, z,
                "already installed (" + OLD + ") - the start repairs the folder");
    }

    private static void newerPreview(Path dir) throws Exception {
        LinkedHashMap<String, byte[]> was = program(true);
        was.put("classes/cc/B.class", ascii("class B"));
        was.put("classes/cc/Old.class", ascii("class Old"));
        Path pkg = stage("the installed folder", () -> {
            Path p = installed(dir, OLD, was);
            Files.createDirectories(p.resolve(ServeLauncher.RUN_DIR).resolve("update"));
            return p;
        });
        LinkedHashMap<String, byte[]> next = program(true);
        next.put("classes/cc/A.class", ascii("class A v2"));
        next.put("classes/cc/B.class", ascii("class B v2"));
        next.put("classes/cc/C.class", ascii("class C"));
        next.put("classes/cc/D.class", ascii("class D"));
        next.put("match-defaults/match-1v1.txt", ascii("shape"));
        Path z = stage("the newer zip", () -> zip(dir, NEW, next));
        Map<String, String> before = stage("the folder snapshot", () -> snapshot(pkg));
        expectProceeds("the newer zip's preview is not 2 replaced, 3 added, 1 removed (flips if a count reads the"
                + " wrong rows, counts an unchanged file, or the two stamps swap)", pkg, z, preview(OLD, NEW, 2, 3, 1));
        Map<String, String> after = stage("the folder snapshot after", () -> snapshot(pkg));
        require(after.equals(before), "the check changed the folder (flips if the check writes a marker, a receipt"
                + " or any file, or a lock probe creates the lock it opens): before " + before.keySet()
                + " after " + after.keySet());
    }

    private static void serveLockHeld(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path run = stage("run/", () -> Files.createDirectories(pkg.resolve(ServeLauncher.RUN_DIR)));
        Path z = stage("the newer zip", () -> zip(dir, NEW, program(true)));
        expectProceeds("the serve-lock row's control was refused before the lock was taken (flips if the probe reads"
                + " an unheld lock as held)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        FileChannel held = hold(run.resolve(ServeLauncher.LOCK_FILE));
        expectRefused("a held run/serve.lock was not refused (flips if the launcher-lock leg of the busy law is"
                + " dropped)", pkg, z, "the server is running: stop it (stop.bat) first");
        release(held);
        expectProceeds("a released run/serve.lock still refused (flips if the probe keeps its own lock or reads"
                + " the file's presence as a hold)", pkg, z, preview(OLD, NEW, 0, 0, 0));
    }

    private static void consoleLockHeld(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path lock = stage("an unheld run/console.lock", () -> {
            Path run = Files.createDirectories(pkg.resolve(ServeLauncher.RUN_DIR));
            return Files.write(run.resolve(OpsConsole.CONSOLE_LOCK_FILE), new byte[0]);
        });
        Path z = stage("the newer zip", () -> zip(dir, NEW, program(true)));
        expectProceeds("an unheld run/console.lock was refused (flips if the console leg reads the file's presence"
                + " as a hold)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        hold(lock);
        expectRefused("a held run/console.lock was not refused (flips if the console-lock leg of the busy law is"
                + " dropped)", pkg, z, "the console is open: close its window first");
    }

    private static void stageLockHeld(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path lock = stage("an unheld stage.lock", () -> Files.write(OpsRoots.packaged(pkg).stageLock(), new byte[0]));
        Path z = stage("the newer zip", () -> zip(dir, NEW, program(true)));
        expectProceeds("an unheld stage.lock was refused (flips if the staging leg reads the file's presence as a"
                + " hold)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        hold(lock);
        expectRefused("a held stage.lock was not refused (flips if the staging leg of the busy law is dropped, or"
                + " probes the live record's lock instead of this folder's)", pkg, z, "a console is staging this folder");
    }

    private static void orphanStatusWriter(Path dir) throws Exception {
        Path pkg = stage("the Linux folder", () -> installed(dir, OLD, program(false)));
        Path status = stage("run/ops/", () -> Files.createDirectories(pkg.toAbsolutePath().normalize()
                .resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.OPS_DIR)).resolve(ServeLauncher.OPS_STATUS_FILE));
        Path z = stage("the newer Linux zip", () -> zip(dir, NEW, program(false)));
        expectProceeds("the orphan row's control (no status file) was refused (flips if an absent status file reads"
                + " as a live writer)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        long self = ProcessHandle.current().pid();
        stage("a status file stamped before this JVM started", () -> Files.write(status,
                ascii("pid=" + self + "\nstamp_ms=1\n")));
        expectProceeds("a status file whose stamp predates its pid's start was refused (flips if the check reads any"
                + " pid row as a live writer instead of the launcher's own writer test)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        stage("a live status file naming this JVM", () -> Files.write(status,
                ascii("pid=" + self + "\nstamp_ms=" + System.currentTimeMillis() + "\n")));
        expectRefused("a status file still written by a live pid was not refused (flips if the orphaned-serve leg of"
                + " the busy law is dropped)", pkg, z, "a server is still running (pid " + self + ") and writing "
                + status + "; its launcher is gone, so stop.sh cannot reach it - end process " + self
                + " yourself (Task Manager / taskkill, kill on Linux; the Ops console's Kill), then run the update again");
    }

    private static void linkAtTarget(Path dir) throws Exception {
        Path z = stage("the newer zip", () -> zip(dir, NEW, program(true)));
        Path outside = stage("a file outside the folder", () -> Files.write(dir.resolve("outside.txt"), ascii("host")));
        Path leafPkg = stage("the installed folder (leaf link)", () -> installed(dir, OLD, program(true)));
        expectProceeds("the link row's control was refused before any link was made", leafPkg, z,
                preview(OLD, NEW, 0, 0, 0));
        stage("README.md removed", () -> {
            Files.delete(leafPkg.resolve("README.md"));
            return null;
        });
        symlinkOrSkip(leafPkg.resolve("README.md"), outside);
        expectRefused("a target that is itself a link was not refused (flips if the link law skips the entry's own"
                + " target)", leafPkg, z, "README.md is a link - the update does not write through links");
    }

    private static void linkAtParent(Path dir) throws Exception {
        Path z = stage("the newer zip", () -> zip(dir, NEW, program(true)));
        Path outsideDir = stage("a folder outside the folder", () -> PackageZipSelftest.tree(dir.resolve("outside"),
                Map.of("CC_Client/mod_info.json", ascii("{}"))));
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path link = pkg.resolve("serve-mods");
        stage("serve-mods removed", () -> {
            deleteTreeOrThrow(link);
            return null;
        });
        dirLinkOrSkip(link, outsideDir);
        try {
            expectRefused("a link at a target's parent outside the program trees was not refused (flips if the link"
                    + " law walks only the program trees, only the leaf, or misses a Windows junction)", pkg, z,
                    "serve-mods is a link - the update does not write through links");
        } finally {
            // the link goes first, as itself: the tree delete must never walk through a junction into its target
            stage("the link's removal", () -> {
                Files.deleteIfExists(link);
                return null;
            });
        }
    }

    private static void sourceStamp(Path dir) throws Exception {
        String source = PackageUpdateCheck.SOURCE_STAMP_PREFIX + OLD;
        Path windowsPkg = stage("a Windows folder built from source", () -> installed(dir, source, program(true)));
        Path windowsZip = stage("the Windows zip", () -> zip(dir, NEW, program(true)));
        expectRefused("a Windows folder built from source was not refused (flips if the source-stamp law is dropped,"
                + " or names the other platform's script)", windowsPkg, windowsZip,
                "this folder was built from source - rebuild it with build.ps1; update.bat is for downloaded packages");
        Path linuxPkg = stage("a Linux folder built from source", () -> installed(dir, source, program(false)));
        expectRefused("a Linux folder built from source was not refused naming update.sh (flips if the script name"
                + " no longer follows the folder's platform)", linuxPkg, windowsZip,
                "this folder was built from source - rebuild it with build.ps1; update.sh is for downloaded packages");
    }

    /**
     * A lock file this account cannot open refuses as "cannot tell", on the launcher's lock and on the console's:
     * the check never reads an unopenable lock as free (a server started by another account still holds it).
     *
     *
     */
    private static void lockUnreadable(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path run = stage("run/", () -> Files.createDirectories(pkg.resolve(ServeLauncher.RUN_DIR)));
        Path z = stage("the newer zip", () -> zip(dir, NEW, program(true)));
        expectProceeds("the unreadable-lock row's control (no lock file) was refused (flips if an absent lock reads"
                + " as held or as unreadable)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        Path serveLock = stage("an empty run/serve.lock",
                () -> Files.write(run.resolve(ServeLauncher.LOCK_FILE), new byte[0]));
        expectProceeds("an unheld lock refused (flips if the probe reads a file's presence as a hold)", pkg, z,
                preview(OLD, NEW, 0, 0, 0));
        Runnable serveRestore = denyRead(serveLock, true);
        expectRefused("an unreadable run/serve.lock did not refuse as cannot-tell (flips if the probe falls back to"
                + " ServerOpsLaunch.lockFileHeld's false-on-throw)", pkg, z,
                "cannot tell whether the server (or the console) is running - run\\serve.lock is not readable"
                        + " (started by another account?)");
        undeny(serveRestore);
        expectProceeds("a restored run/serve.lock still refused (flips if the probe reads a once-denied lock as"
                + " unreadable for good, or the deny's restore broke)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        Path consoleLock = stage("an empty run/console.lock",
                () -> Files.write(run.resolve(OpsConsole.CONSOLE_LOCK_FILE), new byte[0]));
        Runnable consoleRestore = denyRead(consoleLock, true);
        expectRefused("an unreadable run/console.lock did not refuse as cannot-tell (flips if the console leg keeps"
                + " a false-on-throw probe, or the sentence names the wrong lock)", pkg, z,
                "cannot tell whether the server (or the console) is running - run\\console.lock is not readable"
                        + " (started by another account?)");
        undeny(consoleRestore);
        expectProceeds("a restored run/console.lock still refused (flips if the console leg reads a readable unheld"
                + " lock as busy)", pkg, z, preview(OLD, NEW, 0, 0, 0));
    }

    /**
     * A status file this account cannot read refuses as "cannot tell": an orphaned serve started by another
     * account would otherwise read as no server at all.
     *
     *
     */
    private static void statusUnreadable(Path dir) throws Exception {
        Path pkg = stage("the Linux folder", () -> installed(dir, OLD, program(false)));
        Path status = stage("a status file naming no live pid", () -> {
            Path ops = Files.createDirectories(pkg.toAbsolutePath().normalize()
                    .resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.OPS_DIR));
            return Files.write(ops.resolve(ServeLauncher.OPS_STATUS_FILE), ascii("pid=0\nstamp_ms=1\n"));
        });
        Path z = stage("the newer Linux zip", () -> zip(dir, NEW, program(false)));
        expectProceeds("the unreadable-status row's control (a readable status file naming no live pid) was refused"
                + " (flips if the status probe refuses a readable file)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        Runnable restore = denyRead(status, false);
        expectRefused("an unreadable status file did not refuse as cannot-tell (flips if an unreadable status file is"
                + " read as no server)", pkg, z, "cannot tell whether a server is still running - run/ops/ops-status.txt"
                + " is not readable (started by another account?)");
        undeny(restore);
        expectProceeds("a restored status file still refused (flips if the status probe reads a once-denied file as"
                + " unreadable for good, or the deny's restore broke)", pkg, z, preview(OLD, NEW, 0, 0, 0));
    }

    /**
     * A later release's new root files ride its declaration over this check's frozen list. WHY {@code serve}: it
     * is a string prefix of the host's mods tree, so it proceeds only while the host test compares whole segments.
     *
     *
     */
    private static void declaredRootProceeds(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path z = stage("a zip listing and declaring two new root files",
                () -> zipPlus(dir, declaring(packerRowPlus("NEW-ROOT.txt", "serve")), "NEW-ROOT.txt", "serve"));
        expectProceeds("a zip declaring the two new root files it lists was refused (flips if the declared source is"
                + " removed from the program-set law, or the host test loses its slash normalization: serve is a string"
                + " prefix of serve-mods/)", pkg, z, preview(OLD, NEW, 0, 2, 0));
    }

    /**
     * Every host path a zip could declare, in each shape the extract would land on it: the exact name, a case
     * variant, a tree over a host file, a file over a host tree, a tree the host owns; each refuses by name and
     * leaves the folder as it was. The last two requires drive the relation itself on a two-segment host path
     * (every frozen host path is one segment, so only there does the host lie strictly under the declared path).
     *
     *
     */
    private static void declaredHostFileRefused(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Map<String, String> before = stage("the folder snapshot", () -> snapshot(pkg));
        LinkedHashMap<String, String> listedFor = new LinkedHashMap<>();
        listedFor.put("cc-server.properties", "cc-server.properties");
        listedFor.put("launcher.properties", "launcher.properties");
        listedFor.put("cc-server.bans", "cc-server.bans");
        listedFor.put("cc-server.hidden-ships.tmp", "cc-server.hidden-ships.tmp");
        listedFor.put("watch.lease", "watch.lease");
        listedFor.put("stage.lock", "stage.lock");
        listedFor.put("MANIFEST.failed", "MANIFEST.failed");
        listedFor.put("match/", "match/x.txt");
        String store = CustomVariantConfig.PERSIST_DIR_DEFAULT;
        String storeRoot = store.substring(0, store.indexOf('/') + 1);
        listedFor.put(storeRoot, store + "/x.variant");
        // a sibling of the store under its root tree: the whole root is the host's, not only the store
        listedFor.put(storeRoot + "logs/", storeRoot + "logs/x.log");
        listedFor.put("variants-inbox/", "variants-inbox/x.variant");
        listedFor.put("kits/", "kits/other.zip");
        listedFor.put("serve-mods/", "serve-mods/enabled_mods.json");
        // the client mod's own folder is the program's: rows puc-declared-mod-* / puc-declared-other-mod-refused
        listedFor.put("cc-server.properties/", "cc-server.properties/x");
        listedFor.put("run", "run");
        listedFor.put("Run/", "Run/serve.lock");
        listedFor.put("CC-SERVER.PROPERTIES", "CC-SERVER.PROPERTIES");
        for (Map.Entry<String, String> c : listedFor.entrySet()) {
            Path z = stage("a zip declaring " + c.getKey(),
                    () -> zipPlus(dir, declaring(packerRowPlus(c.getKey())), c.getValue()));
            expectRefused("a zip declaring the host path " + c.getKey() + " was not refused by name (flips if the host"
                    + " test is dropped, stops case-folding, or stops relating a file and a tree both ways)", pkg, z,
                    PackageUpdateCheck.DECLARES_HOST_FILE + c.getKey());
        }
        Map<String, String> after = stage("the folder snapshot after", () -> snapshot(pkg));
        require(after.equals(before), "a refused declaration changed the folder (flips if the check writes before its"
                + " verdict): before " + before.keySet() + " after " + after.keySet());
        require(PackageUpdateCheck.hostRelated("serve-mods/", List.of("serve-mods/enabled_mods.json")),
                "a declared tree holding a host file did not relate to it (flips if the host test drops the host-under-"
                        + "declaration direction)");
        require(!PackageUpdateCheck.hostRelated("match-defaults/", List.of("match/")),
                "the shipped match-defaults/ tree related to the host's match/ (flips if the host test compares"
                        + " string prefixes instead of whole segments)");
    }

    /**
     * The packer's REAL row (the builder the folder writer calls) is the sorted union of the frozen constants,
     * fits the frozen caps, and passes: its client-mod trees lie under the host's mods tree, so it holds while the
     * frozen set admits first or the client mod's folder keeps its carve out of the host's mods tree.
     *
     *
     */
    private static void declaredAlreadyFrozenOk(Path dir) throws Exception {
        String row = PackageUpdateCheck.programSetRow();
        List<String> elements = List.of(row.split(",", -1));
        Set<String> union = new HashSet<>(PackageUpdateCheck.PROGRAM_NAMES);
        union.addAll(PackageUpdateCheck.PROGRAM_PREFIXES);
        List<String> sorted = new ArrayList<>(elements);
        sorted.sort(null);
        require(elements.size() == union.size() && union.equals(new HashSet<>(elements)) && sorted.equals(elements),
                "the packer's program.set row is no longer the sorted union of the frozen names and trees (flips if"
                        + " the row builder drops a constant, duplicates one or stops sorting): " + PackageManifest.mask(row));
        require(row.length() * 2 <= PackageUpdateCheck.PROGRAM_SET_MAX_CHARS
                && elements.size() * 2 <= PackageUpdateCheck.PROGRAM_SET_MAX_ELEMENTS,
                "the packer's row is past half the frozen caps (flips if the program set grows toward the length or"
                        + " element cap every installed check keeps): " + row.length() + " chars, " + elements.size());
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        LinkedHashMap<String, byte[]> files = program(true);
        files.put(PackageFolderWriter.DATA_PREFIX + "config/settings.json", ascii("{}"));
        files.put(PackageFolderWriter.MOD_PREFIX + "jars/cc-client-mod.jar", ascii("jar"));
        Path z = stage("a zip carrying the packer's row", () -> zip(dir, declaring(row), files, rowsOf(files)));
        expectProceeds("the packer's own program.set row was refused (flips if the client mod's trees under serve-mods/"
                + " read as host paths: the frozen set no longer admits first AND the client mod's folder lost its"
                + " carve)", pkg, z, preview(OLD, NEW, 0, 2, 0));
    }

    /**
     * With a row present, a name it does not declare still refuses: a new root file, a sibling tree that only
     * shares a string prefix with a declared tree, a case variant of a declared name.
     *
     *
     */
    private static void undeclaredRootRefused(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Map<String, String> listedFor = Map.of(PackageUpdateCheck.programSetRow(), "NEW-ROOT.txt",
                packerRowPlus("web2/"), "web2x/a.js", packerRowPlus("NEW-ROOT.txt"), "new-root.txt");
        for (Map.Entry<String, String> c : listedFor.entrySet()) {
            Path z = stage("a zip listing " + c.getValue(), () -> zipPlus(dir, declaring(c.getKey()), c.getValue()));
            expectRefused("a zip listing " + c.getValue() + ", which its program.set row does not declare, was not"
                    + " refused (flips if a present row admits every name, a declared tree matches without its slash,"
                    + " or a declared name matches another case)", pkg, z,
                    "the zip lists a file outside the program set: " + c.getValue());
        }
    }

    /**
     * A declared tree admits the files and directory entries under it, and only names the portable path law
     * keeps (a Windows short name under it refuses).
     *
     *
     */
    private static void declaredPrefixDirs(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        LinkedHashMap<String, String> web2 = declaring(packerRowPlus("web2/"));
        Path z = stage("a zip with a declared tree", () -> zipPlus(dir, web2, "web2/", "web2/x.js", "web2/sub/"));
        expectProceeds("a declared tree's file and directory entries were refused (flips if a declared tree stops"
                + " admitting names under it, or stops extending the directory-entry law)", pkg, z,
                preview(OLD, NEW, 0, 1, 0));
        Map<String, String> refusedAs = new LinkedHashMap<>();
        refusedAs.put("web3/", "web3/");
        refusedAs.put("web2/SHORT~1.JS", "web2/SHORT~1.JS");
        refusedAs.put("web2/SUB~1/", "web2/SUB~1/");
        for (Map.Entry<String, String> c : refusedAs.entrySet()) {
            Path bad = stage("a zip with " + c.getKey(), () -> zipPlus(dir, web2, "web2/x.js", c.getKey()));
            expectRefused("the entry " + c.getKey() + " beside the declared web2/ tree was not refused (flips if an"
                    + " undeclared directory entry passes, or a declared tree's names skip the portable path law)",
                    pkg, bad, "the zip lists a file outside the program set: " + c.getValue());
        }
    }

    /**
     * The row's grammar refuses the whole zip with one masked sentence: a bad element name, a short name, a tree
     * declared without its slash, an empty or duplicate element, a row past either cap (each cap's boundary
     * proceeds).
     *
     *
     */
    private static void declaredBadNameRefused(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        List<String> atCount = new ArrayList<>();
        for (int i = 0; i < PackageUpdateCheck.PROGRAM_SET_MAX_ELEMENTS; i++) {
            atCount.add("n" + i);
        }
        String atLength = "a".repeat(PackageUpdateCheck.PROGRAM_SET_MAX_CHARS);
        for (String ok : List.of(String.join(",", atCount), atLength)) {
            Path z = stage("a row at a cap", () -> zipPlus(dir, declaring(ok)));
            expectProceeds("a program.set row exactly at its element or length cap was refused (flips if a cap became"
                    + " exclusive)", pkg, z, preview(OLD, NEW, 0, 0, 0));
        }
        atCount.add("n" + PackageUpdateCheck.PROGRAM_SET_MAX_ELEMENTS);
        // each malformed row -> the entry the zip lists beside it (null = none)
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        rows.put(packerRowPlus("../x"), null);
        rows.put(packerRowPlus("run/../x"), null);
        rows.put(packerRowPlus("CONIN$"), null);
        rows.put(packerRowPlus("CC-SER~1.PRO"), "CC-SER~1.PRO");
        rows.put(packerRowPlus("web2"), "web2/x.js");
        rows.put(packerRowPlus("/"), null);
        rows.put(packerRowPlus("a b"), null);
        rows.put(packerRowPlus(""), null);
        rows.put("", null);
        rows.put(packerRowPlus("NEW-ROOT.txt", "NEW-ROOT.txt"), "NEW-ROOT.txt");
        rows.put(String.join(",", atCount), null);
        rows.put(atLength + "a", null);
        for (Map.Entry<String, String> c : rows.entrySet()) {
            String shown = PackageManifest.mask(c.getKey().length() > 40 ? c.getKey().substring(c.getKey().length() - 40)
                    : c.getKey());
            Path z = stage("a malformed row ..." + shown, () -> c.getValue() == null ? zipPlus(dir, declaring(c.getKey()))
                    : zipPlus(dir, declaring(c.getKey()), c.getValue()));
            expectRefused("a program.set row ending '" + shown + "' was not refused as malformed (flips if the grammar"
                    + " law, its caps, the name law, the portable path law or the exact-is-no-tree law is dropped)",
                    pkg, z, PackageUpdateCheck.PROGRAM_SET_MALFORMED);
        }
    }

    /**
     * A zip without the row keeps the frozen law exactly: the control proceeds, a new root file, a new directory
     * entry and a host file refuse as they always did.
     *
     *
     */
    private static void noRowOldLaw(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        Path control = stage("the control zip", () -> zip(dir, NEW, program(true)));
        expectProceeds("a zip without a program.set row was refused (flips if a missing row reads as malformed)", pkg,
                control, preview(OLD, NEW, 0, 0, 0));
        for (String bad : List.of("NEW-ROOT.txt", "web2/", "cc-server.properties")) {
            Path z = stage("a zip without the row naming " + bad, () -> zipPlus(dir, meta(NEW), bad));
            expectRefused("a zip without a program.set row naming " + bad + " was not refused (flips if a missing row"
                    + " reads as declaring every name)", pkg, z, "the zip lists a file outside the program set: " + bad);
        }
    }

    /**
     * The deny-list's two literals equal their runtime writers' package-private names, read by reflection, and the
     * frozen host list holds each with its temp sibling.
     *
     *
     */
    private static void hostPathsPinned(Path dir) throws Exception {
        String bans = stage("the bans writer's file name",
                () -> runtimeConstant("cc.starsector.server.runtime.BanOperatorLane", "BANS_FILE_NAME"));
        String hidden = stage("the hidden-ships writer's file name",
                () -> runtimeConstant("cc.starsector.server.runtime.HiddenShipsLane", "FILE_NAME"));
        require(PackageUpdateCheck.BANS_FILE.equals(bans) && PackageUpdateCheck.HIDDEN_SHIPS_FILE.equals(hidden),
                "the host list's bans / hidden-ships literals no longer equal their writers' names (flips if a writer"
                        + " renames its file and the frozen host list is not updated): " + bans + ", " + hidden);
        String tmp = PackageUpdateCheck.PERSIST_TMP_SUFFIX;
        require(PackageUpdateCheck.HOST_PATHS.containsAll(List.of(bans, bans + tmp, hidden, hidden + tmp)),
                "the frozen host list lost the bans or hidden-ships file or its temp sibling (flips if one is dropped"
                        + " from the list): " + PackageUpdateCheck.HOST_PATHS);
    }

    /**
     * Declared serve-mods/CC_Client/newdir/ admitted: the client mod's own folder is the program's, so a later
     * release adds a tree under it beyond the frozen {@code data/} and {@code jars/} (its file, its directory entry
     * and a directory entry under it proceed). The last require pins the carve's other edge: a host path INSIDE the
     * folder still relates.
     *
     *
     */
    private static void declaredModNewdirAdmitted(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        String tree = PackageUpdateCheck.PROGRAM_MOD_TREE + "newdir/";
        Path z = stage("a zip declaring a new client-mod tree",
                () -> zipPlus(dir, declaring(packerRowPlus(tree)), tree, tree + "x.png", tree + "sub/"));
        expectProceeds("a zip declaring a new tree under the client mod's folder was refused (flips if the client"
                + " mod's folder loses its carve out of the host's mods tree, so the tree reads as a host path)", pkg,
                z, preview(OLD, NEW, 0, 1, 0));
        require(!PackageUpdateCheck.hostRelated(tree, PackageUpdateCheck.HOST_PATHS),
                "the client mod's new tree related to the frozen host list (flips if the carve is dropped)");
        require(PackageUpdateCheck.hostRelated(tree, List.of(tree + "host.txt")),
                "a host path inside the client mod's folder no longer relates to a tree over it (flips if the carve"
                        + " skips every host path, not only those outside the folder)");
    }

    /**
     * Declared serve-mods/CC_Client/jars/x.jar admitted: a later release's second client-mod jar, declared as an
     * exact name, proceeds, and the name passes the host test on its own (not only through the frozen jars tree
     * that admits it first).
     *
     *
     */
    private static void declaredModJarAdmitted(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        String jar = PackageUpdateCheck.PROGRAM_MOD_TREE + "jars/x.jar";
        Path z = stage("a zip declaring a second client-mod jar", () -> zipPlus(dir, declaring(packerRowPlus(jar)), jar));
        expectProceeds("a zip declaring a second client-mod jar was refused (flips if the client mod's jars are"
                + " admitted by neither the frozen jars tree nor the folder's carve)", pkg, z, preview(OLD, NEW, 0, 1, 0));
        require(!PackageUpdateCheck.hostRelated(jar, PackageUpdateCheck.HOST_PATHS),
                "a client-mod jar related to the frozen host list (flips if the carve is dropped: the jar then rides"
                        + " only the frozen jars tree)");
    }

    /**
     * Declared serve-mods/OtherMod/ refused as host: hosts keep their own mods BESIDE the client mod, so the carve
     * is exactly one folder. Another mod's tree, a sibling sharing the folder's name as a string prefix and the
     * folder's bare name as a file each refuse by name; the carved folder is the one the mod sync reserves.
     *
     *
     */
    private static void declaredOtherModRefused(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        String mods = PackageModSync.MODS_DIR + "/";
        String bare = mods + PackageModSync.CLIENT_FOLDER;
        // each declared element -> the entry the zip lists beside it (null = none: a file over the program folder)
        LinkedHashMap<String, String> listedFor = new LinkedHashMap<>();
        listedFor.put(mods + "OtherMod/", mods + "OtherMod/mod_info.json");
        listedFor.put(bare + "X/", bare + "X/mod_info.json");
        listedFor.put(bare, null);
        for (Map.Entry<String, String> c : listedFor.entrySet()) {
            Path z = stage("a zip declaring " + c.getKey(), () -> c.getValue() == null
                    ? zipPlus(dir, declaring(packerRowPlus(c.getKey())))
                    : zipPlus(dir, declaring(packerRowPlus(c.getKey())), c.getValue()));
            expectRefused("a zip declaring " + c.getKey() + " beside the client mod was not refused as host (flips if"
                    + " the carve widens past the client mod's folder, matches its name without the slash, or covers"
                    + " its bare name)", pkg, z, PackageUpdateCheck.DECLARES_HOST_FILE + c.getKey());
        }
        require(PackageUpdateCheck.PROGRAM_MOD_TREE.equals(bare + "/"),
                "the carved folder " + PackageUpdateCheck.PROGRAM_MOD_TREE + " is not the one the mod sync reserves ("
                        + bare + "/; flips if the client mod's folder or the mod sync's reserved name is renamed"
                        + " alone, so the carve covers a folder the host may write)");
    }

    /**
     * serve-mods/enabled_mods.json still host: the host's enabled list sits beside the client mod's folder; declared
     * as a name it refuses by name, and the frozen host list still relates it and the mods tree itself.
     *
     *
     */
    private static void enabledModsStillHost(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        String enabled = PackageModSync.MODS_DIR + "/" + PackageModSync.ENABLED_FILE;
        Path z = stage("a zip declaring the enabled list", () -> zipPlus(dir, declaring(packerRowPlus(enabled)), enabled));
        expectRefused("a zip declaring the host's enabled-mods list was not refused by name (flips if the client mod's"
                + " carve widens to the whole mods tree)", pkg, z, PackageUpdateCheck.DECLARES_HOST_FILE + enabled);
        require(PackageUpdateCheck.hostRelated(enabled, PackageUpdateCheck.HOST_PATHS)
                && PackageUpdateCheck.hostRelated(PackageModSync.MODS_DIR + "/", PackageUpdateCheck.HOST_PATHS),
                "the enabled list or the mods tree no longer relates to the frozen host list (flips if the carve"
                        + " widens past the client mod's folder)");
    }

    /**
     * Case: SERVE-MODS/cc_client/... admitted: the carve folds case like the host test, because a Windows extract
     * lands a case variant on the client mod's own folder and the mod sync reserves the folded name; a case
     * variant of another mod's tree still relates.
     *
     *
     */
    private static void declaredModCaseAdmitted(Path dir) throws Exception {
        Path pkg = stage("the installed folder", () -> installed(dir, OLD, program(true)));
        String folded = PackageModSync.MODS_DIR.toUpperCase(Locale.ROOT) + "/";
        String tree = folded + PackageModSync.CLIENT_FOLDER.toLowerCase(Locale.ROOT) + "/newdir/";
        Path z = stage("a zip declaring a case variant", () -> zipPlus(dir, declaring(packerRowPlus(tree)), tree + "x.png"));
        expectProceeds("a zip declaring " + tree + " was refused (flips if the carve compares without case folding)",
                pkg, z, preview(OLD, NEW, 0, 1, 0));
        require(PackageUpdateCheck.hostRelated(folded + "OtherMod/", PackageUpdateCheck.HOST_PATHS),
                "a case variant of another mod's tree no longer relates to the host's mods tree (flips if the host"
                        + " test stops case-folding, or the carve skips every case variant of the mods tree)");
    }

    // ---- fixtures ----

    /**
     * One version's program files for one platform (the platform = the java the jre carries), in zip order:
     * the jre's java, a class, the start script, the readme, the client mod's info file.
     */
    private static LinkedHashMap<String, byte[]> program(boolean windows) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put(windows ? PackageUpdateCheck.WINDOWS_JAVA : LINUX_JAVA, ascii("java"));
        files.put("classes/cc/A.class", ascii("class A"));
        files.put(windows ? "serve.bat" : "serve.sh", ascii("serve"));
        // the shipped root README (a PROGRAM_NAMES entry).
        files.put("README.md", ascii("readme"));
        files.put(PackageFolderWriter.MOD_INFO_REL, ascii("{}"));
        return files;
    }

    /** An installed folder (a fresh {@code pkg-*} dir): {@code files} plus MANIFEST.txt = the stamp (null = none) + one row per file. */
    private static Path installed(Path dir, String stamp, LinkedHashMap<String, byte[]> files) throws IOException {
        Path pkg = PackageZipSelftest.tree(Files.createTempDirectory(dir, "pkg-"), files);
        LinkedHashMap<String, String> meta = stamp == null ? new LinkedHashMap<>() : meta(stamp);
        Files.write(pkg.resolve(ServeLauncher.MANIFEST_FILE), PackageManifest.render(meta, rowsOf(files)));
        return pkg;
    }

    /** A package zip: {@code files} in map order, then MANIFEST.txt = {@code pack.stamp=stamp} + one row per file. */
    private static Path zip(Path dir, String stamp, LinkedHashMap<String, byte[]> files) throws IOException {
        return zip(dir, meta(stamp), files, rowsOf(files));
    }

    /** A package zip: {@code files} in map order (a name ending {@code /} = a directory entry), then MANIFEST.txt = {@code meta} + {@code rows}. */
    private static Path zip(Path dir, LinkedHashMap<String, String> meta, LinkedHashMap<String, byte[]> files,
            LinkedHashMap<String, String> rows) throws IOException {
        return PackageZipSelftest.baseZip(dir, files, PackageManifest.render(meta, rows));
    }

    /** The Windows program zip under {@code meta} plus the entries {@code extras} in order, each listed when it is a file. */
    private static Path zipPlus(Path dir, LinkedHashMap<String, String> meta, String... extras) throws IOException {
        LinkedHashMap<String, byte[]> files = program(true);
        for (String extra : extras) {
            files.put(extra, ascii("extra"));
        }
        return zip(dir, meta, files, rowsOf(files));
    }

    /**
     * {@link #NEW}'s meta plus a {@code program.set} row of {@code row}; the two helpers below build a later
     * release's row (the packer's real one plus its new elements) and read a runtime writer's file name.
     *
     *
     */
    private static LinkedHashMap<String, String> declaring(String row) {
        LinkedHashMap<String, String> m = meta(NEW);
        m.put(PackageUpdateCheck.PROGRAM_SET_KEY, row);
        return m;
    }

    private static String packerRowPlus(String... extras) {
        StringBuilder row = new StringBuilder(PackageUpdateCheck.programSetRow());
        for (String extra : extras) {
            row.append(',').append(extra);
        }
        return row.toString();
    }

    private static String runtimeConstant(String className, String field) throws ReflectiveOperationException {
        Field f = Class.forName(className).getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static LinkedHashMap<String, String> meta(String stamp) {
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put(PackageBaseZip.PACK_STAMP, stamp);
        return meta;
    }

    /** One sha256 row per FILE entry of {@code files} (directory entries are never manifest rows). */
    private static LinkedHashMap<String, String> rowsOf(Map<String, byte[]> files) {
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!e.getKey().endsWith("/")) {
                rows.put(e.getKey(), sha256Hex(e.getValue()));
            }
        }
        return rows;
    }

    /** Every path under {@code root} (forward slashes) to its sha256, plus a {@code "<rel> size"} entry; directories as {@code <rel>/}. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        TreeMap<String, String> all = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    all.put(rel + "/", "dir");
                } else {
                    byte[] bytes = Files.readAllBytes(p);
                    all.put(rel, sha256Hex(bytes));
                    all.put(rel + " size", Integer.toString(bytes.length));
                }
            }
        }
        return all;
    }

    /** Takes {@code file}'s lock in THIS JVM (the file created); the harness releases it at the row's end. */
    private static FileChannel hold(Path file) {
        return stage("a lock on " + file.getFileName(), () -> {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            HELD.add(ch);
            ch.lock();
            return ch;
        });
    }

    private static void release(FileChannel ch) {
        stage("the lock's release", () -> {
            HELD.remove(ch);
            ch.close();
            return null;
        });
    }

    /**
     * Makes {@code file} unreadable by THIS account and returns its restore (idempotent; the harness runs it again
     * before the row's delete). Windows: a DENY entry for this account (read, write and append data) put FIRST in
     * the file's ACL - the owner keeps the right to rewrite the ACL, so the restore always works; elsewhere: every
     * mode bit cleared. The deny is then PROVED with the open the check makes ({@code lock}: a {@code WRITE}
     * channel; else an input stream): an open that still succeeds (root, a volume without ACLs, a privilege)
     * restores and SKIPs the row, never a false PASS.
     *
     *
     */
    private static Runnable denyRead(Path file, boolean lock) {
        Runnable restore = stage("the read deny on " + file.getFileName(), () -> {
            if (File.separatorChar == '\\') {
                AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
                if (view == null) {
                    throw new Skip("this volume keeps no ACL, so no read deny can be made");
                }
                UserPrincipal self;
                try {
                    self = file.getFileSystem().getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name"));
                } catch (IOException | RuntimeException noSuchName) {
                    System.out.println("NOTE: this account's name did not resolve ("
                            + noSuchName.getClass().getSimpleName() + "); the deny names the file's owner");
                    self = view.getOwner();
                }
                List<AclEntry> was = view.getAcl();
                List<AclEntry> denied = new ArrayList<>();
                denied.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(self)
                        .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                                AclEntryPermission.APPEND_DATA).build());
                denied.addAll(was);
                view.setAcl(denied);
                return once(() -> view.setAcl(was));
            }
            Set<PosixFilePermission> was = Files.getPosixFilePermissions(file);
            Files.setPosixFilePermissions(file, Set.of());
            return once(() -> Files.setPosixFilePermissions(file, was));
        });
        DENIED.add(restore);
        if (opensLikeTheCheck(file, lock)) {
            undeny(restore);
            throw new Skip("this account reads through the deny (root or a non-ACL volume)");
        }
        return restore;
    }

    /** Runs a deny's restore mid-row; a restore that fails is a fixture that never staged, never a kill. */
    private static void undeny(Runnable restore) {
        stage("the read deny's restore", () -> {
            restore.run();
            return null;
        });
    }

    /** {@code restore} wrapped to run at most once (a second call after a success does nothing). */
    private static Runnable once(IoAction restore) {
        boolean[] done = {false};
        return () -> {
            if (done[0]) {
                return;
            }
            try {
                restore.run();
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
            done[0] = true;
        };
    }

    /** True when {@code file} still opens the way the check opens it (a lock: {@code WRITE}; else for reading). */
    private static boolean opensLikeTheCheck(Path file, boolean lock) {
        try {
            if (lock) {
                FileChannel.open(file, StandardOpenOption.WRITE).close();
            } else {
                Files.newInputStream(file).close();
            }
            return true;
        } catch (IOException denied) {
            return false; // the refused open IS the proof the deny took
        }
    }

    /** A symbolic link, or a {@link Skip} when the OS refuses one (Windows without the privilege / developer mode). */
    private static void symlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException | UnsupportedOperationException refused) {
            throw new Skip("symlink creation refused by the OS (" + refused.getClass().getSimpleName() + ")");
        } catch (IOException other) {
            throw new SetupFailure("symlink " + other.getClass().getSimpleName() + " " + PackageManifest.mask(other.getMessage()));
        }
    }

    /**
     * A directory link: a symbolic link, else on Windows a junction (no privilege needed, and the link test reads
     * a junction as a link: a reparse point), made by the system's own {@code mklink /J}; a {@link Skip} when
     * neither can be made. WHY the junction: a stock Windows box refuses symbolic links without the privilege or
     * developer mode, so without it the parent-link law would never run on Windows at all.
     */
    private static void dirLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (FileSystemException | UnsupportedOperationException refused) {
            if (File.separatorChar != '\\') {
                throw new Skip("symlink creation refused by the OS (" + refused.getClass().getSimpleName() + ")");
            }
        } catch (IOException other) {
            throw new SetupFailure("symlink " + other.getClass().getSimpleName() + " " + PackageManifest.mask(other.getMessage()));
        }
        int code;
        try {
            Process mk = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!mk.waitFor(JUNCTION_WAIT_SECONDS, TimeUnit.SECONDS)) {
                mk.destroyForcibly();
                throw new Skip("mklink /J did not finish in " + JUNCTION_WAIT_SECONDS + " s");
            }
            code = mk.exitValue();
        } catch (IOException noCmd) {
            throw new Skip("neither a symlink nor a junction could be made (" + noCmd.getClass().getSimpleName() + ")");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SetupFailure("interrupted while making a junction");
        }
        if (code != 0 || !PackageFolderWriter.isLink(link)) {
            throw new Skip("symlink refused and mklink /J exited " + code + " (or made no reparse point)");
        }
    }

    // ---- the check, captured ----

    /** One run of the check: its exit code and its stdout lines. */
    private record Result(int code, List<String> lines) {
        String last() {
            return lines.isEmpty() ? "<no line>" : lines.get(lines.size() - 1);
        }

        @Override
        public String toString() {
            List<String> masked = new ArrayList<>();
            for (String line : lines) {
                masked.add(PackageManifest.mask(line));
            }
            return "exit " + code + ", lines " + masked;
        }
    }

    private static Result check(Path pkg, Path zip) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code;
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            code = PackageUpdateCheck.check(pkg, zip, out);
        }
        String text = bytes.toString(StandardCharsets.UTF_8);
        return new Result(code, text.isEmpty() ? List.of() : List.of(text.split("\r?\n")));
    }

    /** A verdict line (a refusal, the preview, the equal-stamp line, the error line): the check prints exactly one, LAST. */
    private static long verdicts(Result r) {
        return r.lines().stream().filter(l -> l.startsWith(PackageUpdateCheck.REFUSED_PREFIX) || l.startsWith("update: ")
                || l.startsWith("already installed (") || l.startsWith("update check failed (")).count();
    }

    private static void expectRefused(String regression, Path pkg, Path zip, String sentence) {
        Result r = check(pkg, zip);
        require(r.code() == PackageUpdateCheck.EXIT_REFUSED && r.last().equals(PackageUpdateCheck.REFUSED_PREFIX + sentence)
                && verdicts(r) == 1, regression + " - want exit " + PackageUpdateCheck.EXIT_REFUSED + " and the one LAST line '"
                + PackageUpdateCheck.REFUSED_PREFIX + PackageManifest.mask(sentence) + "', got " + r);
    }

    private static void expectProceeds(String regression, Path pkg, Path zip, String line) {
        Result r = check(pkg, zip);
        require(r.code() == PackageUpdateCheck.EXIT_PROCEED && r.last().equals(line) && verdicts(r) == 1,
                regression + " - want exit " + PackageUpdateCheck.EXIT_PROCEED + " and the one LAST line '"
                        + PackageManifest.mask(line) + "', got " + r);
    }

    private static String preview(String from, String to, int replace, int add, int remove) {
        return "update: " + from + " -> " + to + ": " + replace + " files to replace, " + add + " to add, " + remove
                + " to remove";
    }

    /** The not-a-package sentence for {@code zip} as the check spells it (its absolute, normalized path, masked). */
    private static String notPackage(Path zip) {
        return PackageManifest.mask(zip.toAbsolutePath().normalize().toString()) + " is not an AlphaCoreMP server package";
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every Java runtime ships SHA-256", impossible);
        }
    }

    // ---- the row harness ----

    @FunctionalInterface
    private interface Body {
        void run(Path dir) throws Exception;
    }

    @FunctionalInterface
    private interface Dial<T> {
        T get() throws Exception;
    }

    /** A file-system action a read deny's restore runs. */
    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** A fixture that never staged: prints {@code SETUP-<row>}, never counted as a kill. */
    private static final class SetupFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SetupFailure(String message) {
            super(message);
        }
    }

    /** A row the OS cannot stage (a symbolic link it refuses): prints {@code SKIP <row>}, not a failure. */
    private static final class Skip extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Skip(String message) {
            super(message);
        }
    }

    private static void row(String name, Body body) {
        Path dir;
        try {
            dir = Files.createTempDirectory("cc-puc-");
        } catch (IOException e) {
            System.out.println("SETUP-" + name + ": no temp dir (" + e.getClass().getSimpleName() + ")");
            failures++;
            return;
        }
        try {
            body.run(dir);
            System.out.println("PASS " + name);
        } catch (SetupFailure setup) {
            System.out.println("SETUP-" + name + ": " + setup.getMessage());
            failures++;
        } catch (Skip skip) {
            System.out.println("SKIP " + name + ": " + skip.getMessage());
            skips++;
        } catch (AssertionError failed) {
            System.out.println("FAIL " + name + ": " + failed.getMessage());
            failures++;
        } catch (Exception | Error unexpected) {
            System.out.println("FAIL " + name + ": unexpected " + unexpected.getClass().getSimpleName() + ": "
                    + PackageManifest.mask(unexpected.getMessage()));
            failures++;
        } finally {
            for (FileChannel ch : HELD) {
                try {
                    ch.close();
                } catch (IOException closeFailed) {
                    System.out.println("NOTE " + name + ": a fixture lock channel did not close ("
                            + closeFailed.getClass().getSimpleName() + ")");
                }
            }
            HELD.clear();
            for (Runnable restore : DENIED) {
                try {
                    restore.run();
                } catch (RuntimeException restoreFailed) {
                    System.out.println("NOTE " + name + ": a fixture's read deny was not restored ("
                            + restoreFailed.getClass().getSimpleName() + ")");
                }
            }
            DENIED.clear();
            try {
                deleteTreeOrThrow(dir);
            } catch (IOException leftover) {
                System.out.println("NOTE " + name + ": temp fixtures left at " + PackageManifest.mask(dir.toString())
                        + " (" + leftover.getClass().getSimpleName() + ")");
            }
        }
    }

    private static <T> T stage(String what, Dial<T> fixture) {
        try {
            return fixture.get();
        } catch (Skip | SetupFailure passThrough) {
            throw passThrough;
        } catch (Exception e) {
            throw new SetupFailure(what + ": " + e.getClass().getSimpleName() + " " + PackageManifest.mask(e.getMessage()));
        }
    }

    private static void require(boolean ok, String regression) {
        if (!ok) {
            throw new AssertionError(regression);
        }
    }

    /** Deletes {@code dir}'s tree, deepest first; a link is deleted as itself, never followed. */
    private static void deleteTreeOrThrow(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(all::add);
        }
        for (Path p : all) {
            Files.deleteIfExists(p);
        }
    }
}
