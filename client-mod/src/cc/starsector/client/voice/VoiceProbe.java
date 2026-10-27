package cc.starsector.client.voice;

import java.util.function.Consumer;

/**
 * The programmatic mouth-to-ear meter (
 * {@code w12-voice-chat-latency.md}): dev-only, sysprop-gated, OFF in every shipped window.
 *
 * <p>Shape: the INJECTOR window runs {@code -Dcc.voice.probeTone[=cold|warm]} — its mic is replaced
 * by {@link ProbeToneSource} (a stamped {@link VoiceProbeTone} burst every {@code probePeriodMs},
 * PTT held synthetically; {@code cold} = a fresh PTT burst per tone so every number includes the
 * stream-open jitter fill, {@code warm} = one held stream, tones inside it). The EAR window runs
 * {@code -Dcc.voice.probeEar[=both|sink]} — {@code VoicePlayback} times the tone at its sink
 * hand-off ({@code mix → sink.write}), and the FFM loopback ear ({@code VoiceProbeEarFactory} →
 * {@code ffm/FfmProbeEar}, kit Java-25 only) times it in the endpoint's engine mix with the
 * packet's QPC stamp. One box = one clock: HotSpot {@code nanoTime} on Windows IS the QPC counter
 * with no per-process offset ({@code FfmVoiceCapture}), so stamps pair ACROSS processes.
 *
 * <p>Rows (all through the bound log; {@code t=} values are nanoTime in ms, absolute):
 * <ul>
 * <li>{@code voice probe: #k inject t=<ms>} — the injector, per burst (its first tone sample).</li>
 * <li>{@code voice m2e: #k capture->sink <ms> capture->loopback <ms|-> (sink->loopback <ms>)} — an
 *     ear that holds the inject stamp IN-PROCESS (the single-window echo shape:
 *     {@code -Dcc.voice.clientEcho=true}, the relay bundles own frames back).</li>
 * <li>{@code voice ear: sink t=<ms>} / {@code voice ear: loopback t=<ms>} — an ear window with no
 *     inject of its own (the two-window shape); a report script pairs them
 *     against the injector window's probe rows.</li>
 * </ul>
 * Excluded by design: mic ADC + DAC/driver (single-digit ms). Statics on purpose: three threads
 * (capture, audio, ear reader) meet here and the probe is one-per-JVM; every method is cheap and
 * a no-op when the probe is off.
 */
public final class VoiceProbe {
    public static final String PROP_TONE = "cc.voice.probeTone";
    public static final String PROP_EAR = "cc.voice.probeEar";
    public static final String PROP_PERIOD = "cc.voice.probePeriodMs";

    public static final int TONE_OFF = 0;
    public static final int TONE_COLD = 1;
    public static final int TONE_WARM = 2;
    public static final int EAR_OFF = 0;
    public static final int EAR_SINK = 1;
    public static final int EAR_BOTH = 2;

    public static final int DEFAULT_PERIOD_MS = 2000;
    public static final int MIN_PERIOD_MS = 500;
    public static final int MAX_PERIOD_MS = 10000;
    /** A burst older than this at an ear is never paired (mouth-to-ear bound). */
    static final long PAIR_WINDOW_NS = 1_500_000_000L;
    private static final int RING = 8;

    private static final int TONE_MODE = toneMode(System.getProperty(PROP_TONE));
    private static final int EAR_MODE = earMode(System.getProperty(PROP_EAR), TONE_MODE);
    private static final int PERIOD_MS = periodMs(System.getProperty(PROP_PERIOD));

    private static final Object LOCK = new Object();
    private static Consumer<String> log;
    private static final int[] burst = new int[RING];
    private static final long[] injectNs = new long[RING];
    private static final long[] sinkNs = new long[RING];
    private static final long[] loopNs = new long[RING];
    private static final boolean[] printed = new boolean[RING];
    private static int count;

    private VoiceProbe() {
    }

    // ---------------------------------------------------------------- sysprop laws (pure)

    /** unset / {@code false} / {@code off} = OFF; bare / {@code true} / {@code cold} = COLD; {@code warm} = WARM. */
    public static int toneMode(String raw) {
        if (raw == null) {
            return TONE_OFF;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("false") || v.equals("off") || v.equals("0")) {
            return TONE_OFF;
        }
        return v.equals("warm") ? TONE_WARM : TONE_COLD;
    }

    /** unset ⇒ BOTH when the tone is on (single-window echo shape), else OFF; {@code sink} = sink only; {@code false}/{@code off} = OFF. */
    public static int earMode(String raw, int toneMode) {
        if (raw == null) {
            return toneMode == TONE_OFF ? EAR_OFF : EAR_BOTH;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("false") || v.equals("off") || v.equals("0")) {
            return EAR_OFF;
        }
        return v.equals("sink") ? EAR_SINK : EAR_BOTH;
    }

    /** {@value #DEFAULT_PERIOD_MS} unless a parseable int, clamped to [{@value #MIN_PERIOD_MS}, {@value #MAX_PERIOD_MS}]. */
    public static int periodMs(String raw) {
        if (raw == null) {
            return DEFAULT_PERIOD_MS;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v < MIN_PERIOD_MS ? MIN_PERIOD_MS : (v > MAX_PERIOD_MS ? MAX_PERIOD_MS : v);
        } catch (NumberFormatException e) {
            return DEFAULT_PERIOD_MS;
        }
    }

