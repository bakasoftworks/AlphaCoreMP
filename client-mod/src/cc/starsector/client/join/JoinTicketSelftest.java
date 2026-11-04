package cc.starsector.client.join;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cc.starsector.client.join.JoinGuiSelftest.check;

/**
 * The browser-ticket and owner-only-writer rows of the {@code JoinGui --selftest} run: the one-time ticket driven
 * through the REAL {@link JoinRoutes#handle} on a {@link JoinHost} bound to loopback port 0 (raw sockets, so the
 * Host header and the 303 are read as sent), the opener's 3 s settle rule over fake processes, the off-Windows
 * endpoint path, and the endpoint / window-log writers' second write. Pure test code; no browser, no window.
 *
 *
 */
final class JoinTicketSelftest {

    private JoinTicketSelftest() {
    }

    static void run() throws Exception {
        Path tmp = Files.createTempDirectory("cc-join-ticket-selftest");
        try {
            ticketRows(tmp);
            openerRows();
            writerRows(tmp);
        } finally {
            deleteTree(tmp);
        }
    }

    // ------------------------------------------------------------ the ticket over loopback

    private static void ticketRows(Path tmp) throws Exception {
        Path modDir = Files.createDirectories(tmp.resolve("mods").resolve("CC_Client"));
        JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
        long[] now = { 5000L };
        List<String> logRows = new ArrayList<>();

        JoinHost h = new JoinHost(new JoinRoutes(ui), null, null, () -> now[0], logRows::add);
        AtomicInteger hookRuns = new AtomicInteger();
        h.onStolen(hookRuns::incrementAndGet);
        int port = h.start();
        String ours = "127.0.0.1:" + port;
        try {
            String oldToken = h.token();
            String t = h.mintTicket(h.baseUrl());
            check(t.matches("[0-9a-f]{" + JoinHost.TICKET_HEX_CHARS + "}") && !t.equals(h.mintTicket(h.baseUrl())),
                    "ticket-shape: 16 hex per mint (flips if the ticket width or its randomness changes)");
            check(h.ticketUrl(t).equals("http://127.0.0.1:" + port + "/t/" + t) && !h.ticketUrl(t).contains(oldToken),
                    "ticket-url: the opener's address carries the ticket, never the token (flips if the token rides"
                            + " the opener's argv)");

            // the Host guard precedes the ticket, and a non-GET leaves it live
            String foreign = request(port, "GET", "/t/" + t, "evil.example:" + port);
            String posted = request(port, "POST", "/t/" + t, ours);
            check(status(foreign) == 403 && status(posted) == 405 && h.knowsTicket(t) && !h.isClosed(),
                    "ticket-guards: a foreign Host is 403 and a POST is 405, the ticket unspent (flips if the ticket"
                            + " route runs before the Host guard or a non-GET burns it)");

            String first = request(port, "GET", "/t/" + t, ours);
            check(status(first) == 303 && h.baseUrl().equals(header(first, "Location"))
                            && !first.substring(first.indexOf("\r\n\r\n")).contains(oldToken)
                            && "no-store".equals(header(first, "Cache-Control")),
                    "ticket-redeem-once: the first GET is a 303 to the page address, body token-free, never cached"
                            + " (flips if /t/ meets the token test first, or the redirect is lost)");
            check(status(request(port, "GET", "/" + oldToken + "/api/ping", ours)) == 200,
                    "ticket-redeem-once: the page the 303 names answers (flips if the redemption broke the host)");

            String second = request(port, "GET", "/t/" + t, ours);
            long stolenRows = logRows.stream().filter(r -> r.contains("browser ticket was used")).count();
            check(status(second) == 404 && h.isClosed() && !oldToken.equals(h.token())
                            && h.token().matches("[0-9a-f]{64}") && stolenRows == 1 && hookRuns.get() == 1,
                    "ticket-second-use: a second redemption is 404, rotates the token, closes the host, ONE log"
                            + " row, the hook once (flips if a ticket redeems twice or the stolen path is skipped)"
                            + " - rows=" + stolenRows + " hook=" + hookRuns.get());
            check(h.redeem(t) == null && logRows.stream().filter(r -> r.contains("browser ticket was used")).count() == 1,
                    "ticket-second-use: a closed host never logs the stolen row again (flips on a duplicate row)");
            check("REFUSED".equals(request(port, "GET", "/" + oldToken + "/api/ping", ours)),
                    "ticket-second-use: nothing answers the old address afterwards (flips if close is skipped)");
        } finally {
            h.close();
        }

        // expired: a ticket past its lifetime is the stolen path too; the lifetime edge itself still redeems
        logRows.clear();
        JoinHost e = new JoinHost(new JoinRoutes(ui), null, null, () -> now[0], logRows::add);
        int ePort = e.start();
        String eHost = "127.0.0.1:" + ePort;
        try {
            String edge = e.mintTicket(e.baseUrl());
            String late = e.mintTicket(e.baseUrl());
            now[0] += JoinHost.TICKET_TTL_MS;
            check(status(request(ePort, "GET", "/t/" + edge, eHost)) == 303,
                    "ticket-expired: a GET at exactly the lifetime still redeems (flips if the bound turns exclusive)");
            String eToken = e.token();
            now[0] += 1;
            String expired = request(ePort, "GET", "/t/" + late, eHost);
            check(status(expired) == 404 && e.isClosed() && !eToken.equals(e.token())
                            && logRows.stream().filter(r -> r.contains("after it expired")).count() == 1,
                    "ticket-expired: one ms past the lifetime is refused, rotates, closes, one row (flips if the"
                            + " lifetime check is dropped)");
        } finally {
            e.close();
        }

        // unknown: a blind ticket is today's token miss and never closes the window
        JoinHost u = new JoinHost(new JoinRoutes(ui), null, null, () -> now[0], logRows::add);
        int uPort = u.start();
        try {
            String uToken = u.token();
            u.mintTicket(u.baseUrl());
            String blind = request(uPort, "GET", "/t/0123456789abcdef", "127.0.0.1:" + uPort);
            check(status(blind) == 403 && !u.isClosed() && uToken.equals(u.token()),
                    "ticket-unknown: a never-minted ticket is a 403 token miss, the host open, the token kept (flips"
                            + " if a blind GET from any web page can close the window)");
        } finally {
            u.close();
        }

        // mint refuses anything but a loopback http address; the ticket memory is bounded
        JoinHost m = new JoinHost(new JoinRoutes(ui), null, null, () -> now[0], logRows::add);
        int refused = 0;
        for (String bad : new String[] { "http://evil.example/", "https://127.0.0.1:1/", "http://127.0.0.1:1/a\r\nX: y",
                "http://127.0.0.1:1/a b", null }) {
            try {
                m.mintTicket(bad);
            } catch (IllegalArgumentException expectedRefusal) {
                refused++;
            }
        }
        check(refused == 5, "ticket-mint-loopback-only: 5 of 5 foreign / header-breaking targets refused (flips if a"
                + " ticket can redirect off this box or split the Location header) - refused=" + refused);
        String oldest = m.mintTicket("http://127.0.0.1:1/x/");
        for (int i = 0; i < JoinHost.MAX_TICKETS; i++) {
            m.mintTicket("http://127.0.0.1:1/x/");
        }
        check(!m.knowsTicket(oldest), "ticket-memory-bounded: the oldest ticket drops past the cap (flips if the"
                + " ticket map grows without bound)");

        // the one-shot host: only the ticket route
        JoinHost one = new JoinHost(JoinRoutes.TICKETS_ONLY, null, null, () -> now[0], logRows::add);
        int oPort = one.start();
        String oHost = "127.0.0.1:" + oPort;
        try {
            String editor = "http://127.0.0.1:1/" + JoinHost.mintToken() + "/";
            String ot = one.mintTicket(editor);
            String hop = request(oPort, "GET", "/t/" + ot, oHost);
            check(status(hop) == 303 && editor.equals(header(hop, "Location"))
                            && status(request(oPort, "GET", "/" + one.token() + "/", oHost)) == 404
                            && status(request(oPort, "GET", "/t/0123456789abcdef", oHost)) == 404
                            && status(request(oPort, "GET", "/t/" + ot, "evil.example:" + oPort)) == 403,
                    "ticket-one-shot: the one-shot host 303s its ticket to the foreign address and answers nothing"
                            + " else (flips if it serves a page or skips the Host guard)");
        } finally {
            one.close();
        }
    }

