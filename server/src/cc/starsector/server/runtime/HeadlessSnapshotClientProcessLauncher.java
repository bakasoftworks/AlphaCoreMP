package cc.starsector.server.runtime;

import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity;
import cc.starsector.server.protocol.NetworkCapabilityManifest;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class HeadlessSnapshotClientProcessLauncher {
    private static final long PROCESS_JOIN_TIMEOUT_MILLIS = 15_000L;

    private HeadlessSnapshotClientProcessLauncher() {
    }
    static Process startHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath) throws IOException {
        return startHeadlessClientProcess(
                host,
                port,
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                streamIdentity,
                clientLogPath,
                null,
                null,
                null,
                null);
    }

    static Process startHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand) throws IOException {
        return startHeadlessClientProcess(
                host,
                port,
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                streamIdentity,
                clientLogPath,
                movementCommand,
                null,
                null,
                null);
    }

    static Process startHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand) throws IOException {
        return startHeadlessClientProcess(
                host,
                port,
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                streamIdentity,
                clientLogPath,
                movementCommand,
                null,
                fireCommand,
                null);
    }

    static Process startHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand) throws IOException {
        return startHeadlessClientProcess(
                host,
                port,
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                streamIdentity,
                clientLogPath,
                movementCommand,
                weaponGroupCommand,
                fireCommand,
                systemCommand,
                null,
                HeadlessSnapshotSocketClient.SeatCredential.unasserted());
    }

    static Process startHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            ClientAuthorityIdentity authorityIdentity,
            HeadlessSnapshotSocketClient.SeatCredential seatCredential) throws IOException {
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        seatCredential = seatCredential == null
                ? HeadlessSnapshotSocketClient.SeatCredential.unasserted()
                : seatCredential;
        Path logParent = clientLogPath.getParent();
        if (logParent != null) {
            Files.createDirectories(logParent);
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IOException("java.home is not available for launching headless snapshot client process");
        }
        String javaBinary = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win")
                        ? "java.exe"
                        : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path is not available for launching headless snapshot client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(HeadlessSnapshotSocketClient.class.getName());
        command.add("--host");
        command.add(host);
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--artifact");
        command.add(receiverArtifactPath.toAbsolutePath().normalize().toString());
        command.add("--expected-frames");
        command.add(Integer.toString(expectedFrames));
        command.add("--receipt");
        command.add(clientReceiptPath.toAbsolutePath().normalize().toString());
        command.add("--stream-id");
        command.add(streamIdentity.streamId());
        command.add("--stream-version");
        command.add(Integer.toString(streamIdentity.streamVersion()));
        if (authorityIdentity != null) {
            command.add("--client-authority-id");
            command.add(authorityIdentity.clientId());
            command.add("--client-owned-ship-id");
            command.add(Long.toString(authorityIdentity.ownedShipId()));
        }
        // R6 token-delivery: only an asserted credential adds args, so an unasserted credential
        // (the default) leaves the subprocess command line byte-identical to the pre-R6 launch.
        if (seatCredential.asserted()) {
            command.add("--seat-token");
            command.add(seatCredential.seatToken());
            command.add("--seat-role");
            command.add(seatCredential.seatRole().name());
            command.add("--content-fingerprint");
            command.add(seatCredential.contentFingerprint());
        }
        if (movementCommand != null) {
            command.add("--movement-ship-id");
            command.add(Long.toString(movementCommand.shipId()));
            command.add("--movement-sim-tick");
            command.add(Long.toString(movementCommand.simTick()));
            command.add("--movement-intent");
            command.add(movementCommand.movementIntent().name());
        }
        if (weaponGroupCommand != null) {
            command.add("--weapon-group-ship-id");
            command.add(Long.toString(weaponGroupCommand.shipId()));
            command.add("--weapon-group-sim-tick");
            command.add(Long.toString(weaponGroupCommand.simTick()));
            command.add("--weapon-group");
            command.add(Integer.toString(weaponGroupCommand.group()));
        }
        if (fireCommand != null) {
            command.add("--fire-ship-id");
            command.add(Long.toString(fireCommand.shipId()));
            command.add("--fire-sim-tick");
            command.add(Long.toString(fireCommand.simTick()));
            command.add("--fire");
            command.add(Boolean.toString(fireCommand.fire()));
        }
        if (systemCommand != null) {
            command.add("--system-ship-id");
            command.add(Long.toString(systemCommand.shipId()));
            command.add("--system-sim-tick");
            command.add(Long.toString(systemCommand.simTick()));
            command.add("--system-id");
            command.add(Integer.toString(systemCommand.systemId()));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(clientLogPath.toFile());
        return builder.start();
    }

    static Process startSessionWindowHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        return startSessionWindowHeadlessClientProcess(
                host,
                port,
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                streamIdentity,
                clientLogPath,
                movementCommand,
                null,
                null,
                null,
                resumeClaimParameters);
    }

    static Process startSessionWindowHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        return startSessionWindowHeadlessClientProcess(
                host,
                port,
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                streamIdentity,
                clientLogPath,
                movementCommand,
                null,
                fireCommand,
                null,
                resumeClaimParameters);
    }

    static Process startSessionWindowHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand,
            HeadlessSnapshotSocketClient.SessionResumeClaimParameters resumeClaimParameters) throws IOException {
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Path logParent = clientLogPath.getParent();
        if (logParent != null) {
            Files.createDirectories(logParent);
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IOException("java.home is not available for launching headless snapshot client process");
        }
        String javaBinary = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win")
                        ? "java.exe"
                        : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path is not available for launching headless snapshot client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(HeadlessSnapshotSocketClient.class.getName());
        command.add("--host");
        command.add(host);
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--artifact");
        command.add(receiverArtifactPath.toAbsolutePath().normalize().toString());
        command.add("--expected-frames");
        command.add(Integer.toString(expectedFrames));
        command.add("--receipt");
        command.add(clientReceiptPath.toAbsolutePath().normalize().toString());
        command.add("--session-window-cursor");
        command.add("true");
        command.add("--stream-id");
        command.add(streamIdentity.streamId());
        command.add("--stream-version");
        command.add(Integer.toString(streamIdentity.streamVersion()));
        if (movementCommand != null) {
            command.add("--movement-ship-id");
            command.add(Long.toString(movementCommand.shipId()));
            command.add("--movement-sim-tick");
            command.add(Long.toString(movementCommand.simTick()));
            command.add("--movement-intent");
            command.add(movementCommand.movementIntent().name());
        }
        if (weaponGroupCommand != null) {
            command.add("--weapon-group-ship-id");
            command.add(Long.toString(weaponGroupCommand.shipId()));
            command.add("--weapon-group-sim-tick");
            command.add(Long.toString(weaponGroupCommand.simTick()));
            command.add("--weapon-group");
            command.add(Integer.toString(weaponGroupCommand.group()));
        }
        if (fireCommand != null) {
            command.add("--fire-ship-id");
            command.add(Long.toString(fireCommand.shipId()));
            command.add("--fire-sim-tick");
            command.add(Long.toString(fireCommand.simTick()));
            command.add("--fire");
            command.add(Boolean.toString(fireCommand.fire()));
        }
        if (systemCommand != null) {
            command.add("--system-ship-id");
            command.add(Long.toString(systemCommand.shipId()));
            command.add("--system-sim-tick");
            command.add(Long.toString(systemCommand.simTick()));
            command.add("--system-id");
            command.add(Integer.toString(systemCommand.systemId()));
        }
        if (resumeClaimParameters != null) {
            command.add("--resume-session-id");
            command.add(Long.toString(resumeClaimParameters.sessionId()));
            command.add("--resume-window-ordinal");
            command.add(Integer.toString(resumeClaimParameters.windowOrdinal()));
            command.add("--resume-previous-cursor");
            command.add(Long.toString(resumeClaimParameters.previousResumeCursor()));
            command.add("--resume-next-cursor");
            command.add(Long.toString(resumeClaimParameters.nextResumeCursor()));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(clientLogPath.toFile());
        return builder.start();
    }

    static Process startAdmissionHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath) throws IOException {
        return startAdmissionHeadlessClientProcess(
                host,
                port,
                receiverArtifactPath,
                clientReceiptPath,
                expectedFrames,
                streamIdentity,
                null,
                clientLogPath);
    }

    static Process startAdmissionHeadlessClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            NetworkCapabilityManifest capabilityManifest,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Path logParent = clientLogPath.getParent();
        if (logParent != null) {
            Files.createDirectories(logParent);
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IOException("java.home is not available for launching headless snapshot client process");
        }
        String javaBinary = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win")
                        ? "java.exe"
                        : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path is not available for launching headless snapshot client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(HeadlessSnapshotSocketClient.class.getName());
        command.add("--host");
        command.add(host);
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--artifact");
        command.add(receiverArtifactPath.toAbsolutePath().normalize().toString());
        command.add("--expected-frames");
        command.add(Integer.toString(expectedFrames));
        command.add("--receipt");
        command.add(clientReceiptPath.toAbsolutePath().normalize().toString());
        command.add("--admission-only");
        command.add("true");
        command.add("--stream-id");
        command.add(streamIdentity.streamId());
        command.add("--stream-version");
        command.add(Integer.toString(streamIdentity.streamVersion()));
        if (capabilityManifest != null) {
            command.add("--capability-manifest-version");
            command.add(Integer.toString(capabilityManifest.manifestVersion()));
            command.add("--capability-entry-count");
            command.add(Integer.toString(capabilityManifest.entryCount()));
            command.add("--capability-stream-count");
            command.add(Integer.toString(capabilityManifest.streamCount()));
            command.add("--capability-fingerprint-crc32");
            command.add(Long.toUnsignedString(capabilityManifest.fingerprintCrc32()));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(clientLogPath.toFile());
        return builder.start();
    }

    static Process startPersistentHeadlessClientProcess(
            String host,
            int port,
            List<Path> receiverArtifactPaths,
            Path clientReceiptPath,
            int expectedFramesPerWindow,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand) throws IOException {
        return startPersistentHeadlessClientProcess(
                host,
                port,
                receiverArtifactPaths,
                clientReceiptPath,
                expectedFramesPerWindow,
                streamIdentity,
                clientLogPath,
                movementCommand,
                null,
                null,
                null);
    }

    static Process startPersistentHeadlessClientProcess(
            String host,
            int port,
            List<Path> receiverArtifactPaths,
            Path clientReceiptPath,
            int expectedFramesPerWindow,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand) throws IOException {
        return startPersistentHeadlessClientProcess(
                host,
                port,
                receiverArtifactPaths,
                clientReceiptPath,
                expectedFramesPerWindow,
                streamIdentity,
                clientLogPath,
                movementCommand,
                null,
                fireCommand,
                null);
    }

    static Process startPersistentHeadlessClientProcess(
            String host,
            int port,
            List<Path> receiverArtifactPaths,
            Path clientReceiptPath,
            int expectedFramesPerWindow,
            SnapshotStreamIdentity streamIdentity,
            Path clientLogPath,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
            HeadlessSnapshotSocketClient.ClientWeaponGroupCommand weaponGroupCommand,
            HeadlessSnapshotSocketClient.ClientFireCommand fireCommand,
            HeadlessSnapshotSocketClient.ClientSystemCommand systemCommand) throws IOException {
        receiverArtifactPaths = List.copyOf(Objects.requireNonNull(receiverArtifactPaths, "receiverArtifactPaths"));
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        if (receiverArtifactPaths.size() != 2) {
            throw new IllegalArgumentException("persistent client process currently requires exactly two artifacts");
        }
        Path logParent = clientLogPath.getParent();
        if (logParent != null) {
            Files.createDirectories(logParent);
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IOException("java.home is not available for launching headless snapshot client process");
        }
        String javaBinary = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win")
                        ? "java.exe"
                        : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path is not available for launching headless snapshot client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(HeadlessSnapshotSocketClient.class.getName());
        command.add("--host");
        command.add(host);
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--artifact");
        command.add(receiverArtifactPaths.get(0).toAbsolutePath().normalize().toString());
        command.add("--persistent-windows");
        command.add(Integer.toString(receiverArtifactPaths.size()));
        command.add("--artifact-window-2");
        command.add(receiverArtifactPaths.get(1).toAbsolutePath().normalize().toString());
        command.add("--expected-frames");
        command.add(Integer.toString(expectedFramesPerWindow));
        command.add("--receipt");
        command.add(clientReceiptPath.toAbsolutePath().normalize().toString());
        command.add("--stream-id");
        command.add(streamIdentity.streamId());
        command.add("--stream-version");
        command.add(Integer.toString(streamIdentity.streamVersion()));
        if (movementCommand != null) {
            command.add("--movement-ship-id");
            command.add(Long.toString(movementCommand.shipId()));
            command.add("--movement-sim-tick");
            command.add(Long.toString(movementCommand.simTick()));
            command.add("--movement-intent");
            command.add(movementCommand.movementIntent().name());
        }
        if (weaponGroupCommand != null) {
            command.add("--weapon-group-ship-id");
            command.add(Long.toString(weaponGroupCommand.shipId()));
            command.add("--weapon-group-sim-tick");
            command.add(Long.toString(weaponGroupCommand.simTick()));
            command.add("--weapon-group");
            command.add(Integer.toString(weaponGroupCommand.group()));
        }
        if (fireCommand != null) {
            command.add("--fire-ship-id");
            command.add(Long.toString(fireCommand.shipId()));
            command.add("--fire-sim-tick");
            command.add(Long.toString(fireCommand.simTick()));
            command.add("--fire");
            command.add(Boolean.toString(fireCommand.fire()));
        }
        if (systemCommand != null) {
            command.add("--system-ship-id");
            command.add(Long.toString(systemCommand.shipId()));
            command.add("--system-sim-tick");
            command.add(Long.toString(systemCommand.simTick()));
            command.add("--system-id");
            command.add(Integer.toString(systemCommand.systemId()));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(clientLogPath.toFile());
        return builder.start();
    }

    static int waitForClientProcess(Process clientProcess, Path clientLogPath) throws IOException {
        try {
            if (!clientProcess.waitFor(PROCESS_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                clientProcess.destroyForcibly();
                throw new IOException("timed out waiting for headless snapshot socket client process; log="
                        + clientLogPath.toAbsolutePath().normalize());
            }
            return clientProcess.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for headless snapshot socket client process", interrupted);
        }
    }

}
