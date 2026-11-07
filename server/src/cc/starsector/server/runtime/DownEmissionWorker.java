package cc.starsector.server.runtime;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.ToLongFunction;

/**
 * The down-emission worker: ONE thread that runs the per-tick emission body (compose, fan the
 * wire sends, record the tick) for the payload the sim thread hands it. WHY: the sim thread keeps
 * only the capture, the minimum coherent engine read; derived compute, encoding, the sends and
 * their logging run beside it, so their cost stops scaling the sim frame.
 *
 * <p><b>Single slot, window law.</b> At most ONE payload is ever in flight. The sim calls
 * {@link #awaitIdle} (the drain barrier) before it touches any state the body owns, and
 * {@link #handOff} drains first itself, so the body of tick N always finishes before the sim's
 * work for tick N+1 touches that state, and bodies run one at a time in hand-off order. A
 * hand-off never overwrites an unconsumed slot, so no tick is dropped or duplicated. State the
 * body owns changes owner under that window, not by copy. Idle means {@code doneSeq == handedSeq}:
 * the sim alone writes {@code handedSeq} (bumped BEFORE the slot write, so {@code doneSeq} can
 * never pass it), the worker alone writes {@code doneSeq}, and that volatile write publishes every
 * plain field the body and the worker wrote (the figures below, and whatever the body owns).
 *
 * <p><b>Latency.</b> The worker parks UNTIMED when the slot is empty and the hand-off
 * unparks it, so a send leaves at hand-off time plus one thread wake. Never {@code parkNanos}: a
 * timed park on Windows rounds up to the 15.625 ms scheduler quantum. The barrier spins a BOUNDED
 * time ({@link Thread#onSpinWait}) and then parks behind a volatile waiter reference the worker
 * unparks right after its {@code doneSeq} write: an unbounded spin at the sim's MAX priority can
 * starve a preempted worker of its core, and burns a core for nothing in an unpaced
 * re-simulation. No lost wake-up: the waiter publishes itself,
 * re-checks {@code doneSeq}, parks, and re-checks after every return from park; the worker writes
 * {@code doneSeq} and then reads the waiter (two volatile pairs, so one side always sees the other).
 *
 * <p><b>Fault latch.</b> A throw out of the body (or the tick function) is latched with its tick and
 * the worker stops calling the body. The FIRST barrier, hand-off or {@link #close} after it rethrows
 * the SAME instance on the calling thread (an {@link IOException}, {@link RuntimeException} or
 * {@link Error} as itself; any other throwable wrapped in an {@code IOException}) with a stackless
 * {@link SurfacedAt} added as suppressed, naming the fault tick, the surfacing site and the worker
 * thread. The serve then takes its ordinary exit path. After that a hand-off throws
 * {@link IllegalStateException}; a barrier returns normally (the worker is idle, so the exclusion
 * still holds and a cleanup path in a {@code finally} never masks the primary throw).
 * {@link #faultPending} lets the sim rethrow early, before it starts its own tail of the frame.
 *
 * <p><b>Liveness.</b> A barrier never hangs on a dead worker or an interrupted sim: every 1,024
 * spins and after every return from park it checks the worker is alive (an
 * {@code emit_worker_dead} {@link IOException}) and the caller's interrupt status (re-interrupted,
 * then an {@code IOException}). A worker that dies unparks the waiter on its way out.
 *
 * <p><b>Stdout is unmetered here.</b> The worker never installs the serve's per-thread stdout I/O
 * meter ({@link ServeStdoutIoMeter}): that meter accounts exactly ONE thread per JVM, and an install
 * from this thread would re-aim the sim thread's {@code io_*} brackets at the worker. Its rows (the
 * readback, {@code emit_stall}, the join timeout) are rare and unmetered by design.
 *
 * <p><b>Engine-free.</b> The class references no engine class and no bridge class (a contract row
 * reads its constant pool), so the thread stays outside the one-owner engine rule; generic over its
 * payload, it compiles with no reference to what the serve emits.
 *
 * <p><b>Residency declaration owed by the wiring.</b> Instantiating this in a serve adds a thread to
 * the serve-path census: its census row and a comment line in the sim-residency allow-list ride
 * with the change that wires it (the try-with-resources beside the door, the frame-top and round-end
 * drains, the per-tick hand-off, the tripwires).
 *
 *
 * <p>Threads: {@link #handOff}, {@link #awaitIdle}, {@link #requireIdle}, {@link #busy},
 * {@link #faultPending}, {@link #close} and the figure getters are SIM-side (one calling thread);
 * the figures are coherent only after a barrier returned. The body runs on the worker thread.
 *
 * <p>
 *
 * @param <T> the per-tick payload (a value copy the sim hands over; never {@code null})
 */
