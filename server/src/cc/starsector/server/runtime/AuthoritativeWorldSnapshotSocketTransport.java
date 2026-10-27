package cc.starsector.server.runtime;

import cc.starsector.server.client.WorldSnapshotSocketClient;
import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.NonShipEntitySnapshot;
import cc.starsector.server.protocol.ShipFluxSnapshot;
import cc.starsector.server.protocol.ShipWeaponMountSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Carries the per-entity {@link AuthoritativeWorldSnapshot} wire payload
 * ({@code AWSS}, {@link AuthoritativeWorldSnapshotCodec}) over a real TCP socket to
 * a separate-JVM {@link WorldSnapshotSocketClient}.
 *
 * <p>Before this, the per-entity snapshot codec only ever round-tripped over an
 * in-memory byte stream or was captured in-process from a
 * real engine run. The live socket transport
 * ({@link OfflineCombatStepWireLocalhostSocketTransport}) framed only the
 * fixed-width aggregate {@code OfflineCombatStep} payload. This class is the first
 * time the variable-length per-entity codec crosses a real socket into a real
 * second process, which then re-derives the {@code state_changes} property with no
 * shared memory — the genuinely-new wire crossing flagged.
 *
 *
 * <p>The server streams an already-encoded {@code AWSS} artifact (e.g. the
 * committed real-engine capture), so the bytes that travel
 * the socket are the same authoritative bytes the engine run produced. The crossing
 * is validated on both sides: the server re-decodes the receiver's file and
 * cross-checks frame count, byte-identity, and an independently-computed distinct
 * entity-state count against the client's receipt.
 */
public final class AuthoritativeWorldSnapshotSocketTransport {

    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;
    private static final long PROCESS_JOIN_TIMEOUT_MILLIS = 30_000L;

    private AuthoritativeWorldSnapshotSocketTransport() {
    }

    public record Receipt(
            Path senderArtifactPath,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            String socketHost,
            int socketPort,
            boolean loopback,
            int expectedFrames,
            long senderBytes,
            long receiverBytes,
            int framesReceived,
            int entityCountFirstFrame,
            int serverDistinctEntityStates,
            int clientDistinctEntityStates,
            int serverDistinctFluxStates,
            int clientDistinctFluxStates,
            int serverDistinctWeaponMountStates,
            int clientDistinctWeaponMountStates,
            boolean stateChanges,
            List<AuthoritativeWorldSnapshot> receiverSnapshots) {
    }

    public static Receipt sendArtifactToProcessLoopback(
            Path senderArtifactPath,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        return send(
                senderArtifactPath,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                InetAddress.getLoopbackAddress(),
                true);
    }

    public static Receipt sendArtifactToProcessOffLoopback(
            Path senderArtifactPath,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        InetAddress bindAddress = requireNonLoopbackIpv4Address();
        Receipt receipt = send(
                senderArtifactPath,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                bindAddress,
                false);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    private static Receipt send(
            Path senderArtifactPath,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            InetAddress bindAddress,
            boolean loopback) throws IOException {
        Objects.requireNonNull(senderArtifactPath, "senderArtifactPath");
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(clientLogPath, "clientLogPath");
        if (senderArtifactPath.toAbsolutePath().normalize()
                .equals(receiverArtifactPath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("receiver artifact path must be distinct from sender artifact path");
        }

        byte[] senderBytes = Files.readAllBytes(senderArtifactPath);
        List<AuthoritativeWorldSnapshot> senderSnapshots =
                AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(senderBytes));
        int expectedFrames = senderSnapshots.size();
        if (expectedFrames == 0) {
            throw new IOException("sender artifact decoded to zero world-snapshot frames: " + senderArtifactPath);
        }
        int serverDistinct = distinctEntityStateCount(senderSnapshots);
        int serverDistinctFlux = distinctFluxStateCount(senderSnapshots);
        int serverDistinctMount = distinctWeaponMountStateCount(senderSnapshots);

        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        Process clientProcess = null;
        IOException serverFailure = null;
        long sentBytes = 0L;
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientProcess = startClientProcess(
                    bindAddress.getHostAddress(),
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    clientLogPath);

            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                OutputStream out = socket.getOutputStream();
                out.write(senderBytes);
                out.flush();
                sentBytes = senderBytes.length;
                socket.shutdownOutput();
            } catch (IOException ioe) {
                serverFailure = ioe;
            }
        }

