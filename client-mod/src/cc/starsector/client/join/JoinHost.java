package cc.starsector.client.join;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import cc.starsector.client.editor.HttpExchange;

/**
 * The join WINDOW's self-hosted loopback responder (GUI v2).
 * A COPY of the editor's MECHANISM ({@code EditorHost}: {@code ServerSocket(0, 8,
 * loopback)}, a per-process {@code SecureRandom} token, one short-lived daemon thread per accepted socket,
 * a 5 s read timeout, at most {@value #MAX_IN_FLIGHT} in flight with 503 over the cap) — never
 * {@code EditorHost} the class (it needs {@code Global}, game-JVM only) and never
 * {@code com.sun.net.httpserver} (absent from the 15-module kit JRE). Pure {@code java.base} plus the
 * editor's {@link HttpExchange} parser/replier — a join→editor class ref is one-way-safe under
 * the classloader-B deny check ({@code join/**} is exempt; the editor package is not).
 *
 * <p>Differences from the editor host, all forced by the process it runs in (the window JVM the kit's
 * {@code join-cc.bat} starts BEFORE any game): the bind is SYNCHRONOUS ({@link #start} throws on a bind
 * failure so the caller can fall back to the Swing form); the endpoint
 * file ({@link #endpointFile}, under {@code %LOCALAPPDATA%}) = {@code port|token|nonce} is written with
 * {@code java.nio.file} (no {@code Global}) and deleted on {@link #close}; the token is 64 hex chars
 * (32 bytes) per process; the log sink is {@code System.err} (the window has no game sidelog).
 *
 * <p>Security: loopback bind, random port, the token on
 * EVERY request as the first path segment, bounded requests ({@link HttpExchange}), no request-derived
 * path anywhere, the match password never in a URL, a log line never carrying player text or a secret.
 */
public final class JoinHost {
    static final int MAX_IN_FLIGHT = 8;
    static final int READ_TIMEOUT_MILLIS = 5000;
    /** The endpoint file's name up to its per-install tag ({@link #endpointFile}). */
    static final String ENDPOINT_FILE_PREFIX = "cc_join_endpoint-";
    /** Token width in hex chars (32 random bytes); {@link #parseEndpoint} gates on it. */
    static final int TOKEN_HEX_CHARS = 64;

    /** The route table the host hands every parsed request to (the editor's {@code EditorRoutes.handle} shape). */
    interface Router {
        void handle(HttpExchange req, OutputStream out, String token, int port) throws IOException;
    }

    private final Router router;
    private final Path endpointFile;
    private final String nonce;
    private final String token;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger served = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile int port;
    private volatile boolean closed;

    JoinHost(Router router, Path endpointFile, String nonce) {
        this.router = router;
        this.endpointFile = endpointFile;
        this.nonce = nonce;
        this.token = mintToken();
    }

    /**
     *
     * {@code %LOCALAPPDATA%\cc-starsector\join\cc_join_endpoint-<8 hex>.data} — the user's own non-roaming profile,
     * never the install folder (another local account may write there, and a planted {@code port|token} would steer
     * the single-instance re-open). {@code <8 hex>} = {@link #installTag} of THIS install, so a second install's window
     * never re-opens the first one's page. {@code LOCALAPPDATA} unset, blank or not absolute ⇒ null: NO file and no
     * single-instance check — never a fallback into the install or {@code %TEMP%}. The editor's
     * {@code cc_editor_endpoint.data} stays in {@code saves/common} (its writer is in-game code). Script twins with the
     * same derivation live in the dev tooling.
     */
    static Path endpointFile(Path modDir) {
        return endpointFile(System.getenv("LOCALAPPDATA"), JoinVoiceSettings.installRoot(modDir));
    }

    /** {@link #endpointFile(Path)}, pure over the env value (selftested). */
    static Path endpointFile(String localAppData, Path installRoot) {
        Path base = JoinSettings.absoluteEnvDir(localAppData);
        return base == null ? null : base.resolve(JoinSettings.DIR_NAME).resolve("join")
                .resolve(ENDPOINT_FILE_PREFIX + installTag(installRoot) + ".data");
    }

