package cc.starsector.client.voice.ffm;

import java.lang.foreign.Arena;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import cc.starsector.client.voice.VoiceProbe;
import cc.starsector.client.voice.VoiceProbeTone;

/**
 * The LOOPBACK EAR of the mouth-to-ear meter: a daemon
 * reader ({@code cc-voice-probe-ear}) polls a {@link WasapiCaptureStream#openLoopback loopback}
 * capture of the default RENDER endpoint — the engine's own post-mix output — folds every packet
 * to mono int16, runs a {@link VoiceProbeTone.Detector} over it with the packet's engine QPC stamp
 * as the time base and reports each tone onset to {@link VoiceProbe#noteLoopback}.
 *
 * <p>WHY this is the honest ear: the sink ear times the tone at {@code mix → sink.write}; the
 * loopback ear times it where the endpoint mixed it, so the sink's own buffer depth + the engine
 * period are INSIDE the number. One box = one clock: HotSpot {@code nanoTime} on Windows IS the QPC
 * counter with no per-process offset ({@link FfmVoiceCapture}), so
 * {@code onset = qpc × 100 + intra-packet sample offset} pairs with the injector's and the sink
 * ear's {@code System.nanoTime()} stamps — across threads and across processes.
 *
 * <p>THREADING = {@link FfmVoiceCapture#run} verbatim: the reader owns CoInitialize + the confined
 * {@link Arena} + the stream and tears all of it down in its own {@code finally}; the owner-thread
 * {@link #close} is a stop flag + join, idempotent, never throws. {@link #start} waits on the open
 * latch and throws {@link IllegalStateException} (single line, numbers only) when nothing opened,
 * so the 17-side {@code VoiceProbeEarFactory} logs one honest 'unavailable' row.
 *
 * <p>PACKET LAW: every queued packet is drained per poll; SILENT packets are fed as zeros (they
 * keep the detector's block clock + re-arm hysteresis honest); a packet flagged
 * {@code TIMESTAMP_ERROR} or carrying no QPC is skipped for timing (counted, never fed with a lie).
 * Nothing is allocated per packet. Native-seam law + classloader-B legality: as {@link Com}.
 */
public final class FfmProbeEar implements AutoCloseable, Runnable {
    private static final long OPEN_WAIT_MS = 3000L;
    private static final long CLOSE_JOIN_MS = 2500L;

    /**
     * The ONE 17-side entry ({@code VoiceProbeEarFactory}): declared types are all 17 types, so a
     * Java-17 verifier never sees a class of this package. Throws {@link IllegalStateException}
     * when the loopback stream cannot open (no render endpoint, COM failure, unsupported format).
     */
    public static AutoCloseable start(Consumer<String> log) {
        FfmProbeEar e = new FfmProbeEar(log);
        e.open();
        return e;
    }

    private final Consumer<String> log;
    private volatile boolean stop;
    private Thread reader;
    private CountDownLatch opened;
    private volatile String openError;
    // reader-thread state + counters (read by close() after join)
    private VoiceProbeTone.Detector det;
    private short[] scratch;
    private long wakes;
    private long packets;
    private long silentPackets;
    private long discontinuities;
    private long untimed;
    private long onsets;

    private FfmProbeEar(Consumer<String> log) {
        this.log = log;
    }

