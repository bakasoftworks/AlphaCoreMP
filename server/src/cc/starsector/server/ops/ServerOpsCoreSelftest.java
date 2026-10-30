package cc.starsector.server.ops;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.runtime.BanSet;
import cc.starsector.server.runtime.CidDigest;
import cc.starsector.server.runtime.ClientDebugLease;
import cc.starsector.server.runtime.OperatorCommandFile;
import cc.starsector.server.runtime.SimTickRate;
import cc.starsector.server.match.ContentFingerprintMint;
import static cc.starsector.server.ops.LaneFollow.*;
import static cc.starsector.server.ops.OpsMatchApply.*;
import static cc.starsector.server.ops.OpsMatchRows.*;
import static cc.starsector.server.ops.ServerOpsCore.*;
import static cc.starsector.server.ops.ServerOpsLaunch.*;

/**
 * The headless logic-layer selftest of {@link ServerOpsCore} + {@link ServerOpsLaunch} + {@link OpsMatchRows} +
 * {@link OpsMatchApply} + {@link LaneFollow}, carved out of the core: the
 * build gate runs it first ({@code OpsSelftests --selftest}), then the
 * window-model press rehearsal. Body moved VERBATIM from {@code ServerOpsCore.selftest()}
 * (renamed {@link #run}); the rows read top-down by family - verb-line / sim-speed goldens,
 * append-write, state ladder + writer liveness, staleness, headroom + byte format, lease-KV,
 * Start arg vector, end-state polish, the row editors (tick / map-size / fortressDp / wave scale
 * / joiner default), map-size apply on a temp fixture tree, prefill, password sidecar +
 * descriptor selector + armed door, the Start flow ui1..ui17 through {@link ScriptedUi}, the W10
 * watch flow + brief forward, lane-follow decision, web guards, status JSON, incident shapes,
 * lease ceiling, re-fit policy, operator-brief render. A failed {@link #check} /
 * {@link #checkThrows} row prints {@code FAIL <what>} and counts, and {@link #run} throws ONE
 * {@link AssertionError} naming the count at its end (a rig / setup throw still ends the chain at once);
 * Runs in the gate JVM only; temp-dir fixtures only.
 */
public final class ServerOpsCoreSelftest {
    private ServerOpsCoreSelftest() {
    }

    /**
     * The failed rows of the running {@link #run}: {@link #check} / {@link #checkThrows} print and count instead of
     * throwing, so one failed row never hides the rest of its own chain; {@link #run} resets it first and throws once
     * at its end, so the gate's per-chain guard still counts the chain and the exit path is unchanged. Main-thread
     * only (every check runs on the selftest's own thread).
     */
    private static int failures;

    /** Structure mirrors the OpsStatusContract grammar; values echo the N-sweep scale. */
    private static final List<String> FIXTURE = List.of(
            "ccops.v=1",
            "stamp_ms=1700000000000",
            "pid=4242",
            "phase=run",
            "host=127.0.0.1",
            "port=51888",
            "up_s=125",
            "round=0",
            "frame=7500",
            "sim_tick=7500",
            "seats_total=2",
            "clients=2",
            "tick_per_s=60.0",
            "util_pct=19.4",
            "work_max_ms=16.0",
            "ph.frames=60",
            "ph.pre.mean_ms=0.015",
            "ph.pre.p95_ms=0.040",
            "ph.step.mean_ms=2.310",
            "ph.step.p95_ms=4.870",
            "ph.det.mean_ms=0.060",
            "ph.det.p95_ms=0.120",
            "ph.comp.mean_ms=0.480",
            "ph.comp.p95_ms=0.910",
            "ph.send.mean_ms=0.130",
            "ph.send.p95_ms=0.320",
            "ph.intent.mean_ms=0.020",
            "ph.intent.p95_ms=0.050",
            "ph.tail.mean_ms=0.010",
            "ph.tail.p95_ms=0.030",
            "bytes_sent_total=13643481",
            "bytes_recv_total=144371",
            "sent_bps=151594",
            "recv_bps=1604",
            "overrun_frames=7",
            "debt_ms=0.0",
            "send_failures=0",
            "command_file=out/ops/ops-cmd.txt",
            "seat.count=1",
            "seat.1.seat=1",
            "seat.1.client=mnp-d1",
            "seat.1.name=Dee", // D3: the announced player name column
            "seat.1.endpoint=127.0.0.1:58931",
            "seat.1.ship=mudskipper2_cc_Hellbore",
            "seat.1.live=true",
            "seat.1.tx_bytes=6800000",
            "seat.1.rx_bytes=52000",
            "seat.1.tx_bps=75000",
            "seat.1.rx_bps=580",
            "seat.1.ctrl_depth=0");

    /** A standalone entry (runs this selftest alone); the build
     *  gate still runs {@link #run} through {@code OpsSelftests --selftest}. A failed row throws (exit 1). */
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        run();
        System.out.println("ServerOpsCoreSelftest PASS");
    }

    public static void run() throws Exception {
        failures = 0;
        // The roots record FIRST (every flow row below reads it), then the two
        // package-mode row groups (the mains, the Start chain) - distinct methods.
        rootsRows();
        pkgMainsRows();
        pkgChainRows();
        matchShapeCoreRows();
        packageModSyncRows();
        reachAndModsRemoveRows();
        launcherBootRows();

        // verb-line goldens, round-tripped through the REAL operator-file grammar
        check("kick 3".equals(verbLine(OperatorCommandFile.Verb.KICK, 3)), "kick golden");
        check("end-round".equals(verbLine(OperatorCommandFile.Verb.END_ROUND, 0)),
                "end-round golden");
        check("end-match".equals(verbLine(OperatorCommandFile.Verb.END_MATCH, 0)),
                "end-match golden");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.KICK, 0), "kick 0 rejected");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.KICK, -2), "kick negative rejected");
        OperatorCommandFile.ParseResult roundTrip = OperatorCommandFile.parse(List.of(
                verbLine(OperatorCommandFile.Verb.KICK, 7),
                verbLine(OperatorCommandFile.Verb.END_ROUND, 0),
                verbLine(OperatorCommandFile.Verb.END_MATCH, 0)));
        check(roundTrip.rejected().isEmpty() && roundTrip.commands().size() == 3
                && roundTrip.commands().get(0).equals(
                        new OperatorCommandFile.Command(OperatorCommandFile.Verb.KICK, 7, 0f, false))
                && roundTrip.commands().get(1).verb() == OperatorCommandFile.Verb.END_ROUND
                && roundTrip.commands().get(2).verb() == OperatorCommandFile.Verb.END_MATCH,
                "verb lines round-trip through OperatorCommandFile.parse");

        // sim-speed minter: goldens + reject-never-clamp, round-tripped through
        // the REAL grammar (Float.toString <-> Float.parseFloat is exact for any float)
        check("set-sim-speed 1.5".equals(simSpeedLine(1.5f)), "set-sim-speed golden");
        check("set-sim-speed 0.25".equals(simSpeedLine(0.25f)), "set-sim-speed min golden");
        check("set-sim-speed 2.0".equals(simSpeedLine(2.0f)), "set-sim-speed max golden");
        checkThrows(() -> simSpeedLine(2.5f), "sim-speed over-range rejected");
        checkThrows(() -> simSpeedLine(0.2f), "sim-speed under-range rejected");
        checkThrows(() -> simSpeedLine(Float.NaN), "sim-speed NaN rejected");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.SET_SIM_SPEED, 0),
                "verbLine refuses SET_SIM_SPEED (simSpeedLine is the minter)");
        OperatorCommandFile.ParseResult speedTrip = OperatorCommandFile.parse(List.of(
                simSpeedLine(0.25f), simSpeedLine(2.0f), simSpeedLine(1.5f)));
        check(speedTrip.rejected().isEmpty() && speedTrip.commands().size() == 3
                && speedTrip.commands().get(0).equals(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.SET_SIM_SPEED, 0, 0.25f, false))
                && speedTrip.commands().get(2).simSpeed() == 1.5f,
                "sim-speed lines round-trip through OperatorCommandFile.parse");

        // sim-speed-auto minter goldens + verbLine refusal + round-trip through
        // the REAL grammar (arm/disarm ride Command.autoOn()).
        check("sim-speed-auto on".equals(simSpeedAutoLine(true)), "sim-speed-auto on golden");
        check("sim-speed-auto off".equals(simSpeedAutoLine(false)), "sim-speed-auto off golden");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.SET_SIM_SPEED_AUTO, 0),
                "verbLine refuses SET_SIM_SPEED_AUTO (simSpeedAutoLine is the minter)");
        OperatorCommandFile.ParseResult autoTrip = OperatorCommandFile.parse(List.of(
                simSpeedAutoLine(true), simSpeedAutoLine(false)));
        check(autoTrip.rejected().isEmpty() && autoTrip.commands().size() == 2
                && autoTrip.commands().get(0).equals(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.SET_SIM_SPEED_AUTO, 0, 0f, true))
                && !autoTrip.commands().get(1).autoOn(),
                "sim-speed-auto lines round-trip through OperatorCommandFile.parse");

        // append-write semantics (two clicks inside one 250 ms consume window both land)
        Path tmp = Files.createTempDirectory("cc-ops-gui-selftest");
        Path cmd = tmp.resolve("ops-cmd.txt");
        appendVerb(cmd, verbLine(OperatorCommandFile.Verb.END_ROUND, 0));
        appendVerb(cmd, verbLine(OperatorCommandFile.Verb.KICK, 2));
        check("end-round\nkick 2\n".equals(Files.readString(cmd, StandardCharsets.UTF_8)),
                "append-write byte golden (create + append)");
        OperatorCommandFile.ParseResult appended =
                OperatorCommandFile.parse(Files.readAllLines(cmd, StandardCharsets.UTF_8));
        check(appended.commands().size() == 2
                && appended.commands().get(0).verb() == OperatorCommandFile.Verb.END_ROUND
                && appended.commands().get(1).seatId() == 2,
                "appended file parses in click order");
        Files.delete(cmd);
        appendVerb(cmd, verbLine(OperatorCommandFile.Verb.END_MATCH, 0));
        check("end-match\n".equals(Files.readString(cmd, StandardCharsets.UTF_8)),
                "append recreates a consumed (deleted) file");

        // state ladder (LISTENING ignores staleness by design — lobby blocks in await; a dead
        // writer outranks phase/staleness on every non-final rung; GONE only
        // when it died on THIS shell's watch, else a calm STOPPED leftover)
        check(deriveState("lobby", false, true, false) == ServerState.LISTENING,
                "lobby fresh -> LISTENING");
        check(deriveState("lobby", true, true, false) == ServerState.LISTENING,
                "lobby stale -> LISTENING");
        check(deriveState("lobby", true, false, true) == ServerState.GONE,
                "lobby + dead writer seen alive by this shell -> GONE");
        check(deriveState("lobby", true, false, false) == ServerState.STOPPED,
                "lobby + dead writer never seen alive -> STOPPED (the previous-session leftover, calmly)");
        check(deriveState("run", false, true, false) == ServerState.RUN, "run fresh -> RUN");
        check(deriveState("run", true, true, true) == ServerState.STALE,
                "run stale + alive -> STALE");
        check(deriveState("run", false, false, true) == ServerState.GONE,
                "run fresh + dead on watch -> GONE");
        check(deriveState("run", true, false, true) == ServerState.GONE,
                "run stale + dead on watch -> GONE");
        check(deriveState("run", true, false, false) == ServerState.STOPPED,
                "run stale + dead, never seen alive -> STOPPED leftover");
        check(deriveState("final", false, true, false) == ServerState.EXITED,
                "final fresh -> EXITED");
        check(deriveState("final", true, false, true) == ServerState.EXITED,
                "final stale + dead -> EXITED (a clean end is EXPECTED to be gone)");
        check(deriveState("bogus", false, true, false) == ServerState.STOPPED,
                "unknown phase -> STOPPED");
        check(startEnabled(ServerState.GONE) && startEnabled(ServerState.STOPPED)
                && startEnabled(ServerState.EXITED) && !startEnabled(ServerState.LISTENING)
                && !startEnabled(ServerState.RUN) && !startEnabled(ServerState.STALE),
                "Start offered exactly when no server can be running");
        check(liveServer(ServerState.STALE) && liveServer(ServerState.LISTENING)
                && !liveServer(ServerState.GONE) && !liveServer(ServerState.EXITED),
                "commandable/killable rungs");
        // writer liveness: own pid stamped now = alive; stamped before this JVM started = a
        // recycled pid, not the writer; an absent pid = dead
        long selfPid = ProcessHandle.current().pid();
        check(writerAlive(selfPid, System.currentTimeMillis()), "own pid stamped now -> alive");
        check(!writerAlive(selfPid, 0L), "own pid stamped before own start -> pid reuse, dead");
        check(!writerAlive(2_000_000_000L, System.currentTimeMillis()), "absent pid -> dead");

        // staleness boundary against the real parser (> threshold, not >=)
        OpsSnapshot snap = OpsSnapshot.parse(FIXTURE);
        check(!snap.isStale(1700000000000L + STALE_THRESHOLD_MS, STALE_THRESHOLD_MS),
                "at-threshold not stale");
        check(snap.isStale(1700000000000L + STALE_THRESHOLD_MS + 1, STALE_THRESHOLD_MS),
                "past-threshold stale");

        // headroom math: file rates are bytes/s, uplinks are megabits/s
        check(egressPercentOfUplink(1_250_000L, 10.0) == 100.0, "1.25 MB/s == 100% of 10 Mbps");
        check(egressPercentOfUplink(625_000L, 10.0) == 50.0, "625 KB/s == 50% of 10 Mbps");
        check(egressPercentOfUplink(1_250_000L, 0.0) == 0.0, "unset uplink reads 0, not inf");

        // byte-format + table-row goldens
        check("580 B".equals(bytesText(580L)), "bytes golden B");
        check("75.0 KB".equals(bytesText(75_000L)), "bytes golden KB");
        check("6.8 MB".equals(bytesText(6_800_000L)), "bytes golden MB");
        check("13.6 MB".equals(bytesText(13_643_481L)), "bytes golden MB rounding");
        check("151.6 KB/s".equals(bpsText(151_594L)), "bps golden");
        Object[] row = seatTableRow(snap.seats().get(0));
        check(row.length == SEAT_COLUMNS.length, "row width matches columns");
        check(Integer.valueOf(1).equals(row[0]) && "Dee".equals(row[1]) // D3 name column
                && "mnp-d1".equals(row[2])
                && "mudskipper2_cc_Hellbore".equals(row[3]) && "127.0.0.1:58931".equals(row[4])
                && "live".equals(row[5]) && "75.0 KB/s".equals(row[6]) && "580 B/s".equals(row[7])
                && "6.8 MB".equals(row[8]) && "52.0 KB".equals(row[9])
                && Integer.valueOf(0).equals(row[10]),
                "seat table-row golden");

        // The TWO Start vectors (re-pinned from the
        // retired pwsh startCommand goldens): the owner-mode STAGE through the stage tool, then the
        // SAME ServeLauncher a stranger runs - absolute --pkg / --config, no pwsh, this JVM's java.
        {
            Path vecRoot = Files.createTempDirectory("cc-ops-vec");
            Path vecPkg = vecRoot.resolve("ops-pkg");
            Path vecCfg = vecRoot.resolve("cc-server.properties");
            check(List.of("pwsh", "-NoProfile", "-File", STAGE_TOOL, "-Stage", vecPkg.toString(),
                    "-ServerConfig", vecCfg.toString(), "-BindHost", "10.2.0.2", "-BindPort",
                    "51820", "-OwnerPid", "4242").equals(
                            stageCommand(vecPkg, vecCfg, "10.2.0.2", 51820, 4242L)),
                    "stage-vector: the stage golden (flips if a -Stage / -ServerConfig / bind /"
                            + " -OwnerPid arg drifts from the stage tool's contract)");
            List<String> launch = launchCommand(vecPkg, vecCfg);
            Path javaBin = Paths.get(System.getProperty("java.home"), "bin");
            // The launcher JVM carries the serve's two crash-file flags before -cp.
            check(launch.size() == 10 && Paths.get(launch.get(0)).getParent().equals(javaBin)
                    && launch.subList(1, 10).equals(List.of("-XX:-CreateCoredumpOnCrash",
                            "-XX:ErrorFile=" + vecPkg.resolve("run").resolve("hs_err_pid%p.log"), "-cp",
                            vecPkg.resolve("classes").toString(),
                            "cc.starsector.server.ops.ServeLauncher", "--pkg", vecPkg.toString(),
                            "--config", vecCfg.toString()))
                    && launch.stream().noneMatch(a -> a.contains("pwsh")),
                    "launch-vector: java.home's java -XX:-CreateCoredumpOnCrash -XX:ErrorFile=<pkg>/run/..."
                            + " -cp <pkg>/classes ServeLauncher --pkg --config (flips if --pkg goes implicit,"
                            + " a path goes relative, a crash flag drops or pwsh returns)");
            check(launchCommand(Paths.get("rel-pkg"), Paths.get("rel.properties")).get(7)
                    .equals(Paths.get("rel-pkg").toAbsolutePath().toString()),
                    "launch-vector-abs: a relative pkg root is made absolute (flips if --pkg rides"
                            + " the GUI cwd unresolved)");
            // The README's timer opt-out reaches a console Start too
            List<String> off = launchCommand(vecPkg, vecCfg, " OFF ");
            check(off.size() == 11 && off.get(0).equals(launch.get(0))
                    && off.get(1).equals("-D" + TimerOptOut.KNOB_PROPERTY + "=off")
                    && off.subList(2, 11).equals(launch.subList(1, 10)),
                    "launch-vector-optout: a console started with -Dcc.ops.timerOptOut=off passes the"
                            + " CONSTANT on to the launcher JVM before -cp (flips if the pass-through"
                            + " drops, rides the host's text, or lands after the main class)");
            check(launchCommand(vecPkg, vecCfg, null).equals(launch)
                    && launchCommand(vecPkg, vecCfg, "on").equals(launch)
                    && launchCommand(vecPkg, vecCfg, "offline").equals(launch),
                    "launch-vector-optout-absent: an unset / on / other knob adds nothing (flips if"
                            + " any value but off passes)");
        }
        check("51337".equals(DEFAULT_BIND_PORT), "default bind port value golden");
        checkThrows(() -> validateBind("10.2.0.2", "0"),
                "port 0/auto refused (supervised rebirth needs the pinned port)");
        checkThrows(() -> validateBind("", "51337"), "blank bind host rejected");
        checkThrows(() -> validateBind("10.2.0.2 evil", "51337"),
                "bind host with whitespace rejected");
        checkThrows(() -> validateBind("10.2.0.2", "x"), "non-numeric bind port rejected");
        checkThrows(() -> validateBind("10.2.0.2", "-1"), "negative bind port rejected");
        checkThrows(() -> validateBind("10.2.0.2", "65536"), "oversize bind port rejected");
        validateBind(" 127.0.0.1 ", DEFAULT_BIND_PORT); // the pinned default passes (throws = FAIL)

        // The lease-first Start bind — FIXTURE address sets, never
        // the real NICs (ui4 below still passes 10.2.0.2 into the golden vector untouched).
        {
            Set<String> local = Set.of("127.0.0.1", "10.2.0.2", "192.168.1.20",
                    "0:0:0:0:0:0:0:1");
            VpnLease lease = new VpnLease("203.0.113.7", 53185, "10.2.0.2");
            StartBind sb = resolveStartBind(lease, null, "127.0.0.1", DEFAULT_BIND_PORT, local);
            check("10.2.0.2".equals(sb.bindHost()) && "53185".equals(sb.bindPort())
                    && "203.0.113.7:53185".equals(sb.advertise()),
                    "lease OK = bind tun:port, advertise exit:port (the dev default is overridden)");
            checkThrows(() -> resolveStartBind(null, "timeout", "127.0.0.1", DEFAULT_BIND_PORT,
                    local), "lease FAIL + dev-default bind REFUSED (friend test #1)");
            try {
                resolveStartBind(null, "pull timed out\nsecond line", " 127.0.0.1 ",
                        DEFAULT_BIND_PORT, local);
                check(false, "dev-default refusal throws");
            } catch (IllegalArgumentException e) {
                check(e.getMessage().contains("pull timed out") && !e.getMessage().contains("\n")
                        && e.getMessage().contains("dev default 127.0.0.1:51337"),
                        "the refusal names the pull failure's first line + the default pair");
            }
            StartBind lan = resolveStartBind(null, "timeout", " 192.168.1.20 ", "51820", local);
            check("192.168.1.20".equals(lan.bindHost()) && "51820".equals(lan.bindPort())
                    && lan.advertise() == null, "lease FAIL + a typed LAN host on a NIC stands");
            check("127.0.0.1".equals(resolveStartBind(null, "t", "127.0.0.1", "51820", local)
                    .bindHost()), "loopback on a NON-default port is a deliberate local serve");
            check("0.0.0.0".equals(resolveStartBind(null, "t", "0.0.0.0", "51820", local)
                    .bindHost()), "the wildcard passes the interface check");
            checkThrows(() -> resolveStartBind(null, "t", "192.0.2.1", "51820", local),
                    "a host on no local interface is REFUSED (friend test #2)");
            checkThrows(() -> resolveStartBind(new VpnLease("1.2.3.4", 5, "10.9.9.9"), null,
                    "x", "1", local), "a stale lease whose tun is gone is REFUSED");
            checkThrows(() -> resolveStartBind(null, "t", "10.2.0.2", "51820", Set.of()),
                    "enumeration failure (empty set) refuses every explicit host");
            check("10.2.0.2".equals(canonicalHost("10.2.0.2"))
                    && "0:0:0:0:0:0:0:1".equals(canonicalHost("::1"))
                    && "no.such.host.invalid".equals(canonicalHost("no.such.host.invalid")),
                    "canonical host: literals round-trip, unresolvable stays itself");
            check(portOf("1.2.3.4:51820") == 51820 && portOf("nocolon") == -1
                    && portOf("a:b") == -1, "portOf reads the last colon's digits");
            check("no reason given".equals(oneLine(null)) && "x".equals(oneLine(" x \n y"))
                    && oneLine("a\tb").equals("a?b"), "oneLine: first line, printable ASCII");
        }
        // The brick vs the RUNNING serve's bound endpoint.
        {
            VpnLease lease = new VpnLease("203.0.113.7", 53185, "10.2.0.2");
            OpsSnapshot on53185 = OpsSnapshot.parse(OpsStatusFile.composeLines(1L, 1L, "lobby",
                    "10.2.0.2", 53185, 7, 0, 0, 0, 4, 60.0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, "off",
                    List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY));
            OpsSnapshot on64931 = OpsSnapshot.parse(OpsStatusFile.composeLines(1L, 1L, "lobby",
                    "10.2.0.2", 64931, 7, 0, 0, 0, 4, 60.0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, "off",
                    List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY));
            OpsSnapshot otherTun = OpsSnapshot.parse(OpsStatusFile.composeLines(1L, 1L, "lobby",
                    "10.2.0.9", 53185, 7, 0, 0, 0, 4, 60.0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, "off",
                    List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY));
            OpsSnapshot wildcard = OpsSnapshot.parse(OpsStatusFile.composeLines(1L, 1L, "lobby",
                    "0.0.0.0", 53185, 7, 0, 0, 0, 4, 60.0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, "off",
                    List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY));
            check("203.0.113.7:53185".equals(brickEndpoint(lease, null, "-", null))
                    && "203.0.113.7:53185".equals(brickEndpoint(lease, null, "-", on53185))
                    && "203.0.113.7:53185".equals(brickEndpoint(lease, null, "-", wildcard)),
                    "lease OK: no serve / the serve on the lease's port / a wildcard bind = the lease endpoint");
            checkThrows(() -> brickEndpoint(lease, null, "-", on64931),
                    "lease OK but the serve is on another port = REFUSED (friend test #1)");
            checkThrows(() -> brickEndpoint(lease, null, "-", otherTun),
                    "lease OK but the serve's non-wildcard host is not the tun = REFUSED");
            try {
                brickEndpoint(lease, null, "-", on64931);
                check(false, "moved-port refusal throws");
            } catch (IllegalArgumentException e) {
                check(e.getMessage().startsWith("server is on 10.2.0.2:64931, lease is"
                        + " 203.0.113.7:53185") && e.getMessage().contains("End match, then Start server"),
                        "the refusal names both endpoints and the restart recipe (the web console's verbs)");
            }
            checkThrows(() -> brickEndpoint(null, "timeout", "-", on53185),
                    "lease FAIL + no advertise ever = REFUSED");
            checkThrows(() -> brickEndpoint(null, "timeout", "203.0.113.7:53185", null),
                    "lease FAIL + no running serve to confirm against = REFUSED");
            check("203.0.113.7:53185".equals(brickEndpoint(null, "timeout",
                    " 203.0.113.7:53185 ", on53185)),
                    "lease FAIL + the form's endpoint on the RUNNING serve's port stands");
            checkThrows(() -> brickEndpoint(null, "timeout", "203.0.113.7:53185", on64931),
                    "lease FAIL + the form's port is not the serve's = REFUSED (never a dead brick)");
        }
        // The supervisor receipt → per-life failure (the run-supervised.ps1
        // grammar; the flight-20260830-002633 shape verbatim).
        {
            LifeFailure f = parseLifeFailure(List.of("life=1 pid=14788", "life=1 exit=1",
                    "life=1 archived=2", "life=2 pid=21304"));
            check(f != null && f.life() == 1 && f.exitCode() == 1 && !f.exhausted()
                    && f.exceptionLine().isEmpty()
                    && "life 1/6 exited code 1 - supervisor relaunching".equals(f.detailText()),
                    "receipt: the last non-zero exit, relaunching");
            check(parseLifeFailure(List.of("life=1 pid=1", "life=1 exit=1", "life=2 pid=2",
                    "life=2 exit=0", "clean_exit=true lives=2")) == null,
                    "receipt: a clean last exit = no failure");
            check(parseLifeFailure(List.of()) == null
                    && parseLifeFailure(List.of("life=x exit=y", "garbage")) == null,
                    "receipt: empty / malformed = no failure");
            LifeFailure done = parseLifeFailure(List.of("life=6 pid=9", "life=6 exit=1",
                    "relaunch_budget_exhausted=true lives=6"));
            check(done != null && done.life() == 6 && done.exhausted()
                    && done.detailText().endsWith(" - relaunch budget exhausted"),
                    "receipt: budget exhausted");
            Path lane = Files.createTempDirectory("cc-life");
            Path pointer = lane.resolve("latest.txt");
            Path flight = lane.resolve("flight-20260830-002633");
            Files.createDirectories(flight.resolve("lives"));
            Files.writeString(pointer, "flight-20260830-002633\n", StandardCharsets.UTF_8);
            Files.writeString(flight.resolve(RECEIPT_FILE),
                    "life=1 pid=14788\nlife=1 exit=1\nlife=1 archived=2\nlife=2 pid=21304\n",
                    StandardCharsets.UTF_8);
            Files.writeString(flight.resolve("lives").resolve("life-1.err.log"),
                    "OpenJDK 64-Bit Server VM warning: Options -Xverify:none were deprecated\n"
                            + "[cc-transform] launch gate PASS: HOOK-0043 — 3 fingerprints\n"
                            + "java.net.BindException: Cannot assign requested address: bind\n"
                            + "\tat java.base/sun.nio.ch.Net.bind0(Native Method)\n",
                    StandardCharsets.UTF_8);
            long stamp = Files.getLastModifiedTime(pointer).toMillis();
            LifeFailure live = lifeFailure(pointer, stamp - 5_000L);
            check(live != null && live.life() == 1 && ("java.net.BindException: Cannot assign"
                    + " requested address: bind").equals(live.exceptionLine())
                    && live.detailText().startsWith("life 1/6 exited code 1 - java.net."
                            + "BindException: Cannot assign requested address: bind - supervisor"),
                    "pointer -> receipt -> err log: the exception line rides the detail");
            check(lifeFailure(pointer, stamp + POINTER_SLACK_MS + 60_000L) == null,
                    "a pointer stamped before the press is a previous flight's = null");
            check(lifeFailure(lane.resolve("no-such-pointer.txt"), 0L) == null
                    && lifeFailure(null, 0L) == null, "missing / null pointer = null");
            Files.writeString(pointer, "..\\..\\etc\n", StandardCharsets.UTF_8);
            check(lifeFailure(pointer, 0L) == null, "a pointer off the flight-<stamp> shape = null");
            check(firstExceptionLine(flight.resolve("lives").resolve("life-9.err.log")).isEmpty(),
                    "missing err log = no exception line");
            // A premain refusal ends the JVM in the agent; its row,
            // ahead of the JVM's trace, reaches the host in words with the remedy for its family.
            Path refusedErr = Files.writeString(flight.resolve("lives").resolve("life-2.err.log"),
                    "[cc-premain] pilotship: ON from=file\n"
                            + "[cc-premain] pilotship: refused ship_fingerprint; set pilotship.enabled=false to start\n"
                            + "Exception in thread \"main\" java.lang.reflect.InvocationTargetException\n"
                            + "java.lang.IllegalStateException: cc-transform launch gate refused\n",
                    StandardCharsets.UTF_8);
            Path holderErr = Files.writeString(flight.resolve("lives").resolve("life-3.err.log"),
                    "[cc-premain] pilotship: refused holder_abi; set pilotship.enabled=false to start\n",
                    StandardCharsets.UTF_8);
            Path otherErr = Files.writeString(flight.resolve("lives").resolve("life-4.err.log"),
                    "[cc-premain] magicseam: refused jar_x; set magicseam.enabled=false\n", StandardCharsets.UTF_8);
            check(("game version not recognised; a game update needs the matching server package; on an"
                    + " unchanged game, set pilotship.enabled=false").equals(firstExceptionLine(refusedErr))
                    && "server files incomplete; reinstall the server package".equals(firstExceptionLine(holderErr))
                    && "[cc-premain] magicseam: refused jar_x; set magicseam.enabled=false"
                            .equals(firstExceptionLine(otherErr)),
                    "premain refusal head: the row reads in host words ahead of a later trace head, the holder"
                            + " family reads reinstall, another seam's row as printed (flips if the row is skipped,"
                            + " the later trace head wins, or a family takes the wrong remedy)");
            LaneLaunch pub = LaneLaunch.publicServe(1L, null);
            check(OpsRoots.get().pointer().equals(pub.flightPointer())
                    && LaneLaunch.debugWatch(1L, null).flightPointer() == null,
                    "the public lane carries the supervisor pointer, the watch lane none");
            // The GUI follows the LAUNCHER's own layout names.
            Path pkgRun = Paths.get(OPS_PKG_ROOT).resolve(ServeLauncher.RUN_DIR);
            check(Paths.get(PUBLIC_FLIGHT_POINTER).equals(pkgRun.resolve(ServeLauncher.POINTER_FILE))
                    && Paths.get(PUBLIC_OPS_FILE).equals(pkgRun.resolve(ServeLauncher.OPS_DIR)
                            .resolve(ServeLauncher.OPS_STATUS_FILE)),
                    "pkg-paths: pointer + ops file = ServeLauncher's ${PKG}/run layout under"
                            + " OPS_PKG_ROOT (flips if either reverts to the pwsh roots - a healthy"
                            + " launcher serve would read PENDING until TIMED_OUT)");
            check(launchFailureText(pub, LaunchPhase.FAILED, List.of("tail"), live)
                    .startsWith(live.detailText() + "\n" + PUBLIC_LANE + " launcher exited"),
                    "the failure dialog LEADS with the life line, log tail kept");
        }

        // end-state polish — EXITED reads as a clean end, never a crash
        check("MATCH ENDED".equals(stateHeadline(ServerState.EXITED)),
                "exited headline golden");
        check("RUN".equals(stateHeadline(ServerState.RUN)), "run headline unchanged");
        check(stateHeadline(ServerState.STALE).startsWith("STALE"), "alive-but-frozen says STALE");
        check(stateHeadline(ServerState.GONE).startsWith("SERVER GONE"),
                "dead writer says SERVER GONE");
        check("ended".equals(seatTableRow(snap.seats().get(0), true)[5]),
                "match-over row liveness reads ended");
        check("live".equals(seatTableRow(snap.seats().get(0), false)[5]),
                "run row liveness unchanged");
        String finalText = finalDetailText(snap);
        check(finalText.contains("after round 0") && finalText.contains("1 round(s) played")
                && finalText.contains("13.6 MB out") && finalText.contains("144.4 KB in")
                && finalText.contains("cleanly"),
                "final detail golden (0-based round + totals + clean wording)");

        // The set-before-start tick-rate row editor (the ONE sim.tickRate row)
        List<String> noRow = List.of("# header", "server.portFile=x", "watch.frames=21600");
        check(tickRateRowValue(noRow) == null, "tick-rate row absent -> null");
        check(withTickRateRow(noRow, 60).equals(noRow),
                "60 with no row = no edit (absent IS the default)");
        List<String> at30 = withTickRateRow(noRow, 30);
        check(at30.equals(List.of("# header", "server.portFile=x", "watch.frames=21600",
                TICK_RATE_COMMENT, TICK_RATE_KEY + "=30")),
                "non-60 appends marker + row at the end");
        check("30".equals(tickRateRowValue(at30)), "written row reads back");
        check(withTickRateRow(at30, 45).equals(List.of("# header", "server.portFile=x",
                "watch.frames=21600", TICK_RATE_COMMENT, TICK_RATE_KEY + "=45")),
                "re-set replaces in place, no duplicate marker/row");
        check(withTickRateRow(at30, 60).equals(noRow),
                "back to 60 removes marker + row (canonical bytes restored)");
        List<String> tombstone = List.of(
                "# (leg-2 session row sim.tickRate=30 REMOVED after the run)", "map.half=6000");
        check(tickRateRowValue(tombstone) == null, "commented mention is not a row");
        check(withTickRateRow(tombstone, 60).equals(tombstone),
                "commented mention never edited");
        check(withTickRateRow(List.of("a=1", TICK_RATE_KEY + "=90", "b=2"), 30).equals(
                List.of("a=1", TICK_RATE_COMMENT, TICK_RATE_KEY + "=30", "b=2")),
                "hand-written row replaced in place, marker added above");
        check(withTickRateRow(List.of("a=1", TICK_RATE_KEY + "=90", "b=2",
                TICK_RATE_KEY + "=45"), 60).equals(List.of("a=1", "b=2")),
                "60 removes every uncommented row (last-wins shadowing cleaned)");
        check("45".equals(tickRateRowValue(List.of(
                TICK_RATE_KEY + "=90", TICK_RATE_KEY + "=45"))),
                "duplicate rows read last-wins (Properties/Read-KvFile semantics)");
        // the Start-click validation contract rides the REAL strict parse
        check(SimTickRate.parse("") == 60, "blank tick-rate field parses to the 60 default");
        checkThrows(() -> SimTickRate.parse("sixty"), "non-integer tick rate rejected");
        checkThrows(() -> SimTickRate.parse("9"), "tick rate below 10 rejected");
        checkThrows(() -> SimTickRate.parse("601"), "tick rate above 600 rejected");

        // map-size knob — row editor + strict parse goldens
        List<String> noHalf = List.of("# header", "server.portFile=x");
        check(mapHalfRowValue(noHalf) == null, "map.half row absent -> null");
        check(withMapHalfRow(noHalf, 9000f).equals(List.of("# header", "server.portFile=x",
                MAP_HALF_COMMENT, MAP_HALF_KEY + "=9000")),
                "absent map.half appends marker + row");
        check(withMapHalfRow(List.of("a=1", "map.half=12000", "b=2"), 9000f).equals(
                List.of("a=1", "map.half=9000", "b=2")),
                "map.half replaced in place (hand comment above it untouched)");
        check(withMapHalfRow(List.of("map.half=1", "map.half=2"), 9000f).equals(
                List.of("map.half=9000")), "shadowed duplicate rows collapse to ONE");
        check("12000".equals(mapHalfRowValue(List.of(
                "# map.half=6000 tombstone", "map.half=12000"))),
                "commented mention is not a row; real row reads last-wins");
        check(parseMapHalf("9000") == 9000f && parseMapHalf(" 2000 ") == 2000f
                && parseMapHalf("60000") == 60000f, "map-half bounds accepted");
        checkThrows(() -> parseMapHalf("1999"), "map half below 2000 rejected");
        checkThrows(() -> parseMapHalf("60001"), "map half above 60000 rejected");
        checkThrows(() -> parseMapHalf("9000.5"), "fractional su rejected");
        checkThrows(() -> parseMapHalf("abc"), "non-numeric map half rejected");
        checkThrows(() -> parseMapHalf(""), "blank map half rejected");
        checkThrows(() -> parseMapHalf("Infinity"), "infinite map half rejected");

        // The match.fortressDp row editor (withTickRateRow surgery: 0 = AUTO
        // removes; replace-in-place keeps position; append lands marker + row) + strict parse
        check(fortressDpRowValue(noHalf) == null, "fortressDp row absent -> null");
        check(withFortressDpRow(noHalf, 0).equals(noHalf),
                "dp 0 on a rowless file is a no-op (AUTO is absence)");
        List<String> at770 = withFortressDpRow(noHalf, 770);
        check(at770.equals(List.of("# header", "server.portFile=x",
                FORTRESS_DP_COMMENT, FORTRESS_DP_KEY + "=770")),
                "dp 770 appends marker + row at the end");
        check("770".equals(fortressDpRowValue(at770)), "written fortressDp row reads back");
        check(withFortressDpRow(at770, 385).equals(List.of("# header", "server.portFile=x",
                FORTRESS_DP_COMMENT, FORTRESS_DP_KEY + "=385")),
                "dp change replaces the row in place");
        check(withFortressDpRow(at770, 0).equals(noHalf),
                "dp 0 removes row + marker (file back to canonical bytes)");
        check(withFortressDpRow(List.of("a=1", FORTRESS_DP_KEY + "=90", "b=2",
                FORTRESS_DP_KEY + "=45"), 0).equals(List.of("a=1", "b=2")),
                "remove drops Properties-shadowed duplicates too");
        check(parseFortressDp("") == 0 && parseFortressDp(" 0 ") == 0
                && parseFortressDp("385") == 385 && parseFortressDp("5000") == 5000,
                "fortressDp parse: blank/0 = AUTO, bounds accepted");
        checkThrows(() -> parseFortressDp("-1"), "negative fortress DP rejected");
        checkThrows(() -> parseFortressDp("5001"), "fortress DP above 5000 rejected");
        checkThrows(() -> parseFortressDp("385.5"), "fractional fortress DP rejected");
        checkThrows(() -> parseFortressDp("abc"), "non-numeric fortress DP rejected");
        check("0".equals(fortressDpFieldPrefill(List.of("other=1")))
                && "770".equals(fortressDpFieldPrefill(at770)),
                "fortressDp prefill: row when present, else 0 (AUTO)");

        // The match.fortressWaveScale row editor, the shells'
        // strict parse (the serve's WaveSchedule law) + the AUTO cross-field refusal.
        String flat15 = "1.5,1.5,1.5,1.5,1.5,1.5,1.5";
        check(fortressWaveScaleRowValue(noHalf) == null, "wave-scale row absent -> null");
        check(withFortressWaveScaleRow(noHalf, null) == noHalf,
                "null scale (a shell without the field) leaves the lines untouched");
        check(withFortressWaveScaleRow(noHalf, "").equals(noHalf),
                "blank scale on a rowless file is a no-op (x1 is absence)");
        List<String> scaled = withFortressWaveScaleRow(noHalf, flat15);
        check(scaled.equals(List.of("# header", "server.portFile=x",
                FORTRESS_WAVE_SCALE_COMMENT, FORTRESS_WAVE_SCALE_KEY + "=" + flat15)),
                "scale appends marker + row at the end");
        check(flat15.equals(fortressWaveScaleRowValue(scaled)), "written wave-scale row reads back");
        check(withFortressWaveScaleRow(scaled, "2,2,2,2,2,2,2").equals(List.of("# header",
                "server.portFile=x", FORTRESS_WAVE_SCALE_COMMENT,
                FORTRESS_WAVE_SCALE_KEY + "=2,2,2,2,2,2,2")),
                "scale change replaces the row in place");
        check(withFortressWaveScaleRow(scaled, null) == scaled,
                "null scale leaves an existing row alone (a web-shell Start never strips the tuning)");
        check(withFortressWaveScaleRow(scaled, "").equals(noHalf),
                "blank scale removes row + marker (file back to canonical bytes)");
        check("".equals(parseFortressWaveScaleRow("")) && "".equals(parseFortressWaveScaleRow("  "))
                && "1.5,1.5,1.5,1.75,2,2,2".equals(parseFortressWaveScaleRow(" 1.5, 1.5,1.5,1.75,2,2,2 ")),
                "wave-scale parse: blank = x1, whitespace stripped, typed text kept");
        checkThrows(() -> parseFortressWaveScaleRow("1.5,1.5,1.5"), "short wave-scale list rejected");
        checkThrows(() -> parseFortressWaveScaleRow("5,1,1,1,1,1,1"), "wave-scale factor above 4.0 rejected");
        checkThrows(() -> parseFortressWaveScaleRow("abc"), "non-numeric wave scale rejected");
        checkThrows(() -> requireFortressWaveScaleAllowed(0, flat15),
                "scale on AUTO (dp 0) refused before the write");
        requireFortressWaveScaleAllowed(0, "");
        requireFortressWaveScaleAllowed(0, null);
        requireFortressWaveScaleAllowed(750, flat15);
        check("".equals(fortressWaveScaleFieldPrefill(List.of("other=1")))
                && flat15.equals(fortressWaveScaleFieldPrefill(scaled)),
                "wave-scale prefill: row when present, else blank (x1)");

        // The match.waveRosterFullDp row editor, the
        // shells' strict whole-int parse (0..5000, 0/blank = "" = static) + the AUTO refusal.
        check(waveRosterFullDpRowValue(noHalf) == null, "roster-DP row absent -> null");
        check(withWaveRosterFullDpRow(noHalf, null) == noHalf,
                "null roster DP (a shell without the field) leaves the lines untouched");
        check(withWaveRosterFullDpRow(noHalf, "").equals(noHalf),
                "0/blank roster DP on a rowless file is a no-op (static is absence)");
        List<String> rostered = withWaveRosterFullDpRow(noHalf, "120");
        check(rostered.equals(List.of("# header", "server.portFile=x",
                WAVE_ROSTER_FULL_DP_COMMENT, WAVE_ROSTER_FULL_DP_KEY + "=120")),
                "roster DP appends marker + row at the end");
        check("120".equals(waveRosterFullDpRowValue(rostered)), "written roster-DP row reads back");
        check(withWaveRosterFullDpRow(rostered, "300").equals(List.of("# header",
                "server.portFile=x", WAVE_ROSTER_FULL_DP_COMMENT,
                WAVE_ROSTER_FULL_DP_KEY + "=300")), "roster-DP change replaces the row in place");
        check(withWaveRosterFullDpRow(rostered, null) == rostered,
                "null roster DP leaves an existing row alone");
        check(withWaveRosterFullDpRow(rostered, "").equals(noHalf),
                "0 roster DP removes row + marker (file back to canonical bytes)");
        check("".equals(parseWaveRosterFullDpRow("")) && "".equals(parseWaveRosterFullDpRow("  "))
                && "".equals(parseWaveRosterFullDpRow("0"))
                && "".equals(parseWaveRosterFullDpRow(null))
                && "1".equals(parseWaveRosterFullDpRow("1"))
                && "5000".equals(parseWaveRosterFullDpRow(" 5000 "))
                && "120".equals(parseWaveRosterFullDpRow("0120")),
                "roster-DP parse: blank/0 = static, bounds accepted, canonical digits");
        checkThrows(() -> parseWaveRosterFullDpRow("-1"), "negative roster DP rejected");
        checkThrows(() -> parseWaveRosterFullDpRow("5001"), "roster DP above 5000 rejected");
        checkThrows(() -> parseWaveRosterFullDpRow("120.5"), "fractional roster DP rejected");
        checkThrows(() -> parseWaveRosterFullDpRow("abc"), "non-numeric roster DP rejected");
        checkThrows(() -> requireWaveRosterFullDpAllowed(0, "120"),
                "roster DP on AUTO (dp 0) refused before the write");
        requireWaveRosterFullDpAllowed(0, "");
        requireWaveRosterFullDpAllowed(0, null);
        requireWaveRosterFullDpAllowed(750, "120");
        check("0".equals(waveRosterFullDpFieldPrefill(List.of("other=1")))
                && "120".equals(waveRosterFullDpFieldPrefill(rostered)),
                "roster-DP prefill: row when present, else 0 (static)");
        check("".equals(waveRosterFullDpRowLenient(noHalf))
                && "".equals(waveRosterFullDpRowLenient(List.of(WAVE_ROSTER_FULL_DP_KEY + "=0")))
                && "120".equals(waveRosterFullDpRowLenient(rostered))
                && "junk".equals(waveRosterFullDpRowLenient(
                        List.of(WAVE_ROSTER_FULL_DP_KEY + "= junk "))),
                "roster-DP lenient read: absent/0 = static, a set or garbage row counts as set");

        // The match.waveRosterMultiplier row editor, the
        // shells' strict plain-decimal parse (0.1..10, 1/blank = "" = no change), the lenient
        // read, the prefill and the cross-field refusal against the effective roster DP.
        check(waveRosterMultiplierRowValue(noHalf) == null,
                "roster-x row absent -> null (flips if the reader invents a default row)");
        check(withWaveRosterMultiplierRow(noHalf, null) == noHalf,
                "null roster x (a shell without the field) returns the SAME list (flips if a"
                        + " field-less shell rewrites the file)");
        List<String> multiplied = withWaveRosterMultiplierRow(rostered, "2.0");
        check(multiplied.equals(List.of("# header", "server.portFile=x", WAVE_ROSTER_FULL_DP_COMMENT,
                WAVE_ROSTER_FULL_DP_KEY + "=120", WAVE_ROSTER_MULTIPLIER_COMMENT,
                WAVE_ROSTER_MULTIPLIER_KEY + "=2.0")),
                "roster x appends marker + row at the end (flips if the marker or key drifts)");
        check("2.0".equals(waveRosterMultiplierRowValue(multiplied)),
                "written roster-x row reads back (flips if the writer and reader disagree on the key)");
        check(withWaveRosterMultiplierRow(multiplied, "0.5").equals(List.of("# header",
                "server.portFile=x", WAVE_ROSTER_FULL_DP_COMMENT, WAVE_ROSTER_FULL_DP_KEY + "=120",
                WAVE_ROSTER_MULTIPLIER_COMMENT, WAVE_ROSTER_MULTIPLIER_KEY + "=0.5")),
                "roster-x change replaces the row in place (flips if an upsert appends a duplicate)");
        check(withWaveRosterMultiplierRow(multiplied, "").equals(rostered),
                "unit roster x removes row + marker (flips if the marker is left orphaned)");
        check(withWaveRosterMultiplierRow(multiplied, null) == multiplied,
                "null roster x leaves an existing row alone (flips if no-field strips live tuning)");
        check("2.0".equals(parseWaveRosterMultiplierRow("2"))
                && "0.1".equals(parseWaveRosterMultiplierRow("0.1"))
                && "10.0".equals(parseWaveRosterMultiplierRow(" 10 "))
                && "0.5".equals(parseWaveRosterMultiplierRow("0.50"))
                && "".equals(parseWaveRosterMultiplierRow("1"))
                && "".equals(parseWaveRosterMultiplierRow("1.0"))
                && "".equals(parseWaveRosterMultiplierRow("  "))
                && "".equals(parseWaveRosterMultiplierRow(null)),
                "roster-x parse: bounds accepted in canonical form, unit/blank = no change (flips if a"
                        + " bound narrows or unit writes a row)");
        for (String badX : List.of("0", "0.05", "10.5", "-2", "1e1", ".5", "x", "2.", "+2", "NaN",
                "Infinity", "1".repeat(400))) {
            checkThrows(() -> parseWaveRosterMultiplierRow(badX),
                    "roster x '" + badX.substring(0, Math.min(12, badX.length())) + "' rejected (flips"
                            + " if the grammar or the"
                            + " [0.1, 10] window is loosened or clamps)");
        }
        checkThrows(() -> requireWaveRosterMultiplierAllowed("", "2.0"),
                "roster x with static waves refused before the write (flips if the cross-field law"
                        + " is dropped)");
        checkThrows(() -> requireWaveRosterMultiplierAllowed(null, "2.0"),
                "roster x with an unresolved roster DP refused (flips if null reads as on)");
        requireWaveRosterMultiplierAllowed("90", "2.0");
        requireWaveRosterMultiplierAllowed("", "");
        requireWaveRosterMultiplierAllowed("", null);
        check("".equals(waveRosterMultiplierRowLenient(noHalf))
                && "".equals(waveRosterMultiplierRowLenient(List.of(WAVE_ROSTER_MULTIPLIER_KEY + "=1")))
                && "".equals(waveRosterMultiplierRowLenient(List.of(WAVE_ROSTER_MULTIPLIER_KEY + "=1.0")))
                && "".equals(waveRosterMultiplierRowLenient(List.of(WAVE_ROSTER_MULTIPLIER_KEY + "= 1.00 ")))
                && "2.0".equals(waveRosterMultiplierRowLenient(multiplied))
                && "10".equals(waveRosterMultiplierRowLenient(List.of(WAVE_ROSTER_MULTIPLIER_KEY + "=10")))
                && "junk".equals(waveRosterMultiplierRowLenient(
                        List.of(WAVE_ROSTER_MULTIPLIER_KEY + "= junk "))),
                "roster-x lenient read: absent/unit = no change, a set or garbage row counts as set"
                        + " (flips if 10 reads as unit or garbage is swallowed)");
        check("1".equals(waveRosterMultiplierFieldPrefill(List.of("other=1")))
                && "2.0".equals(waveRosterMultiplierFieldPrefill(multiplied)),
                "roster-x prefill: row when present, else 1 (flips if the default drifts)");

        // joiner-default verb minter + row editor.
        // The verb line single-sources the charset/length law
        // through the REAL OperatorCommandFile grammar (blank -> `none` = clear, exactly one
        // token after the verb); the row editor mirrors the withFortressDpRow AUTO-removes-row
        // identity (blank -> row + marker removed, tracked file back to canonical bytes).
        check("set-joiner-default conquest_Broadside".equals(
                joinerDefaultLine("conquest_Broadside")), "joiner-default line golden");
        check("set-joiner-default none".equals(joinerDefaultLine("  ")),
                "blank joiner-default -> none (clear)");
        check("set-joiner-default none".equals(joinerDefaultLine("none")),
                "literal none -> clear verb");
        check("set-joiner-default a.b_c:d-1".equals(joinerDefaultLine("a.b_c:d-1")),
                "joiner-default full charset golden");
        checkThrows(() -> joinerDefaultLine("bad id!"), "joiner-default bad charset rejected");
        checkThrows(() -> joinerDefaultLine("has space"), "joiner-default inner space rejected");
        checkThrows(() -> joinerDefaultLine("x".repeat(65)),
                "joiner-default over 64 chars rejected");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.SET_JOINER_DEFAULT, 0),
                "verbLine refuses SET_JOINER_DEFAULT (joinerDefaultLine is the minter)");
        OperatorCommandFile.ParseResult jdTrip = OperatorCommandFile.parse(List.of(
                joinerDefaultLine("conquest_Broadside"), joinerDefaultLine("")));
        check(jdTrip.rejected().isEmpty() && jdTrip.commands().size() == 2
                && jdTrip.commands().get(0).verb()
                        == OperatorCommandFile.Verb.SET_JOINER_DEFAULT
                && "conquest_Broadside".equals(jdTrip.commands().get(0).text())
                && jdTrip.commands().get(1).text().isEmpty(),
                "joiner-default lines round-trip through OperatorCommandFile.parse (none folds"
                        + " to an empty arg)");

        // W15 v0: the customs cluster — minter goldens, verbLine refusals,
        // round-trip through the REAL grammar, the seat-table custom cell, the summary label.
        check("custom-submissions on".equals(customSubmissionsLine(true))
                && "custom-submissions off".equals(customSubmissionsLine(false)),
                "custom-submissions goldens");
        check("custom-reject 3".equals(customRejectLine(3)), "custom-reject golden");
        checkThrows(() -> customRejectLine(0), "custom-reject 0 rejected");
        check("custom-ban cc-v1-assault".equals(customBanLine("cc-v1-assault"))
                && "custom-export cc-v1-assault".equals(customExportLine(" cc-v1-assault ")),
                "custom-ban/export goldens (id trimmed, case preserved)");
        checkThrows(() -> customBanLine(""), "custom-ban blank id rejected");
        checkThrows(() -> customBanLine(null), "custom-ban null id rejected");
        checkThrows(() -> customExportLine("bad id!"), "custom-export bad charset rejected");
        // The inverse verbs share their twins' shapes.
        check("custom-allow 3".equals(customAllowLine(3))
                && "custom-unban cc-v1-assault".equals(customUnbanLine(" cc-v1-assault ")),
                "custom-allow/unban goldens");
        checkThrows(() -> customAllowLine(0), "custom-allow 0 rejected");
        checkThrows(() -> customUnbanLine(""), "custom-unban blank id rejected");
        OperatorCommandFile.ParseResult inverseTrip = OperatorCommandFile.parse(List.of(
                customAllowLine(3), customUnbanLine("cc-v1-assault")));
        check(inverseTrip.rejected().isEmpty() && inverseTrip.commands().size() == 2
                && inverseTrip.commands().get(0).verb() == OperatorCommandFile.Verb.CUSTOM_ALLOW
                && inverseTrip.commands().get(0).seatId() == 3
                && inverseTrip.commands().get(1).verb() == OperatorCommandFile.Verb.CUSTOM_UNBAN
                && "cc-v1-assault".equals(inverseTrip.commands().get(1).text()),
                "custom-allow/unban lines round-trip through OperatorCommandFile.parse");
        for (OperatorCommandFile.Verb v : List.of(OperatorCommandFile.Verb.CUSTOM_SUBMISSIONS,
                OperatorCommandFile.Verb.CUSTOM_REJECT, OperatorCommandFile.Verb.CUSTOM_BAN,
                OperatorCommandFile.Verb.CUSTOM_EXPORT, OperatorCommandFile.Verb.CUSTOM_ALLOW,
                OperatorCommandFile.Verb.CUSTOM_UNBAN)) {
            checkThrows(() -> verbLine(v, 1), "verbLine refuses " + v + " (its minter holds it)");
        }
        OperatorCommandFile.ParseResult customTrip = OperatorCommandFile.parse(List.of(
                customSubmissionsLine(false), customRejectLine(2), customBanLine("cc-v1-assault"),
                customExportLine("cc-v2-x")));
        check(customTrip.rejected().isEmpty() && customTrip.commands().size() == 4
                && customTrip.commands().get(0).verb() == OperatorCommandFile.Verb.CUSTOM_SUBMISSIONS
                && !customTrip.commands().get(0).autoOn()
                && customTrip.commands().get(1).verb() == OperatorCommandFile.Verb.CUSTOM_REJECT
                && customTrip.commands().get(1).seatId() == 2
                && customTrip.commands().get(2).verb() == OperatorCommandFile.Verb.CUSTOM_BAN
                && "cc-v1-assault".equals(customTrip.commands().get(2).text())
                && customTrip.commands().get(3).verb() == OperatorCommandFile.Verb.CUSTOM_EXPORT
                && "cc-v2-x".equals(customTrip.commands().get(3).text()),
                "custom verb lines round-trip through OperatorCommandFile.parse");
        checkP27BanRows();
        checkKickAndStrangerTextRows();
        checkKeptRows();
        checkShipsRows();
        OpsSnapshot.SeatRow stock =new OpsSnapshot.SeatRow(1, "c1", "127.0.0.1:1", "wolf_Assault",
                true, 0L, 0L, 0L, 0L, 0, "Dee");
        OpsSnapshot.SeatRow flying = new OpsSnapshot.SeatRow(2, "c2", "127.0.0.1:2",
                "cc-v1-assault", true, 0L, 0L, 0L, 0L, 0, "Bob", "cc-v1-assault", "wolf", 0,
                "Assault");
        // "customs" (the held accepted-id list) closes the row after "custom".
        OpsSnapshot.SeatRow holding = new OpsSnapshot.SeatRow(3, "c3", "127.0.0.1:3",
                "wolf_Assault", true, 0L, 0L, 0L, 0L, 0, "Ann", "", "", -1, "", "cc-v2-a,cc-v3-b");
        // The model-only cidkey cell is LAST now (old
        // writer = ""), so custom/customs sit one left.
        check(CIDKEY_COLUMN.equals(SEAT_COLUMNS[SEAT_COLUMNS.length - 1])
                && "".equals(seatTableRow(stock)[SEAT_COLUMNS.length - 1])
                && "customs".equals(SEAT_COLUMNS[SEAT_COLUMNS.length - 2])
                && "custom".equals(SEAT_COLUMNS[SEAT_COLUMNS.length - 3])
                && "".equals(seatTableRow(stock)[SEAT_COLUMNS.length - 3])
                && "".equals(seatTableRow(stock)[SEAT_COLUMNS.length - 2])
                && "cc-v1-assault".equals(seatTableRow(flying)[SEAT_COLUMNS.length - 3])
                && "".equals(seatTableRow(holding)[SEAT_COLUMNS.length - 3])
                && "cc-v2-a,cc-v3-b".equals(seatTableRow(holding)[SEAT_COLUMNS.length - 2]),
                "seat-table custom/customs cells: blank for a stock hull, the minted id when"
                        + " flying, the held list when holding");
        check("customs: -".equals(customsSummaryText(null)) && "-".equals(customsDetailText(null)),
                "customs summary/detail placeholders without a snapshot");
        List<String> customLines = new ArrayList<>(FIXTURE); // last put wins per key
        customLines.addAll(List.of("custom.count=3", "custom.rejected=1", "custom.banned=2",
                "custom.submissions=on"));
        OpsSnapshot customSnap = OpsSnapshot.parse(customLines);
        check("customs: 3 accepted, 1 rejected, 2 banned - submissions on"
                .equals(customsSummaryText(customSnap)) && "-".equals(customsDetailText(customSnap))
                && customSnap.customCapGlobal() == 0 && "-".equals(customSnap.customTier())
                && customSnap.customExported() == 0L,
                "customs summary golden (pre-(e) writer: byte-identical, detail '-')");
        // A full CustomStats round-trips composeLines → parse → the two texts.
        List<String> fullLines = OpsStatusFile.composeLines(1L, 1L, "run", "127.0.0.1", 51337,
                7, 1, 2, 2L, 4, 60.0, 12.5, 3.2, 1000L, 500L, 100L, 50L, 0, 0.0, 0L, "cmd",
                List.of(new OpsStatusFile.SeatRow(3, "c3", "127.0.0.1:3", "wolf_Assault", true,
                        0L, 0L, 0, "Ann", "", "", -1, "", "cc-v2-a,cc-v3-b")),
                new long[][] {{0L, 0L}}, PhaseWindowStats.Snapshot.EMPTY,
                new OpsStatusFile.CustomStats(3, 6, 2, true, 1, 2, 3, 0, 1, 64, "strict", 4,
                        "engine", "allow", 5L, 1L));
        OpsSnapshot fullSnap = OpsSnapshot.parse(fullLines);
        check(fullLines.contains("custom.rejected.policy=1") && fullLines.contains("custom.rejected.caps=2")
                && fullLines.contains("custom.rejected.engine=3") && fullLines.contains("custom.rejected.operator=0")
                && fullLines.contains("custom.pending=1") && fullLines.contains("custom.cap_global=64")
                && fullLines.contains("custom.tier=strict") && fullLines.contains("custom.per_seat=4")
                && fullLines.contains("custom.smod_cap=engine") && fullLines.contains("custom.dmods=allow")
                && fullLines.contains("custom.exported=5") && fullLines.contains("custom.export_failed=1")
                && fullLines.contains("custom.frame_encode_failed=0") && fullLines.contains("custom.vsub_decode_refused=0")
                && fullLines.contains("seat.1.customs=cc-v2-a,cc-v3-b"),
                "composeLines writes the full custom.* keys (+ the two R3b refusal meters)");
        check(fullSnap.customRejectedPolicy() == 1 && fullSnap.customRejectedCaps() == 2
                && fullSnap.customRejectedEngine() == 3 && fullSnap.customRejectedOperator() == 0
                && fullSnap.customPending() == 1 && fullSnap.customCapGlobal() == 64
                && "strict".equals(fullSnap.customTier()) && fullSnap.customPerSeat() == 4
                && "engine".equals(fullSnap.customSModCap()) && "allow".equals(fullSnap.customDMods())
                && fullSnap.customExported() == 5L && fullSnap.customExportFailed() == 1L
                && "cc-v2-a,cc-v3-b".equals(fullSnap.seats().get(0).customs()),
                "OpsSnapshot parses the full custom.* keys");
        // seat.N.cidkey = the exact
        // cid key, parsed into SeatRow.cidKey and the LAST seat-table cell.
        String c3Key = cc.starsector.server.runtime.BanSet.cidKey("c3");
        check(fullLines.indexOf("seat.1.cidkey=" + c3Key) == fullLines.indexOf("seat.1.client=c3") + 1
                && c3Key.equals(fullSnap.seats().get(0).cidKey())
                && c3Key.equals(seatTableRow(fullSnap.seats().get(0))[SEAT_COLUMNS.length - 1])
                && "".equals(OpsSnapshot.parse(FIXTURE).seats().stream().map(OpsSnapshot.SeatRow::cidKey)
                        .filter(k -> !k.isEmpty()).findFirst().orElse("")),
                "seat.1.cidkey follows seat.1.client, parses into SeatRow.cidKey (an old writer = \"\")");
        // OPERATOR-SERVE-SECRETS-1 — a 32-character cid prints
        // on seat.N.client as its 8-character prefix, and NO line of the file carries the raw id,
        // while seat.N.cidkey stays the EXACT key of the FULL id (the Ban confirm's expected-occupant tail).
        String longCid = "0123456789abcdef0123456789abcdef";
        String longKey = cc.starsector.server.runtime.BanSet.cidKey(longCid);
        List<String> longLines = OpsStatusFile.composeLines(1L, 1L, "run", "127.0.0.1", 51337,
                7, 1, 2, 2L, 4, 60.0, 12.5, 3.2, 1000L, 500L, 100L, 50L, 0, 0.0, 0L, "cmd",
                List.of(new OpsStatusFile.SeatRow(1, longCid, "127.0.0.1:1", "wolf_Assault", true, 0L, 0L, 0)),
                new long[][] {{0L, 0L}}, PhaseWindowStats.Snapshot.EMPTY);
        OpsSnapshot longSnap = OpsSnapshot.parse(longLines);
        check(longLines.contains("seat.1.client=01234567...")
                && longLines.stream().noneMatch(l -> l.contains(longCid))
                && "01234567...".equals(longSnap.seats().get(0).clientId()),
                "seat.1.client of a 32-char cid = its 8-char prefix + '...' and no line holds the raw id"
                        + " (flips if OpsStatusFile prints row.clientId() whole again)");
        check(longLines.indexOf("seat.1.cidkey=" + longKey) == longLines.indexOf("seat.1.client=01234567...") + 1
                && longKey.equals(longSnap.seats().get(0).cidKey())
                && longKey.equals(seatTableRow(longSnap.seats().get(0))[SEAT_COLUMNS.length - 1]),
                "seat.1.cidkey stays the FULL id's exact key beside the prefixed client= (flips if cidkey"
                        + " is hexed from the printed prefix or dropped — the Ban buttons would target a wrong key)");
        check("customs: 3 accepted, 6 rejected, 2 banned, 1 pending, 60/64 free - submissions on"
                .equals(customsSummaryText(fullSnap)), "customs summary golden (headroom pair)");
        // composeLines writes the kept rows, so the detail carries the kept tail
        check(("rejected: policy 1, caps 2, engine 3, operator 0 - policy strict, 4/seat,"
                + " s-mods engine, d-mods allow - exported 5, export failed 1"
                + " | kept for everyone: 0/32 (none) | exported, not kept yet: 0")
                .equals(customsDetailText(fullSnap)), "customs detail golden (the writer's empty kept rows)");
        // row upsert / read-back / prefill round-trips + parse gate
        List<String> jdBase = List.of("# header", "server.portFile=x", "other=2");
        check(joinerDefaultRowValue(jdBase) == null
                && "".equals(joinerDefaultFieldPrefill(jdBase)),
                "joiner-default absent -> null row, blank prefill");
        List<String> jdSet = withJoinerDefaultRow(jdBase, "conquest_Broadside");
        check(jdSet.equals(List.of("# header", "server.portFile=x", "other=2",
                JOINER_DEFAULT_COMMENT, JOINER_DEFAULT_KEY + "=conquest_Broadside")),
                "joiner-default write appends marker + row");
        check("conquest_Broadside".equals(joinerDefaultRowValue(jdSet).trim())
                && "conquest_Broadside".equals(joinerDefaultFieldPrefill(jdSet)),
                "joiner-default row reads back + prefills");
        check(withJoinerDefaultRow(jdSet, "").equals(jdBase)
                && withJoinerDefaultRow(jdSet, "  ").equals(jdBase),
                "blank joiner-default write removes row + marker (back to canonical bytes)");
        check(withJoinerDefaultRow(List.of("a=1", JOINER_DEFAULT_KEY + "=old", "b=2",
                        JOINER_DEFAULT_KEY + "=older"), "new").equals(List.of("a=1",
                        JOINER_DEFAULT_COMMENT, JOINER_DEFAULT_KEY + "=new", "b=2")),
                "joiner-default replace-in-place drops Properties-shadowed duplicates");
        check(parseJoinerDefault("  ").isEmpty()
                && "conquest".equals(parseJoinerDefault("conquest")),
                "parseJoinerDefault: blank -> empty, valid token passthrough");
        check(parseJoinerDefault("none").isEmpty() && parseJoinerDefault("NONE").isEmpty(),
                "parseJoinerDefault: literal none folds to clear at the props layer (L12 F1)");
        checkThrows(() -> parseJoinerDefault("bad id!"), "parseJoinerDefault bad token rejected");

        // Serve mod-set row editor + parse gate. The row
        // editor mirrors the withWaveCategoryRow null/blank identity (null = leave alone, blank =
        // row + marker removed = a vanilla-only serve). START-ONLY: no LIVE verb minter (a running
        // serve can't change its mod set). The parse mirrors the ModSet id law (cc_client refused,
        // duplicates refused, split on ,/; then comma-join canonical).
        List<String> modsBase = List.of("# header", "server.portFile=x", "other=2");
        check(modsEnabledRowValue(modsBase) == null
                && "".equals(modsEnabledFieldPrefill(modsBase)),
                "mods.enabled absent -> null row, blank prefill");
        List<String> modsSet = List.of("# header", "server.portFile=x", "other=2",
                MODS_ENABLED_COMMENT, MODS_ENABLED_KEY + "=lw_lazylib,shaderLib");
        check(withModsEnabledRow(modsBase, "lw_lazylib,shaderLib").equals(modsSet),
                "mods.enabled write appends marker + row");
        check(withModsEnabledRow(modsSet, null) == modsSet,
                "null mods leaves an existing row alone (a web Start never strips the set)");
        check(withModsEnabledRow(modsSet, "").equals(modsBase),
                "blank mods write removes row + marker (back to canonical bytes)");
        check("lw_lazylib, shaderLib".equals(modsEnabledFieldPrefill(List.of(
                        MODS_ENABLED_KEY + "=lw_lazylib, shaderLib"))),
                "mods.enabled prefill keeps the row's inner spacing verbatim");
        check("".equals(parseModsEnabled("")) && "".equals(parseModsEnabled("  ")),
                "parseModsEnabled: blank -> empty (row removed = vanilla only)");
        check("a,b".equals(parseModsEnabled("a,,b")) && "a,b".equals(parseModsEnabled(" a ; b "))
                && "lw_lazylib,shaderLib".equals(parseModsEnabled("lw_lazylib, shaderLib")),
                "parseModsEnabled: split ,/; trim + drop empties -> comma-joined canonical");
        checkThrows(() -> parseModsEnabled("cc_client"),
                "parseModsEnabled: cc_client is always enabled - refused");
        checkThrows(() -> parseModsEnabled("bad id!"),
                "parseModsEnabled: unlawful id refused");
        checkThrows(() -> parseModsEnabled("a,a"),
                "parseModsEnabled: duplicate id refused");

        // The client-OPTIONAL row (the host mods panel's
        // "optional" picks) - the mods.enabled row law verbatim + the never-both law.
        check(modsOptionalRowValue(modsBase) == null
                && "".equals(modsOptionalFieldPrefill(modsBase)),
                "mods.optional absent -> null row, blank prefill");
        List<String> optSet = List.of("# header", "server.portFile=x", "other=2",
                MODS_OPTIONAL_COMMENT, MODS_OPTIONAL_KEY + "=rubric,gfx");
        check(withModsOptionalRow(modsBase, "rubric,gfx").equals(optSet),
                "mods.optional write appends marker + row");
        check(withModsOptionalRow(optSet, null) == optSet,
                "null optional leaves an existing row alone (a web Start never strips it)");
        check(withModsOptionalRow(optSet, "").equals(modsBase),
                "blank optional write removes row + marker (back to canonical bytes)");
        check(withModsOptionalRow(List.of(MODS_OPTIONAL_KEY + "=a", "x=1", MODS_OPTIONAL_KEY
                        + "=b"), "c").equals(List.of(MODS_OPTIONAL_COMMENT,
                        MODS_OPTIONAL_KEY + "=c", "x=1")),
                "mods.optional replace-in-place drops shadowed duplicates");
        check(withModsOptionalRow(withModsEnabledRow(modsBase, "a"), "b").equals(List.of(
                        "# header", "server.portFile=x", "other=2", MODS_ENABLED_COMMENT,
                        MODS_ENABLED_KEY + "=a", MODS_OPTIONAL_COMMENT, MODS_OPTIONAL_KEY + "=b")),
                "both mod rows compose in one line list (the one-write discipline)");
        check("rubric, gfx".equals(modsOptionalFieldPrefill(List.of(
                        MODS_OPTIONAL_KEY + "=rubric, gfx ")))
                && "b".equals(modsOptionalRowValue(List.of(MODS_OPTIONAL_KEY + "=a",
                        "#" + MODS_OPTIONAL_KEY + "=z", MODS_OPTIONAL_KEY + "=b"))),
                "mods.optional prefill trims; last uncommented row wins");
        check("".equals(parseModsOptional(" ")) && "a,b".equals(parseModsOptional(" a ; b ")),
                "parseModsOptional: blank -> empty, split/trim -> comma-joined canonical");
        checkThrows(() -> parseModsOptional("cc_client"),
                "parseModsOptional: cc_client refused (always on)");
        checkThrows(() -> parseModsOptional("bad id!"),
                "parseModsOptional: unlawful id refused");
        checkThrows(() -> parseModsOptional("a;a"),
                "parseModsOptional: duplicate id refused");
        check(modsBothRefusalOrNull("a,b", "c") == null
                && modsBothRefusalOrNull("", "") == null
                && modsBothRefusalOrNull(null, "a") == null
                && modsBothRefusalOrNull("a, b", "c;b") != null
                && modsBothRefusalOrNull("a, b", "c;b").contains("'b'")
                && modsBothRefusalOrNull("a, b", "c;b").contains("never both"),
                "modsBothRefusalOrNull: an id in both lists is refused by name, else null");

        // InstalledModScan over a temp root - two mod
        // folders (string + object version), one junk folder (no mod_info.json), one malformed
        // mod_info.json (no id: skipped with a System.err line, never a throw), a loose file, an
        // absent root (silently skipped); sorted by id; the lib's config-row + default-roots law.
        Path scanRepo = Files.createTempDirectory("cc-mods-scan");
        Path scanRoot = Files.createDirectories(scanRepo.resolve("mods-a"));
        Files.createDirectories(scanRoot.resolve("Zed"));
        Files.writeString(scanRoot.resolve("Zed/mod_info.json"), "{\n  # lenient comment\n"
                + "  \"id\": \"zed_mod\", \"name\": \"Zed Mod\", \"version\": \"2.0.1\",\n"
                + "  \"dependencies\": [{\"id\": \"lw_lazylib\", \"name\": \"LazyLib\"}],\n}",
                StandardCharsets.UTF_8);
        Files.createDirectories(scanRoot.resolve("Alpha"));
        Files.writeString(scanRoot.resolve("Alpha/mod_info.json"), "{\"id\":\"alpha_mod\","
                + "\"name\":\"Alpha\",\"version\":{\"major\":1,\"minor\":\"4\",\"patch\":0},}",
                StandardCharsets.UTF_8);
        Files.createDirectories(scanRoot.resolve("junk"));
        Files.writeString(scanRoot.resolve("junk/readme.txt"), "not a mod", StandardCharsets.UTF_8);
        Files.createDirectories(scanRoot.resolve("broken"));
        Files.writeString(scanRoot.resolve("broken/mod_info.json"), "{ \"name\": \"no id here\", ",
                StandardCharsets.UTF_8);
        Files.writeString(scanRoot.resolve("loose.json"), "{\"id\":\"loose\"}", StandardCharsets.UTF_8);
        // An id the regex accepts but ModSet.lawfulId refuses —
        // a newline inside it would become a second cc-server.properties row when the Mods tab
        // writes the pick (row injection from a foreign mod_info.json) — is skipped at SCAN;
        // so is a mod_info.json over the read cap (never read into memory).
        Files.createDirectories(scanRoot.resolve("Injector"));
        Files.writeString(scanRoot.resolve("Injector/mod_info.json"),
                "{\"id\":\"evil\nmods.enabled=evil\", \"name\": \"Row injector\"}",
                StandardCharsets.UTF_8);
        Files.createDirectories(scanRoot.resolve("Huge"));
        Files.writeString(scanRoot.resolve("Huge/mod_info.json"),
                "{\"id\":\"huge_mod\", \"pad\":\"" + "x".repeat(InstalledModScan.MOD_INFO_READ_CAP)
                        + "\"}", StandardCharsets.UTF_8);
        List<String> scanProps = List.of("#mods.roots=nope", " mods.roots = missing-root ; mods-a ;");
        List<InstalledModScan.InstalledMod> scanned = InstalledModScan.scan(scanRepo, scanProps);
        check(scanned.size() == 2
                && "alpha_mod".equals(scanned.get(0).id()) && "Alpha".equals(scanned.get(0).name())
                && "1.4.0".equals(scanned.get(0).version())
                && scanned.get(0).folder().equals(scanRoot.resolve("Alpha"))
                && "zed_mod".equals(scanned.get(1).id()) && "Zed Mod".equals(scanned.get(1).name())
                && "2.0.1".equals(scanned.get(1).version()),
                "InstalledModScan: two mods id-sorted (string + object version), junk/malformed/"
                        + "loose/absent-root/newline-id/over-cap skipped without a throw ("
                        + scanned + ")");
        check(scanned.stream().noneMatch(m -> m.id().contains("\n") || "huge_mod".equals(m.id()))
                        && InstalledModScan.readModInfo(scanRoot.resolve("Injector"),
                                scanRoot.resolve("Injector/mod_info.json"), scanRoot) == null
                        && InstalledModScan.readModInfo(scanRoot.resolve("Huge"),
                                scanRoot.resolve("Huge/mod_info.json"), scanRoot) == null,
                "InstalledModScan: a newline id fails ModSet.lawfulId at scan and"
                        + " an over-cap mod_info.json is skipped, both null from readModInfo");
        check(List.of("missing-root", "mods-a").equals(InstalledModScan.roots(scanProps))
                && InstalledModScan.DEFAULT_ROOTS.equals(InstalledModScan.roots(List.of("x=1")))
                && InstalledModScan.roots(List.of("mods.roots= ; ")).isEmpty()
                && InstalledModScan.configRowOrNull(List.of("x=1"), "mods.roots") == null
                && "".equals(InstalledModScan.configRowOrNull(List.of("mods.roots="), "mods.roots")),
                "InstalledModScan.roots: the lib's row law (trimmed, ';', last wins); absent = the"
                        + " default roots, a PRESENT blank row = no roots");
        check(InstalledModScan.scanRoots(scanRepo, List.of(scanRoot.toString())).size() == 2
                && scanRoot.equals(scanned.get(0).root())
                && InstalledModScan.scanRoots(scanRepo, List.of()).isEmpty(),
                "InstalledModScan: an absolute root resolves as-is (root recorded); no roots = no mods");
        // A root that is itself a mod folder is that one mod (the Mods tab's single folders)
        List<InstalledModScan.InstalledMod> lone = InstalledModScan.scanRoots(scanRepo,
                List.of("mods-a/Alpha"));
        check(lone.size() == 1 && "alpha_mod".equals(lone.get(0).id())
                && lone.get(0).folder().equals(lone.get(0).root())
                && lone.get(0).folder().equals(scanRoot.resolve("Alpha")),
                "InstalledModScan: a single-mod root lists exactly that mod, root == folder");
        check(List.of("a=1", InstalledModScan.MODS_ROOTS_COMMENT, "mods.roots=x;y").equals(
                        InstalledModScan.withModsRootsRow(List.of("a=1"), "x;y"))
                && List.of(InstalledModScan.MODS_ROOTS_COMMENT, "mods.roots=", "b=2").equals(
                        InstalledModScan.withModsRootsRow(List.of("mods.roots=old", "b=2",
                                " mods.roots = older"), ""))
                && List.of("a=1").equals(InstalledModScan.withModsRootsRow(List.of("a=1"), null)),
                "withModsRootsRow: marker + row appended or replaced in place (duplicates"
                        + " dropped); blank WRITES a blank row; null = untouched");
        // the default-roots mirror of the serve-mods script lib's Get-CcDefaultModRoots (the lib is
        // the ONE home; this pins the Java copy to it when run from the repo root)
        Path modsLib = Paths.get("local/serve-mods-lib.ps1");
        if (Files.isRegularFile(modsLib)) {
            String want = "@('" + String.join("', '", InstalledModScan.DEFAULT_ROOTS.stream()
                    .map(r -> r.replace('/', '\\')).toList()) + "')";
            check(Files.readString(modsLib, StandardCharsets.UTF_8).contains(want),
                    "InstalledModScan.DEFAULT_ROOTS mirrors Get-CcDefaultModRoots " + want);
        } else {
            System.out.println("InstalledModScan default-roots mirror pin skipped (not run from"
                    + " the repo root)");
        }

        // wave-category row editor + LIVE verb minter (the wave-scale /
        // joiner-default idioms: default identity removes row + marker; the LIVE line keeps
        // remnant a REAL retarget target, never folded like the row default).
        List<String> wcBase = List.of("# header", "server.portFile=x");
        List<String> wcSet = List.of("# header", "server.portFile=x",
                WAVE_CATEGORY_COMMENT, WAVE_CATEGORY_KEY + "=derelict");
        check(withWaveCategoryRow(wcBase, "derelict").equals(wcSet),
                "wave-category write appends marker + row");
        check(withWaveCategoryRow(wcSet, "omega").equals(List.of("# header",
                "server.portFile=x", WAVE_CATEGORY_COMMENT, WAVE_CATEGORY_KEY + "=omega")),
                "wave-category change replaces the row in place");
        check(withWaveCategoryRow(wcSet, null) == wcSet,
                "null category leaves an existing row alone (a web-shell Start never strips it)");
        check(withWaveCategoryRow(wcSet, "").equals(wcBase),
                "blank (= remnant default) removes row + marker (back to canonical bytes)");
        check("".equals(parseWaveCategoryRow(null)) && "".equals(parseWaveCategoryRow("remnant"))
                && "derelict".equals(parseWaveCategoryRow("derelict")),
                "wave-category parse: blank/remnant = default identity, category kept");
        checkThrows(() -> parseWaveCategoryRow("nex"),
                "unknown wave category rejected before the write");
        check("wave-category derelict".equals(waveCategoryLine("derelict"))
                && "wave-category remnant".equals(waveCategoryLine("remnant")),
                "wave-category LIVE line goldens (remnant stays a real retarget target)");
        OperatorCommandFile.ParseResult wcTrip = OperatorCommandFile.parse(List.of(
                waveCategoryLine("threat")));
        check(wcTrip.rejected().isEmpty() && wcTrip.commands().size() == 1
                && wcTrip.commands().get(0).verb()
                        == OperatorCommandFile.Verb.SET_WAVE_CATEGORY
                && "threat".equals(wcTrip.commands().get(0).text()),
                "wave-category LIVE line round-trips the REAL operator grammar");
        check("remnant".equals(waveCategoryFieldPrefill(wcBase))
                && "derelict".equals(waveCategoryFieldPrefill(wcSet)),
                "wave-category prefill: row when present, else remnant");

        // The endless row editor + LIVE verb minter (the
        // wave-category idiom, TWO vocabularies: the ROW is the knob's strict boolean, the combo
        // and the verb speak on/off — the mapping lives in OpsMatchRows alone).
        List<String> enBase = List.of("# header", "server.portFile=x");
        List<String> enSet = List.of("# header", "server.portFile=x",
                ENDLESS_COMMENT, ENDLESS_KEY + "=false");
        check(withEndlessRow(enBase, parseEndlessRow("off")).equals(enSet),
                "endless write appends marker + row (off -> the knob's false)");
        check(withEndlessRow(enSet, parseEndlessRow("on")).equals(List.of("# header",
                "server.portFile=x", ENDLESS_COMMENT, ENDLESS_KEY + "=true")),
                "endless change replaces the row in place (on -> the knob's true)");
        check(withEndlessRow(enSet, null) == enSet,
                "null endless leaves an existing row alone (a web-shell Start never strips it)");
        check(withEndlessRow(enSet, "").equals(enBase),
                "blank endless removes row + marker (back to canonical bytes, absent = ON)");
        check("true".equals(parseEndlessRow(null)) && "true".equals(parseEndlessRow("  "))
                && "true".equals(parseEndlessRow("on")) && "false".equals(parseEndlessRow("OFF")),
                "endless parse: blank = the ON default, on/OFF fold to the boolean row");
        checkThrows(() -> parseEndlessRow("maybe"),
                "unknown endless word rejected before the write (reject-never-clamp)");
        check("endless on".equals(endlessLine("on")) && "endless off".equals(endlessLine("off")),
                "endless LIVE line goldens");
        OperatorCommandFile.ParseResult enTrip = OperatorCommandFile.parse(List.of(
                endlessLine("on")));
        check(enTrip.rejected().isEmpty() && enTrip.commands().size() == 1
                && enTrip.commands().get(0).verb() == OperatorCommandFile.Verb.SET_ENDLESS
                && "on".equals(enTrip.commands().get(0).text()),
                "endless LIVE line round-trips the REAL operator grammar");
        check("on".equals(endlessFieldPrefill(enBase))
                && "off".equals(endlessFieldPrefill(enSet)),
                "endless prefill: the row in combo words when present, else on");

        // The terrain stanza editor + LIVE verb
        // minter (the endless idiom on TWO rows under ONE marker; words = the WireKnob tables).
        List<String> trBase = List.of("# header", "server.portFile=x");
        List<String> trSet = List.of("# header", "server.portFile=x", TERRAIN_COMMENT,
                TERRAIN_KEY + "=corona", TERRAIN_LEVEL_KEY + "=50");
        check(withTerrainRows(trBase, "corona", "50").equals(trSet),
                "terrain write appends marker + word row + level row");
        check(withTerrainRows(trSet, "abyss", "75").equals(List.of("# header",
                "server.portFile=x", TERRAIN_COMMENT, TERRAIN_KEY + "=abyss",
                TERRAIN_LEVEL_KEY + "=75")),
                "terrain change replaces the stanza in place");
        check(withTerrainRows(List.of("a=1", TERRAIN_KEY + "=pulsar", "b=2"), "corona", "25")
                        .equals(List.of("a=1", TERRAIN_COMMENT, TERRAIN_KEY + "=corona",
                                TERRAIN_LEVEL_KEY + "=25", "b=2")),
                "a lone hand-written word row is replaced in place by the whole stanza");
        check(withTerrainRows(trSet, null, "50") == trSet,
                "null terrain leaves the rows alone (same instance: a web-shell Start never strips)");
        check(withTerrainRows(trSet, "none", "100").equals(trBase),
                "none removes both rows + the marker (back to canonical bytes, absent = none)");
        check(withTerrainRows(withTerrainRows(trBase, "pulsar", "25"), "pulsar", "25")
                        .equals(withTerrainRows(trBase, "pulsar", "25")),
                "terrain write is idempotent");
        check("none".equals(parseTerrainWord(null)) && "none".equals(parseTerrainWord("  "))
                && "corona".equals(parseTerrainWord(" Corona ")),
                "terrain word parse: blank = none, ASCII-folded");
        checkThrows(() -> parseTerrainWord("maybe"),
                "unknown terrain word rejected before the write (reject-never-clamp)");
        check("100".equals(parseTerrainLevel(null)) && "100".equals(parseTerrainLevel(""))
                && "50".equals(parseTerrainLevel("50")),
                "terrain level parse: blank = 100 (the level default), table word kept");
        checkThrows(() -> parseTerrainLevel("33"),
                "off-table terrain level rejected before the write");
        check("terrain corona 50".equals(terrainLine("corona", "50"))
                && "terrain none 100".equals(terrainLine("none", "100"))
                && "terrain pulsar 100".equals(terrainLine("PULSAR", "")),
                "terrain LIVE line goldens (always three tokens)");
        OperatorCommandFile.ParseResult trTrip = OperatorCommandFile.parse(List.of(
                terrainLine("corona", "50"), terrainLine("none", "100")));
        check(trTrip.rejected().isEmpty() && trTrip.commands().size() == 2
                && trTrip.commands().get(0).verb() == OperatorCommandFile.Verb.SET_TERRAIN
                && "corona 50".equals(trTrip.commands().get(0).text())
                && "none 100".equals(trTrip.commands().get(1).text()),
                "terrain LIVE line round-trips the REAL operator grammar");
        check("none".equals(terrainFieldPrefill(trBase)) && "100".equals(terrainLevelFieldPrefill(trBase))
                && "corona".equals(terrainFieldPrefill(trSet))
                && "50".equals(terrainLevelFieldPrefill(trSet))
                && "none".equals(terrainFieldPrefill(List.of(TERRAIN_KEY + "=nebula")))
                && "100".equals(terrainLevelFieldPrefill(List.of(TERRAIN_LEVEL_KEY + "=33"))),
                "terrain prefill: lawful row when present, else none / 100");

        // The asteroids row editor + LIVE knob-line minter
        // (the endless single-row idiom; words = the WireKnob.ASTEROIDS table).
        List<String> asBase = List.of("# header", "server.portFile=x");
        List<String> asSet = List.of("# header", "server.portFile=x", ASTEROIDS_COMMENT,
                ASTEROIDS_KEY + "=100");
        check(withAsteroidsRow(asBase, "100").equals(asSet),
                "asteroids write appends marker + row");
        check(withAsteroidsRow(asSet, "400").equals(List.of("# header", "server.portFile=x",
                ASTEROIDS_COMMENT, ASTEROIDS_KEY + "=400")),
                "asteroids change replaces the row in place");
        check(withAsteroidsRow(asSet, null) == asSet,
                "null asteroids leaves the row alone (same instance: a web-shell Start never strips)");
        check(withAsteroidsRow(asSet, "none").equals(asBase),
                "asteroids none removes the row + the marker (absent = none)");
        check(withAsteroidsRow(withAsteroidsRow(asBase, "20"), "20")
                        .equals(withAsteroidsRow(asBase, "20")),
                "asteroids write is idempotent");
        check("100".equals(asteroidsRowValue(asSet)) && asteroidsRowValue(asBase) == null
                && "100".equals(asteroidsFieldPrefill(asSet))
                && "100".equals(parseAsteroidsWord(asteroidsFieldPrefill(asSet))),
                "asteroids parse/prefill round trip for match.asteroids=100");
        check("none".equals(asteroidsFieldPrefill(asBase))
                && "none".equals(asteroidsFieldPrefill(List.of(ASTEROIDS_KEY + "=999")))
                && "none".equals(parseAsteroidsWord(null)) && "none".equals(parseAsteroidsWord(" "))
                && "400".equals(parseAsteroidsWord(" 400 ")),
                "asteroids: absent/garbage row prefills none; blank parses none");
        checkThrows(() -> parseAsteroidsWord("many"),
                "unknown asteroids word rejected before the write (reject-never-clamp)");
        checkThrows(() -> asteroidsLine("50"),
                "off-table asteroids word refused by the LIVE line minter too");
        check("knob asteroids 100".equals(asteroidsLine("100"))
                && "knob asteroids none".equals(asteroidsLine("")),
                "asteroids LIVE line goldens (knob asteroids <word>)");
        OperatorCommandFile.ParseResult asTrip = OperatorCommandFile.parse(List.of(
                asteroidsLine("100")));
        check(asTrip.rejected().isEmpty() && asTrip.commands().size() == 1
                && asTrip.commands().get(0).verb() == OperatorCommandFile.Verb.SET_KNOB
                && "asteroids 100".equals(asTrip.commands().get(0).text()),
                "asteroids LIVE line round-trips the REAL operator grammar (SET_KNOB)");

        // applyMapSize against a temp fixture tree (the appendVerb leg
        // precedent) — real props file, real minted descriptor, minimal deployed-shaped root.
        Path msDir = Files.createTempDirectory("cc-ops-map-size-selftest");
        Path msProps = msDir.resolve("cc-server.properties");
        Files.writeString(msProps, "# fixture\nmap.half=6000\nother=1\n",
                StandardCharsets.UTF_8);
        Path msDesc = msDir.resolve("match-fixture.txt");
        MatchDescriptor msSeed = MatchDescriptors.mint("1v1", 6000f);
        Files.writeString(msDesc, MatchDescriptorCodec.encode(msSeed));
        Path msMod = msDir.resolve("CC_Client");
        Files.createDirectories(msMod.resolve("jars"));
        Files.write(msMod.resolve("jars/cc-client-mod.jar"), new byte[] {1, 2, 3});
        Files.writeString(msMod.resolve("mod_info.json"), "{\"id\":\"cc-client\"}");
        Files.createDirectories(msMod.resolve("data"));
        Files.writeString(msMod.resolve("data/fixture.csv"), "x");
        byte[] msPropsBefore = Files.readAllBytes(msProps);
        byte[] msDescBefore = Files.readAllBytes(msDesc);
        boolean msRefused = false;
        try {
            applyMapSize(msProps, List.of(msDesc), msMod, "70000");
        } catch (IllegalArgumentException expected) {
            msRefused = true;
        }
        check(msRefused, "apply out-of-bounds refused");
        check(java.util.Arrays.equals(msPropsBefore, Files.readAllBytes(msProps))
                && java.util.Arrays.equals(msDescBefore, Files.readAllBytes(msDesc))
                && !Files.exists(Paths.get(msDesc + ".fingerprint")),
                "refusal wrote NOTHING");
        MapSizeResult msSame = applyMapSize(msProps, List.of(msDesc), msMod, "6000");
        check(!msSame.changed()
                && java.util.Arrays.equals(msPropsBefore, Files.readAllBytes(msProps))
                && java.util.Arrays.equals(msDescBefore, Files.readAllBytes(msDesc)),
                "no-change fast path wrote nothing");
        MapSizeResult msDid = applyMapSize(msProps, List.of(msDesc), msMod, "8000");
        check(msDid.changed() && msDid.mapHalf() == 8000f, "apply landed");
        check("8000".equals(mapHalfRowValue(
                Files.readAllLines(msProps, StandardCharsets.UTF_8))),
                "props row rewritten to 8000");
        MatchDescriptor msNew = MatchDescriptorCodec.decode(
                Files.readAllLines(msDesc, StandardCharsets.UTF_8));
        check(msNew.halfWidth() == 8000f && msNew.halfHeight() == 8000f
                && "1v1".equals(msNew.shape()) && !msSeed.matchId().equals(msNew.matchId()),
                "descriptor re-authored at 8000, shape carried, match.id rotated");
        String msFpRow = null;
        for (String line : Files.readAllLines(
                Paths.get(msDesc + ".fingerprint"), StandardCharsets.US_ASCII)) {
            if (line.startsWith("fingerprint=")) {
                msFpRow = line;
            }
        }
        check(msFpRow != null && msFpRow.contains(";match=" + msNew.matchId())
                && msFpRow.contains(";jar="
                        + ContentFingerprints.md5Hex(new byte[] {1, 2, 3}) + ";"),
                "sidecar re-minted: match= NEW id, jar= the staged deployed bytes");
        boolean msWarned = false;
        for (String line : msDid.report()) {
            if (line.contains("DEAD")) {
                msWarned = true;
            }
        }
        check(msWarned, "report carries the kits/tokens-dead warning");
        deleteTree(msDir);

        // applyStationVariant twins on the same temp-fixture idiom — the
        // mapApply twin: stationless refusal writes NOTHING, unknown family refused,
        // no-change fast path, family swap rotates match.id + re-mints the sidecar, and
        // the props file stays BYTE-IDENTICAL (descriptor-owned, never a props row).
        Path svDir = Files.createTempDirectory("cc-ops-station-selftest");
        Path svProps = svDir.resolve("cc-server.properties");
        Files.writeString(svProps, "# fixture\nmap.half=6000\n", StandardCharsets.UTF_8);
        Path svMod = svDir.resolve("CC_Client");
        Files.createDirectories(svMod.resolve("jars"));
        Files.write(svMod.resolve("jars/cc-client-mod.jar"), new byte[] {4, 5, 6});
        Files.writeString(svMod.resolve("mod_info.json"), "{\"id\":\"cc-client\"}");
        Path svPlain = svDir.resolve("match-stationless.txt");
        Files.writeString(svPlain,
                MatchDescriptorCodec.encode(MatchDescriptors.mint("1v1", 6000f)));
        Path svFort = svDir.resolve("match-fortress.txt");
        MatchDescriptor svSeed = MatchDescriptors.mint(MatchDescriptors.FORTRESS_SHAPE, 6000f);
        Files.writeString(svFort, MatchDescriptorCodec.encode(svSeed));
        byte[] svPropsBefore = Files.readAllBytes(svProps);
        byte[] svFortBefore = Files.readAllBytes(svFort);
        boolean svRefused = false;
        try {
            applyStationVariant(svProps, List.of(svFort, svPlain), svMod, "hightech");
        } catch (IllegalArgumentException expected) {
            svRefused = true;
        }
        check(svRefused && java.util.Arrays.equals(svFortBefore, Files.readAllBytes(svFort))
                && !Files.exists(Paths.get(svFort + ".fingerprint")),
                "a stationless descriptor refuses the WHOLE station apply before any write");
        checkThrows(() -> {
            try {
                applyStationVariant(svProps, List.of(svFort), svMod, "startech");
            } catch (IOException impossible) {
                throw new RuntimeException(impossible);
            }
        }, "unknown station family refused");
        StationVariantResult svSame = applyStationVariant(svProps, List.of(svFort), svMod,
                MatchDescriptors.familyForStationVariant(
                        MatchDescriptors.BASTION_STATION_VARIANT_ID));
        check(!svSame.changed()
                && java.util.Arrays.equals(svFortBefore, Files.readAllBytes(svFort)),
                "station no-change fast path wrote nothing");
        StationVariantResult svDid = applyStationVariant(svProps, List.of(svFort), svMod,
                "hightech");
        MatchDescriptor svNew = MatchDescriptorCodec.decode(
                Files.readAllLines(svFort, StandardCharsets.UTF_8));
        String svStation = null;
        for (MatchDescriptor.RosterRow svRow : svNew.roster()) {
            if (MatchDescriptors.isStationVariantId(svRow.variantId())) {
                svStation = svRow.variantId();
            }
        }
        check(svDid.changed() && "station3_hightech_Standard".equals(svStation)
                && !svSeed.matchId().equals(svNew.matchId())
                && svNew.halfWidth() == 6000f
                && MatchDescriptors.FORTRESS_SHAPE.equals(svNew.shape()),
                "station swap re-authored the family, carried shape+half, rotated match.id");
        check(java.util.Arrays.equals(svPropsBefore, Files.readAllBytes(svProps)),
                "station apply writes NO props row (props byte-identical)");
        String svFpRow = null;
        for (String line : Files.readAllLines(
                Paths.get(svFort + ".fingerprint"), StandardCharsets.US_ASCII)) {
            if (line.startsWith("fingerprint=")) {
                svFpRow = line;
            }
        }
        check(svFpRow != null && svFpRow.contains(";match=" + svNew.matchId())
                && svFpRow.contains(";jar="
                        + ContentFingerprints.md5Hex(new byte[] {4, 5, 6}) + ";"),
                "station sidecar re-minted: match= NEW id, jar= the staged deployed bytes");
        boolean svWarned = false;
        for (String line : svDid.report()) {
            if (line.contains("DEAD")) {
                svWarned = true;
            }
        }
        check(svWarned, "station report carries the kits/tokens-dead warning");
        check("hightech".equals(stationFamilyPrefill(List.of(svFort))),
                "station prefill reads the FIRST descriptor's baked family");
        check(MatchDescriptors.familyForStationVariant(
                        MatchDescriptors.BASTION_STATION_VARIANT_ID)
                .equals(stationFamilyPrefill(List.of(svDir.resolve("missing.txt"))))
                && MatchDescriptors.familyForStationVariant(
                        MatchDescriptors.BASTION_STATION_VARIANT_ID)
                .equals(stationFamilyPrefill(List.of(svPlain))),
                "station prefill falls back to the code default's family on"
                        + " unreadable/stationless");
        List<Path> svFiles = stationDescriptorFiles();
        check(svFiles.size() == 2
                && svFiles.get(0).toString().replace('\\', '/')
                        .endsWith("generated/joiner-kit/match-bastion-fortress.txt")
                && "match-bastion-fortress-ab.txt".equals(
                        svFiles.get(1).getFileName().toString()),
                "default station descriptor set = the standing fortress pair");
        String svPrevProp = System.setProperty(STATION_DESCRIPTORS_PROPERTY, "c.txt;d.txt");
        try {
            check(stationDescriptorFiles().equals(
                    List.of(Paths.get("c.txt"), Paths.get("d.txt"))),
                    "station descriptor-set property override honored");
        } finally {
            if (svPrevProp == null) {
                System.clearProperty(STATION_DESCRIPTORS_PROPERTY);
            } else {
                System.setProperty(STATION_DESCRIPTORS_PROPERTY, svPrevProp);
            }
        }
        deleteTree(svDir);

        // applyTerrainBackdrop twins on the same temp-fixture idiom — unknown
        // word writes NOTHING, none on fresh mints = the no-change fast path, a folded word
        // re-authors BOTH shapes (stationless + fortress), rotates match.id, re-mints the
        // sidecars, leaves props BYTE-IDENTICAL; then the map-size and station applies THREAD
        // the word (the never-silently-reset law, 4th leg).
        //
        Path tbDir = Files.createTempDirectory("cc-ops-backdrop-selftest");
        Path tbProps = tbDir.resolve("cc-server.properties");
        Files.writeString(tbProps, "# fixture\nmap.half=6000\n", StandardCharsets.UTF_8);
        Path tbMod = tbDir.resolve("CC_Client");
        Files.createDirectories(tbMod.resolve("jars"));
        Files.write(tbMod.resolve("jars/cc-client-mod.jar"), new byte[] {7, 8, 9});
        Files.writeString(tbMod.resolve("mod_info.json"), "{\"id\":\"cc-client\"}");
        Path tbPlain = tbDir.resolve("match-stationless.txt");
        MatchDescriptor tbPlainSeed = MatchDescriptors.mint("1v1", 6000f);
        Files.writeString(tbPlain, MatchDescriptorCodec.encode(tbPlainSeed));
        Path tbFort = tbDir.resolve("match-fortress.txt");
        MatchDescriptor tbFortSeed = MatchDescriptors.mint(MatchDescriptors.FORTRESS_SHAPE, 6000f);
        Files.writeString(tbFort, MatchDescriptorCodec.encode(tbFortSeed));
        List<Path> tbBoth = List.of(tbPlain, tbFort);
        byte[] tbPropsBefore = Files.readAllBytes(tbProps);
        byte[] tbPlainBefore = Files.readAllBytes(tbPlain);
        byte[] tbFortBefore = Files.readAllBytes(tbFort);
        boolean tbRefused = false;
        try {
            applyTerrainBackdrop(tbProps, tbBoth, tbMod, "fog");
        } catch (IllegalArgumentException expected) {
            tbRefused = true;
        }
        check(tbRefused && java.util.Arrays.equals(tbPlainBefore, Files.readAllBytes(tbPlain))
                && java.util.Arrays.equals(tbFortBefore, Files.readAllBytes(tbFort))
                && !Files.exists(Paths.get(tbPlain + ".fingerprint"))
                && !Files.exists(Paths.get(tbFort + ".fingerprint")),
                "unknown backdrop word refused before any write");
        BackdropResult tbSame = applyTerrainBackdrop(tbProps, tbBoth, tbMod, "none");
        check(!tbSame.changed()
                && java.util.Arrays.equals(tbPlainBefore, Files.readAllBytes(tbPlain))
                && java.util.Arrays.equals(tbFortBefore, Files.readAllBytes(tbFort)),
                "backdrop none on fresh mints = the no-change fast path (nothing written)");
        BackdropResult tbDid = applyTerrainBackdrop(tbProps, tbBoth, tbMod, " NEBULA ");
        MatchDescriptor tbPlainNew = MatchDescriptorCodec.decode(
                Files.readAllLines(tbPlain, StandardCharsets.UTF_8));
        MatchDescriptor tbFortNew = MatchDescriptorCodec.decode(
                Files.readAllLines(tbFort, StandardCharsets.UTF_8));
        check(tbDid.changed() && "nebula".equals(tbDid.terrain())
                && "nebula".equals(tbPlainNew.terrain())
                && "nebula".equals(tbFortNew.terrain())
                && !tbPlainSeed.matchId().equals(tbPlainNew.matchId())
                && !tbFortSeed.matchId().equals(tbFortNew.matchId())
                && "1v1".equals(tbPlainNew.shape())
                && MatchDescriptors.FORTRESS_SHAPE.equals(tbFortNew.shape()),
                "backdrop ' NEBULA ' folded + re-authored BOTH shapes, rotated match.id");
        check(Files.exists(Paths.get(tbPlain + ".fingerprint"))
                && Files.exists(Paths.get(tbFort + ".fingerprint")),
                "backdrop apply re-minted a sidecar beside each descriptor");
        check(java.util.Arrays.equals(tbPropsBefore, Files.readAllBytes(tbProps)),
                "backdrop apply writes NO props row (props byte-identical)");
        boolean tbWarned = false;
        for (String line : tbDid.report()) {
            if (line.contains("DEAD")) {
                tbWarned = true;
            }
        }
        check(tbWarned, "backdrop report carries the kits/tokens-dead warning");
        MapSizeResult tbMap = applyMapSize(tbProps, tbBoth, tbMod, "7000");
        check(tbMap.changed()
                && "nebula".equals(MatchDescriptorCodec.decode(Files.readAllLines(
                        tbPlain, StandardCharsets.UTF_8)).terrain())
                && "nebula".equals(MatchDescriptorCodec.decode(Files.readAllLines(
                        tbFort, StandardCharsets.UTF_8)).terrain()),
                "map-size apply THREADS the backdrop word (never silently reset)");
        StationVariantResult tbStation = applyStationVariant(tbProps, List.of(tbFort), tbMod,
                "hightech");
        check(tbStation.changed()
                && "nebula".equals(MatchDescriptorCodec.decode(Files.readAllLines(
                        tbFort, StandardCharsets.UTF_8)).terrain()),
                "station apply THREADS the backdrop word (never silently reset)");
        check("nebula".equals(terrainBackdropPrefill(List.of(tbFort)))
                && MatchDescriptor.TERRAIN_NONE.equals(
                        terrainBackdropPrefill(List.of(tbDir.resolve("missing.txt")))),
                "backdrop prefill reads the FIRST descriptor's word; missing => none");
        deleteTree(tbDir);

        // The shells' path plumbing + field prefill.
        check("6000".equals(mapHalfFieldPrefill(List.of("map.half=6000", "other=1"))),
                "map-size prefill reads the explicit row");
        check(mapHalfText(MatchDescriptors.CERTIFIED_MAP_HALF)
                .equals(mapHalfFieldPrefill(List.of("other=1"))),
                "map-size prefill falls back to the certified default");
        List<Path> msFiles = mapSizeDescriptorFiles();
        check(msFiles.size() == 2
                && msFiles.get(0).toString().replace('\\', '/')
                        .endsWith("generated/joiner-kit/match-standing.txt")
                && "match-bastion-fortress.txt".equals(
                        msFiles.get(1).getFileName().toString()),
                "default map-size descriptor set = the LIVE pair");
        String msPrevProp = System.setProperty(MAP_SIZE_DESCRIPTORS_PROPERTY, "a.txt;b.txt");
        try {
            check(mapSizeDescriptorFiles().equals(
                    List.of(Paths.get("a.txt"), Paths.get("b.txt"))),
                    "map-size descriptor-set property override honored");
        } finally {
            if (msPrevProp == null) {
                System.clearProperty(MAP_SIZE_DESCRIPTORS_PROPERTY);
            } else {
                System.setProperty(MAP_SIZE_DESCRIPTORS_PROPERTY, msPrevProp);
            }
        }

        // The sidecar path rides the match.descriptor row (P1 sibling law);
        // no row = null = the GUI refuses a password Start (the wrapper cannot boot anyway
        // and a "default" path would only mint an orphaned armed sidecar)
        check("out/joiner-kit/match-standing.txt.password".equals(passwordSidecarPath(
                List.of("# header", "match.descriptor=out/joiner-kit/match-standing.txt",
                        "watch.frames=21600"))),
                "sidecar path from the match.descriptor row");
        check(passwordSidecarPath(List.of("a=1")) == null,
                "absent descriptor row -> null (never a default path)");
        check(passwordSidecarPath(List.of("match.descriptor=")) == null,
                "blank descriptor row -> null");
        check(passwordSidecarPath(List.of()) == null, "empty properties -> null");
        check("y.txt.password".equals(passwordSidecarPath(
                List.of("match.descriptor=x.txt", "match.descriptor=y.txt"))),
                "descriptor row reads last-wins (Properties/Read-KvFile semantics)");
        check(passwordSidecarPath(List.of("# match.descriptor=z.txt")) == null,
                "commented descriptor mention is not a row");
        check("spaced.txt.password".equals(passwordSidecarPath(
                List.of("match.descriptor= spaced.txt "))),
                "descriptor value trimmed (the serve mint trims too)");
        // The config-relative rule the descriptor-family readers re-root on
        Path crCfg = Paths.get("some", "dir", "cc-server.properties").toAbsolutePath().normalize();
        Path crDir = crCfg.getParent();
        check(configRelative(crCfg, " match/match.txt ").equals(crDir.resolve("match").resolve("match.txt")),
                "config-relative: a relative row resolves against the CONFIG file's dir, trimmed"
                        + " (flips if it resolves against cwd)");
        check(configRelative(crCfg, "./match/match.txt").equals(crDir.resolve("match").resolve("match.txt")),
                "config-relative: a ./ row normalizes (flips if the ./ survives)");
        check(configRelative(crCfg, crDir.resolve("abs.txt").toString()).equals(crDir.resolve("abs.txt")),
                "config-relative: an absolute row is itself");
        check(configRelative(Paths.get("cc-server.properties"), "match/m.txt").equals(
                Paths.get("").toAbsolutePath().resolve("match").resolve("m.txt").normalize()),
                "config-relative: a bare config name resolves against ITS absolute parent (= cwd)");

        // P6 field sanitize/validate — single-sourced through the REAL MatchPasswords parser,
        // so the GUI's accept set can never drift from the serve-open door's
        check("".equals(sanitizedMatchPassword(null)), "null field -> blank (token mode)");
        check("".equals(sanitizedMatchPassword("   ")), "whitespace field -> blank");
        check("Fri-day-42".equals(sanitizedMatchPassword(" \"Fri-day-42\" ")),
                "quote-strip + trim (chat-paste hazards, JoinGui idiom)");
        // A single repeated char is blocklisted, so the cap fixture
        // alternates two chars.
        String cap = "a1".repeat(MatchPasswords.PASSWORD_MAX_CHARS / 2);
        check(cap.equals(sanitizedMatchPassword(cap)), "64 chars accepted (cap inclusive)");
        checkThrows(() -> sanitizedMatchPassword(cap + "a"), "over 64 chars rejected");
        checkThrows(() -> sanitizedMatchPassword("has space"), "inner space rejected (P1 charset)");
        checkThrows(() -> sanitizedMatchPassword("under_score"), "underscore rejected");
        checkThrows(() -> sanitizedMatchPassword("pw=x"), "equals sign rejected");
        checkThrows(() -> sanitizedMatchPassword("#pw"), "hash rejected");
        // The kit vguard refuses a leading hyphen — so does the door
        checkThrows(() -> sanitizedMatchPassword("-lead"), "leading hyphen rejected (kit vguard law)");
        check("a-b-c-d-".equals(sanitizedMatchPassword("a-b-c-d-")), "inner/trailing hyphens accepted");
        // The 8-char entropy floor — 7 refuses with "8 to 64" wording, 8 passes
        String seven = "abc-123";
        boolean sevenRefused = false;
        try {
            sanitizedMatchPassword(seven);
        } catch (IllegalArgumentException expected) {
            sevenRefused = expected.getMessage().contains("8 to 64");
        }
        check(sevenRefused, "7 chars refused with the '8 to 64' wording (entropy floor)");
        check("abc-1234".equals(sanitizedMatchPassword("abc-1234")), "8 chars accepted (floor inclusive)");
        // The common-password blocklist (match AND admin)
        // and the admin 12 floor — messages name the law, never the value.
        check(messageOf(() -> sanitizedMatchPassword("password")).contains("common password"),
                "match 'password' refused with the 'common password' wording");
        check(messageOf(() -> sanitizedMatchPassword("StarSector")).contains("common password"),
                "blocklist is case-insensitive (StarSector refused)");
        check(messageOf(() -> sanitizedMatchPassword("aaaaaaaa")).contains("common password")
                        && !messageOf(() -> sanitizedMatchPassword("aaaaaaaa")).contains("aaaaaaaa"),
                "a single repeated char refused (aaaaaaaa), the message never echoes the value");
        check(messageOf(() -> sanitizedAdminPassword("Adm-7-pw-20")).contains("12 to 64"),
                "admin 11 chars refused with the '12 to 64' wording (admin floor)");
        check("Adm-7-pw-202".equals(sanitizedAdminPassword("Adm-7-pw-202")),
                "admin 12 chars accepted (admin floor inclusive)");
        check(messageOf(() -> sanitizedAdminPassword("fractalsoftworks")).contains("common password"),
                "admin blocklisted 12+ word refused (fractalsoftworks)");
        check(MatchPasswords.blocklistSize() > 0 && MatchPasswords.blocklistSize() <= 256,
                "blocklist is compiled and at most 256 entries");
        check(!MatchPasswords.isBlocklisted(null) && !MatchPasswords.isBlocklisted("Fri-day-42"),
                "null / an ordinary code is not blocklisted");

        // Join brick: advertise + password -> the one-line ip:port:pass a host sends
        check("1.2.3.4:51820:Fri-day-42".equals(joinBrick("1.2.3.4:51820", " \"Fri-day-42\" ")),
                "join brick composes advertise:password (field sanitize applies)");
        checkThrows(() -> joinBrick("-", "pw-1"), "no lease (advertise '-') -> no brick");
        checkThrows(() -> joinBrick("", "pw-1"), "blank advertise -> no brick");
        checkThrows(() -> joinBrick(null, "pw-1"), "null advertise -> no brick");
        checkThrows(() -> joinBrick("1.2.3.4:51820", "  "), "token mode (blank pw) -> no brick");
        checkThrows(() -> joinBrick("1.2.3.4:51820", "has space"), "bad charset -> no brick");

        // P6 write shape: the exact bytes Start writes round-trip the REAL serve-open parser
        String content = passwordSidecarContent("Fri-day-42");
        check("Fri-day-42".equals(MatchPasswords.parseSidecarPassword(
                content.lines().toList(), "selftest")),
                "sidecar content round-trips MatchPasswords.parseSidecarPassword");
        check(content.endsWith("\n") && content.contains("password=Fri-day-42"),
                "sidecar content shape (newline-terminated k=v)");
        check("Fri-day-42".equals(sidecarPasswordRowValue(content.lines().toList())),
                "prefill row-read reads back the written value");
        check(sidecarPasswordRowValue(List.of("# password=zzz", "x=1")) == null,
                "commented password row is not a prefill");
        check("b".equals(sidecarPasswordRowValue(List.of("password=a", "password=b"))),
                "prefill reads last-wins like the parser");
        check("bad pw".equals(sidecarPasswordRowValue(List.of("password=bad pw"))),
                "prefill is lenient: a charset-violating row prefills AS WRITTEN (bites at Start)");

        // P6 Start-action table
        check(passwordStartAction("", false, null) == PasswordAction.KEEP,
                "blank + absent = keep (token lane untouched)");
        check(passwordStartAction("", true, "x") == PasswordAction.REMOVE,
                "blank + present = remove (GUI confirms first)");
        check(passwordStartAction("pw-1", true, "pw-1") == PasswordAction.KEEP,
                "unchanged password = keep (hand-authored sidecar bytes survive)");
        check(passwordStartAction("pw-2", true, "pw-1") == PasswordAction.WRITE,
                "changed password = write");

        // The admin sidecar twin — path derivation, content round-trip, sanitize
        check("x/m.txt.adminpassword".equals(adminSidecarOf("x/m.txt.password")),
                "admin sidecar = the .adminpassword sibling of the .password path");
        check(adminSidecarOf(null) == null, "no match sidecar -> no admin sidecar");
        check("x/m.txt.adminpassword".equals(adminSidecarOf("x/m.txt")),
                "a bare descriptor path gets the admin suffix");
        String adminContent = adminPasswordSidecarContent("Adm-7-pw-2026");
        check("Adm-7-pw-2026".equals(MatchPasswords.parseAdminSidecarPassword(adminContent.lines().toList(),
                "row")), "admin sidecar content round-trips the REAL parser (same row key, D2)");
        check("Adm-7-pw-2026".equals(sidecarPasswordRowValue(adminContent.lines().toList())),
                "admin sidecar row value prefills");
        check("Adm-7-pw-2026".equals(sanitizedAdminPassword(" \"Adm-7-pw-2026\" "))
                        && "".equals(sanitizedAdminPassword(null)),
                "admin field sanitize = the match-password law");
        checkThrows(() -> sanitizedAdminPassword("no space"), "admin charset violation refuses");
        check(passwordStartAction("pw-1", false, null) == PasswordAction.WRITE,
                "fresh password = write");

        // Selector identity parse rides the REAL boot codec — an
        // encode round-trip of a real mint, so the selector's bootable set == the door's
        MatchDescriptor minted = MatchDescriptors.oneVsOne();
        List<String> descLines = MatchDescriptorCodec.encode(minted).lines().toList();
        DescriptorInfo info = parseDescriptorInfo(descLines);
        check(minted.matchId().equals(info.matchId()) && "1v1".equals(info.shape())
                && info.seatCount() == 2,
                "descriptor info rides the real codec (encode round-trip)");
        List<String> leadingBlank = new ArrayList<>();
        leadingBlank.add("");
        leadingBlank.addAll(descLines);
        check(info.equals(parseDescriptorInfo(leadingBlank)),
                "leading blank line accepted (codec parity, not exact-first-line)");
        checkThrows(() -> parseDescriptorInfo(List.of()), "empty descriptor file rejected");
        checkThrows(() -> parseDescriptorInfo(List.of("match_id=x", "shape=1v1")),
                "tokens sidecar rejected (no version line)");
        List<String> dupShape = new ArrayList<>(descLines);
        dupShape.add("match.shape=1v1");
        checkThrows(() -> parseDescriptorInfo(dupShape),
                "duplicate key rejected (the codec's law — NOT last-wins leniency)");
        checkThrows(() -> parseDescriptorInfo(descLines.subList(0, 6)),
                "hand-truncated descriptor rejected (missing required rows)");
        check("1v1  -  match-1v1.txt  (2 seats)".equals(
                descriptorOptionLabel("match-1v1.txt", info)), "selector label golden");
        check("gauntlet:3:skipper  -  match-standing.txt  (4 seats)".equals(
                descriptorOptionLabel("match-standing.txt",
                        new DescriptorInfo("x", "gauntlet:3:skipper", 4))),
                "selector label plural seats");
        check("1v1  -  m.txt  (1 seat)".equals(descriptorOptionLabel("m.txt",
                new DescriptorInfo("x", "1v1", 1))), "selector label singular seat");

        // The Start no-change guard is CORE logic (default-Start byte-identity is
        // gate-pinned, not a shell comparison — review find wf_ce0b03d4)
        check(!descriptorSwitchNeeded(null, "a.txt"), "no selection = never a switch");
        check(!descriptorSwitchNeeded("gen/kit/m.txt", "gen/kit/m.txt"), "same row = no switch");
        check(!descriptorSwitchNeeded("gen/kit/m.txt", "gen\\kit\\M.TXT"),
                "case/backslash row variant = no switch (Windows fs; canonical bytes kept)");
        check(!descriptorSwitchNeeded(" gen/kit/m.txt ", "gen/kit/m.txt"),
                "padding trimmed before compare");
        check(descriptorSwitchNeeded("a.txt", null), "absent row + selection = switch (append)");
        check(descriptorSwitchNeeded("b.txt", "a.txt"), "different file = switch");

        // armed-door row read (pre-write warning input; wrapper stays the authority)
        check(!doorFingerprintArmedRow(List.of("x=1")), "no door row = not armed");
        check(doorFingerprintArmedRow(List.of("net.doorFingerprint=true")), "armed row reads armed");
        check(doorFingerprintArmedRow(List.of("net.doorFingerprint=false",
                "net.doorFingerprint=TRUE")),
                "door row last-wins + case-insensitive (the wrapper's -ieq law)");
        check(!doorFingerprintArmedRow(List.of("# net.doorFingerprint=true",
                "net.doorFingerprint=false")), "commented mention is not a row; false = off");

        // The match.descriptor row surgery (withTickRateRow idiom; no remove branch)
        List<String> propsFix = List.of("# standing posture", "match.descriptor=a.txt", "x=1");
        check(withDescriptorRow(propsFix, "a.txt").equals(propsFix),
                "same value = byte-identical lines");
        check(withDescriptorRow(propsFix, "b/c.txt").equals(List.of(
                "# standing posture", "match.descriptor=b/c.txt", "x=1")),
                "row replaced in place (comment above preserved)");
        check(withDescriptorRow(List.of("x=1"), "d.txt").equals(
                List.of("x=1", "match.descriptor=d.txt")), "absent row appended");
        check(withDescriptorRow(List.of("match.descriptor=a.txt", "x=1",
                "match.descriptor=b.txt"), "c.txt").equals(
                List.of("match.descriptor=c.txt", "x=1")),
                "shadowed duplicate rows cleaned (last-wins semantics)");
        check(withDescriptorRow(List.of("# match.descriptor=z.txt", "x=1"), "e.txt").equals(
                List.of("# match.descriptor=z.txt", "x=1", "match.descriptor=e.txt")),
                "commented mention never edited");
        check("gen/kit/m.txt".equals(descriptorRowValue(withDescriptorRow(List.of(),
                "gen\\kit\\m.txt"))), "backslash path normalized to forward slashes");
        check("k.txt.password".equals(passwordSidecarPath(withDescriptorRow(propsFix, "k.txt"))),
                "password sidecar path follows the switched row (P1 sibling law)");
        checkThrows(() -> withDescriptorRow(List.of(), "  "), "blank descriptor path rejected");
        // The descriptor row resolves with the
        // Properties key grammar the launcher + serve use - a spaced or ':' row is the SAME key
        // (last wins; the rewrite drops it), a comment or a longer key never is
        String gRow = "descriptor-row-properties-grammar";
        List<String> gLines = List.of("match.descriptor=a.txt", "  match.descriptor = b.txt",
                "match.descriptor : c.txt", "#match.descriptor=d.txt", "match.descriptorX=e.txt");
        String gValue = named(gRow, () -> descriptorRowValue(gLines));
        List<String> gWritten = named(gRow, () -> withDescriptorRow(gLines, "z.txt"));
        check("c.txt".equals(gValue) && gWritten.equals(List.of("match.descriptor=z.txt",
                "#match.descriptor=d.txt", "match.descriptorX=e.txt")),
                gRow + ": descriptorRowValue reads the LAST Properties row (c.txt) and"
                        + " withDescriptorRow keeps ONE row, the first replaced in place (flips if a"
                        + " spaced / colon row is skipped, a prefix key or a comment matches, or a"
                        + " shadowing duplicate survives); got " + gValue + " / " + gWritten);

        // ---- the shared shell-law extractions ----

        // detail-text goldens (Swing inline law moved verbatim; the web JSON rides the same)
        check("127.0.0.1:51888  pid=4242  up=125s  round=0 frame=7500  clients=2/2  tick=60.0/s"
                .equals(runDetailText(snap)), "run detail golden");
        check(goneDetailText(snap).equals("server pid 4242 is GONE without a final snapshot"
                + " (kill fallback or crash) - check " + START_LOG), "gone detail golden");

        // the remember/substitute end-state law
        check(rememberRunSeats(snap), "run phase with seats -> remember");
        List<String> finalLines = new ArrayList<>();
        for (String line : FIXTURE) {
            if (line.startsWith("seat.")) {
                continue;
            }
            finalLines.add(line.startsWith("phase=") ? "phase=final" : line);
        }
        finalLines.add("seat.count=0");
        OpsSnapshot finalSnap = OpsSnapshot.parse(finalLines);
        check(!rememberRunSeats(finalSnap), "final phase never remembered");
        check(displaySeatRows(finalSnap, true, snap.seats()).equals(snap.seats()),
                "match-over empty table falls back to the remembered rows");
        check(displaySeatRows(snap, false, List.of()).equals(snap.seats()),
                "run phase displays the live rows");
        check(displaySeatRows(snap, true, List.of()).equals(snap.seats()),
                "match-over WITH rows keeps them (no substitution)");

        // selector scan-dir derivation (moved from the Swing shell)
        check(DESCRIPTOR_DIR.equals(descriptorScanDir(null)), "no row -> default scan dir");
        check("out/joiner-kit".equals(descriptorScanDir(
                "out\\joiner-kit\\match-standing.txt")),
                "row dir wins (backslash-normalized)");
        check(DESCRIPTOR_DIR.equals(descriptorScanDir("match.txt")),
                "dir-less row -> default scan dir");

        // The WHOLE selector-population law (temp dir; the real-codec gate skips
        // the garbage file, the minted file lists, the standing row selects)
        Path optDir = Files.createTempDirectory("cc-ops-desc");
        Files.writeString(optDir.resolve("match-a.txt"), MatchDescriptorCodec.encode(minted),
                StandardCharsets.UTF_8);
        Files.writeString(optDir.resolve("tokens.txt"), "match_id=x\nshape=1v1\n",
                StandardCharsets.UTF_8);
        String optDirText = optDir.toString().replace('\\', '/');
        // The scan-dir + standing rows here are
        // ABSOLUTE, so any config path (this one names a dir that does not exist) resolves them
        // to themselves.
        Path optCfg = Paths.get("no", "such", "cfg", "cc-server.properties");
        DescriptorOptions optsSel = descriptorOptions(optCfg, optDirText,
                optDirText + "/match-a.txt");
        check(optsSel.options().size() == 1 && optsSel.selectedIndex() == 0
                && (optDirText + "/match-a.txt").equals(optsSel.options().get(0).path())
                && descriptorOptionLabel("match-a.txt", optsSel.options().get(0).info())
                        .equals(optsSel.options().get(0).label()),
                "selector: minted file listed + selected, garbage file codec-refused");
        DescriptorOptions optsNoRow = descriptorOptions(optCfg, optDirText, null);
        check(optsNoRow.options().size() == 2 && optsNoRow.selectedIndex() == 0
                && optsNoRow.options().get(0).path() == null
                && optsNoRow.options().get(0).label().startsWith("(no match.descriptor row"),
                "selector: no row -> cannot-start entry first, scanned appended");
        DescriptorOptions optsOutside = descriptorOptions(optCfg, optDirText,
                "no/such/dir/match-z.txt");
        check(optsOutside.options().size() == 2 && optsOutside.options().get(0).path() == null
                && optsOutside.options().get(0).label().contains("the codec refused it"),
                "selector: unreadable standing row -> boots-as-is entry (gated, not asserted)");
        DescriptorOptions optsNoDir = descriptorOptions(optCfg, optDirText + "/nope",
                optDirText + "/match-a.txt");
        check(optsNoDir.options().size() == 1 && optsNoDir.options().get(0).info() != null
                && optsNoDir.options().get(0).label().endsWith("(standing row)"),
                "selector: unscannable dir -> standing row gated through the SAME parse");
        for (DescriptorOptions o : List.of(optsSel, optsNoRow, optsOutside, optsNoDir)) {
            for (DescriptorOption opt : o.options()) {
                check((opt.path() == null) == (opt.info() == null),
                        "selector invariant: path != null <=> info != null");
            }
        }

        // sidecar prefill (shared by the Swing follow-the-pick sync and /api/config)
        Path prefillSidecar = optDir.resolve("match-a.txt.password");
        Files.writeString(prefillSidecar, "# hand\npassword=pw-pref\n", StandardCharsets.UTF_8);
        check("pw-pref".equals(sidecarPrefill(optCfg, prefillSidecar.toString())),
                "sidecar prefill reads the row");
        check("".equals(sidecarPrefill(optCfg, null)) && "".equals(sidecarPrefill(optCfg,
                optDir.resolve("absent.password").toString())),
                "absent sidecar prefills blank");
        Files.delete(prefillSidecar);

        // The WHOLE Start orchestration headlessly (temp props/sidecar; scripted
        // Ui + recording spawn pin decisions AND written bytes — the orchestration used to
        // live un-gated in the Swing listener)
        check(Files.isRegularFile(Paths.get(STAGE_TOOL)),
                "selftest runs from the repo root (start-flow rows resolve " + STAGE_TOOL + ")");
        // The probe + chain read a TEMP package root here,
        // never owner mode's live package root (a live serve there would refuse every row).
        // The seam is the whole OpsRoots record (reset after the chain rows).
        Path flowPkg = Files.createTempDirectory("cc-ops-pkg");
        OpsRoots.ROOTS.set(OpsRoots.ownerUnder(flowPkg));
        Path flowDir = Files.createTempDirectory("cc-ops-flow");
        Path flowProps = flowDir.resolve("cc-server.properties");
        Path flowDesc = flowDir.resolve("match-flow.txt");
        Files.writeString(flowDesc, MatchDescriptorCodec.encode(minted), StandardCharsets.UTF_8);
        String flowDescText = flowDesc.toString().replace('\\', '/');
        // Every Start writes the debug-watch posture row (false for a plain Start), so a fixture that pins a
        // Start's bytes carries the row as the shipped template does; start-flow-client-debug pins the append.
        String cdOff = CLIENT_DEBUG_KEY + "=false";
        Files.writeString(flowProps, "# posture\r\nmatch.descriptor=" + flowDescText + "\r\n" + cdOff + "\r\n",
                StandardCharsets.UTF_8);
        List<List<String>> spawned = new ArrayList<>();
        SpawnFn recorder = recordedCmd -> {
            spawned.add(recordedCmd);
            return null;
        };

        // default start: no edits anywhere, spawn fired, info composed
        ScriptedUi ui1 = new ScriptedUi();
        ui1.confirmed.add(CONFIRM_TOKEN_MODE_START);
        String flowPropsBytes = Files.readString(flowProps, StandardCharsets.UTF_8);
        check(runStartFlow(ui1, recorder, flowProps, "127.0.0.1", "51337", "", "", "", null, null),
                "default start returns true");
        check(spawned.size() == 1 && spawned.get(0).equals(stageCommand(flowPkg,
                flowProps.toAbsolutePath().normalize(), "127.0.0.1", 51337,
                ProcessHandle.current().pid())) && !STAGING.get(),
                "default start spawns the stage vector (temp pkg root, absolute config, this"
                        + " JVM's pid) and a recording spawn leaves no chain staging");
        check(flowPropsBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "default start leaves the props byte-identical");
        check(ui1.failures.isEmpty()
                && ui1.confirmsAsked.equals(List.of(CONFIRM_TOKEN_MODE_START))
                && ui1.infos.size() == 1
                && ui1.infos.get(0).startsWith(
                        "start requested (bind 127.0.0.1:51337, tick 60 Hz, admission token)"),
                "default start info golden (no kit warnings, token admission)");
        // The token-mode confirm must not claim the kit ships no tokens
        // (a keep-tokens roll carries them — the default in TOKEN mode; a password-armed roll packs
        // REDACTED) and must name the -RedactTokens pack posture.
        String tokenModeText = ui1.confirmMessages.isEmpty() ? "" : ui1.confirmMessages.get(0);
        check(!tokenModeText.contains("ships no tokens") && tokenModeText.contains("-RedactTokens")
                && tokenModeText.contains("admit whoever holds them"),
                "token-mode Start confirm states that shipped seat tokens admit their holder (flips if the"
                        + " text again denies that a keep-tokens kit carries tokens)");

        // validation failure: fail() speaks, nothing written, nothing spawned
        ScriptedUi ui2 = new ScriptedUi();
        check(!runStartFlow(ui2, recorder, flowProps, "127.0.0.1", "51337", "sixty", "", "",
                null, null), "bad tick refuses");
        check(ui2.failures.size() == 1 && spawned.size() == 1
                && flowPropsBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "bad tick: fail only, no write, no spawn");

        // no-descriptor-row password refusal (the orphaned-armed-sidecar guard)
        Path bareProps = flowDir.resolve("bare.properties");
        Files.writeString(bareProps, "x=1\n", StandardCharsets.UTF_8);
        ScriptedUi ui3 = new ScriptedUi();
        check(!runStartFlow(ui3, recorder, bareProps, "127.0.0.1", "51337", "", "", "pw-1pass",
                null, null), "password start without a descriptor row refuses");
        check(ui3.failures.size() == 1
                && ui3.failures.get(0).contains("no match.descriptor row"),
                "refusal names the missing row");

        // password write: sidecar bytes land EXACTLY the composer output, props untouched
        ScriptedUi ui4 = new ScriptedUi();
        Path flowSidecar = Paths.get(flowDescText + ".password");
        check(runStartFlow(ui4, recorder, flowProps, "10.2.0.2", "51820", "", "", "pw-Apass",
                null, null), "password start proceeds");
        check(passwordSidecarContent("pw-Apass").equals(
                Files.readString(flowSidecar, StandardCharsets.UTF_8)),
                "sidecar bytes are the exact composer output");
        check(ui4.infos.get(0).contains("admission password"), "info says password admission");

        // unchanged password = KEEP (hand-authored sidecar bytes survive byte-identical)
        Files.writeString(flowSidecar, "# hand-authored\npassword=pw-Apass\n",
                StandardCharsets.UTF_8);
        ScriptedUi ui5 = new ScriptedUi();
        check(runStartFlow(ui5, recorder, flowProps, "127.0.0.1", "51337", "", "", "pw-Apass",
                null, null), "unchanged password start proceeds");
        check("# hand-authored\npassword=pw-Apass\n".equals(
                Files.readString(flowSidecar, StandardCharsets.UTF_8)),
                "unchanged password keeps the hand-authored bytes");

        // reroot-config-dir /
        // reroot-cwd-untouched / tick-text-adopts - a config in a temp dir != cwd carrying a
        // RELATIVE match.descriptor row (a package's shape): the selector, the prefill and the
        // Start flow's sidecar writes resolve against the CONFIG dir, the row stays relative,
        // nothing lands under cwd; the press's non-60 tick pins the info text.
        Path rrDir = Files.createTempDirectory("cc-ops-reroot");
        Path rrCfg = rrDir.resolve("cc-server.properties");
        String rrRow = "sub/match-x.txt";
        Files.createDirectories(rrDir.resolve("sub"));
        Files.writeString(rrDir.resolve(rrRow), MatchDescriptorCodec.encode(minted),
                StandardCharsets.UTF_8);
        Files.writeString(rrDir.resolve("sub/match-y.txt"), MatchDescriptorCodec.encode(minted),
                StandardCharsets.UTF_8);
        Files.writeString(rrCfg, "match.descriptor=" + rrRow + "\n", StandardCharsets.UTF_8);
        DescriptorOptions rrOpts = descriptorOptions(rrCfg, descriptorScanDir(rrRow), rrRow);
        check(rrOpts.options().size() == 2 && rrOpts.selectedIndex() == 0
                && rrRow.equals(rrOpts.options().get(0).path())
                && "sub/match-y.txt".equals(rrOpts.options().get(1).path())
                && descriptorOptionLabel("match-x.txt", rrOpts.options().get(0).info())
                        .equals(rrOpts.options().get(0).label()),
                "reroot-config-dir (a): the selector scans the CONFIG dir's sub/ (both files, the"
                        + " row's own selected) and keeps option paths in ROW space (flips if the scan"
                        + " opens cwd-relative - a lone '(standing row)' entry - or a path goes absolute)");
        Path rrWant = rrDir.resolve(rrRow + ".password").toAbsolutePath().normalize();
        Path rrAdminWant = rrDir.resolve(rrRow + ".adminpassword").toAbsolutePath().normalize();
        Files.writeString(rrWant, "password=pw-Rpref\n", StandardCharsets.UTF_8);
        check("pw-Rpref".equals(sidecarPrefill(rrCfg, rrRow + ".password")),
                "reroot-config-dir (b): the prefill reads the sidecar beside the CONFIG dir's"
                        + " descriptor (flips if it resolves the row against cwd: a blank prefill)");
        Files.delete(rrWant);
        Path cwdSub = Paths.get("sub").toAbsolutePath().normalize();
        List<Path> cwdStrays = List.of(cwdSub.resolve("match-x.txt.password"),
                cwdSub.resolve("match-x.txt.adminpassword"));
        boolean cwdSubBefore = Files.exists(cwdSub);
        check(cwdStrays.stream().noneMatch(Files::exists), "SETUP-reroot-cwd-untouched: cwd already"
                + " holds sub/match-x.txt.(admin)password - the row cannot see a cwd write");
        ScriptedUi uiRr = new ScriptedUi();
        boolean rrStarted = runStartFlow(uiRr, recorder, rrCfg, "127.0.0.1", "51337", "30", "",
                "pw-Rpass", null, null, "", null, "Adm-7-pw-2026");
        boolean cwdWritten = cwdStrays.stream().anyMatch(Files::exists);
        boolean cwdSubMade = !cwdSubBefore && Files.exists(cwdSub);
        // a regressed run's strays never outlive it (only what THIS press created goes)
        for (Path stray : cwdStrays) {
            Files.deleteIfExists(stray);
        }
        if (cwdSubMade) {
            Files.deleteIfExists(cwdSub);
        }
        List<String> rrLinesAfter = Files.readAllLines(rrCfg, StandardCharsets.UTF_8);
        check(rrStarted && uiRr.failures.isEmpty()
                && configRelative(rrCfg, passwordSidecarPath(rrLinesAfter)).toAbsolutePath()
                        .normalize().equals(rrWant)
                && Files.isRegularFile(rrWant) && passwordSidecarContent("pw-Rpass").equals(
                        Files.readString(rrWant, StandardCharsets.UTF_8))
                && Files.isRegularFile(rrAdminWant)
                && adminPasswordSidecarContent("Adm-7-pw-2026").equals(
                        Files.readString(rrAdminWant, StandardCharsets.UTF_8))
                && rrRow.equals(descriptorRowValue(rrLinesAfter)),
                "reroot-config-dir (c): a password Start lands .password + .adminpassword beside the"
                        + " CONFIG dir's descriptor (both sides toAbsolutePath().normalize()) and the"
                        + " props row stays the relative string (flips if the flow resolves a sidecar"
                        + " against cwd, or the row is rewritten absolute)");
        check(!cwdWritten && !cwdSubMade,
                "reroot-cwd-untouched: the same Start writes nothing under cwd's sub/ (flips if a"
                        + " sidecar write rides the cwd-relative path)");
        // On a POSIX box both sidecars that Start wrote are owner-only (Windows skips the check).
        boolean rrPosix = rrWant.getFileSystem().supportedFileAttributeViews().contains("posix");
        check(!rrPosix || ("rw-------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(rrWant)))
                && "rw-------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(rrAdminWant)))),
                "sidecar-owner-only: a password Start leaves .password + .adminpassword rw------- on POSIX"
                        + " (flips if a sidecar is written with the umask mode)");
        check(uiRr.infos.size() == 1
                && uiRr.infos.get(0).contains("tick 30 Hz - joiners adopt it at join")
                && !uiRr.infos.get(0).contains("KIT PACKED AT THIS RATE"),
                "tick-text-adopts: a non-60 Start says joiners adopt the rate at join (flips if the"
                        + " stale kit-re-pack clause returns)");

        // The RECORD entry (the retired ladder's
        // params object) lands the terrain stanza in the ONE props write; none removes it and
        // the file returns to its exact bytes; a bad word refuses pre-write.
        String trFlowBefore = Files.readString(flowProps, StandardCharsets.UTF_8);
        ScriptedUi uiT1 = new ScriptedUi();
        check(runStartFlow(uiT1, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", null, null, null, null,
                null, null, "abyss", "75", null)), "terrain start (record entry) proceeds");
        List<String> trFlowLines = Files.readAllLines(flowProps, StandardCharsets.UTF_8);
        check("abyss".equals(terrainRowValue(trFlowLines))
                && "75".equals(terrainLevelRowValue(trFlowLines))
                && uiT1.infos.get(0).contains(", terrain abyss 75%"),
                "terrain start writes match.terrain=abyss + match.terrainLevel=75");
        ScriptedUi uiT2 = new ScriptedUi();
        check(runStartFlow(uiT2, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", null, null, null, null,
                null, null, "none", "100", null)), "terrain none start proceeds");
        check(trFlowBefore.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "terrain none removes the stanza (props back to their exact bytes)");
        startFlowClientDebugRow();
        ScriptedUi uiT3 = new ScriptedUi();
        int trSpawns = spawned.size();
        check(!runStartFlow(uiT3, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", null, null, null, null,
                null, null, "hyper", "100", null)) && uiT3.failures.size() == 1
                && spawned.size() == trSpawns
                && trFlowBefore.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "bad terrain word refuses pre-write (no write, no spawn)");
        // The asteroids row rides the same ONE write;
        // none restores the exact bytes; a bad word refuses pre-write.
        ScriptedUi uiAs1 = new ScriptedUi();
        check(runStartFlow(uiAs1, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, "400")), "asteroids start (record entry) proceeds");
        List<String> asFlowLines = Files.readAllLines(flowProps, StandardCharsets.UTF_8);
        check("400".equals(asteroidsRowValue(asFlowLines)) && terrainRowValue(asFlowLines) == null
                && uiAs1.infos.get(0).contains(", asteroids 400"),
                "start writes match.asteroids=400 (terrain rows untouched)");
        ScriptedUi uiAs2 = new ScriptedUi();
        check(runStartFlow(uiAs2, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, "none")), "asteroids none start proceeds");
        check(trFlowBefore.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "asteroids none removes the row (props back to their exact bytes)");
        ScriptedUi uiAs3 = new ScriptedUi();
        int asSpawns = spawned.size();
        check(!runStartFlow(uiAs3, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, "lots")) && uiAs3.failures.size() == 1
                && spawned.size() == asSpawns
                && trFlowBefore.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "bad asteroids word refuses pre-write (no write, no spawn)");
        // The roster-DP row rides the same ONE write
        // beside the fortress total it needs; 0 restores the exact bytes; a typed value on AUTO
        // and a field-less Start over a SET row on AUTO both refuse pre-write.
        ScriptedUi uiRd1 = new ScriptedUi();
        check(runStartFlow(uiRd1, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "120")), "roster-DP start (record entry) proceeds");
        List<String> rdFlowLines = Files.readAllLines(flowProps, StandardCharsets.UTF_8);
        check("120".equals(waveRosterFullDpRowValue(rdFlowLines))
                && "400".equals(fortressDpRowValue(rdFlowLines))
                && uiRd1.infos.get(0).contains(", roster wave DP 120"),
                "start writes match.waveRosterFullDp=120 beside match.fortressDp=400");
        String rdSet = Files.readString(flowProps, StandardCharsets.UTF_8);
        ScriptedUi uiRd2 = new ScriptedUi();
        int rdSpawns = spawned.size();
        check(!runStartFlow(uiRd2, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null)) && uiRd2.failures.size() == 1
                && uiRd2.failures.get(0).contains("roster wave DP needs Total wave DP > 0")
                && spawned.size() == rdSpawns
                && rdSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "field-less Start to AUTO over a SET roster row refuses pre-write");
        ScriptedUi uiRd3 = new ScriptedUi();
        check(!runStartFlow(uiRd3, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "0", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "120")) && uiRd3.failures.size() == 1
                && spawned.size() == rdSpawns
                && rdSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "typed roster DP on AUTO refuses pre-write (no write, no spawn)");
        ScriptedUi uiRd4 = new ScriptedUi();
        check(!runStartFlow(uiRd4, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "12.5")) && uiRd4.failures.size() == 1
                && spawned.size() == rdSpawns
                && rdSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "bad roster DP refuses pre-write (no write, no spawn)");
        // The roster-x row rides the same ONE write right
        // after the roster DP it scales; unit restores the roster-only bytes; a typed or a file
        // multiplier beside a roster DP that lands off refuses pre-write; a field-less roster DP
        // reads the file's row as the effective one.
        ScriptedUi uiRm1 = new ScriptedUi();
        check(runStartFlow(uiRm1, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "120", "2")), "roster-x start (record entry) proceeds");
        List<String> rmFlowLines = Files.readAllLines(flowProps, StandardCharsets.UTF_8);
        check("2.0".equals(waveRosterMultiplierRowValue(rmFlowLines))
                && "120".equals(waveRosterFullDpRowValue(rmFlowLines))
                && rmFlowLines.indexOf(WAVE_ROSTER_MULTIPLIER_KEY + "=2.0") - 1
                        == rmFlowLines.indexOf(WAVE_ROSTER_MULTIPLIER_COMMENT)
                && uiRm1.infos.get(0).contains(", roster wave DP 120, roster wave x 2.0"),
                "start writes match.waveRosterMultiplier=2.0 under its marker beside"
                        + " match.waveRosterFullDp=120 and names it (flips if the Start flow drops the"
                        + " field, the marker, or the summary clause)");
        String rmSet = Files.readString(flowProps, StandardCharsets.UTF_8);
        int rmSpawns = spawned.size();
        ScriptedUi uiRm2 = new ScriptedUi();
        check(!runStartFlow(uiRm2, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "0")) && uiRm2.failures.size() == 1
                && uiRm2.failures.get(0).contains("Roster x needs Roster DP above 0")
                && uiRm2.failures.get(0).contains("(the file's " + WAVE_ROSTER_MULTIPLIER_KEY
                        + " row is set)")
                && spawned.size() == rmSpawns
                && rmSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "field-less roster x over a SET row + roster DP 0 refuses pre-write (flips if a"
                        + " shell without the field boots a serve that dies on the file's multiplier)");
        ScriptedUi uiRm3 = new ScriptedUi();
        check(!runStartFlow(uiRm3, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "0", "2")) && uiRm3.failures.size() == 1
                && uiRm3.failures.get(0).contains("Roster x needs Roster DP above 0")
                && spawned.size() == rmSpawns
                && rmSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "typed roster x + typed roster DP 0 refuses pre-write (flips if the typed"
                        + " cross-field check is dropped)");
        ScriptedUi uiRm4 = new ScriptedUi();
        check(!runStartFlow(uiRm4, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "120", "0.05")) && uiRm4.failures.size() == 1
                && spawned.size() == rmSpawns
                && rmSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "out-of-window roster x refuses pre-write (flips if the flow clamps or skips the parse)");
        ScriptedUi uiRm5 = new ScriptedUi();
        check(runStartFlow(uiRm5, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, null, "3"))
                && "3.0".equals(waveRosterMultiplierRowValue(
                        Files.readAllLines(flowProps, StandardCharsets.UTF_8))),
                "typed roster x with NO roster-DP field proceeds on the file's roster row (flips if"
                        + " the effective roster DP ignores the file when the shell has no field)");
        ScriptedUi uiRm6 = new ScriptedUi();
        check(runStartFlow(uiRm6, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "120", "1"))
                && rdSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8))
                && !uiRm6.infos.get(0).contains("roster wave x"),
                "unit roster x removes row + marker (props back to the roster-only bytes; flips if"
                        + " unit leaves a row or the summary names it)");
        ScriptedUi uiRd5 = new ScriptedUi();
        check(runStartFlow(uiRd5, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", null, null, null, null,
                null, null, null, null, null, "0")), "roster-DP 0 start proceeds");
        check(trFlowBefore.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "roster DP 0 + AUTO removes both rows (props back to their exact bytes)");
        // The wave-scale twin of the roster-DP file-row law — a shell
        // with no scale field (null) landing AUTO over a SET scale row refuses pre-write (the
        // serve would die on its own config: the package console's six-life loop);
        // a field-less Start that keeps the total proceeds; a null joiner default leaves the
        // file's match.joinerDefaultVariant row (the web page has no such field — "" dropped it).
        String wsFlat = "1.5,1.5,1.5,1.5,1.5,1.5,1.5";
        ScriptedUi uiWs1 = new ScriptedUi();
        check(runStartFlow(uiWs1, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, "aurora_Balanced", wsFlat,
                null, null, null, null, null, null, null, null)),
                "scaled start (scale + joiner fields) proceeds");
        List<String> wsFlowLines = Files.readAllLines(flowProps, StandardCharsets.UTF_8);
        check(wsFlat.equals(fortressWaveScaleRowValue(wsFlowLines))
                && "400".equals(fortressDpRowValue(wsFlowLines))
                && "aurora_Balanced".equals(joinerDefaultRowValue(wsFlowLines)),
                "start writes the scale + joiner rows beside match.fortressDp=400");
        String wsSet = Files.readString(flowProps, StandardCharsets.UTF_8);
        ScriptedUi uiWs2 = new ScriptedUi();
        int wsSpawns = spawned.size();
        check(!runStartFlow(uiWs2, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, null, null, null, null, null,
                null, null, null, null, null)) && uiWs2.failures.size() == 1
                && uiWs2.failures.get(0).contains("wave scale needs Total wave DP > 0")
                && uiWs2.failures.get(0).contains("(the file's " + FORTRESS_WAVE_SCALE_KEY
                        + " row is set)")
                && spawned.size() == wsSpawns
                && wsSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "field-less Start to AUTO over a SET scale row refuses pre-write (no write, no spawn)");
        ScriptedUi uiWs3 = new ScriptedUi();
        check(runStartFlow(uiWs3, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "400", "pw-Apass", null, null, null, null, null, null, null,
                null, null, null, null, null))
                && wsSet.equals(Files.readString(flowProps, StandardCharsets.UTF_8))
                && !uiWs3.infos.get(0).contains("joiner default"),
                "field-less Start keeping the total proceeds byte-identical over the SET scale"
                        + " + joiner rows (null joiner = row untouched, not named)");
        ScriptedUi uiWs4 = new ScriptedUi();
        check(runStartFlow(uiWs4, recorder, flowProps, new OpsWindowModel.StartFields(
                "127.0.0.1", "51337", "", "", "pw-Apass", null, null, "", "", null, null, null,
                null, null, null, null, null)), "scale cleared + joiner blank + AUTO proceeds");
        check(trFlowBefore.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "scale clear + joiner blank + AUTO removes the rows (props back to their exact bytes)");

        // The admin credential field — cross-field refusals are PRE-WRITE, the
        // admin sidecar rides beside the match sidecar, blank admin removes it WITHOUT a
        // confirm, a null field (web plane) keeps it.
        Path flowAdmin = Paths.get(flowDescText + ".adminpassword");
        ScriptedUi uiA1 = new ScriptedUi();
        int spawnsBeforeAdmin = spawned.size();
        check(!runStartFlow(uiA1, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                null, null, "", null, "Adm-7-pw-2026")
                && uiA1.failures.size() == 1
                && uiA1.failures.get(0).contains("needs a match password")
                && !Files.exists(flowAdmin) && spawned.size() == spawnsBeforeAdmin,
                "admin without a match password refuses pre-write");
        ScriptedUi uiA2 = new ScriptedUi();
        // The shared value is 12+ so it clears the admin floor and
        // the refusal is the must-differ law (the flow refuses pre-write; the match sidecar stays).
        check(!runStartFlow(uiA2, recorder, flowProps, "127.0.0.1", "51337", "", "",
                "pw-Apass-2026", null, null, "", null, "pw-Apass-2026")
                && uiA2.failures.size() == 1 && uiA2.failures.get(0).contains("must differ")
                && !Files.exists(flowAdmin), "admin equal to the match password refuses pre-write");
        ScriptedUi uiA3 = new ScriptedUi();
        check(runStartFlow(uiA3, recorder, flowProps, "127.0.0.1", "51337", "", "", "pw-Apass",
                null, null, "", null, "Adm-7-pw-2026")
                && adminPasswordSidecarContent("Adm-7-pw-2026").equals(
                        Files.readString(flowAdmin, StandardCharsets.UTF_8))
                && uiA3.confirmsAsked.isEmpty()
                && uiA3.infos.get(0).contains("admin credential armed"),
                "admin start writes the exact admin sidecar bytes beside the kept match sidecar");
        check("# hand-authored\npassword=pw-Apass\n".equals(
                Files.readString(flowSidecar, StandardCharsets.UTF_8)),
                "the match sidecar stays byte-identical across an admin write");
        ScriptedUi uiA4 = new ScriptedUi();
        check(runStartFlow(uiA4, recorder, flowProps, "127.0.0.1", "51337", "", "", "pw-Apass",
                null, null, "", null, null)
                && Files.isRegularFile(flowAdmin)
                && uiA4.infos.get(0).contains("admin credential armed"),
                "a null admin field (web plane) keeps a present admin sidecar");
        ScriptedUi uiA5 = new ScriptedUi();
        check(runStartFlow(uiA5, recorder, flowProps, "127.0.0.1", "51337", "", "", "pw-Apass",
                null, null, "", null, "")
                && !Files.exists(flowAdmin) && uiA5.confirmsAsked.isEmpty()
                && !uiA5.infos.get(0).contains("admin credential"),
                "blank admin removes the admin sidecar with NO confirm (blank = no admin seat)");
        check(runStartFlow(new ScriptedUi(), recorder, flowProps, "127.0.0.1", "51337", "", "",
                "pw-Apass", null, null, "", null, "Adm-7-pw-2026") && Files.isRegularFile(flowAdmin),
                "re-arm the admin sidecar for the token-mode rows below");

        // blank password + present sidecar: REMOVE rides the confirm — refused first
        // (side-effect-free), then confirmed (file deleted)
        ScriptedUi ui6 = new ScriptedUi();
        int spawnsBefore = spawned.size();
        check(!runStartFlow(ui6, recorder, flowProps, "127.0.0.1", "51337", "", "", "", null, null),
                "refused remove-confirm aborts");
        check(ui6.confirmsAsked.equals(List.of(CONFIRM_REMOVE_PASSWORD))
                && Files.isRegularFile(flowSidecar) && Files.isRegularFile(flowAdmin)
                && spawned.size() == spawnsBefore
                && flowPropsBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "refused remove: sidecar + props byte-untouched, no spawn");
        ScriptedUi ui7 = new ScriptedUi();
        ui7.confirmed.add(CONFIRM_REMOVE_PASSWORD);
        check(runStartFlow(ui7, recorder, flowProps, "127.0.0.1", "51337", "", "", "", null, null),
                "confirmed remove proceeds");
        check(!Files.exists(flowSidecar) && !Files.exists(flowAdmin),
                "confirmed remove deletes the sidecar AND the admin sidecar (no"
                        + " admin secret without a match door)");

        // Blank password + NO sidecar = the token-mode Start rides
        // its own confirm — refused first (pre-write, no spawn), then confirmed (spawns)
        ScriptedUi ui7b = new ScriptedUi();
        int spawnsBeforeToken = spawned.size();
        String propsBeforeToken = Files.readString(flowProps, StandardCharsets.UTF_8);
        check(!runStartFlow(ui7b, recorder, flowProps, "127.0.0.1", "51337", "", "", "", null, null),
                "refused token-mode confirm aborts");
        check(ui7b.confirmsAsked.equals(List.of(CONFIRM_TOKEN_MODE_START))
                && ui7b.failures.isEmpty() && spawned.size() == spawnsBeforeToken
                && !Files.exists(flowSidecar)
                && propsBeforeToken.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "refused token-mode: props byte-untouched, no sidecar minted, no spawn");
        ScriptedUi ui7c = new ScriptedUi();
        ui7c.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui7c, recorder, flowProps, "127.0.0.1", "51337", "", "", "", null, null)
                && spawned.size() == spawnsBeforeToken + 1
                && ui7c.infos.get(0).contains("admission token"),
                "confirmed token-mode start proceeds (token admission info)");

        // A blank-password Start that deletes the
        // admin sidecar says so in the confirm the host answers (owner mode, the recording spawn;
        // each leg on its own temp config dir)
        String gr1 = "admin-delete-named-remove-confirm";
        Path g1Dir = rigDir(gr1, "cc-ops-admin-del");
        Path g1Cfg = g1Dir.resolve("cc-server.properties");
        Path g1Side = g1Dir.resolve("m.txt.password");
        Path g1Admin = g1Dir.resolve("m.txt.adminpassword");
        rig(gr1, () -> {
            Files.writeString(g1Cfg, "match.descriptor=m.txt\n", StandardCharsets.UTF_8);
            Files.writeString(g1Side, "password=oldpw1234\n", StandardCharsets.UTF_8);
            Files.writeString(g1Admin, adminPasswordSidecarContent("Adm-7-pw-2026"),
                    StandardCharsets.UTF_8);
        });
        ScriptedUi g1Ui = new ScriptedUi();
        g1Ui.confirmed.add(CONFIRM_REMOVE_PASSWORD);
        boolean g1Ok = named(gr1, () -> runStartFlow(g1Ui, recorder, g1Cfg, "127.0.0.1", "51337",
                "", "", "", null, null));
        String g1Text = g1Ui.confirmMessages.isEmpty() ? "" : g1Ui.confirmMessages.get(0);
        check(g1Ok && g1Ui.confirmsAsked.equals(List.of(CONFIRM_REMOVE_PASSWORD))
                && g1Text.contains("m.txt.adminpassword") && !Files.exists(g1Side)
                && !Files.exists(g1Admin),
                gr1 + ": the REMOVE confirm of a blank-password Start names m.txt.adminpassword"
                        + " and the confirmed Start deletes BOTH sidecars (flips if the admin"
                        + " decision stays after the confirms or the sentence is lost); got ok="
                        + g1Ok + " asked=" + g1Ui.confirmsAsked + " text=" + g1Text.replace('\n', '|'));

        String gr2 = "admin-delete-named-token-confirm";
        Path g2Dir = rigDir(gr2, "cc-ops-admin-tok");
        Path g2Cfg = g2Dir.resolve("cc-server.properties");
        Path g2Admin = g2Dir.resolve("m.txt.adminpassword");
        rig(gr2, () -> {
            Files.writeString(g2Cfg, "match.descriptor=m.txt\n", StandardCharsets.UTF_8);
            Files.writeString(g2Admin, adminPasswordSidecarContent("Adm-7-pw-2026"),
                    StandardCharsets.UTF_8);
        });
        // (i) no .password, the admin file present: the TOKEN-mode confirm (refused) names it
        ScriptedUi g2Ui = new ScriptedUi();
        boolean g2Ok = named(gr2, () -> runStartFlow(g2Ui, recorder, g2Cfg, "127.0.0.1", "51337",
                "", "", "", null, null));
        String g2Text = g2Ui.confirmMessages.isEmpty() ? "" : g2Ui.confirmMessages.get(0);
        boolean g2AdminKept = Files.isRegularFile(g2Admin);
        // (ii) no admin file: neither confirm text carries the sentence (a refused TOKEN confirm
        // here, then a refused REMOVE confirm over a fresh .password)
        rig(gr2, () -> Files.delete(g2Admin));
        ScriptedUi g2UiTok = new ScriptedUi();
        named(gr2, () -> runStartFlow(g2UiTok, recorder, g2Cfg, "127.0.0.1", "51337", "", "",
                "", null, null));
        rig(gr2, () -> Files.writeString(g2Dir.resolve("m.txt.password"), "password=oldpw1234\n",
                StandardCharsets.UTF_8));
        ScriptedUi g2UiRem = new ScriptedUi();
        named(gr2, () -> runStartFlow(g2UiRem, recorder, g2Cfg, "127.0.0.1", "51337", "", "",
                "", null, null));
        String g2TokText = String.join("|", g2UiTok.confirmMessages);
        String g2RemText = String.join("|", g2UiRem.confirmMessages);
        check(!g2Ok && g2Ui.confirmsAsked.equals(List.of(CONFIRM_TOKEN_MODE_START))
                && g2Text.contains("TOKEN mode") && g2Text.contains("m.txt.adminpassword")
                && g2AdminKept && g2UiTok.confirmsAsked.equals(List.of(CONFIRM_TOKEN_MODE_START))
                && g2UiRem.confirmsAsked.equals(List.of(CONFIRM_REMOVE_PASSWORD))
                && !g2TokText.contains("adminpassword") && !g2RemText.contains("adminpassword"),
                gr2 + ": with the admin file present the TOKEN-mode confirm names"
                        + " m.txt.adminpassword (refused = the file stays); with none, neither the TOKEN nor the REMOVE confirm"
                        + " says adminpassword (flips if the sentence prints unconditionally or the"
                        + " admin decision stays after the confirms); got token='"
                        + g2Text.replace('\n', '|') + "' none-token='" + g2TokText.replace('\n', '|')
                        + "' none-remove='" + g2RemText.replace('\n', '|') + "'");

        // switched start: the ONE row rewritten, CRLF separator preserved, shape + kit warning
        Path flowDesc2 = flowDir.resolve("match-flow2.txt");
        Files.writeString(flowDesc2, MatchDescriptorCodec.encode(minted),
                StandardCharsets.UTF_8);
        String flowDesc2Text = flowDesc2.toString().replace('\\', '/');
        ScriptedUi ui8 = new ScriptedUi();
        ui8.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui8, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                flowDesc2Text, info), "switched start proceeds");
        check(("# posture\r\nmatch.descriptor=" + flowDesc2Text + "\r\n" + cdOff + "\r\n").equals(
                Files.readString(flowProps, StandardCharsets.UTF_8)),
                "switched start rewrites the ONE row, CRLF preserved");
        check(ui8.infos.get(0).contains("shape 1v1")
                && ui8.infos.get(0).contains("SWITCHED DESCRIPTOR"),
                "switched info carries shape + kit warning");

        // armed door + switched + missing fingerprint: confirm gathered BEFORE any write
        Files.writeString(flowProps, "match.descriptor=" + flowDescText
                + "\nnet.doorFingerprint=true\n" + cdOff + "\n", StandardCharsets.UTF_8);
        String armedBytes = Files.readString(flowProps, StandardCharsets.UTF_8);
        ScriptedUi ui9 = new ScriptedUi();
        ui9.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(!runStartFlow(ui9, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                flowDesc2Text, info), "armed-door switched start with no fingerprint aborts");
        check(ui9.confirmsAsked.equals(List.of(CONFIRM_TOKEN_MODE_START, CONFIRM_ARMED_DOOR))
                && armedBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "armed-door refusal is PRE-WRITE (props byte-untouched)");
        ScriptedUi ui10 = new ScriptedUi();
        ui10.confirmed.add(CONFIRM_TOKEN_MODE_START);
        ui10.confirmed.add(CONFIRM_ARMED_DOOR);
        check(runStartFlow(ui10, recorder, flowProps, "127.0.0.1", "51337", "", "",
                "", flowDesc2Text, info), "armed-door confirmed proceeds");
        check(("match.descriptor=" + flowDesc2Text + "\nnet.doorFingerprint=true\n" + cdOff + "\n").equals(
                Files.readString(flowProps, StandardCharsets.UTF_8)),
                "confirmed switch lands the row (LF preserved)");

        // The fortress-DP leg of the flow — a positive total lands marker +
        // row beside the tick edit (one write) and speaks in the info line; a follow-up
        // default (blank = 0 = AUTO) start removes it and returns the file to its exact
        // prior bytes (the 0-removes-row identity the AUTO byte-identical law rides on).
        String preDpBytes = Files.readString(flowProps, StandardCharsets.UTF_8);
        ScriptedUi ui11 = new ScriptedUi();
        ui11.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui11, recorder, flowProps, "127.0.0.1", "51337", "", "500", "",
                null, null), "fortress-DP start proceeds");
        check(Files.readString(flowProps, StandardCharsets.UTF_8).equals(
                "match.descriptor=" + flowDesc2Text + "\nnet.doorFingerprint=true\n" + cdOff + "\n"
                        + FORTRESS_DP_COMMENT + "\n" + FORTRESS_DP_KEY + "=500\n"),
                "fortress-DP start appends marker + row (LF preserved)");
        check(ui11.infos.get(0).contains(", fortress DP 500,"),
                "fortress-DP info line names the total");
        int spawnsAfterDpStart = spawned.size();
        ScriptedUi ui12 = new ScriptedUi();
        ui12.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(!runStartFlow(ui12, recorder, flowProps, "127.0.0.1", "51337", "", "5001", "",
                null, null) && ui12.failures.size() == 1
                && spawned.size() == spawnsAfterDpStart
                && Files.readString(flowProps, StandardCharsets.UTF_8).contains(
                        FORTRESS_DP_KEY + "=500"),
                "out-of-range fortress DP refuses pre-write (row untouched, no spawn)");
        ScriptedUi ui13 = new ScriptedUi();
        ui13.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui13, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                null, null), "default start after a fortress-DP start proceeds");
        check(preDpBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "blank fortress DP removes row + marker (file back to prior bytes)");
        check(!ui13.infos.get(0).contains("fortress DP"),
                "AUTO start says nothing about fortress DP");

        // The wave-scale leg of the 12-arg flow — a typed
        // list (whitespace tolerated) lands marker + row right AFTER the fortress-DP row in
        // the same write and is named in the info line; a scale on AUTO (blank DP) refuses
        // PRE-WRITE (row untouched, no spawn); the 11-arg flow (a shell with no scale field)
        // leaves a live row alone; blank scale + blank DP removes both rows and returns the
        // file to its exact prior bytes.
        String flowScale = "1.5,1.5,1.5,1.5,1.5,1.5,1.5";
        ScriptedUi ui14 = new ScriptedUi();
        ui14.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui14, recorder, flowProps, "127.0.0.1", "51337", "", "500", "",
                null, null, "", " 1.5, 1.5,1.5,1.5,1.5,1.5,1.5 "), "wave-scale start proceeds");
        String scaledFlowBytes = "match.descriptor=" + flowDesc2Text
                + "\nnet.doorFingerprint=true\n" + cdOff + "\n"
                + FORTRESS_DP_COMMENT + "\n" + FORTRESS_DP_KEY + "=500\n"
                + FORTRESS_WAVE_SCALE_COMMENT + "\n" + FORTRESS_WAVE_SCALE_KEY + "=" + flowScale
                + "\n";
        check(scaledFlowBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "wave-scale start lands marker + row after the fortress-DP row (whitespace stripped)");
        check(ui14.infos.get(0).contains(", fortress DP 500, wave scale " + flowScale + ","),
                "wave-scale info line names the factors after the total");
        int spawnsAfterScaleStart = spawned.size();
        ScriptedUi ui15 = new ScriptedUi();
        ui15.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(!runStartFlow(ui15, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                null, null, "", flowScale) && ui15.failures.size() == 1
                && spawned.size() == spawnsAfterScaleStart
                && scaledFlowBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "scale on AUTO (blank DP) refuses pre-write (rows untouched, no spawn)");
        ScriptedUi ui16 = new ScriptedUi();
        ui16.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui16, recorder, flowProps, "127.0.0.1", "51337", "", "500", "",
                null, null, "") && scaledFlowBytes.equals(
                        Files.readString(flowProps, StandardCharsets.UTF_8)),
                "11-arg flow (no scale field) leaves the live scale row byte-identical");
        check(ui16.infos.get(0).contains(", fortress DP 500, admission"),
                "11-arg flow says nothing about wave scale");
        ScriptedUi ui17 = new ScriptedUi();
        ui17.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui17, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                null, null, "", ""), "blank scale + AUTO start proceeds");
        check(preDpBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "blank scale + blank DP removes both rows + markers (file back to prior bytes)");
        // The mods panel's two lists through the 17-arg
        // flow - an id in BOTH refuses pre-write (bytes untouched, no spawn); a lawful pair lands
        // both rows in the ONE write; blank + blank removes both (file back to prior bytes).
        int spawnsBeforeMods = spawned.size();
        ScriptedUi ui18 = new ScriptedUi();
        ui18.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(!runStartFlow(ui18, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                null, null, "", null, null, null, null, "alpha,beta", "beta")
                && ui18.failures.size() == 1 && ui18.failures.get(0).contains("never both")
                && spawned.size() == spawnsBeforeMods
                && preDpBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "a mod both required and optional refuses pre-write (bytes untouched, no spawn)");
        ScriptedUi ui19 = new ScriptedUi();
        ui19.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui19, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                null, null, "", null, null, null, null, "alpha", " beta ; gamma"),
                "a lawful required + optional pair starts");
        List<String> modsLanded = Files.readAllLines(flowProps, StandardCharsets.UTF_8);
        check(modsLanded.contains(MODS_ENABLED_KEY + "=alpha")
                && modsLanded.contains(MODS_OPTIONAL_KEY + "=beta,gamma")
                && modsLanded.contains(MODS_OPTIONAL_COMMENT),
                "both mod rows land canonical in the one props write");
        ScriptedUi ui20 = new ScriptedUi();
        ui20.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(!runStartFlow(ui20, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                null, null, "", null, null, null, null, null, "alpha")
                && ui20.failures.size() == 1,
                "a null required list (row untouched) still meets the never-both law against"
                        + " the standing mods.enabled row");
        ScriptedUi ui21 = new ScriptedUi();
        ui21.confirmed.add(CONFIRM_TOKEN_MODE_START);
        check(runStartFlow(ui21, recorder, flowProps, "127.0.0.1", "51337", "", "", "",
                null, null, "", null, null, null, null, "", "")
                && preDpBytes.equals(Files.readString(flowProps, StandardCharsets.UTF_8)),
                "blank required + blank optional removes both rows + markers (prior bytes)");

        // The front-of-flow busy probe (lock / writer /
        // staging) and the Start chain's decision (advance, fake-driven), on the TEMP package root.
        {
            java.util.function.Supplier<ScriptedUi> tokenUi = () -> {
                ScriptedUi u = new ScriptedUi();
                u.confirmed.add(CONFIRM_TOKEN_MODE_START);
                return u;
            };
            Path pDir = Files.createTempDirectory("cc-ops-probe");
            Path pDesc = pDir.resolve("match-probe.txt");
            Files.writeString(pDesc, MatchDescriptorCodec.encode(minted), StandardCharsets.UTF_8);
            Path pProps = pDir.resolve("cc-server.properties");
            Files.writeString(pProps, "match.descriptor=" + pDesc.toString().replace('\\', '/')
                    + "\n" + cdOff + "\n", StandardCharsets.UTF_8);
            String pBytes = Files.readString(pProps, StandardCharsets.UTF_8);
            Path pRun = flowPkg.resolve(ServeLauncher.RUN_DIR);
            Path pOps = pRun.resolve(ServeLauncher.OPS_DIR).resolve(ServeLauncher.OPS_STATUS_FILE);
            Files.createDirectories(pOps.getParent());
            List<List<String>> pSpawned = new ArrayList<>();
            SpawnFn pRec = c -> {
                pSpawned.add(c);
                return null;
            };
            try (java.nio.channels.FileChannel held = java.nio.channels.FileChannel.open(
                    pRun.resolve(ServeLauncher.LOCK_FILE), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); java.nio.channels.FileLock lk = held.lock()) {
                ScriptedUi u1 = tokenUi.get();
                check(lk.isValid() && !runStartFlow(u1, pRec, pProps, "127.0.0.1", "51337", "", "",
                        "", null, null) && u1.failures.size() == 1
                        && u1.failures.get(0).startsWith("Start refused: launcher running")
                        && u1.confirmsAsked.isEmpty() && pSpawned.isEmpty()
                        && pBytes.equals(Files.readString(pProps, StandardCharsets.UTF_8)),
                        "probe-lock-held: a held package lock refuses Start before any confirm,"
                                + " write or spawn (flips if serveBusy's lock leg is removed or"
                                + " the probe moves after the writes)");
            }
            for (int i = 1; i <= 2; i++) {
                ScriptedUi u = tokenUi.get();
                check(runStartFlow(u, pRec, pProps, "127.0.0.1", "51337", "", "", "", null, null)
                        && u.failures.isEmpty() && pSpawned.size() == i
                        && ServerOpsLaunch.STAGE_HOLD.get() == null
                        && !ServerOpsLaunch.stageHeldElsewhere(flowPkg),
                        "probe-lock-held: an idle package proceeds, twice, and a recording spawn"
                                + " leaves no stage.lock hold (flips if the probe keeps the lock it"
                                + " won, refuses an unlocked package, or the null-spawn path stops"
                                + " ending the staging hold)");
            }
            // Another CONSOLE's stage.lock hold refuses this one before any
            // write - the test plays the other console (a same-JVM hold reads as held)
            try (java.nio.channels.FileChannel other = java.nio.channels.FileChannel.open(
                    flowPkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); java.nio.channels.FileLock olk = other.lock()) {
                ScriptedUi uo = tokenUi.get();
                check(olk.isValid() && !runStartFlow(uo, pRec, pProps, "127.0.0.1", "51337", "", "",
                        "", null, null) && uo.failures.size() == 1
                        && uo.failures.get(0).startsWith("Start refused: another console is staging")
                        && pSpawned.size() == 2 && !STAGING.get()
                        && pBytes.equals(Files.readString(pProps, StandardCharsets.UTF_8)),
                        "probe-stage-held: a stage.lock held by another console refuses Start before"
                                + " any write or spawn and leaves this JVM's flag clear (flips if"
                                + " serveBusy's stage leg is removed or beginStaging ignores a lost"
                                + " tryLock)");
            }
            long me = ProcessHandle.current().pid();
            OpsPressSelftest.writeSnap(pOps, System.currentTimeMillis(), me, "lobby", 0, 0,
                    List.of(), "off");
            ScriptedUi u2 = tokenUi.get();
            check(!runStartFlow(u2, pRec, pProps, "127.0.0.1", "51337", "", "", "", null, null)
                    && u2.failures.size() == 1
                    && u2.failures.get(0).startsWith("Start refused: serve writing (pid " + me + ")")
                    && pSpawned.size() == 2
                    && pBytes.equals(Files.readString(pProps, StandardCharsets.UTF_8)),
                    "probe-writer-alive: a live writer's raw pid/stamp rows refuse Start (flips if"
                            + " serveBusy's writer leg is removed)");
            OpsPressSelftest.writeSnap(pOps, 0L, me, "lobby", 0, 0, List.of(), "off");
            ScriptedUi u3 = tokenUi.get();
            check(runStartFlow(u3, pRec, pProps, "127.0.0.1", "51337", "", "", "", null, null)
                    && u3.failures.isEmpty() && pSpawned.size() == 3,
                    "probe-writer-alive: stamp_ms=0 (a recycled pid) proceeds (flips if the"
                            + " writerAlive start-instant guard is dropped from the probe)");
            Files.delete(pOps);
            STAGING.set(true);
            try {
                ScriptedUi u4 = tokenUi.get();
                check(!runStartFlow(u4, pRec, pProps, "127.0.0.1", "51337", "", "", "", null, null)
                        && u4.failures.size() == 1
                        && u4.failures.get(0).startsWith("Start refused: a Start is already staging")
                        && pSpawned.size() == 3,
                        "probe-staging: a Start still staging refuses the next (flips if serveBusy's"
                                + " staging leg is removed)");
            } finally {
                STAGING.set(false);
            }
            ScriptedUi u5 = tokenUi.get();
            check(runStartFlow(u5, pRec, pProps, "127.0.0.1", "51337", "", "", "", null, null)
                    && pSpawned.size() == 4 && !STAGING.get(),
                    "probe-staging: cleared = proceeds, and a recording spawn leaves STAGING clear"
                            + " (flips if a null stage Process starts a chain)");

            // the chain's decision: exit 0 + receipt -> the launch vector through the SAME spawn
            Path cfgAbs = pProps.toAbsolutePath().normalize();
            Path chainSnap = Files.createTempDirectory("cc-ops-snap");
            Files.writeString(chainSnap.resolve("build.stamp"), "sha256:0123456789abcdef",
                    StandardCharsets.US_ASCII);
            Path receipt = flowPkg.resolve(STAGE_RECEIPT);
            Files.writeString(receipt, "pkg=" + flowPkg + "\nsnaproot=" + chainSnap + "\nfork=0\n",
                    StandardCharsets.US_ASCII);
            OpsPressSelftest.FakeProcess stage4 = new OpsPressSelftest.FakeProcess();
            stage4.exit = 0;
            OpsPressSelftest.FakeProcess launcher4 = new OpsPressSelftest.FakeProcess();
            launcher4.pid = 987_654_321L;
            LaneLaunch l4 = LaneLaunch.publicServe(1L, stage4);
            List<List<String>> c4 = new ArrayList<>();
            List<String> pub4 = new ArrayList<>();
            STAGING.set(true);
            // The test plays the launcher - it HOLDS run/serve.lock, so the chain's
            // lock wait ends at once (a live FakeProcess never locks anything itself)
            long t4 = System.nanoTime();
            try (java.nio.channels.FileChannel held4 = java.nio.channels.FileChannel.open(
                    pRun.resolve(ServeLauncher.LOCK_FILE), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); java.nio.channels.FileLock lk4 = held4.lock()) {
                ServerOpsLaunch.StartChain.advance(l4, stage4, 0, c -> {
                    c4.add(c);
                    return launcher4;
                }, flowPkg, cfgAbs, pub4::add);
            }
            check(c4.equals(List.of(launchCommand(flowPkg, cfgAbs))) && l4.current().get() == launcher4
                    && l4.process() == stage4 && l4.exitCodeOrNull() == null
                    && "987654321".equals(Files.readString(chainSnap.resolve("owner.pid"),
                            StandardCharsets.US_ASCII))
                    && !STAGING.get() && pub4.equals(List.of("launcher pid 987654321"))
                    && System.nanoTime() - t4 < 5_000_000_000L,
                    "chain-stage-ok: exit 0 + snaproot -> the launch vector spawned, current = the"
                            + " launcher, owner.pid = its pid, STAGING cleared once the lock is"
                            + " seen held, quietly and within 5 s (flips if the swap, the owner"
                            + " stamp or the finally's STAGING.set(false) is lost, or the lock"
                            + " wait ignores a held lock)");
            // The chain keeps STAGING while a live launcher has
            // not taken the lock; it clears when the launcher DIES (said) or the wait runs out (said)
            long waitBefore = ServerOpsLaunch.LAUNCHER_LOCK_WAIT_MS.get();
            try {
                ServerOpsLaunch.LAUNCHER_LOCK_WAIT_MS.set(8_000L);
                OpsPressSelftest.FakeProcess stage7 = new OpsPressSelftest.FakeProcess();
                stage7.exit = 0;
                OpsPressSelftest.FakeProcess launcher7 = new OpsPressSelftest.FakeProcess();
                launcher7.pid = 987_654_322L;
                LaneLaunch l7 = LaneLaunch.publicServe(1L, stage7);
                List<String> pub7 = java.util.Collections.synchronizedList(new ArrayList<>());
                STAGING.set(true);
                Thread chain7 = new Thread(() -> ServerOpsLaunch.StartChain.advance(l7, stage7, 0,
                        c -> launcher7, flowPkg, cfgAbs, pub7::add), "selftest-chain-7");
                chain7.start();
                long until7 = System.nanoTime() + 400_000_000L;
                while (System.nanoTime() < until7) {
                    Thread.sleep(20);
                }
                boolean heldWhileAlive = STAGING.get() && chain7.isAlive()
                        && l7.current().get() == launcher7;
                launcher7.exit = 2;
                chain7.join(6_000L);
                check(heldWhileAlive && !chain7.isAlive() && !STAGING.get() && pub7.size() == 2
                        && pub7.get(0).equals("launcher pid 987654322")
                        && pub7.get(1).startsWith("launcher pid 987654322 exited 2 before taking"
                                + " the package lock"),
                        "chain-lock-wait: STAGING stays set 0.4 s into a live launcher that holds"
                                + " no lock, then clears when it dies, saying so (flips if advance"
                                + " clears STAGING before the lock wait, or a dead launcher is"
                                + " not noticed)");
                ServerOpsLaunch.LAUNCHER_LOCK_WAIT_MS.set(300L);
                OpsPressSelftest.FakeProcess launcher8 = new OpsPressSelftest.FakeProcess();
                launcher8.pid = 987_654_323L;
                LaneLaunch l8 = LaneLaunch.publicServe(1L, stage7);
                List<String> pub8 = java.util.Collections.synchronizedList(new ArrayList<>());
                STAGING.set(true);
                long t8 = System.nanoTime();
                // on a thread with a bounded join: a lost ceiling FAILS instead of hanging
                Thread chain8 = new Thread(() -> ServerOpsLaunch.StartChain.advance(l8, stage7, 0,
                        c -> launcher8, flowPkg, cfgAbs, pub8::add), "selftest-chain-8");
                chain8.start();
                chain8.join(5_000L);
                long took8 = System.nanoTime() - t8;
                check(!chain8.isAlive() && !STAGING.get() && launcher8.isAlive() && took8 >= 300_000_000L
                        && took8 < 5_000_000_000L && pub8.size() == 2
                        && pub8.get(1).startsWith("launcher pid 987654323 has not taken run/serve.lock"
                                + " within 300 ms"),
                        "chain-lock-wait: a live launcher that never locks ends the wait at the"
                                + " ceiling, STAGING cleared, said (flips if the ceiling is lost -"
                                + " a hung launcher would refuse Start until the GUI restarts)");
            } finally {
                ServerOpsLaunch.LAUNCHER_LOCK_WAIT_MS.set(waitBefore);
                STAGING.set(false);
            }
            OpsPressSelftest.FakeProcess stage5 = new OpsPressSelftest.FakeProcess();
            stage5.exit = 1;
            LaneLaunch l5 = LaneLaunch.publicServe(1L, stage5);
            List<List<String>> c5 = new ArrayList<>();
            List<String> pub5 = new ArrayList<>();
            STAGING.set(true);
            ServerOpsLaunch.StartChain.advance(l5, stage5, 1, c -> {
                c5.add(c);
                return new OpsPressSelftest.FakeProcess();
            }, flowPkg, cfgAbs, pub5::add);
            check(c5.isEmpty() && Integer.valueOf(1).equals(l5.exitCodeOrNull()) && !STAGING.get()
                    && pub5.size() == 1 && pub5.get(0).contains("exited 1"),
                    "chain-stage-fail: a stage exit 1 spawns no launcher (a valid receipt beside it),"
                            + " exitCodeOrNull = 1 for the FAILED render, STAGING cleared (flips if"
                            + " the exit gate or the finally is lost)");
            Files.delete(receipt);
            OpsPressSelftest.FakeProcess stage6 = new OpsPressSelftest.FakeProcess();
            stage6.exit = 0;
            LaneLaunch l6 = LaneLaunch.publicServe(1L, stage6);
            List<List<String>> c6 = new ArrayList<>();
            List<String> pub6 = new ArrayList<>();
            SpawnFn rec6 = c -> {
                c6.add(c);
                return new OpsPressSelftest.FakeProcess();
            };
            STAGING.set(true);
            ServerOpsLaunch.StartChain.advance(l6, stage6, 0, rec6, flowPkg, cfgAbs, pub6::add);
            Path notSnap = Files.createTempDirectory("cc-ops-notsnap");
            Files.writeString(receipt, "snaproot=" + notSnap + "\n", StandardCharsets.US_ASCII);
            ServerOpsLaunch.StartChain.advance(l6, stage6, 0, rec6, flowPkg, cfgAbs, pub6::add);
            check(c6.isEmpty() && l6.current().get() == stage6 && !STAGING.get()
                    && pub6.size() == 2 && pub6.stream().allMatch(p -> p.contains("names no run snapshot"))
                    && !Files.exists(notSnap.resolve("owner.pid")),
                    "chain-no-receipt: exit 0 with no stage.txt (or a snaproot without build.stamp)"
                            + " starts no launcher and publishes why (flips if the receipt gate is"
                            + " dropped and the launcher spawns blind)");
            Files.delete(receipt);
        }
        OpsRoots.ROOTS.set(OpsRoots.owner());

        // W10: the debug-WATCH button flow — fixed command golden, the script
        // must exist in-tree, a spawn is recorded + the info line names the hand-over; a spawn
        // failure is a fail dialog with nothing else touched.
        check(List.of("pwsh", "-NoProfile", "-File", WATCH_SCRIPT, "-NoOpsGui", "-Observer").equals(watchCommand()),
                "watch command golden (fixed local script, no user text; -NoOpsGui = D-GUIREUSE)");
        check(Files.isRegularFile(Paths.get(WATCH_SCRIPT)), "the standing-watch recipe script exists");
        int spawnsBeforeWatch = spawned.size();
        ScriptedUi uw1 = new ScriptedUi();
        check(runWatchFlow(uw1, recorder) && spawned.size() == spawnsBeforeWatch + 1
                && watchCommand().equals(spawned.get(spawned.size() - 1))
                && uw1.failures.isEmpty() && uw1.infos.size() == 1
                && uw1.infos.get(0).contains(WATCH_SCRIPT)
                && uw1.infos.get(0).contains("follows the watch's own status file"),
                "watch flow spawns the fixed command once and explains the follow");
        // D-OPSFILE/D-GUIREUSE: window-follows-lane decision.
        {
            LaneLaunch pub = LaneLaunch.publicServe(1_000_000L, null);
            LaneLaunch dbg = LaneLaunch.debugWatch(1_000_000L, null);
            check(pub.opsFile().equals(OpsRoots.get().opsFile())
                    && pub.logPath().equals(OpsRoots.get().startLog().toString())
                    && dbg.opsFile().equals(Paths.get(WATCH_OPS_FILE)) && dbg.logPath().equals(WATCH_LOG)
                    && pub.exitCodeOrNull() == null,
                    "lane launches pin each lane's own status file + launcher log");
            check("generated/ops/ops-start.out.log".equals(START_LOG),
                    "start-log-path: the Start chain's log is the path the operator reads"
                            + " (flips if it drifts back to the pwsh serve's log)");
            check(launchPhase(pub, null, false, 1_000_500L, null) == LaunchPhase.PENDING,
                    "no snapshot yet, parent running = PENDING");
            check(launchPhase(pub, null, false, 1_000_500L, 0) == LaunchPhase.PENDING,
                    "parent exit 0 (the detached watch) is no verdict = PENDING");
            check(launchPhase(pub, null, false, 1_000_500L, 1) == LaunchPhase.FAILED,
                    "parent exit non-zero before a live snapshot = FAILED");
            check(launchPhase(pub, null, false, 1_000_000L + LAUNCH_GRACE_MS + 1, null)
                    == LaunchPhase.TIMED_OUT, "no live snapshot past the grace = TIMED_OUT");
            check(launchPendingText(pub, 1_012_000L).startsWith(PUBLIC_LANE + " launched 12 s ago")
                    && launchPendingText(pub, 1_012_000L).contains(pub.opsFile().toString()),
                    "pending text names the lane, the wait and the file");
            check(launchFailureText(pub, LaunchPhase.TIMED_OUT, List.of()).contains("(empty)")
                    && launchFailureText(pub, LaunchPhase.FAILED, List.of("a", "b")).endsWith("a\nb"),
                    "failure text carries the log tail");
            check(logTail(Paths.get("out/ops/no-such-log-" + System.nanoTime()), 5).isEmpty(),
                    "missing log tails empty");
        }
        ScriptedUi uw2 = new ScriptedUi();
        check(!runWatchFlow(uw2, ignoredCmd -> { throw new IOException("no pwsh"); })
                && uw2.failures.size() == 1 && uw2.infos.isEmpty()
                && spawned.size() == spawnsBeforeWatch + 1,
                "watch spawn failure = fail dialog, no info line");
        // The pressing window's brief rides the chain as -OwnerBriefFile.
        {
            Path fwdDir = Files.createTempDirectory("cc-ops-fwd");
            ScriptedUi uw3 = new ScriptedUi();
            OwnerBrief shown = new OwnerBrief("20260826-000000", "row A\nrow B");
            check(runWatchFlow(uw3, recorder, shown, fwdDir) && uw3.failures.isEmpty(),
                    "watch flow with a brief spawns");
            List<String> fwdCmd = spawned.get(spawned.size() - 1);
            int at = fwdCmd.indexOf("-OwnerBriefFile");
            // Positions DERIVED from the fixed command's length — the
            // `-Observer` flag grew it to 6 and the hard-coded 5/7 here failed the suite.
            int fixedLen = watchCommand().size();
            check(fwdCmd.subList(0, fixedLen).equals(watchCommand()) && at == fixedLen
                    && fwdCmd.size() == fixedLen + 2,
                    "forwarded command = the fixed command + -OwnerBriefFile <path>");
            Path fwd = Paths.get(fwdCmd.get(fixedLen + 1));
            check(fwd.startsWith(fwdDir) && fwd.getFileName().toString().startsWith("forward-")
                    && Files.readString(fwd, StandardCharsets.UTF_8).equals("row A\nrow B\n"),
                    "the forward file under the forward dir carries the brief BODY verbatim");
            ScriptedUi uw4 = new ScriptedUi();
            check(runWatchFlow(uw4, recorder, null, fwdDir)
                    && watchCommand().equals(spawned.get(spawned.size() - 1)),
                    "no brief shown = the plain brief-less command");
            check(watchCommand(null).equals(watchCommand()), "watchCommand(null) = brief-less");
        }

        // uplink parse (garbage-to-zero; the egress helper already reads <=0 as 0)
        check(uplinkOrZero(" 50 ") == 50.0 && uplinkOrZero("abc") == 0.0
                && uplinkOrZero(null) == 0.0 && uplinkOrZero("") == 0.0,
                "uplink parse garbage-to-zero");

        // The web guard predicate (CSRF / DNS-rebinding, fail-closed)
        check(webRequestAllowed("GET", "127.0.0.1:7717", false), "GET localhost:port allowed");
        check(webRequestAllowed("GET", "localhost", false), "GET bare localhost allowed");
        check(webRequestAllowed("GET", "LocalHost:7717", false), "host check case-insensitive");
        check(webRequestAllowed("GET", "[::1]:7717", false), "GET ipv6 loopback allowed");
        check(!webRequestAllowed("GET", "evil.example:7717", false), "foreign host refused");
        check(!webRequestAllowed("GET", "127.0.0.1.evil.example", false),
                "prefix-spoof host refused");
        check(!webRequestAllowed("GET", null, false), "absent Host refused (fail-closed)");
        check(webRequestAllowed("POST", "127.0.0.1:7717", true), "POST with ops header allowed");
        check(!webRequestAllowed("POST", "127.0.0.1:7717", false),
                "POST without the X-CC-Ops header refused (CSRF guard)");
        check(!webRequestAllowed("PUT", "127.0.0.1:7717", true), "other methods refused");

        // json escaping (quote / backslash / newline / control)
        check("a\\\"b\\\\c\\nd\\u0001e".equals(jsonEscape("a\"b\\c\nd" + (char) 1 + "e")),
                "json escaping golden");

        // The ONE status-JSON composer (presentation law can't fork into page JS)
        String runJson = statusJson(snap, null, "out/ops/ops-status.txt", true, false,
                50.0, 1700000000000L, List.of());
        check(runJson.contains("\"headline\":\"RUN\"")
                && runJson.contains("\"stateKey\":\"run\"")
                && runJson.contains("\"detail\":\"" + jsonEscape(runDetailText(snap)) + "\"")
                && runJson.contains(
                        "\"utilTitle\":\"Sim utilization (% of 16.67 ms frame budget):\"")
                && runJson.contains("\"utilText\":\"19.4%\"")
                && runJson.contains("\"workMaxText\":\"16.0 ms (overruns=7)\"")
                && runJson.contains("\"egressText\":\"2.4%\"")
                && runJson.contains("\"commandable\":true")
                && runJson.contains("\"startEnabled\":false")
                // D3: the name cell rides between seat and client.
                // W15 v0: the custom cell closes the row ("" = stock hull).
                // + the customs cell (the held-id list, "" here).
                // + the LAST cidkey cell (this fixture is an
                // old writer with no seat.1.cidkey line, so ""). + the MagicLib pair right after ctrl (no
                // seat.1.magic lines here, so "" twice).
                && runJson.contains("[1,\"Dee\",\"mnp-d1\",\"mudskipper2_cc_Hellbore\","
                        + "\"127.0.0.1:58931\",\"live\",\"75.0 KB/s\",\"580 B/s\","
                        + "\"6.8 MB\",\"52.0 KB\",0,\"\",\"\",\"\",\"\",\"\"]")
                // The customs card texts + flag + the raw custom.* globals.
                && runJson.contains("\"customs\":\"" + jsonEscape(customsSummaryText(snap)) + "\"")
                && runJson.contains("\"customsDetail\":\"" + jsonEscape(customsDetailText(snap)) + "\"")
                && runJson.contains("\"customSubmissions\":" + snap.customSubmissions())
                && runJson.contains("\"customCount\":" + snap.customCount())
                && runJson.contains("\"customCapGlobal\":" + snap.customCapGlobal())
                && runJson.contains("\"customTier\":\"" + snap.customTier() + "\"")
                && runJson.contains("\"customExportFailed\":" + snap.customExportFailed()),
                "run status json goldens");
        String stoppedJson = statusJson(null, null, "out/ops/ops-status.txt", true, false,
                50.0, 0L, List.of());
        check(stoppedJson.contains("\"headline\":\"STOPPED\"")
                && stoppedJson.contains("no ops file at out/ops/ops-status.txt")
                && stoppedJson.contains("\"startEnabled\":true")
                && stoppedJson.contains("\"commandable\":false")
                && stoppedJson.contains("\"killEnabled\":false")
                && stoppedJson.contains("\"seats\":[]"),
                "stopped status json goldens");
        String goneJson = statusJson(snap, null, "x", false, true, 50.0,
                1700000000000L + STALE_THRESHOLD_MS + 1, List.of());
        check(goneJson.contains("\"stateKey\":\"gone\"")
                && goneJson.contains(
                        "\"headline\":\"SERVER GONE (no clean end - killed or crashed)\"")
                && goneJson.contains(jsonEscape(goneDetailText(snap))),
                "gone status json (stale + dead pid, died on this shell's watch)");
        // The lobby-leftover incident shapes — a lobby leftover whose writer is
        // dead must offer Start; one this shell NEVER saw alive reads STOPPED + leftover detail
        // (it never shows the crash headline for a run the operator did not start), one that
        // died on this shell's watch reads GONE; a stale lobby whose writer LIVES stays
        // LISTENING (await blocks it).
        OpsSnapshot lobbySnap = OpsSnapshot.parse(FIXTURE.stream()
                .map(l -> "phase=run".equals(l) ? "phase=lobby" : l).toList());
        String lobbyDeadJson = statusJson(lobbySnap, null, "x", false, false, 50.0,
                1700000000001L + 7 * 60_000L, List.of());
        check(lobbyDeadJson.contains("\"state\":\"STOPPED\"")
                && lobbyDeadJson.contains("\"stateKey\":\"stopped\"")
                && lobbyDeadJson.contains("\"headline\":\"STOPPED\"")
                && lobbyDeadJson.contains("\"startEnabled\":true")
                && lobbyDeadJson.contains("\"killEnabled\":false")
                && lobbyDeadJson.contains("\"commandable\":false")
                && lobbyDeadJson.contains("\"seats\":[]")
                && lobbyDeadJson.contains("\"utilText\":\"-\"")
                && lobbyDeadJson.contains("\"egressText\":\"-\"")
                && lobbyDeadJson.contains(jsonEscape(
                        leftoverDetailText(lobbySnap, 1700000000001L + 7 * 60_000L)))
                && lobbyDeadJson.contains("pid " + lobbySnap.pid()
                        + " not running, stamped 7m ago"),
                "previous-session leftover (dead writer never seen alive) reads STOPPED calmly"
                        + " + offers Start (never the GONE headline)");
        String lobbyGoneJson = statusJson(lobbySnap, null, "x", false, true, 50.0,
                1700000000001L, List.of());
        check(lobbyGoneJson.contains("\"state\":\"GONE\"")
                && lobbyGoneJson.contains("\"startEnabled\":true")
                && lobbyGoneJson.contains("\"killEnabled\":false")
                && lobbyGoneJson.contains(jsonEscape(goneDetailText(lobbySnap))),
                "lobby writer that died on this shell's watch reads GONE (alarm) + offers Start");
        WriterWatch watch = new WriterWatch();
        check(!watch.observe(lobbySnap, false), "fresh shell + dead pid = leftover, not GONE");
        check(!watch.observe(lobbySnap, true), "alive observation never reads died-on-watch");
        check(watch.observe(lobbySnap, false), "seen alive then dead SAME pid = died on watch");
        OpsSnapshot otherPidSnap = OpsSnapshot.parse(FIXTURE.stream()
                .map(l -> l.startsWith("pid=") ? "pid=424242" : l).toList());
        check(otherPidSnap.pid() == 424242L && !watch.observe(otherPidSnap, false),
                "a different dead pid is never this shell's loss");
        check(deriveState("lobby", true, false, false) == ServerState.STOPPED
                && deriveState("lobby", true, false, true) == ServerState.GONE
                && deriveState("run", false, false, false) == ServerState.STOPPED
                && deriveState("run", false, false, true) == ServerState.GONE
                && deriveState("run", true, true, true) == ServerState.STALE
                && deriveState("final", true, false, true) == ServerState.EXITED,
                "deriveState ladder: dead writer = STOPPED unless died-on-watch; final stays EXITED");
        check("0m".equals(agoText(59_000L)) && "7m".equals(agoText(7 * 60_000L + 1))
                && "3h 5m".equals(agoText((3 * 60 + 5) * 60_000L))
                && "2d 1h".equals(agoText((49 * 60 + 30) * 60_000L)) && "0m".equals(agoText(-5)),
                "agoText goldens");
        String lobbyLiveJson = statusJson(lobbySnap, null, "x", true, false, 50.0,
                1700000000000L + STALE_THRESHOLD_MS * 100, List.of());
        check(lobbyLiveJson.contains("\"state\":\"LISTENING\"")
                && lobbyLiveJson.contains("\"startEnabled\":false")
                && lobbyLiveJson.contains("\"killEnabled\":true"),
                "stale lobby with a live writer stays LISTENING");
        String finalJson = statusJson(finalSnap, null, "x", true, false, 50.0,
                1700000000001L, snap.seats());
        check(finalJson.contains("\"headline\":\"MATCH ENDED\"")
                && finalJson.contains("\"utilText\":\"match over\"")
                && finalJson.contains("\"ended\"")
                && finalJson.contains("match totals")
                && finalJson.contains("\"startEnabled\":true")
                && finalJson.contains("\"killEnabled\":false"),
                "final status json (substituted rows read ended, bars read match over)");
        check(statusJson(null, "boom: bad line", "x", true, false, 50.0, 0L, List.of())
                .contains("\"detail\":\"ops file unreadable: boom: bad line\""),
                "unreadable ops file json detail");

        // A custom id/label carrying a double-quote AND a backslash must ride the
        // statusJson seats array ESCAPED — the composer routes every non-integer seat cell through
        // jsonEscape, so a hand-crafted hostile custom string can never break the web page's
        // JSON.parse. finalSnap (empty seats, EXITED) falls back to the substituted last-run rows,
        // so the evil seat lands in the seats array.
        // Fixture: the hostile custom id and its seat row
        String evilCustomId = "cc-v9\"x\\y"; // a double-quote and a backslash
        OpsSnapshot.SeatRow evilCustom = new OpsSnapshot.SeatRow(4, "c4", "127.0.0.1:4",
                "wolf_Assault", true, 0L, 0L, 0L, 0L, 0, "Cid", evilCustomId, "wolf", 0, "Assault");
        String evilJson = statusJson(finalSnap, null, "x", true, false, 50.0,
                1700000000001L, List.of(evilCustom));
        check(evilJson.contains("\"" + jsonEscape(evilCustomId) + "\"")
                && !evilJson.contains("cc-v9\"x"),
                "a custom id with a quote + backslash rides statusJson seats ESCAPED (JSON stays valid)");

        // /api/config composer rides the same selector/prefill law
        String cfgJson = configJson(flowProps, 50.0);
        check(cfgJson.contains("\"tickRate\":\"60\"") && cfgJson.contains("\"options\":[")
                && cfgJson.contains(jsonEscape(flowDesc2Text))
                && cfgJson.contains("\"bindHost\":\"127.0.0.1\""),
                "config json carries tick default + scanned options + bind defaults");
        // The map-size prefill left /api/config:
        // the console is package-only, a package has no map-size control; Swing reads its own prefill.
        check(!cfgJson.contains("\"mapHalf\""),
                "config json carries NO map-size prefill (flips if the owner-only mapHalf key returns)");
        configNoSecretRows();
        configJoinHereRows(flowProps);
        preFleetFoldRows();
        check(cfgJson.contains("\"fortressDp\":\"0\""),
                "config json carries the fortress-DP prefill (row absent = 0 = AUTO)");
        //
        check(cfgJson.contains("\"waveRosterFullDp\":\"0\""),
                "config json carries the roster-DP prefill (row absent = 0 = static)");
        //
        check(cfgJson.contains("\"waveRosterFullDp\":\"0\",\"waveRosterMultiplier\":\"1\",\"joinerDefault\":"),
                "config json carries the roster-x prefill right after roster DP (row absent = 1 = no"
                        + " change; flips if the key is dropped, moved, or defaults to anything but 1)");
        configSeatsAllSkillsRows(cfgJson, flowDesc2Text,
                parseDescriptorInfo(Files.readAllLines(flowDir.resolve("match-flow2.txt"), StandardCharsets.UTF_8))
                        .seatCount());
        configRngPinsRows(cfgJson);
        configMagicSeamRows(cfgJson);
        configPilotShipRows(cfgJson);

        // review wf_0eb7945a: non-finite doubles are not JSON — a foreign/hand-edited ops
        // file carrying util_pct=NaN passes the lenient parse, so the raw block must emit
        // null (the derived TEXT keeps its Swing-parity "NaN%" string) and finite values
        // must stay byte-identical to the pre-fix append.
        check("19.4".equals(jsonNumber(19.4)) && "null".equals(jsonNumber(Double.NaN))
                && "null".equals(jsonNumber(Double.POSITIVE_INFINITY)),
                "jsonNumber finite/non-finite goldens");
        List<String> nanFixture = new ArrayList<>(FIXTURE);
        nanFixture.replaceAll(s -> s.startsWith("util_pct=") ? "util_pct=NaN" : s);
        String nanJson = statusJson(OpsSnapshot.parse(nanFixture), null, "x", true, false, 10.0,
                1700000000001L, List.of());
        check(nanJson.contains("\"utilPct\":null") && nanJson.contains("\"tickPerS\":60.0")
                && nanJson.contains("\"utilText\":\"NaN%\""),
                "NaN util serializes as raw null + Swing-parity NaN% text");

        // The operator brief renders in the ops window instead of its own
        // console host. "No brief to show" is the HIDDEN-panel state, so every not-a-brief
        // shape must come back null — absent file, empty file, header-only file.
        check(parseBrief(null) == null, "brief: absent file = no panel");
        check(parseBrief(List.of()) == null, "brief: empty file = no panel");
        check(parseBrief(List.of("# armed 20260727-170000")) == null,
                "brief: header-only file = no panel");
        check(parseBrief(List.of("", "   ")) == null, "brief: all-blank file = no panel");
        OwnerBrief stamped = parseBrief(List.of(
                "# armed 20260727-170000", "", "Fly Bastion.", "Watch: MIRV handoff rubberband?", ""));
        check(stamped != null && "20260727-170000".equals(stamped.stamp()),
                "brief: header line yields the arming stamp");
        check(stamped != null && stamped.body().equals(
                        "Fly Bastion.\nWatch: MIRV handoff rubberband?"),
                "brief: header + surrounding blank lines stripped, interior newlines kept");
        OwnerBrief unheaded = parseBrief(List.of("hand-written note", "second line"));
        check(unheaded != null && unheaded.stamp() == null
                        && unheaded.body().equals("hand-written note\nsecond line"),
                "brief: an unheadered (hand-written) file still renders, without a stamp");
        check(parseBrief(List.of("# armed", "body")).stamp() == null,
                "brief: blank stamp reads as no stamp, body still shows");
        check(briefTitle(null).equals("Operator brief - read before you fly"),
                "brief title: no stamp = plain heading");
        check(briefTitle(stamped).contains("armed 20260727-170000"),
                "brief title: the stamp names the arming run");
        // Rows: clamped, and counted WRAPPED (a 200-char paragraph at 80 cols is 3 rows, not 1).
        check(briefRows("one line", 80) == BRIEF_MIN_ROWS, "brief rows: short brief clamps up");
        check(briefRows("", 80) == BRIEF_MIN_ROWS, "brief rows: empty body clamps up");
        check(briefRows("a\nb\nc\nd", 80) == 4, "brief rows: newline count when nothing wraps");
        check(briefRows("x".repeat(200), 80) == 3, "brief rows: a long line counts its WRAPPED rows");
        check(briefRows("x".repeat(4000), 80) == 50,
                "brief rows: a 50-row brief shows WHOLE (auto-expand to any amount of text)");
        check(briefRows("x".repeat(8000), 80) == BRIEF_MAX_ROWS,
                "brief rows: only past the sanity stop does the brief scroll (the re-fit is screen-capped anyway)");
        check(briefRows("body", 0) == BRIEF_MIN_ROWS, "brief rows: zero columns cannot divide by zero");
        // Bullet rows: " | " pieces and newlines each become a row; idempotent.
        check("• a\n• b\n• c".equals(briefBullets("a | b\nc")),
                "brief bullets: ' | ' pieces + newlines = one bullet each");
        check("• a\n• b".equals(briefBullets("  a  |  | b | ")),
                "brief bullets: pieces trimmed, empties dropped");
        check("• a\n• b".equals(briefBullets(briefBullets("a | b"))),
                "brief bullets: idempotent on bulleted text");
        check("• a|b".equals(briefBullets("a|b")),
                "brief bullets: a bare pipe is not a separator");
        check(briefBullets(null) == null, "brief bullets: null passes through");
        // A numbered flight list keeps its numbers — no bullet in front.
        check("1. open X → Y appears\n2. click Z → chat says ok".equals(
                briefBullets("1. open X → Y appears\n2. click Z → chat says ok")),
                "brief bullets: numbered flight steps pass through unbulleted");
        check("• 10 ships arrive\n• 3.5 s".equals(briefBullets("10 ships arrive | 3.5 s")),
                "brief bullets: a leading number without '. ' is still bulleted");
        check("12. a → b".equals(briefBullets("12. a → b")), "brief bullets: two-digit step numbers pass through");
        OpsJoinSelfSelftest.run();
        // The counted rows end the chain here, once, after every row ran.
        if (failures > 0) {
            throw new AssertionError("server-ops-gui selftest FAILED: " + failures + " row(s) - see the FAIL lines above");
        }
    }

    /** {@code config-no-secret} — {@code /api/config} says
     * WHETHER a password is saved, never its text. The picked descriptor's
     * sidecar holds {@code hunter2} and an admin sidecar exists: both facts true per option AND at the
     * top level, while the JSON carries neither secret and no {@code prefill} key; the unpicked option
     * (no sidecars) reads false; a sidecar FILE with no {@code password=} row reads hasPassword false.
     * Under a package record {@code advertise} = the package Advertise text; owner mode = {@code ""}. */
    static void configNoSecretRows() throws IOException {
        Path dir = Files.createTempDirectory("cc-ops-config-secret");
        Files.createDirectories(dir.resolve("match"));
        String desc = MatchDescriptorCodec.encode(MatchDescriptors.oneVsOne());
        Files.writeString(dir.resolve("match/a.txt"), desc, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("match/b.txt"), desc, StandardCharsets.UTF_8);
        Path props = dir.resolve("cc-server.properties");
        Files.writeString(props, "match.descriptor=match/a.txt\nserver.bindPort=40000\n",
                StandardCharsets.UTF_8);
        Path pw = dir.resolve("match/a.txt.password");
        Files.writeString(pw, passwordSidecarContent("hunter2"), StandardCharsets.UTF_8);
        String suffix = MatchPasswords.ADMIN_SIDECAR_SUFFIX;
        Files.writeString(dir.resolve("match/a.txt" + suffix), "password=hunter3admin\n",
                StandardCharsets.UTF_8);
        String aFacts = "\"sidecar\":\"match/a.txt.password\",\"adminSidecar\":\"match/a.txt" + suffix
                + "\",\"hasPassword\":true,\"hasAdmin\":true";
        String bFacts = "\"sidecar\":\"match/b.txt.password\",\"adminSidecar\":\"match/b.txt" + suffix
                + "\",\"hasPassword\":false,\"hasAdmin\":false";
        String json = configJson(props, 50.0);
        check(!json.contains("hunter2") && !json.contains("hunter3") && !json.contains("\"prefill\"")
                        && json.contains("\"selectedIndex\":0," + aFacts + ",\"options\":[")
                        && json.contains("," + aFacts + "},{\"path\":\"match/b.txt\"")
                        && json.endsWith("," + bFacts + "}]}")
                        && json.contains("\"advertise\":\"\""),
                "config-no-secret: the saved password is a fact (hasPassword/hasAdmin per option + the"
                        + " selected at the top level), never text, and owner mode advertises \"\" (flips if"
                        + " the clear-text prefill returns, a fact reads the wrong sidecar, or a secret leaks);"
                        + " got " + json.replace("hunter", "HUNTER"));
        Files.writeString(pw, "# a sidecar with no password row\n", StandardCharsets.UTF_8);
        String noRow = configJson(props, 50.0);
        check(noRow.contains("\"selectedIndex\":0,\"sidecar\":\"match/a.txt.password\",\"adminSidecar\":"
                        + "\"match/a.txt" + suffix + "\",\"hasPassword\":false,\"hasAdmin\":true"),
                "config-no-secret: a sidecar FILE without a password= row = hasPassword false (flips if"
                        + " file existence alone counts as a saved password); got " + noRow);
        // A BLANK password= row is no saved
        // password (the package launcher refuses a blank one: ServerOpsLaunch.PACKAGE_PASSWORD_REQUIRED).
        Files.writeString(pw, passwordSidecarContent("  "), StandardCharsets.UTF_8);
        String blankRow = configJson(props, 50.0);
        check(blankRow.contains("\"selectedIndex\":0,\"sidecar\":\"match/a.txt.password\",\"adminSidecar\":"
                        + "\"match/a.txt" + suffix + "\",\"hasPassword\":false,\"hasAdmin\":true"),
                "config-no-secret: a sidecar whose password= row is BLANK = hasPassword false (flips if a"
                        + " non-null row value alone counts as a saved password); got " + blankRow);
        configPrefillRows(props);
        OpsRoots before = OpsRoots.ROOTS.get();
        OpsRoots.ROOTS.set(OpsRoots.packaged(dir));
        try {
            String pkg = configJson(props, 50.0);
            check(pkg.contains("\"bindPort\":\"40000\",\"advertise\":\"" + OpsSetupPrefill.PKG_PUBLIC_IP
                            + ":40000\""),
                    "config-no-secret: a package's advertise = the Swing Advertise text of the bind-port"
                            + " prefill (flips if advertise drifts from OpsSetupPrefill.packageAdvertise);"
                            + " got " + pkg);
            configAdvertiseSavedRows(props, dir);
        } finally {
            OpsRoots.ROOTS.set(before);
        }
    }

    /**
     * Main's pre-fleet folds. {@code kit-stamps}: {@link ServerOpsCore#packageKitStamps}
     * serves all three MANIFEST stamps and resolves {@code kits/<pack.kit>} only for a plain {@code PACK_KIT} name
     * that is a regular file inside {@code kits/}; a missing MANIFEST = NONE. {@code launcher-row-clear}:
     * {@link ServerOpsLaunch#writeOrClearLauncherRow} with a blank field REMOVES every row of the key (every other
     * byte kept), a second blank = no write, a value = the writer's own behaviour, a missing file + blank = no file.
     */
    static void preFleetFoldRows() throws IOException {
        Path scratch = Files.createTempDirectory("cc-prefleet");
        Path pkg = scratch.resolve("pkg");
        Path kits = KitPacker.kitsDir(pkg);
        Files.createDirectories(kits);
        Files.write(kits.resolve("good.zip"), new byte[] {0x50, 0x4B});
        String md5 = "0123456789abcdef0123456789abcdef";
        String fp = "jar=" + md5 + ";data=abc;hs=15";
        Path manifest = pkg.resolve(ServeLauncher.MANIFEST_FILE);
        check(ServerOpsCore.KitStamps.NONE.equals(packageKitStamps(pkg)),
                "kit-stamps: no MANIFEST = NONE (flips if an unreadable package throws or invents a stamp)");
        Files.writeString(manifest, "git.head=test\npack.kit=good.zip\nkit.jar.md5=" + md5 + "\nkit.fingerprint="
                + fp + "\n", StandardCharsets.US_ASCII);
        ServerOpsCore.KitStamps good = packageKitStamps(pkg);
        check(md5.equals(good.jarMd5OrNull()) && fp.equals(good.fingerprintOrNull())
                        && kits.toAbsolutePath().normalize().resolve("good.zip").equals(good.zipOrNull()),
                "kit-stamps: pack.kit=good.zip + both stamp rows -> (md5, fingerprint, kits/good.zip); got " + good);
        for (String bad : List.of("../good.zip", "sub/good.zip", "good.txt", "nope.zip", "")) {
            Files.writeString(manifest, "git.head=test\npack.kit=" + bad + "\nkit.jar.md5=" + md5 + "\n",
                    StandardCharsets.US_ASCII);
            ServerOpsCore.KitStamps s = packageKitStamps(pkg);
            check(s.zipOrNull() == null && s.fingerprintOrNull() == null,
                    "kit-stamps: pack.kit='" + bad + "' resolves NO zip (flips if a non-PACK_KIT name, a path"
                            + " outside kits/ or a missing file reaches the refresh) and no fingerprint row = null;"
                            + " got " + s);
        }
        String row = "launcher-row-clear";
        Path launcher = scratch.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        String key = ServeLauncher.ROW_GAME_ROOT_SECOND;
        check(!ServerOpsLaunch.writeOrClearLauncherRow(launcher, key, "  ") && !Files.exists(launcher),
                row + ": a blank field on a missing file writes nothing and mints no file");
        String kept = "# c\r\ngame.root=E:/a\r\n";
        Files.writeString(launcher, kept + key + " = E:/b \r\n" + key + "=E:/dup\r\nadvertise=x\r\n",
                StandardCharsets.ISO_8859_1);
        boolean cleared = ServerOpsLaunch.writeOrClearLauncherRow(launcher, key, "");
        String after = Files.readString(launcher, StandardCharsets.ISO_8859_1);
        check(cleared && after.equals(kept + "advertise=x\r\n")
                        && !ServeLauncher.readLauncherRows(launcher, System.out).containsKey(key),
                row + ": a blank field REMOVES every " + key + " row (both duplicates), every other byte and the"
                        + " CRLF breaks kept (flips if only the last row goes or the file is re-encoded); got '"
                        + after.replace("\r", "\\r").replace("\n", "\\n") + "'");
        check(!ServerOpsLaunch.writeOrClearLauncherRow(launcher, key, null)
                        && after.equals(Files.readString(launcher, StandardCharsets.ISO_8859_1)),
                row + ": a second blank on a file without the row = no write");
        check(ServerOpsLaunch.writeOrClearLauncherRow(launcher, key, " E:/c ")
                        && "E:/c".equals(ServeLauncher.readLauncherRows(launcher, System.out).get(key)),
                row + ": a non-blank field = writeLauncherRow (the row appended, trimmed)");
    }

    /**
     * {@code launcher-row-timer} - a
     * {@code timer.optOut=off} launcher row (any case, spaced) gives the supervisor the not-attempted applier with
     * no {@code -D} knob set, while an {@code on} row, a commented off row or no row keep the production one;
     * {@code launcher-row-template-seed} - {@link ServerOpsLaunch#writeLauncherRow} on a MISSING file beside a
     * {@value ServerOpsLaunch#LAUNCHER_TEMPLATE_FILE} creates it from the template's bytes and upserts the row in
     * place (a field equal to the template's row still reports the write), and without a template writes the
     * one-row file; {@code launcher-host-rel} - the packed-refusal sentences name a file under the folder by its
     * relative name with the platform's separator.
     */
    private static void launcherBootRows() throws IOException {
        Path dir = Files.createTempDirectory("cc-launcher-boot-rows");
        try {
            String row = "launcher-row-timer";
            if (System.getProperty(TimerOptOut.KNOB_PROPERTY) != null) {
                failures++;
                System.out.println("SETUP-" + row + ": this JVM carries -D" + TimerOptOut.KNOB_PROPERTY
                        + "; the row needs it unset");
            } else {
                Path launcher = dir.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
                Files.writeString(launcher, "game.root=E:/g\r\n timer.optOut = OFF \r\n", StandardCharsets.ISO_8859_1);
                TimerOptOut.Applier off = ServeLauncher.timerApplier(ServeLauncher.readLauncherRows(launcher, System.out));
                int[] rc = {Integer.MIN_VALUE};
                if (off != TimerOptOut.PRODUCTION) {
                    off.apply(null, v -> rc[0] = v);
                }
                check(off != TimerOptOut.PRODUCTION && rc[0] == TimerOptOut.NOT_ATTEMPTED,
                        row + ": a timer.optOut=off row with no -D -> the not-attempted applier (rc "
                                + TimerOptOut.NOT_ATTEMPTED + "); got " + (off == TimerOptOut.PRODUCTION ? "PRODUCTION"
                                : "rc " + rc[0]) + " (flips if the launcher stops reading the row or reads it"
                                + " case-sensitively)");
                Files.writeString(launcher, "game.root=E:/g\r\n# timer.optOut=off\r\ntimer.optOut=on\r\n",
                        StandardCharsets.ISO_8859_1);
                check(ServeLauncher.timerApplier(ServeLauncher.readLauncherRows(launcher, System.out))
                                == TimerOptOut.PRODUCTION && ServeLauncher.timerApplier(java.util.Map.of())
                                == TimerOptOut.PRODUCTION,
                        row + ": an on row beside a commented off row, and no row, keep the production applier"
                                + " (flips if any value or a comment turns the opt-out off)");
            }

            row = "launcher-row-template-seed";
            String tpl = "# launcher rows\r\ngame.root=\r\nheap.max=2g\r\n# timer.optOut=off\r\n";
            Path withT = dir.resolve("with-template");
            Files.createDirectories(withT);
            Path template = withT.resolve(ServerOpsLaunch.LAUNCHER_TEMPLATE_FILE);
            Files.writeString(template, tpl, StandardCharsets.ISO_8859_1);
            Path seeded = withT.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
            boolean wrote = ServerOpsLaunch.writeLauncherRow(seeded, ServeLauncher.ROW_GAME_ROOT, " E:/g ");
            String got = Files.readString(seeded, StandardCharsets.ISO_8859_1);
            check(wrote && got.equals("# launcher rows\r\ngame.root=E:/g\r\nheap.max=2g\r\n# timer.optOut=off\r\n")
                            && tpl.equals(Files.readString(template, StandardCharsets.ISO_8859_1)),
                    row + ": a missing file beside the template = the template's rows with game.root upserted in"
                            + " place, the template untouched (flips if the seed is skipped - a one-row file - or"
                            + " the template is edited); got '" + got.replace("\r", "\\r").replace("\n", "\\n") + "'");
            Files.delete(seeded);
            boolean equalWrote = ServerOpsLaunch.writeLauncherRow(seeded, ServeLauncher.ROW_HEAP_MAX, "2g");
            check(equalWrote && tpl.equals(Files.readString(seeded, StandardCharsets.ISO_8859_1)),
                    row + ": a field equal to the template's row still creates the file and reports the write (flips"
                            + " if the seed's write is reported as no write)");
            Path bare = dir.resolve("no-template");
            Files.createDirectories(bare);
            Path one = bare.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
            boolean oneWrote = ServerOpsLaunch.writeLauncherRow(one, ServeLauncher.ROW_GAME_ROOT, "E:/g");
            String oneText = Files.readString(one, StandardCharsets.ISO_8859_1);
            check(oneWrote && oneText.equals("game.root=E:/g" + System.lineSeparator()),
                    row + ": no template = the one-row file (flips if a seed source is invented); got '"
                            + oneText.replace("\r", "\\r").replace("\n", "\\n") + "'");

            row = "launcher-host-rel";
            Path pkg = dir.resolve("pkg");
            Path desc = pkg.resolve("match").resolve("match.txt");
            check("match\\match.txt".equals(ServeLauncher.hostRel(pkg, desc, true))
                            && "match/match.txt".equals(ServeLauncher.hostRel(pkg, desc, false))
                            && dir.resolve("elsewhere.txt").toAbsolutePath().normalize().toString()
                                    .equals(ServeLauncher.hostRel(pkg, dir.resolve("elsewhere.txt"), true)),
                    row + ": a file under the folder = its relative name with the platform's separator, one outside"
                            + " = its full path (flips if the remedy names a path the host cannot find)");
        } finally {
            deleteTree(dir);
        }
    }

    /** {@code config-joinhere} - {@link ServerOpsCore#joinHere} is
     * true for every Windows {@code os.name} and false for Linux, macOS, null and blank; {@code /api/config}
     * serves it as a JSON boolean (no quotes) directly after {@code advertiseBind}, valued for THIS JVM's
     * {@code os.name} (the page's show / hide of Join from this PC reads it). */
    static void configJoinHereRows(Path props) {
        check(joinHere("Windows 11") && joinHere("Windows Server 2022"),
                "config-joinhere: a Windows os.name can join from this PC (flips if joinHere stops"
                        + " recognising Windows, e.g. an exact-name compare)");
        check(!joinHere("Linux") && !joinHere("Mac OS X") && !joinHere(null) && !joinHere(""),
                "config-joinhere: Linux / macOS / null / blank cannot (flips if joinHere answers true for a"
                        + " non-Windows console, which would show a button whose kit cannot run)");
        String json = configJson(props, 50.0);
        String want = "\"joinHere\":" + joinHere(System.getProperty("os.name")) + ",";
        int bind = json.indexOf("\"advertiseBind\":\"");
        int close = bind < 0 ? -1 : json.indexOf("\",", bind + "\"advertiseBind\":\"".length());
        check(close >= 0 && json.startsWith(want, close + 2),
                "config-joinhere: /api/config carries " + want + " (an unquoted boolean for this JVM's"
                        + " os.name) right after advertiseBind (flips if the member is dropped, quoted, moved"
                        + " or valued for the wrong OS); got " + json);
    }

    /** {@code config-advertise-saved} - under the package
     * record of {@link #configNoSecretRows}, a {@code <dir>/launcher.properties} {@code advertise} row
     * WINS over the placeholder (the Swing form's open-time rule) and a launcher file with no
     * {@code game.root} row serves {@code "gameRoot":""}; a BLANK advertise row falls back to the
     * placeholder. The launcher file is removed after (a failed check ends the selftest). */
    static void configAdvertiseSavedRows(Path props, Path dir) throws IOException {
        Path launcher = dir.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Files.writeString(launcher, "advertise=203.0.113.7:51337\n", StandardCharsets.UTF_8);
        String saved = configJson(props, 50.0);
        Files.writeString(launcher, "advertise=   \n", StandardCharsets.UTF_8);
        String blank = configJson(props, 50.0);
        // The ProtonVPN mode rows - in the mode the bind host
        // prefill is the saved advertise.bind; a typed mode keeps the config pair's host
        String modeRows = " advertise.bind = 10.2.0.9 \nadvertise=203.0.113.5:51337\nadvertise.from= protonvpn \n";
        Files.writeString(launcher, modeRows, StandardCharsets.UTF_8);
        String proton = configJson(props, 50.0);
        Files.writeString(launcher, modeRows.replace("protonvpn", "typed"), StandardCharsets.UTF_8);
        String typed = configJson(props, 50.0);
        // Field: the second game folder row, served right after joinHere
        Files.writeString(launcher, "game.root.second = D:/second-game \n", StandardCharsets.UTF_8);
        String second = configJson(props, 50.0);
        Files.delete(launcher);
        String joinHereMember = "\"joinHere\":" + joinHere(System.getProperty("os.name"));
        check(second.contains(joinHereMember + ",\"gameRootSecond\":\"D:/second-game\",\"uplinkDefault\":")
                        && saved.contains(joinHereMember + ",\"gameRootSecond\":\"\",\"uplinkDefault\":"),
                "config-gameroot-second: /api/config serves the game.root.second row trimmed as gameRootSecond"
                        + " right after joinHere, \"\" without the row (flips if the member is dropped, moved or"
                        + " untrimmed); got " + second);
        String configHost = OpsSetupPrefill.packageBindPrefill(Files.readAllLines(props, StandardCharsets.UTF_8))[0];
        check(proton.contains("\"bindHost\":\"10.2.0.9\"")
                        && proton.contains("\"gameRoot\":\"\",\"advertiseFrom\":\"protonvpn\",\"advertiseBind\":\"10.2.0.9\""),
                "config-advertise-saved: advertise.from=protonvpn opens the bind host on the saved advertise.bind"
                        + " and serves both mode rows trimmed after gameRoot (flips if the prefill rule or the two"
                        + " members drift); got " + proton);
        check(!"10.2.0.9".equals(configHost) && typed.contains("\"bindHost\":\"" + jsonEscape(configHost) + "\"")
                        && typed.contains("\"advertiseFrom\":\"typed\",\"advertiseBind\":\"10.2.0.9\""),
                "config-advertise-saved: advertise.from=typed keeps the config pair's host " + configHost
                        + " (flips if advertise.bind wins outside the ProtonVPN mode); got " + typed);
        check(saved.contains("\"advertise\":\"203.0.113.7:51337\",\"gameRoot\":\"\""),
                "config-advertise-saved: the saved launcher.properties advertise row is served and a file"
                        + " without game.root serves gameRoot \"\" (flips if the saved row stops winning over"
                        + " the placeholder or the gameRoot key drifts); got " + saved);
        check(blank.contains("\"advertise\":\"" + OpsSetupPrefill.PKG_PUBLIC_IP + ":40000\""),
                "config-advertise-saved: a BLANK advertise row yields the placeholder (flips if a blank row"
                        + " stops yielding OpsSetupPrefill.packageAdvertise); got " + blank);
    }

    /** {@code config-prefills} — the nine
     * Start-row prefills ride {@code /api/config} with the file's EXACT values (the console's Start
     * re-sends them, so a missing key would blank the host's row), JSON-escaped, and an absent row
     * still yields its key (the prefill's own default: {@code ""} for the free-text four, the combo
     * default for the five dials), never a missing key. Reuses {@code configNoSecretRows}' tree and
     * restores its properties file after (a failed check ends the selftest, so no finally). */
    static void configPrefillRows(Path props) throws IOException {
        String original = Files.readString(props, StandardCharsets.UTF_8);
        String[] keys = {"joinerDefault", "fortressWaveScale", "waveCategory", "endless", "modsEnabled",
                "modsOptional", "terrain", "terrainLevel", "asteroids"};
        Files.writeString(props, "match.descriptor=match/a.txt\n"
                + "match.joinerDefaultVariant= aurora_Balanced \nmatch.fortressWaveScale=1.5\n"
                + "match.waveCategory=derelict\nmatch.endless=false\nmods.enabled=lw_lazylib\n"
                + "mods.optional=opt\"q\nmatch.terrain=corona\nmatch.terrainLevel=50\n"
                + "match.asteroids=100\nmatch.waveRosterMultiplier= 2.5 \n", StandardCharsets.UTF_8);
        String set = configJson(props, 50.0);
        String[] want = {"aurora_Balanced", "1.5", "derelict", "off", "lw_lazylib", "opt\\\"q", "corona",
                "50", "100"};
        for (int i = 0; i < keys.length; i++) {
            check(set.contains("\"" + keys[i] + "\":\"" + want[i] + "\""),
                    "config-prefills: " + keys[i] + " = the Swing prefill of its row, JSON-escaped ('" + want[i]
                            + "'; flips if the key is dropped, reads the wrong row, or skips the escape); got "
                            + set);
        }
        // The roster multiplier now sits between waveRosterFullDp and the nine.
        check(set.contains("\"waveRosterFullDp\":\"0\",\"waveRosterMultiplier\":\"2.5\",\"joinerDefault\":")
                        && set.contains("\"asteroids\":\"100\",\"bindHost\":"),
                "config-prefills: the file's trimmed roster-x row, then the nine, sit between"
                        + " waveRosterFullDp and bindHost (flips if a member breaks the string chain's"
                        + " quoting or the roster-x prefill ignores its row); got " + set);
        Files.writeString(props, "match.descriptor=match/a.txt\n", StandardCharsets.UTF_8);
        String absent = configJson(props, 50.0);
        String[] dflt = {"", "", "remnant", "on", "", "", "none", "100", "none"};
        for (int i = 0; i < keys.length; i++) {
            check(absent.contains("\"" + keys[i] + "\":\"" + dflt[i] + "\""),
                    "config-prefills: an absent " + keys[i] + " row still yields the key at its prefill default '"
                            + dflt[i] + "' (flips if the key goes missing); got " + absent);
        }
        Files.writeString(props, original, StandardCharsets.UTF_8);
    }

    /** The {@link OpsRoots} record — owner mode
     * = the repo-relative constants made absolute; the selftest seam + a package = the launcher's
     * layout under ONE root; the lane follow and the chain read the LIVE reference. */
    static void rootsRows() throws Exception {
        OpsRoots before = OpsRoots.get();
        check(before.mode() == OpsRoots.Mode.OWNER && before.equals(OpsRoots.owner()),
                "roots-owner-default: the live record is owner mode until a main or a row sets it");
        Path cwd = Paths.get("").toAbsolutePath();
        check(before.pkg().equals(cwd.resolve(OPS_PKG_ROOT).normalize())
                && before.config().equals(cwd.resolve(ServeLauncher.DEFAULT_CONFIG).normalize())
                && before.opsFile().equals(cwd.resolve(PUBLIC_OPS_FILE).normalize())
                && before.pointer().equals(cwd.resolve(PUBLIC_FLIGHT_POINTER).normalize())
                && before.startLog().equals(cwd.resolve(START_LOG).normalize())
                && before.stageLock().equals(before.pkg().resolve(STAGE_LOCK_FILE))
                && before.windowMemory().equals(cwd.resolve(OpsRoots.WINDOW_MEMORY_DEFAULT).normalize())
                && before.modsBase().equals(cwd)
                && START_LOG.equals(before.startLogText()),
                "roots-owner: every owner-mode path = its repo-relative constant made absolute against the"
                        + " cwd, the detail text keeps the relative spelling (flips if a root moves, goes"
                        + " relative, or the dialog texts start naming an absolute owner-mode path)");
        Path temp = Files.createTempDirectory("cc-ops-roots");
        Path run = temp.resolve(ServeLauncher.RUN_DIR);
        OpsRoots seam = OpsRoots.ownerUnder(temp);
        check(seam.mode() == OpsRoots.Mode.OWNER && seam.pkg().equals(temp)
                && seam.config().equals(temp.resolve(ServeLauncher.DEFAULT_CONFIG))
                && seam.opsFile().equals(run.resolve(ServeLauncher.OPS_DIR)
                        .resolve(ServeLauncher.OPS_STATUS_FILE))
                && seam.pointer().equals(run.resolve(ServeLauncher.POINTER_FILE))
                && seam.startLog().equals(temp.resolve(OpsRoots.START_LOG_FILE))
                && seam.stageLock().equals(temp.resolve(STAGE_LOCK_FILE))
                && seam.windowMemory().equals(run.resolve(OpsRoots.WINDOW_MEMORY_FILE))
                && seam.modsBase().equals(temp)
                && START_LOG.equals(seam.startLogText()),
                "roots-seam: ownerUnder puts every path under the temp root, the start log beside run/"
                        + " (flips if a flow row could reach owner mode's live package or log again)");
        OpsRoots pkg = OpsRoots.packaged(temp);
        check(pkg.mode() == OpsRoots.Mode.PACKAGE && pkg.pkg().equals(temp)
                && pkg.config().equals(seam.config()) && pkg.opsFile().equals(seam.opsFile())
                && pkg.pointer().equals(seam.pointer()) && pkg.stageLock().equals(seam.stageLock())
                && pkg.windowMemory().equals(seam.windowMemory()) && pkg.modsBase().equals(temp)
                && pkg.startLog().equals(run.resolve(OpsRoots.START_LOG_FILE))
                && pkg.startLog().toString().equals(pkg.startLogText()),
                "roots-packaged: a package = the seam's layout with the start log under run/ and the"
                        + " detail text naming the absolute file (flips if package mode's log or text"
                        + " drifts back to owner mode's)");
        checkThrows(() -> new OpsRoots(OpsRoots.Mode.OWNER, Paths.get("relative-pkg"), temp, temp,
                temp, temp, temp, temp, temp), "roots-relative-pkg: a relative package root is refused");
        OpsRoots.ROOTS.set(seam);
        try {
            LaneLaunch pub = LaneLaunch.publicServe(1L, null);
            check(pub.opsFile().equals(seam.opsFile())
                    && pub.logPath().equals(seam.startLog().toString())
                    && seam.pointer().equals(pub.flightPointer())
                    && ServerOpsLaunch.pkgRoot().equals(temp)
                    && ServerOpsLaunch.chainLog().equals(seam.startLog()),
                    "roots-live: the lane follow + the chain read the LIVE record (flips if either"
                            + " resolves a constant or a retired override again)");
        } finally {
            OpsRoots.ROOTS.set(OpsRoots.owner());
        }
        check(OpsRoots.get().equals(before), "roots-reset: the owner record is back after the seam");
    }

    /** The package-mode main rows: {@code pkg-refuse-unpacked} +
     * {@code pkg-bind-loopback} land HERE, a method of their own so no other row group
     * shares this region of the file. */
    static void pkgMainsRows() throws Exception {
        OpsRoots before = OpsRoots.get();
        Path root = Files.createTempDirectory("cc-ops-pkg-gate");
        Path manifest = root.resolve(ServeLauncher.MANIFEST_FILE);
        // (1) pkg-refuse-unpacked: the gate's three verdicts (the packer's marker)
        String missing = messageOf(() -> OpsRoots.packagedChecked(root));
        check(missing.startsWith(manifest + " is missing or malformed (no such file"),
                "pkg-refuse-unpacked: a root with no MANIFEST.txt is refused naming the file (flips"
                        + " if the gate stops reading the manifest); got " + missing);
        Files.writeString(manifest, "not a manifest row\n", StandardCharsets.US_ASCII);
        String malformed = messageOf(() -> OpsRoots.packagedChecked(root));
        check(malformed.startsWith(manifest + " is missing or malformed (IllegalArgumentException: "),
                "pkg-refuse-unpacked: a malformed manifest is refused with the reader's reason (flips"
                        + " if a parse error is swallowed into a verdict); got " + malformed);
        // the owner-mode stage's manifest shape: git.head + kit.jar.md5 + file rows, no stamp
        String ownerShape = PackageManifest.GIT_HEAD + "=test\n" + PackageManifest.KIT_JAR_MD5
                + "=0123456789abcdef0123456789abcdef\n" + "ab".repeat(32) + "  cc-server.jar\n";
        Files.writeString(manifest, ownerShape, StandardCharsets.US_ASCII);
        String unstamped = messageOf(() -> OpsRoots.packagedChecked(root));
        Files.writeString(manifest, ownerShape + "pack.stamp=   \n", StandardCharsets.US_ASCII);
        String blankStamp = messageOf(() -> OpsRoots.packagedChecked(root));
        String notPacked = root + " is not a packed server (no pack.stamp row in MANIFEST.txt)";
        check(notPacked.equals(unstamped) && notPacked.equals(blankStamp),
                "pkg-refuse-unpacked: a manifest without a (non-blank) pack.stamp row = not a packed"
                        + " server (flips if the pack.stamp check is dropped or a blank stamp passes;"
                        + " --pkg generated/ops-pkg would then run package mode); got " + unstamped
                        + " / " + blankStamp);
        Files.writeString(manifest, ownerShape + "pack.stamp=20261007-120000\n",
                StandardCharsets.US_ASCII);
        OpsRoots[] gated = {null};
        String gateThrew = messageOf(() -> gated[0] = OpsRoots.packagedChecked(
                root.resolve("sub").resolve("..")));
        check(gated[0] != null && gated[0].mode() == OpsRoots.Mode.PACKAGE
                && gated[0].equals(OpsRoots.packaged(root)) && OpsRoots.get().equals(before),
                "pkg-refuse-unpacked: a stamped manifest = the PACKAGE record of the normalized root,"
                        + " the live record untouched (flips if the gate returns another layout, skips"
                        + " normalizing, or sets ROOTS itself); threw " + gateThrew);
        // (2) pkg-bind-loopback: PackageBind over a FIXTURE address set (never the real NICs)
        Set<String> local = Set.of("192.168.7.20", "10.0.0.5", "0:0:0:0:0:0:0:1", "127.0.0.1");
        // A loopback bind is ACCEPTED as
        // typed on a NON-default port (a same-PC test), even from a set that holds no loopback
        Set<String> noLoopback = Set.of("192.168.7.20", "10.0.0.5");
        for (String lo : List.of("127.0.0.1", "localhost", "LocalHost", "::1", "127.9.9.9",
                "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) {
            ServerOpsLaunch.StartBind[] lb = {null};
            String why = messageOf(() -> lb[0] = PackageBind.resolve(" " + lo + " ", "40002",
                    noLoopback));
            check(why.isEmpty() && lb[0] != null && lo.equals(lb[0].bindHost())
                            && "40002".equals(lb[0].bindPort()) && lb[0].advertise() == null,
                    "pkg-bind-loopback: " + lo + " on port 40002 is a StartBind with that host, no"
                            + " advertise (flips if the loopback refusal returns, a spelling is still"
                            + " refused, or loopback is checked against the local set); got "
                            + (lb[0] == null ? why : lb[0].toString()));
        }
        // A 127-prefixed name is no typed loopback, and a backslash host never reaches the config row.
        String named = messageOf(() -> PackageBind.resolve("127.0.0.1.invalid", "40002", noLoopback));
        String slash = messageOf(() -> PackageBind.resolve("127.0.0.1\\x", "40002", noLoopback));
        check(named.startsWith("bind host 127.0.0.1.invalid is not on any local interface")
                && slash.contains("no backslash"),
                "pkg-bind-loopback-literal: only a dotted-quad 127 literal passes as typed loopback, and a"
                        + " backslash host is refused (flips if any 127.-prefixed text passes as loopback again);"
                        + " got " + named + " / " + slash);
        ServerOpsLaunch.StartBind[] ok = new ServerOpsLaunch.StartBind[3];
        String okThrew = messageOf(() -> {
            ok[0] = PackageBind.resolve("  ", " 51337 ", local);
            ok[1] = PackageBind.resolve("0.0.0.0", "40001", Set.of());
            ok[2] = PackageBind.resolve(" 192.168.7.20 ", "51337", local);
        });
        check(okThrew.isEmpty() && "0.0.0.0".equals(ok[0].bindHost())
                && "51337".equals(ok[0].bindPort()) && ok[0].advertise() == null
                && "0.0.0.0".equals(ok[1].bindHost()) && "40001".equals(ok[1].bindPort())
                && "192.168.7.20".equals(ok[2].bindHost()),
                "pkg-bind-loopback: blank = 0.0.0.0, 0.0.0.0 passes with no local set, a held LAN"
                        + " host passes, all trimmed, no advertise (flips if blank stays blank or"
                        + " the wildcard is checked against the interfaces); threw " + okThrew);
        String foreign = messageOf(() -> PackageBind.resolve("203.0.113.9", "51337", local));
        check(foreign.equals("bind host 203.0.113.9 is not on any local interface; local IPv4:"
                + " 10.0.0.5, 127.0.0.1, 192.168.7.20 - type a host this box holds")
                && !foreign.contains("VPN"),
                "pkg-bind-loopback: a host on no local interface is refused with NO VPN clause"
                        + " (flips if owner mode's resolveStartBind sentence leaks in or a foreign host"
                        + " passes); got " + foreign);
        String bigPort = messageOf(() -> PackageBind.resolve("0.0.0.0", "70000", local));
        String autoPort = messageOf(() -> PackageBind.resolve("", "0", local));
        check(bigPort.contains("1..65535, got 70000") && autoPort.startsWith("bind port 0/auto"),
                "pkg-bind-loopback: port 70000 and 0/auto are refused (flips if validateBind is"
                        + " skipped); got " + bigPort + " / " + autoPort);
        // pkg-bind-prefill: the form's package-mode bind fields read the config rows (last wins)
        String[] rows = OpsSetupPrefill.packageBindPrefill(List.of("server.bindHost=10.1.1.1",
                "server.bindPort=40000", "server.bindHost= 192.168.7.20 "));
        String[] blankRows = OpsSetupPrefill.packageBindPrefill(List.of("server.bindHost=",
                "# server.bindPort=1"));
        check("192.168.7.20".equals(rows[0]) && "40000".equals(rows[1])
                && "0.0.0.0".equals(blankRows[0]) && DEFAULT_BIND_PORT.equals(blankRows[1]),
                "pkg-bind-prefill: host/port prefill from server.bindHost/server.bindPort, blank or"
                        + " absent = 0.0.0.0 / the default port (flips if a package opens on the"
                        + " owner mode's 127.0.0.1 literal or reads another row)");
        // pkg-config-json-prefill: the WEB console's /api/config
        // rides the same law under a PACKAGE record; the owner-mode literal is pinned at the
        // "config json carries ... bind defaults" row above (flowProps, owner record).
        Path pkgProps = root.resolve(ServeLauncher.DEFAULT_CONFIG);
        Files.writeString(pkgProps, "server.bindHost=10.1.1.1\nserver.bindPort=40000\n",
                StandardCharsets.UTF_8);
        OpsRoots.ROOTS.set(OpsRoots.packaged(root));
        try {
            String pkgJson = configJson(pkgProps, 50.0);
            check(pkgJson.contains("\"bindHost\":\"10.1.1.1\",\"bindPort\":\"40000\""),
                    "pkg-config-json-prefill: a package's /api/config carries its config's bind rows"
                            + " (flips if the web console opens on the 127.0.0.1 literal PackageBind"
                            + " refuses); got " + pkgJson);
        } finally {
            OpsRoots.ROOTS.set(before);
        }
    }

    /** The package-mode Start-chain rows: {@code pkg-bind-config} +
     * {@code pkg-chain-stageless} land HERE. */
    static void pkgChainRows() throws Exception {
        // Rows (1)-(4), each on its OWN temp root
        // under its own record (recording spawns only - never a real java, never owner mode's
        // package or log); the record, the lock-wait budget, STAGING and the hook target reset in
        // the finally.
        long me = ProcessHandle.current().pid();
        long waitBefore = LAUNCHER_LOCK_WAIT_MS.get();
        try {
            // (1) the bind pair rides the ONE config write; the box rows + serve.argv untouched
            String r1 = "pkg-bind-config";
            Path p1 = rigDir(r1, "cc-ops-pkg-bind");
            Path cfg1 = p1.resolve(ServeLauncher.DEFAULT_CONFIG);
            Path box1 = p1.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
            byte[] boxBytes = "# box\r\nbind.host=\r\nbind.port=\r\n".getBytes(StandardCharsets.US_ASCII);
            // A package Start needs a match password (row (5)), so the
            // presses here type one beside a descriptor row to hang its sidecar on
            // the debug-watch posture row every Start writes sits in the fixture as the template ships it
            // (start-flow-client-debug pins its append to a row-less file)
            String cdOff = CLIENT_DEBUG_KEY + "=false";
            rig(r1, () -> {
                Files.writeString(cfg1, "# package config\r\nmatch.descriptor=match.txt\r\n"
                        + "server.bindPort=1\r\nnet.x=y\r\n" + cdOff + "\r\n"
                        + " server.bindPort : 2\r\n", StandardCharsets.UTF_8);
                Files.write(box1, boxBytes);
            });
            OpsRoots.ROOTS.set(OpsRoots.packaged(p1));
            List<List<String>> c1 = new ArrayList<>();
            ScriptedUi u1 = new ScriptedUi();
            boolean ok1 = named(r1, () -> runStartFlow(u1, c -> {
                c1.add(c);
                return null;
            }, cfg1, "0.0.0.0", "51337", "", "", "fixture-pw", null, null));
            String cfg1After = named(r1, () -> Files.readString(cfg1, StandardCharsets.UTF_8));
            byte[] box1After = named(r1, () -> Files.readAllBytes(box1));
            check(ok1 && u1.failures.isEmpty() && u1.confirmsAsked.isEmpty()
                    && ("# package config\r\nmatch.descriptor=match.txt\r\nserver.bindPort=51337\r\n"
                            + "net.x=y\r\n" + cdOff + "\r\nserver.bindHost=0.0.0.0\r\n").equals(cfg1After)
                    && java.util.Arrays.equals(boxBytes, box1After)
                    && !Files.exists(p1.resolve(ServeLauncher.DEFAULT_TEMPLATE))
                    && c1.equals(List.of(launchCommand(p1, cfg1.toAbsolutePath().normalize())))
                    && c1.get(0).stream().noneMatch(tok -> tok.toLowerCase(Locale.ROOT)
                            .contains("pwsh"))
                    && !STAGING.get() && STAGE_HOLD.get() == null
                    && u1.infos.size() == 1 && u1.infos.get(0).endsWith(" launching (log: "
                            + p1.resolve(ServeLauncher.RUN_DIR).resolve(OpsRoots.START_LOG_FILE) + ")")
                    && !u1.infos.get(0).contains("staging"),
                    r1 + ": a package-mode press lands server.bindHost=0.0.0.0 + server.bindPort=51337"
                            + " in the ONE config write (the first port row replaced in place, its"
                            + " spaced Properties twin dropped), leaves launcher.properties byte-"
                            + "identical and mints no serve.argv, spawns exactly ONE pwsh-free command"
                            + " = the launcher's, says 'launching' with no stage (flips if the bind"
                            + " pair moves to the box rows or out of the write, a row duplicates, the"
                            + " stage still spawns, or the info text keeps the staging clause)");

            // (2) the chain is stage-less: STAGING held past the press, cleared by the lock wait
            String r2 = "pkg-chain-stageless";
            Path p2 = rigDir(r2, "cc-ops-pkg-chain");
            Path cfg2 = p2.resolve(ServeLauncher.DEFAULT_CONFIG);
            rig(r2, () -> Files.writeString(cfg2, "# package config\nmatch.descriptor=match.txt\n",
                    StandardCharsets.UTF_8));
            OpsRoots.ROOTS.set(OpsRoots.packaged(p2));
            Path log2 = p2.resolve(ServeLauncher.RUN_DIR).resolve(OpsRoots.START_LOG_FILE);
            OpsPressSelftest.FakeProcess launcher2 = new OpsPressSelftest.FakeProcess();
            launcher2.pid = 987_654_330L;
            List<List<String>> c2 = java.util.Collections.synchronizedList(new ArrayList<>());
            LAUNCHER_LOCK_WAIT_MS.set(300L);
            ScriptedUi u2 = new ScriptedUi();
            long t2 = System.nanoTime();
            boolean ok2 = named(r2, () -> runStartFlow(u2, c -> {
                c2.add(c);
                return launcher2;
            }, cfg2, "0.0.0.0", "51337", "", "", "fixture-pw", null, null));
            boolean heldAtReturn = STAGING.get();
            long until2 = System.nanoTime() + 2_000_000_000L;
            while (STAGING.get() && System.nanoTime() < until2) {
                Thread.sleep(20);
            }
            long took2 = System.nanoTime() - t2;
            String said2 = named(r2, () -> Files.isRegularFile(log2)
                    ? Files.readString(log2, StandardCharsets.US_ASCII) : "");
            boolean noOwnerPid = named(r2, () -> {
                try (Stream<Path> walk = Files.walk(p2)) {
                    return walk.noneMatch(p -> "owner.pid".equals(String.valueOf(p.getFileName())));
                }
            });
            check(ok2 && u2.failures.isEmpty() && heldAtReturn && !STAGING.get()
                    && took2 >= 300_000_000L && STAGE_HOLD.get() == null
                    && !stageHeldElsewhere(p2)
                    && c2.equals(List.of(launchCommand(p2, cfg2.toAbsolutePath().normalize())))
                    && said2.contains("ops-start: launcher pid 987654330 (package mode: no stage)")
                    && said2.contains("ops-start: launcher pid 987654330 has not taken"
                            + " run/serve.lock within 300 ms")
                    && !Files.exists(p2.resolve(STAGE_RECEIPT)) && noOwnerPid
                    && startLogRedirect().equals(log2),
                    r2 + ": a package-mode press spawns ONLY the launcher and returns with STAGING"
                            + " still held; a daemon lock wait (300 ms, a live launcher that never"
                            + " locks) clears it within 2 s, releases stage.lock and SAYS the wait in"
                            + " <pkg>/run/ops-start.out.log - no stage.txt, no owner.pid, and the real"
                            + " spawn's redirect is that same run/ log (flips if the wait runs on the"
                            + " pressing thread, its finally loses endStaging, the stage chain runs,"
                            + " or the redirect drifts back to a cwd-relative START_LOG)");
            OpsRoots.ROOTS.set(OpsRoots.ownerUnder(p2));
            check(startLogRedirect().equals(p2.resolve(OpsRoots.START_LOG_FILE)),
                    r2 + ": under the owner seam the real spawn's redirect is the record's start log"
                            + " beside run/ (flips if the redirect stops reading the live record)");
            launcher2.exit = 0;

            // (3) owner mode is untouched: the stage vector, no bind row anywhere in the config
            String r3 = "pkg-owner-untouched";
            Path p3 = rigDir(r3, "cc-ops-pkg-owner");
            Path cfg3 = p3.resolve(ServeLauncher.DEFAULT_CONFIG);
            String cfg3Bytes = "# owner config\r\nnet.x=y\r\n" + cdOff + "\r\n";
            rig(r3, () -> Files.writeString(cfg3, cfg3Bytes, StandardCharsets.UTF_8));
            OpsRoots.ROOTS.set(OpsRoots.ownerUnder(p3));
            List<List<String>> c3 = new ArrayList<>();
            ScriptedUi u3 = new ScriptedUi();
            boolean ok3 = named(r3, () -> runStartFlow(u3, c -> {
                c3.add(c);
                return null;
            }, cfg3, "0.0.0.0", "51337", "", "", "", null, null));
            String cfg3After = named(r3, () -> Files.readString(cfg3, StandardCharsets.UTF_8));
            check(ok3 && u3.failures.isEmpty()
                    && c3.equals(List.of(stageCommand(p3, cfg3.toAbsolutePath().normalize(),
                            "0.0.0.0", 51337, me)))
                    && cfg3Bytes.equals(cfg3After) && !STAGING.get()
                    && u3.infos.size() == 1 && u3.infos.get(0).endsWith(
                            " staging the server package, then launching (log: " + START_LOG + ")"),
                    r3 + ": the SAME press under the owner seam spawns today's stage vector, writes"
                            + " no server.bind* row (the config byte-identical) and keeps the staging"
                            + " text (flips if the mode gate leaks package behaviour into owner mode)");

            // (4) the static stage hook: registered ONCE, its target cleared by advance's finally
            String r4 = "pkg-stage-hook";
            Path p4 = rigDir(r4, "cc-ops-pkg-hook");
            Path cfgAbs4 = p4.resolve(ServeLauncher.DEFAULT_CONFIG);
            OpsRoots.ROOTS.set(OpsRoots.ownerUnder(p4));
            boolean targeted = true;
            for (int i = 0; i < 2; i++) {
                OpsPressSelftest.FakeProcess st = new OpsPressSelftest.FakeProcess();
                STAGING.set(true);
                named(r4, () -> {
                    ServerOpsLaunch.StartChain.start(st, c -> null, p4, cfgAbs4);
                    return null;
                });
                targeted &= CURRENT_STAGE.get() == st; // the running stage IS the hook's target
                st.exit = 1;
                long until4 = System.nanoTime() + 5_000_000_000L;
                while (STAGING.get() && System.nanoTime() < until4) {
                    Thread.sleep(20);
                }
            }
            boolean once = targeted && STAGE_HOOKS.get() == 1 && CURRENT_STAGE.get() == null
                    && !STAGING.get();
            OpsPressSelftest.FakeProcess older = new OpsPressSelftest.FakeProcess();
            OpsPressSelftest.FakeProcess newer = new OpsPressSelftest.FakeProcess();
            List<String> pub4 = new ArrayList<>();
            CURRENT_STAGE.set(newer);
            named(r4, () -> {
                ServerOpsLaunch.StartChain.advance(LaneLaunch.publicServe(1L, older), older, 1,
                        c -> null, p4, cfgAbs4, pub4::add);
                return null;
            });
            boolean keepsNewer = CURRENT_STAGE.get() == newer;
            CURRENT_STAGE.set(older);
            named(r4, () -> {
                ServerOpsLaunch.StartChain.advance(LaneLaunch.publicServe(1L, older), older, 1,
                        c -> null, p4, cfgAbs4, pub4::add);
                return null;
            });
            boolean clearsOwn = CURRENT_STAGE.get() == null;
            List<String> order = new ArrayList<>();
            OpsPressSelftest.FakeProcess alive = new OpsPressSelftest.FakeProcess();
            alive.tree = List.of(childHandle(order, alive));
            CURRENT_STAGE.set(alive);
            named(r4, () -> {
                reapStage();
                return null;
            });
            boolean reaped = order.equals(List.of("child-while-stage-alive"))
                    && Integer.valueOf(137).equals(alive.exit);
            OpsPressSelftest.FakeProcess treeless = new OpsPressSelftest.FakeProcess();
            CURRENT_STAGE.set(treeless);
            long quiet = CHAIN_QUIET_FAILURES.get();
            named(r4, () -> {
                reapStage();
                return null;
            });
            boolean treelessDies = Integer.valueOf(137).equals(treeless.exit)
                    && CHAIN_QUIET_FAILURES.get() > quiet;
            check(once && keepsNewer && clearsOwn && reaped && treelessDies,
                    r4 + ": two owner-mode chain starts each make their running stage the hook's"
                            + " target, register the exit hook ONCE, and clear the target when"
                            + " advance ends (once=" + once + "); an older"
                            + " chain never clears a newer stage (" + keepsNewer + "), its own is"
                            + " cleared (" + clearsOwn + "); the reaper kills the stage's descendants"
                            + " while the stage still lives, then the stage (" + reaped + "), and a"
                            + " stage with no handle tree still dies, counted (" + treelessDies
                            + ") (flips if the once-guard, the compareAndSet, the children-first"
                            + " order or the stage kill is lost)");

            // (5) pkg-blank-password-kept.
            // The one-password law: a blank match password in
            // package mode resolves to the KEPT one (the standing row's .password) - the press succeeds,
            // reaches neither token-mode confirm and leaves the sidecar byte-identical (KEEP). Leg (5b):
            // with NO standing sidecar (kept "") it is still refused BEFORE the sidecar decision.
            String r5 = "pkg-blank-password-kept";
            Path p5 = rigDir(r5, "cc-ops-pkg-blankpw");
            Path cfg5 = p5.resolve(ServeLauncher.DEFAULT_CONFIG);
            Path side5 = p5.resolve("match.txt" + ".password");
            String cfg5Bytes = "# package config\r\nmatch.descriptor=match.txt\r\nserver.bindPort=51337\r\n";
            String side5Bytes = passwordSidecarContent("fixture-pw");
            rig(r5, () -> {
                Files.writeString(cfg5, cfg5Bytes, StandardCharsets.UTF_8);
                Files.writeString(side5, side5Bytes, StandardCharsets.UTF_8);
            });
            OpsRoots.ROOTS.set(OpsRoots.packaged(p5));
            List<List<String>> c5 = new ArrayList<>();
            ScriptedUi u5 = new ScriptedUi();
            u5.confirmed.add(CONFIRM_REMOVE_PASSWORD);
            u5.confirmed.add(CONFIRM_TOKEN_MODE_START);
            boolean ok5 = named(r5, () -> runStartFlow(u5, c -> {
                c5.add(c);
                return null;
            }, cfg5, new OpsWindowModel.StartFields("0.0.0.0", "51337", "", "", "  ", null, null,
                    null, null, null, null, null, null, null, null, null, null)));
            String side5After = named(r5, () -> Files.exists(side5)
                    ? Files.readString(side5, StandardCharsets.UTF_8) : "<deleted>");
            check(ok5 && u5.failures.isEmpty() && u5.confirmsAsked.isEmpty() && c5.size() == 1
                    && side5Bytes.equals(side5After),
                    r5 + ": a package-mode press with a blank (whitespace) password resolves to the"
                            + " kept one: it succeeds, asks no confirm (the pre-said REMOVE / token-mode"
                            + " confirms are never reached), spawns once and leaves the host's .password"
                            + " byte-identical (flips if the kept resolve is lost, the blank is refused"
                            + " again, or the pre-said REMOVE confirm deletes the sidecar); got ok=" + ok5
                            + " failures=" + u5.failures + " asked=" + u5.confirmsAsked + " spawns="
                            + c5.size() + " sidecar=" + side5After.replace('\n', '|'));
            // (5b) the refusal leg (where the blank-password refusal still holds): no standing sidecar = kept "",
            // refused naming password_missing before any confirm, write or spawn; staging released
            String r5b = "pkg-blank-password-refused";
            Path p5b = rigDir(r5b, "cc-ops-pkg-blankpw-none");
            Path cfg5b = p5b.resolve(ServeLauncher.DEFAULT_CONFIG);
            Path side5b = p5b.resolve("match.txt" + ".password");
            rig(r5b, () -> {
                Files.writeString(cfg5b, cfg5Bytes, StandardCharsets.UTF_8);
            });
            OpsRoots.ROOTS.set(OpsRoots.packaged(p5b));
            List<List<String>> c5b = new ArrayList<>();
            ScriptedUi u5b = new ScriptedUi();
            u5b.confirmed.add(CONFIRM_REMOVE_PASSWORD);
            u5b.confirmed.add(CONFIRM_TOKEN_MODE_START);
            boolean ok5b = named(r5b, () -> runStartFlow(u5b, c -> {
                c5b.add(c);
                return null;
            }, cfg5b, new OpsWindowModel.StartFields("0.0.0.0", "51337", "", "", "  ", null, null,
                    null, null, null, null, null, null, null, null, null, null)));
            String cfg5bAfter = named(r5b, () -> Files.readString(cfg5b, StandardCharsets.UTF_8));
            check(!ok5b && u5b.failures.size() == 1
                    && u5b.failures.get(0).startsWith("Start refused: A package serve needs a match"
                            + " password")
                    && u5b.failures.get(0).contains("(password_missing)")
                    && u5b.confirmsAsked.isEmpty() && c5b.isEmpty() && cfg5Bytes.equals(cfg5bAfter)
                    && !Files.exists(side5b) && !STAGING.get() && STAGE_HOLD.get() == null,
                    r5b + ": a package-mode press with a blank password and NO standing .password"
                            + " (kept \"\") is refused naming password_missing, asks no confirm, spawns"
                            + " nothing, leaves the config byte-identical, creates no sidecar and"
                            + " releases staging (flips if the guard is lost or a kept \"\" is accepted);"
                            + " got ok=" + ok5b + " failures=" + u5b.failures + " asked="
                            + u5b.confirmsAsked + " spawns=" + c5b.size());
            // (5c) the admin twin of the one-password law: a package KEEP (null admin
            // field) follows the STANDING row's admin - leg 1: standing has none (kept "") and the pick
            // carries a stale b.txt.adminpassword -> the Start CLEARS it (never ARMED behind a standing
            // row with no admin) and writes the kept match password beside the pick; leg 2: the standing
            // row has an admin -> it is written beside the pick.
            String r5c = "pkg-admin-twin-follows-kept";
            Path p5c = rigDir(r5c, "cc-ops-pkg-admintwin");
            Path cfg5c = p5c.resolve(ServeLauncher.DEFAULT_CONFIG);
            Path standing5c = p5c.resolve("match.txt" + ".password");
            Path standingAdmin5c = p5c.resolve("match.txt" + ".adminpassword");
            Path pickSide5c = p5c.resolve("b.txt" + ".password");
            Path pickAdmin5c = p5c.resolve("b.txt" + ".adminpassword");
            rig(r5c, () -> {
                Files.writeString(cfg5c, cfg5Bytes, StandardCharsets.UTF_8);
                Files.writeString(standing5c, side5Bytes, StandardCharsets.UTF_8);
                Files.writeString(pickAdmin5c, adminPasswordSidecarContent("stale-adm-1"),
                        StandardCharsets.UTF_8);
            });
            OpsRoots.ROOTS.set(OpsRoots.packaged(p5c));
            List<List<String>> c5c = new ArrayList<>();
            ScriptedUi u5c = new ScriptedUi();
            u5c.confirmed.add(CONFIRM_ARMED_DOOR);
            OpsWindowModel.StartFields pickB5c = new OpsWindowModel.StartFields("0.0.0.0", "51337", "",
                    "", "  ", "b.txt", new DescriptorInfo("x", "1v1", 1), null, null, null, null, null,
                    null, null, null, null, null);
            boolean ok5c = named(r5c, () -> runStartFlow(u5c, c -> {
                c5c.add(c);
                return null;
            }, cfg5c, pickB5c));
            boolean pickPw5c = named(r5c, () -> Files.isRegularFile(pickSide5c) && "fixture-pw".equals(
                    sidecarPasswordRowValue(Files.readAllLines(pickSide5c, StandardCharsets.UTF_8))));
            boolean staleGone5c = !Files.exists(pickAdmin5c);
            // leg 2: the standing row (match.txt again) now carries an admin; the pick has none
            rig(r5c, () -> {
                Files.writeString(cfg5c, cfg5Bytes, StandardCharsets.UTF_8);
                Files.writeString(standingAdmin5c, adminPasswordSidecarContent("kept-admin-code-2"),
                        StandardCharsets.UTF_8);
            });
            ScriptedUi u5c2 = new ScriptedUi();
            u5c2.confirmed.add(CONFIRM_ARMED_DOOR);
            boolean ok5c2 = named(r5c, () -> runStartFlow(u5c2, c -> {
                c5c.add(c);
                return null;
            }, cfg5c, pickB5c));
            boolean pickAdmin5c2 = named(r5c, () -> Files.isRegularFile(pickAdmin5c) && "kept-admin-code-2".equals(
                    sidecarPasswordRowValue(Files.readAllLines(pickAdmin5c, StandardCharsets.UTF_8))));
            check(ok5c && u5c.failures.isEmpty() && pickPw5c && staleGone5c && ok5c2
                    && u5c2.failures.isEmpty() && pickAdmin5c2 && c5c.size() == 2,
                    r5c + ": a package KEEP (null admin field) follows the standing row's admin - a kept"
                            + " \"\" DELETES the pick's stale .adminpassword while the kept match password"
                            + " is written beside the pick (" + staleGone5c + "/" + pickPw5c + "), a kept"
                            + " admin is written beside the pick (" + pickAdmin5c2 + ") (flips if a kept"
                            + " \"\" maps back to KEEP: the stale admin file survives ARMED); got ok="
                            + ok5c + "/" + ok5c2 + " failures=" + u5c.failures + "/" + u5c2.failures
                            + " spawns=" + c5c.size());

            // (6) the port-0 refusal names no VPN lease
            // in a package (its console has none), the owner sentence keeps it
            String r6 = "pkg-port0-text";
            OpsRoots.ROOTS.set(OpsRoots.packaged(p5));
            String pkgPort0 = messageOf(() -> validateBind("0.0.0.0", "0"));
            OpsRoots.ROOTS.set(OpsRoots.ownerUnder(p5));
            String ownerPort0 = messageOf(() -> validateBind("0.0.0.0", "0"));
            check(pkgPort0.startsWith("bind port 0/auto is refused")
                    && pkgPort0.endsWith(" Type the forwarded/LAN port.")
                    && !pkgPort0.contains("VPN lease")
                    && ownerPort0.startsWith("bind port 0/auto is refused")
                    && ownerPort0.endsWith(" Type the forwarded/LAN port, or Pull VPN lease."),
                    r6 + ": package '" + pkgPort0 + "' / owner '" + ownerPort0 + "' (flips if the"
                            + " package sentence offers the owner-mode lease or the owner-mode one loses it)");

            // (7) a SET launcher.properties bind row
            // (the launcher lets it override the pair the console writes) refuses a package Start
            // before any write or confirm; row (1)'s blank rows stay the "blank passes" guard
            String r7 = "pkg-box-rows-refused";
            Path p7 = rigDir(r7, "cc-ops-pkg-boxrows");
            Path cfg7 = p7.resolve(ServeLauncher.DEFAULT_CONFIG);
            Path box7 = p7.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
            Path side7 = p7.resolve("match.txt" + ".password");
            String cfg7Bytes = "# package config\r\nmatch.descriptor=match.txt\r\n";
            byte[] box7Bytes = "# box\r\nbind.host=\r\nbind.port=51820\r\n"
                    .getBytes(StandardCharsets.US_ASCII);
            rig(r7, () -> {
                Files.writeString(cfg7, cfg7Bytes, StandardCharsets.UTF_8);
                Files.write(box7, box7Bytes);
            });
            OpsRoots.ROOTS.set(OpsRoots.packaged(p7));
            List<List<String>> c7 = new ArrayList<>();
            ScriptedUi u7 = new ScriptedUi();
            boolean ok7 = named(r7, () -> runStartFlow(u7, c -> {
                c7.add(c);
                return null;
            }, cfg7, "0.0.0.0", "51337", "", "", "fixture-pw", null, null));
            String cfg7After = named(r7, () -> Files.readString(cfg7, StandardCharsets.UTF_8));
            byte[] box7After = named(r7, () -> Files.readAllBytes(box7));
            check(!ok7 && u7.failures.size() == 1
                    && u7.failures.get(0).equals("Start refused: " + PACKAGE_BOX_ROWS_SET)
                    && u7.failures.get(0).contains("bind.port=")
                    && u7.confirmsAsked.isEmpty() && c7.isEmpty() && cfg7Bytes.equals(cfg7After)
                    && java.util.Arrays.equals(box7Bytes, box7After) && !Files.exists(side7)
                    && !STAGING.get() && STAGE_HOLD.get() == null,
                    r7 + ": a package press whose launcher.properties sets bind.port=51820 is refused"
                            + " naming the box rows, asks no confirm, spawns nothing, writes neither"
                            + " the config nor the .password and releases staging (flips if the guard"
                            + " is lost, runs after a write, or reads this JVM's cwd instead of the"
                            + " package root); got ok=" + ok7 + " failures=" + u7.failures + " asked="
                            + u7.confirmsAsked + " spawns=" + c7.size() + " sidecar="
                            + Files.exists(side7));
        } finally {
            OpsRoots.ROOTS.set(OpsRoots.owner());
            LAUNCHER_LOCK_WAIT_MS.set(waitBefore);
            CURRENT_STAGE.set(null);
            STAGING.set(false);
        }
    }

    /**
     * The four match-shape rows:
     * {@code pkg-picker-lists-n} (a package's picker = the ONE codec-gated offered set; the
     * owner picker's own listing law), {@code pkg-one-password} (ONE match + admin password per package = the
     * STANDING row's, whatever the pick), {@code owner-password-per-shape} (owner mode keeps the per-pick read) and
     * {@code match-shape-texts} (the host texts are non-blank and fill without a surviving placeholder, through
     * their real fill sites). WHY here: the logic layer these rows read ({@link ServerOpsCore#descriptorOptions},
     * {@link ServerOpsCore#packageKeptPassword},
     * {@link OpsMatchApply#applyPackageMatchSetting}, {@code ServeLauncher.run}) is driven headless on temp
     * package folders only; every Start press uses a recording spawn. The prior roots record and the staging
     * state are restored in the finally.
     *
     */
    static void matchShapeCoreRows() throws Exception {
        OpsRoots rootsBefore = OpsRoots.ROOTS.get();
        try {
            // (1) pkg-picker-lists-n: three decodable shapes, a decodable -tokens twin, two sidecars, a folder named
            // d.txt, a garbage z.txt and (where this box lets us) a symlink e.txt -> a.txt
            String rP = "pkg-picker-lists-n";
            Path pkP = rigDir(rP, "cc-ops-shape-picker");
            Path cfgP = pkP.resolve(ServeLauncher.DEFAULT_CONFIG);
            Path mP = pkP.resolve(OpsMatchApply.MATCH_DIR);
            MatchDescriptor dA = MatchDescriptors.mint("1v1", 6000f);
            MatchDescriptor dB = MatchDescriptors.mint("1v1", 6000f);
            MatchDescriptor dC = MatchDescriptors.mint("1v1", 6000f);
            String pickerPw = "picker-pw-aaaa";
            rig(rP, () -> {
                if (new HashSet<>(List.of(dA.matchId(), dB.matchId(), dC.matchId())).size() != 3
                        || dA.seats().isEmpty()) {
                    throw new IllegalStateException("the minted fixture ids collide or carry no seat");
                }
                Files.createDirectories(mP.resolve("d.txt"));
                Files.writeString(cfgP, "# package config\r\nmatch.descriptor=match/a.txt\r\nserver.bindPort=51337\r\n",
                        StandardCharsets.UTF_8);
                Files.writeString(mP.resolve("a.txt"), MatchDescriptorCodec.encode(dA), StandardCharsets.UTF_8);
                Files.writeString(mP.resolve("b.txt"), MatchDescriptorCodec.encode(dB), StandardCharsets.UTF_8);
                Files.writeString(mP.resolve("c.txt"), MatchDescriptorCodec.encode(dC), StandardCharsets.UTF_8);
                Files.writeString(mP.resolve("a-tokens.txt"), MatchDescriptorCodec.encode(dA), StandardCharsets.UTF_8);
                Files.writeString(mP.resolve("a.txt.fingerprint"), "fingerprint=fixture\n", StandardCharsets.US_ASCII);
                Files.writeString(mP.resolve("a.txt.password"), passwordSidecarContent(pickerPw), StandardCharsets.UTF_8);
                Files.writeString(mP.resolve("z.txt"), "this is not a match file\nhello=world\n", StandardCharsets.UTF_8);
            });
            boolean symlinked = false;
            try {
                Files.createSymbolicLink(mP.resolve("e.txt"), Paths.get("a.txt"));
                symlinked = true;
            } catch (IOException | UnsupportedOperationException | SecurityException noLink) {
                System.out.println("NOTE " + rP + ": symlink leg skipped (" + noLink.getClass().getSimpleName() + ")");
            }
            OpsRoots.ROOTS.set(OpsRoots.packaged(pkP));
            int skipsBefore = PICKER_SKIPS.get();
            DescriptorOptions pkgOpts = named(rP, () -> descriptorOptions(cfgP, descriptorScanDir("match/a.txt"),
                    "match/a.txt"));
            int skipsGrew = PICKER_SKIPS.get() - skipsBefore;
            List<String> pkgPaths = new ArrayList<>();
            List<String> pkgIds = new ArrayList<>();
            for (DescriptorOption o : pkgOpts.options()) {
                pkgPaths.add(o.path());
                pkgIds.add(o.info() == null ? null : o.info().matchId());
            }
            check(pkgPaths.equals(List.of("match/a.txt", "match/b.txt", "match/c.txt"))
                    && pkgIds.equals(List.of(dA.matchId(), dB.matchId(), dC.matchId()))
                    && pkgOpts.selectedIndex() == 0 && skipsGrew == 1,
                    rP + ": a package picker lists EXACTLY the codec-gated offered set a, b, c in row space with the"
                            + " standing a.txt picked, and counts the ONE codec-refused offered file (z.txt) in"
                            + " PICKER_SKIPS (flips if the package picker lists the -tokens twin, the d.txt folder, the"
                            + " e.txt symlink, the garbage z.txt or a sidecar, or a refused / unoffered file is counted"
                            + " otherwise); got paths=" + pkgPaths + " selected=" + pkgOpts.selectedIndex()
                            + " skips+" + skipsGrew + " symlink=" + symlinked);
            // the owner-mode branch: every *.txt under the scan dir its full read decodes (the -tokens twin
            // and a symlink followed included; the folder, the garbage file and the sidecars never)
            OpsRoots.ROOTS.set(OpsRoots.ownerUnder(pkP));
            DescriptorOptions ownOpts = named(rP, () -> descriptorOptions(cfgP, descriptorScanDir("match/a.txt"),
                    "match/a.txt"));
            List<String> ownPaths = new ArrayList<>();
            for (DescriptorOption o : ownOpts.options()) {
                ownPaths.add(o.path());
            }
            List<String> wantOwn = new ArrayList<>(List.of("match/a-tokens.txt", "match/a.txt", "match/b.txt",
                    "match/c.txt"));
            if (symlinked) {
                wantOwn.add("match/e.txt");
            }
            check(ownPaths.equals(wantOwn) && ownOpts.selectedIndex() == 1,
                    rP + ": the OWNER picker on the same folder lists every decodable *.txt its full read reaches"
                            + " (the -tokens twin and a followed symlink included, sorted), never the folder, the"
                            + " garbage file or a sidecar, the standing a.txt picked (flips if the owner picker"
                            + " changes its listing law); got paths=" + ownPaths + " selected="
                            + ownOpts.selectedIndex() + " want=" + wantOwn);

            // (2) pkg-one-password - ONLY what the three pkgChainRows rows do not pin: pkg-blank-password-kept (a
            // blank field resolves to the standing row's kept password, the sidecar left byte-identical),
            // pkg-blank-password-refused (no standing sidecar = kept "" = refused password_missing) and
            // pkg-admin-twin-follows-kept (a KEEP admin follows the standing row: a kept "" clears a stale pick admin,
            // a kept admin is written beside the pick). Here: the resolver read for EVERY pick after a typed rotate,
            // after a typed admin rotate, and on an absent standing sidecar / row / config.
            String rO = "pkg-one-password";
            Path pkO = rigDir(rO, "cc-ops-shape-onepw");
            Path cfgO = pkO.resolve(ServeLauncher.DEFAULT_CONFIG);
            Path mO = pkO.resolve(OpsMatchApply.MATCH_DIR);
            Path cfgAbsent = pkO.resolve("cc-server-absent.properties");
            Path cfgNoRow = pkO.resolve("cc-server-norow.properties");
            Path cfgGone = pkO.resolve("cc-server-gone.properties");
            MatchDescriptor oB = MatchDescriptors.redact(MatchDescriptors.mint("1v1", 6000f));
            MatchDescriptor oC = MatchDescriptors.redact(MatchDescriptors.mint("1v1", 6000f));
            String typedPw = "typed-pw-rot1";
            String typedAdmin = "typed-adm-rot2";
            rig(rO, () -> {
                Files.createDirectories(mO);
                Files.writeString(cfgO, "# package config\r\nmatch.descriptor=match/a.txt\r\nserver.bindPort=51337\r\n",
                        StandardCharsets.UTF_8);
                Files.writeString(mO.resolve("a.txt"), MatchDescriptorCodec.encode(MatchDescriptors.redact(
                        MatchDescriptors.mint("1v1", 6000f))), StandardCharsets.UTF_8);
                Files.writeString(mO.resolve("b.txt"), MatchDescriptorCodec.encode(oB), StandardCharsets.UTF_8);
                Files.writeString(mO.resolve("c.txt"), MatchDescriptorCodec.encode(oC), StandardCharsets.UTF_8);
                Files.writeString(mO.resolve("n.txt"), MatchDescriptorCodec.encode(MatchDescriptors.redact(
                        MatchDescriptors.mint("1v1", 6000f))), StandardCharsets.UTF_8);
                Files.writeString(mO.resolve("a.txt.password"), passwordSidecarContent("kept-pw-aaaa"),
                        StandardCharsets.UTF_8);
                Files.writeString(mO.resolve("c.txt.password"), passwordSidecarContent("own-pw-cccc"),
                        StandardCharsets.UTF_8);
                Files.writeString(mO.resolve("a.txt.adminpassword"), adminPasswordSidecarContent("own-adm-aaaa"),
                        StandardCharsets.UTF_8);
                Files.writeString(mO.resolve("c.txt.adminpassword"), adminPasswordSidecarContent("own-adm-cccc"),
                        StandardCharsets.UTF_8);
                Files.writeString(cfgAbsent, "# package config\r\nmatch.descriptor=match/n.txt\r\n",
                        StandardCharsets.UTF_8);
                Files.writeString(cfgNoRow, "# package config\r\nserver.bindPort=51337\r\n", StandardCharsets.UTF_8);
            });
            OpsRoots.ROOTS.set(OpsRoots.packaged(pkO));
            List<String> picks = List.of("match/a.txt.password", "match/b.txt.password", "match/c.txt.password");
            // (i) a Start that picks B with a TYPED password (the 10-arg surface, admin null = KEEP)
            List<List<String>> cO = new ArrayList<>();
            ScriptedUi uO1 = new ScriptedUi();
            uO1.confirmed.add(CONFIRM_ARMED_DOOR);
            boolean okO1 = named(rO, () -> runStartFlow(uO1, c -> {
                cO.add(c);
                return null;
            }, cfgO, "0.0.0.0", "51337", "", "", typedPw, "match/b.txt",
                    new DescriptorInfo(oB.matchId(), oB.shape(), oB.seats().size())));
            String rowAfter1 = named(rO, () -> descriptorRowValue(Files.readAllLines(cfgO, StandardCharsets.UTF_8)));
            List<Boolean> follow1 = new ArrayList<>();
            for (String pick : picks) {
                follow1.add(typedPw.equals(named(rO, () -> packageKeptPassword(cfgO, pick))));
            }
            check(okO1 && uO1.failures.isEmpty() && cO.size() == 1 && "match/b.txt".equals(rowAfter1)
                    && !follow1.contains(false),
                    rO + " (i): after a package Start that picks B with a typed password the standing row is B and"
                            + " the kept password read for EVERY pick (A's own sidecar, B's, C's own) is the typed one"
                            + " (flips if the resolver reads the pick instead of the standing row); got ok=" + okO1
                            + " failures=" + uO1.failures + " spawns=" + cO.size() + " row=" + rowAfter1
                            + " follows(a,b,c)=" + follow1);
            // (iii) a second Start picks C with a blank match field (= the kept one) and a TYPED admin
            ScriptedUi uO2 = new ScriptedUi();
            uO2.confirmed.add(CONFIRM_ARMED_DOOR);
            OpsWindowModel.StartFields pickC = new OpsWindowModel.StartFields("0.0.0.0", "51337", "", "", "",
                    "match/c.txt", new DescriptorInfo(oC.matchId(), oC.shape(), oC.seats().size()), null, null,
                    typedAdmin, null, null, null, null, null, null, null, null);
            boolean okO2 = named(rO, () -> runStartFlow(uO2, c -> {
                cO.add(c);
                return null;
            }, cfgO, pickC));
            String rowAfter2 = named(rO, () -> descriptorRowValue(Files.readAllLines(cfgO, StandardCharsets.UTF_8)));
            List<Boolean> adminFollow = new ArrayList<>();
            List<Boolean> follow2 = new ArrayList<>();
            for (String pick : picks) {
                adminFollow.add(typedAdmin.equals(named(rO, () -> packageKeptAdminPassword(cfgO, pick))));
                follow2.add(typedPw.equals(named(rO, () -> packageKeptPassword(cfgO, pick))));
            }
            check(okO2 && uO2.failures.isEmpty() && cO.size() == 2 && "match/c.txt".equals(rowAfter2)
                    && !adminFollow.contains(false) && !follow2.contains(false),
                    rO + " (iii): after a Start that picks C with a typed admin password the kept ADMIN read for every"
                            + " pick (A's own .adminpassword, B's carried one, C's) is the typed one, and the ONE match"
                            + " password followed the switch (flips if the admin twin reads the pick, or the kept match"
                            + " password is lost on a second switch); got ok=" + okO2 + " failures=" + uO2.failures
                            + " spawns=" + cO.size() + " row=" + rowAfter2 + " adminFollows(a,b,c)=" + adminFollow
                            + " follows(a,b,c)=" + follow2);
            // (ii) a standing row whose sidecar is ABSENT (n.txt), a config with no descriptor row, no config at all:
            // "" for both reads, never null or a throw - A's own present sidecars must not leak in
            List<String> absent = new ArrayList<>();
            for (Path cfg : List.of(cfgAbsent, cfgNoRow, cfgGone)) {
                absent.add(named(rO, () -> packageKeptPassword(cfg, "match/a.txt.password")));
                absent.add(named(rO, () -> packageKeptAdminPassword(cfg, "match/a.txt.password")));
            }
            boolean allBlank = true;
            List<String> shapes = new ArrayList<>();
            for (String value : absent) {
                allBlank &= "".equals(value);
                shapes.add(value == null ? "null" : value.isEmpty() ? "blank" : "SET");
            }
            check(allBlank && Files.isRegularFile(mO.resolve("a.txt.password")),
                    rO + " (ii): a standing row with no .password, a config with no descriptor row and an absent"
                            + " config each answer \"\" for the kept match AND admin password, even with A's own"
                            + " sidecars present (flips if an absent standing sidecar throws, nulls or falls back to"
                            + " the pick); got (match,admin) x (absent,norow,gone)=" + shapes);

            // (3) owner-password-per-shape: owner mode keeps the per-pick read (the package law never touches it)
            String rW = "owner-password-per-shape";
            Path pkW = rigDir(rW, "cc-ops-shape-ownerpw");
            Path cfgW = pkW.resolve(ServeLauncher.DEFAULT_CONFIG);
            Path mW = pkW.resolve(OpsMatchApply.MATCH_DIR);
            rig(rW, () -> {
                Files.createDirectories(mW);
                Files.writeString(cfgW, "# owner config\r\nmatch.descriptor=match/a.txt\r\n", StandardCharsets.UTF_8);
                for (String stem : List.of("a", "b")) {
                    Files.writeString(mW.resolve(stem + ".txt"), MatchDescriptorCodec.encode(
                            MatchDescriptors.mint("1v1", 6000f)), StandardCharsets.UTF_8);
                    Files.writeString(mW.resolve(stem + ".txt.password"), passwordSidecarContent(
                            "owner-pw-" + stem + stem + stem + stem), StandardCharsets.UTF_8);
                    Files.writeString(mW.resolve(stem + ".txt.adminpassword"), adminPasswordSidecarContent(
                            "owner-adm-" + stem + stem + stem + stem), StandardCharsets.UTF_8);
                }
            });
            OpsRoots.ROOTS.set(OpsRoots.ownerUnder(pkW));
            List<Boolean> perPick = new ArrayList<>();
            for (String stem : List.of("a", "b")) {
                String side = "match/" + stem + ".txt.password";
                perPick.add(("owner-pw-" + stem + stem + stem + stem).equals(
                        named(rW, () -> packageKeptPassword(cfgW, side))));
                perPick.add(("owner-adm-" + stem + stem + stem + stem).equals(
                        named(rW, () -> packageKeptAdminPassword(cfgW, side))));
            }
            check(!perPick.contains(false),
                    rW + ": in OWNER mode the match and admin password reads stay per pick - A's sidecars answer A's,"
                            + " B's answer B's though the standing row is A (flips if owner mode collapses to one"
                            + " password); got (a.match,a.admin,b.match,b.admin)=" + perPick);

            // (4) match-shape-texts: the texts are non-blank; the CHANGED note (+ its UNCHANGED / REPAIRED siblings) filled by the
            // real fill site (OpsMatchApply.applyPackageMatchSetting, every Op) and T5 as the launcher composes it
            String rX = "match-shape-texts";
            Map<String, String> texts = new java.util.LinkedHashMap<>();
            texts.put("T5", ServeLauncher.T5_SIDECAR_MISSING_PACKAGE);
            texts.put("CHANGED", OpsMatchApply.CHANGED);
            texts.put("REPAIRED", OpsMatchApply.REPAIRED);
            List<String> blank = new ArrayList<>();
            for (Map.Entry<String, String> t : texts.entrySet()) {
                if (t.getValue() == null || t.getValue().isBlank()) {
                    blank.add(t.getKey());
                }
            }
            check(blank.isEmpty(), rX + ": every match-shape host text is non-blank (flips if a text is blanked);"
                    + " blank=" + blank);
            Path scratchX = rigDir(rX, "cc-ops-shape-texts-scratch");
            Path pkX = rigDir(rX, "cc-ops-shape-texts");
            Path plain = pkX.resolve(OpsMatchApply.MATCH_DIR).resolve("plain.txt");
            Path fort = pkX.resolve(OpsMatchApply.MATCH_DIR).resolve("fort.txt");
            rig(rX, () -> {
                KitFx fx = stageKitFixture(scratchX, pkX, "kitfx");
                Path minted = Paths.get(fx.descriptor() + ".fingerprint");
                Files.writeString(plain, MatchDescriptorCodec.encode(MatchDescriptors.redact(MatchDescriptors
                        .withTerrain(MatchDescriptors.mint("1v1", 6000f, 0.8f), "open"))), StandardCharsets.UTF_8);
                Files.writeString(fort, MatchDescriptorCodec.encode(MatchDescriptors.redact(MatchDescriptors
                        .withTerrain(MatchDescriptors.mint(MatchDescriptors.FORTRESS_SHAPE, 7000f, 0.6f,
                                MatchDescriptors.stationVariantForFamily("lowtech")), "hyper"))),
                        StandardCharsets.UTF_8);
                Files.copy(minted, Paths.get(plain + ".fingerprint"));
                Files.copy(minted, Paths.get(fort + ".fingerprint"));
            });
            OpsRoots xr = OpsRoots.packaged(pkX);
            Object[][] changes = {
                    {plain, Op.SIZE, "8000", "8000"},
                    {plain, Op.BACKDROP, "hyper", "hyper"},
                    {plain, Op.DEPLOY_CR, "55", "55%"},
                    {fort, Op.STATION, "midline", "midline"}};
            List<String> notes = new ArrayList<>();
            List<String> bad = new ArrayList<>();
            for (Object[] ch : changes) {
                Path file = (Path) ch[0];
                Op op = (Op) ch[1];
                ApplyResult changed = named(rX, () -> applyPackageMatchSetting(xr, file, op, (String) ch[2], false));
                notes.add(changed.note());
                if (!changed.changed() || !changed.note().equals(op.what() + " set to " + ch[3] + ". It applies from"
                        + " the next round, when you press Start; nothing is sent now - every player's next Join or"
                        + " Refresh fetches the new match.")) {
                    bad.add("CHANGED/" + op);
                }
            }
            for (Object[] ch : changes) {
                Path file = (Path) ch[0];
                Op op = (Op) ch[1];
                ApplyResult same = named(rX, () -> applyPackageMatchSetting(xr, file, op, (String) ch[2], false));
                notes.add(same.note());
                if (same.changed() || same.repaired() || !same.note().contains(" is already " + ch[3] + ".")) {
                    bad.add("UNCHANGED/" + op);
                }
            }
            Path plainSide = Paths.get(plain + ".fingerprint");
            rig(rX, () -> Files.write(plainSide, ContentFingerprintMint.rekeySidecarLines(
                    Files.readAllLines(plainSide, StandardCharsets.US_ASCII), "stale-fixture-id"),
                    StandardCharsets.US_ASCII));
            ApplyResult repaired = named(rX, () -> applyPackageMatchSetting(xr, plain, Op.SIZE, "8000", false));
            notes.add(repaired.note());
            if (!repaired.repaired() || repaired.changed() || !repaired.note().startsWith("plain.txt: its two match"
                    + " files did not belong together") || !repaired.note().endsWith(" It applies from the next round, when"
                    + " you press Start; nothing is sent now - every player's next Join or Refresh fetches the new match.")) {
                bad.add("REPAIRED/SIZE");
            }
            java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("<[^<>\\s]{1,40}>");
            for (String note : notes) {
                for (String token : List.of("<what>", "<shape>", "<value>", "<setting>", "<name>")) {
                    if (note.contains(token)) {
                        bad.add("placeholder " + token);
                    }
                }
                if (placeholder.matcher(note).find()) {
                    bad.add("placeholder in a " + note.length() + "-char note");
                }
            }
            check(bad.isEmpty() && notes.size() == 9,
                    rX + ": the Apply notes filled by applyPackageMatchSetting - CHANGED on every Op reads '<what>"
                            + " set to <value>.' + the next-round tail with the value as written (8000, hyper, 55%,"
                            + " midline), UNCHANGED names the baked value, REPAIRED ends in the same tail - and no <what> /"
                            + " <shape> / <value> / <setting> / <name> survives any fill (flips if a placeholder"
                            + " survives the fill or the CHANGED note loses its tail; pinned to OpsMatchApply's"
                            + " wire-era sentences); bad=" + bad + " notes=" + notes.size());
            // T5 as the launcher composes it: a PACKED server (pack.stamp set) whose armed door finds no
            // match/match.txt.fingerprint - the injected Windows run, so java = the fixture's jre/bin/java.exe
            Path pkT = rigDir(rX, "cc-ops-shape-t5");
            rig(rX, () -> {
                Files.createDirectories(pkT.resolve("jre").resolve("bin"));
                Files.writeString(pkT.resolve("jre").resolve("bin").resolve("java.exe"), "fixture\n");
                Files.createDirectories(pkT.resolve("game").resolve("native").resolve("windows"));
                Files.writeString(pkT.resolve("game").resolve(ServeLauncher.CORE_JAR), "fixture\n");
                Files.writeString(pkT.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES),
                        "game.root=game\ngl.driver=hardware\n");
                Files.writeString(pkT.resolve(ServeLauncher.DEFAULT_TEMPLATE),
                        "# fixture template\n${JAVA}\n-cp\n${PKG}/cc-server.jar\nfixture.Main\n");
                Files.writeString(pkT.resolve(ServeLauncher.DEFAULT_CONFIG),
                        "match.descriptor=match/match.txt\nnet.doorFingerprint=true\n");
                Files.createDirectories(pkT.resolve(OpsMatchApply.MATCH_DIR));
                Files.writeString(pkT.resolve(OpsMatchApply.MATCH_DIR).resolve("match.txt"),
                        MatchDescriptorCodec.encode(MatchDescriptors.redact(MatchDescriptors.oneVsOne())));
                Files.writeString(pkT.resolve(OpsMatchApply.MATCH_DIR).resolve("match.txt.password"),
                        passwordSidecarContent("t5-fixture-pw"));
                Files.writeString(pkT.resolve(ServeLauncher.MANIFEST_FILE), "pack.stamp=fixture-t5\n");
            });
            ByteArrayOutputStream t5Bytes = new ByteArrayOutputStream();
            java.io.PrintStream t5Out = new java.io.PrintStream(t5Bytes, true, StandardCharsets.UTF_8);
            int t5Code = named(rX, () -> ServeLauncher.run(new String[] {"--pkg", pkT.toString()}, t5Out, t5Out,
                    true, Map.of()));
            String t5Line = "";
            for (String line : t5Bytes.toString(StandardCharsets.UTF_8).lines().toList()) {
                if (line.startsWith(ServeLauncher.REFUSED_PREFIX)) {
                    t5Line = line;
                }
            }
            check(t5Code == ServeLauncher.EXIT_REFUSED_PRELAUNCH
                    && t5Line.startsWith(ServeLauncher.REFUSED_PREFIX + "the fingerprint file ")
                    && t5Line.endsWith(" does not exist. " + ServeLauncher.T5_SIDECAR_MISSING_PACKAGE)
                    && !placeholder.matcher(t5Line).find() && !t5Line.contains("net.doorFingerprint=false"),
                    rX + ": a packed server's armed door with no fingerprint file refuses with T5 as the launcher"
                            + " composes it (exit " + ServeLauncher.EXIT_REFUSED_PRELAUNCH + ", no <...> placeholder,"
                            + " never owner mode's door-off advice) (flips if the package refusal loses T5, a"
                            + " placeholder survives, or T5 is blanked); got exit=" + t5Code + " refusedLine="
                            + !t5Line.isEmpty() + " t5Tail=" + t5Line.endsWith(ServeLauncher.T5_SIDECAR_MISSING_PACKAGE));
        } finally {
            OpsRoots.ROOTS.set(rootsBefore);
            CURRENT_STAGE.set(null);
            STAGING.set(false);
        }
    }

    /**
     * Row {@code config-seats-allskills} - {@code /api/config}'s Seats per
     * address / All skills prefills and the options' seat counts: over the flow props (neither row) the members read
     * {@code "1"} / {@code "on"}; over a props file holding {@code =3} / {@code =false} (and no match.descriptor row)
     * {@code "3"} / {@code "off"}; every option carries {@code "seats"} right after {@code "shape"}, the scanned
     * {@code match-flow2.txt} option its decoded count, and the null-info no-row option {@code "seats":null}.
     */
    private static void configSeatsAllSkillsRows(String cfgJson, String flow2Path, int flow2Seats) {
        String row = "config-seats-allskills";
        check(cfgJson.contains("\"seatsPerSource\":\"1\"") && cfgJson.contains("\"allSkills\":\"on\""),
                row + ": absent rows serve seatsPerSource '1' / allSkills 'on' (flips if a member is dropped or an"
                        + " absent row stops prefilling its default)");
        java.util.regex.Matcher opt = java.util.regex.Pattern.compile("\\{\"path\":").matcher(cfgJson);
        java.util.regex.Matcher seated = java.util.regex.Pattern.compile(
                "\"shape\":(?:\"[^\"]*\"|null),\"seats\":(?:[0-9]+|null),").matcher(cfgJson);
        int options = 0;
        int seats = 0;
        while (opt.find()) {
            options++;
        }
        while (seated.find()) {
            seats++;
        }
        int at = cfgJson.indexOf("{\"path\":\"" + jsonEscape(flow2Path) + "\"");
        int end = at < 0 ? -1 : cfgJson.indexOf('}', at);
        String flow2 = end < 0 ? "" : cfgJson.substring(at, end);
        check(options > 0 && options == seats && flow2.contains("\"seats\":" + flow2Seats + ","),
                row + ": " + seats + " of " + options + " options carry seats, match-flow2 option '" + flow2
                        + "' (want seats " + flow2Seats + ") - flips if the options lose the seat count the New"
                        + " dialog's Players field opens at, or it is not the decoded count");
        Path dir = rigDir(row, "cc-ops-config-seats");
        Path props = dir.resolve("cc-server.properties");
        rig(row, () -> Files.writeString(props, OpsMatchRows.SEATS_PER_SOURCE_KEY + "=3\n" + OpsMatchRows.ALL_SKILLS_KEY
                + "=false\n", StandardCharsets.UTF_8));
        try {
            String set = named(row, () -> configJson(props, 50.0));
            check(set.contains("\"seatsPerSource\":\"3\"") && set.contains("\"allSkills\":\"off\"")
                            && set.contains("\"shape\":null,\"seats\":null,"),
                    row + ": the =3 / =false rows serve '3' / 'off' and the no-row option seats null (flips if a"
                            + " prefill ignores its row or a null-info option drops / invents a count); got "
                            + set.substring(0, Math.min(400, set.length())));
        } finally {
            rig(row, () -> {
                Files.deleteIfExists(props);
                Files.deleteIfExists(dir);
            });
        }
        System.out.println("  PASS " + row);
    }

    /**
     * Row {@code config-rngpins} - {@code /api/config}'s replay-pins prefill:
     * over the flow props (no {@code rngpin.enabled} row) the member reads {@code "on"} (the compiled default) right
     * after {@code allSkills}; over a props file holding {@code =false} it reads {@code "off"} and over {@code =true}
     * {@code "on"}.
     */
    private static void configRngPinsRows(String cfgJson) {
        String row = "config-rngpins";
        check(cfgJson.contains("\"allSkills\":\"on\",\"rngPins\":\"on\",\"magicSeam\":"),
                row + ": an absent row serves rngPins 'on' right after allSkills (flips if the member is dropped,"
                        + " moved, or an absent row stops reading on)");
        Path dir = rigDir(row, "cc-ops-config-rngpins");
        Path props = dir.resolve("cc-server.properties");
        try {
            rig(row, () -> Files.writeString(props, OpsMatchRows.RNG_PINS_KEY + "=false\n", StandardCharsets.UTF_8));
            String off = named(row, () -> configJson(props, 50.0));
            rig(row, () -> Files.writeString(props, OpsMatchRows.RNG_PINS_KEY + "=true\n", StandardCharsets.UTF_8));
            String on = named(row, () -> configJson(props, 50.0));
            check(off.contains("\"rngPins\":\"off\"") && on.contains("\"rngPins\":\"on\""),
                    row + ": the =false / =true rows serve off / on (flips if the prefill ignores its row or the word"
                            + " map inverts); got " + off.substring(0, Math.min(300, off.length())));
        } finally {
            rig(row, () -> {
                Files.deleteIfExists(props);
                Files.deleteIfExists(dir);
            });
        }
        System.out.println("  PASS " + row);
    }

    /**
     * Row {@code config-magicseam} - {@code /api/config}'s MagicLib prefill:
     * over the flow props (no {@code magicseam.enabled} row) the member reads {@code "on"} (the serve's compiled
     * default) right after {@code rngPins}; over a props file holding {@code magicseam.enabled=false} beside
     * {@code rngpin.enabled=true} it reads {@code "off"} while {@code rngPins} still reads {@code "on"} (each prefill
     * reads its OWN row), and over the swapped pair the reverse.
     */
    private static void configMagicSeamRows(String cfgJson) {
        String row = "config-magicseam";
        check(cfgJson.contains("\"rngPins\":\"on\",\"magicSeam\":\"on\",\"pilotShip\":"),
                row + ": an absent row serves magicSeam 'on' right after rngPins (flips if the member is dropped,"
                        + " moved, or an absent row stops reading on)");
        Path dir = rigDir(row, "cc-ops-config-magicseam");
        Path props = dir.resolve("cc-server.properties");
        try {
            rig(row, () -> Files.writeString(props, OpsMatchRows.MAGIC_SEAM_KEY + "=false\n" + OpsMatchRows.RNG_PINS_KEY
                    + "=true\n", StandardCharsets.UTF_8));
            String off = named(row, () -> configJson(props, 50.0));
            rig(row, () -> Files.writeString(props, OpsMatchRows.MAGIC_SEAM_KEY + "=true\n" + OpsMatchRows.RNG_PINS_KEY
                    + "=false\n", StandardCharsets.UTF_8));
            String on = named(row, () -> configJson(props, 50.0));
            check(off.contains("\"rngPins\":\"on\",\"magicSeam\":\"off\"")
                            && on.contains("\"rngPins\":\"off\",\"magicSeam\":\"on\""),
                    row + ": the =false / =true rows serve off / on beside the opposite replay-pins word (flips if the"
                            + " prefill ignores its row, reads the replay-pins row, or the word map inverts); got "
                            + off.substring(0, Math.min(300, off.length())));
        } finally {
            rig(row, () -> {
                Files.deleteIfExists(props);
                Files.deleteIfExists(dir);
            });
        }
        System.out.println("  PASS " + row);
    }

    /**
     * Row {@code config-pilotship} - {@code /api/config}'s player-ship prefill:
     * over the flow props (no {@code pilotship.enabled} row) the member reads {@code "off"} (the serve's compiled
     * default, the OPPOSITE of the MagicLib row's absent default, so a prefill reading the MagicLib row reads on)
     * right after {@code magicSeam}, before {@code selectedIndex}; over a props file holding
     * {@code pilotship.enabled=true} beside {@code magicseam.enabled=false} it reads {@code "on"} while
     * {@code magicSeam} still reads {@code "off"}, and over the swapped pair the reverse.
     */
    private static void configPilotShipRows(String cfgJson) {
        String row = "config-pilotship";
        check(cfgJson.contains("\"magicSeam\":\"on\",\"pilotShip\":\"off\",\"selectedIndex\":"),
                row + ": an absent row serves pilotShip 'off' right after magicSeam (flips if the member is dropped,"
                        + " moved, an absent row stops reading off, or the prefill reads the MagicLib row)");
        Path dir = rigDir(row, "cc-ops-config-pilotship");
        Path props = dir.resolve("cc-server.properties");
        try {
            rig(row, () -> Files.writeString(props, OpsMatchRows.PILOT_SHIP_KEY + "=true\n"
                    + OpsMatchRows.MAGIC_SEAM_KEY + "=false\n", StandardCharsets.UTF_8));
            String on = named(row, () -> configJson(props, 50.0));
            rig(row, () -> Files.writeString(props, OpsMatchRows.PILOT_SHIP_KEY + "=false\n"
                    + OpsMatchRows.MAGIC_SEAM_KEY + "=true\n", StandardCharsets.UTF_8));
            String off = named(row, () -> configJson(props, 50.0));
            check(on.contains("\"magicSeam\":\"off\",\"pilotShip\":\"on\"")
                            && off.contains("\"magicSeam\":\"on\",\"pilotShip\":\"off\""),
                    row + ": the =true / =false rows serve on / off beside the opposite MagicLib word (flips if the"
                            + " prefill ignores its row, reads the MagicLib row, or the word map inverts); got "
                            + on.substring(0, Math.min(300, on.length())));
        } finally {
            rig(row, () -> {
                Files.deleteIfExists(props);
                Files.deleteIfExists(dir);
            });
        }
        System.out.println("  PASS " + row);
    }

    /** A row dial whose unexpected throw still NAMES its row ({@code run-mutants.ps1} counts a
     * kill only on a named line). */
    private static <T> T named(String row, java.util.concurrent.Callable<T> dial) {
        try {
            return dial.call();
        } catch (Exception | Error thrown) {
            throw new AssertionError("FAIL " + row + ": threw " + thrown.getClass().getSimpleName(),
                    thrown);
        }
    }

    /** A rig step that could not stage reads {@code SETUP-<row>} (= ERROR, never a kill). */
    private interface RigStep {
        void run() throws Exception;
    }

    private static void rig(String row, RigStep step) {
        try {
            step.run();
        } catch (Exception | Error thrown) {
            throw new AssertionError("SETUP-" + row + ": " + thrown.getClass().getSimpleName(),
                    thrown);
        }
    }

    private static Path rigDir(String row, String prefix) {
        Path[] made = new Path[1];
        rig(row, () -> made[0] = Files.createTempDirectory(prefix));
        return made[0];
    }

    /** A fake child handle for the reaper row: records whether the stage still lived when the
     * child was killed (children FIRST). */
    private static ProcessHandle childHandle(List<String> order, Process stage) {
        return (ProcessHandle) java.lang.reflect.Proxy.newProxyInstance(
                ServerOpsCoreSelftest.class.getClassLoader(), new Class<?>[] {ProcessHandle.class},
                (proxy, method, args) -> {
                    if ("destroyForcibly".equals(method.getName())) {
                        order.add(stage.isAlive() ? "child-while-stage-alive" : "child-after-stage");
                        return Boolean.TRUE;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Scripted {@link Ui} for the start-flow selftest rows: pre-confirmed keys proceed,
     * everything else records and refuses (the web collecting-Ui shape). */
    private static final class ScriptedUi implements Ui {
        final Set<String> confirmed = new HashSet<>();
        final List<String> confirmsAsked = new ArrayList<>();
        final List<String> confirmMessages = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<String> infos = new ArrayList<>();

        @Override
        public boolean confirm(String key, String title, String message) {
            confirmsAsked.add(key);
            confirmMessages.add(message);
            return confirmed.contains(key);
        }

        @Override
        public void fail(String title, String message) {
            failures.add(title + ": " + message);
        }

        @Override
        public void info(String message) {
            infos.add(message);
        }
    }

    /**
     * kick-line-goldens + kick-parse-roundtrip, the
     * occupant-bound kick, and pkg-texts-stranger, the ten stranger composers:
     * the owner-mode goldens pin owner mode's texts byte for byte;
     * the package goldens are the fixed sentences the web page repeats.
     */
    private static void checkKickAndStrangerTextRows() {
        // kick-line-goldens
        check("kick 3".equals(kickSeatLine(3, "")) && "kick 3".equals(kickSeatLine(3, null))
                        && "kick 3".equals(kickSeatLine(3, "  ")),
                "kick-line-goldens: a blank key mints the key-less kick (flips if a blank key throws or"
                        + " leaks a trailing token)");
        check("kick 3 6a6f65".equals(kickSeatLine(3, "6A6F65"))
                        && "kick 3 6a6f65".equals(kickSeatLine(3, " 6a6f65 ")),
                "kick-line-goldens: the key rides folded + trimmed (flips if the key is dropped or not"
                        + " ASCII-folded to the parser's form)");
        checkThrows(() -> kickSeatLine(3, "zz"), "kick-line-goldens: a non-hex key throws");
        checkThrows(() -> kickSeatLine(3, "6a6"), "kick-line-goldens: an odd-length key throws");
        checkThrows(() -> kickSeatLine(0, ""), "kick-line-goldens: seat 0 throws (the observers' seat)");
        checkThrows(() -> kickSeatLine(-1, "6a6f65"), "kick-line-goldens: a negative seat throws");
        String kickRefusal = messageOf(() -> kickSeatLine(3, "qqzz"));
        check(kickRefusal.startsWith("kick needs the seat's hex cid key") && !kickRefusal.contains("qqzz"),
                "kick-line-goldens: the refusal names the law and never echoes the form's key; got "
                        + kickRefusal);

        // kick-parse-roundtrip: through the REAL operator-file grammar
        OperatorCommandFile.ParseResult kicks = OperatorCommandFile.parse(List.of(
                "kick 3 6a6f65", "kick 3", "kick 3 zz", "kick 3 6a6f65 x", kickSeatLine(4, "6A6F65"),
                "KICK 5 6A6F65", "kick 0 6a6f65"));
        check(kicks.commands().size() == 4 && kicks.rejected().size() == 3
                        && kicks.commands().get(0).equals(new OperatorCommandFile.Command(
                                OperatorCommandFile.Verb.KICK, 3, 0f, false, "6a6f65"))
                        && kicks.commands().get(1).equals(new OperatorCommandFile.Command(
                                OperatorCommandFile.Verb.KICK, 3, 0f, false, ""))
                        && kicks.commands().get(2).equals(new OperatorCommandFile.Command(
                                OperatorCommandFile.Verb.KICK, 4, 0f, false, "6a6f65"))
                        && kicks.commands().get(3).equals(new OperatorCommandFile.Command(
                                OperatorCommandFile.Verb.KICK, 5, 0f, false, "6a6f65")),
                "kick-parse-roundtrip: kick <seat> [<cidkey>] parses to KICK + the folded key ('' when"
                        + " absent); a non-key third token, a fourth token and seat 0 REJECT (flips if the"
                        + " parser drops the key, stops folding it, or accepts a non-key tail): "
                        + kicks.commands() + " rejected=" + kicks.rejected());

        // pkg-texts-stranger: every package text is free of owner-mode words ...
        String opsPath = "C:\\work\\repo\\out\\ops\\ops-status.txt";
        String repoRoot = "C:\\work\\repo";
        String[][] pkgGoldens = {
            {noFileDetail(opsPath, true), "No server is running. Press Start server."},
            {admissionHint("m.txt.password", true), "writes m.txt.password (+ .adminpassword); match 8 to 64,"
                    + " admin 12 to 64, never a common word; match password = what players type to join, admin"
                    + " password = waves + /kick (must differ); a match password is required; blank admin = none"},
            {admissionInlineHint("m.txt.password", true),
                    "writes m.txt.password; a match password is required; blank admin = none"},
            {armedDoorConfirmText("d.txt", true), "net.doorFingerprint=true but d.txt.fingerprint does not"
                    + " exist - the server will REFUSE this Start (fail-closed door preflight), leaving the"
                    + " match.descriptor row switched.\nMake a joiner kit for this match first, or set"
                    + " net.doorFingerprint=false.\nStart anyway?"},
            {customExportTip(true), "Saves a copy of the picked seat's flying custom ship as a file in"
                    + " variants-inbox for your own use. Players do not see it; Keep for everyone lists it."},
            {unbanPickPrompt(3, true), "Ban to lift (3 more not listed - type \"unban cid <key>\" or"
                    + " \"unban src <address>\" into run\\ops\\ops-cmd.txt):"},
            {unbanPickPrompt(0, true), "Ban to lift:"},
            {modsRootsTip(repoRoot, true), "the folders searched for mods (the mods.roots row); none named = the"
                    + " serve-mods folder of this package: extract a mod there, set it to required, press Start"},
            {shapeHint(true), "writes match.descriptor; after a change press Start - every player's join window"
                    + " fetches the new match (the kit stays the same)"},
            {shapeInlineHint(true), "after a change press Start - the players' join windows fetch the new match"},
            {matchPasswordRefusalText(true), "The match password is a short code of letters, digits and"
                    + " dashes, starting with a letter or digit (8 to 64 characters)."},
            // The shipped-ships texts - the page's heading, empty line and
            // failed-save sentence, and the server-minted Hide confirm
            {SHIPS_TITLE, "Ships that come with the server"},
            {SHIPS_EMPTY_TEXT, "Start the server to see its ships."},
            {SHIP_HIDE_TITLE, "Hide ship"},
            {SHIPS_SAVE_FAILED_TEXT, "The hidden list could not be saved, so a change may not last past a restart."
                    + " Failed saves: "},
            {shipHideConfirmText("wolf_Assault"), "Hide 'wolf_Assault' from every player? It leaves the /ship list"
                    + " now and at every start, until you press Show. A player already flying it, or whose seat's"
                    + " default ship it is, keeps it."},
        };
        // The shipped-ships texts also carry none of the words a stranger
        // could not act on (the editor's text law); the older goldens predate this list, so it reads these five only
        List<String> shipNeedles = List.of("dev", "repo", "roll", "watch", "approve");
        for (String text : List.of(SHIPS_TITLE, SHIPS_EMPTY_TEXT, SHIP_HIDE_TITLE, SHIPS_SAVE_FAILED_TEXT,
                shipHideConfirmText("wolf_Assault"))) {
            for (String needle : shipNeedles) {
                check(!text.toLowerCase(Locale.ROOT).contains(needle),
                        "pkg-texts-stranger: a shipped-ships text carries '" + needle + "' (flips if a stranger-facing"
                                + " ships text gains a word about the project's own tools): " + text);
            }
        }
        List<String> needles = List.of("token", "token mode", "ops-cmd", "local\\", ".ps1", "mint flow",
                "ops knob", "re-packed", "owner", opsPath, repoRoot);
        for (String[] g : pkgGoldens) {
            // The package's OWN command file is a stranger-legible pointer, not owner mode's
            // "ops-cmd" verb - only that exact path is exempt from the needle scan
            String lower = g[0].replace("run\\ops\\ops-cmd.txt", "").toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                check(!lower.contains(needle.toLowerCase(Locale.ROOT)),
                        "pkg-texts-stranger: a package text carries the owner-mode word '" + needle
                                + "' (flips if a stranger-facing text regains an owner-mode word): " + g[0]);
            }
            check(g[0].equals(g[1]), "pkg-texts-stranger: the package text drifted from the pinned sentence"
                    + " the web page repeats (flips if one shell's sentence changes alone): " + g[0]);
        }
        // Every command the package unban hint quotes
        // parses through the REAL operator-file grammar (placeholders filled) to UNBAN
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("\"([^\"]+)\"")
                .matcher(unbanPickPrompt(2, true));
        List<String> quotedLines = new ArrayList<>();
        while (quoted.find()) {
            quotedLines.add(quoted.group(1).replace("<key>", "6a6f65").replace("<address>", "10.0.0.2"));
        }
        OperatorCommandFile.ParseResult hinted = OperatorCommandFile.parse(quotedLines);
        check(quotedLines.size() == 2 && hinted.rejected().isEmpty() && hinted.commands().size() == 2
                        && hinted.commands().stream().allMatch(c -> c.verb() == OperatorCommandFile.Verb.UNBAN),
                "pkg-texts-stranger: the package unban hint's commands " + quotedLines + " must parse to"
                        + " UNBAN (flips if the hint names a grammar the command file rejects); rejected="
                        + hinted.rejected());
        // ... and every owner-mode text matches its fixed golden, byte for byte.
        String[][] ownerGoldens = {
            {noFileDetail("out/ops/ops-status.txt", false),
                    "no ops file at out/ops/ops-status.txt - server not started (or ops knob off)"},
            {admissionHint("m.txt.password", false), "writes m.txt.password (+ .adminpassword); match 8 to 64,"
                    + " admin 12 to 64, never a common word; match password = joiners (no kit re-pack), admin"
                    + " password = F11/F12 waves + /kick (must differ); blank match = TOKEN mode (Start asks"
                    + " first); blank admin = none"},
            {admissionInlineHint("m.txt.password", false),
                    "writes m.txt.password; blank match = TOKEN mode (Start asks), blank admin = none"},
            {armedDoorConfirmText("d.txt", false), "net.doorFingerprint=true but d.txt.fingerprint does not"
                    + " exist - the serve wrapper will REFUSE this Start (fail-closed door preflight), leaving"
                    + " the match.descriptor row switched.\nPack a kit against this descriptor first"
                    + " (local\\package-joiner-kit.ps1 stages the sidecar), or set net.doorFingerprint=false."
                    + "\nStart anyway?"},
            {customExportTip(false), "Copies the picked seat's flying custom into custom.exportDir (default"
                    + " variants-inbox/) for the variant mint flow"},
            {unbanPickPrompt(3, false), "Ban to lift (3 more not listed - ops-cmd unban):"},
            {unbanPickPrompt(0, false), "Ban to lift:"},
            {modsRootsTip(repoRoot, false), "the mods.roots row; absent = local/drills;generated/foreign-mods."
                    + " Relative entries resolve under C:\\work\\repo"},
            {shapeHint(false), "writes match.descriptor; a different match means the joiner kit must be"
                    + " re-packed against it before remote players can join"},
            {shapeInlineHint(false), "the descriptor Start writes - a new shape needs a re-packed kit"},
            {matchPasswordRefusalText(false), "The match password is a short code of letters, digits and"
                    + " dashes, starting with a letter or digit (8 to 64 characters); blank the field for"
                    + " token mode."},
        };
        for (String[] g : ownerGoldens) {
            check(g[0].equals(g[1]), "pkg-texts-stranger: an owner-mode text drifted from its fixed text"
                    + " (flips if owner mode's wording changes): " + g[0]);
        }
        // sanitizedMatchPassword reads the mode through OpsRoots (its signature is shared).
        OpsRoots before = OpsRoots.ROOTS.get();
        String ownerRefusal;
        String pkgRefusal;
        try {
            OpsRoots.ROOTS.set(OpsRoots.owner());
            ownerRefusal = messageOf(() -> sanitizedMatchPassword("has space"));
            OpsRoots.ROOTS.set(OpsRoots.packaged(Paths.get("pkg-texts-stranger-root")));
            pkgRefusal = messageOf(() -> sanitizedMatchPassword("has space"));
        } finally {
            OpsRoots.ROOTS.set(before);
        }
        check(ownerRefusal.startsWith(matchPasswordRefusalText(false) + "\n(")
                        && pkgRefusal.startsWith(matchPasswordRefusalText(true) + "\n(")
                        && !pkgRefusal.toLowerCase(Locale.ROOT).contains("token"),
                "pkg-texts-stranger: sanitizedMatchPassword's refusal follows the OpsRoots mode (flips if a"
                        + " package host is told about token mode): owner=" + ownerRefusal + " pkg=" + pkgRefusal);
    }

    /**
     * The kept verbs' console rows -
     * {@code keep-forget-minters}, {@code customs-detail-kept}, {@code pick-rows-kept}, {@code status-json-kept}.
     */
    private static void checkKeptRows() {
        String r = "keep-forget-minters";
        check("custom-keep cc-v1-assault".equals(customKeepLine(" cc-v1-assault "))
                && "custom-keep-export kept-my-wolf-1a2b3c".equals(customKeepExportLine("kept-my-wolf-1a2b3c "))
                && "custom-keep-export cc-v1-assault".equals(customKeepExportLine("cc-v1-assault"))
                && "custom-forget kept-my-wolf-1a2b3c".equals(customForgetLine(" kept-my-wolf-1a2b3c"))
                && "custom-forget my_wolf".equals(customForgetLine("my_wolf")),
                r + ": goldens, trimmed (flips if a minter drops its verb word, the trim or the token, or if Forget"
                + " refuses a host-dropped kept stem without the kept- prefix - the scan lists it)");
        Map<String, Runnable> rejects = new java.util.LinkedHashMap<>();
        rejects.put("keep blank", () -> customKeepLine("  "));
        rejects.put("keep null", () -> customKeepLine(null));
        rejects.put("keep bad id!", () -> customKeepLine("bad id!"));
        rejects.put("keep-export blank", () -> customKeepExportLine(""));
        rejects.put("keep-export null", () -> customKeepExportLine(null));
        rejects.put("keep-export bad id!", () -> customKeepExportLine("bad id!"));
        rejects.put("keep-export wolf_Assault", () -> customKeepExportLine("wolf_Assault"));
        rejects.put("forget blank", () -> customForgetLine(" "));
        rejects.put("forget null", () -> customForgetLine(null));
        rejects.put("forget bad id!", () -> customForgetLine("bad id!"));
        rejects.put("forget cc-v1-x", () -> customForgetLine("cc-v1-x"));
        List<String> accepted = new ArrayList<>();
        for (Map.Entry<String, Runnable> e : rejects.entrySet()) {
            if (messageOf(e.getValue()).isEmpty()) {
                accepted.add(e.getKey());
            }
        }
        check(accepted.isEmpty(), r + ": accepted " + accepted + " (flips if a minter stops refusing blank / null /"
                + " a bad charset / a prefix-less export name / a stem that is not kept-...)");
        OperatorCommandFile.ParseResult trip = OperatorCommandFile.parse(List.of(customKeepLine("cc-v1-assault"),
                customKeepExportLine("kept-my-wolf-1a2b3c"), customForgetLine("kept-my-wolf-1a2b3c")));
        check(trip.rejected().isEmpty() && trip.commands().size() == 3
                && trip.commands().get(0).verb() == OperatorCommandFile.Verb.CUSTOM_KEEP
                && "cc-v1-assault".equals(trip.commands().get(0).text())
                && trip.commands().get(1).verb() == OperatorCommandFile.Verb.CUSTOM_KEEP_EXPORT
                && "kept-my-wolf-1a2b3c".equals(trip.commands().get(1).text())
                && trip.commands().get(2).verb() == OperatorCommandFile.Verb.CUSTOM_FORGET
                && "kept-my-wolf-1a2b3c".equals(trip.commands().get(2).text()),
                r + ": the three lines round-trip through OperatorCommandFile.parse (flips if a minter's verb word"
                        + " drifts from the grammar): " + trip.commands() + " rejected=" + trip.rejected());
        for (OperatorCommandFile.Verb v : List.of(OperatorCommandFile.Verb.CUSTOM_KEEP,
                OperatorCommandFile.Verb.CUSTOM_KEEP_EXPORT, OperatorCommandFile.Verb.CUSTOM_FORGET)) {
            String minter = v == OperatorCommandFile.Verb.CUSTOM_KEEP ? "customKeepLine(String)"
                    : v == OperatorCommandFile.Verb.CUSTOM_KEEP_EXPORT ? "customKeepExportLine(String)"
                    : "customForgetLine(String)";
            Throwable thrown = null;
            try {
                verbLine(v, 1);
            } catch (Throwable t) { // graded right below: an IAE naming the minter, never the AssertionError
                thrown = t;
            }
            check(thrown instanceof IllegalArgumentException && thrown.getMessage().contains(minter),
                    r + ": verbLine(" + v + ", 1) threw " + (thrown == null ? "nothing" : thrown.getClass().getSimpleName())
                            + " (flips if the arm falls back to the AssertionError default or stops naming " + minter + ")");
        }

        r = "customs-detail-kept";
        List<String> base = new ArrayList<>(FIXTURE);
        base.addAll(List.of("custom.count=3", "custom.rejected=6", "custom.banned=2", "custom.submissions=on",
                "custom.rejected.policy=1", "custom.rejected.caps=2", "custom.rejected.engine=3",
                "custom.rejected.operator=0", "custom.pending=1", "custom.cap_global=64", "custom.tier=strict",
                "custom.per_seat=4", "custom.smod_cap=engine", "custom.dmods=allow", "custom.exported=5",
                "custom.export_failed=1"));
        String preKept = "rejected: policy 1, caps 2, engine 3, operator 0 - policy strict, 4/seat, s-mods engine,"
                + " d-mods allow - exported 5, export failed 1";
        OpsSnapshot preSnap = OpsSnapshot.parse(base);
        check(preKept.equals(customsDetailText(preSnap)) && !preSnap.customKeptKnown(),
                r + ": a pre-kept writer's detail is byte-identical (flips if the kept tail shows without custom.kept): "
                        + customsDetailText(preSnap));
        List<String> withKept = new ArrayList<>(base);
        withKept.addAll(List.of("custom.kept=2", "custom.kept_ids=kept-my-wolf-1a2b3c,kept-b-000001",
                "custom.kept_failed=1", "custom.kept_last=forgot kept-c-000002",
                "custom.inbox_ids=cc-v3-x,kept-c-000002", "custom.kept_over_cap=0", "custom.inbox_over_cap=0"));
        OpsSnapshot keptSnap = OpsSnapshot.parse(withKept);
        String keptWant = preKept + " | kept for everyone: 2/32: kept-my-wolf-1a2b3c, kept-b-000001"
                + " | exported, not kept yet: 2: cc-v3-x, kept-c-000002 | last: forgot kept-c-000002";
        check(keptWant.equals(customsDetailText(keptSnap)),
                r + ": the kept tail golden (flips if the detail drops or reorders the kept part): "
                        + customsDetailText(keptSnap));
        List<String> overLines = new ArrayList<>(base);
        overLines.addAll(List.of("custom.kept=0", "custom.kept_ids=-", "custom.kept_failed=0", "custom.kept_last=-",
                "custom.inbox_ids=-", "custom.kept_over_cap=1", "custom.inbox_over_cap=1"));
        OpsSnapshot overSnap = OpsSnapshot.parse(overLines);
        check((preKept + " | kept for everyone: 0/32 (none) - over cap, the first 32 listed"
                + " | exported, not kept yet: 0 - over cap").equals(customsDetailText(overSnap)),
                r + ": none + both over-cap words, kept_last '-' omitted (flips if a capped list is silent or '-'"
                        + " prints as a verdict): " + customsDetailText(overSnap));
        // The two R3b refusal meters parse and show in the detail ONLY once one fired;
        // the pre-R3b golden above (no key) stays byte-identical.
        List<String> meterLines = new ArrayList<>(base);
        meterLines.addAll(List.of("custom.frame_encode_failed=2", "custom.vsub_decode_refused=1"));
        OpsSnapshot meterSnap = OpsSnapshot.parse(meterLines);
        check(meterSnap.customFrameEncodeFailed() == 2 && meterSnap.customVsubDecodeRefused() == 1
                && (preKept + " - frame encode failed 2, vsub decode refused 1").equals(customsDetailText(meterSnap)),
                r + ": the R3b refusal meters parse and tail the detail only when non-zero (flips if a key goes unread"
                        + " or the zero case grows a tail): " + customsDetailText(meterSnap));

        r = "pick-rows-kept";
        check(List.of("kept-my-wolf-1a2b3c", "kept-b-000001").equals(keptPickRows(keptSnap))
                && List.of("cc-v3-x", "kept-c-000002").equals(inboxPickRows(keptSnap))
                && keptPickRows(overSnap).isEmpty() && inboxPickRows(overSnap).isEmpty()
                && keptPickRows(preSnap).isEmpty() && inboxPickRows(preSnap).isEmpty()
                && keptPickRows(null).isEmpty() && inboxPickRows(null).isEmpty(),
                r + ": kept / inbox pick rows = the two id rows, '-' / absent / null = empty (flips if the lists swap,"
                        + " '-' parses as an id or null throws): " + keptPickRows(keptSnap) + " / " + inboxPickRows(keptSnap));

        r = "status-json-kept";
        String keptJson = statusJson(keptSnap, null, "x", true, false, 10.0, 1L, List.of());
        String emptyKeys = "\"customKept\":0,\"customKeptIds\":[],\"customKeptFailed\":0,\"customKeptLast\":\"-\","
                + "\"customInboxIds\":[],\"customKeptOverCap\":false,\"customInboxOverCap\":false";
        check(keptJson.contains("\"customKept\":2,\"customKeptIds\":[\"kept-my-wolf-1a2b3c\",\"kept-b-000001\"],"
                        + "\"customKeptFailed\":1,\"customKeptLast\":\"forgot kept-c-000002\","
                        + "\"customInboxIds\":[\"cc-v3-x\",\"kept-c-000002\"],\"customKeptOverCap\":false,"
                        + "\"customInboxOverCap\":false")
                        && statusJson(overSnap, null, "x", true, false, 10.0, 1L, List.of())
                                .contains("\"customKeptOverCap\":true,\"customInboxOverCap\":true")
                        && statusJson(preSnap, null, "x", true, false, 10.0, 1L, List.of()).contains(emptyKeys)
                        && statusJson(null, null, "x", false, false, 10.0, 1L, List.of()).contains(emptyKeys),
                r + ": the 7 kept keys from a kept snapshot, [] / '-' / false from a pre-kept one and none (flips if a"
                        + " key is dropped, renamed or reads another row): " + keptJson);
        List<String> quoteLines = new ArrayList<>(base);
        quoteLines.addAll(List.of("custom.kept=0", "custom.kept_last=a\"b"));
        check(statusJson(OpsSnapshot.parse(quoteLines), null, "x", true, false, 10.0, 1L, List.of())
                        .contains("\"customKeptLast\":\"a\\\"b\""),
                r + ": customKeptLast rides jsonEscape (flips if a status string reaches the JSON raw)");
        System.out.println("  PASS keep-forget-minters customs-detail-kept pick-rows-kept status-json-kept");
    }

    /**
     * The shipped-ships console rows - {@code ship-hide-minters},
     * {@code pick-rows-ships}, {@code status-json-ships}; then {@code ship-verb-round-trip}
     * (the two minted lines through the REAL {@link OperatorCommandFile#parse}) and {@code ship-verb-line-refused}
     * (the six ship verbs through {@code verbLine(Verb, int)}).
     */
    private static void checkShipsRows() {
        int before = failures;
        String r = "ship-hide-minters";
        check("ship-hide wolf_Assault".equals(shipHideLine(" wolf_Assault "))
                && "ship-show wolf_Assault".equals(shipShowLine("wolf_Assault\t"))
                && "ship-hide doom_antimatter-srm".equals(shipHideLine("doom_antimatter-srm"))
                && "ship-show mudskipper_cc_pilum_large".equals(shipShowLine("mudskipper_cc_pilum_large")),
                r + ": goldens, trimmed, case kept (flips if a minter drops its verb word, the trim or the token,"
                        + " the two minters swap verbs, or a shipped stem carrying - or _ is refused)");
        Map<String, Runnable> rejects = new java.util.LinkedHashMap<>();
        rejects.put("hide blank", () -> shipHideLine("  "));
        rejects.put("hide null", () -> shipHideLine(null));
        rejects.put("show blank", () -> shipShowLine(""));
        rejects.put("show null", () -> shipShowLine(null));
        rejects.put("hide cc-v1-assault", () -> shipHideLine("cc-v1-assault"));
        rejects.put("show CC-V1-assault", () -> shipShowLine("CC-V1-assault"));
        rejects.put("hide bad id!", () -> shipHideLine("bad id!"));
        rejects.put("show bad id!", () -> shipShowLine("bad id!"));
        // the verb token law admits ':' and a leading '_', the shipped-stem law does not
        rejects.put("hide wolf:Assault", () -> shipHideLine("wolf:Assault"));
        rejects.put("show _wolf", () -> shipShowLine("_wolf"));
        rejects.put("hide 65 chars", () -> shipHideLine("w".repeat(65)));
        List<String> accepted = new ArrayList<>();
        for (Map.Entry<String, Runnable> e : rejects.entrySet()) {
            if (messageOf(e.getValue()).isEmpty()) {
                accepted.add(e.getKey());
            }
        }
        check(accepted.isEmpty(), r + ": accepted " + accepted + " (flips if a ship minter stops refusing blank /"
                + " null / a cc-v custom id in any case / a bad charset / a token outside the shipped-stem law / an"
                + " over-long id)");
        String refusal = messageOf(() -> shipHideLine("cc-v1-assault"));
        check(refusal.startsWith("ship-hide needs the name of a ship that comes with the server")
                        && messageOf(() -> shipShowLine("_wolf")).startsWith("ship-show needs"),
                r + ": the refusal names its verb and what it takes (flips if the two minters share one verb word in"
                        + " their refusal or the text stops saying what to pick): " + refusal);
        check(("ship-hide " + "w".repeat(64)).equals(shipHideLine("w".repeat(64))),
                r + ": a 64-char stem is minted (flips if the length bound tightens below the shipped-stem law's 64)");

        r = "pick-rows-ships";
        List<String> shipLines = new ArrayList<>(FIXTURE);
        shipLines.addAll(List.of("ships.base_ids=wolf_Assault,astral_Flash,doom_antimatter-srm",
                "ships.hidden_ids=astral_Flash", "ships.over_cap=1", "ships.last=hid astral_Flash",
                "ships.persist_failed=2"));
        OpsSnapshot shipSnap = OpsSnapshot.parse(shipLines);
        List<String> dashLines = new ArrayList<>(FIXTURE);
        dashLines.addAll(List.of("ships.base_ids=-", "ships.hidden_ids=-", "ships.over_cap=0", "ships.last=-",
                "ships.persist_failed=0"));
        OpsSnapshot dashSnap = OpsSnapshot.parse(dashLines);
        OpsSnapshot preShips = OpsSnapshot.parse(FIXTURE);
        check(List.of("wolf_Assault", "astral_Flash", "doom_antimatter-srm").equals(shippedPickRows(shipSnap))
                        && List.of("astral_Flash").equals(hiddenPickRows(shipSnap))
                        && shipSnap.shipsKnown() && shipSnap.shipsOverCap()
                        && "hid astral_Flash".equals(shipSnap.shipsLast()) && shipSnap.shipsPersistFailed() == 2L,
                r + ": the shipped / hidden pick rows are the two id rows as written, the flag / verdict / counter"
                        + " parse (flips if the lists swap, an id is folded or dropped, or a ships key reads another"
                        + " row): " + shippedPickRows(shipSnap) + " / " + hiddenPickRows(shipSnap) + " last="
                        + shipSnap.shipsLast() + " failed=" + shipSnap.shipsPersistFailed());
        check(shippedPickRows(dashSnap).isEmpty() && hiddenPickRows(dashSnap).isEmpty() && dashSnap.shipsKnown()
                        && !dashSnap.shipsOverCap() && "-".equals(dashSnap.shipsLast())
                        && dashSnap.shipsPersistFailed() == 0L
                        && shippedPickRows(preShips).isEmpty() && hiddenPickRows(preShips).isEmpty()
                        && !preShips.shipsKnown() && !preShips.shipsOverCap() && "-".equals(preShips.shipsLast())
                        && preShips.shipsPersistFailed() == 0L
                        && shippedPickRows(null).isEmpty() && hiddenPickRows(null).isEmpty(),
                r + ": '-' rows / a pre-ships writer / no snapshot = empty lists, false, '-', 0 (flips if '-' parses"
                        + " as an id, an old status file stops parsing or reads as known, or null throws)");

        r = "status-json-ships";
        String shipJson = statusJson(shipSnap, null, "x", true, false, 10.0, 1L, List.of());
        String emptyShips = "\"shipsBaseIds\":[],\"shipsHiddenIds\":[],\"shipsOverCap\":false,\"shipsLast\":\"-\","
                + "\"shipsPersistFailed\":0";
        check(shipJson.contains("\"shipsBaseIds\":[\"wolf_Assault\",\"astral_Flash\",\"doom_antimatter-srm\"],"
                        + "\"shipsHiddenIds\":[\"astral_Flash\"],\"shipsOverCap\":true,"
                        + "\"shipsLast\":\"hid astral_Flash\",\"shipsPersistFailed\":2")
                        && statusJson(preShips, null, "x", true, false, 10.0, 1L, List.of()).contains(emptyShips)
                        && statusJson(null, null, "x", false, false, 10.0, 1L, List.of()).contains(emptyShips),
                r + ": the 5 ships keys from a ships snapshot, [] / false / '-' / 0 from a pre-ships one and none"
                        + " (flips if a key is dropped, renamed or reads another row): " + shipJson);
        // a hidden list round-trips: the JSON array the page reads back equals the status file's row, order and case
        List<String> hiddenIds = List.of("wolf_Assault", "astral_Flash", "Doom_antimatter-srm");
        List<String> tripLines = new ArrayList<>(FIXTURE);
        tripLines.addAll(List.of("ships.base_ids=" + String.join(",", hiddenIds),
                "ships.hidden_ids=" + String.join(",", hiddenIds), "ships.last=a\"b"));
        String tripJson = statusJson(OpsSnapshot.parse(tripLines), null, "x", true, false, 10.0, 1L, List.of());
        String head = "\"shipsHiddenIds\":[";
        int at = tripJson.indexOf(head);
        int end = at < 0 ? -1 : tripJson.indexOf(']', at);
        List<String> readBack = new ArrayList<>();
        if (end > at) {
            for (String quoted : tripJson.substring(at + head.length(), end).split(",")) {
                readBack.add(quoted.length() >= 2 ? quoted.substring(1, quoted.length() - 1) : quoted);
            }
        }
        check(hiddenIds.equals(readBack) && tripJson.contains("\"shipsLast\":\"a\\\"b\""),
                r + ": the hidden list round-trips file -> snapshot -> JSON in order and case, and shipsLast rides"
                        + " jsonEscape (flips if the hidden ids are re-sorted, folded or truncated on the way to the"
                        + " page, or a status string reaches the JSON raw): " + readBack);
        checkShipVerbLines();
        if (failures == before) {
            System.out.println("  PASS ship-hide-minters pick-rows-ships status-json-ships ship-verb-round-trip"
                    + " ship-verb-line-refused");
        }
    }

    /**
     * {@code ship-verb-round-trip} - what the console's Hide / Show mint is
     * what the serve's grammar reads: {@link #shipHideLine} / {@link #shipShowLine} through the REAL
     * {@link OperatorCommandFile#parse} yield {@code SHIP_HIDE} / {@code SHIP_SHOW} with the id as the command text,
     * case kept, at both ends of the shared 64-char bound; a hand-typed line naming two ids is refused, never read as
     * the first. {@code ship-verb-line-refused} - {@code verbLine(Verb, int)} refuses each of the six ship verbs the
     * way it refuses {@code CUSTOM_FORGET} (an {@link IllegalArgumentException} naming the verb, at any seat number),
     * never the {@link AssertionError} default; the two the console mints name their minter.
     */
    private static void checkShipVerbLines() {
        String r = "ship-verb-round-trip";
        String id64 = "W" + "x".repeat(63);
        OperatorCommandFile.ParseResult trip = OperatorCommandFile.parse(List.of(shipHideLine("wolf_Assault"),
                shipShowLine("wolf_Assault"), shipHideLine("Doom_antimatter-srm"), shipShowLine(id64)));
        List<OperatorCommandFile.Command> got = trip.commands();
        check(trip.rejected().isEmpty() && got.size() == 4
                        && got.get(0).verb() == OperatorCommandFile.Verb.SHIP_HIDE && "wolf_Assault".equals(got.get(0).text())
                        && got.get(1).verb() == OperatorCommandFile.Verb.SHIP_SHOW && "wolf_Assault".equals(got.get(1).text())
                        && got.get(2).verb() == OperatorCommandFile.Verb.SHIP_HIDE
                        && "Doom_antimatter-srm".equals(got.get(2).text())
                        && got.get(3).verb() == OperatorCommandFile.Verb.SHIP_SHOW && id64.equals(got.get(3).text()),
                r + ": the minted hide / show lines parse as SHIP_HIDE / SHIP_SHOW with the id as written (flips if a"
                        + " minter's verb word drifts from the grammar, the two swap, the parser folds the id's case, or"
                        + " its token bound tightens below the minter's 64): " + got + " rejected=" + trip.rejected());
        OperatorCommandFile.ParseResult two = OperatorCommandFile.parse(List.of("ship-hide wolf_Assault astral_Flash",
                "ship-show wolf_Assault astral_Flash", "ship-hide"));
        check(two.commands().isEmpty() && two.rejected().size() == 3,
                r + ": a line naming two ids (or none) is refused (flips if the grammar reads the first id and drops the"
                        + " rest, or a bare verb parses): " + two.commands() + " rejected=" + two.rejected().size());

        r = "ship-verb-line-refused";
        Map<OperatorCommandFile.Verb, String> want = new java.util.LinkedHashMap<>();
        want.put(OperatorCommandFile.Verb.SHIP_HIDE, "shipHideLine(String)");
        want.put(OperatorCommandFile.Verb.SHIP_SHOW, "shipShowLine(String)");
        for (OperatorCommandFile.Verb chatOnly : List.of(OperatorCommandFile.Verb.SHIP_REMOVE,
                OperatorCommandFile.Verb.SHIP_RESTORE, OperatorCommandFile.Verb.SHIP_LIST, OperatorCommandFile.Verb.SHIP_KEEP)) {
            want.put(chatOnly, "the console mints none");
        }
        List<String> wrong = new ArrayList<>();
        for (int seat : new int[] {0, 1, -3}) {
            Throwable forget = thrownBy(() -> verbLine(OperatorCommandFile.Verb.CUSTOM_FORGET, seat));
            for (Map.Entry<OperatorCommandFile.Verb, String> e : want.entrySet()) {
                Throwable t = thrownBy(() -> verbLine(e.getKey(), seat));
                String word = e.getKey().name().toLowerCase(Locale.ROOT).replace('_', '-') + " ";
                if (forget == null || t == null || t.getClass() != forget.getClass()
                        || !t.getMessage().startsWith(word) || !t.getMessage().contains(e.getValue())) {
                    wrong.add(e.getKey() + "@" + seat + "=" + (t == null ? "minted" : t.getClass().getSimpleName() + ": "
                            + t.getMessage()));
                }
            }
        }
        check(wrong.isEmpty(), r + ": " + wrong + " (flips if a ship verb's arm is dropped - it falls to the"
                + " AssertionError default - or it mints a line, stops naming its verb, or a minted verb stops naming"
                + " its minter)");
    }

    /** What {@code action} throws (any {@link Throwable}, the {@link AssertionError} default included), or null. */
    private static Throwable thrownBy(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) { // graded by the caller: the class and the message are the row
            return t;
        }
        return null;
    }

    /** The P27 ban ops twins — verb-line goldens + refusals
     *  round-tripped through the REAL grammar, the OpsSnapshot ban block (old writer / 2 rows /
     *  capped + bans_more / fail-closed), the shared display texts and the statusJson fields. */
    private static void checkP27BanRows() {
        check("ban 3".equals(verbLine(OperatorCommandFile.Verb.BAN, 3))
                && "ban-src 3".equals(verbLine(OperatorCommandFile.Verb.BAN_SRC, 3))
                && "bans".equals(verbLine(OperatorCommandFile.Verb.BANS, 0)),
                "P27 ban / ban-src / bans verbLine goldens");
        // A player ban key is the cid's digest; the console's ban-cid and
        // unban cid take either the digest a listing prints or the older hex key (6a6f65 = the hex of "joe") and
        // forward the digest, trimmed and case-folded.
        String joe = CidDigest.of("joe");
        check(("ban-cid " + joe).equals(banCidLine("6a6f65"))
                && ("ban-cid " + joe).equals(banCidLine(" 6A6F65 "))
                && ("ban-cid " + joe).equals(banCidLine(joe))
                && ("ban-cid " + joe).equals(banCidLine(" " + joe.toUpperCase(Locale.ROOT) + " "))
                && "ban-session 7".equals(banSessionLine(7L))
                // A negative session id is lawful (half of all ids)
                && "ban-session -7".equals(banSessionLine(-7L))
                && ("unban cid " + joe).equals(unbanLine("cid", "6a6f65"))
                && ("unban cid " + joe).equals(unbanLine("cid", joe))
                && "unban src 10.0.0.2".equals(unbanLine("src", "10.0.0.2")),
                "P27 ban-cid / ban-session / unban goldens (either cid key form -> the digest, trimmed + folded)"
                        + " — flips if the console forwards the hex key or refuses the digest a listing prints");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.BAN, 0), "ban 0 rejected");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.BAN_SRC, -1), "ban-src -1 rejected");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.BAN_CID, 1),
                "verbLine refuses BAN_CID (banCidLine holds it)");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.BAN_SESSION, 1),
                "verbLine refuses BAN_SESSION (banSessionLine holds it)");
        checkThrows(() -> verbLine(OperatorCommandFile.Verb.UNBAN, 1),
                "verbLine refuses UNBAN (unbanLine holds it)");
        checkThrows(() -> banCidLine("zz"), "ban-cid non-hex key rejected");
        checkThrows(() -> banCidLine(""), "ban-cid blank key rejected");
        checkThrows(() -> banCidLine(null), "ban-cid null key rejected");
        checkThrows(() -> banSessionLine(0L), "ban-session 0 rejected");
        checkThrows(() -> unbanLine("seat", "x"), "unban kind seat rejected");
        checkThrows(() -> unbanLine("cid", "10.0.0.2"), "unban cid needs a player key (digest or hex)");
        checkThrows(() -> unbanLine("src", "not an ip"), "unban src needs the door's key form");
        OperatorCommandFile.ParseResult banTrip = OperatorCommandFile.parse(List.of(
                verbLine(OperatorCommandFile.Verb.BAN, 3), verbLine(OperatorCommandFile.Verb.BAN_SRC, 3),
                verbLine(OperatorCommandFile.Verb.BANS, 0), banCidLine("6A6F65"), banSessionLine(7L),
                unbanLine("cid", "6a6f65"), unbanLine("src", "10.0.0.2")));
        List<OperatorCommandFile.Command> bc = banTrip.commands();
        check(banTrip.rejected().isEmpty() && bc.size() == 7
                && bc.get(0).verb() == OperatorCommandFile.Verb.BAN && bc.get(0).seatId() == 3
                && bc.get(1).verb() == OperatorCommandFile.Verb.BAN_SRC && bc.get(1).seatId() == 3
                && bc.get(2).verb() == OperatorCommandFile.Verb.BANS
                && bc.get(3).verb() == OperatorCommandFile.Verb.BAN_CID
                && joe.equals(bc.get(3).text())
                && bc.get(4).verb() == OperatorCommandFile.Verb.BAN_SESSION
                && "7".equals(bc.get(4).text())
                && bc.get(5).verb() == OperatorCommandFile.Verb.UNBAN
                && ("cid " + joe).equals(bc.get(5).text())
                && bc.get(6).verb() == OperatorCommandFile.Verb.UNBAN
                && "src 10.0.0.2".equals(bc.get(6).text()),
                "P27 ban verb lines round-trip through OperatorCommandFile.parse (the cid key as its digest)");

        // OpsSnapshot ban block: an old (pre-P27) writer = 0 / 0 / empty
        OpsSnapshot oldWriter = OpsSnapshot.parse(FIXTURE);
        check(oldWriter.bans() == 0 && oldWriter.bansMore() == 0 && oldWriter.banRows().isEmpty(),
                "pre-P27 writer (no bans= row) parses to 0 bans / empty rows");
        List<OpsStatusFile.BanRow> two = List.of(
                new OpsStatusFile.BanRow("cid", joe, 86400000L, "op"), // the entry key a live serve writes
                new OpsStatusFile.BanRow("src", "10.0.0.2", 0L, "admin"));
        List<String> twoLines = banComposeLines(two);
        OpsSnapshot twoSnap = OpsSnapshot.parse(twoLines);
        check(twoLines.contains("bans=2") && twoLines.contains("ban.1.kind=cid")
                && twoLines.contains("ban.1.key=" + joe) && twoLines.contains("ban.1.at=86400000")
                && twoLines.contains("ban.2.origin=admin")
                && twoLines.stream().noneMatch(l -> l.startsWith("bans_more=")),
                "composeLines writes the 2-row ban block (no bans_more under the cap)");
        check(twoSnap.bans() == 2 && twoSnap.bansMore() == 0 && twoSnap.banRows().size() == 2
                && twoSnap.banRows().get(0).equals(
                        new OpsSnapshot.BanRow(1, "cid", joe, 86400000L, "op"))
                && twoSnap.banRows().get(1).equals(
                        new OpsSnapshot.BanRow(2, "src", "10.0.0.2", 0L, "admin")),
                "OpsSnapshot parses the 2-row ban block (1-based index)");
        List<OpsStatusFile.BanRow> many = new ArrayList<>();
        for (int i = 0; i < OpsStatusFile.MAX_BAN_ROWS + 2; i++) {
            many.add(new OpsStatusFile.BanRow("cid", String.format(Locale.ROOT, "%04x", i), 1L, "op"));
        }
        List<String> manyLines = banComposeLines(many);
        OpsSnapshot manySnap = OpsSnapshot.parse(manyLines);
        check(manyLines.contains("bans=66") && manyLines.contains("bans_more=2")
                && !manyLines.contains("ban.65.kind=cid")
                && manySnap.bans() == 66 && manySnap.bansMore() == 2
                && manySnap.banRows().size() == OpsStatusFile.MAX_BAN_ROWS
                && manySnap.banRows().get(63).index() == 64
                && "003f".equals(manySnap.banRows().get(63).key()),
                "capped ban block: 64 rows parsed + bans_more=2");
        List<String> halfFormed = new ArrayList<>(FIXTURE);
        halfFormed.addAll(List.of("bans=2", "ban.1.kind=cid", "ban.1.key=" + joe, "ban.1.at=1",
                "ban.1.origin=op"));
        checkThrows(() -> OpsSnapshot.parse(halfFormed), "bans=2 with one row fails closed");
        List<String> negative = new ArrayList<>(FIXTURE);
        negative.add("bans=-1");
        checkThrows(() -> OpsSnapshot.parse(negative), "bans=-1 fails closed");

        // the shared ban-surface texts (Swing dialogs + web page + JSON label)
        check("no bans in the last snapshot".equals(NO_BANS_TEXT), "NO_BANS_TEXT golden");
        // The key-bound seat lines, the confirm
        // goldens (per door mode + per source scope), the hostile-param refusals, F12.
        String tail = "\nIf another client now holds the seat, nothing is banned.\nThe server log names the outcome.";
        check(("Ban seat 3 (mnp-d1)?\nA password serve kicks this player now and refuses their client id AND the"
                + " address they connected from at the door until unbanned.\nA token serve refuses client-id bans"
                + " - use Ban source there." + tail)
                .equals(banConfirmText(3, "mnp-d1")), "banConfirmText golden");
        String srcHead = "Ban the SOURCE address of seat 3 (mnp-d1)?";
        String local = "\nEvery local joiner and test client on it is kicked and refused, across serves, until unbanned.";
        // The loopback confirm claims ONE serve - the boot parse drops a loopback src line
        String loopbackLocal = "\nEvery local joiner and test client on it is kicked and refused until unbanned or this serve"
                + " ends (a loopback bar is never kept in the bans file).";
        check((srcHead + "\nThat address is THIS MACHINE (loopback)." + loopbackLocal + tail)
                        .equals(banSrcConfirmText(3, "mnp-d1", "127.0.0.2:61234"))
                && (srcHead + "\nThat address is THIS MACHINE (loopback)." + loopbackLocal + tail)
                        .equals(banSrcConfirmText(3, "mnp-d1", "0:0:0:0:0:0:0:1"))
                && (srcHead + "\nThat address is on your LAN." + local + tail)
                        .equals(banSrcConfirmText(3, "mnp-d1", "192.168.1.5:5000"))
                && (srcHead + "\nEvery client behind that address (CGNAT / shared LAN) is kicked and refused,"
                        + " across serves, until unbanned." + tail).equals(banSrcConfirmText(3, "mnp-d1", "8.8.8.8:5000")),
                "banSrcConfirmText goldens: 127.0.0.2 / ::1 name THIS MACHINE, 192.168.1.5 your LAN, a public literal the generic text");
        check("ban 2 6a6f65".equals(banSeatLine(OperatorCommandFile.Verb.BAN, 2, "6a6f65"))
                && "ban-src 2 6a6f65".equals(banSeatLine(OperatorCommandFile.Verb.BAN_SRC, 2, "6a6f65"))
                && "ban 2 6a6f65".equals(banSeatLine(OperatorCommandFile.Verb.BAN, 2, " 6A6F65 ")),
                "banSeatLine goldens (key trimmed + lowercased)");
        checkThrows(() -> banSeatLine(OperatorCommandFile.Verb.BAN, 2, ""), "banSeatLine BAN with no key throws");
        checkThrows(() -> banSeatLine(OperatorCommandFile.Verb.BAN_SRC, 2, ""), "banSeatLine BAN_SRC with no key throws");
        checkThrows(() -> banSeatLine(OperatorCommandFile.Verb.BAN, 2, null), "banSeatLine null key throws");
        checkThrows(() -> banSeatLine(OperatorCommandFile.Verb.BAN, 0, "6a6f65"), "banSeatLine seat 0 throws");
        checkThrows(() -> banSeatLine(OperatorCommandFile.Verb.KICK, 2, "6a6f65"), "banSeatLine mints ban / ban-src only");
        checkThrows(() -> banSeatLine(OperatorCommandFile.Verb.BAN, 2, "6a6f\nend-match"), "banSeatLine newline key throws");
        // row 5: hostile params never mint a line (the web twins below pin the file).
        checkThrows(() -> banCidLine("6a6f\nend-match"), "banCidLine newline-injected key throws");
        checkThrows(() -> unbanLine("src", "1.2.3.4 x"), "unbanLine src with a trailing token throws");
        String v6Key;
        try {
            v6Key = cc.starsector.server.runtime.DoorSourceBudget.sourceKey(
                    java.net.InetAddress.getByName("2001:db8:12:3400::1"));
        } catch (java.net.UnknownHostException literal) {
            throw new AssertionError("server-ops-gui selftest FAILED: an IPv6 literal never resolves", literal);
        }
        check(("unban src " + v6Key).equals(unbanLine("src", v6Key)), "unbanLine accepts an IPv6 /56 key: " + v6Key);
        // The web Ban address minter - ONE typed token through the one
        // normalizer; the line carries the STORED key (trimmed, folded, an RFC-form IPv6 range as its byte key).
        String v6Range = BanSet.srcBanKeyOrNull("2a0b:f4c0::/32", null);
        check("ban-ip 1.2.3.4".equals(banIpLine("1.2.3.4"))
                && "ban-ip 1.2.3.0/24".equals(banIpLine(" 1.2.3.0/24 "))
                && "2a:b:f4:c0::/32".equals(v6Range)
                && ("ban-ip " + v6Range).equals(banIpLine("2a0b:f4c0::/32"))
                && ("ban-ip " + v6Range).equals(banIpLine(" 2A0B:F4C0::/32 ")),
                "banIpLine goldens (1.2.3.4 / 1.2.3.0/24 / 2a0b:f4c0::/32 -> its byte key, got " + v6Range
                        + ") - flips if the minter forwards the typed text (padded, upper-cased, RFC form) or the"
                        + " RFC convert drifts");
        checkThrows(() -> banIpLine(""), "banIpLine empty token throws");
        checkThrows(() -> banIpLine(null), "banIpLine null token throws");
        checkThrows(() -> banIpLine("1.2.3.4 x"), "banIpLine two tokens throw (a second token never rides the line)");
        checkThrows(() -> banIpLine("1.2.3.4/24"), "banIpLine host bits set throws");
        checkThrows(() -> banIpLine("1.2.3.4/32"), "banIpLine /32 throws (a bare address is typed bare)");
        checkThrows(() -> banIpLine("not an ip"), "banIpLine non-address throws");
        checkThrows(() -> banIpLine("1".repeat(70)), "banIpLine 70-char token throws");
        checkThrows(() -> banIpLine("1.2.3.4\nend-match"), "banIpLine newline-injected token throws");
        String hostBits = thrownMessage(() -> banIpLine("1.2.3.4/24"));
        String twoTokens = thrownMessage(() -> banIpLine("1.2.3.4 x"));
        check(("ban-ip needs an address or range: " + BanSet.SRC_KEY_FORM
                        + " - host bits are set: the range is 1.2.3.0/24").equals(hostBits)
                && ("ban-ip needs an address or range: " + BanSet.SRC_KEY_FORM).equals(twoTokens),
                "banIpLine refusals name BanSet.SRC_KEY_FORM + the normalizer's reason (the derived range), never the"
                        + " typed text; got '" + hostBits + "' / '" + twoTokens + "' - flips if the form text or the"
                        + " reason is dropped, or the typed token is echoed");
        OperatorCommandFile.ParseResult ipTrip = OperatorCommandFile.parse(List.of(banIpLine("1.2.3.0/24"),
                banIpLine("2a0b:f4c0::/32")));
        check(ipTrip.rejected().isEmpty() && ipTrip.commands().size() == 2
                        && ipTrip.commands().get(0).verb() == OperatorCommandFile.Verb.BAN_IP
                        && "1.2.3.0/24".equals(ipTrip.commands().get(0).text())
                        && ipTrip.commands().get(1).verb() == OperatorCommandFile.Verb.BAN_IP
                        && v6Range != null && v6Range.equals(ipTrip.commands().get(1).text()),
                "banIpLine round-trip: each minted line parses to ONE BAN_IP carrying the stored key; got "
                        + ipTrip.commands() + " rejected " + ipTrip.rejected()
                        + " - flips if a minted ban-ip line is one the serve's grammar refuses or re-keys");
        // F12: a degenerate bans_more=-1 parses to 0 (never a negative tail).
        List<String> negMore = new ArrayList<>(FIXTURE);
        negMore.add("bans_more=-1");
        check(OpsSnapshot.parse(negMore).bansMore() == 0, "F12: bans_more=-1 parses to 0");
        check(("0123456789ab" + (char) 0x2026).equals(banKeyAbbrev("cid", "0123456789abcdef"))
                && "0123456789ab".equals(banKeyAbbrev("cid", "0123456789ab"))
                && "20:1:d:b8:0:12:34::/56".equals(banKeyAbbrev("src", "20:1:d:b8:0:12:34::/56")),
                "banKeyAbbrev: a cid key over 12 chars is cut + ellipsis, src never cut");
        // The digest's abbreviation counts its 12 characters AFTER the h1: tag (every shown char is a digest char).
        String joeAbbrev = CidDigest.body(joe).substring(0, 12) + (char) 0x2026;
        check(joeAbbrev.equals(banKeyAbbrev("cid", joe)) && !banKeyAbbrev("cid", joe).startsWith(CidDigest.PREFIX),
                "banKeyAbbrev: a digest key shows 12 chars after its h1: tag — flips if the tag eats 3 of the 12");
        check(("#1 cid " + joeAbbrev + " op 1970-01-02").equals(banRowLabel(twoSnap.banRows().get(0)))
                && "#2 src 10.0.0.2 admin -".equals(banRowLabel(twoSnap.banRows().get(1))),
                "banRowLabel goldens (UTC date, '-' when atMillis <= 0)");
        String banJson = statusJson(twoSnap, null, "x", true, false, 10.0, 1L, List.of());
        check(banJson.contains("\"bans\":2,\"bansMore\":0,\"banRows\":[{\"index\":1,\"kind\":\"cid\","
                + "\"key\":\"" + joe + "\",\"keyAbbrev\":\"" + joeAbbrev + "\",\"origin\":\"op\",\"atMillis\":86400000,"
                + "\"label\":\"#1 cid " + joeAbbrev + " op 1970-01-02\"},{\"index\":2,\"kind\":\"src\"")
                && banJson.contains("\"label\":\"#2 src 10.0.0.2 admin -\"}]"),
                "statusJson carries bans / bansMore / banRows");
        String noSnapJson = statusJson(null, null, "x", false, false, 10.0, 1L, List.of());
        check(noSnapJson.contains("\"bans\":0,\"bansMore\":0,\"banRows\":[]"),
                "statusJson without a snapshot: 0 / 0 / []");
    }

    /** The 26-arg composeLines with no seats + the given ban rows (P27 fixture helper). */
    private static List<String> banComposeLines(List<OpsStatusFile.BanRow> bans) {
        return OpsStatusFile.composeLines(1L, 1L, "run", "127.0.0.1", 51337, 7, 1, 2, 2L, 4, 60.0,
                12.5, 3.2, 1000L, 500L, 100L, 50L, 0, 0.0, 0L, "cmd", List.of(), new long[0][],
                PhaseWindowStats.Snapshot.EMPTY, OpsStatusFile.CustomStats.NONE, bans);
    }

    /**
     * {@link PackageModSync} — a packed server honours the Mods rows at
     * launch: in-place mods under serve-mods, an outside mod copied in with its declared dependency,
     * the mirror found twice by design (re-synced, never ambiguous), size+mtime-gated re-copy that
     * deletes nothing, the refusals (missing, missing dependency, ambiguous, collision, both rows,
     * cc_client, a blank roots row) and the exact enabled_mods.json bytes (the stage library's shape).
     */
    private static void packageModSyncRows() throws IOException {
        Path pkg = Files.createTempDirectory("cc-pkg-mods");
        Path sm = pkg.resolve(PackageModSync.MODS_DIR);
        writePkgMod(sm.resolve("CC_Client"), "{\"id\":\"cc_client\",\"name\":\"CC\"}");
        writePkgMod(sm.resolve("E"), "{\"id\":\"E\",\"name\":\"E\"}");
        writePkgMod(sm.resolve("D"), "{\"id\":\"D\",\"name\":\"D\"}");
        writePkgMod(sm.resolve("GL"), "{\"id\":\"GL\",\"name\":\"GL\",\"dependencies\":[{\"id\":\"B\"}]}");
        Path ext = Files.createTempDirectory("cc-pkg-mods-ext");
        writePkgMod(ext.resolve("A"), "{\"id\":\"A\",\"name\":\"A\",\"version\":\"1\","
                + "\"dependencies\":[{\"id\":\"B\",\"name\":\"B\"}]}");
        Files.createDirectories(ext.resolve("A").resolve("jars"));
        Files.write(ext.resolve("A").resolve("jars").resolve("a.jar"), new byte[] {1, 2, 3});
        writePkgMod(ext.resolve("B"), "{\"id\":\"B\"}");
        writePkgMod(ext.resolve("F"), "{\"id\":\"F\",\"dependencies\":[{\"id\":\"G\"}]}");
        Path ext2 = Files.createTempDirectory("cc-pkg-mods-ext2");
        writePkgMod(ext2.resolve("Dup"), "{\"id\":\"A\"}");
        Path ext3 = Files.createTempDirectory("cc-pkg-mods-ext3");
        writePkgMod(ext3.resolve("CC_Client"), "{\"id\":\"Z\"}");
        String extRoot = PackageModSync.MODS_DIR + ";" + ext;

        List<String> said = PackageModSync.sync(pkg, "", "", null);
        check("{\"enabledMods\": [\"cc_client\"]}".equals(PackageModSync.enabledFileText(pkg)) && said.size() == 1,
                "mod sync: no rows = cc_client only, one said line (" + said + ")");
        PackageModSync.sync(pkg, "E", "", null);
        check("{\"enabledMods\": [\"cc_client\", \"E\"]}".equals(PackageModSync.enabledFileText(pkg))
                && !Files.exists(sm.resolve("E").resolve("E")), "mod sync: a mod under serve-mods is used in place");
        said = PackageModSync.sync(pkg, "A", "", extRoot);
        check("{\"enabledMods\": [\"cc_client\", \"A\", \"B\"]}".equals(PackageModSync.enabledFileText(pkg))
                && Files.isRegularFile(sm.resolve("A").resolve("jars").resolve("a.jar"))
                && Files.isRegularFile(sm.resolve("B").resolve(InstalledModScan.MOD_INFO))
                && said.get(0).contains("'A': copied 2 file(s)") && said.get(1).contains("'B': copied 1 file(s)")
                && said.get(2).contains("B (dependency of A)"),
                "mod sync: an outside mod + its declared dependency are copied in and listed (" + said + ")");
        Files.write(sm.resolve("A").resolve("stray.txt"), "x".getBytes(StandardCharsets.US_ASCII));
        said = PackageModSync.sync(pkg, "A", "", extRoot);
        check(said.get(0).contains("'A': copied 0 file(s)") && said.get(0).contains("(2 unchanged)")
                && Files.isRegularFile(sm.resolve("A").resolve("stray.txt")),
                "mod sync: the second launch re-copies nothing and deletes nothing (" + said + ")");
        check(pkgModSyncMessage(pkg, "A", "", extRoot + ";" + ext2).contains("more than one searched folder"),
                "mod sync: an id in two outside folders is ambiguous");
        check(pkgModSyncMessage(pkg, "Q", "", null).contains("mod 'Q' is not in any searched folder"),
                "mod sync: a missing mod refuses by name");
        check(pkgModSyncMessage(pkg, "F", "", extRoot).contains("dependency 'G' of mod 'F' is not in any"),
                "mod sync: a missing declared dependency refuses by name");
        said = PackageModSync.sync(pkg, "", "D", null);
        check("{\"enabledMods\": [\"cc_client\"]}".equals(PackageModSync.enabledFileText(pkg))
                && said.get(0).contains("optional = D"), "mod sync: an optional mod is never listed (" + said + ")");
        // The library's two optional-mod laws (serve-mods-lib.ps1:247-251)
        check(pkgModSyncMessage(pkg, "", "GL", extRoot).contains("optional mod 'GL' depends on 'B', which is not required"),
                "mod sync: an optional mod whose declared dependency is not required refuses by both names (flips if"
                        + " the optional loop stops reading dependencies)");
        said = PackageModSync.sync(pkg, "B", "GL", extRoot);
        check("{\"enabledMods\": [\"cc_client\", \"B\"]}".equals(PackageModSync.enabledFileText(pkg))
                && said.get(said.size() - 1).contains("optional = GL"),
                "mod sync: an optional mod whose dependency IS required passes and is never listed (" + said + ")");
        check(pkgModSyncMessage(pkg, "A", "B", extRoot).contains("optional mod 'B' is also required as a dependency of mod 'A'"),
                "mod sync: an optional id a required mod pulls in refuses by both names (flips if the optional loop"
                        + " ignores the enabled set)");
        check(pkgModSyncMessage(pkg, "D", "D", null).contains("D"), "mod sync: an id in both rows refuses");
        check(pkgModSyncMessage(pkg, "Z", "", PackageModSync.MODS_DIR + ";" + ext3).contains("collides"),
                "mod sync: a folder named like our client mod refuses");
        check(pkgModSyncMessage(pkg, "cc_client", "", null).contains("cc_client"),
                "mod sync: cc_client is never a host pick");
        check(pkgModSyncMessage(pkg, "E", "", "").contains("not in any searched folder"),
                "mod sync: a present-but-blank roots row searches nothing");
        // A symlinked mod folder named through mods.roots is mirrored THROUGH the link
        // (toRealPath), never as 0 files; a folder holding only links mirrors nothing and REFUSES. Both
        // rows need a symlink: where this box cannot make one they are a recorded null pass, never green.
        Path ext4 = Files.createTempDirectory("cc-pkg-mods-ext4");
        Path real = Files.createTempDirectory("cc-pkg-mods-real");
        writePkgMod(real.resolve("L"), "{\"id\":\"L\",\"name\":\"L\"}");
        writePkgMod(real.resolve("M"), "{\"id\":\"M\",\"name\":\"M\"}");
        Files.createDirectories(ext4.resolve("M"));
        try {
            Files.createSymbolicLink(ext4.resolve("L"), real.resolve("L"));
            Files.createSymbolicLink(ext4.resolve("M").resolve(InstalledModScan.MOD_INFO),
                    real.resolve("M").resolve(InstalledModScan.MOD_INFO));
        } catch (IOException | UnsupportedOperationException | SecurityException noLink) {
            System.out.println("  note mod sync: the two symlink rows SKIPPED - this box cannot create a symbolic"
                    + " link (" + noLink.getClass().getSimpleName() + "); a null pass, not a PASS");
            return;
        }
        String linkRoots = PackageModSync.MODS_DIR + ";" + ext4;
        said = PackageModSync.sync(pkg, "L", "", linkRoots);
        check(said.get(0).contains("'L': copied 1 file(s)")
                && Files.isRegularFile(sm.resolve("L").resolve(InstalledModScan.MOD_INFO)),
                "mod sync: a symlinked mod folder is mirrored through the link - said " + said);
        check(pkgModSyncMessage(pkg, "M", "", linkRoots).contains("mirrored no files"),
                "mod sync: a mod folder holding only links mirrors nothing and refuses the start");
    }

    /**
     * the core rows - the auto reachability check
     * ({@code reach-live-once}), the shared press flight ({@code reach-press-busy}), the snapshot attach, the
     * kept {@code serve-mods} root of a packed Remove ({@code pkg-remove-*}, {@code owner-remove-unchanged-core})
     * and the section's texts ({@code launch-texts}). The dial seam is swapped and restored in a finally. The
     * debug-watch join daemon's rows ({@code watch-join-*}, its own armed reference reset in a finally) ride here.
     */
    private static void reachAndModsRemoveRows() throws Exception {
        ServerOpsCore.ReachDialer realDialer = ServerOpsCore.reachDialer;
        try {
            reachLiveOnceRow();
            reachPressBusyRow();
        } finally {
            ServerOpsCore.reachDialer = realDialer;
            ServerOpsCore.REACH_ARMED.set(null);
            ServerOpsCore.LAST_REACH.set(null);
        }
        try {
            watchJoinRows();
            watchLeaseRows();
        } finally {
            ServerOpsCore.WATCH_ARMED.set(null);
        }
        pkgRemoveRows();
        launchTextsRow();
    }

    /** A dialer that counts, stamps its completion and answers SILENT after {@code sleepMs}. */
    private static ServerOpsCore.ReachDialer countingDialer(java.util.concurrent.atomic.AtomicInteger dials,
            java.util.concurrent.atomic.AtomicLong doneNs, List<String> targets, long sleepMs) {
        return (host, port, vpn) -> {
            dials.incrementAndGet();
            synchronized (targets) {
                targets.add(host + ":" + port + (vpn ? " vpn" : ""));
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            doneNs.set(System.nanoTime());
            return new ReachProbe.Result(ReachProbe.Verdict.SILENT, sleepMs, "fixture", "silent");
        };
    }

    /** Waits up to {@code ms} for {@code cond}; true when it held. */
    private static boolean await(java.util.function.BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return cond.getAsBoolean();
    }

    /** {@link #FIXTURE} stamped NOW by THIS (live) process: {@code launchPhase} reads it LIVE. */
    private static void writeLiveOps(Path ops) throws IOException {
        long pid = ProcessHandle.current().pid();
        List<String> lines = new ArrayList<>();
        for (String l : FIXTURE) {
            lines.add(l.startsWith("stamp_ms=") ? "stamp_ms=" + System.currentTimeMillis()
                    : l.startsWith("pid=") ? "pid=" + pid : l);
        }
        Files.write(ops, lines, StandardCharsets.UTF_8);
    }

    private static void reachLiveOnceRow() throws Exception {
        Path dir = Files.createTempDirectory("cc-reach-live");
        Path ops = dir.resolve("ops-status.txt");
        Path box = dir.resolve("launcher.properties");
        Files.writeString(box, "advertise=8.8.8.8:27015\nadvertise.from=protonvpn\n", StandardCharsets.UTF_8);
        java.util.concurrent.atomic.AtomicInteger dials = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong doneNs = new java.util.concurrent.atomic.AtomicLong();
        List<String> targets = new ArrayList<>();
        ServerOpsCore.reachDialer = countingDialer(dials, doneNs, targets, 300L);
        ServerOpsCore.LAST_REACH.set(null);
        java.util.concurrent.atomic.AtomicReference<LaneLaunch> holder = new java.util.concurrent.atomic.AtomicReference<>();
        LaneLaunch first = new LaneLaunch(PUBLIC_LANE, ops, "log", System.currentTimeMillis(), null, null);
        holder.set(first);
        check(armReachCheck(first, holder::get, true, box) && !armReachCheck(first, holder::get, true, box),
                "reach-live-once: a ProtonVPN Start arms ONE daemon and the same launch never re-arms (flips if the"
                        + " per-launch CAS is dropped)");
        LaneLaunch typed = new LaneLaunch(PUBLIC_LANE, ops, "log", System.currentTimeMillis(), null, null);
        check(!armReachCheck(typed, holder::get, false, box), "reach-live-once: a launch out of the ProtonVPN mode"
                + " arms nothing (flips if the mode gate is dropped)");
        // a Kill + Start inside the grace: the holder moves to the next launch, which arms its own check
        LaneLaunch second = new LaneLaunch(PUBLIC_LANE, ops, "log", System.currentTimeMillis(), null, null);
        holder.set(second);
        long armT0 = System.nanoTime();
        boolean armed = armReachCheck(second, holder::get, true, box);
        long armReturnNs = System.nanoTime();
        check(armed, "reach-live-once: the next launch arms its own check (flips if the CAS keys on anything but the"
                + " launch)");
        check(dials.get() == 0, "reach-live-once: no dial before LIVE (flips if the daemon dials at arm)");
        writeLiveOps(ops);
        check(await(() -> dials.get() >= 1 && ServerOpsCore.LAST_REACH.get() != null, 6_000L),
                "reach-live-once: LIVE -> one dial within the poll (flips if the daemon never reaches LIVE)");
        Thread.sleep(1_600L); // the replaced launch's daemon wakes once more and must end without a dial
        check(dials.get() == 1, "reach-live-once: " + dials.get() + " dials for a Kill + Start inside the grace, want 1"
                + " (flips if a replaced launch's daemon still dials)");
        synchronized (targets) {
            check(List.of("8.8.8.8:27015 vpn").equals(targets), "reach-live-once: dialed " + targets
                    + ", want the saved advertise row in the ProtonVPN sentence (flips if the target is not the saved row)");
        }
        long armMs = (armReturnNs - armT0) / 1_000_000L;
        long gapMs = (doneNs.get() - armReturnNs) / 1_000_000L;
        check(armReturnNs < doneNs.get() && armMs < 200L, "reach-live-once: the arm returned in " + armMs
                + " ms, the dial ended " + gapMs + " ms after it (flips if the Start answer waits on the dial)");
        System.out.println("  reach-live-once: arm returned in " + armMs + " ms; the dial completed " + gapMs
                + " ms after the arm returned");
        OpsSnapshot.Reach r = ServerOpsCore.LAST_REACH.get();
        check("silent".equals(r.verdict()) && r.atMs() > 0L && r.note().contains("8.8.8.8:27015"),
                "reach-live-once: the snapshot result = the dial's verdict + the advertise note (" + r + ")");
        // a non-global saved advertise: no dial, verdict local
        Files.writeString(box, "advertise=127.0.0.1:27015\nadvertise.from=protonvpn\n", StandardCharsets.UTF_8);
        LaneLaunch third = new LaneLaunch(PUBLIC_LANE, ops, "log", System.currentTimeMillis() - 1L, null, null);
        holder.set(third);
        ServerOpsCore.LAST_REACH.set(null);
        writeLiveOps(ops);
        check(armReachCheck(third, holder::get, true, box), "reach-live-once: the loopback launch arms");
        check(await(() -> ServerOpsCore.LAST_REACH.get() != null, 4_000L) && dials.get() == 1
                && "local".equals(ServerOpsCore.LAST_REACH.get().verdict()),
                "reach-live-once: a non-global advertise records local WITHOUT a dial (flips if the global gate is"
                        + " dropped; dials " + dials.get() + ")");
        // TIMED_OUT ends the wait without a dial
        long ended = ServerOpsCore.REACH_AUTO_ENDED.get();
        Files.delete(ops);
        LaneLaunch stale = new LaneLaunch(PUBLIC_LANE, ops, "log",
                System.currentTimeMillis() - ServerOpsCore.LAUNCH_GRACE_MS - 1_000L, null, null);
        holder.set(stale);
        check(armReachCheck(stale, holder::get, true, box), "reach-live-once: the stale launch arms");
        check(await(() -> ServerOpsCore.REACH_AUTO_ENDED.get() > ended, 3_000L) && dials.get() == 1,
                "reach-live-once: a TIMED_OUT launch ends its check without a dial (flips if the grace is not read)");
    }

    /**
     * {@code start-flow-client-debug} - the debug-watch posture row rides the
     * Start's ONE config write in BOTH modes (owner and package, each on its own temp root under its own record):
     * a plain Start on a row-less file APPENDS {@code server.clientDebug=false} under its note, a debug-watch Start
     * replaces it in place with {@code true}, the next plain Start writes {@code false} again (never removed,
     * never duplicated), and no spawned command ever carries the posture (the serve reads it from the file).
     */
    private static void startFlowClientDebugRow() {
        String r = "start-flow-client-debug";
        OpsRoots before = OpsRoots.ROOTS.get();
        try {
            for (boolean pkg : new boolean[] {false, true}) {
                String mode = pkg ? "package" : "owner";
                Path root = rigDir(r, "cc-ops-client-debug");
                Path cfg = root.resolve(ServeLauncher.DEFAULT_CONFIG);
                rig(r, () -> {
                    Files.writeString(cfg, "# config\r\nmatch.descriptor=match.txt\r\n", StandardCharsets.UTF_8);
                    if (pkg) {
                        Files.writeString(root.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES),
                                "# box\r\nbind.host=\r\nbind.port=\r\n", StandardCharsets.US_ASCII);
                    }
                });
                OpsRoots.ROOTS.set(pkg ? OpsRoots.packaged(root) : OpsRoots.ownerUnder(root));
                OpsWindowModel.StartFields plain = new OpsWindowModel.StartFields(pkg ? "0.0.0.0" : "127.0.0.1",
                        "51337", "", "", "fixture-pw", null, null, "", null, null, null, null, null, null, null, null,
                        null);
                List<List<String>> spawns = new ArrayList<>();
                SpawnFn rec = c -> {
                    spawns.add(c);
                    return null;
                };
                List<List<String>> after = new ArrayList<>();
                List<Boolean> leases = new ArrayList<>();
                List<String> stamps = new ArrayList<>();
                List<Boolean> started = new ArrayList<>();
                List<String> failures = new ArrayList<>();
                for (OpsWindowModel.StartFields f : List.of(plain, plain.withDebugWatch(true), plain)) {
                    ScriptedUi u = new ScriptedUi();
                    u.confirmed.add(CONFIRM_TOKEN_MODE_START);
                    started.add(named(r, () -> runStartFlow(u, rec, cfg, f)));
                    failures.addAll(u.failures);
                    after.add(named(r, () -> Files.readAllLines(cfg, StandardCharsets.UTF_8)));
                    leases.add(named(r, () -> Files.isRegularFile(watchLeaseFile(cfg))));
                    stamps.add(named(r, () -> Files.isRegularFile(watchLeaseFile(cfg))
                            ? Files.readString(watchLeaseFile(cfg), StandardCharsets.US_ASCII) : null));
                }
                List<String> values = new ArrayList<>();
                boolean oneNote = true;
                for (List<String> lines : after) {
                    List<String> rows = lines.stream().filter(l -> l.startsWith("server.clientDebug=")).toList();
                    values.add(rows.size() == 1 ? rows.get(0) : rows.toString());
                    oneNote &= java.util.Collections.frequency(lines, CLIENT_DEBUG_COMMENT) == 1
                            && lines.indexOf(CLIENT_DEBUG_COMMENT) + 1 == lines.indexOf(rows.isEmpty() ? "" : rows.get(0));
                }
                check(List.of(true, true, true).equals(started) && failures.isEmpty()
                        && List.of("server.clientDebug=false", "server.clientDebug=true", "server.clientDebug=false")
                                .equals(values) && oneNote,
                        r + " (" + mode + "): plain / debug watch / plain Starts leave the file row " + values
                                + " (started " + started + ", failures " + failures.size() + ", one note above the"
                                + " row " + oneNote + "; flips if the upsert, the key literal or the always-write"
                                + " law drifts - a plain Start must write false, never remove or skip the row)");
                check(spawns.size() == 3 && spawns.stream().flatMap(List::stream)
                                .noneMatch(tok -> tok.toLowerCase(Locale.ROOT).contains("clientdebug")
                                        || tok.contains("CLIENT_DEBUG")),
                        r + " (" + mode + "): the three Starts spawn " + spawns.size() + " commands, none carrying the"
                                + " posture (flips if the row is composed into the launch command)");
                check(List.of(false, true, false).equals(leases),
                        r + " (" + mode + "): the lease beside the config file reads " + leases + " after plain /"
                                + " debug watch / plain (flips if the debug WATCH Start writes no lease before its"
                                + " spawn, or a plain Start leaves one behind)");
                // The writer's stamp is the serve's grammar. This JVM is
                // the console here, so the debug WATCH Start's lease must name this pid in one LF-ended line.
                String stamp = stamps.get(1);
                long self = ProcessHandle.current().pid();
                check(stamp != null && stamp.matches("\\d+--?\\d+\n") && ClientDebugLease.leaseStampPid(stamp) == self,
                        r + " (" + mode + "): the debug WATCH Start's lease holds one <pid>-<nanotime> line naming this"
                                + " console's pid " + self + " (read " + (stamp == null ? "no lease" : stamp.length()
                                + " B, parsed pid " + ClientDebugLease.leaseStampPid(stamp)) + "; flips if the"
                                + " writer's stamp shape drifts from the serve's parse, so a live watch reads stale)");
            }
        } finally {
            OpsRoots.ROOTS.set(before);
        }
    }

    /**
     * The debug-watch join daemon's rows ({@link ServerOpsCore#armWatchJoin}),
     * each on its own temp status file, fake launch and holder - no game, no socket, no window. Every row awaits
     * {@link ServerOpsCore#WATCH_JOIN_ENDED}, which EVERY daemon exit bumps, so a row never races a running daemon.
     */
    private static void watchJoinRows() throws Exception {
        watchJoinLiveOnceRow();
        watchJoinHolderSwapRow();
        watchJoinTimedOutRow();
        watchJoinFailedRow();
        watchJoinPressFaultRow();
    }

    /** One row's sinks: a counting press, a recording note, the holder the shell would keep. */
    private record WatchRig(java.util.concurrent.atomic.AtomicInteger presses, List<String> notes,
            java.util.concurrent.atomic.AtomicReference<LaneLaunch> holder) {
        WatchRig() {
            this(new java.util.concurrent.atomic.AtomicInteger(),
                    java.util.Collections.synchronizedList(new ArrayList<>()),
                    new java.util.concurrent.atomic.AtomicReference<>());
        }

        boolean arm(String row, LaneLaunch launch) {
            return named(row, () -> armWatchJoin(launch, holder::get, presses::incrementAndGet, notes::add));
        }
    }

    private static void watchJoinLiveOnceRow() throws Exception {
        String r = "watch-join-live-once";
        Path ops = rigDir(r, "cc-watch-join").resolve("ops-status.txt");
        WatchRig w = new WatchRig();
        LaneLaunch launch = new LaneLaunch(PUBLIC_LANE, ops, "log", System.currentTimeMillis(), null, null);
        w.holder().set(launch);
        long ended = WATCH_JOIN_ENDED.get();
        boolean nullsRefused = named(r, () -> !armWatchJoin(null, w.holder()::get, w.presses()::incrementAndGet,
                w.notes()::add) && !armWatchJoin(launch, null, w.presses()::incrementAndGet, w.notes()::add)
                && !armWatchJoin(launch, w.holder()::get, null, w.notes()::add)
                && !armWatchJoin(launch, w.holder()::get, w.presses()::incrementAndGet, null));
        boolean armed = w.arm(r, launch);
        boolean again = w.arm(r, launch);
        check(nullsRefused && armed && !again, r + ": a Start arms ONE daemon, the same launch never"
                + " re-arms, a null argument arms nothing (nulls refused " + nullsRefused + ", armed " + armed
                + ", re-armed " + again + "; flips if the per-launch CAS keys on anything but the launch)");
        Thread.sleep(REACH_LIVE_POLL_MS + 300L); // at least one poll without a live snapshot
        check(w.presses().get() == 0 && w.notes().isEmpty() && WATCH_JOIN_ENDED.get() == ended,
                r + ": no press and no note before LIVE, the daemon still waiting (presses " + w.presses().get()
                        + "; flips if the daemon presses at arm or ends while PENDING)");
        rig(r, () -> writeLiveOps(ops));
        boolean done = await(() -> WATCH_JOIN_ENDED.get() > ended, 4_000L);
        check(done && w.presses().get() == 1 && w.notes().isEmpty(), r + ": LIVE -> exactly one press, no note, the"
                + " daemon ended (ended " + done + ", presses " + w.presses().get() + ", notes " + w.notes().size()
                + "; flips if the daemon never reaches LIVE or notes a live start)");
        Thread.sleep(REACH_LIVE_POLL_MS + 300L); // the snapshot stays live: a daemon still polling would press again
        check(w.presses().get() == 1 && WATCH_JOIN_ENDED.get() == ended + 1, r + ": still one press one poll later"
                + " (presses " + w.presses().get() + "; flips if the daemon keeps polling after its press)");
    }

    private static void watchJoinHolderSwapRow() throws Exception {
        String r = "watch-join-holder-swap";
        Path dir = rigDir(r, "cc-watch-swap");
        // (a) the holder already answers the NEXT launch while a live snapshot sits on the file: the replaced
        // launch's daemon must end on its first check, never press on that snapshot
        Path liveOps = dir.resolve("ops-live.txt");
        WatchRig a = new WatchRig();
        LaneLaunch replaced = new LaneLaunch(PUBLIC_LANE, liveOps, "log", System.currentTimeMillis(), null, null);
        a.holder().set(new LaneLaunch(PUBLIC_LANE, liveOps, "log", System.currentTimeMillis(), null, null));
        rig(r, () -> writeLiveOps(liveOps));
        long endedA = WATCH_JOIN_ENDED.get();
        boolean armedA = a.arm(r, replaced);
        boolean doneA = await(() -> WATCH_JOIN_ENDED.get() > endedA, 3_000L);
        check(armedA && doneA && a.presses().get() == 0 && a.notes().isEmpty(), r + " (a): a launch the holder no"
                + " longer holds ends its daemon with no press even over a live snapshot (armed " + armedA + ", ended "
                + doneA + ", presses " + a.presses().get() + "; flips if the holder check is dropped)");
        // (b) a Kill / the next Start mid-wait: no snapshot ever, the holder moves while the daemon sleeps
        Path waitOps = dir.resolve("ops-wait.txt");
        WatchRig b = new WatchRig();
        LaneLaunch waiting = new LaneLaunch(PUBLIC_LANE, waitOps, "log", System.currentTimeMillis(), null, null);
        b.holder().set(waiting);
        long endedB = WATCH_JOIN_ENDED.get();
        boolean armedB = b.arm(r, waiting);
        Thread.sleep(400L);
        boolean waitedB = WATCH_JOIN_ENDED.get() == endedB;
        b.holder().set(new LaneLaunch(PUBLIC_LANE, waitOps, "log", System.currentTimeMillis(), null, null));
        boolean doneB = await(() -> WATCH_JOIN_ENDED.get() > endedB, 3_000L);
        check(armedB && waitedB && doneB && b.presses().get() == 0 && b.notes().isEmpty(), r + " (b): the holder"
                + " moving mid-wait ends the daemon within a poll, no press, no note (waited " + waitedB + ", ended "
                + doneB + "; flips if a Kill / the next Start no longer ends the daemon)");
    }

    private static void watchJoinTimedOutRow() throws Exception {
        String r = "watch-join-timed-out";
        Path ops = rigDir(r, "cc-watch-timeout").resolve("ops-status.txt");
        WatchRig w = new WatchRig();
        LaneLaunch stale = new LaneLaunch(PUBLIC_LANE, ops, "log",
                System.currentTimeMillis() - LAUNCH_GRACE_MS - 1L, null, null);
        w.holder().set(stale);
        long ended = WATCH_JOIN_ENDED.get();
        boolean armed = w.arm(r, stale);
        boolean done = await(() -> WATCH_JOIN_ENDED.get() > ended, 3_000L);
        List<String> notes = List.copyOf(w.notes());
        check(armed && done && w.presses().get() == 0 && List.of(WATCH_JOIN_TIMED_OUT).equals(notes),
                r + ": a launch past the console's launch clock ends with no press and ONE timed-out note (ended "
                        + done + ", notes " + notes.size() + "; flips if the clock is not the console's grace or the"
                        + " note is lost)");
        check(WATCH_JOIN_TIMED_OUT.contains((LAUNCH_GRACE_MS / 60_000L) + " minutes"), r + ": the note names the"
                + " grace it waited (flips if the launch clock moves and the note's minutes no longer match)");
    }

    private static void watchJoinFailedRow() throws Exception {
        String r = "watch-join-failed";
        Path ops = rigDir(r, "cc-watch-failed").resolve("ops-status.txt");
        WatchRig w = new WatchRig();
        OpsPressSelftest.FakeProcess dead = new OpsPressSelftest.FakeProcess();
        dead.exit = 1; // a launcher that exited non-zero before any live snapshot = FAILED in launchPhase
        LaneLaunch failed = new LaneLaunch(PUBLIC_LANE, ops, "log", System.currentTimeMillis(), dead, null);
        rig(r, () -> {
            LaunchPhase staged = launchPhase(failed, null, false, System.currentTimeMillis(), failed.exitCodeOrNull(),
                    false);
            if (staged != LaunchPhase.FAILED) {
                throw new IllegalStateException("staged " + staged);
            }
        });
        w.holder().set(failed);
        long ended = WATCH_JOIN_ENDED.get();
        boolean armed = w.arm(r, failed);
        boolean done = await(() -> WATCH_JOIN_ENDED.get() > ended, 3_000L);
        check(armed && done && w.presses().get() == 0 && w.notes().isEmpty(), r + ": a dead start ends the daemon"
                + " silent - no press, no note, the Start's own text stands (ended " + done + ", notes "
                + w.notes().size() + "; flips if FAILED waits on, presses or leaves a note)");
    }

    private static void watchJoinPressFaultRow() throws Exception {
        String r = "watch-join-press-fault";
        Path ops = rigDir(r, "cc-watch-fault").resolve("ops-status.txt");
        java.util.concurrent.atomic.AtomicInteger presses = new java.util.concurrent.atomic.AtomicInteger();
        List<String> notes = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicReference<LaneLaunch> holder = new java.util.concurrent.atomic.AtomicReference<>();
        String secret = "host-text-" + Long.toHexString(System.nanoTime());
        Runnable faulty = () -> {
            presses.incrementAndGet();
            throw new IllegalStateException(secret);
        };
        LaneLaunch launch = new LaneLaunch(PUBLIC_LANE, ops, "log", System.currentTimeMillis(), null, null);
        holder.set(launch);
        rig(r, () -> writeLiveOps(ops));
        java.io.PrintStream realOut = System.out;
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        java.io.PrintStream tee = new java.io.PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                seen.write(b);
                realOut.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                seen.write(b, off, len);
                realOut.write(b, off, len);
            }
        }, true, StandardCharsets.UTF_8);
        long ended = WATCH_JOIN_ENDED.get();
        boolean armed;
        boolean done;
        System.setOut(tee);
        try {
            armed = named(r, () -> armWatchJoin(launch, holder::get, faulty, notes::add));
            done = await(() -> WATCH_JOIN_ENDED.get() > ended, 4_000L);
        } finally {
            System.setOut(realOut);
        }
        String out = seen.toString(StandardCharsets.UTF_8);
        List<String> rows = out.lines().filter(l -> l.startsWith("cc-ops watch press=fault (")).toList();
        check(armed && done && presses.get() == 1 && notes.isEmpty()
                && List.of("cc-ops watch press=fault (IllegalStateException)").equals(rows) && !out.contains(secret),
                r + ": a throwing press ends the daemon with ONE fault row naming the exception's SimpleName, never"
                        + " its message (ended " + done + ", fault rows " + rows.size() + ", message leaked "
                        + out.contains(secret) + "; flips if the catch is lost or the row carries the text)");
    }

    private static void reachPressBusyRow() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger dials = new java.util.concurrent.atomic.AtomicInteger();
        ServerOpsCore.reachDialer = (host, port, vpn) -> {
            dials.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            return new ReachProbe.Result(ReachProbe.Verdict.REACHED, 7L, "fixture", "reached");
        };
        java.util.concurrent.atomic.AtomicReference<ReachProbe.Result> a = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ReachProbe.Result> b = new java.util.concurrent.atomic.AtomicReference<>();
        Thread ta = new Thread(() -> a.set(checkReach("8.8.8.8", 27015, "8.8.8.8:27015")), "reach-row-a");
        ta.start();
        check(await(() -> dials.get() == 1, 3_000L), "reach-press-busy: the first press starts its dial");
        Thread tb = new Thread(() -> b.set(checkReach("8.8.8.8", 27015, "8.8.8.8:27015")), "reach-row-b");
        tb.start();
        Thread.sleep(100L);
        String busy = messageOf(() -> checkReach("8.8.4.4", 27015, "8.8.4.4:27015"));
        check(REACH_BUSY.equals(busy), "reach-press-busy: another target while a dial runs = busy, got '" + busy
                + "' (flips if the flight is not keyed by target)");
        release.countDown();
        ta.join(5_000L);
        tb.join(5_000L);
        check(a.get() != null && a.get() == b.get() && dials.get() == 1, "reach-press-busy: two presses on one target"
                + " share ONE dial (dials " + dials.get() + "; flips if a press re-dials a running target)");
        check(a.get().verdict() == ReachProbe.Verdict.REACHED && a.get().note().contains("8.8.8.8:27015")
                && a.get().note().startsWith("Reachable:"), "reach-press-busy: the note is re-rendered for the"
                + " typed Advertise (" + a.get().note() + ")");
        ReachProbe.Result other = checkReach("8.8.4.4", 27015, "8.8.4.4:27015");
        check(dials.get() == 2 && other.note().contains("8.8.4.4"), "reach-press-busy: a different target after the"
                + " first ended = a second flight (dials " + dials.get() + ")");
        check(ReachProbe.TYPE_ADDRESS.equals(messageOf(() -> checkReach("  ", 27015, ""))),
                "reach-press-busy: a blank host refuses with the type-the-address sentence");
        String line = reachLogLine(other);
        check("cc-console reach result=reached reason=reached".equals(line), "reach-press-busy: the stdout row '"
                + line + "' carries no address, only the two tokens");
        // the REAL dial against a closed loopback port (the default dials): refused / silent, inside the wait
        ServerOpsCore.reachDialer = (host, port, vpn) -> ReachProbe.dial(host, port, -0x1234_5678_9abcL,
                ReachProbe.Dials.DEFAULT, vpn);
        int closed;
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        long t0 = System.nanoTime();
        ReachProbe.Result real = checkReach("127.0.0.1", closed, null);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        check((real.verdict() == ReachProbe.Verdict.REFUSED || real.verdict() == ReachProbe.Verdict.SILENT)
                && ms <= REACH_PRESS_WAIT_MS + 500L && ReachProbe.REASONS.contains(real.reason())
                && reachToken(real.verdict()).equals(ServerOpsCore.LAST_REACH.get().verdict()),
                "reach-press-busy: the real dial of a closed loopback port = " + real.verdict() + "/" + real.reason()
                        + " in " + ms + " ms (flips if the press outwaits its bound or the result is not recorded)");
        // the snapshot attach: a parsed snapshot carries none; withReach attaches the console's last dial
        OpsSnapshot snap = OpsSnapshot.parse(FIXTURE);
        OpsSnapshot.Reach rr = new OpsSnapshot.Reach("local", 3L, "say \"hi\"", 1_700_000_000_001L);
        OpsSnapshot with = snap.withReach(rr);
        check(snap.reach() == null && with.reach() == rr && with.port() == snap.port() && with.seats().equals(snap.seats())
                && snap.withReach(null) == snap, "reach-press-busy: withReach attaches only the dial (flips if a parsed"
                + " field is lost in the copy)");
        check("{\"verdict\":\"local\",\"ms\":3,\"note\":\"say \\\"hi\\\"\",\"at\":1700000000001}".equals(reachJson(rr))
                && "null".equals(reachJson(null)) && ("Reachability: say \"hi\"").equals(reachabilityLine(rr)),
                "reach-press-busy: the web reach JSON / the status line shapes (" + reachJson(rr) + ")");
    }

    /**
     * The lease daemon's rows ({@link ServerOpsCore#armWatchLease}): a temp config dir holding
     * the row true + a lease, a fake serve process the row ends, the holder the shell keeps. Every row awaits
     * {@link ServerOpsCore#WATCH_LEASE_ENDED}, which EVERY daemon exit bumps.
     */
    private static void watchLeaseRows() throws Exception {
        watchLeaseEndRow();
        watchLeaseForeignRow();
        watchLeaseHolderSwapRow();
        watchLeaseScrubRow();
    }

    /**
     * {@code watch-lease-scrub}: the END leg's {@code atEnd} (the console's settings scrub) runs exactly once, after
     * the covered serve exits - never while it lives, and never on the holder-moved exit (a newer watch may be
     * mid-launch there).
     */
    private static void watchLeaseScrubRow() throws Exception {
        String r = "watch-lease-scrub";
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        LeaseRig g = stageLeaseRig(r, "stamp-one");
        long ended = WATCH_LEASE_ENDED.get();
        boolean armed = named(r, () -> armWatchLease(g.launch(), g.holder()::get, g.cfg(), ran::incrementAndGet));
        Thread.sleep(REACH_LIVE_POLL_MS + 300L);
        boolean quiet = armed && ran.get() == 0 && Files.isRegularFile(g.lease()); // the serve lives: nothing ran
        g.serve().exit = 0;
        boolean done = await(() -> WATCH_LEASE_ENDED.get() > ended, 4_000L);
        boolean once = await(() -> ran.get() == 1, 1_000L); // the row prints before atEnd: give it its turn
        boolean cleared = !Files.exists(g.lease()) && "[server.clientDebug=false]".equals(named(r, () -> leaseRowNow(g.cfg())));
        // the holder-moved exit of a second daemon: its atEnd never runs
        LeaseRig h = stageLeaseRig(r, "stamp-one");
        java.util.concurrent.atomic.AtomicInteger moved = new java.util.concurrent.atomic.AtomicInteger();
        long ended2 = WATCH_LEASE_ENDED.get();
        boolean armed2 = named(r, () -> armWatchLease(h.launch(), h.holder()::get, h.cfg(), moved::incrementAndGet));
        h.holder().set(new LaneLaunch(PUBLIC_LANE, h.launch().opsFile(), "log", System.currentTimeMillis(), null, null));
        boolean done2 = await(() -> WATCH_LEASE_ENDED.get() > ended2, 4_000L);
        Thread.sleep(200L); // an atEnd wrongly placed after the moved exit would have run by now
        check(quiet && done && once && cleared && ran.get() == 1 && armed2 && done2 && moved.get() == 0
                        && Files.isRegularFile(h.lease()),
                r + ": atEnd runs once on the END leg after the clear (quiet while the serve lived " + quiet + ", ended "
                        + done + ", ran " + ran.get() + ", cleared " + cleared + ") and never on the holder-moved exit ("
                        + done2 + ", ran " + moved.get() + "; flips if the scrub hook runs while the serve lives, runs"
                        + " twice, is skipped on the real end, or rides the holder-moved exit)");
    }

    /** One row's stage: the config file with its posture row true, a lease carrying a stamp, a live fake serve (a pure
     *  holder: the file writes live in {@link #stageLeaseRig}, on this class's residency row). */
    private record LeaseRig(Path cfg, Path lease, OpsPressSelftest.FakeProcess serve, LaneLaunch launch,
            java.util.concurrent.atomic.AtomicReference<LaneLaunch> holder) {
    }

    private static LeaseRig stageLeaseRig(String r, String stamp) {
        Path root = rigDir(r, "cc-watch-lease");
        Path cfg = root.resolve(ServeLauncher.DEFAULT_CONFIG);
        Path lease = watchLeaseFile(cfg);
        rig(r, () -> {
            Files.writeString(cfg, "# config\r\nmatch.descriptor=match.txt\r\n" + CLIENT_DEBUG_COMMENT + "\r\n"
                    + CLIENT_DEBUG_KEY + "=true\r\n", StandardCharsets.UTF_8);
            Files.writeString(lease, stamp + "\n", StandardCharsets.US_ASCII);
        });
        OpsPressSelftest.FakeProcess serve = new OpsPressSelftest.FakeProcess();
        LaneLaunch launch = new LaneLaunch(PUBLIC_LANE, root.resolve("ops-status.txt"), "log",
                System.currentTimeMillis(), serve, null);
        return new LeaseRig(cfg, lease, serve, launch, new java.util.concurrent.atomic.AtomicReference<>(launch));
    }

    /** The posture rows of {@code cfg} as a list literal: {@code [server.clientDebug=<v>]}, {@code []} when none. */
    private static String leaseRowNow(Path cfg) throws IOException {
        return Files.readAllLines(cfg, StandardCharsets.UTF_8).stream()
                .filter(l -> l.startsWith(CLIENT_DEBUG_KEY + "=")).toList().toString();
    }

    private static void watchLeaseEndRow() throws Exception {
        String r = "watch-lease-end";
        LeaseRig g = stageLeaseRig(r, "stamp-one");
        long ended = WATCH_LEASE_ENDED.get();
        boolean armed = named(r, () -> armWatchLease(g.launch(), g.holder()::get, g.cfg()));
        Thread.sleep(REACH_LIVE_POLL_MS + 300L); // the serve lives: the lease and the row stay
        boolean waited = Files.isRegularFile(g.lease()) && WATCH_LEASE_ENDED.get() == ended;
        g.serve().exit = 0; // the covered serve exits
        boolean done = await(() -> WATCH_LEASE_ENDED.get() > ended, 4_000L);
        String row = named(r, () -> leaseRowNow(g.cfg()));
        boolean oneComment = named(r, () -> java.util.Collections.frequency(
                Files.readAllLines(g.cfg(), StandardCharsets.UTF_8), CLIENT_DEBUG_COMMENT) == 1);
        check(armed && waited && done && !Files.exists(g.lease()) && "[server.clientDebug=false]".equals(row)
                        && oneComment,
                r + ": the lease and the true row outlive a live serve (" + waited + "), the serve's exit ends the"
                        + " daemon (" + done + "), deletes the lease and writes the row " + row + " under its one note ("
                        + oneComment + "; flips if the daemon clears while the serve lives, never ends on its exit, or"
                        + " leaves the lease or the true row behind)");
    }

    private static void watchLeaseForeignRow() throws Exception {
        String r = "watch-lease-foreign";
        LeaseRig g = stageLeaseRig(r, "stamp-one");
        long ended = WATCH_LEASE_ENDED.get();
        boolean armed = named(r, () -> armWatchLease(g.launch(), g.holder()::get, g.cfg()));
        rig(r, () -> Files.writeString(g.lease(), "stamp-two\n", StandardCharsets.US_ASCII)); // a newer press's lease
        g.serve().exit = 0;
        boolean done = await(() -> WATCH_LEASE_ENDED.get() > ended, 4_000L);
        String leaseNow = named(r, () -> Files.readString(g.lease(), StandardCharsets.US_ASCII).trim());
        String row = named(r, () -> leaseRowNow(g.cfg()));
        check(armed && done && "stamp-two".equals(leaseNow) && "[server.clientDebug=true]".equals(row),
                r + ": a lease another press re-wrote is left with its row (lease " + leaseNow + ", row " + row
                        + "; flips if the daemon clears a lease it did not write)");
    }

    private static void watchLeaseHolderSwapRow() throws Exception {
        String r = "watch-lease-holder-swap";
        LeaseRig g = stageLeaseRig(r, "stamp-one");
        Path noLease = rigDir(r, "cc-watch-nolease").resolve(ServeLauncher.DEFAULT_CONFIG);
        LaneLaunch handleless = new LaneLaunch(PUBLIC_LANE, g.launch().opsFile(), "log", System.currentTimeMillis(),
                null, null);
        boolean refused = named(r, () -> !armWatchLease(handleless, g.holder()::get, g.cfg())
                && !armWatchLease(g.launch(), g.holder()::get, noLease));
        long ended = WATCH_LEASE_ENDED.get();
        boolean armed = named(r, () -> armWatchLease(g.launch(), g.holder()::get, g.cfg()));
        g.holder().set(handleless); // the next Start holds the row now
        boolean done = await(() -> WATCH_LEASE_ENDED.get() > ended, 4_000L);
        String row = named(r, () -> leaseRowNow(g.cfg()));
        check(refused && armed && done && Files.isRegularFile(g.lease()) && "[server.clientDebug=true]".equals(row),
                r + ": no process handle or no lease arms nothing (" + refused + "); a replaced holder ends the daemon"
                        + " (" + done + ") with the lease and the row untouched (row " + row + "; flips if a replaced"
                        + " daemon touches the row or the lease, or a handle-less launch arms one)");
    }

    private static void pkgRemoveRows() throws IOException {
        Path pkg = Files.createTempDirectory("cc-pkg-remove");
        Path sm = pkg.resolve(PackageModSync.MODS_DIR);
        for (String id : List.of("a", "b", "c")) {
            writePkgMod(sm.resolve(id), "{\"id\":\"" + id + "\",\"name\":\"" + id + "\"}");
        }
        Path ext = Files.createTempDirectory("cc-pkg-remove-ext");
        writePkgMod(ext.resolve("y"), "{\"id\":\"y\"}");
        writePkgMod(ext.resolve("z"), "{\"id\":\"z\"}");
        Path own = Files.createTempDirectory("cc-pkg-remove-own");
        writePkgMod(own, "{\"id\":\"o\"}");
        List<String> root = PackageModSync.PACKAGE_DEFAULT_ROOTS;
        List<InstalledModScan.InstalledMod> whole = InstalledModScan.scanRoots(pkg, root);
        InstalledModScan.InstalledMod a = modOf(whole, "a");
        // absent (the package default): kept as is -> the caller writes no row; the owner-mode rule would have split it
        check(root.equals(OpsModsPanel.rootsWithout(root, pkg, a, whole, sm))
                && List.of("serve-mods/b", "serve-mods/c").equals(OpsModsPanel.rootsWithout(root, pkg, a, whole)),
                "pkg-remove-keeps-serve-mods-root: the absent default stays whole (flips if the kept root is split like"
                        + " the owner-mode rule) - got " + OpsModsPanel.rootsWithout(root, pkg, a, whole, sm));
        // explicit: the root token + an outside folder; an absolute spelling of the root is kept verbatim too
        List<String> explicit = List.of("serve-mods", ext.toString());
        check(explicit.equals(OpsModsPanel.rootsWithout(explicit, pkg, a, whole, sm)),
                "pkg-remove-keeps-serve-mods-root: an explicit serve-mods token is never dropped or split");
        List<String> absolute = List.of(sm.toString());
        check(absolute.equals(OpsModsPanel.rootsWithout(absolute, pkg, a, whole, sm)),
                "pkg-remove-keeps-serve-mods-root: a token RESOLVING to the kept root is kept verbatim (flips if the"
                        + " compare is textual)");
        // split: serve-mods/a + serve-mods/b (an older Remove of c) -> folded back; a and c listed again (off)
        List<String> split = List.of("serve-mods/a", "serve-mods/b");
        List<InstalledModScan.InstalledMod> splitScan = InstalledModScan.scanRoots(pkg, split);
        List<String> folded = OpsModsPanel.rootsWithout(split, pkg, modOf(splitScan, "a"), splitScan, sm);
        List<String> listed = new ArrayList<>();
        for (InstalledModScan.InstalledMod m : InstalledModScan.scanRoots(pkg, folded)) {
            listed.add(m.id());
        }
        listed.sort(null);
        check(List.of("serve-mods").equals(folded) && List.of("a", "b", "c").equals(listed),
                "pkg-remove-keeps-serve-mods-root: a split row folds to the root and lists a, b, c (got " + folded
                        + " -> " + listed + "; flips if the split survives)");
        // repair: a split row beside an own-folder mod; removing that mod folds the split at its first position
        List<String> mixed = List.of(own.toString(), "serve-mods/b", "serve-mods/c");
        List<InstalledModScan.InstalledMod> mixedScan = InstalledModScan.scanRoots(pkg, mixed);
        check(ServerOpsCore.keptRootSplit(mixed, pkg, sm), "pkg-remove-repairs-split-row: the split is seen");
        List<String> repaired = OpsModsPanel.rootsWithout(mixed, pkg, modOf(mixedScan, "o"), mixedScan, sm);
        check(List.of("serve-mods").equals(repaired) && !ServerOpsCore.keptRootSplit(repaired, pkg, sm),
                "pkg-remove-repairs-split-row: the own folder leaves and the split folds back ONCE (got " + repaired
                        + "; flips if the fold repeats or the victim's own folder stays)");
        check(modsRemovedLine("o", "o", true).endsWith(MODS_REMOVED_REPAIRED)
                && !modsRemovedLine("o", "o", false).contains("again"),
                "pkg-remove-repairs-split-row: only a repairing remove says the earlier mods show again");
        // an explicit row without serve-mods: the owner-mode rule unchanged, serve-mods never re-added
        List<String> noKept = List.of(own.toString(), ext.toString());
        List<InstalledModScan.InstalledMod> noKeptScan = InstalledModScan.scanRoots(pkg, noKept);
        InstalledModScan.InstalledMod y = modOf(noKeptScan, "y");
        List<String> kept5 = OpsModsPanel.rootsWithout(noKept, pkg, y, noKeptScan, sm);
        check(kept5.equals(OpsModsPanel.rootsWithout(noKept, pkg, y, noKeptScan)) && !kept5.contains("serve-mods")
                && kept5.size() == 2 && kept5.get(0).equals(own.toString()),
                "pkg-remove-explicit-row-untouched: a row naming no serve-mods keeps the owner-mode rule (got " + kept5 + ")");
        // the 4-arg overload == the 5-arg with no kept root, over every shape above
        for (List<String> rows : List.of(root, explicit, absolute, split, mixed, noKept)) {
            List<InstalledModScan.InstalledMod> scan = InstalledModScan.scanRoots(pkg, rows);
            for (InstalledModScan.InstalledMod m : scan) {
                check(OpsModsPanel.rootsWithout(rows, pkg, m, scan).equals(OpsModsPanel.rootsWithout(rows, pkg, m,
                        scan, null)), "owner-remove-unchanged-core: " + rows + " minus " + m.id()
                        + " differs between the owner overload and keptRoot=null (flips if owner mode changed)");
            }
        }
    }

    private static InstalledModScan.InstalledMod modOf(List<InstalledModScan.InstalledMod> mods, String id) {
        for (InstalledModScan.InstalledMod m : mods) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        throw new AssertionError("server-ops-gui selftest FAILED: SETUP-pkg-remove: no scanned mod " + id + " in " + mods);
    }

    private static void launchTextsRow() {
        // verbatim copies of the shipped launch texts: a reword flips here
        check(List.of("off", "diagnostics (timing logs: up to a few hundred MB)", "full (also all network traffic:"
                + " large)").equals(RECORD_ITEMS), "launch-texts: the Recording items drifted");
        check(("Saves files into this start's run folder for bug reports. full writes up to record.maxMiB (default"
                + " 4096 MiB) of network traffic each time the server starts, and again each time it restarts itself"
                + " after a crash (up to 6 per Start); it includes chat and names.").equals(RECORD_TIP),
                "launch-texts: the Recording tip drifted");
        check(("Sends a test packet from this computer to the Advertise address and waits for the running server to"
                + " answer through the internet.").equals(REACH_TIP) && "Check from the internet".equals(REACH_LABEL),
                "launch-texts: the reach button / tip drifted");
        check(("sets the picked mod to off; one inside serve-mods stays listed until you delete its folder there, one"
                + " added from elsewhere stops being searched. Nothing on disk is deleted.").equals(MODS_REMOVE_TIP),
                "launch-texts: the Mods Remove tip drifted");
        check("mods: Q is off - its folder serve-mods/Qf stays (delete it there to take it off the list)"
                .equals(modsRemovedLine("Q", "Qf", false)) && "mods: Q is off".equals(modsRemovedLine("Q", null, false)),
                "launch-texts: the Mods Remove note drifted (" + modsRemovedLine("Q", "Qf", false) + ")");
        check(modsRemovedLine("x".repeat(200), "f", false).length() < 200, "launch-texts: a long id is cut");
        for (String t : launchTexts()) {
            boolean ascii = t.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f);
            check(ascii && t.length() <= 400 && !t.matches("(?s).*(TASK|REVIEW|ADR-|\\bs[0-9]{3,4}\\b|"
                    + String.valueOf((char) 0xA7) + ").*"), "launch-texts: '" + t + "' is not plain bounded host text");
        }
    }

    private static void writePkgMod(Path folder, String modInfo) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(InstalledModScan.MOD_INFO), modInfo, StandardCharsets.UTF_8);
    }

    /** The refusal sentence {@link PackageModSync#sync} throws, or {@code ""}. */
    private static String pkgModSyncMessage(Path pkg, String enabled, String optional, String roots) {
        try {
            PackageModSync.sync(pkg, enabled, optional, roots);
            return "";
        } catch (IllegalArgumentException expected) {
            return String.valueOf(expected.getMessage());
        } catch (IOException unexpected) {
            return "IOException: " + unexpected.getMessage();
        }
    }

    /** One row: a failure prints {@code FAIL <what>} and counts into {@link #failures}, then the chain goes on.
     * */
    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    /** A staged release-kit fixture - {@code packKit} = the
     *  {@code kits/} file name, {@code descriptor} = the offered {@code match/<stem>.txt} (redacted, its sidecar =
     *  the kit's), {@code manifestRows} = the three MANIFEST rows the packer verifies against. */
    record KitFx(String packKit, Path kit, Path descriptor, String manifestRows, String matchId) {
    }

    /** The fixture kit's {@code mod_info.json} (KitPackerSelftest's shape: the manifest reads its version). */
    private static final String KIT_FX_MOD_INFO = "{\"id\":\"cc_client\",\"name\":\"fixture\",\"version\":{\"major\":\"0\","
            + "\"minor\":\"1\",\"patch\":\"0\"},\"gameVersion\":\"0.98a-RC8\"}\n";

    /**
     * Stages a minimal VALID release kit into {@code pkg}
     * ({@code kits/cc-joiner-kit-20261001-120000.zip}: the jar, {@code mod_info.json}, one kit variant, the redacted
     * {@code match.txt}, {@code kit-manifest.txt} + README from {@link KitPackRules}, the sidecar MINTED by the real
     * {@link ContentFingerprintMint#mintSidecarLines}, zipped by {@link KitZip#write}) and the offered
     * {@code match/<stem>.txt} (+ its sidecar). WHY inline: {@code KitPackerSelftest}'s fixture builder is private;
     * this one ships only kit-resident ships, so the census never needs a game folder. A caller that packs
     * against it appends {@link KitFx#manifestRows} to the package MANIFEST. Read by {@code match-shape-texts},
     * whose offered shapes copy the minted sidecar.
     */
    static KitFx stageKitFixture(Path scratch, Path pkg, String stem) throws IOException {
        Path stage = scratch.resolve("kit-stage-" + stem);
        byte[] jar = new byte[300];
        for (int i = 0; i < jar.length; i++) {
            jar[i] = (byte) (i * 31 + 7);
        }
        String jarMd5 = ContentFingerprints.md5Hex(jar);
        MatchDescriptor d = new MatchDescriptor(MatchDescriptor.VERSION, "kitfx-core-0001", "1v1", 6000f, 6000f, 0.7f,
                "BUDGET", List.of(new MatchDescriptor.Side("Alpha", "", "ATTACK"),
                        new MatchDescriptor.Side("Beta", "", "ATTACK")),
                List.of(new MatchDescriptor.RosterRow(0, "kitship_A", 1, MatchDescriptor.PilotKind.SEAT),
                        new MatchDescriptor.RosterRow(1, "kitship_A", 1, MatchDescriptor.PilotKind.SEAT)),
                List.of(new MatchDescriptor.Seat(1, cc.starsector.server.protocol.SeatRole.PILOT, 0, 1L,
                                "live-kitfx-token-1"),
                        new MatchDescriptor.Seat(2, cc.starsector.server.protocol.SeatRole.PILOT, 1, 2L,
                                "live-kitfx-token-2")));
        String redacted = MatchDescriptorCodec.encode(MatchDescriptors.redact(d));
        java.util.TreeMap<String, byte[]> entries = new java.util.TreeMap<>();
        entries.put(KitPackRules.JAR_ENTRY, jar);
        entries.put(KitPackRules.MOD_DIR + "/mod_info.json", KIT_FX_MOD_INFO.getBytes(StandardCharsets.UTF_8));
        entries.put(KitPackRules.MOD_DIR + "/data/variants/kitship_A.variant",
                "{\"variantId\":\"kitship_A\"}\n".getBytes(StandardCharsets.UTF_8));
        entries.put(KitPackRules.MATCH_ENTRY, redacted.getBytes(StandardCharsets.UTF_8));
        entries.put(KitPackRules.MANIFEST_ENTRY, KitPackRules.manifestText(jarMd5, "20261001-120000", "0.1.0",
                "0.98a-RC8").getBytes(StandardCharsets.UTF_8));
        entries.put(KitPackRules.README_ENTRY, KitPackRules.readmeText(
                KitPackRules.SessionBlock.REDACTED_NO_PASSWORD, null).getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            Path p = stage.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        List<String> sidecar = ContentFingerprintMint.mintSidecarLines(stage.resolve(KitPackRules.MOD_DIR));
        Path stagedSidecar = stage.resolve(KitPackRules.SIDECAR_ENTRY);
        ContentFingerprintMint.writeSidecar(stagedSidecar, sidecar);
        entries.put(KitPackRules.SIDECAR_ENTRY, Files.readAllBytes(stagedSidecar));
        String composite = ContentFingerprints.parseSidecarFingerprint(sidecar, "the kit fixture sidecar");
        String packKit = "cc-joiner-kit-20261001-120000.zip";
        Path kits = KitPacker.kitsDir(pkg);
        Files.createDirectories(kits);
        Path kit = kits.resolve(packKit);
        if (!Files.exists(kit)) {
            List<KitZip.Entry> list = new ArrayList<>();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                list.add(new KitZip.Entry(e.getKey(), e.getValue()));
            }
            KitZip.write(kit, list);
        }
        Path match = pkg.resolve(OpsMatchApply.MATCH_DIR);
        Files.createDirectories(match);
        Path descriptor = match.resolve(stem + ".txt");
        Files.writeString(descriptor, redacted, StandardCharsets.UTF_8);
        ContentFingerprintMint.writeSidecar(Paths.get(descriptor + ".fingerprint"), sidecar);
        return new KitFx(packKit, kit.toAbsolutePath().normalize(), descriptor.toAbsolutePath().normalize(),
                "pack.kit=" + packKit + "\nkit.jar.md5=" + jarMd5 + "\nkit.fingerprint="
                        + KitPacker.withoutMatch(composite) + "\n", d.matchId());
    }

    /**
     * The message {@code action} throws (a RuntimeException), or {@code ""} when it does not throw
     * (so a {@code contains} check fails).
     */
    private static String messageOf(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return String.valueOf(expected.getMessage());
        }
        return "";
    }

    /** One must-throw row: no RuntimeException prints {@code FAIL <what> (expected a throw)} and counts into
     *  {@link #failures}, then the chain goes on. */
    private static void checkThrows(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        failures++;
        System.out.println("FAIL " + what + " (expected a throw)");
    }

    /** The message {@code action} throws, or null when it returns (a refusal-text row's probe).
     * */
    private static String thrownMessage(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException refused) {
            return refused.getMessage();
        }
        return null;
    }
}
