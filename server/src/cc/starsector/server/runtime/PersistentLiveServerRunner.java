package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.adapter.CombatStateHashBoundary;
import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.client.SnapshotClientAdmissionGate;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectReceipt;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectSocketPublication;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.FrameBoundRejectedProcessAdmissionReceipt;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.ProcessDirectReceipt;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded live server proof: one listening socket survives client reconnect.
 */
public final class PersistentLiveServerRunner {
    private static final int CONNECTION_COUNT = 2;
    private static final int CONCURRENT_RECONNECT_CONNECTION_COUNT = 3;
    private static final int CONCURRENT_CLIENT_COUNT = 2;
    private static final int BACKLOG = 2;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;
    private static final String CLIENT_CONTRACT =
            "cc.starsector.server.client.HeadlessSnapshotSocketClient";

    private PersistentLiveServerRunner() {
    }

    public static LiveServerReceipt runTwoConnectionLiveServer(
            List<Path> receiverArtifactPaths,
            List<Path> clientReceiptPaths,
            int expectedFramesPerConnection,
            List<DirectSocketPublication> publications,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(
                receiverArtifactPaths,
                "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        movementCommands = List.copyOf(Objects.requireNonNull(movementCommands, "movementCommands"));
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != CONNECTION_COUNT
                || clientReceiptPaths.size() != CONNECTION_COUNT
                || publications.size() != CONNECTION_COUNT
                || movementCommands.size() != CONNECTION_COUNT) {
            throw new IllegalArgumentException("persistent live server proof requires exactly two connections");
        }
        if (expectedFramesPerConnection < 0) {
            throw new IllegalArgumentException("expectedFramesPerConnection must be >= 0; got "
                    + expectedFramesPerConnection);
        }
        requireDistinctPaths(receiverArtifactPaths, "receiver artifact paths");
        requireDistinctPaths(clientReceiptPaths, "client receipt paths");
        for (int i = 0; i < CONNECTION_COUNT; i++) {
            createParentDirectory(receiverArtifactPaths.get(i));
            createParentDirectory(clientReceiptPaths.get(i));
            Objects.requireNonNull(publications.get(i), "publication");
            Objects.requireNonNull(movementCommands.get(i), "movementCommand");
        }

        long serverPid = ProcessHandle.current().pid();
        long expectedBytes = Math.multiplyExact(
                (long) expectedFramesPerConnection,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        String socketHost = socketAddress.getHostAddress();
        List<ConnectionResult> results = new ArrayList<>(CONNECTION_COUNT);
        long listeningStartedNanos;
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            listeningStartedNanos = System.nanoTime();
            port = serverSocket.getLocalPort();
            for (int i = 0; i < CONNECTION_COUNT; i++) {
                results.add(serveConnection(
                        serverSocket,
                        socketHost,
                        port,
                        receiverArtifactPaths.get(i),
                        clientReceiptPaths.get(i),
                        expectedFramesPerConnection,
                        expectedBytes,
                        publications.get(i),
                        movementCommands.get(i),
                        commandSink,
                        streamIdentity,
                        serverPid));
            }
        }

        return new LiveServerReceipt(
                serverPid,
                socketHost,
                port,
                listeningStartedNanos,
                results.get(0).disconnectedNanos(),
                results.get(1).acceptedNanos(),
                List.of(results.get(0).processReceipt(), results.get(1).processReceipt()),
                List.of(results.get(0).movementCommand(), results.get(1).movementCommand()),
                List.of(results.get(0).commandResult(), results.get(1).commandResult()));
    }

    public static PersistentConcurrentLiveServerReceipt runConcurrentThenReconnectLiveServer(
            List<Path> receiverArtifactPaths,
            List<Path> clientReceiptPaths,
            int expectedFramesPerConnection,
            DirectSocketPublication concurrentPublication,
            DirectSocketPublication reconnectPublication,
            List<ClientAuthorityIdentity> concurrentIdentities,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> concurrentMovementCommands,
            HeadlessSnapshotSocketClient.ClientMovementCommand reconnectMovementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(
                receiverArtifactPaths,
                "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        concurrentIdentities = List.copyOf(Objects.requireNonNull(
                concurrentIdentities,
                "concurrentIdentities"));
        concurrentMovementCommands = List.copyOf(Objects.requireNonNull(
                concurrentMovementCommands,
                "concurrentMovementCommands"));
        Objects.requireNonNull(concurrentPublication, "concurrentPublication");
        Objects.requireNonNull(reconnectPublication, "reconnectPublication");
        Objects.requireNonNull(reconnectMovementCommand, "reconnectMovementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != CONCURRENT_RECONNECT_CONNECTION_COUNT
                || clientReceiptPaths.size() != CONCURRENT_RECONNECT_CONNECTION_COUNT) {
            throw new IllegalArgumentException(
                    "persistent concurrent reconnect proof requires exactly three client paths");
        }
        if (concurrentIdentities.size() != CONCURRENT_CLIENT_COUNT
                || concurrentMovementCommands.size() != CONCURRENT_CLIENT_COUNT) {
            throw new IllegalArgumentException(
                    "persistent concurrent reconnect proof requires exactly two concurrent clients");
        }
        if (expectedFramesPerConnection < 0) {
            throw new IllegalArgumentException("expectedFramesPerConnection must be >= 0; got "
                    + expectedFramesPerConnection);
        }
        requireDistinctPaths(receiverArtifactPaths, "receiver artifact paths");
        requireDistinctPaths(clientReceiptPaths, "client receipt paths");
        for (int i = 0; i < CONCURRENT_RECONNECT_CONNECTION_COUNT; i++) {
            createParentDirectory(receiverArtifactPaths.get(i));
            createParentDirectory(clientReceiptPaths.get(i));
        }

