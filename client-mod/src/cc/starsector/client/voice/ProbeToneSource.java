package cc.starsector.client.voice;

import java.util.concurrent.locks.LockSupport;

/**
 * The INJECTOR: a {@link PcmSource} that replaces the microphone under
 * {@code -Dcc.voice.probeTone} (the design: "window A injects a stamped burst at the
 * capture-pipeline entry"). 48 kHz mono int16, paced by a sample clock anchored to
 * {@code nanoTime} (a read blocks until its frame is due, so {@code VoiceCapture}'s loop runs at
 * exactly the mic cadence; a stall > one period re-anchors instead of bursting catch-up frames).
 * Every {@link VoiceProbe#periodMs()} a {@link VoiceProbeTone#BURST_MS} ms tone starts at sample
 * offset 0 of the period; the read that emits its first sample stamps {@link VoiceProbe#noteInject}
 * AFTER the pacing wait (= the moment the frame is handed to gain → encode → uplink). Between
 * tones the source yields zeros, never the mic.
 *
 * <p>PTT is held SYNTHETICALLY through {@link VoicePtt#probeHold} (immune to the render thread's
 * lost-key-up poll): {@code cold} presses at each burst start and releases {@value #HOLD_MS} ms
 * later (tone + a silent tail so the wire burst ends cleanly) — every number then includes the
 * stream-open jitter fill, the first-syllable case; {@code warm} holds for the whole session —
 * the mid-sentence case. The schedule is a pure function of the absolute sample index
 * ({@link #frame}), so the selftest proves it without a clock.
 */
public final class ProbeToneSource implements PcmSource {
    static final int RATE = VoiceProbeTone.SYNTH_RATE;
    static final int TONE_SAMPLES = VoiceProbeTone.BURST_MS * RATE / 1000;
    static final int HOLD_MS = 500;
    static final int HOLD_SAMPLES = HOLD_MS * RATE / 1000;
    /** Behind by more than this ⇒ re-anchor the clock (never a burst of catch-up frames). */
    private static final long REANCHOR_NS = 250_000_000L;

    private final VoicePtt ptt;
    private final boolean warm;
    private final long periodSamples;
    private long sampleIndex;
    private long anchorNs = -1L;
    private int bursts;
    private volatile boolean closed;

    public ProbeToneSource(VoicePtt ptt, int toneMode, int periodMs) {
        this.ptt = ptt;
        this.warm = toneMode == VoiceProbe.TONE_WARM;
        this.periodSamples = (long) VoiceProbe.periodMs(Integer.toString(periodMs)) * RATE / 1000;
    }

    /** The sysprop-configured injector for the live client. */
    public static ProbeToneSource fromSysprops(VoicePtt ptt) {
        return new ProbeToneSource(ptt, VoiceProbe.toneMode(), VoiceProbe.periodMs());
    }

    public int bursts() {
        return bursts;
    }

    /**
     * Pure schedule: fill {@code n} samples for absolute sample {@code s} (tone inside the first
     * {@link #TONE_SAMPLES} of each period, else zeros). Returns a bit set: 1 = this frame carries a
     * burst START (period offset 0 lies inside it), 2 = PTT must be HELD for this frame.
     */
    public int frame(long s, int n, short[] dst) {
        long pos = Math.floorMod(s, periodSamples);
        boolean crosses = pos + n > periodSamples;
        boolean start = pos == 0L || crosses;
        boolean hold = warm || pos < HOLD_SAMPLES || crosses;
        long p = pos;
        for (int i = 0; i < n; i++) {
            if (p < TONE_SAMPLES) {
                VoiceProbeTone.fill(dst, i, 1, p);
            } else {
                dst[i] = 0;
            }
            if (++p == periodSamples) {
                p = 0L;
            }
        }
        return (start ? 1 : 0) | (hold ? 2 : 0);
    }

    @Override
    public int read(byte[] buf, int off, int len) {
        if (closed) {
            return 0;
        }
        int n = len / 2;
        if (n <= 0) {
            return 0;
        }
        long now = System.nanoTime();
        if (anchorNs < 0L) {
            anchorNs = now;
        }
        long due = anchorNs + (sampleIndex + n) * 1_000_000_000L / RATE;
        long wait = due - now;
        if (wait > 0L) {
            LockSupport.parkNanos(wait);
            if (Thread.interrupted() || closed) {
                Thread.currentThread().interrupt();
                return 0;
            }
        } else if (-wait > REANCHOR_NS) {
            anchorNs = now - sampleIndex * 1_000_000_000L / RATE;
        }
        short[] pcm = new short[n];
        int bits = frame(sampleIndex, n, pcm);
        ptt.probeHold((bits & 2) != 0);
        for (int i = 0; i < n; i++) {
            buf[off + 2 * i] = (byte) pcm[i];
            buf[off + 2 * i + 1] = (byte) (pcm[i] >> 8);
        }
        if ((bits & 1) != 0) {
            VoiceProbe.noteInject(++bursts, System.nanoTime());
        }
        sampleIndex += n;
        return 2 * n;
    }

    @Override
    public int sampleRate() {
        return RATE;
    }

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public void close() {
        closed = true;
        ptt.probeHold(false);
    }
}
