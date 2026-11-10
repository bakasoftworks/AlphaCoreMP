package cc.starsector.client;

import java.util.Arrays;

/**
 * What a retired fuse bomb still owes its burst: the source ship, the burst radius, whether the
 * local engine already ended it on a contact and whether the wire minted it with the own ship as its
 * source, kept for a short while past the twin's retire.
 *
 *
 * <p><b>Why.</b> A fuse bomb's server {@code MISSILE_END} retires its local twin, but the burst it
 * spawned lives about 0.1 s (about six collision passes), and the server names each victim with a
 * {@code BLAST_HIT} that can arrive up to ~6 ticks AFTER that end. {@link BlastHitLane} presents a hit
 * with the bomb's own source ship and burst radius, and skips a bomb the local engine already
 * presented; with the twin gone, this latch is where those three facts live.
 *
 * <p><b>Bounds.</b> A fixed ring of {@link #CAPACITY} slots: a put past the capacity overwrites the
 * oldest entry, nothing is allocated after construction and nothing grows with the wire. A lookup
 * ignores an entry whose end tick is more than {@link #MAX_AGE_TICKS} server ticks away from the
 * asking hit's tick (either side: a hit drained after its bomb's end may carry a slightly earlier
 * tick). Combat thread only (the end consumer's retire and the edge-event drain both run inside the
 * plugin's advance); {@link #clear} at the round reset.
 */
final class EndedBombLatch {

    /** Ring capacity: the most recent ended fuse bombs kept. */
    static final int CAPACITY = 64;

    /** An entry further than this many server ticks from the asking hit is ignored. */
    static final long MAX_AGE_TICKS = 30L;

    private final long[] wireIds = new long[CAPACITY];
    private final Object[] sources = new Object[CAPACITY];
    private final float[] radii = new float[CAPACITY];
    private final boolean[] localContact = new boolean[CAPACITY];
    private final boolean[] ownSourceByWire = new boolean[CAPACITY];
    private final long[] endTicks = new long[CAPACITY];
    /** The slot the next put writes (the oldest entry once the ring is full). */
    private int next;
    private int size;

    /** Remembers one ended fuse bomb ({@code wireId} = its missile network id, positive).
     *  {@code wireOwnSource} = the twin's {@link BlastHitLane#wireOwnSource} verdict, taken while the twin
     *  and the own ship of that moment were both still at hand: a latched hit is refused on it exactly as a
     *  live one is. */
    void put(long wireId, Object source, float burstRadius, boolean endedOnLocalContact, boolean wireOwnSource,
            long endTick) {
        wireIds[next] = wireId;
        sources[next] = source;
        radii[next] = burstRadius;
        localContact[next] = endedOnLocalContact;
        ownSourceByWire[next] = wireOwnSource;
        endTicks[next] = endTick;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    /** The newest slot holding {@code wireId} within {@link #MAX_AGE_TICKS} of {@code nowTick}, else
     *  {@code -1} (never latched, overwritten, or too far in ticks). */
    int find(long wireId, long nowTick) {
        for (int i = 0; i < size; i++) {
            int slot = (next - 1 - i + CAPACITY) % CAPACITY;
            if (wireIds[slot] == wireId) {
                long age = nowTick - endTicks[slot];
                return age > MAX_AGE_TICKS || age < -MAX_AGE_TICKS ? -1 : slot;
            }
        }
        return -1;
    }

    Object source(int slot) {
        return sources[slot];
    }

    float burstRadius(int slot) {
        return radii[slot];
    }

    boolean endedOnLocalContact(int slot) {
        return localContact[slot];
    }

    boolean wireOwnSource(int slot) {
        return ownSourceByWire[slot];
    }

    int size() {
        return size;
    }

    /** Round reset: the ended round's bombs (and their source-ship refs) are dropped. */
    void clear() {
        Arrays.fill(sources, null);
        next = 0;
        size = 0;
    }
}
