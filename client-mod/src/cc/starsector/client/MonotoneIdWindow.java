package cc.starsector.client;

import java.util.TreeSet;

/**
 * A bounded "ever consumed" ledger of server MISSILE wire ids.
 *
 * <p>WHY a window and not a cap: missile wire ids are not per-round. The server mints them from ONE counter that
 * starts at {@code CombatEntityView.MISSILE_NETWORK_ID_BASE} (1,000,000,000) and only ever increments for the
 * server's whole lifetime, never recycled, never reset at a round.
 *
 * A per-round occupancy cap on a holder of those ids would therefore trip on a
 * long but lawful round (the measured mint rate is 5-7 ids a second, so a 2.6-3.5 hour round reaches 65,536),
 * while an unbounded set lets a hostile server grow the kit's heap with one fresh id per frame.
 *
 * <p>THE LAW: the ledger keeps only the ids within {@link #WIDTH} of the largest id it has seen ({@code maxSeen});
 * the low-water is {@code maxSeen - WIDTH}. {@link #offer} REFUSES an id below the low-water (not stored, counted)
 * and, when an id raises {@code maxSeen}, drops every stored id below the new low-water. Every stored id is
 * therefore in {@code [maxSeen - WIDTH, maxSeen]}, so {@link #size} is at most {@code WIDTH + 1} = 65,537 by the
 * integer range alone, with no counter. This EVICTS ids older than the window, by design, and it is the one bounded
 * kit holder that does: on a lawful serve the mint's monotony means an id {@code WIDTH} mints behind the newest is
 * a missile that died long ago and that no later record can carry again, so forgetting it changes nothing; a
 * hostile serve that sends such an id is refused at the ledger and counted. A refused id is still presented by
 * the caller (the ledger only answers "was this id claimed before"); {@link #contains} answers from the stored
 * set alone, so an id below the window reads as never claimed.
 *
 * <p>Value domain: ids are signed longs; lawful ids are positive and at or above the mint base, but every signed
 * value is handled (the low-water saturates at {@code Long.MIN_VALUE}, never wraps). A hostile id near
 * {@code Long.MAX_VALUE} raises the low-water above every lawful id for the rest of the round: the ledger then
 * refuses (and counts) every lawful id until the round reset, which degrades only the duplicate guards it feeds;
 * memory stays bounded.
 *
 * <p>Rows: the round's FIRST refusal writes ONE error row (the once-per-boot announce beside it); later refusals
 * that round only count. {@link #clear} is the round reset: it writes the round's totals row when the round
 * refused anything, then re-arms the ledger, the low-water and the first-refusal row. {@link #logTeardownTotals}
 * writes the session's totals row when the session refused anything (a session's last round never reaches a
 * round reset). Rows carry counts, numeric ids and the window's fixed name only.
 *
 * <p>Threading: the render thread owns every mutation (offer, mark, clear); the teardown row may run on the
 * writer thread and reads scalars only, as the lanes' own meter lines do.
 */
final class MonotoneIdWindow {

    /** The window width: the per-round wire-entity bound, reused (one bound per axis, never a new number). */
    static final long WIDTH = RosterAppendLane.MAX_ROUND_ENTITIES;

    private final String name;
    private final TreeSet<Long> ids = new TreeSet<Long>();
    /** The marked subset (a one-row-per-id log dedupe): always a subset of {@link #ids}, pruned with it. */
    private final TreeSet<Long> marked = new TreeSet<Long>();
    private boolean anySeen;
    private long maxSeen;
    private int refusedRound;
    private long refusedSession;

    /** @param name a fixed lane token naming the ledger in its rows (never wire text). */
    MonotoneIdWindow(String name) {
        this.name = name;
    }

    /** The lowest id the window keeps; {@code Long.MIN_VALUE} before the first id and when it would wrap. */
    long lowWater() {
        if (!anySeen || maxSeen < Long.MIN_VALUE + WIDTH) {
            return Long.MIN_VALUE;
        }
        return maxSeen - WIDTH;
    }

    /**
     * Records {@code id} as claimed. Returns false (stored nowhere, counted) when it is below the low-water; else
     * stores it (idempotent) and, when it is a new maximum, drops every stored id below the new low-water.
     */
    boolean offer(long id) {
        long low = lowWater();
        if (id < low) {
            refusedRound++;
            refusedSession++;
            if (refusedRound == 1) {
                String detail = "id window [" + name + "] refused an id below its low-water: id=" + id
                        + " low_water=" + low + " max_seen=" + maxSeen + " width=" + WIDTH
                        + " (not stored, counted; later refusals this round are counted silently)";
                PilotWatchLog.error("predict missile " + detail, null);
                FallbackAnnounce.announceOnce("missile-id-window-" + name, detail);
            }
            return false;
        }
        ids.add(Long.valueOf(id));
        if (!anySeen || id > maxSeen) {
            anySeen = true;
            maxSeen = id;
            Long newLow = Long.valueOf(lowWater());
            ids.headSet(newLow).clear();
            marked.headSet(newLow).clear();
        }
        return true;
    }

    /** True iff {@code id} is stored: offered, inside the window, not cleared since. */
    boolean contains(long id) {
        return ids.contains(Long.valueOf(id));
    }

    /** Marks a STORED id once: true the first time, false when already marked or not stored. */
    boolean markOnce(long id) {
        Long key = Long.valueOf(id);
        return ids.contains(key) && marked.add(key);
    }

    int size() {
        return ids.size();
    }

    int markedSize() {
        return marked.size();
    }

    /** Ids refused below the low-water since the last round reset. */
    int refusedRound() {
        return refusedRound;
    }

    /** Ids refused below the low-water over the ledger's life (the session; round resets keep it). */
    long refusedSession() {
        return refusedSession;
    }

    /** The round reset: the round's totals row when it refused anything, then an empty, re-armed window. */
    void clear() {
        if (refusedRound > 0) {
            PilotWatchLog.info("predict missile id window [" + name + "] round close: refused_below_window="
                    + refusedRound + " refused_session=" + refusedSession + " size=" + ids.size()
                    + " max_seen=" + maxSeen + " width=" + WIDTH);
        }
        ids.clear();
        marked.clear();
        anySeen = false;
        maxSeen = 0L;
        refusedRound = 0;
    }

    /** The session-teardown totals row, written only when the session refused anything. */
    void logTeardownTotals() {
        long session = refusedSession;
        if (session > 0) {
            PilotWatchLog.info("predict missile id window [" + name + "] teardown: refused_round=" + refusedRound
                    + " refused_session=" + session + " size=" + ids.size() + " max_seen=" + maxSeen
                    + " width=" + WIDTH);
        }
    }
}
