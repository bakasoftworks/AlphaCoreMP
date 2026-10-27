package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The per-peer SEND GATE (demo-program R10):
 * the enforcement point for the decided invariant that <b>the simulation never waits for a
 * peer's socket</b>. Every live publisher before this welded the engine tick to one peer's blocking
 * write+read ({@code PilotIntentSocketServer.exchangeSnapshot} is a strict ping-pong), so one
 * stalled peer froze the authoritative world for everyone — the exact reason per-client lockstep
 * was ruled DOA, rebuilt by construction. The measured stall window at fleet scale is 0.34 s
 * ({@code SlowReaderStallBench}), so kernel slack is not a liveness mechanism.
 *
 * <p>The gate enforces the {@code ReliabilityLane} semantics the protocol registry already
 * declares, per peer, on the unchanged TCP transport:
 * <ul>
 *   <li><b>{@code REPLACEABLE_SNAPSHOT}</b> (AWSS) → a <b>one-slot latest-only mailbox</b>. The
 *       writer always sends the newest deposited snapshot; an overwritten (never-sent) snapshot
 *       increments the per-peer <b>conflation counter</b> — the lag meter, for free.</li>
 *   <li><b>{@code DURABLE_GAMEPLAY_EVENT}</b> (GPEE) → a <b>bounded FIFO, never conflated</b>:
 *       every enqueued event crosses the wire exactly once, in order, riding before the next
 *       snapshot frame (the spec's multiplex order, which {@code writeSnapshotWithEvents} pins).</li>
 * </ul>
 * The sim thread only {@link #deposit deposits} (never blocks on the socket) and
 * {@link #takeIntent takes} the latest UP intent; a per-peer writer thread runs the blocking
 * {@link Transport#exchange} ping-pong (one UP read per DOWN written — the remote client is
 * byte-for-byte untouched).
 *
 * <p><b>UP lane.</b> {@link #takeIntent} hands the sim the LATEST received intent (held controls
 * are sampled state — latest-wins is faithful; zero fresh frames between ticks ⇒ reuse last, held
 * keys persist). The edge-triggered bits ({@link PilotIntentFrame#USE_SHIP_SYSTEM} bit 7,
 * {@link PilotIntentFrame#VENT_FLUX} bit 8) are accumulated <b>sticky-OR across every frame
 * received between consumptions</b>, so conflation can never drop a real press: the server apply
 * path edge-detects on bit transitions, and a press+release that lands entirely between two sim
 * consumptions still surfaces as one set-bit take (then clears on the next raw take).
 *
 * <p><b>Laggard verdict ladder</b> (each rung an emitted {@link GateEvent}; thresholds sized from
 * the measured stall bench — defaults {@link #DEFAULT_DOWNSHIFT_AFTER_MILLIS} /
 * {@link #DEFAULT_KICK_AFTER_MILLIS} / {@link #DEFAULT_FIFO_BOUND}, friends-trust taste call open):
 * <ol>
 *   <li><b>{@code NORMAL} → {@code DOWNSHIFTED}</b> on sustained conflation (a full
 *       {@code downshiftAfterMillis} window in which conflated deposits outnumber clean ones —
 *       a peer persistently absorbing less than the deposit cadence). On this peer-paced
 *       blocking carrier the conflating mailbox IS the cadence downshift mechanically (the writer
 *       can only ever send what the peer absorbs), so this rung is the declared state + event —
 *       the explicit every-Nth-tick skip is reserved for the future non-blocking carrier
 *       (the {@code WorldSnapshotPublicationPolicy} seam). Recovers to {@code NORMAL} after a
 *       full window with zero conflation.</li>
 *   <li><b>→ {@code KICKED}</b> on a wall-clock writer stall ≥ {@code kickAfterMillis} or on
 *       durable-FIFO overflow ({@code fifoBound}): the gate {@link Transport#abort aborts} the
 *       socket out from under the blocked writer (a fully blocked {@code write}/{@code read}
 *       never returns on its own — bench-reproduced, run 1 froze itself) and mints the reason.
 *       The SEAT survives the kick (death-contract property): only the connection dies.</li>
 * </ol>
 * A peer-initiated disconnect (writer IO failure without a kick) lands in {@code DEAD} with the
 * exception as the reason. Stall detection runs on the deposit path at sim cadence plus a 250 ms
 * writer idle-refresh slice (the bench's detection granularity), so an idle-but-healthy writer is
 * never kicked when deposits pause.
 *
 * <p>Pure JDK + project protocol types; transport-agnostic by the small {@link Transport} seam
 * (the live composition passes {@code PilotIntentSocketServer::exchangeSnapshot} +
 * {@code ::close}; the smoke injects controlled latency). Structural bonus: because the writer
 * always offers the latest state at whatever pace the peer absorbs, the keep-warm
 * special case becomes a property of the composition rather than a hand-built phase.
 */
public final class PeerSendGate implements AutoCloseable {

    /** Durable-FIFO bound (events are sparse vs any kick window; thousands ≫ kick). */
    public static final int DEFAULT_FIFO_BOUND = 4096;
    /** Sustained-conflation window before the DOWNSHIFTED verdict (the bench-sized rung default). */
    public static final long DEFAULT_DOWNSHIFT_AFTER_MILLIS = 1_000L;
    /** Wall-clock writer stall before the KICKED verdict (the bench-sized rung default). */
    public static final long DEFAULT_KICK_AFTER_MILLIS = 10_000L;

    /** Writer idle-refresh slice; also the stall-detection granularity floor (bench mechanism). */
    private static final long WRITER_IDLE_SLICE_MILLIS = 250L;

    /** The blocking per-peer exchange the writer thread owns; the sim thread never calls it. */
    public interface Transport {
        /**
         * One ping-pong: write {@code events}-then-{@code despawns}-then-{@code snapshot} DOWN
         * (the 3-arg {@code exchangeSnapshot} multiplex order; both lists empty = the 1-arg bytes),
         * read one intent UP. {@code tick} is the deposit tick of {@code snapshot} (for sink/tick
         * bookkeeping — the composition's sink stamp belongs HERE, on the
         * writer thread, so sink usage is single-threaded per endpoint).
         */
        PilotIntentFrame exchange(long tick, AuthoritativeWorldSnapshot snapshot,
                List<GameplayEdgeEvent> events, List<EntityDespawnEvent> despawns) throws IOException;

        /** Close the underlying socket out from under a blocked exchange (the kick mechanism). */
        void abort();
    }

    /**
     * The duplex seam (mode {@code duplex}): the
     * v1 fused ping-pong split into independently-blocking halves so the delivery rate is no longer
     * welded to RTT (v1 ceiling = tickPeriod/RTT, measured 44 % on the VPN seat). A
     * gate built on this seam runs TWO loops: SEND (one DOWN per fresh deposit — v1's writerLoop
     * minus the read) and RECV ({@link #receiveIntent} forever, feeding the same latest-wins +
     * sticky-OR intent state). The v1 fused {@link Transport} stays for mode {@code decoupled}.
     */
    public interface DuplexTransport {
        /**
         * Write {@code events}-then-{@code despawns}-then-{@code snapshot} DOWN (the same multiplex
         * order as the fused exchange), one flush, NO read. {@code tick} is the deposit tick of
         * {@code snapshot} — also the sink stamp for the DOWN records this call produces (design
         * pt 7: explicit stamps; two loops must not race a shared sink tick field).
         */
        void send(long tick, AuthoritativeWorldSnapshot snapshot,
                List<GameplayEdgeEvent> events, List<EntityDespawnEvent> despawns) throws IOException;

        /**
         * Block for the peer's next UP intent (declaration frames demuxed en route, subject to the
         * pre-barrier freshness gate). {@code stampTick} = the gate's latest deposited tick (the UP
         * sink stamp); {@code pdclAcceptUpperBound} = the declaration freshness bound
         * ({@link Long#MAX_VALUE} once the round barrier has passed). Returns {@code null} on a
         * clean EOF at a frame boundary; a read deadline or peer death throws {@link IOException}.
         */
        PilotIntentFrame receiveIntent(long stampTick, long pdclAcceptUpperBound) throws IOException;

        /** Close the underlying socket out from under EITHER blocked loop (kick/quiesce mechanism). */
        void abort();
    }

    /** Verdict-ladder state. {@code KICKED}/{@code DEAD} are terminal; the seat survives both. */
    public enum Rung { NORMAL, DOWNSHIFTED, KICKED, DEAD }

    /** One emitted ladder transition (the per-peer lag/verdict observability record). */
    public record GateEvent(
            String peer, Rung from, Rung to, String reason,
            long conflatedSnapshots, int fifoDepth, long sinceWriterAliveMillis) {
    }

    /** Observes every UP frame in wire order on the writer thread (digest/match accounting). */
    public interface UpObserver {
        void onIntent(long sentTick, PilotIntentFrame frame);
    }

    private final String peer;
    private final Transport transport;            // v1 fused mode; null in duplex mode
    private final DuplexTransport duplexTransport; // duplex mode; null in v1 mode
    private final Consumer<GateEvent> listener;
    private final UpObserver upObserver;
    private final int fifoBound;
    private final long downshiftAfterMillis;
    private final long kickAfterMillis;
    private final Thread writer; // v1: the fused exchange loop; duplex: the SEND loop
    private final Thread recv;   // duplex only (null in v1 mode): the blocking UP RECV loop

    private final Object lock = new Object();
    // DOWN: one-slot latest-only snapshot mailbox + bounded durable event FIFO.
    private AuthoritativeWorldSnapshot pendingSnapshot;
    private long pendingTick = -1L;
    private boolean pendingFresh;
    private final ArrayDeque<GameplayEdgeEvent> fifo = new ArrayDeque<>();
    // EDSP despawns are DURABLE too (never conflated, exactly once, in
    // order) — a second FIFO whose size counts toward the SAME fifoBound as the event FIFO.
    private final ArrayDeque<EntityDespawnEvent> despawnFifo = new ArrayDeque<>();
    // UP: latest raw frame + sticky-OR edge-bit accumulator between sim consumptions.
    private PilotIntentFrame lastRaw;
    private int stickyEdgeBits;
    private boolean freshSinceLastTake;
    // Ladder state (all guarded by lock).
    private Rung rung = Rung.NORMAL;
    private String deathReason = "";
    private long windowStartMillis = -1L;
    private long windowConflated;
    private long windowClean;
    private long lastWriterAliveMillis;
    private boolean stopRequested;
    private boolean writerExited;
    private boolean writerCleanExit;
    private boolean recvExited;     // duplex only
    private boolean recvCleanExit;  // duplex only
    // round-start barrier state (duplex only, guarded by lock): latched by the first UP
    // frame whose echoed frameSeq is <= this round's latest deposited tick — a client can only echo
    // a tick THIS round has produced, so a stale round-N echo (HIGH ~K) cannot open round N+1.
    private boolean barrierPassed;
    // The freshness upper bound + RECV-side sink stamp (design pt 5/7): written by deposit() under
    // the lock, read by the RECV loop without it — hence volatile.
    private volatile long latestDepositedTick = -1L;
    // Counters.
    private long deposits, depositsRefused, conflatedSnapshots, exchangesCompleted;
    private long eventsEnqueued, eventsSent, upFramesReceived;
    private long despawnsEnqueued, despawnsSent;
    private long freshTakes, reusedTakes, nullTakes;
    private long downsSent, staleUps, sendStalls; // duplex grammar (design pt 8)
    private int maxFifoDepth;
    private long lastSentTick = -1L;

    /** V1 fused mode ({@code decoupled}): one writer thread runs the blocking exchange ping-pong. */
    public PeerSendGate(String peer, Transport transport, int fifoBound,
            long downshiftAfterMillis, long kickAfterMillis,
            Consumer<GateEvent> listener, UpObserver upObserver) {
        this(peer, Objects.requireNonNull(transport, "transport"), null,
                fifoBound, downshiftAfterMillis, kickAfterMillis, listener, upObserver);
    }

    /**
     * Duplex mode: two loops per peer — SEND (one DOWN per fresh deposit, drains the
     * durable FIFOs + latest-wins mailbox; v1's writerLoop minus the read) and RECV (blocking UP
     * reads forever into the same latest-wins + sticky-OR intent state). Delivery rate is thereby
     * decoupled from RTT (RTT = staleness only). {@link #deposit}/{@link #takeIntent} and the
     * ladder API are unchanged. Differences from v1, all from the folded design:
     * <ul>
     *   <li><b>Round-start barrier freshness rule</b> (pt 5): pre-barrier, an UP frame is accepted
     *       iff {@code frameSeq <= latestDepositedTick} of THIS round; rejects count
     *       {@link #staleUps()} and never feed {@code lastRaw} (nor the sticky bits — a round-N
     *       press must not fire in round N+1). The first accepted frame latches the barrier; after
     *       it every frame feeds unconditionally (mid-round echo lag is normal).
     *       {@link #awaitFirstUpFrame} therefore waits on the FRESH counter.</li>
     *   <li><b>Liveness re-base</b> (pt 4): the deposit-path wall-stall kick now watches the SEND
     *       loop only ({@code lastWriterAliveMillis} = send-loop alive); peer silence is the RECV
     *       half's job via the transport's armed read deadline. Either loop's IO death or a kick
     *       aborts the socket so BOTH loops exit — one ladder, two feeders.</li>
     *   <li><b>Quiesce</b> (pt 6): {@link #shutdown} drains-then-stops SEND first, then RECV
     *       (stop checked after every read — returns within ~one client frame interval while the
     *       peer streams); a peer silent at the boundary is the dead case (abort + non-clean
     *       hand-back), v1 semantics.</li>
     *   <li><b>Counters</b> (pt 8): {@code exchangesCompleted} stays 0; {@link #downsSent()} /
     *       {@link #upFramesReceived()} (accepted) / {@link #staleUps()} / {@link #sendStalls()}
     *       carry the duplex grammar.</li>
     * </ul>
     */
    public PeerSendGate(String peer, DuplexTransport transport, int fifoBound,
            long downshiftAfterMillis, long kickAfterMillis,
            Consumer<GateEvent> listener, UpObserver upObserver) {
        this(peer, null, Objects.requireNonNull(transport, "transport"),
                fifoBound, downshiftAfterMillis, kickAfterMillis, listener, upObserver);
    }

    private PeerSendGate(String peer, Transport fused, DuplexTransport duplex, int fifoBound,
            long downshiftAfterMillis, long kickAfterMillis,
            Consumer<GateEvent> listener, UpObserver upObserver) {
        this.peer = Objects.requireNonNull(peer, "peer");
        this.transport = fused;
        this.duplexTransport = duplex;
        if (fifoBound <= 0) {
            throw new IllegalArgumentException("fifoBound must be > 0; got " + fifoBound);
        }
        if (downshiftAfterMillis <= 0 || kickAfterMillis <= 0) {
            throw new IllegalArgumentException("ladder thresholds must be > 0");
        }
        this.fifoBound = fifoBound;
        this.downshiftAfterMillis = downshiftAfterMillis;
        this.kickAfterMillis = kickAfterMillis;
        this.listener = listener;
        this.upObserver = upObserver;
        if (duplex != null) {
            this.writer = new Thread(this::sendLoop, "peer-send-gate-" + peer + "-send");
            this.recv = new Thread(this::recvLoop, "peer-send-gate-" + peer + "-recv");
            this.recv.setDaemon(true);
        } else {
            this.writer = new Thread(this::writerLoop, "peer-send-gate-" + peer);
            this.recv = null;
        }
        this.writer.setDaemon(true);
    }

    /** Start the per-peer thread(s). Call once, after the peer's transport is connected. */
    public void start() {
        synchronized (lock) {
            lastWriterAliveMillis = nowMillis();
        }
        writer.start();
        if (recv != null) {
            recv.start();
        }
    }

    /** Events-only deposit (no despawns this tick) — delegates to the 4-arg lane. */
    public boolean deposit(long tick, AuthoritativeWorldSnapshot snapshot,
            List<GameplayEdgeEvent> events) {
        return deposit(tick, snapshot, events, List.of());
    }

    /**
     * Sim-thread per-tick deposit; NEVER blocks on the socket. The snapshot replaces any unsent
     * one (counted as conflation); {@code events} and {@code despawns} (nullable = none) append to
     * their durable FIFOs (combined size bounded by {@code fifoBound}). Runs the verdict ladder.
     * Returns false when the gate is no longer accepting (kicked/dead/shut down) — the deposit was
     * dropped and the sim should stop feeding this peer.
     */
    public boolean deposit(long tick, AuthoritativeWorldSnapshot snapshot,
            List<GameplayEdgeEvent> events, List<EntityDespawnEvent> despawns) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (lock) {
            if (stopRequested || rung == Rung.KICKED || rung == Rung.DEAD || writerExited
                    || recvExited) {
                depositsRefused++;
                return false;
            }
            long now = nowMillis();
            deposits++;
            int incoming = (events == null ? 0 : events.size()) + (despawns == null ? 0 : despawns.size());
            if (incoming > 0) {
                if (fifo.size() + despawnFifo.size() + incoming > fifoBound) {
                    kickLocked("durable_fifo_overflow", now);
                    return false;
                }
                if (events != null && !events.isEmpty()) {
                    fifo.addAll(events);
                    eventsEnqueued += events.size();
                }
                if (despawns != null && !despawns.isEmpty()) {
                    despawnFifo.addAll(despawns);
                    despawnsEnqueued += despawns.size();
                }
                int depth = fifo.size() + despawnFifo.size();
                if (depth > maxFifoDepth) {
                    maxFifoDepth = depth;
                }
            }
            boolean overwrote = pendingFresh;
            pendingSnapshot = snapshot;
            pendingTick = tick;
            pendingFresh = true;
            latestDepositedTick = tick; // duplex barrier bound + RECV sink stamp (design pt 5/7)
            if (windowStartMillis < 0) {
                windowStartMillis = now;
            }
            if (overwrote) {
                conflatedSnapshots++;
                windowConflated++;
            } else {
                windowClean++;
            }
            if (now - windowStartMillis >= downshiftAfterMillis) {
                if (rung == Rung.NORMAL && windowConflated > windowClean) {
                    transitionLocked(Rung.DOWNSHIFTED, "sustained_conflation", now);
                } else if (rung == Rung.DOWNSHIFTED && windowConflated == 0) {
                    transitionLocked(Rung.NORMAL, "conflation_cleared", now);
                }
                windowStartMillis = now;
                windowConflated = 0;
                windowClean = 0;
            }
            if (now - lastWriterAliveMillis >= kickAfterMillis) {
                kickLocked("wall_clock_stall", now);
                return false;
            }
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Sim-thread per-tick UP consumption: the latest received intent with the sticky edge bits
     * accumulated since the previous take OR'd on (then cleared). No fresh frame since the last
     * take ⇒ the last RAW frame again (held keys persist; a sticky-only bit falls cleanly).
     * Null until the peer's first UP frame lands (callers run their neutral-intent ramp).
     */
    public PilotIntentFrame takeIntent() {
        synchronized (lock) {
            if (lastRaw == null) {
                nullTakes++;
                return null;
            }
            PilotIntentFrame out = lastRaw;
            if (stickyEdgeBits != 0
                    && (out.heldControls() & stickyEdgeBits) != stickyEdgeBits) {
                out = new PilotIntentFrame(out.shipId(), out.frameSeq(), out.cursorFacingDeg(),
                        out.heldControls() | stickyEdgeBits, out.fireHeld(),
                        out.selectedWeaponGroup(), out.ownShipFacingDeg(),
                        out.aimWorldX(), out.aimWorldY(), out.autofireGroupsMask(),
                        out.targetWireId(), out.fireEdgeSeq(), out.activeTubeOrdinal());
            }
            stickyEdgeBits = 0;
            if (freshSinceLastTake) {
                freshTakes++;
                freshSinceLastTake = false;
            } else {
                reusedTakes++;
            }
            return out;
        }
    }

    /** One drained send unit: the latest-wins snapshot + the durable FIFO contents at drain time. */
    private record Drained(AuthoritativeWorldSnapshot snapshot, long tick,
            List<GameplayEdgeEvent> events, List<EntityDespawnEvent> despawns) {
    }

    /**
     * The shared send-loop head (v1 writerLoop and the duplex sendLoop were verbatim copies until
     * it was extracted): wait for a fresh deposit (idle slices refresh the alive stamp),
     * then drain the latest-wins mailbox + durable FIFOs under the lock. Drain-then-stop:
     * a shutdown() that raced the final deposit (the matchOver kill frame + its
     * durable GPEE/EDSP riders) must still send it — the stop exit (returns {@code null},
     * {@code writerCleanExit} already set) fires only once nothing sendable is pending. A terminal
     * gate exits without draining: its socket is already aborted (kick) or dead.
     */
    private Drained awaitFreshDrainOrStop() throws InterruptedException {
        synchronized (lock) {
            while (!pendingFresh && !stopRequested) {
                lastWriterAliveMillis = nowMillis();
                lock.wait(WRITER_IDLE_SLICE_MILLIS);
            }
            if (stopRequested) {
                boolean terminal = rung == Rung.KICKED || rung == Rung.DEAD;
                if (!pendingFresh || terminal) {
                    writerCleanExit = !terminal;
                    return null;
                }
            }
            Drained drained = new Drained(pendingSnapshot, pendingTick,
                    fifo.isEmpty() ? List.of() : new ArrayList<>(fifo),
                    despawnFifo.isEmpty() ? List.of() : new ArrayList<>(despawnFifo));
            pendingFresh = false;
            fifo.clear();
            despawnFifo.clear();
            return drained;
        }
    }

    /** Lock held. Post-send bookkeeping both loop flavors share (the mode counter stays at the call site). */
    private void recordDownSentLocked(long tick, int eventCount, int despawnCount, long now) {
        eventsSent += eventCount;
        despawnsSent += despawnCount;
        lastSentTick = tick;
        lastWriterAliveMillis = now;
    }

    /** Lock held. Feed one ACCEPTED UP frame into the latest-wins + sticky-OR intent state. */
    private void applyUpLocked(PilotIntentFrame up) {
        lastRaw = up;
        freshSinceLastTake = true;
        upFramesReceived++;
        stickyEdgeBits |= up.heldControls()
                & (PilotIntentFrame.USE_SHIP_SYSTEM | PilotIntentFrame.VENT_FLUX);
        lock.notifyAll(); // wake awaitFirstUpFrame (the round-start barrier)
    }

    private void writerLoop() {
        try {
            while (true) {
                Drained drained = awaitFreshDrainOrStop();
                if (drained == null) {
                    return;
                }
                PilotIntentFrame up = transport.exchange(
                        drained.tick(), drained.snapshot(), drained.events(), drained.despawns());
                long now = nowMillis();
                synchronized (lock) {
                    exchangesCompleted++;
                    recordDownSentLocked(drained.tick(), drained.events().size(),
                            drained.despawns().size(), now);
                    if (up != null) {
                        applyUpLocked(up);
                    }
                }
                if (up != null && upObserver != null) {
                    upObserver.onIntent(drained.tick(), up);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            synchronized (lock) {
                stopRequested = true;
                if (rung != Rung.KICKED) {
                    // Peer-initiated death (or post-shutdown abort), not a gate verdict.
                    String reason = "writer_io: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    deathReason = reason;
                    transitionLocked(Rung.DEAD, reason, nowMillis());
                }
            }
        } finally {
            synchronized (lock) {
                writerExited = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * Duplex SEND loop: v1's writerLoop minus the read — wait for a fresh deposit, drain
     * the durable FIFOs + latest-wins mailbox, {@link DuplexTransport#send send}. Same
     * drain-then-stop shutdown semantics; {@code lastWriterAliveMillis} now means "SEND loop alive"
     * (refreshed per completed send + idle slice) and feeds the deposit-path wall-stall kick. A
     * completed send that blocked one idle slice or longer counts {@link #sendStalls()} — the
     * near-backpressure meter (design pt 3: conflation must be backpressure-only).
     */
    private void sendLoop() {
        try {
            while (true) {
                Drained drained = awaitFreshDrainOrStop();
                if (drained == null) {
                    return;
                }
                long sendStart = nowMillis();
                duplexTransport.send(
                        drained.tick(), drained.snapshot(), drained.events(), drained.despawns());
                long now = nowMillis();
                synchronized (lock) {
                    downsSent++;
                    recordDownSentLocked(drained.tick(), drained.events().size(),
                            drained.despawns().size(), now);
                    if (now - sendStart >= WRITER_IDLE_SLICE_MILLIS) {
                        sendStalls++;
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            loopDied("send_io", e);
        } finally {
            synchronized (lock) {
                writerExited = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * Duplex RECV loop: {@link DuplexTransport#receiveIntent} forever, routing accepted
     * frames into the SAME {@code lastRaw}/sticky-OR/{@code freshSinceLastTake} state v1 feeds.
     * Pre-barrier freshness (design pt 5): accept iff {@code frameSeq <= latestDepositedTick};
     * rejects count {@link #staleUps()} and feed NOTHING (a stale round-N sticky press must not
     * fire in round N+1). Stop is checked after every read, so quiesce returns within ~one client
     * frame interval while the peer streams; a read that never returns is the dead case —
     * {@link #shutdown} aborts it (v1 semantics). The observer sees accepted frames only.
     */
    private void recvLoop() {
        try {
            while (true) {
                synchronized (lock) {
                    if (stopRequested) {
                        recvCleanExit = true;
                        return;
                    }
                }
                long stamp = latestDepositedTick;
                boolean passedBefore;
                synchronized (lock) {
                    passedBefore = barrierPassed;
                }
                PilotIntentFrame up = duplexTransport.receiveIntent(
                        stamp, passedBefore ? Long.MAX_VALUE : stamp);
                if (up == null) {
                    // Clean EOF: the peer closed the up lane. Mid-round that is a disconnect (the
                    // fused lanes throw the same way); during quiesce the loop top already exited.
                    loopDied("recv_eof", new IOException("peer closed the up lane (clean EOF)"));
                    return;
                }
                boolean fresh;
                synchronized (lock) {
                    fresh = barrierPassed || up.frameSeq() <= latestDepositedTick;
                    if (fresh) {
                        barrierPassed = true;
                        applyUpLocked(up);
                    } else {
                        staleUps++;
                    }
                }
                if (fresh && upObserver != null) {
                    upObserver.onIntent(stamp, up);
                }
            }
        } catch (IOException | RuntimeException e) {
            loopDied("recv_io", e);
        } finally {
            synchronized (lock) {
                recvExited = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * Either duplex loop's IO death: mint the terminal verdict ONCE (the other loop's abort-induced
     * failure must not overwrite the first reason — one ladder, two feeders), then abort the socket
     * so the OTHER loop exits too. Post-quiesce aborts land here exactly like v1's writerLoop catch
     * ("peer silent at the boundary is the dead case" — non-clean hand-back).
     */
    private void loopDied(String lane, Exception e) {
        synchronized (lock) {
            stopRequested = true;
            if (rung != Rung.KICKED && rung != Rung.DEAD) {
                String reason = lane + ": " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                deathReason = reason;
                transitionLocked(Rung.DEAD, reason, nowMillis());
            }
            lock.notifyAll();
        }
        abortTransport();
    }

    private void abortTransport() {
        if (duplexTransport != null) {
            duplexTransport.abort();
        } else {
            transport.abort();
        }
    }

    /** Lock held. Terminal kick: emit the verdict, then abort the socket to unblock the writer. */
    private void kickLocked(String reason, long now) {
        deathReason = reason;
        stopRequested = true;
        transitionLocked(Rung.KICKED, reason, now);
        lock.notifyAll();
        abortTransport();
    }

    /** Lock held. Listener must not call back into the gate (documented contract). */
    private void transitionLocked(Rung to, String reason, long now) {
        Rung from = rung;
        rung = to;
        if (listener != null) {
            listener.accept(new GateEvent(peer, from, to, reason,
                    conflatedSnapshots, fifo.size(), now - lastWriterAliveMillis));
        }
    }

    /**
     * Stop the writer at a frame boundary: it finishes any in-flight exchange AND drains a still-
     * pending final deposit first (the matchOver kill frame + its durable GPEE/EDSP riders — design
     * pt 8's exactly-once guarantee holds through shutdown). If it is still blocked after
     * {@code graceMillis}, abort the socket. {@code graceMillis <= 0} waits one idle slice only
     * (250 ms) — enough for an idle writer to exit cleanly, never a wait on a blocked exchange.
     * Returns true when the writer exited CLEANLY at a boundary with the gate un-kicked — i.e. the
     * socket is still usable by the caller (single-threaded again) for a terminal send.
     */
    public boolean shutdown(long graceMillis) {
        synchronized (lock) {
            stopRequested = true;
            lock.notifyAll();
        }
        try {
            // Thread.join(0) waits FOREVER — a no-grace shutdown/close on a writer blocked in a
            // deadline-less exchange must not hang the caller (bench review 2026-07-05): bound the
            // no-grace wait to one idle slice, then abort the socket out from under the writer.
            // Duplex: SEND first (drain-then-stop), then RECV — which exits
            // after its next read while the peer streams; still blocked after the grace = the peer
            // is silent at the boundary = the dead case, abort + non-clean hand-back.
            long grace = graceMillis > 0L ? graceMillis : WRITER_IDLE_SLICE_MILLIS;
            writer.join(grace);
            if (recv != null && !writer.isAlive()) {
                recv.join(grace);
            }
            if (writer.isAlive() || (recv != null && recv.isAlive())) {
                abortTransport();
                writer.join(WRITER_IDLE_SLICE_MILLIS * 2);
                if (recv != null) {
                    recv.join(WRITER_IDLE_SLICE_MILLIS * 2);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            return writerCleanExit && (recv == null || recvCleanExit)
                    && rung != Rung.KICKED && rung != Rung.DEAD;
        }
    }

    @Override
    public void close() {
        shutdown(0L);
        abortTransport();
    }

    /**
     * Round-start barrier (the lobby problem): block until the peer's FIRST
     * UP frame lands (true), the gate goes terminal (false), or {@code timeoutMillis} elapses
     * (false). No deposits happen while a caller waits here, so the deposit-path wall-stall kick
     * cannot fire during a lobby; the writer's own blocking exchange stays bounded by the
     * transport's read deadline. Returns immediately once any UP frame was ever received.
     */
    public boolean awaitFirstUpFrame(long timeoutMillis) throws InterruptedException {
        long deadline = nowMillis() + Math.max(0L, timeoutMillis);
        synchronized (lock) {
            while (upFramesReceived == 0) {
                if (writerExited || recvExited || rung == Rung.KICKED || rung == Rung.DEAD) {
                    return false;
                }
                long remaining = deadline - nowMillis();
                if (remaining <= 0L) {
                    return false;
                }
                lock.wait(Math.min(remaining, WRITER_IDLE_SLICE_MILLIS));
            }
            return true;
        }
    }

    public boolean isAlive() {
        synchronized (lock) {
            return !writerExited && !recvExited && rung != Rung.KICKED && rung != Rung.DEAD;
        }
    }

    public Rung rung() {
        synchronized (lock) {
            return rung;
        }
    }

    public String deathReason() {
        synchronized (lock) {
            return deathReason;
        }
    }

    public long deposits() {
        synchronized (lock) {
            return deposits;
        }
    }

    public long depositsRefused() {
        synchronized (lock) {
            return depositsRefused;
        }
    }

    public long conflatedSnapshots() {
        synchronized (lock) {
            return conflatedSnapshots;
        }
    }

    public long exchangesCompleted() {
        synchronized (lock) {
            return exchangesCompleted;
        }
    }

    public long eventsEnqueued() {
        synchronized (lock) {
            return eventsEnqueued;
        }
    }

    public long eventsSent() {
        synchronized (lock) {
            return eventsSent;
        }
    }

    public long despawnsEnqueued() {
        synchronized (lock) {
            return despawnsEnqueued;
        }
    }

    public long despawnsSent() {
        synchronized (lock) {
            return despawnsSent;
        }
    }

    public int maxFifoDepth() {
        synchronized (lock) {
            return maxFifoDepth;
        }
    }

    /** V1: every UP frame. Duplex: ACCEPTED frames only (raw wire UPs = this + {@link #staleUps()}). */
    public long upFramesReceived() {
        synchronized (lock) {
            return upFramesReceived;
        }
    }

    /** Duplex: completed DOWN sends ({@code downs_sent}; the v1 analogue is exchangesCompleted). */
    public long downsSent() {
        synchronized (lock) {
            return downsSent;
        }
    }

    /** Duplex: pre-barrier UP frames rejected by the freshness rule (design pt 5), never fed. */
    public long staleUps() {
        synchronized (lock) {
            return staleUps;
        }
    }

    /** Duplex: completed sends that blocked ≥ one idle slice — the near-backpressure meter. */
    public long sendStalls() {
        synchronized (lock) {
            return sendStalls;
        }
    }

    public long freshTakes() {
        synchronized (lock) {
            return freshTakes;
        }
    }

    public long reusedTakes() {
        synchronized (lock) {
            return reusedTakes;
        }
    }

    public long nullTakes() {
        synchronized (lock) {
            return nullTakes;
        }
    }

    public long lastSentTick() {
        synchronized (lock) {
            return lastSentTick;
        }
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