    // ------------------------------------------------------------ the opener

    /** A process that exits with {@code exit} at once, or (null) is still running at any deadline. */
    private static final class FakeProcess extends Process {
        final Integer exit;
        long waitedMs = -1;
        boolean destroyed;
        boolean unboundedWait;

        FakeProcess(Integer exit) {
            this.exit = exit;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            unboundedWait = true;
            return exit == null ? 0 : exit;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            waitedMs = unit.toMillis(timeout);
            return exit != null;
        }

        @Override
        public int exitValue() {
            if (exit == null) {
                throw new IllegalThreadStateException("still running");
            }
            return exit;
        }

        @Override
        public boolean isAlive() {
            return exit == null;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }
    }

    private static void openerRows() throws Exception {
        List<String> cmd = new ArrayList<>(List.of("xdg-open", "http://127.0.0.1:1/t/0123456789abcdef"));
        FakeProcess fails = new FakeProcess(1);
        FakeProcess ok = new FakeProcess(0);
        FakeProcess running = new FakeProcess(null);
        check(!JoinBrowserUi.runOpener(cmd, c -> fails) && JoinBrowserUi.runOpener(cmd, c -> ok)
                        && JoinBrowserUi.runOpener(cmd, c -> running),
                "opener-settle: exit 1 = not opened, exit 0 = opened, still running at the deadline = opened (flips if"
                        + " a foreground browser reads as a failure or a failed opener as a success)");
        check(fails.waitedMs == 3000 && ok.waitedMs == 3000 && running.waitedMs == 3000
                        && !running.destroyed && !running.unboundedWait && !fails.unboundedWait,
                "opener-settle: a 3 s bounded wait, the child never destroyed nor waited on unbounded (flips if the"
                        + " deadline moves, or the opener kills or blocks on the browser) - waited=" + running.waitedMs);
        check(!JoinBrowserUi.runOpener(cmd, c -> {
            throw new IOException("no such program");
        }), "opener-settle: an absent opener is not opened (flips if a spawn failure escapes or reads as opened)");

        // the whole chain over a REAL host's tickets: xdg-open refuses, gio opens; each attempt its own ticket
        List<List<String>> argv = new ArrayList<>();
        JoinHost h = new JoinHost(JoinRoutes.TICKETS_ONLY, null, null, () -> 0L, line -> { });
        int port = h.start();
        try {
            boolean opened = JoinBrowserUi.openTicketed(h.baseUrl(), h, false, c -> {
                argv.add(new ArrayList<>(c));
                return new FakeProcess("xdg-open".equals(c.get(0)) ? 1 : 0);
            });
            String ticketShape = "http://127\\.0\\.0\\.1:" + port + "/t/[0-9a-f]{16}";
            boolean tokenFree = argv.stream().allMatch(a -> a.stream().noneMatch(s -> s.contains(h.token())));
            check(opened && argv.size() == 2 && "xdg-open".equals(argv.get(0).get(0))
                            && argv.get(1).subList(0, 2).equals(List.of("gio", "open"))
                            && argv.get(0).get(1).matches(ticketShape) && argv.get(1).get(2).matches(ticketShape)
                            && !argv.get(0).get(1).equals(argv.get(1).get(2)) && tokenFree,
                    "opener-chain: xdg-open then gio open, each handed its own ticket address and never the token"
                            + " (flips if the tokened address reaches an argv or a retry reuses a ticket) - argv="
                            + argv.size());
        } finally {
            h.close();
        }
        // no live host of ours: a one-shot host carries the ticket and stays up for it
        JoinBrowserUi.lastOneShot = null;
        List<String> oneArgv = new ArrayList<>();
        boolean oneOpened = JoinBrowserUi.openTicketed("http://127.0.0.1:1/" + JoinHost.mintToken() + "/", null,
                false, c -> {
                    oneArgv.addAll(c);
                    return new FakeProcess(0);
                });
        JoinHost oneShot = JoinBrowserUi.lastOneShot;
        try {
            check(oneOpened && oneShot != null && !oneShot.isClosed()
                            && oneArgv.get(1).startsWith("http://127.0.0.1:" + oneShot.port() + "/t/"),
                    "opener-one-shot: with no host of ours a one-shot host is started and left serving its ticket"
                            + " (flips if the address rides argv instead, or the host closes before the browser comes)");
        } finally {
            if (oneShot != null) {
                oneShot.close();
            }
        }
    }

