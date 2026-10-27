package cc.starsector.client.voice.ffm;

import java.lang.foreign.Arena;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import cc.starsector.client.voice.PcmSink;
import cc.starsector.client.voice.SourceDataLineSink;

/**
 * The W12-DRAGON {@link PcmSink}: a bounded int16 ring fed by the mixer's blocking
 * {@link #write}, drained on every engine wake by a daemon feeder ({@code cc-voice-dragon}) into a
 * {@link WasapiRenderStream} (port map).
 *
 * <p>WHY a ring + feeder: the mixer produces one 20 ms frame per blocking write and the seam has no
 * depth query — pacing IS the block ({@code PcmSink}). WASAPI wants a ~10 ms event-driven feed, so
 * the sink owns the cadence bridge. <b>Lead law</b> ("a real sink must CONTROL
 * its lead"): a write COPIES its frame into the ring at once, THEN blocks until the ring has
 * drained to at most {@code leadMs} of audio (default 0 = EMPTY). The block is the pacing, but it
 * is paid AFTER the copy: the frame just written sits behind only the engine's own
 * padding — ≈ one engine period + wake wait (≈ 20 ms on a 10 ms-period box) instead of the JDK
 * line's 40 ms depth + DirectSound — and the NEXT frame is popped from the jitter buffer fresh,
 * right when the sink can take it. (A block-BEFORE-copy law ages every popped frame by the whole
 * block, ≈ +20 ms.) The engine buffer is the stall margin; a mix-thread stall longer than it is
 * an audible gap (the engine plays silence when the shared buffer underruns — counted as
 * {@code underruns}, never fatal). {@code -Dcc.voice.dragonLeadMs=N} widens the ring lead for a
 * crackle A/B.
 *
 * <p>FALLBACK (composite): if the stream cannot open (no endpoint, mix rate not 48 k,
 * format neither float32 nor int16, COM/link failure) {@link #open} returns a plain
 * {@link SourceDataLineSink} path and every write goes straight to it; if the stream is lost
 * mid-session (device invalidated / unplugged) the feeder switches to draining the SAME ring into a
 * freshly opened JDK line — the mixer never notices. Only when both fail does the seam's
 * {@link IllegalStateException} reach the mixer (voice audio disabled, one log line).
 *
 * <p>Bounded allocation: the ring is {@code min(bufferFrames, MAX_RING_FRAMES)} frames (the
 * mixer's ask, itself derived from the sheet's clamped {@code frameMs}); a write longer than the
 * ring keeps its newest {@code capacity} bytes; nothing is allocated per write or per wake.
 * Threading: {@link #open}/{@link #write} = the mixer thread; {@link #close} = the owner thread;
 * COM + the confined arena live entirely on the feeder.
 *
 * <p>TEST SEAM: the package-private constructor takes the stream and the
 * fallback line as suppliers — {@code null} streams = the real WASAPI path (COM on the feeder);
 * a supplied {@link RenderStream} skips COM entirely, so the {@code selftest25} feeder-law rows
 * script every wake and observe the fallback on a fake {@link PcmSink}. The public entry always
 * binds the real pair.
 */
public final class FfmVoiceBackend implements PcmSink, Runnable {
    /** 1 s at 48 kHz — the allocation bound for the ring. */
    public static final int MAX_RING_FRAMES = 48000;
    public static final int MAX_LEAD_MS = 200;
    private static final long OPEN_WAIT_MS = 3000L;

    /**
     * The ONE 17-side entry ({@code VoiceSinkFactory}): returns the seam type only, so a Java-17
     * verifier never sees a class of this package.
     */
    public static PcmSink create(int leadMs, Consumer<String> log) {
        return new FfmVoiceBackend(leadMs, log);
    }

    private final Consumer<String> log;
    private final int leadMs;
    /** {@code null} = open the real {@link WasapiRenderStream} on the feeder (COM + arena). */
    private final Supplier<RenderStream> streams;
    /** The JDK line behind both fallbacks (open-time delegate, mid-session drain). */
    private final Supplier<PcmSink> lines;
    private final Object lock = new Object();
    private byte[] ring;
    private int capacity;
    private int head;
    private int count;
    private int bytesPerFrame;
    private int leadBytes;
    private int sampleRate;
    private int channels;
    private int askedFrames;
    private volatile boolean stop;
    private volatile boolean feederDead;
    private Thread feeder;
    /** Open-time fallback: writes bypass the ring. */
    private volatile PcmSink delegate;
    /** The engine period in ms once the stream is open (the held-depth term). */
    private volatile int periodMs;
    private CountDownLatch opened;
    private volatile String openError;
    // feeder-thread counters (read by close() after join)
    private long wakes;
    private long underruns;
    private long drained;
    private long timeouts;
    private long framesFed;
    private long writerBlockedNanos;
    private byte[] scratch;

    private FfmVoiceBackend(int leadMs, Consumer<String> log) {
        this(leadMs, log, null, SourceDataLineSink::new);
    }

