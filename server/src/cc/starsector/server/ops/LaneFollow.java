package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cc.starsector.server.ops.ServerOpsCore.*;

/**
 * Window-follows-lane (D-OPSFILE / D-GUIREUSE) carved out of
 * {@link ServerOpsCore} (the {@code deferred-refactors.md} row named this seam
 * {@code ops/LaneFollow.java} at its crossing): one pressed launch ({@link LaneLaunch}), the
 * pure PENDING / LIVE / FAILED / TIMED_OUT decision ({@link #launchPhase} - a snapshot is THIS
 * launch's only when a live writer stamped it after the press; the "MATCH ENDED forever"
 * shape), its detail / dialog texts, and the bounded launcher-log tail. Pure state + pure
 * functions: the caller ({@link OpsWindowModel}) computes writer liveness - the state ladder
 * itself stays in the core. Lane constants ({@code PUBLIC_LANE}, {@code START_LOG}, ...) stay in
 * {@link ServerOpsCore} (static import) because the launch trio and the core's detail texts
 * share them. Bodies moved VERBATIM. Selftest rows: {@link ServerOpsCoreSelftest}.
 */
public final class LaneFollow {
    private LaneFollow() {
    }

    /** One pressed launch the window follows until that lane's first live snapshot.
     * {@code flightPointer} = the supervisor's flight-dir pointer whose receipt
     * names each life's exit ({@link OpsRoots#pointer}); {@code null} = no
     * per-life receipt on this lane (the detached watch).
     *
     * <p>{@code current} = the process the launch's
     * verdict follows. A public Start spawns TWO processes in turn (the stage tool, then the
     * launcher, {@link ServerOpsLaunch.StartChain}), so {@code process} stays the FIRST spawn while
     * {@code current} is swapped to the launcher when the chain spawns it; {@link #exitCodeOrNull}
     * reads {@code current}. A shell that wants its launch to track the swap passes its spawn
     * wrapper's own {@code AtomicReference} here ({@link OpsWindowModel#pressStart}: the chain
     * spawns the launcher through the SAME {@code SpawnFn}, so the wrapper's set IS the swap);
     * {@code null} = a fresh reference seeded with {@code process} (the 5- / 6-arg shapes). */
    public record LaneLaunch(String lane, Path opsFile, String logPath, long pressedAtMs,
            Process process, Path flightPointer, AtomicReference<Process> current) {
        public LaneLaunch {
            if (current == null) {
                current = new AtomicReference<>(process);
            }
        }

        /** The 6-arg shape (no shared {@code current}): seeded from {@code process}. */
        public LaneLaunch(String lane, Path opsFile, String logPath, long pressedAtMs,
                Process process, Path flightPointer) {
            this(lane, opsFile, logPath, pressedAtMs, process, flightPointer, null);
        }

        /** The shape from before {@code flightPointer}: the pointer follows the lane (public serve = the
         * launcher's {@code ${PKG}/run/latest.txt} of the live {@link OpsRoots}; the watch lane has none). */
        public LaneLaunch(String lane, Path opsFile, String logPath, long pressedAtMs,
                Process process) {
            this(lane, opsFile, logPath, pressedAtMs, process,
                    PUBLIC_LANE.equals(lane) ? OpsRoots.get().pointer() : null);
        }

        /** The public Start press ({@link ServerOpsCore#STAGE_TOOL} then the launcher): the live
         * {@link OpsRoots}' status file + start log. */
        public static LaneLaunch publicServe(long pressedAtMs, Process process) {
            OpsRoots roots = OpsRoots.get();
            return new LaneLaunch(PUBLIC_LANE, roots.opsFile(), roots.startLog().toString(),
                    pressedAtMs, process);
        }

        /** The W10 watch press ({@link ServerOpsCore#WATCH_SCRIPT}, {@link ServerOpsCore#WATCH_OPS_FILE}). */
        public static LaneLaunch debugWatch(long pressedAtMs, Process process) {
            return new LaneLaunch(WATCH_LANE, Paths.get(WATCH_OPS_FILE), WATCH_LOG,
                    pressedAtMs, process);
        }

        /** The {@code current} process's exit code (the stage's, then the launcher's once the
         * chain swapped it); null while it runs (or when the spawn was only recorded). */
        public Integer exitCodeOrNull() {
            Process p = current.get();
            return p == null || p.isAlive() ? null : p.exitValue();
        }
    }

