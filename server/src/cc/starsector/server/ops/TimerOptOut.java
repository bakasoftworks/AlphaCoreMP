package cc.starsector.server.ops;

import java.io.OutputStream;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * The per-life timer-resolution opt-out of {@link ServeSupervisor}. Windows 11 withdraws a windowless
 * process's timer-resolution request about 2.4 s after launch, so the serve's sub-tick sleeps (the
 * voice relay pump, the sim pacer) ride the coarse 15.6 ms quantum; the cure is
 * {@code SetProcessInformation(hChild, ProcessPowerThrottling, {1, 0x4, 0}, 12)} applied from OUTSIDE
 * on the child.
 * A Java 17 launcher has no final FFM and the package ships no native helper and
 * no {@code .ps1} file, so {@link #PRODUCTION} spawns the host's own {@code powershell.exe} and feeds
 * it a three-line script ({@code Add-Type} of a small C# class: {@code OpenProcess(0x200
 * PROCESS_SET_INFORMATION)} + {@code SetProcessInformation} + {@code CloseHandle}) through STDIN.
 *
 *
 * <p><b>rc grammar</b> (the receipt row {@code life=N timer_optout=<rc>}): {@code 0} applied;
 * {@code > 0} a Win32 error code (the helper's exit code; a helper-side script failure, e.g. a
 * Constrained-Language host refusing {@code Add-Type}, surfaces as PowerShell's own non-zero exit
 * code instead, indistinguishable here and fail-open alike);
 * {@code -1} the helper could not start, timed out (destroyed at {@link #HELPER_TIMEOUT_SECONDS}),
 * threw, or the child was already dead; {@link #NOT_ATTEMPTED} not a Windows host, no
 * {@code powershell.exe} at its fixed path, or the knob {@value #KNOB_PROPERTY}{@code =off}.
 * Fail-open everywhere: the serve runs either way.
 *
 * <p><b>Why stdin, not an inline {@code -Command}:</b> the C# carries {@code "} characters that
 * Java's Windows argv quoting plus PowerShell's own re-parse of its command line mangle; the stdin
 * pipe carries them byte-exact, and closing it (EOF) ends the helper's read, so it never waits on
 * input. No host text enters the script: the only variable is the child's pid, a Java long.
 *
 * <p><b>Why the pid cannot be recycled under the helper:</b> Windows reuses no pid while a handle to
 * the process is open, and the child's {@link Process} holds one; the helper thread keeps that
 * {@code Process} reachable until the helper has ended ({@link Reference#reachabilityFence}), so the
 * handle's cleaner cannot close it early.
 *
 * <p><b>A stranger's antivirus</b> may flag the shape ({@code -ExecutionPolicy Bypass} +
 * {@code Add-Type} + {@code OpenProcess} on another process): that kills or blocks the helper, which
 * reads as {@code -1} or a non-zero code, and the serve runs as before (fail-open by construction).
 * A host who objects sets {@code -Dcc.ops.timerOptOut=off} on the launcher JVM.
 *
 *
 */
public final class TimerOptOut {

    /** The rc meaning "not attempted" (outside the Win32 error-code range). */
    public static final int NOT_ATTEMPTED = -2;
    /** The launcher-JVM system property: {@code off} (trimmed, any case) = {@link #NOT_ATTEMPTED}; absent or anything else = on. Read at apply time. */
    public static final String KNOB_PROPERTY = "cc.ops.timerOptOut";
    /** The helper's bound: past it the helper is {@code destroyForcibly()}'d and the rc is {@code -1}. */
    public static final long HELPER_TIMEOUT_SECONDS = 20L;
    /** The daemon thread's name (one per life; the supervisor's stop poll and reaping never wait on it). */
    static final String THREAD_NAME = "cc-timer-optout";

    /**
     * The C# the helper compiles, ONE line: it carries no {@code '} so a single-quoted PowerShell
     * string holds it verbatim. {@code Apply} returns 0 or the Win32 error of {@code OpenProcess} /
     * {@code SetProcessInformation}.
     *
     */
    private static final String CSHARP = "using System; using System.Runtime.InteropServices; namespace Cc {"
            + " public static class ServeTimerOptOut { [StructLayout(LayoutKind.Sequential)] public struct"
            + " PowerThrottlingState { public uint Version; public uint ControlMask; public uint StateMask; }"
            + " [DllImport(\"kernel32.dll\", SetLastError = true)] static extern IntPtr OpenProcess(uint access,"
            + " bool inherit, uint pid); [DllImport(\"kernel32.dll\", SetLastError = true)] static extern bool"
            + " SetProcessInformation(IntPtr h, int cls, ref PowerThrottlingState s, int size);"
            + " [DllImport(\"kernel32.dll\")] static extern bool CloseHandle(IntPtr h); public static int"
            + " Apply(uint pid) { IntPtr h = OpenProcess(0x200, false, pid); if (h == IntPtr.Zero) return"
            + " Marshal.GetLastWin32Error(); var s = new PowerThrottlingState { Version = 1, ControlMask = 0x4,"
            + " StateMask = 0 }; bool ok = SetProcessInformation(h, 4, ref s, 12); int rc = ok ? 0 :"
            + " Marshal.GetLastWin32Error(); CloseHandle(h); return rc; } } }";

    /** Applies the opt-out to one life's child process and answers its rc. */
    @FunctionalInterface
    public interface Applier {
        /**
         * Answers {@code rc} EXACTLY once, inline or later from another thread; never blocks the
         * caller on the helper and never throws.
         */
        void apply(Process child, IntConsumer rc);
    }

    /** The real applier (the supervisor's default): the checks inline, the helper on a daemon thread. */
    public static final Applier PRODUCTION = TimerOptOut::applyProduction;

    private TimerOptOut() {
    }

    /** The synchronous {@link #NOT_ATTEMPTED} applier: what a test injects for a deterministic receipt. */
    public static Applier notAttempted() {
        return (child, rc) -> rc.accept(NOT_ATTEMPTED);
    }

    /**
     * {@link #PRODUCTION}: knob off, not Windows, or no {@code powershell.exe} at
     * {@code %SystemRoot%\System32\WindowsPowerShell\v1.0\} (a FIXED path, never {@code PATH}: a server
     * process trusts no host {@code PATH}) answers {@link #NOT_ATTEMPTED} inline; a dead child answers
     * {@code -1} inline; else ONE daemon thread {@value #THREAD_NAME} runs the helper and answers its rc.
     */
    private static void applyProduction(Process child, IntConsumer rc) {
        Path exe = knobOn() ? powerShellExe() : null;
        if (exe == null) {
            rc.accept(NOT_ATTEMPTED);
            return;
        }
        if (!child.isAlive()) {
            rc.accept(-1);
            return;
        }
        Thread helper = new Thread(() -> rc.accept(runHelper(exe, child)), THREAD_NAME);
        helper.setDaemon(true);
        try {
            helper.start();
        } catch (RuntimeException | OutOfMemoryError noThread) {
            // the thread never ran (no native thread left), so this is the one answer
            System.err.println("serve-launcher warning: the timer opt-out thread did not start ("
                    + noThread.getClass().getSimpleName() + ")");
            rc.accept(-1);
        }
    }

    /** The knob's ONE reading, shared with the console's launch command ({@code ServerOpsLaunch.launchCommand}
     * passes the constant on): true iff {@code value} (null = unset) reads {@code off}, trimmed, any case. */
    public static boolean knobOff(String value) {
        return value != null && "off".equals(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean knobOn() {
        return !knobOff(System.getProperty(KNOB_PROPERTY));
    }

    /** The fixed {@code powershell.exe} when this is Windows and it is a regular file, else {@code null}. */
    private static Path powerShellExe() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return null;
        }
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) {
            return null;
        }
        try {
            Path exe = Paths.get(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            return exe.isAbsolute() && Files.isRegularFile(exe) ? exe : null;
        } catch (RuntimeException badRoot) {
            return null;
        }
    }

    /**
     * The helper run on the daemon thread: {@code powershell.exe -NoProfile -NonInteractive -NoLogo
     * -ExecutionPolicy Bypass -Command -}, stdout + stderr discarded, the script written to stdin and
     * the pipe closed; its exit code, or {@code -1} (timed out at {@link #HELPER_TIMEOUT_SECONDS} and
     * destroyed, or any throwable: ONE stderr line naming its class, no message text).
     */
    private static int runHelper(Path exe, Process child) {
        Process helper = null;
        try {
            helper = new ProcessBuilder(exe.toString(), "-NoProfile", "-NonInteractive", "-NoLogo",
                    "-ExecutionPolicy", "Bypass", "-Command", "-")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (OutputStream in = helper.getOutputStream()) {
                in.write(script(child.pid()).getBytes(StandardCharsets.UTF_8));
            }
            if (!helper.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return -1;
            }
            return helper.exitValue();
        } catch (Throwable failed) {
            System.err.println("serve-launcher warning: the timer opt-out helper failed ("
                    + failed.getClass().getSimpleName() + ")");
            return -1;
        } finally {
            if (helper != null && helper.isAlive()) {
                helper.destroyForcibly();
            }
            Reference.reachabilityFence(child);
        }
    }

    /** The three-line helper script, {@code \r\n} line ends; {@code pid} is a Windows pid (a DWORD, > 0). */
    static String script(long pid) {
        return "$src = '" + CSHARP + "'\r\n"
                + "Add-Type -TypeDefinition $src\r\n"
                + "exit [Cc.ServeTimerOptOut]::Apply(" + pid + ")\r\n";
    }
}
