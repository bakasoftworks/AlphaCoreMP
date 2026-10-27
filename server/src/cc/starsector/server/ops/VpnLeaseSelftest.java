package cc.starsector.server.ops;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import cc.starsector.server.ops.ServerOpsCore.VpnLease;
import cc.starsector.server.ops.ServerOpsLaunch.BindWords;

/**
 * {@link VpnLeaseProbe}'s and the ProtonVPN mode's rows ({@link ServerOpsLaunch.BindWords},
 * {@link ServerOpsLaunch#protonMode}, the fill-row writers). Game-free and network-free except
 * loopback datagram pairs (the pull rows' fake gateways: {@code lease-log-age}'s own responder,
 * then {@link Gateway}); row {@code vpn-log-grammar-twin} reads the lease script
 * from the cwd (the repo root - elsewhere it prints one skip line); a second or so.
 *
 * <p>Run in the build gate by {@code OpsSelftests --selftest} and directly through {@link #main}
 * (the mutant runner's entry). A failed row throws an {@link AssertionError} reading
 * {@code FAIL <row>: ...} ({@code SETUP-<row>: ...} when its fixture never staged).
 *
 *
 */
final class VpnLeaseSelftest {

    private VpnLeaseSelftest() {
    }

    private static int checks;

    /** The pull rows' fixed "now" (the {@code lease-log-age} instant) and its clock.
     * */
    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Words a host-facing ProtonVPN text never carries (owner-mode tooling words); a SUBSTRING ban. */
    private static final List<String> HOST_TEXT_BANS = List.of("lease", "pwsh", "script", "repo",
            "generated/", "-kv");

    /** A row's fixture could not be staged - an ERROR, never a kill. */
    private static final class SetupFailure extends Exception {
        SetupFailure(String what, Throwable cause) {
            super(what, cause);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(String row) throws Exception;
    }

    private static void check(String row, boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL " + row + ": " + what);
        }
    }

    /** Runs one row: any throw becomes ONE named failure line. */
    private static void row(String name, Body body) {
        try {
            body.run(name);
        } catch (AssertionError named) {
            throw named;
        } catch (SetupFailure setup) {
            throw new AssertionError("SETUP-" + name + ": " + setup.getMessage() + " ("
                    + setup.getCause() + ")", setup);
        } catch (Exception | Error other) {
            throw new AssertionError("FAIL " + name + ": " + other, other);
        }
    }

    /** Every row; exit 1 on a failure. */
    public static void main(String[] args) {
        try {
            run();
        } catch (Exception | AssertionError e) {
            System.out.println("vpn-lease selftest FAILED: " + e);
            System.exit(1);
        }
    }

    /** Every row over one temp tree, removed after (a residue is said once, never fatal). */
    static void run() throws Exception {
        Path dir = Files.createTempDirectory("cc-vpn-lease-selftest");
        try {
            row("natpmp-decode", VpnLeaseSelftest::natpmpDecode);
            row("lease-log-line", VpnLeaseSelftest::leaseLogLine);
            row("lease-log-age", r -> leaseLogAge(r, dir.resolve("age")));
            row("lease-bind-words", r -> leaseBindWords(r, dir.resolve("words")));
            row("lease-exact", r -> leaseExact(r, dir.resolve("exact")));
            row("lease-log-rotation", r -> leaseLogRotation(r, dir.resolve("rotation")));
            row("lease-log-tail-bound", r -> leaseLogTailBound(r, dir.resolve("tail")));
            row("natpmp-loopback", VpnLeaseSelftest::natpmpLoopback);
            // lease-probe-first reads the counter delta natpmp-timeout's refused pull left
            long[] unparsedDelta = {Long.MIN_VALUE};
            row("natpmp-timeout", r -> natpmpTimeout(r, dir.resolve("timeout"), unparsedDelta));
            row("lease-probe-first", r -> leaseProbeFirst(r, unparsedDelta));
            row("lease-owner-requires-port", VpnLeaseSelftest::leaseOwnerRequiresPort);
            row("lease-address-only", VpnLeaseSelftest::leaseAddressOnly);
            row("vpn-log-grammar-twin", VpnLeaseSelftest::vpnLogGrammarTwin);
            row("lease-text-law", VpnLeaseSelftest::leaseTextLaw);
        } finally {
            deleteTree(dir);
        }
        System.out.println("vpn-lease selftest checks=" + checks);
        System.out.println("vpn-lease selftest PASS");
    }

    /** A NAT-PMP external-address success reply carrying {@code ip}. */
    private static byte[] reply(int a, int b, int c, int d) {
        return new byte[] {0, -128, 0, 0, 0, 0, 0x12, 0x34, (byte) a, (byte) b, (byte) c, (byte) d};
    }

