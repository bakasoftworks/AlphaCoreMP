package cc.starsector.server.client;

import static cc.starsector.server.client.HeadlessSnapshotSocketClient.*;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientFireCommand;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientHandshake;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientMovementCommand;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientSessionResumeClaim;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientSystemCommand;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientWeaponGroupCommand;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerAdmissionAck;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerFireCommandResult;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerInitialSync;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerMovementCommandResult;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerSessionWindowCursor;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerStreamCompletion;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerSystemCommandResult;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerTransportSessionOpened;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ServerWeaponGroupCommandResult;
import cc.starsector.server.protocol.ClientSessionResumeClaimMessage;
import cc.starsector.server.protocol.ClientShipFireCommandMessage;
import cc.starsector.server.protocol.ClientShipMovementCommandMessage;
import cc.starsector.server.protocol.ClientShipSystemCommandMessage;
import cc.starsector.server.protocol.ClientShipWeaponGroupCommandMessage;
import cc.starsector.server.protocol.NetworkCapabilityManifest;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.OfflineCombatStepSnapshotMessage;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import cc.starsector.server.protocol.ServerInitialSyncMessage;
import cc.starsector.server.protocol.ServerSessionWindowCursorMessage;
import cc.starsector.server.protocol.ServerShipFireCommandResultMessage;
import cc.starsector.server.protocol.ServerShipMovementCommandResultMessage;
import cc.starsector.server.protocol.ServerShipSystemCommandResultMessage;
import cc.starsector.server.protocol.ServerShipWeaponGroupCommandResultMessage;
import cc.starsector.server.protocol.ServerTransportSessionOpenedMessage;
import cc.starsector.server.protocol.SnapshotAdmissionAckMessage;
import cc.starsector.server.protocol.SnapshotAdmissionRequestMessage;
import cc.starsector.server.protocol.SnapshotStreamCompletedMessage;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepResult;
import cc.starsector.server.runtime.OfflineCombatStepWireFrame;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