    // ------------------------------------------------------------ the writers

    private static void writerRows(Path tmp) throws Exception {
        Path game = Paths.get("/games/starsector");
        String tag = JoinHost.installTag(game);
        Map<String, String> env = new HashMap<>();
        env.put("HOME", "/home/p");
        check(Paths.get("/home/p", ".local", "share", "cc-starsector", "join", "cc_join_endpoint-" + tag + ".data")
                        .equals(JoinHost.endpointFileOffWindows(env, game)),
                "endpoint-off-windows: no XDG_RUNTIME_DIR = the data dir under HOME (flips if the ladder or the"
                        + " join subdir changes)");
        env.put("XDG_RUNTIME_DIR", "/run/user/1000");
        check(Paths.get("/run/user/1000", "cc-starsector", "join", "cc_join_endpoint-" + tag + ".data")
                        .equals(JoinHost.endpointFileOffWindows(env, game)),
                "endpoint-off-windows: the runtime dir wins when set and absolute (flips if the file moves to the"
                        + " persistent dir)");
        Map<String, String> noHome = new HashMap<>();
        Map<String, String> relHome = new HashMap<>();
        relHome.put("HOME", "home/p");
        relHome.put("XDG_RUNTIME_DIR", "run/user/1000");
        check(JoinHost.endpointFileOffWindows(noHome, game) == null && JoinHost.endpointFileOffWindows(relHome, game) == null,
                "endpoint-off-windows: HOME unset or relative = NO file (flips on a fallback into the install or"
                        + " the start folder)");

        Path ep = tmp.resolve("rt").resolve("cc-starsector").resolve("join").resolve("cc_join_endpoint-0badf00d.data");
        Files.createDirectories(ep.getParent());
        Files.write(ep, ("1|" + "f".repeat(64) + "|0badf00d-stale-and-longer").getBytes(StandardCharsets.US_ASCII));
        JoinHost.writeEndpointFile(ep, "40001|" + "a".repeat(64));
        JoinHost.writeEndpointFile(ep, "40002|" + "b".repeat(64) + "|0badf00d");
        check(("40002|" + "b".repeat(64) + "|0badf00d").equals(new String(Files.readAllBytes(ep), StandardCharsets.US_ASCII))
                        && ownerOnly(ep),
                "endpoint-rewrite: a stale longer file is cut to the new text on each write, 0600 on POSIX (flips if"
                        + " the write appends, refuses an existing file, or leaves the mode open)");
        posixPass("ticket endpoint-rewrite 0600");

        Path log = tmp.resolve("data").resolve("cc-starsector").resolve("join-window.log");
        JoinBrowserUi.appendLog(log, "one\n");
        JoinBrowserUi.appendLog(log, "two\n");
        check("one\ntwo\n".equals(new String(Files.readAllBytes(log), StandardCharsets.UTF_8)) && ownerOnly(log),
                "log-append: the window log keeps both lines, 0600 on POSIX (flips if the log is rewritten or created"
                        + " once only)");
        posixPass("ticket log-append 0600");
    }

