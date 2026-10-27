package cc.starsector.server.ops;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * {@code supervisor_pid=<pid>} (first), per life {@code life=N pid=<pid>},
 * {@code life=N timer_optout=<rc>} ({@link TimerOptOut}), {@code life=N exit=<code>}; terminal
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
 *
 */
public final class ServeSupervisor {

    /**
     * Bound of {@link #lastNonBlankLine}: the printable line length (the {@code LaneFollow.firstExceptionLine} twin).
     * 320, not 240: the key gate's no-key refusal line is 253 chars and 240 cut its "server again." tail
     * (the host reads that line to learn what to do).
     */
    static final int LAST_LINE_MAX_CHARS = 320;
    /** Bound of {@link #lastNonBlankLine}: bytes read from the END of the file. */
    static final int LAST_LINE_MAX_BYTES = 64 * 1024;
    /** Grace between {@code destroy()} and {@code destroyForcibly()} on a stop. */
    static final long STOP_GRACE_MILLIS = 5_000L;
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

    private final Object wake = new Object();
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile boolean stopRequested;
    private volatile boolean started;

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
        if (argv.isEmpty() || maxRelaunches < 0 || relaunchDelayMillis < 0 || applier == null) {
            throw new IllegalArgumentException("supervisor needs a command, maxRelaunches >= 0, delay >= 0, an applier");
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

    private int loop() {
        appendRow(receipt, "supervisor_pid=" + ProcessHandle.current().pid(), console);
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
            try {
                p = pb.start();
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
            if (stopSignalled()) {
                terminalRow("stopped=true lives=" + life);
                return 0;
            }
            if (relaunches >= maxRelaunches) {
                reportTerminalFailure(errLog, code, "the restart limit of " + (maxRelaunches + 1)
                        + " starts is used up");
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
        String tail;
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = ch.size();
            int n = (int) Math.min(size, LAST_LINE_MAX_BYTES);
            ch.position(size - n);
            ByteBuffer buf = ByteBuffer.allocate(n);
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // fill
            }
            tail = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException missing) {
            return "";
        }
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
