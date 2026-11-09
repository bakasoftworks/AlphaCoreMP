package cc.starsector.server.ops;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@link ServeRetention}'s rows: the {@code run.keepFlights} grammar (blank, 0, both range ends, the ASCII-digit and
 * length guards), the plan (name order, the floor of 2, the never-set: the new flight, the newest previous one and the
 * flight {@code latest.txt} named; wrong names; 0 = off), the walker over temp trees (a top-level link and a link
 * nested inside a candidate are never followed or deleted, a held-open file on Windows is counted and retried, the
 * depth / entry / listing bounds keep a folder whole), the launcher's never-throw sweep call, and the text law.
 * Temp folders only (each row's own, removed after it); a junction is made with the system's own {@code mklink /J}.
 *
 * <p>Run through {@link #main} (exit 0 = every row PASS or SKIP, 1 = a FAIL / SETUP). Each row prints ONE line:
 * {@code PASS <row>}, {@code SKIP <row>: <why>}, {@code SETUP-<row>: <what did not stage>} or {@code FAIL <row>: <the
 * regression it caught>}.
 *
 *
 */
final class ServeRetentionSelftest {

    private ServeRetentionSelftest() {
    }

    private static final long JUNCTION_WAIT_SECONDS = 20;
    private static final String NEW = "flight-20261109-120000";
    private static final Pattern RECEIPT_ROW =
            Pattern.compile("retention kept=[0-9]+ removed=[0-9]+ failed=[0-9]+ freed_mib=[0-9]+");
    /** Development ids a host text never carries; a path is checked apart (no text names one but the run folder). */
    private static final Pattern DEV = Pattern.compile("\\bs\\d{3,5}\\b|TASK-|REVIEW-|ADR-");

    @FunctionalInterface
    private interface Body {
        void run(String row) throws Exception;
    }

    private static final class SetupFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SetupFailure(String message) {
            super(message);
        }
    }

    private static final class Skipped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Skipped(String message) {
            super(message);
        }
    }

    private static void check(String row, boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("FAIL " + row + ": " + what);
        }
    }

    /** Runs one row and prints its ONE line; 1 when it failed or did not stage. */
    private static int row(String name, Body body) {
        try {
            body.run(name);
            System.out.println("PASS " + name);
            return 0;
        } catch (Skipped skipped) {
            System.out.println("SKIP " + name + ": " + skipped.getMessage());
            return 0;
        } catch (SetupFailure setup) {
            System.out.println("SETUP-" + name + ": " + setup.getMessage());
            return 1;
        } catch (AssertionError named) {
            String m = String.valueOf(named.getMessage());
            System.out.println(m.startsWith("FAIL " + name + ": ") ? m : "FAIL " + name + ": " + m);
            return 1;
        } catch (Exception | Error other) {
            System.out.println("FAIL " + name + ": " + other);
            return 1;
        }
    }

    public static void main(String[] args) {
        int failures = run();
        System.out.println("serve-retention selftest: " + (failures == 0 ? "all rows PASS" : failures + " row(s) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Every row; the failure count. */
    static int run() {
        int failures = 0;
        failures += row("retention-row-parse", ServeRetentionSelftest::rowParse);
        failures += row("retention-plan-order", ServeRetentionSelftest::planOrder);
        failures += row("retention-plan-floor", ServeRetentionSelftest::planFloor);
        failures += row("retention-plan-never", ServeRetentionSelftest::planNever);
        failures += row("retention-plan-names", ServeRetentionSelftest::planNames);
        failures += row("retention-plan-off", ServeRetentionSelftest::planOff);
        failures += row("retention-walk-tree", ServeRetentionSelftest::walkTree);
        failures += row("retention-walk-top-link", ServeRetentionSelftest::walkTopLink);
        failures += row("retention-walk-nested-link", ServeRetentionSelftest::walkNestedLink);
        failures += row("retention-walk-locked", ServeRetentionSelftest::walkLocked);
        failures += row("retention-walk-bounds", ServeRetentionSelftest::walkBounds);
        failures += row("retention-walk-listing-cap", ServeRetentionSelftest::walkListingCap);
        failures += row("retention-sweep-unlisted", ServeRetentionSelftest::sweepUnlisted);
        failures += row("retention-text-law", ServeRetentionSelftest::textLaw);
        return failures;
    }

    // ------------------------------------------------------------------ the row grammar

    private static void rowParse(String r) {
        for (String blank : new String[] {null, "", "   "}) {
            check(r, ServeRetention.parseKeep(blank) == 20, "a blank row '" + blank + "' did not keep 20 - flips if the"
                    + " default changes or a blank row refuses");
        }
        String[][] good = {{"0", "0"}, {"2", "2"}, {"10000", "10000"}, {" 7 ", "7"}, {"000000020", "20"}};
        for (String[] g : good) {
            int got;
            try {
                got = ServeRetention.parseKeep(g[0]);
            } catch (IllegalArgumentException refused) {
                throw new AssertionError("FAIL " + r + ": '" + g[0] + "' was refused (" + refused.getMessage()
                        + ") - flips if 0 (keep all), a range end, a padded or a 9-digit zero-led value refuses");
            }
            check(r, got == Integer.parseInt(g[1]), "'" + g[0] + "' parsed as " + got + ", want " + g[1]);
        }
        String[] bad = {"1", "10001", "-1", "+5", "abc", "1e3", "2.0", "２０", "٢٠", "0000000020",
            "99999999999", "20 30"};
        for (String b : bad) {
            String msg = refusedKeep(r, b);
            check(r, msg.contains("from 2 to 10000") && msg.contains("got '"), "the refusal for '" + b + "' does not"
                    + " name the range and echo the value: " + msg);
        }
        String longValue = "9".repeat(200);
        String msg = refusedKeep(r, longValue + "x");
        check(r, msg.length() < 200 && msg.contains("..."), "a 200-char value is echoed unbounded - flips if the echo"
                + " cap is lost: " + msg.length() + " chars");
        String odd = refusedKeep(r, "a\u0000bé");
        check(r, odd.contains("got 'a?b?'"), "a control / non-ASCII char reached the refusal unmasked: " + odd);
    }

    /** The refusal message; a value that parses FAILs (naming the regression). */
    private static String refusedKeep(String r, String value) {
        try {
            int n = ServeRetention.parseKeep(value);
            throw new AssertionError("FAIL " + r + ": '" + value + "' parsed as " + n + " - flips if the floor of 2,"
                    + " the ceiling, the ASCII-digit guard (Integer.parseInt takes non-ASCII digits) or the length"
                    + " cap is lost");
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    // ------------------------------------------------------------------ the plan

    private static String seeded(int i) {
        return String.format(Locale.ROOT, "flight-20200101-%06d", i);
    }

    private static List<String> seededNames(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add(seeded(i));
        }
        return names;
    }

    private static void planOrder(String r) {
        List<String> names = seededNames(25);
        Collections.shuffle(names, new java.util.Random(7));
        names.add(3, NEW);
        List<String> plan = ServeRetention.plan(names, NEW, null, 20);
        check(r, plan.equals(seededNames(6)), "25 seeded + the new flight at keep 20 planned " + plan + ", want the 6"
                + " oldest by name, oldest first - flips if the order follows the listing, runs newest-first, or the"
                + " new flight is not counted");
        List<String> without = ServeRetention.plan(seededNames(25), NEW, null, 20);
        check(r, without.equals(seededNames(6)), "a listing WITHOUT the new flight planned " + without + " - flips if"
                + " the new flight counts only when listed");
    }

    private static void planFloor(String r) {
        List<String> plan = ServeRetention.plan(seededNames(5), NEW, null, 2);
        check(r, plan.equals(seededNames(4)), "keep 2 over 5 + the new flight planned " + plan + ", want all but the"
                + " newest previous - flips if the floor keeps fewer or more than the new + the newest previous");
        for (int k : new int[] {1, -1, 10001}) {
            boolean threw;
            try {
                ServeRetention.plan(seededNames(5), NEW, null, k);
                threw = false;
            } catch (IllegalArgumentException expected) {
                threw = true;
            }
            check(r, threw, "plan accepted keep " + k + " - flips if a keep outside 0 or 2..10000 plans");
        }
        check(r, ServeRetention.plan(seededNames(5), NEW, null, 10000).isEmpty(), "keep 10000 over 6 planned a delete");
        check(r, ServeRetention.plan(seededNames(5), NEW, null, 6).isEmpty(), "keep 6 over exactly 6 planned a delete"
                + " - flips on an off-by-one in the excess");
    }

    private static void planNever(String r) {
        List<String> pointed = ServeRetention.plan(seededNames(5), NEW, seeded(0), 2);
        check(r, pointed.equals(List.of(seeded(1), seeded(2), seeded(3))), "the flight latest.txt named ("
                + seeded(0) + ", the oldest) was planned: " + pointed + " - flips if the pointer's flight is not"
                + " protected (a backward clock step deletes the real last flight)");
        String past = "flight-20190101-000000";
        List<String> stepped = ServeRetention.plan(seededNames(5), past, null, 2);
        check(r, stepped.equals(List.of(seeded(0), seeded(1), seeded(2))), "a new flight sorting OLDEST (clock"
                + " stepped back) gave " + stepped + ", want the new and the newest previous never planned, nothing"
                + " younger planned in their place");
        check(r, !ServeRetention.plan(seededNames(5), NEW, "not-a-flight", 2).contains("not-a-flight"),
                "a non-flight pointer name was planned");
    }

    private static void planNames(String r) {
        List<String> names = new ArrayList<>(List.of("flight-2020-01-01", "flight-20200101-0000001",
                "Flight-20200101-000000", "flight-20200101-00000", "xflight-20200101-000000",
                "flight-20200101-000000.zip", "flight-20200101_000000", "flight-２0200101-000000", "..",
                "flight-20200101-000000/..", "flight-19990101-000000 "));
        names.add(null);
        names.addAll(seededNames(3));
        List<String> plan = ServeRetention.plan(names, NEW, null, 2);
        check(r, plan.equals(List.of(seeded(0), seeded(1))), "a mixed listing planned " + plan + ", want the two"
                + " oldest real flight names only - flips if a near-miss name (case, length, suffix, separator,"
                + " non-ASCII digit, trailing space) is planned or counted");
    }

    private static void planOff(String r) {
        check(r, ServeRetention.plan(seededNames(50), NEW, null, ServeRetention.KEEP_ALL).isEmpty(),
                "keep 0 planned a delete - flips if 0 stops meaning keep all");
    }

    // ------------------------------------------------------------------ the walker

    private static void walkTree(String r) throws IOException {
        Path root = tempRoot();
        try {
            Path run = Files.createDirectories(root.resolve("run"));
            byte[] mib = new byte[1 << 20];
            for (int i = 0; i < 5; i++) {
                Path f = seed(run, seeded(i));
                write(f.resolve("wrapper-receipt.txt"), "x\n");
                Files.write(Files.createDirectories(f.resolve("lives")).resolve("life-1.out.log"), mib);
            }
            Files.createDirectories(run.resolve(NEW));
            Files.createDirectories(run.resolve("ops"));
            write(run.resolve("latest.txt"), seeded(4) + "\n");
            write(run.resolve("flight-20000101-000000"), "a FILE with a flight name\n");
            ServeRetention.Result res = ServeRetention.sweep(run, NEW, seeded(4), 3);
            check(r, res.receiptRow().equals("retention kept=3 removed=3 failed=0 freed_mib=3"), "the sweep's row is '"
                    + res.receiptRow() + "' - flips if the count, the freed bytes, or a file entry counted as a flight");
            for (int i = 0; i < 5; i++) {
                boolean there = Files.exists(run.resolve(seeded(i)), LinkOption.NOFOLLOW_LINKS);
                check(r, there == (i >= 3), seeded(i) + (there ? " survived" : " was removed") + " at keep 3 - flips if"
                        + " the oldest by name do not go first");
            }
            check(r, Files.isDirectory(run.resolve(NEW)) && Files.isDirectory(run.resolve("ops"))
                    && Files.isRegularFile(run.resolve("latest.txt"))
                    && Files.isRegularFile(run.resolve("flight-20000101-000000")), "the new flight or a non-candidate"
                    + " entry of run was touched");
            List<String> lines = res.consoleLines(run, 3);
            check(r, lines.equals(List.of("serve-launcher: removed 3 old flight folders from " + run + " (3 MiB); the"
                    + " newest 3 are kept (run.keepFlights in launcher.properties; 0 keeps all)")), "the host line is "
                    + lines);
            check(r, new ServeRetention.Result(20, 0, 0, 0L, null, false).consoleLines(run, 20).isEmpty(),
                    "a sweep that removed nothing printed a line - flips if a clean start stops being quiet");
        } finally {
            cleanup(root);
        }
    }

    private static void walkTopLink(String r) throws IOException {
        Path root = tempRoot();
        try {
            Path run = Files.createDirectories(root.resolve("run"));
            Path sentinel = Files.createDirectories(root.resolve("sentinel"));
            write(sentinel.resolve("keep.txt"), "keep\n");
            Path link = run.resolve("flight-20000101-000000");
            dirLinkOrSkip(link, sentinel);
            for (int i = 0; i < 3; i++) {
                write(seed(run, seeded(i)).resolve("wrapper-receipt.txt"), "x\n");
            }
            Files.createDirectories(run.resolve(NEW));
            ServeRetention.Result res = ServeRetention.sweep(run, NEW, null, 2);
            check(r, res.receiptRow().equals("retention kept=2 removed=2 failed=0 freed_mib=0"), "a top-level link"
                    + " named like the oldest flight gave '" + res.receiptRow() + "' - flips if a link is counted or"
                    + " planned");
            check(r, PackageFolderWriter.isLink(link) && Files.isRegularFile(sentinel.resolve("keep.txt")),
                    "the top-level link or its target's file is gone - flips if a link entry is followed or deleted");
        } finally {
            cleanup(root);
        }
    }

    private static void walkNestedLink(String r) throws IOException {
        Path root = tempRoot();
        try {
            Path run = Files.createDirectories(root.resolve("run"));
            Path sentinel = Files.createDirectories(root.resolve("sentinel"));
            write(sentinel.resolve("keep.txt"), "keep\n");
            Path oldest = seed(run, seeded(0));
            Path log = Files.createDirectories(oldest.resolve("lives")).resolve("life-1.out.log");
            write(log, "log\n");
            Path link = Files.createDirectories(oldest.resolve("settings")).resolve("linked");
            dirLinkOrSkip(link, sentinel);
            for (int i = 1; i < 3; i++) {
                write(seed(run, seeded(i)).resolve("wrapper-receipt.txt"), "x\n");
            }
            Files.createDirectories(run.resolve(NEW));
            ServeRetention.Result res = ServeRetention.sweep(run, NEW, null, 2);
            check(r, Files.isRegularFile(sentinel.resolve("keep.txt")), "a file OUTSIDE run behind a junction inside a"
                    + " candidate was deleted - flips if the walk descends a nested link");
            check(r, Files.isRegularFile(log) && PackageFolderWriter.isLink(link), "the candidate holding a link was"
                    + " deleted part-way (its life log or the link is gone) - flips if the check walk stops keeping it"
                    + " whole");
            check(r, res.removed() == 1 && res.failed() == 1 && seeded(0).equals(res.firstFailed())
                    && !res.firstFailedInUse() && !Files.exists(run.resolve(seeded(1))), "the row is '"
                    + res.receiptRow() + "' (first failed " + res.firstFailed() + ") - want the linked folder failed"
                    + " and the next one removed");
            List<String> lines = res.consoleLines(run, 2);
            check(r, lines.size() == 2 && lines.get(1).contains(seeded(0)) && lines.get(1).endsWith(
                    ServeRetention.UNSAFE_TEXT), "the warning does not name the kept folder and say why: " + lines);
        } finally {
            cleanup(root);
        }
    }

    private static void walkLocked(String r) throws IOException {
        if (File.separatorChar != '\\') {
            throw new Skipped("an open file blocks its delete on Windows only");
        }
        Path root = tempRoot();
        try {
            Path probe = root.resolve("probe.txt");
            write(probe, "probe\n");
            try (FileOutputStream held = new FileOutputStream(probe.toFile(), true)) {
                try {
                    Files.delete(probe);
                    throw new Skipped("this system deleted an open file (no delete lock to test)");
                } catch (FileSystemException expected) {
                    // the lock holds: the row can run
                }
            }
            Path run = Files.createDirectories(root.resolve("run"));
            Path oldest = seed(run, seeded(0));
            write(oldest.resolve("wrapper-receipt.txt"), "x\n");
            Path clientLog = Files.createDirectories(oldest.resolve("client")).resolve("cc-client.log");
            write(clientLog, "client\n");
            for (int i = 1; i < 3; i++) {
                write(seed(run, seeded(i)).resolve("wrapper-receipt.txt"), "x\n");
            }
            Files.createDirectories(run.resolve(NEW));
            ServeRetention.Result first;
            try (FileOutputStream held = new FileOutputStream(clientLog.toFile(), true)) {
                first = ServeRetention.sweep(run, NEW, null, 2);
            }
            check(r, first.failed() == 1 && first.removed() == 1 && first.firstFailedInUse()
                    && seeded(0).equals(first.firstFailed()), "a held-open log gave '" + first.receiptRow()
                    + "' (in use " + first.firstFailedInUse() + ") - flips if a failed delete is not counted or the"
                    + " sweep stops at it");
            check(r, Files.isRegularFile(clientLog) && !Files.exists(oldest.resolve("wrapper-receipt.txt")),
                    "the held folder is not half-emptied leaf-first (open log kept, the receipt deleted)");
            List<String> lines = first.consoleLines(run, 2);
            check(r, lines.size() == 2 && lines.get(1).contains(seeded(0))
                    && lines.get(1).endsWith(ServeRetention.IN_USE_TEXT), "the warning does not name the held folder"
                    + " and the next step: " + lines);
            ServeRetention.Result again = ServeRetention.sweep(run, NEW, null, 2);
            check(r, again.removed() == 1 && again.failed() == 0 && !Files.exists(oldest), "the next start did not"
                    + " remove the released folder: '" + again.receiptRow() + "' - flips if a failed folder leaves the"
                    + " candidates (renamed, or marked)");
        } finally {
            cleanup(root);
        }
    }

    private static void walkBounds(String r) throws IOException {
        Path root = tempRoot();
        try {
            Path run = Files.createDirectories(root.resolve("run"));
            Path deep = seed(run, seeded(0));
            Path deepFile = Files.createDirectories(deep.resolve("a").resolve("b").resolve("c")).resolve("f.txt");
            write(deepFile, "deep\n");
            Path wide = seed(run, seeded(1));
            for (int i = 0; i < 7; i++) {
                write(wide.resolve("f" + i + ".txt"), "wide\n");
            }
            Path fits = seed(run, seeded(2));
            write(fits.resolve("f0.txt"), "x\n");
            write(fits.resolve("f1.txt"), "x\n");
            write(Files.createDirectories(fits.resolve("a")).resolve("x.txt"), "x\n");
            seed(run, seeded(3));
            Files.createDirectories(run.resolve(NEW));
            ServeRetention.Result res = ServeRetention.sweep(run, NEW, null, 2, 3, 6, 100);
            check(r, res.removed() == 1 && res.failed() == 2 && !Files.exists(fits), "depth 3 / 6 entries gave '"
                    + res.receiptRow() + "' - want the deep and the wide folder kept, the fitting one removed");
            check(r, Files.isRegularFile(deepFile), "a folder past the depth bound was deleted part-way - flips if the"
                    + " depth bound stops keeping it whole");
            for (int i = 0; i < 7; i++) {
                check(r, Files.isRegularFile(wide.resolve("f" + i + ".txt")), "a folder past the entry bound lost f" + i
                        + " - flips if the entry bound stops keeping it whole");
            }
        } finally {
            cleanup(root);
        }
    }

    private static void walkListingCap(String r) throws IOException {
        Path root = tempRoot();
        try {
            Path run = Files.createDirectories(root.resolve("run"));
            for (int i = 0; i < 5; i++) {
                write(seed(run, seeded(i)).resolve("wrapper-receipt.txt"), "x\n");
            }
            boolean threw;
            try {
                ServeRetention.sweep(run, NEW, null, 2, 64, 1000, 4);
                threw = false;
            } catch (IOException expected) {
                threw = true;
            }
            check(r, threw, "a run folder over the listing bound was swept - flips if the bound is lost (the oldest"
                    + " named from part of a listing)");
            for (int i = 0; i < 5; i++) {
                check(r, Files.isDirectory(run.resolve(seeded(i))), seeded(i) + " was deleted although the listing"
                        + " ran over its bound");
            }
        } finally {
            cleanup(root);
        }
    }

    private static void sweepUnlisted(String r) throws IOException {
        Path root = tempRoot();
        try {
            Path notAFolder = root.resolve("run");
            write(notAFolder, "a file where run should be\n");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            String row;
            try {
                row = ServeLauncher.sweepOldFlights(notAFolder, NEW, "", 20, out);
            } catch (RuntimeException thrown) {
                throw new AssertionError("FAIL " + r + ": the launcher's sweep threw " + thrown + " - flips if an"
                        + " unlistable run ends a start after its checks passed");
            }
            String console = bytes.toString(StandardCharsets.UTF_8).trim();
            check(r, row == null && console.startsWith("serve-launcher warning: old flight folders in ")
                    && console.endsWith("none were removed") && console.lines().count() == 1, "an unlistable run gave"
                    + " row " + row + " / console '" + console + "' - want no receipt row and ONE warning");
        } finally {
            cleanup(root);
        }
    }

    // ------------------------------------------------------------------ the text law

    private static void textLaw(String r) {
        List<String> texts = new ArrayList<>();
        texts.add(ServeRetention.keepText("<v>"));
        texts.add(ServeRetention.IN_USE_TEXT);
        texts.add(ServeRetention.UNSAFE_TEXT);
        ServeRetention.Result res = new ServeRetention.Result(20, 3, 1, 5L << 20, seeded(0), true);
        texts.addAll(res.consoleLines("/srv/pkg/run", 20));
        texts.addAll(new ServeRetention.Result(20, 1, 2, 0L, seeded(0), false).consoleLines("/srv/pkg/run", 20));
        Pattern we = Pattern.compile("\\b(we|our|us)\\b", Pattern.CASE_INSENSITIVE);
        for (String s : texts) {
            check(r, !s.isBlank() && s.chars().allMatch(c -> c >= 0x20 && c < 0x7f), "a host text is not printable"
                    + " ASCII: " + s);
            check(r, !DEV.matcher(s).find(), "a host text carries a development token: " + s);
            String bare = s.replace("/srv/pkg/run", "<run>");
            check(r, !bare.contains("/") && !bare.contains("\\"), "a host text names a path besides the run folder: " + s);
            check(r, !we.matcher(s).find() && !s.contains("console"), "a host text breaks the voice law (we / our /"
                    + " us, or 'console' for the ops GUI): " + s);
        }
        check(r, texts.size() == 7 && texts.get(3).contains("removed 3 old flight folders")
                && texts.get(5).contains("removed 1 old flight folder from"), "the plural / singular host line drifted: "
                + texts);
        check(r, RECEIPT_ROW.matcher(res.receiptRow()).matches() && res.receiptRow().endsWith("freed_mib=5"),
                "the receipt row is not the machine token: " + res.receiptRow());
    }

    // ------------------------------------------------------------------ fixtures

    private static Path tempRoot() {
        try {
            return Files.createTempDirectory("cc-retention-selftest");
        } catch (IOException e) {
            throw new SetupFailure("no temp folder (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static Path seed(Path run, String name) throws IOException {
        return Files.createDirectories(run.resolve(name));
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.US_ASCII);
    }

    /**
     * A directory link at {@code link} onto {@code target}: on Windows a junction made by the system's own {@code
     * mklink /J} (it needs no privilege, so it is the link a host's folder actually meets, and the walk sees it as a
     * directory that is also "other"), elsewhere a symbolic link; a {@link Skipped} when the platform refuses it.
     */
    private static void dirLinkOrSkip(Path link, Path target) {
        if (File.separatorChar != '\\') {
            try {
                Files.createSymbolicLink(link, target);
                return;
            } catch (FileSystemException | UnsupportedOperationException refused) {
                throw new Skipped("symlink creation refused by the OS (" + refused.getClass().getSimpleName() + ")");
            } catch (IOException other) {
                throw new SetupFailure("symlink " + other.getClass().getSimpleName());
            }
        }
        int code;
        try {
            Process mk = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!mk.waitFor(JUNCTION_WAIT_SECONDS, TimeUnit.SECONDS)) {
                mk.destroyForcibly();
                throw new Skipped("mklink /J did not finish in " + JUNCTION_WAIT_SECONDS + " s");
            }
            code = mk.exitValue();
        } catch (IOException noCmd) {
            throw new Skipped("no junction could be made (" + noCmd.getClass().getSimpleName() + ")");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SetupFailure("interrupted while making a junction");
        }
        if (code != 0 || !PackageFolderWriter.isLink(link)) {
            throw new Skipped("mklink /J exited " + code + " (or made no reparse point)");
        }
    }

    /**
     * Removes a row's temp tree without ever descending a link: a link or junction entry is deleted itself (its
     * target, the row's sentinel inside the same tree, is removed as a plain folder). A miss prints one note.
     */
    private static void cleanup(Path root) {
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                            if (a.isOther() || a.isSymbolicLink()) {
                                Files.delete(d);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                            Files.delete(f);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                            Files.delete(d);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException miss) {
            System.out.println("note: the temp folder " + root + " was not fully removed ("
                    + miss.getClass().getSimpleName() + ")");
        }
    }
}
