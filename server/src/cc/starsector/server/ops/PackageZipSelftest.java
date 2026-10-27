package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rows for the package-folder writer's INPUT side: the manifest
 * grammar ({@link PackageManifest#render} / {@link PackageManifest#parse}), the strict redaction
 * predicate ({@link MatchDescriptors#isStrictlyRedacted}), the kit zip reader + parity
 * ({@link PackageKitZip}) and the base package reader ({@link PackageBaseZip}). Fixtures only: every
 * zip and tree is built under a {@link Files#createTempDirectory} dir that is deleted in
 * {@code finally}. Prints {@code PASS <row>} / {@code FAIL <row>: <the regression that flips it>} /
 * {@code SETUP-<row>: ...} (a fixture that never staged, never a kill) and exits 0 iff every row
 * passed. The fixture builders are package-private so the writer's rows reuse them.
 * Run: {@code java -cp server/classes cc.starsector.server.ops.PackageZipSelftest}.
 *
 *
 */
public final class PackageZipSelftest {

    private static int failures;

    /** The pinned zip-entry time of every fixture jar ({@link #jarWithClasses}): 2020-01-01T00:00:00Z. */
    static final long FIXTURE_ENTRY_TIME_MILLIS = 1_577_836_800_000L;

    private PackageZipSelftest() {
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** The build gate's entry ({@code OpsSelftests --selftest} chains it): every row, the
     * summary line, the failure count returned (the chain exits 1 on a non-zero count). The row
     * name {@code pfw-manifest-render-roundtrip} is distinct from the writer selftest's
     * {@code pfw-manifest-roundtrip} (the mutant runner keys kills by row name). */
    public static int run() {
        row("pfw-manifest-render-roundtrip", PackageZipSelftest::manifestRoundtrip);
        row("pfw-redacted-match", dir -> redactedMatch());
        row("pfw-kit-zip", PackageZipSelftest::kitZipRow);
        row("kit-prewire-refused", PackageZipSelftest::kitPrewireRefused);
        row("pfw-kit-parity", PackageZipSelftest::kitParity);
        row("pfw-base-zip", PackageZipSelftest::baseZipRow);
        row("pfw-source-rekey", PackageZipSelftest::sourceRekey);
        row("pfw-packed-rekey-refused", PackageZipSelftest::packedRekeyRefused);
        row("pfw-source-rekey-no-row", PackageZipSelftest::sourceRekeyNoRow);
        row("pfw-source-rekey-equal", PackageZipSelftest::sourceRekeyEqual);
        System.out.println(failures == 0 ? "PackageZipSelftest: every row PASS"
                : "PackageZipSelftest: " + failures + " row(s) did not pass");
        return failures;
    }

    // ---- rows ----

    private static void manifestRoundtrip(Path dir) throws Exception {
        String hA = sha256Hex(ascii("a"));
        String hB = sha256Hex(ascii("b"));
        String hC = sha256Hex(ascii("c"));
        String hD = sha256Hex(ascii("d"));
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("git.head", "0123456789abcdef0123456789abcdef01234567");
        meta.put("kit.jar.md5", "0123456789abcdef0123456789abcdef");
        meta.put("pack.stamp", "x");
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        files.put("classes/cc/A.class", hA);
        files.put("classes/cc/A$1.class", hB);
        files.put("serve-mods/CC_Client/data/x y.csv", hC);
        files.put("LICENSE.txt", hD);
        byte[] first = PackageManifest.render(meta, files);
        String expected = "git.head=0123456789abcdef0123456789abcdef01234567\n"
                + "kit.jar.md5=0123456789abcdef0123456789abcdef\n" + "pack.stamp=x\n"
                + hA + "  classes/cc/A.class\n" + hB + "  classes/cc/A$1.class\n"
                + hC + "  serve-mods/CC_Client/data/x y.csv\n" + hD + "  LICENSE.txt\n";
        require(Arrays.equals(first, ascii(expected)), "render no longer writes the meta rows in map order, then"
                + " '<64 hex>  <path>' rows in map order, ASCII LF with a trailing LF");
        PackageManifest parsed = PackageManifest.parse(first);
        require(parsed.meta.equals(meta) && parsed.sha256ByPath.equals(files),
                "parse no longer reads render's rows back to the same two maps");
        byte[] second = PackageManifest.render(new LinkedHashMap<>(parsed.meta), parsed.sha256ByPath);
        require(Arrays.equals(first, second), "render(parse(x)) is no longer byte-equal to x");
        Path onDisk = stage("manifest file", () -> Files.write(dir.resolve("MANIFEST.txt"), first));
        PackageManifest viaRead = PackageManifest.read(onDisk);
        require(Arrays.equals(PackageManifest.render(new LinkedHashMap<>(viaRead.meta), viaRead.sha256ByPath), first),
                "read(Path) no longer parses what render wrote (read must be a bounded read + parse)");

        refused("render of a '..' path", () -> PackageManifest.render(meta, with(files, "classes/../x.class", hA)));
        refused("render of a CR in a value", () -> PackageManifest.render(with(meta, "pack.kit", "a\rb"), files));
        refused("render of a non-ASCII key",
                () -> PackageManifest.render(with(meta, "pack.k" + (char) 0xE9, "v"), files));
        refused("render of a 63-hex hash", () -> PackageManifest.render(meta, with(files, "y.txt", hA.substring(1))));
        refused("render of a kit.jar.md5 that is not 32 lowercase hex",
                () -> PackageManifest.render(with(meta, "kit.jar.md5", "0123456789ABCDEF0123456789ABCDEF"), files));
        String r11 = refused("render past 8 MiB", () -> PackageManifest.render(
                with(meta, "pack.kit", "a".repeat(PackageManifest.MAX_MANIFEST_BYTES)), files));
        require("REFUSED the file list exceeds 8 MiB".equals(r11),
                "the over-cap refusal no longer carries the writer's R11 text: " + PackageManifest.mask(r11));
        refused("parse of over-cap bytes", () -> PackageManifest.parse(new byte[PackageManifest.MAX_MANIFEST_BYTES + 1]));
    }

    private static void redactedMatch() {
        require(MatchDescriptors.isStrictlyRedacted(List.of("version=1", "seat.1.token=REDACTED-seat1",
                "seat.2.token=REDACTED-seat2")), "an all-REDACTED descriptor no longer reads as redacted");
        require(!MatchDescriptors.isStrictlyRedacted(List.of("seat.1.token=REDACTED-seat1", "seat.2.token=live-2")),
                "one live token beside a REDACTED row reads as redacted (the ANY-row test came back)");
        require(!MatchDescriptors.isStrictlyRedacted(List.of("seat.1.token=REDACTED-seat1", "seat.12.token=live-12")),
                "a multi-digit seat token row is no longer a token row (a live seat 12 slips past)");
        require(!MatchDescriptors.isStrictlyRedacted(List.of("version=1", "match.id=m")),
                "a descriptor with zero token rows reads as redacted");
        require(!MatchDescriptors.isStrictlyRedacted(List.of("seat.1.token=redacted-seat1")),
                "a lowercase redacted- value reads as redacted (the prefix test went case-insensitive)");
        require(MatchDescriptors.isStrictlyRedacted(List.of("seat.1.token=   REDACTED-seat1")),
                "a value with leading spaces before REDACTED- no longer reads as redacted (the value is no longer trimmed)");
    }

    private static void kitZipRow(Path dir) throws Exception {
        byte[] jar = stage("jar", () -> jarWithClasses(Map.of("cc/x/Folded.class", ascii("folded"))));
        String sidecar = sidecarText(ContentFingerprints.md5Hex(jar), "data/x.csv");
        LinkedHashMap<String, byte[]> extra = new LinkedHashMap<>();
        extra.put("CC_Client/data/", new byte[0]);
        extra.put("CC_Client/data/x.csv", ascii("x,1"));
        extra.put("CC_Client/mod_info.json", ascii("{}"));
        Path kz = stage("kit zip", () -> kitZip(dir, jar, null, sidecar, extra));
        PackageKitZip.Kit kit = PackageKitZip.read(kz);
        require(Arrays.equals(kit.jar(), jar) && Arrays.equals(kit.sidecar(), ascii(sidecar)),
                "read no longer returns the two entries' bytes");
        require(ContentFingerprints.md5Hex(jar).equals(kit.jarMd5()), "jarMd5 is no longer the jar's lowercase md5");
        require(kit.entryNames().equals(List.of(PackageKitZip.JAR_ENTRY,
                PackageKitZip.SIDECAR_ENTRY, "CC_Client/data/x.csv", "CC_Client/mod_info.json")),
                "entryNames no longer lists every non-directory entry in zip order: " + kit.entryNames());
        require(Arrays.equals(PackageKitZip.entry(kz, "CC_Client/mod_info.json", 64), ascii("{}")),
                "entry() no longer returns one entry's bytes");
        require(PackageKitZip.entry(kz, "CC_Client/absent.txt", 64) == null, "entry() of an absent name no longer reads null");
        refused("entry() past its cap", () -> PackageKitZip.entry(kz, "CC_Client/data/x.csv", 2));

        Path noSidecar = stage("sidecar-less kit", () -> kitZip(dir, jar, null, null, Map.of()));
        String missing = refused("a kit without its sidecar", () -> PackageKitZip.read(noSidecar));
        require(missing.startsWith("REFUSED kit entry " + PackageKitZip.SIDECAR_ENTRY + " not found: "),
                "the missing-sidecar refusal no longer names the entry (R10 shape): " + PackageManifest.mask(missing));

        byte[] bomb = stage("bomb kit bytes", () -> bombKitBytes(PackageKitZip.MAX_JAR_BYTES + 1, sidecar));
        Path honest = stage("honest bomb", () -> Files.write(dir.resolve("bomb-honest.zip"), bomb));
        String declaredOver = refused("a jar declared past 64 MiB", () -> PackageKitZip.read(honest));
        require(declaredOver.contains(" entry " + PackageKitZip.JAR_ENTRY + " is unsafe, too large"),
                "the declared-over-cap jar refusal no longer names the jar entry (R13): " + PackageManifest.mask(declaredOver));
        Path liar = stage("lying bomb", () -> Files.write(dir.resolve("bomb-liar.zip"),
                withDeclaredSize(bomb, PackageKitZip.JAR_ENTRY, 100)));
        stage("lying header took", () -> {
            try (ZipFile zf = new ZipFile(liar.toFile())) {
                if (zf.getEntry(PackageKitZip.JAR_ENTRY).getSize() != 100) {
                    throw new IllegalStateException("the patched central directory size did not read back as 100");
                }
            }
            return null;
        });
        String inflatedOver = refused("a jar declared 100 B that inflates past 64 MiB (sizes not counted while inflating)",
                () -> PackageKitZip.read(liar));
        require(inflatedOver.contains(" entry " + PackageKitZip.JAR_ENTRY + " is unsafe, too large"),
                "the inflated-over-cap jar refusal no longer names the jar entry (R13): " + PackageManifest.mask(inflatedOver));

        Path slip = stage("zip-slip kit", () -> kitZip(dir, jar, null, sidecar, Map.of("../x", ascii("x"))));
        String unsafe = refused("a kit entry named ../x", () -> PackageKitZip.read(slip));
        require(unsafe.contains(" entry ../x is unsafe"), "the unsafe-name refusal no longer names the entry: "
                + PackageManifest.mask(unsafe));
    }

    /**
     * A kit zip still CARRYING {@code CC_Client/match.txt} (a pre-wire kit) is refused by read with the
     * bounded reason (the masked kit path + the fixed entry name, never a byte of the entry), and the same kit
     * without that one entry reads: the refusal keys on the entry alone.
     *
     */
    private static void kitPrewireRefused(Path dir) throws Exception {
        byte[] jar = stage("jar", () -> jarWithClasses(Map.of("cc/x/Folded.class", ascii("folded"))));
        String sidecar = sidecarText(ContentFingerprints.md5Hex(jar), "data/x.csv");
        Map<String, byte[]> data = Map.of("CC_Client/data/x.csv", ascii("x,1"));
        String secret = "live-prewire-7";
        Path prewire = stage("pre-wire kit", () -> kitZip(dir, jar, "version=1\nseat.1.token=" + secret + "\n",
                sidecar, data));
        String why = refused("a kit carrying " + PackageKitZip.MATCH_ENTRY, () -> PackageKitZip.read(prewire));
        String expected = "REFUSED " + PackageManifest.mask(prewire.toString()) + " is a pre-wire kit (it still holds "
                + PackageKitZip.MATCH_ENTRY + ") - make the kit again from this build";
        require(why.equals(expected), "the pre-wire refusal is no longer the bounded reason naming the kit and the"
                + " entry (the check moved, or another refusal fired first): " + PackageManifest.mask(why));
        require(!why.contains(secret), "the pre-wire refusal leaks a byte of the kit's match file");
        Path current = stage("current kit", () -> kitZip(dir, jar, null, sidecar, data));
        require(PackageKitZip.read(current).entryNames().equals(List.of(PackageKitZip.JAR_ENTRY,
                PackageKitZip.SIDECAR_ENTRY, "CC_Client/data/x.csv")),
                "the same kit without the match entry no longer reads (the refusal keys on more than the entry)");
    }

    private static void kitParity(Path dir) throws Exception {
        byte[] folded = ascii("folded-class-v1");
        LinkedHashMap<String, byte[]> classesInJar = new LinkedHashMap<>();
        classesInJar.put("cc/x/Folded.class", folded);
        classesInJar.put("cc/client/OnlyClient.class", ascii("client-only"));
        byte[] jar = stage("jar", () -> jarWithClasses(classesInJar));
        String jarMd5 = ContentFingerprints.md5Hex(jar);
        Path classes = stage("classes", () -> tree(dir.resolve("classes"), Map.of("cc/x/Folded.class", folded)));
        Path built = stage("built mod", () -> tree(dir.resolve("built"), Map.of("data/x.csv", ascii("x,1"))));
        String redacted = "version=1\nseat.1.token=REDACTED-seat1\nseat.2.token=REDACTED-seat2\n";
        Path kz = stage("kit zip", () -> kitZip(dir, jar, null, sidecarText(jarMd5, "data/x.csv"),
                Map.of("CC_Client/data/x.csv", ascii("x,1"))));
        PackageKitZip.Kit kit = PackageKitZip.read(kz);
        List<String> green = PackageKitZip.parity(kit, kz, built, classes);
        require(green.isEmpty(), "a kit matching this build is refused: " + green);

        String r4 = "REFUSED match/match.txt holds a real seat token - a server folder holds only the public copy"
                + " (tokens shown as REDACTED-); use the released match file";
        String content = "REFUSED match/match.txt.fingerprint names other content than the kit (another build or mod"
                + " data) - every player would be turned away; use the kit or server package this server came with";
        String stale = "ffffffffffffffffffffffffffffffff";
        String r5 = "REFUSED the player mod does not match its fingerprint file (jar=" + stale + ", the mod is " + jarMd5
                + ") - every player would be turned away; use the kit or server package this server came with";
        String r6 = "REFUSED this build's mod data differs from the kit's in 1 file(s) (first: data/x.csv) - players"
                + " with that kit would be turned away; build from unmodified source, or make a kit from this build";
        String r7 = "REFUSED this build's server classes differ from the copies inside the player mod (1 of 1, first:"
                + " cc/x/Folded.class) - players with that kit would run different game code; build the same release's"
                + " source with a JDK 17, or make a kit from this build";
        byte[] live = ascii("version=1\nseat.1.token=REDACTED-seat1\nseat.2.token=live-2\n");
        byte[] staleSidecar = ascii(sidecarText(stale, "data/x.csv"));
        Path editedBuilt = stage("edited built mod", () -> tree(dir.resolve("built-edited"), Map.of("data/x.csv", ascii("x,2"))));
        Path otherClasses = stage("other classes", () -> tree(dir.resolve("classes-other"),
                Map.of("cc/x/Folded.class", ascii("folded-class-v2"))));

        // the standing pair (a kit holds no match file): R4 + the content half, held to the kit
        byte[] standingSidecar = ascii(sidecarText(jarMd5, "data/x.csv").replace("fixture-match", "standing-match"));
        List<String> standingGreen = PackageKitZip.standingParity(ascii(redacted), standingSidecar, kit);
        require(standingGreen.isEmpty(), "a redacted standing pair of this kit's content under another match id is"
                + " refused (the match term is no longer stripped before the compare): " + standingGreen);
        expectOnly("R4 (a live seat token in the standing match)", PackageKitZip.standingParity(live, standingSidecar,
                kit), r4);
        expectOnly("the standing sidecar names another build", PackageKitZip.standingParity(ascii(redacted),
                staleSidecar, kit), content);
        expectOnly("the standing sidecar has no fingerprint= row", PackageKitZip.standingParity(ascii(redacted),
                ascii("file=data/x.csv\n"), kit), content);
        require(PackageKitZip.standingParity(live, staleSidecar, kit).equals(List.of(r4, content)),
                "with both standing checks broken the lines are no longer exactly R4 then the content line");
        expectOnly("R5 (sidecar jar= is another build's)", PackageKitZip.parity(withSidecar(kit, staleSidecar), kz, built,
                classes), r5);
        expectOnly("R6 (a file= row differs)", PackageKitZip.parity(kit, kz, editedBuilt, classes), r6);
        expectOnly("R7 (a folded class differs)", PackageKitZip.parity(kit, kz, built, otherClasses), r7);
        expectOnly("R6 (no file= row)", PackageKitZip.parity(withSidecar(kit, ascii(sidecarText(jarMd5))), kz, built,
                classes), "REFUSED this build's mod data differs from the kit's in 0 file(s) (first: no file= row) - players"
                + " with that kit would be turned away; build from unmodified source, or make a kit from this build");
        Path unshared = stage("unshared classes", () -> tree(dir.resolve("classes-unshared"),
                Map.of("cc/other/Z.class", folded)));
        expectOnly("R7 (no shared class)", PackageKitZip.parity(kit, kz, built, unshared),
                "REFUSED this build's server classes differ from the copies inside the player mod (0 of 0, first: no"
                        + " .class file is shared with the built server classes) - players with that kit would run"
                        + " different game code; build the same release's source with a JDK 17, or make a kit from this build");
        List<String> all = PackageKitZip.parity(withSidecar(kit, staleSidecar), kz, editedBuilt, otherClasses);
        require(all.equals(List.of(r5, r6, r7)), "with all three broken the lines are no longer exactly R5, R6, R7"
                + " in that order: " + all);
    }

    // ---- the SOURCE re-key rows: a folder laid out from source for the host's OWN kit ----

    /** The standing match every re-key fixture carries: decodable, strictly REDACTED. */
    private static final MatchDescriptor REKEY_LIVE = MatchDescriptors.mint("1v1", 5000f, 0.9f);
    private static final String REKEY_MATCH = MatchDescriptorCodec.encode(MatchDescriptors.redact(REKEY_LIVE));
    private static final String REKEY_DATA_MD5 = "0123456789abcdef0123456789abcdef";
    private static final byte[] REKEY_FOLDED = ascii("folded-class-v1");
    private static final byte[] REKEY_DATA = ascii("x,1\n");
    /** The two sidecars' first lines differ, so a re-key that keeps the package's lines (or swaps in the kit's when it
     *  must not) flips a byte compare. */
    private static final String KIT_HEADER = "# the host's own kit sidecar (fixture)";
    private static final String PKG_HEADER = "# the package's standing sidecar (fixture)";
    /** The writer's lines, spelled here (not read from the writer) so a reworded line flips the rows. */
    private static final String REKEY_CONTENT_LINE = "package-folder: REFUSED match/match.txt.fingerprint names other"
            + " content than the kit (another build or mod data) - every player would be turned away; use the kit or"
            + " server package this server came with";
    private static final String REKEY_NOTE = "package-folder: note: match/match.txt.fingerprint was re-keyed to the kit"
            + " this folder serves (its jar and mod data), keeping its match - the standing copy named another build's"
            + " content";

    /** One re-key fixture: a released package whose standing pair = {@link #REKEY_MATCH} + {@code standing}, the
     *  host's own kit (a jar of another build than the package's: one extra class, the same folded class and data),
     *  and the build's classes, premain and holder jars. */
    private record RekeyFx(Path base, Path kit, Path classes, Path premain, Path holder, String kitJarMd5,
            String standing) {
        PackageFolderWriter.Inputs source(Path pkg) {
            return new PackageFolderWriter.Inputs(PackageFolderWriter.Kind.SOURCE, pkg, classes, premain, holder, null,
                    null, kit, base, null, null, null, null, null, null, null, null, null, false);
        }
    }

    /** {@link RekeyFx} with the package's standing sidecar = {@code standing.apply(packageJarMd5, kitJarMd5)}.
     * */
    private static RekeyFx rekeyFx(Path dir, BiFunction<String, String, String> standing) throws IOException {
        Path in = dir.resolve("in");
        Path classes = tree(in.resolve("classes"), Map.of("cc/x/Folded.class", REKEY_FOLDED));
        Path premain = tree(in.resolve("premain"), Map.of("cc-premain.jar", ascii("premain-jar"),
                "cc-rngpin-holder.jar", ascii("rngpin-holder-jar")));
        byte[] baseJar = jarWithClasses(Map.of("cc/x/Folded.class", REKEY_FOLDED));
        LinkedHashMap<String, byte[]> own = new LinkedHashMap<>();
        own.put("cc/x/Folded.class", REKEY_FOLDED);
        own.put("cc/client/OwnBuild.class", ascii("the host's own client build"));
        byte[] kitJar = jarWithClasses(own);
        String baseMd5 = ContentFingerprints.md5Hex(baseJar);
        String kitMd5 = ContentFingerprints.md5Hex(kitJar);
        String kitSidecar = rekeySidecar(KIT_HEADER, ContentFingerprints.stripMatch(
                ContentFingerprints.composite(kitMd5, REKEY_DATA_MD5, 1, "x")));
        Path kit = kitZip(in, kitJar, null, kitSidecar, Map.of("CC_Client/data/x.csv", REKEY_DATA));
        String standingText = standing.apply(baseMd5, kitMd5);
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("jvm/c2-directives.json", ascii("[{\"match\": \"*.*\"}]\n"));
        files.put("jre/bin/java.exe", ascii("java-exe"));
        files.put("jre/cc-serve-jre.txt", ascii("implementorVersion=17.0.99+7\n"));
        files.put("serve-mods/CC_Client/mod_info.json", ascii("{\"id\": \"cc_client\", \"name\": \"fixture\"}\n"));
        files.put("serve-mods/CC_Client/data/x.csv", REKEY_DATA);
        files.put("serve-mods/CC_Client/jars/cc-client-mod.jar", baseJar);
        files.put("match/match.txt", ascii(REKEY_MATCH));
        files.put("match/match.txt.fingerprint", ascii(standingText));
        files.put("serve-mods/enabled_mods.json", ascii("{\"enabledMods\": [\"cc_client\"]}"));
        files.put("serve.argv", ascii("-Xmx1g\n"));
        files.put("LICENSE.txt", ascii("fixture licence\n"));
        files.put("THIRD-PARTY-NOTICES.txt", ascii("fixture notices\n"));
        files.put("launcher.properties", ascii("launcher=1\n"));
        files.put("cc-server.properties", ascii("port=0\n"));
        files.put("serve.bat", ascii("@echo serve\r\n"));
        files.put("stop.bat", ascii("@echo stop\r\n"));
        files.put("ops-web.bat", ascii("@echo web\r\n"));
        // the base's shipped root README (a SEED).
        files.put("README.md", ascii("fixture readme\n"));
        Set<String> listed = new TreeSet<>(files.keySet());
        listed.removeAll(PackageFolderWriter.SEED_RELS);
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("git.head", "0123456789abcdef0123456789abcdef01234567");
        meta.put("kit.jar.md5", baseMd5);
        meta.put("pack.stamp", "rel-1");
        meta.put("pack.kit", "cc-kit-rel-1.zip");
        meta.put("pack.jre", "17.0.99+7");
        Path base = baseZip(in, files, meta, listed);
        return new RekeyFx(base, kit, classes, premain.resolve("cc-premain.jar"), premain.resolve("cc-rngpin-holder.jar"),
                kitMd5, standingText);
    }

    /** A sidecar in the mint's CRLF shape: {@code header}, {@code fingerprint=<composite>}, one {@code file=} row. */
    private static String rekeySidecar(String header, String composite) {
        return header + "\r\nfingerprint=" + composite + "\r\nfile=data/x.csv\r\n";
    }

    /** The package's standing sidecar keyed to {@link #REKEY_LIVE} over the jar md5 {@code jarMd5}. */
    private static String packageSidecar(String jarMd5) {
        return rekeySidecar(PKG_HEADER, ContentFingerprints.composite(jarMd5, REKEY_DATA_MD5, 1, REKEY_LIVE.matchId()));
    }

    /**
     * SOURCE + the host's OWN kit over a package whose standing sidecar names the package's build: the plan is green
     * with exactly one re-key note, the folder's {@code match/match.txt.fingerprint} = the KIT's sidecar lines with
     * the composite = the kit's jar / data / hs + the package's match term, {@code match/match.txt} = the package's
     * byte-identical, and MANIFEST {@code kit.fingerprint} = the re-keyed composite.
     *
     */
    private static void sourceRekey(Path dir) throws Exception {
        RekeyFx fx = stage("re-key fixture (standing content of another build)",
                () -> rekeyFx(dir, (baseMd5, kitMd5) -> packageSidecar(baseMd5)));
        Path pkg = dir.resolve("source");
        List<String> notices;
        try (PackageFolderWriter.Plan p = PackageFolderWriter.plan(fx.source(pkg))) {
            require(p.refusals().isEmpty(), "SOURCE + the host's own kit over a package of another build is REFUSED"
                    + " (flips if the standing sidecar is no longer re-keyed to the given kit): " + p.refusals());
            notices = p.notices();
            PackageFolderWriter.write(p);
        }
        require(notices.stream().filter(REKEY_NOTE::equals).count() == 1, "the re-key no longer prints exactly one"
                + " note (flips if the note is dropped, doubled or reworded): " + notices);
        String want = ContentFingerprints.composite(fx.kitJarMd5(), REKEY_DATA_MD5, 1, REKEY_LIVE.matchId());
        byte[] got = Files.readAllBytes(pkg.resolve("match/match.txt.fingerprint"));
        require(Arrays.equals(got, ascii(rekeySidecar(KIT_HEADER, want))), "the folder's match sidecar is not the kit's"
                + " sidecar keyed to the package's match (flips if the re-key keeps the package's jar=, drops or invents"
                + " the match term, or keeps the package's lines): " + new String(got, StandardCharsets.ISO_8859_1));
        require(Arrays.equals(Files.readAllBytes(pkg.resolve("match/match.txt")), ascii(REKEY_MATCH)),
                "the folder's match/match.txt is not the package's byte-identical (flips if the re-key rewrites the"
                        + " match file)");
        String meta = PackageManifest.read(pkg.resolve("MANIFEST.txt")).meta.get("kit.fingerprint");
        require(want.equals(meta), "MANIFEST kit.fingerprint is not the re-keyed composite (flips if the meta reads the"
                + " standing bytes from before the re-key): " + meta);
    }

    /**
     * PACKED + the same kit and the same standing pair (as {@code --match}): still REFUSED with the content line
     * verbatim and no re-key note - a package and its kit come as one release, so a differ is a wrong pairing.
     *
     */
    private static void packedRekeyRefused(Path dir) throws Exception {
        RekeyFx fx = stage("re-key fixture (standing content of another build)",
                () -> rekeyFx(dir, (baseMd5, kitMd5) -> packageSidecar(baseMd5)));
        Path match = stage("the standing pair as --match", () -> tree(dir.resolve("standing"),
                Map.of("match.txt", ascii(REKEY_MATCH), "match.txt.fingerprint", ascii(fx.standing())))).resolve("match.txt");
        Path pkg = dir.resolve("packed");
        PackageFolderWriter.Inputs in = new PackageFolderWriter.Inputs(PackageFolderWriter.Kind.PACKED, pkg,
                fx.classes(), fx.premain(), fx.holder(), null, null, fx.kit(), null, null, null, null, null, null, null,
                null, null, null, false, null, null, match);
        try (PackageFolderWriter.Plan p = PackageFolderWriter.plan(in)) {
            require(p.refusals().contains(REKEY_CONTENT_LINE), "PACKED over a kit of another build no longer refuses"
                    + " with the content line verbatim (flips if the re-key widens to PACKED, or the line is reworded): "
                    + p.refusals());
            require(!p.notices().contains(REKEY_NOTE), "PACKED printed the re-key note (flips if PACKED re-keys)");
        }
        require(!Files.exists(pkg), "a refused PACKED plan created the folder");
    }

    /**
     * SOURCE over a standing sidecar with no usable {@code fingerprint=} row: exactly the content refusal, no note,
     * nothing written - the re-key never invents a match term.
     *
     */
    private static void sourceRekeyNoRow(Path dir) throws Exception {
        RekeyFx fx = stage("re-key fixture (no fingerprint= row)",
                () -> rekeyFx(dir, (baseMd5, kitMd5) -> PKG_HEADER + "\r\nfile=data/x.csv\r\n"));
        Path pkg = dir.resolve("source");
        try (PackageFolderWriter.Plan p = PackageFolderWriter.plan(fx.source(pkg))) {
            require(p.refusals().equals(List.of(REKEY_CONTENT_LINE)), "SOURCE over a standing sidecar with no"
                    + " fingerprint= row no longer refuses with exactly the content line (flips if the re-key invents a"
                    + " match term or composes from the kit alone): " + p.refusals());
            require(!p.notices().contains(REKEY_NOTE), "a standing sidecar with no fingerprint= row printed the re-key"
                    + " note (flips if such a sidecar is re-keyed)");
        }
        require(!Files.exists(pkg), "a refused SOURCE plan created the folder");
    }

    /**
     * SOURCE over a standing sidecar already of the kit's content: green, no note, and the folder's sidecar = the
     * package's byte-identical (its own lines kept).
     *
     */
    private static void sourceRekeyEqual(Path dir) throws Exception {
        RekeyFx fx = stage("re-key fixture (standing content = the kit's)",
                () -> rekeyFx(dir, (baseMd5, kitMd5) -> packageSidecar(kitMd5)));
        Path pkg = dir.resolve("source");
        try (PackageFolderWriter.Plan p = PackageFolderWriter.plan(fx.source(pkg))) {
            require(p.refusals().isEmpty(), "SOURCE over a standing sidecar of the kit's own content is REFUSED (flips if"
                    + " the content compare no longer strips the match term): " + p.refusals());
            require(!p.notices().contains(REKEY_NOTE), "a standing sidecar already of the kit's content printed the"
                    + " re-key note (flips if the re-key fires on equal content)");
            PackageFolderWriter.write(p);
        }
        require(Arrays.equals(Files.readAllBytes(pkg.resolve("match/match.txt.fingerprint")), ascii(fx.standing())),
                "a standing sidecar already of the kit's content was rewritten (flips if the re-key fires on equal"
                        + " content and swaps the package's lines for the kit's)");
    }

    private static void baseZipRow(Path dir) throws Exception {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("classes/cc/A.class", ascii("class A"));
        files.put("serve.bat", ascii("@echo off"));
        files.put("jre/release", ascii("JAVA_VERSION=17"));
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("git.head", "0123456789abcdef0123456789abcdef01234567");
        meta.put("pack.stamp", "x");
        Set<String> listed = Set.of("classes/cc/A.class", "jre/release");
        Path good = stage("base zip", () -> baseZip(dir, files, meta, listed));
        Path out = dir.resolve("out").resolve("jre").resolve("release");
        try (PackageBaseZip base = PackageBaseZip.open(good)) {
            require("x".equals(base.manifest().meta.get("pack.stamp")) && base.manifest().sha256ByPath.size() == 2,
                    "open no longer exposes the base's own manifest (pack.stamp + the two listed rows)");
            require(base.names().equals(List.of("classes/cc/A.class", "serve.bat", "jre/release", "MANIFEST.txt")),
                    "names() no longer lists every file entry in zip order: " + base.names());
            require(Arrays.equals(base.read("classes/cc/A.class"), files.get("classes/cc/A.class"))
                    && Arrays.equals(base.read("serve.bat"), files.get("serve.bat")),
                    "read no longer returns a listed / an unlisted entry's bytes");
            require(base.read("absent.txt") == null, "read of an absent entry no longer reads null");
            base.copyTo("jre/release", out);
            require(Arrays.equals(Files.readAllBytes(out), files.get("jre/release")),
                    "copyTo no longer places the entry's bytes under created parent dirs");
            boolean overwrote = true;
            try {
                base.copyTo("jre/release", out);
            } catch (FileAlreadyExistsException expected) {
                overwrote = false;
            }
            require(!overwrote, "copyTo onto an existing file did not throw FileAlreadyExistsException (CREATE_NEW dropped)");
            require(Arrays.equals(Files.readAllBytes(out), files.get("jre/release")),
                    "the refused overwrite changed or removed the existing file");
            refused("copyTo of MANIFEST.txt", () -> {
                base.copyTo("MANIFEST.txt", dir.resolve("out").resolve("MANIFEST.txt"));
                return null;
            });
        }

        Path stampless = stage("stampless base", () -> baseZip(dir, files, with(meta, "pack.stamp", ""), listed));
        expectR12("a base with a blank pack.stamp", stampless);
        Path noManifest = stage("manifest-less base", () -> baseZip(dir, files, (byte[]) null));
        expectR12("a base without MANIFEST.txt", noManifest);
        Path badManifest = stage("unparseable base", () -> baseZip(dir, files, ascii("not a manifest row\n")));
        expectR12("a base whose MANIFEST.txt does not parse", badManifest);

        Path slip = stage("zip-slip base", () -> baseZip(dir, with(files, "../x", ascii("x")), meta, listed));
        expectR13("a base entry named ../x", slip, "../x");

        LinkedHashMap<String, String> offRows = new LinkedHashMap<>();
        offRows.put("classes/cc/A.class", sha256Hex(ascii("not class A")));
        offRows.put("jre/release", sha256Hex(files.get("jre/release")));
        Path off = stage("off-row base", () -> baseZip(dir, files, PackageManifest.render(meta, offRows)));
        Path offTarget = dir.resolve("off").resolve("classes").resolve("cc").resolve("A.class");
        try (PackageBaseZip base = PackageBaseZip.open(off)) {
            String onRead = refused("read of a listed entry whose bytes are off its own row (the own-row hash check is gone)",
                    () -> base.read("classes/cc/A.class"));
            require(onRead.contains(" entry classes/cc/A.class is unsafe, too large, or differs from the package's own file list"),
                    "the off-row read refusal no longer carries R13 naming the entry: " + PackageManifest.mask(onRead));
            refused("copyTo of a listed entry whose bytes are off its own row", () -> {
                base.copyTo("classes/cc/A.class", offTarget);
                return null;
            });
            require(!Files.exists(offTarget), "a refused copyTo left its partial target behind");
        }

        // count-while-inflating, at kilobyte caps through the same inflate path the N3 caps use: every blob's
        // header declares 1 B, so only the bytes counted while inflating can refuse
        LinkedHashMap<String, byte[]> blobs = new LinkedHashMap<>();
        blobs.put("blob/big", new byte[4096]);
        blobs.put("blob/a", new byte[600]);
        blobs.put("blob/b", new byte[600]);
        LinkedHashMap<String, String> stampOnly = new LinkedHashMap<>();
        stampOnly.put("pack.stamp", "x");
        int manifestBytes = PackageManifest.render(stampOnly, new LinkedHashMap<>()).length;
        Path lying = stage("lying-header base", () -> {
            byte[] z = Files.readAllBytes(baseZip(dir, blobs, stampOnly, Set.of()));
            for (String n : blobs.keySet()) {
                z = withDeclaredSize(z, n, 1);
            }
            return Files.write(dir.resolve("lying.zip"), z);
        });
        Path lyingTarget = dir.resolve("lying").resolve("big");
        try (PackageBaseZip base = PackageBaseZip.open(lying, 1024, 1 << 20)) {
            refused("read of an entry declared 1 B that inflates past its cap (sizes not counted while inflating)",
                    () -> base.read("blob/big"));
            refused("copyTo of an entry declared 1 B that inflates past its cap", () -> {
                base.copyTo("blob/big", lyingTarget);
                return null;
            });
            require(!Files.exists(lyingTarget), "a copyTo refused mid-inflate left its partial target behind");
        }
        try (PackageBaseZip base = PackageBaseZip.open(lying, 1024, manifestBytes + 1000L)) {
            require(base.read("blob/a").length == 600 && base.read("blob/a").length == 600,
                    "a re-read of one entry is counted twice against the running total");
            refused("read past the running inflate total (the total is no longer re-counted while inflating)",
                    () -> base.read("blob/b"));
        }

        byte[] goodBytes = stage("good base bytes", () -> Files.readAllBytes(good));
        Path bigDeclared = stage("over-256-MiB base", () -> Files.write(dir.resolve("big.zip"),
                withDeclaredSize(goodBytes, "serve.bat", PackageBaseZip.MAX_ENTRY_BYTES + 1)));
        expectR13("a base entry declared past 256 MiB", bigDeclared, "serve.bat");

        // four pads declared at exactly the per-entry cap (each one passes alone); the three small files ahead of
        // them put the running declared total past 1 GiB at the fourth pad
        LinkedHashMap<String, byte[]> padded = new LinkedHashMap<>(files);
        for (int i = 0; i < 4; i++) {
            padded.put("pad/" + i, new byte[0]);
        }
        Path overTotal = stage("over-1-GiB base", () -> {
            byte[] z = Files.readAllBytes(baseZip(dir, padded, meta, listed));
            for (int i = 0; i < 4; i++) {
                z = withDeclaredSize(z, "pad/" + i, PackageBaseZip.MAX_ENTRY_BYTES);
            }
            return Files.write(dir.resolve("total.zip"), z);
        });
        expectR13("a base whose declared sizes total past 1 GiB (each entry at its cap)", overTotal, "pad/3");

        LinkedHashMap<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i <= PackageBaseZip.MAX_ENTRIES; i++) {
            many.put("n/" + i, new byte[0]);
        }
        Path tooMany = stage("over-20000-entry base", () -> baseZip(dir, many, meta, Set.of()));
        expectR13("a base with 20001 entries", tooMany, "#20001");
    }

    // ---- fixture builders (package-private: the writer's rows reuse them) ----

    /**
     * Writes a kit zip under {@code dir} (a fresh {@code kit-*.zip}) holding, in this order,
     * {@link PackageKitZip#JAR_ENTRY} = {@code jar}, {@link PackageKitZip#MATCH_ENTRY} = {@code match}
     * (UTF-8; a non-null match builds a PRE-WIRE kit, which {@link PackageKitZip#read} refuses),
     * {@link PackageKitZip#SIDECAR_ENTRY} = {@code sidecar} (UTF-8) — each left OUT when null,
     * so a row can build a kit missing one — then {@code extraEntries} in map order (a name ending
     * {@code /} becomes a directory entry; names are written raw, so {@code ../x} builds a zip-slip
     * fixture). Returns the zip.
     */
    static Path kitZip(Path dir, byte[] jar, String match, String sidecar, Map<String, byte[]> extraEntries)
            throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        if (jar != null) {
            entries.put(PackageKitZip.JAR_ENTRY, jar);
        }
        if (match != null) {
            entries.put(PackageKitZip.MATCH_ENTRY, match.getBytes(StandardCharsets.UTF_8));
        }
        if (sidecar != null) {
            entries.put(PackageKitZip.SIDECAR_ENTRY, sidecar.getBytes(StandardCharsets.UTF_8));
        }
        if (extraEntries != null) {
            entries.putAll(extraEntries);
        }
        return writeZip(Files.createTempFile(dir, "kit-", ".zip"), entries);
    }

    /**
     * Writes a base package zip under {@code dir} (a fresh {@code base-*.zip}): {@code files} in map
     * order, then a CORRECT {@code MANIFEST.txt} entry = {@link PackageManifest#render} of {@code meta}
     * plus one sha256 row for each file named in {@code listed} (map order). Returns the zip.
     */
    static Path baseZip(Path dir, LinkedHashMap<String, byte[]> files, LinkedHashMap<String, String> meta,
            Set<String> listed) throws IOException {
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (listed.contains(e.getKey())) {
                rows.put(e.getKey(), sha256Hex(e.getValue()));
            }
        }
        return baseZip(dir, files, PackageManifest.render(meta, rows));
    }

    /**
     * Writes a base package zip under {@code dir}: {@code files} in map order, then {@code manifest}
     * as the {@code MANIFEST.txt} entry verbatim (null = no manifest entry) — the raw form for a
     * stampless, unparseable or off-row fixture. Returns the zip.
     */
    static Path baseZip(Path dir, LinkedHashMap<String, byte[]> files, byte[] manifest) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(files);
        if (manifest != null) {
            entries.put(PackageBaseZip.MANIFEST_ENTRY, manifest);
        }
        return writeZip(Files.createTempFile(dir, "base-", ".zip"), entries);
    }

    /**
     * A jar (zip) image holding {@code classesByPath} (entry name to bytes), DEFLATED, map order. Entry
     * times are PINNED: an unset {@link ZipEntry} time is the wall clock at 2 s resolution, so two
     * calls with the same classes straddling a boundary gave two md5s — {@code pfw-base-shapes}
     * keys its sidecar to one call's jar and packs another's, and failed once under box load
     * (the suite beside a four-lane fleet). The same bytes in = the same jar out, always.
     */
    static byte[] jarWithClasses(Map<String, byte[]> classesByPath) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : classesByPath.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(FIXTURE_ENTRY_TIME_MILLIS);
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Writes {@code files} ({@code /}-separated relative path to bytes) under {@code dir}, parents created; returns {@code dir}. */
    static Path tree(Path dir, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(dir);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path p = dir;
            for (String seg : e.getKey().split("/")) {
                p = p.resolve(seg);
            }
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        return dir;
    }

    /**
     * {@code zip} with the central-directory UNCOMPRESSED size of {@code entryName} rewritten to
     * {@code declared} (the header lies; the data is untouched) — the fixture for "sizes counted
     * while inflating, never the header alone" and for a declared-over-cap entry without the bytes.
     * Walks the end record's directory (never a signature scan through entry data).
     */
    static byte[] withDeclaredSize(byte[] zip, String entryName, long declared) {
        if (declared < 0 || declared >= 0xFFFFFFFFL) {
            throw new IllegalArgumentException("declared size must fit a non-zip64 header");
        }
        byte[] out = zip.clone();
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = out.length - 22; i >= 0; i--) {
            if (b.getInt(i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IllegalArgumentException("no end-of-central-directory record");
        }
        int count = Short.toUnsignedInt(b.getShort(eocd + 10));
        int pos = b.getInt(eocd + 16);
        for (int n = 0; n < count; n++) {
            if (b.getInt(pos) != 0x02014b50) {
                throw new IllegalArgumentException("central directory record " + n + " has no signature");
            }
            int nameLen = Short.toUnsignedInt(b.getShort(pos + 28));
            int extraLen = Short.toUnsignedInt(b.getShort(pos + 30));
            int commentLen = Short.toUnsignedInt(b.getShort(pos + 32));
            String name = new String(out, pos + 46, nameLen, StandardCharsets.UTF_8);
            if (name.equals(entryName)) {
                b.putInt(pos + 24, (int) declared);
                return out;
            }
            pos += 46 + nameLen + extraLen + commentLen;
        }
        throw new IllegalArgumentException("no central directory record named " + PackageManifest.mask(entryName));
    }

    // ---- helpers ----

    /** A sidecar in the mint's shape (CRLF, as the shipped one): a comment, the composite, one {@code file=} row each. */
    private static String sidecarText(String jarMd5, String... fileRows) {
        StringBuilder sb = new StringBuilder("# content-fingerprint sidecar (fixture)\r\n");
        sb.append("fingerprint=")
                .append(ContentFingerprints.composite(jarMd5, "0123456789abcdef0123456789abcdef", 1, "fixture-match"))
                .append("\r\n");
        for (String rel : fileRows) {
            sb.append("file=").append(rel).append("\r\n");
        }
        return sb.toString();
    }

    /** A kit zip image whose jar entry is {@code zeros} zero bytes, streamed (never held), plus the sidecar. */
    private static byte[] bombKitBytes(long zeros, String sidecar) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry(PackageKitZip.JAR_ENTRY));
            byte[] chunk = new byte[1 << 20];
            for (long left = zeros; left > 0; left -= chunk.length) {
                zos.write(chunk, 0, (int) Math.min(chunk.length, left));
            }
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(PackageKitZip.SIDECAR_ENTRY));
            zos.write(ascii(sidecar));
            zos.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Path writeZip(Path zip, LinkedHashMap<String, byte[]> entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                if (!e.getKey().endsWith("/")) {
                    zos.write(e.getValue());
                }
                zos.closeEntry();
            }
        }
        return zip;
    }

    private static PackageKitZip.Kit withSidecar(PackageKitZip.Kit k, byte[] sidecar) {
        return new PackageKitZip.Kit(k.jar(), k.jarMd5(), sidecar, k.entryNames());
    }

    private static void expectOnly(String leg, List<String> got, String line) {
        require(got.equals(List.of(line)), leg + " alone no longer yields exactly its one line (a check dropped,"
                + " re-ordered or re-worded): " + got);
    }

    private static void expectR12(String leg, Path zip) {
        String why = refused(leg, () -> {
            PackageBaseZip.open(zip).close();
            return null;
        });
        require(why.equals("REFUSED " + PackageManifest.mask(zip.toString())
                + " is not a server package - pass the cc-server zip released with this source"),
                leg + " no longer refuses with R12: " + PackageManifest.mask(why));
    }

    private static void expectR13(String leg, Path zip, String nameToken) {
        String why = refused(leg, () -> {
            PackageBaseZip.open(zip).close();
            return null;
        });
        require(why.contains(" entry " + nameToken) && why.endsWith(
                "is unsafe, too large, or differs from the package's own file list - download the server package again"),
                leg + " no longer refuses with R13 naming " + nameToken + ": " + PackageManifest.mask(why));
    }

    private static <K, V> LinkedHashMap<K, V> with(Map<K, V> base, K key, V value) {
        LinkedHashMap<K, V> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
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

    private static String refused(String leg, Dial<?> dial) {
        try {
            dial.get();
        } catch (IllegalArgumentException expected) {
            return expected.getMessage();
        } catch (Exception other) {
            throw new AssertionError(leg + ": threw " + other.getClass().getSimpleName()
                    + " instead of a refusal (IllegalArgumentException)");
        }
        throw new AssertionError(leg + " was ACCEPTED (its refusal is gone)");
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
