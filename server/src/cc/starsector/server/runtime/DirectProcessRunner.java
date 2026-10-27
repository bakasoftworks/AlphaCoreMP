package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.client.SnapshotClientAdmissionGate;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.CommandedProcessDirectReceipt;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectReceipt;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectSocketPublication;
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
import java.util.List;
import java.util.Objects;
import java.util.Properties;

final class DirectProcessRunner {
    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private DirectProcessRunner() {
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
    static ProcessDirectReceipt sendDirectToProcess(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }

        long expectedBytes = Math.multiplyExact((long) expectedFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
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

        InetAddress loopback = InetAddress.getLoopbackAddress();
        IOException senderFailure = null;
        RuntimeException publisherFailure = null;
        long senderBytes = 0L;
        long receiverBytes = 0L;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        List<OfflineCombatStepWireFrame> receiverFrames = List.of();
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        HeadlessSnapshotSocketClient.ServerInitialSync clientInitialSync = null;
        int clientInitialSyncBytes = 0;
        HeadlessSnapshotSocketClient.ServerStreamCompletion clientStreamCompletion = null;
        int clientStreamCompletionBytes = 0;
        long serverPid = ProcessHandle.current().pid();
        long clientPid = -1L;
        int clientExitCode = -1;
        int port;
        Process clientProcess = null;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(loopback, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientProcess = HeadlessSnapshotClientProcessLauncher.startHeadlessClientProcess(
                    loopback.getHostAddress(),
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    streamIdentity,
                    clientLogPath);
            clientPid = clientProcess.pid();

            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socket.getInputStream());
                OutputStream socketOutput = socket.getOutputStream();
                clientAdmission =
                        new SnapshotClientAdmissionGate(expectedFrames, streamIdentity).admit(clientHandshake);
                clientAdmissionAck = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(clientAdmission);
                HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutput, clientAdmissionAck);
                clientAdmissionAckBytes = HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES;
                if (!clientAdmission.accepted()) {
                    throw new IOException("headless snapshot socket process client admission rejected: "
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
            throw new IOException("headless snapshot socket process client exited " + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null) {
            throw new IOException("headless snapshot socket process client did not complete handshake");
        }
        if (clientAdmission == null || !clientAdmission.accepted()) {
            throw new IOException("headless snapshot socket process client was not admitted");
        }
        if (senderBytes != expectedBytes) {
            throw new IOException("offline step process direct socket sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        receiverBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("offline step process direct socket receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        receiverFrames = OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
        if (receiverFrames.size() != expectedFrames) {
            throw new IOException("offline step process direct socket receiver frame count mismatch: expected "
                    + expectedFrames + ", got " + receiverFrames.size());
        }
        if (!senderFrames.equals(receiverFrames)) {
            throw new IOException("offline step process direct socket receiver frames do not match sender frames");
        }
        Properties clientReceipt = HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(clientReceiptPath);
        HeadlessSnapshotProcessReceiptValidator.validateProcessClientReceipt(
                clientReceipt,
                loopback.getHostAddress(),
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
                loopback.getHostAddress(),
                port,
                "socket_input_stream",
                false,
                "cc.starsector.server.client.HeadlessSnapshotSocketClient",
                clientHandshake,
                clientAdmission,
                clientAdmissionAckBytes,
                clientAdmissionAck,
                0,
                null,
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
        return new ProcessDirectReceipt(
                directReceipt,
                clientReceiptPath,
                clientLogPath,
                serverPid,
                clientPid,
                clientExitCode,
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "frames_read"),
                HeadlessSnapshotProcessReceiptValidator.requireLongProperty(clientReceipt, "latest_completed_tick"),
                HeadlessSnapshotProcessReceiptValidator.requireLongProperty(clientReceipt, "latest_state_hash"),
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "latest_entity_count"),
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "latest_ship_count"));
    }

    static CommandedProcessDirectReceipt sendDirectToProcessWithClientFireCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(fireCommand, "fireCommand");
        return sendDirectToProcessWithClientCommands(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                null,
                null,
                fireCommand,
                null,
                commandSink,
                streamIdentity,
                socketAddress);
    }

    static CommandedProcessDirectReceipt sendDirectToProcessWithClientSystemCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(systemCommand, "systemCommand");
        return sendDirectToProcessWithClientCommands(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                null,
                null,
                null,
                systemCommand,
                commandSink,
                streamIdentity,
                socketAddress);
    }

