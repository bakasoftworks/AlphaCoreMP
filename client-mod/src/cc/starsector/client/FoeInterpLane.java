package cc.starsector.client;

import cc.starsector.client.net.InterpolatedPose;
import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import cc.starsector.client.net.SnapshotInterpolator;

/**
 * The FOE-INTERP lane (knob {@code cc.predict.foeInterp}; statics =
 * {@code PredictKnobConfig.FOE_INTERP_MODE}): owns the live
 * {@link SnapshotInterpolator} instance, the render-time cursor + the I7 delay RAMP, and
 * every lane meter, so the plugin keeps only thin touchpoints (the
 * {@link FractureFamilyCoordinator}/{@link ClockServoLaw} isolation idiom): feed at the
 * two {@code latestSnapshot} publish sites, target substitution at the two
 * {@code reconcileFoe} seams, round-local resets, and the meter line.
 *
 * <p>The lane time-shifts foe KINEMATICS only (I1): in {@code apply} the buffered pose
 * substitutes the reconcile <em>target</em> — {@code reconcileFoe}'s glide/SNAP law and
 * every {@code maintainFoe*} state lane keep reading the live snapshot untouched. In
 * {@code meter} the buffer is fed and measured (buffered-vs-live divergence, the live
 * lane's first inter-arrival jitter EWMA) but no behavior changes.
 *
 * <p>Fallback contract (I5): any frame the buffer cannot serve a pose (cold start,
 * entity absent from the bracketing frames, buffer cleared) {@link #targetFor} returns
 * {@code null} and the caller reconciles to the live pose — silent, counted, never an
 * error. A teleport snap-only window bypasses the buffer the same way (I8):
 * live pose + snap reconcile is that lane's law verbatim; post-window the cursor has
 * passed the fence and buffered rendering resumes.
 *
 * <p>The I7 ramp: {@code delayEff} starts at 0 each round and relaxes toward the
 * measured {@link SnapshotInterpolator#suggestedInterpDelayNanos()} with time-constant
 * {@link #RAMP_TAU_SEC} (tickrate-agnostic — no literal frame rate anywhere), so
 * entering apply mid-buffer the target walks smoothly into the past instead of stepping
 * ~2 intervals at once.
 *
 * <p>Engine-free on purpose (no Starsector-API import) so the ramp/cursor/fallback laws
 * are exercised by the game-free {@code FoeInterpLaneSmoke} in the build's in-loop
 * suite; both clocks arrive as caller-supplied nanos.
 *
 * <p>Threading: {@link #onPublish} runs on the NET thread; everything else on the
 * RENDER thread — coarse synchronization, the interpolator's own idiom (tiny bodies at
 * ≤ ~540 Hz).
 */
final class FoeInterpLane {

    /** I7 ramp time-constant, seconds ({@code delayEff -> suggested} relaxation). */
    static final double RAMP_TAU_SEC = 0.25d;
    /** EWMA smoothing factor for the arrival-seam interval/jitter meters. */
    static final double EWMA_ALPHA = 0.1d;
    /** 1290 (83) CL-12 — apply-mode cold announces only past THIS much continuous miss time per
     * entity: normal warmup is bounded by the interp delay (~2 snapshot intervals, well under
     * 250 ms at any real tick rate), so a first-frame announce false-fires on every healthy round
     * start. Time-based, not frame-counted — the lane's no-literal-frame-rate law. */
    static final long COLD_ANNOUNCE_NANOS = 1_000_000_000L;
    /** Cold-streak tracker cap (entity ids are wire-derived — bounded-allocation law; the map
     * also clears every round). Overflow = untracked = no announce, fail-quiet. */
    static final int COLD_TRACK_CAP = 256;

    private final boolean meter;
    private final boolean apply;
    private final SnapshotInterpolator interp = new SnapshotInterpolator();

    // --- render-thread cursor state (I3/I7) ---
    private long delayEffNanos;                    // ramped render delay; 0 at round start
    private long cursorNanos = Long.MIN_VALUE;     // this frame's render time (now - delayEff)
    private long lastRenderNanos = Long.MIN_VALUE;

