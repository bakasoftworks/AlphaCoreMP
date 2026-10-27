package cc.starsector.client.voice;

import javax.sound.sampled.TargetDataLine;

/**
 * {@link PcmSource} over an open, started {@code javax.sound.sampled.TargetDataLine}
 * — the shipped capture path, behaviour byte-identical to the pre-seam
 * {@code VoiceCapture} (it called exactly {@code read}/{@code getBufferSize}/{@code stop}/
 * {@code close} on the line). {@code TargetDataLine.read} blocks until {@code len} bytes arrive and
 * returns 0 without blocking once the device is gone — the seam's dead contract for free.
 * {@code javax.sound.sampled} is classloader-B legal.
 */
public final class LinePcmSource implements PcmSource {
    private final TargetDataLine line;
    private final int sampleRate;

    public LinePcmSource(TargetDataLine line, int sampleRate) {
        this.line = line;
        this.sampleRate = sampleRate;
    }

    @Override
    public int read(byte[] buf, int off, int len) {
        return line.read(buf, off, len);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int getBufferSize() {
        return line.getBufferSize();
    }

    /** {@code stop(); close()} — closing unblocks a pending read on another thread (JDK contract). */
    @Override
    public void close() {
        try {
            line.stop();
            line.close();
        } catch (RuntimeException e) {
            // best effort
        }
    }
}
