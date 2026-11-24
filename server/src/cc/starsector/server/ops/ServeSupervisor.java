package cc.starsector.server.ops;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import cc.starsector.server.platform.ActivationGate;

/**
 * The serve JVM's lives loop in Java — the pwsh supervisor script's law ported for the
 * release package: relaunch on a NONZERO exit after a delay, up to a budget; a
 * clean exit 0 stops; exit {@link ActivationGate#EXIT_REFUSED} (78, the game's activation or version
 * check said no) is FINAL after that life; the stop file {@code <pkg>/run/stop} stops (checked before
 * each launch, after each exit, and polled once a second while a life runs, so writing it
 * is a complete stop verb for a host or a service manager).
 *
 * <p><b>Receipt grammar, append-only, KEPT</b> so the Ops GUI's existing reader
 * ({@link LaneFollow#parseLifeFailure}, which parses only {@code life=N exit=} and
 * {@code relaunch_budget_exhausted=}) reads a Java-written receipt unchanged:
 * {@code supervisor_pid=<pid>} (first), then on a packed start that swept old flight folders
 * {@code retention kept=<k> removed=<n> failed=<f> freed_mib=<m>} ({@link ServeRetention.Result#receiptRow}, handed in
 * through {@link #rowAfterPid}), per life {@code life=N pid=<pid>},
 * {@code life=N timer_optout=<rc>} ({@link TimerOptOut}), {@code life=N exit=<code>}, then on a failed life (not
 * 0, not 78, not stopped mid-life) {@code life=N budget_refunded=true ran_s=<whole seconds>} (it ran at least the
 * refund threshold and no stop is seen, below) and {@code life=N out_of_memory=true} (below); terminal
 * {@code clean_exit=true lives=N} (0), {@code stopped=true lives=N} (0),
 * {@code refused_exit=true lives=N} (78), {@code relaunch_budget_exhausted=true lives=N} (1).
 * A life whose process could not be started counts as {@code life=N exit=-1} (the loop never dies
 * on a start failure). Every row append retries 8 x 25 ms and then warns once and goes on: a
 * reader holding the receipt once killed the pwsh supervisor mid-serve.
 *
 *
 * <p><b>The {@code timer_optout} row lands when the applier answers</b>, not in a fixed slot: the
 * production applier answers from its own thread up to {@link TimerOptOut#HELPER_TIMEOUT_SECONDS}
 * later, so the row follows {@code life=N pid=} and may follow {@code life=N exit=} or even the
 * terminal row of a short life (and a launcher JVM that exits first leaves none). Order is free
 * because every reader matches rows by prefix and skips the rest ({@link LaneFollow#parseLifeFailure},
 * {@link LaneFollow#parseTerminalRow}, {@code ServerOpsKill.terminalRowOf}, the suite's
 * {@code awaitLife}); two writers never split a row ({@link #appendRow}).
 *
 *
 * <p>On BOTH terminal failures (78 and the exhausted budget) the child's last non-blank STDERR
 * line goes to the console FIRST (the activation refusal is {@code System.err} then exit 78;
 * stdout's last line is posture noise), then one plain line naming the code and the err-log path.
 *
 * <p><b>The budget refunds after a long life.</b> A life that ran at least {@code refundAfterMillis}
 * ({@link ServeLauncher#RELAUNCH_REFUND_AFTER_MS} = 10 minutes in production; the clock runs from the started
 * process to the wait's return) and then failed sets the relaunch count back to 0 BEFORE the budget check and
 * writes {@code life=N budget_refunded=true ran_s=<s>} right after its {@code exit=} row. WHY: the budget stops a
 * crash LOOP; a serve that reaches its heap cap dies hours apart, and without the refund its six starts ran out
 * over a few days and the server stayed down. A life that could not be started ({@code exit=-1}), a stop, exit 0
 * and exit 78 never refund.
 *
 * <p><b>An out-of-memory exit is told.</b> Every serve JVM runs with {@code -XX:+ExitOnOutOfMemoryError}, so a
 * heap that hits {@code -Xmx} ends the process: HotSpot prints {@link #OOM_LINE_PREFIX} at the start of a stdout
 * line and exits {@link #EXIT_OUT_OF_MEMORY}. When a failed life's code is 3 AND the bounded tail of its
 * {@code out.log} or {@code err.log} has a line starting with that literal ({@link #tailHasLineStarting}; a
 * boolean, the log bytes are never echoed: the serve's logs can hold player text), the receipt gets
 * {@code life=N out_of_memory=true} and the console ONE fixed line naming {@code heap.max} in
 * {@code launcher.properties} (the row the launcher reads into {@code -Xmx}); when that life ends the budget, the
 * terminal line names it too, so the host does not read code 3 as the launcher's own "a file is missing or
 * changed".
 *
 *
 *
 */
