package cc.starsector.server.runtime;

import cc.starsector.server.client.CombatOutcomeEventSocketClient;
import cc.starsector.server.protocol.CombatOutcomeEvent;
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
 * Publishes the server-authoritative {@link CombatOutcomeEvent} stream onto a real
 * TCP socket, consumed by a separate-JVM {@link CombatOutcomeEventSocketClient}.
 *
 *
 * <p>This is the live-consumer crossing for the new
 * {@link cc.starsector.server.protocol.ServerCombatOutcomeEventMessage}: each match
 * outcome (derived server-side by {@link CombatOutcomeEventDetector}) is encoded with the
 * registry-bound {@link CombatOutcomeEventCodec} and written+flushed onto the socket
 * to a second process that decodes it. The crossing is fail-closed and byte-checked:
 * the publisher cross-verifies the receiver's drained bytes for byte-identity and the
 * receipt's event count / winning sides / ticks against what it published.
 *
 * <p>Mirrors {@link EntityDespawnEventSocketPublisher}; like that despawn publisher
 * (which derives its events from a real-engine roster capture), the outcome is
 * roster-derived: at HEAD the live serve loop ({@code PersistentDedicatedCombatServer}'s
 * match-outcome loop) feeds the detector from {@code EngineCombatBinding.combatOutcomeFromRosterOrNull()}
 * — the authoritative producer. The engine's {@code getWinningSideId()}
 * read was proven unreliable headless and is retained as diagnostic/parity only,
 * NOT the producer; the earlier "real-engine winning-side observation is a still-PENDING
 * follow-up" framing was superseded by that roster-derived wire. This publisher is
 * the wire+consumer landing site the already-derived outcome flows through.
 */
public final class CombatOutcomeEventSocketPublisher {

    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
    private static final long PROCESS_JOIN_TIMEOUT_MILLIS = 60_000L;

    private CombatOutcomeEventSocketPublisher() {
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
            List<CombatOutcomeEvent> events) {
    }

    public static Receipt publishLoopback(
            List<CombatOutcomeEvent> events,
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
            List<CombatOutcomeEvent> events,
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
            List<CombatOutcomeEvent> events,
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
            throw new IllegalArgumentException("combat outcome publish requires at least one event");
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
                for (CombatOutcomeEvent event : events) {
                    ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
                    CombatOutcomeEventCodec.writeEvent(frameBytes, event);
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
            throw new IOException("combat outcome client process exited " + exitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }

        byte[] sent = publishedBytes.toByteArray();
        byte[] receiverBytes = Files.readAllBytes(receiverArtifactPath);
        if (!Arrays.equals(sent, receiverBytes)) {
            throw new IOException("combat outcome receiver bytes differ from published bytes");
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
            Path receiptPath, List<CombatOutcomeEvent> events, int sentBytes, String publishedSha256)
            throws IOException {
        int eventsReceived = -1;
        long bytesReceived = -1L;
        String receivedSha256 = null;
        String winningSides = null;
        String decidedTicks = null;
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
                case "winning_sides" -> winningSides = value;
                case "decided_ticks" -> decidedTicks = value;
                default -> { /* ignore */ }
            }
        }
        if (eventsReceived != events.size()) {
            throw new IOException("combat outcome event count mismatch: published " + events.size()
                    + " vs client " + eventsReceived);
        }
        if (bytesReceived != sentBytes) {
            throw new IOException("combat outcome byte count mismatch: published " + sentBytes
                    + " vs client " + bytesReceived);
        }
        if (receivedSha256 != null && !receivedSha256.equals(publishedSha256)) {
            throw new IOException("combat outcome SHA-256 mismatch: published " + publishedSha256
                    + " vs client " + receivedSha256);
        }
        StringJoiner sides = new StringJoiner(",");
        StringJoiner ticks = new StringJoiner(",");
        for (CombatOutcomeEvent event : events) {
            sides.add(Integer.toString(event.winningSideId()));
            ticks.add(Long.toString(event.simTick()));
        }
        if (!sides.toString().equals(winningSides)) {
            throw new IOException("combat outcome winning-side list mismatch: published " + sides
                    + " vs client " + winningSides);
        }
        if (!ticks.toString().equals(decidedTicks)) {
            throw new IOException("combat outcome tick list mismatch: published " + ticks
                    + " vs client " + decidedTicks);
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
            throw new IOException("java.home unavailable for launching combat outcome client process");
        }
        String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path unavailable for launching combat outcome client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(CombatOutcomeEventSocketClient.class.getName());
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
            throw new IOException("combat outcome client process was not started");
        }
        try {
            if (!clientProcess.waitFor(PROCESS_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                clientProcess.destroyForcibly();
                throw new IOException("timed out waiting for combat outcome client process; log="
                        + clientLogPath.toAbsolutePath().normalize());
            }
            return clientProcess.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for combat outcome client process", interrupted);
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
}
