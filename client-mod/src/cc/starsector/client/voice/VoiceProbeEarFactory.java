package cc.starsector.client.voice;

import java.util.function.Consumer;

import cc.starsector.client.voice.ffm.FfmProbeEar;

/**
 * Starts the mouth-to-ear meter's LOOPBACK EAR: the FFM
 * WASAPI loopback of the default render endpoint ({@code ffm/FfmProbeEar}) on a Java ≥ 22
 * Windows-x64 JVM (the kit's bundled Temurin 25), else nothing — the sink ear in
 * {@code VoicePlayback} still runs, and {@link VoiceProbe} prints its rows with the loopback
 * column empty. Dev-only: {@code VoiceProbe#earMode() == EAR_BOTH} is the caller's gate.
 *
 * <p>CLASS-VERSION LAW: exactly {@link VoiceSourceFactory} — compiled {@code --release 17} by the
 * Temurin-25 javac (pass B) and the ONE 17-side reference to {@code FfmProbeEar}: a single
 * {@code invokestatic} whose declared types are all 17 types ({@link AutoCloseable},
 * {@link Consumer}). No field, local, cast, {@code instanceof} or {@code catch} type here is an
 * {@code ffm} class, so the stock Zulu-17 game JVM never links major 66 (JVMS section 5.4.3.3).
 */
public final class VoiceProbeEarFactory {
    private VoiceProbeEarFactory() {
    }

    /**
     * The open ear, or {@code null} with one honest 'unavailable' row on {@code log} (a 17 JVM,
     * a non-Windows box, no render endpoint, COM/link failure, an unsupported mix format).
     */
    public static AutoCloseable start(Consumer<String> log) {
        if (!VoiceSinkFactory.dragonEligible()) {
            say(log, "voice probe ear: unavailable (needs the kit Java 25 JRE)");
            return null;
        }
        try {
            return FfmProbeEar.start(log);
        } catch (Throwable t) {
            // LinkageError family (a < 22 JVM), the ear's own IllegalStateException (no render
            // endpoint / COM failure) or anything else — never an ffm type here.
            say(log, "voice probe ear: unavailable (" + describe(t) + ")");
            return null;
        }
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