public final class ServeSupervisor {

    /**
     * Bound of {@link #lastNonBlankLine}: the printable line length (the {@code LaneFollow.firstExceptionLine} twin).
     * 320, not 240: the key gate's no-key refusal line is 253 chars and 240 cut its "server again." tail
     * (the host reads that line to learn what to do). 512, not 320: the
     * Windows reserved-port warning ({@code WindowsPortReservation.warning}) is about 350 chars before the
     * probe's own prefix, so 320 cut its admin remedy on BOTH host surfaces (the ops GUI's last line and the
     * console's first exception line, which share this bound); the longest such line is pinned under it by
     * the port-reservation-warning-fits-host-line selftest row.
     */
    static final int LAST_LINE_MAX_CHARS = 512;
    /** Bound of {@link #lastNonBlankLine}: bytes read from the END of the file. */
    static final int LAST_LINE_MAX_BYTES = 64 * 1024;
    /** Grace between {@code destroy()} and {@code destroyForcibly()} on a stop. */
    static final long STOP_GRACE_MILLIS = 5_000L;
    /**
     * The start of the line HotSpot prints on stdout as {@code -XX:+ExitOnOutOfMemoryError} ends the JVM
     * ({@code Terminating due to java.lang.OutOfMemoryError: Java heap space}, then exit 3). Matched at a line start
     * only, as a fixed literal: a chat or log line that merely quotes it mid-line is no out-of-memory exit.
     *
     *
     */
    static final String OOM_LINE_PREFIX = "Terminating due to java.lang.OutOfMemoryError";
    /** HotSpot's exit code after {@link #OOM_LINE_PREFIX} (the JVM's own, so it says nothing about the launcher's 3). */
    static final int EXIT_OUT_OF_MEMORY = 3;
    private static final int RECEIPT_TRIES = 8;
    private static final long RECEIPT_RETRY_MILLIS = 25L;

    private final Path flightDir;
    private final Path stopFile;
    private final Path receipt;
    private final Path workDir;
    private final List<String> argv;
    private final Map<String, String> env;
    private final int maxRelaunches;
    private final long relaunchDelayMillis;
    private final PrintStream console;
    private final int bindPort;
    private final TimerOptOut.Applier applier;
    private final long refundAfterMillis;

    private final Object wake = new Object();
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile boolean stopRequested;
    private volatile boolean started;
    /** {@link #rowAfterPid}'s row; null = none. */
    private volatile String leadRow;

