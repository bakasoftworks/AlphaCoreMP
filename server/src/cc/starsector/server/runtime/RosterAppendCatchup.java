package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The PACED per-session admission cursor: everything a joiner must be taught about the match that
 * scales with the seat count or the round's age leaves over several ticks, never in one burst.
 *
 * The CTRL kill point is ~1024 frames (512 send window
 * + 512 spool): a standing endless round's RAPP log alone crosses it after ~50 min, and at 255
 * seats the per-seat admission terms (one SEAT baseline row + one seat-name row a seat) are 510
 * frames, far past the 64-frame margin the variant-definition burst leaves.
 *
 * <p>SEGMENTS, drained in a FIXED order per session: {@link Segment#SEAT} (a plain snapshot of
 * pre-encoded baseline frames; never coalesced — every row carries a fresh seat sequence and the
 * client keeps the newest), {@link Segment#RAPP} (the round's shared append-log, walked to its
 * CURRENT end), {@link Segment#XFER} (the in-flight transfer BEGINs, snapshotted from the port
 * EXACTLY once when the RAPP segment completes — a BEGIN references roster ids RAPP taught — and
 * sent WHOLE in that same step, past the step's budget: the synchronous transfer replay moved
 * into the cursor, so no live transfer frame can overtake its BEGIN) and
 * {@link Segment#SNMR} (seat id → pre-encoded seat-name frame, bound by the reader thread). Bodies
 * leave before names; a segment the session never gets is simply absent.
 *
 * <p>BUDGET: each step sends at most {@link #budget}{@code (headroom, cap, RESERVE)} frames, where
 * headroom is the session's free CTRL window (0 while its spool is non-empty, 0 while the session
 * is not yet provisioned) — so a step never spools and leaves {@link #RESERVE} for the live fans,
 * whatever the RTT. At the default cap 64 a session runs at full rate while its RTT is ≤ 7 ticks
 * (64 × 7 = 448 = 512 − {@link #RESERVE}); a 3-h endless log (~3500 frames) is ~55 ticks.
 * Across ALL cursors one tick sends at most {@link #GLOBAL_CAP} frames, shared round-robin — a
 * RAPP completion's XFER spill (above) is the one excess, counted in that tick's ledger.
 *
 * <p>ORDER law: while a session's RAPP segment exists the door's live RAPP and XFER fans WITHHOLD
 * frames from it ({@code PeerSession.rosterCatchupActive}, set through {@link Port#setFlag}); the
 * withheld RAPP frames are in the log, so the cursor delivers them after every earlier log frame
 * (per-session delivery order == log order — mutable-per-id rows never arrive stale), and the
 * withheld XFER view is replaced by the completion snapshot.
 *
 * <p>INVARIANT: a session's catch-up flag is true iff its cursor holds a RAPP segment; the RAPP
 * completion and EVERY removal path (completion, death, round reset) clear it — a session left
 * flagged would never receive a live RAPP again. A SEAT-only cursor never sets it.
 *
 * <p>THREADS: the serve-loop thread owns the cursor map and every drain (O(active cursors)
 * bookkeeping + at most {@link #GLOBAL_CAP} pre-encoded sends per tick plus any XFER spill; the
 * cursor encodes nothing, the port's once-per-admission XFER snapshot aside — the frame-loop
 * residency law). The
 * reader thread binds and replaces seat-name rows ({@link #bindSeatNames},
 * {@link #replacePendingName}). The {@link Cursor} object is the per-session catch-up MONITOR:
 * every segment send and every seat-name row change runs under {@code synchronized (cursor)}.
 * Lock order: the cursor monitor → the door lock → reliability ({@link Port#send} takes the door
 * lock inside), so a caller of a reader-thread method holds NO door lock.
 *
 * <p>Generic in the session type so the pure rows drive it with a fake {@link Port}.
 *
 *
 */
public final class RosterAppendCatchup<S> {

    /** The admission segments in their FIXED drain order. */
    public enum Segment { SEAT, RAPP, XFER, SNMR }

    /** Frames of CTRL window left free for the live fans. */
    public static final int RESERVE = 64;
    /** Default per-tick cap ({@code cc.server.rappCatchupPerTick}): 64 frames so a 30-seat admission's
     *  per-seat catch-up (two frames a seat) still leaves in ONE tick while the window keeps its
     *  {@link #RESERVE}. */
    public static final int DEFAULT_CAP = 64;
    /** The knob key/env/file triple (registered BEHAVIOR in {@link PostureRegistry}). */
    public static final String PROP = "cc.server.rappCatchupPerTick";
    /** */
    public static final String ENV = "CC_SERVER_RAPP_CATCHUP_PER_TICK";
    /** */
    public static final String FILE_KEY = "server.rappCatchupPerTick";

    /**
     * Frames ALL cursors together may send in one serve tick (admit steps included; a RAPP
     * completion's XFER spill is the one excess, and the next tick starts a fresh ledger). A round-wrap
     * SEAT re-baseline at 255 seats is 255 × 255 = 65 025 frames: at 512 a tick it drains in 128
     * ticks (≈ 2.1 s at 60 Hz, admission-class) instead of one serve-thread stall.
     *
     *
     */
    public static final int GLOBAL_CAP = 512;

    /** Drain calls a retired or never-minted index entry is kept (the bind-vs-mint race window);
     *  public for row C10's idle-drain bound. */
    public static final long TOMBSTONE_DRAINS = 600L;
    /** Drain calls between index sweeps (row C10). */
    public static final long SWEEP_EVERY_DRAINS = 60L;

    /** The session seam (real = PeerSession over the tick's liveById; rows = a fake).
     * */
    public interface Port<S> {
        boolean isLive(S session);

        int headroom(S session);

        /** One RAPP / XFER / SNMR frame to one session; takes the door lock inside and runs under
         *  the cursor monitor. */
        void send(S session, byte[] frame);

        int spoolDepth(S session);

        /** The catch-up flag (the live RAPP / XFER fans withhold from a flagged session). */
        void setFlag(S session, boolean on);

        /**
         * false = the door has not finished provisioning the session (its variant-definition
         * burst may still be filling the window): the cursor treats it as headroom 0 — it sends
         * nothing, retires nothing and is NOT abandoned. The default (true) is the pre-gate
         * behaviour for a port that does not know.
         */
        default boolean provisioned(S session) {
            return true;
        }

        /**
         * The session's in-flight transfer BEGIN frames, asked EXACTLY once, under the cursor
         * monitor, at the moment its RAPP segment completes (after the last RAPP send, before
         * the first XFER send); the cursor then sends them ALL in that same step, past the step's
         * budget (at most one BEGIN per seat, into a spool the step's own budget proved empty).
         * Empty = no XFER segment. The default (empty) is for a port that replays transfers itself
         * at {@link Listener#completed}.
         */
        default List<byte[]> xferSnapshot(S session) {
            return List.of();
        }

        /**
         * One SEAT baseline frame. A separate leg because the baseline keeps its own send
         * primitive (no per-frame down record of its own); the default routes it like any frame.
         *
         */
        default void sendSeat(S session, byte[] frame) {
            send(session, frame);
        }
    }

    /** Completion/abandon callbacks (the serve loop prints the lines). Serve thread; a callback
     *  never mints. */
    public interface Listener<S> {
        /** Once per cursor: after the last frame of its last segment — or, for a cursor whose RAPP
         *  walk ended before a round wrap while other segments still drain, AT that wrap (the dying
         *  log's size; the serve line then names the round that completed it, never a later round's
         *  frame with a negative tick count), and its later
         *  retirement reports nothing more. {@code logSize} = the RAPP log's size at that moment. */
        void completed(long sessionId, S session, Cursor cursor, int logSize);

        /** {@code reason} = {@code dead} (the session left the live map: the whole cursor is
         *  dropped) or {@code round_reset} (the round wrapped under a RAPP segment: ONLY that
         *  segment is dropped — a cursor whose {@link Cursor#pending()} is still non-empty keeps
         *  draining and later reports {@link #completed}). */
        void abandoned(long sessionId, S session, Cursor cursor, int logSize, String reason);
    }

    /**
     * One session's admission walk AND its catch-up monitor (every field below the meters is
     * guarded by {@code synchronized (this)}). Created by the serve thread at mint, or earlier by
     * the reader thread when the seat names are bound before the serve loop saw the session live
     * (a pre-cursor: names only, adopted at mint).
     */
    public static final class Cursor {
        final long sessionId;
        Object session;
        long startTick;
        boolean minted;
        boolean retired;
        List<byte[]> seat;
        int seatNext;
        boolean rapp;
        boolean rappCompleted;
        /** {@link Listener#completed} already fired (at a round wrap that found the RAPP walk
         *  ended): the retirement fires it no second time. */
        boolean completionReported;
        int next;
        List<byte[]> xfer;
        int xferNext;
        LinkedHashMap<Integer, byte[]> snmr;
        int sent;
        int rappSent;
        int stalledTicks;
        int spoolMax;
        // Serve-thread-only step bookkeeping (never read by the reader thread).
        long steppedTick = Long.MIN_VALUE;
        long sweepStamp = Long.MIN_VALUE;
        int tickBudget;
        int tickSent;

        Cursor(long sessionId) {
            this.sessionId = sessionId;
        }

        /** The RAPP log index the cursor sends next. */
        public int next() { return next; }

        public long startTick() { return startTick; }

        /** Frames sent across every segment. */
        public int sent() { return sent; }

        /** RAPP log frames sent (the completion line's {@code sent=}, whose meaning predates the
         *  other segments). */
        public synchronized int rappSent() { return rappSent; }

        public int stalledTicks() { return stalledTicks; }

        public int spoolMax() { return spoolMax; }

        /** true = the RAPP segment walked to the log's end (false if it was dropped or never
         *  minted). */
        public synchronized boolean rappCompleted() { return rappCompleted; }

        /** The segments still holding unsent frames, in drain order. */
        public synchronized Set<Segment> pending() {
            EnumSet<Segment> out = EnumSet.noneOf(Segment.class);
            if (seat != null && seatNext < seat.size()) {
                out.add(Segment.SEAT);
            }
            if (rapp) {
                out.add(Segment.RAPP);
            }
            if (xfer != null && xferNext < xfer.size()) {
                out.add(Segment.XFER);
            }
            if (snmr != null && !snmr.isEmpty()) {
                out.add(Segment.SNMR);
            }
            return out;
        }

        synchronized boolean hasPending() {
            return pendingLocked();
        }

        boolean pendingLocked() {
            return (seat != null && seatNext < seat.size()) || rapp
                    || (xfer != null && xferNext < xfer.size()) || (snmr != null && !snmr.isEmpty());
        }
    }

    private final int cap;
    /** Minted, unretired cursors in mint order (serve thread only). */
    private final Map<Long, Cursor> cursors = new LinkedHashMap<>();
    /** Every cursor by session id for the reader thread: minted ones, pre-cursors, tombstones. */
    private final ConcurrentHashMap<Long, Cursor> index = new ConcurrentHashMap<>();
    private final ArrayList<Cursor> scratch = new ArrayList<>();
    private final ArrayList<Cursor> dead = new ArrayList<>();
    private final ArrayList<Cursor> done = new ArrayList<>();
    private int completedThisRound;
    private long ticksMaxThisRound;
    private int abandonedThisRound;
    private int globalCapTicksThisRound;
    /**
     * The serve-thread send MEASURE behind {@link #GLOBAL_CAP}: wall nanos and frames of the drain
     * steps that sent at least one frame this round (two clock reads per such step, none on an idle
     * or stalled tick; no per-send timing), printed as {@code cursor_drain_us=} /
     * {@code cursor_drain_sends=}. WHY: the cap bounds the serve-thread stall only if a cursor send
     * is cheap — the 512 is kept on this measured number. Delete with the row that reads it, or
     * when {@link #GLOBAL_CAP} becomes a knob.
     *
     */
    private long drainNanosThisRound;
    private long drainSendsThisRound;
    private long ledgerTick = Long.MIN_VALUE;
    private int ledgerSpent;
    private long capHitTick = Long.MIN_VALUE;
    private int rotation;
    private long drainCalls;
    private long lastSweep = Long.MIN_VALUE;

    /** {@code cap <= 0} = the LEGACY one-shot replay (the rollback lever, the
     *  {@code serve.waveSpawnPerTick=0} precedent; {@link #paced()} is false, nothing here runs —
     *  no cursor is minted, no flag is set, no name is bound — and the caller keeps its synchronous
     *  burst). */
    public RosterAppendCatchup(int cap) {
        this.cap = cap;
    }

    /** */
    public boolean paced() {
        return cap > 0;
    }

    /** */
    public int cap() {
        return cap;
    }

    /** The budget law: {@code min(cap, headroom - reserve)}, never negative (≤ 0 ⇒ stall this
     *  tick). */
    public static int budget(int headroom, int cap, int reserve) {
        int room = headroom - reserve;
        if (room <= 0 || cap <= 0) {
            return 0;
        }
        return Math.min(cap, room);
    }

    /** The RAPP-only mint (an empty SEAT snapshot). */
    public void mint(long sessionId, S session, long nowTick, Port<S> port) {
        mint(sessionId, session, nowTick, port, List.of());
    }

    /**
     * Mint the admission cursor of a newly-live session: a SEAT segment over {@code seatSnapshot}
     * (held by reference, never mutated — one encode-once list may serve every session) + a RAPP
     * segment at log index 0, and flag the session. Names bound earlier by the reader thread are
     * adopted. A re-mint of a live cursor (never expected, ids are per-admission) restarts its
     * SEAT and RAPP walks and keeps its names. No-op when not {@link #paced()}.
     *
     */
    public void mint(long sessionId, S session, long nowTick, Port<S> port, List<byte[]> seatSnapshot) {
        if (!paced()) {
            return;
        }
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(seatSnapshot, "seatSnapshot");
        Cursor c = adopt(sessionId, session);
        synchronized (c) {
            c.startTick = nowTick;
            c.seat = seatSnapshot.isEmpty() ? null : seatSnapshot;
            c.seatNext = 0;
            c.rapp = true;
            c.rappCompleted = false;
            c.completionReported = false;
            c.next = 0;
            c.xfer = null;
            c.xferNext = 0;
            port.setFlag(session, true);
        }
    }

    /**
     * The round-wrap SEAT re-baseline as a segment: a session WITH a cursor gets its SEAT segment
     * REPLACED (the unsent remainder dropped — the client keeps the newest seat sequence); a live
     * session WITHOUT one gets a SEAT-only cursor, which never sets the catch-up flag. An empty
     * {@code frames} clears a pending SEAT segment and mints nothing. {@code frames} is held by
     * reference, never mutated. No-op when not {@link #paced()}.
     */
    public void replaceSeatSnapshot(long sessionId, S session, List<byte[]> frames, long nowTick,
            Port<S> port) {
        if (!paced()) {
            return;
        }
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(frames, "frames");
        Cursor c = cursors.get(sessionId);
        boolean fresh = c == null;
        if (fresh) {
            if (frames.isEmpty() || !port.isLive(session)) {
                return;
            }
            c = adopt(sessionId, session);
        }
        synchronized (c) {
            if (fresh) {
                c.startTick = nowTick;
            }
            c.seat = frames.isEmpty() ? null : frames;
            c.seatNext = 0;
        }
    }

    /**
     * Reader thread, once the door gathered the joiner's seat-name table (seat id → pre-encoded
     * seat-name frame; sent in ascending seat id): bind it as the SNMR segment. A cursor not yet
     * minted (the serve loop has not seen the session live) gets a pre-cursor the mint adopts.
     * CALLER CONTRACT: bind BEFORE marking the session provisioned (an unprovisioned cursor sends
     * and completes nothing, so the names are always bound before it can finish); hold no door
     * lock. false = no cursor will carry them (not {@link #paced()}, or the session's cursor
     * already finished) — the caller sends them the legacy way.
     */
    public boolean bindSeatNames(long sessionId, Map<Integer, byte[]> rows) {
        if (!paced()) {
            return false;
        }
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            return true;
        }
        LinkedHashMap<Integer, byte[]> ordered = new LinkedHashMap<>();
        for (Map.Entry<Integer, byte[]> e : new TreeMap<>(rows).entrySet()) {
            ordered.put(e.getKey(), Objects.requireNonNull(e.getValue(), "seat-name frame"));
        }
        Cursor c = index.get(sessionId);
        if (c == null) {
            Cursor fresh = new Cursor(sessionId);
            Cursor raced = index.putIfAbsent(sessionId, fresh);
            c = raced == null ? fresh : raced;
        }
        synchronized (c) {
            if (c.retired) {
                return false;
            }
            c.snmr = ordered;
            return true;
        }
    }

    /**
     * The live seat-name fan's hook (reader thread; the caller holds NO door lock — the monitor
     * precedes the door lock): under the session's monitor, REPLACE its UNSENT SNMR row for
     * {@code seatId} with {@code frame} (newest wins, delivered in the snapshot's turn) and return
     * true. false = no unsent row for that seat (already sent, no SNMR segment, no cursor, not
     * paced): the caller fans the frame live — CTRL is ordered, so it lands after the row the
     * cursor already sent.
     */
    public boolean replacePendingName(long sessionId, int seatId, byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        if (!paced()) {
            return false;
        }
        Cursor c = index.get(sessionId);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            if (c.retired || c.snmr == null || !c.snmr.containsKey(seatId)) {
                return false;
            }
            c.snmr.put(seatId, frame);
            return true;
        }
    }

    /** */
    public boolean active(long sessionId) {
        return cursors.containsKey(sessionId);
    }

    /** */
    public int activeCount() {
        return cursors.size();
    }

    /** */
    public Cursor cursorOrNull(long sessionId) {
        return cursors.get(sessionId);
    }

    /**
     * The admit-tick step for one session: up to {@code budget(headroom, cap, RESERVE)} frames
     * across its segments in order, bounded by what is left of this tick's {@link #GLOBAL_CAP}.
     *
     */
    public void drainOne(long sessionId, List<byte[]> rappLog, long nowTick, Port<S> port,
            Listener<S> listener) {
        Cursor c = cursors.get(sessionId);
        if (c == null) {
            return;
        }
        rollLedger(nowTick);
        c.steppedTick = nowTick;
        int b = stepBudget(c, port);
        if (b < 0) {
            cursors.remove(sessionId);
            abandonDead(c, rappLog, port, listener);
            return;
        }
        if (b == 0) {
            c.stalledTicks++;
            return;
        }
        // The ledger may already sit PAST the cap (an earlier step's XFER spill): n is then
        // negative and this step sends nothing; the spill itself is counted in full below.
        int n = Math.min(b, GLOBAL_CAP - ledgerSpent);
        int sent = 0;
        if (n > 0) {
            long t0 = System.nanoTime();
            sent = sendUpTo(c, n, rappLog, nowTick, port);
            noteDrain(t0, sent);
        }
        ledgerSpent += sent;
        if (n < b && c.hasPending()) {
            noteCapHit(nowTick);
        }
        if (sent > 0) {
            c.spoolMax = Math.max(c.spoolMax, port.spoolDepth(sessionOf(c)));
        }
        if (n > 0 && retireIfDone(c)) {
            cursors.remove(sessionId);
            finishCompleted(c, rappLog, port, listener);
        }
    }

    /**
     * One step for every active cursor (one call per serve tick) under the shared
     * {@link #GLOBAL_CAP}: each cursor's own budget is computed once, then the tick's remaining
     * global budget is split as a FAIR SHARE ({@code remaining / cursors} each), and what is left
     * is filled greedily round-robin from a start index that rotates every tick — no cursor
     * starves, the global budget is never exceeded and never left unused while a cursor can send.
     * A cursor already stepped this tick (its admit step) is skipped; one whose session left the
     * live map is abandoned {@code dead}; an unprovisioned one stalls. O(active cursors)
     * bookkeeping per tick.
     */
    public void drainAll(List<byte[]> rappLog, long nowTick, Port<S> port, Listener<S> listener) {
        drainCalls++;
        sweepIndex();
        if (cursors.isEmpty()) {
            return;
        }
        rollLedger(nowTick);
        scratch.clear();
        dead.clear();
        done.clear();
        for (Cursor c : cursors.values()) {
            if (c.steppedTick == nowTick) {
                continue;
            }
            c.steppedTick = nowTick;
            int b = stepBudget(c, port);
            if (b < 0) {
                dead.add(c);
            } else if (b == 0) {
                c.stalledTicks++;
            } else {
                c.tickBudget = b;
                c.tickSent = 0;
                scratch.add(c);
            }
        }
        int k = scratch.size();
        if (k > 0) {
            long t0 = System.nanoTime();
            int room0 = Math.max(0, GLOBAL_CAP - ledgerSpent);
            int room = room0;
            int start = Math.floorMod(rotation++, k);
            int share = room / k;
            // A grant may come back LARGER than asked (a RAPP completion sends its XFER snapshot
            // whole), so room can go negative: the fair-share pass still hands every cursor its
            // own share (one cursor's spill never starves the next), the greedy pass stops, and
            // the ledger adds what was really sent (room0 - room), never a clamped figure.
            if (share > 0) {
                for (int i = 0; i < k; i++) {
                    room -= give(scratch.get((start + i) % k), share, rappLog, nowTick, port);
                }
            }
            for (int i = 0; i < k && room > 0; i++) {
                room -= give(scratch.get((start + i) % k), room, rappLog, nowTick, port);
            }
            noteDrain(t0, room0 - room);
            if (room <= 0) {
                for (int i = 0; i < k; i++) {
                    Cursor c = scratch.get(i);
                    if (c.tickBudget > 0 && c.hasPending()) {
                        noteCapHit(nowTick);
                        break;
                    }
                }
            }
            ledgerSpent += room0 - room;
            for (int i = 0; i < k; i++) {
                Cursor c = scratch.get(i);
                if (c.tickSent > 0) {
                    c.spoolMax = Math.max(c.spoolMax, port.spoolDepth(sessionOf(c)));
                }
                if (retireIfDone(c)) {
                    done.add(c);
                }
            }
        }
        // Removed BEFORE the callbacks (a callback never mints).
        for (int i = 0; i < dead.size(); i++) {
            cursors.remove(dead.get(i).sessionId);
        }
        for (int i = 0; i < done.size(); i++) {
            cursors.remove(done.get(i).sessionId);
        }
        for (int i = 0; i < dead.size(); i++) {
            abandonDead(dead.get(i), rappLog, port, listener);
        }
        for (int i = 0; i < done.size(); i++) {
            finishCompleted(done.get(i), rappLog, port, listener);
        }
        scratch.clear();
        dead.clear();
        done.clear();
    }

    /** One grant inside drainAll: up to {@code min(want, the cursor's own budget left)}, plus a
     *  RAPP completion's whole XFER spill — so the return may exceed the grant and the cursor's
     *  budget may go negative, which only ends its grants for this tick (the {@code n <= 0}
     *  guard; the budget is re-set every tick). */
    private int give(Cursor c, int want, List<byte[]> log, long nowTick, Port<S> port) {
        int n = Math.min(want, c.tickBudget);
        if (n <= 0) {
            return 0;
        }
        int sent = sendUpTo(c, n, log, nowTick, port);
        c.tickBudget -= sent;
        c.tickSent += sent;
        return sent;
    }

    /** -1 = the session left the live map; 0 = stall (no window room, or not provisioned).
     * */
    private int stepBudget(Cursor c, Port<S> port) {
        S session = sessionOf(c);
        if (!port.isLive(session)) {
            return -1;
        }
        int headroom = port.provisioned(session) ? port.headroom(session) : 0;
        return budget(headroom, cap, RESERVE);
    }

    /**
     * Up to {@code n} frames across the segments in order, all under the cursor monitor. The RAPP
     * segment completes only inside a step with budget left (so the log end it saw is current):
     * the flag clears, the XFER snapshot is taken (once) and sent WHOLE in this same step — past
     * {@code n} if it must, so the return may exceed {@code n} by at most the snapshot's size (one
     * BEGIN per seat in flight at most; the step had budget only because the session's spool was
     * empty, so the spill cannot reach the spool bound) — then the step goes on into SNMR while
     * budget is left. WHY whole: a paced BEGIN could land after a later live COMMIT / ABORT of the
     * same transfer, which the live transfer fan no longer withholds once the flag is clear.
     * An SNMR row is removed and sent under the same monitor hold, so a racing
     * {@link #replacePendingName} either replaced it before the send or finds it gone.
     *
     */
    private int sendUpTo(Cursor c, int n, List<byte[]> log, long nowTick, Port<S> port) {
        S session = sessionOf(c);
        int sent = 0;
        synchronized (c) {
            while (sent < n) {
                if (c.seat != null && c.seatNext < c.seat.size()) {
                    port.sendSeat(session, c.seat.get(c.seatNext++));
                    sent++;
                } else if (c.rapp) {
                    if (c.next < size(log)) {
                        port.send(session, log.get(c.next++));
                        sent++;
                        c.rappSent++;
                    } else {
                        c.rapp = false;
                        c.rappCompleted = true;
                        port.setFlag(session, false);
                        completedThisRound++;
                        ticksMaxThisRound = Math.max(ticksMaxThisRound, nowTick - c.startTick);
                        List<byte[]> x = port.xferSnapshot(session);
                        c.xfer = x == null || x.isEmpty() ? null : x;
                        c.xferNext = 0;
                    }
                } else if (c.xfer != null && c.xferNext < c.xfer.size()) {
                    while (c.xferNext < c.xfer.size()) { // the whole segment, past n
                        port.send(session, c.xfer.get(c.xferNext++));
                        sent++;
                    }
                } else if (c.snmr != null && !c.snmr.isEmpty()) {
                    Iterator<byte[]> it = c.snmr.values().iterator();
                    byte[] frame = it.next();
                    it.remove();
                    port.send(session, frame);
                    sent++;
                } else {
                    break;
                }
            }
            c.sent += sent;
        }
        return sent;
    }

    /** Nothing pending ⇒ retired under the monitor (a racing bind then sees it and declines).
     * */
    private static boolean retireIfDone(Cursor c) {
        synchronized (c) {
            if (c.pendingLocked()) {
                return false;
            }
            c.retired = true;
            return true;
        }
    }

    /** The session left the live map: the whole cursor goes; the RAPP meter counts it only if a
     *  RAPP segment was still pending. */
    private void abandonDead(Cursor c, List<byte[]> log, Port<S> port, Listener<S> listener) {
        S session = sessionOf(c);
        boolean hadRapp;
        synchronized (c) {
            hadRapp = c.rapp;
            c.rapp = false;
            c.retired = true;
        }
        port.setFlag(session, false); // the invariant: every removal path clears the flag
        if (hadRapp) {
            abandonedThisRound++;
        }
        listener.abandoned(c.sessionId, session, c, size(log), "dead");
        release(c);
    }

    /** */
    private void finishCompleted(Cursor c, List<byte[]> log, Port<S> port, Listener<S> listener) {
        S session = sessionOf(c);
        port.setFlag(session, false); // the invariant: every removal path clears the flag
        boolean reported;
        synchronized (c) {
            reported = c.completionReported;
            c.completionReported = true;
        }
        if (!reported) {
            listener.completed(c.sessionId, session, c, size(log));
        }
        release(c);
    }

    /**
     * A retired cursor drops its session and every segment the moment it leaves the live map
     * (serve thread, after the listener saw it): the tombstone that stays in {@link #index} until
     * the sweep ages it out (so a late {@link #bindSeatNames} still answers false) is then a few
     * ints, never a dead {@code PeerSession} with its reliability rings and spool. WHY: the
     * delta lens found every admit-tick completion left its dead session
     * reachable for the serve's lifetime; row C10 pins {@link #retainedSessions} at 0.
     */
    private static void release(Cursor c) {
        synchronized (c) {
            c.session = null;
            c.seat = null;
            c.xfer = null;
            c.snmr = null;
        }
    }

    /** Index entries (live cursors + tombstones + pre-cursors): the sweep's bound, row C10. */
    public int indexedCursors() {
        return index.size();
    }

    /** Index entries still holding a session object: live cursors only once {@link #release}
     *  ran for every retired one (row C10: 0 after N admit-tick completions). */
    public int retainedSessions() {
        int n = 0;
        for (Cursor c : index.values()) {
            synchronized (c) {
                if (c.session != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * The round reset: drop ONLY the RAPP segment of every cursor (clearing its flag — a flag
     * surviving the round = a session that never receives a live RAPP again — and reporting it
     * {@code abandoned(..., "round_reset")}); SEAT / XFER / SNMR segments stay and keep draining
     * (nothing re-sends names at a wrap); a cursor left with nothing pending is removed with no
     * completion. Zeroes the round meters. Call AFTER the previous round's lines printed and
     * BEFORE the round-wrap re-announce fans (a still-flagged session would miss the re-announce,
     * which lands only in the dying log).
     *
     */
    public void resetRound(Port<S> port, Listener<S> listener, int oldLogSize) {
        Iterator<Cursor> it = cursors.values().iterator();
        while (it.hasNext()) {
            Cursor c = it.next();
            S session = sessionOf(c);
            boolean dropped;
            boolean empty;
            boolean walked;
            synchronized (c) {
                dropped = c.rapp;
                c.rapp = false;
                empty = !c.pendingLocked();
                walked = c.rappCompleted && !c.completionReported;
                if (walked) {
                    c.completionReported = true;
                }
                if (empty) {
                    c.retired = true;
                }
            }
            port.setFlag(session, false);
            if (dropped) {
                listener.abandoned(c.sessionId, session, c, oldLogSize, "round_reset");
            }
            if (walked) {
                // A cursor whose RAPP walked the dying log but still
                // drains SEAT / SNMR reports its catch-up NOW, in the round that completed it (the
                // walked log's size, this round's frame — never a later round's frame and a negative
                // ticks=); its retirement after the wrap then reports nothing more (once per cursor).
                listener.completed(c.sessionId, session, c, oldLogSize);
            }
            if (empty) {
                it.remove();
                release(c);
            }
        }
        completedThisRound = 0;
        ticksMaxThisRound = 0L;
        abandonedThisRound = 0;
        globalCapTicksThisRound = 0;
        drainNanosThisRound = 0L;
        drainSendsThisRound = 0L;
    }

    /** Round-line tokens (appended after roundCounterTail at the call sites);
     *  {@code cursor_global_budget_ticks} = ticks this round whose {@link #GLOBAL_CAP} ran out
     *  while a cursor could still send; {@code cursor_drain_us} / {@code cursor_drain_sends} = the
     *  send measure ({@link #drainNanosThisRound}). */
    public String roundTokens(List<byte[]> log) {
        return " rapp_log_frames=" + (log == null ? 0 : log.size())
                + " rapp_catchups=" + completedThisRound
                + " rapp_catchup_ticks_max=" + ticksMaxThisRound
                + " rapp_catchup_abandoned=" + abandonedThisRound
                + " cursor_global_budget_ticks=" + globalCapTicksThisRound
                + " cursor_drain_us=" + drainNanosThisRound / 1000L
                + " cursor_drain_sends=" + drainSendsThisRound;
    }

    /** One timed drain step: accumulated only when it sent (an empty step leaves the measure
     *  untouched). */
    private void noteDrain(long t0, int sent) {
        if (sent > 0) {
            drainNanosThisRound += System.nanoTime() - t0;
            drainSendsThisRound += sent;
        }
    }

    /** RAPP segments that walked to the log's end this round. */
    public int completedThisRound() { return completedThisRound; }

    /** */
    public long ticksMaxThisRound() { return ticksMaxThisRound; }

    /** Cursors that died holding a RAPP segment this round. */
    public int abandonedThisRound() { return abandonedThisRound; }

    /** */
    public int globalCapTicksThisRound() { return globalCapTicksThisRound; }

    /** Serve thread: the session's cursor, minted (or a pre-cursor adopted, or a tombstone
     *  replaced), registered in mint order. */
    private Cursor adopt(long sessionId, S session) {
        Cursor c = index.get(sessionId);
        if (c == null) {
            Cursor fresh = new Cursor(sessionId);
            Cursor raced = index.putIfAbsent(sessionId, fresh);
            c = raced == null ? fresh : raced;
        }
        if (c.retired) {
            // Only this thread retires; a reader holding the tombstone sees it retired (false).
            c = new Cursor(sessionId);
            index.put(sessionId, c);
        }
        c.sweepStamp = Long.MIN_VALUE;
        synchronized (c) {
            c.minted = true;
            c.session = session;
        }
        cursors.put(sessionId, c);
        return c;
    }

    /** Drop index entries that are retired or were never minted once they aged
     *  {@link #TOMBSTONE_DRAINS} drain calls (a bind for a session that died before its mint).
     * */
    private void sweepIndex() {
        if (index.size() == cursors.size()
                || (lastSweep != Long.MIN_VALUE && drainCalls - lastSweep < SWEEP_EVERY_DRAINS)) {
            return;
        }
        lastSweep = drainCalls;
        Iterator<Cursor> it = index.values().iterator();
        while (it.hasNext()) {
            Cursor c = it.next();
            if (c.minted && !c.retired) {
                continue;
            }
            if (c.sweepStamp == Long.MIN_VALUE) {
                c.sweepStamp = drainCalls;
            } else if (drainCalls - c.sweepStamp >= TOMBSTONE_DRAINS) {
                synchronized (c) {
                    c.retired = true;
                    c.snmr = null;
                }
                it.remove();
            }
        }
    }

    /** The per-tick global send ledger (admit steps and the drain share it); counted once per
     *  tick when it bites. */
    private void rollLedger(long nowTick) {
        if (ledgerTick != nowTick) {
            ledgerTick = nowTick;
            ledgerSpent = 0;
        }
    }

    private void noteCapHit(long nowTick) {
        if (capHitTick != nowTick) {
            capHitTick = nowTick;
            globalCapTicksThisRound++;
        }
    }

    @SuppressWarnings("unchecked")
    private S sessionOf(Cursor c) {
        return (S) c.session;
    }

    private static int size(List<byte[]> log) {
        return log == null ? 0 : log.size();
    }
}
