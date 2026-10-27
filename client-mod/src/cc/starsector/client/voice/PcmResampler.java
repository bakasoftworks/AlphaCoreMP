package cc.starsector.client.voice;

/**
 * Stateful PCM conversion from a captured line format to the encoder's 48 kHz mono (
 * W12 VOICE residue): 16-bit signed input at ANY rate, 1 or 2
 * channels, either byte order → 16-bit mono at {@link #OUT_RATE}. Pure (no JDK audio types) so
 * the selftest pins it; identity when the line is already 48 k mono (every output equals its
 * input sample — the frac is always 0, no arithmetic drift).
 *
 * <p>WHY linear interpolation, not a windowed sinc: the encoder is Opus SILK/CELT voice at
 * 48 kbps — its own internal resampler and the 8 kHz-ish speech band dominate; a linear
 * interpolator's HF roll-off above ~10 kHz is inaudible on push-to-talk speech, costs ~1 µs per
 * frame, and has no lookahead (zero added latency). Stereo is downmixed {@code (L+R)/2} BEFORE
 * interpolation.
 *
 * <p>Phase law: output sample {@code k} (absolute index) sits at input position
 * {@code k * inRate / outRate}, computed in exact {@code long} arithmetic each call, so the
 * mapping never drifts across frames however many frames run. The caller asks
 * {@link #inputNeeded(int)} for the exact number of input frames (mono positions) to read for the
 * next {@code outSamples} outputs, reads exactly that many, then calls
 * {@link #convert(byte[], int, short[], int)} — the two most recent input samples are carried
 * across calls so the interpolation at a frame boundary is continuous. Over-supplying input is a
 * caller bug (the extra samples are kept and shift later phase); under-supplying throws.
 */
public final class PcmResampler {
    public static final int OUT_RATE = 48000;

    public final int inRate;
    public final int channels;
    public final boolean bigEndian;
    /** Bytes per input frame (one sample position across all channels). */
    public final int bytesPerFrame;

    private long outIndex;
    /** Total input frames consumed so far (absolute index of the next unread input frame). */
    private long inCount;
    /** Input samples at absolute indices {@code inCount-2} and {@code inCount-1} (mono, downmixed). */
    private int hist0;
    private int hist1;

    public PcmResampler(int inRate, int channels, boolean bigEndian) {
        if (inRate < 1000 || inRate > 400000) {
            throw new IllegalArgumentException("inRate " + inRate);
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("channels " + channels);
        }
        this.inRate = inRate;
        this.channels = channels;
        this.bigEndian = bigEndian;
        this.bytesPerFrame = channels * 2;
    }

    /** True iff every output equals its input sample (48 k mono, any byte order). */
    public boolean identity() {
        return inRate == OUT_RATE && channels == 1;
    }

    /**
     * Input frames the next {@link #convert} of {@code outSamples} needs beyond those already
     * consumed (≥ 0). Read exactly this many frames ({@code × bytesPerFrame} bytes).
     */
    public int inputNeeded(int outSamples) {
        if (outSamples <= 0) {
            return 0;
        }
        long lastNum = (outIndex + outSamples - 1) * inRate;
        long idx = lastNum / OUT_RATE;
        long needTotal = idx + (lastNum % OUT_RATE == 0 ? 1 : 2);
        long need = needTotal - inCount;
        return need < 0 ? 0 : (int) need;
    }

    /**
     * Upper bound of {@link #inputNeeded} for {@code outSamples} outputs from any phase — sizes
     * the read buffer once.
     */
    public int maxInputFor(int outSamples) {
        return (int) (((long) outSamples * inRate + OUT_RATE - 1) / OUT_RATE) + 2;
    }

    /**
     * Converts {@code inFrames} input frames from {@code raw} (offset 0, interleaved, this
     * resampler's byte order) into {@code outSamples} mono 48 kHz samples in {@code out}.
     *
     * @throws IllegalArgumentException when fewer input frames than {@link #inputNeeded} were given
     */
    public void convert(byte[] raw, int inFrames, short[] out, int outSamples) {
        int need = inputNeeded(outSamples);
        if (inFrames < need) {
            throw new IllegalArgumentException("need " + need + " input frames, got " + inFrames);
        }
        long base = inCount;
        for (int k = 0; k < outSamples; k++) {
            long num = (outIndex + k) * inRate;
            long idx = num / OUT_RATE;
            int frac = (int) (num % OUT_RATE);
            int a = sampleAt(raw, inFrames, base, idx);
            int v;
            if (frac == 0) {
                v = a;
            } else {
                int b = sampleAt(raw, inFrames, base, idx + 1);
                // exact: a + (b-a)*frac/OUT_RATE, rounded to nearest
                long d = (long) (b - a) * frac;
                v = a + (int) ((d + (d >= 0 ? OUT_RATE / 2 : -(OUT_RATE / 2))) / OUT_RATE);
            }
            out[k] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
        }
        if (inFrames >= 2) {
            hist0 = mono(raw, inFrames - 2);
            hist1 = mono(raw, inFrames - 1);
        } else if (inFrames == 1) {
            hist0 = hist1;
            hist1 = mono(raw, 0);
        }
        inCount += inFrames;
        outIndex += outSamples;
    }

    private int sampleAt(byte[] raw, int inFrames, long base, long idx) {
        long rel = idx - base;
        if (rel >= inFrames) {
            // only reachable when frac != 0 and the caller under-supplied — hold the last sample
            rel = inFrames - 1;
        }
        if (rel >= 0) {
            return mono(raw, (int) rel);
        }
        return rel == -1 ? hist1 : hist0;
    }

    private int mono(byte[] raw, int frame) {
        int off = frame * bytesPerFrame;
        int s0 = sample16(raw, off);
        if (channels == 1) {
            return s0;
        }
        int s1 = sample16(raw, off + 2);
        return (s0 + s1) >> 1;
    }

    private int sample16(byte[] raw, int off) {
        return bigEndian
                ? (short) (((raw[off] & 0xFF) << 8) | (raw[off + 1] & 0xFF))
                : (short) ((raw[off] & 0xFF) | (raw[off + 1] << 8));
    }

    /** Total input frames consumed so far (selftest drift row). */
    public long inputConsumed() {
        return inCount;
    }

    /** Total output samples produced so far. */
    public long outputProduced() {
        return outIndex;
    }
}