        int exitCode = waitForClientProcess(clientProcess, clientLogPath);
        if (serverFailure != null) {
            throw serverFailure;
        }
        if (exitCode != 0) {
            throw new IOException("world snapshot socket client process exited " + exitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (sentBytes != senderBytes.length) {
            throw new IOException("world snapshot socket sender wrote unexpected byte count: expected "
                    + senderBytes.length + ", got " + sentBytes);
        }

        byte[] receiverBytes = Files.readAllBytes(receiverArtifactPath);
        if (!Arrays.equals(senderBytes, receiverBytes)) {
            throw new IOException("world snapshot socket receiver bytes differ from sender bytes");
        }
        List<AuthoritativeWorldSnapshot> receiverSnapshots =
                AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(receiverBytes));
        if (receiverSnapshots.size() != expectedFrames) {
            throw new IOException("world snapshot socket receiver frame count mismatch: expected "
                    + expectedFrames + ", got " + receiverSnapshots.size());
        }

        ClientReceipt clientReceipt = readClientReceipt(clientReceiptPath);
        if (clientReceipt.framesReceived() != expectedFrames) {
            throw new IOException("world snapshot socket client receipt frame count mismatch: expected "
                    + expectedFrames + ", got " + clientReceipt.framesReceived());
        }
        if (clientReceipt.bytesReceived() != senderBytes.length) {
            throw new IOException("world snapshot socket client receipt byte count mismatch: expected "
                    + senderBytes.length + ", got " + clientReceipt.bytesReceived());
        }
        if (clientReceipt.distinctEntityStates() != serverDistinct) {
            throw new IOException("world snapshot socket distinct-entity-state mismatch: server "
                    + serverDistinct + " vs client " + clientReceipt.distinctEntityStates());
        }
        if (clientReceipt.distinctFluxStates() != serverDistinctFlux) {
            throw new IOException("world snapshot socket distinct-flux-state mismatch: server "
                    + serverDistinctFlux + " vs client " + clientReceipt.distinctFluxStates());
        }
        if (clientReceipt.distinctWeaponMountStates() != serverDistinctMount) {
            throw new IOException("world snapshot socket distinct-weapon-mount-state mismatch: server "
                    + serverDistinctMount + " vs client " + clientReceipt.distinctWeaponMountStates());
        }

        return new Receipt(
                senderArtifactPath,
                receiverArtifactPath,
                clientReceiptPath,
                bindAddress.getHostAddress(),
                port,
                loopback,
                expectedFrames,
                senderBytes.length,
                receiverBytes.length,
                clientReceipt.framesReceived(),
                receiverSnapshots.get(0).entityCount(),
                serverDistinct,
                clientReceipt.distinctEntityStates(),
                serverDistinctFlux,
                clientReceipt.distinctFluxStates(),
                serverDistinctMount,
                clientReceipt.distinctWeaponMountStates(),
                clientReceipt.stateChanges(),
                receiverSnapshots);
    }

