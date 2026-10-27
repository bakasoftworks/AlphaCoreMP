package cc.starsector.client;

/**
 * Per-key row budget for one capped log lane: ≤ {@code perKey} (≥ 1) rows per key, ≤ {@code maxKeys}
 * distinct keys (a later key gets no rows), ≤ {@code globalMax} rows in all.
 * WHY: the foe-system rows shared ONE 60-row budget with no entity
 * id, so a busy hull starved every later window. Pure, primitive arrays + linear probe (no boxing or
 * per-call allocation), caller-thread only; smoke PerKeyLogBudgetSmoke.
 */
final class PerKeyLogBudget {

    private final int perKey, maxKeys, globalMax;
    private final long[] keys;
    private final boolean[] used;
    private final int[] counts;
    private int distinct, total;
    private long capped;

    PerKeyLogBudget(int perKey, int maxKeys, int globalMax) {
        this.perKey = perKey;
        this.maxKeys = maxKeys;
        this.globalMax = globalMax;
        int size = Integer.highestOneBit(Math.max(2, maxKeys * 2 - 1)) << 1; // pow2 >= 2x keys: a free slot ends every probe
        this.keys = new long[size];
        this.used = new boolean[size];
        this.counts = new int[size];
    }

    /** One row for {@code key}: true = log it (counted); false = refused by a cap (metered in {@link #capped}). */
    boolean admit(long key) {
        return take(key, true);
    }

    boolean admitExempt(long key) { return take(key, false); } // no per-key cap; still a key slot + the ceiling

    private boolean take(long key, boolean perKeyCapped) {
        int slot = total < globalMax ? slotOf(key) : -1; // a ceiling refusal never claims a slot
        if (slot < 0 || (perKeyCapped && counts[slot] >= perKey)) {
            capped++;
            return false;
        }
        counts[slot] += perKeyCapped ? 1 : 0;
        total++;
        return true;
    }

    private int slotOf(long key) {
        int mask = keys.length - 1;
        int h = Long.hashCode(key) * 0x9E3779B9;
        int i = (h ^ (h >>> 16)) & mask;
        while (used[i]) {
            if (keys[i] == key) {
                return i;
            }
            i = (i + 1) & mask;
        }
        if (distinct >= maxKeys) {
            return -1; // the bounded key map is full
        }
        used[i] = true;
        keys[i] = key;
        distinct++;
        return i;
    }

    int keys() { return distinct; } // distinct keys given a slot (each logged >= 1 row)

    long capped() { return capped; } // rows refused by any of the three caps

    /** Round wrap: the key table and the per-key counts are per-round like every FSLC
     *  map cleared at the reset (else the 64 slots fill with dead-round ids and a reused id stays stuck at
     *  its cap). The CEILING ({@code total}) and {@link #capped} stay per-combat: the ceiling shadows the
     *  lane's own per-combat {@code PredictLane} cap in lockstep, so a per-round re-arm would admit rows the
     *  lane then drops uncounted. Pin = PerKeyLogBudgetSmoke B15. */
    void resetKeys() {
        java.util.Arrays.fill(used, false);
        java.util.Arrays.fill(counts, 0);
        distinct = 0;
    }
}