final class HeadlessSnapshotControlWire {
    private HeadlessSnapshotControlWire() {
    }
    static void writeHandshake(OutputStream output, ClientHandshake handshake) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(handshake, "handshake");
        requireSnapshotHandshake(handshake);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(HANDSHAKE_MAGIC);
        data.writeShort(HANDSHAKE_VERSION);
        data.writeInt(handshake.admissionRequestMessageTypeCode());
        data.writeShort(handshake.admissionRequestSchemaVersion());
        data.writeInt(handshake.wireTypeCode());
        data.writeShort(handshake.schemaVersion());
        data.writeInt(handshake.streamTypeCode());
        data.writeShort(handshake.streamVersion());
        data.writeInt(handshake.expectedFrames());
        data.writeShort(checkedManifestVersion(handshake.capabilityManifest()));
        data.writeInt(handshake.capabilityManifest().entryCount());
        data.writeInt(handshake.capabilityManifest().streamCount());
        data.writeInt((int) handshake.capabilityManifest().fingerprintCrc32());
        writeFixedClientAuthorityId(data, handshake.clientAuthorityId());
        data.writeLong(handshake.clientOwnedShipId());
        writeReservedSeatField(data, handshake.seatToken(), SEAT_TOKEN_BYTES, "seat token");
        data.writeByte(handshake.seatRole().wireCode());
        writeReservedSeatField(
                data, handshake.contentFingerprint(), CONTENT_FINGERPRINT_BYTES, "content fingerprint");
        data.flush();
    }

    static ClientHandshake readHandshake(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot client handshake");
        }
        if (magic != HANDSHAKE_MAGIC) {
            throw new IOException("headless snapshot client handshake magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int version = data.readUnsignedShort();
        if (version != HANDSHAKE_VERSION) {
            throw new IOException("unsupported headless snapshot client handshake version: " + version);
        }
        int admissionRequestMessageTypeCode = data.readInt();
        int admissionRequestSchemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry admissionRequestEntry =
                requireRegisteredAdmissionRequestIdentity(
                        admissionRequestMessageTypeCode,
                        admissionRequestSchemaVersion);
        int wireTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        int streamTypeCode = data.readInt();
        int streamVersion = data.readUnsignedShort();
        int expectedFrames = data.readInt();
        int manifestVersion = data.readUnsignedShort();
        int registryEntryCount = data.readInt();
        int registryStreamCount = data.readInt();
        long capabilityFingerprintCrc32 = Integer.toUnsignedLong(data.readInt());
        String clientAuthorityId = readFixedClientAuthorityId(data);
        long clientOwnedShipId = data.readLong();
        String seatToken = readReservedSeatField(data, SEAT_TOKEN_BYTES, "seat token");
        cc.starsector.server.protocol.SeatRole seatRole;
        try {
            seatRole = cc.starsector.server.protocol.SeatRole.fromWireCode(data.readUnsignedByte());
        } catch (IllegalArgumentException badRole) {
            throw new IOException("headless snapshot client handshake seat role is malformed", badRole);
        }
        String contentFingerprint =
                readReservedSeatField(data, CONTENT_FINGERPRINT_BYTES, "content fingerprint");
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(wireTypeCode, schemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client advertised unsupported wire identity: "
                            + Integer.toUnsignedString(wireTypeCode)
                            + " v" + schemaVersion,
                    badIdentity);
        }
        SnapshotStreamIdentity streamIdentity;
        try {
            streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(streamTypeCode, streamVersion);
        } catch (IllegalArgumentException badStreamIdentity) {
            throw new IOException(
                    "headless snapshot client advertised unsupported stream identity: "
                            + Integer.toUnsignedString(streamTypeCode)
                            + " v" + streamVersion,
                    badStreamIdentity);
        }
        NetworkCapabilityManifest capabilityManifest;
        try {
            capabilityManifest = new NetworkCapabilityManifest(
                    manifestVersion,
                    registryEntryCount,
                    registryStreamCount,
                    capabilityFingerprintCrc32);
        } catch (IllegalArgumentException badCapability) {
            throw new IOException("headless snapshot client advertised malformed capability manifest", badCapability);
        }
        ClientHandshake handshake = new ClientHandshake(
                admissionRequestEntry.messageId(),
                admissionRequestMessageTypeCode,
                admissionRequestSchemaVersion,
                entry.messageId(),
                wireTypeCode,
                schemaVersion,
                streamIdentity.streamId(),
                streamTypeCode,
                streamVersion,
                expectedFrames,
                capabilityManifest,
                clientAuthorityId,
                clientOwnedShipId,
                seatToken,
                seatRole,
                contentFingerprint);
        requireSnapshotHandshake(handshake);
        return handshake;
    }

    private static void writeFixedClientAuthorityId(
            DataOutputStream data,
            String clientAuthorityId) throws IOException {
        byte[] bytes = Objects.requireNonNull(clientAuthorityId, "clientAuthorityId")
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > CLIENT_AUTHORITY_ID_BYTES) {
            throw new IOException("client authority id must be 1.." + CLIENT_AUTHORITY_ID_BYTES
                    + " UTF-8 bytes");
        }
        data.writeShort(bytes.length);
        data.write(bytes);
        for (int i = bytes.length; i < CLIENT_AUTHORITY_ID_BYTES; i++) {
            data.writeByte(0);
        }
    }

    private static String readFixedClientAuthorityId(DataInputStream data) throws IOException {
        int length = data.readUnsignedShort();
        if (length <= 0 || length > CLIENT_AUTHORITY_ID_BYTES) {
            throw new IOException("headless snapshot client authority id length out of range: " + length);
        }
        byte[] padded = new byte[CLIENT_AUTHORITY_ID_BYTES];
        data.readFully(padded);
        for (int i = length; i < padded.length; i++) {
            if (padded[i] != 0) {
                throw new IOException("headless snapshot client authority id padding was not zeroed");
            }
        }
        return new String(padded, 0, length, StandardCharsets.UTF_8);
    }

    // R1 reserved seat fields (HSCC v2): same fixed-width zero-padded shape as the
    // authority id, but length 0 is legal — blank means "unasserted" until R8 enforces.
    private static void writeReservedSeatField(
            DataOutputStream data,
            String value,
            int capacityBytes,
            String fieldName) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, fieldName).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > capacityBytes) {
            throw new IOException(fieldName + " must be 0.." + capacityBytes + " UTF-8 bytes");
        }
        data.writeShort(bytes.length);
        data.write(bytes);
        for (int i = bytes.length; i < capacityBytes; i++) {
            data.writeByte(0);
        }
    }

    private static String readReservedSeatField(
            DataInputStream data,
            int capacityBytes,
            String fieldName) throws IOException {
        int length = data.readUnsignedShort();
        if (length > capacityBytes) {
            throw new IOException("headless snapshot client handshake " + fieldName
                    + " length out of range: " + length);
        }
        byte[] padded = new byte[capacityBytes];
        data.readFully(padded);
        for (int i = length; i < padded.length; i++) {
            if (padded[i] != 0) {
                throw new IOException("headless snapshot client handshake " + fieldName
                        + " padding was not zeroed");
            }
        }
        return new String(padded, 0, length, StandardCharsets.UTF_8);
    }

    static ClientMovementCommand movementCommand(
            long shipId,
            long simTick,
            ShipCommandSink.Movement movementIntent) {
        return new ClientMovementCommand(
                ClientShipMovementCommandMessage.MESSAGE_ID,
                MOVEMENT_COMMAND_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(MOVEMENT_COMMAND_SCHEMA_VERSION),
                shipId,
                simTick,
                movementIntent);
    }

    static void writeMovementCommand(
            OutputStream output,
            ClientMovementCommand movementCommand) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(movementCommand, "movementCommand");
        requireMovementCommandIdentity(movementCommand);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MOVEMENT_COMMAND_MAGIC);
        data.writeInt(movementCommand.messageTypeCode());
        data.writeShort(movementCommand.schemaVersion());
        data.writeLong(movementCommand.shipId());
        data.writeLong(movementCommand.simTick());
        data.writeInt(movementCommand.movementIntent().ordinal());
        data.flush();
    }

    static ClientMovementCommand readMovementCommand(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot client movement command");
        }
        if (magic != MOVEMENT_COMMAND_MAGIC) {
            throw new IOException("headless snapshot client movement command magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int messageTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry movementEntry =
                requireRegisteredMovementCommandIdentity(messageTypeCode, schemaVersion);
        long shipId = data.readLong();
        long simTick = data.readLong();
        int movementOrdinal = data.readInt();
        ShipCommandSink.Movement[] movements = ShipCommandSink.Movement.values();
        if (movementOrdinal < 0 || movementOrdinal >= movements.length) {
            throw new IOException("headless snapshot client movement command has invalid movement ordinal: "
                    + movementOrdinal);
        }
        ClientMovementCommand movementCommand = new ClientMovementCommand(
                movementEntry.messageId(),
                messageTypeCode,
                schemaVersion,
                shipId,
                simTick,
                movements[movementOrdinal]);
        requireMovementCommandIdentity(movementCommand);
        return movementCommand;
    }

    static ClientFireCommand fireCommand(
            long shipId,
            long simTick,
            boolean fire) {
        return new ClientFireCommand(
                ClientShipFireCommandMessage.MESSAGE_ID,
                FIRE_COMMAND_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(FIRE_COMMAND_SCHEMA_VERSION),
                shipId,
                simTick,
                fire);
    }

    static void writeFireCommand(
            OutputStream output,
            ClientFireCommand fireCommand) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(fireCommand, "fireCommand");
        requireFireCommandIdentity(fireCommand);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(FIRE_COMMAND_MAGIC);
        data.writeInt(fireCommand.messageTypeCode());
        data.writeShort(fireCommand.schemaVersion());
        data.writeLong(fireCommand.shipId());
        data.writeLong(fireCommand.simTick());
        data.writeBoolean(fireCommand.fire());
        data.flush();
    }

    static ClientFireCommand readFireCommand(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot client fire command");
        }
        if (magic != FIRE_COMMAND_MAGIC) {
            throw new IOException("headless snapshot client fire command magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int messageTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry fireEntry =
                requireRegisteredFireCommandIdentity(messageTypeCode, schemaVersion);
        long shipId = data.readLong();
        long simTick = data.readLong();
        boolean fire = data.readBoolean();
        ClientFireCommand fireCommand = new ClientFireCommand(
                fireEntry.messageId(),
                messageTypeCode,
                schemaVersion,
                shipId,
                simTick,
                fire);
        requireFireCommandIdentity(fireCommand);
        return fireCommand;
    }

    static ClientWeaponGroupCommand weaponGroupCommand(
            long shipId,
            long simTick,
            int group) {
        return new ClientWeaponGroupCommand(
                ClientShipWeaponGroupCommandMessage.MESSAGE_ID,
                WEAPON_GROUP_COMMAND_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(WEAPON_GROUP_COMMAND_SCHEMA_VERSION),
                shipId,
                simTick,
                group);
    }

    static void writeWeaponGroupCommand(
            OutputStream output,
            ClientWeaponGroupCommand weaponGroupCommand) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(weaponGroupCommand, "weaponGroupCommand");
        requireWeaponGroupCommandIdentity(weaponGroupCommand);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(WEAPON_GROUP_COMMAND_MAGIC);
        data.writeInt(weaponGroupCommand.messageTypeCode());
        data.writeShort(weaponGroupCommand.schemaVersion());
        data.writeLong(weaponGroupCommand.shipId());
        data.writeLong(weaponGroupCommand.simTick());
        data.writeInt(weaponGroupCommand.group());
        data.flush();
    }

    static ClientWeaponGroupCommand readWeaponGroupCommand(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot client weapon-group command");
        }
        if (magic != WEAPON_GROUP_COMMAND_MAGIC) {
            throw new IOException("headless snapshot client weapon-group command magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int messageTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry weaponGroupEntry =
                requireRegisteredWeaponGroupCommandIdentity(messageTypeCode, schemaVersion);
        long shipId = data.readLong();
        long simTick = data.readLong();
        int group = data.readInt();
        ClientWeaponGroupCommand weaponGroupCommand = new ClientWeaponGroupCommand(
                weaponGroupEntry.messageId(),
                messageTypeCode,
                schemaVersion,
                shipId,
                simTick,
                group);
        requireWeaponGroupCommandIdentity(weaponGroupCommand);
        return weaponGroupCommand;
    }

    static ClientSystemCommand systemCommand(
            long shipId,
            long simTick,
            int systemId) {
        return new ClientSystemCommand(
                ClientShipSystemCommandMessage.MESSAGE_ID,
                SYSTEM_COMMAND_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(SYSTEM_COMMAND_SCHEMA_VERSION),
                shipId,
                simTick,
                systemId);
    }

    static void writeSystemCommand(
            OutputStream output,
            ClientSystemCommand systemCommand) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(systemCommand, "systemCommand");
        requireSystemCommandIdentity(systemCommand);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(SYSTEM_COMMAND_MAGIC);
        data.writeInt(systemCommand.messageTypeCode());
        data.writeShort(systemCommand.schemaVersion());
        data.writeLong(systemCommand.shipId());
        data.writeLong(systemCommand.simTick());
        data.writeInt(systemCommand.systemId());
        data.flush();
    }

    static ClientSystemCommand readSystemCommand(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot client system command");
        }
        if (magic != SYSTEM_COMMAND_MAGIC) {
            throw new IOException("headless snapshot client system command magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int messageTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry systemEntry =
                requireRegisteredSystemCommandIdentity(messageTypeCode, schemaVersion);
        long shipId = data.readLong();
        long simTick = data.readLong();
        int systemId = data.readInt();
        ClientSystemCommand systemCommand = new ClientSystemCommand(
                systemEntry.messageId(),
                messageTypeCode,
                schemaVersion,
                shipId,
                simTick,
                systemId);
        requireSystemCommandIdentity(systemCommand);
        return systemCommand;
    }

    static ClientSessionResumeClaim sessionResumeClaim(
            ClientHandshake handshake,
            long sessionId,
            int windowOrdinal,
            long previousResumeCursor,
            long nextResumeCursor) {
        Objects.requireNonNull(handshake, "handshake");
        return new ClientSessionResumeClaim(
                ClientSessionResumeClaimMessage.MESSAGE_ID,
                SESSION_RESUME_CLAIM_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(SESSION_RESUME_CLAIM_SCHEMA_VERSION),
                handshake.messageId(),
                handshake.wireTypeCode(),
                handshake.schemaVersion(),
                handshake.streamId(),
                handshake.streamTypeCode(),
                handshake.streamVersion(),
                sessionId,
                windowOrdinal,
                previousResumeCursor,
                nextResumeCursor,
                handshake.expectedFrames(),
                handshake.capabilityManifest());
    }

    static ClientSessionResumeClaim sessionResumeClaim(
            ClientHandshake handshake,
            ServerSessionWindowCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        return sessionResumeClaim(
                handshake,
                cursor.sessionId(),
                cursor.windowOrdinal(),
                cursor.previousResumeCursor(),
                cursor.nextResumeCursor());
    }

    static void writeSessionResumeClaim(
            OutputStream output,
            ClientSessionResumeClaim claim) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(claim, "claim");
        requireSessionResumeClaimIdentity(claim);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(SESSION_RESUME_CLAIM_MAGIC);
        data.writeInt(claim.claimMessageTypeCode());
        data.writeShort(claim.claimSchemaVersion());
        data.writeInt(claim.wireTypeCode());
        data.writeShort(claim.schemaVersion());
        data.writeInt(claim.streamTypeCode());
        data.writeShort(claim.streamVersion());
        data.writeLong(claim.sessionId());
        data.writeInt(claim.windowOrdinal());
        data.writeLong(claim.previousResumeCursor());
        data.writeLong(claim.nextResumeCursor());
        data.writeInt(claim.expectedFrames());
        data.writeShort(checkedManifestVersion(claim.capabilityManifest()));
        data.writeInt(claim.capabilityManifest().entryCount());
        data.writeInt(claim.capabilityManifest().streamCount());
        data.writeInt((int) claim.capabilityManifest().fingerprintCrc32());
        data.flush();
    }

    static ClientSessionResumeClaim readSessionResumeClaim(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(handshake, "handshake");
        ClientSessionResumeClaim claim = readSessionResumeClaim(input);
        requireSessionResumeClaimMatchesHandshake(claim, handshake);
        return claim;
    }

    static ClientSessionResumeClaim readSessionResumeClaim(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot client session resume claim");
        }
        if (magic != SESSION_RESUME_CLAIM_MAGIC) {
            throw new IOException("headless snapshot client session resume claim magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int claimMessageTypeCode = data.readInt();
        int claimSchemaVersion = data.readUnsignedShort();
        requireRegisteredSessionResumeClaimIdentity(claimMessageTypeCode, claimSchemaVersion);
        int wireTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        int streamTypeCode = data.readInt();
        int streamVersion = data.readUnsignedShort();
        long sessionId = data.readLong();
        int windowOrdinal = data.readInt();
        long previousResumeCursor = data.readLong();
        long nextResumeCursor = data.readLong();
        int expectedFrames = data.readInt();
        int manifestVersion = data.readUnsignedShort();
        int registryEntryCount = data.readInt();
        int registryStreamCount = data.readInt();
        long capabilityFingerprintCrc32 = Integer.toUnsignedLong(data.readInt());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(wireTypeCode, schemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client session resume claim uses unsupported wire identity: "
                            + Integer.toUnsignedString(wireTypeCode)
                            + " v" + schemaVersion,
                    badIdentity);
        }
        SnapshotStreamIdentity streamIdentity;
        try {
            streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(streamTypeCode, streamVersion);
        } catch (IllegalArgumentException badStreamIdentity) {
            throw new IOException(
                    "headless snapshot client session resume claim uses unsupported stream identity: "
                            + Integer.toUnsignedString(streamTypeCode)
                            + " v" + streamVersion,
                    badStreamIdentity);
        }
        NetworkCapabilityManifest capabilityManifest;
        try {
            capabilityManifest = new NetworkCapabilityManifest(
                    manifestVersion,
                    registryEntryCount,
                    registryStreamCount,
                    capabilityFingerprintCrc32);
        } catch (IllegalArgumentException badCapability) {
            throw new IOException("headless snapshot client session resume claim has malformed capability manifest",
                    badCapability);
        }
        ClientSessionResumeClaim claim = new ClientSessionResumeClaim(
                ClientSessionResumeClaimMessage.MESSAGE_ID,
                claimMessageTypeCode,
                claimSchemaVersion,
                entry.messageId(),
                wireTypeCode,
                schemaVersion,
                streamIdentity.streamId(),
                streamTypeCode,
                streamVersion,
                sessionId,
                windowOrdinal,
                previousResumeCursor,
                nextResumeCursor,
                expectedFrames,
                capabilityManifest);
        requireSessionResumeClaimIdentity(claim);
        return claim;
    }

    static void requireSessionResumeClaimMatchesCursor(
            ClientSessionResumeClaim claim,
            ServerSessionWindowCursor cursor) throws IOException {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(cursor, "cursor");
        requireSessionResumeClaimIdentity(claim);
        requireSessionWindowCursorIdentity(cursor);
        if (!claim.messageId().equals(cursor.messageId())
                || claim.wireTypeCode() != cursor.wireTypeCode()
                || claim.schemaVersion() != cursor.schemaVersion()
                || !claim.streamId().equals(cursor.streamId())
                || claim.streamTypeCode() != cursor.streamTypeCode()
                || claim.streamVersion() != cursor.streamVersion()
                || claim.sessionId() != cursor.sessionId()
                || claim.windowOrdinal() != cursor.windowOrdinal()
                || claim.previousResumeCursor() != cursor.previousResumeCursor()
                || claim.nextResumeCursor() != cursor.nextResumeCursor()
                || claim.expectedFrames() != cursor.expectedFrames()
                || !claim.capabilityManifest().equals(cursor.capabilityManifest())) {
            throw new IOException("headless snapshot client session resume claim does not match cursor");
        }
    }

    static ServerMovementCommandResult movementCommandResult(
            ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult) {
        Objects.requireNonNull(movementCommand, "movementCommand");
        return new ServerMovementCommandResult(
                ServerShipMovementCommandResultMessage.MESSAGE_ID,
                MOVEMENT_COMMAND_RESULT_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(MOVEMENT_COMMAND_RESULT_SCHEMA_VERSION),
                movementCommand.shipId(),
                movementCommand.simTick(),
                movementCommand.movementIntent(),
                commandResult);
    }

    static void writeMovementCommandResult(
            OutputStream output,
            ServerMovementCommandResult movementCommandResult) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(movementCommandResult, "movementCommandResult");
        requireMovementCommandResultIdentity(movementCommandResult);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MOVEMENT_COMMAND_RESULT_MAGIC);
        data.writeInt(movementCommandResult.messageTypeCode());
        data.writeShort(movementCommandResult.schemaVersion());
        data.writeLong(movementCommandResult.shipId());
        data.writeLong(movementCommandResult.simTick());
        data.writeInt(movementCommandResult.movementIntent().ordinal());
        data.writeInt(movementCommandResult.commandResult().ordinal());
        data.flush();
    }

    static ServerMovementCommandResult readMovementCommandResult(
            InputStream input,
            ClientMovementCommand expectedCommand) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expectedCommand, "expectedCommand");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot server movement command result");
        }
        if (magic != MOVEMENT_COMMAND_RESULT_MAGIC) {
            throw new IOException("headless snapshot server movement command result magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int messageTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry resultEntry =
                requireRegisteredMovementCommandResultIdentity(messageTypeCode, schemaVersion);
        long shipId = data.readLong();
        long simTick = data.readLong();
        int movementOrdinal = data.readInt();
        ShipCommandSink.Movement[] movements = ShipCommandSink.Movement.values();
        if (movementOrdinal < 0 || movementOrdinal >= movements.length) {
            throw new IOException("headless snapshot server movement command result has invalid movement ordinal: "
                    + movementOrdinal);
        }
        int resultOrdinal = data.readInt();
        ShipCommandSink.Result[] results = ShipCommandSink.Result.values();
        if (resultOrdinal < 0 || resultOrdinal >= results.length) {
            throw new IOException("headless snapshot server movement command result has invalid result ordinal: "
                    + resultOrdinal);
        }
        ServerMovementCommandResult movementCommandResult = new ServerMovementCommandResult(
                resultEntry.messageId(),
                messageTypeCode,
                schemaVersion,
                shipId,
                simTick,
                movements[movementOrdinal],
                results[resultOrdinal]);
        requireMovementCommandResultIdentity(movementCommandResult);
        if (movementCommandResult.shipId() != expectedCommand.shipId()
                || movementCommandResult.simTick() != expectedCommand.simTick()
                || movementCommandResult.movementIntent() != expectedCommand.movementIntent()) {
            throw new IOException("headless snapshot server movement command result does not match request");
        }
        return movementCommandResult;
    }

    static ServerFireCommandResult fireCommandResult(
            ClientFireCommand fireCommand,
            ShipCommandSink.Result commandResult) {
        Objects.requireNonNull(fireCommand, "fireCommand");
        return new ServerFireCommandResult(
                ServerShipFireCommandResultMessage.MESSAGE_ID,
                FIRE_COMMAND_RESULT_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(FIRE_COMMAND_RESULT_SCHEMA_VERSION),
                fireCommand.shipId(),
                fireCommand.simTick(),
                fireCommand.fire(),
                commandResult);
    }

    static void writeFireCommandResult(
            OutputStream output,
            ServerFireCommandResult fireCommandResult) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(fireCommandResult, "fireCommandResult");
        requireFireCommandResultIdentity(fireCommandResult);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(FIRE_COMMAND_RESULT_MAGIC);
        data.writeInt(fireCommandResult.messageTypeCode());
        data.writeShort(fireCommandResult.schemaVersion());
        data.writeLong(fireCommandResult.shipId());
        data.writeLong(fireCommandResult.simTick());
        data.writeBoolean(fireCommandResult.fire());
        data.writeInt(fireCommandResult.commandResult().ordinal());
        data.flush();
    }

    static ServerFireCommandResult readFireCommandResult(
            InputStream input,
            ClientFireCommand expectedCommand) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expectedCommand, "expectedCommand");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot server fire command result");
        }
        if (magic != FIRE_COMMAND_RESULT_MAGIC) {
            throw new IOException("headless snapshot server fire command result magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int messageTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry resultEntry =
                requireRegisteredFireCommandResultIdentity(messageTypeCode, schemaVersion);
        long shipId = data.readLong();
        long simTick = data.readLong();
        boolean fire = data.readBoolean();
        int resultOrdinal = data.readInt();
        ShipCommandSink.Result[] results = ShipCommandSink.Result.values();
        if (resultOrdinal < 0 || resultOrdinal >= results.length) {
            throw new IOException("headless snapshot server fire command result has invalid result ordinal: "
                    + resultOrdinal);
        }
        ServerFireCommandResult fireCommandResult = new ServerFireCommandResult(
                resultEntry.messageId(),
                messageTypeCode,
                schemaVersion,
                shipId,
                simTick,
                fire,
                results[resultOrdinal]);
        requireFireCommandResultIdentity(fireCommandResult);
        if (fireCommandResult.shipId() != expectedCommand.shipId()
                || fireCommandResult.simTick() != expectedCommand.simTick()
                || fireCommandResult.fire() != expectedCommand.fire()) {
            throw new IOException("headless snapshot server fire command result does not match request");
        }
        return fireCommandResult;
    }

    static ServerWeaponGroupCommandResult weaponGroupCommandResult(
            ClientWeaponGroupCommand weaponGroupCommand,
            ShipCommandSink.Result commandResult) {
        Objects.requireNonNull(weaponGroupCommand, "weaponGroupCommand");
        return new ServerWeaponGroupCommandResult(
                ServerShipWeaponGroupCommandResultMessage.MESSAGE_ID,
                WEAPON_GROUP_COMMAND_RESULT_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(WEAPON_GROUP_COMMAND_RESULT_SCHEMA_VERSION),
                weaponGroupCommand.shipId(),
                weaponGroupCommand.simTick(),
                weaponGroupCommand.group(),
                commandResult);
    }

    static void writeWeaponGroupCommandResult(
            OutputStream output,
            ServerWeaponGroupCommandResult weaponGroupCommandResult) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(weaponGroupCommandResult, "weaponGroupCommandResult");
        requireWeaponGroupCommandResultIdentity(weaponGroupCommandResult);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(WEAPON_GROUP_COMMAND_RESULT_MAGIC);
        data.writeInt(weaponGroupCommandResult.messageTypeCode());
        data.writeShort(weaponGroupCommandResult.schemaVersion());
        data.writeLong(weaponGroupCommandResult.shipId());
        data.writeLong(weaponGroupCommandResult.simTick());
        data.writeInt(weaponGroupCommandResult.group());
        data.writeInt(weaponGroupCommandResult.commandResult().ordinal());
        data.flush();
    }

    static ServerWeaponGroupCommandResult readWeaponGroupCommandResult(
            InputStream input,
            ClientWeaponGroupCommand expectedCommand) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expectedCommand, "expectedCommand");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot server weapon-group command result");
        }
        if (magic != WEAPON_GROUP_COMMAND_RESULT_MAGIC) {
            throw new IOException("headless snapshot server weapon-group command result magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int messageTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry resultEntry =
                requireRegisteredWeaponGroupCommandResultIdentity(messageTypeCode, schemaVersion);
        long shipId = data.readLong();
        long simTick = data.readLong();
        int group = data.readInt();
        int resultOrdinal = data.readInt();
        ShipCommandSink.Result[] results = ShipCommandSink.Result.values();
        if (resultOrdinal < 0 || resultOrdinal >= results.length) {
            throw new IOException("headless snapshot server weapon-group command result has invalid result ordinal: "
                    + resultOrdinal);
        }
        ServerWeaponGroupCommandResult weaponGroupCommandResult = new ServerWeaponGroupCommandResult(
                resultEntry.messageId(),
                messageTypeCode,
                schemaVersion,
                shipId,
                simTick,
                group,
                results[resultOrdinal]);
        requireWeaponGroupCommandResultIdentity(weaponGroupCommandResult);
        if (weaponGroupCommandResult.shipId() != expectedCommand.shipId()
                || weaponGroupCommandResult.simTick() != expectedCommand.simTick()
                || weaponGroupCommandResult.group() != expectedCommand.group()) {
            throw new IOException("headless snapshot server weapon-group command result does not match request");
        }
        return weaponGroupCommandResult;
    }

    static ServerSystemCommandResult systemCommandResult(
            ClientSystemCommand systemCommand,
            ShipCommandSink.Result commandResult) {
        Objects.requireNonNull(systemCommand, "systemCommand");
        return new ServerSystemCommandResult(
                ServerShipSystemCommandResultMessage.MESSAGE_ID,
                SYSTEM_COMMAND_RESULT_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(SYSTEM_COMMAND_RESULT_SCHEMA_VERSION),
                systemCommand.shipId(),
                systemCommand.simTick(),
                systemCommand.systemId(),
                commandResult);
    }

    static void writeSystemCommandResult(
            OutputStream output,
            ServerSystemCommandResult systemCommandResult) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(systemCommandResult, "systemCommandResult");
        requireSystemCommandResultIdentity(systemCommandResult);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(SYSTEM_COMMAND_RESULT_MAGIC);
        data.writeInt(systemCommandResult.messageTypeCode());
        data.writeShort(systemCommandResult.schemaVersion());
        data.writeLong(systemCommandResult.shipId());
        data.writeLong(systemCommandResult.simTick());
        data.writeInt(systemCommandResult.systemId());
        data.writeInt(systemCommandResult.commandResult().ordinal());
        data.flush();
    }

    static ServerSystemCommandResult readSystemCommandResult(
            InputStream input,
            ClientSystemCommand expectedCommand) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expectedCommand, "expectedCommand");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot server system command result");
        }
        if (magic != SYSTEM_COMMAND_RESULT_MAGIC) {
            throw new IOException("headless snapshot server system command result magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int messageTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        ProtocolRegistryEntry resultEntry =
                requireRegisteredSystemCommandResultIdentity(messageTypeCode, schemaVersion);
        long shipId = data.readLong();
        long simTick = data.readLong();
        int systemId = data.readInt();
        int resultOrdinal = data.readInt();
        ShipCommandSink.Result[] results = ShipCommandSink.Result.values();
        if (resultOrdinal < 0 || resultOrdinal >= results.length) {
            throw new IOException("headless snapshot server system command result has invalid result ordinal: "
                    + resultOrdinal);
        }
        ServerSystemCommandResult systemCommandResult = new ServerSystemCommandResult(
                resultEntry.messageId(),
                messageTypeCode,
                schemaVersion,
                shipId,
                simTick,
                systemId,
                results[resultOrdinal]);
        requireSystemCommandResultIdentity(systemCommandResult);
        if (systemCommandResult.shipId() != expectedCommand.shipId()
                || systemCommandResult.simTick() != expectedCommand.simTick()
                || systemCommandResult.systemId() != expectedCommand.systemId()) {
            throw new IOException("headless snapshot server system command result does not match request");
        }
        return systemCommandResult;
    }

    static void writeAdmissionAck(OutputStream output, ServerAdmissionAck admissionAck) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(admissionAck, "admissionAck");
        requireAdmissionAckIdentity(admissionAck);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(ADMISSION_ACK_MAGIC);
        data.writeInt(admissionAck.ackMessageTypeCode());
        data.writeShort(admissionAck.ackSchemaVersion());
        data.writeBoolean(admissionAck.accepted());
        data.writeInt(admissionAck.wireTypeCode());
        data.writeShort(admissionAck.schemaVersion());
        data.writeInt(admissionAck.streamTypeCode());
        data.writeShort(admissionAck.streamVersion());
        data.writeInt(admissionAck.expectedFrames());
        data.writeShort(checkedManifestVersion(admissionAck.capabilityManifest()));
        data.writeInt(admissionAck.capabilityManifest().entryCount());
        data.writeInt(admissionAck.capabilityManifest().streamCount());
        data.writeInt((int) admissionAck.capabilityManifest().fingerprintCrc32());
        data.flush();
    }

    static ServerAdmissionAck readAdmissionAck(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(handshake, "handshake");
        ServerAdmissionAck admissionAck = readAdmissionAck(input);
        requireAdmissionAckMatchesHandshake(admissionAck, handshake);
        return admissionAck;
    }

    static ServerAdmissionAck readAdmissionAck(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot server admission acknowledgement");
        }
        if (magic != ADMISSION_ACK_MAGIC) {
            throw new IOException("headless snapshot server admission acknowledgement magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int ackMessageTypeCode = data.readInt();
        int ackSchemaVersion = data.readUnsignedShort();
        requireRegisteredAdmissionAckIdentity(ackMessageTypeCode, ackSchemaVersion);
        boolean accepted = data.readBoolean();
        int wireTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        int streamTypeCode = data.readInt();
        int streamVersion = data.readUnsignedShort();
        int expectedFrames = data.readInt();
        int manifestVersion = data.readUnsignedShort();
        int registryEntryCount = data.readInt();
        int registryStreamCount = data.readInt();
        long capabilityFingerprintCrc32 = Integer.toUnsignedLong(data.readInt());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(wireTypeCode, schemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot server admission acknowledged unsupported wire identity: "
                            + Integer.toUnsignedString(wireTypeCode)
                            + " v" + schemaVersion,
                    badIdentity);
        }
        SnapshotStreamIdentity streamIdentity;
        try {
            streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(streamTypeCode, streamVersion);
        } catch (IllegalArgumentException badStreamIdentity) {
            throw new IOException(
                    "headless snapshot server admission acknowledged unsupported stream identity: "
                            + Integer.toUnsignedString(streamTypeCode)
                            + " v" + streamVersion,
                    badStreamIdentity);
        }
        NetworkCapabilityManifest capabilityManifest;
        try {
            capabilityManifest = new NetworkCapabilityManifest(
                    manifestVersion,
                    registryEntryCount,
                    registryStreamCount,
                    capabilityFingerprintCrc32);
        } catch (IllegalArgumentException badCapability) {
            throw new IOException(
                    "headless snapshot server admission acknowledged malformed capability manifest",
                    badCapability);
        }
        ServerAdmissionAck admissionAck = new ServerAdmissionAck(
                accepted,
                ackMessageTypeCode,
                ackSchemaVersion,
                entry.messageId(),
                wireTypeCode,
                schemaVersion,
                streamIdentity.streamId(),
                streamTypeCode,
                streamVersion,
                expectedFrames,
                capabilityManifest);
        requireAdmissionAckIdentity(admissionAck);
        return admissionAck;
    }

    static void writeTransportSessionOpened(
            OutputStream output,
            ServerTransportSessionOpened sessionOpened) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(sessionOpened, "sessionOpened");
        requireTransportSessionOpenedIdentity(sessionOpened);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(TRANSPORT_SESSION_OPENED_MAGIC);
        data.writeInt(sessionOpened.sessionMessageTypeCode());
        data.writeShort(sessionOpened.sessionSchemaVersion());
        data.writeInt(sessionOpened.wireTypeCode());
        data.writeShort(sessionOpened.schemaVersion());
        data.writeInt(sessionOpened.streamTypeCode());
        data.writeShort(sessionOpened.streamVersion());
        data.writeBoolean(sessionOpened.offLoopback());
        data.writeInt((int) sessionOpened.socketHostCrc32());
        data.writeInt(sessionOpened.socketPort());
        data.writeShort(checkedManifestVersion(sessionOpened.capabilityManifest()));
        data.writeInt(sessionOpened.capabilityManifest().entryCount());
        data.writeInt(sessionOpened.capabilityManifest().streamCount());
        data.writeInt((int) sessionOpened.capabilityManifest().fingerprintCrc32());
        data.flush();
    }

    static ServerTransportSessionOpened readTransportSessionOpened(
            InputStream input,
            ClientHandshake handshake,
            String socketHost,
            int socketPort) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(handshake, "handshake");
        ServerTransportSessionOpened sessionOpened = readTransportSessionOpened(input);
        requireTransportSessionOpenedMatchesHandshake(sessionOpened, handshake, socketHost, socketPort);
        return sessionOpened;
    }

    static ServerTransportSessionOpened readTransportSessionOpened(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot transport session opened");
        }
        if (magic != TRANSPORT_SESSION_OPENED_MAGIC) {
            throw new IOException("headless snapshot transport session opened magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int sessionMessageTypeCode = data.readInt();
        int sessionSchemaVersion = data.readUnsignedShort();
        requireRegisteredTransportSessionOpenedIdentity(sessionMessageTypeCode, sessionSchemaVersion);
        int wireTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        int streamTypeCode = data.readInt();
        int streamVersion = data.readUnsignedShort();
        boolean offLoopback = data.readBoolean();
        long socketHostCrc32 = Integer.toUnsignedLong(data.readInt());
        int socketPort = data.readInt();
        int manifestVersion = data.readUnsignedShort();
        int registryEntryCount = data.readInt();
        int registryStreamCount = data.readInt();
        long capabilityFingerprintCrc32 = Integer.toUnsignedLong(data.readInt());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(wireTypeCode, schemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot transport session opened uses unsupported wire identity: "
                            + Integer.toUnsignedString(wireTypeCode)
                            + " v" + schemaVersion,
                    badIdentity);
        }
        SnapshotStreamIdentity streamIdentity;
        try {
            streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(streamTypeCode, streamVersion);
        } catch (IllegalArgumentException badStreamIdentity) {
            throw new IOException(
                    "headless snapshot transport session opened uses unsupported stream identity: "
                            + Integer.toUnsignedString(streamTypeCode)
                            + " v" + streamVersion,
                    badStreamIdentity);
        }
        NetworkCapabilityManifest capabilityManifest;
        try {
            capabilityManifest = new NetworkCapabilityManifest(
                    manifestVersion,
                    registryEntryCount,
                    registryStreamCount,
                    capabilityFingerprintCrc32);
        } catch (IllegalArgumentException badCapability) {
            throw new IOException("headless snapshot transport session opened has malformed capability manifest",
                    badCapability);
        }
        ServerTransportSessionOpened sessionOpened = new ServerTransportSessionOpened(
                sessionMessageTypeCode,
                sessionSchemaVersion,
                entry.messageId(),
                wireTypeCode,
                schemaVersion,
                streamIdentity.streamId(),
                streamTypeCode,
                streamVersion,
                offLoopback,
                socketHostCrc32,
                socketPort,
                capabilityManifest);
        requireTransportSessionOpenedIdentity(sessionOpened);
        return sessionOpened;
    }

    static void writeSessionWindowCursor(
            OutputStream output,
            ServerSessionWindowCursor cursor) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(cursor, "cursor");
        requireSessionWindowCursorIdentity(cursor);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(SESSION_WINDOW_CURSOR_MAGIC);
        data.writeInt(cursor.cursorMessageTypeCode());
        data.writeShort(cursor.cursorSchemaVersion());
        data.writeInt(cursor.wireTypeCode());
        data.writeShort(cursor.schemaVersion());
        data.writeInt(cursor.streamTypeCode());
        data.writeShort(cursor.streamVersion());
        data.writeLong(cursor.sessionId());
        data.writeInt(cursor.windowOrdinal());
        data.writeLong(cursor.previousResumeCursor());
        data.writeLong(cursor.nextResumeCursor());
        data.writeInt(cursor.expectedFrames());
        data.writeShort(checkedManifestVersion(cursor.capabilityManifest()));
        data.writeInt(cursor.capabilityManifest().entryCount());
        data.writeInt(cursor.capabilityManifest().streamCount());
        data.writeInt((int) cursor.capabilityManifest().fingerprintCrc32());
        data.flush();
    }

    static ServerSessionWindowCursor readSessionWindowCursor(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(handshake, "handshake");
        ServerSessionWindowCursor cursor = readSessionWindowCursor(input);
        requireSessionWindowCursorMatchesHandshake(cursor, handshake);
        return cursor;
    }

    static ServerSessionWindowCursor readSessionWindowCursor(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot session window cursor");
        }
        if (magic != SESSION_WINDOW_CURSOR_MAGIC) {
            throw new IOException("headless snapshot session window cursor magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int cursorMessageTypeCode = data.readInt();
        int cursorSchemaVersion = data.readUnsignedShort();
        requireRegisteredSessionWindowCursorIdentity(cursorMessageTypeCode, cursorSchemaVersion);
        int wireTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        int streamTypeCode = data.readInt();
        int streamVersion = data.readUnsignedShort();
        long sessionId = data.readLong();
        int windowOrdinal = data.readInt();
        long previousResumeCursor = data.readLong();
        long nextResumeCursor = data.readLong();
        int expectedFrames = data.readInt();
        int manifestVersion = data.readUnsignedShort();
        int registryEntryCount = data.readInt();
        int registryStreamCount = data.readInt();
        long capabilityFingerprintCrc32 = Integer.toUnsignedLong(data.readInt());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(wireTypeCode, schemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot session window cursor uses unsupported wire identity: "
                            + Integer.toUnsignedString(wireTypeCode)
                            + " v" + schemaVersion,
                    badIdentity);
        }
        SnapshotStreamIdentity streamIdentity;
        try {
            streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(streamTypeCode, streamVersion);
        } catch (IllegalArgumentException badStreamIdentity) {
            throw new IOException(
                    "headless snapshot session window cursor uses unsupported stream identity: "
                            + Integer.toUnsignedString(streamTypeCode)
                            + " v" + streamVersion,
                    badStreamIdentity);
        }
        NetworkCapabilityManifest capabilityManifest;
        try {
            capabilityManifest = new NetworkCapabilityManifest(
                    manifestVersion,
                    registryEntryCount,
                    registryStreamCount,
                    capabilityFingerprintCrc32);
        } catch (IllegalArgumentException badCapability) {
            throw new IOException("headless snapshot session window cursor has malformed capability manifest",
                    badCapability);
        }
        ServerSessionWindowCursor cursor = new ServerSessionWindowCursor(
                cursorMessageTypeCode,
                cursorSchemaVersion,
                entry.messageId(),
                wireTypeCode,
                schemaVersion,
                streamIdentity.streamId(),
                streamTypeCode,
                streamVersion,
                sessionId,
                windowOrdinal,
                previousResumeCursor,
                nextResumeCursor,
                expectedFrames,
                capabilityManifest);
        requireSessionWindowCursorIdentity(cursor);
        return cursor;
    }

    static void writeInitialSync(
            OutputStream output,
            ServerInitialSync initialSync) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(initialSync, "initialSync");
        requireInitialSyncIdentity(initialSync);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(INITIAL_SYNC_MAGIC);
        data.writeInt(initialSync.initialSyncMessageTypeCode());
        data.writeShort(initialSync.initialSyncSchemaVersion());
        data.writeInt(initialSync.wireTypeCode());
        data.writeShort(initialSync.schemaVersion());
        data.writeInt(initialSync.streamTypeCode());
        data.writeShort(initialSync.streamVersion());
        data.writeInt(Math.toIntExact(initialSync.snapshotSequence()));
        data.writeLong(initialSync.startedTick());
        data.writeLong(initialSync.completedTick());
        data.writeLong(initialSync.stateHash());
        data.writeInt(initialSync.entityCount());
        data.writeInt(initialSync.shipCount());
        data.writeInt(initialSync.shipNetworkIdCount());
        data.writeLong(initialSync.firstShipNetworkId());
        data.writeLong(initialSync.lastShipNetworkId());
        data.writeBoolean(initialSync.stableShipNetworkIdentityRange());
        data.writeShort(checkedManifestVersion(initialSync.capabilityManifest()));
        data.writeInt(initialSync.capabilityManifest().entryCount());
        data.writeInt(initialSync.capabilityManifest().streamCount());
        data.writeInt((int) initialSync.capabilityManifest().fingerprintCrc32());
        data.flush();
    }

    static ServerInitialSync readInitialSync(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(handshake, "handshake");
        ServerInitialSync initialSync = readInitialSync(input);
        requireInitialSyncMatchesHandshake(initialSync, handshake);
        return initialSync;
    }

    static ServerInitialSync readInitialSync(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot initial sync");
        }
        if (magic != INITIAL_SYNC_MAGIC) {
            throw new IOException("headless snapshot initial sync magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int initialSyncMessageTypeCode = data.readInt();
        int initialSyncSchemaVersion = data.readUnsignedShort();
        requireRegisteredInitialSyncIdentity(initialSyncMessageTypeCode, initialSyncSchemaVersion);
        int wireTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        int streamTypeCode = data.readInt();
        int streamVersion = data.readUnsignedShort();
        long snapshotSequence = Integer.toUnsignedLong(data.readInt());
        long startedTick = data.readLong();
        long completedTick = data.readLong();
        long stateHash = data.readLong();
        int entityCount = data.readInt();
        int shipCount = data.readInt();
        int shipNetworkIdCount = data.readInt();
        long firstShipNetworkId = data.readLong();
        long lastShipNetworkId = data.readLong();
        boolean stableShipNetworkIdentityRange = data.readBoolean();
        int manifestVersion = data.readUnsignedShort();
        int registryEntryCount = data.readInt();
        int registryStreamCount = data.readInt();
        long capabilityFingerprintCrc32 = Integer.toUnsignedLong(data.readInt());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(wireTypeCode, schemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot initial sync uses unsupported wire identity: "
                            + Integer.toUnsignedString(wireTypeCode)
                            + " v" + schemaVersion,
                    badIdentity);
        }
        SnapshotStreamIdentity streamIdentity;
        try {
            streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(streamTypeCode, streamVersion);
        } catch (IllegalArgumentException badStreamIdentity) {
            throw new IOException(
                    "headless snapshot initial sync uses unsupported stream identity: "
                            + Integer.toUnsignedString(streamTypeCode)
                            + " v" + streamVersion,
                    badStreamIdentity);
        }
        NetworkCapabilityManifest capabilityManifest;
        try {
            capabilityManifest = new NetworkCapabilityManifest(
                    manifestVersion,
                    registryEntryCount,
                    registryStreamCount,
                    capabilityFingerprintCrc32);
        } catch (IllegalArgumentException badCapability) {
            throw new IOException("headless snapshot initial sync has malformed capability manifest",
                    badCapability);
        }
        ServerInitialSync initialSync = new ServerInitialSync(
                initialSyncMessageTypeCode,
                initialSyncSchemaVersion,
                entry.messageId(),
                wireTypeCode,
                schemaVersion,
                streamIdentity.streamId(),
                streamTypeCode,
                streamVersion,
                snapshotSequence,
                startedTick,
                completedTick,
                stateHash,
                entityCount,
                shipCount,
                shipNetworkIdCount,
                firstShipNetworkId,
                lastShipNetworkId,
                stableShipNetworkIdentityRange,
                capabilityManifest);
        requireInitialSyncIdentity(initialSync);
        return initialSync;
    }

    static void writeStreamCompletion(
            OutputStream output,
            ServerStreamCompletion streamCompletion) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(streamCompletion, "streamCompletion");
        requireStreamCompletionIdentity(streamCompletion);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(STREAM_COMPLETED_MAGIC);
        data.writeInt(streamCompletion.completionMessageTypeCode());
        data.writeShort(streamCompletion.completionSchemaVersion());
        data.writeInt(streamCompletion.wireTypeCode());
        data.writeShort(streamCompletion.schemaVersion());
        data.writeInt(streamCompletion.streamTypeCode());
        data.writeShort(streamCompletion.streamVersion());
        data.writeInt(streamCompletion.completedFrames());
        data.writeShort(checkedManifestVersion(streamCompletion.capabilityManifest()));
        data.writeInt(streamCompletion.capabilityManifest().entryCount());
        data.writeInt(streamCompletion.capabilityManifest().streamCount());
        data.writeInt((int) streamCompletion.capabilityManifest().fingerprintCrc32());
        data.flush();
    }

    static ServerStreamCompletion readStreamCompletion(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(handshake, "handshake");
        ServerStreamCompletion streamCompletion = readStreamCompletion(input);
        requireStreamCompletionMatchesHandshake(streamCompletion, handshake);
        return streamCompletion;
    }

    static ServerStreamCompletion readStreamCompletion(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated headless snapshot stream completion");
        }
        if (magic != STREAM_COMPLETED_MAGIC) {
            throw new IOException("headless snapshot stream completion magic mismatch: "
                    + Integer.toHexString(magic));
        }
        int completionMessageTypeCode = data.readInt();
        int completionSchemaVersion = data.readUnsignedShort();
        requireRegisteredStreamCompletionIdentity(completionMessageTypeCode, completionSchemaVersion);
        int wireTypeCode = data.readInt();
        int schemaVersion = data.readUnsignedShort();
        int streamTypeCode = data.readInt();
        int streamVersion = data.readUnsignedShort();
        int completedFrames = data.readInt();
        int manifestVersion = data.readUnsignedShort();
        int registryEntryCount = data.readInt();
        int registryStreamCount = data.readInt();
        long capabilityFingerprintCrc32 = Integer.toUnsignedLong(data.readInt());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(wireTypeCode, schemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot stream completion acknowledged unsupported wire identity: "
                            + Integer.toUnsignedString(wireTypeCode)
                            + " v" + schemaVersion,
                    badIdentity);
        }
        SnapshotStreamIdentity streamIdentity;
        try {
            streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(streamTypeCode, streamVersion);
        } catch (IllegalArgumentException badStreamIdentity) {
            throw new IOException(
                    "headless snapshot stream completion acknowledged unsupported stream identity: "
                            + Integer.toUnsignedString(streamTypeCode)
                            + " v" + streamVersion,
                    badStreamIdentity);
        }
        NetworkCapabilityManifest capabilityManifest;
        try {
            capabilityManifest = new NetworkCapabilityManifest(
                    manifestVersion,
                    registryEntryCount,
                    registryStreamCount,
                    capabilityFingerprintCrc32);
        } catch (IllegalArgumentException badCapability) {
            throw new IOException("headless snapshot stream completion has malformed capability manifest",
                    badCapability);
        }
        ServerStreamCompletion streamCompletion = new ServerStreamCompletion(
                completionMessageTypeCode,
                completionSchemaVersion,
                entry.messageId(),
                wireTypeCode,
                schemaVersion,
                streamIdentity.streamId(),
                streamTypeCode,
                streamVersion,
                completedFrames,
                capabilityManifest);
        requireStreamCompletionIdentity(streamCompletion);
        return streamCompletion;
    }

    static void requireSnapshotFrame(
            OfflineCombatStepWireFrame frame,
            ClientHandshake handshake) {
        if (!OfflineCombatStepSnapshotMessage.MESSAGE_ID.equals(frame.registryEntry().messageId())) {
            throw new IllegalStateException("headless snapshot client decoded unexpected message id: "
                    + frame.registryEntry().messageId());
        }
        if (frame.messageTypeCode() != handshake.wireTypeCode()
                || frame.registryEntry().schemaVersion() != handshake.schemaVersion()) {
            throw new IllegalStateException("headless snapshot client decoded frame outside advertised identity");
        }
    }

    private static void requireSnapshotHandshake(ClientHandshake handshake) throws IOException {
        requireAdmissionRequestHandshake(handshake);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    handshake.wireTypeCode(),
                    handshake.schemaVersion());
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client handshake uses unregistered wire identity: "
                            + Integer.toUnsignedString(handshake.wireTypeCode())
                            + " v" + handshake.schemaVersion(),
                    badIdentity);
        }
        if (!OfflineCombatStepSnapshotMessage.MESSAGE_ID.equals(entry.messageId())
                || !entry.messageId().equals(handshake.messageId())) {
            throw new IOException("headless snapshot client handshake is not for "
                    + OfflineCombatStepSnapshotMessage.MESSAGE_ID);
        }
        SnapshotStreamIdentity streamIdentity = requireRegisteredSnapshotStreamIdentity(
                handshake.streamTypeCode(),
                handshake.streamVersion());
        if (!streamIdentity.streamId().equals(handshake.streamId())) {
            throw new IOException("headless snapshot client handshake stream id mismatch: "
                    + handshake.streamId());
        }
    }

    private static void requireAdmissionRequestHandshake(ClientHandshake handshake) throws IOException {
        ProtocolRegistryEntry requestEntry =
                requireRegisteredAdmissionRequestIdentity(
                        handshake.admissionRequestMessageTypeCode(),
                        handshake.admissionRequestSchemaVersion());
        if (!requestEntry.messageId().equals(handshake.admissionRequestMessageId())) {
            throw new IOException("headless snapshot client handshake admission request message id mismatch: "
                    + handshake.admissionRequestMessageId());
        }
    }

    private static void requireAuthoritativeCapability(ClientHandshake handshake) throws IOException {
        NetworkCapabilityManifest required = NetworkMessageRegistry.authoritativeCombat().capabilityManifest();
        if (!required.equals(handshake.capabilityManifest())) {
            throw new IOException("headless snapshot client handshake capability manifest mismatch");
        }
    }

    private static void requireAcceptedAdmissionAck(ServerAdmissionAck admissionAck) throws IOException {
        if (!admissionAck.accepted()) {
            throw new IOException("headless snapshot server admission acknowledgement is not accepted");
        }
        requireAdmissionAckIdentity(admissionAck);
    }

    private static void requireAdmissionAckIdentity(ServerAdmissionAck admissionAck) throws IOException {
        requireRegisteredAdmissionAckIdentity(
                admissionAck.ackMessageTypeCode(),
                admissionAck.ackSchemaVersion());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    admissionAck.wireTypeCode(),
                    admissionAck.schemaVersion());
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot server admission acknowledgement uses unregistered wire identity: "
                            + Integer.toUnsignedString(admissionAck.wireTypeCode())
                            + " v" + admissionAck.schemaVersion(),
                    badIdentity);
        }
        if (!OfflineCombatStepSnapshotMessage.MESSAGE_ID.equals(entry.messageId())
                || !entry.messageId().equals(admissionAck.messageId())) {
            throw new IOException("headless snapshot server admission acknowledgement is not for "
                    + OfflineCombatStepSnapshotMessage.MESSAGE_ID);
        }
        SnapshotStreamIdentity streamIdentity = requireRegisteredSnapshotStreamIdentity(
                admissionAck.streamTypeCode(),
                admissionAck.streamVersion());
        if (!streamIdentity.streamId().equals(admissionAck.streamId())) {
            throw new IOException("headless snapshot server admission acknowledgement stream id mismatch: "
                    + admissionAck.streamId());
        }
        if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                .equals(admissionAck.capabilityManifest())) {
            throw new IOException("headless snapshot server admission acknowledgement capability mismatch");
        }
    }

    private static void requireAdmissionAckMatchesHandshake(
            ServerAdmissionAck admissionAck,
            ClientHandshake handshake) throws IOException {
        requireAcceptedAdmissionAck(admissionAck);
        if (!admissionAck.messageId().equals(handshake.messageId())
                || admissionAck.wireTypeCode() != handshake.wireTypeCode()
                || admissionAck.schemaVersion() != handshake.schemaVersion()
                || !admissionAck.streamId().equals(handshake.streamId())
                || admissionAck.streamTypeCode() != handshake.streamTypeCode()
                || admissionAck.streamVersion() != handshake.streamVersion()
                || admissionAck.expectedFrames() != handshake.expectedFrames()
                || !admissionAck.capabilityManifest().equals(handshake.capabilityManifest())) {
            throw new IOException("headless snapshot server admission acknowledgement does not match handshake");
        }
    }

    private static void requireMovementCommandIdentity(ClientMovementCommand movementCommand)
            throws IOException {
        ProtocolRegistryEntry movementEntry =
                requireRegisteredMovementCommandIdentity(
                        movementCommand.messageTypeCode(),
                        movementCommand.schemaVersion());
        if (!movementEntry.messageId().equals(movementCommand.messageId())) {
            throw new IOException("headless snapshot client movement command message id mismatch: "
                    + movementCommand.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredMovementCommandIdentity(
            int movementCommandMessageTypeCode,
            int movementCommandSchemaVersion) throws IOException {
        ProtocolRegistryEntry movementEntry;
        try {
            movementEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    movementCommandMessageTypeCode,
                    movementCommandSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client movement command uses unregistered identity: "
                            + Integer.toUnsignedString(movementCommandMessageTypeCode)
                            + " v" + movementCommandSchemaVersion,
                    badIdentity);
        }
        if (!ClientShipMovementCommandMessage.MESSAGE_ID.equals(movementEntry.messageId())) {
            throw new IOException("headless snapshot client movement command message id mismatch: "
                    + movementEntry.messageId());
        }
        return movementEntry;
    }

    private static void requireFireCommandIdentity(ClientFireCommand fireCommand)
            throws IOException {
        ProtocolRegistryEntry fireEntry =
                requireRegisteredFireCommandIdentity(
                        fireCommand.messageTypeCode(),
                        fireCommand.schemaVersion());
        if (!fireEntry.messageId().equals(fireCommand.messageId())) {
            throw new IOException("headless snapshot client fire command message id mismatch: "
                    + fireCommand.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredFireCommandIdentity(
            int fireCommandMessageTypeCode,
            int fireCommandSchemaVersion) throws IOException {
        ProtocolRegistryEntry fireEntry;
        try {
            fireEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    fireCommandMessageTypeCode,
                    fireCommandSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client fire command uses unregistered identity: "
                            + Integer.toUnsignedString(fireCommandMessageTypeCode)
                            + " v" + fireCommandSchemaVersion,
                    badIdentity);
        }
        if (!ClientShipFireCommandMessage.MESSAGE_ID.equals(fireEntry.messageId())) {
            throw new IOException("headless snapshot client fire command message id mismatch: "
                    + fireEntry.messageId());
        }
        return fireEntry;
    }

    private static void requireSystemCommandIdentity(ClientSystemCommand systemCommand)
            throws IOException {
        ProtocolRegistryEntry systemEntry =
                requireRegisteredSystemCommandIdentity(
                        systemCommand.messageTypeCode(),
                        systemCommand.schemaVersion());
        if (!systemEntry.messageId().equals(systemCommand.messageId())) {
            throw new IOException("headless snapshot client system command message id mismatch: "
                    + systemCommand.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredSystemCommandIdentity(
            int systemCommandMessageTypeCode,
            int systemCommandSchemaVersion) throws IOException {
        ProtocolRegistryEntry systemEntry;
        try {
            systemEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    systemCommandMessageTypeCode,
                    systemCommandSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client system command uses unregistered identity: "
                            + Integer.toUnsignedString(systemCommandMessageTypeCode)
                            + " v" + systemCommandSchemaVersion,
                    badIdentity);
        }
        if (!ClientShipSystemCommandMessage.MESSAGE_ID.equals(systemEntry.messageId())) {
            throw new IOException("headless snapshot client system command message id mismatch: "
                    + systemEntry.messageId());
        }
        return systemEntry;
    }

    private static void requireWeaponGroupCommandIdentity(ClientWeaponGroupCommand weaponGroupCommand)
            throws IOException {
        ProtocolRegistryEntry weaponGroupEntry =
                requireRegisteredWeaponGroupCommandIdentity(
                        weaponGroupCommand.messageTypeCode(),
                        weaponGroupCommand.schemaVersion());
        if (!weaponGroupEntry.messageId().equals(weaponGroupCommand.messageId())) {
            throw new IOException("headless snapshot client weapon-group command message id mismatch: "
                    + weaponGroupCommand.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredWeaponGroupCommandIdentity(
            int weaponGroupCommandMessageTypeCode,
            int weaponGroupCommandSchemaVersion) throws IOException {
        ProtocolRegistryEntry weaponGroupEntry;
        try {
            weaponGroupEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    weaponGroupCommandMessageTypeCode,
                    weaponGroupCommandSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client weapon-group command uses unregistered identity: "
                            + Integer.toUnsignedString(weaponGroupCommandMessageTypeCode)
                            + " v" + weaponGroupCommandSchemaVersion,
                    badIdentity);
        }
        if (!ClientShipWeaponGroupCommandMessage.MESSAGE_ID.equals(weaponGroupEntry.messageId())) {
            throw new IOException("headless snapshot client weapon-group command message id mismatch: "
                    + weaponGroupEntry.messageId());
        }
        return weaponGroupEntry;
    }

    private static void requireSessionResumeClaimIdentity(ClientSessionResumeClaim claim)
            throws IOException {
        ProtocolRegistryEntry claimEntry =
                requireRegisteredSessionResumeClaimIdentity(
                        claim.claimMessageTypeCode(),
                        claim.claimSchemaVersion());
        if (!claimEntry.messageId().equals(claim.claimMessageId())) {
            throw new IOException("headless snapshot client session resume claim message id mismatch: "
                    + claim.claimMessageId());
        }
        ProtocolRegistryEntry entry = requireRegisteredSnapshotIdentity(
                claim.wireTypeCode(),
                claim.schemaVersion());
        if (!entry.messageId().equals(claim.messageId())) {
            throw new IOException("headless snapshot client session resume claim uses unregistered snapshot identity: "
                    + Integer.toUnsignedString(claim.wireTypeCode())
                    + " v" + claim.schemaVersion());
        }
        SnapshotStreamIdentity streamIdentity = requireRegisteredSnapshotStreamIdentity(
                claim.streamTypeCode(),
                claim.streamVersion());
        if (!streamIdentity.streamId().equals(claim.streamId())) {
            throw new IOException("headless snapshot client session resume claim stream id mismatch: "
                    + claim.streamId());
        }
        if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                .equals(claim.capabilityManifest())) {
            throw new IOException("headless snapshot client session resume claim capability mismatch");
        }
    }

    private static void requireSessionResumeClaimMatchesHandshake(
            ClientSessionResumeClaim claim,
            ClientHandshake handshake) throws IOException {
        requireSessionResumeClaimIdentity(claim);
        if (!claim.messageId().equals(handshake.messageId())
                || claim.wireTypeCode() != handshake.wireTypeCode()
                || claim.schemaVersion() != handshake.schemaVersion()
                || !claim.streamId().equals(handshake.streamId())
                || claim.streamTypeCode() != handshake.streamTypeCode()
                || claim.streamVersion() != handshake.streamVersion()
                || claim.expectedFrames() != handshake.expectedFrames()
                || !claim.capabilityManifest().equals(handshake.capabilityManifest())) {
            throw new IOException("headless snapshot client session resume claim does not match handshake");
        }
    }

    private static ProtocolRegistryEntry requireRegisteredSessionResumeClaimIdentity(
            int claimMessageTypeCode,
            int claimSchemaVersion) throws IOException {
        ProtocolRegistryEntry claimEntry;
        try {
            claimEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    claimMessageTypeCode,
                    claimSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client session resume claim uses unregistered identity: "
                            + Integer.toUnsignedString(claimMessageTypeCode)
                            + " v" + claimSchemaVersion,
                    badIdentity);
        }
        if (!ClientSessionResumeClaimMessage.MESSAGE_ID.equals(claimEntry.messageId())) {
            throw new IOException("headless snapshot client session resume claim message id mismatch: "
                    + claimEntry.messageId());
        }
        return claimEntry;
    }

    private static void requireMovementCommandResultIdentity(ServerMovementCommandResult movementCommandResult)
            throws IOException {
        ProtocolRegistryEntry resultEntry =
                requireRegisteredMovementCommandResultIdentity(
                        movementCommandResult.messageTypeCode(),
                        movementCommandResult.schemaVersion());
        if (!resultEntry.messageId().equals(movementCommandResult.messageId())) {
            throw new IOException("headless snapshot server movement command result message id mismatch: "
                    + movementCommandResult.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredMovementCommandResultIdentity(
            int movementCommandResultMessageTypeCode,
            int movementCommandResultSchemaVersion) throws IOException {
        ProtocolRegistryEntry resultEntry;
        try {
            resultEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    movementCommandResultMessageTypeCode,
                    movementCommandResultSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot server movement command result uses unregistered identity: "
                            + Integer.toUnsignedString(movementCommandResultMessageTypeCode)
                            + " v" + movementCommandResultSchemaVersion,
                    badIdentity);
        }
        if (!ServerShipMovementCommandResultMessage.MESSAGE_ID.equals(resultEntry.messageId())) {
            throw new IOException("headless snapshot server movement command result message id mismatch: "
                    + resultEntry.messageId());
        }
        return resultEntry;
    }

    private static void requireFireCommandResultIdentity(ServerFireCommandResult fireCommandResult)
            throws IOException {
        ProtocolRegistryEntry resultEntry =
                requireRegisteredFireCommandResultIdentity(
                        fireCommandResult.messageTypeCode(),
                        fireCommandResult.schemaVersion());
        if (!resultEntry.messageId().equals(fireCommandResult.messageId())) {
            throw new IOException("headless snapshot server fire command result message id mismatch: "
                    + fireCommandResult.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredFireCommandResultIdentity(
            int fireCommandResultMessageTypeCode,
            int fireCommandResultSchemaVersion) throws IOException {
        ProtocolRegistryEntry resultEntry;
        try {
            resultEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    fireCommandResultMessageTypeCode,
                    fireCommandResultSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot server fire command result uses unregistered identity: "
                            + Integer.toUnsignedString(fireCommandResultMessageTypeCode)
                            + " v" + fireCommandResultSchemaVersion,
                    badIdentity);
        }
        if (!ServerShipFireCommandResultMessage.MESSAGE_ID.equals(resultEntry.messageId())) {
            throw new IOException("headless snapshot server fire command result message id mismatch: "
                    + resultEntry.messageId());
        }
        return resultEntry;
    }

    private static void requireSystemCommandResultIdentity(ServerSystemCommandResult systemCommandResult)
            throws IOException {
        ProtocolRegistryEntry resultEntry =
                requireRegisteredSystemCommandResultIdentity(
                        systemCommandResult.messageTypeCode(),
                        systemCommandResult.schemaVersion());
        if (!resultEntry.messageId().equals(systemCommandResult.messageId())) {
            throw new IOException("headless snapshot server system command result message id mismatch: "
                    + systemCommandResult.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredSystemCommandResultIdentity(
            int systemCommandResultMessageTypeCode,
            int systemCommandResultSchemaVersion) throws IOException {
        ProtocolRegistryEntry resultEntry;
        try {
            resultEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    systemCommandResultMessageTypeCode,
                    systemCommandResultSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot server system command result uses unregistered identity: "
                            + Integer.toUnsignedString(systemCommandResultMessageTypeCode)
                            + " v" + systemCommandResultSchemaVersion,
                    badIdentity);
        }
        if (!ServerShipSystemCommandResultMessage.MESSAGE_ID.equals(resultEntry.messageId())) {
            throw new IOException("headless snapshot server system command result message id mismatch: "
                    + resultEntry.messageId());
        }
        return resultEntry;
    }

    private static void requireWeaponGroupCommandResultIdentity(
            ServerWeaponGroupCommandResult weaponGroupCommandResult) throws IOException {
        ProtocolRegistryEntry resultEntry =
                requireRegisteredWeaponGroupCommandResultIdentity(
                        weaponGroupCommandResult.messageTypeCode(),
                        weaponGroupCommandResult.schemaVersion());
        if (!resultEntry.messageId().equals(weaponGroupCommandResult.messageId())) {
            throw new IOException("headless snapshot server weapon-group command result message id mismatch: "
                    + weaponGroupCommandResult.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredWeaponGroupCommandResultIdentity(
            int weaponGroupCommandResultMessageTypeCode,
            int weaponGroupCommandResultSchemaVersion) throws IOException {
        ProtocolRegistryEntry resultEntry;
        try {
            resultEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    weaponGroupCommandResultMessageTypeCode,
                    weaponGroupCommandResultSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot server weapon-group command result uses unregistered identity: "
                            + Integer.toUnsignedString(weaponGroupCommandResultMessageTypeCode)
                            + " v" + weaponGroupCommandResultSchemaVersion,
                    badIdentity);
        }
        if (!ServerShipWeaponGroupCommandResultMessage.MESSAGE_ID.equals(resultEntry.messageId())) {
            throw new IOException("headless snapshot server weapon-group command result message id mismatch: "
                    + resultEntry.messageId());
        }
        return resultEntry;
    }

    private static void requireTransportSessionOpenedIdentity(ServerTransportSessionOpened sessionOpened)
            throws IOException {
        requireRegisteredTransportSessionOpenedIdentity(
                sessionOpened.sessionMessageTypeCode(),
                sessionOpened.sessionSchemaVersion());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    sessionOpened.wireTypeCode(),
                    sessionOpened.schemaVersion());
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot transport session opened uses unregistered snapshot wire identity: "
                            + Integer.toUnsignedString(sessionOpened.wireTypeCode())
                            + " v" + sessionOpened.schemaVersion(),
                    badIdentity);
        }
        if (!OfflineCombatStepSnapshotMessage.MESSAGE_ID.equals(entry.messageId())
                || !entry.messageId().equals(sessionOpened.messageId())) {
            throw new IOException("headless snapshot transport session opened is not for "
                    + OfflineCombatStepSnapshotMessage.MESSAGE_ID);
        }
        SnapshotStreamIdentity streamIdentity = requireRegisteredSnapshotStreamIdentity(
                sessionOpened.streamTypeCode(),
                sessionOpened.streamVersion());
        if (!streamIdentity.streamId().equals(sessionOpened.streamId())) {
            throw new IOException("headless snapshot transport session opened stream id mismatch: "
                    + sessionOpened.streamId());
        }
        if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                .equals(sessionOpened.capabilityManifest())) {
            throw new IOException("headless snapshot transport session opened capability mismatch");
        }
    }

    private static void requireTransportSessionOpenedMatchesHandshake(
            ServerTransportSessionOpened sessionOpened,
            ClientHandshake handshake,
            String socketHost,
            int socketPort) throws IOException {
        requireTransportSessionOpenedIdentity(sessionOpened);
        if (!sessionOpened.messageId().equals(handshake.messageId())
                || sessionOpened.wireTypeCode() != handshake.wireTypeCode()
                || sessionOpened.schemaVersion() != handshake.schemaVersion()
                || !sessionOpened.streamId().equals(handshake.streamId())
                || sessionOpened.streamTypeCode() != handshake.streamTypeCode()
                || sessionOpened.streamVersion() != handshake.streamVersion()
                || !sessionOpened.capabilityManifest().equals(handshake.capabilityManifest())) {
            throw new IOException("headless snapshot transport session opened does not match handshake");
        }
        if (sessionOpened.socketHostCrc32() != socketHostCrc32(socketHost)
                || sessionOpened.socketPort() != socketPort
                || sessionOpened.offLoopback() != isOffLoopbackHost(socketHost)) {
            throw new IOException("headless snapshot transport session opened does not match socket endpoint");
        }
    }

    private static void requireRegisteredTransportSessionOpenedIdentity(
            int sessionMessageTypeCode,
            int sessionSchemaVersion) throws IOException {
        ProtocolRegistryEntry sessionEntry;
        try {
            sessionEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    sessionMessageTypeCode,
                    sessionSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot transport session opened uses unregistered identity: "
                            + Integer.toUnsignedString(sessionMessageTypeCode)
                            + " v" + sessionSchemaVersion,
                    badIdentity);
        }
        if (!ServerTransportSessionOpenedMessage.MESSAGE_ID.equals(sessionEntry.messageId())) {
            throw new IOException("headless snapshot transport session opened message id mismatch: "
                    + sessionEntry.messageId());
        }
    }

    private static void requireSessionWindowCursorIdentity(ServerSessionWindowCursor cursor)
            throws IOException {
        requireRegisteredSessionWindowCursorIdentity(
                cursor.cursorMessageTypeCode(),
                cursor.cursorSchemaVersion());
        ProtocolRegistryEntry entry = requireRegisteredSnapshotIdentity(
                cursor.wireTypeCode(),
                cursor.schemaVersion());
        if (!entry.messageId().equals(cursor.messageId())) {
            throw new IOException(
                    "headless snapshot session window cursor uses unregistered snapshot wire identity: "
                            + Integer.toUnsignedString(cursor.wireTypeCode())
                            + " v" + cursor.schemaVersion());
        }
        SnapshotStreamIdentity streamIdentity = requireRegisteredSnapshotStreamIdentity(
                cursor.streamTypeCode(),
                cursor.streamVersion());
        if (!streamIdentity.streamId().equals(cursor.streamId())) {
            throw new IOException("headless snapshot session window cursor stream id mismatch: "
                    + cursor.streamId());
        }
        if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                .equals(cursor.capabilityManifest())) {
            throw new IOException("headless snapshot session window cursor capability mismatch");
        }
    }

    private static void requireSessionWindowCursorMatchesHandshake(
            ServerSessionWindowCursor cursor,
            ClientHandshake handshake) throws IOException {
        requireSessionWindowCursorIdentity(cursor);
        if (!cursor.messageId().equals(handshake.messageId())
                || cursor.wireTypeCode() != handshake.wireTypeCode()
                || cursor.schemaVersion() != handshake.schemaVersion()
                || !cursor.streamId().equals(handshake.streamId())
                || cursor.streamTypeCode() != handshake.streamTypeCode()
                || cursor.streamVersion() != handshake.streamVersion()
                || cursor.expectedFrames() != handshake.expectedFrames()
                || !cursor.capabilityManifest().equals(handshake.capabilityManifest())) {
            throw new IOException("headless snapshot session window cursor does not match handshake");
        }
    }

    private static void requireRegisteredSessionWindowCursorIdentity(
            int cursorMessageTypeCode,
            int cursorSchemaVersion) throws IOException {
        ProtocolRegistryEntry cursorEntry;
        try {
            cursorEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    cursorMessageTypeCode,
                    cursorSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot session window cursor uses unregistered identity: "
                            + Integer.toUnsignedString(cursorMessageTypeCode)
                            + " v" + cursorSchemaVersion,
                    badIdentity);
        }
        if (!ServerSessionWindowCursorMessage.MESSAGE_ID.equals(cursorEntry.messageId())) {
            throw new IOException("headless snapshot session window cursor message id mismatch: "
                    + cursorEntry.messageId());
        }
    }

    private static void requireInitialSyncIdentity(ServerInitialSync initialSync)
            throws IOException {
        requireRegisteredInitialSyncIdentity(
                initialSync.initialSyncMessageTypeCode(),
                initialSync.initialSyncSchemaVersion());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    initialSync.wireTypeCode(),
                    initialSync.schemaVersion());
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot initial sync uses unregistered snapshot wire identity: "
                            + Integer.toUnsignedString(initialSync.wireTypeCode())
                            + " v" + initialSync.schemaVersion(),
                    badIdentity);
        }
        if (!OfflineCombatStepSnapshotMessage.MESSAGE_ID.equals(entry.messageId())
                || !entry.messageId().equals(initialSync.messageId())) {
            throw new IOException("headless snapshot initial sync is not for "
                    + OfflineCombatStepSnapshotMessage.MESSAGE_ID);
        }
        SnapshotStreamIdentity streamIdentity = requireRegisteredSnapshotStreamIdentity(
                initialSync.streamTypeCode(),
                initialSync.streamVersion());
        if (!streamIdentity.streamId().equals(initialSync.streamId())) {
            throw new IOException("headless snapshot initial sync stream id mismatch: "
                    + initialSync.streamId());
        }
        if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                .equals(initialSync.capabilityManifest())) {
            throw new IOException("headless snapshot initial sync capability mismatch");
        }
    }

    private static void requireInitialSyncMatchesHandshake(
            ServerInitialSync initialSync,
            ClientHandshake handshake) throws IOException {
        requireInitialSyncIdentity(initialSync);
        if (!initialSync.messageId().equals(handshake.messageId())
                || initialSync.wireTypeCode() != handshake.wireTypeCode()
                || initialSync.schemaVersion() != handshake.schemaVersion()
                || !initialSync.streamId().equals(handshake.streamId())
                || initialSync.streamTypeCode() != handshake.streamTypeCode()
                || initialSync.streamVersion() != handshake.streamVersion()
                || !initialSync.capabilityManifest().equals(handshake.capabilityManifest())) {
            throw new IOException("headless snapshot initial sync does not match handshake");
        }
    }

    static void requireInitialSyncMatchesFirstFrame(
            ServerInitialSync initialSync,
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
            throw new IllegalArgumentException("initial sync must match the first decoded snapshot frame");
        }
    }

    private static void requireRegisteredInitialSyncIdentity(
            int initialSyncMessageTypeCode,
            int initialSyncSchemaVersion) throws IOException {
        ProtocolRegistryEntry initialSyncEntry;
        try {
            initialSyncEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    initialSyncMessageTypeCode,
                    initialSyncSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot initial sync uses unregistered identity: "
                            + Integer.toUnsignedString(initialSyncMessageTypeCode)
                            + " v" + initialSyncSchemaVersion,
                    badIdentity);
        }
        if (!ServerInitialSyncMessage.MESSAGE_ID.equals(initialSyncEntry.messageId())) {
            throw new IOException("headless snapshot initial sync message id mismatch: "
                    + initialSyncEntry.messageId());
        }
    }

    private static void requireRegisteredAdmissionAckIdentity(
            int ackMessageTypeCode,
            int ackSchemaVersion) throws IOException {
        ProtocolRegistryEntry ackEntry;
        try {
            ackEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    ackMessageTypeCode,
                    ackSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot server admission acknowledgement uses unregistered ack identity: "
                            + Integer.toUnsignedString(ackMessageTypeCode)
                            + " v" + ackSchemaVersion,
                    badIdentity);
        }
        if (!SnapshotAdmissionAckMessage.MESSAGE_ID.equals(ackEntry.messageId())) {
            throw new IOException("headless snapshot server admission acknowledgement message id mismatch: "
                    + ackEntry.messageId());
        }
    }

    private static ProtocolRegistryEntry requireRegisteredAdmissionRequestIdentity(
            int admissionRequestMessageTypeCode,
            int admissionRequestSchemaVersion) throws IOException {
        ProtocolRegistryEntry requestEntry;
        try {
            requestEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    admissionRequestMessageTypeCode,
                    admissionRequestSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot client handshake uses unregistered admission request identity: "
                            + Integer.toUnsignedString(admissionRequestMessageTypeCode)
                            + " v" + admissionRequestSchemaVersion,
                    badIdentity);
        }
        if (!SnapshotAdmissionRequestMessage.MESSAGE_ID.equals(requestEntry.messageId())) {
            throw new IOException("headless snapshot client handshake admission request message id mismatch: "
                    + requestEntry.messageId());
        }
        return requestEntry;
    }

    private static void requireStreamCompletionIdentity(ServerStreamCompletion streamCompletion)
            throws IOException {
        requireRegisteredStreamCompletionIdentity(
                streamCompletion.completionMessageTypeCode(),
                streamCompletion.completionSchemaVersion());
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    streamCompletion.wireTypeCode(),
                    streamCompletion.schemaVersion());
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot stream completion uses unregistered wire identity: "
                            + Integer.toUnsignedString(streamCompletion.wireTypeCode())
                            + " v" + streamCompletion.schemaVersion(),
                    badIdentity);
        }
        if (!OfflineCombatStepSnapshotMessage.MESSAGE_ID.equals(entry.messageId())
                || !entry.messageId().equals(streamCompletion.messageId())) {
            throw new IOException("headless snapshot stream completion is not for "
                    + OfflineCombatStepSnapshotMessage.MESSAGE_ID);
        }
        SnapshotStreamIdentity streamIdentity = requireRegisteredSnapshotStreamIdentity(
                streamCompletion.streamTypeCode(),
                streamCompletion.streamVersion());
        if (!streamIdentity.streamId().equals(streamCompletion.streamId())) {
            throw new IOException("headless snapshot stream completion stream id mismatch: "
                    + streamCompletion.streamId());
        }
        if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                .equals(streamCompletion.capabilityManifest())) {
            throw new IOException("headless snapshot stream completion capability mismatch");
        }
    }

    private static void requireStreamCompletionMatchesHandshake(
            ServerStreamCompletion streamCompletion,
            ClientHandshake handshake) throws IOException {
        requireStreamCompletionIdentity(streamCompletion);
        if (!streamCompletion.messageId().equals(handshake.messageId())
                || streamCompletion.wireTypeCode() != handshake.wireTypeCode()
                || streamCompletion.schemaVersion() != handshake.schemaVersion()
                || !streamCompletion.streamId().equals(handshake.streamId())
                || streamCompletion.streamTypeCode() != handshake.streamTypeCode()
                || streamCompletion.streamVersion() != handshake.streamVersion()
                || streamCompletion.completedFrames() != handshake.expectedFrames()
                || !streamCompletion.capabilityManifest().equals(handshake.capabilityManifest())) {
            throw new IOException("headless snapshot stream completion does not match handshake");
        }
    }

    private static ProtocolRegistryEntry requireRegisteredSnapshotIdentity(
            int wireTypeCode,
            int schemaVersion) throws IOException {
        try {
            return NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    wireTypeCode,
                    schemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot stream uses unregistered wire identity: "
                            + Integer.toUnsignedString(wireTypeCode)
                            + " v" + schemaVersion,
                    badIdentity);
        }
    }

    private static SnapshotStreamIdentity requireRegisteredSnapshotStreamIdentity(
            int streamTypeCode,
            int streamVersion) throws IOException {
        try {
            return NetworkMessageRegistry.authoritativeCombat().requireSnapshotStreamType(
                    streamTypeCode,
                    streamVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot stream uses unregistered stream identity: "
                            + Integer.toUnsignedString(streamTypeCode)
                            + " v" + streamVersion,
                    badIdentity);
        }
    }

    private static void requireRegisteredStreamCompletionIdentity(
            int streamCompletionMessageTypeCode,
            int streamCompletionSchemaVersion) throws IOException {
        ProtocolRegistryEntry streamCompletionEntry;
        try {
            streamCompletionEntry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    streamCompletionMessageTypeCode,
                    streamCompletionSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "headless snapshot stream completion uses unregistered completion identity: "
                            + Integer.toUnsignedString(streamCompletionMessageTypeCode)
                            + " v" + streamCompletionSchemaVersion,
                    badIdentity);
        }
        if (!SnapshotStreamCompletedMessage.MESSAGE_ID.equals(streamCompletionEntry.messageId())) {
            throw new IOException("headless snapshot stream completion message id mismatch: "
                    + streamCompletionEntry.messageId());
        }
    }

    static void requireStreamIdentityMatchesFrames(
            ClientHandshake handshake,
            int expectedFrames,
            List<OfflineCombatStepWireFrame> frames) {
        SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                .requireSnapshotStreamType(handshake.streamTypeCode(), handshake.streamVersion());
        if (!streamIdentity.streamId().equals(handshake.streamId())) {
            throw new IllegalArgumentException("snapshot stream identity does not match registry export");
        }
        if (streamIdentity.hasFrameRequirement() && streamIdentity.requiredFrames() != expectedFrames) {
            throw new IllegalArgumentException("snapshot stream required frame count does not match receipt");
        }
        if (streamIdentity.hasShipCountRequirement()) {
            int maxShips = streamIdentity.requiredShipCount();
            int minShips = streamIdentity.minShipCount();
            for (OfflineCombatStepWireFrame frame : frames) {
                int count = frame.result().shipCount();
                if (count < minShips || count > maxShips) {
                    throw new IllegalArgumentException("snapshot stream required ship count does not match frame");
                }
            }
        }
        if (streamIdentity.requiresStateChanges()) {
            if (frames.size() < 2) {
                throw new IllegalArgumentException("state-changing snapshot stream requires at least two frames");
            }
            long firstStateHash = frames.get(0).result().stateHash();
            boolean sawDifferentStateHash = false;
            for (OfflineCombatStepWireFrame frame : frames) {
                if (frame.result().stateHash() != firstStateHash) {
                    sawDifferentStateHash = true;
                    break;
                }
            }
            if (!sawDifferentStateHash) {
                throw new IllegalArgumentException("state-changing snapshot stream requires changing state hashes");
            }
        }
    }


    private static short checkedManifestVersion(NetworkCapabilityManifest capabilityManifest) {
        int manifestVersion = capabilityManifest.manifestVersion();
        if (manifestVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("manifest version exceeds signed short range: " + manifestVersion);
        }
        return (short) manifestVersion;
    }

    static long socketHostCrc32(String socketHost) {
        if (socketHost == null || socketHost.isBlank()) {
            throw new IllegalArgumentException("socketHost must be non-blank");
        }
        CRC32 crc = new CRC32();
        byte[] bytes = socketHost.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }

    static boolean isOffLoopbackHost(String socketHost) throws IOException {
        InetAddress address = InetAddress.getByName(socketHost);
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress();
    }

    static short checkedProtocolSchemaVersion(ProtocolRegistryEntry registryEntry) {
        int schemaVersion = registryEntry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("wire schema version exceeds signed short range: " + schemaVersion);
        }
        return (short) schemaVersion;
    }
}
