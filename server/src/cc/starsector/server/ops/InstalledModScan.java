package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cc.starsector.server.runtime.ModSet;

//
/**
 * The mods a serve COULD load: every immediate subdirectory of a {@code mods.roots} root that
 * holds a {@code mod_info.json}, or the root itself when IT holds one — the Mods tab's
 * population ({@link OpsModsPanel}: a GUI way for the server host to enable
 * or disable mods; its own tab, add / remove folders).
 *
 * <p>A Java PORT of the launcher's resolver law in its PowerShell library — the lib
 * stays the authority (it is what actually mirrors the enabled ids before the serve JVM boots):
 * <ul>
 * <li>roots = the {@code mods.roots} row, semicolon-separated, each trimmed, empties dropped,
 *     repo-relative or absolute ({@code Resolve-CcExtraMods}); absent/blank row =
 *     {@link #DEFAULT_ROOTS} ({@code Get-CcDefaultModRoots}, the lib's ONE home for the
 *     default — this constant mirrors it and {@code ServerOpsCoreSelftest} pins the pair);</li>
 * <li>a row is read the lib's {@code Get-CcConfigModsRow} way (trimmed line, {@code #}/{@code !}
 *     comments skipped, key trimmed, LAST row wins) — NOT the shells' line-start-exact grammar;</li>
 * <li>the id / name / version come out of the raw JSON by regex (first match), never a strict
 *     JSON parse: the engine's own parser is lenient (comments, trailing commas), so a strict
 *     parse would refuse real mods ({@code Get-CcModInfoField}). The id regex is the lib's
 *     {@code "id"\s*:\s*"([^"]+)"} verbatim — the scan lists what the LAUNCHER would resolve —
 *     but the captured id must pass the ModSet id law ({@code ModSet.lawfulId}, ≤ {@code
 *     ModSet.MAX_ID_CHARS}) HERE, at scan: the regex alone lets
 *     a newline / {@code ,} / {@code ;} through, and the Mods tab writes the chosen id into
 *     {@code cc-server.properties} — an unlawful id from a foreign {@code mod_info.json} would be
 *     a properties-row injection. Skip-loud (one {@code System.err} line naming the file and the
 *     id's LENGTH, never its text), so the launcher's Start-time refusal
 *     ({@link OpsMatchRows#parseModsEnabled}) stays the second wall, not the first.</li>
 * <li>a {@code mod_info.json} over {@link #MOD_INFO_READ_CAP} bytes is skipped loud, never read
 *     into memory (a real one is a few hundred bytes; the cap bounds a hostile folder).</li>
 * </ul>
 *
 * <p>Fail-soft: an absent root is silently skipped (the lib's law — an absent default root costs
 * nothing); an unreadable root/file or a {@code mod_info.json} without an id is skipped with ONE
 * {@code System.err} line — never a throw (the form must open even over a broken mods folder).
 * Pure static, no Swing: unit-tested over a temp dir in {@link ServerOpsCoreSelftest}.
 */
public final class InstalledModScan {

    /** The {@code cc-server.properties} row naming the roots (the lib's key). */
    public static final String MODS_ROOTS_KEY = "mods.roots";

    /** {@code Get-CcDefaultModRoots} mirrored: in-repo proof mods + pinned third-party releases. */
    public static final List<String> DEFAULT_ROOTS = List.of("local/drills", "generated/foreign-mods");

    /** The file that makes a folder a mod. */
    public static final String MOD_INFO = "mod_info.json";

