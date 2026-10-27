package cc.starsector.server.ops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The release package's integrity list {@code <pkg>/MANIFEST.txt} (ASCII, LF): k=v rows
 * ({@code git.head=<hash>}, {@code kit.jar.md5=<32 hex>}) and file rows in the {@code sha256sum}
 * shape ({@code <64 hex sha256>}, TWO spaces, a relative forward-slash path). WHY it exists: a
 * package carries no build tree, no snapshot and no provenance gate, so {@link ServeLauncher}
 * verifies every listed file BEFORE life 1 (exit 3, the first mismatched path named) — the
 * replacement for owner mode's build / snapshot / provenance / serve-mods-sync checks.
 * What is listed is the packager's call; this class checks
 * whatever is listed. {@code kit.jar.md5} is also the expectation the armed door's sidecar
 * {@code jar=} must equal.
 *
 *
 */
public final class PackageManifest {

    /** The k=v row naming the commit the package was cut from. */
    public static final String GIT_HEAD = "git.head";
    /** The k=v row naming the client-mod jar's md5 (lowercase hex) the package was rolled with. */
    public static final String KIT_JAR_MD5 = "kit.jar.md5";
    /** The k=v row naming the release kit zip the package came with (a plain file name under {@code kits/}). */
    public static final String PACK_KIT = "pack.kit";
    /** The k=v row carrying that kit sidecar's full composite (its {@code match=} is the kit's own shape). */
    public static final String KIT_FINGERPRINT = "kit.fingerprint";
    /** A manifest is a few hundred KB at most; past this it is not one (bounded read). */
    static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;

    private static final Pattern FILE_ROW = Pattern.compile("^([0-9a-fA-F]{64})  (.+)$");
    private static final Pattern META_ROW = Pattern.compile("^([a-z][a-z0-9._-]*)=(.*)$");
    private static final Pattern MD5_HEX = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern META_KEY = Pattern.compile("^[a-z][a-z0-9._-]*$");
    private static final Pattern MD5_LOWER = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SHA256_LOWER = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_ROW_CHARS_IN_ERROR = 120;

    /** The k=v rows, in file order (unmodifiable). */
    public final Map<String, String> meta;
    /** Relative forward-slash path to its lowercase sha256 hex, in file order. */
    public final LinkedHashMap<String, String> sha256ByPath;

    private PackageManifest(Map<String, String> meta, LinkedHashMap<String, String> sha256ByPath) {
        this.meta = Collections.unmodifiableMap(meta);
        this.sha256ByPath = sha256ByPath;
    }

    /**
     * The kit rows {@code PackageFolderWriter} emits (PACKED / SOURCE meta), read by the kit packer's
     * verify-before-write: {@link #PACK_KIT},
     * {@link #KIT_FINGERPRINT}, {@link #KIT_JAR_MD5} (lowercased by {@link #parse}); null = the row is absent.
     *
     *
     */
    public String packKit() {
        return meta.get(PACK_KIT);
    }

    public String kitFingerprint() {
        return meta.get(KIT_FINGERPRINT);
    }

    public String kitJarMd5() {
        return meta.get(KIT_JAR_MD5);
    }