    private static void natpmpDecode(String r) {
        byte[] golden = reply(203, 0, 113, 5);
        check(r, "203.0.113.5".equals(VpnLeaseProbe.decodeExitIp(golden, 12)),
                "the 12 B golden reply decodes to 203.0.113.5 (flips if the IPv4 offset or the"
                        + " & 0xFF of a byte >= 128 regresses)");
        check(r, VpnLeaseProbe.decodeExitIp(golden, 11) == null,
                "an 11 B reply is no answer (flips if the length guard drops)");
        byte[] op129 = reply(203, 0, 113, 5);
        op129[1] = (byte) 129;
        check(r, VpnLeaseProbe.decodeExitIp(op129, 12) == null,
                "op 129 is no answer (flips if the op 128 guard drops)");
        byte[] result1 = reply(203, 0, 113, 5);
        result1[3] = 1;
        check(r, VpnLeaseProbe.decodeExitIp(result1, 12) == null,
                "result 1 is no answer (flips if the result-code guard drops)");
        byte[] result256 = reply(203, 0, 113, 5);
        result256[2] = 1;
        check(r, VpnLeaseProbe.decodeExitIp(result256, 12) == null,
                "result 256 (the high byte) is no answer (flips if only byte 3 is read)");
        for (String pub : List.of("203.0.113.5", "8.8.8.8", "100.128.0.1", "172.32.0.1")) {
            check(r, VpnLeaseProbe.globalIpv4(pub),
                    pub + " is public (flips if a private range is drawn too wide)");
        }
        for (String priv : List.of("0.0.0.0", "10.2.0.1", "100.64.0.1", "100.127.255.255",
                "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "224.0.0.1")) {
            check(r, !VpnLeaseProbe.globalIpv4(priv),
                    priv + " is not public (flips if a LAN / CGNAT / loopback / multicast range"
                            + " is admitted as the exit address)");
        }
    }

    private static void leaseLogLine(String r) {
        VpnLeaseProbe.LogHit last = VpnLeaseProbe.scanLines(List.of(
                "a | Port pair 1->111", "b | noise", "c | Port pair 2->222", "d | tail"));
        check(r, last != null && last.port() == 222,
                "the LAST of two Port pair lines wins (flips if the scan runs from the top)");
        VpnLeaseProbe.LogHit upper = VpnLeaseProbe.scanLines(List.of("x | port PAIR 1->2"));
        check(r, upper != null && upper.port() == 2,
                "port PAIR matches (flips if PORT_PAIR loses CASE_INSENSITIVE)");
        check(r, VpnLeaseProbe.scanLines(List.of("x | nothing here", "")) == null,
                "no port line = null");
        check(r, VpnLeaseProbe.scanLines(List.of("x | Port pair 12->")) == null,
                "a torn Port pair line is no hit");
        VpnLeaseProbe.LogHit pastTorn = VpnLeaseProbe.scanLines(List.of(
                "x | Port pair 1->5", "x | Port pair 12->", "x | Port pair 1->70000",
                "x | Port pair 1->0"));
        check(r, pastTorn != null && pastTorn.port() == 5,
                "torn / out-of-range lines are skipped and the scan goes on (flips if a"
                        + " 0 / 70000 port is kept or the scan stops at the first matching line)");
        String stamp = "2026-07-08T14:03:22.123+02:00";
        VpnLeaseProbe.LogHit stamped = VpnLeaseProbe.scanLines(List.of(
                stamp + " | Port pair 1->46337"));
        check(r, stamped != null && stamped.port() == 46337
                && OffsetDateTime.parse(stamp).toInstant().equals(stamped.stamp()),
                "the offset stamp parses to its instant (flips if the stamp group or parse drifts)");
        long unparsedBefore = VpnLeaseProbe.STAMPS_UNPARSED.get();
        VpnLeaseProbe.LogHit garbage = VpnLeaseProbe.scanLines(List.of("garbage | Port pair 1->2"));
        check(r, garbage != null && garbage.port() == 2 && garbage.stamp() == null,
                "an unparsable stamp keeps the port with a null stamp");
        check(r, VpnLeaseProbe.STAMPS_UNPARSED.get() == unparsedBefore + 1,
                "the unparsable stamp is counted (flips if the catch stops metering)");
        String longLine = "y | " + "x".repeat(9000) + " Port pair 1->9";
        VpnLeaseProbe.LogHit capped = VpnLeaseProbe.scanLines(List.of("x | Port pair 1->7", longLine));
        check(r, capped != null && capped.port() == 7,
                "a 9000-char line holding a pair is skipped (flips if MAX_LINE_CHARS stops binding)");
    }

    private static void leaseLogAge(String r, Path logDir) throws Exception {
        Instant now = Instant.parse("2026-07-08T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        check(r, VpnLeaseProbe.ageWhy(new VpnLeaseProbe.LogHit(1, now.minusSeconds(594)), clock)
                == null, "a 9.9 min old line is fresh (flips if the threshold drops below 10)");
        String stale = String.format(Locale.ROOT, VpnLeaseProbe.WHY_STALE, 10);
        String got = VpnLeaseProbe.ageWhy(new VpnLeaseProbe.LogHit(1, now.minusSeconds(606)), clock);
        check(r, stale.equals(got) && got.contains(" 10 min "),
                "a 10.1 min old line is stale, its sentence says 10 (flips if MAX_AGE_MIN rises"
                        + " or the age gate drops); got " + got);
        check(r, VpnLeaseProbe.ageWhy(new VpnLeaseProbe.LogHit(1, null), clock) == null,
                "a null stamp passes the age gate");
        check(r, VpnLeaseProbe.ageWhy(new VpnLeaseProbe.LogHit(1, now.plusSeconds(300)), clock)
                == null, "a future stamp passes the age gate (clock skew is not staleness)");

        // the pull over a planted log whose ONLY line is 10.1 min old + a loopback gateway
        String line = OffsetDateTime.ofInstant(now.minusSeconds(606), ZoneOffset.UTC)
                + " | Port pair 1->46337\r\n";
        DatagramSocket gateway;
        try {
            Files.createDirectories(logDir);
            Files.write(logDir.resolve("client-logs.txt"), line.getBytes(StandardCharsets.UTF_8));
            gateway = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            gateway.setSoTimeout(5000);
        } catch (IOException e) {
            throw new SetupFailure("planted log / loopback gateway", e);
        }
        AtomicReference<byte[]> asked = new AtomicReference<>();
        Thread responder = new Thread(() -> {
            try {
                DatagramPacket in = new DatagramPacket(new byte[64], 64);
                gateway.receive(in);
                byte[] got2 = new byte[in.getLength()];
                System.arraycopy(in.getData(), 0, got2, 0, in.getLength());
                asked.set(got2);
                byte[] answer = reply(203, 0, 113, 5);
                gateway.send(new DatagramPacket(answer, answer.length, in.getSocketAddress()));
            } catch (IOException e) {
                asked.compareAndSet(null, new byte[] {-1});
            }
        }, "cc-vpn-lease-selftest-gw");
        responder.setDaemon(true);
        try {
            responder.start();
            VpnLeaseProbe.Env env = new VpnLeaseProbe.Env(InetAddress.getLoopbackAddress(),
                    gateway.getLocalPort(), 3000, logDir, clock);
            VpnLeaseProbe.Probe p = VpnLeaseProbe.pull(env);
            responder.join(5000);
            byte[] sent = asked.get();
            check(r, sent != null && sent.length == 2 && sent[0] == 0 && sent[1] == 0,
                    "the gateway was asked with exactly 00 00");
            check(r, "203.0.113.5".equals(p.exitIp()) && "127.0.0.1".equals(p.tunIp()),
                    "the answer's exit + the socket's own address fill the addresses; got "
                            + p.exitIp() + " / " + p.tunIp());
            check(r, p.port() == 0 && !p.portFound() && stale.equals(p.portWhy())
                    && Math.abs(p.ageMin() - 10.1) < 0.01,
                    "a stale-only log yields port 0 with the stale why (flips if a stale stamp"
                            + " ever yields a port); got port=" + p.port() + " why=" + p.portWhy());
            try {
                VpnLease lease = p.toLease();
                check(r, false, "owner mode must refuse a stale port, got " + lease);
            } catch (IllegalStateException refused) {
                check(r, stale.equals(refused.getMessage()),
                        "owner mode refuses with the stale sentence; got " + refused.getMessage());
            }
        } finally {
            gateway.close();
        }
    }

    private static void leaseBindWords(String r, Path dir) throws Exception {
        VpnLease lease = new VpnLease("203.0.113.5", 51337, "10.2.0.9");
        List<String> v4 = List.of("192.168.1.5");
        OpsSnapshot live = liveAt51000();
        // the owner's two sentences as they read, before the move into BindWords
        String ownerStart = "bind host 10.2.0.2 is not on any local interface (VPN down / stale"
                + " lease?) - the lease's tun 10.2.0.9 is gone; local IPv4: 192.168.1.5 - reconnect"
                + " the VPN and press Start again, or type a host this box holds";
        String ownerBrick = "server is on 0.0.0.0:51000, lease is 203.0.113.5:51337 (tun 10.2.0.9)"
                + " - joiners would dial a dead port; End match, then Start server, then Copy join brick";
        check(r, ownerStart.equals(BindWords.OWNER.notOnInterface("10.2.0.2", lease, v4)),
                "the OWNER Start refusal is byte-identical (flips if an owner-mode word changes)");
        check(r, ownerBrick.equals(BindWords.OWNER.deadPort(live, lease)),
                "the OWNER brick refusal is byte-identical (flips if an owner-mode word changes)");
        String pkgStart = BindWords.PACKAGE.notOnInterface("10.2.0.2", lease, v4);
        String pkgBrick = BindWords.PACKAGE.deadPort(live, lease);
        check(r, ("The listen address 10.2.0.2 is not on this computer any more (ProtonVPN"
                + " disconnected?); its IPv4: 192.168.1.5. Reconnect ProtonVPN and press Start"
                + " again, or type an address this computer holds.").equals(pkgStart),
                "the PACKAGE Start sentence is the design text; got " + pkgStart);
        check(r, ("ProtonVPN now forwards 203.0.113.5:51337 but the server listens on"
                + " 0.0.0.0:51000 - players would dial a dead port. Press End match, then Start"
                + " server, then Copy join brick.").equals(pkgBrick),
                "the PACKAGE brick sentence is the design text; got " + pkgBrick);
        check(r, BindWords.PACKAGE.notOnInterface("10.2.0.2", null, List.of()).contains(
                "its IPv4: (none)."), "an empty IPv4 list reads (none)");
        for (String pkg : List.of(pkgStart, pkgBrick)) {
            String low = pkg.toLowerCase(Locale.ROOT);
            check(r, !low.contains("lease") && !low.contains("pull") && pkg.contains("ProtonVPN"),
                    "a PACKAGE sentence names ProtonVPN and no lease / pull word: " + pkg);
        }
        // the overloads route the words: 6-arg PACKAGE, 5-arg / 4-arg = OWNER
        Set<String> local = Set.of("192.168.1.5");
        check(r, refusal(() -> ServerOpsLaunch.resolveStartBind(lease, null, "", "", local,
                BindWords.PACKAGE)).equals(BindWords.PACKAGE.notOnInterface("10.2.0.9", lease, v4)),
                "resolveStartBind(..., PACKAGE) speaks the PACKAGE words");
        check(r, refusal(() -> ServerOpsLaunch.resolveStartBind(lease, null, "", "", local))
                .equals(BindWords.OWNER.notOnInterface("10.2.0.9", lease, v4)),
                "the 5-arg resolveStartBind keeps the OWNER-mode words");
        check(r, refusal(() -> ServerOpsLaunch.brickEndpoint(lease, null, "-", live,
                BindWords.PACKAGE)).equals(pkgBrick),
                "brickEndpoint(..., PACKAGE) speaks the PACKAGE words");
        check(r, refusal(() -> ServerOpsLaunch.brickEndpoint(lease, null, "-", live))
                .equals(ownerBrick), "the 4-arg brickEndpoint keeps the OWNER-mode words");

        // the mode predicate: value equality against the SAVED fill
        Map<String, String> saved = new HashMap<>();
        saved.put("advertise.from", "protonvpn");
        saved.put("advertise.bind", "10.2.0.9");
        saved.put("advertise", "203.0.113.5:51337");
        check(r, ServerOpsLaunch.protonMode(saved, "10.2.0.9", "203.0.113.5:60000"),
                "a port edit stays in the mode (flips if the whole advertise text is compared)");
        check(r, !ServerOpsLaunch.protonMode(saved, "10.2.0.9", "203.0.113.6:51337"),
                "an advertise address edit leaves the mode");
        check(r, !ServerOpsLaunch.protonMode(saved, "10.2.0.2", "203.0.113.5:51337"),
                "a bind host edit leaves the mode");
        Map<String, String> typed = new HashMap<>(saved);
        typed.put("advertise.from", "typed");
        check(r, !ServerOpsLaunch.protonMode(typed, "10.2.0.9", "203.0.113.5:51337"),
                "advertise.from=typed is out of the mode");
        check(r, !ServerOpsLaunch.protonMode(Map.of(), "", ""), "an empty map is out of the mode");

        // the writers: three rows appended with the file's own separator, game.root byte-intact
        Path file = dir.resolve("launcher.properties");
        byte[] head = "game.root=C:\\x\r\n".getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(dir);
            Files.write(file, head);
        } catch (IOException e) {
            throw new SetupFailure("launcher.properties fixture", e);
        }
        ServerOpsLaunch.writeFillRows(file, "10.2.0.9", "203.0.113.5:51337");
        String after = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        check(r, ("game.root=C:\\x\r\nadvertise.bind=10.2.0.9\r\nadvertise=203.0.113.5:51337\r\n"
                + "advertise.from=protonvpn\r\n").equals(after),
                "writeFillRows writes bind, advertise, mode in order, game.root intact; got " + after);
        Map<String, String> rows = ServeLauncher.readLauncherRows(file, System.out);
        check(r, ServerOpsLaunch.protonMode(rows, "10.2.0.9", "203.0.113.5:51337"),
                "the launcher's own reader of the written rows puts the fill in the mode");
        ServerOpsLaunch.writeModeRow(file, false);
        Map<String, String> left = ServeLauncher.readLauncherRows(file, System.out);
        check(r, "typed".equals(left.get("advertise.from"))
                && !ServerOpsLaunch.protonMode(left, "10.2.0.9", "203.0.113.5:51337"),
                "writeModeRow(false) writes advertise.from=typed and leaves the mode");
    }

