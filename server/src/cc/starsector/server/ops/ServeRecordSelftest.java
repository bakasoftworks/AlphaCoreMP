package cc.starsector.server.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import cc.starsector.server.ops.ServeRecord.Mode;

/**
 * {@link ServeRecord}'s rows: the {@code record} / {@code record.maxMiB} row grammar, the five lines against MINT's
 * {@code $OwnerBoxRules} outputs, the insertion point, {@code record=off}'s identity, the never-0 cap, the
 * already-set refusal and the text law. Pure (no file, socket or clock); milliseconds.
 *
 * <p>Run through {@link #main} (exit 0 = every row PASS, 1 = a FAIL; the mutant runner's entry). Each row prints ONE
 * line: {@code PASS <row>} or {@code FAIL <row>: <the regression it caught>}.
 *
 *
 */
final class ServeRecordSelftest {

    private ServeRecordSelftest() {
    }

    private static final String MAIN = "cc.starsector.server.runtime.PilotIntentCombatServerMain";

    /** A 6-line fixture: 4 options + the main class + 1 argument. */
    private static final List<String> T6 = List.of("-Xms1g", "-Xmx4g", "-Dfile.encoding=UTF-8", "-XX:+UseShenandoahGC",
            MAIN, "--pace-wall-clock");

    @FunctionalInterface
    private interface Body {
        void run(String row);
    }

