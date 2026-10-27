package cc.starsector.client;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Why this meter: the F9 press measured the
 * client's OWN frame ring, the wire arrival meter and a body census — none of which measure the
 * foe replicas' MOTION. This meter does, at the ONE seam every replica pose crosses
 * ({@code FoeStateLaneCoordinator.reconcileFoeTo}): per entity, the step between consecutive
 * DISTINCT authoritative target positions against the target's own speed × one sim tick
 * ({@link #TICK_DT_SEC}) — a ratio ≈ 1 is one tick of travel, ≈ 2 is a two-tick jump (a folded
 * tick, a merger skip, a dropped snapshot: the replica hitches on a CLEAN frame loop and a clean
 * wire) — plus the correction the glide/SNAP law applied that frame (a snap = a visible teleport).
 * Window semantics = the wire-mark row's: maxima and counts since the previous mark, taken and
 * reset by {@link #takeMarkTokens}; the trackers survive the take (a step spans marks).
 * Skipped, never sampled: snap-only teleport frames (that lane owns the transit — a legitimate
 * jump), slow targets below {@link #MIN_SPEED_SU_S} (a ratio over a tiny expectation is noise),
 * and the first pose after a (re)seed. Plugin thread only, no sync; bounded ({@link #TRACK_CAP});
 * numbers only, no player text; B-clean (java.util + String only).
 *
 * <p>The jump ATTRIBUTION split. One measured match read {@code foe_jumps}
 * 129…577 per window and no counter explained them (the AWSD hold-last drops did not track them;
 * the correction maxima tracked the window's worst RENDER frame). Every jump now lands in exactly
 * one class: {@code frame} = the render frame itself spanned ≥ {@link #LONG_FRAME_SEC} (the frame
 * loop hitched, so the presenter skipped ticks — every moving replica jumps at once; not a wire
 * fault); {@code skip} = a normal frame, but ≥ 2 snapshot ticks passed between this replica's
 * consecutive applied targets (a lost datagram, a per-entity hold-last, a burst the freshness
 * buffer conflated) — {@code foe_skip_frames} counts the distinct render frames carrying one, so
 * skip ÷ frames ≈ the world size reads as whole-tick skips and ≈ 1 as per-entity holds;
 * {@code same} = consecutive ticks and the POSE leapt (time-mult hulls, a hard accel, a server
 * step) — the meter's own false-positive class, sized here instead of guessed.
 */
final class FoeStepMeter {
    /** The engine's fixed step: one snapshot tick of travel = speed × this. */
    static final float TICK_DT_SEC = 1f / 60f;
    /** Below this target speed (su/s) a step ratio is not read (expected ≈ 0). */
    static final double MIN_SPEED_SU_S = 25d;
    /** A step ratio at or above this counts as a JUMP (≥ ~two ticks in one applied target). */
    static final double JUMP_RATIO = 1.75d;
    /** Entity trackers cap (a bastion round is far below; the cap only bounds a runaway id space). */
    static final int TRACK_CAP = 256;
    /** A render frame at or above this wall dt spans ≥ ~1.5 sim ticks: the presenter skipped ticks itself. */
    static final double LONG_FRAME_SEC = 1.5d * TICK_DT_SEC;

    private static final class Track {
        float x;
        float y;
        boolean seeded;
        /** The snapshot tick the last DISTINCT target was applied at (a held pose keeps its birth tick). */
        long tick = Long.MIN_VALUE;
    }

    private final Map<Long, Track> tracks = new HashMap<Long, Track>();
    private long tracksDropped;

    // --- this render frame (set once per frame by beginFrame) ---
    private boolean longFrame;
    private long frameSeq;
    private long lastSkipFrameSeq = -1L;

    // --- window (since the previous mark) ---
    private double ratioMax;
    private long ratioMaxId = -1L;
    private long ratioMaxTick = -1L;
    private double ratioMaxStepSu;
    private long steps;
    private long jumps;
    private double corrMaxSu;
    private long corrMaxId = -1L;
    private long snaps;
    private long jumpsFrame;
    private long jumpsSkip;
    private long jumpsSame;
    private long skipFrames;
    private long skipTicksMax;

    /** Once per render frame, BEFORE the replica reconcile: this frame's unclamped wall dt. */
    void beginFrame(double realDtSec) {
        frameSeq++;
        longFrame = realDtSec >= LONG_FRAME_SEC;
    }

    /**
     * One applied target for one replica. {@code rdx,rdy} = the residual (target − presented) BEFORE
     * the correction; {@code snapped} = the law snapped instead of gliding; {@code alpha} = the glide
     * fraction applied otherwise.
     */
    void note(long entityId, float x, float y, float velX, float velY, float rdx, float rdy,
            boolean snapped, float alpha, boolean snapOnly, long simTick) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return;
        }
        // the correction the eye saw this frame (glide fraction of the residual, or the whole of it)
        double residual = Math.sqrt((double) rdx * rdx + (double) rdy * rdy);
        double applied = snapped ? residual : residual * Math.max(0f, Math.min(1f, alpha));
        if (snapped && !snapOnly) {
            snaps++;
        }
        if (!snapOnly && applied > corrMaxSu) {
            corrMaxSu = applied;
            corrMaxId = entityId;
        }
        Track t = tracks.get(entityId);
        if (t == null) {
            if (tracks.size() >= TRACK_CAP) {
                tracksDropped++;
                return;
            }
            t = new Track();
            tracks.put(entityId, t);
        }
        if (snapOnly) {
            t.seeded = false; // the teleport lane owns this transit; re-seed after it
            return;
        }
        if (!t.seeded) {
            t.x = x;
            t.y = y;
            t.tick = simTick;
            t.seeded = true;
            return;
        }
        if (t.x == x && t.y == y) {
            return; // the same target as last frame: no new tick applied (t.tick stays the pose's birth)
        }
        double stepSu = Math.sqrt(((double) x - t.x) * ((double) x - t.x) + ((double) y - t.y) * ((double) y - t.y));
        long prevTick = t.tick;
        t.x = x;
        t.y = y;
        t.tick = simTick;
        double speed = Float.isFinite(velX) && Float.isFinite(velY)
                ? Math.sqrt((double) velX * velX + (double) velY * velY) : 0d;
        if (speed < MIN_SPEED_SU_S) {
            return;
        }
        steps++;
        double ratio = stepSu / (speed * TICK_DT_SEC);
        if (ratio >= JUMP_RATIO) {
            jumps++;
            // Attribution: exactly one class per jump (frame outranks skip — a hitched frame
            // loop skips ticks by itself, so its tick gap says nothing about the wire).
            long tickGap = simTick == Long.MIN_VALUE || prevTick == Long.MIN_VALUE ? 0L : simTick - prevTick;
            if (longFrame) {
                jumpsFrame++;
            } else if (tickGap >= 2L) {
                jumpsSkip++;
                if (frameSeq != lastSkipFrameSeq) {
                    lastSkipFrameSeq = frameSeq;
                    skipFrames++;
                }
                if (tickGap > skipTicksMax) {
                    skipTicksMax = tickGap;
                }
            } else {
                jumpsSame++;
            }
        }
        if (ratio > ratioMax) {
            ratioMax = ratio;
            ratioMaxId = entityId;
            ratioMaxTick = simTick;
            ratioMaxStepSu = stepSu;
        }
    }

    /** A respawn re-adopt / round reset: the trackers forget (a fresh hull mints no step). */
    void resetRoundLocal() {
        tracks.clear();
    }

    /** The F9 wire-mark tokens for the window since the previous take; takes and resets the window. */
    String takeMarkTokens() {
        String s = String.format(Locale.ROOT,
                " foe_step_ratio_max=%.2f foe_step_id=%d foe_step_tick=%d foe_step_su=%.1f"
                        + " foe_steps=%d foe_jumps=%d foe_corr_max_su=%.1f foe_corr_id=%d foe_snaps=%d"
                        + " foe_tracked=%d foe_track_dropped=%d"
                        + " foe_jumps_frame=%d foe_jumps_skip=%d foe_jumps_same=%d"
                        + " foe_skip_frames=%d foe_skip_ticks_max=%d",
                ratioMax, ratioMaxId, ratioMaxTick, ratioMaxStepSu, steps, jumps,
                corrMaxSu, corrMaxId, snaps, tracks.size(), tracksDropped,
                jumpsFrame, jumpsSkip, jumpsSame, skipFrames, skipTicksMax);
        ratioMax = 0d;
        ratioMaxId = -1L;
        ratioMaxTick = -1L;
        ratioMaxStepSu = 0d;
        steps = 0L;
        jumps = 0L;
        corrMaxSu = 0d;
        corrMaxId = -1L;
        snaps = 0L;
        jumpsFrame = 0L;
        jumpsSkip = 0L;
        jumpsSame = 0L;
        skipFrames = 0L;
        skipTicksMax = 0L;
        return s;
    }
}
