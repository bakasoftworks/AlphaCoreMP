package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-victim EMP-arc damage histogram behind
 * {@link ArcHitServeProbe} — the attribution the 20-row {@code arc_hit} log cap cannot carry
 * (851 arcs emitted, 20 rows, none on a fighter). Two ledgers, both fed by the
 * drain's ALREADY-RESOLVED wire ids + the wire event's clamped split (no engine read here):
 * <ul>
 * <li>BUCKET ledger keyed (victim wire id, flavor): ONE {@code npeer-serve arc_hit_hist} row per
 * {@link #width}-tick bucket (index = {@code floorDiv(simTick, width)} — the same law as
 * {@code audit-dmg-delta.ps1 -WindowTicks}, default 300), flushed when the drain first sees a
 * later bucket ({@link #rollTo}) or at round end ({@link #flush}); an empty bucket prints
 * nothing.</li>
 * <li>FLIGHT ledger keyed by victim (flavors summed) for the round counters line
 * ({@link #summary}: top-{@link #SUMMARY_TOP} by hull, the rest counted by
 * {@link #summaryMore}).</li>
 * </ul>
 * Row grammar (ROOT locale, one decimal, no spaces inside a value):
 * {@code npeer-serve arc_hit_hist bucket=<t0> w=<width> cells=<k>
 * by_tgt=<tgt>:<flavor>:<n>/<hull>/<armor>/<shield>[,<tgt>:<flavor>:...]} — {@code n} arcs, the
 * summed applied split (a shield hit carries shield only, else armor + hull; NaN/negative = 0,
 * the wire's own clamp). Consumer: the damage-delta audit script (SCRIPT-DAMAGE exact tier:
 * arc-dealt hull per (ship, bucket)).
 */
public final class ArcHitHistogram {

    /** Bucket width in sim ticks = the auditor's {@code -WindowTicks} default (5 s at 60 tps). */
    public static final int DEFAULT_WIDTH = 300;
    /** Victims named on the counters line (hull desc, id asc on ties); the rest fold into more=. */
    public static final int SUMMARY_TOP = 24;
    /**
     * P23 leg 1: the flight ledger's cap on distinct victims KEPT. A 40-min
     * flight of respawning fighters mints a fresh wire id per spawn, so the ledger is bounded
     * here rather than by the match: past the cap a NEW victim evicts the kept victim with the
     * least arc-dealt hull (ties: fewer arcs, then the larger = newer wire id — the transient
     * fighter goes first). Evicted victims stay counted ({@link #victimCount},
     * {@link #summaryMore}); a victim hit again after eviction re-enters from zero (its summary
     * value is then a lower bound). The BUCKET ledger is untouched (cleared every
     * {@link #width} ticks, bounded by the arcs of one bucket). Row grammar unchanged.
     */
    public static final int FLIGHT_MAX = 256;

    private static final class Cell {
        int n;
        double hull;
        double armor;
        double shield;
    }

    private record Key(long tgt, int flavor) {
    }

    private final int width;
    private final int flightMax;
    private long bucketIndex = Long.MIN_VALUE;
    private final Map<Key, Cell> bucket = new LinkedHashMap<>();
    private final Map<Long, Cell> flight = new HashMap<>();
    private long rows;
    private int evicted;

    public ArcHitHistogram(int width) {
        this(width, FLIGHT_MAX);
    }

    /** {@code flightMax} = distinct victims kept in the flight ledger ({@link #FLIGHT_MAX} in production). */
    public ArcHitHistogram(int width, int flightMax) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        if (flightMax <= 0) {
            throw new IllegalArgumentException("flightMax must be positive: " + flightMax);
        }
        this.width = width;
        this.flightMax = flightMax;
    }

    /**
     * Move to {@code simTick}'s bucket. Returns the row of the bucket that just CLOSED (the
     * caller prints it) or {@code null} when the bucket is unchanged or was empty.
     */
    public String rollTo(long simTick) {
        long idx = Math.floorDiv(simTick, width);
        if (idx == bucketIndex) {
            return null;
        }
        String closed = flush();
        bucketIndex = idx;
        return closed;
    }

    /** One arc's applied split into the CURRENT bucket + the flight ledger ({@link #rollTo} first). */
    public void add(long tgt, int flavor, float armor, float hull, float shield) {
        accumulate(bucket.computeIfAbsent(new Key(tgt, flavor), k -> new Cell()), armor, hull, shield);
        Cell f = flight.get(tgt);
        if (f == null) {
            if (flight.size() >= flightMax) {
                evictOne();
            }
            f = new Cell();
            flight.put(tgt, f);
        }
        accumulate(f, armor, hull, shield);
    }

    /** Drop the kept victim with the least hull (ties: fewer arcs, then the larger wire id). */
    private void evictOne() {
        long victim = 0L;
        Cell worst = null;
        for (Map.Entry<Long, Cell> e : flight.entrySet()) {
            Cell c = e.getValue();
            if (worst == null
                    || c.hull < worst.hull
                    || (c.hull == worst.hull && (c.n < worst.n || (c.n == worst.n && e.getKey() > victim)))) {
                victim = e.getKey();
                worst = c;
            }
        }
        flight.remove(victim);
        evicted++;
    }

    private static void accumulate(Cell c, float armor, float hull, float shield) {
        c.n++;
        c.armor += finite(armor);
        c.hull += finite(hull);
        c.shield += finite(shield);
    }

    private static double finite(float v) {
        return Float.isFinite(v) && v > 0f ? v : 0d;
    }

    /**
     * Close the current bucket and return its row, or {@code null} when it holds no arc. The
     * bucket is cleared either way (a second call returns {@code null}); the flight ledger stays.
     */
    public String flush() {
        if (bucket.isEmpty() || bucketIndex == Long.MIN_VALUE) {
            bucket.clear();
            return null;
        }
        StringBuilder sb = new StringBuilder(64 + bucket.size() * 28);
        sb.append("npeer-serve arc_hit_hist bucket=").append(bucketIndex * width)
                .append(" w=").append(width)
                .append(" cells=").append(bucket.size())
                .append(" by_tgt=");
        boolean first = true;
        for (Map.Entry<Key, Cell> e : bucket.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Cell c = e.getValue();
            sb.append(e.getKey().tgt()).append(':').append(e.getKey().flavor()).append(':').append(c.n)
                    .append('/').append(fmt(c.hull))
                    .append('/').append(fmt(c.armor))
                    .append('/').append(fmt(c.shield));
        }
        bucket.clear();
        rows++;
        return sb.toString();
    }

    /** {@code arc_hit_hist} rows returned so far (a printed-row meter for the counters line). */
    public long rowCount() {
        return rows;
    }

    /** Distinct victims SEEN by the flight ledger (kept + evicted). */
    public int victimCount() {
        return flight.size() + evicted;
    }

    /** Victims dropped from the flight ledger by the {@link #FLIGHT_MAX} cap (0 below it). */
    public int evictedCount() {
        return evicted;
    }

    /** Counters-line value: {@code <tgt>:<n>/<hull>} × top-{@link #SUMMARY_TOP} by hull desc, or "-". */
    public String summary() {
        if (flight.isEmpty()) {
            return "-";
        }
        List<Map.Entry<Long, Cell>> entries = new ArrayList<>(flight.entrySet());
        entries.sort((a, b) -> {
            int byHull = Double.compare(b.getValue().hull, a.getValue().hull);
            return byHull != 0 ? byHull : Long.compare(a.getKey(), b.getKey());
        });
        StringBuilder sb = new StringBuilder(entries.size() * 16);
        int shown = Math.min(SUMMARY_TOP, entries.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Map.Entry<Long, Cell> e = entries.get(i);
            sb.append(e.getKey()).append(':').append(e.getValue().n).append('/').append(fmt(e.getValue().hull));
        }
        return sb.toString();
    }

    /** Victims NOT named by {@link #summary}: the fold past top-N plus the evicted (0 when every victim fits). */
    public int summaryMore() {
        return Math.max(0, flight.size() - SUMMARY_TOP) + evicted;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