    public enum LaunchPhase { PENDING, LIVE, FAILED, TIMED_OUT }

    /**
     * Pure decision (selftest-pinned): a snapshot is THIS launch's only when a LIVE writer stamped
     * it after the press — the previous run's final snapshot on the same path (the "MATCH ENDED
     * forever") is never rendered as the new lane. A parent that exited non-zero before
     * that = FAILED (the wrapper's preflight throw becomes a dialog, not a silent revert); no live
     * snapshot within {@link ServerOpsCore#LAUNCH_GRACE_MS} = TIMED_OUT; else PENDING. The watch parent exits 0
     * after detaching, so its 0 is not a verdict either way.
     */
    public static LaunchPhase launchPhase(LaneLaunch launch, OpsSnapshot snap, boolean writerAlive,
            long nowMs, Integer parentExitCode) {
        return launchPhase(launch, snap, writerAlive, nowMs, parentExitCode, true);
    }

    /**
     * The same decision with the Start chain's
     * staging truth ({@link ServerOpsLaunch#STAGING}, passed by both shells' callers —
     * {@link OpsWindowModel#apply} and {@code OpsConsole.launchJsonOrNull}; a parameter, not a read
     * inside, so this class stays free of the console's process-wide state). A PUBLIC-lane parent
     * exit 0 before the first live snapshot is a VERDICT ({@link LaunchPhase#FAILED}: the launcher
     * ended without a live serve — a receipt's terminal row then says how) once {@code staging} is
     * false; while it is true the 0 is the STAGE tool's own clean exit and the chain is about to
     * swap {@code current} to the launcher = still PENDING. The watch lane's 0 stays no verdict
     * (its parent detaches). The 5-arg shape passes {@code staging = true}: no staging truth =
     * the older law (exit 0 never a verdict; PENDING stays bounded by the grace).
     */
    public static LaunchPhase launchPhase(LaneLaunch launch, OpsSnapshot snap, boolean writerAlive,
            long nowMs, Integer parentExitCode, boolean staging) {
        if (snap != null && writerAlive && snap.stampMs() >= launch.pressedAtMs()) {
            return LaunchPhase.LIVE;
        }
        if (parentExitCode != null && parentExitCode != 0) {
            return LaunchPhase.FAILED;
        }
        if (parentExitCode != null && !staging && PUBLIC_LANE.equals(launch.lane())) {
            return LaunchPhase.FAILED;
        }
        if (nowMs - launch.pressedAtMs() > LAUNCH_GRACE_MS) {
            return LaunchPhase.TIMED_OUT;
        }
        return LaunchPhase.PENDING;
    }

    /** The detail line while a launch is PENDING. */
    public static String launchPendingText(LaneLaunch launch, long nowMs) {
        return launch.lane() + " launched " + Math.max(0, (nowMs - launch.pressedAtMs()) / 1000)
                + " s ago - waiting for its first live snapshot at " + launch.opsFile()
                + " (log: " + launch.logPath() + ")";
    }

    /** The dialog text for a FAILED / TIMED_OUT launch, carrying the launcher log's tail. */
    public static String launchFailureText(LaneLaunch launch, LaunchPhase phase,
            List<String> logTail) {
        return launchFailureHead(launch, phase) + ".\nLast lines of " + launch.logPath() + ":\n"
                + (logTail.isEmpty() ? "(empty)" : String.join("\n", logTail));
    }

    /** The failure dialog's first line (no tail): shared by the dialog and {@link #launchJson}. */
    static String launchFailureHead(LaneLaunch launch, LaunchPhase phase) {
        return phase == LaunchPhase.FAILED
                ? launch.lane() + " launcher exited with code " + launch.exitCodeOrNull()
                        + " before any live snapshot reached " + launch.opsFile()
                : launch.lane() + " wrote no live snapshot to " + launch.opsFile() + " within "
                        + (LAUNCH_GRACE_MS / 1000) + " s";
    }

