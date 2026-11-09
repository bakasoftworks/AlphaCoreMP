package cc.starsector.client.net;

import cc.starsector.client.FallbackAnnounce;
import cc.starsector.client.PilotWatchLog;
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

    /**
     * The OCCUPANCY cap on each tick group's chunk deque: the
     * same-tick fresh group and the next-round future group, each on its own {@code size()}. The SNAP
     * envelope carries no chunk count, total or index, so the receiver cannot learn how many datagrams a
     * tick lawfully holds, and a server streaming one tick without end would grow a group without bound.
     * Lawful play measured 4.3-4.6 datagrams per tick on AVERAGE (about 1,780x headroom); the PEAK is
     * unmeasured, so {@link #groupDepthMax} meters it for the teardown receipt. At the cap the NEWEST
     * datagram is refused and counted ({@link #groupCapDropped}); an older one is never evicted, and a
     * newer tick still clears and adopts as before. A group stores INFLATED payloads (up to 8,192 B each), so the
     * count alone would allow 64 MiB a group; {@link #SNAP_GROUP_MAX_BYTES} holds it to 16 MiB a group, 32 MiB both.
     */
    static final int SNAP_GROUP_MAX_DATAGRAMS = 8_192;

    /**
     * The BYTE budget on each tick group, beside the datagram count: the
     * summed length of the payloads a group currently HOLDS (an occupancy budget like the count, so a drained chunk
     * frees its bytes). The group stores the inflated payload, which the inflate guard lets reach 8,192 B, so the
     * count cap alone would admit 64 MiB a group. A base L1 serve emits at most 1,164 B raw a datagram, so the count
     * cap's lawful ceiling (8,192 x 1,164 B, about 9.09 MiB) sits under this budget, which therefore never trips
     * before the count cap on a lawful serve; it is about 3,100x the measured average group (4.3-4.6 datagrams a
     * tick, at most 5.4 KB). At the budget the NEWEST datagram is refused and counted on {@link #groupCapDropped},
     * the same meter as a count refusal; an older one is never evicted. Flips if a lawful serve may ever send
     * datagrams up to the 8,192-B inflate ceiling (compressed-fit packing): the budget then binds at 2,048 datagrams.
     */
    static final int SNAP_GROUP_MAX_BYTES = 16 << 20;

    /** {@link #groupInsertLocked} outcomes: appended, refused as an older tick, refused at the cap. */
    private static final int GROUP_INSERTED = 0;
    private static final int GROUP_OLDER_TICK = 1;
    private static final int GROUP_CAPPED = 2;

    /** One round-barrier SNAP slot as a per-TICK group (conflation ON): {@code tick} is a
     * HIGH-WATER — never reset on drain (a post-drain same-tick arrival must still append),
     * reset only on the re-admit clear and the round-flip promote. {@code bytes} is the summed
     * length of {@code chunks}, the {@link #SNAP_GROUP_MAX_BYTES} occupancy. Guarded by the session
     * {@code lock}. */
    private static final class SnapTickGroup {
        long tick = Long.MIN_VALUE;
        final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        long bytes;

        void reset() {
            tick = Long.MIN_VALUE;
            chunks.clear();
            bytes = 0;
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
    /** Datagrams refused at {@link #SNAP_GROUP_MAX_DATAGRAMS} or
     *  {@link #SNAP_GROUP_MAX_BYTES} (either group, either bound) — a SESSION total, never re-armed, so the
     *  teardown receipt carries every round's. */
    private volatile long snapGroupCapDropped;
    /** The deepest tick group seen this session (either group; the occupancy right after an append, never
     *  the appended total) — the per-tick datagram peak the cap was sized without; never re-armed. */
    private volatile int snapGroupDepthMax;
    /** The most bytes one tick group has held this session (either
     *  group; the occupancy right after an append) — the byte budget's peak; never re-armed. */
    private volatile long snapGroupBytesMax;
    /** Per-round refusal state, guarded by the session lock: this round's cap refusals and the one-shot
     *  first-refusal row latch, both re-armed at the round flip and at the re-admit clear. */
    private int groupCapDroppedThisRound;
    private boolean groupCapRowArmed = true;

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

    /** The group-cap meters (lock-free session reads; the
     *  per-round count is lock-guarded, hence its suffix). */
    long groupCapDropped() {
        return snapGroupCapDropped;
    }

    int groupDepthMax() {
        return snapGroupDepthMax;
    }

    long groupBytesMax() {
        return snapGroupBytesMax;
    }

    int groupCapDroppedThisRoundLocked() {
        return groupCapDroppedThisRound;
    }

    /** Drain, fresh lane: conflation ON takes the newest tick's chunks FIFO, ONE per call
     * (the group's tick high-water survives the drain); OFF takes-and-clears the single slot.
     * {@code null} = nothing fresh waiting. A polled chunk frees
     * its length from the group's byte budget (an occupancy budget, never an intake counter). */
    byte[] pollFreshLocked() {
        if (tickConflate) {
            byte[] chunk = freshSnapGroup.chunks.pollFirst();
            if (chunk != null) {
                freshSnapGroup.bytes -= chunk.length;
            }
            return chunk;
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
     * counted; a full group refuses the newest, counted
     * on the cap meter alone). Returns true when a blocked take should be woken (fresh-group
     * insert only). */
    boolean deliverConflatedLocked(int frameRound, int currentRound, byte[] raw, long tick) {
        if (frameRound == currentRound) {
            int outcome = groupInsertLocked(freshSnapGroup, raw, tick);
            if (outcome == GROUP_INSERTED) {
                return true;
            }
            if (outcome == GROUP_OLDER_TICK) {
                snapOldTickDropped++;
            }
            return false;
        }
        if (frameRound == ((currentRound + 1) & 0xFF)) {
            int outcome = groupInsertLocked(futureSnapGroup, raw, tick);
            if (outcome == GROUP_INSERTED) {
                bufferedFutureRound = frameRound;
                snapFutureBuffered++;
            } else if (outcome == GROUP_OLDER_TICK) {
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
     * drops OLDEST counted) or drops counted with it OFF.
     * a same-tick chunk refused by a full fresh group is counted on the cap meter alone and never queued for passthrough (that
     * deque carries older-tick refusals only). Returns true when a blocked take should be
     * woken (fold queued OR passthrough queued — a take may be blocked with an empty fresh
     * group). */
    boolean deliverStaleLocked(int frameRound, int currentRound, byte[] raw, long tick) {
        if (frameRound != currentRound) {
            snapStaleRoundDropped++;
            return false;
        }
        int outcome = groupInsertLocked(freshSnapGroup, raw, tick);
        if (outcome == GROUP_INSERTED) {
            snapStaleFoldQueued++;
            return true;
        }
        if (outcome == GROUP_CAPPED) {
            return false;
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
     * gate the fresh round's feed; its passthrough refusals die with it too. The
     * dying round's group-cap state closes and re-arms here as well. */
    void promoteAtRoundFlipLocked(int newCurrentRound) {
        rearmGroupCapLocked("flip");
        if (tickConflate) {
            boolean promote = !futureSnapGroup.chunks.isEmpty()
                    && bufferedFutureRound == newCurrentRound;
            freshSnapGroup.reset();
            if (promote) {
                freshSnapGroup.tick = futureSnapGroup.tick;
                freshSnapGroup.chunks.addAll(futureSnapGroup.chunks);
                freshSnapGroup.bytes = futureSnapGroup.bytes;
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
     * tick high-waters must not gate the fresh feed; its refusals die with it; the
     * group-cap round state re-arms, the session totals keep). */
    void clearForReAdmissionLocked() {
        rearmGroupCapLocked("readmit");
        freshSnapPayload = null;
        bufferedFutureSnapPayload = null;
        freshSnapGroup.reset();
        futureSnapGroup.reset();
        stalePassthroughChunks.clear();
    }

    /** Tick-group insert: newer tick clears + adopts, equal tick appends, older tick refuses
     * (the caller counts the REFUSAL on its own lane's counter; the newer tick's clear counts the
     * un-taken chunks it discards on {@link #snapSupersededDropped}).
     * An append into a group already holding
     * {@link #SNAP_GROUP_MAX_DATAGRAMS} chunks is refused here and counted on the cap meter (the
     * caller counts nothing more); a kept append raises the depth high-water.
     * so is an append that would carry the group's held bytes past
     * {@link #SNAP_GROUP_MAX_BYTES} (the count bound is judged first); a kept append adds its length
     * and raises the bytes high-water. Refusing the NEWEST keeps the chunks the FIFO drain is already
     * taking; nothing admitted is ever evicted. */
    private int groupInsertLocked(SnapTickGroup group, byte[] raw, long tick) {
        if (tick < group.tick) {
            return GROUP_OLDER_TICK;
        }
        if (tick > group.tick) {
            snapSupersededDropped += group.chunks.size();
            group.chunks.clear();
            group.bytes = 0;
            group.tick = tick;
        }
        String bound = group.chunks.size() >= SNAP_GROUP_MAX_DATAGRAMS ? "datagrams"
                : group.bytes + raw.length > SNAP_GROUP_MAX_BYTES ? "bytes" : null;
        if (bound != null) {
            noteGroupCapRefusalLocked(group == freshSnapGroup ? "fresh" : "future", tick, bound, group.bytes);
            return GROUP_CAPPED;
        }
        group.chunks.addLast(raw);
        group.bytes += raw.length;
        int depth = group.chunks.size();
        if (depth > snapGroupDepthMax) {
            snapGroupDepthMax = depth;
        }
        if (group.bytes > snapGroupBytesMax) {
            snapGroupBytesMax = group.bytes;
        }
        return GROUP_INSERTED;
    }

    /**
     * One cap refusal: counted on the session total and the
     * round's count; the round's FIRST refusal writes ONE error row (bounded tokens: the group, the tick,
     * the cap, the bound that refused, the group's held bytes, the byte budget) and the once-per-boot
     * announce, later refusals that round count silently. The session never fails on it. Written under
     * the session lock, as the passthrough overflow announce above is: at most one row pair a round, and
     * a round cannot refuse before a group holds {@value #SNAP_GROUP_MAX_DATAGRAMS} datagrams or is
     * within one datagram of {@value #SNAP_GROUP_MAX_BYTES} bytes.
     * {@code bound} is {@code datagrams} or {@code bytes}; {@code heldBytes} is the group's
     * occupancy before the refused datagram.
     */
    private void noteGroupCapRefusalLocked(String group, long tick, String bound, long heldBytes) {
        snapGroupCapDropped++;
        groupCapDroppedThisRound++;
        if (!groupCapRowArmed) {
            return;
        }
        groupCapRowArmed = false;
        String detail = "group=" + group + " tick=" + tick + " cap=" + SNAP_GROUP_MAX_DATAGRAMS
                + " bound=" + bound + " bytes=" + heldBytes + " byte_cap=" + SNAP_GROUP_MAX_BYTES
                + " (newest datagram refused, counted; later refusals this round are counted silently)";
        PilotWatchLog.error("predict snap group cap " + detail, null);
        FallbackAnnounce.announceOnce("snap-group-cap", detail);
    }

    /**
     * Closes the group-cap round state at the round flip and
     * the re-admit clear: a round that refused writes ONE totals row, then this round's count zeroes and
     * the first-refusal row re-arms. The session total and the depth and bytes high-waters are NOT reset
     * here; the teardown receipt reads them.
     */
    private void rearmGroupCapLocked(String at) {
        if (groupCapDroppedThisRound > 0) {
            PilotWatchLog.info("predict snap group cap round close: at=" + at + " dropped="
                    + groupCapDroppedThisRound + " session_dropped=" + snapGroupCapDropped
                    + " depth_max=" + snapGroupDepthMax + " bytes_max=" + snapGroupBytesMax
                    + " cap=" + SNAP_GROUP_MAX_DATAGRAMS);
        }
        groupCapDroppedThisRound = 0;
        groupCapRowArmed = true;
    }
}
