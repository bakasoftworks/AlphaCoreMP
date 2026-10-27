package cc.starsector.server.runtime;

import cc.starsector.server.client.WorldSnapshotSocketClient;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.WorldSnapshotMessage;
import cc.starsector.server.protocol.WorldSnapshotPublicationPolicy;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fans ONE authoritative {@link AuthoritativeWorldSnapshot} stream out to several
 * CONCURRENT separate-JVM {@link WorldSnapshotSocketClient} peers, each negotiated to
 * a DIFFERENT served schema by the real {@link NetworkMessageRegistry}.
 *
 *
 * <p>Two prior capabilities meet here for the first time:
 *
 * <ul>
 *   <li>The per-client schema negotiation + tier downgrade built earlier
 *       ({@link NetworkMessageRegistry#negotiateServedSchema} +
 *       {@link AuthoritativeWorldSnapshot#withoutWeaponMountTier()}, wrapped by
 *       {@link WorldSnapshotPublicationPolicy}) had only ever run in-memory in a unit
 *       test, or single-peer/sequentially through
 *       {@link LiveWorldSnapshotSocketPublisher#publishGovernedLoopback}.</li>
 *   <li>The concurrent multi-client fanout
 *       ({@code concurrentTwoClientAuthoritativeSessionFansOneStreamToTwoProcesses})
 *       served every peer the SAME byte-identical artifact on the fixed-width
 *       offline-step path.</li>
 * </ul>
 *
 * <p>This class is the first time the live AWSS path serves <em>heterogeneous</em>
 * schemas to <em>concurrent</em> peers: a single server binds one socket, accepts each
 * peer connection at the same time, reads the schema version that peer advertises over
 * the wire, resolves a per-peer {@link WorldSnapshotPublicationPolicy} from the registry,
 * and streams the per-peer-governed bytes per tick on its own thread. A v4 peer keeps the
 * weapon-mount tier; a concurrent v3 peer has it projected out (but keeps flux); a
 * concurrent v2 peer keeps only the non-ship tier. Each peer is a real second process
 * that decodes ONLY its negotiated tier and independently re-derives its state-change
 * counts — so the downgrade machinery is exercised end to end, concurrently, over a real
 * socket, not merely asserted in memory.
 *
 * <p>The crossing is fail-closed: the server records exactly which tier records it served
 * each peer (governed per frame) and reconciles them against that peer's separate-process
 * receipt (bytes, frames, weapon-mount/flux/non-ship record counts). Any worker IOException
 * or non-zero client exit fails the whole fanout. Loopback keeps it self-contained.
 */
public final class ConcurrentNegotiatedWorldSnapshotFanout {

    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
    private static final long PROCESS_JOIN_TIMEOUT_MILLIS = 60_000L;
    private static final long WORKER_JOIN_TIMEOUT_MILLIS = 45_000L;

    private ConcurrentNegotiatedWorldSnapshotFanout() {
    }

    /**
     * One concurrent peer: the maximum world-snapshot schema version it advertises over
     * the socket, plus the files its separate process drains the stream into and reports
     * through. {@code peerId} is the spec's index in the fan-out list.
     */
    public record PeerSpec(
            int advertisedMaxSchema,
            Path receiverArtifactPath,
            Path receiptPath,
            Path logPath) {
        public PeerSpec {
            Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            Objects.requireNonNull(receiptPath, "receiptPath");
            Objects.requireNonNull(logPath, "logPath");
            if (advertisedMaxSchema <= 0) {
                throw new IllegalArgumentException("advertisedMaxSchema must be > 0; got " + advertisedMaxSchema);
            }
        }
    }

    /** Reconciled server+client view of one peer's negotiated, governed crossing. */
    public record PeerReceipt(
            int peerId,
            int advertisedMaxSchema,
            int servedSchemaVersion,
            int framesServed,
            long bytesServed,
            int weaponMountRecordsServed,
            int fluxRecordsServed,
            int nonShipRecordsServed,
            long clientBytesReceived,
            int clientFramesReceived,
            int clientWeaponMountRecords,
            int clientFramesWithWeaponMounts,
            int clientDistinctWeaponMountStates,
            int clientFluxRecords,
            int clientDistinctFluxStates,
            int clientNonShipRecords,
            boolean clientStateChanges) {

        /** True when the negotiated schema is high enough to carry the v4 weapon-mount tier. */
        public boolean servedWeaponMountTier() {
            return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V4;
        }

        /** True when the negotiated schema is high enough to carry the v3 flux tier. */
        public boolean servedFluxTier() {
            return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V3;
        }
    }

    public record Receipt(
            String socketHost,
            int socketPort,
            boolean loopback,
            int expectedFrames,
            List<PeerReceipt> peers) {
    }

    /**
     * Bind one loopback server socket and fan {@code sourceFrames} out to every peer in
     * {@code peers} concurrently, serving each its registry-negotiated schema. Returns the
     * reconciled per-peer receipts in spec order.
     */
    public static Receipt fanOutLoopback(
            List<AuthoritativeWorldSnapshot> sourceFrames, List<PeerSpec> peers) throws IOException {
        Objects.requireNonNull(sourceFrames, "sourceFrames");
        Objects.requireNonNull(peers, "peers");
        if (sourceFrames.isEmpty()) {
            throw new IllegalArgumentException("fan-out requires at least one source frame");
        }
        if (peers.size() < 2) {
            throw new IllegalArgumentException("concurrent fan-out requires at least two peers; got " + peers.size());
        }
        requireDistinctReceiverPaths(peers);
        List<AuthoritativeWorldSnapshot> frames = List.copyOf(sourceFrames);
        int expectedFrames = frames.size();
        int peerCount = peers.size();
        NetworkMessageRegistry registry = NetworkMessageRegistry.authoritativeCombat();
        InetAddress bindAddress = InetAddress.getLoopbackAddress();

        for (PeerSpec spec : peers) {
            Path parent = spec.receiverArtifactPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }

        PeerServerAccounting[] serverByPeer = new PeerServerAccounting[peerCount];
        List<Process> clientProcesses = new ArrayList<>(peerCount);
        IOException failure = null;
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), peerCount);
            port = serverSocket.getLocalPort();

            for (int peerId = 0; peerId < peerCount; peerId++) {
                clientProcesses.add(startClientProcess(
                        bindAddress.getHostAddress(), port, peerId, peers.get(peerId), expectedFrames));
            }

            ExecutorService pool = Executors.newFixedThreadPool(peerCount);
            List<Future<?>> futures = new ArrayList<>(peerCount);
            try {
                for (int i = 0; i < peerCount; i++) {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                    futures.add(pool.submit(() -> {
                        servePeer(socket, frames, registry, serverByPeer);
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(WORKER_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                }
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                failure = cause instanceof IOException io
                        ? io
                        : new IOException("concurrent fan-out worker failed", cause);
            } catch (Exception e) {
                failure = new IOException("concurrent fan-out failed awaiting workers", e);
            } finally {
                pool.shutdownNow();
            }
        }

        try {
            joinClientProcesses(clientProcesses, peers);
        } catch (IOException joinFailure) {
            if (failure == null) {
                failure = joinFailure;
            }
        }
        if (failure != null) {
            throw failure;
        }

        List<PeerReceipt> peerReceipts = new ArrayList<>(peerCount);
        for (int peerId = 0; peerId < peerCount; peerId++) {
            PeerServerAccounting served = serverByPeer[peerId];
            if (served == null) {
                throw new IOException("no server accounting recorded for peer " + peerId);
            }
            peerReceipts.add(reconcilePeer(peerId, peers.get(peerId), served));
        }
        return new Receipt(bindAddress.getHostAddress(), port, true, expectedFrames, peerReceipts);
    }

    /**
     * Serve one accepted peer: read the schema it advertises, resolve its per-peer policy
     * from the registry, and stream each governed frame per tick on this worker thread.
     */
    private static void servePeer(
            Socket socket,
            List<AuthoritativeWorldSnapshot> frames,
            NetworkMessageRegistry registry,
            PeerServerAccounting[] serverByPeer) throws IOException {
        try (Socket peerSocket = socket) {
            InputStream rawIn = peerSocket.getInputStream();
            DataInputStream in = new DataInputStream(rawIn);
            int peerId = in.readInt();
            int advertisedMaxSchema = in.readInt();
            if (peerId < 0 || peerId >= serverByPeer.length) {
                throw new IOException("peer advertised out-of-range peerId: " + peerId);
            }
            if (advertisedMaxSchema <= 0) {
                throw new IOException("peer " + peerId + " advertised non-positive max schema: "
                        + advertisedMaxSchema);
            }
            // Registry-driven, per-peer negotiation: the schema this peer is served is what
            // the registry may serve a peer that advertised this maximum (capped to it).
            WorldSnapshotPublicationPolicy policy =
                    WorldSnapshotPublicationPolicy.forPeer(registry, advertisedMaxSchema, null);

            OutputStream out = peerSocket.getOutputStream();
            long bytesServed = 0L;
            int weaponMountRecordsServed = 0;
            int fluxRecordsServed = 0;
            int nonShipRecordsServed = 0;
            for (AuthoritativeWorldSnapshot frame : frames) {
                AuthoritativeWorldSnapshot governed = policy.govern(frame);
                if (governed == null) {
                    throw new IOException("policy produced a null governed snapshot for peer " + peerId);
                }
                ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
                AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, governed);
                byte[] encoded = frameBytes.toByteArray();
                out.write(encoded);
                out.flush();
                bytesServed += encoded.length;
                weaponMountRecordsServed += governed.weaponMountCount();
                fluxRecordsServed += governed.fluxStateCount();
                nonShipRecordsServed += governed.nonShipEntityCount();
            }
            peerSocket.shutdownOutput();
            serverByPeer[peerId] = new PeerServerAccounting(
                    advertisedMaxSchema,
                    policy.servedSchemaVersion(),
                    frames.size(),
                    bytesServed,
                    weaponMountRecordsServed,
                    fluxRecordsServed,
                    nonShipRecordsServed);
        }
    }

    private static PeerReceipt reconcilePeer(int peerId, PeerSpec spec, PeerServerAccounting served)
            throws IOException {
        ClientReceipt client = readClientReceipt(spec.receiptPath());
        if (client.framesReceived() != served.framesServed()) {
            throw new IOException("peer " + peerId + " frame count mismatch: served "
                    + served.framesServed() + " vs client " + client.framesReceived());
        }
        if (client.bytesReceived() != served.bytesServed()) {
            throw new IOException("peer " + peerId + " byte count mismatch: served "
                    + served.bytesServed() + " vs client " + client.bytesReceived());
        }
        if (client.weaponMountRecords() != served.weaponMountRecordsServed()) {
            throw new IOException("peer " + peerId + " weapon-mount record mismatch: served "
                    + served.weaponMountRecordsServed() + " vs client " + client.weaponMountRecords());
        }
        if (client.fluxRecords() != served.fluxRecordsServed()) {
            throw new IOException("peer " + peerId + " flux record mismatch: served "
                    + served.fluxRecordsServed() + " vs client " + client.fluxRecords());
        }
        if (client.nonShipRecords() != served.nonShipRecordsServed()) {
            throw new IOException("peer " + peerId + " non-ship record mismatch: served "
                    + served.nonShipRecordsServed() + " vs client " + client.nonShipRecords());
        }
        return new PeerReceipt(
                peerId,
                served.advertisedMaxSchema(),
                served.servedSchemaVersion(),
                served.framesServed(),
                served.bytesServed(),
                served.weaponMountRecordsServed(),
                served.fluxRecordsServed(),
                served.nonShipRecordsServed(),
                client.bytesReceived(),
                client.framesReceived(),
                client.weaponMountRecords(),
                client.framesWithWeaponMounts(),
                client.distinctWeaponMountStates(),
                client.fluxRecords(),
                client.distinctFluxStates(),
                client.nonShipRecords(),
                client.stateChanges());
    }

    private static void requireDistinctReceiverPaths(List<PeerSpec> peers) {
        Set<Path> receivers = new HashSet<>();
        Set<Path> receipts = new HashSet<>();
        for (PeerSpec spec : peers) {
            Path receiver = spec.receiverArtifactPath().toAbsolutePath().normalize();
            Path receipt = spec.receiptPath().toAbsolutePath().normalize();
            if (!receivers.add(receiver)) {
                throw new IllegalArgumentException("duplicate receiver artifact path: " + receiver);
            }
            if (!receipts.add(receipt)) {
                throw new IllegalArgumentException("duplicate receipt path: " + receipt);
            }
        }
    }

    private static Process startClientProcess(
            String host, int port, int peerId, PeerSpec spec, int expectedFrames) throws IOException {
        Path logParent = spec.logPath().getParent();
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
        command.add(spec.receiverArtifactPath().toAbsolutePath().normalize().toString());
        command.add("--expected-frames");
        command.add(Integer.toString(expectedFrames));
        command.add("--receipt");
        command.add(spec.receiptPath().toAbsolutePath().normalize().toString());
        // The peer advertises its capability over the socket: the server reads exactly
        // this (peerId, maxSchema) handshake and negotiates what schema to serve it.
        command.add("--advertise-peer-id");
        command.add(Integer.toString(peerId));
        command.add("--advertise-max-schema");
        command.add(Integer.toString(spec.advertisedMaxSchema()));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(spec.logPath().toFile());
        return builder.start();
    }

    private static void joinClientProcesses(List<Process> processes, List<PeerSpec> peers) throws IOException {
        IOException first = null;
        for (int i = 0; i < processes.size(); i++) {
            Process process = processes.get(i);
            if (process == null) {
                continue;
            }
            try {
                if (!process.waitFor(PROCESS_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    if (first == null) {
                        first = new IOException("timed out waiting for fan-out client process " + i
                                + "; log=" + peers.get(i).logPath().toAbsolutePath().normalize());
                    }
                    continue;
                }
                int exit = process.exitValue();
                if (exit != 0 && first == null) {
                    first = new IOException("fan-out client process " + i + " exited " + exit
                            + "; log=" + peers.get(i).logPath().toAbsolutePath().normalize());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for fan-out client process " + i, interrupted);
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private record PeerServerAccounting(
            int advertisedMaxSchema,
            int servedSchemaVersion,
            int framesServed,
            long bytesServed,
            int weaponMountRecordsServed,
            int fluxRecordsServed,
            int nonShipRecordsServed) {
    }

    private record ClientReceipt(
            long bytesReceived,
            int framesReceived,
            int weaponMountRecords,
            int framesWithWeaponMounts,
            int distinctWeaponMountStates,
            int fluxRecords,
            int distinctFluxStates,
            int nonShipRecords,
            boolean stateChanges) {
    }

    private static ClientReceipt readClientReceipt(Path receiptPath) throws IOException {
        long bytesReceived = -1L;
        int framesReceived = -1;
        int weaponMountRecords = -1;
        int framesWithWeaponMounts = -1;
        int distinctWeaponMountStates = -1;
        int fluxRecords = -1;
        int distinctFluxStates = -1;
        int nonShipRecords = -1;
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
                case "weapon_mount_records_received" -> weaponMountRecords = Integer.parseInt(value);
                case "frames_with_weapon_mounts" -> framesWithWeaponMounts = Integer.parseInt(value);
                case "distinct_weapon_mount_states" -> distinctWeaponMountStates = Integer.parseInt(value);
                case "flux_records_received" -> fluxRecords = Integer.parseInt(value);
                case "distinct_flux_states" -> distinctFluxStates = Integer.parseInt(value);
                case "non_ship_records_received" -> nonShipRecords = Integer.parseInt(value);
                case "state_changes" -> stateChanges = Boolean.parseBoolean(value);
                default -> { /* ignore other keys */ }
            }
        }
        if (bytesReceived < 0 || framesReceived < 0 || weaponMountRecords < 0
                || fluxRecords < 0 || nonShipRecords < 0) {
            throw new IOException("fan-out client receipt missing required fields: " + receiptPath);
        }
        return new ClientReceipt(
                bytesReceived, framesReceived, weaponMountRecords, framesWithWeaponMounts,
                distinctWeaponMountStates, fluxRecords, distinctFluxStates, nonShipRecords, stateChanges);
    }
}