    /** The success path: a 1 min old port line + the golden answer
     * end in the exact lease and the exact package fill. */
    private static void leaseExact(String r, Path logDir) throws Exception {
        plant(logDir.resolve("client-logs.txt"), portLine(60, 46337), NOW);
        try (Gateway gw = new Gateway(reply(203, 0, 113, 5))) {
            VpnLeaseProbe.Probe p = VpnLeaseProbe.pull(gw.env(3000, logDir));
            check(r, p.port() == 46337 && p.portFound() && p.portWhy() == null
                    && 0.9 < p.ageMin() && p.ageMin() < 1.1,
                    "a 1 min old port line yields port 46337, no why, age 1.0 (flips if the found"
                            + " return reads 0 or the age drifts); got port=" + p.port() + " why="
                            + p.portWhy() + " age=" + p.ageMin());
            check(r, gw.asked.size() == 1, "ONE request per pull (flips on a retransmit); asked "
                    + gw.askedText());
            VpnLease lease = p.toLease();
            check(r, new VpnLease("203.0.113.5", 46337, "127.0.0.1").equals(lease),
                    "owner mode mints exit 203.0.113.5, port 46337, tun 127.0.0.1 (flips if"
                            + " toLease drops or swaps a field); got " + lease);
            VpnLeaseProbe.Fill blank = VpnLeaseProbe.fill(p, "");
            String found = String.format(Locale.ROOT, VpnLeaseProbe.NOTE_FOUND, "127.0.0.1", 46337,
                    "203.0.113.5", 46337);
            check(r, "127.0.0.1".equals(blank.bindHost()) && "46337".equals(blank.bindPort())
                    && "203.0.113.5:46337".equals(blank.advertise()) && blank.portFound()
                    && found.equals(blank.note()),
                    "the package fill of a found port = tun host, the port, exit:port, the found"
                            + " note (flips if a fill field or the note drifts); got " + blank);
            VpnLeaseProbe.Fill typedOver = VpnLeaseProbe.fill(p, "60000");
            check(r, "46337".equals(typedOver.bindPort())
                    && "203.0.113.5:46337".equals(typedOver.advertise()) && typedOver.portFound(),
                    "the FOUND port outranks a typed 60000 (flips if the typed field wins); got "
                            + typedOver);
        }
    }

