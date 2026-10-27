package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.runtime.HandshakeCodec;
import cc.starsector.server.ops.PackageFolderWriter.Inputs;
import cc.starsector.server.ops.PackageFolderWriter.Kind;
import cc.starsector.server.ops.PackageFolderWriter.Plan;
import cc.starsector.server.ops.PackageFolderWriter.Platform;
import cc.starsector.server.ops.PackageFolderWriter.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rows for the package-folder writer's OUTPUT side:
 * {@link PackageFolderWriter#plan} + {@link PackageFolderWriter#write} driven end to end on fixture
 * inputs, then the folder it laid out read back — the route table per kind and platform, the
 * manifest grammar and meta order, the per-kind path law, the {@code pack.stamp} polarity of the
 * packaged gate, the SEED law and the K line, the SOURCE re-run delete scope, the G2 self-verify
 * with its {@code MANIFEST.failed} marker, determinism, the printed texts and the CLI's
 * {@code --produced} list. WHY its own class: {@link PackageZipSelftest} rows the INPUT readers; these
 * rows need the whole writer, and the writer's guard rows live in
 * {@code PackageFolderWriterGuardSelftest}. Fixtures reuse
 * {@link PackageZipSelftest}'s package-private builders ({@code tree}, {@code kitZip},
 * {@code baseZip}, {@code jarWithClasses}); every input and output folder sits under a
 * {@link Files#createTempDirectory} dir deleted in {@code finally}. Prints {@code PASS <row>} /
 * {@code FAIL <row>: <the regression that flips it>} / {@code SETUP-<row>: ...} (a fixture that never
 * staged, never a kill) and exits 0 iff every row passed.
 * Run: {@code java -cp server/classes cc.starsector.server.ops.PackageFolderWriterSelftest}.
 *
 *
 */
public final class PackageFolderWriterSelftest {

    private static int failures;

    // ---- fixture constants ----

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String STAMP = "fixture-1";
    private static final String JRE_VERSION = "17.0.99+7";
    private static final String BASE_KIT_NAME = "cc-kit-rel-1.zip";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final String CLOCK_STAMP = "source-20260102-030405";
    private static final String DATA_MD5 = "0123456789abcdef0123456789abcdef";
    private static final String FOLDED_REL = "cc/x/Folded.class";
    private static final byte[] FOLDED = ascii("folded-class-v1");
    /** The standing shape every fixture's {@code --match} / base pair carries: a decodable descriptor, REDACTED. */
    private static final MatchDescriptor STANDING_LIVE = MatchDescriptors.mint("1v1", 5000f, 0.9f);
    private static final String MATCH = MatchDescriptorCodec.encode(MatchDescriptors.redact(STANDING_LIVE));
    private static final byte[] MOD_INFO = ascii("{\"id\": \"cc_client\", \"name\": \"fixture\"}\n");
    private static final byte[] DATA = ascii("x,1\n");
    private static final byte[] LICENSE = ascii("fixture licence\n");
    private static final byte[] ARGV = ascii("-Xmx1g\n");
    private static final byte[] NOTICES = ascii("fixture notices\n");
    private static final byte[] LAUNCHER = ascii("launcher=1\n");
    /** The mint's launcher TEMPLATE: distinct from {@link #LAUNCHER}, so a route that ships the live file under the
     *  template's name (or seeds the live name from the wrong file) flips. */
    private static final byte[] LAUNCHER_TPL = ascii("launcher=template\n# timer.optOut=off\n");
    /** The live names a PACKED folder never writes: the first start creates each from a listed template. */
    private static final List<String> LIVE_NAMES = List.of("cc-server.properties", "launcher.properties",
            "serve-mods/enabled_mods.json", "match/match.txt", "match/match.txt.fingerprint");
    private static final byte[] C2 = ascii("[{\"match\": \"*.*\"}]\n");
    /** The holder fixture's bytes: distinct from the premain's, so a route that copies the premain under the holder's name flips. */
    private static final byte[] HOLDER = ascii("rngpin-holder-jar");
    private static final String ENABLED = "{\"enabledMods\": [\"cc_client\"]}";
    private static final List<String> META_ALL = List.of("git.head", "kit.jar.md5", "kit.fingerprint", "pack.stamp",
            "pack.kit", "pack.jre");
    /** The PACKED manifest's meta rows: META_ALL + the zip-declared program set. */
    private static final List<String> META_PACKED = List.of("git.head", "kit.jar.md5", "kit.fingerprint", "pack.stamp",
            "pack.kit", "pack.jre", PackageUpdateCheck.PROGRAM_SET_KEY);
    /** The printed-text prefix, spelled here (not read from the writer) so a dropped prefix flips the rows. */
    private static final String PREFIX = "package-folder: ";
    /**
     * The G1 token classes a printed text must not carry (the export's id classes: task / review ids, the section
     * mark, session refs, work-queue refs, workflow ids, repo paths),
     * each widened to the bare token the export must never see.
     */
    private static final List<Pattern> G1 = List.of(
            Pattern.compile("TASK-|REVIEW-|\\b(?:ADR|INV|CAP|SOL|TRACE|RE)-\\d{2,}"),
            Pattern.compile(Pattern.quote(String.valueOf((char) 0xA7))),
            Pattern.compile("\\bs(?!(?:16|32|64)\\b)\\d{2,4}\\b"),
            Pattern.compile("\\bwq"),
            Pattern.compile("wf_"),
            Pattern.compile("\\bdocs[\\\\/]|\\bgenerated[\\\\/]|\\blocal[\\\\/][A-Za-z0-9_-]+\\.ps1|\\b[A-Za-z]+_musings[\\\\/]"));

    private PackageFolderWriterSelftest() {
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** The build gate's entry ({@code OpsSelftests --selftest} chains it): every row, the
     * summary line, the failure count returned. */
    public static int run() {
        row("pfw-layout-packed", PackageFolderWriterSelftest::layoutPacked);
        row("pfw-layout-linux", PackageFolderWriterSelftest::layoutLinux);
        row("pfw-manifest-roundtrip", PackageFolderWriterSelftest::manifestRoundtrip);
        row("pfw-rows-grammar", PackageFolderWriterSelftest::rowsGrammar);
        row("pfw-packaged-gate", PackageFolderWriterSelftest::packagedGate);
        row("src-owner-refusal", PackageFolderWriterSelftest::srcOwnerRefusal);
        row("src-owner-refusal-text", PackageFolderWriterSelftest::srcOwnerRefusalText);
        row("pfw-keeps-host-files", PackageFolderWriterSelftest::keepsHostFiles);
        row("pfw-delete-scope", PackageFolderWriterSelftest::deleteScope);
        row("pfw-self-verify", PackageFolderWriterSelftest::selfVerify);
        row("pfw-deterministic", PackageFolderWriterSelftest::deterministic);
        row("pfw-text-law", PackageFolderWriterSelftest::textLaw);
        row("pfw-produced", PackageFolderWriterSelftest::produced);
        row("pfw-shapes-set", PackageFolderWriterSelftest::shapesSet);
        row("pfw-holder-bytes-per-route", PackageFolderWriterSelftest::holderBytesPerRoute);
        row("pfw-holder-beside-premain", PackageFolderWriterSelftest::holderBesidePremain);
        row("pfw-kit-zip-shipped", PackageFolderWriterSelftest::kitZipShipped);
        row("pfw-kit-zip-source", PackageFolderWriterSelftest::kitZipSource);
        row("pfw-standing-pair-from-match", PackageFolderWriterSelftest::standingPairFromMatch);
        row("pfw-packed-no-live-names", PackageFolderWriterSelftest::packedNoLiveNames);
        row("pfw-defaults-listed", PackageFolderWriterSelftest::defaultsListed);
        row("pfw-source-new-layout", PackageFolderWriterSelftest::sourceNewLayout);
        System.out.println(failures == 0 ? "PackageFolderWriterSelftest: every row PASS"
                : "PackageFolderWriterSelftest: " + failures + " row(s) did not pass");
        return failures;
    }

    // ---- rows ----

    /**
     * The owner-mode OPEN gate is pure over its cwd - a tree without the stage
     * tool gets the ONE sentence, a tree with it (the maintainer's repo root) null, so owner mode opens
     * as before; the tool is found by the core's own constant (a renamed tool flips the row), and only a regular
     * FILE counts (a directory of that name is not the tool).
     */
    private static void srcOwnerRefusal(Path dir) throws Exception {
        Path source = dir.resolve("source-tree");
        Files.createDirectories(source);
        String refused = OpsRoots.ownerOpenRefusal(source);
        require(OpsRoots.OWNER_OPEN_REFUSAL.equals(refused),
                "a tree without the stage tool no longer refuses owner mode with the one sentence: " + refused);
        Path tool = source.resolve(ServerOpsCore.STAGE_TOOL);
        Files.createDirectories(tool.getParent());
        Files.write(tool, ascii("# stage tool fixture\n"));
        require(OpsRoots.ownerOpenRefusal(source) == null,
                "a tree WITH the stage tool is refused: the maintainer's own console would not open");
        Files.delete(tool);
        Files.createDirectories(tool);
        require(OpsRoots.ownerOpenRefusal(source) != null, "a DIRECTORY named like the stage tool opens owner mode");
    }

    /** The refusal is a stranger's sentence: it names the server-folder route ({@code build.ps1 -ServerFolder},
     * {@code ops-web.bat}, the one console), never the retired Swing console script, and carries no repository path,
     * id or non-ASCII char (the G1 law). */
    private static void srcOwnerRefusalText(Path dir) throws Exception {
        String s = OpsRoots.OWNER_OPEN_REFUSAL;
        textOk("owner-open refusal", s, "", dir);
        require(s.contains("ops-web.bat") && s.contains("build.ps1 -ServerFolder"),
                "the refusal no longer names the server-folder route (build.ps1 -ServerFolder / ops-web.bat): " + s);
        require(!s.contains("ops-gui"),
                "the refusal names the retired Swing console script (one console ships: ops-web.bat): " + s);
        require(!s.contains("local/") && !s.contains("local\\") && !s.contains("repo root") && !s.contains("generated"),
                "the refusal names a repository path or the maintainer-only phrase: " + s);
    }

    /**
     * The RNG holder jar on every kind: one green write each of OWNER_STAGE, PACKED and SOURCE from ONE fixture,
     * keyed by kind in write order. The premain resolves the holder beside its own jar, so a kind that drops,
     * renames, rewrites or nests it fails every pinned start at agent load.
     *
     */
    private static Map<String, Path> threeRoutes(Path dir, Fx fx) throws IOException {
        Path base = stage("base zip", () -> base(fx, true));
        Inputs owner = stage("owner stage", () -> ownerStage(dir, fx, classes()));
        LinkedHashMap<String, Path> out = new LinkedHashMap<>();
        writeOk("OWNER_STAGE", owner, fx);
        out.put("OWNER_STAGE", owner.pkg());
        writeOk("PACKED", fx.packed(dir.resolve("packed")), fx);
        out.put("PACKED", dir.resolve("packed"));
        writeOk("SOURCE", fx.source(dir.resolve("source"), fx.classes(), base), fx);
        out.put("SOURCE", dir.resolve("source"));
        return out;
    }

    /** The manifest rows whose last path segment is {@code name} (a nested or renamed copy shows here, or not at all). */
    private static List<String> rowsNamed(PackageManifest m, String name) {
        List<String> out = new ArrayList<>();
        for (String rel : m.sha256ByPath.keySet()) {
            if (rel.equals(name) || rel.endsWith("/" + name)) {
                out.add(rel);
            }
        }
        return out;
    }

    /**
     * B1: on each kind exactly one manifest row names {@code cc-rngpin-holder.jar}, its sha256 is the INPUT
     * holder's, and the placed file hashes to it. Flips if a route drops, renames or rewrites the holder (or ships
     * the premain's bytes under its name: the fixture's two jars differ).
     *
     */
    private static void holderBytesPerRoute(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        String want = sha256(Files.readAllBytes(fx.holder()));
        if (want.equals(sha256(Files.readAllBytes(fx.premain())))) {
            throw new SetupFailure("the holder and premain fixtures share bytes");
        }
        for (Map.Entry<String, Path> e : threeRoutes(dir, fx).entrySet()) {
            PackageManifest m = manifest(e.getValue());
            List<String> rows = rowsNamed(m, "cc-rngpin-holder.jar");
            require(rows.size() == 1, e.getKey() + " lists " + rows.size() + " cc-rngpin-holder.jar row(s), not 1 (the"
                    + " route dropped, renamed or doubled the holder): " + rows);
            Path placed = e.getValue().resolve(rows.get(0));
            require(want.equals(m.sha256ByPath.get(rows.get(0))) && Files.isRegularFile(placed)
                    && want.equals(sha256(Files.readAllBytes(placed))), e.getKey() + "'s holder is not the input's"
                    + " bytes (listed or placed): the route rewrote it or copied another file under its name");
        }
    }

    /**
     * B3: on each kind the holder's row sits in the SAME folder as {@code cc-premain.jar}'s and the file is there on
     * disk - the premain's lookup law (it opens {@code cc-rngpin-holder.jar} in its own jar's directory). Flips if a
     * route nests either jar, or moves one without the other.
     *
     */
    private static void holderBesidePremain(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        for (Map.Entry<String, Path> e : threeRoutes(dir, fx).entrySet()) {
            PackageManifest m = manifest(e.getValue());
            List<String> premain = rowsNamed(m, "cc-premain.jar");
            List<String> holder = rowsNamed(m, "cc-rngpin-holder.jar");
            require(premain.size() == 1 && holder.size() == 1, e.getKey() + " does not list exactly one premain and one"
                    + " holder row: " + premain + " " + holder);
            String pDir = premain.get(0).contains("/") ? premain.get(0).substring(0, premain.get(0).lastIndexOf('/')) : "";
            String hDir = holder.get(0).contains("/") ? holder.get(0).substring(0, holder.get(0).lastIndexOf('/')) : "";
            require(pDir.equals(hDir) && Files.isRegularFile(e.getValue().resolve(premain.get(0))
                    .resolveSibling("cc-rngpin-holder.jar")), e.getKey() + " places the holder in '" + hDir
                    + "', not beside the premain in '" + pDir + "' (the premain would refuse at agent load)");
        }
    }

    /**
     * The joiner kit zip ships inside a PACKED folder on BOTH platforms: exactly one manifest row names the kit's
     * file name and it is {@code kits/<that name>}; that row's sha256 is the input zip's and the placed file's md5
     * is the input's; MANIFEST {@code pack.kit} is that name; and the console's own reader
     * ({@link ServerOpsCore#packageKitStamps}) resolves its zip to exactly that file. Flips if the route drops,
     * renames, nests or rewrites the kit, if {@code pack.kit} drifts from the shipped name, if the reader stops
     * finding it, or if one platform loses the route.
     *
     */
    private static void kitZipShipped(Path dir) throws Exception {
        for (Platform pf : List.of(Platform.WINDOWS, Platform.LINUX)) {
            Path sub = dir.resolve(pf == Platform.WINDOWS ? "win" : "linux");
            Fx fx = stage(pf + " fixture", () -> fx(sub, pf, classes()));
            Path pkg = sub.resolve("out");
            writeOk("PACKED / " + pf, fx.packed(pkg), fx);
            PackageManifest m = manifest(pkg);
            String name = fx.kit().getFileName().toString();
            String rel = "kits/" + name;
            List<String> rows = rowsNamed(m, name);
            require(rows.equals(List.of(rel)), pf + " lists " + rows + " for the kit zip, not exactly [" + rel
                    + "] (the kit route dropped, renamed, nested or doubled it, or this platform lost the route)");
            byte[] src = Files.readAllBytes(fx.kit());
            Path placed = pkg.resolve(rel);
            require(sha256(src).equals(m.sha256ByPath.get(rel)) && Files.isRegularFile(placed)
                    && md5(src).equals(md5(Files.readAllBytes(placed))), pf + "'s shipped kit is not the input zip's"
                    + " bytes (listed sha256 or placed md5): the route rewrote it or copied another file under its name");
            require(name.equals(m.packKit()), pf + "'s MANIFEST pack.kit (" + m.packKit() + ") is not the shipped kit's"
                    + " name " + name + " (the console's kit check would look for another file)");
            Path read = ServerOpsCore.packageKitStamps(pkg).zipOrNull();
            require(read != null && read.toAbsolutePath().normalize().equals(placed.toAbsolutePath().normalize()),
                    pf + ": the console's package kit reader resolves " + read + ", not the shipped " + rel
                            + " (the reader and the writer disagree on where the release kit sits)");
        }
    }

    /**
     * SOURCE over a released package that carries its kit at {@code kits/<pack.kit>}: (a) without {@code --kit} the
     * folder ships the base's own kit byte-equal and LISTED (flips if SOURCE refuses R13 on the release's own kit, or
     * drops or rewrites it); (b) with {@code --kit} the folder ships the GIVEN kit, not the base's, and
     * {@code pack.kit} names it (flips if the shipped zip and {@code pack.kit} diverge); (c) any other
     * {@code kits/*} base entry still refuses R13 (flips if the route widened past the base's own kit name).
     *
     */
    private static void kitZipSource(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        String baseRel = "kits/" + BASE_KIT_NAME;
        byte[] baseKit = ascii("the release kit zip a base package carries");
        Path base = stage("base zip with its kit", () -> base(fx, true, Map.of(baseRel, baseKit)));

        Path a = dir.resolve("source-a");
        writeOk("SOURCE + base kit", fx.source(a, fx.classes(), base), fx);
        PackageManifest ma = manifest(a);
        require(Files.isRegularFile(a.resolve(baseRel)) && Arrays.equals(Files.readAllBytes(a.resolve(baseRel)), baseKit)
                && sha256(baseKit).equals(ma.sha256ByPath.get(baseRel)), "SOURCE without --kit no longer ships the"
                + " base's own " + baseRel + " byte-equal and LISTED (rows " + rowsNamed(ma, BASE_KIT_NAME) + ")");
        require(BASE_KIT_NAME.equals(ma.packKit()), "SOURCE without --kit no longer keeps the base's pack.kit: "
                + ma.packKit());

        Inputs noKit = fx.source(dir.resolve("source-b"), fx.classes(), base);
        Inputs withKit = new Inputs(noKit.kind(), noKit.pkg(), noKit.classesDir(), noKit.premainJar(), noKit.holderJar(),
                noKit.c2File(), noKit.clientModDir(), fx.kit(), noKit.baseZip(), noKit.argvFile(), noKit.jreDir(),
                noKit.mesaDir(), noKit.nativeDir(), noKit.hostFilesDir(), noKit.platform(), noKit.gitHead(),
                noKit.kitJarMd5(), noKit.stamp(), noKit.produced());
        writeOk("SOURCE --kit + base kit", withKit, fx);
        Path b = withKit.pkg();
        PackageManifest mb = manifest(b);
        String given = fx.kit().getFileName().toString();
        require(Arrays.equals(Files.readAllBytes(b.resolve("kits/" + given)), Files.readAllBytes(fx.kit()))
                && mb.sha256ByPath.containsKey("kits/" + given) && given.equals(mb.packKit()),
                "SOURCE --kit no longer ships the given kit at kits/" + given + ", listed and named by pack.kit (the"
                        + " shipped zip and pack.kit diverge): pack.kit=" + mb.packKit());
        require(!Files.exists(b.resolve(baseRel)) && !mb.sha256ByPath.containsKey(baseRel),
                "SOURCE --kit still ships the base's " + baseRel + " beside the given kit");

        Path other = stage("base zip with a foreign kit", () -> base(fx, true, Map.of("kits/other.zip", baseKit)));
        String r13 = PREFIX + PackageKitZip.r13(other, "kits/other.zip").getMessage();
        List<String> refused = refusals(fx.source(dir.resolve("source-c"), fx.classes(), other), fx);
        require(refused.contains(r13), "a base kits/other.zip (not the base's pack.kit) no longer refuses R13 (the base"
                + " kit route widened to any kits/ entry): " + refused);
    }

    /**
     * The folder's standing pair comes from ONE source, the {@code --match} input, never the kit: (a) a PACKED pack
     * over a kit holding no match entry places {@code match/match.txt} + its {@code .fingerprint} byte-equal to the
     * {@code --match} file + its sidecar, the placed file strictly REDACTED, and MANIFEST {@code kit.fingerprint} =
     * that sidecar's composite (match-keyed; the kit's own sidecar carries no match term); (b) a PACKED pack with no
     * {@code --match} refuses with exactly the one no-standing-match line, nothing written; (c) a {@code --match}
     * whose sidecar is keyed to another match id refuses with exactly the one split-pair line; (d) SOURCE with
     * {@code --match} seeds THAT pair, not the base's, and {@code kit.fingerprint} follows it.
     *
     */
    private static void standingPairFromMatch(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        if (PackageKitZip.entry(fx.kit(), PackageKitZip.MATCH_ENTRY, 1 << 20) != null) {
            throw new SetupFailure("the fixture kit still carries a match entry");
        }
        Path pkg = dir.resolve("packed");
        writeOk("PACKED --match", fx.packed(pkg), fx);
        byte[] wantMatch = Files.readAllBytes(fx.match());
        byte[] wantSide = Files.readAllBytes(PackageFolderWriter.sidecarOf(fx.match()));
        byte[] gotMatch = Files.readAllBytes(pkg.resolve("match-defaults/match.txt"));
        require(Arrays.equals(gotMatch, wantMatch)
                && Arrays.equals(Files.readAllBytes(pkg.resolve("match-defaults/match.txt.fingerprint")), wantSide),
                "(a) the folder's match-defaults/match.txt + .fingerprint are not the --match pair byte-equal (flips if"
                        + " the pair comes from another source, or is rewritten on the way)");
        require(MatchDescriptors.isStrictlyRedacted(new String(gotMatch, StandardCharsets.UTF_8).lines().toList()),
                "(a) the placed match-defaults/match.txt is not strictly REDACTED (the redaction twin over the folder's"
                        + " match file)");
        String standingFp = ContentFingerprints.parseSidecarFingerprint(
                new String(wantSide, StandardCharsets.ISO_8859_1).lines().toList(), "the fixture's standing sidecar");
        require(standingFp.equals(manifest(pkg).meta.get("kit.fingerprint")), "(a) MANIFEST kit.fingerprint is not the"
                + " standing sidecar's composite (flips if the meta reads the kit's sidecar, which carries no match term): "
                + manifest(pkg).meta.get("kit.fingerprint"));

        Path none = dir.resolve("packed-none");
        List<String> refused = refusals(withMatch(fx.packed(none), null), fx);
        require(refused.equals(List.of(PackageFolderWriter.NO_STANDING_MATCH)), "(b) a PACKED pack with no --match no"
                + " longer refuses with exactly the one no-standing-match line (flips if PACKED seeds a pair from another"
                + " source, or the refusal is dropped): " + refused);
        require(!Files.exists(none), "(b) a refused no---match plan created the folder");

        MatchDescriptor other = MatchDescriptors.mint("1v1", 4000f, 0.6f);
        Path split = stage("a split pair", () -> standingPair(dir.resolve("split"), MATCH,
                sidecarKeyed(md5(fx.jar()), other.matchId(), "data/x.csv")));
        List<String> splitRefused = refusals(withMatch(fx.packed(dir.resolve("packed-split")), split), fx);
        require(splitRefused.size() == 1 && splitRefused.get(0).startsWith(PREFIX + "REFUSED ")
                && splitRefused.get(0).contains("name two different matches"), "(c) a --match whose .fingerprint is keyed"
                + " to another match id no longer refuses with exactly the split-pair line (flips if the match= vs"
                + " match.id check is lost): " + splitRefused);

        MatchDescriptor second = MatchDescriptors.mint("1v1", 3000f, 0.5f);
        Path given = stage("a second standing pair", () -> standingPair(dir.resolve("given"),
                MatchDescriptorCodec.encode(MatchDescriptors.redact(second)),
                sidecarKeyed(md5(fx.jar()), second.matchId(), "data/x.csv")));
        Path base = stage("base zip", () -> base(fx, true));
        Path src = dir.resolve("source");
        writeOk("SOURCE --match", withMatch(fx.source(src, fx.classes(), base), given), fx);
        byte[] givenSide = Files.readAllBytes(PackageFolderWriter.sidecarOf(given));
        require(Arrays.equals(Files.readAllBytes(src.resolve("match/match.txt")), Files.readAllBytes(given))
                && Arrays.equals(Files.readAllBytes(src.resolve("match/match.txt.fingerprint")), givenSide),
                "(d) SOURCE with --match did not seed the --match pair (flips if SOURCE still reads the base's own pair)");
        require(ContentFingerprints.parseSidecarFingerprint(new String(givenSide, StandardCharsets.ISO_8859_1).lines()
                .toList(), "the given sidecar").equals(manifest(src).meta.get("kit.fingerprint")), "(d) SOURCE"
                + " kit.fingerprint is not the --match sidecar's composite (flips if it reads the base's or the kit's)");
    }

    /**
     * (i) A PACKED folder holds NO live-named host file, on both platforms: none of {@link #LIVE_NAMES} exists and
     * no {@code match/} folder, and every file on disk but {@code MANIFEST.txt} (and the writer's stage lock) is a
     * manifest row hashing to its bytes - so a hand extract of a newer zip over a host's folder replaces program
     * files only. Flips if a route goes back to a SEED (an unlisted live name or an unlisted script / template).
     *
     */
    private static void packedNoLiveNames(Path dir) throws Exception {
        for (Platform pf : List.of(Platform.WINDOWS, Platform.LINUX)) {
            Path sub = dir.resolve(pf == Platform.WINDOWS ? "win" : "linux");
            Fx fx = stage(pf + " fixture", () -> fx(sub, pf, classes()));
            Path pkg = sub.resolve("out");
            writeOk("PACKED / " + pf, fx.packed(pkg), fx);
            List<String> live = LIVE_NAMES.stream().filter(n -> Files.exists(pkg.resolve(n))).toList();
            require(live.isEmpty() && !Files.exists(pkg.resolve("match")), pf + " PACKED folder holds a live-named"
                    + " host file or a match/ folder (flips if a route seeds a live name again): " + live);
            PackageManifest m = manifest(pkg);
            TreeSet<String> onDisk = new TreeSet<>();
            try (Stream<Path> s = Files.walk(pkg)) {
                s.filter(Files::isRegularFile).forEach(p -> onDisk.add(pkg.relativize(p).toString().replace('\\', '/')));
            }
            onDisk.remove("MANIFEST.txt");
            onDisk.remove(ServerOpsLaunch.STAGE_LOCK_FILE);
            List<String> unlisted = onDisk.stream().filter(r -> !m.sha256ByPath.containsKey(r)).toList();
            require(unlisted.isEmpty(), pf + " PACKED folder holds files with no manifest row (flips if a script, README,"
                    + " template or shipped default is written unlisted): " + unlisted);
            for (String rel : onDisk) {
                require(m.sha256ByPath.get(rel).equals(sha256(Files.readAllBytes(pkg.resolve(rel)))),
                        pf + ": the row for " + rel + " does not hash to the file (flips if a file is rewritten after its"
                                + " row is taken)");
            }
        }
    }

    /**
     * (ii) The {@code match-defaults/} rows are manifest rows: a PACKED pack with {@code --shapes} lists the standing
     * pair AND every shipped shape pair under {@code match-defaults/}, each row the sha256 of the file on disk, the
     * rows in ordinal order with the routes' rows. Flips if the shape set is written unlisted, hashed before its
     * write, or left out of the sort.
     */
    private static void defaultsListed(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        MatchDescriptor kitLive = MatchDescriptors.mint("1v1", 6000f, 0.8f);
        String composite = ContentFingerprints.composite(md5(fx.jar()), DATA_MD5, HandshakeCodec.HANDSHAKE_VERSION,
                kitLive.matchId());
        Path kit = stage("a kit holding no match file", () -> PackageZipSelftest.kitZip(fx.root(), fx.jar(), null,
                "# kit sidecar (fixture)\r\nfingerprint=" + ContentFingerprints.stripMatch(composite)
                        + "\r\nfile=data/x.csv\r\n", Map.of("CC_Client/data/x.csv", DATA)));
        Path match = stage("the standing pair", () -> standingPair(dir.resolve("standing"),
                MatchDescriptorCodec.encode(MatchDescriptors.redact(kitLive)),
                "# standing sidecar (fixture)\r\nfingerprint=" + composite + "\r\nfile=data/x.csv\r\n"));
        Path lib = dir.resolve("library");
        stage("library", () -> {
            libraryShape(lib, "match-a", MatchDescriptors.mint("bastion:duel", 7000f, 0.7f), true);
            return libraryShape(lib, "match-b", MatchDescriptors.mint("bastion:duel", 8000f, 0.6f), true);
        });
        Path pkg = dir.resolve("shaped");
        writeOk("PACKED with --shapes", withShapes(fx.packed(pkg, fx.hostFiles(), kit, match), lib, null), fx);
        PackageManifest m = manifest(pkg);
        List<String> want = List.of("match-defaults/match-a.txt", "match-defaults/match-a.txt.fingerprint",
                "match-defaults/match-b.txt", "match-defaults/match-b.txt.fingerprint", "match-defaults/match.txt",
                "match-defaults/match.txt.fingerprint");
        List<String> got = m.sha256ByPath.keySet().stream().filter(r -> r.startsWith("match-defaults/")).toList();
        require(got.equals(want), "the match-defaults/ manifest rows are not the standing pair + both shape pairs in"
                + " ordinal order (flips if a shape goes unlisted or the shape rows are appended unsorted): " + got);
        for (String rel : want) {
            require(m.sha256ByPath.get(rel).equals(sha256(Files.readAllBytes(pkg.resolve(rel)))), rel + "'s row does"
                    + " not hash to the written file (flips if the row is taken before the write)");
        }
        require(PackageManifest.firstMismatch(pkg, m) == null, "the shaped folder fails its own manifest");
    }

    /**
     * (iii) SOURCE over a NEW-layout base (a PACKED folder zipped as a release: every file listed, the standing pair
     * and one shipped shape under {@code match-defaults/}, plus {@code update.bat}) routes it whole: exit 0; the live
     * names are SEEDED (unlisted) - {@code cc-server.properties} / {@code launcher.properties} = the base's
     * templates byte-equal, {@code enabled_mods.json} = the minted bytes, {@code match/match.txt} + sidecar = the
     * base's defaults pair, the shape at {@code match/match-alpha.txt} with its sidecar re-keyed to its id; the two
     * templates + {@code update.bat} are LISTED singles; no {@code match-defaults/} folder lands; and a re-run is green
     * with no K / R15 line, every seed kept. Flips if the defaults pair, the templates, the update script or the
     * shape library refuse R13, or the live names go unseeded.
     */
    private static void sourceNewLayout(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        Path packed = dir.resolve("packed");
        writeOk("PACKED (the release)", fx.packed(packed), fx);
        MatchDescriptor alpha = MatchDescriptors.redact(MatchDescriptors.mint("bastion:duel", 7000f, 0.7f));
        Path base = stage("the release as a new-layout base", () -> newLayoutBase(fx, packed, alpha));
        Path src = dir.resolve("source");
        writeOk("SOURCE over a new-layout base", fx.source(src, fx.classes(), base), fx);
        PackageManifest m = manifest(src);
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        seeds.put("cc-server.properties", hostFiles(false).get("cc-server.template.properties"));
        seeds.put("launcher.properties", LAUNCHER_TPL);
        seeds.put("serve-mods/enabled_mods.json", ascii(ENABLED));
        seeds.put("match/match.txt", ascii(MATCH));
        seeds.put("match/match-alpha.txt", MatchDescriptorCodec.encode(alpha).getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> e : seeds.entrySet()) {
            Path p = src.resolve(e.getKey());
            require(Files.isRegularFile(p) && Arrays.equals(Files.readAllBytes(p), e.getValue())
                    && !m.sha256ByPath.containsKey(e.getKey()), "SOURCE over a new-layout base did not SEED "
                    + e.getKey() + " from the base's template / defaults (flips if the live name goes unseeded, is"
                    + " listed, or comes from another file)");
        }
        for (String side : List.of("match/match.txt.fingerprint", "match/match-alpha.txt.fingerprint")) {
            require(Files.isRegularFile(src.resolve(side)) && !m.sha256ByPath.containsKey(side), side + " is missing or"
                    + " listed in the SOURCE folder (flips if the defaults sidecar is not routed to the live pair)");
        }
        String keyed = ContentFingerprints.componentOrNull(ContentFingerprints.parseSidecarFingerprint(Files.readAllLines(
                src.resolve("match/match-alpha.txt.fingerprint"), StandardCharsets.ISO_8859_1), "match-alpha"), "match");
        require(alpha.matchId().equals(keyed), "the seeded shape's sidecar is not re-keyed to the shape's id (flips if"
                + " the base's match-defaults sidecar is copied instead of re-keyed): match=" + keyed);
        for (String single : List.of("cc-server.template.properties", "launcher.template.properties", "update.bat")) {
            require(m.sha256ByPath.containsKey(single) && Files.isRegularFile(src.resolve(single)), single + " is not a"
                    + " LISTED single of the SOURCE folder (flips if the new-layout base's program file is dropped or"
                    + " seeded)");
        }
        require(!Files.exists(src.resolve("match-defaults")), "the SOURCE folder copied the base's match-defaults/"
                + " under its own name (flips if the defaults pair is routed as a base file)");
        Written again = writeOk("SOURCE re-run over the new-layout base", fx.source(src, fx.classes(), base), fx);
        require(again.notices().isEmpty(), "the re-run printed K / note lines over unedited seeds (flips if a seed is"
                + " compared against another source than the one that wrote it): " + again.notices());
        for (Map.Entry<String, byte[]> e : seeds.entrySet()) {
            require(Arrays.equals(Files.readAllBytes(src.resolve(e.getKey())), e.getValue()), "the re-run rewrote "
                    + e.getKey());
        }
    }

    /**
     * Today's release as a base zip: every manifest row of the PACKED folder {@code packed} (its {@code kits/} entry
     * left out: {@code pack.kit} names {@link #BASE_KIT_NAME}, which the base does not carry) + {@code update.bat}
     * + one shipped shape pair under {@code match-defaults/} ({@code alpha}, its sidecar keyed to it), ALL listed;
     * the meta = {@link #base}'s.
     */
    private static Path newLayoutBase(Fx fx, Path packed, MatchDescriptor alpha) throws IOException {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        for (String rel : manifest(packed).sha256ByPath.keySet()) {
            if (!rel.startsWith("kits/")) {
                files.put(rel, Files.readAllBytes(packed.resolve(rel)));
            }
        }
        files.put("update.bat", ascii("@echo update\r\n"));
        // the standing sidecar at the wire's handshake version: each shipped shape's sidecar is re-keyed from it
        files.put("match-defaults/match.txt.fingerprint", ascii("# standing sidecar (fixture)\r\nfingerprint="
                + ContentFingerprints.composite(md5(fx.jar()), DATA_MD5, HandshakeCodec.HANDSHAKE_VERSION,
                        STANDING_LIVE.matchId()) + "\r\nfile=data/x.csv\r\n"));
        files.put("match-defaults/match-alpha.txt", MatchDescriptorCodec.encode(alpha).getBytes(StandardCharsets.UTF_8));
        files.put("match-defaults/match-alpha.txt.fingerprint", ascii(sidecarKeyed(md5(fx.jar()), alpha.matchId(),
                "data/x.csv")));
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("git.head", HEAD);
        meta.put("kit.jar.md5", md5(fx.jar()));
        meta.put("pack.stamp", "rel-2");
        meta.put("pack.kit", BASE_KIT_NAME);
        meta.put("pack.jre", JRE_VERSION);
        return PackageZipSelftest.baseZip(fx.root(), files, meta, new TreeSet<>(files.keySet()));
    }

    private static void layoutPacked(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        Path pkg = dir.resolve("out");
        stage("empty folder", () -> Files.createDirectories(pkg));
        Written w = writeOk("PACKED / WINDOWS", fx.packed(pkg), fx);
        PackageManifest m = manifest(pkg);

        Matcher ok = Pattern.compile("^" + Pattern.quote(PREFIX + "wrote ") + "(.+) \\((\\d+) files; (\\d+) test and tool"
                + " classes left out\\) - start it with serve\\.bat or ops-web\\.bat \\(Linux: sh serve\\.sh or sh"
                + " ops-web\\.sh\\)$").matcher(w.result().okLine());
        require(ok.matches(), "the OK line lost its shape (prefix, counts or the start hint): "
                + PackageManifest.mask(w.result().okLine()));
        require(ok.group(1).equals(PackageManifest.mask(pkg.toAbsolutePath().normalize().toString())),
                "the OK line no longer names the folder written: " + PackageManifest.mask(ok.group(1)));
        require(Integer.parseInt(ok.group(2)) == m.sha256ByPath.size() && w.result().files() == m.sha256ByPath.size(),
                "the OK line's file count is no longer MANIFEST.txt's row count (" + ok.group(2) + " vs "
                        + m.sha256ByPath.size() + ")");
        require(Integer.parseInt(ok.group(3)) == 3 && w.result().skipped() == 3,
                "the OK line no longer counts the 3 tool / selftest classes left out: " + ok.group(3));

        for (String home : List.of("classes/cc/starsector/server/tools/Tool.class", "classes/cc/a/FooSelftest.class",
                "classes/cc/a/FooSelftest$Inner.class")) {
            require(!Files.exists(pkg.resolve(home)) && !m.sha256ByPath.containsKey(home),
                    home + " was copied (the classes route no longer leaves tools/** and *Selftest + its $ inners home)");
        }
        require(Arrays.equals(Files.readAllBytes(pkg.resolve("serve-mods/CC_Client/jars/cc-client-mod.jar")), fx.jar()),
                "the kit's jar is no longer placed byte-equal at serve-mods/CC_Client/jars/cc-client-mod.jar");
        byte[] match = Files.readAllBytes(pkg.resolve("match-defaults/match.txt"));
        require(Arrays.equals(match, ascii(MATCH)) && MatchDescriptors.isStrictlyRedacted(
                Arrays.asList(new String(match, StandardCharsets.US_ASCII).split("\n"))),
                "match-defaults/match.txt is no longer the --match input's strictly REDACTED copy");
        for (String bat : List.of("serve.bat", "stop.bat", "ops-web.bat", "update.bat", "README.md")) {
            require(Files.isRegularFile(pkg.resolve(bat)), bat + " is missing from a Windows folder (the host table regressed)");
        }
        require(rootNames(pkg).stream().noneMatch(n -> n.endsWith(".sh")),
                "a Windows folder holds a .sh script (the platform table copies the Linux trio): " + rootNames(pkg));

        TreeSet<String> listed = new TreeSet<>(List.of("classes/cc/a/B.class", "classes/" + FOLDED_REL, "cc-premain.jar",
                "cc-rngpin-holder.jar", "jvm/c2-directives.json", "serve-mods/CC_Client/mod_info.json", "serve-mods/CC_Client/data/x.csv",
                "LICENSE.txt", "serve-mods/CC_Client/jars/cc-client-mod.jar", "serve.argv", "THIRD-PARTY-NOTICES.txt",
                "jre/bin/java.exe", "jre/bin/opengl32.dll", "jre/bin/libgallium_wgl.dll", "jre/cc-serve-jre.txt",
                "kits/" + fx.kit().getFileName(), "cc-server.template.properties", "launcher.template.properties",
                "serve.bat", "stop.bat", "ops-web.bat", "update.bat", "README.md", "match-defaults/match.txt",
                "match-defaults/match.txt.fingerprint"));
        require(new TreeSet<>(m.sha256ByPath.keySet()).equals(listed), "the PACKED route table's LISTED set drifted"
                + " (flips if a template, script, README or the match-defaults pair goes unlisted or back to a live name):"
                + " expected " + listed + ", got " + new TreeSet<>(m.sha256ByPath.keySet()));
        List<String> order = new ArrayList<>(m.sha256ByPath.keySet());
        List<String> sorted = new ArrayList<>(order);
        sorted.sort(Comparator.naturalOrder());
        require(order.equals(sorted), "the PACKED manifest rows are no longer in ordinal path order: " + order);
        require(Arrays.equals(Files.readAllBytes(pkg.resolve("launcher.template.properties")), LAUNCHER_TPL)
                && Arrays.equals(Files.readAllBytes(pkg.resolve("cc-server.template.properties")),
                        hostFiles(false).get("cc-server.template.properties")),
                "a template is not its input byte-equal (flips if the launcher template is copied from the mint's live"
                        + " launcher.properties, or the server template is rewritten)");
        FileTime written = Files.getLastModifiedTime(pkg.resolve("MANIFEST.txt"));
        for (String rel : m.sha256ByPath.keySet()) {
            require(written.compareTo(Files.getLastModifiedTime(pkg.resolve(rel))) >= 0,
                    "MANIFEST.txt is older than " + rel + " (it is no longer written LAST)");
        }
    }

    private static void layoutLinux(Path dir) throws Exception {
        Fx fx = stage("Linux fixture", () -> fx(dir, Platform.LINUX, classes()));
        Path pkg = dir.resolve("out");
        writeOk("PACKED / LINUX", fx.packed(pkg), fx);
        PackageManifest m = manifest(pkg);
        for (String sh : List.of("serve.sh", "stop.sh", "ops-web.sh", "update.sh")) {
            require(Files.isRegularFile(pkg.resolve(sh)), sh + " is missing from a Linux folder (the platform table regressed)");
        }
        require(rootNames(pkg).stream().noneMatch(n -> n.endsWith(".bat")),
                "a Linux folder holds a .bat script (the platform table copies the Windows set): " + rootNames(pkg));
        if (fx.pins().size() != 2) {
            throw new SetupFailure("the Linux fixture does not pin two natives");
        }
        for (Map.Entry<String, String> pin : fx.pins().entrySet()) {
            String rel = "native/linux/" + pin.getKey();
            require(Files.isRegularFile(pkg.resolve(rel)) && pin.getValue().equals(m.sha256ByPath.get(rel)),
                    rel + " is not placed AND listed with its pinned sha256 (the natives route dropped)");
        }

        Path crHost = stage("CR host files", () -> PackageZipSelftest.tree(dir.resolve("host-cr"), hostFiles(true)));
        Path pkg2 = dir.resolve("out-cr");
        String cr = PREFIX + "REFUSED serve.sh holds a CR byte at offset 9 - a Linux host script must be LF only"
                + " (re-checkout server-package\\serve.sh, or convert it)";
        boolean writeRefused = false;
        try (Plan p = PackageFolderWriter.plan(fx.packed(pkg2, crHost, fx.kit()), CLOCK, fx.pins())) {
            require(p.refusals().equals(List.of(cr)), "a CR byte in serve.sh no longer refuses with exactly the CR line: "
                    + p.refusals());
            try {
                PackageFolderWriter.write(p);
            } catch (IllegalStateException refused) {
                writeRefused = true;
            }
        }
        require(writeRefused, "a plan holding the CR refusal was WRITTEN");
        require(!Files.exists(pkg2), "a refused Linux plan left the folder behind (nothing may be written)");
    }

    private static void manifestRoundtrip(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        Path base = stage("base zip", () -> base(fx, true));
        Inputs owner = stage("owner stage", () -> ownerStage(dir, fx, classes()));
        // the STANDING sidecar's composite (match-keyed): flips if kit.fingerprint reads the kit's (no match term)
        String composite = ContentFingerprints.composite(md5(fx.jar()), DATA_MD5, 1, STANDING_LIVE.matchId());

        PackageManifest o = roundtrip("OWNER_STAGE", owner, fx, List.of("git.head", "kit.jar.md5"));
        require(HEAD.equals(o.meta.get("git.head")), "OWNER_STAGE no longer keeps the passed git.head");
        require(o.sha256ByPath.containsKey("serve.argv") && o.sha256ByPath.containsKey("serve-mods/enabled_mods.json")
                && o.sha256ByPath.containsKey("classes/cc/starsector/server/tools/Tool.class"),
                "OWNER_STAGE no longer lists today's row set (serve.argv, all of serve-mods/**, all of classes/**)");

        PackageManifest p = roundtrip("PACKED", fx.packed(dir.resolve("packed")), fx, META_PACKED);
        require(List.of(HEAD, composite, STAMP, fx.kit().getFileName().toString(), JRE_VERSION).equals(List.of(
                p.meta.get("git.head"), p.meta.get("kit.fingerprint"), p.meta.get("pack.stamp"), p.meta.get("pack.kit"),
                p.meta.get("pack.jre"))), "PACKED meta values drifted from the inputs: " + p.meta);

        PackageManifest s = roundtrip("SOURCE", fx.source(dir.resolve("source"), fx.classes(), base), fx, META_ALL);
        require(List.of(HEAD, composite, CLOCK_STAMP, BASE_KIT_NAME, JRE_VERSION).equals(List.of(
                s.meta.get("git.head"), s.meta.get("kit.fingerprint"), s.meta.get("pack.stamp"), s.meta.get("pack.kit"),
                s.meta.get("pack.jre"))), "SOURCE meta no longer copies git.head / pack.kit / pack.jre from the base: "
                        + s.meta);
        // SOURCE with --kit names THAT kit as pack.kit (Make joiner kit resolves
        // kits/<pack.kit> to the jar kit.jar.md5 describes); the row above keeps the no-kit case on the base's name
        Inputs noKit = fx.source(dir.resolve("source-kit"), fx.classes(), base);
        Inputs withKit = new Inputs(noKit.kind(), noKit.pkg(), noKit.classesDir(), noKit.premainJar(), noKit.holderJar(),
                noKit.c2File(), noKit.clientModDir(), fx.kit(), noKit.baseZip(), noKit.argvFile(), noKit.jreDir(), noKit.mesaDir(),
                noKit.nativeDir(), noKit.hostFilesDir(), noKit.platform(), noKit.gitHead(), noKit.kitJarMd5(),
                noKit.stamp(), noKit.produced());
        PackageManifest sk = roundtrip("SOURCE --kit", withKit, fx, META_ALL);
        require(fx.kit().getFileName().toString().equals(sk.meta.get("pack.kit")),
                "SOURCE --kit no longer names the given kit as pack.kit (Make joiner kit would refuse R2 on the"
                        + " base's kit): " + sk.meta);
    }

    private static void rowsGrammar(Path dir) throws Exception {
        Map<String, byte[]> spaced = classes();
        spaced.put("cc/a/Has Space.class", ascii("spaced"));
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, spaced));
        Path base = stage("base zip", () -> base(fx, true));
        Inputs owner = stage("owner stage", () -> ownerStage(dir, fx, spaced));
        String rel = "classes/cc/a/Has Space.class";

        PackageManifest o = roundtrip("OWNER_STAGE", owner, fx, List.of("git.head", "kit.jar.md5"));
        require(o.sha256ByPath.containsKey(rel), "OWNER_STAGE no longer LISTS a spaced class file (owner mode's own"
                + " mods may hold spaces; the portable law leaked into the OWNER_STAGE kind)");

        String r9 = PREFIX + "REFUSED " + rel + " cannot be listed (a space, a device name or an unusual character) -"
                + " rename it";
        require(refusals(fx.packed(dir.resolve("packed")), fx).equals(List.of(r9)),
                "PACKED no longer refuses a spaced class with exactly R9 naming it (the portable path law is lost): "
                        + refusals(fx.packed(dir.resolve("packed")), fx));
        require(refusals(fx.source(dir.resolve("source"), fx.classes(), base), fx).equals(List.of(r9)),
                "SOURCE no longer refuses a spaced class with exactly R9 naming it (the portable path law is lost): "
                        + refusals(fx.source(dir.resolve("source"), fx.classes(), base), fx));
        require(!Files.exists(dir.resolve("packed")) && !Files.exists(dir.resolve("source")),
                "a plan refused by R9 left a folder behind");
    }

    private static void packagedGate(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        Path base = stage("base zip", () -> base(fx, true));
        Path packed = dir.resolve("packed");
        writeOk("PACKED", fx.packed(packed), fx);
        gateReturns("PACKED", packed);

        Path source = dir.resolve("source");
        try (Plan p = PackageFolderWriter.plan(fx.source(source, fx.classes(), base))) {
            require(p.refusals().isEmpty(), "SOURCE (system clock): a green fixture is REFUSED: " + p.refusals());
            PackageFolderWriter.write(p);
        }
        gateReturns("SOURCE", source);
        String stamp = manifest(source).meta.get("pack.stamp");
        require(stamp != null && stamp.matches("^source-\\d{8}-\\d{6}$"),
                "a SOURCE pack.stamp is no longer source-<yyyyMMdd-HHmmss>: " + PackageManifest.mask(stamp));

        Inputs owner = stage("owner stage", () -> ownerStage(dir, fx, classes()));
        writeOk("OWNER_STAGE", owner, fx);
        boolean threw = false;
        try {
            OpsRoots.packagedChecked(owner.pkg());
        } catch (IllegalArgumentException notPacked) {
            threw = true;
        }
        require(threw, "an owner stage now PASSES the packaged gate (a pack.stamp is written for OWNER_STAGE: --pkg on"
                + " an owner-mode stage folder would be accepted)");
    }

    private static void keepsHostFiles(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        Path base = stage("base zip", () -> base(fx, true));
        Path pkg = dir.resolve("srv");
        Written first = writeOk("SOURCE run 1", fx.source(pkg, fx.classes(), base), fx);
        require(first.notices().isEmpty(), "a SOURCE run into a new folder printed K / note lines: " + first.notices());
        require(Arrays.equals(Files.readAllBytes(pkg.resolve("serve.bat")), hostFiles(false).get("serve.bat")),
                "SOURCE run 1 no longer seeds serve.bat from the base");

        LinkedHashMap<String, byte[]> host = new LinkedHashMap<>();
        host.put("serve.bat", ascii("@echo my own serve\r\n"));
        host.put("cc-server.properties", ascii("port=7777\n"));
        host.put("match/mine.txt", ascii("version=1\nseat.1.token=REDACTED-mine\n"));
        host.put("my-notes.txt", ascii("my notes\n"));
        stage("host edits", () -> PackageZipSelftest.tree(pkg, host));
        Written second = writeOk("SOURCE re-run", fx.source(pkg, fx.classes(), base), fx);
        for (Map.Entry<String, byte[]> e : host.entrySet()) {
            require(Arrays.equals(Files.readAllBytes(pkg.resolve(e.getKey())), e.getValue()),
                    "a SOURCE re-run overwrote the host's " + e.getKey() + " (SEED overwrites / a host file was replaced)");
        }
        // The writer's K law (keptPhase): a K line names a kept SEED file that DIFFERS from this build's
        // copy; an unrouted host file (mine.txt, my-notes.txt) and an unedited SEED (stop.bat) get none.
        List<String> k = List.of(kLine("cc-server.properties"), kLine("serve.bat"));
        require(second.notices().equals(k), "the re-run's K lines are no longer exactly the two edited SEED files"
                + " (K law: kept AND differs): " + second.notices());
    }

    private static void deleteScope(Path dir) throws Exception {
        Map<String, byte[]> run1Classes = new LinkedHashMap<>();
        run1Classes.put("a/B.class", ascii("class-b"));
        run1Classes.put(FOLDED_REL, FOLDED);
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, run1Classes));
        Path base = stage("base zip", () -> base(fx, true));
        Path pkg = dir.resolve("srv");
        writeOk("SOURCE run 1", fx.source(pkg, fx.classes(), base), fx);
        PackageManifest before = manifest(pkg);
        require(before.sha256ByPath.containsKey("classes/a/B.class") && before.sha256ByPath.containsKey(
                "THIRD-PARTY-NOTICES.txt"), "SOURCE run 1 no longer lists classes/a/B.class and THIRD-PARTY-NOTICES.txt");

        LinkedHashMap<String, byte[]> host = new LinkedHashMap<>();
        host.put("classes/host.txt", ascii("a host file inside an own tree, never listed\n"));
        host.put("match/mine.txt", ascii("version=1\nseat.1.token=REDACTED-mine\n"));
        stage("host drops", () -> PackageZipSelftest.tree(pkg, host));
        Path run2Classes = stage("run-2 classes", () -> PackageZipSelftest.tree(dir.resolve("classes-2"),
                Map.of(FOLDED_REL, FOLDED)));
        Path base2 = stage("run-2 base without THIRD-PARTY-NOTICES.txt", () -> base(fx, false));
        Written second = writeOk("SOURCE run 2", fx.source(pkg, run2Classes, base2), fx);

        require(second.deletes().equals(List.of("classes/a/B.class")), "the planned deletes are no longer exactly the"
                + " previously listed own-tree file the build dropped: " + second.deletes());
        require(!Files.exists(pkg.resolve("classes/a/B.class")), "classes/a/B.class survived run 2 (the delete scope"
                + " narrowed to nothing)");
        require(!Files.exists(pkg.resolve("classes/a")), "the emptied classes/a folder was left behind");
        for (String keep : host.keySet()) {
            require(Arrays.equals(Files.readAllBytes(pkg.resolve(keep)), host.get(keep)), keep + " was deleted or changed"
                    + " (the delete scope widened past previously-listed files)");
        }
        require(Arrays.equals(Files.readAllBytes(pkg.resolve("THIRD-PARTY-NOTICES.txt")), NOTICES),
                "THIRD-PARTY-NOTICES.txt (listed by run 1, outside the own trees) was deleted (the delete scope widened"
                        + " past the own trees)");
        PackageManifest after = manifest(pkg);
        require(!after.sha256ByPath.containsKey("classes/a/B.class") && !after.sha256ByPath.containsKey(
                "THIRD-PARTY-NOTICES.txt"), "run 2's manifest still lists a file run 2 did not write");
        require(PackageManifest.firstMismatch(pkg, after) == null, "run 2's folder fails its own manifest");
    }

    private static void selfVerify(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        Path base = stage("base zip", () -> base(fx, true));
        Path pkg = dir.resolve("srv");
        String victim = "classes/cc/a/B.class";
        byte[] original = classes().get("cc/a/B.class");
        AtomicReference<String> hookFailed = new AtomicReference<>();
        String thrown = null;
        PackageFolderWriter.BEFORE_SELF_VERIFY.set(folder -> {
            try {
                Files.write(folder.resolve(victim), ascii("tampered between the move and G2"));
            } catch (IOException e) {
                hookFailed.set(e.getClass().getSimpleName());
            }
        });
        try (Plan p = PackageFolderWriter.plan(fx.source(pkg, fx.classes(), base), CLOCK, fx.pins())) {
            require(p.refusals().isEmpty(), "SOURCE: a green fixture is REFUSED: " + p.refusals());
            PackageFolderWriter.write(p);
        } catch (IOException failed) {
            thrown = failed.getMessage();
        } finally {
            PackageFolderWriter.BEFORE_SELF_VERIFY.set(null);
        }
        if (hookFailed.get() != null) {
            throw new SetupFailure("the corrupting hook could not write: " + hookFailed.get());
        }
        String expected = PREFIX + "REFUSED the folder does not match what was just written (" + victim
                + ": sha256 differs) - the folder was left without MANIFEST.txt, so it will not start until this command"
                + " finishes once";
        require(expected.equals(thrown), "a file corrupted before G2 no longer throws R14 + EF naming it (G2 dropped):"
                + " " + PackageManifest.mask(thrown));
        require(!Files.exists(pkg.resolve("MANIFEST.txt")), "MANIFEST.txt survived a failed G2 (the folder would start)");
        require(Files.isRegularFile(pkg.resolve("MANIFEST.failed")) && PackageFolderWriter.madeByThisTool(pkg) != null,
                "no MANIFEST.failed carrying pack.stamp after a failed G2 (the rename dropped)");

        Written again = writeOk("SOURCE after a failed run (MANIFEST.failed = made by this tool, never R3)",
                fx.source(pkg, fx.classes(), base), fx);
        require(again.result().files() > 0 && Files.isRegularFile(pkg.resolve("MANIFEST.txt"))
                && !Files.exists(pkg.resolve("MANIFEST.failed")), "the run after a failure did not finish the folder"
                + " (MANIFEST.txt placed, MANIFEST.failed removed)");
        require(Arrays.equals(Files.readAllBytes(pkg.resolve(victim)), original),
                "the run after a failure did not restore the corrupted listed file");
    }

    private static void deterministic(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        Path base = stage("base zip", () -> base(fx, true));
        writeOk("PACKED a", fx.packed(dir.resolve("packed-a")), fx);
        writeOk("PACKED b", fx.packed(dir.resolve("packed-b")), fx);
        require(Arrays.equals(Files.readAllBytes(dir.resolve("packed-a/MANIFEST.txt")),
                Files.readAllBytes(dir.resolve("packed-b/MANIFEST.txt"))),
                "two PACKED runs with the same inputs wrote different MANIFEST.txt bytes (wall-clock or map-order noise)");
        writeOk("SOURCE a", fx.source(dir.resolve("source-a"), fx.classes(), base), fx);
        writeOk("SOURCE b", fx.source(dir.resolve("source-b"), fx.classes(), base), fx);
        require(Arrays.equals(Files.readAllBytes(dir.resolve("source-a/MANIFEST.txt")),
                Files.readAllBytes(dir.resolve("source-b/MANIFEST.txt"))),
                "two SOURCE runs with the same Clock wrote different MANIFEST.txt bytes (wall-clock or map-order noise)");
        require(CLOCK_STAMP.equals(manifest(dir.resolve("source-a")).meta.get("pack.stamp")),
                "a SOURCE pack.stamp no longer comes from the injected Clock (wall-clock leaked into a row)");
    }

    private static void textLaw(Path dir) throws Exception {
        Set<String> tails = Set.of(PackageFolderWriter.EF, PackageFolderWriter.MANIFEST_LEFT);
        List<String> templates = PackageFolderWriter.texts();
        for (String t : templates) {
            textOk("text template", t, tails.contains(t) ? " - " : PREFIX, dir);
        }
        textOk("R14 + EF", PackageFolderWriter.fmt(PackageFolderWriter.R14, "classes/a/B.class", "sha256 differs")
                + PackageFolderWriter.EF, PREFIX, dir);

        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        byte[] otherJar = stage("other jar", () -> PackageZipSelftest.jarWithClasses(Map.of(FOLDED_REL,
                ascii("folded-class-v2"))));
        Path badKit = stage("broken kit", () -> PackageZipSelftest.kitZip(fx.root(), otherJar, null,
                kitSidecar("ffffffffffffffffffffffffffffffff", "data/x.csv"), Map.of("CC_Client/data/x.csv", ascii("x,2\n"))));
        // the live-token standing file rides --match (a kit holds none); its sidecar's content half is the bad kit's
        Path liveMatch = stage("a live-token standing pair", () -> standingPair(dir.resolve("live-match"),
                "version=1\nseat.1.token=REDACTED-seat1\nseat.2.token=live-2\n",
                sidecar("ffffffffffffffffffffffffffffffff", "data/x.csv")));
        List<String> parity = refusals(fx.packed(dir.resolve("out-kit"), fx.hostFiles(), badKit, liveMatch), fx);
        List<String> heads = List.of("REFUSED match/match.txt holds a real seat token",
                "REFUSED the player mod does not match its fingerprint file", "REFUSED this build's mod data differs",
                "REFUSED this build's server classes differ");
        require(parity.size() == 4, "a kit broken four ways no longer prints exactly R4-R7: " + parity);
        for (int i = 0; i < 4; i++) {
            require(parity.get(i).startsWith(PREFIX + heads.get(i)), "R" + (4 + i) + " is no longer printed as the"
                    + " writer's prefixed line: " + PackageManifest.mask(parity.get(i)));
            textOk("R" + (4 + i), parity.get(i), PREFIX, dir);
        }

        Path notZip = stage("not-a-zip base", () -> write(dir.resolve("not-a-package.zip"), ascii("not a zip")));
        List<String> r12 = refusals(fx.source(dir.resolve("out-base"), fx.classes(), notZip), fx);
        require(r12.stream().anyMatch(l -> l.contains(" is not a server package - ")),
                "a non-zip base no longer prints R12: " + r12);
        for (String line : r12) {
            textOk("R12 plan line", line, PREFIX, dir);
        }
        textOk("R13", PREFIX + PackageKitZip.r13(notZip, "jre/x.bin").getMessage(), PREFIX, dir);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int rc;
        try (PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8)) {
            rc = PackageFolderWriter.run(new String[] {"--kind", "nope", "--pkg", "x"}, ps);
        }
        List<String> cli = bos.toString(StandardCharsets.UTF_8).lines().toList();
        require(rc == 2 && cli.size() == 1, "a bad --kind no longer prints ONE refusal line with exit 2: rc=" + rc + " "
                + cli);
        textOk("CLI refusal", cli.get(0), PREFIX, dir);
    }

    private static void produced(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        Path pkg = dir.resolve("out");
        List<String> lines = cli(fx, pkg, true);
        PackageManifest m = manifest(pkg);
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : m.sha256ByPath.entrySet()) {
            rows.add(e.getValue() + "  " + e.getKey());
        }
        require(!rows.isEmpty() && lines.size() == rows.size() + 1 && lines.get(0).startsWith(PREFIX + "wrote "),
                "--produced no longer prints the OK line then one line per manifest row: " + lines.size() + " lines for "
                        + rows.size() + " rows");
        require(lines.subList(1, lines.size()).equals(rows), "--produced drifted from MANIFEST.txt's file rows");
        for (String r : rows) {
            require(r.matches("^[0-9a-f]{64}  \\S.*$"), "a --produced line is not '<64 hex>  <path>': " + r);
        }
        List<String> plain = cli(fx, dir.resolve("out-plain"), false);
        require(plain.size() == 1 && plain.get(0).startsWith(PREFIX + "wrote "),
                "without --produced the CLI no longer prints only the OK line: " + plain.size() + " lines");
    }

    /**
     * {@code pfw-shapes-set}: a 3-shape library - {@code match-kit} (twin-ed, the
     * KIT's match.id AND shape string), {@code match-other} (twin-ed, ANOTHER shape string: the writer dedupes on
     * {@code match.shape}), {@code match-loose} (no twin) - packs to exactly ONE extra
     * pair in {@code match-defaults/}: {@code match-other.txt} = {@code encode(redact(live))} + its sidecar = the kit's RE-KEYED to its id;
     * the kit's twin is deduped, the twin-less one skipped, no {@code -tokens} file ships; {@code MANIFEST.txt}
     * = the same inputs' run WITHOUT {@code --shapes} plus exactly that pair's two rows (the shapes are LISTED).
     * Then the
     * CLI: a library shape fielding a mod variant the kit stripped exits 1 naming the shape, leaves
     * {@code MANIFEST.failed} and no {@code MANIFEST.txt}, and wrote none of its pair; {@code --mod-variants}
     * alone is a plan refusal.
     */
    private static void shapesSet(Path dir) throws Exception {
        Fx fx = stage("Windows fixture", () -> fx(dir, Platform.WINDOWS, classes()));
        MatchDescriptor kitLive = MatchDescriptors.mint("1v1", 6000f, 0.8f);
        MatchDescriptor other = MatchDescriptors.mint("bastion:duel", 7000f, 0.7f);   // not the kit's shape string
        MatchDescriptor loose = MatchDescriptors.mint("1v1", 8000f, 0.6f);
        String composite = ContentFingerprints.composite(md5(fx.jar()), DATA_MD5, HandshakeCodec.HANDSHAKE_VERSION,
                kitLive.matchId());
        // the STANDING server sidecar (match-keyed) rides --match; the kit's own carries no match term, so a re-key
        // read from the kit throws and the pack below fails (the shapes' re-key source is the standing sidecar)
        List<String> standingSidecar = List.of("# standing sidecar (fixture)", "fingerprint=" + composite,
                "file=data/x.csv");
        List<String> kitSidecar = List.of("# kit sidecar (fixture)", "fingerprint="
                + ContentFingerprints.stripMatch(composite), "file=data/x.csv");
        Path kit = stage("a kit holding no match file", () -> PackageZipSelftest.kitZip(fx.root(), fx.jar(), null,
                String.join("\r\n", kitSidecar) + "\r\n", Map.of("CC_Client/data/x.csv", DATA)));
        Path match = stage("the standing pair", () -> standingPair(dir.resolve("standing"),
                MatchDescriptorCodec.encode(MatchDescriptors.redact(kitLive)), String.join("\r\n", standingSidecar) + "\r\n"));
        Path lib = dir.resolve("library");
        stage("library", () -> {
            libraryShape(lib, "match-kit", kitLive, true);
            libraryShape(lib, "match-other", other, true);
            return libraryShape(lib, "match-loose", loose, false);
        });
        Path plain = dir.resolve("plain");
        Path shaped = dir.resolve("shaped");
        stage("empty folders", () -> Files.createDirectories(plain) + "" + Files.createDirectories(shaped));
        writeOk("PACKED without --shapes", fx.packed(plain, fx.hostFiles(), kit, match), fx);
        writeOk("PACKED with --shapes", withShapes(fx.packed(shaped, fx.hostFiles(), kit, match), lib, null), fx);

        List<String> listing;
        try (Stream<Path> s = Files.list(shaped.resolve("match-defaults"))) {
            listing = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        require(listing.equals(List.of("match-other.txt", "match-other.txt.fingerprint", "match.txt",
                "match.txt.fingerprint")), "match-defaults/ is not the kit pair + ONE library pair (flips if the kit's"
                + " twin is not deduped, a twin-less or -tokens file ships, or the set is not written): " + listing);
        require(!Files.exists(shaped.resolve("match")), "a PACKED folder with --shapes holds a match/ folder (flips if"
                + " the shape set is written into the live folder the console offers and Apply writes)");
        String text = Files.readString(shaped.resolve("match-defaults/match-other.txt"), StandardCharsets.UTF_8);
        require(text.equals(MatchDescriptorCodec.encode(MatchDescriptors.redact(other)))
                && MatchDescriptors.isStrictlyRedacted(text.lines().toList())
                && MatchDescriptorCodec.decode(text.lines().toList()).matchId().equals(other.matchId()),
                "match-other.txt is not encode(redact(live)) keeping its match.id (flips if a live token ships or the"
                        + " shape is re-minted)");
        List<String> keyed = Files.readAllLines(shaped.resolve("match-defaults/match-other.txt.fingerprint"),
                StandardCharsets.ISO_8859_1);
        require(keyed.equals(cc.starsector.server.match.ContentFingerprintMint.rekeySidecarLines(standingSidecar,
                other.matchId())), "match-other.txt.fingerprint is not the STANDING sidecar re-keyed to the shape's"
                + " match.id (flips if the sidecar is copied un-keyed or minted elsewhere): " + keyed);
        // the shape rows are LISTED: the shaped manifest = the plain one + exactly the two library rows
        TreeSet<String> plainRows = new TreeSet<>(manifest(plain).sha256ByPath.keySet());
        TreeSet<String> shapedRows = new TreeSet<>(manifest(shaped).sha256ByPath.keySet());
        TreeSet<String> added = new TreeSet<>(shapedRows);
        added.removeAll(plainRows);
        require(shapedRows.containsAll(plainRows) && added.equals(new TreeSet<>(List.of(
                "match-defaults/match-other.txt", "match-defaults/match-other.txt.fingerprint"))),
                "the --shapes manifest is not the plain one + the two match-defaults/match-other rows (flips if a shipped"
                        + " shape goes unlisted, or the shape set drops or adds a route row): added " + added);

        // the belt through the CLI: a shape fielding a mod variant the kit's file= rows do not ship
        MatchDescriptor belted = MatchDescriptors.mint("bastion:duel", 9000f, 0.5f);   // ships (not the kit's shape), so the belt decides
        String strippedId = belted.roster().get(0).variantId();
        Path lib2 = dir.resolve("library-belt");
        Path mods = dir.resolve("mod-variants");
        Path failed = dir.resolve("failed");
        stage("belt fixtures", () -> {
            libraryShape(lib2, "match-belt", belted, true);
            write(mods.resolve("sub/" + strippedId + ".variant"), ascii("{}"));
            return Files.createDirectories(failed);
        });
        List<String> args = List.of("--kind", "packed", "--pkg", failed.toString(), "--classes", fx.classes().toString(),
                "--premain", fx.premain().toString(), "--holder", fx.holder().toString(), "--c2", fx.c2().toString(),
                "--client-mod", fx.mod().toString(), "--kit", kit.toString(), "--argv", fx.argv().toString(), "--jre", fx.jre().toString(), "--host-files",
                fx.hostFiles().toString(), "--platform", "windows", "--git-head", HEAD, "--stamp", STAMP, "--shapes",
                lib2.toString(), "--mod-variants", mods.toString(), "--match", match.toString());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int rc;
        try (PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8)) {
            rc = PackageFolderWriter.run(args.toArray(new String[0]), ps);
        }
        String out = bos.toString(StandardCharsets.UTF_8);
        require(rc == 1 && out.contains("match-belt") && out.contains(strippedId)
                && Files.isRegularFile(failed.resolve("MANIFEST.failed")) && !Files.exists(failed.resolve("MANIFEST.txt"))
                && !Files.exists(failed.resolve("match-defaults/match-belt.txt"))
                && !Files.exists(failed.resolve("match-defaults/match-belt.txt.fingerprint")),
                "a stripped-variant shape no longer refuses the pack naming it (exit 1, MANIFEST.failed, nothing of"
                        + " the shape written) - flips if the roster belt or the failure record regresses: rc=" + rc
                        + " " + PackageManifest.mask(out));
        List<String> alone = refusals(withShapes(fx.packed(dir.resolve("alone"), fx.hostFiles(), kit, match), null, mods),
                fx);
        require(alone.stream().anyMatch(l -> l.contains("--mod-variants without --shapes")),
                "--mod-variants without --shapes is no longer a plan refusal: " + alone);
    }

    /** One library entry: {@code <stem>.txt} = the LIVE text (the library is the source) + a {@code -tokens} twin. */
    private static Path libraryShape(Path lib, String stem, MatchDescriptor live, boolean twin) throws IOException {
        Path f = write(lib.resolve(stem + ".txt"), MatchDescriptorCodec.encode(live).getBytes(StandardCharsets.UTF_8));
        if (twin) {
            write(lib.resolve(stem + "-tokens.txt"), ascii("seat.1.token=fixture\n"));
        }
        return f;
    }

    /** {@code in} with the shipped-shape flags set. */
    private static Inputs withShapes(Inputs in, Path shapes, Path modVariants) {
        return new Inputs(in.kind(), in.pkg(), in.classesDir(), in.premainJar(), in.holderJar(), in.c2File(),
                in.clientModDir(), in.kitZip(), in.baseZip(), in.argvFile(), in.jreDir(), in.mesaDir(), in.nativeDir(),
                in.hostFilesDir(), in.platform(), in.gitHead(), in.kitJarMd5(), in.stamp(), in.produced(), shapes,
                modVariants, in.matchFile());
    }

    /** {@code in} with another {@code --match} ({@code null} = none). */
    private static Inputs withMatch(Inputs in, Path match) {
        return new Inputs(in.kind(), in.pkg(), in.classesDir(), in.premainJar(), in.holderJar(), in.c2File(),
                in.clientModDir(), in.kitZip(), in.baseZip(), in.argvFile(), in.jreDir(), in.mesaDir(), in.nativeDir(),
                in.hostFilesDir(), in.platform(), in.gitHead(), in.kitJarMd5(), in.stamp(), in.produced(), in.shapesDir(),
                in.modVariantsDir(), match);
    }

    // ---- fixtures ----

    /** One PACKED / SOURCE input set (paths under {@code root}); {@code pins} = the natives' sha256 pins;
     *  {@code match} = the standing {@code --match} file (its {@code .fingerprint} beside it). */
    private record Fx(Path root, Path classes, Path premain, Path holder, Path c2, Path mod, Path kit, Path argv,
            Path jre, Path natives, Path hostFiles, Platform platform, byte[] jar, Map<String, String> pins, Path match) {

        Inputs packed(Path pkg) {
            return packed(pkg, hostFiles, kit);
        }

        Inputs packed(Path pkg, Path host, Path kitZip) {
            return packed(pkg, host, kitZip, match);
        }

        Inputs packed(Path pkg, Path host, Path kitZip, Path matchFile) {
            return new Inputs(Kind.PACKED, pkg, classes, premain, holder, c2, mod, kitZip, null, argv, jre, null, natives,
                    host, platform, HEAD, null, STAMP, false, null, null, matchFile);
        }

        /** SOURCE over {@code base}: the build's classes + premain + holder, the C2 file and the kit from the base. */
        Inputs source(Path pkg, Path classesDir, Path base) {
            return new Inputs(Kind.SOURCE, pkg, classesDir, premain, holder, null, null, null, base, null, null, null, null,
                    null, null, null, null, null, false);
        }
    }

    /** What one green write returned plus the plan's K / note lines and deletes. */
    private record Written(Result result, List<String> notices, List<String> deletes) {
    }

    /**
     * The PACKED inputs under {@code dir/in}: the class tree, premain, C2 file, a client-mod tree, a kit
     * zip (its jar folds {@link #FOLDED_REL} byte-equal to the tree's, a REDACTED match, a sidecar whose
     * composite carries the jar's md5 and one {@code file=} row), the mint folder (argv, launcher
     * properties, notices), a JRE image (Windows: {@code java.exe} + the Mesa pair; Linux: {@code java}
     * + {@code jspawnhelper} + {@code target=linux}), the Linux natives with their pins, the host files.
     */
    private static Fx fx(Path dir, Platform pf, Map<String, byte[]> classFiles) throws IOException {
        Path in = dir.resolve("in");
        Path classes = PackageZipSelftest.tree(in.resolve("classes"), classFiles);
        Path premain = write(in.resolve("premain/cc-premain.jar"), ascii("premain-jar"));
        Path holder = write(in.resolve("premain/cc-rngpin-holder.jar"), HOLDER);
        Path c2 = write(in.resolve("c2/c2-directives.json"), C2);
        Path mod = PackageZipSelftest.tree(in.resolve("mod"), Map.of("mod_info.json", MOD_INFO, "data/x.csv", DATA,
                "LICENSE.txt", LICENSE));
        byte[] jar = PackageZipSelftest.jarWithClasses(Map.of(FOLDED_REL, FOLDED));
        Path kit = PackageZipSelftest.kitZip(in, jar, null, kitSidecar(md5(jar), "data/x.csv"),
                Map.of("CC_Client/data/x.csv", DATA));
        Path match = standingPair(in.resolve("standing"), MATCH, sidecar(md5(jar), "data/x.csv"));
        Path mint = PackageZipSelftest.tree(in.resolve("mint"), Map.of("serve.argv", ARGV, "launcher.properties",
                LAUNCHER, "launcher.template.properties", LAUNCHER_TPL, "THIRD-PARTY-NOTICES.txt", NOTICES));
        LinkedHashMap<String, byte[]> jreFiles = new LinkedHashMap<>();
        Path natives = null;
        Map<String, String> pins = PackageFolderWriter.NATIVE_PINS;
        if (pf == Platform.WINDOWS) {
            jreFiles.put("bin/java.exe", ascii("java-exe"));
            jreFiles.put("bin/opengl32.dll", ascii("mesa-opengl"));
            jreFiles.put("bin/libgallium_wgl.dll", ascii("mesa-gallium"));
            jreFiles.put("cc-serve-jre.txt", ascii("implementorVersion=" + JRE_VERSION + "\nsource=/opt/jdk\nbytes=1\n"));
        } else {
            jreFiles.put("bin/java", ascii("java-elf"));
            jreFiles.put("lib/jspawnhelper", ascii("jspawnhelper-elf"));
            jreFiles.put("cc-serve-jre.txt", ascii("implementorVersion=" + JRE_VERSION + "\ntarget=linux\nsource=/opt/jdk\n"));
            LinkedHashMap<String, byte[]> so = new LinkedHashMap<>();
            so.put("liblwjgl64.so", ascii("lwjgl-native"));
            so.put("libjawt.so", ascii("jawt-native"));
            natives = PackageZipSelftest.tree(in.resolve("native"), so);
            LinkedHashMap<String, String> fixturePins = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : so.entrySet()) {
                fixturePins.put(e.getKey(), sha256(e.getValue()));
            }
            pins = fixturePins;
        }
        Path jre = PackageZipSelftest.tree(in.resolve("jre"), jreFiles);
        Path host = PackageZipSelftest.tree(in.resolve("host"), hostFiles(false));
        return new Fx(in, classes, premain, holder, c2, mod, kit, mint.resolve("serve.argv"), jre, natives, host, pf, jar,
                pins, match);
    }

    /** A standing pair under {@code dir}: {@code match.txt} = {@code matchText} + {@code match.txt.fingerprint} =
     *  {@code sidecarText}; returns the match file. */
    private static Path standingPair(Path dir, String matchText, String sidecarText) throws IOException {
        Path match = write(dir.resolve("match.txt"), matchText.getBytes(StandardCharsets.UTF_8));
        write(dir.resolve("match.txt.fingerprint"), sidecarText.getBytes(StandardCharsets.UTF_8));
        return match;
    }

    /** The template, the four bats, the four LF-only sh scripts ({@code serve.sh} with a CR when asked), the README
     *  ({@code update.*} joined the host table). */
    private static LinkedHashMap<String, byte[]> hostFiles(boolean crInServeSh) {
        LinkedHashMap<String, byte[]> h = new LinkedHashMap<>();
        h.put("cc-server.template.properties", ascii("port=0\n"));
        h.put("serve.bat", ascii("@echo serve\r\n"));
        h.put("stop.bat", ascii("@echo stop\r\n"));
        h.put("ops-web.bat", ascii("@echo web\r\n"));
        h.put("update.bat", ascii("@echo update\r\n"));
        h.put("serve.sh", ascii(crInServeSh ? "#!/bin/sh\r\nset -eu\n" : "#!/bin/sh\nset -eu\n"));
        h.put("stop.sh", ascii("#!/bin/sh\necho stop\n"));
        h.put("ops-web.sh", ascii("#!/bin/sh\necho web\n"));
        h.put("update.sh", ascii("#!/bin/sh\necho update\n"));
        // the host README the PACKED kind requires (HOST_WINDOWS / HOST_LINUX).
        h.put("README.md", ascii("fixture readme\n"));
        return h;
    }

    /**
     * A released server package in the OLDER layout (Windows; a release from before the PACKED folder listed every
     * file and moved its standing pair to {@code match-defaults/}): its live-named host files are unlisted SEEDs,
     * every other file listed - a SOURCE run must still route such a base ({@link #newLayoutBase} is today's shape):
     * {@code git.head} = {@link #HEAD}, {@code pack.kit} = {@link #BASE_KIT_NAME}, {@code pack.jre} =
     * {@link #JRE_VERSION}; {@code notices = false} leaves {@code THIRD-PARTY-NOTICES.txt} out.
     */
    private static Path base(Fx fx, boolean notices) throws IOException {
        return base(fx, notices, Map.of());
    }

    /**
     * {@link #base(Fx, boolean)} plus {@code extra} entries (each listed, as every non-SEED base file is), e.g. the
     * release kit zip a package ships at {@code kits/<pack.kit>}.
     *
     */
    private static Path base(Fx fx, boolean notices, Map<String, byte[]> extra) throws IOException {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("jvm/c2-directives.json", C2);
        files.put("jre/bin/java.exe", ascii("java-exe"));
        files.put("jre/cc-serve-jre.txt", ascii("implementorVersion=" + JRE_VERSION + "\n"));
        files.put("serve-mods/CC_Client/mod_info.json", MOD_INFO);
        files.put("serve-mods/CC_Client/data/x.csv", DATA);
        files.put("serve-mods/CC_Client/jars/cc-client-mod.jar", fx.jar());
        files.put("match/match.txt", ascii(MATCH));
        files.put("match/match.txt.fingerprint", ascii(sidecar(md5(fx.jar()), "data/x.csv")));
        files.put("serve-mods/enabled_mods.json", ascii(ENABLED));
        files.put("serve.argv", ARGV);
        files.put("LICENSE.txt", LICENSE);
        if (notices) {
            files.put("THIRD-PARTY-NOTICES.txt", NOTICES);
        }
        files.put("launcher.properties", LAUNCHER);
        files.put("cc-server.properties", ascii("port=0\n"));
        // the README rides the base as a SEED under the shipped name (SEED_RELS).
        for (String h : List.of("serve.bat", "stop.bat", "ops-web.bat", "README.md")) {
            files.put(h, hostFiles(false).get(h));
        }
        files.putAll(extra);
        Set<String> listed = new TreeSet<>(files.keySet());
        listed.removeAll(PackageFolderWriter.SEED_RELS);
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("git.head", HEAD);
        meta.put("kit.jar.md5", md5(fx.jar()));
        meta.put("pack.stamp", "rel-1");
        meta.put("pack.kit", BASE_KIT_NAME);
        meta.put("pack.jre", JRE_VERSION);
        return PackageZipSelftest.baseZip(fx.root(), files, meta, listed);
    }

    /**
     * An owner stage at {@code dir/stage}: {@code classes} and {@code serve-mods} are REAL folders inside
     * it, passed as their own link targets ({@link Files#isSameFile} holds for a folder against itself),
     * {@code serve.argv} minted in place.
     */
    private static Inputs ownerStage(Path dir, Fx fx, Map<String, byte[]> classFiles) throws IOException {
        Path pkg = dir.resolve("stage");
        Path classes = PackageZipSelftest.tree(pkg.resolve("classes"), classFiles);
        LinkedHashMap<String, byte[]> mods = new LinkedHashMap<>();
        mods.put("CC_Client/mod_info.json", MOD_INFO);
        mods.put("CC_Client/data/x.csv", DATA);
        mods.put("CC_Client/jars/cc-client-mod.jar", fx.jar());
        mods.put("enabled_mods.json", ascii(ENABLED));
        PackageZipSelftest.tree(pkg.resolve("serve-mods"), mods);
        write(pkg.resolve("serve.argv"), ARGV);
        return new Inputs(Kind.OWNER_STAGE, pkg, classes, fx.premain(), fx.holder(), fx.c2(),
                pkg.resolve("serve-mods").resolve("CC_Client"), null, null, null, null, null, null, null, null, HEAD,
                md5(fx.jar()), null, false);
    }

    /** The five class files: two shipped (one folded into the kit's jar), one tool, a selftest + its inner. */
    private static Map<String, byte[]> classes() {
        LinkedHashMap<String, byte[]> c = new LinkedHashMap<>();
        c.put("cc/a/B.class", ascii("class-b"));
        c.put(FOLDED_REL, FOLDED);
        c.put("cc/starsector/server/tools/Tool.class", ascii("tool"));
        c.put("cc/a/FooSelftest.class", ascii("selftest"));
        c.put("cc/a/FooSelftest$Inner.class", ascii("selftest-inner"));
        return c;
    }

    /**
     * A STANDING server sidecar in the mint's shape (CRLF): a comment, the composite carrying {@code jarMd5} keyed
     * to {@link #STANDING_LIVE}'s id, one {@code file=} row each. Minted here: {@code PackageZipSelftest}'s twin is
     * private to that class.
     */
    private static String sidecar(String jarMd5, String... fileRows) {
        return sidecarKeyed(jarMd5, STANDING_LIVE.matchId(), fileRows);
    }

    /** {@link #sidecar} keyed to {@code matchId}. */
    private static String sidecarKeyed(String jarMd5, String matchId, String... fileRows) {
        return sidecarText(ContentFingerprints.composite(jarMd5, DATA_MD5, 1, matchId), fileRows);
    }

    /** A KIT's sidecar: the same composite with no match term (a kit carries no match file).
     * */
    private static String kitSidecar(String jarMd5, String... fileRows) {
        return sidecarText(ContentFingerprints.stripMatch(ContentFingerprints.composite(jarMd5, DATA_MD5, 1, "x")),
                fileRows);
    }

    private static String sidecarText(String composite, String... fileRows) {
        StringBuilder sb = new StringBuilder("# content-fingerprint sidecar (fixture)\r\n");
        sb.append("fingerprint=").append(composite).append("\r\n");
        for (String rel : fileRows) {
            sb.append("file=").append(rel).append("\r\n");
        }
        return sb.toString();
    }

    // ---- helpers ----

    /** Plan + write a fixture that must be green; a refusal is a FAIL (a check now refuses what it must pass). */
    private static Written writeOk(String what, Inputs in, Fx fx) throws IOException {
        try (Plan p = PackageFolderWriter.plan(in, CLOCK, fx.pins())) {
            require(p.refusals().isEmpty(), what + ": a green fixture is REFUSED (a check now refuses what it must pass): "
                    + p.refusals());
            List<String> notices = p.notices();
            List<String> deletes = p.deletes();
            return new Written(PackageFolderWriter.write(p), notices, deletes);
        }
    }

    private static List<String> refusals(Inputs in, Fx fx) {
        try (Plan p = PackageFolderWriter.plan(in, CLOCK, fx.pins())) {
            return p.refusals();
        }
    }

    /** Writes, then checks the folder: G2's own question re-asked, the meta order, the parse + render round trip. */
    private static PackageManifest roundtrip(String kind, Inputs in, Fx fx, List<String> metaKeys) throws IOException {
        Written w = writeOk(kind, in, fx);
        Path pkg = in.pkg();
        byte[] bytes = Files.readAllBytes(pkg.resolve("MANIFEST.txt"));
        PackageManifest m = PackageManifest.parse(bytes);
        String miss = PackageManifest.firstMismatch(pkg, m);
        require(miss == null, kind + ": a listed file is not hashed as placed (" + PackageManifest.mask(miss) + ")");
        require(new ArrayList<>(m.meta.keySet()).equals(metaKeys), kind + ": the meta rows are no longer " + metaKeys
                + " in that order: " + m.meta.keySet());
        require(Arrays.equals(PackageManifest.render(new LinkedHashMap<>(m.meta), m.sha256ByPath), bytes),
                kind + ": MANIFEST.txt no longer round-trips byte-equal through parse + render");
        require(!m.sha256ByPath.isEmpty() && m.sha256ByPath.size() == w.result().files(),
                kind + ": Result.files() is no longer the manifest's row count");
        require(md5(fx.jar()).equals(m.meta.get("kit.jar.md5")), kind + ": kit.jar.md5 is no longer the md5 of the jar"
                + " placed / passed");
        return m;
    }

    private static void gateReturns(String kind, Path pkg) {
        try {
            require(OpsRoots.packagedChecked(pkg) != null, kind + ": the packaged gate returned null");
        } catch (IllegalArgumentException refused) {
            throw new AssertionError(kind + " no longer passes the packaged gate (pack.stamp missing): "
                    + PackageManifest.mask(refused.getMessage()));
        }
    }

    /** The CLI in-process ({@link PackageFolderWriter#run} = {@code main} minus its exit on a non-zero code). */
    private static List<String> cli(Fx fx, Path pkg, boolean produced) {
        List<String> args = new ArrayList<>(List.of("--kind", "packed", "--pkg", pkg.toString(), "--classes",
                fx.classes().toString(), "--premain", fx.premain().toString(), "--holder", fx.holder().toString(), "--c2",
                fx.c2().toString(), "--client-mod",
                fx.mod().toString(), "--kit", fx.kit().toString(), "--argv", fx.argv().toString(), "--jre",
                fx.jre().toString(), "--host-files", fx.hostFiles().toString(), "--platform", "windows", "--git-head", HEAD,
                "--stamp", STAMP, "--match", fx.match().toString()));
        if (produced) {
            args.add("--produced");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int rc;
        try (PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8)) {
            rc = PackageFolderWriter.run(args.toArray(new String[0]), ps);
        }
        List<String> lines = bos.toString(StandardCharsets.UTF_8).lines().toList();
        require(rc == 0, "the CLI on a green fixture exited " + rc + ": " + lines);
        return lines;
    }

    /**
     * One printed text against the law: starts with {@code head}, printable ASCII, at most 400 chars,
     * no G1 token. The row's temp folder is replaced first (a fixture path is not the writer's text).
     */
    private static void textOk(String what, String text, String head, Path dir) {
        String t = text.replace(dir.toString(), "<dir>");
        require(t.startsWith(head), what + " no longer starts '" + head + "': " + PackageManifest.mask(t));
        require(t.length() <= 400, what + " is over 400 chars (" + t.length() + "): " + PackageManifest.mask(t));
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            require(c >= 0x20 && c <= 0x7E, what + " holds a non-ASCII or control char at " + i + ": "
                    + PackageManifest.mask(t));
        }
        for (Pattern p : G1) {
            require(!p.matcher(t).find(), what + " carries a G1 token (" + p.pattern() + "): " + PackageManifest.mask(t));
        }
    }

    private static String kLine(String rel) {
        return PREFIX + "kept your " + rel + " - it differs from this build's copy; host files are never overwritten";
    }

    private static PackageManifest manifest(Path pkg) throws IOException {
        return PackageManifest.read(pkg.resolve("MANIFEST.txt"));
    }

    private static List<String> rootNames(Path pkg) throws IOException {
        try (Stream<Path> s = Files.list(pkg)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static Path write(Path p, byte[] bytes) throws IOException {
        Files.createDirectories(p.getParent());
        return Files.write(p, bytes);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String md5(byte[] bytes) {
        return ContentFingerprints.md5Hex(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every Java runtime ships SHA-256", impossible);
        }
    }

    // ---- the row harness (PackageZipSelftest's idiom) ----

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

    private static void row(String name, Body body) {
        Path dir;
        try {
            dir = Files.createTempDirectory("cc-pfw-");
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