    /**
     * After a row whose 0600 half went through {@link #ownerOnly}: ONE {@code ROW-PASS <row>} line, printed only where
     * that half really asserted ({@code ownerOnly} is trivially true without a POSIX view, so Windows prints none). The
     * Linux kit check requires the line, so a deleted row, or a JVM whose POSIX view is off, reads as not run.
     *
     */
    private static void posixPass(String row) {
        if (KitFileModes.posix()) {
            System.out.println("ROW-PASS " + row);
        }
    }

    /** 0600 where the filesystem has POSIX modes; trivially true elsewhere (Windows has no such view). */
    private static boolean ownerOnly(Path file) throws IOException {
        return !KitFileModes.posix()
                || "rw-------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    // ------------------------------------------------------------ helpers

    /** One raw HTTP request to the loopback port; the whole reply, or {@code REFUSED} when nothing answers. */
    private static String request(int port, String method, String path, String hostHeader) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setSoTimeout(5000);
            String head = method + " " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nConnection: close\r\n"
                    + ("POST".equals(method) ? "Content-Length: 0\r\n" : "") + "\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                reply.write(buf, 0, n);
            }
            return reply.toString(StandardCharsets.ISO_8859_1);
        } catch (IOException nothingAnswers) {
            return "REFUSED";
        }
    }

    private static int status(String reply) {
        if (!reply.startsWith("HTTP/")) {
            return -1;
        }
        String[] parts = reply.split(" ", 3);
        return parts.length < 2 ? -1 : Integer.parseInt(parts[1]);
    }

    private static String header(String reply, String name) {
        String key = "\r\n" + name + ": ";
        int at = reply.indexOf(key);
        if (at < 0) {
            return null;
        }
        int end = reply.indexOf("\r\n", at + key.length());
        return reply.substring(at + key.length(), end);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            for (int i = all.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(all.get(i));
            }
        }
    }
}
