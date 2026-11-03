package cc.starsector.client;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import com.fs.starfarer.api.combat.ShipAPI;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.replica.ReplicaPilotWindow;

/**
 * The remote pilot mark. The server marks every
 * human-flown hull with {@code customData["cc_client.pilot"] = int[]{seat, heldAbilityBits}} so
 * a mod's ship system can tell "a player flies this hull, obey the held bits" from "AI-flown,
 * run the AI" (the unmodified mod subsystem gates its {@code shouldActivateAI} on that array).
 * On another player's client the same hull is a REPLICA nobody marks, so the mod ran its AI
 * there and presented an ability the pilot never pressed. The AWSD pilot triple carries the
 * serve's mark to every client; this class re-publishes it on each REMOTE body, so the same
 * mod code reads the same pilot on every machine.
 *
 * <p><b>The own ship is NEVER written</b> (identity skip, every frame, against both the bound
 * own ship and {@code engine.getPlayerShip()}): the own client's ability state is read from the
 * local body, and a mark written there would feed the server's echo back into the local read.
 * Neither is the hull this client's OWN SEAT flies (a pose whose seat is the one passed to
 * {@link #beginFrame(ShipAPI, ShipAPI, int)}): while it is being adopted it is not yet bound as the
 * own ship, yet it is answered from the local body all the same, so the same law holds there; a
 * tenure already on it is left unrefreshed and {@link #endFrame} sweeps it the same frame.
 *
 * <p><b>Pose → action:</b> seat {@code ≥ 1} publishes ONE {@code int[]{seat, mask,
 * ROLE_REPLICA, flags}} per body tenure ({@code flags} = the serve's mark flags off the wire's
 * pilot triple, bit 0 = the serve follows the pilot's MagicLib decision on this hull, so this
 * window's MagicLib hook does the same; bit 1 = the wire carries the pilot's held controls, and
 * then the array is {@code int[]{seat, mask, ROLE_REPLICA, flags, controls}}) and rewrites
 * {@code [0]}/{@code [1]}/{@code [3]} (and {@code [4]} when present) in place each frame (mask
 * 0 KEEPS the array — "marked, nothing held" is how the mod cancels an active toggle); a bit-1
 * edge is the one change that cannot ride in place (the length follows the bit), so it publishes
 * a fresh array of the new shape; seat
 * {@code 0} removes OUR array only ({@code Map.remove(key, ours)}); {@link
 * ServerEntityPose#PILOT_UNKNOWN} (an AWSS-sourced pose, no pilot tier) HOLDS. A value under the
 * key that is not ours (foreign) is never touched. The poses are held-last between datagrams
 * and the caller walks its bodies every frame, so a stale feed HOLDS marks; only a body that
 * left the walk (dead, unbound, adopted as own, gone from the registry) is swept by {@link
 * #endFrame}, the frame it leaves, before the mod's next AI query.
 *
 * <p><b>the replica window's index</b>: every array
 * this class publishes is handed to {@link ReplicaPilotWindow#markPublished} and every edge where
 * a tenure ends or the key stops being ours (seat 0, a foreign takeover, a failed publish, the
 * sweep, the round wrap) calls {@link ReplicaPilotWindow#markRemoved}. The index holds the SAME
 * array this class rewrites in place, so a window opened for the hull reads {@code [4]} live off
 * the body's own mark, never a copy that lags the wire; a removal edge tells the index
 * unconditionally (a ship it never indexed is a no-op there), so it never answers for a hull whose
 * key is no longer ours.
 *
 * <p><b>The first write is {@code setCustomData}</b>: the engine's {@code getCustomData()}
 * returns a fresh DETACHED map while the hull's map is still null,
 * so a {@code get().put} would vanish. The write is read back;
 * a mismatch is counted {@code pilot_mark_lost} and the tenure is dropped (retried on the next
 * maintained frame — an engine never shaped like that; the meter makes it loud).
 *
 * <p>Render thread only. Per body per frame = one identity lookup + one map read and up to four
 * int stores while marked; the only allocations are the tenure holder + its array, once per
 * tenure (one more array at each controls-bit edge), and one iterator per frame while a tenure
 * exists; the index is told only on a publish or removal edge (an identity scan over at most its
 * capacity), never on the steady in-place frame.
 */
final class RemotePilotMarkLane {

    /**
     * The mark's customData key — the server's {@code PilotCommandApplier} key, spelled as a
     * literal because the server constant is not on the client kit's classpath.
     */
    static final String KEY = "cc_client.pilot";