    // --- arrival-seam meters (net thread; I5 — the live lane's first jitter meter) ---
    private long feedsAppended;                    // addOrFold appends (true arrivals)
    private long feedsFolded;                      // co-tick folds + regressed drops (non-appends)
    private long lastAppendNanos = Long.MIN_VALUE; // folds/drops never clock the EWMAs
    private double arrivalEwmaNanos;               // smoothed inter-arrival interval
    private double jitterEwmaNanos;                // smoothed |dt - arrivalEwma| deviation

    // --- render-seam meters ---
    private long divSamples;                       // buffered-vs-live divergence samples
    private double divSum;
    private float divMax;
    private long applyFrames;                      // apply: buffered target substituted
    private long fallbackNullFrames;               // poseAt null -> live-pose fallback (counted, silent)
    private long snapOnlyBypassFrames;             // I8: teleport window bypassed the buffer
    private long roundResets;
    /** CL-12 — per-entity start-of-cold-streak render nanos (apply mode only; round-local). */
    private final java.util.HashMap<Long, Long> coldSinceNanos = new java.util.HashMap<Long, Long>();

    FoeInterpLane(boolean meter, boolean apply) {
        this.meter = meter;
        this.apply = apply;
    }

    /**
     * NET thread — mirror of every {@code latestSnapshot} publish (both the AWSS and the
     * AWSD-fold branch). {@code addOrFold}, not {@code add}: the publish seam's tick
     * sequence repeats on co-tick chunk folds. Only true appends
     * clock the interval/jitter EWMAs — the interpolator's return value is the skip signal.
     */
    synchronized void onPublish(long nowNanos, ServerSnapshotFrame frame) {
        if (!meter) {
            return;
        }
        if (!interp.addOrFold(nowNanos, frame)) {
            feedsFolded++;
            return;
        }
        feedsAppended++;
        if (lastAppendNanos != Long.MIN_VALUE) {
            double dt = nowNanos - lastAppendNanos;
            if (arrivalEwmaNanos <= 0d) {
                arrivalEwmaNanos = dt; // first measured interval seeds the EWMA
            } else {
                jitterEwmaNanos += EWMA_ALPHA * (Math.abs(dt - arrivalEwmaNanos) - jitterEwmaNanos);
                arrivalEwmaNanos += EWMA_ALPHA * (dt - arrivalEwmaNanos);
            }
        }
        lastAppendNanos = nowNanos;
    }

    /** RENDER thread, once per advance frame — walk the I7 ramp, mint this frame's cursor. */
    synchronized void beginRenderFrame(long nowNanos) {
        if (!meter) {
            return;
        }
        if (lastRenderNanos != Long.MIN_VALUE && nowNanos > lastRenderNanos) {
            long target = interp.suggestedInterpDelayNanos();
            double dtSec = Math.min(1d, (nowNanos - lastRenderNanos) / 1e9d);
            double k = 1d - Math.exp(-dtSec / RAMP_TAU_SEC);
            delayEffNanos += (long) ((target - delayEffNanos) * k);
        }
        lastRenderNanos = nowNanos;
        cursorNanos = nowNanos - delayEffNanos;
    }

