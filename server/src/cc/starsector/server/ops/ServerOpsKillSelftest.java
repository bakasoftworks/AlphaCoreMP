package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ServerOpsKill}'s ORDER rows - the stop file FIRST, the live writer, the running lives,
 * the launcher LAST - re-homed from the retired July web shell's selftest
 * ({@code ServerOpsWebSelftest}, deleted with {@code ServerOpsWeb}) so this product truth
 * outlives that shell (the July shell is replaced, not patched). Rows 1-6 drive the
 * pure {@link ServerOpsKill#killOrder} through recording fakes; row 7 {@code kill-order-real-child}
 * drives {@link ServerOpsKill#killPublicServe} - the real wiring both consoles' Kill calls - against
 * a REAL child JVM (this class's {@code --child}, which only sleeps); the known-launcher rows
 * ({@code kill-order-known-launcher-terminal-pointer}, {@code kill-order-live-pointer-wins}: real
 * children; {@code kill-order-old-lives-unhanded}: the gated receipt directly) pin which launcher
 * {@link ServerOpsKill#killPublicServe} trusts and which receipt it reads. The web Kill's HTTP half
 * (confirm, single flight) is pinned by {@code OpsConsoleSelftest}'s {@code web-kill-*} rows, not
 * here.
 *
 * <p>Run in the build gate by {@code OpsSelftests --selftest} ({@code server-ops selftest}) and
 * directly through {@link #main}; game-free, a few seconds. A failed row throws an
 * {@link AssertionError} naming it ({@code SETUP-<row>} when its fixture never staged). Pids: &gt; 0
 * = a process; 0 (or negative) = none / unknown, never alive to the order.
 *
 *
 */
final class ServerOpsKillSelftest {

    /** The {@code --child} JVM's whole life: it never exits on its own inside row 7, and an orphan
     * (the gate JVM died before row 7's {@code finally}) retires itself after this. */
    private static final long CHILD_SLEEP_MS = 120_000L;

    private ServerOpsKillSelftest() {
    }

    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("kill selftest check failed: " + what);
        }
    }

    /** {@code --child} = sleep {@link #CHILD_SLEEP_MS} (row 7's live writer); else every row, exit 1 on a failure. */
    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "--child".equals(args[0])) {
            Thread.sleep(CHILD_SLEEP_MS);
            return;
        }
        try {
            run();
        } catch (Exception | AssertionError e) {
            System.out.println("server-ops-kill selftest FAILED: " + e);
            System.exit(1);
        }
        System.out.println("server-ops-kill selftest PASS");
    }

    /** Every row over one temp tree, removed after (a residue is said once, never fatal). */
    static void run() throws Exception {
        Path dir = Files.createTempDirectory("cc-ops-kill-selftest");
        try {
            killOrderRows(dir.resolve("kill-rows"));
            realChildRow(dir.resolve("run").resolve("ops").resolve("ops-status.txt"));
            knownLauncherRows(dir.resolve("known-rows"));
        } finally {
            deleteTree(dir);
        }
        System.out.println("server-ops-kill selftest checks=" + checks);
    }

    // ------------------------------------------------------------------ Kill order rows
    // ServerOpsKill's order through
    // recording fakes (rows 1-6) and the REAL killPublicServe wiring over a live child JVM (row 7,
    // its HTTP driver dropped with the July shell). Each failure line names its row; a
    // fixture that never staged reads SETUP-<row>.

    /** A named row's assertion: the failure line carries {@code <row>: <what flips it>}. */
    private static void row(String row, boolean ok, String flipsIf) {
        check(ok, row + ": " + flipsIf);
    }

    @FunctionalInterface
    private interface Staging {
        void run() throws Exception;
    }

    /** A fixture step; its throw reads {@code SETUP-<row>} (an ERROR, never a mutant kill). */
    private static void setup(String row, Staging step) {
        try {
            step.run();
        } catch (Exception e) {
            throw new AssertionError("SETUP-" + row + ": " + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }

    /** The recording world of rows 1-5: an alive set, every kill in order (with whether the stop
     * file existed AT that call), every wait, and a receipt script read per poll. */
    private static final class Fake {
        final java.util.Set<Long> alive = new java.util.HashSet<>();
        final List<Long> kills = new ArrayList<>();
        final List<Boolean> stopAtKill = new ArrayList<>();
        final List<Long> waits = new ArrayList<>();
        final Path stop;
        int reads;
        /** Receipt reads from this index on (1-based) return {@code later}; earlier ones {@code first}. */
        int laterFrom = Integer.MAX_VALUE;
        List<String> first = List.of();
        List<String> later = List.of();
        /** The pid that exits once the later receipt is served (the launcher after its terminal row). */
        long exitsWithLater;

        Fake(Path stop, long... alivePids) {
            this.stop = stop;
            for (long p : alivePids) {
                alive.add(p);
            }
        }

        ServerOpsKill.Outcome order(long writerPid, long launcherPid, long pollMs, long boundMs) {
            return ServerOpsKill.killOrder(stop, writerPid, launcherPid, alive::contains,
                    pid -> {
                        kills.add(pid);
                        stopAtKill.add(stopExists(stop));
                        alive.remove(pid);
                    },
                    () -> {
                        reads++;
                        if (reads >= laterFrom) {
                            alive.remove(exitsWithLater);
                            return later;
                        }
                        return first;
                    },
                    waits::add, pollMs, boundMs);
        }
    }

    /** The fake kill's stop-file probe, kept in THIS class (its sim-residency row covers the
     * selftest's temp-dir fixture I/O; the nested fake carries none). */
    private static boolean stopExists(Path stop) {
        return Files.exists(stop);
    }

    static void killOrderRows(Path root) throws Exception {
        final long writer = 1_001L;
        final long launcher = 2_002L;

        // (1) writer live: stop file BEFORE the first kill, the writer only, the receipt's terminal row
        String r1 = "kill-order-writer-live";
        Fake f1 = new Fake(root.resolve("k1").resolve("run").resolve("stop"), writer, launcher);
        f1.first = List.of("supervisor_pid=" + launcher, "life=1 pid=" + writer);
        f1.later = List.of("supervisor_pid=" + launcher, "life=1 pid=" + writer, "life=1 exit=1",
                "stopped=true lives=1");
        f1.laterFrom = 2;
        f1.exitsWithLater = launcher;
        ServerOpsKill.Outcome o1 = f1.order(writer, launcher, 1_000L, 8_000L);
        row(r1, f1.stopAtKill.equals(List.of(true)), "the stop file did not exist at the kill call "
                + f1.stopAtKill + " - flips if the stop file is written after (or not before) the kill");
        row(r1, f1.kills.equals(List.of(writer)), "kills " + f1.kills
                + " - flips if the writer is not ended or a life / the launcher is ended despite the terminal row");
        row(r1, "stopped=true lives=1".equals(o1.terminalRow()), "terminalRow " + o1.terminalRow()
                + " - flips if the receipt poll misses the supervisor's terminal row");
        row(r1, o1.stopWritten() && o1.writerKilled() && !o1.launcherKilled() && o1.livesKilled().isEmpty(),
                "outcome " + o1 + " - flips if the outcome misreports the order's effects");
        row(r1, Files.isRegularFile(f1.stop) && Files.size(f1.stop) == 0L,
                "the stop file is gone or not empty after the order - flips if the order deletes it (the next launcher does)");
        row(r1, f1.waits.equals(List.of(1_000L)) && f1.reads == 2, "waits " + f1.waits + " reads " + f1.reads
                + " - flips if the poll does not wait pollMs between reads or keeps polling past the terminal row");
        row(r1, o1.text().contains("server pid 1001 ended") && o1.text().contains("stopped=true lives=1")
                && o1.text().contains("launcher pid 2002 exited"), "text '" + o1.text()
                + "' - flips if the operator sentence drops the pid / receipt / launcher exit");
        System.out.println("  PASS " + r1);

        // (2) the gap: writer dead, launcher alive -> proceeds, no writer kill, same terminal
        String r2 = "kill-order-gap";
        Fake f2 = new Fake(root.resolve("k2").resolve("run").resolve("stop"), launcher);
        f2.first = List.of("supervisor_pid=" + launcher, "life=1 pid=" + writer, "life=1 exit=1");
        f2.later = List.of("supervisor_pid=" + launcher, "life=1 pid=" + writer, "life=1 exit=1",
                "stopped=true lives=1");
        f2.laterFrom = 2;
        f2.exitsWithLater = launcher;
        ServerOpsKill.Outcome o2 = f2.order(writer, launcher, 1_000L, 8_000L);
        row(r2, !o2.nothingToStop() && o2.stopWritten() && Files.isRegularFile(f2.stop),
                "outcome " + o2 + " - flips if a live launcher with a dead writer is refused (the gap unkillable)");
        row(r2, f2.kills.isEmpty() && !o2.writerKilled(), "kills " + f2.kills
                + " - flips if the dead writer's pid is killed in the gap");
        row(r2, "stopped=true lives=1".equals(o2.terminalRow()) && !o2.launcherKilled()
                && o2.text().contains("no live server process"), "outcome " + o2
                + " - flips if the gap Kill does not end on the supervisor's stopped row");
        System.out.println("  PASS " + r2);

        // (3) no terminal row in the bound: running lives first, THEN the launcher (never first)
        String r3 = "kill-order-no-receipt";
        long lifeDone = 3_003L;
        long lifeRunning = 4_004L;
        Fake f3 = new Fake(root.resolve("k3").resolve("run").resolve("stop"), launcher, lifeDone, lifeRunning);
        f3.first = List.of("supervisor_pid=" + launcher, "life=1 pid=" + lifeDone, "life=1 exit=1",
                "life=2 pid=" + lifeRunning);
        ServerOpsKill.Outcome o3 = f3.order(0L, launcher, 1_000L, 3_000L);
        row(r3, o3.terminalRow() == null && f3.waits.equals(List.of(1_000L, 1_000L, 1_000L)),
                "terminal " + o3.terminalRow() + " waits " + f3.waits
                        + " - flips if the bound is not honoured (polls past / short of boundMs)");
        row(r3, o3.livesKilled().equals(List.of(lifeRunning)) && f3.alive.contains(lifeDone),
                "livesKilled " + o3.livesKilled() + " - flips if a life with an exit row is ended or the running life is skipped");
        row(r3, o3.launcherKilled() && f3.kills.equals(List.of(lifeRunning, launcher)),
                "kills " + f3.kills + " - flips if the launcher is ended FIRST (orphans the serve) or not at all");
        row(r3, f3.stopAtKill.equals(List.of(true, true)), "stop at kills " + f3.stopAtKill
                + " - flips if any kill precedes the stop file");
        System.out.println("  PASS " + r3);

        // (3b) a running-life pid the lifeKillable guard refuses (the real wiring's
        // start-time rule: a recycled pid) is SAID and left alone; the other life and THEN the launcher still go
        String r3b = "kill-order-life-guard";
        long lifeRecycled = 5_005L;
        Path stop3b = root.resolve("k3b").resolve("run").resolve("stop");
        java.util.Set<Long> alive3b = new java.util.HashSet<>(List.of(launcher, lifeRunning, lifeRecycled));
        List<Long> kills3b = new java.util.ArrayList<>();
        List<String> receipt3b = List.of("supervisor_pid=" + launcher, "life=1 pid=" + lifeRunning,
                "life=2 pid=" + lifeRecycled);
        ServerOpsKill.Outcome o3b = ServerOpsKill.killOrder(stop3b, 0L, launcher, alive3b::contains,
                pid -> pid != lifeRecycled, pid -> {
                    kills3b.add(pid);
                    alive3b.remove(pid);
                }, () -> receipt3b, ms -> { }, 1_000L, 2_000L);
        row(r3b, o3b.livesKilled().equals(List.of(lifeRunning)) && kills3b.equals(List.of(lifeRunning, launcher))
                && alive3b.contains(lifeRecycled) && o3b.text().contains("pid " + lifeRecycled + " left alone"),
                "livesKilled " + o3b.livesKilled() + " kills " + kills3b + " text " + o3b.text()
                        + " - flips if the guard-refused life is ended or unsaid, or the launcher is skipped / not last");
        System.out.println("  PASS " + r3b);

        // (3c) the writer ended at step 3 is ALSO a receipt life with no exit row (the supervisor
        // died unrecorded); a process alive under that pid at step 6 is a recycled pid - said, never ended twice
        String r3c = "kill-order-no-recycle";
        Path stop3c = root.resolve("k3c").resolve("run").resolve("stop");
        List<Long> kills3c = new java.util.ArrayList<>();
        List<String> receipt3c = List.of("supervisor_pid=" + launcher, "life=1 pid=" + lifeRunning);
        ServerOpsKill.Outcome o3c = ServerOpsKill.killOrder(stop3c, lifeRunning, launcher,
                pid -> pid == lifeRunning || pid == launcher, kills3c::add, () -> receipt3c, ms -> { },
                1_000L, 2_000L);
        row(r3c, o3c.writerKilled() && o3c.livesKilled().isEmpty() && kills3c.equals(List.of(lifeRunning, launcher))
                && o3c.text().contains("pid " + lifeRunning + " was already ended at step 3"),
                "livesKilled " + o3c.livesKilled() + " kills " + kills3c + " text " + o3c.text()
                        + " - flips if step 6 re-ends the step-3 writer's pid (a recycled pid) or does not say so");
        System.out.println("  PASS " + r3c);

        // (4) nothing alive -> nothing written, the nothing-to-stop text; pids <= 0 are unknown
        String r4 = "kill-order-nothing";
        Fake f4 = new Fake(root.resolve("k4").resolve("run").resolve("stop"));
        ServerOpsKill.Outcome o4 = f4.order(writer, launcher, 1_000L, 8_000L);
        row(r4, o4.nothingToStop() && ServerOpsKill.NOTHING_TO_STOP.equals(o4.text()) && !o4.stopWritten(),
                "outcome " + o4 + " - flips if a dead writer + dead launcher still runs the order");
        row(r4, !Files.exists(f4.stop.getParent()) && f4.kills.isEmpty() && f4.reads == 0 && f4.waits.isEmpty(),
                "the refusal wrote / killed / polled - flips if step 1 is not FIRST");
        ServerOpsKill.Outcome o4b = ServerOpsKill.killOrder(f4.stop, 0L, -5L, pid -> true, pid -> { },
                List::of, ms -> { }, 1_000L, 8_000L);
        row(r4, o4b.nothingToStop() && !Files.exists(f4.stop.getParent()), "outcome " + o4b
                + " - flips if a pid <= 0 (0 / negative = unknown) is treated as alive");
        System.out.println("  PASS " + r4);

        // (5) the stop path under a FILE: said in the text, the order continues
        String r5 = "kill-order-stop-io-fail";
        Path blocker = root.resolve("k5").resolve("afile");
        setup(r5, () -> {
            Files.createDirectories(blocker.getParent());
            Files.writeString(blocker, "x", StandardCharsets.US_ASCII);
        });
        Fake f5 = new Fake(blocker.resolve("stop"), writer, launcher);
        f5.first = List.of("supervisor_pid=" + launcher, "life=1 pid=" + writer, "life=1 exit=1",
                "stopped=true lives=1");
        f5.exitsWithLater = launcher;
        f5.laterFrom = 1;
        f5.later = f5.first;
        ServerOpsKill.Outcome o5 = f5.order(writer, launcher, 1_000L, 8_000L);
        row(r5, !o5.stopWritten() && o5.text().startsWith("stop file not written (")
                && o5.text().contains("the server will relaunch"), "outcome " + o5
                + " - flips if a failed stop write is silent or reported as written");
        row(r5, f5.kills.equals(List.of(writer)) && o5.writerKilled() && Files.isRegularFile(blocker),
                "kills " + f5.kills + " - flips if the order stops at the failed write instead of continuing");
        System.out.println("  PASS " + r5);

        // (6) launcherPidOf: pointer -> flight receipt -> supervisor_pid, the start-instant guard
        String r6 = "launcher-pid-of";
        long self = ProcessHandle.current().pid();
        java.time.Instant selfStart = ProcessHandle.current().info().startInstant().orElse(null);
        setup(r6, () -> {
            if (selfStart == null) {
                throw new IllegalStateException("this JVM's start instant is unreadable");
            }
        });
        Path runNow = root.resolve("k6").resolve("run");
        Path runOld = root.resolve("k6-old").resolve("run");
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.Instant before = selfStart.isBefore(java.time.Instant.now()) ? selfStart : java.time.Instant.now();
        java.time.LocalDateTime hourAgo = java.time.LocalDateTime.ofInstant(
                before.minus(java.time.Duration.ofHours(1)), java.time.ZoneId.systemDefault());
        setup(r6, () -> {
            writeFlight(runNow, now, "supervisor_pid=" + self + "\r\nlife=1 pid=7\r\n");
            writeFlight(runOld, hourAgo, "supervisor_pid=" + self + "\r\n");
        });
        long got = ServerOpsKill.launcherPidOf(runNow.resolve(ServeLauncher.POINTER_FILE));
        row(r6, got == self, "got " + got + " want " + self
                + " - flips if the pointer -> receipt -> supervisor_pid read (CRLF rows) breaks");
        long old = ServerOpsKill.launcherPidOf(runOld.resolve(ServeLauncher.POINTER_FILE));
        row(r6, old == 0L, "got " + old + " for a flight stamped 1 h before this JVM started"
                + " - flips if the start-instant (pid-reuse) guard is lost");
        long none = ServerOpsKill.launcherPidOf(root.resolve("k6-none").resolve("run")
                .resolve(ServeLauncher.POINTER_FILE));
        row(r6, none == 0L, "got " + none + " - flips if a missing pointer yields a pid");
        System.out.println("  PASS " + r6);
    }

    /** {@code <run>/latest.txt} -> {@code flight-<stamp>} -> its receipt with {@code rows}. */
    private static Path writeFlight(Path run, java.time.LocalDateTime stamp, String rows) throws IOException {
        String flight = ServeLauncher.FLIGHT_PREFIX + stamp.format(ServeLauncher.FLIGHT_STAMP);
        Path dir = Files.createDirectories(run.resolve(flight));
        Files.writeString(run.resolve(ServeLauncher.POINTER_FILE), flight + "\n", StandardCharsets.US_ASCII);
        Files.writeString(dir.resolve(LaneFollow.RECEIPT_FILE), rows, StandardCharsets.US_ASCII);
        return dir.resolve(LaneFollow.RECEIPT_FILE);
    }

    /** {@code <java.home>/bin/java[.exe]} of this JVM (row 7's child below runs on it). */
    private static String javaExe() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    /**
     * Row (7) {@code kill-order-real-child} (the July {@code api-kill-gap} without its HTTP driver):
     * {@link ServerOpsKill#killPublicServe} itself over a temp {@code run/} - no live
     * writer (pid 0) + a pointer whose {@code supervisor_pid=} is DEAD answers
     * {@link ServerOpsKill#NOTHING_TO_STOP} and writes no stop file; then a spawned java child
     * (this JVM's runtime + classpath, {@code --child}: it never exits on its own inside the row)
     * passed as the live writer is stopped: the stop file appears under the temp {@code run/}, a
     * stand-in supervisor thread answers it with {@code stopped=true}, the child is gone. The ops
     * file itself is never written: the order derives {@code run/} from its path only.
     */
    private static void realChildRow(Path ops) throws Exception {
        String r7 = "kill-order-real-child";
        Path run = ops.getParent().getParent();
        Path stop = run.resolve(ServeLauncher.STOP_FILE);
        // A DEAD launcher pid that can never alias a live process: above every OS's pid range
        // (Windows pids < 2^26, Linux pid_max <= 2^22) yet inside a signed int32 (POSIX pid_t,
        // Windows DWORD), so no native truncation either. A recycled real pid here could make
        // the order end a stranger's process.
        long deadPid = 2_000_000_001L;
        setup(r7, () -> {
            writeFlight(run, java.time.LocalDateTime.now(), "supervisor_pid=" + deadPid + "\n");
        });
        ServerOpsKill.Outcome refused = ServerOpsKill.killPublicServe(ops, 0L, null);
        row(r7, refused.nothingToStop() && ServerOpsKill.NOTHING_TO_STOP.equals(refused.text())
                && !refused.stopWritten() && !Files.exists(stop), "outcome " + refused
                + " - flips if a dead launcher pid with no live writer still runs the order or the refusal is lost");

        Process child = null;
        Thread supervisor = null;
        try {
            Process[] spawned = {null};
            Path[] receipt = {null};
            setup(r7, () -> {
                spawned[0] = new ProcessBuilder(javaExe(), "-cp", System.getProperty("java.class.path"),
                        ServerOpsKillSelftest.class.getName(), "--child")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                long pid = spawned[0].pid();
                receipt[0] = writeFlight(run, java.time.LocalDateTime.now(),
                        "supervisor_pid=" + deadPid + "\nlife=1 pid=" + pid + "\n");
                if (!spawned[0].isAlive()) {
                    throw new IllegalStateException("the java child exited at once: " + spawned[0].exitValue());
                }
            });
            child = spawned[0];
            Path receiptFile = receipt[0];
            // the stand-in supervisor: answers the stop file with its terminal rows
            supervisor = new Thread(() -> {
                long until = System.currentTimeMillis() + 20_000L;
                try {
                    while (!Files.exists(stop) && System.currentTimeMillis() < until) {
                        Thread.sleep(20L);
                    }
                    if (Files.exists(stop)) {
                        Files.writeString(receiptFile, "life=1 exit=1\nstopped=true lives=1\n",
                                StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.APPEND);
                    }
                } catch (IOException | InterruptedException e) {
                    System.out.println("  kill-order-real-child stand-in supervisor ended early ("
                            + e.getClass().getSimpleName() + ")");
                }
            }, "cc-ops-kill-selftest-supervisor");
            supervisor.setDaemon(true);
            supervisor.start();
            long childPid = child.pid();
            ServerOpsKill.Outcome killed = ServerOpsKill.killPublicServe(ops, childPid, null);
            row(r7, !killed.nothingToStop() && killed.writerKilled()
                    && killed.text().contains("server pid " + childPid + " ended")
                    && "stopped=true lives=1".equals(killed.terminalRow()), "outcome " + killed
                    + " - flips if killPublicServe stops reaching the live writer or the receipt poll misses the stopped row");
            row(r7, Files.isRegularFile(stop), "no stop file under the temp run/ - flips if killPublicServe skips the stop"
                    + " file (or roots it anywhere but the ops file's run/)");
            boolean exited = child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            row(r7, exited && !child.isAlive(), "the live writer child is still alive - flips if the writer kill is lost");
            System.out.println("  PASS " + r7);
        } finally {
            if (child != null) {
                child.destroyForcibly();
            }
            if (supervisor != null) {
                supervisor.interrupt();
            }
        }
    }

    /** A DEAD supervisor pid that can never alias a live process (row 7's reasoning: above every OS's pid range,
     * inside a signed int32). */
    private static final long DEAD_PID = 2_000_000_001L;

    /**
     * The known-launcher rows - the boot window, where the shell's spawned launcher has no receipt yet and
     * {@code latest.txt} still names the previous flight - each over its own temp {@code run/}: the gated receipt
     * directly, then {@link ServerOpsKill#killPublicServe} over REAL {@code --child} JVMs (row (9) waits the real
     * 8 s bound: its gated receipt never yields a terminal row). Pids: the children's (&gt; 0) and {@link #DEAD_PID}.
     *
     *
     */
    private static void knownLauncherRows(Path root) throws Exception {
        oldLivesUnhandedRow(root.resolve("k8").resolve("run"));
        knownLauncherTerminalPointerRow(root.resolve("k9").resolve("run").resolve("ops").resolve("ops-status.txt"));
        livePointerWinsRow(root.resolve("k10").resolve("run").resolve("ops").resolve("ops-status.txt"));
    }

    /** Row (8): {@link ServerOpsKill.FlightReceipt} hands nothing until the pointer's receipt names the launcher, and
     * its life bound follows the CURRENT flight's stamp; no process is touched (get() only reads files). */
    private static void oldLivesUnhandedRow(Path run) {
        String r = "kill-order-old-lives-unhanded";
        Path pointer = run.resolve(ServeLauncher.POINTER_FILE);
        long launcher = 4_242L;
        java.time.LocalDateTime now = java.time.LocalDateTime.now().withNano(0);
        setup(r, () -> writeFlight(run, now.minusHours(1),
                "supervisor_pid=" + DEAD_PID + "\nlife=1 pid=7\nstopped=true lives=1\n"));
        ServerOpsKill.FlightReceipt receipt = new ServerOpsKill.FlightReceipt(pointer, launcher);
        List<String> old = receipt.get();
        row(r, old.isEmpty() && receipt.earliestLifeStartMs() == Long.MAX_VALUE, "handed " + old + " (life bound "
                + receipt.earliestLifeStartMs() + ") while the pointer's receipt names supervisor " + DEAD_PID + ", not "
                + launcher + " - flips if the gate is dropped (the old flight's stale stopped=true or its recycled life"
                + " pids reach the order)");
        setup(r, () -> writeFlight(run, now, "supervisor_pid=" + launcher + "\nlife=1 pid=8\n"));
        List<String> fresh = receipt.get();
        long want = now.atZone(java.time.ZoneId.systemDefault()).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
                - 1_000L;
        row(r, fresh.equals(List.of("supervisor_pid=" + launcher, "life=1 pid=8"))
                && receipt.earliestLifeStartMs() == want, "handed " + fresh + " life bound "
                + receipt.earliestLifeStartMs() + " want " + want + " - flips if the gate never opens once the receipt"
                + " names the launcher, or the life bound is not re-read from the CURRENT flight's stamp (the old"
                + " flight's is 1 h earlier)");
        List<String> orphan = new ServerOpsKill.FlightReceipt(pointer, 0L).get();
        row(r, orphan.size() == 2, "a no-launcher order was handed " + orphan + " - flips if the gate also swallows the"
                + " receipt of an orphaned serve (pid 0 = no launcher known)");
        System.out.println("  PASS " + r);
    }

    /** One {@code --child} JVM (sleeps {@link #CHILD_SLEEP_MS}); a child that exits at once is a staging failure. */
    private static Process spawnChild() throws IOException {
        Process p = new ProcessBuilder(javaExe(), "-cp", System.getProperty("java.class.path"),
                ServerOpsKillSelftest.class.getName(), "--child")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.isAlive()) {
            throw new IllegalStateException("the java child exited at once: " + p.exitValue());
        }
        return p;
    }

    private static void destroyAll(Process... children) {
        for (Process c : children) {
            if (c != null) {
                c.destroyForcibly();
            }
        }
    }

    /**
     * Row (9): the pointer names a DEAD supervisor whose receipt lists a LIVE life (a second child, spawned after
     * the flight stamp, so the life guard alone would pass it); the shell's known launcher (the first child) is
     * alive. The order writes the stop file, ends the known launcher LAST, and leaves the old receipt's life alone.
     */
    private static void knownLauncherTerminalPointerRow(Path ops) throws Exception {
        String r = "kill-order-known-launcher-terminal-pointer";
        Path run = ops.getParent().getParent();
        Path stop = run.resolve(ServeLauncher.STOP_FILE);
        Process[] kids = {null, null};
        try {
            setup(r, () -> {
                java.time.LocalDateTime stamp = java.time.LocalDateTime.now();
                kids[1] = spawnChild();
                writeFlight(run, stamp, "supervisor_pid=" + DEAD_PID + "\nlife=1 pid=" + kids[1].pid() + "\n");
                kids[0] = spawnChild();
            });
            Process launcher = kids[0];
            Process oldLife = kids[1];
            ServerOpsKill.Outcome o = ServerOpsKill.killPublicServe(ops, 0L, launcher);
            boolean ended = launcher.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            row(r, !o.nothingToStop() && o.stopWritten() && Files.isRegularFile(stop), "outcome " + o
                    + " - flips if the alive known launcher is ignored while the pointer names a dead supervisor"
                    + " (nothing to stop while the spawned launcher lives)");
            row(r, o.launcherKilled() && ended && o.text().contains("then ended launcher pid " + launcher.pid()),
                    "outcome " + o + ", known launcher ended=" + ended + " - flips if the order stops reaching the"
                            + " trusted known launcher");
            row(r, o.livesKilled().isEmpty() && oldLife.isAlive(), "outcome " + o + ", the old receipt's life alive="
                    + oldLife.isAlive() + " - flips if the previous flight's receipt lives are handed to the order"
                    + " (a recycled pid would be ended)");
            System.out.println("  PASS " + r);
        } finally {
            destroyAll(kids);
        }
    }

    /**
     * Row (10): the pointer names a LIVE supervisor (a second child) that is NOT the shell's known Process (the first
     * child): the pointer's launcher is the target. A stand-in answers the stop file with {@code stopped=true lives=0}
     * and ends that supervisor child (its launcher's exit); the known Process is never touched.
     */
    private static void livePointerWinsRow(Path ops) throws Exception {
        String r = "kill-order-live-pointer-wins";
        Path run = ops.getParent().getParent();
        Path stop = run.resolve(ServeLauncher.STOP_FILE);
        Process[] kids = {null, null};
        Path[] receipt = {null};
        Thread standIn = null;
        try {
            setup(r, () -> {
                kids[0] = spawnChild();
                kids[1] = spawnChild();
                receipt[0] = writeFlight(run, java.time.LocalDateTime.now(), "supervisor_pid=" + kids[1].pid() + "\n");
            });
            Process known = kids[0];
            Process supervisor = kids[1];
            Path receiptFile = receipt[0];
            standIn = new Thread(() -> {
                long until = System.currentTimeMillis() + 20_000L;
                try {
                    while (!Files.exists(stop) && System.currentTimeMillis() < until) {
                        Thread.sleep(20L);
                    }
                    if (Files.exists(stop)) {
                        Files.writeString(receiptFile, "stopped=true lives=0\n", StandardCharsets.US_ASCII,
                                java.nio.file.StandardOpenOption.APPEND);
                        supervisor.destroyForcibly();
                    }
                } catch (IOException | InterruptedException e) {
                    System.out.println("  " + r + " stand-in supervisor ended early (" + e.getClass().getSimpleName()
                            + ")");
                }
            }, "cc-ops-kill-selftest-live-pointer");
            standIn.setDaemon(true);
            standIn.start();
            ServerOpsKill.Outcome o = ServerOpsKill.killPublicServe(ops, 0L, known);
            row(r, "stopped=true lives=0".equals(o.terminalRow())
                    && o.text().contains("launcher pid " + supervisor.pid() + " exited"), "outcome " + o
                    + " - flips if the known Process outranks the pointer's live launcher (the trust order inverts: the"
                    + " gated receipt then hides the live flight's stopped row)");
            row(r, known.isAlive() && !o.launcherKilled(), "the known Process alive=" + known.isAlive() + ", outcome "
                    + o + " - flips if the order ends the shell's known Process while a live flight owns the run");
            System.out.println("  PASS " + r);
        } finally {
            destroyAll(kids);
            if (standIn != null) {
                standIn.interrupt();
            }
        }
    }

    /** Best-effort temp-tree removal; residue is reported once, never fatal. */
    private static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException residue) {
            System.out.println("server-ops-kill selftest: temp residue left at " + dir + " ("
                    + residue.getClass().getSimpleName() + ")");
        }
    }
}
