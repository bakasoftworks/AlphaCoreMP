package cc.starsector.server.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Objects;

/**
 * A real off-loopback TCP carrier for the faithful pilot lane — the
 * first slice of the live two-client 1v1. It binds a {@link ServerSocket} to a NON-loopback local
 * address (so bytes traverse the real NIC stack, the off-loopback sense),
 * connects a client {@link Socket} to it, and exposes {@link #roundTrip}, which writes a
 * {@link PilotIntentFrame} on the CLIENT end ({@link PilotIntentWireCodec}) and reads it back on
 * the SERVER end — i.e. the per-tick faithful intent is serialized, carried across a real socket,
 * and deserialized before the server applies it to the engine.
 *
 * <p>This colocates the write (client) and read (server) ends in one process so a single headless
 * gate can prove the wire bridge into a real {@code advanceInner}. The PRODUCT splits the two ends
 * across machines and adds the second ship/client, reusing the proven off-loopback transport
 * for the fanout direction; the codec and frame shape are
 * unchanged across that promotion.
 */
public final class PilotIntentSocketBridge implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int ACCEPT_JOIN_MILLIS = 10_000;

    private final ServerSocket serverSocket;
    private final Socket clientSocket;
    private final Socket serverSideSocket;
    private final OutputStream clientOut;
    private final InputStream serverIn;
    private final boolean offLoopback;
    private final String host;
    private final int port;

    private PilotIntentSocketBridge(
            ServerSocket serverSocket,
            Socket clientSocket,
            Socket serverSideSocket,
            boolean offLoopback,
            String host,
            int port) throws IOException {
        this.serverSocket = serverSocket;
        this.clientSocket = clientSocket;
        this.serverSideSocket = serverSideSocket;
        this.offLoopback = offLoopback;
        this.host = host;
        this.port = port;
        this.clientOut = new BufferedOutputStream(clientSocket.getOutputStream());
        this.serverIn = new BufferedInputStream(serverSideSocket.getInputStream());
    }

    /** Open an off-loopback connected socket pair (binds to a site-local NIC address if one exists). */
    public static PilotIntentSocketBridge open() throws IOException {
        InetAddress bind = firstNonLoopbackAddress();
        boolean offLoopback = bind != null;
        if (bind == null) {
            bind = InetAddress.getLoopbackAddress();
        }
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(bind, 0));
        int port = server.getLocalPort();
        String host = bind.getHostAddress();
        // Accept on a daemon thread so the same-thread connect() below cannot deadlock.
        final Socket[] accepted = new Socket[1];
        final IOException[] acceptFailure = new IOException[1];
        Thread acceptor = new Thread(() -> {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                accepted[0] = s;
            } catch (IOException e) {
                acceptFailure[0] = e;
            }
        }, "pilot-intent-bridge-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        Socket client = new Socket();
        client.setTcpNoDelay(true);
        try {
            client.connect(new InetSocketAddress(bind, port), CONNECT_TIMEOUT_MILLIS);
            acceptor.join(ACCEPT_JOIN_MILLIS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            closeQuietly(client);
            try { server.close(); } catch (IOException ignore) { }
            throw new IOException("pilot intent bridge accept interrupted", ie);
        }
        if (acceptFailure[0] != null) {
            closeQuietly(client);
            try { server.close(); } catch (IOException ignore) { }
            throw acceptFailure[0];
        }
        if (accepted[0] == null) {
            closeQuietly(client);
            try { server.close(); } catch (IOException ignore) { }
            throw new IOException("pilot intent bridge accept timed out");
        }
        return new PilotIntentSocketBridge(server, client, accepted[0], offLoopback, host, port);
    }

    /** Write {@code frame} on the client end and read it back on the server end (one real round-trip). */
    public PilotIntentFrame roundTrip(PilotIntentFrame frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        PilotIntentWireCodec.writeFrame(clientOut, frame);
        clientOut.flush();
        PilotIntentFrame decoded = PilotIntentWireCodec.readFrame(serverIn);
        if (decoded == null) {
            throw new IOException("pilot intent bridge server end closed mid-round-trip");
        }
        return decoded;
    }

    public boolean isOffLoopback() {
        return offLoopback;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        closeQuietly(clientSocket);
        closeQuietly(serverSideSocket);
        try { serverSocket.close(); } catch (IOException ignore) { }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignore) { }
        }
    }

    private static InetAddress firstNonLoopbackAddress() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a;
                    }
                }
            }
        } catch (IOException ignore) {
            // fall through to loopback
        }
        return null;
    }
}
