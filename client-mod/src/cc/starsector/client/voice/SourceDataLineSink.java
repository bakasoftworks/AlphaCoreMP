package cc.starsector.client.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * {@link PcmSink} over {@code javax.sound.sampled.SourceDataLine} (build brief
 * D1): stereo 48 kHz 16-bit signed LE, ZERO OpenAL. WHY not the engine's OpenAL: its floor is a
 * computed ~93 ms; SourceDataLine measured 26.5 ms submit-to-mix (latency doc), and the
 * FFM dragon needs this exact mixer as its fallback/baseline. The line is asked for exactly the
 * caller's buffer (2 frames per D1); the granted size is read back for the ONE log line.
 * {@code javax.sound.sampled} is classloader-B legal (build report).
 */
public final class SourceDataLineSink implements PcmSink {
    private SourceDataLine line;
    /** The granted buffer in ms — a full line is what a returned write leaves queued. */
    private volatile int depthMs;

    @Override
    public int open(int sampleRate, int channels, int bufferFrames) {
        close();
        AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
        int bytes = bufferFrames * channels * 2;
        try {
            SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
            l.open(fmt, bytes);
            l.start();
            line = l;
            depthMs = (int) ((long) l.getBufferSize() * 1000L / (channels * 2L) / sampleRate);
            return l.getBufferSize();
        } catch (Exception e) {
            // LineUnavailableException / IllegalArgumentException / SecurityException: one plain
            // single-line reason, never the device text.
            throw new IllegalStateException("voice playback line unavailable: "
                    + e.getClass().getSimpleName());
        }
    }

    @Override
    public void write(byte[] pcm, int off, int len) {
        SourceDataLine l = line;
        if (l != null) {
            l.write(pcm, off, len);
        }
    }

    @Override
    public int depthMs() {
        return line == null ? 0 : depthMs;
    }

    @Override
    public void close() {
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.close();
            } catch (RuntimeException e) {
                // Best effort at teardown.
            }
        }
    }
}
