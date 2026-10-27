package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;
import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.client.SnapshotClientAdmissionGate;
import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.CommandAuthorityDecision;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ConcurrentCommandClaim;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Two-process client session against one authoritative snapshot source.
 */
public final class ConcurrentMultiplayerSessionRunner {
    private static final int CLIENT_COUNT = 2;
    private static final int BACKLOG = 2;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;
    private static final String CLIENT_CONTRACT =
            "cc.starsector.server.client.HeadlessSnapshotSocketClient";

    private ConcurrentMultiplayerSessionRunner() {
    }

    public static SessionReceipt runTwoClientAuthoritativeSession(
            List<Path> receiverArtifactPaths,
            List<Path> clientReceiptPaths,
            int expectedFrames,
            DirectSocketPublication publication,
            List<ClientAuthorityIdentity> identities,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(socketAddress, "socketAddress");
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            return runTwoClientAuthoritativeSessionOnBoundSocket(
                    serverSocket,
                    socketAddress.getHostAddress(),
                    serverSocket.getLocalPort(),
                    receiverArtifactPaths,
                    clientReceiptPaths,
                    expectedFrames,
                    publication,
                    identities,
                    commands,
                    commandSink,
                    streamIdentity);
        }
    }

    /**
     * demo-program R6 token-delivery + R8b ON path, end-to-end: each subprocess client delivers
     * its {@code clientSeatCredentials} entry (seat token + content fingerprint) in its handshake,
     * the admission gate enforces {@code seatAuthority.mintedTokens()} + {@code
     * requiredContentFingerprint} at the door, and {@link SeatAdmission} binds each connection's
     * commandable ship to its seat token (duplicate-seat rejected; arbiter seeded from the token).
     * This is the live consumer that exercises the wiring instead of leaving it dark.
     */
    public static SessionReceipt runTwoClientSeatEnforcedSession(
            List<Path> receiverArtifactPaths,
            List<Path> clientReceiptPaths,
            int expectedFrames,
            DirectSocketPublication publication,
            List<ClientAuthorityIdentity> identities,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            InetAddress socketAddress,
            MatchSeatAuthority seatAuthority,
            List<HeadlessSnapshotSocketClient.SeatCredential> clientSeatCredentials,
            String requiredContentFingerprint) throws IOException {
        Objects.requireNonNull(socketAddress, "socketAddress");
        Objects.requireNonNull(seatAuthority, "seatAuthority");
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(socketAddress, 0), BACKLOG);
            return runTwoClientAuthoritativeSessionOnBoundSocket(
                    serverSocket,
                    socketAddress.getHostAddress(),
                    serverSocket.getLocalPort(),
                    receiverArtifactPaths,
                    clientReceiptPaths,
                    expectedFrames,
                    publication,
                    identities,
                    commands,
                    commandSink,
                    streamIdentity,
                    seatAuthority,
                    requiredContentFingerprint,
                    clientSeatCredentials);
        }
    }

    static SessionReceipt runTwoClientAuthoritativeSessionOnBoundSocket(
            ServerSocket serverSocket,
            String socketHost,
            int port,
            List<Path> receiverArtifactPaths,
            List<Path> clientReceiptPaths,
            int expectedFrames,
            DirectSocketPublication publication,
            List<ClientAuthorityIdentity> identities,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        // Default-off perimeter-trust v0 (every existing caller): no seat ledger, no required
        // content fingerprint -> byte-for-byte the pre-R8b-runner-wiring behavior.
        return runTwoClientAuthoritativeSessionOnBoundSocket(
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
                streamIdentity,
                null,
                "",
                unassertedSeatCredentials());
    }

    /** The default-off per-client credentials: every client carries an unasserted seat credential. */
    private static List<HeadlessSnapshotSocketClient.SeatCredential> unassertedSeatCredentials() {
        return List.of(
                HeadlessSnapshotSocketClient.SeatCredential.unasserted(),
                HeadlessSnapshotSocketClient.SeatCredential.unasserted());
    }

    /**
     * demo-program R8b-runner-wiring (opt-in, default-off — its parts were built dark first):
     * when {@code seatAuthority} is non-null the accept loop binds each connection's
     * commandable ship to its seat token via {@link SeatAdmission} (the token, not the
     * client-asserted {@code ownedShipId}, decides — and a duplicate token is rejected), and the
     * admission gate enforces the seat-token set + (when {@code requiredContentFingerprint} is
     * non-blank) the content-universe fingerprint. Both default off ({@code seatAuthority == null}
     * and a blank fingerprint) reproduce the exact pre-R8 behavior. The ON path is exercised
     * end-to-end by {@link #runTwoClientSeatEnforcedSession} (R6 token-delivery): each subprocess
     * client delivers its {@code clientSeatCredentials} entry (seat token + content fingerprint) in
     * its handshake. {@code clientSeatCredentials} must be one per client; the default-off callers
     * pass unasserted credentials, so the subprocess command line stays byte-identical.
     */
    static SessionReceipt runTwoClientAuthoritativeSessionOnBoundSocket(
            ServerSocket serverSocket,
            String socketHost,
            int port,
            List<Path> receiverArtifactPaths,
            List<Path> clientReceiptPaths,
            int expectedFrames,
            DirectSocketPublication publication,
            List<ClientAuthorityIdentity> identities,
            List<HeadlessSnapshotSocketClient.ClientMovementCommand> commands,
            ShipCommandSink commandSink,
            SnapshotStreamIdentity streamIdentity,
            MatchSeatAuthority seatAuthority,
            String requiredContentFingerprint,
            List<HeadlessSnapshotSocketClient.SeatCredential> clientSeatCredentials) throws IOException {
        Objects.requireNonNull(serverSocket, "serverSocket");
        clientSeatCredentials = List.copyOf(Objects.requireNonNull(clientSeatCredentials, "clientSeatCredentials"));
        if (clientSeatCredentials.size() != CLIENT_COUNT) {
            throw new IllegalArgumentException("concurrent authoritative session requires exactly two seat credentials");
        }
        String enforcedContentFingerprint =
                (requiredContentFingerprint == null || requiredContentFingerprint.isBlank())
                        ? ""
                        : requiredContentFingerprint;
        Set<String> enforcedSeatTokens = (seatAuthority != null) ? seatAuthority.mintedTokens() : Set.of();
        if (socketHost == null || socketHost.isBlank()) {
            throw new IllegalArgumentException("socketHost must be non-blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("socketPort out of range: " + port);
        }
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(
                receiverArtifactPaths,
                "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        identities = List.copyOf(Objects.requireNonNull(identities, "identities"));
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        if (receiverArtifactPaths.size() != CLIENT_COUNT
                || clientReceiptPaths.size() != CLIENT_COUNT
                || identities.size() != CLIENT_COUNT
                || commands.size() != CLIENT_COUNT) {
            throw new IllegalArgumentException("concurrent authoritative session requires exactly two clients");
        }
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        requireDistinctPaths(receiverArtifactPaths, "receiver artifact paths");
        requireDistinctPaths(clientReceiptPaths, "client receipt paths");
        requireDistinctAuthority(identities);
        for (int i = 0; i < CLIENT_COUNT; i++) {
            Objects.requireNonNull(receiverArtifactPaths.get(i), "receiverArtifactPath");
            Objects.requireNonNull(clientReceiptPaths.get(i), "clientReceiptPath");
            Objects.requireNonNull(commands.get(i), "command");
            createParentDirectory(receiverArtifactPaths.get(i));
            createParentDirectory(clientReceiptPaths.get(i));
        }

        long expectedBytes = Math.multiplyExact(
                (long) expectedFrames,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        Path[] clientLogPaths = new Path[CLIENT_COUNT];
        for (int i = 0; i < CLIENT_COUNT; i++) {
            clientLogPaths[i] = siblingWithSuffix(clientReceiptPaths.get(i), ".log");
            Files.deleteIfExists(clientReceiptPaths.get(i));
            Files.deleteIfExists(clientLogPaths[i]);
        }

        long serverPid = ProcessHandle.current().pid();
        long[] clientPids = new long[CLIENT_COUNT];
        int[] clientExitCodes = new int[] { -1, -1 };
        Process[] clientProcesses = new Process[CLIENT_COUNT];
        Socket[] sockets = new Socket[CLIENT_COUNT];
        OutputStream[] socketOutputs = new OutputStream[CLIENT_COUNT];
        HeadlessSnapshotSocketClient.ClientHandshake[] handshakes =
                new HeadlessSnapshotSocketClient.ClientHandshake[CLIENT_COUNT];
        HeadlessSnapshotSocketClient.ClientMovementCommand[] receivedCommands =
                new HeadlessSnapshotSocketClient.ClientMovementCommand[CLIENT_COUNT];
        SnapshotClientAdmissionGate.Admission[] admissions =
                new SnapshotClientAdmissionGate.Admission[CLIENT_COUNT];
        HeadlessSnapshotSocketClient.ServerAdmissionAck[] admissionAcks =
                new HeadlessSnapshotSocketClient.ServerAdmissionAck[CLIENT_COUNT];
        HeadlessSnapshotSocketClient.ServerMovementCommandResult[] movementResults =
                new HeadlessSnapshotSocketClient.ServerMovementCommandResult[CLIENT_COUNT];
        ShipCommandSink.Result[] commandResults = new ShipCommandSink.Result[CLIENT_COUNT];
        HeadlessSnapshotSocketClient.ServerInitialSync[] initialSyncs =
                new HeadlessSnapshotSocketClient.ServerInitialSync[CLIENT_COUNT];
        HeadlessSnapshotSocketClient.ServerStreamCompletion[] streamCompletions =
                new HeadlessSnapshotSocketClient.ServerStreamCompletion[CLIENT_COUNT];
        List<CommandAuthorityDecision> authorityDecisions = List.of();
        List<OfflineCombatStepWireFrame> senderFrames = List.of();
        long senderBytesPerClient = 0L;
        IOException serverFailure = null;
        RuntimeException publisherFailure = null;

        try {
            for (int i = 0; i < CLIENT_COUNT; i++) {
                clientProcesses[i] = HeadlessSnapshotClientProcessLauncher.startHeadlessClientProcess(
                        socketHost,
                        port,
                        receiverArtifactPaths.get(i),
                        clientReceiptPaths.get(i),
                        expectedFrames,
                        streamIdentity,
                        clientLogPaths[i],
                        commands.get(i),
                        null,
                        null,
                        null,
                        identities.get(i),
                        clientSeatCredentials.get(i));
                clientPids[i] = clientProcesses[i].pid();
            }

            try {
                for (int accepted = 0; accepted < CLIENT_COUNT; accepted++) {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                    InputStream socketInput = socket.getInputStream();
                    HeadlessSnapshotSocketClient.ClientHandshake handshake =
                            HeadlessSnapshotSocketClient.readHandshake(socketInput);
                    HeadlessSnapshotSocketClient.ClientMovementCommand receivedCommand =
                            HeadlessSnapshotSocketClient.readMovementCommand(socketInput);
                    int identityIndex = identityIndexOf(identities, handshake.authorityIdentity());
                    if (identityIndex < 0) {
                        throw new IOException("concurrent client advertised unknown authority identity "
                                + handshake.clientAuthorityId());
                    }
                    if (sockets[identityIndex] != null) {
                        throw new IOException("duplicate concurrent client authority identity "
                                + handshake.clientAuthorityId());
                    }
                    sockets[identityIndex] = socket;
                    socketOutputs[identityIndex] = socket.getOutputStream();
                    handshakes[identityIndex] = handshake;
                    receivedCommands[identityIndex] = receivedCommand;
                }

                List<ConcurrentCommandClaim> claims = new ArrayList<>(CLIENT_COUNT);
                for (int i = 0; i < CLIENT_COUNT; i++) {
                    if (handshakes[i] == null || receivedCommands[i] == null || socketOutputs[i] == null) {
                        throw new IOException("concurrent client " + i + " did not complete handshake and command");
                    }
                    ClientAuthorityIdentity claimIdentity = identities.get(i);
                    if (seatAuthority != null) {
                        // R8b: the seat token binds the commandable ship (duplicate-seat rejected;
                        // the arbiter is seeded from the token's seat, not the client-asserted
                        // ownedShipId). SeatAdmissionRejected is an IOException -> the existing
                        // accept-loop failure path closes the sockets.
                        claimIdentity = SeatAdmission.authorize(
                                seatAuthority,
                                handshakes[i].clientAuthorityId(),
                                handshakes[i].seatToken());
                    }
                    claims.add(new ConcurrentCommandClaim(
                            claimIdentity,
                            receivedCommands[i].shipId(),
                            receivedCommands[i].simTick()));
                }
                ConcurrentCommandAuthorityArbiter arbiter =
                        new ConcurrentCommandAuthorityArbiter(
                                NetworkMessageRegistry.authoritativeCombat()
                                        .commandAuthorityRelevanceRule());
                authorityDecisions = arbiter.arbitrate(claims);
                for (CommandAuthorityDecision decision : authorityDecisions) {
                    int identityIndex = identityIndexOf(identities, decision.claim().client());
                    if (identityIndex < 0) {
                        throw new IOException("authority decision references unknown client "
                                + PeerText.quote(decision.claim().client().clientId()));
                    }
                    if (decision.granted()) {
                        ShipCommandSink.Result result = commandSink.submitMovement(
                                decision.claim().targetShipId(),
                                ShipCommandSink.Source.PLAYER,
                                decision.claim().simTick(),
                                receivedCommands[identityIndex].movementIntent());
                        if (result == null) {
                            throw new IOException("ship movement command sink returned null");
                        }
                        commandResults[identityIndex] = result;
                    } else {
                        commandResults[identityIndex] = ShipCommandSink.Result.REJECTED_INVALID;
                    }
                }

                for (int i = 0; i < CLIENT_COUNT; i++) {
                    admissions[i] = new SnapshotClientAdmissionGate(
                            expectedFrames,
                            streamIdentity,
                            NetworkMessageRegistry.authoritativeCombat(),
                            enforcedSeatTokens,
                            enforcedContentFingerprint).admit(handshakes[i]);
                    admissionAcks[i] = HeadlessSnapshotSocketClient.ServerAdmissionAck.fromAdmission(admissions[i]);
                    HeadlessSnapshotSocketClient.writeAdmissionAck(socketOutputs[i], admissionAcks[i]);
                    if (!admissions[i].accepted()) {
                        throw new IOException("concurrent headless snapshot client " + i
                                + " admission rejected: " + admissions[i].reason());
                    }
                    HeadlessSnapshotSocketClient.writeTransportSessionOpened(
                            socketOutputs[i],
                            HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                                    handshakes[i],
                                    socketHost,
                                    port));
                    movementResults[i] = HeadlessSnapshotSocketClient.movementCommandResult(
                            receivedCommands[i],
                            commandResults[i]);
                    HeadlessSnapshotSocketClient.writeMovementCommandResult(
                            socketOutputs[i],
                            movementResults[i]);
                }

                FanoutDirectSocketSink sink = new FanoutDirectSocketSink(
                        Arrays.asList(socketOutputs),
                        Arrays.asList(handshakes),
                        expectedFrames);
                try {
                    publication.publishTo(sink);
                    sink.finish();
                    senderBytesPerClient = sink.bytesWrittenPerClient();
                    senderFrames = sink.frames();
                    List<HeadlessSnapshotSocketClient.ServerInitialSync> publishedInitialSyncs =
                            sink.initialSyncs();
                    for (int i = 0; i < CLIENT_COUNT; i++) {
                        HeadlessSnapshotProcessReceiptValidator.requireStreamIdentityMatchesFrames(
                                handshakes[i],
                                expectedFrames,
                                senderFrames);
                        initialSyncs[i] = publishedInitialSyncs.get(i);
                        streamCompletions[i] =
                                HeadlessSnapshotSocketClient.ServerStreamCompletion.completed(handshakes[i]);
                        HeadlessSnapshotSocketClient.writeStreamCompletion(
                                socketOutputs[i],
                                streamCompletions[i]);
                    }
                } catch (UncheckedIOException uioe) {
                    serverFailure = uioe.getCause();
                } catch (IOException ioe) {
                    serverFailure = ioe;
                } catch (RuntimeException rte) {
                    publisherFailure = rte;
                } finally {
                    if (serverFailure == null && publisherFailure == null) {
                        senderBytesPerClient = sink.bytesWrittenPerClient();
                        senderFrames = sink.frames();
                    }
                }
            } catch (IOException ioe) {
                serverFailure = ioe;
            } finally {
                shutdownOutputs(sockets, serverFailure, publisherFailure);
                closeSockets(sockets, serverFailure);
            }

            for (int i = 0; i < CLIENT_COUNT; i++) {
                clientExitCodes[i] = HeadlessSnapshotClientProcessLauncher.waitForClientProcess(
                        clientProcesses[i],
                        clientLogPaths[i]);
            }
        } finally {
            for (Process clientProcess : clientProcesses) {
                if (clientProcess != null && clientProcess.isAlive()) {
                    clientProcess.destroyForcibly();
                }
            }
        }

        if (serverFailure != null) {
            throw serverFailure;
        }
        if (publisherFailure != null) {
            throw publisherFailure;
        }
        if (senderBytesPerClient != expectedBytes) {
            throw new IOException("concurrent fanout sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytesPerClient);
        }

        List<ProcessDirectReceipt> processReceipts = new ArrayList<>(CLIENT_COUNT);
        List<Long> finalStateHashes = new ArrayList<>(CLIENT_COUNT);
        for (int i = 0; i < CLIENT_COUNT; i++) {
            if (clientExitCodes[i] != 0) {
                throw new IOException("concurrent headless snapshot client " + i + " exited "
                        + clientExitCodes[i] + "; log=" + clientLogPaths[i].toAbsolutePath().normalize());
            }
            if (clientPids[i] <= 0L) {
                throw new IOException("concurrent headless snapshot client " + i + " pid was not recorded");
            }
            long receiverBytes = Files.size(receiverArtifactPaths.get(i));
            if (receiverBytes != expectedBytes) {
                throw new IOException("concurrent receiver artifact " + i + " byte count mismatch: expected "
                        + expectedBytes + ", got " + receiverBytes);
            }
            List<OfflineCombatStepWireFrame> receiverFrames =
                    OfflineCombatStepWireCodec.readFrames(receiverArtifactPaths.get(i));
            if (!senderFrames.equals(receiverFrames)) {
                throw new IOException("concurrent receiver artifact " + i
                        + " frames do not match authoritative sender frames");
            }
            Properties clientReceipt =
                    HeadlessSnapshotProcessReceiptValidator.loadProcessClientReceipt(clientReceiptPaths.get(i));
            HeadlessSnapshotProcessReceiptValidator.validateProcessClientReceipt(
                    clientReceipt,
                    socketHost,
                    port,
                    receiverArtifactPaths.get(i),
                    expectedFrames,
                    expectedBytes,
                    receiverFrames,
                    handshakes[i],
                    serverPid,
                    clientPids[i]);
            HeadlessSnapshotProcessReceiptValidator.requireProcessClientMovementReceipt(
                    clientReceipt,
                    receivedCommands[i],
                    commandResults[i]);
            DirectReceipt directReceipt = new DirectReceipt(
                    receiverArtifactPaths.get(i),
                    expectedFrames,
                    expectedBytes,
                    senderBytesPerClient,
                    receiverBytes,
                    socketHost,
                    port,
                    "socket_input_stream",
                    false,
                    CLIENT_CONTRACT,
                    handshakes[i],
                    admissions[i],
                    HeadlessSnapshotSocketClient.ADMISSION_ACK_BYTES,
                    admissionAcks[i],
                    HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_BYTES,
                    movementResults[i],
                    0,
                    null,
                    0,
                    null,
                    0,
                    null,
                    HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES,
                    initialSyncs[i],
                    HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES,
                    streamCompletions[i],
                    senderFrames,
                    receiverFrames);
            processReceipts.add(new ProcessDirectReceipt(
                    directReceipt,
                    clientReceiptPaths.get(i),
                    clientLogPaths[i],
                    serverPid,
                    clientPids[i],
                    clientExitCodes[i],
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
                            "latest_ship_count")));
            finalStateHashes.add(Long.valueOf(processReceipts.get(i).clientLatestStateHash()));
        }
        requireByteIdentical(receiverArtifactPaths.get(0), receiverArtifactPaths.get(1));
        if (!processReceipts.get(0).receiverFrames().equals(processReceipts.get(1).receiverFrames())) {
            throw new IOException("concurrent clients decoded different authoritative frames");
        }
        if (!finalStateHashes.get(0).equals(finalStateHashes.get(1))) {
            throw new IOException("concurrent clients ended on different authoritative state hashes");
        }
        if (serverPid == clientPids[0] || serverPid == clientPids[1] || clientPids[0] == clientPids[1]) {
            throw new IOException("concurrent session did not use three distinct OS processes");
        }

        return new SessionReceipt(
                serverPid,
                List.of(Long.valueOf(clientPids[0]), Long.valueOf(clientPids[1])),
                clientReceiptPaths,
                socketHost,
                port,
                authorityDecisions,
                senderFrames,
                finalStateHashes,
                processReceipts);
    }

    private static void createParentDirectory(Path path) throws IOException {
        Path parent = path.getParent();
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

    private static void requireDistinctAuthority(List<ClientAuthorityIdentity> identities) {
        for (int i = 0; i < identities.size(); i++) {
            ClientAuthorityIdentity left = Objects.requireNonNull(identities.get(i), "identity");
            for (int j = i + 1; j < identities.size(); j++) {
                ClientAuthorityIdentity right = Objects.requireNonNull(identities.get(j), "identity");
                if (left.clientId().equals(right.clientId())) {
                    throw new IllegalArgumentException("client authority ids must be distinct");
                }
                if (left.ownedShipId() == right.ownedShipId()) {
                    throw new IllegalArgumentException("client authority owned ships must be distinct");
                }
            }
        }
    }

    private static int identityIndexOf(
            List<ClientAuthorityIdentity> identities,
            ClientAuthorityIdentity identity) {
        for (int i = 0; i < identities.size(); i++) {
            if (identities.get(i).equals(identity)) {
                return i;
            }
        }
        return -1;
    }

    private static void shutdownOutputs(
            Socket[] sockets,
            IOException ioFailure,
            RuntimeException runtimeFailure) {
        for (Socket socket : sockets) {
            if (socket == null) {
                continue;
            }
            try {
                socket.shutdownOutput();
            } catch (IOException shutdownFailure) {
                if (ioFailure != null) {
                    ioFailure.addSuppressed(shutdownFailure);
                } else if (runtimeFailure != null) {
                    runtimeFailure.addSuppressed(shutdownFailure);
                }
            }
        }
    }

    private static void closeSockets(Socket[] sockets, IOException ioFailure) {
        for (Socket socket : sockets) {
            if (socket == null) {
                continue;
            }
            try {
                socket.close();
            } catch (IOException closeFailure) {
                if (ioFailure != null) {
                    ioFailure.addSuppressed(closeFailure);
                }
            }
        }
    }

    private static void requireByteIdentical(Path left, Path right) throws IOException {
        byte[] leftBytes = Files.readAllBytes(left);
        byte[] rightBytes = Files.readAllBytes(right);
        if (!Arrays.equals(leftBytes, rightBytes)) {
            throw new IOException("concurrent receiver artifacts are not byte-identical");
        }
    }

    public record SessionReceipt(
            long serverPid,
            List<Long> clientPids,
            List<Path> clientReceiptPaths,
            String socketHost,
            int socketPort,
            List<CommandAuthorityDecision> authorityDecisions,
            List<OfflineCombatStepWireFrame> sharedFrames,
            List<Long> clientFinalStateHashes,
            List<ProcessDirectReceipt> processReceipts) {

        public SessionReceipt {
            clientPids = List.copyOf(Objects.requireNonNull(clientPids, "clientPids"));
            clientReceiptPaths = List.copyOf(Objects.requireNonNull(
                    clientReceiptPaths,
                    "clientReceiptPaths"));
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            authorityDecisions = List.copyOf(Objects.requireNonNull(
                    authorityDecisions,
                    "authorityDecisions"));
            sharedFrames = List.copyOf(Objects.requireNonNull(sharedFrames, "sharedFrames"));
            clientFinalStateHashes = List.copyOf(Objects.requireNonNull(
                    clientFinalStateHashes,
                    "clientFinalStateHashes"));
            processReceipts = List.copyOf(Objects.requireNonNull(processReceipts, "processReceipts"));
            if (serverPid <= 0L) {
                throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
            }
            if (clientPids.size() != CLIENT_COUNT
                    || clientReceiptPaths.size() != CLIENT_COUNT
                    || clientFinalStateHashes.size() != CLIENT_COUNT
                    || processReceipts.size() != CLIENT_COUNT) {
                throw new IllegalArgumentException("session receipt requires exactly two clients");
            }
            if (serverPid == clientPids.get(0).longValue()
                    || serverPid == clientPids.get(1).longValue()
                    || clientPids.get(0).equals(clientPids.get(1))) {
                throw new IllegalArgumentException("session receipt requires three distinct OS processes");
            }
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must be non-blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (!clientFinalStateHashes.get(0).equals(clientFinalStateHashes.get(1))) {
                throw new IllegalArgumentException("client final state hashes must converge");
            }
            if (authorityDecisions.size() != CLIENT_COUNT) {
                throw new IllegalArgumentException("session receipt requires one authority decision per client");
            }
            for (int i = 0; i < CLIENT_COUNT; i++) {
                if (processReceipts.get(i).serverPid() != serverPid
                        || processReceipts.get(i).clientPid() != clientPids.get(i).longValue()) {
                    throw new IllegalArgumentException("process receipt pid mismatch at client " + i);
                }
                if (!processReceipts.get(i).receiverFrames().equals(sharedFrames)) {
                    throw new IllegalArgumentException("process receipt frames must match shared frames");
                }
            }
        }
    }
}
