package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import cc.starsector.server.runtime.ModSet;

/**
 * Mods on a PACKAGED server: a host must be able to extract mods
 * into a folder and run them, never a sealed mods folder.
 * This is the Java port of the launcher library's
 * extra-mods law,
 * run by {@link ServeLauncher} before life 1 on a packed server only
 * (owner mode's stage tool still runs the library):
 * <ul>
 * <li>the serve's mods folder is {@code <pkg>/serve-mods/} — HOST-OWNED beyond our own
 *     {@code CC_Client/**} (the packer lists only that subtree and our jar in {@code MANIFEST.txt};
 *     {@value #ENABLED_FILE} is written here at every launch, so it is not listed);</li>
 * <li>{@code mods.enabled} (ids the serve loads; every joiner must run them) and
 *     {@code mods.optional} (joiners may run them; never loaded) are the Mods tab's rows, read with the
 *     shells' own text law ({@link OpsMatchRows#parseModsEnabled} / {@link OpsMatchRows#parseModsOptional});</li>
 * <li>{@code mods.roots} = the searched folders (absolute, or relative to the package root); ABSENT =
 *     {@link #PACKAGE_DEFAULT_ROOTS} ({@code serve-mods} itself: "extract the mod there, press Start");</li>
 * <li>each id resolves to exactly ONE folder holding a {@code mod_info.json} with that id — missing or
 *     ambiguous = a refusal naming the id (fail-closed, the library's law); every DECLARED dependency
 *     ({@code dependencies[].id}, transitively) is enabled with its consumer, because the engine parses
 *     dependencies but never enables them;</li>
 * <li>an optional mod's declared dependencies must be REQUIRED, and an optional id a required mod pulls in
 *     is refused: the library's two optional laws, {@code serve-mods-lib.ps1:247-251};</li>
 * <li>a folder outside {@code serve-mods/} is COPIED in under its own name (size + mtime gated, so an
 *     unchanged mod costs one directory walk; never a delete — the folder is the host's);</li>
 * <li>{@value #ENABLED_FILE} = {@code cc_client} + the enabled ids + their dependencies, the library's
 *     exact shape; the engine reads it from the folder {@code serve.argv} points it at.</li>
 * </ul>
 * The door then folds whatever the engine actually enabled ({@code RealEngineModSetFacts.snapshot}),
 * so joiners are held to the set this file produced — no second source of truth.
 *
 * <p>Pure apart from the file I/O it names; every refusal is ONE operator sentence
 * ({@link IllegalArgumentException}); a copy failure is an {@link IOException}. Rows in
 * {@code ServerOpsCoreSelftest.packageModSyncRows}.
 */
public final class PackageModSync {

    /** The serve's mods folder under the package root (what the launch template points the engine at). */
    public static final String MODS_DIR = "serve-mods";
    /** The engine's enabled list inside {@link #MODS_DIR}. */
    public static final String ENABLED_FILE = "enabled_mods.json";
    /** Our own client mod's folder inside {@link #MODS_DIR} (packer-listed, never a mirror target). */
    public static final String CLIENT_FOLDER = "CC_Client";
    /** A packed server searches its own mods folder when the host named no {@code mods.roots}. */
    public static final List<String> PACKAGE_DEFAULT_ROOTS = List.of(MODS_DIR);
    /** Largest {@code mod_info.json} read for dependencies ({@link InstalledModScan#MOD_INFO_READ_CAP}). */
    static final int MOD_INFO_READ_CAP = InstalledModScan.MOD_INFO_READ_CAP;

