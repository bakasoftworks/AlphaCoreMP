package cc.starsector.server.runtime;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;

/**
 * The in-serve JFR "block-catcher": ONE always-on, low-cost Flight Recorder
 * recording per serve that captures ONLY the events which NAME an off-CPU wait on the sim thread
 * (and the safepoint/sample context to tell the classes apart), so the NEXT zero-CPU stall row
 * ({@code work_frame_stall … adv_eng=235 adv_cpu=0.00}) grades itself instead of opening a
 * session of offline guesswork.
 *
 * <p>WHY: three zero-CPU in-advance stalls on record were read as one class and were two.
 * The rig's was safepoint time-to-safepoint (a starved sibling; rig-only). The
 * live-host flights' 126/238 ms waits are NOT TTSP (safepoint log µs), NOT GC, NOT stdout (lane
 * ON, 0 fallbacks), NOT class loading (CPU-bearing) — a monitor held by a sibling, a jar/data read,
 * a hard page fault or a DPC storm are all still open and NONE is discriminable from the rows.
 * JFR's {@code JavaMonitorEnter}/{@code JavaMonitorWait}/{@code ThreadPark}/{@code File*}/
 * {@code Socket*} duration events carry the waiting stack + the monitor class/path; sampling
 * proves preemption (samples present, {@code adv_cpu≈0}); {@code SafepointBegin} proves TTSP;
 * nothing at all ⇒ VM-internal. Reader = the JFR row reader (joins
 * {@code wall_ms=} rows to the events on {@code main}).
 *
 * <p>RESIDENCY: the sim thread never writes here — JFR's own recorder thread owns the
 * chunk I/O; the threshold events cost nothing until a wait actually exceeds 10 ms; sampling is
 * 50 Hz across the process (the JDK's stock {@code default} profile, documented &lt;1 %). The
 * in-flight chunk repository is the JVM flag {@code -XX:FlightRecorderOptions=repository=…} —
 * the launch wrapper points it at the run dir (HDD; DISK LAW) and this class prints the readback
 * so a run on the SSD default is visible in its own log, never assumed.
 *
 * <p>Contract: {@link #startForServe} is fail-soft (null + a {@code jfr_blocks state=fail} row —
 * a serve never dies for its instrumentation); {@link #stopAndDump} in the serve's teardown
 * {@code finally} writes the destination and prints the {@code jfr_blocks state=dumped file=…
 * bytes=…} receipt (every mode printed, none narrated). Knob
 * {@code cc.jfr.blocks} = destination path; blank or {@code off} = OFF (one row, nothing armed).
 */
public final class ServeBlockRecorder {

    /** The OFF sentinel accepted in place of a destination path (case-insensitive). */
    public static final String OFF = "off";

    /** Duration events that NAME a wait: recorded with stack when they exceed this. */
    static final Duration BLOCK_THRESHOLD = Duration.ofMillis(10);
    /** Sampling period for the on-CPU/native samples (the JDK default-profile cadence). */
    static final Duration SAMPLE_PERIOD = Duration.ofMillis(20);
    /** Class loads slower than this are worth a stack (cheap first-touch loads stay silent). */
    static final Duration CLASS_LOAD_THRESHOLD = Duration.ofMillis(5);
    /** Disk bound for the destination (a 2 h flight at 50 Hz sampling ≈ 40 MB). */
    static final long MAX_BYTES = 256L * 1024 * 1024;
    /**
     * Blind spots closed: {@code jdk.ThreadSleep} at threshold ZERO (a sleep on
     * the sim thread of ANY length is a bug worth its stack — the candidate) and
     * {@code jdk.Compilation} above this (a co-timed big C2 compile was on-CPU the whole 400 ms
     * window; compiler-thread event, no stack, context only).
     */
    static final Duration COMPILATION_THRESHOLD = Duration.ofMillis(100);

    private static final String[] BLOCK_EVENTS = {
        "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark",
        "jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite",
    };

    private final Recording recording;
    private final Path destination;
    private boolean dumped;

    private ServeBlockRecorder(Recording recording, Path destination) {
        this.recording = recording;
        this.destination = destination;
    }

