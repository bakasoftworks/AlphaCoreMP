package cc.starsector.client.net;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Client-side snapshot interpolation buffer.
 *
 * <p>Production netcode (Source/Quake-style entity interpolation) never snaps a remote
 * entity to the latest received snapshot — that is the per-frame teleport seen in play
 * as "vibrating into place". Instead it buffers recent authoritative
 * snapshots and renders remote entities a fixed delay <em>in the past</em>, drawing each
 * frame at {@code renderTime = now - interpDelay} by linearly interpolating between the
 * two buffered snapshots that bracket that render time. The delay (≈1.5–2× the snapshot
 * interval) keeps at least one snapshot on each side of the render cursor so there is
 * always a pair to interpolate, absorbing network jitter at the cost of a small,
 * imperceptible latency.
 *
 * <p>This class is the engine-free core of that scheme: a small timestamped ring buffer
 * of {@link ServerSnapshotFrame}s plus {@link #poseAt} which finds the bracketing pair
 * for a given entity id and returns the lerped position + shortest-arc-lerped facing. It
 * takes its timestamps as explicit arguments (monotonic-clock nanos supplied by the
 * caller) rather than reading a clock itself, so it is fully deterministic under the
 * game-free verifier. {@link ServerSnapshotClient} owns one instance, feeds it from the
 * reader thread, and the combat render thread queries it via
 * {@link ServerSnapshotClient#interpolatedPose}.
 *
 * <p>Behaviour at the buffer ends is <em>hold</em> (clamp), not extrapolate: a render
 * time before the oldest sample returns the oldest pose, and one after the newest returns
 * the newest pose. This is the simplest correct first cut (no overshoot); bounded
 * extrapolation on starvation is a later refinement.
 *
 * <p>Foe interpolation: the live predict client feeds a second instance from
 * the PRCP publish seam via {@link #addOrFold} (that seam repeats {@code simTick}s on
 * co-tick chunk folds — a plain {@link #add} would insert duplicate-pose samples that
 * read as flat-lerp micro-hitches), queries it with the fence-aware
 * {@link #poseAt(long, long, long)} (the teleport hold-then-jump
 * contract), and resets it per round via {@link #clear()}.
 *
 * <p>Thread-safe via coarse synchronization on this instance — reads/writes are tiny and
 * at most ~60–120 Hz, so contention is negligible.
 */
public final class SnapshotInterpolator {

    /**
     * Fallback snapshot interval, in nanoseconds (16 ms), used only before the buffer has
     * enough samples to measure the real interval. This is the validated server frame
     * pace, NOT a product constant — the live interval is <em>measured</em> from frame
     * arrivals ({@link #estimatedIntervalNanos()}) so the client adapts to whatever
     * tickrate the server is configured to pace at (the server's network tick is a
     * configurable invariant; see {@code DECISIONS.md}). Nothing here assumes a fixed
     * rate beyond this cold-start fallback.
     */
    public static final long DEFAULT_INTERVAL_NANOS = 16_000_000L;

    /**
     * Render delay as a multiple of the (measured) snapshot interval: ≈2 intervals of
     * headroom keeps a bracketing pair available under normal jitter while staying
     * imperceptible. Used by callers that derive their delay from
     * {@link #estimatedIntervalNanos()} so the delay tracks the configured tickrate.
     */
    public static final int INTERP_DELAY_INTERVALS = 2;

    /**
     * Default render delay behind the newest snapshot, in nanoseconds: {@value
     * #INTERP_DELAY_INTERVALS}× the fallback interval (~32 ms). Only used as the
     * cold-start / no-measurement fallback; the live delay is derived from the measured
     * interval so it follows a re-configured server tickrate without a client change.
     */
    public static final long DEFAULT_INTERP_DELAY_NANOS = INTERP_DELAY_INTERVALS * DEFAULT_INTERVAL_NANOS;

    /** Default retention window: drop samples older than {@code newest - this} (~1 s). */
    public static final long DEFAULT_BUFFER_NANOS = 1_000_000_000L;

    /** Hard cap on buffered samples (safety against a runaway producer). */
    private static final int MAX_SAMPLES = 256;

    private final long bufferNanos;
    private final Deque<Sample> samples = new ArrayDeque<>();

    public SnapshotInterpolator() {
        this(DEFAULT_BUFFER_NANOS);
    }

    public SnapshotInterpolator(long bufferNanos) {
        this.bufferNanos = Math.max(1L, bufferNanos);
    }

    /** One buffered frame paired with the monotonic-clock time it was received. */
    private static final class Sample {
        final long timeNanos;
        final ServerSnapshotFrame frame;

        Sample(long timeNanos, ServerSnapshotFrame frame) {
            this.timeNanos = timeNanos;
            this.frame = frame;
        }
    }

    /**
     * Append a received frame stamped at {@code timeNanos} (a monotonic clock such as
     * {@link System#nanoTime()}). Non-monotonic or duplicate timestamps are dropped so
     * the buffer stays strictly increasing and bracketing is unambiguous. Old samples
     * outside the retention window are evicted (always keeping the last two so a pair
     * remains available).
     */
    public synchronized void add(long timeNanos, ServerSnapshotFrame frame) {
        if (frame == null) {
            return;
        }
        Sample last = samples.peekLast();
        if (last != null && timeNanos <= last.timeNanos) {
            return; // out-of-order / duplicate; keep the deque strictly increasing
        }
        samples.addLast(new Sample(timeNanos, frame));
        long cutoff = timeNanos - bufferNanos;
        while (samples.size() > 2 && samples.peekFirst().timeNanos < cutoff) {
            samples.removeFirst();
        }
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    /**
     * {@link #add} variant for the live publish seam, whose tick sequence is monotonic
     * non-decreasing but REPEATS on co-tick chunk folds: a frame whose
     * {@code simTick} equals the newest buffered sample's replaces that sample's frame in
     * place, KEEPING its original arrival stamp (so the interval estimator never sees the
     * fold as an extra arrival); a regressed tick is dropped; anything newer appends via
     * {@link #add} (which still drops non-monotonic arrival stamps).
     *
     * @return {@code true} only when a NEW sample was appended — the caller's
     *         inter-arrival jitter meter must skip folds/drops.
     */
    public synchronized boolean addOrFold(long timeNanos, ServerSnapshotFrame frame) {
        if (frame == null) {
            return false;
        }
        Sample newest = samples.peekLast();
        if (newest != null) {
            long newestTick = newest.frame.simTick();
            if (frame.simTick() == newestTick) {
                samples.removeLast();
                samples.addLast(new Sample(newest.timeNanos, frame));
                return false;
            }
            if (frame.simTick() < newestTick) {
                return false; // regressed tick: the publish seam never goes backward
            }
        }
        add(timeNanos, frame);
        Sample last = samples.peekLast();
        return last != null && last.frame == frame;
    }

    /**
     * Drop every buffered sample — round-boundary hygiene: frames from a
     * dead round must never bracket a lerp into the new one. The next {@link #add} accepts
     * any stamp (the monotonic guard is relative to the buffer's own newest sample).
     */
    public synchronized void clear() {
        samples.clear();
    }

    /** Number of frames currently buffered. */
    public synchronized int size() {
        return samples.size();
    }

    /**
     * The average interval between buffered snapshots, in nanoseconds — i.e. the
     * <em>measured</em> snapshot rate, used to derive a tickrate-agnostic render delay
     * (see {@link #INTERP_DELAY_INTERVALS}). Computed as
     * {@code (newest - oldest) / (count - 1)} over the retention window, so it tracks the
     * server's configured pace automatically. Returns {@link #DEFAULT_INTERVAL_NANOS}
     * until at least two samples have arrived (cold start).
     */
    public synchronized long estimatedIntervalNanos() {
        if (samples.size() < 2) {
            return DEFAULT_INTERVAL_NANOS;
        }
        long span = samples.peekLast().timeNanos - samples.peekFirst().timeNanos;
        long interval = span / (samples.size() - 1);
        return interval > 0L ? interval : DEFAULT_INTERVAL_NANOS;
    }

    /**
     * The render delay a caller should use given the current measured interval:
     * {@link #INTERP_DELAY_INTERVALS}× {@link #estimatedIntervalNanos()}. Adapts to the
     * configured server tickrate without any client reconfiguration.
     */
    public synchronized long suggestedInterpDelayNanos() {
        return INTERP_DELAY_INTERVALS * estimatedIntervalNanos();
    }

    /**
     * The interpolated pose of the entity with id {@code entityId} at
     * {@code renderTimeNanos}, or {@code null} if the buffer holds no pose for that
     * entity. Lerps position/velocity and shortest-arc-lerps facing between the two
     * buffered snapshots bracketing the render time; clamps (holds) at the buffer ends
     * and when only one bracketing frame carries the entity. Fence-free form — delegates
     * with the no-fence sentinel.
     */
    public synchronized InterpolatedPose poseAt(long renderTimeNanos, long entityId) {
        return poseAt(renderTimeNanos, entityId, Long.MIN_VALUE);
    }

    /**
     * Fence-aware {@link #poseAt(long, long)} (the teleport
     * contract): when the bracketing pair SPANS the fence tick
     * ({@code before.simTick < fenceTick <= after.simTick}) the before-pose is returned
     * unlerped — hold-then-jump: the discontinuity lands whole when the render cursor
     * passes the post-fence sample, never as a lerped smear across the teleport.
     * {@code fenceTick = Long.MIN_VALUE} means no fence (every tick is post-fence).
     */
    public synchronized InterpolatedPose poseAt(long renderTimeNanos, long entityId, long fenceTick) {
        if (samples.isEmpty()) {
            return null;
        }
        Sample before = null; // latest sample with time <= renderTime
        Sample after = null;  // earliest sample with time >= renderTime
        for (Sample s : samples) {
            if (s.timeNanos <= renderTimeNanos) {
                before = s;
            }
            if (s.timeNanos >= renderTimeNanos) {
                after = s;
                break; // samples are strictly increasing, so this is the earliest such
            }
        }

        ServerEntityPose poseBefore = before != null ? before.frame.entityById(entityId) : null;
        ServerEntityPose poseAfter = after != null ? after.frame.entityById(entityId) : null;

        if (poseBefore == null && poseAfter == null) {
            return null; // entity not present in either bracketing frame
        }
        if (poseBefore == null) {
            return InterpolatedPose.of(poseAfter);  // before the buffer / hold the earliest
        }
        if (poseAfter == null) {
            return InterpolatedPose.of(poseBefore); // after the buffer / hold the latest
        }
        if (before == after) {
            return InterpolatedPose.of(poseBefore); // exact hit on one sample
        }
        if (before.frame.simTick() < fenceTick && after.frame.simTick() >= fenceTick) {
            return InterpolatedPose.of(poseBefore); // bracket spans the fence: hold, don't smear
        }

        long span = after.timeNanos - before.timeNanos;
        float t = span <= 0L
                ? 0f
                : (float) ((double) (renderTimeNanos - before.timeNanos) / (double) span);
        if (t < 0f) {
            t = 0f;
        } else if (t > 1f) {
            t = 1f;
        }
        float x = lerp(poseBefore.locX(), poseAfter.locX(), t);
        float y = lerp(poseBefore.locY(), poseAfter.locY(), t);
        float facing = lerpAngleDeg(poseBefore.facing(), poseAfter.facing(), t);
        float velX = lerp(poseBefore.velX(), poseAfter.velX(), t);
        float velY = lerp(poseBefore.velY(), poseAfter.velY(), t);
        float angVel = lerp(poseBefore.angularVelocity(), poseAfter.angularVelocity(), t);
        return new InterpolatedPose(entityId, x, y, facing, velX, velY, angVel);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Interpolate between two angles in degrees along the shortest arc (so 350°→10°
     * crosses 0° rather than sweeping backward through 180°).
     */
    static float lerpAngleDeg(float a, float b, float t) {
        float diff = wrap360(b - a + 180f) - 180f; // shortest signed delta in [-180, 180)
        return a + diff * t;
    }

    private static float wrap360(float deg) {
        float m = deg % 360f;
        return m < 0f ? m + 360f : m;
    }
}
