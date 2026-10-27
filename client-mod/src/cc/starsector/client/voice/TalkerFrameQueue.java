package cc.starsector.client.voice;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

import cc.starsector.server.runtime.VoiceFrameCodec;

/**
 * The ONE bounded net-to-audio queue for decoded VCDN talker frames (build brief
 * D5). WHY bounded drop-oldest: the net reader thread must never block on audio, and a stalled
 * audio thread must never let memory grow — voice is real-time, the newest frame is the valuable
 * one. Capacity 64 = plenty for 8 talkers x 4-frame jitter target + a burst; overflow is counted so
 * the debrief can see it. Thread-safe (net thread offers, audio thread polls).
 */
public final class TalkerFrameQueue {
    public static final int CAPACITY = 64;

    /** A frame + the NET thread's {@code nanoTime} at {@link #offer} — the true
     *  arrival, not the audio tick that later drains the queue (which quantized every arrival to
     *  the pop cadence and hid the arrival/pop phase the jitter buffer's wait is made of). */
    public record Arrival(VoiceFrameCodec.TalkerFrame frame, long nanos) {
    }

    private final ArrayDeque<Arrival> deque = new ArrayDeque<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong offered = new AtomicLong();

    /** Enqueue (stamped now); when full the OLDEST frame is discarded and counted. Never blocks. */
    public void offer(VoiceFrameCodec.TalkerFrame frame) {
        if (frame == null) {
            return;
        }
        offered.incrementAndGet();
        Arrival a = new Arrival(frame, System.nanoTime());
        synchronized (deque) {
            if (deque.size() >= CAPACITY) {
                deque.pollFirst();
                dropped.incrementAndGet();
            }
            deque.addLast(a);
        }
    }

    /** Oldest arrival or null. Never blocks. */
    public Arrival poll() {
        synchronized (deque) {
            return deque.pollFirst();
        }
    }

    public int size() {
        synchronized (deque) {
            return deque.size();
        }
    }

    public void clear() {
        synchronized (deque) {
            deque.clear();
        }
    }

    public long dropped() {
        return dropped.get();
    }

    public long offered() {
        return offered.get();
    }
}
