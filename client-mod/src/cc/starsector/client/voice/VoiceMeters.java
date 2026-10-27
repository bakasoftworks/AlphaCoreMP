package cc.starsector.client.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cross-thread voice meters (build brief D12): the audio thread publishes each
 * talker's decoded-PCM RMS level + last-frame nanos; the render-thread presence lane reads them to
 * drive the speaker glyph tiers and the HUD "talking:" row with a 200 ms hold. Capture
 * publishes the own-mic state (PTT held, silent-mic frame count) for the HUD warning. WHY a map of
 * atomics instead of a queue: the render thread only ever wants the latest value, never history.
 */
public final class VoiceMeters {
    /** Presence hold after the last decoded frame (D7/D12). */
    public static final long HOLD_NANOS = 200_000_000L;

    /** One talker's live meter (mutable atomics; the object itself is never replaced). */
    public static final class Talker {
        public final int seatId;
        public final AtomicLong anchorWireId = new AtomicLong(-1L);
        /** RMS level 0..32767 of the last decoded frame. */
        public final AtomicInteger level = new AtomicInteger();
        public final AtomicLong lastFrameNanos = new AtomicLong();
        public final AtomicInteger flags = new AtomicInteger();

        Talker(int seatId) {
            this.seatId = seatId;
        }
    }

    private final Map<Integer, Talker> talkers = new ConcurrentHashMap<>();
    /** Own-mic state for the HUD: PTT mode (0 off / 1 global / 2 proximity). */
    public final AtomicInteger pttMode = new AtomicInteger();
    /** Consecutive all-zero capture frames while PTT was held (silent-mic warning, D12). */
    public final AtomicInteger silentCaptureFrames = new AtomicInteger();
    /** RMS 0..32767 of the last CAPTURED (post-gain) frame while PTT is held: the own glyph's
     *  arcs light from this, echo or not (without echo no own stream exists,
     *  so the own glyph drew tier 0 forever — "the animated parts are just gone"). */
    public final AtomicInteger ownLevel = new AtomicInteger();
    /** True when capture is disabled (unsupported format / no mixer / mic.on=false). */
    public final AtomicInteger captureState = new AtomicInteger();
    public static final int CAPTURE_OFF = 0;
    public static final int CAPTURE_LIVE = 1;
    public static final int CAPTURE_UNSUPPORTED = 2;
    /** The open line stopped delivering audio (device unplugged / Bluetooth drop):
     * the capture thread exited; a mic.on flip in {@code /settings} restarts it. */
    public static final int CAPTURE_LOST = 3;
    /** Last measured own-voice echo round trip in nanos (only when the sheet's echo flag is set). */
    public final AtomicLong echoRttNanos = new AtomicLong(-1L);
    /** Nanos of the last {@link #echoRttNanos} stamp (the HUD shows
     * the {@code voice rtt} row only within 1.5 s of it; -1 = never). */
    public final AtomicLong echoStampNanos = new AtomicLong(-1L);
    /** Own-stream jitter target + line depth in ms at that stamp (the "buf" half of the row). */
    public final AtomicInteger echoBufMs = new AtomicInteger();
    /** The deepest ACTIVE talker stream's jitter target + line depth in ms (0 when
     * nobody is being heard) — the "buf" half of the rtt-stats row without echo. */
    public final AtomicInteger talkerBufMs = new AtomicInteger();
    /** The open sink's real held depth in ms ({@code PcmSink.depthMs}; 0 = unknown ⇒
     * the 2-frame default) — the line-depth term of every "buf" figure above. */
    public final AtomicInteger lineDepthMs = new AtomicInteger();
    /** Frames the decode guard refused (D6) — aggregate, for the debrief. */
    public final AtomicLong decodeRefused = new AtomicLong();
    public final AtomicLong plcFrames = new AtomicLong();

    /** Audio thread: publish one decoded frame's level for a talker. */
    public void publish(int seatId, long anchorWireId, int flags, int rmsLevel, long nowNanos) {
        Talker t = talkers.computeIfAbsent(seatId, Talker::new);
        t.anchorWireId.set(anchorWireId);
        t.flags.set(flags);
        t.level.set(rmsLevel);
        t.lastFrameNanos.set(nowNanos);
    }

    /** Render thread: talkers whose last frame is within the hold window. */
    public List<Talker> activeTalkers(long nowNanos) {
        List<Talker> out = new ArrayList<>(4);
        for (Talker t : talkers.values()) {
            long last = t.lastFrameNanos.get();
            if (last != 0L && nowNanos - last <= HOLD_NANOS) {
                out.add(t);
            }
        }
        return out;
    }

    public Talker talker(int seatId) {
        return talkers.get(seatId);
    }

    public void clear() {
        talkers.clear();
        pttMode.set(0);
        silentCaptureFrames.set(0);
        ownLevel.set(0);
    }
}
