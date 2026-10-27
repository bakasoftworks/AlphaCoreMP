package cc.starsector.server.client;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.NetworkCapabilityManifest;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import cc.starsector.server.protocol.SeatRole;
import cc.starsector.server.protocol.SnapshotAdmissionRequestMessage;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.runtime.OfflineCombatStepWireCodec;
import cc.starsector.server.runtime.OfflineCombatStepWireFrame;
import cc.starsector.server.runtime.OfflineCombatStepResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Minimal headless client for the registered offline-combat snapshot stream.
 *
 * <p>The client advertises the exact registry wire identity it can consume,
 * connects over a real socket, then decodes frames from the socket input stream
 * through the product wire codec. The receiver artifact is only a tee of bytes
 * already read from the socket.
 */
public final class HeadlessSnapshotSocketClient {

    public static final int HANDSHAKE_MAGIC = 0x48534343; // "HSCC"
    // v2 (2026-06-11, demo-program R1): appends the wire-freeze reservation fields —
    // seat token, seat role, content fingerprint. Reserved-and-carried; enforcement is R8.
    public static final short HANDSHAKE_VERSION = 2;
    public static final int ADMISSION_ACK_MAGIC = 0x48534143; // "HSAC"
    private static final ProtocolRegistryEntry ADMISSION_REQUEST_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().snapshotAdmissionRequestWireEntry();
    public static final int ADMISSION_REQUEST_MESSAGE_TYPE_CODE =
            ADMISSION_REQUEST_REGISTRY_ENTRY.wireTypeCode();
    public static final short ADMISSION_REQUEST_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(ADMISSION_REQUEST_REGISTRY_ENTRY);
    public static final int CLIENT_AUTHORITY_ID_BYTES = 32;
    public static final String DEFAULT_CLIENT_AUTHORITY_ID = "default";
    public static final long DEFAULT_CLIENT_OWNED_SHIP_ID = 1L;
    /** Per-seat opaque token capacity: 256-bit token in hex (or base64) fits. Blank = unasserted. */
    public static final int SEAT_TOKEN_BYTES = 64;
    /** Universe fingerprint capacity (build md5 + modset hash + schema + knob fingerprint). Blank = unasserted. */
    public static final int CONTENT_FINGERPRINT_BYTES = 128;
    public static final String UNASSERTED_SEAT_TOKEN = "";
    public static final String UNASSERTED_CONTENT_FINGERPRINT = "";
    public static final int HANDSHAKE_BYTES =
            Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + CLIENT_AUTHORITY_ID_BYTES
                    + Long.BYTES
                    + Short.BYTES
                    + SEAT_TOKEN_BYTES
                    + Byte.BYTES
                    + Short.BYTES
                    + CONTENT_FINGERPRINT_BYTES;
    public static final int MOVEMENT_COMMAND_MAGIC = 0x48534D43; // "HSMC"
    private static final ProtocolRegistryEntry MOVEMENT_COMMAND_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().clientShipMovementCommandWireEntry();
    public static final int MOVEMENT_COMMAND_MESSAGE_TYPE_CODE =
            MOVEMENT_COMMAND_REGISTRY_ENTRY.wireTypeCode();
    public static final short MOVEMENT_COMMAND_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(MOVEMENT_COMMAND_REGISTRY_ENTRY);
    public static final int MOVEMENT_COMMAND_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES;
    public static final int WEAPON_GROUP_COMMAND_MAGIC = 0x48534743; // "HSGC"
    private static final ProtocolRegistryEntry WEAPON_GROUP_COMMAND_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().clientShipWeaponGroupCommandWireEntry();
    public static final int WEAPON_GROUP_COMMAND_MESSAGE_TYPE_CODE =
            WEAPON_GROUP_COMMAND_REGISTRY_ENTRY.wireTypeCode();
    public static final short WEAPON_GROUP_COMMAND_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(WEAPON_GROUP_COMMAND_REGISTRY_ENTRY);
    public static final int WEAPON_GROUP_COMMAND_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES;
    public static final int FIRE_COMMAND_MAGIC = 0x48534643; // "HSFC"
    private static final ProtocolRegistryEntry FIRE_COMMAND_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().clientShipFireCommandWireEntry();
    public static final int FIRE_COMMAND_MESSAGE_TYPE_CODE =
            FIRE_COMMAND_REGISTRY_ENTRY.wireTypeCode();
    public static final short FIRE_COMMAND_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(FIRE_COMMAND_REGISTRY_ENTRY);
    public static final int FIRE_COMMAND_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Byte.BYTES;
    public static final int SYSTEM_COMMAND_MAGIC = 0x48535943; // "HSYC"
    private static final ProtocolRegistryEntry SYSTEM_COMMAND_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().clientShipSystemCommandWireEntry();
    public static final int SYSTEM_COMMAND_MESSAGE_TYPE_CODE =
            SYSTEM_COMMAND_REGISTRY_ENTRY.wireTypeCode();
    public static final short SYSTEM_COMMAND_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(SYSTEM_COMMAND_REGISTRY_ENTRY);
    public static final int SYSTEM_COMMAND_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES;
    public static final int SESSION_RESUME_CLAIM_MAGIC = 0x48535243; // "HSRC"
    private static final ProtocolRegistryEntry SESSION_RESUME_CLAIM_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().clientSessionResumeClaimWireEntry();
    public static final int SESSION_RESUME_CLAIM_MESSAGE_TYPE_CODE =
            SESSION_RESUME_CLAIM_REGISTRY_ENTRY.wireTypeCode();
    public static final short SESSION_RESUME_CLAIM_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(SESSION_RESUME_CLAIM_REGISTRY_ENTRY);
    public static final int SESSION_RESUME_CLAIM_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    public static final int MOVEMENT_COMMAND_RESULT_MAGIC = 0x48534D52; // "HSMR"
    private static final ProtocolRegistryEntry MOVEMENT_COMMAND_RESULT_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverShipMovementCommandResultWireEntry();
    public static final int MOVEMENT_COMMAND_RESULT_MESSAGE_TYPE_CODE =
            MOVEMENT_COMMAND_RESULT_REGISTRY_ENTRY.wireTypeCode();
    public static final short MOVEMENT_COMMAND_RESULT_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(MOVEMENT_COMMAND_RESULT_REGISTRY_ENTRY);
    public static final int MOVEMENT_COMMAND_RESULT_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    public static final int WEAPON_GROUP_COMMAND_RESULT_MAGIC = 0x48534752; // "HSGR"
    private static final ProtocolRegistryEntry WEAPON_GROUP_COMMAND_RESULT_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverShipWeaponGroupCommandResultWireEntry();
    public static final int WEAPON_GROUP_COMMAND_RESULT_MESSAGE_TYPE_CODE =
            WEAPON_GROUP_COMMAND_RESULT_REGISTRY_ENTRY.wireTypeCode();
    public static final short WEAPON_GROUP_COMMAND_RESULT_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(WEAPON_GROUP_COMMAND_RESULT_REGISTRY_ENTRY);
    public static final int WEAPON_GROUP_COMMAND_RESULT_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    public static final int FIRE_COMMAND_RESULT_MAGIC = 0x48534652; // "HSFR"
    private static final ProtocolRegistryEntry FIRE_COMMAND_RESULT_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverShipFireCommandResultWireEntry();
    public static final int FIRE_COMMAND_RESULT_MESSAGE_TYPE_CODE =
            FIRE_COMMAND_RESULT_REGISTRY_ENTRY.wireTypeCode();
    public static final short FIRE_COMMAND_RESULT_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(FIRE_COMMAND_RESULT_REGISTRY_ENTRY);
    public static final int FIRE_COMMAND_RESULT_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Byte.BYTES
                    + Integer.BYTES;
    public static final int SYSTEM_COMMAND_RESULT_MAGIC = 0x48535952; // "HSYR"
    private static final ProtocolRegistryEntry SYSTEM_COMMAND_RESULT_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverShipSystemCommandResultWireEntry();
    public static final int SYSTEM_COMMAND_RESULT_MESSAGE_TYPE_CODE =
            SYSTEM_COMMAND_RESULT_REGISTRY_ENTRY.wireTypeCode();
    public static final short SYSTEM_COMMAND_RESULT_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(SYSTEM_COMMAND_RESULT_REGISTRY_ENTRY);
    public static final int SYSTEM_COMMAND_RESULT_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    private static final ProtocolRegistryEntry ADMISSION_ACK_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().snapshotAdmissionAckWireEntry();
    public static final int ADMISSION_ACK_MESSAGE_TYPE_CODE = ADMISSION_ACK_REGISTRY_ENTRY.wireTypeCode();
    public static final short ADMISSION_ACK_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(ADMISSION_ACK_REGISTRY_ENTRY);
    public static final int ADMISSION_ACK_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + 1
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    public static final int TRANSPORT_SESSION_OPENED_MAGIC = 0x4853544F; // "HSTO"
    private static final ProtocolRegistryEntry TRANSPORT_SESSION_OPENED_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverTransportSessionOpenedWireEntry();
    public static final int TRANSPORT_SESSION_OPENED_MESSAGE_TYPE_CODE =
            TRANSPORT_SESSION_OPENED_REGISTRY_ENTRY.wireTypeCode();
    public static final short TRANSPORT_SESSION_OPENED_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(TRANSPORT_SESSION_OPENED_REGISTRY_ENTRY);
    public static final int TRANSPORT_SESSION_OPENED_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + 1
                    + Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    public static final int SESSION_WINDOW_CURSOR_MAGIC = 0x48535743; // "HSWC"
    private static final ProtocolRegistryEntry SESSION_WINDOW_CURSOR_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverSessionWindowCursorWireEntry();
    public static final int SESSION_WINDOW_CURSOR_MESSAGE_TYPE_CODE =
            SESSION_WINDOW_CURSOR_REGISTRY_ENTRY.wireTypeCode();
    public static final short SESSION_WINDOW_CURSOR_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(SESSION_WINDOW_CURSOR_REGISTRY_ENTRY);
    public static final int SESSION_WINDOW_CURSOR_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    public static final int INITIAL_SYNC_MAGIC = 0x48534953; // "HSIS"
    private static final ProtocolRegistryEntry INITIAL_SYNC_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverInitialSyncWireEntry();
    public static final int INITIAL_SYNC_MESSAGE_TYPE_CODE =
            INITIAL_SYNC_REGISTRY_ENTRY.wireTypeCode();
    public static final short INITIAL_SYNC_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(INITIAL_SYNC_REGISTRY_ENTRY);
    public static final int INITIAL_SYNC_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Long.BYTES
                    + Long.BYTES
                    + 1
                    + Short.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    public static final int STREAM_COMPLETED_MAGIC = 0x48535343; // "HSSC"
    private static final ProtocolRegistryEntry STREAM_COMPLETED_REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().snapshotStreamCompletedWireEntry();
    public static final int STREAM_COMPLETED_MESSAGE_TYPE_CODE =
            STREAM_COMPLETED_REGISTRY_ENTRY.wireTypeCode();
    public static final short STREAM_COMPLETED_SCHEMA_VERSION =
            HeadlessSnapshotControlWire.checkedProtocolSchemaVersion(STREAM_COMPLETED_REGISTRY_ENTRY);
    public static final int STREAM_COMPLETED_BYTES =
            Integer.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Integer.BYTES
                    + Integer.BYTES;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;
    private final ClientAuthorityIdentity authorityIdentity;
    private final SeatCredential seatCredential;

    public HeadlessSnapshotSocketClient() {
        this(defaultAuthorityIdentity());
    }

    public HeadlessSnapshotSocketClient(ClientAuthorityIdentity authorityIdentity) {
        this(authorityIdentity, SeatCredential.unasserted());
    }

