package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;

/**
 * The travel-drive fire lock, client half.
 * The server stamps {@link ServerEntityPose#FLAG_DRIVE_LOCK} on a seat hull while its TRAVEL DRIVE
 * blocks firing (the fly-in burn, a retreat burn — vanilla's own click clause).
 * The client's engine does not run that drive in the server's
 * window (the round-0 adopt kills the local drive; remount / wrap mints are offset), so without this
 * lane an in-burn trigger fires locally and the server refuses it: a client-only phantom.
 *
 * <p><b>Hold</b> ({@link #hold}): while a FRESH, alive, flags-known own pose carries the bit, each
 * frame calls {@code blockCommandForOneFrame(USE_SELECTED_GROUP)} (the API block of either fire verb
 * adds both) and {@code setHoldFireOneFrame(true)} (autofire
 * groups) — the same two calls the engine's own {@code noFiring} lock makes.
 * One-frame flags: the plugin re-asserts them every frame ahead of every advance early-return, so the
 * refusal holds under either plugin / ship advance ordering. API verbs only — no reflection (the mod
 * classloader's deny list). The body the trigger reaches is {@code engine.getPlayerShip()} (the trigger-body law,
 * {@code CombatState.controlPlayerShip}); when the own hull is held and the player ship is a different
 * non-pod body (a displaced player ship — the state {@code CommandTransferLane.maintainStuckWatchdog}
 * watches for), BOTH are held. A body the engine calls a shuttle pod ({@code isShuttlePod()} — vanilla's
 * {@code shuttlepod_xfer}; the {@link ClaimPlaceholderHold} skip) is never held, and with such an own
 * body nothing is; the mod's own {@code cc_pod} body is an ordinary hull to this test and IS held.
 *
 * <p><b>Release = fail-OPEN:</b> a stale snapshot (the plugin's 2 s wall gate, no fresh frame yet
 * included), a missing pose / body, a dead pose, unknown flags — never a dead gun on a frozen flag. A
 * flux-less DELTA frame is NOT stale: a delta pose inherits its anchor's flags
 * ({@code AwsdSnapshotReader}), so there is no per-delta freshness test here.
 *
 * <p><b>Wire sample</b> ({@link #gateWireFire}): while the lane HELD on its latest frame, the wire fire
 * level reads neutral — called AFTER the autopilot fire fold (a burst in flight would re-raise it) and
 * BEFORE the fire-edge counter.
 *
 * <p><b>Falling edge</b>: on the first fresh, alive, flags-known own pose that reads
 * the bit CLEAR after the previous such pose read it SET, a locally-ON travel drive is turned off ONCE
 * ({@code turnOffTravelDrive()}, the {@code RejoinRebaselineLane} adopt verb) — the server's drive ended
 * (burn over, a collision abort, a flameout) and the local drive is re-sourced from that truth. Never a
 * level rule: the client's own accepted retreat starts its drive locally before the server's, and an old
 * server never sets the bit. The previous-bit memory forgets on a missing / dead own pose and at the round
 * reset ({@link #release}); a stale frame, unknown flags, or a missing / pod body leave it as it was.
 *
 * <p><b>Unpaired burn</b> (the C8 residual's meter): a life whose fresh own poses
 * read CLEAR and never SET while the LOCAL travel drive is on (and the hull is not retreating — the own
 * retreat starts its drive locally first) has no edge to fire: the local burn runs out on its own clock.
 * Each such episode counts once in {@code drive_lock_unpaired=} and logs one row (its tick span) when it
 * CLOSES unpaired — the local burn ended, or the life did. A SET arriving while it
 * is open pairs it (the wrap mint reads CLEAR, then SET) and the round reset forgets it: neither counts.
 * Nothing is changed.
 *
 * <p>Render thread only; per frame = one cached pose lookup (a re-published frame is not re-scanned),
 * a few field tests, no allocation; log rows on edges only, capped per round.
 */
final class DriveLockLane {

