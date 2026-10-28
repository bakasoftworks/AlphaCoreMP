package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprintMint;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Rows for the packed folder's first-start repair ({@link PackageRepair#repairIfPacked}). Each row builds a
 * synthetic PACKED folder under a {@link Files#createTempDirectory} dir (deleted in {@code finally}): tiny
 * {@code classes/} and {@code jre/} stand-ins, both settings templates, the {@code match-defaults/} standing pair and
 * one shipped shape pair, a kit zip stand-in under {@code kits/}, and a {@code MANIFEST.txt} rendered by
 * {@link PackageManifest#render} with {@code pack.stamp}, {@code git.head}, {@code kit.jar.md5} and
 * {@code pack.kit} (the kit-verdict rows add {@code kit.fingerprint}). A folder is "updated" the way a host updates one: the next release's files are written over it
 * (nothing is deleted, as the operating system's unzip does), then the folder is started again. Every host-facing
 * line a row expects is spelled here, never read from the repair, so a reworded line flips its row; the fixture
 * hashes with its own digest, so a broken hash in the repair cannot agree with itself.
 *
 * <p>Prints {@code PASS <row>} / {@code FAIL <row>: <the regression that flips it>} / {@code SETUP-<row>: ...} (a
 * fixture that never staged, never a kill) / {@code SKIP <row>: ...} (a row this platform cannot stage) and exits 0
 * iff no row failed. Run: {@code java -cp server/classes cc.starsector.server.ops.PackageRepairSelftest}.
 *
 *
 */
public final class PackageRepairSelftest {

    private static int failures;

    private static final String S1 = "20261024-120000";
    private static final String S2 = "20261025-090000";
    private static final String GIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String JAR_V1 = "11111111111111111111111111111111";
    private static final String JAR_V2 = "22222222222222222222222222222222";
    private static final String DATA = "0123456789abcdef0123456789abcdef";
    private static final String DATA_V2 = "fedcba9876543210fedcba9876543210";
    private static final String STD_ID = "std-1v1";
    private static final String DUEL_ID = "duel-1";
    private static final String MINE_ID = "mine-7";
    /** The repair folds rels on Windows only; every row but the case-fold one runs the platform's own choice. */
    private static final boolean FOLD = File.separatorChar == '\\';
    private static final String UPDATE_SCRIPT = FOLD ? "update.bat" : "update.sh";
    /** The pinned time a quiet-start row stamps on every file (2020-01-01T00:00:00Z): a rewrite reads as now. */
    private static final long FIXTURE_TIME_MILLIS = 1_577_836_800_000L;
    private static final String BOM = new String(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
            StandardCharsets.ISO_8859_1);

    private static final String PROPS_V1 = "# the port\nport=7000\n# the tick\ntick=60\nretune=1\ngone=5\n";
    private static final String PROPS_V2 = "# the port\nport=7000\n# the tick\ntick=50\nretune=2\ngone=6\n"
            + "# a new knob\nfresh=yes\n";
    private static final String LAUNCHER_V1 = "heap=4g\ngame.root=\n";
    private static final String LAUNCHER_V2 = "heap=6g\ngame.root=\n# the recorder\njfr=off\n";

    private static final String INSTALLED = "installed " + S1 + ": settings created from the templates";
    private static final String KEPT = "your settings, passwords, bans, kept ships, mods and recordings are kept";
    private static final String BUSY = "another start is repairing this folder";
    private static final String EDITED = ": edited by you, no longer part of the package";
    private static final String LEFT_OVER = ": not part of this package (left over from an older one, or copied in by hand)";
    private static final String NOT_UPDATED = shown("run/update/applied") + " is not updated, so the next start"
            + " repeats this repair";

    /**
     * The link rows: the one file the folder behind a planted link holds (a followed link would expose it to the
     * repair), and the bound on the one {@code mklink /J} child a Windows link row makes (it returns in milliseconds).
     *
     *
     */
    private static final String BEHIND_NAME = "behind.txt";
    private static final String BEHIND_TEXT = "behind the junction";
    private static final long JUNCTION_WAIT_SECONDS = 20;

    private static final PrintStream NULL_OUT = new PrintStream(OutputStream.nullOutputStream());

    private PackageRepairSelftest() {
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Every row, the summary line, the failure count returned (a chaining gate exits 1 on a non-zero count). */
    public static int run() {
        row("pkg-repair-fresh-unpack", PackageRepairSelftest::freshUnpack);
        row("pkg-repair-second-start-quiet", PackageRepairSelftest::secondStartQuiet);
        row("pkg-repair-dropped-rows", PackageRepairSelftest::droppedRows);
        row("pkg-repair-leftover-cheap-path", PackageRepairSelftest::leftoverCheapPath);
        row("pkg-repair-merge-three-way", PackageRepairSelftest::mergeThreeWay);
        row("pkg-repair-merge-old-unknown", PackageRepairSelftest::mergeOldUnknown);
        row("pkg-repair-merge-launcher-reader", PackageRepairSelftest::mergeLauncherReader);
        row("pkg-repair-rekey", PackageRepairSelftest::rekey);
        row("pkg-repair-rekey-kit-unreadable", PackageRepairSelftest::rekeyKitUnreadable);
        row("pkg-repair-kit-data-only", PackageRepairSelftest::kitDataOnly);
        row("pkg-repair-kit-match-only", PackageRepairSelftest::kitMatchOnly);
        row("pkg-repair-kit-no-print-row", PackageRepairSelftest::kitNoPrintRow);
        row("pkg-repair-roster-belt", PackageRepairSelftest::rosterBelt);
        row("pkg-repair-half-pair", PackageRepairSelftest::halfPair);
        row("pkg-repair-not-packed", PackageRepairSelftest::notPacked);
        row("pkg-repair-casefold", PackageRepairSelftest::caseFold);
        row("pkg-repair-lock-busy", PackageRepairSelftest::lockBusy);
        row("pkg-repair-junction-seed-target", PackageRepairSelftest::junctionSeedTarget);
        row("pkg-repair-junction-match-parent", PackageRepairSelftest::junctionMatchParent);
        row("pkg-repair-junction-program-tree", PackageRepairSelftest::junctionProgramTree);
        System.out.println(failures == 0 ? "PackageRepairSelftest: every row PASS"
                : "PackageRepairSelftest: " + failures + " row(s) did not pass");
        return failures;
    }

    // ---- rows ----

    /** A fresh unpack's first start: every seed = its source's bytes, the receipts, the log, the verdict last. */
    private static void freshUnpack(Path dir) throws Exception {
        Release v1 = stage("the first release", () -> release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path pkg = stage("extract the first release", () -> extracted(dir, v1));
        List<String> said = start(pkg);
        require(INSTALLED.equals(last(said)), "a fresh unpack's start does not end in the installed verdict (flips if the"
                + " verdict is dropped, reworded, not printed last, or a fresh folder reads as an update): " + said);
        requireBytes(pkg, "cc-server.properties", v1.files().get("cc-server.template.properties"),
                "cc-server.properties is not seeded from its template");
        requireBytes(pkg, "launcher.properties", v1.files().get("launcher.template.properties"),
                "launcher.properties is not seeded from its template");
        requireBytes(pkg, "serve-mods/enabled_mods.json", ascii("{\"enabledMods\": [\"cc_client\"]}"),
                "serve-mods/enabled_mods.json is not seeded with the mod list");
        for (String name : List.of("match.txt", "match-duel.txt")) {
            for (String half : List.of("", ".fingerprint")) {
                requireBytes(pkg, "match/" + name + half, v1.files().get("match-defaults/" + name + half),
                        "the match pair " + name + " is not seeded from match-defaults");
            }
        }
        require(said.contains("seeded " + shown("match/match-duel.txt") + " and its .fingerprint"), "the shipped shape"
                + " pair's seed is not said (flips if the seed line is dropped or the shape set is not read): " + said);
        require(said.contains("seeded " + shown("cc-server.properties")), "the settings seed is not said: " + said);
        requireBytes(pkg, "run/update/applied/MANIFEST.txt", v1.manifest(),
                "the receipt manifest is not the folder's manifest (flips if the receipts are not written)");
        requireBytes(pkg, "run/update/applied/cc-server.template.properties", ascii(PROPS_V1),
                "the receipt template is not the applied template");
        requireBytes(pkg, "run/update/applied/launcher.template.properties", ascii(LAUNCHER_V1),
                "the receipt launcher template is not the applied template");
        String log = text(pkg, "run/update/update.log");
        require(log != null && log.contains(" repair none -> " + S1 + " ") && log.contains(INSTALLED), "the update log"
                + " lacks the header or the verdict (flips if the log is not written or its header is reworded): "
                + escaped(log));
    }

    /** A second start over an applied folder writes nothing (every byte and time kept) and says nothing. */
    private static void secondStartQuiet(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        stage("pin every file's time", () -> {
            for (Path p : regularFiles(pkg)) {
                Files.setLastModifiedTime(p, FileTime.fromMillis(FIXTURE_TIME_MILLIS));
            }
            return null;
        });
        Map<String, String> before = stage("snapshot", () -> snapshot(pkg));
        List<String> said = start(pkg);
        require(said.isEmpty(), "a second start with nothing to do said something (flips if the receipt compare fails,"
                + " the cheap path re-seeds or merges, or prints a verdict): " + said);
        Map<String, String> after = snapshot(pkg);
        require(after.equals(before), "a second start wrote a file (flips if the cheap path rewrites a seed, a receipt"
                + " or the log): " + changed(before, after));
    }

    /** The next release drops rows: the unedited ones go, an edited one outside the trees stays, inside moves. */
    private static void droppedRows(Path dir) throws Exception {
        LinkedHashMap<String, byte[]> f1 = files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1);
        f1.put("classes/cc/Dropped.class", ascii("dropped class"));
        f1.put("classes/cc/Edited.class", ascii("shipped class"));
        f1.put("stop.bat", ascii("@echo stop\r\n"));
        f1.put("extras/extra.txt", ascii("not a program file of this layout"));
        Path pkg = installed(dir, release(S1, JAR_V1, f1));
        String hostKit = "kits/cc-joiner-kit-20261024-130000-mine.zip";
        stage("host edits + a host kit", () -> {
            write(pkg, "classes/cc/Edited.class", ascii("edited by the host"));
            write(pkg, "stop.bat", ascii("@echo my own stop\r\n"));
            write(pkg, hostKit, ascii("a kit the host made"));
            return null;
        });
        LinkedHashMap<String, byte[]> f2 = files(S2, JAR_V1, PROPS_V1, LAUNCHER_V1);
        f2.put("classes/cc/B.class", ascii("class B"));
        Release v2 = stage("the next release", () -> release(S2, JAR_V1, f2));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        List<String> said = start(pkg);
        require(!Files.exists(pkg.resolve("classes/cc/Dropped.class"))
                && said.contains("removed " + shown("classes/cc/Dropped.class") + ": no longer part of the package"),
                "a dropped program file holding the old bytes is not removed with its line (flips if dropped rows are"
                        + " not walked or the sha compare is inverted): " + said);
        require(!Files.exists(pkg.resolve("kits/" + kitName(S1)))
                && said.contains("removed " + shown("kits/" + kitName(S1)) + ": no longer part of the package"),
                "the old release's kit is not removed (flips if kits/<old pack.kit> is no longer a program file): " + said);
        requireBytes(pkg, "stop.bat", ascii("@echo my own stop\r\n"), "an edited dropped file outside the program"
                + " trees is not kept as the host left it (flips if an edited row is deleted or moved)");
        require(said.contains("kept " + shown("stop.bat") + EDITED), "the kept line of an edited dropped file is not"
                + " said: " + said);
        require(!Files.exists(pkg.resolve("classes/cc/Edited.class")), "an edited dropped file inside a program tree"
                + " was left in the tree (flips if it is kept instead of moved to run/stale)");
        requireBytes(pkg, "run/stale/classes/cc/Edited.class", ascii("edited by the host"), "an edited dropped file"
                + " inside a program tree is not moved to run/stale with its bytes (flips if it is deleted)");
        require(said.contains("moved " + shown("classes/cc/Edited.class") + " to " + shown("run/stale") + EDITED),
                "the moved line of an edited dropped program-tree file is not said: " + said);
        require(Files.exists(pkg.resolve("extras/extra.txt")), "a dropped row outside the program set was removed (flips"
                + " if the program-set guard of the dropped walk is gone)");
        require(Files.exists(pkg.resolve(hostKit)), "a host-made kit under kits/ was removed or moved (flips if kits/"
                + " becomes a program tree)");
        String verdict = "updated to " + S2 + " (from " + S1 + "); 1 files replaced, 2 added, 5 removed; " + KEPT
                + " (0 new settings rows added)";
        require(verdict.equals(last(said)), "the update verdict is not the manifests' diff (flips if a count is taken"
                + " from the wrong side, a kit line rides an unchanged kit, or the verdict is not last): " + said);
        requireBytes(pkg, "run/update/applied/MANIFEST.txt", v2.manifest(), "the receipt is not the new manifest");
    }

    /** A file planted in a program tree on the cheap path is moved under run/stale (never replacing an older copy). */
    private static void leftoverCheapPath(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        stage("plant files", () -> {
            write(pkg, "classes/cc/Planted.class", ascii("planted one"));
            write(pkg, "jre/lib/extra.dll", ascii("planted dll"));
            write(pkg, "notes.txt", ascii("the host's notes"));
            write(pkg, "match/notes.md", ascii("the host's match notes"));
            return null;
        });
        List<String> said = start(pkg);
        String movedClass = "moved " + shown("classes/cc/Planted.class") + " to " + shown("run/stale") + LEFT_OVER;
        String movedDll = "moved " + shown("jre/lib/extra.dll") + " to " + shown("run/stale") + LEFT_OVER;
        require(said.equals(List.of(movedClass, movedDll)), "the cheap path's leftover walk did not move exactly the two"
                + " planted program-tree files, with no verdict (flips if the walk runs only on an update, misses a"
                + " tree, or the cheap path prints a verdict): " + said);
        require(!Files.exists(pkg.resolve("classes/cc/Planted.class")), "a planted class stayed in classes/");
        requireBytes(pkg, "run/stale/classes/cc/Planted.class", ascii("planted one"), "a planted class is not"
                + " quarantined with its bytes (flips if a leftover is deleted)");
        requireBytes(pkg, "run/stale/jre/lib/extra.dll", ascii("planted dll"), "a planted jre file is not quarantined");
        requireBytes(pkg, "notes.txt", ascii("the host's notes"), "a host file outside the program trees was touched");
        requireBytes(pkg, "match/notes.md", ascii("the host's match notes"), "a host file under match/ was touched");
        String log = text(pkg, "run/update/update.log");
        require(log != null && log.contains(movedClass), "the move is not in the update log (flips if the cheap path's"
                + " lines are not logged)");
        stage("plant the same name again", () -> write(pkg, "classes/cc/Planted.class", ascii("planted two")));
        start(pkg);
        requireBytes(pkg, "run/stale/classes/cc/Planted.class", ascii("planted one"), "a second quarantine of the same"
                + " name replaced the older copy (flips if run/stale is written over)");
        requireBytes(pkg, "run/stale/classes/cc/Planted.class.1", ascii("planted two"), "a second quarantine of the"
                + " same name is not kept beside the first as .1");
    }

    /** The three-way merge of cc-server.properties under its consumer's reader, then its idempotence. */
    private static void mergeThreeWay(Path dir) throws Exception {
        Release v1 = stage("the first release", () -> release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path pkg = installed(dir, v1);
        // the host re-saved the file in a Windows editor (CRLF), tuned tick and deleted gone
        String host = "# the port\r\nport=7000\r\n# the tick\r\ntick=30\r\nretune=1\r\n";
        stage("the host edits its settings", () -> write(pkg, "cc-server.properties", ascii(host)));
        Release v2 = stage("the next release", () -> release(S2, JAR_V1, files(S2, JAR_V1, PROPS_V2, LAUNCHER_V1)));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        List<String> said = start(pkg);
        String want = "# the port\r\nport=7000\r\n# the tick\r\ntick=30\r\nretune=2\r\n\r\n" + addedHeader(S2)
                + "\r\n# a new knob\r\nfresh=yes\r\n";
        requireBytes(pkg, "cc-server.properties", ascii(want), "the merged settings are not the host file with retune"
                + " re-tuned in place (CRLF kept) and fresh appended once under the dated comment (flips if a"
                + " host-changed row is rewritten, a host-removed key comes back, the line break is not the host's, or"
                + " the new key is not appended)");
        for (String line : List.of("settings: retune re-tuned to this version's default (cc-server.properties)",
                "settings: tick kept at your value; this version ships another default (cc-server.properties)",
                "settings: gone stays removed (cc-server.properties)",
                "settings: fresh added at this version's default (cc-server.properties)")) {
            require(said.contains(line), "the merge line '" + line + "' is not said (flips if that merge case is"
                    + " decided otherwise or its line is reworded): " + said);
        }
        require(last(said) != null && last(said).endsWith("(1 new settings rows added)"), "the verdict does not count"
                + " the one appended row: " + said);
        requireBytes(pkg, "run/update/applied/cc-server.template.properties", ascii(PROPS_V2), "the receipt template"
                + " is not the new template (flips if the receipts keep the old one)");
        List<String> again = start(pkg);
        require(again.isEmpty(), "a re-run over the merged folder said something: " + again);
        requireBytes(pkg, "cc-server.properties", ascii(want), "a re-run changed the merged settings");
        // a start that died after the merge but before the receipts: the full repair runs again over the merged file
        stage("roll the receipts back", () -> {
            write(pkg, "run/update/applied/MANIFEST.txt", v1.manifest());
            write(pkg, "run/update/applied/cc-server.template.properties", ascii(PROPS_V1));
            return null;
        });
        List<String> replay = start(pkg);
        requireBytes(pkg, "cc-server.properties", ascii(want), "a repeated full repair changed the merged settings"
                + " (flips if the merge appends a key the host file already has, or re-tunes an already-shipped value)");
        require(replay.stream().noneMatch(l -> l.contains(" added at this version's default")), "a repeated full"
                + " repair appended again: " + replay);
    }

    /** A folder with settings but no receipts (from before the repair existed): absent keys appended, none rewritten. */
    private static void mergeOldUnknown(Path dir) throws Exception {
        Release v2 = stage("a release", () -> release(S2, JAR_V1, files(S2, JAR_V1, PROPS_V2, LAUNCHER_V2)));
        Path pkg = stage("extract it over an older folder", () -> extracted(dir, v2));
        String host = "port=7000\nretune=1\n";
        stage("the older folder's settings", () -> {
            write(pkg, "cc-server.properties", ascii(host));
            write(pkg, "launcher.properties", ascii(LAUNCHER_V2));
            return null;
        });
        List<String> said = start(pkg);
        String want = host + "\n" + addedHeader(S2) + "\n# the tick\ntick=50\ngone=6\n# a new knob\nfresh=yes\n";
        requireBytes(pkg, "cc-server.properties", ascii(want), "with no receipt the merge did not append exactly the"
                + " absent keys, leaving retune at the host's value (flips if an unknown old template is read as the"
                + " host's or the new one, so a host value is rewritten or a key is dropped)");
        require(said.stream().noneMatch(l -> l.contains(" re-tuned ")), "a row was re-tuned with no old template: "
                + said);
        String verdict = "updated to " + S2 + " (from an older package); " + KEPT + " (3 new settings rows added);"
                + " players need the new kit: " + shown("kits/" + kitName(S2));
        require(verdict.equals(last(said)), "an older folder's verdict is not the from-an-older-package line (flips if"
                + " existing settings with no receipt read as a fresh unpack): " + said);
    }

    /** launcher.properties merges as the launcher reads it: a BOM ignored, the FIRST '=' splits, no escapes. */
    private static void mergeLauncherReader(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        String host = BOM + "heap=4g\r\ngame.root=C:\\Games\\Star=sector\r\n";
        stage("the host edits launcher.properties", () -> write(pkg, "launcher.properties",
                host.getBytes(StandardCharsets.ISO_8859_1)));
        Release v2 = stage("the next release", () -> release(S2, JAR_V1, files(S2, JAR_V1, PROPS_V1, LAUNCHER_V2)));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        List<String> said = start(pkg);
        String want = BOM + "heap=6g\r\ngame.root=C:\\Games\\Star=sector\r\n\r\n" + addedHeader(S2)
                + "\r\n# the recorder\r\njfr=off\r\n";
        requireBytes(pkg, "launcher.properties", want.getBytes(StandardCharsets.ISO_8859_1), "launcher.properties is"
                + " not merged as the launcher reads it (flips if the merge reads it with Properties - the backslash"
                + " row then reads differently and nothing merges - or the BOM rides the first key, so heap is"
                + " appended again instead of re-tuned)");
        Map<String, String> rows = ServeLauncher.readLauncherRows(pkg.resolve("launcher.properties"), NULL_OUT);
        require(rows.equals(Map.of("heap", "6g", "game.root", "C:\\Games\\Star=sector", "jfr", "off")), "the"
                + " launcher's own read of the merged file is not the expected three rows: " + rows);
        require(said.contains("settings: heap re-tuned to this version's default (launcher.properties)"), "the heap"
                + " re-tune line is not said: " + said);
        require(said.stream().noneMatch(l -> l.contains("reads differently")), "the merge refused a file the launcher"
                + " reads fine: " + said);
    }

    /** Every host match sidecar moves to the new kit with its own match term; one that cannot is named, untouched. */
    private static void rekey(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        byte[] noMatch = ContentFingerprintMint.sidecarBytes(List.of("# made by hand", "fingerprint="
                + ContentFingerprints.stripMatch(ContentFingerprints.composite(JAR_V1, DATA, 1, "x"))));
        stage("the host's own shapes", () -> {
            write(pkg, "match/match-mine.txt", ascii(matchTxt()));
            write(pkg, "match/match-mine.txt.fingerprint", sidecar(JAR_V1, MINE_ID));
            write(pkg, "match/match-nomatch.txt", ascii(matchTxt()));
            write(pkg, "match/match-nomatch.txt.fingerprint", noMatch);
            return null;
        });
        Release v2 = stage("the next release (a new kit)", () -> release(S2, JAR_V2,
                files(S2, JAR_V2, PROPS_V1, LAUNCHER_V1)));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        List<String> said = start(pkg);
        requireBytes(pkg, "match/match.txt.fingerprint", v2.files().get("match-defaults/match.txt.fingerprint"),
                "the standing sidecar is not re-keyed to the new standing default (flips if the re-key is skipped or"
                        + " keeps the old kit's lines)");
        Map<String, String> terms = new TreeMap<>(Map.of("match/match.txt.fingerprint", STD_ID,
                "match/match-duel.txt.fingerprint", DUEL_ID, "match/match-mine.txt.fingerprint", MINE_ID));
        for (Map.Entry<String, String> e : terms.entrySet()) {
            byte[] b = Files.readAllBytes(pkg.resolve(e.getKey()));
            String fp = ContentFingerprints.parseSidecarFingerprint(PackageRepair.sidecarLines(b), "a fixture sidecar");
            require(JAR_V2.equals(ContentFingerprints.componentOrNull(fp, "jar"))
                    && e.getValue().equals(ContentFingerprints.componentOrNull(fp, "match")), e.getKey() + " is not on"
                    + " the new kit's jar with its own match kept (flips if a host sidecar is not re-keyed, or the"
                    + " re-key takes the kit's match term): " + fp);
        }
        require(said.contains("re-keyed " + shown("match/match-mine.txt.fingerprint") + " to this version's kit"),
                "the re-key line of a host shape is not said: " + said);
        requireBytes(pkg, "match/match-nomatch.txt.fingerprint", noMatch, "a sidecar with no match term was written"
                + " (flips if the re-key invents a match term)");
        String cannot = shown("match/match-nomatch.txt.fingerprint") + " could not be moved to this version's kit -"
                + " delete " + shown("match/match-nomatch.txt") + " and its .fingerprint and start again (a shipped"
                + " shape is copied back; a shape you made: make it again with New match shape)";
        String verdict = last(said);
        require(verdict != null && verdict.endsWith("; " + cannot), "the verdict does not end in the cannot-move line"
                + " (flips if a CANNOT re-key is silent or not a verdict tail): " + verdict);
        require(verdict.contains("; players need the new kit: " + shown("kits/" + kitName(S2)) + ";"), "the verdict"
                + " does not name the new kit when kit.jar.md5 changed: " + verdict);
        requireBytes(pkg, "match/match.txt", ascii(matchTxt()), "the re-key rewrote the match file itself");
    }

    /** The folder's standing default is torn: no re-key, the remedy named, the receipts kept for the next start. */
    private static void rekeyKitUnreadable(Path dir) throws Exception {
        Release v1 = stage("the first release", () -> release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path pkg = installed(dir, v1);
        Release v2 = stage("the next release (a new kit)", () -> release(S2, JAR_V2,
                files(S2, JAR_V2, PROPS_V1, LAUNCHER_V1)));
        stage("extract the next release, the standing default torn", () -> {
            extractOver(pkg, v2);
            write(pkg, "match-defaults/match.txt.fingerprint", ascii("torn"));
            return null;
        });
        List<String> said = start(pkg);
        requireBytes(pkg, "match/match.txt.fingerprint", v1.files().get("match-defaults/match.txt.fingerprint"),
                "a host sidecar was re-keyed against a standing default that does not match its row");
        String tail = shown("match-defaults/match.txt.fingerprint") + " cannot be read, so your match files were not"
                + " moved to this version's kit - run " + UPDATE_SCRIPT + " again with the zip you downloaded";
        require(last(said) != null && last(said).endsWith("; " + tail), "the verdict does not name the torn standing"
                + " default (flips if a kit-side null re-key is silent): " + said);
        require(said.contains(NOT_UPDATED), "a repair that could not finish did not say the receipts stay: " + said);
        requireBytes(pkg, "run/update/applied/MANIFEST.txt", v1.manifest(), "the receipt moved on although the"
                + " re-key did not run (flips if a retry does not hold the receipts back)");
        stage("the host re-runs the extract", () -> write(pkg, "match-defaults/match.txt.fingerprint",
                v2.files().get("match-defaults/match.txt.fingerprint")));
        start(pkg);
        requireBytes(pkg, "match/match.txt.fingerprint", v2.files().get("match-defaults/match.txt.fingerprint"),
                "the next start did not finish the re-key (flips if the full repair is not repeated)");
        requireBytes(pkg, "run/update/applied/MANIFEST.txt", v2.manifest(), "the receipt is not the new manifest"
                + " after the repeated repair");
    }

    /**
     * The kit line keys on what the join door compares (the kit jar md5, and the kit fingerprint with its match term
     * stripped): the next release changes only the kit's DATA (the jar md5 the same), and the verdict still asks
     * players for the new kit, because the door turns every old kit away.
     *
     */
    private static void kitDataOnly(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, kitPrint(JAR_V1, DATA, STD_ID),
                files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Release v2 = stage("the next release (only the kit's data changed)", () -> release(S2, JAR_V1,
                kitPrint(JAR_V1, DATA_V2, STD_ID), files(S2, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        List<String> said = start(pkg);
        String verdict = plainUpdate(1) + newKitLine(S2);
        require(verdict.equals(last(said)), "a data-only kit change did not ask players for the new kit (flips if the"
                + " verdict's kit line keys on kit.jar.md5 alone, so the host is never told while the door refuses"
                + " every old kit): " + said);
    }

    /** Two releases whose kit fingerprints differ only in the match term name the same kit: no kit line. */
    private static void kitMatchOnly(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, kitPrint(JAR_V1, DATA, STD_ID),
                files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Release v2 = stage("the next release (only the kit's match term changed)", () -> release(S2, JAR_V1,
                kitPrint(JAR_V1, DATA, "std-2v2"), files(S2, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        List<String> said = start(pkg);
        require(plainUpdate(1).equals(last(said)), "a match-term-only fingerprint change asked players for a new kit"
                + " (flips if the kit compare keeps the ;match= term, which the door strips - it accepts that kit):"
                + " " + said);
    }

    /** An older folder's manifest has no kit.fingerprint row: the jar md5 alone still decides the kit line. */
    private static void kitNoPrintRow(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Release v2 = stage("the next release (a new kit jar, the fingerprint row new)", () -> release(S2, JAR_V2,
                kitPrint(JAR_V2, DATA, STD_ID), files(S2, JAR_V2, PROPS_V1, LAUNCHER_V1)));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        List<String> said = start(pkg);
        String verdict = plainUpdate(3) + newKitLine(S2);
        require(verdict.equals(last(said)), "a new kit jar under a manifest with no kit.fingerprint row did not ask"
                + " players for the new kit (flips if the jar md5 compare is dropped when a fingerprint row is"
                + " absent): " + said);
    }

    /** A host match file fielding a mod variant the new kit lacks is named; one the kit carries is not. */
    private static void rosterBelt(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        String belt = stage("a match fielding cc_belt_Hull", () -> fielding("cc_belt_Hull"));
        String kept = stage("a match fielding cc_kept_Hull", () -> fielding("cc_kept_Hull"));
        stage("the host's own shapes", () -> {
            write(pkg, "match/match-mine.txt", ascii(belt));
            write(pkg, "match/match-mine.txt.fingerprint", sidecar(JAR_V1, MINE_ID));
            write(pkg, "match/match-keep.txt", ascii(kept));
            write(pkg, "match/match-keep.txt.fingerprint", sidecar(JAR_V1, "keep-1"));
            return null;
        });
        LinkedHashMap<String, byte[]> f2 = files(S2, JAR_V2, PROPS_V1, LAUNCHER_V1);
        f2.put("serve-mods/CC_Client/data/variants/cc_belt_Hull.variant", ascii("{\"variantId\": \"cc_belt_Hull\"}"));
        f2.put("serve-mods/CC_Client/data/variants/cc_kept_Hull.variant", ascii("{\"variantId\": \"cc_kept_Hull\"}"));
        Release v2 = stage("the next release", () -> release(S2, JAR_V2, f2));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        List<String> said = start(pkg);
        require(said.contains(shown("match/match-mine.txt") + " fields cc_belt_Hull, which this version's kit does not"
                + " carry"), "the roster belt does not name a match fielding a variant the new kit lacks (flips if the"
                + " belt is not run or reads the old kit): " + said);
        require(said.stream().noneMatch(l -> l.contains("cc_kept_Hull")), "the roster belt named a variant the kit"
                + " carries (flips if the kit's file= rows are not read): " + said);
    }

    /** A match pair with one half present is never completed by a seed: no write, the remedy line. */
    private static void halfPair(Path dir) throws Exception {
        Release v1 = stage("the first release", () -> release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path pkg = stage("extract the first release", () -> extracted(dir, v1));
        stage("one half of each pair", () -> {
            write(pkg, "match/match.txt", ascii("the host's match"));
            write(pkg, "match/match-duel.txt.fingerprint", v1.files().get("match-defaults/match-duel.txt.fingerprint"));
            return null;
        });
        List<String> said = start(pkg);
        requireBytes(pkg, "match/match.txt", ascii("the host's match"), "a half pair's present half was written");
        require(!Files.exists(pkg.resolve("match/match.txt.fingerprint")) && !Files.exists(pkg.resolve(
                "match/match-duel.txt")), "a half pair was completed by a seed (flips if a pair is seeded when only one"
                + " half is absent)");
        for (String txt : List.of("match/match.txt", "match/match-duel.txt")) {
            String line = shown(txt) + ": its pair is incomplete - press Apply on it (a missing .fingerprint), or"
                    + " delete the other file and start again (a shipped shape is copied back)";
            require(said.contains(line), "the half-pair line of " + txt + " is not said: " + said);
        }
        require(Files.exists(pkg.resolve("cc-server.properties")) && INSTALLED.equals(last(said)), "the repair did not"
                + " go on past a half pair: " + said);
    }

    /** A source-written folder, a folder with no MANIFEST.txt and one with a malformed one are left alone. */
    private static void notPacked(Path dir) throws Exception {
        Release source = stage("a source-stamped release", () -> release("source-" + S1, JAR_V1,
                files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path pkg = stage("extract it", () -> extracted(dir, source));
        List<String> said = start(pkg);
        require(said.isEmpty() && !Files.exists(pkg.resolve("cc-server.properties")) && !Files.exists(pkg.resolve("run")),
                "a source- stamp was repaired (flips if the release-stamp test is gone): " + said);
        stage("remove the manifest", () -> {
            Files.delete(pkg.resolve("MANIFEST.txt"));
            return null;
        });
        said = start(pkg);
        require(said.isEmpty() && !Files.exists(pkg.resolve("cc-server.properties")) && !Files.exists(pkg.resolve("run")),
                "a folder with no MANIFEST.txt was repaired: " + said);
        stage("a malformed manifest", () -> write(pkg, "MANIFEST.txt", ascii("pack.stamp=" + S1 + "\nnot a row\n")));
        said = start(pkg);
        require(said.isEmpty() && !Files.exists(pkg.resolve("run")), "a malformed MANIFEST.txt was repaired (flips if a"
                + " manifest parse failure reads as packed): " + said);
    }

    /** Windows: a case-only renamed program file (the disk keeps the old case) is neither removed nor quarantined. */
    private static void caseFold(Path dir) throws Exception {
        if (!FOLD) {
            throw new Skipped("case-only renames fold only on Windows (this file system keeps both names apart)");
        }
        LinkedHashMap<String, byte[]> f1 = files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1);
        f1.put("classes/cc/Same.class", ascii("same bytes"));
        f1.put("classes/cc/Diff.class", ascii("diff bytes v1"));
        Path pkg = installed(dir, release(S1, JAR_V1, f1));
        LinkedHashMap<String, byte[]> f2 = files(S2, JAR_V1, PROPS_V1, LAUNCHER_V1);
        f2.put("classes/cc/same.class", ascii("same bytes"));
        f2.put("classes/cc/diff.class", ascii("diff bytes v2"));
        Release v2 = stage("the next release", () -> release(S2, JAR_V1, f2));
        stage("extract the next release over the folder", () -> extractOver(pkg, v2));
        Set<String> names = stage("list classes/cc", () -> names(pkg.resolve("classes/cc")));
        if (!names.containsAll(List.of("Same.class", "Diff.class")) || names.contains("same.class")
                || names.contains("diff.class")) {
            throw new SetupFailure("the folder does not keep the old case on an overwrite: " + names);
        }
        List<String> said = PackageRepair.repairIfPacked(pkg, null);
        require(names(pkg.resolve("classes/cc")).containsAll(List.of("Same.class", "Diff.class")), "a case-only"
                + " renamed program file is gone (flips if the dropped walk or the leftover walk compares case)");
        requireBytes(pkg, "classes/cc/Diff.class", ascii("diff bytes v2"), "a case-only renamed file lost the new bytes");
        require(!Files.exists(pkg.resolve("run/stale")), "a case-only renamed program file was quarantined (flips if"
                + " the leftover walk or the dropped walk compares case on Windows)");
        require(said.stream().noneMatch(l -> (l.startsWith("removed ") || l.startsWith("moved "))
                && (l.toLowerCase(Locale.ROOT).contains("same.class") || l.toLowerCase(Locale.ROOT).contains("diff.class"))),
                "a case-only rename said a removal or a move of the renamed file: " + said);
        require(said.contains("removed " + shown("kits/" + kitName(S1)) + ": no longer part of the package"),
                "the case-folded dropped walk no longer removes a genuinely dropped row: " + said);
    }

    /** A held repair lock: a second start with no wait skips with the line; a released one inside the wait repairs. */
    private static void lockBusy(Path dir) throws Exception {
        Release v1 = stage("the first release", () -> release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path pkg = stage("extract the first release", () -> extracted(dir, v1));
        Path lockFile = pkg.resolve("run/update/repair.lock");
        FileChannel ch = stage("open the repair lock", () -> {
            Files.createDirectories(lockFile.getParent());
            return FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        });
        try {
            FileLock held = stage("hold the repair lock", ch::lock);
            List<String> said = start(pkg);
            require(said.equals(List.of(BUSY)), "a start under a held repair lock did not skip with exactly the busy"
                    + " line (flips if the lock is not taken, or a same-JVM holder is not waited for): " + said);
            require(!Files.exists(pkg.resolve("cc-server.properties")), "a start under a held repair lock seeded");
            AtomicReference<Throwable> releaseFailed = new AtomicReference<>();
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(150L);
                    held.release();
                } catch (InterruptedException | IOException e) {
                    releaseFailed.set(e); // the SETUP line below names it
                }
            }, "pkg-repair-lock-releaser");
            releaser.setDaemon(true);
            releaser.start();
            List<String> waited = PackageRepair.repair(pkg, null, FOLD, 5_000L);
            releaser.join(10_000L);
            if (releaseFailed.get() != null) {
                throw new SetupFailure("the lock was not released (" + releaseFailed.get().getClass().getSimpleName() + ")");
            }
            require(INSTALLED.equals(last(waited)), "a start whose lock frees inside its wait did not repair (flips if"
                    + " the lock is tried once instead of polled): " + waited);
        } finally {
            ch.close();
        }
    }

    /**
     * A junction at the standing pair's seed target on a fresh unpack: nothing is seeded or moved through it, the
     * link stays, neither half of that pair is created, the pair's link line is said, the other shipped pair is
     * still seeded and the repair goes on to its verdict.
     *
     *
     */
    private static void junctionSeedTarget(Path dir) throws Exception {
        Release v1 = stage("the first release", () -> release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path pkg = stage("extract the first release", () -> extracted(dir, v1));
        Path link = pkg.resolve("match/match.txt");
        stage("the match folder", () -> Files.createDirectories(link.getParent()));
        Path behind = behind();
        try {
            dirLinkOrSkip(link, behind);
            List<String> said = start(pkg);
            requireUntouched(behind, "a seed wrote or moved a file into the folder a junction at its target points at"
                    + " (flips if seedPair writes before safe() vets its targets)");
            require(PackageFolderWriter.isLink(link), "the junction at the seed target was replaced (flips if a seed"
                    + " moves its file over a link, or safe() stops reading a junction as a link)");
            require(!Files.exists(pkg.resolve("match/match.txt.fingerprint")), "the .fingerprint half was created beside"
                    + " a linked .txt target (flips if seedPair writes its .fingerprint before both targets pass safe(),"
                    + " or safe() stops reading a junction as a link)");
            require(said.contains(shown("match/match.txt") + ": a link (or an odd name) on its path - its pair is not"
                    + " created"), "the linked pair's line is not said (flips if seedPair stops vetting its targets"
                    + " with safe(), or the line is reworded): " + said);
            for (String half : List.of("", ".fingerprint")) {
                requireBytes(pkg, "match/match-duel.txt" + half, v1.files().get("match-defaults/match-duel.txt" + half),
                        "a link at one pair's target stopped the other shipped pair's seed (flips if a linked pair"
                                + " ends the seeds instead of only its own pair)");
            }
            require(INSTALLED.equals(last(said)), "the repair did not go on past a linked seed target (flips if a link"
                    + " line aborts the repair): " + said);
        } finally {
            unlinkFirst("pkg-repair-junction-seed-target", link, behind);
        }
    }

    /**
     * A junction at {@code match} itself on a fresh unpack: no pair is written through it, the match-folder line is
     * said exactly once for both shipped pairs, and the repair goes on to its verdict.
     *
     *
     */
    private static void junctionMatchParent(Path dir) throws Exception {
        Release v1 = stage("the first release", () -> release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path pkg = stage("extract the first release", () -> extracted(dir, v1));
        Path link = pkg.resolve("match");
        if (Files.exists(link)) {
            throw new SetupFailure("the fresh unpack already holds a match folder");
        }
        Path behind = behind();
        try {
            dirLinkOrSkip(link, behind);
            List<String> said = start(pkg);
            requireUntouched(behind, "a match pair was written through a junction at match (flips if seedPair drops its"
                    + " match-folder check AND safe() stops checking a target's parents)");
            require(PackageFolderWriter.isLink(link), "the junction at match was replaced (flips if a seed or the"
                    + " re-key writes over a linked match folder)");
            String line = shown("match") + ": a link (or an odd name) on its path - no match file is created";
            long times = said.stream().filter(line::equals).count();
            require(times == 1, "the linked match folder's line is not said exactly once (flips if seedPair drops its"
                    + " match-folder check, or the said-once latch is lost so every pair repeats it): " + times
                    + " in " + said);
            require(INSTALLED.equals(last(said)), "the repair did not go on past a linked match folder (flips if a"
                    + " link line aborts the repair): " + said);
        } finally {
            unlinkFirst("pkg-repair-junction-match-parent", link, behind);
        }
    }

    /**
     * A junction inside a program tree on the cheap path (an installed folder started again): the leftover walk moves
     * the real leftover planted beside it and never descends the junction, so the file behind it stays in place.
     *
     *
     */
    private static void junctionProgramTree(Path dir) throws Exception {
        Path pkg = installed(dir, release(S1, JAR_V1, files(S1, JAR_V1, PROPS_V1, LAUNCHER_V1)));
        Path link = pkg.resolve("classes/sub");
        stage("plant a leftover beside the junction", () -> write(pkg, "classes/cc/Planted.class", ascii("planted one")));
        Path behind = behind();
        try {
            dirLinkOrSkip(link, behind);
            List<String> said = start(pkg);
            requireUntouched(behind, "the leftover walk moved a file out of the folder behind a junction in a program"
                    + " tree (flips if the walk descends a junction AND the move stops vetting its source with safe())");
            require(PackageFolderWriter.isLink(link), "the junction in a program tree was moved or replaced (flips if"
                    + " the walk reports a junction as a leftover file)");
            require(said.stream().noneMatch(l -> l.contains(shown("classes/sub"))), "a line names the junction or a"
                    + " file behind it (flips if the walk descends a junction or reports it as a file): " + said);
            String moved = "moved " + shown("classes/cc/Planted.class") + " to " + shown("run/stale") + LEFT_OVER;
            require(said.equals(List.of(moved)), "the cheap path's walk over a tree holding a junction did not move"
                    + " exactly the leftover planted beside it (flips if a junction aborts the walk over its tree, or"
                    + " the cheap path prints a verdict): " + said);
            Path staleDir = pkg.resolve("run/stale");
            List<String> stale = new ArrayList<>();
            if (Files.isDirectory(staleDir)) {
                for (Path p : regularFiles(staleDir)) {
                    stale.add(staleDir.relativize(p).toString().replace('\\', '/'));
                }
            }
            require(stale.equals(List.of("classes/cc/Planted.class")), "run/stale holds more or less than the planted"
                    + " leftover (flips if a file behind a junction is quarantined): " + stale);
        } finally {
            unlinkFirst("pkg-repair-junction-program-tree", link, behind);
        }
    }

    // ---- the fixture ----

    /** One release: its program files and the {@code MANIFEST.txt} bytes listing them. */
    private record Release(LinkedHashMap<String, byte[]> files, byte[] manifest) {
    }

    private static Release release(String stamp, String jarMd5, Map<String, byte[]> files) {
        return release(stamp, jarMd5, null, files);
    }

    /**
     * {@link #release} with a {@code kit.fingerprint} row ({@code kitPrintOrNull} null = a manifest older than that
     * row, the shape every other row runs).
     */
    private static Release release(String stamp, String jarMd5, String kitPrintOrNull, Map<String, byte[]> files) {
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("git.head", GIT);
        meta.put("kit.jar.md5", jarMd5);
        if (kitPrintOrNull != null) {
            meta.put("kit.fingerprint", kitPrintOrNull);
        }
        meta.put("pack.stamp", stamp);
        meta.put("pack.kit", kitName(stamp));
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>(files);
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : copy.entrySet()) {
            rows.put(e.getKey(), sha256Hex(e.getValue()));
        }
        return new Release(copy, PackageManifest.render(meta, rows));
    }

    private static String kitName(String stamp) {
        return "cc-kit-" + stamp + ".zip";
    }

    /**
     * The kit rows' fixture strings: a kit fingerprint in the packer's full-composite shape (its match term kept, as
     * the package manifest carries it); the update verdict from {@link #S1} to {@link #S2} over {@link #files} with
     * {@code replaced} files replaced (the stamped class, plus the two standing sidecars on a new jar), the old kit
     * removed and the new one added; and the kit line it ends in when players need the new kit.
     *
     */
    private static String kitPrint(String jarMd5, String dataMd5, String matchId) {
        return ContentFingerprints.composite(jarMd5, dataMd5, 1, matchId);
    }

    private static String plainUpdate(int replaced) {
        return "updated to " + S2 + " (from " + S1 + "); " + replaced + " files replaced, 1 added, 1 removed; " + KEPT
                + " (0 new settings rows added)";
    }

    private static String newKitLine(String stamp) {
        return "; players need the new kit: " + shown("kits/" + kitName(stamp));
    }

    /** A release's program files: {@code classes/cc/A.class} differs per stamp, the kit zip is named after it. */
    private static LinkedHashMap<String, byte[]> files(String stamp, String jarMd5, String props, String launcher) {
        LinkedHashMap<String, byte[]> f = new LinkedHashMap<>();
        f.put("classes/cc/A.class", ascii("class A " + stamp));
        f.put("jre/bin/java.exe", ascii("java-exe"));
        f.put("serve.bat", ascii("@echo serve\r\n"));
        f.put("cc-server.template.properties", ascii(props));
        f.put("launcher.template.properties", ascii(launcher));
        f.put("match-defaults/match.txt", ascii(matchTxt()));
        f.put("match-defaults/match.txt.fingerprint", sidecar(jarMd5, STD_ID));
        f.put("match-defaults/match-duel.txt", ascii(matchTxt()));
        f.put("match-defaults/match-duel.txt.fingerprint", sidecar(jarMd5, DUEL_ID));
        f.put("kits/" + kitName(stamp), ascii("kit zip " + stamp));
        return f;
    }

    /** A sidecar in the mint's shape over {@code jarMd5}: one {@code file=} data row and one shipped variant. */
    private static byte[] sidecar(String jarMd5, String matchId) {
        return ContentFingerprintMint.sidecarBytes(List.of("# fixture kit sidecar",
                "fingerprint=" + ContentFingerprints.composite(jarMd5, DATA, 1, matchId),
                "file=data/x.csv", "file=data/variants/cc_kept_Hull.variant"));
    }

    /** Minted once per run: a mint draws a fresh {@code match.id}, and every release must ship the same bytes. */
    private static String matchTxt;

    private static String matchTxt() {
        if (matchTxt == null) {
            matchTxt = MatchDescriptorCodec.encode(MatchDescriptors.redact(MatchDescriptors.mint("1v1", 5000f, 0.9f)));
        }
        return matchTxt;
    }

    /** A decodable match file whose first roster row fields {@code variantId}. */
    private static String fielding(String variantId) {
        String text = MatchDescriptorCodec.encode(MatchDescriptors.mint("1v1", 5000f, 0.9f));
        String swapped = text.replaceFirst("(?m)^roster\\.0\\.variant=.*$", "roster.0.variant=" + variantId);
        if (swapped.equals(text)) {
            throw new IllegalStateException("the minted match has no roster.0.variant row");
        }
        MatchDescriptorCodec.decode(swapped.lines().toList());
        return swapped;
    }

    private static String addedHeader(String stamp) {
        return "# Added by the update to " + stamp + ": settings new in this version, at the values it ships";
    }

    private static Path extracted(Path dir, Release r) throws IOException {
        Path pkg = dir.resolve("pkg");
        extractOver(pkg, r);
        return pkg;
    }

    /** The release's files written over the folder as an unzip does (overwritten, nothing deleted), manifest last. */
    private static Void extractOver(Path pkg, Release r) throws IOException {
        for (Map.Entry<String, byte[]> e : r.files().entrySet()) {
            write(pkg, e.getKey(), e.getValue());
        }
        write(pkg, "MANIFEST.txt", r.manifest());
        return null;
    }

    /** A fresh unpack of {@code v1} (stamp {@link #S1}) and its first start; not installed = SETUP. */
    private static Path installed(Path dir, Release v1) {
        Path pkg = stage("extract the first release", () -> extracted(dir, v1));
        List<String> said = stage("the first start", () -> start(pkg));
        if (!INSTALLED.equals(last(said))) {
            throw new SetupFailure("the first start did not install the folder: " + said);
        }
        return pkg;
    }

    private static List<String> start(Path pkg) {
        return PackageRepair.repair(pkg, null, FOLD, 0L);
    }

    private static Void write(Path pkg, String rel, byte[] bytes) throws IOException {
        Path p = pkg.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
        return null;
    }

    private static byte[] readOrNull(Path p) throws IOException {
        try {
            return Files.readAllBytes(p);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    private static String text(Path pkg, String rel) throws IOException {
        byte[] b = readOrNull(pkg.resolve(rel));
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static List<Path> regularFiles(Path pkg) throws IOException {
        try (Stream<Path> walk = Files.walk(pkg)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** rel -> sha256 + modified time of every regular file under the folder. */
    private static Map<String, String> snapshot(Path pkg) throws IOException {
        Map<String, String> m = new TreeMap<>();
        for (Path p : regularFiles(pkg)) {
            m.put(pkg.relativize(p).toString().replace('\\', '/'), sha256Hex(Files.readAllBytes(p)) + " @"
                    + Files.getLastModifiedTime(p).toMillis());
        }
        return m;
    }

    private static List<String> changed(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (!Objects.equals(before.get(k), after.get(k))) {
                out.add(k);
            }
        }
        return out;
    }

    private static Set<String> names(Path dir) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.forEach(p -> out.add(p.getFileName().toString()));
        }
        return out;
    }

    private static void requireBytes(Path pkg, String rel, byte[] want, String regression) throws IOException {
        byte[] got = readOrNull(pkg.resolve(rel));
        require(got != null, regression + " - " + rel + " is missing");
        require(Arrays.equals(got, want), regression + " - " + rel + " holds " + escaped(
                new String(got, StandardCharsets.ISO_8859_1)));
    }

    private static String escaped(String s) {
        return s == null ? "<none>" : PackageManifest.mask(s.replace("\r", "\\r").replace("\n", "\\n"));
    }

    private static String last(List<String> said) {
        return said == null || said.isEmpty() ? null : said.get(said.size() - 1);
    }

    /** A rel as the repair prints it: the platform's separator. */
    private static String shown(String rel) {
        return rel.replace('/', File.separatorChar);
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

    /**
     * A directory link at {@code link} onto {@code target}: on Windows a junction made by the system's own
     * {@code mklink /J}, elsewhere a symbolic link; a {@link Skipped} when the platform refuses it. WHY a junction on
     * Windows even where symbolic links are allowed: it needs no privilege, so it is the link a host's folder
     * actually meets, and it is the shape the repair's guards must catch twice over - its link test reads a junction
     * as a link (a reparse point), and its tree walk sees a junction as a directory that is also "other". A symbolic
     * link there would leave the walk's junction branch unrun.
     *
     *
     */
    private static void dirLinkOrSkip(Path link, Path target) {
        if (File.separatorChar != '\\') {
            try {
                Files.createSymbolicLink(link, target);
                return;
            } catch (FileSystemException | UnsupportedOperationException refused) {
                throw new Skipped("symlink creation refused by the OS (" + refused.getClass().getSimpleName() + ")");
            } catch (IOException other) {
                throw new SetupFailure("symlink " + other.getClass().getSimpleName() + " "
                        + PackageManifest.mask(other.getMessage()));
            }
        }
        int code;
        try {
            Process mk = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!mk.waitFor(JUNCTION_WAIT_SECONDS, TimeUnit.SECONDS)) {
                mk.destroyForcibly();
                throw new Skipped("mklink /J did not finish in " + JUNCTION_WAIT_SECONDS + " s");
            }
            code = mk.exitValue();
        } catch (IOException noCmd) {
            throw new Skipped("no junction could be made (" + noCmd.getClass().getSimpleName() + ")");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SetupFailure("interrupted while making a junction");
        }
        if (code != 0 || !PackageFolderWriter.isLink(link)) {
            throw new Skipped("mklink /J exited " + code + " (or made no reparse point)");
        }
    }

    /**
     * A fresh folder OUTSIDE the row's folder holding only {@link #BEHIND_NAME}: what a followed link would expose.
     *
     *
     */
    private static Path behind() {
        return stage("a folder behind the link", () -> {
            Path b = Files.createTempDirectory("cc-pkgrepair-behind-");
            Files.write(b.resolve(BEHIND_NAME), ascii(BEHIND_TEXT));
            return b;
        });
    }

    /**
     * The folder behind a link still holds exactly its one file, byte-equal: nothing seeded, moved or written into it.
     *
     *
     */
    private static void requireUntouched(Path behind, String regression) throws IOException {
        Set<String> there = names(behind);
        require(there.equals(Set.of(BEHIND_NAME)), regression + " - the folder behind the link holds " + there);
        requireBytes(behind, BEHIND_NAME, ascii(BEHIND_TEXT), regression);
    }

    /**
     * The link removed as itself FIRST, then the folder behind it: the row's tree delete walks with
     * {@link Files#walk}, which descends a junction, so a link still standing would let it delete through into the
     * folder behind. Never throws (a cleanup never replaces a row's verdict); what it cannot remove prints a NOTE.
     *
     *
     */
    private static void unlinkFirst(String row, Path link, Path behind) {
        try {
            Files.deleteIfExists(link);
        } catch (IOException stuck) {
            System.out.println("NOTE " + row + ": the link was not removed (" + stuck.getClass().getSimpleName() + ")");
        }
        deleteTree(row, behind);
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

    /** A row this platform cannot stage: prints {@code SKIP <row>}, not a failure. */
    private static final class Skipped extends RuntimeException {
        Skipped(String message) {
            super(message);
        }
    }

    private static void row(String name, Body body) {
        Path dir;
        try {
            dir = Files.createTempDirectory("cc-pkgrepair-");
        } catch (IOException e) {
            System.out.println("SETUP-" + name + ": no temp dir (" + e.getClass().getSimpleName() + ")");
            failures++;
            return;
        }
        try {
            body.run(dir);
            System.out.println("PASS " + name);
        } catch (Skipped skipped) {
            System.out.println("SKIP " + name + ": " + skipped.getMessage());
        } catch (SetupFailure setup) {
            System.out.println("SETUP-" + name + ": " + setup.getMessage());
            failures++;
        } catch (AssertionError failed) {
            System.out.println("FAIL " + name + ": " + failed.getMessage());
            failures++;
        } catch (Exception | Error unexpected) {
            System.out.println("FAIL " + name + ": unexpected " + unexpected.getClass().getSimpleName() + ": "
                    + PackageManifest.mask(unexpected.getMessage()));
            failures++;
        } finally {
            deleteTree(name, dir);
        }
    }

    private static <T> T stage(String what, Dial<T> fixture) {
        try {
            return fixture.get();
        } catch (SetupFailure | Skipped passOn) {
            throw passOn;
        } catch (Exception e) {
            throw new SetupFailure(what + ": " + e.getClass().getSimpleName() + " " + PackageManifest.mask(e.getMessage()));
        }
    }

    private static void require(boolean ok, String regression) {
        if (!ok) {
            throw new AssertionError(regression);
        }
    }

    private static void deleteTree(String row, Path dir) {
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(all::add);
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        } catch (IOException leftover) {
            System.out.println("NOTE " + row + ": temp fixtures left at " + PackageManifest.mask(dir.toString())
                    + " (" + leftover.getClass().getSimpleName() + ")");
        }
    }
}
