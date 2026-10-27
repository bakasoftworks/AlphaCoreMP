package cc.starsector.client.voice.ffm;

import java.lang.foreign.Arena;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import cc.starsector.client.voice.CaptureFormatLadder;
import cc.starsector.client.voice.CaptureRing;
import cc.starsector.client.voice.PcmResampler;
import cc.starsector.client.voice.PcmSource;

/**
 * The W12-DRAGON {@link PcmSource}: a daemon reader ({@code cc-voice-dragon-cap}) drains every
 * engine packet of a {@link WasapiCaptureStream} on its event into a bounded mono-int16 ring that
 * the capture thread's blocking {@link #read} pops (the capture mirror of
 * {@link FfmVoiceBackend}).
 *
 * <p>WHY a reader thread + ring and not COM on the capture thread itself: {@code VoiceCapture.close()}
 * tears the source down from the OWNER thread, and a COM apartment object must only ever be touched
 * by the thread that made it — so the reader owns CoInitialize + the confined {@link Arena} + the
 * stream and tears all of it down in its own {@code finally}; the owner-thread {@link #close} is a
 * stop flag + notify + join, exactly {@link FfmVoiceBackend#close}. The ring itself is the 17-side
 * {@link CaptureRing} (latency-first newest-wins law, proven on the Temurin-17 selftest);
 * capacity = the capture's depth ask scaled to the mix rate but never under four engine periods;
 * nothing is allocated per packet or per read. SILENT packets write zeros (the line keeps its
 * cadence); DISCONTINUITY is counted.
 *
 * <p>SELF-TIMESTAMPING: every packet carries the engine's QPC position; HotSpot's
 * {@code nanoTime} IS the QPC clock on Windows, so {@code nanoTime − qpc×100} at {@code GetBuffer}
 * return = the acquisition latency with no stimulus and no mic — a bounded reservoir of the last
 * {@value #LAT_SAMPLES} values feeds the close line's {@code dtLat p50/p99}.
 *
 * <p>FALLBACK: open failure (no capture endpoint, COM/link failure, unsupported mix format) throws
 * {@link IllegalStateException} from {@link #create} — the 17-side factory logs it and opens the JDK
 * ladder. A stream lost mid-session (device invalidated, event starved 3 × {@code WAIT_MS}) makes
 * the reader re-open the JDK {@code TargetDataLine} and push ITS reads into the SAME ring through
 * {@link PcmResampler} (only when the ring rate is 48 k — the resampler's one output rate); if that
 * cannot open, the source goes DEAD and {@link #read} returns 0 without blocking, so
 * {@code VoiceCapture}'s existing F4 path declares CAPTURE_LOST after one paced second.
 *
 * <p>TEST SEAM: the package-private constructor takes the stream and the
 * fallback line as suppliers — {@code null} streams = the real WASAPI path (COM on the reader); a
 * supplied {@link CaptureStream} skips COM entirely, so the {@code selftest25} reader-law rows
 * script every wake/packet and observe the fallback on a fake line. The public entry always binds
 * the real pair.
 */
public final class FfmVoiceCapture implements PcmSource, Runnable {
    /** 1 s at 192 kHz — the allocation bound for the ring. */
    public static final int MAX_RING_SAMPLES = 192000;
    static final int MIN_RING_PERIODS = 4;
    static final int LAT_SAMPLES = 512;
    private static final long OPEN_WAIT_MS = 3000L;

    /**
     * The ONE 17-side entry ({@code VoiceSourceFactory}): returns the seam type only, so a Java-17
     * verifier never sees a class of this package. Throws {@link IllegalStateException} (single
     * line, numbers only) when the stream cannot open.
     */
    public static PcmSource create(int bufferSamples48k, Consumer<String> log) {
        FfmVoiceCapture c = new FfmVoiceCapture(bufferSamples48k, log);
        c.open();
        return c;
    }

