package cc.starsector.server.client;

import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.runtime.GameplayEdgeEventCodec;
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
 * Separate-JVM consumer for the server-&gt;client {@link GameplayEdgeEvent} stream:
 * the live consumer of the new
 * {@link cc.starsector.server.protocol.ServerGameplayEdgeEventMessage} threaded
 * through the registry, mirroring {@link EntityDespawnEventSocketClient}. A process
 * with no shared memory connects to the publisher socket, drains every byte,
 * decodes each frame with the real product {@link GameplayEdgeEventCodec}, and
 * writes a receipt naming exactly which edges happened — tick, entity, kind,
 * phase, mount, aim — for the publisher to cross-verify.
 *
 * <p>Fail-closed: any decode/byte mismatch exits non-zero with a diagnostic on
 * stderr and writes no partial receipt.
 */
public final class GameplayEdgeEventSocketClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;

    private GameplayEdgeEventSocketClient() {
    }

    public record Receipt(
            String host,
            int port,
            long bytesReceived,
            int eventsReceived,
            String receivedSha256,
            List<GameplayEdgeEvent> events) {
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
            System.out.println("gameplay-edge-event socket client OK: events=" + receipt.eventsReceived()
                    + " bytes=" + receipt.bytesReceived()
                    + " edges=" + describe(receipt.events()));
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Connect, drain all bytes to {@code artifactPath}, and decode the edge events. */
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

        List<GameplayEdgeEvent> events =
                GameplayEdgeEventCodec.readEvents(new ByteArrayInputStream(received));
        if (expectedEvents >= 0 && events.size() != expectedEvents) {
            throw new IOException("gameplay edge event socket client count mismatch: expected "
                    + expectedEvents + ", got " + events.size());
        }
        return new Receipt(host, port, received.length, events.size(), sha256Hex(received), events);
    }

    /** Deterministic per-event aim text: NDJSON convention ({@code null} for the NaN fail-soft marker). */
    public static String aimText(GameplayEdgeEvent event) {
        return Float.isNaN(event.aimFacingDeg()) ? "null" : Float.toString(event.aimFacingDeg());
    }

    private static String describe(List<GameplayEdgeEvent> events) {
        StringJoiner joiner = new StringJoiner(",");
        for (GameplayEdgeEvent event : events) {
            joiner.add(event.entityId() + "@" + event.simTick() + ":" + event.kind()
                    + "/" + event.phase() + "/m" + event.mountIndex());
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
        lines.add("client_class=" + GameplayEdgeEventSocketClient.class.getName());
        lines.add("host=" + receipt.host());
        lines.add("port=" + receipt.port());
        lines.add("bytes_received=" + receipt.bytesReceived());
        lines.add("events_received=" + receipt.eventsReceived());
        lines.add("received_sha256=" + receipt.receivedSha256());
        StringJoiner ticks = new StringJoiner(",");
        StringJoiner ids = new StringJoiner(",");
        StringJoiner kinds = new StringJoiner(",");
        StringJoiner phases = new StringJoiner(",");
        StringJoiner mounts = new StringJoiner(",");
        StringJoiner aims = new StringJoiner(",");
        for (GameplayEdgeEvent event : receipt.events()) {
            ticks.add(Long.toString(event.simTick()));
            ids.add(Long.toString(event.entityId()));
            kinds.add(event.kind().name());
            phases.add(event.phase().name());
            mounts.add(Integer.toString(event.mountIndex()));
            aims.add(aimText(event));
        }
        lines.add("event_ticks=" + ticks);
        lines.add("event_entity_ids=" + ids);
        lines.add("event_kinds=" + kinds);
        lines.add("event_phases=" + phases);
        lines.add("event_mounts=" + mounts);
        lines.add("event_aims=" + aims);
        Files.write(receiptPath, lines,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
