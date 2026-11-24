package cc.starsector.server.bridge;

import java.util.IdentityHashMap;

/**
 * An identity-keyed map whose entries DIE when
 * nothing touched them between two {@link #sweep()} calls.
 *
 * <p><b>Why it exists.</b> {@link EngineCombatBinding}'s per-instance signature maps are keyed by the engine
 * Ship OBJECT (deliberately: a respawn is a new object, so it re-prints). A bastion serve is ONE continuous
 * round, the engine is never re-made, and a plain {@code IdentityHashMap} cleared only at 2,048 entries kept
 * every removed body reachable: up to 2,047 dead Ships, about 0.17 MB each on a real serve (the recon =).
 * The owner of a key touches it every tick while the body is observed, so "untouched
 * for a sweep period" is the removal signal, with no engine callback and no liveness read.
 *
 * <p><b>How.</b> Two generations. A hit in the old generation moves the entry to the young one;
 * {@link #sweep()} drops what stayed old. A key touched every tick costs the same single lookup as the plain
 * map did; the move is one put per live key per sweep period. A key touched again AFTER its entry died reads
 * as never seen — the answer a respawned body gets. The two tables are swapped, never re-allocated.
 *
 * <p>Sim-thread only, no synchronization. A null value is a legal value (it still reads as present).
 */
public final class SweptIdentityMap<V> {

    private IdentityHashMap<Object, V> young = new IdentityHashMap<>();
    private IdentityHashMap<Object, V> old = new IdentityHashMap<>();

    /** True when the key is held. A hit keeps the entry alive past the next {@link #sweep()}. */
    public boolean containsKey(Object key) {
        if (young.containsKey(key)) {
            return true;
        }
        if (!old.containsKey(key)) {
            return false;
        }
        young.put(key, old.remove(key));
        return true;
    }

    /** The held value, or null when the key is not held. A hit keeps the entry alive like {@link #containsKey}. */
    public V get(Object key) {
        return containsKey(key) ? young.get(key) : null;
    }

    public void put(Object key, V value) {
        old.remove(key);
        young.put(key, value);
    }

    public int size() {
        return young.size() + old.size();
    }

    public void clear() {
        young.clear();
        old.clear();
    }

    /**
     * Drops every entry no call touched since the previous sweep. An entry therefore outlives its last touch by
     * one to two sweep periods, and a key touched at least once a period is never dropped.
     */
    public void sweep() {
        IdentityHashMap<Object, V> dropped = old;
        old = young;
        dropped.clear();
        young = dropped;
    }
}