    /**
     * Edge log rows per round (reset by {@link #release}). 64 = ~30 fly-ins a round (an ON + an OFF row
     * each, plus edge / unpaired rows): 16 covered 8 and a 9-life round lost its last windows.
     * The row that takes the last slot says so.
     */
    private static final int LOG_MAX_PER_ROUND = 64;

    private static final String WHY_HELD = "held";
    private static final String WHY_NO_POSE = "no-pose";
    private static final String WHY_DEAD = "dead";
    private static final String WHY_STALE = "stale";
    private static final String WHY_UNKNOWN = "unknown-flags";
    private static final String WHY_NO_BODY = "no-body";
    private static final String WHY_POD = "shuttle-pod";
    private static final String WHY_CLEAR = "bit-clear";
    private static final String WHY_THREW = "threw";

    /** True when the latest {@link #hold} held — what {@link #gateWireFire} reads. */
    private boolean heldLastFrame;
    /** {@link #heldLastFrame} as it stood before the latest {@link #hold}: the hold that governed a shot the scan lane sees a frame late. */
    private boolean heldPrevFrame;
    /** Own projectile discharges born under a held frame ({@link #onOwnDischarge}). */
    private long firedInLock;
    /** The previous fresh, alive, flags-known own pose read the bit SET (the falling-edge memory). */
    private boolean prevBitSet;
    /** A fresh, alive, flags-known own pose read the bit SET this life (forgets with {@link #prevBitSet}'s pose rules). */
    private boolean sawSetThisLife;
    /** An unpaired-candidate episode is open: local burn on, bit CLEAR, no SET this life. */
    private boolean unpairedOpen;
    private long unpairedOpenTick;
    private int unpaired;
    private long holdFrames;
    /** Wire fire samples that read PRESSED while the lane held ({@link #gateWireFire} turned them neutral). */
    private long pressedFrames;
    private int driveOffs;
    private int logRows;
    private boolean failLogged;
    private String lastWhy = WHY_NO_POSE;

    private ServerSnapshotFrame cachedFrame;
    private long cachedShipId;
    private ServerEntityPose cachedPose;

    /**
     * The plugin's per-frame entry: resolves the own pose from the PUBLISHED frame ({@code frame} may be
     * null pre-feed; {@code shipId} > 0 for a hull holder, 0 = observer / no session ⇒ no pose ⇒ release)
     * and runs one {@link #hold}. Returns true the frame it held.
     */
    boolean holdFrom(ShipAPI own, ShipAPI player, ServerSnapshotFrame frame, long shipId, boolean stale) {
        ServerEntityPose pose;
        if (frame == null || shipId <= 0L) {
            pose = null;
        } else if (frame == cachedFrame && shipId == cachedShipId) {
            pose = cachedPose;
        } else {
            pose = frame.entityById(shipId);
            cachedFrame = frame;
            cachedShipId = shipId;
            cachedPose = pose;
        }
        return hold(own, player, pose, stale, frame != null ? frame.simTick() : -1L);
    }

    /**
     * One frame of the lock over {@code own} (and {@code player} when it is another body); {@code pose}
     * = the latest own pose (null = none), {@code stale} = no fresh frame within the plugin's wall gate,
     * {@code simTick} = the published frame's tick (log only). Returns true the frame it held.
     */
    boolean hold(ShipAPI own, ShipAPI player, ServerEntityPose pose, boolean stale, long simTick) {
        boolean held;
        try {
            held = step(own, player, pose, stale, simTick);
        } catch (Throwable t) {
            held = false;
            lastWhy = WHY_THREW;
            if (!failLogged) {
                failLogged = true;
                PilotWatchLog.error("drive-lock: hold threw (the own trigger stays free this frame;"
                        + " one-shot log)", t);
            }
        }
        if (held != heldLastFrame && logOpen()) {
            logRow("drive-lock hold " + (held ? "ON" : "OFF") + " why=" + lastWhy + " tick=" + simTick);
        }
        heldPrevFrame = heldLastFrame;
        heldLastFrame = held;
        return held;
    }

