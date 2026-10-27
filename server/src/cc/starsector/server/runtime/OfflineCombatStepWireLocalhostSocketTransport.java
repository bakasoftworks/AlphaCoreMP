package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.adapter.CombatStateHashBoundary;
import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.client.SnapshotClientAdmissionGate;
import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity;
import cc.starsector.server.protocol.NetworkCapabilityManifest;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.SnapshotAdmissionRequestMessage;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bounded local socket transport for offline combat frames.
 *
 * <p>The artifact path reads already-encoded product wire artifact bytes and
 * writes them over a loopback {@link Socket}. The direct path writes completed
 * live step results through the product wire codec to the socket output, then
 * decodes the receiver socket input directly while teeing bytes to a secondary
 * receiver-owned artifact.
 */
public final class OfflineCombatStepWireLocalhostSocketTransport {

    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;
    private static final long RECEIVER_JOIN_TIMEOUT_MILLIS = 10_000L;

    private OfflineCombatStepWireLocalhostSocketTransport() {
    }

    private static SnapshotStreamIdentity genericStreamIdentity() {
        return NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotStreamIdentity();
    }

    @FunctionalInterface
    public interface DirectSocketPublication {
        void publishTo(OfflineCombatStepSink sink) throws IOException;
    }

    public static Receipt send(Path senderArtifactPath, Path receiverArtifactPath, int expectedFrames)
            throws IOException {
        Objects.requireNonNull(senderArtifactPath, "senderArtifactPath");
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        if (senderArtifactPath.toAbsolutePath().normalize()
                .equals(receiverArtifactPath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("receiver artifact path must be distinct from sender artifact path");
        }

        long expectedBytes = Math.multiplyExact((long) expectedFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        long senderArtifactBytes = Files.size(senderArtifactPath);
        if (senderArtifactBytes != expectedBytes) {
            throw new IOException("offline step wire socket sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderArtifactBytes);
        }

        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        InetAddress loopback = InetAddress.getLoopbackAddress();
        Receiver receiver;
        Thread receiverThread;
        IOException senderFailure = null;
        long senderBytes = 0L;
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(loopback, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            receiver = new Receiver(serverSocket, receiverArtifactPath, expectedBytes);
            receiverThread = new Thread(receiver, "offline-step-wire-localhost-socket-receiver");
            receiverThread.setDaemon(true);
            receiverThread.start();

            try (Socket socket = new Socket();
                    InputStream source = Files.newInputStream(senderArtifactPath)) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                socket.connect(new InetSocketAddress(loopback, port), SOCKET_TIMEOUT_MILLIS);
                senderBytes = copyBounded(source, socket.getOutputStream(), expectedBytes);
                socket.shutdownOutput();
            } catch (IOException ioe) {
                senderFailure = ioe;
            }

            joinReceiver(receiverThread);
        }

        if (senderFailure != null) {
            throw senderFailure;
        }
        receiver.rethrowIfFailed();
        if (senderBytes != expectedBytes) {
            throw new IOException("offline step wire socket sender wrote unexpected byte count: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        long receiverBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("offline step wire socket receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        List<OfflineCombatStepWireFrame> receiverFrames =
                OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
        if (receiverFrames.size() != expectedFrames) {
            throw new IOException("offline step wire socket receiver frame count mismatch: expected "
                    + expectedFrames + ", got " + receiverFrames.size());
        }
        return new Receipt(
                senderArtifactPath,
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                senderBytes,
                receiverBytes,
                loopback.getHostAddress(),
                port,
                receiverFrames);
    }

    public static DirectReceipt sendDirect(
            Path receiverArtifactPath,
            int expectedFrames,
            DirectSocketPublication publication) throws IOException {
        return sendDirect(receiverArtifactPath, expectedFrames, publication, genericStreamIdentity(), frame -> {
        });
    }

    public static DirectReceipt sendDirect(
            Path receiverArtifactPath,
            int expectedFrames,
            DirectSocketPublication publication,
            Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer) throws IOException {
        return sendDirect(
                receiverArtifactPath,
                expectedFrames,
                publication,
                genericStreamIdentity(),
                receiverFrameConsumer);
    }

    public static DirectReceipt sendDirect(
            Path receiverArtifactPath,
            int expectedFrames,
            DirectSocketPublication publication,
            SnapshotStreamIdentity streamIdentity,
            Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(receiverFrameConsumer, "receiverFrameConsumer");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }

        long expectedBytes = Math.multiplyExact((long) expectedFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        InetAddress loopback = InetAddress.getLoopbackAddress();
        Thread clientThread;
        IOException senderFailure = null;
        IOException clientFailure = null;
        RuntimeException publisherFailure = null;
        RuntimeException clientRuntimeFailure = null;
        long senderBytes = 0L;
        long receiverBytes = 0L;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        List<OfflineCombatStepWireFrame> receiverFrames = List.of();
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        HeadlessSnapshotSocketClient.ServerMovementCommandResult clientMovementCommandResult = null;
        int clientMovementCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ServerInitialSync clientInitialSync = null;
        int clientInitialSyncBytes = 0;
        HeadlessSnapshotSocketClient.ServerStreamCompletion clientStreamCompletion = null;
        int clientStreamCompletionBytes = 0;
        DirectClientRunner clientRunner = null;
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(loopback, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientRunner = new DirectClientRunner(
                    new HeadlessSnapshotSocketClient(),
                    loopback.getHostAddress(),
                    port,
                    receiverArtifactPath,
                    expectedFrames,
                    streamIdentity,
                    receiverFrameConsumer);
            clientThread = new Thread(clientRunner, "headless-snapshot-socket-client");
            clientThread.setDaemon(true);
            clientThread.start();

            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socket.getInputStream());
                OutputStream socketOutput = socket.getOutputStream();
                clientAdmission =
                        new SnapshotClientAdmissionGate(expectedFrames, genericStreamIdentity()).admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                if (!clientAdmission.accepted()) {
                    throw new IOException("headless snapshot socket client admission rejected: "
                            + clientAdmission.reason());
                }
                HeadlessSnapshotSocketClient.writeTransportSessionOpened(
                        socketOutput,
                        HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                                clientHandshake,
                                loopback.getHostAddress(),
                                port));
                DirectSocketSink sink = new DirectSocketSink(socketOutput, clientHandshake, expectedFrames);
                try {
                    publication.publishTo(sink);
                    sink.finish();
                    HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(clientHandshake, expectedFrames, sink.frames());
                    clientStreamCompletion =
                            HeadlessSnapshotSocketClient.ServerStreamCompletion.completed(clientHandshake);
                    HeadlessSnapshotSocketClient.writeStreamCompletion(socketOutput, clientStreamCompletion);
                    clientStreamCompletionBytes = HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES;
                } catch (UncheckedIOException uioe) {
                    senderFailure = uioe.getCause();
                } catch (IOException ioe) {
                    senderFailure = ioe;
                } catch (RuntimeException rte) {
                    publisherFailure = rte;
                } finally {
                    senderBytes = sink.bytesWritten();
                    senderFrames = sink.frames();
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
            }

            joinReceiver(clientThread);
            if (clientRunner != null) {
                clientFailure = clientRunner.failure();
                clientRuntimeFailure = clientRunner.runtimeFailure();
                HeadlessSnapshotSocketClient.Receipt clientReceipt = clientRunner.receipt();
                if (clientReceipt != null) {
                    receiverBytes = clientReceipt.bytesReceived();
                    receiverFrames = clientReceipt.frames();
                    clientHandshake = clientReceipt.handshake();
                    clientAdmissionAck = clientReceipt.admissionAck();
                    clientAdmissionAckBytes = clientReceipt.admissionAckBytes();
                    clientMovementCommandResult = clientReceipt.movementCommandResult();
                    clientMovementCommandResultBytes = clientReceipt.movementCommandResultBytes();
                    clientInitialSync = clientReceipt.initialSync();
                    clientInitialSyncBytes = clientReceipt.initialSyncBytes();
                    clientStreamCompletion = clientReceipt.streamCompletion();
                    clientStreamCompletionBytes = clientReceipt.streamCompletionBytes();
                    clientAdmission = requireClientAdmission(clientHandshake, expectedFrames, genericStreamIdentity());
                }
            }
        }

        if (senderFailure != null) {
            throw senderFailure;
        }
        if (publisherFailure != null) {
            throw publisherFailure;
        }
        if (clientFailure != null) {
            throw clientFailure;
        }
        if (clientRuntimeFailure != null) {
            throw clientRuntimeFailure;
        }
        if (clientHandshake == null) {
            throw new IOException("headless snapshot socket client did not complete handshake");
        }
        if (clientAdmission == null || !clientAdmission.accepted()) {
            throw new IOException("headless snapshot socket client was not admitted");
        }
        if (senderBytes != expectedBytes) {
            throw new IOException("offline step direct wire socket sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        long receiverArtifactBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("offline step direct wire socket receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        if (receiverArtifactBytes != expectedBytes) {
            throw new IOException("offline step direct wire socket receiver artifact byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverArtifactBytes);
        }
        if (receiverFrames.size() != expectedFrames) {
            throw new IOException("offline step direct wire socket receiver frame count mismatch: expected "
                    + expectedFrames + ", got " + receiverFrames.size());
        }
        return new DirectReceipt(
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                senderBytes,
                receiverBytes,
                loopback.getHostAddress(),
                port,
                "socket_input_stream",
                false,
                "cc.starsector.server.client.HeadlessSnapshotSocketClient",
                clientHandshake,
                clientAdmission,
                clientAdmissionAckBytes,
                clientAdmissionAck,
                clientMovementCommandResultBytes,
                clientMovementCommandResult,
                0,
                null,
                0,
                null,
                0,
                null,
                clientInitialSyncBytes,
                clientInitialSync,
                clientStreamCompletionBytes,
                clientStreamCompletion,
                senderFrames,
                receiverFrames);
    }

    public static ProcessDirectReceipt sendDirectToProcess(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication) throws IOException {
        return sendDirectToProcess(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                genericStreamIdentity());
    }

    public static ProcessDirectReceipt sendDirectToProcess(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return DirectProcessRunner.sendDirectToProcess(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                streamIdentity);
    }

    public static StreamIdentityRejectedProcessAdmissionReceipt
            sendStreamIdentityRejectedToProcessWithClientStreamIdentity(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    SnapshotStreamIdentity requiredStreamIdentity,
                    SnapshotStreamIdentity offeredStreamIdentity) throws IOException {
        return sendStreamIdentityRejectedToProcessWithClientStreamIdentity(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                requiredStreamIdentity,
                offeredStreamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static StreamIdentityRejectedProcessAdmissionReceipt
            sendStreamIdentityRejectedToProcessOffLoopbackWithClientStreamIdentity(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    SnapshotStreamIdentity requiredStreamIdentity,
                    SnapshotStreamIdentity offeredStreamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        StreamIdentityRejectedProcessAdmissionReceipt receipt =
                sendStreamIdentityRejectedToProcessWithClientStreamIdentity(
                        receiverArtifactPath,
                        clientReceiptPath,
                        expectedFrames,
                        requiredStreamIdentity,
                        offeredStreamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static CapabilityManifestRejectedProcessAdmissionReceipt
            sendCapabilityManifestRejectedToProcessOffLoopbackWithClientCapabilityManifest(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    SnapshotStreamIdentity streamIdentity,
                    NetworkCapabilityManifest offeredCapabilityManifest) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        CapabilityManifestRejectedProcessAdmissionReceipt receipt =
                sendCapabilityManifestRejectedToProcessWithClientCapabilityManifest(
                        receiverArtifactPath,
                        clientReceiptPath,
                        expectedFrames,
                        streamIdentity,
                        offeredCapabilityManifest,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static FrameBoundRejectedProcessAdmissionReceipt
            sendFrameBoundRejectedToProcessOffLoopbackWithClientExpectedFrames(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int requiredFrames,
                    int clientExpectedFrames,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        FrameBoundRejectedProcessAdmissionReceipt receipt =
                sendFrameBoundRejectedToProcessWithClientExpectedFrames(
                        receiverArtifactPath,
                        clientReceiptPath,
                        requiredFrames,
                        clientExpectedFrames,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static ConcurrentMultiplayerSessionRunner.SessionReceipt
            sendConcurrentTwoClientAuthoritativeSession(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFrames,
                    DirectSocketPublication publication,
                    List<ClientAuthorityIdentity> identities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return ConcurrentMultiplayerSessionRunner.runTwoClientAuthoritativeSession(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFrames,
                publication,
                identities,
                commands,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    /**
     * demo-program R6 token-delivery + R8b ON path end-to-end over loopback: each subprocess client
     * delivers its {@code clientSeatCredentials} entry, the gate enforces the minted seat tokens +
     * {@code requiredContentFingerprint}, and {@link SeatAdmission} binds each connection to its
     * seat token's ship. The live consumer for the wiring.
     */
    public static ConcurrentMultiplayerSessionRunner.SessionReceipt
            sendConcurrentTwoClientSeatEnforcedSession(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFrames,
                    DirectSocketPublication publication,
                    List<ClientAuthorityIdentity> identities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity,
                    MatchSeatAuthority seatAuthority,
                    List<HeadlessSnapshotSocketClient.SeatCredential> clientSeatCredentials,
                    String requiredContentFingerprint) throws IOException {
        return ConcurrentMultiplayerSessionRunner.runTwoClientSeatEnforcedSession(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFrames,
                publication,
                identities,
                commands,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress(),
                seatAuthority,
                clientSeatCredentials,
                requiredContentFingerprint);
    }

    public static ConcurrentMultiplayerSessionRunner.SessionReceipt
            sendConcurrentTwoClientAuthoritativeSessionOffLoopback(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFrames,
                    DirectSocketPublication publication,
                    List<ClientAuthorityIdentity> identities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        ConcurrentMultiplayerSessionRunner.SessionReceipt receipt =
                ConcurrentMultiplayerSessionRunner.runTwoClientAuthoritativeSession(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFrames,
                        publication,
                        identities,
                        commands,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static PersistentLiveServerRunner.LiveServerReceipt
            sendPersistentLiveServerReconnectWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    List<DirectSocketPublication> publications,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentLiveServerRunner.runTwoConnectionLiveServer(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerConnection,
                publications,
                movementCommands,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentLiveServerRunner.LiveServerReceipt
            sendPersistentLiveServerReconnectOffLoopbackWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    List<DirectSocketPublication> publications,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentLiveServerRunner.LiveServerReceipt receipt =
                PersistentLiveServerRunner.runTwoConnectionLiveServer(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerConnection,
                        publications,
                        movementCommands,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static PersistentLiveServerRunner.PersistentConcurrentLiveServerReceipt
            sendPersistentConcurrentLiveServerReconnectWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    DirectSocketPublication concurrentPublication,
                    DirectSocketPublication reconnectPublication,
                    List<ClientAuthorityIdentity> concurrentIdentities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> concurrentMovementCommands,
                    HeadlessSnapshotSocketClient.ClientMovementCommand reconnectMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentLiveServerRunner.runConcurrentThenReconnectLiveServer(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerConnection,
                concurrentPublication,
                reconnectPublication,
                concurrentIdentities,
                concurrentMovementCommands,
                reconnectMovementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentLiveServerRunner.PersistentConcurrentLiveServerReceipt
            sendPersistentConcurrentLiveServerReconnectOffLoopbackWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    DirectSocketPublication concurrentPublication,
                    DirectSocketPublication reconnectPublication,
                    List<ClientAuthorityIdentity> concurrentIdentities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> concurrentMovementCommands,
                    HeadlessSnapshotSocketClient.ClientMovementCommand reconnectMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentLiveServerRunner.PersistentConcurrentLiveServerReceipt receipt =
                PersistentLiveServerRunner.runConcurrentThenReconnectLiveServer(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerConnection,
                        concurrentPublication,
                        reconnectPublication,
                        concurrentIdentities,
                        concurrentMovementCommands,
                        reconnectMovementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickLiveServerReceipt
            sendIndependentTickLiveServerWithClientMovementCommand(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentLiveServerRunner.runIndependentTickLiveServer(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                step,
                stateHashBoundary,
                movementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentLiveServerRunner.IndependentTickLiveServerReceipt
            sendIndependentTickLiveServerOffLoopbackWithClientMovementCommand(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentLiveServerRunner.IndependentTickLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickLiveServer(
                        receiverArtifactPath,
                        clientReceiptPath,
                        expectedFrames,
                        step,
                        stateHashBoundary,
                        movementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickAdmissionRejectLiveServerReceipt
            sendIndependentTickAdmissionRejectLiveServerWithClientMovementCommand(
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
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentLiveServerRunner.runIndependentTickAdmissionRejectThenAcceptLiveServer(
                rejectedReceiverArtifactPath,
                acceptedReceiverArtifactPath,
                rejectedClientReceiptPath,
                acceptedClientReceiptPath,
                expectedFrames,
                rejectedExpectedFrames,
                step,
                stateHashBoundary,
                movementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentLiveServerRunner.IndependentTickAdmissionRejectLiveServerReceipt
            sendIndependentTickAdmissionRejectLiveServerOffLoopbackWithClientMovementCommand(
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
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentLiveServerRunner.IndependentTickAdmissionRejectLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickAdmissionRejectThenAcceptLiveServer(
                        rejectedReceiverArtifactPath,
                        acceptedReceiverArtifactPath,
                        rejectedClientReceiptPath,
                        acceptedClientReceiptPath,
                        expectedFrames,
                        rejectedExpectedFrames,
                        step,
                        stateHashBoundary,
                        movementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickObserverLiveServerReceipt
            sendIndependentTickObserverLiveServer(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentLiveServerRunner.runIndependentTickObserverLiveServer(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                step,
                stateHashBoundary,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentLiveServerRunner.IndependentTickObserverLiveServerReceipt
            sendIndependentTickObserverLiveServerOffLoopback(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentLiveServerRunner.IndependentTickObserverLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickObserverLiveServer(
                        receiverArtifactPath,
                        clientReceiptPath,
                        expectedFrames,
                        step,
                        stateHashBoundary,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickReconnectLiveServerReceipt
            sendIndependentTickReconnectLiveServerWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentLiveServerRunner.runIndependentTickReconnectLiveServer(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerConnection,
                step,
                stateHashBoundary,
                movementCommands,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentLiveServerRunner.IndependentTickReconnectLiveServerReceipt
            sendIndependentTickReconnectLiveServerOffLoopbackWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentLiveServerRunner.IndependentTickReconnectLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickReconnectLiveServer(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerConnection,
                        step,
                        stateHashBoundary,
                        movementCommands,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickConcurrentLiveServerReceipt
            sendIndependentTickConcurrentLiveServerWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFrames,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    List<ClientAuthorityIdentity> identities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentLiveServerRunner.runIndependentTickConcurrentLiveServer(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFrames,
                step,
                stateHashBoundary,
                identities,
                commands,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentLiveServerRunner.IndependentTickConcurrentLiveServerReceipt
            sendIndependentTickConcurrentLiveServerOffLoopbackWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFrames,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    List<ClientAuthorityIdentity> identities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentLiveServerRunner.IndependentTickConcurrentLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickConcurrentLiveServer(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFrames,
                        step,
                        stateHashBoundary,
                        identities,
                        commands,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickConflictReconnectLiveServerReceipt
            sendIndependentTickConflictReconnectLiveServerWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    List<ClientAuthorityIdentity> concurrentIdentities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> concurrentCommands,
                    HeadlessSnapshotSocketClient.ClientMovementCommand reconnectMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentLiveServerRunner.runIndependentTickConflictThenReconnectLiveServer(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerConnection,
                step,
                stateHashBoundary,
                concurrentIdentities,
                concurrentCommands,
                reconnectMovementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentLiveServerRunner.IndependentTickConflictReconnectLiveServerReceipt
            sendIndependentTickConflictReconnectLiveServerOffLoopbackWithClientMovementCommands(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    CombatStepBoundary step,
                    CombatStateHashBoundary stateHashBoundary,
                    List<ClientAuthorityIdentity> concurrentIdentities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> concurrentCommands,
                    HeadlessSnapshotSocketClient.ClientMovementCommand reconnectMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentLiveServerRunner.IndependentTickConflictReconnectLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickConflictThenReconnectLiveServer(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerConnection,
                        step,
                        stateHashBoundary,
                        concurrentIdentities,
                        concurrentCommands,
                        reconnectMovementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessWithClientMovementCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink) throws IOException {
        return DirectProcessRunner.sendDirectToProcessWithClientMovementCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                movementCommand,
                commandSink,
                genericStreamIdentity(),
                InetAddress.getLoopbackAddress());
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessWithClientMovementCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return DirectProcessRunner.sendDirectToProcessWithClientMovementCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                movementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessOffLoopbackWithClientMovementCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        CommandedProcessDirectReceipt receipt = DirectProcessRunner.sendDirectToProcessWithClientMovementCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                movementCommand,
                commandSink,
                streamIdentity,
                socketAddress);
        requireNonLoopbackSocketHost(receipt.processReceipt().directReceipt().socketHost());
        return receipt;
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessWithClientFireCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            ShipCommandSink commandSink) throws IOException {
        return DirectProcessRunner.sendDirectToProcessWithClientFireCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                fireCommand,
                commandSink,
                genericStreamIdentity(),
                InetAddress.getLoopbackAddress());
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessWithClientFireCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return DirectProcessRunner.sendDirectToProcessWithClientFireCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                fireCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessOffLoopbackWithClientFireCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        CommandedProcessDirectReceipt receipt = DirectProcessRunner.sendDirectToProcessWithClientFireCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                fireCommand,
                commandSink,
                streamIdentity,
                socketAddress);
        requireNonLoopbackSocketHost(receipt.processReceipt().directReceipt().socketHost());
        return receipt;
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessWithClientWeaponGroupCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            ShipCommandSink commandSink) throws IOException {
        return DirectProcessRunner.sendDirectToProcessWithClientWeaponGroupCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                weaponGroupCommand,
                commandSink,
                genericStreamIdentity(),
                InetAddress.getLoopbackAddress());
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessWithClientWeaponGroupCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return DirectProcessRunner.sendDirectToProcessWithClientWeaponGroupCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                weaponGroupCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessOffLoopbackWithClientWeaponGroupCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        CommandedProcessDirectReceipt receipt = DirectProcessRunner.sendDirectToProcessWithClientWeaponGroupCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                weaponGroupCommand,
                commandSink,
                streamIdentity,
                socketAddress);
        requireNonLoopbackSocketHost(receipt.processReceipt().directReceipt().socketHost());
        return receipt;
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessWithClientSystemCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ShipCommandSink commandSink) throws IOException {
        return DirectProcessRunner.sendDirectToProcessWithClientSystemCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                systemCommand,
                commandSink,
                genericStreamIdentity(),
                InetAddress.getLoopbackAddress());
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessWithClientSystemCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return DirectProcessRunner.sendDirectToProcessWithClientSystemCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                systemCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static CommandedProcessDirectReceipt sendDirectToProcessOffLoopbackWithClientSystemCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        CommandedProcessDirectReceipt receipt = DirectProcessRunner.sendDirectToProcessWithClientSystemCommand(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                systemCommand,
                commandSink,
                streamIdentity,
                socketAddress);
        requireNonLoopbackSocketHost(receipt.processReceipt().directReceipt().socketHost());
        return receipt;
    }

    private static StreamIdentityRejectedProcessAdmissionReceipt
            sendStreamIdentityRejectedToProcessWithClientStreamIdentity(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    SnapshotStreamIdentity requiredStreamIdentity,
                    SnapshotStreamIdentity offeredStreamIdentity,
                    InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(requiredStreamIdentity, "requiredStreamIdentity");
        Objects.requireNonNull(offeredStreamIdentity, "offeredStreamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }
        Path receiptParent = clientReceiptPath.getParent();
        if (receiptParent != null) {
            Files.createDirectories(receiptParent);
        }
        Path clientLogPath = siblingWithSuffix(clientReceiptPath, ".log");
        Files.deleteIfExists(receiverArtifactPath);
        Files.deleteIfExists(clientReceiptPath);
        Files.deleteIfExists(clientLogPath);

        String socketHost = socketAddress.getHostAddress();
        long serverPid = ProcessHandle.current().pid();
        long clientPid = -1L;
        int clientExitCode = -1;
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        int port;
        Process clientProcess = null;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientProcess = HeadlessSnapshotClientProcessLauncher.startAdmissionHeadlessClientProcess(
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    offeredStreamIdentity,
                    clientLogPath);
            clientPid = clientProcess.pid();
            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socket.getInputStream());
                clientAdmission =
                        new SnapshotClientAdmissionGate(expectedFrames, requiredStreamIdentity)
                                .admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                OutputStream socketOutput = socket.getOutputStream();
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                socket.shutdownOutput();
                if (clientAdmission.accepted()) {
                    throw new IOException("stream identity mismatch client was admitted");
                }
            }
            clientExitCode = HeadlessSnapshotClientProcessLauncher.waitForClientProcess(clientProcess, clientLogPath);
        } finally {
            if (clientProcess != null && clientProcess.isAlive()) {
                clientProcess.destroyForcibly();
            }
        }
        if (clientExitCode != 0) {
            throw new IOException("stream identity mismatch admission client exited " + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null || clientAdmission == null || clientAdmissionAck == null) {
            throw new IOException("stream identity mismatch admission did not complete");
        }
        long receiverArtifactBytes = Files.exists(receiverArtifactPath)
                ? Files.size(receiverArtifactPath)
                : 0L;
        Properties clientReceipt = HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(clientReceiptPath);
        return new StreamIdentityRejectedProcessAdmissionReceipt(
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                expectedFrames,
                receiverArtifactBytes,
                socketHost,
                port,
                serverPid,
                clientPid,
                clientExitCode,
                requiredStreamIdentity,
                offeredStreamIdentity,
                clientHandshake,
                clientAdmission,
                clientAdmissionAckBytes,
                clientAdmissionAck,
                clientReceipt);
    }

    private static CapabilityManifestRejectedProcessAdmissionReceipt
            sendCapabilityManifestRejectedToProcessWithClientCapabilityManifest(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    SnapshotStreamIdentity streamIdentity,
                    NetworkCapabilityManifest offeredCapabilityManifest,
                    InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(offeredCapabilityManifest, "offeredCapabilityManifest");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }
        Path receiptParent = clientReceiptPath.getParent();
        if (receiptParent != null) {
            Files.createDirectories(receiptParent);
        }
        Path clientLogPath = siblingWithSuffix(clientReceiptPath, ".log");
        Files.deleteIfExists(receiverArtifactPath);
        Files.deleteIfExists(clientReceiptPath);
        Files.deleteIfExists(clientLogPath);

        String socketHost = socketAddress.getHostAddress();
        long serverPid = ProcessHandle.current().pid();
        long clientPid = -1L;
        int clientExitCode = -1;
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        int port;
        Process clientProcess = null;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientProcess = HeadlessSnapshotClientProcessLauncher.startAdmissionHeadlessClientProcess(
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    streamIdentity,
                    offeredCapabilityManifest,
                    clientLogPath);
            clientPid = clientProcess.pid();
            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socket.getInputStream());
                clientAdmission =
                        new SnapshotClientAdmissionGate(expectedFrames, streamIdentity)
                                .admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                OutputStream socketOutput = socket.getOutputStream();
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                socket.shutdownOutput();
                if (clientAdmission.accepted()) {
                    throw new IOException("capability manifest mismatch client was admitted");
                }
            }
            clientExitCode = HeadlessSnapshotClientProcessLauncher.waitForClientProcess(clientProcess, clientLogPath);
        } finally {
            if (clientProcess != null && clientProcess.isAlive()) {
                clientProcess.destroyForcibly();
            }
        }
        if (clientExitCode != 0) {
            throw new IOException("capability manifest mismatch admission client exited " + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null || clientAdmission == null || clientAdmissionAck == null) {
            throw new IOException("capability manifest mismatch admission did not complete");
        }
        long receiverArtifactBytes = Files.exists(receiverArtifactPath)
                ? Files.size(receiverArtifactPath)
                : 0L;
        Properties clientReceipt = HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(clientReceiptPath);
        return new CapabilityManifestRejectedProcessAdmissionReceipt(
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                expectedFrames,
                receiverArtifactBytes,
                socketHost,
                port,
                serverPid,
                clientPid,
                clientExitCode,
                streamIdentity,
                offeredCapabilityManifest,
                clientHandshake,
                clientAdmission,
                clientAdmissionAckBytes,
                clientAdmissionAck,
                clientReceipt);
    }

    private static FrameBoundRejectedProcessAdmissionReceipt
            sendFrameBoundRejectedToProcessWithClientExpectedFrames(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int requiredFrames,
                    int clientExpectedFrames,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (requiredFrames < 0) {
            throw new IllegalArgumentException("requiredFrames must be >= 0; got " + requiredFrames);
        }
        if (clientExpectedFrames < 0) {
            throw new IllegalArgumentException(
                    "clientExpectedFrames must be >= 0; got " + clientExpectedFrames);
        }
        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }
        Path receiptParent = clientReceiptPath.getParent();
        if (receiptParent != null) {
            Files.createDirectories(receiptParent);
        }
        Path clientLogPath = siblingWithSuffix(clientReceiptPath, ".log");
        Files.deleteIfExists(receiverArtifactPath);
        Files.deleteIfExists(clientReceiptPath);
        Files.deleteIfExists(clientLogPath);

        String socketHost = socketAddress.getHostAddress();
        long serverPid = ProcessHandle.current().pid();
        long clientPid = -1L;
        int clientExitCode = -1;
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        int port;
        Process clientProcess = null;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            port = serverSocket.getLocalPort();
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
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                OutputStream socketOutput = socket.getOutputStream();
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                socket.shutdownOutput();
                if (clientAdmission.accepted()) {
                    throw new IOException("frame-bound mismatch client was admitted");
                }
            }
            clientExitCode = HeadlessSnapshotClientProcessLauncher.waitForClientProcess(clientProcess, clientLogPath);
        } finally {
            if (clientProcess != null && clientProcess.isAlive()) {
                clientProcess.destroyForcibly();
            }
        }
        if (clientExitCode != 0) {
            throw new IOException("frame-bound mismatch admission client exited " + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null || clientAdmission == null || clientAdmissionAck == null) {
            throw new IOException("frame-bound mismatch admission did not complete");
        }
        long receiverArtifactBytes = Files.exists(receiverArtifactPath)
                ? Files.size(receiverArtifactPath)
                : 0L;
        Properties clientReceipt = HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(clientReceiptPath);
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

    public static PersistentCommandedProcessDirectReceipt sendPersistentSessionToProcessWithClientMovementCommand(
            List<Path> receiverArtifactPaths,
            Path clientReceiptPath,
            int expectedFramesPerWindow,
            List<DirectSocketPublication> publications,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return PersistentSessionProcessRunner.sendPersistentSessionToProcessWithClientMovementCommand(
                receiverArtifactPaths,
                clientReceiptPath,
                expectedFramesPerWindow,
                publications,
                movementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static PersistentCommandedProcessDirectReceipt
            sendPersistentSessionToProcessOffLoopbackWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    Path clientReceiptPath,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        PersistentCommandedProcessDirectReceipt receipt =
                PersistentSessionProcessRunner.sendPersistentSessionToProcessWithClientMovementCommand(
                        receiverArtifactPaths,
                        clientReceiptPath,
                        expectedFramesPerWindow,
                        publications,
                        movementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.windowReceipts().get(0).directReceipt().socketHost());
        return receipt;
    }

    public static ReconnectResumeCommandedProcessDirectReceipt
            sendReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return sendReconnectResumeSessionToProcessWithClientMovementCommand(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerWindow,
                publications,
                movementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static ReconnectResumeCommandedProcessDirectReceipt
            sendReconnectResumeSessionToProcessOffLoopbackWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        ReconnectResumeCommandedProcessDirectReceipt receipt =
                sendReconnectResumeSessionToProcessWithClientMovementCommand(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerWindow,
                        publications,
                        movementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.windowReceipts().get(0).directReceipt().socketHost());
        requireNonLoopbackSocketHost(receipt.windowReceipts().get(1).directReceipt().socketHost());
        return receipt;
    }

    public static ReplayRejectedReconnectResumeCommandedProcessDirectReceipt
            sendReplayRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return ReconnectResumeReplayRunner.run(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerWindow,
                publications,
                movementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static ReplayRejectedReconnectResumeCommandedProcessDirectReceipt
            sendReplayRejectedReconnectResumeSessionToProcessOffLoopbackWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        ReplayRejectedReconnectResumeCommandedProcessDirectReceipt receipt =
                ReconnectResumeReplayRunner.run(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerWindow,
                        publications,
                        movementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(
                receipt.acceptedResumeReceipt().windowReceipts().get(0).directReceipt().socketHost());
        requireNonLoopbackSocketHost(
                receipt.acceptedResumeReceipt().windowReceipts().get(1).directReceipt().socketHost());
        return receipt;
    }

    public static StaleCommandRejectedReconnectResumeProcessReceipt
            sendStaleCommandRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand staleMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return ReconnectResumeCommandTickRunner.run(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerWindow,
                publications,
                staleMovementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static StaleCommandRejectedReconnectResumeProcessReceipt
            sendStaleCommandRejectedReconnectResumeSessionToProcessOffLoopbackWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand staleMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        StaleCommandRejectedReconnectResumeProcessReceipt receipt =
                ReconnectResumeCommandTickRunner.run(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerWindow,
                        publications,
                        staleMovementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.acceptedWindowReceipt().directReceipt().socketHost());
        return receipt;
    }

    public static UnauthorizedCommandRejectedReconnectResumeProcessReceipt
            sendUnauthorizedCommandRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand unauthorizedMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return ReconnectResumeCommandTickRunner.runUnauthorizedShip(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerWindow,
                publications,
                unauthorizedMovementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static UnauthorizedCommandRejectedReconnectResumeProcessReceipt
            sendUnauthorizedCommandRejectedReconnectResumeSessionToProcessOffLoopbackWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand unauthorizedMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        UnauthorizedCommandRejectedReconnectResumeProcessReceipt receipt =
                ReconnectResumeCommandTickRunner.runUnauthorizedShip(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerWindow,
                        publications,
                        unauthorizedMovementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.acceptedWindowReceipt().directReceipt().socketHost());
        return receipt;
    }

    public static MissingClaimRejectedReconnectResumeProcessReceipt
            sendMissingClaimRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return sendMissingClaimRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerWindow,
                publications,
                movementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static MissingClaimRejectedReconnectResumeProcessReceipt
            sendMissingClaimRejectedReconnectResumeSessionToProcessOffLoopbackWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        MissingClaimRejectedReconnectResumeProcessReceipt receipt =
                sendMissingClaimRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerWindow,
                        publications,
                        movementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.acceptedWindowReceipt().directReceipt().socketHost());
        return receipt;
    }

    public static RejectedReconnectResumeCommandedProcessDirectReceipt
            sendRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        return sendRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                receiverArtifactPaths,
                clientReceiptPaths,
                expectedFramesPerWindow,
                publications,
                movementCommand,
                commandSink,
                streamIdentity,
                InetAddress.getLoopbackAddress());
    }

    public static RejectedReconnectResumeCommandedProcessDirectReceipt
            sendRejectedReconnectResumeSessionToProcessOffLoopbackWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        InetAddress socketAddress = requireNonLoopbackIpv4Address();
        RejectedReconnectResumeCommandedProcessDirectReceipt receipt =
                sendRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerWindow,
                        publications,
                        movementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireNonLoopbackSocketHost(receipt.acceptedWindowReceipt().directReceipt().socketHost());
        return receipt;
    }

    private static ReconnectResumeCommandedProcessDirectReceipt
            sendReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != 2
                || clientReceiptPaths.size() != 2
                || publications.size() != 2) {
            throw new IllegalArgumentException(
                    "reconnect resume currently requires exactly two receiver artifacts, receipts, and windows");
        }
        if (expectedFramesPerWindow < 0) {
            throw new IllegalArgumentException(
                    "expectedFramesPerWindow must be >= 0; got " + expectedFramesPerWindow);
        }

        long expectedBytesPerWindow = Math.multiplyExact(
                (long) expectedFramesPerWindow,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        long serverPid = ProcessHandle.current().pid();
        ServerReconnectSessionState sessionState =
                ServerReconnectSessionState.create(expectedFramesPerWindow, serverPid);
        List<SessionWindowProcessResult> results = new ArrayList<>(2);
        for (int i = 0; i < 2; i++) {
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters =
                    resumeClaimParametersOrNull(sessionState.latestCursorOrNull());
            SessionWindowProcessResult result = SessionWindowProcessRunner.runSessionWindowProcessClient(
                    receiverArtifactPaths.get(i),
                    clientReceiptPaths.get(i),
                    expectedFramesPerWindow,
                    expectedBytesPerWindow,
                    publications.get(i),
                    i == 1 ? movementCommand : null,
                    i == 1 ? commandSink : null,
                    streamIdentity,
                    socketAddress,
                    serverPid,
                    i,
                    sessionState,
                    resumeClaimParameters);
            results.add(result);
        }

        List<ProcessDirectReceipt> windowReceipts = List.of(
                results.get(0).processReceipt(),
                results.get(1).processReceipt());
        return new ReconnectResumeCommandedProcessDirectReceipt(
                windowReceipts,
                List.of(results.get(0).sessionWindowCursor(), results.get(1).sessionWindowCursor()),
                results.get(1).resumeClaim(),
                results.get(1).receivedMovementCommand(),
                results.get(1).movementCommandResult());
    }

    private static MissingClaimRejectedReconnectResumeProcessReceipt
            sendMissingClaimRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != 2
                || clientReceiptPaths.size() != 2
                || publications.size() != 2) {
            throw new IllegalArgumentException(
                    "missing-claim reconnect rejection requires exactly two receiver artifacts, receipts, and windows");
        }
        if (expectedFramesPerWindow < 0) {
            throw new IllegalArgumentException(
                    "expectedFramesPerWindow must be >= 0; got " + expectedFramesPerWindow);
        }

        long expectedBytesPerWindow = Math.multiplyExact(
                (long) expectedFramesPerWindow,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        long serverPid = ProcessHandle.current().pid();
        ServerReconnectSessionState sessionState =
                ServerReconnectSessionState.create(expectedFramesPerWindow, serverPid);
        SessionWindowProcessResult acceptedWindow = SessionWindowProcessRunner.runSessionWindowProcessClient(
                receiverArtifactPaths.get(0),
                clientReceiptPaths.get(0),
                expectedFramesPerWindow,
                expectedBytesPerWindow,
                publications.get(0),
                null,
                null,
                streamIdentity,
                socketAddress,
                serverPid,
                0,
                sessionState,
                null);
        try {
            SessionWindowProcessRunner.runSessionWindowProcessClient(
                    receiverArtifactPaths.get(1),
                    clientReceiptPaths.get(1),
                    expectedFramesPerWindow,
                    expectedBytesPerWindow,
                    publications.get(1),
                    movementCommand,
                    commandSink,
                    streamIdentity,
                    socketAddress,
                    serverPid,
                    1,
                    sessionState,
                    null);
        } catch (IOException rejection) {
            if (!ServerReconnectSessionState.isMovementCommandRejection(rejection)) {
                throw rejection;
            }
            return new MissingClaimRejectedReconnectResumeProcessReceipt(
                    acceptedWindow.processReceipt(),
                    acceptedWindow.sessionWindowCursor(),
                    movementCommand,
                    null,
                    rejection.getMessage());
        }
        throw new IOException("server-owned reconnect session state accepted a missing-claim movement command");
    }

    private static RejectedReconnectResumeCommandedProcessDirectReceipt
            sendRejectedReconnectResumeSessionToProcessWithClientMovementCommand(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != 2
                || clientReceiptPaths.size() != 2
                || publications.size() != 2) {
            throw new IllegalArgumentException(
                    "reconnect resume rejection currently requires exactly two receiver artifacts, receipts, and windows");
        }
        if (expectedFramesPerWindow < 0) {
            throw new IllegalArgumentException(
                    "expectedFramesPerWindow must be >= 0; got " + expectedFramesPerWindow);
        }

        long expectedBytesPerWindow = Math.multiplyExact(
                (long) expectedFramesPerWindow,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        long serverPid = ProcessHandle.current().pid();
        ServerReconnectSessionState sessionState =
                ServerReconnectSessionState.create(expectedFramesPerWindow, serverPid);
        SessionWindowProcessResult acceptedWindow = SessionWindowProcessRunner.runSessionWindowProcessClient(
                receiverArtifactPaths.get(0),
                clientReceiptPaths.get(0),
                expectedFramesPerWindow,
                expectedBytesPerWindow,
                publications.get(0),
                null,
                null,
                streamIdentity,
                socketAddress,
                serverPid,
                0,
                sessionState,
                null);
        HeadlessSnapshotSocketClient.SessionResumeClaimParameters rejectedClaimParameters =
                rejectedResumeClaimParameters(sessionState.latestCursorOrNull());
        try {
            SessionWindowProcessRunner.runSessionWindowProcessClient(
                    receiverArtifactPaths.get(1),
                    clientReceiptPaths.get(1),
                    expectedFramesPerWindow,
                    expectedBytesPerWindow,
                    publications.get(1),
                    movementCommand,
                    commandSink,
                    streamIdentity,
                    socketAddress,
                    serverPid,
                    1,
                    sessionState,
                    rejectedClaimParameters);
        } catch (IOException rejection) {
            if (!ServerReconnectSessionState.isResumeClaimRejection(rejection)) {
                throw rejection;
            }
            return new RejectedReconnectResumeCommandedProcessDirectReceipt(
                    acceptedWindow.processReceipt(),
                    acceptedWindow.sessionWindowCursor(),
                    rejectedClaimParameters,
                    movementCommand,
                    null,
                    rejection.getMessage());
        }
        throw new IOException("server-owned reconnect session state accepted a rejected resume claim");
    }

    private static HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParametersOrNull(
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return new HeadlessSnapshotSocketClient.SessionResumeClaimParameters(
                cursor.sessionId(),
                cursor.windowOrdinal(),
                cursor.previousResumeCursor(),
                cursor.nextResumeCursor());
    }

    private static HeadlessSnapshotSocketClient.SessionResumeClaimParameters rejectedResumeClaimParameters(
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        long rejectedSessionId = cursor.sessionId() == Long.MAX_VALUE
                ? cursor.sessionId() - 1L
                : cursor.sessionId() + 1L;
        return new HeadlessSnapshotSocketClient.SessionResumeClaimParameters(
                rejectedSessionId,
                cursor.windowOrdinal(),
                cursor.previousResumeCursor(),
                cursor.nextResumeCursor());
    }

    public static CommandedDirectReceipt sendDirectWithClientMovementCommand(
            Path receiverArtifactPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink) throws IOException {
        return sendDirectWithClientMovementCommand(
                receiverArtifactPath,
                expectedFrames,
                publication,
                movementCommand,
                commandSink,
                frame -> {
                });
    }

    public static CommandedDirectReceipt sendDirectWithClientMovementCommand(
            Path receiverArtifactPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(receiverFrameConsumer, "receiverFrameConsumer");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }

        long expectedBytes = Math.multiplyExact((long) expectedFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        InetAddress loopback = InetAddress.getLoopbackAddress();
        Thread clientThread;
        IOException senderFailure = null;
        IOException clientFailure = null;
        RuntimeException publisherFailure = null;
        RuntimeException clientRuntimeFailure = null;
        long senderBytes = 0L;
        long receiverBytes = 0L;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        List<OfflineCombatStepWireFrame> receiverFrames = List.of();
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        HeadlessSnapshotSocketClient.ClientMovementCommand receivedMovementCommand = null;
        ShipCommandSink.Result movementCommandResult = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        HeadlessSnapshotSocketClient.ServerMovementCommandResult clientMovementCommandResult = null;
        int clientMovementCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ServerInitialSync clientInitialSync = null;
        int clientInitialSyncBytes = 0;
        HeadlessSnapshotSocketClient.ServerStreamCompletion clientStreamCompletion = null;
        int clientStreamCompletionBytes = 0;
        DirectClientRunner clientRunner = null;
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(loopback, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientRunner = new DirectClientRunner(
                    new HeadlessSnapshotSocketClient(),
                    loopback.getHostAddress(),
                    port,
                    receiverArtifactPath,
                    expectedFrames,
                    receiverFrameConsumer,
                    movementCommand);
            clientThread = new Thread(clientRunner, "headless-snapshot-socket-client-commanded");
            clientThread.setDaemon(true);
            clientThread.start();

            try (Socket socket = serverSocket.accept()) {
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
                        new SnapshotClientAdmissionGate(expectedFrames, genericStreamIdentity()).admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                if (!clientAdmission.accepted()) {
                    throw new IOException("headless snapshot socket client admission rejected: "
                            + clientAdmission.reason());
                }
                HeadlessSnapshotSocketClient.writeTransportSessionOpened(
                        socketOutput,
                        HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                                clientHandshake,
                                loopback.getHostAddress(),
                                port));
                clientMovementCommandResult =
                        HeadlessSnapshotSocketClient.movementCommandResult(
                                receivedMovementCommand,
                                movementCommandResult);
                HeadlessSnapshotSocketClient.writeMovementCommandResult(socketOutput, clientMovementCommandResult);
                clientMovementCommandResultBytes = HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_BYTES;
                DirectSocketSink sink = new DirectSocketSink(socketOutput, clientHandshake, expectedFrames);
                try {
                    publication.publishTo(sink);
                    sink.finish();
                    HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(clientHandshake, expectedFrames, sink.frames());
                    clientStreamCompletion =
                            HeadlessSnapshotSocketClient.ServerStreamCompletion.completed(clientHandshake);
                    HeadlessSnapshotSocketClient.writeStreamCompletion(socketOutput, clientStreamCompletion);
                    clientStreamCompletionBytes = HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES;
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
                    clientInitialSyncBytes = clientInitialSync == null
                            ? 0
                            : HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES;
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
            }

            joinReceiver(clientThread);
            if (clientRunner != null) {
                clientFailure = clientRunner.failure();
                clientRuntimeFailure = clientRunner.runtimeFailure();
                HeadlessSnapshotSocketClient.Receipt clientReceipt = clientRunner.receipt();
                if (clientReceipt != null) {
                    receiverBytes = clientReceipt.bytesReceived();
                    receiverFrames = clientReceipt.frames();
                    clientHandshake = clientReceipt.handshake();
                    clientAdmissionAck = clientReceipt.admissionAck();
                    clientAdmissionAckBytes = clientReceipt.admissionAckBytes();
                    clientMovementCommandResult = clientReceipt.movementCommandResult();
                    clientMovementCommandResultBytes = clientReceipt.movementCommandResultBytes();
                    clientInitialSync = clientReceipt.initialSync();
                    clientInitialSyncBytes = clientReceipt.initialSyncBytes();
                    clientStreamCompletion = clientReceipt.streamCompletion();
                    clientStreamCompletionBytes = clientReceipt.streamCompletionBytes();
                    clientAdmission = requireClientAdmission(clientHandshake, expectedFrames, genericStreamIdentity());
                }
            }
        }

        if (senderFailure != null) {
            throw senderFailure;
        }
        if (publisherFailure != null) {
            throw publisherFailure;
        }
        if (clientFailure != null) {
            throw clientFailure;
        }
        if (clientRuntimeFailure != null) {
            throw clientRuntimeFailure;
        }
        if (clientHandshake == null) {
            throw new IOException("headless snapshot socket client did not complete handshake");
        }
        if (receivedMovementCommand == null) {
            throw new IOException("headless snapshot socket client movement command was not received");
        }
        if (movementCommandResult == null) {
            throw new IOException("headless snapshot socket client movement command was not consumed");
        }
        if (clientAdmission == null || !clientAdmission.accepted()) {
            throw new IOException("headless snapshot socket client was not admitted");
        }
        if (senderBytes != expectedBytes) {
            throw new IOException("offline step commanded direct wire socket sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        long receiverArtifactBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("offline step commanded direct wire socket receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        if (receiverArtifactBytes != expectedBytes) {
            throw new IOException("offline step commanded direct wire socket receiver artifact byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverArtifactBytes);
        }
        if (receiverFrames.size() != expectedFrames) {
            throw new IOException("offline step commanded direct wire socket receiver frame count mismatch: expected "
                    + expectedFrames + ", got " + receiverFrames.size());
        }
        DirectReceipt directReceipt = new DirectReceipt(
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                senderBytes,
                receiverBytes,
                loopback.getHostAddress(),
                port,
                "socket_input_stream",
                false,
                "cc.starsector.server.client.HeadlessSnapshotSocketClient",
                clientHandshake,
                clientAdmission,
                clientAdmissionAckBytes,
                clientAdmissionAck,
                clientMovementCommandResultBytes,
                clientMovementCommandResult,
                0,
                null,
                0,
                null,
                0,
                null,
                clientInitialSyncBytes,
                clientInitialSync,
                clientStreamCompletionBytes,
                clientStreamCompletion,
                senderFrames,
                receiverFrames);
        return new CommandedDirectReceipt(
                directReceipt,
                receivedMovementCommand,
                movementCommandResult);
    }

    public static MultiClientDirectReceipt sendDirectToClients(
            List<Path> receiverArtifactPaths,
            int expectedFrames,
            DirectSocketPublication publication,
            List<Consumer<? super OfflineCombatStepWireFrame>> receiverFrameConsumers) throws IOException {
        Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(receiverFrameConsumers, "receiverFrameConsumers");
        if (receiverArtifactPaths.size() < 2) {
            throw new IllegalArgumentException("multi-client direct socket transport requires at least two clients");
        }
        if (receiverFrameConsumers.size() != receiverArtifactPaths.size()) {
            throw new IllegalArgumentException("receiverFrameConsumers size must match receiverArtifactPaths");
        }
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        requireDistinctReceiverArtifacts(receiverArtifactPaths);

        int clientCount = receiverArtifactPaths.size();
        long expectedBytes = Math.multiplyExact((long) expectedFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        for (Path receiverArtifactPath : receiverArtifactPaths) {
            Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            Path receiverParent = receiverArtifactPath.getParent();
            if (receiverParent != null) {
                Files.createDirectories(receiverParent);
            }
        }
        for (Consumer<? super OfflineCombatStepWireFrame> consumer : receiverFrameConsumers) {
            Objects.requireNonNull(consumer, "receiverFrameConsumer");
        }

        InetAddress loopback = InetAddress.getLoopbackAddress();
        IOException serverFailure = null;
        RuntimeException publisherFailure = null;
        long senderBytesPerClient = 0L;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        List<DirectClientRunner> clientRunners = new ArrayList<>(clientCount);
        List<Thread> clientThreads = new ArrayList<>(clientCount);
        List<Socket> acceptedSockets = new ArrayList<>(clientCount);
        List<OutputStream> socketOutputs = new ArrayList<>(clientCount);
        List<HeadlessSnapshotSocketClient.ClientHandshake> clientHandshakes = new ArrayList<>(clientCount);
        List<SnapshotClientAdmissionGate.Admission> clientAdmissions = new ArrayList<>(clientCount);
        List<HeadlessSnapshotSocketClient.ServerAdmissionAck> clientAdmissionAcks = new ArrayList<>(clientCount);
        List<Integer> clientAdmissionAckBytes = new ArrayList<>(clientCount);
        List<HeadlessSnapshotSocketClient.ServerStreamCompletion> clientStreamCompletions =
                new ArrayList<>(clientCount);
        List<Integer> clientStreamCompletionBytes = new ArrayList<>(clientCount);
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(loopback, 0), Math.max(BACKLOG, clientCount));
            port = serverSocket.getLocalPort();
            for (int i = 0; i < clientCount; i++) {
                DirectClientRunner clientRunner = new DirectClientRunner(
                        new HeadlessSnapshotSocketClient(),
                        loopback.getHostAddress(),
                        port,
                        receiverArtifactPaths.get(i),
                        expectedFrames,
                        receiverFrameConsumers.get(i));
                Thread clientThread = new Thread(clientRunner, "headless-snapshot-socket-client-" + i);
                clientThread.setDaemon(true);
                clientRunners.add(clientRunner);
                clientThreads.add(clientThread);
                clientThread.start();
            }

            try {
                SnapshotClientAdmissionGate admissionGate =
                        new SnapshotClientAdmissionGate(expectedFrames, genericStreamIdentity());
                for (int i = 0; i < clientCount; i++) {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                    acceptedSockets.add(socket);
                    HeadlessSnapshotSocketClient.ClientHandshake clientHandshake =
                            HeadlessSnapshotSocketClient.readHandshake(socket.getInputStream());
                    OutputStream socketOutput = socket.getOutputStream();
                    SnapshotClientAdmissionGate.Admission clientAdmission = admissionGate.admit(clientHandshake);
                    HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck =
                            HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                    HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                    clientHandshakes.add(clientHandshake);
                    clientAdmissions.add(clientAdmission);
                    clientAdmissionAcks.add(clientAdmissionAck);
                    clientAdmissionAckBytes.add(HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES);
                    socketOutputs.add(socketOutput);
                    if (!clientAdmission.accepted()) {
                        throw new IOException("headless snapshot socket client " + i + " admission rejected: "
                                + clientAdmission.reason());
                    }
                    HeadlessSnapshotSocketClient.writeTransportSessionOpened(
                            socketOutput,
                            HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                                    clientHandshake,
                                    loopback.getHostAddress(),
                                    port));
                }

                FanoutDirectSocketSink sink =
                        new FanoutDirectSocketSink(socketOutputs, clientHandshakes, expectedFrames);
                try {
                    publication.publishTo(sink);
                    sink.finish();
                    senderBytesPerClient = sink.bytesWrittenPerClient();
                    senderFrames = sink.frames();
                    for (int i = 0; i < clientCount; i++) {
                        HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(clientHandshakes.get(i), expectedFrames, senderFrames);
                        HeadlessSnapshotSocketClient.ServerStreamCompletion clientStreamCompletion =
                                HeadlessSnapshotSocketClient.ServerStreamCompletion.completed(clientHandshakes.get(i));
                        HeadlessSnapshotSocketClient.writeStreamCompletion(
                                socketOutputs.get(i),
                                clientStreamCompletion);
                        clientStreamCompletions.add(clientStreamCompletion);
                        clientStreamCompletionBytes.add(HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES);
                    }
                } catch (UncheckedIOException uioe) {
                    serverFailure = uioe.getCause();
                } catch (IOException ioe) {
                    serverFailure = ioe;
                } catch (RuntimeException rte) {
                    publisherFailure = rte;
                } finally {
                    senderBytesPerClient = sink.bytesWrittenPerClient();
                    senderFrames = sink.frames();
                    shutdownAcceptedSocketOutputs(acceptedSockets, serverFailure, publisherFailure);
                }
            } catch (IOException ioe) {
                serverFailure = ioe;
            } finally {
                closeAcceptedSockets(acceptedSockets, serverFailure);
            }

            for (Thread clientThread : clientThreads) {
                joinReceiver(clientThread);
            }
        }

        if (serverFailure != null) {
            throw serverFailure;
        }
        if (publisherFailure != null) {
            throw publisherFailure;
        }

        List<DirectClientReceipt> clientReceipts = new ArrayList<>(clientCount);
        for (int i = 0; i < clientCount; i++) {
            DirectClientRunner clientRunner = clientRunners.get(i);
            if (clientRunner.failure() != null) {
                throw clientRunner.failure();
            }
            if (clientRunner.runtimeFailure() != null) {
                throw clientRunner.runtimeFailure();
            }
            HeadlessSnapshotSocketClient.Receipt clientReceipt = clientRunner.receipt();
            if (clientReceipt == null) {
                throw new IOException("headless snapshot socket client " + i + " did not produce a receipt");
            }
            long receiverArtifactBytes = Files.size(receiverArtifactPaths.get(i));
            if (receiverArtifactBytes != expectedBytes) {
                throw new IOException("multi-client receiver artifact " + i + " byte count mismatch: expected "
                        + expectedBytes + ", got " + receiverArtifactBytes);
            }
            List<OfflineCombatStepWireFrame> artifactFrames =
                    OfflineCombatStepWireCodec.readFrames(receiverArtifactPaths.get(i));
            if (!artifactFrames.equals(clientReceipt.frames())) {
                throw new IOException("multi-client receiver artifact " + i + " does not match socket frames");
            }
            if (i >= clientAdmissions.size()) {
                throw new IOException("headless snapshot socket client " + i + " did not complete admission");
            }
            if (i >= clientStreamCompletions.size()) {
                throw new IOException("headless snapshot socket client " + i + " did not receive stream completion");
            }
                    clientReceipts.add(new DirectClientReceipt(
                    i,
                    clientReceipt.receiverArtifactPath(),
                    expectedFrames,
                    expectedBytes,
                    clientReceipt.bytesReceived(),
                    clientReceipt.decodeSource(),
                    clientReceipt.artifactUsedAsDecodeSource(),
                    "cc.starsector.server.client.HeadlessSnapshotSocketClient",
                    clientReceipt.handshake(),
                    clientAdmissions.get(i),
                    clientAdmissionAckBytes.get(i),
                    clientReceipt.admissionAck(),
                    clientReceipt.initialSyncBytes(),
                    clientReceipt.initialSync(),
                    clientStreamCompletionBytes.get(i),
                    clientReceipt.streamCompletion(),
                    clientReceipt.frames()));
        }

        return new MultiClientDirectReceipt(
                expectedFrames,
                expectedBytes,
                senderBytesPerClient,
                Math.multiplyExact(senderBytesPerClient, (long) clientCount),
                loopback.getHostAddress(),
                port,
                "socket_input_stream",
                false,
                "cc.starsector.server.client.HeadlessSnapshotSocketClient",
                clientReceipts,
                senderFrames);
    }

    public static MixedAdmissionDirectReceipt sendDirectWithRejectedClient(
            Path acceptedReceiverArtifactPath,
            int expectedFrames,
            int rejectedExpectedFrames,
            DirectSocketPublication publication,
            Consumer<? super OfflineCombatStepWireFrame> acceptedReceiverFrameConsumer) throws IOException {
        Objects.requireNonNull(acceptedReceiverArtifactPath, "acceptedReceiverArtifactPath");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(acceptedReceiverFrameConsumer, "acceptedReceiverFrameConsumer");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        if (rejectedExpectedFrames < 0) {
            throw new IllegalArgumentException("rejectedExpectedFrames must be >= 0; got " + rejectedExpectedFrames);
        }
        if (rejectedExpectedFrames == expectedFrames) {
            throw new IllegalArgumentException("rejectedExpectedFrames must differ from expectedFrames");
        }

        long expectedBytes = Math.multiplyExact((long) expectedFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        Path receiverParent = acceptedReceiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        InetAddress loopback = InetAddress.getLoopbackAddress();
        DirectClientRunner acceptedRunner;
        AdmissionClientRunner rejectedRunner;
        Thread acceptedThread;
        Thread rejectedThread;
        IOException serverFailure = null;
        RuntimeException publisherFailure = null;
        long senderBytes = 0L;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        HeadlessSnapshotSocketClient.ClientHandshake acceptedHandshake = null;
        SnapshotClientAdmissionGate.Admission acceptedAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck acceptedAdmissionAck = null;
        int acceptedAdmissionAckBytes = 0;
        HeadlessSnapshotSocketClient.ServerInitialSync acceptedInitialSync = null;
        int acceptedInitialSyncBytes = 0;
        HeadlessSnapshotSocketClient.ServerStreamCompletion acceptedStreamCompletion = null;
        int acceptedStreamCompletionBytes = 0;
        HeadlessSnapshotSocketClient.ClientHandshake rejectedHandshake = null;
        SnapshotClientAdmissionGate.Admission rejectedAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck rejectedAdmissionAck = null;
        int rejectedAdmissionAckBytes = 0;
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(loopback, 0), 2);
            port = serverSocket.getLocalPort();
            acceptedRunner = new DirectClientRunner(
                    new HeadlessSnapshotSocketClient(),
                    loopback.getHostAddress(),
                    port,
                    acceptedReceiverArtifactPath,
                    expectedFrames,
                    acceptedReceiverFrameConsumer);
            rejectedRunner = new AdmissionClientRunner(
                    new HeadlessSnapshotSocketClient(),
                    loopback.getHostAddress(),
                    port,
                    rejectedExpectedFrames);
            acceptedThread = new Thread(acceptedRunner, "headless-snapshot-socket-client-accepted");
            rejectedThread = new Thread(rejectedRunner, "headless-snapshot-socket-client-rejected");
            acceptedThread.setDaemon(true);
            rejectedThread.setDaemon(true);
            acceptedThread.start();
            rejectedThread.start();

            Socket acceptedSocket = null;
            OutputStream acceptedOutput = null;
            try {
                SnapshotClientAdmissionGate admissionGate =
                        new SnapshotClientAdmissionGate(expectedFrames, genericStreamIdentity());
                for (int i = 0; i < 2; i++) {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                    HeadlessSnapshotSocketClient.ClientHandshake handshake =
                            HeadlessSnapshotSocketClient.readHandshake(socket.getInputStream());
                    OutputStream socketOutput = socket.getOutputStream();
                    SnapshotClientAdmissionGate.Admission admission = admissionGate.admit(handshake);
                    HeadlessSnapshotSocketClient.ServerAdmissionAck admissionAck =
                            HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(admission);
                    HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, admissionAck);
                    if (admission.accepted()) {
                        if (acceptedSocket != null) {
                            socket.close();
                            throw new IOException("mixed admission transport received multiple accepted clients");
                        }
                        acceptedSocket = socket;
                        acceptedOutput = socketOutput;
                        acceptedHandshake = handshake;
                        acceptedAdmission = admission;
                        acceptedAdmissionAck = admissionAck;
                        acceptedAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                        HeadlessSnapshotSocketClient.writeTransportSessionOpened(
                                acceptedOutput,
                                HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                                        acceptedHandshake,
                                        loopback.getHostAddress(),
                                        port));
                    } else {
                        if (rejectedAdmission != null) {
                            socket.close();
                            throw new IOException("mixed admission transport received multiple rejected clients");
                        }
                        rejectedHandshake = handshake;
                        rejectedAdmission = admission;
                        rejectedAdmissionAck = admissionAck;
                        rejectedAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                        socket.shutdownOutput();
                        socket.close();
                    }
                }
                if (acceptedSocket == null || acceptedOutput == null || acceptedAdmission == null) {
                    throw new IOException("mixed admission transport did not admit an accepted client");
                }
                if (rejectedAdmission == null || rejectedAdmission.accepted()) {
                    throw new IOException("mixed admission transport did not reject a client");
                }

                DirectSocketSink sink = new DirectSocketSink(acceptedOutput, acceptedHandshake, expectedFrames);
                try {
                    publication.publishTo(sink);
                    sink.finish();
                    HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(acceptedHandshake, expectedFrames, sink.frames());
                    acceptedStreamCompletion =
                            HeadlessSnapshotSocketClient.ServerStreamCompletion.completed(acceptedHandshake);
                    HeadlessSnapshotSocketClient.writeStreamCompletion(acceptedOutput, acceptedStreamCompletion);
                    acceptedStreamCompletionBytes = HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES;
                } catch (UncheckedIOException uioe) {
                    serverFailure = uioe.getCause();
                } catch (IOException ioe) {
                    serverFailure = ioe;
                } catch (RuntimeException rte) {
                    publisherFailure = rte;
                } finally {
                    senderBytes = sink.bytesWritten();
                    senderFrames = sink.frames();
                    acceptedInitialSync = sink.initialSync();
                    acceptedInitialSyncBytes = acceptedInitialSync == null
                            ? 0
                            : HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES;
                    try {
                        acceptedSocket.shutdownOutput();
                    } catch (IOException shutdownFailure) {
                        if (serverFailure != null) {
                            serverFailure.addSuppressed(shutdownFailure);
                        } else if (publisherFailure != null) {
                            publisherFailure.addSuppressed(shutdownFailure);
                        } else {
                            serverFailure = shutdownFailure;
                        }
                    }
                    acceptedSocket.close();
                }
            } catch (IOException ioe) {
                serverFailure = ioe;
            }

            joinReceiver(acceptedThread);
            joinReceiver(rejectedThread);
        }

        if (serverFailure != null) {
            throw serverFailure;
        }
        if (publisherFailure != null) {
            throw publisherFailure;
        }
        if (acceptedRunner.failure() != null) {
            throw acceptedRunner.failure();
        }
        if (acceptedRunner.runtimeFailure() != null) {
            throw acceptedRunner.runtimeFailure();
        }
        if (rejectedRunner.failure() != null) {
            throw rejectedRunner.failure();
        }
        if (rejectedRunner.runtimeFailure() != null) {
            throw rejectedRunner.runtimeFailure();
        }
        HeadlessSnapshotSocketClient.Receipt acceptedClientReceipt = acceptedRunner.receipt();
        if (acceptedClientReceipt == null) {
            throw new IOException("mixed admission accepted client did not produce a receipt");
        }
        HeadlessSnapshotSocketClient.AdmissionReceipt rejectedClientReceipt = rejectedRunner.receipt();
        if (rejectedClientReceipt == null) {
            throw new IOException("mixed admission rejected client did not produce an admission receipt");
        }
        long receiverArtifactBytes = Files.size(acceptedReceiverArtifactPath);
        if (receiverArtifactBytes != expectedBytes) {
            throw new IOException("mixed admission accepted receiver artifact byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverArtifactBytes);
        }
        List<OfflineCombatStepWireFrame> artifactFrames =
                OfflineCombatStepWireCodec.readFrames(acceptedReceiverArtifactPath);
        if (!artifactFrames.equals(acceptedClientReceipt.frames())) {
            throw new IOException("mixed admission accepted receiver artifact does not match socket frames");
        }

        DirectClientReceipt acceptedReceipt = new DirectClientReceipt(
                0,
                acceptedClientReceipt.receiverArtifactPath(),
                expectedFrames,
                expectedBytes,
                acceptedClientReceipt.bytesReceived(),
                acceptedClientReceipt.decodeSource(),
                acceptedClientReceipt.artifactUsedAsDecodeSource(),
                "cc.starsector.server.client.HeadlessSnapshotSocketClient",
                acceptedClientReceipt.handshake(),
                acceptedAdmission,
                acceptedAdmissionAckBytes,
                acceptedClientReceipt.admissionAck(),
                acceptedClientReceipt.initialSyncBytes(),
                acceptedClientReceipt.initialSync(),
                acceptedStreamCompletionBytes,
                acceptedClientReceipt.streamCompletion(),
                acceptedClientReceipt.frames());
        RejectedClientReceipt rejectedReceipt = new RejectedClientReceipt(
                1,
                rejectedExpectedFrames,
                expectedFrames,
                rejectedHandshake,
                rejectedAdmission,
                rejectedAdmissionAckBytes,
                rejectedAdmissionAck,
                rejectedClientReceipt);

        return new MixedAdmissionDirectReceipt(
                expectedFrames,
                expectedBytes,
                senderBytes,
                loopback.getHostAddress(),
                port,
                acceptedReceipt,
                rejectedReceipt,
                senderFrames);
    }

    private static Path siblingWithSuffix(Path path, String suffix) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("path must include a file name: " + path);
        }
        Path sibling = Path.of(fileName.toString() + suffix);
        Path parent = path.getParent();
        return parent == null ? sibling : parent.resolve(sibling);
    }

    private static InetAddress requireNonLoopbackIpv4Address() throws IOException {
        InetAddress fallback = null;
        java.util.Enumeration<NetworkInterface> interfaces;
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
            java.util.Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address)
                        || address.isAnyLocalAddress()
                        || address.isLoopbackAddress()) {
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

    private static void requireNonLoopbackSocketHost(String socketHost) throws IOException {
        Objects.requireNonNull(socketHost, "socketHost");
        InetAddress address = InetAddress.getByName(socketHost);
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            throw new IOException("off-loopback transport resolved to loopback host: " + socketHost);
        }
    }

    private static SnapshotClientAdmissionGate.Admission requireClientAdmission(
            HeadlessSnapshotSocketClient.ClientHandshake handshake,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        SnapshotClientAdmissionGate.Admission admission =
                new SnapshotClientAdmissionGate(expectedFrames, streamIdentity).admit(handshake);
        if (!admission.accepted()) {
            throw new IOException("headless snapshot socket client admission rejected: "
                    + admission.reason());
        }
        return admission;
    }

    private static void requireDistinctReceiverArtifacts(List<Path> receiverArtifactPaths) {
        for (int i = 0; i < receiverArtifactPaths.size(); i++) {
            Path left = Objects.requireNonNull(receiverArtifactPaths.get(i), "receiverArtifactPath")
                    .toAbsolutePath()
                    .normalize();
            for (int j = i + 1; j < receiverArtifactPaths.size(); j++) {
                Path right = Objects.requireNonNull(receiverArtifactPaths.get(j), "receiverArtifactPath")
                        .toAbsolutePath()
                        .normalize();
                if (left.equals(right)) {
                    throw new IllegalArgumentException("multi-client receiver artifact paths must be distinct");
                }
            }
        }
    }

    private static void shutdownAcceptedSocketOutputs(
            List<Socket> acceptedSockets,
            IOException serverFailure,
            RuntimeException publisherFailure) throws IOException {
        IOException shutdownFailure = null;
        for (Socket socket : acceptedSockets) {
            try {
                socket.shutdownOutput();
            } catch (IOException ioe) {
                if (shutdownFailure == null) {
                    shutdownFailure = ioe;
                } else {
                    shutdownFailure.addSuppressed(ioe);
                }
            }
        }
        if (shutdownFailure != null) {
            if (serverFailure != null) {
                serverFailure.addSuppressed(shutdownFailure);
            } else if (publisherFailure != null) {
                publisherFailure.addSuppressed(shutdownFailure);
            } else {
                throw shutdownFailure;
            }
        }
    }

    private static void closeAcceptedSockets(List<Socket> acceptedSockets, IOException serverFailure)
            throws IOException {
        IOException closeFailure = null;
        for (Socket socket : acceptedSockets) {
            try {
                socket.close();
            } catch (IOException ioe) {
                if (closeFailure == null) {
                    closeFailure = ioe;
                } else {
                    closeFailure.addSuppressed(ioe);
                }
            }
        }
        if (closeFailure != null) {
            if (serverFailure != null) {
                serverFailure.addSuppressed(closeFailure);
            } else {
                throw closeFailure;
            }
        }
    }

    private static long copyBounded(InputStream input, OutputStream output, long expectedBytes)
            throws IOException {
        byte[] buffer = new byte[OfflineCombatStepWireCodec.FRAME_BYTES];
        long total = 0L;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            total += read;
            if (total > expectedBytes) {
                throw new IOException("offline step wire socket byte bound exceeded");
            }
            output.write(buffer, 0, read);
        }
        output.flush();
        if (total != expectedBytes) {
            throw new IOException("offline step wire socket byte count mismatch: expected "
                    + expectedBytes + ", got " + total);
        }
        return total;
    }

    private static void joinReceiver(Thread receiverThread) throws IOException {
        try {
            receiverThread.join(RECEIVER_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for offline step wire socket receiver", interrupted);
        }
        if (receiverThread.isAlive()) {
            receiverThread.interrupt();
            throw new IOException("timed out waiting for offline step wire socket receiver");
        }
    }

    public record Receipt(
            Path senderArtifactPath,
            Path receiverArtifactPath,
            int expectedFrames,
            long expectedBytes,
            long senderBytes,
            long receiverBytes,
            String socketHost,
            int socketPort,
            List<OfflineCombatStepWireFrame> receiverFrames) {

        public Receipt {
            senderArtifactPath = Objects.requireNonNull(senderArtifactPath, "senderArtifactPath");
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            receiverFrames = List.copyOf(Objects.requireNonNull(receiverFrames, "receiverFrames"));
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (expectedBytes < 0L) {
                throw new IllegalArgumentException("expectedBytes must be >= 0; got " + expectedBytes);
            }
            if (senderBytes != expectedBytes) {
                throw new IllegalArgumentException("senderBytes must equal expectedBytes");
            }
            if (receiverBytes != expectedBytes) {
                throw new IllegalArgumentException("receiverBytes must equal expectedBytes");
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must not be blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (receiverFrames.size() != expectedFrames) {
                throw new IllegalArgumentException("receiver frame count must equal expectedFrames");
            }
        }
    }

    public record CommandedDirectReceipt(
            DirectReceipt directReceipt,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult) {

        public CommandedDirectReceipt {
            directReceipt = Objects.requireNonNull(directReceipt, "directReceipt");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
            if (movementCommand.messageTypeCode()
                    != HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_MESSAGE_TYPE_CODE) {
                throw new IllegalArgumentException("movement command message type mismatch");
            }
            if (movementCommand.schemaVersion()
                    != Short.toUnsignedInt(HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_SCHEMA_VERSION)) {
                throw new IllegalArgumentException("movement command schema mismatch");
            }
            HeadlessSnapshotSocketClient.ServerMovementCommandResult result =
                    directReceipt.receiverClientMovementCommandResult();
            if (result == null) {
                throw new IllegalArgumentException("commanded receipt requires a server movement command result");
            }
            if (result.shipId() != movementCommand.shipId()
                    || result.simTick() != movementCommand.simTick()
                    || result.movementIntent() != movementCommand.movementIntent()
                    || result.commandResult() != commandResult) {
                throw new IllegalArgumentException("server movement command result must match commanded receipt");
            }
        }
    }

    public record DirectReceipt(
            Path receiverArtifactPath,
            int expectedFrames,
            long expectedBytes,
            long senderBytes,
            long receiverBytes,
            String socketHost,
            int socketPort,
            String receiverDecodeSource,
            boolean receiverArtifactUsedAsDecodeSource,
            String receiverClientContract,
            HeadlessSnapshotSocketClient.ClientHandshake receiverClientHandshake,
            SnapshotClientAdmissionGate.Admission receiverClientAdmission,
            int receiverClientAdmissionAckBytes,
            HeadlessSnapshotSocketClient.ServerAdmissionAck receiverClientAdmissionAck,
            int receiverClientMovementCommandResultBytes,
            HeadlessSnapshotSocketClient.ServerMovementCommandResult receiverClientMovementCommandResult,
            int receiverClientWeaponGroupCommandResultBytes,
            HeadlessSnapshotSocketClient.ServerWeaponGroupCommandResult receiverClientWeaponGroupCommandResult,
            int receiverClientFireCommandResultBytes,
            HeadlessSnapshotSocketClient.ServerFireCommandResult receiverClientFireCommandResult,
            int receiverClientSystemCommandResultBytes,
            HeadlessSnapshotSocketClient.ServerSystemCommandResult receiverClientSystemCommandResult,
            int receiverClientInitialSyncBytes,
            HeadlessSnapshotSocketClient.ServerInitialSync receiverClientInitialSync,
            int receiverClientStreamCompletionBytes,
            HeadlessSnapshotSocketClient.ServerStreamCompletion receiverClientStreamCompletion,
            List<OfflineCombatStepWireFrame> senderFrames,
            List<OfflineCombatStepWireFrame> receiverFrames) {

        public DirectReceipt {
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            receiverDecodeSource = Objects.requireNonNull(receiverDecodeSource, "receiverDecodeSource");
            receiverClientContract = Objects.requireNonNull(receiverClientContract, "receiverClientContract");
            receiverClientHandshake = Objects.requireNonNull(receiverClientHandshake, "receiverClientHandshake");
            receiverClientAdmission = Objects.requireNonNull(receiverClientAdmission, "receiverClientAdmission");
            receiverClientAdmissionAck =
                    Objects.requireNonNull(receiverClientAdmissionAck, "receiverClientAdmissionAck");
            receiverClientInitialSync =
                    Objects.requireNonNull(receiverClientInitialSync, "receiverClientInitialSync");
            receiverClientStreamCompletion =
                    Objects.requireNonNull(receiverClientStreamCompletion, "receiverClientStreamCompletion");
            senderFrames = List.copyOf(Objects.requireNonNull(senderFrames, "senderFrames"));
            receiverFrames = List.copyOf(Objects.requireNonNull(receiverFrames, "receiverFrames"));
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (expectedBytes < 0L) {
                throw new IllegalArgumentException("expectedBytes must be >= 0; got " + expectedBytes);
            }
            if (senderBytes != expectedBytes) {
                throw new IllegalArgumentException("senderBytes must equal expectedBytes");
            }
            if (receiverBytes != expectedBytes) {
                throw new IllegalArgumentException("receiverBytes must equal expectedBytes");
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must not be blank");
            }
            if (receiverDecodeSource.isBlank()) {
                throw new IllegalArgumentException("receiverDecodeSource must not be blank");
            }
            if (receiverClientContract.isBlank()) {
                throw new IllegalArgumentException("receiverClientContract must not be blank");
            }
            if (receiverArtifactUsedAsDecodeSource) {
                throw new IllegalArgumentException("direct receipt must decode from socket input");
            }
            if (receiverClientHandshake.expectedFrames() != expectedFrames) {
                throw new IllegalArgumentException("receiver client handshake expectedFrames must match receipt");
            }
            if (receiverClientHandshake.admissionRequestMessageTypeCode()
                            != HeadlessSnapshotSocketClient.ADMISSION_REQUEST_MESSAGE_TYPE_CODE
                    || receiverClientHandshake.admissionRequestSchemaVersion()
                            != Short.toUnsignedInt(HeadlessSnapshotSocketClient.ADMISSION_REQUEST_SCHEMA_VERSION)
                    || !SnapshotAdmissionRequestMessage.MESSAGE_ID
                            .equals(receiverClientHandshake.admissionRequestMessageId())) {
                throw new IllegalArgumentException(
                        "receiver client handshake must match registered admission request identity");
            }
            if (receiverClientHandshake.wireTypeCode() != OfflineCombatStepWireCodec.MESSAGE_TYPE_CODE
                    || receiverClientHandshake.schemaVersion() != Short.toUnsignedInt(OfflineCombatStepWireCodec.SCHEMA_VERSION)
                    || !OfflineCombatStepWireCodec.MESSAGE_ID.equals(receiverClientHandshake.messageId())) {
                throw new IllegalArgumentException("receiver client handshake must match registered snapshot wire identity");
            }
            if (!receiverClientAdmission.accepted()
                    || receiverClientAdmission.requiredFrames() != expectedFrames
                    || receiverClientAdmission.expectedFrames() != expectedFrames
                    || receiverClientAdmission.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientAdmission.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientAdmission.messageId().equals(receiverClientHandshake.messageId())
                    || !receiverClientAdmission.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientAdmission.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientAdmission.streamVersion() != receiverClientHandshake.streamVersion()
                    || !receiverClientAdmission.offeredCapabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())
                    || !receiverClientAdmission.requiredCapabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("receiver client admission must match accepted handshake");
            }
            if (receiverClientAdmissionAckBytes != HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES) {
                throw new IllegalArgumentException("receiver client admission ack byte count mismatch");
            }
            if (!receiverClientAdmissionAck.accepted()
                    || receiverClientAdmissionAck.ackMessageTypeCode()
                            != HeadlessSnapshotSocketClient.ADMISSION_ACK_MESSAGE_TYPE_CODE
                    || receiverClientAdmissionAck.ackSchemaVersion()
                            != Short.toUnsignedInt(HeadlessSnapshotSocketClient.ADMISSION_ACK_SCHEMA_VERSION)
                    || !receiverClientAdmissionAck.messageId().equals(receiverClientHandshake.messageId())
                    || receiverClientAdmissionAck.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientAdmissionAck.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientAdmissionAck.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientAdmissionAck.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientAdmissionAck.streamVersion() != receiverClientHandshake.streamVersion()
                    || receiverClientAdmissionAck.expectedFrames() != expectedFrames
                    || !receiverClientAdmissionAck.capabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("receiver client admission ack must match accepted handshake");
            }
            if (receiverClientMovementCommandResult == null) {
                if (receiverClientMovementCommandResultBytes != 0) {
                    throw new IllegalArgumentException(
                            "receiver client movement command result byte count must be zero when absent");
                }
            } else {
                if (receiverClientMovementCommandResultBytes
                        != HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_BYTES) {
                    throw new IllegalArgumentException(
                            "receiver client movement command result byte count mismatch");
                }
                if (receiverClientMovementCommandResult.messageTypeCode()
                                != HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_MESSAGE_TYPE_CODE
                        || receiverClientMovementCommandResult.schemaVersion()
                                != Short.toUnsignedInt(
                                        HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_SCHEMA_VERSION)) {
                    throw new IllegalArgumentException(
                            "receiver client movement command result must match registered identity");
                }
            }
            if (receiverClientWeaponGroupCommandResult == null) {
                if (receiverClientWeaponGroupCommandResultBytes != 0) {
                    throw new IllegalArgumentException(
                            "receiver client weapon-group command result byte count must be zero when absent");
                }
            } else {
                if (receiverClientWeaponGroupCommandResultBytes
                        != HeadlessSnapshotSocketClient.WEAPON_GROUP_COMMAND_RESULT_BYTES) {
                    throw new IllegalArgumentException(
                            "receiver client weapon-group command result byte count mismatch");
                }
                if (receiverClientWeaponGroupCommandResult.messageTypeCode()
                                != HeadlessSnapshotSocketClient.WEAPON_GROUP_COMMAND_RESULT_MESSAGE_TYPE_CODE
                        || receiverClientWeaponGroupCommandResult.schemaVersion()
                                != Short.toUnsignedInt(
                                        HeadlessSnapshotSocketClient.WEAPON_GROUP_COMMAND_RESULT_SCHEMA_VERSION)) {
                    throw new IllegalArgumentException(
                            "receiver client weapon-group command result must match registered identity");
                }
            }
            if (receiverClientFireCommandResult == null) {
                if (receiverClientFireCommandResultBytes != 0) {
                    throw new IllegalArgumentException(
                            "receiver client fire command result byte count must be zero when absent");
                }
            } else {
                if (receiverClientFireCommandResultBytes
                        != HeadlessSnapshotSocketClient.FIRE_COMMAND_RESULT_BYTES) {
                    throw new IllegalArgumentException(
                            "receiver client fire command result byte count mismatch");
                }
                if (receiverClientFireCommandResult.messageTypeCode()
                                != HeadlessSnapshotSocketClient.FIRE_COMMAND_RESULT_MESSAGE_TYPE_CODE
                        || receiverClientFireCommandResult.schemaVersion()
                                != Short.toUnsignedInt(
                                        HeadlessSnapshotSocketClient.FIRE_COMMAND_RESULT_SCHEMA_VERSION)) {
                    throw new IllegalArgumentException(
                            "receiver client fire command result must match registered identity");
                }
            }
            if (receiverClientSystemCommandResult == null) {
                if (receiverClientSystemCommandResultBytes != 0) {
                    throw new IllegalArgumentException(
                            "receiver client system command result byte count must be zero when absent");
                }
            } else {
                if (receiverClientSystemCommandResultBytes
                        != HeadlessSnapshotSocketClient.SYSTEM_COMMAND_RESULT_BYTES) {
                    throw new IllegalArgumentException(
                            "receiver client system command result byte count mismatch");
                }
                if (receiverClientSystemCommandResult.messageTypeCode()
                                != HeadlessSnapshotSocketClient.SYSTEM_COMMAND_RESULT_MESSAGE_TYPE_CODE
                        || receiverClientSystemCommandResult.schemaVersion()
                                != Short.toUnsignedInt(
                                        HeadlessSnapshotSocketClient.SYSTEM_COMMAND_RESULT_SCHEMA_VERSION)) {
                    throw new IllegalArgumentException(
                            "receiver client system command result must match registered identity");
                }
            }
            if (receiverClientInitialSyncBytes != HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES) {
                throw new IllegalArgumentException("receiver client initial sync byte count mismatch");
            }
            if (receiverClientInitialSync.initialSyncMessageTypeCode()
                            != HeadlessSnapshotSocketClient.INITIAL_SYNC_MESSAGE_TYPE_CODE
                    || receiverClientInitialSync.initialSyncSchemaVersion()
                            != Short.toUnsignedInt(HeadlessSnapshotSocketClient.INITIAL_SYNC_SCHEMA_VERSION)
                    || !receiverClientInitialSync.messageId().equals(receiverClientHandshake.messageId())
                    || receiverClientInitialSync.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientInitialSync.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientInitialSync.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientInitialSync.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientInitialSync.streamVersion() != receiverClientHandshake.streamVersion()
                    || !receiverClientInitialSync.capabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("receiver client initial sync must match accepted handshake");
            }
            if (receiverClientStreamCompletionBytes != HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES) {
                throw new IllegalArgumentException("receiver client stream completion byte count mismatch");
            }
            if (receiverClientStreamCompletion.completionMessageTypeCode()
                            != HeadlessSnapshotSocketClient.STREAM_COMPLETED_MESSAGE_TYPE_CODE
                    || receiverClientStreamCompletion.completionSchemaVersion()
                            != Short.toUnsignedInt(HeadlessSnapshotSocketClient.STREAM_COMPLETED_SCHEMA_VERSION)
                    || !receiverClientStreamCompletion.messageId().equals(receiverClientHandshake.messageId())
                    || receiverClientStreamCompletion.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientStreamCompletion.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientStreamCompletion.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientStreamCompletion.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientStreamCompletion.streamVersion() != receiverClientHandshake.streamVersion()
                    || receiverClientStreamCompletion.completedFrames() != expectedFrames
                    || !receiverClientStreamCompletion.capabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("receiver client stream completion must match handshake");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (senderFrames.size() != expectedFrames) {
                throw new IllegalArgumentException("sender frame count must equal expectedFrames");
            }
            if (receiverFrames.size() != expectedFrames) {
                throw new IllegalArgumentException("receiver frame count must equal expectedFrames");
            }
            if (!receiverFrames.isEmpty()) {
                HeadlessSnapshotProcessReceiptValidator.requireInitialSyncMatchesFirstFrame(receiverClientInitialSync, receiverFrames.get(0));
            }
            HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(receiverClientHandshake, expectedFrames, receiverFrames);
        }
    }

    public record ProcessDirectReceipt(
            DirectReceipt directReceipt,
            Path clientReceiptPath,
            Path clientLogPath,
            long serverPid,
            long clientPid,
            int clientExitCode,
            int clientFramesRead,
            long clientLatestCompletedTick,
            long clientLatestStateHash,
            int clientLatestEntityCount,
            int clientLatestShipCount) {

        public ProcessDirectReceipt {
            directReceipt = Objects.requireNonNull(directReceipt, "directReceipt");
            clientReceiptPath = Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
            clientLogPath = Objects.requireNonNull(clientLogPath, "clientLogPath");
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (clientPid <= 0L) {
                throw new IllegalArgumentException("clientPid must be > 0; got " + clientPid);
            }
            if (serverPid == clientPid) {
                throw new IllegalArgumentException("process receipt requires client_pid != server_pid");
            }
            if (clientExitCode != 0) {
                throw new IllegalArgumentException("clientExitCode must be 0; got " + clientExitCode);
            }
            if (clientFramesRead != directReceipt.expectedFrames()) {
                throw new IllegalArgumentException("clientFramesRead must equal expectedFrames");
            }
            OfflineCombatStepWireFrame latestFrame = directReceipt.receiverFrames().isEmpty()
                    ? null
                    : directReceipt.receiverFrames().get(directReceipt.receiverFrames().size() - 1);
            if (latestFrame == null) {
                if (clientLatestCompletedTick != -1L
                        || clientLatestStateHash != 0L
                        || clientLatestEntityCount != -1
                        || clientLatestShipCount != -1) {
                    throw new IllegalArgumentException("empty process receipt latest fields must be empty sentinels");
                }
            } else if (clientLatestCompletedTick != latestFrame.result().completedTick()
                    || clientLatestStateHash != latestFrame.result().stateHash()
                    || clientLatestEntityCount != latestFrame.result().entityCount()
                    || clientLatestShipCount != latestFrame.result().shipCount()) {
                throw new IllegalArgumentException("client latest process receipt fields must match receiver frames");
            }
        }

        public List<OfflineCombatStepWireFrame> senderFrames() {
            return directReceipt.senderFrames();
        }

        public List<OfflineCombatStepWireFrame> receiverFrames() {
            return directReceipt.receiverFrames();
        }
    }

    public record CommandedProcessDirectReceipt(
            ProcessDirectReceipt processReceipt,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            ShipCommandSink.Result weaponGroupCommandResult,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            ShipCommandSink.Result fireCommandResult,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ShipCommandSink.Result systemCommandResult) {

        public CommandedProcessDirectReceipt {
            processReceipt = Objects.requireNonNull(processReceipt, "processReceipt");
            boolean hasMovementCommand = movementCommand != null || commandResult != null;
            boolean hasWeaponGroupCommand = weaponGroupCommand != null || weaponGroupCommandResult != null;
            boolean hasFireCommand = fireCommand != null || fireCommandResult != null;
            boolean hasSystemCommand = systemCommand != null || systemCommandResult != null;
            if ((movementCommand == null) != (commandResult == null)) {
                throw new IllegalArgumentException("movement command and result must be provided together");
            }
            if ((fireCommand == null) != (fireCommandResult == null)) {
                throw new IllegalArgumentException("fire command and result must be provided together");
            }
            if ((weaponGroupCommand == null) != (weaponGroupCommandResult == null)) {
                throw new IllegalArgumentException("weapon-group command and result must be provided together");
            }
            if ((systemCommand == null) != (systemCommandResult == null)) {
                throw new IllegalArgumentException("system command and result must be provided together");
            }
            if (!hasMovementCommand && !hasWeaponGroupCommand && !hasFireCommand && !hasSystemCommand) {
                throw new IllegalArgumentException("commanded process receipt requires at least one command result");
            }
            if (processReceipt.serverPid() == processReceipt.clientPid()) {
                throw new IllegalArgumentException("commanded process receipt requires client_pid != server_pid");
            }
            HeadlessSnapshotSocketClient.ServerMovementCommandResult result =
                    processReceipt.directReceipt().receiverClientMovementCommandResult();
            if (hasMovementCommand && result == null) {
                throw new IllegalArgumentException("commanded process receipt requires a movement command result");
            }
            if (hasMovementCommand
                    && (result.shipId() != movementCommand.shipId()
                    || result.simTick() != movementCommand.simTick()
                    || result.movementIntent() != movementCommand.movementIntent()
                    || result.commandResult() != commandResult)) {
                throw new IllegalArgumentException("process movement command result must match command receipt");
            }
            HeadlessSnapshotSocketClient.ServerWeaponGroupCommandResult weaponGroupResult =
                    processReceipt.directReceipt().receiverClientWeaponGroupCommandResult();
            if (hasWeaponGroupCommand && weaponGroupResult == null) {
                throw new IllegalArgumentException("commanded process receipt requires a weapon-group command result");
            }
            if (hasWeaponGroupCommand
                    && (weaponGroupResult.shipId() != weaponGroupCommand.shipId()
                    || weaponGroupResult.simTick() != weaponGroupCommand.simTick()
                    || weaponGroupResult.group() != weaponGroupCommand.group()
                    || weaponGroupResult.commandResult() != weaponGroupCommandResult)) {
                throw new IllegalArgumentException("process weapon-group command result must match command receipt");
            }
            HeadlessSnapshotSocketClient.ServerFireCommandResult fireResult =
                    processReceipt.directReceipt().receiverClientFireCommandResult();
            if (hasFireCommand && fireResult == null) {
                throw new IllegalArgumentException("commanded process receipt requires a fire command result");
            }
            if (hasFireCommand
                    && (fireResult.shipId() != fireCommand.shipId()
                    || fireResult.simTick() != fireCommand.simTick()
                    || fireResult.fire() != fireCommand.fire()
                    || fireResult.commandResult() != fireCommandResult)) {
                throw new IllegalArgumentException("process fire command result must match command receipt");
            }
            HeadlessSnapshotSocketClient.ServerSystemCommandResult systemResult =
                    processReceipt.directReceipt().receiverClientSystemCommandResult();
            if (hasSystemCommand && systemResult == null) {
                throw new IllegalArgumentException("commanded process receipt requires a system command result");
            }
            if (hasSystemCommand
                    && (systemResult.shipId() != systemCommand.shipId()
                    || systemResult.simTick() != systemCommand.simTick()
                    || systemResult.systemId() != systemCommand.systemId()
                    || systemResult.commandResult() != systemCommandResult)) {
                throw new IllegalArgumentException("process system command result must match command receipt");
            }
        }

        public List<OfflineCombatStepWireFrame> senderFrames() {
            return processReceipt.senderFrames();
        }

        public List<OfflineCombatStepWireFrame> receiverFrames() {
            return processReceipt.receiverFrames();
        }

        public long serverPid() {
            return processReceipt.serverPid();
        }

        public long clientPid() {
            return processReceipt.clientPid();
        }
    }

    public record PersistentCommandedProcessDirectReceipt(
            List<ProcessDirectReceipt> windowReceipts,
            List<HeadlessSnapshotSocketClient.ServerSessionWindowCursor> sessionWindowCursors,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult) {

        public PersistentCommandedProcessDirectReceipt {
            windowReceipts = List.copyOf(Objects.requireNonNull(windowReceipts, "windowReceipts"));
            sessionWindowCursors =
                    List.copyOf(Objects.requireNonNull(sessionWindowCursors, "sessionWindowCursors"));
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
            if (windowReceipts.size() < 2) {
                throw new IllegalArgumentException("persistent commanded receipt requires at least two windows");
            }
            if (sessionWindowCursors.size() != windowReceipts.size()) {
                throw new IllegalArgumentException("persistent cursor count must match window count");
            }
            ProcessDirectReceipt firstWindow = windowReceipts.get(0);
            HeadlessSnapshotSocketClient.ServerMovementCommandResult result =
                    firstWindow.directReceipt().receiverClientMovementCommandResult();
            if (result == null) {
                throw new IllegalArgumentException("persistent commanded receipt requires a movement command result");
            }
            if (result.shipId() != movementCommand.shipId()
                    || result.simTick() != movementCommand.simTick()
                    || result.movementIntent() != movementCommand.movementIntent()
                    || result.commandResult() != commandResult) {
                throw new IllegalArgumentException("persistent command result must match command receipt");
            }
            for (int i = 0; i < windowReceipts.size(); i++) {
                ProcessDirectReceipt window = windowReceipts.get(i);
                if (window.serverPid() != firstWindow.serverPid()
                        || window.clientPid() != firstWindow.clientPid()
                        || window.directReceipt().socketPort() != firstWindow.directReceipt().socketPort()
                        || !window.directReceipt().socketHost().equals(firstWindow.directReceipt().socketHost())) {
                    throw new IllegalArgumentException("persistent windows must use the same process socket session");
                }
                if (!window.directReceipt().receiverClientHandshake()
                        .equals(firstWindow.directReceipt().receiverClientHandshake())) {
                    throw new IllegalArgumentException("persistent windows must use the same client handshake");
                }
                HeadlessSnapshotSocketClient.ServerSessionWindowCursor cursor = sessionWindowCursors.get(i);
                long expectedPreviousCursor =
                        Math.multiplyExact((long) i, (long) window.directReceipt().expectedFrames());
                if (cursor.sessionId() != sessionWindowCursors.get(0).sessionId()
                        || cursor.windowOrdinal() != i
                        || cursor.previousResumeCursor() != expectedPreviousCursor
                        || cursor.nextResumeCursor()
                                != expectedPreviousCursor + window.directReceipt().expectedFrames()
                        || cursor.expectedFrames() != window.directReceipt().expectedFrames()
                        || !cursor.messageId().equals(window.directReceipt().receiverClientHandshake().messageId())
                        || cursor.wireTypeCode()
                                != window.directReceipt().receiverClientHandshake().wireTypeCode()
                        || cursor.schemaVersion()
                                != window.directReceipt().receiverClientHandshake().schemaVersion()
                        || !cursor.streamId().equals(
                                window.directReceipt().receiverClientHandshake().streamId())
                        || cursor.streamTypeCode()
                                != window.directReceipt().receiverClientHandshake().streamTypeCode()
                        || cursor.streamVersion()
                                != window.directReceipt().receiverClientHandshake().streamVersion()) {
                    throw new IllegalArgumentException(
                            "persistent session window cursor must match window receipt");
                }
            }
        }

        public List<OfflineCombatStepWireFrame> firstWindowFrames() {
            return windowReceipts.get(0).receiverFrames();
        }

        public List<OfflineCombatStepWireFrame> secondWindowFrames() {
            return windowReceipts.get(1).receiverFrames();
        }

        public long serverPid() {
            return windowReceipts.get(0).serverPid();
        }

        public long clientPid() {
            return windowReceipts.get(0).clientPid();
        }
    }

    public record ReconnectResumeCommandedProcessDirectReceipt(
            List<ProcessDirectReceipt> windowReceipts,
            List<HeadlessSnapshotSocketClient.ServerSessionWindowCursor> sessionWindowCursors,
            HeadlessSnapshotSocketClient.ClientSessionResumeClaim resumeClaim,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult) {

        public ReconnectResumeCommandedProcessDirectReceipt {
            windowReceipts = List.copyOf(Objects.requireNonNull(windowReceipts, "windowReceipts"));
            sessionWindowCursors =
                    List.copyOf(Objects.requireNonNull(sessionWindowCursors, "sessionWindowCursors"));
            resumeClaim = Objects.requireNonNull(resumeClaim, "resumeClaim");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
            if (windowReceipts.size() != 2 || sessionWindowCursors.size() != 2) {
                throw new IllegalArgumentException("reconnect resume receipt requires exactly two windows");
            }
            ProcessDirectReceipt firstWindow = windowReceipts.get(0);
            ProcessDirectReceipt secondWindow = windowReceipts.get(1);
            if (firstWindow.serverPid() != secondWindow.serverPid()) {
                throw new IllegalArgumentException("reconnect resume windows must share server pid");
            }
            if (firstWindow.serverPid() == firstWindow.clientPid()
                    || secondWindow.serverPid() == secondWindow.clientPid()
                    || firstWindow.clientPid() == secondWindow.clientPid()) {
                throw new IllegalArgumentException("reconnect resume requires two separate client processes");
            }
            if (!firstWindow.directReceipt().socketHost().equals(secondWindow.directReceipt().socketHost())) {
                throw new IllegalArgumentException("reconnect resume windows must use the same socket host");
            }
            if (firstWindow.directReceipt().socketPort() == secondWindow.directReceipt().socketPort()) {
                throw new IllegalArgumentException("reconnect resume must use a new socket session");
            }
            if (firstWindow.directReceipt().receiverClientMovementCommandResult() != null) {
                throw new IllegalArgumentException("reconnect resume first window must not apply movement command");
            }
            HeadlessSnapshotSocketClient.ServerMovementCommandResult result =
                    secondWindow.directReceipt().receiverClientMovementCommandResult();
            if (result == null) {
                throw new IllegalArgumentException("reconnect resume requires resumed-window movement result");
            }
            if (result.shipId() != movementCommand.shipId()
                    || result.simTick() != movementCommand.simTick()
                    || result.movementIntent() != movementCommand.movementIntent()
                    || result.commandResult() != commandResult) {
                throw new IllegalArgumentException("reconnect resume command result must match resumed command");
            }
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor firstCursor = sessionWindowCursors.get(0);
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor secondCursor = sessionWindowCursors.get(1);
            int expectedFrames = firstWindow.directReceipt().expectedFrames();
            if (secondWindow.directReceipt().expectedFrames() != expectedFrames) {
                throw new IllegalArgumentException("reconnect resume windows must use same frame count");
            }
            if (firstCursor.sessionId() != secondCursor.sessionId()
                    || firstCursor.windowOrdinal() != 0
                    || secondCursor.windowOrdinal() != 1
                    || firstCursor.previousResumeCursor() != 0L
                    || firstCursor.nextResumeCursor() != expectedFrames
                    || secondCursor.previousResumeCursor() != firstCursor.nextResumeCursor()
                    || secondCursor.nextResumeCursor() != Math.multiplyExact(2L, (long) expectedFrames)
                    || firstCursor.expectedFrames() != expectedFrames
                    || secondCursor.expectedFrames() != expectedFrames) {
                throw new IllegalArgumentException("reconnect resume cursor progression is invalid");
            }
            if (resumeClaim.sessionId() != firstCursor.sessionId()
                    || resumeClaim.windowOrdinal() != firstCursor.windowOrdinal()
                    || resumeClaim.previousResumeCursor() != firstCursor.previousResumeCursor()
                    || resumeClaim.nextResumeCursor() != firstCursor.nextResumeCursor()
                    || resumeClaim.expectedFrames() != firstCursor.expectedFrames()) {
                throw new IllegalArgumentException("reconnect resume claim must present first cursor");
            }
        }

        public List<OfflineCombatStepWireFrame> firstWindowFrames() {
            return windowReceipts.get(0).receiverFrames();
        }

        public List<OfflineCombatStepWireFrame> secondWindowFrames() {
            return windowReceipts.get(1).receiverFrames();
        }

        public long serverPid() {
            return windowReceipts.get(0).serverPid();
        }

        public long firstClientPid() {
            return windowReceipts.get(0).clientPid();
        }

        public long secondClientPid() {
            return windowReceipts.get(1).clientPid();
        }
    }

    public record ReplayRejectedReconnectResumeCommandedProcessDirectReceipt(
            ReconnectResumeCommandedProcessDirectReceipt acceptedResumeReceipt,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters replayedClaimParameters,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult,
            String rejectionMessage) {

        public ReplayRejectedReconnectResumeCommandedProcessDirectReceipt {
            acceptedResumeReceipt = Objects.requireNonNull(acceptedResumeReceipt, "acceptedResumeReceipt");
            replayedClaimParameters = Objects.requireNonNull(replayedClaimParameters, "replayedClaimParameters");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            if (commandResult != null) {
                throw new IllegalArgumentException("replayed reconnect command result must be absent");
            }
            rejectionMessage = Objects.requireNonNull(rejectionMessage, "rejectionMessage");
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor firstCursor =
                    acceptedResumeReceipt.sessionWindowCursors().get(0);
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor secondCursor =
                    acceptedResumeReceipt.sessionWindowCursors().get(1);
            if (replayedClaimParameters.sessionId() != firstCursor.sessionId()
                    || replayedClaimParameters.windowOrdinal() != firstCursor.windowOrdinal()
                    || replayedClaimParameters.previousResumeCursor() != firstCursor.previousResumeCursor()
                    || replayedClaimParameters.nextResumeCursor() != firstCursor.nextResumeCursor()) {
                throw new IllegalArgumentException("replayed reconnect claim must present consumed first cursor");
            }
            if (replayedClaimParameters.sessionId() == secondCursor.sessionId()
                    && replayedClaimParameters.windowOrdinal() == secondCursor.windowOrdinal()
                    && replayedClaimParameters.previousResumeCursor() == secondCursor.previousResumeCursor()
                    && replayedClaimParameters.nextResumeCursor() == secondCursor.nextResumeCursor()) {
                throw new IllegalArgumentException("replayed reconnect claim must differ from current cursor");
            }
            if (!ServerReconnectSessionState.isResumeClaimRejection(new IOException(rejectionMessage))) {
                throw new IllegalArgumentException("replayed reconnect rejection message does not name server state");
            }
        }
    }

    public record StaleCommandRejectedReconnectResumeProcessReceipt(
            ProcessDirectReceipt acceptedWindowReceipt,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor consumedCursor,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor currentCursor,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult,
            String rejectionMessage) {

        public StaleCommandRejectedReconnectResumeProcessReceipt {
            acceptedWindowReceipt = Objects.requireNonNull(acceptedWindowReceipt, "acceptedWindowReceipt");
            consumedCursor = Objects.requireNonNull(consumedCursor, "consumedCursor");
            currentCursor = Objects.requireNonNull(currentCursor, "currentCursor");
            resumeClaimParameters = Objects.requireNonNull(resumeClaimParameters, "resumeClaimParameters");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            if (commandResult != null) {
                throw new IllegalArgumentException("stale reconnect command result must be absent");
            }
            rejectionMessage = Objects.requireNonNull(rejectionMessage, "rejectionMessage");
            if (acceptedWindowReceipt.serverPid() == acceptedWindowReceipt.clientPid()) {
                throw new IllegalArgumentException("stale reconnect command requires a separate client process");
            }
            if (acceptedWindowReceipt.directReceipt().receiverClientMovementCommandResult() != null) {
                throw new IllegalArgumentException("stale reconnect command first window must not apply movement");
            }
            if (resumeClaimParameters.sessionId() != consumedCursor.sessionId()
                    || resumeClaimParameters.windowOrdinal() != consumedCursor.windowOrdinal()
                    || resumeClaimParameters.previousResumeCursor() != consumedCursor.previousResumeCursor()
                    || resumeClaimParameters.nextResumeCursor() != consumedCursor.nextResumeCursor()) {
                throw new IllegalArgumentException("stale reconnect command resume claim must present consumed cursor");
            }
            if (currentCursor.sessionId() != consumedCursor.sessionId()
                    || currentCursor.windowOrdinal() != consumedCursor.windowOrdinal() + 1
                    || currentCursor.previousResumeCursor() != consumedCursor.nextResumeCursor()) {
                throw new IllegalArgumentException("stale reconnect command current cursor must follow consumed cursor");
            }
            long simTick = movementCommand.simTick();
            if (simTick >= currentCursor.previousResumeCursor() && simTick < currentCursor.nextResumeCursor()) {
                throw new IllegalArgumentException("stale reconnect command tick must be outside current cursor");
            }
            if (!ServerReconnectSessionState.isMovementCommandRejection(new IOException(rejectionMessage))) {
                throw new IllegalArgumentException("stale reconnect command rejection message does not name server state");
            }
        }
    }

    public record UnauthorizedCommandRejectedReconnectResumeProcessReceipt(
            ProcessDirectReceipt acceptedWindowReceipt,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor consumedCursor,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor currentCursor,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            long authorizedCommandShipId,
            ShipCommandSink.Result commandResult,
            String rejectionMessage) {

        public UnauthorizedCommandRejectedReconnectResumeProcessReceipt {
            acceptedWindowReceipt = Objects.requireNonNull(acceptedWindowReceipt, "acceptedWindowReceipt");
            consumedCursor = Objects.requireNonNull(consumedCursor, "consumedCursor");
            currentCursor = Objects.requireNonNull(currentCursor, "currentCursor");
            resumeClaimParameters = Objects.requireNonNull(resumeClaimParameters, "resumeClaimParameters");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            if (authorizedCommandShipId <= 0L) {
                throw new IllegalArgumentException(
                        "authorizedCommandShipId must be > 0; got " + authorizedCommandShipId);
            }
            if (commandResult != null) {
                throw new IllegalArgumentException("unauthorized reconnect command result must be absent");
            }
            rejectionMessage = Objects.requireNonNull(rejectionMessage, "rejectionMessage");
            if (acceptedWindowReceipt.serverPid() == acceptedWindowReceipt.clientPid()) {
                throw new IllegalArgumentException("unauthorized reconnect command requires a separate client process");
            }
            if (acceptedWindowReceipt.directReceipt().receiverClientMovementCommandResult() != null) {
                throw new IllegalArgumentException(
                        "unauthorized reconnect command first window must not apply movement");
            }
            if (resumeClaimParameters.sessionId() != consumedCursor.sessionId()
                    || resumeClaimParameters.windowOrdinal() != consumedCursor.windowOrdinal()
                    || resumeClaimParameters.previousResumeCursor() != consumedCursor.previousResumeCursor()
                    || resumeClaimParameters.nextResumeCursor() != consumedCursor.nextResumeCursor()) {
                throw new IllegalArgumentException(
                        "unauthorized reconnect command resume claim must present consumed cursor");
            }
            if (currentCursor.sessionId() != consumedCursor.sessionId()
                    || currentCursor.windowOrdinal() != consumedCursor.windowOrdinal() + 1
                    || currentCursor.previousResumeCursor() != consumedCursor.nextResumeCursor()) {
                throw new IllegalArgumentException(
                        "unauthorized reconnect command current cursor must follow consumed cursor");
            }
            long simTick = movementCommand.simTick();
            if (simTick < currentCursor.previousResumeCursor() || simTick >= currentCursor.nextResumeCursor()) {
                throw new IllegalArgumentException("unauthorized reconnect command tick must be inside current cursor");
            }
            if (movementCommand.shipId() == authorizedCommandShipId) {
                throw new IllegalArgumentException(
                        "unauthorized reconnect command must target a non-authorized ship id");
            }
            if (!ServerReconnectSessionState.isMovementCommandRejection(new IOException(rejectionMessage))) {
                throw new IllegalArgumentException(
                        "unauthorized reconnect command rejection message does not name server state");
            }
        }
    }

    public record MissingClaimRejectedReconnectResumeProcessReceipt(
            ProcessDirectReceipt acceptedWindowReceipt,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor consumedCursor,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult,
            String rejectionMessage) {

        public MissingClaimRejectedReconnectResumeProcessReceipt {
            acceptedWindowReceipt = Objects.requireNonNull(acceptedWindowReceipt, "acceptedWindowReceipt");
            consumedCursor = Objects.requireNonNull(consumedCursor, "consumedCursor");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            if (commandResult != null) {
                throw new IllegalArgumentException("missing-claim reconnect command result must be absent");
            }
            rejectionMessage = Objects.requireNonNull(rejectionMessage, "rejectionMessage");
            if (acceptedWindowReceipt.serverPid() == acceptedWindowReceipt.clientPid()) {
                throw new IllegalArgumentException("missing-claim reconnect command requires a separate client process");
            }
            if (acceptedWindowReceipt.directReceipt().receiverClientMovementCommandResult() != null) {
                throw new IllegalArgumentException(
                        "missing-claim reconnect first window must not apply movement");
            }
            if (movementCommand.simTick() < consumedCursor.nextResumeCursor()) {
                throw new IllegalArgumentException(
                        "missing-claim reconnect command must target a resumed-window tick");
            }
            if (!ServerReconnectSessionState.isMovementCommandRejection(new IOException(rejectionMessage))
                    || !rejectionMessage.contains("missing resume claim")) {
                throw new IllegalArgumentException(
                        "missing-claim reconnect rejection message does not name server state and missing claim");
            }
        }
    }

    public record StreamIdentityRejectedProcessAdmissionReceipt(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            int expectedFrames,
            long receiverArtifactBytes,
            String socketHost,
            int socketPort,
            long serverPid,
            long clientPid,
            int clientExitCode,
            SnapshotStreamIdentity requiredStreamIdentity,
            SnapshotStreamIdentity offeredStreamIdentity,
            HeadlessSnapshotSocketClient.ClientHandshake clientHandshake,
            SnapshotClientAdmissionGate.Admission clientAdmission,
            int clientAdmissionAckBytes,
            HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck,
            Properties clientReceipt) {

        public StreamIdentityRejectedProcessAdmissionReceipt {
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            clientReceiptPath = Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
            clientLogPath = Objects.requireNonNull(clientLogPath, "clientLogPath");
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            requiredStreamIdentity = Objects.requireNonNull(requiredStreamIdentity, "requiredStreamIdentity");
            offeredStreamIdentity = Objects.requireNonNull(offeredStreamIdentity, "offeredStreamIdentity");
            clientHandshake = Objects.requireNonNull(clientHandshake, "clientHandshake");
            clientAdmission = Objects.requireNonNull(clientAdmission, "clientAdmission");
            clientAdmissionAck = Objects.requireNonNull(clientAdmissionAck, "clientAdmissionAck");
            clientReceipt = Objects.requireNonNull(clientReceipt, "clientReceipt");
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (receiverArtifactBytes != 0L) {
                throw new IllegalArgumentException("stream identity rejection must not write snapshot bytes");
            }
            if (serverPid <= 0L || clientPid <= 0L || serverPid == clientPid) {
                throw new IllegalArgumentException("stream identity rejection requires a separate client process");
            }
            if (clientExitCode != 0) {
                throw new IllegalArgumentException("stream identity rejected client process must exit 0");
            }
            if (clientAdmission.accepted() || clientAdmissionAck.accepted()) {
                throw new IllegalArgumentException("stream identity mismatch admission must be rejected");
            }
            if (!"server_stream_identity_mismatch".equals(clientAdmission.reason())) {
                throw new IllegalArgumentException("stream identity rejection reason mismatch: "
                        + clientAdmission.reason());
            }
            if (clientAdmissionAckBytes != HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES) {
                throw new IllegalArgumentException("stream identity rejection admission ack byte count mismatch");
            }
            if (!offeredStreamIdentity.streamId().equals(clientHandshake.streamId())
                    || offeredStreamIdentity.streamTypeCode() != clientHandshake.streamTypeCode()
                    || offeredStreamIdentity.streamVersion() != clientHandshake.streamVersion()) {
                throw new IllegalArgumentException("stream identity rejected handshake must advertise offered stream");
            }
            if (requiredStreamIdentity.streamTypeCode() == offeredStreamIdentity.streamTypeCode()
                    && requiredStreamIdentity.streamVersion() == offeredStreamIdentity.streamVersion()) {
                throw new IllegalArgumentException("required and offered stream identities must differ");
            }
            try {
                HeadlessSnapshotProcessReceiptValidator.requireProperty(clientReceipt, "status", "ok");
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "admission_accepted",
                        "false");
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "stream_id",
                        offeredStreamIdentity.streamId());
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "stream_version",
                        Integer.toString(offeredStreamIdentity.streamVersion()));
            } catch (IOException ioe) {
                throw new IllegalArgumentException("stream identity rejected process receipt mismatch", ioe);
            }
        }
    }

    public record CapabilityManifestRejectedProcessAdmissionReceipt(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            int expectedFrames,
            long receiverArtifactBytes,
            String socketHost,
            int socketPort,
            long serverPid,
            long clientPid,
            int clientExitCode,
            SnapshotStreamIdentity streamIdentity,
            NetworkCapabilityManifest offeredCapabilityManifest,
            HeadlessSnapshotSocketClient.ClientHandshake clientHandshake,
            SnapshotClientAdmissionGate.Admission clientAdmission,
            int clientAdmissionAckBytes,
            HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck,
            Properties clientReceipt) {

        public CapabilityManifestRejectedProcessAdmissionReceipt {
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            clientReceiptPath = Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
            clientLogPath = Objects.requireNonNull(clientLogPath, "clientLogPath");
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            streamIdentity = Objects.requireNonNull(streamIdentity, "streamIdentity");
            offeredCapabilityManifest =
                    Objects.requireNonNull(offeredCapabilityManifest, "offeredCapabilityManifest");
            clientHandshake = Objects.requireNonNull(clientHandshake, "clientHandshake");
            clientAdmission = Objects.requireNonNull(clientAdmission, "clientAdmission");
            clientAdmissionAck = Objects.requireNonNull(clientAdmissionAck, "clientAdmissionAck");
            clientReceipt = Objects.requireNonNull(clientReceipt, "clientReceipt");
            NetworkCapabilityManifest requiredCapabilityManifest =
                    NetworkMessageRegistry.authoritativeCombat().capabilityManifest();
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (receiverArtifactBytes != 0L) {
                throw new IllegalArgumentException("capability manifest rejection must not write snapshot bytes");
            }
            if (serverPid <= 0L || clientPid <= 0L || serverPid == clientPid) {
                throw new IllegalArgumentException("capability manifest rejection requires a separate client process");
            }
            if (clientExitCode != 0) {
                throw new IllegalArgumentException("capability manifest rejected client process must exit 0");
            }
            if (clientAdmission.accepted() || clientAdmissionAck.accepted()) {
                throw new IllegalArgumentException("capability manifest mismatch admission must be rejected");
            }
            if (!"registry_capability_mismatch".equals(clientAdmission.reason())) {
                throw new IllegalArgumentException("capability manifest rejection reason mismatch: "
                        + clientAdmission.reason());
            }
            if (clientAdmissionAckBytes != HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES) {
                throw new IllegalArgumentException("capability manifest rejection admission ack byte count mismatch");
            }
            if (!streamIdentity.streamId().equals(clientHandshake.streamId())
                    || streamIdentity.streamTypeCode() != clientHandshake.streamTypeCode()
                    || streamIdentity.streamVersion() != clientHandshake.streamVersion()) {
                throw new IllegalArgumentException("capability manifest rejected handshake must keep stream identity");
            }
            if (!offeredCapabilityManifest.equals(clientHandshake.capabilityManifest())
                    || !offeredCapabilityManifest.equals(clientAdmission.offeredCapabilityManifest())) {
                throw new IllegalArgumentException("capability manifest rejected handshake must advertise offered manifest");
            }
            if (offeredCapabilityManifest.equals(requiredCapabilityManifest)) {
                throw new IllegalArgumentException("offered capability manifest must differ from registry manifest");
            }
            if (!requiredCapabilityManifest.equals(clientAdmission.requiredCapabilityManifest())
                    || !requiredCapabilityManifest.equals(clientAdmissionAck.capabilityManifest())) {
                throw new IllegalArgumentException("capability manifest rejection must acknowledge required manifest");
            }
            try {
                HeadlessSnapshotProcessReceiptValidator.requireProperty(clientReceipt, "status", "ok");
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "admission_accepted",
                        "false");
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "stream_id",
                        streamIdentity.streamId());
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "capability_fingerprint_crc32",
                        Long.toUnsignedString(offeredCapabilityManifest.fingerprintCrc32()));
            } catch (IOException ioe) {
                throw new IllegalArgumentException("capability manifest rejected process receipt mismatch", ioe);
            }
        }
    }

    public record FrameBoundRejectedProcessAdmissionReceipt(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            int requiredFrames,
            int clientExpectedFrames,
            long receiverArtifactBytes,
            String socketHost,
            int socketPort,
            long serverPid,
            long clientPid,
            int clientExitCode,
            SnapshotStreamIdentity streamIdentity,
            HeadlessSnapshotSocketClient.ClientHandshake clientHandshake,
            SnapshotClientAdmissionGate.Admission clientAdmission,
            int clientAdmissionAckBytes,
            HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck,
            Properties clientReceipt) {

        public FrameBoundRejectedProcessAdmissionReceipt {
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            clientReceiptPath = Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
            clientLogPath = Objects.requireNonNull(clientLogPath, "clientLogPath");
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            streamIdentity = Objects.requireNonNull(streamIdentity, "streamIdentity");
            clientHandshake = Objects.requireNonNull(clientHandshake, "clientHandshake");
            clientAdmission = Objects.requireNonNull(clientAdmission, "clientAdmission");
            clientAdmissionAck = Objects.requireNonNull(clientAdmissionAck, "clientAdmissionAck");
            clientReceipt = Objects.requireNonNull(clientReceipt, "clientReceipt");
            if (requiredFrames < 0) {
                throw new IllegalArgumentException("requiredFrames must be >= 0; got " + requiredFrames);
            }
            if (clientExpectedFrames < 0) {
                throw new IllegalArgumentException(
                        "clientExpectedFrames must be >= 0; got " + clientExpectedFrames);
            }
            if (requiredFrames == clientExpectedFrames) {
                throw new IllegalArgumentException("frame-bound rejection requires different frame counts");
            }
            if (receiverArtifactBytes != 0L) {
                throw new IllegalArgumentException("frame-bound rejection must not write snapshot bytes");
            }
            if (serverPid <= 0L || clientPid <= 0L || serverPid == clientPid) {
                throw new IllegalArgumentException("frame-bound rejection requires a separate client process");
            }
            if (clientExitCode != 0) {
                throw new IllegalArgumentException("frame-bound rejected client process must exit 0");
            }
            if (clientAdmission.accepted() || clientAdmissionAck.accepted()) {
                throw new IllegalArgumentException("frame-bound mismatch admission must be rejected");
            }
            if (!"frame_bound_mismatch".equals(clientAdmission.reason())) {
                throw new IllegalArgumentException("frame-bound rejection reason mismatch: "
                        + clientAdmission.reason());
            }
            if (clientAdmission.expectedFrames() != clientExpectedFrames
                    || clientAdmission.requiredFrames() != requiredFrames
                    || clientAdmissionAck.expectedFrames() != clientExpectedFrames
                    || clientHandshake.expectedFrames() != clientExpectedFrames) {
                throw new IllegalArgumentException("frame-bound rejection must preserve offered and required frames");
            }
            if (clientAdmissionAckBytes != HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES) {
                throw new IllegalArgumentException("frame-bound rejection admission ack byte count mismatch");
            }
            if (!streamIdentity.streamId().equals(clientHandshake.streamId())
                    || streamIdentity.streamTypeCode() != clientHandshake.streamTypeCode()
                    || streamIdentity.streamVersion() != clientHandshake.streamVersion()) {
                throw new IllegalArgumentException("frame-bound rejected handshake must keep stream identity");
            }
            if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                    .equals(clientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("frame-bound rejected handshake must keep required capability");
            }
            try {
                HeadlessSnapshotProcessReceiptValidator.requireProperty(clientReceipt, "status", "ok");
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "admission_accepted",
                        "false");
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "stream_id",
                        streamIdentity.streamId());
                HeadlessSnapshotProcessReceiptValidator.requireProperty(
                        clientReceipt,
                        "expected_frames",
                        Integer.toString(clientExpectedFrames));
            } catch (IOException ioe) {
                throw new IllegalArgumentException("frame-bound rejected process receipt mismatch", ioe);
            }
        }
    }

    public record RejectedReconnectResumeCommandedProcessDirectReceipt(
            ProcessDirectReceipt acceptedWindowReceipt,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters rejectedClaimParameters,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult,
            String rejectionMessage) {

        public RejectedReconnectResumeCommandedProcessDirectReceipt {
            acceptedWindowReceipt = Objects.requireNonNull(acceptedWindowReceipt, "acceptedWindowReceipt");
            issuedCursor = Objects.requireNonNull(issuedCursor, "issuedCursor");
            rejectedClaimParameters = Objects.requireNonNull(rejectedClaimParameters, "rejectedClaimParameters");
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            if (commandResult != null) {
                throw new IllegalArgumentException("rejected reconnect command result must be absent");
            }
            rejectionMessage = Objects.requireNonNull(rejectionMessage, "rejectionMessage");
            HeadlessSnapshotSocketClient.ServerMovementCommandResult result =
                    acceptedWindowReceipt.directReceipt().receiverClientMovementCommandResult();
            if (acceptedWindowReceipt.serverPid() == acceptedWindowReceipt.clientPid()) {
                throw new IllegalArgumentException("reconnect rejection requires a separate client process");
            }
            if (result != null) {
                throw new IllegalArgumentException("reconnect rejection first window must not apply movement command");
            }
            if (rejectedClaimParameters.sessionId() == issuedCursor.sessionId()
                    && rejectedClaimParameters.windowOrdinal() == issuedCursor.windowOrdinal()
                    && rejectedClaimParameters.previousResumeCursor() == issuedCursor.previousResumeCursor()
                    && rejectedClaimParameters.nextResumeCursor() == issuedCursor.nextResumeCursor()) {
                throw new IllegalArgumentException("reconnect rejection claim parameters must differ from cursor");
            }
            if (!ServerReconnectSessionState.isResumeClaimRejection(
                    new IOException(rejectionMessage))) {
                throw new IllegalArgumentException("reconnect rejection message does not name server session state");
            }
        }
    }

    record SessionWindowProcessResult(
            ProcessDirectReceipt processReceipt,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor sessionWindowCursor,
            HeadlessSnapshotSocketClient.ClientSessionResumeClaim resumeClaim,
            HeadlessSnapshotSocketClient.ClientMovementCommand receivedMovementCommand,
            ShipCommandSink.Result movementCommandResult,
            HeadlessSnapshotSocketClient.ClientFireCommand receivedFireCommand,
            ShipCommandSink.Result fireCommandResult,
            HeadlessSnapshotSocketClient.ClientSystemCommand receivedSystemCommand,
            ShipCommandSink.Result systemCommandResult) {

        SessionWindowProcessResult {
            processReceipt = Objects.requireNonNull(processReceipt, "processReceipt");
            sessionWindowCursor = Objects.requireNonNull(sessionWindowCursor, "sessionWindowCursor");
        }
    }

    public record MultiClientDirectReceipt(
            int expectedFrames,
            long expectedBytesPerClient,
            long senderBytesPerClient,
            long totalSenderBytes,
            String socketHost,
            int socketPort,
            String receiverDecodeSource,
            boolean receiverArtifactUsedAsDecodeSource,
            String receiverClientContract,
            List<DirectClientReceipt> clients,
            List<OfflineCombatStepWireFrame> senderFrames) {

        public MultiClientDirectReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            receiverDecodeSource = Objects.requireNonNull(receiverDecodeSource, "receiverDecodeSource");
            receiverClientContract = Objects.requireNonNull(receiverClientContract, "receiverClientContract");
            clients = List.copyOf(Objects.requireNonNull(clients, "clients"));
            senderFrames = List.copyOf(Objects.requireNonNull(senderFrames, "senderFrames"));
            if (clients.size() < 2) {
                throw new IllegalArgumentException("multi-client direct receipt requires at least two clients");
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (expectedBytesPerClient < 0L) {
                throw new IllegalArgumentException("expectedBytesPerClient must be >= 0; got "
                        + expectedBytesPerClient);
            }
            if (senderBytesPerClient != expectedBytesPerClient) {
                throw new IllegalArgumentException("senderBytesPerClient must equal expectedBytesPerClient");
            }
            if (totalSenderBytes != Math.multiplyExact(senderBytesPerClient, (long) clients.size())) {
                throw new IllegalArgumentException("totalSenderBytes must equal senderBytesPerClient * client count");
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must not be blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (receiverDecodeSource.isBlank()) {
                throw new IllegalArgumentException("receiverDecodeSource must not be blank");
            }
            if (receiverArtifactUsedAsDecodeSource) {
                throw new IllegalArgumentException("multi-client direct receipt must decode from socket input");
            }
            if (receiverClientContract.isBlank()) {
                throw new IllegalArgumentException("receiverClientContract must not be blank");
            }
            if (senderFrames.size() != expectedFrames) {
                throw new IllegalArgumentException("sender frame count must equal expectedFrames");
            }
            for (int i = 0; i < clients.size(); i++) {
                DirectClientReceipt client = clients.get(i);
                if (client.clientIndex() != i) {
                    throw new IllegalArgumentException("client receipt index mismatch at " + i);
                }
                if (client.expectedFrames() != expectedFrames
                        || client.expectedBytes() != expectedBytesPerClient
                        || client.bytesReceived() != expectedBytesPerClient
                        || !client.decodeSource().equals(receiverDecodeSource)
                        || client.artifactUsedAsDecodeSource()
                        || !client.receiverClientContract().equals(receiverClientContract)) {
                    throw new IllegalArgumentException("client receipt does not match multi-client transport contract");
                }
                if (!client.frames().equals(senderFrames)) {
                    throw new IllegalArgumentException("client frames must match sender fanout frames");
                }
            }
        }

        public int clientCount() {
            return clients.size();
        }
    }

    public record DirectClientReceipt(
            int clientIndex,
            Path receiverArtifactPath,
            int expectedFrames,
            long expectedBytes,
            long bytesReceived,
            String decodeSource,
            boolean artifactUsedAsDecodeSource,
            String receiverClientContract,
            HeadlessSnapshotSocketClient.ClientHandshake receiverClientHandshake,
            SnapshotClientAdmissionGate.Admission receiverClientAdmission,
            int receiverClientAdmissionAckBytes,
            HeadlessSnapshotSocketClient.ServerAdmissionAck receiverClientAdmissionAck,
            int receiverClientInitialSyncBytes,
            HeadlessSnapshotSocketClient.ServerInitialSync receiverClientInitialSync,
            int receiverClientStreamCompletionBytes,
            HeadlessSnapshotSocketClient.ServerStreamCompletion receiverClientStreamCompletion,
            List<OfflineCombatStepWireFrame> frames) {

        public DirectClientReceipt {
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            decodeSource = Objects.requireNonNull(decodeSource, "decodeSource");
            receiverClientContract = Objects.requireNonNull(receiverClientContract, "receiverClientContract");
            receiverClientHandshake = Objects.requireNonNull(receiverClientHandshake, "receiverClientHandshake");
            receiverClientAdmission = Objects.requireNonNull(receiverClientAdmission, "receiverClientAdmission");
            receiverClientAdmissionAck =
                    Objects.requireNonNull(receiverClientAdmissionAck, "receiverClientAdmissionAck");
            receiverClientInitialSync =
                    Objects.requireNonNull(receiverClientInitialSync, "receiverClientInitialSync");
            receiverClientStreamCompletion =
                    Objects.requireNonNull(receiverClientStreamCompletion, "receiverClientStreamCompletion");
            frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
            if (clientIndex < 0) {
                throw new IllegalArgumentException("clientIndex must be >= 0; got " + clientIndex);
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (expectedBytes < 0L) {
                throw new IllegalArgumentException("expectedBytes must be >= 0; got " + expectedBytes);
            }
            if (bytesReceived != expectedBytes) {
                throw new IllegalArgumentException("bytesReceived must equal expectedBytes");
            }
            if (decodeSource.isBlank()) {
                throw new IllegalArgumentException("decodeSource must not be blank");
            }
            if (artifactUsedAsDecodeSource) {
                throw new IllegalArgumentException("direct client receipt must decode from socket input");
            }
            if (receiverClientContract.isBlank()) {
                throw new IllegalArgumentException("receiverClientContract must not be blank");
            }
            if (receiverClientHandshake.expectedFrames() != expectedFrames) {
                throw new IllegalArgumentException("receiver client handshake expectedFrames must match receipt");
            }
            if (receiverClientAdmissionAckBytes != HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES) {
                throw new IllegalArgumentException("receiver client admission ack byte count mismatch");
            }
            if (receiverClientInitialSyncBytes != HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES) {
                throw new IllegalArgumentException("receiver client initial sync byte count mismatch");
            }
            if (receiverClientStreamCompletionBytes != HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES) {
                throw new IllegalArgumentException("receiver client stream completion byte count mismatch");
            }
            if (!receiverClientAdmission.accepted()
                    || receiverClientAdmission.requiredFrames() != expectedFrames
                    || receiverClientAdmission.expectedFrames() != expectedFrames
                    || receiverClientAdmission.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientAdmission.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientAdmission.messageId().equals(receiverClientHandshake.messageId())
                    || !receiverClientAdmission.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientAdmission.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientAdmission.streamVersion() != receiverClientHandshake.streamVersion()
                    || !receiverClientAdmission.offeredCapabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())
                    || !receiverClientAdmission.requiredCapabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("receiver client admission must match accepted handshake");
            }
            if (!receiverClientAdmissionAck.accepted()
                    || receiverClientAdmissionAck.ackMessageTypeCode()
                            != HeadlessSnapshotSocketClient.ADMISSION_ACK_MESSAGE_TYPE_CODE
                    || receiverClientAdmissionAck.ackSchemaVersion()
                            != Short.toUnsignedInt(HeadlessSnapshotSocketClient.ADMISSION_ACK_SCHEMA_VERSION)
                    || !receiverClientAdmissionAck.messageId().equals(receiverClientHandshake.messageId())
                    || receiverClientAdmissionAck.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientAdmissionAck.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientAdmissionAck.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientAdmissionAck.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientAdmissionAck.streamVersion() != receiverClientHandshake.streamVersion()
                    || receiverClientAdmissionAck.expectedFrames() != expectedFrames
                    || !receiverClientAdmissionAck.capabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("receiver client admission ack must match accepted handshake");
            }
            if (receiverClientInitialSync.initialSyncMessageTypeCode()
                            != HeadlessSnapshotSocketClient.INITIAL_SYNC_MESSAGE_TYPE_CODE
                    || receiverClientInitialSync.initialSyncSchemaVersion()
                            != Short.toUnsignedInt(HeadlessSnapshotSocketClient.INITIAL_SYNC_SCHEMA_VERSION)
                    || !receiverClientInitialSync.messageId().equals(receiverClientHandshake.messageId())
                    || receiverClientInitialSync.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientInitialSync.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientInitialSync.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientInitialSync.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientInitialSync.streamVersion() != receiverClientHandshake.streamVersion()
                    || !receiverClientInitialSync.capabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("receiver client initial sync must match accepted handshake");
            }
            if (receiverClientStreamCompletion.completionMessageTypeCode()
                            != HeadlessSnapshotSocketClient.STREAM_COMPLETED_MESSAGE_TYPE_CODE
                    || receiverClientStreamCompletion.completionSchemaVersion()
                            != Short.toUnsignedInt(HeadlessSnapshotSocketClient.STREAM_COMPLETED_SCHEMA_VERSION)
                    || !receiverClientStreamCompletion.messageId().equals(receiverClientHandshake.messageId())
                    || receiverClientStreamCompletion.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientStreamCompletion.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientStreamCompletion.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientStreamCompletion.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientStreamCompletion.streamVersion() != receiverClientHandshake.streamVersion()
                    || receiverClientStreamCompletion.completedFrames() != expectedFrames
                    || !receiverClientStreamCompletion.capabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("receiver client stream completion must match handshake");
            }
            if (frames.size() != expectedFrames) {
                throw new IllegalArgumentException("frame count must equal expectedFrames");
            }
            if (!frames.isEmpty()) {
                HeadlessSnapshotProcessReceiptValidator.requireInitialSyncMatchesFirstFrame(receiverClientInitialSync, frames.get(0));
            }
            HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(receiverClientHandshake, expectedFrames, frames);
        }
    }

    public record MixedAdmissionDirectReceipt(
            int expectedFrames,
            long expectedBytes,
            long senderBytes,
            String socketHost,
            int socketPort,
            DirectClientReceipt acceptedClient,
            RejectedClientReceipt rejectedClient,
            List<OfflineCombatStepWireFrame> senderFrames) {

        public MixedAdmissionDirectReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            acceptedClient = Objects.requireNonNull(acceptedClient, "acceptedClient");
            rejectedClient = Objects.requireNonNull(rejectedClient, "rejectedClient");
            senderFrames = List.copyOf(Objects.requireNonNull(senderFrames, "senderFrames"));
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (expectedBytes < 0L) {
                throw new IllegalArgumentException("expectedBytes must be >= 0; got " + expectedBytes);
            }
            if (senderBytes != expectedBytes) {
                throw new IllegalArgumentException("senderBytes must equal expectedBytes");
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must not be blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (acceptedClient.clientIndex() != 0 || rejectedClient.clientIndex() != 1) {
                throw new IllegalArgumentException("mixed admission client indexes must be 0 accepted / 1 rejected");
            }
            if (acceptedClient.expectedFrames() != expectedFrames
                    || acceptedClient.expectedBytes() != expectedBytes
                    || acceptedClient.bytesReceived() != expectedBytes
                    || !acceptedClient.frames().equals(senderFrames)) {
                throw new IllegalArgumentException("accepted client must match streamed sender frames");
            }
            if (rejectedClient.requiredFrames() != expectedFrames
                    || rejectedClient.rejectedExpectedFrames() == expectedFrames
                    || rejectedClient.receiverClientAdmission().accepted()
                    || rejectedClient.receiverClientAdmissionAck().accepted()) {
                throw new IllegalArgumentException("rejected client must be rejected before streaming");
            }
            if (senderFrames.size() != expectedFrames) {
                throw new IllegalArgumentException("sender frame count must equal expectedFrames");
            }
        }
    }

    public record RejectedClientReceipt(
            int clientIndex,
            int rejectedExpectedFrames,
            int requiredFrames,
            HeadlessSnapshotSocketClient.ClientHandshake receiverClientHandshake,
            SnapshotClientAdmissionGate.Admission receiverClientAdmission,
            int receiverClientAdmissionAckBytes,
            HeadlessSnapshotSocketClient.ServerAdmissionAck receiverClientAdmissionAck,
            HeadlessSnapshotSocketClient.AdmissionReceipt clientAdmissionReceipt) {

        public RejectedClientReceipt {
            receiverClientHandshake = Objects.requireNonNull(receiverClientHandshake, "receiverClientHandshake");
            receiverClientAdmission = Objects.requireNonNull(receiverClientAdmission, "receiverClientAdmission");
            receiverClientAdmissionAck =
                    Objects.requireNonNull(receiverClientAdmissionAck, "receiverClientAdmissionAck");
            clientAdmissionReceipt = Objects.requireNonNull(clientAdmissionReceipt, "clientAdmissionReceipt");
            if (clientIndex < 0) {
                throw new IllegalArgumentException("clientIndex must be >= 0; got " + clientIndex);
            }
            if (rejectedExpectedFrames < 0) {
                throw new IllegalArgumentException("rejectedExpectedFrames must be >= 0; got "
                        + rejectedExpectedFrames);
            }
            if (requiredFrames < 0) {
                throw new IllegalArgumentException("requiredFrames must be >= 0; got " + requiredFrames);
            }
            if (receiverClientAdmission.accepted()) {
                throw new IllegalArgumentException("rejected client admission must be rejected");
            }
            if (receiverClientAdmission.expectedFrames() != rejectedExpectedFrames
                    || receiverClientAdmission.requiredFrames() != requiredFrames
                    || !receiverClientAdmission.messageId().equals(receiverClientHandshake.messageId())
                    || receiverClientAdmission.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientAdmission.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientAdmission.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientAdmission.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientAdmission.streamVersion() != receiverClientHandshake.streamVersion()
                    || !receiverClientAdmission.offeredCapabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("rejected admission must match rejected client handshake");
            }
            if (receiverClientAdmissionAckBytes != HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES) {
                throw new IllegalArgumentException("rejected admission ack byte count mismatch");
            }
            if (receiverClientAdmissionAck.accepted()
                    || receiverClientAdmissionAck.ackMessageTypeCode()
                            != HeadlessSnapshotSocketClient.ADMISSION_ACK_MESSAGE_TYPE_CODE
                    || receiverClientAdmissionAck.ackSchemaVersion()
                            != Short.toUnsignedInt(HeadlessSnapshotSocketClient.ADMISSION_ACK_SCHEMA_VERSION)
                    || !receiverClientAdmissionAck.messageId().equals(receiverClientHandshake.messageId())
                    || receiverClientAdmissionAck.wireTypeCode() != receiverClientHandshake.wireTypeCode()
                    || receiverClientAdmissionAck.schemaVersion() != receiverClientHandshake.schemaVersion()
                    || !receiverClientAdmissionAck.streamId().equals(receiverClientHandshake.streamId())
                    || receiverClientAdmissionAck.streamTypeCode() != receiverClientHandshake.streamTypeCode()
                    || receiverClientAdmissionAck.streamVersion() != receiverClientHandshake.streamVersion()
                    || receiverClientAdmissionAck.expectedFrames() != rejectedExpectedFrames
                    || !receiverClientAdmissionAck.capabilityManifest()
                            .equals(receiverClientHandshake.capabilityManifest())) {
                throw new IllegalArgumentException("rejected admission ack must match rejected handshake");
            }
            if (!clientAdmissionReceipt.handshake().equals(receiverClientHandshake)
                    || clientAdmissionReceipt.expectedFrames() != rejectedExpectedFrames
                    || clientAdmissionReceipt.admissionAckBytes()
                            != HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES
                    || !clientAdmissionReceipt.admissionAck().equals(receiverClientAdmissionAck)) {
                throw new IllegalArgumentException("rejected client socket receipt must match server acknowledgement");
            }
        }
    }

}
