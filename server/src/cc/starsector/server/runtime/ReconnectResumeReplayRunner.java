package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepWireLocalhostSocketTransport.DirectSocketPublication;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ReconnectResumeReplayRunner {

    private ReconnectResumeReplayRunner() {
    }

    static OfflineCombatStepWireLocalhostSocketTransport.ReplayRejectedReconnectResumeCommandedProcessDirectReceipt
            run(
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
        if (receiverArtifactPaths.size() != 3
                || clientReceiptPaths.size() != 3
                || publications.size() != 3) {
            throw new IllegalArgumentException(
                    "reconnect replay rejection requires exactly three receiver artifacts, receipts, and windows");
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
        List<OfflineCombatStepWireLocalhostSocketTransport.SessionWindowProcessResult> results =
                new ArrayList<>(2);
        for (int i = 0; i < 2; i++) {
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters =
                    resumeClaimParametersOrNull(sessionState.latestCursorOrNull());
            OfflineCombatStepWireLocalhostSocketTransport.SessionWindowProcessResult result =
                    SessionWindowProcessRunner.runSessionWindowProcessClient(
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

        HeadlessSnapshotSocketClient.SessionResumeClaimParameters replayedClaimParameters =
                resumeClaimParametersOrNull(results.get(0).sessionWindowCursor());
        try {
            SessionWindowProcessRunner.runSessionWindowProcessClient(
                    receiverArtifactPaths.get(2),
                    clientReceiptPaths.get(2),
                    expectedFramesPerWindow,
                    expectedBytesPerWindow,
                    publications.get(2),
                    movementCommand,
                    commandSink,
                    streamIdentity,
                    socketAddress,
                    serverPid,
                    2,
                    sessionState,
                    replayedClaimParameters);
        } catch (IOException rejection) {
            if (!ServerReconnectSessionState.isResumeClaimRejection(rejection)) {
                throw rejection;
            }
            return new OfflineCombatStepWireLocalhostSocketTransport
                    .ReplayRejectedReconnectResumeCommandedProcessDirectReceipt(
                            new OfflineCombatStepWireLocalhostSocketTransport
                                    .ReconnectResumeCommandedProcessDirectReceipt(
                                            List.of(
                                                    results.get(0).processReceipt(),
                                                    results.get(1).processReceipt()),
                                            List.of(
                                                    results.get(0).sessionWindowCursor(),
                                                    results.get(1).sessionWindowCursor()),
                                            results.get(1).resumeClaim(),
                                            results.get(1).receivedMovementCommand(),
                                            results.get(1).movementCommandResult()),
                            replayedClaimParameters,
                            movementCommand,
                            null,
                            rejection.getMessage());
        }
        throw new IOException("server-owned reconnect session state accepted a replayed resume claim");
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
