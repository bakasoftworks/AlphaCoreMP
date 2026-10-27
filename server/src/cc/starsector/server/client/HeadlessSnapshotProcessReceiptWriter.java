package cc.starsector.server.client;

import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientSessionResumeClaim;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.AdmissionReceipt;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientHandshake;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.PersistentReceipt;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.PersistentWindowReceipt;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.Receipt;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerFireCommandResult;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerInitialSync;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerMovementCommandResult;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerSessionWindowCursor;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerSystemCommandResult;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerTransportSessionOpened;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerWeaponGroupCommandResult;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepResult;
import cc.starsector.server.runtime.OfflineCombatStepWireFrame;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

final class HeadlessSnapshotProcessReceiptWriter {
    private HeadlessSnapshotProcessReceiptWriter() {
    }

    static void write(Path receiptPath, AdmissionReceipt receipt) throws IOException {
        Objects.requireNonNull(receiptPath, "receiptPath");
        Objects.requireNonNull(receipt, "receipt");
        Path receiptParent = receiptPath.getParent();
        if (receiptParent != null) {
            Files.createDirectories(receiptParent);
        }

        Properties properties = new Properties();
        properties.setProperty("status", "ok");
        properties.setProperty("client_pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("socket_host", receipt.socketHost());
        properties.setProperty("socket_port", Integer.toString(receipt.socketPort()));
        properties.setProperty("expected_frames", Integer.toString(receipt.expectedFrames()));
        properties.setProperty("stream_id", receipt.handshake().streamId());
        properties.setProperty(
                "stream_type_code",
                Integer.toUnsignedString(receipt.handshake().streamTypeCode()));
        properties.setProperty("stream_version", Integer.toString(receipt.handshake().streamVersion()));
        writeClientAuthorityProperties(properties, receipt.handshake());
        properties.setProperty(
                "capability_stream_count",
                Integer.toString(receipt.handshake().capabilityManifest().streamCount()));
        properties.setProperty(
                "capability_entry_count",
                Integer.toString(receipt.handshake().capabilityManifest().entryCount()));
        properties.setProperty(
                "capability_fingerprint_crc32",
                Long.toUnsignedString(receipt.handshake().capabilityManifest().fingerprintCrc32()));
        properties.setProperty("admission_ack_bytes", Integer.toString(receipt.admissionAckBytes()));
        properties.setProperty("admission_accepted", Boolean.toString(receipt.admissionAck().accepted()));
        try (OutputStream output = Files.newOutputStream(
                receiptPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "headless snapshot socket admission client process receipt");
        }
    }

    static void write(Path receiptPath, Receipt receipt) throws IOException {
        Objects.requireNonNull(receiptPath, "receiptPath");
        Objects.requireNonNull(receipt, "receipt");
        Path receiptParent = receiptPath.getParent();
        if (receiptParent != null) {
            Files.createDirectories(receiptParent);
        }

        List<OfflineCombatStepWireFrame> frames = receipt.frames();
        OfflineCombatStepWireFrame latestFrame = frames.isEmpty() ? null : frames.get(frames.size() - 1);
        Properties properties = new Properties();
        properties.setProperty("status", "ok");
        properties.setProperty("client_pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("socket_host", receipt.socketHost());
        properties.setProperty("socket_port", Integer.toString(receipt.socketPort()));
        properties.setProperty(
                "receiver_artifact_path",
                receipt.receiverArtifactPath().toAbsolutePath().normalize().toString());
        properties.setProperty("expected_frames", Integer.toString(receipt.expectedFrames()));
        properties.setProperty("expected_bytes", Long.toString(receipt.expectedBytes()));
        properties.setProperty("bytes_received", Long.toString(receipt.bytesReceived()));
        properties.setProperty("decode_source", receipt.decodeSource());
        properties.setProperty("stream_id", receipt.handshake().streamId());
        properties.setProperty(
                "stream_type_code",
                Integer.toUnsignedString(receipt.handshake().streamTypeCode()));
        properties.setProperty("stream_version", Integer.toString(receipt.handshake().streamVersion()));
        writeClientAuthorityProperties(properties, receipt.handshake());
        SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                .requireSnapshotStreamType(receipt.handshake().streamTypeCode(), receipt.handshake().streamVersion());
        properties.setProperty("stream_required_ship_count", Integer.toString(streamIdentity.requiredShipCount()));
        properties.setProperty("stream_required_frames", Integer.toString(streamIdentity.requiredFrames()));
        properties.setProperty(
                "stream_requires_state_changes",
                Boolean.toString(streamIdentity.requiresStateChanges()));
        properties.setProperty(
                "capability_stream_count",
                Integer.toString(receipt.handshake().capabilityManifest().streamCount()));
        properties.setProperty("frames_read", Integer.toString(frames.size()));
        ServerTransportSessionOpened transportSessionOpened = receipt.transportSessionOpened();
        properties.setProperty(
                "transport_session_opened_present",
                Boolean.toString(transportSessionOpened != null));
        properties.setProperty(
                "transport_session_opened_bytes",
                Integer.toString(receipt.transportSessionOpenedBytes()));
        if (transportSessionOpened != null) {
            properties.setProperty(
                    "transport_session_opened_message_type_code",
                    Integer.toUnsignedString(transportSessionOpened.sessionMessageTypeCode()));
            properties.setProperty(
                    "transport_session_opened_schema_version",
                    Integer.toString(transportSessionOpened.sessionSchemaVersion()));
            properties.setProperty(
                    "transport_session_opened_off_loopback",
                    Boolean.toString(transportSessionOpened.offLoopback()));
            properties.setProperty(
                    "transport_session_opened_socket_host_crc32",
                    Long.toUnsignedString(transportSessionOpened.socketHostCrc32()));
            properties.setProperty(
                    "transport_session_opened_socket_port",
                    Integer.toString(transportSessionOpened.socketPort()));
        }
        ServerMovementCommandResult movementCommandResult = receipt.movementCommandResult();
        properties.setProperty(
                "movement_command_result_present",
                Boolean.toString(movementCommandResult != null));
        properties.setProperty(
                "movement_command_result_bytes",
                Integer.toString(receipt.movementCommandResultBytes()));
        if (movementCommandResult != null) {
            properties.setProperty(
                    "movement_command_result_message_type_code",
                    Integer.toUnsignedString(movementCommandResult.messageTypeCode()));
            properties.setProperty(
                    "movement_command_result_schema_version",
                    Integer.toString(movementCommandResult.schemaVersion()));
            properties.setProperty(
                    "movement_command_result_ship_id",
                    Long.toString(movementCommandResult.shipId()));
            properties.setProperty(
                    "movement_command_result_sim_tick",
                    Long.toString(movementCommandResult.simTick()));
            properties.setProperty(
                    "movement_command_result_intent",
                    movementCommandResult.movementIntent().name());
            properties.setProperty(
                    "movement_command_result",
                    movementCommandResult.commandResult().name());
        }
        ServerWeaponGroupCommandResult weaponGroupCommandResult = receipt.weaponGroupCommandResult();
        properties.setProperty(
                "weapon_group_command_result_present",
                Boolean.toString(weaponGroupCommandResult != null));
        properties.setProperty(
                "weapon_group_command_result_bytes",
                Integer.toString(receipt.weaponGroupCommandResultBytes()));
        if (weaponGroupCommandResult != null) {
            properties.setProperty(
                    "weapon_group_command_result_message_type_code",
                    Integer.toUnsignedString(weaponGroupCommandResult.messageTypeCode()));
            properties.setProperty(
                    "weapon_group_command_result_schema_version",
                    Integer.toString(weaponGroupCommandResult.schemaVersion()));
            properties.setProperty(
                    "weapon_group_command_result_ship_id",
                    Long.toString(weaponGroupCommandResult.shipId()));
            properties.setProperty(
                    "weapon_group_command_result_sim_tick",
                    Long.toString(weaponGroupCommandResult.simTick()));
            properties.setProperty(
                    "weapon_group_command_result_group",
                    Integer.toString(weaponGroupCommandResult.group()));
            properties.setProperty(
                    "weapon_group_command_result",
                    weaponGroupCommandResult.commandResult().name());
        }
        ServerFireCommandResult fireCommandResult = receipt.fireCommandResult();
        properties.setProperty(
                "fire_command_result_present",
                Boolean.toString(fireCommandResult != null));
        properties.setProperty(
                "fire_command_result_bytes",
                Integer.toString(receipt.fireCommandResultBytes()));
        if (fireCommandResult != null) {
            properties.setProperty(
                    "fire_command_result_message_type_code",
                    Integer.toUnsignedString(fireCommandResult.messageTypeCode()));
            properties.setProperty(
                    "fire_command_result_schema_version",
                    Integer.toString(fireCommandResult.schemaVersion()));
            properties.setProperty(
                    "fire_command_result_ship_id",
                    Long.toString(fireCommandResult.shipId()));
            properties.setProperty(
                    "fire_command_result_sim_tick",
                    Long.toString(fireCommandResult.simTick()));
            properties.setProperty(
                    "fire_command_result_fire",
                    Boolean.toString(fireCommandResult.fire()));
            properties.setProperty(
                    "fire_command_result",
                    fireCommandResult.commandResult().name());
        }
        ServerSystemCommandResult systemCommandResult = receipt.systemCommandResult();
        properties.setProperty(
                "system_command_result_present",
                Boolean.toString(systemCommandResult != null));
        properties.setProperty(
                "system_command_result_bytes",
                Integer.toString(receipt.systemCommandResultBytes()));
        if (systemCommandResult != null) {
            properties.setProperty(
                    "system_command_result_message_type_code",
                    Integer.toUnsignedString(systemCommandResult.messageTypeCode()));
            properties.setProperty(
                    "system_command_result_schema_version",
                    Integer.toString(systemCommandResult.schemaVersion()));
            properties.setProperty(
                    "system_command_result_ship_id",
                    Long.toString(systemCommandResult.shipId()));
            properties.setProperty(
                    "system_command_result_sim_tick",
                    Long.toString(systemCommandResult.simTick()));
            properties.setProperty(
                    "system_command_result_system_id",
                    Integer.toString(systemCommandResult.systemId()));
            properties.setProperty(
                    "system_command_result",
                    systemCommandResult.commandResult().name());
        }
        ClientSessionResumeClaim sessionResumeClaim = receipt.sessionResumeClaim();
        properties.setProperty(
                "client_session_resume_claim_sent",
                Boolean.toString(sessionResumeClaim != null));
        properties.setProperty(
                "client_session_resume_claim_bytes",
                Integer.toString(receipt.sessionResumeClaimBytes()));
        if (sessionResumeClaim != null) {
            properties.setProperty(
                    "client_session_resume_claim_message_type_code",
                    Integer.toUnsignedString(sessionResumeClaim.claimMessageTypeCode()));
            properties.setProperty(
                    "client_session_resume_claim_schema_version",
                    Integer.toString(sessionResumeClaim.claimSchemaVersion()));
            properties.setProperty(
                    "client_session_resume_claim_session_id",
                    Long.toString(sessionResumeClaim.sessionId()));
            properties.setProperty(
                    "client_session_resume_claim_window_ordinal",
                    Integer.toString(sessionResumeClaim.windowOrdinal()));
            properties.setProperty(
                    "client_session_resume_claim_previous_resume_cursor",
                    Long.toString(sessionResumeClaim.previousResumeCursor()));
            properties.setProperty(
                    "client_session_resume_claim_next_resume_cursor",
                    Long.toString(sessionResumeClaim.nextResumeCursor()));
            properties.setProperty(
                    "client_session_resume_claim_expected_frames",
                    Integer.toString(sessionResumeClaim.expectedFrames()));
        }
        ServerSessionWindowCursor sessionWindowCursor = receipt.sessionWindowCursor();
        properties.setProperty(
                "session_window_cursor_present",
                Boolean.toString(sessionWindowCursor != null));
        properties.setProperty(
                "session_window_cursor_bytes",
                Integer.toString(receipt.sessionWindowCursorBytes()));
        if (sessionWindowCursor != null) {
            properties.setProperty(
                    "session_window_cursor_message_type_code",
                    Integer.toUnsignedString(sessionWindowCursor.cursorMessageTypeCode()));
            properties.setProperty(
                    "session_window_cursor_schema_version",
                    Integer.toString(sessionWindowCursor.cursorSchemaVersion()));
            properties.setProperty(
                    "session_window_cursor_session_id",
                    Long.toString(sessionWindowCursor.sessionId()));
            properties.setProperty(
                    "session_window_cursor_window_ordinal",
                    Integer.toString(sessionWindowCursor.windowOrdinal()));
            properties.setProperty(
                    "session_window_cursor_previous_resume_cursor",
                    Long.toString(sessionWindowCursor.previousResumeCursor()));
            properties.setProperty(
                    "session_window_cursor_next_resume_cursor",
                    Long.toString(sessionWindowCursor.nextResumeCursor()));
            properties.setProperty(
                    "session_window_cursor_expected_frames",
                    Integer.toString(sessionWindowCursor.expectedFrames()));
        }
        ServerInitialSync initialSync = receipt.initialSync();
        properties.setProperty("initial_sync_present", Boolean.toString(initialSync != null));
        properties.setProperty("initial_sync_bytes", Integer.toString(receipt.initialSyncBytes()));
        if (initialSync != null) {
            properties.setProperty(
                    "initial_sync_message_type_code",
                    Integer.toUnsignedString(initialSync.initialSyncMessageTypeCode()));
            properties.setProperty(
                    "initial_sync_schema_version",
                    Integer.toString(initialSync.initialSyncSchemaVersion()));
            properties.setProperty(
                    "initial_sync_sequence",
                    Long.toString(initialSync.snapshotSequence()));
            properties.setProperty(
                    "initial_sync_started_tick",
                    Long.toString(initialSync.startedTick()));
            properties.setProperty(
                    "initial_sync_completed_tick",
                    Long.toString(initialSync.completedTick()));
            properties.setProperty(
                    "initial_sync_state_hash",
                    Long.toString(initialSync.stateHash()));
            properties.setProperty(
                    "initial_sync_entity_count",
                    Integer.toString(initialSync.entityCount()));
            properties.setProperty(
                    "initial_sync_ship_count",
                    Integer.toString(initialSync.shipCount()));
            properties.setProperty(
                    "initial_sync_ship_network_id_count",
                    Integer.toString(initialSync.shipNetworkIdCount()));
            properties.setProperty(
                    "initial_sync_first_ship_network_id",
                    Long.toString(initialSync.firstShipNetworkId()));
            properties.setProperty(
                    "initial_sync_last_ship_network_id",
                    Long.toString(initialSync.lastShipNetworkId()));
            properties.setProperty(
                    "initial_sync_stable_ship_network_identity_range",
                    Boolean.toString(initialSync.stableShipNetworkIdentityRange()));
        }
        properties.setProperty(
                "latest_completed_tick",
                latestFrame == null ? "-1" : Long.toString(latestFrame.result().completedTick()));
        properties.setProperty(
                "latest_state_hash",
                latestFrame == null ? "0" : Long.toString(latestFrame.result().stateHash()));
        properties.setProperty(
                "latest_entity_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().entityCount()));
        properties.setProperty(
                "latest_ship_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().shipCount()));
        properties.setProperty(
                "latest_ship_network_id_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().shipNetworkIdCount()));
        properties.setProperty(
                "latest_first_ship_network_id",
                latestFrame == null ? "-1" : Long.toString(latestFrame.result().firstShipNetworkId()));
        properties.setProperty(
                "latest_last_ship_network_id",
                latestFrame == null ? "-1" : Long.toString(latestFrame.result().lastShipNetworkId()));
        properties.setProperty(
                "latest_stable_ship_network_identity_range",
                Boolean.toString(latestFrame != null && latestFrame.result().stableShipNetworkIdentityRange()));
        properties.setProperty(
                "all_frames_stable_ship_network_identity_range",
                Boolean.toString(allFramesStableShipNetworkIdentityRange(frames)));
        properties.setProperty(
                "all_frames_same_ship_network_identity_range",
                Boolean.toString(allFramesSameShipNetworkIdentityRange(frames)));
        try (OutputStream output = Files.newOutputStream(
                receiptPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "headless snapshot socket client process receipt");
        }
    }

    static void write(Path receiptPath, PersistentReceipt receipt) throws IOException {
        Objects.requireNonNull(receiptPath, "receiptPath");
        Objects.requireNonNull(receipt, "receipt");
        Path receiptParent = receiptPath.getParent();
        if (receiptParent != null) {
            Files.createDirectories(receiptParent);
        }

        List<PersistentWindowReceipt> windows = receipt.windows();
        PersistentWindowReceipt latestWindow = windows.get(windows.size() - 1);
        OfflineCombatStepWireFrame latestFrame = latestWindow.frames().isEmpty()
                ? null
                : latestWindow.frames().get(latestWindow.frames().size() - 1);
        Properties properties = new Properties();
        properties.setProperty("status", "ok");
        properties.setProperty("client_pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("socket_host", receipt.socketHost());
        properties.setProperty("socket_port", Integer.toString(receipt.socketPort()));
        properties.setProperty("persistent_session_reused", "true");
        properties.setProperty("persistent_session_window_count", Integer.toString(windows.size()));
        properties.setProperty("expected_frames", Integer.toString(receipt.expectedFramesPerWindow()));
        properties.setProperty("expected_bytes", Long.toString(receipt.expectedBytesPerWindow()));
        properties.setProperty("bytes_received", Long.toString(receipt.totalBytesReceived()));
        properties.setProperty("decode_source", receipt.decodeSource());
        properties.setProperty("stream_id", receipt.handshake().streamId());
        properties.setProperty("stream_type_code", Integer.toUnsignedString(receipt.handshake().streamTypeCode()));
        properties.setProperty("stream_version", Integer.toString(receipt.handshake().streamVersion()));
        writeClientAuthorityProperties(properties, receipt.handshake());
        SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                .requireSnapshotStreamType(receipt.handshake().streamTypeCode(), receipt.handshake().streamVersion());
        properties.setProperty("stream_required_ship_count", Integer.toString(streamIdentity.requiredShipCount()));
        properties.setProperty("stream_required_frames", Integer.toString(streamIdentity.requiredFrames()));
        properties.setProperty(
                "stream_requires_state_changes",
                Boolean.toString(streamIdentity.requiresStateChanges()));
        properties.setProperty(
                "capability_stream_count",
                Integer.toString(receipt.handshake().capabilityManifest().streamCount()));
        properties.setProperty("frames_read", Integer.toString(receipt.totalFramesRead()));
        ServerTransportSessionOpened transportSessionOpened = receipt.transportSessionOpened();
        properties.setProperty("transport_session_opened_present", "true");
        properties.setProperty(
                "transport_session_opened_bytes",
                Integer.toString(receipt.transportSessionOpenedBytes()));
        properties.setProperty(
                "transport_session_opened_message_type_code",
                Integer.toUnsignedString(transportSessionOpened.sessionMessageTypeCode()));
        properties.setProperty(
                "transport_session_opened_schema_version",
                Integer.toString(transportSessionOpened.sessionSchemaVersion()));
        properties.setProperty(
                "transport_session_opened_off_loopback",
                Boolean.toString(transportSessionOpened.offLoopback()));
        properties.setProperty(
                "transport_session_opened_socket_host_crc32",
                Long.toUnsignedString(transportSessionOpened.socketHostCrc32()));
        properties.setProperty(
                "transport_session_opened_socket_port",
                Integer.toString(transportSessionOpened.socketPort()));
        ServerSessionWindowCursor firstCursor = windows.get(0).sessionWindowCursor();
        ServerSessionWindowCursor latestCursor = latestWindow.sessionWindowCursor();
        properties.setProperty("session_window_cursor_present", "true");
        properties.setProperty(
                "session_window_cursor_bytes",
                Integer.toString(HeadlessSnapshotSocketClient.SESSION_WINDOW_CURSOR_BYTES));
        properties.setProperty(
                "session_window_cursor_message_type_code",
                Integer.toUnsignedString(firstCursor.cursorMessageTypeCode()));
        properties.setProperty(
                "session_window_cursor_schema_version",
                Integer.toString(firstCursor.cursorSchemaVersion()));
        properties.setProperty(
                "session_window_cursor_session_id",
                Long.toString(firstCursor.sessionId()));
        properties.setProperty(
                "session_window_cursor_window_count",
                Integer.toString(windows.size()));
        properties.setProperty(
                "session_window_cursor_final_resume_cursor",
                Long.toString(latestCursor.nextResumeCursor()));
        ServerMovementCommandResult movementCommandResult = receipt.movementCommandResult();
        properties.setProperty(
                "movement_command_result_present",
                Boolean.toString(movementCommandResult != null));
        properties.setProperty(
                "movement_command_result_bytes",
                Integer.toString(receipt.movementCommandResultBytes()));
        if (movementCommandResult != null) {
            properties.setProperty(
                    "movement_command_result_message_type_code",
                    Integer.toUnsignedString(movementCommandResult.messageTypeCode()));
            properties.setProperty(
                    "movement_command_result_schema_version",
                    Integer.toString(movementCommandResult.schemaVersion()));
            properties.setProperty(
                    "movement_command_result_ship_id",
                    Long.toString(movementCommandResult.shipId()));
            properties.setProperty(
                    "movement_command_result_sim_tick",
                    Long.toString(movementCommandResult.simTick()));
            properties.setProperty(
                    "movement_command_result_intent",
                    movementCommandResult.movementIntent().name());
            properties.setProperty("movement_command_result", movementCommandResult.commandResult().name());
        }
        ServerWeaponGroupCommandResult weaponGroupCommandResult = receipt.weaponGroupCommandResult();
        properties.setProperty(
                "weapon_group_command_result_present",
                Boolean.toString(weaponGroupCommandResult != null));
        properties.setProperty(
                "weapon_group_command_result_bytes",
                Integer.toString(receipt.weaponGroupCommandResultBytes()));
        if (weaponGroupCommandResult != null) {
            properties.setProperty(
                    "weapon_group_command_result_message_type_code",
                    Integer.toUnsignedString(weaponGroupCommandResult.messageTypeCode()));
            properties.setProperty(
                    "weapon_group_command_result_schema_version",
                    Integer.toString(weaponGroupCommandResult.schemaVersion()));
            properties.setProperty(
                    "weapon_group_command_result_ship_id",
                    Long.toString(weaponGroupCommandResult.shipId()));
            properties.setProperty(
                    "weapon_group_command_result_sim_tick",
                    Long.toString(weaponGroupCommandResult.simTick()));
            properties.setProperty(
                    "weapon_group_command_result_group",
                    Integer.toString(weaponGroupCommandResult.group()));
            properties.setProperty(
                    "weapon_group_command_result",
                    weaponGroupCommandResult.commandResult().name());
        }
        ServerFireCommandResult fireCommandResult = receipt.fireCommandResult();
        properties.setProperty(
                "fire_command_result_present",
                Boolean.toString(fireCommandResult != null));
        properties.setProperty(
                "fire_command_result_bytes",
                Integer.toString(receipt.fireCommandResultBytes()));
        if (fireCommandResult != null) {
            properties.setProperty(
                    "fire_command_result_message_type_code",
                    Integer.toUnsignedString(fireCommandResult.messageTypeCode()));
            properties.setProperty(
                    "fire_command_result_schema_version",
                    Integer.toString(fireCommandResult.schemaVersion()));
            properties.setProperty(
                    "fire_command_result_ship_id",
                    Long.toString(fireCommandResult.shipId()));
            properties.setProperty(
                    "fire_command_result_sim_tick",
                    Long.toString(fireCommandResult.simTick()));
            properties.setProperty(
                    "fire_command_result_fire",
                    Boolean.toString(fireCommandResult.fire()));
            properties.setProperty("fire_command_result", fireCommandResult.commandResult().name());
        }
        ServerSystemCommandResult systemCommandResult = receipt.systemCommandResult();
        properties.setProperty(
                "system_command_result_present",
                Boolean.toString(systemCommandResult != null));
        properties.setProperty(
                "system_command_result_bytes",
                Integer.toString(receipt.systemCommandResultBytes()));
        if (systemCommandResult != null) {
            properties.setProperty(
                    "system_command_result_message_type_code",
                    Integer.toUnsignedString(systemCommandResult.messageTypeCode()));
            properties.setProperty(
                    "system_command_result_schema_version",
                    Integer.toString(systemCommandResult.schemaVersion()));
            properties.setProperty(
                    "system_command_result_ship_id",
                    Long.toString(systemCommandResult.shipId()));
            properties.setProperty(
                    "system_command_result_sim_tick",
                    Long.toString(systemCommandResult.simTick()));
            properties.setProperty(
                    "system_command_result_system_id",
                    Integer.toString(systemCommandResult.systemId()));
            properties.setProperty("system_command_result", systemCommandResult.commandResult().name());
        }
        properties.setProperty(
                "latest_completed_tick",
                latestFrame == null ? "-1" : Long.toString(latestFrame.result().completedTick()));
        properties.setProperty(
                "latest_state_hash",
                latestFrame == null ? "0" : Long.toString(latestFrame.result().stateHash()));
        properties.setProperty(
                "latest_entity_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().entityCount()));
        properties.setProperty(
                "latest_ship_count",
                latestFrame == null ? "-1" : Integer.toString(latestFrame.result().shipCount()));
        for (PersistentWindowReceipt window : windows) {
            String prefix = "window_" + (window.windowIndex() + 1) + "_";
            List<OfflineCombatStepWireFrame> windowFrames = window.frames();
            OfflineCombatStepWireFrame firstFrame = windowFrames.isEmpty() ? null : windowFrames.get(0);
            OfflineCombatStepWireFrame lastFrame =
                    windowFrames.isEmpty() ? null : windowFrames.get(windowFrames.size() - 1);
            properties.setProperty(
                    prefix + "receiver_artifact_path",
                    window.receiverArtifactPath().toAbsolutePath().normalize().toString());
            properties.setProperty(prefix + "frames_read", Integer.toString(windowFrames.size()));
            properties.setProperty(prefix + "expected_bytes", Long.toString(window.expectedBytes()));
            properties.setProperty(prefix + "bytes_received", Long.toString(window.bytesReceived()));
            ServerSessionWindowCursor windowCursor = window.sessionWindowCursor();
            properties.setProperty(
                    prefix + "session_window_cursor_bytes",
                    Integer.toString(window.sessionWindowCursorBytes()));
            properties.setProperty(
                    prefix + "session_window_cursor_message_type_code",
                    Integer.toUnsignedString(windowCursor.cursorMessageTypeCode()));
            properties.setProperty(
                    prefix + "session_window_cursor_schema_version",
                    Integer.toString(windowCursor.cursorSchemaVersion()));
            properties.setProperty(
                    prefix + "session_window_cursor_session_id",
                    Long.toString(windowCursor.sessionId()));
            properties.setProperty(
                    prefix + "session_window_cursor_window_ordinal",
                    Integer.toString(windowCursor.windowOrdinal()));
            properties.setProperty(
                    prefix + "session_window_cursor_previous_resume_cursor",
                    Long.toString(windowCursor.previousResumeCursor()));
            properties.setProperty(
                    prefix + "session_window_cursor_next_resume_cursor",
                    Long.toString(windowCursor.nextResumeCursor()));
            properties.setProperty(
                    prefix + "session_window_cursor_expected_frames",
                    Integer.toString(windowCursor.expectedFrames()));
            properties.setProperty(prefix + "initial_sync_bytes", Integer.toString(window.initialSyncBytes()));
            properties.setProperty(
                    prefix + "initial_sync_state_hash",
                    firstFrame == null ? "0" : Long.toString(window.initialSync().stateHash()));
            properties.setProperty(
                    prefix + "stream_completion_bytes",
                    Integer.toString(window.streamCompletionBytes()));
            properties.setProperty(
                    prefix + "stream_completion_completed_frames",
                    Integer.toString(window.streamCompletion().completedFrames()));
            properties.setProperty(
                    prefix + "first_completed_tick",
                    firstFrame == null ? "-1" : Long.toString(firstFrame.result().completedTick()));
            properties.setProperty(
                    prefix + "latest_completed_tick",
                    lastFrame == null ? "-1" : Long.toString(lastFrame.result().completedTick()));
            properties.setProperty(
                    prefix + "first_state_hash",
                    firstFrame == null ? "0" : Long.toString(firstFrame.result().stateHash()));
            properties.setProperty(
                    prefix + "latest_state_hash",
                    lastFrame == null ? "0" : Long.toString(lastFrame.result().stateHash()));
            properties.setProperty(
                    prefix + "latest_ship_count",
                    lastFrame == null ? "-1" : Integer.toString(lastFrame.result().shipCount()));
            properties.setProperty(prefix + "state_changes", Boolean.toString(window.stateChanges()));
        }
        try (OutputStream output = Files.newOutputStream(
                receiptPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "headless snapshot socket persistent client process receipt");
        }
    }

    private static boolean allFramesStableShipNetworkIdentityRange(List<OfflineCombatStepWireFrame> frames) {
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

    private static boolean allFramesSameShipNetworkIdentityRange(List<OfflineCombatStepWireFrame> frames) {
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

    private static void writeClientAuthorityProperties(
            Properties properties,
            ClientHandshake handshake) {
        properties.setProperty("client_authority_id", handshake.clientAuthorityId());
        properties.setProperty("client_owned_ship_id", Long.toString(handshake.clientOwnedShipId()));
        properties.setProperty("client_seat_token", handshake.seatToken());
        properties.setProperty("client_seat_role", handshake.seatRole().name());
        properties.setProperty("client_content_fingerprint", handshake.contentFingerprint());
    }
}
