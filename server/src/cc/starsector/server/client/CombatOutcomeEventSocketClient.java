package cc.starsector.server.client;

import cc.starsector.server.protocol.CombatOutcomeEvent;
import cc.starsector.server.runtime.CombatOutcomeEventCodec;
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
 * Separate-JVM consumer for the server-&gt;client {@link CombatOutcomeEvent} stream:
 * the live consumer of the new
 * {@link cc.starsector.server.protocol.ServerCombatOutcomeEventMessage} threaded
 * through the registry. A process with no shared memory connects to the publisher
 * socket, drains every byte, decodes each frame with the real product
 * {@link CombatOutcomeEventCodec}, and writes a receipt naming exactly which side
 * won, whether the engine's render-coupled combatOver flag was set, and at which
 * tick the match was decided.
 *
 * <p>Fail-closed: any decode/byte mismatch exits non-zero with a diagnostic on
 * stderr and writes no partial receipt.
 */
public final class CombatOutcomeEventSocketClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;

    private CombatOutcomeEventSocketClient() {
    }

    public record Receipt(
            String host,
            int port,
            long bytesReceived,
            int eventsReceived,
            String receivedSha256,
            List<CombatOutcomeEvent> events) {
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
            System.out.println("combat-outcome-event socket client OK: events=" + receipt.eventsReceived()
                    + " bytes=" + receipt.bytesReceived()
                    + " outcomes=" + describe(receipt.events()));
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Connect, drain all bytes to {@code artifactPath}, and decode the outcome events. */
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

        List<CombatOutcomeEvent> events =
                CombatOutcomeEventCodec.readEvents(new ByteArrayInputStream(received));
        if (expectedEvents >= 0 && events.size() != expectedEvents) {
            throw new IOException("combat outcome event socket client count mismatch: expected "
                    + expectedEvents + ", got " + events.size());
        }
        return new Receipt(host, port, received.length, events.size(), sha256Hex(received), events);
    }

    private static String describe(List<CombatOutcomeEvent> events) {
        StringJoiner joiner = new StringJoiner(",");
        for (CombatOutcomeEvent event : events) {
            joiner.add("side" + event.winningSideId() + "@" + event.simTick()
                    + (event.combatOver() ? ":over" : ":decided"));
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
        lines.add("client_class=" + CombatOutcomeEventSocketClient.class.getName());
        lines.add("host=" + receipt.host());
        lines.add("port=" + receipt.port());
        lines.add("bytes_received=" + receipt.bytesReceived());
        lines.add("events_received=" + receipt.eventsReceived());
        lines.add("received_sha256=" + receipt.receivedSha256());
        StringJoiner sides = new StringJoiner(",");
        StringJoiner overs = new StringJoiner(",");
        StringJoiner ticks = new StringJoiner(",");
        for (CombatOutcomeEvent event : receipt.events()) {
            sides.add(Integer.toString(event.winningSideId()));
            overs.add(Boolean.toString(event.combatOver()));
            ticks.add(Long.toString(event.simTick()));
        }
        lines.add("winning_sides=" + sides);
        lines.add("combat_over_flags=" + overs);
        lines.add("decided_ticks=" + ticks);
        Files.write(receiptPath, lines,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
