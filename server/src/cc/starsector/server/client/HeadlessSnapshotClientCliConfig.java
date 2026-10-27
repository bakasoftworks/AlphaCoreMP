package cc.starsector.server.client;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientFireCommand;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientMovementCommand;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientSystemCommand;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.ClientWeaponGroupCommand;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.SessionResumeClaimParameters;
import cc.starsector.server.client.HeadlessSnapshotSocketClient.SeatCredential;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity;
import cc.starsector.server.protocol.NetworkCapabilityManifest;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.SeatRole;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

record HeadlessSnapshotClientCliConfig(
        String host,
        int port,
        Path receiverArtifactPath,
        Path secondReceiverArtifactPath,
        int expectedFrames,
        Path receiptPath,
        SnapshotStreamIdentity streamIdentity,
        NetworkCapabilityManifest capabilityManifest,
        String clientAuthorityId,
        long clientOwnedShipId,
        String seatToken,
        SeatRole seatRole,
        String contentFingerprint,
        boolean admissionOnly,
        int persistentWindows,
        boolean sessionWindowCursor,
        Long movementShipId,
        Long movementSimTick,
        ShipCommandSink.Movement movementIntent,
        Long weaponGroupShipId,
        Long weaponGroupSimTick,
        Integer weaponGroup,
        Long fireShipId,
        Long fireSimTick,
        Boolean fire,
        Long systemShipId,
        Long systemSimTick,
        Integer systemId,
        Long resumeSessionId,
        Integer resumeWindowOrdinal,
        Long resumePreviousCursor,
        Long resumeNextCursor) {

    HeadlessSnapshotClientCliConfig {
        host = Objects.requireNonNull(host, "host");
        receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        receiptPath = Objects.requireNonNull(receiptPath, "receiptPath");
        streamIdentity = Objects.requireNonNull(streamIdentity, "streamIdentity");
        capabilityManifest = Objects.requireNonNull(capabilityManifest, "capabilityManifest");
        if (clientAuthorityId == null || clientAuthorityId.isBlank()) {
            throw new IllegalArgumentException("clientAuthorityId must be non-blank");
        }
        if (clientAuthorityId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > HeadlessSnapshotSocketClient.CLIENT_AUTHORITY_ID_BYTES) {
            throw new IllegalArgumentException("clientAuthorityId exceeds "
                    + HeadlessSnapshotSocketClient.CLIENT_AUTHORITY_ID_BYTES + " bytes: "
                    + clientAuthorityId);
        }
        if (clientOwnedShipId <= 0L) {
            throw new IllegalArgumentException("clientOwnedShipId must be > 0; got " + clientOwnedShipId);
        }
        seatToken = Objects.requireNonNull(seatToken, "seatToken");
        seatRole = Objects.requireNonNull(seatRole, "seatRole");
        contentFingerprint = Objects.requireNonNull(contentFingerprint, "contentFingerprint");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        if (persistentWindows < 1 || persistentWindows > 2) {
            throw new IllegalArgumentException("persistentWindows must be 1 or 2; got " + persistentWindows);
        }
        if (admissionOnly && persistentWindows != 1) {
            throw new IllegalArgumentException("--admission-only true requires --persistent-windows 1");
        }
        if (sessionWindowCursor && persistentWindows != 1) {
            throw new IllegalArgumentException("--session-window-cursor requires --persistent-windows 1");
        }
        if (admissionOnly && sessionWindowCursor) {
            throw new IllegalArgumentException("--admission-only true cannot use --session-window-cursor true");
        }
        if (persistentWindows == 2) {
            secondReceiverArtifactPath =
                    Objects.requireNonNull(secondReceiverArtifactPath, "secondReceiverArtifactPath");
        } else if (secondReceiverArtifactPath != null) {
            throw new IllegalArgumentException("--artifact-window-2 requires --persistent-windows 2");
        }
        int movementOptionCount = 0;
        movementOptionCount += movementShipId == null ? 0 : 1;
        movementOptionCount += movementSimTick == null ? 0 : 1;
        movementOptionCount += movementIntent == null ? 0 : 1;
        if (movementOptionCount != 0 && movementOptionCount != 3) {
            throw new IllegalArgumentException(usage());
        }
        if (admissionOnly && movementOptionCount != 0) {
            throw new IllegalArgumentException("--admission-only true cannot send movement");
        }
        if (movementShipId != null && movementShipId <= 0L) {
            throw new IllegalArgumentException("movement ship id must be > 0; got " + movementShipId);
        }
        if (movementSimTick != null && movementSimTick < 0L) {
            throw new IllegalArgumentException("movement sim tick must be >= 0; got " + movementSimTick);
        }
        int weaponGroupOptionCount = 0;
        weaponGroupOptionCount += weaponGroupShipId == null ? 0 : 1;
        weaponGroupOptionCount += weaponGroupSimTick == null ? 0 : 1;
        weaponGroupOptionCount += weaponGroup == null ? 0 : 1;
        if (weaponGroupOptionCount != 0 && weaponGroupOptionCount != 3) {
            throw new IllegalArgumentException(usage());
        }
        if (admissionOnly && weaponGroupOptionCount != 0) {
            throw new IllegalArgumentException("--admission-only true cannot send weapon-group commands");
        }
        if (weaponGroupShipId != null && weaponGroupShipId <= 0L) {
            throw new IllegalArgumentException("weapon-group ship id must be > 0; got " + weaponGroupShipId);
        }
        if (weaponGroupSimTick != null && weaponGroupSimTick < 0L) {
            throw new IllegalArgumentException("weapon-group sim tick must be >= 0; got " + weaponGroupSimTick);
        }
        if (weaponGroup != null && weaponGroup < 0) {
            throw new IllegalArgumentException("weapon group must be >= 0; got " + weaponGroup);
        }
        int fireOptionCount = 0;
        fireOptionCount += fireShipId == null ? 0 : 1;
        fireOptionCount += fireSimTick == null ? 0 : 1;
        fireOptionCount += fire == null ? 0 : 1;
        if (fireOptionCount != 0 && fireOptionCount != 3) {
            throw new IllegalArgumentException(usage());
        }
        if (admissionOnly && fireOptionCount != 0) {
            throw new IllegalArgumentException("--admission-only true cannot send fire commands");
        }
        if (fireShipId != null && fireShipId <= 0L) {
            throw new IllegalArgumentException("fire ship id must be > 0; got " + fireShipId);
        }
        if (fireSimTick != null && fireSimTick < 0L) {
            throw new IllegalArgumentException("fire sim tick must be >= 0; got " + fireSimTick);
        }
        int systemOptionCount = 0;
        systemOptionCount += systemShipId == null ? 0 : 1;
        systemOptionCount += systemSimTick == null ? 0 : 1;
        systemOptionCount += systemId == null ? 0 : 1;
        if (systemOptionCount != 0 && systemOptionCount != 3) {
            throw new IllegalArgumentException(usage());
        }
        if (admissionOnly && systemOptionCount != 0) {
            throw new IllegalArgumentException("--admission-only true cannot send system commands");
        }
        if (systemShipId != null && systemShipId <= 0L) {
            throw new IllegalArgumentException("system ship id must be > 0; got " + systemShipId);
        }
        if (systemSimTick != null && systemSimTick < 0L) {
            throw new IllegalArgumentException("system sim tick must be >= 0; got " + systemSimTick);
        }
        if (systemId != null && systemId < 0) {
            throw new IllegalArgumentException("system id must be >= 0; got " + systemId);
        }
        int resumeOptionCount = 0;
        resumeOptionCount += resumeSessionId == null ? 0 : 1;
        resumeOptionCount += resumeWindowOrdinal == null ? 0 : 1;
        resumeOptionCount += resumePreviousCursor == null ? 0 : 1;
        resumeOptionCount += resumeNextCursor == null ? 0 : 1;
        if (resumeOptionCount != 0 && resumeOptionCount != 4) {
            throw new IllegalArgumentException(usage());
        }
        if (admissionOnly && resumeOptionCount != 0) {
            throw new IllegalArgumentException("--admission-only true cannot send resume claims");
        }
        if (resumeOptionCount == 4 && !sessionWindowCursor) {
            throw new IllegalArgumentException("--resume-* requires --session-window-cursor true");
        }
        if (resumeSessionId != null && resumeSessionId <= 0L) {
            throw new IllegalArgumentException("resume session id must be > 0; got " + resumeSessionId);
        }
        if (resumeWindowOrdinal != null && resumeWindowOrdinal < 0) {
            throw new IllegalArgumentException(
                    "resume window ordinal must be >= 0; got " + resumeWindowOrdinal);
        }
        if (resumePreviousCursor != null && resumePreviousCursor < 0L) {
            throw new IllegalArgumentException(
                    "resume previous cursor must be >= 0; got " + resumePreviousCursor);
        }
        if (resumePreviousCursor != null
                && resumeNextCursor != null
                && resumeNextCursor < resumePreviousCursor) {
            throw new IllegalArgumentException("resume next cursor must be >= previous cursor");
        }
    }

    ClientMovementCommand movementCommandOrNull() {
        if (movementShipId == null) {
            return null;
        }
        return HeadlessSnapshotSocketClient.movementCommand(movementShipId, movementSimTick, movementIntent);
    }

    ClientFireCommand fireCommandOrNull() {
        if (fireShipId == null) {
            return null;
        }
        return HeadlessSnapshotSocketClient.fireCommand(fireShipId, fireSimTick, fire.booleanValue());
    }

    ClientWeaponGroupCommand weaponGroupCommandOrNull() {
        if (weaponGroupShipId == null) {
            return null;
        }
        return HeadlessSnapshotSocketClient.weaponGroupCommand(weaponGroupShipId, weaponGroupSimTick, weaponGroup);
    }

    ClientSystemCommand systemCommandOrNull() {
        if (systemShipId == null) {
            return null;
        }
        return HeadlessSnapshotSocketClient.systemCommand(systemShipId, systemSimTick, systemId);
    }

    SessionResumeClaimParameters resumeClaimOrNull() {
        if (resumeSessionId == null) {
            return null;
        }
        return new SessionResumeClaimParameters(
                resumeSessionId,
                resumeWindowOrdinal,
                resumePreviousCursor,
                resumeNextCursor);
    }

    ClientAuthorityIdentity authorityIdentity() {
        return new ClientAuthorityIdentity(clientAuthorityId, clientOwnedShipId);
    }

    SeatCredential seatCredential() {
        return new SeatCredential(seatToken, seatRole, contentFingerprint);
    }

    List<Path> receiverArtifactPaths() {
        if (persistentWindows == 1) {
            return List.of(receiverArtifactPath);
        }
        return List.of(receiverArtifactPath, secondReceiverArtifactPath);
    }

    static HeadlessSnapshotClientCliConfig parse(String[] args) {
        Objects.requireNonNull(args, "args");
        String host = null;
        Integer port = null;
        Path artifact = null;
        Path artifactWindow2 = null;
        Integer expectedFrames = null;
        Path receipt = null;
        Integer persistentWindows = 1;
        Boolean admissionOnly = Boolean.FALSE;
        Long movementShipId = null;
        Long movementSimTick = null;
        ShipCommandSink.Movement movementIntent = null;
        Long weaponGroupShipId = null;
        Long weaponGroupSimTick = null;
        Integer weaponGroup = null;
        Long fireShipId = null;
        Long fireSimTick = null;
        Boolean fire = null;
        Long systemShipId = null;
        Long systemSimTick = null;
        Integer systemId = null;
        Boolean sessionWindowCursor = Boolean.FALSE;
        Long resumeSessionId = null;
        Integer resumeWindowOrdinal = null;
        Long resumePreviousCursor = null;
        Long resumeNextCursor = null;
        String streamId = NetworkMessageRegistry.authoritativeCombat()
                .offlineCombatStepSnapshotStreamIdentity()
                .streamId();
        Integer streamVersion = NetworkMessageRegistry.authoritativeCombat()
                .offlineCombatStepSnapshotStreamIdentity()
                .streamVersion();
        NetworkCapabilityManifest defaultCapabilityManifest =
                NetworkMessageRegistry.authoritativeCombat().capabilityManifest();
        Integer capabilityManifestVersion = defaultCapabilityManifest.manifestVersion();
        Integer capabilityEntryCount = defaultCapabilityManifest.entryCount();
        Integer capabilityStreamCount = defaultCapabilityManifest.streamCount();
        Long capabilityFingerprintCrc32 = defaultCapabilityManifest.fingerprintCrc32();
        String clientAuthorityId = HeadlessSnapshotSocketClient.DEFAULT_CLIENT_AUTHORITY_ID;
        Long clientOwnedShipId = Long.valueOf(HeadlessSnapshotSocketClient.DEFAULT_CLIENT_OWNED_SHIP_ID);
        String seatToken = HeadlessSnapshotSocketClient.UNASSERTED_SEAT_TOKEN;
        SeatRole seatRole = SeatRole.PILOT;
        String contentFingerprint = HeadlessSnapshotSocketClient.UNASSERTED_CONTENT_FINGERPRINT;
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException(usage());
        }
        for (int i = 0; i < args.length; i += 2) {
            String key = args[i];
            String value = args[i + 1];
            switch (key) {
                case "--host" -> host = value;
                case "--port" -> port = Integer.valueOf(value);
                case "--artifact" -> artifact = Path.of(value);
                case "--artifact-window-2" -> artifactWindow2 = Path.of(value);
                case "--expected-frames" -> expectedFrames = Integer.valueOf(value);
                case "--receipt" -> receipt = Path.of(value);
                case "--admission-only" -> admissionOnly = Boolean.valueOf(value);
                case "--persistent-windows" -> persistentWindows = Integer.valueOf(value);
                case "--stream-id" -> streamId = value;
                case "--stream-version" -> streamVersion = Integer.valueOf(value);
                case "--capability-manifest-version" -> capabilityManifestVersion = Integer.valueOf(value);
                case "--capability-entry-count" -> capabilityEntryCount = Integer.valueOf(value);
                case "--capability-stream-count" -> capabilityStreamCount = Integer.valueOf(value);
                case "--capability-fingerprint-crc32" -> capabilityFingerprintCrc32 = Long.parseUnsignedLong(value);
                case "--client-authority-id" -> clientAuthorityId = value;
                case "--client-owned-ship-id" -> clientOwnedShipId = Long.valueOf(value);
                case "--seat-token" -> seatToken = value;
                case "--seat-role" -> seatRole = SeatRole.valueOf(value);
                case "--content-fingerprint" -> contentFingerprint = value;
                case "--session-window-cursor" -> sessionWindowCursor = Boolean.valueOf(value);
                case "--movement-ship-id" -> movementShipId = Long.valueOf(value);
                case "--movement-sim-tick" -> movementSimTick = Long.valueOf(value);
                case "--movement-intent" -> movementIntent = ShipCommandSink.Movement.valueOf(value);
                case "--weapon-group-ship-id" -> weaponGroupShipId = Long.valueOf(value);
                case "--weapon-group-sim-tick" -> weaponGroupSimTick = Long.valueOf(value);
                case "--weapon-group" -> weaponGroup = Integer.valueOf(value);
                case "--fire-ship-id" -> fireShipId = Long.valueOf(value);
                case "--fire-sim-tick" -> fireSimTick = Long.valueOf(value);
                case "--fire" -> fire = Boolean.valueOf(value);
                case "--system-ship-id" -> systemShipId = Long.valueOf(value);
                case "--system-sim-tick" -> systemSimTick = Long.valueOf(value);
                case "--system-id" -> systemId = Integer.valueOf(value);
                case "--resume-session-id" -> resumeSessionId = Long.valueOf(value);
                case "--resume-window-ordinal" -> resumeWindowOrdinal = Integer.valueOf(value);
                case "--resume-previous-cursor" -> resumePreviousCursor = Long.valueOf(value);
                case "--resume-next-cursor" -> resumeNextCursor = Long.valueOf(value);
                default -> throw new IllegalArgumentException("unknown argument " + key + "; " + usage());
            }
        }
        if (host == null || port == null || artifact == null || expectedFrames == null || receipt == null) {
            throw new IllegalArgumentException(usage());
        }
        SnapshotStreamIdentity streamIdentity = NetworkMessageRegistry.authoritativeCombat()
                .requireSnapshotStream(streamId, streamVersion);
        NetworkCapabilityManifest capabilityManifest = new NetworkCapabilityManifest(
                capabilityManifestVersion,
                capabilityEntryCount,
                capabilityStreamCount,
                capabilityFingerprintCrc32);
        return new HeadlessSnapshotClientCliConfig(
                host,
                port,
                artifact,
                artifactWindow2,
                expectedFrames,
                receipt,
                streamIdentity,
                capabilityManifest,
                clientAuthorityId,
                clientOwnedShipId.longValue(),
                seatToken,
                seatRole,
                contentFingerprint,
                admissionOnly.booleanValue(),
                persistentWindows,
                sessionWindowCursor.booleanValue(),
                movementShipId,
                movementSimTick,
                movementIntent,
                weaponGroupShipId,
                weaponGroupSimTick,
                weaponGroup,
                fireShipId,
                fireSimTick,
                fire,
                systemShipId,
                systemSimTick,
                systemId,
                resumeSessionId,
                resumeWindowOrdinal,
                resumePreviousCursor,
                resumeNextCursor);
    }

    private static String usage() {
        return "usage: HeadlessSnapshotSocketClient --host <host> --port <port> "
                + "--artifact <receiver.ocsr> --expected-frames <count> --receipt <receipt.properties> "
                + "[--client-authority-id <id> --client-owned-ship-id <ship>] "
                + "[--seat-token <token> --seat-role <PILOT|ADMIRAL|SPECTATOR> --content-fingerprint <fp>] "
                + "[--admission-only true] "
                + "[--persistent-windows 2 --artifact-window-2 <receiver-window-2.ocsr>] "
                + "[--session-window-cursor true] "
                + "[--stream-id <id> --stream-version <version>] "
                + "[--capability-manifest-version <version> --capability-entry-count <count> "
                + "--capability-stream-count <count> --capability-fingerprint-crc32 <crc32>] "
                + "[--movement-ship-id <id> --movement-sim-tick <tick> --movement-intent <intent>] "
                + "[--weapon-group-ship-id <id> --weapon-group-sim-tick <tick> --weapon-group <group>] "
                + "[--fire-ship-id <id> --fire-sim-tick <tick> --fire <true|false>] "
                + "[--system-ship-id <id> --system-sim-tick <tick> --system-id <id>] "
                + "[--resume-session-id <id> --resume-window-ordinal <n> "
                + "--resume-previous-cursor <cursor> --resume-next-cursor <cursor>]";
    }
}
