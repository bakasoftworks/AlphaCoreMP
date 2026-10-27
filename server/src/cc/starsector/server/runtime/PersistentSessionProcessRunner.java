package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.client.SnapshotClientAdmissionGate;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectReceipt;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectSocketPublication;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.PersistentCommandedProcessDirectReceipt;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.ProcessDirectReceipt;
import java.io.IOException;
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

final class PersistentSessionProcessRunner {
    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private PersistentSessionProcessRunner() {
    }

    private static Path siblingWithSuffix(Path path, String suffix) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(suffix, "suffix");
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("path must have a file name: " + path);
        }
        return path.resolveSibling(fileName.toString() + suffix);
    }
    static PersistentCommandedProcessDirectReceipt sendPersistentSessionToProcessWithClientMovementCommand(
            List<Path> receiverArtifactPaths,
            Path clientReceiptPath,
            int expectedFramesPerWindow,
            List<DirectSocketPublication> publications,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths"));
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != 2 || publications.size() != 2) {
            throw new IllegalArgumentException("persistent session reuse currently requires exactly two windows");
        }
        if (expectedFramesPerWindow < 0) {
            throw new IllegalArgumentException(
                    "expectedFramesPerWindow must be >= 0; got " + expectedFramesPerWindow);
        }
        for (Path receiverArtifactPath : receiverArtifactPaths) {
            Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            Path receiverParent = receiverArtifactPath.getParent();
            if (receiverParent != null) {
                Files.createDirectories(receiverParent);
            }
        }
        Path receiptParent = clientReceiptPath.getParent();
        if (receiptParent != null) {
            Files.createDirectories(receiptParent);
        }
        Path clientLogPath = siblingWithSuffix(clientReceiptPath, ".log");
        Files.deleteIfExists(clientReceiptPath);
        Files.deleteIfExists(clientLogPath);

        long expectedBytesPerWindow = Math.multiplyExact(
                (long) expectedFramesPerWindow,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        String socketHost = socketAddress.getHostAddress();
        IOException senderFailure = null;
        RuntimeException publisherFailure = null;
        List<Long> senderBytes = new ArrayList<>(publications.size());
        List<List<OfflineCombatStepWireFrame>> senderFrames = new ArrayList<>(publications.size());
        List<HeadlessSnapshotSocketClient.ServerInitialSync> clientInitialSyncs =
                new ArrayList<>(publications.size());
        List<HeadlessSnapshotSocketClient.ServerStreamCompletion> clientStreamCompletions =
                new ArrayList<>(publications.size());
        List<HeadlessSnapshotSocketClient.ServerSessionWindowCursor> clientSessionWindowCursors =
                new ArrayList<>(publications.size());
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        HeadlessSnapshotSocketClient.ClientMovementCommand receivedMovementCommand = null;
        ShipCommandSink.Result movementCommandResult = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        HeadlessSnapshotSocketClient.ServerMovementCommandResult clientMovementCommandResult = null;
        int clientMovementCommandResultBytes = 0;
        long serverPid = ProcessHandle.current().pid();
        long clientPid = -1L;
        int clientExitCode = -1;
        int port;
        Process clientProcess = null;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientProcess = HeadlessSnapshotClientProcessLauncher.startPersistentHeadlessClientProcess(
                    socketHost,
                    port,
                    receiverArtifactPaths,
                    clientReceiptPath,
                    expectedFramesPerWindow,
                    streamIdentity,
                    clientLogPath,
                    movementCommand);
            clientPid = clientProcess.pid();

            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                InputStream socketInput = socket.getInputStream();
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socketInput);
                long sessionWindowSessionId =
                        HeadlessSnapshotSocketClient.createSessionWindowSessionId(clientHandshake, serverPid);
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
                clientAdmission = new SnapshotClientAdmissionGate(
                        expectedFramesPerWindow,
                        streamIdentity).admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                if (!clientAdmission.accepted()) {
                    throw new IOException("persistent headless snapshot socket process client admission rejected: "
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
                HeadlessSnapshotSocketClient.writeMovementCommandResult(socketOutput, clientMovementCommandResult);
                clientMovementCommandResultBytes = HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_BYTES;

                try {
                    for (int i = 0; i < publications.size(); i++) {
                        DirectSocketPublication publication = publications.get(i);
                        HeadlessSnapshotSocketClient.ServerSessionWindowCursor sessionWindowCursor =
                                HeadlessSnapshotSocketClient.ServerSessionWindowCursor.cursor(
                                        clientHandshake,
                                        sessionWindowSessionId,
                                        i,
                                        Math.multiplyExact((long) i, (long) expectedFramesPerWindow),
                                        expectedFramesPerWindow);
                        HeadlessSnapshotSocketClient.writeSessionWindowCursor(
                                socketOutput,
                                sessionWindowCursor);
                        DirectSocketSink sink =
                                new DirectSocketSink(socketOutput, clientHandshake, expectedFramesPerWindow);
                        publication.publishTo(sink);
                        sink.finish();
                        HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(
                                clientHandshake,
                                expectedFramesPerWindow,
                                sink.frames());
                        HeadlessSnapshotSocketClient.ServerStreamCompletion streamCompletion =
                                HeadlessSnapshotSocketClient.ServerStreamCompletion.completed(clientHandshake);
                        HeadlessSnapshotSocketClient.writeStreamCompletion(socketOutput, streamCompletion);
                        senderBytes.add(Long.valueOf(sink.bytesWritten()));
                        senderFrames.add(sink.frames());
                        clientSessionWindowCursors.add(sessionWindowCursor);
                        clientInitialSyncs.add(sink.initialSync());
                        clientStreamCompletions.add(streamCompletion);
                    }
                } catch (UncheckedIOException uioe) {
                    senderFailure = uioe.getCause();
                } catch (IOException ioe) {
                    senderFailure = ioe;
                } catch (RuntimeException rte) {
                    publisherFailure = rte;
                } finally {
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

            clientExitCode = HeadlessSnapshotClientProcessLauncher.waitForClientProcess(clientProcess, clientLogPath);
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
            throw new IOException("persistent headless snapshot socket process client exited " + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null) {
            throw new IOException("persistent headless snapshot socket process client did not complete handshake");
        }
        if (receivedMovementCommand == null) {
            throw new IOException("persistent headless snapshot socket process client movement command was not received");
        }
        if (!receivedMovementCommand.equals(movementCommand)) {
            throw new IOException("persistent headless snapshot socket process client movement command mismatch");
        }
        if (movementCommandResult == null) {
            throw new IOException("persistent headless snapshot socket process client movement command was not consumed");
        }
        if (clientAdmission == null || !clientAdmission.accepted()) {
            throw new IOException("persistent headless snapshot socket process client was not admitted");
        }
        if (senderBytes.size() != receiverArtifactPaths.size()
                || senderFrames.size() != receiverArtifactPaths.size()
                || clientSessionWindowCursors.size() != receiverArtifactPaths.size()
                || clientInitialSyncs.size() != receiverArtifactPaths.size()
                || clientStreamCompletions.size() != receiverArtifactPaths.size()) {
            throw new IOException("persistent session did not publish every requested window");
        }

        Properties clientReceipt = HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(clientReceiptPath);
        HeadlessSnapshotProcessReceiptValidator.validatePersistentProcessClientReceipt(
                clientReceipt,
                socketHost,
                port,
                receiverArtifactPaths,
                expectedFramesPerWindow,
                expectedBytesPerWindow,
                clientHandshake,
                serverPid,
                clientPid);
        HeadlessSnapshotProcessReceiptValidator.requireProcessClientMovementReceipt(clientReceipt, movementCommand, movementCommandResult);

        List<ProcessDirectReceipt> windowReceipts = new ArrayList<>(receiverArtifactPaths.size());
        for (int i = 0; i < receiverArtifactPaths.size(); i++) {
            Path receiverArtifactPath = receiverArtifactPaths.get(i);
            long windowSenderBytes = senderBytes.get(i).longValue();
            if (windowSenderBytes != expectedBytesPerWindow) {
                throw new IOException("persistent session sender byte count mismatch for window " + (i + 1)
                        + ": expected " + expectedBytesPerWindow + ", got " + windowSenderBytes);
            }
            long receiverBytes = Files.size(receiverArtifactPath);
            if (receiverBytes != expectedBytesPerWindow) {
                throw new IOException("persistent session receiver byte count mismatch for window " + (i + 1)
                        + ": expected " + expectedBytesPerWindow + ", got " + receiverBytes);
            }
            List<OfflineCombatStepWireFrame> receiverFrames = OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
            if (receiverFrames.size() != expectedFramesPerWindow) {
                throw new IOException("persistent session receiver frame count mismatch for window " + (i + 1)
                        + ": expected " + expectedFramesPerWindow + ", got " + receiverFrames.size());
            }
            if (!senderFrames.get(i).equals(receiverFrames)) {
                throw new IOException("persistent session receiver frames do not match sender frames for window "
                        + (i + 1));
            }
            DirectReceipt directReceipt = new DirectReceipt(
                    receiverArtifactPath,
                    expectedFramesPerWindow,
                    expectedBytesPerWindow,
                    windowSenderBytes,
                    receiverBytes,
                    socketHost,
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
                    HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES,
                    clientInitialSyncs.get(i),
                    HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES,
                    clientStreamCompletions.get(i),
                    senderFrames.get(i),
                    receiverFrames);
            OfflineCombatStepWireFrame latestFrame = receiverFrames.isEmpty()
                    ? null
                    : receiverFrames.get(receiverFrames.size() - 1);
            windowReceipts.add(new ProcessDirectReceipt(
                    directReceipt,
                    clientReceiptPath,
                    clientLogPath,
                    serverPid,
                    clientPid,
                    clientExitCode,
                    expectedFramesPerWindow,
                    latestFrame == null ? -1L : latestFrame.result().completedTick(),
                    latestFrame == null ? 0L : latestFrame.result().stateHash(),
                    latestFrame == null ? -1 : latestFrame.result().entityCount(),
                    latestFrame == null ? -1 : latestFrame.result().shipCount()));
        }
        return new PersistentCommandedProcessDirectReceipt(
                windowReceipts,
                clientSessionWindowCursors,
                receivedMovementCommand,
                movementCommandResult);
    }

}
