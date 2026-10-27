package cc.starsector.client.net;

import cc.starsector.client.FallbackAnnounce;
import java.util.ArrayDeque;

/**
 * The snap-freshness state machine, extracted from
 * {@link PredictReconcileDatagramSession} (a maintainability seam):
 * the round-barrier SNAP slots — per-TICK groups with
 * conflation ON, single latest-wins payload slots with it OFF — plus the stale-fold append,
 * the stale-passthrough side-deque, and the lever/lane counters. Behavior is the
 * session's, verbatim; the semantics (tick high-water, fresh-first drain, composition
 * laws) are documented on the session's class doc.
 *
 * <p><b>NOT self-locking (the extraction's one law):</b> every poll/deliver/flip/clear method
 * runs with the session's {@code lock} monitor HELD — the {@code *Locked} suffix marks the
 * contract. The session keeps the single-monitor invariant the 1226/1227 gate legs proved;
 * this class adds no second monitor. Two deliberate exceptions ride the session's existing
 * discipline unchanged: the volatile counters are written by the reader thread (under the
 * monitor, except {@link #noteStaleRoundDropped} — the stale hook's lock-free volatile
 * pre-check, single-writer) and read lock-free by the public receipt accessors.
 *
 * <p>The lever fields arrive RESOLVED — the session parses the knobs (short-circuit: a child
 * lever's knob is never parsed with its parent OFF) and applies the composition laws
 * (staleFold requires tickConflate; stalePassthrough requires staleFold) before construction.
 */
final class SnapFreshnessBuffer {

    /** Passthrough deque bound (it is otherwise the session's only
     * consumer-stall-unbounded structure); overflow drops OLDEST counted — oldest stale
     * content is likeliest already superseded per-tier. */
    static final int STALE_PASSTHROUGH_CAP = 32;

    /** One round-barrier SNAP slot as a per-TICK group (conflation ON): {@code tick} is a
     * HIGH-WATER — never reset on drain (a post-drain same-tick arrival must still append),
     * reset only on the re-admit clear and the round-flip promote. Guarded by the session
     * {@code lock}. */
    private static final class SnapTickGroup {
        long tick = Long.MIN_VALUE;
        final ArrayDeque<byte[]> chunks = new ArrayDeque<>();

        void reset() {
            tick = Long.MIN_VALUE;
            chunks.clear();
        }
    }

    /** The snap-freshness levers, resolved + composition-law-applied by the session at construction. */
    private final boolean tickConflate;
    private final boolean staleFold;
    private final boolean stalePassthrough;

    private byte[] freshSnapPayload; // conflation OFF; latest-wins overwrite IS conflation
    private byte[] bufferedFutureSnapPayload; // conflation OFF (the one-slot future buffer)
    private final SnapTickGroup freshSnapGroup = new SnapTickGroup(); // conflation-ON twin slots
    private final SnapTickGroup futureSnapGroup = new SnapTickGroup();
    /** group-REFUSED older-tick stale chunks awaiting passthrough delivery to the
     * merger's per-tier guards — a SEPARATE deque, out-of-group by construction, so it
     * survives a newer-tick group adoption. */
    private final ArrayDeque<byte[]> stalePassthroughChunks = new ArrayDeque<>();
    private int bufferedFutureRound;

    // ---- counters (volatile: reader-thread written; plain: consumer thread — the session's
    // discipline, moved with the state they count) ----
    private volatile long snapStaleRoundDropped;
    private volatile long snapFutureBuffered;
    private long snapFutureApplied; // consumer thread (drain-time flip)
    private volatile long snapOldTickDropped;
    /** Un-taken chunks a NEWER tick's clear discarded (the post-stall catch-up
     *  discard, graded "drops became late deliveries" — witnessed nowhere before;
     *  {@link #snapOldTickDropped} counts REFUSALS of older ticks only). */
    private volatile long snapSupersededDropped;
    private volatile long snapStaleFoldQueued;
    private volatile long snapStaleOldDropped;
    private volatile long snapStalePassthroughQueued;
    private volatile long snapStalePassthroughDropped;

    SnapFreshnessBuffer(boolean tickConflate, boolean staleFold, boolean stalePassthrough) {
        this.tickConflate = tickConflate;
        this.staleFold = staleFold;
        this.stalePassthrough = stalePassthrough;
    }

    boolean tickConflate() {
        return tickConflate;
    }

    boolean staleFold() {
        return staleFold;
    }

    boolean stalePassthrough() {
        return stalePassthrough;
    }

    long oldTickDropped() {
        return snapOldTickDropped;
    }

    long supersededDropped() {
        return snapSupersededDropped;
    }

    long staleFoldQueued() {
        return snapStaleFoldQueued;
    }

    long staleOldDropped() {
        return snapStaleOldDropped;
    }

    long stalePassthroughQueued() {
        return snapStalePassthroughQueued;
    }

    long stalePassthroughDropped() {
        return snapStalePassthroughDropped;
    }

    /** Drain, fresh lane: conflation ON takes the newest tick's chunks FIFO, ONE per call
     * (the group's tick high-water survives the drain); OFF takes-and-clears the single slot.
     * {@code null} = nothing fresh waiting. */
    byte[] pollFreshLocked() {
        if (tickConflate) {
            return freshSnapGroup.chunks.pollFirst();
        }
        byte[] payload = freshSnapPayload;
        freshSnapPayload = null;
        return payload;
    }

    /** Drain, fresh-first: group-refused stale chunks deliver only when the caller
     * found no newest-tick chunk (passthrough must never delay fresh truth; the merger's
     * per-tier guards make the late fold safe). Always {@code null} with the lever OFF —
     * the deque never fills. */
    byte[] pollStalePassthroughLocked() {
        return stalePassthroughChunks.pollFirst();
    }