    /**
     * demo-program R6 token-delivery: a client that delivers a non-blank {@link SeatCredential}
     * (seat token + role + content fingerprint) in its handshake so the server runner's R8b
     * admission ON path (seat ledger + fingerprint door-check) runs end-to-end. An
     * {@link SeatCredential#unasserted()} credential carries blank token/fingerprint — the
     * perimeter-trust v0 default, byte-identical on the wire to the pre-R6 handshake.
     */
    public HeadlessSnapshotSocketClient(ClientAuthorityIdentity authorityIdentity, SeatCredential seatCredential) {
        this.authorityIdentity = Objects.requireNonNull(authorityIdentity, "authorityIdentity");
        this.seatCredential = Objects.requireNonNull(seatCredential, "seatCredential");
    }

    public static ClientAuthorityIdentity defaultAuthorityIdentity() {
        return new ClientAuthorityIdentity(DEFAULT_CLIENT_AUTHORITY_ID, DEFAULT_CLIENT_OWNED_SHIP_ID);
    }

    /**
     * The seat credential a client delivers in its handshake (demo-program R1 wire-freeze fields,
     * R6 delivery, R8 enforcement): {@code seatToken} (per-seat opaque identity), {@code seatRole},
     * and {@code contentFingerprint} (universe identity). {@link #unasserted()} = blank
     * token/fingerprint + PILOT role = the perimeter-trust v0 default (no enforcement engaged).
     */
    public record SeatCredential(String seatToken, SeatRole seatRole, String contentFingerprint) {
        public SeatCredential {
            seatToken = Objects.requireNonNull(seatToken, "seatToken");
            seatRole = Objects.requireNonNull(seatRole, "seatRole");
            contentFingerprint = Objects.requireNonNull(contentFingerprint, "contentFingerprint");
        }

        /** The unasserted default: blank token + blank fingerprint + PILOT role. */
        public static SeatCredential unasserted() {
            return new SeatCredential(UNASSERTED_SEAT_TOKEN, SeatRole.PILOT, UNASSERTED_CONTENT_FINGERPRINT);
        }

        /** True iff this credential asserts a non-blank seat token or content fingerprint. */
        public boolean asserted() {
            return !seatToken.isBlank() || !contentFingerprint.isBlank();
        }
    }

    /**
     * Build this client's advertised handshake carrying its {@link SeatCredential} (R6
     * token-delivery). Routes the default capability manifest; the unasserted-credential default
     * reproduces the pre-R6 handshake byte-for-byte.
     */
    private ClientHandshake advertisedClientHandshake(int expectedFrames, SnapshotStreamIdentity streamIdentity) {
        return advertisedClientHandshake(
                expectedFrames,
                streamIdentity,
                NetworkMessageRegistry.authoritativeCombat().capabilityManifest());
    }

    private ClientHandshake advertisedClientHandshake(
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            NetworkCapabilityManifest capabilityManifest) {
        return advertisedHandshake(
                expectedFrames,
                streamIdentity,
                capabilityManifest,
                authorityIdentity,
                seatCredential.seatToken(),
                seatCredential.seatRole(),
                seatCredential.contentFingerprint());
    }

    public static void main(String[] args) {
        try {
            HeadlessSnapshotClientCliConfig config = HeadlessSnapshotClientCliConfig.parse(args);
            HeadlessSnapshotSocketClient client =
                    new HeadlessSnapshotSocketClient(config.authorityIdentity(), config.seatCredential());
            ClientMovementCommand movementCommand = config.movementCommandOrNull();
            ClientWeaponGroupCommand weaponGroupCommand = config.weaponGroupCommandOrNull();
            ClientFireCommand fireCommand = config.fireCommandOrNull();
            ClientSystemCommand systemCommand = config.systemCommandOrNull();
            if (config.admissionOnly()) {
                AdmissionReceipt receipt = client.connectForAdmission(
                        config.host(),
                        config.port(),
                        config.expectedFrames(),
                        config.streamIdentity(),
                        config.capabilityManifest());
                HeadlessSnapshotProcessReceiptWriter.write(config.receiptPath(), receipt);
            } else if (config.sessionWindowCursor()) {
                Receipt receipt = client.connectAndReadSessionWindow(
                        config.host(),
                        config.port(),
                        config.receiverArtifactPath(),
                        config.expectedFrames(),
                        frame -> {
                        },
                        movementCommand,
                        weaponGroupCommand,
                        fireCommand,
                        systemCommand,
                        config.streamIdentity(),
                        config.resumeClaimOrNull());
                HeadlessSnapshotProcessReceiptWriter.write(config.receiptPath(), receipt);
            } else if (config.persistentWindows() > 1) {
                PersistentReceipt receipt = client.connectAndReadPersistentWindows(
                        config.host(),
                        config.port(),
                        config.receiverArtifactPaths(),
                        config.expectedFrames(),
                        frame -> {
                        },
                        movementCommand,
                        weaponGroupCommand,
                        fireCommand,
                        systemCommand,
                        config.streamIdentity());
                HeadlessSnapshotProcessReceiptWriter.write(config.receiptPath(), receipt);
            } else {
                Receipt receipt = movementCommand == null
                        && weaponGroupCommand == null
                        && fireCommand == null
                        && systemCommand == null
                        ? client.connectAndRead(
                                config.host(),
                                config.port(),
                                config.receiverArtifactPath(),
                                config.expectedFrames(),
                                config.streamIdentity(),
                                frame -> {
                                })
                        : client.connectAndReadWithCommands(
                                config.host(),
                                config.port(),
                                config.receiverArtifactPath(),
                                config.expectedFrames(),
                                frame -> {
                                },
                                movementCommand,
                                weaponGroupCommand,
                                fireCommand,
                                systemCommand,
                                config.streamIdentity());
                HeadlessSnapshotProcessReceiptWriter.write(config.receiptPath(), receipt);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public Receipt connectAndRead(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotStreamIdentity(),
                frameConsumer,
                null,
                null,
                null,
                null);
    }

    public Receipt connectAndRead(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                null,
                null,
                null,
                null);
    }

    public Receipt connectAndReadWithMovementCommand(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotStreamIdentity(),
                frameConsumer,
                Objects.requireNonNull(movementCommand, "movementCommand"),
                null,
                null,
                null);
    }