public final class DownEmissionWorker<T> implements AutoCloseable {

    /** The per-tick emission, run on the worker thread one payload at a time. {@code pickupNanos} =
     * the worker's {@link System#nanoTime} when it took the payload from the slot; the hand-off
     * stamp is {@link #lastHandoffNanos} (wake = pickup minus it). A throw latches (class javadoc). */
    @FunctionalInterface
    public interface Body<T> {
        void emit(T t, long pickupNanos) throws IOException;
    }

    /** The default thread name, the serve's {@code cc-*} house naming. The serve also passes
     * {@link Thread#MAX_PRIORITY} (the sim thread's own priority: a NORM worker would be descheduled
     * behind the sim's siblings at quantum expiry), daemon {@code true}, and its 100 ms frame-stall
     * witness constant as the stall threshold, so no new number is introduced. */
    public static final String DEFAULT_THREAD_NAME = "cc-down-emit";

    /** The default barrier spin bound before the sim parks: 50 µs. Long enough to catch a body
     * finishing a few microseconds late without a park/unpark round trip, short enough that a
     * preempted worker is not starved of its core by the spinning sim. */
    public static final long DEFAULT_SPIN_BOUND_NANOS = 50_000L;

    /** {@link #close}'s join bound: a tick in flight finishes its sends and record well inside it;
     * a hung body costs teardown at most this much, never a hang. */
    public static final long JOIN_MILLIS = 2_000L;

    /** The tick value meaning "none yet" ({@link #lastTick} before the first tick) or "the tick
     * function itself threw" ({@link SurfacedAt#faultTick}). Real ticks are whatever the tick
     * function returns (the serve's sim ticks are >= 0). */
    public static final long UNKNOWN_TICK = Long.MIN_VALUE;

    /** Spins between two liveness / interrupt checks (a power of two: the check is a mask). */
    private static final int LIVENESS_CHECK_SPINS = 1024;

    private final String name;
    private final ToLongFunction<T> tickOf;
    private final Body<T> body;
    private final long stallNanos;
    private final long spinBoundNanos;
    private final Thread thread;

    private volatile T slot;
    private volatile long handedSeq;
    private volatile long doneSeq;
    private volatile Thread waiter;
    private volatile boolean stopping;
    private volatile boolean exited;
    private volatile boolean dieAfterNextBody;
    private volatile Throwable fault;
    private volatile long faultTick = UNKNOWN_TICK;

    /** Sim-written before the {@code handedSeq} and slot writes, so the slot read publishes it. */
    private long handoffNanos;

    // Worker-written figures, published by the doneSeq write.
    private long lastTick = UNKNOWN_TICK;
    private long lastWakeNanos;
    private long lastBusyNanos;
    private long busyNanosTotal;

    // Sim-only state.
    private T lastHanded;
    private boolean faultSurfaced;
    private boolean joined;
    private long waits;
    private long waitMaxNanos;
    private long waitSumNanos;
    private long handoffWaits;
    private long waitParks;