    /** Test seam (class javadoc): {@code streams == null} = the real WASAPI stream. */
    FfmVoiceBackend(int leadMs, Consumer<String> log, Supplier<RenderStream> streams, Supplier<PcmSink> lines) {
        this.leadMs = Math.max(0, Math.min(MAX_LEAD_MS, leadMs));
        this.log = log;
        this.streams = streams;
        this.lines = lines;
    }

    @Override
    public int open(int sampleRate, int channels, int bufferFrames) {
        close();
        if (channels < 1 || channels > 2 || sampleRate < 8000 || sampleRate > 192000) {
            throw new IllegalStateException("voice playback ask unsupported");
        }
        int frames = Math.max(1, Math.min(bufferFrames, MAX_RING_FRAMES));
        synchronized (lock) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.askedFrames = bufferFrames;
            bytesPerFrame = channels * 2;
            capacity = frames * bytesPerFrame;
            ring = new byte[capacity];
            head = 0;
            count = 0;
            leadBytes = (int) Math.min(capacity, (long) leadMs * sampleRate / 1000L * bytesPerFrame);
            stop = false;
            feederDead = false;
        }
        wakes = 0;
        underruns = 0;
        drained = 0;
        timeouts = 0;
        framesFed = 0;
        writerBlockedNanos = 0;
        openError = null;
        opened = new CountDownLatch(1);
        Thread t = new Thread(this, "cc-voice-dragon");
        t.setDaemon(true);
        feeder = t;
        t.start();
        boolean signalled = false;
        try {
            signalled = opened.await(OPEN_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (signalled && openError == null) {
            return capacity;
        }
        // Open-time fallback: stop the feeder (it may still be mid-activate), then the JDK line.
        String why = openError == null ? "open timeout" : openError;
        stop = true;
        joinFeeder(t, 1000L);
        feeder = null;
        say("voice dragon: unavailable (" + why + "); JDK line instead");
        PcmSink d = lines.get();
        int granted = d.open(sampleRate, channels, bufferFrames);
        delegate = d;
        return granted;
    }

    /** Lead law ⇒ a returned write leaves at most the engine's own period queued
     *  ahead of that frame, plus the configured ring lead; the JDK fallback reports its line. */
    @Override
    public int depthMs() {
        PcmSink d = delegate;
        if (d != null) {
            return d.depthMs();
        }
        int p = periodMs;
        return p <= 0 ? 0 : p + leadMs;
    }

    @Override
    public void write(byte[] pcm, int off, int len) {
        PcmSink d = delegate;
        if (d != null) {
            d.write(pcm, off, len);
            return;
        }
        if (len <= 0) {
            return;
        }
        synchronized (lock) {
            if (ring == null) {
                return;
            }
            if (len > capacity) {
                off += len - capacity;
                len = capacity;
            }
            // Room wait only (a lead wider than capacity − one frame): never the lead itself.
            while (!stop && !feederDead && count + len > capacity) {
                if (!await()) {
                    return;
                }
            }
            if (stop || feederDead) {
                return;
            }
            int tail = (head + count) % capacity;
            int first = Math.min(len, capacity - tail);
            System.arraycopy(pcm, off, ring, tail, first);
            if (first < len) {
                System.arraycopy(pcm, off + first, ring, 0, len - first);
            }
            count += len;
            lock.notifyAll();
            // Lead law, paid AFTER the copy: hold the mixer until the feeder has drained the ring
            // to ≤ leadBytes, so its next pop is fresh (class javadoc).
            long t0 = System.nanoTime();
            while (!stop && !feederDead && count > leadBytes) {
                if (!await()) {
                    break;
                }
            }
            writerBlockedNanos += System.nanoTime() - t0;
        }
    }

    /** Under {@link #lock}: one bounded wait; false = interrupted (the writer gives up). */
    private boolean await() {
        try {
            lock.wait(100L);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Feeder side: move up to {@code maxFrames} whole frames from the ring into {@code dst}. */
    private int take(byte[] dst, int maxFrames) {
        synchronized (lock) {
            int frames = Math.min(maxFrames, count / bytesPerFrame);
            int bytes = frames * bytesPerFrame;
            if (bytes > 0) {
                int first = Math.min(bytes, capacity - head);
                System.arraycopy(ring, head, dst, 0, first);
                if (first < bytes) {
                    System.arraycopy(ring, 0, dst, first, bytes - first);
                }
                head = (head + bytes) % capacity;
                count -= bytes;
                lock.notifyAll();
            }
            return frames;
        }
    }

    @Override
    public void close() {
        stop = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        Thread t = feeder;
        feeder = null;
        if (t != null) {
            // Join the CAPTURED thread (joining via the field after nulling it
            // was a silent no-op — counters raced, and a re-open could run two feeders).
            joinFeeder(t, 2500L);
            if (wakes > 0) {
                say("voice dragon closed: wakes=" + wakes + " underruns=" + underruns + " drained=" + drained + " timeouts="
                        + timeouts + " framesFed=" + framesFed + " writerBlockMs="
                        + (writerBlockedNanos / 1_000_000L));
            }
        }
        PcmSink d = delegate;
        delegate = null;
        if (d != null) {
            d.close();
        }
        synchronized (lock) {
            ring = null;
            count = 0;
            head = 0;
        }
    }

    private static void joinFeeder(Thread t, long ms) {
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- the feeder thread

    @Override
    public void run() {
        String failure = null;
        boolean wasOpen = false;
        try (Arena arena = Arena.ofConfined()) {
            Com com = null;
            boolean coInit = false;
            RenderStream w = null;
            try {
                if (streams == null) {
                    com = new Com(arena);
                    int hr = com.coInitialize(WasapiRenderStream.COINIT_APARTMENTTHREADED);
                    if (hr < 0) {
                        throw new Com.HrException("CoInitializeEx", hr);
                    }
                    coInit = true;
                    w = WasapiRenderStream.open(com, arena, sampleRate);
                } else {
                    w = streams.get(); // test seam: no COM, no arena use
                }
                if (stop) {
                    return; // open() timed out and fell back: never start the endpoint, never log the open line (capture mirror)
                }
                scratch = new byte[w.bufferFrames() * bytesPerFrame];
                periodMs = (int) Math.max(1L, Math.round(w.periodFrames() * 1000.0 / sampleRate));
                w.start();
                wasOpen = true;
                say("voice dragon: wasapi shared " + w.mixRate() + " Hz " + (w.isFloat() ? "f32" : "i16") + " x"
                        + w.mixChannels() + ", period " + w.periodFrames() + " fr, buffer " + w.bufferFrames()
                        + " fr, latency " + (w.streamLatency100ns() / 10_000L) + " ms, ring " + capacity
                        + " B, lead " + leadMs + " ms, mmcss=" + (w.mmcssOk() ? "yes" : "no"));
                opened.countDown(); // AFTER the open line: open() returning ⇒ the line is on the log (review F3)
                feedLoop(w);
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
                say("voice dragon: stream lost (" + failure + "); draining to the JDK line");
                drainToLine();
            }
        } catch (Throwable t) {
            if (failure == null) {
                failure = describe(t);
            }
        } finally {
            if (failure != null && !wasOpen) {
                openError = failure;
            }
            synchronized (lock) {
                feederDead = true;
                lock.notifyAll();
            }
            opened.countDown();
        }
    }

    /**
     * Wait → padding → take(bufferFrames − padding) → render; nothing is written on an empty ring.
     *
     * <p>Starvation law: at 10 ms frames the feed equals
     * the engine period, so the shared buffer is legitimately EMPTY at every wake (we fed one
     * period, the engine took one period) — the old {@code pad == 0} test counted 49 % of wakes
     * as "underruns" that were never silence. An underrun is a wake that finds the buffer empty
     * AFTER a pass the engine could NOT fill: the frames queued after the previous render
     * ({@code lastQueued} = pad + got) were short of one period. {@code drained} keeps the plain
     * empty-at-wake count so the lockstep depth stays readable.
     */
    private void feedLoop(RenderStream w) throws Throwable {
        boolean primed = false;
        int starved = 0;
        int period = w.periodFrames();
        int lastQueued = 0;
        while (!stop) {
            int r = w.waitWake();
            if (stop) {
                break;
            }
            if (r == WasapiRenderStream.WAIT_TIMEOUT) {
                timeouts++;
                // Review F2: an event that stops firing on a still-"valid" stream (device
                // switch/unplug without a final signal) would otherwise wedge the mixer's write()
                // forever — three silent waits (3 × WAIT_MS) = stream lost ⇒ the JDK-line drain.
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
            int pad = w.padding();
            if (primed && pad == 0) {
                drained++;
                if (lastQueued < period) {
                    underruns++;
                }
            }
            int free = w.bufferFrames() - pad;
            if (free < 0) {
                free = 0;
            }
            int got = take(scratch, free);
            if (got > 0) {
                w.render(scratch, got, channels);
                framesFed += got;
                primed = true;
            }
            lastQueued = pad + got;
        }
    }

    /** Runtime fallback: the ring keeps pacing the mixer; the JDK line's blocking write paces us. */
    private void drainToLine() {
        PcmSink d = lines.get();
        try {
            d.open(sampleRate, channels, askedFrames);
        } catch (IllegalStateException e) {
            say("voice dragon: " + e.getMessage());
            return;
        }
        byte[] chunk = new byte[capacity];
        try {
            while (!stop) {
                synchronized (lock) {
                    while (!stop && count < bytesPerFrame) {
                        try {
                            lock.wait(50L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                int frames = take(chunk, capacity / bytesPerFrame);
                if (frames > 0) {
                    d.write(chunk, 0, frames * bytesPerFrame);
                }
            }
        } finally {
            d.close();
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