    public Receipt connectAndReadWithMovementCommand(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                Objects.requireNonNull(movementCommand, "movementCommand"),
                null,
                null,
                null);
    }

    public Receipt connectAndReadWithFireCommand(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientFireCommand fireCommand,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                null,
                null,
                Objects.requireNonNull(fireCommand, "fireCommand"),
                null);
    }

    public Receipt connectAndReadWithWeaponGroupCommand(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientWeaponGroupCommand weaponGroupCommand,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                null,
                Objects.requireNonNull(weaponGroupCommand, "weaponGroupCommand"),
                null,
                null);
    }

    public Receipt connectAndReadWithSystemCommand(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientSystemCommand systemCommand,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                null,
                null,
                null,
                Objects.requireNonNull(systemCommand, "systemCommand"));
    }

    public Receipt connectAndReadWithCommands(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            ClientFireCommand fireCommand,
            ClientSystemCommand systemCommand,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return connectAndReadWithCommands(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                frameConsumer,
                movementCommand,
                null,
                fireCommand,
                systemCommand,
                streamIdentity);
    }

    public Receipt connectAndReadWithCommands(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            ClientWeaponGroupCommand weaponGroupCommand,
            ClientFireCommand fireCommand,
            ClientSystemCommand systemCommand,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        if (movementCommand == null && weaponGroupCommand == null && fireCommand == null && systemCommand == null) {
            throw new IllegalArgumentException("at least one command must be provided");
        }
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                movementCommand,
                weaponGroupCommand,
                fireCommand,
                systemCommand);
    }

    private Receipt connectAndReadInternal(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                movementCommand,
                null,
                null,
                null);
    }

    private Receipt connectAndReadInternal(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            ClientWeaponGroupCommand weaponGroupCommand,
            ClientFireCommand fireCommand,
            ClientSystemCommand systemCommand) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                movementCommand,
                weaponGroupCommand,
                fireCommand,
                systemCommand,
                false,
                null);
    }

    public Receipt connectAndReadSessionWindow(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            SnapshotStreamIdentity streamIdentity,
            SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        return connectAndReadSessionWindow(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                frameConsumer,
                movementCommand,
                null,
                null,
                null,
                streamIdentity,
                resumeClaimParameters);
    }

    public Receipt connectAndReadSessionWindow(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            ClientFireCommand fireCommand,
            ClientSystemCommand systemCommand,
            SnapshotStreamIdentity streamIdentity,
            SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        return connectAndReadSessionWindow(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                frameConsumer,
                movementCommand,
                null,
                fireCommand,
                systemCommand,
                streamIdentity,
                resumeClaimParameters);
    }

    public Receipt connectAndReadSessionWindow(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            ClientWeaponGroupCommand weaponGroupCommand,
            ClientFireCommand fireCommand,
            ClientSystemCommand systemCommand,
            SnapshotStreamIdentity streamIdentity,
            SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        return connectAndReadInternal(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                streamIdentity,
                frameConsumer,
                movementCommand,
                weaponGroupCommand,
                fireCommand,
                systemCommand,
                true,
                resumeClaimParameters);
    }

    private Receipt connectAndReadInternal(
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            ClientWeaponGroupCommand weaponGroupCommand,
            ClientFireCommand fireCommand,
            ClientSystemCommand systemCommand,
            boolean readSessionWindowCursor,
            SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(frameConsumer, "frameConsumer");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }

        long expectedBytes = Math.multiplyExact(
                (long) expectedFrames,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        ClientHandshake handshake = advertisedClientHandshake(expectedFrames, streamIdentity);
        ClientSessionResumeClaim sessionResumeClaim =
                resumeClaimParameters == null ? null : resumeClaimParameters.toClaim(handshake);
        int sessionResumeClaimBytes = sessionResumeClaim == null ? 0 : SESSION_RESUME_CLAIM_BYTES;
        ServerAdmissionAck admissionAck;
        ServerTransportSessionOpened transportSessionOpened;
        ServerMovementCommandResult movementCommandResult = null;
        int movementCommandResultBytes = 0;
        ServerWeaponGroupCommandResult weaponGroupCommandResult = null;
        int weaponGroupCommandResultBytes = 0;
        ServerFireCommandResult fireCommandResult = null;
        int fireCommandResultBytes = 0;
        ServerSystemCommandResult systemCommandResult = null;
        int systemCommandResultBytes = 0;
        ServerSessionWindowCursor sessionWindowCursor = null;
        int sessionWindowCursorBytes = 0;
        ServerInitialSync initialSync;
        int initialSyncBytes;
        ServerStreamCompletion streamCompletion;
        List<OfflineCombatStepWireFrame> frames = new ArrayList<>();
        long bytesReceived;
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MILLIS);
            writeHandshake(socket.getOutputStream(), handshake);
            if (sessionResumeClaim != null) {
                writeSessionResumeClaim(socket.getOutputStream(), sessionResumeClaim);
            }
            if (movementCommand != null) {
                writeMovementCommand(socket.getOutputStream(), movementCommand);
            }
            if (weaponGroupCommand != null) {
                writeWeaponGroupCommand(socket.getOutputStream(), weaponGroupCommand);
            }
            if (fireCommand != null) {
                writeFireCommand(socket.getOutputStream(), fireCommand);
            }
            if (systemCommand != null) {
                writeSystemCommand(socket.getOutputStream(), systemCommand);
            }
            socket.shutdownOutput();

            try (InputStream input = socket.getInputStream();
                    OutputStream artifactOutput = Files.newOutputStream(
                            receiverArtifactPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                admissionAck = readAdmissionAck(input, handshake);
                transportSessionOpened = readTransportSessionOpened(input, handshake, host, port);
                if (movementCommand != null) {
                    movementCommandResult = readMovementCommandResult(input, movementCommand);
                    movementCommandResultBytes = MOVEMENT_COMMAND_RESULT_BYTES;
                }
                if (weaponGroupCommand != null) {
                    weaponGroupCommandResult = readWeaponGroupCommandResult(input, weaponGroupCommand);
                    weaponGroupCommandResultBytes = WEAPON_GROUP_COMMAND_RESULT_BYTES;
                }
                if (fireCommand != null) {
                    fireCommandResult = readFireCommandResult(input, fireCommand);
                    fireCommandResultBytes = FIRE_COMMAND_RESULT_BYTES;
                }
                if (systemCommand != null) {
                    systemCommandResult = readSystemCommandResult(input, systemCommand);
                    systemCommandResultBytes = SYSTEM_COMMAND_RESULT_BYTES;
                }
                if (readSessionWindowCursor) {
                    sessionWindowCursor = readSessionWindowCursor(input, handshake);
                    sessionWindowCursorBytes = SESSION_WINDOW_CURSOR_BYTES;
                }
                initialSync = readInitialSync(input, handshake);
                initialSyncBytes = INITIAL_SYNC_BYTES;
                BoundedTeeInputStream directInput =
                        new BoundedTeeInputStream(input, artifactOutput, expectedBytes);
                try {
                    OfflineCombatStepWireCodec.readFrames(directInput, frame -> {
                        frames.add(frame);
                        HeadlessSnapshotControlWire.requireSnapshotFrame(frame, handshake);
                        frameConsumer.accept(frame);
                    });
                } finally {
                    bytesReceived = directInput.bytesRead();
                }
                streamCompletion = readStreamCompletion(input, handshake);
                artifactOutput.flush();
            }
        }

        if (bytesReceived != expectedBytes) {
            throw new IOException("headless snapshot client read unexpected byte count: expected "
                    + expectedBytes + ", got " + bytesReceived);
        }
        if (frames.size() != expectedFrames) {
            throw new IOException("headless snapshot client frame count mismatch: expected "
                    + expectedFrames + ", got " + frames.size());
        }

        return new Receipt(
                host,
                port,
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                bytesReceived,
                "socket_input_stream",
                false,
                handshake,
                ADMISSION_ACK_BYTES,
                admissionAck,
                TRANSPORT_SESSION_OPENED_BYTES,
                transportSessionOpened,
                movementCommandResultBytes,
                movementCommandResult,
                weaponGroupCommandResultBytes,
                weaponGroupCommandResult,
                fireCommandResultBytes,
                fireCommandResult,
                systemCommandResultBytes,
                systemCommandResult,
                sessionResumeClaimBytes,
                sessionResumeClaim,
                sessionWindowCursorBytes,
                sessionWindowCursor,
                initialSyncBytes,
                initialSync,
                STREAM_COMPLETED_BYTES,
                streamCompletion,
                frames);
    }

    public PersistentReceipt connectAndReadPersistentWindows(
            String host,
            int port,
            List<Path> receiverArtifactPaths,
            int expectedFramesPerWindow,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            ClientMovementCommand movementCommand,
            ClientWeaponGroupCommand weaponGroupCommand,
            ClientFireCommand fireCommand,
            ClientSystemCommand systemCommand,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths");
        Objects.requireNonNull(frameConsumer, "frameConsumer");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (expectedFramesPerWindow < 0) {
            throw new IllegalArgumentException(
                    "expectedFramesPerWindow must be >= 0; got " + expectedFramesPerWindow);
        }
        if (receiverArtifactPaths.size() < 2) {
            throw new IllegalArgumentException("persistent session requires at least two stream windows");
        }
        List<Path> artifactPaths = List.copyOf(receiverArtifactPaths);
        for (Path artifactPath : artifactPaths) {
            Objects.requireNonNull(artifactPath, "receiverArtifactPath");
            Path receiverParent = artifactPath.getParent();
            if (receiverParent != null) {
                Files.createDirectories(receiverParent);
            }
        }

        ClientHandshake handshake = advertisedClientHandshake(
                expectedFramesPerWindow,
                streamIdentity);
        ServerAdmissionAck admissionAck;
        ServerTransportSessionOpened transportSessionOpened;
        ServerMovementCommandResult movementCommandResult = null;
        int movementCommandResultBytes = 0;
        ServerWeaponGroupCommandResult weaponGroupCommandResult = null;
        int weaponGroupCommandResultBytes = 0;
        ServerFireCommandResult fireCommandResult = null;
        int fireCommandResultBytes = 0;
        ServerSystemCommandResult systemCommandResult = null;
        int systemCommandResultBytes = 0;
        List<PersistentWindowReceipt> windows = new ArrayList<>(artifactPaths.size());
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MILLIS);
            writeHandshake(socket.getOutputStream(), handshake);
            if (movementCommand != null) {
                writeMovementCommand(socket.getOutputStream(), movementCommand);
            }
            if (weaponGroupCommand != null) {
                writeWeaponGroupCommand(socket.getOutputStream(), weaponGroupCommand);
            }
            if (fireCommand != null) {
                writeFireCommand(socket.getOutputStream(), fireCommand);
            }
            if (systemCommand != null) {
                writeSystemCommand(socket.getOutputStream(), systemCommand);
            }
            socket.shutdownOutput();

            try (InputStream input = socket.getInputStream()) {
                admissionAck = readAdmissionAck(input, handshake);
                transportSessionOpened = readTransportSessionOpened(input, handshake, host, port);
                if (movementCommand != null) {
                    movementCommandResult = readMovementCommandResult(input, movementCommand);
                    movementCommandResultBytes = MOVEMENT_COMMAND_RESULT_BYTES;
                }
                if (weaponGroupCommand != null) {
                    weaponGroupCommandResult = readWeaponGroupCommandResult(input, weaponGroupCommand);
                    weaponGroupCommandResultBytes = WEAPON_GROUP_COMMAND_RESULT_BYTES;
                }
                if (fireCommand != null) {
                    fireCommandResult = readFireCommandResult(input, fireCommand);
                    fireCommandResultBytes = FIRE_COMMAND_RESULT_BYTES;
                }
                if (systemCommand != null) {
                    systemCommandResult = readSystemCommandResult(input, systemCommand);
                    systemCommandResultBytes = SYSTEM_COMMAND_RESULT_BYTES;
                }
                for (int windowIndex = 0; windowIndex < artifactPaths.size(); windowIndex++) {
                    windows.add(readPersistentWindow(
                            input,
                            artifactPaths.get(windowIndex),
                            expectedFramesPerWindow,
                            handshake,
                            frameConsumer,
                            windowIndex));
                }
            }
        }

        return new PersistentReceipt(
                host,
                port,
                expectedFramesPerWindow,
                "socket_input_stream",
                false,
                handshake,
                ADMISSION_ACK_BYTES,
                admissionAck,
                TRANSPORT_SESSION_OPENED_BYTES,
                transportSessionOpened,
                movementCommandResultBytes,
                movementCommandResult,
                weaponGroupCommandResultBytes,
                weaponGroupCommandResult,
                fireCommandResultBytes,
                fireCommandResult,
                systemCommandResultBytes,
                systemCommandResult,
                windows);
    }

    private static PersistentWindowReceipt readPersistentWindow(
            InputStream input,
            Path receiverArtifactPath,
            int expectedFrames,
            ClientHandshake handshake,
            Consumer<? super OfflineCombatStepWireFrame> frameConsumer,
            int windowIndex) throws IOException {
        long expectedBytes = Math.multiplyExact(
                (long) expectedFrames,
                (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        ServerSessionWindowCursor sessionWindowCursor = readSessionWindowCursor(input, handshake);
        ServerInitialSync initialSync = readInitialSync(input, handshake);
        List<OfflineCombatStepWireFrame> frames = new ArrayList<>();
        long bytesReceived;
        try (OutputStream artifactOutput = Files.newOutputStream(
                receiverArtifactPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            BoundedTeeInputStream directInput =
                    new BoundedTeeInputStream(input, artifactOutput, expectedBytes);
            try {
                OfflineCombatStepWireCodec.readFrames(directInput, frame -> {
                    frames.add(frame);
                    HeadlessSnapshotControlWire.requireSnapshotFrame(frame, handshake);
                    frameConsumer.accept(frame);
                });
            } finally {
                bytesReceived = directInput.bytesRead();
            }
            artifactOutput.flush();
        }
        ServerStreamCompletion streamCompletion = readStreamCompletion(input, handshake);
        if (bytesReceived != expectedBytes) {
            throw new IOException("headless snapshot persistent window " + windowIndex
                    + " read unexpected byte count: expected " + expectedBytes + ", got " + bytesReceived);
        }
        if (frames.size() != expectedFrames) {
            throw new IOException("headless snapshot persistent window " + windowIndex
                    + " frame count mismatch: expected " + expectedFrames + ", got " + frames.size());
        }
        return new PersistentWindowReceipt(
                windowIndex,
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                bytesReceived,
                SESSION_WINDOW_CURSOR_BYTES,
                sessionWindowCursor,
                INITIAL_SYNC_BYTES,
                initialSync,
                STREAM_COMPLETED_BYTES,
                streamCompletion,
                frames);
    }

    public AdmissionReceipt connectForAdmission(
            String host,
            int port,
            int expectedFrames) throws IOException {
        return connectForAdmission(
                host,
                port,
                expectedFrames,
                NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotStreamIdentity());
    }

    public AdmissionReceipt connectForAdmission(
            String host,
            int port,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return connectForAdmission(
                host,
                port,
                expectedFrames,
                streamIdentity,
                NetworkMessageRegistry.authoritativeCombat().capabilityManifest());
    }

    public AdmissionReceipt connectForAdmission(
            String host,
            int port,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            NetworkCapabilityManifest capabilityManifest) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }

        ClientHandshake handshake = advertisedClientHandshake(
                expectedFrames,
                streamIdentity,
                capabilityManifest);
        ServerAdmissionAck admissionAck;
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MILLIS);
            writeHandshake(socket.getOutputStream(), handshake);
            socket.shutdownOutput();
            admissionAck = readAdmissionAck(socket.getInputStream());
        }

        return new AdmissionReceipt(
                host,
                port,
                expectedFrames,
                handshake,
                ADMISSION_ACK_BYTES,
                admissionAck);
    }

    public static ClientHandshake advertisedHandshake(int expectedFrames) {
        return advertisedHandshake(
                expectedFrames,
                NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotStreamIdentity());
    }

    public static ClientHandshake advertisedHandshake(
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity) {
        return advertisedHandshake(
                expectedFrames,
                streamIdentity,
                NetworkMessageRegistry.authoritativeCombat().capabilityManifest(),
                defaultAuthorityIdentity());
    }

    public static ClientHandshake advertisedHandshake(
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            NetworkCapabilityManifest capabilityManifest) {
        return advertisedHandshake(
                expectedFrames,
                streamIdentity,
                capabilityManifest,
                defaultAuthorityIdentity());
    }

    public static ClientHandshake advertisedHandshake(
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            ClientAuthorityIdentity authorityIdentity) {
        return advertisedHandshake(
                expectedFrames,
                streamIdentity,
                NetworkMessageRegistry.authoritativeCombat().capabilityManifest(),
                authorityIdentity);
    }

    public static ClientHandshake advertisedHandshake(
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            NetworkCapabilityManifest capabilityManifest,
            ClientAuthorityIdentity authorityIdentity) {
        return advertisedHandshake(
                expectedFrames,
                streamIdentity,
                capabilityManifest,
                authorityIdentity,
                UNASSERTED_SEAT_TOKEN,
                SeatRole.PILOT,
                UNASSERTED_CONTENT_FINGERPRINT);
    }

    public static ClientHandshake advertisedHandshake(
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            NetworkCapabilityManifest capabilityManifest,
            ClientAuthorityIdentity authorityIdentity,
            String seatToken,
            SeatRole seatRole,
            String contentFingerprint) {
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        authorityIdentity = Objects.requireNonNull(authorityIdentity, "authorityIdentity");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        ProtocolRegistryEntry entry =
                NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotWireEntry();
        SnapshotStreamIdentity registeredStream =
                NetworkMessageRegistry.authoritativeCombat().requireSnapshotStream(
                        streamIdentity.streamId(),
                        streamIdentity.streamVersion());
        return new ClientHandshake(
                ADMISSION_REQUEST_REGISTRY_ENTRY.messageId(),
                ADMISSION_REQUEST_MESSAGE_TYPE_CODE,
                Short.toUnsignedInt(ADMISSION_REQUEST_SCHEMA_VERSION),
                entry.messageId(),
                entry.wireTypeCode(),
                entry.schemaVersion(),
                registeredStream.streamId(),
                registeredStream.streamTypeCode(),
                registeredStream.streamVersion(),
                expectedFrames,
                capabilityManifest,
                authorityIdentity.clientId(),
                authorityIdentity.ownedShipId(),
                seatToken,
                seatRole,
                contentFingerprint);
    }

    public static void writeHandshake(OutputStream output, ClientHandshake handshake) throws IOException {
        HeadlessSnapshotControlWire.writeHandshake(output, handshake);
    }

    public static ClientHandshake readHandshake(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readHandshake(input);
    }

    public static ClientMovementCommand movementCommand(
            long shipId,
            long simTick,
            ShipCommandSink.Movement movementIntent) {
        return HeadlessSnapshotControlWire.movementCommand(shipId, simTick, movementIntent);
    }

    public static void writeMovementCommand(
            OutputStream output,
            ClientMovementCommand movementCommand) throws IOException {
        HeadlessSnapshotControlWire.writeMovementCommand(output, movementCommand);
    }

    public static ClientMovementCommand readMovementCommand(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readMovementCommand(input);
    }

    public static ClientFireCommand fireCommand(
            long shipId,
            long simTick,
            boolean fire) {
        return HeadlessSnapshotControlWire.fireCommand(shipId, simTick, fire);
    }

    public static void writeFireCommand(
            OutputStream output,
            ClientFireCommand fireCommand) throws IOException {
        HeadlessSnapshotControlWire.writeFireCommand(output, fireCommand);
    }

    public static ClientFireCommand readFireCommand(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readFireCommand(input);
    }

    public static ClientWeaponGroupCommand weaponGroupCommand(
            long shipId,
            long simTick,
            int group) {
        return HeadlessSnapshotControlWire.weaponGroupCommand(shipId, simTick, group);
    }

    public static void writeWeaponGroupCommand(
            OutputStream output,
            ClientWeaponGroupCommand weaponGroupCommand) throws IOException {
        HeadlessSnapshotControlWire.writeWeaponGroupCommand(output, weaponGroupCommand);
    }

    public static ClientWeaponGroupCommand readWeaponGroupCommand(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readWeaponGroupCommand(input);
    }

    public static ClientSystemCommand systemCommand(
            long shipId,
            long simTick,
            int systemId) {
        return HeadlessSnapshotControlWire.systemCommand(shipId, simTick, systemId);
    }

    public static void writeSystemCommand(
            OutputStream output,
            ClientSystemCommand systemCommand) throws IOException {
        HeadlessSnapshotControlWire.writeSystemCommand(output, systemCommand);
    }

    public static ClientSystemCommand readSystemCommand(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readSystemCommand(input);
    }

    public static ClientSessionResumeClaim sessionResumeClaim(
            ClientHandshake handshake,
            long sessionId,
            int windowOrdinal,
            long previousResumeCursor,
            long nextResumeCursor) {
        return HeadlessSnapshotControlWire.sessionResumeClaim(
                handshake,
                sessionId,
                windowOrdinal,
                previousResumeCursor,
                nextResumeCursor);
    }

    public static ClientSessionResumeClaim sessionResumeClaim(
            ClientHandshake handshake,
            ServerSessionWindowCursor cursor) {
        return HeadlessSnapshotControlWire.sessionResumeClaim(handshake, cursor);
    }

    public static void writeSessionResumeClaim(
            OutputStream output,
            ClientSessionResumeClaim claim) throws IOException {
        HeadlessSnapshotControlWire.writeSessionResumeClaim(output, claim);
    }

    public static ClientSessionResumeClaim readSessionResumeClaim(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        return HeadlessSnapshotControlWire.readSessionResumeClaim(input, handshake);
    }

    public static ClientSessionResumeClaim readSessionResumeClaim(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readSessionResumeClaim(input);
    }

    public static void requireSessionResumeClaimMatchesCursor(
            ClientSessionResumeClaim claim,
            ServerSessionWindowCursor cursor) throws IOException {
        HeadlessSnapshotControlWire.requireSessionResumeClaimMatchesCursor(claim, cursor);
    }

    public static ServerMovementCommandResult movementCommandResult(
            ClientMovementCommand movementCommand,
            ShipCommandSink.Result commandResult) {
        return HeadlessSnapshotControlWire.movementCommandResult(movementCommand, commandResult);
    }

    public static void writeMovementCommandResult(
            OutputStream output,
            ServerMovementCommandResult movementCommandResult) throws IOException {
        HeadlessSnapshotControlWire.writeMovementCommandResult(output, movementCommandResult);
    }

    public static ServerMovementCommandResult readMovementCommandResult(
            InputStream input,
            ClientMovementCommand expectedCommand) throws IOException {
        return HeadlessSnapshotControlWire.readMovementCommandResult(input, expectedCommand);
    }

    public static ServerFireCommandResult fireCommandResult(
            ClientFireCommand fireCommand,
            ShipCommandSink.Result commandResult) {
        return HeadlessSnapshotControlWire.fireCommandResult(fireCommand, commandResult);
    }

    public static void writeFireCommandResult(
            OutputStream output,
            ServerFireCommandResult fireCommandResult) throws IOException {
        HeadlessSnapshotControlWire.writeFireCommandResult(output, fireCommandResult);
    }

    public static ServerFireCommandResult readFireCommandResult(
            InputStream input,
            ClientFireCommand expectedCommand) throws IOException {
        return HeadlessSnapshotControlWire.readFireCommandResult(input, expectedCommand);
    }

    public static ServerWeaponGroupCommandResult weaponGroupCommandResult(
            ClientWeaponGroupCommand weaponGroupCommand,
            ShipCommandSink.Result commandResult) {
        return HeadlessSnapshotControlWire.weaponGroupCommandResult(weaponGroupCommand, commandResult);
    }

    public static void writeWeaponGroupCommandResult(
            OutputStream output,
            ServerWeaponGroupCommandResult weaponGroupCommandResult) throws IOException {
        HeadlessSnapshotControlWire.writeWeaponGroupCommandResult(output, weaponGroupCommandResult);
    }

    public static ServerWeaponGroupCommandResult readWeaponGroupCommandResult(
            InputStream input,
            ClientWeaponGroupCommand expectedCommand) throws IOException {
        return HeadlessSnapshotControlWire.readWeaponGroupCommandResult(input, expectedCommand);
    }

    public static ServerSystemCommandResult systemCommandResult(
            ClientSystemCommand systemCommand,
            ShipCommandSink.Result commandResult) {
        return HeadlessSnapshotControlWire.systemCommandResult(systemCommand, commandResult);
    }

    public static void writeSystemCommandResult(
            OutputStream output,
            ServerSystemCommandResult systemCommandResult) throws IOException {
        HeadlessSnapshotControlWire.writeSystemCommandResult(output, systemCommandResult);
    }

    public static ServerSystemCommandResult readSystemCommandResult(
            InputStream input,
            ClientSystemCommand expectedCommand) throws IOException {
        return HeadlessSnapshotControlWire.readSystemCommandResult(input, expectedCommand);
    }

    public static void writeAdmissionAck(OutputStream output, ServerAdmissionAck admissionAck) throws IOException {
        HeadlessSnapshotControlWire.writeAdmissionAck(output, admissionAck);
    }

    public static ServerAdmissionAck readAdmissionAck(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        return HeadlessSnapshotControlWire.readAdmissionAck(input, handshake);
    }

    public static ServerAdmissionAck readAdmissionAck(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readAdmissionAck(input);
    }

    public static void writeTransportSessionOpened(
            OutputStream output,
            ServerTransportSessionOpened sessionOpened) throws IOException {
        HeadlessSnapshotControlWire.writeTransportSessionOpened(output, sessionOpened);
    }

    public static ServerTransportSessionOpened readTransportSessionOpened(
            InputStream input,
            ClientHandshake handshake,
            String socketHost,
            int socketPort) throws IOException {
        return HeadlessSnapshotControlWire.readTransportSessionOpened(input, handshake, socketHost, socketPort);
    }

    public static ServerTransportSessionOpened readTransportSessionOpened(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readTransportSessionOpened(input);
    }

    public static void writeSessionWindowCursor(
            OutputStream output,
            ServerSessionWindowCursor cursor) throws IOException {
        HeadlessSnapshotControlWire.writeSessionWindowCursor(output, cursor);
    }

    public static ServerSessionWindowCursor readSessionWindowCursor(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        return HeadlessSnapshotControlWire.readSessionWindowCursor(input, handshake);
    }

    public static ServerSessionWindowCursor readSessionWindowCursor(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readSessionWindowCursor(input);
    }

    public static void writeInitialSync(
            OutputStream output,
            ServerInitialSync initialSync) throws IOException {
        HeadlessSnapshotControlWire.writeInitialSync(output, initialSync);
    }

    public static ServerInitialSync readInitialSync(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        return HeadlessSnapshotControlWire.readInitialSync(input, handshake);
    }

    public static ServerInitialSync readInitialSync(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readInitialSync(input);
    }

    public static void writeStreamCompletion(
            OutputStream output,
            ServerStreamCompletion streamCompletion) throws IOException {
        HeadlessSnapshotControlWire.writeStreamCompletion(output, streamCompletion);
    }

    public static ServerStreamCompletion readStreamCompletion(
            InputStream input,
            ClientHandshake handshake) throws IOException {
        return HeadlessSnapshotControlWire.readStreamCompletion(input, handshake);
    }

    public static ServerStreamCompletion readStreamCompletion(InputStream input) throws IOException {
        return HeadlessSnapshotControlWire.readStreamCompletion(input);
    }
    public record ClientMovementCommand(
            String messageId,
            int messageTypeCode,
            int schemaVersion,
            long shipId,
            long simTick,
            ShipCommandSink.Movement movementIntent) {

        public ClientMovementCommand {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (shipId < 0L) {
                throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            movementIntent = Objects.requireNonNull(movementIntent, "movementIntent");
        }
    }

    public record ClientFireCommand(
            String messageId,
            int messageTypeCode,
            int schemaVersion,
            long shipId,
            long simTick,
            boolean fire) {

        public ClientFireCommand {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (shipId <= 0L) {
                throw new IllegalArgumentException("shipId must be > 0; got " + shipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
        }
    }

    public record ClientWeaponGroupCommand(
            String messageId,
            int messageTypeCode,
            int schemaVersion,
            long shipId,
            long simTick,
            int group) {

        public ClientWeaponGroupCommand {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (shipId <= 0L) {
                throw new IllegalArgumentException("shipId must be > 0; got " + shipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            if (group < 0) {
                throw new IllegalArgumentException("group must be >= 0; got " + group);
            }
        }
    }

    public record ClientSystemCommand(
            String messageId,
            int messageTypeCode,
            int schemaVersion,
            long shipId,
            long simTick,
            int systemId) {

        public ClientSystemCommand {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (shipId <= 0L) {
                throw new IllegalArgumentException("shipId must be > 0; got " + shipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            if (systemId < 0) {
                throw new IllegalArgumentException("systemId must be >= 0; got " + systemId);
            }
        }
    }

    public record ClientSessionResumeClaim(
            String claimMessageId,
            int claimMessageTypeCode,
            int claimSchemaVersion,
            String messageId,
            int wireTypeCode,
            int schemaVersion,
            String streamId,
            int streamTypeCode,
            int streamVersion,
            long sessionId,
            int windowOrdinal,
            long previousResumeCursor,
            long nextResumeCursor,
            int expectedFrames,
            NetworkCapabilityManifest capabilityManifest) {

        public ClientSessionResumeClaim {
            if (claimMessageId == null || claimMessageId.isBlank()) {
                throw new IllegalArgumentException("claimMessageId must be non-blank");
            }
            if (claimSchemaVersion <= 0) {
                throw new IllegalArgumentException("claimSchemaVersion must be > 0; got " + claimSchemaVersion);
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
            if (sessionId <= 0L) {
                throw new IllegalArgumentException("sessionId must be > 0; got " + sessionId);
            }
            if (windowOrdinal < 0) {
                throw new IllegalArgumentException("windowOrdinal must be >= 0; got " + windowOrdinal);
            }
            if (previousResumeCursor < 0L) {
                throw new IllegalArgumentException(
                        "previousResumeCursor must be >= 0; got " + previousResumeCursor);
            }
            if (nextResumeCursor < previousResumeCursor) {
                throw new IllegalArgumentException("nextResumeCursor must be >= previousResumeCursor");
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (nextResumeCursor != previousResumeCursor + expectedFrames) {
                throw new IllegalArgumentException("nextResumeCursor must advance by expectedFrames");
            }
            capabilityManifest = Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        }
    }

    public record SessionResumeClaimParameters(
            long sessionId,
            int windowOrdinal,
            long previousResumeCursor,
            long nextResumeCursor) {

        public ClientSessionResumeClaim toClaim(ClientHandshake handshake) {
            return sessionResumeClaim(
                    handshake,
                    sessionId,
                    windowOrdinal,
                    previousResumeCursor,
                    nextResumeCursor);
        }

        public SessionResumeClaimParameters {
            if (sessionId <= 0L) {
                throw new IllegalArgumentException("sessionId must be > 0; got " + sessionId);
            }
            if (windowOrdinal < 0) {
                throw new IllegalArgumentException("windowOrdinal must be >= 0; got " + windowOrdinal);
            }
            if (previousResumeCursor < 0L) {
                throw new IllegalArgumentException(
                        "previousResumeCursor must be >= 0; got " + previousResumeCursor);
            }
            if (nextResumeCursor < previousResumeCursor) {
                throw new IllegalArgumentException("nextResumeCursor must be >= previousResumeCursor");
            }
        }
    }

    public record ServerMovementCommandResult(
            String messageId,
            int messageTypeCode,
            int schemaVersion,
            long shipId,
            long simTick,
            ShipCommandSink.Movement movementIntent,
            ShipCommandSink.Result commandResult) {

        public ServerMovementCommandResult {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (shipId <= 0L) {
                throw new IllegalArgumentException("shipId must be > 0; got " + shipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            movementIntent = Objects.requireNonNull(movementIntent, "movementIntent");
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
        }
    }

    public record ServerFireCommandResult(
            String messageId,
            int messageTypeCode,
            int schemaVersion,
            long shipId,
            long simTick,
            boolean fire,
            ShipCommandSink.Result commandResult) {

        public ServerFireCommandResult {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (shipId <= 0L) {
                throw new IllegalArgumentException("shipId must be > 0; got " + shipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
        }
    }

    public record ServerWeaponGroupCommandResult(
            String messageId,
            int messageTypeCode,
            int schemaVersion,
            long shipId,
            long simTick,
            int group,
            ShipCommandSink.Result commandResult) {

        public ServerWeaponGroupCommandResult {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (shipId <= 0L) {
                throw new IllegalArgumentException("shipId must be > 0; got " + shipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            if (group < 0) {
                throw new IllegalArgumentException("group must be >= 0; got " + group);
            }
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
        }
    }

    public record ServerSystemCommandResult(
            String messageId,
            int messageTypeCode,
            int schemaVersion,
            long shipId,
            long simTick,
            int systemId,
            ShipCommandSink.Result commandResult) {

        public ServerSystemCommandResult {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (shipId <= 0L) {
                throw new IllegalArgumentException("shipId must be > 0; got " + shipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            if (systemId < 0) {
                throw new IllegalArgumentException("systemId must be >= 0; got " + systemId);
            }
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
        }
    }

    /**
     * The admission handshake (HSCC v2). The v2 tail fields are the demo-program R1
     * wire-freeze reservations (2026-06-11), carried now and enforced by R8.
     * The composed seat object they declare:
     * <b>identity</b> = {@code seatToken} (per-seat opaque token minted with the match
     * descriptor; blank = unasserted, perimeter-trust v0), <b>role</b> =
     * {@code seatRole}, <b>mount</b> = {@code clientOwnedShipId} — the seat's
     * <em>current hull</em>, mutable post-admission by a future server-authoritative
     * RESEAT event, never the seat's identity ("the seat outlives the hull").
     * {@code contentFingerprint} is the universe identity ("are we running the same
     * world": build + mod set + schema + critical knobs; blank = unasserted) the
     * drift tripwire mints and checks warn-grade.
     */
    public record ClientHandshake(
            String admissionRequestMessageId,
            int admissionRequestMessageTypeCode,
            int admissionRequestSchemaVersion,
            String messageId,
            int wireTypeCode,
            int schemaVersion,
            String streamId,
            int streamTypeCode,
            int streamVersion,
            int expectedFrames,
            NetworkCapabilityManifest capabilityManifest,
            String clientAuthorityId,
            long clientOwnedShipId,
            String seatToken,
            SeatRole seatRole,
            String contentFingerprint) {

        public ClientHandshake {
            if (admissionRequestMessageId == null || admissionRequestMessageId.isBlank()) {
                throw new IllegalArgumentException("admissionRequestMessageId must be non-blank");
            }
            if (admissionRequestSchemaVersion <= 0) {
                throw new IllegalArgumentException(
                        "admissionRequestSchemaVersion must be > 0; got " + admissionRequestSchemaVersion);
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            capabilityManifest = Objects.requireNonNull(capabilityManifest, "capabilityManifest");
            if (clientAuthorityId == null || clientAuthorityId.isBlank()) {
                throw new IllegalArgumentException("clientAuthorityId must be non-blank");
            }
            if (clientAuthorityId.getBytes(StandardCharsets.UTF_8).length > CLIENT_AUTHORITY_ID_BYTES) {
                throw new IllegalArgumentException("clientAuthorityId exceeds "
                        + CLIENT_AUTHORITY_ID_BYTES + " bytes: " + clientAuthorityId);
            }
            if (clientOwnedShipId <= 0L) {
                throw new IllegalArgumentException(
                        "clientOwnedShipId must be > 0; got " + clientOwnedShipId);
            }
            Objects.requireNonNull(seatToken, "seatToken");
            if (seatToken.getBytes(StandardCharsets.UTF_8).length > SEAT_TOKEN_BYTES) {
                throw new IllegalArgumentException(
                        "seatToken exceeds " + SEAT_TOKEN_BYTES + " UTF-8 bytes");
            }
            Objects.requireNonNull(seatRole, "seatRole");
            Objects.requireNonNull(contentFingerprint, "contentFingerprint");
            if (contentFingerprint.getBytes(StandardCharsets.UTF_8).length > CONTENT_FINGERPRINT_BYTES) {
                throw new IllegalArgumentException(
                        "contentFingerprint exceeds " + CONTENT_FINGERPRINT_BYTES + " UTF-8 bytes");
            }
        }

        public ClientAuthorityIdentity authorityIdentity() {
            return new ClientAuthorityIdentity(clientAuthorityId, clientOwnedShipId);
        }
    }

    public record ServerAdmissionAck(
            boolean accepted,
            int ackMessageTypeCode,
            int ackSchemaVersion,
            String messageId,
            int wireTypeCode,
            int schemaVersion,
            String streamId,
            int streamTypeCode,
            int streamVersion,
            int expectedFrames,
            NetworkCapabilityManifest capabilityManifest) {

        public static ServerAdmissionAck fromAdmission(SnapshotClientAdmissionGate.Admission admission) {
            Objects.requireNonNull(admission, "admission");
            return new ServerAdmissionAck(
                    admission.accepted(),
                    ADMISSION_ACK_MESSAGE_TYPE_CODE,
                    Short.toUnsignedInt(ADMISSION_ACK_SCHEMA_VERSION),
                    admission.messageId(),
                    admission.wireTypeCode(),
                    admission.schemaVersion(),
                    admission.streamId(),
                    admission.streamTypeCode(),
                    admission.streamVersion(),
                    admission.expectedFrames(),
                    admission.requiredCapabilityManifest());
        }

        public static ServerAdmissionAck accepted(SnapshotClientAdmissionGate.Admission admission) {
            ServerAdmissionAck admissionAck = fromAdmission(admission);
            if (!admissionAck.accepted()) {
                throw new IllegalArgumentException("cannot acknowledge rejected client admission");
            }
            return admissionAck;
        }

        public ServerAdmissionAck {
            if (ackSchemaVersion <= 0) {
                throw new IllegalArgumentException("ackSchemaVersion must be > 0; got " + ackSchemaVersion);
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            capabilityManifest = Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        }
    }

    public record ServerTransportSessionOpened(
            int sessionMessageTypeCode,
            int sessionSchemaVersion,
            String messageId,
            int wireTypeCode,
            int schemaVersion,
            String streamId,
            int streamTypeCode,
            int streamVersion,
            boolean offLoopback,
            long socketHostCrc32,
            int socketPort,
            NetworkCapabilityManifest capabilityManifest) {

        public static ServerTransportSessionOpened opened(
                ClientHandshake handshake,
                String socketHost,
                int socketPort) throws IOException {
            Objects.requireNonNull(handshake, "handshake");
            return new ServerTransportSessionOpened(
                    TRANSPORT_SESSION_OPENED_MESSAGE_TYPE_CODE,
                    Short.toUnsignedInt(TRANSPORT_SESSION_OPENED_SCHEMA_VERSION),
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    HeadlessSnapshotControlWire.isOffLoopbackHost(socketHost),
                    HeadlessSnapshotControlWire.socketHostCrc32(socketHost),
                    socketPort,
                    handshake.capabilityManifest());
        }

        public ServerTransportSessionOpened {
            if (sessionSchemaVersion <= 0) {
                throw new IllegalArgumentException(
                        "sessionSchemaVersion must be > 0; got " + sessionSchemaVersion);
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
            if (socketHostCrc32 < 0L || socketHostCrc32 > 0xffff_ffffL) {
                throw new IllegalArgumentException("socketHostCrc32 must be an unsigned 32-bit value");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            capabilityManifest = Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        }
    }

    public record ServerSessionWindowCursor(
            int cursorMessageTypeCode,
            int cursorSchemaVersion,
            String messageId,
            int wireTypeCode,
            int schemaVersion,
            String streamId,
            int streamTypeCode,
            int streamVersion,
            long sessionId,
            int windowOrdinal,
            long previousResumeCursor,
            long nextResumeCursor,
            int expectedFrames,
            NetworkCapabilityManifest capabilityManifest) {

        public static ServerSessionWindowCursor cursor(
                ClientHandshake handshake,
                long sessionId,
                int windowOrdinal,
                long previousResumeCursor,
                int expectedFrames) {
            Objects.requireNonNull(handshake, "handshake");
            return new ServerSessionWindowCursor(
                    SESSION_WINDOW_CURSOR_MESSAGE_TYPE_CODE,
                    Short.toUnsignedInt(SESSION_WINDOW_CURSOR_SCHEMA_VERSION),
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    sessionId,
                    windowOrdinal,
                    previousResumeCursor,
                    Math.addExact(previousResumeCursor, expectedFrames),
                    expectedFrames,
                    handshake.capabilityManifest());
        }

        public ServerSessionWindowCursor {
            if (cursorSchemaVersion <= 0) {
                throw new IllegalArgumentException(
                        "cursorSchemaVersion must be > 0; got " + cursorSchemaVersion);
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
            if (sessionId <= 0L) {
                throw new IllegalArgumentException("sessionId must be > 0; got " + sessionId);
            }
            if (windowOrdinal < 0) {
                throw new IllegalArgumentException("windowOrdinal must be >= 0; got " + windowOrdinal);
            }
            if (previousResumeCursor < 0L) {
                throw new IllegalArgumentException(
                        "previousResumeCursor must be >= 0; got " + previousResumeCursor);
            }
            if (nextResumeCursor < previousResumeCursor) {
                throw new IllegalArgumentException("nextResumeCursor must be >= previousResumeCursor");
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (nextResumeCursor != previousResumeCursor + expectedFrames) {
                throw new IllegalArgumentException("nextResumeCursor must advance by expectedFrames");
            }
            capabilityManifest = Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        }
    }

    public record ServerInitialSync(
            int initialSyncMessageTypeCode,
            int initialSyncSchemaVersion,
            String messageId,
            int wireTypeCode,
            int schemaVersion,
            String streamId,
            int streamTypeCode,
            int streamVersion,
            long snapshotSequence,
            long startedTick,
            long completedTick,
            long stateHash,
            int entityCount,
            int shipCount,
            int shipNetworkIdCount,
            long firstShipNetworkId,
            long lastShipNetworkId,
            boolean stableShipNetworkIdentityRange,
            NetworkCapabilityManifest capabilityManifest) {

        public static ServerInitialSync fromSnapshot(
                ClientHandshake handshake,
                long snapshotSequence,
                OfflineCombatStepResult result) {
            Objects.requireNonNull(handshake, "handshake");
            Objects.requireNonNull(result, "result");
            return new ServerInitialSync(
                    INITIAL_SYNC_MESSAGE_TYPE_CODE,
                    Short.toUnsignedInt(INITIAL_SYNC_SCHEMA_VERSION),
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    snapshotSequence,
                    result.startedTick(),
                    result.completedTick(),
                    result.stateHash(),
                    result.entityCount(),
                    result.shipCount(),
                    result.shipNetworkIdCount(),
                    result.firstShipNetworkId(),
                    result.lastShipNetworkId(),
                    result.stableShipNetworkIdentityRange(),
                    handshake.capabilityManifest());
        }

        public ServerInitialSync {
            if (initialSyncSchemaVersion <= 0) {
                throw new IllegalArgumentException(
                        "initialSyncSchemaVersion must be > 0; got " + initialSyncSchemaVersion);
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
            if (snapshotSequence < 0L || snapshotSequence > Integer.toUnsignedLong(-1)) {
                throw new IllegalArgumentException("snapshotSequence must fit unsigned 32-bit");
            }
            if (startedTick < 0L) {
                throw new IllegalArgumentException("startedTick must be >= 0; got " + startedTick);
            }
            if (completedTick < startedTick) {
                throw new IllegalArgumentException("completedTick must be >= startedTick");
            }
            if (entityCount < 0 || shipCount < 0 || shipNetworkIdCount < 0) {
                throw new IllegalArgumentException("initial sync counts must be >= 0");
            }
            if (shipNetworkIdCount > 0 && (firstShipNetworkId <= 0L || lastShipNetworkId < firstShipNetworkId)) {
                throw new IllegalArgumentException("initial sync ship id range is invalid");
            }
            capabilityManifest = Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        }
    }

    public record ServerStreamCompletion(
            int completionMessageTypeCode,
            int completionSchemaVersion,
            String messageId,
            int wireTypeCode,
            int schemaVersion,
            String streamId,
            int streamTypeCode,
            int streamVersion,
            int completedFrames,
            NetworkCapabilityManifest capabilityManifest) {

        public static ServerStreamCompletion completed(ClientHandshake handshake) {
            Objects.requireNonNull(handshake, "handshake");
            return new ServerStreamCompletion(
                    STREAM_COMPLETED_MESSAGE_TYPE_CODE,
                    Short.toUnsignedInt(STREAM_COMPLETED_SCHEMA_VERSION),
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    handshake.capabilityManifest());
        }

        public ServerStreamCompletion {
            if (completionSchemaVersion <= 0) {
                throw new IllegalArgumentException(
                        "completionSchemaVersion must be > 0; got " + completionSchemaVersion);
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
            if (completedFrames < 0) {
                throw new IllegalArgumentException("completedFrames must be >= 0; got " + completedFrames);
            }
            capabilityManifest = Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        }
    }

    public record Receipt(
            String socketHost,
            int socketPort,
            Path receiverArtifactPath,
            int expectedFrames,
            long expectedBytes,
            long bytesReceived,
            String decodeSource,
            boolean artifactUsedAsDecodeSource,
            ClientHandshake handshake,
            int admissionAckBytes,
            ServerAdmissionAck admissionAck,
            int transportSessionOpenedBytes,
            ServerTransportSessionOpened transportSessionOpened,
            int movementCommandResultBytes,
            ServerMovementCommandResult movementCommandResult,
            int weaponGroupCommandResultBytes,
            ServerWeaponGroupCommandResult weaponGroupCommandResult,
            int fireCommandResultBytes,
            ServerFireCommandResult fireCommandResult,
            int systemCommandResultBytes,
            ServerSystemCommandResult systemCommandResult,
            int sessionResumeClaimBytes,
            ClientSessionResumeClaim sessionResumeClaim,
            int sessionWindowCursorBytes,
            ServerSessionWindowCursor sessionWindowCursor,
            int initialSyncBytes,
            ServerInitialSync initialSync,
            int streamCompletionBytes,
            ServerStreamCompletion streamCompletion,
            List<OfflineCombatStepWireFrame> frames) {

        public Receipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            decodeSource = Objects.requireNonNull(decodeSource, "decodeSource");
            handshake = Objects.requireNonNull(handshake, "handshake");
            admissionAck = Objects.requireNonNull(admissionAck, "admissionAck");
            transportSessionOpened = Objects.requireNonNull(transportSessionOpened, "transportSessionOpened");
            initialSync = Objects.requireNonNull(initialSync, "initialSync");
            streamCompletion = Objects.requireNonNull(streamCompletion, "streamCompletion");
            frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must not be blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (expectedBytes < 0L) {
                throw new IllegalArgumentException("expectedBytes must be >= 0; got " + expectedBytes);
            }
            if (bytesReceived != expectedBytes) {
                throw new IllegalArgumentException("bytesReceived must equal expectedBytes");
            }
            if (decodeSource.isBlank()) {
                throw new IllegalArgumentException("decodeSource must not be blank");
            }
            if (artifactUsedAsDecodeSource) {
                throw new IllegalArgumentException("headless snapshot client must decode from socket input");
            }
            if (handshake.expectedFrames() != expectedFrames) {
                throw new IllegalArgumentException("handshake expectedFrames must match receipt");
            }
            if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                    .equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("handshake capability manifest must match registry export");
            }
            SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(handshake.streamTypeCode(), handshake.streamVersion());
            if (!streamIdentity.streamId().equals(handshake.streamId())) {
                throw new IllegalArgumentException("handshake stream identity must match registry export");
            }
            if (!SnapshotAdmissionRequestMessage.MESSAGE_ID.equals(handshake.admissionRequestMessageId())
                    || handshake.admissionRequestMessageTypeCode() != ADMISSION_REQUEST_MESSAGE_TYPE_CODE
                    || handshake.admissionRequestSchemaVersion()
                            != Short.toUnsignedInt(ADMISSION_REQUEST_SCHEMA_VERSION)) {
                throw new IllegalArgumentException("handshake admission request must match registered identity");
            }
            if (admissionAckBytes != ADMISSION_ACK_BYTES) {
                throw new IllegalArgumentException("admissionAckBytes must equal ADMISSION_ACK_BYTES");
            }
            if (!admissionAck.accepted()
                    || admissionAck.ackMessageTypeCode() != ADMISSION_ACK_MESSAGE_TYPE_CODE
                    || admissionAck.ackSchemaVersion() != Short.toUnsignedInt(ADMISSION_ACK_SCHEMA_VERSION)
                    || !admissionAck.messageId().equals(handshake.messageId())
                    || admissionAck.wireTypeCode() != handshake.wireTypeCode()
                    || admissionAck.schemaVersion() != handshake.schemaVersion()
                    || !admissionAck.streamId().equals(handshake.streamId())
                    || admissionAck.streamTypeCode() != handshake.streamTypeCode()
                    || admissionAck.streamVersion() != handshake.streamVersion()
                    || admissionAck.expectedFrames() != expectedFrames
                    || !admissionAck.capabilityManifest().equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("admission acknowledgement must match handshake");
            }
            if (transportSessionOpenedBytes != TRANSPORT_SESSION_OPENED_BYTES) {
                throw new IllegalArgumentException(
                        "transportSessionOpenedBytes must equal TRANSPORT_SESSION_OPENED_BYTES");
            }
            if (transportSessionOpened.sessionMessageTypeCode() != TRANSPORT_SESSION_OPENED_MESSAGE_TYPE_CODE
                    || transportSessionOpened.sessionSchemaVersion()
                            != Short.toUnsignedInt(TRANSPORT_SESSION_OPENED_SCHEMA_VERSION)
                    || !transportSessionOpened.messageId().equals(handshake.messageId())
                    || transportSessionOpened.wireTypeCode() != handshake.wireTypeCode()
                    || transportSessionOpened.schemaVersion() != handshake.schemaVersion()
                    || !transportSessionOpened.streamId().equals(handshake.streamId())
                    || transportSessionOpened.streamTypeCode() != handshake.streamTypeCode()
                    || transportSessionOpened.streamVersion() != handshake.streamVersion()
                    || !transportSessionOpened.capabilityManifest().equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("transport session opened must match handshake");
            }
            if (movementCommandResult == null) {
                if (movementCommandResultBytes != 0) {
                    throw new IllegalArgumentException("movementCommandResultBytes must be zero when absent");
                }
            } else {
                if (movementCommandResultBytes != MOVEMENT_COMMAND_RESULT_BYTES) {
                    throw new IllegalArgumentException(
                            "movementCommandResultBytes must equal MOVEMENT_COMMAND_RESULT_BYTES");
                }
                if (movementCommandResult.messageTypeCode() != MOVEMENT_COMMAND_RESULT_MESSAGE_TYPE_CODE
                        || movementCommandResult.schemaVersion()
                                != Short.toUnsignedInt(MOVEMENT_COMMAND_RESULT_SCHEMA_VERSION)) {
                    throw new IllegalArgumentException("movement command result must match registered identity");
                }
            }
            if (weaponGroupCommandResult == null) {
                if (weaponGroupCommandResultBytes != 0) {
                    throw new IllegalArgumentException("weaponGroupCommandResultBytes must be zero when absent");
                }
            } else {
                if (weaponGroupCommandResultBytes != WEAPON_GROUP_COMMAND_RESULT_BYTES) {
                    throw new IllegalArgumentException(
                            "weaponGroupCommandResultBytes must equal WEAPON_GROUP_COMMAND_RESULT_BYTES");
                }
                if (weaponGroupCommandResult.messageTypeCode() != WEAPON_GROUP_COMMAND_RESULT_MESSAGE_TYPE_CODE
                        || weaponGroupCommandResult.schemaVersion()
                                != Short.toUnsignedInt(WEAPON_GROUP_COMMAND_RESULT_SCHEMA_VERSION)) {
                    throw new IllegalArgumentException("weapon-group command result must match registered identity");
                }
            }
            if (fireCommandResult == null) {
                if (fireCommandResultBytes != 0) {
                    throw new IllegalArgumentException("fireCommandResultBytes must be zero when absent");
                }
            } else {
                if (fireCommandResultBytes != FIRE_COMMAND_RESULT_BYTES) {
                    throw new IllegalArgumentException(
                            "fireCommandResultBytes must equal FIRE_COMMAND_RESULT_BYTES");
                }
                if (fireCommandResult.messageTypeCode() != FIRE_COMMAND_RESULT_MESSAGE_TYPE_CODE
                        || fireCommandResult.schemaVersion()
                                != Short.toUnsignedInt(FIRE_COMMAND_RESULT_SCHEMA_VERSION)) {
                    throw new IllegalArgumentException("fire command result must match registered identity");
                }
            }
            if (systemCommandResult == null) {
                if (systemCommandResultBytes != 0) {
                    throw new IllegalArgumentException("systemCommandResultBytes must be zero when absent");
                }
            } else {
                if (systemCommandResultBytes != SYSTEM_COMMAND_RESULT_BYTES) {
                    throw new IllegalArgumentException(
                            "systemCommandResultBytes must equal SYSTEM_COMMAND_RESULT_BYTES");
                }
                if (systemCommandResult.messageTypeCode() != SYSTEM_COMMAND_RESULT_MESSAGE_TYPE_CODE
                        || systemCommandResult.schemaVersion()
                                != Short.toUnsignedInt(SYSTEM_COMMAND_RESULT_SCHEMA_VERSION)) {
                    throw new IllegalArgumentException("system command result must match registered identity");
                }
            }
            if (sessionResumeClaim == null) {
                if (sessionResumeClaimBytes != 0) {
                    throw new IllegalArgumentException("sessionResumeClaimBytes must be zero when absent");
                }
            } else {
                if (sessionResumeClaimBytes != SESSION_RESUME_CLAIM_BYTES) {
                    throw new IllegalArgumentException(
                            "sessionResumeClaimBytes must equal SESSION_RESUME_CLAIM_BYTES");
                }
                if (sessionResumeClaim.claimMessageTypeCode() != SESSION_RESUME_CLAIM_MESSAGE_TYPE_CODE
                        || sessionResumeClaim.claimSchemaVersion()
                                != Short.toUnsignedInt(SESSION_RESUME_CLAIM_SCHEMA_VERSION)
                        || !sessionResumeClaim.messageId().equals(handshake.messageId())
                        || sessionResumeClaim.wireTypeCode() != handshake.wireTypeCode()
                        || sessionResumeClaim.schemaVersion() != handshake.schemaVersion()
                        || !sessionResumeClaim.streamId().equals(handshake.streamId())
                        || sessionResumeClaim.streamTypeCode() != handshake.streamTypeCode()
                        || sessionResumeClaim.streamVersion() != handshake.streamVersion()
                        || sessionResumeClaim.expectedFrames() != expectedFrames
                        || !sessionResumeClaim.capabilityManifest().equals(handshake.capabilityManifest())) {
                    throw new IllegalArgumentException("session resume claim must match handshake");
                }
            }
            if (sessionWindowCursor == null) {
                if (sessionWindowCursorBytes != 0) {
                    throw new IllegalArgumentException("sessionWindowCursorBytes must be zero when absent");
                }
            } else {
                if (sessionWindowCursorBytes != SESSION_WINDOW_CURSOR_BYTES) {
                    throw new IllegalArgumentException(
                            "sessionWindowCursorBytes must equal SESSION_WINDOW_CURSOR_BYTES");
                }
                if (sessionWindowCursor.cursorMessageTypeCode() != SESSION_WINDOW_CURSOR_MESSAGE_TYPE_CODE
                        || sessionWindowCursor.cursorSchemaVersion()
                                != Short.toUnsignedInt(SESSION_WINDOW_CURSOR_SCHEMA_VERSION)
                        || !sessionWindowCursor.messageId().equals(handshake.messageId())
                        || sessionWindowCursor.wireTypeCode() != handshake.wireTypeCode()
                        || sessionWindowCursor.schemaVersion() != handshake.schemaVersion()
                        || !sessionWindowCursor.streamId().equals(handshake.streamId())
                        || sessionWindowCursor.streamTypeCode() != handshake.streamTypeCode()
                        || sessionWindowCursor.streamVersion() != handshake.streamVersion()
                        || sessionWindowCursor.expectedFrames() != expectedFrames
                        || !sessionWindowCursor.capabilityManifest().equals(handshake.capabilityManifest())) {
                    throw new IllegalArgumentException("session window cursor must match handshake");
                }
            }
            if (initialSyncBytes != INITIAL_SYNC_BYTES) {
                throw new IllegalArgumentException("initialSyncBytes must equal INITIAL_SYNC_BYTES");
            }
            if (initialSync.initialSyncMessageTypeCode() != INITIAL_SYNC_MESSAGE_TYPE_CODE
                    || initialSync.initialSyncSchemaVersion()
                            != Short.toUnsignedInt(INITIAL_SYNC_SCHEMA_VERSION)
                    || !initialSync.messageId().equals(handshake.messageId())
                    || initialSync.wireTypeCode() != handshake.wireTypeCode()
                    || initialSync.schemaVersion() != handshake.schemaVersion()
                    || !initialSync.streamId().equals(handshake.streamId())
                    || initialSync.streamTypeCode() != handshake.streamTypeCode()
                    || initialSync.streamVersion() != handshake.streamVersion()
                    || !initialSync.capabilityManifest().equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("initial sync must match handshake");
            }
            if (streamCompletionBytes != STREAM_COMPLETED_BYTES) {
                throw new IllegalArgumentException("streamCompletionBytes must equal STREAM_COMPLETED_BYTES");
            }
            if (streamCompletion.completionMessageTypeCode() != STREAM_COMPLETED_MESSAGE_TYPE_CODE
                    || streamCompletion.completionSchemaVersion()
                            != Short.toUnsignedInt(STREAM_COMPLETED_SCHEMA_VERSION)
                    || !streamCompletion.messageId().equals(handshake.messageId())
                    || streamCompletion.wireTypeCode() != handshake.wireTypeCode()
                    || streamCompletion.schemaVersion() != handshake.schemaVersion()
                    || !streamCompletion.streamId().equals(handshake.streamId())
                    || streamCompletion.streamTypeCode() != handshake.streamTypeCode()
                    || streamCompletion.streamVersion() != handshake.streamVersion()
                    || streamCompletion.completedFrames() != expectedFrames
                    || !streamCompletion.capabilityManifest().equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("stream completion must match handshake");
            }
            if (frames.size() != expectedFrames) {
                throw new IllegalArgumentException("frame count must equal expectedFrames");
            }
            if (!frames.isEmpty()) {
                HeadlessSnapshotControlWire.requireInitialSyncMatchesFirstFrame(initialSync, frames.get(0));
            }
            HeadlessSnapshotControlWire.requireStreamIdentityMatchesFrames(handshake, expectedFrames, frames);
        }
    }

    public record PersistentWindowReceipt(
            int windowIndex,
            Path receiverArtifactPath,
            int expectedFrames,
            long expectedBytes,
            long bytesReceived,
            int sessionWindowCursorBytes,
            ServerSessionWindowCursor sessionWindowCursor,
            int initialSyncBytes,
            ServerInitialSync initialSync,
            int streamCompletionBytes,
            ServerStreamCompletion streamCompletion,
            List<OfflineCombatStepWireFrame> frames) {

        public PersistentWindowReceipt {
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            sessionWindowCursor = Objects.requireNonNull(sessionWindowCursor, "sessionWindowCursor");
            initialSync = Objects.requireNonNull(initialSync, "initialSync");
            streamCompletion = Objects.requireNonNull(streamCompletion, "streamCompletion");
            frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
            if (windowIndex < 0) {
                throw new IllegalArgumentException("windowIndex must be >= 0; got " + windowIndex);
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (expectedBytes < 0L) {
                throw new IllegalArgumentException("expectedBytes must be >= 0; got " + expectedBytes);
            }
            if (bytesReceived != expectedBytes) {
                throw new IllegalArgumentException("bytesReceived must equal expectedBytes");
            }
            if (sessionWindowCursorBytes != SESSION_WINDOW_CURSOR_BYTES) {
                throw new IllegalArgumentException(
                        "sessionWindowCursorBytes must equal SESSION_WINDOW_CURSOR_BYTES");
            }
            if (initialSyncBytes != INITIAL_SYNC_BYTES) {
                throw new IllegalArgumentException("initialSyncBytes must equal INITIAL_SYNC_BYTES");
            }
            if (streamCompletionBytes != STREAM_COMPLETED_BYTES) {
                throw new IllegalArgumentException("streamCompletionBytes must equal STREAM_COMPLETED_BYTES");
            }
            if (frames.size() != expectedFrames) {
                throw new IllegalArgumentException("frame count must equal expectedFrames");
            }
        }

        public boolean stateChanges() {
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
    }

    public record PersistentReceipt(
            String socketHost,
            int socketPort,
            int expectedFramesPerWindow,
            String decodeSource,
            boolean artifactUsedAsDecodeSource,
            ClientHandshake handshake,
            int admissionAckBytes,
            ServerAdmissionAck admissionAck,
            int transportSessionOpenedBytes,
            ServerTransportSessionOpened transportSessionOpened,
            int movementCommandResultBytes,
            ServerMovementCommandResult movementCommandResult,
            int weaponGroupCommandResultBytes,
            ServerWeaponGroupCommandResult weaponGroupCommandResult,
            int fireCommandResultBytes,
            ServerFireCommandResult fireCommandResult,
            int systemCommandResultBytes,
            ServerSystemCommandResult systemCommandResult,
            List<PersistentWindowReceipt> windows) {

        public PersistentReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            decodeSource = Objects.requireNonNull(decodeSource, "decodeSource");
            handshake = Objects.requireNonNull(handshake, "handshake");
            admissionAck = Objects.requireNonNull(admissionAck, "admissionAck");
            transportSessionOpened = Objects.requireNonNull(transportSessionOpened, "transportSessionOpened");
            windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must not be blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (expectedFramesPerWindow < 0) {
                throw new IllegalArgumentException(
                        "expectedFramesPerWindow must be >= 0; got " + expectedFramesPerWindow);
            }
            if (decodeSource.isBlank()) {
                throw new IllegalArgumentException("decodeSource must not be blank");
            }
            if (artifactUsedAsDecodeSource) {
                throw new IllegalArgumentException("persistent receipt must decode from socket input");
            }
            if (windows.size() < 2) {
                throw new IllegalArgumentException("persistent receipt requires at least two stream windows");
            }
            if (handshake.expectedFrames() != expectedFramesPerWindow) {
                throw new IllegalArgumentException("handshake expectedFrames must match window frame count");
            }
            if (!NetworkMessageRegistry.authoritativeCombat().capabilityManifest()
                    .equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("handshake capability manifest must match registry export");
            }
            SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(handshake.streamTypeCode(), handshake.streamVersion());
            if (!streamIdentity.streamId().equals(handshake.streamId())) {
                throw new IllegalArgumentException("handshake stream identity must match registry export");
            }
            if (admissionAckBytes != ADMISSION_ACK_BYTES || !admissionAck.accepted()
                    || admissionAck.expectedFrames() != expectedFramesPerWindow
                    || !admissionAck.messageId().equals(handshake.messageId())
                    || admissionAck.wireTypeCode() != handshake.wireTypeCode()
                    || admissionAck.schemaVersion() != handshake.schemaVersion()
                    || !admissionAck.streamId().equals(handshake.streamId())
                    || admissionAck.streamTypeCode() != handshake.streamTypeCode()
                    || admissionAck.streamVersion() != handshake.streamVersion()
                    || !admissionAck.capabilityManifest().equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("persistent admission acknowledgement must match handshake");
            }
            if (transportSessionOpenedBytes != TRANSPORT_SESSION_OPENED_BYTES
                    || transportSessionOpened.sessionMessageTypeCode() != TRANSPORT_SESSION_OPENED_MESSAGE_TYPE_CODE
                    || transportSessionOpened.sessionSchemaVersion()
                            != Short.toUnsignedInt(TRANSPORT_SESSION_OPENED_SCHEMA_VERSION)
                    || !transportSessionOpened.messageId().equals(handshake.messageId())
                    || transportSessionOpened.wireTypeCode() != handshake.wireTypeCode()
                    || transportSessionOpened.schemaVersion() != handshake.schemaVersion()
                    || !transportSessionOpened.streamId().equals(handshake.streamId())
                    || transportSessionOpened.streamTypeCode() != handshake.streamTypeCode()
                    || transportSessionOpened.streamVersion() != handshake.streamVersion()
                    || !transportSessionOpened.capabilityManifest().equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("persistent transport session opened must match handshake");
            }
            if (movementCommandResult == null) {
                if (movementCommandResultBytes != 0) {
                    throw new IllegalArgumentException("movementCommandResultBytes must be zero when absent");
                }
            } else if (movementCommandResultBytes != MOVEMENT_COMMAND_RESULT_BYTES
                    || movementCommandResult.messageTypeCode() != MOVEMENT_COMMAND_RESULT_MESSAGE_TYPE_CODE
                    || movementCommandResult.schemaVersion()
                            != Short.toUnsignedInt(MOVEMENT_COMMAND_RESULT_SCHEMA_VERSION)) {
                throw new IllegalArgumentException("movement command result must match registered identity");
            }
            if (weaponGroupCommandResult == null) {
                if (weaponGroupCommandResultBytes != 0) {
                    throw new IllegalArgumentException("weaponGroupCommandResultBytes must be zero when absent");
                }
            } else if (weaponGroupCommandResultBytes != WEAPON_GROUP_COMMAND_RESULT_BYTES
                    || weaponGroupCommandResult.messageTypeCode() != WEAPON_GROUP_COMMAND_RESULT_MESSAGE_TYPE_CODE
                    || weaponGroupCommandResult.schemaVersion()
                            != Short.toUnsignedInt(WEAPON_GROUP_COMMAND_RESULT_SCHEMA_VERSION)) {
                throw new IllegalArgumentException("weapon-group command result must match registered identity");
            }
            if (fireCommandResult == null) {
                if (fireCommandResultBytes != 0) {
                    throw new IllegalArgumentException("fireCommandResultBytes must be zero when absent");
                }
            } else if (fireCommandResultBytes != FIRE_COMMAND_RESULT_BYTES
                    || fireCommandResult.messageTypeCode() != FIRE_COMMAND_RESULT_MESSAGE_TYPE_CODE
                    || fireCommandResult.schemaVersion()
                            != Short.toUnsignedInt(FIRE_COMMAND_RESULT_SCHEMA_VERSION)) {
                throw new IllegalArgumentException("fire command result must match registered identity");
            }
            if (systemCommandResult == null) {
                if (systemCommandResultBytes != 0) {
                    throw new IllegalArgumentException("systemCommandResultBytes must be zero when absent");
                }
            } else if (systemCommandResultBytes != SYSTEM_COMMAND_RESULT_BYTES
                    || systemCommandResult.messageTypeCode() != SYSTEM_COMMAND_RESULT_MESSAGE_TYPE_CODE
                    || systemCommandResult.schemaVersion()
                            != Short.toUnsignedInt(SYSTEM_COMMAND_RESULT_SCHEMA_VERSION)) {
                throw new IllegalArgumentException("system command result must match registered identity");
            }
            for (int i = 0; i < windows.size(); i++) {
                PersistentWindowReceipt window = windows.get(i);
                long expectedPreviousCursor = Math.multiplyExact((long) i, (long) expectedFramesPerWindow);
                if (window.windowIndex() != i) {
                    throw new IllegalArgumentException("persistent window index must match list order");
                }
                if (window.expectedFrames() != expectedFramesPerWindow) {
                    throw new IllegalArgumentException("persistent window frame count must match handshake");
                }
                if (window.sessionWindowCursor().cursorMessageTypeCode() != SESSION_WINDOW_CURSOR_MESSAGE_TYPE_CODE
                        || window.sessionWindowCursor().cursorSchemaVersion()
                                != Short.toUnsignedInt(SESSION_WINDOW_CURSOR_SCHEMA_VERSION)
                        || !window.sessionWindowCursor().messageId().equals(handshake.messageId())
                        || window.sessionWindowCursor().wireTypeCode() != handshake.wireTypeCode()
                        || window.sessionWindowCursor().schemaVersion() != handshake.schemaVersion()
                        || !window.sessionWindowCursor().streamId().equals(handshake.streamId())
                        || window.sessionWindowCursor().streamTypeCode() != handshake.streamTypeCode()
                        || window.sessionWindowCursor().streamVersion() != handshake.streamVersion()
                        || window.sessionWindowCursor().windowOrdinal() != i
                        || window.sessionWindowCursor().previousResumeCursor() != expectedPreviousCursor
                        || window.sessionWindowCursor().nextResumeCursor()
                                != expectedPreviousCursor + expectedFramesPerWindow
                        || window.sessionWindowCursor().expectedFrames() != expectedFramesPerWindow
                        || !window.sessionWindowCursor().capabilityManifest().equals(handshake.capabilityManifest())) {
                    throw new IllegalArgumentException("persistent session window cursor must match handshake");
                }
                if (i > 0 && window.sessionWindowCursor().sessionId()
                        != windows.get(0).sessionWindowCursor().sessionId()) {
                    throw new IllegalArgumentException("persistent session window cursors must share session id");
                }
                if (window.initialSync().initialSyncMessageTypeCode() != INITIAL_SYNC_MESSAGE_TYPE_CODE
                        || window.initialSync().initialSyncSchemaVersion()
                                != Short.toUnsignedInt(INITIAL_SYNC_SCHEMA_VERSION)
                        || !window.initialSync().messageId().equals(handshake.messageId())
                        || window.initialSync().wireTypeCode() != handshake.wireTypeCode()
                        || window.initialSync().schemaVersion() != handshake.schemaVersion()
                        || !window.initialSync().streamId().equals(handshake.streamId())
                        || window.initialSync().streamTypeCode() != handshake.streamTypeCode()
                        || window.initialSync().streamVersion() != handshake.streamVersion()
                        || !window.initialSync().capabilityManifest().equals(handshake.capabilityManifest())) {
                    throw new IllegalArgumentException("persistent initial sync must match handshake");
                }
                if (window.streamCompletion().completionMessageTypeCode() != STREAM_COMPLETED_MESSAGE_TYPE_CODE
                        || window.streamCompletion().completionSchemaVersion()
                                != Short.toUnsignedInt(STREAM_COMPLETED_SCHEMA_VERSION)
                        || !window.streamCompletion().messageId().equals(handshake.messageId())
                        || window.streamCompletion().wireTypeCode() != handshake.wireTypeCode()
                        || window.streamCompletion().schemaVersion() != handshake.schemaVersion()
                        || !window.streamCompletion().streamId().equals(handshake.streamId())
                        || window.streamCompletion().streamTypeCode() != handshake.streamTypeCode()
                        || window.streamCompletion().streamVersion() != handshake.streamVersion()
                        || window.streamCompletion().completedFrames() != expectedFramesPerWindow
                        || !window.streamCompletion().capabilityManifest().equals(handshake.capabilityManifest())) {
                    throw new IllegalArgumentException("persistent stream completion must match handshake");
                }
                if (!window.frames().isEmpty()) {
                    HeadlessSnapshotControlWire.requireInitialSyncMatchesFirstFrame(window.initialSync(), window.frames().get(0));
                }
                HeadlessSnapshotControlWire.requireStreamIdentityMatchesFrames(handshake, expectedFramesPerWindow, window.frames());
            }
        }

        public long expectedBytesPerWindow() {
            return Math.multiplyExact((long) expectedFramesPerWindow, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        }

        public int totalFramesRead() {
            return Math.multiplyExact(expectedFramesPerWindow, windows.size());
        }

        public long totalBytesReceived() {
            return Math.multiplyExact(expectedBytesPerWindow(), (long) windows.size());
        }
    }

    public record AdmissionReceipt(
            String socketHost,
            int socketPort,
            int expectedFrames,
            ClientHandshake handshake,
            int admissionAckBytes,
            ServerAdmissionAck admissionAck) {

        public AdmissionReceipt {
            socketHost = Objects.requireNonNull(socketHost, "socketHost");
            handshake = Objects.requireNonNull(handshake, "handshake");
            admissionAck = Objects.requireNonNull(admissionAck, "admissionAck");
            if (socketHost.isBlank()) {
                throw new IllegalArgumentException("socketHost must not be blank");
            }
            if (socketPort <= 0 || socketPort > 65535) {
                throw new IllegalArgumentException("socketPort out of range: " + socketPort);
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (handshake.expectedFrames() != expectedFrames) {
                throw new IllegalArgumentException("handshake expectedFrames must match receipt");
            }
            SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                    .requireSnapshotStreamType(handshake.streamTypeCode(), handshake.streamVersion());
            if (!streamIdentity.streamId().equals(handshake.streamId())) {
                throw new IllegalArgumentException("handshake stream identity must match registry export");
            }
            if (!SnapshotAdmissionRequestMessage.MESSAGE_ID.equals(handshake.admissionRequestMessageId())
                    || handshake.admissionRequestMessageTypeCode() != ADMISSION_REQUEST_MESSAGE_TYPE_CODE
                    || handshake.admissionRequestSchemaVersion()
                            != Short.toUnsignedInt(ADMISSION_REQUEST_SCHEMA_VERSION)) {
                throw new IllegalArgumentException("handshake admission request must match registered identity");
            }
            if (admissionAckBytes != ADMISSION_ACK_BYTES) {
                throw new IllegalArgumentException("admissionAckBytes must equal ADMISSION_ACK_BYTES");
            }
            if (admissionAck.ackMessageTypeCode() != ADMISSION_ACK_MESSAGE_TYPE_CODE
                    || admissionAck.ackSchemaVersion() != Short.toUnsignedInt(ADMISSION_ACK_SCHEMA_VERSION)
                    || !admissionAck.messageId().equals(handshake.messageId())
                    || admissionAck.wireTypeCode() != handshake.wireTypeCode()
                    || admissionAck.schemaVersion() != handshake.schemaVersion()
                    || !admissionAck.streamId().equals(handshake.streamId())
                    || admissionAck.streamTypeCode() != handshake.streamTypeCode()
                    || admissionAck.streamVersion() != handshake.streamVersion()
                    || admissionAck.expectedFrames() != expectedFrames) {
                throw new IllegalArgumentException("admission acknowledgement must match handshake");
            }
            NetworkCapabilityManifest requiredCapabilityManifest =
                    NetworkMessageRegistry.authoritativeCombat().capabilityManifest();
            if (admissionAck.accepted()
                    && !admissionAck.capabilityManifest().equals(handshake.capabilityManifest())) {
                throw new IllegalArgumentException("accepted admission acknowledgement must match handshake capability");
            }
            if (!admissionAck.accepted()
                    && !admissionAck.capabilityManifest().equals(requiredCapabilityManifest)) {
                throw new IllegalArgumentException("rejected admission acknowledgement must carry required capability");
            }
        }
    }

    public static long createSessionWindowSessionId(ClientHandshake handshake, long serverPid) {
        Objects.requireNonNull(handshake, "handshake");
        if (serverPid <= 0L) {
            throw new IllegalArgumentException("serverPid must be > 0; got " + serverPid);
        }
        CRC32 crc = new CRC32();
        updateCrc32(crc, handshake.streamId());
        updateCrc32(crc, Integer.toString(handshake.streamTypeCode()));
        updateCrc32(crc, Integer.toString(handshake.streamVersion()));
        updateCrc32(crc, Long.toString(serverPid));
        long high = crc.getValue() & 0x7fff_ffffL;
        long low = System.nanoTime() & 0xffff_ffffL;
        long sessionId = (high << 32) | low;
        return sessionId == 0L ? 1L : sessionId;
    }

    private static void updateCrc32(CRC32 crc, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
    }

}
