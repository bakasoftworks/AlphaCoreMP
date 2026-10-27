package cc.starsector.server.runtime;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The DECOUPLED stdout lane: one JVM-wide wrapper over {@code System.out}
 * handing complete flush-delimited chunks to a {@link BoundedChunkDrain} writer thread, so the
 * serve thread's println never blocks on the redirected log file's disk (the
 * mechanism: watch artifacts live on a 5400-rpm HDD; a sync println measured up to 312 ms
 * under load). Install is idempotent like {@link ServeStdoutIoMeter} (one wrapper per JVM —
 * contract suites must not stack) and ORDER-SENSITIVE: the serve installs this lane FIRST and
 * the meter second, so the meter times the serve thread's enqueue+fallback cost and io_* keeps
 * meaning "serve-thread stdout blocking time" in both modes. A later serve's knob flips
 * {@link #enabled} rather than re-installing; disabled = byte-for-byte synchronous passthrough
 * (the pre-lane behavior). Chunk boundaries are the top PrintStream's autoflush per println —
 * its monitor serializes write+flush pairs, so a chunk is a whole print and cross-thread line
 * atomicity is unchanged; a pathological flushless writer is bounded by the 64 KB valve.
 * Never-drop / never-reorder law = {@link BoundedChunkDrain}. A JVM shutdown hook drains the
 * tail; a hard kill can cost at most the queued rows (the ccwr-style truncation tolerance).
 */
final class DecoupledStdoutLane extends OutputStream {

    private static final Object INSTALL_LOCK = new Object();
    private static volatile DecoupledStdoutLane installed;
    private static final int PENDING_FLUSH_VALVE_BYTES = 64 * 1024;

    private final PrintStream delegate;
    private final BoundedChunkDrain drain;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(512);
    private final AtomicLong chunks = new AtomicLong();
    private volatile boolean enabled;

    private DecoupledStdoutLane(PrintStream delegate) {
        this.delegate = delegate;
        this.drain = new BoundedChunkDrain("cc-stdout-drain", 8192,
                new BoundedChunkDrain.ChunkWriter() {
                    @Override
                    public void write(byte[] chunk) {
                        delegate.write(chunk, 0, chunk.length);
                    }

                    @Override
                    public void flush() {
                        delegate.flush();
                    }
                });
    }

    /** Install once per JVM + set this serve's mode; fail-soft null (sync behavior stands). */
    static DecoupledStdoutLane installForServe(boolean enabled) {
        try {
            DecoupledStdoutLane lane = installed;
            if (lane == null) {
                synchronized (INSTALL_LOCK) {
                    lane = installed;
                    if (lane == null) {
                        lane = new DecoupledStdoutLane(System.out);
                        System.setOut(new PrintStream(lane, true));
                        DecoupledStdoutLane hooked = lane;
                        Runtime.getRuntime().addShutdownHook(new Thread(
                                () -> hooked.drainForExit(), "cc-stdout-drain-hook"));
                        installed = lane;
                    }
                }
            }
            synchronized (lane) {
                lane.flushPendingLocked(); // no chunk spans a mode flip between serves
                lane.enabled = enabled;
            }
            return lane;
        } catch (RuntimeException failSoft) {
            return null;
        }
    }

    @Override
    public synchronized void write(int b) {
        if (enabled) {
            pending.write(b);
            valve();
        } else {
            delegate.write(b);
        }
    }

    @Override
    public synchronized void write(byte[] buf, int off, int len) {
        if (enabled) {
            pending.write(buf, off, len);
            valve();
        } else {
            delegate.write(buf, off, len);
        }
    }

    @Override
    public synchronized void flush() {
        if (!enabled) {
            delegate.flush();
            return;
        }
        flushPendingLocked();
    }

    /** Never forwarded as close: the wrapper must not close process stdout (meter precedent). */
    @Override
    public synchronized void close() {
        flush();
    }

    private void valve() {
        if (pending.size() >= PENDING_FLUSH_VALVE_BYTES) {
            flushPendingLocked();
        }
    }

    private void flushPendingLocked() {
        if (pending.size() > 0) {
            drain.submit(pending.toByteArray());
            pending.reset();
            chunks.incrementAndGet();
        }
    }

    private void drainForExit() {
        synchronized (this) {
            flushPendingLocked();
        }
        drain.awaitDrained(2_000L);
    }

    long chunks() {
        return chunks.get();
    }

    long fallbackDrains() {
        return drain.fallbackDrains();
    }

    long maxQueueDepth() {
        return drain.maxQueueDepth();
    }
}