    /** The same dialog text, LED by what the supervisor's receipt says when a
     * life died (an incident showed "STARTING" for 4 minutes over five bare
     * {@code BindException} lives the wrapper log never showed). {@code null} = plain text. */
    public static String launchFailureText(LaneLaunch launch, LaunchPhase phase,
            List<String> logTail, LifeFailure life) {
        String plain = launchFailureText(launch, phase, logTail);
        return life == null ? plain : life.detailText() + "\n" + plain;
    }

    // ------------------------------------------ per-life failure

    /** What the supervisor's receipt says about the LAST life that exited non-zero while the
     * launch is still PENDING: {@code exceptionLine} = the first exception line of that life's
     * stderr ({@code lives/life-N.err.log}), {@code ""} when none; {@code exhausted} = the
     * relaunch budget is spent (the parent exits 1 next). */
    public record LifeFailure(int life, int exitCode, boolean exhausted, String exceptionLine) {
        /** The ONE printable detail/headline line the window shows (single-line law). */
        public String detailText() {
            // Exit 78 is FINAL (ServeSupervisor writes
            // refused_exit and never relaunches it) - the old "supervisor relaunching" mislabel.
            return "life " + life + "/" + (START_MAX_RELAUNCHES + 1) + " exited code " + exitCode
                    + (exceptionLine.isEmpty() ? "" : " - " + exceptionLine)
                    + (exitCode == EXIT_REFUSED_FINAL ? " - the server REFUSED (exit 78, final)"
                            : exhausted ? " - relaunch budget exhausted"
                            : " - supervisor relaunching");
        }
    }

    /** The serve's activation-refusal exit ({@code ActivationGate.EXIT_REFUSED}): the supervisor
     * ends on it with {@code refused_exit=true}, never a relaunch. */
    static final int EXIT_REFUSED_FINAL = cc.starsector.server.platform.ActivationGate.EXIT_REFUSED;

    // ------------------------------------------ the launcher's terminal rows

    /** The receipt's terminal verdicts ({@code ServeSupervisor.loop} / {@code ServeLauncher}'s
     * pre-check refusal): exactly one is the receipt's last row once the launcher is done. */
    public enum Terminal { REFUSED_PRELAUNCH, REFUSED_EXIT, STOPPED, BUDGET_EXHAUSTED, CLEAN_EXIT }

    /**
     * One parsed terminal row. {@code reason} = the
     * closed-vocabulary token of a {@code refused_prelaunch=true reason=<token>} row
     * ({@code [a-z0-9_]{1,64}}), else {@code ""}; {@code lives} = the row's {@code lives=N}
     * (0..999999; 0 = stopped before life 1; always 0 for REFUSED_PRELAUNCH, which has none).
     */
    public record TerminalRow(Terminal kind, String reason, int lives) {
        /** The row as the receipt spells it (the web's {@code "terminal"} field). */
        public String rowText() {
            switch (kind) {
                case REFUSED_PRELAUNCH:
                    return "refused_prelaunch=true reason=" + reason;
                case REFUSED_EXIT:
                    return "refused_exit=true lives=" + lives;
                case STOPPED:
                    return "stopped=true lives=" + lives;
                case BUDGET_EXHAUSTED:
                    return "relaunch_budget_exhausted=true lives=" + lives;
                default:
                    return "clean_exit=true lives=" + lives;
            }
        }
    }

    private static final Pattern TERMINAL_LIVES_ROW = Pattern.compile(
            "(stopped|clean_exit|refused_exit|relaunch_budget_exhausted)=true lives=([0-9]{1,6})");
    private static final Pattern TERMINAL_REFUSED_ROW = Pattern.compile(
            "refused_prelaunch=true reason=([a-z0-9_]{1,64})");

