package cc.starsector.client.voice.ffm;

import java.util.Optional;
import java.util.function.Consumer;

import cc.starsector.client.voice.VoiceProbe;

/**
 * The OWNERLESS LOOPBACK EAR: a game-free {@code main} that opens
 * the mouth-to-ear meter's loopback ear ({@link FfmProbeEar}: WASAPI loopback of the default
 * RENDER endpoint + {@code VoiceProbeTone.Detector}) on the kit's Java-25 image and prints its rows
 * to stdout. WHY: the m2e rig's unattended window rides the install's Zulu 17 (the trace agent
 * Fatals on Temurin 25), so the in-window ear is 'unavailable' there and the
 * rig only ever saw {@code capture->sink}; every remaining latency lever (the late pop
 * {@code -Dcc.voice.popOffsetMs}, the sink fork's own depth) lives BETWEEN the sink hand-off and the
 * speaker, i.e. exactly what this ear hears and that one cannot. One box = one clock: HotSpot
 * {@code nanoTime} on Windows IS the QPC counter with no per-process offset ({@code FfmVoiceCapture}),
 * so the {@code voice ear: loopback t=<ms>} rows printed here pair with the injector
 * window's {@code voice probe: #k inject t=<ms>} rows ACROSS processes — the rig's {@code -Shape pair}
 * debrief already does that pairing; {@code run-voice-m2e-watch.ps1 -LoopbackEar} launches this
 * beside window 1 with its stdout as {@code window-2-sidelog.log}.
 *
 * <p>Rows (stdout, flushed per line, ASCII): the ear's own open / close / stream-lost lines
 * ({@link FfmProbeEar}), {@code voice ear: loopback t=<ms>} per detected onset (via
 * {@link VoiceProbe#noteLoopback} — with no inject stamp in this JVM it always prints the bare ear
 * row), and {@code voice ear main: ...} for start / exit.
 *
 * <p>Usage: {@code java --enable-native-access=ALL-UNNAMED -cp cc-client-mod.jar
 * cc.starsector.client.voice.ffm.LoopbackEarMain [watchPid] [maxSeconds]} — exits when the watched
 * pid (the injector window) is gone, or after {@code maxSeconds} (default 900), whichever first;
 * exit 2 = the ear could not open (row says why). Rides the mod jar (pass A, {@code --release 22})
 * so the rig runs the SAME bytes the game loads; never referenced in-game (classloader-B never
 * links it), and its own refs are B-legal ({@code java.io.PrintStream} is on B's allow list).
 */
public final class LoopbackEarMain {
    private static final int DEFAULT_MAX_SECONDS = 900;
    private static final long POLL_MS = 500L;

    private LoopbackEarMain() {
    }

    public static void main(String[] args) {
        long watchPid = args.length > 0 ? parseLong(args[0], -1L) : -1L;
        long maxSeconds = args.length > 1 ? parseLong(args[1], DEFAULT_MAX_SECONDS) : DEFAULT_MAX_SECONDS;
        Consumer<String> out = new Stdout();
        VoiceProbe.bindLog(out);
        out.accept("voice ear main: java " + Runtime.version() + " watchPid=" + watchPid + " maxSeconds=" + maxSeconds
                + " t=" + ms(System.nanoTime()));
        AutoCloseable ear;
        try {
            ear = FfmProbeEar.start(out);
        } catch (Throwable t) {
            out.accept("voice ear main: FAILED to open the loopback ear (" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + ")");
            System.exit(2);
            return;
        }
        long end = System.nanoTime() + maxSeconds * 1_000_000_000L;
        String why = "maxSeconds reached";
        try {
            while (System.nanoTime() < end) {
                if (watchPid > 0L && !alive(watchPid)) {
                    why = "watched pid " + watchPid + " exited";
                    break;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            why = "interrupted";
        }
        try {
            ear.close();
        } catch (Exception ignored) {
            // close() never throws; belt and braces.
        }
        out.accept("voice ear main: exit (" + why + ") t=" + ms(System.nanoTime()));
        System.exit(0);
    }

    private static boolean alive(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        return h.isPresent() && h.get().isAlive();
    }

    private static long parseLong(String s, long dflt) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** ns → "123.4" (ms, one decimal) — the same shape as {@code VoiceProbe.ms}. */
    private static String ms(long ns) {
        long tenths = Math.round(ns / 100_000.0);
        String sign = tenths < 0 ? "-" : "";
        tenths = Math.abs(tenths);
        return sign + (tenths / 10) + "." + (tenths % 10);
    }

    /** stdout, flushed per row — the rig redirects it to a file and reads it after the run. */
    private static final class Stdout implements Consumer<String> {
        @Override
        public void accept(String line) {
            System.out.println(line);
            System.out.flush();
        }
    }
}
