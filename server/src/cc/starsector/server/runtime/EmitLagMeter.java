package cc.starsector.server.runtime;

import java.util.Locale;

/**
 * How late each tick's DOWN send lands, measured on whatever thread runs the emission — the
 * receipt behind the never-add-latency law: moving the compose and fan of tick N
 * off the sim thread is allowed only if the wire send of tick N lands no later than it did
 * on-thread, and that is PROVED from these figures on an A/B rig, never asserted.
 *
 * <p>Per fanned tick (a tick with at least one live session; a zero-session tick sends nothing
 * and records nothing) four figures, all from clock stamps the serve already reads — this
 * meter adds no {@code System.nanoTime()} of its own:
 * <ul>
 *   <li><b>lag</b> = fan end − capture end: how long the captured world waited to hit the wire;</li>
 *   <li><b>phase</b> = fan end − the frame's due instant (the pacer's absolute deadline on a
 *       paced frame, else the frame's work start): the player-facing figure, comparable across
 *       runs because paced runs share absolute deadlines. Kept in TWO classes — SLACK (the
 *       frame slept to its deadline) and DEBT (it did not: the pacer was already behind) — so a
 *       debt-heavy run cannot hide a slack regression inside one mixed distribution;</li>
 *   <li><b>comp</b> = wire-stage end − pickup: the compose cost ahead of the send;</li>
 *   <li><b>wake</b> = pickup − hand-off: zero when the emitting thread is the capturing thread.</li>
 * </ul>
 *
 * <p>Each figure lands in a two-tier histogram: 1 µs buckets over [0, 4.096 ms) — a µs wake
 * must stay resolvable — then 64 µs buckets up to 266.24 ms, then one overflow count; count,
 * sum and max are exact. A percentile reads as its bucket's upper bound clamped to the exact
 * max, so it never exceeds an observed value and an all-zero figure reads 0. Single writer:
 * the emitting thread; the round line reads it only once that thread is idle.
 *
 *
 */
public final class EmitLagMeter {

    /** Fine tier: 4,096 buckets of 1 µs. */
    public static final int FINE_BUCKETS = 4096;
    public static final long FINE_BUCKET_NANOS = 1_000L;
    /** Coarse tier: 4,096 buckets of 64 µs starting where the fine tier ends. */
    public static final int COARSE_BUCKETS = 4096;
    public static final long COARSE_BUCKET_NANOS = 64_000L;
    public static final long FINE_SPAN_NANOS = FINE_BUCKETS * FINE_BUCKET_NANOS;
    public static final long COARSE_END_NANOS =
            FINE_SPAN_NANOS + COARSE_BUCKETS * COARSE_BUCKET_NANOS;

    private final Histogram lag = new Histogram();
    private final Histogram phaseSlack = new Histogram();
    private final Histogram phaseDebt = new Histogram();
    private final Histogram comp = new Histogram();
    private final Histogram wake = new Histogram();
    private long ticks;
    private long debtTicks;

    /**
     * Record one emitted tick. {@code fanCount == 0} records nothing (nothing reached a wire).
     * Every stamp is a {@code System.nanoTime()} value the caller already holds; a difference
     * below zero (a pacer wake ahead of its deadline) is recorded as 0.
     *
     * @param inDebt          the frame did not sleep to a paced deadline (pacer behind)
     * @param captureEndNanos the engine capture's end (the world the tick carries)
     * @param dueNanos        the instant the frame was due
     * @param handoffNanos    when the tick was handed to the emitting thread
     * @param pickupNanos     when the emitting thread started on it
     * @param wireStageEndNanos when the wire frames were ready to send
     * @param fanEndNanos     when the last session's send returned
     */
    public void record(int fanCount, boolean inDebt, long captureEndNanos, long dueNanos,
            long handoffNanos, long pickupNanos, long wireStageEndNanos, long fanEndNanos) {
        if (fanCount <= 0) {
            return;
        }
        ticks++;
        lag.add(fanEndNanos - captureEndNanos);
        if (inDebt) {
            debtTicks++;
            phaseDebt.add(fanEndNanos - dueNanos);
        } else {
            phaseSlack.add(fanEndNanos - dueNanos);
        }
        comp.add(wireStageEndNanos - pickupNanos);
        wake.add(pickupNanos - handoffNanos);
    }

    /** Clear every figure (a new round). */
    public void reset() {
        lag.reset();
        phaseSlack.reset();
        phaseDebt.reset();
        comp.reset();
        wake.reset();
        ticks = 0L;
        debtTicks = 0L;
    }

    public long ticks() {
        return ticks;
    }

    public long debtTicks() {
        return debtTicks;
    }

    public Histogram lag() {
        return lag;
    }

    public Histogram phaseSlack() {
        return phaseSlack;
    }

    public Histogram phaseDebt() {
        return phaseDebt;
    }

    public Histogram comp() {
        return comp;
    }

    public Histogram wake() {
        return wake;
    }

