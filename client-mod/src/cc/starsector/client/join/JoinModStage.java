package cc.starsector.client.join;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import cc.starsector.client.net.DoorProbe;
import cc.starsector.server.runtime.ModDataWalk;
import cc.starsector.server.runtime.ModSet;

/**
 * The JOINER half of the mods ladder — the join page
 * diffs the server's published mod set against the joiner's own {@code mods/} folder and, on
 * Launch, stages the KIT'S OWN MODS ROOT the game boots from.
 *
 * <p><b>The owner's law (2026-09-21): the player's ordinary mod list is never tampered with.</b>
 * The kit does NOT write {@code mods/enabled_mods.json}. Instead it keeps its own root at
 * {@code <install>/saves/common/cc_mods/}: one directory JUNCTION per required mod (pointing at the
 * joiner's real folder under {@code mods/}), one for the kit itself, and an {@code enabled_mods.json}
 * that lists exactly the server's set. {@code join-cc.bat} points the game at that root through
 * {@code -Dcom.fs.starfarer.settings.paths.mods} (the engine's own property; {@code ModManager}
 * reads {@code <paths.mods>/enabled_mods.json}). Starting
 * Starsector the normal way reads {@code ../mods} as always, so the player's own list and folders
 * are untouched BY CONSTRUCTION — no backup, no restore, nothing to heal after a crash. A junction
 * is a link: deleting one removes the link, never the mod (the kit only ever deletes junctions it
 * finds in its own root, one by one, never recursively).
 *
 * <p>Version recipe = the engine's own ({@code ModManager.VersionInfo}):
 * a string {@code version} is taken verbatim; an object form renders {@code major.minor.patch} /
 * {@code major.minor} / {@code major}; absent = "". The jars digest = {@link ModSet#jarsMd5} over
 * the declared jars in declaration order (the in-game {@code ClientModSetBoot} recipe). The
 * server's row carries the version LABEL ({@link ModSet#versionLabel}) and the files PREFIX (jars
 * word + data word, {@link ModSet#filesPrefix}), so both sides compare the same projections. A
 * {@link #scan} is identity only ({@code mod_info.json}); the jars + {@code data/**} digests are
 * computed lazily per PUBLISHED id inside {@link #diff}.
 *
 *
 * <p>Security posture: every published id/label is
 * DATA — rendered through {@code textContent} on the page, never a path. A junction target is
 * always resolved from the joiner's OWN scan (a folder under their {@code mods/} whose
 * {@code mod_info.json} declares the id); the server never names a path. Folder names reach
 * {@code cmd.exe /c mklink} as arguments — the {@link #stageableName} law refuses the cmd
 * metacharacters ({@code % ^ & | < > "}) and control characters, so no name can break out.
 *
 * <p>Game-free: this class runs in the join-window JVM only (the {@code join/**} B-deny exemption)
 * and depends on nothing but {@code java.nio} and the bundled {@link ModSet} (no {@code org.json}:
 * the {@code --selftest} lane runs on the mod jar alone; {@link MiniJson} is the reader).
 */
final class JoinModStage {

    /** The kit's mods root, under the install root (beside the {@code cc_variants} cache); ONE home =
     * {@link ModSet#KIT_MODS_ROOT_REL} (the in-game boot recognises a kit boot by it). */
    static final String ROOT_REL = ModSet.KIT_MODS_ROOT_REL;
    /** The sidecar in the root naming the OPTIONAL mods this launch staged — the game
     * reads it back ({@link ModSet#OPTIONAL_CACHE_COMMON_NAME}) and drops those ids from its fold. */
    static final String OPTIONAL_FILE = "optional-mods.txt.data";
    /** Read guard on a {@code mod_info.json} (the largest real one is a few KB). */
    static final long MOD_INFO_MAX_BYTES = 1L << 20;
    /** The launch gate word the SPA keys on. */
    static final String GATE = "mods";
    static final String TITLE = "Mods";

    static final String STATUS_OK = "ok";
    static final String STATUS_MISSING = "missing";
    static final String STATUS_VERSION = "version";
    static final String STATUS_CONTENT = "content";
    /** Same version and jars, but the {@code data/**} digest word differs (an edited CSV, a
     * half-updated folder) — the data half of the published files prefix. */
    static final String STATUS_DATA = "data";
    static final String STATUS_UNREADABLE = "unreadable";