    /** The lease script's rotation case: a 0 B newest file falls through to the next;
     * the newer of two files with lines wins. */
    private static void leaseLogRotation(String r, Path dir) throws Exception {
        Path newestEmpty = dir.resolve("a");
        Path olderEmpty = dir.resolve("b");
        Path bothLines = dir.resolve("c");
        plant(newestEmpty.resolve("client-logs.txt"), "", NOW);
        plant(newestEmpty.resolve("client-logs.1.txt"), portLine(60, 46337), NOW.minusSeconds(10));
        plant(olderEmpty.resolve("client-logs.txt"), "", NOW.minusSeconds(10));
        plant(olderEmpty.resolve("client-logs.1.txt"), portLine(60, 46337), NOW);
        // the OLDER file carries the fresher stamp: the modified time orders, never the stamp
        plant(bothLines.resolve("client-logs.txt"), portLine(60, 46337), NOW);
        plant(bothLines.resolve("client-logs.1.txt"), portLine(30, 40001), NOW.minusSeconds(10));
        try (Gateway gw = new Gateway(reply(203, 0, 113, 5))) {
            VpnLeaseProbe.Probe rotated = VpnLeaseProbe.pull(gw.env(3000, newestEmpty));
            check(r, rotated.port() == 46337 && rotated.portWhy() == null,
                    "a 0 B NEWEST client-logs.txt falls through to client-logs.1.txt (flips if a 0 B"
                            + " newest file ends the scan or the scan stops after one file); got port="
                            + rotated.port() + " why=" + rotated.portWhy());
            VpnLeaseProbe.Probe inverse = VpnLeaseProbe.pull(gw.env(3000, olderEmpty));
            check(r, inverse.port() == 46337 && inverse.portWhy() == null,
                    "client-logs.1.txt NEWER than a 0 B client-logs.txt is read first (flips if the"
                            + " glob stops listing client-logs.1.txt); got port=" + inverse.port()
                            + " why=" + inverse.portWhy());
        }
        VpnLeaseProbe.LogHit newer = VpnLeaseProbe.scanDir(bothLines);
        check(r, newer != null && newer.port() == 46337,
                "the NEWER file's port wins over an older file's fresher line (flips if the sort runs"
                        + " oldest first); got " + newer);
    }

