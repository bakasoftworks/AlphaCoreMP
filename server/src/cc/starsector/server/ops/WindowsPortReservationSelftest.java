package cc.starsector.server.ops;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Rows for {@link WindowsPortReservation} (the Windows-reserved UDP port block check): the parse of netsh's
 * real output shape (a capture of a Hyper-V box: six dynamic WinNAT blocks + one administered), the
 * holding rule (an administered block never holds a port - a bind inside one succeeds), the bounds of the
 * row parse, the warning's admin lines, the not-Windows and failed-run answers, the lease-fill annotate,
 * the live run (the real netsh run returns in bounds; its answer is printed, never asserted, since the blocks
 * move with the host) and the real bind probe failing on a loopback port the row holds (bound on port 0).
 * No other row spawns netsh. Output:
 * {@code PASS <row>} / {@code FAIL <row>: <the regression that flips it>}; {@link #run} returns the
 * failure count.
 *
 *
 */
public final class WindowsPortReservationSelftest {

    /** netsh's output in the real shape (header, column titles, dashes, 7 rows with trailing pad, legend). */
    static final List<String> CANNED = List.of(
            "",
            "Protocol udp Port Exclusion Ranges",
            "",
            "Start Port    End Port      ",
            "----------    --------      ",
            "     50000       50059     *",
            "     52773       52872      ",
            "     61808       61907      ",
            "     62008       62107      ",
            "     62182       62281      ",
            "     62282       62381      ",
            "     62382       62481      ",
            "",
            "* - Administered port exclusions.",
            "");
    private static final String WINDOWS = "Windows 11";
    private static int failures;

    private WindowsPortReservationSelftest() {
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Every row, the summary line, the failure count returned. */
    public static int run() {
        failures = 0;
        row("port-reservation-parse-real-shape", WindowsPortReservationSelftest::parseRealShape);
        row("port-reservation-holding-dynamic", WindowsPortReservationSelftest::holdingDynamic);
        row("port-reservation-administered-never-holds", WindowsPortReservationSelftest::administeredNeverHolds);
        row("port-reservation-garbage-ignored", WindowsPortReservationSelftest::garbageIgnored);
        row("port-reservation-warning-names-block", WindowsPortReservationSelftest::warningNamesBlock);
        row("port-reservation-not-windows", WindowsPortReservationSelftest::notWindows);
        row("port-reservation-bad-port-no-run", WindowsPortReservationSelftest::badPortNoRun);
        row("port-reservation-runner-throws", WindowsPortReservationSelftest::runnerThrows);
        row("port-reservation-annotate", WindowsPortReservationSelftest::annotate);
        row("port-reservation-live-bounded", WindowsPortReservationSelftest::liveBounded);
        row("port-reservation-probe-bind-held-port", WindowsPortReservationSelftest::probeBindHeldPort);
        row("port-reservation-warning-fits-host-line", WindowsPortReservationSelftest::warningFitsHostLine);
        System.out.println(failures == 0 ? "WindowsPortReservationSelftest: every row PASS"
                : "WindowsPortReservationSelftest: " + failures + " row(s) did not pass");
        return failures;
    }

    // ---- rows ----

    private static void parseRealShape() {
        List<WindowsPortReservation.Block> blocks = WindowsPortReservation.parse(CANNED);
        require(blocks.size() == 7, "parse found " + blocks.size() + " blocks, not 7 (flips if a header,"
                + " dash or legend line parses as a row, or a trailing-pad row is dropped)");
        require(blocks.get(0).equals(new WindowsPortReservation.Block(50000, 50059, true)),
                "the * row parsed as " + blocks.get(0) + " (flips if the administered mark is lost)");
        for (int i = 1; i < blocks.size(); i++) {
            require(!blocks.get(i).administered(), "row " + i + " " + blocks.get(i)
                    + " parsed as administered (flips if the mark leaks onto an unmarked row)");
        }
        require(blocks.get(5).equals(new WindowsPortReservation.Block(62282, 62381, false)),
                "the sixth row parsed as " + blocks.get(5) + " (flips if a column is misread)");
    }

    private static void holdingDynamic() {
        List<WindowsPortReservation.Block> blocks = WindowsPortReservation.parse(CANNED);
        WindowsPortReservation.Block want = new WindowsPortReservation.Block(62282, 62381, false);
        for (int port : new int[] {62316, 62282, 62381}) {
            require(want.equals(WindowsPortReservation.holding(blocks, port)), "holding(" + port + ") = "
                    + WindowsPortReservation.holding(blocks, port) + ", not 62282-62381 (flips if a dynamic block"
                    + " stops holding a port inside it or an edge is off by one)");
        }
        for (int port : new int[] {1, 62108, 62181, 62482, 65535}) {
            require(WindowsPortReservation.holding(blocks, port) == null, "holding(" + port + ") = "
                    + WindowsPortReservation.holding(blocks, port) + " for a port outside every block (flips if"
                    + " the containment test widens)");
        }
    }

    private static void administeredNeverHolds() {
        List<WindowsPortReservation.Block> blocks = WindowsPortReservation.parse(CANNED);
        for (int port : new int[] {50000, 50001, 50059}) {
            require(WindowsPortReservation.holding(blocks, port) == null, "holding(" + port + ") = "
                    + WindowsPortReservation.holding(blocks, port) + " inside the administered block (flips if an"
                    + " administered exclusion counts as holding: a bind inside one succeeds, measured)");
            require(WindowsPortReservation.warning(port, WINDOWS, () -> CANNED).isEmpty(), "warning(" + port
                    + ") is non-empty inside the administered block (flips if the warning ignores the mark)");
        }
    }

    private static void garbageIgnored() {
        List<String> lines = List.of(
                "garbage",
                "62400 62300",
                "0 10",
                "100 70000",
                "123456 7",
                "100 200 * extra",
                "100 200 **",
                "-5 10",
                " ".repeat(200) + "300 400",
                "  100  200 ",
                "65535 65535 *");
        List<WindowsPortReservation.Block> blocks = WindowsPortReservation.parse(lines);
        List<WindowsPortReservation.Block> want = List.of(new WindowsPortReservation.Block(100, 200, false),
                new WindowsPortReservation.Block(65535, 65535, true));
        require(want.equals(blocks), "parse kept " + blocks + ", not exactly 100-200 + 65535-65535* (flips if a"
                + " start > end, a zero start, an end past 65535, a six-digit field, a trailing word, a double"
                + " mark, a sign or an over-long line is accepted)");
        require(WindowsPortReservation.parse(null).isEmpty(), "parse(null) is not empty (flips if a null"
                + " runner answer stops being no blocks)");
    }

    private static void warningNamesBlock() {
        String w = WindowsPortReservation.warning(62316, WINDOWS, () -> CANNED);
        for (String part : new String[] {"UDP port 62316 ", "block 62282-62381 ", "net stop winnat;",
                "netsh int ipv4 add excludedportrange protocol=udp startport=62316 numberofports=1 store=persistent;",
                "net start winnat", "reconnect the VPN"}) {
            require(w.contains(part), "the warning lacks [" + part + "] (flips if the block, the port or an admin"
                    + " line drops out): " + PeerText.bound(w, 200));
        }
        for (String word : new String[] {" we ", " our ", " us "}) {
            require(!(" " + w.toLowerCase(java.util.Locale.ROOT) + " ").contains(word),
                    "the warning speaks as [" + word.trim() + "] (flips if the released text breaks the voice law)");
        }
        require(WindowsPortReservation.warning(40000, WINDOWS, () -> CANNED).isEmpty(),
                "warning(40000) is non-empty for a port in no block (flips if a free port gets a warning)");
    }

    private static void notWindows() {
        AtomicInteger ran = new AtomicInteger();
        Supplier<List<String>> runner = () -> {
            ran.incrementAndGet();
            return CANNED;
        };
        WindowsPortReservation.Answer a = WindowsPortReservation.check(62316, "Linux", runner);
        require(a.block() == null && "not_windows".equals(a.why()), "a Linux check answered " + a
                + " (flips if the OS gate stops answering why=not_windows)");
        require(WindowsPortReservation.warning(62316, "Mac OS X", runner).isEmpty(), "a macOS warning is"
                + " non-empty (flips if the OS gate lets the parse run)");
        require(WindowsPortReservation.check(62316, null, runner).why().equals("not_windows"), "a null OS name"
                + " did not answer not_windows (flips if a missing os.name reaches the run)");
        require(ran.get() == 0, "the runner ran " + ran.get() + " time(s) off Windows (flips if a non-Windows"
                + " host spawns netsh)");
        WindowsPortReservation.Answer on = WindowsPortReservation.check(62316, WINDOWS, runner);
        require(ran.get() == 1 && on.why() == null
                && new WindowsPortReservation.Block(62282, 62381, false).equals(on.block()),
                "the Windows check answered " + on + " after " + ran.get() + " run(s) (flips if the seam stops"
                + " running the parse on Windows)");
    }

    private static void badPortNoRun() {
        AtomicInteger ran = new AtomicInteger();
        Supplier<List<String>> runner = () -> {
            ran.incrementAndGet();
            return CANNED;
        };
        for (int port : new int[] {0, -1, 65536, Integer.MIN_VALUE}) {
            WindowsPortReservation.Answer a = WindowsPortReservation.check(port, WINDOWS, runner);
            require(a.block() == null && "bad_port".equals(a.why()), "check(" + port + ") answered " + a
                    + " (flips if a port outside 1..65535 is not refused first)");
        }
        require(ran.get() == 0, "the runner ran " + ran.get() + " time(s) for out-of-range ports (flips if the"
                + " port check stops running before anything spawns)");
    }

    private static void runnerThrows() {
        Supplier<List<String>> throwing = () -> {
            throw new IllegalStateException("the canned spawn failure");
        };
        WindowsPortReservation.Answer a = WindowsPortReservation.check(62316, WINDOWS, throwing);
        require(a.block() == null && "spawn:IllegalStateException".equals(a.why()), "a throwing runner answered "
                + a + " (flips if a spawn failure escapes or loses its SimpleName token)");
        require(WindowsPortReservation.warning(62316, WINDOWS, throwing).isEmpty(), "a throwing runner warned"
                + " (flips if a failed check stops answering an empty warning)");
        WindowsPortReservation.Answer none = WindowsPortReservation.check(62316, WINDOWS, () -> null);
        require(none.block() == null && none.why() == null, "a null runner answer gave " + none
                + " (flips if no lines stops meaning no block)");
    }

    private static void annotate() {
        VpnLeaseProbe.Fill blank = new VpnLeaseProbe.Fill("10.2.0.2", "", "89.187.171.240:", false, "address only.");
        AtomicInteger asked = new AtomicInteger();
        require(WindowsPortReservation.annotate(blank, p -> {
            asked.incrementAndGet();
            return "x";
        }) == blank && asked.get() == 0, "a blank-port fill was not answered as the SAME object without a check"
                + " (flips if annotate copies or checks a fill with no port)");
        VpnLeaseProbe.Fill zero = new VpnLeaseProbe.Fill("10.2.0.2", "0", "89.187.171.240:0", false, "n");
        require(WindowsPortReservation.annotate(zero, p -> "x") == zero, "a port-0 fill was annotated (flips if"
                + " the 1..65535 gate on the fill's port goes)");
        VpnLeaseProbe.Fill found = new VpnLeaseProbe.Fill("10.2.0.2", "62316", "89.187.171.240:62316", true,
                "found.");
        String w = WindowsPortReservation.warning(62316, WINDOWS, () -> CANNED);
        VpnLeaseProbe.Fill noted = WindowsPortReservation.annotate(found,
                p -> WindowsPortReservation.warning(p, WINDOWS, () -> CANNED));
        require(!w.isEmpty() && noted.equals(new VpnLeaseProbe.Fill("10.2.0.2", "62316", "89.187.171.240:62316",
                true, "found. " + w)), "the reserved-port fill annotated as " + noted + " (flips if a field is"
                + " dropped or the note is not the old note + one space + the warning)");
        VpnLeaseProbe.Fill free = new VpnLeaseProbe.Fill("10.2.0.2", "40000", "89.187.171.240:40000", true, "f.");
        require(WindowsPortReservation.annotate(free, p -> WindowsPortReservation.warning(p, WINDOWS,
                () -> CANNED)) == free, "a free-port fill was not the SAME object (flips if a fill is copied"
                + " with no warning to add)");
        require(WindowsPortReservation.annotate(null) == null, "annotate(null) did not answer null (flips if"
                + " the never-throws guard goes)");
    }

    private static void liveBounded() {
        long t0 = System.nanoTime();
        WindowsPortReservation.Answer a = WindowsPortReservation.check(62316);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("  port-reservation live: check(62316) = " + a + " in " + ms + " ms");
        require(a != null && ms < 5_000L, "the live check took " + ms + " ms (flips if the run's deadline or"
                + " the reader bound stops holding)");
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            require("not_windows".equals(a.why()), "a non-Windows live check answered " + a
                    + " (flips if the live OS gate breaks)");
        }
    }

    /**
     * The real bind probe's failure path, caller-first: a loopback port this row holds (port 0, so the OS picks
     * one outside every exclusion) makes the probe fail; its message must be the block warning when the live
     * check names a block, else the probe's three-guess message unchanged.
     */
    private static void probeBindHeldPort() throws Exception {
        try (java.net.DatagramSocket held = new java.net.DatagramSocket(new java.net.InetSocketAddress(
                java.net.InetAddress.getByName("127.0.0.1"), 0))) {
            int port = held.getLocalPort();
            String w = WindowsPortReservation.warning(port);
            String want = w.isEmpty() ? "bind probe FAILED for 127.0.0.1:" + port + " (VPN down / stale lease /"
                    + " port taken? the door binds this endpoint after engine boot): "
                    : "bind probe FAILED for 127.0.0.1:" + port + ": " + w;
            try {
                cc.starsector.server.runtime.PilotIntentDatagramServer.probeBind("127.0.0.1", port);
                throw new AssertionError("the probe bound a port this row holds (flips if the probe stops"
                        + " failing a taken endpoint)");
            } catch (java.net.BindException failed) {
                String got = String.valueOf(failed.getMessage());
                require(w.isEmpty() ? got.startsWith(want) : got.equals(want), "the probe said ["
                        + PeerText.bound(got, 220) + "] (flips if the probe's failure message stops matching the"
                        + " warning branch the live check picks)");
                require(failed.getCause() instanceof java.net.SocketException, "the probe's cause is "
                        + failed.getCause() + " (flips if the bind failure stops riding as the cause)");
            }
        }
    }

    // ---- plumbing ----

    /**
     * The display bound: both host surfaces (the ops GUI's last stderr line, the console's first exception line)
     * cut at {@link ServeSupervisor#LAST_LINE_MAX_CHARS}; the LONGEST reserved-port line (the JVM's uncaught-trace
     * prefix, the probe's prefix, an IPv6 bind address with a scope id at the port ceiling, then the warning for a
     * block at the ceiling) must fit it whole, else the admin remedy is cut again.
     */
    private static void warningFitsHostLine() {
        String w = WindowsPortReservation.warning(65535, WINDOWS, () -> List.of("     65535       65535"));
        require(!w.isEmpty(), "warning(65535) is empty for the one-port block 65535-65535 (flips if a block at the"
                + " port ceiling is not parsed or not held)");
        String scopedAddress = "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff" + "%ethernet_32769";
        String line = "Exception in thread \"main\" java.net.BindException: bind probe FAILED for "
                + scopedAddress + ":" + 65535 + ": " + w;
        require(line.length() <= ServeSupervisor.LAST_LINE_MAX_CHARS, "the longest reserved-port host line is "
                + line.length() + " chars > the host-line bound " + ServeSupervisor.LAST_LINE_MAX_CHARS
                + " (flips if the warning grows or the bound shrinks: both host surfaces would cut the admin remedy)");
        System.out.println("  port-reservation host line: " + line.length() + " of " + ServeSupervisor.LAST_LINE_MAX_CHARS
                + " chars (warning " + w.length() + ")");
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void row(String name, Body body) {
        try {
            body.run();
            System.out.println("PASS " + name);
        } catch (AssertionError failed) {
            System.out.println("FAIL " + name + ": " + failed.getMessage());
            failures++;
        } catch (Exception | Error unexpected) {
            System.out.println("FAIL " + name + ": unexpected " + unexpected.getClass().getSimpleName());
            failures++;
        }
    }

    private static void require(boolean ok, String regression) {
        if (!ok) {
            throw new AssertionError(regression);
        }
    }
}