    /**
     * Pure (selftest-pinned) — the LAST terminal row
     * of a receipt ({@code ServeSupervisor} / {@code ServeLauncher} grammar, one row per line),
     * else {@code null}. Fail-closed like {@link #parseLifeFailure}: a row off the strict grammar
     * (a non-numeric {@code lives=}, an off-vocabulary reason) is skipped, never a verdict.
     */
    public static TerminalRow parseTerminalRow(List<String> receiptLines) {
        TerminalRow last = null;
        for (String raw : receiptLines) {
            String line = raw.trim();
            Matcher refused = TERMINAL_REFUSED_ROW.matcher(line);
            if (refused.matches()) {
                last = new TerminalRow(Terminal.REFUSED_PRELAUNCH, refused.group(1), 0);
                continue;
            }
            Matcher m = TERMINAL_LIVES_ROW.matcher(line);
            if (!m.matches()) {
                continue;
            }
            Terminal kind = switch (m.group(1)) {
                case "stopped" -> Terminal.STOPPED;
                case "clean_exit" -> Terminal.CLEAN_EXIT;
                case "refused_exit" -> Terminal.REFUSED_EXIT;
                default -> Terminal.BUDGET_EXHAUSTED;
            };
            last = new TerminalRow(kind, "", Integer.parseInt(m.group(2)));
        }
        return last;
    }

    /**
     * THIS launch's terminal row — {@link #lifeFailure}'s
     * pointer law ({@link #flightDir}: mtime ≥ press − {@link #POINTER_SLACK_MS}, the
     * {@code flight-<stamp>} shape) then {@link #parseTerminalRow} over the receipt's first 16 KB.
     * Anything missing = {@code null}; a read that throws = {@code null}, uncounted (there is no
     * counter; {@link #lifeFailure}'s read was never counted either).
     */
    public static TerminalRow terminalRow(Path pointer, long pressedAtMs) {
        Path dir = flightDirOrNull(pointer, pressedAtMs);
        return dir == null ? null : terminalRowOf(dir);
    }

