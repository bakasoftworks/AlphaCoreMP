package cc.starsector.server.runtime;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The bounded-queue chunk drain under every decoupled file lane
 * ({@link DecoupledStdoutLane}, {@link WireRecordSink} decoupled mode): the StallIoBench
 * async-load shape promoted to product. Submitters hand over COMPLETE byte chunks; one daemon
 * drain thread owns the delegate writer, so the submitting (serve) thread never blocks on the
 * disk (bench: HDD sync-load 312 ms worst vs 0.24 ms enqueue). The queue-full
 * path never drops and never reorders: the submitter takes the write lock, drains the queue in
 * arrival order, then writes its own chunk synchronously (fail-open drop-to-SYNC — the
 * logging law: a stalled disk may cost that frame, never a log row or wire record). A delegate
 * write failure latches {@link #error()} once and the lane goes no-op with the backlog cleared
 * (instrument discipline — a dead disk never kills the serve; owners re-latch their own error).
 */
final class BoundedChunkDrain {

    interface ChunkWriter {
        void write(byte[] chunk) throws IOException;

        void flush() throws IOException;
    }

    private final ArrayBlockingQueue<byte[]> queue;
    private final ChunkWriter writer;
    /** Serializes ALL delegate writes: the drain loop polls INSIDE it so a fallback's
     * drain-then-write cannot interleave with a chunk the drain thread already polled. */
    private final Object writeLock = new Object();
    private final Thread drain;
    private final AtomicLong fallbackDrains = new AtomicLong();
    private final AtomicLong maxDepth = new AtomicLong();
    private volatile boolean stop;
    private volatile String error = "";

    BoundedChunkDrain(String threadName, int capacity, ChunkWriter writer) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writer = writer;
        this.drain = new Thread(this::drainLoop, threadName);
        this.drain.setDaemon(true);
        this.drain.start();
    }

    /** Enqueue one complete chunk; on a full queue drain-then-write inline (order kept). */
    void submit(byte[] chunk) {
        if (!error.isEmpty()) {
            return;
        }
        if (queue.offer(chunk)) {
            maxDepth.accumulateAndGet(queue.size(), Math::max);
            return;
        }
        fallbackDrains.incrementAndGet();
        synchronized (writeLock) {
            try {
                for (byte[] queued; (queued = queue.poll()) != null; ) {
                    writer.write(queued);
                }
                writer.write(chunk);
                writer.flush();
            } catch (IOException e) {
                fail(e);
            }
        }
    }

    private void drainLoop() {
        while (true) {
            synchronized (writeLock) {
                try {
                    boolean wrote = false;
                    for (byte[] chunk; (chunk = queue.poll()) != null; ) {
                        writer.write(chunk);
                        wrote = true;
                    }
                    if (wrote) {
                        writer.flush();
                    }
                } catch (IOException e) {
                    fail(e);
                    return;
                }
            }
            if (stop && queue.isEmpty()) {
                return;
            }
            LockSupport.parkNanos(500_000L);
        }
    }

    /** Best-effort wait for an empty, flushed queue (shutdown-hook / teardown ordering). */
    boolean awaitDrained(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!queue.isEmpty() && error.isEmpty() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000L);
        }
        synchronized (writeLock) {
            try {
                if (error.isEmpty()) {
                    writer.flush();
                }
            } catch (IOException e) {
                fail(e);
            }
        }
        return queue.isEmpty();
    }

    /** Stop the drain thread, then drain any remainder inline (owner close path). */
    void shutdown(long timeoutMillis) {
        stop = true;
        try {
            drain.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (writeLock) {
            try {
                for (byte[] chunk; (chunk = queue.poll()) != null; ) {
                    if (error.isEmpty()) {
                        writer.write(chunk);
                    }
                }
                if (error.isEmpty()) {
                    writer.flush();
                }
            } catch (IOException e) {
                fail(e);
            }
        }
    }

    private void fail(IOException e) {
        if (error.isEmpty()) {
            error = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        }
        queue.clear();
        // A latched error also ends the drain thread: without this, a FALLBACK-path disk
        // failure (fail() called on the submitter thread) would leave the drain looping
        // forever on a permanently-empty queue — one leaked busy-parking thread per serve.
        stop = true;
    }

    String error() {
        return error;
    }

    long fallbackDrains() {
        return fallbackDrains.get();
    }

    long maxQueueDepth() {
        return maxDepth.get();
    }
}
