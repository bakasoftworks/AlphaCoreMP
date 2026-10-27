package cc.starsector.client;

import cc.starsector.client.net.ClientMatchReset;
import cc.starsector.client.net.ClientRosterAppend;
import cc.starsector.client.net.PredictSession;

import com.fs.starfarer.api.combat.ShipAPI;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * The RAPP roster-append client lane (the standing
 * bite-#1 extraction): net-thread intake and render-thread registry fold of
 * {@link ClientRosterAppend} frames, plus the B1b {@code /ship} seat variant RE-POINT law.
 * NEW-LANE-LAW collaborator of {@link PredictReconcileCombatPlugin} (the
 * {@link StationAdoptBindLane} shape): the plugin owns the drive call (fold BEFORE the
 * spawn-on-sight gate) and the match-reset queue drop; every append/repoint law lives here.
 *
 * <p><b>Thread contract.</b> {@link #enqueue} runs on the net thread (the edge-event-queue
 * idiom — gameplay-critical, unbounded, never dropped: a lost append re-fields the ghost
 * match); {@link #drain} folds on the render thread; {@link #clearQueue} at the match-reset
 * boundary drops the frames older than the consumed reset's round epoch (the OLD round's).
 *
 * <p><b>Round-epoch gate</b>. Every frame carries
 * the session's ctrl round epoch at intake ({@link ClientRosterAppend#roundEpoch()}; the net thread
 * routes MRST ≺ RAPP on one FIFO, so a row stamped epoch N FOLLOWS the N-th reset). The render
 * thread retires epochs as it CONSUMES resets ({@link #onResetConsumed}, by value = max, never
 * {@code ++}; seeded at the plugin bind by {@link #seedFromSession}). A queue head stamped past
 * the retired epoch STOPS the drain (FIFO kept) until the plugin consumes its reset — so a new
 * round's row never folds against the dying round's registry (the station's tick-0 wings,
 * seen in two test flights: count-ignored against the round-0 body of the same id, then swept).
 * A head deferred past {@link #RAPP_EPOCH_FAILOPEN_MS} fails OPEN (error row + meter; muted
 * after a clean stream end) — a lost reset can never strand the lane.
 *
 * <p><b>Row law</b> (sides arrive descriptor-ABSOLUTE and map RELATIVE to
 * {@code plugin.ownDescSide} here — relative-in-frame would be wrong for side-1 seats):
 * <ul>
 * <li><b>OWN wire id (B1b own-seat guard):</b> re-point {@code variantById} ONLY — NEVER mint a
 * RemoteEntity (that replicates our own ship; B1b's seat re-point fan is the first RAPP lane
 * that carries seat wire ids). {@code respawnOwn} reads {@code variantById} FIRST, so the next
 * own respawn fields the re-pointed hull; the LIVE own body is never re-minted here (the
 * authority/rejoin lanes own it — a wrong-variant own life self-heals at its next respawn).</li>
 * <li><b>Known id, variant EQUAL:</b> count-ignore (idempotent — the late-joiner catch-up
 * replay, the loss-robust re-fan and a duplicate are indistinguishable and all safe).</li>
 * <li><b>Known id, variant DIFFERS (B1b repoint):</b> the server re-announced a seat under a
 * new variant ({@code /ship}): re-point {@code variantById} (the ONE variant authority —
 * {@code respawnOwn}, spawn-on-sight and {@code OfficerLoadout} all read it) and drop any stale
 * bound body via the {@code respawnRemote} reset idiom so spawn-on-sight re-mints from the
 * re-pointed registry — convergent under any UDP arrival order. A stale body still ALIVE (the
 * reorder race minted the old variant moments earlier) is also REMOVED from the engine (the
 * match-reset sweep idiom): unlike a corpse — whose wreckage stays, mirroring the server world
 * — a live wrong-variant hull has no wire feed and would linger as a ghost.</li>
 * <li><b>Unknown id:</b> APPEND a round-local registry entry (+ its variant row) that the
 * spawn-on-sight gate then mints at first pose sight (the pre-B1b wave lane, verbatim).</li>
 * </ul>
 */
final class RosterAppendLane {

    private final PredictReconcileCombatPlugin plugin;
    private final ArrayDeque<ClientRosterAppend> queue = new ArrayDeque<ClientRosterAppend>();
    private int rowsApplied;
    private int rowsKnown;
    /** B1b — known-id rows whose variant differed: seat re-points applied (own + remote). */
    private int rowsRepointed;

    // The round-epoch gate state (see the class doc).
    /** Fail-open bound for a deferred head (the pre-publish window is ~7 ms; a HOLD never drains). */
    static final long RAPP_EPOCH_FAILOPEN_MS = 5_000L;
    /** The highest ctrl round epoch whose reset the plugin consumed (render thread). */
    private int resetsRetired;
    /** Deferral episode start ({@link #clock} ms); 0 = not deferring (render thread). */
    private long deferredSinceMillis;
    /** Rows currently queued — guarded by {@link #queue} (net-thread add, render-thread take). */
    private int queuedRows;
    /** The queue PREFIX already counted into the deferral meters (frames / rows; render thread,
     *  updated under the monitor). Frames only append at the tail and leave from the head or the
     *  reset cut, so the counted frames stay a prefix: each frame counts ONCE, at its first stop. */
    private int deferredCountedFrames;
    private int deferredCountedRows;
    /** Meters (render thread; the debrief line reads them there). Every frame queued behind a
     *  stopped head is deferred with it — a wrap's carried-pick + growth frames both count. */
    private int rowsDeferred;
    private int framesDeferred;
    private int rowsDroppedAtReset;
    /** old-epoch SEAT rows folded into the registry at a reset
     *  ({@link #foldSeatRowsBeforeReset}), and the frames that left the queue whole (all-seat frames). */
    private int rowsFoldedAtReset;
    private int framesFoldedAtReset;
    private int epochFailOpens;

    /**
     * The per-round bound on the replica roster map
     * ({@code remoteEntities}, roster and appended entries together) a fresh appended wire id may
     * grow — at or past it, once {@link #retireDeadBeforeSight} freed no room, the id is REFUSED
     * (never stored), counted, and named in ONE error row a
     * round plus a one-shot verdict announce, so a lawful roster that ever reached it is a loud
     * desync, never a silent one. The server holds no hard per-round entity budget (its round
     * append-log grows for as long as an endless round runs), so the cap mirrors the largest roster
     * its documented laws produce: the match document's summed roster (4096 ships) plus a late
     * joiner's replay of a three-hour endless round's append-log (~3500 frames, the catch-up
     * cursor's own sizing) at the codec's 16 rows a frame = 60,096; 65,536 leaves ~9% headroom.
     * The largest shipped shape at 255 seats with its waves (at most 400 rows a scaled wave) and
     * one pod a seat sits far below it. (MatchDocumentLaws.SHIPS_MAX, RosterAppendCatchup,
     * RosterAppendFrameCodec.MAX_ROWS_PER_FRAME, WaveSchedule) In-session non-fighter deaths keep
     * their rows AND bodies ({@code FoeStateLaneCoordinator.killRemote} never nulls {@code re.ship};
     * {@link #retireDeadBeforeSight} spares bodied entries), so one endless round's hulls accumulate
     * toward the cap: measured 593 hulls/h on the owner's standing fortress =
     * ~110 h of ONE round — lawful, far past any session; the bodied-hulk retire is a design item
     * (work-queue Kit-cap (d)), never this lane's sweep.
     */
    static final int MAX_ROUND_ENTITIES = 65_536;

    /**
     * The intake queue's frame bound. The largest lawful
     * backlog is a late joiner's whole catch-up replay landing before the render thread drains (a
     * three-hour endless round's append-log is ~3500 frames, the catch-up cursor's own sizing);
     * 8192 holds over twice that. Past it the INCOMING frame is refused (counted, one error row a
     * round): refusing the newest keeps the queued frames an exact prefix of the server's log
     * order (the order the catch-up cursor delivers) and leaves the deferral meters' counted
     * prefix intact, while dropping the oldest would cut the round's earliest rows (a later
     * re-point of a dropped id would then fold as a fresh append) and could pull a deferred head
     * out from under the round-epoch gate.
     */
    static final int MAX_QUEUED_FRAMES = 8_192;

    /**
     * Failed spawn-on-sight attempts (a NULL mint or a
     * throw) one replica entry may spend before it is marked dead for the round — 300 is the bind
     * census's own PARTIAL-row cadence, counted in render passes (one per walk that fails it, a
     * stale-snapshot pass included), so its wall time is fps-dependent; room for a transient engine
     * refusal to clear. The first failure logs (the first throw keeps its stack trace), later ones
     * are counted silently, the spent budget logs ONE error row; the entry stays unbound in the
     * census, and a variant re-point or the next round's reset re-arms it.
     */
    static final int SPAWN_FAILURE_BUDGET = 300;

    /**
     * The FRESH merged snapshot frames
     * (simTick advanced — the wire's net tick, never a render pass against a stale snapshot) an
     * appended entry may spend bodiless with no pose before the entity cap may retire it as dead
     * before sight ({@code RemoteEntity.awaitFrames}). The kit's own absence law kills a tracked
     * non-fighter replica on ONE merged frame that lacks it and holds a fighter only
     * {@link PredictReconcileCombatPlugin#FIGHTER_REASON_HOLD_TICKS}; an appended row races its
     * entity's first pose by a few frames at most. 256 fresh frames is seconds of wire time at the
     * served snapshot cadence, independent of render fps (a snapshot gap counts nothing), far past
     * that race and far below a round. Read only when a fresh id meets a full map.
     * (FoeStateLaneCoordinator.killRemote)
     */
    static final int DEAD_BEFORE_SIGHT_FRAMES = 256;

    /** The per-round bound meters ({@link #onRoundReset}
     *  clears them). Fresh rows refused at {@link #MAX_ROUND_ENTITIES} and entries whose spawn
     *  budget ran out (render thread); frames/rows refused at {@link #MAX_QUEUED_FRAMES} (guarded
     *  by {@link #queue}: net-thread write). The first refusal of a round is the count's 0 -> 1. */
    private int rowsRefusedAtCap;
    private int spawnBudgetsSpent;
    private int framesRefusedAtIntake;
    private int rowsRefusedAtIntake;
    /** Appended entries the entity cap retired as dead
     *  before sight this round ({@link #retireDeadBeforeSight}); render thread, cleared by
     *  {@link #onRoundReset} after its summary row prints it. */
    private int entriesRetiredDead;
    /** Failed spawn attempts per entry object (identity: a re-appended id is a fresh budget):
     *  {failures, throws}; render thread, cleared at the round reset. */
    private final IdentityHashMap<PredictReconcileCombatPlugin.RemoteEntity, int[]> spawnFailures =
            new IdentityHashMap<PredictReconcileCombatPlugin.RemoteEntity, int[]>();

    /** Valve clock — package-private and settable (the class is final): the smoke injects it. */
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** Set by the net loop at a clean end of stream: the valve is muted (a goodbye right after a
     *  routed MRST must not fire it at teardown); the head keeps deferring silently. */
    volatile boolean streamEnded;

    RosterAppendLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** RAPP fold observability: receive-side frame counter — the E3 lost-row hunt
     *  found this lane logs at APPLY only, so delivered-but-folded-silently vs never-delivered
     *  could not be told apart at bytes. Volatile: net-thread write, log-line read. */
    private volatile int framesReceived;
    /** Own rows the registry deduped while the LIVE hull disagreed (each armed
     *  the alive re-field) — non-zero in a debrief = a refused/forked join pick was healed. */
    private int ownPickForks;

    /** The LIVE own hull's variant id, or null when there is no own body / the
     *  engine read throws (a null never dedups — the caller falls through to count-ignored). */
    private String liveOwnHullVariantOrNull() {
        ShipAPI own = plugin.ownShip;
        if (own == null) {
            return null;
        }
        try {
            return own.getVariant() != null ? own.getVariant().getHullVariantId() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Net-thread intake — queue one decoded roster append for the render thread's fold.
     *  At {@link #MAX_QUEUED_FRAMES} the incoming frame is
     *  refused (one compare under the monitor; the round's first refusal logs, later ones count). */
    void enqueue(ClientRosterAppend append) {
        int refused = 0;
        int refusedRows = 0;
        synchronized (queue) {
            framesReceived++;
            if (queue.size() >= MAX_QUEUED_FRAMES) {
                refused = ++framesRefusedAtIntake;
                rowsRefusedAtIntake += append.rows().size();
                refusedRows = rowsRefusedAtIntake;
            } else {
                queue.addLast(append);
                queuedRows += append.rows().size();
            }
        }
        if (refused > 0) {
            if (refused == 1) {
                String detail = "intake queue full (" + MAX_QUEUED_FRAMES + " frames): refused frame rows="
                        + append.rows().size() + " epoch=" + append.roundEpoch()
                        + " (later refusals this round are counted silently; rows refused=" + refusedRows + ")";
                PilotWatchLog.error("predict roster-append: " + detail, null);
                FallbackAnnounce.announceOnce("rapp-intake-cap", detail);
            }
            return;
        }
        PilotWatchLog.info("predict roster-append: RECV frame rows=" + append.rows().size()
                + " epoch=" + append.roundEpoch() + " (received=" + framesReceived + ")");
    }

    /** match-reset boundary cut — drops ONLY the frames
     *  older than the consumed reset's epoch ({@code roundEpoch < resetEpoch}: the OLD round's
     *  pre-boundary appends, exact under coalesced MRSTs) and keeps the rest IN ORDER (the new
     *  round's rows fold after the rebuild). {@code Integer.MAX_VALUE} = a bare life reset: drop
     *  everything (the law before the round-epoch gate). Render thread; one info row per cut. */
    void clearQueue(int resetEpoch) {
        int droppedFrames = 0;
        int droppedRows = 0;
        int kept;
        int keptRows;
        synchronized (queue) {
            int counted = deferredCountedFrames;
            int pos = 0;
            for (Iterator<ClientRosterAppend> it = queue.iterator(); it.hasNext(); pos++) {
                ClientRosterAppend frame = it.next();
                if (frame.roundEpoch() >= resetEpoch) {
                    continue;
                }
                it.remove();
                int n = frame.rows().size();
                queuedRows -= n;
                droppedFrames++;
                droppedRows += n;
                if (pos < counted) {
                    deferredCountedFrames--;
                    deferredCountedRows -= n;
                }
            }
            kept = queue.size();
            keptRows = queuedRows;
        }
        rowsDroppedAtReset += droppedRows;
        if (droppedFrames > 0) {
            // Stamps never decrease along the FIFO, so a drop always took the HEAD: its deferral
            // episode is over — the next deferred head opens its own (a bare life-reset cut retires
            // nothing, so nothing else would clear the clock before the same advance's drain;
            // pinned by RosterAppendResetSmoke L3c).
            deferredSinceMillis = 0L;
        }
        // kept frames/rows = the new round's stamped frames that never needed a deferral (no render
        // frame landed in the pre-publish window) — the timing-independent twin of the deferred meters
        // for the wrap bar.
        PilotWatchLog.info("predict roster-append: reset cut ("
                + (resetEpoch == Integer.MAX_VALUE ? "life reset: all" : "epoch<" + resetEpoch)
                + ") dropped frames=" + droppedFrames + " rows=" + droppedRows
                + " kept frames=" + kept + " rows=" + keptRows + " retired=" + resetsRetired
                + " (dropped_rows=" + rowsDroppedAtReset + ")");
    }

    /** The wrap re-announce race (r1). An old-epoch seat row
     *  still queued when the render thread rebuilds (a render stall) would be dropped by
     *  {@link #clearQueue} AFTER {@code applyMatchReset} fielded the own hull on the stale believed
     *  variant — and the own-hull invariant (registry == live) could then never heal it.
     * the server now fans the wrap's seat re-point AFTER the MRST (stamped
     *  N = the reset's own epoch), so the fold also takes the reset epoch's seat rows
     *  ({@code roundEpoch <= resetEpoch}; the cut keeps {@code <}) — else that row waits deferred
     *  past the own read and the own seat fields the stale (or module) variant, then re-fields. The
     *  plugin calls this INSIDE the registry rebuild, past the HOLD gate and BEFORE its own-variant
     *  read ({@code Integer.MAX_VALUE} = a bare life reset: every queued frame).
     *  <p>REGISTRY-ONLY: for each frame at or below {@code resetEpoch}, each SEAT row
     *  ({@code isSeatWire}: descriptor-stable ids — a non-seat id may be re-used by the next round, so
     *  its row still drops at the cut; the predicate =
     *  "fold this seat row now" — the plugin's predicate also rejects a seat with an un-taken MMNT queued
     *  (MMNT≺RAPP), and a seat row it rejects is treated exactly as a non-seat row: kept at the same
     *  epoch, dropped by the cut at {@code <}, applied by the post-retire drain at {@code ==}) is
     *  written into {@code variantById} in FIFO order (the last row
     *  for a seat wins, as in {@link #drain}). None of the drain's arms run — no re-mint, no body
     *  removal, no own re-field latch: the rebuild sweeps every body, fields the own hull from the
     *  registry and re-mints the other seats on sight from it. An all-seat frame leaves the queue; a
     *  MIXED frame stays with only its non-seat rows (same epoch), so the cut drops and counts only
     *  those (a reset-epoch frame's non-seat rows are kept by the cut and drain after the retire).
     *  Render thread; one info row when a row folded. Returns the rows folded. */
    int foldSeatRowsBeforeReset(int resetEpoch, LongPredicate isSeatWire) {
        List<ClientRosterAppend.Row> folded = null;
        int foldedFrames = 0;
        boolean headLeft = false;
        synchronized (queue) {
            int counted = deferredCountedFrames;
            List<ClientRosterAppend> rebuilt = new ArrayList<ClientRosterAppend>(queue.size());
            int pos = 0;
            for (ClientRosterAppend frame : queue) {
                int seatRows = 0;
                List<ClientRosterAppend.Row> rest = null;
                if (frame.roundEpoch() <= resetEpoch) {
                    for (ClientRosterAppend.Row row : frame.rows()) {
                        if (isSeatWire.test(row.wireId())) {
                            if (folded == null) {
                                folded = new ArrayList<ClientRosterAppend.Row>();
                            }
                            folded.add(row);
                            seatRows++;
                        } else {
                            if (rest == null) {
                                rest = new ArrayList<ClientRosterAppend.Row>(frame.rows().size());
                            }
                            rest.add(row);
                        }
                    }
                }
                if (seatRows == 0) {
                    rebuilt.add(frame);
                } else {
                    queuedRows -= seatRows;
                    if (pos < counted) {
                        deferredCountedRows -= seatRows;
                    }
                    if (rest == null) {
                        foldedFrames++;
                        headLeft |= pos == 0;
                        if (pos < counted) {
                            deferredCountedFrames--;
                        }
                    } else {
                        rebuilt.add(new ClientRosterAppend(rest, frame.roundEpoch()));
                    }
                }
                pos++;
            }
            if (folded != null) {
                queue.clear();
                queue.addAll(rebuilt);
            }
        }
        if (folded == null) {
            return 0;
        }
        if (headLeft) {
            deferredSinceMillis = 0L; // the clearQueue law: a removed head ends its deferral episode
        }
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < folded.size(); i++) {
            ClientRosterAppend.Row row = folded.get(i);
            plugin.variantById.put(Long.valueOf(row.wireId()), row.variantId());
            if (i < 8) {
                ids.append(i > 0 ? "," : "").append(row.wireId());
            }
        }
        if (folded.size() > 8) {
            ids.append(",...");
        }
        rowsFoldedAtReset += folded.size();
        framesFoldedAtReset += foldedFrames;
        // The reset-fold row keeps the cut row's epoch wording and the
        // lane's prefix (a grep for that prefix finds it).
        PilotWatchLog.info("predict roster-append: reset fold ("
                + (resetEpoch == Integer.MAX_VALUE ? "life reset: all" : "epoch<=" + resetEpoch)
                + ") whole frames=" + foldedFrames + " seat rows=" + folded.size() + " ids=" + ids);
        return folded.size();
    }

    /** The plugin consumed {@code reset} (applied OR
     *  ignored) — retire its epoch BY VALUE ({@code max}, never {@code ++}: a coalesced or
     *  valve-adopted epoch must never drift the count). A null reset (bare life reset) or an
     *  epoch at/below the retired one is a no-op. Render thread. */
    void onResetConsumed(ClientMatchReset reset) {
        if (reset == null || reset.roundEpoch() <= resetsRetired) {
            return;
        }
        resetsRetired = reset.roundEpoch();
        deferredSinceMillis = 0L; // a still-deferred head (a newer reset pending) opens a new episode
    }

    /** The plugin bind — the session is app-scoped and
     *  outlives a mission while this lane is per plugin, so the retired count starts at the
     *  session's epoch (unseeded, every frame of a re-picked mission would defer to the valve). */
    void seedFromSession(PredictSession s) {
        resetsRetired = s.ctrlRoundEpoch();
        PilotWatchLog.info("predict roster-append: epoch seeded retired=" + resetsRetired);
    }

    /** The deferred head either keeps waiting
     *  ({@code false}) or the valve opens ({@code true}: the caller folds it). Arms + logs once per
     *  deferral episode. */
    private boolean epochValveOpens(ClientRosterAppend head) {
        if (deferredSinceMillis == 0L) {
            deferredSinceMillis = clock.getAsLong();
            PilotWatchLog.info("predict roster-append: DEFERRED frame rows=" + head.rows().size()
                    + " epoch=" + head.roundEpoch() + " retired=" + resetsRetired
                    + " (reset pending)");
            return false;
        }
        if (streamEnded) {
            return false; // clean end of stream: no reset can still come — defer silently
        }
        long waited = clock.getAsLong() - deferredSinceMillis;
        if (waited <= RAPP_EPOCH_FAILOPEN_MS) {
            return false;
        }
        epochFailOpens++;
        PilotWatchLog.error("predict roster-append: EPOCH FAIL-OPEN frame rows=" + head.rows().size()
                + " epoch=" + head.roundEpoch() + " retired=" + resetsRetired + " deferred "
                + waited + " ms > " + RAPP_EPOCH_FAILOPEN_MS + " ms with no reset consumed"
                + " -> adopting the frame's epoch and folding (failopen=" + epochFailOpens + ")",
                null);
        resetsRetired = Math.max(resetsRetired, head.roundEpoch());
        return true;
    }

    // the round-epoch gate's meters + the smoke's reads (render thread).
    int rowsDeferred() {
        return rowsDeferred;
    }

    int framesDeferred() {
        return framesDeferred;
    }

    int rowsDroppedAtReset() {
        return rowsDroppedAtReset;
    }

    /** The r1 seat-row fold meters (census line + smoke). */
    int rowsFoldedAtReset() {
        return rowsFoldedAtReset;
    }

    int framesFoldedAtReset() {
        return framesFoldedAtReset;
    }

    int epochFailOpens() {
        return epochFailOpens;
    }

    int rowsApplied() {
        return rowsApplied;
    }

    int rowsKnown() {
        return rowsKnown;
    }

    int resetsRetired() {
        return resetsRetired;
    }

    /** The per-round bound meters (the smoke's reads). */
    int rowsRefusedAtCap() {
        return rowsRefusedAtCap;
    }

    int spawnBudgetsSpent() {
        return spawnBudgetsSpent;
    }

    int entriesRetiredDead() {
        return entriesRetiredDead;
    }

    int framesRefusedAtIntake() {
        synchronized (queue) {
            return framesRefusedAtIntake;
        }
    }

    /** A fresh wire id met a full roster map
     *  ({@link #MAX_ROUND_ENTITIES}) that {@link #retireDeadBeforeSight} could not thin — refused,
     *  counted; the round's first refusal logs ONE error row (and the one-shot verdict announce,
     *  both naming the round's retire count), later ones only count. Render thread. */
    private void refuseAtEntityCap(ClientRosterAppend.Row row) {
        if (++rowsRefusedAtCap != 1) {
            return;
        }
        String detail = "entity cap (" + MAX_ROUND_ENTITIES + " roster entries): refused fresh id "
                + row.wireId() + " variant=" + FallbackAnnounce.sanitize(row.variantId(), 64)
                + " retiredDead=" + entriesRetiredDead
                + " (not stored; later refusals this round are counted silently)";
        PilotWatchLog.error("predict roster-append: " + detail, null);
        FallbackAnnounce.announceOnce("rapp-entity-cap", detail);
    }

    /**
     * The entity cap's reclaim, run only when a fresh id
     * meets a full map (at most once a drain pass). A late joiner's catch-up replays every
     * appended id of the round, including ids that died before the join: such an entry never gets
     * a pose, so spawn-on-sight retries it forever, and no kill or reap path (each needs a body)
     * removes it; left alone, the map fills with them and the round's newest rows, the live
     * hulls, are refused. RETIRED: an APPENDED bodiless entry that is marked dead ({@code killed}:
     * dead at first sight, no variant row, spawn budget spent) or has spent
     * {@link #DEAD_BEFORE_SIGHT_FRAMES} spawn-on-sight passes with no pose. SPARED: roster entries
     * and an entry between a same-id re-life and its re-mint ({@code remounted}), because a seat
     * or a remount comes back at the SAME id with no new append row; station modules, mounted or
     * mount-pending seats and mint-suppressed transfer ids (the spawn walk's own skips, owned by
     * their lanes); station parents (handed to the station lane at append); and every BODIED entry,
     * a killed in-session hulk included ({@code killRemote} keeps {@code re.ship}) — those rows
     * accumulate for the round (the {@link #MAX_ROUND_ENTITIES} rate), a design item, never this
     * sweep's. A retired id that is appended again later folds as a fresh entry. Each removal clears what the reap sweep and the
     * station unadopt clear for an id: the variant row, the census unbound count, this lane's
     * spawn-failure budget, a queued despawn reason, the facing slot and the shield-facing
     * keep-last (the body-keyed releases have no body here). The round's first retire logs ONE
     * row; later ones only count. Render thread.
     */
    private void retireDeadBeforeSight() {
        int retired = 0;
        Iterator<Map.Entry<Long, PredictReconcileCombatPlugin.RemoteEntity>> it =
                plugin.remoteEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, PredictReconcileCombatPlugin.RemoteEntity> entry = it.next();
            Long key = entry.getKey(); // read BEFORE remove: a TreeMap removal may re-use this node
            PredictReconcileCombatPlugin.RemoteEntity re = entry.getValue();
            if (re.ship != null || !re.appended || re.remounted || re.stationModule
                    || (!re.killed && re.awaitFrames < DEAD_BEFORE_SIGHT_FRAMES)
                    || plugin.mmnt.mountedOrPending(re.entityId)
                    || plugin.xfer.suppressMintFor(re.entityId)
                    || MatchMissionBuilder.isStationVariant(plugin.variantById.get(key))) {
                continue;
            }
            it.remove();
            plugin.variantById.remove(key);
            spawnFailures.remove(re);
            synchronized (plugin.despawnReasonByEntity) {
                plugin.despawnReasonByEntity.remove(key);
            }
            plugin.foeFacing.release(re.entityId);
            plugin.lastShieldFacingU8.remove(key);
            retired++;
        }
        if (retired == 0) {
            return;
        }
        plugin.remoteUnbound = Math.max(0, plugin.remoteUnbound - retired);
        boolean firstThisRound = entriesRetiredDead == 0;
        entriesRetiredDead += retired;
        if (firstThisRound) {
            PilotWatchLog.info("predict roster-append: entity cap (" + MAX_ROUND_ENTITIES
                    + " roster entries) reached: retired " + retired + " appended entries dead"
                    + " before sight (bodiless; marked dead or no pose for " + DEAD_BEFORE_SIGHT_FRAMES
                    + " fresh snapshot frames) -> map " + plugin.remoteEntities.size()
                    + " (later retires this round are counted silently)");
        }
    }

    /**
     * One failed spawn-on-sight attempt for {@code re} — a
     * NULL mint ({@code failure == null}; {@code spawnCall} names the engine call) or a throw.
     * Counts against {@link #SPAWN_FAILURE_BUDGET}: the entry's first failure logs, its first
     * throw logs WITH the stack trace, later ones only count; the attempt that spends the budget
     * marks the entry dead for the round ({@code killed} — the walk's skip flag, the plugin's
     * dead-at-first-sight idiom) and logs ONE error row, so neither the walk nor the log revisits
     * it. Render thread.
     */
    void onSpawnFailed(PredictReconcileCombatPlugin.RemoteEntity re, String variantId, String spawnCall,
            Throwable failure, long simTick) {
        int[] counts = spawnFailures.get(re);
        if (counts == null) {
            counts = new int[2];
            spawnFailures.put(re, counts);
        }
        int failures = ++counts[0];
        int throwsSeen = failure != null ? ++counts[1] : counts[1];
        if (failure != null && throwsSeen == 1) {
            PilotWatchLog.error("predict bots bind: entity " + re.entityId + " SPAWN FAILED variant="
                    + FallbackAnnounce.sanitize(variantId, 64)
                    + " -> retry next frame (failure " + failures + "/" + SPAWN_FAILURE_BUDGET
                    + "; later failures counted silently)", failure);
        } else if (failure == null && failures == 1) {
            PilotWatchLog.info("predict bots bind: entity " + re.entityId + " " + spawnCall
                    + " returned NULL variant=" + FallbackAnnounce.sanitize(variantId, 64)
                    + " -> retry next frame (failure 1/"
                    + SPAWN_FAILURE_BUDGET + "; later failures counted silently)");
        }
        if (failures < SPAWN_FAILURE_BUDGET) {
            return;
        }
        re.killed = true;
        re.deathTick = simTick;
        spawnBudgetsSpent++;
        PilotWatchLog.error("predict bots bind: entity " + re.entityId + " SPAWN BUDGET SPENT after "
                + failures + " failed attempts (throws=" + throwsSeen + ", last="
                + (failure != null ? failure.getClass().getSimpleName() : "NULL") + ") variant="
                + FallbackAnnounce.sanitize(variantId, 64) + " -> dead for the round, stays unbound in the census until the entity cap retires it (spent=" + spawnBudgetsSpent
                + ")", null);
    }

    /** {@code re} minted — its failure count (if any) goes.
     *  The common frame (no failures this round) is one isEmpty read. Render thread. */
    void onSpawned(PredictReconcileCombatPlugin.RemoteEntity re) {
        if (!spawnFailures.isEmpty()) {
            spawnFailures.remove(re);
        }
    }

    /**
     * The round reset re-arms the per-round bounds — the
     * refusal counts (so the next round's first refusal logs again) and every entry's spawn
     * budget (the reset itself clears each surviving entry's dead flag). A round that refused,
     * spent or retired anything closes with ONE summary row so its counts outlive the clear.
     * Render thread.
     */
    void onRoundReset() {
        int intakeFrames;
        int intakeRows;
        synchronized (queue) {
            intakeFrames = framesRefusedAtIntake;
            intakeRows = rowsRefusedAtIntake;
            framesRefusedAtIntake = 0;
            rowsRefusedAtIntake = 0;
        }
        if (rowsRefusedAtCap > 0 || intakeFrames > 0 || spawnBudgetsSpent > 0 || entriesRetiredDead > 0) {
            PilotWatchLog.info("predict roster-append: round bounds closed: entity-cap refused rows="
                    + rowsRefusedAtCap + " intake refused frames=" + intakeFrames + " rows=" + intakeRows
                    + " spawn budgets spent=" + spawnBudgetsSpent + " retiredDead=" + entriesRetiredDead);
        }
        rowsRefusedAtCap = 0;
        spawnBudgetsSpent = 0;
        entriesRetiredDead = 0;
        spawnFailures.clear();
    }

    int queuedFrames() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** Render-thread fold — called BEFORE the spawn-on-sight gate so a hull appended (or
     *  re-pointed) this frame can mint at this same frame's first pose sight. */
    void drain() {
        long ownWireId = plugin.session.shipId();
        boolean capRetireWalked = false; // at most ONE retire walk a drain pass (one pass a render frame)
        while (true) {
            // PEEK + the round-epoch gate. Only this
            // (render) thread removes frames, so the head peeked here is the one polled below.
            ClientRosterAppend append;
            int newlyDeferredFrames = 0;
            int newlyDeferredRows = 0;
            synchronized (queue) {
                append = queue.peekFirst();
                if (append != null && append.roundEpoch() > resetsRetired) {
                    // Everything queued waits behind this head (stamps never decrease along the FIFO).
                    newlyDeferredFrames = queue.size() - deferredCountedFrames;
                    newlyDeferredRows = queuedRows - deferredCountedRows;
                    deferredCountedFrames = queue.size();
                    deferredCountedRows = queuedRows;
                }
            }
            if (append == null) {
                deferredSinceMillis = 0L;
                return;
            }
            if (append.roundEpoch() > resetsRetired) {
                framesDeferred += newlyDeferredFrames;
                rowsDeferred += newlyDeferredRows;
                if (!epochValveOpens(append)) {
                    return; // FIFO kept: nothing behind the deferred head folds early
                }
            }
            synchronized (queue) {
                queue.pollFirst();
                queuedRows -= append.rows().size();
                if (deferredCountedFrames > 0) {
                    deferredCountedFrames--;
                    deferredCountedRows -= append.rows().size();
                }
            }
            deferredSinceMillis = 0L;
            for (ClientRosterAppend.Row row : append.rows()) {
                Long key = Long.valueOf(row.wireId());
                if (row.wireId() == ownWireId) {
                    String prior = plugin.variantById.put(key, row.variantId());
                    if (row.variantId().equals(prior)) {
                        // the own-hull invariant (a silent own-hull fork must never
                        // happen again): this row is what the server FIELDED for the own seat. The
                        // registry (seeded from the DESCRIPTOR walk) agreeing with it proves
                        // nothing about the LIVE hull — one test run: descriptor grendel
                        // == fielded grendel while the window flew its client-baked (server-
                        // refused) ziggurat_Strike pick for 22349 ticks, this branch logging
                        // "duplicate re-point ... count-ignored" the whole time. Only the live
                        // hull may dedup an own row; a live hull that differs ARMS the L5 alive
                        // re-field (the resolver self-clears a spurious arm) and announces the
                        // fork once (the watch-verdict FALLBACK section lists it). Mount/transfer
                        // tenure on the seat id is left to the standing own-hull invariant
                        // (PredictReconcileCombatPlugin), which re-checks every 2 s.
                        String live = liveOwnHullVariantOrNull();
                        if (live != null && !live.equals(row.variantId())
                                && plugin.ownMountedModule == null && !plugin.xfer.ownTransit()) {
                            plugin.ownRefieldPending = true;
                            ownPickForks++;
                            String fork = "OWN seat " + row.wireId() + " PICK FORK: live hull "
                                    + FallbackAnnounce.sanitize(live, 64) + " != server fielded "
                                    + FallbackAnnounce.sanitize(row.variantId(), 64)
                                    + " (registry already agreed with the row; alive re-field"
                                    + " ARMED; forks=" + ownPickForks + ")";
                            PilotWatchLog.error("predict roster-append: " + fork, null);
                            FallbackAnnounce.announceOnce("own-pick-fork", fork);
                            continue;
                        }
                        rowsKnown++; // replay/duplicate of an already-applied own re-point
                        // The fold's formerly-ONLY silent OWN path — now loud so a
                        // dedup can never masquerade as a lost row again.
                        // variantId is wire-decoded text — printable-scrubbed at write time
                        // (single-line log law; the re-point line below gets the same guard).
                        PilotWatchLog.info("predict roster-append: OWN seat " + row.wireId()
                                + " duplicate re-point "
                                + FallbackAnnounce.sanitize(row.variantId(), 64)
                                + " count-ignored (rowsKnown=" + rowsKnown + ")");
                        continue;
                    }
                    rowsRepointed++;
                    // D-JOINPICK: a registry-only re-point left
                    // the joiner's t=0 descriptor hull flying until its next respawn while the
                    // server (and every other window) simulated the announced variant — arm the
                    // plugin's alive re-field; it compares registry vs live hull next frame.
                    plugin.ownRefieldPending = true;
                    PilotWatchLog.info("predict roster-append: OWN seat " + row.wireId()
                            + " variant re-point " + FallbackAnnounce.sanitize(prior, 64)
                            + " -> " + FallbackAnnounce.sanitize(row.variantId(), 64)
                            + " (registry re-pointed; alive re-field ARMED, else next own"
                            + " respawn fields it; repointed=" + rowsRepointed + ")");
                    continue;
                }
                if (plugin.xfer.suppressMintFor(row.wireId())) {
                    // The OWN transfer pod (and any H1-retired
                    // id) takes the own-seat shape — variant registered so the id stays
                    // engine-resolvable, replica NEVER minted (the local vanilla pod is adopted
                    // instead; other seats' pods mint normally via the S1 branch).
                    plugin.variantById.put(key, row.variantId());
                    rowsKnown++;
                    PilotWatchLog.info("predict roster-append: suppressed-mint id "
                            + row.wireId() + " variant registered, replica not minted"
                            + " (rowsKnown=" + rowsKnown + ")");
                    continue;
                }
                PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(key);
                if (re != null) {
                    String prior = plugin.variantById.get(key);
                    if (re.stationModule || plugin.mmnt.mountedOrPending(row.wireId())
                            || row.variantId().equals(prior)) {
                        // Module rows are round-local client mints — a wire re-point row can
                        // never target one by construction (the server fans seat + wave ids
                        // only); treating it as known-ignore keeps that impossible case inert
                        // (an unbound module row must NEVER reach spawn-on-sight). A MOUNTED
                        // (or mount-pending) seat's row is likewise count-ignored:
                        // the seat id drives an adopted module body, so the
                        // repoint drop/re-mint below must never run against it — the mount
                        // lane owns that id until dismount/death clears it.
                        rowsKnown++;
                        PilotWatchLog.info("predict roster-append: entity " + row.wireId()
                                + " count-ignored ("
                                + (re.stationModule ? "module row"
                                        : plugin.mmnt.mountedOrPending(row.wireId())
                                                ? "mounted seat" : "variant known")
                                + "; rowsKnown=" + rowsKnown + ")");
                        // Rider client|1414-mt (R1) — a
                        // REMOTE mounted seat's HULL re-announce (the wrap's) is count-ignored above;
                        // STASH it on the seat's record for ModuleMountLane.resetRound's registry
                        // write-back (stash only: no registry write, no arm). The mount's own RAPP
                        // row (MMNT then RAPP on one CTRL FIFO, ModuleSeatServeLane) carries the
                        // MODULE variant — not a hull announce, never stashed. A PENDING (not yet
                        // mounted) seat has no record: its announce is lost, as before.
                        ModuleMountLane.MountedSeat mounted = plugin.mountedSeats.get(key);
                        if (mounted != null && !mounted.own
                                && !row.variantId().equals(mounted.moduleVariantId)) {
                            mounted.announcedHullVariantId = row.variantId();
                        }
                        continue;
                    }
                    plugin.variantById.put(key, row.variantId());
                    rowsRepointed++;
                    // A new variant re-arms the spawn-failure
                    // budget — and lifts the budget's own dead mark (an entry that never minted).
                    int[] failed = spawnFailures.remove(re);
                    if (failed != null && failed[0] >= SPAWN_FAILURE_BUDGET && re.ship == null) {
                        re.killed = false;
                    }
                    ShipAPI stale = re.ship;
                    if (stale != null && plugin.xfer.hasStagedBeginFor(row.wireId())) {
                        // this is the
                        // seat's S→cc_pod repoint of a STAGED foreign BEGIN (server order on
                        // one CTRL FIFO: F's row ≺ this repoint ≺ BEGIN; client order intake
                        // ≺ this fold ≺ applyStaged). The bound body is the ALIVE source that
                        // applyRemoteBegin re-homes under F — dropping + re-minting it here
                        // handed the BEGIN's apply a cc_pod replica as F's body (a shuttle
                        // drove the source hull's path on a late SNAP).
                        // Registry re-pointed only; the BEGIN's apply unbinds S this same
                        // frame for the cc_pod re-mint at S's next pose sight.
                        PilotWatchLog.info("predict roster-append: REPOINT entity "
                                + row.wireId() + " variant "
                                + FallbackAnnounce.sanitize(prior, 64) + " -> "
                                + FallbackAnnounce.sanitize(row.variantId(), 64)
                                + " (staged transfer BEGIN: source body KEPT for the"
                                + " re-home under F; repointed=" + rowsRepointed + ")");
                        continue;
                    }
                    if (stale != null) {
                        plugin.respawnRemote(re); // corpse ref drops; wreckage stays (server-mirroring)
                        if (stale.isAlive() && plugin.engine != null) {
                            // Reorder race: the old-variant body was already re-minted LIVE —
                            // no wire feed will ever drive it again; remove or it ghosts.
                            plugin.removeStaleBody(stale, "repoint-stale-remint"); // Evict + remove
                        }
                    }
                    PilotWatchLog.info("predict roster-append: REPOINT entity " + row.wireId()
                            + " variant " + FallbackAnnounce.sanitize(prior, 64)
                            + " -> " + FallbackAnnounce.sanitize(row.variantId(), 64)
                            + (stale != null ? " (stale body dropped)" : " (unbound)")
                            + "; spawn-on-sight re-mints at next pose sight"
                            + " (repointed=" + rowsRepointed + ")");
                    continue;
                }
                if (plugin.remoteEntities.size() >= MAX_ROUND_ENTITIES) {
                    // A full map first sheds its appended
                    // entries dead before sight (ONE walk a drain pass, so a row flood at the cap
                    // never re-walks the map per row); refused only if that freed no room.
                    if (!capRetireWalked) {
                        capRetireWalked = true;
                        retireDeadBeforeSight();
                    }
                    if (plugin.remoteEntities.size() >= MAX_ROUND_ENTITIES) {
                        refuseAtEntityCap(row);
                        continue;
                    }
                }
                int relSide = row.side() == plugin.ownDescSide ? 0 : 1;
                plugin.remoteEntities.put(key, new PredictReconcileCombatPlugin.RemoteEntity(
                        row.wireId(), relSide, row.shipName(), row.crPerMille(),
                        row.carrierWireId(), row.bayIndex(), row.officerOrNull(),
                        row.atRest(), // RAPP v5 bit0 -> no parity burn
                        row.allSkillsCaptain())); // bit1: restored captain = the all-skills seat mint
                plugin.variantById.put(key, row.variantId());
                plugin.remoteUnbound++;
                plugin.remoteBindCompleteLogged = false;
                rowsApplied++;
                if (MatchMissionBuilder.isStationVariant(row.variantId())) {
                    // A wire-born MODULAR parent (/spawnfoe Survey
                    // Mothership) — spawn-on-sight mints base + engine-built children at first
                    // pose; the adopt-bind lane claims the children to the server's mint-time
                    // band root+1..root+N (never RAPP rows), so no double bodies.
                    plugin.stations.acceptAppendedRow(row.wireId(), row.variantId());
                }
                PilotWatchLog.info("predict roster-append: entity " + row.wireId()
                        + " absSide=" + row.side() + " -> side " + relSide
                        + " variant=" + FallbackAnnounce.sanitize(row.variantId(), 64)
                        + " name=" + FallbackAnnounce.sanitize(row.shipName(), 64)
                        + " cr=" + row.crPerMille()
                        + (row.hasCarrier() ? " carrier=" + row.carrierWireId()
                                + " bay=" + row.bayIndex() : "")
                        + (row.officerOrNull() != null ? " officer="
                                + FallbackAnnounce.sanitize(row.officerOrNull().fullName(), 32)
                                        .replace(' ', '_')
                                + "/L" + row.officerOrNull().level
                                + "/" + row.officerOrNull().skills.size() : "")
                        + (row.atRest() ? " at_rest=1" : "")
                        + (row.allSkillsCaptain() ? " all_skills=1" : "")
                        + " APPENDED unbound; spawn-on-sight mints at first pose"
                        + " (applied=" + rowsApplied
                        + " knownIgnored=" + rowsKnown + ")");
            }
        }
    }
}
