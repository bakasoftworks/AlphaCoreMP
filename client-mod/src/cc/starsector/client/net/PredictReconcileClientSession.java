package cc.starsector.client.net;

import cc.starsector.server.runtime.PilotIntentFrame;
import cc.starsector.server.runtime.PilotIntentWireCodec;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * The client session for the PREDICT-AND-RECONCILE watch — the
 * intent-UP / AWSS-DOWN seam {@link cc.starsector.client.PredictReconcileCombatPlugin} drives.
 *
 * <p>It is the deliberate sibling of {@link cc.starsector.server.client.PilotIntentClientSession},
 * differing in exactly the way predict-and-reconcile forces: the DOWN channel carries the full
 * {@code AuthoritativeWorldSnapshot} ({@code AWSS}) — which includes per-entity
 * {@code velX/velY/angularVelocity} — instead of the velocity-less {@code PilotWorldViewFrame} the
 * puppet lane uses. A predicting client MUST have velocity to dead-reckon the foe and to keep a
 * post-correction trajectory plausible (switching from puppet to predict makes
 * velocity a required DOWN field). The UP channel is UNCHANGED — the same faithful
 * {@link PilotIntentFrame}/{@link PilotIntentWireCodec} the proven pilot lane already sends, so the
 * human's live {@code (mouse-aim, WASD, fire)} reaches the server exactly as before. No re-minted
 * codec: AWSS decode reuses {@link AwssSnapshotReader}; intent encode reuses {@link PilotIntentWireCodec}.
 *
 * <p><b>Strict per-tick ping-pong.</b> {@link #receiveSnapshot()} reads one AWSS frame DOWN, then
 * {@link #sendIntent} writes this tick's intent UP — mirroring the server's "write snapshot, read
 * intent" loop (the predict-reconcile sibling of {@code PilotIntentSocketServer.exchange}). The
 * combat plugin runs this exchange on a daemon thread so the render thread never blocks on the
 * socket (exactly as {@code PilotIntentCombatPlugin} does for its view exchange).
 *
 * <p><b>Allowlist-clean transport.</b> Loaded in-game by Starsector's script
 * classloader {@code com.fs.starfarer.loading.scripts.B}, a static name blocklist that denies
 * {@code java.io.BufferedOutputStream}/{@code DataOutputStream}/{@code ByteArrayOutputStream}. So the
 * OUTPUT stream is a BARE {@code socket.getOutputStream()} and intents are serialized via
 * {@link PilotIntentWireCodec#encodeFrame} into a fixed {@code byte[]} (no {@code Data*Stream}); the
 * INPUT keeps the allowlisted {@link BufferedInputStream}. This is the same discipline the proven
 * {@link cc.starsector.server.client.PilotIntentClientSession} and {@code ServerSnapshotClient} use,
 * so it clears the script sandbox that earlier in-game runs failed on.
 *
 * <p>Fail-closed: any I/O error propagates; the caller closes the session ({@link AutoCloseable}).
 */
public final class PredictReconcileClientSession implements PredictSession {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 60_000;

    private final long shipId;
    private final int selectedWeaponGroup;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private ServerSnapshotFrame lastSnapshot;
    private int framesSent;
    private int despawnFramesIgnored;
    private int matchResetFramesIgnored;
    private int seatNamesFramesIgnored;
    private int seatNameRecordFramesIgnored;
    private int hulkFractureFramesIgnored;
    private int hulkPoseFramesIgnored;
    private int hulkCutGeometryFramesIgnored;
    private int reservedCtrlIgnored;

    private PredictReconcileClientSession(long shipId, int selectedWeaponGroup, Socket socket,
            InputStream in, OutputStream out) {
        this.shipId = shipId;
        this.selectedWeaponGroup = selectedWeaponGroup;
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /**
     * Connect to the server's predict-reconcile endpoint at {@code host:port} and pilot {@code shipId}
     * with the given weapon group. The transport is allowlist-clean (bare output stream + buffered
     * input), so stream acquisition succeeds under the in-game script classloader B on the first
     * attempt. Fail-closed: on any failure the just-connected socket is closed and the
     * cause rethrown so the caller leaves the ships locally driven.
     */
    public static PredictReconcileClientSession connect(String host, int port, long shipId,
            int selectedWeaponGroup) throws IOException {
        if (host == null) {
            throw new IllegalArgumentException("host must not be null");
        }
        if (port < 0) {
            throw new IllegalArgumentException("port must be >= 0; got " + port);
        }
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (selectedWeaponGroup < 0) {
            throw new IllegalArgumentException("selectedWeaponGroup must be >= 0; got " + selectedWeaponGroup);
        }
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        try {
            // BARE output stream — NO BufferedOutputStream (blocklisted by the in-game script
            // classloader B). BufferedInputStream IS allowlisted, so the read side
            // keeps it. Mirrors PilotIntentClientSession.acquireStreamsWithRetry's stream choice.
            OutputStream rawOut = socket.getOutputStream();
            InputStream bufIn = new BufferedInputStream(socket.getInputStream());
            return new PredictReconcileClientSession(shipId, selectedWeaponGroup, socket, bufIn, rawOut);
        } catch (Throwable t) {
            try {
                socket.close();
            } catch (IOException ignore) {
                // best effort
            }
            throw t;
        }
    }

    /**
     * Read the next authoritative {@code AWSS} world snapshot the server streamed DOWN (loc/vel/facing/
     * angVel/hull per entity), or {@code null} at a clean end of stream. Throws on a corrupt/truncated
     * frame ({@link AwssSnapshotReader} fail-closed contract). The decoded frame is also kept as
     * {@link #lastSnapshot()} so {@link #sendIntent} can echo its {@code simTick} as the intent frameSeq.
     */
    public ServerSnapshotFrame receiveSnapshot() throws IOException {
        ServerSnapshotFrame frame = AwssSnapshotReader.readFrame(in);
        this.lastSnapshot = frame;
        return frame;
    }

    /**
     * The demultiplexed receive the
     * predict plugin uses when {@code cc.predict.eventReplay} is ON: reads the down stream
     * frame-by-frame ({@link MultiplexedServerStreamReader}), routing every {@code GPEE}
     * gameplay edge event to {@code onEdgeEvent} (possibly several per snapshot — the durable
     * lane interleaves them around the tick's {@code AWSS} frame), and returns at the next
     * {@code AWSS} world snapshot (or {@code null} at a clean end-of-stream, after any trailing
     * events were still routed). An {@code EDSP} despawn frame is not part of the predict lane's
     * contract today; it is ignored and counted ({@link #despawnFramesIgnored()}) so a server
     * change cannot fail the read but stays visible in the WATCH summary. With the flag OFF the
     * plugin keeps calling {@link #receiveSnapshot()} — that path is byte-identical to pre-B3.
     *
     * <p>An explicit {@code MRST} match-reset boundary (L1 session-lifecycle) is the
     * predict client's next-round signal; this single-consumer overload has no handler for it, so
     * it is counted ({@link #matchResetFramesIgnored()}) and skipped — like the despawn lane, a
     * server reset cannot crash the read. The reset-aware
     * {@link #receiveSnapshot(Consumer, Consumer)} overload delivers it to a callback for the
     * tear-down-and-rebuild the predict combat plugin will perform.
     */
    public ServerSnapshotFrame receiveSnapshot(Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent)
            throws IOException {
        return receiveSnapshot(onEdgeEvent, reset -> matchResetFramesIgnored++);
    }

    /**
     * The reset-aware demultiplexed receive (L1 session-lifecycle): like
     * {@link #receiveSnapshot(Consumer)} but also routes every explicit {@code MRST} match-reset
     * boundary on the persistent socket to {@code onReset} (the next-round signal the server
     * interleaves inline between two rounds), then keeps reading and returns
     * at the next {@code AWSS} world snapshot — i.e. the first frame of the fresh round. So a caller
     * looping on this overload receives the reset callback in-line, immediately before the next-round
     * snapshot it returns. The predict combat plugin uses this to tear down its local prediction
     * combat and rebuild from the new round's descriptor (the programmatic combat re-entry) at exactly
     * the right point in the stream, instead of inferring the boundary from snapshot side-effects
     * (which a client running its own engine cannot do). A {@code GPEE} gameplay edge
     * event is routed to {@code onEdgeEvent}; an {@code EDSP} despawn frame is still ignored and
     * counted ({@link #despawnFramesIgnored()}); a clean end-of-stream returns {@code null} after any
     * trailing events/resets were routed.
     */
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, despawn -> despawnFramesIgnored++);
    }

    /**
     * The fully-demultiplexed receive: like
     * {@link #receiveSnapshot(Consumer, Consumer)} but the {@code EDSP} despawn lane is CONSUMED —
     * every {@link ClientEntityDespawn} the server multiplexed ahead of its tick's {@code AWSS} is
     * routed to {@code onDespawn} before that snapshot is returned. This is the ignore→consume flip
     * the predict own-death lane needs: roster ABSENCE stays the kill edge, but the death REASON
     * ({@code DESTROYED} vs {@code DEPARTED}) must be server-sent — kill and roster-drop are
     * same-frame, so the wire hull is always stale and a client inference would misread (server
     * authority). The two narrower overloads
     * delegate here with counting no-op consumers, so their observable behavior is unchanged.
     */
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, names -> seatNamesFramesIgnored++);
    }

    /**
     * The seat-name-aware demultiplexed receive (seat-name handshake): like
     * {@link #receiveSnapshot(Consumer, Consumer, Consumer)} but the once-per-connection
     * {@code SNMS} seat-name table the server emits right after both seats are known (BEFORE the
     * round's first {@code AWSS}) is routed to {@code onSeatNames} — the plugin's net-thread stash
     * whose names the render thread applies wire-first at ship bind. The narrower overloads
     * delegate here with a counting no-op ({@link #seatNamesFramesIgnored()}), so a server SNMS
     * cannot fail their read but stays visible.
     */
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames) throws IOException {
        while (true) {
            ServerStreamMessage message = MultiplexedServerStreamReader.readMessage(in);
            if (message == null) {
                return null; // clean EOF at a frame boundary
            }
            switch (message.kind()) {
                case SNAPSHOT:
                    this.lastSnapshot = message.frame();
                    return message.frame();
                case GAMEPLAY_EDGE_EVENT:
                    onEdgeEvent.accept(message.gameplayEdgeEvent());
                    break;
                case MATCH_RESET:
                    onReset.accept(message.matchReset());
                    break;
                case DESPAWN:
                    onDespawn.accept(message.despawn());
                    break;
                case SEAT_NAMES:
                    onSeatNames.accept(message.seatNames());
                    break;
                case SEAT_NAME_RECORD:
                    // ignore-count parity with the datagram lane — the shared reader
                    // now yields SNMR on this retired-fallback transport too (RE tooling only);
                    // a record must never kill its read.
                    seatNameRecordFramesIgnored++;
                    break;
                case HULK_FRACTURE:
                    // Same mandatory tolerance — the shared reader yields HKFR here
                    // too (the TCP-retirement scope boundary: unknown magic throws, so the retired
                    // transport count-ignores rather than dies; never a feature port).
                    hulkFractureFramesIgnored++;
                    break;
                case HULK_POSE:
                    // Same mandatory tolerance for the HKPS keyframe lane —
                    // count-ignore on the retired transport, never a feature port.
                    hulkPoseFramesIgnored++;
                    break;
                case HULK_CUT_GEOMETRY:
                    // Same mandatory tolerance for the HKCG cut-geometry lane —
                    // count-ignore on the retired transport, never a feature port.
                    hulkCutGeometryFramesIgnored++;
                    break;
                case RESERVED_CONTROL:
                    // Reserved DESC/HOUS kinds are TOLERATED (count-ignored) so a
                    // future consumer arc never forces a re-kit — same on both transports.
                    reservedCtrlIgnored++;
                    break;
                default:
                    // fail closed: a kind this switch does not know is a contract change, not noise
                    throw new IOException("unhandled server stream message kind: " + message.kind());
            }
        }
    }

    /** {@code EDSP} frames seen (and ignored) on the demuxed predict down stream. */
    public int despawnFramesIgnored() {
        return despawnFramesIgnored;
    }

    /**
     * {@code MRST} match-reset frames seen (and ignored) on the demuxed predict down stream by the
     * single-consumer {@link #receiveSnapshot(Consumer)} overload (L1 session-lifecycle). The
     * reset-aware {@link #receiveSnapshot(Consumer, Consumer)} overload delivers them to a callback
     * instead of counting them here.
     */
    public int matchResetFramesIgnored() {
        return matchResetFramesIgnored;
    }

    /**
     * {@code SNMS} seat-name frames seen (and ignored) on the demuxed predict down stream by the
     * overloads without an {@code onSeatNames} consumer. The seat-name-aware
     * {@link #receiveSnapshot(Consumer, Consumer, Consumer, Consumer)} overload delivers them to a
     * callback instead of counting them here.
     */
    public int seatNamesFramesIgnored() {
        return seatNamesFramesIgnored;
    }

    /** {@code SNMR} seat-name records seen and count-ignored on this retired-fallback transport
     * (ignore parity — the datagram lane consumes them; here they must only never
     * kill the read). */
    @Override
    public int seatNameRecordFramesIgnored() {
        return seatNameRecordFramesIgnored;
    }

    /** {@code HKFR} hulk-fracture edges seen and count-ignored on this retired-fallback transport
     * (ignore parity — the datagram lane consumes them; here they must only never
     * kill the read). */
    @Override
    public int hulkFractureFramesIgnored() {
        return hulkFractureFramesIgnored;
    }

    /** {@code HKPS} corpse-pose keyframes seen and count-ignored on this retired-fallback
     * transport (ignore parity — the datagram lane consumes them; here they must
     * only never kill the read). */
    @Override
    public int hulkPoseFramesIgnored() {
        return hulkPoseFramesIgnored;
    }

    /** {@code HKCG} hulk-cut-geometry records seen and count-ignored on this retired-fallback
     * transport (ignore parity — the datagram lane consumes them; here they must
     * only never kill the read). */
    @Override
    public int hulkCutGeometryFramesIgnored() {
        return hulkCutGeometryFramesIgnored;
    }

    /** Reserved {@code DESC}/{@code HOUS} control frames seen and count-ignored. */
    @Override
    public int reservedCtrlIgnored() {
        return reservedCtrlIgnored;
    }

    /**
     * Send this tick's faithful pilot intent UP — the player's LIVE input: {@code cursorFacingDeg}
     * (absolute world aim, drives the weapon lane), {@code ownShipFacingDeg} (the own ship's REAL
     * A/D-driven visual heading this frame, {@code ownShip.getFacing()}; the
     * server pins its own-ship copy to THIS so thrust integrates in the heading the human actually flew,
     * NOT the cursor), {@code heldControls} (the unified input lane: a bitmask of the
     * MOVEMENT COMMANDS the engine's native player control emitted on the own ship
     * this frame — captured, not hand-interpreted — which the server replays verbatim via
     * the engine's own {@code giveCommand}; see {@link cc.starsector.server.runtime.PilotIntentFrame#HELD_CONTROLS_MASK}),
     * and {@code fireHeld} (the trigger). {@code aimWorldX}/{@code aimWorldY} (intent frame v3) carry the
     * aim WORLD POINT ({@code ownShip.getMouseTarget()}) so the server pins its engine's own per-frame
     * mount-slew target to the player's true aim. The frame echoes the last received snapshot's {@code simTick} as
     * its {@code frameSeq} (server-side correlation) and the session's ship id + weapon group, then is
     * serialized once via the allowlist-clean {@link PilotIntentWireCodec#encodeFrame} and written.
     * Unlike the pilot lane this is NOT gated on having received a frame first — a predicting client
     * may send input before the first snapshot — but in the ping-pong loop a snapshot always precedes it.
     */
    public void sendIntent(float cursorFacingDeg, float ownShipFacingDeg, int heldControls, boolean fireHeld,
            float aimWorldX, float aimWorldY, int selectedWeaponGroup, int autofireGroupsMask,
            long targetWireId, int fireEdgeSeq, int activeTubeOrdinal,
            long keyCodes, int mouseButtons, int controlKeys)
            throws IOException {
        sendIntent(cursorFacingDeg, ownShipFacingDeg, heldControls, fireHeld, aimWorldX, aimWorldY,
                selectedWeaponGroup, autofireGroupsMask, targetWireId, fireEdgeSeq, activeTubeOrdinal,
                keyCodes, mouseButtons, controlKeys,
                lastSnapshot != null ? lastSnapshot.simTick() : -1L);
    }

    /**
     * The duplex client half: the explicit-echo-tick send the plugin's WRITER thread
     * uses. Under {@code cc.predict.duplex} the read and write halves run on different threads, so
     * the writer must NOT read {@link #lastSnapshot} (written by the reader thread mid-read) — the
     * plugin passes the echo tick from its own volatile {@code latestSnapshot} instead. A negative
     * {@code echoTick} is the no-snapshot-yet sentinel and sends frameSeq 0, exactly as the welded
     * overload does before the first read (the server's round-start barrier accepts it:
     * {@code 0 <= latestDepositedTick}). The welded overload delegates here, byte-identical.
     * {@code keyCodes}/{@code mouseButtons}/{@code controlKeys} (PINT v7) ride into the frame verbatim
     * ({@link PredictSession} states their domains; a non-canonical key pack throws from the frame constructor).
     */
    public void sendIntent(float cursorFacingDeg, float ownShipFacingDeg, int heldControls, boolean fireHeld,
            float aimWorldX, float aimWorldY, int selectedWeaponGroup, int autofireGroupsMask,
            long targetWireId, int fireEdgeSeq, int activeTubeOrdinal,
            long keyCodes, int mouseButtons, int controlKeys, long echoTick)
            throws IOException {
        int frameSeq = echoTick >= 0L ? (int) (echoTick & 0x7fffffff) : 0;
        // Live-sampled group < 0 (no selected group resolvable this frame) falls back to the
        // connect-time group so the frame stays wire-valid (the record rejects negatives).
        int group = selectedWeaponGroup >= 0 ? selectedWeaponGroup : this.selectedWeaponGroup;
        PilotIntentFrame intent = new PilotIntentFrame(
                shipId, frameSeq, cursorFacingDeg, heldControls, fireHeld, group,
                ownShipFacingDeg, aimWorldX, aimWorldY, autofireGroupsMask,
                targetWireId, fireEdgeSeq,
                // -1 (ABSENT) passes through wire-valid like the target lane's
                // sentinels — no connect-time fallback exists for a tube pointer.
                activeTubeOrdinal,
                keyCodes, mouseButtons, controlKeys);
        byte[] bytes = PilotIntentWireCodec.encodeFrame(intent);
        out.write(bytes);
        out.flush();
        framesSent++;
    }

    /**
     * The declared-discharge fire lane (client half): send one {@code PDCL} discharge
     * declaration UP — one discharge the local predicting engine actually EMITTED, encoded
     * byte-identical to the server's {@code FireDischargeDeclarationCodec} by
     * {@link FireDischargeDeclarationWriter}. MUST ride immediately after this tick's
     * {@link #sendIntent} (the server's {@code UpLaneDemux} routes PDCL-before-PINT at its next
     * read; the between-rounds {@code awaitReentry} read is NOT demuxed and would fail-close on
     * it) — the plugin's net loop enforces that ordering by draining its pending declarations
     * right after the intent send, and only there.
     */
    public void sendFireDeclaration(int declSeq, long clientTick, float aimFacingDeg,
            float aimWorldX, float aimWorldY, int mountIndex,
            float muzzleX, float muzzleY, float bearingDeg, float speed) throws IOException {
        byte[] bytes = FireDischargeDeclarationWriter.encodeFrame(
                shipId, declSeq, clientTick, aimFacingDeg, aimWorldX, aimWorldY, mountIndex,
                muzzleX, muzzleY, bearingDeg, speed);
        out.write(bytes);
        out.flush();
    }

    /**
     * The seat-name handshake (client half): send the ONE {@code SNAM} announce UP —
     * this session's shipId + the name the local human picked ({@code null}/empty = the no-pick
     * sentinel, nameLen 0), encoded byte-identical to the server's {@code SeatNameFrameCodec} by
     * {@link SeatNameWriter}. MUST be sent immediately after {@link #connect}, BEFORE the first
     * receive — the server performs a bounded pre-loop {@code awaitSeatName} read right after
     * accepting the seat and would otherwise stall its {@code clientWaitMillis} budget; sending it
     * any later deadlocks against the server's write-snapshot-first loop. Callers gate it on
     * {@code cc.predict.eventReplay} (the lane's ONE knob — knob-off servers demux fail-closed on
     * an unexpected SNAM by design, the strict-lockstep posture).
     */
    public void sendSeatName(String name) throws IOException {
        byte[] bytes = SeatNameWriter.encodeFrame(shipId, name);
        out.write(bytes);
        out.flush();
    }

    /** The most recent snapshot received (the correction signal for this tick), or {@code null} before the first read. */
    public ServerSnapshotFrame lastSnapshot() {
        return lastSnapshot;
    }

    public long shipId() {
        return shipId;
    }

    /** The weapon group the human's held trigger fires in-game (the plugin holds FIRE on this group). */
    public int selectedWeaponGroup() {
        return selectedWeaponGroup;
    }

    public int framesSent() {
        return framesSent;
    }

    /** The socket is this transport's whole liveness state. Close-at-mission-exit
     * keeps the interface default (close+true) — the retired twin never grows the
     * keep-open feature. */
    @Override
    public boolean isDefunct() {
        return socket.isClosed();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignore) {
            // best effort
        }
    }
}
