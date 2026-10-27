package cc.starsector.client.voice;

import java.util.function.Consumer;

import cc.starsector.client.voice.ffm.FfmVoiceBackend;

/**
 * Picks the mixer's {@link PcmSink}: the W12-DRAGON WASAPI path on a Java ≥ 22 Windows-x64 JVM
 * (the kit's bundled Temurin 25), else the pure-JDK {@link SourceDataLineSink}.
 *
 *
 * <p>CLASS-VERSION LAW (the lazily-loaded nested engine-typed holder idiom of {@code FighterWingHudLane}, stricter): this class is
 * compiled {@code --release 17} by the Temurin-25 javac (the only javac that can READ the major-66
 * {@code ffm} classes it names) and is the ONE 17-side reference to that package — a single
 * {@code invokestatic FfmVoiceBackend.create} whose declared return type is the seam. No field,
 * local, cast, {@code instanceof} or {@code catch} type here may be an {@code ffm} class, so the
 * stock Zulu-17 game JVM's verifier never touches major 66 and the guarded call never executes
 * there (JVMS section 5.4.3.3, lazy resolution). The Temurin-17 selftest JVM runs {@link #create} as the
 * isolation proof (a leak would be an {@code UnsupportedClassVersionError} at link).
 *
 * <p>Knobs (dev/A-B, INFRA — never posture): {@code -Dcc.voice.sink=auto|line|dragon} (default
 * auto; {@code line} = the JDK line even on 25; {@code dragon} = try the dragon regardless of the
 * gate — on a 17 JVM that lands in the {@link Throwable} catch and falls back) and
 * {@code -Dcc.voice.dragonLeadMs=N} (ring lead, default 0; see {@link FfmVoiceBackend}).
 */
public final class VoiceSinkFactory {
    /** {@code java.lang.foreign} went final in 22. */
    public static final int MIN_DRAGON_FEATURE = 22;

    private VoiceSinkFactory() {
    }

    public static PcmSink create(Consumer<String> log) {
        String pick = System.getProperty("cc.voice.sink", "auto");
        if ("line".equalsIgnoreCase(pick)) {
            say(log, "voice sink: JDK line (cc.voice.sink=line)");
            return new SourceDataLineSink();
        }
        boolean forced = "dragon".equalsIgnoreCase(pick);
        if (!forced && !dragonEligible()) {
            // this branch was SILENT and a J17 watch window's floor (line 20 ms ⇒
            // HUD buf 40) read as jitter vs the kit dragon's 30 — the pick is always said now.
            say(log, "voice sink: JDK line (java " + Runtime.version().feature() + " < "
                    + MIN_DRAGON_FEATURE + " or non-Win64)");
            return new SourceDataLineSink();
        }
        Integer lead = Integer.getInteger("cc.voice.dragonLeadMs");
        int leadMs = lead == null ? 0 : Math.max(0, Math.min(FfmVoiceBackend.MAX_LEAD_MS, lead.intValue()));
        try {
            return FfmVoiceBackend.create(leadMs, log);
        } catch (Throwable t) {
            // LinkageError family (forced on a < 22 JVM) or anything else — never an ffm type here.
            say(log, "voice sink: dragon unavailable (" + t.getClass().getSimpleName() + "); JDK line");
        }
        return new SourceDataLineSink();
    }

    /** The runtime gate: Java ≥ 22 (FFM final), Windows, 64-bit. */
    public static boolean dragonEligible() {
        return Runtime.version().feature() >= MIN_DRAGON_FEATURE
                && System.getProperty("os.name", "").startsWith("Windows")
                && System.getProperty("os.arch", "").contains("64");
    }

    private static void say(Consumer<String> log, String line) {
        if (log != null) {
            log.accept(line);
        }
    }
}