    /** A pair line past the {@link VpnLeaseProbe#TAIL_BYTES} tail of the newest file is
     * no hit, and that miss ends the scan. */
    private static void leaseLogTailBound(String r, Path dir) throws Exception {
        Path longFile = dir.resolve("client-logs.txt");
        int size = VpnLeaseProbe.TAIL_BYTES + 10;
        String headLine = portLine(60, 46337);
        plant(longFile, padTo(headLine, "", size), NOW);
        try {
            if (headLine.length() >= 100 || Files.size(longFile) != size) {
                throw new SetupFailure("the long log is not " + size + " B with its pair line in the"
                        + " first 100 B", null);
            }
        } catch (IOException e) {
            throw new SetupFailure("the long log's size", e);
        }
        VpnLeaseProbe.LogHit headOnly = VpnLeaseProbe.scanDir(dir);
        check(r, headOnly == null, "a pair line only in the first 100 B of a TAIL_BYTES + 10 B file"
                + " is past the tail (flips if the tail grows to the whole file or the cut first line"
                + " is kept); got " + headOnly);
        try (Gateway gw = new Gateway(reply(203, 0, 113, 5))) {
            VpnLeaseProbe.Probe p = VpnLeaseProbe.pull(gw.env(3000, dir));
            check(r, p.port() == 0 && VpnLeaseProbe.WHY_NO_PORT_LINE.equals(p.portWhy())
                    && p.ageMin() == -1,
                    "the pull over a tail miss = port 0 with the no-port-line why; got port="
                            + p.port() + " why=" + p.portWhy());
        }
        plant(dir.resolve("client-logs.1.txt"), portLine(60, 40001), NOW.minusSeconds(10));
        VpnLeaseProbe.LogHit pastMiss = VpnLeaseProbe.scanDir(dir);
        check(r, pastMiss == null, "a tail miss on the longer NEWEST file ends the scan - the older"
                + " client-logs.1.txt's line is never read (flips if the whole-read gate is dropped);"
                + " got " + pastMiss);
        plant(longFile, padTo("", portLine(60, 46338), size), NOW);
        VpnLeaseProbe.LogHit inTail = VpnLeaseProbe.scanDir(dir);
        check(r, inTail != null && inTail.port() == 46338,
                "a pair line inside the last 1 KB is read (flips if the tail is taken from the"
                        + " wrong end); got " + inTail);
    }

