package cc.starsector.server.ops;

import java.util.Arrays;
import java.util.Locale;

/**
 * Scale-lane row 2: per-phase
 * frame-work aggregation between successive ops writes. The serve loop already brackets every
 * frame into pre|step|det|comp|send|intent|tail witness stamps but only prints them above the
 * 100 ms stall threshold, so every ordinary 17-100 ms overrun went unattributed. This law folds
 * each frame's seven brackets into the current window (mean exact via running sums; p95 over a
 * bounded sample ring) and {@link OpsStatusFile} snapshots-and-resets it on its 1 s compose —
 * the ops file's {@code ph.*} keys plus a 1 Hz stdout mirror line then NAME where the
 * milliseconds went, at every load level, not just past the stall threshold.
 *
 * <p>Pure arithmetic, zero engine/socket imports (the {@code SimSpeedGovernorLaw} idiom);
 * serve-thread only like every pacer local. Contract rows live in {@code OpsStatusContract}.
 */
public final class PhaseWindowStats {

    /** Bracket order = the serve loop's witness stamp order (the stall-line phaseNames twin). */
    public static final String[] PHASE_NAMES =
            {"pre", "step", "det", "comp", "send", "intent", "tail"};
    public static final int PHASES = PHASE_NAMES.length;
    /** p95 sample cap per phase per window (~17 s at 60 fps — a 1 s window never fills it).
     * Past the cap the ring keeps the NEWEST samples (p95 degrades to the tail of the window);
     * the mean stays exact over ALL fed frames via the sums. */
    public static final int RING_CAPACITY = 1024;

    private final long[][] ring = new long[PHASES][RING_CAPACITY];
    private final long[] sums = new long[PHASES];
    private long frames;

    /** One served frame's seven bracket durations (nanos), stall-line order. */
    public void feed(long preNanos, long stepNanos, long detNanos, long compNanos,
            long sendNanos, long intentNanos, long tailNanos) {
        int slot = (int) (frames % RING_CAPACITY);
        long[] values = {preNanos, stepNanos, detNanos, compNanos, sendNanos, intentNanos,
                tailNanos};
        for (int p = 0; p < PHASES; p++) {
            ring[p][slot] = values[p];
            sums[p] += values[p];
        }
        frames++;
    }

    /** Window close (the 1 s ops compose): mean/p95 per phase in ms, then reset. */
    public Snapshot snapshotAndReset() {
        int n = (int) Math.min(frames, RING_CAPACITY);
        double[] meanMs = new double[PHASES];
        double[] p95Ms = new double[PHASES];
        if (n > 0) {
            for (int p = 0; p < PHASES; p++) {
                meanMs[p] = sums[p] / (double) frames / 1_000_000.0d;
                long[] sorted = Arrays.copyOf(ring[p], n);
                Arrays.sort(sorted);
                p95Ms[p] = sorted[(int) Math.ceil(0.95d * n) - 1] / 1_000_000.0d;
            }
        }
        Snapshot snapshot = new Snapshot(frames, meanMs, p95Ms);
        frames = 0L;
        Arrays.fill(sums, 0L);
        return snapshot;
    }

    /** Immutable window result (ms). {@link #EMPTY} = the no-frames window (count 0, zeros) —
     * lobby composes and a detached stats slot both emit it so the grammar keys are ALWAYS
     * present (fail-closed parse core). */
    public record Snapshot(long frames, double[] meanMs, double[] p95Ms) {
        public static final Snapshot EMPTY =
                new Snapshot(0L, new double[PHASES], new double[PHASES]);

        /** The 1 Hz stdout mirror — the ops FILE is a latest-wins rewrite, so this line is
         * what preserves the window timeline in archived serve logs ({@code mean/p95} ms). */
        public String opsLine() {
            StringBuilder sb = new StringBuilder("ops-file phase_win frames=").append(frames);
            for (int p = 0; p < PHASES; p++) {
                sb.append(' ').append(PHASE_NAMES[p]).append("_ms=")
                        .append(String.format(Locale.ROOT, "%.3f", meanMs[p]))
                        .append('/')
                        .append(String.format(Locale.ROOT, "%.3f", p95Ms[p]));
            }
            return sb.toString();
        }
    }
}
