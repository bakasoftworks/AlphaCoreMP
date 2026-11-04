package cc.starsector.client.join;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import cc.starsector.client.KitUserDirs;
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
 * file ({@link #endpointFile}, the user's own profile dir) = {@code port|token|nonce} is written with
 * {@code java.nio.file} (no {@code Global}) owner-only and deleted on {@link #close}; the token is 64 hex chars
 * (32 bytes) per process; the log sink is the window log ({@code JoinBrowserUi.log}; the window has no game sidelog).
 *
 * <p>Security: loopback bind, random port, the token on
 * EVERY request as the first path segment, bounded requests ({@link HttpExchange}), no request-derived
 * path anywhere, the match password never in a URL, a log line never carrying player text or a secret.
 * Off Windows the browser is never handed the tokened URL: it gets a one-time ticket ({@link #mintTicket},
 * {@code /t/<ticket>}) whose first GET answers a 303 to the stored address.
 */
public final class JoinHost {
    static final int MAX_IN_FLIGHT = 8;
    static final int READ_TIMEOUT_MILLIS = 5000;
    /** The endpoint file's name up to its per-install tag ({@link #endpointFile}). */
    static final String ENDPOINT_FILE_PREFIX = "cc_join_endpoint-";
    /** Token width in hex chars (32 random bytes); {@link #parseEndpoint} gates on it. */
    static final int TOKEN_HEX_CHARS = 64;
    /** A browser ticket's width in hex chars (8 random bytes). */
    static final int TICKET_HEX_CHARS = 16;
    /** A ticket redeems within this many ms of its mint, or never. */
    static final long TICKET_TTL_MS = 30000;
    /** Tickets remembered per host — spent and expired ones stay so a later use is caught; the oldest drops past it. */
    static final int MAX_TICKETS = 16;
    /** The ticket route's path prefix: {@code /t/<ticket>}, outside the token. */
    static final String TICKET_PATH = "/t/";

    /** The route table the host hands every parsed request to (the editor's {@code EditorRoutes.handle} shape). */
    interface Router {
        void handle(HttpExchange req, OutputStream out, String token, int port) throws IOException;

        /**
         * The host's own call, the ticket desk riding along: a router with a ticket route overrides this; the
         * default is the token + port form read at call time (a rotated token is live on the next request).
         *
         */
        default void handle(HttpExchange req, OutputStream out, JoinHost host) throws IOException {
            handle(req, out, host.token(), host.port());
        }
    }

    /** One minted ticket: its 303 target, its mint time, and whether its one GET was spent. */
    private static final class Ticket {
        final String redirect;
        final long mintedMs;
        boolean spent;

        Ticket(String redirect, long mintedMs) {
            this.redirect = redirect;
            this.mintedMs = mintedMs;
        }
    }

    private final Router router;
    private final Path endpointFile;
    private final String nonce;
    private final LongSupplier clockMs;
    private final Consumer<String> log;
    /** Guarded by {@code this}; insertion order = mint order (the oldest is the one evicted). */
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger served = new AtomicInteger();
    private volatile String token;
    private volatile Runnable onStolen;
    private volatile ServerSocket server;
    private volatile int port;
    private volatile boolean closed;

    JoinHost(Router router, Path endpointFile, String nonce) {
        this(router, endpointFile, nonce, () -> System.nanoTime() / 1000000L, JoinBrowserUi::log);
    }

    /** The injectable form (selftest): a monotonic ms clock for the ticket lifetime, and the log sink. */
    JoinHost(Router router, Path endpointFile, String nonce, LongSupplier clockMs, Consumer<String> log) {
        this.router = router;
        this.endpointFile = endpointFile;
        this.nonce = nonce;
        this.clockMs = clockMs;
        this.log = log;
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
     * Off Windows: {@link #endpointFileOffWindows} (the kit's runtime dir; Windows keeps the expression above).
     */
    static Path endpointFile(Path modDir) {
        if (!KitUserDirs.isWindows()) {
            return endpointFileOffWindows(System.getenv(), JoinVoiceSettings.installRoot(modDir));
        }
        return endpointFile(System.getenv("LOCALAPPDATA"), JoinVoiceSettings.installRoot(modDir));
    }

    /**
     * Off Windows the file lives in the kit's RUNTIME dir
     * ({@code <runtimeBase>/cc-starsector/join/cc_join_endpoint-<8 hex>.data}; {@link KitUserDirs#runtimeDir}: the
     * session's {@code XDG_RUNTIME_DIR} when set and absolute, else the data dir under HOME) — the same per-install
     * tag, owner-only modes at the write ({@link #start}). HOME unusable ⇒ null: no file, no single-instance check,
     * never a fallback into the install or {@code /tmp}. Pure over the env map (selftested).
     */
    static Path endpointFileOffWindows(Map<String, String> env, Path installRoot) {
        Path dir = KitUserDirs.runtimeDir(env);
        return dir == null ? null : dir.resolve(KitUserDirs.JOIN_SUBDIR)
                .resolve(ENDPOINT_FILE_PREFIX + installTag(installRoot) + ".data");
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
        log.accept("join-host: listening on " + s.getInetAddress().getHostAddress() + ":" + port
                + " (token " + token.length() + " hex chars, per-process)");
        // A null file (no LOCALAPPDATA) writes nothing - JoinBrowserUi.run logged
        // it; a failure logs the class only (the exception text names the profile path)
        if (endpointFile != null) {
            try {
                writeEndpointFile(endpointFile, endpointFileText(port, token, nonce));
            } catch (IOException | RuntimeException e) {
                System.err.println("join-host: endpoint file write failed: " + e.getClass().getSimpleName());
            }
        }
        Thread t = new Thread(this::serve, "cc-join-accept");
        t.setDaemon(true);
        t.start();
        return port;
    }

    /**
     * The endpoint text through the owner-only helper — 0700 dirs, a 0600 file
     * (an existing file is set 0600 too), and a REWRITE ({@code CREATE|TRUNCATE_EXISTING|WRITE}, the
     * {@code Files.write} options it replaces): a stale longer file from a dead window is cut to the new text, never
     * refused. On Windows (no {@code posix} view) both helpers are the plain calls.
     */
    static void writeEndpointFile(Path file, String text) throws IOException {
        KitFileModes.createDirs(file.getParent());
        ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
        try (SeekableByteChannel ch = KitFileModes.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) {
                ch.write(bytes);
            }
        }
    }

    /** The CURRENT token: a stolen ticket rotates it ({@link #redeem}), so every reader reads it at use. */
    String token() {
        return token;
    }

    /** Runs once after a stolen ticket closed this host (the browser session's Swing fallback); null = none. */
    void onStolen(Runnable hook) {
        this.onStolen = hook;
    }

    /**
     * A one-time browser ticket for {@code redirectUrl} — 16 hex from
     * {@link SecureRandom}, spent by its first GET ({@link #redeem}), dead {@value #TICKET_TTL_MS} ms after the mint.
     * WHY: off Windows every opener ends in the browser's own command line, which any local account can read for
     * the browser's whole life; the tokened page address must never ride it, so the opener gets
     * {@link #ticketUrl} instead and the address travels in the 303's {@code Location}. The target must be a
     * loopback http address with no control or space characters (it becomes a response header).
     */
    synchronized String mintTicket(String redirectUrl) {
        if (!isLoopbackHttp(redirectUrl)) {
            throw new IllegalArgumentException("a ticket redirects only to a loopback http address");
        }
        while (tickets.size() >= MAX_TICKETS) {
            Iterator<String> oldest = tickets.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        String t = randomHex(TICKET_HEX_CHARS);
        tickets.put(t, new Ticket(redirectUrl, clockMs.getAsLong()));
        return t;
    }

    /** {@code http://127.0.0.1:<port>/t/<ticket>} — the only address an opener is handed off Windows. */
    String ticketUrl(String ticket) {
        return "http://127.0.0.1:" + port + TICKET_PATH + ticket;
    }

    /** True when {@code ticket} was minted here (live, spent or expired) and is still remembered. */
    synchronized boolean knowsTicket(String ticket) {
        return tickets.containsKey(ticket);
    }

    /**
     * The ticket's redirect on its FIRST GET inside its lifetime, then never
     * again. A remembered ticket used a second time, or after its lifetime, means another program read it: the
     * token is rotated (any copy of the old page address is dead), this host closes, ONE log row lands and the
     * {@link #onStolen} hook runs; null is returned. An unknown ticket returns null with no side effect — a blind
     * request (a web page probing loopback ports) must not be able to close the window.
     */
    String redeem(String ticket) {
        String why;
        synchronized (this) {
            Ticket t = tickets.get(ticket);
            if (t == null || closed) {
                return null;
            }
            long age = clockMs.getAsLong() - t.mintedMs;
            if (!t.spent && age >= 0 && age <= TICKET_TTL_MS) {
                t.spent = true;
                return t.redirect;
            }
            why = t.spent ? "a second time" : "after it expired";
            t.spent = true;
            token = mintToken();
            close();
        }
        log.accept("join-host: a browser ticket was used " + why + " - another program may have read it;"
                + " the page address is replaced and this page is closed");
        Runnable hook = onStolen;
        if (hook != null) {
            hook.run();
        }
        return null;
    }

    /** True for {@code http://127.0.0.1:<port>/...} made of printable, space-free ASCII only. */
    static boolean isLoopbackHttp(String url) {
        if (url == null || !url.startsWith("http://127.0.0.1:")) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** Waits until this host closes or {@code ms} pass; true when it closed. */
    boolean awaitClosed(long ms) {
        try {
            return closedLatch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return closed;
        }
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
        closedLatch.countDown();
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
            router.handle(req, out, this);
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
        return randomHex(TOKEN_HEX_CHARS);
    }

    /** {@code chars} lower-case hex chars ({@code chars / 2} bytes) from a fresh {@link SecureRandom}. */
    static String randomHex(int chars) {
        byte[] b = new byte[chars / 2];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(chars);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