    /** DOWN SNAP behind the round barrier, conflation OFF: current round = fresh slot
     * (latest-wins), next round = one-slot future buffer (its MRST is racing retransmission),
     * else stale drop. Returns true when a blocked take should be woken. */
    boolean deliverLocked(int frameRound, int currentRound, byte[] payload) {
        if (frameRound == currentRound) {
            freshSnapPayload = payload;
            return true;
        }
        if (frameRound == ((currentRound + 1) & 0xFF)) {
            bufferedFutureSnapPayload = payload;
            bufferedFutureRound = frameRound;
            snapFutureBuffered++;
            return false;
        }
        snapStaleRoundDropped++;
        return false;
    }

    /** The conflation-ON twin of {@link #deliverLocked}: the SAME barrier decides the slot and
     * the tick-group rules the insert (newer clears+adopts, equal appends, older refuses
     * counted). Returns true when a blocked take should be woken (fresh-group insert only). */
    boolean deliverConflatedLocked(int frameRound, int currentRound, byte[] raw, long tick) {
        if (frameRound == currentRound) {
            if (groupInsertLocked(freshSnapGroup, raw, tick)) {
                return true;
            }
            snapOldTickDropped++;
            return false;
        }
        if (frameRound == ((currentRound + 1) & 0xFF)) {
            if (groupInsertLocked(futureSnapGroup, raw, tick)) {
                bufferedFutureRound = frameRound;
                snapFutureBuffered++;
            } else {
                snapOldTickDropped++;
            }
            return false;
        }
        snapStaleRoundDropped++;
        return false;
    }

    /** The stale-fold hook body (see the session's {@code onSnapStaleDelivered}): current-round
     * chunks fold into the fresh group (in practice only {@code tick ==} the slot high-water
     * appends — the reordered same-tick sibling chunk); an older-tick refusal queues on the
     * passthrough deque with that lever ON (cap {@value #STALE_PASSTHROUGH_CAP}, overflow
     * drops OLDEST counted) or drops counted with it OFF. Returns true when a blocked take
     * should be woken (fold queued OR passthrough queued — a take may be blocked with an
     * empty fresh group). */
    boolean deliverStaleLocked(int frameRound, int currentRound, byte[] raw, long tick) {
        if (frameRound != currentRound) {
            snapStaleRoundDropped++;
            return false;
        }
        if (groupInsertLocked(freshSnapGroup, raw, tick)) {
            snapStaleFoldQueued++;
            return true;
        }
        if (stalePassthrough) {
            if (stalePassthroughChunks.size() >= STALE_PASSTHROUGH_CAP) {
                stalePassthroughChunks.removeFirst();
                snapStalePassthroughDropped++;
                // 1290 (83) CL-5 Q-b: deque overflow = consumer stall (the expected
                // conflation/stale drops stay meters) — the first one confesses.
                FallbackAnnounce.announceOnce("q-b-stale-passthrough-overflow",
                        "cap=" + STALE_PASSTHROUGH_CAP + " consumer stall -> oldest stale chunk dropped");
            }
            stalePassthroughChunks.addLast(raw);
            snapStalePassthroughQueued++;
            return true;
        }
        snapStaleOldDropped++;
        return false;
    }

    /** The stale hook's volatile round pre-check drop (reader thread, deliberately OUTSIDE the
     * monitor — the one lock-free write; single-writer volatile, the session's discipline). */
    void noteStaleRoundDropped() {
        snapStaleRoundDropped++;
    }

    /** The round-flip promote (the session flips {@code currentRoundId} first, then calls this):
     * promote a matching buffered next-round SNAP into the fresh slot (latest-wins). Conflation
     * ON promotes the future GROUP wholesale — the fresh slot is reset first even when nothing
     * is buffered (a deliberate deviation from the OFF path's residue-keep): tick spaces do
     * not compare across rounds, so a dying round's high-water or un-taken chunks must never
     * gate the fresh round's feed; its passthrough refusals die with it too. */
    void promoteAtRoundFlipLocked(int newCurrentRound) {
        if (tickConflate) {
            boolean promote = !futureSnapGroup.chunks.isEmpty()
                    && bufferedFutureRound == newCurrentRound;
            freshSnapGroup.reset();
            if (promote) {
                freshSnapGroup.tick = futureSnapGroup.tick;
                freshSnapGroup.chunks.addAll(futureSnapGroup.chunks);
                snapFutureApplied++;
            }
            futureSnapGroup.reset();
            stalePassthroughChunks.clear();
        } else if (bufferedFutureSnapPayload != null && bufferedFutureRound == newCurrentRound) {
            freshSnapPayload = bufferedFutureSnapPayload;
            bufferedFutureSnapPayload = null;
            snapFutureApplied++;
        }
    }

    /** The B4 re-admit clear (a re-admitted session is a late joiner — the dead session's
     * tick high-waters must not gate the fresh feed; its refusals die with it). */
    void clearForReAdmissionLocked() {
        freshSnapPayload = null;
        bufferedFutureSnapPayload = null;
        freshSnapGroup.reset();
        futureSnapGroup.reset();
        stalePassthroughChunks.clear();
    }

    /** Tick-group insert: newer tick clears + adopts, equal tick appends, older tick refuses
     * (the caller counts the REFUSAL on its own lane's counter; the newer tick's clear counts the
     * un-taken chunks it discards on {@link #snapSupersededDropped}). */
    private boolean groupInsertLocked(SnapTickGroup group, byte[] raw, long tick) {
        if (tick < group.tick) {
            return false;
        }
        if (tick > group.tick) {
            snapSupersededDropped += group.chunks.size();
            group.chunks.clear();
            group.tick = tick;
        }
        group.chunks.addLast(raw);
        return true;
    }
}