    /** The terminal row of ONE pinned flight dir (a consumed launch re-reads its
     * OWN flight, never whatever the shared pointer names later); missing / unreadable = null,
     * uncounted like {@link #lifeFailure}'s read (the render falls back to the snapshot). */
    public static TerminalRow terminalRowOf(Path flightDir) {
        try {
            return parseTerminalRow(readHead(flightDir.resolve(RECEIPT_FILE), 16_384).lines().toList());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@link #flightDir} with its throw folded to {@code null} (an unreadable pointer reads as
     * "no flight yet", the pre-fold behaviour of both readers). */
    static Path flightDirOrNull(Path pointer, long pressedAtMs) {
        try {
            return flightDir(pointer, pressedAtMs);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The launcher's own refusal sentence: the last {@code serve-launcher refused:} line of the
     * start log's tail (both Start spawns append there), one printable line ≤ 320 chars; none =
     * {@code ""}. */
    public static String refusalSentence(Path log) {
        String prefix = ServeLauncher.REFUSED_PREFIX.trim();
        List<String> tail = logTail(log, 15);
        for (int i = tail.size() - 1; i >= 0; i--) {
            String t = tail.get(i).trim();
            if (t.startsWith(prefix)) {
                return oneLine(t, 320);
            }
        }
        return "";
    }

    /** The REFUSED_PRELAUNCH detail line (the Swing render and the web's {@code text}). */
    public static String refusedText(TerminalRow t, Path log) {
        String sentence = refusalSentence(log);
        return "REFUSED before launch: " + t.reason() + (sentence.isEmpty() ? "" : " - " + sentence);
    }

    /** The STOPPED detail line (the Swing render and the web's {@code text}). */
    public static String stoppedText(TerminalRow t) {
        return "stopped by Kill / stop file (lives " + t.lives() + ")";
    }

    /**
     * The web shell's launch object — ONE JSON object
     * {@code {"phase","headline","stateKey","terminal","reason","text"}} whose texts are the Swing
     * render's own strings ({@link #refusedText}, {@link #stoppedText}, the PENDING composition of
     * {@link OpsWindowModel#apply}, {@link #launchFailureHead}). {@code headline} / {@code stateKey}
     * null = follow the snapshot (LIVE, or a CLEAN_EXIT whose final snapshot says MATCH ENDED);
     * {@code stateKey} is the page's colour class (starting amber / gone red / stopped calm), so
     * the page JS only picks. Reads the launch's start log (bounded, {@link #logTail}).
     */
    public static String launchJson(LaneLaunch launch, LaunchPhase phase, LifeFailure life,
            TerminalRow terminal, long nowMs) {
        Terminal kind = terminal == null ? null : terminal.kind();
        String headline;
        String stateKey;
        String text;
        if (phase == LaunchPhase.LIVE || kind == Terminal.CLEAN_EXIT) {
            headline = null;
            stateKey = null;
            text = "";
        } else if (kind == Terminal.REFUSED_PRELAUNCH) {
            headline = "REFUSED";
            stateKey = "stopped";
            text = refusedText(terminal, Paths.get(launch.logPath()));
        } else if (kind == Terminal.STOPPED) {
            headline = "STOPPED";
            stateKey = "stopped";
            text = stoppedText(terminal);
        } else if (phase == LaunchPhase.PENDING) {
            headline = life == null ? "STARTING" : "FAILED";
            stateKey = life == null ? "starting" : "gone";
            text = (life == null ? "" : life.detailText() + "  |  ") + launchPendingText(launch, nowMs);
        } else {
            headline = "FAILED";
            stateKey = "gone";
            List<String> tail = logTail(Paths.get(launch.logPath()), 1);
            text = (life == null ? "" : life.detailText() + " - ") + launchFailureHead(launch, phase)
                    + (tail.isEmpty() ? "" : " - last log line: " + oneLine(tail.get(0).trim(), 320));
        }
        return "{\"phase\":\"" + phase.name() + "\",\"headline\":" + jsonText(headline)
                + ",\"stateKey\":" + jsonText(stateKey)
                + ",\"terminal\":" + jsonText(terminal == null ? null : terminal.rowText())
                + ",\"reason\":" + jsonText(kind == Terminal.REFUSED_PRELAUNCH ? terminal.reason() : null)
                + ",\"text\":" + jsonText(text) + "}";
    }

    private static String jsonText(String s) {
        return s == null ? "null" : "\"" + jsonEscape(s) + "\"";
    }

    /** {@code s} as ONE printable ASCII line of at most {@code max} chars (others → {@code ?}). */
    static String oneLine(String s, int max) {
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return sb.toString();
    }

    /** Where {@code run-supervised.ps1} writes each life's exit ({@code life=N exit=C}). */
    public static final String RECEIPT_FILE = "wrapper-receipt.txt";
    /** A pointer stamped this long before the press still counts as THIS launch's (NTFS
     * mtime granularity vs the press instant); older = a previous flight's, ignored. */
    static final long POINTER_SLACK_MS = 2_000L;
    private static final Pattern FLIGHT_DIR = Pattern.compile("flight-[0-9]{8}-[0-9]{6}");
    /** A stack trace's first line: a class name ending in Exception/Error, then the message
     * ({@code PilotIntentCombatServerMain.main} prints the trace bare; JVM warnings and the
     * {@code [cc-transform]} rows never match). */
    private static final Pattern EXCEPTION_LINE =
            Pattern.compile("^[A-Za-z_$][\\w.$]*(Exception|Error)\\b.*");

    /**
     * Pure (selftest-pinned): the receipt rows ({@code run-supervised.ps1} grammar — {@code life=N
     * pid=}, {@code life=N exit=C}, {@code relaunch_budget_exhausted=true lives=N}) → the last
     * life's non-zero exit, or {@code null} when no life exited non-zero yet / the last exit was
     * clean. Malformed rows are skipped (the file is ours, but the read stays fail-closed).
     */
    public static LifeFailure parseLifeFailure(List<String> receiptLines) {
        int life = -1;
        int exit = 0;
        boolean exhausted = false;
        for (String raw : receiptLines) {
            String line = raw.trim();
            if (line.startsWith("relaunch_budget_exhausted=true")) {
                exhausted = true;
                continue;
            }
            if (!line.startsWith("life=")) {
                continue;
            }
            String[] kv = line.split(" ");
            if (kv.length < 2 || !kv[1].startsWith("exit=")) {
                continue;
            }
            try {
                life = Integer.parseInt(kv[0].substring("life=".length()));
                exit = Integer.parseInt(kv[1].substring("exit=".length()));
            } catch (NumberFormatException malformed) {
                life = -1;
            }
        }
        return life > 0 && exit != 0 ? new LifeFailure(life, exit, exhausted, "") : null;
    }

    /** The first stack-trace head line of a life's stderr, as ONE printable ASCII line (≤ 320
     * chars: the key gate's 253-char line survives whole), reading at most the
     * file's first 64 KB; none/missing = {@code ""}. */
    public static String firstExceptionLine(Path errLog) {
        for (String line : readHead(errLog, 65_536).lines().toList()) {
            String t = line.trim();
            if (EXCEPTION_LINE.matcher(t).matches()) {
                return oneLine(t, 320);
            }
        }
        return "";
    }

    /**
     * The receipt-aware read for a PENDING launch: pointer file (its first line names the
     * flight dir, validated against the {@code flight-<stamp>} shape — never an arbitrary path)
     * → {@code <dir>/wrapper-receipt.txt} → {@link #parseLifeFailure} → that life's exception
     * line. A pointer stamped before the press (a previous flight's) or anything missing /
     * unreadable = {@code null} (the plain STARTING render).
     */
    public static LifeFailure lifeFailure(Path pointer, long pressedAtMs) {
        try {
            Path dir = flightDir(pointer, pressedAtMs);
            if (dir == null) {
                return null;
            }
            LifeFailure f = parseLifeFailure(
                    readHead(dir.resolve(RECEIPT_FILE), 16_384).lines().toList());
            if (f == null) {
                return null;
            }
            return new LifeFailure(f.life(), f.exitCode(), f.exhausted(), firstExceptionLine(
                    dir.resolve("lives").resolve("life-" + f.life() + ".err.log")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** THIS launch's flight dir off the pointer (its first line, validated against the
     * {@code flight-<stamp>} shape — never an arbitrary path); a pointer stamped before
     * {@code pressedAtMs - POINTER_SLACK_MS} (a previous flight's) or missing = {@code null}.
     * Shared by {@link #lifeFailure} and {@link #terminalRow} (moved out of the former). */
    static Path flightDir(Path pointer, long pressedAtMs) throws IOException {
        if (pointer == null || !Files.isRegularFile(pointer)
                || Files.getLastModifiedTime(pointer).toMillis() < pressedAtMs - POINTER_SLACK_MS) {
            return null;
        }
        String flight = readHead(pointer, 256).lines().findFirst().orElse("").trim();
        return FLIGHT_DIR.matcher(flight).matches() ? pointer.resolveSibling(flight) : null;
    }

    /** The first {@code maxBytes} of a local file as UTF-8 (bounded by construction; the files
     * are the supervisor's, never wire). Missing / unreadable = {@code ""}. */
    static String readHead(Path file, int maxBytes) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            byte[] buf = new byte[maxBytes];
            int n = 0;
            try (java.io.InputStream in = Files.newInputStream(file)) {
                int r;
                while (n < maxBytes && (r = in.read(buf, n, maxBytes - n)) > 0) {
                    n += r;
                }
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** The last {@code maxLines} non-blank lines of a local log, reading at most its final
     * 16 KB (bounded by construction; the launcher logs are ours, never wire). Missing or
     * unreadable = empty. */
    public static List<String> logTail(Path log, int maxLines) {
        try {
            if (!Files.isRegularFile(log)) {
                return List.of();
            }
            long size = Files.size(log);
            long from = Math.max(0L, size - 16_384L);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) (size - from));
            try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(log)) {
                ch.position(from);
                while (buf.hasRemaining() && ch.read(buf) > 0) {
                    // fill
                }
            }
            List<String> lines = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8)
                    .lines().filter(l -> !l.isBlank()).toList();
            return lines.size() <= maxLines ? lines
                    : lines.subList(lines.size() - maxLines, lines.size());
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
