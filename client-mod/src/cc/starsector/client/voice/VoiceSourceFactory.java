package cc.starsector.client.voice;

import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import cc.starsector.client.voice.ffm.FfmVoiceCapture;

/**
 * Picks the capture thread's {@link PcmSource}: the W12-DRAGON WASAPI capture on a Java ≥ 22
 * Windows-x64 JVM (the kit's bundled Temurin 25) when the mic is the DEFAULT endpoint, else the
 * pure-JDK {@link CaptureFormatLadder} line (the render mirror is
 * {@link VoiceSinkFactory}).
 *
 * <p>DEVICE LAW: the dragon opens ONLY the default capture endpoint
 * ({@code eCommunications} role — the voice-chat mic convention), so a non-empty
 * {@code mic.device} keeps the JDK ladder, which honours the name exactly
 * ({@link CaptureFormatLadder#pickMixer}); no {@code /settings} row is forced.
 *
 * <p>CLASS-VERSION LAW: exactly {@link VoiceSinkFactory} — compiled {@code --release 17} by the
 * Temurin-25 javac (pass B) and the ONE 17-side reference to {@code FfmVoiceCapture}: a single
 * {@code invokestatic} whose declared return type is the seam. No field, local, cast,
 * {@code instanceof} or {@code catch} type here is an {@code ffm} class, so the stock Zulu-17 game
 * JVM never links major 66 (JVMS section 5.4.3.3); the Temurin-17 selftest runs {@link #open} forced as the
 * isolation proof.
 *
 * <p>Knob (dev/A-B, INFRA — never posture): {@code -Dcc.voice.source=auto|line|dragon} (default
 * auto; {@code line} = the JDK ladder even on 25 — the one-flag revert; {@code dragon} = try the
 * dragon regardless of the gate, a named mic included — on a 17 JVM that lands in the
 * {@link Throwable} catch and falls back).
 */
public final class VoiceSourceFactory {
    private VoiceSourceFactory() {
    }

    /**
     * Opens the capture source for {@code micDevice} ("" = default) with a {@code bufferSamples48k}
     * depth ask. The dragon's {@code Opened} carries {@code line == null} and a
     * "{@code mixRate} Hz mono LE" format (the ring's format).
     *
     * @throws Exception the ladder's LAST failure when no source opens at all
     */
    public static CaptureFormatLadder.Opened open(String micDevice, int bufferSamples48k,
            Consumer<String> log) throws Exception {
        String dev = micDevice == null ? "" : micDevice;
        String pick = System.getProperty("cc.voice.source", "auto");
        if ("line".equalsIgnoreCase(pick)) {
            say(log, "voice source: JDK line (cc.voice.source=line)");
            return CaptureFormatLadder.open(CaptureFormatLadder.pickMixer(dev), bufferSamples48k);
        }
        boolean forced = "dragon".equalsIgnoreCase(pick);
        if (forced || dragonEligible(dev)) {
            try {
                PcmSource s = FfmVoiceCapture.create(bufferSamples48k, log);
                if (s != null) {
                    return new CaptureFormatLadder.Opened(s,
                            new AudioFormat(s.sampleRate(), 16, 1, true, false));
                }
            } catch (Throwable t) {
                // LinkageError family (forced on a < 22 JVM), the dragon's own IllegalStateException
                // (no capture endpoint / COM failure) or anything else — never an ffm type here.
                say(log, "voice source: dragon unavailable (" + describe(t) + "); JDK line");
            }
        }
        return CaptureFormatLadder.open(CaptureFormatLadder.pickMixer(dev), bufferSamples48k);
    }

    /** The runtime gate: {@link VoiceSinkFactory#dragonEligible} AND the default mic. */
    public static boolean dragonEligible(String micDevice) {
        return VoiceSinkFactory.dragonEligible() && (micDevice == null || micDevice.isEmpty());
    }

    private static String describe(Throwable t) {
        String m = t instanceof IllegalStateException ? t.getMessage() : null;
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static void say(Consumer<String> log, String line) {
        if (log != null) {
            log.accept(line);
        }
    }
}