    private JoinModStage() {
    }

    /** One folder under {@code <install>/mods} with a readable {@code mod_info.json}. {@code jarsMd5} /
     * {@code dataMd5} are null UNTIL {@link #digest} ran (a {@link #scan} is identity-only); after a
     * digest, a null digest + a non-null {@code note} = unreadable.
     * */
    static final class LocalMod {
        final String id;
        final Path dir;
        final String version;
        /** The declared {@code jars} strings in declaration order (empty = none / not a list); what
         * {@link #digest} reads. */
        final List<String> jars;
        /** null = not digested yet, or a declared jar is missing/unreadable (the note says which). */
        final String jarsMd5;
        /** The {@code data/**} digest ({@code ModDataWalk}); null = not digested yet, or
         * unreadable (the note says why). */
        final String dataMd5;
        final String note;

        /** {@code jars} joined the identity. */
        LocalMod(String id, Path dir, String version, List<String> jars, String jarsMd5, String dataMd5, String note) {
            this.id = id;
            this.dir = dir;
            this.version = version;
            this.jars = jars == null ? List.of() : jars;
            this.jarsMd5 = jarsMd5;
            this.dataMd5 = dataMd5;
            this.note = note;
        }
    }

    /** The joiner's own {@code mods/} folder, id-keyed (a duplicate id keeps the first folder and
     * notes the second — the launcher would refuse the same ambiguity). */
    static final class Scan {
        final Map<String, LocalMod> byId = new TreeMap<>();
        final List<String> notes = new ArrayList<>();
    }

    /** One diff row: the server's id + wanted version label vs. what the joiner has. */
    static final class Row {
        final String id;
        final String want;
        final String have;
        final String status;
        /** The joiner's folder to link (non-null only on {@link #STATUS_OK}). */
        final Path dir;
        /** A client-OPTIONAL row — never a launch problem; staged only on {@link #STATUS_OK}. */
        final boolean optional;

        Row(String id, String want, String have, String status, Path dir) {
            this(id, want, have, status, dir, false);
        }

        Row(String id, String want, String have, String status, Path dir, boolean optional) {
            this.id = id;
            this.want = want;
            this.have = have;
            this.status = status;
            this.dir = dir;
            this.optional = optional;
        }

        /** The ONE wording home for a row (the SPA and the Swing twin both render this). */
        String line() {
            if (optional) {
                switch (status) {
                    case STATUS_OK:
                        return id + " " + want + ": optional - present, will load";
                    case STATUS_MISSING:
                        return id + " " + want + ": optional - not installed (fine, it only changes visuals)";
                    case STATUS_VERSION:
                        return id + ": optional - you have " + have + ", the server pins " + want + " - not loaded";
                    case STATUS_CONTENT:
                        return id + " " + want + ": optional - same version but different files - not loaded";
                    case STATUS_DATA:
                        return id + " " + want + ": optional - same version and jars but different data files - not loaded";
                    default:
                        return id + " " + want + ": optional - your copy could not be read (" + have + ") - not loaded";
                }
            }
            switch (status) {
                case STATUS_OK:
                    return id + " " + want + ": present";
                case STATUS_MISSING:
                    return id + " " + want + ": MISSING - get this mod (version " + want
                            + ") and put its folder in mods\\, then Refresh";
                case STATUS_VERSION:
                    return id + ": wrong version - you have " + have + ", the server runs " + want;
                case STATUS_CONTENT:
                    return id + " " + want + ": same version but different files - re-download it"
                            + " from where the host got it";
                case STATUS_DATA:
                    return id + " " + want + ": same version and jars but different data files (an edited"
                            + " or half-updated copy?) - re-download it from where the host got it";
                default:
                    return id + " " + want + ": your copy could not be read - " + have;
            }
        }
    }

    /** The diff of the server's published set against the scan + the launch verdict. */
    static final class Diff {
        final List<Row> rows;
        /** True = every published row is present with the right version and files. */
        final boolean ok;
        /** False = the listing was cut (cap or lost frames): the rows are a partial view. */
        final boolean complete;
        /** The server's full mod count (0 = nothing published). */
        final int available;
        final String line;

        Diff(List<Row> rows, boolean ok, boolean complete, int available, String line) {
            this.rows = rows;
            this.ok = ok;
            this.complete = complete;
            this.available = available;
            this.line = line;
        }

