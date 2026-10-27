package cc.starsector.server.client;

import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.runtime.EntityDespawnEventCodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Separate-JVM consumer for the server-&gt;client {@link EntityDespawnEvent} stream:
 * the live consumer of the new
 * {@link cc.starsector.server.protocol.ServerEntityDespawnEventMessage} threaded
 * through the registry. A process with no shared memory connects to the publisher
 * socket, drains every byte, decodes each frame with the real product
 * {@link EntityDespawnEventCodec}, and writes a receipt naming exactly which
 * {@code entityId}s despawned, at which tick, for which reason.
 *
 * <p>Fail-closed: any decode/byte mismatch exits non-zero with a diagnostic on
 * stderr and writes no partial receipt.
 */
public final class EntityDespawnEventSocketClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;

    private EntityDespawnEventSocketClient() {
    }

    public record Receipt(
            String host,
            int port,
            long bytesReceived,
            int eventsReceived,
            String receivedSha256,
            List<EntityDespawnEvent> events) {
    }

    public static void main(String[] args) {
        try {
            String host = null;
            int port = -1;
            Path artifactPath = null;
            Path receiptPath = null;
            int expectedEvents = -1;
            for (int i = 0; i + 1 < args.length; i += 2) {
                switch (args[i]) {
                    case "--host" -> host = args[i + 1];
                    case "--port" -> port = Integer.parseInt(args[i + 1]);
                    case "--artifact" -> artifactPath = Path.of(args[i + 1]);
                    case "--receipt" -> receiptPath = Path.of(args[i + 1]);
                    case "--expected-events" -> expectedEvents = Integer.parseInt(args[i + 1]);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (host == null || port < 0 || artifactPath == null || receiptPath == null) {
                throw new IllegalArgumentException(
                        "required: --host --port --artifact --receipt [--expected-events]");
            }
            Receipt receipt = receive(host, port, artifactPath, expectedEvents);
            writeReceipt(receiptPath, receipt);
            System.out.println("despawn-event socket client OK: events=" + receipt.eventsReceived()
                    + " bytes=" + receipt.bytesReceived()
                    + " despawns=" + describe(receipt.events()));
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Connect, drain all bytes to {@code artifactPath}, and decode the despawn events. */
    public static Receipt receive(String host, int port, Path artifactPath, int expectedEvents)
            throws IOException {
        Path parent = artifactPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] received;
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            received = socket.getInputStream().readAllBytes();
        }
        Files.write(artifactPath, received,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        List<EntityDespawnEvent> events =
                EntityDespawnEventCodec.readEvents(new ByteArrayInputStream(received));
        if (expectedEvents >= 0 && events.size() != expectedEvents) {
            throw new IOException("despawn event socket client count mismatch: expected "
                    + expectedEvents + ", got " + events.size());
        }
        return new Receipt(host, port, received.length, events.size(), sha256Hex(received), events);
    }

    private static String describe(List<EntityDespawnEvent> events) {
        StringJoiner joiner = new StringJoiner(",");
        for (EntityDespawnEvent event : events) {
            joiner.add(event.entityId() + "@" + event.simTick() + ":" + event.reason());
        }
        return joiner.toString();
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

    private static void writeReceipt(Path receiptPath, Receipt receipt) throws IOException {
        Path parent = receiptPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        lines.add("client_class=" + EntityDespawnEventSocketClient.class.getName());
        lines.add("host=" + receipt.host());
        lines.add("port=" + receipt.port());
        lines.add("bytes_received=" + receipt.bytesReceived());
        lines.add("events_received=" + receipt.eventsReceived());
        lines.add("received_sha256=" + receipt.receivedSha256());
        StringJoiner ids = new StringJoiner(",");
        StringJoiner ticks = new StringJoiner(",");
        StringJoiner reasons = new StringJoiner(",");
        for (EntityDespawnEvent event : receipt.events()) {
            ids.add(Long.toString(event.entityId()));
            ticks.add(Long.toString(event.simTick()));
            reasons.add(event.reason().name());
        }
        lines.add("despawn_ids=" + ids);
        lines.add("despawn_ticks=" + ticks);
        lines.add("despawn_reasons=" + reasons);
        Files.write(receiptPath, lines,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
