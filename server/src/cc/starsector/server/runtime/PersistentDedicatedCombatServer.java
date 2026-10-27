package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatOutcomeView;
import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.adapter.CombatWorldState;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ClientRelevanceView;
import cc.starsector.server.protocol.CombatOutcomeEvent;
import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.protocol.WorldSnapshotPublicationPolicy;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.function.BooleanSupplier;

/**
 * The long-lived, standalone dedicated combat server <em>process</em> core.
 *
 * <p>The project already proved every <em>bounded</em> half of a live server: the
 * registry-governed boot serve assembly ({@link DedicatedCombatServerBoot})
 * serves one stream and returns, and the
 * persistent independent-tick runner ({@link PersistentLiveServerRunner})
 * keeps one {@link ServerSocket} alive across a
 * <em>fixed</em> count of connections — and in every case the server spawns its own
 * client. What was missing was the runnable whole: a server you <em>start and leave
 * running</em>, that an <em>independent</em> client process connects to, that keeps
 * serving live engine combat connection after connection until you explicitly shut
 * it down. This class is that process core (the handoff's "unite the persistent
 * listener with the boot entrypoint" move).
 *
 * <p>{@link #serveUntilShutdown} binds <em>one</em> listening socket, advertises its
 * bound {@code host:port} through {@code onReady}, and then loops: accept an external
 * client, read that peer's schema advertisement (two big-endian ints — the
 * per-peer negotiation handshake the
 * {@link cc.starsector.server.client.WorldSnapshotSocketClient} already speaks),
 * resolve the registry-governed {@link WorldSnapshotPublicationPolicy} for that peer,
 * and stream a fresh {@link EngineBackedLiveWorldSnapshotSource} session of governed
 * per-entity {@code AWSS} frames off the <em>same continuously-advancing engine
 * world</em>. The loop ends when {@code shutdownRequested} fires (e.g. a sentinel
 * file the operator drops) or a configured session cap is reached, after which the
 * socket is closed and a per-session receipt is returned. The world is never reset
 * between connections, so a client that connects later observes a later world state —
 * the genuinely-new "persistent live server" capability, not a one-shot gate.
 *
 * <p>Like the rest of the project this class carries no game identity: the caller
 * supplies the already-promoted {@link EngineCombatBinding} (the scout's real
 * engine binding, or a game-free reflective binding for the contract).
 */
public final class PersistentDedicatedCombatServer {

    private static final int BACKLOG = 4;

    private PersistentDedicatedCombatServer() {
    }

    /** Invoked once, right after the listening socket binds, with its resolved address. */
    @FunctionalInterface
    public interface ListenerReady {
        void onReady(String host, int port) throws IOException;
    }

    /**
     * Immutable serve configuration. {@code framesPerSession} is the number of live
     * governed frames each connected client is streamed before the server moves on to
     * the next connection; {@code relevanceView} (nullable) scopes each frame to one
     * peer's interest set; {@code maxSessions <= 0} means serve until the shutdown
     * signal with no session cap; {@code acceptPollMillis} bounds how long a blocked
     * {@code accept()} waits before the loop re-checks the shutdown signal.
     */
    public record ServeConfig(
            SnapshotStreamIdentity streamIdentity,
            int framesPerSession,
            float fixedDt,
            ClientRelevanceView relevanceView,
            int maxSessions,
            int acceptPollMillis,
            int socketTimeoutMillis,
            int shipCountOverride) {

        public ServeConfig {
            Objects.requireNonNull(streamIdentity, "streamIdentity");
            if (framesPerSession < 1) {
                throw new IllegalArgumentException("framesPerSession must be >= 1; got " + framesPerSession);
            }
            if (!Float.isFinite(fixedDt) || fixedDt <= 0.0f) {
                throw new IllegalArgumentException("fixedDt must be a positive finite step; got " + fixedDt);
            }
            if (acceptPollMillis < 1) {
                throw new IllegalArgumentException("acceptPollMillis must be >= 1; got " + acceptPollMillis);
            }
            if (socketTimeoutMillis < 1) {
                throw new IllegalArgumentException("socketTimeoutMillis must be >= 1; got " + socketTimeoutMillis);
            }
            if (shipCountOverride < 0) {
                throw new IllegalArgumentException("shipCountOverride must be >= 0 (0 = read live from the bound roster); got "
                        + shipCountOverride);
            }
        }

        /**
         * Convenience constructor with no ship-count override: {@code serveUntilShutdown} reads the
         * count live from the bound engine roster ({@code world.roster().shipCount()}). This is the
         * fake-binding battery / contract path — the fake factory's ships sit in the repo list at
         * t=0 so the live read is correct there. The real dedicated-server path passes
         * an explicit override (the deploy-ledger count), because the real engine parks freshly
         * deployed ships on the pending-add queue, undrained until the first {@code advanceInner}
         * (the fix for a startup blocker).
         */
        public ServeConfig(
                SnapshotStreamIdentity streamIdentity,
                int framesPerSession,
                float fixedDt,
                ClientRelevanceView relevanceView,
                int maxSessions,
                int acceptPollMillis,
                int socketTimeoutMillis) {
            this(streamIdentity, framesPerSession, fixedDt, relevanceView, maxSessions,
                    acceptPollMillis, socketTimeoutMillis, 0);
        }
    }

    /** Per-connection record of what the standing server actually served one peer. */
    public record SessionSummary(
            int sessionIndex,
            int peerId,
            int peerMaxSchema,
            int servedSchemaVersion,
            String reliabilityLane,
            boolean relevanceScoped,
            int framesServed,
            int sourceShipCountFirstFrame,
            int servedShipCountFirstFrame,
            long servedBytes,
            String servedSha256) {
    }

    /** The whole-process outcome: which address it served and every session it served until shutdown. */
    public record ServeReceipt(
            long serverPid,
            String host,
            int port,
            int sessionsServed,
            String shutdownReason,
            List<SessionSummary> sessions) {

        public ServeReceipt {
            sessions = List.copyOf(sessions);
        }
    }

    /**
     * L1 next-round RESET hook (2b, Option A — session-lifecycle design): between served sessions in
     * {@link #serveUntilShutdown}, after a round ended at roster-0 (both ships dead, {@code matchOver}),
     * the standing server invokes this to reset the bound engine + re-spawn a fresh roster before
     * accepting the next client, so the next session is a fresh round (best-of-N feel) rather than a
     * continuation of the dead world. The real-engine path injects
     * {@code RealEngineCombatBindingFactory::resetForRedeploy} (wrapped to {@link IOException} on a
     * reset seam); the default (no hook) keeps the prior dead-world-continuation behaviour
     * byte-identical. A thrown {@link IOException} ends the standing server with a named
     * {@code match-reset-failed} reason rather than serving a stale roster.
     */
    @FunctionalInterface
    public interface MatchResetHook {
        void reset() throws IOException;
    }

    /** {@link #serveOneClient} result: the per-peer summary plus whether the round ended at roster-0. */
    private record ServedSession(SessionSummary summary, boolean matchOver) {
    }

    /**
     * Wraps the base engine step with the per-session command-authority MANEUVER gate so
     * the standing server stays engine-identity-free: the caller (the scout's real-engine
     * binding, or a game-free reflective one) supplies the actual gated step
     * — e.g. {@code HeadlessCombatContext.engineClientIntentManeuverAuthorityStep}
     * ({@code ShipAPI.giveCommand}). One gate is built per connection
     * because the gate carries that connection's received intent and per-tick application
     * count.
     */
    @FunctionalInterface
    public interface ManeuverGatedStepFactory {
        CombatStepBoundary gatedStep(CombatStepBoundary baseStep, ClientManeuverGate gate);
    }

    /**
     * Per-connection record of one command+observe MANEUVER round-trip session the
     * standing server served: the negotiated schema, the inbound intent it read and
     * gated, how many ticks actually issued the gated maneuver, and the folded read
     * tiers (flux v3 + weapon-mount v4) co-present in the served bytes.
     */
    public record ManeuverSessionSummary(
            int sessionIndex,
            int peerId,
            int peerMaxSchema,
            int servedSchemaVersion,
            boolean intentReceived,
            boolean intentManeuver,
            long intentShipId,
            int intentMovementOrdinal,
            long inboundIntentBytes,
            int maneuverApplications,
            int framesServed,
            int shipCountFirstFrame,
            int fluxRecordsFirstFrame,
            int weaponMountRecordsFirstFrame,
            long servedBytes,
            String servedSha256) {
    }

    /** The whole-process outcome of the command+observe serve loop. */
    public record ManeuverServeReceipt(
            long serverPid,
            String host,
            int port,
            int sessionsServed,
            String shutdownReason,
            List<ManeuverSessionSummary> sessions) {

        public ManeuverServeReceipt {
            sessions = List.copyOf(sessions);
        }
    }

    /**
     * Per-connection record of one <em>server-authoritative pose-driven</em> session
     * the standing server served: the negotiated schema, how many owned ships the
     * {@link ShipPoseDriver} drove each tick, and the cumulative authoritative pose
     * writes applied vs. rejected across the session (Path B). Unlike the
     * {@link ManeuverSessionSummary command+observe} path — where the client's intent
     * is gated onto the engine and the <em>engine</em> integrates the motion — here the
     * server owns the motion directly: the driver decides each owned ship's pose and
     * writes it through the {@link cc.starsector.server.adapter.ShipPoseSink} every tick
     * before the snapshot read, so the served frames carry the server-driven trajectory.
     */
    public record PoseSessionSummary(
            int sessionIndex,
            int peerId,
            int peerMaxSchema,
            int servedSchemaVersion,
            int shipsDriven,
            int posesApplied,
            int posesRejected,
            int framesServed,
            int sourceShipCountFirstFrame,
            int servedShipCountFirstFrame,
            long servedBytes,
            String servedSha256) {
    }

    /** The whole-process outcome of the server-authoritative pose-driven serve loop. */
    public record PoseServeReceipt(
            long serverPid,
            String host,
            int port,
            int sessionsServed,
            String shutdownReason,
            List<PoseSessionSummary> sessions) {

        public PoseServeReceipt {
            sessions = List.copyOf(sessions);
        }
    }

    /**
     * Per-connection record of one <em>command-driven</em> session the standing server
     * served: the negotiated schema, the inbound {@link LiveManeuverIntent} this client
     * sent for its OWN owned ship (and whether that ship was a registered, commandable
     * one), and the cumulative per-tick command-authority issuance the shared
     * {@link ShipCommandDriver} performed across the session
     * ({@code ShipAPI.giveCommand}).
     *
     * <p>This is the COMMAND complement of {@link PoseSessionSummary}. The pose-driven
     * path has the <em>server</em> script the motion ({@code setLoc}/{@code
     * setVel}); here the server only <em>commands</em> and the engine integrates the
     * motion. Unlike the single-intent {@link ManeuverSessionSummary command+observe}
     * path (one client → one bound ship, repository ship 0), the driver holds a
     * <em>per-owned-ship</em> intent, so {@code shipsCommandedLastTick} reflects every
     * client whose held command is still active — two clients each piloting their own
     * ship show {@code shipsCommandedLastTick == 2}.
     */
    public record CommandSessionSummary(
            int sessionIndex,
            int peerId,
            int peerMaxSchema,
            int servedSchemaVersion,
            boolean intentReceived,
            boolean intentManeuver,
            long intentShipId,
            int intentMovementOrdinal,
            boolean intentShipRegistered,
            long inboundIntentBytes,
            int shipsCommandedLastTick,
            int commandsIssued,
            int commandsApplied,
            int commandsRejected,
            int framesServed,
            int sourceShipCountFirstFrame,
            int servedShipCountFirstFrame,
            long servedBytes,
            String servedSha256) {
    }

    /** The whole-process outcome of the server-authoritative command-driven serve loop. */
    public record CommandServeReceipt(
            long serverPid,
            String host,
            int port,
            int sessionsServed,
            String shutdownReason,
            List<CommandSessionSummary> sessions) {

        public CommandServeReceipt {
            sessions = List.copyOf(sessions);
        }
    }

    /**
     * Per-connection record of one <em>continuous</em> command-driven session: the negotiated
     * schema, then the live per-frame intent channel this client drove over a <em>single</em>
     * connection. Unlike {@link CommandSessionSummary} — where one held intent is read once at
     * connect and the same command is re-issued for the whole window, so a key-change only
     * lands on the NEXT connection — here the server drains a fresh {@link LiveManeuverIntent}
     * each frame off the same socket and applies it latest-wins before that frame's command
     * issuance. {@code intentsConsumed} is how many intents the one connection carried (vs. the
     * single-shot path's 1); {@code intentChanges} is how many times the effective held command
     * changed mid-session (the two-window demo's stutter is gone iff this is {@code >= 1}
     * <em>without</em> a reconnect); {@code firstMovementOrdinal}/{@code lastMovementOrdinal}
     * bracket the change (a coast frame is reported as ordinal {@code -1}).
     */
    public record ContinuousCommandSessionSummary(
            int sessionIndex,
            int peerId,
            int peerMaxSchema,
            int servedSchemaVersion,
            int intentsConsumed,
            int intentChanges,
            long intentShipId,
            boolean intentShipRegistered,
            int firstMovementOrdinal,
            int lastMovementOrdinal,
            int shipsCommandedLastTick,
            int commandsIssued,
            int commandsApplied,
            int commandsRejected,
            int framesServed,
            int sourceShipCountFirstFrame,
            int servedShipCountFirstFrame,
            long servedBytes,
            String servedSha256) {
    }

    /** The whole-process outcome of the continuous command-driven serve loop. */
    public record ContinuousCommandServeReceipt(
            long serverPid,
            String host,
            int port,
            int sessionsServed,
            String shutdownReason,
            List<ContinuousCommandSessionSummary> sessions) {

        public ContinuousCommandServeReceipt {
            sessions = List.copyOf(sessions);
        }
    }

    /**
     * Per-client record within one CONCURRENT command-driven match: the negotiated schema
     * and the live per-frame intent channel this client drove over its OWN connection while
     * the match's other clients drove theirs — all over the SINGLE shared engine world
     * advanced ONCE per frame. Unlike {@link ContinuousCommandSessionSummary} (one client
     * served alone — the world advanced for it and it alone), every client in a match receives
     * the SAME authoritative broadcast: {@code servedSha256} is byte-identical across all
     * clients of a match, because one world advance feeds them all.
     */
    public record ConcurrentClientSummary(
            int clientIndex,
            int peerId,
            int peerMaxSchema,
            int servedSchemaVersion,
            long intentShipId,
            boolean intentShipRegistered,
            int firstMovementOrdinal,
            int lastMovementOrdinal,
            int intentsConsumed,
            int intentChanges,
            int framesServed,
            long servedBytes,
            String servedSha256) {
    }

