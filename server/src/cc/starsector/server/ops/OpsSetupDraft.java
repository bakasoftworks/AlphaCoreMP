package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The web console's UNSAVED Setup, kept across page loads and console restarts (the owner's
 * finding on the shipped package: every typed field was lost when the window closed before Start).
 *
 * <p>WHY a draft file and not the real rows: a live serve re-reads {@code cc-server.properties} as it runs, so
 * a half-typed value must never land there before the host presses Start (Start keeps its validation and is
 * still the ONLY writer of the real rows). WHY one server-side file and not the page's own storage: the
 * page may be opened from another browser or computer, and one draft keeps every viewer on the same values.
 *
 * <p>File = {@link #fileFor}: {@value #FILE_NAME} beside {@link OpsRoots#windowMemory()} (a package: {@code <pkg>/run/}).
 * Rows {@code <control id>=<value>} keyed by the parity ids the page's controls carry ({@link OpsParity}),
 * only the CLOSED list {@link #DRAFT_IDS} (the host-config facts Start writes), plus {@code base=<millis>}
 * (the newest mtime of the two files Start writes when the draft was written - a draft older than a hand
 * edit of either file is DROPPED at load, {@link #load}) and {@code updated=<ISO instant>} (the restore
 * note's time). Values are one line each, at most {@link #MAX_VALUE_CHARS} chars; the file at most
 * {@link #MAX_FILE_BYTES} B and {@link #MAX_ROWS} rows - anything over is refused by the writer
 * ({@link #put}, a {@link Refused} with its bounded token) and ignored by the reader. NEVER a password or the
 * join brick: an id outside {@link #DRAFT_IDS} is refused by the route before this class sees it, refused
 * again by {@link #put} and skipped by {@link #parse}; {@link #NEVER_DRAFTED} is disjoint from the list.
 *
 * <p>Pure over (bytes, rows): {@link #parse} / {@link #render} / {@link #put} touch no file; {@link #load},
 * {@link #write} and {@link #clear} are the three file touches (a bounded read, the atomic temp-sibling write
 * of {@link #writeAtomic}, a delete). Thread: any (the console makes every file touch - the
 * route, the config read, the rebase - under its one start lock).
 */
final class OpsSetupDraft {

    /** The file name beside {@link OpsRoots#windowMemory()}. */
    static final String FILE_NAME = "setup-draft.properties";
    static final int MAX_FILE_BYTES = 16384;
    static final int MAX_ROWS = 64;
    static final int MAX_VALUE_CHARS = 512;
    /**
     * The ONLY ids a draft holds - the page's prefilled Setup controls, the
     * match shape pick, the advertise address and the second game folder (each a host-config fact Start
     * writes). WHY a closed list and not a denylist: a denylist admitted the join brick's output, whose value is
     * the whole brick INCLUDING the match password; a closed list keeps every future page id OUT until it is
     * decided to be a draftable host fact. OUT by design: both passwords ({@link #NEVER_DRAFTED}), the brick,
     * the map fields (each has its own Apply), the shape's player count and its Apply, voice, the verbs and the
     * uplink. Pinned by rows {@code draft-allowlist} and {@code draft-ids-parity}.
     * + {@code setup.match.pvp}, the PvP mode select - a Start-time fact
     * like the shape pick (Start switches the picked file's mode; no Apply, no config row), so it is drafted.
     */
    static final Set<String> DRAFT_IDS = Set.of("setup.bindHost", "setup.bindPort", "setup.advertise",
            "setup.gameRoot", "setup.gameRootSecond", "setup.tickRate", "setup.record", "setup.net.seatsPerSource",
            "setup.officer.allSkills", "setup.replay.rngPins", "setup.shape", "setup.fortress.dp",
            "setup.fortress.terrain", "setup.fortress.terrainLevel", "setup.fortress.asteroids", "setup.wave.scale",
            "setup.wave.rosterDp", "setup.wave.rosterMultiplier", "setup.wave.category", "setup.joiner.default",
            "setup.joiner.endless", "setup.match.pvp");
    /** The password controls: never drafted (a never-started password is retyped; the page never fills one).
     *  Disjoint from {@link #DRAFT_IDS} (row {@code draft-ids-parity}). */
    static final Set<String> NEVER_DRAFTED = Set.of("setup.password", "setup.adminPassword");
    static final String ROW_BASE = "base";
    static final String ROW_UPDATED = "updated";
    /** The route's stdout token when a row is refused; never the value. */
    static final String REFUSED = "refused";
    /** {@link Refused} sentences: host-facing, fixed, never naming the id, the value or a path. */
    static final String REFUSED_ID = "That field is not kept in the unsaved Setup.";
    static final String REFUSED_VALUE = "An unsaved Setup value must be one line of at most "
            + MAX_VALUE_CHARS + " characters.";
    static final String REFUSED_ROWS = "The unsaved Setup is full (" + MAX_ROWS + " fields).";
    static final String REFUSED_SIZE = "The unsaved Setup is over its " + MAX_FILE_BYTES + " byte cap.";

    /**
     * {@link #put}'s refusal - {@link #token} is a bounded word the route
     * prints and maps without reading the message ({@code id} / {@code value} / {@code rows} / {@code size});
     * the message is one of the fixed {@code REFUSED_*} sentences, never the id or the value.
     */
    static final class Refused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String token;

        Refused(String token, String sentence) {
            super(sentence);
            this.token = token;
        }

        String token() {
            return token;
        }
    }

    /** One draft: {@code rows} (insertion-ordered, ids to values, never a password), {@code base} = the config
     *  mtime the draft was based on (0 = unknown), {@code updatedOrNull} = when it was last written. */
    record Draft(Map<String, String> rows, long base, Instant updatedOrNull) {
        static final Draft EMPTY = new Draft(Map.of(), 0L, null);

        boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    private OpsSetupDraft() {
    }

    /** {@value #FILE_NAME} beside the roots' window-memory file. */
    static Path fileFor(OpsRoots roots) {
        return roots.windowMemory().resolveSibling(FILE_NAME);
    }

    /**
     * The newest mtime (millis) of the files Start writes - {@code cc-server.properties} and
     * {@code launcher.properties} - 0 when neither exists or cannot be stat'ed. The draft's {@code base} row.
     */
    static long configBase(Path propsFile, Path launcherFileOrNull) {
        long newest = 0L;
        for (Path p : new Path[] {propsFile, launcherFileOrNull}) {
            try {
                if (p != null && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
                }
            } catch (IOException | RuntimeException unreadable) {
                // a file that cannot be stat'ed counts as absent - the draft then rides on the other
            }
        }
        return newest;
    }

    /** A lawful id: one of {@link #DRAFT_IDS} (so never a password, the brick, {@code base} or {@code updated}).
     *  Read by {@link #put} and {@link #parse}, so a hand-planted row outside the list is ignored at load. */
    static boolean lawfulId(String id) {
        return id != null && DRAFT_IDS.contains(id);
    }

    /** A lawful value: at most {@link #MAX_VALUE_CHARS} chars, no CR / LF / NUL (one row = one line). */
    static boolean lawfulValue(String value) {
        if (value == null || value.length() > MAX_VALUE_CHARS) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code draft} with {@code id} set to {@code value} (an empty value KEEPS the row: an emptied field is a
     * draft fact too), {@code base} / {@code updated} replaced. Throws {@link Refused}, in this order: token
     * {@code id} (not {@link #lawfulId}), {@code value} (not {@link #lawfulValue}), {@code rows} (a NEW row past
     * {@link #MAX_ROWS}; an existing id stays replaceable), {@code size} (the rendered file past
     * {@link #MAX_FILE_BYTES} B) - the route prints {@value #REFUSED}{@code :<token>}.
     *
     * <p>The byte cap is enforced HERE on write. WHY: {@link #parse} returns
     * EMPTY for a body over the cap, so an uncapped write made the WHOLE draft vanish silently at the next load
     * (eleven lawful rows of 512 three-byte characters already pass it). Row {@code draft-caps}.
     */
    static Draft put(Draft draft, String id, String value, long base, Instant updated) {
        if (!lawfulId(id)) {
            throw new Refused("id", REFUSED_ID);
        }
        if (!lawfulValue(value)) {
            throw new Refused("value", REFUSED_VALUE);
        }
        Map<String, String> rows = new LinkedHashMap<>(draft.rows());
        if (!rows.containsKey(id) && rows.size() >= MAX_ROWS) {
            throw new Refused("rows", REFUSED_ROWS);
        }
        rows.put(id, value);
        Draft next = new Draft(Collections.unmodifiableMap(rows), base, updated);
        if (render(next).getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new Refused("size", REFUSED_SIZE);
        }
        return next;
    }

    /** The file body: a comment line, {@code base=}, {@code updated=}, then every row in order, LF-ended. */
    static String render(Draft draft) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("# unsaved console Setup - rewritten on each change, cleared by Start\n");
        sb.append(ROW_BASE).append('=').append(draft.base()).append('\n');
        sb.append(ROW_UPDATED).append('=').append(draft.updatedOrNull() == null ? "" : draft.updatedOrNull())
                .append('\n');
        for (Map.Entry<String, String> e : draft.rows().entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /**
     * The bytes as a draft: rows split on LF (a trailing CR dropped), {@code #} lines skipped, the first
     * {@code =} splits id from value (the value verbatim after it); an unlawful id or value, a row past
     * {@link #MAX_ROWS} or a body over {@link #MAX_FILE_BYTES} B = that row (or the whole draft) IGNORED, never
     * an exception. {@code base} not a long = 0; {@code updated} not an instant = null.
     */
    static Draft parse(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_FILE_BYTES) {
            return Draft.EMPTY;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> rows = new LinkedHashMap<>();
        long base = 0L;
        Instant updated = null;
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.charAt(0) == '#' || eq <= 0) {
                continue;
            }
            String id = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (ROW_BASE.equals(id)) {
                try {
                    base = Long.parseLong(value.trim());
                } catch (NumberFormatException notALong) {
                    base = 0L;
                }
            } else if (ROW_UPDATED.equals(id)) {
                try {
                    updated = value.trim().isEmpty() ? null : Instant.parse(value.trim());
                } catch (RuntimeException notAnInstant) {
                    updated = null;
                }
            } else if (lawfulId(id) && lawfulValue(value) && rows.size() < MAX_ROWS) {
                rows.put(id, value);
            }
        }
        return new Draft(Collections.unmodifiableMap(rows), base, updated);
    }

    /**
     * The draft on disk, or {@link Draft#EMPTY}: no file / not a regular file / over the cap / unreadable =
     * EMPTY; a parsed draft whose {@code base} is older than {@code currentBase} (a hand edit of the config
     * since) = EMPTY, and the stale file is deleted (best effort). Never throws.
     */
    static Draft load(Path file, long currentBase) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) {
                return Draft.EMPTY;
            }
            Draft d = parse(Files.readAllBytes(file));
            if (d.isEmpty()) {
                return Draft.EMPTY;
            }
            if (currentBase > 0L && d.base() > 0L && d.base() < currentBase) {
                System.out.println("cc-console draft=dropped_stale");
                clear(file);
                return Draft.EMPTY;
            }
            return d;
        } catch (IOException | RuntimeException unreadable) {
            System.out.println("cc-console draft=unreadable (" + unreadable.getClass().getSimpleName() + ")");
            return Draft.EMPTY;
        }
    }

    /** {@link #render} through the atomic temp-sibling write; an empty draft deletes the file instead. */
    static void write(Path file, Draft draft) throws IOException {
        if (draft.isEmpty()) {
            clear(file);
            return;
        }
        writeAtomic(file, render(draft));
    }

    /** Temp sibling + atomic move (plain replace when the filesystem refuses ATOMIC_MOVE). WHY atomic: the
     * console re-reads the draft on every config read, so a half-written file must never be what it reads.
     * */
    static void writeAtomic(Path file, String text) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes the file (and a leftover temp sibling); a missing file is fine; never throws. */
    static void clear(Path file) {
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
        } catch (IOException | RuntimeException notDeleted) {
            System.out.println("cc-console draft=clear_failed (" + notDeleted.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The draft as the {@code "draft"} JSON object member body + {@code "draftUpdated"}: {@code
     * {"<id>":"<value>",...}} with every id and value JSON-escaped, and the ISO instant or {@code ""}.
     * Appended by {@code ServerOpsCore.configJson}; the page lays it over the filled form.
     */
    static String jsonMembers(Draft draft) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("\"draft\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : draft.rows().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(ServerOpsCore.jsonEscape(e.getKey())).append("\":\"")
                    .append(ServerOpsCore.jsonEscape(e.getValue())).append('"');
        }
        sb.append("},\"draftUpdated\":\"")
                .append(draft.updatedOrNull() == null ? "" : draft.updatedOrNull().toString()).append('"');
        return sb.toString();
    }

    /** A bounded token for a stdout row: {@code n=<rows>}. */
    static String summary(Draft draft) {
        return String.format(Locale.ROOT, "n=%d", draft.rows().size());
    }
}