    private static final Pattern DEPENDENCIES = Pattern.compile("\"dependencies\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern DEP_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    /** One mod the launch will use: its id, the folder it was found in, the folder the engine reads
     * it from ({@code == source} when it already lies under {@code serve-mods/}), the id that pulled
     * it in as a dependency ({@code ""} = named by the host) and whether it is optional (never listed). */
    public record Resolved(String id, Path source, Path target, String via, boolean optional) {
    }

    /** The launch's mod set: the ids {@link #ENABLED_FILE} lists ({@code cc_client} first) and every
     * folder to mirror or use in place. */
    public record Plan(List<String> enabledIds, List<Resolved> mods) {
    }

    private PackageModSync() {
    }

    /**
     * Resolve the rows against the package. {@code rootsRowOrNull} = the {@code mods.roots} value, or
     * null when the row is absent (the present-but-blank row = no roots, the Mods tab's law).
     *
     * @throws IllegalArgumentException one operator sentence (an unlawful id, an id in both rows, a
     *         missing / ambiguous mod or dependency, a folder-name collision)
     */
    public static Plan plan(Path pkg, String enabledRow, String optionalRow, String rootsRowOrNull) {
        Path root = pkg.toAbsolutePath().normalize();
        Path modsDir = root.resolve(MODS_DIR);
        List<String> enabled = split(OpsMatchRows.parseModsEnabled(enabledRow));
        List<String> optional = split(OpsMatchRows.parseModsOptional(optionalRow));
        String both = OpsMatchRows.modsBothRefusalOrNull(String.join(",", enabled), String.join(",", optional));
        if (both != null) {
            throw new IllegalArgumentException(both);
        }
        List<String> roots = rootsRowOrNull == null ? PACKAGE_DEFAULT_ROOTS : InstalledModScan.splitRoots(rootsRowOrNull);
        Map<String, List<Path>> found = new LinkedHashMap<>();
        for (InstalledModScan.InstalledMod m : InstalledModScan.scanRoots(root, roots)) {
            if (ModSet.EXCLUDED_ID.equals(m.id())) {
                continue; // our own client mod: always on, never a host pick
            }
            found.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(m.folder().toAbsolutePath().normalize());
        }
        String searched = String.join(";", roots);
        List<String> enabledIds = new ArrayList<>();
        enabledIds.add(ModSet.EXCLUDED_ID);
        List<Resolved> mods = new ArrayList<>();
        Map<String, String> targetNames = new LinkedHashMap<>(); // target folder name -> id
        targetNames.put(CLIENT_FOLDER.toLowerCase(java.util.Locale.ROOT), ModSet.EXCLUDED_ID);
        // breadth-first over the host's ids, then every declared dependency (the library's order)
        List<String[]> queue = new ArrayList<>();
        for (String id : enabled) {
            queue.add(new String[] {id, ""});
        }
        for (int i = 0; i < queue.size(); i++) {
            String id = queue.get(i)[0];
            String via = queue.get(i)[1];
            if (ModSet.EXCLUDED_ID.equals(id)) {
                continue; // a mod may declare our client mod as a dependency; it is always enabled
            }
            if (enabledIds.contains(id)) {
                continue;
            }
            Path source = resolveOne(found, id, via, false, searched, modsDir);
            Resolved r = resolved(id, source, via, false, root, modsDir, targetNames);
            enabledIds.add(id);
            mods.add(r);
            for (String dep : declaredDependencies(source.resolve(InstalledModScan.MOD_INFO))) {
                queue.add(new String[] {dep, id});
            }
        }
        // the library's two optional-mod laws (serve-mods-lib.ps1:247-251): an
        // optional id a required mod pulls in is REQUIRED (the serve's optionalSnapshot throws "also ENABLED" on
        // every life otherwise, and the supervisor relaunches into it); an optional mod's declared dependencies
        // must be required (a player loading the optional mod alone hits NoClassDefFoundError at first use -
        // GraphicsLib needs LazyLib)
        for (String id : optional) {
            if (enabledIds.contains(id)) {
                String via = "";
                for (Resolved r : mods) {
                    if (r.id().equals(id)) {
                        via = r.via();
                    }
                }
                throw new IllegalArgumentException("optional mod '" + id + "' is also required as a dependency of mod '"
                        + via + "': a mod is required or optional, never both - set '" + id + "' to off in the Mods tab");
            }
            Path source = resolveOne(found, id, "", true, searched, modsDir);
            for (String dep : declaredDependencies(source.resolve(InstalledModScan.MOD_INFO))) {
                if (!ModSet.EXCLUDED_ID.equals(dep) && !enabledIds.contains(dep)) {
                    throw new IllegalArgumentException("optional mod '" + id + "' depends on '" + dep + "', which is not"
                            + " required: set '" + dep + "' to required in the Mods tab (a player who runs an optional"
                            + " mod needs its libraries)");
                }
            }
            mods.add(resolved(id, source, "", true, root, modsDir, targetNames));
        }
        return new Plan(List.copyOf(enabledIds), List.copyOf(mods));
    }

    /**
     * Apply a plan: copy every folder that lies outside {@code serve-mods/} into it (unchanged files
     * skipped by size + mtime; nothing is ever deleted), then write {@link #ENABLED_FILE}. Returns the
     * lines to say, one per action.
     */
    public static List<String> apply(Path pkg, Plan plan) throws IOException {
        Path root = pkg.toAbsolutePath().normalize();
        Path modsDir = root.resolve(MODS_DIR);
        List<String> said = new ArrayList<>();
        Files.createDirectories(modsDir);
        for (Resolved r : plan.mods()) {
            if (r.target().equals(r.source())) {
                continue;
            }
            long[] counts = mirror(r.source(), r.target());
            if (counts[0] + counts[1] == 0) {
                // a set knob is never silently inert (the optional path's law): a mirror holding no file
                // (links only, a vanished source) would list an id the engine drops without a word
                throw new IllegalArgumentException((r.optional() ? "optional mod '" : "mod '") + r.id()
                        + "' at " + r.source() + " mirrored no files into " + root.relativize(r.target())
                        + " - the folder holds only links or nothing; extract the mod there as plain files");
            }
            said.add((r.optional() ? "optional mod '" : "mod '") + r.id() + "': copied " + counts[0]
                    + " file(s) into " + root.relativize(r.target()) + " (" + counts[1] + " unchanged)");
        }
        StringBuilder names = new StringBuilder();
        for (Resolved r : plan.mods()) {
            if (!r.optional()) {
                names.append(", ").append(r.id()).append(r.via().isEmpty() ? "" : " (dependency of " + r.via() + ")");
            }
        }
        List<String> optional = new ArrayList<>();
        for (Resolved r : plan.mods()) {
            if (r.optional()) {
                optional.add(r.id());
            }
        }
        said.add("enabled = " + ModSet.EXCLUDED_ID + names + (optional.isEmpty() ? "" : "; optional = "
                + String.join(", ", optional)) + " -> " + root.relativize(modsDir.resolve(ENABLED_FILE)));
        StringBuilder json = new StringBuilder("{\"enabledMods\": [");
        for (int i = 0; i < plan.enabledIds().size(); i++) {
            json.append(i == 0 ? "" : ", ").append('"').append(plan.enabledIds().get(i)).append('"');
        }
        json.append("]}");
        Path file = modsDir.resolve(ENABLED_FILE);
        Path tmp = modsDir.resolve(ENABLED_FILE + ".tmp");
        Files.write(tmp, json.toString().getBytes(StandardCharsets.US_ASCII));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException noAtomic) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return said;
    }

