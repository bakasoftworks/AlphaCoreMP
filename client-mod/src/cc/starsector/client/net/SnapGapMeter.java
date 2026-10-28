package cc.starsector.client.net;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The
 * wire-stall INSTRUMENT: per-SNAP-datagram inter-arrival gaps on the reader thread, bucketed.
 * Design = one section of the interpolation-smoother design (the only part
 * of that design kept; the smoother itself is CLOSED by a standing rule: no foe
 * delay, ever). ZERO behavior change: nothing reads this but log rows.
 *
 * <p>WHY a gap histogram and not the existing gauges: one test flight carried 13 wire
 * stalls of 68–145 ms while {@code jitter_ewma_ms} read 1.56 (an α=0.1 EWMA over 60 arrivals/s
 * smooths a 6-tick hole to noise) and {@code reader_gap_max_ms} is pinned by one cold-start
 * outlier for the flight. The 33–67 ms class ({@code foe_skip_frames} minus the SUSPEND-class
 * stalls ≈ one per ~7 s) was never measured at all; the owner's next hairpin flight sizes it
 * off this row.
 *
 * <p>What is clocked: {@code gap = returned − lastSnapReturned} over SNAP-channel frames only
 * (voice/ctrl/ack never clock it — a CTRL burst between two SNAPs is not a stall). Kept:
 * (a) cumulative counts per ms bucket {@code ge33/50/67/100/150/250} (each bucket counts every
 * gap ≥ its edge, so {@code ge67 − ge100} = the 67–100 class); (b) the last ≤ {@link #STALL_CAP}
 * wire-stall ends {@code (endNanos, gapNanos)} for gaps ≥ the stall bar (knob
 * {@code -Dcc.client.snapStallMs}, default {@link #DEFAULT_STALL_BAR_MS}) — the F9 row lists
 * the ones inside its trailing horizon with their AGE at the press; (c) the LOCAL-stall
 * discriminator: a gap that the reader's OWN out-of-{@code receive()} time covers (≥ half of
 * it) is decode/delivery/STW on THIS box, not the wire — counted apart ({@code local_ge33} +
 * its maxima), never in the wire buckets or the deque. Limit, stated: a STW pause that lands
 * while the thread is BLOCKED in {@code receive()} stamps {@code returned} after the pause and
 * reads as a wire gap here — the GC witness on the same marks is the tie-breaker.
 * (d) {@code ge1000} = gaps ≥ 1 s, counted alone and kept OUT of the buckets and the deque: a
 * round flip / lobby sit widens the SNAP lane legitimately and would otherwise pin
 * {@code ge250} — read beside the traffic, like {@code reader_gap_max_ms}.
 *
 * <p>Threads: the reader thread is the ONLY writer ({@link #onReaderBusy}, {@link #onSnapReturned},
 * {@link #reset}); the mark/teardown threads read. Hot path per SNAP = one subtraction, one
 * compare against the lowest edge, two plain-field writes and one volatile increment; the
 * atomics and the deque lock are touched only at a gap ≥ 33 ms (rare by construction) and at
 * a take. {@link #reset} rides the reader's receive-failure path AND its re-admission WELCOME
 * (a blackout the socket never faulted on must not clock as one giant gap). Off-frame by residency (the reader thread).
 * Numbers only, no player text; B-clean (java.util + atomics only). Game-free
 * rows = {@code SnapGapMeterSelftest} (client build suite).
 */
public final class SnapGapMeter {
    /** Bucket edges, ms — tick-agnostic (the bar is a knob, the buckets are ms). */
    public static final int[] BUCKET_MS = {33, 50, 67, 100, 150, 250};
    /** Gaps ≥ this are the idle/round-flip class (d): counted alone, never bucketed. */
    public static final long IDLE_BAR_MS = 1000L;
    /** Deque capacity — the last N wire stalls ≥ the bar. */
    public static final int STALL_CAP = 32;
    /** Default stall bar (ms) = the SUSPEND-class floor measured on 200417 (68 ms). */
    public static final int DEFAULT_STALL_BAR_MS = 67;
    /** Knob: {@code -Dcc.client.snapStallMs=<ms>} — the deque admission bar. */
    public static final String STALL_BAR_PROP = "cc.client.snapStallMs";

    private static final long NANOS_PER_MS = 1_000_000L;

    private final long stallBarNanos;
    private final AtomicLongArray wireGe = new AtomicLongArray(BUCKET_MS.length);
    private final AtomicLong idleGe1000 = new AtomicLong();
    private final AtomicLong localGe33 = new AtomicLong();
    private final AtomicLong wireMaxNanos = new AtomicLong();
    private final AtomicLong wireWindowMaxNanos = new AtomicLong();
    private final AtomicLong localMaxNanos = new AtomicLong();
    private final AtomicLong localWindowMaxNanos = new AtomicLong();
    private final ArrayDeque<long[]> stalls = new ArrayDeque<>(STALL_CAP); // guarded by stalls
    private volatile long snapsClocked;

    // reader-thread-only state
    private long lastSnapReturnedNanos;
    private long busySinceSnapNanos;

    /** The session's meter: bar from the knob (default {@link #DEFAULT_STALL_BAR_MS}); an
     * unparseable value falls back to the default (an instrument never fails a connect). */
    public SnapGapMeter() {
        this(resolveStallBarMs());
    }

    /** Test seam: an explicit bar (ms). */
    public SnapGapMeter(long stallBarMs) {
        this.stallBarNanos = Math.max(1L, stallBarMs) * NANOS_PER_MS;
    }

    static long resolveStallBarMs() {
        // the literal at the call site (not STALL_BAR_PROP): the knob checker pairs sysprops with
        // the knob registry by this exact shape — a constant-fed read is invisible to its regex.
        String raw = System.getProperty("cc.client.snapStallMs");
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_STALL_BAR_MS;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : DEFAULT_STALL_BAR_MS;
        } catch (NumberFormatException e) {
            return DEFAULT_STALL_BAR_MS;
        }
    }

    /** The resolved deque admission bar (ms). */
    public long stallBarMillis() {
        return stallBarNanos / NANOS_PER_MS;
    }

    // ------------------------------------------------------------------ reader thread

    /** Reader thread, once per loop iteration BEFORE {@code receive()}: the time the reader
     * spent OUT of {@code receive()} since its previous return (the {@code busy}). Summed
     * until the next SNAP return — the local-stall discriminator's numerator. */
    public void onReaderBusy(long busyNanos) {
        if (busyNanos > 0L) {
            busySinceSnapNanos += busyNanos;
        }
    }

    /** Reader thread, at every SNAP-channel frame's {@code receive()} return stamp. */
    public void onSnapReturned(long returnedNanos) {
        long last = lastSnapReturnedNanos;
        lastSnapReturnedNanos = returnedNanos;
        long busy = busySinceSnapNanos;
        busySinceSnapNanos = 0L;
        snapsClocked++;
        if (last == 0L) {
            return; // the first SNAP after birth/reset clocks nothing
        }
        long gap = returnedNanos - last;
        if (gap < BUCKET_MS[0] * NANOS_PER_MS) {
            return; // the common case: on cadence
        }
        if (busy * 2L >= gap) {
            // (c) the reader itself was away for at least half the gap: a LOCAL stall.
            localGe33.incrementAndGet();
            localMaxNanos.accumulateAndGet(gap, Math::max);
            localWindowMaxNanos.accumulateAndGet(gap, Math::max);
            return;
        }
        if (gap >= IDLE_BAR_MS * NANOS_PER_MS) {
            idleGe1000.incrementAndGet(); // (d) the idle/round-flip class, kept out of the buckets
            return;
        }
        for (int i = 0; i < BUCKET_MS.length; i++) {
            if (gap >= BUCKET_MS[i] * NANOS_PER_MS) {
                wireGe.incrementAndGet(i);
            }
        }
        wireMaxNanos.accumulateAndGet(gap, Math::max);
        wireWindowMaxNanos.accumulateAndGet(gap, Math::max);
        if (gap >= stallBarNanos) {
            synchronized (stalls) {
                if (stalls.size() >= STALL_CAP) {
                    stalls.pollFirst(); // drop-oldest
                }
                stalls.addLast(new long[] {returnedNanos, gap});
            }
        }
    }

    /** Reader thread: a receive failure / re-admission episode is not a SNAP gap — the next
     * SNAP starts a fresh clock. Counters and the deque are kept (they are the session's record). */
    public void reset() {
        lastSnapReturnedNanos = 0L;
        busySinceSnapNanos = 0L;
    }

    // ------------------------------------------------------------------ readers (any thread)

    /** SNAP frames clocked so far. */
    public long snapsClocked() {
        return snapsClocked;
    }

    /** Cumulative wire gaps ≥ {@code BUCKET_MS[i]} ms (excludes local and idle-class gaps). */
    public long wireGe(int bucket) {
        return wireGe.get(bucket);
    }

    /** {@link #wireGe(int)} by bucket EDGE (ms) — self-describing at a call site; an edge that
     * is not in {@link #BUCKET_MS} throws (a caller typo, never a silent 0). */
    public long wireGeMs(int edgeMs) {
        for (int i = 0; i < BUCKET_MS.length; i++) {
            if (BUCKET_MS[i] == edgeMs) {
                return wireGe.get(i);
            }
        }
        throw new IllegalArgumentException("no snap-gap bucket at " + edgeMs + " ms");
    }

    /** Cumulative wire gaps ≥ 1 s (the idle/round-flip class; not in the buckets). */
    public long idleGe1000() {
        return idleGe1000.get();
    }

    /** Cumulative LOCAL stalls ≥ 33 ms (the reader's own busy covered ≥ half the gap). */
    public long localGe33() {
        return localGe33.get();
    }

    /** Longest wire gap so far (ms; excludes local + idle). */
    public double wireMaxMillis() {
        return wireMaxNanos.get() / 1e6d;
    }

    /** Longest wire gap SINCE the previous take (ms) — taking it starts the next window
     * (the {@code reader_gap_win_max_ms} idiom). */
    public double takeWireWindowMaxMillis() {
        return wireWindowMaxNanos.getAndSet(0L) / 1e6d;
    }

    /** Longest local stall so far (ms). */
    public double localMaxMillis() {
        return localMaxNanos.get() / 1e6d;
    }

    /** Longest local stall SINCE the previous take (ms). */
    public double takeLocalWindowMaxMillis() {
        return localWindowMaxNanos.getAndSet(0L) / 1e6d;
    }

    /** Snapshot of the deque, oldest first: {@code {endNanos, gapNanos}} rows. */
    public long[][] stallsSnapshot() {
        synchronized (stalls) {
            return stalls.toArray(new long[0][]);
        }
    }

    /**
     * The F9 row's stall list: every deque entry that ended within {@code horizonNanos} before
     * {@code nowNanos}, newest first, as {@code n[age_s:gap_ms,…]} — {@code n} = the count inside
     * the horizon AMONG THE ≤ {@link #STALL_CAP} KEPT stalls (a storm past 32 stalls in one
     * horizon undercounts here; the bucket deltas on the same row carry the full count), the
     * list capped at {@code maxShown} (the count still says how many were kept).
     * {@code 0[]} when none. Ages/gaps to one decimal / whole ms; nothing else is printed.
     */
    public String recentStallsToken(long nowNanos, long horizonNanos, int maxShown) {
        long[][] rows = stallsSnapshot();
        StringBuilder sb = new StringBuilder();
        int inside = 0;
        int shown = 0;
        for (int i = rows.length - 1; i >= 0; i--) {
            long age = nowNanos - rows[i][0];
            if (age < 0L || age > horizonNanos) {
                continue;
            }
            inside++;
            if (shown < maxShown) {
                if (shown > 0) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.ROOT, "%.1fs:%d", age / 1e9d, rows[i][1] / NANOS_PER_MS));
                shown++;
            }
        }
        return inside + "[" + sb + "]";
    }

    /** The cumulative histogram as row tokens ({@code snap_gap_ge33=…} … {@code snap_gap_ge1000=…}
     * {@code snap_gap_local_ge33=…}), leading space, no deltas — the periodic flush / teardown shape. */
    public String cumulativeTokens() {
        StringBuilder sb = new StringBuilder(96);
        for (int i = 0; i < BUCKET_MS.length; i++) {
            sb.append(" snap_gap_ge").append(BUCKET_MS[i]).append('=').append(wireGe.get(i));
        }
        sb.append(" snap_gap_ge1000=").append(idleGe1000.get());
        sb.append(" snap_gap_local_ge33=").append(localGe33.get());
        return sb.toString();
    }
}