    /**
     * The hull-swap re-assert. {@link #hold} runs once a frame on the body that is
     * own THEN; the claim fields the seat hull LATER in the same frame (the held body was the placeholder), so
     * without this the fresh hull's first advance carries no flag and a pressed trigger fires one volley the
     * server refuses (the round-0 kit volley: {@code client_only=3} on every rig leg). Called by the one
     * own-seat mint right after the player-ship re-point: the same two verbs as the hold and nothing else — no
     * state, no row, no count. Does nothing unless the latest hold HELD (the pose that hold read is the minted
     * hull's own: one seat, one hull id). A respawn or match-reset mint CAN find it held (the corpse is still
     * own at a respawn; the round reset releases the lane AFTER its spawns): that hull gets one frame of the same
     * block while its local fly-in drive already blocks fire — no visible effect (delta).
     */
    void reassertOn(ShipAPI body) {
        if (!heldLastFrame || body == null) {
            return;
        }
        try {
            if (body.isShuttlePod()) {
                return;
            }
            body.blockCommandForOneFrame(ShipCommand.USE_SELECTED_GROUP);
            body.setHoldFireOneFrame(true);
        } catch (Throwable t) {
            if (!failLogged) {
                failLogged = true;
                PilotWatchLog.error("drive-lock: the hull-swap re-assert threw (the fresh hull's trigger stays"
                        + " free this frame; one-shot log)", t);
            }
        }
    }

    /**
     * The kit-side witness the rig's R-LOCK lacked (it read the server reel only): an
     * own PROJECTILE discharge born under a held frame counts into {@code drive_lock_fired=}. The birth callback
     * runs inside the ship's advance, governed by the latest hold; {@code seenByScan} = the scan lane found the
     * projectile one plugin frame after its birth, so the PREVIOUS hold governed it. Expected 0 — the hold blocks
     * both fire verbs — so a count is a body that advanced unheld (the hull-swap class) or a fire path the two
     * verbs do not cover. A beam has no birth here and is not counted.
     */
    void onOwnDischarge(boolean seenByScan) {
        if (seenByScan ? heldPrevFrame : heldLastFrame) {
            firedInLock++;
        }
    }

    private boolean step(ShipAPI own, ShipAPI player, ServerEntityPose pose, boolean stale, long simTick) {
        if (pose == null || !(pose.hitpoints() > 0f)) {
            lastWhy = pose == null ? WHY_NO_POSE : WHY_DEAD;
            closeUnpaired(simTick, lastWhy); // the life ended with its local burn never covered
            prevBitSet = false;
            sawSetThisLife = false;
            return false;
        }
        if (stale) {
            lastWhy = WHY_STALE;
            return false;
        }
        if (!pose.hasFluxFlags()) {
            lastWhy = WHY_UNKNOWN;
            return false;
        }
        if (own == null) {
            lastWhy = WHY_NO_BODY;
            return false;
        }
        if (own.isShuttlePod()) {
            lastWhy = WHY_POD;
            return false;
        }
        if (!pose.isDriveLockBit()) {
            lastWhy = WHY_CLEAR;
            if (prevBitSet) {
                prevBitSet = false;
                ShipSystemAPI drive = own.getTravelDrive();
                if (drive != null && drive.isOn()) {
                    own.turnOffTravelDrive();
                    driveOffs++;
                    if (logOpen()) {
                        logRow("drive-lock falling edge: the server's drive ended, local travel drive OFF"
                                + " tick=" + simTick);
                    }
                }
            } else if (!sawSetThisLife) {
                // C8: a local burn the server's bit never covered this life.
                // Three getter reads on a CLEAR frame, no allocation. The episode OPENS
                // here and counts only when it closes unpaired (a SET below pairs it instead).
                ShipSystemAPI drive = own.getTravelDrive();
                boolean localBurn = drive != null && drive.isOn() && !own.isRetreating();
                if (!localBurn) {
                    closeUnpaired(simTick, "burn-over");
                } else if (!unpairedOpen) {
                    unpairedOpen = true;
                    unpairedOpenTick = simTick;
                }
            }
            return false;
        }
        prevBitSet = true;
        sawSetThisLife = true;
        unpairedOpen = false; // the server's bit arrived: the open local burn is paired, not C8
        lastWhy = WHY_HELD;
        own.blockCommandForOneFrame(ShipCommand.USE_SELECTED_GROUP);
        own.setHoldFireOneFrame(true);
        if (player != null && player != own && !player.isShuttlePod()) {
            player.blockCommandForOneFrame(ShipCommand.USE_SELECTED_GROUP);
            player.setHoldFireOneFrame(true);
        }
        holdFrames++;
        return true;
    }

