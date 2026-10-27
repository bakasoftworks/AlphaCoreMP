package cc.starsector.server.runtime;

import cc.starsector.server.client.EntityDespawnEventSocketClient;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.EntityDespawnEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the server-authoritative {@link EntityDespawnEvent} stream onto a real
 * TCP socket, consumed by a separate-JVM {@link EntityDespawnEventSocketClient}.
 *
 *
 * <p>This is the live-consumer crossing for the new
 * {@link cc.starsector.server.protocol.ServerEntityDespawnEventMessage}: the
 * {@link EntityDespawnEventDetector} derives despawn events from the full
 * authoritative snapshot stream, each event is encoded with the registry-bound
 * {@link EntityDespawnEventCodec}, and written+flushed onto the socket to a second
 * process that decodes it. The crossing is fail-closed and byte-checked: the
 * publisher cross-verifies the receiver's drained bytes for byte-identity and the
 * receipt's event count / ids / ticks / reasons against what it published.
 *
 * <p>{@link #main} grounds it in real engine bytes without relaunching the game: it
 * decodes a committed real-engine {@code AWSS} capture (e.g.
 * the byte-verified
 * lethal organic-despawn roster {1,2,3}-&gt;{1,3}-&gt;{1}), runs the detector over
 * its frames, and publishes the derived despawn events off-loopback. The despawn
 * events on the wire therefore correspond to real-engine roster
 * transitions.
 */
public final class EntityDespawnEventSocketPublisher {

    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
    private static final long PROCESS_JOIN_TIMEOUT_MILLIS = 60_000L;

    private EntityDespawnEventSocketPublisher() {
    }

    public record Receipt(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            String socketHost,
            int socketPort,
            boolean loopback,
            int sourceFrames,
            int eventsPublished,
            long bytesPublished,
            long receiverBytes,
            String publishedSha256,
            List<EntityDespawnEvent> events) {
    }

    public static Receipt publishLoopback(
            List<AuthoritativeWorldSnapshot> sourceFrames,
            float destroyedHullThreshold,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        return publish(
                sourceFrames,
                destroyedHullThreshold,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                InetAddress.getLoopbackAddress(),
                true);
    }

    public static Receipt publishOffLoopback(
            List<AuthoritativeWorldSnapshot> sourceFrames,
            float destroyedHullThreshold,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        InetAddress bindAddress = requireNonLoopbackIpv4Address();
        Receipt receipt = publish(
                sourceFrames,
                destroyedHullThreshold,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                bindAddress,
                false);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    private static Receipt publish(
            List<AuthoritativeWorldSnapshot> sourceFrames,
            float destroyedHullThreshold,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            InetAddress bindAddress,
            boolean loopback) throws IOException {
        Objects.requireNonNull(sourceFrames, "sourceFrames");
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(clientLogPath, "clientLogPath");
        if (sourceFrames.isEmpty()) {
            throw new IllegalArgumentException("despawn event publish requires at least one source frame");
        }

        // Derive the despawn events from the full authoritative roster stream.
        EntityDespawnEventDetector detector = new EntityDespawnEventDetector(destroyedHullThreshold);
        List<EntityDespawnEvent> events = detector.observeAll(sourceFrames);
        if (events.isEmpty()) {
            throw new IOException("source frames produced no despawn events (roster never shrank)");
        }

        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        ByteArrayOutputStream publishedBytes = new ByteArrayOutputStream();
        Process clientProcess = null;
        IOException serverFailure = null;
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
                    events.size(),
                    clientLogPath);

            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                OutputStream out = socket.getOutputStream();
                for (EntityDespawnEvent event : events) {
                    ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
                    EntityDespawnEventCodec.writeEvent(frameBytes, event);
                    byte[] encoded = frameBytes.toByteArray();
                    out.write(encoded);
                    out.flush();
                    publishedBytes.write(encoded);
                }
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
            throw new IOException("despawn event client process exited " + exitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }

        byte[] sent = publishedBytes.toByteArray();
        byte[] receiverBytes = Files.readAllBytes(receiverArtifactPath);
        if (!Arrays.equals(sent, receiverBytes)) {
            throw new IOException("despawn event receiver bytes differ from published bytes");
        }
        String publishedSha256 = sha256Hex(sent);
        verifyClientReceipt(clientReceiptPath, events, sent.length, publishedSha256);

        return new Receipt(
                receiverArtifactPath,
                clientReceiptPath,
                bindAddress.getHostAddress(),
                port,
                loopback,
                sourceFrames.size(),
                events.size(),
                sent.length,
                receiverBytes.length,
                publishedSha256,
                events);
    }

    private static void verifyClientReceipt(
            Path receiptPath, List<EntityDespawnEvent> events, int sentBytes, String publishedSha256)
            throws IOException {
        int eventsReceived = -1;
        long bytesReceived = -1L;
        String receivedSha256 = null;
        String despawnIds = null;
        String despawnTicks = null;
        String despawnReasons = null;
        for (String line : Files.readAllLines(receiptPath)) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "events_received" -> eventsReceived = Integer.parseInt(value);
                case "bytes_received" -> bytesReceived = Long.parseLong(value);
                case "received_sha256" -> receivedSha256 = value;
                case "despawn_ids" -> despawnIds = value;
                case "despawn_ticks" -> despawnTicks = value;
                case "despawn_reasons" -> despawnReasons = value;
                default -> { /* ignore */ }
            }
        }
        if (eventsReceived != events.size()) {
            throw new IOException("despawn event count mismatch: published " + events.size()
                    + " vs client " + eventsReceived);
        }
        if (bytesReceived != sentBytes) {
            throw new IOException("despawn event byte count mismatch: published " + sentBytes
                    + " vs client " + bytesReceived);
        }
        if (receivedSha256 != null && !receivedSha256.equals(publishedSha256)) {
            throw new IOException("despawn event SHA-256 mismatch: published " + publishedSha256
                    + " vs client " + receivedSha256);
        }
        StringJoiner ids = new StringJoiner(",");
        StringJoiner ticks = new StringJoiner(",");
        StringJoiner reasons = new StringJoiner(",");
        for (EntityDespawnEvent event : events) {
            ids.add(Long.toString(event.entityId()));
            ticks.add(Long.toString(event.simTick()));
            reasons.add(event.reason().name());
        }
        if (!ids.toString().equals(despawnIds)) {
            throw new IOException("despawn event id list mismatch: published " + ids + " vs client " + despawnIds);
        }
        if (!ticks.toString().equals(despawnTicks)) {
            throw new IOException("despawn event tick list mismatch: published " + ticks + " vs client " + despawnTicks);
        }
        if (!reasons.toString().equals(despawnReasons)) {
            throw new IOException("despawn event reason list mismatch: published " + reasons
                    + " vs client " + despawnReasons);
        }
    }

    private static Process startClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedEvents,
            Path clientLogPath) throws IOException {
        Path logParent = clientLogPath.getParent();
        if (logParent != null) {
            Files.createDirectories(logParent);
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IOException("java.home unavailable for launching despawn event client process");
        }
        String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path unavailable for launching despawn event client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(EntityDespawnEventSocketClient.class.getName());
        command.add("--host");
        command.add(host);
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--artifact");
        command.add(receiverArtifactPath.toAbsolutePath().normalize().toString());
        command.add("--receipt");
        command.add(clientReceiptPath.toAbsolutePath().normalize().toString());
        command.add("--expected-events");
        command.add(Integer.toString(expectedEvents));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(clientLogPath.toFile());
        return builder.start();
    }

    private static int waitForClientProcess(Process clientProcess, Path clientLogPath) throws IOException {
        if (clientProcess == null) {
            throw new IOException("despawn event client process was not started");
        }
        try {
            if (!clientProcess.waitFor(PROCESS_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                clientProcess.destroyForcibly();
                throw new IOException("timed out waiting for despawn event client process; log="
                        + clientLogPath.toAbsolutePath().normalize());
            }
            return clientProcess.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for despawn event client process", interrupted);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
            throw new IOException("no network interfaces available for off-loopback publish");
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
        throw new IOException("no non-loopback IPv4 address available for off-loopback publish");
    }

    private static void requireNonLoopbackSocketHost(String socketHost) throws IOException {
        Objects.requireNonNull(socketHost, "socketHost");
        InetAddress address = InetAddress.getByName(socketHost);
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            throw new IOException("off-loopback publish resolved to loopback host: " + socketHost);
        }
    }

    /**
     * Decode a committed real-engine {@code AWSS} capture, derive the despawn events,
     * publish them to a separate-JVM consumer over a socket, then write a manifest
     * describing the crossing. Arguments:
     * <pre>
     *   --source-awss &lt;path&gt;   committed AWSS capture to decode (required)
     *   --artifact &lt;path&gt;      where the receiver writes the despawn-event bytes (required)
     *   --receipt &lt;path&gt;       where the receiver writes its receipt (required)
     *   --log &lt;path&gt;           consumer process log (required)
     *   --manifest &lt;path&gt;      manifest JSON to write (required)
     *   --threshold &lt;float&gt;    destroyed-hull threshold (default 800.0)
     *   --loopback             bind loopback instead of off-loopback (default off-loopback)
     * </pre>
     */
    public static void main(String[] args) {
        try {
            Path sourceAwss = null;
            Path artifact = null;
            Path receipt = null;
            Path log = null;
            Path manifest = null;
            float threshold = EntityDespawnEventDetector.DEFAULT_DESTROYED_HULL_THRESHOLD;
            boolean loopback = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--source-awss" -> sourceAwss = Path.of(args[++i]);
                    case "--artifact" -> artifact = Path.of(args[++i]);
                    case "--receipt" -> receipt = Path.of(args[++i]);
                    case "--log" -> log = Path.of(args[++i]);
                    case "--manifest" -> manifest = Path.of(args[++i]);
                    case "--threshold" -> threshold = Float.parseFloat(args[++i]);
                    case "--loopback" -> loopback = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (sourceAwss == null || artifact == null || receipt == null || log == null || manifest == null) {
                throw new IllegalArgumentException(
                        "required: --source-awss --artifact --receipt --log --manifest [--threshold f] [--loopback]");
            }

            byte[] sourceBytes = Files.readAllBytes(sourceAwss);
            List<AuthoritativeWorldSnapshot> frames =
                    AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(sourceBytes));
            String sourceSha = sha256Hex(sourceBytes);

            Receipt result = loopback
                    ? publishLoopback(frames, threshold, artifact, receipt, log)
                    : publishOffLoopback(frames, threshold, artifact, receipt, log);

            writeManifest(manifest, sourceAwss, sourceSha, threshold, result);

            System.out.println("despawn-event publish OK: source_frames=" + result.sourceFrames()
                    + " events=" + result.eventsPublished()
                    + " host=" + result.socketHost() + " loopback=" + result.loopback()
                    + " bytes=" + result.bytesPublished()
                    + " sha256=" + result.publishedSha256());
            for (EntityDespawnEvent event : result.events()) {
                System.out.println("  despawn: entityId=" + event.entityId()
                        + " tick=" + event.simTick() + " reason=" + event.reason());
            }
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void writeManifest(
            Path manifestPath, Path sourceAwss, String sourceSha, float threshold, Receipt result)
            throws IOException {
        byte[] artifactBytes = Files.readAllBytes(result.receiverArtifactPath());
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"task\": \"entity-despawn-event-stream\",\n");
        json.append("  \"description\": \"Explicit server->client EntityDespawnEvent stream derived from a")
                .append(" committed real-engine AWSS roster capture and crossed off-loopback to a separate-JVM")
                .append(" consumer. The despawn events correspond to real-engine organic")
                .append(" roster transitions (each killed ship's last hull driven below the destroyed-hull")
                .append(" threshold before its stable entityId vanished).\",\n");
        json.append("  \"source_awss\": \"").append(jsonEscape(sourceAwss.toString().replace('\\', '/'))).append("\",\n");
        json.append("  \"source_awss_sha256\": \"").append(sourceSha).append("\",\n");
        json.append("  \"source_frames\": ").append(result.sourceFrames()).append(",\n");
        json.append("  \"destroyed_hull_threshold\": ").append(threshold).append(",\n");
        json.append("  \"socket_host\": \"").append(result.socketHost()).append("\",\n");
        json.append("  \"loopback\": ").append(result.loopback()).append(",\n");
        json.append("  \"events_published\": ").append(result.eventsPublished()).append(",\n");
        json.append("  \"frame_bytes\": ").append(EntityDespawnEventCodec.FRAME_BYTES).append(",\n");
        json.append("  \"wire_message_id\": \"").append(EntityDespawnEventCodec.MESSAGE_ID).append("\",\n");
        json.append("  \"wire_message_type_code\": ")
                .append(Integer.toUnsignedLong(EntityDespawnEventCodec.MESSAGE_TYPE_CODE)).append(",\n");
        json.append("  \"wire_schema_version\": ").append(EntityDespawnEventCodec.SCHEMA_VERSION).append(",\n");
        json.append("  \"artifact_bytes\": ").append(artifactBytes.length).append(",\n");
        json.append("  \"artifact_sha256\": \"").append(result.publishedSha256()).append("\",\n");
        json.append("  \"despawns\": [\n");
        List<EntityDespawnEvent> events = result.events();
        for (int i = 0; i < events.size(); i++) {
            EntityDespawnEvent event = events.get(i);
            json.append("    { \"entity_id\": ").append(event.entityId())
                    .append(", \"tick\": ").append(event.simTick())
                    .append(", \"reason\": \"").append(event.reason().name())
                    .append("\", \"reason_wire_code\": ").append(event.reason().wireCode()).append(" }");
            json.append(i + 1 < events.size() ? ",\n" : "\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        Path parent = manifestPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(manifestPath, json.toString());
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
