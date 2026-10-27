package cc.starsector.server.runtime;

import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Server-owned authority for reconnect session windows.
 */
public final class ServerReconnectSessionState {

    public static final long DEFAULT_AUTHORIZED_COMMAND_SHIP_ID = 1L;

    private static final String REJECTION_MESSAGE =
            "server-owned reconnect session state rejected resume claim";
    private static final String MOVEMENT_COMMAND_REJECTION_MESSAGE =
            "server-owned reconnect session state rejected movement command";

    private final int expectedFramesPerWindow;
    private final long serverPid;
    private final long authorizedCommandShipId;
    private final List<HeadlessSnapshotSocketClient.ServerSessionWindowCursor> issuedCursors =
            new ArrayList<>();
    private Long sessionId;

    private ServerReconnectSessionState(
            int expectedFramesPerWindow,
            long serverPid,
            long authorizedCommandShipId) {
        if (expectedFramesPerWindow < 0) {
            throw new IllegalArgumentException(
                    "expectedFramesPerWindow must be >= 0; got " + expectedFramesPerWindow);
        }
        if (serverPid <= 0L) {
            throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
        }
        if (authorizedCommandShipId <= 0L) {
            throw new IllegalArgumentException(
                    "authorizedCommandShipId must be > 0; got " + authorizedCommandShipId);
        }
        this.expectedFramesPerWindow = expectedFramesPerWindow;
        this.serverPid = serverPid;
        this.authorizedCommandShipId = authorizedCommandShipId;
    }

    public static ServerReconnectSessionState create(int expectedFramesPerWindow, long serverPid) {
        return create(expectedFramesPerWindow, serverPid, DEFAULT_AUTHORIZED_COMMAND_SHIP_ID);
    }

    public static ServerReconnectSessionState create(
            int expectedFramesPerWindow,
            long serverPid,
            long authorizedCommandShipId) {
        return new ServerReconnectSessionState(expectedFramesPerWindow, serverPid, authorizedCommandShipId);
    }

    public static boolean isResumeClaimRejection(Throwable failure) {
        return containsMessage(failure, REJECTION_MESSAGE);
    }

    public static boolean isMovementCommandRejection(Throwable failure) {
        return containsMessage(failure, MOVEMENT_COMMAND_REJECTION_MESSAGE);
    }

    private static boolean containsMessage(Throwable failure, String expectedMessage) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(expectedMessage)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public HeadlessSnapshotSocketClient.ServerSessionWindowCursor issueCursor(
            HeadlessSnapshotSocketClient.ClientHandshake handshake,
            int windowOrdinal) throws IOException {
        Objects.requireNonNull(handshake, "handshake");
        int expectedWindowOrdinal = issuedCursors.size();
        if (windowOrdinal != expectedWindowOrdinal) {
            throw new IOException("server reconnect session cursor ordinal mismatch: expected "
                    + expectedWindowOrdinal + ", got " + windowOrdinal);
        }

        long previousResumeCursor = 0L;
        if (issuedCursors.isEmpty()) {
            sessionId = HeadlessSnapshotSocketClient.createSessionWindowSessionId(handshake, serverPid);
        } else {
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor previous =
                    issuedCursors.get(issuedCursors.size() - 1);
            previousResumeCursor = previous.nextResumeCursor();
        }

        HeadlessSnapshotSocketClient.ServerSessionWindowCursor cursor =
                HeadlessSnapshotSocketClient.ServerSessionWindowCursor.cursor(
                        handshake,
                        sessionId.longValue(),
                        windowOrdinal,
                        previousResumeCursor,
                        expectedFramesPerWindow);
        issuedCursors.add(cursor);
        return cursor;
    }

    public ResumeClaimValidation validateResumeClaim(
            HeadlessSnapshotSocketClient.ClientSessionResumeClaim claim) throws IOException {
        Objects.requireNonNull(claim, "claim");
        HeadlessSnapshotSocketClient.ServerSessionWindowCursor expected = latestCursorOrNull();
        if (expected == null) {
            throw new IOException(REJECTION_MESSAGE + ": no issued cursor");
        }
        try {
            HeadlessSnapshotSocketClient.requireSessionResumeClaimMatchesCursor(claim, expected);
        } catch (IOException mismatch) {
            throw new IOException(REJECTION_MESSAGE, mismatch);
        }
        return new ResumeClaimValidation(claim, expected);
    }

