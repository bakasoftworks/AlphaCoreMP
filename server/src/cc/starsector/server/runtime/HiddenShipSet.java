package cc.starsector.server.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The host's hidden-ships list — the model, the
 * {@code cc-server.hidden-ships} codec and its boot read. A hidden id is a ship that comes with
 * the server which the host took off {@code /ship} for every player; the caller maps each lawful id
 * onto the shipped catalogue (an id that names no shipped ship is kept here, with no effect), and
 * persistence is the off-frame {@link HiddenShipsPersistSink} (never a file op on the
 * frame). Built on the {@link BanSet} idiom, with no kind / term columns: a line is one ship id.
 *
 * <p>File grammar: one id per line, surrounding whitespace ignored; a line starting with {@code #}
 * is a comment; blank lines are skipped; a leading U+FEFF (an editor's BOM) is stripped. Any other
 * line that is not one lawful id ({@link #idLawful}) is MALFORMED: kept verbatim (a rewrite never
 * loses it) and named once by line number, never by content (the file is host text = data).
 *
 * <p>Laws: ids compare by the ASCII-lower {@link #fold} (the same fold {@link PickableVariants}
 * keys its listing on) and keep the spelling they were stored with; capped at
 * {@value #MAX_ENTRIES} ids, refused at the cap ({@link AddResult#CAP}), never evicted; a parsed
 * id past the cap is OVER-CAP — not in effect, but kept verbatim like a malformed line, so a host's
 * hand-edited file never shrinks through a rewrite. Thread-safe on its own monitor and never calls
 * out (no lock, no file, no log inside a mutation).
 */
public final class HiddenShipSet {

    /** The id cap: {@link #add} refuses past it; {@link #parse} keeps extras verbatim, not in effect. */
    public static final int MAX_ENTRIES = 128;
    /** A file larger than this fails {@link #load} (128 ids fit in under 9 KiB). */
    public static final int MAX_FILE_BYTES = 64 * 1024;
    /** Lines kept verbatim (comments, malformed, over-cap) across rewrites; extras dropped + one row. */
    public static final int MAX_PRESERVED_LINES = 1_024;
    /** The shipped-stem length bound ({@link PickableVariants#ID_MAX_CHARS}, the same 64). */
    public static final int ID_MAX_CHARS = 64;
    /** U+FEFF, stripped from the first line. */
    static final char BOM = (char) 0xFEFF;
    /** Per-line parse rows printed before the rest collapse into one count row (a 64 KiB file of
     *  junk would otherwise print thousands of rows at boot). */
    static final int MAX_LINE_ROWS = 16;

    public enum AddResult { ADDED, ALREADY, CAP, UNLAWFUL }

    /** fold → stored spelling; the map's key order IS the fold order {@link #ids} and {@link #render}
     *  publish. Guarded by {@code this}. */
    private final TreeMap<String, String> ids = new TreeMap<>();
    /** Comment + malformed lines, file order, verbatim (BOM-stripped). */
    private final List<String> preserved = new ArrayList<>();
    /** Lawful ids parsed past {@link #MAX_ENTRIES}, file order, verbatim. */
    private final List<String> overCapLines = new ArrayList<>();
    private boolean overCap;
    private boolean dirty;

    public HiddenShipSet() {
    }

    // ---------------------------------------------------------------- the id law

    /**
     * The id law: 1..{@value #ID_MAX_CHARS} chars, every char printable ASCII 0x21..0x7e (no
     * space, no control, no non-ASCII), and NOT starting with {@code #} — such a line reads back as
     * a comment, so storing it would lose the hide at the next boot (no shipped stem and no verb
     * token starts with {@code #}). Null = unlawful.
     */
    public static boolean idLawful(String id) {
        if (id == null || id.isEmpty() || id.length() > ID_MAX_CHARS || id.charAt(0) == '#') {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    /** The compare key: ASCII-only lower case ({@code 'A'..'Z'} only, never a Locale); null → null. */
    public static String fold(String id) {
        return id == null ? null : ChatCommands.asciiLower(id);
    }

    // ---------------------------------------------------------------- queries

    /** True when a fold-equal id is stored (any spelling); null / unlawful → false. */
    public synchronized boolean contains(String id) {
        return id != null && ids.containsKey(fold(id));
    }

    /** The stored ids in their stored spelling, sorted by {@link #fold}; an immutable copy. */
    public synchronized List<String> ids() {
        return List.copyOf(ids.values());
    }

    public synchronized int size() {
        return ids.size();
    }

    /** True at {@value #MAX_ENTRIES} ids (the next new {@link #add} answers CAP). */
    public synchronized boolean isFull() {
        return ids.size() >= MAX_ENTRIES;
    }

    /** True when the parse met lawful ids past the cap (kept verbatim, NOT in effect). Fixed at
     *  parse: removing an id never promotes an over-cap line in memory — the next boot reads them in
     *  file order, and {@link #render} writes them after the in-effect ids. */
    public synchronized boolean overCap() {
        return overCap;
    }

    /** Lines kept verbatim through a rewrite: comments + malformed + over-cap. */
    public synchronized int preservedCount() {
        return preserved.size() + overCapLines.size();
    }

    /** True when an {@link #add} answered ADDED or a {@link #remove} answered true since the last
     *  {@link #render}; a refused or no-op call never marks, and a parsed set starts clean. */
    public synchronized boolean dirty() {
        return dirty;
    }

    // ---------------------------------------------------------------- mutation

    /** Add one id: unlawful → UNLAWFUL; a fold-equal id stored → ALREADY (its stored spelling
     *  kept); at the cap → CAP (nothing evicted); else ADDED + dirty. */
    public synchronized AddResult add(String id) {
        if (!idLawful(id)) {
            return AddResult.UNLAWFUL;
        }
        String key = fold(id);
        if (ids.containsKey(key)) {
            return AddResult.ALREADY;
        }
        if (ids.size() >= MAX_ENTRIES) {
            return AddResult.CAP;
        }
        ids.put(key, id);
        dirty = true;
        return AddResult.ADDED;
    }

    /** Remove the fold-equal id; false when absent (a no-op stays clean). Over-cap and preserved
     *  lines are never touched. */
    public synchronized boolean remove(String id) {
        boolean removed = id != null && ids.remove(fold(id)) != null;
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    // ---------------------------------------------------------------- codec

    /**
     * The file lines: the preserved comment / malformed lines (file order), then the ids in stored
     * spelling sorted by fold, then the over-cap lines (file order). The over-cap lines go LAST so
     * the next boot reads the in-effect ids first: while the set is full (always true after a parse
     * that met over-cap lines, until a remove) {@code parse(render())} holds the same ids and renders
     * the same lines; after a remove, the next boot takes the first over-cap line into effect (the
     * file-order law). Clears {@link #dirty} (the render IS the save's snapshot).
     */
    public synchronized List<String> render() {
        List<String> out = new ArrayList<>(preserved.size() + ids.size() + overCapLines.size());
        out.addAll(preserved);
        out.addAll(ids.values());
        out.addAll(overCapLines);
        dirty = false;
        return List.copyOf(out);
    }

    /**
     * The pure codec (the file grammar in the class javadoc). Never throws on content. A
     * malformed line is kept verbatim and named by line number; a fold-equal repeat of a stored id
     * is dropped (one row; the first spelling wins); a lawful id past {@value #MAX_ENTRIES} is kept
     * verbatim as over-cap (one summary row); verbatim lines past {@value #MAX_PRESERVED_LINES} are
     * dropped (one summary row). Per-line rows stop at {@value #MAX_LINE_ROWS}, then one count row.
     * The parsed set is clean.
     */
    public static HiddenShipSet parse(List<String> lines, Consumer<String> log) {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(log, "log");
        HiddenShipSet set = new HiddenShipSet();
        int lineRows = 0;
        int lineRowsSuppressed = 0;
        int overCapCount = 0;
        int verbatimDropped = 0;
        synchronized (set) {
            for (int n = 0; n < lines.size(); n++) {
                String raw = lines.get(n);
                if (raw == null) {
                    continue;
                }
                if (n == 0 && !raw.isEmpty() && raw.charAt(0) == BOM) {
                    raw = raw.substring(1);
                }
                String t = raw.strip();
                if (t.isEmpty()) {
                    continue;
                }
                boolean comment = t.charAt(0) == '#';
                boolean lawful = !comment && idLawful(t);
                if (lawful && set.ids.containsKey(fold(t))) {
                    if (lineRows < MAX_LINE_ROWS) {
                        lineRows++;
                        log.accept("hidden_ships_file_line_duplicate line=" + (n + 1)
                                + " (the same ship as an earlier line; dropped on the next save)");
                    } else {
                        lineRowsSuppressed++;
                    }
                    continue;
                }
                if (lawful && set.ids.size() < MAX_ENTRIES) {
                    set.ids.put(fold(t), t);
                    continue;
                }
                if (lawful) {
                    overCapCount++;
                    set.overCap = true;
                } else if (!comment) {
                    if (lineRows < MAX_LINE_ROWS) {
                        lineRows++;
                        log.accept("hidden_ships_file_line_skipped line=" + (n + 1) + " reason=\"not one"
                                + " ship id: 1-" + ID_MAX_CHARS + " printable ASCII characters, no spaces\""
                                + " (kept as written on the next save)");
                    } else {
                        lineRowsSuppressed++;
                    }
                }
                if (set.preserved.size() + set.overCapLines.size() >= MAX_PRESERVED_LINES) {
                    verbatimDropped++;
                } else if (lawful) {
                    set.overCapLines.add(raw);
                } else {
                    set.preserved.add(raw);
                }
            }
        }
        if (lineRowsSuppressed > 0) {
            log.accept("hidden_ships_file_line_rows_suppressed count=" + lineRowsSuppressed
                    + " (duplicate / malformed lines past the first " + MAX_LINE_ROWS + ")");
        }
        if (overCapCount > 0) {
            log.accept("hidden_ships_file_over_cap cap=" + MAX_ENTRIES + " not_in_effect=" + overCapCount
                    + " (kept as written on the next save; only the first " + MAX_ENTRIES
                    + " ships are hidden)");
        }
        if (verbatimDropped > 0) {
            log.accept("hidden_ships_file_preserved_over_cap cap=" + MAX_PRESERVED_LINES + " dropped="
                    + verbatimDropped);
        }
        return set;
    }

    /**
     * The boot read: ABSENT ({@link Files#notExists}) ⇒ an empty set + one row
     * {@code hidden_ships_file path=… ids=0 state=absent}. DAMAGED — existence undeterminable,
     * unreadable, over {@value #MAX_FILE_BYTES} bytes, or not valid UTF-8 — ⇒
     * {@link IllegalArgumentException} naming the path (and the line, for bad bytes); the CALLER
     * then runs memory-only and never overwrites the file. A hidden list is not a security list, so
     * this read never refuses a boot itself. A loaded file prints
     * {@code hidden_ships_file path=… ids=N state=loaded preserved=P over_cap=0|1}.
     */
    public static HiddenShipSet load(Path file, Consumer<String> log) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(log, "log");
        if (Files.notExists(file)) {
            log.accept("hidden_ships_file path=" + file + " ids=0 state=absent");
            return new HiddenShipSet();
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("hidden ships file " + file + " cannot be checked (no"
                    + " access to its folder?) - fix the folder access or remove the file");
        }
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new IllegalArgumentException(tooBig(file));
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("hidden ships file " + file + " exists but is unreadable"
                    + " - fix or remove it: " + e, e);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(tooBig(file)); // it grew between the size check and the read
        }
        String text = decodeOrThrow(bytes, file);
        HiddenShipSet set = parse(text.lines().toList(), log);
        log.accept("hidden_ships_file path=" + file + " ids=" + set.size() + " state=loaded preserved="
                + set.preservedCount() + " over_cap=" + (set.overCap() ? 1 : 0));
        return set;
    }

    private static String tooBig(Path file) {
        return "hidden ships file " + file + " exceeds " + MAX_FILE_BYTES + " bytes - fix or remove it";
    }

    private static String decodeOrThrow(byte[] bytes, Path file) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length + 1);
        CoderResult r = decoder.decode(in, out, true);
        if (!r.isError()) {
            r = decoder.flush(out);
        }
        if (r.isError()) {
            int line = 1;
            for (int i = 0; i < in.position() && i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    line++;
                }
            }
            throw new IllegalArgumentException("hidden ships file " + file + " line " + line
                    + " is not valid UTF-8 - fix or remove the file");
        }
        out.flip();
        return out.toString();
    }
}
