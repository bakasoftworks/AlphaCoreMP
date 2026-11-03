package cc.starsector.server.ops;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names the Windows-reserved UDP port block a door port sits in. A Windows host running Hyper-V, WSL
 * or Docker gets DYNAMIC UDP port-exclusion blocks from WinNAT (they move on a reboot); a bind inside
 * one fails with WSAEACCES, which Java words "Address already in use", so without this check a failed
 * bind probe guesses a dead VPN, a stale lease or a taken port while nothing holds the port. An
 * ADMINISTERED exclusion (netsh's {@code *} rows) blocks no bind - a bind inside one succeeds, measured -
 * so only a non-administered block counts as holding a port, and a one-port administered exclusion is
 * the host's persistent remedy (the three admin lines of {@link #warning(int)}).
 *
 * <p>Cost and reach: one check spawns {@code netsh.exe} (tens of ms, up to ~1 s on a cold box), so it
 * runs ONLY on the bind probe's failure path and on the ops console's VPN lease fill - never on a
 * success path, never on the frame loop. The command is FIXED: {@code netsh.exe} under the
 * {@code SystemRoot} env var's {@code System32} (an absolute path, never {@code PATH}) with the argv
 * {@code interface ipv4 show excludedportrange protocol=udp}; nothing from a caller reaches the command
 * line (the port is an int checked 1..65535 before anything runs). Bounded: {@value #TIMEOUT_SECONDS} s
 * then {@code destroyForcibly()}, stdout read at most {@value #MAX_STDOUT_BYTES} B /
 * {@value #MAX_LINES} lines, stderr discarded, stdin closed, the {@code CC_*} env keys dropped. The parse
 * keeps only all-digit rows (a localized header never matters; a line over {@value #MAX_LINE_CHARS}
 * chars is skipped), and the warning echoes only the port and the two parsed block numbers, never
 * netsh text. Never throws: a run that fails answers a bounded {@code why} token (one stdout row) and
 * an empty warning; a host that is not Windows answers {@code why=not_windows} without a spawn.
 *
 *
 */
public final class WindowsPortReservation {

    /** The netsh run's bound: past it the child is {@code destroyForcibly()}'d and the answer is {@code why=timeout}. */
    static final long TIMEOUT_SECONDS = 3L;
    /** The most stdout bytes read from the child; the rest is never read. */
    static final int MAX_STDOUT_BYTES = 64 * 1024;
    /** The most lines {@link #parse} looks at. */
    static final int MAX_LINES = 512;
    /** A longer line is skipped before the row pattern sees it (a real row is ~30 chars). */
    static final int MAX_LINE_CHARS = 128;

    /** One netsh row: {@code start end [*]}, digits only. */
    private static final Pattern ROW = Pattern.compile("^\\s*(\\d{1,5})\\s+(\\d{1,5})\\s*(\\*)?\\s*$");
    private static final Pattern PORT_FIELD = Pattern.compile("\\d{1,5}");

    /** The host-voice warning: the port, the block's start + end, the port again (the admin line). */
    private static final String WARNING = "UDP port %d sits in a Windows-reserved port block %d-%d"
            + " (Hyper-V / WSL / Docker reserve such blocks; nothing holds the port). Free it in an administrator"
            + " PowerShell: net stop winnat; netsh int ipv4 add excludedportrange protocol=udp startport=%d"
            + " numberofports=1 store=persistent; net start winnat - or reconnect the VPN for another port.";

    private WindowsPortReservation() {
    }

    /** One exclusion block, {@code 1 <= start <= end <= 65535}; {@code administered} = netsh's {@code *} mark. */
    public record Block(int start, int end, boolean administered) {
    }

    /**
     * A check's answer: {@code block} = the non-administered block holding the port, or null (none, or no
     * check ran); {@code why} = null when the check ran, else one bounded token: {@code bad_port} (outside
     * 1..65535), {@code not_windows}, {@code timeout}, {@code spawn:<SimpleName>} or {@code exit:<n>}.
     */
    public record Answer(Block block, String why) {
    }

    /** A netsh run that answered no usable lines; {@code why} is the bounded token the answer carries. */
    private static final class RunFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String why;

        RunFailure(String why) {
            super(why, null, false, false);
            this.why = why;
        }
    }

    /**
     * The blocks in netsh's output: every line (the first {@value #MAX_LINES}, each at most
     * {@value #MAX_LINE_CHARS} chars) matching {@code start end [*]} with {@code 1 <= start <= end <= 65535};
     * every other line (headers, dashes, the legend, garbage) is ignored. Null in = no blocks.
     */
    static List<Block> parse(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        if (lines == null) {
            return blocks;
        }
        int seen = 0;
        for (String line : lines) {
            if (++seen > MAX_LINES) {
                break;
            }
            if (line == null || line.length() > MAX_LINE_CHARS) {
                continue;
            }
            Matcher m = ROW.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int start = Integer.parseInt(m.group(1));
            int end = Integer.parseInt(m.group(2));
            if (start >= 1 && start <= end && end <= 65535) {
                blocks.add(new Block(start, end, m.group(3) != null));
            }
        }
        return blocks;
    }

    /** The first NON-administered block containing {@code port}, else null (an administered one blocks no bind). */
    static Block holding(List<Block> blocks, int port) {
        if (blocks == null) {
            return null;
        }
        for (Block b : blocks) {
            if (b != null && !b.administered() && port >= b.start() && port <= b.end()) {
                return b;
            }
        }
        return null;
    }

    /** The live check on this host ({@code os.name}, the real netsh run). */
    public static Answer check(int port) {
        try {
            return check(port, System.getProperty("os.name", ""), WindowsPortReservation::runNetsh);
        } catch (Throwable failed) {
            return failedRun("spawn:" + failed.getClass().getSimpleName());
        }
    }

    /**
     * The check with its host seams: the port is judged first (no run for a port outside 1..65535), then
     * the OS name (no run unless it starts with {@code Windows}), then {@code runner}'s lines are parsed.
     */
    static Answer check(int port, String osName, Supplier<List<String>> runner) {
        try {
            if (port < 1 || port > 65535) {
                return new Answer(null, "bad_port");
            }
            if (osName == null || !osName.startsWith("Windows")) {
                return new Answer(null, "not_windows");
            }
            return new Answer(holding(parse(runner.get()), port), null);
        } catch (RunFailure failed) {
            return failedRun(failed.why);
        } catch (Throwable failed) {
            return failedRun("spawn:" + failed.getClass().getSimpleName());
        }
    }

    /** A run that could not answer: ONE stdout row (bounded tokens only), the answer with no block. */
    private static Answer failedRun(String why) {
        System.out.println("windows-port-reservation check=skipped why=" + why);
        return new Answer(null, why);
    }

    /**
     * The host-voice warning for {@code port}: "" when no Windows-reserved block holds it OR the check could
     * not run, else the sentence pair naming the block and the administrator lines that free the port.
     */
    public static String warning(int port) {
        try {
            return warningFor(port, check(port));
        } catch (Throwable failed) {
            return skipped("warning", failed, "");
        }
    }

    /** {@link #warning(int)} through the check's host seams. */
    static String warning(int port, String osName, Supplier<List<String>> runner) {
        try {
            return warningFor(port, check(port, osName, runner));
        } catch (Throwable failed) {
            return skipped("warning", failed, "");
        }
    }

    /** A step that threw past its own guards: ONE stdout row (the step word + a SimpleName), then {@code fallback}. */
    private static <T> T skipped(String step, Throwable failed, T fallback) {
        System.out.println("windows-port-reservation " + step + "=skipped why=" + failed.getClass().getSimpleName());
        return fallback;
    }

    private static String warningFor(int port, Answer answer) {
        if (answer == null || answer.block() == null) {
            return "";
        }
        return String.format(Locale.ROOT, WARNING, port, answer.block().start(), answer.block().end(), port);
    }

    /**
     * A VPN lease fill with the reserved-block warning appended to its note, so the host reads it in the ops
     * console BEFORE Start: when {@code fill.bindPort()} is a port 1..65535 and {@link #warning(int)} is
     * non-empty, a new fill with {@code note + " " + warning} and every other field kept; else the SAME fill
     * object (a blank port, a free port, a check that could not run, a null fill).
     */
    public static VpnLeaseProbe.Fill annotate(VpnLeaseProbe.Fill fill) {
        return annotate(fill, WindowsPortReservation::warning);
    }

    /** {@link #annotate(VpnLeaseProbe.Fill)} with the warning as a seam. */
    static VpnLeaseProbe.Fill annotate(VpnLeaseProbe.Fill fill, IntFunction<String> warn) {
        try {
            if (fill == null || fill.bindPort() == null) {
                return fill;
            }
            String field = fill.bindPort().trim();
            if (!PORT_FIELD.matcher(field).matches()) {
                return fill;
            }
            int port = Integer.parseInt(field);
            if (port < 1 || port > 65535) {
                return fill;
            }
            String w = warn.apply(port);
            if (w == null || w.isEmpty()) {
                return fill;
            }
            String note = fill.note() == null || fill.note().isBlank() ? w : fill.note() + " " + w;
            return new VpnLeaseProbe.Fill(fill.bindHost(), fill.bindPort(), fill.advertise(), fill.portFound(), note);
        } catch (Throwable failed) {
            return skipped("annotate", failed, fill);
        }
    }

    /**
     * The real run: the fixed netsh argv, ONE deadline of {@value #TIMEOUT_SECONDS} s over the child's exit
     * and the stdout drain (a daemon reader thread, so a stuck pipe never pins this caller past it), the
     * lines decoded byte-for-byte (the rows are ASCII digits in every console code page). Throws
     * {@link RunFailure} ({@code timeout}, {@code exit:<n>}, {@code spawn:<SimpleName>} of a spawn, wait or
     * read failure; an interrupt is re-asserted on the thread) or an unchecked throwable; the child is
     * destroyed on every path that leaves it alive.
     */
    private static List<String> runNetsh() {
        try {
            return runNetshOnce();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RunFailure("spawn:InterruptedException");
        } catch (java.io.IOException failed) {
            throw new RunFailure("spawn:" + failed.getClass().getSimpleName());
        }
    }

    private static List<String> runNetshOnce() throws java.io.IOException, InterruptedException {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException();
        }
        Path exe = Paths.get(root, "System32", "netsh.exe");
        if (!exe.isAbsolute()) {
            throw new IllegalStateException();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        Process child = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(exe.toString(), "interface", "ipv4", "show",
                    "excludedportrange", "protocol=udp").redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().keySet().removeIf(key -> key.startsWith("CC_"));
            child = builder.start();
            child.getOutputStream().close();
            BoundedRead read = new BoundedRead(child.getInputStream());
            Thread reader = new Thread(read, "windows-port-reservation-read");
            reader.setDaemon(true);
            reader.start();
            if (!child.waitFor(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new RunFailure("timeout");
            }
            reader.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            if (reader.isAlive()) {
                throw new RunFailure("timeout");
            }
            if (read.failure != null) {
                throw new RunFailure("spawn:" + read.failure.getClass().getSimpleName());
            }
            int rc = child.exitValue();
            if (rc != 0) {
                throw new RunFailure("exit:" + rc);
            }
            return read.lines();
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    /** The stdout drain: at most {@value #MAX_STDOUT_BYTES} B, then it stops reading; a read failure is kept for the caller. */
    private static final class BoundedRead implements Runnable {
        private final InputStream in;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile Throwable failure;

        BoundedRead(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] buf = new byte[4096];
            try {
                while (bytes.size() < MAX_STDOUT_BYTES) {
                    int n = in.read(buf, 0, Math.min(buf.length, MAX_STDOUT_BYTES - bytes.size()));
                    if (n < 0) {
                        break;
                    }
                    bytes.write(buf, 0, n);
                }
            } catch (Throwable failed) { // counted by the caller: the run answers why=spawn:<SimpleName>
                failure = failed;
            }
        }

        /** The read bytes as lines (ISO-8859-1, byte-for-byte), at most {@value #MAX_LINES}. */
        List<String> lines() {
            String text = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
            List<String> out = new ArrayList<>();
            for (String line : text.split("\r?\n", -1)) {
                if (out.size() >= MAX_LINES) {
                    break;
                }
                out.add(line);
            }
            return out;
        }
    }
}
