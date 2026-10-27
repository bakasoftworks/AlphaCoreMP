package cc.starsector.server.client;

import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec;
import cc.starsector.server.runtime.GameplayEdgeEventCodec;
import cc.starsector.server.runtime.PilotIntentFrame;
import cc.starsector.server.runtime.PilotIntentWireCodec;
import cc.starsector.server.runtime.PilotPortFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The K-synthetic-clients harness for the demo-program R5 clients ladder:
 * ONE external JVM hosting K scripted subscriber threads, each speaking the exact
 * {@link PilotIntentSocketClient} per-tick loop against its own
 * {@link cc.starsector.server.runtime.PilotIntentSocketServer} peer — read DOWN frames
 * (GPEE decode-and-discard, AWSS decoded through the REAL codec), answer each AWSS with one IDLE
 * {@link PilotIntentFrame} UP (no held controls, no fire — the ladder prices fan-out + decode, not
 * gameplay). Endpoints resolve from per-peer {@link PilotPortFile}s ({@code ladder-port-<i>.txt})
 * in {@code --ports-dir}; each thread polls for its file, connects, then loops until the server
 * closes the socket (clean EOF). One receipt carries per-client liveness for the L4 row
 * (UP decode liveness 100% per client). Fail-closed: a thread error is counted + named in the
 * receipt and the harness exits non-zero.
 */
public final class ClientLadderHarness {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 120_000;

    private ClientLadderHarness() {
    }

    private static final class ClientStats {
        volatile boolean connected;
        volatile int framesReceived;
        volatile int intentsSent;
        volatile int gpeeSkipped;
        volatile String error = "";
    }

    public static void main(String[] args) {
        Path portsDir = null;
        int count = -1;
        Path receiptPath = null;
        int portFileWaitSeconds = 60;
        try {
            for (int i = 0; i + 1 < args.length; i += 2) {
                switch (args[i]) {
                    case "--ports-dir" -> portsDir = Path.of(args[i + 1]);
                    case "--count" -> count = Integer.parseInt(args[i + 1]);
                    case "--receipt" -> receiptPath = Path.of(args[i + 1]);
                    case "--port-file-wait-sec" -> portFileWaitSeconds = Integer.parseInt(args[i + 1]);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (portsDir == null || count < 1 || receiptPath == null) {
                throw new IllegalArgumentException("required: --ports-dir --count --receipt [--port-file-wait-sec]");
            }
            ClientStats[] stats = new ClientStats[count];
            Thread[] threads = new Thread[count];
            for (int i = 0; i < count; i++) {
                final int idx = i;
                final Path portFile = portsDir.resolve("ladder-port-" + i + ".txt");
                final int waitSec = portFileWaitSeconds;
                stats[i] = new ClientStats();
                final ClientStats st = stats[i];
                threads[i] = new Thread(() -> runClient(portFile, waitSec, st), "ladder-client-" + idx);
                threads[i].setDaemon(false);
                threads[i].start();
            }
            for (Thread t : threads) {
                t.join();
            }
            boolean allConnected = true;
            boolean anyError = false;
            long totalFrames = 0L;
            for (ClientStats st : stats) {
                allConnected &= st.connected;
                anyError |= !st.error.isEmpty();
                totalFrames += st.framesReceived;
            }
            writeReceipt(receiptPath, count, stats, allConnected, totalFrames);
            System.out.println("client-ladder harness done: count=" + count
                    + " all_connected=" + allConnected + " total_frames=" + totalFrames
                    + " any_error=" + anyError);
            System.exit(allConnected && !anyError ? 0 : 1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    /** One subscriber: resolve the port file (polling), connect, loop the ping-pong until EOF. */
    private static void runClient(Path portFile, int portFileWaitSeconds, ClientStats st) {
        try {
            String host = null;
            int port = -1;
            long deadline = System.nanoTime() + portFileWaitSeconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                String[] endpoint = PilotPortFile.read(portFile.toString());
                if (endpoint != null) {
                    host = endpoint[0];
                    port = Integer.parseInt(endpoint[1]);
                    break;
                }
                Thread.sleep(200L);
            }
            if (host == null) {
                throw new IOException("port file never appeared/parsed: " + portFile);
            }
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                st.connected = true;
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                while (true) {
                    int magic = peekMagic(in);
                    if (magic == -1) {
                        break; // clean server close
                    }
                    if (magic == GameplayEdgeEventCodec.MAGIC) {
                        GameplayEdgeEventCodec.readEvent(in);
                        st.gpeeSkipped++;
                        continue;
                    }
                    if (magic != AuthoritativeWorldSnapshotCodec.MAGIC) {
                        throw new IOException("unexpected DOWN magic 0x" + Integer.toHexString(magic)
                                + " (the bench clients lane streams AWSS only)");
                    }
                    byte[] frameBytes = AuthoritativeWorldSnapshotCodec.readFrameBytes(in);
                    if (frameBytes == null) {
                        break; // clean end of stream
                    }
                    List<AuthoritativeWorldSnapshot> decoded =
                            AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(frameBytes));
                    if (decoded.isEmpty()) {
                        throw new IOException("AWSS frame decoded to zero snapshots");
                    }
                    AuthoritativeWorldSnapshot snap = decoded.get(0);
                    st.framesReceived++;
                    long ownId = snap.entities().isEmpty() ? 0L : snap.entities().get(0).entityId();
                    // IDLE intent: nothing held, nothing fired — the server reads exactly one UP
                    // frame per exchange; this answers it and prices the decode + answer path only.
                    // aim world point is don't-care on this idle lane (nothing fires; its assertions
                    // price the decode/answer path, not shot landing).
                    PilotIntentFrame intent = PilotIntentFrame.of(
                            ownId, (int) snap.simTick(), 0f, 0, /*fireHeld*/ false, 0, 0f, 0f, 0f);
                    ByteArrayOutputStream buf = new ByteArrayOutputStream(PilotIntentWireCodec.FRAME_BYTES);
                    PilotIntentWireCodec.writeFrame(buf, intent);
                    out.write(buf.toByteArray());
                    out.flush();
                    st.intentsSent++;
                }
            }
        } catch (Exception e) {
            st.error = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /** Peek the next frame's 4-byte magic without consuming it, or -1 at end-of-stream. */
    private static int peekMagic(BufferedInputStream in) throws IOException {
        in.mark(4);
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        in.reset();
        if ((b0 | b1 | b2 | b3) < 0) {
            return -1;
        }
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static void writeReceipt(
            Path receiptPath, int count, ClientStats[] stats, boolean allConnected, long totalFrames)
            throws IOException {
        Path parent = receiptPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        lines.add("harness_class=" + ClientLadderHarness.class.getName());
        lines.add("count=" + count);
        lines.add("all_connected=" + allConnected);
        lines.add("total_frames=" + totalFrames);
        for (int i = 0; i < count; i++) {
            ClientStats st = stats[i];
            lines.add("client_" + i + "_connected=" + st.connected);
            lines.add("client_" + i + "_frames=" + st.framesReceived);
            lines.add("client_" + i + "_intents=" + st.intentsSent);
            lines.add("client_" + i + "_gpee=" + st.gpeeSkipped);
            lines.add("client_" + i + "_error=" + st.error);
        }
        Files.write(receiptPath, lines,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