    /**
     * Per-match record of one CONCURRENT command-driven match: how many clients were served
     * at once, how many ticks the SHARED engine world advanced for the whole match
     * ({@code framesAdvanced}; one advance per frame regardless of client count — the
     * genuinely-new property vs. the per-connection serve loops above, which advance the world
     * once per client), and the shared driver's command tallies. Each client's per-connection
     * detail is in {@link #clients}.
     */
    public record ConcurrentCommandMatchSummary(
            int matchIndex,
            int clientCount,
            int framesAdvanced,
            int shipsCommandedLastTick,
            int commandsIssued,
            int commandsApplied,
            int commandsRejected,
            int sourceShipCountFirstFrame,
            int servedShipCountFirstFrame,
            List<ConcurrentClientSummary> clients) {

        public ConcurrentCommandMatchSummary {
            clients = List.copyOf(clients);
        }
    }

    /** The whole-process outcome of the concurrent command-driven serve loop. */
    public record ConcurrentCommandServeReceipt(
            long serverPid,
            String host,
            int port,
            int matchesServed,
            int clientsServed,
            String shutdownReason,
            List<ConcurrentCommandMatchSummary> matches) {

        public ConcurrentCommandServeReceipt {
            matches = List.copyOf(matches);
        }
    }

    /**
     * Bind one listening socket and serve governed live engine combat to each
     * external client that connects, connection after connection, until
     * {@code shutdownRequested} fires (or {@code maxSessions} is reached). Fails closed
     * — before binding the socket — when the bound engine roster/frame contract does
     * not satisfy the configured {@link SnapshotStreamIdentity}.
     */
    public static ServeReceipt serveUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested) throws IOException {
        return serveUntilShutdown(config, binding, bindAddress, onReady, shutdownRequested, null);
    }

    /**
     * {@link #serveUntilShutdown} with an L1 next-round {@link MatchResetHook} (2b, Option A). When
     * {@code matchResetHook} is non-null and a served round ended at roster-0 (both ships dead), the
     * server resets the bound engine + re-spawns a fresh roster before accepting the next client.
     * A null hook is exactly the prior behaviour (dead-world continuation), byte-identical.
     */
    public static ServeReceipt serveUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested,
            MatchResetHook matchResetHook) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(shutdownRequested, "shutdownRequested");

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        requireAdmittedEngineStream(config, world, step);
        // The snapshot source's position-keyed mode bounds each tick's roster by an exact
        // ship count. The fake battery reads it live (its ships are repo-visible at t=0). The
        // real engine parks freshly-deployed ships on the pending-add queue, undrained until the
        // first advanceInner (executeAdds; drain_frame=0), so world.roster().shipCount()
        // reads 0 at startup and the source ctor failed closed shipCountBound=0. The
        // real path therefore passes the deploy-ledger count as an explicit override; the first
        // publishNextTick's advanceInner drains the queue before the first frame is captured, so
        // the captured roster (2) matches the override (2). Override 0 = read live (battery).
        int shipCount = config.shipCountOverride() > 0
                ? config.shipCountOverride()
                : world.roster().shipCount();
        NetworkMessageRegistry registry = NetworkMessageRegistry.authoritativeCombat();

        // Persistent-socket multi-round serving: when >1 AND a reset hook is wired,
        // serve that many lethal rounds to a SINGLE connected client, resetting between rounds WITHOUT
        // closing the socket -- the persistent-socket reset the reconnect-style GREEN measure
        // did NOT exercise (that measure proved the engine re-spawn on a two-socket reconnect flow, one
        // socket per round). Default 1 = the proven reconnect-style accept-per-round path, byte-identical.
        int roundsPerSocket = Math.max(1, ServerConfig.resolveInt(
                "cc.server.roundsPerSocket", "CC_SERVER_ROUNDS_PER_SOCKET", "server.roundsPerSocket", 1));

        long serverPid = ProcessHandle.current().pid();
        List<SessionSummary> sessions = new ArrayList<>();
        String host;
        int port;
        String shutdownReason;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(config.acceptPollMillis());
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            host = bindAddress.getHostAddress();
            port = serverSocket.getLocalPort();
            if (onReady != null) {
                onReady.onReady(host, port);
            }

            while (true) {
                if (shutdownRequested.getAsBoolean()) {
                    shutdownReason = "shutdown-signal";
                    break;
                }
                if (config.maxSessions() > 0 && sessions.size() >= config.maxSessions()) {
                    shutdownReason = "max-sessions";
                    break;
                }
                Socket accepted;
                try {
                    accepted = serverSocket.accept();
                } catch (SocketTimeoutException timeout) {
                    continue; // re-check the shutdown signal, then keep listening
                }
                try (Socket socket = accepted) {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(config.socketTimeoutMillis());
                    ServedSession served = (roundsPerSocket > 1 && matchResetHook != null)
                            ? serveRoundsOnOneClient(config, registry, step, binding, shipCount, socket,
                                    sessions.size(), roundsPerSocket, matchResetHook)
                            : serveOneClient(config, registry, step, binding, shipCount, socket, sessions.size());
                    sessions.add(served.summary());
                    // L1 MATCH_RESET (2b, Option A): if this round ended at roster-0 (both ships dead)
                    // and a reset hook is wired, reset the engine + re-deploy a fresh roster before the
                    // next accept, so the next session is a fresh round, not a continuation of the dead
                    // world. A reset seam (engine state not clearing between matches) is surfaced as a
                    // named shutdown rather than silently serving a stale roster.
                    if (served.matchOver() && matchResetHook != null) {
                        try {
                            matchResetHook.reset();
                        } catch (IOException resetFailure) {
                            System.err.println("[cc-server] match-reset failed after session "
                                    + (sessions.size() - 1) + ": " + resetFailure);
                            shutdownReason = "match-reset-failed";
                            break;
                        }
                    }
                } catch (IOException perClient) {
                    // One slow/misbehaving/half-open client must NOT take down the standing
                    // server: e.g. a peer that connects but never sends the
                    // read-first (peerId, maxSchema) advertisement -> SocketTimeoutException
                    // on serveOneClient's readInt. Log that one session and keep accepting.
                    System.err.println("[cc-server] dropped read-only client session "
                            + sessions.size() + " on I/O error: " + perClient);
                }
            }
        }
        return new ServeReceipt(serverPid, host, port, sessions.size(), shutdownReason, sessions);
    }

    private static ServedSession serveOneClient(
            ServeConfig config,
            NetworkMessageRegistry registry,
            EngineBackedCombatStepBoundary step,
            EngineCombatBinding binding,
            int shipCount,
            Socket socket,
            int sessionIndex) throws IOException {
        // Per-peer negotiation handshake: the connecting client advertises
        // (peerId, maxSupportedSchema) as two big-endian ints; the registry resolves the
        // schema/lane this standing server may serve THAT peer before any frame is sent.
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int peerId = in.readInt();
        int peerMaxSchema = in.readInt();
        WorldSnapshotPublicationPolicy policy =
                WorldSnapshotPublicationPolicy.forPeer(registry, peerMaxSchema, config.relevanceView());

        // A fresh source per connection that keeps advancing the SAME engine world, so a
        // later client observes a later world (continuity), not a reset one-shot.
        // B3: position-keyed mode (null registry) hard-throws when the roster shrinks
        // below shipCountBound — a retreat OR a kill drops the session (the "engine view
        // ship count mismatch"). The proven stable-roster mode instead keys
        // each ship's network entityId on the engine's STABLE id (ShipAPI.getId()), drops a !alive
        // ship, and keeps survivor entityIds stable — the correct behaviour for the
        // "Hellbore fires + KILLS" goal (position mode renumbers survivors each tick). Opt-in,
        // default-OFF (-Dcc.server.stableRoster=true) so every existing position-keyed regression
        // path stays byte-identical; the real-engine demo wrapper sets it on. A fresh registry per
        // connection scopes entityIds to the session.
        boolean stableRoster = ServerConfig.resolveBoolean(
                "cc.server.stableRoster", "CC_SERVER_STABLE_ROSTER", "server.stableRoster", false);
        EngineBackedLiveWorldSnapshotSource source = stableRoster
                ? EngineBackedLiveWorldSnapshotSource.stableRoster(
                        step, binding, config.framesPerSession(), shipCount, config.fixedDt(),
                        null /*contentRegistry*/, new ShipNetworkIdRegistry(), Map.of(), null)
                : new EngineBackedLiveWorldSnapshotSource(
                        step, binding, config.framesPerSession(), shipCount, config.fixedDt());

        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream servedBytes = new ByteArrayOutputStream();
        int sourceShipCountFirstFrame = -1;
        int servedShipCountFirstFrame = -1;
        int framesServed = 0;
        boolean matchOver = false;
        for (int frame = 0; frame < config.framesPerSession(); frame++) {
            AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
            if (snapshot == null) {
                throw new IOException("live snapshot source returned null at frame " + frame);
            }
            if (sourceShipCountFirstFrame < 0) {
                sourceShipCountFirstFrame = snapshot.entityCount();
            }
            AuthoritativeWorldSnapshot governed = policy.govern(snapshot);
            if (governed == null) {
                throw new IOException("publication policy produced a null governed snapshot at frame " + frame);
            }
            if (servedShipCountFirstFrame < 0) {
                servedShipCountFirstFrame = governed.entityCount();
            }
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
            byte[] encoded = frameBytes.toByteArray();
            out.write(encoded);
            out.flush();
            servedBytes.write(encoded);
            framesServed++;
            if (source.matchOver()) {
                // This tick read ZERO live ships, so the roster-0 frame just flushed
                // is the faithful end-of-match signal — the client observes the last death(s) as the
                // entity list empties. End the session cleanly here instead of looping into another
                // alive==0 read (which previously threw "size out of bounds" -> logged "dropped ...
                // on I/O error"). The final frame is already on the wire; stop without pacing.
                matchOver = true;
                break;
            }
        }
        socket.shutdownOutput();

        byte[] served = servedBytes.toByteArray();
        if (!matchOver) {
            // A full-length session must still satisfy the configured stream (ship count + frame
            // count). A match-over session intentionally ends early at roster-0, so
            // neither the live-ship-count nor the frame-count contract applies to it — skip the check
            // there. framesServed == framesPerSession for every non-match-over session, so this stays
            // byte-identical to the prior behaviour on the existing (no-kill / single-kill) paths.
            requireServedSessionMatchesStream(config.streamIdentity(), sourceShipCountFirstFrame, config.framesPerSession());
        }
        return new ServedSession(
                new SessionSummary(
                        sessionIndex,
                        peerId,
                        peerMaxSchema,
                        policy.servedSchemaVersion(),
                        policy.reliabilityLane().name(),
                        policy.relevanceView().isPresent(),
                        framesServed,
                        sourceShipCountFirstFrame,
                        servedShipCountFirstFrame,
                        served.length,
                        sha256Hex(served)),
                matchOver);
    }

    /** {@link #streamOneRound} result: how the round ended and its first-frame ship counts. */
    private record RoundResult(
            int framesServed,
            boolean matchOver,
            int sourceShipCountFirstFrame,
            int servedShipCountFirstFrame) {
    }

    /**
     * Stream one lethal round to {@code out}: the SAME governed-AWSS frame loop {@link #serveOneClient}
     * runs (byte-identical wire bytes -- same source, policy, codec, order), ending at
     * {@code framesPerSession} or at the first roster-0 ({@code source.matchOver()}). Does NOT touch the
     * socket lifecycle (no handshake, no shutdownOutput) so a caller can serve several rounds across one
     * persistent connection. The flushed bytes are also appended to {@code servedBytes} for the summary.
     */
    private static RoundResult streamOneRound(
            ServeConfig config,
            WorldSnapshotPublicationPolicy policy,
            EngineBackedLiveWorldSnapshotSource source,
            OutputStream out,
            ByteArrayOutputStream servedBytes) throws IOException {
        int sourceShipCountFirstFrame = -1;
        int servedShipCountFirstFrame = -1;
        int framesServed = 0;
        boolean matchOver = false;
        for (int frame = 0; frame < config.framesPerSession(); frame++) {
            AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
            if (snapshot == null) {
                throw new IOException("live snapshot source returned null at frame " + frame);
            }
            if (sourceShipCountFirstFrame < 0) {
                sourceShipCountFirstFrame = snapshot.entityCount();
            }
            AuthoritativeWorldSnapshot governed = policy.govern(snapshot);
            if (governed == null) {
                throw new IOException("publication policy produced a null governed snapshot at frame " + frame);
            }
            if (servedShipCountFirstFrame < 0) {
                servedShipCountFirstFrame = governed.entityCount();
            }
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
            byte[] encoded = frameBytes.toByteArray();
            out.write(encoded);
            out.flush();
            servedBytes.write(encoded);
            framesServed++;
            if (source.matchOver()) {
                // Roster-0 just flushed: the faithful end-of-round signal. Stop the round
                // here; the caller decides whether to reset + stream another round on this same socket.
                matchOver = true;
                break;
            }
        }
        return new RoundResult(framesServed, matchOver, sourceShipCountFirstFrame, servedShipCountFirstFrame);
    }

    /**
     * Persistent-socket variant of {@link #serveOneClient}: serve up to {@code roundsPerSocket} lethal
     * rounds to a SINGLE connected client, keeping the SAME socket open across each next-round reset.
     * This is the persistent-socket reset the GREEN measure did NOT exercise:
     * that measure proved the engine re-spawn on a RECONNECT-style two-socket flow (one socket per round,
     * the client disconnects + reconnects between rounds). Here the peer never disconnects -- after a round
     * ends at roster-0 and more rounds remain, {@code matchResetHook} resets the bound engine + re-deploys
     * a fresh roster, then a fresh source streams the next round down the SAME {@code OutputStream}. The
     * output is half-closed only after the final round, so a single {@code readAllBytes()} client captures
     * every round back-to-back; the on-wire round boundary is the entity-count sequence (2..0 then 2..0).
     *
     * <p>Returns {@code matchOver=false} so the caller's accept-loop post-session reset never double-fires
     * -- this method owns every between-round reset. A reset that throws propagates as the round's
     * {@link IOException}, half-closing the connection after the rounds served so far (a truncated capture
     * = a visible RED), exactly the fail-closed posture the reconnect path takes on a reset seam.
     */
    private static ServedSession serveRoundsOnOneClient(
            ServeConfig config,
            NetworkMessageRegistry registry,
            EngineBackedCombatStepBoundary step,
            EngineCombatBinding binding,
            int shipCount,
            Socket socket,
            int sessionIndex,
            int roundsPerSocket,
            MatchResetHook matchResetHook) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int peerId = in.readInt();
        int peerMaxSchema = in.readInt();
        WorldSnapshotPublicationPolicy policy =
                WorldSnapshotPublicationPolicy.forPeer(registry, peerMaxSchema, config.relevanceView());
        boolean stableRoster = ServerConfig.resolveBoolean(
                "cc.server.stableRoster", "CC_SERVER_STABLE_ROSTER", "server.stableRoster", false);
        // Explicit MATCH_RESET wire frame (default-OFF): when set, an explicit
        // MatchResetFrameCodec "MRST" control frame is emitted INLINE on the same socket between
        // rounds, right after the reset and before the next round's snapshot frames. The implicit
        // entity-count boundary is fine for a read-only AI-vs-AI decoder but a predict
        // client cannot infer it (it runs its own local engine + reconciles); the explicit frame is
        // the tear-down + rebuild signal it decodes. Default-OFF keeps the read-only path
        // byte-identical (the GREEN measure), so the AWSS-only decoders stay unaffected.
        boolean emitMatchResetFrame = ServerConfig.resolveBoolean(
                "cc.server.emitMatchResetFrame", "CC_SERVER_EMIT_MATCH_RESET_FRAME", "server.emitMatchResetFrame", false);
        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream servedBytes = new ByteArrayOutputStream();

        int totalFramesServed = 0;
        int firstRoundSourceShipCount = -1;
        int firstRoundServedShipCount = -1;
        int roundsServed = 0;
        int resetFramesEmitted = 0;
        boolean lastRoundMatchOver = false;
        for (int round = 0; round < roundsPerSocket; round++) {
            // A fresh source per round (same construction the reconnect path makes per accept). The cached
            // binding follows the engine's new repository + player seat after resetForRedeploy, so the
            // freshly re-deployed roster streams without rebuilding the binding.
            EngineBackedLiveWorldSnapshotSource source = stableRoster
                    ? EngineBackedLiveWorldSnapshotSource.stableRoster(
                            step, binding, config.framesPerSession(), shipCount, config.fixedDt(),
                            null /*contentRegistry*/, new ShipNetworkIdRegistry(), Map.of(), null)
                    : new EngineBackedLiveWorldSnapshotSource(
                            step, binding, config.framesPerSession(), shipCount, config.fixedDt());
            RoundResult r = streamOneRound(config, policy, source, out, servedBytes);
            roundsServed++;
            totalFramesServed += r.framesServed();
            if (firstRoundSourceShipCount < 0) firstRoundSourceShipCount = r.sourceShipCountFirstFrame();
            if (firstRoundServedShipCount < 0) firstRoundServedShipCount = r.servedShipCountFirstFrame();
            lastRoundMatchOver = r.matchOver();
            System.out.println("persistent-rounds round=" + round
                    + " frames=" + r.framesServed()
                    + " match_over=" + r.matchOver()
                    + " source_ships_first=" + r.sourceShipCountFirstFrame()
                    + " served_ships_first=" + r.servedShipCountFirstFrame());
            // Continue to the next round on the SAME socket only if this round ended at roster-0 and a reset
            // hook is wired and more rounds remain; otherwise stop (an undecided round / the last round).
            if (r.matchOver() && round < roundsPerSocket - 1) {
                matchResetHook.reset();
                System.out.println("persistent-rounds reset_fired after round=" + round
                        + " (same socket stays open)");
                if (emitMatchResetFrame) {
                    // Emit the explicit MATCH_RESET control frame INLINE, after the reset and before
                    // round+1's snapshot frames, on the SAME OutputStream the snapshots flow down. A
                    // demuxing reader (MatchAwareSnapshotStream) routes it apart from the AWSS frames;
                    // the entity-count boundary stays implicit on the wire alongside it.
                    resetFramesEmitted++;
                    ByteArrayOutputStream resetFrame =
                            new ByteArrayOutputStream(MatchResetFrameCodec.frameByteCount());
                    MatchResetFrameCodec.writeFrame(
                            resetFrame, new MatchResetSignal(resetFramesEmitted, round, round + 1));
                    byte[] resetBytes = resetFrame.toByteArray();
                    out.write(resetBytes);
                    out.flush();
                    servedBytes.write(resetBytes);
                    System.out.println("persistent-rounds match_reset_frame_emitted seq=" + resetFramesEmitted
                            + " completed_round=" + round + " next_round=" + (round + 1));
                }
            } else {
                break;
            }
        }
        socket.shutdownOutput();

        byte[] served = servedBytes.toByteArray();
        System.out.println("persistent-rounds-served rounds=" + roundsServed
                + " total_frames=" + totalFramesServed
                + " last_match_over=" + lastRoundMatchOver
                + " served_bytes=" + served.length
                + " served_sha256=" + sha256Hex(served));
        return new ServedSession(
                new SessionSummary(
                        sessionIndex,
                        peerId,
                        peerMaxSchema,
                        policy.servedSchemaVersion(),
                        policy.reliabilityLane().name(),
                        policy.relevanceView().isPresent(),
                        totalFramesServed,
                        firstRoundSourceShipCount,
                        firstRoundServedShipCount,
                        served.length,
                        sha256Hex(served)),
                false);
    }

    /**
     * Outcome of {@link #runMatchOutcomeLoop}: how many ticks were advanced, whether the
     * match was decided within the budget, the single decided {@link CombatOutcomeEvent}
     * (null when undecided), and why the loop stopped ({@code "decided"} / {@code "max-ticks"}
     * / {@code "shutdown-signal"}).
     */
    public record MatchOutcomeReceipt(
            int ticksAdvanced,
            boolean decided,
            CombatOutcomeEvent decidedEvent,
            String stopReason) {

        /** Winning {@code FleetSide} ordinal, or {@code -1} when the match was not decided. */
        public int winningSideId() {
            return decided ? decidedEvent.winningSideId() : -1;
        }

        /** The completed tick at which the match was decided, or {@code -1} when undecided. */
        public long decidedTick() {
            return decided ? decidedEvent.simTick() : -1L;
        }

        /** The render-coupled engine {@code combatOver} carried on the decided event (false when undecided). */
        public boolean combatOver() {
            return decided && decidedEvent.combatOver();
        }
    }

    /**
     * Run the LIVE headless match-outcome loop: advance the engine inner step once per
     * tick and fold the server-authoritative roster-derived combat OUTCOME
     * ({@link EngineCombatBinding#combatOutcomeFromRosterOrNull()}) through the
     * unchanged {@link CombatOutcomeEventDetector} until the match is decided, the
     * {@code maxTicks} budget is exhausted, or {@code shutdownRequested} fires.
     *
     * <p>This is the live wire-in the roster outcome producer was
     * missing: until now nothing folded {@code combatOutcomeFromRosterOrNull()} through the
     * detector inside a run loop driven by real per-tick engine advances — it was exercised
     * only by unit smokes with hand-fed views ({@link CombatOutcomeEventDetector#observe}).
     * Here the producer runs against a real advancing world through the same
     * {@link EngineBackedCombatStepBoundary} the snapshot serve loops use, so the decided
     * event the detector emits is keyed at the tick the roster the server authoritatively
     * owns first eliminates one fighting side. The single decided {@link CombatOutcomeEvent}
     * is the signal a dedicated combat server pushes to end the match and declare a winner;
     * {@link DedicatedCombatServerMain} crosses it to a real separate-JVM consumer over a
     * socket ({@link CombatOutcomeEventSocketPublisher}).
     *
     * <p>The loop reads the outcome AFTER advancing the step each tick (the same
     * advance-then-read convention {@link cc.starsector.server.tests.FakeMatchEndingEngineBindingFactory}
     * and the despawn fake document), stamping the completed 0-based {@code simTick} on the
     * detector. A {@code null} roster read fails closed: the binding must wire
     * {@code shipAllegiance(getOwner, isAlive)} for a headless server to derive the
     * authoritative outcome.
     */
    public static MatchOutcomeReceipt runMatchOutcomeLoop(
            EngineCombatBinding binding,
            float fixedDt,
            int maxTicks,
            BooleanSupplier shutdownRequested) {
        Objects.requireNonNull(binding, "binding");
        if (maxTicks < 1) {
            throw new IllegalArgumentException("maxTicks must be >= 1; got " + maxTicks);
        }
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        CombatOutcomeEventDetector detector = new CombatOutcomeEventDetector();
        int tick = 0;
        for (; tick < maxTicks; tick++) {
            if (shutdownRequested != null && shutdownRequested.getAsBoolean()) {
                return new MatchOutcomeReceipt(tick, false, null, "shutdown-signal");
            }
            step.step(fixedDt, tick);
            CombatOutcomeView outcome = binding.combatOutcomeFromRosterOrNull();
            if (outcome == null) {
                throw new IllegalStateException(
                        "roster-derived combat outcome unavailable at tick " + tick
                                + "; the binding must wire shipAllegiance(getOwner, isAlive)");
            }
            Optional<CombatOutcomeEvent> decided = detector.observe(outcome, tick);
            if (decided.isPresent()) {
                return new MatchOutcomeReceipt(tick + 1, true, decided.get(), "decided");
            }
        }
        return new MatchOutcomeReceipt(tick, false, null, "max-ticks");
    }

    /**
     * Bind one listening socket and serve, connection after connection until
     * {@code shutdownRequested} fires (or {@code maxSessions} is reached), the
     * <em>end-to-end command+observe</em> round-trip the standing server's read-only
     * {@link #serveUntilShutdown} never closed: each connected external client first
     * advertises its peer id + max schema (8B, the handshake), then
     * sends one {@link LiveManeuverIntent} (the registered {@code client.ship_movement.requested}
     * message, 23B), and the server reads <em>both</em> off the same stream, gates the
     * per-tick command-authority MANEUVER ({@code gatedStepFactory}) on that
     * intent, and streams back the governed v4 frames (motion + folded flux/weapon-mount
     * read tiers) carrying the MOTION effect of the client's own command. This is the
     * composition: the persistent process,
     * the MANEUVER round-trip, and the folded read tiers
     * united in one run behind one server method. Fails closed — before binding — when the
     * bound engine roster/frame contract does not satisfy the configured
     * {@link SnapshotStreamIdentity}.
     */
    public static ManeuverServeReceipt serveManeuverRoundTripUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            ManeuverGatedStepFactory gatedStepFactory,
            ContentIdRegistry contentRegistry,
            Map<Long, Integer> ownerByShipId,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(gatedStepFactory, "gatedStepFactory");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(shutdownRequested, "shutdownRequested");

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary baseStep = new EngineBackedCombatStepBoundary(world, binding);
        requireAdmittedEngineStream(config, world, baseStep);
        int shipCount = world.roster().shipCount();
        NetworkMessageRegistry registry = NetworkMessageRegistry.authoritativeCombat();

        long serverPid = ProcessHandle.current().pid();
        List<ManeuverSessionSummary> sessions = new ArrayList<>();
        String host;
        int port;
        String shutdownReason;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(config.acceptPollMillis());
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            host = bindAddress.getHostAddress();
            port = serverSocket.getLocalPort();
            if (onReady != null) {
                onReady.onReady(host, port);
            }

            while (true) {
                if (shutdownRequested.getAsBoolean()) {
                    shutdownReason = "shutdown-signal";
                    break;
                }
                if (config.maxSessions() > 0 && sessions.size() >= config.maxSessions()) {
                    shutdownReason = "max-sessions";
                    break;
                }
                Socket accepted;
                try {
                    accepted = serverSocket.accept();
                } catch (SocketTimeoutException timeout) {
                    continue; // re-check the shutdown signal, then keep listening
                }
                try (Socket socket = accepted) {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(config.socketTimeoutMillis());
                    sessions.add(serveOneManeuverRoundTripClient(
                            config, registry, baseStep, binding, shipCount, gatedStepFactory,
                            contentRegistry, ownerByShipId, socket, sessions.size()));
                } catch (IOException perClient) {
                    // Same robustness guard as the read-only path: a single
                    // client's handshake/stream IOException (e.g. a peer that connects but
                    // never advertises -> SocketTimeoutException) must not exit main. Log
                    // that one session and keep accepting the next connection.
                    System.err.println("[cc-server] dropped maneuver client session "
                            + sessions.size() + " on I/O error: " + perClient);
                }
            }
        }
        return new ManeuverServeReceipt(serverPid, host, port, sessions.size(), shutdownReason, sessions);
    }

    private static ManeuverSessionSummary serveOneManeuverRoundTripClient(
            ServeConfig config,
            NetworkMessageRegistry registry,
            EngineBackedCombatStepBoundary baseStep,
            EngineCombatBinding binding,
            int shipCount,
            ManeuverGatedStepFactory gatedStepFactory,
            ContentIdRegistry contentRegistry,
            Map<Long, Integer> ownerByShipId,
            Socket socket,
            int sessionIndex) throws IOException {
        // Same stream carries both halves of the composed handshake: the client writes the
        // (peerId, maxSchema) advertisement (8B) FIRST, then the LiveManeuverIntent (23B)
        // contiguously, then drains frames. Read both off the one DataInputStream.
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int peerId = in.readInt();
        int peerMaxSchema = in.readInt();
        LiveManeuverIntent intent = LiveManeuverIntentCodec.readIntent(in);
        ClientManeuverGate gate = new ClientManeuverGate();
        gate.receive(intent);

        WorldSnapshotPublicationPolicy policy =
                WorldSnapshotPublicationPolicy.forPeer(registry, peerMaxSchema, config.relevanceView());

        // Wrap the base engine step with the per-session gated command-authority maneuver,
        // then fold the read tiers onto each published frame so the same run carries BOTH
        // the client's command effect and the read replication. NOTE: the engine-backed
        // source folds every wired tier its binding can read (through v12) — the govern()
        // serve-down chain, not this fold, is what caps the frame at the peer's advertised
        // schema (the v3+v4 wording that used to sit here understated the fold and had
        // masked the v7-capped govern gap, closed).
        CombatStepBoundary gatedStep = gatedStepFactory.gatedStep(baseStep, gate);
        EngineBackedLiveWorldSnapshotSource source = new EngineBackedLiveWorldSnapshotSource(
                gatedStep, binding, config.framesPerSession(), shipCount, config.fixedDt(),
                contentRegistry, ownerByShipId);

        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream servedBytes = new ByteArrayOutputStream();
        int sourceShipCountFirstFrame = -1;
        int shipCountFirstFrame = -1;
        int fluxRecordsFirstFrame = -1;
        int weaponMountRecordsFirstFrame = -1;
        for (int frame = 0; frame < config.framesPerSession(); frame++) {
            AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
            if (snapshot == null) {
                throw new IOException("live snapshot source returned null at frame " + frame);
            }
            if (sourceShipCountFirstFrame < 0) {
                sourceShipCountFirstFrame = snapshot.entityCount();
            }
            AuthoritativeWorldSnapshot governed = policy.govern(snapshot);
            if (governed == null) {
                throw new IOException("publication policy produced a null governed snapshot at frame " + frame);
            }
            if (shipCountFirstFrame < 0) {
                shipCountFirstFrame = governed.entityCount();
                fluxRecordsFirstFrame = governed.fluxStateCount();
                weaponMountRecordsFirstFrame = governed.weaponMountCount();
            }
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
            byte[] encoded = frameBytes.toByteArray();
            out.write(encoded);
            out.flush();
            servedBytes.write(encoded);
        }
        socket.shutdownOutput();

        byte[] served = servedBytes.toByteArray();
        requireServedSessionMatchesStream(config.streamIdentity(), sourceShipCountFirstFrame, config.framesPerSession());
        return new ManeuverSessionSummary(
                sessionIndex,
                peerId,
                peerMaxSchema,
                policy.servedSchemaVersion(),
                gate.intentReceived(),
                gate.intentOrNull() != null && gate.intentOrNull().maneuver(),
                gate.shipId(),
                gate.movementOrdinal(),
                LiveManeuverIntentCodec.FRAME_BYTES,
                gate.maneuverApplications(),
                config.framesPerSession(),
                shipCountFirstFrame,
                fluxRecordsFirstFrame,
                weaponMountRecordsFirstFrame,
                served.length,
                sha256Hex(served));
    }

    /**
     * Bind one listening socket and serve, connection after connection until
     * {@code shutdownRequested} fires (or {@code maxSessions} is reached), a
     * <em>server-authoritative pose-driven</em> stream: each connected external client
     * advertises its peer id + max schema (8B, the handshake) and then
     * drains the governed frames, exactly as the read-only {@link #serveUntilShutdown}
     * does — but here a {@link ShipPoseDriver} owns the motion. Each tick the engine inner
     * step runs and then the driver writes every owned ship's authoritative pose through
     * its {@link cc.starsector.server.adapter.ShipPoseSink} ({@code setLoc}/{@code
     * setVel}, Path B, confirmed on the real engine) <em>before</em>
     * the snapshot source reads the motion views, so the frame the client receives carries
     * the server-driven trajectory rather than the engine's own per-ship motion. This is
     * the wire: the per-tick pose driver — which until
     * now stood alone with only its loop adapter and client bridge — driven each tick inside
     * the live standing-server serve loop over a real socket to an independent client. The
     * same continuously-advancing engine world and driver are shared across connections, so
     * a later client observes a later world (continuity). Fails closed — before binding —
     * when the bound engine roster/frame contract does not satisfy the configured
     * {@link SnapshotStreamIdentity}.
     */
    public static PoseServeReceipt servePoseDrivenUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            ShipPoseDriver driver,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(shutdownRequested, "shutdownRequested");

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary baseStep = new EngineBackedCombatStepBoundary(world, binding);
        requireAdmittedEngineStream(config, world, baseStep);
        int shipCount = world.roster().shipCount();
        NetworkMessageRegistry registry = NetworkMessageRegistry.authoritativeCombat();

        long serverPid = ProcessHandle.current().pid();
        List<PoseSessionSummary> sessions = new ArrayList<>();
        String host;
        int port;
        String shutdownReason;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(config.acceptPollMillis());
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            host = bindAddress.getHostAddress();
            port = serverSocket.getLocalPort();
            if (onReady != null) {
                onReady.onReady(host, port);
            }

            while (true) {
                if (shutdownRequested.getAsBoolean()) {
                    shutdownReason = "shutdown-signal";
                    break;
                }
                if (config.maxSessions() > 0 && sessions.size() >= config.maxSessions()) {
                    shutdownReason = "max-sessions";
                    break;
                }
                Socket accepted;
                try {
                    accepted = serverSocket.accept();
                } catch (SocketTimeoutException timeout) {
                    continue; // re-check the shutdown signal, then keep listening
                }
                try (Socket socket = accepted) {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(config.socketTimeoutMillis());
                    sessions.add(serveOnePoseDrivenClient(
                            config, registry, baseStep, binding, shipCount, driver, socket, sessions.size()));
                } catch (IOException perClient) {
                    // Same robustness guard as the read-only / maneuver paths: a
                    // single client's handshake/stream IOException must not exit main. Log that
                    // one session and keep accepting the next connection.
                    System.err.println("[cc-server] dropped pose-driven client session "
                            + sessions.size() + " on I/O error: " + perClient);
                }
            }
        }
        return new PoseServeReceipt(serverPid, host, port, sessions.size(), shutdownReason, sessions);
    }

    private static PoseSessionSummary serveOnePoseDrivenClient(
            ServeConfig config,
            NetworkMessageRegistry registry,
            EngineBackedCombatStepBoundary baseStep,
            EngineCombatBinding binding,
            int shipCount,
            ShipPoseDriver driver,
            Socket socket,
            int sessionIndex) throws IOException {
        // Per-peer negotiation handshake, identical to the read-only path: the
        // pose-driven stream is observe-only on the wire (the server, not the client, owns
        // the motion), so the client advertises (peerId, maxSchema) and then drains frames.
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int peerId = in.readInt();
        int peerMaxSchema = in.readInt();
        WorldSnapshotPublicationPolicy policy =
                WorldSnapshotPublicationPolicy.forPeer(registry, peerMaxSchema, config.relevanceView());

        // Wrap the engine step so each tick the server-authoritative ShipPoseDriver writes
        // every owned ship's pose (Path B) AFTER the inner step and BEFORE the
        // snapshot source reads the motion views. A fresh wrapper per connection keeps the
        // pose-write tallies per-session, while the SAME driver (and engine world) is shared
        // across connections so the world keeps advancing (continuity).
        PoseDrivenStep drivenStep = new PoseDrivenStep(baseStep, driver);
        EngineBackedLiveWorldSnapshotSource source = new EngineBackedLiveWorldSnapshotSource(
                drivenStep, binding, config.framesPerSession(), shipCount, config.fixedDt());

        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream servedBytes = new ByteArrayOutputStream();
        int sourceShipCountFirstFrame = -1;
        int servedShipCountFirstFrame = -1;
        for (int frame = 0; frame < config.framesPerSession(); frame++) {
            AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
            if (snapshot == null) {
                throw new IOException("live snapshot source returned null at frame " + frame);
            }
            if (sourceShipCountFirstFrame < 0) {
                sourceShipCountFirstFrame = snapshot.entityCount();
            }
            AuthoritativeWorldSnapshot governed = policy.govern(snapshot);
            if (governed == null) {
                throw new IOException("publication policy produced a null governed snapshot at frame " + frame);
            }
            if (servedShipCountFirstFrame < 0) {
                servedShipCountFirstFrame = governed.entityCount();
            }
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
            byte[] encoded = frameBytes.toByteArray();
            out.write(encoded);
            out.flush();
            servedBytes.write(encoded);
        }
        socket.shutdownOutput();

        byte[] served = servedBytes.toByteArray();
        requireServedSessionMatchesStream(config.streamIdentity(), sourceShipCountFirstFrame, config.framesPerSession());
        return new PoseSessionSummary(
                sessionIndex,
                peerId,
                peerMaxSchema,
                policy.servedSchemaVersion(),
                drivenStep.shipsDrivenLastTick(),
                drivenStep.posesApplied(),
                drivenStep.posesRejected(),
                config.framesPerSession(),
                sourceShipCountFirstFrame,
                servedShipCountFirstFrame,
                served.length,
                sha256Hex(served));
    }

    /**
     * Per-connection {@link CombatStepBoundary} decorator that runs the
     * server-authoritative {@link ShipPoseDriver} each fixed step. It forwards the world
     * advance to the engine-backed base step, then drives the pose driver so every owned
     * ship's authoritative pose ({@code setLoc}/{@code setVel}, Path B) is
     * written AFTER the inner step and BEFORE the snapshot source reads the motion views —
     * so the served frame carries the server-driven trajectory, not the engine's own
     * per-ship motion. The pose-write tallies are per-connection so the session receipt can
     * report poses applied/rejected; the driver itself is shared across connections.
     */
    private static final class PoseDrivenStep implements CombatStepBoundary {
        private final CombatStepBoundary baseStep;
        private final ShipPoseDriver driver;
        private int posesApplied;
        private int posesRejected;
        private int shipsDrivenLastTick;

        PoseDrivenStep(CombatStepBoundary baseStep, ShipPoseDriver driver) {
            this.baseStep = Objects.requireNonNull(baseStep, "baseStep");
            this.driver = Objects.requireNonNull(driver, "driver");
        }

        @Override
        public CombatWorldState worldState() {
            return baseStep.worldState();
        }

        @Override
        public float effectiveStepAmount(float fixedDt) {
            return baseStep.effectiveStepAmount(fixedDt);
        }

        @Override
        public void step(float fixedDt, long simTick) {
            baseStep.step(fixedDt, simTick);
            ShipPoseDriver.AdvanceSummary summary = driver.advance(simTick, fixedDt);
            posesApplied += summary.posesApplied();
            posesRejected += summary.posesRejected();
            shipsDrivenLastTick = summary.shipsDriven();
        }

        @Override
        public boolean inputCouplingBypassed() {
            return baseStep.inputCouplingBypassed();
        }

        int posesApplied() {
            return posesApplied;
        }

        int posesRejected() {
            return posesRejected;
        }

        int shipsDrivenLastTick() {
            return shipsDrivenLastTick;
        }
    }

    /**
     * Bind one listening socket and serve, connection after connection until
     * {@code shutdownRequested} fires (or {@code maxSessions} is reached), a
     * <em>command-driven</em> stream: each connected external client advertises its peer
     * id + max schema (8B, the handshake) and then sends one
     * {@link LiveManeuverIntent} (the registered {@code client.ship_movement.requested}
     * frame, 23B) naming the movement it wants held on <em>its own</em> owned ship. The
     * server records that intent on the shared {@link ShipCommandDriver} and, each tick,
     * the driver issues every owned ship's currently-held movement command to that
     * specific ship through its {@link cc.starsector.server.adapter.ShipCommandSink}
     * ({@code ShipAPI.giveCommand}) — the server <em>commands</em>, the engine
     * <em>integrates</em> the motion — before the snapshot source reads the motion views.
     *
     * <p>This is the COMMAND complement of {@link #servePoseDrivenUntilShutdown} (where the
     * server scripts the pose directly) and the multi-client successor to the
     * single-intent {@link #serveManeuverRoundTripUntilShutdown}: that path gates ONE
     * client's intent onto repository ship 0; here the {@link ShipCommandDriver} holds a
     * <em>per-owned-ship</em> intent and the SAME driver is shared across connections, so a
     * second client commanding a second owned ship leaves the first client's held command
     * in force — two clients each pilot their OWN ship under genuine command authority. The
     * stream back is observe-only (the client drains the resulting governed frames; it does
     * not echo the command). Fails closed — before binding — when the bound engine
     * roster/frame contract does not satisfy the configured {@link SnapshotStreamIdentity}.
     */
    public static CommandServeReceipt serveCommandDrivenUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            ShipCommandDriver driver,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(shutdownRequested, "shutdownRequested");

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary baseStep = new EngineBackedCombatStepBoundary(world, binding);
        requireAdmittedEngineStream(config, world, baseStep);
        int shipCount = world.roster().shipCount();
        NetworkMessageRegistry registry = NetworkMessageRegistry.authoritativeCombat();

        long serverPid = ProcessHandle.current().pid();
        List<CommandSessionSummary> sessions = new ArrayList<>();
        String host;
        int port;
        String shutdownReason;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(config.acceptPollMillis());
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            host = bindAddress.getHostAddress();
            port = serverSocket.getLocalPort();
            if (onReady != null) {
                onReady.onReady(host, port);
            }

            while (true) {
                if (shutdownRequested.getAsBoolean()) {
                    shutdownReason = "shutdown-signal";
                    break;
                }
                if (config.maxSessions() > 0 && sessions.size() >= config.maxSessions()) {
                    shutdownReason = "max-sessions";
                    break;
                }
                Socket accepted;
                try {
                    accepted = serverSocket.accept();
                } catch (SocketTimeoutException timeout) {
                    continue; // re-check the shutdown signal, then keep listening
                }
                try (Socket socket = accepted) {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(config.socketTimeoutMillis());
                    sessions.add(serveOneCommandDrivenClient(
                            config, registry, baseStep, binding, shipCount, driver, socket, sessions.size()));
                } catch (IOException perClient) {
                    // Same robustness guard as the read-only / maneuver / pose paths:
                    // a single client's handshake/stream IOException must not exit main. Log that
                    // one session and keep accepting the next connection.
                    System.err.println("[cc-server] dropped command-driven client session "
                            + sessions.size() + " on I/O error: " + perClient);
                }
            }
        }
        return new CommandServeReceipt(serverPid, host, port, sessions.size(), shutdownReason, sessions);
    }

    private static CommandSessionSummary serveOneCommandDrivenClient(
            ServeConfig config,
            NetworkMessageRegistry registry,
            EngineBackedCombatStepBoundary baseStep,
            EngineCombatBinding binding,
            int shipCount,
            ShipCommandDriver driver,
            Socket socket,
            int sessionIndex) throws IOException {
        // Same stream carries both halves of the composed handshake (as the maneuver path):
        // the client writes the (peerId, maxSchema) advertisement (8B) FIRST, then one
        // LiveManeuverIntent (23B) naming its own ship's movement, then drains frames.
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int peerId = in.readInt();
        int peerMaxSchema = in.readInt();
        LiveManeuverIntent intent = LiveManeuverIntentCodec.readIntent(in);
        // Record this client's intent on the shared per-owned-ship driver. A client may only
        // command a registered owned ship; an intent for an unregistered/non-owned ship is
        // read and reported but not applied (the driver would reject it anyway, and the held
        // intents of the other clients must not be disturbed). hold/no-command coasts.
        boolean intentShipRegistered = driver.isRegistered(intent.shipId());
        if (intentShipRegistered) {
            driver.applyManeuver(intent);
        }

        WorldSnapshotPublicationPolicy policy =
                WorldSnapshotPublicationPolicy.forPeer(registry, peerMaxSchema, config.relevanceView());

        // Wrap the engine step so each tick the shared ShipCommandDriver issues every owned
        // ship's held movement command BEFORE the engine integrates, then the
        // engine advances and the snapshot source reads the resulting motion. A fresh wrapper
        // per connection keeps the command tallies per-session while the SAME driver (and
        // engine world) is shared across connections so held intents persist (continuity).
        CommandDrivenStep drivenStep = new CommandDrivenStep(baseStep, driver);
        EngineBackedLiveWorldSnapshotSource source = new EngineBackedLiveWorldSnapshotSource(
                drivenStep, binding, config.framesPerSession(), shipCount, config.fixedDt());

        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream servedBytes = new ByteArrayOutputStream();
        int sourceShipCountFirstFrame = -1;
        int servedShipCountFirstFrame = -1;
        for (int frame = 0; frame < config.framesPerSession(); frame++) {
            AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
            if (snapshot == null) {
                throw new IOException("live snapshot source returned null at frame " + frame);
            }
            if (sourceShipCountFirstFrame < 0) {
                sourceShipCountFirstFrame = snapshot.entityCount();
            }
            AuthoritativeWorldSnapshot governed = policy.govern(snapshot);
            if (governed == null) {
                throw new IOException("publication policy produced a null governed snapshot at frame " + frame);
            }
            if (servedShipCountFirstFrame < 0) {
                servedShipCountFirstFrame = governed.entityCount();
            }
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
            byte[] encoded = frameBytes.toByteArray();
            out.write(encoded);
            out.flush();
            servedBytes.write(encoded);
        }
        socket.shutdownOutput();

        byte[] served = servedBytes.toByteArray();
        requireServedSessionMatchesStream(config.streamIdentity(), sourceShipCountFirstFrame, config.framesPerSession());
        return new CommandSessionSummary(
                sessionIndex,
                peerId,
                peerMaxSchema,
                policy.servedSchemaVersion(),
                true,
                intent.maneuver(),
                intent.shipId(),
                intent.movementOrdinal(),
                intentShipRegistered,
                LiveManeuverIntentCodec.FRAME_BYTES,
                drivenStep.shipsCommandedLastTick(),
                drivenStep.commandsIssued(),
                drivenStep.commandsApplied(),
                drivenStep.commandsRejected(),
                config.framesPerSession(),
                sourceShipCountFirstFrame,
                servedShipCountFirstFrame,
                served.length,
                sha256Hex(served));
    }

    /**
     * Bind one listening socket and serve, connection after connection until
     * {@code shutdownRequested} fires (or {@code maxSessions} is reached), a
     * <em>continuous</em> command-driven stream: the live per-frame intent channel the
     * single-shot {@link #serveCommandDrivenUntilShutdown} never had. Each connected client
     * advertises its peer id + max schema (8B, the handshake) and then,
     * instead of sending ONE {@link LiveManeuverIntent} the server re-issues for the whole
     * window, streams a fresh intent for <em>each</em> frame over the <em>same</em> connection.
     * The server, before publishing each frame, drains every intent currently buffered on the
     * socket and applies the latest one ({@code latest-wins}) to the shared
     * {@link ShipCommandDriver}, then the driver issues that ship's now-current movement
     * command ({@code ShipAPI.giveCommand}) and the engine integrates it — so a
     * mid-session key-change lands on the very next frame <em>without a reconnect</em>.
     *
     * <p>This is the answer to the stutter seen in the
     * two-window demo: the single-shot path lands a command change only on
     * the next connection because it reads one held intent per connect. The wire-back direction
     * is unchanged (the client drains the resulting governed frames; it does not echo the
     * command). The same continuously-advancing engine world and shared driver persist across
     * connections (continuity). Fails closed — before binding — when the bound engine
     * roster/frame contract does not satisfy the configured {@link SnapshotStreamIdentity}.
     */
    public static ContinuousCommandServeReceipt serveContinuousCommandDrivenUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            ShipCommandDriver driver,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested) throws IOException {
        // 6-arg back-compat delegator: no reset hook -> the single-round continuous serve, byte-identical
        // (roundsPerSocket is ignored without a hook, exactly as the read-only serveUntilShutdown defaults).
        return serveContinuousCommandDrivenUntilShutdown(
                config, binding, driver, bindAddress, onReady, shutdownRequested, null);
    }

    /**
     * Persistent-socket multi-round overload of the continuous command-driven serve (build-order step 2,
     * the intent-up lane port of {@link #serveUntilShutdown}'s round-loop). When
     * {@code -Dcc.server.roundsPerSocket} &gt; 1 AND a {@code matchResetHook} is wired, each accepted client
     * is served up to that many lethal rounds over the SAME socket (handshake once, then round after round),
     * with the bound engine reset + re-deployed between rounds and — when
     * {@code -Dcc.server.emitMatchResetFrame} is set — an explicit {@code "MRST"} control frame
     * ({@link MatchResetFrameCodec}) emitted inline at each round boundary so a predict client decodes the
     * tear-down + rebuild point it cannot infer. The SAME {@link ShipCommandDriver} (and its registration of
     * the owned ship ids) is shared across rounds, so the up intent channel keeps draining for the same
     * ships after the reset — the seam this port proves. Default ({@code roundsPerSocket} 1 OR no hook)
     * dispatches to the proven single-round {@link #serveOneContinuousCommandDrivenClient}, byte-identical.
     */
    public static ContinuousCommandServeReceipt serveContinuousCommandDrivenUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            ShipCommandDriver driver,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested,
            MatchResetHook matchResetHook) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(shutdownRequested, "shutdownRequested");

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary baseStep = new EngineBackedCombatStepBoundary(world, binding);
        requireAdmittedEngineStream(config, world, baseStep);
        // Mirror serveUntilShutdown: the real engine parks freshly-deployed ships on
        // the pending-add queue (undrained until the first advanceInner), so world.roster().shipCount()
        // reads 0 here and the source ctor failed closed shipCountBound=0 on a live run. When the
        // demo roster was deployed at construction (RealEngineDemoRoster), bound by the deploy-ledger
        // count instead; override 0 (fake/other factory) keeps the live read, so the game-free
        // continuous contract stays byte-identical.
        int shipCount = config.shipCountOverride() > 0
                ? config.shipCountOverride()
                : world.roster().shipCount();
        NetworkMessageRegistry registry = NetworkMessageRegistry.authoritativeCombat();

        // Persistent-socket multi-round serving on the intent-up lane (mirrors serveUntilShutdown ~L506):
        // >1 AND a reset hook -> serve that many rounds to ONE connected client, resetting between rounds
        // WITHOUT closing the socket. Default 1 = the proven single-round path, byte-identical.
        int roundsPerSocket = Math.max(1, ServerConfig.resolveInt(
                "cc.server.roundsPerSocket", "CC_SERVER_ROUNDS_PER_SOCKET", "server.roundsPerSocket", 1));

        long serverPid = ProcessHandle.current().pid();
        List<ContinuousCommandSessionSummary> sessions = new ArrayList<>();
        String host;
        int port;
        String shutdownReason;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(config.acceptPollMillis());
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            host = bindAddress.getHostAddress();
            port = serverSocket.getLocalPort();
            if (onReady != null) {
                onReady.onReady(host, port);
            }

            while (true) {
                if (shutdownRequested.getAsBoolean()) {
                    shutdownReason = "shutdown-signal";
                    break;
                }
                if (config.maxSessions() > 0 && sessions.size() >= config.maxSessions()) {
                    shutdownReason = "max-sessions";
                    break;
                }
                Socket accepted;
                try {
                    accepted = serverSocket.accept();
                } catch (SocketTimeoutException timeout) {
                    continue; // re-check the shutdown signal, then keep listening
                }
                try (Socket socket = accepted) {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(config.socketTimeoutMillis());
                    ContinuousCommandSessionSummary summary =
                            (roundsPerSocket > 1 && matchResetHook != null)
                                    ? serveContinuousCommandRoundsOnOneClient(config, registry, baseStep, binding,
                                            shipCount, driver, socket, sessions.size(), roundsPerSocket, matchResetHook)
                                    : serveOneContinuousCommandDrivenClient(
                                            config, registry, baseStep, binding, shipCount, driver, socket,
                                            sessions.size());
                    sessions.add(summary);
                } catch (IOException perClient) {
                    // Same robustness guard as the other paths: one client's
                    // handshake/stream IOException must not exit main. Log and keep accepting.
                    System.err.println("[cc-server] dropped continuous command-driven client session "
                            + sessions.size() + " on I/O error: " + perClient);
                }
            }
        }
        return new ContinuousCommandServeReceipt(serverPid, host, port, sessions.size(), shutdownReason, sessions);
    }

    private static ContinuousCommandSessionSummary serveOneContinuousCommandDrivenClient(
            ServeConfig config,
            NetworkMessageRegistry registry,
            EngineBackedCombatStepBoundary baseStep,
            EngineCombatBinding binding,
            int shipCount,
            ShipCommandDriver driver,
            Socket socket,
            int sessionIndex) throws IOException {
        // Handshake first (8B), exactly as the single-shot command path. Then this connection
        // carries a STREAM of per-frame LiveManeuverIntents rather than one held intent.
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int peerId = in.readInt();
        int peerMaxSchema = in.readInt();
        WorldSnapshotPublicationPolicy policy =
                WorldSnapshotPublicationPolicy.forPeer(registry, peerMaxSchema, config.relevanceView());

        // Wrap the engine step so each tick the shared ShipCommandDriver issues every owned
        // ship's currently-held movement command BEFORE the engine integrates. A
        // fresh wrapper per connection keeps the command tallies per-session; the SAME driver
        // (and engine world) is shared across connections so held intents persist.
        CommandDrivenStep drivenStep = new CommandDrivenStep(baseStep, driver);
        // Bug-A fix: honor -Dcc.server.stableRoster on the single-round command lane too. The
        // position-keyed 5-arg source hard-throws "engine view ship count mismatch" the instant the roster
        // shrinks (a real kill/retreat), so a lethal single-round serve died at the death frame. The
        // multi-round lane (serveContinuousCommandRoundsOnOneClient) already branches the same way; mirror
        // it so this lane survives the kill and produces a decodable death-window artifact.
        boolean stableRoster = ServerConfig.resolveBoolean(
                "cc.server.stableRoster", "CC_SERVER_STABLE_ROSTER", "server.stableRoster", false);
        EngineBackedLiveWorldSnapshotSource source = stableRoster
                ? EngineBackedLiveWorldSnapshotSource.stableRoster(
                        drivenStep, binding, config.framesPerSession(), shipCount, config.fixedDt(),
                        null /*contentRegistry*/, new ShipNetworkIdRegistry(), Map.of(), null)
                : new EngineBackedLiveWorldSnapshotSource(
                        drivenStep, binding, config.framesPerSession(), shipCount, config.fixedDt());

        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream servedBytes = new ByteArrayOutputStream();
        int sourceShipCountFirstFrame = -1;
        int servedShipCountFirstFrame = -1;
        int intentsConsumed = 0;
        int intentChanges = 0;
        long intentShipId = -1L;
        boolean anyIntentRegistered = false;
        int firstMovementOrdinal = -1;
        int lastMovementOrdinal = -1;
        for (int frame = 0; frame < config.framesPerSession(); frame++) {
            // Live per-frame intent channel: block for THIS frame's intent, then non-blockingly
            // drain any extra intents that piled up since the last frame and keep only the last
            // (latest-wins). In lockstep (one intent per frame) the drain is a no-op; an async
            // client that batched several collapses to its newest command here.
            LiveManeuverIntent intent = LiveManeuverIntentCodec.readIntent(in);
            intentsConsumed++;
            while (in.available() >= LiveManeuverIntentCodec.FRAME_BYTES) {
                intent = LiveManeuverIntentCodec.readIntent(in);
                intentsConsumed++;
            }
            if (intentShipId < 0L) {
                intentShipId = intent.shipId();
            }
            // A client may only command a registered owned ship; an intent for an
            // unregistered ship is counted but not applied (the driver would reject it and the
            // other ships' held intents must not be disturbed).
            if (driver.isRegistered(intent.shipId())) {
                anyIntentRegistered = true;
                driver.applyManeuver(intent);
            }
            int effectiveOrdinal = intent.maneuver() ? intent.movementOrdinal() : -1; // -1 == coast
            if (frame == 0) {
                firstMovementOrdinal = effectiveOrdinal;
            } else if (effectiveOrdinal != lastMovementOrdinal) {
                intentChanges++;
            }
            lastMovementOrdinal = effectiveOrdinal;

            AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
            if (snapshot == null) {
                throw new IOException("live snapshot source returned null at frame " + frame);
            }
            if (sourceShipCountFirstFrame < 0) {
                sourceShipCountFirstFrame = snapshot.entityCount();
            }
            AuthoritativeWorldSnapshot governed = policy.govern(snapshot);
            if (governed == null) {
                throw new IOException("publication policy produced a null governed snapshot at frame " + frame);
            }
            if (servedShipCountFirstFrame < 0) {
                servedShipCountFirstFrame = governed.entityCount();
            }
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
            byte[] encoded = frameBytes.toByteArray();
            out.write(encoded);
            out.flush();
            servedBytes.write(encoded);
        }
        socket.shutdownOutput();

        byte[] served = servedBytes.toByteArray();
        requireServedSessionMatchesStream(config.streamIdentity(), sourceShipCountFirstFrame, config.framesPerSession());
        return new ContinuousCommandSessionSummary(
                sessionIndex,
                peerId,
                peerMaxSchema,
                policy.servedSchemaVersion(),
                intentsConsumed,
                intentChanges,
                intentShipId,
                anyIntentRegistered,
                firstMovementOrdinal,
                lastMovementOrdinal,
                drivenStep.shipsCommandedLastTick(),
                drivenStep.commandsIssued(),
                drivenStep.commandsApplied(),
                drivenStep.commandsRejected(),
                config.framesPerSession(),
                sourceShipCountFirstFrame,
                servedShipCountFirstFrame,
                served.length,
                sha256Hex(served));
    }

    /** {@link #streamOneCommandRound} result: how the round ended plus its per-round intent/command tallies. */
    private record CommandRoundResult(
            int framesServed,
            boolean matchOver,
            int intentsConsumed,
            int intentChanges,
            long intentShipId,
            boolean anyIntentRegistered,
            int firstMovementOrdinal,
            int lastMovementOrdinal,
            int shipsCommandedLastTick,
            int commandsIssued,
            int commandsApplied,
            int commandsRejected,
            int sourceShipCountFirstFrame,
            int servedShipCountFirstFrame) {
    }

    /**
     * Stream ONE lethal command-driven round to {@code out}: the SAME per-frame intent-drain + publish loop
     * {@link #serveOneContinuousCommandDrivenClient} runs (block for this frame's
     * {@link LiveManeuverIntent}, drain buffered extras latest-wins, apply to the SHARED {@code driver},
     * publish + write the governed AWSS frame), ending at {@code framesPerSession} or the first roster-0
     * ({@code source.matchOver()}). Does NOT touch the socket lifecycle (no handshake, no shutdownOutput)
     * so a caller can serve several rounds across one persistent connection; the flushed bytes are also
     * appended to {@code servedBytes}. The {@code driver} and its registration are shared across rounds —
     * the up intent channel keeps draining for the same owned ships after a between-rounds reset.
     */
    private static CommandRoundResult streamOneCommandRound(
            ServeConfig config,
            WorldSnapshotPublicationPolicy policy,
            EngineBackedLiveWorldSnapshotSource source,
            CommandDrivenStep drivenStep,
            ShipCommandDriver driver,
            DataInputStream in,
            OutputStream out,
            ByteArrayOutputStream servedBytes) throws IOException {
        int sourceShipCountFirstFrame = -1;
        int servedShipCountFirstFrame = -1;
        int intentsConsumed = 0;
        int intentChanges = 0;
        long intentShipId = -1L;
        boolean anyIntentRegistered = false;
        int firstMovementOrdinal = -1;
        int lastMovementOrdinal = -1;
        boolean matchOver = false;
        int framesServed = 0;
        for (int frame = 0; frame < config.framesPerSession(); frame++) {
            // Live per-frame intent channel (identical to the single-round path): block for THIS frame's
            // intent, then non-blockingly drain any extras that piled up since the last frame, latest-wins.
            LiveManeuverIntent intent = LiveManeuverIntentCodec.readIntent(in);
            intentsConsumed++;
            while (in.available() >= LiveManeuverIntentCodec.FRAME_BYTES) {
                intent = LiveManeuverIntentCodec.readIntent(in);
                intentsConsumed++;
            }
            if (intentShipId < 0L) {
                intentShipId = intent.shipId();
            }
            if (driver.isRegistered(intent.shipId())) {
                anyIntentRegistered = true;
                driver.applyManeuver(intent);
            }
            int effectiveOrdinal = intent.maneuver() ? intent.movementOrdinal() : -1; // -1 == coast
            if (frame == 0) {
                firstMovementOrdinal = effectiveOrdinal;
            } else if (effectiveOrdinal != lastMovementOrdinal) {
                intentChanges++;
            }
            lastMovementOrdinal = effectiveOrdinal;

            AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
            if (snapshot == null) {
                throw new IOException("live snapshot source returned null at frame " + frame);
            }
            if (sourceShipCountFirstFrame < 0) {
                sourceShipCountFirstFrame = snapshot.entityCount();
            }
            AuthoritativeWorldSnapshot governed = policy.govern(snapshot);
            if (governed == null) {
                throw new IOException("publication policy produced a null governed snapshot at frame " + frame);
            }
            if (servedShipCountFirstFrame < 0) {
                servedShipCountFirstFrame = governed.entityCount();
            }
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
            byte[] encoded = frameBytes.toByteArray();
            out.write(encoded);
            out.flush();
            servedBytes.write(encoded);
            framesServed++;
            if (source.matchOver()) {
                // Roster-0 just flushed: the faithful end-of-round signal. Stop the round
                // here; the caller decides whether to reset + stream another round on this same socket.
                matchOver = true;
                break;
            }
        }
        return new CommandRoundResult(framesServed, matchOver, intentsConsumed, intentChanges, intentShipId,
                anyIntentRegistered, firstMovementOrdinal, lastMovementOrdinal,
                drivenStep.shipsCommandedLastTick(), drivenStep.commandsIssued(),
                drivenStep.commandsApplied(), drivenStep.commandsRejected(),
                sourceShipCountFirstFrame, servedShipCountFirstFrame);
    }

    /**
     * Persistent-socket multi-round variant of {@link #serveOneContinuousCommandDrivenClient}: the
     * intent-up lane port of the read-only {@link #serveRoundsOnOneClient}. Serve up to
     * {@code roundsPerSocket} lethal command-driven rounds to a SINGLE connected client over the SAME
     * socket: handshake once, then loop {@link #streamOneCommandRound}s. After a round ends at roster-0 and
     * more rounds remain, {@code matchResetHook} resets the bound engine + re-deploys a fresh roster, and —
     * when {@code -Dcc.server.emitMatchResetFrame} is set — an explicit {@code "MRST"} control frame is
     * emitted inline (after the reset, before the next round's snapshots) on the SAME {@code OutputStream}
     * a {@link MatchAwareSnapshotStream} demuxes. The output is half-closed only after the final round.
     *
     * <p>The SAME {@link ShipCommandDriver} (a fresh {@link CommandDrivenStep} wrapper per round, but the
     * driver + its owned-ship registration shared) drives every round: the up intent channel keeps draining
     * for the same ids after the reset — the seam this port proves. The cached binding follows the engine's
     * re-deployed roster across the reset by construction. A reset that throws
     * propagates as the round's {@link IOException}, half-closing after the rounds served so far (a truncated
     * capture = a visible RED), the same fail-closed posture the read-only round-loop takes on a reset seam.
     */
    private static ContinuousCommandSessionSummary serveContinuousCommandRoundsOnOneClient(
            ServeConfig config,
            NetworkMessageRegistry registry,
            EngineBackedCombatStepBoundary baseStep,
            EngineCombatBinding binding,
            int shipCount,
            ShipCommandDriver driver,
            Socket socket,
            int sessionIndex,
            int roundsPerSocket,
            MatchResetHook matchResetHook) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int peerId = in.readInt();
        int peerMaxSchema = in.readInt();
        WorldSnapshotPublicationPolicy policy =
                WorldSnapshotPublicationPolicy.forPeer(registry, peerMaxSchema, config.relevanceView());
        boolean stableRoster = ServerConfig.resolveBoolean(
                "cc.server.stableRoster", "CC_SERVER_STABLE_ROSTER", "server.stableRoster", false);
        boolean emitMatchResetFrame = ServerConfig.resolveBoolean(
                "cc.server.emitMatchResetFrame", "CC_SERVER_EMIT_MATCH_RESET_FRAME", "server.emitMatchResetFrame", false);
        // pause-until-reentry round-boundary flow-control (default-OFF): when set, after a
        // round ends at roster-0 the server produces NOTHING for the next round until the client sends an
        // explicit "MRER" re-entry frame UP the same socket (the complement of the "MRST" sent DOWN). This
        // is the round-boundary protocol a human-piloted persistent socket needs: the human
        // spends seconds on the results screen + relaunch with no reader draining the socket, and a server
        // that resumed producing into that gap would back-pressure / KICK the silent seat. Default-OFF keeps
        // the proven round-loop byte-identical.
        boolean awaitReentry = ServerConfig.resolveBoolean(
                "cc.server.awaitReentry", "CC_SERVER_AWAIT_REENTRY", "server.awaitReentry", false);
        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream servedBytes = new ByteArrayOutputStream();

        int totalFramesServed = 0;
        int totalIntentsConsumed = 0;
        int totalIntentChanges = 0;
        int totalCommandsIssued = 0;
        int totalCommandsApplied = 0;
        int totalCommandsRejected = 0;
        long firstIntentShipId = -1L;
        boolean anyIntentRegistered = false;
        int firstMovementOrdinal = -1;
        int lastMovementOrdinal = -1;
        int firstRoundSourceShipCount = -1;
        int firstRoundServedShipCount = -1;
        int lastShipsCommandedLastTick = 0;
        int resetFramesEmitted = 0;
        int reentriesAwaited = 0;
        int roundsServed = 0;
        boolean lastRoundMatchOver = false;
        for (int round = 0; round < roundsPerSocket; round++) {
            // A fresh CommandDrivenStep + source per round (per-round command tallies), but the SAME driver
            // (and its registration) is reused -- the seam: the up channel keeps draining for the same ids
            // after the reset. Stable-roster mode is required for the roster-0 matchOver signal.
            CommandDrivenStep drivenStep = new CommandDrivenStep(baseStep, driver);
            EngineBackedLiveWorldSnapshotSource source = stableRoster
                    ? EngineBackedLiveWorldSnapshotSource.stableRoster(
                            drivenStep, binding, config.framesPerSession(), shipCount, config.fixedDt(),
                            null /*contentRegistry*/, new ShipNetworkIdRegistry(), Map.of(), null)
                    : new EngineBackedLiveWorldSnapshotSource(
                            drivenStep, binding, config.framesPerSession(), shipCount, config.fixedDt());
            CommandRoundResult r =
                    streamOneCommandRound(config, policy, source, drivenStep, driver, in, out, servedBytes);
            roundsServed++;
            totalFramesServed += r.framesServed();
            totalIntentsConsumed += r.intentsConsumed();
            totalIntentChanges += r.intentChanges();
            totalCommandsIssued += r.commandsIssued();
            totalCommandsApplied += r.commandsApplied();
            totalCommandsRejected += r.commandsRejected();
            if (firstIntentShipId < 0L && r.intentShipId() >= 0L) {
                firstIntentShipId = r.intentShipId();
            }
            anyIntentRegistered |= r.anyIntentRegistered();
            if (firstMovementOrdinal == -1 && r.framesServed() > 0) {
                firstMovementOrdinal = r.firstMovementOrdinal();
            }
            lastMovementOrdinal = r.lastMovementOrdinal();
            lastShipsCommandedLastTick = r.shipsCommandedLastTick();
            if (firstRoundSourceShipCount < 0) firstRoundSourceShipCount = r.sourceShipCountFirstFrame();
            if (firstRoundServedShipCount < 0) firstRoundServedShipCount = r.servedShipCountFirstFrame();
            lastRoundMatchOver = r.matchOver();
            System.out.println("continuous-command-rounds round=" + round
                    + " frames=" + r.framesServed()
                    + " match_over=" + r.matchOver()
                    + " intents_consumed=" + r.intentsConsumed()
                    + " commands_applied=" + r.commandsApplied()
                    + " ships_commanded=" + r.shipsCommandedLastTick()
                    + " source_ships_first=" + r.sourceShipCountFirstFrame()
                    + " served_ships_first=" + r.servedShipCountFirstFrame());
            // Continue on the SAME socket only if this round ended at roster-0 and more rounds remain.
            if (r.matchOver() && round < roundsPerSocket - 1) {
                matchResetHook.reset();
                System.out.println("continuous-command-rounds reset_fired after round=" + round
                        + " (same socket stays open)");
                if (emitMatchResetFrame) {
                    // Explicit MATCH_RESET control frame INLINE, after the reset and before round+1's
                    // snapshot frames, on the SAME OutputStream the snapshots flow down. A demuxing reader
                    // (MatchAwareSnapshotStream) routes it apart from the AWSS frames.
                    resetFramesEmitted++;
                    ByteArrayOutputStream resetFrame =
                            new ByteArrayOutputStream(MatchResetFrameCodec.frameByteCount());
                    MatchResetFrameCodec.writeFrame(
                            resetFrame, new MatchResetSignal(resetFramesEmitted, round, round + 1));
                    byte[] resetBytes = resetFrame.toByteArray();
                    out.write(resetBytes);
                    out.flush();
                    servedBytes.write(resetBytes);
                    System.out.println("continuous-command-rounds match_reset_frame_emitted seq=" + resetFramesEmitted
                            + " completed_round=" + round + " next_round=" + (round + 1));
                }
                if (awaitReentry) {
                    // Block until the client explicitly asks for the next round UP the same socket. The read
                    // is for a distinct "MRER" frame, NOT a LiveManeuverIntent ("LMVI"): a stray buffered
                    // intent has the wrong magic and fails closed, so the next round starts ONLY on this
                    // signal. While blocked here the server produces nothing -> it idles cleanly across the
                    // human results-screen gap. A truncated/garbled
                    // re-entry frame propagates as the round's IOException (fail-closed, same posture as a
                    // reset seam).
                    MatchReentrySignal reentry = MatchReentryFrameCodec.readFrame(in);
                    reentriesAwaited++;
                    System.out.println("continuous-command-rounds reentry_awaited seq=" + reentry.reentrySeq()
                            + " completed_round=" + reentry.completedRoundIndex()
                            + " next_round=" + reentry.nextRoundIndex());
                }
            } else {
                break;
            }
        }
        socket.shutdownOutput();

        byte[] served = servedBytes.toByteArray();
        System.out.println("continuous-command-rounds-served rounds=" + roundsServed
                + " total_frames=" + totalFramesServed
                + " total_intents=" + totalIntentsConsumed
                + " reset_frames=" + resetFramesEmitted
                + " reentries_awaited=" + reentriesAwaited
                + " last_match_over=" + lastRoundMatchOver
                + " served_bytes=" + served.length
                + " served_sha256=" + sha256Hex(served));
        return new ContinuousCommandSessionSummary(
                sessionIndex,
                peerId,
                peerMaxSchema,
                policy.servedSchemaVersion(),
                totalIntentsConsumed,
                totalIntentChanges,
                firstIntentShipId,
                anyIntentRegistered,
                firstMovementOrdinal,
                lastMovementOrdinal,
                lastShipsCommandedLastTick,
                totalCommandsIssued,
                totalCommandsApplied,
                totalCommandsRejected,
                totalFramesServed,
                firstRoundSourceShipCount,
                firstRoundServedShipCount,
                served.length,
                sha256Hex(served));
    }

    /**
     * Position-mode delegate (back-compat): serves the concurrent multi-client command
     * stream with no explicit despawn lane. Every existing caller
     * ({@link DedicatedCombatServerMain}, the contract) reaches this
     * 7-arg form, which forwards a {@code null} {@link ShipNetworkIdRegistry} — keeping the
     * proven position-keyed snapshot source byte-identical. To EMIT explicit despawn events
     * over the same multiplexed socket, call the 8-arg overload below with a non-null
     * registry (which switches the source to stable-roster mode, the only mode in which a
     * roster shrink can surface).
     */
    public static ConcurrentCommandServeReceipt serveConcurrentCommandDrivenUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            ShipCommandDriver driver,
            int clientsPerMatch,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested) throws IOException {
        return serveConcurrentCommandDrivenUntilShutdown(
                config, binding, driver, clientsPerMatch, null, bindAddress, onReady, shutdownRequested);
    }

    /**
     * Bind one listening socket and serve, MATCH after match until {@code shutdownRequested}
     * fires (or {@code maxSessions} matches are reached), a <em>concurrent multi-client</em>
     * command-driven stream — the categorically-new capability the per-connection serve loops
     * above never had. Every method above ({@link #serveCommandDrivenUntilShutdown},
     * {@link #serveContinuousCommandDrivenUntilShutdown}, …) serves ONE socket fully —
     * advancing the shared engine world for that client alone — before it accepts the next.
     * This method instead gathers {@code clientsPerMatch} connected clients into one
     * <em>match</em> and serves them <em>simultaneously</em> (a handler thread per accepted
     * socket) over a SINGLE shared engine world advanced exactly ONCE per frame: each frame,
     * every client's handler reads that client's own per-frame {@link LiveManeuverIntent} and
     * applies it to the shared {@link ShipCommandDriver} (each client commands its OWN owned
     * ship), the world advances once (the driver issues every held command, the
     * engine integrates), and the one resulting governed frame is broadcast to every client.
     * So N clients each pilot their own diverging ship and all receive the SAME authoritative
     * world stream (byte-identical across the match) — the real dedicated-server shape: one
     * simulation, many clients.
     *
     * <p>Frame lockstep makes it deterministic (not timing-flaky): a per-frame
     * {@link CyclicBarrier} with one party per client rendezvouses all handlers each frame; the
     * barrier action advances the shared world exactly once, after every client has contributed
     * this frame's intent and before any client is sent this frame. The shared world + driver
     * persist across matches (continuity). Fails closed — before binding — when the bound engine
     * roster/frame contract does not satisfy the configured {@link SnapshotStreamIdentity}.
     *
     * <p>When {@code stableIdRegistry} is non-null the match's snapshot source runs in
     * <em>stable-roster</em> mode (survivors keep their network {@code entityId} and a
     * {@code !alive} ship is dropped), and one {@link EntityDespawnEventDetector} observes the
     * RAW full-roster frame each tick (before any per-peer governance, so it is relevance-safe):
     * each detected {@link EntityDespawnEvent} is multiplexed onto every peer's socket right
     * after that peer's {@code AWSS} frame (magic {@code EDSP}), giving the explicit despawn lane
     * its first LIVE producer. A {@code null} registry keeps
     * the byte-identical position-keyed path with no despawn lane.
     */
    public static ConcurrentCommandServeReceipt serveConcurrentCommandDrivenUntilShutdown(
            ServeConfig config,
            EngineCombatBinding binding,
            ShipCommandDriver driver,
            int clientsPerMatch,
            ShipNetworkIdRegistry stableIdRegistry,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(shutdownRequested, "shutdownRequested");
        if (clientsPerMatch < 1) {
            throw new IllegalArgumentException("clientsPerMatch must be >= 1; got " + clientsPerMatch);
        }

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary baseStep = new EngineBackedCombatStepBoundary(world, binding);
        requireAdmittedEngineStream(config, world, baseStep);
        int shipCount = world.roster().shipCount();
        NetworkMessageRegistry registry = NetworkMessageRegistry.authoritativeCombat();

        long serverPid = ProcessHandle.current().pid();
        List<ConcurrentCommandMatchSummary> matches = new ArrayList<>();
        int clientsServed = 0;
        String host;
        int port;
        String shutdownReason;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(config.acceptPollMillis());
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            host = bindAddress.getHostAddress();
            port = serverSocket.getLocalPort();
            if (onReady != null) {
                onReady.onReady(host, port);
            }

            while (true) {
                if (shutdownRequested.getAsBoolean()) {
                    shutdownReason = "shutdown-signal";
                    break;
                }
                if (config.maxSessions() > 0 && matches.size() >= config.maxSessions()) {
                    shutdownReason = "max-sessions";
                    break;
                }
                // Gather one full match's worth of clients before serving, re-checking the
                // shutdown signal between accepts so a half-filled match cannot wedge the server.
                List<Socket> matchSockets = new ArrayList<>(clientsPerMatch);
                boolean shutdownWhileGathering = false;
                while (matchSockets.size() < clientsPerMatch) {
                    if (shutdownRequested.getAsBoolean()) {
                        shutdownWhileGathering = true;
                        break;
                    }
                    Socket accepted;
                    try {
                        accepted = serverSocket.accept();
                    } catch (SocketTimeoutException timeout) {
                        continue; // re-check the shutdown signal, then keep listening
                    }
                    accepted.setTcpNoDelay(true);
                    accepted.setSoTimeout(config.socketTimeoutMillis());
                    matchSockets.add(accepted);
                }
                if (shutdownWhileGathering) {
                    for (Socket socket : matchSockets) {
                        closeQuietly(socket);
                    }
                    shutdownReason = "shutdown-signal";
                    break;
                }
                try {
                    ConcurrentCommandMatchSummary match = serveOneConcurrentMatch(
                            config, registry, baseStep, binding, shipCount, driver, matchSockets,
                            matches.size(), stableIdRegistry);
                    matches.add(match);
                    clientsServed += match.clientCount();
                } catch (IOException perMatch) {
                    // One match's failure (a slow/half-open client, a broken barrier) must not
                    // take down the standing server; log it and keep accepting.
                    System.err.println("[cc-server] dropped concurrent command-driven match "
                            + matches.size() + " on I/O error: " + perMatch);
                } finally {
                    for (Socket socket : matchSockets) {
                        closeQuietly(socket);
                    }
                }
            }
        }
        return new ConcurrentCommandServeReceipt(
                serverPid, host, port, matches.size(), clientsServed, shutdownReason, matches);
    }

    /**
     * The per-match engine graph the {@linkplain #serveConcurrentCommandDrivenFreshPerMatchUntilShutdown
     * fresh-per-match} concurrent serve loop rebuilds for each new match: a fresh
     * {@link EngineCombatBinding} (its own roster + advance counter), the {@link ShipCommandDriver}
     * whose sink wraps THAT binding, and the {@link ShipNetworkIdRegistry} that interns its roster
     * (non-null => stable-roster mode + EDSP despawn lane; null => position-keyed, no lane). All
     * three must come from the SAME fresh binding, so they are produced together by one
     * {@link MatchComponentsSupplier}.
     */
    public record MatchComponents(
            EngineCombatBinding binding,
            ShipCommandDriver driver,
            ShipNetworkIdRegistry stableIdRegistry) {

        public MatchComponents {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(driver, "driver");
            // stableIdRegistry is intentionally nullable (null => no despawn lane).
        }
    }

    /** Supplies a fresh {@link MatchComponents} per match (e.g. via the CLI's reflective factories). */
    @FunctionalInterface
    public interface MatchComponentsSupplier {
        MatchComponents get() throws IOException;
    }

    /**
     * Bind one listening socket and serve, MATCH after match until {@code shutdownRequested}
     * fires (or {@code maxSessions} matches are reached), the concurrent multi-client
     * command-driven stream — but with a <em>fresh engine graph per match</em>: each match gets
     * its own {@link MatchComponents} (binding + driver + id registry) from {@code matchFactory},
     * so a match's world starts from a clean roster + advance counter rather than the single
     * continuously-advancing world the {@linkplain
     * #serveConcurrentCommandDrivenUntilShutdown(ServeConfig, EngineCombatBinding, ShipCommandDriver,
     * int, ShipNetworkIdRegistry, InetAddress, ListenerReady, BooleanSupplier) shared-binding
     * overload} keeps across matches.
     *
     * <p>This is the despawn-watch demo's timing fix:
     * with the shared binding, a roster-shrink despawn scheduled
     * {@code despawnAtAdvance} ticks in fires <em>once</em> for the whole process — the FIRST
     * match despawns and every later match opens with the ship already gone. With a fresh binding
     * per match, every match (i.e. every fresh client connection forming a match, which the
     * connect-per-combat client now opens at COMBAT start) despawns ~{@code despawnAtAdvance} ticks
     * into THAT match, so the human sees the three puppets fly a few seconds and then one vanish —
     * repeatably, on every combat. The trade-off vs. the shared overload is deliberate: cross-match
     * world continuity is given up for per-match determinism, which is exactly what a watchable demo
     * (and a fresh-arena match model) wants.
     *
     * <p>Fails closed — before binding the socket — when the first {@link MatchComponents}'s engine
     * roster/frame contract does not satisfy the configured {@link SnapshotStreamIdentity}; each
     * subsequent match's fresh binding has the same roster shape, so it admits identically.
     */
    public static ConcurrentCommandServeReceipt serveConcurrentCommandDrivenFreshPerMatchUntilShutdown(
            ServeConfig config,
            MatchComponentsSupplier matchFactory,
            int clientsPerMatch,
            InetAddress bindAddress,
            ListenerReady onReady,
            BooleanSupplier shutdownRequested) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(matchFactory, "matchFactory");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(shutdownRequested, "shutdownRequested");
        if (clientsPerMatch < 1) {
            throw new IllegalArgumentException("clientsPerMatch must be >= 1; got " + clientsPerMatch);
        }

        // Admission check on a representative fresh binding (discarded): fail closed before binding
        // the socket if the roster/frame contract is wrong. Each match builds its own fresh graph.
        MatchComponents admissionComponents = matchFactory.get();
        EngineBackedCombatWorldState admissionWorld =
                new EngineBackedCombatWorldState(admissionComponents.binding());
        EngineBackedCombatStepBoundary admissionStep =
                new EngineBackedCombatStepBoundary(admissionWorld, admissionComponents.binding());
        requireAdmittedEngineStream(config, admissionWorld, admissionStep);
        NetworkMessageRegistry registry = NetworkMessageRegistry.authoritativeCombat();

        long serverPid = ProcessHandle.current().pid();
        List<ConcurrentCommandMatchSummary> matches = new ArrayList<>();
        int clientsServed = 0;
        String host;
        int port;
        String shutdownReason;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(config.acceptPollMillis());
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            host = bindAddress.getHostAddress();
            port = serverSocket.getLocalPort();
            if (onReady != null) {
                onReady.onReady(host, port);
            }

            while (true) {
                if (shutdownRequested.getAsBoolean()) {
                    shutdownReason = "shutdown-signal";
                    break;
                }
                if (config.maxSessions() > 0 && matches.size() >= config.maxSessions()) {
                    shutdownReason = "max-sessions";
                    break;
                }
                List<Socket> matchSockets = new ArrayList<>(clientsPerMatch);
                boolean shutdownWhileGathering = false;
                while (matchSockets.size() < clientsPerMatch) {
                    if (shutdownRequested.getAsBoolean()) {
                        shutdownWhileGathering = true;
                        break;
                    }
                    Socket accepted;
                    try {
                        accepted = serverSocket.accept();
                    } catch (SocketTimeoutException timeout) {
                        continue;
                    }
                    accepted.setTcpNoDelay(true);
                    accepted.setSoTimeout(config.socketTimeoutMillis());
                    matchSockets.add(accepted);
                }
                if (shutdownWhileGathering) {
                    for (Socket socket : matchSockets) {
                        closeQuietly(socket);
                    }
                    shutdownReason = "shutdown-signal";
                    break;
                }
                try {
                    // FRESH per match: a clean binding (roster + advance counter), the driver bound
                    // to it, and its own id registry — so this match's despawn schedule starts over.
                    MatchComponents mc = matchFactory.get();
                    EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(mc.binding());
                    EngineBackedCombatStepBoundary baseStep =
                            new EngineBackedCombatStepBoundary(world, mc.binding());
                    int shipCount = world.roster().shipCount();
                    ConcurrentCommandMatchSummary match = serveOneConcurrentMatch(
                            config, registry, baseStep, mc.binding(), shipCount, mc.driver(),
                            matchSockets, matches.size(), mc.stableIdRegistry());
                    matches.add(match);
                    clientsServed += match.clientCount();
                } catch (IOException perMatch) {
                    System.err.println("[cc-server] dropped fresh-per-match concurrent command-driven match "
                            + matches.size() + " on I/O error: " + perMatch);
                } finally {
                    for (Socket socket : matchSockets) {
                        closeQuietly(socket);
                    }
                }
            }
        }
        return new ConcurrentCommandServeReceipt(
                serverPid, host, port, matches.size(), clientsServed, shutdownReason, matches);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentCommandMatchSummary serveOneConcurrentMatch(
            ServeConfig config,
            NetworkMessageRegistry registry,
            EngineBackedCombatStepBoundary baseStep,
            EngineCombatBinding binding,
            int shipCount,
            ShipCommandDriver driver,
            List<Socket> sockets,
            int matchIndex,
            ShipNetworkIdRegistry stableIdRegistry) throws IOException {
        int clients = sockets.size();
        int frames = config.framesPerSession();
        // ONE shared command-driven step + snapshot source for the whole match: the world is
        // advanced exactly ONCE per frame regardless of how many clients are connected. The
        // SAME driver (and engine world) is shared across matches so held intents persist.
        CommandDrivenStep drivenStep = new CommandDrivenStep(baseStep, driver);
        // Position-keyed mode (null registry) hard-throws on a roster shrink, so a despawn can
        // never surface there; stable-roster mode (non-null registry) keeps survivor entityIds
        // stable and drops a !alive ship, the only mode in which an explicit despawn can fire.
        // The position path stays byte-identical.
        EngineBackedLiveWorldSnapshotSource source = stableIdRegistry == null
                ? new EngineBackedLiveWorldSnapshotSource(drivenStep, binding, frames, shipCount, config.fixedDt())
                : EngineBackedLiveWorldSnapshotSource.stableRoster(
                        drivenStep, binding, frames, shipCount, config.fixedDt(),
                        null /*contentRegistry*/, stableIdRegistry, Map.of(), null);
        // The despawn lane runs only in stable-roster mode. The detector observes the RAW
        // full-roster frame (before any per-peer governance), so a ship leaving one peer's
        // relevance set is never mistaken for a despawn — the relevance-safety property.
        EntityDespawnEventDetector despawnDetector =
                stableIdRegistry == null ? null : new EntityDespawnEventDetector();
        // Shared per-frame slot the barrier action fills with this frame's detected despawns;
        // every handler broadcasts the SAME events after its AWSS write (identical across peers).
        List<EntityDespawnEvent>[] frameDespawns =
                despawnDetector == null ? null : (List<EntityDespawnEvent>[]) new List[] { List.<EntityDespawnEvent>of() };

        // Shared per-frame slot the barrier action fills with the one advanced frame; every
        // client handler then governs + encodes + writes it for its own peer.
        AuthoritativeWorldSnapshot[] rawFrame = new AuthoritativeWorldSnapshot[1];
        IOException[] simError = new IOException[1];
        int[] sourceShipCountFirstFrame = { -1 };
        int[] frameCounter = { 0 };
        CyclicBarrier barrier = new CyclicBarrier(clients, () -> {
            // Runs once per frame, on the last handler to arrive, after every client has applied
            // this frame's intent. Advances the SHARED world exactly once and publishes the frame.
            try {
                AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
                if (snapshot == null) {
                    simError[0] = new IOException(
                            "live snapshot source returned null at frame " + frameCounter[0]);
                    rawFrame[0] = null;
                    return;
                }
                if (frameCounter[0] == 0) {
                    sourceShipCountFirstFrame[0] = snapshot.entityCount();
                }
                rawFrame[0] = snapshot;
                if (despawnDetector != null) {
                    // Observe the raw full roster; emits one EDSP per entityId that left this tick.
                    frameDespawns[0] = despawnDetector.observe(snapshot);
                }
                frameCounter[0]++;
            } catch (IOException e) {
                simError[0] = e;
                rawFrame[0] = null;
            }
        });

        List<ConcurrentClientHandler> handlers = new ArrayList<>(clients);
        List<Thread> threads = new ArrayList<>(clients);
        for (int i = 0; i < clients; i++) {
            ConcurrentClientHandler handler = new ConcurrentClientHandler(
                    sockets.get(i), i, frames, registry, config.relevanceView(), driver, barrier,
                    rawFrame, simError, frameDespawns);
            handlers.add(handler);
            threads.add(new Thread(handler, "cc-concurrent-match-" + matchIndex + "-client-" + i));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while serving concurrent match " + matchIndex, ie);
            }
        }
        // Any client failure fails the whole match: the shared world advanced in lockstep with
        // all of them, so a partial match has an inconsistent advance count and is not served.
        List<ConcurrentClientSummary> clientSummaries = new ArrayList<>(clients);
        for (ConcurrentClientHandler handler : handlers) {
            if (handler.error() != null) {
                throw new IOException("concurrent match " + matchIndex + " client "
                        + handler.clientIndex() + " failed: " + handler.error(), handler.error());
            }
            clientSummaries.add(handler.summary());
        }
        if (simError[0] != null) {
            throw simError[0];
        }
        int sourceShips = sourceShipCountFirstFrame[0];
        requireServedSessionMatchesStream(config.streamIdentity(), sourceShips, frames);
        // No relevance scoping in this path, and schema projection never drops a ship, so every
        // peer is broadcast the same roster the source produced.
        return new ConcurrentCommandMatchSummary(
                matchIndex,
                clients,
                frameCounter[0],
                drivenStep.shipsCommandedLastTick(),
                drivenStep.commandsIssued(),
                drivenStep.commandsApplied(),
                drivenStep.commandsRejected(),
                sourceShips,
                sourceShips,
                clientSummaries);
    }

    /**
     * One concurrent-match client: its own socket + per-frame intent channel, rendezvousing
     * with the match's other clients each frame at the shared {@link CyclicBarrier} whose action
     * advances the single shared world. Before the barrier it reads this client's per-frame
     * intent and applies it to the shared driver (this client's OWN ship); after the barrier it
     * governs + encodes + writes the single broadcast frame for its own peer. A failure records
     * the error and resets the barrier so any peer still waiting fails fast instead of hanging.
     */
    private static final class ConcurrentClientHandler implements Runnable {
        private final Socket socket;
        private final int clientIndex;
        private final int frames;
        private final NetworkMessageRegistry registry;
        private final ClientRelevanceView relevanceView;
        private final ShipCommandDriver driver;
        private final CyclicBarrier barrier;
        private final AuthoritativeWorldSnapshot[] rawFrame;
        private final IOException[] simError;
        // Per-frame despawn events to multiplex after each AWSS write (null => no despawn lane,
        // the byte-identical position-keyed path).
        private final List<EntityDespawnEvent>[] frameDespawns;
        private ConcurrentClientSummary summary;
        private Exception error;

        ConcurrentClientHandler(
                Socket socket,
                int clientIndex,
                int frames,
                NetworkMessageRegistry registry,
                ClientRelevanceView relevanceView,
                ShipCommandDriver driver,
                CyclicBarrier barrier,
                AuthoritativeWorldSnapshot[] rawFrame,
                IOException[] simError,
                List<EntityDespawnEvent>[] frameDespawns) {
            this.socket = socket;
            this.clientIndex = clientIndex;
            this.frames = frames;
            this.registry = registry;
            this.relevanceView = relevanceView;
            this.driver = driver;
            this.barrier = barrier;
            this.rawFrame = rawFrame;
            this.simError = simError;
            this.frameDespawns = frameDespawns;
        }

        int clientIndex() {
            return clientIndex;
        }

        ConcurrentClientSummary summary() {
            return summary;
        }

        Exception error() {
            return error;
        }

        @Override
        public void run() {
            try {
                // Handshake first (8B), exactly as the single-shot command path; then this
                // connection carries a STREAM of per-frame LiveManeuverIntents (one per frame).
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int peerId = in.readInt();
                int peerMaxSchema = in.readInt();
                WorldSnapshotPublicationPolicy policy =
                        WorldSnapshotPublicationPolicy.forPeer(registry, peerMaxSchema, relevanceView);
                OutputStream out = socket.getOutputStream();
                ByteArrayOutputStream servedBytes = new ByteArrayOutputStream();
                int intentsConsumed = 0;
                int intentChanges = 0;
                long intentShipId = -1L;
                boolean anyIntentRegistered = false;
                int firstMovementOrdinal = -1;
                int lastMovementOrdinal = -1;
                for (int frame = 0; frame < frames; frame++) {
                    // This client's per-frame intent: block for it, then non-blockingly drain any
                    // buffered extras and keep the last (latest-wins). In lockstep the drain is a
                    // no-op (one intent per frame).
                    LiveManeuverIntent intent = LiveManeuverIntentCodec.readIntent(in);
                    intentsConsumed++;
                    while (in.available() >= LiveManeuverIntentCodec.FRAME_BYTES) {
                        intent = LiveManeuverIntentCodec.readIntent(in);
                        intentsConsumed++;
                    }
                    if (intentShipId < 0L) {
                        intentShipId = intent.shipId();
                    }
                    // A client may only command a registered owned ship; an intent for an
                    // unregistered ship is counted but not applied (the driver would reject it,
                    // and the other clients' held intents must not be disturbed). Synchronized so
                    // the simultaneous per-frame applies from the match's clients are race-free.
                    synchronized (driver) {
                        if (driver.isRegistered(intent.shipId())) {
                            anyIntentRegistered = true;
                            driver.applyManeuver(intent);
                        }
                    }
                    int effectiveOrdinal = intent.maneuver() ? intent.movementOrdinal() : -1; // -1 == coast
                    if (frame == 0) {
                        firstMovementOrdinal = effectiveOrdinal;
                    } else if (effectiveOrdinal != lastMovementOrdinal) {
                        intentChanges++;
                    }
                    lastMovementOrdinal = effectiveOrdinal;

                    // Rendezvous: when every client has applied this frame's intent, the barrier
                    // action advances the SHARED world exactly once and fills rawFrame.
                    barrier.await();
                    if (simError[0] != null) {
                        throw simError[0];
                    }
                    AuthoritativeWorldSnapshot raw = rawFrame[0];
                    if (raw == null) {
                        throw new IOException("shared frame missing at frame " + frame);
                    }
                    AuthoritativeWorldSnapshot governed = policy.govern(raw);
                    if (governed == null) {
                        throw new IOException(
                                "publication policy produced a null governed snapshot at frame " + frame);
                    }
                    ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
                    AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
                    byte[] encoded = frameBytes.toByteArray();
                    out.write(encoded);
                    out.flush();
                    servedBytes.write(encoded);
                    // Multiplex this frame's explicit despawns onto the SAME socket right after
                    // the AWSS frame (magic EDSP). The detector ran on the raw full roster in the
                    // barrier action, so this lane is relevance-safe and identical across peers;
                    // folding the bytes into servedBytes keeps the served SHA over the full
                    // multiplexed stream. A null slot is the no-despawn-lane path (skip).
                    if (frameDespawns != null) {
                        for (EntityDespawnEvent despawn : frameDespawns[0]) {
                            EntityDespawnEventCodec.writeEvent(out, despawn);
                            servedBytes.write(EntityDespawnEventCodec.toBytes(despawn));
                        }
                    }
                }
                socket.shutdownOutput();
                byte[] served = servedBytes.toByteArray();
                summary = new ConcurrentClientSummary(
                        clientIndex,
                        peerId,
                        peerMaxSchema,
                        policy.servedSchemaVersion(),
                        intentShipId,
                        anyIntentRegistered,
                        firstMovementOrdinal,
                        lastMovementOrdinal,
                        intentsConsumed,
                        intentChanges,
                        frames,
                        served.length,
                        sha256Hex(served));
            } catch (Exception e) {
                error = e;
                // Unblock any peers still parked at the barrier so the match fails fast instead
                // of hanging until socket timeout. reset() makes their await() throw.
                barrier.reset();
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort close of an accepted match socket
        }
    }

    /**
     * Per-connection {@link CombatStepBoundary} decorator that runs the shared
     * {@link ShipCommandDriver} each fixed step. Unlike {@link PoseDrivenStep} (which writes
     * the pose AFTER the inner step, Path B), this issues each owned ship's held movement
     * command ({@code ShipAPI.giveCommand}) BEFORE forwarding the world advance to
     * the engine, so the engine integrates the commands on the same tick — the authentic
     * input → integrate → read ordering. The command tallies are per-connection so the
     * session receipt can report ships commanded / commands applied / rejected; the driver
     * (and its per-owned-ship held intents) is shared across connections.
     */
    private static final class CommandDrivenStep implements CombatStepBoundary {
        private final CombatStepBoundary baseStep;
        private final ShipCommandDriver driver;
        private int commandsIssued;
        private int commandsApplied;
        private int commandsRejected;
        private int shipsCommandedLastTick;

        CommandDrivenStep(CombatStepBoundary baseStep, ShipCommandDriver driver) {
            this.baseStep = Objects.requireNonNull(baseStep, "baseStep");
            this.driver = Objects.requireNonNull(driver, "driver");
        }

        @Override
        public CombatWorldState worldState() {
            return baseStep.worldState();
        }

        @Override
        public float effectiveStepAmount(float fixedDt) {
            return baseStep.effectiveStepAmount(fixedDt);
        }

        @Override
        public void step(float fixedDt, long simTick) {
            ShipCommandDriver.AdvanceSummary summary = driver.advance(simTick);
            baseStep.step(fixedDt, simTick);
            commandsIssued += summary.shipsCommanded();
            commandsApplied += summary.commandsApplied();
            commandsRejected += summary.commandsRejected();
            shipsCommandedLastTick = summary.shipsCommanded();
        }

        @Override
        public boolean inputCouplingBypassed() {
            return baseStep.inputCouplingBypassed();
        }

        int commandsIssued() {
            return commandsIssued;
        }

        int commandsApplied() {
            return commandsApplied;
        }

        int commandsRejected() {
            return commandsRejected;
        }

        int shipsCommandedLastTick() {
            return shipsCommandedLastTick;
        }
    }

    private static void requireAdmittedEngineStream(
            ServeConfig config, EngineBackedCombatWorldState world, EngineBackedCombatStepBoundary step) {
        if (!step.directInnerStepReady()) {
            // Self-explaining refusal: report exactly which precondition is unmet. The gate is
            // inputCouplingBypassed() && populatedForUnpausedStep() (= combatSpaceReady && fog(0)
            // && fog(1) && finite timeMult); the roster is NOT a precondition here.
            throw new IllegalStateException("engine-backed direct inner step is not ready for the dedicated server"
                    + " [inputCouplingBypassed=" + step.inputCouplingBypassed()
                    + " populatedForUnpausedStep=" + world.populatedForUnpausedStep()
                    + " combatSpaceReady=" + world.combatSpaceReady()
                    + " fogFriendly=" + world.fogOfWarReady(CombatWorldState.OWNER_FRIENDLY)
                    + " fogEnemy=" + world.fogOfWarReady(CombatWorldState.OWNER_ENEMY)
                    + " timeMult=" + world.timeMult()
                    + " shipCount=" + world.roster().shipCount()
                    + " entityCount=" + world.entityCount() + "]");
        }
        SnapshotStreamIdentity id = config.streamIdentity();
        int shipCount = world.roster().shipCount();
        if (id.hasShipCountRequirement() && id.requiredShipCount() != shipCount) {
            throw new IllegalStateException("dedicated server stream requires " + id.requiredShipCount()
                    + " ships, but engine roster has " + shipCount);
        }
        if (id.hasFrameRequirement() && id.requiredFrames() != config.framesPerSession()) {
            throw new IllegalArgumentException("dedicated server stream requires " + id.requiredFrames()
                    + " frames, but framesPerSession=" + config.framesPerSession());
        }
    }

    private static void requireServedSessionMatchesStream(
            SnapshotStreamIdentity id, int sourceShipCountFirstFrame, int framesServed) {
        // Roster contract is checked against the SOURCE (pre-governance) first-frame ship
        // count: schema projection never drops a ship, but a relevance-scoped peer is served
        // fewer ships than the engine produced, so the served count is the wrong basis here.
        if (id.hasShipCountRequirement() && sourceShipCountFirstFrame != id.requiredShipCount()) {
            throw new IllegalStateException("dedicated server session requires " + id.requiredShipCount()
                    + " ships, but source frame has " + sourceShipCountFirstFrame);
        }
        if (id.hasFrameRequirement() && id.requiredFrames() != framesServed) {
            throw new IllegalStateException("dedicated server session served " + framesServed
                    + " frames, but stream requires " + id.requiredFrames());
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
