package cc.starsector.client.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 *
 *
 * <p>The ONE bounded, drop-with-count inbox for a drained CTRL frame family: the net consumer
 * thread {@link #offer}s decoded records, the presentation / fold lane {@link #take}s them all at
 * once. Past {@code cap} UN-TAKEN records an offer is refused and counted — never grows, never a
 * silent loss (wire-driven allocation stays capped).
 *
 * <p>WHY: {@code PredictReconcileDatagramSession} carried the same lock + {@code ArrayDeque} +
 * received/dropped counter + take-copy-clear block hand-copied per CTRL frame kind — eight copies
 * (8 {@code new BoundedInbox} sites, was
 * "twelve") of one bug surface (a cap check or a counter forgotten in one copy is a silent unbounded queue
 * on the wire path). One generic makes the law one place; every future CTRL frame kind gets its
 * bound and its counts for free.
 *
 * The collapse is behaviour-preserving — same caps, same counts, same return
 * shapes, one monitor per family as before.
 *
 * <p>Locking: every method is {@code synchronized} on the inbox itself (NOT a private lock) so a
 * caller can compose a multi-step decision atomically — composite operations
 * {@code synchronized (inbox) { ... }} — the monitor is reentrant (the asteroid family's
 * cap-then-death-row keep relies on this).
 */
public final class BoundedInbox<T> {

    private final int cap;
    private final ArrayDeque<T> records = new ArrayDeque<>();
    private int received;
    private int dropped;

    /** @param cap the un-taken record bound, &gt;= 1 */
    public BoundedInbox(int cap) {
        if (cap < 1) {
            throw new IllegalArgumentException("BoundedInbox cap must be >= 1, was " + cap);
        }
        this.cap = cap;
    }

    /** Counts the record as received; past {@code cap} un-taken records refuses it and counts the
     *  drop. @return {@code true} if queued, {@code false} if cap-dropped */
    public synchronized boolean offer(T record) {
        received++;
        if (records.size() >= cap) {
            dropped++;
            return false;
        }
        records.add(record);
        return true;
    }

    /** Queues past the cap, up to {@code cap + extra} un-taken records; touches NEITHER
     *  {@link #received()} NOR {@link #dropped()} (the caller keeps its own counters for this
     *  lane — the asteroid death-row keep). @return {@code true} if queued */
    public synchronized boolean offerPastCap(T record, int extra) {
        if (records.size() < cap + extra) {
            records.add(record);
            return true;
        }
        return false;
    }

    /** Drain-take every queued record in offer order; empty (never null, immutable) when none. */
    public synchronized List<T> take() {
        if (records.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> takes = new ArrayList<>(records);
        records.clear();
        return takes;
    }

    /** Copies the queued records in order into {@code into}; consumes nothing. */
    public synchronized void peekInto(Collection<? super T> into) {
        into.addAll(records);
    }

    /** Offers seen (queued or cap-dropped); {@link #offerPastCap} does not count. */
    public synchronized int received() {
        return received;
    }

    /** Offers refused at the cap. */
    public synchronized int dropped() {
        return dropped;
    }

    /** Un-taken records on hand. */
    public synchronized int size() {
        return records.size();
    }

    public int cap() {
        return cap;
    }
}
