package cc.starsector.client.net;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import cc.starsector.client.FallbackAnnounce;

/**
 * Live client connection to a running dedicated combat server.
 *
 * <p>Owns a daemon background thread that connects to the server's
 * {@code AWSS} world-snapshot socket, decodes frames with {@link AwssSnapshotReader},
 * and stores the most recent {@link ServerSnapshotFrame} in a lock-free
 * latest-wins slot. The combat render thread polls {@link #latestFrame()} once per
 * frame and never blocks on the network — exactly the snapshot-authoritative
 * presentation contract (the client renders server truth; it holds no authority).
 *
 * <p>The reader loop is reconnect-tolerant: if the server is not up yet, or the
 * stream ends, it backs off and retries until {@link #close()}. This lets the
 * human start the game and the server in either order during the Spike 2 live run.
 *
 * <p>Spike 3 adds the outbound half: a render-thread caller
 * sets a {@link ClientManeuverIntent} via {@link #setManeuverIntent}; on each
 * connection the reader thread sends the standing server's maneuver handshake
 * (the {@code (peerId, maxSchema)} advertisement + the
 * {@code client.ship_movement.requested} frame, see {@link ManeuverIntentWriter})
 * <em>before</em> draining snapshots — matching the read-first order
 * {@code serveManeuverRoundTripUntilShutdown} expects. With no intent set the client
 * still sends the 8-byte {@code (peerId, maxSchema)} advertisement that BOTH server modes
 * read first before draining snapshots — a read-only Spike 2 server does
 * read it (via {@code serveOneClient}), so the client must always send it. Held semantics:
 * one intent per connection (the latest captured input is sent on the next connect);
 * a continuous per-frame intent channel is a later server-side refinement.
 *
 * <p>Despawn-lane LIVE wire-in, client CONSUME half: the reader loop demuxes the
 * multiplexed server stream via {@link MultiplexedServerStreamReader} instead of decoding only
 * {@code AWSS} — the durable {@code EDSP} despawn lane is queued for the combat thread to
 * {@link #drainDespawns() drain}, with {@link #hasExplicitDespawnLane()} latched so a consumer
 * can prefer this relevance-safe lane over snapshot roster diff. A pure-{@code AWSS} server (no
 * despawn lane) decodes exactly as before — the demux dispatches by leading magic.
 *
 * <p>Pure Java (no Starsector API), so it is exercised directly by the game-free
 * verifier against the real server codec over a real loopback socket.
 */
