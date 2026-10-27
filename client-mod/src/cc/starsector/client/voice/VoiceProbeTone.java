package cc.starsector.client.voice;

/**
 * The mouth-to-ear PROBE TONE: what the injector synthesises and what the two
 * ears listen for. One 1 kHz sine burst of {@value #BURST_MS} ms at amplitude {@value #AMPLITUDE}
 * (−8.7 dBFS), abrupt onset (a sharp edge is what the ears time; Opus VOIP at the sheet's bitrate
 * reproduces a 1 kHz tone with its fundamental intact — proven by the codec round-trip row in
 * {@code VoiceClientLawSelftest}).
 *
 * <p>{@link Detector} = a Goertzel bin at {@value #TONE_HZ} Hz over 2 ms sub-blocks with a
 * tone-to-total energy ratio test, so a broadband game mix (explosions, engines) under the loopback
 * ear never fires it, and a hysteresis re-arm so one burst = exactly one onset. Rate-aware: the
 * WASAPI loopback ear runs at the endpoint's mix rate (44.1 / 48 / 96 k), the sink ear at 48 k.
 * Pure Java, 17-side, no allocation per feed.
 */
public final class VoiceProbeTone {
    public static final int TONE_HZ = 1000;
    public static final int BURST_MS = 100;
    public static final int AMPLITUDE = 12000;
    /** Synth rate = the encoder's rate ({@code PcmResampler.OUT_RATE}); 48 samples per 1 kHz cycle. */
    public static final int SYNTH_RATE = 48000;
    private static final int CYCLE = SYNTH_RATE / TONE_HZ;
    private static final short[] TABLE = new short[CYCLE];

    static {
        for (int i = 0; i < CYCLE; i++) {
            TABLE[i] = (short) Math.round(AMPLITUDE * Math.sin(2.0 * Math.PI * i / CYCLE));
        }
    }

    private VoiceProbeTone() {
    }

    /** {@code n} tone samples at 48 k into {@code dst[off..)}, phase-continuous from absolute sample {@code sampleIndex}. */
    public static void fill(short[] dst, int off, int n, long sampleIndex) {
        int p = (int) Math.floorMod(sampleIndex, (long) CYCLE);
        for (int i = 0; i < n; i++) {
            dst[off + i] = TABLE[p];
            if (++p == CYCLE) {
                p = 0;
            }
        }
    }

    /**
     * Onset detector. {@link #feed} consumes mono int16 samples whose FIRST sample was captured /
     * handed off at {@code firstSampleNs} (nanoTime domain) and returns the onset time of the first
     * 2 ms sub-block that carries the tone (−1 = no new onset). Sub-blocks straddle feed calls (a
     * carry buffer keeps the partial block and its start stamp). Laws: fires once per burst — after
     * an onset the detector is disarmed until {@value #REARM_BLOCKS} consecutive tone-free blocks
     * (≥ 50 ms of silence / speech / game mix) re-arm it; a block is "tone" when its RMS is at least
     * {@value #MIN_RMS} AND the 1 kHz bin holds at least {@value #MIN_RATIO} of the block's energy.
     */
    public static final class Detector {
        static final int REARM_BLOCKS = 25;
        // Was 1500 (−27 dBFS) — the endpoint's volume is applied BEFORE the WASAPI
        // loopback tap, so the loopback ear heard the 12000-amplitude tone at RMS 750–890 (ratio 1.00,
        // owner's volume ≈ 10 %) and never armed. 100 (−50 dBFS) keeps the tone audible to the ear down to
        // ≈ 1 % volume; the 1 kHz ratio test stays the discriminator (white noise ≈ 2/N ≈ 0.02 per bin).
        static final int MIN_RMS = 100;
        static final double MIN_RATIO = 0.4;

        private final int rate;
        private final int blockSamples;
        private final double coeff;
        private final short[] carry;
        private int carried;
        private long blockStartNs;
        private boolean armed = true;
        private int quietBlocks;
        private long onsets;

        public Detector(int sampleRate) {
            this.rate = Math.max(8000, sampleRate);
            this.blockSamples = Math.max(8, this.rate / 500); // 2 ms
            int k = (int) Math.round((double) blockSamples * TONE_HZ / this.rate);
            this.coeff = 2.0 * Math.cos(2.0 * Math.PI * k / blockSamples);
            this.carry = new short[blockSamples];
        }

        public int blockSamples() {
            return blockSamples;
        }

        public long onsets() {
            return onsets;
        }

        /** Onset stamp (ns) of the first tone block in this feed, else −1. */
        public long feed(short[] pcm, int off, int n, long firstSampleNs) {
            long found = -1L;
            for (int i = 0; i < n; i++) {
                if (carried == 0) {
                    blockStartNs = firstSampleNs + i * 1_000_000_000L / rate;
                }
                carry[carried++] = pcm[off + i];
                if (carried == blockSamples) {
                    carried = 0;
                    boolean tone = isTone(carry, blockSamples);
                    if (tone) {
                        quietBlocks = 0;
                        if (armed) {
                            armed = false;
                            onsets++;
                            if (found < 0L) {
                                found = blockStartNs;
                            }
                        }
                    } else if (!armed && ++quietBlocks >= REARM_BLOCKS) {
                        armed = true;
                    }
                }
            }
            return found;
        }

        /** Goertzel power at the bin vs total energy: {@code 2|X|² / (N·Σx²) >= MIN_RATIO} and RMS >= MIN_RMS. */
        boolean isTone(short[] x, int len) {
            double s1 = 0, s2 = 0, energy = 0;
            for (int i = 0; i < len; i++) {
                double v = x[i];
                energy += v * v;
                double s0 = v + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            if (energy < (double) MIN_RMS * MIN_RMS * len) {
                return false;
            }
            double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
            return 2.0 * power >= MIN_RATIO * len * energy;
        }
    }
}
