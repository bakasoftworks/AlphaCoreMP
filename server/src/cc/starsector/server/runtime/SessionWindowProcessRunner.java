package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.client.SnapshotClientAdmissionGate;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectReceipt;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectSocketPublication;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.ProcessDirectReceipt;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.SessionWindowProcessResult;
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
import java.util.List;
import java.util.Objects;
import java.util.Properties;

final class SessionWindowProcessRunner {
    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private SessionWindowProcessRunner() {
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
    static SessionWindowProcessResult runSessionWindowProcessClient(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            long expectedBytes,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress,
            long serverPid,
            int windowOrdinal,
            ServerReconnectSessionState sessionState,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        return runSessionWindowProcessClient(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                expectedBytes,
                publication,
                movementCommand,
                null,
                null,
                commandSink,
                streamIdentity,
                socketAddress,
                serverPid,
                windowOrdinal,
                sessionState,
                resumeClaimParameters);
    }

    static SessionWindowProcessResult runSessionWindowProcessClient(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            long expectedBytes,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress,
            long serverPid,
            int windowOrdinal,
            ServerReconnectSessionState sessionState,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        Objects.requireNonNull(sessionState, "sessionState");
        boolean hasCommand = movementCommand != null || fireCommand != null || systemCommand != null;
        if (hasCommand != (commandSink != null)) {
            throw new IllegalArgumentException("client commands and commandSink must be provided together");
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
        Files.deleteIfExists(clientReceiptPath);
        Files.deleteIfExists(clientLogPath);

        String socketHost = socketAddress.getHostAddress();
        IOException senderFailure = null;
        RuntimeException publisherFailure = null;
        long senderBytes = 0L;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        List<OfflineCombatStepWireFrame> receiverFrames;
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        HeadlessSnapshotSocketClient.ClientMovementCommand receivedMovementCommand = null;
        ShipCommandSink.Result movementCommandResult = null;
        HeadlessSnapshotSocketClient.ClientFireCommand receivedFireCommand = null;
        ShipCommandSink.Result fireCommandResult = null;
        HeadlessSnapshotSocketClient.ClientSystemCommand receivedSystemCommand = null;
        ShipCommandSink.Result systemCommandResult = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        HeadlessSnapshotSocketClient.ServerMovementCommandResult clientMovementCommandResult = null;
        int clientMovementCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ServerFireCommandResult clientFireCommandResult = null;
        int clientFireCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ServerSystemCommandResult clientSystemCommandResult = null;
        int clientSystemCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ClientSessionResumeClaim resumeClaim = null;
        HeadlessSnapshotSocketClient.ServerSessionWindowCursor sessionWindowCursor = null;
        HeadlessSnapshotSocketClient.ServerInitialSync clientInitialSync = null;
        int clientInitialSyncBytes = 0;
        HeadlessSnapshotSocketClient.ServerStreamCompletion clientStreamCompletion = null;
        int clientStreamCompletionBytes = 0;
        long clientPid = -1L;
        int clientExitCode = -1;
        int port;
        Process clientProcess = null;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientProcess = HeadlessSnapshotClientProcessLauncher.startSessionWindowHeadlessClientProcess(
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    streamIdentity,
                    clientLogPath,
                    movementCommand,
                    null,
                    fireCommand,
                    systemCommand,
                    resumeClaimParameters);
            clientPid = clientProcess.pid();

            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                InputStream socketInput = socket.getInputStream();
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socketInput);
                if (resumeClaimParameters != null) {
                    resumeClaim = HeadlessSnapshotSocketClient.readSessionResumeClaim(socketInput, clientHandshake);
                    sessionState.validateResumeClaim(resumeClaim);
                    sessionWindowCursor = sessionState.issueCursor(clientHandshake, windowOrdinal);
                }
                if (movementCommand != null) {
                    sessionState.requireResumeClaimForMovementCommand(windowOrdinal, resumeClaim);
                    receivedMovementCommand = HeadlessSnapshotSocketClient.readMovementCommand(socketInput);
                    if (sessionWindowCursor != null) {
                        sessionState.validateMovementCommand(receivedMovementCommand, sessionWindowCursor);
                    }
                    movementCommandResult = commandSink.submitMovement(
                            receivedMovementCommand.shipId(),
                            ShipCommandSink.Source.PLAYER,
                            receivedMovementCommand.simTick(),
                            receivedMovementCommand.movementIntent());
                    if (movementCommandResult == null) {
                        throw new IOException("ship movement command sink returned null");
                    }
                }
                if (fireCommand != null) {
                    sessionState.requireResumeClaimForMovementCommand(windowOrdinal, resumeClaim);
                    receivedFireCommand = HeadlessSnapshotSocketClient.readFireCommand(socketInput);
                    if (sessionWindowCursor != null) {
                        sessionState.validateFireCommand(receivedFireCommand, sessionWindowCursor);
                    }
                    fireCommandResult = commandSink.submitFire(
                            receivedFireCommand.shipId(),
                            ShipCommandSink.Source.PLAYER,
                            receivedFireCommand.simTick(),
                            receivedFireCommand.fire());
                    if (fireCommandResult == null) {
                        throw new IOException("ship fire command sink returned null");
                    }
                }
                if (systemCommand != null) {
                    sessionState.requireResumeClaimForMovementCommand(windowOrdinal, resumeClaim);
                    receivedSystemCommand = HeadlessSnapshotSocketClient.readSystemCommand(socketInput);
                    if (sessionWindowCursor != null) {
                        sessionState.validateSystemCommand(receivedSystemCommand, sessionWindowCursor);
                    }
                    systemCommandResult = commandSink.activateSystem(
                            receivedSystemCommand.shipId(),
                            ShipCommandSink.Source.PLAYER,
                            receivedSystemCommand.simTick(),
                            receivedSystemCommand.systemId());
                    if (systemCommandResult == null) {
                        throw new IOException("ship system command sink returned null");
                    }
                }

                OutputStream socketOutput = socket.getOutputStream();
                clientAdmission =
                        new SnapshotClientAdmissionGate(expectedFrames, streamIdentity).admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                if (!clientAdmission.accepted()) {
                    throw new IOException("session-window headless snapshot process client admission rejected: "
                            + clientAdmission.reason());
                }
                HeadlessSnapshotSocketClient.writeTransportSessionOpened(
                        socketOutput,
                        HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                                clientHandshake,
                                socketHost,
                                port));
                if (receivedMovementCommand != null) {
                    clientMovementCommandResult =
                            HeadlessSnapshotSocketClient.movementCommandResult(
                                    receivedMovementCommand,
                                    movementCommandResult);
                    HeadlessSnapshotSocketClient.writeMovementCommandResult(socketOutput, clientMovementCommandResult);
                    clientMovementCommandResultBytes = HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_BYTES;
                }
                if (receivedFireCommand != null) {
                    clientFireCommandResult =
                            HeadlessSnapshotSocketClient.fireCommandResult(
                                    receivedFireCommand,
                                    fireCommandResult);
                    HeadlessSnapshotSocketClient.writeFireCommandResult(socketOutput, clientFireCommandResult);
                    clientFireCommandResultBytes = HeadlessSnapshotSocketClient.FIRE_COMMAND_RESULT_BYTES;
                }
                if (receivedSystemCommand != null) {
                    clientSystemCommandResult =
                            HeadlessSnapshotSocketClient.systemCommandResult(
                                    receivedSystemCommand,
                                    systemCommandResult);
                    HeadlessSnapshotSocketClient.writeSystemCommandResult(socketOutput, clientSystemCommandResult);
                    clientSystemCommandResultBytes = HeadlessSnapshotSocketClient.SYSTEM_COMMAND_RESULT_BYTES;
                }

                if (sessionWindowCursor == null) {
                    sessionWindowCursor = sessionState.issueCursor(clientHandshake, windowOrdinal);
                }
                HeadlessSnapshotSocketClient.writeSessionWindowCursor(socketOutput, sessionWindowCursor);
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
            throw new IOException("session-window headless snapshot socket process client exited "
                    + clientExitCode + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null) {
            throw new IOException("session-window process client did not complete handshake");
        }
        if (movementCommand != null) {
            if (receivedMovementCommand == null || !receivedMovementCommand.equals(movementCommand)) {
                throw new IOException("session-window process client movement command mismatch");
            }
            if (movementCommandResult == null) {
                throw new IOException("session-window process client movement command was not consumed");
            }
        }
        if (fireCommand != null) {
            if (receivedFireCommand == null || !receivedFireCommand.equals(fireCommand)) {
                throw new IOException("session-window process client fire command mismatch");
            }
            if (fireCommandResult == null) {
                throw new IOException("session-window process client fire command was not consumed");
            }
        }
        if (systemCommand != null) {
            if (receivedSystemCommand == null || !receivedSystemCommand.equals(systemCommand)) {
                throw new IOException("session-window process client system command mismatch");
            }
            if (systemCommandResult == null) {
                throw new IOException("session-window process client system command was not consumed");
            }
        }
        if (resumeClaimParameters != null && resumeClaim == null) {
            throw new IOException("session-window process client did not send resume claim");
        }
        if (clientAdmission == null || !clientAdmission.accepted()) {
            throw new IOException("session-window process client was not admitted");
        }
        if (sessionWindowCursor == null) {
            throw new IOException("session-window process did not publish a cursor");
        }
        if (senderBytes != expectedBytes) {
            throw new IOException("session-window process sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        long receiverBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("session-window process receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        receiverFrames = OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
        if (receiverFrames.size() != expectedFrames) {
            throw new IOException("session-window process receiver frame count mismatch: expected "
                    + expectedFrames + ", got " + receiverFrames.size());
        }
        if (!senderFrames.equals(receiverFrames)) {
            throw new IOException("session-window process receiver frames do not match sender frames");
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
        HeadlessSnapshotProcessReceiptValidator.validateSessionWindowProcessClientReceipt(
                clientReceipt,
                sessionWindowCursor,
                resumeClaim);
        if (movementCommand != null) {
            HeadlessSnapshotProcessReceiptValidator.requireProcessClientMovementReceipt(clientReceipt, movementCommand, movementCommandResult);
        }
        if (fireCommand != null) {
            HeadlessSnapshotProcessReceiptValidator.requireProcessClientFireReceipt(
                    clientReceipt,
                    fireCommand,
                    fireCommandResult);
        }
        if (systemCommand != null) {
            HeadlessSnapshotProcessReceiptValidator.requireProcessClientSystemReceipt(
                    clientReceipt,
                    systemCommand,
                    systemCommandResult);
        }

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
                "cc.starsector.server.client.HeadlessSnapshotSocketClient",
                clientHandshake,
                clientAdmission,
                clientAdmissionAckBytes,
                clientAdmissionAck,
                clientMovementCommandResultBytes,
                clientMovementCommandResult,
                0,
                null,
                clientFireCommandResultBytes,
                clientFireCommandResult,
                clientSystemCommandResultBytes,
                clientSystemCommandResult,
                clientInitialSyncBytes,
                clientInitialSync,
                clientStreamCompletionBytes,
                clientStreamCompletion,
                senderFrames,
                receiverFrames);
        OfflineCombatStepWireFrame latestFrame = receiverFrames.isEmpty()
                ? null
                : receiverFrames.get(receiverFrames.size() - 1);
        ProcessDirectReceipt processReceipt = new ProcessDirectReceipt(
                directReceipt,
                clientReceiptPath,
                clientLogPath,
                serverPid,
                clientPid,
                clientExitCode,
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "frames_read"),
                latestFrame == null ? -1L : latestFrame.result().completedTick(),
                latestFrame == null ? 0L : latestFrame.result().stateHash(),
                latestFrame == null ? -1 : latestFrame.result().entityCount(),
                latestFrame == null ? -1 : latestFrame.result().shipCount());
        return new SessionWindowProcessResult(
                processReceipt,
                sessionWindowCursor,
                resumeClaim,
                receivedMovementCommand,
                movementCommandResult,
                receivedFireCommand,
                fireCommandResult,
                receivedSystemCommand,
                systemCommandResult);
    }

}