    /**
     * {@code mark[2]} on a mark THIS client wrote: the array's role is "a client replica of
     * the serve's mark". The mark contract is append-only (a reader takes {@code length >= 2};
     * the MagicLib hook takes {@code length >= 4}): the serve's own-hull mark is {@code {seat,
     * mask, 0, flags}} and the replica {@code {seat, mask, 1, flags}} (the pilot contract's mark clause),
     * so {@code [2] == 1} lets a mod tell it runs on a client replica — e.g. to skip a side
     * effect (an entity spawn) the server already performs; {@code [3]}
     * bit 0 = FOLLOW (the serve's patch follows this pilot's MagicLib decision); {@code [3]} bit 1
     * ({@link #FLAG_CONTROLS}) ⇒ a fifth slot {@code [4]} = the pilot's held controls, so the
     * replica window's predicate takes {@code length >= 5}.
     */
    static final int ROLE_REPLICA = 1;

    /**
     * {@code mark[3]} bit 1: the wire carried the pilot's
     * held controls u16 (bit i = the pilot holds their own binding of control i; range 0..0xFFFF),
     * so the mark is five ints with {@code [4]} = that u16. The length follows the bit, never a
     * zero filler: a reader that takes {@code length >= 5} reads only controls the serve actually
     * sent, so a hull whose pilot's keys are not on the wire is never answered as "holds nothing".
     *
     */
    static final int FLAG_CONTROLS = 0x02;

    /**
     * The replica's held-controls edge log arm
     * ({@code -Dcc.pilot.keyEdgeLog=true}, read ONCE at class init; default OFF, and OFF costs one
     * field test per {@code [4]} write). Armed, every bit that changes in a mark's {@code [4]} is one
     * watch row, so the control edges this window saw can be counted beside the serve's own edge
     * rows for the same seat: a tap the serve acted on with no row here never reached the mark (it
     * fell between two mark writes), while one with a row here that the window never acted on was
     * lost after the mark. Package-private and non-final so the
     * game-free smoke arms it for its row (a sysprop set after class init would be missed).
     */
    static boolean keyEdgeLog = Boolean.getBoolean("cc.pilot.keyEdgeLog");

    /** The edge log's process-wide row budget; past it ONE cap line, then silence. */
    static final int KEY_EDGE_ROW_CAP = 4096;

    /** Rows the edge log has written in this process (render thread only, like the lane). */
    private static int keyEdgeRows;
    private static boolean keyEdgeCapLogged;

    /** One body's tenure: OUR array ({@code null} while a foreign value owns the key) + the last frame it was refreshed. */
    private static final class Tenure {
        int[] mark;
        long refreshedFrame;

        Tenure(int[] mark, long refreshedFrame) {
            this.mark = mark;
            this.refreshedFrame = refreshedFrame;
        }
    }

    private final IdentityHashMap<ShipAPI, Tenure> tenures = new IdentityHashMap<ShipAPI, Tenure>();
    private long frame;
    private ShipAPI own;
    private ShipAPI player;
    /** The seat this client flies this frame: 1..255 = a pilot seat; 0 (observer) or -1 (unknown) = none. */
    private int ownSeat = -1;
    private int writes;
    private int removes;
    private int sweeps;
    private int holds;
    private int lost;
    private int foreign;
    private int ownSkips;
    /** The edge log's {@code ms=} zero: the first armed {@link #beginFrame} of this lane (one lane per combat). */
    private long edgeClockNanos;
    private boolean edgeClockStarted;

    /** Once per frame, before the remote walk: the identity-skip pair (either may be null), no own seat known. */
    void beginFrame(ShipAPI ownShip, ShipAPI playerShip) {
        beginFrame(ownShip, playerShip, -1);
    }

    /**
     * Once per frame, before the remote walk: the
     * identity-skip pair (either may be null) and the seat this client flies (signed; {@code ≥ 1}
     * = a pilot seat, {@code ≤ 0} = none: an observer or a caller that does not know it). A pose
     * naming that seat is the hull this client is adopting, skipped like the own ship (class doc).
     */
    void beginFrame(ShipAPI ownShip, ShipAPI playerShip, int ownSeat) {
        frame++;
        own = ownShip;
        player = playerShip;
        this.ownSeat = ownSeat;
        // The edge log's ms zero, stamped once per lane.
        if (keyEdgeLog && !edgeClockStarted) {
            edgeClockStarted = true;
            edgeClockNanos = System.nanoTime();
        }
    }

    private boolean isOwn(ShipAPI body) {
        return body == own || body == player;
    }