    /** {@link #plan} then {@link #apply}. */
    public static List<String> sync(Path pkg, String enabledRow, String optionalRow, String rootsRowOrNull)
            throws IOException {
        return apply(pkg, plan(pkg, enabledRow, optionalRow, rootsRowOrNull));
    }

    /** The current {@link #ENABLED_FILE} text, or null. */
    public static String enabledFileText(Path pkg) throws IOException {
        Path file = pkg.toAbsolutePath().normalize().resolve(MODS_DIR).resolve(ENABLED_FILE);
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.US_ASCII) : null;
    }

    private static Path resolveOne(Map<String, List<Path>> found, String id, String via, boolean optional,
            String searched, Path modsDir) {
        String what = (optional ? "optional mod '" : via.isEmpty() ? "mod '" : "dependency '") + id + "'"
                + (via.isEmpty() ? "" : " of mod '" + via + "'");
        List<Path> folders = found.get(id);
        if (folders == null || folders.isEmpty()) {
            throw new IllegalArgumentException(what + " is not in any searched folder (" + searched
                    + "): extract it into " + modsDir + (via.isEmpty() ? ", or set it to off in the Mods tab"
                            : ", or set mod '" + via + "' to off in the Mods tab"));
        }
        // the copy a previous launch made under serve-mods/ is the MIRROR of a folder found outside
        // (the serve-mods root is searched by default, so a mirrored mod is found twice by design):
        // one outside source wins and is re-synced; two outside sources, or two under serve-mods, are ambiguous
        List<Path> outside = new ArrayList<>();
        List<Path> inside = new ArrayList<>();
        for (Path f : folders) {
            (f.getParent() != null && f.getParent().equals(modsDir) ? inside : outside).add(f);
        }
        if (outside.size() > 1) {
            throw new IllegalArgumentException(what + " is in more than one searched folder (" + outside.get(0)
                    + " and " + outside.get(1) + "): keep one");
        }
        if (outside.size() == 1) {
            Path source = outside.get(0);
            for (Path mirror : inside) {
                if (!mirror.getFileName().toString().equals(source.getFileName().toString())) {
                    throw new IllegalArgumentException(what + " is in " + source + " and also in " + mirror
                            + " under another folder name: remove one of them");
                }
            }
            return source;
        }
        if (inside.size() > 1) {
            throw new IllegalArgumentException(what + " is in more than one folder under " + modsDir + " ("
                    + inside.get(0).getFileName() + " and " + inside.get(1).getFileName() + "): keep one");
        }
        return inside.get(0);
    }

    private static Resolved resolved(String id, Path source, String via, boolean optional, Path root,
            Path modsDir, Map<String, String> targetNames) {
        Path target = source.startsWith(modsDir) && source.getParent() != null && source.getParent().equals(modsDir)
                ? source : modsDir.resolve(source.getFileName().toString());
        if (source.startsWith(root) && !source.startsWith(modsDir)) {
            throw new IllegalArgumentException("mod '" + id + "' lies inside this package (" + source
                    + ") but not in " + modsDir + ": move it there");
        }
        if (!target.equals(source) && (root.startsWith(source) || source.startsWith(target))) {
            throw new IllegalArgumentException("mod '" + id + "' folder " + source + " contains this package"
                    + " or its mods folder: move it elsewhere");
        }
        String name = target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String owner = targetNames.get(name);
        if (owner != null && !owner.equals(id)) {
            throw new IllegalArgumentException("mod '" + id + "' folder name '" + target.getFileName()
                    + "' collides with mod '" + owner + "' in " + modsDir + ": rename the folder");
        }
        targetNames.put(name, id);
        if (!target.equals(source) && Files.isDirectory(target)) {
            Path info = target.resolve(InstalledModScan.MOD_INFO);
            InstalledModScan.InstalledMod there = Files.isRegularFile(info)
                    ? InstalledModScan.readModInfo(target, info, modsDir) : null;
            if (there != null && !there.id().equals(id)) {
                throw new IllegalArgumentException("mod '" + id + "' would be copied to " + target
                        + ", which already holds mod '" + there.id() + "': rename the source folder");
            }
        }
        return new Resolved(id, source, target, via, optional);
    }

    /** The {@code dependencies[].id} values of a {@code mod_info.json} by the library's regex (the
     * engine's own parser is lenient, so no strict JSON); unreadable / over cap / none = empty. */
    static List<String> declaredDependencies(Path info) {
        List<String> out = new ArrayList<>();
        String raw;
        try {
            if (!Files.isRegularFile(info) || Files.size(info) > MOD_INFO_READ_CAP) {
                return out;
            }
            raw = Files.readString(info, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return out;
        }
        Matcher block = DEPENDENCIES.matcher(raw);
        if (!block.find()) {
            return out;
        }
        Matcher id = DEP_ID.matcher(block.group(1));
        while (id.find()) {
            String dep = id.group(1).trim();
            if (!dep.isEmpty() && ModSet.lawfulId(dep) && dep.length() <= ModSet.MAX_ID_CHARS && !out.contains(dep)) {
                out.add(dep);
            }
        }
        return out;
    }

    /** Copy {@code source} into {@code target}: directories created, files copied when missing or
     * different in size / mtime, symbolic links INSIDE the tree skipped, nothing deleted. A link AS the
     * source is followed ({@code toRealPath}): the walk's start entry under NOFOLLOW is the link itself,
     * so a host's {@code ln -s} mod folder (found by the scan, which follows links) mirrored 0 files and
     * was listed anyway (the engine drops a listed id with no folder without a word).
     * Returns {copied, unchanged}. */
    static long[] mirror(Path source, Path target) throws IOException {
        long copied = 0;
        long unchanged = 0;
        Files.createDirectories(target);
        Path real = source.toRealPath();
        try (Stream<Path> walk = Files.walk(real)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = real.relativize(p);
                if (rel.toString().isEmpty()) {
                    continue;
                }
                Path dest = target.resolve(rel.toString());
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isDirectory()) {
                    Files.createDirectories(dest);
                } else if (a.isRegularFile()) {
                    if (Files.isRegularFile(dest) && Files.size(dest) == a.size()
                            && Files.getLastModifiedTime(dest).toMillis() == a.lastModifiedTime().toMillis()) {
                        unchanged++;
                        continue;
                    }
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
            }
        }
        return new long[] {copied, unchanged};
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        for (String t : csv.split(",")) {
            if (!t.trim().isEmpty()) {
                out.add(t.trim());
            }
        }
        return out;
    }
}