        long serverPid = ProcessHandle.current().pid();
        String socketHost = socketAddress.getHostAddress();
        long listeningStartedNanos;
        int port;
        ConcurrentMultiplayerSessionRunner.SessionReceipt concurrentReceipt;
        long concurrentWindowClosedNanos;
        ConnectionResult reconnectResult;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            listeningStartedNanos = System.nanoTime();
            port = serverSocket.getLocalPort();
            concurrentReceipt = ConcurrentMultiplayerSessionRunner.runTwoClientAuthoritativeSessionOnBoundSocket(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPaths.subList(0, CONCURRENT_CLIENT_COUNT),
                    clientReceiptPaths.subList(0, CONCURRENT_CLIENT_COUNT),
                    expectedFramesPerConnection,
                    concurrentPublication,
                    concurrentIdentities,
                    concurrentMovementCommands,
                    commandSink,
                    streamIdentity);
            concurrentWindowClosedNanos = System.nanoTime();
            reconnectResult = serveConnection(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPaths.get(2),
                    clientReceiptPaths.get(2),
                    expectedFramesPerConnection,
                    Math.multiplyExact(
                            (long) expectedFramesPerConnection,
                            (long) OfflineCombatStepWireCodec.FRAME_BYTES),
                    reconnectPublication,
                    reconnectMovementCommand,
                    commandSink,
                    streamIdentity,
                    serverPid);
        }

        return new PersistentConcurrentLiveServerReceipt(
                serverPid,
                socketHost,
                port,
                listeningStartedNanos,
                concurrentWindowClosedNanos,
                reconnectResult.acceptedNanos(),
                concurrentReceipt,
                reconnectResult.processReceipt(),
                reconnectResult.movementCommand(),
                reconnectResult.commandResult());
    }

    public static IndependentTickLiveServerReceipt runIndependentTickLiveServer(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            CombatStepBoundary step,
            CombatStateHashBoundary stateHashBoundary,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (expectedFrames < 1) {
            throw new IllegalArgumentException("independent tick proof requires at least one frame");
        }
        createParentDirectory(receiverArtifactPath);
        createParentDirectory(clientReceiptPath);

        long serverPid = ProcessHandle.current().pid();
        String socketHost = socketAddress.getHostAddress();
        long expectedBytes = Math.multiplyExact(
                (long) expectedFrames,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        IndependentTickPublication publication =
                new IndependentTickPublication(expectedFrames, expectedFrames, step, stateHashBoundary);
        long listeningStartedNanos;
        int port;
        ConnectionResult result;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            listeningStartedNanos = System.nanoTime();
            port = serverSocket.getLocalPort();
            publication.start();
            publication.awaitFirstTick();
            result = serveConnection(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    expectedBytes,
                    publication,
                    movementCommand,
                    commandSink,
                    streamIdentity,
                    serverPid);
            publication.awaitCompletion();
        } finally {
            publication.stopIfRunning();
        }

        return new IndependentTickLiveServerReceipt(
                serverPid,
                socketHost,
                port,
                listeningStartedNanos,
                publication.tickThreadStartedNanos(),
                publication.firstTickPublishedNanos(),
                result.acceptedNanos(),
                publication.completedNanos(),
                publication.producedFrames(),
                result.processReceipt(),
                result.movementCommand(),
                result.commandResult());
    }

    public static IndependentTickAdmissionRejectLiveServerReceipt
            runIndependentTickAdmissionRejectThenAcceptLiveServer(
                    Path rejectedReceiverArtifactPath,
                    Path acceptedReceiverArtifactPath,
                    Path rejectedClientReceiptPath,
                    Path acceptedClientReceiptPath,
                    int expectedFrames,
                    int rejectedExpectedFrames,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(rejectedReceiverArtifactPath, "rejectedReceiverArtifactPath");
        Objects.requireNonNull(acceptedReceiverArtifactPath, "acceptedReceiverArtifactPath");
        Objects.requireNonNull(rejectedClientReceiptPath, "rejectedClientReceiptPath");
        Objects.requireNonNull(acceptedClientReceiptPath, "acceptedClientReceiptPath");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (expectedFrames < 1) {
            throw new IllegalArgumentException(
                    "independent tick admission resilience proof requires at least one frame");
        }
        if (rejectedExpectedFrames < 0) {
            throw new IllegalArgumentException(
                    "rejectedExpectedFrames must be >= 0; got " + rejectedExpectedFrames);
        }
        if (rejectedExpectedFrames == expectedFrames) {
            throw new IllegalArgumentException(
                    "rejectedExpectedFrames must differ from accepted expectedFrames");
        }
        requireDistinctPaths(
                List.of(rejectedReceiverArtifactPath, acceptedReceiverArtifactPath),
                "receiver artifact paths");
        requireDistinctPaths(
                List.of(rejectedClientReceiptPath, acceptedClientReceiptPath),
                "client receipt paths");
        createParentDirectory(rejectedReceiverArtifactPath);
        createParentDirectory(acceptedReceiverArtifactPath);
        createParentDirectory(rejectedClientReceiptPath);
        createParentDirectory(acceptedClientReceiptPath);

        long serverPid = ProcessHandle.current().pid();
        String socketHost = socketAddress.getHostAddress();
        long expectedBytes = Math.multiplyExact(
                (long) expectedFrames,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        IndependentTickPublication publication =
                new IndependentTickPublication(expectedFrames, expectedFrames, step, stateHashBoundary);
        long listeningStartedNanos;
        long rejectedAdmissionCompletedNanos;
        int port;
        FrameBoundRejectedProcessAdmissionReceipt rejectedReceipt;
        ConnectionResult acceptedResult;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            listeningStartedNanos = System.nanoTime();
            port = serverSocket.getLocalPort();
            publication.start();
            publication.awaitFirstTick();
            rejectedReceipt = serveFrameBoundRejectedAdmission(
                    serverSocket,
                    socketHost,
                    port,
                    rejectedReceiverArtifactPath,
                    rejectedClientReceiptPath,
                    expectedFrames,
                    rejectedExpectedFrames,
                    streamIdentity,
                    serverPid);
            rejectedAdmissionCompletedNanos = System.nanoTime();
            acceptedResult = serveConnection(
                    serverSocket,
                    socketHost,
                    port,
                    acceptedReceiverArtifactPath,
                    acceptedClientReceiptPath,
                    expectedFrames,
                    expectedBytes,
                    publication,
                    movementCommand,
                    commandSink,
                    streamIdentity,
                    serverPid);
            publication.awaitCompletion();
        } finally {
            publication.stopIfRunning();
        }

        return new IndependentTickAdmissionRejectLiveServerReceipt(
                serverPid,
                socketHost,
                port,
                listeningStartedNanos,
                publication.tickThreadStartedNanos(),
                publication.firstTickPublishedNanos(),
                rejectedAdmissionCompletedNanos,
                acceptedResult.acceptedNanos(),
                publication.completedNanos(),
                publication.producedFrames(),
                rejectedReceipt,
                acceptedResult.processReceipt(),
                acceptedResult.movementCommand(),
                acceptedResult.commandResult());
    }

    public static IndependentTickObserverLiveServerReceipt runIndependentTickObserverLiveServer(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            CombatStepBoundary step,
            CombatStateHashBoundary stateHashBoundary,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (expectedFrames < 1) {
            throw new IllegalArgumentException(
                    "independent tick observer proof requires at least one frame");
        }
        createParentDirectory(receiverArtifactPath);
        createParentDirectory(clientReceiptPath);

        long serverPid = ProcessHandle.current().pid();
        String socketHost = socketAddress.getHostAddress();
        long expectedBytes = Math.multiplyExact(
                (long) expectedFrames,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        IndependentTickPublication publication =
                new IndependentTickPublication(expectedFrames, expectedFrames, step, stateHashBoundary);
        long listeningStartedNanos;
        int port;
        ObserverConnectionResult result;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            listeningStartedNanos = System.nanoTime();
            port = serverSocket.getLocalPort();
            publication.start();
            publication.awaitFirstTick();
            result = serveObserverConnection(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    expectedBytes,
                    publication,
                    streamIdentity,
                    serverPid);
            publication.awaitCompletion();
        } finally {
            publication.stopIfRunning();
        }

        return new IndependentTickObserverLiveServerReceipt(
                serverPid,
                socketHost,
                port,
                listeningStartedNanos,
                publication.tickThreadStartedNanos(),
                publication.firstTickPublishedNanos(),
                result.acceptedNanos(),
                publication.completedNanos(),
                publication.producedFrames(),
                result.processReceipt());
    }

    public static IndependentTickReconnectLiveServerReceipt runIndependentTickReconnectLiveServer(
            List<Path> receiverArtifactPaths,
            List<Path> clientReceiptPaths,
            int expectedFramesPerConnection,
            CombatStepBoundary step,
            CombatStateHashBoundary stateHashBoundary,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(
                receiverArtifactPaths,
                "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        movementCommands = List.copyOf(Objects.requireNonNull(movementCommands, "movementCommands"));
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != CONNECTION_COUNT
                || clientReceiptPaths.size() != CONNECTION_COUNT
                || movementCommands.size() != CONNECTION_COUNT) {
            throw new IllegalArgumentException(
                    "independent tick reconnect proof requires exactly two connections");
        }
        if (expectedFramesPerConnection < 1) {
            throw new IllegalArgumentException(
                    "independent tick reconnect proof requires at least one frame per connection");
        }
        requireDistinctPaths(receiverArtifactPaths, "receiver artifact paths");
        requireDistinctPaths(clientReceiptPaths, "client receipt paths");
        for (int i = 0; i < CONNECTION_COUNT; i++) {
            createParentDirectory(receiverArtifactPaths.get(i));
            createParentDirectory(clientReceiptPaths.get(i));
            Objects.requireNonNull(movementCommands.get(i), "movementCommand");
        }

        long serverPid = ProcessHandle.current().pid();
        String socketHost = socketAddress.getHostAddress();
        long expectedBytes = Math.multiplyExact(
                (long) expectedFramesPerConnection,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        IndependentTickPublication publication =
                new IndependentTickPublication(
                        expectedFramesPerConnection,
                        Math.multiplyExact(expectedFramesPerConnection, CONNECTION_COUNT),
                        step,
                        stateHashBoundary,
                        expectedFramesPerConnection);
        long listeningStartedNanos;
        int port;
        ConnectionResult firstResult;
        ConnectionResult secondResult;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            listeningStartedNanos = System.nanoTime();
            port = serverSocket.getLocalPort();
            publication.start();
            publication.awaitFirstTick();
            firstResult = serveConnection(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPaths.get(0),
                    clientReceiptPaths.get(0),
                    expectedFramesPerConnection,
                    expectedBytes,
                    publication,
                    movementCommands.get(0),
                    commandSink,
                    streamIdentity,
                    serverPid);
            publication.releaseReconnectWindow();
            publication.awaitFirstReconnectTick();
            secondResult = serveConnection(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPaths.get(1),
                    clientReceiptPaths.get(1),
                    expectedFramesPerConnection,
                    expectedBytes,
                    publication,
                    movementCommands.get(1),
                    commandSink,
                    streamIdentity,
                    serverPid);
            publication.awaitCompletion();
        } finally {
            publication.stopIfRunning();
        }

        return new IndependentTickReconnectLiveServerReceipt(
                serverPid,
                socketHost,
                port,
                listeningStartedNanos,
                publication.tickThreadStartedNanos(),
                publication.firstTickPublishedNanos(),
                firstResult.acceptedNanos(),
                firstResult.disconnectedNanos(),
                publication.reconnectWindowReleasedNanos(),
                publication.firstReconnectTickPublishedNanos(),
                secondResult.acceptedNanos(),
                publication.completedNanos(),
                publication.producedFrames(),
                List.of(firstResult.processReceipt(), secondResult.processReceipt()),
                List.of(firstResult.movementCommand(), secondResult.movementCommand()),
                List.of(firstResult.commandResult(), secondResult.commandResult()));
    }

    public static IndependentTickConcurrentLiveServerReceipt runIndependentTickConcurrentLiveServer(
            List<Path> receiverArtifactPaths,
            List<Path> clientReceiptPaths,
            int expectedFrames,
            CombatStepBoundary step,
            CombatStateHashBoundary stateHashBoundary,
            List<ClientAuthorityIdentity> identities,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (expectedFrames < 1) {
            throw new IllegalArgumentException(
                    "independent tick concurrent proof requires at least one frame");
        }

        long serverPid = ProcessHandle.current().pid();
        String socketHost = socketAddress.getHostAddress();
        IndependentTickPublication publication =
                new IndependentTickPublication(
                        expectedFrames,
                        expectedFrames,
                        step,
                        stateHashBoundary,
                        -1,
                        1);
        long listeningStartedNanos;
        long concurrentSessionStartedNanos;
        long concurrentSessionCompletedNanos;
        int port;
        ConcurrentMultiplayerSessionRunner.SessionReceipt sessionReceipt;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            listeningStartedNanos = System.nanoTime();
            port = serverSocket.getLocalPort();
            publication.start();
            publication.awaitFirstTick();
            concurrentSessionStartedNanos = System.nanoTime();
            sessionReceipt = ConcurrentMultiplayerSessionRunner.runTwoClientAuthoritativeSessionOnBoundSocket(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPaths,
                    clientReceiptPaths,
                    expectedFrames,
                    publication,
                    identities,
                    commands,
                    commandSink,
                    streamIdentity);
            concurrentSessionCompletedNanos = System.nanoTime();
            publication.awaitCompletion();
        } finally {
            publication.stopIfRunning();
        }

        return new IndependentTickConcurrentLiveServerReceipt(
                serverPid,
                socketHost,
                port,
                listeningStartedNanos,
                publication.tickThreadStartedNanos(),
                publication.firstTickPublishedNanos(),
                concurrentSessionStartedNanos,
                concurrentSessionCompletedNanos,
                publication.completedNanos(),
                publication.producedFrames(),
                sessionReceipt);
    }

    public static IndependentTickConflictReconnectLiveServerReceipt
            runIndependentTickConflictThenReconnectLiveServer(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    List<ClientAuthorityIdentity> concurrentIdentities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> concurrentCommands,
                    HeadlessSnapshotSocketClient.ClientMovementCommand reconnectMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(
                receiverArtifactPaths,
                "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        Objects.requireNonNull(reconnectMovementCommand, "reconnectMovementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != CONCURRENT_RECONNECT_CONNECTION_COUNT
                || clientReceiptPaths.size() != CONCURRENT_RECONNECT_CONNECTION_COUNT) {
            throw new IllegalArgumentException(
                    "independent tick conflict reconnect proof requires exactly three client paths");
        }
        if (expectedFramesPerConnection < 1) {
            throw new IllegalArgumentException(
                    "independent tick conflict reconnect proof requires at least one frame per connection");
        }
        requireDistinctPaths(receiverArtifactPaths, "receiver artifact paths");
        requireDistinctPaths(clientReceiptPaths, "client receipt paths");
        for (int i = 0; i < CONCURRENT_RECONNECT_CONNECTION_COUNT; i++) {
            createParentDirectory(receiverArtifactPaths.get(i));
            createParentDirectory(clientReceiptPaths.get(i));
        }

        long serverPid = ProcessHandle.current().pid();
        String socketHost = socketAddress.getHostAddress();
        long expectedBytes = Math.multiplyExact(
                (long) expectedFramesPerConnection,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        IndependentTickPublication publication =
                new IndependentTickPublication(
                        expectedFramesPerConnection,
                        Math.multiplyExact(expectedFramesPerConnection, CONNECTION_COUNT),
                        step,
                        stateHashBoundary,
                        expectedFramesPerConnection,
                        1);
        long listeningStartedNanos;
        long concurrentSessionStartedNanos;
        long concurrentWindowClosedNanos;
        int port;
        ConcurrentMultiplayerSessionRunner.SessionReceipt concurrentReceipt;
        ConnectionResult reconnectResult;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            listeningStartedNanos = System.nanoTime();
            port = serverSocket.getLocalPort();
            publication.start();
            publication.awaitFirstTick();
            concurrentSessionStartedNanos = System.nanoTime();
            concurrentReceipt = ConcurrentMultiplayerSessionRunner.runTwoClientAuthoritativeSessionOnBoundSocket(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPaths.subList(0, CONCURRENT_CLIENT_COUNT),
                    clientReceiptPaths.subList(0, CONCURRENT_CLIENT_COUNT),
                    expectedFramesPerConnection,
                    publication,
                    concurrentIdentities,
                    concurrentCommands,
                    commandSink,
                    streamIdentity);
            concurrentWindowClosedNanos = System.nanoTime();
            publication.releaseReconnectWindow();
            publication.awaitFirstReconnectTick();
            reconnectResult = serveConnection(
                    serverSocket,
                    socketHost,
                    port,
                    receiverArtifactPaths.get(2),
                    clientReceiptPaths.get(2),
                    expectedFramesPerConnection,
                    expectedBytes,
                    publication,
                    reconnectMovementCommand,
                    commandSink,
                    streamIdentity,
                    serverPid);
            publication.awaitCompletion();
        } finally {
            publication.stopIfRunning();
        }

        return new IndependentTickConflictReconnectLiveServerReceipt(
                serverPid,
                socketHost,
                port,
                listeningStartedNanos,
                publication.tickThreadStartedNanos(),
                publication.firstTickPublishedNanos(),
                concurrentSessionStartedNanos,
                concurrentWindowClosedNanos,
                publication.reconnectWindowReleasedNanos(),
                publication.firstReconnectTickPublishedNanos(),
                reconnectResult.acceptedNanos(),
                publication.completedNanos(),
                publication.producedFrames(),
                concurrentReceipt,
                reconnectResult.processReceipt(),
                reconnectResult.movementCommand(),
                reconnectResult.commandResult());
    }

    private static ConnectionResult serveConnection(
            ServerSocket serverSocket,
            String socketHost,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            long expectedBytes,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            long serverPid) throws IOException {
        Path clientLogPath = siblingWithSuffix(clientReceiptPath, ".log");
        Files.deleteIfExists(clientReceiptPath);
        Files.deleteIfExists(clientLogPath);

        IOException senderFailure = null;
        RuntimeException publisherFailure = null;
        long senderBytes = 0L;
        long receiverBytes;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        List<OfflineCombatStepWireFrame> receiverFrames;
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        HeadlessSnapshotSocketClient.ClientMovementCommand receivedMovementCommand = null;
        ShipCommandSink.Result movementCommandResult = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        HeadlessSnapshotSocketClient.ServerMovementCommandResult clientMovementCommandResult = null;
        HeadlessSnapshotSocketClient.ServerInitialSync clientInitialSync = null;
        HeadlessSnapshotSocketClient.ServerStreamCompletion clientStreamCompletion = null;
        long clientPid = -1L;
        int clientExitCode = -1;
        long acceptedNanos = -1L;
        long disconnectedNanos = -1L;
        Process clientProcess = null;
        try {
            clientProcess = HeadlessSnapshotClientProcessLauncher.startHeadlessClientProcess(
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    streamIdentity,
                    clientLogPath,
                    movementCommand);
            clientPid = clientProcess.pid();
            try (Socket socket = serverSocket.accept()) {
                acceptedNanos = System.nanoTime();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                InputStream socketInput = socket.getInputStream();
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socketInput);
                receivedMovementCommand = HeadlessSnapshotSocketClient.readMovementCommand(socketInput);
                movementCommandResult = commandSink.submitMovement(
                        receivedMovementCommand.shipId(),
                        ShipCommandSink.Source.PLAYER,
                        receivedMovementCommand.simTick(),
                        receivedMovementCommand.movementIntent());
                if (movementCommandResult == null) {
                    throw new IOException("ship movement command sink returned null");
                }

                OutputStream socketOutput = socket.getOutputStream();
                clientAdmission =
                        new SnapshotClientAdmissionGate(expectedFrames, streamIdentity).admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                if (!clientAdmission.accepted()) {
                    throw new IOException("persistent live server client admission rejected: "
                            + clientAdmission.reason());
                }
                HeadlessSnapshotSocketClient.writeTransportSessionOpened(
                        socketOutput,
                        HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                                clientHandshake,
                                socketHost,
                                port));
                clientMovementCommandResult =
                        HeadlessSnapshotSocketClient.movementCommandResult(
                                receivedMovementCommand,
                                movementCommandResult);
                HeadlessSnapshotSocketClient.writeMovementCommandResult(
                        socketOutput,
                        clientMovementCommandResult);

                DirectSocketSink sink = new DirectSocketSink(socketOutput, clientHandshake, expectedFrames);
                try {
                    publication.publishTo(sink);
                    sink.finish();
                    HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(
                            clientHandshake,
                            expectedFrames,
                            sink.frames());
                    clientStreamCompletion =
                            HeadlessSnapshotSocketClient.ServerStreamCompletion.completed(clientHandshake);
                    HeadlessSnapshotSocketClient.writeStreamCompletion(socketOutput, clientStreamCompletion);
                } catch (UncheckedIOException uioe) {
                    senderFailure = uioe.getCause();
                } catch (IOException ioe) {
                    senderFailure = ioe;
                } catch (RuntimeException rte) {
                    publisherFailure = rte;
                } finally {
                    senderBytes = sink.bytesWritten();
                    senderFrames = sink.frames();
                    clientInitialSync = sink.initialSync();
                    try {
                        socket.shutdownOutput();
                    } catch (IOException shutdownFailure) {
                        if (senderFailure != null) {
                            senderFailure.addSuppressed(shutdownFailure);
                        } else if (publisherFailure != null) {
                            publisherFailure.addSuppressed(shutdownFailure);
                        } else {
                            senderFailure = shutdownFailure;
                        }
                    }
                }
            } catch (IOException ioe) {
                senderFailure = ioe;
            } finally {
                disconnectedNanos = System.nanoTime();
            }

            clientExitCode = HeadlessSnapshotClientProcessLauncher.waitForClientProcess(
                    clientProcess,
                    clientLogPath);
        } finally {
            if (clientProcess != null && clientProcess.isAlive()) {
                clientProcess.destroyForcibly();
            }
        }

        if (senderFailure != null) {
            throw senderFailure;
        }
        if (publisherFailure != null) {
            throw publisherFailure;
        }
        if (clientExitCode != 0) {
            throw new IOException("persistent live server client exited " + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null || receivedMovementCommand == null) {
            throw new IOException("persistent live server client did not send handshake and movement command");
        }
        if (!receivedMovementCommand.equals(movementCommand)) {
            throw new IOException("persistent live server movement command mismatch");
        }
        if (senderBytes != expectedBytes) {
            throw new IOException("persistent live server sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        receiverBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("persistent live server receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        receiverFrames = OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
        if (!senderFrames.equals(receiverFrames)) {
            throw new IOException("persistent live server receiver frames do not match sender frames");
        }

        Properties clientReceipt = HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(clientReceiptPath);
        HeadlessSnapshotProcessReceiptValidator.validateProcessClientReceipt(
                clientReceipt,
                socketHost,
                port,
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                receiverFrames,
                clientHandshake,
                serverPid,
                clientPid);
        HeadlessSnapshotProcessReceiptValidator.requireProcessClientMovementReceipt(
                clientReceipt,
                movementCommand,
                movementCommandResult);

        DirectReceipt directReceipt = new DirectReceipt(
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                senderBytes,
                receiverBytes,
                socketHost,
                port,
                "socket_input_stream",
                false,
                CLIENT_CONTRACT,
                clientHandshake,
                clientAdmission,
                HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES,
                clientAdmissionAck,
                HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_BYTES,
                clientMovementCommandResult,
                0,
                null,
                0,
                null,
                0,
                null,
                HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES,
                clientInitialSync,
                HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES,
                clientStreamCompletion,
                senderFrames,
                receiverFrames);
        ProcessDirectReceipt processReceipt = new ProcessDirectReceipt(
                directReceipt,
                clientReceiptPath,
                clientLogPath,
                serverPid,
                clientPid,
                clientExitCode,
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "frames_read"),
                HeadlessSnapshotProcessReceiptValidator.requireLongProperty(
                        clientReceipt,
                        "latest_completed_tick"),
                HeadlessSnapshotProcessReceiptValidator.requireLongProperty(
                        clientReceipt,
                        "latest_state_hash"),
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(
                        clientReceipt,
                        "latest_entity_count"),
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(
                        clientReceipt,
                        "latest_ship_count"));
        return new ConnectionResult(
                processReceipt,
                receivedMovementCommand,
                movementCommandResult,
                acceptedNanos,
                disconnectedNanos);
    }

    private static ObserverConnectionResult serveObserverConnection(
            ServerSocket serverSocket,
            String socketHost,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            long expectedBytes,
            DirectSocketPublication publication,
            SnapshotStreamIdentity streamIdentity,
            long serverPid) throws IOException {
        Path clientLogPath = siblingWithSuffix(clientReceiptPath, ".log");
        Files.deleteIfExists(clientReceiptPath);
        Files.deleteIfExists(clientLogPath);

        IOException senderFailure = null;
        RuntimeException publisherFailure = null;
        long senderBytes = 0L;
        long receiverBytes;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        List<OfflineCombatStepWireFrame> receiverFrames;
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        HeadlessSnapshotSocketClient.ServerInitialSync clientInitialSync = null;
        HeadlessSnapshotSocketClient.ServerStreamCompletion clientStreamCompletion = null;
        long clientPid = -1L;
        int clientExitCode = -1;
        long acceptedNanos = -1L;
        long disconnectedNanos = -1L;
        Process clientProcess = null;
        try {
            clientProcess = HeadlessSnapshotClientProcessLauncher.startHeadlessClientProcess(
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    streamIdentity,
                    clientLogPath);
            clientPid = clientProcess.pid();
            try (Socket socket = serverSocket.accept()) {
                acceptedNanos = System.nanoTime();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                InputStream socketInput = socket.getInputStream();
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socketInput);

                OutputStream socketOutput = socket.getOutputStream();
                clientAdmission =
                        new SnapshotClientAdmissionGate(expectedFrames, streamIdentity).admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                if (!clientAdmission.accepted()) {
                    throw new IOException("independent tick observer admission rejected: "
                            + clientAdmission.reason());
                }
                HeadlessSnapshotSocketClient.writeTransportSessionOpened(
                        socketOutput,
                        HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                                clientHandshake,
                                socketHost,
                                port));

                DirectSocketSink sink = new DirectSocketSink(socketOutput, clientHandshake, expectedFrames);
                try {
                    publication.publishTo(sink);
                    sink.finish();
                    HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(
                            clientHandshake,
                            expectedFrames,
                            sink.frames());
                    clientStreamCompletion =
                            HeadlessSnapshotSocketClient.ServerStreamCompletion.completed(clientHandshake);
                    HeadlessSnapshotSocketClient.writeStreamCompletion(socketOutput, clientStreamCompletion);
                } catch (UncheckedIOException uioe) {
                    senderFailure = uioe.getCause();
                } catch (IOException ioe) {
                    senderFailure = ioe;
                } catch (RuntimeException rte) {
                    publisherFailure = rte;
                } finally {
                    senderBytes = sink.bytesWritten();
                    senderFrames = sink.frames();
                    clientInitialSync = sink.initialSync();
                    try {
                        socket.shutdownOutput();
                    } catch (IOException shutdownFailure) {
                        if (senderFailure != null) {
                            senderFailure.addSuppressed(shutdownFailure);
                        } else if (publisherFailure != null) {
                            publisherFailure.addSuppressed(shutdownFailure);
                        } else {
                            senderFailure = shutdownFailure;
                        }
                    }
                }
            } catch (IOException ioe) {
                senderFailure = ioe;
            } finally {
                disconnectedNanos = System.nanoTime();
            }

            clientExitCode = HeadlessSnapshotClientProcessLauncher.waitForClientProcess(
                    clientProcess,
                    clientLogPath);
        } finally {
            if (clientProcess != null && clientProcess.isAlive()) {
                clientProcess.destroyForcibly();
            }
        }

        if (senderFailure != null) {
            throw senderFailure;
        }
        if (publisherFailure != null) {
            throw publisherFailure;
        }
        if (clientExitCode != 0) {
            throw new IOException("independent tick observer client exited " + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null) {
            throw new IOException("independent tick observer client did not send handshake");
        }
        if (senderBytes != expectedBytes) {
            throw new IOException("independent tick observer sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        receiverBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("independent tick observer receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        receiverFrames = OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
        if (!senderFrames.equals(receiverFrames)) {
            throw new IOException("independent tick observer receiver frames do not match sender frames");
        }

        Properties clientReceipt = HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(clientReceiptPath);
        HeadlessSnapshotProcessReceiptValidator.validateProcessClientReceipt(
                clientReceipt,
                socketHost,
                port,
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                receiverFrames,
                clientHandshake,
                serverPid,
                clientPid);

        DirectReceipt directReceipt = new DirectReceipt(
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                senderBytes,
                receiverBytes,
                socketHost,
                port,
                "socket_input_stream",
                false,
                CLIENT_CONTRACT,
                clientHandshake,
                clientAdmission,
                HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES,
                clientAdmissionAck,
                0,
                null,
                0,
                null,
                0,
                null,
                0,
                null,
                HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES,
                clientInitialSync,
                HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES,
                clientStreamCompletion,
                senderFrames,
                receiverFrames);
        ProcessDirectReceipt processReceipt = new ProcessDirectReceipt(
                directReceipt,
                clientReceiptPath,
                clientLogPath,
                serverPid,
                clientPid,
                clientExitCode,
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "frames_read"),
                HeadlessSnapshotProcessReceiptValidator.requireLongProperty(
                        clientReceipt,
                        "latest_completed_tick"),
                HeadlessSnapshotProcessReceiptValidator.requireLongProperty(
                        clientReceipt,
                        "latest_state_hash"),
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(
                        clientReceipt,
                        "latest_entity_count"),
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(
                        clientReceipt,
                        "latest_ship_count"));
        return new ObserverConnectionResult(processReceipt, acceptedNanos, disconnectedNanos);
    }

    private static FrameBoundRejectedProcessAdmissionReceipt serveFrameBoundRejectedAdmission(
            ServerSocket serverSocket,
            String socketHost,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int requiredFrames,
            int clientExpectedFrames,
            SnapshotStreamIdentity streamIdentity,
            long serverPid) throws IOException {
        Path clientLogPath = siblingWithSuffix(clientReceiptPath, ".log");
        Files.deleteIfExists(receiverArtifactPath);
        Files.deleteIfExists(clientReceiptPath);
        Files.deleteIfExists(clientLogPath);

        long clientPid = -1L;
        int clientExitCode = -1;
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        Process clientProcess = null;
        try {
            clientProcess = HeadlessSnapshotClientProcessLauncher.startAdmissionHeadlessClientProcess(
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    clientExpectedFrames,
                    streamIdentity,
                    clientLogPath);
            clientPid = clientProcess.pid();
            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socket.getInputStream());
                clientAdmission =
                        new SnapshotClientAdmissionGate(requiredFrames, streamIdentity)
                                .admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(
                        clientAdmission);
                OutputStream socketOutput = socket.getOutputStream();
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                socket.shutdownOutput();
                if (clientAdmission.accepted()) {
                    throw new IOException("live server frame-bound mismatch client was admitted");
                }
            }
            clientExitCode = HeadlessSnapshotClientProcessLauncher.waitForClientProcess(
                    clientProcess,
                    clientLogPath);
        } finally {
            if (clientProcess != null && clientProcess.isAlive()) {
                clientProcess.destroyForcibly();
            }
        }
        if (clientExitCode != 0) {
            throw new IOException("live server frame-bound mismatch admission client exited "
                    + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null || clientAdmission == null || clientAdmissionAck == null) {
            throw new IOException("live server frame-bound mismatch admission did not complete");
        }
        long receiverArtifactBytes = Files.exists(receiverArtifactPath)
                ? Files.size(receiverArtifactPath)
                : 0L;
        Properties clientReceipt = HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(
                clientReceiptPath);
        return new FrameBoundRejectedProcessAdmissionReceipt(
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                requiredFrames,
                clientExpectedFrames,
                receiverArtifactBytes,
                socketHost,
                port,
                serverPid,
                clientPid,
                clientExitCode,
                streamIdentity,
                clientHandshake,
                clientAdmission,
                clientAdmissionAckBytes,
                clientAdmissionAck,
                clientReceipt);
    }

    private static void createParentDirectory(Path path) throws IOException {
        Path parent = Objects.requireNonNull(path, "path").getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static Path siblingWithSuffix(Path path, String suffix) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("path must have a file name: " + path);
        }
        return path.resolveSibling(fileName.toString() + suffix);
    }

    private static void requireDistinctPaths(List<Path> paths, String label) {
        for (int i = 0; i < paths.size(); i++) {
            Path left = Objects.requireNonNull(paths.get(i), "path")
                    .toAbsolutePath()
                    .normalize();
            for (int j = i + 1; j < paths.size(); j++) {
                Path right = Objects.requireNonNull(paths.get(j), "path")
                        .toAbsolutePath()
                        .normalize();
                if (left.equals(right)) {
                    throw new IllegalArgumentException(label + " must be distinct");
                }
            }
        }
    }

    private record ConnectionResult(
            ProcessDirectReceipt processReceipt,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult,
            long acceptedNanos,
            long disconnectedNanos) {

        private ConnectionResult {
            processReceipt = Objects.requireNonNull(processReceipt, "processReceipt");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
            if (acceptedNanos <= 0L) {
                throw new IllegalArgumentException("acceptedNanos must be recorded");
            }
            if (disconnectedNanos < acceptedNanos) {
                throw new IllegalArgumentException("disconnect must not precede accept");
            }
        }
    }

    private record ObserverConnectionResult(
            ProcessDirectReceipt processReceipt,
            long acceptedNanos,
            long disconnectedNanos) {

        private ObserverConnectionResult {
            processReceipt = Objects.requireNonNull(processReceipt, "processReceipt");
            if (acceptedNanos <= 0L) {
                throw new IllegalArgumentException("acceptedNanos must be recorded");
            }
            if (disconnectedNanos < acceptedNanos) {
                throw new IllegalArgumentException("disconnect must not precede accept");
            }
        }
    }

    private static final class IndependentTickPublication implements DirectSocketPublication {
        private final int framesPerPublication;
        private final int totalFrames;
        private final CombatStepBoundary step;
        private final CombatStateHashBoundary stateHashBoundary;
        private final BlockingQueue<OfflineCombatStepResult> queue;
        private final int reconnectPauseAfterFrames;
        private final CountDownLatch firstTick = new CountDownLatch(1);
        private final CountDownLatch reconnectWindowRelease = new CountDownLatch(1);
        private final CountDownLatch firstReconnectTick = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile Thread thread;
        private volatile long tickThreadStartedNanos = -1L;
        private volatile long firstTickPublishedNanos = -1L;
        private volatile long reconnectWindowReleasedNanos = -1L;
        private volatile long firstReconnectTickPublishedNanos = -1L;
        private volatile long completedNanos = -1L;
        private volatile int producedFrames;

        private IndependentTickPublication(
                int framesPerPublication,
                int totalFrames,
                CombatStepBoundary step,
                CombatStateHashBoundary stateHashBoundary) {
            this(framesPerPublication, totalFrames, step, stateHashBoundary, -1);
        }

        private IndependentTickPublication(
                int framesPerPublication,
                int totalFrames,
                CombatStepBoundary step,
                CombatStateHashBoundary stateHashBoundary,
                int reconnectPauseAfterFrames) {
            this(
                    framesPerPublication,
                    totalFrames,
                    step,
                    stateHashBoundary,
                    reconnectPauseAfterFrames,
                    framesPerPublication);
        }

        private IndependentTickPublication(
                int framesPerPublication,
                int totalFrames,
                CombatStepBoundary step,
                CombatStateHashBoundary stateHashBoundary,
                int reconnectPauseAfterFrames,
                int queueCapacity) {
            if (framesPerPublication < 1) {
                throw new IllegalArgumentException("framesPerPublication must be > 0; got "
                        + framesPerPublication);
            }
            if (totalFrames < framesPerPublication) {
                throw new IllegalArgumentException("totalFrames must be >= framesPerPublication; got "
                        + totalFrames + " < " + framesPerPublication);
            }
            if (reconnectPauseAfterFrames != -1
                    && (reconnectPauseAfterFrames < 1 || reconnectPauseAfterFrames >= totalFrames)) {
                throw new IllegalArgumentException("reconnect pause must be inside the produced stream; got "
                        + reconnectPauseAfterFrames + " for totalFrames=" + totalFrames);
            }
            if (queueCapacity < 1 || queueCapacity > framesPerPublication) {
                throw new IllegalArgumentException("queueCapacity must be in [1, framesPerPublication]; got "
                        + queueCapacity + " for framesPerPublication=" + framesPerPublication);
            }
            this.framesPerPublication = framesPerPublication;
            this.totalFrames = totalFrames;
            this.step = Objects.requireNonNull(step, "step");
            this.stateHashBoundary = Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
            this.reconnectPauseAfterFrames = reconnectPauseAfterFrames;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
        }

        private void start() {
            if (thread != null) {
                throw new IllegalStateException("independent tick publication already started");
            }
            Thread tickThread = new Thread(this::produce, "cc-starsector-independent-tick-proof");
            tickThread.setDaemon(true);
            thread = tickThread;
            tickThread.start();
        }

        private void produce() {
            tickThreadStartedNanos = System.nanoTime();
            try {
                FixedStepServerLoop loop = new FixedStepServerLoop(0.5f);
                for (int i = 0; i < totalFrames; i++) {
                    if (i == reconnectPauseAfterFrames) {
                        await(reconnectWindowRelease, "reconnect tick window release");
                    }
                    OfflineCombatStepResult result = loop.runOfflineStep(step, stateHashBoundary);
                    queue.put(result);
                    producedFrames = i + 1;
                    if (i == 0) {
                        firstTickPublishedNanos = System.nanoTime();
                        firstTick.countDown();
                    } else if (i == reconnectPauseAfterFrames) {
                        firstReconnectTickPublishedNanos = System.nanoTime();
                        firstReconnectTick.countDown();
                    }
                    Thread.sleep(1L);
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
                firstTick.countDown();
                firstReconnectTick.countDown();
            } finally {
                completedNanos = System.nanoTime();
                completed.countDown();
            }
        }

        private void awaitFirstTick() throws IOException {
            await(firstTick, "first independent tick");
            throwIfFailed();
            if (firstTickPublishedNanos <= 0L) {
                throw new IOException("independent tick loop did not publish its first tick");
            }
        }

        private void awaitCompletion() throws IOException {
            await(completed, "independent tick completion");
            throwIfFailed();
            if (producedFrames != totalFrames) {
                throw new IOException("independent tick loop produced " + producedFrames
                        + " frames, expected " + totalFrames);
            }
        }

        private void releaseReconnectWindow() {
            if (reconnectPauseAfterFrames == -1) {
                throw new IllegalStateException("independent tick publication has no reconnect window");
            }
            reconnectWindowReleasedNanos = System.nanoTime();
            reconnectWindowRelease.countDown();
        }

        private void awaitFirstReconnectTick() throws IOException {
            if (reconnectPauseAfterFrames == -1) {
                throw new IllegalStateException("independent tick publication has no reconnect window");
            }
            await(firstReconnectTick, "first reconnect independent tick");
            throwIfFailed();
            if (firstReconnectTickPublishedNanos <= 0L) {
                throw new IOException("independent tick loop did not publish its first reconnect tick");
            }
        }

        private void stopIfRunning() {
            Thread tickThread = thread;
            if (tickThread != null && tickThread.isAlive()) {
                tickThread.interrupt();
            }
        }

        @Override
        public void publishTo(OfflineCombatStepSink sink) throws IOException {
            Objects.requireNonNull(sink, "sink");
            for (int i = 0; i < framesPerPublication; i++) {
                OfflineCombatStepResult result;
                try {
                    result = queue.poll(SOCKET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interruptedIOException("interrupted while waiting for independent tick frame");
                }
                if (result == null) {
                    throwIfFailed();
                    throw new IOException("timed out waiting for independent tick frame " + i);
                }
                sink.publish(result);
            }
            throwIfFailed();
        }

        private void await(CountDownLatch latch, String label) throws IOException {
            boolean finished;
            try {
                finished = latch.await(SOCKET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interruptedIOException("interrupted while waiting for " + label);
            }
            if (!finished) {
                throw new IOException("timed out waiting for " + label);
            }
        }

        private void throwIfFailed() throws IOException {
            Throwable throwable = failure.get();
            if (throwable == null) {
                return;
            }
            if (throwable instanceof IOException ioe) {
                throw ioe;
            }
            if (throwable instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                InterruptedIOException interruptedIOException =
                        interruptedIOException("independent tick loop was interrupted");
                interruptedIOException.addSuppressed(interrupted);
                throw interruptedIOException;
            }
            throw new IOException("independent tick loop failed", throwable);
        }

        private static InterruptedIOException interruptedIOException(String message) {
            InterruptedIOException interruptedIOException = new InterruptedIOException(message);
            interruptedIOException.bytesTransferred = 0;
            return interruptedIOException;
        }

        private long tickThreadStartedNanos() {
            return tickThreadStartedNanos;
        }

        private long firstTickPublishedNanos() {
            return firstTickPublishedNanos;
        }

        private long reconnectWindowReleasedNanos() {
            return reconnectWindowReleasedNanos;
        }

        private long firstReconnectTickPublishedNanos() {
            return firstReconnectTickPublishedNanos;
        }

        private long completedNanos() {
            return completedNanos;
        }

        private int producedFrames() {
            return producedFrames;
        }
    }

    public record LiveServerReceipt(
            long serverPid,
            String socketHost,
            int socketPort,
            long listeningStartedNanos,
            long firstDisconnectNanos,
            long secondAcceptNanos,
            List<ProcessDirectReceipt> connectionReceipts,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
            List<ShipCommandSink.Result> commandResults) {

        public LiveServerReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            connectionReceipts = List.copyOf(Objects.requireNonNull(
                    connectionReceipts,
                    "connectionReceipts"));
            movementCommands = List.copyOf(Objects.requireNonNull(movementCommands, "movementCommands"));
            commandResults = List.copyOf(Objects.requireNonNull(commandResults, "commandResults"));
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (connectionReceipts.size() != CONNECTION_COUNT
                    || movementCommands.size() != CONNECTION_COUNT
                    || commandResults.size() != CONNECTION_COUNT) {
                throw new IllegalArgumentException("live server receipt requires exactly two connections");
            }
            if (listeningStartedNanos <= 0L
                    || firstDisconnectNanos < listeningStartedNanos
                    || secondAcceptNanos <= firstDisconnectNanos) {
                throw new IllegalArgumentException("live server timing must prove reconnect after disconnect");
            }
            for (int i = 0; i < CONNECTION_COUNT; i++) {
                ProcessDirectReceipt receipt = connectionReceipts.get(i);
                if (receipt.serverPid() != serverPid) {
                    throw new IllegalArgumentException("connection receipt server pid mismatch at " + i);
                }
                if (receipt.clientPid() == serverPid) {
                    throw new IllegalArgumentException("connection client must be a separate process");
                }
                if (!receipt.directReceipt().socketHost().equals(socketHost)
                        || receipt.directReceipt().socketPort() != socketPort) {
                    throw new IllegalArgumentException("connection receipt must use persistent live socket");
                }
                Objects.requireNonNull(commandResults.get(i), "commandResult");
            }
            if (connectionReceipts.get(0).clientPid() == connectionReceipts.get(1).clientPid()) {
                throw new IllegalArgumentException("reconnect proof requires a second client process");
            }
        }

        public int acceptedConnections() {
            return connectionReceipts.size();
        }

        public long serverUptimeNanosThroughReconnect() {
            return secondAcceptNanos - listeningStartedNanos;
        }
    }

    public record PersistentConcurrentLiveServerReceipt(
            long serverPid,
            String socketHost,
            int socketPort,
            long listeningStartedNanos,
            long concurrentWindowClosedNanos,
            long reconnectAcceptedNanos,
            ConcurrentMultiplayerSessionRunner.SessionReceipt concurrentSessionReceipt,
            ProcessDirectReceipt reconnectReceipt,
            HeadlessSnapshotSocketClient.ClientMovementCommand reconnectMovementCommand,
            ShipCommandSink.Result reconnectCommandResult) {

        public PersistentConcurrentLiveServerReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            concurrentSessionReceipt = Objects.requireNonNull(
                    concurrentSessionReceipt,
                    "concurrentSessionReceipt");
            reconnectReceipt = Objects.requireNonNull(reconnectReceipt, "reconnectReceipt");
            reconnectMovementCommand = Objects.requireNonNull(
                    reconnectMovementCommand,
                    "reconnectMovementCommand");
            reconnectCommandResult = Objects.requireNonNull(reconnectCommandResult, "reconnectCommandResult");
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (listeningStartedNanos <= 0L
                    || concurrentWindowClosedNanos < listeningStartedNanos
                    || reconnectAcceptedNanos <= concurrentWindowClosedNanos) {
                throw new IllegalArgumentException(
                        "persistent concurrent live server timing must prove reconnect after concurrent window");
            }
            if (concurrentSessionReceipt.serverPid() != serverPid
                    || reconnectReceipt.serverPid() != serverPid) {
                throw new IllegalArgumentException("all windows must use the same server process");
            }
            if (!concurrentSessionReceipt.socketHost().equals(socketHost)
                    || concurrentSessionReceipt.socketPort() != socketPort
                    || !reconnectReceipt.directReceipt().socketHost().equals(socketHost)
                    || reconnectReceipt.directReceipt().socketPort() != socketPort) {
                throw new IllegalArgumentException("all windows must use the persistent live socket");
            }
            if (reconnectReceipt.clientPid() == serverPid
                    || concurrentSessionReceipt.clientPids().contains(
                            Long.valueOf(reconnectReceipt.clientPid()))) {
                throw new IllegalArgumentException("reconnect client must be a new separate process");
            }
        }

        public int acceptedConnections() {
            return concurrentSessionReceipt.processReceipts().size() + 1;
        }

        public long serverUptimeNanosThroughReconnect() {
            return reconnectAcceptedNanos - listeningStartedNanos;
        }
    }

    public record IndependentTickLiveServerReceipt(
            long serverPid,
            String socketHost,
            int socketPort,
            long listeningStartedNanos,
            long tickThreadStartedNanos,
            long firstTickPublishedNanos,
            long clientAcceptedNanos,
            long tickThreadCompletedNanos,
            int producedFrames,
            ProcessDirectReceipt processReceipt,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult) {

        public IndependentTickLiveServerReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            processReceipt = Objects.requireNonNull(processReceipt, "processReceipt");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (producedFrames <= 0) {
                throw new IllegalArgumentException("producedFrames must be > 0; got " + producedFrames);
            }
            if (listeningStartedNanos <= 0L
                    || tickThreadStartedNanos < listeningStartedNanos
                    || firstTickPublishedNanos < tickThreadStartedNanos
                    || clientAcceptedNanos <= firstTickPublishedNanos
                    || tickThreadCompletedNanos < firstTickPublishedNanos) {
                throw new IllegalArgumentException(
                        "independent tick timing must prove tick publication before client accept");
            }
            if (processReceipt.serverPid() != serverPid) {
                throw new IllegalArgumentException("process receipt server pid mismatch");
            }
            if (processReceipt.clientPid() == serverPid) {
                throw new IllegalArgumentException("independent tick client must be a separate process");
            }
            if (!processReceipt.directReceipt().socketHost().equals(socketHost)
                    || processReceipt.directReceipt().socketPort() != socketPort) {
                throw new IllegalArgumentException("independent tick receipt must use the live socket");
            }
            if (processReceipt.receiverFrames().size() != producedFrames) {
                throw new IllegalArgumentException("produced frame count must match receiver frames");
            }
        }

        public long tickLeadNanosBeforeAccept() {
            return clientAcceptedNanos - firstTickPublishedNanos;
        }
    }

    public record IndependentTickObserverLiveServerReceipt(
            long serverPid,
            String socketHost,
            int socketPort,
            long listeningStartedNanos,
            long tickThreadStartedNanos,
            long firstTickPublishedNanos,
            long observerAcceptedNanos,
            long tickThreadCompletedNanos,
            int producedFrames,
            ProcessDirectReceipt processReceipt) {

        public IndependentTickObserverLiveServerReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            processReceipt = Objects.requireNonNull(processReceipt, "processReceipt");
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (producedFrames <= 0) {
                throw new IllegalArgumentException("producedFrames must be > 0; got " + producedFrames);
            }
            if (listeningStartedNanos <= 0L
                    || tickThreadStartedNanos < listeningStartedNanos
                    || firstTickPublishedNanos < tickThreadStartedNanos
                    || observerAcceptedNanos <= firstTickPublishedNanos
                    || tickThreadCompletedNanos < firstTickPublishedNanos) {
                throw new IllegalArgumentException(
                        "independent tick observer timing must prove tick publication before observer accept");
            }
            if (processReceipt.serverPid() != serverPid) {
                throw new IllegalArgumentException("observer process receipt server pid mismatch");
            }
            if (processReceipt.clientPid() == serverPid) {
                throw new IllegalArgumentException("observer client must be a separate process");
            }
            if (!processReceipt.directReceipt().socketHost().equals(socketHost)
                    || processReceipt.directReceipt().socketPort() != socketPort) {
                throw new IllegalArgumentException("observer receipt must use the live socket");
            }
            if (processReceipt.directReceipt().receiverClientMovementCommandResult() != null
                    || processReceipt.directReceipt().receiverClientWeaponGroupCommandResult() != null
                    || processReceipt.directReceipt().receiverClientFireCommandResult() != null
                    || processReceipt.directReceipt().receiverClientSystemCommandResult() != null) {
                throw new IllegalArgumentException("observer receipt must not include command results");
            }
            if (processReceipt.receiverFrames().size() != producedFrames) {
                throw new IllegalArgumentException("produced frames must match observer frames");
            }
        }

        public long tickLeadNanosBeforeObserverAccept() {
            return observerAcceptedNanos - firstTickPublishedNanos;
        }
    }

    public record IndependentTickAdmissionRejectLiveServerReceipt(
            long serverPid,
            String socketHost,
            int socketPort,
            long listeningStartedNanos,
            long tickThreadStartedNanos,
            long firstTickPublishedNanos,
            long rejectedAdmissionCompletedNanos,
            long acceptedClientAcceptedNanos,
            long tickThreadCompletedNanos,
            int producedFrames,
            FrameBoundRejectedProcessAdmissionReceipt rejectedAdmissionReceipt,
            ProcessDirectReceipt acceptedProcessReceipt,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult) {

        public IndependentTickAdmissionRejectLiveServerReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            rejectedAdmissionReceipt = Objects.requireNonNull(
                    rejectedAdmissionReceipt,
                    "rejectedAdmissionReceipt");
            acceptedProcessReceipt = Objects.requireNonNull(
                    acceptedProcessReceipt,
                    "acceptedProcessReceipt");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (producedFrames <= 0) {
                throw new IllegalArgumentException("producedFrames must be > 0; got " + producedFrames);
            }
            if (listeningStartedNanos <= 0L
                    || tickThreadStartedNanos < listeningStartedNanos
                    || firstTickPublishedNanos < tickThreadStartedNanos
                    || rejectedAdmissionCompletedNanos <= firstTickPublishedNanos
                    || acceptedClientAcceptedNanos <= rejectedAdmissionCompletedNanos
                    || tickThreadCompletedNanos < firstTickPublishedNanos) {
                throw new IllegalArgumentException(
                        "independent tick admission resilience timing must prove rejection before accepted stream");
            }
            if (rejectedAdmissionReceipt.serverPid() != serverPid
                    || acceptedProcessReceipt.serverPid() != serverPid) {
                throw new IllegalArgumentException("all live admission receipts must use the same server process");
            }
            if (!rejectedAdmissionReceipt.socketHost().equals(socketHost)
                    || rejectedAdmissionReceipt.socketPort() != socketPort
                    || !acceptedProcessReceipt.directReceipt().socketHost().equals(socketHost)
                    || acceptedProcessReceipt.directReceipt().socketPort() != socketPort) {
                throw new IllegalArgumentException(
                        "admission rejection and accepted stream must use the same live socket");
            }
            if (rejectedAdmissionReceipt.clientPid() == serverPid
                    || acceptedProcessReceipt.clientPid() == serverPid
                    || rejectedAdmissionReceipt.clientPid() == acceptedProcessReceipt.clientPid()) {
                throw new IllegalArgumentException(
                        "rejected and accepted clients must be separate process clients");
            }
            if (acceptedProcessReceipt.receiverFrames().size() != producedFrames) {
                throw new IllegalArgumentException("produced frames must match accepted process frames");
            }
        }

        public int rejectedAdmissions() {
            return 1;
        }

        public int acceptedConnections() {
            return 1;
        }

        public long tickLeadNanosBeforeRejectedAdmissionCompletes() {
            return rejectedAdmissionCompletedNanos - firstTickPublishedNanos;
        }

        public long serverUptimeNanosThroughAcceptedClient() {
            return acceptedClientAcceptedNanos - listeningStartedNanos;
        }
    }

    public record IndependentTickReconnectLiveServerReceipt(
            long serverPid,
            String socketHost,
            int socketPort,
            long listeningStartedNanos,
            long tickThreadStartedNanos,
            long firstTickPublishedNanos,
            long firstClientAcceptedNanos,
            long firstDisconnectNanos,
            long reconnectWindowReleasedNanos,
            long firstReconnectTickPublishedNanos,
            long secondClientAcceptedNanos,
            long tickThreadCompletedNanos,
            int producedFrames,
            List<ProcessDirectReceipt> connectionReceipts,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
            List<ShipCommandSink.Result> commandResults) {

        public IndependentTickReconnectLiveServerReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            connectionReceipts = List.copyOf(Objects.requireNonNull(
                    connectionReceipts,
                    "connectionReceipts"));
            movementCommands = List.copyOf(Objects.requireNonNull(movementCommands, "movementCommands"));
            commandResults = List.copyOf(Objects.requireNonNull(commandResults, "commandResults"));
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (connectionReceipts.size() != CONNECTION_COUNT
                    || movementCommands.size() != CONNECTION_COUNT
                    || commandResults.size() != CONNECTION_COUNT) {
                throw new IllegalArgumentException(
                        "independent tick reconnect receipt requires exactly two connections");
            }
            if (producedFrames <= 0) {
                throw new IllegalArgumentException("producedFrames must be > 0; got " + producedFrames);
            }
            if (listeningStartedNanos <= 0L
                    || tickThreadStartedNanos < listeningStartedNanos
                    || firstTickPublishedNanos < tickThreadStartedNanos
                    || firstClientAcceptedNanos <= firstTickPublishedNanos
                    || firstDisconnectNanos < firstClientAcceptedNanos
                    || reconnectWindowReleasedNanos < firstDisconnectNanos
                    || firstReconnectTickPublishedNanos < reconnectWindowReleasedNanos
                    || secondClientAcceptedNanos <= firstReconnectTickPublishedNanos
                    || tickThreadCompletedNanos < firstReconnectTickPublishedNanos) {
                throw new IllegalArgumentException(
                        "independent tick reconnect timing must prove a resumed tick window before reconnect accept");
            }

            int totalReceivedFrames = 0;
            for (int i = 0; i < CONNECTION_COUNT; i++) {
                ProcessDirectReceipt receipt = connectionReceipts.get(i);
                if (receipt.serverPid() != serverPid) {
                    throw new IllegalArgumentException("connection receipt server pid mismatch at " + i);
                }
                if (receipt.clientPid() == serverPid) {
                    throw new IllegalArgumentException("independent tick reconnect client must be separate");
                }
                if (!receipt.directReceipt().socketHost().equals(socketHost)
                        || receipt.directReceipt().socketPort() != socketPort) {
                    throw new IllegalArgumentException(
                            "independent tick reconnect receipt must use the persistent live socket");
                }
                if (receipt.receiverFrames().isEmpty()) {
                    throw new IllegalArgumentException("independent tick reconnect window had no frames");
                }
                totalReceivedFrames += receipt.receiverFrames().size();
                Objects.requireNonNull(movementCommands.get(i), "movementCommand");
                Objects.requireNonNull(commandResults.get(i), "commandResult");
            }
            if (connectionReceipts.get(0).clientPid() == connectionReceipts.get(1).clientPid()) {
                throw new IllegalArgumentException("reconnect proof requires a second client process");
            }
            if (producedFrames != totalReceivedFrames) {
                throw new IllegalArgumentException("produced frame count must match both reconnect windows");
            }

            List<OfflineCombatStepWireFrame> firstFrames = connectionReceipts.get(0).receiverFrames();
            List<OfflineCombatStepWireFrame> secondFrames = connectionReceipts.get(1).receiverFrames();
            long firstWindowCompletedTick =
                    firstFrames.get(firstFrames.size() - 1).result().completedTick();
            long secondWindowStartedTick = secondFrames.get(0).result().startedTick();
            if (secondWindowStartedTick != firstWindowCompletedTick) {
                throw new IllegalArgumentException(
                        "reconnect window must continue after first window completed tick");
            }
        }

        public int acceptedConnections() {
            return connectionReceipts.size();
        }

        public long firstTickLeadNanosBeforeAccept() {
            return firstClientAcceptedNanos - firstTickPublishedNanos;
        }

        public long reconnectTickLeadNanosBeforeAccept() {
            return secondClientAcceptedNanos - firstReconnectTickPublishedNanos;
        }

        public long serverUptimeNanosThroughReconnect() {
            return secondClientAcceptedNanos - listeningStartedNanos;
        }
    }

    public record IndependentTickConcurrentLiveServerReceipt(
            long serverPid,
            String socketHost,
            int socketPort,
            long listeningStartedNanos,
            long tickThreadStartedNanos,
            long firstTickPublishedNanos,
            long concurrentSessionStartedNanos,
            long concurrentSessionCompletedNanos,
            long tickThreadCompletedNanos,
            int producedFrames,
            ConcurrentMultiplayerSessionRunner.SessionReceipt concurrentSessionReceipt) {

        public IndependentTickConcurrentLiveServerReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            concurrentSessionReceipt = Objects.requireNonNull(
                    concurrentSessionReceipt,
                    "concurrentSessionReceipt");
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (producedFrames <= 0) {
                throw new IllegalArgumentException("producedFrames must be > 0; got " + producedFrames);
            }
            if (listeningStartedNanos <= 0L
                    || tickThreadStartedNanos < listeningStartedNanos
                    || firstTickPublishedNanos < tickThreadStartedNanos
                    || concurrentSessionStartedNanos <= firstTickPublishedNanos
                    || concurrentSessionCompletedNanos < concurrentSessionStartedNanos
                    || tickThreadCompletedNanos <= concurrentSessionStartedNanos) {
                throw new IllegalArgumentException(
                        "independent tick concurrent timing must prove producer overlap with fanout session");
            }
            if (concurrentSessionReceipt.serverPid() != serverPid) {
                throw new IllegalArgumentException("concurrent session server pid mismatch");
            }
            if (!concurrentSessionReceipt.socketHost().equals(socketHost)
                    || concurrentSessionReceipt.socketPort() != socketPort) {
                throw new IllegalArgumentException(
                        "independent tick concurrent receipt must use the live socket");
            }
            if (concurrentSessionReceipt.sharedFrames().size() != producedFrames) {
                throw new IllegalArgumentException("produced frames must match shared concurrent frames");
            }
        }

        public int acceptedConnections() {
            return concurrentSessionReceipt.processReceipts().size();
        }

        public long firstTickLeadNanosBeforeConcurrentSession() {
            return concurrentSessionStartedNanos - firstTickPublishedNanos;
        }
    }

    public record IndependentTickConflictReconnectLiveServerReceipt(
            long serverPid,
            String socketHost,
            int socketPort,
            long listeningStartedNanos,
            long tickThreadStartedNanos,
            long firstTickPublishedNanos,
            long concurrentSessionStartedNanos,
            long concurrentWindowClosedNanos,
            long reconnectWindowReleasedNanos,
            long firstReconnectTickPublishedNanos,
            long reconnectAcceptedNanos,
            long tickThreadCompletedNanos,
            int producedFrames,
            ConcurrentMultiplayerSessionRunner.SessionReceipt concurrentSessionReceipt,
            ProcessDirectReceipt reconnectReceipt,
            HeadlessSnapshotSocketClient.ClientMovementCommand reconnectMovementCommand,
            ShipCommandSink.Result reconnectCommandResult) {

        public IndependentTickConflictReconnectLiveServerReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            concurrentSessionReceipt = Objects.requireNonNull(
                    concurrentSessionReceipt,
                    "concurrentSessionReceipt");
            reconnectReceipt = Objects.requireNonNull(reconnectReceipt, "reconnectReceipt");
            reconnectMovementCommand = Objects.requireNonNull(
                    reconnectMovementCommand,
                    "reconnectMovementCommand");
            reconnectCommandResult = Objects.requireNonNull(reconnectCommandResult, "reconnectCommandResult");
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (producedFrames <= 0) {
                throw new IllegalArgumentException("producedFrames must be > 0; got " + producedFrames);
            }
            if (listeningStartedNanos <= 0L
                    || tickThreadStartedNanos < listeningStartedNanos
                    || firstTickPublishedNanos < tickThreadStartedNanos
                    || concurrentSessionStartedNanos <= firstTickPublishedNanos
                    || concurrentWindowClosedNanos < concurrentSessionStartedNanos
                    || reconnectWindowReleasedNanos < concurrentWindowClosedNanos
                    || firstReconnectTickPublishedNanos < reconnectWindowReleasedNanos
                    || reconnectAcceptedNanos <= firstReconnectTickPublishedNanos
                    || tickThreadCompletedNanos < firstReconnectTickPublishedNanos) {
                throw new IllegalArgumentException(
                        "independent tick conflict reconnect timing must prove conflict then reconnect windows");
            }
            if (concurrentSessionReceipt.serverPid() != serverPid
                    || reconnectReceipt.serverPid() != serverPid) {
                throw new IllegalArgumentException("all windows must use the same server process");
            }
            if (!concurrentSessionReceipt.socketHost().equals(socketHost)
                    || concurrentSessionReceipt.socketPort() != socketPort
                    || !reconnectReceipt.directReceipt().socketHost().equals(socketHost)
                    || reconnectReceipt.directReceipt().socketPort() != socketPort) {
                throw new IllegalArgumentException(
                        "independent tick conflict reconnect receipt must use the persistent live socket");
            }
            if (reconnectReceipt.clientPid() == serverPid
                    || concurrentSessionReceipt.clientPids().contains(
                            Long.valueOf(reconnectReceipt.clientPid()))) {
                throw new IllegalArgumentException("reconnect client must be a third process");
            }
            int receivedFrames = concurrentSessionReceipt.sharedFrames().size()
                    + reconnectReceipt.receiverFrames().size();
            if (producedFrames != receivedFrames) {
                throw new IllegalArgumentException("produced frames must match conflict and reconnect windows");
            }
            if (concurrentSessionReceipt.sharedFrames().isEmpty()
                    || reconnectReceipt.receiverFrames().isEmpty()) {
                throw new IllegalArgumentException("conflict and reconnect windows must contain frames");
            }
            List<OfflineCombatStepWireFrame> conflictFrames = concurrentSessionReceipt.sharedFrames();
            List<OfflineCombatStepWireFrame> reconnectFrames = reconnectReceipt.receiverFrames();
            long conflictCompletedTick =
                    conflictFrames.get(conflictFrames.size() - 1).result().completedTick();
            long reconnectStartedTick = reconnectFrames.get(0).result().startedTick();
            if (reconnectStartedTick != conflictCompletedTick) {
                throw new IllegalArgumentException(
                        "reconnect tick window must continue after conflict window completed tick");
            }
        }

        public int acceptedConnections() {
            return concurrentSessionReceipt.processReceipts().size() + 1;
        }

        public long serverUptimeNanosThroughReconnect() {
            return reconnectAcceptedNanos - listeningStartedNanos;
        }
    }
}