    /**
     * Starts the worker thread and prints its readback row
     * ({@code npeer-serve emit_worker thread= priority= daemon=}), the per-run proof the priority
     * lever applied (Linux ignores Java priorities without a JVM flag + root, a silent no-op).
     *
     * @param threadName     the worker thread's name ({@link #DEFAULT_THREAD_NAME} in the serve)
     * @param priority       its priority, 1..10 ({@link Thread#MAX_PRIORITY} in the serve)
     * @param daemon         its daemon flag ({@code true} in the serve: a hung body never pins the JVM)
     * @param stallNanos     a body busier than this prints one {@code emit_stall} row; > 0
     * @param spinBoundNanos the barrier's spin before it parks, >= 0 ({@link #DEFAULT_SPIN_BOUND_NANOS})
     * @param tickOf         the payload's tick, for the fault latch, the figures and the tripwire
     * @param body           the per-tick emission
     */
    public DownEmissionWorker(String threadName, int priority, boolean daemon, long stallNanos,
            long spinBoundNanos, ToLongFunction<T> tickOf, Body<T> body) {
        this.name = Objects.requireNonNull(threadName, "threadName");
        if (stallNanos <= 0L || spinBoundNanos < 0L) {
            throw new IllegalArgumentException("stallNanos must be > 0 and spinBoundNanos >= 0, got "
                    + stallNanos + " / " + spinBoundNanos);
        }
        this.tickOf = Objects.requireNonNull(tickOf, "tickOf");
        this.body = Objects.requireNonNull(body, "body");
        this.stallNanos = stallNanos;
        this.spinBoundNanos = spinBoundNanos;
        Thread t = new Thread(this::workerMain, threadName);
        t.setDaemon(daemon);
        t.setPriority(priority);
        this.thread = t;
        t.start();
        System.out.println("npeer-serve emit_worker thread=" + t.getName() + " priority=" + t.getPriority()
                + " daemon=" + t.isDaemon());
    }

    /**
     * Hands tick {@code t} to the worker. Drains first ({@code awaitIdle("handoff")}); a drain that
     * had to wait counts {@link #handoffWaits}, which stays 0 when the sim's own frame-top barrier
     * ran first. Stamps {@link #lastHandoffNanos} (the one clock read this costs the sim), bumps
     * {@code handedSeq}, writes the slot, unparks the worker.
     *
     * @throws IllegalStateException after {@link #close}, or after a latched fault surfaced
     * @throws IOException           a latched body fault (first surface), a dead worker, an interrupt
     */
    public void handOff(T t) throws IOException {
        Objects.requireNonNull(t, "payload");
        if (stopping) {
            throw new IllegalStateException(name + " closed");
        }
        if (faultSurfaced) {
            throw new IllegalStateException(name + " faulted at tick " + faultTick + "; no hand-off after the fault surfaced");
        }
        if (awaitIdle("handoff") > 0L) {
            handoffWaits++;
        }
        if (exited) {
            throw new IOException("emit_worker_dead site=handoff");
        }
        lastHanded = t;
        handoffNanos = System.nanoTime();
        handedSeq = handedSeq + 1L;
        slot = t;
        LockSupport.unpark(thread);
    }

    /**
     * The drain barrier: returns once the worker is idle, then surfaces a latched fault (first time
     * only). Fast path = two volatile reads. Slow path = a bounded spin, then an untimed park the
     * worker ends; the wait folds into {@link #waits}, {@link #waitMaxNanos}, {@link #waitSumNanos}.
     *
     * @param site a fixed code token naming the caller, carried into errors and {@link SurfacedAt}
     * @return the waited nanos: 0 on the fast path, else >= 1
     * @throws IOException {@code emit_worker_dead site=…}, an interrupt (status re-set), or a
     *                     latched body fault
     */
    public long awaitIdle(String site) throws IOException {
        if (doneSeq == handedSeq) {
            surfaceFault(site);
            return 0L;
        }
        long start = System.nanoTime();
        for (int spins = 1; doneSeq != handedSeq; spins++) {
            Thread.onSpinWait();
            if ((spins & (LIVENESS_CHECK_SPINS - 1)) == 0) {
                checkLive(site);
            }
            if (System.nanoTime() - start >= spinBoundNanos) {
                parkUntilIdle(site);
                break;
            }
        }
        long waited = Math.max(1L, System.nanoTime() - start);
        waits++;
        waitSumNanos += waited;
        if (waited > waitMaxNanos) {
            waitMaxNanos = waited;
        }
        surfaceFault(site);
        return waited;
    }

