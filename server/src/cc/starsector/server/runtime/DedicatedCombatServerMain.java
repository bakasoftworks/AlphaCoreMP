package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.CommandServeReceipt;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.CommandSessionSummary;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ConcurrentClientSummary;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ConcurrentCommandMatchSummary;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ConcurrentCommandServeReceipt;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ContinuousCommandServeReceipt;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ContinuousCommandSessionSummary;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ManeuverGatedStepFactory;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ManeuverServeReceipt;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ManeuverSessionSummary;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.PoseServeReceipt;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.PoseSessionSummary;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ServeConfig;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ServeReceipt;
import cc.starsector.server.runtime.PersistentDedicatedCombatServer.SessionSummary;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Standalone-process entrypoint for the long-lived dedicated combat server
 * ({@link PersistentDedicatedCombatServer}). This is the runnable whole the project
 * never had: a {@code main} you start and leave running, that binds one socket,
 * advertises its bound {@code host:port} into {@code --port-file}, and serves
 * registry-governed live engine combat to each independent client that connects
 * until you drop the {@code --shutdown-file} sentinel.
 *
 * <p>The server carries no game identity. The {@link EngineCombatBinding} is supplied
 * by a pluggable factory named with {@code --binding-factory <fqcn>} — a class with a
 * {@code public static EngineCombatBinding create()} method. The game-free smoke and
 * contract pass a fake reflective factory on the classpath; the real-engine run is
 * the same {@code main} with the scout's real-engine binding factory injected (the
 * same injection model {@link DedicatedCombatServerBoot} already uses).
 *
 * <pre>
 *   java ... cc.starsector.server.runtime.DedicatedCombatServerMain \
 *       --binding-factory cc.starsector.server.tests.FakeEngineBindingFactory \
 *       --frames-per-session 24 --required-ships 3 --max-sessions 2 \
 *       --port-file run/port.properties --shutdown-file run/shutdown.signal [--off-loopback]
 * </pre>
 *
 * <p>With {@code --maneuver} the same process serves the <em>command+observe</em>
 * round-trip instead of the read-only stream: it selects
 * {@link PersistentDedicatedCombatServer#serveManeuverRoundTripUntilShutdown} so each
 * connected client first sends a {@link LiveManeuverIntent} (the registered
 * {@code client.ship_movement.requested} frame the {@code CC_Client} mod emits on
 * input) which the server gates onto the per-tick command-authority MANEUVER before
 * streaming the governed frames back. The gated step / content table / ship-owner map
 * the maneuver path needs are supplied by a second pluggable factory,
 * {@code --maneuver-factory <fqcn>} — a class with a
 * {@code public static ManeuverComponents create(EngineCombatBinding)} method, the same
 * injection model as {@code --binding-factory} (the real-engine run injects the scout's
 * {@code HeadlessCombatContext}-backed factory; the contract injects a game-free one).
 * This is the server side the client arc's visible pilot run needs — wiring the existing
 * maneuver-serving path onto the CLI, no new maneuver logic.
 *
 * <pre>
 *   java ... cc.starsector.server.runtime.DedicatedCombatServerMain --maneuver \
 *       --binding-factory cc.starsector.server.tests.FakeEngineBindingFactory \
 *       --maneuver-factory cc.starsector.server.tests.FakeManeuverComponentsFactory \
 *       --frames-per-session 20 --required-ships 3 \
 *       --port-file run/port.properties --shutdown-file run/shutdown.signal [--off-loopback]
 * </pre>
 *
 * <p>With {@code --pose-driven} the same process serves a <em>server-authoritative
 * pose-driven</em> stream: it selects
 * {@link PersistentDedicatedCombatServer#servePoseDrivenUntilShutdown}, where a
 * {@link ShipPoseDriver} owns the motion. Each tick the engine inner step runs and then
 * the driver writes every owned ship's authoritative pose directly (via
 * {@code setLoc}/{@code setVel}, Path B, confirmed on the real engine)
 * before the snapshot is read, so the frames the client drains carry
 * the server-driven trajectory rather than the engine's own per-ship motion. This is the
 * authority complement of {@code --maneuver}: there the client's intent is gated onto the
 * engine and the <em>engine</em> integrates the motion; here the <em>server</em> integrates
 * it and writes the pose. The pre-registered driver (its owned ships, kinematics, and held
 * intents) is supplied by a third pluggable factory, {@code --pose-driver-factory <fqcn>} —
 * a class with a {@code public static ShipPoseDriver create(EngineCombatBinding)} method,
 * the same injection model as {@code --binding-factory} (the real-engine run injects the
 * scout's pose-write-capable binding + driver; the contract injects a game-free one). This
 * is the wire: the per-tick pose driver, until now
 * standing alone, driven inside the live server over a real socket — no new pose-write
 * logic.
 *
 * <pre>
 *   java ... cc.starsector.server.runtime.DedicatedCombatServerMain --pose-driven \
 *       --binding-factory cc.starsector.server.tests.FakePoseWritableEngineBindingFactory \
 *       --pose-driver-factory cc.starsector.server.tests.FakePoseDriverFactory \
 *       --frames-per-session 20 --required-ships 3 \
 *       --port-file run/port.properties --shutdown-file run/shutdown.signal [--off-loopback]
 * </pre>
 *
 * <p>With {@code --command-driven} the same process serves a <em>command-driven</em>
 * stream: it selects
 * {@link PersistentDedicatedCombatServer#serveCommandDrivenUntilShutdown}, where a
 * {@link ShipCommandDriver} routes each connected client's movement intent to that
 * client's OWN owned ship every tick ({@code ShipAPI.giveCommand}) and lets the
 * engine integrate the motion. Each client advertises {@code (peerId, maxSchema)} and then
 * sends one {@link LiveManeuverIntent} naming the movement to hold on its own ship; the
 * shared driver holds a <em>per-owned-ship</em> intent, so a second client commanding a
 * second ship leaves the first client's held command in force — two clients each pilot
 * their own ship. This is the COMMAND complement of {@code --pose-driven} (there the
 * <em>server</em> scripts the pose; here the server only <em>commands</em>) and
 * the multi-client successor to the single-intent {@code --maneuver} mode. The
 * pre-registered driver (its owned ships + command sink) is supplied by a pluggable factory,
 * {@code --command-driver-factory <fqcn>} — a class with a
 * {@code public static ShipCommandDriver create(EngineCombatBinding)} method, the same
 * injection model as {@code --pose-driver-factory}.
 *
 * <pre>
 *   java ... cc.starsector.server.runtime.DedicatedCombatServerMain --command-driven \
 *       --binding-factory cc.starsector.server.tests.FakeCommandableEngineBindingFactory \
 *       --command-driver-factory cc.starsector.server.tests.FakeCommandDriverFactory \
 *       --frames-per-session 20 --required-ships 3 \
 *       --port-file run/port.properties --shutdown-file run/shutdown.signal [--off-loopback]
 * </pre>
 */
public final class DedicatedCombatServerMain {

    private static final float DEFAULT_DT = SimTickRate.fixedDt();

    private DedicatedCombatServerMain() {
    }

    public static void main(String[] args) {
        try {
            String bindingFactory = null;
            int framesPerSession = 24;
            float fixedDt = DEFAULT_DT;
            int requiredShips = 0;
            int maxSessions = 0;
            int acceptPollMillis = 250;
            int socketTimeoutMillis = 15_000;
            boolean offLoopback = false;
            String streamId = "persistent-dedicated-combat-server";
            int streamVersion = 1;
            Path portFile = null;
            Path shutdownFile = null;
            boolean maneuver = false;
            String maneuverFactory = null;
            boolean poseDriven = false;
            String poseDriverFactory = null;
            boolean commandDriven = false;
            String commandDriverFactory = null;
            // --continuous modifies --command-driven to serve the live per-frame
            // intent channel (serveContinuousCommandDrivenUntilShutdown) instead of the
            // single-held-intent serveCommandDrivenUntilShutdown — a mid-session command change
            // lands on the next frame without a reconnect.
            boolean continuous = false;
            // --concurrent modifies --command-driven to serve MULTIPLE clients at once
            // over ONE shared engine world advanced once per frame
            // (serveConcurrentCommandDrivenUntilShutdown), a handler thread per accepted socket.
            // --clients-per-match sets how many clients form one concurrent match (default 2).
            boolean concurrent = false;
            int clientsPerMatch = 2;
            // --despawn-lane makes --concurrent serve the STABLE-ROSTER 8-arg overload
            // of serveConcurrentCommandDrivenUntilShutdown (a fresh ShipNetworkIdRegistry) instead
            // of the position-keyed 7-arg one, turning on the explicit EDSP despawn lane — the only
            // live producer of client despawns. This is the server side the human-watchable
            // despawn demo needs (a puppet vanishes when the server's roster shrinks).
            boolean despawnLane = false;
            // --fresh-binding-per-match makes --concurrent rebuild the engine graph
            // (binding + driver + id registry) for EACH match instead of sharing one
            // continuously-advancing world across matches
            // (serveConcurrentCommandDrivenFreshPerMatchUntilShutdown). This is the despawn-watch
            // demo's timing fix: with the shared binding a roster-shrink despawn fires once for the
            // whole process (the first match), so a later combat opens with the ship already gone;
            // a fresh binding per match despawns ~despawnAtAdvance ticks into EVERY match, so the
            // connect-per-combat client sees the staged vanish on every combat.
            boolean freshBindingPerMatch = false;
            // --match-outcome runs the LIVE headless match-outcome loop
            // (PersistentDedicatedCombatServer.runMatchOutcomeLoop) instead of a snapshot serve
            // loop: advance the engine per tick, fold the server's roster-derived combat OUTCOME
            // through the CombatOutcomeEventDetector until a side is eliminated, then
            // cross the single decided event to a real separate-JVM consumer over a socket. This
            // is the missing LIVE wire-in for the roster outcome producer (until now
            // only exercised by unit smokes with hand-fed views). --max-ticks bounds the loop;
            // --outcome-dir receives the published-event artifacts.
            boolean matchOutcome = false;
            String outcomeDir = null;
            // Verdict-persist: when set, the --match-outcome loop persists the TERMINAL
            // verdict (decided / draw-on-timeout / aborted — MatchTerminalVerdict) plus the re-sim
            // identity meta to this .ccvr file on match end. Default null = OFF = today's behaviour
            // byte-unchanged (the decided event still crosses the wire; nothing is written to disk).
            String verdictRecord = null;
            int maxTicks = 600;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--binding-factory" -> bindingFactory = args[++i];
                    case "--frames-per-session" -> framesPerSession = Integer.parseInt(args[++i]);
                    case "--fixed-dt" -> fixedDt = Float.parseFloat(args[++i]);
                    case "--required-ships" -> requiredShips = Integer.parseInt(args[++i]);
                    case "--max-sessions" -> maxSessions = Integer.parseInt(args[++i]);
                    case "--accept-poll-ms" -> acceptPollMillis = Integer.parseInt(args[++i]);
                    case "--socket-timeout-ms" -> socketTimeoutMillis = Integer.parseInt(args[++i]);
                    case "--off-loopback" -> offLoopback = true;
                    case "--stream-id" -> streamId = args[++i];
                    case "--stream-version" -> streamVersion = Integer.parseInt(args[++i]);
                    case "--port-file" -> portFile = Path.of(args[++i]);
                    case "--shutdown-file" -> shutdownFile = Path.of(args[++i]);
                    case "--maneuver" -> maneuver = true;
                    case "--maneuver-factory" -> maneuverFactory = args[++i];
                    case "--pose-driven" -> poseDriven = true;
                    case "--pose-driver-factory" -> poseDriverFactory = args[++i];
                    case "--command-driven" -> commandDriven = true;
                    case "--command-driver-factory" -> commandDriverFactory = args[++i];
                    case "--continuous" -> continuous = true;
                    case "--concurrent" -> concurrent = true;
                    case "--clients-per-match" -> clientsPerMatch = Integer.parseInt(args[++i]);
                    case "--despawn-lane" -> despawnLane = true;
                    case "--fresh-binding-per-match" -> freshBindingPerMatch = true;
                    case "--match-outcome" -> matchOutcome = true;
                    case "--outcome-dir" -> outcomeDir = args[++i];
                    case "--verdict-record" -> verdictRecord = args[++i];
                    case "--max-ticks" -> maxTicks = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            // The pose-driven fence:
            // --pose-driven drives a server-side hand-rolled
            // semi-implicit-Euler integrator (ShipPoseDriver) that deliberately OVERWRITES the
            // engine's own integration each tick (a recorded anti-pattern; the project rules require
            // explicit sign-off for a server-side reimplementation of engine behavior). It is kept as the
            // proven write-authority test stimulus (exercised directly in
            // EngineBridgeSmoke) but is NOT a supported production serve mode while the faithful
            // command-driven lane (ShipCommandDriver -> ShipAPI.giveCommand, engine-integrated) exists.
            // Reject it here, before any other validation, so the message is unambiguous;
            // servePoseDrivenUntilShutdown + ShipPoseDriver stay intact, just unreachable from the CLI.
            if (poseDriven) {
                throw new IllegalArgumentException(
                        "--pose-driven is not a supported production serve mode: it runs a"
                                + " server-side hand-rolled integrator (ShipPoseDriver) that overwrites the"
                                + " engine's own integration. Use --command-driven --command-driver-factory"
                                + " <fqcn> (ShipCommandDriver issues ShipAPI.giveCommand and the engine"
                                + " integrates the motion). The ShipPoseDriver write-authority stimulus"
                                + " remains available to tests directly (EngineBridgeSmoke).");
            }
            if (bindingFactory == null) {
                throw new IllegalArgumentException("required: --binding-factory <fqcn>");
            }
            // The snapshot serve loops bind a standing listening socket and need a port/shutdown
            // file; the --match-outcome loop is self-terminating (it advances to a decision and
            // publishes once), so it needs neither — it needs --outcome-dir for the published-event
            // artifacts instead.
            if (!matchOutcome && (portFile == null || shutdownFile == null)) {
                throw new IllegalArgumentException(
                        "required: --binding-factory <fqcn> --port-file <path> --shutdown-file <path>"
                                + " [--frames-per-session N --required-ships K --max-sessions N --off-loopback ...]"
                                + " [--maneuver --maneuver-factory <fqcn>]"
                                + " [--pose-driven --pose-driver-factory <fqcn>]"
                                + " [--command-driven --command-driver-factory <fqcn>]"
                                + " [--match-outcome --outcome-dir <dir> --max-ticks N]");
            }
            if (matchOutcome && outcomeDir == null) {
                throw new IllegalArgumentException(
                        "--match-outcome requires --outcome-dir <dir> (it receives the published"
                                + " combat-outcome event artifacts of the real separate-JVM consumer crossing)");
            }
            if ((maneuver ? 1 : 0) + (poseDriven ? 1 : 0) + (commandDriven ? 1 : 0) + (matchOutcome ? 1 : 0) > 1) {
                throw new IllegalArgumentException(
                        "--maneuver, --pose-driven, --command-driven and --match-outcome are mutually exclusive modes");
            }
            if (maneuver && maneuverFactory == null) {
                throw new IllegalArgumentException(
                        "--maneuver requires --maneuver-factory <fqcn> (a class with"
                                + " public static ManeuverComponents create(EngineCombatBinding))");
            }
            if (!maneuver && maneuverFactory != null) {
                throw new IllegalArgumentException("--maneuver-factory requires --maneuver");
            }
            if (poseDriven && poseDriverFactory == null) {
                throw new IllegalArgumentException(
                        "--pose-driven requires --pose-driver-factory <fqcn> (a class with"
                                + " public static ShipPoseDriver create(EngineCombatBinding))");
            }
            if (!poseDriven && poseDriverFactory != null) {
                throw new IllegalArgumentException("--pose-driver-factory requires --pose-driven");
            }
            if (commandDriven && commandDriverFactory == null) {
                throw new IllegalArgumentException(
                        "--command-driven requires --command-driver-factory <fqcn> (a class with"
                                + " public static ShipCommandDriver create(EngineCombatBinding))");
            }
            if (!commandDriven && commandDriverFactory != null) {
                throw new IllegalArgumentException("--command-driver-factory requires --command-driven");
            }
            if (continuous && !commandDriven) {
                throw new IllegalArgumentException(
                        "--continuous requires --command-driven (it modifies the command-driven serve loop"
                                + " to a live per-frame intent channel)");
            }
            if (concurrent && !commandDriven) {
                throw new IllegalArgumentException(
                        "--concurrent requires --command-driven (it modifies the command-driven serve loop"
                                + " to serve multiple clients at once over one shared world)");
            }
            if (concurrent && continuous) {
                throw new IllegalArgumentException(
                        "--concurrent and --continuous are mutually exclusive: --concurrent already drives"
                                + " each client's per-frame intent channel, for many clients at once");
            }
            if (concurrent && clientsPerMatch < 1) {
                throw new IllegalArgumentException(
                        "--clients-per-match must be >= 1; got " + clientsPerMatch);
            }
            if (despawnLane && !concurrent) {
                throw new IllegalArgumentException(
                        "--despawn-lane requires --concurrent (it selects the stable-roster overload"
                                + " of the concurrent command-driven serve loop, the only live"
                                + " producer of the explicit EDSP despawn lane)");
            }
            if (freshBindingPerMatch && !concurrent) {
                throw new IllegalArgumentException(
                        "--fresh-binding-per-match requires --concurrent (it rebuilds the concurrent"
                                + " serve loop's engine graph per match instead of sharing one world)");
            }

            // Lane-posture M3: dump-then-enforce — this main's posture on
            // record in every boot log; contract-test child spawns are file-less => unpostured
            // => enforce inert (all 6 spawners verified to tolerate the extra lines).
            PostureRegistry.dump(System.out);
            PostureRegistry.enforce();

            EngineCombatBinding binding = buildBinding(bindingFactory);

            if (matchOutcome) {
                // LIVE match-outcome run loop: advance the engine per tick and fold the
                // server's roster-derived combat OUTCOME (combatOutcomeFromRosterOrNull())
                // through the CombatOutcomeEventDetector until one fighting side is eliminated, then
                // cross the single decided event to a real separate-JVM consumer over a socket. No
                // standing listening socket: the loop self-terminates on a decision (or the tick
                // budget / shutdown sentinel). This is the missing LIVE wire-in for the
                // roster outcome producer.
                final Path matchShutdown = shutdownFile;
                if (matchShutdown != null) {
                    Files.deleteIfExists(matchShutdown);
                }
                BooleanSupplier stop = matchShutdown != null
                        ? () -> Files.exists(matchShutdown)
                        : () -> false;
                PersistentDedicatedCombatServer.MatchOutcomeReceipt outcome =
                        PersistentDedicatedCombatServer.runMatchOutcomeLoop(binding, fixedDt, maxTicks, stop);
                Path outDir = Path.of(outcomeDir);
                Files.createDirectories(outDir);
                String publishedHost = "-";
                int publishedPort = -1;
                long publishedBytes = -1L;
                String publishedSha = "-";
                if (outcome.decided()) {
                    // The decided outcome is crossed to a real separate-JVM CombatOutcomeEventSocketClient
                    // (the existing consumer); publish* throws on any byte/count/identity
                    // mismatch, so a clean return is the live-consumer proof.
                    CombatOutcomeEventSocketPublisher.Receipt receipt = offLoopback
                            ? CombatOutcomeEventSocketPublisher.publishOffLoopback(
                                    List.of(outcome.decidedEvent()),
                                    outDir.resolve("received.cbto"),
                                    outDir.resolve("receipt.txt"),
                                    outDir.resolve("client.log"))
                            : CombatOutcomeEventSocketPublisher.publishLoopback(
                                    List.of(outcome.decidedEvent()),
                                    outDir.resolve("received.cbto"),
                                    outDir.resolve("receipt.txt"),
                                    outDir.resolve("client.log"));
                    publishedHost = receipt.socketHost();
                    publishedPort = receipt.socketPort();
                    publishedBytes = receipt.bytesPublished();
                    publishedSha = receipt.publishedSha256();
                }
                // Verdict-persist: the match-end hook. The terminal verdict covers the
                // cases CombatOutcomeEvent cannot (a max-ticks DRAW, a shutdown ABORTED), and the .ccvr
                // file binds it to the re-sim identity meta — the verdict's per-match record line the
                // verifiable-matches gap table named (gap #7). OFF (flag absent) = nothing written.
                MatchTerminalVerdict verdict = MatchTerminalVerdict.from(outcome);
                String verdictRecordPath = "-";
                if (verdictRecord != null) {
                    verdictRecordPath = MatchVerdictRecordCodec
                            .writeRecord(Path.of(verdictRecord), verdict, MatchRecordMeta.base())
                            .toAbsolutePath().normalize().toString();
                }
                System.out.println("dedicated-combat-server-match-outcome DONE"
                        + " decided=" + outcome.decided()
                        + " winning_side=" + outcome.winningSideId()
                        + " decided_tick=" + outcome.decidedTick()
                        + " combat_over=" + outcome.combatOver()
                        + " ticks_advanced=" + outcome.ticksAdvanced()
                        + " stop_reason=" + outcome.stopReason()
                        + " verdict_kind=" + verdict.kind()
                        + " verdict_record=" + verdictRecordPath
                        + " published_host=" + publishedHost
                        + " published_port=" + publishedPort
                        + " published_bytes=" + publishedBytes
                        + " published_sha256=" + publishedSha);
                System.exit(0);
            }

            SnapshotStreamIdentity streamIdentity = new SnapshotStreamIdentity(
                    streamId,
                    streamVersion,
                    requiredShips,
                    framesPerSession,
                    true);
            // Real engine path: if buildBinding deployed the demo roster (RealEngineCombatBindingFactory,
            // construction-time L1 Option A), bound the snapshot source by the deploy-ledger count rather
            // than the pre-drain getObjects().getList(Ship) read (0 until the first advanceInner drains the
            // pending-add queue) that made serveUntilShutdown fail closed shipCountBound=0.
            // Override 0 (no roster deployed — a fake/other factory) keeps the live read.
            int shipCountOverride = RealEngineDemoRoster.deployed() ? RealEngineDemoRoster.deployedShipCount() : 0;
            ServeConfig config = new ServeConfig(
                    streamIdentity,
                    framesPerSession,
                    fixedDt,
                    null,
                    maxSessions,
                    acceptPollMillis,
                    socketTimeoutMillis,
                    shipCountOverride);

            InetAddress bindAddress = offLoopback
                    ? requireNonLoopbackIpv4Address()
                    : InetAddress.getLoopbackAddress();
            // Start clean: a stale sentinel from a prior run must not pre-trip shutdown.
            Files.deleteIfExists(shutdownFile);
            final Path shutdownSignal = shutdownFile;
            final Path portFilePath = portFile;

            if (maneuver) {
                // Command+observe round-trip: each client sends a LiveManeuverIntent the
                // server gates onto the per-tick command-authority MANEUVER before streaming
                // the governed frames back. The gated step / content table / ship-owner map
                // are injected the same reflective way the binding is (--maneuver-factory).
                ManeuverComponents components = buildManeuverComponents(maneuverFactory, binding);
                ManeuverServeReceipt receipt = PersistentDedicatedCombatServer.serveManeuverRoundTripUntilShutdown(
                        config,
                        binding,
                        components.gatedStepFactory(),
                        components.contentRegistry(),
                        components.ownerByShipId(),
                        bindAddress,
                        (host, port) -> writePortFile(portFilePath, host, port),
                        () -> Files.exists(shutdownSignal));
                for (ManeuverSessionSummary session : receipt.sessions()) {
                    System.out.println("maneuver-session"
                            + " index=" + session.sessionIndex()
                            + " peer_id=" + session.peerId()
                            + " peer_max_schema=" + session.peerMaxSchema()
                            + " served_schema=" + session.servedSchemaVersion()
                            + " intent_received=" + session.intentReceived()
                            + " intent_maneuver=" + session.intentManeuver()
                            + " intent_ship_id=" + session.intentShipId()
                            + " intent_movement_ordinal=" + session.intentMovementOrdinal()
                            + " inbound_intent_bytes=" + session.inboundIntentBytes()
                            + " maneuver_applications=" + session.maneuverApplications()
                            + " frames=" + session.framesServed()
                            + " ship_count=" + session.shipCountFirstFrame()
                            + " flux_records=" + session.fluxRecordsFirstFrame()
                            + " weapon_mount_records=" + session.weaponMountRecordsFirstFrame()
                            + " served_bytes=" + session.servedBytes()
                            + " served_sha256=" + session.servedSha256());
                }
                System.out.println("dedicated-combat-server-maneuver DONE"
                        + " host=" + receipt.host()
                        + " port=" + receipt.port()
                        + " sessions_served=" + receipt.sessionsServed()
                        + " shutdown_reason=" + receipt.shutdownReason());
                System.exit(0);
            }

            if (poseDriven) {
                // Server-authoritative pose-driven stream: a ShipPoseDriver owns the motion,
                // writing every owned ship's pose each tick (Path B) before the
                // snapshot read, so the client drains the server-driven trajectory. The driver
                // (owned ships, kinematics, held intents) is injected the same reflective way
                // the binding is (--pose-driver-factory).
                ShipPoseDriver driver = buildPoseDriver(poseDriverFactory, binding);
                PoseServeReceipt receipt = PersistentDedicatedCombatServer.servePoseDrivenUntilShutdown(
                        config,
                        binding,
                        driver,
                        bindAddress,
                        (host, port) -> writePortFile(portFilePath, host, port),
                        () -> Files.exists(shutdownSignal));
                for (PoseSessionSummary session : receipt.sessions()) {
                    System.out.println("pose-session"
                            + " index=" + session.sessionIndex()
                            + " peer_id=" + session.peerId()
                            + " peer_max_schema=" + session.peerMaxSchema()
                            + " served_schema=" + session.servedSchemaVersion()
                            + " ships_driven=" + session.shipsDriven()
                            + " poses_applied=" + session.posesApplied()
                            + " poses_rejected=" + session.posesRejected()
                            + " frames=" + session.framesServed()
                            + " source_ships=" + session.sourceShipCountFirstFrame()
                            + " served_ships=" + session.servedShipCountFirstFrame()
                            + " served_bytes=" + session.servedBytes()
                            + " served_sha256=" + session.servedSha256());
                }
                System.out.println("dedicated-combat-server-pose-driven DONE"
                        + " host=" + receipt.host()
                        + " port=" + receipt.port()
                        + " sessions_served=" + receipt.sessionsServed()
                        + " shutdown_reason=" + receipt.shutdownReason());
                System.exit(0);
            }

            if (commandDriven) {
                // Command-driven stream: a ShipCommandDriver routes each client's movement
                // intent to that client's OWN owned ship each tick (
                // ShipAPI.giveCommand) and the engine integrates the motion. The shared driver
                // holds a per-owned-ship intent, so two clients each command their own ship.
                // The driver (owned ships + command sink) is injected the same reflective way
                // the binding is (--command-driver-factory).
                ShipCommandDriver driver = buildCommandDriver(commandDriverFactory, binding);
                if (concurrent) {
                    // Concurrent multi-client serve: gather clientsPerMatch clients into one match
                    // and serve them simultaneously (a handler thread per socket) over ONE shared
                    // engine world advanced once per frame; every client commands its own ship and
                    // all receive the same authoritative broadcast. With --despawn-lane
                    // a fresh ShipNetworkIdRegistry selects the stable-roster 8-arg
                    // overload, turning on the explicit EDSP despawn lane so a roster shrink emits a
                    // client-consumable despawn; without it, null => the byte-identical position-keyed
                    // path.
                    ShipNetworkIdRegistry stableIdRegistry = despawnLane ? new ShipNetworkIdRegistry() : null;
                    ConcurrentCommandServeReceipt concurrentReceipt;
                    if (freshBindingPerMatch) {
                        // Rebuild the engine graph per match (fresh binding + driver +
                        // id registry from the same reflective factories) so each match's roster +
                        // advance counter start clean and a scheduled roster-shrink despawn fires
                        // ~despawnAtAdvance ticks into EVERY match, not just the first. This is the
                        // despawn-watch demo's combat-aligned timing fix (paired with the
                        // connect-per-combat client). The fresh path always carries its own despawn
                        // lane iff --despawn-lane (a fresh registry per match).
                        final String bindingFactoryName = bindingFactory;
                        final String commandDriverFactoryName = commandDriverFactory;
                        final boolean perMatchLane = despawnLane;
                        PersistentDedicatedCombatServer.MatchComponentsSupplier matchFactory = () -> {
                            try {
                                EngineCombatBinding freshBinding = buildBinding(bindingFactoryName);
                                ShipCommandDriver freshDriver =
                                        buildCommandDriver(commandDriverFactoryName, freshBinding);
                                ShipNetworkIdRegistry freshRegistry =
                                        perMatchLane ? new ShipNetworkIdRegistry() : null;
                                return new PersistentDedicatedCombatServer.MatchComponents(
                                        freshBinding, freshDriver, freshRegistry);
                            } catch (Exception e) {
                                throw new IOException("failed to build fresh per-match components", e);
                            }
                        };
                        concurrentReceipt =
                                PersistentDedicatedCombatServer.serveConcurrentCommandDrivenFreshPerMatchUntilShutdown(
                                        config,
                                        matchFactory,
                                        clientsPerMatch,
                                        bindAddress,
                                        (host, port) -> writePortFile(portFilePath, host, port),
                                        () -> Files.exists(shutdownSignal));
                    } else {
                        concurrentReceipt =
                                PersistentDedicatedCombatServer.serveConcurrentCommandDrivenUntilShutdown(
                                        config,
                                        binding,
                                        driver,
                                        clientsPerMatch,
                                        stableIdRegistry,
                                        bindAddress,
                                        (host, port) -> writePortFile(portFilePath, host, port),
                                        () -> Files.exists(shutdownSignal));
                    }
                    for (ConcurrentCommandMatchSummary match : concurrentReceipt.matches()) {
                        System.out.println("concurrent-command-match"
                                + " index=" + match.matchIndex()
                                + " clients=" + match.clientCount()
                                + " frames_advanced=" + match.framesAdvanced()
                                + " ships_commanded=" + match.shipsCommandedLastTick()
                                + " commands_issued=" + match.commandsIssued()
                                + " commands_applied=" + match.commandsApplied()
                                + " commands_rejected=" + match.commandsRejected()
                                + " source_ships=" + match.sourceShipCountFirstFrame()
                                + " served_ships=" + match.servedShipCountFirstFrame());
                        for (ConcurrentClientSummary client : match.clients()) {
                            System.out.println("concurrent-command-client"
                                    + " match=" + match.matchIndex()
                                    + " client=" + client.clientIndex()
                                    + " peer_id=" + client.peerId()
                                    + " peer_max_schema=" + client.peerMaxSchema()
                                    + " served_schema=" + client.servedSchemaVersion()
                                    + " intent_ship_id=" + client.intentShipId()
                                    + " intent_ship_registered=" + client.intentShipRegistered()
                                    + " first_movement_ordinal=" + client.firstMovementOrdinal()
                                    + " last_movement_ordinal=" + client.lastMovementOrdinal()
                                    + " intents_consumed=" + client.intentsConsumed()
                                    + " intent_changes=" + client.intentChanges()
                                    + " frames=" + client.framesServed()
                                    + " served_bytes=" + client.servedBytes()
                                    + " served_sha256=" + client.servedSha256());
                        }
                    }
                    System.out.println("dedicated-combat-server-concurrent-command-driven DONE"
                            + " host=" + concurrentReceipt.host()
                            + " port=" + concurrentReceipt.port()
                            + " matches_served=" + concurrentReceipt.matchesServed()
                            + " clients_served=" + concurrentReceipt.clientsServed()
                            + " shutdown_reason=" + concurrentReceipt.shutdownReason());
                    System.exit(0);
                }
                if (continuous) {
                    // Live per-frame intent channel: the server drains a fresh intent off the
                    // single connection each frame (latest-wins) before issuing the command, so a
                    // mid-session command change lands without a reconnect.
                    // Wire the SAME L1 MATCH_RESET hook the read-only branch uses
                    // (below) so -Dcc.server.matchReset=true + -Dcc.server.roundsPerSocket>1 serves
                    // multiple lethal rounds over ONE socket on the intent-up lane, resetting +
                    // re-deploying the bound real-engine roster between rounds (the 7-arg overload
                    // dispatches to serveContinuousCommandRoundsOnOneClient when a hook is present).
                    // null on a fake/other binding (no demo roster) keeps the single-round path
                    // byte-identical, exactly as the read-only branch does.
                    PersistentDedicatedCombatServer.MatchResetHook continuousResetHook =
                            buildMatchResetHookOrNull();
                    ContinuousCommandServeReceipt continuousReceipt =
                            PersistentDedicatedCombatServer.serveContinuousCommandDrivenUntilShutdown(
                                    config,
                                    binding,
                                    driver,
                                    bindAddress,
                                    (host, port) -> writePortFile(portFilePath, host, port),
                                    () -> Files.exists(shutdownSignal),
                                    continuousResetHook);
                    for (ContinuousCommandSessionSummary session : continuousReceipt.sessions()) {
                        System.out.println("continuous-command-session"
                                + " index=" + session.sessionIndex()
                                + " peer_id=" + session.peerId()
                                + " peer_max_schema=" + session.peerMaxSchema()
                                + " served_schema=" + session.servedSchemaVersion()
                                + " intents_consumed=" + session.intentsConsumed()
                                + " intent_changes=" + session.intentChanges()
                                + " intent_ship_id=" + session.intentShipId()
                                + " intent_ship_registered=" + session.intentShipRegistered()
                                + " first_movement_ordinal=" + session.firstMovementOrdinal()
                                + " last_movement_ordinal=" + session.lastMovementOrdinal()
                                + " ships_commanded=" + session.shipsCommandedLastTick()
                                + " commands_issued=" + session.commandsIssued()
                                + " commands_applied=" + session.commandsApplied()
                                + " commands_rejected=" + session.commandsRejected()
                                + " frames=" + session.framesServed()
                                + " source_ships=" + session.sourceShipCountFirstFrame()
                                + " served_ships=" + session.servedShipCountFirstFrame()
                                + " served_bytes=" + session.servedBytes()
                                + " served_sha256=" + session.servedSha256());
                    }
                    System.out.println("dedicated-combat-server-continuous-command-driven DONE"
                            + " host=" + continuousReceipt.host()
                            + " port=" + continuousReceipt.port()
                            + " sessions_served=" + continuousReceipt.sessionsServed()
                            + " shutdown_reason=" + continuousReceipt.shutdownReason());
                    System.exit(0);
                }
                CommandServeReceipt receipt = PersistentDedicatedCombatServer.serveCommandDrivenUntilShutdown(
                        config,
                        binding,
                        driver,
                        bindAddress,
                        (host, port) -> writePortFile(portFilePath, host, port),
                        () -> Files.exists(shutdownSignal));
                for (CommandSessionSummary session : receipt.sessions()) {
                    System.out.println("command-session"
                            + " index=" + session.sessionIndex()
                            + " peer_id=" + session.peerId()
                            + " peer_max_schema=" + session.peerMaxSchema()
                            + " served_schema=" + session.servedSchemaVersion()
                            + " intent_received=" + session.intentReceived()
                            + " intent_maneuver=" + session.intentManeuver()
                            + " intent_ship_id=" + session.intentShipId()
                            + " intent_movement_ordinal=" + session.intentMovementOrdinal()
                            + " intent_ship_registered=" + session.intentShipRegistered()
                            + " inbound_intent_bytes=" + session.inboundIntentBytes()
                            + " ships_commanded=" + session.shipsCommandedLastTick()
                            + " commands_issued=" + session.commandsIssued()
                            + " commands_applied=" + session.commandsApplied()
                            + " commands_rejected=" + session.commandsRejected()
                            + " frames=" + session.framesServed()
                            + " source_ships=" + session.sourceShipCountFirstFrame()
                            + " served_ships=" + session.servedShipCountFirstFrame()
                            + " served_bytes=" + session.servedBytes()
                            + " served_sha256=" + session.servedSha256());
                }
                System.out.println("dedicated-combat-server-command-driven DONE"
                        + " host=" + receipt.host()
                        + " port=" + receipt.port()
                        + " sessions_served=" + receipt.sessionsServed()
                        + " shutdown_reason=" + receipt.shutdownReason());
                System.exit(0);
            }

            // L1 MATCH_RESET (2b, Option A): -Dcc.server.matchReset=true makes the standing read-only
            // server re-spawn a fresh 1v1 roster after a roster-0 match-over (both ships dead) before
            // accepting the next client, so it serves a SECOND lethal round in the same JVM rather than
            // a continuation of the dead world. Only meaningful for the real-engine binding (the demo
            // roster was deployed); a fake/other binding that deployed no roster has nothing to reset,
            // so the hook stays null and behaviour is byte-identical. Default OFF.
            PersistentDedicatedCombatServer.MatchResetHook resetHook = buildMatchResetHookOrNull();
            ServeReceipt receipt = PersistentDedicatedCombatServer.serveUntilShutdown(
                    config,
                    binding,
                    bindAddress,
                    (host, port) -> writePortFile(portFilePath, host, port),
                    () -> Files.exists(shutdownSignal),
                    resetHook);

            for (SessionSummary session : receipt.sessions()) {
                System.out.println("session"
                        + " index=" + session.sessionIndex()
                        + " peer_id=" + session.peerId()
                        + " peer_max_schema=" + session.peerMaxSchema()
                        + " served_schema=" + session.servedSchemaVersion()
                        + " lane=" + session.reliabilityLane()
                        + " frames=" + session.framesServed()
                        + " source_ships=" + session.sourceShipCountFirstFrame()
                        + " served_ships=" + session.servedShipCountFirstFrame()
                        + " served_bytes=" + session.servedBytes()
                        + " served_sha256=" + session.servedSha256());
            }
            System.out.println("dedicated-combat-server DONE"
                    + " host=" + receipt.host()
                    + " port=" + receipt.port()
                    + " sessions_served=" + receipt.sessionsServed()
                    + " shutdown_reason=" + receipt.shutdownReason());
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static EngineCombatBinding buildBinding(String factoryClassName) throws Exception {
        Class<?> factory = Class.forName(factoryClassName);
        Method create = factory.getMethod("create");
        Object binding = create.invoke(null);
        if (!(binding instanceof EngineCombatBinding engineBinding)) {
            throw new IllegalArgumentException("binding factory " + factoryClassName
                    + ".create() did not return an EngineCombatBinding");
        }
        return engineBinding;
    }

    private static ManeuverComponents buildManeuverComponents(
            String factoryClassName, EngineCombatBinding binding) throws Exception {
        Class<?> factory = Class.forName(factoryClassName);
        Method create = factory.getMethod("create", EngineCombatBinding.class);
        Object components = create.invoke(null, binding);
        if (!(components instanceof ManeuverComponents maneuverComponents)) {
            throw new IllegalArgumentException("maneuver factory " + factoryClassName
                    + ".create(EngineCombatBinding) did not return a ManeuverComponents");
        }
        return maneuverComponents;
    }

    private static ShipPoseDriver buildPoseDriver(
            String factoryClassName, EngineCombatBinding binding) throws Exception {
        Class<?> factory = Class.forName(factoryClassName);
        Method create = factory.getMethod("create", EngineCombatBinding.class);
        Object driver = create.invoke(null, binding);
        if (!(driver instanceof ShipPoseDriver poseDriver)) {
            throw new IllegalArgumentException("pose-driver factory " + factoryClassName
                    + ".create(EngineCombatBinding) did not return a ShipPoseDriver");
        }
        return poseDriver;
    }

    private static ShipCommandDriver buildCommandDriver(
            String factoryClassName, EngineCombatBinding binding) throws Exception {
        Class<?> factory = Class.forName(factoryClassName);
        Method create = factory.getMethod("create", EngineCombatBinding.class);
        Object driver = create.invoke(null, binding);
        if (!(driver instanceof ShipCommandDriver commandDriver)) {
            throw new IllegalArgumentException("command-driver factory " + factoryClassName
                    + ".create(EngineCombatBinding) did not return a ShipCommandDriver");
        }
        return commandDriver;
    }

    /**
     * Build the L1 MATCH_RESET hook (Option A) iff {@code -Dcc.server.matchReset=true} AND a real-engine demo
     * roster was deployed ({@link RealEngineCombatBindingFactory}). The hook resets + re-deploys the bound
     * real-engine roster between rounds ({@link RealEngineCombatBindingFactory#resetForRedeploy()}). When the
     * flag is set on a fake/other binding that deployed no roster there is nothing to reset, so this returns
     * {@code null} (byte-identical behaviour) and logs the ignore. Shared by the read-only
     * {@code serveUntilShutdown} branch and the {@code --continuous} intent-up-lane multi-round branch
     * so the two wire the reset hook identically.
     */
    private static PersistentDedicatedCombatServer.MatchResetHook buildMatchResetHookOrNull() {
        if (!ServerConfig.resolveBoolean(
                "cc.server.matchReset", "CC_SERVER_MATCH_RESET", "server.matchReset", false)) {
            return null;
        }
        if (!RealEngineDemoRoster.deployed()) {
            System.out.println("dedicated-combat-server match-reset requested but no demo roster"
                    + " deployed (non-real binding) -- ignored");
            return null;
        }
        return () -> {
            try {
                RealEngineCombatBindingFactory.resetForRedeploy();
            } catch (RuntimeException reset) {
                throw new IOException("match reset failed: " + reset.getMessage(), reset);
            }
        };
    }

    private static void writePortFile(Path portFile, String host, int port) throws IOException {
        Path parent = portFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Write to a temp sibling then atomically move into place so a reader never
        // observes a half-written port file while the server is coming up.
        Path tmp = portFile.resolveSibling(portFile.getFileName() + ".tmp");
        Files.write(tmp, List.of("host=" + host, "port=" + port),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, portFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, portFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static InetAddress requireNonLoopbackIpv4Address() throws IOException {
        InetAddress fallback = null;
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            throw new IOException("failed to enumerate local network interfaces", e);
        }
        if (interfaces == null) {
            throw new IOException("no network interfaces are available for off-loopback transport");
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            try {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
            } catch (SocketException e) {
                continue;
            }
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address)
                        || address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()) {
                    continue;
                }
                if (address.isSiteLocalAddress()) {
                    return address;
                }
                if (fallback == null) {
                    fallback = address;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IOException("no non-loopback IPv4 address is available for off-loopback transport");
    }
}
