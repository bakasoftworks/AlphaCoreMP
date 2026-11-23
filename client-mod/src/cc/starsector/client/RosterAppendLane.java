package cc.starsector.client;

import cc.starsector.client.net.ClientMatchReset;
import cc.starsector.client.net.ClientRosterAppend;
import cc.starsector.client.net.PredictReconcileDatagramSession;
import cc.starsector.client.net.PredictSession;
import cc.starsector.server.match.OfficerRosterCodec;

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
 * idiom — gameplay-critical, bounded in frames and in estimated decoded bytes and loud past
 * either bound: a lost append re-fields the ghost match); {@link #drain} folds on the render
 * thread; {@link #clearQueue} at the match-reset boundary drops the frames older than the consumed
 * reset's round epoch (the OLD round's); {@link #onReAdmitted}, on the net thread, drops the frames
 * a re-admit's replay delivers again.
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
    /** FIELDED rows count-ignored as an equal variant on a
     *  known id (a respawn in the same ship), split from {@link #rowsKnown} so that meter keeps meaning
     *  the wrap re-announces and duplicates; never a per-row text ({@link #ROW_LOG_MAX}). Render thread. */
    private int rowsFieldedKnown;
    /** B1b — known-id rows whose variant differed: seat re-points applied (own + remote). */
    private int rowsRepointed;

    // The round-epoch gate state (see the class doc).
    /** Fail-open bound for a deferred head (the pre-publish window is ~7 ms; a HOLD never drains). */
    static final long RAPP_EPOCH_FAILOPEN_MS = 5_000L;
    /** The highest ctrl round epoch whose reset the plugin consumed (render thread). */
    private int resetsRetired;
    /** Deferral episode start ({@link #clock} ms); 0 = not deferring (render thread). */
    private long deferredSinceMillis;
    /** Rows currently queued — guarded by {@link #queue} (net-thread add and re-admit drop,
     *  render-thread take). */
    private int queuedRows;
    /** The queued frames' summed {@link #decodedCostEstimate},
     *  guarded by {@link #queue} beside {@link #queuedRows}: the net thread charges a frame at intake and
     *  every removal (the drain's poll, the reset cut, the reset fold, the re-admit drop) returns that
     *  frame's charge. Range 0 .. {@link #MAX_QUEUED_DECODED_BYTES}. */
    private long queuedDecodedBytes;
    /** The queue PREFIX already counted into the deferral meters (frames / rows; render thread,
     *  updated under the monitor). Frames only append at the tail and leave from the head, the
     *  reset cut or the re-admit drop (which empties the queue and zeroes this prefix), so the counted
     *  frames stay a prefix: each frame counts ONCE, at its first stop. */
    private int deferredCountedFrames;
    private int deferredCountedRows;
    /** Meters (render thread; the debrief line reads them there). Every frame queued behind a
     *  stopped head is deferred with it — a wrap's carried-pick + growth frames both count. */
    private int rowsDeferred;
    private int framesDeferred;
    private int rowsDroppedAtReset;
    /** old-epoch SEAT rows folded at a reset
     *  ({@link #foldSeatRowsBeforeReset}; every row it pulled, a record-removal row included, though a
     *  removal is written into the fleet stamps only, never the registry), and the frames that left the
     *  queue whole (all-seat frames). */
    private int rowsFoldedAtReset;
    private int framesFoldedAtReset;
    private int epochFailOpens;
    /** The key-work bound's meters ({@link #KEY_WORK_PER_PASS}; lifetime,
     *  never reset): the drain passes it stopped before their entry frames ran out, and the reset fold's
     *  fleet-record folds it skipped. */
    private long keyWorkStops;
    private long keyWorkSkips;

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
     * The intake queue's
     * frame bound. The largest lawful backlog is a late joiner's whole catch-up replay landing before
     * the render thread drains. Sizing, a three-hour endless round: the append-log's own frames come
     * to about 3,500 (the catch-up cursor's own sizing), and every fielding now sends its own 1-row
     * frame, 30 seats x 1 life a minute x 180 min = 5,400 more: about 8,900 frames, past the old
     * 8,192. 16,384 holds that with about 1.8x to spare.
     * <p>What it costs, in measured retained sizes: a queued frame holds its decoded rows.
     * A lawful 1-row fielded frame retains 237 B (3.7 MiB at this bound) and a lawful officered wave
     * frame (3 rows x 10 skills) 5,582 B. The decoder's true ceiling is NOT 16 rows: a 16-row frame of
     * 32-skill officers is a 2,705-byte body, over the reader's 1,144-byte body cap
     * ({@link cc.starsector.client.net.ClientRosterAppendReader}), so it is refused undecoded. The
     * ceiling that decodes is 7 rows carrying 214 one-char officer skills in exactly 1,144 body bytes
     * (1,164 on the wire): 27,214 B retained, so this bound ALONE lets a host that keeps the queue
     * full pin 425 MiB of the kit's 2,048 MB heap. {@link #MAX_QUEUED_DECODED_BYTES} is the second
     * bound that holds that pin down; this one stays as the count bound.
     * <p>Past it the INCOMING frame is refused (counted, one error row a
     * round): refusing the newest keeps the queued frames an exact prefix of the server's log
     * order (the order the catch-up cursor delivers) and leaves the deferral meters' counted
     * prefix intact, while dropping the oldest would cut the round's earliest rows (a later
     * re-point of a dropped id would then fold as a fresh append) and could pull a deferred head
     * out from under the round-epoch gate.
     */
    static final int MAX_QUEUED_FRAMES = 16_384;

    /**
     * The key work one drain pass may charge, in the unit of
     * {@link FleetStamps#keyWorkPerRow} (one roster row walked or one key entry built). WHY: every fielded or
     * removal row's fleet fold builds one whole-side key on the render thread (every roster row walked, the side's
     * non-seat rows expanded by count, then the record, joined, encoded and checksummed), so the frame bound above
     * alone let a host freeze the render thread with a 4,096-ship document and a queue full of fielded rows.
     * Sizing: one maximal 4,096-ship build, 4,096 + 4,092 + 256 = 8,444, fits; the 32-seat fortress charges about
     * 300 a row, so a lawful pass, paced at 64 catch-up frames a tick, never reaches it.
     * <p>The granularity is a FRAME: the drain polls and runs whole frames (a stop inside a frame would need a
     * resume cursor), and a pass's first frame always folds. WORST CASE per pass: at most this many units, except a
     * lone first frame, at most 16 rows x 8,444 = 135,104 units (16 x 4,348 = 69,568 key entries) at a 4,096-ship
     * document. {@link #foldSeatRowsBeforeReset} takes the same budget per call, by row.
     */
    static final int KEY_WORK_PER_PASS = 65_536;

    /**
     * The intake queue's second bound, on the ESTIMATED decoded
     * size of the queued frames ({@link #decodedCostEstimate}), counted under the queue monitor beside
     * {@link #queuedRows}. WHY: decoding inflates the wire about 24 times, and no frame count serves both
     * sides: the decoder's ceiling frame retains 27,214 B, so {@link #MAX_QUEUED_FRAMES} of them pin
     * 425 MiB of a 2,048 MB heap, while holding that pin to 64 MiB by count alone would allow at most
     * 2,466 frames, far below the lawful 8,900-frame catch-up. A row bound would not do it either
     * (65,536 rows of 32 skills is about 243 MiB).
     * <p>Sizing: the estimate sits above the measured retained size of every shape (a
     * 1-row fielded frame 522 vs 237 B, a 16-row wave 4,288 vs 3,077 B, an officered wave 6,418 vs
     * 5,582 B, the ceiling 32,604 vs 27,214 B). The lawful three-hour worst case, every append-log
     * frame an officered wave, estimates at 3,500 x 6,418 + 5,400 x 522 = 25.3 MB, 2.65x under this
     * bound; a host keeping the queue full now pins at most about 56 MB real (2.7% of the heap).
     * <p>A frame whose charge would carry the queued total past this bound is refused through the SAME
     * refuse-newest path and meters as {@link #MAX_QUEUED_FRAMES} (the newest refused keeps the queue an
     * exact prefix of the server's log order), plus its own count {@link #framesRefusedAtByteBound}
     * (the round-close row's {@code byte_bound}); the charge returns when the frame leaves the queue.
     */
    static final long MAX_QUEUED_DECODED_BYTES = 67_108_864L;

    /**
     * One frame's charge against
     * {@link #MAX_QUEUED_DECODED_BYTES}, in bytes: 256 + the sum over rows of
     * (192 + 2 x (variant chars + ship-name chars)) + the sum over officers of (512 + 128 x skills).
     * An over-estimate of the retained size by design (the bound's sizing). A pure function of the
     * immutable decoded frame, so the bytes a removal returns are exactly the bytes the intake charged.
     * Range: 256 and up, a few tens of KB at most for a frame the reader decodes; never negative.
     */
    static long decodedCostEstimate(ClientRosterAppend frame) {
        long cost = 256L;
        for (ClientRosterAppend.Row row : frame.rows()) {
            cost += 192L + 2L * (charsOf(row.variantId()) + charsOf(row.shipName()));
            OfficerRosterCodec.OfficerSpec officer = row.officerOrNull();
            if (officer != null) {
                cost += 512L + 128L * (officer.skills == null ? 0 : officer.skills.size());
            }
        }
        return cost;
    }

    private static long charsOf(String s) {
        return s == null ? 0L : s.length();
    }

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

    /**
     * The per-round cap on this lane's per-ROW log rows (the fold's nine row arms) and, counted apart, on
     * the intake's per-FRAME {@code RECV} rows (the number is the despawn
     * rows' own, {@link EdgeEventLane#DESPAWN_LOG_MAX}, reused: the same per-entity lifecycle rows, a
     * round). WHY: every folded row wrote one row into the sidelog's BLOCKING 4,096-slot ring
     * ({@link PilotWatchLog}: a full ring blocks the caller), so a host streaming current-round RAPP
     * frames made the render thread wait on disk for every row it folded, and the flood restarted the
     * size-capped sidelog over the session's real rows. Past the cap a row is COUNTED, never built or
     * written: ONE census row marks the cut ({@link #rowLog}) and the round-close row carries the
     * unlogged counts ({@link #onRoundReset}); every fold meter keeps counting. The round's FIRST own
     * pick-fork error row is exempt (a silent own-hull fork must never happen again).
     * The spawn-failure rows ({@link #onSpawnFailed}) take the same cap, the round's first of each kind exempt.
     * A lawful round past 4,096 appended rows (the standing fortress mints ~593 hulls an hour) loses
     * only the per-row text. A fielded row the fold count-ignores (an equal variant on a known id: every
     * respawn in the same ship, ~5,400 a three-hour round of 30 seats) writes no per-row text at all,
     * below the cap too: it moves {@link #rowsFieldedKnown} only, which the round-close census row prints.
     */
    static final int ROW_LOG_MAX = EdgeEventLane.DESPAWN_LOG_MAX;
    /** {@link #ROW_LOG_MAX}'s fold-side meters (render thread; {@link #onRoundReset} prints + clears). */
    private int rowLogLines;
    private int rowsUnlogged;
    /** Own pick-fork rows this round, logged or not (the first one always logs; render thread). */
    private int forkRowsThisRound;
    /** {@link #onSpawnFailed}'s first-failure rows this round by kind, logged or not (the first of each
     *  kind always logs; render thread). */
    private int spawnThrowRowsThisRound;
    private int spawnNullRowsThisRound;
    /** {@link #ROW_LOG_MAX}'s intake-side meters (guarded by {@link #queue}: net-thread write). */
    private int recvLogLines;
    private int recvUnlogged;

    /** The per-round bound meters ({@link #onRoundReset}
     *  clears them). Fresh rows refused at {@link #MAX_ROUND_ENTITIES} and entries whose spawn
     *  budget ran out (render thread); frames/rows refused at {@link #MAX_QUEUED_FRAMES} (guarded
     *  by {@link #queue}: net-thread write). The first refusal of a round is the count's 0 -> 1. */
    private int rowsRefusedAtCap;
    private int spawnBudgetsSpent;
    private int framesRefusedAtIntake;
    private int rowsRefusedAtIntake;
    /** Of {@link #framesRefusedAtIntake}, the frames refused at
     *  {@link #MAX_QUEUED_DECODED_BYTES} with the frame bound not reached (guarded by {@link #queue}:
     *  net-thread write; the round-close row prints it as {@code byte_bound}, then clears it). */
    private int framesRefusedAtByteBound;
    /** The re-admit edge state ({@link #onReAdmitted}), all guarded
     *  by {@link #queue}. {@code admitGenSeen} = the newest admission generation a frame was taken in under
     *  (net thread; >= 0); {@code reAdmitEdges} = edges taken (net thread) and {@code reAdmitEdgesFolded} =
     *  edges whose render half ran ({@link #foldReAdmitEdge}); the frames / rows an edge dropped (lifetime
     *  counts, net thread). */
    private long admitGenSeen;
    private int reAdmitEdges;
    private int reAdmitEdgesFolded;
    private int framesDroppedAtReAdmit;
    private int rowsDroppedAtReAdmit;
    /** Appended entries the entity cap retired as dead
     *  before sight this round ({@link #retireDeadBeforeSight}); render thread, cleared by
     *  {@link #onRoundReset} after its summary row prints it. */
    private int entriesRetiredDead;
    /** At most ONE {@link #retireDeadBeforeSight} walk a
     *  drain pass (one pass a render frame) — re-armed at the start of each {@link #drain}; the
     *  command-transfer re-home, later in the same render frame, shares it through
     *  {@link #admitFresh}, so a mint flood at the cap never re-walks the map per id. */
    private boolean capRetireWalked;
    /** Failed spawn attempts per entry object (identity: a re-appended id is a fresh budget):
     *  {failures, throws}; render thread, cleared at the round reset. */
    private final IdentityHashMap<PredictReconcileCombatPlugin.RemoteEntity, int[]> spawnFailures =
            new IdentityHashMap<PredictReconcileCombatPlugin.RemoteEntity, int[]>();

    /** Valve clock — package-private and settable (the class is final): the smoke injects it. */
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** The admission generation the frame being taken in arrived
     *  under, read by {@link #enqueue} on the net thread (>= 0, never decreasing on one session):
     *  settable like {@link #clock}, a smoke installs its own. */
    java.util.function.LongSupplier admitGeneration = this::liveAdmitGeneration;
    /** The fleet-record fold every fielded row feeds, at
     *  both fold sites in log order (the app-wide instance the fleet-context mint reads; settable like
     *  {@link #clock}: a smoke installs its own). Its unclosed timer runs on {@link #clock}. */
    FleetStamps fleetStamps = FleetStamps.shared();
    /** The census text the last round-close row printed, first the one at the lane's build (a row is
     *  written only when the census moved). */
    private String fleetCensusLogged = fleetStamps.meterText();
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
    /** Own rows this round that re-pointed the live hull to
     *  the server's realized loadout of the same goal variant (each armed the alive re-field; not a
     *  fork). Render thread; printed and cleared by {@link #onRoundReset}'s summary row. */
    private int ownRealizedRefields;

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
     *  refused (one compare under the monitor; the round's first refusal logs, later ones count).
     *  So is a frame whose {@link #decodedCostEstimate} would carry
     *  the queued total past {@link #MAX_QUEUED_DECODED_BYTES} (the same path and meters, plus
     *  {@link #framesRefusedAtByteBound}); a frame taken under a newer admission first runs the re-admit
     *  edge ({@link #onReAdmitted}), so the stale queue is gone before the replay's first frame queues. */
    void enqueue(ClientRosterAppend append) {
        onReAdmitted(admitGeneration.getAsLong());
        long cost = decodedCostEstimate(append); // outside the monitor: a pure read of the immutable frame
        int refused = 0;
        int refusedRows = 0;
        int queuedAtRefusal = 0;
        boolean byteBound = false;
        int recvRow = 0; // 1 = the frame's RECV row, 2 = the one census row past ROW_LOG_MAX, 0 = counted only
        synchronized (queue) {
            framesReceived++;
            boolean framesFull = queue.size() >= MAX_QUEUED_FRAMES;
            if (framesFull || queuedDecodedBytes + cost > MAX_QUEUED_DECODED_BYTES) {
                refused = ++framesRefusedAtIntake;
                rowsRefusedAtIntake += append.rows().size();
                refusedRows = rowsRefusedAtIntake;
                queuedAtRefusal = queue.size();
                if (!framesFull) {
                    framesRefusedAtByteBound++;
                    byteBound = true;
                }
            } else {
                queue.addLast(append);
                queuedRows += append.rows().size();
                queuedDecodedBytes += cost;
                if (recvLogLines < ROW_LOG_MAX) {
                    recvLogLines++;
                    recvRow = 1;
                } else if (recvUnlogged++ == 0) {
                    recvRow = 2;
                }
            }
        }
        if (refused > 0) {
            if (refused == 1) {
                String bound = byteBound
                        ? "intake queue full (" + MAX_QUEUED_DECODED_BYTES + " estimated decoded bytes, frames="
                                + queuedAtRefusal + "): refused frame cost=" + cost + " rows="
                        : "intake queue full (" + MAX_QUEUED_FRAMES + " frames): refused frame rows=";
                String detail = bound
                        + append.rows().size() + " epoch=" + append.roundEpoch()
                        + " (later refusals this round are counted silently; rows refused=" + refusedRows + ")";
                PilotWatchLog.error("predict roster-append: " + detail, null);
                FallbackAnnounce.announceOnce("rapp-intake-cap", detail);
            }
            return;
        }
        if (recvRow == 1) {
            PilotWatchLog.info("predict roster-append: RECV frame rows=" + append.rows().size()
                    + " epoch=" + append.roundEpoch() + " (received=" + framesReceived + ")");
        } else if (recvRow == 2) {
            PilotWatchLog.info("predict roster-append: RECV row log cap reached (" + ROW_LOG_MAX
                    + " frames this round): later RECV rows are counted, not logged (received="
                    + framesReceived + "; the round-close row carries the count)");
        }
    }

    /**
     * The re-admit edge, the lane's half. The server treats a
     * re-admitted session as a late joiner and replays the round's whole append log from the round's
     * start, so every frame still queued from before the edge arrives again in that replay: they are
     * dropped here (counted in {@link #framesDroppedAtReAdmit}, one info row an edge), else each would
     * fold twice.
     * <p>Caller: {@link #enqueue}, with the generation the frame being taken in arrived under
     * ({@link #admitGeneration}: the session's re-admit count read when its CTRL payload was taken,
     * so a frame taken before the edge but handed over after it still reads the old generation, queues,
     * and is dropped by the first frame of the new one). The edge therefore reaches the lane at the
     * replay's first frame, before that frame queues, so before any replayed frame drains. A
     * generation at or below the newest seen is no edge (a no-op), so the drop runs ONCE an edge.
     * <p>Threads: net thread. The drop and the edge count happen under the {@link #queue} monitor, the
     * one every drain pass takes to peek and again to poll, so a pass in flight either polled its head
     * before the drop (that frame was taken before the edge and folds whole; the pass's next peek
     * then sees the edge) or finds at its poll that the head it peeked is gone (it folds nothing and
     * peeks again). A frame the replay delivers after the edge queues after the drop and is never
     * dropped by it. The render thread's half ({@link #foldReAdmitEdge}) runs at the first peek after
     * the edge, before any frame taken after the edge folds.
     */
    void onReAdmitted(long generation) {
        int frames;
        int rows;
        int framesTotal;
        int edges;
        synchronized (queue) {
            if (generation <= admitGenSeen) {
                return;
            }
            admitGenSeen = generation;
            frames = queue.size();
            rows = queuedRows;
            queue.clear();
            queuedRows = 0;
            queuedDecodedBytes = 0L;
            deferredCountedFrames = 0;
            deferredCountedRows = 0;
            framesDroppedAtReAdmit += frames;
            rowsDroppedAtReAdmit += rows;
            framesTotal = framesDroppedAtReAdmit;
            edges = ++reAdmitEdges;
        }
        PilotWatchLog.info("predict roster-append: re-admit edge (generation " + generation
                + "): dropped queued frames=" + frames + " rows=" + rows
                + ", the server's replay delivers them again (edges=" + edges + " dropped_frames_total="
                + framesTotal + ")");
    }

    /**
     * The render thread's half of a re-admit edge
     * ({@link #onReAdmitted}), run by the first drain peek or reset fold that sees the edge, outside the
     * monitor and between frames: every frame folded before it was taken before the edge, and no frame
     * taken after the edge has folded yet. A deferred head left with the dropped queue, so its deferral
     * episode ends here. This is where the fleet fold restarts, so the replay that follows rebuilds the
     * round's record from its start instead of folding each write a second time.
     */
    private void foldReAdmitEdge() {
        deferredSinceMillis = 0L;
        // (main's fold line; render thread,
        // between frames, so no frame's rows straddle the restart)
        fleetStamps.restartForReplay();
    }

    /** {@link #admitGeneration}'s default, net thread: the
     *  datagram session's per-take stamp ({@link PredictReconcileDatagramSession#ctrlTakeReAdmits}),
     *  else the session's re-admit count; 0 with no session. Range >= 0. */
    private long liveAdmitGeneration() {
        PredictSession s = plugin.session;
        if (s instanceof PredictReconcileDatagramSession) {
            return ((PredictReconcileDatagramSession) s).ctrlTakeReAdmits();
        }
        return s == null ? 0L : s.reAdmits();
    }

    /** The re-admit meters (the smoke's reads): frames / rows dropped at an edge, edges whose render
     *  half ran. */
    int framesDroppedAtReAdmit() {
        synchronized (queue) {
            return framesDroppedAtReAdmit;
        }
    }

    int rowsDroppedAtReAdmit() {
        synchronized (queue) {
            return rowsDroppedAtReAdmit;
        }
    }

    int reAdmitEdgesFolded() {
        synchronized (queue) {
            return reAdmitEdgesFolded;
        }
    }

    /** {@link #MAX_QUEUED_DECODED_BYTES}'s meters (the smoke's reads): the queued charge, and this
     *  round's frames refused at the byte bound. */
    long queuedDecodedBytes() {
        synchronized (queue) {
            return queuedDecodedBytes;
        }
    }

    int framesRefusedAtByteBound() {
        synchronized (queue) {
            return framesRefusedAtByteBound;
        }
    }

    /**
     * {@link #ROW_LOG_MAX}'s gate for ONE per-row log row of the fold:
     * true = the caller builds and writes its row; false = the row is counted unlogged and the caller
     * builds nothing. The first row past the cap writes the ONE census row in its place. Render thread.
     */
    private boolean rowLog() {
        if (rowLogLines < ROW_LOG_MAX) {
            rowLogLines++;
            return true;
        }
        if (rowsUnlogged++ == 0) {
            PilotWatchLog.info("predict roster-append: row log cap reached (" + ROW_LOG_MAX
                    + " rows this round): later per-row rows are counted, not logged (applied="
                    + rowsApplied + " knownIgnored=" + rowsKnown + " rows_fielded_known=" + rowsFieldedKnown
                    + " repointed=" + rowsRepointed + "; the round-close row carries the count)");
        }
        return false;
    }

    /** {@link #ROW_LOG_MAX}'s meters (the smoke's reads): fold rows / RECV rows counted unlogged this round. */
    int rowsUnlogged() {
        return rowsUnlogged;
    }

    int recvUnlogged() {
        synchronized (queue) {
            return recvUnlogged;
        }
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
                queuedDecodedBytes -= decodedCostEstimate(frame);
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
     *  for a seat wins, as in {@link #drain}); a record-removal row is folded into the fleet stamps at
     *  its log position and written nowhere else. None of the drain's arms run — no re-mint, no body
     *  removal, no own re-field latch: the rebuild sweeps every body, fields the own hull from the
     *  registry and re-mints the other seats on sight from it. An all-seat frame leaves the queue; a
     *  MIXED frame stays with only its non-seat rows (same epoch), so the cut drops and counts only
     *  those (a reset-epoch frame's non-seat rows are kept by the cut and drain after the retire).
     *  Render thread; one info row when a row folded. Returns the rows folded.
     *  <p>KEY WORK. The fleet-record folds of one call charge at most
     *  {@link #KEY_WORK_PER_PASS} (the drain's per-pass budget, in {@link FleetStamps#keyWorkPerRow} units) by row
     *  in log order, the first always folding; a row past it has its fleet fold SKIPPED (counted
     *  {@link #keyWorkSkips}, one ERROR row each time the count crosses a power of two), never its registry write.
     *  A skipped row at the reset's own epoch leaves that round's record short, which the stamps then count as a
     *  mismatch: hostile-only, a lawful wrap is a few rows a seat. */
    int foldSeatRowsBeforeReset(int resetEpoch, LongPredicate isSeatWire) {
        List<ClientRosterAppend.Row> folded = null;
        List<ClientRosterAppend.Row> recordRows = null; // the folded fleet-record rows (writes + removals), log order
        List<Integer> recordEpochs = null; // each one's frame epoch
        int foldedFrames = 0;
        boolean headLeft = false;
        boolean edge;
        synchronized (queue) {
            // A re-admit edge folds before this fold's rows
            edge = reAdmitEdges != reAdmitEdgesFolded;
            reAdmitEdgesFolded = reAdmitEdges;
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
                            if (row.fleetFielded() || row.fleetVacated()) {
                                // A removal joins the writes' ONE
                                // log-ordered list, never a second pass after it: a write, a removal and a
                                // write of one id fold present, gone, present
                                if (recordRows == null) {
                                    recordRows = new ArrayList<ClientRosterAppend.Row>();
                                    recordEpochs = new ArrayList<Integer>();
                                }
                                recordRows.add(row);
                                recordEpochs.add(Integer.valueOf(frame.roundEpoch()));
                            }
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
                    queuedDecodedBytes -= decodedCostEstimate(frame); // the whole frame's charge returns ...
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
                        ClientRosterAppend kept = new ClientRosterAppend(rest, frame.roundEpoch());
                        queuedDecodedBytes += decodedCostEstimate(kept); // ... and its kept rows are charged again
                        rebuilt.add(kept);
                    }
                }
                pos++;
            }
            if (folded != null) {
                queue.clear();
                queue.addAll(rebuilt);
            }
        }
        if (edge) {
            foldReAdmitEdge();
        }
        // The seat rows pulled here left the queue, so this is
        // their ONLY fleet-record fold (drain never sees them again), outside the queue monitor. Then the
        // reset being applied drops the folds of the rounds below it (by epoch, never per life); a bare
        // life reset names no epoch and drops nothing.
        if (recordRows != null) {
            // The key-work bound, by row: a row whose fleet fold would carry
            // this call past the budget has that fold SKIPPED and counted, never deferred (the reset applied next
            // drops every epoch below it, so a deferred row would fold into a dropped epoch); the first row always
            // folds. A skipped row still left the queue (above) and still takes its registry write (below).
            long work = 0L;
            long skipsBefore = keyWorkSkips;
            for (int i = 0; i < recordRows.size(); i++) {
                ClientRosterAppend.Row row = recordRows.get(i);
                int epoch = recordEpochs.get(i).intValue();
                int w = fleetStamps.keyWorkPerRow(row.side());
                if (i > 0 && work + w > KEY_WORK_PER_PASS) {
                    keyWorkSkips++;
                    continue;
                }
                work += w;
                // One pass in log order, each row by its kind
                if (row.fleetVacated()) {
                    vacateFleetRow(epoch, row);
                } else {
                    foldFleetRow(epoch, row);
                }
            }
            if (Long.highestOneBit(keyWorkSkips) > skipsBefore) {
                PilotWatchLog.error("predict roster-append: KEY WORK bound skipped reset folds rows="
                        + (keyWorkSkips - skipsBefore) + " (key_work_skips=" + keyWorkSkips + ")", null);
            }
        }
        if (resetEpoch != Integer.MAX_VALUE) {
            fleetStamps.epochApplied(resetEpoch);
            logFleetDiagnostics();
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
            if (!row.fleetVacated()) { // a removal announces no hull: it registers nothing
                plugin.variantById.put(Long.valueOf(row.wireId()), row.variantId());
            }
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
        fleetStamps.epochApplied(reset.roundEpoch()); // the same epoch the wrap fold applied = a no-op
        logFleetDiagnostics();
    }

    /** One fielded row into the fleet record, in log order,
     *  under its frame's epoch; the fold's diagnostic rows are written at once (so they sit in the log
     *  beside the row that caused them). Never throws (the fold refuses a malformed row). */
    private void foldFleetRow(int epoch, ClientRosterAppend.Row row) {
        fleetStamps.fold(epoch, row.wireId(), row.side(), row.variantId(), row.fleetRoundOpen(),
                row.fleetKeyDigest());
        logFleetDiagnostics();
    }

    /** One record-removal row (flags 0x10: its wire id leaves the
     *  round's record; no hull is announced) into the fleet stamps, in log order, under its frame's epoch,
     *  its diagnostic rows written at once as {@link #foldFleetRow} does; it costs what that fold costs.
     *  Every caller then leaves the row: a removal re-points, mints and registers nothing, and a body still
     *  on screen under that id is left as it is for its own despawn (nothing waits on the removal). Never
     *  throws: the stamps refuse a malformed id or side as {@code bad_row} and count every outcome. */
    private void vacateFleetRow(int epoch, ClientRosterAppend.Row row) {
        fleetStamps.vacate(epoch, row.wireId(), row.side(), row.fleetKeyDigest());
        logFleetDiagnostics();
    }

    /** The fleet fold's pending diagnostic rows (bounded tokens
     *  only, capped per kind by the fold) as ERROR rows under the lane's prefix. */
    private void logFleetDiagnostics() {
        for (String row : fleetStamps.takeDiagnostics()) {
            PilotWatchLog.error("predict roster-append: " + row, null);
        }
    }

    /** The plugin bind — the session is app-scoped and
     *  outlives a mission while this lane is per plugin, so the retired count starts at the
     *  session's epoch (unseeded, every frame of a re-picked mission would defer to the valve). */
    void seedFromSession(PredictSession s) {
        resetsRetired = s.ctrlRoundEpoch();
        fleetStamps.epochApplied(resetsRetired); // the fleet fold's round in play starts at the session's epoch
        synchronized (queue) {
            // The re-admit edge's baseline is the admission in play at
            // the bind; a re-admit before it is no edge for this lane (nothing of this lane was queued or folded).
            admitGenSeen = Math.max(admitGenSeen, s.reAdmits());
        }
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
        fleetStamps.noteFailOpen(head.roundEpoch()); // the stamps adopt it too, marked: a mint with no stamp then counts failopen
        logFleetDiagnostics(); // the round before ends here: an unclosed side of it is spoken
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

    /** The key-work bound's meters ({@link #KEY_WORK_PER_PASS}). */
    long keyWorkStops() {
        return keyWorkStops;
    }

    long keyWorkSkips() {
        return keyWorkSkips;
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

    int rowsFieldedKnown() {
        return rowsFieldedKnown;
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

    /**
     * The entity cap's ONE admission gate for a fresh
     * roster id, shared by the roster-append fold ({@link #drain}) and the command-transfer
     * re-home's row-less mint, so neither path can grow {@code remoteEntities} past
     * {@link #MAX_ROUND_ENTITIES}. A full map first sheds its appended entries dead before sight
     * (ONE walk a drain pass, {@link #capRetireWalked}); the id is refused only if that freed no
     * room — counted, the round's first refusal logs one row ({@link #refuseAtEntityCap}). A
     * refusal stores nothing; the caller decides what its refused id leaves behind. {@code wireId}
     * is the signed-long u64 wire id; {@code variant} may be null (logged sanitized). Render thread.
     *
     * @return true = room for one more entry (the caller mints it), false = refused at the cap
     */
    boolean admitFresh(long wireId, String variant) {
        if (plugin.remoteEntities.size() < MAX_ROUND_ENTITIES) {
            return true;
        }
        if (!capRetireWalked) {
            capRetireWalked = true;
            retireDeadBeforeSight();
        }
        if (plugin.remoteEntities.size() < MAX_ROUND_ENTITIES) {
            return true;
        }
        refuseAtEntityCap(wireId, variant);
        return false;
    }

    /** {@code re} left the registry through the remote
     *  reap sweep — its spawn-failure count goes with it (the retire walk's own remove), so a
     *  reaped entry's budget never outlives the entry. One isEmpty read on the common frame.
     *  Render thread. */
    void onReaped(PredictReconcileCombatPlugin.RemoteEntity re) {
        if (!spawnFailures.isEmpty()) {
            spawnFailures.remove(re);
        }
    }

    /** A fresh wire id met a full roster map
     *  ({@link #MAX_ROUND_ENTITIES}) that {@link #retireDeadBeforeSight} could not thin — refused,
     *  counted; the round's first refusal logs ONE error row (and the one-shot verdict announce,
     *  both naming the round's retire count), later ones only count. Render thread. */
    private void refuseAtEntityCap(long wireId, String variant) {
        if (++rowsRefusedAtCap != 1) {
            return;
        }
        String detail = "entity cap (" + MAX_ROUND_ENTITIES + " roster entries): refused fresh id "
                + wireId + " variant=" + FallbackAnnounce.sanitize(variant, 64)
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
                plugin.despawnCorpselessByEntity.remove(key);
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
        // ROW_LOG_MAX: these rows are per ENTRY, and a variant re-point re-arms
        // an entry's budget (drain's REPOINT arm), so a host re-pointing entries whose mint fails got a
        // row (a throw: a stack trace) an entry a render pass. Each takes the lane's row cap; the
        // round's FIRST row of each kind is exempt (a real failure is never silent, the first throw
        // keeps its stack trace): three rows a round at most past the cap.
        if (failure != null && throwsSeen == 1) {
            if (spawnThrowRowsThisRound++ == 0 || rowLog()) {
                PilotWatchLog.error("predict bots bind: entity " + re.entityId + " SPAWN FAILED variant="
                        + FallbackAnnounce.sanitize(variantId, 64)
                        + " -> retry next frame (failure " + failures + "/" + SPAWN_FAILURE_BUDGET
                        + "; later failures counted silently)", failure);
            }
        } else if (failure == null && failures == 1) {
            if (spawnNullRowsThisRound++ == 0 || rowLog()) {
                PilotWatchLog.info("predict bots bind: entity " + re.entityId + " " + spawnCall
                        + " returned NULL variant=" + FallbackAnnounce.sanitize(variantId, 64)
                        + " -> retry next frame (failure 1/"
                        + SPAWN_FAILURE_BUDGET + "; later failures counted silently)");
            }
        }
        if (failures < SPAWN_FAILURE_BUDGET) {
            return;
        }
        re.killed = true;
        re.deathTick = simTick;
        if (++spawnBudgetsSpent == 1 || rowLog()) {
            PilotWatchLog.error("predict bots bind: entity " + re.entityId + " SPAWN BUDGET SPENT after "
                    + failures + " failed attempts (throws=" + throwsSeen + ", last="
                    + (failure != null ? failure.getClass().getSimpleName() : "NULL") + ") variant="
                    + FallbackAnnounce.sanitize(variantId, 64) + " -> dead for the round, stays unbound in the census until the entity cap retires it (spent=" + spawnBudgetsSpent
                    + ")", null);
        }
    }

    /**
     * A mint REFUSED before any engine call; {@code reason} is
     * the caller's bounded constant ({@code UNKNOWN_WING_ID}: a roster id that only looks like a wing,
     * which the engine's member build throws for). No retry can succeed, so the entry's whole
     * {@link #SPAWN_FAILURE_BUDGET} is spent AT ONCE: dead for the round ({@code killed}, the walk's skip
     * flag), counted in {@link #spawnBudgetsSpent}, ONE row (the round's first spent budget exempt, later
     * ones under {@link #rowLog()}). A variant re-point re-arms it like any spent budget (the drain's
     * re-point arm lifts the dead mark of an entry that never minted), so a hostile re-point costs at
     * most one row and no mint. A second call for a spent entry writes nothing. Render thread.
     */
    void onSpawnRefused(PredictReconcileCombatPlugin.RemoteEntity re, String variantId, String reason,
            long simTick) {
        int[] counts = spawnFailures.get(re);
        if (counts == null) {
            counts = new int[2];
            spawnFailures.put(re, counts);
        } else if (counts[0] >= SPAWN_FAILURE_BUDGET) {
            return;
        }
        counts[0] = SPAWN_FAILURE_BUDGET;
        re.killed = true;
        re.deathTick = simTick;
        if (++spawnBudgetsSpent == 1 || rowLog()) {
            PilotWatchLog.error("predict bots bind: entity " + re.entityId + " SPAWN REFUSED (" + reason
                    + ") variant=" + FallbackAnnounce.quote(variantId, 64)
                    + " -> no mint, no retry: the spawn budget is spent at once, dead until a variant"
                    + " re-point (spent=" + spawnBudgetsSpent + ")", null);
        }
    }

    /**
     * The gate of the mint site's wing-id note. An APPENDED
     * entry's note (its SHIP mint takes the engine's placeholder) passes {@link #rowLog()}; a ROSTER
     * entry's note passes once per entry ({@code wingNoteLogged}: a re-point re-mints it, the note does
     * not repeat), then {@link #rowLog()}. True = the caller builds and writes its row. Render thread.
     */
    boolean admitWingNote(PredictReconcileCombatPlugin.RemoteEntity re) {
        if (!re.appended) {
            if (re.wingNoteLogged) {
                return false;
            }
            re.wingNoteLogged = true;
        }
        return rowLog();
    }

    /** What a mint-site variant id names, by the engine's two
     *  spec tables. SHIP = a resolvable ship variant, or no wing reading at all (the SHIP mint takes the
     *  engine's placeholder for an unknown id); KNOWN_WING = a fighter wing spec (the member-first
     *  FIGHTER_WING mint); UNKNOWN_WING = only the {@code _wing} suffix matched: no spec, and the engine's
     *  member build throws for a wing id it cannot resolve. Lives
     *  here, beside the refusal it decides, because the combat plugin is held to its size backstop. */
    enum WingIdKind { SHIP, KNOWN_WING, UNKNOWN_WING }

    /** The {@link WingIdKind} of a mint-site id, by the engine's tables: at most two spec-table reads, at
     *  a mint only (no per-frame work). A resolvable SHIP VARIANT — fighter hulls included
     *  ({@code hoplon_Escort}, {@code attack_swarm_Attack}) — is NOT a wing: the member-first mint takes
     *  each as a wing-less FIGHTER-hull ship (the former {@code v.isFighter()} read fired
     *  the WING warning on every appended fighter while the engine substituted nothing). A read that
     *  throws reads as "not found" (an unknown id may throw instead of returning null: that IS the
     *  answer, not a fault); the verdict is {@link #wingIdKind(String, boolean, boolean)}'s. */
    static WingIdKind wingIdKind(String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return wingIdKind(variantId, false, false);
        }
        boolean shipVariant = false;
        try {
            shipVariant = com.fs.starfarer.api.Global.getSettings().getVariant(variantId) != null;
        } catch (RuntimeException ignore) {
            // unknown id may throw instead of returning null: not a ship variant
        }
        if (shipVariant) {
            return WingIdKind.SHIP; // the wing table is never read for a ship variant
        }
        boolean wingSpec = false;
        try {
            wingSpec = com.fs.starfarer.api.Global.getSettings().getFighterWingSpec(variantId) != null;
        } catch (RuntimeException ignore) {
            // unknown wing id may throw: no wing spec, the suffix law decides
        }
        return wingIdKind(variantId, false, wingSpec);
    }

    /** The pure law over the two reads. A null / empty id and a
     *  resolvable ship variant are SHIP (a ship read outranks the suffix); a wing spec is KNOWN_WING;
     *  else the {@code _wing} suffix the growth announce keys on (case-sensitive) is UNKNOWN_WING, any
     *  other id SHIP. */
    static WingIdKind wingIdKind(String variantId, boolean shipVariant, boolean wingSpec) {
        if (variantId == null || variantId.isEmpty() || shipVariant) {
            return WingIdKind.SHIP;
        }
        if (wingSpec) {
            return WingIdKind.KNOWN_WING;
        }
        return variantId.endsWith("_wing") ? WingIdKind.UNKNOWN_WING : WingIdKind.SHIP;
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
     * spent or retired anything, or re-fielded the own hull to a realized loadout, closes with ONE
     * summary row so its counts outlive the clear.
     * Render thread.
     */
    void onRoundReset() {
        int intakeFrames;
        int intakeRows;
        int intakeByteBound;
        int recvCut;
        synchronized (queue) {
            intakeFrames = framesRefusedAtIntake;
            intakeRows = rowsRefusedAtIntake;
            intakeByteBound = framesRefusedAtByteBound;
            recvCut = recvUnlogged;
            framesRefusedAtIntake = 0;
            rowsRefusedAtIntake = 0;
            framesRefusedAtByteBound = 0;
            recvLogLines = 0;
            recvUnlogged = 0;
        }
        if (rowsRefusedAtCap > 0 || intakeFrames > 0 || spawnBudgetsSpent > 0 || entriesRetiredDead > 0
                || ownRealizedRefields > 0 || rowsUnlogged > 0 || recvCut > 0) {
            PilotWatchLog.info("predict roster-append: round bounds closed: entity-cap refused rows="
                    + rowsRefusedAtCap + " intake refused frames=" + intakeFrames + " rows=" + intakeRows
                    + " byte_bound=" + intakeByteBound
                    + " spawn budgets spent=" + spawnBudgetsSpent + " retiredDead=" + entriesRetiredDead
                    + " own_realized_refields=" + ownRealizedRefields
                    + " unlogged rows=" + rowsUnlogged + " recv=" + recvCut + " (log cap " + ROW_LOG_MAX + ")");
        }
        // The fleet fold's census (lifetime counts: folds, MISMATCH,
        // OPEN-UNCLOSED, every refusal) closes the round when it moved, so the counts past the fold's own
        // row caps still reach the log. It also carries rows_fielded_known (lifetime): every fielded row
        // folds into the census first, so a moved count always moved the census too.
        String fleetCensus = fleetStamps.meterText();
        if (!fleetCensus.equals(fleetCensusLogged)) {
            fleetCensusLogged = fleetCensus;
            PilotWatchLog.info("predict roster-append: round close census" + fleetCensus
                    + " rows_fielded_known=" + rowsFieldedKnown);
        }
        rowLogLines = 0; // the row-log cap re-arms with the round (ROW_LOG_MAX)
        rowsUnlogged = 0;
        forkRowsThisRound = 0;
        spawnThrowRowsThisRound = 0; // the spawn-failure rows' first-of-a-kind exemption re-arms with the round
        spawnNullRowsThisRound = 0;
        ownRealizedRefields = 0;
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
     *  re-pointed) this frame can mint at this same frame's first pose sight.
     *  <p><b>Bounded by the entry size</b> (the {@link EdgeEventDrain}
     *  law, with this lane's epoch gate kept in front of every poll): one pass folds at most the
     *  frames the queue held when it began. WHY: the net thread refills the queue while this thread
     *  folds, so a pass that ran until the queue was EMPTY let a host that streams RAPP frames
     *  faster than the fold runs hold one render frame open without end (the joiner's game hung).
     *  A frame that lands during the pass waits, in FIFO order, for the next render frame's pass;
     *  nothing is dropped. One pass is therefore at most {@link #MAX_QUEUED_FRAMES} frames.
     *  <p><b>and by key work.</b> Every fielded or removal row's
     *  fleet fold builds one whole-side key, so a pass also stops before a head whose rows would carry its
     *  charge past {@link #KEY_WORK_PER_PASS} (in {@link FleetStamps#keyWorkPerRow} units). That head stays,
     *  in FIFO order, for the next render frame's pass; nothing is dropped. The bound is per FRAME and a pass's
     *  first frame always folds, so a pass charges at most the budget or one lone frame's work. Counted
     *  {@link #keyWorkStops}, one ERROR row each time that count reaches a power of two. */
    void drain() {
        long ownWireId = plugin.session.shipId();
        capRetireWalked = false; // at most ONE retire walk a drain pass (one pass a render frame)
        // The fleet fold's unclosed timer on the valve clock (a
        // diagnostic; the clock is read only while a round-open set is still within its window).
        if (fleetStamps.tick(clock, RAPP_EPOCH_FAILOPEN_MS)) {
            logFleetDiagnostics();
        }
        int entryFrames; // the pass's budget: read ONCE, never re-read as the net thread refills
        synchronized (queue) {
            entryFrames = queue.size();
        }
        int foldedFrames = 0;
        long passKeyWork = 0L; // the pass's key-work charge (KEY_WORK_PER_PASS units)
        while (true) {
            // PEEK + the round-epoch gate.
            // the head peeked here is the one polled below unless a re-admit edge
            // dropped it in between (net thread, onReAdmitted): the poll then finds another head and
            // folds nothing.
            ClientRosterAppend append;
            int newlyDeferredFrames = 0;
            int newlyDeferredRows = 0;
            boolean edge;
            synchronized (queue) {
                edge = reAdmitEdges != reAdmitEdgesFolded; // seen with the queue the drop left behind
                reAdmitEdgesFolded = reAdmitEdges;
                append = queue.peekFirst();
                if (append != null && append.roundEpoch() > resetsRetired) {
                    // Everything queued waits behind this head (stamps never decrease along the FIFO).
                    newlyDeferredFrames = queue.size() - deferredCountedFrames;
                    newlyDeferredRows = queuedRows - deferredCountedRows;
                    deferredCountedFrames = queue.size();
                    deferredCountedRows = queuedRows;
                }
            }
            if (edge) {
                foldReAdmitEdge(); // before any frame taken after the edge folds
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
            if (foldedFrames >= entryFrames) {
                return; // the entry-size bound: this head landed during the pass, it folds next frame
            }
            // The key-work bound: each fielded or removal row of the head
            // builds one whole-side key; a head that would carry the pass past the budget waits for the next pass
            // (FIFO kept, nothing dropped), but a pass's first frame always folds
            long work = 0L;
            for (ClientRosterAppend.Row row : append.rows()) {
                if (row.fleetFielded() || row.fleetVacated()) {
                    work += fleetStamps.keyWorkPerRow(row.side());
                }
            }
            if (foldedFrames > 0 && passKeyWork + work > KEY_WORK_PER_PASS) {
                keyWorkStops++;
                if (Long.bitCount(keyWorkStops) == 1) {
                    PilotWatchLog.error("predict roster-append: KEY WORK bound stopped the pass frames=" + foldedFrames
                            + " work=" + passKeyWork + " (key_work_stops=" + keyWorkStops + ")", null);
                }
                return;
            }
            long cost = decodedCostEstimate(append);
            boolean gone;
            synchronized (queue) {
                gone = queue.peekFirst() != append;
                if (!gone) {
                    queue.pollFirst();
                    queuedRows -= append.rows().size();
                    queuedDecodedBytes -= cost;
                    if (deferredCountedFrames > 0) {
                        deferredCountedFrames--;
                        deferredCountedRows -= append.rows().size();
                    }
                }
            }
            if (gone) {
                // A re-admit edge dropped the peeked head: fold
                // nothing of it and peek again (that peek folds the edge first)
                continue;
            }
            foldedFrames++;
            passKeyWork += work;
            deferredSinceMillis = 0L;
            for (ClientRosterAppend.Row row : append.rows()) {
                if (row.fleetVacated()) {
                    // A removal row folds where a fielded row does,
                    // in log order, then leaves the loop: no arm below may read it as a hull announce of its
                    // goal id (no re-point, no body drop, no mint, no registry write, no officer or carrier)
                    vacateFleetRow(append.roundEpoch(), row);
                    continue;
                }
                if (row.fleetFielded()) {
                    // Every fielded row folds FIRST, whatever an
                    // arm below then does with it (count-ignore, staged, mounted, refused at the cap).
                    foldFleetRow(append.roundEpoch(), row);
                }
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
                            if (RealizedVariantId.isRealized(row.variantId())
                                    && RealizedVariantId.goalOf(row.variantId()).equals(live)) {
                                // NOT a pick fork. The row names
                                // the server's realized loadout of the very variant the live hull flies
                                // (a mod rolled its slot weapons at member creation); the re-field builds
                                // the minted definition, so it is armed quietly and counted, never announced.
                                ownRealizedRefields++;
                                if (rowLog()) {
                                    PilotWatchLog.info("predict roster-append: OWN seat " + row.wireId()
                                            + " REALIZED LOADOUT: live " + FallbackAnnounce.sanitize(live, 64)
                                            + " -> server " + FallbackAnnounce.sanitize(row.variantId(), 64)
                                            + " (alive re-field ARMED; own_realized_refields=" + ownRealizedRefields + ")");
                                }
                                continue;
                            }
                            ownPickForks++;
                            // ROW_LOG_MAX: the round's FIRST fork row always logs (never a silent
                            // fork); a host re-sending the row cannot flood past the row cap.
                            if (forkRowsThisRound++ == 0 || rowLog()) {
                                String fork = "OWN seat " + row.wireId() + " PICK FORK: live hull "
                                        + FallbackAnnounce.sanitize(live, 64) + " != server fielded "
                                        + FallbackAnnounce.sanitize(row.variantId(), 64)
                                        + " (registry already agreed with the row; alive re-field"
                                        + " ARMED; forks=" + ownPickForks + ")";
                                PilotWatchLog.error("predict roster-append: " + fork, null);
                                FallbackAnnounce.announceOnce("own-pick-fork", fork);
                            }
                            continue;
                        }
                        if (row.fleetFielded()) {
                            // An own respawn in the same
                            // ship (one fielded row per fielding, thousands a long round) is counted
                            // in rows_fielded_known, never a per-row text (ROW_LOG_MAX); the
                            // round-close census row prints that count, so the dedup stays visible.
                            rowsFieldedKnown++;
                            continue;
                        }
                        rowsKnown++; // replay/duplicate of an already-applied own re-point
                        // The fold's formerly-ONLY silent OWN path — loud for a
                        // re-announce so a dedup can never masquerade as a lost row again
                        // (a fielded duplicate is metered above).
                        // variantId is wire-decoded text — printable-scrubbed at write time
                        // (single-line log law; the re-point line below gets the same guard).
                        if (rowLog()) {
                            PilotWatchLog.info("predict roster-append: OWN seat " + row.wireId()
                                    + " duplicate re-point "
                                    + FallbackAnnounce.sanitize(row.variantId(), 64)
                                    + " count-ignored (rowsKnown=" + rowsKnown + ")");
                        }
                        continue;
                    }
                    rowsRepointed++;
                    // D-JOINPICK: a registry-only re-point left
                    // the joiner's t=0 descriptor hull flying until its next respawn while the
                    // server (and every other window) simulated the announced variant — arm the
                    // plugin's alive re-field; it compares registry vs live hull next frame.
                    plugin.ownRefieldPending = true;
                    if (rowLog()) {
                        PilotWatchLog.info("predict roster-append: OWN seat " + row.wireId()
                                + " variant re-point " + FallbackAnnounce.sanitize(prior, 64)
                                + " -> " + FallbackAnnounce.sanitize(row.variantId(), 64)
                                + " (registry re-pointed; alive re-field ARMED, else next own"
                                + " respawn fields it; repointed=" + rowsRepointed + ")");
                    }
                    continue;
                }
                if (plugin.xfer.suppressMintFor(row.wireId())) {
                    // The OWN transfer pod (and any H1-retired
                    // id) takes the own-seat shape — variant registered so the id stays
                    // engine-resolvable, replica NEVER minted (the local vanilla pod is adopted
                    // instead; other seats' pods mint normally via the S1 branch).
                    plugin.variantById.put(key, row.variantId());
                    rowsKnown++;
                    if (rowLog()) {
                        PilotWatchLog.info("predict roster-append: suppressed-mint id "
                                + row.wireId() + " variant registered, replica not minted"
                                + " (rowsKnown=" + rowsKnown + ")");
                    }
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
                        if (row.fleetFielded() && row.variantId().equals(prior)) {
                            // A respawn in the same ship,
                            // thousands a long round: counted only, never a per-row text (ROW_LOG_MAX).
                            rowsFieldedKnown++;
                        } else {
                            rowsKnown++;
                            if (rowLog()) {
                                PilotWatchLog.info("predict roster-append: entity " + row.wireId()
                                        + " count-ignored ("
                                        + (re.stationModule ? "module row"
                                                : plugin.mmnt.mountedOrPending(row.wireId())
                                                        ? "mounted seat" : "variant known")
                                        + "; rowsKnown=" + rowsKnown + ")");
                            }
                        }
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
                        if (rowLog()) {
                            PilotWatchLog.info("predict roster-append: REPOINT entity "
                                    + row.wireId() + " variant "
                                    + FallbackAnnounce.sanitize(prior, 64) + " -> "
                                    + FallbackAnnounce.sanitize(row.variantId(), 64)
                                    + " (staged transfer BEGIN: source body KEPT for the"
                                    + " re-home under F; repointed=" + rowsRepointed + ")");
                        }
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
                    if (rowLog()) {
                        PilotWatchLog.info("predict roster-append: REPOINT entity " + row.wireId()
                                + " variant " + FallbackAnnounce.sanitize(prior, 64)
                                + " -> " + FallbackAnnounce.sanitize(row.variantId(), 64)
                                + (stale != null ? " (stale body dropped)" : " (unbound)")
                                + "; spawn-on-sight re-mints at next pose sight"
                                + " (repointed=" + rowsRepointed + ")");
                    }
                    continue;
                }
                if (!admitFresh(row.wireId(), row.variantId())) {
                    continue; // refused at the entity cap (counted; the walk-then-refuse gate)
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
                if (rowLog()) {
                    PilotWatchLog.info("predict roster-append: entity " + row.wireId()
                            + " absSide=" + row.side() + " -> side " + relSide
                            + " variant=" + FallbackAnnounce.sanitize(row.variantId(), 64)
                            // The RAPP name is a joiner's TYPED name since v0.6.8
                            + " name=" + FallbackAnnounce.quote(row.shipName(), 64)
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
                            + " knownIgnored=" + rowsKnown + " rows_fielded_known=" + rowsFieldedKnown + ")");
                }
            }
        }
    }
}
