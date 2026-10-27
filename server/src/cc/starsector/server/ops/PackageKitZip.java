package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprintMint;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptors;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The player kit zip as the package-folder writer reads it: the two entries a server folder takes
 * from the kit (the client jar, its file list) and the kit-parity checks every COPY layout re-asserts.
 *A kit carries NO match file: the
 * match rides the server's own standing pair and the wire, so a kit still holding {@link #MATCH_ENTRY}
 * is a pre-wire kit and {@link #read} refuses it; the redaction check runs on the standing match
 * instead ({@link #standingParity}).
 * WHY a Java reader: the parity used to live only in the packer ({@code package-server.ps1}
 * {@code Test-CcPsKitFold} + the sidecar {@code file=} loop), so a source host that never runs the
 * packer could lay out a folder whose kit turns every player away; the writer runs these checks on
 * every layout instead. A kit zip is DATA: every name is checked against the manifest path law
 * ({@link PackageManifest#safeRelative}) before use, every size is counted while inflating (a
 * zip header's declared size is never trusted alone), and every refusal names a masked path or a
 * hash, never a seat token. Caps = the ONE cap set.
 *
 *
 */
public final class PackageKitZip {

    /** The mod folder every kit entry lives under. */
    public static final String MOD_PREFIX = "CC_Client/";
    /** The client jar entry (the folded server classes ride inside it). */
    public static final String JAR_ENTRY = "CC_Client/jars/cc-client-mod.jar";
    /** The match file a PRE-WIRE kit carried; {@link #read} refuses a kit holding it. */
    public static final String MATCH_ENTRY = "CC_Client/match.txt";
    /** The kit's file list, the content-fingerprint sidecar ({@code fingerprint=} composite + {@code file=} rows). */
    public static final String SIDECAR_ENTRY = "CC_Client/kit.files";

    /** The client jar cap, inflated. */
    static final long MAX_JAR_BYTES = 64L * 1024 * 1024;
    /** The sidecar cap. */
    static final long MAX_SIDECAR_BYTES = 1024L * 1024;
    /** A {@code file=} data entry or a folded class, inflated: no single kit entry outgrows the jar cap. */
    static final long MAX_DATA_ENTRY_BYTES = MAX_JAR_BYTES;
    /** All folded classes inflated by one R7 pass: the package-total cap, so a bomb jar stays bounded. */
    static final long MAX_FOLD_INFLATED_BYTES = 1024L * 1024 * 1024;

    private PackageKitZip() {
    }

    /**
     * The two entries a server folder takes from a kit, plus the kit's entry names.
     *
     * @param jar the client jar bytes ({@link #JAR_ENTRY})
     * @param jarMd5 lowercase md5 hex of {@code jar} ({@link ContentFingerprints#md5Hex})
     * @param sidecar the kit's file list bytes ({@link #SIDECAR_ENTRY})
     * @param entryNames every non-directory entry name in the kit, zip order, each a safe relative path
     */
    public record Kit(byte[] jar, String jarMd5, byte[] sidecar, List<String> entryNames) {
    }

    /**
     * Reads the kit's two entries and names. Throws {@link IOException} when the file is not a
     * readable zip; {@link IllegalArgumentException} (the writer's R10 / R13 texts, without its
     * prefix, the kit named) when an entry is missing, an entry inflates past its cap (jar
     * {@value #MAX_JAR_BYTES} B, sidecar {@value #MAX_SIDECAR_BYTES} B), any entry name — directories
     * included — fails the path law or repeats, or the kit still holds {@link #MATCH_ENTRY}
     * ({@link #prewireRefusal}: a kit from before the match moved to the server; its entry is never read).
     */
    public static Kit read(Path kitZip) throws IOException {
        try (ZipFile zf = new ZipFile(kitZip.toFile())) {
            List<String> names = checkedNames(zf, kitZip);
            if (names.contains(MATCH_ENTRY)) {
                throw prewireRefusal(kitZip);
            }
            byte[] jar = required(zf, kitZip, JAR_ENTRY, MAX_JAR_BYTES);
            byte[] sidecar = required(zf, kitZip, SIDECAR_ENTRY, MAX_SIDECAR_BYTES);
            return new Kit(jar, ContentFingerprints.md5Hex(jar), sidecar, List.copyOf(names));
        }
    }

    /**
     * The pre-wire refusal text: the masked kit path and the fixed entry name, never a byte of the entry.
     * WHY refuse rather than ignore: a server folder laid out from such a kit would serve players a
     * kit whose own match file disagrees with the match the server stages.
     *
     */
    static IllegalArgumentException prewireRefusal(Path kitZip) {
        return new IllegalArgumentException("REFUSED " + PackageManifest.mask(kitZip.toString())
                + " is a pre-wire kit (it still holds " + MATCH_ENTRY + ") - make the kit again from this build");
    }

    /**
     * Parity of the STANDING pair (the server folder's own {@code match/match.txt} + its {@code .fingerprint},
     * both non-null bytes) against the kit: the refusal lines in THIS order, at most one each, empty = parity
     * holds (texts = the writer's lines without its prefix):
     * <ol>
     * <li>R4: the match file is not STRICTLY redacted ({@link MatchDescriptors#isStrictlyRedacted});</li>
     * <li>the standing sidecar's composite without its match term ({@link ContentFingerprints#stripMatch}) is
     * not the kit's (no usable {@code fingerprint=} row on either side counts as a differ).</li>
     * </ol>
     * WHY here: a kit holds no match file, so the redaction check the kit parity ran moves to the one match a
     * folder seeds; and the folder's sidecar (the door's expected composite) no longer comes from the kit, so
     * its content half is held to the kit's - else every player holding that kit would be turned away.
     *
     */
    public static List<String> standingParity(byte[] standingMatch, byte[] standingSidecar, Kit kit) {
        List<String> out = new ArrayList<>();
        if (!MatchDescriptors.isStrictlyRedacted(lines(standingMatch))) {
            out.add("REFUSED match/match.txt holds a real seat token - a server folder holds only the public"
                    + " copy (tokens shown as REDACTED-); use the released match file");
        }
        String standing = contentOrNull(lines(standingSidecar));
        if (standing == null || !standing.equals(contentOrNull(lines(kit.sidecar())))) {
            out.add("REFUSED match/match.txt.fingerprint names other content than the kit (another build or mod"
                    + " data) - every player would be turned away; use the kit or server package this server came with");
        }
        return out;
    }

    /**
     * The standing sidecar RE-KEYED to {@code kit}, for a folder laid out from source: the kit's own sidecar lines
     * with every {@code fingerprint=} row set to {@link ContentFingerprints#composite} of the kit composite's
     * {@code jar=}, {@code data=} and {@code hs=} plus the standing composite's {@code match=}, every other kit line
     * (its comments, its {@code file=} rows) as is, rendered by the one sidecar renderer
     * ({@link ContentFingerprintMint#sidecarBytes}). Null = no re-key, and the caller keeps the standing bytes: the
     * content halves already agree ({@link ContentFingerprints#stripMatch}); either side has no usable
     * {@code fingerprint=} row; the standing composite has no match term or the kit's lacks a component; the
     * composite does not compose (a separator, over the cap); or it does not read back as exactly the kit's content
     * half plus that match term. On null {@link #standingParity}'s content refusal is the record.
     * WHY: a folder built from source serves the kit it is given (that kit's jar and mod data), so the door must
     * expect that kit's content; the standing pair only says which match the folder stages. The kit's own
     * {@code file=} rows ride along because a folder's match sidecar is read as the served kit's surface (the shape
     * belt, the shape re-key). A released package and its own kit never differ, so a packed folder never re-keys:
     * its caller refuses instead.
     *
     */
    public static byte[] rekeyedToKitOrNull(byte[] standingSidecar, Kit kit) {
        return rekeyedToKitOrNull(standingSidecar, lines(kit.sidecar()));
    }

    /**
     * {@link #rekeyedToKitOrNull(byte[], Kit)} over the kit side's sidecar LINES (split on line breaks; a terminal
     * empty element is the file's last line break, not a line), every null case the same. WHY a lines form: a packed
     * folder's first start after an update re-keys each host {@code match/*.txt.fingerprint} to the folder's own
     * standing default sidecar, which holds the new kit's content half and {@code file=} rows - there is no kit zip
     * to read at that point ({@link PackageRepair}). Null inputs read as "no usable row" (null).
     *
     */
    public static byte[] rekeyedToKitOrNull(byte[] standingSidecar, List<String> kitSidecarLines) {
        if (standingSidecar == null || kitSidecarLines == null) {
            return null;
        }
        String standing = fingerprintOrNull(lines(standingSidecar));
        List<String> kitLines = new ArrayList<>(kitSidecarLines);
        if (!kitLines.isEmpty() && kitLines.get(kitLines.size() - 1).isEmpty()) {
            kitLines.remove(kitLines.size() - 1); // the terminal line break, not a line
        }
        String kitFp = fingerprintOrNull(kitLines);
        if (standing == null || kitFp == null) {
            return null;
        }
        String content = ContentFingerprints.stripMatch(kitFp).trim();
        if (content.equals(ContentFingerprints.stripMatch(standing).trim())) {
            return null;
        }
        String matchId = ContentFingerprints.componentOrNull(standing, "match");
        String jar = ContentFingerprints.componentOrNull(content, "jar");
        String data = ContentFingerprints.componentOrNull(content, "data");
        String hs = ContentFingerprints.componentOrNull(content, "hs");
        if (matchId == null || jar == null || data == null || hs == null) {
            return null;
        }
        String composed;
        try {
            composed = ContentFingerprints.composite(jar, data, Integer.parseInt(hs), matchId);
        } catch (IllegalArgumentException unfit) {
            return null; // a component that does not compose: standingParity's content line is the record
        }
        if (!content.equals(ContentFingerprints.stripMatch(composed))
                || !matchId.equals(ContentFingerprints.componentOrNull(composed, "match"))) {
            return null;
        }
        List<String> out = new ArrayList<>(kitLines.size());
        for (String line : kitLines) {
            out.add(fingerprintRow(line) ? "fingerprint=" + composed : line);
        }
        byte[] bytes = ContentFingerprintMint.sidecarBytes(out);
        return composed.equals(fingerprintOrNull(lines(bytes))) ? bytes : null;
    }

    /** The parsed {@code fingerprint=} composite ({@link ContentFingerprints#parseSidecarFingerprint}), or null when
     *  the lines carry no usable row. */
    private static String fingerprintOrNull(List<String> sidecarLines) {
        try {
            return ContentFingerprints.parseSidecarFingerprint(sidecarLines, "a sidecar");
        } catch (IllegalArgumentException noFingerprintRow) {
            return null; // the caller's null branch (standingParity's content line) is the record
        }
    }

    /** True for a line {@link ContentFingerprints#parseSidecarFingerprint} reads as a {@code fingerprint=} row: not
     *  blank, not a {@code #} comment, key {@code fingerprint} before the first {@code =} (trimmed).
     * */
    private static boolean fingerprintRow(String line) {
        String t = line.trim();
        int eq = t.indexOf('=');
        return !t.isEmpty() && !t.startsWith("#") && eq > 0 && "fingerprint".equals(t.substring(0, eq).trim());
    }

    /** A sidecar's composite without its match term, trimmed, or null when it carries no usable {@code fingerprint=} row. */
    private static String contentOrNull(List<String> sidecarLines) {
        try {
            return ContentFingerprints.stripMatch(
                    ContentFingerprints.parseSidecarFingerprint(sidecarLines, "a sidecar")).trim();
        } catch (IllegalArgumentException noFingerprintRow) {
            return null;
        }
    }

    /**
     * One entry's bytes, counted while inflating: {@code null} when the kit holds no such entry,
     * {@link IllegalArgumentException} (R13 text) past {@code cap} bytes.
     */
    public static byte[] entry(Path kitZip, String name, long cap) throws IOException {
        try (ZipFile zf = new ZipFile(kitZip.toFile())) {
            ZipEntry e = name == null ? null : zf.getEntry(name);
            if (e == null || e.isDirectory()) {
                return null;
            }
            return inflateCapped(zf, e, cap, kitZip);
        }
    }

    /**
     * Kit parity — the refusal lines in THIS order, at most one each, empty = parity
     * holds (texts = the writer's R5-R7 without its prefix; R4 runs on the standing match,
     * {@link #standingParity}, a kit holding none):
     * <ol>
     * <li>R5: the sidecar composite's {@code jar=} is not {@code kit.jarMd5()} (case-sensitive, as
     * {@code ServeLauncher}'s own sidecar check compares it);</li>
     * <li>R6: a sidecar {@code file=} row whose kit entry {@code CC_Client/<rel>} and
     * {@code builtModDir/<rel>} are not byte-equal (absent either side or a non-safe path counts as a
     * differ; NO {@code file=} row at all is R6 too: the door's digest surface is empty);</li>
     * <li>R7: a {@code .class} entry of the jar whose twin exists under {@code classesDir} and whose
     * md5 differs ({@code Test-CcPsKitFold}'s semantics: a non-safe {@code .class} name counts as a
     * differ, and a jar sharing no class with {@code classesDir} is R7 too).</li>
     * </ol>
     * R7 under another JDK 17 patch than the release's build is a FALSE refusal (javac output can
     * differ); its text names the escape (make a kit from this build). Throws
     * {@link IllegalArgumentException} (R13 text) when a kit entry it reads inflates past its cap.
     */
    public static List<String> parity(Kit kit, Path kitZip, Path builtModDir, Path classesDir) throws IOException {
        List<String> out = new ArrayList<>();
        List<String> sidecarLines = lines(kit.sidecar());
        String jar = sidecarJar(sidecarLines);
        if (jar == null || !jar.equals(kit.jarMd5())) {
            out.add("REFUSED the player mod does not match its fingerprint file (jar=" + PackageManifest.mask(jar)
                    + ", the mod is " + kit.jarMd5() + ") - every player would be turned away; use the kit or"
                    + " server package this server came with");
        }
        String r6 = dataParity(sidecarLines, kitZip, builtModDir);
        if (r6 != null) {
            out.add(r6);
        }
        String r7 = foldParity(kit.jar(), classesDir, kitZip);
        if (r7 != null) {
            out.add(r7);
        }
        return out;
    }

    /**
     * The composite's {@code jar=} component, trimmed, or null when the sidecar carries no usable
     * {@code fingerprint=} row — the R5 line then shows {@code jar=<none>} (the refusal IS the record
     * of the parse failure, the way {@code ServeLauncher}'s own check turns it into R_SIDECAR_JAR).
     */
    private static String sidecarJar(List<String> sidecarLines) {
        String composite;
        try {
            composite = ContentFingerprints.parseSidecarFingerprint(sidecarLines, "the kit sidecar");
        } catch (IllegalArgumentException noFingerprintRow) {
            return null;
        }
        String jar = ContentFingerprints.componentOrNull(composite, "jar");
        return jar == null ? null : jar.trim();
    }

    /** R6 or null: every {@code file=} row byte-equal between the kit and the built mod tree. */
    private static String dataParity(List<String> sidecarLines, Path kitZip, Path builtModDir) throws IOException {
        int rows = 0;
        int differ = 0;
        String first = null;
        Path root = builtModDir.toAbsolutePath().normalize();
        try (ZipFile zf = new ZipFile(kitZip.toFile())) {
            for (String line : sidecarLines) {
                if (!line.startsWith("file=")) {
                    continue;
                }
                rows++;
                String rel = line.substring("file=".length());
                if (!dataRowEqual(zf, kitZip, root, rel)) {
                    differ++;
                    if (first == null) {
                        first = PackageManifest.mask(rel);
                    }
                }
            }
        }
        if (rows == 0) {
            return r6Text(0, "no file= row");
        }
        return differ == 0 ? null : r6Text(differ, first);
    }

    private static boolean dataRowEqual(ZipFile zf, Path kitZip, Path root, String rel) throws IOException {
        if (!PackageManifest.safeRelative(rel)) {
            return false;
        }
        ZipEntry e = zf.getEntry(MOD_PREFIX + rel);
        Path built = root.resolve(rel).normalize();
        if (e == null || e.isDirectory() || !built.startsWith(root) || !Files.isRegularFile(built)) {
            return false;
        }
        byte[] kitBytes = inflateCapped(zf, e, MAX_DATA_ENTRY_BYTES, kitZip);
        return Files.size(built) == kitBytes.length && Arrays.equals(kitBytes, Files.readAllBytes(built));
    }

    private static String r6Text(int n, String first) {
        return "REFUSED this build's mod data differs from the kit's in " + n + " file(s) (first: " + first
                + ") - players with that kit would be turned away; build from unmodified source, or make a kit"
                + " from this build";
    }

    /**
     * R7 or null, {@code Test-CcPsKitFold} in Java: walk the jar's {@code .class} entries; a
     * non-safe name is a differ (it cannot be placed under {@code classesDir}); an entry with no
     * regular-file twin under {@code classesDir} is skipped (a client-only class); a twin-backed
     * entry whose md5 differs — or whose twin vanished since the walk saw it — is a differ.
     * {@code <n> of <m>} = differs of (shared + non-safe). Zero shared classes = R7 (the folded
     * classes are missing from one side), and so is a jar that is not a readable zip. WHY a temp
     * copy read through {@link ZipFile}: the central directory is what the game's class loader and
     * the pwsh twin ({@code ZipArchive}) read, and it lets a skipped entry stay un-inflated (a
     * {@code ZipInputStream} inflates every entry it walks past, so a bomb entry the check never
     * needs would still cost its whole inflation).
     */
    private static String foldParity(byte[] jar, Path classesDir, Path kitZip) throws IOException {
        Path root = classesDir.toAbsolutePath().normalize();
        int shared = 0;
        int unsafe = 0;
        int bad = 0;
        String first = null;
        long inflated = 0;
        Path tmp = Files.createTempFile("cc-kit-jar-", ".jar");
        try {
            Files.write(tmp, jar);
            try (ZipFile zf = new ZipFile(tmp.toFile())) {
                Enumeration<? extends ZipEntry> all = zf.entries();
                while (all.hasMoreElements()) {
                    ZipEntry e = all.nextElement();
                    String rel = e.getName();
                    if (e.isDirectory() || !rel.endsWith(".class")) {
                        continue;
                    }
                    if (!PackageManifest.safeRelative(rel)) {
                        unsafe++;
                        first = first == null ? PackageManifest.mask(rel) : first;
                        continue;
                    }
                    Path twin = root.resolve(rel).normalize();
                    if (!twin.startsWith(root) || !Files.isRegularFile(twin)) {
                        continue;
                    }
                    shared++;
                    MessageDigest md5 = digest("MD5");
                    try (InputStream in = zf.getInputStream(e)) {
                        inflated += digestCapped(in, md5, MAX_DATA_ENTRY_BYTES, MAX_FOLD_INFLATED_BYTES - inflated,
                                kitZip, JAR_ENTRY + "!" + rel);
                    }
                    String entryMd5 = HexFormat.of().formatHex(md5.digest());
                    if (!entryMd5.equals(twinMd5(twin))) {
                        bad++;
                        first = first == null ? PackageManifest.mask(rel) : first;
                    }
                }
            } catch (ZipException | EOFException unreadableJar) {
                // the refusal line is this failure's record: the jar cannot be walked, so no class is proven shared
                return r7Text(bad + unsafe + 1, shared + unsafe + 1, "the player mod jar is not a readable zip");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (bad + unsafe > 0) {
            return r7Text(bad + unsafe, shared + unsafe, first);
        }
        if (shared == 0) {
            return r7Text(0, 0, "no .class file is shared with the built server classes");
        }
        return null;
    }

    /** The twin's md5, or null when it vanished since the walk saw it (a differ, never a throw). */
    private static String twinMd5(Path twin) throws IOException {
        try {
            return ContentFingerprints.md5Hex(Files.readAllBytes(twin));
        } catch (NoSuchFileException vanished) {
            return null;
        }
    }

    private static String r7Text(int n, int m, String first) {
        return "REFUSED this build's server classes differ from the copies inside the player mod (" + n + " of " + m
                + ", first: " + first + ") - players with that kit would run different game code; build the same"
                + " release's source with a JDK 17, or make a kit from this build";
    }

    /** Every name checked (directories too, trailing slash dropped) — the list keeps files only. */
    private static List<String> checkedNames(ZipFile zf, Path kitZip) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Enumeration<? extends ZipEntry> all = zf.entries();
        while (all.hasMoreElements()) {
            ZipEntry e = all.nextElement();
            String name = e.getName();
            String bare = e.isDirectory() ? name.substring(0, name.length() - 1) : name;
            if (!PackageManifest.safeRelative(bare) || !seen.add(name)) {
                throw r13(kitZip, name);
            }
            if (!e.isDirectory()) {
                names.add(name);
            }
        }
        return names;
    }

    private static byte[] required(ZipFile zf, Path kitZip, String name, long cap) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null || e.isDirectory()) {
            throw new IllegalArgumentException("REFUSED kit entry " + name + " not found: "
                    + PackageManifest.mask(kitZip.toString()));
        }
        return inflateCapped(zf, e, cap, kitZip);
    }

    /** The entry's bytes, refused (R13) on a declared size over {@code cap} AND on inflated bytes past it. */
    private static byte[] inflateCapped(ZipFile zf, ZipEntry e, long cap, Path kitZip) throws IOException {
        if (e.getSize() > cap) {
            throw r13(kitZip, e.getName());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, Math.min(e.getSize(), 1 << 20)));
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        try (InputStream in = zf.getInputStream(e)) {
            for (int n; (n = in.read(buf)) != -1; ) {
                total += n;
                if (total > cap) {
                    throw r13(kitZip, e.getName());
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /** Digests the current entry of {@code in}; R13 past {@code cap} or past the pass's {@code budgetLeft}. */
    private static long digestCapped(InputStream in, MessageDigest md, long cap, long budgetLeft, Path kitZip,
            String label) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        for (int n; (n = in.read(buf)) != -1; ) {
            total += n;
            if (total > cap || total > budgetLeft) {
                throw r13(kitZip, label);
            }
            md.update(buf, 0, n);
        }
        return total;
    }

    static IllegalArgumentException r13(Path zip, String entryName) {
        return new IllegalArgumentException("REFUSED " + PackageManifest.mask(zip.toString()) + " entry "
                + PackageManifest.mask(entryName) + " is unsafe, too large, or differs from the package's own file"
                + " list - download the server package again");
    }

    /** The bytes as lines: ISO-8859-1 (never throws; the sidecar is ASCII by mint), split on LF / CRLF. */
    private static List<String> lines(byte[] bytes) {
        return Arrays.asList(new String(bytes, StandardCharsets.ISO_8859_1).split("\r?\n", -1));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every Java runtime ships " + algorithm, impossible);
        }
    }
}
