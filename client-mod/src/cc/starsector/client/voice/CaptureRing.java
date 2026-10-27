package cc.starsector.client.voice;

/**
 * The W12-DRAGON capture ring: a bounded
 * mono-int16 byte ring with the latency-first NEWEST-WINS law — {@link #push} drops the OLDEST
 * bytes on overflow (counted in {@link #droppedBytes}; a single buffer wider than the ring keeps
 * its newest {@code capacity} bytes), {@link #read} blocks until at least one sample (2 bytes) is
 * queued or the ring is {@link #release released}, then pops up to {@code len} bytes (rounded
 * down to whole samples). Once released, a read that finds fewer than 2 bytes returns 0 without
 * blocking — that is the "source went DEAD" contract of {@link PcmSource#read}.
 *
 * <p>WHY a class of its own: this is the pure half of {@code voice/ffm/FfmVoiceCapture} (the
 * reader thread pushes engine packets, the capture thread pops frames). It is 17-side and names no
 * {@code java.lang.foreign} type, so the ring/overflow/wrap arithmetic rides the Temurin-17
 * {@code VoiceClientLawSelftest} on every build instead of the 25-JVM hardware smoke, whose pacing
 * rows proved load-flaky as a build gate. Nothing is allocated per push
 * or per read; the ring object is its own monitor.
 */
public final class CaptureRing {
    private byte[] ring;
    private int capacity;
    private int head;
    private int count;
    private long droppedBytes;
    private boolean released;

    /**
     * (Re)allocates {@code max(2, samples)} mono-int16 samples of capacity, empties the ring and
     * clears the released flag ({@link #droppedBytes} keeps counting across allocations).
     */
    public synchronized void allocate(int samples) {
        capacity = Math.max(2, samples) * 2;
        ring = new byte[capacity];
        head = 0;
        count = 0;
        released = false;
    }

    /** Capacity in bytes (0 before {@link #allocate}). */
    public synchronized int capacity() {
        return capacity;
    }

    /** Bytes dropped by the newest-wins law so far. */
    public synchronized long droppedBytes() {
        return droppedBytes;
    }

    /** Ends blocking: wakes every waiting {@link #read}; reads keep draining what is queued. */
    public synchronized void release() {
        released = true;
        notifyAll();
    }

    /** Producer side: append {@code frames} mono samples of {@code src}, dropping the OLDEST on overflow. */
    public synchronized void push(short[] src, int frames) {
        int bytes = frames * 2;
        if (ring == null || bytes <= 0) {
            return;
        }
        int srcOff = 0;
        if (bytes > capacity) {
            srcOff = (bytes - capacity) / 2;
            droppedBytes += bytes - capacity;
            bytes = capacity;
        }
        int over = count + bytes - capacity;
        if (over > 0) {
            head = (head + over) % capacity;
            count -= over;
            droppedBytes += over;
        }
        int tail = (head + count) % capacity;
        for (int i = 0; i < bytes / 2; i++) {
            short v = src[srcOff + i];
            int p = (tail + 2 * i) % capacity;
            ring[p] = (byte) v;
            ring[(p + 1) % capacity] = (byte) (v >> 8);
        }
        count += bytes;
        notifyAll();
    }

    /**
     * Consumer side: pop up to {@code len} bytes (whole samples) into {@code buf}; blocks while
     * fewer than 2 bytes are queued and the ring is not released. Returns 0 for {@code len <= 0},
     * on interrupt (the flag is re-set), and once released with nothing left to drain.
     */
    public synchronized int read(byte[] buf, int off, int len) {
        if (len <= 0) {
            return 0;
        }
        while (count < 2 && !released) {
            try {
                wait(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        if (count < 2 || ring == null) {
            return 0; // released and drained: never blocks again
        }
        int n = Math.min(len, count) & ~1;
        int first = Math.min(n, capacity - head);
        System.arraycopy(ring, head, buf, off, first);
        if (first < n) {
            System.arraycopy(ring, 0, buf, off + first, n - first);
        }
        head = (head + n) % capacity;
        count -= n;
        return n;
    }
}
