package cc.starsector.server.runtime;

import cc.starsector.server.client.GameplayEdgeEventSocketClient;
import cc.starsector.server.protocol.GameplayEdgeEvent;
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
 * Publishes a server-authoritative {@link GameplayEdgeEvent} stream onto a real
 * TCP socket, consumed by a separate-JVM {@link GameplayEdgeEventSocketClient}
 * (the despawn-pattern wire trio).
 *
 *
 * <p>This is the live-consumer crossing for the new
 * {@link cc.starsector.server.protocol.ServerGameplayEdgeEventMessage}: each event
 * is encoded with the registry-bound {@link GameplayEdgeEventCodec} and
 * written+flushed onto the socket to a second process that decodes it. The
 * crossing is fail-closed and byte-checked: the publisher cross-verifies the
 * receiver's drained bytes for byte-identity and the receipt's event count /
 * ticks / ids / kinds / phases / mounts / aims against what it published.
 *
 * <p>{@link #main} grounds it in real engine bytes without relaunching the game:
 * it reads a recorded B1 {@code gameplay-events.ndjson} (the byte-stable
 * {@link GameplayEdgeEventNdjsonLog} the real-engine detector wrote — e.g. the
 * B1 G4 first real-engine discharge edges)
 * and publishes those events off-loopback. The edges on the wire therefore
 * correspond to engine-accepted post-step state transitions, not synthetic data.
 */
public final class GameplayEdgeEventSocketPublisher {

    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
    private static final long PROCESS_JOIN_TIMEOUT_MILLIS = 60_000L;

    private GameplayEdgeEventSocketPublisher() {
    }

    public record Receipt(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            String socketHost,
            int socketPort,
            boolean loopback,
            int eventsPublished,
            long bytesPublished,
            long receiverBytes,
            String publishedSha256,
            List<GameplayEdgeEvent> events) {
    }

    public static Receipt publishLoopback(
            List<GameplayEdgeEvent> events,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        return publish(
                events,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                InetAddress.getLoopbackAddress(),
                true);
    }

    public static Receipt publishOffLoopback(
            List<GameplayEdgeEvent> events,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        InetAddress bindAddress = requireNonLoopbackIpv4Address();
        Receipt receipt = publish(
                events,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                bindAddress,
                false);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    private static Receipt publish(
            List<GameplayEdgeEvent> events,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            InetAddress bindAddress,
            boolean loopback) throws IOException {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(clientLogPath, "clientLogPath");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("gameplay edge event publish requires at least one event");
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
                for (GameplayEdgeEvent event : events) {
                    byte[] encoded = GameplayEdgeEventCodec.toBytes(event);
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
            throw new IOException("gameplay edge event client process exited " + exitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }

        byte[] sent = publishedBytes.toByteArray();
        byte[] receiverBytes = Files.readAllBytes(receiverArtifactPath);
        if (!Arrays.equals(sent, receiverBytes)) {
            throw new IOException("gameplay edge event receiver bytes differ from published bytes");
        }
        String publishedSha256 = sha256Hex(sent);
        verifyClientReceipt(clientReceiptPath, events, sent.length, publishedSha256);

        return new Receipt(
                receiverArtifactPath,
                clientReceiptPath,
                bindAddress.getHostAddress(),
                port,
                loopback,
                events.size(),
                sent.length,
                receiverBytes.length,
                publishedSha256,
                events);
    }

    private static void verifyClientReceipt(
            Path receiptPath, List<GameplayEdgeEvent> events, int sentBytes, String publishedSha256)
            throws IOException {
        int eventsReceived = -1;
        long bytesReceived = -1L;
        String receivedSha256 = null;
        String eventTicks = null;
        String eventEntityIds = null;
        String eventKinds = null;
        String eventPhases = null;
        String eventMounts = null;
        String eventAims = null;
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
                case "event_ticks" -> eventTicks = value;
                case "event_entity_ids" -> eventEntityIds = value;
                case "event_kinds" -> eventKinds = value;
                case "event_phases" -> eventPhases = value;
                case "event_mounts" -> eventMounts = value;
                case "event_aims" -> eventAims = value;
                default -> { /* ignore */ }
            }
        }
        if (eventsReceived != events.size()) {
            throw new IOException("gameplay edge event count mismatch: published " + events.size()
                    + " vs client " + eventsReceived);
        }
        if (bytesReceived != sentBytes) {
            throw new IOException("gameplay edge event byte count mismatch: published " + sentBytes
                    + " vs client " + bytesReceived);
        }
        if (receivedSha256 != null && !receivedSha256.equals(publishedSha256)) {
            throw new IOException("gameplay edge event SHA-256 mismatch: published " + publishedSha256
                    + " vs client " + receivedSha256);
        }
        StringJoiner ticks = new StringJoiner(",");
        StringJoiner ids = new StringJoiner(",");
        StringJoiner kinds = new StringJoiner(",");
        StringJoiner phases = new StringJoiner(",");
        StringJoiner mounts = new StringJoiner(",");
        StringJoiner aims = new StringJoiner(",");
        for (GameplayEdgeEvent event : events) {
            ticks.add(Long.toString(event.simTick()));
            ids.add(Long.toString(event.entityId()));
            kinds.add(event.kind().name());
            phases.add(event.phase().name());
            mounts.add(Integer.toString(event.mountIndex()));
            aims.add(GameplayEdgeEventSocketClient.aimText(event));
        }
        requireFieldMatch("tick", ticks.toString(), eventTicks);
        requireFieldMatch("entity id", ids.toString(), eventEntityIds);
        requireFieldMatch("kind", kinds.toString(), eventKinds);
        requireFieldMatch("phase", phases.toString(), eventPhases);
        requireFieldMatch("mount", mounts.toString(), eventMounts);
        requireFieldMatch("aim", aims.toString(), eventAims);
    }

    private static void requireFieldMatch(String label, String published, String received)
            throws IOException {
        if (!published.equals(received)) {
            throw new IOException("gameplay edge event " + label + " list mismatch: published "
                    + published + " vs client " + received);
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
            throw new IOException("java.home unavailable for launching gameplay edge event client process");
        }
        String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path unavailable for launching gameplay edge event client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(GameplayEdgeEventSocketClient.class.getName());
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
            throw new IOException("gameplay edge event client process was not started");
        }
        try {
            if (!clientProcess.waitFor(PROCESS_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                clientProcess.destroyForcibly();
                throw new IOException("timed out waiting for gameplay edge event client process; log="
                        + clientLogPath.toAbsolutePath().normalize());
            }
            return clientProcess.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for gameplay edge event client process", interrupted);
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
     * Read a recorded real-engine B1 {@code gameplay-events.ndjson}, publish its
     * events to a separate-JVM consumer over a socket, then write a manifest
     * describing the crossing. Arguments:
     * <pre>
     *   --source-events &lt;path&gt;  recorded NDJSON event log to read (required)
     *   --artifact &lt;path&gt;       where the receiver writes the edge-event bytes (required)
     *   --receipt &lt;path&gt;        where the receiver writes its receipt (required)
     *   --log &lt;path&gt;            consumer process log (required)
     *   --manifest &lt;path&gt;       manifest JSON to write (required)
     *   --loopback              bind loopback instead of off-loopback (default off-loopback)
     * </pre>
     */
    public static void main(String[] args) {
        try {
            Path sourceEvents = null;
            Path artifact = null;
            Path receipt = null;
            Path log = null;
            Path manifest = null;
            boolean loopback = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--source-events" -> sourceEvents = Path.of(args[++i]);
                    case "--artifact" -> artifact = Path.of(args[++i]);
                    case "--receipt" -> receipt = Path.of(args[++i]);
                    case "--log" -> log = Path.of(args[++i]);
                    case "--manifest" -> manifest = Path.of(args[++i]);
                    case "--loopback" -> loopback = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (sourceEvents == null || artifact == null || receipt == null || log == null || manifest == null) {
                throw new IllegalArgumentException(
                        "required: --source-events --artifact --receipt --log --manifest [--loopback]");
            }

            byte[] sourceBytes = Files.readAllBytes(sourceEvents);
            List<GameplayEdgeEvent> events = GameplayEdgeEventNdjsonLog.readAll(sourceEvents);
            String sourceSha = sha256Hex(sourceBytes);

            Receipt result = loopback
                    ? publishLoopback(events, artifact, receipt, log)
                    : publishOffLoopback(events, artifact, receipt, log);

            writeManifest(manifest, sourceEvents, sourceSha, result);

            System.out.println("gameplay-edge-event publish OK: source_events=" + result.eventsPublished()
                    + " host=" + result.socketHost() + " loopback=" + result.loopback()
                    + " bytes=" + result.bytesPublished()
                    + " sha256=" + result.publishedSha256());
            for (GameplayEdgeEvent event : result.events()) {
                System.out.println("  edge: entityId=" + event.entityId()
                        + " tick=" + event.simTick() + " kind=" + event.kind()
                        + " phase=" + event.phase() + " mount=" + event.mountIndex()
                        + " aim=" + GameplayEdgeEventSocketClient.aimText(event));
            }
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void writeManifest(
            Path manifestPath, Path sourceEvents, String sourceSha, Receipt result)
            throws IOException {
        byte[] artifactBytes = Files.readAllBytes(result.receiverArtifactPath());
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"task\": \"gameplay-edge-event-stream\",\n");
        json.append("  \"description\": \"B2 wire trio for the tick-stamped gameplay edge events")
                .append(": a recorded real-engine B1 NDJSON event log")
                .append(" (engine-accepted post-step discharge/window edges) re-encoded with the")
                .append(" registry-bound GameplayEdgeEventCodec and crossed off-loopback to a")
                .append(" separate-JVM consumer, byte-verified end to end.\",\n");
        json.append("  \"source_events\": \"").append(jsonEscape(sourceEvents.toString().replace('\\', '/'))).append("\",\n");
        json.append("  \"source_events_sha256\": \"").append(sourceSha).append("\",\n");
        json.append("  \"socket_host\": \"").append(result.socketHost()).append("\",\n");
        json.append("  \"loopback\": ").append(result.loopback()).append(",\n");
        json.append("  \"events_published\": ").append(result.eventsPublished()).append(",\n");
        json.append("  \"frame_bytes\": ").append(GameplayEdgeEventCodec.FRAME_BYTES).append(",\n");
        json.append("  \"wire_message_id\": \"").append(GameplayEdgeEventCodec.MESSAGE_ID).append("\",\n");
        json.append("  \"wire_message_type_code\": ")
                .append(Integer.toUnsignedLong(GameplayEdgeEventCodec.MESSAGE_TYPE_CODE)).append(",\n");
        json.append("  \"wire_schema_version\": ").append(GameplayEdgeEventCodec.SCHEMA_VERSION).append(",\n");
        json.append("  \"artifact_bytes\": ").append(artifactBytes.length).append(",\n");
        json.append("  \"artifact_sha256\": \"").append(result.publishedSha256()).append("\",\n");
        json.append("  \"events\": [\n");
        List<GameplayEdgeEvent> events = result.events();
        for (int i = 0; i < events.size(); i++) {
            GameplayEdgeEvent event = events.get(i);
            json.append("    { \"sim_tick\": ").append(event.simTick())
                    .append(", \"entity_id\": ").append(event.entityId())
                    .append(", \"kind\": \"").append(event.kind().name())
                    .append("\", \"phase\": \"").append(event.phase().name())
                    .append("\", \"mount_index\": ").append(event.mountIndex())
                    .append(", \"aim_facing_deg\": ").append(GameplayEdgeEventSocketClient.aimText(event))
                    .append(" }");
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
