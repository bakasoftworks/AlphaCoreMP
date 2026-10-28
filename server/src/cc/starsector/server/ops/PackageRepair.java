package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchShapes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The first start's repair of a downloaded server package folder: the ONE place a packed folder's host files are
 * created, kept in step with the program files beside them, and the host is told what changed. WHY at the start and
 * not in an updater: an update is the operating system's own unzip over the folder (or a hand extract), so whatever
 * the extract left behind - new templates, a dropped program file, a leftover of an older release, host match
 * sidecars keyed to the old kit - is converged by the next start of the folder's NEW code, and re-running the
 * extract is always a safe remedy. Not packed ({@code MANIFEST.txt} absent or malformed, or its {@code pack.stamp}
 * not a {@code yyyyMMdd-HHmmss} release stamp: owner mode, a folder written from source) = a no-op, no line.
 *
 * <p>Every start, under {@code run/update/repair.lock}: the SEEDS (each host file that is absent, created from the
 * program file it comes from: both settings files from their templates, {@code serve-mods/enabled_mods.json}, the
 * standing match pair and each shipped shape pair from {@code match-defaults/}) and the LEFTOVER walk (a regular
 * file in a program tree that the manifest does not list is moved under {@code run/stale/}, never deleted). Only
 * when {@code MANIFEST.txt} differs from {@code run/update/applied/MANIFEST.txt} (the receipt of the LAST repair):
 * the DROPPED program files (listed by the applied manifest, not by the new one, still holding the applied bytes)
 * are removed; the three-way SETTINGS MERGE (the new template, the applied template, the host file); the match
 * RE-KEY of every host {@code match/*.txt.fingerprint} to the new kit + the roster belt; the receipts (written
 * last, the manifest last of all: a crash before it repeats the repair, which is idempotent); the verdict line.
 *
 * <p>Laws: writes land only under the folder (normalized + {@code startsWith}) and never through a link (every
 * existing component from the folder down is tested with {@link PackageFolderWriter#isLink}); every read of a host
 * path is {@code NOFOLLOW_LINKS} and capped; a program file is used as a source only when its bytes hash to its
 * manifest row; the log and the printed lines name paths, the shipped templates' keys and counts - never a value
 * read from a host file; seat-password and token files under {@code match/} are never opened. Never throws on a
 * host-file problem: each problem is one line and the repair goes on.
 *
 *
 */
public final class PackageRepair {

    /** The repair's own directory under the folder's {@code run/}: the lock, the log, the receipts, its temp files. */
    static final String UPDATE_DIR = ServeLauncher.RUN_DIR + "/update";
    static final String APPLIED_DIR = UPDATE_DIR + "/applied";
    static final String APPLIED_MANIFEST = APPLIED_DIR + "/" + ServeLauncher.MANIFEST_FILE;
    static final String LOCK_REL = UPDATE_DIR + "/repair.lock";
    static final String LOG_REL = UPDATE_DIR + "/update.log";
    /** The log past {@link #MAX_LOG_BYTES} is moved here (replacing an older one), so the log stays bounded. */
    static final String LOG_PREVIOUS_REL = UPDATE_DIR + "/update.previous.log";
    static final String STALE_DIR = ServeLauncher.RUN_DIR + "/stale";
    static final String LAUNCHER_TEMPLATE = "launcher.template.properties";
    static final String DEFAULTS_PREFIX = "match-defaults/";
    static final String MATCH_PREFIX = OpsMatchApply.MATCH_DIR + "/";
    static final String STANDING_DEFAULT = DEFAULTS_PREFIX + "match.txt";
    static final String STANDING_DEFAULT_SIDECAR = STANDING_DEFAULT + MatchShapes.SIDECAR_SUFFIX;
    static final String VARIANTS_PREFIX = PackageFolderWriter.DATA_PREFIX + "variants/";
    static final String VARIANT_EXT = ".variant";
    static final String TEMP_SUFFIX = ".repair-tmp";
    static final List<String> UPDATE_SCRIPTS = List.of("update.bat", "update.sh");

    /** A second start waits this long for the first one's repair, then goes on without repairing. */
    static final long LOCK_WAIT_MS = 10_000L;
    static final long LOCK_POLL_MS = 200L;
    /** Bounds of the leftover walk: files visited per start, depth, and files moved per start (the next start goes on). */
    static final int MAX_WALK_FILES = 100_000;
    static final int MAX_WALK_DEPTH = 64;
    static final int MAX_LEFTOVERS = 1000;
    /** Per-file lines (removed / moved / re-keyed / roster) printed per start; the rest go to the log only. */
    static final int MAX_FILE_LINES = 200;
    static final long MAX_LOG_BYTES = 1024L * 1024;
    /** {@code run/stale/<rel>}, then {@code <rel>.1} .. {@code <rel>.9}: an older quarantined copy is never replaced. */
    static final int MAX_STALE_TRIES = 10;
    static final int MAX_SIDECAR_BYTES = (int) PackageKitZip.MAX_SIDECAR_BYTES;

    static final String KEPT = "your settings, passwords, bans, kept ships, mods and recordings are kept";
    static final String ADDED_HEADER = "# Added by the update to %s: settings new in this version, at the values it ships";
    static final String PAIR_INCOMPLETE = "%s: its pair is incomplete - press Apply on it (a missing .fingerprint),"
            + " or delete the other file and start again (a shipped shape is copied back)";
    static final String BUSY = "another start is repairing this folder";

    /** Silent catches (an absent file, a race another start won, an undecodable shape the console names itself). */
    static final AtomicLong QUIET_FAILURES = new AtomicLong();

    /** The program trees: the writer's own trees + the shipped match defaults. Only these are walked for leftovers. */
    static final List<String> PROGRAM_TREES;
    /** The single program files outside those trees (a dropped row is only ever removed inside this set). */
    static final Set<String> PROGRAM_NAMES;

    static {
        List<String> trees = new ArrayList<>(PackageFolderWriter.OWN_TREES);
        trees.add(DEFAULTS_PREFIX);
        PROGRAM_TREES = List.copyOf(trees);
        Set<String> names = new HashSet<>(List.of(PackageFolderWriter.PREMAIN_REL, PackageFolderWriter.HOLDER_REL,
                PackageFolderWriter.C2_REL, PackageFolderWriter.MOD_INFO_REL,
                PackageFolderWriter.MOD_PREFIX + PackageFolderWriter.LICENSE_REL, PackageFolderWriter.ARGV_REL,
                PackageFolderWriter.NOTICES_REL, PackageFolderWriter.LICENSE_REL, PackageFolderWriter.PROPS_TEMPLATE,
                LAUNCHER_TEMPLATE));
        names.addAll(PackageFolderWriter.HOST_WINDOWS);
        names.addAll(PackageFolderWriter.HOST_LINUX);
        names.addAll(UPDATE_SCRIPTS);
        PROGRAM_NAMES = Set.copyOf(names);
    }

    private static final Pattern STAMP = Pattern.compile("[0-9]{8}-[0-9]{6}");
    private static final Pattern GIT_HEX = Pattern.compile("[0-9a-f]{7,64}");
    private static final PrintStream NULL_OUT = new PrintStream(OutputStream.nullOutputStream());

    private PackageRepair() {
    }

    /** True for a release stamp {@code yyyyMMdd-HHmmss} (the packer's); a {@code source-...} stamp or null is not one. */
    static boolean packedStamp(String stamp) {
        return stamp != null && STAMP.matcher(stamp).matches();
    }

    /**
     * Repairs {@code pkg} when it is a packed release folder (else nothing): prints each line to {@code out} (null =
     * print nothing) as it goes AND returns them, the verdict last when there is one. A start with nothing to do
     * prints nothing and returns an empty list. Never throws on a host-file problem.
     */
    public static List<String> repairIfPacked(Path pkg, PrintStream out) {
        return repair(pkg, out, File.separatorChar == '\\', LOCK_WAIT_MS);
    }

    /** {@link #repairIfPacked} with the rel case-folding (Windows) and the lock wait as parameters. */
    static List<String> repair(Path pkg, PrintStream out, boolean foldCase, long lockWaitMs) {
        List<String> said = new ArrayList<>();
        if (pkg == null) {
            return said;
        }
        Path root;
        byte[] manifestBytes;
        PackageManifest now;
        try {
            root = pkg.toAbsolutePath().normalize();
            manifestBytes = readCapped(root.resolve(ServeLauncher.MANIFEST_FILE), PackageManifest.MAX_MANIFEST_BYTES);
            now = PackageManifest.parse(manifestBytes);
        } catch (IOException | RuntimeException notPacked) {
            QUIET_FAILURES.incrementAndGet(); // no readable manifest = not a packed folder: nothing to repair
            return said;
        }
        if (!packedStamp(now.meta.get(OpsRoots.PACK_STAMP))) {
            return said;
        }
        Run run = new Run(root, now, manifestBytes, out, said, foldCase);
        try {
            run.locked(lockWaitMs);
        } catch (RuntimeException unexpected) {
            run.say("the start's repair stopped early (" + simple(unexpected) + ") - the next start tries again");
        }
        return said;
    }

    // =====================================================================================
    // the pure parts (the rows drive these directly)
    // =====================================================================================

    /** The manifests' diff, rels compared case-folded when {@code foldCase}: rows in both whose sha differs, rows only
     *  in the new one, rows only in the old one (a null old = every row added). */
    record Diff(List<String> replaced, List<String> added, List<String> removed) {
    }

    static Diff diff(PackageManifest oldOrNull, PackageManifest now, boolean foldCase) {
        Map<String, String> before = new HashMap<>();
        if (oldOrNull != null) {
            for (Map.Entry<String, String> e : oldOrNull.sha256ByPath.entrySet()) {
                before.put(fold(e.getKey(), foldCase), e.getValue());
            }
        }
        Set<String> after = new HashSet<>();
        List<String> replaced = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, String> e : now.sha256ByPath.entrySet()) {
            String f = fold(e.getKey(), foldCase);
            after.add(f);
            String was = before.get(f);
            if (was == null) {
                added.add(e.getKey());
            } else if (!was.equals(e.getValue())) {
                replaced.add(e.getKey());
            }
        }
        if (oldOrNull != null) {
            for (String rel : oldOrNull.sha256ByPath.keySet()) {
                if (!after.contains(fold(rel, foldCase))) {
                    removed.add(rel);
                }
            }
        }
        return new Diff(List.copyOf(replaced), List.copyOf(added), List.copyOf(removed));
    }

    /** Which reader a settings file is read with: the one its consumer uses, so keys and values agree with it. */
    enum Reader {
        /** {@code cc-server.properties}: {@link Properties#load} (ISO-8859-1, escapes, continuation rows), as the launcher loads it. */
        PROPERTIES,
        /** {@code launcher.properties}: {@link ServeLauncher#readLauncherRows}'s plain rows (UTF-8, the first {@code =} splits, no escapes). */
        LAUNCHER_ROWS
    }

    /**
     * One file's merge result: {@code bytes} = the merged file (null = nothing to write); {@code lines} = what is said
     * whether or not it is written (kept, stays removed, a problem); {@code doneLines} = true only once the bytes are
     * written (added, re-tuned); {@code retry} = a read problem the next start should try again.
     */
    record Merged(byte[] bytes, List<String> lines, List<String> doneLines, int appended, int retuned, boolean retry) {
    }

    /**
     * The three-way settings merge of ONE file, pure. For each key of the new template's live rows, in template
     * order: absent in the host file and absent in the old template = APPENDED (the template's comment lines directly
     * above it + the row verbatim, after one blank line and the dated header, once per merge); absent in the host file
     * but in the old template = the host removed it, untouched; the host value equal to the old template's and the new
     * template's value different = the VALUE rewritten in place on the row's own line (key, separator and line break
     * kept; a row spanning lines or a key present twice = untouched); a host value that differs from both = untouched.
     * A null old template (no receipt yet, or none in that version) = appends only, nothing rewritten. Everything
     * else keeps its bytes. The result is re-read with the same reader and must read as the host file plus exactly
     * the changes, else nothing is written. {@code file} names the file in the lines; keys are masked.
     */
    static Merged mergeOne(String file, byte[] host, byte[] oldTemplate, byte[] newTemplate, Reader reader,
            String newStamp) {
        List<String> lines = new ArrayList<>();
        Doc h;
        Doc t;
        Doc o = null;
        try {
            h = Doc.parse(host, reader);
        } catch (IOException | RuntimeException unreadable) {
            lines.add(file + " cannot be read as settings (" + simple(unreadable) + ") - not merged; the next start"
                    + " tries again");
            return new Merged(null, lines, List.of(), 0, 0, true);
        }
        try {
            t = Doc.parse(newTemplate, reader);
        } catch (IOException | RuntimeException unreadable) {
            lines.add("the template of " + file + " cannot be read as settings (" + simple(unreadable) + ") - not merged");
            return new Merged(null, lines, List.of(), 0, 0, true);
        }
        if (oldTemplate != null) {
            try {
                o = Doc.parse(oldTemplate, reader);
            } catch (IOException | RuntimeException unreadable) {
                lines.add("the last applied template of " + file + " cannot be read (" + simple(unreadable)
                        + ") - only settings new to this version are added");
            }
        }
        Map<String, String> expected = new HashMap<>(h.values);
        TreeMap<Integer, Edit> edits = new TreeMap<>();
        List<Row> adds = new ArrayList<>();
        List<String> done = new ArrayList<>();
        int retuned = 0;
        for (String k : t.byKey.keySet()) {
            String key = PackageManifest.mask(k);
            String newValue = t.values.get(k);
            boolean oldHas = o != null && o.values.containsKey(k);
            if (!h.values.containsKey(k)) {
                if (oldHas) {
                    lines.add("settings: " + key + " stays removed (" + file + ")");
                } else {
                    adds.add(t.lastRow(k));
                    expected.put(k, newValue);
                    done.add("settings: " + key + " added at this version's default (" + file + ")");
                }
                continue;
            }
            if (!oldHas) {
                continue;
            }
            String oldValue = o.values.get(k);
            String hostValue = h.values.get(k);
            if (Objects.equals(oldValue, newValue) || Objects.equals(hostValue, newValue)) {
                continue;
            }
            if (!Objects.equals(hostValue, oldValue)) {
                lines.add("settings: " + key + " kept at your value; this version ships another default (" + file + ")");
                continue;
            }
            List<Row> hostRows = h.byKey.get(k);
            List<Row> newRows = t.byKey.get(k);
            if (hostRows.size() != 1 || newRows.size() != 1 || !hostRows.get(0).singleLine()
                    || !newRows.get(0).singleLine()) {
                lines.add("settings: " + key + " not re-tuned: its row spans lines or appears twice (" + file + ")");
                continue;
            }
            Row hr = hostRows.get(0);
            Row tr = newRows.get(0);
            String raw = t.text.substring(tr.valueStart(), tr.valueEnd());
            int line = hr.firstLine();
            String edited = h.text.substring(h.start[line], hr.valueStart()) + raw + h.text.substring(hr.valueEnd(), h.end[line]);
            if (!readsAs(edited, reader, k, newValue)) {
                // e.g. a host row with no separator: the shipped value would fuse into the key
                lines.add("settings: " + key + " not re-tuned: its row has another shape than the shipped one (" + file + ")");
                continue;
            }
            edits.put(hr.valueStart(), new Edit(hr.valueStart(), hr.valueEnd(), raw));
            expected.put(k, newValue);
            done.add("settings: " + key + " re-tuned to this version's default (" + file + ")");
            retuned++;
        }
        if (edits.isEmpty() && adds.isEmpty()) {
            return new Merged(null, lines, List.of(), 0, 0, false);
        }
        StringBuilder sb = new StringBuilder(h.text.length() + 1024);
        int at = 0;
        for (Edit e : edits.values()) {
            sb.append(h.text, at, e.start()).append(e.text());
            at = e.end();
        }
        sb.append(h.text, at, h.text.length());
        if (!adds.isEmpty()) {
            String eol = eolOf(h.text, t.text);
            char lastChar = sb.length() == 0 ? '\n' : sb.charAt(sb.length() - 1);
            if (lastChar != '\n' && lastChar != '\r') {
                sb.append(eol);
            }
            sb.append(eol).append(String.format(ADDED_HEADER, newStamp)).append(eol);
            for (Row r : adds) {
                for (int li = t.commentRunStart(r.firstLine()); li <= r.lastLine(); li++) {
                    sb.append(t.content(li)).append(eol);
                }
            }
        }
        byte[] merged = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        Map<String, String> reread;
        try {
            reread = Doc.parse(merged, reader).values;
        } catch (IOException | RuntimeException unreadable) {
            QUIET_FAILURES.incrementAndGet(); // the line below names the outcome
            reread = null;
        }
        if (!expected.equals(reread)) {
            lines.add(file + " was not merged: the merged file would read differently - left as it is");
            return new Merged(null, lines, List.of(), 0, 0, false);
        }
        return new Merged(merged, lines, List.copyOf(done), adds.size(), retuned, false);
    }

    /** True when the one line {@code edited} reads, with {@code reader}, as exactly {@code key} = {@code value}. */
    private static boolean readsAs(String edited, Reader reader, String key, String value) {
        try {
            Map<String, String> m = Doc.parse(edited.getBytes(StandardCharsets.ISO_8859_1), reader).values;
            return m.size() == 1 && value.equals(m.get(key));
        } catch (IOException | RuntimeException unreadable) {
            QUIET_FAILURES.incrementAndGet(); // the caller's "not re-tuned" line is the record
            return false;
        }
    }

    /** The re-key of ONE host sidecar to the kit lines: re-keyed bytes, unchanged (the content halves already agree),
     *  or cannot (no usable row, or a composite that does not re-compose: the host's remedy line). */
    enum RekeyOutcome {
        REKEYED, UNCHANGED, CANNOT
    }

    record Rekey(RekeyOutcome outcome, byte[] bytes) {
    }

    static Rekey rekeyOne(byte[] hostSidecar, List<String> kitLines) {
        byte[] rekeyed = PackageKitZip.rekeyedToKitOrNull(hostSidecar, kitLines);
        if (rekeyed != null) {
            return new Rekey(RekeyOutcome.REKEYED, rekeyed);
        }
        String mine = hostSidecar == null ? null : contentHalfOrNull(sidecarLines(hostSidecar));
        String kit = kitLines == null ? null : contentHalfOrNull(kitLines);
        return new Rekey(mine != null && mine.equals(kit) ? RekeyOutcome.UNCHANGED : RekeyOutcome.CANNOT, null);
    }

    /** A sidecar's lines as the kit reader splits them (ISO-8859-1, on line breaks, the terminal empty element kept). */
    static List<String> sidecarLines(byte[] bytes) {
        return Arrays.asList(new String(bytes, StandardCharsets.ISO_8859_1).split("\r?\n", -1));
    }

    /** A sidecar's composite without its match term, trimmed; null when it carries no usable {@code fingerprint=} row. */
    static String contentHalfOrNull(List<String> sidecarLines) {
        try {
            return ContentFingerprints.stripMatch(
                    ContentFingerprints.parseSidecarFingerprint(sidecarLines, "a match sidecar")).trim();
        } catch (IllegalArgumentException noRow) {
            QUIET_FAILURES.incrementAndGet(); // the caller's CANNOT / kit line is the record
            return null;
        }
    }

    // =====================================================================================
    // the settings file model
    // =====================================================================================

    /** One live row: key + value as the reader reads them, its physical lines, and (single-line rows) its value span. */
    record Row(String key, String value, int firstLine, int lastLine, int valueStart, int valueEnd) {
        boolean singleLine() {
            return firstLine == lastLine && valueStart >= 0;
        }
    }

    private record Edit(int start, int end, String text) {
    }

    /**
     * A settings file as one reader reads it. The text is the ISO-8859-1 decoding of the bytes, so every offset is a
     * byte offset and the structural characters ({@code = : # ! \} quotes, white space, line breaks) are found the
     * same way in a UTF-8 file; keys and values are decoded with the reader's own charset.
     */
    static final class Doc {
        final String text;
        final int bom;
        final int[] start;
        final int[] end;
        final boolean[] comment;
        final Map<String, List<Row>> byKey = new LinkedHashMap<>();
        Map<String, String> values = Map.of();

        private Doc(String text, int bom) {
            this.text = text;
            this.bom = bom;
            List<int[]> spans = new ArrayList<>();
            int s = 0;
            int n = text.length();
            while (s < n) {
                int e = s;
                while (e < n && text.charAt(e) != '\n' && text.charAt(e) != '\r') {
                    e++;
                }
                spans.add(new int[] {s, e});
                s = e < n && text.charAt(e) == '\r' && e + 1 < n && text.charAt(e + 1) == '\n' ? e + 2 : e + 1;
            }
            start = new int[spans.size()];
            end = new int[spans.size()];
            comment = new boolean[spans.size()];
            for (int i = 0; i < spans.size(); i++) {
                start[i] = spans.get(i)[0];
                end[i] = spans.get(i)[1];
            }
        }

        static Doc parse(byte[] bytes, Reader reader) throws IOException {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            return reader == Reader.PROPERTIES ? properties(text) : launcherRows(text);
        }

        int lines() {
            return start.length;
        }

        /** The line's text without its line break (the first line without a UTF-8 BOM). */
        String content(int line) {
            return text.substring(line == 0 ? Math.min(start[0] + bom, end[0]) : start[line], end[line]);
        }

        /** The first of the comment lines directly above {@code line} ({@code line} itself when there are none). */
        int commentRunStart(int line) {
            int li = line;
            while (li > 0 && comment[li - 1]) {
                li--;
            }
            return li;
        }

        Row lastRow(String key) {
            List<Row> rows = byKey.get(key);
            return rows.get(rows.size() - 1);
        }

        private void add(Row r) {
            byKey.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r);
        }

        /** {@link Properties} logical rows: a line ending in an odd run of backslashes continues; each row's key and
         *  value come from loading that row's text alone, and the whole file loaded at once must read the same. */
        private static Doc properties(String text) throws IOException {
            Doc d = new Doc(text, 0);
            int n = d.lines();
            for (int li = 0; li < n; ) {
                int p = d.start[li];
                while (p < d.end[li] && propertiesSpace(text.charAt(p))) {
                    p++;
                }
                if (p == d.end[li]) {
                    li++;
                    continue;
                }
                char c = text.charAt(p);
                if (c == '#' || c == '!') {
                    d.comment[li] = true;
                    li++;
                    continue;
                }
                int last = li;
                while (last + 1 < n && oddTrailingBackslashes(text, d.start[last], d.end[last])) {
                    last++;
                }
                Properties one = new Properties();
                one.load(new StringReader(text.substring(d.start[li], d.end[last])));
                if (one.size() != 1) {
                    throw new IOException("the row at line " + (li + 1) + " reads as " + one.size() + " settings");
                }
                String key = one.stringPropertyNames().iterator().next();
                int vs = last == li ? propertiesValueStart(text, p, d.end[li]) : -1;
                d.add(new Row(key, one.getProperty(key), li, last, vs, last == li ? d.end[li] : -1));
                li = last + 1;
            }
            Properties all = new Properties();
            all.load(new StringReader(text));
            Map<String, String> whole = new HashMap<>();
            for (String k : all.stringPropertyNames()) {
                whole.put(k, all.getProperty(k));
            }
            if (!whole.equals(d.lastValues())) {
                throw new IOException("its rows read differently one by one than as a whole");
            }
            d.values = whole;
            return d;
        }

        /** {@link ServeLauncher#readLauncherRows}'s grammar line by line: trimmed, {@code #} / {@code !} comments,
         *  the first {@code =} splits (not at the start), key and value trimmed, one pair of quotes stripped, UTF-8,
         *  a leading BOM ignored; a later row of a key wins. */
        private static Doc launcherRows(String text) {
            int bom = text.length() >= 3 && text.charAt(0) == (char) 0xEF && text.charAt(1) == (char) 0xBB
                    && text.charAt(2) == (char) 0xBF ? 3 : 0;
            Doc d = new Doc(text, bom);
            for (int li = 0; li < d.lines(); li++) {
                int a = li == 0 ? Math.min(d.start[0] + bom, d.end[0]) : d.start[li];
                int b = d.end[li];
                while (a < b && text.charAt(a) <= ' ') {
                    a++;
                }
                while (b > a && text.charAt(b - 1) <= ' ') {
                    b--;
                }
                if (a == b) {
                    continue;
                }
                char c = text.charAt(a);
                if (c == '#' || c == '!') {
                    d.comment[li] = true;
                    continue;
                }
                int eq = text.indexOf('=', a);
                if (eq < 0 || eq >= b || eq == a) {
                    continue;
                }
                String key = utf8(text, a, eq).trim();
                int vs = eq + 1;
                while (vs < b && text.charAt(vs) <= ' ') {
                    vs++;
                }
                String value = utf8(text, vs, b);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1).trim();
                }
                d.add(new Row(key, value, li, li, vs, b));
            }
            d.values = d.lastValues();
            return d;
        }

        private Map<String, String> lastValues() {
            Map<String, String> m = new HashMap<>();
            for (List<Row> rows : byKey.values()) {
                m.put(rows.get(0).key(), rows.get(rows.size() - 1).value());
            }
            return m;
        }
    }

    private static boolean propertiesSpace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static boolean oddTrailingBackslashes(String text, int from, int to) {
        int run = 0;
        for (int i = to - 1; i >= from && text.charAt(i) == '\\'; i--) {
            run++;
        }
        return (run & 1) == 1;
    }

    /** Where {@link Properties#load} starts the value of a one-line row whose key starts at {@code p}: the key ends at
     *  the first unescaped {@code =}, {@code :} or white space; then white space and at most one separator are skipped. */
    private static int propertiesValueStart(String text, int p, int limit) {
        int keyEnd = p;
        int valueStart = limit;
        boolean hasSep = false;
        boolean escaped = false;
        while (keyEnd < limit) {
            char c = text.charAt(keyEnd);
            if ((c == '=' || c == ':') && !escaped) {
                valueStart = keyEnd + 1;
                hasSep = true;
                break;
            }
            if (propertiesSpace(c) && !escaped) {
                valueStart = keyEnd + 1;
                break;
            }
            escaped = c == '\\' && !escaped;
            keyEnd++;
        }
        while (valueStart < limit) {
            char c = text.charAt(valueStart);
            if (!propertiesSpace(c)) {
                if (!hasSep && (c == '=' || c == ':')) {
                    hasSep = true;
                } else {
                    break;
                }
            }
            valueStart++;
        }
        return valueStart;
    }

    private static String utf8(String latin1, int from, int to) {
        return new String(latin1.substring(from, to).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    /** The host file's own line break (CRLF / LF), else the template's, else LF. */
    private static String eolOf(String host, String template) {
        if (host.contains("\r\n")) {
            return "\r\n";
        }
        if (host.indexOf('\n') >= 0) {
            return "\n";
        }
        return template.contains("\r\n") ? "\r\n" : "\n";
    }

    // =====================================================================================
    // shared helpers
    // =====================================================================================

    static String fold(String rel, boolean foldCase) {
        return foldCase ? rel.toLowerCase(Locale.ROOT) : rel;
    }

    /** A dropped manifest row is only ever removed when it names a program file of the package's own layout. */
    static boolean programRel(String rel, String packKit, boolean foldCase) {
        String f = fold(rel, foldCase);
        for (String tree : PROGRAM_TREES) {
            if (f.startsWith(fold(tree, foldCase))) {
                return true;
            }
        }
        for (String name : PROGRAM_NAMES) {
            if (f.equals(fold(name, foldCase))) {
                return true;
            }
        }
        return plainName(packKit) && f.equals(fold(PackageFolderWriter.KITS_PREFIX + packKit, foldCase));
    }

    static boolean inProgramTree(String rel, boolean foldCase) {
        String f = fold(rel, foldCase);
        for (String tree : PROGRAM_TREES) {
            if (f.startsWith(fold(tree, foldCase))) {
                return true;
            }
        }
        return false;
    }

    private static boolean plainName(String name) {
        return name != null && !name.isEmpty() && name.indexOf('/') < 0 && PackageManifest.safeRelative(name);
    }

    /** A bounded read that never follows a link at the last component (past {@code cap} = IOException, never truncated). */
    static byte[] readCapped(Path p, long cap) throws IOException {
        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            byte[] b = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 16L, cap + 1));
            if (b.length > cap) {
                throw new IOException("over the " + cap + " B read cap");
            }
            return b;
        }
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /** Streamed sha256 of one file, never following a link at the last component. */
    static String sha256Hex(Path p) throws IOException {
        MessageDigest sha = sha256();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                sha.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every Java runtime ships SHA-256", impossible);
        }
    }

    private static String simple(Throwable t) {
        return t.getClass().getSimpleName();
    }

    /** A rel as the host reads it: the platform's separator, bounded printable ASCII, no trailing separator. */
    static String shown(String rel) {
        String r = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
        return PackageManifest.mask(r.replace('/', File.separatorChar));
    }

    private static String updateScript() {
        return File.separatorChar == '\\' ? UPDATE_SCRIPTS.get(0) : UPDATE_SCRIPTS.get(1);
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? "windows" : os.startsWith("linux") ? "linux" : "other";
    }

    private static String stampOf(PackageManifest m) {
        if (m == null) {
            return "none";
        }
        String s = m.meta.get(OpsRoots.PACK_STAMP);
        return packedStamp(s) ? s : "unknown";
    }

    private static String gitOf(PackageManifest m) {
        if (m == null) {
            return "none";
        }
        String g = m.meta.get(PackageManifest.GIT_HEAD);
        g = g == null ? "" : g.toLowerCase(Locale.ROOT);
        return GIT_HEX.matcher(g).matches() ? g.substring(0, Math.min(9, g.length())) : "unknown";
    }

    // =====================================================================================
    // one repair
    // =====================================================================================

    private static final class Run {
        final Path root;
        final PackageManifest now;
        final byte[] nowBytes;
        final String newStamp;
        final PrintStream out;
        final List<String> said;
        final boolean foldCase;
        final List<String> log = new ArrayList<>();
        final List<String> tails = new ArrayList<>();
        final Map<String, byte[]> verified = new HashMap<>();
        final Set<String> listedFolded = new HashSet<>();
        PackageManifest old;
        boolean retry;
        boolean matchLinkSaid;
        int appended;
        int fileLines;
        int suppressed;
        int walked;

        Run(Path root, PackageManifest now, byte[] nowBytes, PrintStream out, List<String> said, boolean foldCase) {
            this.root = root;
            this.now = now;
            this.nowBytes = nowBytes;
            this.newStamp = now.meta.get(OpsRoots.PACK_STAMP);
            this.out = out;
            this.said = said;
            this.foldCase = foldCase;
            for (String rel : now.sha256ByPath.keySet()) {
                listedFolded.add(fold(rel, foldCase));
            }
        }

        void say(String line) {
            said.add(line);
            log.add(line);
            if (out != null) {
                out.println(line);
            }
        }

        /** A per-file line: always logged, printed for the first {@link #MAX_FILE_LINES} of a start. */
        void sayFile(String line) {
            if (fileLines++ < MAX_FILE_LINES) {
                say(line);
            } else {
                log.add(line);
                suppressed++;
            }
        }

        /** A verdict tail: logged now, the first one rides the verdict line. */
        void tail(String line) {
            log.add(line);
            tails.add(line);
        }

        /** {@code rel} under the folder with no link on any existing component down from it, else null. */
        Path safe(String rel) {
            Path t;
            try {
                t = root.resolve(rel).normalize();
            } catch (RuntimeException badName) {
                QUIET_FAILURES.incrementAndGet(); // the caller's "link or odd name" line is the record
                return null;
            }
            if (!t.startsWith(root) || t.equals(root)) {
                return null;
            }
            for (Path p = t; p != null && !p.equals(root); p = p.getParent()) {
                if (PackageFolderWriter.isLink(p)) {
                    return null;
                }
            }
            return t;
        }

        private static String linked(String rel, String what) {
            return shown(rel) + ": a link (or an odd name) on its path - " + what;
        }

        boolean present(String rel) {
            Path p = safe(rel);
            return p == null || Files.exists(p, LinkOption.NOFOLLOW_LINKS);
        }

        void locked(long waitMs) {
            Path dir = safe(UPDATE_DIR);
            Path lockFile = safe(LOCK_REL);
            if (dir == null || lockFile == null) {
                say(linked(UPDATE_DIR, "the start's repair is skipped"));
                return;
            }
            FileChannel ch;
            try {
                Files.createDirectories(dir);
                ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException unwritable) {
                say(shown(UPDATE_DIR) + " cannot be written (" + simple(unwritable) + ") - the start's repair is"
                        + " skipped; move the package to a folder you can write");
                return;
            }
            FileLock lock = null;
            try {
                lock = acquire(ch, waitMs);
                if (lock == null) {
                    say(BUSY);
                    return;
                }
                body();
            } finally {
                try {
                    if (lock != null) {
                        lock.release();
                    }
                    ch.close();
                } catch (IOException closing) {
                    QUIET_FAILURES.incrementAndGet(); // the lock dies with the channel / the JVM anyway
                }
            }
        }

        /** tryLock, polled until {@code waitMs} passes; null = another start (or another thread here) holds it. */
        private FileLock acquire(FileChannel ch, long waitMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMs));
            while (true) {
                try {
                    FileLock l = ch.tryLock();
                    if (l != null) {
                        return l;
                    }
                } catch (OverlappingFileLockException sameJvm) {
                    QUIET_FAILURES.incrementAndGet(); // another thread of this JVM is repairing: wait as for another start
                } catch (IOException unlockable) {
                    say(shown(LOCK_REL) + " cannot be locked (" + simple(unlockable) + ") - the start's repair is skipped");
                    return null;
                }
                if (System.nanoTime() - deadline >= 0) {
                    return null;
                }
                try {
                    Thread.sleep(LOCK_POLL_MS);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        void body() {
            byte[] appliedBytes = null;
            Path appliedPath = safe(APPLIED_MANIFEST);
            if (appliedPath == null) {
                say(linked(APPLIED_MANIFEST, "this start treats the folder as freshly unpacked"));
            } else if (Files.exists(appliedPath, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    appliedBytes = readCapped(appliedPath, PackageManifest.MAX_MANIFEST_BYTES);
                    old = PackageManifest.parse(appliedBytes);
                } catch (IOException | RuntimeException unreadable) {
                    appliedBytes = null;
                    old = null;
                    say(shown(APPLIED_MANIFEST) + " cannot be read (" + simple(unreadable) + ") - this start treats"
                            + " the folder as freshly unpacked");
                }
            }
            boolean full = appliedBytes == null || !Arrays.equals(appliedBytes, nowBytes);
            boolean settingsExisted = present(PackageFolderWriter.PROPS_REL) || present(PackageFolderWriter.LAUNCHER_REL);
            seeds();
            if (full && old != null) {
                dropped();
            }
            leftovers();
            if (!full) {
                finish(null);
                return;
            }
            merge(PackageFolderWriter.PROPS_REL, PackageFolderWriter.PROPS_TEMPLATE, Reader.PROPERTIES,
                    ServeLauncher.MAX_CONFIG_BYTES);
            merge(PackageFolderWriter.LAUNCHER_REL, LAUNCHER_TEMPLATE, Reader.LAUNCHER_ROWS,
                    ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES);
            List<String> kit = kitLinesOrNull();
            if (kit != null) {
                rekeyAll(kit);
                rosterBelt(kit);
            }
            receipts();
            finish(verdict(settingsExisted));
        }

        // ---- (b) the seeds ----

        void seeds() {
            seedFile(PackageFolderWriter.PROPS_REL, PackageFolderWriter.PROPS_TEMPLATE, ServeLauncher.MAX_CONFIG_BYTES);
            seedFile(PackageFolderWriter.LAUNCHER_REL, LAUNCHER_TEMPLATE, ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES);
            seedTo(PackageFolderWriter.ENABLED_REL, PackageFolderWriter.ENABLED_MODS.getBytes(StandardCharsets.US_ASCII));
            seedPair(PackageFolderWriter.MATCH_REL, STANDING_DEFAULT);
            for (String shape : shippedShapeDefaults()) {
                seedPair(MATCH_PREFIX + shape.substring(DEFAULTS_PREFIX.length()), shape);
            }
        }

        /** The listed {@code match-defaults/match-<name>.txt} rows whose {@code .fingerprint} is listed too, sorted. */
        private Set<String> shippedShapeDefaults() {
            Set<String> out = new TreeSet<>();
            String head = DEFAULTS_PREFIX + MatchShapes.SHAPE_PREFIX;
            for (String rel : now.sha256ByPath.keySet()) {
                if (!rel.startsWith(head) || !rel.endsWith(MatchShapes.TXT) || rel.contains(MatchShapes.TOKENS_MARK)) {
                    continue;
                }
                String name = rel.substring(head.length(), rel.length() - MatchShapes.TXT.length());
                if (MatchShapes.NAME_RULE.matcher(name).matches()
                        && now.sha256ByPath.containsKey(rel + MatchShapes.SIDECAR_SUFFIX)
                        && out.size() < MatchShapes.MAX_FOLDER_SHAPES) {
                    out.add(rel);
                }
            }
            return out;
        }

        void seedFile(String target, String source, long cap) {
            Path t = seedTarget(target);
            if (t == null) {
                return;
            }
            byte[] src = programBytes(source, cap);
            if (src != null && createNew(target, t, src)) {
                say("seeded " + shown(target));
            }
        }

        void seedTo(String target, byte[] bytes) {
            Path t = seedTarget(target);
            if (t != null && createNew(target, t, bytes)) {
                say("seeded " + shown(target));
            }
        }

        /** The target to seed, or null: present (a link there = one warning line, never written through) or unsafe. */
        private Path seedTarget(String target) {
            Path t = safe(target);
            if (t == null) {
                say(linked(target, "not created"));
                return null;
            }
            if (Files.exists(t, LinkOption.NOFOLLOW_LINKS)) {
                if (PackageFolderWriter.isLink(t)) {
                    say(shown(target) + " is a link - left as it is, never written through");
                }
                return null;
            }
            return t;
        }

        /** A match pair is seeded only when BOTH halves are absent ({@code .fingerprint} first, as the shape writer). */
        void seedPair(String txtTarget, String txtSource) {
            String fpTarget = txtTarget + MatchShapes.SIDECAR_SUFFIX;
            if (safe(OpsMatchApply.MATCH_DIR) == null) {
                if (!matchLinkSaid) {
                    matchLinkSaid = true;
                    say(linked(OpsMatchApply.MATCH_DIR, "no match file is created"));
                }
                return;
            }
            Path tt = safe(txtTarget);
            Path ft = safe(fpTarget);
            if (tt == null || ft == null) {
                say(linked(txtTarget, "its pair is not created"));
                return;
            }
            boolean txtThere = Files.exists(tt, LinkOption.NOFOLLOW_LINKS);
            boolean fpThere = Files.exists(ft, LinkOption.NOFOLLOW_LINKS);
            if (txtThere && fpThere) {
                return;
            }
            if (txtThere || fpThere) {
                say(String.format(PAIR_INCOMPLETE, shown(txtTarget)));
                return;
            }
            byte[] fp = programBytes(txtSource + MatchShapes.SIDECAR_SUFFIX, MAX_SIDECAR_BYTES);
            byte[] txt = programBytes(txtSource, MatchShapes.MAX_DESCRIPTOR_BYTES);
            if (fp == null || txt == null || !createNew(fpTarget, ft, fp)) {
                return;
            }
            if (createNew(txtTarget, tt, txt)) {
                say("seeded " + shown(txtTarget) + " and its .fingerprint");
            } else {
                deleteOwn(ft); // never leave half a pair this start made
            }
        }

        /** A direct CREATE_NEW write (no temp: a rename may replace on some systems; the repair lock covers the race). */
        private boolean createNew(String rel, Path t, byte[] bytes) {
            OutputStream os;
            try {
                Files.createDirectories(t.getParent());
                os = Files.newOutputStream(t, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (FileAlreadyExistsException raced) {
                QUIET_FAILURES.incrementAndGet(); // another start created it first: theirs stands
                return false;
            } catch (IOException | RuntimeException e) {
                say(shown(rel) + " could not be created (" + simple(e) + ")");
                return false;
            }
            try (OutputStream o = os) {
                o.write(bytes);
                return true;
            } catch (IOException e) {
                say(shown(rel) + " could not be written whole (" + simple(e) + ") - removed; the next start creates it again");
                deleteOwn(t);
                return false;
            }
        }

        private void deleteOwn(Path p) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException | RuntimeException e) {
                QUIET_FAILURES.incrementAndGet(); // the next start's pair / seed check names what is left
            }
        }

        /**
         * A listed program file's bytes when they hash to its manifest row (cached per start); null when the row is
         * absent (silent: not part of this layout) or the file is missing, unreadable, over {@code cap} or differs
         * (one line, and the full repair repeats at the next start).
         */
        byte[] programBytes(String rel, long cap) {
            if (verified.containsKey(rel)) {
                return verified.get(rel);
            }
            byte[] got = null;
            String want = now.sha256ByPath.get(rel);
            if (want != null) {
                Path p = safe(rel);
                String remedy = " - run " + updateScript() + " again with the zip you downloaded";
                if (p == null) {
                    say(linked(rel, "not used"));
                    retry = true;
                } else {
                    try {
                        byte[] b = readCapped(p, cap);
                        if (sha256Hex(b).equals(want)) {
                            got = b;
                        } else {
                            say(shown(rel) + " does not match the package's file list" + remedy);
                            retry = true;
                        }
                    } catch (NoSuchFileException absent) {
                        say(shown(rel) + " is missing" + remedy);
                        retry = true;
                    } catch (IOException | RuntimeException unreadable) {
                        say(shown(rel) + " cannot be read (" + simple(unreadable) + ")" + remedy);
                        retry = true;
                    }
                }
            }
            verified.put(rel, got);
            return got;
        }

        // ---- (c) the dropped program files ----

        void dropped() {
            String oldKit = old.packKit();
            for (Map.Entry<String, String> e : old.sha256ByPath.entrySet()) {
                String rel = e.getKey();
                if (listedFolded.contains(fold(rel, foldCase))) {
                    continue;
                }
                if (!programRel(rel, oldKit, foldCase)) {
                    QUIET_FAILURES.incrementAndGet(); // not a program file of this layout: never ours to remove
                    continue;
                }
                Path p = safe(rel);
                if (p == null) {
                    say(linked(rel, "left as it is"));
                    continue;
                }
                BasicFileAttributes a;
                String sha;
                try {
                    a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!a.isRegularFile()) {
                        say(shown(rel) + " is not a plain file - left as it is");
                        continue;
                    }
                    sha = sha256Hex(p);
                } catch (NoSuchFileException gone) {
                    QUIET_FAILURES.incrementAndGet(); // already gone: nothing to remove
                    continue;
                } catch (IOException | RuntimeException unreadable) {
                    say(shown(rel) + " cannot be read (" + simple(unreadable) + ") - left as it is; the next start tries again");
                    retry = true;
                    continue;
                }
                if (sha.equals(e.getValue())) {
                    try {
                        Files.delete(p);
                        sayFile("removed " + shown(rel) + ": no longer part of the package");
                    } catch (NoSuchFileException gone) {
                        QUIET_FAILURES.incrementAndGet(); // removed by someone else meanwhile
                    } catch (IOException | RuntimeException locked) {
                        say(shown(rel) + " could not be removed (" + simple(locked) + ") - the next start tries again");
                        retry = true;
                    }
                } else if (inProgramTree(rel, foldCase)) {
                    toStale(rel, p, "edited by you, no longer part of the package");
                } else {
                    sayFile("kept " + shown(rel) + ": edited by you, no longer part of the package");
                }
            }
        }

        // ---- (d) the leftovers ----

        void leftovers() {
            List<String> found = new ArrayList<>();
            for (String tree : PROGRAM_TREES) {
                String dirRel = tree.substring(0, tree.length() - 1);
                Path dir = safe(dirRel);
                if (dir == null) {
                    say(linked(dirRel, "not checked for left-over files"));
                    continue;
                }
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                walk(dir, found);
                if (found.size() >= MAX_LEFTOVERS || walked > MAX_WALK_FILES) {
                    break;
                }
            }
            for (String rel : found) {
                Path p = safe(rel);
                if (p != null) {
                    toStale(rel, p, "not part of this package (left over from an older one, or copied in by hand)");
                }
            }
        }

        private void walk(Path dir, List<String> found) {
            try {
                Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), MAX_WALK_DEPTH, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        // a junction reads as a directory AND "other": never descended (its files are not the folder's)
                        return a.isOther() || a.isSymbolicLink() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        if (++walked > MAX_WALK_FILES) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (a.isRegularFile()) {
                            String rel = root.relativize(f).toString().replace('\\', '/');
                            if (!listedFolded.contains(fold(rel, foldCase))) {
                                found.add(rel);
                                if (found.size() >= MAX_LEFTOVERS) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) {
                        QUIET_FAILURES.incrementAndGet(); // an unreadable entry is left where it is
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException unwalkable) {
                say(shown(root.relativize(dir).toString().replace('\\', '/')) + " cannot be checked for left-over files ("
                        + simple(unwalkable) + ")");
            }
        }

        /** Moves one file to {@code run/stale/<rel>} (or the first free {@code .1} .. {@code .9} beside it): never deleted. */
        private void toStale(String rel, Path src, String why) {
            for (int i = 0; i < MAX_STALE_TRIES; i++) {
                String dstRel = STALE_DIR + "/" + rel + (i == 0 ? "" : "." + i);
                Path dst = safe(dstRel);
                if (dst == null) {
                    say(linked(STALE_DIR, shown(rel) + " is left as it is"));
                    return;
                }
                if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Files.createDirectories(dst.getParent());
                    Files.move(src, dst);
                    sayFile("moved " + shown(rel) + " to " + shown(STALE_DIR) + ": " + why);
                    return;
                } catch (FileAlreadyExistsException raced) {
                    QUIET_FAILURES.incrementAndGet(); // taken meanwhile: the next name
                } catch (IOException | RuntimeException unmovable) {
                    say(shown(rel) + " could not be moved to " + shown(STALE_DIR) + " (" + simple(unmovable)
                            + ") - left as it is");
                    retry = true;
                    return;
                }
            }
            say(shown(rel) + ": " + shown(STALE_DIR) + " already holds " + MAX_STALE_TRIES + " copies of it - left as it is");
        }

        // ---- (e) the settings merge ----

        void merge(String hostRel, String templateRel, Reader reader, long cap) {
            byte[] fresh = programBytes(templateRel, cap);
            if (fresh == null) {
                return;
            }
            Path host = safe(hostRel);
            if (host == null) {
                say(linked(hostRel, "not merged"));
                return;
            }
            if (!Files.exists(host, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!Files.isRegularFile(host, LinkOption.NOFOLLOW_LINKS)) {
                say(shown(hostRel) + " is not a plain file - not merged");
                return;
            }
            byte[] mine;
            try {
                mine = readCapped(host, cap);
            } catch (IOException | RuntimeException unreadable) {
                say(shown(hostRel) + " cannot be read (" + simple(unreadable) + ") - not merged; the next start tries again");
                retry = true;
                return;
            }
            if (reader == Reader.LAUNCHER_ROWS && !launcherReadAgrees(host, mine)) {
                say(shown(hostRel) + " reads differently from the launcher's own read - not merged; the next start tries again");
                retry = true;
                return;
            }
            Merged m = mergeOne(shown(hostRel), mine, appliedTemplateOrNull(templateRel, cap), fresh, reader, newStamp);
            for (String line : m.lines()) {
                say(line);
            }
            if (m.retry()) {
                retry = true;
            }
            if (m.bytes() != null && replaceAtomic(hostRel, host, m.bytes(), reader)) {
                appended += m.appended();
                for (String line : m.doneLines()) {
                    say(line);
                }
            }
        }

        /** The launcher's own read of the file equals this class's read of the bytes in hand. */
        private boolean launcherReadAgrees(Path file, byte[] bytes) {
            try {
                return ServeLauncher.readLauncherRows(file, NULL_OUT).equals(Doc.parse(bytes, Reader.LAUNCHER_ROWS).values);
            } catch (IOException | RuntimeException unreadable) {
                QUIET_FAILURES.incrementAndGet(); // the caller's "reads differently" line is the record
                return false;
            }
        }

        /** The template the LAST repair applied, or null (no receipt: this folder's first repair, or none shipped then). */
        private byte[] appliedTemplateOrNull(String templateRel, long cap) {
            if (old == null) {
                return null;
            }
            String rel = APPLIED_DIR + "/" + templateRel;
            Path p = safe(rel);
            if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            try {
                return readCapped(p, cap);
            } catch (IOException | RuntimeException unreadable) {
                say(shown(rel) + " cannot be read (" + simple(unreadable) + ") - only settings new to this version are added");
                return null;
            }
        }

        /**
         * Writes {@code bytes} over {@code target}: a temp file under {@code run/update/}, re-read by the launcher's
         * own reader when {@code verify} is {@link Reader#LAUNCHER_ROWS}, then ATOMIC_MOVE + REPLACE_EXISTING (a
         * reader sees the old file or the new one). False = left as it was, with one line.
         */
        boolean replaceAtomic(String rel, Path target, byte[] bytes, Reader verify) {
            String tmpRel = UPDATE_DIR + "/" + target.getFileName() + TEMP_SUFFIX;
            Path tmp = safe(tmpRel);
            if (tmp == null) {
                say(linked(tmpRel, shown(rel) + " is left as it was"));
                retry = true;
                return false;
            }
            try {
                Files.createDirectories(tmp.getParent());
                Files.deleteIfExists(tmp);
                Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                if (verify == Reader.LAUNCHER_ROWS && !launcherReadAgrees(tmp, bytes)) {
                    deleteOwn(tmp);
                    say(shown(rel) + " was not merged: the launcher would read the merged file differently - left as it is");
                    return false;
                }
                if (PackageFolderWriter.isLink(target)) {
                    deleteOwn(tmp);
                    say(shown(rel) + " is a link - left as it is, never written through");
                    return false;
                }
                Files.createDirectories(target.getParent());
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException | RuntimeException unwritable) {
                deleteOwn(tmp);
                say(shown(rel) + " could not be written (" + simple(unwritable) + ") - left as it was; the next start"
                        + " tries again");
                retry = true;
                return false;
            }
        }

        // ---- (f) the match re-key + the roster belt ----

        /** The folder's standing default sidecar lines (the new kit's content half + {@code file=} rows); null = no
         *  re-key: silent when this layout lists none, else a verdict tail naming that file. */
        private List<String> kitLinesOrNull() {
            if (!now.sha256ByPath.containsKey(STANDING_DEFAULT_SIDECAR)) {
                return null;
            }
            byte[] b = programBytes(STANDING_DEFAULT_SIDECAR, MAX_SIDECAR_BYTES);
            List<String> lines = b == null ? null : sidecarLines(b);
            if (lines == null || contentHalfOrNull(lines) == null) {
                tail(shown(STANDING_DEFAULT_SIDECAR) + " cannot be read, so your match files were not moved to this"
                        + " version's kit - run " + updateScript() + " again with the zip you downloaded");
                retry = true;
                return null;
            }
            return lines;
        }

        void rekeyAll(List<String> kit) {
            Path dir = safe(OpsMatchApply.MATCH_DIR);
            if (dir == null) {
                if (!matchLinkSaid) {
                    matchLinkSaid = true;
                    say(linked(OpsMatchApply.MATCH_DIR, "its match files were not moved to this version's kit"));
                }
                return;
            }
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            List<Path> sidecars = new ArrayList<>();
            String suffix = MatchShapes.TXT + MatchShapes.SIDECAR_SUFFIX;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(suffix) && !name.contains(MatchShapes.TOKENS_MARK)
                            && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        sidecars.add(p);
                        if (sidecars.size() >= OpsMatchApply.MAX_OFFERED) {
                            break;
                        }
                    }
                }
            } catch (IOException | RuntimeException unlistable) {
                say(shown(OpsMatchApply.MATCH_DIR) + " cannot be listed (" + simple(unlistable) + ") - its match files"
                        + " were not moved to this version's kit; the next start tries again");
                retry = true;
                return;
            }
            sidecars.sort(null);
            for (Path p : sidecars) {
                String rel = MATCH_PREFIX + p.getFileName();
                String txtRel = rel.substring(0, rel.length() - MatchShapes.SIDECAR_SUFFIX.length());
                String cannot = shown(rel) + " could not be moved to this version's kit - delete " + shown(txtRel)
                        + " and its .fingerprint and start again (a shipped shape is copied back; a shape you made:"
                        + " make it again with New match shape)";
                byte[] mine;
                try {
                    mine = readCapped(p, MAX_SIDECAR_BYTES);
                } catch (IOException | RuntimeException unreadable) {
                    log.add(shown(rel) + " cannot be read (" + simple(unreadable) + ")");
                    tail(cannot);
                    continue;
                }
                Rekey r = rekeyOne(mine, kit);
                if (r.outcome() == RekeyOutcome.CANNOT) {
                    tail(cannot);
                } else if (r.outcome() == RekeyOutcome.REKEYED && replaceAtomic(rel, p, r.bytes(), null)) {
                    sayFile("re-keyed " + shown(rel) + " to this version's kit");
                }
            }
        }

        /** Report only: an offered match file fielding a mod variant this version's kit does not ship (a joiner would
         *  be turned away at that ship). The id printed is the package's own variant name, never host text. */
        void rosterBelt(List<String> kit) {
            Set<String> modIds = new TreeSet<>();
            String head = VARIANTS_PREFIX.toLowerCase(Locale.ROOT);
            for (String rel : now.sha256ByPath.keySet()) {
                String f = rel.toLowerCase(Locale.ROOT);
                if (f.startsWith(head) && f.endsWith(VARIANT_EXT)) {
                    String leaf = rel.substring(rel.lastIndexOf('/') + 1);
                    String id = leaf.substring(0, leaf.length() - VARIANT_EXT.length());
                    if (!id.isEmpty()) {
                        modIds.add(id);
                    }
                }
            }
            Path dir = safe(OpsMatchApply.MATCH_DIR);
            if (modIds.isEmpty() || dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Set<String> shipped = MatchShapes.shippedVariantIds(kit);
            for (Path p : OpsMatchApply.offeredDescriptors(OpsRoots.packaged(root))) {
                MatchDescriptor d;
                try {
                    d = MatchDescriptorCodec.decode(new String(readCapped(p, MatchShapes.MAX_DESCRIPTOR_BYTES),
                            StandardCharsets.UTF_8).lines().toList());
                } catch (IOException | RuntimeException undecodable) {
                    QUIET_FAILURES.incrementAndGet(); // the console's own reads name a damaged match file
                    continue;
                }
                Set<String> named = new TreeSet<>();
                for (MatchDescriptor.RosterRow row : d.roster()) {
                    String id = row.variantId();
                    if (id != null && modIds.contains(id) && !shipped.contains(id)) {
                        named.add(id);
                    }
                }
                for (String id : named) {
                    sayFile(shown(MATCH_PREFIX + p.getFileName()) + " fields " + PackageManifest.mask(id)
                            + ", which this version's kit does not carry");
                }
            }
        }

        // ---- (g) the receipts, the log, the verdict ----

        void receipts() {
            List<String> templates = List.of(PackageFolderWriter.PROPS_TEMPLATE, LAUNCHER_TEMPLATE);
            Map<String, byte[]> bytes = new LinkedHashMap<>();
            for (String t : templates) {
                long cap = t.equals(LAUNCHER_TEMPLATE) ? ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES : ServeLauncher.MAX_CONFIG_BYTES;
                bytes.put(t, programBytes(t, cap));
            }
            if (retry) {
                say(shown(APPLIED_DIR) + " is not updated, so the next start repeats this repair");
                return;
            }
            for (String t : templates) {
                String rel = APPLIED_DIR + "/" + t;
                Path p = safe(rel);
                if (p == null) {
                    say(linked(rel, "the next start repeats this repair"));
                    return;
                }
                byte[] b = bytes.get(t);
                if (b != null) {
                    if (!replaceAtomic(rel, p, b, null)) {
                        return;
                    }
                } else {
                    deleteOwn(p); // this version ships no such template: the next repair reads none
                }
            }
            Path manifest = safe(APPLIED_MANIFEST);
            if (manifest == null) {
                say(linked(APPLIED_MANIFEST, "the next start repeats this repair"));
                return;
            }
            replaceAtomic(APPLIED_MANIFEST, manifest, nowBytes, null);
        }

        private String verdict(boolean settingsExisted) {
            StringBuilder v = new StringBuilder();
            boolean kitChanged;
            if (old == null && !settingsExisted) {
                v.append("installed ").append(newStamp).append(": settings created from the templates");
                kitChanged = false;
            } else if (old == null) {
                v.append("updated to ").append(newStamp).append(" (from an older package); ").append(KEPT)
                        .append(" (").append(appended).append(" new settings rows added)");
                kitChanged = true;
            } else {
                Diff d = diff(old, now, foldCase);
                v.append("updated to ").append(newStamp).append(" (from ").append(stampOf(old)).append("); ")
                        .append(d.replaced().size()).append(" files replaced, ").append(d.added().size())
                        .append(" added, ").append(d.removed().size()).append(" removed; ").append(KEPT)
                        .append(" (").append(appended).append(" new settings rows added)");
                kitChanged = kitChanged(old, now);
            }
            String kit = now.packKit();
            if (kitChanged && now.kitJarMd5() != null && plainName(kit)) {
                v.append("; players need the new kit: ").append(shown(PackageFolderWriter.KITS_PREFIX + kit));
            }
            if (!tails.isEmpty()) {
                v.append("; ").append(tails.get(0));
                if (tails.size() > 1) {
                    v.append(" (and ").append(tails.size() - 1).append(" more: see ").append(shown(LOG_REL)).append(')');
                }
            }
            return v.toString();
        }

        /**
         * Whether the players' installed kits stop matching this package, judged as the join door judges an installed
         * kit, so the verdict asks for the new kit exactly when the door would refuse the old one: the kit jar md5
         * differs, OR both manifests carry a {@link PackageManifest#KIT_FINGERPRINT} and those composites differ once
         * their {@code ;match=} term is stripped ({@link KitPacker#withoutMatch}: the jar AND data halves; a kit packed
         * for another match shape is still the same kit). A jar-only compare missed a data-only kit change (every
         * variant file new, the jar unchanged): the host was never told while the door turned every old kit away. A
         * manifest older than the fingerprint row, or one whose row is blank (the door reads blank as absent too),
         * leaves the jar md5 as the whole compare. Case-sensitive, as the door (both sides are minted lowercase).
         *
         */
        private static boolean kitChanged(PackageManifest old, PackageManifest now) {
            if (!Objects.equals(old.kitJarMd5(), now.kitJarMd5())) {
                return true;
            }
            String was = old.kitFingerprint();
            String is = now.kitFingerprint();
            if (was == null || was.isBlank() || is == null || is.isBlank()) {
                return false;
            }
            return !KitPacker.withoutMatch(was).equals(KitPacker.withoutMatch(is));
        }

        /** Appends the log (a header + this start's lines + the verdict), then prints the verdict LAST. */
        private void finish(String verdictOrNull) {
            if (suppressed > 0) {
                say(suppressed + " more file lines of this start are in " + shown(LOG_REL));
            }
            if (verdictOrNull != null) {
                log.add(verdictOrNull);
            }
            if (!log.isEmpty()) {
                appendLog();
            }
            if (verdictOrNull != null) {
                said.add(verdictOrNull);
                if (out != null) {
                    out.println(verdictOrNull);
                }
            }
        }

        private void appendLog() {
            Path file = safe(LOG_REL);
            if (file == null) {
                say(linked(LOG_REL, "not written"));
                return;
            }
            String eol = System.lineSeparator();
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)).append(" repair ").append(stampOf(old))
                    .append(" -> ").append(newStamp).append(" (git ").append(gitOf(old)).append(" -> ")
                    .append(gitOf(now)).append(") by ").append(platform()).append(eol);
            for (String line : log) {
                sb.append(line).append(eol);
            }
            try {
                Files.createDirectories(file.getParent());
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > MAX_LOG_BYTES) {
                    Path previous = safe(LOG_PREVIOUS_REL);
                    if (previous != null) {
                        Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException unwritable) {
                say(shown(LOG_REL) + " could not be written (" + simple(unwritable) + ")");
            }
        }
    }
}