    /**
     * The round-line tokens, appended after the last existing token (append-only grammar: a
     * reader keyed on token names keeps parsing older lines). The emitting thread's own
     * figures come in {@code worker}; an on-thread emission passes {@link WorkerFigures#INLINE}
     * so its line parses exactly like a worker run's.
     */
    public String roundTokens(WorkerFigures worker) {
        StringBuilder b = new StringBuilder(640);
        b.append(" emit_thread=").append(worker.thread())
                .append(" emit_ticks=").append(ticks)
                .append(" emit_debt_ticks=").append(debtTicks);
        lag.appendTokens(b, "emit_lag", true);
        phaseSlack.appendTokens(b, "emit_phase_slack", true);
        phaseDebt.appendTokens(b, "emit_phase_debt", true);
        comp.appendTokens(b, "emit_comp", false);
        wake.appendTokens(b, "emit_wake", true);
        b.append(" emit_waits=").append(worker.waits())
                .append(" emit_wait_max_us=").append(ceilMicros(worker.waitMaxNanos()))
                .append(" emit_wait_sum_ms=")
                .append(String.format(Locale.ROOT, "%.3f", worker.waitSumNanos() / 1e6d))
                .append(" emit_handoff_waits=").append(worker.handoffWaits())
                .append(" emit_busy_pct=")
                .append(String.format(Locale.ROOT, "%.1f", worker.busyPct()))
                .append(" emit_window_violations=").append(worker.windowViolations())
                .append(" emit_inline_ticks=").append(worker.inlineTicks())
                .append(" emit_wait_parks=").append(worker.waitParks());
        return b.toString();
    }

    /** Nanos to µs, rounded UP so a non-zero figure never prints as 0. */
    static long ceilMicros(long nanos) {
        return nanos <= 0L ? 0L : (nanos + 999L) / 1_000L;
    }

    /**
     * The emitting thread's round figures (hand-off waits, busy share, window violations).
     * An on-thread emission has none of these: {@link #INLINE}.
     */
    public record WorkerFigures(String thread, long waits, long waitMaxNanos, long waitSumNanos,
            long handoffWaits, long busyNanos, long roundWallNanos, long windowViolations,
            long inlineTicks, long waitParks) {

        /** The sim thread emits its own ticks: no hand-off, so every worker figure is 0. */
        public static final WorkerFigures INLINE =
                new WorkerFigures("main", 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

        /** Busy time as a percentage of the round's wall time (0 with no wall time). */
        public double busyPct() {
            return roundWallNanos <= 0L ? 0.0d : busyNanos * 100.0d / roundWallNanos;
        }
    }

    /** One two-tier latency histogram (see the class javadoc for the bucket layout). */
    public static final class Histogram {
        private final long[] fine = new long[FINE_BUCKETS];
        private final long[] coarse = new long[COARSE_BUCKETS];
        private long overflow;
        private long count;
        private long sumNanos;
        private long maxNanos;

        void add(long nanos) {
            long v = nanos < 0L ? 0L : nanos;
            if (v < FINE_SPAN_NANOS) {
                fine[(int) (v / FINE_BUCKET_NANOS)]++;
            } else if (v < COARSE_END_NANOS) {
                coarse[(int) ((v - FINE_SPAN_NANOS) / COARSE_BUCKET_NANOS)]++;
            } else {
                overflow++;
            }
            count++;
            sumNanos += v;
            if (v > maxNanos) {
                maxNanos = v;
            }
        }

        void reset() {
            java.util.Arrays.fill(fine, 0L);
            java.util.Arrays.fill(coarse, 0L);
            overflow = 0L;
            count = 0L;
            sumNanos = 0L;
            maxNanos = 0L;
        }

        public long count() {
            return count;
        }

        public long sumNanos() {
            return sumNanos;
        }

        public long maxNanos() {
            return maxNanos;
        }

        public long overflow() {
            return overflow;
        }

        public long fineCount(int bucket) {
            return fine[bucket];
        }

        public long coarseCount(int bucket) {
            return coarse[bucket];
        }

        /**
         * The {@code pct}-th percentile (1..100) as nanos: the upper bound of the bucket
         * holding the ceil(pct% · count)-th smallest sample, clamped to the exact max (an
         * overflow-bucket percentile IS the max — the only honest bound). 0 when empty.
         */
        public long percentileNanos(int pct) {
            if (pct < 1 || pct > 100) {
                throw new IllegalArgumentException("percentile outside [1,100]: " + pct);
            }
            if (count == 0L) {
                return 0L;
            }
            long rank = (count * pct + 99L) / 100L;
            long seen = 0L;
            for (int i = 0; i < FINE_BUCKETS; i++) {
                seen += fine[i];
                if (seen >= rank) {
                    return Math.min((i + 1) * FINE_BUCKET_NANOS, maxNanos);
                }
            }
            for (int i = 0; i < COARSE_BUCKETS; i++) {
                seen += coarse[i];
                if (seen >= rank) {
                    return Math.min(FINE_SPAN_NANOS + (i + 1) * COARSE_BUCKET_NANOS, maxNanos);
                }
            }
            return maxNanos;
        }

        void appendTokens(StringBuilder b, String key, boolean withMax) {
            b.append(' ').append(key).append("_p50_us=").append(ceilMicros(percentileNanos(50)))
                    .append(' ').append(key).append("_p99_us=")
                    .append(ceilMicros(percentileNanos(99)));
            if (withMax) {
                b.append(' ').append(key).append("_max_us=").append(ceilMicros(maxNanos));
            }
        }
    }
}