    /**
     * @param flightDir this launch's flight dir (its {@code lives/} holds each life's
     *     {@code life-N.out.log} / {@code .err.log})
     * @param stopFile the launcher-scoped stop signal ({@code <pkg>/run/stop})
     * @param receipt the append-only receipt ({@code <flight>/wrapper-receipt.txt})
     * @param workDir every life's working directory (the game folder's real path)
     * @param argv the substituted command line, executable first
     * @param env the child's COMPLETE environment ({@link ServeLauncher#childEnv})
     * @param maxRelaunches relaunches after life 1 (so {@code maxRelaunches + 1} lives at most; >= 0)
     * @param relaunchDelayMillis pause before each relaunch (the launcher passes 2000; >= 0)
     * @param console where the host-facing lines go
     */
    public ServeSupervisor(Path flightDir, Path stopFile, Path receipt, Path workDir, List<String> argv,
            Map<String, String> env, int maxRelaunches, long relaunchDelayMillis, PrintStream console) {
        this(flightDir, stopFile, receipt, workDir, argv, env, maxRelaunches, relaunchDelayMillis, console, 0);
    }

    /**
     * @param bindPort the serve's fixed {@code server.bindPort}, named on the per-life console line
     *     (without it a clean serve showed a host NOTHING); {@code <= 0} = not named (a direct supervisor without a config)
     */
    public ServeSupervisor(Path flightDir, Path stopFile, Path receipt, Path workDir, List<String> argv,
            Map<String, String> env, int maxRelaunches, long relaunchDelayMillis, PrintStream console, int bindPort) {
        this(flightDir, stopFile, receipt, workDir, argv, env, maxRelaunches, relaunchDelayMillis, console, bindPort,
                TimerOptOut.PRODUCTION);
    }

    /**
     * @param applier the per-life timer opt-out ({@link TimerOptOut#PRODUCTION} through the other ctors; a test
     *     injects {@link TimerOptOut#notAttempted()} or a stub); called once per started life, never waited on
     *
     */
    public ServeSupervisor(Path flightDir, Path stopFile, Path receipt, Path workDir, List<String> argv,
            Map<String, String> env, int maxRelaunches, long relaunchDelayMillis, PrintStream console, int bindPort,
            TimerOptOut.Applier applier) {
        this(flightDir, stopFile, receipt, workDir, argv, env, maxRelaunches, relaunchDelayMillis, console, bindPort,
                applier, ServeLauncher.RELAUNCH_REFUND_AFTER_MS);
    }

    /**
     * @param refundAfterMillis a life that ran at least this long and then failed refunds the relaunch budget
     *     (>= 1; {@link ServeLauncher#RELAUNCH_REFUND_AFTER_MS} through every other ctor and the launcher; a row
     *     passes a short one so its lives stay under seconds)
     *
     */
    public ServeSupervisor(Path flightDir, Path stopFile, Path receipt, Path workDir, List<String> argv,
            Map<String, String> env, int maxRelaunches, long relaunchDelayMillis, PrintStream console, int bindPort,
            TimerOptOut.Applier applier, long refundAfterMillis) {
        if (argv.isEmpty() || maxRelaunches < 0 || relaunchDelayMillis < 0 || applier == null
                || refundAfterMillis < 1) {
            throw new IllegalArgumentException("supervisor needs a command, maxRelaunches >= 0, delay >= 0, an applier,"
                    + " refundAfterMillis >= 1");
        }
        this.flightDir = flightDir;
        this.stopFile = stopFile;
        this.receipt = receipt;
        this.workDir = workDir;
        this.argv = List.copyOf(argv);
        this.env = Map.copyOf(env);
        this.maxRelaunches = maxRelaunches;
        this.relaunchDelayMillis = relaunchDelayMillis;
        this.console = console;
        this.bindPort = bindPort;
        this.applier = applier;
        this.refundAfterMillis = refundAfterMillis;
    }

    /** Runs the lives loop to its terminal row; returns 0 (clean or stopped), 1 (budget) or 78 (refused). */
    public int run() {
        started = true;
        try {
            return loop();
        } finally {
            finished.countDown();
        }
    }