    /**
     * Arm the recording toward {@code destinationPath}; null when OFF or on any failure (both
     * printed). Never throws — JFR absent/disabled, an unusable path, or a disk error all land
     * as a {@code state=fail reason=…} row.
     */
    public static ServeBlockRecorder startForServe(String destinationPath) {
        if (isOff(destinationPath)) {
            System.out.println("npeer-serve jfr_blocks state=off");
            return null;
        }
        Path dest = null;
        Recording recording = null;
        try {
            dest = Path.of(destinationPath.trim()).toAbsolutePath();
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            recording = new Recording(); // no template: NOTHING enabled but the list below
            recording.setName("cc-serve-blocks");
            recording.setToDisk(true);
            recording.setDumpOnExit(true); // a SIGTERM/normal exit still lands the file
            recording.setDestination(dest);
            recording.setMaxSize(MAX_BYTES);
            for (String event : BLOCK_EVENTS) {
                recording.enable(event).withThreshold(BLOCK_THRESHOLD).withStackTrace();
            }
            recording.enable("jdk.ExecutionSample").withPeriod(SAMPLE_PERIOD).withStackTrace();
            recording.enable("jdk.NativeMethodSample").withPeriod(SAMPLE_PERIOD).withStackTrace();
            recording.enable("jdk.SafepointBegin").withThreshold(Duration.ZERO);
            recording.enable("jdk.ClassLoad").withThreshold(CLASS_LOAD_THRESHOLD).withStackTrace();
            recording.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO).withStackTrace();
            recording.enable("jdk.Compilation").withThreshold(COMPILATION_THRESHOLD);
            // WHY a C2 giant bails
            // (`failureMessage`, joined on compileId to its jdk.Compilation row — the recorded
            // 10 s strafe-maneuver bailout had NO reason on record) and WHY a
            // hot AI method recompiles in a loop (`D.<init>` 9× in 50 s: deopt reason/action +
            // the deopting frame). Instant events, no threshold; both ride the JDK's own
            // `profile` template, so their cost is the documented sampling-profile class.
            recording.enable("jdk.CompilationFailure");
            recording.enable("jdk.Deoptimization").withStackTrace();
            // once-per-chunk metadata: jvmStartTime/recordingStart = the reader's clock anchor
            // (jfr-block-rows.ps1 derives the printed-clock→epoch offset from them; no anchor ⇒
            // it refuses rather than assume zero)
            recording.enable("jdk.JVMInformation");
            recording.enable("jdk.ActiveRecording");
            recording.start();
            System.out.println("npeer-serve jfr_blocks state=started file=" + dest
                    + " repo=" + repositoryReadback() + " id=" + recording.getId());
            return new ServeBlockRecorder(recording, dest);
        } catch (Throwable failSoft) { // NoClassDefFoundError / IllegalStateException / IOException
            System.out.println("npeer-serve jfr_blocks state=fail file=" + dest
                    + " reason=" + oneLine(failSoft));
            if (recording != null) { // Release the half-built recording's JFR slot
                try {
                    recording.close();
                } catch (Throwable ignored) {
                    // nothing left to release after a failed start
                }
            }
            return null;
        }
    }

    /** Blank or {@code off} (as the whole value or the file name — the config-file tier resolves
     *  relative values against its own directory, so {@code off} arrives as {@code <dir>/off}). */
    public static boolean isOff(String destinationPath) {
        if (destinationPath == null || destinationPath.isBlank()) {
            return true;
        }
        String trimmed = destinationPath.trim();
        if (OFF.equalsIgnoreCase(trimmed)) {
            return true;
        }
        Path name = Path.of(trimmed).getFileName();
        return name != null && OFF.equalsIgnoreCase(name.toString());
    }

    /** Stop, write the destination, print the receipt; idempotent; never throws. */
    public void stopAndDump() {
        if (dumped) {
            return;
        }
        dumped = true;
        try {
            recording.stop(); // with a destination set, stop() IS the write
            long bytes = Files.exists(destination) ? Files.size(destination) : -1L;
            System.out.println("npeer-serve jfr_blocks state=dumped file=" + destination
                    + " bytes=" + bytes);
        } catch (Throwable failSoft) {
            System.out.println("npeer-serve jfr_blocks state=dump_fail file=" + destination
                    + " reason=" + oneLine(failSoft));
        } finally {
            try {
                recording.close();
            } catch (Throwable ignored) {
                // close() after a failed stop has nothing left to release
            }
        }
    }

    /** Where this recorder writes (for tests and the receipt). */
    public Path destination() {
        return destination;
    }

    /**
     * The JVM's {@code FlightRecorderOptions} as launched — {@code repository=…} is where JFR
     * spools chunks BEFORE the dump ({@code java.io.tmpdir} = the SSD when unset: DISK LAW
     * visible in the boot row). Readback-only; never a hidden mode.
     */
    static String repositoryReadback() {
        try {
            String value = ManagementFactory
                    .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                    .getVMOption("FlightRecorderOptions").getValue();
            return value == null || value.isBlank() ? "tmpdir-default" : oneLine(value);
        } catch (Throwable unavailable) {
            return "unreadable";
        }
    }

    /** Single-line, bounded text for a log row (one printable line). */
    private static String oneLine(Object o) {
        String s = String.valueOf(o).replaceAll("[\\r\\n\\t]+", " ").trim();
        return s.length() > 240 ? s.substring(0, 240) + "..." : s;
    }
}