    /** Starts the reader and waits for its open line (or its failure); throws = nothing opened. */
    private void open() {
        stop = false;
        openError = null;
        opened = new CountDownLatch(1);
        Thread t = new Thread(this, "cc-voice-probe-ear");
        t.setDaemon(true);
        reader = t;
        t.start();
        boolean signalled = false;
        try {
            signalled = opened.await(OPEN_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (signalled && openError == null) {
            return;
        }
        String why = openError == null ? "open timeout" : openError;
        stop = true;
        join(t, 1000L);
        reader = null;
        throw new IllegalStateException(why);
    }

    /** Stop flag + join (owner thread); the close line carries the counters. Idempotent, never throws. */
    @Override
    public void close() {
        stop = true;
        Thread t = reader;
        reader = null;
        if (t != null) {
            join(t, CLOSE_JOIN_MS);
            say("voice probe ear closed: packets=" + packets + " silent=" + silentPackets + " discont=" + discontinuities
                    + " untimed=" + untimed + " onsets=" + onsets + " wakes=" + wakes);
        }
    }

    private static void join(Thread t, long ms) {
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- the reader thread

    @Override
    public void run() {
        String failure = null;
        boolean wasOpen = false;
        try (Arena arena = Arena.ofConfined()) {
            Com com = null;
            boolean coInit = false;
            CaptureStream w = null;
            try {
                com = new Com(arena);
                int hr = com.coInitialize(WasapiRenderStream.COINIT_APARTMENTTHREADED);
                if (hr < 0) {
                    throw new Com.HrException("CoInitializeEx", hr);
                }
                coInit = true;
                w = WasapiCaptureStream.openLoopback(com, arena);
                if (stop) {
                    return; // start() timed out: never start the stream, never log the open line
                }
                scratch = new short[w.bufferFrames()];
                det = new VoiceProbeTone.Detector(w.mixRate());
                w.start();
                wasOpen = true;
                say("voice probe ear: loopback " + w.mixRate() + " Hz " + (w.isFloat() ? "f32" : "i16") + " x" + w.mixChannels()
                        + ", period " + w.periodFrames() + " fr, buffer " + w.bufferFrames() + " fr, mmcss=" + (w.mmcssOk() ? "yes" : "no"));
                opened.countDown(); // AFTER the open line
                drainLoop(w);
            } catch (Throwable t) {
                failure = describe(t);
            } finally {
                if (w != null) {
                    w.close();
                }
                if (coInit) {
                    try {
                        com.coUninit();
                    } catch (Throwable ignored) {
                        // Best effort at teardown.
                    }
                }
            }
            if (failure != null && wasOpen && !stop) {
                say("voice probe ear: stream lost (" + failure + ")");
            }
        } catch (Throwable t) {
            if (failure == null) {
                failure = describe(t);
            }
        } finally {
            if (failure != null && !wasOpen) {
                openError = failure;
            }
            opened.countDown();
        }
    }

    /** Poll → drain EVERY queued packet → detector (QPC × 100 = the nanoTime-domain stamp of the packet's first frame). */
    private void drainLoop(CaptureStream w) throws Throwable {
        while (!stop) {
            int r = w.waitWake();
            if (stop) {
                break;
            }
            if (r != WasapiRenderStream.WAIT_OBJECT_0) {
                throw new IllegalStateException("wait " + r);
            }
            wakes++;
            while (!stop && w.nextPacketFrames() > 0) {
                int frames = w.readPacket(scratch);
                if (frames <= 0) {
                    break;
                }
                packets++;
                int flags = w.lastFlags();
                if ((flags & WasapiCaptureStream.FLAG_SILENT) != 0) {
                    silentPackets++;
                }
                if ((flags & WasapiCaptureStream.FLAG_DISCONTINUITY) != 0) {
                    discontinuities++;
                }
                long qpc = w.lastQpc100ns();
                if ((flags & WasapiCaptureStream.FLAG_TIMESTAMP_ERROR) != 0 || qpc <= 0L) {
                    untimed++;
                    continue;
                }
                long onset = det.feed(scratch, 0, frames, qpc * 100L);
                if (onset >= 0L) {
                    onsets++;
                    VoiceProbe.noteLoopback(onset);
                }
            }
        }
    }

    private static String describe(Throwable t) {
        String m = t instanceof Com.HrException || t instanceof IllegalStateException ? t.getMessage() : null;
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private void say(String line) {
        if (log != null) {
            log.accept(line);
        }
    }
}
