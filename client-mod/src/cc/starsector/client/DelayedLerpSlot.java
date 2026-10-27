package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * self-contained 2-sample delayed-lerp pin slot, carved out of
 * {@link CommandTransferLane} (the ARMED deferred-refactors row, executed): a PURE
 * presentation primitive with zero plugin/episode coupling. Feed keeps the newest pair keyed
 * by wire id; a key change, a sim-tick gap &gt; 3, or a teleport-scale jump
 * ({@link #SNAP_DISTANCE_SQ}) restarts the pair at the fresh pose (the "clear ⇒ raw hard-set"
 * SNAP law); a null pose is NEVER buffered (H-B) — 4 consecutive absent ticks clear the slot
 * so a despawned wire body releases its pin instead of freezing it. Read pins one measured
 * arrival-interval behind the newest sample (16 ms fallback; a 2-sample slot cannot hold the
 * foe lane's deeper buffer). The lerp statics are private copies of the package-private
 * {@code SnapshotInterpolator} ones (cross-package reuse won't compile; dedup by
 * widening those to public was DECLINED).
 */
final class DelayedLerpSlot {

    /** R8: teleport snap threshold, squared — the foe-gate 300 su mirror. */
    static final float SNAP_DISTANCE_SQ = 300f * 300f;
    /** R8: lerp cursor fallback when the arrival delta is degenerate (~one 60 Hz frame). */
    static final long FALLBACK_INTERVAL_NANOS = 16_000_000L;

    private long key;
    private int samples; // 0, 1, or 2 (index 1 = newest)
    private int misses;
    private long tick0, tick1;
    private long arrival0, arrival1;
    private float x0, y0, f0, vx0, vy0, av0;
    private float x1, y1, f1, vx1, vy1, av1;

    // W3-E meter: transit-scoped counters — they SURVIVE clear() (the transit-END
    // chokepoint clears the slot before the meter line prints) and zero only at resetMeter().
    private int sampleCount, snapCount, missCount, clearCount, pinAppliedCount, tClampCount;

    void clear() {
        key = 0L;
        samples = 0;
        misses = 0;
        clearCount++;
    }

    void noteMiss() {
        missCount++;
        if (samples > 0 && ++misses > 3) {
            clear();
        }
    }

    void feed(long slotKey, ServerEntityPose pose, long simTick, long nowNanos) {
        if (pose == null) {
            return; // H-B: never buffer a null pose
        }
        if (slotKey != key) {
            clear();
            key = slotKey;
        }
        misses = 0;
        if (samples > 0) {
            if (simTick <= tick1) {
                return; // stale/duplicate tick — the feed is newest-only
            }
            float dx = pose.locX() - x1;
            float dy = pose.locY() - y1;
            if (simTick - tick1 > 3 || dx * dx + dy * dy > SNAP_DISTANCE_SQ) {
                samples = 0; // SNAP edge: gap/teleport — restart the pair at this pose
                snapCount++;
            }
        }
        sampleCount++;
        if (samples > 0) {
            tick0 = tick1;
            arrival0 = arrival1;
            x0 = x1; y0 = y1; f0 = f1; vx0 = vx1; vy0 = vy1; av0 = av1;
            samples = 2;
        } else {
            samples = 1;
        }
        tick1 = simTick;
        arrival1 = nowNanos;
        x1 = pose.locX(); y1 = pose.locY(); f1 = pose.facing();
        vx1 = pose.velX(); vy1 = pose.velY(); av1 = pose.angularVelocity();
    }

    /** Pin {@code body} to the slot's delayed-lerp pose; false when the slot is empty. */
    boolean pin(ShipAPI body, long nowNanos) {
        if (samples == 0) {
            return false;
        }
        pinAppliedCount++;
        if (samples == 1) {
            hardPin(body, x1, y1, f1, vx1, vy1, av1); // single sample: raw hard-set
            return true;
        }
        long interval = arrival1 - arrival0;
        if (interval <= 0L) {
            interval = FALLBACK_INTERVAL_NANOS;
        }
        float t = (float) ((double) (nowNanos - arrival1) / (double) interval);
        if (t < 0f) {
            t = 0f;
        } else if (t > 1f) {
            t = 1f;
            tClampCount++; // W3-E M2 signal: arrival jitter held the cursor at the old sample
        }
        hardPin(body,
                lerp(x0, x1, t), lerp(y0, y1, t), lerpAngleDeg(f0, f1, t),
                lerp(vx0, vx1, t), lerp(vy0, vy1, t), lerp(av0, av1, t));
        return true;
    }

    /** 4-field DemDrone hard-set idiom ({@link DemDronePresenter#presentPass}) — presentation pin, never break the frame. */
    private static void hardPin(ShipAPI body, float locX, float locY, float facing,
            float velX, float velY, float angVel) {
        try {
            org.lwjgl.util.vector.Vector2f loc = body.getLocation();
            if (loc != null) {
                loc.set(locX, locY);
            }
            body.setFacing(facing);
            org.lwjgl.util.vector.Vector2f vel = body.getVelocity();
            if (vel != null) {
                vel.set(velX, velY);
            }
            body.setAngularVelocity(angVel);
        } catch (Throwable ignore) {
            // presentation pin; never break the frame
        }
    }

    /** W3-E meter — transit-scoped reads; the owner is {@code CommandTransferLane}'s
     *  one-line transit-end emit. */
    int meterSamples() { return sampleCount; }
    int meterSnaps() { return snapCount; }
    int meterMisses() { return missCount; }
    int meterClears() { return clearCount; }
    int meterPins() { return pinAppliedCount; }
    int meterTClamps() { return tClampCount; }

    /** Zero the meter for the next transit (called AFTER the emit — {@link #clear} keeps the
     *  counters alive so the transit-END chokepoint's own clear still gets counted). */
    void resetMeter() {
        sampleCount = 0;
        snapCount = 0;
        missCount = 0;
        clearCount = 0;
        pinAppliedCount = 0;
        tClampCount = 0;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Shortest-arc degree lerp (350°→10° crosses 0°) — SnapshotInterpolator provenance. */
    private static float lerpAngleDeg(float a, float b, float t) {
        float diff = wrap360(b - a + 180f) - 180f; // shortest signed delta in [-180, 180)
        return a + diff * t;
    }

    private static float wrap360(float deg) {
        float m = deg % 360f;
        return m < 0f ? m + 360f : m;
    }
}