    /**
     * A launcher row appended right after {@code supervisor_pid=} and before any life row (the retention receipt);
     * called before {@link #run}, at most once (a later call replaces the row). WHY here and not in the launcher: a
     * row the launcher appended before {@link #run} would displace {@code supervisor_pid=} from the receipt's first
     * row, which the grammar above promises and a reader takes positionally.
     *
     *
     */
    public void rowAfterPid(String row) {
        if (started) {
            throw new IllegalStateException("rowAfterPid after run()");
        }
        leadRow = row;
    }

    private int loop() {
        appendRow(receipt, "supervisor_pid=" + ProcessHandle.current().pid(), console);
        String lead = leadRow;
        if (lead != null) {
            appendRow(receipt, lead, console);
        }
        Path lives = flightDir.resolve("lives");
        int life = 0;
        int relaunches = 0;
        while (true) {
            if (stopSignalled()) {
                terminalRow("stopped=true lives=" + life);
                return 0;
            }
            life++;
            Path errLog = lives.resolve("life-" + life + ".err.log");
            Path outLog = lives.resolve("life-" + life + ".out.log");
            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(workDir.toFile())
                    .redirectOutput(outLog.toFile())
                    .redirectError(errLog.toFile());
            pb.environment().clear();
            pb.environment().putAll(env);
            int code;
            boolean stoppedHere = false;
            Process p;
            long startedNanos = 0L;
            long ranNanos = -1L;
            try {
                p = pb.start();
                startedNanos = System.nanoTime();
            } catch (IOException | RuntimeException startFailed) {
                ServeLauncher.say(console,"serve-launcher: the server process could not be started ("
                        + startFailed.getClass().getSimpleName() + ")");
                p = null;
            }
            if (p == null) {
                code = -1;
            } else {
                appendRow(receipt, "life=" + life + " pid=" + p.pid(), console);
                // The ONE host-facing line of a healthy life (the serve's own output goes to the log)
                ServeLauncher.say(console, "serve-launcher: life " + life + " started (pid " + p.pid()
                        + (bindPort > 0 ? ", port " + bindPort : "") + "), output in " + outLog);
                // The row lands whenever the applier answers (maybe after exit=); never waited on
                final int lifeNo = life;
                applier.apply(p, rc -> appendRow(receipt, "life=" + lifeNo + " timer_optout=" + rc, console));
                stoppedHere = awaitOrStop(p);
                ranNanos = System.nanoTime() - startedNanos;
                code = p.exitValue();
            }
            appendRow(receipt, "life=" + life + " exit=" + code, console);
            if (stoppedHere) {
                terminalRow("stopped=true lives=" + life);
                return 0;
            }
            if (code == 0) {
                terminalRow("clean_exit=true lives=" + life);
                return 0;
            }
            if (code == ActivationGate.EXIT_REFUSED) {
                // The gate's own refusal line (activation, or a game version / build this server build does not
                // support) is the err log's last line, printed just above this sentence; naming only activation here
                // sent a host with the wrong game version to re-enter their key.
                reportTerminalFailure(errLog, code, "the game's activation or version check refused"
                        + " to start it (the line above says which), so it is not restarted");
                appendRow(receipt, "refused_exit=true lives=" + life, console);
                return ActivationGate.EXIT_REFUSED;
            }
            // The refund and the out-of-memory tell (class javadoc). Both
            // rows follow this life's exit= row; a stop, exit 0 and exit 78 returned above, and a stop seen now
            // refunds nothing and restarts nothing.
            boolean stopNow = stopSignalled();
            if (!stopNow && ranNanos >= TimeUnit.MILLISECONDS.toNanos(refundAfterMillis)) {
                relaunches = 0;
                appendRow(receipt, "life=" + life + " budget_refunded=true ran_s="
                        + TimeUnit.NANOSECONDS.toSeconds(ranNanos), console);
            }
            boolean exhausted = relaunches >= maxRelaunches;
            boolean outOfMemory = code == EXIT_OUT_OF_MEMORY
                    && (tailHasLineStarting(outLog, OOM_LINE_PREFIX) || tailHasLineStarting(errLog, OOM_LINE_PREFIX));
            if (outOfMemory) {
                appendRow(receipt, "life=" + life + " out_of_memory=true", console);
                ServeLauncher.say(console, "serve-launcher: the server ran out of memory (life " + life
                        + "). To give it more, raise heap.max in launcher.properties"
                        + (stopNow || exhausted ? "." : "; it is started again with the same limit."));
            }
            if (stopNow) {
                terminalRow("stopped=true lives=" + life);
                return 0;
            }
            if (exhausted) {
                reportTerminalFailure(errLog, code, "the restart limit of " + (maxRelaunches + 1)
                        + " starts is used up" + (outOfMemory
                        ? "; the server ran out of memory; raise heap.max in launcher.properties" : ""));
                appendRow(receipt, "relaunch_budget_exhausted=true lives=" + life, console);
                return 1;
            }
            relaunches++;
            pauseBeforeRelaunch();
        }
    }

