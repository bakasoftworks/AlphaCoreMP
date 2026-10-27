package cc.starsector.server.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * JSTACK-ON-STALL: a sibling daemon that names the sim thread's wait BY
 * JAVA FRAME while the stall is still happening, for the stall class every other instrument is
 * blind to.
 *
 * <p>WHY: the observed F9 stalls are ONE ~320–400 ms
 * user-mode blocking wait of the sim thread inside the engine advance ({@code adv_cpu=0.00},
 * ETW CSwitch {@code UserRequest/Waiting}) — NOT a page fault (0 HardFault machine-wide in the
 * window), NOT preemption, NOT GC/safepoint, and JFR records NOTHING for it: the sampler skips a
 * thread in {@code _thread_blocked}, and no monitor/park/file/socket duration event fires. What
 * fits is a {@code Thread.sleep} on the sim thread, a HotSpot VM-internal wait (class-init
 * placeholder, code-cache/IC transition, a JFR/Threads lock) or a JNI native wait — each of which
 * a stack of the WAITING thread names directly. So: poll the boundary's live step-start stamp
 * off-frame; when the live step exceeds the threshold, take ONE
 * {@link ThreadMXBean#getThreadInfo(long, int)} of the sim thread (a single handshake that
 * returns the stack, {@code Thread.State}, the lock it waits on and the lock owner as one
 * consistent snapshot) and print ONE {@code sim_stall_stack} block; ≤1 block per step.
 *
 * <p>RESIDENCY (the frame-loop law): the sim thread pays two volatile writes per step
 * (published by {@code EngineBackedCombatStepBoundary}) plus two volatile
 * reads and ONE {@code System.nanoTime()} in {@code beginLiveStep} (the window now opens before the
 * pilot apply loop) and NOTHING else. The ONE block per step goes to the first wait
 * past the threshold: a pre-step phase over it spends the block, and a later engine-advance wait in
 * the SAME step then goes unnamed (accepted: re-arming costs a sim-thread write). The stack walk
 * runs on this daemon; a sim thread blocked in the VM or in native is handshake-safe (the
 * sampler thread walks it for free), a running one answers at its next safepoint poll (~µs).
 * The poll is a 20 ms timed park (the {@code cc-ops-writer} cadence a scan proved
 * wakes within 0.5 ms on this box); the block prints through the decoupled stdout lane as ONE
 * println (one chunk ⇒ contiguous rows, never interleaved). Bounded: at most {@link #MAX_FRAMES}
 * frames per block, one block per step, the frame text is JVM-generated (never wire-derived).
 *
 * <p>Row grammar (read by an offline log reader, joined to a {@code work_frame_stall}
 * row by {@code wall_ms} inside {@code [wall_ms − total_ms, wall_ms]}):
 * <pre>
 * npeer-serve sim_stall_sampler state=armed threshold_ms=80 poll_ms=20 sim_thread=main
 * npeer-serve sim_stall_stack seq=1 wall_ms=… live_ms=… step_start_wall_ms=… state=WAITING in_native=false
 *     suspended=false lock=… lock_owner=…/… depth=… printed=…
 * npeer-serve sim_stall_frame seq=1 i=0 at=java.lang.Object.wait(Native Method)
 * …
 * npeer-serve sim_stall_sampler state=closed blocks=… polls=…
 * </pre>
 * Every mode is printed, none narrated: {@code state=off} when the knob
 * disables it, {@code state=fail} when the bean is unavailable — a serve never dies for its
 * instrumentation.
 */
public final class SimStallStackSampler implements AutoCloseable {

    /** Default live-step threshold (ms) before a stack is taken; the knob overrides. */
    public static final int DEFAULT_THRESHOLD_MS = 80;
    /** Poll cadence — the proven cc-ops-writer timed-park shape. */
    static final long POLL_NANOS = 20_000_000L;
    /** Frames printed per block (the wait site is at the TOP; the serve loop below is known). */
    static final int MAX_FRAMES = 40;

    private final Thread simThread;
    private final LongSupplier liveStepStartNanos;
    private final long thresholdNanos;
    private final Consumer<String> sink;
    private final ThreadMXBean threads;
    private final Thread poller;
    private volatile boolean running = true;
    // poller-thread-only counters, read at close (after join) — no contention
    private long blocks;
    private long polls;
    private long lastCapturedStart;

    private SimStallStackSampler(Thread simThread, LongSupplier liveStepStartNanos,
            long thresholdNanos, Consumer<String> sink, ThreadMXBean threads) {
        this.simThread = simThread;
        this.liveStepStartNanos = liveStepStartNanos;
        this.thresholdNanos = thresholdNanos;
        this.sink = sink;
        this.threads = threads;
        this.poller = new Thread(this::pollLoop, "cc-sim-stall-stack");
        this.poller.setDaemon(true);
    }

    /**
     * Arm for the serve: {@code thresholdMs ≤ 0} = OFF (one row, nothing armed); any failure to
     * reach the thread bean prints {@code state=fail} and returns null. Never throws.
     */
    public static SimStallStackSampler startForServe(Thread simThread,
            LongSupplier liveStepStartNanos, int thresholdMs) {
        return start(simThread, liveStepStartNanos, thresholdMs, System.out::println);
    }

    /** The injectable-sink form (contract rows pass a collector; public for the tests package). */
    public static SimStallStackSampler start(Thread simThread, LongSupplier liveStepStartNanos,
            int thresholdMs, Consumer<String> sink) {
        if (thresholdMs <= 0) {
            sink.accept("npeer-serve sim_stall_sampler state=off threshold_ms=" + thresholdMs);
            return null;
        }
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            SimStallStackSampler sampler = new SimStallStackSampler(simThread, liveStepStartNanos,
                    thresholdMs * 1_000_000L, sink, bean);
            sampler.poller.start();
            sink.accept("npeer-serve sim_stall_sampler state=armed threshold_ms=" + thresholdMs
                    + " poll_ms=" + (POLL_NANOS / 1_000_000L) + " max_frames=" + MAX_FRAMES
                    + " sim_thread=" + simThread.getName() + " sim_tid=" + simThread.getId());
            return sampler;
        } catch (Throwable failSoft) {
            sink.accept("npeer-serve sim_stall_sampler state=fail reason=" + oneLine(failSoft));
            return null;
        }
    }

    private void pollLoop() {
        while (running) {
            LockSupport.parkNanos(POLL_NANOS);
            polls++;
            long start = liveStepStartNanos.getAsLong();
            if (start == 0L || start == lastCapturedStart) {
                continue; // between steps, or this step already has its one block
            }
            long liveNanos = System.nanoTime() - start;
            if (liveNanos < thresholdNanos) {
                continue;
            }
            lastCapturedStart = start;
            try {
                capture(liveNanos);
            } catch (Throwable failSoft) { // an instrument never takes the serve down
                sink.accept("npeer-serve sim_stall_stack seq=" + (blocks + 1)
                        + " state=capture_fail reason=" + oneLine(failSoft));
            }
        }
    }

    private void capture(long liveNanos) {
        long wallMs = System.currentTimeMillis();
        ThreadInfo info = threads.getThreadInfo(simThread.getId(), MAX_FRAMES);
        blocks++;
        StringBuilder block = new StringBuilder(2048);
        block.append(String.format(Locale.ROOT,
                "npeer-serve sim_stall_stack seq=%d wall_ms=%d live_ms=%.2f step_start_wall_ms=%d",
                blocks, wallMs, liveNanos / 1_000_000.0d, wallMs - liveNanos / 1_000_000L));
        if (info == null) {
            block.append(" state=GONE depth=0 printed=0");
            sink.accept(block.toString());
            return;
        }
        StackTraceElement[] frames = info.getStackTrace();
        String lock = info.getLockName();
        String owner = info.getLockOwnerName();
        block.append(" state=").append(info.getThreadState())
                .append(" in_native=").append(info.isInNative())
                .append(" suspended=").append(info.isSuspended())
                .append(" lock=").append(lock == null ? "none" : oneLine(lock))
                .append(" lock_owner=").append(owner == null ? "none" : oneLine(owner))
                .append('/').append(info.getLockOwnerId())
                .append(" depth=").append(frames.length)
                .append(" printed=").append(Math.min(frames.length, MAX_FRAMES));
        for (int i = 0; i < frames.length && i < MAX_FRAMES; i++) {
            block.append("\nnpeer-serve sim_stall_frame seq=").append(blocks)
                    .append(" i=").append(i).append(" at=").append(oneLine(frames[i]));
        }
        sink.accept(block.toString());
    }

    /** Blocks printed so far (poller-owned; exact after {@link #close}). */
    public long blocks() {
        return blocks;
    }

    /** Stop polling, join the daemon, print the receipt; idempotent; never throws. */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        LockSupport.unpark(poller);
        try {
            poller.join(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sink.accept("npeer-serve sim_stall_sampler state=closed blocks=" + blocks
                + " polls=" + polls + " poller_alive=" + poller.isAlive());
    }

    /** Single-line, bounded text for a log row (one printable line). */
    private static String oneLine(Object o) {
        String s = String.valueOf(o).replaceAll("[\\r\\n\\t]+", " ").trim();
        return s.length() > 240 ? s.substring(0, 240) + "..." : s;
    }
}
