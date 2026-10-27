package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ops console's Kill = the STOP FILE FIRST, one order ({@link OpsConsole}'s
 * {@code POST /api/kill}). WHY: the public serve runs under
 * {@link ServeLauncher}'s {@link ServeSupervisor}, which RELAUNCHES a life that dies; a bare
 * {@code destroyForcibly} of the writer pid (the old Kill) just starts life N+1. The stop
 * file is the supervisor's own verb (it polls it each second and before every relaunch,
 * {@code ServeSupervisor.loop} / {@code awaitOrStop}), so it is written BEFORE anything is
 * ended; the launcher is ended only as the LAST resort and only after every running life it
 * started (a Windows {@code destroy()} is {@code TerminateProcess}: the launcher's shutdown hook
 * never runs, so a launcher ended first would orphan its serve).
 *
 * <p>{@link #killOrder} is PURE (every effect through its lambdas) and pinned by
 * {@link ServerOpsKillSelftest}'s {@code kill-order-*} rows; {@link #killPublicServe} is the real
 * wiring the console calls. The call is BLOCKING (≤ 2 × the 8 s bound): the web answers when it
 * returns.
 *
 * <p>The gap between lives (writer dead, launcher alive) is killable;
 * the writer kill is skipped there.
 *
 */
public final class ServerOpsKill {
    private ServerOpsKill() {
    }

    /** The one outcome both shells show. {@code terminalRow} = the supervisor's terminal receipt
     * row seen within the bound ({@code null} = none); {@code livesKilled} = the receipt's
     * still-running life pids this order ended (only when no terminal row came); {@code text} =
     * the one ASCII operator sentence. */
    public record Outcome(boolean stopWritten, boolean writerKilled, String terminalRow,
            List<Long> livesKilled, boolean launcherKilled, String text) {
        /** True only for step 1's refusal (neither the writer nor the launcher alive): nothing
         * was written or ended — the web keeps its "Kill refused" answer for exactly this. */
        public boolean nothingToStop() {
            return NOTHING_TO_STOP.equals(text);
        }
    }

    /** The poll wait (a test fake records instead of sleeping). */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /** Step 1's refusal text (the web's refusal keys on it through {@link Outcome#nothingToStop}). */
    public static final String NOTHING_TO_STOP = "nothing to stop: no live server or launcher";
    /** The real poll cadence = {@code ServeSupervisor}'s own 1 s stop-file poll. */
    static final long POLL_MS = 1_000L;
    /** The real bound: the supervisor's 1 s poll + its 2 s relaunch pause
     * ({@link ServeLauncher#RELAUNCH_DELAY_MS}) + slack for its receipt write and exit. */
    static final long BOUND_MS = 8_000L;
    /** A launcher started this long after its flight's stamp still counts as that flight's
     * (the stamp is second-truncated and taken after the lock); later = a recycled pid. */
    static final long LAUNCHER_START_SLACK_MS = 60_000L;

    /** The supervisor's terminal receipt rows ({@code ServeSupervisor.loop}). */
    static final Pattern TERMINAL_ROW =
            Pattern.compile("^(stopped|clean_exit|refused_exit|relaunch_budget_exhausted)=true( lives=\\d+)?$");
    private static final Pattern LIFE_PID_ROW = Pattern.compile("^life=(\\d{1,9}) pid=(\\d{1,18})$");
    private static final Pattern LIFE_EXIT_ROW = Pattern.compile("^life=(\\d{1,9}) exit=");
    private static final Pattern SUPERVISOR_PID_ROW = Pattern.compile("^supervisor_pid=(\\d{1,18})$");
    /** The pointer's flight-dir shape = {@link ServeLauncher#FLIGHT_PREFIX} +
     * {@link ServeLauncher#FLIGHT_STAMP} (= {@code LaneFollow}'s private {@code FLIGHT_DIR} twin);
     * group 1 = the local-time stamp. Never an arbitrary path. */
    private static final Pattern FLIGHT_DIR =
            Pattern.compile(Pattern.quote(ServeLauncher.FLIGHT_PREFIX) + "([0-9]{8}-[0-9]{6})");

    /**
     * The order, pure: (1) proceed only if the writer OR the launcher
     * is alive (a pid ≤ 0 = unknown = not alive), else {@link #NOTHING_TO_STOP}; (2) write the
     * EMPTY {@code stopFile} (its parent created; a failure is SAID — the server will relaunch —
     * and the order continues); (3) end the writer only when it is alive (skipped in the gap
     * between lives); (4) poll {@code receiptLines} every {@code pollMs} up to {@code boundMs}
     * for a terminal row; (5) with one, confirm the launcher exits (the same bound, once);
     * (6) without one, end every receipt life with a {@code pid=} row and no {@code exit=} row
     * that is still alive AND {@code lifeKillable} (a live pid the predicate refuses is SAID and
     * left alone), THEN the launcher — the launcher LAST, never first; (7) the stop file
     * is left in place (the next launcher claims it after its lock: an order when written after
     * that launcher started, else deleted as stale). An interrupt during a
     * wait ends the order where it stands (no escalation) and re-asserts the interrupt flag.
     *
     * @param writerPid   the snapshot writer's pid, &gt; 0; 0 = unknown / not live
     * @param launcherPid the launcher (supervisor) pid, &gt; 0; 0 = unknown
     * @param lifeKillable a receipt life pid that is really THIS flight's process (the real wiring's
     *                    start-time guard); the 9-arg shape passes {@code alive}
     * @param pollMs      &gt; 0
     * @param boundMs     ≥ 0
     */
    public static Outcome killOrder(Path stopFile, long writerPid, long launcherPid,
            LongPredicate alive, LongConsumer kill, Supplier<List<String>> receiptLines,
            Sleeper sleeper, long pollMs, long boundMs) {
        return killOrder(stopFile, writerPid, launcherPid, alive, alive, kill, receiptLines, sleeper, pollMs,
                boundMs);
    }

    /** {@link #killOrder(Path, long, long, LongPredicate, LongConsumer, Supplier, Sleeper, long, long)} with
     * step (6)'s {@code lifeKillable} guard explicit. */
    public static Outcome killOrder(Path stopFile, long writerPid, long launcherPid,
            LongPredicate alive, LongPredicate lifeKillable, LongConsumer kill, Supplier<List<String>> receiptLines,
            Sleeper sleeper, long pollMs, long boundMs) {
        Objects.requireNonNull(stopFile, "stopFile");
        if (pollMs <= 0 || boundMs < 0) {
            throw new IllegalArgumentException("killOrder needs pollMs > 0 and boundMs >= 0");
        }
        boolean writerUp = writerPid > 0 && alive.test(writerPid);
        boolean launcherUp = launcherPid > 0 && alive.test(launcherPid);
        if (!writerUp && !launcherUp) {
            return new Outcome(false, false, null, List.of(), false, NOTHING_TO_STOP);
        }
        // (2) the stop file FIRST — the supervisor's own verb, before anything is ended
        boolean stopWritten;
        String stopFailure = null;
        try {
            Path parent = stopFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(stopFile, new byte[0]);
            stopWritten = true;
        } catch (IOException | RuntimeException e) {
            stopWritten = false;
            stopFailure = e.getClass().getSimpleName();
        }
        StringBuilder text = new StringBuilder(stopWritten ? "stop file written"
                : "stop file not written (" + stopFailure + "): the server will relaunch");
        // (3) the writer, only when alive (the gap between lives has none)
        if (writerUp) {
            kill.accept(writerPid);
            text.append("; server pid ").append(writerPid).append(" ended");
        } else {
            text.append("; no live server process (between restarts)");
        }
        // (4) the supervisor's terminal row within the bound
        String terminal = null;
        boolean interrupted = false;
        try {
            terminal = terminalRowOf(receiptLines.get());
            for (long waited = 0; terminal == null && waited < boundMs; waited += pollMs) {
                sleeper.sleep(pollMs);
                terminal = terminalRowOf(receiptLines.get());
            }
            // (5) with a terminal row: the launcher exits once its run() returns
            if (terminal != null && launcherPid > 0) {
                for (long waited = 0; alive.test(launcherPid) && waited < boundMs; waited += pollMs) {
                    sleeper.sleep(pollMs);
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            Thread.currentThread().interrupt();
        }
        if (interrupted) {
            text.append("; interrupted while waiting for the launcher's receipt (stop file left in place)");
            return new Outcome(stopWritten, writerUp, terminal, List.of(), false, text.toString());
        }
        if (terminal != null) {
            text.append("; launcher receipt: ").append(terminal);
            if (launcherPid > 0) {
                text.append(alive.test(launcherPid)
                        ? "; launcher pid " + launcherPid + " still running after " + (boundMs / 1000) + " s"
                        : "; launcher pid " + launcherPid + " exited");
            }
            return new Outcome(stopWritten, writerUp, terminal, List.of(), false, text.toString());
        }
        // (6) no terminal row: every still-running life first, THEN the launcher (never first)
        text.append("; no stop receipt within ").append(boundMs / 1000).append(" s");
        List<Long> livesKilled = new ArrayList<>();
        for (long pid : runningLifePids(receiptLines.get())) {
            if (pid <= 0 || !alive.test(pid)) {
                continue;
            }
            if (writerUp && pid == writerPid) {
                // Step (3) already ended this pid; a process alive under it ~8 s later is a
                // recycled pid (the dead supervisor never wrote the exit row) - never ended twice
                text.append("; server life pid ").append(pid).append(" was already ended at step 3 (not re-ended)");
                continue;
            }
            if (!lifeKillable.test(pid)) {
                // a recycled pid: the receipt's life died unrecorded and another process wears its number
                text.append("; server life pid ").append(pid).append(" left alone (not this flight's process)");
                continue;
            }
            kill.accept(pid);
            livesKilled.add(pid);
            text.append("; ended server life pid ").append(pid);
        }
        boolean launcherKilled = false;
        if (launcherPid > 0 && alive.test(launcherPid)) {
            kill.accept(launcherPid);
            launcherKilled = true;
            text.append("; then ended launcher pid ").append(launcherPid);
        } else {
            text.append(launcherPid > 0 ? "; launcher pid " + launcherPid + " already gone"
                    : "; no launcher pid known");
        }
        return new Outcome(stopWritten, writerUp, null, List.copyOf(livesKilled), launcherKilled,
                text.toString());
    }

    /**
     * The flight pointer's launcher pid, 0 = none: {@code latest.txt}'s first line names
     * {@code flight-yyyyMMdd-HHmmss} → its {@code wrapper-receipt.txt} → the first
     * {@code supervisor_pid=N} row (the supervisor's own first row) → N when that process is
     * alive AND started no later than the flight stamp (local time) + {@link
     * #LAUNCHER_START_SLACK_MS} — the pid-reuse guard ({@code ServerOpsCore.writerAlive}'s idea:
     * the launcher starts before it creates its flight). An unreadable start instant = alive. In
     * a DST fold the LATER offset is taken (the permissive side). Bounded reads, missing /
     * malformed anything = 0.
     */
    public static long launcherPidOf(Path flightPointer) {
        long pid = supervisorPidOf(flightPointer);
        if (pid <= 0) {
            return 0L;
        }
        Instant stamp = flightStampOf(flightPointer);
        if (stamp == null) {
            return 0L;
        }
        long latestStartMs = stamp.toEpochMilli() + LAUNCHER_START_SLACK_MS;
        long found = pid;
        return ProcessHandle.of(pid).map(h -> h.isAlive()
                && h.info().startInstant().map(t -> t.toEpochMilli() <= latestStartMs).orElse(true))
                .orElse(false) ? found : 0L;
    }

    /** The pointer's receipt {@code supervisor_pid=N} row (the supervisor's own first row), 0 = no
     * pointer / receipt / row. No liveness or start-time check: {@link #launcherPidOf} adds those. */
    static long supervisorPidOf(Path flightPointer) {
        Path receipt = receiptOf(flightPointer);
        return receipt == null ? 0L : supervisorPidIn(LaneFollow.readHead(receipt, 65_536).lines().toList());
    }

    /** The first {@code supervisor_pid=N} row's N among receipt {@code lines}, 0 = none. */
    static long supervisorPidIn(List<String> lines) {
        for (String raw : lines) {
            Matcher m = SUPERVISOR_PID_ROW.matcher(raw.trim());
            if (m.matches()) {
                return Long.parseLong(m.group(1));
            }
        }
        return 0L;
    }

    /**
     * The real wiring both shells call. {@code run} = the launcher's {@code run/} =
     * {@code opsFile}'s grandparent (the launcher layout {@code run/ops/ops-status.txt}); the stop
     * file = {@code run/stop}; the launcher pid, in this order: {@code knownLauncher} when it is
     * alive AND the pointer's receipt names its pid; else {@link #launcherPidOf} over
     * {@code run/latest.txt} (a LIVE previous flight wins over the known Process); else, when that
     * yields none, the alive {@code knownLauncher} itself - the spawned launcher before its receipt
     * exists, while the pointer still names the previous, finished flight. That last trust is safe
     * because the launcher honours a stop written after its own start at every point of its boot
     * ({@link ServeLauncher#stopOrderedAtBoot}, then its supervisor's own poll). A trusted known
     * launcher is tested and ended through its own {@code Process}, never by a pid it may have
     * released. The receipt = {@link FlightReceipt} (re-read each poll, handed only once it names
     * the launcher); kill = {@code destroyForcibly} (never this JVM), a receipt life only when its
     * process started at or after its flight's stamp - 1 s AND before this order began;
     * poll {@link #POLL_MS}, bound {@link #BOUND_MS}. BLOCKS up to 2 × the bound.
     *
     * <p>The shells' known {@code Process} never outranks a live
     * flight, and the previous flight's receipt (its stale {@code stopped=true}, its lives' recycled
     * pids) is never read for a launcher it does not name. Merged with the Start chain, the known
     * Process is first the STAGE pwsh: trusted the same way, it is ended last, after the bound (the
     * chain then starts no launcher); a stage that hands over inside the bound starts its launcher
     * AFTER the stop was written, so that launcher clears the stop as stale and this order reads no
     * receipt. And a receipt life whose supervisor died unrecorded may wear a recycled pid: ended
     * only when it started after the flight stamp (second-truncated, taken before any life spawns);
     * an unreadable start instant = left alone (a kill stays conservative).
     *
     * @param writerPid the LIVE snapshot writer's pid (&gt; 0), 0 when no live writer
     * @param knownLauncher the shell's own launcher {@code Process}, or {@code null}
     */
    public static Outcome killPublicServe(Path opsFile, long writerPid, Process knownLauncher) {
        Path opsDir = opsFile.toAbsolutePath().getParent();
        Path run = opsDir == null ? null : opsDir.getParent();
        if (run == null) {
            throw new IllegalArgumentException("the ops file has no launcher run folder above it");
        }
        Path pointer = run.resolve(ServeLauncher.POINTER_FILE);
        long receiptPid = supervisorPidOf(pointer);
        boolean knownUp = knownLauncher != null && knownLauncher.isAlive();
        Process trusted = null;
        long launcherPid;
        if (knownUp && receiptPid > 0 && knownLauncher.pid() == receiptPid) {
            trusted = knownLauncher;
            launcherPid = receiptPid;
        } else {
            launcherPid = launcherPidOf(pointer); // a LIVE previous flight wins over the known Process
            if (launcherPid == 0L && knownUp) {
                // The pointer yields no live launcher - the alive known one is
                // trusted (its boot honours this order's stop), its receipt gated by FlightReceipt
                trusted = knownLauncher;
                launcherPid = knownLauncher.pid();
            }
        }
        FlightReceipt receipt = new FlightReceipt(pointer, launcherPid);
        // A life also started BEFORE this order began - the stop file is written first, so a
        // live supervisor spawns no life after it; a process born during the order's waits wears a recycled pid
        long orderStartMs = System.currentTimeMillis();
        long self = ProcessHandle.current().pid();
        Process launcherProcess = trusted;
        long trustedPid = launcherPid;
        return killOrder(run.resolve(ServeLauncher.STOP_FILE), writerPid, launcherPid,
                pid -> launcherProcess != null && pid == trustedPid ? launcherProcess.isAlive()
                        : pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                pid -> pid > 0 && pid != self && ProcessHandle.of(pid).map(h -> h.isAlive()
                        && h.info().startInstant().map(t -> t.toEpochMilli() >= receipt.earliestLifeStartMs()
                                && t.toEpochMilli() <= orderStartMs).orElse(false)).orElse(false),
                pid -> {
                    if (launcherProcess != null && pid == trustedPid) {
                        launcherProcess.destroyForcibly();
                    } else if (pid > 0 && pid != self) {
                        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                    }
                },
                receipt, Thread::sleep, POLL_MS, BOUND_MS);
    }

    /**
     * The receipt {@link #killOrder} polls, re-read from the pointer on EVERY {@link #get}: for a
     * launcher pid &gt; 0 the pointer's flight's lines are handed only once that receipt's
     * {@code supervisor_pid=} row names the pid - until then {@code List.of()} (no terminal row, no
     * life). WHY: a trusted known launcher has no receipt yet while the pointer still names the
     * PREVIOUS flight, whose stale terminal row would read as this order's success and whose lives
     * may be recycled pids. {@link #earliestLifeStartMs} = the handed flight's stamp - 1 s
     * ({@code Long.MAX_VALUE} while nothing is handed or the stamp is unreadable: no life is
     * killable), re-derived from the SAME pointer read as the lines, so the life guard never pairs
     * one flight's lives with another flight's stamp. A launcher pid of 0 (none known: an orphaned
     * serve) is handed the pointer's receipt as read.
     *
     *
     */
    static final class FlightReceipt implements Supplier<List<String>> {
        private final Path pointer;
        private final long launcherPid;
        private long earliestLifeStartMs = Long.MAX_VALUE;

        /** @param launcherPid the launcher this order targets, &gt; 0; 0 = none known (no gate) */
        FlightReceipt(Path pointer, long launcherPid) {
            this.pointer = Objects.requireNonNull(pointer, "pointer");
            this.launcherPid = launcherPid;
        }

        @Override
        public List<String> get() {
            earliestLifeStartMs = Long.MAX_VALUE;
            Matcher flight = flightOf(pointer);
            if (flight == null) {
                return List.of();
            }
            List<String> lines = LaneFollow.readHead(pointer.resolveSibling(flight.group())
                    .resolve(LaneFollow.RECEIPT_FILE), 65_536).lines().toList();
            if (launcherPid > 0 && supervisorPidIn(lines) != launcherPid) {
                return List.of();
            }
            Instant stamp = stampOf(flight);
            earliestLifeStartMs = stamp == null ? Long.MAX_VALUE : stamp.toEpochMilli() - 1_000L;
            return lines;
        }

        /** The lower start bound of a killable life of the lines last handed (epoch ms). */
        long earliestLifeStartMs() {
            return earliestLifeStartMs;
        }
    }

    /** The first terminal row (trimmed: the supervisor writes the platform line separator), or null. */
    static String terminalRowOf(List<String> lines) {
        for (String raw : lines) {
            String line = raw.trim();
            if (TERMINAL_ROW.matcher(line).matches()) {
                return line;
            }
        }
        return null;
    }

    /** Every {@code life=N pid=P} row's P whose life has no {@code life=N exit=} row yet, in order. */
    static List<Long> runningLifePids(List<String> lines) {
        Set<String> exited = new HashSet<>();
        for (String raw : lines) {
            Matcher m = LIFE_EXIT_ROW.matcher(raw.trim());
            if (m.find()) {
                exited.add(m.group(1));
            }
        }
        List<Long> out = new ArrayList<>();
        for (String raw : lines) {
            Matcher m = LIFE_PID_ROW.matcher(raw.trim());
            if (m.matches() && !exited.contains(m.group(1))) {
                out.add(Long.parseLong(m.group(2)));
            }
        }
        return out;
    }

    /** The pointer's flight dir name (validated), or null. */
    private static Matcher flightOf(Path pointer) {
        if (pointer == null) {
            return null;
        }
        String flight = LaneFollow.readHead(pointer, 256).lines().findFirst().orElse("").trim();
        Matcher m = FLIGHT_DIR.matcher(flight);
        return m.matches() ? m : null;
    }

    /** {@code <pointer's dir>/<flight>/wrapper-receipt.txt}, or null when the pointer is absent / malformed. */
    private static Path receiptOf(Path pointer) {
        Matcher m = flightOf(pointer);
        return m == null ? null : pointer.resolveSibling(m.group()).resolve(LaneFollow.RECEIPT_FILE);
    }

    /** The flight dir's local-time stamp as an instant (DST fold: the later offset), or null. */
    private static Instant flightStampOf(Path pointer) {
        return stampOf(flightOf(pointer));
    }

    /** {@link #flightOf}'s match as its stamp's instant (DST fold: the later offset), or null. */
    private static Instant stampOf(Matcher m) {
        if (m == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(m.group(1), ServeLauncher.FLIGHT_STAMP)
                    .atZone(ZoneId.systemDefault()).withLaterOffsetAtOverlap().toInstant();
        } catch (DateTimeParseException malformed) {
            return null; // a shape-valid but impossible date (month 13) = no flight
        }
    }
}