    /** The ask through the real socket: exactly {@code 00 00}, the golden answer decoded, a
     * private or short answer refused N2; a null log dir = the Linux why.
     * */
    private static void natpmpLoopback(String r) throws Exception {
        try (Gateway gw = new Gateway(reply(203, 0, 113, 5))) {
            VpnLeaseProbe.Probe p = VpnLeaseProbe.pull(gw.env(3000, null));
            check(r, gw.asked.size() == 1 && Arrays.equals(new byte[] {0, 0}, gw.asked.get(0)),
                    "the gateway is asked with EXACTLY 00 00, once (flips if the ask grows a byte or"
                            + " retransmits); asked " + gw.askedText());
            check(r, "203.0.113.5".equals(p.exitIp()) && "127.0.0.1".equals(p.tunIp()),
                    "the golden answer's exit + the socket's own address; got " + p.exitIp()
                            + " / " + p.tunIp());
            check(r, p.port() == 0 && VpnLeaseProbe.WHY_LINUX.equals(p.portWhy()) && p.ageMin() == -1,
                    "a null log dir = port 0 with the Linux sentence, no age (flips if a null dir"
                            + " reads as another why); got port=" + p.port() + " why=" + p.portWhy());
        }
        try (Gateway gw = new Gateway(reply(10, 2, 0, 1))) {
            String refused = pullRefusal(gw.env(3000, null));
            check(r, VpnLeaseProbe.N2.equals(refused), "a private 10.2.0.1 answer is refused N2"
                    + " (flips if a private answer passes); got " + refused);
        }
        try (Gateway gw = new Gateway(Arrays.copyOf(reply(203, 0, 113, 5), 11))) {
            String refused = pullRefusal(gw.env(3000, null));
            check(r, VpnLeaseProbe.N2.equals(refused), "an 11 B answer is refused N2 (flips if the"
                    + " decoder's length guard relaxes); got " + refused);
        }
    }

    /** A silent gateway: N1 within the ask's bound. Stages {@code lease-probe-first}'s fixture
     * (a log whose scan moves {@link VpnLeaseProbe#STAMPS_UNPARSED}) and leaves the counter's
     * delta across the refused pull in {@code unparsedDelta[0]}. */
    private static void natpmpTimeout(String r, Path dir, long[] unparsedDelta) throws Exception {
        plant(dir.resolve("client-logs.txt"), "garbage | Port pair 1->2\r\n", NOW);
        long staged = VpnLeaseProbe.STAMPS_UNPARSED.get();
        VpnLeaseProbe.LogHit control = VpnLeaseProbe.scanDir(dir);
        if (control == null || control.port() != 2 || VpnLeaseProbe.STAMPS_UNPARSED.get() != staged + 1) {
            throw new SetupFailure("the garbage-stamp log does not move STAMPS_UNPARSED by one when"
                    + " scanned (lease-probe-first would be vacuous); got " + control, null);
        }
        try (Gateway gw = new Gateway(null)) {
            long before = VpnLeaseProbe.STAMPS_UNPARSED.get();
            long t0 = System.nanoTime();
            String refused = pullRefusal(gw.env(300, dir));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            unparsedDelta[0] = VpnLeaseProbe.STAMPS_UNPARSED.get() - before;
            check(r, VpnLeaseProbe.N1.equals(refused), "a silent gateway is refused N1 (flips if a"
                    + " timeout stops refusing); got " + refused);
            check(r, ms < 3000, "a 300 ms ask refuses in under 3 s (flips if the timeout stops"
                    + " bounding the ask); took " + ms + " ms");
        }
    }

    /** The gateway is asked FIRST: the refused pull opened no log. */
    private static void leaseProbeFirst(String r, long[] unparsedDelta) throws Exception {
        if (unparsedDelta[0] == Long.MIN_VALUE) {
            throw new SetupFailure("natpmp-timeout's refused pull never ran", null);
        }
        check(r, unparsedDelta[0] == 0, "the refused pull scanned no log - the gateway is asked"
                + " FIRST (flips if pull scans before it asks); STAMPS_UNPARSED moved by "
                + unparsedDelta[0]);
    }

