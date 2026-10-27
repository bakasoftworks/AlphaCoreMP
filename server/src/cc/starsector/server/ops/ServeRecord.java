package cc.starsector.server.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A packaged server's {@code record=off|diag|full} + {@code record.maxMiB} rows in
 * {@code launcher.properties}, turned into the SAME JVM argument lines an owner-mode serve gets at launch
 * (the serve-template mint's {@code $OwnerBoxRules}). WHY
 * launcher rows and not a template edit: {@code serve.argv} is manifest-listed (an edited line refuses
 * {@code manifest_mismatch}), and the config {@code net.sinkPath} row is neither flight-scoped nor
 * capped, so a package host had no bounded way to record a flight for a bug report.
 *
 *
 * <p>Pure: no I/O, no clock, no state. The caller's contract: read the rows with {@link #parse}
 * and {@link #capMiB}, then run {@link #withLines} on the template lines BEFORE
 * {@link ArgvTemplate#substitute}, so the {@code ${RUN}} in every line resolves through the
 * template's CLOSED token list to that launch's flight dir. Every refusal is an
 * {@link IllegalArgumentException} whose message is the host-facing sentence (the four
 * {@link #texts}); a host-typed value is echoed bounded (printable ASCII, at most
 * {@value #MAX_ECHO_CHARS} chars), a template line's value never.
 *
 *
 */
public final class ServeRecord {

    /** The {@code record=} row's three values; {@link #row()} is the row spelling. */
    public enum Mode {
        /** No line: {@link #withLines} hands the template back untouched (argv byte-identical to a launch with no row). */
        OFF,
        /** The gc + safepoint log, the JFR repository and the serve-block JFR dump: timing evidence, no player bytes. */
        DIAG,
        /** DIAG plus the wire record {@code wire.ccwr} (the seats' intents and declarations: chat and names), capped. */
        FULL;

        /** The row spelling {@code off} / {@code diag} / {@code full} (what {@link #parse} accepts). */
        public String row() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * {@code record.maxMiB} bounds, all positive MiB: 1024..262144 (262144 MiB = 256 GiB, a typo guard), 4096 when the
     * row is blank or absent (about 40 minutes of one seat). Never 0: {@code WireRecordSink.openBounded} reads a 0 cap
     * as UNBOUNDED, so a FULL record always carries a cap from this range. The floor is the sink's own:
     * a smaller cap passed this row, then met the serve's sink-cap refusal only
     * AFTER the engine boot + warm-up, once per supervisor life (× 6) — and the {@code record_disk} hint
     * ("lower record.maxMiB") steered hosts there.
     */
    public static final int CAP_MIN_MIB = cc.starsector.server.runtime.WireRecordSink.SINK_CAP_FLOOR_MIB;
    public static final int CAP_MAX_MIB = 262144;
    public static final int CAP_DEFAULT_MIB = 4096;

    /** The disk floor's headroom over the cap, in MiB (the {@link #diskText} sentence's "plus 1024"). */
    public static final int DISK_HEADROOM_MIB = 1024;

    /**
     * The five record lines, byte-equal to the {@code $OwnerBoxRules} outputs (that list's order: sinkPath, sinkMaxMiB,
     * gc, FlightRecorderOptions, jfr.blocks), {@code ${RUN}} left UNSUBSTITUTED.
     */
    public static final String LINE_SINK_PATH = "-Dcc.net.sinkPath=${RUN}/wire.ccwr";
    public static final String SINK_MAX_MIB_PREFIX = "-Dcc.net.sinkMaxMiB=";
    public static final String LINE_GC_LOG = "-Xlog:gc*,safepoint:file=${RUN}/gc.log:time,uptime";
    public static final String LINE_JFR_REPO = "-XX:FlightRecorderOptions=repository=${RUN}/jfr-repo";
    public static final String LINE_JFR_BLOCKS = "-Dcc.jfr.blocks=${RUN}/serve-blocks.jfr";

    /**
     * The five keys a template must not already set, in the record lines' order: {line prefix, the separator that
     * follows it, the name the refusal says}. A line equal to the prefix, or the prefix + separator + anything, sets it;
     * {@code -Dcc.jfr.blocksX=1} (another property) does not.
     */
    private static final String[][] KEYS = {
        {"-Dcc.net.sinkPath", "=", "cc.net.sinkPath"},
        {"-Dcc.net.sinkMaxMiB", "=", "cc.net.sinkMaxMiB"},
        {"-Xlog", ":", "-Xlog"},
        {"-XX:FlightRecorderOptions", "=", "-XX:FlightRecorderOptions"},
        {"-Dcc.jfr.blocks", "=", "cc.jfr.blocks"},
    };

    /**
     * java launcher options whose value is the NEXT argument line, so that line is never the main class (the
     * launcher's own option table [external, from memory of the JDK 17 launcher]; MINT emits {@code -cp} + its
     * classpath and the bare {@code --add-opens} / {@code --add-exports} forms).
     */
    private static final Set<String> SEPARATE_VALUE_OPTIONS = Set.of("-cp", "-classpath", "--class-path", "-p",
            "--module-path", "--upgrade-module-path", "--add-modules", "--enable-native-access", "--limit-modules",
            "--add-exports", "--add-opens", "--add-reads", "--patch-module", "--source");

    /** Launcher options that select the main themselves: the record lines go before them. */
    private static final Set<String> MAIN_SELECTOR_OPTIONS = Set.of("-jar", "-m", "--module");

    /** The longest host-typed value a refusal echoes. */
    static final int MAX_ECHO_CHARS = 40;

    private ServeRecord() {
    }

    /**
     * The {@code record} row: trimmed, then exactly {@code off} / {@code diag} / {@code full} (case-exact, lower).
     * Absent ({@code null}) or blank = {@link Mode#OFF} (the default). Anything else throws the row refusal.
     */
    public static Mode parse(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return Mode.OFF;
        }
        for (Mode m : Mode.values()) {
            if (m.row().equals(v)) {
                return m;
            }
        }
        throw new IllegalArgumentException(rowText(v));
    }

    /**
     * The {@code record.maxMiB} row: trimmed, ASCII digits only (no sign, no unit, at most 9), within
     * {@value #CAP_MIN_MIB}..{@value #CAP_MAX_MIB}; absent or blank = {@value #CAP_DEFAULT_MIB}. Anything else
     * (0 included) throws the cap refusal. Returns a positive MiB count.
     */
    public static int capMiB(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return CAP_DEFAULT_MIB;
        }
        boolean digits = v.length() <= 9;
        for (int i = 0; digits && i < v.length(); i++) {
            char c = v.charAt(i);
            digits = c >= '0' && c <= '9';
        }
        if (!digits) {
            throw new IllegalArgumentException(capText(v));
        }
        int n = Integer.parseInt(v);
        if (n < CAP_MIN_MIB || n > CAP_MAX_MIB) {
            throw new IllegalArgumentException(capText(v));
        }
        return n;
    }

    /**
     * The record lines for {@code mode}, {@code ${RUN}} unsubstituted, in {@code $OwnerBoxRules} order: OFF = none;
     * DIAG = gc, FlightRecorderOptions, jfr.blocks; FULL = sinkPath, {@code sinkMaxMiB=<capMiB>}, then DIAG's three.
     * Only FULL reads {@code capMiB}, and refuses one outside {@value #CAP_MIN_MIB}..{@value #CAP_MAX_MIB} (never a
     * 0 = unbounded sink). Immutable.
     */
    public static List<String> lines(Mode mode, int capMiB) {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.OFF) {
            return List.of();
        }
        if (mode == Mode.DIAG) {
            return List.of(LINE_GC_LOG, LINE_JFR_REPO, LINE_JFR_BLOCKS);
        }
        if (capMiB < CAP_MIN_MIB || capMiB > CAP_MAX_MIB) {
            throw new IllegalArgumentException(capText(Integer.toString(capMiB)));
        }
        return List.of(LINE_SINK_PATH, SINK_MAX_MIB_PREFIX + capMiB, LINE_GC_LOG, LINE_JFR_REPO, LINE_JFR_BLOCKS);
    }

    /** {@link #withLines(List, Mode, int, Object)} naming the package's own template file in its refusal. */
    public static List<String> withLines(List<String> templateLines, Mode mode, int capMiB) {
        return withLines(templateLines, mode, capMiB, ServeLauncher.DEFAULT_TEMPLATE);
    }

    /**
     * The template lines with {@link #lines} inserted immediately before the main class ({@link #mainClassIndex}),
     * after every JVM option; the input list is never modified. OFF returns {@code templateLines} ITSELF (no check,
     * no copy: {@code record=off} = today's argv). DIAG / FULL first refuse a template that already sets any of the
     * five keys before its main class, naming the first such key and never its value (the refusal names
     * {@code template}, the caller's template path). Returns an unmodifiable list.
     */
    public static List<String> withLines(List<String> templateLines, Mode mode, int capMiB, Object template) {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.OFF) {
            return templateLines;
        }
        List<String> add = lines(mode, capMiB);
        int main = mainClassIndex(templateLines);
        for (int i = 0; i < main; i++) {
            String key = keySetBy(templateLines.get(i));
            if (key != null) {
                throw new IllegalArgumentException(templateText(template, key));
            }
        }
        List<String> out = new ArrayList<>(templateLines.size() + add.size());
        out.addAll(templateLines.subList(0, main));
        out.addAll(add);
        out.addAll(templateLines.subList(main, templateLines.size()));
        return Collections.unmodifiableList(out);
    }

    /**
     * The main-class rule (ArgvTemplate itself never locates a main class; MINT matches the server's literal main
     * class name): line 0 is the java executable (the template's {@code ${JAVA}} row) when it does not start with
     * {@code -}; after it, a line starting with {@code -} is a JVM option, a {@link #SEPARATE_VALUE_OPTIONS} one also
     * consumes the next line as its value, and the first remaining line that does not start with {@code -}, or a
     * {@link #MAIN_SELECTOR_OPTIONS} line, is the main. No main = {@code lines.size()} (the record lines append last,
     * as MINT appends its crash-file lines when the argv names no main class).
     */
    static int mainClassIndex(List<String> lines) {
        int i = !lines.isEmpty() && !lines.get(0).trim().startsWith("-") ? 1 : 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (!line.startsWith("-") || MAIN_SELECTOR_OPTIONS.contains(line)) {
                return i;
            }
            i += SEPARATE_VALUE_OPTIONS.contains(line) ? 2 : 1;
        }
        return lines.size();
    }

    /** The refusal name of the record key {@code line} sets, or {@code null} when it sets none. */
    private static String keySetBy(String line) {
        String l = line.trim();
        for (String[] k : KEYS) {
            if (l.equals(k[0]) || l.startsWith(k[0] + k[1])) {
                return k[2];
            }
        }
        return null;
    }

    /** The {@code record_disk} floor for a cap, in MiB: {@code capMiB + }{@value #DISK_HEADROOM_MIB} (positive). */
    public static long diskFloorMiB(int capMiB) {
        return (long) capMiB + DISK_HEADROOM_MIB;
    }

    /** The disk refusal; {@code freeMiB} = the run folder's usable space in MiB (0 or more), {@code capMiB} positive. */
    public static String diskText(Object run, long freeMiB, int capMiB) {
        return disk(String.valueOf(run), Long.toString(freeMiB), Integer.toString(capMiB));
    }

    /** The template refusal: {@code template} = the template path, {@code key} = one of the five key names. */
    public static String templateText(Object template, String key) {
        return "the launch template " + template + " already sets " + key + ": delete that line, or set record=off";
    }

    /** The {@code record} row refusal echoing the trimmed host value, bounded. */
    static String rowText(String value) {
        return "the row record does not parse: must be off, diag or full, got '" + echo(value) + "'";
    }

    /** The {@code record.maxMiB} row refusal echoing the trimmed host value, bounded. */
    static String capText(String value) {
        return "the row record.maxMiB does not parse: must be a whole number from " + CAP_MIN_MIB + " to " + CAP_MAX_MIB
                + ", got '" + echo(value) + "'";
    }

    private static String disk(String run, String freeMiB, String capMiB) {
        return "recording is on (record=full) but the disk holding " + run + " has " + freeMiB
                + " MiB free, less than record.maxMiB (" + capMiB + ") plus " + DISK_HEADROOM_MIB
                + ": free space, lower record.maxMiB in launcher.properties, or set record=off";
    }

    /**
     * The four refusals with their placeholders ({@code <run> <n> <m>}, {@code <v>}, {@code <v>}, {@code <t> <key>})
     * left in; the text-law rows read this list.
     */
    public static List<String> texts() {
        return List.of(disk("<run>", "<n>", "<m>"), rowText("<v>"), capText("<v>"), templateText("<t>", "<key>"));
    }

    /** A host-typed value as a refusal echoes it: printable ASCII ({@code ?} for anything else), cut at {@value #MAX_ECHO_CHARS} + "...". */
    private static String echo(String value) {
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_ECHO_CHARS) + 3);
        for (int i = 0; i < value.length() && i < MAX_ECHO_CHARS; i++) {
            char c = value.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        if (value.length() > MAX_ECHO_CHARS) {
            sb.append("...");
        }
        return sb.toString();
    }
}