    private void parkUntilIdle(String site) throws IOException {
        if (doneSeq == handedSeq) {
            return;
        }
        waitParks++;
        waiter = Thread.currentThread();
        try {
            while (doneSeq != handedSeq) {
                checkLive(site);
                LockSupport.park(this);
            }
        } finally {
            waiter = null;
        }
    }

    private void checkLive(String site) throws IOException {
        if ((exited || !thread.isAlive()) && doneSeq != handedSeq) {
            throw new IOException("emit_worker_dead site=" + site);
        }
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while awaiting " + name);
        }
    }

    private void surfaceFault(String site) throws IOException {
        Throwable f = fault;
        if (f == null || faultSurfaced) {
            return;
        }
        faultSurfaced = true;
        Throwable thrown = f instanceof IOException || f instanceof RuntimeException || f instanceof Error
                ? f : new IOException(name + " body threw " + f.getClass().getName() + " at tick " + faultTick, f);
        thrown.addSuppressed(new SurfacedAt(faultTick, site, name));
        if (thrown instanceof RuntimeException) {
            throw (RuntimeException) thrown;
        }
        if (thrown instanceof Error) {
            throw (Error) thrown;
        }
        throw (IOException) thrown;
    }

    /** Tripwire for state the body owns: throws {@link IllegalStateException} naming the held tick
     * and {@code site} when the worker is not idle. Never waits. */
    public void requireIdle(String site) {
        if (doneSeq != handedSeq) {
            throw new IllegalStateException(site + " touched state owned by " + name + " while it holds tick "
                    + tickOf.applyAsLong(lastHanded));
        }
    }

    /** {@code true} while a handed tick is not yet done (the worker holds the body's state). */
    public boolean busy() {
        return doneSeq != handedSeq;
    }

    /** {@code true} when a body fault is latched and not yet surfaced: the sim may rethrow early
     * (through {@link #awaitIdle}) before its own tail of the frame. */
    public boolean faultPending() {
        return fault != null && !faultSurfaced;
    }

    /**
     * Stops the worker: a tick in flight (or handed and not yet picked up) finishes its body first,
     * then the thread exits; joins at most {@link #JOIN_MILLIS} and prints
     * {@code npeer-serve emit_worker_join_timeout} when the thread outlives that. Throws a fault the
     * sim never surfaced (try-with-resources attaches it as suppressed to a primary throw).
     * Idempotent.
     */
    @Override
    public void close() throws IOException {
        if (!stopping) {
            stopping = true;
            LockSupport.unpark(thread);
            boolean interrupted = false;
            try {
                thread.join(JOIN_MILLIS);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
            joined = !thread.isAlive();
            if (!joined) {
                System.out.println("npeer-serve emit_worker_join_timeout thread=" + name + " join_ms=" + JOIN_MILLIS
                        + " interrupted=" + interrupted);
            }
        }
        surfaceFault("close");
    }

    /** {@code true} once {@link #close} saw the thread exit: only then may teardown release state
     * the body owns (a hung body must never race a release). */
    public boolean joined() {
        return joined;
    }

    /**
     * Test hook only, never called by product code: the worker exits right after its next body
     * returns WITHOUT marking the tick done, i.e. a worker that dies holding a tick. The barrier
     * must report that as {@code emit_worker_dead}, never hang.
     */
    public void dieAfterNextBodyForTest() {
        dieAfterNextBody = true;
    }

    private void workerMain() {
        try {
            loop();
        } finally {
            exited = true;
            Thread w = waiter;
            if (w != null) {
                LockSupport.unpark(w);
            }
        }
    }

    private void loop() {
        for (;;) {
            T t = slot;
            if (t == null) {
                if (stopping) {
                    return;
                }
                LockSupport.park(this);
                continue;
            }
            slot = null;
            long pickup = System.nanoTime();
            long tick = UNKNOWN_TICK;
            if (fault == null) {
                try {
                    tick = tickOf.applyAsLong(t);
                    body.emit(t, pickup);
                } catch (Throwable e) {
                    faultTick = tick;
                    fault = e;
                }
            }
            long busy = System.nanoTime() - pickup;
            if (dieAfterNextBody) {
                return;
            }
            lastTick = tick;
            lastWakeNanos = pickup - handoffNanos;
            lastBusyNanos = busy;
            busyNanosTotal += busy;
            doneSeq = doneSeq + 1L;
            Thread w = waiter;
            if (w != null) {
                LockSupport.unpark(w);
            }
            // After the doneSeq write: the sim never waits on this row's stdout write.
            if (busy > stallNanos) {
                System.out.println("npeer-serve emit_stall tick=" + tick + " busy_ms="
                        + String.format(Locale.ROOT, "%.3f", busy / 1_000_000.0));
            }
        }
    }

    /** The sim's {@link System#nanoTime} at the latest hand-off; the body reads it to compute its
     * wake (pickup minus this). Stable while the body runs: the next hand-off waits for idle. */
    public long lastHandoffNanos() {
        return handoffNanos;
    }

    /** The tick of the latest completed body, {@link #UNKNOWN_TICK} before the first. */
    public long lastTick() {
        return lastTick;
    }

    /** Hand-off stamp to worker pickup for the latest completed tick, nanos. */
    public long lastWakeNanos() {
        return lastWakeNanos;
    }

    /** Body time of the latest completed tick, nanos. */
    public long lastBusyNanos() {
        return lastBusyNanos;
    }

    /** Body time summed over every completed tick, nanos. */
    public long busyNanosTotal() {
        return busyNanosTotal;
    }

    /** Barriers that had to wait (slow path), any site. */
    public long waits() {
        return waits;
    }

    /** The longest single barrier wait, nanos. */
    public long waitMaxNanos() {
        return waitMaxNanos;
    }

    /** All barrier waits summed, nanos. */
    public long waitSumNanos() {
        return waitSumNanos;
    }

    /** Hand-offs whose own drain had to wait: 0 when the sim's frame-top barrier always ran first. */
    public long handoffWaits() {
        return handoffWaits;
    }

    /** Barrier waits that outlasted the spin bound and parked (the {@code emit_wait_parks=} figure). */
    public long waitParks() {
        return waitParks;
    }

    /** The worker thread's name. */
    public String threadName() {
        return name;
    }

    /**
     * Stackless marker added as suppressed to a surfaced fault: the fault tick, the barrier site
     * that surfaced it and the worker thread it was thrown on (a fault's own stack trace does not
     * name its thread).
     */
    public static final class SurfacedAt extends Exception {
        private static final long serialVersionUID = 1L;
        private final long faultTick;
        private final String site;
        private final String workerThread;

        SurfacedAt(long faultTick, String site, String workerThread) {
            super("surfaced_at site=" + site + " fault_tick=" + faultTick + " thrown_on=" + workerThread, null,
                    false, false);
            this.faultTick = faultTick;
            this.site = site;
            this.workerThread = workerThread;
        }

        public long faultTick() {
            return faultTick;
        }

        public String site() {
            return site;
        }

        public String workerThread() {
            return workerThread;
        }
    }
}