    private final Consumer<String> log;
    private final int bufferSamples48k;
    /** {@code null} = open the real {@link WasapiCaptureStream} on the reader (COM + arena). */
    private final Supplier<CaptureStream> streams;
    /** The JDK ladder behind {@link #fallbackToLine} (throws = no line opened). */
    private final Callable<CaptureFormatLadder.Opened> lines;
    private final CaptureRing ring = new CaptureRing();
    private volatile int sampleRate;
    private volatile boolean stop;
    private Thread reader;
    /** The JDK line of a live {@link #fallbackToLine}, so the owner-thread {@link #close} can unblock its read. */
    private volatile PcmSource fallback;
    private CountDownLatch opened;
    private volatile String openError;
    // reader-thread counters (read by close() after join)
    private long wakes;
    private long timeouts;
    private long packets;
    private long silentPackets;
    private long discontinuities;
    private long framesIn;
    private final long[] latNs = new long[LAT_SAMPLES];
    private long latCount; // long: an int index would go negative after ~248 days
    private int periodFrames;
    private short[] scratch;

    private FfmVoiceCapture(int bufferSamples48k, Consumer<String> log) {
        this(bufferSamples48k, log, null, null);
    }

    /** Test seam (class javadoc): {@code streams == null} = the real WASAPI stream; {@code lines == null} = the real JDK ladder. */
    FfmVoiceCapture(int bufferSamples48k, Consumer<String> log, Supplier<CaptureStream> streams, Callable<CaptureFormatLadder.Opened> lines) {
        this.bufferSamples48k = Math.max(1, bufferSamples48k);
        this.log = log;
        this.streams = streams;
        this.lines = lines != null ? lines : () -> CaptureFormatLadder.open(null, this.bufferSamples48k);
    }

    /** Starts the reader and waits for its open line (or its failure); throws = nothing opened. */
    void open() {
        stop = false;
        openError = null;
        opened = new CountDownLatch(1);
        Thread t = new Thread(this, "cc-voice-dragon-cap");
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

    @Override
    public int read(byte[] buf, int off, int len) {
        return ring.read(buf, off, len); // blocks until a sample or the ring is released (close / reader exit)
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int getBufferSize() {
        return ring.capacity();
    }

    @Override
    public void close() {
        stop = true;
        ring.release();
        PcmSource fb = fallback;
        if (fb != null) {
            fb.close(); // stop()+close() unblocks the reader parked in TargetDataLine.read
        }
        Thread t = reader;
        reader = null;
        if (t != null) {
            join(t, 2500L);
            if (wakes > 0) {
                say("voice capture closed: packets=" + packets + " silent=" + silentPackets + " discont="
                        + discontinuities + " wakes=" + wakes + " timeouts=" + timeouts + " framesIn=" + framesIn
                        + " droppedB=" + ring.droppedBytes() + " dtLatMs=" + latencyText());
            }
        }
    }

    /** {@code p50/p99} of the acquisition-latency reservoir in ms, "-" when empty. */
    private String latencyText() {
        int n = (int) Math.min(latCount, LAT_SAMPLES);
        if (n == 0) {
            return "-";
        }
        long[] s = Arrays.copyOf(latNs, n);
        Arrays.sort(s);
        return String.format("%.1f/%.1f", s[n / 2] / 1e6, s[Math.min(n - 1, (int) (n * 0.99))] / 1e6);
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
                if (streams == null) {
                    com = new Com(arena);
                    int hr = com.coInitialize(WasapiRenderStream.COINIT_APARTMENTTHREADED);
                    if (hr < 0) {
                        throw new Com.HrException("CoInitializeEx", hr);
                    }
                    coInit = true;
                    w = WasapiCaptureStream.open(com, arena);
                } else {
                    w = streams.get(); // test seam: no COM, no arena use
                }
                if (stop) {
                    return; // open() timed out and fell back: never start the mic, never log the open line
                }
                periodFrames = w.periodFrames();
                long want = (long) bufferSamples48k * w.mixRate() / PcmResampler.OUT_RATE;
                int samples = (int) Math.max(Math.min(want, MAX_RING_SAMPLES), Math.min((long) MIN_RING_PERIODS * periodFrames, MAX_RING_SAMPLES));
                ring.allocate(samples);
                scratch = new short[w.bufferFrames()];
                sampleRate = w.mixRate();
                w.start();
                wasOpen = true;
                say("voice capture: wasapi shared " + w.mixRate() + " Hz " + (w.isFloat() ? "f32" : "i16") + " x" + w.mixChannels()
                        + ", period " + periodFrames + " fr, buffer " + w.bufferFrames() + " fr, ring " + ring.capacity()
                        + " B, mmcss=" + (w.mmcssOk() ? "yes" : "no") + " (default endpoint)");
                opened.countDown(); // AFTER the open line (the render leg's review F3)
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
                say("voice capture: stream lost (" + failure + "); JDK line into the ring");
                fallbackToLine();
            }
        } catch (Throwable t) {
            if (failure == null) {
                failure = describe(t);
            }
        } finally {
            if (failure != null && !wasOpen) {
                openError = failure;
            }
            ring.release(); // the reader is gone: a blocked read returns 0 (DEAD) from here on
            opened.countDown();
        }
    }