    public MovementCommandValidation validateMovementCommand(
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor) throws IOException {
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(issuedCursor, "issuedCursor");
        validateCommandSubjectAndTick(movementCommand.shipId(), movementCommand.simTick(), issuedCursor);
        return new MovementCommandValidation(movementCommand, issuedCursor);
    }

    public FireCommandValidation validateFireCommand(
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor) throws IOException {
        Objects.requireNonNull(fireCommand, "fireCommand");
        Objects.requireNonNull(issuedCursor, "issuedCursor");
        validateCommandSubjectAndTick(fireCommand.shipId(), fireCommand.simTick(), issuedCursor);
        return new FireCommandValidation(fireCommand, issuedCursor);
    }

    public SystemCommandValidation validateSystemCommand(
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor) throws IOException {
        Objects.requireNonNull(systemCommand, "systemCommand");
        Objects.requireNonNull(issuedCursor, "issuedCursor");
        validateCommandSubjectAndTick(systemCommand.shipId(), systemCommand.simTick(), issuedCursor);
        return new SystemCommandValidation(systemCommand, issuedCursor);
    }

    private void validateCommandSubjectAndTick(
            long shipId,
            long simTick,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor) throws IOException {
        if (shipId != authorizedCommandShipId) {
            throw new IOException(MOVEMENT_COMMAND_REJECTION_MESSAGE
                    + ": shipId=" + shipId
                    + ", authorizedShipId=" + authorizedCommandShipId);
        }
        if (simTick < issuedCursor.previousResumeCursor() || simTick >= issuedCursor.nextResumeCursor()) {
            throw new IOException(MOVEMENT_COMMAND_REJECTION_MESSAGE
                    + ": simTick=" + simTick
                    + ", expected [" + issuedCursor.previousResumeCursor()
                    + ", " + issuedCursor.nextResumeCursor() + ")");
        }
    }

    public void requireResumeClaimForMovementCommand(
            int windowOrdinal,
            HeadlessSnapshotSocketClient.ClientSessionResumeClaim resumeClaim) throws IOException {
        if (windowOrdinal < 0) {
            throw new IllegalArgumentException("windowOrdinal must be >= 0; got " + windowOrdinal);
        }
        if (windowOrdinal > 0 && resumeClaim == null) {
            throw new IOException(MOVEMENT_COMMAND_REJECTION_MESSAGE
                    + ": missing resume claim for windowOrdinal=" + windowOrdinal);
        }
    }

    public HeadlessSnapshotSocketClient.ServerSessionWindowCursor latestCursorOrNull() {
        if (issuedCursors.isEmpty()) {
            return null;
        }
        return issuedCursors.get(issuedCursors.size() - 1);
    }

    public List<HeadlessSnapshotSocketClient.ServerSessionWindowCursor> issuedCursors() {
        return List.copyOf(issuedCursors);
    }

    public long authorizedCommandShipId() {
        return authorizedCommandShipId;
    }

    public record ResumeClaimValidation(
            HeadlessSnapshotSocketClient.ClientSessionResumeClaim claim,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor) {

        public ResumeClaimValidation {
            claim = Objects.requireNonNull(claim, "claim");
            issuedCursor = Objects.requireNonNull(issuedCursor, "issuedCursor");
        }
    }

    public record MovementCommandValidation(
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor) {

        public MovementCommandValidation {
            movementCommand = Objects.requireNonNull(movementCommand, "movementCommand");
            issuedCursor = Objects.requireNonNull(issuedCursor, "issuedCursor");
        }
    }

    public record FireCommandValidation(
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor) {

        public FireCommandValidation {
            fireCommand = Objects.requireNonNull(fireCommand, "fireCommand");
            issuedCursor = Objects.requireNonNull(issuedCursor, "issuedCursor");
        }
    }

    public record SystemCommandValidation(
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor issuedCursor) {

        public SystemCommandValidation {
            systemCommand = Objects.requireNonNull(systemCommand, "systemCommand");
            issuedCursor = Objects.requireNonNull(issuedCursor, "issuedCursor");
        }
    }
}
