package cc.starsector.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The hulk-pose ADOPT RING: a fixed-capacity ring of
 * every BIG hulk-pose adoption (posU &gt; 5 || velUs &gt; 5), flushed as grep-stable rows at an
 * owner F9 marker and at teardown.
 *
 * <p>WHY: {@code FractureFamilyCoordinator.logPoseAdopt} emits at most 150 capped
 * {@code predict hulk-pose adopt:} lines per run; on one flight the cap ran out at tick ~11219
 * and the owner's F9 #3 at tick 47154 had NO adopt row to grade against. The capped live lines
 * stay exactly as they were; this ring keeps the most recent adopts so a late marker still sees
 * them (row format mirrors {@code predict stutter-ring marker #N} of
 * {@link ClientWorstFramesLedger}).
 *
 * <p>Dependency-free (java.util/java.lang only) so the smoke drives it headlessly. Storage is
 * parallel primitive arrays + one write cursor + a writes counter: no per-record objects.
 * Single-threaded by contract (record, F9 dump and teardown dump all run on the render thread).
 */
final class HulkAdoptRing {

    static final int CAPACITY = 64;
    static final int DUMP_ROWS_MAX = 32;
    /** 15 s at 60 Hz: an F9 lags the felt event by 1-3 s and a "sustained" hulk contact runs
     * ~10 s (ticks 46449-46977 for one hulk). */
    static final long WINDOW_TICKS = 900L;

    private final int capacity;
    private final long[] tick;
    private final long[] root;
    private final long[] death;
    private final int[] rank;
    private final float[] dpos;
    private final float[] dvel;
    private final float[] dfacing;
    private final float[] minDist;
    private final int[] near;
    private final long[] dtick;
    private final boolean[] glide;
    private int cursor;   // next slot to write
    private long writes;  // total records ever

    HulkAdoptRing() {
        this(CAPACITY);
    }

    HulkAdoptRing(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
        tick = new long[capacity];
        root = new long[capacity];
        death = new long[capacity];
        rank = new int[capacity];
        dpos = new float[capacity];
        dvel = new float[capacity];
        dfacing = new float[capacity];
        minDist = new float[capacity];
        near = new int[capacity];
        dtick = new long[capacity];
        glide = new boolean[capacity];
    }

    /** Record one big adopt; overwrites the oldest when full. Never throws.
     * {@code minDist} NaN = n/a; {@code near} -1 = n/a; {@code dtick} Long.MIN_VALUE = n/a. */
    void record(long t, long rootId, long deathTick, int sizeRank, float dPos, float dVel,
            float dFacing, float minD, int nearBit, long dTick, boolean glided) {
        int i = cursor;
        tick[i] = t;
        root[i] = rootId;
        death[i] = deathTick;
        rank[i] = sizeRank;
        dpos[i] = dPos;
        dvel[i] = dVel;
        dfacing[i] = dFacing;
        minDist[i] = minD;
        near[i] = nearBit;
        dtick[i] = dTick;
        glide[i] = glided;
        cursor = (i + 1) % capacity;
        writes++;
    }

    long writes() {
        return writes;
    }

    /** Header row first, then the in-window entries (tick &gt;= simTick - WINDOW_TICKS; ALL when
     * simTick == Long.MIN_VALUE), newest {@link #DUMP_ROWS_MAX} kept, emitted oldest first.
     * Never throws (a formatting failure yields the header plus an error row). */
    List<String> dumpRows(String where, long markerNo, long simTick) {
        List<String> out = new ArrayList<>();
        String prefix = "predict hulk-pose adopt-ring [" + where + "] marker #" + markerNo;
        try {
            boolean all = simTick == Long.MIN_VALUE;
            long floor = all ? Long.MIN_VALUE : simTick - WINDOW_TICKS;
            int stored = (int) Math.min(writes, (long) capacity);
            int oldest = stored < capacity ? 0 : cursor;
            int inWindow = 0;
            for (int k = 0; k < stored; k++) {
                int i = (oldest + k) % capacity;
                if (all || tick[i] >= floor) {
                    inWindow++;
                }
            }
            int shown = Math.min(inWindow, DUMP_ROWS_MAX);
            out.add(prefix + " tick=" + (all ? -1L : simTick) + " window_ticks=" + WINDOW_TICKS
                    + " in_window=" + inWindow + " shown=" + shown + " writes=" + writes
                    + " cap=" + capacity);
            int skip = inWindow - shown; // drop the oldest in-window entries
            int row = 0;
            for (int k = 0; k < stored; k++) {
                int i = (oldest + k) % capacity;
                if (!all && tick[i] < floor) {
                    continue;
                }
                if (skip > 0) {
                    skip--;
                    continue;
                }
                row++;
                out.add(prefix + " row=" + row + " tick=" + tick[i]
                        + " age_ticks=" + (all ? -1L : simTick - tick[i])
                        + " root=" + root[i] + " death=" + death[i] + " rank=" + rank[i]
                        + " dpos=" + String.format(Locale.ROOT, "%.1f", dpos[i])
                        + " dvel=" + String.format(Locale.ROOT, "%.1f", dvel[i])
                        + " dfacing=" + String.format(Locale.ROOT, "%.1f", dfacing[i])
                        + " minDist=" + (Float.isNaN(minDist[i]) ? "n/a"
                                : String.format(Locale.ROOT, "%.0f", minDist[i]))
                        + " near=" + (near[i] < 0 ? "n/a" : Integer.toString(near[i]))
                        + " dtick=" + (dtick[i] == Long.MIN_VALUE ? "n/a" : Long.toString(dtick[i]))
                        + " glide=" + (glide[i] ? "1" : "0"));
            }
        } catch (Throwable t) {
            // never throws: the failure is visible as a row (the caller logs every row)
            out.add(prefix + " dump FAILED " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }
}