    /** Wait → drain EVERY queued packet (a wake can hold more than one) → ring. */
    private void drainLoop(CaptureStream w) throws Throwable {
        int starved = 0;
        while (!stop) {
            int r = w.waitWake();
            if (stop) {
                break;
            }
            if (r == WasapiRenderStream.WAIT_TIMEOUT) {
                timeouts++;
                // A stream whose event stops firing without a final HRESULT (device switch/unplug)
                // must not wedge the mic forever: three silent waits = lost ⇒ the JDK-line fallback.
                if (++starved >= 3) {
                    throw new IllegalStateException("event starved");
                }
                continue;
            }
            starved = 0;
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
                framesIn += frames;
                int flags = w.lastFlags();
                if ((flags & WasapiCaptureStream.FLAG_SILENT) != 0) {
                    silentPackets++;
                }
                if ((flags & WasapiCaptureStream.FLAG_DISCONTINUITY) != 0) {
                    discontinuities++;
                }
                long qpc = w.lastQpc100ns();
                if ((flags & WasapiCaptureStream.FLAG_TIMESTAMP_ERROR) == 0 && qpc > 0) {
                    latNs[(int) (latCount % LAT_SAMPLES)] = w.lastStampNs() - qpc * 100L;
                    latCount++;
                }
                ring.push(scratch, frames);
            }
        }
    }

    /**
     * Runtime fallback: the JDK ladder's line, converted to the ring's rate by {@link PcmResampler}
     * (48 k only — its one output rate), pushed one engine period at a time. Ends on stop, on a
     * dead line (read ≤ 0) or when nothing opens — the source is then DEAD (read returns 0).
     */
    private void fallbackToLine() {
        if (sampleRate != PcmResampler.OUT_RATE) {
            say("voice capture: ring rate " + sampleRate + " has no JDK fallback; capture lost");
            return;
        }
        CaptureFormatLadder.Opened o;
        try {
            o = lines.call();
        } catch (Exception e) {
            say("voice capture: JDK line unavailable (" + e.getClass().getSimpleName() + "); capture lost");
            return;
        }
        fallback = o.source;
        if (stop) {
            o.source.close();
            fallback = null;
            return;
        }
        PcmResampler rs = o.resampler();
        int outSamples = Math.max(1, periodFrames);
        byte[] raw = new byte[rs.maxInputFor(outSamples) * rs.bytesPerFrame];
        short[] pcm = new short[outSamples];
        try {
            while (!stop) {
                int inFrames = rs.inputNeeded(outSamples);
                int bytes = inFrames * rs.bytesPerFrame;
                int off = 0;
                while (off < bytes && !stop) {
                    int n = o.source.read(raw, off, bytes - off);
                    if (n <= 0) {
                        return;
                    }
                    off += n;
                }
                if (off < bytes) {
                    return;
                }
                rs.convert(raw, inFrames, pcm, outSamples);
                ring.push(pcm, outSamples);
            }
        } finally {
            fallback = null;
            o.source.close();
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