    /** Largest {@code mod_info.json} the scan reads; larger = skipped loud. */
    public static final int MOD_INFO_READ_CAP = 64 * 1024;

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VERSION_KEY = Pattern.compile("\"version\"\\s*:");
    /** A string version ({@code "version":"1.2.3"}) … */
    private static final Pattern VERSION_TEXT = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"");
    /** … or the object form ({@code "version":{"major":1,"minor":2,"patch":3}}). */
    private static final Pattern VERSION_OBJECT = Pattern.compile("\"version\"\\s*:\\s*\\{([^}]*)\\}");
    private static final Pattern VERSION_PART = Pattern.compile(
            "\"(major|minor|patch)\"\\s*:\\s*\"?([^\",}\\s]+)\"?");

    /** One installed mod: its declared id, display name + version ({@code ""} when undeclared),
     * the folder that holds its {@code mod_info.json} and the {@code mods.roots} root it was found
     * under (= {@code folder} when the root is itself the mod, for {@link OpsModsPanel}). */
    public record InstalledMod(String id, String name, String version, Path folder, Path root) {
    }

    /** Marker kept directly above the {@code mods.roots} row the Mods tab writes (the
     * {@link OpsMatchRows#MODS_ENABLED_COMMENT} idiom). */
    public static final String MODS_ROOTS_COMMENT =
            "# folders the Ops window's Mods tab searches for mods (';'-separated, repo-relative"
                    + " or absolute; a mod's own folder or a folder of mods; a PRESENT row is the"
                    + " list even when blank, absent = the built-in search folders)";

    /** The id-free head of the marker an older build wrote (it named the {@link #DEFAULT_ROOTS}
     * paths and a development id after this point); {@link #withModsRootsRow} still recognizes it
     * through {@link OpsMatchRows#isMarker} so a host file's old line is replaced, never orphaned.
     * */
    static final String MODS_ROOTS_LEGACY_STEM =
            "# folders the Ops window's Mods tab searches for mods (';'-separated, repo-relative"
                    + " or absolute; a mod's own folder or a folder of mods; a PRESENT row is the"
                    + " list even when blank, absent = ";

    private InstalledModScan() {
    }

    /** The lib's {@code Get-CcConfigModsRow} law over already-read lines: last uncommented
     * {@code key=} row wins (trimmed), {@code ""} when absent. */
    public static String configRow(List<String> propsLines, String key) {
        String val = configRowOrNull(propsLines, key);
        return val == null ? "" : val;
    }

    /** {@link #configRow} distinguishing ABSENT (null) from present-but-blank ({@code ""}) —
     * the {@code -NullWhenAbsent} twin in the lib (the roots row's explicit-blank law). */
    public static String configRowOrNull(List<String> propsLines, String key) {
        String val = null;
        for (String line : propsLines) {
            String t = line.trim();
            if (t.startsWith("#") || t.startsWith("!")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq < 1) {
                continue;
            }
            if (t.substring(0, eq).trim().equals(key)) {
                val = t.substring(eq + 1).trim();
            }
        }
        return val;
    }

    /** The roots the scan walks: the {@code mods.roots} row split on {@code ;} (trimmed, empties
     * dropped) when the row is PRESENT — a blank present row = no roots (the Mods tab emptied the
     * list) — else {@link #DEFAULT_ROOTS}. */
    public static List<String> roots(List<String> propsLines) {
        return roots(propsLines, DEFAULT_ROOTS);
    }

    /** {@link #roots} with the ABSENT-row default named: a packed server searches its own
     * {@code serve-mods} folder ({@link PackageModSync#PACKAGE_DEFAULT_ROOTS}), owner mode the
     * repo's {@link #DEFAULT_ROOTS}. */
    public static List<String> roots(List<String> propsLines, List<String> absentDefault) {
        String row = configRowOrNull(propsLines, MODS_ROOTS_KEY);
        return row == null ? absentDefault : splitRoots(row);
    }

    /** A present {@code mods.roots} value split on {@code ;} (trimmed, empties dropped). */
    public static List<String> splitRoots(String row) {
        List<String> out = new ArrayList<>();
        for (String r : row.split(";")) {
            if (!r.trim().isEmpty()) {
                out.add(r.trim());
            }
        }
        return out;
    }

    /** {@link #scan} for a packed server: relative roots resolve under the package root and an
     * absent row searches {@code serve-mods} (the Mods tab's scan in package mode). */
    public static List<InstalledMod> scanPackaged(Path pkgRoot, List<String> propsLines) {
        return scanRoots(pkgRoot, roots(propsLines, PackageModSync.PACKAGE_DEFAULT_ROOTS));
    }

    /**
     * {@code propsLines} with the {@code mods.roots} row set to {@code value} (marker + row, in
     * place of the first existing row, Properties-shadowed duplicates dropped, else appended);
     * a blank value WRITES a blank row (the explicit-empty list), null = untouched.
     */
    public static List<String> withModsRootsRow(List<String> lines, String value) {
        if (value == null) {
            return lines;
        }
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.trim().startsWith(MODS_ROOTS_KEY) && line.trim().substring(
                    MODS_ROOTS_KEY.length()).trim().startsWith("=")) {
                if (!out.isEmpty() && OpsMatchRows.isMarker(out.get(out.size() - 1),
                        MODS_ROOTS_COMMENT, MODS_ROOTS_LEGACY_STEM)) {
                    out.remove(out.size() - 1);
                }
                if (!placed) {
                    out.add(MODS_ROOTS_COMMENT);
                    out.add(MODS_ROOTS_KEY + "=" + value.trim());
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (!placed) {
            out.add(MODS_ROOTS_COMMENT);
            out.add(MODS_ROOTS_KEY + "=" + value.trim());
        }
        return out;
    }

    /** A root token resolved to a path (relative under {@code repoRoot}; both slashes accepted),
     * or null when the token is not a path at all. Existence is NOT checked. */
    public static Path resolveRoot(Path repoRoot, String token) {
        try {
            Path p = Paths.get(token.trim().replace('\\', '/'));
            return p.isAbsolute() ? p : repoRoot.resolve(p);
        } catch (RuntimeException bad) {
            return null;
        }
    }

    /** Scan the {@link #roots} of {@code propsLines}, relative roots resolved under {@code repoRoot}. */
    public static List<InstalledMod> scan(Path repoRoot, List<String> propsLines) {
        return scanRoots(repoRoot, roots(propsLines));
    }

    /**
     * Every mod under {@code roots}, sorted by id (then folder, so an id found twice — the lib
     * refuses that as ambiguous — lists both folders deterministically). Never throws.
     */
    public static List<InstalledMod> scanRoots(Path repoRoot, List<String> roots) {
        List<InstalledMod> out = new ArrayList<>();
        for (String root : roots) {
            Path rootPath = resolveRoot(repoRoot, root);
            if (rootPath == null) {
                System.err.println("[mods-scan] root '" + root + "' is not a path");
                continue;
            }
            if (!Files.isDirectory(rootPath)) {
                continue;
            }
            // A root that is ITSELF a mod folder is that one mod (the Mods tab lists single
            // mod folders; the lib's Resolve-CcExtraMods carries the same rule)
            Path own = rootPath.resolve(MOD_INFO);
            if (Files.isRegularFile(own)) {
                InstalledMod mod = readModInfo(rootPath, own, rootPath);
                if (mod != null) {
                    out.add(mod);
                }
                continue;
            }
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(rootPath)) {
                for (Path d : dirs) {
                    Path info = d.resolve(MOD_INFO);
                    if (!Files.isDirectory(d) || !Files.isRegularFile(info)) {
                        continue;
                    }
                    InstalledMod mod = readModInfo(d, info, rootPath);
                    if (mod != null) {
                        out.add(mod);
                    }
                }
            } catch (IOException | RuntimeException bad) {
                System.err.println("[mods-scan] root " + rootPath + " unreadable, skipped: " + bad);
            }
        }
        out.sort(Comparator.comparing(InstalledMod::id).thenComparing(m -> m.folder().toString()));
        return out;
    }

    /** One folder's record, or null (unreadable / over the read cap / no id / an unlawful id —
     * one {@code System.err} line each). */
    static InstalledMod readModInfo(Path folder, Path info, Path root) {
        String raw;
        try {
            long size = Files.size(info);
            if (size > MOD_INFO_READ_CAP) {
                System.err.println("[mods-scan] " + info + " is " + size + " bytes (cap "
                        + MOD_INFO_READ_CAP + "), skipped");
                return null;
            }
            raw = Files.readString(info, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException bad) {
            System.err.println("[mods-scan] " + info + " unreadable, skipped: " + bad);
            return null;
        }
        Matcher id = ID.matcher(raw);
        if (!id.find()) {
            System.err.println("[mods-scan] " + info + " declares no \"id\", skipped");
            return null;
        }
        String declared = id.group(1);
        if (declared.length() > ModSet.MAX_ID_CHARS || !ModSet.lawfulId(declared)) {
            // the id's TEXT is never echoed (it failed the law; a newline in it would fold this
            // very line) — the length + the file locate it for the host
            System.err.println("[mods-scan] " + info + " declares an unlawful \"id\" ("
                    + declared.length() + " chars; ModSet.lawfulId), skipped");
            return null;
        }
        Matcher name = NAME.matcher(raw);
        return new InstalledMod(declared, name.find() ? name.group(1).trim() : "",
                version(raw), folder, root);
    }

    /** The declared version: the string form, else {@code major.minor.patch} of the object form
     * (the parts present, in that order), else {@code ""}. */
    static String version(String raw) {
        // decide on the FIRST "version" key only (a dependency object's string version further
        // down must never stand in for a top-level object version)
        Matcher key = VERSION_KEY.matcher(raw);
        if (!key.find()) {
            return "";
        }
        String from = raw.substring(key.start());
        Matcher text = VERSION_TEXT.matcher(from);
        if (text.lookingAt()) {
            return text.group(1).trim();
        }
        Matcher obj = VERSION_OBJECT.matcher(from);
        if (!obj.lookingAt()) {
            return "";
        }
        String major = null;
        String minor = null;
        String patch = null;
        Matcher part = VERSION_PART.matcher(obj.group(1));
        while (part.find()) {
            switch (part.group(1)) {
                case "major" -> major = major == null ? part.group(2) : major;
                case "minor" -> minor = minor == null ? part.group(2) : minor;
                default -> patch = patch == null ? part.group(2) : patch;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : new String[] {major, minor, patch}) {
            if (p != null) {
                sb.append(sb.length() == 0 ? "" : ".").append(p);
            }
        }
        return sb.toString();
    }
}
