package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.ops.PackageFolderWriter.Inputs;
import cc.starsector.server.ops.PackageFolderWriter.Kind;
import cc.starsector.server.ops.PackageFolderWriter.Plan;
import cc.starsector.server.ops.PackageFolderWriter.Platform;
import cc.starsector.server.ops.PackageFolderWriter.Result;
import cc.starsector.server.runtime.HandshakeCodec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Guard rows for {@link PackageFolderWriter}:
 * what the writer REFUSES and what it never touches. Every refusal is collected before any write
 * (refuse-all-first); strict redaction and the kit parity reach the plan as R4-R7; the serve and stage
 * locks refuse (R1 / R2) and the writer's own hold is released; a link under the folder refuses (R8)
 * while an owner stage's link to the passed target is accepted; an owner stage keeps today's row set and
 * meta, carries no {@code pack.stamp} and never reads its host or secret files; a SOURCE re-run from
 * another release refuses; an interrupted write leaves {@code MANIFEST.failed} (never a stale
 * {@code MANIFEST.txt}) and the next run is not R3; the CLI's exit codes hold in a child JVM. Later rows:
 * an Error mid-routes still finds the marker and reaches the caller as is; a SOURCE re-run that would
 * place another kit under a kept sidecar refuses (R15); a secret-named input is never opened; a base entry name
 * is grammar-checked before use; each packer check fires alone; the redaction law's trim twin. The layout
 * rows live in {@code PackageFolderWriterSelftest}; the fixtures reuse {@link PackageZipSelftest}'s
 * builders. Every tree lives under a {@link Files#createTempDirectory} dir deleted in {@code finally}.
 * Prints {@code PASS <row>} / {@code FAIL <row>: <the regression that flips it>} / {@code SETUP-<row>: ...}
 * (a fixture that never staged, never a kill) / {@code SKIP <row>: ...} (the OS refused a fixture the row
 * needs: never a PASS) and exits 0 iff no row failed.
 * Run: {@code java -cp server/classes cc.starsector.server.ops.PackageFolderWriterGuardSelftest}.
 *
 *
 */
public final class PackageFolderWriterGuardSelftest {

    private static int failures;
    private static int skips;

    // ---- fixture values ----

    private static final String P = "package-folder: ";
    /** A PACKED head is exactly 40 hex; a SOURCE folder takes its base's. */
    private static final String HEAD_A = "a".repeat(40);
    private static final String HEAD_B = "b".repeat(40);
    /** The md5 an owner stage is passed (kept as passed, never computed). */
    private static final String PASSED_MD5 = "0123456789abcdef0123456789abcdef";
    private static final String STALE_MD5 = "ffffffffffffffffffffffffffffffff";
    /** A live seat-token value: it must never appear in a refusal line. */
    private static final String LIVE_TOKEN = "LiveSeatToken7q9x";
    /** The standing shape the fixtures' pairs carry: a decodable descriptor, REDACTED (its id keys the standing
     *  sidecar, {@link #sidecar}). */
    private static final MatchDescriptor STANDING = MatchDescriptors.redact(MatchDescriptors.mint("1v1", 5000f, 0.9f));
    private static final String REDACTED_MATCH = MatchDescriptorCodec.encode(STANDING);
    private static final String LIVE_MATCH = "version=1\nseat.1.token=REDACTED-seat1\nseat.2.token=" + LIVE_TOKEN + "\n";
    private static final String MOD_INFO = "{\"id\": \"cc_client\", \"name\": \"fixture\"}";
    private static final String STAMP = "t1";

    // ---- the texts the rows expect (verbatim, so a re-worded line flips a row too) ----

    private static final String R1 = P + "REFUSED a server is running from %s - stop it (stop.bat, or Kill in the console),"
            + " then run this again; nothing was changed";
    private static final String R2 = P + "REFUSED another build or Start is writing %s - wait for it, then run this again;"
            + " nothing was changed";
    private static final String R3 = P + "REFUSED %s is not empty and was not made by this tool - pass an empty folder, or a"
            + " server folder this tool made";
    private static final String R4 = P + "REFUSED match/match.txt holds a real seat token - a server folder holds only the"
            + " public copy (tokens shown as REDACTED-); use the released match file";
    private static final String R5 = P + "REFUSED the player mod does not match its fingerprint file (jar=%s, the mod is %s)"
            + " - every player would be turned away; use the kit or server package this server came with";
    private static final String R6 = P + "REFUSED this build's mod data differs from the kit's in 1 file(s) (first:"
            + " data/x.csv) - players with that kit would be turned away; build from unmodified source, or make a kit from"
            + " this build";
    private static final String R7 = P + "REFUSED this build's server classes differ from the copies inside the player mod"
            + " (1 of 1, first: cc/x/Folded.class) - players with that kit would run different game code; build the same"
            + " release's source with a JDK 17, or make a kit from this build";
    private static final String R8 = P + "REFUSED %s/%s is a link - this tool never writes through a link; remove it, then"
            + " run this again";
    private static final String R10 = P + "REFUSED %s not found: %s";
    private static final String R14_HEAD = P + "REFUSED the folder does not match what was just written (";
    private static final String EF = " - the folder was left without MANIFEST.txt, so it will not start until this command"
            + " finishes once";
    private static final String OTHER_RELEASE = P + "REFUSED this folder was made from another release (%s vs the"
            + " package's %s) - start from an empty folder";
    private static final String OK = P + "wrote %s (%d files; %d test and tool classes left out) - start it with serve.bat"
            + " or ops-web.bat (Linux: sh serve.sh or sh ops-web.sh)";
    /** The later texts, verbatim like the rest. */
    private static final String R9 = P + "REFUSED %s cannot be listed (a space, a device name or an unusual character) -"
            + " rename it";
    private static final String R15 = P + "REFUSED match/ was made for another kit (its jar= %s, this build's jar %s) -"
            + " start from an empty folder, or remove match/ and run again";
    private static final String SECRET = P + "REFUSED %s looks like a password or token file (*.password, *.admin*,"
            + " *-tokens*) - a server folder never copies one; move it out, then run this again";
    private static final String UNREADABLE_MID = " cannot be read (";
    private static final String NON_CLASS = P + "REFUSED %s holds %d non-.class file(s) (the classes route copies .class"
            + " files only): %s";
    private static final String PIN = P + "REFUSED %s hashes to sha256 %s, not the pinned %s - another build of %s";
    private static final String MOD_ID_TEXT = P + "REFUSED %s names mod id '%s', not cc_client (enabled_mods.json is"
            + " minted for cc_client)";
    /** A pin no fixture byte hashes to. */
    private static final String WRONG_PIN = "f".repeat(64);

    /** An owner stage's manifest rows for {@link #ownerStageFx}: today's set. */
    private static final Set<String> OWNER_ROWS = Set.of("serve.argv", "cc-premain.jar", "cc-rngpin-holder.jar",
            "jvm/c2-directives.json",
            "classes/cc/x/Folded.class", "classes/cc/x/Other.class", "serve-mods/enabled_mods.json",
            "serve-mods/CC_Client/mod_info.json", "serve-mods/CC_Client/jars/cc-client-mod.jar",
            "serve-mods/CC_Client/data/x.csv");

    private PackageFolderWriterGuardSelftest() {
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** The build gate's entry ({@code OpsSelftests --selftest} chains it): every row, the
     * summary line, the failure count returned (a SKIP is not a failure). */
    public static int run() {
        row("pfw-refuse-all-first", PackageFolderWriterGuardSelftest::refuseAllFirst);
        row("pfw-redacted-match-plan", PackageFolderWriterGuardSelftest::redactedMatchPlan);
        row("pfw-kit-parity-plan", PackageFolderWriterGuardSelftest::kitParityPlan);
        row("pfw-lock", PackageFolderWriterGuardSelftest::lock);
        row("pfw-link-refused", PackageFolderWriterGuardSelftest::linkRefused);
        row("pfw-owner-stage", PackageFolderWriterGuardSelftest::ownerStage);
        row("pfw-source-rerun-head", PackageFolderWriterGuardSelftest::sourceRerunHead);
        row("pfw-interrupted", PackageFolderWriterGuardSelftest::interrupted);
        row("pfw-main-exit", PackageFolderWriterGuardSelftest::mainExit);
        row("pfw-source-rerun-kit", PackageFolderWriterGuardSelftest::sourceRerunKit);
        row("pfw-secret-input", PackageFolderWriterGuardSelftest::secretInput);
        row("pfw-secret-name", PackageFolderWriterGuardSelftest::secretNameRow);
        row("pfw-base-zip-illegal-name", PackageFolderWriterGuardSelftest::baseZipIllegalName);
        row("pfw-base-shapes", PackageFolderWriterGuardSelftest::baseShapes);
        row("pfw-packer-checks", PackageFolderWriterGuardSelftest::packerChecks);
        row("pfw-redaction-trim-twins", PackageFolderWriterGuardSelftest::redactionTrimTwins);
        row("pfw-holder-missing-refuses", PackageFolderWriterGuardSelftest::holderMissingRefuses);
        String skipped = skips == 0 ? "" : " (" + skips + " SKIP)";
        System.out.println(failures == 0 ? "PackageFolderWriterGuardSelftest: every row PASS" + skipped
                : "PackageFolderWriterGuardSelftest: " + failures + " row(s) did not pass" + skipped);
        return failures;
    }

    // ---- rows ----

    /** (1) three faults at once: every one is a line, in the bucket order, and nothing is written. */
    private static void refuseAllFirst(Path dir) throws Exception {
        PackedFx fx = stage("packed fixture with a live token", () -> packedFx(dir, Break.LIVE_TOKEN));
        Path pkg = stage("non-empty folder", () -> PackageZipSelftest.tree(dir.resolve("pkg"),
                Map.of("host-note.txt", ascii("the host's own file\n"))));
        Path absentPremain = dir.resolve("absent-premain.jar");
        List<String> want = List.of(at(R3, pkg), at(R10, "the premain jar (--premain)", absentPremain), R4);
        TreeSet<String> before = listing(pkg);
        try (Plan p = PackageFolderWriter.plan(fx.withPremain(absentPremain).inputs(pkg))) {
            List<String> got = p.refusals();
            for (String line : got) {
                require(line.startsWith(P), "a refusal line lost its 'package-folder: ' prefix: " + mask(line));
            }
            require(got.equals(want), "a non-empty folder + a missing premain + a live match token no longer yield"
                    + " exactly R3, R10, R4 in the plan's bucket order (folder, inputs, kit) - the plan stops at the first"
                    + " fault, drops one, or re-orders: " + got);
            String thrown = writeRefused(p, "the three-fault plan");
            require(thrown.equals(String.join("\n", want)), "write() of a refused plan no longer throws naming every"
                    + " refusal: " + mask(thrown));
        }
        TreeSet<String> after = listing(pkg);
        after.remove(ServerOpsLaunch.STAGE_LOCK_FILE);
        require(after.equals(before), "a refused plan or its refused write left something under the folder beyond what"
                + " was there (bar the plan's own documented stage.lock): " + after);
        require(Arrays.equals(Files.readAllBytes(pkg.resolve("host-note.txt")), ascii("the host's own file\n")),
                "a refused plan changed the host's own file");
    }

    /** (2) one REDACTED + one live row = R4 exactly, the live value nowhere in a line. */
    private static void redactedMatchPlan(Path dir) throws Exception {
        PackedFx fx = stage("packed fixture with a live token", () -> packedFx(dir, Break.LIVE_TOKEN));
        Path pkg = dir.resolve("pkg");
        try (Plan p = PackageFolderWriter.plan(fx.inputs(pkg))) {
            List<String> got = p.refusals();
            String thrown = writeRefused(p, "the live-token plan");
            for (String line : got) {
                require(!line.contains(LIVE_TOKEN), "a refusal line carries the live seat token's value (refusals name"
                        + " files, never values)");
            }
            require(!thrown.contains(LIVE_TOKEN), "the refused write's message carries the live seat token's value");
            require(got.equals(List.of(R4)), "a kit match with one REDACTED and one live seat token row no longer yields"
                    + " exactly R4 - the plan dropped the R4 mapping or the any-row redaction test came back: " + got);
        }
        require(!Files.exists(pkg), "a refused plan created the server folder");
    }

    /** (3) R5 / R6 / R7 each ALONE through the plan, and the green fixture writes with the placed jar's md5. */
    private static void kitParityPlan(Path dir) throws Exception {
        PackedFx stale = stage("stale-jar fixture", () -> packedFx(dir, Break.STALE_JAR));
        expectOnly("a sidecar whose jar= is another build's", stale.inputs(dir.resolve("pkg-r5")),
                String.format(Locale.ROOT, R5, STALE_MD5, stale.jarMd5()));
        PackedFx data = stage("edited-data fixture", () -> packedFx(dir, Break.DATA_DIFFERS));
        expectOnly("a file= row whose built copy differs", data.inputs(dir.resolve("pkg-r6")), R6);
        PackedFx fold = stage("other-fold fixture", () -> packedFx(dir, Break.FOLD_DIFFERS));
        expectOnly("a folded class that differs from the tree's", fold.inputs(dir.resolve("pkg-r7")), R7);

        PackedFx green = stage("green fixture", () -> packedFx(dir, Break.NONE));
        Path pkg = dir.resolve("pkg-green");
        writeGreen(green.inputs(pkg), "the green PACKED fixture");
        PackageManifest m = PackageManifest.read(pkg.resolve(ServeLauncher.MANIFEST_FILE));
        require(green.jarMd5().equals(m.meta.get(PackageManifest.KIT_JAR_MD5)), "kit.jar.md5 is no longer the md5 of the"
                + " jar the folder holds (the sidecar's jar=): " + mask(m.meta.get(PackageManifest.KIT_JAR_MD5)));
    }

    /** (4) R1 / R2 per kind, the owner stage under both locks, the writer's own hold released. */
    private static void lock(Path dir) throws Exception {
        // (a) a launcher holds <pkg>/run/serve.lock (ServeLauncher's own CREATE+WRITE channel + tryLock)
        PackedFx fx = stage("packed fixture", () -> packedFx(dir, Break.NONE));
        SourceFx src = stage("source fixture", () -> sourceFx(dir, HEAD_A));
        Path packedPkg = stage("packed folder", () -> Files.createDirectories(dir.resolve("pkg-r1-packed")));
        Path sourcePkg = stage("source folder", () -> Files.createDirectories(dir.resolve("pkg-r1-source")));
        Path ownerPkg = dir.resolve("pkg-r1-owner");
        Inputs owner = stage("owner stage", () -> ownerStageFx(dir, ownerPkg, true));
        List<FileChannel> held = new ArrayList<>();
        try {
            for (Path pkg : List.of(packedPkg, sourcePkg, ownerPkg)) {
                held.add(stage("serve.lock hold", () -> hold(serveLock(pkg))));
            }
            List<String> packed = refusalsOf(fx.inputs(packedPkg));
            require(!packed.isEmpty() && packed.get(0).equals(at(R1, packedPkg)), "a PACKED plan no longer refuses"
                    + " FIRST with R1 while a launcher holds run/serve.lock (the lockHeld check dropped): " + packed);
            require(freeNow(packedPkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE)), "a refused PACKED plan kept its"
                    + " stage.lock hold (the refused plan no longer releases it)");
            List<String> source = refusalsOf(src.inputs(sourcePkg));
            require(!source.isEmpty() && source.get(0).equals(at(R1, sourcePkg)), "a SOURCE plan no longer refuses"
                    + " FIRST with R1 while a launcher holds run/serve.lock: " + source);
            List<String> ownerLines = refusalsOf(owner);
            require(ownerLines.isEmpty(), "an owner stage is refused while run/serve.lock is held (the owner stage must"
                    + " never take the R1 check: the console probes the launcher itself): " + ownerLines);
        } finally {
            closeAll(held);
        }

        // (b) another build or Start holds <pkg>/stage.lock from a second channel
        Path packedPkg2 = stage("packed folder", () -> Files.createDirectories(dir.resolve("pkg-r2-packed")));
        Path sourcePkg2 = stage("source folder", () -> Files.createDirectories(dir.resolve("pkg-r2-source")));
        held.clear();
        try {
            held.add(stage("stage.lock hold", () -> hold(packedPkg2.resolve(ServerOpsLaunch.STAGE_LOCK_FILE))));
            held.add(stage("stage.lock hold", () -> hold(sourcePkg2.resolve(ServerOpsLaunch.STAGE_LOCK_FILE))));
            List<String> packed = refusalsOf(fx.inputs(packedPkg2));
            require(packed.equals(List.of(at(R2, packedPkg2))), "a PACKED plan no longer refuses with exactly R2 while"
                    + " another holder has stage.lock: " + packed);
            List<String> source = refusalsOf(src.inputs(sourcePkg2));
            require(source.equals(List.of(at(R2, sourcePkg2))), "a SOURCE plan no longer refuses with exactly R2 while"
                    + " another holder has stage.lock: " + source);
        } finally {
            closeAll(held);
        }

        // (c) after a successful PACKED write the writer's own stage.lock hold is released
        Path greenPkg = stage("packed folder", () -> Files.createDirectories(dir.resolve("pkg-green")));
        writeGreen(fx.inputs(greenPkg), "the green PACKED fixture");
        require(freeNow(greenPkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE)), "after a finished PACKED write a fresh"
                + " tryLock on stage.lock fails (the writer's hold leaked past write())");

        // (d) the console that staged holds stage.lock (ServerOpsLaunch#beginStaging): the owner stage neither takes nor needs it
        Path guiPkg = dir.resolve("pkg-gui-owner");
        Inputs guiOwner = stage("owner stage", () -> ownerStageFx(dir, guiPkg, true));
        held.clear();
        try {
            held.add(stage("the console's stage.lock hold", () -> hold(guiPkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE))));
            writeGreen(guiOwner, "an owner stage under the console's stage.lock hold");
        } finally {
            closeAll(held);
        }
    }

    /** (5) R8 on a symlinked {@code classes} for both COPY kinds; the owner stage's link to the passed target is accepted. */
    private static void linkRefused(Path dir) throws Exception {
        Path target = stage("link target", () -> PackageZipSelftest.tree(dir.resolve("link-target"),
                Map.of("cc/x/Folded.class", FOLDED)));
        Path packedPkg = stage("packed folder", () -> Files.createDirectories(dir.resolve("pkg-packed")));
        symlinkOrSkip(packedPkg.resolve("classes"), target);
        PackedFx fx = stage("packed fixture", () -> packedFx(dir, Break.NONE));
        List<String> packed = refusalsOf(fx.inputs(packedPkg));
        require(packed.contains(at(R8, packedPkg, "classes")), "a PACKED plan no longer refuses R8 naming classes when"
                + " <pkg>/classes is a symbolic link (the link test regressed): " + packed);

        Path sourcePkg = stage("source folder", () -> Files.createDirectories(dir.resolve("pkg-source")));
        symlinkOrSkip(sourcePkg.resolve("classes"), target);
        SourceFx src = stage("source fixture", () -> sourceFx(dir, HEAD_A));
        List<String> source = refusalsOf(src.inputs(sourcePkg));
        require(source.contains(at(R8, sourcePkg, "classes")), "a SOURCE plan no longer refuses R8 naming classes when"
                + " <pkg>/classes is a symbolic link: " + source);

        Path ownerPkg = dir.resolve("pkg-owner");
        Inputs owner = stage("owner stage, classes outside", () -> ownerStageFx(dir, ownerPkg, false));
        symlinkOrSkip(ownerPkg.resolve("classes"), owner.classesDir());
        List<String> ownerLines = refusalsOf(owner);
        require(ownerLines.isEmpty(), "an owner stage whose classes link resolves to the passed target is refused (the"
                + " isSameFile law no longer holds through a link): " + ownerLines);
        Path other = stage("another classes tree", () -> PackageZipSelftest.tree(dir.resolve("other-classes"),
                Map.of("cc/x/Folded.class", FOLDED)));
        List<String> elsewhere = refusalsOf(withClasses(owner, other));
        require(elsewhere.contains(at(R10, "a stage link to " + mask(other), norm(ownerPkg).resolve("classes"))),
                "an owner stage whose classes link resolves to ANOTHER folder than the passed target is accepted (the"
                        + " same-file law dropped): " + elsewhere);
    }

    /** (6) the owner stage: no parity, today's row set, the passed meta, no stamp, host and secret files never read. */
    private static void ownerStage(Path dir) throws Exception {
        Path pkg = dir.resolve("stage");
        Inputs in = stage("owner stage", () -> ownerStageFx(dir, pkg, true));
        LinkedHashMap<String, byte[]> hostFiles = new LinkedHashMap<>();
        hostFiles.put("cc-server.properties", ascii("admin.password.file=ops-admin.password\n"));
        hostFiles.put("match/x.txt", ascii(LIVE_MATCH));
        hostFiles.put("ops-admin.password", ascii("fixture-password\n"));
        hostFiles.put("match/seat-tokens.txt", ascii("seat.2.token=" + LIVE_TOKEN + "\n"));
        stage("host and secret files", () -> PackageZipSelftest.tree(pkg, hostFiles));
        // the observable for "never opened": each host / secret file is held under an EXCLUSIVE byte-range lock, so on
        // Windows (mandatory locks) any read of one fails - as a refusal, a write failure or a QUIET_FAILURES count
        List<FileChannel> held = new ArrayList<>();
        Result r;
        try {
            for (String rel : hostFiles.keySet()) {
                held.add(stage("exclusive lock on " + rel, () -> exclusive(pkg.resolve(rel))));
            }
            boolean readsBlocked;
            try {
                Files.readAllBytes(pkg.resolve("ops-admin.password"));
                readsBlocked = false;
            } catch (IOException lockedOut) {
                readsBlocked = true;
            }
            if (!readsBlocked) {
                System.out.println("NOTE pfw-owner-stage: byte-range locks are advisory on this OS - the never-read leg"
                        + " is not observed here (the row-set, meta and stamp legs still run)");
            }
            int quietBefore = PackageFolderWriter.QUIET_FAILURES.get();
            r = writeGreen(in, "an owner stage holding a live match/x.txt, a properties file and two secret files");
            require(PackageFolderWriter.QUIET_FAILURES.get() == quietBefore, "the owner stage counted a quiet failure"
                    + " while its host and secret files were locked (a read of one was swallowed)");
        } finally {
            closeAll(held);
        }
        PackageManifest m = PackageManifest.read(pkg.resolve(ServeLauncher.MANIFEST_FILE));
        require(new TreeSet<>(m.sha256ByPath.keySet()).equals(new TreeSet<>(OWNER_ROWS)), "the owner stage's MANIFEST.txt"
                + " is no longer today's row set (serve.argv, premain, RNG holder, C2, classes/**, serve-mods/** incl."
                + " enabled_mods.json; never match/, properties or secrets): " + new TreeSet<>(m.sha256ByPath.keySet()));
        require(r.files() == OWNER_ROWS.size(), "the owner stage's OK count is no longer its manifest row count: " + r.files());
        require(new ArrayList<>(m.meta.keySet()).equals(List.of(PackageManifest.GIT_HEAD, PackageManifest.KIT_JAR_MD5))
                && HEAD_A.equals(m.meta.get(PackageManifest.GIT_HEAD))
                && PASSED_MD5.equals(m.meta.get(PackageManifest.KIT_JAR_MD5)), "the owner stage's meta is no longer exactly"
                + " git.head + the PASSED kit.jar.md5 (a pack.stamp or another row came in): " + m.meta.keySet());
        boolean gated;
        try {
            OpsRoots.packagedChecked(pkg);
            gated = false;
        } catch (IllegalArgumentException notPacked) {
            gated = true;
        }
        require(gated, "packagedChecked ACCEPTS an owner stage (it gained a pack.stamp: --pkg would treat the owner's"
                + " stage as a released package)");
        require(!Files.exists(pkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE)), "the owner stage created stage.lock (it must"
                + " never take the lock the console that staged holds)");

        Path pkg2 = dir.resolve("stage-elsewhere");
        Inputs in2 = stage("second owner stage", () -> ownerStageFx(dir, pkg2, true));
        Path other = stage("another classes tree", () -> PackageZipSelftest.tree(dir.resolve("other-classes"),
                Map.of("cc/x/Folded.class", FOLDED)));
        List<String> elsewhere = refusalsOf(withClasses(in2, other));
        require(elsewhere.equals(List.of(at(R10, "a stage link to " + mask(other), norm(pkg2).resolve("classes")))),
                "an owner stage whose <pkg>/classes is not the passed classes target is no longer refused with exactly"
                        + " the stage-link R10 (the isSameFile law dropped): " + elsewhere);
    }

    /** (7) a SOURCE re-run from another release's base refuses and changes nothing; the same release's re-run plans. */
    private static void sourceRerunHead(Path dir) throws Exception {
        SourceFx a = stage("base A", () -> sourceFx(dir, HEAD_A));
        SourceFx b = stage("base B", () -> sourceFx(dir, HEAD_B));
        Path pkg = dir.resolve("pkg");
        writeGreen(a.inputs(pkg), "SOURCE run 1 from base A");
        Path manifest = pkg.resolve(ServeLauncher.MANIFEST_FILE);
        PackageManifest first = PackageManifest.read(manifest);
        require(HEAD_A.equals(first.meta.get(PackageManifest.GIT_HEAD)), "a SOURCE folder no longer carries its base's"
                + " git.head: " + mask(first.meta.get(PackageManifest.GIT_HEAD)));
        byte[] before = Files.readAllBytes(manifest);
        TreeSet<String> filesBefore = listing(pkg);
        List<String> same = refusalsOf(a.inputs(pkg));
        require(same.isEmpty(), "a SOURCE re-run from the SAME release is refused (the head compare refuses every"
                + " re-run): " + same);
        try (Plan p = PackageFolderWriter.plan(b.inputs(pkg))) {
            require(p.refusals().equals(List.of(at(OTHER_RELEASE, HEAD_A, HEAD_B))), "a SOURCE re-run from a base with"
                    + " another git.head no longer refuses with exactly the another-release line (the other-release check dropped): "
                    + p.refusals());
            writeRefused(p, "the other-release plan");
        }
        require(Arrays.equals(Files.readAllBytes(manifest), before), "the refused other-release re-run changed"
                + " MANIFEST.txt");
        require(listing(pkg).equals(filesBefore), "the refused other-release re-run added or removed files");
    }

    /**
     * (8) an I/O failure on the LAST listed route ({@code serve.argv}: a non-empty FOLDER of that name planted
     * between the plan and the write, so the temp file's ATOMIC_MOVE onto it fails): A = a fresh PACKED folder,
     * B = a SOURCE re-run over a finished folder (its old MANIFEST.txt must go), then the next SOURCE runs.
     */
    private static void interrupted(Path dir) throws Exception {
        // A: a fresh PACKED folder
        PackedFx fx = stage("packed fixture", () -> packedFx(dir, Break.NONE));
        Path pkg = stage("packed folder", () -> Files.createDirectories(dir.resolve("pkg-packed")));
        String thrown;
        try (Plan p = PackageFolderWriter.plan(fx.inputs(pkg))) {
            require(p.refusals().isEmpty(), "the green PACKED fixture is refused: " + p.refusals());
            // the scripts are LISTED since the folder ships program files only, so the last host script sorts last;
            // update.bat since main's fold put it on the host table
            String last = lastListed(p);
            require(last != null && last.equals("update.bat"), "the PACKED route table's last listed route is no longer"
                    + " update.bat (the row's fault target; flips if the scripts go back to unlisted seeds): " + last);
            stage("blocker folder", () -> PackageZipSelftest.tree(pkg.resolve("update.bat"), Map.of("blocker.txt",
                    ascii("x"))));
            thrown = writeFailed(p, "the PACKED write onto a blocked update.bat");
        }
        requireEf(thrown, "the PACKED write", "update.bat");
        Path manifest = pkg.resolve(ServeLauncher.MANIFEST_FILE);
        Path failed = pkg.resolve("MANIFEST.failed");
        require(!Files.exists(manifest), "an interrupted PACKED write left a MANIFEST.txt behind (the folder would start"
                + " half-written)");
        require(Files.isRegularFile(failed), "an interrupted PACKED write left no MANIFEST.failed (the next run's"
                + " made-by-this-tool marker and delete scope)");
        PackageManifest marker = PackageManifest.read(failed);
        require(STAMP.equals(marker.meta.get(OpsRoots.PACK_STAMP)), "MANIFEST.failed no longer carries the run's"
                + " pack.stamp (the next run would read the folder as not made by this tool): "
                + mask(marker.meta.get(OpsRoots.PACK_STAMP)));
        require(marker.sha256ByPath.containsKey("classes/cc/x/Folded.class")
                && !marker.sha256ByPath.containsKey("update.bat"), "MANIFEST.failed no longer lists exactly the rows"
                + " placed before the failure (a placed class, never the failed update.bat)");
        SourceFx src = stage("source fixture", () -> sourceFx(dir, HEAD_A));
        List<String> next = refusalsOf(src.inputs(pkg));
        require(!next.contains(at(R3, pkg)), "the next SOURCE run on an interrupted PACKED folder is R3 (MANIFEST.failed"
                + " no longer read as the made-by-this-tool marker): " + next);

        // B: a SOURCE re-run over a finished folder fails on the same route
        Path pkg2 = dir.resolve("pkg-source");
        writeGreen(src.inputs(pkg2), "SOURCE run 1");
        Path manifest2 = pkg2.resolve(ServeLauncher.MANIFEST_FILE);
        Path failed2 = pkg2.resolve("MANIFEST.failed");
        try (Plan p = PackageFolderWriter.plan(src.inputs(pkg2))) {
            require(p.refusals().isEmpty(), "a same-release SOURCE re-run is refused: " + p.refusals());
            String last = lastListed(p);
            require(last != null && last.equals("serve.argv"), "the SOURCE route table's last listed route is no longer"
                    + " serve.argv (the row's fault target): " + last);
            stage("blocker folder", () -> {
                Files.delete(pkg2.resolve("serve.argv"));
                return PackageZipSelftest.tree(pkg2.resolve("serve.argv"), Map.of("blocker.txt", ascii("x")));
            });
            thrown = writeFailed(p, "the SOURCE re-run onto a blocked serve.argv");
        }
        requireEf(thrown, "the SOURCE re-run");
        require(!Files.exists(manifest2), "an interrupted SOURCE re-run left the previous MANIFEST.txt behind (the"
                + " folder would start half-rewritten under the old manifest)");
        require(Files.isRegularFile(failed2), "an interrupted SOURCE re-run left no MANIFEST.failed");

        // C: the operator clears the blocker; the next SOURCE run is not R3 and finishes the folder
        stage("blocker removed", () -> {
            deleteTreeOrThrow(pkg2.resolve("serve.argv"));
            return null;
        });
        List<String> again = refusalsOf(src.inputs(pkg2));
        require(again.isEmpty(), "the next SOURCE run on an interrupted folder is refused (R3 or another-release: the"
                + " MANIFEST.failed marker no longer carries the stamp or head): " + again);
        writeGreen(src.inputs(pkg2), "the SOURCE run after an interrupted one");
        require(Files.isRegularFile(manifest2) && !Files.exists(failed2), "a finished run after an interrupted one no"
                + " longer leaves MANIFEST.txt and removes MANIFEST.failed");

        interruptedDroppedClass(dir);
        interruptedByError(dir, fx, src);
    }

    /**
     * (8) leg B': run 1 places {@code classes/a/B.class}; run 2 no longer builds it and fails on {@code serve.argv}
     * before its deletes run, so {@code MANIFEST.failed} must still LIST it (the previous set united in); run 3
     * (green) then deletes it.
     */
    private static void interruptedDroppedClass(Path dir) throws Exception {
        SourceFx src = stage("source fixture", () -> sourceFx(dir, HEAD_A));
        Path dropped = src.classes().resolve("a").resolve("B.class");
        stage("built class a/B.class", () -> PackageZipSelftest.tree(src.classes(), Map.of("a/B.class", ascii("b-class"))));
        Path pkg = dir.resolve("pkg-dropped");
        writeGreen(src.inputs(pkg), "B' run 1 with classes/a/B.class");
        Path placed = pkg.resolve("classes").resolve("a").resolve("B.class");
        require(Files.isRegularFile(placed), "B' run 1 no longer places the built class classes/a/B.class (the classes"
                + " route dropped it)");
        stage("a/B.class dropped from the build", () -> {
            Files.delete(dropped);
            return null;
        });
        String thrown;
        try (Plan p = PackageFolderWriter.plan(src.inputs(pkg))) {
            require(p.refusals().isEmpty(), "B' run 2 (one class fewer) is refused: " + p.refusals());
            require(p.deletes().contains("classes/a/B.class"), "B' run 2 no longer plans the delete of the class the"
                    + " build dropped (deletesPhase lost the previous manifest): " + p.deletes());
            stage("blocker folder", () -> {
                Files.delete(pkg.resolve("serve.argv"));
                return PackageZipSelftest.tree(pkg.resolve("serve.argv"), Map.of("blocker.txt", ascii("x")));
            });
            thrown = writeFailed(p, "B' run 2 onto a blocked serve.argv");
        }
        requireEf(thrown, "B' run 2");
        PackageManifest marker = PackageManifest.read(pkg.resolve("MANIFEST.failed"));
        require(marker.sha256ByPath.containsKey("classes/a/B.class"), "B': MANIFEST.failed no longer LISTS"
                + " classes/a/B.class after run 2 failed before its deletes (failure() dropped the previous-set union:"
                + " the class is unlisted, invisible to G2, never deleted): " + marker.sha256ByPath.keySet());
        stage("blocker removed", () -> {
            deleteTreeOrThrow(pkg.resolve("serve.argv"));
            return null;
        });
        writeGreen(src.inputs(pkg), "B' run 3");
        require(!Files.exists(placed), "B' run 3 left classes/a/B.class in the folder (the previous set read from"
                + " MANIFEST.failed no longer scopes the delete)");
    }

    /**
     * (8) leg C: an {@link Error} thrown mid-routes from {@link PackageFolderWriter#BEFORE_ROUTE} (at
     * {@code serve.argv}, after real places) = a run that dies where no {@code catch} runs. AT the seam the folder
     * must already carry the made-by-this-tool marker (a fresh PACKED folder) and no stamped {@code MANIFEST.txt}
     * (a SOURCE re-run); the Error must reach the caller as is; {@code failure()} must have recorded the rows
     * placed (a placed class with its real sha256, never the unplaced {@code serve.argv}); the next SOURCE run
     * is not R3.
     */
    private static void interruptedByError(Path dir, PackedFx fx, SourceFx src) throws Exception {
        Path pkg = stage("packed folder", () -> Files.createDirectories(dir.resolve("pkg-error")));
        boolean[] markerAtSeam = new boolean[1];
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> freshSeamRows =
                new java.util.concurrent.atomic.AtomicReference<>();
        Throwable got = writeWithSeam(fx.inputs(pkg), "C (fresh PACKED)", folder -> {
            markerAtSeam[0] = PackageFolderWriter.madeByThisTool(folder) != null;
            freshSeamRows.set(markerRowsOrNull(folder));
        });
        require(markerAtSeam[0], "C: at the instant of the mid-routes Error (a kill: no catch runs) a fresh PACKED"
                + " folder carried no made-by-this-tool marker (MANIFEST.failed is written only inside failure(), not"
                + " before the first route - the next run would be R3)");
        // the pre-route marker's row SET is the next run's delete
        // scope - on a fresh folder every PLANNED listed row rides it at the UNPLACED sha (serve.argv is not yet placed
        // at the seam); dropping the planned-row loop flips this.
        require(freshSeamRows.get() != null && UNPLACED.equals(freshSeamRows.get().get("serve.argv")),
                "C: at the seam the pre-route MANIFEST.failed no longer lists the planned row serve.argv at the UNPLACED"
                        + " sha (markUnfinished dropped the planned rows: the next run's delete scope misses what this run"
                        + " places): " + (freshSeamRows.get() == null ? "marker unreadable" : freshSeamRows.get().get("serve.argv")));
        require(got instanceof SeamError, "C: the Error thrown mid-routes no longer reaches the caller as is ("
                + (got == null ? "nothing was thrown" : got.getClass().getSimpleName() + " instead") + ")");
        require(PackageFolderWriter.madeByThisTool(pkg) != null, "C: after the Error the folder reads as not made by"
                + " this tool (no stamped MANIFEST.txt / MANIFEST.failed)");
        require(!Files.exists(pkg.resolve(ServeLauncher.MANIFEST_FILE)), "C: the Error left a MANIFEST.txt behind");
        PackageManifest marker = PackageManifest.read(pkg.resolve("MANIFEST.failed"));
        String placedRow = marker.sha256ByPath.get("classes/cc/x/Folded.class");
        require(placedRow != null && !placedRow.equals("0".repeat(64)) && !marker.sha256ByPath.containsKey("serve.argv"),
                "C: MANIFEST.failed is not failure()'s record of the rows placed (a placed class with its real sha256,"
                        + " never the unplaced serve.argv) - the Error escaped before failure() ran: "
                        + marker.sha256ByPath.keySet());
        List<String> next = refusalsOf(src.inputs(pkg));
        require(!next.contains(at(R3, pkg)), "C: the next SOURCE run on a folder an Error interrupted is R3: " + next);

        Path rerun = dir.resolve("pkg-error-rerun");
        // run 1 builds classes/a/C.class; the re-run no longer does (a dropped class)
        // and is killed mid-routes, so the pre-route marker must still LIST it (the previous set united in: the class
        // stays in the next run's delete scope); dropping the previous set flips this.
        Path droppedClass = src.classes().resolve("a").resolve("C.class");
        stage("built class a/C.class", () -> PackageZipSelftest.tree(src.classes(), Map.of("a/C.class", ascii("c-class"))));
        writeGreen(src.inputs(rerun), "C run 1 (SOURCE)");
        require(Files.isRegularFile(rerun.resolve("classes").resolve("a").resolve("C.class")),
                "C run 1 no longer places the built class classes/a/C.class (the classes route dropped it)");
        stage("a/C.class dropped from the build", () -> {
            Files.delete(droppedClass);
            return null;
        });
        boolean[] staleAtSeam = new boolean[1];
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> rerunSeamRows =
                new java.util.concurrent.atomic.AtomicReference<>();
        Throwable got2 = writeWithSeam(src.inputs(rerun), "C (SOURCE re-run)", folder -> {
            staleAtSeam[0] = Files.exists(folder.resolve(ServeLauncher.MANIFEST_FILE));
            rerunSeamRows.set(markerRowsOrNull(folder));
        });
        require(!staleAtSeam[0], "C: at a mid-routes kill of a SOURCE re-run the previous stamped MANIFEST.txt was"
                + " still in place over half-rewritten files (the pre-route marker step no longer drops it)");
        require(rerunSeamRows.get() != null && rerunSeamRows.get().containsKey("classes/a/C.class"),
                "C: at the seam of a SOURCE re-run the pre-route MANIFEST.failed no longer lists the previous-only row"
                        + " classes/a/C.class (markUnfinished dropped the previous set: a class the build dropped stays"
                        + " unlisted, invisible to G2, never deleted): "
                        + (rerunSeamRows.get() == null ? "marker unreadable" : rerunSeamRows.get().keySet()));
        require(got2 instanceof SeamError, "C: the re-run's mid-routes Error no longer reaches the caller as is");
        List<String> again = refusalsOf(src.inputs(rerun));
        require(again.isEmpty(), "C: the next SOURCE run on a re-run an Error interrupted is refused: " + again);
    }

    /** The sha a pre-route marker row carries before its file is placed ({@code PackageFolderWriter.UNPLACED_SHA}). */
    private static final String UNPLACED = "0".repeat(64);

    /** The rows of {@code MANIFEST.failed} in {@code folder}, or null when none is readable there. */
    private static Map<String, String> markerRowsOrNull(Path folder) {
        try {
            return PackageManifest.read(folder.resolve("MANIFEST.failed")).sha256ByPath;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** The Error {@link #writeWithSeam} throws mid-routes (an {@link AssertionError}, as a JVM-level failure would be). */
    private static final class SeamError extends AssertionError {
        SeamError() {
            super("the mid-routes seam");
        }
    }

    /**
     * Plans {@code in} (must be green) and writes it with {@link PackageFolderWriter#BEFORE_ROUTE} set: at the
     * {@code serve.argv} route {@code atSeam} observes the folder, then a {@link SeamError} is thrown. Returns
     * what {@code write} threw (null when it returned); the seam is cleared in {@code finally}.
     */
    private static Throwable writeWithSeam(Inputs in, String leg, java.util.function.Consumer<Path> atSeam) {
        Path folder = norm(in.pkg());
        PackageFolderWriter.BEFORE_ROUTE.set(t -> {
            if (t.equals(folder.resolve("serve.argv"))) {
                atSeam.accept(folder);
                throw new SeamError();
            }
        });
        try (Plan p = PackageFolderWriter.plan(in)) {
            require(p.refusals().isEmpty(), leg + ": the green fixture is refused: " + p.refusals());
            try {
                PackageFolderWriter.write(p);
                return null;
            } catch (SeamError | IOException | RuntimeException thrown) {
                return thrown;
            }
        } finally {
            PackageFolderWriter.BEFORE_ROUTE.set(null);
        }
    }

    /** (9) the CLI in a child JVM: a bad flag = exit 2 one line; a refused plan = exit 2 + its lines; green = exit 0 + OK. */
    private static void mainExit(Path dir) throws Exception {
        PackedFx fx = stage("packed fixture", () -> packedFx(dir, Break.NONE));
        Child bad = cli(dir, "--kind", "packed", "--bogus", "x");
        require(bad.exit() == 2 && bad.lines().equals(List.of(P + "REFUSED --bogus is not a flag of this tool")),
                "an unknown flag no longer exits 2 with its one REFUSED line: exit " + bad.exit() + " " + bad.lines());

        Path refusedPkg = dir.resolve("pkg-refused");
        Path absentPremain = dir.resolve("absent-premain.jar");
        Child refused = cli(dir, fx.withPremain(absentPremain).args(refusedPkg));
        require(refused.exit() == 2 && refused.lines().equals(List.of(at(R10, "the premain jar (--premain)",
                absentPremain))), "a refused plan no longer exits 2 with its REFUSED lines on stdout: exit "
                + refused.exit() + " " + refused.lines());
        require(!Files.exists(refusedPkg), "a refused CLI run created the server folder");

        Path okPkg = dir.resolve("pkg-ok");
        Child ok = cli(dir, fx.args(okPkg));
        Path manifest = okPkg.resolve(ServeLauncher.MANIFEST_FILE);
        require(ok.exit() == 0 && Files.isRegularFile(manifest), "a green PACKED CLI run no longer exits 0 with"
                + " MANIFEST.txt written: exit " + ok.exit() + " " + ok.lines());
        int rows = PackageManifest.read(manifest).sha256ByPath.size();
        String want = String.format(Locale.ROOT, OK, mask(norm(okPkg)), rows, 1);
        require(ok.lines().equals(List.of(want)), "a green PACKED CLI run no longer prints exactly the OK line (its file"
                + " count = the manifest rows, 1 test class left out): " + ok.lines());
    }

    /**
     * (10): SOURCE run 1 with the base's kit, then run 2 with a {@code --kit} whose jar differs: the kept
     * SEED sidecar's {@code jar=} would no longer be {@code kit.jar.md5}, so run 2 must refuse R15 (else the
     * launcher refuses {@code sidecar_stale} at Start) and leave the folder byte-unchanged.
     *
     */
    private static void sourceRerunKit(Path dir) throws Exception {
        SourceFx src = stage("source fixture", () -> sourceFx(dir, HEAD_A));
        LinkedHashMap<String, byte[]> otherClasses = new LinkedHashMap<>();
        otherClasses.put("cc/x/Folded.class", FOLDED);
        otherClasses.put("cc/client/OnlyClient.class", ascii("client-only-v2"));
        byte[] otherJar = stage("another client jar", () -> PackageZipSelftest.jarWithClasses(otherClasses));
        String otherMd5 = ContentFingerprints.md5Hex(otherJar);
        Path otherKit = stage("another kit", () -> PackageZipSelftest.kitZip(dir, otherJar, null,
                kitSidecar(otherMd5), Map.of("CC_Client/data/x.csv", ascii("x,1"))));
        // that kit's own standing pair (--match): the base's pair names the base's jar, so only R15 is left to refuse
        Path otherMatch = stage("that kit's standing pair", () -> standingPair(dir.resolve("other-standing"),
                REDACTED_MATCH, sidecar(otherMd5)));
        Path pkg = dir.resolve("pkg");
        Child first = cliHere(src.args(pkg, null));
        require(first.exit() == 0, "SOURCE run 1 with the base's kit no longer exits 0: exit " + first.exit() + " "
                + first.lines());
        Path manifest = pkg.resolve(ServeLauncher.MANIFEST_FILE);
        Path jar = pkg.resolve("serve-mods/CC_Client/jars/cc-client-mod.jar");
        byte[] manifestBefore = Files.readAllBytes(manifest);
        String jarSha = PackageManifest.sha256Hex(jar);
        String keptMd5 = ContentFingerprints.md5Hex(Files.readAllBytes(jar));
        TreeSet<String> before = listing(pkg);
        Child second = cliHere(src.args(pkg, otherKit, otherMatch));
        String want = String.format(Locale.ROOT, R15, keptMd5, otherMd5);
        require(second.exit() == 2 && second.lines().equals(List.of(want)), "a SOURCE re-run placing another kit's jar"
                + " under the kept match/ sidecar no longer refuses with exactly R15 (it prints OK: kit.jar.md5 and the"
                + " sidecar's jar= now differ, the launcher refuses sidecar_stale at Start): exit " + second.exit() + " "
                + second.lines());
        require(Arrays.equals(Files.readAllBytes(manifest), manifestBefore) && PackageManifest.sha256Hex(jar).equals(jarSha),
                "the refused R15 run changed MANIFEST.txt or the placed jar");
        require(listing(pkg).equals(before), "the refused R15 run added or removed files");
    }

    /**
     * (11): {@code --kit <dir>/x-tokens.zip} (a real, otherwise green kit) held under an exclusive lock, so
     * on Windows any read of it fails: exit 2 with exactly ONE SECRET line, no UNREADABLE line, no quiet failure
     * counted, and the kit never read (no jar route planned — the observable on an OS with advisory locks too).
     *
     */
    private static void secretInput(Path dir) throws Exception {
        PackedFx fx = stage("packed fixture", () -> packedFx(dir, Break.NONE));
        Path tokens = stage("x-tokens.zip", () -> Files.copy(fx.kit(), dir.resolve("x-tokens.zip")));
        PackedFx sec = new PackedFx(fx.classes(), fx.premain(), fx.holder(), fx.c2(), fx.mod(), tokens, fx.argv(), fx.jre(),
                fx.host(), fx.jarMd5(), fx.match());
        Path pkg = dir.resolve("pkg");
        List<FileChannel> held = new ArrayList<>();
        try {
            held.add(stage("exclusive lock on x-tokens.zip", () -> exclusive(tokens)));
            if (!readBlocked(tokens)) {
                System.out.println("NOTE pfw-secret-input: byte-range locks are advisory on this OS - the UNREADABLE leg"
                        + " is not observed here (the exit, SECRET and no-jar-route legs still run)");
            }
            int quietBefore = PackageFolderWriter.QUIET_FAILURES.get();
            Child c = cliHere(sec.args(pkg));
            List<String> routes = new ArrayList<>();
            try (Plan p = PackageFolderWriter.plan(sec.inputs(pkg))) {
                p.routes().forEach(r -> routes.add(r.rel()));
            }
            int quietAfter = PackageFolderWriter.QUIET_FAILURES.get();
            require(c.lines().stream().noneMatch(l -> l.contains(UNREADABLE_MID)), "a secret-named --kit was OPENED (an"
                    + " UNREADABLE line: the plan read it under the lock): " + c.lines());
            require(c.exit() == 2 && c.lines().equals(List.of(at(SECRET, tokens))), "a secret-named --kit no longer"
                    + " refuses with exactly its one SECRET line and exit 2 (the open ran, or the SECRET line is gone):"
                    + " exit " + c.exit() + " " + c.lines());
            require(!routes.contains("serve-mods/CC_Client/jars/cc-client-mod.jar"), "a secret-named --kit was READ"
                    + " (its jar became a planned route: readKit no longer consults the secret set)");
            require(quietAfter == quietBefore, "a secret-named --kit counted a quiet failure (a swallowed read of it)");
        } finally {
            closeAll(held);
        }
        require(!Files.exists(pkg), "a refused secret-input run created the server folder");
    }

    /**
     * (12) S4 both legs: PACKED {@code --argv <mint>/serve.password} + a built mod file {@code data/x-tokens.csv}
     * = exactly TWO SECRET lines (the input, then the routed name), exit 2, nothing written.
     *
     */
    private static void secretNameRow(Path dir) throws Exception {
        PackedFx fx = stage("packed fixture", () -> packedFx(dir, Break.NONE));
        Path password = stage("serve.password", () -> Files.copy(fx.argv(), fx.argv().resolveSibling("serve.password")));
        stage("data/x-tokens.csv", () -> Files.write(fx.mod().resolve("data").resolve("x-tokens.csv"), ascii("x,9")));
        PackedFx sec = new PackedFx(fx.classes(), fx.premain(), fx.holder(), fx.c2(), fx.mod(), fx.kit(), password, fx.jre(),
                fx.host(), fx.jarMd5(), fx.match());
        Path pkg = dir.resolve("pkg");
        Child c = cliHere(sec.args(pkg));
        List<String> want = List.of(at(SECRET, password),
                String.format(Locale.ROOT, SECRET, mask("serve-mods/CC_Client/data/x-tokens.csv")));
        require(c.exit() == 2 && c.lines().equals(want), "a secret-named --argv + a routed *-tokens* file no longer"
                + " refuse with exactly two SECRET lines (the input's secretName call or the grammar's secret-name leg"
                + " is a no-op): exit " + c.exit() + " " + c.lines());
        require(!Files.exists(pkg), "a refused secret-name run created the server folder");
    }

    /**
     * (13): a base whose entry {@code jre/a*b} (illegal on Windows, outside the portable law everywhere)
     * over an existing EMPTY folder = exit 2 with exactly one R9 naming it, nothing written, no stack trace,
     * no route for the name.
     */
    private static void baseZipIllegalName(Path dir) throws Exception {
        SourceFx src = stage("source fixture with jre/a*b", () -> sourceFx(dir, HEAD_A, Map.of("jre/a*b", ascii("x"))));
        Path pkg = stage("empty folder", () -> Files.createDirectories(dir.resolve("pkg")));
        Child c;
        List<String> routes = new ArrayList<>();
        try {
            c = cliHere(src.args(pkg, null));
            try (Plan p = PackageFolderWriter.plan(src.inputs(pkg))) {
                p.routes().forEach(r -> routes.add(r.rel()));
            }
        } catch (RuntimeException trace) {
            throw new AssertionError("a base entry name reached pkg.resolve before the grammar (exit 1 + a "
                    + trace.getClass().getSimpleName() + " stack trace, no package-folder: line)");
        }
        require(c.exit() == 2 && c.lines().equals(List.of(String.format(Locale.ROOT, R9, mask("jre/a*b")))),
                "a base entry jre/a*b no longer refuses with exactly one R9 naming it (exit " + c.exit() + "): "
                        + c.lines());
        require(routes.stream().noneMatch(r -> r.contains("a*b")), "the base's illegal entry name is still a planned"
                + " route: " + routes);
        TreeSet<String> after = listing(pkg);
        after.remove(ServerOpsLaunch.STAGE_LOCK_FILE);
        require(after.isEmpty(), "a refused illegal-name run wrote into the folder: " + after);
    }

    /**
     * (13b) a SOURCE base carrying the shipped shape library (two {@code match/match-<name>.txt} +
     * {@code .fingerprint} pairs, unlisted in its manifest, their sidecars keyed to ANOTHER jar) over an empty
     * folder, the kit composed from the base: exit 0; the pair lands in {@code match/} with no manifest row, the
     * text byte-equal to the base's, the sidecar RE-KEYED (its {@code jar=} the kit's, its {@code match=} the
     * shape's id - the base's stale sidecar bytes never land); the pair whose id is the kit's own shape is deduped
     * (nothing written for it); a re-run keeps the pair (SEED: the same OK line, the same bytes); an orphan sidecar
     * refuses with exactly one R13 line naming it, nothing written.
     */
    private static void baseShapes(Path dir) throws Exception {
        MatchDescriptor kitShape = MatchDescriptors.redact(MatchDescriptors.mint("1v1", 6000f, 0.8f));
        MatchDescriptor alpha = MatchDescriptors.redact(MatchDescriptors.mint("1v1", 7000f, 0.7f));
        byte[] kitText = MatchDescriptorCodec.encode(kitShape).getBytes(StandardCharsets.UTF_8);
        byte[] alphaText = MatchDescriptorCodec.encode(alpha).getBytes(StandardCharsets.UTF_8);
        String jarMd5 = ContentFingerprints.md5Hex(jarBytes());
        String kitSidecar = "# fixture kit sidecar\r\nfingerprint=" + ContentFingerprints.composite(jarMd5, PASSED_MD5,
                HandshakeCodec.HANDSHAKE_VERSION, kitShape.matchId()) + "\r\nfile=data/x.csv\r\n";
        String staleSidecar = "# stale library sidecar\r\nfingerprint=" + ContentFingerprints.composite(STALE_MD5,
                PASSED_MD5, HandshakeCodec.HANDSHAKE_VERSION, alpha.matchId()) + "\r\nfile=data/x.csv\r\n";
        LinkedHashMap<String, byte[]> extra = new LinkedHashMap<>();
        extra.put("match/match.txt", kitText);
        extra.put("match/match.txt.fingerprint", ascii(kitSidecar));
        extra.put("match/match-alpha.txt", alphaText);
        extra.put("match/match-alpha.txt.fingerprint", ascii(staleSidecar));
        extra.put("match/match-same.txt", kitText);
        extra.put("match/match-same.txt.fingerprint", ascii(staleSidecar));
        SourceFx src = stage("source fixture with a shape library", () -> sourceFx(dir, HEAD_A, extra));
        Path pkg = dir.resolve("pkg");
        Child first = cliHere(src.args(pkg, null));
        require(first.exit() == 0, "a SOURCE run over a base carrying shape pairs no longer exits 0 (the pairs refuse as"
                + " unrouted base entries): exit " + first.exit() + " " + first.lines());
        Path txt = pkg.resolve("match").resolve("match-alpha.txt");
        Path side = pkg.resolve("match").resolve("match-alpha.txt.fingerprint");
        require(Files.isRegularFile(txt) && Arrays.equals(Files.readAllBytes(txt), alphaText),
                "the library shape text did not land byte-equal to the base's");
        require(Files.isRegularFile(side), "the library shape's sidecar did not land");
        byte[] sideBytes = Files.readAllBytes(side);
        String composite = ContentFingerprints.parseSidecarFingerprint(
                new String(sideBytes, StandardCharsets.ISO_8859_1).lines().toList(), "match-alpha");
        String jar = ContentFingerprints.componentOrNull(composite, "jar");
        String match = ContentFingerprints.componentOrNull(composite, "match");
        require(jarMd5.equals(jar) && alpha.matchId().equals(match), "the shape sidecar is not the kit's re-keyed to the"
                + " shape's id (jar= " + jar + ", match= " + match + "): a copied base sidecar leaves the shape refused"
                + " at Start as stale once the kit's jar differs from the package's");
        require(!Files.exists(pkg.resolve("match").resolve("match-same.txt"))
                && !Files.exists(pkg.resolve("match").resolve("match-same.txt.fingerprint")),
                "the base's pair for the kit's own shape was written (not deduped: two files of one id)");
        String manifest = Files.readString(pkg.resolve(ServeLauncher.MANIFEST_FILE), StandardCharsets.UTF_8);
        require(!manifest.contains("match-alpha"), "a shape pair gained a MANIFEST.txt row (it is unlisted, like the"
                + " kit's own pair)");
        Child second = cliHere(src.args(pkg, null));
        require(second.exit() == 0 && second.lines().equals(first.lines()) && Arrays.equals(Files.readAllBytes(side),
                sideBytes), "a re-run over the kept shape pair no longer repeats run 1's OK line with the pair unchanged"
                + " (a K line, a refusal or a rewrite): exit " + second.exit() + " " + second.lines());
        // SOURCE --kit with a current kit (its sidecar carries no match
        // term) over the same base: the shape sidecar is re-keyed from the base's STANDING sidecar
        Path kitNow = stage("a kit holding no match file", () -> PackageZipSelftest.kitZip(dir, jarBytes(), null,
                "# fixture kit sidecar\r\nfingerprint=" + ContentFingerprints.stripMatch(ContentFingerprints.composite(
                        jarMd5, PASSED_MD5, HandshakeCodec.HANDSHAKE_VERSION, kitShape.matchId())) + "\r\nfile=data/x.csv\r\n",
                Map.of("CC_Client/data/x.csv", ascii("x,1"))));
        Path pkg3 = dir.resolve("pkg3");
        Child withKit = cliHere(src.args(pkg3, kitNow));
        Path side3 = pkg3.resolve("match").resolve("match-alpha.txt.fingerprint");
        String keyed3 = withKit.exit() == 0 && Files.isRegularFile(side3) ? ContentFingerprints.componentOrNull(
                ContentFingerprints.parseSidecarFingerprint(Files.readAllLines(side3, StandardCharsets.ISO_8859_1),
                        "match-alpha"), "match") : null;
        require(alpha.matchId().equals(keyed3), "SOURCE --kit with a kit whose sidecar carries no match term no longer"
                + " re-keys the shape sidecar to the shape's id (flips if the re-key reads the kit's sidecar instead of"
                + " the standing one): exit " + withKit.exit() + " " + withKit.lines());
        LinkedHashMap<String, byte[]> orphan = new LinkedHashMap<>(extra);
        orphan.remove("match/match-alpha.txt");
        SourceFx bad = stage("source fixture with an orphan shape sidecar", () -> sourceFx(dir, HEAD_A, orphan));
        Path pkg2 = dir.resolve("pkg2");
        Child c = cliHere(bad.args(pkg2, null));
        require(c.exit() == 2 && c.lines().size() == 1 && c.lines().get(0).startsWith(P + "REFUSED ")
                && c.lines().get(0).contains(" entry match/match-alpha.txt.fingerprint is unsafe, too large, or differs"),
                "an orphan shape sidecar in the base no longer refuses with exactly one R13 line naming it: exit "
                        + c.exit() + " " + c.lines());
        require(!Files.exists(pkg2), "a refused orphan-sidecar run created the server folder");
    }

    /**
     * (14) the packer's step-2 checks, each ALONE on an otherwise green PACKED fixture: (a) a non-.class file
     * under {@code --classes} = exactly NON_CLASS; (b) a Linux native off its pin = exactly PIN (the green leg
     * at its own pin first), (b') a Windows Mesa dll off the stamp's {@code mesa.*.sha256=} pin = exactly PIN;
     * (c) {@code "id":"other"} = exactly MOD_ID_TEXT.
     */
    private static void packerChecks(Path dir) throws Exception {
        PackedFx green = stage("packed fixture", () -> packedFx(dir, Break.NONE));
        List<String> clean = refusalsOf(green.inputs(dir.resolve("pkg-green")));
        require(clean.isEmpty(), "a packer check fires on the green PACKED fixture: " + clean);

        PackedFx a = stage("packed fixture (a)", () -> packedFx(dir, Break.NONE));
        stage("a non-.class file", () -> Files.write(a.classes().resolve("cc").resolve("x").resolve("notes.txt"),
                ascii("not a class")));
        String wantA = String.format(Locale.ROOT, NON_CLASS, mask(norm(a.classes())), 1, mask("cc/x/notes.txt"));
        List<String> gotA = refusalsOf(a.inputs(dir.resolve("pkg-a")));
        require(gotA.equals(List.of(wantA)), "(a) a non-.class file under --classes no longer yields exactly the"
                + " NON_CLASS line (the check dropped): " + gotA);

        PackedFx w = stage("packed fixture (b)", () -> packedFx(dir, Break.NONE));
        Path root = w.classes().getParent();
        Path jre = stage("Linux JRE image", () -> PackageZipSelftest.tree(root.resolve("jre-linux"), Map.of(
                "cc-serve-jre.txt", ascii("implementorVersion=17.0.99-fixture\ntarget=linux\n"),
                "release", ascii("JAVA_VERSION=\"17\"\n"),
                "bin/java", ascii("elf"),
                "lib/jspawnhelper", ascii("helper"))));
        Path host = stage("Linux host files", () -> PackageZipSelftest.tree(root.resolve("host-linux"), Map.of(
                "cc-server.template.properties", ascii("port=1\n"),
                "serve.sh", ascii("#!/bin/sh\necho serve\n"),
                "stop.sh", ascii("#!/bin/sh\necho stop\n"),
                "ops-web.sh", ascii("#!/bin/sh\necho web\n"),
                "update.sh", ascii("#!/bin/sh\necho update\n"),
                "README.md", ascii("readme\n")))); // HOST_LINUX's README.
        Path natives = stage("Linux natives", () -> PackageZipSelftest.tree(root.resolve("native"),
                Map.of("libfixture.so", ascii("so-bytes"))));
        Path so = natives.resolve("libfixture.so");
        String soSha = PackageManifest.sha256Hex(so);
        Inputs linux = new Inputs(Kind.PACKED, dir.resolve("pkg-b"), w.classes(), w.premain(), w.holder(), w.c2(), w.mod(),
                w.kit(), null, w.argv(), jre, null, natives, host, Platform.LINUX, HEAD_A, null, STAMP, false, null, null,
                w.match());
        List<String> atPin = refusalsPinned(linux, Map.of("libfixture.so", soSha));
        require(atPin.isEmpty(), "(b) the Linux PACKED fixture with its native AT its pin is refused (the pin check"
                + " fires on a matching native): " + atPin);
        List<String> gotB = refusalsPinned(linux, Map.of("libfixture.so", WRONG_PIN));
        String wantB = String.format(Locale.ROOT, PIN, mask(so), soSha, WRONG_PIN, "libfixture.so");
        require(gotB.equals(List.of(wantB)), "(b) a Linux native off its pin no longer yields exactly the PIN line"
                + " (the native pin check dropped): " + gotB);

        PackedFx m = stage("packed fixture (b')", () -> packedFx(dir, Break.NONE));
        Path dll = m.jre().resolve("bin").resolve("opengl32.dll");
        String dllSha = PackageManifest.sha256Hex(dll);
        String stampHead = "implementorVersion=17.0.99-fixture\nsource=C:/jdk\nbytes=1\nmesa.opengl32.sha256=";
        stage("Mesa pin at the dll", () -> Files.write(m.jre().resolve("cc-serve-jre.txt"), ascii(stampHead + dllSha + "\n")));
        List<String> mesaAtPin = refusalsOf(m.inputs(dir.resolve("pkg-b2")));
        require(mesaAtPin.isEmpty(), "(b') a Mesa dll AT its stamp pin is refused: " + mesaAtPin);
        stage("Mesa pin off the dll", () -> Files.write(m.jre().resolve("cc-serve-jre.txt"),
                ascii(stampHead + WRONG_PIN + "\n")));
        List<String> gotMesa = refusalsOf(m.inputs(dir.resolve("pkg-b2")));
        String wantMesa = String.format(Locale.ROOT, PIN, mask(dll), dllSha, WRONG_PIN, "opengl32.dll");
        require(gotMesa.equals(List.of(wantMesa)), "(b') a Mesa dll off its stamp pin no longer yields exactly the PIN"
                + " line (the Mesa pin check dropped): " + gotMesa);

        PackedFx c = stage("packed fixture (c)", () -> packedFx(dir, Break.NONE));
        Path modInfo = c.mod().resolve("mod_info.json");
        stage("mod id other", () -> Files.write(modInfo, ascii("{\"id\":\"other\", \"name\": \"fixture\"}")));
        List<String> gotC = refusalsOf(c.inputs(dir.resolve("pkg-c")));
        require(gotC.equals(List.of(String.format(Locale.ROOT, MOD_ID_TEXT, mask(modInfo), "other"))), "(c) a mod_info.json"
                + " naming mod id 'other' no longer yields exactly the MOD_ID_TEXT line (the mod id check dropped): "
                + gotC);
    }

    /**
     * (15) the strict-redaction law's Java twin (the pwsh twin's row {@code T1-redaction-trim-twins} pins the
     * SAME three fixtures, verbatim and in this order — never change them): the value is trimmed before the
     * case-sensitive {@code REDACTED-} test, and EVERY seat-token row must pass it.
     *
     */
    private static void redactionTrimTwins(Path dir) {
        require(MatchDescriptors.isStrictlyRedacted(List.of("seat.0.token= REDACTED-a", "seat.1.token=REDACTED-b")),
                "a REDACTED- value after a leading space no longer reads as redacted (the value trim drifted from the"
                        + " pwsh twin's)");
        require(!MatchDescriptors.isStrictlyRedacted(List.of("seat.0.token=redacted-a")), "a lower-case redacted- value"
                + " now reads as redacted (the case-sensitive prefix test drifted)");
        require(!MatchDescriptors.isStrictlyRedacted(List.of("seat.0.token=REDACTED-a", "seat.1.token=live")), "one"
                + " REDACTED- row beside a live token now reads as redacted (the all-rows law drifted to any-row)");
    }

    /**
     * B2: the RNG holder jar is a REQUIRED input of every kind (the premain refuses a launch without it beside its own
     * jar): a {@code --holder} naming a missing file refuses with exactly the R10 line naming {@code --holder} on
     * OWNER_STAGE, PACKED and SOURCE, and through the CLI (exit 2, nothing created) - also when {@code --holder} is
     * left off entirely. Flips if any kind's require is lost or the flag stops parsing.
     *
     */
    private static void holderMissingRefuses(Path dir) throws Exception {
        Path absent = dir.resolve("absent-holder.jar");
        String want = at(R10, "the rng holder jar (--holder)", absent);
        PackedFx packed = stage("packed fixture", () -> packedFx(dir, Break.NONE));
        SourceFx source = stage("source fixture", () -> sourceFx(dir, HEAD_A));
        Path ownerPkg = dir.resolve("stage");
        Inputs owner = stage("owner stage", () -> ownerStageFx(dir, ownerPkg, true));
        require(refusalsOf(owner).isEmpty() && refusalsOf(packed.inputs(dir.resolve("pkg-green"))).isEmpty()
                && refusalsOf(source.inputs(dir.resolve("src-green"))).isEmpty(), "a green fixture WITH its holder is"
                + " refused (the holder require fires on a present file)");
        Map<String, List<String>> got = new LinkedHashMap<>();
        got.put("OWNER_STAGE", refusalsOf(withHolder(owner, absent)));
        got.put("PACKED", refusalsOf(packed.withHolder(absent).inputs(dir.resolve("pkg-a"))));
        got.put("SOURCE", refusalsOf(withHolder(source.inputs(dir.resolve("src-a")), absent)));
        for (Map.Entry<String, List<String>> e : got.entrySet()) {
            require(e.getValue().equals(List.of(want)), e.getKey() + " with a missing --holder no longer refuses with"
                    + " exactly the --holder R10 line (that kind's require dropped): " + e.getValue());
        }
        Path cliPkg = dir.resolve("pkg-cli");
        Child missing = cliHere(packed.withHolder(absent).args(cliPkg));
        List<String> flags = new ArrayList<>(Arrays.asList(packed.args(cliPkg)));
        int at = flags.indexOf("--holder");
        flags.subList(at, at + 2).clear();
        Child omitted = cliHere(flags.toArray(new String[0]));
        require(missing.exit() == 2 && missing.lines().equals(List.of(want)) && omitted.exit() == 2
                && omitted.lines().equals(List.of(P + "REFUSED the rng holder jar (--holder) not found: <none>"))
                && !Files.exists(cliPkg), "the CLI with a missing or omitted --holder no longer exits 2 with its one"
                + " --holder line and nothing created: " + missing.exit() + " " + missing.lines() + " / " + omitted.exit()
                + " " + omitted.lines());
    }

    // ---- fixtures ----

    /** Which ONE parity leg a PACKED fixture breaks. */
    private enum Break { NONE, LIVE_TOKEN, STALE_JAR, DATA_DIFFERS, FOLD_DIFFERS }

    /** A PACKED kind's inputs; {@link #inputs} points them at one folder, {@link #args} renders the same as CLI flags. */
    private record PackedFx(Path classes, Path premain, Path holder, Path c2, Path mod, Path kit, Path argv, Path jre,
            Path host, String jarMd5, Path match) {
        Inputs inputs(Path pkg) {
            return new Inputs(Kind.PACKED, pkg, classes, premain, holder, c2, mod, kit, null, argv, jre, null, null, host,
                    Platform.WINDOWS, HEAD_A, null, STAMP, false, null, null, match);
        }

        PackedFx withPremain(Path p) {
            return new PackedFx(classes, p, holder, c2, mod, kit, argv, jre, host, jarMd5, match);
        }

        /** The same fixture with another holder path. */
        PackedFx withHolder(Path h) {
            return new PackedFx(classes, premain, h, c2, mod, kit, argv, jre, host, jarMd5, match);
        }

        String[] args(Path pkg) {
            return new String[] {"--kind", "packed", "--pkg", pkg.toString(), "--classes", classes.toString(),
                "--premain", premain.toString(), "--holder", holder.toString(), "--c2", c2.toString(), "--client-mod",
                mod.toString(), "--kit", kit.toString(), "--argv", argv.toString(), "--jre", jre.toString(),
                "--host-files", host.toString(), "--platform", "windows", "--git-head", HEAD_A, "--stamp", STAMP,
                "--match", match.toString()};
        }
    }

    /** A SOURCE kind's inputs: the built classes + premain + holder and a released base (the kit comes from the base). */
    private record SourceFx(Path classes, Path premain, Path holder, Path base) {
        Inputs inputs(Path pkg) {
            return new Inputs(Kind.SOURCE, pkg, classes, premain, holder, null, null, null, base, null, null, null, null,
                    null, null, null, null, null, false);
        }

        /** The same inputs as CLI flags, plus {@code --kit} when {@code kit} is given. */
        String[] args(Path pkg, Path kit) {
            return args(pkg, kit, null);
        }

        /** {@link #args(Path, Path)} plus {@code --match} when {@code match} is given. */
        String[] args(Path pkg, Path kit, Path match) {
            List<String> a = new ArrayList<>(List.of("--kind", "source", "--pkg", pkg.toString(), "--classes",
                    classes.toString(), "--premain", premain.toString(), "--holder", holder.toString(), "--base",
                    base.toString()));
            if (kit != null) {
                a.addAll(List.of("--kit", kit.toString()));
            }
            if (match != null) {
                a.addAll(List.of("--match", match.toString()));
            }
            return a.toArray(new String[0]);
        }
    }

    /** The CLI child's exit code and stdout lines. */
    private record Child(int exit, List<String> lines) {
    }

    /** The folded class the jar and the built tree share. */
    private static final byte[] FOLDED = ascii("folded-class-v1");

    /**
     * A PACKED fixture under a fresh folder of {@code dir}: classes (the folded class + one selftest class left
     * home), premain, C2, the built client mod, a kit zip whose jar folds the class byte-equal and whose sidecar
     * names {@code data/x.csv}, the minted argv + launcher.properties + notices, a Windows JRE image with the Mesa
     * pair, the host files — green unless {@code brk} breaks one leg.
     */
    private static PackedFx packedFx(Path dir, Break brk) throws IOException {
        Path root = Files.createTempDirectory(dir, "packed-");
        Path classes = PackageZipSelftest.tree(root.resolve("classes"), Map.of(
                "cc/x/Folded.class", brk == Break.FOLD_DIFFERS ? ascii("folded-class-v2") : FOLDED,
                "cc/x/FixtureSelftest.class", ascii("left home")));
        byte[] jar = jarBytes();
        String jarMd5 = ContentFingerprints.md5Hex(jar);
        Path mod = PackageZipSelftest.tree(root.resolve("mod"), Map.of(
                "mod_info.json", ascii(MOD_INFO),
                "LICENSE.txt", ascii("fixture license\n"),
                "data/x.csv", ascii(brk == Break.DATA_DIFFERS ? "x,2" : "x,1")));
        String sideJar = brk == Break.STALE_JAR ? STALE_MD5 : jarMd5;
        Path kit = PackageZipSelftest.kitZip(root, jar, null, kitSidecar(sideJar), Map.of("CC_Client/data/x.csv",
                ascii("x,1")));
        // the standing pair rides --match (a kit carries no match file); its sidecar's content half is the kit's
        Path match = standingPair(root.resolve("standing"), brk == Break.LIVE_TOKEN ? LIVE_MATCH : REDACTED_MATCH,
                sidecar(sideJar));
        Path premain = Files.write(root.resolve("cc-premain.jar"), ascii("premain"));
        Path holder = Files.write(root.resolve("cc-rngpin-holder.jar"), ascii("rngpin-holder"));
        Path c2 = Files.write(root.resolve("c2-directives.json"), ascii("[]"));
        Path mint = PackageZipSelftest.tree(root.resolve("mint"), Map.of(
                "serve.argv", ascii("-Xmx1g\n"),
                "launcher.properties", ascii("launcher=fixture\n"),
                "launcher.template.properties", ascii("launcher=fixture-template\n"),
                "THIRD-PARTY-NOTICES.txt", ascii("notices\n")));
        Path jre = PackageZipSelftest.tree(root.resolve("jre"), Map.of(
                "cc-serve-jre.txt", ascii("implementorVersion=17.0.99-fixture\nsource=C:/jdk\nbytes=1\n"),
                "release", ascii("JAVA_VERSION=\"17\"\n"),
                "bin/java.exe", ascii("exe"),
                "bin/opengl32.dll", ascii("gl"),
                "bin/libgallium_wgl.dll", ascii("gallium")));
        Path host = PackageZipSelftest.tree(root.resolve("host"), Map.of(
                "cc-server.template.properties", ascii("port=1\n"),
                "serve.bat", ascii("@echo serve\r\n"),
                "stop.bat", ascii("@echo stop\r\n"),
                "ops-web.bat", ascii("@echo web\r\n"),
                "update.bat", ascii("@echo update\r\n"),
                "README.md", ascii("readme\n"))); // HOST_WINDOWS's README.
        return new PackedFx(classes, premain, holder, c2, mod, kit, mint.resolve("serve.argv"), jre, host, jarMd5, match);
    }

    /** A standing pair under {@code dir}: {@code match.txt} + {@code match.txt.fingerprint}; returns the match file.
     * */
    private static Path standingPair(Path dir, String matchText, String sidecarText) throws IOException {
        Files.createDirectories(dir);
        Path match = Files.write(dir.resolve("match.txt"), matchText.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("match.txt.fingerprint"), sidecarText.getBytes(StandardCharsets.UTF_8));
        return match;
    }

    /**
     * A SOURCE fixture: the built classes + premain and a base package zip (git.head = {@code head}) holding the
     * argv, the mod (mod_info, data, jar), the redacted match + sidecar, the C2 file, a JRE file and two SEED host
     * files; every non-SEED entry is listed in the base's own manifest.
     */
    private static SourceFx sourceFx(Path dir, String head) throws IOException {
        return sourceFx(dir, head, Map.of());
    }

    /** {@link #sourceFx(Path, String)} whose base also carries {@code extraEntries} (unlisted). */
    private static SourceFx sourceFx(Path dir, String head, Map<String, byte[]> extraEntries) throws IOException {
        Path root = Files.createTempDirectory(dir, "source-");
        Path classes = PackageZipSelftest.tree(root.resolve("classes"), Map.of(
                "cc/x/Folded.class", FOLDED, "cc/x/FixtureSelftest.class", ascii("left home")));
        Path premain = Files.write(root.resolve("cc-premain.jar"), ascii("premain"));
        Path holder = Files.write(root.resolve("cc-rngpin-holder.jar"), ascii("rngpin-holder"));
        byte[] jar = jarBytes();
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("serve.argv", ascii("-Xmx1g\n"));
        files.put("serve-mods/CC_Client/mod_info.json", ascii(MOD_INFO));
        files.put("serve-mods/CC_Client/data/x.csv", ascii("x,1"));
        files.put("serve-mods/CC_Client/jars/cc-client-mod.jar", jar);
        files.put("match/match.txt", ascii(REDACTED_MATCH));
        files.put("match/match.txt.fingerprint", ascii(sidecar(ContentFingerprints.md5Hex(jar))));
        files.put("jvm/c2-directives.json", ascii("[]"));
        files.put("jre/release", ascii("JAVA_VERSION=\"17\"\n"));
        files.put("serve.bat", ascii("@echo serve\r\n"));
        files.put("cc-server.properties", ascii("port=1\n"));
        files.putAll(extraEntries);
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put(PackageManifest.GIT_HEAD, head);
        meta.put(OpsRoots.PACK_STAMP, "base-1");
        meta.put("pack.kit", "cc-kit.zip");
        meta.put("pack.jre", "17.0.99-fixture");
        Set<String> listed = Set.of("serve.argv", "serve-mods/CC_Client/mod_info.json", "serve-mods/CC_Client/data/x.csv",
                "serve-mods/CC_Client/jars/cc-client-mod.jar", "jvm/c2-directives.json", "jre/release");
        return new SourceFx(classes, premain, holder, PackageZipSelftest.baseZip(root, files, meta, listed));
    }

    /**
     * An owner stage at {@code pkg} (made here): {@code serve.argv}, {@code serve-mods/enabled_mods.json} and the
     * {@code serve-mods/CC_Client} mod as REAL folders (the passed mod target IS that folder, so
     * {@link Files#isSameFile} holds without a link); the classes tree is {@code <pkg>/classes} when
     * {@code classesInPkg}, else a folder outside it that the caller links {@code <pkg>/classes} to.
     */
    private static Inputs ownerStageFx(Path dir, Path pkg, boolean classesInPkg) throws IOException {
        Path root = Files.createTempDirectory(dir, "owner-");
        PackageZipSelftest.tree(pkg, Map.of(
                "serve.argv", ascii("-Xmx1g\n"),
                "serve-mods/enabled_mods.json", ascii("{\"enabledMods\": [\"cc_client\"]}"),
                "serve-mods/CC_Client/mod_info.json", ascii(MOD_INFO),
                "serve-mods/CC_Client/jars/cc-client-mod.jar", ascii("jar"),
                "serve-mods/CC_Client/data/x.csv", ascii("x,1")));
        Map<String, byte[]> classes = Map.of("cc/x/Folded.class", FOLDED, "cc/x/Other.class", ascii("other"));
        Path classesDir = PackageZipSelftest.tree(classesInPkg ? pkg.resolve("classes") : root.resolve("classes-target"),
                classes);
        Path premain = Files.write(root.resolve("cc-premain.jar"), ascii("premain"));
        Path holder = Files.write(root.resolve("cc-rngpin-holder.jar"), ascii("rngpin-holder"));
        Path c2 = Files.write(root.resolve("c2-directives.json"), ascii("[]"));
        return new Inputs(Kind.OWNER_STAGE, pkg, classesDir, premain, holder, c2,
                pkg.resolve("serve-mods").resolve("CC_Client"), null, null, null, null, null, null, null, null, HEAD_A,
                PASSED_MD5, null, false);
    }

    private static Inputs withClasses(Inputs in, Path classes) {
        return new Inputs(in.kind(), in.pkg(), classes, in.premainJar(), in.holderJar(), in.c2File(), in.clientModDir(),
                in.kitZip(), in.baseZip(), in.argvFile(), in.jreDir(), in.mesaDir(), in.nativeDir(), in.hostFilesDir(),
                in.platform(), in.gitHead(), in.kitJarMd5(), in.stamp(), in.produced(), in.shapesDir(),
                in.modVariantsDir(), in.matchFile());
    }

    /** {@code in} with another holder path (every other input kept). */
    private static Inputs withHolder(Inputs in, Path holder) {
        return new Inputs(in.kind(), in.pkg(), in.classesDir(), in.premainJar(), holder, in.c2File(), in.clientModDir(),
                in.kitZip(), in.baseZip(), in.argvFile(), in.jreDir(), in.mesaDir(), in.nativeDir(), in.hostFilesDir(),
                in.platform(), in.gitHead(), in.kitJarMd5(), in.stamp(), in.produced(), in.shapesDir(),
                in.modVariantsDir(), in.matchFile());
    }

    /** The client jar: the folded class + one client-only class (fold parity compares only the shared one). */
    private static byte[] jarBytes() throws IOException {
        LinkedHashMap<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("cc/x/Folded.class", FOLDED);
        classes.put("cc/client/OnlyClient.class", ascii("client-only"));
        return PackageZipSelftest.jarWithClasses(classes);
    }

    /** A STANDING sidecar in the mint's shape (CRLF): a comment, the composite naming {@code jarMd5} keyed to
     *  {@link #STANDING}'s id, one {@code file=} row. */
    private static String sidecar(String jarMd5) {
        return "# content-fingerprint sidecar (fixture)\r\nfingerprint="
                + ContentFingerprints.composite(jarMd5, PASSED_MD5, 1, STANDING.matchId()) + "\r\nfile=data/x.csv\r\n";
    }

    /** A KIT's sidecar: {@link #sidecar}'s composite with no match term (a kit carries no match file).
     * */
    private static String kitSidecar(String jarMd5) {
        return "# content-fingerprint sidecar (fixture)\r\nfingerprint=" + ContentFingerprints.stripMatch(
                ContentFingerprints.composite(jarMd5, PASSED_MD5, 1, STANDING.matchId())) + "\r\nfile=data/x.csv\r\n";
    }

    // ---- writer helpers ----

    private static List<String> refusalsOf(Inputs in) {
        try (Plan p = PackageFolderWriter.plan(in)) {
            return p.refusals();
        }
    }

    /**
     * The plan's refusals under the given native pins (the plan's own seam; the CLI pins the real natives).
     *
     */
    private static List<String> refusalsPinned(Inputs in, Map<String, String> pins) {
        try (Plan p = PackageFolderWriter.plan(in, Clock.systemDefaultZone(), pins)) {
            return p.refusals();
        }
    }

    /**
     * The CLI in THIS JVM ({@link PackageFolderWriter#run(String[], PrintStream)}: {@code main} without the
     * exit): its exit code and printed lines. A throw propagates (the caller names it).
     */
    private static Child cliHere(String... args) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int rc;
        try (PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8)) {
            rc = PackageFolderWriter.run(args, ps);
        }
        return new Child(rc, bos.toString(StandardCharsets.UTF_8).lines().toList());
    }

    /** True when a plain read of {@code file} fails (a mandatory lock holds it); false on an OS with advisory locks. */
    private static boolean readBlocked(Path file) {
        try {
            Files.readAllBytes(file);
            return false;
        } catch (IOException lockedOut) {
            return true;
        }
    }

    /** Plans and writes {@code in}, which must be green; a refusal or a failed write is this row's FAIL. */
    private static Result writeGreen(Inputs in, String leg) {
        try (Plan p = PackageFolderWriter.plan(in)) {
            require(p.refusals().isEmpty(), leg + " is refused: " + p.refusals());
            try {
                return PackageFolderWriter.write(p);
            } catch (IOException | IllegalStateException failed) {
                throw new AssertionError(leg + ": the write failed (" + failed.getClass().getSimpleName() + "): "
                        + mask(failed.getMessage()));
            }
        }
    }

    /** {@code write(p)} must refuse (IllegalStateException, nothing written); returns its message. */
    private static String writeRefused(Plan p, String leg) {
        try {
            PackageFolderWriter.write(p);
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        } catch (IOException failed) {
            throw new AssertionError(leg + ": write() began writing (IOException) instead of refusing");
        }
        throw new AssertionError(leg + " was WRITTEN (write() no longer refuses a refused plan)");
    }

    /** {@code write(p)} must fail after its first write (IOException); returns its message. */
    private static String writeFailed(Plan p, String leg) {
        try {
            PackageFolderWriter.write(p);
        } catch (IOException failed) {
            return failed.getMessage();
        } catch (IllegalStateException refused) {
            throw new AssertionError(leg + " was REFUSED before writing (" + mask(refused.getMessage()) + ")");
        }
        throw new AssertionError(leg + " FINISHED (the planted serve.argv folder no longer fails the move)");
    }

    private static void requireEf(String thrown, String leg) {
        requireEf(thrown, leg, "serve.argv");
    }

    /** {@link #requireEf(String, String)} for the route {@code rel} the leg blocked. */
    private static void requireEf(String thrown, String leg, String rel) {
        require(thrown.startsWith(R14_HEAD + rel + ": ") && thrown.endsWith(EF), leg + "'s failure no longer"
                + " throws R14 naming " + rel + " with the EF sentence: " + mask(thrown));
    }

    /** The rel of the plan's last LISTED route (the write order), or null. */
    private static String lastListed(Plan p) {
        String last = null;
        for (PackageFolderWriter.Route r : p.routes()) {
            if (r.listed()) {
                last = r.rel();
            }
        }
        return last;
    }

    private static void expectOnly(String leg, Inputs in, String line) {
        List<String> got = refusalsOf(in);
        require(got.equals(List.of(line)), leg + " alone no longer yields exactly its one parity line through the plan"
                + " (PackageKitZip.parity skipped or its lines mis-mapped): " + got);
    }

    // ---- locks, links, the child JVM ----

    private static Path serveLock(Path pkg) {
        return pkg.resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.LOCK_FILE);
    }

    /** Holds {@code file} the way the launcher and the console do: a CREATE+WRITE channel and {@code tryLock}. */
    private static FileChannel hold(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock won;
        try {
            won = ch.tryLock();
        } catch (IOException | OverlappingFileLockException notWon) {
            ch.close();
            throw new IOException("the fixture lock was not won (" + notWon.getClass().getSimpleName() + ")");
        }
        if (won == null) {
            ch.close();
            throw new IOException("the fixture lock is held elsewhere");
        }
        return ch;
    }

    /** An EXCLUSIVE whole-file lock on an existing file (a READ+WRITE channel). */
    private static FileChannel exclusive(Path file) throws IOException {
        FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock won = ch.tryLock();
        if (won == null) {
            ch.close();
            throw new IOException("the exclusive lock is held elsewhere");
        }
        return ch;
    }

    /** True when a fresh channel wins {@code tryLock} on {@code file} (released at once); false when it is held. */
    private static boolean freeNow(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock won = ch.tryLock();
            if (won == null) {
                return false;
            }
            won.release();
            return true;
        } catch (OverlappingFileLockException heldInThisJvm) {
            return false;
        }
    }

    private static void closeAll(List<FileChannel> channels) {
        for (FileChannel ch : channels) {
            try {
                ch.close();
            } catch (IOException closeFailed) {
                System.out.println("NOTE a fixture lock channel did not close (" + closeFailed.getClass().getSimpleName()
                        + ")");
            }
        }
        channels.clear();
    }

    /** A symbolic link, or a {@link Skip} when the OS refuses one (Windows without the privilege / developer mode). */
    private static void symlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException | UnsupportedOperationException refused) {
            throw new Skip("symlink creation refused by the OS (" + refused.getClass().getSimpleName() + ")");
        } catch (IOException other) {
            throw new SetupFailure("symlink " + other.getClass().getSimpleName() + " " + mask(other.getMessage()));
        }
    }

    /** Runs the writer's {@code main} in a child JVM (this JVM's java + classpath); stdout via a file, never a pipe. */
    private static Child cli(Path dir, String... args) throws Exception {
        Path out = Files.createTempFile(dir, "cli-", ".out");
        Path err = Files.createTempFile(dir, "cli-", ".err");
        String java = Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
                PackageFolderWriter.class.getName()));
        cmd.addAll(Arrays.asList(args));
        Process proc = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            proc.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("the CLI child JVM did not exit within 120 s");
        }
        return new Child(proc.exitValue(), Files.readAllLines(out, StandardCharsets.ISO_8859_1));
    }

    // ---- helpers ----

    /** {@code template} with every Path argument absolute + normalized and masked (as the writer prints a path); other arguments as given. */
    private static String at(String template, Object... args) {
        Object[] shown = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            shown[i] = args[i] instanceof Path p ? mask(norm(p)) : String.valueOf(args[i]);
        }
        return String.format(Locale.ROOT, template, shown);
    }

    private static Path norm(Path p) {
        return p.toAbsolutePath().normalize();
    }

    private static String mask(Object o) {
        return PackageManifest.mask(o == null ? null : o.toString());
    }

    /** Every path under {@code root} (folders too), {@code /}-separated; empty when it does not exist. */
    private static TreeSet<String> listing(Path root) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> all = Files.walk(root)) {
            all.filter(p -> !p.equals(root)).forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/')));
        }
        return out;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void deleteTreeOrThrow(Path root) throws IOException {
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(all::add);
        }
        for (Path p : all) {
            Files.deleteIfExists(p);
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

    /** A fixture that never staged: prints {@code SETUP-<row>}, never counted as a kill. */
    private static final class SetupFailure extends RuntimeException {
        SetupFailure(String message) {
            super(message);
        }
    }

    /** The OS refused a fixture the row needs: prints {@code SKIP <row>}, counted apart, never a PASS. */
    private static final class Skip extends RuntimeException {
        Skip(String message) {
            super(message);
        }
    }

    private static void row(String name, Body body) {
        Path dir;
        try {
            dir = Files.createTempDirectory("cc-pfwg-");
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
                    + mask(unexpected.getMessage()));
            failures++;
        } finally {
            try {
                deleteTreeOrThrow(dir);
            } catch (IOException leftover) {
                System.out.println("NOTE " + name + ": temp fixtures left at " + mask(dir) + " ("
                        + leftover.getClass().getSimpleName() + ")");
            }
        }
    }

    private static <T> T stage(String what, Dial<T> fixture) {
        try {
            return fixture.get();
        } catch (Exception e) {
            throw new SetupFailure(what + ": " + e.getClass().getSimpleName() + " " + mask(e.getMessage()));
        }
    }

    private static void require(boolean ok, String regression) {
        if (!ok) {
            throw new AssertionError(regression);
        }
    }
}
