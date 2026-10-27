package cc.starsector.client.editor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.fs.starfarer.api.Global;

import cc.starsector.client.PilotWatchLog;

/**
 * The loopback editor RESPONDER. Started
 * once from {@code onApplicationLoad}: a daemon thread binds {@code ServerSocket(0, 8, loopback)}
 * (random port, loopback only), writes {@code port|token[|nonce]} through the public
 * {@code writeTextFileToCommon("cc_editor_endpoint", …)} (⇒ {@code saves/common/cc_editor_endpoint.data},
 * the join window's hand-off) and accepts connections; each accepted socket is served on its own
 * short-lived daemon thread (a browser's idle pre-connect must never stall the others) with a
 * 5 s read timeout, at most {@value #MAX_IN_FLIGHT} in flight (over-cap ⇒ 503), oracle work
 * serialized under {@link EditorOracle#LOCK}. Proven idioms: a game-free probe
 * and an in-game drill.
 *
 * <p>Security: loopback bind, random
 * port, per-process {@code SecureRandom} token on EVERY request, bounded requests
 * ({@link HttpExchange}), no request-derived path anywhere, player text sanitized at every log
 * sink. Kill switch: {@code -Dcc.editor=off}. Classloader-B: {@code java.net} + allowed
 * {@code java.io} streams only, catch types {@code IOException} / {@code Throwable}.
 */
public final class EditorHost {
    static final int MAX_IN_FLIGHT = 8;
    static final int READ_TIMEOUT_MILLIS = 5000;
    static final String ENDPOINT_COMMON_NAME = "cc_editor_endpoint";

    /**
     * Row (f) law 2: the launch NONCE the kit's join-cc.bat
     * minted for THIS game and handed over as {@code -Dcc.editor.launch=<8 lowercase hex>}. It rides
     * the endpoint file as a third field so the join window binds to the game it launched, never to
     * whichever game on the box wrote the shared file last. Absent or malformed ⇒ null ⇒ the
     * two-field file (tooling launches, the arm's own windows) — the readers treat the field as
     * additive. Charset-gated here as well: a hostile -D can only cost the binding, never the token.
     */
    static String launchNonce() {
        String raw = System.getProperty("cc.editor.launch");
        return raw != null && raw.matches("[0-9a-f]{8}") ? raw : null;
    }

    /** The endpoint file's text: {@code port|token} or {@code port|token|nonce}. */
    static String endpointFileText(int port, String tok, String nonce) {
        return port + "|" + tok + (nonce == null ? "" : "|" + nonce);
    }

    private static volatile boolean started;
    private static volatile String token;
    private static volatile int port;
    private static volatile String status = "not started";
    private static volatile long startNanos;
    private static final AtomicInteger inFlight = new AtomicInteger();
    private static final AtomicInteger served = new AtomicInteger();

    private EditorHost() {
    }

    /** One-shot: start the responder thread; returns immediately (bind happens on the thread). */
    public static synchronized void startAtApplicationLoad() {
        if (started) {
            return;
        }
        started = true;
        startNanos = System.nanoTime();
        if ("off".equalsIgnoreCase(System.getProperty("cc.editor", "on"))) {
            status = "DISABLED (-Dcc.editor=off)";
            return;
        }
        final String minted = mintToken();
        token = minted;
        status = "starting";
        Thread t = new Thread(new Runnable() {
            public void run() {
                serve(minted);
            }
        }, "cc-editor-accept");
        t.setDaemon(true);
        t.start();
    }

    /** The load-log line. */
    public static String describe() {
        return status + (port > 0 ? " 127.0.0.1:" + port : "");
    }

    static long uptimeMs() {
        return startNanos == 0 ? 0 : (System.nanoTime() - startNanos) / 1000000L;
    }

    private static void serve(String tok) {
        ServerSocket server;
        try {
            server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            status = "BIND FAILED " + e.getClass().getSimpleName();
            PilotWatchLog.error("editor: loopback bind failed", e);
            return;
        }
        port = server.getLocalPort();
        status = "LISTENING";
        PilotWatchLog.info("editor: listening on " + server.getInetAddress().getHostAddress() + ":" + port
                + " (token " + tok.length() + " hex chars, per-process)");
        String nonce = launchNonce();
        try {
            Global.getSettings().writeTextFileToCommon(ENDPOINT_COMMON_NAME, endpointFileText(port, tok, nonce));
            PilotWatchLog.info("editor: endpoint file written (saves/common/" + ENDPOINT_COMMON_NAME + ".data"
                    + (nonce == null ? ", no launch nonce)" : ", launch nonce " + nonce + ")"));
        } catch (IOException | RuntimeException e) {
            status = "LISTENING, endpoint file FAILED";
            PilotWatchLog.error("editor: endpoint file write failed", e);
        }
        while (true) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                status = "accept loop ended: " + e.getClass().getSimpleName();
                PilotWatchLog.error("editor: accept failed; responder stopped", e);
                return;
            }
            if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
                inFlight.decrementAndGet();
                try {
                    s.setSoTimeout(READ_TIMEOUT_MILLIS);
                    HttpExchange.replyText(s.getOutputStream(), 503, "busy");
                } catch (IOException ignored) {
                    // the client went away
                } finally {
                    closeQuietly(s);
                }
                continue;
            }
            Thread worker = new Thread(new Runnable() {
                public void run() {
                    try {
                        handle(s, tok);
                    } finally {
                        inFlight.decrementAndGet();
                        closeQuietly(s);
                    }
                }
            }, "cc-editor-req-" + served.incrementAndGet());
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void handle(Socket s, String tok) {
        OutputStream out = null;
        try {
            s.setSoTimeout(READ_TIMEOUT_MILLIS);
            out = s.getOutputStream();
            HttpExchange req;
            try {
                req = HttpExchange.read(s.getInputStream());
            } catch (HttpExchange.Refused refused) {
                HttpExchange.replyText(out, refused.status, refused.getMessage());
                return;
            }
            EditorRoutes.handle(req, out, tok, port);
        } catch (IOException io) {
            // a closed or timed-out browser connection: nothing to answer
        } catch (Throwable fault) {
            PilotWatchLog.error("editor: request thread fault", fault);
            if (out != null) {
                try {
                    HttpExchange.replyText(out, 500, "internal error");
                } catch (IOException ignored) {
                    // the client went away
                }
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static String mintToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
