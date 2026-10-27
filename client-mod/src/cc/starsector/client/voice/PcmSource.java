package cc.starsector.client.voice;

/**
 * The capture thread's one way in: a blocking PCM source (the mirror of
 * {@link PcmSink}). WHY a seam: the shipped impl is the pure-JDK {@link LinePcmSource} over the
 * {@link CaptureFormatLadder}'s {@code TargetDataLine}; the W12-DRAGON WASAPI capture
 * ({@code voice/ffm/FfmVoiceCapture}, Java ≥ 22 only) feeds this same seam through
 * {@link VoiceSourceFactory}, and {@link VoiceCapture}'s fill loop / dead-line pacing / resampler /
 * encode never learn which one they read.
 *
 * <p>Format law: every source delivers 16-bit signed PCM in the {@code AudioFormat} its
 * {@code CaptureFormatLadder.Opened} carries (the JDK line's negotiated format; the dragon's
 * "mix-rate Hz mono LE"), so {@link PcmResampler} converts either to the encoder's 48 k mono.
 */
public interface PcmSource {
    /**
     * Blocking read of interleaved PCM bytes into {@code buf}. Returns the bytes copied
     * ({@code > 0}), or {@code <= 0} when the source is DEAD (device removed / stream lost) —
     * and then it never blocks (the caller paces by one frame and gives the line
     * up after {@code VoiceCapture#DEAD_LINE_READS} empties). A partial read is legal (the
     * caller loops until its frame is full).
     */
    int read(byte[] buf, int off, int len);

    /** Sample rate of the bytes {@link #read} delivers (matches the {@code Opened.format}). */
    int sampleRate();

    /** Granted buffer depth in bytes — the ONE open log line only. */
    int getBufferSize();

    /**
     * Stops the source and unblocks a pending {@link #read}. Called from the OWNER thread
     * ({@code VoiceCapture.close()}) as well as from the capture thread's own {@code finally}:
     * every impl is safe from any thread and idempotent.
     */
    void close();
}