public final class ServerSnapshotClient implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    /** Default backoff between reconnects (ms). Avoids hammering a down server. */
    private static final long DEFAULT_RECONNECT_BACKOFF_MILLIS = 1_000L;
    /**
     * Backoff between reconnect attempts (ms). The read-only/pose streams use one long
     * session and reconnect rarely, so the 1s default keeps a down server from being
     * hammered. The <em>command-driven</em> two-player path is
     * different: each connection sends ONE held movement intent at connect, so the human's
     * input only reaches the server on the NEXT reconnect — a 1s backoff makes the puppet
     * freeze ~1s between sessions, the stutter a player would see in a two-window run.
     * Lowering it (e.g. {@code -Dcc.client.reconnectBackoffMillis=100}) reconnects fast
     * enough that the held command is re-sent promptly and the render gap is small, without
     * the protocol change a continuous per-frame intent channel would need. Configurable so
     * the default behavior (and the three game-free verifiers) is unchanged.
     */
    private static final long RECONNECT_BACKOFF_MILLIS = resolveReconnectBackoffMillis();

    /** Default peer id advertised to the maneuver server (cosmetic / diagnostic). */
    public static final int DEFAULT_PEER_ID = 1;
    /**
     * Default max snapshot schema advertised (v4 = the richest folded stream every existing
     * verifier/mission negotiates). The richer schema-v5 combat-side allegiance tier (friend/foe)
     * is opt-in via {@code cc.client.maxSchema} / {@code CC_CLIENT_MAX_SCHEMA} (see
     * {@link #resolveMaxSchema()}): the server only serves a tier the peer advertises support for
     * ({@code WorldSnapshotPublicationPolicy.govern} drops the allegiance tier below v5), so the
     * player-piloted demo bumps this to {@code 5} while every other path stays byte-identical at v4.
     */
    public static final int DEFAULT_MAX_SCHEMA = 4;

    private final String host;
    private final int port;
    private final int peerId;
    private final int maxSchema;
    /**
     * Whether to speak the server's per-frame intent protocol: send ONE
     * {@link ClientManeuverIntent} per received frame in lockstep, the channel the
     * concurrent / continuous command-driven serve loops read
     * ({@code serveConcurrentCommandDrivenUntilShutdown} /
     * {@code serveContinuousCommandDrivenUntilShutdown}). Default {@code false} keeps the
     * single-shot path (one intent at connect, then read-only) every existing server mode
     * and verifier exercises byte-identically. The despawn-watch demo sets it on so a real
     * game client can drive the EDSP-emitting concurrent overload (the only live producer of
     * the despawn lane). Resolved per-instance at construction so the flag is read after the
     * launcher sets it (no static-init ordering trap).
     */
    private final boolean perFrameIntents;

    private final AtomicReference<ServerSnapshotFrame> latest = new AtomicReference<>();
    // Timestamped ring buffer of recent frames feeding client-side
    // interpolation. Fed by the reader thread on each decode; queried by the render
    // thread via interpolatedPose(). latest (above) is kept for the startup/fallback
    // path and existing callers (maneuver target id, frame-presence checks).
    private final SnapshotInterpolator interpolator = new SnapshotInterpolator();
    private final AtomicLong framesDecoded = new AtomicLong();
    // Despawn-lane LIVE wire-in, client CONSUME half: the reader loop now
    // demuxes the multiplexed server stream (AWSS snapshot lane + durable EDSP despawn
    // lane). Explicit despawn events land in this lock-free queue for the combat thread to
    // drain once per frame; the flag latches true the first time an EDSP frame arrives so a
    // consumer can prefer this relevance-safe lane over snapshot roster-diff. An AWSS-only
    // server never sets the flag and never enqueues, so the pure-pose path is unchanged.
    private final Queue<ClientEntityDespawn> despawns = new ConcurrentLinkedQueue<>();
    private final AtomicLong despawnsDecoded = new AtomicLong();
    private volatile boolean sawExplicitDespawnLane;
    private final AtomicReference<String> lastError = new AtomicReference<>();
    // Spike 3: the latest captured maneuver intent (null until input arrives), sent
    // as the per-connection handshake. Set from the render thread, read by the reader.
    private final AtomicReference<ClientManeuverIntent> pendingManeuver = new AtomicReference<>();
    private final AtomicLong handshakesSent = new AtomicLong();
    // Count of per-frame intents written in perFrameIntents mode (0 in the
    // single-shot path). Diagnostic + the concurrent-despawn verifier asserts it advanced.
    private final AtomicLong perFrameIntentsSent = new AtomicLong();
    private final AtomicReference<ClientManeuverIntent> lastSent = new AtomicReference<>();
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile Socket activeSocket;
    private Thread readerThread;

    public ServerSnapshotClient(String host, int port) {
        this(host, port, DEFAULT_PEER_ID, resolveMaxSchema());
    }

    public ServerSnapshotClient(String host, int port, int peerId, int maxSchema) {
        this.host = host;
        this.port = port;
        this.peerId = peerId;
        this.maxSchema = maxSchema;
        this.perFrameIntents = resolvePerFrameIntents();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** Start the background reader thread. Idempotent-ish: only the first call starts it. */
    public synchronized void start() {
        if (readerThread != null || closed) {
            return;
        }
        readerThread = new Thread(this::runReaderLoop, "cc-server-snapshot-client");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** The most recently decoded frame, or {@code null} if none has arrived yet. */
    public ServerSnapshotFrame latestFrame() {
        return latest.get();
    }

    /**
     * The client-side interpolated pose of server entity {@code entityId} at
     * {@code renderTimeNanos} (a {@link System#nanoTime()} value, typically
     * {@code now - interpDelay}), or {@code null} if the interpolation buffer holds no
     * pose for that entity yet. Smooths the puppet across the snapshot stream instead of
     * snapping to the latest frame; see {@link SnapshotInterpolator}.
     */
    public InterpolatedPose interpolatedPose(long renderTimeNanos, long entityId) {
        return interpolator.poseAt(renderTimeNanos, entityId);
    }

    /** Frames currently held in the interpolation buffer (diagnostic). */
    public int interpolationBufferSize() {
        return interpolator.size();
    }

    /**
     * The render delay (ns) the puppet should use given the <em>measured</em> snapshot
     * interval — {@link SnapshotInterpolator#suggestedInterpDelayNanos()}. Tracks the
     * server's configured tickrate automatically, so the client needs no change when the
     * server pace is re-tuned (the network tick is a configurable invariant).
     */
    public long suggestedInterpDelayNanos() {
        return interpolator.suggestedInterpDelayNanos();
    }

    /** True while a socket to the server is open. */
    public boolean isConnected() {
        return connected;
    }

    /** Total frames decoded since start (across reconnects). */
    public long framesDecoded() {
        return framesDecoded.get();
    }

    /** Total explicit {@code EDSP} despawn events decoded since start (across reconnects). */
    public long despawnsDecoded() {
        return despawnsDecoded.get();
    }

    /**
     * Whether the server has ever sent an explicit {@code EDSP} despawn event on this stream
     * (latched true on the first one, across reconnects). When true the despawn-lane consumer
     * should trust {@link #drainDespawns()} as the relevance-safe authority and stop deriving
     * despawns from snapshot roster diff; when false (an AWSS-only server) it falls back to
     * roster diff so the pure-pose demo still reacts to a vanished ship.
     */
    public boolean hasExplicitDespawnLane() {
        return sawExplicitDespawnLane;
    }

    /**
     * Remove and return every explicit despawn event decoded since the last drain, in arrival
     * order (empty when none). Called once per combat frame by the puppet plugin to retire the
     * local puppet a vanished server entity was driving. Non-blocking and lock-free.
     */
    public List<ClientEntityDespawn> drainDespawns() {
        if (despawns.isEmpty()) {
            return List.of();
        }
        List<ClientEntityDespawn> drained = new ArrayList<>();
        ClientEntityDespawn event;
        while ((event = despawns.poll()) != null) {
            drained.add(event);
        }
        return drained;
    }

    /** The last connection/decode error message, or {@code null}. */
    public String lastError() {
        return lastError.get();
    }

    /**
     * Set (or clear, with {@code null}) the maneuver intent the client sends as its
     * per-connection handshake. Called from the combat render thread as local input
     * is captured; the reader thread sends the latest value on its next connect.
     */
    public void setManeuverIntent(ClientManeuverIntent intent) {
        pendingManeuver.set(intent);
    }

    /** The maneuver intent currently queued to send, or {@code null}. */
    public ClientManeuverIntent pendingManeuver() {
        return pendingManeuver.get();
    }

    /** Total maneuver handshakes sent since start (across reconnects). */
    public long handshakesSent() {
        return handshakesSent.get();
    }

    /** Whether this client speaks the per-frame intent protocol. */
    public boolean perFrameIntents() {
        return perFrameIntents;
    }

    /** Total per-frame intents written since start (0 in the single-shot path). */
    public long perFrameIntentsSent() {
        return perFrameIntentsSent.get();
    }

    /**
     * Write the current held intent as one per-frame {@link LiveManeuverIntent}-equivalent
     * frame. Uses the latest captured maneuver, or a coast intent for the
     * unregistered ship {@code 0} before any input has arrived (the driver no-ops an
     * unregistered ship, so it never disturbs another client's held command).
     */
    private void sendFrameIntent(OutputStream out) throws IOException {
        ClientManeuverIntent intent = pendingManeuver.get();
        if (intent == null) {
            intent = ClientManeuverIntent.hold(0L);
        }
        ManeuverIntentWriter.writeManeuver(out, intent);
        lastSent.set(intent);
        perFrameIntentsSent.incrementAndGet();
    }

    /** The last maneuver intent actually written to the socket, or {@code null}. */
    public ClientManeuverIntent lastManeuverSent() {
        return lastSent.get();
    }

    private void runReaderLoop() {
        while (!closed) {
            try (Socket socket = new Socket()) {
                activeSocket = socket;
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                connected = true;
                lastError.set(null);
                // Both server modes read the (peerId, maxSchema) advertisement FIRST:
                // the read-only serveUntilShutdown via serveOneClient, and the
                // maneuver round-trip via serveOneManeuverRoundTripClient. So the client
                // ALWAYS sends the 8-byte advertisement on connect BEFORE reading any
                // snapshots — otherwise the server blocks on readInt and the connection
                // times out, taking the standing server down. When local input
                // has produced a maneuver intent, the intent frame follows the advertisement
                // contiguously (writeHandshake), matching the read-first order
                // serveManeuverRoundTripUntilShutdown expects.
                OutputStream out = socket.getOutputStream();
                if (perFrameIntents) {
                    // Per-frame intent protocol: the 8-byte advertisement, then ONE
                    // intent per frame in lockstep with received AWSS frames — the channel the
                    // concurrent/continuous command-driven serve loops read. Without this the
                    // concurrent handler blocks on the second frame's readIntent forever (the
                    // single-shot path below sends only one intent), so the EDSP-emitting
                    // concurrent overload — the despawn lane's only live producer — can't be
                    // driven by a real game client.
                    ManeuverIntentWriter.writeAdvertisement(out, peerId, maxSchema);
                    sendFrameIntent(out); // intent[0], before the handler's first readIntent
                } else {
                    ClientManeuverIntent intent = pendingManeuver.get();
                    if (intent != null) {
                        ManeuverIntentWriter.writeHandshake(out, peerId, maxSchema, intent);
                        lastSent.set(intent);
                        handshakesSent.incrementAndGet();
                    } else {
                        ManeuverIntentWriter.writeAdvertisement(out, peerId, maxSchema);
                    }
                }
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                while (!closed) {
                    // Demux the multiplexed server stream: AWSS pose frames feed
                    // the latest-wins slot + interpolator as before; durable EDSP despawn
                    // events are queued for the combat thread to drain. Dispatch is by leading
                    // magic, so a pure-AWSS server (no despawn lane) decodes exactly as before.
                    ServerStreamMessage message = MultiplexedServerStreamReader.readMessage(in);
                    if (message == null) {
                        // 1290 (81) N7: a clean EOF reconnected with no error recorded at all.
                        FallbackAnnounce.announceOnce("n7-snapshot-reconnect",
                                "clean EOF -> silent backoff reconnect loop");
                        break; // clean end of stream; fall through to reconnect
                    }
                    if (message.isSnapshot()) {
                        ServerSnapshotFrame frame = message.frame();
                        latest.set(frame);
                        interpolator.add(System.nanoTime(), frame);
                        framesDecoded.incrementAndGet();
                        if (perFrameIntents) {
                            // Lockstep: feed the next frame's intent now that this frame arrived.
                            // The EDSP lane (if any) follows this AWSS on the wire; sending here
                            // (before reading it) just buffers the intent the handler reads at the
                            // top of its next frame — no deadlock with one client.
                            sendFrameIntent(out);
                        }
                    } else {
                        despawns.add(message.despawn());
                        despawnsDecoded.incrementAndGet();
                        sawExplicitDespawnLane = true;
                    }
                }
            } catch (IOException e) {
                lastError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                if (!closed) {
                    // 1290 (81) N7: lastError was a poll-only string — a refused/dead server left
                    // puppets frozen in a silent reconnect loop with no log row.
                    FallbackAnnounce.announceOnce("n7-snapshot-reconnect",
                            "receive failed: " + lastError.get() + " -> backoff reconnect loop");
                }
            } finally {
                connected = false;
                activeSocket = null;
            }
            if (closed) {
                return;
            }
            try {
                Thread.sleep(RECONNECT_BACKOFF_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The reconnect backoff in ms from {@code cc.client.reconnectBackoffMillis} (then
     * {@code CC_CLIENT_RECONNECT_BACKOFF_MILLIS}), or {@value #DEFAULT_RECONNECT_BACKOFF_MILLIS}
     * when unset/unparseable. A non-positive value is treated as 1 (never busy-spin with no
     * pause). Resolved once at class load; the demo launcher passes the low value.
     */
    private static long resolveReconnectBackoffMillis() {
        String raw = System.getProperty("cc.client.reconnectBackoffMillis");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_CLIENT_RECONNECT_BACKOFF_MILLIS");
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_RECONNECT_BACKOFF_MILLIS;
        }
        try {
            long millis = Long.parseLong(raw.trim());
            return millis > 0L ? millis : 1L;
        } catch (NumberFormatException e) {
            return DEFAULT_RECONNECT_BACKOFF_MILLIS;
        }
    }

    /**
     * Whether to speak the per-frame intent protocol, from
     * {@code cc.client.perFrameIntents} (then {@code CC_CLIENT_PER_FRAME_INTENTS}); default
     * {@code false}. Any of {@code 1/true/yes/on} (case-insensitive) enables it. Resolved at
     * construction so the demo launcher's environment is honored without a static-init trap.
     */
    private static boolean resolvePerFrameIntents() {
        String raw = System.getProperty("cc.client.perFrameIntents");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_CLIENT_PER_FRAME_INTENTS");
        }
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    /**
     * The max snapshot schema this client advertises, from {@code cc.client.maxSchema} (then
     * {@code CC_CLIENT_MAX_SCHEMA}); {@link #DEFAULT_MAX_SCHEMA} when unset/blank, but a
     * set-yet-unparseable/non-positive value advertises
     * {@link AwssSnapshotReader#HIGHEST_KNOWN_SCHEMA_VERSION} with the announce ((83) CL-3 — a
     * typo must not silently downgrade the wire to v4).
     * The player-piloted demo sets it to {@code 5} so the server serves the schema-v5 combat-side
     * allegiance tier the mod renders friend/foe; every other path leaves it at the v4 default so the
     * existing byte-identity verifiers and the pose/despawn missions are unchanged. Only the no-schema
     * (2-arg) constructor consults this; callers that pass an explicit {@code maxSchema} are honored
     * verbatim. Resolved per-instance at construction (no static-init trap), mirroring
     * {@link #resolvePerFrameIntents()}.
     */
    private static int resolveMaxSchema() {
        String raw = System.getProperty("cc.client.maxSchema");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_CLIENT_MAX_SCHEMA");
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_SCHEMA;
        }
        try {
            int schema = Integer.parseInt(raw.trim());
            if (schema <= 0) {
                // 1290 (81) N2 + (83) CL-3: a bad knob must not silently DOWNGRADE the wire — a
                // set-but-broken value now advertises the full known ceiling, not v4 (the announce
                // stays). Unset/blank stays the silent v4 default.
                FallbackAnnounce.announceOnce("n2-max-schema-bad", "cc.client.maxSchema=" + raw
                        + " non-positive -> advertising known ceiling v"
                        + AwssSnapshotReader.HIGHEST_KNOWN_SCHEMA_VERSION);
                return AwssSnapshotReader.HIGHEST_KNOWN_SCHEMA_VERSION;
            }
            return schema;
        } catch (NumberFormatException e) {
            FallbackAnnounce.announceOnce("n2-max-schema-bad", "cc.client.maxSchema=" + raw
                    + " unparseable -> advertising known ceiling v"
                    + AwssSnapshotReader.HIGHEST_KNOWN_SCHEMA_VERSION);
            return AwssSnapshotReader.HIGHEST_KNOWN_SCHEMA_VERSION;
        }
    }

    @Override
    public void close() {
        closed = true;
        Socket socket = activeSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort; the reader loop also observes the closed flag
            }
        }
        Thread thread = readerThread;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
