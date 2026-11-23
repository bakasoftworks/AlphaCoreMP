package cc.starsector.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The sidelog's write gate. ONE boot-wide character bucket bounds
 * how fast rows reach the file, and an in-flight count keeps the async ring from ever filling, so a row
 * flood can neither restart the capped file at line rate nor park the thread that logs it.
 *
 * <p><b>Why.</b> Every {@link PilotWatchLog} row reaches log4j 1.2.9's {@code AsyncAppender}, a 4,096-slot
 * ring whose {@code append} WAITS while the ring is full (1.2.9 has no non-blocking mode; the caller is
 * dominantly the render thread), and nothing bounded the row rate: a released joiner already writes about
 * 400 MB an hour, and a host able to drive a per-event row could write at line rate. The gate is ALL state
 * here, never a {@link PilotWatchLog} static: that class's attach rows run inside its own static init,
 * before its logger field is set.
 *
 * <p><b>The bucket.</b> A GCRA in one {@code AtomicLong} (the theoretical arrival time, TAT): one CAS per
 * admitted row, boot-wide, never reset. A row is charged its EXACT {@code msg.length()} in chars, BEFORE it
 * is rendered. Empty = the row is dropped and counted ({@link #globalRefused()}): never rendered, never
 * logged, never re-routed to {@code System.out} (that would move the flood into the game's own log). The
 * first refusal of a boot says ONE {@code System.err} line. Priority rows skip the bucket: they are the two
 * sidelog attach rows and the quit summary row, three a boot, none peer-driven, and a drained bucket must
 * never hide its own evidence.
 *
 * <p><b>The ring guard.</b> While the async sidelog is live a row reserves a slot ({@link #reserveRing})
 * before it reaches log4j and the file appender behind the ring gives it back in a {@code finally}
 * ({@link #releaseRing}; {@link CappedSidelogAppender}, {@link CountingFileAppender}). The decrement runs
 * after the Dispatcher's pop, so the count is never below the ring's occupancy: a row admitted under
 * {@link #RING_HARD} always finds a free slot and no admit path ever waits. Over the limit = dropped and
 * counted; the first PRIORITY drop of a boot (a Dispatcher that stopped draining) says ONE
 * {@code System.err} line. Nothing resets the count.
 *
 * <p>Classloader-B law: names no {@code java.io} file type and no {@code java.lang.reflect}
 * type ({@code System.err}'s {@code PrintStream} only).
 */
final class LogRowGateLaw {

    /**
     * The bucket's depth: 33,554,432 chars (32 Mi, a third of the default sidelog's 96 MiB-of-chars cap) =
     * 64 s of refill. It absorbs a p99-level excursion of the heaviest recorded window for minutes.
     */
    static final long GLOBAL_BURST_CHARS = 33_554_432L;

    /**
     * The refill: 524,288 chars a second (512 Ki). The default sidelog was measured at 6.0-7.3 MB a minute =
     * 98-119 KiB/s mean ({@link SidelogCapLaw}'s record), so this is 4.3x that mean (524,288 / 122,000), and it
     * sits above the MEAN of every recorded verbose watch window (169-285 KiB/s): honest play is never
     * refused. A flood held at the refill restarts the 96 MiB file about every 3.2 min (96 Mi / 512 Ki =
     * 192 s) instead of the honest 13-16 min.
     */
    static final long GLOBAL_REFILL_CHARS_PER_SEC = 524_288L;

    /** Time runs in 1/1024 ns units: the clock's nanos shifted left by this. */
    static final int UNIT_SHIFT = 10;

    /**
     * One char of refill in 1/1024 ns units: 1e9 / 524,288 ns = 1,953,125 / 1,024 ns EXACTLY (the refill is
     * 2^19), so a char costs 1,953,125 units with no rounding and the burst is exact to the char.
     */
    static final long UNITS_PER_CHAR = (1_000_000_000L << UNIT_SHIFT) / GLOBAL_REFILL_CHARS_PER_SEC;

    /** The burst in units: 6.5536e13, a 64 s horizon. */
    private static final long BURST_UNITS = GLOBAL_BURST_CHARS * UNITS_PER_CHAR;

    /** The async ring's length ({@code setBufferSize}); the guard keeps the in-flight count under it. */
    static final int RING_SLOTS = 4_096;

    /** An ordinary row is admitted while the in-flight count stays at or under this (7/8 of the ring). */
    static final int RING_SOFT = 3_584;

    /** A priority row's limit: 64 below the ring, the headroom for concurrent over-limit reservers. */
    static final int RING_HARD = 4_032;

    /** The process-wide gate {@link PilotWatchLog} and the two file appenders share. */
    static final LogRowGateLaw BOOT = new LogRowGateLaw(System::nanoTime);

    private final LongSupplier nanos;
    /** The theoretical arrival time in units; compared by difference only, so a clock wrap is harmless. */
    private final AtomicLong tat;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong globalRefused = new AtomicLong();
    private final AtomicLong ringDropped = new AtomicLong();
    private final AtomicLong ringDroppedPriority = new AtomicLong();
    /** {@link PilotWatchLog}'s emit faults (a throwing gate clock or a dead stream): counted, never thrown. */
    private final AtomicLong emitFaults = new AtomicLong();
    private final AtomicBoolean refusalSaid = new AtomicBoolean();
    private final AtomicBoolean priorityDropSaid = new AtomicBoolean();

    /**
     * @param nanos a monotonic nanosecond clock (any sign, any origin; {@code System::nanoTime} in product,
     *              an injected one in the selftest). The bucket starts FULL.
     */
    LogRowGateLaw(LongSupplier nanos) {
        this.nanos = nanos;
        this.tat = new AtomicLong(nanos.getAsLong() << UNIT_SHIFT);
    }

    /**
     * Charge {@code chars} (≥ 0; a negative counts as 0) to the bucket. True = admitted (the TAT advanced by
     * one CAS); false = refused and counted, the TAT untouched. A refusal costs one clock read, one volatile
     * read and one counter increment, and never allocates. Wrap-safe while rows arrive at least once in
     * about 104 days (2^63 units).
     */
    boolean admitGlobal(int chars) {
        long cost = Math.max(0, chars) * UNITS_PER_CHAR;
        long now = nanos.getAsLong() << UNIT_SHIFT;
        for (;;) {
            long t = tat.get();
            long next = (t - now > 0L ? t : now) + cost;
            if (next - now > BURST_UNITS) {
                globalRefused.incrementAndGet();
                if (!refusalSaid.get() && refusalSaid.compareAndSet(false, true)) {
                    say("log-gate: a row over the sidelog rate bound was dropped; later ones are dropped and"
                            + " counted, the totals print at quit");
                }
                return false;
            }
            if (tat.compareAndSet(t, next)) {
                return true;
            }
        }
    }

    /** Reserve one in-flight ring slot. True = reserved (the caller's logger call or a file appender's
     *  {@code finally} gives it back); false = over the limit, dropped and counted. */
    boolean reserveRing(boolean priority) {
        int n = inFlight.incrementAndGet();
        if (n <= (priority ? RING_HARD : RING_SOFT)) {
            return true;
        }
        inFlight.decrementAndGet();
        if (priority) {
            ringDroppedPriority.incrementAndGet();
            if (!priorityDropSaid.get() && priorityDropSaid.compareAndSet(false, true)) {
                say("log-gate: a priority row found the sidelog ring full (its writer stopped draining); later"
                        + " drops are counted");
            }
        } else {
            ringDropped.incrementAndGet();
        }
        return false;
    }

    /** Give one slot back: the file appenders' {@code finally}, and emit's undo when its logger threw. */
    void releaseRing() {
        inFlight.decrementAndGet();
    }

    void emitFault() {
        emitFaults.incrementAndGet();
    }

    /** The quit summary row's text (fixed; counts only, no peer text). */
    String summaryRow() {
        return "log-gate summary global_refused=" + globalRefused.get() + " ring_dropped=" + ringDropped.get()
                + " ring_dropped_priority=" + ringDroppedPriority.get();
    }

    int inFlight() {
        return inFlight.get();
    }

    long globalRefused() {
        return globalRefused.get();
    }

    long ringDropped() {
        return ringDropped.get();
    }

    long ringDroppedPriority() {
        return ringDroppedPriority.get();
    }

    long emitFaults() {
        return emitFaults.get();
    }

    private static void say(String line) {
        try {
            System.err.println(PilotWatchLog.TAG + line);
        } catch (Throwable ignore) {
            // System.err itself failed: there is nowhere left to say it (the counters still hold the drops)
        }
    }
}