    private static void check(String row, boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("FAIL " + row + ": " + what);
        }
    }

    /** The refusal {@code call} must throw; its message. A call that returns FAILs with {@code what}. */
    private static String refused(String row, Runnable call, String what) {
        try {
            call.run();
        } catch (IllegalArgumentException expected) {
            return expected.getMessage();
        }
        throw new AssertionError("FAIL " + row + ": " + what);
    }

    /** {@code call}'s value; a refusal FAILs with {@code what} + the refusal. */
    private static <T> T accepted(String row, Supplier<T> call, String what) {
        try {
            return call.get();
        } catch (IllegalArgumentException refusal) {
            throw new AssertionError("FAIL " + row + ": " + what + " (" + refusal.getMessage() + ")");
        }
    }

    /** Runs one row and prints its ONE line; 1 when it failed. */
    private static int row(String name, Body body) {
        try {
            body.run(name);
            System.out.println("PASS " + name);
            return 0;
        } catch (AssertionError named) {
            String m = String.valueOf(named.getMessage());
            System.out.println(m.startsWith("FAIL " + name + ": ") ? m : "FAIL " + name + ": " + m);
            return 1;
        } catch (RuntimeException | Error other) {
            System.out.println("FAIL " + name + ": " + other);
            return 1;
        }
    }

    /** Every row; exit 1 on a failure. */
    public static void main(String[] args) {
        int failures = run();
        System.out.println("serve-record selftest: " + (failures == 0 ? "all rows PASS" : failures + " row(s) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Every row in order; the failure count (0 = green). */
    static int run() {
        int failures = 0;
        failures += row("record-row-parse", ServeRecordSelftest::rowParse);
        failures += row("record-lines-equal-mint", ServeRecordSelftest::linesEqualMint);
        failures += row("record-insert-before-main", ServeRecordSelftest::insertBeforeMain);
        failures += row("record-off-identical", ServeRecordSelftest::offIdentical);
        failures += row("record-full-cap-nonzero", ServeRecordSelftest::fullCapNonzero);
        failures += row("record-template-dup-refused", ServeRecordSelftest::templateDupRefused);
        failures += row("record-text-law", ServeRecordSelftest::textLaw);
        return failures;
    }

    /** The two rows' grammar: off / diag / full exact-lower + trim + blank = off; the cap 1024..262144, blank = 4096. */
    private static void rowParse(String r) {
        check(r, ServeRecord.parse("off") == Mode.OFF, "parse(off) is not OFF: the row spelling off stopped selecting no recording");
        check(r, ServeRecord.parse("diag") == Mode.DIAG, "parse(diag) is not DIAG: the timing-only spelling moved");
        check(r, ServeRecord.parse("full") == Mode.FULL, "parse(full) is not FULL: the wire-record spelling moved");
        check(r, ServeRecord.parse(" full\t") == Mode.FULL, "a padded full is refused: the row value lost its trim");
        check(r, ServeRecord.parse(null) == Mode.OFF && ServeRecord.parse("") == Mode.OFF
                && ServeRecord.parse("   ") == Mode.OFF, "an absent or blank record row is not OFF: the default stopped being off");
        for (String bad : List.of(" Full ", "FULL", "Diag", "on", "fulll", "0", "off,full")) {
            String m = refused(r, () -> ServeRecord.parse(bad), "parse accepted '" + bad
                    + "': the row stopped being case-exact lower off|diag|full");
            check(r, m.equals(ServeRecord.rowText(bad.trim())), "the refusal of '" + bad
                    + "' is not the row refusal text echoing the trimmed value: " + m);
        }
        check(r, ServeRecord.capMiB(null) == 4096 && ServeRecord.capMiB("") == 4096 && ServeRecord.capMiB("  ") == 4096,
                "an absent or blank record.maxMiB is not 4096: the default cap moved");
        check(r, ServeRecord.capMiB("1024") == 1024, "cap 1024 is not 1024: the 1024..262144 range lost its low end");
        check(r, ServeRecord.capMiB("262144") == 262144, "cap 262144 is not 262144: off-by-one at the high end");
        check(r, ServeRecord.capMiB(" 2048 ") == 2048, "a padded cap is not 2048: the cap lost its trim");
        String arabicThree = String.valueOf((char) 0x0663);
        // 1 and 1023 refuse — a sub-1024 cap passed the row yet met the sink's
        // own floor only AFTER the engine boot + warm-up, once per supervisor life (× 6).
        for (String bad : List.of("0", "00", "1", "1023", "262145", "-1", "+5", "4k", "1.5", "99999999999", "2147483648", arabicThree)) {
            String m = refused(r, () -> ServeRecord.capMiB(bad), "capMiB accepted '" + bad
                    + "': a 0 (= an unbounded wire sink), sub-1024, out-of-range, signed, unit-suffixed or non-ASCII cap leaks through");
            check(r, m.equals(ServeRecord.capText(bad)), "the refusal of cap '" + bad
                    + "' is not the cap refusal text echoing the value: " + m);
        }
    }

    /**
     * A LITERAL copy of MINT's five {@code $OwnerBoxRules} outputs ({@code <n>} =
     * the cap): a MINT rule change does not flip this row by itself, it flips the mint script's own {@code ownerbox-record-rows}
     * pin, and both copies re-pin together.
     */
    private static void linesEqualMint(String r) {
        List<String> mint = List.of(
                "-Dcc.net.sinkPath=${RUN}/wire.ccwr",
                "-Dcc.net.sinkMaxMiB=4096",
                "-Xlog:gc*,safepoint:file=${RUN}/gc.log:time,uptime",
                "-XX:FlightRecorderOptions=repository=${RUN}/jfr-repo",
                "-Dcc.jfr.blocks=${RUN}/serve-blocks.jfr");
        List<String> full = ServeRecord.lines(Mode.FULL, 4096);
        check(r, full.equals(mint), "FULL differs from MINT's five owner-mode lines (byte or order; re-pin with MINT): " + full);
        check(r, ServeRecord.lines(Mode.FULL, 1777).get(1).equals("-Dcc.net.sinkMaxMiB=1777"),
                "FULL's sinkMaxMiB is not the cap passed in: " + ServeRecord.lines(Mode.FULL, 1777));
        List<String> diag = ServeRecord.lines(Mode.DIAG, 4096);
        check(r, diag.equals(mint.subList(2, 5)), "DIAG is not exactly MINT's gc + JFR lines 3-5 (a wire line leaked "
                + "into diag, or a timing line dropped): " + diag);
        check(r, ServeRecord.lines(Mode.OFF, 4096).isEmpty(), "OFF emits a line: record=off would change the argv");
        List<String> sub = ArgvTemplate.substitute(ServeRecord.lines(Mode.FULL, 2048), Map.of("RUN", "/srv/run/flight-1"));
        check(r, sub.get(0).equals("-Dcc.net.sinkPath=/srv/run/flight-1/wire.ccwr")
                && sub.get(2).equals("-Xlog:gc*,safepoint:file=/srv/run/flight-1/gc.log:time,uptime")
                && sub.get(4).equals("-Dcc.jfr.blocks=/srv/run/flight-1/serve-blocks.jfr"),
                "${RUN} does not resolve to the flight dir through ArgvTemplate.substitute: " + sub);
    }

    /** The lines land after the JVM options and before the main class; the main class's arguments stay last. */
    private static void insertBeforeMain(String r) {
        List<String> diag = accepted(r, () -> ServeRecord.withLines(T6, Mode.DIAG, 4096), "the 6-line fixture is refused");
        List<String> want = new ArrayList<>(T6.subList(0, 4));
        want.addAll(ServeRecord.lines(Mode.DIAG, 4096));
        want.addAll(T6.subList(4, 6));
        check(r, diag.equals(want), "DIAG did not land between the 4 options and the main class, arg last: " + diag);
        List<String> mutable = new ArrayList<>(T6);
        List<String> full = accepted(r, () -> ServeRecord.withLines(mutable, Mode.FULL, 2048), "the 6-line fixture is refused");
        check(r, full.size() == 11 && full.subList(4, 9).equals(ServeRecord.lines(Mode.FULL, 2048))
                && full.get(9).equals(MAIN) && full.get(10).equals("--pace-wall-clock"),
                "FULL did not land as 5 lines before the main class with the arg last: " + full);
        check(r, mutable.equals(T6), "withLines modified the caller's template list: " + mutable);
        List<String> real = List.of("${JAVA}", "-Xms${HEAP_MIN}", "-cp", "${PKG}/classes${SEP}${GAME}/starfarer_obf.jar",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-XX:ErrorFile=${RUN}/lives/hs_err_pid%p.log", MAIN,
                "--client-wait-ms", "5000");
        check(r, ServeRecord.mainClassIndex(real) == 7, "the main class of a minted template is not line 8 (the ${JAVA} "
                + "row, the -cp value or the --add-opens value was taken for it): " + ServeRecord.mainClassIndex(real));
        List<String> rd = accepted(r, () -> ServeRecord.withLines(real, Mode.DIAG, 1), "the minted fixture is refused");
        check(r, rd.subList(0, 7).equals(real.subList(0, 7)) && rd.subList(7, 10).equals(ServeRecord.lines(Mode.DIAG, 1))
                && rd.subList(10, 13).equals(real.subList(7, 10)),
                "DIAG did not land just before the main class of a minted template (after the crash-file line): " + rd);
        check(r, ServeRecord.mainClassIndex(List.of("${JAVA}", "-Xmx1g", "-jar", "x.jar", "a")) == 2
                && ServeRecord.mainClassIndex(List.of("${JAVA}", "-m", "mod/Main")) == 1,
                "a -jar / -m main selector is not the insertion point (the lines would split it from its value)");
        List<String> none = accepted(r, () -> ServeRecord.withLines(List.of("${JAVA}", "-Xmx1g"), Mode.DIAG, 1),
                "an options-only template is refused");
        check(r, none.size() == 5 && none.subList(2, 5).equals(ServeRecord.lines(Mode.DIAG, 1)),
                "an options-only template did not get the lines appended last (the no-main-class rule): " + none);
    }

    /** {@code record=off}: the template object itself, never checked, never copied. */
    private static void offIdentical(String r) {
        List<String> t = new ArrayList<>(T6);
        List<String> off = accepted(r, () -> ServeRecord.withLines(t, Mode.OFF, 4096), "record=off refused a plain template");
        check(r, off == t, "record=off returned another list object: off must hand the launcher its template untouched");
        check(r, off.equals(T6) && off.stream().noneMatch(l -> l.startsWith("-Dcc.net.sinkPath")),
                "record=off changed the template or added a sinkPath line: " + off);
        List<String> keyed = List.of("${JAVA}", "-Dcc.jfr.blocks=x", "-Dcc.net.sinkPath=y", MAIN);
        List<String> keyedOff = accepted(r, () -> ServeRecord.withLines(keyed, Mode.OFF, 0),
                "record=off refused a template carrying record keys with cap 0: off must never check (today's argv)");
        check(r, keyedOff == keyed, "record=off with record keys in the template returned another list object");
    }

    /** No FULL output ever carries {@code sinkMaxMiB=0} (0 = {@code WireRecordSink} unbounded). */
    private static void fullCapNonzero(String r) {
        Pattern zero = Pattern.compile("-Dcc\\.net\\.sinkMaxMiB=[+-]?0*");
        for (int cap : new int[] {1024, 1025, 4096, 262144}) {
            List<String> full = ServeRecord.lines(Mode.FULL, cap);
            check(r, full.contains("-Dcc.net.sinkMaxMiB=" + cap), "FULL with cap " + cap + " lacks its sinkMaxMiB line: " + full);
            check(r, full.stream().noneMatch(l -> zero.matcher(l).matches()), "FULL with cap " + cap + " emits sinkMaxMiB=0: " + full);
        }
        for (int bad : new int[] {0, 1, 1023, -1, Integer.MIN_VALUE, 262145, Integer.MAX_VALUE}) {
            String m = refused(r, () -> ServeRecord.lines(Mode.FULL, bad), "lines(FULL, " + bad
                    + ") emitted: a FULL record must always carry a 1024..262144 cap (0 = an unbounded wire sink)");
            check(r, m.equals(ServeRecord.capText(Integer.toString(bad))), "the cap " + bad
                    + " refusal is not the cap text: " + m);
            refused(r, () -> ServeRecord.withLines(T6, Mode.FULL, bad), "withLines(FULL, " + bad
                    + ") emitted: the template path skipped the cap check");
        }
    }

    /** A template already setting a record key refuses, naming the key and never the template's value. */
    private static void templateDupRefused(String r) {
        List<String> t = List.of("${JAVA}", "-Xmx1g", "-Dcc.jfr.blocks=x", MAIN, "a");
        String m = refused(r, () -> ServeRecord.withLines(t, Mode.DIAG, 4096), "a template already setting "
                + "-Dcc.jfr.blocks= was accepted with record=diag: two JFR dump lines, the host's silently shadowed");
        check(r, m.contains("cc.jfr.blocks"), "the already-set refusal does not name the key cc.jfr.blocks: " + m);
        check(r, !m.contains("=x") && !m.contains("'x'"), "the already-set refusal echoes the template's value: " + m);
        check(r, m.equals(ServeRecord.templateText(ServeLauncher.DEFAULT_TEMPLATE, "cc.jfr.blocks")),
                "the already-set refusal is not the text naming serve.argv: " + m);
        String secret = "/home/host/private-7f3a.bin";
        String[][] planted = {
            {"-Dcc.net.sinkPath=" + secret, "cc.net.sinkPath"},
            {"-Dcc.net.sinkMaxMiB=0", "cc.net.sinkMaxMiB"},
            {"-Xlog:gc:file=" + secret, "-Xlog"},
            {"-XX:FlightRecorderOptions=repository=" + secret, "-XX:FlightRecorderOptions"},
            {"-Dcc.jfr.blocks=" + secret, "cc.jfr.blocks"},
        };
        for (Mode mode : List.of(Mode.DIAG, Mode.FULL)) {
            for (String[] p : planted) {
                List<String> tp = List.of("${JAVA}", "-cp", "${PKG}/classes", p[0], MAIN);
                String mm = refused(r, () -> ServeRecord.withLines(tp, mode, 2048), mode.row() + " accepted a template "
                        + "already setting " + p[1] + ": the key check lost that key");
                check(r, mm.equals(ServeRecord.templateText(ServeLauncher.DEFAULT_TEMPLATE, p[1])) && !mm.contains(secret),
                        mode.row() + ": the refusal for " + p[1] + " does not name exactly that key, or echoes the value: " + mm);
            }
        }
        List<String> near = List.of("${JAVA}", "-Dcc.jfr.blocksX=1", "-Dcc.net.sinkPathology=1",
                "-XX:FlightRecorderOptionsX", "-Xlogger", MAIN, "-Dcc.jfr.blocks=after-main");
        List<String> nearOut = accepted(r, () -> ServeRecord.withLines(near, Mode.FULL, 2048), "a near-miss key (another "
                + "property sharing a prefix) or a main-class argument after the main class was refused as already set");
        check(r, nearOut.size() == near.size() + 5, "the near-miss template did not get the 5 FULL lines: " + nearOut);
        String named = refused(r, () -> ServeRecord.withLines(t, Mode.FULL, 2048, "/pkg/serve.argv"),
                "the template-naming overload accepted an already-set key");
        check(r, named.equals("the launch template /pkg/serve.argv already sets cc.jfr.blocks: delete that line, or set record=off"),
                "the template-naming overload's refusal is not the text with the caller's template: " + named);
    }

    /** The four refusals verbatim, console-safe (printable ASCII, no dev token), and the host echo bounded. */
    private static void textLaw(String r) {
        List<String> draft = List.of(
                "recording is on (record=full) but the disk holding <run> has <n> MiB free, less than record.maxMiB (<m>) plus 1024: free space, lower record.maxMiB in launcher.properties, or set record=off",
                "the row record does not parse: must be off, diag or full, got '<v>'",
                "the row record.maxMiB does not parse: must be a whole number from 1024 to 262144, got '<v>'",
                "the launch template <t> already sets <key>: delete that line, or set record=off");
        check(r, ServeRecord.texts().equals(draft), "texts() is not the four refusals verbatim"
                + ": a sentence was reworded, dropped or reordered: " + ServeRecord.texts());
        check(r, ServeRecord.diskText("/srv/pkg/run", 5, 4096).equals(
                draft.get(0).replace("<run>", "/srv/pkg/run").replace("<n>", "5").replace("<m>", "4096")),
                "diskText does not render the disk sentence: " + ServeRecord.diskText("/srv/pkg/run", 5, 4096));
        check(r, ServeRecord.diskFloorMiB(4096) == 5120L && ServeRecord.diskFloorMiB(262144) == 263168L,
                "the disk floor is not cap + 1024 MiB: the sentence's 'plus 1024' no longer tells the truth");
        Pattern dev = Pattern.compile("\\bs\\d{3,5}\\b|TASK-|REVIEW-|ADR-|generated/|local/|docs/");
        List<String> served = new ArrayList<>(ServeRecord.texts());
        served.add(ServeRecord.diskText("/srv/pkg/run", 5, 4096));
        served.add(refused(r, () -> ServeRecord.parse("loud"), "parse accepted loud"));
        served.add(refused(r, () -> ServeRecord.capMiB("0"), "capMiB accepted 0"));
        served.add(refused(r, () -> ServeRecord.withLines(List.of("-Xlog:gc", MAIN), Mode.DIAG, 1), "-Xlog accepted"));
        for (String s : served) {
            check(r, !s.isBlank() && s.chars().allMatch(c -> c >= 0x20 && c < 0x7f),
                    "a refusal is blank or not printable ASCII (console text): " + s);
            check(r, !dev.matcher(s).find(), "a refusal carries a session / task / repo-path token: " + s);
        }
        String longValue = "a".repeat(500);
        String longMsg = refused(r, () -> ServeRecord.parse(longValue), "parse accepted a 500-char value");
        check(r, longMsg.length() <= ServeRecord.rowText("").length() + ServeRecord.MAX_ECHO_CHARS + 3,
                "a 500-char host value is echoed unbounded: " + longMsg.length() + " chars");
        String ctl = "fu" + (char) 0x0A + "ll" + (char) 0x1B + "[2J";
        String ctlMsg = refused(r, () -> ServeRecord.parse(ctl), "parse accepted a value with a newline and an escape");
        check(r, ctlMsg.contains("fu?ll?[2J") && ctlMsg.chars().allMatch(c -> c >= 0x20 && c < 0x7f),
                "a control character in the host value reached the console text: " + ctlMsg);
    }
}