    /** Per remote body per frame: publish, rewrite, remove or hold from the body's own pose. */
    void maintain(ShipAPI body, ServerEntityPose pose) {
        if (body == null || pose == null) {
            return;
        }
        if (isOwn(body)) {
            ownSkips++;
            return;
        }
        Tenure t = tenures.get(body);
        int seat = pose.pilotSeat();
        if (ownSeat >= 1 && seat == ownSeat) {
            // The hull this client's own seat flies: answered
            // from the local body, so a replica mark there would feed the server's echo into the local
            // read (the own-ship law). A tenure already here is NOT refreshed, so endFrame removes OUR
            // array and takes the hull out of the replica window's index this same frame.
            ownSkips++;
            return;
        }
        if (seat < 0) {
            if (t != null) {
                t.refreshedFrame = frame;
                holds++;
            }
            return;
        }
        if (seat == 0) {
            if (t != null) {
                tenures.remove(body);
                if (t.mark != null && body.getCustomData().remove(KEY, t.mark)) {
                    removes++;
                }
                ReplicaPilotWindow.markRemoved(body);
            }
            return;
        }
        int mask = pose.pilotMask();
        int flags = pose.pilotFlags();
        int controls = pose.pilotControls() & 0xFFFF;
        if (t == null) {
            Object present = body.getCustomData().get(KEY);
            if (present != null) {
                tenures.put(body, new Tenure(null, frame));
                foreign++;
                return;
            }
            int[] ours = newMark(seat, mask, flags, controls);
            if (publish(body, ours)) {
                tenures.put(body, new Tenure(ours, frame));
                ReplicaPilotWindow.markPublished(body, ours);
                if (keyEdgeLog && ours.length >= 5) {
                    logControlEdges(seat, 0, controls); // a fresh mark's [4] rises from nothing held
                }
            }
            return;
        }
        t.refreshedFrame = frame;
        Object present = body.getCustomData().get(KEY);
        int[] old = t.mark;
        if (old == null) {
            // Foreign tenure: never touched; the key is claimed only once its owner let go.
            if (present == null) {
                int[] ours = newMark(seat, mask, flags, controls);
                if (publish(body, ours)) {
                    t.mark = ours;
                    ReplicaPilotWindow.markPublished(body, ours);
                    if (keyEdgeLog && ours.length >= 5) {
                        logControlEdges(seat, 0, controls);
                    }
                } else {
                    tenures.remove(body);
                    ReplicaPilotWindow.markRemoved(body);
                }
            }
            return;
        }
        if (present != old && present != null) {
            // Someone else took the key over: it is theirs now, ours is abandoned, and the replica
            // window must stop answering for this hull off an array no mod reads any more.
            t.mark = null;
            foreign++;
            ReplicaPilotWindow.markRemoved(body);
            return;
        }
        boolean reshape = ((flags & FLAG_CONTROLS) != 0) != (old.length >= 5);
        if (present == null || reshape) {
            // Our array was removed under us (republish the
            // SAME array; the tenure stands) or the controls bit changed (the length follows the bit,
            // so a fresh array of the new shape replaces ours). Either way the index is told the array
            // now live. A publish that does not read back drops the tenure: OUR old array leaves the
            // key (never a stale shape left behind for a mod to read) and the index forgets the hull.
            int[] next = reshape ? newMark(seat, mask, flags, controls) : old;
            if (!publish(body, next)) {
                body.getCustomData().remove(KEY, old);
                tenures.remove(body);
                ReplicaPilotWindow.markRemoved(body);
                return;
            }
            t.mark = next;
            ReplicaPilotWindow.markPublished(body, next);
        }
        int[] m = t.mark;
        m[0] = seat;
        m[1] = mask;
        m[3] = flags;
        if (m.length >= 5) {
            if (keyEdgeLog) {
                // A reshape's fresh array already holds this frame's controls: its edges count from 0.
                logControlEdges(seat, m == old ? m[4] : 0, controls);
            }
            m[4] = controls;
        }
    }

