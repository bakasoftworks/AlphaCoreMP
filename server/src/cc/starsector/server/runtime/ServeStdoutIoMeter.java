package cc.starsector.server.runtime;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * serve-thread stdout I/O accounting for the work-frame-stall witness:
 * ONE JVM-wide wrapper installed over {@code System.out} that times the delegate's write+flush
 * (the real syscall against the redirected log file) and charges it to the serve thread only.
 * The witness reads the counter at each phase stamp, so a stall line names how much of each
 * bracket was blocking stdout I/O vs compute (the blocking-file-I/O reframe's
 * discriminator).
 *
 * <p>Install is idempotent (one wrapper per JVM, re-aimed at the caller's thread per serve —
 * contract suites calling serveNPeer repeatedly must not stack wrappers) and fail-soft (any
 * install failure returns null; the witness prints io=0 rather than the serve dying for its
 * instrument). Non-serve threads delegate untimed and otherwise unchanged. Delegation preserves
 * the original stream's flush-per-write syscall pattern exactly: the delegate PrintStream
 * autoflushes its own byte writes, and the extra wrapper-level flush lands on an already-empty
 * buffer (a no-op). The counter is single-writer (the accounted thread) by construction;
 * volatile covers cross-serve thread handover visibility.
 */
final class ServeStdoutIoMeter extends OutputStream {

    private static final Object INSTALL_LOCK = new Object();
    private static volatile ServeStdoutIoMeter installed;

    private final PrintStream delegate;
    private volatile Thread accounted;
    private volatile long ioNanos;

    private ServeStdoutIoMeter(PrintStream delegate) {
        this.delegate = delegate;
    }

    /** Install (once per JVM) + aim the counter at the calling thread; null on failure. */
    static ServeStdoutIoMeter installForCurrentThread() {
        try {
            ServeStdoutIoMeter meter = installed;
            if (meter == null) {
                synchronized (INSTALL_LOCK) {
                    meter = installed;
                    if (meter == null) {
                        meter = new ServeStdoutIoMeter(System.out);
                        System.setOut(new PrintStream(meter, true));
                        installed = meter;
                    }
                }
            }
            meter.accounted = Thread.currentThread();
            return meter;
        } catch (RuntimeException failSoft) {
            return null;
        }
    }

    /** Cumulative accounted-thread write+flush nanos; 0 for a null meter (install failed). */
    static long nanosOf(ServeStdoutIoMeter meter) {
        return meter == null ? 0L : meter.ioNanos;
    }

    @Override
    public void write(int b) {
        if (Thread.currentThread() == accounted) {
            long t0 = System.nanoTime();
            delegate.write(b);
            ioNanos += System.nanoTime() - t0;
        } else {
            delegate.write(b);
        }
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        if (Thread.currentThread() == accounted) {
            long t0 = System.nanoTime();
            delegate.write(buf, off, len);
            ioNanos += System.nanoTime() - t0;
        } else {
            delegate.write(buf, off, len);
        }
    }

    @Override
    public void flush() {
        if (Thread.currentThread() == accounted) {
            long t0 = System.nanoTime();
            delegate.flush();
            ioNanos += System.nanoTime() - t0;
        } else {
            delegate.flush();
        }
    }

    /** Never forwarded as close: the wrapper must not be able to close the process stdout. */
    @Override
    public void close() {
        flush();
    }
}
