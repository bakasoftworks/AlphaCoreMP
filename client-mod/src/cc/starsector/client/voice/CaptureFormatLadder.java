package cc.starsector.client.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Capture-format negotiation (W12 VOICE residue): opens a {@link TargetDataLine}
 * on the wanted mixer at the FIRST format of {@link #LADDER} the mixer accepts, so a microphone
 * that cannot do 48 kHz mono 16-bit LE (macOS CoreAudio exposes only the device's own rate,
 * ALSA {@code hw:} devices refuse resampling, some USB mics are 44.1 k/stereo only) still feeds
 * the encoder through {@link PcmResampler}. Pure JDK ({@code javax.sound.sampled}, sandbox-legal,
 * b-deny-clean) so the joiner window's Test bar (install JRE, mod jar only) shares it with the
 * in-game capture and reports the SAME negotiated format the match will use.
 *
 * <p>Ladder order = quality first, then cost: 48 k mono (identity) → 48 k stereo (downmix only)
 * → 44.1 k → 32 k → 24 k → 16 k (voice band intact) → 96 k → 22.05 k → 8 k; mono before stereo,
 * little-endian before big-endian at each rate. Every entry is 16-bit signed PCM.
 */
public final class CaptureFormatLadder {
    private static final int[] RATES = {48000, 44100, 32000, 24000, 16000, 96000, 22050, 8000};

    /** Formats tried in order; index 0 is the native 48 k mono 16-bit LE. */
    public static final AudioFormat[] LADDER = build();

    private static AudioFormat[] build() {
        AudioFormat[] out = new AudioFormat[RATES.length * 4];
        int i = 0;
        for (int rate : RATES) {
            for (int ch = 1; ch <= 2; ch++) {
                out[i++] = new AudioFormat(rate, 16, ch, true, false);
                out[i++] = new AudioFormat(rate, 16, ch, true, true);
            }
        }
        return out;
    }

    /**
     * An open, started {@link PcmSource} plus the format it delivers. {@link #line} is the JDK
     * {@code TargetDataLine} behind {@link #source} when the ladder opened it (the joiner's Test bar
     * reads it directly) and {@code null} for a {@link VoiceSourceFactory} dragon source.
     *
     */
    public static final class Opened {
        public final PcmSource source;
        public final TargetDataLine line;
        public final AudioFormat format;

        Opened(TargetDataLine line, AudioFormat format) {
            this.line = line;
            this.format = format;
            this.source = new LinePcmSource(line, (int) format.getSampleRate());
        }

        /** A non-line source ({@code line == null}). */
        public Opened(PcmSource source, AudioFormat format) {
            this.source = source;
            this.line = null;
            this.format = format;
        }

        /** The converter for this line's format. */
        public PcmResampler resampler() {
            return new PcmResampler((int) format.getSampleRate(), format.getChannels(),
                    format.isBigEndian());
        }
    }

    /**
     * Opens + starts the first ladder format the mixer accepts.
     *
     * @param mixer null = the system default mixer
     * @param bufferSamples48k wanted line buffer in 48 kHz mono samples (scaled to the format)
     * @throws Exception the LAST failure when no ladder format opens
     */
    public static Opened open(Mixer.Info mixer, int bufferSamples48k) throws Exception {
        Exception last = null;
        for (AudioFormat fmt : LADDER) {
            TargetDataLine l = null;
            try {
                l = mixer == null ? AudioSystem.getTargetDataLine(fmt)
                        : AudioSystem.getTargetDataLine(fmt, mixer);
                l.open(fmt, bufferBytes(fmt, bufferSamples48k));
                l.start();
                AudioFormat got = l.getFormat();
                return new Opened(l, got == null ? fmt : got);
            } catch (Exception e) {
                last = e;
                if (l != null) {
                    try {
                        l.close();
                    } catch (RuntimeException ignored) {
                        // best effort
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException("empty ladder") : last;
    }

    /** Line-buffer bytes for {@code bufferSamples48k} of audio at {@code fmt}. */
    static int bufferBytes(AudioFormat fmt, int bufferSamples48k) {
        long frames = ((long) bufferSamples48k * (long) fmt.getSampleRate()
                + PcmResampler.OUT_RATE - 1) / PcmResampler.OUT_RATE;
        return (int) frames * fmt.getChannels() * 2;
    }

    /**
     * First CAPTURE mixer whose name contains {@code wanted} (case-insensitive); null = default.
     * DirectSound interleaves playback and capture entries ("Speakers (Realtek…)"
     * before "Microphone (Realtek…)"), so a short hand-edited {@code mic.device} must never bind a
     * playback mixer — only mixers that support a {@code TargetDataLine} are candidates. Lives
     * HERE (moved from {@code VoiceCapture}): the pass-B bridge
     * {@link VoiceSourceFactory} and the capture rig need the pick without dragging
     * {@code VoiceCapture}'s Concentus/wire-codec imports onto their classpath.
     */
    public static Mixer.Info pickMixer(String wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return null;
        }
        String w = wanted.toLowerCase(java.util.Locale.ROOT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            String name = info.getName();
            if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains(w)
                    && supportsCapture(info)) {
                return info;
            }
        }
        return null;
    }

    public static boolean supportsCapture(Mixer.Info info) {
        try {
            return AudioSystem.getMixer(info).isLineSupported(new Line.Info(TargetDataLine.class));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code "48000 Hz mono LE"} — the log / status-line rendering of a format. */
    public static String describe(AudioFormat fmt) {
        return (int) fmt.getSampleRate() + " Hz " + (fmt.getChannels() == 1 ? "mono" : "stereo")
                + (fmt.isBigEndian() ? " BE" : " LE");
    }

    /** True iff {@code fmt} needs no conversion (48 k mono, either byte order). */
    public static boolean isNative(AudioFormat fmt) {
        return (int) fmt.getSampleRate() == PcmResampler.OUT_RATE && fmt.getChannels() == 1;
    }

    private CaptureFormatLadder() {
    }
}