    /**
     * Reads {@code manifest} and {@link #parse}s it. A missing file throws
     * {@link java.nio.file.NoSuchFileException} (both callers, {@code OpsRoots.packagedChecked} and
     * {@code ServeLauncher.loadManifest}, name that case apart); an unreadable or over-cap file
     * throws {@link IOException} — the cap is counted on the bytes actually read, so a file that
     * grows between the size check and the read is still refused; a malformed row throws
     * {@link IllegalArgumentException} as {@link #parse} documents.
     *
     *
     */
    public static PackageManifest read(Path manifest) throws IOException {
        if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
            throw new IOException("manifest exceeds " + MAX_MANIFEST_BYTES + " bytes");
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(manifest)) {
            bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
        }
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("manifest exceeds " + MAX_MANIFEST_BYTES + " bytes");
        }
        return parse(bytes);
    }

    /**
     * The grammar {@link #read} applies, over bytes already in hand (the base package zip's own
     * {@code MANIFEST.txt} entry, {@code PackageBaseZip}): ASCII rows split on LF, a trailing CR
     * and blank rows tolerated, meta values trimmed, hex lowercased. Over-cap bytes or a malformed
     * row (neither shape, a duplicate key or path, an absolute or {@code ..} or backslash path, a
     * {@code kit.jar.md5} that is not 32 hex) throw {@link IllegalArgumentException} naming the
     * 1-based line and the row (bounded, ASCII). {@code render(parse(x))} equals {@code x} for
     * every {@code x} that {@link #render} produced.
     *
     *
     */
    public static PackageManifest parse(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("manifest bytes must not be null");
        }
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("manifest exceeds " + MAX_MANIFEST_BYTES + " bytes");
        }
        String text = new String(bytes, StandardCharsets.US_ASCII);
        Map<String, String> meta = new LinkedHashMap<>();
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        String[] rows = text.split("\n", -1);
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].endsWith("\r") ? rows[i].substring(0, rows[i].length() - 1) : rows[i];
            if (row.isBlank()) {
                continue;
            }
            Matcher f = FILE_ROW.matcher(row);
            if (f.matches()) {
                String path = f.group(2);
                if (!safeRelative(path) || files.containsKey(path)) {
                    throw malformed(i, row);
                }
                files.put(path, f.group(1).toLowerCase(java.util.Locale.ROOT));
                continue;
            }
            Matcher k = META_ROW.matcher(row);
            if (!k.matches() || meta.containsKey(k.group(1))) {
                throw malformed(i, row);
            }
            String value = k.group(2).trim();
            if (KIT_JAR_MD5.equals(k.group(1))) {
                if (!MD5_HEX.matcher(value).matches()) {
                    throw malformed(i, row);
                }
                value = value.toLowerCase(java.util.Locale.ROOT);
            }
            meta.put(k.group(1), value);
        }
        return new PackageManifest(meta, files);
    }

    /**
     * The text the packer and the package-folder writer write as {@code MANIFEST.txt}: the meta
     * rows in the caller's map order (the packer passes {@code package-server.ps1}'s
     * {@code $PsMetaOrder}), then one {@code <64 lowercase hex>  <path>} row per file in map order;
     * ASCII, LF, no BOM, an LF after every row. WHY strict: what this writes must {@link #parse}
     * back to the same two maps (row {@code pfw-manifest-roundtrip}), so it REFUSES — an
     * {@link IllegalArgumentException}, bounded ASCII naming the key or the masked path, never a
     * meta value — every input parse would read differently or not at all: a key outside
     * {@code META_ROW}'s key half; a value or path holding a control char, CR, LF or non-ASCII; a
     * value with leading or trailing white space (parse trims it); a path failing
     * {@link #safeRelative}; a hash not 64 lowercase hex or a {@code kit.jar.md5} not 32 lowercase
     * hex (parse lowercases both); a rendering over {@link #MAX_MANIFEST_BYTES} (the writer's R11:
     * the message is that refusal's text, the writer adds its own prefix).
     *
     *
     */
    public static byte[] render(LinkedHashMap<String, String> meta, LinkedHashMap<String, String> sha256ByPath) {
        if (meta == null || sha256ByPath == null) {
            throw new IllegalArgumentException("manifest render: the meta and file maps must not be null");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : meta.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || !META_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("manifest render: meta key " + mask(key)
                        + " is not [a-z][a-z0-9._-]*");
            }
            if (value == null || !printableAscii(value) || !value.equals(value.trim())) {
                throw new IllegalArgumentException("manifest render: the " + key
                        + " value holds a control char, a non-ASCII char or edge white space");
            }
            if (KIT_JAR_MD5.equals(key) && !MD5_LOWER.matcher(value).matches()) {
                throw new IllegalArgumentException("manifest render: " + KIT_JAR_MD5 + " is not 32 lowercase hex");
            }
            sb.append(key).append('=').append(value).append('\n');
            requireUnderCap(sb);
        }
        for (Map.Entry<String, String> e : sha256ByPath.entrySet()) {
            String path = e.getKey();
            String hash = e.getValue();
            if (path == null || !printableAscii(path) || !safeRelative(path)) {
                throw new IllegalArgumentException("manifest render: path " + mask(path)
                        + " is not a safe relative printable-ASCII path");
            }
            if (hash == null || !SHA256_LOWER.matcher(hash).matches()) {
                throw new IllegalArgumentException("manifest render: the hash of " + path + " is not 64 lowercase hex");
            }
            sb.append(hash).append("  ").append(path).append('\n');
            requireUnderCap(sb);
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void requireUnderCap(StringBuilder rendered) {
        if (rendered.length() > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("REFUSED the file list exceeds "
                    + (MAX_MANIFEST_BYTES / (1024 * 1024)) + " MiB");
        }
    }

    private static boolean printableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code s} as bounded printable ASCII for a refusal line: every control or non-ASCII char
     * becomes {@code ?} (package-server.ps1's {@code Format-CcPsName} idiom, widened to non-ASCII)
     * and the text stops at {@value #MAX_ROW_CHARS_IN_ERROR} chars; {@code null} reads
     * {@code <none>}. Shared by the package zip readers so every name they print is one shape.
     *
     *
     */
    static String mask(String s) {
        if (s == null) {
            return "<none>";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX_ROW_CHARS_IN_ERROR; i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return s.length() > MAX_ROW_CHARS_IN_ERROR ? sb + "..." : sb.toString();
    }

    /**
     * The first listed file that is absent or whose sha256 differs, in manifest order:
     * {@code "<path>: missing"} (not a regular file under {@code pkgRoot}),
     * {@code "<path>: unreadable"} (an I/O error while hashing), or
     * {@code "<path>: sha256 differs"}; {@code null} = every listed file present and matching.
     */
    public static String firstMismatch(Path pkgRoot, PackageManifest m) {
        Path root = pkgRoot.toAbsolutePath().normalize();
        for (Map.Entry<String, String> e : m.sha256ByPath.entrySet()) {
            Path file = root.resolve(e.getKey()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return e.getKey() + ": missing";
            }
            String actual;
            try {
                actual = sha256Hex(file);
            } catch (IOException unreadable) {
                return e.getKey() + ": unreadable";
            }
            if (!actual.equals(e.getValue())) {
                return e.getKey() + ": sha256 differs";
            }
        }
        return null;
    }

    /** Lowercase-hex sha256 of one file, streamed. */
    static String sha256Hex(Path file) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every Java runtime ships SHA-256", impossible);
        }
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                sha.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /**
     * A relative forward-slash path that cannot leave the package root. Package-private:
     * the ONE path law the kit and base zip readers apply to entry names.
     */
    static boolean safeRelative(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.endsWith("/")) {
            return false;
        }
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException malformed(int index, String row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length() && sb.length() < MAX_ROW_CHARS_IN_ERROR; i++) {
            char c = row.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return new IllegalArgumentException("manifest line " + (index + 1) + " is malformed: " + sb);
    }
}