    /** The first 4 bytes, lower-case hex, of SHA-256 over the UTF-8 bytes of the install root's absolute, normalized,
     *  {@code Locale.ROOT} lower-cased path string (the scripts: {@code GetFullPath(..).TrimEnd('\').ToLowerInvariant()}). */
    static String installTag(Path installRoot) {
        String key = installRoot.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        byte[] d;
        try {
            d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", absent);
        }
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 4; i++) {
            sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16)).append(Character.forDigit(d[i] & 0xF, 16));
        }
        return sb.toString();
    }

    /** The endpoint file's text: {@code port|token} or {@code port|token|nonce} (the editor's shape). */
    static String endpointFileText(int port, String tok, String nonce) {
        return port + "|" + tok + (nonce == null ? "" : "|" + nonce);
    }

    /**
     * Pure (selftested): the base URL {@code http://127.0.0.1:<port>/<token>/} out of an endpoint file's text,
     * or null when the text is not ours (port out of range, a token that is not {@value #TOKEN_HEX_CHARS} hex
     * chars, a malformed nonce). Used by the single-instance check: a second window pings the
     * file's port with the file's token; an answer means the first window is live.
     */
    static String parseEndpoint(String text) {
        if (text == null) {
            return null;
        }
        String[] fields = text.trim().split("\\|", -1);
        if (fields.length < 2 || fields.length > 3) {
            return null;
        }
        int p;
        try {
            p = Integer.parseInt(fields[0].trim());
        } catch (NumberFormatException bad) {
            return null;
        }
        String tok = fields[1].trim();
        if (p < 1 || p > 65535 || !tok.matches("[0-9a-f]{" + TOKEN_HEX_CHARS + "}")) {
            return null;
        }
        if (fields.length == 3 && !fields[2].trim().matches("[0-9a-f]{8}")) {
            return null;
        }
        return "http://127.0.0.1:" + p + "/" + tok + "/";
    }

    /**
     * Bind (loopback, random port), write the endpoint file, start the accept thread. Throws on a bind
     * failure — the caller's cue for the Swing fallback; an endpoint-file failure is logged, not fatal (the
     * browser is opened on the URL this method's caller reads from {@link #baseUrl}, not from the file).
     */
    synchronized int start() throws IOException {
        if (server != null) {
            return port;
        }
        ServerSocket s = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        server = s;
        port = s.getLocalPort();
        JoinBrowserUi.log("join-host: listening on " + s.getInetAddress().getHostAddress() + ":" + port
                + " (token " + token.length() + " hex chars, per-process)");
        // A null file (no LOCALAPPDATA) writes nothing - JoinBrowserUi.run logged
        // it; a failure logs the class only (the exception text names the profile path)
        if (endpointFile != null) {
            try {
                Files.createDirectories(endpointFile.getParent());
                Files.write(endpointFile, endpointFileText(port, token, nonce).getBytes(StandardCharsets.US_ASCII));
            } catch (IOException | RuntimeException e) {
                System.err.println("join-host: endpoint file write failed: " + e.getClass().getSimpleName());
            }
        }
        Thread t = new Thread(this::serve, "cc-join-accept");
        t.setDaemon(true);
        t.start();
        return port;
    }

    String token() {
        return token;
    }

    int port() {
        return port;
    }

    /** {@code http://127.0.0.1:<port>/<token>/} — what the browser is opened on. */
    String baseUrl() {
        return "http://127.0.0.1:" + port + "/" + token + "/";
    }

    boolean isClosed() {
        return closed;
    }

    /** Stop accepting and drop the endpoint file (best-effort; in-flight replies finish on their own threads). */
    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        try {
            if (endpointFile != null) {
                Files.deleteIfExists(endpointFile);
            }
        } catch (IOException | RuntimeException ignored) {
            // a stale file is rejected by the next window's ping (parseEndpoint + no answer)
        }
    }

    private void serve() {
        ServerSocket s = server;
        while (!closed) {
            final Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("join-host: accept failed; responder stopped: " + e.getClass().getSimpleName());
                }
                return;
            }
            if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
                inFlight.decrementAndGet();
                try {
                    c.setSoTimeout(READ_TIMEOUT_MILLIS);
                    HttpExchange.replyText(c.getOutputStream(), 503, "busy");
                } catch (IOException ignored) {
                    // the client went away
                } finally {
                    closeQuietly(c);
                }
                continue;
            }
            Thread worker = new Thread(() -> {
                try {
                    handle(c);
                } finally {
                    inFlight.decrementAndGet();
                    closeQuietly(c);
                }
            }, "cc-join-req-" + served.incrementAndGet());
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket c) {
        OutputStream out = null;
        try {
            c.setSoTimeout(READ_TIMEOUT_MILLIS);
            out = c.getOutputStream();
            HttpExchange req;
            try {
                req = HttpExchange.read(c.getInputStream());
            } catch (HttpExchange.Refused refused) {
                HttpExchange.replyText(out, refused.status, refused.getMessage());
                return;
            }
            router.handle(req, out, token, port);
        } catch (IOException io) {
            // a closed or timed-out browser connection: nothing to answer
        } catch (Throwable fault) {
            // the fault's class only — never a message that could carry request text
            System.err.println("join-host: request thread fault " + fault.getClass().getName());
            if (out != null) {
                try {
                    HttpExchange.replyText(out, 500, "internal error");
                } catch (IOException ignored) {
                    // the client went away
                }
            }
        }
    }

    private static void closeQuietly(Socket c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    static String mintToken() {
        byte[] b = new byte[TOKEN_HEX_CHARS / 2];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(TOKEN_HEX_CHARS);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
