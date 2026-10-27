package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

final class HeadlessSnapshotProcessReceiptValidator {
    private HeadlessSnapshotProcessReceiptValidator() {
    }
    static Properties loadProcessClientReceipt(Path clientReceiptPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(clientReceiptPath)) {
            properties.load(input);
        }
        return properties;
    }

    static void validateProcessClientReceipt(
            Properties clientReceipt,
            String socketHost,
            int socketPort,
            Path receiverArtifactPath,
            int expectedFrames,
            long expectedBytes,
            List<OfflineCombatStepWireFrame> receiverFrames,
            HeadlessSnapshotSocketClient.ClientHandshake clientHandshake,
            long serverPid,
            long clientPid) throws IOException {
        requireProperty(clientReceipt, "status", "ok");
        long receiptClientPid = requireLongProperty(clientReceipt, "client_pid");
        if (receiptClientPid != clientPid) {
            throw new IOException("headless snapshot socket client pid mismatch: process="
                    + clientPid + ", receipt=" + receiptClientPid);
        }
        if (serverPid == clientPid) {
            throw new IOException("headless snapshot socket client did not run in a separate process");
        }
        requireProperty(clientReceipt, "socket_host", socketHost);
        requireProperty(clientReceipt, "socket_port", Integer.toString(socketPort));
        requireProperty(
                clientReceipt,
                "receiver_artifact_path",
                receiverArtifactPath.toAbsolutePath().normalize().toString());
        requireProperty(clientReceipt, "expected_frames", Integer.toString(expectedFrames));
        requireProperty(clientReceipt, "expected_bytes", Long.toString(expectedBytes));
        requireProperty(clientReceipt, "bytes_received", Long.toString(expectedBytes));
        requireProperty(clientReceipt, "decode_source", "socket_input_stream");
        SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                .requireSnapshotStreamType(clientHandshake.streamTypeCode(), clientHandshake.streamVersion());
        if (!streamIdentity.streamId().equals(clientHandshake.streamId())) {
            throw new IOException("headless snapshot socket client handshake stream identity mismatch");
        }
        requireProperty(clientReceipt, "stream_id", clientHandshake.streamId());
        requireProperty(clientReceipt, "stream_type_code", Integer.toUnsignedString(clientHandshake.streamTypeCode()));
        requireProperty(clientReceipt, "stream_version", Integer.toString(clientHandshake.streamVersion()));
        requireClientAuthorityReceipt(clientReceipt, clientHandshake);
        requireProperty(clientReceipt, "stream_required_ship_count", Integer.toString(streamIdentity.requiredShipCount()));
        requireProperty(clientReceipt, "stream_required_frames", Integer.toString(streamIdentity.requiredFrames()));
        requireProperty(
                clientReceipt,
                "stream_requires_state_changes",
                Boolean.toString(streamIdentity.requiresStateChanges()));
        requireProperty(
                clientReceipt,
                "capability_stream_count",
                Integer.toString(clientHandshake.capabilityManifest().streamCount()));
        requireProperty(clientReceipt, "frames_read", Integer.toString(expectedFrames));
        HeadlessSnapshotSocketClient.ServerTransportSessionOpened expectedSession =
                HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                        clientHandshake,
                        socketHost,
                        socketPort);
        requireProperty(clientReceipt, "transport_session_opened_present", "true");
        requireProperty(
                clientReceipt,
                "transport_session_opened_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.TRANSPORT_SESSION_OPENED_BYTES));
        requireProperty(
                clientReceipt,
                "transport_session_opened_message_type_code",
                Integer.toUnsignedString(expectedSession.sessionMessageTypeCode()));
        requireProperty(
                clientReceipt,
                "transport_session_opened_schema_version",
                Integer.toString(expectedSession.sessionSchemaVersion()));
        requireProperty(
                clientReceipt,
                "transport_session_opened_off_loopback",
                Boolean.toString(expectedSession.offLoopback()));
        requireProperty(
                clientReceipt,
                "transport_session_opened_socket_host_crc32",
                Long.toUnsignedString(expectedSession.socketHostCrc32()));
        requireProperty(
                clientReceipt,
                "transport_session_opened_socket_port",
                Integer.toString(expectedSession.socketPort()));
        OfflineCombatStepWireFrame latestFrame =
                receiverFrames.isEmpty() ? null : receiverFrames.get(receiverFrames.size() - 1);
        requireProperty(
                clientReceipt,
                "latest_completed_tick",
                latestFrame == null ? "-1" : Long.toString(latestFrame.result().completedTick()));
        requireProperty(
                clientReceipt,
                "latest_state_hash",
                latestFrame == null ? "0" : Long.toString(latestFrame.result().stateHash()));
        requireProperty(
                clientReceipt,
                "latest_entity_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().entityCount()));
        requireProperty(
                clientReceipt,
                "latest_ship_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().shipCount()));
        requireProperty(
                clientReceipt,
                "latest_ship_network_id_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().shipNetworkIdCount()));
        requireProperty(
                clientReceipt,
                "latest_first_ship_network_id",
                latestFrame == null ? "-1" : Long.toString(latestFrame.result().firstShipNetworkId()));
        requireProperty(
                clientReceipt,
                "latest_last_ship_network_id",
                latestFrame == null ? "-1" : Long.toString(latestFrame.result().lastShipNetworkId()));
        requireProperty(
                clientReceipt,
                "latest_stable_ship_network_identity_range",
                Boolean.toString(latestFrame != null && latestFrame.result().stableShipNetworkIdentityRange()));
        requireProperty(
                clientReceipt,
                "all_frames_stable_ship_network_identity_range",
                Boolean.toString(allFramesStableShipNetworkIdentityRange(receiverFrames)));
        requireProperty(
                clientReceipt,
                "all_frames_same_ship_network_identity_range",
                Boolean.toString(allFramesSameShipNetworkIdentityRange(receiverFrames)));
        OfflineCombatStepWireFrame firstFrame =
                receiverFrames.isEmpty() ? null : receiverFrames.get(0);
        requireProperty(clientReceipt, "initial_sync_present", Boolean.toString(firstFrame != null));
        requireProperty(
                clientReceipt,
                "initial_sync_bytes",
                firstFrame == null
                        ? "0"
                        : Integer.toString(HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES));
        if (firstFrame != null) {
            requireProperty(
                    clientReceipt,
                    "initial_sync_message_type_code",
                    Integer.toUnsignedString(HeadlessSnapshotSocketClient.INITIAL_SYNC_MESSAGE_TYPE_CODE));
            requireProperty(
                    clientReceipt,
                    "initial_sync_schema_version",
                    Integer.toString(Short.toUnsignedInt(
                            HeadlessSnapshotSocketClient.INITIAL_SYNC_SCHEMA_VERSION)));
            requireProperty(clientReceipt, "initial_sync_sequence", Long.toString(firstFrame.sequence()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_started_tick",
                    Long.toString(firstFrame.result().startedTick()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_completed_tick",
                    Long.toString(firstFrame.result().completedTick()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_state_hash",
                    Long.toString(firstFrame.result().stateHash()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_entity_count",
                    Integer.toString(firstFrame.result().entityCount()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_ship_count",
                    Integer.toString(firstFrame.result().shipCount()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_ship_network_id_count",
                    Integer.toString(firstFrame.result().shipNetworkIdCount()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_first_ship_network_id",
                    Long.toString(firstFrame.result().firstShipNetworkId()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_last_ship_network_id",
                    Long.toString(firstFrame.result().lastShipNetworkId()));
            requireProperty(
                    clientReceipt,
                    "initial_sync_stable_ship_network_identity_range",
                    Boolean.toString(firstFrame.result().stableShipNetworkIdentityRange()));
        }
    }

    static void validateSessionWindowProcessClientReceipt(
            Properties clientReceipt,
            HeadlessSnapshotSocketClient.ServerSessionWindowCursor sessionWindowCursor,
            HeadlessSnapshotSocketClient.ClientSessionResumeClaim resumeClaim) throws IOException {
        Objects.requireNonNull(sessionWindowCursor, "sessionWindowCursor");
        requireProperty(clientReceipt, "session_window_cursor_present", "true");
        requireProperty(
                clientReceipt,
                "session_window_cursor_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_BYTES));
        requireProperty(
                clientReceipt,
                "session_window_cursor_message_type_code",
                Integer.toUnsignedString(HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_MESSAGE_TYPE_CODE));
        requireProperty(
                clientReceipt,
                "session_window_cursor_schema_version",
                Integer.toString(Short.toUnsignedInt(
                        HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_SCHEMA_VERSION)));
        requireProperty(
                clientReceipt,
                "session_window_cursor_session_id",
                Long.toString(sessionWindowCursor.sessionId()));
        requireProperty(
                clientReceipt,
                "session_window_cursor_window_ordinal",
                Integer.toString(sessionWindowCursor.windowOrdinal()));
        requireProperty(
                clientReceipt,
                "session_window_cursor_previous_resume_cursor",
                Long.toString(sessionWindowCursor.previousResumeCursor()));
        requireProperty(
                clientReceipt,
                "session_window_cursor_next_resume_cursor",
                Long.toString(sessionWindowCursor.nextResumeCursor()));
        requireProperty(
                clientReceipt,
                "session_window_cursor_expected_frames",
                Integer.toString(sessionWindowCursor.expectedFrames()));
        requireProperty(
                clientReceipt,
                "client_session_resume_claim_sent",
                Boolean.toString(resumeClaim != null));
        requireProperty(
                clientReceipt,
                "client_session_resume_claim_bytes",
                Integer.toString(resumeClaim == null
                        ? 0
                        : HeadlessSnapshotSocketClient.SESSION_RESUME_CLAIM_BYTES));
        if (resumeClaim != null) {
            requireProperty(
                    clientReceipt,
                    "client_session_resume_claim_message_type_code",
                    Integer.toUnsignedString(HeadlessSnapshotSocketClient.SESSION_RESUME_CLAIM_MESSAGE_TYPE_CODE));
            requireProperty(
                    clientReceipt,
                    "client_session_resume_claim_schema_version",
                    Integer.toString(Short.toUnsignedInt(
                            HeadlessSnapshotSocketClient.SESSION_RESUME_CLAIM_SCHEMA_VERSION)));
            requireProperty(
                    clientReceipt,
                    "client_session_resume_claim_session_id",
                    Long.toString(resumeClaim.sessionId()));
            requireProperty(
                    clientReceipt,
                    "client_session_resume_claim_window_ordinal",
                    Integer.toString(resumeClaim.windowOrdinal()));
            requireProperty(
                    clientReceipt,
                    "client_session_resume_claim_previous_resume_cursor",
                    Long.toString(resumeClaim.previousResumeCursor()));
            requireProperty(
                    clientReceipt,
                    "client_session_resume_claim_next_resume_cursor",
                    Long.toString(resumeClaim.nextResumeCursor()));
            requireProperty(
                    clientReceipt,
                    "client_session_resume_claim_expected_frames",
                    Integer.toString(resumeClaim.expectedFrames()));
        }
    }

    static void validatePersistentProcessClientReceipt(
            Properties clientReceipt,
            String socketHost,
            int socketPort,
            List<Path> receiverArtifactPaths,
            int expectedFramesPerWindow,
            long expectedBytesPerWindow,
            HeadlessSnapshotSocketClient.ClientHandshake clientHandshake,
            long serverPid,
            long clientPid) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths"));
        requireProperty(clientReceipt, "status", "ok");
        long receiptClientPid = requireLongProperty(clientReceipt, "client_pid");
        if (receiptClientPid != clientPid) {
            throw new IOException("persistent headless snapshot socket client pid mismatch: process="
                    + clientPid + ", receipt=" + receiptClientPid);
        }
        if (serverPid == clientPid) {
            throw new IOException("persistent headless snapshot socket client did not run in a separate process");
        }
        requireProperty(clientReceipt, "socket_host", socketHost);
        requireProperty(clientReceipt, "socket_port", Integer.toString(socketPort));
        requireProperty(clientReceipt, "persistent_session_reused", "true");
        requireProperty(
                clientReceipt,
                "persistent_session_window_count",
                Integer.toString(receiverArtifactPaths.size()));
        requireProperty(clientReceipt, "expected_frames", Integer.toString(expectedFramesPerWindow));
        requireProperty(clientReceipt, "expected_bytes", Long.toString(expectedBytesPerWindow));
        requireProperty(
                clientReceipt,
                "bytes_received",
                Long.toString(Math.multiplyExact(expectedBytesPerWindow, (long) receiverArtifactPaths.size())));
        requireProperty(clientReceipt, "decode_source", "socket_input_stream");
        SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                .requireSnapshotStreamType(clientHandshake.streamTypeCode(), clientHandshake.streamVersion());
        if (!streamIdentity.streamId().equals(clientHandshake.streamId())) {
            throw new IOException("persistent headless snapshot socket client handshake stream identity mismatch");
        }
        requireProperty(clientReceipt, "stream_id", clientHandshake.streamId());
        requireProperty(clientReceipt, "stream_type_code", Integer.toUnsignedString(clientHandshake.streamTypeCode()));
        requireProperty(clientReceipt, "stream_version", Integer.toString(clientHandshake.streamVersion()));
        requireClientAuthorityReceipt(clientReceipt, clientHandshake);
        requireProperty(clientReceipt, "stream_required_ship_count", Integer.toString(streamIdentity.requiredShipCount()));
        requireProperty(clientReceipt, "stream_required_frames", Integer.toString(streamIdentity.requiredFrames()));
        requireProperty(
                clientReceipt,
                "stream_requires_state_changes",
                Boolean.toString(streamIdentity.requiresStateChanges()));
        requireProperty(
                clientReceipt,
                "capability_stream_count",
                Integer.toString(clientHandshake.capabilityManifest().streamCount()));
        requireProperty(
                clientReceipt,
                "frames_read",
                Integer.toString(Math.multiplyExact(expectedFramesPerWindow, receiverArtifactPaths.size())));
        HeadlessSnapshotSocketClient.ServerTransportSessionOpened expectedSession =
                HeadlessSnapshotSocketClient.ServerTransportSessionOpened.opened(
                        clientHandshake,
                        socketHost,
                        socketPort);
        requireProperty(clientReceipt, "transport_session_opened_present", "true");
        requireProperty(
                clientReceipt,
                "transport_session_opened_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.TRANSPORT_SESSION_OPENED_BYTES));
        requireProperty(
                clientReceipt,
                "transport_session_opened_message_type_code",
                Integer.toUnsignedString(expectedSession.sessionMessageTypeCode()));
        requireProperty(
                clientReceipt,
                "transport_session_opened_schema_version",
                Integer.toString(expectedSession.sessionSchemaVersion()));
        requireProperty(
                clientReceipt,
                "transport_session_opened_off_loopback",
                Boolean.toString(expectedSession.offLoopback()));
        requireProperty(
                clientReceipt,
                "transport_session_opened_socket_host_crc32",
                Long.toUnsignedString(expectedSession.socketHostCrc32()));
        requireProperty(
                clientReceipt,
                "transport_session_opened_socket_port",
                Integer.toString(expectedSession.socketPort()));
        requireProperty(clientReceipt, "session_window_cursor_present", "true");
        requireProperty(
                clientReceipt,
                "session_window_cursor_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_BYTES));
        requireProperty(
                clientReceipt,
                "session_window_cursor_message_type_code",
                Integer.toUnsignedString(HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_MESSAGE_TYPE_CODE));
        requireProperty(
                clientReceipt,
                "session_window_cursor_schema_version",
                Integer.toString(Short.toUnsignedInt(
                        HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_SCHEMA_VERSION)));
        long sessionWindowSessionId =
                requireLongProperty(clientReceipt, "session_window_cursor_session_id");
        if (sessionWindowSessionId <= 0L) {
            throw new IOException("persistent session cursor id must be positive");
        }
        requireProperty(
                clientReceipt,
                "session_window_cursor_window_count",
                Integer.toString(receiverArtifactPaths.size()));
        requireProperty(
                clientReceipt,
                "session_window_cursor_final_resume_cursor",
                Long.toString(Math.multiplyExact(
                        (long) expectedFramesPerWindow,
                        (long) receiverArtifactPaths.size())));
        for (int i = 0; i < receiverArtifactPaths.size(); i++) {
            Path receiverArtifactPath = receiverArtifactPaths.get(i);
            List<OfflineCombatStepWireFrame> frames = OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
            if (frames.size() != expectedFramesPerWindow) {
                throw new IOException("persistent receipt window " + (i + 1)
                        + " frame count mismatch: expected " + expectedFramesPerWindow
                        + ", got " + frames.size());
            }
            OfflineCombatStepWireFrame firstFrame = frames.isEmpty() ? null : frames.get(0);
            OfflineCombatStepWireFrame latestFrame = frames.isEmpty() ? null : frames.get(frames.size() - 1);
            String prefix = "window_" + (i + 1) + "_";
            requireProperty(
                    clientReceipt,
                    prefix + "receiver_artifact_path",
                    receiverArtifactPath.toAbsolutePath().normalize().toString());
            requireProperty(clientReceipt, prefix + "frames_read", Integer.toString(expectedFramesPerWindow));
            requireProperty(clientReceipt, prefix + "expected_bytes", Long.toString(expectedBytesPerWindow));
            requireProperty(clientReceipt, prefix + "bytes_received", Long.toString(expectedBytesPerWindow));
            requireProperty(
                    clientReceipt,
                    prefix + "session_window_cursor_bytes",
                    Integer.toString(HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_BYTES));
            requireProperty(
                    clientReceipt,
                    prefix + "session_window_cursor_message_type_code",
                    Integer.toUnsignedString(HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_MESSAGE_TYPE_CODE));
            requireProperty(
                    clientReceipt,
                    prefix + "session_window_cursor_schema_version",
                    Integer.toString(Short.toUnsignedInt(
                            HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_SCHEMA_VERSION)));
            requireProperty(
                    clientReceipt,
                    prefix + "session_window_cursor_session_id",
                    Long.toString(sessionWindowSessionId));
            requireProperty(
                    clientReceipt,
                    prefix + "session_window_cursor_window_ordinal",
                    Integer.toString(i));
            long expectedPreviousCursor = Math.multiplyExact((long) i, (long) expectedFramesPerWindow);
            requireProperty(
                    clientReceipt,
                    prefix + "session_window_cursor_previous_resume_cursor",
                    Long.toString(expectedPreviousCursor));
            requireProperty(
                    clientReceipt,
                    prefix + "session_window_cursor_next_resume_cursor",
                    Long.toString(expectedPreviousCursor + expectedFramesPerWindow));
            requireProperty(
                    clientReceipt,
                    prefix + "session_window_cursor_expected_frames",
                    Integer.toString(expectedFramesPerWindow));
            requireProperty(
                    clientReceipt,
                    prefix + "initial_sync_bytes",
                    Integer.toString(HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES));
            requireProperty(
                    clientReceipt,
                    prefix + "initial_sync_state_hash",
                    firstFrame == null ? "0" : Long.toString(firstFrame.result().stateHash()));
            requireProperty(
                    clientReceipt,
                    prefix + "stream_completion_bytes",
                    Integer.toString(HeadlessSnapshotSocketClient.STREAM_COMPLETED_BYTES));
            requireProperty(
                    clientReceipt,
                    prefix + "stream_completion_completed_frames",
                    Integer.toString(expectedFramesPerWindow));
            requireProperty(
                    clientReceipt,
                    prefix + "first_completed_tick",
                    firstFrame == null ? "-1" : Long.toString(firstFrame.result().completedTick()));
            requireProperty(
                    clientReceipt,
                    prefix + "latest_completed_tick",
                    latestFrame == null ? "-1" : Long.toString(latestFrame.result().completedTick()));
            requireProperty(
                    clientReceipt,
                    prefix + "first_state_hash",
                    firstFrame == null ? "0" : Long.toString(firstFrame.result().stateHash()));
            requireProperty(
                    clientReceipt,
                    prefix + "latest_state_hash",
                    latestFrame == null ? "0" : Long.toString(latestFrame.result().stateHash()));
            requireProperty(
                    clientReceipt,
                    prefix + "latest_ship_count",
                    latestFrame == null ? "-1" : Integer.toString(latestFrame.result().shipCount()));
            requireProperty(clientReceipt, prefix + "state_changes", Boolean.toString(stateChanges(frames)));
        }
        List<OfflineCombatStepWireFrame> latestWindowFrames =
                OfflineCombatStepWireCodec.readFrames(receiverArtifactPaths.get(receiverArtifactPaths.size() - 1));
        OfflineCombatStepWireFrame latestFrame = latestWindowFrames.isEmpty()
                ? null
                : latestWindowFrames.get(latestWindowFrames.size() - 1);
        requireProperty(
                clientReceipt,
                "latest_completed_tick",
                latestFrame == null ? "-1" : Long.toString(latestFrame.result().completedTick()));
        requireProperty(
                clientReceipt,
                "latest_state_hash",
                latestFrame == null ? "0" : Long.toString(latestFrame.result().stateHash()));
        requireProperty(
                clientReceipt,
                "latest_entity_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().entityCount()));
        requireProperty(
                clientReceipt,
                "latest_ship_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().shipCount()));
    }

    static void requireStreamIdentityMatchesFrames(
            HeadlessSnapshotSocketClient.ClientHandshake handshake,
            int expectedFrames,
            List<OfflineCombatStepWireFrame> frames) {
        SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                .requireSnapshotStreamType(handshake.streamTypeCode(), handshake.streamVersion());
        if (!streamIdentity.streamId().equals(handshake.streamId())) {
            throw new IllegalArgumentException("receiver client handshake stream identity must match registry export");
        }
        if (streamIdentity.hasFrameRequirement() && streamIdentity.requiredFrames() != expectedFrames) {
            throw new IllegalArgumentException("registered stream frame requirement must match expectedFrames");
        }
        if (streamIdentity.hasShipCountRequirement()) {
            int maxShips = streamIdentity.requiredShipCount();
            int minShips = streamIdentity.minShipCount();
            for (OfflineCombatStepWireFrame frame : frames) {
                int count = frame.result().shipCount();
                if (count < minShips || count > maxShips) {
                    throw new IllegalArgumentException("registered stream ship-count requirement failed");
                }
            }
        }
        if (streamIdentity.requiresStateChanges()) {
            if (frames.size() < 2) {
                throw new IllegalArgumentException("registered state-changing stream requires at least two frames");
            }
            long firstStateHash = frames.get(0).result().stateHash();
            boolean changed = false;
            for (OfflineCombatStepWireFrame frame : frames) {
                if (frame.result().stateHash() != firstStateHash) {
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                throw new IllegalArgumentException("registered state-changing stream had constant state hash");
            }
        }
    }

    private static void requireClientAuthorityReceipt(
            Properties clientReceipt,
            HeadlessSnapshotSocketClient.ClientHandshake clientHandshake) throws IOException {
        requireProperty(clientReceipt, "client_authority_id", clientHandshake.clientAuthorityId());
        requireProperty(clientReceipt, "client_owned_ship_id", Long.toString(clientHandshake.clientOwnedShipId()));
        requireProperty(clientReceipt, "client_seat_token", clientHandshake.seatToken());
        requireProperty(clientReceipt, "client_seat_role", clientHandshake.seatRole().name());
        requireProperty(clientReceipt, "client_content_fingerprint", clientHandshake.contentFingerprint());
    }

    static void requireInitialSyncMatchesFirstFrame(
            HeadlessSnapshotSocketClient.ServerInitialSync initialSync,
            OfflineCombatStepWireFrame firstFrame) {
        OfflineCombatStepResult result = firstFrame.result();
        if (initialSync.snapshotSequence() != firstFrame.sequence()
                || initialSync.startedTick() != result.startedTick()
                || initialSync.completedTick() != result.completedTick()
                || initialSync.stateHash() != result.stateHash()
                || initialSync.entityCount() != result.entityCount()
                || initialSync.shipCount() != result.shipCount()
                || initialSync.shipNetworkIdCount() != result.shipNetworkIdCount()
                || initialSync.firstShipNetworkId() != result.firstShipNetworkId()
                || initialSync.lastShipNetworkId() != result.lastShipNetworkId()
                || initialSync.stableShipNetworkIdentityRange()
                        != result.stableShipNetworkIdentityRange()) {
            throw new IllegalArgumentException("receiver client initial sync must match first decoded frame");
        }
    }

    static boolean allFramesStableShipNetworkIdentityRange(List<OfflineCombatStepWireFrame> frames) {
        if (frames.isEmpty()) {
            return false;
        }
        for (OfflineCombatStepWireFrame frame : frames) {
            if (!frame.result().stableShipNetworkIdentityRange()) {
                return false;
            }
        }
        return true;
    }

    static boolean allFramesSameShipNetworkIdentityRange(List<OfflineCombatStepWireFrame> frames) {
        if (frames.isEmpty()) {
            return false;
        }
        OfflineCombatStepResult first = frames.get(0).result();
        for (OfflineCombatStepWireFrame frame : frames) {
            OfflineCombatStepResult result = frame.result();
            if (result.shipNetworkIdCount() != first.shipNetworkIdCount()
                    || result.firstShipNetworkId() != first.firstShipNetworkId()
                    || result.lastShipNetworkId() != first.lastShipNetworkId()) {
                return false;
            }
        }
        return true;
    }

    static boolean stateChanges(List<OfflineCombatStepWireFrame> frames) {
        if (frames.size() < 2) {
            return false;
        }
        long firstStateHash = frames.get(0).result().stateHash();
        for (OfflineCombatStepWireFrame frame : frames) {
            if (frame.result().stateHash() != firstStateHash) {
                return true;
            }
        }
        return false;
    }

    static void requireProcessClientMovementReceipt(
            Properties clientReceipt,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            ShipCommandSink.Result movementCommandResult) throws IOException {
        requireProperty(clientReceipt, "movement_command_result_present", "true");
        requireProperty(
                clientReceipt,
                "movement_command_result_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_BYTES));
        requireProperty(
                clientReceipt,
                "movement_command_result_message_type_code",
                Integer.toUnsignedString(HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_MESSAGE_TYPE_CODE));
        requireProperty(
                clientReceipt,
                "movement_command_result_schema_version",
                Integer.toString(Short.toUnsignedInt(
                        HeadlessSnapshotSocketClient.MOVEMENT_COMMAND_RESULT_SCHEMA_VERSION)));
        requireProperty(
                clientReceipt,
                "movement_command_result_ship_id",
                Long.toString(movementCommand.shipId()));
        requireProperty(
                clientReceipt,
                "movement_command_result_sim_tick",
                Long.toString(movementCommand.simTick()));
        requireProperty(
                clientReceipt,
                "movement_command_result_intent",
                movementCommand.movementIntent().name());
        requireProperty(
                clientReceipt,
                "movement_command_result",
                movementCommandResult.name());
    }

    static void requireProcessClientFireReceipt(
            Properties clientReceipt,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            ShipCommandSink.Result fireCommandResult) throws IOException {
        requireProperty(clientReceipt, "fire_command_result_present", "true");
        requireProperty(
                clientReceipt,
                "fire_command_result_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.FIRE_COMMAND_RESULT_BYTES));
        requireProperty(
                clientReceipt,
                "fire_command_result_message_type_code",
                Integer.toUnsignedString(HeadlessSnapshotSocketClient.FIRE_COMMAND_RESULT_MESSAGE_TYPE_CODE));
        requireProperty(
                clientReceipt,
                "fire_command_result_schema_version",
                Integer.toString(Short.toUnsignedInt(
                        HeadlessSnapshotSocketClient.FIRE_COMMAND_RESULT_SCHEMA_VERSION)));
        requireProperty(
                clientReceipt,
                "fire_command_result_ship_id",
                Long.toString(fireCommand.shipId()));
        requireProperty(
                clientReceipt,
                "fire_command_result_sim_tick",
                Long.toString(fireCommand.simTick()));
        requireProperty(
                clientReceipt,
                "fire_command_result_fire",
                Boolean.toString(fireCommand.fire()));
        requireProperty(
                clientReceipt,
                "fire_command_result",
                fireCommandResult.name());
    }

    static void requireProcessClientWeaponGroupReceipt(
            Properties clientReceipt,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            ShipCommandSink.Result weaponGroupCommandResult) throws IOException {
        requireProperty(clientReceipt, "weapon_group_command_result_present", "true");
        requireProperty(
                clientReceipt,
                "weapon_group_command_result_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.WEAPON_GROUP_COMMAND_RESULT_BYTES));
        requireProperty(
                clientReceipt,
                "weapon_group_command_result_message_type_code",
                Integer.toUnsignedString(
                        HeadlessSnapshotSocketClient.WEAPON_GROUP_COMMAND_RESULT_MESSAGE_TYPE_CODE));
        requireProperty(
                clientReceipt,
                "weapon_group_command_result_schema_version",
                Integer.toString(Short.toUnsignedInt(
                        HeadlessSnapshotSocketClient.WEAPON_GROUP_COMMAND_RESULT_SCHEMA_VERSION)));
        requireProperty(
                clientReceipt,
                "weapon_group_command_result_ship_id",
                Long.toString(weaponGroupCommand.shipId()));
        requireProperty(
                clientReceipt,
                "weapon_group_command_result_sim_tick",
                Long.toString(weaponGroupCommand.simTick()));
        requireProperty(
                clientReceipt,
                "weapon_group_command_result_group",
                Integer.toString(weaponGroupCommand.group()));
        requireProperty(
                clientReceipt,
                "weapon_group_command_result",
                weaponGroupCommandResult.name());
    }

    static void requireProcessClientSystemReceipt(
            Properties clientReceipt,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ShipCommandSink.Result systemCommandResult) throws IOException {
        requireProperty(clientReceipt, "system_command_result_present", "true");
        requireProperty(
                clientReceipt,
                "system_command_result_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.SYSTEM_COMMAND_RESULT_BYTES));
        requireProperty(
                clientReceipt,
                "system_command_result_message_type_code",
                Integer.toUnsignedString(HeadlessSnapshotSocketClient.SYSTEM_COMMAND_RESULT_MESSAGE_TYPE_CODE));
        requireProperty(
                clientReceipt,
                "system_command_result_schema_version",
                Integer.toString(Short.toUnsignedInt(
                        HeadlessSnapshotSocketClient.SYSTEM_COMMAND_RESULT_SCHEMA_VERSION)));
        requireProperty(
                clientReceipt,
                "system_command_result_ship_id",
                Long.toString(systemCommand.shipId()));
        requireProperty(
                clientReceipt,
                "system_command_result_sim_tick",
                Long.toString(systemCommand.simTick()));
        requireProperty(
                clientReceipt,
                "system_command_result_system_id",
                Integer.toString(systemCommand.systemId()));
        requireProperty(
                clientReceipt,
                "system_command_result",
                systemCommandResult.name());
    }

    static void requireProperty(Properties properties, String key, String expected) throws IOException {
        String actual = properties.getProperty(key);
        if (!expected.equals(actual)) {
            throw new IOException("headless snapshot socket client receipt " + key
                    + " mismatch: expected " + expected + ", got " + actual);
        }
    }

    static int requireIntProperty(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IOException("headless snapshot socket client receipt missing " + key);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            throw new IOException("headless snapshot socket client receipt has invalid int " + key
                    + ": " + value, nfe);
        }
    }

    static long requireLongProperty(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IOException("headless snapshot socket client receipt missing " + key);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException nfe) {
            throw new IOException("headless snapshot socket client receipt has invalid long " + key
                    + ": " + value, nfe);
        }
    }

}