    /**
     * RENDER thread — the I1 target substitution for one foe replica this frame: the
     * fence-aware buffered pose when the lane is in APPLY and the buffer can serve one,
     * else {@code null} (caller reconciles to the live pose). Meter mode measures
     * divergence and always returns {@code null}. {@code fenceTick} = the entity's
     * jump fence (hold-then-jump inside the interpolator).
     */
    synchronized InterpolatedPose targetFor(ServerEntityPose live, long fenceTick,
            boolean snapOnlyWindow) {
        if (!meter || live == null || cursorNanos == Long.MIN_VALUE) {
            return null;
        }
        if (snapOnlyWindow) {
            snapOnlyBypassFrames++;
            return null; // I8: the teleport lane owns this transit, buffer bypassed
        }
        InterpolatedPose ip = interp.poseAt(cursorNanos, live.entityId(), fenceTick);
        if (ip == null) {
            fallbackNullFrames++;
            if (apply) {
                // 1290 (81) R7 as re-scoped by (83) CL-12: a cold buffer while APPLYING is
                // normal warmup for the first ~2 snapshot intervals — only a PERSISTENT
                // cold streak confesses (meter-mode nulls always stay silent).
                Long key = Long.valueOf(live.entityId());
                Long since = coldSinceNanos.get(key);
                if (since == null) {
                    if (coldSinceNanos.size() < COLD_TRACK_CAP) {
                        coldSinceNanos.put(key, Long.valueOf(lastRenderNanos));
                    }
                } else if (lastRenderNanos - since.longValue() >= COLD_ANNOUNCE_NANOS) {
                    FallbackAnnounce.announceOnce("r7-interp-cold-buffer#" + live.entityId(),
                            "apply-mode interp cold >=1s -> live-pose passthrough");
                }
            }
            return null;
        }
        if (apply && !coldSinceNanos.isEmpty()) {
            coldSinceNanos.remove(Long.valueOf(live.entityId())); // served — the streak is over
        }
        float dx = ip.locX() - live.locX();
        float dy = ip.locY() - live.locY();
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        divSamples++;
        divSum += dist;
        if (dist > divMax) {
            divMax = dist;
        }
        if (!apply) {
            return null;
        }
        applyFrames++;
        return ip;
    }

    /**
     * RENDER thread — I9 round hygiene (MRST {@code finishMatchReset} + respawn
     * re-adopt): drop the buffer (dead-round frames must never bracket a lerp into the
     * new one) and restart the I7 ramp from 0. Cumulative meters persist (the lane-meter
     * family law); the EWMAs keep their estimate but the cross-boundary gap never clocks
     * them ({@code lastAppendNanos} re-arms).
     */
    synchronized void resetRoundLocal() {
        interp.clear();
        delayEffNanos = 0L;
        cursorNanos = Long.MIN_VALUE;
        lastRenderNanos = Long.MIN_VALUE;
        lastAppendNanos = Long.MIN_VALUE;
        coldSinceNanos.clear(); // CL-12: cold streaks are round-local like the buffer they watch
        roundResets++;
    }

    /** Meter-line tokens (the teleport-meter/beam-meter block idiom in the plugin). */
    synchronized String meterTokens() {
        double mean = divSamples > 0 ? divSum / divSamples : 0d;
        return " buffer=" + interp.size()
                + " delay_eff_ms=" + fmt(delayEffNanos / 1e6d)
                + " arrival_ewma_ms=" + fmt(arrivalEwmaNanos / 1e6d)
                + " jitter_ewma_ms=" + fmt(jitterEwmaNanos / 1e6d)
                + " feeds_appended=" + feedsAppended
                + " feeds_folded=" + feedsFolded
                + " div_samples=" + divSamples
                + " div_mean=" + fmt(mean)
                + " div_max=" + fmt(divMax)
                + " apply_frames=" + applyFrames
                + " fallback_null=" + fallbackNullFrames
                + " snaponly_bypass=" + snapOnlyBypassFrames
                + " round_resets=" + roundResets;
    }

    /** wire-mark: the live arrival-cadence EWMA (ms) for the F9 wire row. */
    synchronized double arrivalEwmaMs() {
        return arrivalEwmaNanos / 1e6d;
    }

    /** wire-mark: the live arrival-jitter EWMA (ms) for the F9 wire row. */
    synchronized double jitterEwmaMs() {
        return jitterEwmaNanos / 1e6d;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // --- package-private accessors for the game-free smoke ---
    long delayEffNanosForTest() {
        return delayEffNanos;
    }

    long feedsAppendedForTest() {
        return feedsAppended;
    }

    long feedsFoldedForTest() {
        return feedsFolded;
    }

    long fallbackNullFramesForTest() {
        return fallbackNullFrames;
    }

    long applyFramesForTest() {
        return applyFrames;
    }

    long snapOnlyBypassFramesForTest() {
        return snapOnlyBypassFrames;
    }

    long divSamplesForTest() {
        return divSamples;
    }

    double jitterEwmaNanosForTest() {
        return jitterEwmaNanos;
    }
}
