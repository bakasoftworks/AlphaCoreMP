package cc.starsector.server.ops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.ops.OpsJoinSelf.Outcome;
import cc.starsector.server.runtime.HandshakeCodec;
import cc.starsector.server.runtime.OperatorCommandFile;

/**
 * {@link OpsJoinSelf}'s rows - "Join from this PC":
 * {@code join-install} (the saved Game folder row: install / core form / relative / R1 / R2), {@code join-dial}
 * (wildcard, loopback, LAN, a name, IPv6, the TUN hairpin through the re-probe, R10, N7),
 * {@code join-merge} (the join-watch script's merge golden, byte for byte),
 * {@code join-spawn-shape} (the fixed argv, cwd, the child env, no secret in argv / env / stdout),
 * {@code join-client-diag} (the debug-watch diag key after the strip, the JTO path law, null = unchanged) and
 * {@code join-order} (the step order's no-write / no-spawn promises, the stdout row); then
 * {@code join-settings-home} (the settings path's precedence arm by arm, the legacy file, the run's pick),
 * {@code join-endpoint} (the endpoint file + install tag, the client's golden), {@code join-ping} (the REAL
 * loopback ping), {@code join-client-twin} (the client source's literals) and
 * {@code join-texts} (K3 over every text field); then {@code join-self-writes-no-kit-file} (a full press writes
 * nothing under the host's kit folder - the REAL disk); then
 * {@code join-kit-check-current}, {@code join-kit-check-stale} and {@code join-kit-check-skipped} (step (1c): the
 * installed kit's stamps against the package's, before anything is written into it); then
 * {@code join-kit-refresh-done}, {@code join-kit-refresh-live-window}, {@code join-kit-refresh-rollback} and
 * {@code join-kit-refresh-verify} (a stale kit replaced from the package's kit zip: stage, park, install, roll back
 * - REAL temp folders through {@link OpsJoinSelf#LIVE}'s refresh seams) and {@code join-fx-delegates} (both
 * {@code Effects} wrappers delegate every seam); then {@code join-second-window},
 * {@code join-second-refused} (the debug flight's second pilot window), {@code join-cid-arm}, {@code join-arm-note}
 * (the seat-key arm through the serve's command file) and {@code join-settings-scrub} (the flight-end secret
 * scrub); {@code join-arm-always} and {@code join-reopened-plain} follow the
 * arm rows (the console arms every press over a live command file; a REOPENED press arms nothing);
 * {@code join-watch-log-cap} follows the second-window rows (the debug flight's client
 * log cap row: its parse, and a bad row refusing the whole press). Game-free: the install fixture is real temp files (the core's {@code resolveGame} reads the disk) and every
 * other effect is {@link FakeEffects} - except {@code join-ping}, which drives the real ping against a loopback
 * {@code HttpServer} (127.0.0.1, port 0, stopped in {@code finally}), and {@code join-client-twin}, which reads
 * {@code client-mod/src} under the cwd (absent = ONE skip line).
 *
 * <p>Run in the build gate through {@code ServerOpsCoreSelftest.run()} ({@code OpsSelftests --selftest}) and
 * directly through {@link #main} (the mutant runner's entry). A failed row prints its line {@code FAIL <row>: ...}
 * ({@code SETUP-<row>: ...} when its fixture never staged), the later rows still run, and {@link #run} then
 * throws the first failure as an {@link AssertionError}.
 *
 *
 */
final class OpsJoinSelfSelftest {

    private OpsJoinSelfSelftest() {
    }

    private static int checks;

    /** The rows' match password (lawful: {@code MatchPasswords}' charset, 10 chars, not blocklisted). */
    private static final String PW = "pw12345678";
    /** A join window's endpoint token (64 lower-case hex) and nonce (8 hex). */
    private static final String TOKEN = "0123456789abcdef".repeat(4);
    private static final String NONCE = "89abcdef";

    /**
     * {@code join-ping}'s other endpoint tokens, 64 lower-case hex each so {@link OpsJoinSelf#liveUrlOrNull} dials
     * them: another app, a body that fits the client's 1024 B read, one byte over it, a silent handler, and one the
     * server does not know (404).
     */
    private static final String TOKEN_DEV = "d".repeat(64);
    private static final String TOKEN_FIT = "f".repeat(64);
    private static final String TOKEN_BIG = "b".repeat(64);
    private static final String TOKEN_SILENT = "5".repeat(64);
    private static final String TOKEN_UNKNOWN = "e".repeat(64);
    /** The join window's ping marker ({@code JoinBrowserUi.liveWindowUrl}) and its whole ping body. */
    private static final String PING_MARKER = "\"app\":\"join\"";
    private static final String PING_JOIN = "{" + PING_MARKER + "}";

    /**
     * {@code join-texts}: the {@code OpsJoinSelf} field names the K3 law covers, the texts this row knows (a new
     * match fails until it is added here and its laws re-read), a {@code String.format} slot, a "what to do next"
     * verb and the dev-tooling words a stranger's console never shows.
     */
    private static final Pattern TEXT_FIELD =
            Pattern.compile("LABEL|TIP|TITLE|KIT_HINT|SECOND_PREFIX|NOTE_\\w*|R\\d+(_SHAPE)?|L\\d+");
    private static final Set<String> KNOWN_TEXTS = Set.of("TITLE", "LABEL", "TIP", "KIT_HINT", "NOTE_OPENED",
            "NOTE_REOPENED", "NOTE_REOPENED_WEB", "R1", "R2", "R4", "R5", "R5_SHAPE", "R6", "R7", "R8", "R9", "R10",
            "R12", "R13", "R14", "R15", "NOTE_KIT_REFRESHED", "NOTE_OPENED_ADMIN", "R16", "R17", "R18",
            "SECOND_PREFIX", "NOTE_SECOND_LIVE");
    private static final Pattern FORMAT_SLOT = Pattern.compile("%(?:([1-9])\\$)?([a-zA-Z%])");
    private static final Pattern ACTION = Pattern.compile(
            "\\b(press|run|set|type|extract|reconnect|start|join from)\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> DEV_WORDS =
            List.of("pwsh", ".ps1", "script", "repo", "local/", "vpn lease", "watch");

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
        System.out.println("join-self row " + name + " PASS");
    }

    /** {@link #row}, keeping going: a failure's line is printed and kept for {@link #run} to throw after the last
     *  row. */
    private static void row(List<AssertionError> failed, String name, Body body) {
        try {
            row(name, body);
        } catch (AssertionError e) {
            System.out.println(e.getMessage());
            failed.add(e);
        }
    }

    /** Every row; exit 1 on a failure. */
    public static void main(String[] args) {
        try {
            run();
        } catch (Exception | AssertionError e) {
            System.out.println("join-self selftest FAILED: " + e);
            System.exit(1);
        }
    }

    /** Every row over one temp tree, removed after (a residue is said once, never fatal).
     *  A failed row prints its line and the later rows still run - a mutant that also flips
     *  an earlier row is seen on the row it targets; the first failure is thrown after the last row, the others
     *  attached as suppressed. */
    public static void run() throws Exception {
        Path dir = Files.createTempDirectory("cc-join-self-selftest");
        List<AssertionError> failed = new ArrayList<>();
        try {
            row(failed, "join-install", r -> joinInstall(r, dir.resolve("install")));
            row(failed, "join-dial", OpsJoinSelfSelftest::joinDial);
            row(failed, "join-watch-dial", r -> joinWatchDial(r, dir.resolve("watchdial")));
            row(failed, "join-merge", OpsJoinSelfSelftest::joinMerge);
            row(failed, "join-spawn-shape", r -> joinSpawnShape(r, dir.resolve("spawn")));
            row(failed, "join-client-diag", r -> joinClientDiag(r, dir.resolve("diag")));
            row(failed, "join-order", r -> joinOrder(r, dir.resolve("order")));
            row(failed, "join-settings-home", r -> joinSettingsHome(r, dir.resolve("home")));
            row(failed, "join-endpoint", r -> joinEndpoint(r, dir.resolve("endpoint")));
            row(failed, "join-ping", OpsJoinSelfSelftest::joinPing);
            row(failed, "join-client-twin", OpsJoinSelfSelftest::joinClientTwin);
            row(failed, "join-texts", OpsJoinSelfSelftest::joinTexts);
            row(failed, "join-self-writes-no-kit-file", r -> joinSelfWritesNoKitFile(r, dir.resolve("nokitfile")));
            row(failed, "join-kit-check-current", r -> joinKitCheckCurrent(r, dir.resolve("kitcurrent")));
            row(failed, "join-kit-check-stale", r -> joinKitCheckStale(r, dir.resolve("kitstale")));
            row(failed, "join-kit-check-skipped", r -> joinKitCheckSkipped(r, dir.resolve("kitskipped")));
            row(failed, "join-kit-refresh-done", r -> joinKitRefreshDone(r, dir.resolve("refreshdone")));
            row(failed, "join-kit-refresh-live-window", r -> joinKitRefreshLiveWindow(r, dir.resolve("refreshlive")));
            row(failed, "join-kit-refresh-rollback", r -> joinKitRefreshRollback(r, dir.resolve("refreshroll")));
            row(failed, "join-kit-refresh-verify", r -> joinKitRefreshVerify(r, dir.resolve("refreshverify")));
            row(failed, "join-fx-delegates", r -> joinFxDelegates(r, dir.resolve("delegates")));
            row(failed, "join-second-window", r -> joinSecondWindow(r, dir.resolve("second")));
            row(failed, "join-second-refused", r -> joinSecondRefused(r, dir.resolve("secondbad")));
            row(failed, "join-watch-log-cap", r -> joinWatchLogCap(r, dir.resolve("logcap")));
            row(failed, "join-cid-arm", r -> joinCidArm(r, dir.resolve("cidarm")));
            row(failed, "join-arm-note", r -> joinArmNote(r, dir.resolve("armnote")));
            row(failed, "join-arm-always", r -> joinArmAlways(r, dir.resolve("armalways")));
            row(failed, "join-reopened-plain", r -> joinReopenedPlain(r, dir.resolve("reopenedplain")));
            row(failed, "join-settings-scrub", r -> joinSettingsScrub(r, dir.resolve("scrub")));
            row(failed, "sweep-plain-join-not-recorded", r -> sweepPlainJoinNotRecorded(r, dir.resolve("sweepplain")));
            row(failed, "spawn-result-carries-handle", r -> spawnResultCarriesHandle(r, dir.resolve("spawnhandle")));
        } finally {
            deleteTree(dir);
        }
        if (!failed.isEmpty()) {
            AssertionError first = failed.get(0);
            for (AssertionError later : failed.subList(1, failed.size())) {
                first.addSuppressed(later);
            }
            throw first;
        }
        System.out.println("join-self selftest checks=" + checks);
        System.out.println("join-self selftest PASS");
    }

    // ---- join-install ----

