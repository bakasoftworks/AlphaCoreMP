package cc.starsector.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A generic PER-ENTITY event ring: a fixed-capacity ring of
 * small primitive event rows (tick, kind, entity + a constructor-labelled column schema), flushed as
 * grep-stable rows at an owner F9 marker and at teardown.
 *
 * <p>WHY: the capped live lines of the foe-fluxflags lane (400 shared rows, drained by per-frame
 * vent-tail disagrees) and of the same-side aim lane (20 record rows per run) run out early in a
 * flight, so a late F9 cannot name WHICH foe / WHICH mount the event hit.
 * The model is
 * {@link HulkAdoptRing} (left untouched): same header shape, same 900-tick window, same
 * newest-N-emitted-oldest-first dump, same {@code n/a} idiom. One class, four instances (flux
 * disagree / flux vent_tail on {@link FoeStateLaneCoordinator}, aim record / aim release on
 * {@link SameSideAimLane}); the smoke {@code EntityEventRingSmoke} pins the shared loop once.
 *
 * <p>Schema: {@code long tick, entity, last, l0; int kind, i0, i1; float f0, f1, f2}. A column
 * whose constructor name is null is not rendered. n/a sentinels: {@code Integer.MIN_VALUE},
 * {@code Long.MIN_VALUE}, NaN. Row column order is fixed: {@code i0 i1 last l0 f0 f1 f2}
 * ({@code last} = the coalesced run's last tick, only rendered when {@code lastName} is set).
 * {@code i0BitNames} (non-null) renders {@code i0} as one {@code name=0|1} token per bit (bit k =
 * {@code 1 << k}) instead of a raw int.
 *
 * <p>Dependency-free (java.util/java.lang only) so the smoke drives it headlessly; storage is
 * parallel primitive arrays + one write cursor + a writes counter, no per-record objects.
 * Threading: record, F9 dump and teardown dump run on the render thread, EXCEPT the staleness
 * watchdog's teardown ({@code PredictReconcileCombatPlugin.teardownActiveBody}),
 * which runs on the writer thread and may race a render-thread record: with {@code % capacity}
 * indexing the accepted worst case is a torn row or an off-by-one {@code in_window}, never a throw
 * out of the dump (it catches everything).
 */
final class EntityEventRing {

    /** 15 s at 60 Hz — the {@link HulkAdoptRing#WINDOW_TICKS} window (an F9 lags its event 1-3 s). */
    static final long WINDOW_TICKS = 900L;
    /** {@link #recordOrExtend} looks back at most this many newest rows (bounded O(8)). */
    static final int COALESCE_SCAN = 8;
    /** A row extends while the new tick is at most this many ticks after its last tick. */
    static final long COALESCE_GAP_TICKS = 2L;

    private final String prefix;
    private final int capacity;
    private final int dumpRowsMax;
    private final String[] kindNames;
    private final String i0Name;
    private final String[] i0BitNames;
    private final String i1Name;
    private final String lastName;
    private final String l0Name;
    private final String f0Name;
    private final String f1Name;
    private final String f2Name;

    private final long[] tick;
    private final long[] last;
    private final long[] entity;
    private final long[] l0;
    private final int[] kind;
    private final int[] i0;
    private final int[] i1;
    private final float[] f0;
    private final float[] f1;
    private final float[] f2;
    private int cursor;   // next slot to write
    private long writes;  // total rows ever written (an extension is not a write)

    /**
     * One ring instance. {@code prefix} is the dump token
     * (distinct from every live-line prefix, spec F8); {@code capacity} &lt; 1 or
     * {@code dumpRowsMax} &lt; 1 THROWS (a caller bug).
     */
    EntityEventRing(String prefix, int capacity, int dumpRowsMax, String[] kindNames,
            String i0Name, String[] i0BitNames, String i1Name, String lastName, String l0Name,
            String f0Name, String f1Name, String f2Name) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        if (dumpRowsMax < 1) {
            throw new IllegalArgumentException("dumpRowsMax must be >= 1: " + dumpRowsMax);
        }
        this.prefix = prefix;
        this.capacity = capacity;
        this.dumpRowsMax = dumpRowsMax;
        this.kindNames = kindNames == null ? new String[0] : kindNames.clone();
        this.i0Name = i0Name;
        this.i0BitNames = i0BitNames == null ? null : i0BitNames.clone();
        this.i1Name = i1Name;
        this.lastName = lastName;
        this.l0Name = l0Name;
        this.f0Name = f0Name;
        this.f1Name = f1Name;
        this.f2Name = f2Name;
        tick = new long[capacity];
        last = new long[capacity];
        entity = new long[capacity];
        l0 = new long[capacity];
        kind = new int[capacity];
        i0 = new int[capacity];
        i1 = new int[capacity];
        f0 = new float[capacity];
        f1 = new float[capacity];
        f2 = new float[capacity];
    }

    /** Record one row; overwrites the oldest when full. Never throws. */
    void record(long t, int kindCode, long entityId, int a0, int a1, long b0, float x0, float x1,
            float x2) {
        int i = cursor;
        if (i < 0 || i >= capacity) {
            i = 0; // a torn cursor (the writer-thread teardown race) — never index out of bounds
        }
        tick[i] = t;
        last[i] = t;
        entity[i] = entityId;
        kind[i] = kindCode;
        i0[i] = a0;
        i1[i] = a1;
        l0[i] = b0;
        f0[i] = x0;
        f1[i] = x1;
        f2[i] = x2;
        cursor = (i + 1) % capacity;
        writes++;
    }

    /**
     * The coalescing write (spec F7: a 30-frame vent tail must
     * not spend 30 slots): scan back at most {@link #COALESCE_SCAN} newest rows for the same
     * {@code kindCode} + {@code entityId} + {@code state} whose last tick is within
     * {@link #COALESCE_GAP_TICKS}; found ⇒ {@code i1} (frames) += 1 and {@code last} = max(last, t);
     * else a new row with {@code i1 = 1}. Returns true when it extended. Never throws.
     */
    boolean recordOrExtend(long t, int kindCode, long entityId, int state) {
        int stored = (int) Math.min(writes, (long) capacity);
        int scan = Math.min(stored, COALESCE_SCAN);
        for (int k = 1; k <= scan; k++) {
            int i = ((cursor - k) % capacity + capacity) % capacity;
            if (entity[i] == entityId && kind[i] == kindCode && i0[i] == state
                    && t >= tick[i] && t <= last[i] + COALESCE_GAP_TICKS) {
                i1[i]++;
                if (t > last[i]) {
                    last[i] = t;
                }
                return true;
            }
        }
        record(t, kindCode, entityId, state, 1, Long.MIN_VALUE, Float.NaN, Float.NaN, Float.NaN);
        return false;
    }

    long writes() {
        return writes;
    }

    /**
     * Header row first (the {@link HulkAdoptRing#dumpRows} header shape),
     * then the in-window rows (a row's LAST tick &gt;= simTick - {@link #WINDOW_TICKS}; ALL when
     * simTick == Long.MIN_VALUE), newest {@code dumpRowsMax} kept, emitted oldest first. Never throws
     * (a formatting failure yields the header plus an error row).
     */
    List<String> dumpRows(String where, long markerNo, long simTick) {
        List<String> out = new ArrayList<>();
        String head = prefix + " [" + where + "] marker #" + markerNo;
        try {
            boolean all = simTick == Long.MIN_VALUE;
            long floor = all ? Long.MIN_VALUE : simTick - WINDOW_TICKS;
            int stored = (int) Math.min(writes, (long) capacity);
            int oldest = stored < capacity ? 0 : cursor;
            int inWindow = 0;
            for (int k = 0; k < stored; k++) {
                int i = (oldest + k) % capacity;
                if (all || last[i] >= floor) {
                    inWindow++;
                }
            }
            int shown = Math.min(inWindow, dumpRowsMax);
            out.add(head + " tick=" + (all ? -1L : simTick) + " window_ticks=" + WINDOW_TICKS
                    + " in_window=" + inWindow + " shown=" + shown + " writes=" + writes
                    + " cap=" + capacity);
            int skip = inWindow - shown; // drop the oldest in-window rows
            int row = 0;
            for (int k = 0; k < stored; k++) {
                int i = (oldest + k) % capacity;
                if (!all && last[i] < floor) {
                    continue;
                }
                if (skip > 0) {
                    skip--;
                    continue;
                }
                row++;
                out.add(renderRow(head, row, i, all, simTick));
            }
        } catch (Throwable t) {
            // never throws: the failure is visible as a row (the caller logs every row)
            out.add(head + " dump FAILED " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    private String renderRow(String head, int row, int i, boolean all, long simTick) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(head).append(" row=").append(row).append(" tick=").append(tick[i])
                .append(" age_ticks=").append(all ? -1L : simTick - tick[i])
                .append(" kind=").append(kindName(kind[i]))
                .append(" entity=").append(entity[i]);
        if (i0BitNames != null) {
            for (int b = 0; b < i0BitNames.length; b++) {
                sb.append(' ').append(i0BitNames[b]).append('=')
                        .append(i0[i] == Integer.MIN_VALUE ? "n/a" : ((i0[i] >> b) & 1) != 0 ? "1" : "0");
            }
        } else {
            appendInt(sb, i0Name, i0[i]);
        }
        appendInt(sb, i1Name, i1[i]);
        appendLong(sb, lastName, last[i]);
        appendLong(sb, l0Name, l0[i]);
        appendFloat(sb, f0Name, f0[i]);
        appendFloat(sb, f1Name, f1[i]);
        appendFloat(sb, f2Name, f2[i]);
        return sb.toString();
    }

    private String kindName(int k) {
        return k >= 0 && k < kindNames.length ? kindNames[k] : Integer.toString(k);
    }

    private static void appendInt(StringBuilder sb, String name, int v) {
        if (name != null) {
            sb.append(' ').append(name).append('=')
                    .append(v == Integer.MIN_VALUE ? "n/a" : Integer.toString(v));
        }
    }

    private static void appendLong(StringBuilder sb, String name, long v) {
        if (name != null) {
            sb.append(' ').append(name).append('=')
                    .append(v == Long.MIN_VALUE ? "n/a" : Long.toString(v));
        }
    }

    private static void appendFloat(StringBuilder sb, String name, float v) {
        if (name != null) {
            sb.append(' ').append(name).append('=')
                    .append(Float.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.1f", v));
        }
    }
}
