package cc.starsector.client.voice;

/**
 * The mixer's one way out: a blocking PCM sink.
 * WHY a seam: the L3 impl is the pure-JDK {@link SourceDataLineSink}; the later
 * FFM sink feeds this same seam, and the selftest can hand the mixer a fake.
 * The blocking {@link #write} is what paces the audio thread — no timers, no busy loops.
 */
public interface PcmSink {
    /**
     * Open for 16-bit signed little-endian PCM. {@code bufferFrames} = requested line depth in
     * sample-frames (per channel); returns the GRANTED buffer size in bytes (logged once).
     * Throws {@link IllegalStateException} (single-line message, no device text) when no line
     * can be opened.
     */
    int open(int sampleRate, int channels, int bufferFrames);

    /** Blocking write of interleaved LE PCM bytes. */
    void write(byte[] pcm, int off, int len);

    /**
     * The audio this sink holds AHEAD of the frame a {@link #write}
     * just returned for, in ms — the HUD "buf" row's line-depth term. The JDK line holds its
     * granted buffer (2 frames); the dragon holds at most one engine period plus its ring lead.
     * Valid after {@link #open}; 0 = unknown (the mixer then assumes its 2-frame default).
     */
    default int depthMs() {
        return 0;
    }

    void close();
}