    /**
     * Waits for {@code p}, polling the stop signal once a second; on a stop: {@code destroy()},
     * up to {@link #STOP_GRACE_MILLIS}, then {@code destroyForcibly()}. Returns whether it stopped the life.
     */
    private boolean awaitOrStop(Process p) {
        while (true) {
            try {
                if (p.waitFor(1, TimeUnit.SECONDS)) {
                    return false;
                }
                if (!stopSignalled()) {
                    continue;
                }
                p.destroy();
                if (!p.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    p.waitFor();
                }
                return true;
            } catch (InterruptedException interrupted) {
                ServeLauncher.say(console,"serve-launcher: interrupted; stopping the server");
                stopRequested = true;
                Thread.interrupted();
            }
        }
    }

    private void pauseBeforeRelaunch() {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(relaunchDelayMillis);
        synchronized (wake) {
            long left;
            while (!stopRequested && (left = until - System.nanoTime()) > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(wake, left);
                } catch (InterruptedException interrupted) {
                    ServeLauncher.say(console,"serve-launcher: interrupted; stopping the server");
                    stopRequested = true;
                }
            }
        }
    }

    private boolean stopSignalled() {
        return stopRequested || Files.exists(stopFile);
    }

    /**
     * The shutdown hook's verb (Ctrl-C, a service stop, the console closing): writes the stop file,
     * wakes a pending relaunch delay, and waits until the loop has stopped the running life (the
     * loop's poll sees the request within a second, then destroy / grace / force) and written its
     * {@code stopped=true} row — so the JVM does not halt under a half-written receipt. A no-op
     * once the loop has finished; returns at once when the loop never started.
     */
    public void requestStop() {
        if (finished.getCount() == 0) {
            return;
        }
        stopRequested = true;
        try {
            Files.write(stopFile, new byte[0]);
        } catch (IOException | RuntimeException e) {
            ServeLauncher.say(console,"serve-launcher: could not write the stop file " + stopFile + " ("
                    + e.getClass().getSimpleName() + "); stopping anyway");
        }
        synchronized (wake) {
            wake.notifyAll();
        }
        if (!started) {
            return;
        }
        try {
            if (!finished.await(STOP_GRACE_MILLIS + 3_000L, TimeUnit.MILLISECONDS)) {
                ServeLauncher.say(console,"serve-launcher: the server did not stop in time");
            }
        } catch (InterruptedException interrupted) {
            ServeLauncher.say(console,"serve-launcher: interrupted while stopping the server");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A quiet terminal row ({@code stopped=} / {@code clean_exit=}) is also echoed on the console (so
     * the host learns the serve ended and why); the two loud ones ride {@link #reportTerminalFailure}.
     */
    private void terminalRow(String row) {
        appendRow(receipt, row, console);
        ServeLauncher.say(console, "serve-launcher: " + row);
    }

    /** The child's last stderr line FIRST, then one plain line naming the code and the err log. */
    private void reportTerminalFailure(Path errLog, int code, String why) {
        String last = lastNonBlankLine(errLog);
        if (!last.isEmpty()) {
            ServeLauncher.say(console,last);
        }
        ServeLauncher.say(console,"serve-launcher: the server exited with code " + code + ": " + why
                + ". Error output: " + errLog);
    }

    /**
     * The last non-blank line of {@code file} as ONE printable-ASCII line (other chars become
     * {@code ?}), at most {@link #LAST_LINE_MAX_CHARS} chars, reading at most the file's final
     * {@link #LAST_LINE_MAX_BYTES} bytes; missing / unreadable / all blank = {@code ""}.
     */
    public static String lastNonBlankLine(Path file) {
        Tail read = readTail(file);
        if (read == null) {
            return "";
        }
        String tail = new String(read.bytes(), StandardCharsets.UTF_8);
        String[] lines = tail.split("\r?\n|\r");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < t.length() && sb.length() < LAST_LINE_MAX_CHARS; k++) {
                char c = t.charAt(k);
                sb.append(c >= 0x20 && c < 0x7F ? c : '?');
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * Whether a WHOLE line in the final {@link #LAST_LINE_MAX_BYTES} bytes of {@code file} starts with {@code prefix}
     * (an ASCII literal, compared byte for byte; no trim, so a line that only quotes it after other text never
     * matches). When the window starts mid-file its first, cut line is skipped. Missing / unreadable = false. Returns
     * a boolean only: no byte of the file reaches a console or a receipt.
     *
     *
     */
    static boolean tailHasLineStarting(Path file, String prefix) {
        Tail t = readTail(file);
        if (t == null) {
            return false;
        }
        // ISO-8859-1 maps each byte to one char, so an ASCII prefix matches only its own bytes (never a decoded
        // multi-byte sequence)
        String[] lines = new String(t.bytes(), StandardCharsets.ISO_8859_1).split("\r?\n|\r", -1);
        for (int i = t.fromFileStart() ? 0 : 1; i < lines.length; i++) {
            if (lines[i].startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The final bytes of a file and whether they begin at its first byte (else the first line is cut). */
    private record Tail(byte[] bytes, boolean fromFileStart) {
    }

    /**
     * Reads at most the final {@link #LAST_LINE_MAX_BYTES} bytes of {@code file}; null = missing / unreadable.
     *
     */
    private static Tail readTail(Path file) {
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = ch.size();
            int n = (int) Math.min(size, LAST_LINE_MAX_BYTES);
            ch.position(size - n);
            ByteBuffer buf = ByteBuffer.allocate(n);
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // fill
            }
            return new Tail(Arrays.copyOf(buf.array(), buf.position()), size <= n);
        } catch (IOException | RuntimeException missing) {
            return null;
        }
    }

    /**
     * Appends ONE row (+ the platform line separator, UTF-8) with {@code APPEND, CREATE}; on an
     * {@link IOException} retries {@value #RECEIPT_TRIES} x {@value #RECEIPT_RETRY_MILLIS} ms, then
     * prints ONE warning and returns — never throws (the receipt is the assertion surface, the
     * lives loop is the product). Also used by {@link ServeLauncher} for its pre-launch refusal row.
     * Thread-safe per row: the lives loop and the {@link TimerOptOut} thread may append at once, and
     * each row is ONE {@code Files.write(APPEND)} of a few dozen bytes (an append-mode open writes at
     * the end of file, one write call per row), so two writers never interleave inside a row.
     */
    static void appendRow(Path receipt, String row, PrintStream console) {
        byte[] bytes = (row + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < RECEIPT_TRIES; attempt++) {
            try {
                Files.write(receipt, bytes, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
                return;
            } catch (IOException busy) {
                try {
                    Thread.sleep(RECEIPT_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        ServeLauncher.say(console,"serve-launcher warning: a receipt row was not written to " + receipt);
    }
}
