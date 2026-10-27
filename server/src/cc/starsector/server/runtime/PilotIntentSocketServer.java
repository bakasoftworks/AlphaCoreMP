package cc.starsector.server.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;

/**
 * The SERVER read endpoint for the live two-client 1v1 — the
 * promotion of {@link PilotIntentSocketBridge} from a single-JVM round-trip to a real REMOTE peer.
 *
 * <p>Where the bridge colocated both ends in one process, this endpoint binds a {@link ServerSocket}
 * to a NON-loopback local address (the off-loopback sense) and waits for a separate
 * remote pilot-client JVM ({@code PilotIntentSocketClient}) to {@link Socket#connect connect} in. Each
 * tick the server {@link #exchange exchanges} with that peer: it writes a {@link PilotWorldViewFrame}
 * DOWN (the two ship poses, via {@link PilotWorldViewWireCodec}) so the client can compute its own
 * faithful aim, then reads the client's {@link PilotIntentFrame} back UP the unchanged
 * {@link PilotIntentWireCodec}. The decoded intent — produced by an actual other process — is what the
 * server applies to the engine.
 *
 * <p>Two DOWN payloads share this per-tick ping-pong. {@link #exchange exchange(PilotWorldViewFrame)}
 * is the proven puppet-pilot lane (pose down, intent up; its only caller at HEAD is the
 * {@code PilotIntentClientSessionContract} test). The LIVE serve loop drives
 * {@link #exchangeSnapshot exchangeSnapshot(AuthoritativeWorldSnapshot)} — the
 * predict-and-reconcile lane carrying each entity's {@code velX/velY/angularVelocity} + hull, which
 * {@code PersistentPilotIntentCombatServer} calls per tick. So the real network loop is the snapshot
 * lane, intent up.
 *
 * <p>Accept runs on a daemon thread so {@link #open} returns immediately with the bound host/port (the
 * caller launches the client process with them, then {@link #awaitClient waits} for the connection).
 * Ordering is a strict per-tick ping-pong (server writes the view, then reads the intent; the client
 * reads the view, then writes the intent), so there is no deadlock.
 *
 * <p>History: this class implemented the extracted {@code PilotEndpoint} seam with its
 * existing bodies until the seam retired with the TCP serve lane
 * — the bodies keep VERBATIM; the scout predict lane (run-predict-watch /
 * verify-pilot-watch-selftest / ghost / idle-socket-repro) drives this class directly.
 */
