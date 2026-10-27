package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectSocketPublication;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

final class ReconnectResumeCommandTickRunner {

    private ReconnectResumeCommandTickRunner() {
    }

    static OfflineCombatStepWireLocalhostSocketTransport.StaleCommandRejectedReconnectResumeProcessReceipt
            run(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand staleMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        Objects.requireNonNull(staleMovementCommand, "staleMovementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != 2
                || clientReceiptPaths.size() != 2
                || publications.size() != 2) {
            throw new IllegalArgumentException(
                    "stale reconnect command rejection requires exactly two receiver artifacts, receipts, and windows");
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
        OfflineCombatStepWireLocalhostSocketTransport.SessionWindowProcessResult acceptedWindow =
                SessionWindowProcessRunner.runSessionWindowProcessClient(
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

        HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters =
                resumeClaimParametersOrNull(acceptedWindow.sessionWindowCursor());
        try {
            SessionWindowProcessRunner.runSessionWindowProcessClient(
                    receiverArtifactPaths.get(1),
                    clientReceiptPaths.get(1),
                    expectedFramesPerWindow,
                    expectedBytesPerWindow,
                    publications.get(1),
                    staleMovementCommand,
                    commandSink,
                    streamIdentity,
                    socketAddress,
                    serverPid,
                    1,
                    sessionState,
                    resumeClaimParameters);
        } catch (IOException rejection) {
            if (!ServerReconnectSessionState.isMovementCommandRejection(rejection)) {
                throw rejection;
            }
            return new OfflineCombatStepWireLocalhostSocketTransport
                    .StaleCommandRejectedReconnectResumeProcessReceipt(
                            acceptedWindow.processReceipt(),
                            acceptedWindow.sessionWindowCursor(),
                            sessionState.latestCursorOrNull(),
                            resumeClaimParameters,
                            staleMovementCommand,
                            null,
                            rejection.getMessage());
        }
        throw new IOException("server-owned reconnect session state accepted a stale movement command");
    }

    static OfflineCombatStepWireLocalhostSocketTransport.UnauthorizedCommandRejectedReconnectResumeProcessReceipt
            runUnauthorizedShip(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerWindow,
                    List<DirectSocketPublication> publications,
                    HeadlessSnapshotSocketClient.ClientMovementCommand unauthorizedMovementCommand,
                    ShipCommandSink commandSink,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths"));
        clientReceiptPaths = List.copyOf(Objects.requireNonNull(clientReceiptPaths, "clientReceiptPaths"));
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        Objects.requireNonNull(unauthorizedMovementCommand, "unauthorizedMovementCommand");
        Objects.requireNonNull(commandSink, "commandSink");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        if (receiverArtifactPaths.size() != 2
                || clientReceiptPaths.size() != 2
                || publications.size() != 2) {
            throw new IllegalArgumentException(
                    "unauthorized reconnect command rejection requires exactly two receiver artifacts, receipts, and windows");
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
        OfflineCombatStepWireLocalhostSocketTransport.SessionWindowProcessResult acceptedWindow =
                SessionWindowProcessRunner.runSessionWindowProcessClient(
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

        HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters =
                resumeClaimParametersOrNull(acceptedWindow.sessionWindowCursor());
        try {
            SessionWindowProcessRunner.runSessionWindowProcessClient(
                    receiverArtifactPaths.get(1),
                    clientReceiptPaths.get(1),
                    expectedFramesPerWindow,
                    expectedBytesPerWindow,
                    publications.get(1),
                    unauthorizedMovementCommand,
                    commandSink,
                    streamIdentity,
                    socketAddress,
                    serverPid,
                    1,
                    sessionState,
                    resumeClaimParameters);
        } catch (IOException rejection) {
            if (!ServerReconnectSessionState.isMovementCommandRejection(rejection)) {
                throw rejection;
            }
            return new OfflineCombatStepWireLocalhostSocketTransport
                    .UnauthorizedCommandRejectedReconnectResumeProcessReceipt(
                            acceptedWindow.processReceipt(),
                            acceptedWindow.sessionWindowCursor(),
                            sessionState.latestCursorOrNull(),
                            resumeClaimParameters,
                            unauthorizedMovementCommand,
                            sessionState.authorizedCommandShipId(),
                            null,
                            rejection.getMessage());
        }
        throw new IOException("server-owned reconnect session state accepted an unauthorized movement command");
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
}