    public static int toneMode() {
        return TONE_MODE;
    }

    public static int earMode() {
        return EAR_MODE;
    }

    public static int periodMs() {
        return PERIOD_MS;
    }

    public static String describe() {
        return "tone=" + (TONE_MODE == TONE_OFF ? "off" : TONE_MODE == TONE_COLD ? "cold" : "warm")
                + " ear=" + (EAR_MODE == EAR_OFF ? "off" : EAR_MODE == EAR_SINK ? "sink" : "both")
                + " period=" + PERIOD_MS + " ms";
    }

    // ---------------------------------------------------------------- the meter registry

    /** The row sink (the mod's sidelog in production; a list in the selftest). */
    public static void bindLog(Consumer<String> l) {
        synchronized (LOCK) {
            log = l;
        }
    }

    /** Selftest: forget every stamp. */
    public static void reset() {
        synchronized (LOCK) {
            count = 0;
        }
    }

    /** The injector: burst {@code k}'s first tone sample left the capture-pipeline entry at {@code ns}. */
    public static void noteInject(int k, long ns) {
        synchronized (LOCK) {
            int i = count++ % RING;
            burst[i] = k;
            injectNs[i] = ns;
            sinkNs[i] = -1L;
            loopNs[i] = -1L;
            printed[i] = false;
            say("voice probe: #" + k + " inject t=" + ms(ns));
        }
    }

    /** The sink ear: a tone onset was handed to the sink at {@code ns}. */
    public static void noteSink(long ns) {
        synchronized (LOCK) {
            int i = pair(ns, false);
            if (i < 0) {
                say("voice ear: sink t=" + ms(ns));
                return;
            }
            sinkNs[i] = ns;
            // A sink-only ear prints now; a both-ears window waits for the loopback (or flushes the
            // stale entry when the NEXT sink onset lands, so a lost loopback never hides a number).
            if (EAR_MODE != EAR_BOTH) {
                print(i);
            } else {
                flushOlder(i);
            }
        }
    }

    /** The loopback ear: a tone onset was in the endpoint's engine mix at {@code ns} (QPC domain). */
    public static void noteLoopback(long ns) {
        synchronized (LOCK) {
            int i = pair(ns, true);
            if (i < 0) {
                say("voice ear: loopback t=" + ms(ns));
                return;
            }
            loopNs[i] = ns;
            if (printed[i]) {
                // The sink row already went out (sink-only ear / flushed entry): an addendum row.
                say("voice m2e: #" + burst[i] + " capture->loopback " + ms(ns - injectNs[i])
                        + " (sink->loopback " + ms(ns - sinkNs[i]) + ")");
                return;
            }
            print(i);
        }
    }

    /** Newest entry within the pair window that still lacks this ear's stamp (loopback needs a sink first). */
    private static int pair(long ns, boolean loopback) {
        int n = Math.min(count, RING);
        int best = -1;
        long bestInject = Long.MIN_VALUE;
        for (int j = 0; j < n; j++) {
            int i = (count - 1 - j + RING) % RING;
            long dt = ns - injectNs[i];
            if (dt < 0L || dt > PAIR_WINDOW_NS) {
                continue;
            }
            boolean open = loopback ? (sinkNs[i] >= 0L && loopNs[i] < 0L) : sinkNs[i] < 0L;
            if (open && injectNs[i] > bestInject) {
                best = i;
                bestInject = injectNs[i];
            }
        }
        return best;
    }

    private static void flushOlder(int keep) {
        int n = Math.min(count, RING);
        for (int j = 0; j < n; j++) {
            int i = (count - 1 - j + RING) % RING;
            if (i != keep && !printed[i] && sinkNs[i] >= 0L && injectNs[i] < injectNs[keep]) {
                print(i);
            }
        }
    }

    private static void print(int i) {
        if (printed[i]) {
            return;
        }
        printed[i] = true;
        StringBuilder sb = new StringBuilder(96);
        sb.append("voice m2e: #").append(burst[i])
                .append(" capture->sink ").append(sinkNs[i] < 0L ? "-" : ms(sinkNs[i] - injectNs[i]))
                .append(" capture->loopback ").append(loopNs[i] < 0L ? "-" : ms(loopNs[i] - injectNs[i]));
        if (sinkNs[i] >= 0L && loopNs[i] >= 0L) {
            sb.append(" (sink->loopback ").append(ms(loopNs[i] - sinkNs[i])).append(')');
        }
        say(sb.toString());
    }

    /** ns → "123.4" (ms, one decimal; ASCII only — the sidelog is the consumer). */
    static String ms(long ns) {
        long tenths = Math.round(ns / 100_000.0);
        String sign = tenths < 0 ? "-" : "";
        tenths = Math.abs(tenths);
        return sign + (tenths / 10) + "." + (tenths % 10);
    }

    private static void say(String line) {
        Consumer<String> l = log;
        if (l != null) {
            l.accept(line);
        }
    }
}