public final class PilotIntentSocketServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final boolean offLoopback;
    private final String host;
    private final int port;
    private final Thread acceptor;

    private volatile Socket clientSocket;
    private volatile IOException acceptFailure;
    private OutputStream out;
    private InputStream in;
    // Optional both-direction wire record sink (CCWR v1). Null (the default) takes
    // the exact pre-sink code path; attached, DOWN writes are buffer-captured (content-identical
    // bytes, one write) and UP frames re-encoded through the unchanged fixed-width+CRC codec.
    private volatile WireRecordSink sink;
    // The up-lane demux state (declared discharges routed apart from intent frames).
    private final UpLaneDemux upLaneDemux = new UpLaneDemux();
    // Socket-liveness cluster CORE. Both 0 (the default) keep
    // the exact pre-liveness transport: no read deadline (accept()'s SO_TIMEOUT 0 = infinite), unbounded
    // PDCL consume loop. configureLiveness() arms them; only the serve loop's exchangeSnapshot lanes
    // honor them (the puppet exchange() and every pre-lane consumer stay byte-identical).
    private volatile int livenessReadDeadlineMillis;
    private volatile int livenessDeclarationBudget;
    // pre-round join grace — the window bounding the FIRST UP read after connect. A
    // connected human legitimately sits in the lobby/mission-brief far longer than the mid-round
    // deadline (a VPN playtest: seat 1 reaped at round 0 frame 0 after exactly 10 s while both
    // humans coordinated on voice), so the first exchange gets the serve config's join budget
    // instead; the steady deadline arms once the peer's first frame proves it live. Still bounded:
    // grace expiry frees the sim thread through the SAME disconnect path (no infinite wedge).
    private volatile long livenessJoinGraceMillis;
    private volatile boolean livenessJoinGracePending;

    private PilotIntentSocketServer(ServerSocket serverSocket, boolean offLoopback, String host, int port) {
        this.serverSocket = serverSocket;
        this.offLoopback = offLoopback;
        this.host = host;
        this.port = port;
        this.acceptor = new Thread(this::acceptOne, "pilot-intent-server-accept");
        this.acceptor.setDaemon(true);
    }

    /** Bind an off-loopback {@link ServerSocket} (site-local NIC address if one exists) and start accepting. */
    public static PilotIntentSocketServer open() throws IOException {
        return open("", 0);
    }

    /**
     * The step-3 fixed-bind door (the D3-reactivation deferral):
     * the ProtonVPN forwarded-port path needs the listener on a SPECIFIC port reachable via the VPN TUN
     * adapter, which the auto pick (first site-local NIC, ephemeral port) cannot promise — the TUN and
     * LAN NICs are both site-local, so the auto pick is nondeterministic between them, and the forwarded
     * port is assigned by the VPN (NAT-PMP), never ephemeral.
     *
     * <p>{@code bindHost} {@code ""} = the proven auto pick; {@code "0.0.0.0"} = wildcard (all
     * interfaces — the VPN lane's shape); anything else = that literal interface address. {@code
     * bindPort} {@code 0} = ephemeral (proven); {@code N} = exactly {@code N}, and a taken port THROWS
     * ({@link java.net.BindException}) — fail-closed, a VPN-forwarded port that cannot be bound must not
     * silently serve elsewhere ({@code setReuseAddress} is skipped for a fixed port: Windows
     * SO_REUSEADDR would let a second bind of a live port succeed). A wildcard bind advertises the
     * auto-picked address via {@link #host()} (port-file / GO-block consumers need a dialable host, not
     * {@code 0.0.0.0}). The no-arg {@link #open()} delegates here with the defaults, byte-identical.
     */
    public static PilotIntentSocketServer open(String bindHost, int bindPort) throws IOException {
        String requested = bindHost == null ? "" : bindHost.trim();
        boolean wildcard = "0.0.0.0".equals(requested);
        InetAddress bind;
        boolean offLoopback;
        if (requested.isEmpty()) {
            bind = firstNonLoopbackAddress();
            offLoopback = bind != null;
            if (bind == null) {
                bind = InetAddress.getLoopbackAddress();
            }
        } else if (wildcard) {
            bind = null;
            offLoopback = true;
        } else {
            bind = InetAddress.getByName(requested);
            offLoopback = !bind.isLoopbackAddress();
        }
        ServerSocket server = new ServerSocket();
        if (bindPort == 0) {
            server.setReuseAddress(true);
        }
        try {
            server.bind(wildcard ? new InetSocketAddress(bindPort) : new InetSocketAddress(bind, bindPort));
        } catch (IOException bindFailure) {
            try { server.close(); } catch (IOException ignore) { }
            throw bindFailure;
        }
        InetAddress advertised = bind;
        if (wildcard) {
            advertised = firstNonLoopbackAddress();
            if (advertised == null) {
                advertised = InetAddress.getLoopbackAddress();
            }
        }
        PilotIntentSocketServer endpoint = new PilotIntentSocketServer(
                server, offLoopback, advertised.getHostAddress(), server.getLocalPort());
        endpoint.acceptor.start();
        return endpoint;
    }

    private void acceptOne() {
        try {
            Socket s = serverSocket.accept();
            s.setTcpNoDelay(true);
            clientSocket = s;
        } catch (IOException e) {
            acceptFailure = e;
        }
    }

    /**
     * Block (up to {@code timeoutMillis}) until the remote client connects, then wire up the streams.
     * Returns {@code true} if a client connected; throws on an accept failure or interruption.
     */
    public boolean awaitClient(long timeoutMillis) throws IOException {
        try {
            acceptor.join(timeoutMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("pilot intent server accept interrupted", ie);
        }
        if (acceptFailure != null) {
            throw acceptFailure;
        }
        if (clientSocket == null) {
            return false;
        }
        this.out = new BufferedOutputStream(clientSocket.getOutputStream());
        this.in = new BufferedInputStream(clientSocket.getInputStream());
        return true;
    }

    /**
     * Socket-liveness cluster CORE (default OFF — every existing caller keeps
     * the exact pre-liveness transport). Arms, on the ACCEPTED socket: (a) a lifecycle-aware read deadline —
     * every serve-loop UP read ({@link #exchangeSnapshot} → {@link UpLaneDemux#read}) now fails into the
     * existing peer-disconnected path ({@link java.net.SocketTimeoutException} →
     * {@link PilotPeerDisconnectedException}) after {@code readDeadlineMillis} of peer silence instead of
     * blocking the sim thread forever, while {@link #awaitReentry()} RELAXES it to infinite across the
     * human's results-screen idle and re-arms it in {@code finally} — and (b) a per-exchange
     * declaration budget bounding {@link UpLaneDemux#read}'s {@code while(true)} (fail-closed "too many
     * PDCL frames without a PINT", also into the disconnect path: the peer is treated as
     * potentially-hostile-or-dead, and dropping ONE peer must never take the server down). Writes are NOT
     * covered — the slow-reader DOWN write-stall stays open, deferred to a write-side
     * deadline or the dormant {@code decoupledSend}/{@code PeerSendGate} lane (cluster part (c)).
     * Zero disarms either half. Requires a connected client (the deadline arms the accepted socket).
     */
    public void configureLiveness(int readDeadlineMillis, int declarationBudgetPerExchange)
            throws IOException {
        configureLiveness(readDeadlineMillis, declarationBudgetPerExchange, 0L);
    }

    /**
     * lifecycle-aware arm: same as the 2-arg form, plus a <b>join grace</b> —
     * {@code joinGraceMillis} (0 = none, the exact 2-arg behaviour) bounds the FIRST UP read after
     * connect instead of {@code readDeadlineMillis}. Rationale: the read deadline prices mid-round
     * peer silence, but the first exchange's wait is a HUMAN lifecycle phase (mission brief / lobby /
     * voice coordination — a VPN playtest reaped seat 1 at round 0 frame 0 at exactly 10 s), so it
     * gets the serve config's join budget ({@code clientWaitMillis}, the same patience the connect
     * itself gets). The first successfully read frame proves the peer live and drops the socket to the
     * steady deadline. Grace expiry fails into the SAME graceful disconnect path — bounded, never the
     * pre-liveness infinite pre-round wedge.
     */
    public void configureLiveness(int readDeadlineMillis, int declarationBudgetPerExchange,
            long joinGraceMillis) throws IOException {
        if (readDeadlineMillis < 0 || declarationBudgetPerExchange < 0 || joinGraceMillis < 0L) {
            throw new IllegalArgumentException("liveness knobs must be >= 0 (0 = off); got readDeadlineMillis="
                    + readDeadlineMillis + " declarationBudgetPerExchange=" + declarationBudgetPerExchange
                    + " joinGraceMillis=" + joinGraceMillis);
        }
        requireConnected();
        this.livenessReadDeadlineMillis = readDeadlineMillis;
        this.livenessDeclarationBudget = declarationBudgetPerExchange;
        this.livenessJoinGraceMillis = joinGraceMillis;
        this.livenessJoinGracePending = readDeadlineMillis > 0 && joinGraceMillis > 0L;
        if (readDeadlineMillis > 0) {
            clientSocket.setSoTimeout(livenessJoinGracePending
                    ? (int) Math.min(joinGraceMillis, Integer.MAX_VALUE)
                    : readDeadlineMillis);
        }
    }

    /** The peer's first read frame proves it live — drop the join grace to the steady deadline. */
    private void settleJoinGrace() {
        if (!livenessJoinGracePending) {
            return;
        }
        livenessJoinGracePending = false;
        try {
            clientSocket.setSoTimeout(livenessReadDeadlineMillis);
        } catch (java.net.SocketException gone) {
            // Socket already torn down — the next read surfaces it through the disconnect path.
        }
    }

    /** Classify an armed-deadline read timeout by lifecycle phase (join grace vs mid-round). */
    private PilotPeerDisconnectedException classifyReadDeadline(java.net.SocketTimeoutException deadline) {
        if (livenessJoinGracePending) {
            return new PilotPeerDisconnectedException(
                    "pilot peer connected but never began exchanging within the join grace ("
                            + livenessJoinGraceMillis + " ms from connect to first intent): " + deadline,
                    deadline);
        }
        return new PilotPeerDisconnectedException(
                "pilot peer liveness read deadline exceeded (" + livenessReadDeadlineMillis
                        + " ms of peer silence mid-exchange): " + deadline, deadline);
    }

    /**
     * Bounded pre-loop handshake read for the seat-name lane: block up to
     * {@code timeoutMillis} for the client's single connect-time {@code SNAM} announce, BEFORE the
     * serve loop's first DOWN write. Returns {@code null} when the window elapses with no bytes (a
     * pre-1163 or name-less client — the caller falls back to config; the client sends nothing
     * before its first receive, so a timeout fires with zero bytes consumed and the stream stays
     * frame-aligned) or on a clean EOF. Wrong magic / truncation / CRC fail closed as
     * {@link IOException} ({@link SeatNameFrameCodec#readAnnounce}). The prior socket timeout is
     * restored in {@code finally}. NO sink interaction — the handshake runs pre-sink-attach by
     * design, so recorded {@code .ccwr} fixtures stay pure PINT/PDCL.
     */
    public SeatNameFrameCodec.SeatNameAnnounce awaitSeatName(long timeoutMillis) throws IOException {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "awaitSeatName timeout must be > 0 ms; got " + timeoutMillis);
        }
        requireConnected();
        Socket c = clientSocket;
        int priorTimeout = c.getSoTimeout();
        c.setSoTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
        try {
            return SeatNameFrameCodec.readAnnounce(in);
        } catch (java.net.SocketTimeoutException noAnnounce) {
            return null;
        } finally {
            c.setSoTimeout(priorTimeout);
        }
    }

    /**
     * Emit the single {@code SNMS} seat-name table DOWN to the connected client — the
     * handshake's second half, written once when both seats are known, BEFORE the sink attaches and
     * the round loop starts (so it is never recorded; the client's
     * {@code MultiplexedServerStreamReader} demuxes it apart by magic). Either name may be
     * {@code null}/empty = the no-override sentinel. Peer-gone maps to
     * {@link PilotPeerDisconnectedException} (the {@link #writeMatchReset} posture).
     */
    public void writeSeatNames(String seat1Name, String seat2Name) throws IOException {
        requireConnected();
        try {
            SeatNameFrameCodec.writeSeatNames(out, seat1Name, seat2Name);
            out.flush();
        } catch (java.net.SocketException | java.io.EOFException peerGone) {
            throw new PilotPeerDisconnectedException(
                    "pilot peer disconnected before seat-names frame: " + peerGone, peerGone);
        }
    }

    /**
     * One tick of the live loop: write {@code view} DOWN to the remote client, then read the client's
     * {@link PilotIntentFrame} back UP. The returned intent is the actual remote peer's, produced from
     * the view just sent.
     */
    public PilotIntentFrame exchange(PilotWorldViewFrame view) throws IOException {
        Objects.requireNonNull(view, "view");
        requireConnected();
        WireRecordSink s = sink;
        writeDown(s, PilotWorldViewWireCodec.FRAME_BYTES,
                target -> PilotWorldViewWireCodec.writeFrame(target, view), NO_STAMP);
        PilotIntentFrame decoded = upLaneDemux.read(in, s);
        if (decoded == null) {
            throw new IOException("pilot intent server client end closed mid-exchange");
        }
        if (s != null) {
            recordUp(s, decoded);
        }
        return decoded;
    }

    /** Attach the B.4 wire record sink (null detaches; default = exact pre-sink path). */
    public void attachSink(WireRecordSink sink) {
        this.sink = sink;
    }

    private static void recordUp(WireRecordSink s, PilotIntentFrame decoded) throws IOException {
        recordUp(s, decoded, NO_STAMP);
    }

    /** UP sink record, re-encoded through the unchanged codec; explicit stamp on the duplex RECV path. */
    private static void recordUp(WireRecordSink s, PilotIntentFrame decoded, long stampTick)
            throws IOException {
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream(PilotIntentWireCodec.FRAME_BYTES);
        PilotIntentWireCodec.writeFrame(cap, decoded);
        if (stampTick == NO_STAMP) {
            s.record(WireRecordSink.KIND_UP, cap.toByteArray());
        } else {
            s.record(WireRecordSink.KIND_UP, cap.toByteArray(), stampTick);
        }
    }

    /** The DOWN frame set one {@link #writeDown} call serializes (flush is the helper's job). */
    @FunctionalInterface
    private interface DownFrameWriter {
        void write(OutputStream target) throws IOException;
    }

    /** Stamp sentinel for {@link #writeDown}/{@link #recordUp}: use the sink's shared tick field. */
    private static final long NO_STAMP = Long.MIN_VALUE;

    /**
     * The ONE DOWN-write idiom (extracted — this body used to be copy-pasted per
     * lane): sink detached ⇒ serialize straight to the socket stream; sink attached ⇒ buffer-capture
     * (content-identical bytes, one write), send, then record — stamped explicitly when
     * {@code stampTick != NO_STAMP} (the duplex loops must not race the sink's shared tick field,
     * design pt 7). {@code s} is the caller's ONE volatile read of {@link #sink}, passed in so a
     * concurrent attach cannot split a write/record pair. A writer that already flushed (e.g.
     * {@link #writeSnapshotWithEvents}) just makes the helper's flush a byte-free no-op.
     */
    private void writeDown(WireRecordSink s, int capHint, DownFrameWriter writer, long stampTick)
            throws IOException {
        if (s == null) {
            writer.write(out);
            out.flush();
            return;
        }
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream(capHint);
        writer.write(cap);
        byte[] down = cap.toByteArray();
        out.write(down);
        out.flush();
        if (stampTick == NO_STAMP) {
            s.record(WireRecordSink.KIND_DOWN, down);
        } else {
            s.record(WireRecordSink.KIND_DOWN, down, stampTick);
        }
    }

    /**
     * One tick of the PREDICT-AND-RECONCILE loop: write the full
     * {@link cc.starsector.server.protocol.AuthoritativeWorldSnapshot} ({@code AWSS}) DOWN — which, unlike
     * {@link #exchange exchange(PilotWorldViewFrame)}, carries each entity's {@code velX/velY/angularVelocity}
     * + absolute hull, the fields a predicting client needs to dead-reckon the foe — then read the client's
     * {@link PilotIntentFrame} back UP. The strict per-tick ping-pong is identical to {@link #exchange}; only
     * the DOWN payload differs (AWSS via {@link AuthoritativeWorldSnapshotCodec} instead of
     * {@link PilotWorldViewWireCodec}). {@link #exchange} is left byte-for-byte unchanged, so the proven puppet
     * pilot lane is untouched. Delegates to the 3-arg lane with empty lists — byte-identical (an
     * empty event/despawn list writes exactly this lane's bytes, the multiplex-order pin) and
     * classification-identical (same deadline/peer-gone/EOF messages).
     */
    public PilotIntentFrame exchangeSnapshot(
            cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot) throws IOException {
        return exchangeSnapshot(snapshot,
                java.util.List.<cc.starsector.server.protocol.GameplayEdgeEvent>of(),
                java.util.List.<cc.starsector.server.protocol.EntityDespawnEvent>of());
    }

    /**
     * The live gameplay-edge-event lane:
     * identical to {@link #exchangeSnapshot(cc.starsector.server.protocol.AuthoritativeWorldSnapshot)}
     * except this tick's detected {@link cc.starsector.server.protocol.GameplayEdgeEvent}s are
     * multiplexed onto the SAME stream — each {@code GPEE} frame written <em>before</em> the tick's
     * {@code AWSS} frame, so the client's demultiplexed read
     * ({@code PredictReconcileClientSession.receiveSnapshot(Consumer)}) routes them and then returns
     * this tick's snapshot: the normal case, event tick == snapshot tick (age 0). An empty
     * event list writes exactly the 1-arg method's bytes, so a no-edge tick is byte-identical to the
     * proven AWSS-only path. Callers gate this on the client side running the demuxed read
     * ({@code cc.predict.eventReplay}); a plain {@code AwssSnapshotReader} peer would read a
     * {@code GPEE} magic as a corrupt snapshot and fail closed.
     */
    public PilotIntentFrame exchangeSnapshot(
            cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot,
            java.util.List<cc.starsector.server.protocol.GameplayEdgeEvent> events) throws IOException {
        return exchangeSnapshot(snapshot, events,
                java.util.List.<cc.starsector.server.protocol.EntityDespawnEvent>of());
    }

    /**
     * The despawn-reason lane: identical to the 2-arg events overload except this
     * tick's {@link cc.starsector.server.protocol.EntityDespawnEvent}s ride the same stream too —
     * each {@code EDSP} frame written after the tick's {@code GPEE} frames and <em>before</em> its
     * {@code AWSS}, so the client's demultiplexed read routes the despawn (and its server-decided
     * DESTROYED/DEPARTED reason) ahead of the drop-frame snapshot it explains. Roster ABSENCE in the
     * AWSS stays the kill edge; this lane only carries WHY (the client must never hull-infer it —
     * kill and roster-drop are same-frame, so the wire hull is stale). Empty lists write exactly the
     * 1-arg method's bytes; the 2-arg overload delegates here, byte-identical.
     */
    public PilotIntentFrame exchangeSnapshot(
            cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot,
            java.util.List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            java.util.List<cc.starsector.server.protocol.EntityDespawnEvent> despawns)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(despawns, "despawns");
        requireConnected();
        WireRecordSink s = sink;
        PilotIntentFrame decoded;
        try {
            writeDown(s, 256, target -> writeSnapshotWithEvents(target, snapshot, events, despawns),
                    NO_STAMP);
            decoded = upLaneDemux.read(in, s, livenessDeclarationBudget);
            settleJoinGrace();
        } catch (java.net.SocketTimeoutException deadline) {
            // The armed liveness read deadline fired — the peer sent nothing for the whole
            // window (silent/asleep/half-open). Treated as potentially-hostile-or-dead
            // and freed into the SAME graceful disconnect path, which both detects AND
            // unwedges. Phase-classified — a pre-first-exchange timeout names the join
            // grace, not the mid-round deadline.
            throw classifyReadDeadline(deadline);
        } catch (java.net.SocketException | java.io.EOFException peerGone) {
            // Latent gap closed: this despawn-reason
            // overload never inherited the peer-gone classification the 1-arg lane got — so a peer RST landing on an
            // EVENT-BEARING tick (GPEE/EDSP ahead of the AWSS, e.g. racing the kill tick) crashed the
            // serve exit-1 instead of ending the round gracefully. Same classification, same posture.
            throw new PilotPeerDisconnectedException(
                    "pilot peer disconnected mid-exchange: " + peerGone, peerGone);
        }
        if (decoded == null) {
            throw new PilotPeerDisconnectedException(
                    "pilot peer closed cleanly (EOF) mid-exchange");
        }
        if (s != null) {
            recordUp(s, decoded);
        }
        observeEchoAge(snapshot.simTick(), decoded.frameSeq());
        return decoded;
    }

    /**
     * Duplex DOWN half: write one multiplexed frame set ({@code GPEE}* {@code EDSP}*
     * {@code AWSS}, one flush) — NO read. The duplex gate's SEND loop owns this call; sink DOWN
     * records are stamped with the EXPLICIT deposit tick ({@code stampTick}) because the v1
     * single-threaded {@code sink.tick()} stamp races under two loops (design pt 7). Peer-gone
     * classification matches the fused exchange lanes; the armed liveness read deadline does not
     * apply (no read here — the RECV half owns it).
     */
    public void sendSnapshot(long stampTick,
            cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot,
            java.util.List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            java.util.List<cc.starsector.server.protocol.EntityDespawnEvent> despawns)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(despawns, "despawns");
        requireConnected();
        try {
            writeDown(sink, 256, target -> writeSnapshotWithEvents(target, snapshot, events, despawns),
                    stampTick);
        } catch (java.net.SocketException | java.io.EOFException peerGone) {
            throw new PilotPeerDisconnectedException(
                    "pilot peer disconnected mid-send: " + peerGone, peerGone);
        }
    }

    /**
     * Duplex UP half: block for the next {@code PINT} intent ({@code PDCL} declarations
     * demuxed + enqueued en route), honoring the armed liveness read deadline / join grace / PDCL
     * budget exactly like the fused lanes. The duplex gate's RECV loop owns this call.
     * {@code stampTick} stamps every UP sink record (the gate's latest deposited tick — NOT the
     * intent's echoed {@code frameSeq}, which would zero the dead-clocks echo-age meters into
     * false-green, design pt 7). {@code pdclAcceptUpperBound} is the pre-barrier freshness bound:
     * a demuxed declaration whose {@code clientTick} exceeds it is dropped + counted stale, never
     * enqueued (a boundary-raced round-N declaration must not surface as a phantom shot in round
     * N+1, design pt 5); pass {@link Long#MAX_VALUE} once the round barrier has passed. Returns
     * {@code null} on a clean EOF at a frame boundary — the gate's RECV loop owns the verdict
     * (the fused lanes throw instead because their caller is the sim thread).
     */
    public PilotIntentFrame receiveIntent(long stampTick, long pdclAcceptUpperBound)
            throws IOException {
        requireConnected();
        WireRecordSink s = sink;
        PilotIntentFrame decoded;
        try {
            decoded = upLaneDemux.read(in, s, livenessDeclarationBudget, stampTick,
                    pdclAcceptUpperBound);
            settleJoinGrace();
        } catch (java.net.SocketTimeoutException deadline) {
            throw classifyReadDeadline(deadline);
        } catch (java.net.SocketException | java.io.EOFException peerGone) {
            throw new PilotPeerDisconnectedException(
                    "pilot peer disconnected mid-receive: " + peerGone, peerGone);
        }
        if (decoded == null) {
            return null;
        }
        if (s != null) {
            recordUp(s, decoded, stampTick);
        }
        return decoded;
    }

    /** Declarations dropped as pre-barrier stale by the duplex freshness gate. */
    public long staleDeclarationsDropped() {
        return upLaneDemux.stalePdclCount();
    }

    /**
     * The v1-path echo-age ratchet tripwire. A duplex-shaped client (one
     * that sends intents at its own frame rate instead of answering each DOWN) against these fused
     * request-response lanes gets exactly ONE buffered UP read per exchange, so the per-exchange
     * echo age (the DOWN tick just sent minus the intent's echoed {@code frameSeq}) grows
     * monotonically without bound — the staleness-ratchet signature of the BROKEN
     * duplex-client+v1-server pairing. Log-once WARN after {@code ECHO_AGE_RATCHET_EXCHANGES}
     * consecutive STRICT rises: a healthy welded/decoupled peer echoes the latest DOWN (age
     * constant ~0..2) and network jitter oscillates, so a strict 30-exchange monotone rise cannot
     * occur benignly. Deliberately NOT an {@code in.available()} byte heuristic — multi-PDCL ticks
     * legitimately leave trailing buffered bytes, so that false-positives under sustained fire.
     * Exchange lanes are single-threaded per instance (sim thread OR one
     * gate writer), so plain fields suffice.
     */
    private static final int ECHO_AGE_RATCHET_EXCHANGES = 30;
    private long echoAgePrev = Long.MIN_VALUE;
    private int echoAgeRiseStreak;
    private boolean echoRatchetWarned;

    private void observeEchoAge(long downTick, long echoedFrameSeq) {
        long age = downTick - echoedFrameSeq;
        if (echoAgePrev != Long.MIN_VALUE && age > echoAgePrev) {
            echoAgeRiseStreak++;
        } else {
            echoAgeRiseStreak = 0;
        }
        echoAgePrev = age;
        if (!echoRatchetWarned && echoAgeRiseStreak >= ECHO_AGE_RATCHET_EXCHANGES) {
            echoRatchetWarned = true;
            System.out.println("pilot-intent-echo-ratchet WARN streak=" + echoAgeRiseStreak
                    + " echo_age=" + age
                    + " (per-exchange echo age rose monotonically — duplex-shaped client against a"
                    + " v1 request-response send path? UP backlog ratchets staleness unboundedly;"
                    + " align the client kit and the server sendMode)");
        }
    }

    /**
     * The DOWN half of {@link #exchangeSnapshot(cc.starsector.server.protocol.AuthoritativeWorldSnapshot,
     * java.util.List)}, exposed so the smoke can prove the multiplexed wire order
     * (every event frame, then the snapshot frame, one flush) byte-for-byte without a socket.
     */
    public static void writeSnapshotWithEvents(
            OutputStream out,
            cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot,
            java.util.List<cc.starsector.server.protocol.GameplayEdgeEvent> events) throws IOException {
        writeSnapshotWithEvents(out, snapshot, events,
                java.util.List.<cc.starsector.server.protocol.EntityDespawnEvent>of());
    }

    /**
     * The DOWN half with the despawn-reason lane: every {@code GPEE} event frame, then
     * every {@code EDSP} despawn frame, then the {@code AWSS} snapshot frame, one flush — so the
     * client demux consumes the despawn immediately before the drop-frame snapshot it explains.
     * Empty despawns write exactly the 3-arg method's bytes (which delegates here).
     */
    public static void writeSnapshotWithEvents(
            OutputStream out,
            cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot,
            java.util.List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            java.util.List<cc.starsector.server.protocol.EntityDespawnEvent> despawns)
            throws IOException {
        for (cc.starsector.server.protocol.GameplayEdgeEvent event : events) {
            GameplayEdgeEventCodec.writeEvent(out, event);
        }
        for (cc.starsector.server.protocol.EntityDespawnEvent despawn : despawns) {
            EntityDespawnEventCodec.writeEvent(out, despawn);
        }
        AuthoritativeWorldSnapshotCodec.writeFrame(out, snapshot);
        out.flush();
    }

    /**
     * Emit one explicit {@code MATCH_RESET} (MRST) control frame DOWN to the connected client — the
     * Stack-A reset port, the Pilot-lane analogue of the dedicated server's inline
     * MRST emit (in {@code PersistentDedicatedCombatServer}). Written on the SAME stream the
     * {@code AWSS} frames flow down (after the engine reset, before the next round's snapshots), so the
     * client's {@code MultiplexedServerStreamReader} demuxes it apart by magic and decodes the
     * round-boundary teardown point it cannot otherwise infer. No intent is read back. The MRST codec
     * stays behind this transport boundary, exactly as the AWSS / pilot-intent codecs already do.
     */
    public void writeMatchReset(MatchResetSignal signal) throws IOException {
        Objects.requireNonNull(signal, "signal");
        requireConnected();
        try {
            writeDown(sink, MatchResetFrameCodec.frameByteCount(),
                    target -> MatchResetFrameCodec.writeFrame(target, signal), NO_STAMP);
        } catch (java.net.SocketException | java.io.EOFException peerGone) {
            // Peer gone before the round-boundary MRST could be written: graceful.
            throw new PilotPeerDisconnectedException(
                    "pilot peer disconnected before match-reset frame: " + peerGone, peerGone);
        }
    }

    /**
     * Block until the connected client sends one {@code MATCH_REENTRY} (MRER) control frame UP — the
     * Pilot-lane analogue of the dedicated server's await-reentry read
     * (in {@code PersistentDedicatedCombatServer}). The read is for a distinct MRER frame, NOT a
     * {@link PilotIntentFrame}: a stray buffered intent has the wrong magic and fails closed, so the next
     * round starts ONLY on this explicit signal. While blocked here the server produces nothing, so it
     * idles cleanly across the human's results-screen + relaunch gap. A
     * truncated/garbled frame propagates as an {@link IOException} (fail-closed, same posture as a reset
     * seam).
     */
    public MatchReentrySignal awaitReentry() throws IOException {
        requireConnected();
        MatchReentrySignal reentry;
        // lifecycle-aware deadline: the results-screen idle is the ONE read that legitimately
        // blocks unbounded (the human sits on results/relaunch arbitrarily long) — relax the
        // armed deadline to infinite for exactly this read, re-arm in finally.
        boolean relaxed = false;
        try {
            if (livenessReadDeadlineMillis > 0) {
                clientSocket.setSoTimeout(0);
                relaxed = true;
            }
            reentry = MatchReentryFrameCodec.readFrame(in);
        } catch (java.net.SocketException | java.io.EOFException peerGone) {
            // Peer gone while we idled for the re-entry signal (e.g. the human closed the window during the
            // results-screen gap instead of relaunching): graceful, not a crash.
            throw new PilotPeerDisconnectedException(
                    "pilot peer disconnected awaiting match re-entry: " + peerGone, peerGone);
        } finally {
            if (relaxed) {
                try {
                    clientSocket.setSoTimeout(livenessReadDeadlineMillis);
                } catch (java.net.SocketException reArmFailed) {
                    // Socket already torn down (peer gone propagating above) — nothing left to re-arm.
                }
            }
        }
        if (reentry == null) {
            throw new PilotPeerDisconnectedException(
                    "pilot peer closed cleanly (EOF) awaiting match re-entry");
        }
        WireRecordSink s = sink;
        if (s != null) {
            java.io.ByteArrayOutputStream cap =
                    new java.io.ByteArrayOutputStream(MatchReentryFrameCodec.frameByteCount());
            MatchReentryFrameCodec.writeFrame(cap, reentry);
            s.record(WireRecordSink.KIND_UP, cap.toByteArray());
        }
        return reentry;
    }

    /** Send a final (terminal) view DOWN so the remote client stops cleanly; no intent is read back. */
    public void sendTerminal(PilotWorldViewFrame terminalView) throws IOException {
        Objects.requireNonNull(terminalView, "terminalView");
        if (!terminalView.terminal()) {
            throw new IllegalArgumentException("sendTerminal requires a terminal view");
        }
        requireConnected();
        writeDown(sink, PilotWorldViewWireCodec.FRAME_BYTES,
                target -> PilotWorldViewWireCodec.writeFrame(target, terminalView), NO_STAMP);
    }

    /**
     * Half-close the output stream so the connected client reads a clean EOF (-1) at a frame boundary on its
     * next read, instead of a possible TCP RST from the hard {@link #close()} that a serve loop would
     * otherwise hit on exit. Mirrors the dedicated lane's {@code socket.shutdownOutput()} after its round
     * loop (in {@code PersistentDedicatedCombatServer}), which {@code WorldSnapshotSocketClient} relies on
     * for a clean end-of-stream. A no-op if no client is connected / the socket is already closed.
     */
    public void shutdownOutput() throws IOException {
        Socket c = clientSocket;
        if (c != null && !c.isClosed() && !c.isOutputShutdown()) {
            c.shutdownOutput();
        }
    }

    /**
     * Dequeue the oldest demuxed {@link FireDischargeDeclaration} routed off the up-lane, or
     * {@code null} if none is pending. The serve loop drains this each tick to feed the
     * declared-discharge fire lane; declarations arrive in wire order (FIFO, never conflated).
     */
    public FireDischargeDeclaration pollDeclaration() {
        return upLaneDemux.poll();
    }

    /** Number of demuxed declarations currently pending (not yet {@link #pollDeclaration polled}). */
    public int pendingDeclarationCount() {
        return upLaneDemux.pendingCount();
    }

    /** Declarations dropped because the pending FIFO was full (bound {@link UpLaneDemux#MAX_PENDING}). */
    public long declarationsDropped() {
        return upLaneDemux.droppedCount();
    }

    /**
     * The up-lane demux: the pilot up direction gets the same demux-by-magic treatment the
     * DOWN direction already has (client-side {@code MultiplexedServerStreamReader} routing GPEE vs
     * AWSS vs MRST). The client writes {@code PDCL} declaration frames after the tick's {@code PINT}
     * intent on the SAME stream, so an intent read first routes any queued declarations
     * ({@link FireDischargeDeclarationCodec#readFrameAfterMagic}) into a bounded FIFO, then requires
     * the intent frame ({@link PilotIntentWireCodec#readFrameAfterMagic}; wire bytes unchanged — the
     * CRC still covers the magic). Any other magic stays fail-closed, exactly the old posture.
     *
     * <p>Kept its own class (not inlined) so the routing + FIFO edges are testable over a plain
     * {@link InputStream} without a socket; the poll side is synchronized because the decoupled
     * {@code PeerSendGate} path reads on a reader thread while the sim thread drains.
     */
    public static final class UpLaneDemux {

        /** Pending-declaration FIFO bound — mirrors the down-lane event FIFO bound (PeerSendGate). */
        public static final int MAX_PENDING = 4096;

        private final java.util.ArrayDeque<FireDischargeDeclaration> pending = new java.util.ArrayDeque<>();
        private long dropped;
        private long stalePdcls;

        /**
         * Read UP frames until an intent arrives: {@code PDCL} frames are decoded + enqueued (and
         * recorded to {@code sink} as UP records in wire order, when attached), the terminating
         * {@code PINT} frame is returned decoded. Returns {@code null} on a clean EOF at a frame
         * boundary (the pre-demux {@code readFrame} null contract); throws fail-closed on a
         * truncated magic, an unknown magic, or any frame-level corruption.
         */
        public PilotIntentFrame read(InputStream in, WireRecordSink sink) throws IOException {
            return read(in, sink, 0);
        }

        /**
         * Liveness variant: {@code maxDeclarations > 0} bounds the PDCL-consume loop — one
         * exchange may route at most that many declarations before its intent; the excess frame fails
         * closed as {@link PilotPeerDisconnectedException} ("too many declarations without an intent",
         * the peer-hostile-or-dead posture — a strict-ping-pong client queues a handful per tick, so
         * only a flood peer can reach a bound at FIFO scale). {@code 0} = the exact pre-1166 unbounded
         * loop (every non-liveness caller).
         */
        public PilotIntentFrame read(InputStream in, WireRecordSink sink, int maxDeclarations)
                throws IOException {
            return read(in, sink, maxDeclarations, Long.MIN_VALUE, Long.MAX_VALUE);
        }

        /**
         * Duplex variant: {@code stampTick != Long.MIN_VALUE} stamps each PDCL sink
         * record explicitly (the duplex gate's two loops must not race the sink's shared tick
         * field); {@code pdclAcceptUpperBound} drops (never enqueues) a declaration whose
         * {@code clientTick} exceeds it — the pre-barrier stale gate, counted via
         * {@link #stalePdclCount()}. A dropped declaration is still sink-recorded (wire truth) and
         * still charged against the declaration budget (a stale flood is still a flood). Every
         * pre-duplex caller delegates here with {@code (Long.MIN_VALUE, Long.MAX_VALUE)} —
         * sink-field stamping, accept everything — byte-identical behaviour.
         */
        public PilotIntentFrame read(InputStream in, WireRecordSink sink, int maxDeclarations,
                long stampTick, long pdclAcceptUpperBound) throws IOException {
            Objects.requireNonNull(in, "in");
            int declarationsThisRead = 0;
            while (true) {
                byte[] magicBytes = in.readNBytes(Integer.BYTES);
                if (magicBytes.length == 0) {
                    return null; // clean end of stream at a frame boundary
                }
                if (magicBytes.length != Integer.BYTES) {
                    throw new IOException("pilot up-lane frame magic truncated: got "
                            + magicBytes.length + " bytes");
                }
                int magic = ((magicBytes[0] & 0xFF) << 24) | ((magicBytes[1] & 0xFF) << 16)
                        | ((magicBytes[2] & 0xFF) << 8) | (magicBytes[3] & 0xFF);
                if (magic == FireDischargeDeclarationCodec.MAGIC) {
                    declarationsThisRead++;
                    if (maxDeclarations > 0 && declarationsThisRead > maxDeclarations) {
                        throw new PilotPeerDisconnectedException(
                                "pilot up-lane declaration budget exceeded: " + declarationsThisRead
                                        + " PDCL frames without a PINT intent this exchange (budget "
                                        + maxDeclarations + ") — peer treated as hostile-or-dead"
                                        + " (socket-liveness)");
                    }
                    FireDischargeDeclaration decl = FireDischargeDeclarationCodec.readFrameAfterMagic(in);
                    if (decl.clientTick() > pdclAcceptUpperBound) {
                        // pre-barrier stale gate: a boundary-raced round-N declaration
                        // (clientTick above anything THIS round has produced) never reaches the
                        // declaration queue — a phantom shot in round N+1 is worse than a lost
                        // boundary shot.
                        synchronized (pending) {
                            stalePdcls++;
                        }
                    } else {
                        synchronized (pending) {
                            if (pending.size() >= MAX_PENDING) {
                                dropped++;
                            } else {
                                pending.addLast(decl);
                            }
                        }
                    }
                    if (sink != null) {
                        byte[] pdclBytes = FireDischargeDeclarationCodec.encodeFrame(decl);
                        if (stampTick != Long.MIN_VALUE) {
                            sink.record(WireRecordSink.KIND_UP, pdclBytes, stampTick);
                        } else {
                            sink.record(WireRecordSink.KIND_UP, pdclBytes);
                        }
                    }
                    continue;
                }
                if (magic == PilotIntentWireCodec.MAGIC) {
                    return PilotIntentWireCodec.readFrameAfterMagic(in);
                }
                throw new IOException("pilot up-lane bad magic: 0x" + Integer.toHexString(magic)
                        + " (expected PINT intent or PDCL declaration)");
            }
        }

        public FireDischargeDeclaration poll() {
            synchronized (pending) {
                return pending.pollFirst();
            }
        }

        public int pendingCount() {
            synchronized (pending) {
                return pending.size();
            }
        }

        public long droppedCount() {
            synchronized (pending) {
                return dropped;
            }
        }

        /** Declarations dropped by the pre-barrier stale gate (never enqueued). */
        public long stalePdclCount() {
            synchronized (pending) {
                return stalePdcls;
            }
        }
    }

    private void requireConnected() throws IOException {
        if (out == null || in == null) {
            throw new IOException("pilot intent server has no connected client (call awaitClient first)");
        }
    }

    public boolean isOffLoopback() {
        return offLoopback;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        Socket c = clientSocket;
        if (c != null) {
            try { c.close(); } catch (IOException ignore) { }
        }
        try { serverSocket.close(); } catch (IOException ignore) { }
    }

    /** The first up, non-loopback, site-local IPv4 of this box, else null (the caller falls back to
     * loopback). The rule lives in {@link BindAddressPick} — a pure, stateless NIC
     * pick is the "genuinely transport-agnostic" shared code the clean-separation directive allows;
     * TCP behavior unchanged. Package-private; the rows class
     * {@code BindAddressPickRows} (BP8) reads it reflectively. */
    static InetAddress firstNonLoopbackAddress() {
        return BindAddressPick.siteLocalIpv4(BindAddressPick.upNonLoopbackAddresses());
    }
}