    /**
     * Distinct per-entity-records digest count across frames, excluding the
     * monotonic {@code simTick}. Mirrors {@link WorldSnapshotSocketClient} and
     * its verifier script, so the server and the
     * separate client compute the same property by the same rule.
     */
    static int distinctEntityStateCount(List<AuthoritativeWorldSnapshot> snapshots) throws IOException {
        Set<String> distinct = new HashSet<>();
        for (AuthoritativeWorldSnapshot snapshot : snapshots) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeInt(snapshot.entityCount());
            for (AuthoritativeEntitySnapshot entity : snapshot.entities()) {
                data.writeLong(entity.entityId());
                data.writeInt(entity.contentId());
                data.writeLong(entity.spawnTick());
                data.writeInt(entity.ownerConnectionId());
                data.writeInt(Float.floatToIntBits(entity.locX()));
                data.writeInt(Float.floatToIntBits(entity.locY()));
                data.writeInt(Float.floatToIntBits(entity.velX()));
                data.writeInt(Float.floatToIntBits(entity.velY()));
                data.writeInt(Float.floatToIntBits(entity.facing()));
                data.writeInt(Float.floatToIntBits(entity.angularVelocity()));
                data.writeInt(Float.floatToIntBits(entity.hitpoints()));
                data.writeInt(Float.floatToIntBits(entity.hullLevel()));
                data.writeInt(entity.armorCellCount());
                data.writeInt(Float.floatToIntBits(entity.armorTotal()));
                data.writeLong(entity.armorGridHash());
            }
            // Non-ship tier: a ship-only frame appends a constant
            // (count 0), so the distinct count over ship-only streams is unchanged;
            // an evolving beam endpoint here makes the distinct count reflect it too.
            data.writeInt(snapshot.nonShipEntityCount());
            for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
                data.writeLong(nonShip.networkId());
                data.writeInt(nonShip.kindWireCode());
                data.writeInt(nonShip.owner());
                data.writeInt(Float.floatToIntBits(nonShip.posX()));
                data.writeInt(Float.floatToIntBits(nonShip.posY()));
                data.writeInt(nonShip.contentId());
            }
            // Flux tier (schema v3): a flux-empty frame appends a constant
            // (count 0), so the distinct count over pre-v3 streams is unchanged; an
            // evolving flux/shield state makes the distinct count reflect it too. This
            // mirrors WorldSnapshotSocketClient.appendFluxBytes byte-for-byte so the
            // server and the separate client agree on the property.
            appendFluxBytes(data, snapshot);
            // Weapon-mount tier (schema v4): a mount-empty frame appends a
            // constant (count 0), so the distinct count over pre-v4 streams is unchanged;
            // an evolving per-mount state (ammo draining, cooldown cycling) makes the
            // distinct count reflect it too. Mirrors WorldSnapshotSocketClient
            // .appendWeaponMountBytes byte-for-byte so the two sides agree.
            appendWeaponMountBytes(data, snapshot);
            data.flush();
            distinct.add(Arrays.toString(bytes.toByteArray()));
        }
        return distinct.size();
    }

    /**
     * Distinct digest count over just the flux tier across frames,
     * mirroring {@link WorldSnapshotSocketClient}'s flux digest. A value &gt; 1 means
     * the decoded per-ship flux/shield state itself evolved (flux climbing, an overload
     * latching, a shield dropping), proving the flux crossing carries live state.
     */
    static int distinctFluxStateCount(List<AuthoritativeWorldSnapshot> snapshots) throws IOException {
        Set<String> distinct = new HashSet<>();
        for (AuthoritativeWorldSnapshot snapshot : snapshots) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            appendFluxBytes(data, snapshot);
            data.flush();
            distinct.add(Arrays.toString(bytes.toByteArray()));
        }
        return distinct.size();
    }

    /** Append the canonical flux-tier bytes (count, then per-record fields) to {@code data}. */
    private static void appendFluxBytes(DataOutputStream data, AuthoritativeWorldSnapshot snapshot)
            throws IOException {
        data.writeInt(snapshot.fluxStateCount());
        for (ShipFluxSnapshot flux : snapshot.fluxStates()) {
            data.writeLong(flux.entityId());
            data.writeInt(Float.floatToIntBits(flux.currFlux()));
            data.writeInt(Float.floatToIntBits(flux.maxFlux()));
            data.writeInt(Float.floatToIntBits(flux.fluxLevel()));
            data.writeInt(Float.floatToIntBits(flux.hardFlux()));
            data.writeInt(flux.statusFlags());
        }
    }

    /**
     * Distinct digest count over just the weapon-mount tier across frames,
     * mirroring {@link WorldSnapshotSocketClient}'s weapon-mount digest. A value &gt; 1
     * means the decoded per-mount readiness itself evolved (ammo draining, a cooldown
     * cycling, a mount disabling), proving the weapon-mount crossing carries live state.
     */
    static int distinctWeaponMountStateCount(List<AuthoritativeWorldSnapshot> snapshots) throws IOException {
        Set<String> distinct = new HashSet<>();
        for (AuthoritativeWorldSnapshot snapshot : snapshots) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            appendWeaponMountBytes(data, snapshot);
            data.flush();
            distinct.add(Arrays.toString(bytes.toByteArray()));
        }
        return distinct.size();
    }

    /** Append the canonical weapon-mount-tier bytes (count, then per-record fields) to {@code data}. */
    private static void appendWeaponMountBytes(DataOutputStream data, AuthoritativeWorldSnapshot snapshot)
            throws IOException {
        data.writeInt(snapshot.weaponMountCount());
        for (ShipWeaponMountSnapshot mount : snapshot.weaponMounts()) {
            data.writeLong(mount.entityId());
            data.writeInt(mount.mountIndex());
            data.writeInt(mount.weaponContentId());
            data.writeInt(Float.floatToIntBits(mount.cooldownRemaining()));
            data.writeInt(Float.floatToIntBits(mount.cooldown()));
            data.writeInt(mount.ammo());
            data.writeInt(mount.maxAmmo());
            data.writeInt(Float.floatToIntBits(mount.chargeLevel()));
            data.writeInt(mount.statusFlags());
        }
    }

    /**
     * Distinct digest count over just the non-ship tier across frames,
     * mirroring {@link WorldSnapshotSocketClient#nonShipStateKey}. A value &gt; 1
     * means the decoded non-ship entities themselves evolved (e.g. a beam impact
     * endpoint moved), proving the beam crossing carries live state, not a constant.
     */
    static int distinctNonShipStateCount(List<AuthoritativeWorldSnapshot> snapshots) throws IOException {
        Set<String> distinct = new HashSet<>();
        for (AuthoritativeWorldSnapshot snapshot : snapshots) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeInt(snapshot.nonShipEntityCount());
            for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
                data.writeLong(nonShip.networkId());
                data.writeInt(nonShip.kindWireCode());
                data.writeInt(nonShip.owner());
                data.writeInt(Float.floatToIntBits(nonShip.posX()));
                data.writeInt(Float.floatToIntBits(nonShip.posY()));
                data.writeInt(nonShip.contentId());
            }
            data.flush();
            distinct.add(Arrays.toString(bytes.toByteArray()));
        }
        return distinct.size();
    }

    private static Process startClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            Path clientLogPath) throws IOException {
        Path logParent = clientLogPath.getParent();
        if (logParent != null) {
            Files.createDirectories(logParent);
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IOException("java.home is not available for launching world snapshot client process");
        }
        String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path is not available for launching world snapshot client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(WorldSnapshotSocketClient.class.getName());
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
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(clientLogPath.toFile());
        return builder.start();
    }

    private static int waitForClientProcess(Process clientProcess, Path clientLogPath) throws IOException {
        if (clientProcess == null) {
            throw new IOException("world snapshot client process was not started");
        }
        try {
            if (!clientProcess.waitFor(PROCESS_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                clientProcess.destroyForcibly();
                throw new IOException("timed out waiting for world snapshot socket client process; log="
                        + clientLogPath.toAbsolutePath().normalize());
            }
            return clientProcess.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for world snapshot socket client process", interrupted);
        }
    }

    private record ClientReceipt(
            long bytesReceived,
            int framesReceived,
            int distinctEntityStates,
            int distinctFluxStates,
            int distinctWeaponMountStates,
            boolean stateChanges) {
    }

    private static ClientReceipt readClientReceipt(Path receiptPath) throws IOException {
        long bytesReceived = -1L;
        int framesReceived = -1;
        int distinctEntityStates = -1;
        int distinctFluxStates = -1;
        int distinctWeaponMountStates = -1;
        boolean stateChanges = false;
        for (String line : Files.readAllLines(receiptPath)) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "bytes_received" -> bytesReceived = Long.parseLong(value);
                case "frames_received" -> framesReceived = Integer.parseInt(value);
                case "distinct_entity_states" -> distinctEntityStates = Integer.parseInt(value);
                case "distinct_flux_states" -> distinctFluxStates = Integer.parseInt(value);
                case "distinct_weapon_mount_states" -> distinctWeaponMountStates = Integer.parseInt(value);
                case "state_changes" -> stateChanges = Boolean.parseBoolean(value);
                default -> { /* ignore other keys */ }
            }
        }
        if (bytesReceived < 0 || framesReceived < 0 || distinctEntityStates < 0
                || distinctFluxStates < 0 || distinctWeaponMountStates < 0) {
            throw new IOException("world snapshot client receipt missing required fields: " + receiptPath);
        }
        return new ClientReceipt(
                bytesReceived, framesReceived, distinctEntityStates, distinctFluxStates,
                distinctWeaponMountStates, stateChanges);
    }

    private static InetAddress requireNonLoopbackIpv4Address() throws IOException {
        InetAddress fallback = null;
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            throw new IOException("failed to enumerate local network interfaces", e);
        }
        if (interfaces == null) {
            throw new IOException("no network interfaces are available for off-loopback transport");
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            try {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
            } catch (SocketException e) {
                continue;
            }
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address)
                        || address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()) {
                    continue;
                }
                if (address.isSiteLocalAddress()) {
                    return address;
                }
                if (fallback == null) {
                    fallback = address;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IOException("no non-loopback IPv4 address is available for off-loopback transport");
    }

    private static void requireNonLoopbackSocketHost(String socketHost) throws IOException {
        Objects.requireNonNull(socketHost, "socketHost");
        InetAddress address = InetAddress.getByName(socketHost);
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            throw new IOException("off-loopback transport resolved to loopback host: " + socketHost);
        }
    }

    /**
     * CLI entrypoint for the committed real-engine crossing (driven).
     * Streams an existing
     * {@code AWSS} artifact off-loopback (default) to the separate-JVM client and
     * prints a summary; exits non-zero on any mismatch.
     */
    public static void main(String[] args) {
        try {
            Path sender = null;
            Path receiver = null;
            Path receipt = null;
            Path log = null;
            boolean loopback = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--artifact" -> sender = Path.of(args[++i]);
                    case "--receiver" -> receiver = Path.of(args[++i]);
                    case "--receipt" -> receipt = Path.of(args[++i]);
                    case "--log" -> log = Path.of(args[++i]);
                    case "--loopback" -> loopback = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (sender == null || receiver == null || receipt == null || log == null) {
                throw new IllegalArgumentException("required: --artifact --receiver --receipt --log [--loopback]");
            }
            Receipt result = loopback
                    ? sendArtifactToProcessLoopback(sender, receiver, receipt, log)
                    : sendArtifactToProcessOffLoopback(sender, receiver, receipt, log);
            System.out.println("world-snapshot socket crossing OK"
                    + " host=" + result.socketHost()
                    + " port=" + result.socketPort()
                    + " loopback=" + result.loopback()
                    + " frames=" + result.framesReceived()
                    + " bytes=" + result.receiverBytes()
                    + " entities=" + result.entityCountFirstFrame()
                    + " distinct_entity_states=" + result.serverDistinctEntityStates()
                    + " (client " + result.clientDistinctEntityStates() + ")"
                    + " distinct_flux_states=" + result.serverDistinctFluxStates()
                    + " (client " + result.clientDistinctFluxStates() + ")"
                    + " distinct_weapon_mount_states=" + result.serverDistinctWeaponMountStates()
                    + " (client " + result.clientDistinctWeaponMountStates() + ")"
                    + " state_changes=" + result.stateChanges());
            System.exit(result.stateChanges() ? 0 : 2);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