    static CommandedProcessDirectReceipt sendDirectToProcessWithClientMovementCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(movementCommand, "movementCommand");
        return sendDirectToProcessWithClientCommands(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                movementCommand,
                null,
                null,
                null,
                commandSink,
                streamIdentity,
                socketAddress);
    }

    static CommandedProcessDirectReceipt sendDirectToProcessWithClientWeaponGroupCommand(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(weaponGroupCommand, "weaponGroupCommand");
        return sendDirectToProcessWithClientCommands(
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                publication,
                null,
                weaponGroupCommand,
                null,
                null,
                commandSink,
                streamIdentity,
                socketAddress);
    }

    private static CommandedProcessDirectReceipt sendDirectToProcessWithClientCommands(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            DirectSocketPublication publication,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (movementCommand == null && weaponGroupCommand == null && fireCommand == null && systemCommand == null) {
            throw new IllegalArgumentException("at least one client command must be provided");
        }
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }

        long expectedBytes = Math.multiplyExact((long) expectedFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
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
        long receiverBytes = 0L;
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        List<OfflineCombatStepWireFrame> receiverFrames = List.of();
        HeadlessSnapshotSocketClient.ClientHandshake clientHandshake = null;
        HeadlessSnapshotSocketClient.ClientMovementCommand receivedMovementCommand = null;
        ShipCommandSink.Result movementCommandResult = null;
        HeadlessSnapshotSocketClient.ClientWeaponGroupCommand receivedWeaponGroupCommand = null;
        ShipCommandSink.Result weaponGroupCommandResult = null;
        HeadlessSnapshotSocketClient.ClientFireCommand receivedFireCommand = null;
        ShipCommandSink.Result fireCommandResult = null;
        HeadlessSnapshotSocketClient.ClientSystemCommand receivedSystemCommand = null;
        ShipCommandSink.Result systemCommandResult = null;
        SnapshotClientAdmissionGate.Admission clientAdmission = null;
        HeadlessSnapshotSocketClient.ServerAdmissionAck clientAdmissionAck = null;
        int clientAdmissionAckBytes = 0;
        HeadlessSnapshotSocketClient.ServerMovementCommandResult clientMovementCommandResult = null;
        int clientMovementCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ServerWeaponGroupCommandResult clientWeaponGroupCommandResult = null;
        int clientWeaponGroupCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ServerFireCommandResult clientFireCommandResult = null;
        int clientFireCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ServerSystemCommandResult clientSystemCommandResult = null;
        int clientSystemCommandResultBytes = 0;
        HeadlessSnapshotSocketClient.ServerInitialSync clientInitialSync = null;
        int clientInitialSyncBytes = 0;
        HeadlessSnapshotSocketClient.ServerStreamCompletion clientStreamCompletion = null;
        int clientStreamCompletionBytes = 0;
        long serverPid = ProcessHandle.current().pid();
        long clientPid = -1L;
        int clientExitCode = -1;
        int port;
        Process clientProcess = null;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientProcess = HeadlessSnapshotClientProcessLauncher.startHeadlessClientProcess(
                    socketHost,
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    streamIdentity,
                    clientLogPath,
                    movementCommand,
                    weaponGroupCommand,
                    fireCommand,
                    systemCommand);
            clientPid = clientProcess.pid();

            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                InputStream socketInput = socket.getInputStream();
                clientHandshake = HeadlessSnapshotSocketClient.readHandshake(socketInput);
                if (movementCommand != null) {
                    receivedMovementCommand = HeadlessSnapshotSocketClient.readMovementCommand(socketInput);
                    movementCommandResult = commandSink.submitMovement(
                            receivedMovementCommand.shipId(),
                            ShipCommandSink.Source.PLAYER,
                            receivedMovementCommand.simTick(),
                            receivedMovementCommand.movementIntent());
                    if (movementCommandResult == null) {
                        throw new IOException("ship movement command sink returned null");
                    }
                }
                if (weaponGroupCommand != null) {
                    receivedWeaponGroupCommand = HeadlessSnapshotSocketClient.readWeaponGroupCommand(socketInput);
                    weaponGroupCommandResult = commandSink.selectWeaponGroup(
                            receivedWeaponGroupCommand.shipId(),
                            ShipCommandSink.Source.PLAYER,
                            receivedWeaponGroupCommand.simTick(),
                            receivedWeaponGroupCommand.group());
                    if (weaponGroupCommandResult == null) {
                        throw new IOException("ship weapon-group command sink returned null");
                    }
                }
                if (fireCommand != null) {
                    receivedFireCommand = HeadlessSnapshotSocketClient.readFireCommand(socketInput);
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
                    receivedSystemCommand = HeadlessSnapshotSocketClient.readSystemCommand(socketInput);
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
                    throw new IOException("headless snapshot socket process client admission rejected: "
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
                if (receivedWeaponGroupCommand != null) {
                    clientWeaponGroupCommandResult =
                            HeadlessSnapshotSocketClient.weaponGroupCommandResult(
                                    receivedWeaponGroupCommand,
                                    weaponGroupCommandResult);
                    HeadlessSnapshotSocketClient.writeWeaponGroupCommandResult(
                            socketOutput,
                            clientWeaponGroupCommandResult);
                    clientWeaponGroupCommandResultBytes =
                            HeadlessSnapshotSocketClient.WEAPON_GROUP_COMMAND_RESULT_BYTES;
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
            throw new IOException("headless snapshot socket process client exited " + clientExitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (clientHandshake == null) {
            throw new IOException("headless snapshot socket process client did not complete handshake");
        }
        if (movementCommand != null) {
            if (receivedMovementCommand == null) {
                throw new IOException("headless snapshot socket process client movement command was not received");
            }
            if (!receivedMovementCommand.equals(movementCommand)) {
                throw new IOException("headless snapshot socket process client movement command mismatch");
            }
            if (movementCommandResult == null) {
                throw new IOException("headless snapshot socket process client movement command was not consumed");
            }
        }
        if (weaponGroupCommand != null) {
            if (receivedWeaponGroupCommand == null) {
                throw new IOException("headless snapshot socket process client weapon-group command was not received");
            }
            if (!receivedWeaponGroupCommand.equals(weaponGroupCommand)) {
                throw new IOException("headless snapshot socket process client weapon-group command mismatch");
            }
            if (weaponGroupCommandResult == null) {
                throw new IOException("headless snapshot socket process client weapon-group command was not consumed");
            }
        }
        if (fireCommand != null) {
            if (receivedFireCommand == null) {
                throw new IOException("headless snapshot socket process client fire command was not received");
            }
            if (!receivedFireCommand.equals(fireCommand)) {
                throw new IOException("headless snapshot socket process client fire command mismatch");
            }
            if (fireCommandResult == null) {
                throw new IOException("headless snapshot socket process client fire command was not consumed");
            }
        }
        if (systemCommand != null) {
            if (receivedSystemCommand == null) {
                throw new IOException("headless snapshot socket process client system command was not received");
            }
            if (!receivedSystemCommand.equals(systemCommand)) {
                throw new IOException("headless snapshot socket process client system command mismatch");
            }
            if (systemCommandResult == null) {
                throw new IOException("headless snapshot socket process client system command was not consumed");
            }
        }
        if (clientAdmission == null || !clientAdmission.accepted()) {
            throw new IOException("headless snapshot socket process client was not admitted");
        }
        if (senderBytes != expectedBytes) {
            throw new IOException("offline step process commanded socket sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        receiverBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("offline step process commanded socket receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        receiverFrames = OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
        if (receiverFrames.size() != expectedFrames) {
            throw new IOException("offline step process commanded socket receiver frame count mismatch: expected "
                    + expectedFrames + ", got " + receiverFrames.size());
        }
        if (!senderFrames.equals(receiverFrames)) {
            throw new IOException("offline step process commanded socket receiver frames do not match sender frames");
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
        if (movementCommand != null) {
            HeadlessSnapshotProcessReceiptValidator.requireProcessClientMovementReceipt(
                    clientReceipt,
                    movementCommand,
                    movementCommandResult);
        }
        if (weaponGroupCommand != null) {
            HeadlessSnapshotProcessReceiptValidator.requireProcessClientWeaponGroupReceipt(
                    clientReceipt,
                    weaponGroupCommand,
                    weaponGroupCommandResult);
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
                clientWeaponGroupCommandResultBytes,
                clientWeaponGroupCommandResult,
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
        ProcessDirectReceipt processReceipt = new ProcessDirectReceipt(
                directReceipt,
                clientReceiptPath,
                clientLogPath,
                serverPid,
                clientPid,
                clientExitCode,
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "frames_read"),
                HeadlessSnapshotProcessReceiptValidator.requireLongProperty(clientReceipt, "latest_completed_tick"),
                HeadlessSnapshotProcessReceiptValidator.requireLongProperty(clientReceipt, "latest_state_hash"),
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "latest_entity_count"),
                HeadlessSnapshotProcessReceiptValidator.requireIntProperty(clientReceipt, "latest_ship_count"));
        return new CommandedProcessDirectReceipt(
                processReceipt,
                receivedMovementCommand,
                movementCommandResult,
                receivedWeaponGroupCommand,
                weaponGroupCommandResult,
                receivedFireCommand,
                fireCommandResult,
                receivedSystemCommand,
                systemCommandResult);
    }

}