    /**
     * One watch row per bit that differs between the
     * controls a mark held before this write ({@code before}, 0 for a fresh mark) and the ones
     * written ({@code after}); both u16, 0..0xFFFF. {@code frame} = this lane's beginFrame count,
     * {@code ms} = since its first armed beginFrame, {@code seat} = the mark's seat (1..255). A
     * mark that leaves (seat 0, sweep, round wrap) writes no row. Process budget {@link
     * #KEY_EDGE_ROW_CAP}, then one cap line.
     */
    private void logControlEdges(int seat, int before, int after) {
        int changed = (before ^ after) & 0xFFFF;
        if (changed == 0) {
            return;
        }
        long ms = (System.nanoTime() - edgeClockNanos) / 1_000_000L;
        String hex = Integer.toHexString(0x10000 | (after & 0xFFFF)).substring(1);
        while (changed != 0) {
            int bit = Integer.numberOfTrailingZeros(changed);
            changed &= changed - 1;
            if (keyEdgeRows >= KEY_EDGE_ROW_CAP) {
                if (!keyEdgeCapLogged) {
                    keyEdgeCapLogged = true;
                    PilotWatchLog.info("key-edge ROW CAP reached (" + KEY_EDGE_ROW_CAP + "); further rows suppressed");
                }
                return;
            }
            keyEdgeRows++;
            PilotWatchLog.info("predict replica key-edge frame=" + frame + " ms=" + ms + " seat=" + seat
                    + " bit=" + bit + (((after >>> bit) & 1) != 0 ? " rise" : " fall") + " controls=0x" + hex);
        }
    }

    /**
     * A fresh replica mark in the shape the wire's flags
     * call for: five ints with {@code [4] = controls} iff flags bit 1 ({@link #FLAG_CONTROLS}),
     * else four.
     */
    private static int[] newMark(int seat, int mask, int flags, int controls) {
        return (flags & FLAG_CONTROLS) != 0
                ? new int[] {seat, mask, ROLE_REPLICA, flags, controls}
                : new int[] {seat, mask, ROLE_REPLICA, flags};
    }

    /** setCustomData + read-back identity check (see class doc); true when the mark is live on the body. */
    private boolean publish(ShipAPI body, int[] ours) {
        body.setCustomData(KEY, ours);
        if (body.getCustomData().get(KEY) != ours) {
            lost++;
            return false;
        }
        writes++;
        return true;
    }

    /**
     * A live, bound remote body whose pose is ABSENT this frame (a bounded absence hold): its
     * tenure counts as refreshed — absence inside a hold is not a vacate. The own ship is
     * never refreshed (it stays subject to the sweep).
     */
    void hold(ShipAPI body) {
        if (body == null) {
            return;
        }
        if (isOwn(body)) {
            ownSkips++;
            return;
        }
        Tenure t = tenures.get(body);
        if (t != null) {
            t.refreshedFrame = frame;
            holds++;
        }
    }

    /** Once per frame, after the remote walk: every tenure neither maintained nor held this frame loses OUR array. */
    void endFrame() {
        if (tenures.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<ShipAPI, Tenure>> it = tenures.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, Tenure> e = it.next();
            Tenure t = e.getValue();
            if (t.refreshedFrame != frame) {
                // The key is read BEFORE it.remove(): an IdentityHashMap entry is dead once its
                // iterator removed it (its getKey throws IllegalStateException).
                ShipAPI body = e.getKey();
                if (t.mark != null) {
                    body.getCustomData().remove(KEY, t.mark);
                }
                it.remove();
                sweeps++;
                ReplicaPilotWindow.markRemoved(body);
            }
        }
    }

    /** Round wrap (before the unbind / evict loops, where no {@link #endFrame} follows): every tenure ends, OUR arrays removed. */
    void releaseAll() {
        for (Map.Entry<ShipAPI, Tenure> e : tenures.entrySet()) {
            int[] mark = e.getValue().mark;
            if (mark != null) {
                e.getKey().getCustomData().remove(KEY, mark);
            }
            ReplicaPilotWindow.markRemoved(e.getKey());
            sweeps++;
        }
        tenures.clear();
    }

    /**
     * Cumulative tokens (never reset on read): writes = marks published (a controls-bit reshape
     * is one), removes = OUR arrays removed on a seat-0 pose, sweeps = tenures ended by {@link
     * #endFrame} or {@link #releaseAll}, holds = held tenure-frames (UNKNOWN pose or absence
     * hold), lost = writes that did not read back, foreign = tenures that found a value not ours,
     * own_skips = calls skipped for the own ship or the own seat's hull.
     */
    String meterTokens() {
        return " pilot_mark_writes=" + writes
                + " pilot_mark_removes=" + removes
                + " pilot_mark_sweeps=" + sweeps
                + " pilot_mark_holds=" + holds
                + " pilot_mark_lost=" + lost
                + " pilot_mark_foreign=" + foreign
                + " pilot_mark_own_skips=" + ownSkips;
    }

    /** Its own ungated line in the per-lane meter flush (the grader reads the tokens by name). */
    void logMeter(String where) {
        PilotWatchLog.info("predict pilot-mark meter [" + where + "]:" + meterTokens());
    }

    int tenureCount() {
        return tenures.size();
    }
}