        /** No probe served yet / nothing published: nothing to require, cc_client-only root. */
        static Diff none() {
            return new Diff(List.of(), true, true, 0, "");
        }
    }

    // ------------------------------------------------------------------ the local scan

    /** Scan {@code <install>/mods}: every immediate folder with a {@code mod_info.json}. Never
     * throws — an unreadable folder becomes a note. Identity only — no jar or data bytes are read;
     * {@link #diff} digests the published ids lazily. Still call it off the EDT: it lists
     * {@code mods/} and reads every {@code mod_info.json}.
     * */
    static Scan scan(Path installRoot) {
        Scan scan = new Scan();
        Path mods = installRoot.resolve("mods");
        if (!Files.isDirectory(mods)) {
            scan.notes.add("no mods folder at " + mods);
            return scan;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path p : ds) {
                if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("mod_info.json"))) {
                    dirs.add(p);
                }
            }
        } catch (IOException e) {
            scan.notes.add("mods folder unreadable: " + e.getMessage());
            return scan;
        }
        dirs.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        for (Path dir : dirs) {
            LocalMod mod = readMod(dir);
            if (mod == null) {
                continue;
            }
            if (mod.id == null) {
                scan.notes.add(mod.note);
                continue;
            }
            LocalMod prior = scan.byId.get(mod.id);
            if (prior != null) {
                scan.notes.add("mod id " + mod.id + " is declared by two folders (" + prior.dir.getFileName()
                        + " and " + dir.getFileName() + ") - the first is used");
                continue;
            }
            scan.byId.put(mod.id, mod);
        }
        return scan;
    }

    /** One folder → its identity (id null = unreadable; the note says why). */
    static LocalMod readMod(Path dir) {
        Path info = dir.resolve("mod_info.json");
        String name = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
        String text;
        try {
            if (Files.size(info) > MOD_INFO_MAX_BYTES) {
                return new LocalMod(null, dir, null, null, null, null, name + ": mod_info.json over 1 MB - skipped");
            }
            text = new String(Files.readAllBytes(info), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new LocalMod(null, dir, null, null, null, null, name + ": mod_info.json unreadable (" + e.getMessage() + ")");
        }
        Map<String, Object> obj;
        try {
            Object parsed = MiniJson.parse(stripHashComments(text));
            if (!(parsed instanceof Map)) {
                return new LocalMod(null, dir, null, null, null, null, name + ": mod_info.json is not an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = (Map<String, Object>) parsed;
            obj = asMap;
        } catch (RuntimeException e) {
            return new LocalMod(null, dir, null, null, null, null, name + ": mod_info.json does not parse (" + e.getMessage() + ")");
        }
        Object idRaw = obj.get("id");
        if (!(idRaw instanceof String) || ((String) idRaw).isEmpty()) {
            return new LocalMod(null, dir, null, null, null, null, name + ": mod_info.json has no id");
        }
        String id = (String) idRaw;
        String version = versionOf(obj.get("version"));
        // Identity only — the jar strings, no bytes
        List<String> jars = new ArrayList<>();
        Object jarsRaw = obj.get("jars");
        if (jarsRaw instanceof List) {
            for (Object jar : (List<?>) jarsRaw) {
                if (jar instanceof String) {
                    jars.add((String) jar);
                }
            }
        }
        return new LocalMod(id, dir, version, List.copyOf(jars), null, null, null);
    }

    /**
     * How many real digest walks
     * (jar reads + {@code data/**} walk) {@link #digest} has run in this JVM. WHY: the selftest proves
     * two identical published rows cost ONE walk ({@link #diff} memoises per id); package-private,
     * never a meter or a log.
     */
    static final java.util.concurrent.atomic.AtomicInteger DIGEST_WALKS =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The mod's jars + {@code data/**} digests, computed on demand. WHY: the old
     * {@link #scan} read every folder's declared jars AND walked {@code data/**} of EVERY folder
     * under {@code mods/} on EVERY served probe — even when the server published zero mods, seconds
     * per probe on an HDD install. Now the digests are computed for PUBLISHED ids only, inside
     * {@link #diff}. IO posture: file IO, so call it OFF the EDT — {@code diff()} runs beside
     * {@code scan()} on the probe's worker ({@code JoinBrowserUi} the HTTP request thread,
     * {@code JoinGui} the {@code cc-join-ship-list} worker). Returns a NEW {@link LocalMod} carrying
     * both digests, or the note with whatever digest it could compute (the pre-rider wording). A mod
     * with {@code id == null} or already digested returns itself.
     *
     *
     */
    static LocalMod digest(LocalMod m) {
        if (m.id == null || (m.jarsMd5 != null && m.dataMd5 != null)) {
            return m;
        }
        DIGEST_WALKS.incrementAndGet();
        List<byte[]> jarBytes = new ArrayList<>();
        for (String j : m.jars) {
            Path jarPath = isAbsoluteJar(j) ? Paths.get(j) : m.dir.resolve(j.replace('\\', '/'));
            try {
                jarBytes.add(Files.readAllBytes(jarPath));
            } catch (IOException | RuntimeException e) {
                return new LocalMod(m.id, m.dir, m.version, m.jars, null, null,
                        "declared jar " + j + " is missing or unreadable");
            }
        }
        // The data/** half through the ONE walker both trees fold (game-free here, nio is fine)
        String dataMd5;
        try {
            dataMd5 = ModDataWalk.dataMd5(m.dir.toString());
        } catch (IOException | RuntimeException e) {
            return new LocalMod(m.id, m.dir, m.version, m.jars, ModSet.jarsMd5(jarBytes), null,
                    "a data/ file is unreadable (" + e.getMessage() + ")");
        }
        return new LocalMod(m.id, m.dir, m.version, m.jars, ModSet.jarsMd5(jarBytes), dataMd5, null);
    }

    /** The engine's jar-path rule ({@code ClientModSetBoot}: a drive letter or a leading slash). */
    static boolean isAbsoluteJar(String jar) {
        return jar.indexOf(':') >= 0 || jar.startsWith("/") || jar.startsWith("\\");
    }

    /** The engine's {@code VersionInfo} string ({@code ModManager.VersionInfo.getString()}). */
    static String versionOf(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof Map) {
            Map<?, ?> v = (Map<?, ?>) raw;
            String major = str(v.get("major"));
            String minor = str(v.get("minor"));
            String patch = str(v.get("patch"));
            if (!patch.isEmpty()) {
                return major + "." + minor + "." + patch;
            }
            if (!minor.isEmpty()) {
                return major + "." + minor;
            }
            return major;
        }
        return str(raw);
    }

    private static String str(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d); // org.json renders 1 as "1", not "1.0"
            }
        }
        return String.valueOf(o);
    }

    /** The engine's {@code LoadingUtils} pre-pass: a {@code #} outside a double-quoted string
     * comments the rest of the line; a newline ends both the comment and any open string. */
    static String stripHashComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean inComment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                inComment = false;
                inString = false;
                if (c == '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (inComment) {
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (c == '#' && !inString) {
                inComment = true;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ the diff

    static Diff diff(List<DoorProbe.ModRow> published, int available, boolean complete, Scan scan) {
        if (published.isEmpty() && available == 0) {
            return Diff.none();
        }
        List<Row> rows = new ArrayList<>();
        int bad = 0;
        int optionalCount = 0;
        // ONE digest per published id per diff —
        // duplicate published rows (up to 8 frames) re-walked the same mod once per row before.
        Map<String, LocalMod> digested = new HashMap<>();
        for (DoorProbe.ModRow p : published) {
            LocalMod have = scan.byId.get(p.id);
            if (have != null) {
                // Published ids only
                have = digested.computeIfAbsent(p.id, k -> digest(scan.byId.get(k)));
            }
            Row row;
            if (have == null) {
                row = new Row(p.id, p.versionLabel, "", STATUS_MISSING, null, p.optional);
            } else if (have.jarsMd5 == null || have.dataMd5 == null) {
                row = new Row(p.id, p.versionLabel, have.note, STATUS_UNREADABLE, null, p.optional);
            } else if (!ModSet.versionLabel(have.version).equals(p.versionLabel)) {
                row = new Row(p.id, p.versionLabel, ModSet.versionLabel(have.version), STATUS_VERSION, null, p.optional);
            } else if (ModSet.jarsWord(ModSet.filesPrefix(have.jarsMd5, have.dataMd5)) != ModSet.jarsWord(p.filesPrefix)) {
                row = new Row(p.id, p.versionLabel, p.versionLabel, STATUS_CONTENT, null, p.optional);
            } else if (ModSet.dataWord(ModSet.filesPrefix(have.jarsMd5, have.dataMd5)) != ModSet.dataWord(p.filesPrefix)) {
                // Jars agree, data/** does not — the door would refuse this copy (the fold carries both)
                row = new Row(p.id, p.versionLabel, p.versionLabel, STATUS_DATA, null, p.optional);
            } else {
                row = new Row(p.id, p.versionLabel, p.versionLabel, STATUS_OK, have.dir, p.optional);
            }
            // An optional row is never a problem — absent/skewed = simply not staged
            if (p.optional) {
                optionalCount++;
            } else if (!STATUS_OK.equals(row.status)) {
                bad++;
            }
            rows.add(row);
        }
        StringBuilder line = new StringBuilder("Mods: the server runs ").append(available)
                .append(available == 1 ? " mod" : " mods");
        if (optionalCount > 0) {
            line.append(" (").append(optionalCount).append(" optional)");
        }
        if (bad == 0) {
            line.append(" - all present");
        } else {
            line.append(" - ").append(bad).append(bad == 1 ? " problem" : " problems")
                    .append(" (see below)");
        }
        if (!complete) {
            line.append("; list incomplete (").append(rows.size()).append(" of ").append(available)
                    .append(" received) - press Refresh");
        }
        line.append('.');
        return new Diff(rows, bad == 0 && complete, complete, available, line.toString());
    }

    /** The diff rows as the SPA's {@code mods} array: {@code [{id, want, have, status, line}]}. */
    static String rowsJson(Diff diff) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Row r : diff.rows) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(new JoinJson().put("id", r.id).put("want", r.want).put("have", r.have)
                    .put("status", r.status).put("optional", r.optional).put("line", r.line()).toString());
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ the staging

    /** The kit's mods root for an install. */
    static Path root(Path installRoot) {
        return installRoot.resolve(ROOT_REL.replace('/', java.io.File.separatorChar));
    }

    /**
     * Stage the root for THIS launch: remove every junction the root holds, link the kit folder
     * and every OK row's folder, then write {@code enabled_mods.json} = cc_client + the rows'
     * ids (server order). The json is written LAST: a failure part-way leaves no json, and the bat
     * then boots the plain {@code ../mods} lane (today's behaviour) — never a half set. Throws
     * with a player-readable message on any refusal; nothing outside the root is ever touched.
     */
    static Path stage(Path installRoot, Path modDir, List<Row> rows) throws IOException {
        Path root = root(installRoot);
        Files.createDirectories(root);
        Path json = root.resolve("enabled_mods.json");
        Path optionalFile = root.resolve(OPTIONAL_FILE);
        Files.deleteIfExists(json);
        Files.deleteIfExists(optionalFile);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (isJunction(p)) {
                    Files.delete(p); // the link only — never the mod behind it
                } else {
                    throw new IOException("the kit's mods root holds a real item it did not create: "
                            + p + " - move it out (the kit keeps only links there)");
                }
            }
        }
        List<String> ids = new ArrayList<>();
        List<String> optionalStaged = new ArrayList<>();
        ids.add(ModSet.EXCLUDED_ID);
        Path kitDir = modDir.toAbsolutePath().normalize();
        junction(root.resolve(kitDir.getFileName().toString()), kitDir);
        for (Row r : rows) {
            if (!STATUS_OK.equals(r.status) || r.dir == null) {
                if (r.optional) {
                    continue; // An absent / skewed OPTIONAL mod is simply not staged
                }
                throw new IOException("mod " + r.id + " is not ready (" + r.status + ") - Refresh and fix it first");
            }
            Path target = r.dir.toAbsolutePath().normalize();
            Path link = root.resolve(target.getFileName().toString());
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("two mod folders share the name " + target.getFileName()
                        + " - rename one of them");
            }
            junction(link, target);
            ids.add(r.id);
            if (r.optional) {
                optionalStaged.add(r.id);
            }
        }
        // The sidecar BEFORE the json (json last = the all-or-nothing law): the game boots from
        // this root, reads the ids and folds its set WITHOUT them (ModSet.OPTIONAL_CACHE_COMMON_NAME).
        if (!optionalStaged.isEmpty()) {
            Files.write(optionalFile, ModSet.optionalIdsText(optionalStaged).getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder sb = new StringBuilder("{\"enabledMods\": [");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(ids.get(i)).append('"');
        }
        sb.append("]}\n");
        Files.write(json, sb.toString().getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /** True for a directory reparse point that is not a symlink (a junction), NOFOLLOW. */
    static boolean isJunction(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isOther();
        } catch (IOException e) {
            return false;
        }
    }

    /** The cmd-argument law for a link or target path: printable, none of the cmd metacharacters
     * a quoted argument cannot neutralise ({@code % ^ & | < > "}). Null = lawful. */
    static String stageableName(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '%' || c == '^' || c == '&' || c == '|' || c == '<'
                    || c == '>' || c == '"') {
                return "the folder name " + path + " has a character the kit cannot link (" + c
                        + ") - rename the folder";
            }
        }
        return null;
    }

    /** {@code mklink /J link target} through {@code cmd.exe} (no privilege needed for a junction;
     * Java has no junction API). Both paths pass the {@link #stageableName} law first. */
    static void junction(Path link, Path target) throws IOException {
        String bad = stageableName(link.toString());
        if (bad == null) {
            bad = stageableName(target.toString());
        }
        if (bad != null) {
            throw new IOException(bad);
        }
        if (!Files.isDirectory(target)) {
            throw new IOException("not a folder: " + target);
        }
        String sysRoot = System.getenv("SystemRoot");
        Path cmd = Paths.get(sysRoot == null || sysRoot.isEmpty() ? "C:\\Windows" : sysRoot, "System32", "cmd.exe");
        Process p = new ProcessBuilder(cmd.toString(), "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        byte[] out;
        int rc;
        try {
            out = p.getInputStream().readAllBytes();
            rc = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while linking " + link);
        }
        if (rc != 0 || !isJunction(link)) {
            throw new IOException("could not link " + link.getFileName() + " (mklink exit " + rc + "): "
                    + new String(out, StandardCharsets.UTF_8).trim());
        }
    }

    // ------------------------------------------------------------------ a tiny JSON reader

    /**
     * A minimal JSON reader for {@code mod_info.json}: objects → {@link LinkedHashMap}, arrays →
     * {@link List}, strings, numbers ({@link Double}), booleans, null. Tolerates what the engine's
     * (old org.json) tokener tolerates in the wild: line and block comments, single-quoted
     * strings, unquoted keys, trailing commas. Throws {@link IllegalArgumentException} on
     * anything else. Game-free, org.json-free (the selftest classpath is the mod jar alone).
     */
    static final class MiniJson {
        private final String s;
        private int i;

        private MiniJson(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            MiniJson p = new MiniJson(text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != p.s.length()) {
                throw p.err("trailing text");
            }
            return v;
        }

        private IllegalArgumentException err(String what) {
            return new IllegalArgumentException(what + " at offset " + i);
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                    i++;
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    int end = s.indexOf("*/", i + 2);
                    i = end < 0 ? s.length() : end + 2;
                } else {
                    return;
                }
            }
        }

        private Object value() {
            if (i >= s.length()) {
                throw err("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"' || c == '\'') {
                return string(c);
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-.0123456789eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (i == start) {
                throw err("unexpected character '" + c + "'");
            }
            try {
                return Double.valueOf(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw err("bad number");
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            while (true) {
                ws();
                if (i >= s.length()) {
                    throw err("unterminated object");
                }
                char c = s.charAt(i);
                if (c == '}') {
                    i++;
                    return m;
                }
                if (c == ',') {
                    i++;
                    continue;
                }
                String key = c == '"' || c == '\'' ? string(c) : bareKey();
                ws();
                if (i >= s.length() || (s.charAt(i) != ':' && s.charAt(i) != '=')) {
                    throw err("expected ':' after key " + key);
                }
                i++;
                ws();
                m.put(key, value());
            }
        }

        private String bareKey() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '$') {
                    i++;
                } else {
                    break;
                }
            }
            if (i == start) {
                throw err("expected a key");
            }
            return s.substring(start, i);
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++; // [
            while (true) {
                ws();
                if (i >= s.length()) {
                    throw err("unterminated array");
                }
                char c = s.charAt(i);
                if (c == ']') {
                    i++;
                    return a;
                }
                if (c == ',') {
                    i++;
                    continue;
                }
                a.add(value());
            }
        }

        private String string(char quote) {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == quote) {
                    return sb.toString();
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw err("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }
    }
}