    /**
     * The wire fire sample: {@code derivedHeld} (mouse-0 post-swallow OR the autopilot fold) passes
     * through unless the latest {@link #hold} held, then it reads neutral (the fire-swallow precedent).
     * A pressed sample turned neutral counts into {@code drive_lock_pressed_frames=} — the only record
     * that a trigger was DOWN inside the lock (the edge counter sits after this gate).
     */
    boolean gateWireFire(boolean derivedHeld) {
        if (derivedHeld && heldLastFrame) {
            pressedFrames++;
            return false;
        }
        return derivedHeld;
    }

    /** An open episode ended with no SET this life — count it once, with its tick span. */
    private void closeUnpaired(long simTick, String how) {
        if (!unpairedOpen) {
            return;
        }
        unpairedOpen = false;
        unpaired++;
        if (logOpen()) {
            logRow("drive-lock unpaired: a local travel-drive burn ended (" + how + ") with the server's bit"
                    + " never SET this life ticks=" + unpairedOpenTick + ".." + simTick);
        }
    }

    /** Round reset: forget the held state, the falling-edge memory, an open unpaired episode (uncounted) and the cached pose. */
    void release() {
        heldLastFrame = false;
        heldPrevFrame = false;
        prevBitSet = false;
        sawSetThisLife = false;
        unpairedOpen = false;
        lastWhy = WHY_NO_POSE;
        cachedFrame = null;
        cachedPose = null;
        cachedShipId = 0L;
        logRows = 0;
    }

    /** The per-round row cap, tested BEFORE a caller builds its row string (no string past the cap). */
    private boolean logOpen() {
        return logRows < LOG_MAX_PER_ROUND;
    }

    private void logRow(String row) {
        if (logRows < LOG_MAX_PER_ROUND) {
            logRows++;
            PilotWatchLog.info(logRows == LOG_MAX_PER_ROUND
                    ? row + " [row cap " + LOG_MAX_PER_ROUND + " reached: later rows this round are suppressed]" : row);
        }
    }

    /**
     * The merger-meter tokens, EXACTLY
     * {@code " drive_lock_frames=<n> drive_lock_unpaired=<m> drive_lock_pressed_frames=<p> drive_lock_fired=<f>"}
     * (cumulative held frames; cumulative unpaired-burn episodes; cumulative pressed wire
     * samples the gate turned neutral — 0 with held frames = nobody pressed inside the lock,
     * so "0 in-burn discharges" proves nothing; cumulative own projectile births under a held frame,
     * {@link #onOwnDischarge}; never reset on read). Reader = the arena rig's R-LOCK INFO
     * kit row (the LAST {@code predict merger-meter} row of the kit sidelog — the same row a play-test
     * grade reads) and, for {@code drive_lock_fired=}, the rig's R-LOCK kit
     * row (FAIL on > 0); delete trigger: all four tokens when the done-when bar
     * is met on two flights.
     */
    String meterTokens() {
        return " drive_lock_frames=" + holdFrames + " drive_lock_unpaired=" + unpaired
                + " drive_lock_pressed_frames=" + pressedFrames + " drive_lock_fired=" + firedInLock;
    }

    long firedInLock() {
        return firedInLock;
    }

    long pressedFrames() {
        return pressedFrames;
    }

    /** Edge rows printed this round (the smoke's cap row). */
    int logRows() {
        return logRows;
    }

    int unpaired() {
        return unpaired;
    }

    long holdFrames() {
        return holdFrames;
    }

    int driveOffs() {
        return driveOffs;
    }

    boolean heldLastFrame() {
        return heldLastFrame;
    }
}