    /** Owner mode never mints a port-0 lease: it throws the why. */
    private static void leaseOwnerRequiresPort(String r) {
        String noLine = leaseRefusal(new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 0,
                VpnLeaseProbe.WHY_NO_PORT_LINE, -1));
        check(r, VpnLeaseProbe.WHY_NO_PORT_LINE.equals(noLine), "a port-0 probe refuses with the"
                + " no-port-line why (flips if owner mode mints a port-0 lease); got " + noLine);
        String stale = String.format(Locale.ROOT, VpnLeaseProbe.WHY_STALE, 12);
        String staleGot = leaseRefusal(new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 0, stale,
                12.4));
        check(r, stale.equals(staleGot), "a port-0 probe with the stale why refuses with it; got "
                + staleGot);
        VpnLease lease = new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 46337, null, 1.0)
                .toLease();
        check(r, new VpnLease("203.0.113.5", 46337, "10.2.0.9").equals(lease),
                "a port-46337 probe is the lease (flips if toLease refuses a found port); got " + lease);
    }

    /** A port-0 probe's package fill: the address only, a typed port kept, the why in the note.
     * */
    private static void leaseAddressOnly(String r) {
        String why = VpnLeaseProbe.WHY_NO_PORT_LINE;
        VpnLeaseProbe.Probe zero = new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 0, why, -1);
        String addressOnly = String.format(Locale.ROOT, VpnLeaseProbe.NOTE_ADDRESS_ONLY, why);
        VpnLeaseProbe.Fill blank = VpnLeaseProbe.fill(zero, "");
        check(r, "10.2.0.9".equals(blank.bindHost()) && "".equals(blank.bindPort())
                && "203.0.113.5:".equals(blank.advertise()) && !blank.portFound(),
                "no port found, none typed = the tun host, a blank port, exit: (flips if a fill"
                        + " field invents a port); got " + blank);
        check(r, (addressOnly + " " + VpnLeaseProbe.P1).equals(blank.note())
                && blank.note().contains(why),
                "the blank fill's note = the why + the type-the-port ask (flips if the note loses"
                        + " the why or P1); got " + blank.note());
        VpnLeaseProbe.Fill typed = VpnLeaseProbe.fill(zero, " 51337 ");
        check(r, "51337".equals(typed.bindPort()) && "203.0.113.5:51337".equals(typed.advertise())
                && !typed.portFound(),
                "a typed port (trimmed) fills port + advertise (flips if a typed port is dropped);"
                        + " got " + typed);
        check(r, addressOnly.equals(typed.note()) && typed.note().endsWith("into port"),
                "no P1 ask over a typed port (flips if P1 lands on a typed port); got " + typed.note());
        check(r, blank.equals(VpnLeaseProbe.fill(zero, null)), "a null typed field = the blank case");
    }

    /** STATIC twin: the script's grammar literals, each at its use site, and the Java
     * constants that twin them. */
    private static void vpnLogGrammarTwin(String r) throws Exception {
        Path script = Paths.get("local", "get-vpn-lease.ps1");
        if (!Files.isRegularFile(script)) {
            // The exported source ships no local/, and a cwd off the
            // repo root has none either - the ServerOpsCoreSelftest serve-mods-lib mirror's idiom:
            // ONE printed line, never a SETUP (the repo root always has the file, so the twin runs there)
            System.out.println("vpn-log-grammar-twin skipped (local/get-vpn-lease.ps1 is not under the cwd "
                    + Paths.get("").toAbsolutePath() + " - the twin runs from the repo root)");
            return;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SetupFailure("local/get-vpn-lease.ps1 unreadable", e);
        }
        // the lease script's own spelling, literal by literal
        for (String literal : List.of("-Filter 'client-logs*.txt'",
                "-Pattern 'Port pair (\\d+)->(\\d+)'", "-match '^(\\S+) \\|'", "$Gateway = '10.2.0.1'",
                "ReceiveTimeout = 3000", "Connect($Gateway, 5351)", "$MaxLeaseAgeMin = 10")) {
            check(r, text.contains(literal), "the script still spells " + literal
                    + " (flips if the script's grammar drifts from VpnLeaseProbe's twin)");
        }
        Pattern pair = VpnLeaseProbe.PORT_PAIR;
        check(r, "Port pair (\\d+)->(\\d+)".equals(pair.pattern())
                && (pair.flags() & Pattern.CASE_INSENSITIVE) != 0,
                "PORT_PAIR twins the script's -Pattern, case-insensitive like Select-String (flips if"
                        + " the Java grammar drifts); got " + pair.pattern() + " flags " + pair.flags());
        check(r, "^(\\S+) \\|".equals(VpnLeaseProbe.STAMP.pattern()),
                "STAMP twins the script's -match (flips if it drifts); got "
                        + VpnLeaseProbe.STAMP.pattern());
        check(r, "10.2.0.1".equals(VpnLeaseProbe.GATEWAY_HOST) && VpnLeaseProbe.GATEWAY_PORT == 5351,
                "the gateway is 10.2.0.1:5351 on both sides (flips if either drifts)");
        check(r, VpnLeaseProbe.ASK_TIMEOUT_MS == 3000 && VpnLeaseProbe.MAX_AGE_MIN == 10,
                "the 3000 ms receive + the 10 min age twin the script (flips if either drifts)");
        check(r, "client-logs*.txt".equals(VpnLeaseProbe.LOG_GLOB),
                "LOG_GLOB twins the script's -Filter (flips if it drifts)");
    }

    /** Every host-facing ProtonVPN text names none of the owner-mode tooling words.
     * */
    private static void leaseTextLaw(String r) throws Exception {
        Map<String, String> texts = new TreeMap<>();
        for (Field f : VpnLeaseProbe.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) && Modifier.isFinal(mod) && f.getType() == String.class) {
                f.setAccessible(true);
                texts.put(f.getName(), (String) f.get(null));
            }
        }
        check(r, texts.size() >= 15, "the reflection sees >= 15 static final String fields of"
                + " VpnLeaseProbe (flips if it finds nothing); saw " + texts.keySet());
        VpnLease lease = new VpnLease("203.0.113.5", 51337, "10.2.0.9");
        texts.put("BindWords.PACKAGE.notOnInterface",
                BindWords.PACKAGE.notOnInterface("10.2.0.2", null, List.of()));
        texts.put("BindWords.PACKAGE.deadPort", BindWords.PACKAGE.deadPort(liveAt51000(), lease));
        for (Map.Entry<String, String> e : texts.entrySet()) {
            String text = e.getValue();
            check(r, text != null && !text.isBlank(), e.getKey() + " is non-blank");
            String low = text.toLowerCase(Locale.ROOT);
            for (String banned : HOST_TEXT_BANS) {
                check(r, !low.contains(banned), e.getKey() + " carries no '" + banned + "' (flips"
                        + " if a host-facing text names the repo tooling): " + text);
            }
        }
    }

    /** A live snapshot of a server on 0.0.0.0:51000 (the brick sentences' fixture, shared by
     * {@code lease-bind-words} and {@code lease-text-law}). */
    private static OpsSnapshot liveAt51000() {
        return OpsSnapshot.parse(OpsStatusFile.composeLines(1L, 1L, "lobby",
                "0.0.0.0", 51000, 7, 0, 0, 0, 4, 60.0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, "off",
                List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY));
    }

    /** A log line stamped {@code ageSec} before {@link #NOW} naming forwarded port {@code port}, CRLF-ended.
     * */
    private static String portLine(long ageSec, int port) {
        return OffsetDateTime.ofInstant(NOW.minusSeconds(ageSec), ZoneOffset.UTC)
                + " | Port pair 1->" + port + "\r\n";
    }

    /** {@code head} + {@code x\n} padding + {@code tail}, exactly {@code total} ASCII bytes (an odd
     * pad opens with one empty line, so no pad byte joins a pair line).
     * */
    private static String padTo(String head, String tail, int total) {
        int pad = total - head.length() - tail.length();
        return head + (pad % 2 == 1 ? "\n" : "") + "x\n".repeat(pad / 2) + tail;
    }

    /** Writes {@code text} to {@code file} (parents made) with modified time {@code modified};
     * a failure is the row's SETUP. */
    private static void plant(Path file, String text, Instant modified) throws SetupFailure {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
            Files.setLastModifiedTime(file, FileTime.from(modified));
        } catch (IOException e) {
            throw new SetupFailure("planted log " + file.getFileName(), e);
        }
    }

    /** The {@link IllegalStateException} message {@code pull(env)} throws; {@code "(no refusal ...)"}
     * when it returns. */
    private static String pullRefusal(VpnLeaseProbe.Env env) throws IOException {
        try {
            VpnLeaseProbe.Probe p = VpnLeaseProbe.pull(env);
            return "(no refusal: exit " + p.exitIp() + ", port " + p.port() + ")";
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
    }

    /** The {@link IllegalStateException} message {@code p.toLease()} throws; {@code "(no refusal ...)"}
     * when it mints a lease. */
    private static String leaseRefusal(VpnLeaseProbe.Probe p) {
        try {
            return "(no refusal: " + p.toLease() + ")";
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
    }

    /**
     * A loopback fake NAT-PMP gateway: answers EVERY request with {@code answer} ({@code null} =
     * never answers - the silent gateway), keeping each request's bytes in {@link #asked}. No file
     * I/O here (a nested class is its own residency-gate entry).
     */
    private static final class Gateway implements AutoCloseable {
        final List<byte[]> asked = new CopyOnWriteArrayList<>();
        private final DatagramSocket socket;

        Gateway(byte[] answer) throws SetupFailure {
            try {
                socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            } catch (IOException e) {
                throw new SetupFailure("loopback gateway", e);
            }
            Thread responder = new Thread(() -> serve(answer), "cc-vpn-lease-selftest-gw");
            responder.setDaemon(true);
            responder.start();
        }

        private void serve(byte[] answer) {
            try {
                while (true) {
                    DatagramPacket in = new DatagramPacket(new byte[64], 64);
                    socket.receive(in);
                    asked.add(Arrays.copyOf(in.getData(), in.getLength()));
                    if (answer != null) {
                        socket.send(new DatagramPacket(answer, answer.length, in.getSocketAddress()));
                    }
                }
            } catch (IOException ended) {
                // close() ends the loop here (said nothing); any other end is said once - the
                // unanswered pull then fails its row on N1
                if (!socket.isClosed()) {
                    System.out.println("vpn-lease selftest: fake gateway stopped ("
                            + ended.getClass().getSimpleName() + ")");
                }
            }
        }

        /** A pull env aimed at this gateway at {@link #CLOCK}; {@code logDir} null = no log. */
        VpnLeaseProbe.Env env(int timeoutMs, Path logDir) {
            return new VpnLeaseProbe.Env(InetAddress.getLoopbackAddress(), socket.getLocalPort(),
                    timeoutMs, logDir, CLOCK);
        }

        String askedText() {
            return asked.stream().map(Arrays::toString).toList().toString();
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    /** The {@link IllegalArgumentException} message {@code call} throws; {@code "(no refusal)"}
     * when it returns (the caller's equality check then fails under its row name). */
    private static String refusal(Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
        return "(no refusal)";
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(walk.toList());
            all.sort(Comparator.reverseOrder());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException residue) {
            System.out.println("vpn-lease selftest: temp residue at " + dir + " ("
                    + residue.getClass().getSimpleName() + ")");
        }
    }
}