    private static void joinInstall(String r, Path pkg) throws Exception {
        Path install = stageInstall(pkg.resolve("game"), true, true);
        String row = ServeLauncher.ROW_GAME_ROOT;
        check(r, install.equals(OpsJoinSelf.installOrNull(pkg, Map.of(row, install.toString()))),
                "the Game folder row naming the install resolves to it (flips if installOrNull drops or"
                        + " re-roots the resolved row)");
        check(r, OpsJoinSelf.installRefusalOrNull(install, OpsJoinSelf.LIVE, "kit.zip") == null,
                "an install holding starsector.exe + mods/CC_Client/join-cc.bat is not refused (flips if the"
                        + " exe / bat paths drift from the kit's layout)");
        check(r, install.equals(OpsJoinSelf.installOrNull(pkg,
                        Map.of(row, install.resolve("starsector-core").toString()))),
                "the row naming <install>/starsector-core resolves to the SAME install (flips if the"
                        + " core-form -> getParent arm is lost)");
        check(r, install.equals(OpsJoinSelf.installOrNull(pkg, Map.of(row, "  game  "))),
                "a relative, padded row resolves under the package root (flips if the row is not trimmed or"
                        + " not resolved against packageRoot)");
        check(r, OpsJoinSelf.installOrNull(pkg, Map.of(row, "   ")) == null
                        && OpsJoinSelf.installOrNull(pkg, Map.of()) == null
                        && OpsJoinSelf.R1.equals(OpsJoinSelf.installRefusalOrNull(null, OpsJoinSelf.LIVE, "kit")),
                "a blank / absent row = no install = R1 (flips if a blank row resolves to the package root)");
        Path notGame = pkg.resolve("not-a-game");
        try {
            Files.createDirectories(notGame);
        } catch (IOException e) {
            throw new SetupFailure("folder " + notGame, e);
        }
        check(r, OpsJoinSelf.installOrNull(pkg, Map.of(row, notGame.toString())) == null,
                "a folder without the engine jar is no install (flips if resolveGame's verdict is skipped)");
        Path noExe = stageInstall(pkg.resolve("noexe"), false, true);
        if (noExe.toString().length() > OpsJoinSelf.TEXT_PATH_MAX) {
            throw new SetupFailure("temp path over " + OpsJoinSelf.TEXT_PATH_MAX + " chars: " + noExe, null);
        }
        String r2 = OpsJoinSelf.installRefusalOrNull(noExe, OpsJoinSelf.LIVE, "CC_Client-kit.zip");
        check(r, r2 != null && r2.startsWith("No player kit in this game: ")
                        && r2.contains(noExe + "\\mods\\CC_Client\\join-cc.bat or " + noExe + "\\starsector.exe")
                        && r2.contains("Extract CC_Client from CC_Client-kit.zip into " + noExe + "\\mods\\"),
                "no starsector.exe = R2 naming the install and the kit hint (flips if R2's slots drift or the"
                        + " exe check is dropped): " + r2);
        Path noBat = stageInstall(pkg.resolve("nobat"), true, false);
        String r2bat = OpsJoinSelf.installRefusalOrNull(noBat, OpsJoinSelf.LIVE, "kit.zip");
        check(r, r2bat != null && r2bat.startsWith("No player kit in this game: ") && r2bat.contains(noBat.toString()),
                "no kit bat = R2 (flips if the bat check is dropped)");
        String longHint = "k".repeat(200);
        String cut = OpsJoinSelf.installRefusalOrNull(noBat, OpsJoinSelf.LIVE, longHint);
        check(r, cut.contains("k".repeat(OpsJoinSelf.TEXT_PATH_MAX - 3) + "...")
                        && !cut.contains("k".repeat(OpsJoinSelf.TEXT_PATH_MAX - 2)),
                "a 200-char kit hint is cut to 120 chars with '...' (flips if boundedText stops bounding)");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            check(r, OpsJoinSelf.installOrNull(pkg, Map.of(row, "C:\\x<y")) == null,
                    "an unparsable Windows row = no install, never a throw (flips if resolveRow's"
                            + " InvalidPathException guard is bypassed)");
        }
        kitFolderJoin(r, install);
    }

    /**
     * {@code join-install}'s kit-folder leg. The kit folder is a path
     * JOIN of {@code mods} + {@code CC_Client}. On the real staged install (any OS) the cwd equals the two-name
     * join; then the same over {@link #posixPath}, a POSIX path model whose {@code resolve(String)} splits on '/'
     * ONLY, so one {@code "mods\\CC_Client"} string stays ONE name there, as on Linux (a Linux
     * build would refuse a good install, reasoned). WHY a model: every file-system provider this JDK offers on Windows (the
     * default, zip, jrt) parses a backslash as a separator (a jshell probe: zip and jrt resolve {@code "a\\b"}
     * to two names), so no real Path here could see the literal come back.
     */
    private static void kitFolderJoin(String r, Path install) {
        check(r, OpsJoinSelf.cwd(install).equals(install.resolve("mods").resolve("CC_Client")),
                "the kit folder of the staged install is <install>/mods/CC_Client (flips if the bat moves): "
                        + OpsJoinSelf.cwd(install));
        Path posix = posixPath(List.of("game"));
        Path cwd = OpsJoinSelf.cwd(posix);
        check(r, cwd.equals(posixPath(List.of("game", "mods", "CC_Client"))),
                "on a POSIX path the kit folder is /game/mods/CC_Client, two names (flips if the kit folder is one"
                        + " \"mods\\CC_Client\" string again): " + cwd + " names=" + cwd.getNameCount());
        check(r, OpsJoinSelf.installRefusalOrNull(posix, FakeEffects.withKit(posix), "kit.zip") == null,
                "a good install on a POSIX path is not refused (flips if the bat check builds the kit folder from"
                        + " one backslash string, the Linux build's R2 on a good install)");
    }

    /**
     * A POSIX path model for {@link #kitFolderJoin}: an absolute path of {@code names}; {@code resolve(String)}
     * appends the argument's '/'-separated names (a backslash is an ordinary character, as on Linux);
     * {@code equals} / {@code hashCode} / {@code toString} / {@code getNameCount} over the names; any other
     * {@link Path} method throws {@link UnsupportedOperationException} (a core change that needs more of the
     * model fails the pristine row, never a silent pass).
     */
    private static Path posixPath(List<String> names) {
        List<String> own = List.copyOf(names);
        return (Path) Proxy.newProxyInstance(OpsJoinSelfSelftest.class.getClassLoader(), new Class<?>[] {Path.class},
                (self, method, args) -> switch (method.getName()) {
                    case "resolve" -> {
                        if (!(args[0] instanceof String other)) {
                            throw new UnsupportedOperationException("resolve(Path) on the POSIX model");
                        }
                        List<String> more = new ArrayList<>(own);
                        for (String name : other.split("/")) {
                            if (!name.isEmpty()) {
                                more.add(name);
                            }
                        }
                        yield posixPath(more);
                    }
                    case "getNameCount" -> own.size();
                    case "toString" -> "/" + String.join("/", own);
                    case "hashCode" -> own.hashCode();
                    case "equals" -> args[0] instanceof Path p && Proxy.isProxyClass(p.getClass())
                            && self.toString().equals(p.toString());
                    default -> throw new UnsupportedOperationException(method.getName() + " on the POSIX model");
                });
    }

    // ---- join-dial ----

    private static void joinDial(String r) throws Exception {
        check(r, "127.0.0.1:46337".equals(OpsJoinSelf.dial(live("0.0.0.0", 46337), Map.of(), null)),
                "a wildcard bind dials 127.0.0.1 (flips if 0.0.0.0 reaches the window as a host)");
        for (String host : List.of("127.0.0.1", "192.168.1.5", "my-host.example")) {
            check(r, (host + ":46337").equals(OpsJoinSelf.dial(live(host, 46337), Map.of(), null)),
                    host + " dials itself (flips if a loopback / LAN / name host is rewritten)");
        }
        check(r, OpsJoinSelf.R5.equals(refusal(() -> OpsJoinSelf.dial(live("::1", 46337), Map.of(), null))),
                "an IPv6 bind = R5 (flips if an IPv6 host reaches the N7 shape check or the window)");
        Map<String, String> tun = Map.of(
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM, "protonvpn",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND, "10.2.0.9",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, "203.0.113.5:46337");
        OpsSnapshot tunLive = live("10.2.0.9", 46337);
        VpnLeaseProbe.Probe found = new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 46337, null, 0.5);
        check(r, "203.0.113.5:46337".equals(OpsJoinSelf.dial(tunLive, tun, found)),
                "TUN mode dials the probe's public exit:port (flips if the TUN bind host is dialled as itself)");
        VpnLeaseProbe.Probe rotated = new VpnLeaseProbe.Probe("198.51.100.9", "10.2.0.9", 46337, null, 0.5);
        // A rotated exit dials the re-probe, never the saved row alone.
        check(r, "198.51.100.9:46337".equals(OpsJoinSelf.dial(tunLive, tun, rotated)),
                "a rotated exit with the port held dials the RE-PROBE's exit, not the saved advertise row"
                        + " (flips if the saved row alone is dialled)");
        VpnLeaseProbe.Probe noPort = new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 0, "no port line", -1);
        check(r, "203.0.113.5:46337".equals(OpsJoinSelf.dial(tunLive, tun, noPort)),
                "a probe without a port uses the saved advertise row's port (flips if tunDial stops falling"
                        + " back to portOf(advertise))");
        check(r, OpsJoinSelf.R10.equals(refusal(() -> OpsJoinSelf.dial(tunLive, tun, null))),
                "TUN mode with no probe answer = R10 EXACT (flips if a silent gateway dials the saved row)");
        VpnLeaseProbe.Probe moved = new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 46338, null, 0.5);
        String dead = refusal(() -> OpsJoinSelf.dial(tunLive, tun, moved));
        check(r, dead.startsWith("ProtonVPN now forwards "),
                "a probe forwarding another port = the PACKAGE dead-port refusal (flips if the brick check is"
                        + " skipped or speaks the owner-mode words): " + dead);
        Map<String, String> noMode = Map.of(
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND, "10.2.0.9",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, "203.0.113.5:46337");
        check(r, "10.2.0.9:46337".equals(OpsJoinSelf.dial(tunLive, noMode, null)),
                "no advertise.from row = not TUN mode, the 10.2.0.x host dials itself (flips if the mode is"
                        + " guessed from the address shape)");
        // The debug watch's first window of two dials the live bind's own
        // endpoint whatever the Fill rows say - the TUN host itself, the wildcard as loopback, IPv6 refused.
        check(r, "10.2.0.9:46337".equals(OpsJoinSelf.dialLocal(tunLive))
                        && "127.0.0.1:46337".equals(OpsJoinSelf.dialLocal(live("0.0.0.0", 46337))),
                "the local dial = the TUN bind host itself and 0.0.0.0 as 127.0.0.1 (flips if the local dial rewrites"
                        + " the bind host or passes the wildcard to the window as a host)");
        check(r, OpsJoinSelf.R5.equals(refusal(() -> OpsJoinSelf.dialLocal(live("::1", 46337)))),
                "an IPv6 bind = R5 on the local dial too (flips if an IPv6 host reaches the window through it)");
        // The debug WATCH's second window - one local, one through the VPN: the VPN's public
        // endpoint whatever advertise.from says; the gate = the probe's TUN address equals the live bind host.
        Map<String, String> typed = Map.of(
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM, "typed",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND, "10.2.0.9",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, "203.0.113.5:46337");
        check(r, "203.0.113.5:46337".equals(OpsJoinSelf.dialPublic(tunLive, typed, found)),
                "window 2 dials the probe's public exit:port with advertise.from=typed (flips if tunMode gates the"
                        + " second window's dial - a folder saved by a typed address reads typed)");
        check(r, "198.51.100.9:46337".equals(OpsJoinSelf.dialPublic(tunLive, typed, rotated)),
                "window 2 over a rotated exit dials the RE-PROBE's exit (flips if the saved advertise row is dialled)");
        check(r, OpsJoinSelf.dialPublic(tunLive, typed, null) == null,
                "window 2 with no probe answer = null = the local fallback, never R10 (flips if a failed probe"
                        + " refuses the second window)");
        check(r, OpsJoinSelf.dialPublic(live("192.168.1.5", 46337), typed, found) == null
                        && OpsJoinSelf.dialPublic(live("0.0.0.0", 46337), typed, found) == null,
                "a serve not bound to the probe's TUN address keeps the local dial (flips if a LAN / wildcard serve's"
                        + " second window dials the VPN exit)");
        String deadSecond = refusal(() -> OpsJoinSelf.dialPublic(tunLive, typed, moved));
        check(r, deadSecond.startsWith("ProtonVPN now forwards "),
                "window 2 over a probe forwarding another port = the PACKAGE dead-port refusal (flips if the brick"
                        + " check is skipped on the second window)");
        Map<String, String> otherBind = Map.of(
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM, "protonvpn",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND, "10.2.0.7",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, "203.0.113.5:46337");
        check(r, !OpsJoinSelf.tunMode(otherBind, "10.2.0.9") && OpsJoinSelf.tunMode(tun, "10.2.0.9")
                        && !OpsJoinSelf.tunMode(Map.of(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM, "protonvpn",
                                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND, " "), " "),
                "TUN mode needs the saved bind host to EQUAL the live host and be non-blank (flips if either"
                        + " half of the predicate is dropped)");
        OpsSnapshot spaced = live("bad host", 46337);
        if (!"bad host".equals(spaced.host())) {
            throw new SetupFailure("the snapshot fixture did not keep the host 'bad host': " + spaced.host(), null);
        }
        check(r, "The server address bad host cannot be dialled by the join window".equals(
                        refusal(() -> OpsJoinSelf.dial(spaced, Map.of(), null))),
                "a host with a space = the N7 refusal (flips if the dial-host charset check is dropped)");
        String longHost = "h".repeat(300);
        String longRefusal = refusal(() -> OpsJoinSelf.dial(live(longHost, 46337), Map.of(), null));
        check(r, longRefusal.startsWith("The server address ") && longRefusal.length() <= 120,
                "an over-long host = the N7 refusal, at most 120 chars (flips if the 253-char bound or the"
                        + " shown-host cut is lost): " + longRefusal.length());
    }

    /**
     * {@code join-watch-dial}: {@link OpsJoinSelf#pressWatch} (each window's step 5 over the fakes) on a serve bound
     * to the VPN's TUN address with the ProtonVPN Fill rows saved - window 1 dials the bind's own endpoint and asks
     * no probe, window 2 the probe's public exit; a wildcard bind's window 1 dials 127.0.0.1; a LONE watch window
     * keeps the TUN hairpin. WHY: one window local, one through the VPN - a hairpin on both leaves the local path
     * unwatched.
     */
    private static void joinWatchDial(String r, Path base) throws Exception {
        Path install1 = stageInstall(base.resolve("game1"), true, true);
        Path install2 = stageInstall(base.resolve("game2"), true, true);
        Path flight = base.resolve("flight");
        Map<String, String> tun = new HashMap<>(Map.of(
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM, "protonvpn",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND, "10.2.0.9",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, "203.0.113.5:46337",
                ServeLauncher.ROW_GAME_ROOT_SECOND, install2.toString()));
        VpnLeaseProbe.Probe found = new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 46337, null, 0.5);
        String local = String.format(Locale.ROOT, OpsJoinSelf.NOTE_OPENED, "10.2.0.9", 46337);
        String vpn = String.format(Locale.ROOT, OpsJoinSelf.NOTE_OPENED, "203.0.113.5", 46337);

        FakeEffects fx = twoKits(install1, install2);
        fx.probe = found;
        Pressed two = pressWatch(inputs(base, install1, live("10.2.0.9", 46337), PW, tun), fx, flight);
        check(r, two.result.outcome() == Outcome.OPENED && fx.spawns.size() == 2
                        && (local + " " + OpsJoinSelf.SECOND_PREFIX + vpn).equals(two.result.text()),
                "a two-window watch on the VPN address with the ProtonVPN rows saved: window 1 dials the bind"
                        + " 10.2.0.9:46337, window 2 the public exit 203.0.113.5:46337 (flips if window 1 takes the TUN"
                        + " hairpin - both windows ride the VPN - or window 2 loses its public dial): "
                        + two.result.outcome() + " " + two.result.text());
        check(r, fx.probeCalls == 1 && hasRow(two.stdout, "cc-ops join first_dial=local")
                        && hasRow(two.stdout, "cc-ops join second_dial=vpn")
                        && !two.stdout.contains("10.2.0.9") && !two.stdout.contains("203.0.113.5"),
                "window 1 asks no probe and says first_dial=local, window 2 probes once and says second_dial=vpn, no"
                        + " address on stdout (flips if window 1 probes - a silent gateway would refuse it R10 - or a"
                        + " dial row is lost or carries the address): probeCalls=" + fx.probeCalls);

        FakeEffects wildFx = twoKits(install1, install2);
        wildFx.probe = found;
        Pressed wild = pressWatch(inputs(base, install1, live("0.0.0.0", 46337), PW, tun), wildFx, flight);
        String loop = String.format(Locale.ROOT, OpsJoinSelf.NOTE_OPENED, "127.0.0.1", 46337);
        check(r, wild.result.outcome() == Outcome.OPENED
                        && (loop + " " + OpsJoinSelf.SECOND_PREFIX + loop).equals(wild.result.text())
                        && hasRow(wild.stdout, "cc-ops join first_dial=local")
                        && hasRow(wild.stdout, "cc-ops join second_dial=local"),
                "a wildcard bind's two windows both dial 127.0.0.1:46337 (flips if window 1's local dial passes"
                        + " 0.0.0.0 to the window as a host, or window 2 dials the VPN exit off a serve not on the TUN"
                        + " address): " + wild.result.text());

        Map<String, String> lone = new HashMap<>(tun);
        lone.put(ServeLauncher.ROW_GAME_ROOT_SECOND, "  ");
        FakeEffects loneFx = FakeEffects.withKit(install1);
        loneFx.probe = found;
        Pressed one = pressWatch(inputs(base, install1, live("10.2.0.9", 46337), PW, lone), loneFx, flight);
        check(r, one.result.outcome() == Outcome.OPENED && vpn.equals(one.result.text()) && loneFx.probeCalls == 1
                        && !one.stdout.contains("first_dial="),
                "a LONE watch window on the VPN address keeps the TUN hairpin through the probe's public exit and says"
                        + " no first_dial row (flips if the local dial is set on a window with no second one): "
                        + one.result.text() + " probeCalls=" + loneFx.probeCalls);
    }

    // ---- join-merge ----

    private static void joinMerge(String r) {
        byte[] fresh = OpsJoinSelf.merge(null, "h", 1, "");
        check(r, Arrays.equals(fresh, ascii("host=h\r\nport=1\r\n")),
                "an absent file + no password = exactly host/port CRLF rows (flips if the row order, the CRLF or"
                        + " the trailing terminator drifts): " + shown(fresh));
        check(r, !new String(fresh, StandardCharsets.ISO_8859_1).contains("password"),
                "no password given = no password row of ours (flips if a blank password row is written)");
        byte[] armed = OpsJoinSelf.merge(ascii("host=a\r\nport=2\r\nship=x\r\nweird=a\\\\b\r\n"), "h", 1, PW);
        check(r, Arrays.equals(armed, ascii("ship=x\r\nweird=a\\\\b\r\nhost=h\r\nport=1\r\npassword=" + PW
                        + "\r\npasswordFor=h\\:1\r\n")),
                "other rows kept byte-verbatim (an EVEN backslash run untouched), the new rows LAST in order"
                        + " host, port, password, passwordFor (flips if a kept row is re-escaped or the order"
                        + " drifts): " + shown(armed));
        byte[] replaced = OpsJoinSelf.merge(ascii("password=old\r\npasswordFor=a\\:2\r\n"), "h", 1, PW);
        check(r, Arrays.equals(replaced, ascii("host=h\r\nport=1\r\npassword=" + PW + "\r\npasswordFor=h\\:1\r\n")),
                "a new password DROPS the old password + passwordFor rows (flips if the armed denylist loses"
                        + " password|passwordFor): " + shown(replaced));
        byte[] frozen = OpsJoinSelf.merge(ascii("host=a\r\nport=2\r\npassword=old\r\n"), "h", 1, "");
        check(r, Arrays.equals(frozen, ascii("password=old\r\npasswordFor=a\\:2\r\nhost=h\r\nport=1\r\n")),
                "no password + an old password with no passwordFor = the FREEZE row binding it to the OLD"
                        + " host:port, the old password KEPT (flips if the freeze is lost): " + shown(frozen));
        byte[] lastWins = OpsJoinSelf.merge(
                ascii("host=a\r\nhost=b\r\nport=2\r\nport=3\r\npassword=old\r\n"), "h", 1, "");
        check(r, new String(lastWins, StandardCharsets.ISO_8859_1).startsWith("password=old\r\npasswordFor=b\\:3\r\n"),
                "the FREEZE binds the LAST host / port values, as Properties.load (flips if the first row is"
                        + " taken): " + shown(lastWins));
        byte[] emptyFor = OpsJoinSelf.merge(
                ascii("host=a\r\nport=2\r\npassword=old\r\npasswordFor=\r\n"), "h", 1, "");
        check(r, Arrays.equals(emptyFor, ascii("password=old\r\npasswordFor=\r\nhost=h\r\nport=1\r\n")),
                "an EMPTY passwordFor row binds nothing and is never widened by a freeze (flips if the freeze"
                        + " tests the value instead of the row's presence): " + shown(emptyFor));
        byte[] odd = OpsJoinSelf.merge(ascii("k=v\\\r\n"), "h", 1, "");
        check(r, Arrays.equals(odd, ascii("k=v\\\\\r\nhost=h\r\nport=1\r\n")),
                "a kept row ending in ONE backslash gets one more, so no continuation swallows host= (flips if"
                        + " the odd-run guard is lost): " + shown(odd));
        byte[] colon = OpsJoinSelf.merge(null, "h:x", 1, PW);
        check(r, new String(colon, StandardCharsets.ISO_8859_1).endsWith("password=" + PW + "\r\npasswordFor=h\\:x\\:1\r\n"),
                "a colon in the dial host is written '\\:' in passwordFor (flips if the host's colons are not"
                        + " escaped): " + shown(colon));
        byte[] unlawful = OpsJoinSelf.merge(null, "h", 1, "bad pass!");
        check(r, Arrays.equals(unlawful, ascii("host=h\r\nport=1\r\n")),
                "an unlawful password is treated as blank (flips if merge writes a password the server's"
                        + " sidecar law refuses): " + shown(unlawful));
        byte[] latin = {'n', '=', (byte) 0xE9, '\r', 'H', 'O', 'S', 'T', '=', 'z', '\n'};
        byte[] verbatim = OpsJoinSelf.merge(latin, "h", 1, "");
        byte[] wantLatin = {'n', '=', (byte) 0xE9, '\r', '\n', 'h', 'o', 's', 't', '=', 'h', '\r', '\n',
            'p', 'o', 'r', 't', '=', '1', '\r', '\n'};
        check(r, Arrays.equals(verbatim, wantLatin),
                "a non-ASCII byte survives verbatim, a lone CR ends a row and HOST= is dropped case-blind as the"
                        + " golden's -match (flips if rows are decoded lossy or split on LF only)");
    }

    // ---- join-spawn-shape ----

    private static void joinSpawnShape(String r, Path base) throws Exception {
        List<String> want = List.of("C:\\Windows\\System32\\cmd.exe", "/d", "/c", "start", "/wait",
                "\"AlphaCoreMP join\"", "cmd.exe", "/d", "/c", ".\\join-cc.bat");
        // The fixed spawn argv, as the design spells it; the bat rides its explicit relative
        // path - a bare name is a PATH lookup that NoDefaultCurrentDirectoryInExePath excludes the kit folder from.
        // /wait keeps the outer cmd alive as long as the window: its handle is the window's.
        //
        check(r, want.equals(OpsJoinSelf.argv("C:\\Windows")) && want.equals(OpsJoinSelf.argv("C:\\Windows\\"))
                        && want.equals(OpsJoinSelf.argv("C:\\Windows", false)),
                "the argv is the design's EXACTLY, 10 elements, the title ONE quoted element after /wait (flips if an"
                        + " element is added, dropped, re-quoted or reordered - a lost /wait leaves the console no"
                        + " handle on the window; or if this JVM carries cc.ops.joinWindowMinimized): "
                        + OpsJoinSelf.argv("C:\\Windows"));
        // The proof chain's leg sets -Dcc.ops.joinWindowMinimized=true; /min rides start's own
        // switch slot, before /wait, and nothing else moves (the console's outer-cmd match reads ' start ' + the title).
        List<String> wantMin = new ArrayList<>(want);
        wantMin.add(4, "/min");
        check(r, wantMin.equals(OpsJoinSelf.argv("C:\\Windows", true)),
                "the minimized argv is the design's with ONE /min between start and /wait, 11 elements (flips if /min"
                        + " lands elsewhere or anything else changes): " + OpsJoinSelf.argv("C:\\Windows", true));
        Path game = Paths.get("C:\\Games\\Starsector");
        check(r, OpsJoinSelf.cwd(game).equals(game.resolve("mods").resolve("CC_Client")),
                "the cwd is <install>\\mods\\CC_Client (flips if the bat is not found where the kit puts it)");
        Map<String, String> parent = new LinkedHashMap<>();
        parent.put("CC_JOIN_DRYRUN", "1");
        parent.put("cc_x", "2");
        parent.put("Cc_Y", "3");
        parent.put("JAVA_TOOL_OPTIONS", "-Xmx1g");
        parent.put("PATH", "p");
        check(r, OpsJoinSelf.childEnv(parent).equals(Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g", "PATH", "p")),
                "the child env drops every CC_* key case-blind and keeps JAVA_TOOL_OPTIONS (flips if the cc_ strip"
                        + " is lost or made case-sensitive): " + OpsJoinSelf.childEnv(parent).keySet());
        Path install = stageInstall(base.resolve("game"), true, true);
        FakeEffects fx = FakeEffects.withKit(install);
        fx.env.putAll(parent);
        Pressed pressed = press(inputs(base, install, live("192.168.1.5", 46337), PW, Map.of()), fx);
        check(r, pressed.result.outcome() == Outcome.OPENED && fx.spawns.size() == 1,
                "the fixture press opens the window: " + pressed.result);
        Spawn s = fx.spawns.get(0);
        check(r, s.argv.equals(want) && s.cwd.equals(install.resolve("mods").resolve("CC_Client")),
                "the press spawns the fixed argv in the kit folder (flips if press builds its own argv / cwd)");
        boolean clean = true;
        for (String a : s.argv) {
            clean &= !a.contains(PW) && !a.contains("192.168.1.5");
        }
        for (Map.Entry<String, String> e : s.env.entrySet()) {
            clean &= !e.getValue().contains(PW) && !e.getValue().contains("192.168.1.5") && !e.getKey().contains(PW);
        }
        check(r, clean && !s.env.containsKey("CC_JOIN_DRYRUN"),
                "no argv element, env key or env value carries the password or the dial host, and no CC_* key"
                        + " reaches the child (flips if the dial or the secret is passed on the command line or"
                        + " the env)");
        check(r, !pressed.stdout.contains(PW) && !pressed.stdout.contains("192.168.1.5")
                        && !pressed.result.text().contains(PW),
                "neither the stdout rows nor the note carry the password or the dial host (flips if the press"
                        + " logs its inputs)");
        byte[] written = fx.files.get(settings(base));
        check(r, written != null && new String(written, StandardCharsets.ISO_8859_1).contains("password=" + PW + "\r\n"),
                "control: the password DID reach its one designed place, the settings file's password= row"
                        + " (flips if the secret's path is cut, which would make the checks above vacuous)");
    }

    // ---- join-client-diag ----

    /** {@link OpsJoinSelf.Effects#withClientDiag}: the ONE key lands after the CC_* strip with both folders made
     *  first, a folder the JTO cannot carry adds nothing and makes nothing, null leaves the env equal to an
     *  undecorated press's. */
    private static void joinClientDiag(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        OpsSnapshot lan = live("192.168.1.5", 46337);
        String key = OpsJoinSelf.CLIENT_JVM_FLAGS_KEY;
        // (a) a lawful absolute folder WITH A SPACE (a host's profile path): the key is present after the
        // strip while an inherited one still drops; each flag double-quoted = one JTO token
        Path diag = base.resolve("flight one").resolve("client");
        FakeEffects fx = FakeEffects.withKit(install);
        fx.env.put(key, "-Dinherited=1");
        Pressed a = press(inputs(base, install, lan, PW, Map.of()), fx.withClientDiag(diag));
        String flags = a.result.outcome() == Outcome.OPENED && fx.spawns.size() == 1
                ? fx.spawns.get(0).env.get(key) : null;
        String want = "\"-Xlog:gc*,safepoint:file=" + diag + "\\gclog-%p-%t.log:time,uptime\" \"-Dcc.markframes.dir="
                + diag + "\\frames\"";
        check(r, want.equals(flags) && List.of(diag.resolve("frames")).equals(fx.dirsMade)
                        && !a.stdout.contains("diag="),
                "the diag press spawns with the GC-log + frames flags under the given spaced folder, each flag"
                        + " double-quoted, the inherited value gone, the frames folder (and so the diag folder) made"
                        + " through the seam, no skipped row (flips if the key rides the strip, the flag text or its"
                        + " quoting drifts, the mkdir is lost or a spaced path is refused): " + flags + " " + fx.dirsMade);
        // (b) a folder outside the carried class (parentheses): the window still opens, no key, no folder, one
        // token row without the path
        Path odd = base.resolve("flight(two)").resolve("client");
        FakeEffects fxOdd = FakeEffects.withKit(install);
        Pressed b = press(inputs(base, install, lan, PW, Map.of()), fxOdd.withClientDiag(odd));
        check(r, b.result.outcome() == Outcome.OPENED && fxOdd.spawns.size() == 1
                        && !fxOdd.spawns.get(0).env.containsKey(key) && fxOdd.dirsMade.isEmpty()
                        && b.stdout.contains("cc-ops join diag=skipped_path") && !b.stdout.contains("flight(two)"),
                "a folder the JTO cannot carry still opens the window but adds no key, makes no folder and prints"
                        + " one token row without the path (flips if the path law is lost or the mkdir runs before"
                        + " the check): " + b.stdout.trim());
        // (c) null = the undecorated press's env, byte for byte
        FakeEffects fxNull = FakeEffects.withKit(install);
        fxNull.env.put(key, "-Dinherited=1");
        press(inputs(base, install, lan, PW, Map.of()), fxNull.withClientDiag(null));
        FakeEffects fxPlain = FakeEffects.withKit(install);
        fxPlain.env.put(key, "-Dinherited=1");
        press(inputs(base, install, lan, PW, Map.of()), fxPlain);
        check(r, fxNull.spawns.size() == 1 && fxPlain.spawns.size() == 1
                        && fxNull.spawns.get(0).env.equals(fxPlain.spawns.get(0).env)
                        && !fxNull.spawns.get(0).env.containsKey(key),
                "a null folder leaves the child env equal to an undecorated press's, the inherited key dropped by"
                        + " both (flips if null decorates or the strip is lost)");
    }

    // ---- join-order ----

    private static void joinOrder(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        OpsSnapshot lan = live("192.168.1.5", 46337);
        Path settings = settings(base);

        // (a) a live window: REOPENED, nothing written, nothing spawned
        FakeEffects live = FakeEffects.withKit(install);
        Path endpoint = OpsJoinSelf.endpointFile(base.resolve("lad").toString(), install);
        live.files.put(endpoint, ascii("46000|" + TOKEN + "|" + NONCE));
        live.pings.put(46000, "{\"ok\":true,\"app\":\"join\"}");
        Pressed a = press(inputs(base, install, lan, PW, Map.of()), live);
        check(r, a.result.outcome() == Outcome.REOPENED && live.writes.isEmpty() && live.spawns.isEmpty()
                        && ("http://127.0.0.1:46000/" + TOKEN + "/").equals(a.result.liveUrlOrNull())
                        && OpsJoinSelf.NOTE_REOPENED.equals(a.result.text()),
                "(a) a live join window = REOPENED with its URL, 0 writes, 0 spawns (flips if the live-window"
                        + " step moves after the write or is dropped): " + a.result);
        stdoutLaw(r, "a", a, "reopened", "-", 46000);
        check(r, !a.stdout.contains(TOKEN), "(a) the window's token never reaches stdout");

        // (a2) an endpoint whose port answers another app: not ours, the press goes on
        FakeEffects other = FakeEffects.withKit(install);
        other.files.put(endpoint, ascii("46000|" + TOKEN));
        other.pings.put(46000, "{\"app\":\"editor\"}");
        Pressed a2 = press(inputs(base, install, lan, PW, Map.of()), other);
        check(r, a2.result.outcome() == Outcome.OPENED && other.writes.size() == 1 && other.spawns.size() == 1,
                "(a2) a ping answered by another app is no live window (flips if the \"app\":\"join\" test is"
                        + " dropped): " + a2.result);

        // (b) R2: nothing read, written or started
        FakeEffects noBat = FakeEffects.withKit(install);
        noBat.files.remove(install.resolve("mods").resolve("CC_Client").resolve("join-cc.bat"));
        Pressed b = press(inputs(base, install, lan, PW, Map.of()), noBat);
        check(r, b.result.outcome() == Outcome.REFUSED && "r2".equals(b.result.reasonToken())
                        && b.result.text().startsWith("No player kit in this game: ")
                        && noBat.reads.isEmpty() && noBat.writes.isEmpty() && noBat.spawns.isEmpty(),
                "(b) no kit = R2 REFUSED, 0 reads, 0 writes, 0 spawns (flips if the install check moves after"
                        + " a read / write / spawn): " + b.result + " reads=" + noBat.reads.size());
        stdoutLaw(r, "b", b, "refused", "r2", 46337);

        // (c) no live serve: R4
        FakeEffects down = FakeEffects.withKit(install);
        Pressed c = press(inputs(base, install, null, PW, Map.of()), down);
        check(r, c.result.outcome() == Outcome.REFUSED && OpsJoinSelf.R4.equals(c.result.text())
                        && "r4".equals(c.result.reasonToken()) && down.writes.isEmpty() && down.spawns.isEmpty(),
                "(c) no live serve = R4, 0 writes, 0 spawns (flips if the live gate is dropped): " + c.result);

        // (d) a failed write: R6 naming the class, no spawn
        FakeEffects badWrite = FakeEffects.withKit(install);
        badWrite.writeFailure = new IOException("disk full");
        Pressed d = press(inputs(base, install, lan, PW, Map.of()), badWrite);
        check(r, d.result.outcome() == Outcome.REFUSED && d.result.text().startsWith(
                        "Your saved join settings could not be updated (IOException) - nothing was started.")
                        && badWrite.spawns.isEmpty(),
                "(d) a failed settings write = R6 naming IOException, 0 spawns (flips if a failed write"
                        + " continues to the spawn): " + d.result);
        stdoutLaw(r, "d", d, "refused", "r6_write", 46337);

        // (d2) an over-cap settings file: R6, the file untouched
        FakeEffects big = FakeEffects.withKit(install);
        byte[] huge = new byte[OpsJoinSelf.SETTINGS_MAX_BYTES + 1];
        big.files.put(settings, huge);
        Pressed d2 = press(inputs(base, install, lan, PW, Map.of()), big);
        check(r, d2.result.outcome() == Outcome.REFUSED && "r6_read".equals(d2.result.reasonToken())
                        && big.writes.isEmpty() && big.spawns.isEmpty() && big.files.get(settings) == huge,
                "(d2) a settings file over 64 KiB = R6, never rewritten, 0 spawns (flips if the N3 bound is"
                        + " dropped): " + d2.result);

        // (e) the cmd exits 1 within the wait: R7 naming 1, after exactly one write
        FakeEffects exit1 = FakeEffects.withKit(install);
        exit1.spawnExit = 1;
        Pressed e = press(inputs(base, install, lan, PW, Map.of()), exit1);
        check(r, e.result.outcome() == Outcome.REFUSED && e.result.text().startsWith(
                        "Windows did not open the join window (1) - run ") && exit1.writes.size() == 1
                        && "spawn_nonzero".equals(e.result.reasonToken()),
                "(e) a nonzero exit = R7 naming the code, exactly 1 write (flips if a failed start reads as"
                        + " opened): " + e.result);

        // (f) still running after the wait: OPENED, one write, one spawn
        FakeEffects running = FakeEffects.withKit(install);
        Pressed f = press(inputs(base, install, lan, PW, Map.of()), running);
        check(r, f.result.outcome() == Outcome.OPENED && running.writes.size() == 1 && running.spawns.size() == 1
                        && "Join window opening (it dials 192.168.1.5:46337) - press Launch in it."
                                .equals(f.result.text()),
                "(f) still running = OPENED with the dial note, 1 write, 1 spawn (flips if -1 reads as a"
                        + " failure or the note drifts): " + f.result);
        stdoutLaw(r, "f", f, "opened", "-", 46337);

        // (g) TUN mode, the probe throws: R10, nothing written or started
        FakeEffects silent = FakeEffects.withKit(install);
        silent.probeFailure = new IOException("gateway silent");
        Map<String, String> tunRows = Map.of(
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM, "protonvpn",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND, "10.2.0.9",
                ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, "203.0.113.5:46337");
        Pressed g = press(inputs(base, install, live("10.2.0.9", 46337), PW, tunRows), silent);
        check(r, g.result.outcome() == Outcome.REFUSED && OpsJoinSelf.R10.equals(g.result.text())
                        && "r10".equals(g.result.reasonToken()) && silent.probeCalls == 1
                        && silent.writes.isEmpty() && silent.spawns.isEmpty(),
                "(g) TUN mode + a silent gateway = R10, 0 writes, 0 spawns (flips if a failed probe dials the"
                        + " saved row or the probe is skipped): " + g.result);
        stdoutLaw(r, "g", g, "refused", "r10", 46337);
        check(r, !g.stdout.contains("10.2.0.9") && !g.stdout.contains("203.0.113.5"),
                "(g) neither the TUN host nor the public exit reaches stdout");

        // (h2) a LAN press never asks the probe
        check(r, running.probeCalls == 0, "a non-TUN press never asks the ProtonVPN probe (flips if every press"
                + " probes)");

        // (i) the step list, every step pinned by name
        List<String> names = new ArrayList<>();
        for (OpsJoinSelf.Step step : OpsJoinSelf.STEPS) {
            names.add(step.name());
        }
        check(r, names.equals(List.of("step1Install", "step1cKitCurrent", "step2LiveWindow", "step3LiveGate",
                        "step4Password", "step5Dial", "step6Write", "step6bArm", "step7Spawn")),
                "(i) the press order is the design's: nine steps, the kit check second and the seat-key arm between"
                        + " the settings write and the spawn (flips if a step is dropped, renamed, reordered or added -"
                        + " a kit match-file step back in the list, the kit check after a write, an arm after the spawn"
                        + " reaches the serve after the window's hello, an arm before the write names a key the file"
                        + " does not hold): " + names);
        // The kit check with no package stamps continues, zero reads
        FakeEffects noStamps = new FakeEffects();
        Printed kc = printed(() -> String.valueOf(stepNamed("step1cKitCurrent").run(
                new OpsJoinSelf.Press(inputs(base, install, lan, PW, Map.of()), noStamps))));
        check(r, "null".equals(kc.value()) && kc.stdout().contains("cc-ops join kit_check=skipped")
                        && noStamps.reads.isEmpty(),
                "(i) step1cKitCurrent with no package stamps returns null = continue, one skipped row, zero reads"
                        + " (flips if the check stops a stamp-less press or reads the kit first): " + kc.value() + " "
                        + noStamps.reads);

        // (j) the legacy settings file: written in place when the current one is absent
        FakeEffects legacy = FakeEffects.withKit(install);
        Path old = base.resolve("ad").resolve("cc-starsector").resolve("join-settings.properties");
        legacy.files.put(old, ascii("cid=abc\r\n"));
        Pressed j = press(inputs(base, install, lan, "", Map.of()), legacy);
        check(r, j.result.outcome() == Outcome.OPENED && legacy.writes.equals(List.of(old))
                        && new String(legacy.files.get(old), StandardCharsets.ISO_8859_1).startsWith("cid=abc\r\n"),
                "(j) a legacy %APPDATA% file with no current one is merged IN PLACE (the window migrates it;"
                        + " flips if the current path is written and the window then deletes the cid's file): "
                        + legacy.writes);

        joinOrderWithProbe(r, base, install, settings, tunRows);
    }

    /**
     * {@code join-order} (k): the shells' shape - {@code press} through {@code fake.withProbe(call)}
     * ({@code OpsJoinSelf.LIVE.withProbe} in Swing and the web console) in TUN mode. The wrapper's answer reaches the
     * dial, a wrapper that throws is R10 with no write and no spawn, and every other seam call reaches the wrapped
     * fake exactly as an unwrapped press records it (the delegation law).
     */
    private static void joinOrderWithProbe(String r, Path base, Path install, Path settings, Map<String, String> tunRows) {
        VpnLeaseProbe.Probe exit = new VpnLeaseProbe.Probe("203.0.113.5", "10.2.0.9", 46337, null, 0.5);
        FakeEffects direct = FakeEffects.withKit(install);
        direct.probe = exit;
        Pressed k0 = press(inputs(base, install, live("10.2.0.9", 46337), PW, tunRows), direct);
        check(r, k0.result.outcome() == Outcome.OPENED && direct.probeCalls == 1,
                "(k) control: an unwrapped TUN press with that probe answer OPENS (flips if the TUN dial path"
                        + " breaks, which would make the wrapped legs vacuous): " + k0.result);

        FakeEffects wrapped = FakeEffects.withKit(install);
        wrapped.probeFailure = new IOException("the wrapped fake's own probe was asked");
        int[] wrapperCalls = {0};
        Pressed k = press(inputs(base, install, live("10.2.0.9", 46337), PW, tunRows), wrapped.withProbe(() -> {
            wrapperCalls[0]++;
            return exit;
        }));
        check(r, k.result.outcome() == Outcome.OPENED && wrapperCalls[0] == 1 && wrapped.probeCalls == 0
                        && "Join window opening (it dials 203.0.113.5:46337) - press Launch in it."
                                .equals(k.result.text()),
                "(k) TUN mode through withProbe: the WRAPPER's answer reaches the dial and the wrapped fake's probe is"
                        + " never asked (flips if withProbe delegates probe() or the press ignores the effects it was"
                        + " handed): " + k.result + " wrapperCalls=" + wrapperCalls[0]);
        byte[] written = wrapped.files.get(settings);
        check(r, written != null && new String(written, StandardCharsets.ISO_8859_1)
                        .contains("host=203.0.113.5\r\nport=46337\r\n"),
                "(k) the probe's public endpoint is the one written to the settings file (flips if the saved row or"
                        + " the TUN host is written instead)");
        check(r, wrapped.reads.equals(direct.reads) && wrapped.writes.equals(direct.writes)
                        && wrapped.spawns.equals(direct.spawns) && Arrays.equals(written, direct.files.get(settings)),
                "(k) every other seam call reaches the wrapped fake exactly as the unwrapped press recorded it (the"
                        + " delegation law; flips if withProbe drops, re-targets or doubles a read / write / spawn): reads="
                        + wrapped.reads + " vs " + direct.reads + " writes=" + wrapped.writes + " vs " + direct.writes);
        stdoutLaw(r, "k", k, "opened", "-", 46337);
        check(r, !k.stdout.contains("10.2.0.9") && !k.stdout.contains("203.0.113.5"),
                "(k) neither the TUN host nor the public exit reaches stdout");

        FakeEffects refused = FakeEffects.withKit(install);
        refused.probe = exit; // a regression that asks the wrapped fake would dial and spawn
        Pressed k2 = press(inputs(base, install, live("10.2.0.9", 46337), PW, tunRows), refused.withProbe(() -> {
            throw new IOException("x");
        }));
        check(r, k2.result.outcome() == Outcome.REFUSED && OpsJoinSelf.R10.equals(k2.result.text())
                        && "r10".equals(k2.result.reasonToken()) && refused.probeCalls == 0
                        && refused.writes.isEmpty() && refused.spawns.isEmpty(),
                "(k) a wrapper that THROWS IOException = R10, 0 writes, 0 spawns (flips if the wrapper's refusal falls"
                        + " back to the wrapped fake's probe or to a dial): " + k2.result);
        stdoutLaw(r, "k2", k2, "refused", "r10", 46337);
    }

    /** ONE {@code cc-ops join outcome=} row with the expected outcome + reason, and no port digits, host or
     *  password in any stdout row. */
    private static void stdoutLaw(String r, String tag, Pressed p, String outcome, String reason, int port) {
        List<String> rows = p.stdout.lines().filter(l -> l.startsWith("cc-ops join outcome=")).toList();
        check(r, rows.equals(List.of("cc-ops join outcome=" + outcome + " reason=" + reason)),
                "(" + tag + ") ONE stdout row 'outcome=" + outcome + " reason=" + reason + "' (flips if the press"
                        + " row is lost, doubled or mis-tokened): " + p.stdout.strip());
        check(r, !p.stdout.contains(Integer.toString(port)) && !p.stdout.contains("192.168.1.5")
                        && !p.stdout.contains("127.0.0.1") && !p.stdout.contains(PW),
                "(" + tag + ") the stdout rows carry no port, host or password (flips if the dial or the secret"
                        + " is logged): " + p.stdout.strip());
    }

    // ---- join-settings-home ----

    /**
     * {@code join-settings-home}: the join window's settings path, {@code JoinSettings.settingsBase}'s precedence
     * arm by arm (an ABSOLUTE {@code LOCALAPPDATA}; else a non-blank
     * {@code APPDATA} AS GIVEN - that arm tests blankness only; else the home), the legacy {@code %APPDATA%} file
     * ({@code pathForRun}: an absolute {@code APPDATA} only) and the run's pick ({@code settingsForRun})
     * over fake files, which the core never moves.
     */
    private static void joinSettingsHome(String r, Path base) {
        String lad = base.resolve("lad").toString();
        String ad = base.resolve("ad").toString();
        String home = base.resolve("home").toString();
        Path inLad = settingsUnder(base.resolve("lad"));
        Path inAd = settingsUnder(base.resolve("ad"));
        Path inHome = settingsUnder(base.resolve("home"));
        check(r, "cc-starsector".equals(OpsJoinSelf.SETTINGS_DIR_NAME)
                        && "join-settings.properties".equals(OpsJoinSelf.SETTINGS_FILE_NAME),
                "(1) the name constants equal JoinSettings.DIR_NAME / FILE_NAME as JoinSettings declares them"
                        + " (flips if the console's copy of the settings folder or file name drifts): "
                        + OpsJoinSelf.SETTINGS_DIR_NAME + " / " + OpsJoinSelf.SETTINGS_FILE_NAME);
        check(r, inLad.equals(OpsJoinSelf.settingsFile(lad, ad, home)),
                "(2) an absolute LOCALAPPDATA wins over APPDATA and the home (flips if the roaming APPDATA is"
                        + " preferred): " + OpsJoinSelf.settingsFile(lad, ad, home));
        for (String notAbsolute : Arrays.asList((String) null, "", "   ", "lad", "\\lad")) {
            check(r, inAd.equals(OpsJoinSelf.settingsFile(notAbsolute, ad, home)),
                    "(3) a LOCALAPPDATA of " + shownEnv(notAbsolute) + " is no absolute folder, so APPDATA is next"
                            + " (flips if a blank, relative or drive-relative value is used - it would resolve"
                            + " against the console's cwd): " + OpsJoinSelf.settingsFile(notAbsolute, ad, home));
        }
        for (String blank : Arrays.asList((String) null, "", "   ")) {
            check(r, inHome.equals(OpsJoinSelf.settingsFile("lad", blank, home)),
                    "(4) no usable LOCALAPPDATA and an APPDATA of " + shownEnv(blank) + " = the home (flips if a"
                            + " blank APPDATA is used as a folder): " + OpsJoinSelf.settingsFile("lad", blank, home));
        }
        check(r, settingsUnder(Paths.get("rel")).equals(OpsJoinSelf.settingsFile(null, "rel", home)),
                "(5) a relative APPDATA is used AS GIVEN, as the client's settingsBase does (flips if the console's"
                        + " copy gates that arm on absoluteness and the client does not): "
                        + OpsJoinSelf.settingsFile(null, "rel", home));
        check(r, inAd.equals(OpsJoinSelf.legacySettingsFile(ad)),
                "(6) the legacy file is <APPDATA>\\cc-starsector\\join-settings.properties (flips if the legacy"
                        + " path drifts from JoinSettings.pathForRun's): " + OpsJoinSelf.legacySettingsFile(ad));
        for (String notAbsolute : Arrays.asList((String) null, "", "   ", "ad", "\\ad")) {
            check(r, OpsJoinSelf.legacySettingsFile(notAbsolute) == null,
                    "(7) an APPDATA of " + shownEnv(notAbsolute) + " names no legacy file (flips if the legacy gate"
                            + " loses pathForRun's absoluteEnvDir test)");
        }
        FakeEffects fx = new FakeEffects();
        check(r, inLad.equals(OpsJoinSelf.settingsForRun(fx, lad, ad, home)),
                "(8) neither file = the current path (flips if an absent legacy file is picked)");
        fx.files.put(inAd, ascii("cid=abc\r\n"));
        check(r, inAd.equals(OpsJoinSelf.settingsForRun(fx, lad, ad, home)),
                "(9) the current file absent + the legacy one a file = the LEGACY path, left for the window's own"
                        + " migrateLegacy (flips if the legacy arm is lost: the window would then delete the"
                        + " legacy file, cid and all, as a leftover): " + OpsJoinSelf.settingsForRun(fx, lad, ad, home));
        fx.files.put(inLad, ascii("host=h\r\n"));
        check(r, inLad.equals(OpsJoinSelf.settingsForRun(fx, lad, ad, home)),
                "(10) both files present = the current path (flips if a present current file loses to the legacy"
                        + " one)");
        fx.files.remove(inAd);
        check(r, inLad.equals(OpsJoinSelf.settingsForRun(fx, lad, ad, home)),
                "(11) the current file alone = the current path");
        fx.files.remove(inLad);
        fx.files.put(settingsUnder(Paths.get("ad")), ascii("cid=abc\r\n"));
        check(r, inLad.equals(OpsJoinSelf.settingsForRun(fx, lad, "ad", home)),
                "(12) a relative APPDATA names no legacy file even with a file at that relative path (flips if"
                        + " settingsForRun's legacy path skips the absolute gate)");
        check(r, fx.writes.isEmpty() && fx.reads.isEmpty() && fx.spawns.isEmpty(),
                "(13) picking the path moves, reads and writes nothing - the window migrates its own file (flips"
                        + " if the core starts moving the legacy file): writes=" + fx.writes + " reads=" + fx.reads);
    }

    // ---- join-endpoint ----

    /**
     * {@code join-endpoint}: {@code endpointFile} / {@code installTag} against {@code JoinHost.endpointFile} /
     * {@code installTag}: the folder, prefix and suffix, the case fold, normalize
     * and toAbsolutePath, the tag's shape, the null arms; on Windows the client's own golden
     * (the {@code JoinGuiSelftest} endpoint-file row, its literals copied).
     */
    private static void joinEndpoint(String r, Path base) {
        Path lad = base.resolve("lad");
        Path game = base.resolve("game");
        String tag = OpsJoinSelf.installTag(game);
        Path want = lad.resolve("cc-starsector").resolve("join").resolve("cc_join_endpoint-" + tag + ".data");
        check(r, want.equals(OpsJoinSelf.endpointFile(lad.toString(), game)),
                "(1) the endpoint file is <LOCALAPPDATA>\\cc-starsector\\join\\cc_join_endpoint-<tag>.data (flips if"
                        + " the folder, the sub-folder, the prefix or the .data suffix drifts from"
                        + " JoinHost.endpointFile): " + OpsJoinSelf.endpointFile(lad.toString(), game));
        check(r, tag.equals(OpsJoinSelf.installTag(base.resolve("GAME"))),
                "(2) the tag folds case (flips if the Locale.ROOT lower-case fold is lost: a press would miss the"
                        + " live window of the same install typed in another case)");
        check(r, tag.equals(OpsJoinSelf.installTag(game.resolve("mods").resolve(".."))),
                "(3) the tag hashes the normalized path (flips if normalize is dropped)");
        Path relative = Paths.get("join-endpoint-rel");
        check(r, OpsJoinSelf.installTag(relative).equals(OpsJoinSelf.installTag(relative.toAbsolutePath())),
                "(4) a relative install hashes as its absolute form (flips if toAbsolutePath is dropped)");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Path golden = Paths.get("C:\\Games\\Starsector");
            check(r, Paths.get("D:\\Lad\\cc-starsector\\join\\cc_join_endpoint-ecf39d82.data")
                            .equals(OpsJoinSelf.endpointFile("D:\\Lad", golden))
                            && "ecf39d82".equals(OpsJoinSelf.installTag(Paths.get("c:\\GAMES\\Starsector\\mods\\.."))),
                    "(5) the client's golden: LOCALAPPDATA\\cc-starsector\\join, the per-install tag over the"
                            + " lower-cased normalized root (flips if the folder, the hash, the case fold or normalize"
                            + " changes): " + OpsJoinSelf.endpointFile("D:\\Lad", golden));
        }
        for (String notAbsolute : Arrays.asList((String) null, "", "   ", "Lad", "\\Lad")) {
            check(r, OpsJoinSelf.endpointFile(notAbsolute, game) == null,
                    "(6) a LOCALAPPDATA of " + shownEnv(notAbsolute) + " = NO endpoint file, never a fallback into"
                            + " the install or the temp folder (flips if the absolute gate is lost)");
        }
        check(r, OpsJoinSelf.endpointFile(lad.toString(), null) == null,
                "(7) no install = no endpoint file, never a throw (flips if a null install reaches installTag)");
        for (Path install : List.of(game, base.resolve("GAME"), base.resolve("Spiel" + (char) 0xE9),
                base.resolve("a b").resolve("c"), Paths.get("C:\\Games\\Starsector"), relative)) {
            String t = OpsJoinSelf.installTag(install);
            check(r, t.matches("[0-9a-f]{8}"),
                    "(8) the tag is 8 lower-case hex chars for any install (flips if its width or digit case drifts"
                            + " from the client's): " + t);
        }
    }

    // ---- join-ping ----

    /**
     * {@code join-ping}: the REAL loopback ping ({@code OpsJoinSelf.Live.pingBody}) against a real
     * {@link HttpServer} on {@code 127.0.0.1}, port 0, through {@link OpsJoinSelf#liveUrlOrNull} with the endpoint
     * file faked ({@link FakeEffects#livePing}). ONE context {@code /}; {@link #answerPing} picks the answer by the
     * path's token. The handlers run on a daemon pool, so the silent one never blocks {@code stop}; the server is
     * stopped and the pool shut down in {@code finally}. The sizes (1024 / 1025) and the 3000 ms bound are the
     * CLIENT's numbers typed here ({@code JoinBrowserUi.PING_TIMEOUT_MS} 2000 ms, the 1024 B read buffer
     * of its {@code liveWindowUrl}), never the core's constants.
     */
    private static void joinPing(String r) throws Exception {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new SetupFailure("a loopback HttpServer on port 0", e);
        }
        ExecutorService pool = Executors.newCachedThreadPool(job -> {
            Thread t = new Thread(job, "join-ping-selftest");
            t.setDaemon(true);
            return t;
        });
        Map<String, Integer> hits = new ConcurrentHashMap<>();
        boolean stopped = false;
        try {
            server.setExecutor(pool);
            server.createContext("/", ex -> answerPing(ex, hits));
            server.start();
            InetSocketAddress bound = server.getAddress();
            int port = bound.getPort();
            if (!(bound.getAddress() instanceof Inet4Address) || port < 1 || port > 65535) {
                throw new SetupFailure("the listener is not on 127.0.0.1, port 1..65535: " + bound, null);
            }
            FakeEffects fx = new FakeEffects();
            fx.livePing = true;
            Path file = Paths.get("join-ping", "endpoint.data"); // a FAKE file: never on disk

            // (a) the join window answers
            String body = OpsJoinSelf.LIVE.pingBody(port, TOKEN);
            fx.files.put(file, ascii(port + "|" + TOKEN));
            String url = OpsJoinSelf.liveUrlOrNull(fx, file);
            check(r, PING_JOIN.equals(body) && ("http://127.0.0.1:" + port + "/" + TOKEN + "/").equals(url),
                    "(a) GET /<token>/api/ping answered 200 {\"app\":\"join\"} = that body and the window's URL"
                            + " (flips if the ping's path, host or token drifts from the client's): body=" + body
                            + " url=" + (url == null ? "null" : "set"));

            // (b) another app on the port
            String devBody = OpsJoinSelf.LIVE.pingBody(port, TOKEN_DEV);
            fx.files.put(file, ascii(port + "|" + TOKEN_DEV));
            check(r, "{\"app\":\"dev\"}".equals(devBody) && OpsJoinSelf.liveUrlOrNull(fx, file) == null,
                    "(b) a 200 from another app = no live window (flips if the \"app\":\"join\" test is dropped):"
                            + " body=" + devBody);

            // (c) a token the server does not know: 404
            String unknown = OpsJoinSelf.LIVE.pingBody(port, TOKEN_UNKNOWN);
            fx.files.put(file, ascii(port + "|" + TOKEN_UNKNOWN));
            check(r, unknown == null && OpsJoinSelf.liveUrlOrNull(fx, file) == null
                            && hits.getOrDefault("404", 0) > 0,
                    "(c) a wrong token answered 404 = null (flips if a non-200 answer is read as a body): " + unknown);

            // (d) the 1024 B read, both edges: the marker's last byte at 1023 is seen, at 1024 it is not
            String fit = OpsJoinSelf.LIVE.pingBody(port, TOKEN_FIT);
            fx.files.put(file, ascii(port + "|" + TOKEN_FIT));
            String fitUrl = OpsJoinSelf.liveUrlOrNull(fx, file);
            String big = OpsJoinSelf.LIVE.pingBody(port, TOKEN_BIG);
            fx.files.put(file, ascii(port + "|" + TOKEN_BIG));
            String bigUrl = OpsJoinSelf.liveUrlOrNull(fx, file);
            check(r, fit != null && fit.length() == 1024 && fitUrl != null,
                    "(d) a 1024 B body ending in the marker is read whole = a live window (flips if the cap drops"
                            + " under the client's 1024 B): " + (fit == null ? "null" : fit.length() + " B"));
            check(r, big != null && big.length() == 1024 && bigUrl == null,
                    "(d) a 1025 B body is cut at 1024 B, its marker unseen = no live window (flips if the body cap"
                            + " is lost or grows past the client's 1024 B): "
                            + (big == null ? "null" : big.length() + " B") + " url=" + (bigUrl == null ? "null" : "set"));

            // (e) a silent window: the 2000 ms timeout, measured
            fx.files.put(file, ascii(port + "|" + TOKEN_SILENT));
            long t0 = System.nanoTime();
            Printed silent = printed(() -> OpsJoinSelf.liveUrlOrNull(fx, file));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            check(r, silent.value() == null && hits.getOrDefault("silent", 0) == 1 && ms >= 1500 && ms < 3000,
                    "(e) a handler silent for 3500 ms = null within 3000 ms, after a real wait (flips if the connect /"
                            + " read timeout is lost or drifts from the client's 2000 ms): " + ms + " ms, url="
                            + (silent.value() == null ? "null" : "set") + ", hits=" + hits.getOrDefault("silent", 0));
            check(r, silent.stdout().contains("cc-ops join ping=down (") && !silent.stdout().contains(TOKEN_SILENT)
                            && !silent.stdout().contains(Integer.toString(port)),
                    "(e) the timeout prints ONE bounded ping=down row, no token and no port (flips if the ping logs"
                            + " its URL): " + silent.stdout().strip().replace(TOKEN_SILENT, "<token>"));

            // (f) a closed port: null from the seam itself (liveUrlOrNull's own catch would mask a throw)
            server.stop(0);
            stopped = true;
            String closed;
            try {
                closed = OpsJoinSelf.LIVE.pingBody(port, TOKEN);
            } catch (IOException | RuntimeException thrown) {
                closed = "threw " + thrown.getClass().getSimpleName();
            }
            check(r, closed == null,
                    "(f) a closed port = null, never a throw past Live.pingBody (flips if the seam stops catching a"
                            + " refused connect): " + closed);
        } finally {
            if (!stopped) {
                server.stop(0);
            }
            pool.shutdownNow();
        }
    }

    /** {@link #joinPing}'s one handler: the answer is picked by the path's token, an unknown one = 404; the silent
     *  token answers {@link #PING_JOIN} after 3500 ms (past the 2000 ms timeout); an interrupt (the row's pool shut
     *  down) ends it unanswered and is counted. Every hit is counted by its tag.
     * */
    private static void answerPing(HttpExchange ex, Map<String, Integer> hits) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String tag = "404";
            String body = null;
            if (path.equals("/" + TOKEN + "/api/ping")) {
                tag = "join";
                body = PING_JOIN;
            } else if (path.equals("/" + TOKEN_DEV + "/api/ping")) {
                tag = "dev";
                body = "{\"app\":\"dev\"}";
            } else if (path.equals("/" + TOKEN_FIT + "/api/ping")) {
                tag = "fit";
                body = markerAtEnd(1024);
            } else if (path.equals("/" + TOKEN_BIG + "/api/ping")) {
                tag = "big";
                body = markerAtEnd(1025);
            } else if (path.equals("/" + TOKEN_SILENT + "/api/ping")) {
                tag = "silent";
                body = PING_JOIN;
            }
            hits.merge(tag, 1, Integer::sum);
            if ("silent".equals(tag)) {
                try {
                    Thread.sleep(3500);
                } catch (InterruptedException woken) {
                    Thread.currentThread().interrupt();
                    hits.merge("silent-interrupted", 1, Integer::sum);
                    return;
                }
            }
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            ex.close();
        }
    }

    /** {@code total} ASCII bytes ending in {@link #PING_MARKER} (its last byte at index {@code total - 1}). */
    private static String markerAtEnd(int total) {
        return "x".repeat(total - PING_MARKER.length()) + PING_MARKER;
    }

    // ---- join-client-twin ----

    /**
     * {@code join-client-twin}: STATIC over the CLIENT source - {@code OpsJoinSelf} is the
     * THIRD copy of the kit's settings / endpoint-file law, and this row is what makes a kit-side move fail the
     * console's gate. Each pin is a text the client source must hold, read at the pin's {@code file:line}; the
     * name pins are built from {@code OpsJoinSelf}'s constants, so they flip on drift at either end. The source is
     * looked for under the cwd, then under {@code ..} once; absent = ONE skip line and a PASS (the export ships no
     * {@code client-mod/src}: a stranger's build must not fail on it).
     */
    private static void joinClientTwin(String r) throws Exception {
        Path rel = Paths.get("client-mod", "src", "cc", "starsector", "client", "join");
        Path dir = null;
        for (Path root : List.of(Paths.get(""), Paths.get(".."))) {
            Path d = root.resolve(rel);
            if (Files.isRegularFile(d.resolve("JoinSettings.java")) && Files.isRegularFile(d.resolve("JoinHost.java"))) {
                dir = d;
                break;
            }
        }
        if (dir == null) {
            System.out.println("SKIP join-client-twin: client source absent (the export ships no client-mod/src)");
            return;
        }
        Map<String, String> source = new LinkedHashMap<>();
        for (String name : List.of("JoinSettings.java", "JoinHost.java")) {
            try {
                source.put(name, Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new SetupFailure("client source " + name + " unreadable", e);
            }
        }
        String[][] pins = {
            {"JoinSettings.java", "DIR_NAME = \"" + OpsJoinSelf.SETTINGS_DIR_NAME + "\";",
                "JoinSettings.DIR_NAME vs OpsJoinSelf.SETTINGS_DIR_NAME"},
            {"JoinSettings.java", "FILE_NAME = \"" + OpsJoinSelf.SETTINGS_FILE_NAME + "\";",
                "JoinSettings.FILE_NAME vs OpsJoinSelf.SETTINGS_FILE_NAME"},
            {"JoinSettings.java", "settingsBase(System.getenv(\"LOCALAPPDATA\"), System.getenv(\"APPDATA\"),"
                    + " System.getProperty(\"user.home\"))",
                "JoinSettings.defaultPath's env names vs the shells' localAppData / appData / userHome"},
            {"JoinSettings.java", "Path local = absoluteEnvDir(localAppData);",
                "JoinSettings.settingsBase's LOCALAPPDATA gate vs OpsJoinSelf.settingsFile"},
            {"JoinSettings.java", "return appData == null || appData.isBlank() ? Paths.get(userHome) : Paths.get(appData);",
                "JoinSettings.settingsBase's APPDATA / home arms vs OpsJoinSelf.settingsFile"},
            {"JoinSettings.java", "Path appData = absoluteEnvDir(System.getenv(\"APPDATA\"));",
                "JoinSettings.pathForRun's legacy gate vs OpsJoinSelf.legacySettingsFile"},
            {"JoinHost.java", "ENDPOINT_FILE_PREFIX = \"" + OpsJoinSelf.ENDPOINT_FILE_PREFIX + "\";",
                "JoinHost.ENDPOINT_FILE_PREFIX vs OpsJoinSelf.ENDPOINT_FILE_PREFIX"},
            {"JoinHost.java", "TOKEN_HEX_CHARS = " + OpsJoinSelf.ENDPOINT_TOKEN_HEX_CHARS + ";",
                "JoinHost.TOKEN_HEX_CHARS vs OpsJoinSelf.ENDPOINT_TOKEN_HEX_CHARS"},
            {"JoinHost.java", "Path base = JoinSettings.absoluteEnvDir(localAppData);",
                "JoinHost.endpointFile's LOCALAPPDATA gate vs OpsJoinSelf.endpointFile"},
            {"JoinHost.java", ".resolve(JoinSettings.DIR_NAME).resolve(\"" + OpsJoinSelf.ENDPOINT_DIR_NAME + "\")",
                "JoinHost.endpointFile's folder vs OpsJoinSelf.ENDPOINT_DIR_NAME"},
            {"JoinHost.java", ".resolve(ENDPOINT_FILE_PREFIX + installTag(installRoot) + \".data\")",
                "JoinHost.endpointFile's .data suffix vs OpsJoinSelf.endpointFile"},
            {"JoinHost.java", "installRoot.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT)",
                "JoinHost.installTag's key vs OpsJoinSelf.installTag's fold"},
            {"JoinHost.java", "MessageDigest.getInstance(\"SHA-256\")",
                "JoinHost.installTag's hash vs OpsJoinSelf.installTag"},
            {"JoinHost.java", "for (int i = 0; i < 4; i++)",
                "JoinHost.installTag's 4-byte width vs OpsJoinSelf.installTag"},
        };
        for (String[] pin : pins) {
            check(r, source.get(pin[0]).contains(pin[1]),
                    "flips when the kit moves its settings file / endpoint file and the console's third copy did not"
                            + " follow: " + pin[2] + " - " + pin[0] + " no longer holds [" + pin[1] + "]");
        }
    }

    // ---- join-texts ----

    /**
     * {@code join-texts}: K3 over EVERY {@code OpsJoinSelf} text by reflection (the {@code static final String}
     * fields {@link #TEXT_FIELD} matches): printable ASCII, at most 400 chars with the sample args, no dev-tooling
     * word, no {@code password=}; the refusals end with a period or name what to do; the per-text facts (TIP,
     * R9, R10 exact). A matched field this row does not know fails, naming it.
     *
     */
    private static void joinTexts(String r) throws Exception {
        Map<String, String> texts = new TreeMap<>();
        for (Field f : OpsJoinSelf.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (f.getType() == String.class && Modifier.isStatic(m) && Modifier.isFinal(m)
                    && TEXT_FIELD.matcher(f.getName()).matches()) {
                texts.put(f.getName(), (String) f.get(null));
            }
        }
        Set<String> unexpected = new TreeSet<>(texts.keySet());
        unexpected.removeAll(KNOWN_TEXTS);
        Set<String> missing = new TreeSet<>(KNOWN_TEXTS);
        missing.removeAll(texts.keySet());
        check(r, unexpected.isEmpty(),
                "every text field the K3 pattern matches is one this row knows (flips when a text is added: add it"
                        + " to KNOWN_TEXTS and re-read its laws): unexpected " + unexpected);
        check(r, missing.isEmpty(),
                "every known text is still a static final String the pattern matches (flips if a text is renamed"
                        + " out of the law's reach): missing " + missing);
        for (Map.Entry<String, String> t : texts.entrySet()) {
            String name = t.getKey();
            String raw = t.getValue();
            check(r, raw != null && !raw.isBlank() && printableAscii(raw),
                    name + " is non-blank printable ASCII 0x20..0x7E (flips on an ellipsis, an em dash, a tab or a"
                            + " line break in a stranger's dialog): " + firstNotPrintable(raw));
            String shown;
            try {
                shown = sampleFormatted(raw);
            } catch (IllegalArgumentException bad) {
                check(r, false, name + " formats with the sample args (flips on a slot no shell fills): "
                        + bad.getClass().getSimpleName());
                continue;
            }
            check(r, shown.length() <= 400 && printableAscii(shown),
                    name + " with the sample args is printable and at most 400 chars (flips if a text outgrows a"
                            + " dialog): " + shown.length() + " chars, " + firstNotPrintable(shown));
            String low = shown.toLowerCase(Locale.ROOT);
            String dev = null;
            for (String word : DEV_WORDS) {
                if (low.contains(word)) {
                    dev = word;
                    break;
                }
            }
            check(r, dev == null, name + " names no dev-tooling word (K3, a stranger's console; flips if a text"
                    + " says '" + dev + "')");
            check(r, !low.contains("password="),
                    name + " never shows a password= row (flips if a text quotes the settings file's secret row)");
            if (name.matches("R\\d+")) {
                check(r, shown.endsWith(".") || ACTION.matcher(shown).find(),
                        name + " ends with a period or names what to do next (flips if a refusal is cut with no"
                                + " action): " + shown);
            }
        }
        check(r, texts.getOrDefault("TIP", "").contains("CC_Client"),
                "TIP names the player kit CC_Client (flips if the tip stops telling a host which mod it needs)");
        String r9 = texts.getOrDefault("R9", "");
        check(r, r9.contains("Windows") && r9.contains("Copy join brick"),
                "R9 names Windows and the Copy join brick button (flips if a non-Windows host loses the way to"
                        + " join): " + r9);
        // The R10 sentence, as the design spells it.
        check(r, "ProtonVPN did not answer - reconnect it and press Join from this PC again".equals(texts.get("R10")),
                "R10 is the design sentence EXACTLY (flips on any edit to the R10 sentence): " + texts.get("R10"));
    }

    /** {@code text} through {@code String.format} with the sample args: an {@code s} slot = {@code C:\x} at index
     *  1, {@code IOException} at index 2; a {@code d} slot = 1 (a slot this cannot fill throws, the caller's
     *  check names it). */
    private static String sampleFormatted(String text) {
        Matcher m = FORMAT_SLOT.matcher(text);
        List<Object> args = new ArrayList<>();
        int next = 0;
        while (m.find()) {
            char conversion = m.group(2).charAt(0);
            if (conversion == '%' || conversion == 'n') {
                continue;
            }
            int at = m.group(1) != null ? Integer.parseInt(m.group(1)) : ++next;
            while (args.size() < at) {
                args.add(null);
            }
            args.set(at - 1, conversion == 'd' ? (Object) 1 : at == 1 ? "C:\\x" : "IOException");
        }
        return String.format(Locale.ROOT, text, args.toArray());
    }

    private static boolean printableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** The first char outside 0x20..0x7E as {@code 0x2026 at 17}, or {@code none}. */
    private static String firstNotPrintable(String s) {
        if (s == null) {
            return "null";
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch > 0x7E) {
                return String.format(Locale.ROOT, "0x%04X at %d", (int) ch, i);
            }
        }
        return "none";
    }

    // ---- join-self-writes-no-kit-file ----

    /**
     * {@code join-self-writes-no-kit-file}: a full press through the real {@link OpsJoinSelf#STEPS} (the spawn faked)
     * over a REAL install ({@link FakeEffects#diskRoot} = the install) whose kit the kit check reads current, with
     * no input naming a match file - the kit folder listed before
     * and after (every entry, a file's bytes, every last-modified time) is the same, no recorded write or folder
     * lands under it, and the press OPENS (every step ran). Two kits: (a) no match file, (b) another shape's match
     * file (a kit extracted from a zip that still carries one).
     */
    private static void joinSelfWritesNoKitFile(String r, Path base) throws Exception {
        for (String leg : List.of("a", "b")) {
            Path install = stageInstall(base.resolve("game-" + leg), true, true);
            Path kit = install.resolve("mods").resolve("CC_Client");
            stageFile(kit.resolve("kit-manifest.txt"),
                    ascii(KitPackRules.manifestText(KIT_MD5, KIT_STAMP, "0.1.0", "0.98a-RC8")));
            stageFile(kit.resolve("kit.files"), ascii("# the kit's own composite\r\nfingerprint="
                    + kitComposite(KIT_MD5, KIT_DATA, "shape-b") + "\r\n"));
            if ("b".equals(leg)) {
                stageFile(kit.resolve("match.txt"), MatchDescriptorCodec.encode(MatchDescriptors.redact(
                        MatchDescriptors.mint("1v1"))).getBytes(StandardCharsets.US_ASCII));
            }
            OpsJoinSelf.Inputs in = inputs(base, install, live("192.168.1.5", 46337), PW, Map.of())
                    .withKitStamps(KIT_MD5, kitComposite(KIT_MD5, KIT_DATA, "shape-a"), null);
            FakeEffects fx = diskFx(install);
            Map<String, String> before = listing(kit);
            Pressed p = press(in, fx);
            Map<String, String> after = listing(kit);
            Path kitAbs = kit.toAbsolutePath().normalize();
            List<Path> underKit = new ArrayList<>();
            for (Path w : fx.writes) {
                if (w.toAbsolutePath().normalize().startsWith(kitAbs)) {
                    underKit.add(w);
                }
            }
            for (Path d : fx.dirsMade) {
                if (d.toAbsolutePath().normalize().startsWith(kitAbs)) {
                    underKit.add(d);
                }
            }
            check(r, after.equals(before) && underKit.isEmpty(),
                    "(" + leg + ") the kit folder is byte-, entry- and time-equal after a full press and no write lands"
                            + " under it (flips if any step writes, creates or rewrites a file under the kit folder - a"
                            + " match-file step back in the press):"
                            + " before=" + before.keySet() + " after=" + after.keySet() + " underKit=" + underKit);
            check(r, p.result.outcome() == Outcome.OPENED && fx.spawns.size() == 1 && fx.writes.size() == 1
                            && hasRow(p.stdout, "cc-ops join kit_check=current") && !p.stdout.contains("kit_match"),
                    "(" + leg + ") the press ran every step to ONE spawn with ONE write (the settings file) over a kit"
                            + " read current (flips if the world stops reaching the spawn - the row would then prove"
                            + " nothing - or a second write joins the settings one): " + p.result + " writes="
                            + fx.writes + " " + p.stdout.strip());
            stdoutLaw(r, "nokit-" + leg, p, "opened", "-", 46337);
        }
    }

    /** Every entry under {@code dir}, relative ({@code /}): a folder = {@code dir@<mtime>}, a file = its bytes as
     *  ISO-8859-1 text + {@code @<mtime>}; a missing folder = empty. */
    private static Map<String, String> listing(Path dir) throws SetupFailure {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : walk.toList()) {
                String at = "@" + Files.getLastModifiedTime(f).toMillis();
                out.put(dir.relativize(f).toString().replace('\\', '/'), Files.isDirectory(f) ? "dir" + at
                        : new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1) + at);
            }
        } catch (IOException e) {
            throw new SetupFailure("listing the tree at " + dir, e);
        }
        return out;
    }

    /** The press step named {@code name} (a renamed step fails the row, naming it). */
    private static OpsJoinSelf.StepBody stepNamed(String name) {
        for (OpsJoinSelf.Step step : OpsJoinSelf.STEPS) {
            if (step.name().equals(name)) {
                return step.body();
            }
        }
        throw new IllegalStateException("no press step named " + name);
    }

    /** A real fixture file (its folder created first). */
    private static void stageFile(Path file, byte[] bytes) throws SetupFailure {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new SetupFailure("fixture file at " + file, e);
        }
    }

    // ---- join-kit-check-* ----

    /** The kit-check rows' stamps: the package jar md5 (lowercase, as the MANIFEST carries it), another kit's md5,
     *  two data digests and the installed / package stamps (the owner's measured kit shape). */
    private static final String KIT_MD5 = "2256887282c1a642da77f7e029ae49f0";
    private static final String KIT_MD5_OTHER = "0123456789abcdef0123456789abcdef";
    private static final String KIT_DATA = "65db3c6dc242f2072efd1ea376ca9d26";
    private static final String KIT_DATA_OTHER = "fedcba9876543210fedcba9876543210";
    private static final String KIT_STAMP = "20261016-191116";
    private static final String KIT_STAMP_OLD = "20260830-090512";

    /** A kit composite as the packer mints it, for match shape {@code matchId}. */
    private static String kitComposite(String jarMd5, String dataMd5, String matchId) {
        return ContentFingerprints.composite(jarMd5, dataMd5, HandshakeCodec.HANDSHAKE_VERSION, matchId);
    }

    /**
     * The installed kit's two stamp files in {@code fx}'s map, as the packer writes them: {@code kit-manifest.txt}
     * ({@link KitPackRules#manifestText}: {@code jarMd5} UPPERCASE, CRLF) and, when {@code compositeOrNull} is set,
     * the sidecar {@code kit.files} with a comment row and its {@code fingerprint=} row (the literal kit
     * layout, never the core's constants).
     */
    private static void stageKitStamps(FakeEffects fx, Path install, String jarMd5, String stamp,
            String compositeOrNull) {
        Path kit = install.resolve("mods").resolve("CC_Client");
        fx.files.put(kit.resolve("kit-manifest.txt"),
                ascii(KitPackRules.manifestText(jarMd5, stamp, "0.1.0", "0.98a-RC8")));
        if (compositeOrNull != null) {
            fx.files.put(kit.resolve("kit.files"),
                    ascii("# the kit's own composite\r\nfingerprint=" + compositeOrNull + "\r\n"));
        }
    }

    /**
     * {@code join-kit-check-current}: the installed kit is this server's - its manifest md5 UPPERCASE against the
     * package's lowercase, its sidecar packed for match shape B against the package fingerprint's shape A, the same
     * jar / data / hs - so the press OPENS with one {@code kit_check=current} row, having read both stamp files; the
     * pre-Start API answers null over the same world and R1 over an unset Game folder.
     *
     */
    private static void joinKitCheckCurrent(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        Path kit = install.resolve("mods").resolve("CC_Client");
        OpsSnapshot lan = live("192.168.1.5", 46337);
        String fpA = kitComposite(KIT_MD5, KIT_DATA, "shape-a");
        OpsJoinSelf.Inputs in = inputs(base, install, lan, PW, Map.of()).withKitStamps(KIT_MD5, fpA, null);
        FakeEffects fx = FakeEffects.withKit(install);
        stageKitStamps(fx, install, KIT_MD5, KIT_STAMP, kitComposite(KIT_MD5, KIT_DATA, "shape-b"));
        Pressed a = press(in, fx);
        check(r, a.result.outcome() == Outcome.OPENED && a.stdout.contains("cc-ops join kit_check=current")
                        && !a.stdout.contains("kit_check=stale") && fx.spawns.size() == 1
                        && fx.reads.contains(kit.resolve("kit-manifest.txt"))
                        && fx.reads.contains(kit.resolve("kit.files")),
                "a kit whose manifest md5 is the package's in UPPERCASE and whose sidecar differs only in match="
                        + " OPENS with one kit_check=current row, both stamp files read (flips if the md5 compare is"
                        + " case-sensitive, if ;match= is not stripped - a kit packed for another shape refused - or if"
                        + " the fingerprint compare is skipped): " + a.result + " " + a.stdout.strip());
        stdoutLaw(r, "current", a, "opened", "-", 46337);

        FakeEffects fxApi = FakeEffects.withKit(install);
        stageKitStamps(fxApi, install, KIT_MD5, KIT_STAMP, kitComposite(KIT_MD5, KIT_DATA, "shape-b"));
        Printed api = printed(() -> String.valueOf(OpsJoinSelf.kitCheckOrNull(in, fxApi)));
        check(r, "null".equals(api.value()) && api.stdout().contains("cc-ops join kit_check=current")
                        && fxApi.writes.isEmpty() && fxApi.spawns.isEmpty(),
                "the pre-Start API over the same current kit answers null = proceed, one current row, nothing written"
                        + " or started (flips if it refuses a current kit or runs more of the press): " + api.value());

        Map<String, String> noGameRow = new HashMap<>();
        OpsJoinSelf.Inputs unset = new OpsJoinSelf.Inputs(base, noGameRow, lan, PW, "CC_Client-kit.zip",
                base.resolve("lad").toString(), base.resolve("ad").toString(), base.toString(), null)
                .withKitStamps(KIT_MD5, fpA, null);
        FakeEffects fxUnset = FakeEffects.withKit(install);
        Printed none = printed(() -> {
            OpsJoinSelf.KitCheck c = OpsJoinSelf.kitCheckOrNull(unset, fxUnset);
            return c == null || c.refusedOrNull() == null ? "proceed"
                    : c.refusedOrNull().reasonToken() + "|" + c.refusedOrNull().text() + "|" + c.noteOrNull();
        });
        check(r, ("r1|" + OpsJoinSelf.R1 + "|null").equals(none.value()) && fxUnset.reads.isEmpty()
                        && !none.stdout().contains("kit_check="),
                "the pre-Start API over an unset Game folder answers step (1)'s R1 before any kit read (flips if it"
                        + " skips the install law or checks a kit it has no folder for): " + none.value());
    }

    /**
     * {@code join-kit-check-stale}: five stale worlds - (a) another jar md5, (b) the same md5 with a data drift in
     * the sidecar, (c) no {@code kit-manifest.txt}, (d) a manifest without its {@code jarMd5} row, (e) a fingerprint
     * set and no sidecar - each REFUSED {@code kit_stale} with R12 built from the fixture's own stamps, and 0 writes,
     * 0 spawns, one
     * {@code kit_check=stale} row, no path or md5 in stdout.
     */
    private static void joinKitCheckStale(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        Path kit = install.resolve("mods").resolve("CC_Client");
        OpsSnapshot lan = live("192.168.1.5", 46337);
        OpsJoinSelf.Inputs bare = inputs(base, install, lan, PW, Map.of());
        String fp = kitComposite(KIT_MD5, KIT_DATA, "shape-a");
        String mods = OpsJoinSelf.boundedText(install.resolve("mods").toString());
        String[] legs = {"a", "b", "c", "d", "e"};
        for (String leg : legs) {
            FakeEffects fx = FakeEffects.withKit(install);
            OpsJoinSelf.Inputs in;
            String yours;
            switch (leg) {
                case "a" -> {
                    stageKitStamps(fx, install, KIT_MD5_OTHER, KIT_STAMP_OLD, null);
                    in = bare.withKitStamps(KIT_MD5, null, null);
                    yours = "kit 20260830-090512, jar 01234567";
                }
                case "b" -> {
                    stageKitStamps(fx, install, KIT_MD5, KIT_STAMP, kitComposite(KIT_MD5, KIT_DATA_OTHER, "shape-a"));
                    in = bare.withKitStamps(KIT_MD5, fp, null);
                    yours = "kit 20261016-191116, jar 22568872";
                }
                case "c" -> {
                    in = bare.withKitStamps(KIT_MD5, null, null);
                    yours = "no kit stamp";
                }
                case "d" -> {
                    fx.files.put(kit.resolve("kit-manifest.txt"),
                            ascii("kitStamp=20260830-090512\r\nmodVersion=0.1.0\r\nsource=kit\r\n"));
                    in = bare.withKitStamps(KIT_MD5, null, null);
                    yours = "kit 20260830-090512, jar ?";
                }
                default -> {
                    stageKitStamps(fx, install, KIT_MD5, KIT_STAMP, null);
                    in = bare.withKitStamps(KIT_MD5, fp, null);
                    yours = "kit 20261016-191116, jar 22568872";
                }
            }
            Pressed p = press(in, fx);
            String want = String.format(Locale.ROOT, OpsJoinSelf.R12, yours, "22568872", "CC_Client-kit.zip", mods);
            check(r, p.result.outcome() == Outcome.REFUSED && "kit_stale".equals(p.result.reasonToken())
                            && want.equals(p.result.text()),
                    "(" + leg + ") a stale kit = REFUSED kit_stale with R12 naming its own stamps, the package jar, the"
                            + " kit hint and the mods folder (flips if this stale world is called current - a data-only"
                            + " drift or a missing stamp read as a pass - or if R12 loses a stamp or the folder): "
                            + p.result);
            check(r, fx.writes.isEmpty() && fx.spawns.isEmpty() && p.stdout.contains("cc-ops join kit_check=stale"),
                    "(" + leg + ") nothing written or started and one kit_check=stale row (flips if the check runs after"
                            + " a write, or a stale kit is written into): writes=" + fx.writes + " "
                            + p.stdout.strip());
            String low = p.stdout.toLowerCase(Locale.ROOT);
            check(r, !p.stdout.contains(base.toString()) && !low.contains("22568872") && !low.contains("01234567"),
                    "(" + leg + ") no path and no md5 in stdout (flips if a stamp or the kit folder is logged): "
                            + p.stdout.strip());
            stdoutLaw(r, "stale-" + leg, p, "refused", "kit_stale", 46337);
        }
    }

    /**
     * {@code join-kit-check-skipped}: no package jar md5 while the fingerprint and the zip ARE set, over a kit whose
     * stamps are another kit's = one {@code kit_check=skipped} row, neither stamp file read, OPENED as an
     * unstamped press.
     */
    private static void joinKitCheckSkipped(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        Path kit = install.resolve("mods").resolve("CC_Client");
        OpsSnapshot lan = live("192.168.1.5", 46337);
        Path zip = base.resolve("pkg").resolve("kits").resolve("CC_Client-kit.zip");
        OpsJoinSelf.Inputs in = inputs(base, install, lan, PW, Map.of())
                .withKitStamps(null, kitComposite(KIT_MD5, KIT_DATA, "shape-a"), zip);
        FakeEffects fx = FakeEffects.withKit(install);
        stageKitStamps(fx, install, KIT_MD5_OTHER, KIT_STAMP_OLD, kitComposite(KIT_MD5_OTHER, KIT_DATA_OTHER, "x"));
        Pressed p = press(in, fx);
        FakeEffects plain = FakeEffects.withKit(install);
        Pressed q = press(inputs(base, install, lan, PW, Map.of()), plain);
        check(r, p.result.outcome() == Outcome.OPENED && p.result.equals(q.result)
                        && p.stdout.contains("cc-ops join kit_check=skipped") && !p.stdout.contains("kit_check=stale")
                        && !fx.reads.contains(kit.resolve("kit-manifest.txt"))
                        && !fx.reads.contains(kit.resolve("kit.files")),
                "no package jar md5 = one skipped row, neither stamp file read, the same OPENED answer as an unstamped"
                        + " press, even with a fingerprint and a zip set over another kit (flips if a null md5 still"
                        + " compares or the fingerprint alone arms the check): " + p.result + " reads=" + fx.reads);
        stdoutLaw(r, "skipped", p, "opened", "-", 46337);
    }

    // ---- join-kit-refresh-* + join-fx-delegates ----

    /**
     * The refresh rows' kit zip, laid out as the packer writes one (the literal kit layout, never the core's
     * constants): the jar, the bat, {@code mod_info.json}, one data file, one {@code jre-cc} file,
     * {@code kit-manifest.txt} (its {@code jarMd5} = the jar's), the sidecar packed for match shape {@code shape-z}
     * (its {@code data=} = {@code sidecarDataOrNull} when set, else the entries' own digest) and a root
     * {@code README.md}; {@code fp} = the package fingerprint for shape {@code shape-a}; {@code entries} = name ->
     * bytes in write order.
     */
    private record KitFixture(Path zip, String jarMd5, String fp, Map<String, byte[]> entries) {
        /** The entries as the zip reader answers them, {@code extra} appended LAST. */
        List<KitZip.Entry> asEntries(Map<String, byte[]> extra) {
            List<KitZip.Entry> out = new ArrayList<>();
            entries.forEach((n, b) -> out.add(new KitZip.Entry(n, b)));
            extra.forEach((n, b) -> out.add(new KitZip.Entry(n, b)));
            return out;
        }

        /** The kit folder the zip installs: its {@code CC_Client/} entries, relative, as text. */
        Map<String, String> kitTree() {
            Map<String, String> out = new TreeMap<>();
            entries.forEach((n, b) -> {
                if (n.startsWith("CC_Client/")) {
                    out.put(n.substring("CC_Client/".length()), new String(b, StandardCharsets.ISO_8859_1));
                }
            });
            return out;
        }
    }

    private static KitFixture kitZip(Path zip, String sidecarDataOrNull) throws SetupFailure {
        byte[] jar = ascii("cc-client-mod jar bytes for the refresh rows 0123456789");
        byte[] modInfo = ascii("{\"id\":\"cc_client\",\"version\":\"0.1.0\"}");
        byte[] data = ascii("id,name\r\nrefresh,kit\r\n");
        String jarMd5 = ContentFingerprints.md5Hex(jar);
        TreeMap<String, byte[]> surface = new TreeMap<>();
        surface.put("mod_info.json", modInfo);
        surface.put("data/config/cc_refresh.csv", data);
        String dataMd5 = ContentFingerprints.dataMd5(surface);
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("CC_Client/jars/cc-client-mod.jar", jar);
        e.put("CC_Client/join-cc.bat", ascii("@echo off\r\nrem the kit launcher\r\n"));
        e.put("CC_Client/mod_info.json", modInfo);
        e.put("CC_Client/data/config/cc_refresh.csv", data);
        e.put("CC_Client/jre-cc/release", ascii("JAVA_VERSION=\"25\"\r\n"));
        e.put("CC_Client/kit-manifest.txt", ascii(KitPackRules.manifestText(jarMd5, KIT_STAMP, "0.1.0", "0.98a-RC8")));
        e.put("CC_Client/kit.files", ascii("# the kit's own composite\r\nfingerprint="
                + kitComposite(jarMd5, sidecarDataOrNull == null ? dataMd5 : sidecarDataOrNull, "shape-z") + "\r\n"));
        // the kit's root README as the packer names it (KitPackRules.README_ENTRY).
        e.put("README.md", ascii("Extract CC_Client into your game's mods folder.\r\n"));
        try {
            Files.createDirectories(zip.getParent());
            List<KitZip.Entry> list = new ArrayList<>();
            e.forEach((n, b) -> list.add(new KitZip.Entry(n, b)));
            KitZip.write(zip, list);
        } catch (IOException | RuntimeException failed) {
            throw new SetupFailure("kit zip fixture at " + zip, failed);
        }
        return new KitFixture(zip, jarMd5, kitComposite(jarMd5, dataMd5, "shape-a"), e);
    }

    /** An installed kit of ANOTHER build on the real disk: the bat, its manifest (another md5, the old stamp) and
     *  a marker file {@code old.txt} holding {@code marker}. */
    private static void stageOldKit(Path kit, String marker) throws SetupFailure {
        stageFile(kit.resolve("join-cc.bat"), new byte[] {1});
        stageFile(kit.resolve("kit-manifest.txt"),
                ascii(KitPackRules.manifestText(KIT_MD5_OTHER, KIT_STAMP_OLD, "0.1.0", "0.98a-RC8")));
        stageFile(kit.resolve("old.txt"), ascii(marker));
    }

    /** A refresh row's world: a real install holding an old kit, the package's kit zip, the inputs (LAN, the zip's
     *  stamps), and the install's real path (the park and stage sit there). */
    private record RefreshWorld(Path install, Path kit, Path real, KitFixture z, OpsJoinSelf.Inputs in) {
        Path park(String suffix) {
            return real.resolve("cc-kit-parked").resolve("CC_Client-20260830-090512" + suffix);
        }
    }

    private static RefreshWorld refreshWorld(Path base, String sidecarDataOrNull) throws SetupFailure {
        Path install = stageInstall(base.resolve("game"), true, true);
        Path kit = install.resolve("mods").resolve("CC_Client");
        stageOldKit(kit, "old kit A");
        KitFixture z = kitZip(base.resolve("pkg").resolve("kits").resolve("cc-joiner-kit.zip"), sidecarDataOrNull);
        Path real;
        try {
            real = install.toRealPath();
        } catch (IOException e) {
            throw new SetupFailure("the real path of " + install, e);
        }
        OpsJoinSelf.Inputs in = inputs(base, install, live("192.168.1.5", 46337), PW, Map.of())
                .withKitStamps(z.jarMd5(), z.fp(), z.zip());
        return new RefreshWorld(install, kit, real, z, in);
    }

    /** {@link FakeEffects#withKit} with the whole install on the REAL disk (the stamp reads, the refresh's moves). */
    private static FakeEffects diskFx(Path install) {
        FakeEffects fx = FakeEffects.withKit(install);
        fx.diskRoot = install;
        return fx;
    }

    /** Every regular file under {@code dir}, relative ({@code /}), as ISO-8859-1 text; a missing folder = empty. */
    private static Map<String, String> treeOf(Path dir) throws SetupFailure {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                out.put(dir.relativize(f).toString().replace('\\', '/'),
                        new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1));
            }
        } catch (IOException e) {
            throw new SetupFailure("reading the tree at " + dir, e);
        }
        return out;
    }

    /** The staging folders left in {@code real} (the refresh's own {@code .cc-kit-staging-*} names). */
    private static List<String> stagesLeft(Path real) throws SetupFailure {
        try (Stream<Path> list = Files.list(real)) {
            return list.map(q -> q.getFileName().toString()).filter(n -> n.startsWith(".cc-kit-staging-")).toList();
        } catch (IOException e) {
            throw new SetupFailure("listing " + real, e);
        }
    }

    /** R14 as a refusal over {@code w} shows it: the old kit's stamps, the package jar's first 8 hex, {@code why},
     *  the zip and the install's {@code mods} folder. */
    private static String r14(RefreshWorld w, OpsJoinSelf.Inputs in, String why) {
        return String.format(Locale.ROOT, OpsJoinSelf.R14, "kit 20260830-090512, jar 01234567",
                in.kitJarMd5OrNull().substring(0, 8).toUpperCase(Locale.ROOT), why,
                OpsJoinSelf.boundedText(String.valueOf(in.kitZipOrNull())),
                OpsJoinSelf.boundedText(w.install().resolve("mods").toString()));
    }

    /** The refresh's expected seam calls: the zip read, the real path, the stage, one write per {@code CC_Client/}
     *  entry, then {@code tail}. */
    private static List<String> refreshOps(KitFixture z, String... tail) {
        List<String> ops = new ArrayList<>(List.of("readKitZip", "realPath", "createStage"));
        for (String name : z.entries().keySet()) {
            if (name.startsWith("CC_Client/")) {
                ops.add("writeNew");
            }
        }
        ops.addAll(List.of(tail));
        return ops;
    }

    /**
     * {@code join-kit-refresh-done}: a stale kit (another md5, stamp {@code 20260830-090512}, a marker
     * {@code old.txt}) with the package's kit zip at hand = OPENED after, IN
     * ORDER, the zip read, the real path, the stage, one write per kit entry, the park, the install and the stage
     * delete; the old kit parked byte-equal under {@code cc-kit-parked\CC_Client-20260830-090512};
     * {@code mods\CC_Client} = the zip's kit byte-equal and nothing else, no README, no staging folder; the
     * answer led by the note; stdout {@code kit_check=stale} then {@code kit_refresh=done}, no path. The next press =
     * current, no refresh op; a second stale kit parks under {@code -2}, the first park untouched.
     *
     */
    private static void joinKitRefreshDone(String r, Path base) throws Exception {
        RefreshWorld w = refreshWorld(base, null);
        Map<String, String> oldA = treeOf(w.kit());
        FakeEffects fx = diskFx(w.install());
        Pressed a = press(w.in(), fx);
        Path park1 = w.park("");
        String note1 = String.format(Locale.ROOT, OpsJoinSelf.NOTE_KIT_REFRESHED,
                OpsJoinSelf.boundedText(park1.toString()));
        check(r, a.result.outcome() == Outcome.OPENED && fx.ops.equals(refreshOps(w.z(), "moveDir", "moveDir",
                        "deleteStage")) && a.result.text().startsWith(note1 + " Join window opening"),
                "a stale kit with the zip at hand is refreshed IN ORDER (zip read + verify, real path, stage, writes,"
                        + " park, install, stage delete) and the press OPENS with the note first (flips if the order"
                        + " breaks - park before verify, install before stage - or the note is dropped): " + a.result
                        + " ops=" + fx.ops);
        Map<String, String> parked = treeOf(park1);
        check(r, parked.equals(oldA),
                "the old kit is parked byte-equal under cc-kit-parked\\CC_Client-<its kitStamp> (flips if the park is"
                        + " skipped, misnamed or loses a file): " + parked.keySet() + " vs " + oldA.keySet());
        Map<String, String> newKit = treeOf(w.kit());
        List<String> stages = stagesLeft(w.real());
        check(r, newKit.equals(w.z().kitTree()) && Files.notExists(w.real().resolve("README.md"))
                        && Files.notExists(w.real().resolve("mods").resolve("README.md")) && stages.isEmpty(),
                "mods\\CC_Client = the zip's CC_Client entries byte-equal and nothing else, no README outside the zip,"
                        + " no staging folder left (flips if a step writes a file such as a match file into the"
                        + " refreshed kit, README leaks into the game folder, or the stage is kept): " + newKit.keySet()
                        + " stages=" + stages);
        int stale = a.stdout.indexOf("cc-ops join kit_check=stale");
        int done = a.stdout.indexOf("cc-ops join kit_refresh=done");
        check(r, stale >= 0 && done > stale && !a.stdout.contains(base.toString())
                        && !a.stdout.contains(w.real().toString()) && !a.stdout.contains("README"),
                "stdout: kit_check=stale, then kit_refresh=done, no path and no entry name (flips if a row is lost or a"
                        + " path is logged): " + a.stdout.strip());
        stdoutLaw(r, "done", a, "opened", "-", 46337);

        FakeEffects fx2 = diskFx(w.install());
        Pressed b = press(w.in(), fx2);
        check(r, b.result.outcome() == Outcome.OPENED && fx2.ops.isEmpty()
                        && b.stdout.contains("cc-ops join kit_check=current") && !b.stdout.contains("kit_refresh=")
                        && b.result.text().startsWith("Join window opening"),
                "the next press finds the refreshed kit current: no refresh op, no note (flips if the manifest and"
                        + " sidecar the zip installed do not pass the check - a refresh on every press): " + b.result
                        + " ops=" + fx2.ops);

        deleteTree(w.kit());
        if (Files.exists(w.kit())) {
            throw new SetupFailure("removing the refreshed kit", new IOException(w.kit().toString()));
        }
        stageOldKit(w.kit(), "old kit B");
        Map<String, String> oldB = treeOf(w.kit());
        FakeEffects fx3 = diskFx(w.install());
        Pressed c = press(w.in(), fx3);
        Path park2 = w.park("-2");
        String note2 = String.format(Locale.ROOT, OpsJoinSelf.NOTE_KIT_REFRESHED,
                OpsJoinSelf.boundedText(park2.toString()));
        Map<String, String> parked1 = treeOf(park1);
        Map<String, String> parked2 = treeOf(park2);
        check(r, c.result.outcome() == Outcome.OPENED && parked2.equals(oldB) && parked1.equals(oldA)
                        && c.result.text().startsWith(note2 + " ")
                        && fx3.ops.equals(refreshOps(w.z(), "moveDir", "moveDir", "moveDir", "deleteStage")),
                "a second stale kit parks under the next free name (-2) and the first park is untouched (flips if a"
                        + " park is deleted or overwritten, or a taken name reads as held): " + c.result + " park1="
                        + parked1.keySet() + " park2=" + parked2.keySet() + " ops=" + fx3.ops);
    }

    /**
     * {@code join-kit-refresh-live-window}: a stale kit with the zip at hand while its join window is live (the
     * endpoint file + ping) = REFUSED {@code kit_live_window} with R13, no refresh seam called, the old kit untouched,
     * no park or stage, stdout {@code kit_refresh=refused:live_window}.
     */
    private static void joinKitRefreshLiveWindow(String r, Path base) throws Exception {
        RefreshWorld w = refreshWorld(base, null);
        Map<String, String> old = treeOf(w.kit());
        FakeEffects fx = diskFx(w.install());
        Path endpoint = OpsJoinSelf.endpointFile(base.resolve("lad").toString(), w.install());
        fx.files.put(endpoint, ascii("46000|" + TOKEN + "|" + NONCE));
        fx.pings.put(46000, PING_JOIN);
        Pressed p = press(w.in(), fx);
        check(r, p.result.outcome() == Outcome.REFUSED && "kit_live_window".equals(p.result.reasonToken())
                        && OpsJoinSelf.R13.equals(p.result.text()) && fx.ops.isEmpty() && treeOf(w.kit()).equals(old)
                        && Files.notExists(w.real().resolve("cc-kit-parked")) && stagesLeft(w.real()).isEmpty()
                        && fx.writes.isEmpty() && fx.spawns.isEmpty(),
                "a live join window stops the refresh before any zip read or move = R13, the old kit untouched (flips"
                        + " if the detector runs after any read or move): " + p.result + " ops=" + fx.ops);
        check(r, p.stdout.contains("cc-ops join kit_refresh=refused:live_window"),
                "one kit_refresh=refused:live_window row (flips if the refusal is not logged or mis-tokened): "
                        + p.stdout.strip());
        stdoutLaw(r, "live", p, "refused", "kit_live_window", 46337);
    }

    /**
     * {@code join-kit-refresh-rollback} (the REAL disk, faults from {@link FakeEffects#failOn}): (a) the install
     * move fails = the park moved back, {@code kit_rolled_back}, the old kit in place byte-equal; (b) the move back
     * fails too = {@code kit_rollback_failed}, R15 naming the park, which still holds the old kit; (c) the park move
     * fails ({@code AccessDeniedException}) = {@code kit_held}, nothing moved; (d) a staged write fails =
     * {@code kit_denied}, the game's kit untouched. No staging folder left in any leg; each token on its stdout row.
     *
     */
    private static void joinKitRefreshRollback(String r, Path base) throws Exception {
        RefreshWorld a = refreshWorld(base.resolve("a"), null);
        Map<String, String> oldA = treeOf(a.kit());
        FakeEffects fxA = diskFx(a.install());
        fxA.failOn.put("moveDir#2", new IOException("install move refused"));
        Pressed pa = press(a.in(), fxA);
        check(r, pa.result.outcome() == Outcome.REFUSED && "kit_rolled_back".equals(pa.result.reasonToken())
                        && r14(a, a.in(), OpsJoinSelf.WHY_ROLLED_BACK).equals(pa.result.text())
                        && treeOf(a.kit()).equals(oldA) && treeOf(a.park("")).isEmpty() && stagesLeft(a.real()).isEmpty()
                        && fxA.spawns.isEmpty() && pa.stdout.contains("cc-ops join kit_refresh=rolled_back (IOException)"),
                "(a) a failed install moves the park back = kit_rolled_back, the old kit in place byte-equal, no stage"
                        + " (flips if a failed install leaves no kit silently or the rollback is skipped): " + pa.result
                        + " ops=" + fxA.ops);
        stdoutLaw(r, "rollback-a", pa, "refused", "kit_rolled_back", 46337);

        RefreshWorld b = refreshWorld(base.resolve("b"), null);
        Map<String, String> oldB = treeOf(b.kit());
        FakeEffects fxB = diskFx(b.install());
        fxB.failOn.put("moveDir#2", new IOException("install move refused"));
        fxB.failOn.put("moveDir#3", new IOException("move back refused"));
        Pressed pb = press(b.in(), fxB);
        String r15 = String.format(Locale.ROOT, OpsJoinSelf.R15, OpsJoinSelf.boundedText(b.park("").toString()),
                OpsJoinSelf.boundedText(b.real().resolve("mods").toString()));
        check(r, pb.result.outcome() == Outcome.REFUSED && "kit_rollback_failed".equals(pb.result.reasonToken())
                        && r15.equals(pb.result.text()) && treeOf(b.park("")).equals(oldB) && Files.notExists(b.kit())
                        && stagesLeft(b.real()).isEmpty()
                        && pb.stdout.contains("cc-ops join kit_refresh=rollback_failed (IOException)"),
                "(b) a failed install AND a failed move back = kit_rollback_failed, R15 naming the park, the park still"
                        + " holding the old kit (flips if the park is lost or the host is not told where it is): "
                        + pb.result);
        stdoutLaw(r, "rollback-b", pb, "refused", "kit_rollback_failed", 46337);

        RefreshWorld c = refreshWorld(base.resolve("c"), null);
        Map<String, String> oldC = treeOf(c.kit());
        FakeEffects fxC = diskFx(c.install());
        fxC.failOn.put("moveDir#1", new AccessDeniedException("CC_Client"));
        Pressed pc = press(c.in(), fxC);
        check(r, pc.result.outcome() == Outcome.REFUSED && "kit_held".equals(pc.result.reasonToken())
                        && r14(c, c.in(), OpsJoinSelf.WHY_IN_USE).equals(pc.result.text())
                        && treeOf(c.kit()).equals(oldC) && Files.notExists(c.real().resolve("cc-kit-parked"))
                        && stagesLeft(c.real()).isEmpty() && fxC.ops.equals(refreshOps(c.z(), "moveDir", "deleteStage"))
                        && pc.stdout.contains("cc-ops join kit_refresh=refused:held (AccessDeniedException)"),
                "(c) a refused park move = kit_held, nothing moved, the stage deleted (flips if held is mis-tokened,"
                        + " the press goes on to the install, or the stage is kept): " + pc.result + " ops=" + fxC.ops);
        stdoutLaw(r, "rollback-c", pc, "refused", "kit_held", 46337);

        RefreshWorld d = refreshWorld(base.resolve("d"), null);
        Map<String, String> oldD = treeOf(d.kit());
        FakeEffects fxD = diskFx(d.install());
        fxD.failOn.put("writeNew#1", new IOException("disk full"));
        Pressed pd = press(d.in(), fxD);
        check(r, pd.result.outcome() == Outcome.REFUSED && "kit_denied".equals(pd.result.reasonToken())
                        && r14(d, d.in(), OpsJoinSelf.WHY_NOT_WRITABLE).equals(pd.result.text())
                        && treeOf(d.kit()).equals(oldD) && Files.notExists(d.real().resolve("cc-kit-parked"))
                        && stagesLeft(d.real()).isEmpty()
                        && fxD.ops.equals(List.of("readKitZip", "realPath", "createStage", "writeNew", "deleteStage"))
                        && pd.stdout.contains("cc-ops join kit_refresh=refused:denied (IOException)"),
                "(d) a failed staged write = kit_denied, the game's kit untouched, the stage deleted (flips if denied is"
                        + " mis-tokened or a write failure reaches the park): " + pd.result + " ops=" + fxD.ops);
        stdoutLaw(r, "rollback-d", pd, "refused", "kit_denied", 46337);
    }

    /**
     * {@code join-kit-refresh-verify}: a zip that is not this server's kit is never installed - (a) its jar is not
     * the package's, (b) its data differs from the package fingerprint, (c) an entry {@code CC_Client/../evil.txt}
     * and (c2) {@code CC_Client/../../evil.txt} (bypassing the zip reader's grammar), (d) a file that is not a zip,
     * (f) its own sidecar disagrees with the fingerprint, (g) its own manifest names another jar = {@code kit_verify};
     * (e) a plain read failure = {@code kit_io}. Each: the old kit untouched, no park, no stage, no file outside the
     * stage. Plus the containment law over names directly.
     */
    private static void joinKitRefreshVerify(String r, Path base) throws Exception {
        RefreshWorld w = refreshWorld(base.resolve("w"), null);
        KitFixture z = w.z();
        Map<String, String> old = treeOf(w.kit());
        Path notZip = base.resolve("pkg-d").resolve("not-a-kit.zip");
        stageFile(notZip, ascii("this file is not a zip archive\r\n"));
        KitFixture zf = kitZip(base.resolve("pkg-f").resolve("cc-joiner-kit.zip"), KIT_DATA_OTHER);
        Map<String, byte[]> otherManifest = new LinkedHashMap<>(z.entries());
        otherManifest.put("CC_Client/kit-manifest.txt",
                ascii(KitPackRules.manifestText(KIT_MD5_OTHER, KIT_STAMP, "0.1.0", "0.98a-RC8")));
        OpsJoinSelf.Inputs plain = inputs(base.resolve("w"), w.install(), live("192.168.1.5", 46337), PW, Map.of());
        String[] legs = {"a", "b", "c", "c2", "d", "e", "f", "g"};
        for (String leg : legs) {
            FakeEffects fx = diskFx(w.install());
            OpsJoinSelf.Inputs in = w.in();
            String token = "verify";
            List<String> wantOps = List.of("readKitZip");
            switch (leg) {
                case "a" -> in = plain.withKitStamps(KIT_MD5, null, z.zip());
                case "b" -> in = plain.withKitStamps(z.jarMd5(), kitComposite(z.jarMd5(), KIT_DATA_OTHER, "shape-a"),
                        z.zip());
                case "c" -> {
                    fx.zipOverride = z.asEntries(Map.of("CC_Client/../evil.txt", ascii("evil")));
                    wantOps = refreshOps(z, "deleteStage");
                }
                case "c2" -> {
                    fx.zipOverride = z.asEntries(Map.of("CC_Client/../../evil.txt", ascii("evil")));
                    wantOps = refreshOps(z, "deleteStage");
                }
                case "d" -> in = plain.withKitStamps(z.jarMd5(), z.fp(), notZip);
                case "e" -> {
                    fx.failOn.put("readKitZip#1", new IOException("read failed"));
                    token = "io";
                }
                case "f" -> in = plain.withKitStamps(zf.jarMd5(), zf.fp(), zf.zip());
                default -> fx.zipOverride = new KitFixture(z.zip(), z.jarMd5(), z.fp(), otherManifest)
                        .asEntries(Map.of());
            }
            Pressed p = press(in, fx);
            String why = "io".equals(token) ? "a file operation failed (IOException)" : OpsJoinSelf.WHY_ZIP_MISMATCH;
            check(r, p.result.outcome() == Outcome.REFUSED && ("kit_" + token).equals(p.result.reasonToken())
                            && r14(w, in, why).equals(p.result.text()) && fx.ops.equals(wantOps),
                    "(" + leg + ") a zip that is not this server's kit = kit_" + token + " with R14, the refresh seams"
                            + " stopping there (flips if the containment check is lost or a damaged / foreign zip is"
                            + " installed): " + p.result + " ops=" + fx.ops);
            check(r, treeOf(w.kit()).equals(old) && Files.notExists(w.real().resolve("cc-kit-parked"))
                            && stagesLeft(w.real()).isEmpty() && Files.notExists(w.real().resolve("evil.txt"))
                            && Files.notExists(w.real().resolve("mods").resolve("evil.txt"))
                            && fx.writes.isEmpty() && fx.spawns.isEmpty()
                            && p.stdout.contains("cc-ops join kit_refresh=refused:" + token)
                            && !p.stdout.contains("evil"),
                    "(" + leg + ") the old kit untouched, no park, no stage, no file outside the stage, one refused:"
                            + token + " row with no entry name (flips if a refused zip touches the game folder): "
                            + p.stdout.strip());
            stdoutLaw(r, "verify-" + leg, p, "refused", "kit_" + token, 46337);
        }
        Path stage = w.real().resolve(".cc-kit-staging-0000000000000000");
        check(r, stage.resolve("CC_Client/data/a.csv").normalize()
                        .equals(OpsJoinSelf.stagedPathOrNull(stage, "CC_Client/data/a.csv"))
                        && OpsJoinSelf.stagedPathOrNull(stage, "CC_Client/../../evil.txt") == null
                        && OpsJoinSelf.stagedPathOrNull(stage, "CC_Client/../evil.txt") == null
                        && OpsJoinSelf.stagedPathOrNull(stage, "CC_Client/") == null
                        && OpsJoinSelf.stagedPathOrNull(stage, "CC_Client\\evil.txt") == null
                        && OpsJoinSelf.stagedPathOrNull(stage, "C:/evil.txt") == null
                        && OpsJoinSelf.stagedPathOrNull(stage, "/evil.txt") == null,
                "a kit entry lands inside the stage or nowhere: .., a trailing /, a backslash, a drive and a leading /"
                        + " are refused (flips if the containment law loses a case)");
    }

    /** A seam call that may throw (the delegation row's shape). */
    @FunctionalInterface
    private interface SeamCall {
        void call() throws IOException;
    }

    /**
     * {@code sweep-plain-join-not-recorded}: a hand Join press records NO window even when its spawn kept a handle,
     * while the debug watch's press over the same kind of spawn records each window it opened, in order, with its own
     * kit folder and start instant (the control: without it the hand press's empty record would prove nothing).
     *
     */
    private static void sweepPlainJoinNotRecorded(String r, Path base) throws Exception {
        Path install1 = stageInstall(base.resolve("game1"), true, true);
        Path install2 = stageInstall(base.resolve("game2"), true, true);
        FakeEffects hand = FakeEffects.withKit(install1);
        hand.spawnHandle = ProcessHandle.current();
        Pressed plain = press(inputs(base, install1, live("192.168.1.5", 46337), PW, Map.of()), hand);
        check(r, plain.result.outcome() == Outcome.OPENED && hand.spawns.size() == 1
                        && plain.result.windows().isEmpty(),
                "a hand Join press that opened its window records none (flips if a plain join's window is recorded -"
                        + " the console would then close a window the host opened for themselves): "
                        + plain.result.outcome() + " " + plain.result.windows());
        FakeEffects fx = twoKits(install1, install2);
        fx.spawnHandle = ProcessHandle.current();
        Pressed two = pressWatch(inputs(base, install1, live("0.0.0.0", 46337), PW,
                Map.of(ServeLauncher.ROW_GAME_ROOT_SECOND, install2.toString())), fx, base.resolve("flight"));
        List<JoinWindowSweep.Window> w = two.result.windows();
        check(r, two.result.outcome() == Outcome.OPENED && fx.spawns.size() == 2 && w.size() == 2
                        && OpsJoinSelf.cwd(install1).equals(w.get(0).kitDir())
                        && OpsJoinSelf.cwd(install2).equals(w.get(1).kitDir())
                        && FakeEffects.SPAWN_START_BASE.plusSeconds(1).equals(w.get(0).start())
                        && FakeEffects.SPAWN_START_BASE.plusSeconds(2).equals(w.get(1).start())
                        && w.get(0).handle().pid() == ProcessHandle.current().pid(),
                "the debug watch's press records BOTH windows it opened, the first game's kit then the second's, each"
                        + " with its own spawn's start instant (flips if a watch window is left out of the record - it"
                        + " would never be closed - or the records swap): " + two.result.outcome() + " " + w);
    }

    /**
     * {@code spawn-result-carries-handle}: the REAL spawn seam ({@link OpsJoinSelf#LIVE}) answers the handle of the
     * process it started and that process's own start instant, read while it lives. The subject is a game-free child
     * that opens no window (PING.EXE on Windows, sleep elsewhere; about 5 s, so the 2 s wait answers STILL_RUNNING
     * with slack on a loaded box), killed in a finally.
     */
    private static void spawnResultCarriesHandle(String r, Path base) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        List<String> argv = windows ? List.of(root + "\\System32\\PING.EXE", "-n", "6", "127.0.0.1")
                : List.of("/bin/sleep", "5");
        OpsJoinSelf.Spawned s;
        try {
            Files.createDirectories(base);
            s = OpsJoinSelf.LIVE.spawnWindow(argv, base, Map.of("SystemRoot", root));
        } catch (IOException e) {
            throw new SetupFailure("the game-free child did not start", e);
        }
        ProcessHandle h = s.handleOrNull();
        try {
            String exe = h == null ? "" : h.info().command().orElse("").toLowerCase(Locale.ROOT);
            check(r, h != null && s.code() == OpsJoinSelf.STILL_RUNNING && h.isAlive() && s.startOrNull() != null
                            && s.startOrNull().equals(h.info().startInstant().orElse(null))
                            && exe.endsWith(windows ? "ping.exe" : "sleep"),
                    "the real spawn answers the live handle of the process it started, with that process's own start"
                            + " instant (flips if the handle or the start instant is dropped - a debug watch's windows"
                            + " could then never be closed): code=" + s.code() + " handle="
                            + (h == null ? "none" : Long.toString(h.pid())) + " start=" + (s.startOrNull() != null));
        } finally {
            if (h != null) {
                h.destroyForcibly();
            }
        }
    }

    /**
     * {@code join-fx-delegates}: both {@code Effects} wrappers ({@code withProbe}, {@code withClientDiag} over a
     * path) declare EVERY {@code Effects} seam, and each of the six refresh seams called through a wrapper reaches
     * the wrapped fake (its own fault comes back, its {@code ops} records the call).
     *
     */
    private static void joinFxDelegates(String r, Path base) throws Exception {
        FakeEffects fake = new FakeEffects();
        Map<String, OpsJoinSelf.Effects> wrappers = new LinkedHashMap<>();
        wrappers.put("withProbe", fake.withProbe(() -> null));
        wrappers.put("withClientDiag", fake.withClientDiag(base.resolve("diag")));
        check(r, wrappers.values().stream().map(Object::getClass).distinct().count() == 2
                        && wrappers.values().stream().noneMatch(x -> x == fake),
                "the two wrappers are two classes of their own (flips if a wrapper returns the base itself, which would"
                        + " make this row vacuous)");
        List<String> missing = new ArrayList<>();
        int seams = 0;
        for (Method m : OpsJoinSelf.Effects.class.getMethods()) {
            if (m.getName().equals("withProbe") || m.getName().equals("withClientDiag")
                    || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            seams++;
            for (Map.Entry<String, OpsJoinSelf.Effects> wr : wrappers.entrySet()) {
                try {
                    wr.getValue().getClass().getDeclaredMethod(m.getName(), m.getParameterTypes());
                } catch (NoSuchMethodException absent) {
                    missing.add(wr.getKey() + "." + m.getName());
                }
            }
        }
        check(r, missing.isEmpty() && seams >= 15,
                "both wrappers declare every Effects seam (flips if a wrapper misses one - production would then run"
                        + " the throwing default): missing " + missing + " seams=" + seams);
        List<String> six = List.of("realPath", "readKitZip", "createStage", "writeNew", "moveDir", "deleteStage");
        Path x = base.resolve("x");
        for (String kind : wrappers.keySet()) {
            FakeEffects f = new FakeEffects();
            Map<String, IOException> markers = new LinkedHashMap<>();
            for (String name : six) {
                IOException marker = new IOException("marker " + name);
                markers.put(name, marker);
                f.failOn.put(name + "#1", marker);
            }
            OpsJoinSelf.Effects wr = kind.equals("withProbe") ? f.withProbe(() -> null) : f.withClientDiag(x);
            Map<String, SeamCall> calls = new LinkedHashMap<>();
            calls.put("realPath", () -> wr.realPath(x));
            calls.put("readKitZip", () -> wr.readKitZip(x));
            calls.put("createStage", () -> wr.createStage(x));
            calls.put("writeNew", () -> wr.writeNew(x, new byte[0]));
            calls.put("moveDir", () -> wr.moveDir(x, x));
            calls.put("deleteStage", () -> wr.deleteStage(x));
            List<String> reached = new ArrayList<>();
            for (Map.Entry<String, SeamCall> call : calls.entrySet()) {
                try {
                    call.getValue().call();
                    reached.add(call.getKey() + ":returned");
                } catch (IOException e) {
                    reached.add(e == markers.get(call.getKey()) ? call.getKey() : call.getKey() + ":" + e);
                } catch (RuntimeException e) {
                    reached.add(call.getKey() + ":" + e.getClass().getSimpleName());
                }
            }
            check(r, reached.equals(six) && f.ops.equals(six),
                    "(" + kind + ") each refresh seam through the wrapper reaches the wrapped fake (flips if a wrapper"
                            + " drops, re-targets or defaults one): " + reached + " ops=" + f.ops);
        }
    }

    // ---- join-second-window / join-second-refused ----

    /**
     * {@code join-second-window}: {@link OpsJoinSelf#pressWatch} with a second Game folder opens two windows in
     * order, each with its own diag folder and sidelog after the CC_* strip, the second with its own fresh seat key
     * in its env - never in the JVM flags, never on the first window; a blank second row opens one.
     *
     */
    private static void joinSecondWindow(String r, Path base) throws Exception {
        Path install1 = stageInstall(base.resolve("game1"), true, true);
        Path install2 = stageInstall(base.resolve("game2"), true, true);
        Path flight = base.resolve("flight");
        Path d1 = flight.resolve("client");
        Path d2 = flight.resolve("client2");
        OpsSnapshot wild = live("0.0.0.0", 46337);
        FakeEffects fx = twoKits(install1, install2);
        fx.env.put("CC_PREDICT_CLIENT_ID", "inherited-cid");
        fx.env.put("CC_WATCH_SIDELOG", "inherited-log");
        fx.env.put("cc_watch_sidelog", "inherited-low");
        fx.env.put("CC_WATCH_SIDELOG_MAX_MIB", "4321");
        fx.env.put("cc_watch_sidelog_max_mib", "4322");
        Pressed two = pressWatch(inputs(base, install1, wild, PW,
                Map.of(ServeLauncher.ROW_GAME_ROOT_SECOND, install2.toString(),
                        OpsJoinSelf.ROW_WATCH_CLIENT_LOG_MAX_MIB, " 300 ")), fx, flight);
        check(r, two.result.outcome() == Outcome.OPENED && fx.spawns.size() == 2
                        && install1.resolve("mods").resolve("CC_Client").equals(fx.spawns.get(0).cwd)
                        && install2.resolve("mods").resolve("CC_Client").equals(fx.spawns.get(1).cwd)
                        && two.stdout.contains("cc-ops join second=opened"),
                "a second Game folder opens two windows, the first game's kit then the second's, and says"
                        + " second=opened (flips if window 2 is not pressed or both run over one install): "
                        + two.result + " spawns=" + fx.spawns.size());
        Map<String, String> env1 = fx.spawns.get(0).env;
        Map<String, String> env2 = fx.spawns.get(1).env;
        String flags1 = String.valueOf(env1.get("CC_CLIENT_JVM_FLAGS"));
        String flags2 = String.valueOf(env2.get("CC_CLIENT_JVM_FLAGS"));
        check(r, (d1 + "\\cc-client.log").equals(env1.get("CC_WATCH_SIDELOG"))
                        && (d2 + "\\cc-client.log").equals(env2.get("CC_WATCH_SIDELOG"))
                        && flags1.contains(d1 + "\\frames") && !flags1.contains("client2")
                        && flags2.contains(d2 + "\\frames"),
                "each window's sidelog and diag flags sit in its OWN folder, client then client2 (flips if a sidelog"
                        + " path is shared - the appending client log would interleave - or the folders swap): "
                        + shownEnv(env1.get("CC_WATCH_SIDELOG")) + " " + shownEnv(env2.get("CC_WATCH_SIDELOG")));
        String cid2 = env2.get("CC_PREDICT_CLIENT_ID");
        String fileCid = lastRowValue(fx.files.get(settings(base)), "cid");
        check(r, cid2 != null && cid2.matches("[0-9a-f]{32}") && fileCid != null && !cid2.equals(fileCid)
                        && !env1.containsKey("CC_PREDICT_CLIENT_ID"),
                "window 2's env carries its own fresh 32-hex seat key, distinct from the key the settings file holds"
                        + " for window 1, and window 1's env carries none (flips if the key rides window 1 - the"
                        + " first seat would be taken over - or equals the file's): " + shownEnv(cid2) + " file="
                        + shownEnv(fileCid) + " w1=" + env1.containsKey("CC_PREDICT_CLIENT_ID"));
        check(r, !flags1.contains(cid2) && !flags2.contains(cid2)
                        && !String.valueOf(env2.get("JAVA_TOOL_OPTIONS")).contains(cid2),
                "no JVM-flags value carries the seat key (flips if the cid rides CC_CLIENT_JVM_FLAGS, which the kit"
                        + " hands to the game JVM's JAVA_TOOL_OPTIONS)");
        boolean inheritedGone = true;
        for (Map<String, String> env : List.of(env1, env2)) {
            inheritedGone &= !env.containsValue("inherited-cid") && !env.containsValue("inherited-log")
                    && !env.containsValue("inherited-low") && !env.containsKey("cc_watch_sidelog");
        }
        check(r, inheritedGone,
                "a parent CC_PREDICT_CLIENT_ID / CC_WATCH_SIDELOG / cc_watch_sidelog reaches neither window (flips if"
                        + " the strip runs after the re-adds, or a re-add copies the parent's value)");
        // The client log cap rides beside each window's sidelog, the
        // launcher row's value (blanks trimmed), never the parent's copy of the key in either spelling.
        boolean capRides = true;
        for (Map<String, String> env : List.of(env1, env2)) {
            capRides &= "300".equals(env.get("CC_WATCH_SIDELOG_MAX_MIB")) && !env.containsValue("4321")
                    && !env.containsValue("4322") && !env.containsKey("cc_watch_sidelog_max_mib");
        }
        check(r, capRides,
                "both windows carry CC_WATCH_SIDELOG_MAX_MIB=300 from the launcher row and no parent copy of the key"
                        + " (flips if the cap key is not put beside the sidelog on either window - that window's"
                        + " client log goes uncapped - or the parent's value survives the strip): "
                        + shownEnv(env1.get("CC_WATCH_SIDELOG_MAX_MIB")) + " "
                        + shownEnv(env2.get("CC_WATCH_SIDELOG_MAX_MIB")));
        check(r, List.of(d1.resolve("frames"), d2.resolve("frames")).equals(fx.dirsMade),
                "both windows' frames folders are made, window 1's first (flips if window 2 loses its diagnostics"
                        + " or shares window 1's folder): " + fx.dirsMade);
        check(r, !two.stdout.contains(cid2) && !two.stdout.contains(fileCid)
                        && !two.stdout.contains(cid2.substring(0, 8)) && !two.stdout.contains(fileCid.substring(0, 8)),
                "no stdout row carries either seat key or its 8-char prefix (flips if a press logs a key)");

        // a blank second row = ONE window, its sidelog set, no seat key in its env, no cid row minted
        FakeEffects one = twoKits(install1, install2);
        one.env.put("CC_PREDICT_CLIENT_ID", "inherited-cid");
        Pressed single = pressWatch(inputs(base, install1, wild, PW,
                Map.of(ServeLauncher.ROW_GAME_ROOT_SECOND, "  ")), one, flight);
        check(r, single.result.outcome() == Outcome.OPENED && one.spawns.size() == 1
                        && !one.spawns.get(0).env.containsKey("CC_PREDICT_CLIENT_ID")
                        && (d1 + "\\cc-client.log").equals(one.spawns.get(0).env.get("CC_WATCH_SIDELOG"))
                        && "1024".equals(one.spawns.get(0).env.get("CC_WATCH_SIDELOG_MAX_MIB"))
                        && single.stdout.contains("cc-ops join second=none")
                        && lastRowValue(one.files.get(settings(base)), "cid") == null,
                "a blank second Game folder opens ONE window with its sidelog, its cap at the 1024 MiB default (no"
                        + " cap row), no seat key in its env and no cid row written (flips if a blank row opens two, a"
                        + " lone window loses its cap or the default moves, or a lone unarmed window gets a key minted): "
                        + single.result + " spawns=" + one.spawns.size());
    }

    /**
     * {@code join-second-refused}: a second Game folder that is no game (R16), or the first game through another
     * spelling (R17 - {@code toRealPath} folds the case on Windows), refuses the WHOLE press before either window
     * is read, written, armed or started.
     */
    private static void joinSecondRefused(String r, Path base) throws Exception {
        Path install1 = stageInstall(base.resolve("game1"), true, true);
        Path notGame = base.resolve("notgame");
        try {
            Files.createDirectories(notGame);
        } catch (IOException e) {
            throw new SetupFailure("empty folder at " + notGame, e);
        }
        String upper = install1.toString().toUpperCase(Locale.ROOT);
        if (upper.equals(install1.toString())) {
            throw new SetupFailure("the install path has no lower-case letter to fold", null);
        }
        OpsSnapshot withCmd = liveWith("192.168.1.5", 46337, base.resolve("cmd.txt").toString());
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put(notGame.toString(), String.format(Locale.ROOT, OpsJoinSelf.R16,
                OpsJoinSelf.boundedText(notGame.toString())));
        cases.put(upper, String.format(Locale.ROOT, OpsJoinSelf.R17, OpsJoinSelf.boundedText(upper)));
        for (Map.Entry<String, String> c : cases.entrySet()) {
            FakeEffects fx = FakeEffects.withKit(install1);
            Pressed p = pressWatch(inputs(base, install1, withCmd, PW,
                    Map.of(ServeLauncher.ROW_GAME_ROOT_SECOND, c.getKey())).withAdminArm(true), fx, base.resolve("f"));
            check(r, p.result.outcome() == Outcome.REFUSED && "r1_second".equals(p.result.reasonToken())
                            && c.getValue().equals(p.result.text())
                            && p.stdout.contains("cc-ops join outcome=refused reason=r1_second"),
                    "a bad second Game folder = r1_second with its sentence (flips if the same-install test is a string"
                            + " compare, or a bad row 2 opens window 1 anyway): " + p.result);
            check(r, fx.spawns.isEmpty() && fx.writes.isEmpty() && fx.appends.isEmpty() && fx.reads.isEmpty()
                            && fx.dirsMade.isEmpty(),
                    "the refused press read, wrote, armed and started nothing (flips if window 1 runs before row 2 is"
                            + " checked): spawns=" + fx.spawns.size() + " writes=" + fx.writes.size() + " appends="
                            + fx.appends.size() + " reads=" + fx.reads.size());
        }
    }

    // ---- join-watch-log-cap ----

    /**
     * {@code join-watch-log-cap}: the server half of the cap knob's parse rows - {@link OpsJoinSelf#watchClientLogMaxMib}
     * over both range ends, blank, junk and a negative value - and a row it refuses ends the debug press before
     * anything is read, written, armed or started, with {@link OpsJoinSelf#R18} naming the row. The row's name is the
     * literal a host types (the commented template row), so a renamed constant flips here.
     *
     */
    private static void joinWatchLogCap(String r, Path base) throws Exception {
        Object[][] cases = {
            {null, 1024, "an absent row stops meaning the 1024 MiB default"},
            {"", 1024, "a blank row stops meaning the default"},
            {"  ", 1024, "an all-blank row stops meaning the default"},
            {"96", 96, "the low end (96) is refused"},
            {"65536", 65536, "the high end (65536) is refused"},
            {" 2048 ", 2048, "surrounding blanks stop being ignored"},
            {"95", -1, "one below the low end is accepted"},
            {"65537", -1, "one above the high end is accepted"},
            {"-96", -1, "a negative value is accepted"},
            {"+200", -1, "a signed value is accepted"},
            {"1g", -1, "a unit suffix is accepted"},
            {"junk", -1, "junk is accepted"},
            {"99999999999", -1, "an over-long value throws or wraps instead of refusing"},
        };
        for (Object[] c : cases) {
            String in = (String) c[0];
            int want = (Integer) c[1];
            int got = OpsJoinSelf.watchClientLogMaxMib(in);
            check(r, got == want, "watchClientLogMaxMib(" + shownEnv(in) + ") = " + got + " (want " + want
                    + ") - flips if " + c[2]);
        }
        Path install1 = stageInstall(base.resolve("game1"), true, true);
        OpsSnapshot withCmd = liveWith("192.168.1.5", 46337, base.resolve("cmd.txt").toString());
        String range = OpsJoinSelf.WATCH_CLIENT_LOG_MIN_MIB + " to " + OpsJoinSelf.WATCH_CLIENT_LOG_MAX_MIB;
        for (String bad : List.of("95", "-1", "lots")) {
            FakeEffects fx = FakeEffects.withKit(install1);
            Pressed p = pressWatch(inputs(base, install1, withCmd, PW,
                    Map.of("watch.clientLogMaxMiB", bad)).withAdminArm(true), fx, base.resolve("f"));
            String want = String.format(Locale.ROOT, OpsJoinSelf.R18, "watch.clientLogMaxMiB", bad);
            check(r, p.result.outcome() == Outcome.REFUSED && "client_log_row".equals(p.result.reasonToken())
                            && want.equals(p.result.text()) && p.result.text().contains(range)
                            && hasRow(p.stdout, "cc-ops join outcome=refused reason=client_log_row"),
                    "a watch.clientLogMaxMiB row of " + shownEnv(bad) + " refuses with client_log_row and the R18"
                            + " sentence naming the row and the range " + range + " (flips if a bad row opens a"
                            + " window anyway, the row is read under another name, or the sentence's range drifts"
                            + " from the parse): " + p.result);
            check(r, fx.spawns.isEmpty() && fx.writes.isEmpty() && fx.appends.isEmpty() && fx.reads.isEmpty()
                            && fx.dirsMade.isEmpty(),
                    "the refused press read, wrote, armed and started nothing (flips if the cap row is checked after"
                            + " window 1 runs): spawns=" + fx.spawns.size() + " writes=" + fx.writes.size()
                            + " appends=" + fx.appends.size() + " reads=" + fx.reads.size());
        }
    }

    // ---- join-cid-arm / join-arm-note ----

    /**
     * {@code join-cid-arm}: an armed press ({@code adminArm}; the console arms every press over a live command
     * file, row {@code join-arm-always}) mints this window's seat key FRESH, hands it to the
     * window through its env ({@code CC_PREDICT_CLIENT_ID}) - never the settings file, whose persisted {@code cid}
     * row stays untouched and is never the armed key, because that row reaches every CC server the host ever joins
     * - and appends exactly that key's {@code admin-cid} line before the spawn - the SEAM leg
     * through a REAL command file read back by the serve's own parser; two windows arm two keys, each before its
     * own spawn. The un-armed first-of-two window still settles the FILE's row (kept when lawful and read alike by
     * the window, else minted AND written).
     *
     */
    private static void joinCidArm(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        Path serve = base.resolve("serve");
        try {
            Files.createDirectories(serve);
        } catch (IOException e) {
            throw new SetupFailure("command-file folder at " + serve, e);
        }
        Path cmd = serve.resolve("cmd.txt");
        OpsSnapshot withCmd = liveWith("192.168.1.5", 46337, cmd.toString());
        if (!cmd.toString().equals(withCmd.commandFile())) {
            throw new SetupFailure("the status fixture did not carry the command file: " + withCmd.commandFile(), null);
        }
        StringBuilder allOut = new StringBuilder();
        List<String> allCids = new ArrayList<>();

        // (a) no saved cid, armed: a 32-hex key minted into the window's ENV, NO cid row written, its key appended
        //     to the real file before the spawn
        FakeEffects fa = FakeEffects.withKit(install);
        fa.cmdDiskRoot = base;
        Pressed a = press(inputs(base, install, withCmd, PW, Map.of()).withAdminArm(true), fa);
        allOut.append(a.stdout);
        List<String> rowsA = rowsOf(fa.files.get(settings(base)));
        String cidA = fa.spawns.isEmpty() ? null : fa.spawns.get(0).env.get("CC_PREDICT_CLIENT_ID");
        check(r, cidA != null && cidA.matches("[0-9a-f]{32}")
                        && Arrays.equals(OpsJoinSelf.merge(null, "192.168.1.5", 46337, PW), fa.files.get(settings(base)))
                        && hasRow(a.stdout, "cc-ops join cid=minted_env"),
                "(a) no saved cid: a 32-hex key is minted into the window's env and the settings file gets NO cid row"
                        + " - the plain merge's bytes (flips if the armed key is written to the file, the row the host"
                        + " sends to every server it joins): " + rowsA + " env=" + shownEnv(cidA));
        if (cidA == null) {
            return;
        }
        allCids.add(cidA);
        OperatorCommandFile.ParseResult parsed = OperatorCommandFile.parse(Files.readAllLines(cmd,
                StandardCharsets.UTF_8));
        check(r, parsed.commands().size() == 1 && parsed.rejected().isEmpty()
                        && parsed.commands().get(0).verb() == OperatorCommandFile.Verb.ADMIN_CID
                        && hexOf(cidA).equals(parsed.commands().get(0).text()),
                "(a) the real command file holds ONE line the serve's parser reads as admin-cid of exactly the env"
                        + " key (flips if the arm names another value, a malformed line, or none): "
                        + parsed.commands() + " rejected=" + parsed.rejected());
        check(r, fa.trail.equals(List.of("append:admin-cid", "spawn")) && a.stdout.contains("admin_arm=appended"),
                "(a) the arm reaches the command file BEFORE the spawn (flips if the arm moves after the window"
                        + " starts): " + fa.trail);

        // (b) THE DOOR ROW: a lawful saved cid + an armed press: the file's row stays byte-identical and is NEVER
        //     the armed key - the arm names a fresh env mint equal to no stored cid= row
        FakeEffects fb = FakeEffects.withKit(install);
        fb.files.put(settings(base), ascii("name=x\r\ncid=rig-cid-01\r\n"));
        Pressed b = press(inputs(base, install, withCmd, PW, Map.of()).withAdminArm(true), fb);
        allOut.append(b.stdout);
        allCids.add("rig-cid-01");
        List<String> rowsB = rowsOf(fb.files.get(settings(base)));
        long cidRowsB = rowsB.stream().filter(x -> x.toLowerCase(Locale.ROOT).matches("\\s*cid\\s*=.*")).count();
        String envB = fb.spawns.isEmpty() ? null : fb.spawns.get(0).env.get("CC_PREDICT_CLIENT_ID");
        check(r, cidRowsB == 1 && rowsB.contains("cid=rig-cid-01") && envB != null && envB.matches("[0-9a-f]{32}")
                        && fb.appends.equals(List.of("admin-cid " + hexOf(envB)))
                        && hasRow(b.stdout, "cc-ops join cid=minted_env"),
                "(b) the saved cid row stays byte-identical and is never the armed key - the ONE arm line names a"
                        + " fresh 32-hex env mint, not the stored row (flips if the persisted cid - held in full by the"
                        + " ops-status file of every server this host ever joined - is armed as the admin key): "
                        + rowsB + " appends=" + fb.appends.size());
        if (envB != null) {
            allCids.add(envB);
        }

        // (c) the un-armed FIRST-of-two window settles the FILE's row: a saved cid the press must not keep is
        //     re-minted and WRITTEN (the old row gone), a lawful one kept byte-identical; nothing armed either way
        Path install2 = stageInstall(base.resolve("game2"), true, true);
        Map<String, String> second = Map.of(ServeLauncher.ROW_GAME_ROOT_SECOND, install2.toString());
        List<String> badRows = List.of("cid=a\\b", "cid=" + "c".repeat(65), "cid=", "cid=abc ", "CID=upper-key");
        for (String bad : badRows) {
            FakeEffects fc = twoKits(install, install2);
            fc.files.put(settings(base), ascii("name=x\r\n" + bad + "\r\n"));
            Pressed c = pressWatch(inputs(base, install, withCmd, PW, second), fc, null);
            allOut.append(c.stdout);
            List<String> rowsC = rowsOf(fc.files.get(settings(base)));
            List<String> cidRowsC = rowsC.stream().filter(x -> x.toLowerCase(Locale.ROOT).matches("\\s*cid\\s*=.*"))
                    .toList();
            String minted = lastRowValue(fc.files.get(settings(base)), "cid");
            check(r, cidRowsC.size() == 1 && minted != null && minted.matches("[0-9a-f]{32}") && !rowsC.contains(bad)
                            && fc.appends.isEmpty() && hasRow(c.stdout, "cc-ops join cid=minted"),
                    "(c) un-armed window 1 of two: saved '" + bad + "' is re-minted and written, the old row gone,"
                            + " nothing armed (flips if an unsendable cid, or one the window reads differently -"
                            + " backslash, trailing blank, case variant - is kept, or an un-armed press arms): "
                            + rowsC + " " + fc.appends);
            if (minted != null) {
                allCids.add(minted);
            }
        }
        FakeEffects fk = twoKits(install, install2);
        fk.files.put(settings(base), ascii("name=x\r\ncid=rig-cid-01\r\n"));
        Pressed k = pressWatch(inputs(base, install, withCmd, PW, second), fk, null);
        allOut.append(k.stdout);
        check(r, "rig-cid-01".equals(lastRowValue(fk.files.get(settings(base)), "cid")) && fk.appends.isEmpty()
                        && hasRow(k.stdout, "cc-ops join cid=kept"),
                "(c) un-armed window 1 of two: a lawful saved cid is kept byte-identical, nothing armed (flips if a"
                        + " good cid is re-minted - a new seat key - or an un-armed press arms): " + fk.appends);

        // (d) an un-armed press, one window: the 4-arg merge's bytes, no arm, no cid row printed - the core's own law
        //     even beside a live command file (the console's presses over one are armed: join-arm-always)
        FakeEffects fd = FakeEffects.withKit(install);
        byte[] savedD = ascii("name=x\r\ncid=keep-me\r\nCID=other\r\n");
        fd.files.put(settings(base), savedD);
        Pressed d = press(inputs(base, install, withCmd, PW, Map.of()), fd);
        allOut.append(d.stdout);
        check(r, Arrays.equals(OpsJoinSelf.merge(savedD, "192.168.1.5", 46337, PW), fd.files.get(settings(base)))
                        && fd.appends.isEmpty() && fd.trail.equals(List.of("spawn"))
                        && !d.stdout.contains("cc-ops join cid=") && !d.stdout.contains("admin_arm"),
                "(d) an un-armed press, one window: the settings bytes are the plain merge's, 0 appends, no cid or arm"
                        + " row (flips if an unarmed lone press mints, rewrites a cid row or arms): " + fd.trail);

        // (e) two windows, an armed press: two FRESH env keys armed, each before its own spawn, nothing between
        //     the windows; the file's saved row untouched and never armed
        FakeEffects fe = twoKits(install, install2);
        fe.files.put(settings(base), ascii("name=x\r\ncid=rig-cid-01\r\n"));
        Pressed e = pressWatch(inputs(base, install, withCmd, PW, second).withAdminArm(true), fe, null);
        allOut.append(e.stdout);
        String c1 = fe.spawns.size() == 2 ? fe.spawns.get(0).env.get("CC_PREDICT_CLIENT_ID") : null;
        String c2 = fe.spawns.size() == 2 ? fe.spawns.get(1).env.get("CC_PREDICT_CLIENT_ID") : null;
        check(r, c1 != null && c2 != null && !c1.equals(c2) && !"rig-cid-01".equals(c1) && !"rig-cid-01".equals(c2)
                        && "rig-cid-01".equals(lastRowValue(fe.files.get(settings(base)), "cid"))
                        && fe.appends.equals(List.of("admin-cid " + hexOf(c1), "admin-cid " + hexOf(c2)))
                        && fe.trail.equals(List.of("append:admin-cid", "spawn", "append:admin-cid", "spawn")),
                "(e) two windows arm two fresh env keys - window 1's then window 2's - each before its own spawn, the"
                        + " saved row untouched and un-armed (flips if only one window is armed, window 2 arms window"
                        + " 1's key, a key equals the saved row, or an arm lands after its spawn): " + fe.trail
                        + " appends=" + fe.appends.size());
        String admin = "Join window opening (it dials 192.168.1.5:46337) - press Launch in it; paste nothing - it"
                + " signs in as admin.";
        check(r, e.result.outcome() == Outcome.OPENED && (admin + " Second game folder: " + admin).equals(e.result.text()),
                "(e) the answer = window 1's admin note, then the second folder's (flips if the second answer is"
                        + " dropped or a window's note ignores its arm): " + e.result.text());
        if (c1 != null) {
            allCids.add(c1);
        }
        if (c2 != null) {
            allCids.add(c2);
        }
        String out = allOut.toString();
        for (String cid : allCids) {
            check(r, !out.contains(cid) && !out.contains(hexOf(cid)) && (cid.length() < 8
                            || !out.contains(cid.substring(0, 8))),
                    "no stdout row carries a seat key, its door key or its 8-char prefix (flips if the cid, the key or"
                            + " a print of either reaches a log row)");
        }
    }

    /**
     * {@code join-arm-note}: the admin note only when the arm line landed; no command file, a blank one or a failed
     * append opens the window plain - never refused.
     */
    private static void joinArmNote(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        String plain = "Join window opening (it dials 192.168.1.5:46337) - press Launch in it.";
        String admin = "Join window opening (it dials 192.168.1.5:46337) - press Launch in it; paste nothing - it"
                + " signs in as admin.";
        OpsSnapshot withCmd = liveWith("192.168.1.5", 46337, base.resolve("cmd.txt").toString());
        FakeEffects armed = FakeEffects.withKit(install);
        Pressed a = press(inputs(base, install, withCmd, PW, Map.of()).withAdminArm(true), armed);
        check(r, a.result.outcome() == Outcome.OPENED && admin.equals(a.result.text()) && armed.appends.size() == 1,
                "armed: the admin note, one line appended (flips if the note stays plain after a landed arm): "
                        + a.result.text());
        Map<String, OpsSnapshot> noFile = new LinkedHashMap<>();
        noFile.put("off", live("192.168.1.5", 46337));
        noFile.put("blank", liveWith("192.168.1.5", 46337, ""));
        for (Map.Entry<String, OpsSnapshot> nf : noFile.entrySet()) {
            FakeEffects fx = FakeEffects.withKit(install);
            Pressed p = press(inputs(base, install, nf.getValue(), PW, Map.of()).withAdminArm(true), fx);
            check(r, p.result.outcome() == Outcome.OPENED && plain.equals(p.result.text()) && fx.appends.isEmpty()
                            && fx.trail.equals(List.of("spawn"))
                            && p.stdout.contains("cc-ops join admin_arm=skipped_no_command_file"),
                    "command file " + nf.getKey() + ": skipped row, plain note, the window still opens (flips if a"
                            + " serve with no command file refuses the window or shows the admin note): " + p.result);
        }
        FakeEffects failing = FakeEffects.withKit(install);
        failing.failOn.put("appendCommandLine#1", new IOException("locked"));
        Pressed f = press(inputs(base, install, withCmd, PW, Map.of()).withAdminArm(true), failing);
        check(r, f.result.outcome() == Outcome.OPENED && plain.equals(f.result.text()) && failing.spawns.size() == 1
                        && failing.appends.isEmpty() && f.stdout.contains("cc-ops join admin_arm=failed (IOException)"),
                "a failed append: still OPENED with the plain note and one failed row naming the class (flips if a"
                        + " failed arm refuses the window or the admin note is shown without an armed line): "
                        + f.result);
        FakeEffects unarmed = FakeEffects.withKit(install);
        Pressed u = press(inputs(base, install, withCmd, PW, Map.of()), unarmed);
        check(r, u.result.outcome() == Outcome.OPENED && plain.equals(u.result.text()) && unarmed.appends.isEmpty()
                        && !u.stdout.contains("admin_arm"),
                "an un-armed press: the plain note, no arm row (flips if an unarmed press arms or claims admin): "
                        + u.result.text());
    }

    // ---- join-arm-always / join-reopened-plain ----

    /**
     * {@code join-arm-always}: the console's arm bit is {@link OpsJoinSelf#armFileOrNull} over the live snapshot
     * ALONE - a serve naming a command file arms a press whose inputs hold no admin secret (the match password
     * only): the window gets a fresh env key ({@code cid=minted_env}), the REAL command file gets that key's ONE
     * {@code admin-cid} line before the spawn, the reel records the line as {@code <redacted>}, the settings file
     * gets the plain merge's bytes (no cid row) and no stdout row carries the key; a serve whose arm line cannot
     * land (command file off or blank, no live serve) is not armed.
     */
    private static void joinArmAlways(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        Path serve = base.resolve("serve");
        try {
            Files.createDirectories(serve);
        } catch (IOException e) {
            throw new SetupFailure("command-file folder at " + serve, e);
        }
        Path cmd = serve.resolve("cmd.txt");
        OpsSnapshot withCmd = liveWith("192.168.1.5", 46337, cmd.toString());
        if (!cmd.toString().equals(withCmd.commandFile())) {
            throw new SetupFailure("the status fixture did not carry the command file: " + withCmd.commandFile(), null);
        }
        check(r, cmd.toString().equals(OpsJoinSelf.armFileOrNull(withCmd)),
                "a live serve naming a command file arms the press, no admin secret read (flips if the arm needs a"
                        + " saved admin secret again, or ignores a live command file): "
                        + (OpsJoinSelf.armFileOrNull(withCmd) == null ? "not armed" : "armed elsewhere"));
        Map<String, OpsSnapshot> none = new LinkedHashMap<>();
        none.put("off", live("192.168.1.5", 46337));
        none.put("blank", liveWith("192.168.1.5", 46337, ""));
        none.put("no live serve", null);
        for (Map.Entry<String, OpsSnapshot> n : none.entrySet()) {
            check(r, OpsJoinSelf.armFileOrNull(n.getValue()) == null,
                    "command file " + n.getKey() + ": not armed (flips if the console arms a press whose arm line"
                            + " cannot land - its window would claim admin with nothing armed)");
        }

        OpsJoinSelf.Inputs in = inputs(base, install, withCmd, PW, Map.of())
                .withAdminArm(OpsJoinSelf.armFileOrNull(withCmd) != null);
        FakeEffects fx = FakeEffects.withKit(install);
        fx.cmdDiskRoot = base;
        Pressed a = press(in, fx);
        String key = fx.spawns.isEmpty() ? null : fx.spawns.get(0).env.get("CC_PREDICT_CLIENT_ID");
        String admin = "Join window opening (it dials 192.168.1.5:46337) - press Launch in it; paste nothing - it"
                + " signs in as admin.";
        check(r, in.adminArm() && a.result.outcome() == Outcome.OPENED && admin.equals(a.result.text())
                        && key != null && key.matches("[0-9a-f]{32}") && hasRow(a.stdout, "cc-ops join cid=minted_env")
                        && hasRow(a.stdout, "cc-ops join admin_arm=appended")
                        && fx.trail.equals(List.of("append:admin-cid", "spawn")),
                "no admin secret in the inputs, a live command file: the press mints a fresh env key, appends its"
                        + " arm before the spawn and answers the admin note (flips if the host's window opens plain"
                        + " because no admin secret is saved): adminArm=" + in.adminArm() + " " + fx.trail + " "
                        + a.result.text());
        if (key == null) {
            return;
        }
        OperatorCommandFile.ParseResult parsed = OperatorCommandFile.parse(Files.readAllLines(cmd,
                StandardCharsets.UTF_8));
        check(r, parsed.commands().size() == 1 && parsed.rejected().isEmpty()
                        && parsed.commands().get(0).verb() == OperatorCommandFile.Verb.ADMIN_CID
                        && hexOf(key).equals(parsed.commands().get(0).text())
                        && OperatorCommandFile.REDACTED.equals(OperatorCommandFile.recordedText(parsed.commands().get(0))),
                "the real command file holds ONE admin-cid line of exactly the env key, and the reel records it as"
                        + " <redacted> (flips if the arm names another value, or the key reaches a recording): "
                        + parsed.commands().size() + " command(s), rejected=" + parsed.rejected().size());
        check(r, Arrays.equals(OpsJoinSelf.merge(null, "192.168.1.5", 46337, PW), fx.files.get(settings(base))),
                "the settings file gets the plain merge's bytes - no cid row, no admin secret (flips if the armed key"
                        + " or a secret lands in join-settings.properties): " + rowsOf(fx.files.get(settings(base))));
        check(r, !a.stdout.contains(key) && !a.stdout.contains(hexOf(key)) && !a.stdout.contains(key.substring(0, 8)),
                "no stdout row carries the armed key, its door form or its 8-char prefix (flips if the key reaches a"
                        + " log row)");
    }

    /**
     * {@code join-reopened-plain}: a press the console ARMS (a live command file) that meets a live join window
     * answers REOPENED and arms nothing - no {@code admin-cid} line, no settings write, no spawn, no cid or arm row -
     * and both REOPENED notes name the host's two ways back as admin after a restart: the ship editor's host card and
     * a fresh Join press.
     */
    private static void joinReopenedPlain(String r, Path base) throws Exception {
        Path install = stageInstall(base.resolve("game"), true, true);
        Path cmd = base.resolve("cmd.txt");
        OpsSnapshot withCmd = liveWith("192.168.1.5", 46337, cmd.toString());
        FakeEffects fx = FakeEffects.withKit(install);
        fx.cmdDiskRoot = base;
        Path endpoint = OpsJoinSelf.endpointFile(base.resolve("lad").toString(), install);
        fx.files.put(endpoint, ascii("46000|" + TOKEN + "|" + NONCE));
        fx.pings.put(46000, "{\"ok\":true,\"app\":\"join\"}");
        OpsJoinSelf.Inputs in = inputs(base, install, withCmd, PW, Map.of())
                .withAdminArm(OpsJoinSelf.armFileOrNull(withCmd) != null);
        if (!in.adminArm()) {
            throw new SetupFailure("the console-shaped inputs were not armed over a live command file", null);
        }
        Pressed a = press(in, fx);
        check(r, a.result.outcome() == Outcome.REOPENED && fx.appends.isEmpty() && fx.trail.isEmpty()
                        && fx.writes.isEmpty() && fx.spawns.isEmpty() && !Files.exists(cmd)
                        && !a.stdout.contains("admin_arm") && !a.stdout.contains("cc-ops join cid="),
                "an armed press meeting a live window = REOPENED with 0 arm lines, 0 writes, 0 spawns (flips if a"
                        + " REOPENED press re-arms the live window - a grant credential kept across serves - or the"
                        + " live-window step moves after the arm): " + a.result.outcome() + " " + fx.trail);
        check(r, OpsJoinSelf.NOTE_REOPENED.equals(a.result.text()),
                "the answer is the REOPENED note (flips if the press answers another text): " + a.result.text());
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("NOTE_REOPENED", OpsJoinSelf.NOTE_REOPENED);
        notes.put("NOTE_REOPENED_WEB", OpsJoinSelf.NOTE_REOPENED_WEB);
        for (Map.Entry<String, String> n : notes.entrySet()) {
            String t = n.getValue();
            check(r, t.contains("restart") && t.contains("'Ships on this server' card")
                            && t.contains("press Join from this PC again"),
                    n.getKey() + " names the ship editor's host card and a fresh Join press as the way back to admin"
                            + " after a restart (flips if the note drops the host's path back to admin): " + t);
        }
    }

    // ---- join-settings-scrub ----

    /**
     * {@code join-settings-scrub}: {@link OpsJoinSelf#scrubSettingsSecret} drops every password / passwordFor row
     * (case-blind, a continued value's tail too) from BOTH settings files and keeps every other row byte-verbatim
     * in order - the cid above all; a clean file is not written; a missing one is absent; an over-cap file fails
     * untouched while the other file is still scrubbed.
     */
    private static void joinSettingsScrub(String r, Path base) throws Exception {
        Path lad = base.resolve("lad");
        Path ad = base.resolve("ad");
        Path current = settingsUnder(lad);
        Path legacy = settingsUnder(ad);
        FakeEffects fx = new FakeEffects();
        fx.files.put(current, ascii("name=a\r\nhost=h\r\nport=1\r\npassword=p\r\npasswordFor=h\\:1\r\nPassword = q\r\n"
                + "cid=c\r\nextra=1\r\n"));
        fx.files.put(legacy, ascii("password=ab\\\r\n  cd\r\ncid=legacy\r\n"));
        Printed s = printed(() -> OpsJoinSelf.scrubSettingsSecret(fx, lad.toString(), ad.toString(), base.toString()));
        check(r, "scrubbed".equals(s.value()) && s.stdout().trim().equals("cc-ops join settings_secret=scrubbed")
                        && Arrays.equals(ascii("name=a\r\nhost=h\r\nport=1\r\ncid=c\r\nextra=1\r\n"), fx.files.get(current)),
                "the current file loses every password / passwordFor row (any case) and keeps every other row in order,"
                        + " the cid included; ONE stdout row, no value (flips if a secret row survives or another row is"
                        + " lost): " + s.value() + " " + shown(fx.files.get(current)));
        check(r, Arrays.equals(ascii("cid=legacy\r\n"), fx.files.get(legacy)),
                "the legacy roaming file is scrubbed too, a continued secret's tail with it (flips if the legacy file"
                        + " keeps its secret, or a continuation row survives as a key): " + shown(fx.files.get(legacy)));

        FakeEffects clean = new FakeEffects();
        clean.files.put(current, ascii("name=a\r\ncid=c\r\n"));
        Printed c = printed(() -> OpsJoinSelf.scrubSettingsSecret(clean, lad.toString(), ad.toString(), base.toString()));
        check(r, "clean".equals(c.value()) && clean.writes.isEmpty(),
                "a file with no secret row = clean and NOT written (flips if every scrub rewrites): " + c.value());

        FakeEffects none = new FakeEffects();
        Printed n = printed(() -> OpsJoinSelf.scrubSettingsSecret(none, lad.toString(), ad.toString(), base.toString()));
        check(r, "absent".equals(n.value()) && none.writes.isEmpty(),
                "no settings file = absent, nothing written (flips if a missing file is created): " + n.value());

        FakeEffects big = new FakeEffects();
        byte[] over = new byte[OpsJoinSelf.SETTINGS_MAX_BYTES + 1];
        Arrays.fill(over, (byte) 'x');
        big.files.put(current, over.clone());
        big.files.put(legacy, ascii("password=old\r\ncid=legacy\r\n"));
        Printed g = printed(() -> OpsJoinSelf.scrubSettingsSecret(big, lad.toString(), ad.toString(), base.toString()));
        check(r, "failed (IOException)".equals(g.value()) && Arrays.equals(over, big.files.get(current))
                        && Arrays.equals(ascii("cid=legacy\r\n"), big.files.get(legacy))
                        && g.stdout().contains("cc-ops join settings_secret=failed (IOException)"),
                "an over-cap file fails untouched while the legacy file is still scrubbed (flips if the cap is lost or"
                        + " one failure skips the other file): " + g.value());
    }

    // ---- fixtures ----

    /** A press's answer + everything it printed. */
    private record Pressed(OpsJoinSelf.Result result, String stdout) {
    }

    /** {@link OpsJoinSelf#press} with {@code System.out} captured. */
    private static Pressed press(OpsJoinSelf.Inputs in, OpsJoinSelf.Effects fx) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        OpsJoinSelf.Result result;
        try (PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            result = OpsJoinSelf.press(in, fx);
        } finally {
            System.setOut(original);
        }
        return new Pressed(result, buf.toString(StandardCharsets.UTF_8));
    }

    /** A press's inputs over {@code base}: the Game folder row = {@code install}, LOCALAPPDATA = base/lad,
     *  APPDATA = base/ad, the kit hint {@code CC_Client-kit.zip}; the props path null. */
    private static OpsJoinSelf.Inputs inputs(Path base, Path install, OpsSnapshot live, String pw,
            Map<String, String> extraRows) {
        Map<String, String> rows = new HashMap<>(extraRows);
        rows.put(ServeLauncher.ROW_GAME_ROOT, install.toString());
        return new OpsJoinSelf.Inputs(base, rows, live, pw, "CC_Client-kit.zip", base.resolve("lad").toString(),
                base.resolve("ad").toString(), base.toString(), null);
    }

    /** The current settings file under {@link #inputs}' LOCALAPPDATA (the literal client layout). */
    private static Path settings(Path base) {
        return base.resolve("lad").resolve("cc-starsector").resolve("join-settings.properties");
    }

    /** {@code <folder>\cc-starsector\join-settings.properties} (the literal client layout); an env value as a
     *  failure message shows it ({@code null} or quoted); {@code call}'s answer + what it printed.
     * */
    private static Path settingsUnder(Path folder) {
        return folder.resolve("cc-starsector").resolve("join-settings.properties");
    }

    private static String shownEnv(String value) {
        return value == null ? "null" : "'" + value + "'";
    }

    /** Captured stdout holds {@code row} as a WHOLE line ({@code cid=minted} must not match {@code cid=minted_env}). */
    private static boolean hasRow(String stdout, String row) {
        return stdout.lines().anyMatch(row::equals);
    }

    private record Printed(String value, String stdout) {
    }

    private static Printed printed(Supplier<String> call) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        String value;
        try (PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            value = call.get();
        } finally {
            System.setOut(original);
        }
        return new Printed(value, buf.toString(StandardCharsets.UTF_8));
    }

    /** A live snapshot of a server bound to {@code host:port} (VpnLeaseSelftest's fixture shape). */
    private static OpsSnapshot live(String host, int port) {
        return liveWith(host, port, "off");
    }

    /** {@link #live} with the serve's command file set ({@code "off"} = none; no seat rows).
     * */
    private static OpsSnapshot liveWith(String host, int port, String commandFile) {
        return OpsSnapshot.parse(OpsStatusFile.composeLines(1L, 1L, "lobby",
                host, port, 7, 0, 0, 0, 4, 60.0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, commandFile,
                List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY));
    }

    /** {@link OpsJoinSelf#pressWatch} with {@code System.out} captured ({@link #press}'s shape).
     * */
    private static Pressed pressWatch(OpsJoinSelf.Inputs in, OpsJoinSelf.Effects fx, Path flightOrNull) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        OpsJoinSelf.Result result;
        try (PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            result = OpsJoinSelf.pressWatch(in, fx, flightOrNull);
        } finally {
            System.setOut(original);
        }
        return new Pressed(result, buf.toString(StandardCharsets.UTF_8));
    }

    /** {@link FakeEffects#withKit} over BOTH games (the second pilot window's rows). */
    private static FakeEffects twoKits(Path install1, Path install2) {
        FakeEffects fx = FakeEffects.withKit(install1);
        fx.files.put(install2.resolve("starsector.exe"), new byte[] {1});
        fx.files.put(install2.resolve("mods").resolve("CC_Client").resolve("join-cc.bat"), new byte[] {1});
        return fx;
    }

    /** A settings file's rows (CRLF-split, ISO-8859-1); null bytes = no rows. */
    private static List<String> rowsOf(byte[] bytes) {
        return bytes == null ? List.of()
                : Arrays.asList(new String(bytes, StandardCharsets.ISO_8859_1).split("\r\n"));
    }

    /** The value of the LAST row that starts exactly {@code key=} (case-sensitive, as the window's load), or null. */
    private static String lastRowValue(byte[] bytes, String key) {
        String value = null;
        for (String row : rowsOf(bytes)) {
            if (row.startsWith(key + "=")) {
                value = row.substring(key.length() + 1);
            }
        }
        return value;
    }

    /** A seat key's door key, computed here (lowercase hex of the UTF-8), never by the code under test. */
    private static String hexOf(String cid) {
        return HexFormat.of().formatHex(cid.getBytes(StandardCharsets.UTF_8));
    }

    /** A game folder of real files: the engine jar in starsector-core, + starsector.exe, + the kit bat. */
    private static Path stageInstall(Path at, boolean exe, boolean bat) throws SetupFailure {
        try {
            Path core = at.resolve("starsector-core");
            Files.createDirectories(core);
            Files.write(core.resolve("starfarer_obf.jar"), new byte[] {1});
            if (exe) {
                Files.write(at.resolve("starsector.exe"), new byte[] {1});
            }
            if (bat) {
                Path kit = at.resolve("mods").resolve("CC_Client");
                Files.createDirectories(kit);
                Files.write(kit.resolve("join-cc.bat"), new byte[] {1});
            }
            return at;
        } catch (IOException e) {
            throw new SetupFailure("install fixture at " + at, e);
        }
    }

    /** One recorded spawn. */
    private record Spawn(List<String> argv, Path cwd, Map<String, String> env) {
    }

    /** Every seam faked and recorded: files = a map, pings = port -> body (or the REAL ping, {@link #livePing}),
     *  spawns = a list; the probe, the write and the spawn settable to fail. */
    private static final class FakeEffects implements OpsJoinSelf.Effects {
        final Map<Path, byte[]> files = new HashMap<>();
        final Map<Integer, String> pings = new HashMap<>();
        final List<Path> reads = new ArrayList<>();
        final List<Path> writes = new ArrayList<>();
        final List<Spawn> spawns = new ArrayList<>();
        /** Every {@link #createDirectories} call in order (the diag decorator's two folders, nothing else). */
        final List<Path> dirsMade = new ArrayList<>();
        final Map<String, String> env = new LinkedHashMap<>(Map.of("PATH", "p"));
        VpnLeaseProbe.Probe probe;
        IOException probeFailure;
        IOException writeFailure;
        int probeCalls;
        int spawnExit = OpsJoinSelf.STILL_RUNNING;

        /** The exe + the kit bat present (the literal kit layout, never the core's constants). */
        static FakeEffects withKit(Path install) {
            FakeEffects fx = new FakeEffects();
            fx.files.put(install.resolve("starsector.exe"), new byte[] {1});
            fx.files.put(install.resolve("mods").resolve("CC_Client").resolve("join-cc.bat"), new byte[] {1});
            return fx;
        }

        /** The real-disk rows' switch (the kit-refresh rows, {@code join-self-writes-no-kit-file}): a path under this
         *  folder is the REAL disk through {@link OpsJoinSelf#LIVE} (its temp + atomic move, a real
         *  lastModifiedTime); null (every other row) = the map only. Reads and writes are recorded either way.
         * */
        Path diskRoot;

        private boolean onDisk(Path file) {
            return diskRoot != null && file.startsWith(diskRoot);
        }

        @Override
        public boolean isRegularFile(Path file) {
            return onDisk(file) ? OpsJoinSelf.LIVE.isRegularFile(file) : files.containsKey(file);
        }

        @Override
        public byte[] readBounded(Path file, int cap) throws IOException {
            reads.add(file);
            if (onDisk(file)) {
                return OpsJoinSelf.LIVE.readBounded(file, cap);
            }
            byte[] bytes = files.get(file);
            if (bytes == null) {
                throw new NoSuchFileException(file.getFileName().toString());
            }
            if (bytes.length > cap) {
                throw new IOException("over cap");
            }
            return bytes.clone();
        }

        @Override
        public void writeAtomic(Path file, byte[] bytes) throws IOException {
            writes.add(file);
            if (writeFailure != null) {
                throw writeFailure;
            }
            if (onDisk(file)) {
                OpsJoinSelf.LIVE.writeAtomic(file, bytes);
                return;
            }
            files.put(file, bytes.clone());
        }

        /** {@code join-ping}'s switch: true = {@link #pingBody} is the REAL loopback GET ({@link OpsJoinSelf#LIVE})
         *  while the files stay fake; false (the default, every other row) = the {@link #pings} map.
         * */
        boolean livePing;

        @Override
        public String pingBody(int port, String token) throws IOException {
            if (livePing) {
                return OpsJoinSelf.LIVE.pingBody(port, token);
            }
            return TOKEN.equals(token) ? pings.get(port) : null;
        }

        @Override
        public VpnLeaseProbe.Probe probe() throws IOException {
            probeCalls++;
            if (probeFailure != null) {
                throw probeFailure;
            }
            return probe;
        }

        @Override
        public void createDirectories(Path dir) {
            dirsMade.add(dir);
        }

        @Override
        public int spawn(List<String> argv, Path cwd, Map<String, String> childEnv) {
            spawns.add(new Spawn(List.copyOf(argv), cwd, Map.copyOf(childEnv)));
            trail.add("spawn");
            return spawnExit;
        }

        /** The handle every {@link #spawnWindow} answers with (null = none, as the interface default); window N's
         *  start instant = {@link #SPAWN_START_BASE} + N seconds, so each window's record is its own. Never killed:
         *  the press rows read the record, they never sweep. */
        ProcessHandle spawnHandle;
        static final Instant SPAWN_START_BASE = Instant.ofEpochSecond(1_700_000_000L);

        @Override
        public OpsJoinSelf.Spawned spawnWindow(List<String> argv, Path cwd, Map<String, String> childEnv) {
            int code = spawn(argv, cwd, childEnv);
            return spawnHandle == null ? new OpsJoinSelf.Spawned(code, null, null)
                    : new OpsJoinSelf.Spawned(code, spawnHandle, SPAWN_START_BASE.plusSeconds(spawns.size()));
        }

        /** The seat-key arm's seam: every {@link #appendCommandLine} attempt adds {@code append:<verb>} to
         *  {@link #trail} (beside each {@code spawn}, so a row reads the arm-before-spawn order), a key in
         *  {@link #failOn} ({@code "appendCommandLine#1"}) throws instead, else the line joins {@link #appends} and,
         *  with {@link #cmdDiskRoot} set, reaches the REAL file through {@link OpsJoinSelf#LIVE}. {@link #ops} is
         *  never touched (the refresh rows compare it exactly). */
        final List<String> appends = new ArrayList<>();
        final List<String> trail = new ArrayList<>();
        Path cmdDiskRoot;

        @Override
        public void appendCommandLine(Path commandFile, String line) throws IOException {
            trail.add("append:" + line.split(" ", 2)[0]);
            IOException fail = failOn.get("appendCommandLine#"
                    + opCounts.merge("appendCommandLine", 1, Integer::sum));
            if (fail != null) {
                throw fail;
            }
            appends.add(line);
            if (cmdDiskRoot != null) {
                OpsJoinSelf.LIVE.appendCommandLine(commandFile, line);
            }
        }

        @Override
        public Map<String, String> parentEnv() {
            return env;
        }

        @Override
        public String systemRoot() {
            return "C:\\Windows";
        }

        /** The kit refresh's six seams: each call's name appended to {@link #ops} IN ORDER; a key in
         *  {@link #failOn} ({@code "moveDir#2"} = the 2nd {@code moveDir} call) throws its exception instead of the
         *  call; else {@link #readKitZip} answers {@link #zipOverride} when set, and every call runs
         *  {@link OpsJoinSelf#LIVE} = the REAL disk (the rows' temp folders).
         * */
        final List<String> ops = new ArrayList<>();
        final Map<String, IOException> failOn = new HashMap<>();
        List<KitZip.Entry> zipOverride;
        private final Map<String, Integer> opCounts = new HashMap<>();

        private void op(String name) throws IOException {
            ops.add(name);
            IOException fail = failOn.get(name + "#" + opCounts.merge(name, 1, Integer::sum));
            if (fail != null) {
                throw fail;
            }
        }

        @Override
        public Path realPath(Path p) throws IOException {
            op("realPath");
            return OpsJoinSelf.LIVE.realPath(p);
        }

        @Override
        public List<KitZip.Entry> readKitZip(Path zip) throws IOException {
            op("readKitZip");
            return zipOverride != null ? zipOverride : OpsJoinSelf.LIVE.readKitZip(zip);
        }

        @Override
        public Path createStage(Path root) throws IOException {
            op("createStage");
            return OpsJoinSelf.LIVE.createStage(root);
        }

        @Override
        public void writeNew(Path file, byte[] bytes) throws IOException {
            op("writeNew");
            OpsJoinSelf.LIVE.writeNew(file, bytes);
        }

        @Override
        public void moveDir(Path from, Path to) throws IOException {
            op("moveDir");
            OpsJoinSelf.LIVE.moveDir(from, to);
        }

        @Override
        public void deleteStage(Path stage) throws IOException {
            op("deleteStage");
            OpsJoinSelf.LIVE.deleteStage(stage);
        }
    }

    /** {@code call}'s refusal sentence, or a marker that it did not refuse. */
    private static String refusal(Supplier<String> call) {
        try {
            return "(no refusal: " + call.get() + ")";
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Bytes as a one-line view for a failure message (CR / LF visible). */
    private static String shown(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1).replace("\r", "<CR>").replace("\n", "<LF>");
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(walk.toList());
            all.sort(Comparator.reverseOrder());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException residue) {
            System.out.println("join-self selftest: temp residue at " + dir + " ("
                    + residue.getClass().getSimpleName() + ")");
        }
    }
}
