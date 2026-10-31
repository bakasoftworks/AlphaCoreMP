package cc.starsector.server.ops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.runtime.HandshakeCodec;

/**
 * The console's build-gate contract,
 * {@code OpsConsole --selftest} (the {@code server/build.ps1} hook): the pure
 * {@link OpsConsoleFormsSelftest} rows, the {@link OpsConsolePageSelftest} rows over the served
 * page, {@code parity-web-twin}, then the HTTP rows over the REAL shell ({@link OpsConsole#boot},
 * loopback port 0) on a temp PACKAGE root ({@link OpsRoots#packagedChecked} over a
 * {@code pack.stamp} manifest; {@link OpsRoots#ROOTS} set and restored in {@code finally}), with a
 * recording spawn (a FakeProcess that already exited - never a real server) and a scripted Kill
 * order (never a real kill: the rig's snapshot names THIS JVM as the writer).
 *
 * <p>Each row prints {@code PASS <row>} or {@code FAIL <row>: <why> (flips if ...)}; a rig that
 * could not stage prints {@code SETUP-<row>: ...} (an ERROR, never a mutant kill). {@link #run}
 * returns every failure line.
 */
final class OpsConsoleSelftest {

    private OpsConsoleSelftest() {
    }

    private static final List<String> API_PATHS = List.of("/api/status", "/api/config", "/api/start",
            "/api/verb", "/api/kill", "/api/voice", "/api/mods", "/api/reveal", "/api/brick", "/api/lease",
            "/api/join", "/api/match-settings", "/api/reach", "/api/shape-new", "/api/watch", "/api/draft",
            "/api/dev-editor", "/api/quit");
    /** The planted sidecar password (a VALID one: 8..64 letters / digits / dashes, else Start refuses it). */
    private static final String SECRET = "hunter2-fixture";

    /** A rig step that could not stage. */
    private static final class SetupFail extends RuntimeException {
        SetupFail(String row, Exception cause) {
            super("SETUP-" + row + ": " + cause.getClass().getSimpleName() + " "
                    + PeerText.bound(String.valueOf(cause.getMessage()), 160), null, false, false);
        }
    }

    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Check {
        /** null = green, else the offence (naming the regression that flips it). */
        String run() throws Exception;
    }

    record Resp(int code, String body, HttpHeaders headers) {
    }

    /** The staged world: a temp package root, the booted console, its recorders. */
    static final class Rig {
        Path root;
        Path pkg;
        Path sidecar;
        Path cmd;
        OpsRoots roots;
        final List<List<String>> spawned = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<OpsConsole.KillFn> killMode = new AtomicReference<>();
        /** The exit every recorded spawn is born with (1 = died at once, the rows' default; null = LIVES until a
         *  row ends it through {@link #lastSpawn} - the lease daemon's end leg waits on that). */
        final AtomicReference<Integer> spawnExit = new AtomicReference<>(1);
        /** The last fake launcher the recorder handed the console. */
        final AtomicReference<OpsPressSelftest.FakeProcess> lastSpawn = new AtomicReference<>();
        ServerOpsCore.SpawnFn recorder;
        OpsConsole.KillFn kill;
        OpsConsole.Boot boot;
        HttpClient client;
        /** The loopback ProtonVPN gateway the console's lease Env points at (closed at teardown). */
        DatagramSocket gateway;
        /** The lease Env's ProtonVPN log dir (absent unless a row plants it). */
        Path leaseLogs;
        /** Datagrams the gateway received (one per pull). */
        final AtomicInteger asked = new AtomicInteger();
        /** true = the gateway receives but never answers (N1). */
        final AtomicBoolean silent = new AtomicBoolean();
        /** The gateway's wait before it answers (ms; 0 = at once). */
        final AtomicInteger delayMs = new AtomicInteger();
        /** The responder's failed receive / send / sleep steps (the close at teardown included). */
        final AtomicInteger gatewayErrors = new AtomicInteger();
        /** Join from this PC's seams the console boots with (never a real spawn). */
        FakeJoinEffects joinFx;
        /** Confirmed Close ops GUI presses (the rig's {@link OpsConsole#quitHook} for its life: never the JVM's end). */
        final AtomicInteger quits = new AtomicInteger();
    }

    /**
     * The rig's Join from this PC seams. The file seams hit the REAL
     * disk under the rig's temp root (the core's install read resolves through the real disk anyway); the
     * ping answers from {@link #pings} (port to body; absent = null); the spawn only RECORDS (argv, cwd,
     * env) and answers {@link OpsJoinSelf#STILL_RUNNING} - never a process - holding on {@link #hold}
     * while one is set; the env names temp folders and carries {@code CC_JOIN_DRYRUN}, which the child
     * env must drop. The probe is never reached: the console swaps it for its shared gateway flight (the
     * TUN dial is the core's {@code join-dial} row).
     */
    static final class FakeJoinEffects implements OpsJoinSelf.Effects {
        record Spawn(List<String> argv, Path cwd, Map<String, String> env) {
        }

        final Map<Integer, String> pings = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Spawn> spawns = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> env;
        /** Counted down by each spawn (the concurrency leg waits on it); null = none. */
        volatile CountDownLatch entered;
        /** Each spawn waits on it (at most 20 s) while set; null = answer at once. */
        volatile CountDownLatch hold;

        FakeJoinEffects(Path root) {
            Map<String, String> e = new java.util.LinkedHashMap<>();
            e.put("LOCALAPPDATA", root.resolve("lad").toString());
            e.put("APPDATA", root.resolve("ad").toString());
            e.put("USERPROFILE", root.resolve("up").toString());
            e.put("SystemRoot", "C:\\Windows");
            e.put("PATH", "x");
            e.put("CC_JOIN_DRYRUN", "1");
            env = Collections.unmodifiableMap(e);
        }

        @Override
        public boolean isRegularFile(Path file) {
            return OpsJoinSelf.LIVE.isRegularFile(file);
        }

        @Override
        public byte[] readBounded(Path file, int cap) throws IOException {
            return OpsJoinSelf.LIVE.readBounded(file, cap);
        }

        @Override
        public void writeAtomic(Path file, byte[] bytes) throws IOException {
            OpsJoinSelf.LIVE.writeAtomic(file, bytes);
        }

        @Override
        public void createDirectories(Path dir) throws IOException {
            OpsJoinSelf.LIVE.createDirectories(dir);
        }

        @Override
        public String pingBody(int port, String token) {
            return pings.get(port);
        }

        @Override
        public VpnLeaseProbe.Probe probe() throws IOException {
            throw new IOException("web-join never reaches the fake probe");
        }

        /** The recording spawn WITH a handle, so a debug-watch press records the window it opened (without one the
         *  recording wiring was unpinned): this JVM's own handle at {@code Instant.EPOCH} -
         *  a start instant no live process carries, so a sweep counts it {@code gone} and never touches it. */
        @Override
        public OpsJoinSelf.Spawned spawnWindow(List<String> argv, Path cwd, Map<String, String> childEnv) {
            return new OpsJoinSelf.Spawned(spawn(argv, cwd, childEnv), ProcessHandle.current(), java.time.Instant.EPOCH);
        }

        @Override
        public int spawn(List<String> argv, Path cwd, Map<String, String> childEnv) {
            spawns.add(new Spawn(List.copyOf(argv), cwd, Map.copyOf(childEnv)));
            CountDownLatch in = entered;
            if (in != null) {
                in.countDown();
            }
            CountDownLatch h = hold;
            if (h != null) {
                try {
                    h.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) { // one row; the press answers as still running
                    Thread.currentThread().interrupt();
                    System.out.println("web-join fake spawn interrupted");
                }
            }
            return OpsJoinSelf.STILL_RUNNING;
        }

        @Override
        public Map<String, String> parentEnv() {
            return env;
        }

        @Override
        public String systemRoot() {
            return "C:\\Windows";
        }

        /** The real disk, like the reads above: the staged fake games are real folders under the rig's root, and
         *  the second-folder check resolves them through their links (row web-watch-second-check). */
        @Override
        public Path realPath(Path p) throws IOException {
            return OpsJoinSelf.LIVE.realPath(p);
        }

        /** Every arm line a press handed its serve's command file, in order: (the file the press named, the line). */
        final List<Map.Entry<Path, String>> armLines = Collections.synchronizedList(new ArrayList<>());

        /** The arm seam RECORDS and never writes. WHY never: the rig's
         *  serve names {@code rig.cmd}, the file the verb rows read - a written arm line would pose as a verb the
         *  console sent (web-verb-confirm-roundtrip asserts it absent). WHY record: without this seam every rig press
         *  failed its arm (the interface default throws) and opened plain, so no row saw the armed path. */
        @Override
        public void appendCommandLine(Path commandFile, String line) {
            armLines.add(Map.entry(commandFile, line));
        }
    }

    static List<String> run() {
        List<String> fails = new ArrayList<>(OpsConsoleFormsSelftest.run());
        String html = OpsConsolePage.html();
        String css = OpsConsolePage.css();
        String js = OpsConsolePage.js();
        fails.addAll(OpsConsolePageSelftest.run(html, css, js));
        row(fails, "parity-web-twin", () -> parityWebTwin(html, js));
        row(fails, "page-post-timeout", () -> postTimeoutRow(js));
        row(fails, "page-first-tick", () -> firstTickRow(js));
        row(fails, "page-hashchange", () -> hashChangeRow(js));
        row(fails, "page-setup-twins", () -> setupTwinsRow(html, js));
        row(fails, "draft-allowlist", OpsConsoleSelftest::draftAllowlistRow);
        row(fails, "draft-ids-parity", OpsConsoleSelftest::draftIdsParityRow);
        row(fails, "draft-caps", OpsConsoleSelftest::draftCapsRow);
        row(fails, "draft-stale-load", OpsConsoleSelftest::draftStaleLoadRow);
        row(fails, "draft-roundtrip", OpsConsoleSelftest::draftRoundtripRow);
        row(fails, "page-roster-mult", () -> rosterMultPageRow(html, js));
        row(fails, "page-seats-allskills", () -> seatsAllSkillsPageRow(html, js));
        row(fails, "page-rngpins", () -> rngPinsPageRow(html, js));
        row(fails, "page-magicseam", () -> magicSeamPageRow(html, js));
        row(fails, "page-pilotship", () -> pilotShipPageRow(html, js));
        row(fails, "status-magic-line", () -> statusMagicLineRow(html, js));
        row(fails, "status-magic-cell", () -> statusMagicCellRow(html, js));
        row(fails, "status-pilot-line", () -> statusPilotLineRow(html, js));
        row(fails, "page-pvp", () -> pvpPageRow(html, js));
        row(fails, "page-lease", () -> pageLeaseRow(html, js));
        row(fails, "forms-custom-keep", OpsConsoleSelftest::formsCustomKeepRow);
        row(fails, "page-pack-line", () -> packLinePageRow(html, js));
        row(fails, "console-lock-static", OpsConsoleSelftest::consoleLockStaticRow);
        row(fails, "console-running-card", OpsConsoleSelftest::consoleRunningCardRow);
        httpRows(fails);
        fails.addAll(OpsConsoleOwnerSelftest.run(html, js));
        return fails;
    }

    /**
     * {@code console-running-card} (a user finds the console that is already open):
     *
     * the running card written and read back while its pid (this JVM) is ALIVE; no card, a dead pid's card, a
     * malformed card and an out-of-range port each read null; {@link OpsConsole#reopenRunning} on a Windows terminal
     * prints the KEYED address and spawns ONE browser (exit 0), off a terminal prints the file hint with no key and
     * no spawn (exit 0), and exits 2 when the spawn fails (the bat window then pauses on the address).
     */
    static String consoleRunningCardRow() throws Exception {
        Path root = Files.createTempDirectory("cc-console-running");
        List<String> bad = new ArrayList<>();
        try {
            if (OpsConsole.runningConsole(root) != null) {
                bad.add("no card read as a running console");
            }
            OpsConsole.Running mine = new OpsConsole.Running(ProcessHandle.current().pid(), 7717,
                    "2026-10-27 11:15", "20261026-220924");
            Path card = OpsConsole.writeRunning(root, mine);
            if (!mine.equals(OpsConsole.runningConsole(root))) {
                bad.add("own card read back as " + OpsConsole.runningConsole(root));
            }
            OpsConsole.writeRunning(root, new OpsConsole.Running(9_000_000_000_000L, 7717, "2026-10-27 11:15", "-"));
            if (OpsConsole.runningConsole(root) != null) {
                bad.add("a dead pid's card read as running");
            }
            Files.writeString(card, "pid=abc\nport=7717\n", StandardCharsets.US_ASCII);
            if (OpsConsole.runningConsole(root) != null) {
                bad.add("a malformed card read as running");
            }
            if (OpsConsole.Running.parse("pid=5\nport=70000\nsince=2026-10-27 11:15\npack=-\n") != null) {
                bad.add("port 70000 parsed");
            }
            String key = "k".repeat(43);
            OpsConsole.writeToken(root, key);
            String url = "http://127.0.0.1:7717/#" + key;
            List<List<String>> spawned = new ArrayList<>();
            OpsConsole.BrowserSpawn recorder = argv -> spawned.add(List.copyOf(argv));
            int[] rc = new int[3];
            List<String> win = captureLines(() -> rc[0] = OpsConsole.reopenRunning(root, mine, true, true, recorder));
            List<String> off = captureLines(() -> rc[1] = OpsConsole.reopenRunning(root, mine, false, true, recorder));
            List<String> failed = captureLines(() -> rc[2] = OpsConsole.reopenRunning(root, mine, true, true,
                    argv -> {
                        throw new IOException("message carrying " + url);
                    }));
            if (rc[0] != 0 || spawned.size() != 1 || !spawned.get(0).contains(url) || !win.contains("open " + url)
                    || !win.contains("cc-console browser result=opened")) {
                bad.add("windows tty: rc " + rc[0] + " spawns " + spawned.size() + " lines " + win.size());
            }
            if (rc[1] != 0 || spawned.size() != 1 || off.stream().anyMatch(l -> l.contains(key))) {
                bad.add("off tty: rc " + rc[1] + " spawns " + spawned.size() + " key shown="
                        + off.stream().anyMatch(l -> l.contains(key)));
            }
            if (rc[2] != 2 || failed.stream().anyMatch(l -> l.contains("message carrying"))) {
                bad.add("failed spawn: rc " + rc[2] + " lines " + failed.size());
            }
        } finally {
            deleteTree(root);
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    private static void row(List<String> fails, String name, Check check) {
        String why;
        try {
            why = check.run();
        } catch (SetupFail setup) { // the rig never staged: an ERROR line, never a named-row kill
            System.out.println(setup.getMessage());
            fails.add(setup.getMessage());
            return;
        } catch (Exception e) { // the row's own dial threw: that row FAILS by name
            why = "threw " + e.getClass().getSimpleName() + ": " + PeerText.bound(String.valueOf(e.getMessage()), 160);
        }
        if (why == null) {
            System.out.println("PASS " + name);
        } else {
            String line = "FAIL " + name + ": " + why;
            System.out.println(line);
            fails.add(line);
        }
    }

    private static void setup(String row, Step step) {
        try {
            step.run();
        } catch (Exception e) { // rethrown as the SETUP line
            throw new SetupFail(row, e);
        }
    }

    // ---- parity-web-twin -----------------------------------------------------------------------

    /** Every package control id has its page twin: the literal {@code data-cc="<id>"} in the
     * served HTML ({@code <template>} bodies included - a plain scan) or in the JS. No skip list:
     * an id in {@link OpsParity#PACKAGE_IDS} without a page twin turns this row red the
     * session it lands. */
    static String parityWebTwin(String html, String js) {
        if (OpsParity.PACKAGE_IDS.isEmpty()) {
            return "OpsParity.PACKAGE_IDS is empty (flips if the package parity list is emptied)";
        }
        List<String> missing = new ArrayList<>();
        for (String id : OpsParity.PACKAGE_IDS) {
            String literal = "data-cc=\"" + id + "\"";
            if (!html.contains(literal) && !js.contains(literal)) {
                missing.add(id);
            }
        }
        System.out.println("parity-web-twin ids=" + OpsParity.PACKAGE_IDS.size() + " missing=" + missing);
        return missing.isEmpty() ? null : missing.size() + " package id(s) without a page twin " + missing
                + " (flips if a page control for an OpsParity.PACKAGE_IDS id loses its data-cc twin in the page"
                + " or the JS)";
    }

    /** {@code page-roster-mult} - Roster x over the SERVED html + js: the
     * input carries {@code setup.wave.rosterMultiplier} after Roster DP inside {@code #pre-start} (locked while a
     * server runs, like the roster DP it scales), its hint names the row, and it rides the config prefill list
     * and the Start payload. */
    static String rosterMultPageRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        String input = "<input id=\"f-roster-mult\" data-cc=\"setup.wave.rosterMultiplier\"";
        int at = html.indexOf(input);
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int rosterDp = html.indexOf("<input id=\"f-roster-dp\"");
        if (at < 0 || pre < 0 || at < pre || at > preEnd || rosterDp < 0 || at < rosterDp) {
            bad.add("no " + input + "> after Roster DP inside #pre-start (the field lost its twin id or left the"
                    + " locked group)");
        }
        if (!html.contains("<p class=\"hint\" id=\"hint-roster-mult\">writes match.waveRosterMultiplier;")) {
            bad.add("the #hint-roster-mult hint does not name match.waveRosterMultiplier");
        }
        if (!js.contains("['f-roster-mult', 'waveRosterMultiplier']")) {
            bad.add("SETUP_PREFILL lacks ['f-roster-mult', 'waveRosterMultiplier'] (the saved row stops prefilling)");
        }
        if (!js.contains("waveRosterMultiplier: CC.el('f-roster-mult').value")) {
            bad.add("CC.startForm sends no waveRosterMultiplier (Start stops writing the row)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** {@code page-seats-allskills} - over the SERVED html + js: Seats per
     * address ({@code setup.net.seatsPerSource}, after Tick rate) and All skills ({@code setup.officer.allSkills},
     * on selected / off) sit inside {@code #pre-start} (locked while a server runs), each hint names its props row and
     * has its help row, and both ride the config prefill list and the Start payload. */
    static String seatsAllSkillsPageRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int tick = html.indexOf("<input id=\"f-tick\"");
        int seats = html.indexOf("<input id=\"f-seats-per-source\" data-cc=\"setup.net.seatsPerSource\"");
        int skills = html.indexOf("<select id=\"f-all-skills\" data-cc=\"setup.officer.allSkills\"");
        if (seats < 0 || pre < 0 || seats < pre || seats > preEnd || tick < 0 || seats < tick) {
            bad.add("no Seats per address input with its twin id after Tick rate inside #pre-start");
        }
        if (skills < 0 || skills < pre || skills > preEnd
                || !html.startsWith("<option selected>on</option><option>off</option></select>",
                        html.indexOf('>', skills) + 1)) {
            bad.add("no All skills select (on selected, off) with its twin id inside #pre-start");
        }
        for (String[] hint : new String[][] {{"hint-seats-per-source", "writes moderation.maxSeatsPerSource; 0 = no limit"},
                {"hint-all-skills", "writes server.officerSkillsAll; on = every player's ship gets a captain"}}) {
            if (!html.contains("<p class=\"hint\" id=\"" + hint[0] + "\">" + hint[1])
                    || !html.contains("<dd data-hint=\"" + hint[0] + "\"></dd>")) {
                bad.add("#" + hint[0] + " lacks its text or its help row");
            }
        }
        for (String need : List.of("['f-seats-per-source', 'seatsPerSource']", "['f-all-skills', 'allSkills']",
                "seatsPerSource: CC.el('f-seats-per-source').value", "allSkills: CC.el('f-all-skills').value")) {
            if (!js.contains(need)) {
                bad.add("the script lacks " + need + " (the saved row stops prefilling or Start stops writing it)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** {@code page-rngpins} - over the SERVED html + js: the replay-pins select
     * ({@code setup.replay.rngPins}, on selected / off, labelled "Deterministic replay pins (RNG)") sits inside
     * {@code #pre-start} (locked while a server runs), its hint names the props row and has its help row, and it
     * rides the config prefill list and the Start payload. */
    static String rngPinsPageRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int pins = html.indexOf("<select id=\"f-rng-pins\" data-cc=\"setup.replay.rngPins\"");
        if (pins < 0 || pre < 0 || pins < pre || pins > preEnd
                || !html.startsWith("<option selected>on</option><option>off</option></select>",
                        html.indexOf('>', pins) + 1)) {
            bad.add("no replay-pins select (on selected, off) with its twin id inside #pre-start");
        }
        if (!html.contains("<label for=\"f-rng-pins\">" + OpsMatchRows.RNG_PINS_LABEL + "</label>")
                || !html.contains("<dt>" + OpsMatchRows.RNG_PINS_LABEL + "</dt><dd data-hint=\"hint-rng-pins\"></dd>")) {
            bad.add("the select's label or its help row drifted from '" + OpsMatchRows.RNG_PINS_LABEL + "'");
        }
        if (!html.contains("<p class=\"hint\" id=\"hint-rng-pins\">writes " + OpsMatchRows.RNG_PINS_KEY + "; on = ")) {
            bad.add("#hint-rng-pins lacks its text (the row it writes)");
        }
        for (String need : List.of("['f-rng-pins', 'rngPins']", "rngPins: CC.el('f-rng-pins').value")) {
            if (!js.contains(need)) {
                bad.add("the script lacks " + need + " (the saved row stops prefilling or Start stops writing it)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** {@code page-magicseam} - over the SERVED html + js: the MagicLib select
     * ({@code setup.mods.magicSeam}, on selected / off, labelled {@link OpsMatchRows#MAGIC_SEAM_LABEL}) sits inside
     * {@code #pre-start} (locked while a server runs: the serve reads the row only while it loads), its hint names the
     * props row and has its help row, and it rides the config prefill list and the Start payload under its own key
     * (a payload sending the MagicLib word as {@code rngPins} would flip the replay pins instead). */
    static String magicSeamPageRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int sel = html.indexOf("<select id=\"f-magic-seam\" data-cc=\"setup.mods.magicSeam\"");
        if (sel < 0 || pre < 0 || sel < pre || sel > preEnd
                || !html.startsWith("<option selected>on</option><option>off</option></select>",
                        html.indexOf('>', sel) + 1)) {
            bad.add("no MagicLib select (on selected, off) with its twin id inside #pre-start (flips if it moves out of"
                    + " the boot-time lock group or stops opening at on)");
        }
        if (!html.contains("<label for=\"f-magic-seam\">" + OpsMatchRows.MAGIC_SEAM_LABEL + "</label>")
                || !html.contains("<dt>" + OpsMatchRows.MAGIC_SEAM_LABEL + "</dt><dd data-hint=\"hint-magic-seam\"></dd>")) {
            bad.add("the select's label or its help row drifted from '" + OpsMatchRows.MAGIC_SEAM_LABEL + "'");
        }
        if (!html.contains("<p class=\"hint\" id=\"hint-magic-seam\">writes " + OpsMatchRows.MAGIC_SEAM_KEY
                + "; on = ")) {
            bad.add("#hint-magic-seam lacks its text (the row it writes)");
        }
        for (String need : List.of("['f-magic-seam', 'magicSeam']", "magicSeam: CC.el('f-magic-seam').value")) {
            if (!js.contains(need)) {
                bad.add("the script lacks " + need + " (the saved row stops prefilling or Start stops writing it)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** {@code page-pilotship} - over the SERVED html + js: the player-ship select
     * ({@code setup.mods.pilotShip}, on / off with ON selected = the shipped default, labelled
     * {@link OpsMatchRows#PILOT_SHIP_LABEL}) sits inside {@code #pre-start} right after the MagicLib select (locked while
     * a server runs: the serve reads the row only while it loads), its hint names the props row and has its help row, and
     * it rides the config prefill list and the Start payload under its own key (a payload sending the word as
     * {@code magicSeam} would flip the MagicLib row instead). */
    static String pilotShipPageRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int magic = html.indexOf("<select id=\"f-magic-seam\"");
        int sel = html.indexOf("<select id=\"f-pilot-ship\" data-cc=\"setup.mods.pilotShip\"");
        if (sel < 0 || pre < 0 || sel < pre || sel > preEnd || magic < 0 || sel < magic
                || html.indexOf("<select", magic + 1) != sel
                || !html.startsWith("<option selected>on</option><option>off</option></select>",
                        html.indexOf('>', sel) + 1)) {
            bad.add("no player-ship select (on, off selected) with its twin id right after the MagicLib select inside"
                    + " #pre-start (flips if it moves out of the boot-time lock group, or stops opening at off)");
        }
        if (!html.contains("<label for=\"f-pilot-ship\">" + OpsMatchRows.PILOT_SHIP_LABEL + "</label>")
                || !html.contains("<dt>" + OpsMatchRows.PILOT_SHIP_LABEL + "</dt><dd data-hint=\"hint-pilot-ship\"></dd>")) {
            bad.add("the select's label or its help row drifted from '" + OpsMatchRows.PILOT_SHIP_LABEL + "'");
        }
        if (!html.contains("<p class=\"hint\" id=\"hint-pilot-ship\">writes " + OpsMatchRows.PILOT_SHIP_KEY
                + "; on = ")) {
            bad.add("#hint-pilot-ship lacks its text (the row it writes)");
        }
        for (String need : List.of("['f-pilot-ship', 'pilotShip']", "pilotShip: CC.el('f-pilot-ship').value")) {
            if (!js.contains(need)) {
                bad.add("the script lacks " + need + " (the saved row stops prefilling or Start stops writing it)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** {@code status-magic-line} - the MATCH line's MagicLib sentence through the
     * whole host path (a {@link OpsStatusFile.MagicSeam} composed into status lines, parsed back by {@link OpsSnapshot},
     * answered by {@link ServerOpsCore#statusJson} as {@code magicSeamLine}): an applied receipt reads on, the host
     * switch "off (host switch)", a disarmed seam "off (MagicLib version not recognised)" for an unknown build and
     * "off (the server's MagicLib check failed: the token)" for the seam's own miss, an armed or absent receipt
     * "off (MagicLib not in this match)"; a writer with no {@code magic_seam} key and a previous server's leftover file
     * answer "" (no line). Over the SERVED html + js: {@code #magic-seam} is an id-only span inside the MATCH cluster,
     * hidden until the script writes the sentence into it, and hidden again while the sentence reads empty. */
    static String statusMagicLineRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        String head = "MagicLib abilities follow the pilot: ";
        String applied = "applied:0123456789abcdef";
        String[][] cases = {
                {applied, head + "on", "an applied receipt stops reading on"},
                {"off", head + "off (host switch)", "the host switch stops reading off (host switch)"},
                {"disarmed:window_absent", head + "off (MagicLib version not recognised)",
                        "an unknown MagicLib build reads on or shows the raw token"},
                {"disarmed:not_allowlisted", head + "off (MagicLib version not recognised)",
                        "a build outside the verified set reads as the serve's own failure"},
                {"disarmed:hook_abi", head + "off (the server's MagicLib check failed: hook_abi)",
                        "the seam's own miss reads on or loses the token a bug report needs"},
                {"armed", head + "off (MagicLib not in this match)", "an armed seam that met no MagicLib reads on or no line"},
                {null, head + "off (MagicLib not in this match)", "a serve with no receipt (unset) reads on or no line"}};
        for (String[] c : cases) {
            String got = answerMember(magicStatusJson(magicSnap(new OpsStatusFile.MagicSeam(c[0], true, MAGIC_BITS)),
                    true), "magicSeamLine");
            if (!c[1].equals(got)) {
                bad.add(c[0] + " -> '" + got + "' (flips if " + c[2] + ")");
            }
        }
        String none = answerMember(magicStatusJson(magicSnap(null), true), "magicSeamLine");
        if (!"".equals(none)) {
            bad.add("no magic_seam key -> '" + none + "' (flips if a pre-seam writer paints a MagicLib line or the"
                    + " answer drops the key)");
        }
        String leftover = answerMember(magicStatusJson(magicSnap(new OpsStatusFile.MagicSeam(applied, true, MAGIC_BITS)),
                false), "magicSeamLine");
        if (!"".equals(leftover)) {
            bad.add("a previous server's leftover file -> '" + leftover + "' (flips if a dead writer's receipt paints"
                    + " this match's line)");
        }
        int match = html.indexOf("<div class=\"cluster\" id=\"cl-match\"");
        int matchEnd = match < 0 ? -1 : html.indexOf("</div>", match);
        int span = html.indexOf("<span id=\"magic-seam\" hidden></span>");
        if (span < 0 || match < 0 || span < match || span > matchEnd) {
            bad.add("no hidden id-only #magic-seam span inside the MATCH cluster (flips if it moves out of the cluster,"
                    + " shows an empty line before the first status, or gains a control id)");
        }
        for (String need : List.of("typeof s.magicSeamLine === 'string' ? s.magicSeamLine : ''",
                "CC.el('magic-seam')", "CC.show(el, line !== '')", "renderMagicSeam(s);")) {
            if (!js.contains(need)) {
                bad.add("the script lacks " + need + " (the line stops reading the answer, stops hiding on '', or is"
                        + " never rendered)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** {@code status-magic-cell} - each seat's MagicLib cell and the host words
     * the page shows as its title, through the same host path as {@code status-magic-line}: a followed seat (the FOLLOW
     * predicate's why is null) reads follows; a seat whose HELLO carried 0x08 but is not followed reads reports with the
     * words for host_off / not_applied / door_unarmed; a seat without 0x08 (none, or the replica bit alone) reads "-"
     * with no title even while the host switch is off. The answer's column names and the served page agree: the
     * MagicLib header and template cell sit right after ctrl, the why has no cell of its own, and the script's
     * fallback column list equals {@link ServerOpsCore#SEAT_COLUMNS} and reads the why into the cell's title. */
    static String statusMagicCellRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        String applied = "applied:0123456789abcdef";
        // {receipt, door armed, the seat's ctrl depth (7 = 0x08, 8 = no bits, 9 = 0x10 alone), cell, title, regression}
        String[][] cases = {
                {applied, "1", "7", "follows", "", "a followed seat stops reading follows or gains a title"},
                {applied, "1", "8", "-", "", "a seat whose game reports nothing reads follows or reports"},
                {applied, "1", "9", "-", "", "the replica bit alone reads as reporting MagicLib states"},
                {"off", "1", "7", "reports", "host switch off", "the host_off words drift or the cell stops reporting"},
                {"off", "1", "8", "-", "", "a seat that reports nothing gains the host-off title"},
                {"disarmed:window_absent", "1", "7", "reports", "MagicLib unchanged on the server",
                        "the not_applied words drift"},
                {applied, "0", "7", "reports", "the mod check is off for this match", "the door_unarmed words drift"
                        + " or an unarmed door follows"}};
        for (String[] c : cases) {
            String body = magicStatusJson(magicSnap(new OpsStatusFile.MagicSeam(c[0], "1".equals(c[1]), MAGIC_BITS)),
                    true);
            String want = "," + c[2] + ",\"" + c[3] + "\",\"" + c[4] + "\",";
            if (!body.contains(want)) {
                int at = body.indexOf("," + c[2] + ",\"");
                String got = at < 0 ? "no seat" : body.substring(at, Math.min(body.length(), at + 60));
                bad.add(c[0] + (c[1].equals("1") ? "" : " door unarmed") + " ctrl " + c[2] + " -> " + got
                        + " (flips if " + c[3] + "/" + c[4] + " is lost: " + c[5] + ")");
            }
        }
        if (!magicStatusJson(magicSnap(null), true).contains("\"ctrl\",\"MagicLib\",\"MagicLib why\",\"custom\"")) {
            bad.add("seatColumns lacks ctrl, MagicLib, MagicLib why, custom (flips if the names the page reads its"
                    + " cells by leave their slot)");
        }
        int ctrlTh = html.indexOf("data-cc=\"live.seat.col.ctrl\">ctrl</th>");
        int magicTh = html.indexOf("<th scope=\"col\" data-cc=\"live.seat.col.magicLib\">MagicLib</th>");
        if (ctrlTh < 0 || magicTh < 0 || html.indexOf("<th", ctrlTh) != magicTh) {
            bad.add("no live.seat.col.magicLib header right after ctrl (flips if the header leaves its slot)");
        }
        if (!html.contains("data-col=\"ctrl\"></td><td role=\"gridcell\" data-col=\"MagicLib\"></td>")
                || html.contains("data-col=\"" + ServerOpsCore.MAGIC_WHY_COLUMN + "\"")) {
            bad.add("the seat row template lacks the MagicLib cell after ctrl or shows the why as a cell (flips if the"
                    + " body drifts from the header)");
        }
        StringBuilder cols = new StringBuilder("const COLS = [");
        for (int i = 0; i < ServerOpsCore.SEAT_COLUMNS.length; i++) {
            cols.append(i > 0 ? ", '" : "'").append(ServerOpsCore.SEAT_COLUMNS[i]).append('\'');
        }
        String flat = js.replaceAll("\\s+", " ");
        if (!flat.contains(cols.append("];").toString())) {
            bad.add("the script's fallback COLS drifted from the server's seat columns (flips if MagicLib or its why"
                    + " leaves the list or moves)");
        }
        if (!js.contains("td.dataset.col === 'MagicLib'") || !js.contains("r.cell('MagicLib why')")) {
            bad.add("the script no longer reads the MagicLib why into the MagicLib cell's title");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** HELLO flag bits per seat for the MagicLib rows: seat 1 reports (0x08), seat 2 none, seat 3 the replica bit
     *  alone (0x10). */
    private static final java.util.function.IntUnaryOperator MAGIC_BITS = seat -> seat == 1 ? 0x08 : seat == 3 ? 0x10 : 0;

    /** Status lines for three live seats (ids 1..3, ctrl depths 7..9 so each
     *  seat's cells are findable in the answer) composed with {@code magic} (null = a writer with no MagicLib rows)
     *  and parsed back - the reader the ops GUI's status answer is built from. */
    private static OpsSnapshot magicSnap(OpsStatusFile.MagicSeam magic) {
        return OpsSnapshot.parse(magicLines(magic));
    }

    /** {@link #magicSnap}'s status lines, before the parse. */
    private static List<String> magicLines(OpsStatusFile.MagicSeam magic) {
        List<OpsStatusFile.SeatRow> rows = List.of(
                new OpsStatusFile.SeatRow(1, "cid-a", "e1", "wolf_Assault", true, 1L, 1L, 7),
                new OpsStatusFile.SeatRow(2, "cid-b", "e2", "wolf_Assault", true, 1L, 1L, 8),
                new OpsStatusFile.SeatRow(3, "cid-c", "e3", "wolf_Assault", true, 1L, 1L, 9));
        return OpsStatusFile.composeLines(1L, 2L, "run", "h", 1, 0L, 0, 1, 0L, rows.size(),
                0.0d, 0.0d, 0.0d, 0L, 0L, 0L, 0L, 0, 0.0d, 0L, "off", rows, new long[rows.size()][2],
                PhaseWindowStats.Snapshot.EMPTY, OpsStatusFile.CustomStats.NONE, null, null, magic);
    }

    /** {@code status-pilot-line} - the MATCH line's player-ship sentence, read
     * BY NAME from a status file's {@code pilot_ship=} row (the {@link #magicLines} fixture plus that one row, parsed
     * by {@link OpsSnapshot} and answered by {@link ServerOpsCore#statusJson} as {@code pilotShipLine}): every receipt
     * class the premain writes gets its host words - applied with a count of script classes (plural, singular, zero,
     * unreadable), armed, off, each refused reason under the one start-time wrapper (both families, every load-time
     * wrap token, a Throwable token and an unknown token kept, bounded), each latch; {@code unset}, an
     * empty row, a writer with no row and a previous server's leftover file answer "" (no line). Over the SERVED html
     * + js: {@code #pilot-ship} is an id-only hidden span inside the MATCH cluster, the script writes the sentence
     * into it and hides it again while the sentence reads empty. */
    static String statusPilotLineRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        String head = "Each pilot's ship counts as the player's: ";
        String refused = head + "off (the start-time check refused the switch: ";
        String[][] cases = {
                {"applied:7", head + "on (7 script classes)", "an applied receipt stops reading on with its class"
                        + " count, or calls the classes mod classes again (the count includes vanilla scripts)"},
                {"applied:1", head + "on (1 script class)", "one redirected class reads as a plural or loses its count"},
                {"applied:0", head + "on (no script class yet)", "a zero count (the window wrapped, no script yet)"
                        + " reads 0 script classes or off"},
                {"applied:x9", head + "on", "an unreadable count hides a live window or paints the raw token"},
                {"armed", head + "on (no script class yet)", "an armed window reads off or no line"},
                {"off", head + "off", "the host switch off stops reading off or gains a reason"},
                {"refused:ship_fingerprint", refused + "game version not recognised)",
                        "a ship-code fingerprint refusal reads on, shows the raw token or names a game-version check"},
                {"refused:golden_mismatch", refused + "game version not recognised)",
                        "a golden mismatch reads as anything but an unknown game version"},
                {"refused:not_target", refused + "game version not recognised)",
                        "a ship class the wrap did not take shows the raw token"},
                {"refused:self_check", refused + "game version not recognised)",
                        "a wrap that failed its own re-read shows the raw token"},
                {"refused:window_repeated", refused + "game version not recognised)",
                        "an already-wrapped ship class shows the raw token"},
                {"refused:window_absent", refused + "game version not recognised)",
                        "a ship class with no advance body shows the raw token"},
                {"refused:class_version", refused + "game version not recognised)",
                        "a ship class too old to wrap shows the raw token"},
                {"refused:holder_missing", refused + "server files incomplete)",
                        "a missing helper class reads as a game version problem, shows the raw token or the wrapper"
                                + " still says the game-version check failed"},
                {"refused:holder_abi", refused + "server files incomplete)",
                        "a wrong-shape helper class reads as a game version problem"},
                {"refused:error:NullPointerException", refused + "error:NullPointerException)",
                        "a wrap Throwable is folded into a family or loses the class name a bug report needs"},
                {"refused:step_x", refused + "step_x)", "an unknown refusal loses the token a bug report needs"},
                {"latched:leak", head + "off (switched off for this match after a fault)",
                        "a leak latch reads on (the window is off for the match)"},
                {"latched:mirror_overflow", head + "off (switched off for this match after a fault)",
                        "a latch reason other than leak reads on or no line"},
                {"unset", "", "a serve where nothing decided the switch paints a line"},
                {"", "", "an empty row paints a line"},
                {"garbled", "", "a receipt outside the set paints a line"}};
        for (String[] c : cases) {
            List<String> lines = new ArrayList<>(magicLines(null));
            lines.add("pilot_ship=" + c[0]);
            String got = answerMember(magicStatusJson(OpsSnapshot.parse(lines), true), "pilotShipLine");
            if (!c[1].equals(got)) {
                bad.add("'" + c[0] + "' -> '" + got + "' (flips if " + c[2] + ", or the answer drops the key)");
            }
        }
        String none = answerMember(magicStatusJson(magicSnap(null), true), "pilotShipLine");
        if (!"".equals(none)) {
            bad.add("no pilot_ship row -> '" + none + "' (flips if an older writer paints a line or the answer drops"
                    + " the key)");
        }
        List<String> live = new ArrayList<>(magicLines(null));
        live.add("pilot_ship=applied:7");
        String leftover = answerMember(magicStatusJson(OpsSnapshot.parse(live), false), "pilotShipLine");
        if (!"".equals(leftover)) {
            bad.add("a previous server's leftover file -> '" + leftover + "' (flips if a dead writer's receipt paints"
                    + " this match's line)");
        }
        int match = html.indexOf("<div class=\"cluster\" id=\"cl-match\"");
        int matchEnd = match < 0 ? -1 : html.indexOf("</div>", match);
        int span = html.indexOf("<span id=\"pilot-ship\" hidden></span>");
        if (span < 0 || match < 0 || span < match || span > matchEnd) {
            bad.add("no hidden id-only #pilot-ship span inside the MATCH cluster (flips if it moves out of the cluster,"
                    + " shows an empty line before the first status, or gains a control id)");
        }
        for (String need : List.of("typeof s.pilotShipLine === 'string' ? s.pilotShipLine : ''",
                "CC.el('pilot-ship')", "renderPilotShip(s);")) {
            if (!js.contains(need)) {
                bad.add("the script lacks " + need + " (the line stops reading the answer or is never rendered)");
            }
        }
        int fn = js.indexOf("const renderPilotShip = (s) => {");
        int fnEnd = fn < 0 ? -1 : js.indexOf("};", fn);
        if (fn < 0 || fnEnd < 0 || !js.substring(fn, fnEnd).contains("CC.show(el, line !== '')")) {
            bad.add("renderPilotShip no longer hides the line on '' (flips if an empty sentence shows a blank line)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** The status answer for {@code snap} at its own stamp ({@code writerAlive} false = a previous server's file). */
    private static String magicStatusJson(OpsSnapshot snap, boolean writerAlive) {
        return ServerOpsCore.statusJson(snap, null, "x", writerAlive, false, 10.0, 1L, List.of());
    }

    /** {@code page-pvp} - over the SERVED html + js: ONE PvP mode select
     * ({@code #f-pvp}, {@code setup.match.pvp}, Off selected / On carrying the Start parse's words off / on) is the
     * row right after the Match shape row inside {@code #pre-start} (the pre-start lock disables it while a server
     * runs), its hint is the mode's tip verbatim with its help row; the script prefills it (the config list, then the
     * picked option's shape against the server's PvP shape id), drops a host pick on a shape switch and re-saves the
     * draft, and the Start payload sends {@code pvp} + {@code pvpStation}. */
    static String pvpPageRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        String tip = "Players fight each other in two teams, South and North. No station, and nobody wins. Waves"
                + " start paused. Fortress match, 2 or more players. Changing it makes a new match.";
        String select = "<select id=\"f-pvp\" data-cc=\"setup.match.pvp\" aria-describedby=\"hint-pvp\">"
                + "<option value=\"off\" selected>Off</option><option value=\"on\">On</option></select>";
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int shapeHint = html.indexOf("<p class=\"hint\" id=\"hint-shape\"");
        int nextRow = shapeHint < 0 ? -1 : html.indexOf("<div class=\"frow\">", shapeHint);
        int at = html.indexOf(select);
        int hint = html.indexOf("<p class=\"hint\" id=\"hint-pvp\">" + tip + "</p>");
        if (html.indexOf("id=\"f-pvp\"") != html.lastIndexOf("id=\"f-pvp\"") || at < 0 || pre < 0 || at < pre
                || at > preEnd || nextRow < 0
                || !html.startsWith("<div class=\"frow\">\n<label for=\"f-pvp\">PvP mode</label>", nextRow)) {
            bad.add("no single PvP mode select (Off selected, On; values off / on) as the row right after the Match"
                    + " shape row inside #pre-start (flips if it moves, leaves the pre-start lock, loses its id or"
                    + " label, doubles, or its option values stop being the Start parse's words)");
        }
        if (hint < at || hint > preEnd || !html.contains("<dt>PvP mode</dt><dd data-hint=\"hint-pvp\"></dd>")) {
            bad.add("#hint-pvp is not the mode's tip verbatim under the select, or its help row is gone (flips if the"
                    + " tip's wording drifts or the hint leaves the row)");
        }
        String flat = js.replaceAll("\\s+", " ");
        String prefill = between(flat, "const SETUP_PREFILL = [", "];");
        int apply = flat.indexOf("const setupApplyShape = (switched) => {");
        int applyEnd = apply < 0 ? -1 : flat.indexOf("};", apply);
        String applyBody = applyEnd < 0 ? "" : flat.substring(apply, applyEnd);
        int form = flat.indexOf("CC.startForm = () => {");
        int formEnd = form < 0 ? -1 : flat.indexOf("};", form);
        String formBody = formEnd < 0 ? "" : flat.substring(form, formEnd);
        if (prefill == null || !prefill.contains("['f-pvp', 'pvp']")
                || !flat.contains("const SETUP_PVP_SHAPE = '" + MatchDescriptors.PVP_SHAPE + "';")
                || !flat.contains("return o !== null && o.shape === SETUP_PVP_SHAPE;")) {
            bad.add("the PvP select is not prefilled from the config list and the server's PvP shape id (flips if the"
                    + " config member, the shape id or the picked-option test drifts - a Start would then switch the"
                    + " file the host left alone)");
        }
        for (String need : List.of("if (switched === true) { delete pvp.dataset.touched; }",
                "if (!setupTouched(pvp)) { pvp.value = setupPickedPvp() ? 'on' : 'off'; }",
                "setupDraftPending.add('f-pvp'); setupDraftFlush(pvp);")) {
            if (!applyBody.contains(need)) {
                bad.add("setupApplyShape lacks '" + need + "' (flips if the mode stops following the pick, a host pick"
                        + " survives a switch, or the draft keeps a mode picked for another shape)");
            }
        }
        for (String need : List.of("pvp: CC.el('f-pvp').value,", "pvpStation: CC.el('f-map-station').value,")) {
            if (!formBody.contains(need)) {
                bad.add("CC.startForm lacks '" + need + "' (flips if Start stops sending the mode or the station an"
                        + " OFF switch brings back)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }


    /**
     * {@code page-setup-twins} - the package Setup twins'
     * page shape, read over the SERVED html + js: the Game folder field carries {@code setup.gameRoot},
     * rides the config prefill list and the Start payload; Advertise is an editable input whose port
     * listener keeps its placeholder guard (Start now sends Advertise for the ProtonVPN mode
     * compare - row {@code page-lease} pins it, so the old never-sends check left); the Join brick row carries
     * {@code setup.brick}, its click posts {@code /api/brick} and clears the box after {@code 45000} ms
     * = {@link OpsWindowModel#NOTE_TTL_MS}.
     */
    static String setupTwinsRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        if (!html.contains("<input id=\"f-game-root\" data-cc=\"setup.gameRoot\"")) {
            bad.add("no <input id=\"f-game-root\" data-cc=\"setup.gameRoot\"> (the Game folder field lost its twin id)");
        }
        if (!js.contains("['f-game-root', 'gameRoot']")) {
            bad.add("SETUP_PREFILL lacks ['f-game-root', 'gameRoot'] (the saved game.root row stops prefilling)");
        }
        if (!js.contains("gameRoot: CC.el('f-game-root').value")) {
            bad.add("CC.startForm sends no gameRoot (Start stops saving the Game folder)");
        }
        if (!html.contains("<input id=\"f-advertise\" data-cc=\"setup.advertise\"") || html.contains("<output id=\"f-advertise\"")) {
            bad.add("Advertise is not an editable <input id=\"f-advertise\"> (the host can no longer type the address)");
        }
        String follow = between(js, "const setupAdvertise = () => {", "};");
        if (follow == null || !follow.contains("CC.el('f-advertise')") || !follow.contains(".startsWith(setupAdvertisePrefix)")) {
            bad.add("the port listener lost its placeholder guard startsWith(setupAdvertisePrefix) on f-advertise"
                    + " (a port edit would overwrite a typed or saved address)");
        }
        if (!html.contains("id=\"btn-brick\" data-cc=\"setup.brick\"") || !html.contains("<output id=\"f-brick\"")) {
            bad.add("no #btn-brick data-cc=\"setup.brick\" + <output id=\"f-brick\"> (the Copy join brick box lost its twin)");
        }
        String click = between(js, "CC.el('btn-brick').addEventListener('click'", "CC._copy(a.brick);");
        if (click == null || !click.contains("CC.send('/api/brick'")
                || !click.contains("}, " + OpsWindowModel.NOTE_TTL_MS + ");")) {
            bad.add("the brick click is unbound, stops posting /api/brick, or its box clock (literal 45000) drifted"
                    + " from OpsWindowModel.NOTE_TTL_MS=" + OpsWindowModel.NOTE_TTL_MS
                    + " (the box would outlive or undercut the Swing note)");
        }
        // A password typed but Started elsewhere must never ride the live brick.
        if (click == null || !click.contains("const pw = CC.ladder().startEnabled ? CC.el('f-password').value : '';")) {
            bad.add("the brick click sends the typed password while the pre-start lock holds (a password never"
                    + " Started here would be built into the running server's brick and refuse every joiner)");
        }
        // The note's text never carries the brick - a note joins the History list (hidden, 20 entries,
        // no clock) where the password would outlive the box's 45 s
        String note = click == null ? null : between(click, "CC.note(", ");");
        if (note == null || note.contains("a.brick")) {
            bad.add("the brick click's CC.note text " + (note == null ? "is missing" : "carries a.brick")
                    + " (the password would sit in the History list past the box's 45 s)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-lease} - Fill from ProtonVPN's page shape over the
     * SERVED html + js: the button with its {@code setup.lease} twin id and tip, the click bound and
     * posting {@code /api/lease}, the brick send carrying {@code bindHost} and {@code CC.startForm}
     * carrying {@code advertise} (the two halves of the server's mode compare), and the port listener
     * following a fill's address.
     */
    static String pageLeaseRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        if (!html.contains("<button type=\"button\" id=\"btn-lease\" data-cc=\"setup.lease\""
                + " aria-describedby=\"tip-lease\">Fill from ProtonVPN</button>")) {
            bad.add("no Fill from ProtonVPN button with id btn-lease / data-cc setup.lease / tip-lease"
                    + " (the Swing button lost its page twin)");
        }
        if (!html.contains("<dd id=\"tip-lease\">")) {
            bad.add("no <dd id=\"tip-lease\"> help entry (the button's tip is gone)");
        }
        String click = between(js, "CC.el('btn-lease').addEventListener('click'", "}, () => {});");
        if (click == null || !click.contains("CC.send('/api/lease'") || !click.contains("setupApplyFill(a)")) {
            bad.add("the btn-lease click is unbound, stops posting '/api/lease', or no longer applies the fill");
        }
        String brick = between(js, "CC.send('/api/brick'", "{busy:");
        if (brick == null || !brick.contains("bindHost: CC.el('f-bind-host').value")) {
            bad.add("the brick send carries no bindHost (the server's mode compare would always miss)");
        }
        String start = between(js, "CC.startForm = () => {", "return f;");
        if (start == null || !start.contains("advertise: CC.el('f-advertise').value")) {
            bad.add("CC.startForm carries no advertise (a Start in the ProtonVPN mode would never ask again)");
        }
        String follow = between(js, "const setupAdvertise = () => {", "};");
        if (follow == null || !follow.contains("setupAddressPart(el.value) === setupFollowAddress")) {
            bad.add("the port listener stopped following a fill's address (a port edit would strand the address)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** The text from {@code open} (inclusive) to the first {@code close} after it; null = either absent. */
    private static String between(String text, String open, String close) {
        int at = text.indexOf(open);
        int end = at < 0 ? -1 : text.indexOf(close, at + open.length());
        return end < 0 ? null : text.substring(at, end);
    }

    // ---- the core script's text rows ----------------------------

    /**
     * The page's POST abort. The literal
     * {@code isStatus ? 3000 : <postMs>} must be in the served script with {@code postMs} at least
     * {@link OpsConsole#REQUEST_CLOCK_S} (the shell's own request clock) and above the Kill's two
     * {@link ServerOpsKill#BOUND_MS} polls - else the browser cuts off a slow Kill's outcome.
     */
    static String postTimeoutRow(String js) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("setTimeout[(][(][)] => ctl[.]abort[(][)], isStatus [?] 3000 : ([0-9]+)[)]").matcher(js);
        if (!m.find()) {
            return "no 'isStatus ? 3000 : <postMs>' abort in the served script (flips if the POST abort"
                    + " falls back to the poll's 3 s)";
        }
        long postMs = Long.parseLong(m.group(1));
        boolean outlasts = postMs >= OpsConsole.REQUEST_CLOCK_S * 1000L && postMs > 2 * ServerOpsKill.BOUND_MS;
        return outlasts ? null : "POST abort " + postMs + " ms vs request-read clock " + OpsConsole.REQUEST_CLOCK_S
                + " s and 2 x " + ServerOpsKill.BOUND_MS + " ms (flips if the page's abort - the ONLY clock on a"
                + " stuck handler, the server times the request read alone - drops under the server's read clock"
                + " or the Kill's two polls)";
    }

    /**
     * The first status answer opens Setup on a stopped
     * server, Players on a running one (picked from the first answer's {@code startEnabled}):
     * the two-branch pick, {@code _start} arming the flag and {@code section()} disarming it.
     */
    static String firstTickRow(String js) {
        List<String> missing = new ArrayList<>();
        for (String literal : List.of("CC.section(CC.ladder().startEnabled ? 'setup' : 'players')",
                "CC.section('players');\n    CC._startTab = true;")) {
            if (!js.contains(literal)) {
                missing.add(literal.replace("\n", " "));
            }
        }
        int at = js.indexOf("\n  section(name) {");
        int end = at < 0 ? -1 : js.indexOf("\n  },", at);
        if (end < 0 || !js.substring(at, end).contains("CC._startTab = false;")) {
            missing.add("section() clearing CC._startTab");
        }
        return missing.isEmpty() ? null : "missing " + missing + " (flips if the first tick stops picking Setup"
                + " for a stopped server, _start stops arming it, or an operator's own pick stops winning)";
    }

    /**
     * A key pasted into the open page's address is
     * adopted without a reload. The {@code 'hashchange'} listener, {@code _boot} handing off to the
     * run-once {@code _start()} (else a later key could never start the page), and the listener's
     * three branches: the blank / malformed drop (stored key cleared, no-key shown), the same-key no-op
     * and the new-key start / immediate poll.
     */
    static String hashChangeRow(String js) {
        List<String> missing = new ArrayList<>();
        int at = js.indexOf("window.addEventListener('hashchange', () => {");
        int end = at < 0 ? -1 : js.indexOf("\n    });", at);
        String body = end < 0 ? "" : js.substring(at, end);
        if (body.isEmpty()) {
            missing.add("the 'hashchange' listener");
        }
        for (String literal : List.of("CC._store('cc.token', null);", "CC.show(CC.el('no-key'), true);",
                "if (key === CC.token) {", "CC.show(CC.el('no-key'), false);", "CC._start();", "CC._tick();",
                "CC._modules.forEach((m) => CC._call(m, 'onShow', CC.sectionName));")) {
            if (!body.contains(literal)) {
                missing.add("listener: " + literal);
            }
        }
        // the run-once flag is SET as well as checked (a dropped set = a second poll per adoption)
        for (String literal : List.of("    CC._start();\n  },", "if (CC._started) {\n      return;\n    }",
                "CC._started = true;")) {
            if (!js.contains(literal)) {
                missing.add(literal.replace("\n", " "));
            }
        }
        return missing.isEmpty() ? null : "missing " + missing + " (flips if a key pasted into the open page's"
                + " address stops being adopted without a reload, a blank / malformed one stops dropping the key,"
                + " or the page's run stops being run-once)";
    }

    // ---- the unsaved Setup draft (pure) --------------------------------------------------------

    /**
     * {@code draft-allowlist} - {@link OpsSetupDraft#put} refuses with token
     * {@code id} the join brick, a map field, both passwords, a voice / verb / uplink id, the file's own
     * {@code base} / {@code updated} keys and {@code null}; it accepts every {@link OpsSetupDraft#DRAFT_IDS} id;
     * {@link OpsSetupDraft#parse} drops a planted brick or password row.
     */
    static String draftAllowlistRow() {
        List<String> bad = new ArrayList<>();
        for (String id : Arrays.asList("setup.brick.text", "setup.map.half", "setup.password", "setup.adminPassword",
                "voice.enabled", "verb.watch", "load.uplink", "base", "updated", null)) {
            String token = draftPutToken(OpsSetupDraft.Draft.EMPTY, id, "x");
            if (!"id".equals(token)) {
                bad.add(id + " -> " + (token == null ? "accepted" : token) + " (flips if the allowlist reopens to"
                        + " the brick, a password, an Apply-owned field or a non-Setup id)");
            }
        }
        for (String id : OpsSetupDraft.DRAFT_IDS) {
            String token = draftPutToken(OpsSetupDraft.Draft.EMPTY, id, "x");
            if (token != null) {
                bad.add(id + " -> refused " + token + " (flips if a listed Setup id stops being kept)");
            }
        }
        OpsSetupDraft.Draft parsed = OpsSetupDraft.parse(("base=5\nupdated=\nsetup.brick.text=x\nsetup.password=y\n"
                + "setup.tickRate=60\n").getBytes(StandardCharsets.UTF_8));
        if (!parsed.rows().equals(Map.of("setup.tickRate", "60"))) {
            bad.add("parse kept " + parsed.rows().keySet() + " (flips if the reader admits a row the writer refuses:"
                    + " a hand-planted brick would be served back to the page)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** {@link OpsSetupDraft#put}'s refusal token, or {@code null} when it accepted. */
    private static String draftPutToken(OpsSetupDraft.Draft d, String id, String value) {
        try {
            OpsSetupDraft.put(d, id, value, 1L, null);
            return null;
        } catch (OpsSetupDraft.Refused refused) { // the row reads its token
            return refused.token();
        }
    }

    /**
     * {@code draft-ids-parity} - {@link OpsSetupDraft#DRAFT_IDS} holds 24
     * ids: 22 of {@link OpsParity#PACKAGE_IDS} and exactly two of {@link OpsParity#PAGE_ONLY_IDS} (the
     * second game folder and the PvP mode select); none is {@link OpsSetupDraft#NEVER_DRAFTED}; the 4 page-only ids
     * are disjoint from every other parity list, match the page's {@code data-cc} grammar, and the shape's Players pair is
     * not drafted. Re-pinned for the PvP select; re-pinned for the
     * MagicLib select; re-pinned for the player-ship select.
     */
    static String draftIdsParityRow() {
        List<String> bad = new ArrayList<>();
        long pkg = OpsSetupDraft.DRAFT_IDS.stream().filter(OpsParity.PACKAGE_IDS::contains).count();
        List<String> pageOnly = OpsSetupDraft.DRAFT_IDS.stream().filter(OpsParity.PAGE_ONLY_IDS::contains)
                .sorted().toList();
        if (OpsSetupDraft.DRAFT_IDS.size() != 24 || pkg != 22
                || !pageOnly.equals(List.of("setup.gameRootSecond", "setup.match.pvp"))) {
            bad.add("draft ids " + OpsSetupDraft.DRAFT_IDS.size() + " = package " + pkg + " + page-only " + pageOnly
                    + " (flips if a draft id drifts from the page's parity ids, or the PvP select stops being drafted)");
        }
        if (OpsParity.PAGE_ONLY_IDS.size() != 4) {
            bad.add("page-only ids " + OpsParity.PAGE_ONLY_IDS + " (flips if one is dropped or a package id joins)");
        }
        for (String id : OpsSetupDraft.DRAFT_IDS) {
            if (OpsSetupDraft.NEVER_DRAFTED.contains(id)) {
                bad.add(id + " is drafted AND never-drafted (flips if a password id joins the draft list)");
            }
        }
        for (String id : OpsParity.PAGE_ONLY_IDS) {
            if (OpsParity.PACKAGE_IDS.contains(id) || OpsParity.OWNER_IDS.contains(id)
                    || OpsParity.PACKAGE_ONLY_IDS.contains(id) || !id.matches("[a-z][A-Za-z0-9.]*")) {
                bad.add(id + " is listed in another parity list or breaks the data-cc grammar (flips if a page-only"
                        + " id is mislisted as a package / owner one)");
            }
        }
        if (OpsSetupDraft.DRAFT_IDS.contains("setup.shape.players")
                || OpsSetupDraft.DRAFT_IDS.contains("setup.shape.playersApply")) {
            bad.add("the Players pair is drafted (flips if an Apply-owned field joins the draft list)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code draft-caps} - eleven 512-char rows of a 3-byte character: the
     * eleventh {@code put} is refused {@code size} and the ten-row draft renders within the file cap (so it
     * survives the next load); a 513-char value and a CR / LF / NUL value = {@code value} (512 passes); a 64-row
     * draft refuses a NEW id with {@code rows} but still replaces an existing one.
     */
    static String draftCapsRow() {
        List<String> bad = new ArrayList<>();
        List<String> ids = new ArrayList<>(OpsSetupDraft.DRAFT_IDS);
        Collections.sort(ids);
        String wide = String.valueOf((char) 0x20AC).repeat(OpsSetupDraft.MAX_VALUE_CHARS);
        OpsSetupDraft.Draft d = OpsSetupDraft.Draft.EMPTY;
        String eleventh = "accepted";
        for (int i = 0; i < 11; i++) {
            try {
                d = OpsSetupDraft.put(d, ids.get(i), wide, 1L, Instant.parse("2026-10-17T12:00:00Z"));
            } catch (OpsSetupDraft.Refused refused) { // read below: only the eleventh may refuse
                if (i < 10) {
                    bad.add("put " + (i + 1) + " refused " + refused.token() + " (flips if a cap moves below ten"
                            + " full rows)");
                    break;
                }
                eleventh = refused.token();
            }
        }
        byte[] body = OpsSetupDraft.render(d).getBytes(StandardCharsets.UTF_8);
        if (!"size".equals(eleventh) || d.rows().size() != 10 || body.length > OpsSetupDraft.MAX_FILE_BYTES
                || OpsSetupDraft.parse(body).rows().size() != 10) {
            bad.add("eleventh wide row -> " + eleventh + ", kept " + d.rows().size() + " rows in " + body.length
                    + " B (flips if the byte cap stops being enforced on write - the whole draft would vanish at load)");
        }
        String[][] values = {{"513 chars", "1".repeat(OpsSetupDraft.MAX_VALUE_CHARS + 1), "value"},
            {"512 chars", "1".repeat(OpsSetupDraft.MAX_VALUE_CHARS), null},
            {"CR", "6\r0", "value"}, {"LF", "6\n0", "value"}, {"NUL", "6" + (char) 0 + "0", "value"}};
        for (String[] leg : values) {
            String token = draftPutToken(OpsSetupDraft.Draft.EMPTY, "setup.tickRate", leg[1]);
            if (!java.util.Objects.equals(leg[2], token)) {
                bad.add(leg[0] + " -> " + token + " (flips if the value cap or the one-line law moves)");
            }
        }
        java.util.LinkedHashMap<String, String> full = new java.util.LinkedHashMap<>();
        full.put("setup.bindPort", "1");
        for (int i = 1; i < OpsSetupDraft.MAX_ROWS; i++) {
            full.put("filler.row" + i, "v");
        }
        OpsSetupDraft.Draft sixtyFour = new OpsSetupDraft.Draft(Collections.unmodifiableMap(full), 1L, null);
        String fresh = draftPutToken(sixtyFour, "setup.tickRate", "60");
        String replaced = draftPutToken(sixtyFour, "setup.bindPort", "2");
        if (full.size() != OpsSetupDraft.MAX_ROWS || !"rows".equals(fresh) || replaced != null) {
            bad.add(full.size() + " rows: a new id -> " + fresh + ", an existing id -> " + replaced
                    + " (flips if the row cap moves or stops letting an existing row be replaced)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code draft-stale-load} over a temp folder - {@link OpsSetupDraft#load}
     * keeps a draft whose {@code base} equals or passes the current base, drops one older than it (EMPTY and the
     * file deleted), never drops on a current base of 0 or a draft base of 0; {@link OpsSetupDraft#configBase} =
     * the newer of two files in both orders, 0 for two missing files.
     */
    static String draftStaleLoadRow() throws Exception {
        String r = "draft-stale-load";
        Path[] dir = {null};
        setup(r, () -> dir[0] = Files.createTempDirectory("cc-draft-stale"));
        List<String> bad = new ArrayList<>();
        try {
            Path file = dir[0].resolve(OpsSetupDraft.FILE_NAME);
            long[][] legs = {{1000L, 1000L, 1}, {1000L, 999L, 1}, {1000L, 1001L, 0}, {1000L, 0L, 1}, {0L, 5000L, 1}};
            for (long[] leg : legs) {
                OpsSetupDraft.Draft d = new OpsSetupDraft.Draft(Map.of("setup.tickRate", "60"), leg[0], null);
                setup(r, () -> OpsSetupDraft.write(file, d));
                OpsSetupDraft.Draft got = OpsSetupDraft.load(file, leg[1]);
                boolean kept = leg[2] == 1;
                if (got.isEmpty() == kept || Files.exists(file) != kept) {
                    bad.add("draft base " + leg[0] + " at current " + leg[1] + " -> empty=" + got.isEmpty() + " file="
                            + Files.exists(file) + " (flips if a hand edit of either config file stops winning over an"
                            + " older draft, or an unknown base starts dropping one)");
                }
            }
            Path a = dir[0].resolve("a.properties");
            Path b = dir[0].resolve("b.properties");
            setup(r, () -> {
                Files.writeString(a, "a=1\n", StandardCharsets.UTF_8);
                Files.writeString(b, "b=1\n", StandardCharsets.UTF_8);
            });
            long older = 1_700_000_000_000L;
            long newer = 1_700_000_100_000L;
            for (boolean aNewer : new boolean[] {false, true}) {
                setup(r, () -> {
                    Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(aNewer ? newer : older));
                    Files.setLastModifiedTime(b, java.nio.file.attribute.FileTime.fromMillis(aNewer ? older : newer));
                });
                long want = Files.getLastModifiedTime(aNewer ? a : b).toMillis();
                long got = OpsSetupDraft.configBase(a, b);
                if (got != want || want <= Files.getLastModifiedTime(aNewer ? b : a).toMillis()) {
                    bad.add("configBase with " + (aNewer ? "a" : "b") + " newer -> " + got + " want " + want
                            + " (flips if the base stops being the NEWER file's mtime)");
                }
            }
            long none = OpsSetupDraft.configBase(dir[0].resolve("missing-1"), dir[0].resolve("missing-2"));
            if (none != 0L) {
                bad.add("configBase of two missing files -> " + none + " (flips if an absent file stops counting 0)");
            }
        } finally {
            deleteTree(dir[0]);
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code draft-roundtrip} - {@code parse(render(d))} equals {@code d}
     * (rows in order, {@code base}, {@code updated}) with a value holding {@code =} and an empty value; a CRLF
     * body parses the same; {@code #} lines are skipped; a body over the file cap = EMPTY;
     * {@link OpsSetupDraft#clear} removes the file and a planted {@code .tmp}.
     */
    static String draftRoundtripRow() throws Exception {
        String r = "draft-roundtrip";
        List<String> bad = new ArrayList<>();
        java.util.LinkedHashMap<String, String> rows = new java.util.LinkedHashMap<>();
        rows.put("setup.tickRate", "60");
        rows.put("setup.bindHost", "a=b=c");
        rows.put("setup.record", "");
        rows.put("setup.advertise", "10.2.0.2:51400");
        OpsSetupDraft.Draft d = new OpsSetupDraft.Draft(Collections.unmodifiableMap(rows), 1234L,
                Instant.parse("2026-10-17T12:00:00.123Z"));
        String body = OpsSetupDraft.render(d);
        String[][] legs = {{"LF", body}, {"CRLF", body.replace("\n", "\r\n")},
            {"# lines", body + "#setup.tickRate=99\n# a note\n"}};
        for (String[] leg : legs) {
            OpsSetupDraft.Draft back = OpsSetupDraft.parse(leg[1].getBytes(StandardCharsets.UTF_8));
            if (!d.equals(back) || !new ArrayList<>(d.rows().keySet()).equals(new ArrayList<>(back.rows().keySet()))) {
                bad.add(leg[0] + " -> " + back + " (flips if a row is lost, changed or reordered through the file)");
            }
        }
        OpsSetupDraft.Draft over = OpsSetupDraft.parse((body + "#" + "p".repeat(OpsSetupDraft.MAX_FILE_BYTES))
                .getBytes(StandardCharsets.UTF_8));
        if (!over.isEmpty() || over.base() != 0L) {
            bad.add("a body over the cap -> " + over.rows().size() + " rows (flips if the reader's size cap is dropped)");
        }
        Path[] dir = {null};
        setup(r, () -> dir[0] = Files.createTempDirectory("cc-draft-roundtrip"));
        try {
            Path file = dir[0].resolve(OpsSetupDraft.FILE_NAME);
            Path tmp = dir[0].resolve(OpsSetupDraft.FILE_NAME + ".tmp");
            setup(r, () -> {
                OpsSetupDraft.write(file, d);
                Files.writeString(tmp, "x", StandardCharsets.UTF_8);
            });
            boolean wrote = Files.exists(file) && d.equals(OpsSetupDraft.load(file, 0L));
            OpsSetupDraft.clear(file);
            if (!wrote || Files.exists(file) || Files.exists(tmp)) {
                bad.add("write+load=" + wrote + " after clear file=" + Files.exists(file) + " tmp=" + Files.exists(tmp)
                        + " (flips if the file write loses the draft or clear leaves the file or its temp sibling)");
            }
        } finally {
            deleteTree(dir[0]);
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    // ---- the HTTP rows -------------------------------------------------------------------------

    private static void httpRows(List<String> fails) {
        Rig rig = new Rig();
        try {
            stage(rig);
        } catch (SetupFail setup) {
            System.out.println(setup.getMessage());
            fails.add(setup.getMessage());
            if (rig.gateway != null) {
                rig.gateway.close();
            }
            if (rig.root != null) {
                deleteTree(rig.root);
            }
            return;
        }
        OpsRoots prior = OpsRoots.ROOTS.get();
        OpsRoots.ROOTS.set(rig.roots);
        try {
            row(fails, "web-token-403", () -> tokenRow(rig));
            row(fails, "web-exact-path", () -> exactPathRow(rig));
            row(fails, "web-405", () -> methodRow(rig));
            row(fails, "web-413", () -> sizeRow(rig));
            row(fails, "web-headers", () -> headersRow(rig));
            row(fails, "web-token-file", () -> tokenFileRow(rig));
            row(fails, "web-browser-open", () -> browserOpenRow(rig));
            row(fails, "web-config-no-secret", () -> configRow(rig));
            row(fails, "web-reveal", () -> revealRow(rig));
            row(fails, "web-start-tristate", () -> startRow(rig));
            row(fails, "web-start-roster-mult", () -> startRosterMultRow(rig));
            row(fails, "web-start-seats", () -> startSeatsRow(rig));
            row(fails, "web-start-rngpins", () -> startRngPinsRow(rig));
            row(fails, "web-start-magicseam", () -> startMagicSeamRow(rig));
            row(fails, "web-start-pilotship", () -> startPilotShipRow(rig));
            row(fails, "web-start-pvp", () -> startPvpRow(rig));
            row(fails, "web-start-descriptor-overcap", () -> startDescriptorOverCapRow(rig));
            row(fails, "web-start-gameroot", () -> startGameRootRow(rig));
            row(fails, "web-draft-clear", () -> draftClearRow(rig));
            row(fails, "web-start-second-folder", () -> startSecondFolderRow(rig));
            row(fails, "web-brick", () -> brickRow(rig));
            row(fails, "web-lease", () -> leaseRow(rig));
            row(fails, "web-start-lease", () -> startLeaseRow(rig));
            row(fails, "web-brick-lease", () -> brickLeaseRow(rig));
            row(fails, "web-join", () -> joinRow(rig));
            row(fails, "web-join-admin-first", () -> joinAdminFirstRow(rig));
            row(fails, "web-join-arm-recorded", () -> joinArmRecordedRow(rig));
            row(fails, "web-status-armed-cidkey", () -> statusArmedCidKeyRow(rig));
            row(fails, "web-join-admin-cap-9th", () -> joinAdminCapRow(rig));
            row(fails, "web-join-kit-stamps", () -> joinKitStampsRow(rig));
            row(fails, "web-watch-route", () -> watchRouteRow(rig));
            row(fails, "web-watch-linux", () -> watchLinuxRow(rig));
            row(fails, "web-watch-kit-check", () -> watchKitCheckRow(rig));
            row(fails, "web-watch-second-check", () -> watchSecondCheckRow(rig));
            row(fails, "web-watch-note", () -> watchNoteRow(rig));
            row(fails, "web-verb-confirm-roundtrip", () -> verbConfirmRow(rig));
            row(fails, "api-verb-keep", () -> apiVerbKeepRow(rig));
            row(fails, "forms-ship-hide", () -> formsShipHideRow(rig));
            row(fails, "api-verb-ship-hide", () -> apiVerbShipHideRow(rig));
            row(fails, "web-kill-confirm", () -> killConfirmRow(rig));
            row(fails, "web-quit-confirm", () -> quitConfirmRow(rig));
            row(fails, "web-kill-single-flight", () -> killSingleFlightRow(rig));
            row(fails, "web-500-fixed", () -> fixed500Row(rig));
            row(fails, "web-kill-outcome", () -> killOutcomeRow(rig));
            row(fails, "web-mods-add", () -> modsAddRow(rig));
            row(fails, "web-mods-remove", () -> modsRemoveRow(rig));
            row(fails, "web-mods-use", () -> modsUseRow(rig));
            row(fails, "web-draft-survives-own-writes", () -> draftSurvivesOwnWritesRow(rig));
            row(fails, "web-draft-settled-by-fill", () -> draftSettledByFillRow(rig));
            row(fails, "web-draft-fill-mints-nothing", () -> draftFillMintsNothingRow(rig));
            row(fails, "web-match-settings", () -> matchSettingsRow(rig));
            row(fails, "web-match-settings-players", () -> matchSettingsPlayersRow(rig));
            row(fails, "web-draft", () -> draftRow(rig));
            row(fails, "web-dead-routes", () -> deadRoutesRow(rig));
            row(fails, "web-shape-new", () -> shapeNewRow(rig));
            row(fails, "web-shape-new-players", () -> shapeNewPlayersRow(rig));
            row(fails, "web-props-locked", () -> propsLockedRow(rig));
            row(fails, "web-start-keeps-password", () -> startKeepsPasswordRow(rig));
            row(fails, "config-match-settings", () -> configMatchSettingsRow(rig));
            row(fails, "web-reach", () -> reachRow(rig));
            row(fails, "web-status-reach", () -> statusReachRow(rig));
            row(fails, "web-status-pack", () -> statusPackRow(rig));
            row(fails, "web-start-record", () -> startRecordRow(rig));
            row(fails, "web-record-owner-untouched", OpsConsoleSelftest::recordOwnerUntouchedRow);
            row(fails, "web-start-arms-reach", () -> startArmsReachRow(rig));
        } finally {
            rig.boot.stop();
            OpsConsole.quitHook = OpsConsole.QUIT_LIVE;
            rig.gateway.close();
            OpsRoots.ROOTS.set(prior);
            deleteTree(rig.root);
        }
    }

    /**
     * {@code console-lock-static} - {@link OpsConsole#takeConsoleLock} on a
     * fresh folder creates {@code run/} and holds {@code run/console.lock}, and the hold SURVIVES {@link System#gc()}:
     * a second channel's {@code tryLock} on the file in this JVM throws {@code OverlappingFileLockException} and a
     * second take answers {@link OpsConsole#consoleBusySentence}; after {@link OpsConsole#releaseConsoleLock} a take
     * succeeds again (so the row leaves no hold behind).
     */
    static String consoleLockStaticRow() throws Exception {
        Path root = Files.createTempDirectory("cc-console-lock");
        List<String> bad = new ArrayList<>();
        try {
            Path file = root.resolve(ServeLauncher.RUN_DIR).resolve(OpsConsole.CONSOLE_LOCK_FILE);
            String first = OpsConsole.takeConsoleLock(root, true);
            if (first != null || !Files.isRegularFile(file)) {
                OpsConsole.releaseConsoleLock();
                return "a free folder's take answered " + first + ", lock file present=" + Files.isRegularFile(file)
                        + " (flips if the take stops creating run/ or cannot lock a fresh file)";
            }
            try {
                for (int i = 0; i < 3; i++) {
                    System.gc();
                    Thread.sleep(40);
                }
                boolean overlapped = false;
                try (java.nio.channels.FileChannel probe = java.nio.channels.FileChannel.open(file,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    java.nio.channels.FileLock stolen = probe.tryLock();
                    if (stolen != null) {
                        stolen.release();
                    }
                } catch (java.nio.channels.OverlappingFileLockException held) {
                    overlapped = true;
                }
                if (!overlapped) {
                    bad.add("after System.gc() a second tryLock in this JVM got the lock (flips if the channel and"
                            + " lock live in a local the channel's Cleaner drops, or the take lets go)");
                }
                String second = OpsConsole.takeConsoleLock(root, true);
                if (!OpsConsole.consoleBusySentence(true).equals(second)) {
                    bad.add("a second take answered " + second + " (flips if a held lock is taken twice, or the"
                            + " held-elsewhere branch loses its sentence)");
                }
            } finally {
                OpsConsole.releaseConsoleLock();
            }
            String again = OpsConsole.takeConsoleLock(root, false);
            OpsConsole.releaseConsoleLock();
            if (again != null) {
                bad.add("after the release a take answered " + again + " (flips if the release keeps the lock)");
            }
        } finally {
            deleteTree(root);
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-pack-line} - over the SERVED html + js: the hidden
     * {@code #pack-line} ({@code status.pack}) sits in the strip's head row, and the core script fills it with
     * {@code package <pack>} from the status answer and hides it when the answer has none.
     */
    static String packLinePageRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        int head = html.indexOf("<div class=\"strip-row strip-head\">");
        int headEnd = head < 0 ? -1 : html.indexOf("</div>", head);
        int slot = html.indexOf("<p id=\"pack-line\" class=\"dim\" data-cc=\"status.pack\" hidden></p>");
        if (slot < 0 || slot < head || slot > headEnd) {
            bad.add("no hidden #pack-line status.pack slot in the strip's head row (flips if the header loses the"
                    + " package line)");
        }
        if (!js.contains("const pack = s && typeof s.pack === 'string' ? s.pack : '';")
                || !js.contains("pk.hidden = !pack;") || !js.contains("CC.text(pk, pack ? 'package ' + pack : '');")) {
            bad.add("the core script does not fill #pack-line with 'package ' + s.pack and hide it without one");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-status-pack} - the REAL {@code GET /api/status} of the
     * rig's packed folder carries {@code "pack":"<pack.stamp>"} and its console's boot line is
     * {@code console: package <stamp> (<git.head first 9>)}; a second console booted on a folder whose MANIFEST
     * has no {@code pack.stamp} serves no {@code pack} key and has no boot line.
     */
    private static String statusPackRow(Rig rig) throws Exception {
        String r = "web-status-pack";
        List<String> bad = new ArrayList<>();
        Resp packed = call(rig, "GET", "/api/status", rig.boot.token(), null);
        if (packed.code() != 200 || !packed.body().contains(",\"pack\":\"20261008-120000\"")) {
            bad.add("packed rig -> " + packed.code() + " " + PeerText.bound(packed.body(), 80) + " (flips if the"
                    + " status stops serving pack from the MANIFEST's pack.stamp)");
        }
        String line = rig.boot.console().packLine();
        if (!"console: package 20261008-120000 (test)".equals(line)) {
            bad.add("boot line " + line + " (flips if the boot line drops the stamp or the git head)");
        }
        Path bare = rig.root.resolve("pkg-unstamped");
        setup(r, () -> {
            Files.createDirectories(bare);
            Files.writeString(bare.resolve(ServeLauncher.MANIFEST_FILE), "git.head=test\n", StandardCharsets.US_ASCII);
        });
        OpsConsole.Boot other = OpsConsole.boot(OpsRoots.packaged(bare), 0, rig.recorder, rig.kill);
        try {
            Resp plain = call(rig, other.port(), "GET", "/api/status", other.token(), null);
            if (plain.code() != 200 || plain.body().contains("\"pack\"") || other.console().packLine() != null) {
                bad.add("unstamped folder -> " + plain.code() + " pack key=" + plain.body().contains("\"pack\"")
                        + " boot line=" + other.console().packLine() + " (flips if pack is served without a"
                        + " pack.stamp row)");
            }
        } finally {
            other.stop();
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    static void stage(Rig rig) {
        String r = "web-rig";
        setup(r, () -> {
            rig.root = Files.createTempDirectory("cc-ops-console-selftest");
            rig.pkg = rig.root.resolve("pkg");
            Files.createDirectories(rig.pkg);
            Files.writeString(rig.pkg.resolve(ServeLauncher.MANIFEST_FILE),
                    "git.head=test\npack.stamp=20261008-120000\n", StandardCharsets.US_ASCII);
            Files.writeString(rig.pkg.resolve("match-web-pkg.txt"), cc.starsector.server.match.MatchDescriptorCodec
                    .encode(cc.starsector.server.match.MatchDescriptors.oneVsOne()), StandardCharsets.UTF_8);
            Files.writeString(rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG),
                    "# posture\nmatch.descriptor=match-web-pkg.txt\nmatch.fortressDp=400\n", StandardCharsets.UTF_8);
            rig.sidecar = rig.pkg.resolve("match-web-pkg.txt.password");
            Files.writeString(rig.sidecar, "password=" + SECRET + "\n", StandardCharsets.UTF_8);
            rig.roots = OpsRoots.packagedChecked(rig.pkg);
            Files.createDirectories(rig.roots.opsFile().getParent());
            rig.cmd = rig.root.resolve("operator-commands.txt");
            rig.recorder = cmd -> {
                rig.spawned.add(List.copyOf(cmd));
                OpsPressSelftest.FakeProcess p = new OpsPressSelftest.FakeProcess();
                p.exit = rig.spawnExit.get();
                rig.lastSpawn.set(p);
                return p;
            };
            rig.killMode.set((o, w, l) -> outcome("stop file written (selftest)"));
            rig.kill = (o, w, l) -> rig.killMode.get().kill(o, w, l);
            startGateway(rig);
            // the lease log dir stays ABSENT (every pull = an address-only fill, the no-app-log why)
            // except while web-lease plants one fresh port line for its filled leg
            rig.leaseLogs = rig.root.resolve("proton-logs");
            rig.joinFx = new FakeJoinEffects(rig.root.resolve("join-env"));
            OpsConsole.quitHook = rig.quits::incrementAndGet; // restored at the teardown
            rig.boot = OpsConsole.boot(rig.roots, 0, rig.recorder, rig.kill, new VpnLeaseProbe.Env(
                    InetAddress.getLoopbackAddress(), rig.gateway.getLocalPort(), 3000, rig.leaseLogs,
                    Clock.systemUTC()), rig.joinFx);
            rig.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).build();
        });
    }

    private static ServerOpsKill.Outcome outcome(String text) {
        return new ServerOpsKill.Outcome(true, false, null, List.of(), false, text);
    }

    private static HttpRequest request(int port, String method, String path, String token, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) {
            b.header(OpsConsole.TOKEN_HEADER, token);
        }
        if (body != null) {
            b.header("Content-Type", "application/x-www-form-urlencoded");
        }
        return b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static Resp call(Rig rig, int port, String method, String path, String token, String body) throws Exception {
        HttpResponse<String> r = rig.client.send(request(port, method, path, token, body),
                HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    static Resp call(Rig rig, String method, String path, String token, String body) throws Exception {
        return call(rig, rig.boot.port(), method, path, token, body);
    }

    static Resp post(Rig rig, String path, String body) throws Exception {
        return call(rig, "POST", path, rig.boot.token(), body);
    }

    /** Every /api/* x GET and POST without the key / with a wrong 43-char / a 10-char key = 403 +
     * the exact body; the three static files answer 200 without one; the real key reaches status. */
    private static String tokenRow(Rig rig) throws Exception {
        String wrong43 = "A".repeat(43);
        String short10 = "B".repeat(10);
        List<String> bad = new ArrayList<>();
        int probes = 0;
        for (String path : API_PATHS) {
            for (String method : List.of("GET", "POST")) {
                for (String key : Arrays.asList(null, wrong43, short10)) {
                    Resp r = call(rig, method, path, key, "POST".equals(method) ? "verb=end-round" : null);
                    probes++;
                    if (r.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(r.body())) {
                        bad.add(method + " " + path + " key=" + (key == null ? "none" : key.length() + "ch")
                                + " -> " + r.code());
                    }
                }
            }
        }
        for (String path : List.of("/", "/ops.css", "/ops.js")) {
            Resp r = call(rig, "GET", path, null, null);
            if (r.code() != 200 || r.body().isEmpty()) {
                bad.add("GET " + path + " without a key -> " + r.code());
            }
        }
        Resp keyed = call(rig, "GET", "/api/status", rig.boot.token(), null);
        if (keyed.code() != 200 || !keyed.body().contains("\"state\"")) {
            bad.add("GET /api/status WITH the key -> " + keyed.code());
        }
        return bad.isEmpty() && probes == API_PATHS.size() * 6 ? null : bad.size() + " wrong answer(s), first "
                + (bad.isEmpty() ? "-" : bad.get(0)) + " (flips if the /api/* key compare is skipped or loosened,"
                + " or a static file starts demanding the key)";
    }

    private static String exactPathRow(Rig rig) throws Exception {
        List<String> bad = new ArrayList<>();
        for (String path : List.of("/api/status/x", "/api/statusx", "/ops.jsx")) {
            Resp r = call(rig, "GET", path, rig.boot.token(), null);
            if (r.code() != 404) {
                bad.add(path + " -> " + r.code());
            }
        }
        return bad.isEmpty() ? null : "not 404: " + bad + " (flips if a handler stops comparing the EXACT path"
                + " - the JDK context is a prefix match)";
    }

    private static String methodRow(Rig rig) throws Exception {
        Resp postPage = call(rig, "POST", "/", null, "x=1");
        Resp getStart = call(rig, "GET", "/api/start", rig.boot.token(), null);
        // /api/mods serves GET + POST, and the funnel's
        // webRequestAllowed refuses every other method 403 BEFORE the 405 check - so a PUT there
        // answers the fixed host refusal, never the route
        Resp putMods = call(rig, "PUT", "/api/mods", rig.boot.token(), "op=rescan");
        boolean putRefused = putMods.code() == 403 && OpsConsole.REFUSED_HOST.equals(putMods.body());
        return postPage.code() == 405 && getStart.code() == 405 && putRefused ? null
                : "POST / -> " + postPage.code() + ", GET /api/start (keyed) -> " + getStart.code()
                        + ", PUT /api/mods (keyed) -> " + putMods.code() + " " + PeerText.bound(putMods.body(), 60)
                        + " (flips if a route stops refusing the method it does not serve)";
    }

    /** 65537 bytes = 413 + the fixed body; exactly 65536 = served (the verb route's own refusal). */
    private static String sizeRow(Rig rig) throws Exception {
        String prefix = "verb=end-round&pad=";
        String atCap = prefix + "a".repeat(OpsConsole.BODY_CAP - prefix.length());
        Resp over = post(rig, "/api/verb", atCap + "a");
        Resp at = post(rig, "/api/verb", atCap);
        return over.code() == 413 && OpsConsole.TOO_LARGE.equals(over.body()) && at.code() == 200
                && at.body().contains("\"error\"") ? null
                : "65537 -> " + over.code() + ", 65536 -> " + at.code() + " " + PeerText.bound(at.body(), 80)
                        + " (flips if the body cap is dropped, moved off 64 KB, or trusts Content-Length)";
    }

    private static String headersRow(Rig rig) throws Exception {
        List<String> bad = new ArrayList<>();
        for (Resp r : List.of(call(rig, "GET", "/", null, null), call(rig, "GET", "/api/status", null, null),
                call(rig, "GET", "/ops.jsx", rig.boot.token(), null))) {
            String tag = r.code() + "";
            need(bad, tag, r.headers(), "Cache-Control", "no-store");
            need(bad, tag, r.headers(), "X-Frame-Options", "DENY");
            need(bad, tag, r.headers(), "Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
            need(bad, tag, r.headers(), "Referrer-Policy", "no-referrer");
            for (String name : r.headers().map().keySet()) {
                if (name.toLowerCase(Locale.ROOT).startsWith("access-control-")) {
                    bad.add(tag + " carries " + name);
                }
            }
        }
        return bad.isEmpty() ? null : bad + " (flips if a response loses a security header or gains a CORS one)";
    }

    private static void need(List<String> bad, String tag, HttpHeaders h, String name, String want) {
        String got = h.firstValue(name).orElse(null);
        if (!want.equals(got)) {
            bad.add(tag + " " + name + "=" + got);
        }
    }

    /**
     * The file holds the 43-char key the guard accepts ({@code rw-------} on POSIX) and the key PERSISTS
     * (the row re-cut from "a second boot rewrites it"): a second boot on the same
     * package KEEPS it - the same key served, the file's bytes untouched, stdout {@code cc-console key=kept};
     * a planted malformed file, a folder in the file's place (the not-a-regular-file branch; a symlink needs a
     * privilege on Windows) and, on POSIX, a loosened {@code rw-r--r--} file are burnt - a FRESH 43-char key
     * written and served, the old key refused 403, the row's {@code reason} naming the branch, the teed
     * stdout never carrying a key. The rig's own key file is restored at the end.
     */
    private static String tokenFileRow(Rig rig) throws Exception {
        Path file = rig.boot.tokenFile();
        String first = Files.readString(file, StandardCharsets.US_ASCII);
        Resp viaFile = call(rig, "GET", "/api/status", first, null);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        String perms = posix ? PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) : "n/a";
        List<String> bad = new ArrayList<>();
        if (first.length() != 43 || !first.matches(OpsConsole.TOKEN_SHAPE) || !first.equals(rig.boot.token())) {
            bad.add("file " + first.length() + "ch is not the guard's key");
        }
        if (viaFile.code() != 200) {
            bad.add("via-file " + viaFile.code());
        }
        if (posix && !"rw-------".equals(perms)) {
            bad.add("perms " + perms);
        }
        try {
            Rebooted kept = reboot(rig, first);
            if (!"cc-console key=kept".equals(kept.row()) || !first.equals(kept.token()) || !first.equals(kept.file())
                    || kept.own() != 200 || !kept.file.equals(kept.token())) {
                bad.add("second boot row=" + kept.row() + " same-key=" + first.equals(kept.token()) + " own="
                        + kept.own());
            }
            Files.writeString(file, "not-a-key\n", StandardCharsets.US_ASCII);
            burnt(bad, "malformed", reboot(rig, first), first);
            Files.delete(file);
            Files.createDirectory(file);
            burnt(bad, "not_file", reboot(rig, first), first);
            if (posix) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
                burnt(bad, "loose_perms", reboot(rig, first), first);
            } else {
                System.out.println("web-token-file: the loose_perms branch is POSIX-only (skipped on this fs)");
            }
        } finally {
            OpsConsole.writeToken(rig.pkg, first); // the rig's console still compares against `first`
        }
        return bad.isEmpty() ? null : bad + " (flips if the token file is not the guard's key, is not KEPT by a"
                + " later boot, is kept when malformed / not a regular file / readable beyond its owner, or the key"
                + " row carries a key)";
    }

    /** One re-boot of the rig's package: its key row, its key, the file's text, its guard's answers to its own
     * key and to {@code old}, and whether the teed stdout carried either key. */
    private record Rebooted(String row, String token, String file, int own, int old, boolean leaked) {
    }

    private static Rebooted reboot(Rig rig, String old) throws Exception {
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        OpsConsole.Boot second;
        try {
            second = OpsConsole.boot(rig.roots, 0, rig.recorder, rig.kill);
        } finally {
            System.setOut(prior);
        }
        try {
            String out = captured.toString(StandardCharsets.UTF_8);
            String row = out.lines().filter(l -> l.startsWith("cc-console key=")).findFirst().orElse("<no key row>");
            String fileText = Files.readString(second.tokenFile(), StandardCharsets.US_ASCII);
            Resp own = call(rig, second.port(), "GET", "/api/status", second.token(), null);
            Resp oldKey = call(rig, second.port(), "GET", "/api/status", old, null);
            return new Rebooted(row, second.token(), fileText, own.code(), oldKey.code(),
                    out.contains(second.token()) || out.contains(old));
        } finally {
            second.stop();
        }
    }

    private static void burnt(List<String> bad, String reason, Rebooted r, String first) {
        if (!("cc-console key=new reason=" + reason).equals(r.row()) || r.token().equals(first)
                || !r.token().matches(OpsConsole.TOKEN_SHAPE) || !r.file().equals(r.token()) || r.own() != 200
                || r.old() != 403 || r.leaked()) {
            bad.add(reason + ": row=" + r.row() + " fresh=" + !r.token().equals(first) + " file=key="
                    + r.file().equals(r.token()) + " own/old=" + r.own() + "/" + r.old() + " leaked=" + r.leaked());
        }
    }

    /**
     * {@code web-browser-open} (the planned {@code Desktop.browse}
     * was dead under the forced headless law): {@link OpsConsole#printAddress} off a tty prints ONLY the file hint
     * (no address, no spawn); on a tty off Windows the {@code open http://127.0.0.1:<port>/#<key>} line and
     * {@code result=unsupported}, no spawn; on a tty + Windows that line, EXACTLY {@link OpsConsole#browserCommand}'s
     * argv ({@code cmd.exe /d /c start "" <url>}, the url ending in the key) and {@code result=opened}; a throwing
     * spawn = {@code result=failed:IOException} with the exception's message (which could carry the url) never
     * printed. No process is ever started.
     */
    private static String browserOpenRow(Rig rig) throws Exception {
        List<List<String>> spawned = new ArrayList<>();
        OpsConsole.BrowserSpawn recorder = argv -> spawned.add(List.copyOf(argv));
        String url = "http://127.0.0.1:" + rig.boot.port() + "/#" + rig.boot.token();
        OpsConsole.BrowserSpawn thrower = argv -> {
            throw new IOException("message carrying " + url);
        };
        List<String> offTty = captureLines(() -> OpsConsole.printAddress(rig.boot, false, true, recorder));
        int afterOffTty = spawned.size();
        List<String> linuxTty = captureLines(() -> OpsConsole.printAddress(rig.boot, true, false, recorder));
        int afterLinux = spawned.size();
        List<String> winTty = captureLines(() -> OpsConsole.printAddress(rig.boot, true, true, recorder));
        List<String> failed = captureLines(() -> OpsConsole.printAddress(rig.boot, true, true, thrower));
        String root = System.getenv("SystemRoot");
        List<String> want = OpsConsole.browserCommand(root == null || root.isBlank() ? "C:\\Windows" : root, url);
        List<String> bad = new ArrayList<>();
        if (!List.of("address: see run/console.token").equals(offTty) || afterOffTty != 0) {
            bad.add("off a tty: " + offTty + " spawns=" + afterOffTty);
        }
        if (!List.of("open " + url, "cc-console browser result=unsupported").equals(linuxTty) || afterLinux != 0) {
            bad.add("tty off Windows: " + linuxTty.size() + " line(s) spawns=" + afterLinux);
        }
        if (!List.of("open " + url, "cc-console browser result=opened").equals(winTty) || spawned.size() != 1
                || !want.equals(spawned.get(0))) {
            bad.add("tty on Windows: " + winTty.size() + " line(s) spawns=" + spawned.size() + " argv-as-planned="
                    + (spawned.size() == 1 && want.equals(spawned.get(0))));
        }
        if (want.size() != 6 || !want.get(0).endsWith("\\System32\\cmd.exe") || !"/d".equals(want.get(1))
                || !"/c".equals(want.get(2)) || !"start".equals(want.get(3)) || !"\"\"".equals(want.get(4))
                || !url.equals(want.get(5))) {
            bad.add("argv shape drifted (" + want.size() + " elements)");
        }
        if (!List.of("open " + url, "cc-console browser result=failed:IOException").equals(failed)
                || String.join("\n", failed).contains("message carrying")) {
            bad.add("throwing spawn: " + failed.size() + " line(s) message-printed="
                    + String.join("\n", failed).contains("message carrying"));
        }
        return bad.isEmpty() ? null : bad + " (flips if the address lines leave the tty gate, the browser spawn"
                + " runs off a tty or off Windows, its argv drifts from cmd /d /c start \"\" <url>, or a failure"
                + " prints more than the class name)";
    }

    /** Runs {@code step} with stdout teed and returns what it printed, as lines. */
    static List<String> captureLines(Step step) throws Exception {
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        try {
            step.run();
        } finally {
            System.setOut(prior);
        }
        return captured.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static String configRow(Rig rig) throws Exception {
        Resp c = call(rig, "GET", "/api/config", rig.boot.token(), null);
        return c.code() == 200 && !c.body().contains(SECRET) && c.body().contains("\"hasPassword\":true") ? null
                : "config " + c.code() + " leaks=" + c.body().contains(SECRET) + " hasPassword="
                        + c.body().contains("\"hasPassword\":true")
                        + " (flips if /api/config serves a stored password or stops reading the sidecar)";
    }

    /**
     * Show saved passwords ({@code POST /api/reveal}).
     * A keyed GET = 405; a confirmed POST under a wrong key = the 403 refusal; a POST without the
     * {@code reveal} confirm = the confirm JSON and no secret; with
     * it and an admin sidecar planted beside {@code rig.sidecar} = both values, for the offered standing
     * descriptor AND the blank (null-path) pick; both sidecars moved aside = two {@code ""}; a descriptor
     * the selector does not offer whose {@code .password} file EXISTS ({@code ./match-web-pkg.txt}) =
     * the {@value OpsConsole#REVEAL_UNKNOWN_SHAPE} refusal; stdout (teed for the row) carries the one
     * {@code cc-console reveal} row and neither secret ({@code API_PATHS} also lists the route for
     * {@code web-token-403}). Planted / moved files are restored in {@code finally}.
     */
    private static String revealRow(Rig rig) throws Exception {
        String r = "web-reveal";
        String adminSecret = "admin-fixture-pass-77";
        String shape = rig.sidecar.getFileName().toString().replace(".password", "");
        Path admin = rig.pkg.resolve(ServerOpsCore.adminSidecarOf(rig.sidecar.getFileName().toString()));
        Path aside = rig.root.resolve("reveal-aside.password");
        Path adminAside = rig.root.resolve("reveal-aside.adminpassword");
        setup(r, () -> Files.writeString(admin, "password=" + adminSecret + "\n", StandardCharsets.UTF_8));
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        Resp get;
        Resp wrongKey;
        Resp ask;
        Resp both;
        Resp blank;
        Resp gone;
        Resp unknown;
        try {
            get = call(rig, "GET", "/api/reveal", rig.boot.token(), null);
            wrongKey = call(rig, "POST", "/api/reveal", "C".repeat(43), "descriptor=" + shape + "&confirmed=reveal");
            ask = post(rig, "/api/reveal", "descriptor=" + shape);
            both = post(rig, "/api/reveal", "descriptor=" + shape + "&confirmed=reveal");
            blank = post(rig, "/api/reveal", "descriptor=&confirmed=reveal");
            unknown = post(rig, "/api/reveal", "descriptor=./" + shape + "&confirmed=reveal");
            setup(r, () -> {
                Files.move(rig.sidecar, aside);
                Files.move(admin, adminAside);
            });
            gone = post(rig, "/api/reveal", "descriptor=" + shape + "&confirmed=reveal");
        } finally {
            System.setOut(prior);
            setup(r, () -> {
                if (Files.exists(aside)) {
                    Files.move(aside, rig.sidecar);
                }
                Files.deleteIfExists(adminAside);
                Files.deleteIfExists(admin);
            });
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        String values = "{\"ok\":true,\"password\":\"" + SECRET + "\",\"adminPassword\":\"" + adminSecret + "\"}";
        List<String> bad = new ArrayList<>();
        if (get.code() != 405) {
            bad.add("GET -> " + get.code() + " (the route stopped refusing GET)");
        }
        if (wrongKey.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(wrongKey.body())) {
            bad.add("wrong key -> " + wrongKey.code() + " (the route lost its key guard)");
        }
        if (!ask.body().contains("\"key\":\"" + OpsConsole.REVEAL_KEY + "\"") || ask.body().contains(SECRET)) {
            bad.add("unconfirmed -> " + PeerText.bound(ask.body(), 80) + " (the confirm was skipped)");
        }
        if (!values.equals(both.body()) || !values.equals(blank.body())) {
            bad.add("confirmed values wrong: picked=" + values.equals(both.body()) + " blank="
                    + values.equals(blank.body()) + " (the sidecar rule or the answer keys changed)");
        }
        if (!"{\"ok\":true,\"password\":\"\",\"adminPassword\":\"\"}".equals(gone.body())) {
            bad.add("missing sidecars -> " + PeerText.bound(gone.body(), 80) + " (a missing sidecar no longer answers \"\")");
        }
        if (!unknown.body().contains(OpsConsole.REVEAL_UNKNOWN_SHAPE) || unknown.body().contains(SECRET)) {
            bad.add("unoffered descriptor answered " + PeerText.bound(unknown.body(), 60)
                    + " (the offered-descriptor bound on the file read was dropped)");
        }
        if (!out.contains("cc-console reveal") || out.contains(SECRET) || out.contains(adminSecret)) {
            bad.add("stdout row seen=" + out.contains("cc-console reveal") + " secret leaked="
                    + (out.contains(SECRET) || out.contains(adminSecret)) + " (a secret reached stdout or the row is gone)");
        }
        bad.addAll(revealRotateLeg(rig));
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code keep} over the saved sidecar REACHES the launch (the recording spawn) with the sidecar
     * bytes unchanged; {@code replace} blank and the admin {@code remove} without its box are refused
     * before anything is written or spawned.
     */
    private static String startRow(Rig rig) throws Exception {
        String fields = "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400&descriptor=match-web-pkg.txt";
        byte[] before = Files.readAllBytes(rig.sidecar);
        int spawns0 = rig.spawned.size();
        Resp blank = post(rig, "/api/start", fields + "&passwordAction=replace&password=");
        Resp noBox = post(rig, "/api/start", fields + "&adminAction=remove");
        int spawns1 = rig.spawned.size();
        boolean untouched1 = Arrays.equals(before, Files.readAllBytes(rig.sidecar));
        Resp keep = post(rig, "/api/start", fields + "&passwordAction=keep&confirmed="
                + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed=" + ServerOpsCore.CONFIRM_ARMED_DOOR);
        long until = System.currentTimeMillis() + 10_000L;
        while (ServerOpsLaunch.STAGING.get() && System.currentTimeMillis() < until) {
            Thread.sleep(20L);
        }
        int spawns2 = rig.spawned.size();
        boolean untouched2 = Arrays.equals(before, Files.readAllBytes(rig.sidecar));
        boolean ok = blank.body().contains(OpsConsoleForms.jsonEscape(OpsConsoleForms.BLANK_PASSWORD))
                && noBox.body().contains(OpsConsoleForms.jsonEscape(OpsConsoleForms.ADMIN_REMOVE_BOX))
                && spawns1 == spawns0 && untouched1 && keep.body().startsWith("{\"ok\":true")
                && spawns2 > spawns1 && untouched2 && !ServerOpsLaunch.STAGING.get();
        return ok ? null : "blank " + PeerText.bound(blank.body(), 90) + " | no-box " + PeerText.bound(noBox.body(), 90)
                + " | keep " + PeerText.bound(keep.body(), 160) + " | spawns " + spawns0 + "/" + spawns1 + "/" + spawns2
                + " sidecar kept " + untouched1 + "/" + untouched2 + " (flips if keep stops resolving the PICKED"
                + " sidecar server-side - a package Start then refuses the blank password - or a refusal writes or"
                + " spawns)";
    }

    /**
     * {@code web-start-roster-mult} - Roster x through the REAL
     * {@code POST /api/start} over the rig's package props ({@code match.fortressDp=400}, no roster rows).
     * {@code /api/config} prefills {@code "1"}; a multiplier 2 beside Roster DP 0 is refused with the
     * cross-field sentence before anything is written or spawned; beside Roster DP 120 the Start reaches its
     * spawn, writes {@code match.waveRosterMultiplier=2.0} and names it, and {@code /api/config} then serves
     * it; a unit Start removes the row and its marker. The props bytes are restored in {@code finally}.
     */
    private static String startRosterMultRow(Rig rig) throws Exception {
        String r = "web-start-roster-mult";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        String fields = "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400&descriptor=match-web-pkg.txt"
                + "&passwordAction=keep&confirmed=" + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed="
                + ServerOpsCore.CONFIRM_ARMED_DOOR;
        AtomicReference<byte[]> prior = new AtomicReference<>();
        setup(r, () -> prior.set(Files.readAllBytes(props)));
        Resp config0;
        Resp refused;
        Resp set;
        Resp config1;
        Resp unit;
        int spawns0;
        int spawns1;
        int spawns2;
        int spawns3;
        byte[] afterRefused;
        List<String> afterSet;
        List<String> afterUnit;
        try {
            config0 = call(rig, "GET", "/api/config", rig.boot.token(), null);
            spawns0 = rig.spawned.size();
            refused = post(rig, "/api/start", fields + "&waveRosterFullDp=0&waveRosterMultiplier=2");
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterRefused = Files.readAllBytes(props);
            set = post(rig, "/api/start", fields + "&waveRosterFullDp=120&waveRosterMultiplier=2");
            awaitStaging();
            spawns2 = rig.spawned.size();
            afterSet = Files.readAllLines(props, StandardCharsets.UTF_8);
            config1 = call(rig, "GET", "/api/config", rig.boot.token(), null);
            unit = post(rig, "/api/start", fields + "&waveRosterFullDp=120&waveRosterMultiplier=1");
            awaitStaging();
            spawns3 = rig.spawned.size();
            afterUnit = Files.readAllLines(props, StandardCharsets.UTF_8);
        } finally {
            setup(r, () -> Files.write(props, prior.get()));
        }
        List<String> bad = new ArrayList<>();
        if (!config0.body().contains("\"waveRosterMultiplier\":\"1\"")) {
            bad.add("config before " + PeerText.bound(config0.body(), 80) + " (the absent row stopped prefilling 1)");
        }
        if (!refused.body().contains("Roster x needs Roster DP above 0") || spawns1 != spawns0
                || !Arrays.equals(prior.get(), afterRefused)) {
            bad.add("x2 + roster 0 " + PeerText.bound(refused.body(), 120) + " spawns " + spawns0 + "/" + spawns1
                    + " (the cross-field refusal is gone, or it wrote or spawned)");
        }
        if (!set.body().startsWith("{\"ok\":true") || !set.body().contains("roster wave x 2.0") || spawns2 <= spawns1
                || !afterSet.contains(OpsMatchRows.WAVE_ROSTER_MULTIPLIER_KEY + "=2.0")
                || !afterSet.contains(OpsMatchRows.WAVE_ROSTER_FULL_DP_KEY + "=120")) {
            bad.add("x2 + roster 120 " + PeerText.bound(set.body(), 120) + " spawns " + spawns1 + "/" + spawns2
                    + " row " + afterSet.contains(OpsMatchRows.WAVE_ROSTER_MULTIPLIER_KEY + "=2.0")
                    + " (the form field never reached the Start flow, or the row was not written)");
        }
        if (!config1.body().contains("\"waveRosterMultiplier\":\"2.0\"")) {
            bad.add("config after " + PeerText.bound(config1.body(), 80) + " (the written row is not served back)");
        }
        boolean unitClean = afterUnit.stream().noneMatch(l -> l.startsWith(OpsMatchRows.WAVE_ROSTER_MULTIPLIER_KEY + "=")
                || l.equals(OpsMatchRows.WAVE_ROSTER_MULTIPLIER_COMMENT));
        if (!unit.body().startsWith("{\"ok\":true") || spawns3 <= spawns2 || !unitClean) {
            bad.add("x1 " + PeerText.bound(unit.body(), 80) + " spawns " + spawns2 + "/" + spawns3 + " clean "
                    + unitClean + " (a unit Start left the row or its marker)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-gameroot} - the Game folder is saved
     * at Start, the Swing package wrap ({@link ServerOpsLaunch#savingLauncherRow}). A CRLF
     * {@code launcher.properties} (an old {@code game.root} row + a neighbour row) under the rig's package
     * root; a keep Start with a typed {@code gameRoot} that REACHES the recording spawn rewrites only that
     * row, byte-exact (the CRLF and the neighbour kept); a Start with a BLANK {@code gameRoot} leaves the
     * file byte-identical; {@code /api/config} then serves the saved row. The file is removed in
     * {@code finally}.
     */
    private static String startGameRootRow(Rig rig) throws Exception {
        String r = "web-start-gameroot";
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        String fields = "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400&descriptor=match-web-pkg.txt"
                + "&passwordAction=keep&confirmed=" + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed="
                + ServerOpsCore.CONFIRM_ARMED_DOOR;
        String typedRoot = "C:\\new\\Starsector";
        byte[] want = ("game.root=" + typedRoot + "\r\nkeep.me=1\r\n").getBytes(StandardCharsets.UTF_8);
        setup(r, () -> Files.write(launcher, "game.root=C:\\old\r\nkeep.me=1\r\n".getBytes(StandardCharsets.UTF_8)));
        Resp typed;
        Resp blank;
        Resp config;
        int spawns0;
        int spawns1;
        int spawns2;
        byte[] afterTyped;
        byte[] afterBlank;
        try {
            spawns0 = rig.spawned.size();
            typed = post(rig, "/api/start", fields + "&gameRoot=" + URLEncoder.encode(typedRoot, StandardCharsets.UTF_8));
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterTyped = Files.readAllBytes(launcher);
            blank = post(rig, "/api/start", fields + "&gameRoot=");
            awaitStaging();
            spawns2 = rig.spawned.size();
            afterBlank = Files.readAllBytes(launcher);
            config = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            setup(r, () -> Files.deleteIfExists(launcher));
        }
        List<String> bad = new ArrayList<>();
        if (!typed.body().startsWith("{\"ok\":true") || spawns1 <= spawns0) {
            bad.add("typed Start " + PeerText.bound(typed.body(), 120) + " spawns " + spawns0 + "/" + spawns1
                    + " (the Start never reached its spawn)");
        }
        if (!Arrays.equals(want, afterTyped)) {
            bad.add("after the typed Start the file is '" + PeerText.bound(new String(afterTyped, StandardCharsets.UTF_8)
                    .replace("\r", "<CR>").replace("\n", "<LF>"), 120) + "' (the savingLauncherRow wrap was dropped,"
                    + " or the row writer rewrote more than its row)");
        }
        if (!blank.body().startsWith("{\"ok\":true") || spawns2 <= spawns1 || !Arrays.equals(want, afterBlank)) {
            bad.add("blank Start " + PeerText.bound(blank.body(), 80) + " spawns " + spawns1 + "/" + spawns2
                    + " file kept " + Arrays.equals(want, afterBlank) + " (a blank Game folder wrote the file)");
        }
        if (!config.body().contains("\"gameRoot\":\"C:\\\\new\\\\Starsector\"")) {
            bad.add("config " + PeerText.bound(config.body(), 80) + " (/api/config stopped serving the game.root row)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-draft-clear} - the unsaved Setup draft is cleared by a
     * STARTED Start or Watch only, through the REAL routes on the rig's package (the Start rows' fields; the status
     * file taken aside; the props, the status file and the draft restored in {@code finally}). A saved draft
     * survives a REFUSED Start ({@code tickRate=x}, refused inside the flow, under the same lock as the clear): the
     * file stands and {@code /api/config} still serves the row. A started {@code POST /api/watch} ({@code os.name}
     * pinned to Windows) removes the file; a re-saved draft is then removed by a started {@code POST /api/start}
     * (which also replaces the watch's launch, ending its armed press) and config serves {@code "draft":{}}. The
     * file is checked BEFORE any config read, because a config load drops a stale draft by itself. Flips if the
     * clear moves before the started check (a refused Start would lose the typed Setup) or is dropped (an old draft
     * would overlay a started config).
     */
    private static String draftClearRow(Rig rig) throws Exception {
        String r = "web-draft-clear";
        Path draft = OpsSetupDraft.fileFor(rig.roots);
        Path props = rig.roots.config();
        Path ops = rig.roots.opsFile();
        String fields = startFields("match-web-pkg.txt");
        Path install = rig.root.resolve("draft-clear-game"); // the watch's kit check wants a game (row web-watch-kit-check)
        byte[][] prior = new byte[4][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = takeFile(draft);
            prior[1] = Files.readAllBytes(props);
            prior[2] = takeFile(ops); // a live status file would refuse the Start
            prior[3] = stageWatchGame(rig, install);
        });
        String priorOs = System.getProperty("os.name");
        Resp saved;
        Resp refused;
        Resp configRefused;
        Resp watch;
        Resp resaved;
        Resp start;
        Resp configStarted;
        boolean keptAfterRefused;
        boolean goneAfterWatch;
        boolean savedBeforeStart;
        boolean goneAfterStart;
        int[] spawns = new int[4];
        try {
            saved = post(rig, "/api/draft", "id=setup.tickRate&value=90");
            spawns[0] = rig.spawned.size();
            refused = post(rig, "/api/start", fields.replace("tickRate=60", "tickRate=x"));
            awaitStaging();
            spawns[1] = rig.spawned.size();
            keptAfterRefused = Files.isRegularFile(draft);
            configRefused = call(rig, "GET", "/api/config", rig.boot.token(), null);
            System.setProperty("os.name", "Windows 11");
            watch = post(rig, "/api/watch", fields);
            awaitStaging();
            spawns[2] = rig.spawned.size();
            goneAfterWatch = !Files.exists(draft);
            System.setProperty("os.name", priorOs);
            resaved = post(rig, "/api/draft", "id=setup.tickRate&value=90");
            savedBeforeStart = Files.isRegularFile(draft);
            start = post(rig, "/api/start", fields);
            awaitStaging();
            spawns[3] = rig.spawned.size();
            goneAfterStart = !Files.exists(draft);
            configStarted = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            System.setProperty("os.name", priorOs);
            setup(r, () -> {
                OpsSetupDraft.clear(draft);
                restoreFile(draft, prior[0]);
                Files.write(props, prior[1]);
                restoreFile(ops, prior[2]);
                restoreFile(rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES), prior[3]);
                deleteTree(install);
            });
        }
        List<String> bad = new ArrayList<>();
        if (!"{\"ok\":true}".equals(saved.body()) || !"{\"ok\":true}".equals(resaved.body()) || !savedBeforeStart) {
            bad.add("draft saves -> " + PeerText.bound(saved.body(), 60) + " / " + PeerText.bound(resaved.body(), 60)
                    + " file before the Start=" + savedBeforeStart + " (flips if the draft route stops keeping a"
                    + " lawful Setup field)");
        }
        String lead = "{\"draft\":{\"setup.tickRate\":\"90\"},\"draftUpdated\":\"";
        if (refused.body().startsWith("{\"ok\":true") || spawns[1] != spawns[0] || !keptAfterRefused
                || !configRefused.body().startsWith(lead)) {
            bad.add("refused Start " + PeerText.bound(refused.body(), 100) + " spawns " + spawns[0] + "/" + spawns[1]
                    + " file kept=" + keptAfterRefused + " config " + PeerText.bound(configRefused.body(), 80)
                    + " (flips if the clear moves before the started check - a refused Start would lose the typed"
                    + " Setup)");
        }
        if (!watch.body().startsWith("{\"ok\":true") || spawns[2] <= spawns[1] || !goneAfterWatch) {
            bad.add("started Watch " + PeerText.bound(watch.body(), 100) + " spawns " + spawns[1] + "/" + spawns[2]
                    + " file gone=" + goneAfterWatch + " (flips if a started Watch stops clearing the draft - an old"
                    + " draft would overlay the started config)");
        }
        if (!start.body().startsWith("{\"ok\":true") || spawns[3] <= spawns[2] || !goneAfterStart
                || !configStarted.body().startsWith("{\"draft\":{},\"draftUpdated\":\"\",")) {
            bad.add("started Start " + PeerText.bound(start.body(), 100) + " spawns " + spawns[2] + "/" + spawns[3]
                    + " file gone=" + goneAfterStart + " config " + PeerText.bound(configStarted.body(), 80)
                    + " (flips if the clear is dropped - an old draft would overlay a started config)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-second-folder} - the debug WATCH's second game folder
     * rides {@code POST /api/start} as the form field {@code gameRootSecond}, saved or CLEARED in
     * {@code launcher.properties} right before the spawn (the {@code web-start-gameroot} model; the status file
     * taken aside; the launcher file and the props restored in {@code finally}). A CRLF launcher file
     * {@code game.root=C:\old} + {@code keep.me=1}: a Start with {@code gameRootSecond=D:\second} appends exactly
     * that row (both old rows byte-kept); a Start WITHOUT the field (an older page, a hand-made request) leaves the
     * bytes untouched with the row present; {@code /api/config} serves the row as {@code gameRootSecond}; a Start
     * with the field BLANK removes the row (the file back to its first bytes). Flips if the wrapper is dropped,
     * uses the blank-skips writer (the row could never be cleared, so the host could never return to one window),
     * reads an absent field as blank, or rewrites more than its row.
     */
    private static String startSecondFolderRow(Rig rig) throws Exception {
        String r = "web-start-second-folder";
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path props = rig.roots.config();
        Path ops = rig.roots.opsFile();
        String fields = startFields("match-web-pkg.txt");
        byte[] first = "game.root=C:\\old\r\nkeep.me=1\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] want = "game.root=C:\\old\r\nkeep.me=1\r\ngame.root.second=D:\\second\r\n".getBytes(StandardCharsets.UTF_8);
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = takeFile(launcher);
            prior[1] = Files.readAllBytes(props);
            prior[2] = takeFile(ops); // a live status file would refuse the Start
            Files.write(launcher, first);
        });
        Resp typed;
        Resp absent;
        Resp config;
        Resp blank;
        byte[] afterTyped;
        byte[] afterAbsent;
        byte[] afterBlank;
        int[] spawns = new int[4];
        try {
            spawns[0] = rig.spawned.size();
            typed = post(rig, "/api/start", fields + "&gameRootSecond=" + enc("D:\\second"));
            awaitStaging();
            spawns[1] = rig.spawned.size();
            afterTyped = Files.readAllBytes(launcher);
            absent = post(rig, "/api/start", fields);
            awaitStaging();
            spawns[2] = rig.spawned.size();
            afterAbsent = Files.readAllBytes(launcher);
            config = call(rig, "GET", "/api/config", rig.boot.token(), null);
            blank = post(rig, "/api/start", fields + "&gameRootSecond=");
            awaitStaging();
            spawns[3] = rig.spawned.size();
            afterBlank = Files.readAllBytes(launcher);
        } finally {
            setup(r, () -> {
                restoreFile(launcher, prior[0]);
                Files.write(props, prior[1]);
                restoreFile(ops, prior[2]);
            });
        }
        List<String> bad = new ArrayList<>();
        if (!typed.body().startsWith("{\"ok\":true") || spawns[1] <= spawns[0] || !Arrays.equals(want, afterTyped)) {
            bad.add("typed second folder " + PeerText.bound(typed.body(), 100) + " spawns " + spawns[0] + "/" + spawns[1]
                    + " file '" + crlfShown(afterTyped) + "' (flips if the second-folder wrap is dropped or rewrites"
                    + " more than its row)");
        }
        if (!absent.body().startsWith("{\"ok\":true") || spawns[2] <= spawns[1] || !Arrays.equals(want, afterAbsent)) {
            bad.add("absent field " + PeerText.bound(absent.body(), 100) + " spawns " + spawns[1] + "/" + spawns[2]
                    + " file '" + crlfShown(afterAbsent) + "' (flips if an absent field is read as blank - an older"
                    + " page would drop the saved second folder)");
        }
        if (!config.body().contains("\"gameRootSecond\":\"D:\\\\second\"")) {
            bad.add("config " + PeerText.bound(config.body(), 80) + " (flips if /api/config stops serving the second"
                    + " folder row)");
        }
        if (!blank.body().startsWith("{\"ok\":true") || spawns[3] <= spawns[2] || !Arrays.equals(first, afterBlank)) {
            bad.add("blank field " + PeerText.bound(blank.body(), 100) + " spawns " + spawns[2] + "/" + spawns[3]
                    + " file '" + crlfShown(afterBlank) + "' (flips if the wrap uses the blank-skips writer - the row"
                    + " could never be cleared - or the clear touches another byte)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** File bytes for a failure line, CR / LF shown as tokens. */
    private static String crlfShown(byte[] bytes) {
        return PeerText.bound(new String(bytes, StandardCharsets.UTF_8).replace("\r", "<CR>").replace("\n", "<LF>"), 120);
    }

    /**
     * {@code web-brick} - Copy join brick
     * ({@code POST /api/brick}; {@code API_PATHS} lists it for {@code web-token-403}). A keyed GET = 405;
     * a blank {@code advertise} = {@link ServerOpsLaunch#PKG_BRICK_TYPE_FIRST}; a typed one with no status
     * file = {@link ServerOpsLaunch#PKG_BRICK_START_FIRST}, and neither refusal writes
     * {@code launcher.properties}; with a LIVE status file (this JVM as the writer, the
     * {@code verbConfirmRow} idiom) and {@code keep} over the planted {@code rig.sidecar} = the brick
     * {@code 1.2.3.4:<port>:<SECRET>}, {@code advertiseSaved:true} and the {@code advertise} row saved; a
     * port other than the live one = the port refusal; {@code keep} with the sidecar moved aside = the
     * type-a-password refusal; the teed stdout has the {@code cc-console brick} row and neither the
     * secret nor the endpoint. The status file, the launcher file and the sidecar are restored in
     * {@code finally}.
     */
    private static String brickRow(Rig rig) throws Exception {
        String r = "web-brick";
        int port = 51337;
        String endpoint = "1.2.3.4:" + port;
        Path ops = rig.roots.opsFile();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path aside = rig.root.resolve("brick-aside.password");
        byte[][] priorOps = {null};
        setup(r, () -> {
            if (Files.exists(ops)) {
                priorOps[0] = Files.readAllBytes(ops);
                Files.delete(ops);
            }
            Files.deleteIfExists(launcher);
        });
        String keep = "&passwordAction=keep&password=";
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        Resp get;
        Resp blankAdv;
        Resp noServer;
        boolean savedOnRefusal;
        Resp good;
        String saved;
        Resp wrongPort;
        Resp noSidecar;
        try {
            get = call(rig, "GET", "/api/brick", rig.boot.token(), null);
            blankAdv = post(rig, "/api/brick", "advertise=" + keep);
            noServer = post(rig, "/api/brick", "advertise=" + URLEncoder.encode(endpoint, StandardCharsets.UTF_8) + keep);
            savedOnRefusal = Files.exists(launcher);
            setup(r, () -> Files.write(ops, OpsStatusFile.composeLines(System.currentTimeMillis(),
                    ProcessHandle.current().pid(), "run", "127.0.0.1", port, 7, 1, 2, 2L, 4, 60.0, 12.5, 3.2,
                    1000L, 500L, 100L, 50L, 0, 0.0, 0L, rig.cmd.toString(), List.of(),
                    new long[0][], PhaseWindowStats.Snapshot.EMPTY), StandardCharsets.UTF_8));
            good = post(rig, "/api/brick", "advertise=" + URLEncoder.encode(endpoint, StandardCharsets.UTF_8) + keep);
            saved = ServeLauncher.readLauncherRows(launcher, prior).get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE);
            wrongPort = post(rig, "/api/brick", "advertise=" + URLEncoder.encode("1.2.3.4:" + (port + 1),
                    StandardCharsets.UTF_8) + keep);
            setup(r, () -> Files.move(rig.sidecar, aside));
            noSidecar = post(rig, "/api/brick", "advertise=" + URLEncoder.encode(endpoint, StandardCharsets.UTF_8) + keep);
        } finally {
            System.setOut(prior);
            setup(r, () -> {
                if (Files.exists(aside)) {
                    Files.move(aside, rig.sidecar);
                }
                Files.deleteIfExists(launcher);
                if (priorOps[0] != null) {
                    Files.write(ops, priorOps[0]);
                } else {
                    Files.deleteIfExists(ops);
                }
            });
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();
        if (get.code() != 405) {
            bad.add("GET -> " + get.code() + " (the route stopped refusing GET)");
        }
        if (!blankAdv.body().contains(OpsConsoleForms.jsonEscape(ServerOpsLaunch.PKG_BRICK_TYPE_FIRST))
                || !noServer.body().contains(OpsConsoleForms.jsonEscape(ServerOpsLaunch.PKG_BRICK_START_FIRST))
                || savedOnRefusal) {
            bad.add("blank " + PeerText.bound(blankAdv.body(), 60) + " | no server " + PeerText.bound(noServer.body(), 60)
                    + " | saved on refusal " + savedOnRefusal + " (the live gate or the endpoint rule broke, or a refusal saved)");
        }
        String want = "{\"ok\":true,\"brick\":\"" + endpoint + ":" + SECRET + "\",\"advertiseSaved\":true}";
        if (!want.equals(good.body())) {
            bad.add("live keep -> " + PeerText.bound(good.body().replace(SECRET, "<SECRET>"), 100)
                    + " (the keep resolve, the live gate or the answer keys changed)");
        }
        if (!endpoint.equals(saved)) {
            bad.add("launcher.properties advertise=" + saved + " (the good brick stopped saving Advertise)");
        }
        if (!wrongPort.body().contains("Advertise names port " + (port + 1)) || wrongPort.body().contains(SECRET)) {
            bad.add("wrong port -> " + PeerText.bound(wrongPort.body(), 80) + " (the port check against the live serve broke)");
        }
        if (!noSidecar.body().contains("type one into the Password field first") || noSidecar.body().contains(SECRET)) {
            bad.add("no sidecar -> " + PeerText.bound(noSidecar.body(), 80) + " (keep stopped reading the SAVED sidecar)");
        }
        if (!out.contains("cc-console brick") || out.contains(SECRET) || out.contains("1.2.3.4")) {
            bad.add("stdout row seen=" + out.contains("cc-console brick") + " secret leaked=" + out.contains(SECRET)
                    + " endpoint leaked=" + out.contains("1.2.3.4") + " (the no-secret stdout law broke)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-lease} - Fill from ProtonVPN over the REAL handler and
     * the rig's loopback gateway ({@code POST /api/lease}; {@code API_PATHS} lists it for
     * {@code web-token-403}). A keyed GET = 405; {@code bindPort=51337} with no ProtonVPN log = the
     * address-only fill JSON and the three mode rows saved; a fresh planted port line wins over the typed
     * port ({@code portFound:true}); with the gateway answering after 1 s, TWO POSTs sent together both
     * answer and the gateway saw EXACTLY one more datagram (the shared flight); a silent gateway = the N1
     * error within 5 s after one datagram; a LF in the form = refused with NO datagram; the teed stdout
     * holds the filled / address_only / refused rows with bounded reasons and never an address or a port.
     * The launcher file, the log dir, {@code silent} and {@code delayMs} are restored in {@code finally}.
     */
    private static String leaseRow(Rig rig) throws Exception {
        String r = "web-lease";
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        byte[][] priorLauncher = {null};
        setup(r, () -> priorLauncher[0] = takeFile(launcher));
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        Resp get;
        Resp addressOnly;
        Map<String, String> rows;
        Resp found;
        HttpResponse<String> one;
        HttpResponse<String> two;
        Resp silent;
        long silentMs;
        Resp form;
        int[] asked = new int[6];
        try {
            get = call(rig, "GET", "/api/lease", rig.boot.token(), null);
            asked[0] = rig.asked.get();
            addressOnly = post(rig, "/api/lease", "bindPort=51337");
            asked[1] = rig.asked.get();
            rows = ServeLauncher.readLauncherRows(launcher, prior);
            setup(r, () -> {
                Files.createDirectories(rig.leaseLogs);
                Files.writeString(rig.leaseLogs.resolve("client-logs.txt"), OffsetDateTime.ofInstant(Instant.now(),
                        ZoneOffset.UTC) + " | Port pair 1->51340\r\n", StandardCharsets.UTF_8);
            });
            found = post(rig, "/api/lease", "bindPort=40000");
            setup(r, () -> deleteTree(rig.leaseLogs));
            rig.delayMs.set(1000);
            asked[2] = rig.asked.get();
            CompletableFuture<HttpResponse<String>> first = rig.client.sendAsync(request(rig.boot.port(), "POST",
                    "/api/lease", rig.boot.token(), "bindPort=51337"), HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> second = rig.client.sendAsync(request(rig.boot.port(), "POST",
                    "/api/lease", rig.boot.token(), "bindPort=51337"), HttpResponse.BodyHandlers.ofString());
            one = first.get(30, TimeUnit.SECONDS);
            two = second.get(30, TimeUnit.SECONDS);
            asked[3] = rig.asked.get();
            rig.delayMs.set(0);
            rig.silent.set(true);
            long t0 = System.nanoTime();
            silent = post(rig, "/api/lease", "bindPort=51337");
            silentMs = (System.nanoTime() - t0) / 1_000_000L;
            asked[4] = rig.asked.get();
            form = post(rig, "/api/lease", "bindPort=%0A");
            asked[5] = rig.asked.get();
        } finally {
            System.setOut(prior);
            rig.silent.set(false);
            rig.delayMs.set(0);
            setup(r, () -> {
                if (Files.exists(rig.leaseLogs)) {
                    deleteTree(rig.leaseLogs);
                }
                restoreFile(launcher, priorLauncher[0]);
            });
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();
        if (get.code() != 405) {
            bad.add("GET -> " + get.code() + " (the route stopped refusing GET)");
        }
        String wantAddressOnly = "{\"ok\":true,\"bindHost\":\"127.0.0.1\",\"bindPort\":\"51337\",\"advertise\":"
                + "\"203.0.113.5:51337\",\"portFound\":false,\"saved\":true,\"note\":\"" + OpsConsoleForms.jsonEscape(
                String.format(Locale.ROOT, VpnLeaseProbe.NOTE_ADDRESS_ONLY, VpnLeaseProbe.WHY_NO_APP_LOG)) + "\"}";
        if (!wantAddressOnly.equals(addressOnly.body()) || asked[1] != asked[0] + 1) {
            bad.add("address-only -> " + PeerText.bound(addressOnly.body(), 160) + " asked +" + (asked[1] - asked[0])
                    + " (the fill JSON keys / values or the one-datagram pull changed)");
        }
        if (!"127.0.0.1".equals(rows.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND))
                || !"203.0.113.5:51337".equals(rows.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE))
                || !ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(rows.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM))) {
            bad.add("launcher rows " + rows + " (the fill stopped saving the mode reference)");
        }
        if (!found.body().startsWith("{\"ok\":true,\"bindHost\":\"127.0.0.1\",\"bindPort\":\"51340\",\"advertise\":"
                + "\"203.0.113.5:51340\",\"portFound\":true,\"saved\":true,")) {
            bad.add("found -> " + PeerText.bound(found.body(), 120) + " (the logged port stopped winning over the typed one)");
        }
        boolean pairOk = one.body().startsWith("{\"ok\":true,") && two.body().startsWith("{\"ok\":true,");
        if (!pairOk || asked[3] != asked[2] + 1) {
            bad.add("two POSTs together -> ok " + pairOk + ", gateway asked +" + (asked[3] - asked[2])
                    + " (flips if a second POST sends its own datagram instead of joining the flight)");
        }
        if (!OpsConsoleForms.errorJson(OpsConsole.LEASE_REFUSED, VpnLeaseProbe.N1).equals(silent.body())
                || silentMs >= 5000L || asked[4] != asked[3] + 1) {
            bad.add("silent -> " + PeerText.bound(silent.body(), 80) + " in " + silentMs + " ms, asked +"
                    + (asked[4] - asked[3]) + " (the N1 refusal, its 3 s clock or the one datagram changed)");
        }
        if (!OpsConsoleForms.errorJson(OpsConsole.LEASE_REFUSED, OpsConsoleForms.CONTROL_CHAR).equals(form.body())
                || asked[5] != asked[4]) {
            bad.add("LF form -> " + PeerText.bound(form.body(), 80) + " asked +" + (asked[5] - asked[4])
                    + " (a control char must be refused before the gateway is asked)");
        }
        for (String row : List.of("cc-console lease result=address_only reason=no_port",
                "cc-console lease result=filled reason=none", "cc-console lease result=refused reason=gateway_silent",
                "cc-console lease result=refused reason=form")) {
            if (!out.contains(row)) {
                bad.add("stdout lacks '" + row + "' (the bounded lease row changed)");
            }
        }
        for (String leak : List.of("203.0.113.5", "51337", "51340", "40000")) {
            if (out.contains(leak)) {
                bad.add("stdout carries " + leak + " (the lease row must hold no address and no port)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-lease} - Start in the ProtonVPN mode. The launcher
     * file planted with the mode rows (bind 127.0.0.1, advertise {@code 203.0.113.5:40000}); a keep Start
     * (the {@code web-start-gameroot} fields) with that bind host, port 51337 and that Advertise = in the
     * mode (a port edit stays in it): the gateway asked once, the spawn reached, the answer's {@code fill}
     * = 127.0.0.1 / 51337 / {@code 203.0.113.5:51337} and the rows re-saved with it; a Start whose
     * Advertise names {@code 1.2.3.4} = out of the mode: NO datagram and the mode row turned
     * {@code typed}; the rows re-planted + a silent gateway = a Start on the typed pair whose detail carries
     * {@link VpnLeaseProbe#NOTE_START_UNCHECKED} and one {@code result=unchecked} stdout row. The
     * STALE-EXIT refused press - the rows planted with an exit that has
     * since changed ({@code 198.51.100.9:40000}) and the kept password moved aside, a press naming that
     * stale Advertise asks once and is REFUSED inside the flow (a package needs a match password:
     * {@code password_missing}) with no {@code fill} and the rows UNTOUCHED (a save before the flow
     * started would send the next press, which re-sends the stale Advertise as a confirm re-POST does,
     * out of the mode); the retried press (password back) is still in the mode, asks once more and
     * saves the fresh fill. SETUP needs 127.0.0.1 on an up interface (the lease bind
     * check). The launcher file, the sidecar and {@code silent} are restored in {@code finally}.
     */
    private static String startLeaseRow(Rig rig) throws Exception {
        String r = "web-start-lease";
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        String modeRows = "advertise.from=protonvpn\r\nadvertise.bind=127.0.0.1\r\nadvertise=203.0.113.5:40000\r\n";
        byte[][] priorLauncher = {null};
        setup(r, () -> {
            if (!ServerOpsLaunch.localAddresses().contains("127.0.0.1")) {
                throw new IllegalStateException("127.0.0.1 is on no up interface (the lease bind check cannot pass)");
            }
            priorLauncher[0] = takeFile(launcher);
            Files.writeString(launcher, modeRows, StandardCharsets.UTF_8);
        });
        String base = "bindHost=127.0.0.1&bindPort=51337&tickRate=60&fortressDp=400&descriptor=match-web-pkg.txt"
                + "&passwordAction=keep";
        String fields = base + "&confirmed=" + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed="
                + ServerOpsCore.CONFIRM_ARMED_DOOR + "&advertise=";
        String unconfirmed = base + "&advertise="; // the stale-exit press (no confirm tokens; the refusal fires before any confirm)
        // The saved row names an exit that has since CHANGED (the responder's is 203.0.113.5); a confirm
        // answer carries no fill, so the rows must stay untouched until the flow starts
        String staleRows = "advertise.from=protonvpn\r\nadvertise.bind=127.0.0.1\r\nadvertise=198.51.100.9:40000\r\n";
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        Resp inMode;
        Resp typed;
        Resp silent;
        Resp confirmAsk;
        Resp confirmed;
        Map<String, String> afterIn;
        Map<String, String> afterTyped;
        Map<String, String> afterAsk;
        Map<String, String> afterConfirmed;
        int[] asked = new int[6];
        int spawns0;
        int spawns1;
        try {
            asked[0] = rig.asked.get();
            spawns0 = rig.spawned.size();
            inMode = post(rig, "/api/start", fields + URLEncoder.encode("203.0.113.5:40000", StandardCharsets.UTF_8));
            awaitStaging();
            asked[1] = rig.asked.get();
            spawns1 = rig.spawned.size();
            afterIn = ServeLauncher.readLauncherRows(launcher, prior);
            typed = post(rig, "/api/start", fields + URLEncoder.encode("1.2.3.4:51337", StandardCharsets.UTF_8));
            awaitStaging();
            asked[2] = rig.asked.get();
            afterTyped = ServeLauncher.readLauncherRows(launcher, prior);
            // the stale-exit REFUSED press: the press is IN the mode (the stale Advertise equals the stale saved row),
            // asks once, then the flow REFUSES it (a package needs a match password: password_missing) - a non-ok
            // answer with NO fill that must leave the rows as planted; the retried press (password back; the page
            // re-sends the same stale Advertise, as CC.send's base does on a confirm) is still in the mode, asks
            // again and saves the fresh fill - a save on the first press would have made the retry "typed"
            setup(r, () -> Files.writeString(launcher, staleRows, StandardCharsets.UTF_8));
            // the kept password moved aside for the FIRST press only (keep + no kept password = the package's
            // password_missing refusal inside the flow, AFTER the probe); restored before the retried press, which
            // is then a plain in-mode Start (the rig's password stays what the later rows expect)
            Path sidecar = ServerOpsCore.configRelative(rig.roots.config(), "match-web-pkg.txt.password");
            byte[] priorSidecar = takeFile(sidecar);
            try {
                confirmAsk = post(rig, "/api/start", unconfirmed + URLEncoder.encode("198.51.100.9:40000", StandardCharsets.UTF_8));
                awaitStaging();
            } finally {
                restoreFile(sidecar, priorSidecar);
            }
            asked[4] = rig.asked.get();
            afterAsk = ServeLauncher.readLauncherRows(launcher, prior);
            confirmed = post(rig, "/api/start", fields + URLEncoder.encode("198.51.100.9:40000", StandardCharsets.UTF_8));
            awaitStaging();
            asked[5] = rig.asked.get();
            afterConfirmed = ServeLauncher.readLauncherRows(launcher, prior);
            rig.silent.set(true);
            setup(r, () -> Files.writeString(launcher, modeRows, StandardCharsets.UTF_8));
            silent = post(rig, "/api/start", fields + URLEncoder.encode("203.0.113.5:40000", StandardCharsets.UTF_8));
            awaitStaging();
            asked[3] = rig.asked.get();
        } finally {
            System.setOut(prior);
            rig.silent.set(false);
            setup(r, () -> restoreFile(launcher, priorLauncher[0]));
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();
        if (!inMode.body().startsWith("{\"ok\":true") || asked[1] != asked[0] + 1 || spawns1 <= spawns0
                || !inMode.body().contains("\"fill\":{\"bindHost\":\"127.0.0.1\",\"bindPort\":\"51337\","
                        + "\"advertise\":\"203.0.113.5:51337\"")) {
            bad.add("in-mode Start " + PeerText.bound(inMode.body(), 160) + " asked +" + (asked[1] - asked[0])
                    + " spawns " + spawns0 + "/" + spawns1 + " (flips if the mode Start stops asking the gateway"
                    + " again or stops answering the fresh fill)");
        }
        if (!"127.0.0.1".equals(afterIn.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND))
                || !"203.0.113.5:51337".equals(afterIn.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE))
                || !ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(afterIn.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM))) {
            bad.add("rows after the mode Start " + afterIn + " (an answered re-ask stopped re-saving the fill)");
        }
        if (!typed.body().startsWith("{\"ok\":true") || asked[2] != asked[1]
                || !ServerOpsLaunch.ADVERTISE_FROM_TYPED.equals(afterTyped.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM))) {
            bad.add("edited-address Start " + PeerText.bound(typed.body(), 80) + " asked +" + (asked[2] - asked[1])
                    + " mode row " + afterTyped.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM)
                    + " (an address edit must leave the mode: no datagram, the row typed)");
        }
        if (!confirmAsk.body().startsWith("{\"error\"") || confirmAsk.body().contains("\"fill\"")
                || asked[4] != asked[2] + 1
                || !"198.51.100.9:40000".equals(afterAsk.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE))
                || !ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(afterAsk.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM))) {
            bad.add("stale-exit refused press " + PeerText.bound(confirmAsk.body(), 120) + " asked +" + (asked[4] - asked[2])
                    + " rows " + afterAsk + " (a refused or confirm answer carries no fill, so it must leave the saved rows"
                    + " untouched; a save before the flow started sends the next press, re-sending the stale Advertise,"
                    + " out of the mode)");
        }
        if (!confirmed.body().startsWith("{\"ok\":true") || !confirmed.body().contains("\"advertise\":\"203.0.113.5:51337\"")
                || asked[5] != asked[4] + 1
                || !"203.0.113.5:51337".equals(afterConfirmed.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE))
                || !ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(afterConfirmed.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM))) {
            bad.add("stale-exit retried Start " + PeerText.bound(confirmed.body(), 160) + " asked +" + (asked[5] - asked[4])
                    + " rows " + afterConfirmed + " (the retried press naming the stale exit is still in the mode:"
                    + " one ask, the fresh fill answered and saved)");
        }
        String note = OpsConsoleForms.jsonEscape(String.format(Locale.ROOT, VpnLeaseProbe.NOTE_START_UNCHECKED,
                "127.0.0.1:51337"));
        if (!silent.body().startsWith("{\"ok\":true") || !silent.body().contains(note) || asked[3] != asked[5] + 1
                || !out.contains("cc-console lease result=unchecked reason=gateway_silent press=start")) {
            bad.add("silent-gateway Start " + PeerText.bound(silent.body(), 160) + " asked +" + (asked[3] - asked[5])
                    + " (a silent gateway must start the typed pair with the unchecked note and one bounded row)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-brick-lease} - Copy
     * join brick in the ProtonVPN mode. The launcher file planted with the mode rows (bind 127.0.0.1,
     * advertise {@code 203.0.113.5:40000}) and a live status file on 127.0.0.1:51337; a keep brick with
     * that bind host and Advertise {@code 203.0.113.5:51337} = in the mode: the gateway asked once, the
     * brick {@code 203.0.113.5:51337:<secret>} with {@code fill} (the probe's tun / the port / the
     * endpoint) and the three rows re-saved to it; the live serve moved to 51338 = the PACKAGE dead-port
     * refusal naming both ports, no secret; the gateway silent (the serve back on 51337) = the typed
     * brick with {@link VpnLeaseProbe#NOTE_BRICK_UNCHECKED} as {@code note}, the mode row kept and one
     * {@code result=unchecked … press=brick} stdout row; a brick whose bind host is {@code 10.9.9.9} =
     * out of the mode: NO datagram, the brick still answered, the mode row turned {@code typed}. The
     * status file, the launcher file and {@code silent} are restored in {@code finally}.
     */
    private static String brickLeaseRow(Rig rig) throws Exception {
        String r = "web-brick-lease";
        int port = 51337;
        String endpoint = "203.0.113.5:" + port;
        Path ops = rig.roots.opsFile();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        String modeRows = "advertise.from=protonvpn\r\nadvertise.bind=127.0.0.1\r\nadvertise=203.0.113.5:40000\r\n";
        byte[][] priorOps = {null};
        byte[][] priorLauncher = {null};
        setup(r, () -> {
            priorOps[0] = takeFile(ops);
            priorLauncher[0] = takeFile(launcher);
            Files.writeString(launcher, modeRows, StandardCharsets.UTF_8);
        });
        String keep = "&passwordAction=keep&password=";
        String inModeForm = "bindHost=127.0.0.1&advertise=" + URLEncoder.encode(endpoint, StandardCharsets.UTF_8) + keep;
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        Resp answered;
        Resp movedServe;
        Resp silent;
        Resp typedHost;
        Map<String, String> afterAnswered;
        Map<String, String> afterSilent;
        Map<String, String> afterTyped;
        int[] asked = new int[5];
        try {
            asked[0] = rig.asked.get();
            setup(r, () -> Files.write(ops, liveStatus(rig, port), StandardCharsets.UTF_8));
            answered = post(rig, "/api/brick", inModeForm);
            asked[1] = rig.asked.get();
            afterAnswered = ServeLauncher.readLauncherRows(launcher, prior);
            setup(r, () -> Files.write(ops, liveStatus(rig, port + 1), StandardCharsets.UTF_8));
            movedServe = post(rig, "/api/brick", inModeForm);
            asked[2] = rig.asked.get();
            setup(r, () -> Files.write(ops, liveStatus(rig, port), StandardCharsets.UTF_8));
            rig.silent.set(true);
            silent = post(rig, "/api/brick", inModeForm);
            rig.silent.set(false);
            asked[3] = rig.asked.get();
            afterSilent = ServeLauncher.readLauncherRows(launcher, prior);
            typedHost = post(rig, "/api/brick", "bindHost=10.9.9.9&advertise="
                    + URLEncoder.encode(endpoint, StandardCharsets.UTF_8) + keep);
            asked[4] = rig.asked.get();
            afterTyped = ServeLauncher.readLauncherRows(launcher, prior);
        } finally {
            System.setOut(prior);
            rig.silent.set(false);
            setup(r, () -> {
                restoreFile(launcher, priorLauncher[0]);
                restoreFile(ops, priorOps[0]);
            });
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();
        String wantAnswered = "{\"ok\":true,\"brick\":\"" + endpoint + ":" + SECRET + "\",\"advertiseSaved\":true,"
                + "\"fill\":{\"bindHost\":\"127.0.0.1\",\"bindPort\":\"" + port + "\",\"advertise\":\"" + endpoint
                + "\",\"note\":\"\"}}";
        if (!wantAnswered.equals(answered.body()) || asked[1] != asked[0] + 1) {
            bad.add("in-mode brick " + PeerText.bound(answered.body().replace(SECRET, "<SECRET>"), 200) + " asked +"
                    + (asked[1] - asked[0]) + " (flips if the mode brick stops asking the gateway again, stops"
                    + " answering fill, or its keys change)");
        }
        if (!"127.0.0.1".equals(afterAnswered.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND))
                || !endpoint.equals(afterAnswered.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE))
                || !ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(afterAnswered.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM))) {
            bad.add("rows after the mode brick " + afterAnswered + " (an answered re-ask stopped re-saving the fill)");
        }
        if (!movedServe.body().startsWith("{\"error\":\"") || !movedServe.body().contains("ProtonVPN now forwards " + endpoint)
                || !movedServe.body().contains(String.valueOf(port + 1)) || movedServe.body().contains(SECRET)
                || asked[2] != asked[1] + 1) {
            bad.add("serve on " + (port + 1) + " -> " + PeerText.bound(movedServe.body(), 160) + " asked +"
                    + (asked[2] - asked[1]) + " (the PACKAGE dead-port check against the live serve broke)");
        }
        String wantSilent = "{\"ok\":true,\"brick\":\"" + endpoint + ":" + SECRET + "\",\"advertiseSaved\":true,\"note\":\""
                + OpsConsoleForms.jsonEscape(VpnLeaseProbe.NOTE_BRICK_UNCHECKED) + "\"}";
        if (!wantSilent.equals(silent.body()) || asked[3] != asked[2] + 1
                || !ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(afterSilent.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM))
                || !out.contains("cc-console lease result=unchecked reason=gateway_silent press=brick")) {
            bad.add("silent-gateway brick " + PeerText.bound(silent.body().replace(SECRET, "<SECRET>"), 200) + " asked +"
                    + (asked[3] - asked[2]) + " mode row " + afterSilent.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM)
                    + " (a silent gateway must brick the typed Advertise with the unchecked note, keep the mode"
                    + " row and log one bounded row)");
        }
        if (!typedHost.body().startsWith("{\"ok\":true,\"brick\":\"" + endpoint + ":") || asked[4] != asked[3]
                || !ServerOpsLaunch.ADVERTISE_FROM_TYPED.equals(afterTyped.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM))) {
            bad.add("edited-host brick " + PeerText.bound(typedHost.body().replace(SECRET, "<SECRET>"), 120) + " asked +"
                    + (asked[4] - asked[3]) + " mode row " + afterTyped.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM)
                    + " (a bind-host edit must leave the mode: no datagram, the row typed, the brick still built)");
        }
        if (out.contains(SECRET) || out.contains("203.0.113.5")) {
            bad.add("stdout secret leaked=" + out.contains(SECRET) + " address leaked=" + out.contains("203.0.113.5")
                    + " (the no-secret / no-address stdout law broke)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-join} - Join from this PC over the REAL handler
     * ({@code POST /api/join}, the rig's {@link FakeJoinEffects}; {@code os.name} pinned to Windows for the
     * legs, restored in {@code finally}). Legs: (1) {@code API_PATHS} lists the route (so web-token-403
     * covers it), a keyless POST = 403, a keyed GET = 405; (2) no {@code game.root} row = R1; (3) a staged
     * install saved as the row, no live serve = R4 and no spawn; (4) a live serve on {@code 0.0.0.0:<p>} =
     * the ARMED OPENED note dialling {@code 127.0.0.1:<p>} (the serve names a command file, so every press arms -
     * the rig's {@link FakeJoinEffects#appendCommandLine} records the line), ONE spawn of the core's fixed argv in
     * {@code <install>\mods\CC_Client} with {@code CC_JOIN_DRYRUN} dropped and {@code PATH} kept, the
     * settings file holding host + port; (5) the sidecar holding {@code pw12345678} = the file holds it,
     * while the answer, the stdout rows and the spawn env never carry it (nor the stdout / env the dial);
     * (6) a live join window (endpoint file + ping) = the web REOPENED note, ONE spawn, the settings file
     * byte-unchanged (a sentinel planted first, so any write shows); (7) a press held in its spawn + a
     * second POST = R8, then the first answers ok; (8) {@code os.name=Linux} = R9, no spawn; (9) exactly
     * one {@code cc-console join} row per press, each with {@code outcome=} and no {@code <p>}. The status
     * file, the launcher file and the sidecar are restored in {@code finally}.
     * <p>(7) also drives
     * {@link OpsConsole#pressJoinOnce} the way the debug-watch daemon does, on this thread while the POST is held:
     * its sink gets R8 and it answers R8 at once; (7b) released, the same daemon press with a diag folder opens
     * ONE window, its sink gets the OPENED sentence, and the spawn env carries the client-diagnostics key exactly
     * when the folder's characters can ride it (the key's folder made).
     */
    private static String joinRow(Rig rig) throws Exception {
        String r = "web-join";
        int port = 51341;
        String dial = "127.0.0.1:" + port;
        String pw = "pw12345678";
        String windowToken = "ab".repeat(32);
        FakeJoinEffects fx = rig.joinFx;
        Path ops = rig.roots.opsFile();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path lad = Path.of(fx.env.get("LOCALAPPDATA"));
        Path settings = lad.resolve("cc-starsector").resolve("join-settings.properties");
        Path install = rig.root.resolve("join-game");
        Path endpoint = lad.resolve("cc-starsector").resolve("join")
                .resolve("cc_join_endpoint-" + OpsJoinSelf.installTag(install) + ".data");
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            prior[0] = takeFile(ops);
            prior[1] = takeFile(launcher);
            prior[2] = Files.readAllBytes(rig.sidecar);
            Files.createDirectories(install.resolve("starsector-core"));
            Files.write(install.resolve("starsector-core").resolve("starfarer_obf.jar"), new byte[] {1});
            Files.write(install.resolve("starsector.exe"), new byte[] {1});
            Files.createDirectories(install.resolve("mods").resolve("CC_Client"));
            Files.write(install.resolve("mods").resolve("CC_Client").resolve("join-cc.bat"), new byte[] {1});
        });
        String priorOs = System.getProperty("os.name");
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int base = fx.spawns.size();
        int[] spawned = new int[7];
        Path flight = rig.root.resolve("join-watch-flight"); // the daemon's press (WATCH shape) places client under it
        Path diag = flight.resolve("client");
        AtomicReference<String> daemonHeldSink = new AtomicReference<>();
        AtomicReference<String> daemonOpenSink = new AtomicReference<>();
        String daemonHeld;
        String daemonOpened;
        Resp keyless;
        Resp keyedGet;
        Resp noRoot;
        Resp notLive;
        Resp opened;
        Resp armed;
        Resp reopened;
        Resp held;
        Resp second;
        Resp linux;
        boolean reachedSpawn;
        String settings4;
        String settings5;
        byte[] sentinel = "# web-join sentinel\r\nhost=192.0.2.9\r\nport=9\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] settings6;
        List<String> sweepRows = new ArrayList<>(); // (7c) the daemon press's recorded window, swept over the REAL Os
        int recorded;
        System.setOut(tee(priorOut, captured));
        try {
            System.setProperty("os.name", "Windows 11");
            keyless = call(rig, "POST", "/api/join", null, "");
            keyedGet = call(rig, "GET", "/api/join", rig.boot.token(), null);
            noRoot = post(rig, "/api/join", "");
            setup(r, () -> ServerOpsLaunch.writeLauncherRow(launcher, ServeLauncher.ROW_GAME_ROOT, install.toString()));
            notLive = post(rig, "/api/join", "");
            spawned[0] = fx.spawns.size() - base;
            setup(r, () -> Files.write(ops, liveStatusOn(rig, "0.0.0.0", port), StandardCharsets.UTF_8));
            opened = post(rig, "/api/join", "");
            spawned[1] = fx.spawns.size() - base;
            settings4 = Files.isRegularFile(settings) ? Files.readString(settings, StandardCharsets.ISO_8859_1) : "";
            setup(r, () -> {
                Files.writeString(rig.sidecar, "password=" + pw + "\n", StandardCharsets.UTF_8);
                Files.write(ops, liveStatusOn(rig, "0.0.0.0", port), StandardCharsets.UTF_8); // a fresh stamp
            });
            armed = post(rig, "/api/join", "");
            spawned[2] = fx.spawns.size() - base;
            settings5 = Files.isRegularFile(settings) ? Files.readString(settings, StandardCharsets.ISO_8859_1) : "";
            setup(r, () -> {
                Files.createDirectories(settings.getParent());
                Files.write(settings, sentinel);
                Files.createDirectories(endpoint.getParent());
                Files.writeString(endpoint, "46000|" + windowToken + "|0badf00d", StandardCharsets.US_ASCII);
            });
            fx.pings.put(46000, "{\"app\":\"join\"}");
            reopened = post(rig, "/api/join", "");
            spawned[3] = fx.spawns.size() - base;
            settings6 = Files.readAllBytes(settings);
            fx.pings.clear();
            setup(r, () -> {
                Files.deleteIfExists(endpoint);
                Files.write(ops, liveStatusOn(rig, "0.0.0.0", port), StandardCharsets.UTF_8); // a fresh stamp
            });
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch hold = new CountDownLatch(1);
            fx.entered = entered;
            fx.hold = hold;
            CompletableFuture<Resp> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return post(rig, "/api/join", "");
                } catch (Exception e) { // surfaces through get() as the row's own throw
                    throw new IllegalStateException(e);
                }
            });
            try {
                reachedSpawn = entered.await(20, TimeUnit.SECONDS);
                second = post(rig, "/api/join", "");
                daemonHeld = rig.boot.console().pressJoinOnce(true, flight, daemonHeldSink::set); // the daemon's press
            } finally {
                hold.countDown();
            }
            held = first.get(30, TimeUnit.SECONDS);
            fx.entered = null;
            fx.hold = null;
            spawned[4] = fx.spawns.size() - base;
            setup(r, () -> Files.write(ops, liveStatusOn(rig, "0.0.0.0", port), StandardCharsets.UTF_8)); // fresh
            JoinWindowSweep.WindowSet opened7 = new JoinWindowSweep.WindowSet(JoinWindowSweep.LIVE, sweepRows::add);
            daemonOpened = rig.boot.console().pressJoinOnce(true, flight, daemonOpenSink::set, opened7::record);
            spawned[5] = fx.spawns.size() - base;
            recorded = opened7.size();
            opened7.sweepOnce();
            System.setProperty("os.name", "Linux");
            linux = post(rig, "/api/join", "");
            spawned[6] = fx.spawns.size() - base;
        } finally {
            System.setOut(priorOut);
            System.setProperty("os.name", priorOs);
            fx.pings.clear();
            fx.entered = null;
            fx.hold = null;
            setup(r, () -> {
                restoreFile(ops, prior[0]);
                restoreFile(launcher, prior[1]);
                Files.write(rig.sidecar, prior[2]);
            });
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();
        if (!API_PATHS.contains("/api/join") || keyless.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(keyless.body())
                || keyedGet.code() != 405) {
            bad.add("(1) listed=" + API_PATHS.contains("/api/join") + " keyless POST -> " + keyless.code()
                    + " keyed GET -> " + keyedGet.code() + " (flips if the route loses the key or POST-only guard)");
        }
        String r1 = OpsConsoleForms.errorJson(OpsJoinSelf.TITLE, OpsJoinSelf.R1);
        if (!r1.equals(noRoot.body())) {
            bad.add("(2) no Game folder -> " + PeerText.bound(noRoot.body(), 160) + " (flips if R1 or the title drifts)");
        }
        if (!OpsConsoleForms.errorJson(OpsJoinSelf.TITLE, OpsJoinSelf.R4).equals(notLive.body()) || spawned[0] != 0) {
            bad.add("(3) no live serve -> " + PeerText.bound(notLive.body(), 160) + " spawns=" + spawned[0]
                    + " (flips if the live gate stops refusing R4 or a refusal spawns)");
        }
        String wantOpened = "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(
                String.format(Locale.ROOT, OpsJoinSelf.NOTE_OPENED_ADMIN, "127.0.0.1", port)) + "\"}";
        FakeJoinEffects.Spawn s4 = spawned[1] >= 1 ? fx.spawns.get(base + spawned[1] - 1) : null;
        if (!wantOpened.equals(opened.body()) || spawned[1] != 1 || s4 == null
                || !OpsJoinSelf.argv("C:\\Windows").equals(s4.argv())
                || !install.resolve("mods").resolve("CC_Client").equals(s4.cwd())
                || s4.env().containsKey("CC_JOIN_DRYRUN") || !"x".equals(s4.env().get("PATH"))
                || !settings4.contains("host=127.0.0.1\r\n") || !settings4.contains("port=" + port + "\r\n")) {
            bad.add("(4) live serve -> " + PeerText.bound(opened.body(), 160) + " spawns=" + spawned[1] + " spawn="
                    + (s4 == null ? "-" : s4.argv() + " in " + s4.cwd().getFileName() + " dryrun="
                    + s4.env().containsKey("CC_JOIN_DRYRUN")) + " settings host/port="
                    + settings4.contains("host=127.0.0.1") + "/" + settings4.contains("port=" + port)
                    + " (flips if the wildcard dial, the fixed argv / cwd, the CC_* drop or the settings write drifts, or"
                    + " a press over a live command file stops arming)");
        }
        boolean envLeak = false;
        for (FakeJoinEffects.Spawn s : fx.spawns) {
            for (String v : s.env().values()) {
                envLeak |= v.contains(pw) || v.contains(dial);
            }
        }
        if (!settings5.contains("password=" + pw + "\r\n") || !armed.body().startsWith("{\"ok\":true,")
                || armed.body().contains(pw) || out.contains(pw) || out.contains(dial) || envLeak || spawned[2] != 2) {
            bad.add("(5) armed: file password=" + settings5.contains("password=" + pw) + " answer ok="
                    + armed.body().startsWith("{\"ok\":true,") + " leak answer/stdout/env=" + armed.body().contains(pw)
                    + "/" + (out.contains(pw) || out.contains(dial)) + "/" + envLeak + " spawns=" + spawned[2]
                    + " (flips if the saved password stops arming the window or reaches an answer, a row or the env)");
        }
        String wantReopened = "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(OpsJoinSelf.NOTE_REOPENED_WEB) + "\"}";
        if (!wantReopened.equals(reopened.body()) || spawned[3] != spawned[2] + 1 || !Arrays.equals(sentinel, settings6)
                || reopened.body().contains(windowToken)) {
            bad.add("(6) live window -> " + PeerText.bound(reopened.body(), 160) + " spawns +" + (spawned[3] - spawned[2])
                    + " settings unchanged=" + Arrays.equals(sentinel, settings6) + " (flips if the web re-open writes the"
                    + " settings, skips the spawn, or sends the window's URL)");
        }
        String r8 = OpsConsoleForms.errorJson(OpsJoinSelf.TITLE, OpsJoinSelf.R8);
        if (!reachedSpawn || !r8.equals(second.body()) || !held.body().startsWith("{\"ok\":true,")
                || spawned[4] != spawned[3] + 1 || !r8.equals(daemonHeld) || !OpsJoinSelf.R8.equals(daemonHeldSink.get())) {
            bad.add("(7) held press reached spawn=" + reachedSpawn + " second -> " + PeerText.bound(second.body(), 120)
                    + " daemon press -> " + PeerText.bound(daemonHeld, 80) + " sink=" + PeerText.bound(
                    String.valueOf(daemonHeldSink.get()), 60) + " held -> " + PeerText.bound(held.body(), 80)
                    + " spawns +" + (spawned[4] - spawned[3]) + " (flips if the one-press-in-flight guard is lost, or"
                    + " the daemon's press skips it or waits)");
        }
        String wantOpenNote = String.format(Locale.ROOT, OpsJoinSelf.NOTE_OPENED_ADMIN, "127.0.0.1", port);
        FakeJoinEffects.Spawn s7 = spawned[5] == spawned[4] + 1 ? fx.spawns.get(base + spawned[5] - 1) : null;
        boolean carriable = OpsJoinSelf.DIAG_DIR_CHARS.matcher(diag.toString()).matches();
        String diagFlags = s7 == null ? null : s7.env().get(OpsJoinSelf.CLIENT_JVM_FLAGS_KEY);
        boolean diagOk = carriable
                ? diagFlags != null && diagFlags.contains(diag.toString())
                        && Files.isDirectory(diag.resolve(OpsJoinSelf.DIAG_FRAMES_DIR))
                : diagFlags == null;
        if (s7 == null || !daemonOpened.startsWith("{\"ok\":true,") || !wantOpenNote.equals(daemonOpenSink.get())
                || !diagOk) {
            bad.add("(7b) daemon press -> " + PeerText.bound(daemonOpened, 80) + " spawns +" + (spawned[5] - spawned[4])
                    + " sink=opened " + wantOpenNote.equals(daemonOpenSink.get()) + " diag key carriable=" + carriable
                    + " present=" + (diagFlags != null) + " (flips if the daemon's press loses its diag folder, its"
                    + " outcome sentence, or opens no window)");
        }
        // (7c) the recording wiring: the OPENED window reaches the daemon's set (Result.windows -> openedSink), and
        // the sweep over the real Os counts the fake's handle (this JVM at EPOCH) gone - never touched
        if (recorded != 1 || !List.of("cc-console watch sweep closed=0 gone=1 detached=0").equals(sweepRows)) {
            bad.add("(7c) daemon press recorded=" + recorded + " sweep rows=" + sweepRows + " (flips if the press drops"
                    + " the window it opened - Result.windows or openedSink.accept - or the sweep kills a handle whose"
                    + " start instant moved)");
        }
        if (!OpsConsoleForms.errorJson(OpsJoinSelf.TITLE, OpsJoinSelf.R9).equals(linux.body()) || spawned[6] != spawned[5]) {
            bad.add("(8) Linux -> " + PeerText.bound(linux.body(), 160) + " spawns +" + (spawned[6] - spawned[5])
                    + " (flips if a non-Windows console stops refusing R9 before any step)");
        }
        List<String> rows = new ArrayList<>();
        for (String line : out.split("\r?\n")) {
            if (line.startsWith("cc-console join ")) {
                rows.add(line);
            }
        }
        boolean rowsClean = rows.size() == 10; // 8 POSTs + the two daemon presses
        for (String row : rows) {
            java.util.regex.Matcher digits = java.util.regex.Pattern.compile("[0-9]+").matcher(row);
            boolean portShown = false;
            while (digits.find()) {
                portShown |= digits.group().equals(String.valueOf(port));
            }
            rowsClean &= row.contains(" outcome=") && !portShown;
        }
        if (!rowsClean) {
            bad.add("(9) " + rows.size() + " cc-console join row(s) for 10 presses, first "
                    + PeerText.bound(rows.isEmpty() ? "-" : rows.get(0), 120)
                    + " (flips if a press prints no row, two rows, or a row names the dial)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** A staged player install under {@code dir}: the core jar, the exe and the kit's bat (the join's step 1 + 2). */
    private static void stageInstall(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("starsector-core"));
        Files.write(dir.resolve("starsector-core").resolve("starfarer_obf.jar"), new byte[] {1});
        Files.write(dir.resolve("starsector.exe"), new byte[] {1});
        Files.createDirectories(dir.resolve("mods").resolve("CC_Client"));
        Files.write(dir.resolve("mods").resolve("CC_Client").resolve("join-cc.bat"), new byte[] {1});
    }

    /**
     * {@code web-join-admin-first} - through the REAL {@code POST /api/join}
     * ({@code os.name} pinned to Windows, a live serve on {@code 0.0.0.0:<p>}, its own staged install saved as the
     * {@code game.root} row, stdout teed). The status, launcher, settings and admin files are restored in
     * {@code finally}.
     *
     * <p>re-cut for the admin ARM - with an admin sidecar planted beside the
     * standing row's sidecar the window's settings file holds the MATCH password and the press receipt
     * ({@code lastPressInputs}) carries {@code adminArm} true; with it removed (a fresh stamp) the match password
     * again and {@code adminArm} STILL true (the arm follows the live command file -
     * the rig's serve names one - and reads no admin secret; leg 2 re-cut by main at the wave-1 merge). The admin
     * secret reaches nothing: the answer, a stdout row, the status JSON or the settings file. Flips if the window
     * gets the admin secret back (a pasted brick would overwrite it again) or the arm goes back to gating on a
     * kept admin secret.
     */
    private static String joinAdminFirstRow(Rig rig) throws Exception {
        String r = "web-join-admin-first";
        int port = 51343;
        String adminSecret = "admin-fixture-pass-88";
        FakeJoinEffects fx = rig.joinFx;
        Path ops = rig.roots.opsFile();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path settings = Path.of(fx.env.get("LOCALAPPDATA")).resolve(OpsJoinSelf.SETTINGS_DIR_NAME)
                .resolve(OpsJoinSelf.SETTINGS_FILE_NAME);
        Path install = rig.root.resolve("admin-first-game");
        Path admin = rig.pkg.resolve(ServerOpsCore.adminSidecarOf(rig.sidecar.getFileName().toString()));
        byte[][] prior = new byte[4][];
        setup(r, () -> {
            prior[0] = takeFile(ops);
            prior[1] = takeFile(launcher);
            prior[2] = takeFile(settings);
            prior[3] = takeFile(admin);
            stageInstall(install);
            ServerOpsLaunch.writeLauncherRow(launcher, ServeLauncher.ROW_GAME_ROOT, install.toString());
            Files.writeString(admin, "password=" + adminSecret + "\n", StandardCharsets.UTF_8);
            Files.write(ops, liveStatusOn(rig, "0.0.0.0", port), StandardCharsets.UTF_8);
        });
        String priorOs = System.getProperty("os.name");
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Resp withAdmin;
        Resp status;
        Resp matchOnly;
        String settingsAdmin;
        String settingsMatch;
        OpsJoinSelf.Inputs inAdmin;
        OpsJoinSelf.Inputs inMatch;
        AtomicReference<OpsJoinSelf.Inputs> receipt = rig.boot.console().lastPressInputs;
        System.setOut(tee(priorOut, captured));
        try {
            System.setProperty("os.name", "Windows 11");
            receipt.set(null); // an earlier row's press must never pass for this one
            withAdmin = post(rig, "/api/join", "");
            inAdmin = receipt.get();
            settingsAdmin = Files.isRegularFile(settings) ? Files.readString(settings, StandardCharsets.ISO_8859_1) : "";
            status = call(rig, "GET", "/api/status", rig.boot.token(), null);
            setup(r, () -> {
                Files.delete(admin);
                Files.write(ops, liveStatusOn(rig, "0.0.0.0", port), StandardCharsets.UTF_8); // a fresh stamp
            });
            receipt.set(null);
            matchOnly = post(rig, "/api/join", "");
            inMatch = receipt.get();
            settingsMatch = Files.isRegularFile(settings) ? Files.readString(settings, StandardCharsets.ISO_8859_1) : "";
        } finally {
            System.setOut(priorOut);
            System.setProperty("os.name", priorOs);
            setup(r, () -> {
                restoreFile(ops, prior[0]);
                restoreFile(launcher, prior[1]);
                restoreFile(settings, prior[2]);
                restoreFile(admin, prior[3]);
            });
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();
        if (!withAdmin.body().startsWith("{\"ok\":true,") || !settingsAdmin.contains("password=" + SECRET + "\r\n")
                || inAdmin == null || !inAdmin.adminArm() || !SECRET.equals(inAdmin.passwordOrBlank())) {
            bad.add("admin sidecar planted -> " + PeerText.bound(withAdmin.body(), 120) + " settings password=match "
                    + settingsAdmin.contains("password=" + SECRET) + " receipt adminArm="
                    + (inAdmin == null ? "no press" : inAdmin.adminArm()) + " (flips if the window gets the admin"
                    + " secret back - a pasted brick would overwrite it again - or the arm stops following the admin"
                    + " sidecar)");
        }
        if (!matchOnly.body().startsWith("{\"ok\":true,") || !settingsMatch.contains("password=" + SECRET + "\r\n")
                || inMatch == null || !inMatch.adminArm()) {
            bad.add("no admin sidecar -> " + PeerText.bound(matchOnly.body(), 120) + " settings password=match "
                    + settingsMatch.contains("password=" + SECRET) + " receipt adminArm="
                    + (inMatch == null ? "no press" : inMatch.adminArm()) + " (flips if the arm drops without a kept"
                    + " admin secret - every press over a live command file arms - or the window stops getting the"
                    + " match password)");
        }
        if (withAdmin.body().contains(adminSecret) || out.contains(adminSecret) || status.body().contains(adminSecret)
                || settingsAdmin.contains(adminSecret) || settingsMatch.contains(adminSecret)) {
            bad.add("the admin secret leaked: answer/stdout/status/settings = " + withAdmin.body().contains(adminSecret)
                    + "/" + out.contains(adminSecret) + "/" + status.body().contains(adminSecret) + "/"
                    + (settingsAdmin.contains(adminSecret) || settingsMatch.contains(adminSecret))
                    + " (flips if the admin secret's value leaves the console's lock: the window, a log row, an"
                    + " answer or the status)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-join-arm-recorded} - ONE press through the REAL
     * {@code POST /api/join} ({@code os.name} pinned to Windows, a live serve on {@code 0.0.0.0:<p>} naming
     * {@code rig.cmd}, a staged install saved as the {@code game.root} row, no admin sidecar, stdout teed) hands the
     * rig's recorder exactly ONE {@code admin-cid <key>} line for {@code rig.cmd}, whose key is the hex of the cid the
     * spawned window carries in its env (the arm elevates the host's OWN window), and answers the armed OPENED
     * note. The key - and the cid it encodes - reach no stdout row, the answer, the status JSON or the window's
     * settings file, and {@code rig.cmd} itself is never written by the press. The status, launcher and settings
     * files are restored in {@code finally}.
     */
    private static String joinArmRecordedRow(Rig rig) throws Exception {
        String r = "web-join-arm-recorded";
        int port = 51345;
        FakeJoinEffects fx = rig.joinFx;
        Path ops = rig.roots.opsFile();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path settings = Path.of(fx.env.get("LOCALAPPDATA")).resolve(OpsJoinSelf.SETTINGS_DIR_NAME)
                .resolve(OpsJoinSelf.SETTINGS_FILE_NAME);
        Path install = rig.root.resolve("arm-recorded-game");
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            prior[0] = takeFile(ops);
            prior[1] = takeFile(launcher);
            prior[2] = takeFile(settings);
            stageInstall(install);
            ServerOpsLaunch.writeLauncherRow(launcher, ServeLauncher.ROW_GAME_ROOT, install.toString());
            Files.write(ops, liveStatusOn(rig, "0.0.0.0", port), StandardCharsets.UTF_8);
        });
        byte[] cmdBefore = Files.exists(rig.cmd) ? Files.readAllBytes(rig.cmd) : null;
        int armBase = fx.armLines.size();
        int spawnBase = fx.spawns.size();
        String priorOs = System.getProperty("os.name");
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Resp press;
        Resp status;
        String settingsText;
        System.setOut(tee(priorOut, captured));
        try {
            System.setProperty("os.name", "Windows 11");
            press = post(rig, "/api/join", "");
            status = call(rig, "GET", "/api/status", rig.boot.token(), null);
            settingsText = Files.isRegularFile(settings) ? Files.readString(settings, StandardCharsets.ISO_8859_1) : "";
        } finally {
            System.setOut(priorOut);
            System.setProperty("os.name", priorOs);
            setup(r, () -> {
                restoreFile(ops, prior[0]);
                restoreFile(launcher, prior[1]);
                restoreFile(settings, prior[2]);
            });
        }
        byte[] cmdAfter = Files.exists(rig.cmd) ? Files.readAllBytes(rig.cmd) : null;
        List<Map.Entry<Path, String>> lines;
        synchronized (fx.armLines) {
            lines = new ArrayList<>(fx.armLines.subList(armBase, fx.armLines.size()));
        }
        FakeJoinEffects.Spawn spawn = fx.spawns.size() == spawnBase + 1 ? fx.spawns.get(spawnBase) : null;
        String cid = spawn == null ? null : spawn.env().get(OpsJoinSelf.PREDICT_CID_KEY);
        String key = cid == null || cid.isEmpty() ? null : cc.starsector.server.runtime.BanSet.cidKey(cid);
        String out = captured.toString(StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();
        if (key == null || lines.size() != 1 || !rig.cmd.equals(lines.get(0).getKey())
                || !("admin-cid " + key).equals(lines.get(0).getValue())) {
            bad.add("recorded " + lines.size() + " arm line(s) for spawns +" + (fx.spawns.size() - spawnBase)
                    + ", file=" + (lines.isEmpty() ? "-" : rig.cmd.equals(lines.get(0).getKey()))
                    + " names the window's env key=" + (key != null && !lines.isEmpty()
                            && ("admin-cid " + key).equals(lines.get(0).getValue()))
                    + " (flips if a press over a live command file stops arming, arms twice, writes another file,"
                    + " or arms a key the window it opened does not carry)");
        }
        String wantNote = "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(
                String.format(Locale.ROOT, OpsJoinSelf.NOTE_OPENED_ADMIN, "127.0.0.1", port)) + "\"}";
        int appendedRows = 0;
        int failedRows = 0;
        for (String line : out.split("\r?\n")) {
            appendedRows += line.equals("cc-ops join admin_arm=appended") ? 1 : 0;
            failedRows += line.startsWith("cc-ops join admin_arm=failed") ? 1 : 0;
        }
        if (!wantNote.equals(press.body()) || appendedRows != 1 || failedRows != 0) {
            bad.add("answer " + PeerText.bound(press.body(), 140) + " admin_arm appended/failed rows=" + appendedRows
                    + "/" + failedRows + " (flips if the armed press answers the plain note, or the arm row stops"
                    + " printing once)");
        }
        if (key != null && (out.contains(key) || out.contains(cid) || press.body().contains(key)
                || press.body().contains(cid) || status.body().contains(key) || status.body().contains(cid)
                || settingsText.contains(key) || settingsText.contains(cid))) {
            bad.add("the arm key leaked: stdout/answer/status/settings = " + (out.contains(key) || out.contains(cid))
                    + "/" + (press.body().contains(key) || press.body().contains(cid)) + "/"
                    + (status.body().contains(key) || status.body().contains(cid)) + "/"
                    + (settingsText.contains(key) || settingsText.contains(cid))
                    + " (flips if the seat key that elevates the window leaves the press: a log row, an answer, the"
                    + " status or the window's saved settings)");
        }
        if (!Arrays.equals(cmdBefore, cmdAfter)) {
            bad.add("the press changed rig.cmd (" + (cmdBefore == null ? 0 : cmdBefore.length) + " -> "
                    + (cmdAfter == null ? 0 : cmdAfter.length) + " bytes) (flips if the rig's arm seam writes the"
                    + " command file the verb rows read)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-status-armed-cidkey} - once the armed window is admitted,
     * the {@code /api/status} seats table carries that seat's cid key IN FULL as its hidden last cell, and that is
     * kept on purpose: the page sends the cell back as the Ban source form's {@code key}, which
     * {@link OpsConsoleForms} compares to the status file row's cid key and mints the confirm from, so a digest there
     * would break the press; every reader of the cell already holds the console key, which reaches the admin password
     * through {@code /api/reveal} - equal authority, nothing new. ONE press through the REAL {@code POST /api/join}
     * ({@link #joinArmRecordedRow}'s rig: Windows, a live serve on {@code 0.0.0.0:<p>}, a staged install, stdout
     * teed) arms a key; a status file is then planted whose seat 1 is the window the press opened (the serve's own
     * composer, checked to write {@code seat.1.cidkey} = the armed key), and a keyed {@code GET /api/status} must
     * answer seat 1's last cell = that key verbatim, while no stdout row of the press or the read carries any
     * 9-character run of the key or of the cid it encodes (the 8-character log law). The keyless read is
     * {@code web-token-403}'s (403 + the fixed refusal body on every /api route). Files restored in {@code finally}.
     */
    private static String statusArmedCidKeyRow(Rig rig) throws Exception {
        String r = "web-status-armed-cidkey";
        int port = 51346;
        FakeJoinEffects fx = rig.joinFx;
        Path ops = rig.roots.opsFile();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path settings = Path.of(fx.env.get("LOCALAPPDATA")).resolve(OpsJoinSelf.SETTINGS_DIR_NAME)
                .resolve(OpsJoinSelf.SETTINGS_FILE_NAME);
        Path install = rig.root.resolve("armed-cidkey-game");
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            prior[0] = takeFile(ops);
            prior[1] = takeFile(launcher);
            prior[2] = takeFile(settings);
            stageInstall(install);
            ServerOpsLaunch.writeLauncherRow(launcher, ServeLauncher.ROW_GAME_ROOT, install.toString());
            Files.write(ops, liveStatusOn(rig, "0.0.0.0", port), StandardCharsets.UTF_8);
        });
        int armBase = fx.armLines.size();
        int spawnBase = fx.spawns.size();
        String[] armed = new String[2]; // [0] = the armed key, [1] = the window's cid it encodes
        String priorOs = System.getProperty("os.name");
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Resp status;
        System.setOut(tee(priorOut, captured));
        try {
            System.setProperty("os.name", "Windows 11");
            post(rig, "/api/join", "");
            setup(r, () -> {
                List<Map.Entry<Path, String>> lines;
                synchronized (fx.armLines) {
                    lines = new ArrayList<>(fx.armLines.subList(armBase, fx.armLines.size()));
                }
                String head = "admin-cid ";
                FakeJoinEffects.Spawn spawn = fx.spawns.size() == spawnBase + 1 ? fx.spawns.get(spawnBase) : null;
                String windowCid = spawn == null ? null : spawn.env().get(OpsJoinSelf.PREDICT_CID_KEY);
                if (lines.size() != 1 || !lines.get(0).getValue().startsWith(head) || windowCid == null
                        || windowCid.isEmpty()) {
                    throw new IllegalStateException("the press armed " + lines.size() + " line(s) for "
                            + (fx.spawns.size() - spawnBase) + " window(s)");
                }
                armed[0] = lines.get(0).getValue().substring(head.length());
                armed[1] = windowCid;
                List<OpsStatusFile.SeatRow> admitted = List.of(new OpsStatusFile.SeatRow(1, windowCid,
                        "127.0.0.1:2", "wolf_Assault", true, 0L, 0L, 0, "Host"));
                Files.write(ops, OpsStatusFile.composeLines(System.currentTimeMillis(), ProcessHandle.current().pid(),
                        "run", "0.0.0.0", port, 7, 1, 2, 2L, 4, 60.0, 12.5, 3.2, 1000L, 500L, 100L, 50L, 0, 0.0, 0L,
                        rig.cmd.toString(), admitted, new long[][] {{0L, 0L}}, PhaseWindowStats.Snapshot.EMPTY),
                        StandardCharsets.UTF_8);
                List<OpsSnapshot.SeatRow> read = OpsSnapshot.parseFile(ops).seats();
                if (read.size() != 1 || read.get(0).seatId() != 1 || !armed[0].equals(read.get(0).cidKey())) {
                    throw new IllegalStateException("the planted status file's seat 1 does not carry the armed key ("
                            + read.size() + " seat row(s))");
                }
            });
            status = call(rig, "GET", "/api/status", rig.boot.token(), null);
        } finally {
            System.setOut(priorOut);
            System.setProperty("os.name", priorOs);
            setup(r, () -> {
                restoreFile(ops, prior[0]);
                restoreFile(launcher, prior[1]);
                restoreFile(settings, prior[2]);
            });
        }
        String key = armed[0];
        String out = captured.toString(StandardCharsets.UTF_8);
        String body = status.body();
        int at = body.indexOf("\"seats\":[[");
        int end = at < 0 ? -1 : body.indexOf(']', at + 10);
        String seat1 = end < 0 ? "" : body.substring(at + 9, end + 1);
        boolean rawLast = seat1.endsWith(",\"" + key + "\"]");
        List<String> bad = new ArrayList<>();
        if (status.code() != 200 || !seat1.startsWith("[1,") || !rawLast) {
            bad.add("GET /api/status -> " + status.code() + ", seat 1 row found=" + seat1.startsWith("[1,")
                    + ", its last cell is the armed key verbatim=" + rawLast + " (flips if the seats table drops,"
                    + " shortens or digests the hidden cid key cell - the page sends that cell back as the Ban source"
                    + " form's key and the console compares it to the seat's key, so the press would stop working)");
        }
        int runs = 0;
        for (String secret : List.of(key, armed[1])) {
            for (int i = 0; i + 9 <= secret.length(); i++) {
                runs += out.contains(secret.substring(i, i + 9)) ? 1 : 0;
            }
        }
        if (runs != 0) {
            bad.add(runs + " nine-character run(s) of the armed key or its cid reached stdout during the press and the"
                    + " status read (flips if a console log row prints more than the first 8 characters of a seat"
                    + " key)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-join-admin-cap-9th} - the console answers the press the
     * door's armed-key cap ({@link cc.starsector.server.runtime.PilotIntentDatagramServer#ARMED_ADMIN_CAP}) would
     * refuse. Through the REAL {@code POST /api/join} ({@link #joinArmRecordedRow}'s rig: Windows, a live serve on
     * {@code 0.0.0.0:<p>} naming {@code rig.cmd}, a staged install, stdout teed), each press over a fresh-stamped
     * status file. On ONE serve identity - a writer pid of ANOTHER live process, this JVM's parent, so the earlier
     * rows' presses (counted on this JVM's pid) never pre-fill it - presses 1..cap each hand the recorder ONE arm
     * line and answer the armed OPENED note; press cap+1 hands it NONE, still spawns its window (no env seat key),
     * answers the plain OPENED note followed by {@link OpsConsole#NOTE_ARM_CAP} within the core texts' law
     * (printable ASCII, at most 400 chars), leaves {@code adminArm} false in the press receipt and prints ONE
     * {@code withheld_cap} row; then (F-C-2) a status file naming the SAME pid with a
     * SMALLER {@code up_s} (the OS reused the pid for a restarted serve) arms the next press again, and so does
     * one naming a NEW pid (this JVM's). The status, launcher and settings files are restored in {@code finally}.
     */
    private static String joinAdminCapRow(Rig rig) throws Exception {
        String r = "web-join-admin-cap-9th";
        int port = 51347;
        int cap = cc.starsector.server.runtime.PilotIntentDatagramServer.ARMED_ADMIN_CAP;
        FakeJoinEffects fx = rig.joinFx;
        Path ops = rig.roots.opsFile();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path settings = Path.of(fx.env.get("LOCALAPPDATA")).resolve(OpsJoinSelf.SETTINGS_DIR_NAME)
                .resolve(OpsJoinSelf.SETTINGS_FILE_NAME);
        Path install = rig.root.resolve("admin-cap-game");
        long self = ProcessHandle.current().pid();
        long[] servePid = {-1L};
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            servePid[0] = ProcessHandle.current().parent()
                    .filter(h -> ServerOpsCore.writerAlive(h.pid(), System.currentTimeMillis()))
                    .map(ProcessHandle::pid)
                    .orElseThrow(() -> new IllegalStateException("no live parent process to stand in for a serve"));
            prior[0] = takeFile(ops);
            prior[1] = takeFile(launcher);
            prior[2] = takeFile(settings);
            stageInstall(install);
            ServerOpsLaunch.writeLauncherRow(launcher, ServeLauncher.ROW_GAME_ROOT, install.toString());
        });
        AtomicReference<OpsJoinSelf.Inputs> receipt = rig.boot.console().lastPressInputs;
        int presses = cap + 3; // cap armed presses, the one past the cap, the same-pid restart, then a new serve pid
        Resp[] answers = new Resp[presses];
        int[] armDelta = new int[presses];
        int[] spawnDelta = new int[presses];
        boolean[] receiptArm = new boolean[presses];
        String[] envCid = new String[presses];
        String priorOs = System.getProperty("os.name");
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(priorOut, captured));
        try {
            System.setProperty("os.name", "Windows 11");
            for (int i = 0; i < presses; i++) {
                long pid = i <= cap + 1 ? servePid[0] : self;
                // up_s rises one a press (7, 8, ...), as a live serve's does; press cap+2 = the same pid with up_s
                // back at 9 = a restart. 9 is ABOVE the first life's first reading (7), so a ledger that remembers
                // only the up_s it last reset at would miss it.
                long upSeconds = i == cap + 1 ? 9L : 7L + Math.min(i, cap);
                setup(r, () -> Files.write(ops, liveStatusPid(rig, "0.0.0.0", port, pid, upSeconds),
                        StandardCharsets.UTF_8));
                int armBase = fx.armLines.size();
                int spawnBase = fx.spawns.size();
                receipt.set(null); // an earlier press must never pass for this one
                answers[i] = post(rig, "/api/join", "");
                armDelta[i] = fx.armLines.size() - armBase;
                spawnDelta[i] = fx.spawns.size() - spawnBase;
                OpsJoinSelf.Inputs in = receipt.get();
                receiptArm[i] = in != null && in.adminArm();
                envCid[i] = spawnDelta[i] == 1 ? fx.spawns.get(spawnBase).env().get(OpsJoinSelf.PREDICT_CID_KEY) : null;
            }
        } finally {
            System.setOut(priorOut);
            System.setProperty("os.name", priorOs);
            setup(r, () -> {
                restoreFile(ops, prior[0]);
                restoreFile(launcher, prior[1]);
                restoreFile(settings, prior[2]);
            });
        }
        String wantAdmin = "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(
                String.format(Locale.ROOT, OpsJoinSelf.NOTE_OPENED_ADMIN, "127.0.0.1", port)) + "\"}";
        String capText = String.format(Locale.ROOT, OpsJoinSelf.NOTE_OPENED, "127.0.0.1", port) + " "
                + OpsConsole.NOTE_ARM_CAP;
        String wantCap = "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(capText) + "\"}";
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < cap; i++) {
            if (armDelta[i] != 1 || spawnDelta[i] != 1 || !receiptArm[i] || !wantAdmin.equals(answers[i].body())) {
                bad.add("press " + (i + 1) + " of " + cap + " on one serve: arm lines +" + armDelta[i] + " spawns +"
                        + spawnDelta[i] + " adminArm=" + receiptArm[i] + " answer "
                        + PeerText.bound(answers[i].body(), 100) + " (flips if the console withholds the arm before"
                        + " the door's cap is reached, or stops answering the armed note)");
                break;
            }
        }
        if (armDelta[cap] != 0 || spawnDelta[cap] != 1 || receiptArm[cap] || envCid[cap] != null
                || !wantCap.equals(answers[cap].body())) {
            bad.add("press " + (cap + 1) + " on the same serve: arm lines +" + armDelta[cap] + " spawns +"
                    + spawnDelta[cap] + " adminArm=" + receiptArm[cap] + " env key=" + (envCid[cap] != null)
                    + " answer " + PeerText.bound(answers[cap].body(), 120) + " (flips if the press past the door's"
                    + " cap still arms a key the door refuses, stops opening the window, or answers without the"
                    + " not-signed-in sentence)");
        }
        if (capText.length() > 400 || !capText.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f)) {
            bad.add("the capped answer is " + capText.length() + " chars (flips if the cap sentence leaves the join"
                    + " texts' law: printable ASCII, at most 400 chars)");
        }
        int restarted = cap + 1;
        if (armDelta[restarted] != 1 || spawnDelta[restarted] != 1 || !receiptArm[restarted]
                || !wantAdmin.equals(answers[restarted].body())) {
            bad.add("press on the SAME pid after up_s fell (" + (7 + cap) + " -> 9): arm lines +" + armDelta[restarted] + " adminArm="
                    + receiptArm[restarted] + " answer " + PeerText.bound(answers[restarted].body(), 100) + " (flips if"
                    + " the count outlives a same-pid restart - a serve the OS re-handed its pid would never sign the"
                    + " host's window in again; F-C-2)");
        }
        int fresh = cap + 2;
        if (armDelta[fresh] != 1 || spawnDelta[fresh] != 1 || !receiptArm[fresh]
                || !wantAdmin.equals(answers[fresh].body())) {
            bad.add("press on a NEW serve pid: arm lines +" + armDelta[fresh] + " adminArm=" + receiptArm[fresh]
                    + " answer " + PeerText.bound(answers[fresh].body(), 100) + " (flips if the count outlives its"
                    + " serve - a restarted server would never sign the host's window in again)");
        }
        int withheld = 0;
        int appended = 0;
        for (String line : captured.toString(StandardCharsets.UTF_8).split("\r?\n")) {
            withheld += line.equals("cc-console join admin_arm=withheld_cap") ? 1 : 0;
            appended += line.equals("cc-ops join admin_arm=appended") ? 1 : 0;
        }
        if (withheld != 1 || appended != cap + 2) {
            bad.add("stdout withheld_cap/appended rows=" + withheld + "/" + appended + " (flips if the withheld arm"
                    + " stops printing its one row, or prints it on an armed press)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-join-kit-stamps} - the package's kit stamps ride the press
     * inputs: two presses through the REAL {@code POST /api/join} ({@code os.name} pinned to Windows, the status
     * file taken aside), each read from the press receipt ({@code lastPressInputs}) ONLY - never the press outcome,
     * which a later kit check is free to change. The rig's stock MANIFEST (no kit rows) = all three stamps null;
     * with {@code kits/good.zip} and the {@code pack.kit} / {@code kit.jar.md5} / {@code kit.fingerprint} rows
     * planted = (the md5, the fingerprint, the resolved zip) in that order. The MANIFEST bytes, the status file and
     * {@code kits/} are restored in {@code finally}. Flips if the press drops the stamps, invents them for a
     * MANIFEST without kit rows, or swaps their order.
     */
    private static String joinKitStampsRow(Rig rig) throws Exception {
        String r = "web-join-kit-stamps";
        Path manifest = rig.pkg.resolve(ServeLauncher.MANIFEST_FILE);
        Path ops = rig.roots.opsFile();
        Path kits = KitPacker.kitsDir(rig.pkg);
        Path zip = kits.resolve("good.zip");
        String md5 = "0123456789abcdef0123456789abcdef";
        String fp = "jar=" + md5 + ";data=abc;hs=15";
        byte[][] prior = new byte[3][];
        boolean[] kitsExisted = {false};
        setup(r, () -> {
            prior[0] = Files.readAllBytes(manifest);
            prior[1] = takeFile(ops);
            kitsExisted[0] = Files.isDirectory(kits);
            prior[2] = kitsExisted[0] ? takeFile(zip) : null;
        });
        String priorOs = System.getProperty("os.name");
        AtomicReference<OpsJoinSelf.Inputs> receipt = rig.boot.console().lastPressInputs;
        OpsJoinSelf.Inputs stock;
        OpsJoinSelf.Inputs stamped;
        try {
            System.setProperty("os.name", "Windows 11");
            receipt.set(null); // an earlier row's press must never pass for this one
            post(rig, "/api/join", "");
            stock = receipt.get();
            setup(r, () -> {
                Files.createDirectories(kits);
                Files.write(zip, new byte[] {0x50, 0x4B});
                Files.writeString(manifest, "git.head=test\npack.kit=good.zip\nkit.jar.md5=" + md5 + "\nkit.fingerprint="
                        + fp + "\n", StandardCharsets.US_ASCII);
            });
            receipt.set(null);
            post(rig, "/api/join", "");
            stamped = receipt.get();
        } finally {
            System.setProperty("os.name", priorOs);
            setup(r, () -> {
                Files.write(manifest, prior[0]);
                restoreFile(ops, prior[1]);
                if (kitsExisted[0]) {
                    restoreFile(zip, prior[2]);
                } else {
                    deleteTree(kits);
                }
            });
        }
        Path wantZip = kits.toAbsolutePath().normalize().resolve("good.zip");
        List<String> bad = new ArrayList<>();
        if (stock == null || stock.kitJarMd5OrNull() != null || stock.kitFingerprintOrNull() != null
                || stock.kitZipOrNull() != null) {
            bad.add("stock MANIFEST -> " + (stock == null ? "no press receipt" : "stamps " + stock.kitJarMd5OrNull() + " / "
                    + stock.kitFingerprintOrNull() + " / " + stock.kitZipOrNull()) + " (flips if the press stops"
                    + " recording its inputs, or invents stamps for a MANIFEST without kit rows)");
        }
        if (stamped == null || !md5.equals(stamped.kitJarMd5OrNull()) || !fp.equals(stamped.kitFingerprintOrNull())
                || !wantZip.equals(stamped.kitZipOrNull())) {
            bad.add("kit MANIFEST -> " + (stamped == null ? "no press receipt" : "stamps " + stamped.kitJarMd5OrNull()
                    + " / " + stamped.kitFingerprintOrNull() + " / " + stamped.kitZipOrNull()) + " (flips if the press"
                    + " drops the package's kit stamps or swaps their order)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-watch-route} - Launch debug WATCH through the REAL
     * {@code POST /api/watch} ({@code os.name} pinned to Windows, the Start rows' fields over the rig's package
     * props, the status file taken aside): the STARTED answer + a spawn, and the props hold
     * {@code server.clientDebug=true} exactly once; the next plain {@code POST /api/start} writes it {@code false}
     * (still once). The daemon the watch arms is never asserted here (it presses only once a door opens). Flips if
     * the route's {@code debugWatch} field, the shared Start body or the flow's upsert drifts. The props and status
     * bytes are restored in {@code finally}.
     */
    private static String watchRouteRow(Rig rig) throws Exception {
        String r = "web-watch-route";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        Path ops = rig.roots.opsFile();
        String fields = startFields("match-web-pkg.txt");
        Path install = rig.root.resolve("watch-route-game"); // the watch's kit check wants a game (row web-watch-kit-check)
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = Files.readAllBytes(props);
            prior[1] = takeFile(ops); // a live status file would refuse the Start
            prior[2] = stageWatchGame(rig, install);
        });
        String priorOs = System.getProperty("os.name");
        Resp watch;
        Resp start;
        int spawns0;
        int spawns1;
        int spawns2;
        List<String> afterWatch;
        List<String> afterStart;
        try {
            System.setProperty("os.name", "Windows 11");
            spawns0 = rig.spawned.size();
            // The watch's launcher LIVES until the posture row is read: the lease daemon's
            // end leg (which writes the row false) waits on the launch's exit, so the true read is deterministic
            // - before, it raced the daemon's ~1 s poll (awaitStaging now drains that daemon, so the read moved
            // ahead of it)
            rig.spawnExit.set(null);
            watch = post(rig, "/api/watch", fields);
            afterWatch = Files.readAllLines(props, StandardCharsets.UTF_8);
            spawns1 = rig.spawned.size();
            rig.spawnExit.set(1);
            OpsPressSelftest.FakeProcess live = rig.lastSpawn.get();
            if (live != null) {
                live.exit = 1; // the launcher "dies": the staging hold ends, the daemon clears the row
            }
            awaitStaging();
            start = post(rig, "/api/start", fields);
            awaitStaging();
            spawns2 = rig.spawned.size();
            afterStart = Files.readAllLines(props, StandardCharsets.UTF_8);
        } finally {
            System.setProperty("os.name", priorOs);
            rig.spawnExit.set(1);
            setup(r, () -> {
                Files.write(props, prior[0]);
                restoreFile(ops, prior[1]);
                restoreFile(rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES), prior[2]);
                deleteTree(install);
            });
        }
        String key = OpsMatchRows.CLIENT_DEBUG_KEY + "=";
        long rowsWatch = afterWatch.stream().filter(l -> l.startsWith(key)).count();
        long rowsStart = afterStart.stream().filter(l -> l.startsWith(key)).count();
        List<String> bad = new ArrayList<>();
        if (!watch.body().startsWith("{\"ok\":true") || spawns1 <= spawns0 || rowsWatch != 1
                || !afterWatch.contains(key + "true")) {
            bad.add("watch " + PeerText.bound(watch.body(), 120) + " spawns " + spawns0 + "/" + spawns1 + " rows="
                    + rowsWatch + " true=" + afterWatch.contains(key + "true") + " (flips if the watch route stops"
                    + " starting, or its Start stops writing the debug posture row true)");
        }
        if (!start.body().startsWith("{\"ok\":true") || spawns2 <= spawns1 || rowsStart != 1
                || !afterStart.contains(key + "false")) {
            bad.add("start after " + PeerText.bound(start.body(), 120) + " spawns " + spawns1 + "/" + spawns2 + " rows="
                    + rowsStart + " false=" + afterStart.contains(key + "false") + " (flips if a plain Start keeps the"
                    + " debug posture or stops writing its row)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-watch-linux} - the same {@code POST /api/watch} with
     * {@code os.name=Linux} (the join rows' pin) answers {@link OpsJoinSelf#R9} in the join POST's shape BEFORE any
     * Start: no spawn and the props bytes unchanged (so no posture row). Flips if a non-Windows watch starts a plain
     * match or writes the row. {@code os.name} is restored in {@code finally}.
     */
    private static String watchLinuxRow(Rig rig) throws Exception {
        String r = "web-watch-linux";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        AtomicReference<byte[]> prior = new AtomicReference<>();
        setup(r, () -> {
            awaitStaging();
            prior.set(Files.readAllBytes(props));
        });
        String priorOs = System.getProperty("os.name");
        Resp linux;
        int spawns0;
        int spawns1;
        byte[] after;
        try {
            System.setProperty("os.name", "Linux");
            spawns0 = rig.spawned.size();
            linux = post(rig, "/api/watch", startFields("match-web-pkg.txt"));
            awaitStaging();
            spawns1 = rig.spawned.size();
            after = Files.readAllBytes(props);
        } finally {
            System.setProperty("os.name", priorOs);
            setup(r, () -> Files.write(props, prior.get()));
        }
        boolean r9 = OpsConsoleForms.errorJson(OpsJoinSelf.TITLE, OpsJoinSelf.R9).equals(linux.body());
        boolean same = Arrays.equals(prior.get(), after);
        return r9 && spawns1 == spawns0 && same ? null
                : "Linux watch -> " + PeerText.bound(linux.body(), 120) + " spawns " + spawns0 + "/" + spawns1
                        + " props unchanged=" + same + " (flips if a non-Windows watch stops refusing R9 before the"
                        + " Start, spawns or writes the posture row)";
    }

    /**
     * {@code web-watch-kit-check}: Launch debug WATCH runs the player-kit check BEFORE its Start. A package MANIFEST
     * whose {@code kit.jar.md5} names a kit the staged game does not hold (no kit manifest in its {@code CC_Client})
     * and no package kit zip to refresh from = the watch refused with the stale-kit sentence
     * ({@link OpsJoinSelf#R12}) under the Start's title, nothing spawned, the props bytes unchanged (no posture row);
     * the same form through the plain {@code POST /api/start} is not checked and starts. The Game folder rides the
     * form field (the one the Start would save; the launcher row stays as it was) and {@code os.name} is pinned to
     * Windows. Flips if the check runs after the spawn, stops refusing R12, a refusal spawns or writes the row, or a
     * plain Start starts checking kits. Everything staged is restored / removed in {@code finally}.
     *
     */
    private static String watchKitCheckRow(Rig rig) throws Exception {
        String r = "web-watch-kit-check";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        Path manifest = rig.pkg.resolve(ServeLauncher.MANIFEST_FILE);
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path ops = rig.roots.opsFile();
        Path install = rig.root.resolve("watch-kit-game");
        String md5 = "fedcba9876543210fedcba9876543210";
        byte[][] prior = new byte[4][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = Files.readAllBytes(props);
            prior[1] = Files.readAllBytes(manifest);
            prior[2] = takeFile(ops); // a live status file would refuse the plain Start too
            prior[3] = takeFile(launcher); // the plain Start saves the typed Game folder: kept off the rig
            Files.createDirectories(install.resolve("starsector-core"));
            Files.write(install.resolve("starsector-core").resolve("starfarer_obf.jar"), new byte[] {1});
            Files.write(install.resolve("starsector.exe"), new byte[] {1});
            Files.createDirectories(install.resolve("mods").resolve("CC_Client"));
            Files.write(install.resolve("mods").resolve("CC_Client").resolve("join-cc.bat"), new byte[] {1});
            Files.writeString(manifest, "git.head=test\nkit.jar.md5=" + md5 + "\n", StandardCharsets.US_ASCII);
        });
        String fields = startFields("match-web-pkg.txt") + "&gameRoot="
                + URLEncoder.encode(install.toString(), StandardCharsets.UTF_8);
        String priorOs = System.getProperty("os.name");
        Resp watch;
        Resp start;
        int spawns0;
        int spawns1;
        int spawns2;
        byte[] afterWatch;
        try {
            System.setProperty("os.name", "Windows 11");
            spawns0 = rig.spawned.size();
            watch = post(rig, "/api/watch", fields);
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterWatch = Files.readAllBytes(props);
            start = post(rig, "/api/start", fields);
            awaitStaging();
            spawns2 = rig.spawned.size();
        } finally {
            System.setProperty("os.name", priorOs);
            setup(r, () -> {
                Files.write(props, prior[0]);
                Files.write(manifest, prior[1]);
                restoreFile(ops, prior[2]);
                restoreFile(launcher, prior[3]);
                deleteTree(install);
            });
        }
        String staleHead = OpsJoinSelf.R12.substring(0, OpsJoinSelf.R12.indexOf('%'));
        boolean refused = watch.body().contains("\"title\":\"Start refused\"")
                && watch.body().contains(OpsConsoleForms.jsonEscape(staleHead));
        boolean same = Arrays.equals(prior[0], afterWatch);
        boolean started = start.body().startsWith("{\"ok\":true") && spawns2 > spawns1;
        return refused && spawns1 == spawns0 && same && started ? null
                : "watch -> " + PeerText.bound(watch.body(), 160) + " spawns " + spawns0 + "/" + spawns1
                        + " props unchanged=" + same + "; plain start -> " + PeerText.bound(start.body(), 80) + " spawns "
                        + spawns1 + "/" + spawns2 + " (flips if the watch's kit check stops refusing a stale kit before"
                        + " the Start, a refused watch spawns or writes the posture row, or a plain Start checks kits)";
    }

    /**
     * {@code web-watch-second-check}: Launch debug WATCH checks the second Game folder BEFORE its Start. A form whose
     * {@code gameRootSecond} names the first window's own game (the staged fake game the kit check accepts) = the
     * watch refused with {@link OpsJoinSelf#R17}'s sentence under the Start's title, nothing spawned, the props bytes
     * unchanged (no posture row); the same form with a BLANK second folder (one window) passes the check and STARTS
     * (a spawn). Between the two: the staged launcher file carrying
     * {@code watch.clientLogMaxMiB=junk} = the Start refused with {@link OpsJoinSelf#R18}'s fixed clause naming the
     * row, nothing spawned, the props bytes unchanged - the cap row parses BEFORE the Start, not first at the daemon's
     * press (a running serve, no windows); the staged bytes are put back before the blank-second post. {@code os.name}
     * pinned to Windows; the staged game, launcher, props and status bytes restored / removed in {@code finally}. Flips
     * if a check moves behind the spawn, stops refusing the first game's own folder (R16 instead = the rig's effects
     * lost {@code realPath}), a junk cap row passes the Start, a refusal spawns or writes the row, or a blank second
     * folder is refused.
     */
    private static String watchSecondCheckRow(Rig rig) throws Exception {
        String r = "web-watch-second-check";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path ops = rig.roots.opsFile();
        Path install = rig.root.resolve("watch-second-game");
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = Files.readAllBytes(props);
            prior[1] = takeFile(ops); // a live status file would refuse the Start
            prior[2] = stageWatchGame(rig, install);
        });
        String folder = URLEncoder.encode(install.toString(), StandardCharsets.UTF_8);
        String fields = startFields("match-web-pkg.txt") + "&gameRoot=" + folder;
        String priorOs = System.getProperty("os.name");
        Resp same;
        Resp junk;
        Resp blank;
        int spawns0;
        int spawns1;
        int spawnsJunk;
        int spawns2;
        byte[] afterSame;
        byte[] afterJunk;
        try {
            System.setProperty("os.name", "Windows 11");
            spawns0 = rig.spawned.size();
            same = post(rig, "/api/watch", fields + "&gameRootSecond=" + folder);
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterSame = Files.readAllBytes(props);
            byte[] staged = Files.readAllBytes(launcher);
            ServerOpsLaunch.writeLauncherRow(launcher, OpsJoinSelf.ROW_WATCH_CLIENT_LOG_MAX_MIB, "junk");
            junk = post(rig, "/api/watch", fields + "&gameRootSecond=");
            awaitStaging();
            spawnsJunk = rig.spawned.size();
            afterJunk = Files.readAllBytes(props);
            Files.write(launcher, staged); // the junk row gone before the blank-second Start below
            blank = post(rig, "/api/watch", fields + "&gameRootSecond=");
            awaitStaging();
            spawns2 = rig.spawned.size();
        } finally {
            System.setProperty("os.name", priorOs);
            setup(r, () -> {
                Files.write(props, prior[0]);
                restoreFile(ops, prior[1]);
                restoreFile(launcher, prior[2]);
                deleteTree(install);
            });
        }
        String sameHead = OpsJoinSelf.R17.substring(0, OpsJoinSelf.R17.indexOf('%'));
        boolean refused = same.body().contains("\"title\":\"Start refused\"")
                && same.body().contains(OpsConsoleForms.jsonEscape(sameHead));
        boolean unchanged = Arrays.equals(prior[0], afterSame);
        // R18's fixed clause between its row slot and the unit parenthesis: " in launcher.properties must be blank or a
        // whole number from 96 to 65536" - derived from the constant, so the row follows a reworded sentence
        String capClause = OpsJoinSelf.R18.substring(OpsJoinSelf.R18.indexOf("%1$s") + 4,
                OpsJoinSelf.R18.indexOf(" (MiB"));
        boolean capRefused = junk.body().contains("\"title\":\"Start refused\"")
                && junk.body().contains(OpsConsoleForms.jsonEscape(OpsJoinSelf.ROW_WATCH_CLIENT_LOG_MAX_MIB + capClause))
                && spawnsJunk == spawns1 && Arrays.equals(prior[0], afterJunk);
        boolean started = blank.body().startsWith("{\"ok\":true") && spawns2 > spawnsJunk;
        return refused && spawns1 == spawns0 && unchanged && capRefused && started ? null
                : "same second folder -> " + PeerText.bound(same.body(), 160) + " spawns " + spawns0 + "/" + spawns1
                        + " props unchanged=" + unchanged + "; junk cap row -> " + PeerText.bound(junk.body(), 160)
                        + " spawns " + spawns1 + "/" + spawnsJunk + " props unchanged=" + Arrays.equals(prior[0], afterJunk)
                        + "; blank second -> " + PeerText.bound(blank.body(), 80) + " spawns " + spawnsJunk + "/"
                        + spawns2 + " (flips if the watch's second-folder check stops refusing the first game's own"
                        + " folder before the Start, a junk watch.clientLogMaxMiB row passes the Start instead of"
                        + " refusing with R18 before it, a refused watch spawns or writes the posture row, or a blank"
                        + " second folder is refused)";
    }

    /**
     * {@code web-watch-note} - the status JSON's {@code watchNote} over the REAL
     * {@code GET /api/status}: a sentence with quotes (planted in the console's field, the daemon sinks' target) is
     * served escaped; a REFUSED Start ({@code rngPins=maybe}) leaves it; an accepted {@code POST /api/start} clears it,
     * so the next status carries {@code "watchNote":""}. Flips if the key, its escape or the accepted-Start clear is
     * lost. The props / status bytes and the field are restored in {@code finally}.
     */
    private static String watchNoteRow(Rig rig) throws Exception {
        String r = "web-watch-note";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        Path ops = rig.roots.opsFile();
        String fields = startFields("match-web-pkg.txt");
        String note = "a \"quoted\" note";
        String wantNote = "\"watchNote\":\"" + OpsConsoleForms.jsonEscape(note) + "\"";
        byte[][] prior = new byte[2][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = Files.readAllBytes(props);
            prior[1] = takeFile(ops); // a live status file would refuse the Start
        });
        OpsConsole console = rig.boot.console();
        String priorNote = console.watchNote.get();
        Resp planted;
        Resp refused;
        Resp afterRefused;
        Resp started;
        Resp cleared;
        try {
            console.watchNote.set(note);
            planted = call(rig, "GET", "/api/status", rig.boot.token(), null);
            refused = post(rig, "/api/start", fields + "&rngPins=maybe");
            afterRefused = call(rig, "GET", "/api/status", rig.boot.token(), null);
            started = post(rig, "/api/start", fields);
            awaitStaging();
            cleared = call(rig, "GET", "/api/status", rig.boot.token(), null);
        } finally {
            console.watchNote.set(priorNote);
            setup(r, () -> {
                Files.write(props, prior[0]);
                restoreFile(ops, prior[1]);
            });
        }
        List<String> bad = new ArrayList<>();
        if (!planted.body().contains(wantNote) || !afterRefused.body().contains(wantNote)
                || refused.body().startsWith("{\"ok\":true")) {
            bad.add("planted -> " + planted.body().contains(wantNote) + ", after a refused Start ("
                    + PeerText.bound(refused.body(), 60) + ") -> " + afterRefused.body().contains(wantNote)
                    + " (flips if the status loses the key or its escape, or a refused Start clears it)");
        }
        if (!started.body().startsWith("{\"ok\":true") || !cleared.body().contains("\"watchNote\":\"\"")) {
            bad.add("accepted Start " + PeerText.bound(started.body(), 80) + " then status carries an empty watchNote="
                    + cleared.body().contains("\"watchNote\":\"\"") + " (flips if an accepted Start stops clearing it)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** A live {@code run} status file on 127.0.0.1:{@code port} whose writer is THIS JVM ({@code web-brick}'s plant). */
    private static List<String> liveStatus(Rig rig, int port) {
        return liveStatusOn(rig, "127.0.0.1", port);
    }

    /** {@link #liveStatus} on {@code host} ({@code web-join} plants the wildcard {@code 0.0.0.0}). */
    private static List<String> liveStatusOn(Rig rig, String host, int port) {
        return liveStatusPid(rig, host, port, ProcessHandle.current().pid());
    }

    /** {@link #liveStatusOn} written by the serve {@code pid} (&gt; 0, a live process): another serve identity for
     *  the console's per-serve arm count (row {@code web-join-admin-cap-9th}).
     * */
    private static List<String> liveStatusPid(Rig rig, String host, int port, long pid) {
        return liveStatusPid(rig, host, port, pid, 7L);
    }

    /** {@link #liveStatusPid} with the serve's {@code up_s} = {@code upSeconds}: a SMALLER value under the same pid
     *  is the same-pid restart the console's arm count re-keys on (F-C-2). */
    private static List<String> liveStatusPid(Rig rig, String host, int port, long pid, long upSeconds) {
        return OpsStatusFile.composeLines(System.currentTimeMillis(), pid, "run", host,
                port, upSeconds, 1, 2, 2L, 4, 60.0, 12.5, 3.2, 1000L, 500L, 100L, 50L, 0, 0.0, 0L, rig.cmd.toString(),
                List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY);
    }

    /** The rig's loopback ProtonVPN gateway - the
     * {@code VpnLeaseSelftest} responder idiom, looped: each datagram counts into {@code asked}; unless
     * {@code silent}, it answers after {@code delayMs} with a NAT-PMP success naming 203.0.113.5. */
    private static void startGateway(Rig rig) throws IOException {
        DatagramSocket gw = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        rig.gateway = gw;
        Thread responder = new Thread(() -> {
            byte[] buf = new byte[64];
            while (!gw.isClosed()) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    gw.receive(in);
                    rig.asked.incrementAndGet();
                    if (!rig.silent.get()) {
                        Thread.sleep(rig.delayMs.get());
                        byte[] answer = {0, -128, 0, 0, 0, 0, 0x12, 0x34, (byte) 203, 0, 113, 5};
                        gw.send(new DatagramPacket(answer, answer.length, in.getSocketAddress()));
                    }
                } catch (IOException | InterruptedException stopped) { // counted; the teardown close ends the loop
                    rig.gatewayErrors.incrementAndGet();
                }
            }
        }, "cc-ops-console-selftest-gw");
        responder.setDaemon(true);
        responder.start();
    }

    /** The file's bytes (null = absent), then the file removed - a row plants its own. */
    static byte[] takeFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        Files.delete(file);
        return bytes;
    }

    /** {@link #takeFile}'s inverse: the prior bytes back, or the file removed when there were none. */
    static void restoreFile(Path file, byte[] prior) throws IOException {
        if (prior != null) {
            Files.write(file, prior);
        } else {
            Files.deleteIfExists(file);
        }
    }

    /**
     * A fake game the debug watch's kit check accepts at its install law (the exe, the engine jar, the kit bat; no
     * kit manifest - the rig's package MANIFEST carries no kit stamps, so the compare is skipped) and the launcher
     * row naming it. Returns the launcher file's prior bytes (null = none) for {@link #restoreFile}; the caller
     * removes {@code install}.
     */
    private static byte[] stageWatchGame(Rig rig, Path install) throws Exception {
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        byte[] prior = Files.isRegularFile(launcher) ? Files.readAllBytes(launcher) : null;
        Files.createDirectories(install.resolve("starsector-core"));
        Files.write(install.resolve("starsector-core").resolve("starfarer_obf.jar"), new byte[] {1});
        Files.write(install.resolve("starsector.exe"), new byte[] {1});
        Files.createDirectories(install.resolve("mods").resolve("CC_Client"));
        Files.write(install.resolve("mods").resolve("CC_Client").resolve("join-cc.bat"), new byte[] {1});
        ServerOpsLaunch.writeLauncherRow(launcher, ServeLauncher.ROW_GAME_ROOT, install.toString());
        return prior;
    }

    /**
     * The package Start's settle wait: the launcher-lock hold ({@link ServerOpsLaunch#STAGING}) AND every lease
     * daemon the press armed ({@link ServerOpsCore#WATCH_LEASE_THREAD}) are gone before the next press, read or
     * restore. WHY the daemon too: it rewrites the props (a read-modify-write under
     * {@code PROPS_LOCK}, then the join-settings scrub) up to {@link ServerOpsCore#REACH_LIVE_POLL_MS} AFTER the hold
     * clears; a row's {@code finally} restore racing that rewrite dropped the {@code match.descriptor} row once per
     * ~6 loaded runs (the FULL suite's 7 {@code password_missing} rows, lane A2's flake set) - every later
     * package Start then resolved no standing sidecar. Shared with {@code OpsConsoleOwnerSelftest}. Bounded; a
     * give-up is SAID on stdout, never silent.
     */
    static void awaitStaging() throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000L;
        while ((ServerOpsLaunch.STAGING.get() || leaseDaemonsAlive() > 0) && System.currentTimeMillis() < until) {
            Thread.sleep(20L);
        }
        if (ServerOpsLaunch.STAGING.get() || leaseDaemonsAlive() > 0) {
            System.out.println("NOTE awaitStaging gave up after 10 s: staging=" + ServerOpsLaunch.STAGING.get()
                    + " lease_daemons=" + leaseDaemonsAlive());
        }
    }

    /** Live {@link ServerOpsCore#WATCH_LEASE_THREAD} threads in this JVM (the daemon dies after its props rewrite,
     *  its stdout row and the {@code atEnd} scrub - so none alive = every late write landed). */
    static int leaseDaemonsAlive() {
        int alive = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (ServerOpsCore.WATCH_LEASE_THREAD.equals(t.getName()) && t.isAlive()) {
                alive++;
            }
        }
        return alive;
    }

    /**
     * A kick with no {@code confirmed=} answers the occupant-bound key {@code kick:<seat>:<cidKey>}
     * and writes nothing; a confirm minted for another occupant asks again; a form key naming another
     * occupant is refused; the right confirm appends the line.
     */
    private static String verbConfirmRow(Rig rig) throws Exception {
        String r = "web-verb-confirm-roundtrip";
        String[] key = {null};
        setup(r, () -> {
            List<OpsStatusFile.SeatRow> seats = List.of(
                    new OpsStatusFile.SeatRow(2, "c2", "127.0.0.1:2", "wolf_Assault", true, 0L, 0L, 0,
                            "Ann", "cc-v2-brawl", "wolf", 10, "Brawl"),
                    new OpsStatusFile.SeatRow(3, "c3", "203.0.113.9:3", "lasher_CS", true, 0L, 0L, 0, "Bob"));
            Files.write(rig.roots.opsFile(), OpsStatusFile.composeLines(System.currentTimeMillis(),
                    ProcessHandle.current().pid(), "run", "127.0.0.1", 51337, 7, 1, 2, 2L, 4, 60.0, 12.5, 3.2,
                    1000L, 500L, 100L, 50L, 0, 0.0, 0L, rig.cmd.toString(), seats,
                    new long[][] {{0L, 0L}, {0L, 0L}}, PhaseWindowStats.Snapshot.EMPTY), StandardCharsets.UTF_8);
            for (OpsSnapshot.SeatRow s : OpsSnapshot.parseFile(rig.roots.opsFile()).seats()) {
                if (s.seatId() == 2) {
                    key[0] = s.cidKey().trim().toLowerCase(Locale.ROOT);
                }
            }
            if (key[0] == null || key[0].isEmpty()) {
                throw new IllegalStateException("seat 2 has no cid key");
            }
        });
        String k2 = key[0];
        String other = "0".repeat(k2.length()).equals(k2) ? "1".repeat(k2.length()) : "0".repeat(k2.length());
        Resp ask = post(rig, "/api/verb", "verb=kick&seat=2");
        boolean silent1 = !Files.exists(rig.cmd);
        Resp stale = post(rig, "/api/verb", "verb=kick&seat=2&confirmed=kick:2:" + other);
        Resp swapped = post(rig, "/api/verb", "verb=kick&seat=2&key=" + other + "&confirmed=kick:2:" + other);
        boolean silent2 = !Files.exists(rig.cmd);
        Resp go = post(rig, "/api/verb", "verb=kick&seat=2&confirmed=kick:2:" + k2);
        String written = Files.exists(rig.cmd) ? Files.readString(rig.cmd, StandardCharsets.UTF_8) : "";
        String detail = go.body().startsWith("{\"ok\":true,\"detail\":\"") && go.body().endsWith("\"}")
                ? go.body().substring("{\"ok\":true,\"detail\":\"".length(), go.body().length() - 2) : null;
        boolean ok = ask.body().contains("\"needsConfirm\"") && ask.body().contains("\"key\":\"kick:2:" + k2 + "\"")
                && silent1 && stale.body().contains("\"needsConfirm\"") && !stale.body().contains("\"ok\"")
                && swapped.body().contains("changed hands") && silent2 && detail != null
                && detail.startsWith("kick 2") && written.equals(detail + "\n");
        return ok ? null : "ask " + PeerText.bound(ask.body(), 120) + " | stale " + PeerText.bound(stale.body(), 60)
                + " | swapped " + PeerText.bound(swapped.body(), 60) + " | go " + PeerText.bound(go.body(), 60)
                + " | silent " + silent1 + "/" + silent2 + " file " + written.length() + "ch (flips if the verb"
                + " route writes before its confirm, or a confirm stops binding the seat's occupant)";
    }

    /** Seats for the kept-verb rows - seat 2 flies {@code cc-v1-x},
     * seat 3 a stock hull (the {@code web-verb-confirm-roundtrip} composeLines idiom, a live stamp). */
    private static List<String> keptSeatLines(String commandFile) {
        List<OpsStatusFile.SeatRow> seats = List.of(
                new OpsStatusFile.SeatRow(2, "c2", "127.0.0.1:2", "wolf_Assault", true, 0L, 0L, 0,
                        "Ann", "cc-v1-x", "wolf", 10, "X"),
                new OpsStatusFile.SeatRow(3, "c3", "203.0.113.9:3", "lasher_CS", true, 0L, 0L, 0, "Bob"));
        return OpsStatusFile.composeLines(System.currentTimeMillis(), ProcessHandle.current().pid(), "run",
                "127.0.0.1", 51337, 7, 1, 2, 2L, 4, 60.0, 12.5, 3.2, 1000L, 500L, 100L, 50L, 0, 0.0, 0L,
                commandFile, seats, new long[][] {{0L, 0L}, {0L, 0L}}, PhaseWindowStats.Snapshot.EMPTY);
    }

    /**
     * Row {@code forms-custom-keep} - the three kept verbs' web
     * grammar ({@link OpsConsoleForms#verbLine} + {@link OpsConsoleForms#verbConfirm}) over decoded bodies and a
     * composed snapshot whose seat 2 flies {@code cc-v1-x}. WHY: a typed Custom id wins over the pick (the Ban
     * custom law), a blank one falls back to the picked seat's flying custom; the per-row Keep / Forget name
     * their file by {@code exportName=} / {@code stem=} and by nothing else (a body carrying both reads the
     * verb's own key); a blank name is refused with the page's sentence, a non-kept stem by the minter; every
     * confirm is server-minted from the MINTED token.
     */
    private static String formsCustomKeepRow() {
        OpsSnapshot snap = OpsSnapshot.parse(keptSeatLines("out/x/never-written-cmd.txt"));
        String[][] lines = {
            {"verb=custom-keep&id=cc-v1-typed&seat=2", "custom-keep cc-v1-typed"},
            {"verb=custom-keep&id=+cc-v1-typed+&seat=2", "custom-keep cc-v1-typed"},
            {"verb=custom-keep&id=&seat=2", "custom-keep cc-v1-x"},
            {"verb=custom-keep&seat=3", "refused: Seat 3 is flying a stock hull, not a custom."},
            {"verb=custom-keep-export&exportName=kept-my-wolf-1a2b3c", "custom-keep-export kept-my-wolf-1a2b3c"},
            {"verb=custom-keep-export&exportName=cc-v1-assault", "custom-keep-export cc-v1-assault"},
            {"verb=custom-keep-export&stem=kept-a-1a2b3c&exportName=cc-v1-assault",
                "custom-keep-export cc-v1-assault"},
            {"verb=custom-keep-export&exportName=", "refused: custom-keep-export: pick an exported ship first"},
            {"verb=custom-keep-export", "refused: custom-keep-export: pick an exported ship first"},
            {"verb=custom-forget&stem=kept-a-1a2b3c", "custom-forget kept-a-1a2b3c"},
            {"verb=custom-forget&exportName=kept-b-000000&stem=kept-a-1a2b3c", "custom-forget kept-a-1a2b3c"},
            {"verb=custom-forget&stem=+", "refused: custom-forget: pick a kept ship first"},
            {"verb=custom-forget", "refused: custom-forget: pick a kept ship first"},
        };
        List<String> bad = new ArrayList<>();
        for (String[] w : lines) {
            String got;
            try {
                got = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(w[0]), snap);
            } catch (IllegalArgumentException refused) {
                got = "refused: " + refused.getMessage();
            }
            if (!w[1].equals(got)) {
                bad.add(w[0] + " -> '" + PeerText.bound(got, 80) + "' want '" + w[1] + "'");
            }
        }
        for (String body : List.of("verb=custom-forget&stem=cc-v1-x", "verb=custom-keep-export&exportName=wolf_Assault",
                "verb=custom-keep&id=bad+id!")) {
            try {
                bad.add(body + " minted '" + OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(body), snap)
                        + "' - want refused");
            } catch (IllegalArgumentException refused) { // the minter's refusal is this check's pass
                if (refused.getMessage() == null || refused.getMessage().isBlank()) {
                    bad.add(body + " refused with no sentence");
                }
            }
        }
        String keepTail = "' for every player? It joins the /ship list now and at every start, until you press Forget kept.";
        String[][] confirms = {
            {"verb=custom-keep&id=cc-v1-typed&seat=2", "custom-keep:cc-v1-typed", "Keep for everyone",
                "Keep 'cc-v1-typed" + keepTail},
            {"verb=custom-keep&seat=2", "custom-keep:cc-v1-x", "Keep for everyone", "Keep 'cc-v1-x" + keepTail},
            {"verb=custom-keep-export&exportName=kept-my-wolf-1a2b3c", "custom-keep-export:kept-my-wolf-1a2b3c",
                "Keep for everyone", "Keep 'kept-my-wolf-1a2b3c" + keepTail},
            {"verb=custom-forget&stem=kept-a-1a2b3c", "custom-forget:kept-a-1a2b3c", "Forget kept",
                "Forget 'kept-a-1a2b3c'? It leaves /ship now; its file moves back to variants-inbox."},
        };
        for (String[] c : confirms) {
            OpsConsoleForms.ConfirmSpec spec = OpsConsoleForms.verbConfirm(OpsConsoleForms.decodeForm(c[0]), snap, 0L);
            if (spec == null || !c[1].equals(spec.key()) || !c[2].equals(spec.title()) || !c[3].equals(spec.message())) {
                bad.add("confirm " + c[0] + " -> " + (spec == null ? "none" : spec.key() + " / " + spec.title() + " / "
                        + PeerText.bound(spec.message(), 60)));
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad) + " (flips if a kept verb's"
                + " arm reads the wrong form key, stops preferring the typed id, drops a refusal sentence, or its"
                + " confirm stops naming the minted token)";
    }

    /**
     * Row {@code api-verb-keep} - the REAL keyed {@code POST /api/verb}
     * route over the rig's loopback console (the {@code web-verb-confirm-roundtrip} idiom, the ops file re-staged
     * live). WHY: Keep for everyone and Forget kept reach the server only through the one verb route: an unconfirmed
     * ask answers the server-minted confirm and writes NOTHING, the confirmed key appends exactly the minted line,
     * a keyless POST is 403 and a keyed GET 405.
     */
    private static String apiVerbKeepRow(Rig rig) throws Exception {
        String r = "api-verb-keep";
        setup(r, () -> Files.write(rig.roots.opsFile(), keptSeatLines(rig.cmd.toString()), StandardCharsets.UTF_8));
        String before = Files.exists(rig.cmd) ? Files.readString(rig.cmd, StandardCharsets.UTF_8) : "";
        Resp ask = post(rig, "/api/verb", "verb=custom-keep&id=cc-v1-x");
        Resp forget = post(rig, "/api/verb", "verb=custom-forget&stem=kept-a-1a2b3c");
        Resp keyless = call(rig, "POST", "/api/verb", null, "verb=custom-keep&id=cc-v1-x");
        Resp get = call(rig, "GET", "/api/verb", rig.boot.token(), null);
        String mid = Files.exists(rig.cmd) ? Files.readString(rig.cmd, StandardCharsets.UTF_8) : "";
        Resp go = post(rig, "/api/verb", "verb=custom-keep&id=cc-v1-x&confirmed=custom-keep:cc-v1-x");
        String after = Files.exists(rig.cmd) ? Files.readString(rig.cmd, StandardCharsets.UTF_8) : "";
        String keepAsk = OpsConsoleForms.needsConfirmJson(List.of(new OpsConsoleForms.ConfirmSpec(
                "custom-keep:cc-v1-x", "Keep for everyone", "Keep 'cc-v1-x' for every player? It joins the /ship"
                        + " list now and at every start, until you press Forget kept.")));
        String forgetAsk = OpsConsoleForms.needsConfirmJson(List.of(new OpsConsoleForms.ConfirmSpec(
                "custom-forget:kept-a-1a2b3c", "Forget kept",
                "Forget 'kept-a-1a2b3c'? It leaves /ship now; its file moves back to variants-inbox.")));
        boolean ok = ask.code() == 200 && keepAsk.equals(ask.body()) && forget.code() == 200
                && forgetAsk.equals(forget.body()) && keyless.code() == 403
                && OpsConsole.REFUSED_TOKEN.equals(keyless.body()) && get.code() == 405 && mid.equals(before)
                && go.code() == 200 && OpsConsoleForms.okJson("custom-keep cc-v1-x").equals(go.body())
                && after.equals(before + "custom-keep cc-v1-x\n");
        return ok ? null : "ask " + ask.code() + " " + PeerText.bound(ask.body(), 100) + " | forget " + forget.code() + " "
                + PeerText.bound(forget.body(), 80) + " | keyless " + keyless.code() + " | GET " + get.code()
                + " | wrote before the confirm " + !mid.equals(before) + " | go " + go.code() + " "
                + PeerText.bound(go.body(), 60) + " appended " + (after.length() - before.length()) + "ch (flips if the"
                + " verb route stops minting the kept confirm, writes before it, appends another line, or a kept verb"
                + " slips past the key / method gates)";
    }

    /** The rig's command file as text ("" while absent). */
    private static String cmdText(Rig rig) throws IOException {
        return Files.exists(rig.cmd) ? Files.readString(rig.cmd, StandardCharsets.UTF_8) : "";
    }

    /**
     * Row {@code forms-ship-hide} - the page's per-row Hide / Show under
     * "Ships that come with the server" through the REAL keyed {@code POST /api/verb} (the {@code api-verb-keep} idiom,
     * a live status re-staged). WHY: Hide reaches the server only after the host answers the server-minted confirm
     * (an unconfirmed ask, or a confirm minted for ANOTHER row's id, writes nothing); the confirmed key appends exactly
     * {@code ship-hide <id>}; Show asks nothing and appends at once; a custom {@code cc-v} id, a blank id or a token
     * outside the shipped-stem law is answered with the minter's (or the page's pick-first) sentence and writes
     * nothing, even when the body carries a matching {@code confirmed=}.
     */
    private static String formsShipHideRow(Rig rig) throws Exception {
        String r = "forms-ship-hide";
        setup(r, () -> Files.write(rig.roots.opsFile(), keptSeatLines(rig.cmd.toString()), StandardCharsets.UTF_8));
        String before = cmdText(rig);
        Resp ask = post(rig, "/api/verb", "verb=ship-hide&id=wolf_Assault");
        Resp crossed = post(rig, "/api/verb", "verb=ship-hide&id=astral_Flash&confirmed=ship-hide:wolf_Assault");
        String afterAsk = cmdText(rig);
        Resp hide = post(rig, "/api/verb", "verb=ship-hide&id=wolf_Assault&confirmed=ship-hide:wolf_Assault");
        String afterHide = cmdText(rig);
        Resp show = post(rig, "/api/verb", "verb=ship-show&id=astral_Flash");
        String afterShow = cmdText(rig);
        String[][] refused = {
            {"verb=ship-hide&id=cc-v1-assault&confirmed=ship-hide:cc-v1-assault", refusalOf(
                    () -> ServerOpsCore.shipHideLine("cc-v1-assault"))},
            {"verb=ship-show&id=CC-V1-assault", refusalOf(() -> ServerOpsCore.shipShowLine("CC-V1-assault"))},
            {"verb=ship-hide&id=wolf%3AAssault&confirmed=ship-hide:wolf:Assault", refusalOf(
                    () -> ServerOpsCore.shipHideLine("wolf:Assault"))},
            {"verb=ship-show&id=bad%20id%21", refusalOf(() -> ServerOpsCore.shipShowLine("bad id!"))},
            {"verb=ship-hide&id=+&confirmed=ship-hide:", "ship-hide: pick a ship that comes with the server first"},
            {"verb=ship-show", "ship-show: pick a ship that comes with the server first"}};
        List<String> bad = new ArrayList<>();
        for (String[] c : refused) {
            Resp got = post(rig, "/api/verb", c[0]);
            if (c[1] == null || got.code() != 200 || !OpsConsoleForms.errorJson("Verb refused", c[1]).equals(got.body())) {
                bad.add(c[0] + " -> " + got.code() + " " + PeerText.bound(got.body(), 100) + " want the sentence '"
                        + PeerText.bound(String.valueOf(c[1]), 60) + "'");
            }
        }
        String afterRefused = cmdText(rig);
        String hideAsk = OpsConsoleForms.needsConfirmJson(List.of(new OpsConsoleForms.ConfirmSpec(
                "ship-hide:wolf_Assault", ServerOpsCore.SHIP_HIDE_TITLE, ServerOpsCore.shipHideConfirmText("wolf_Assault"))));
        String crossedAsk = OpsConsoleForms.needsConfirmJson(List.of(new OpsConsoleForms.ConfirmSpec(
                "ship-hide:astral_Flash", ServerOpsCore.SHIP_HIDE_TITLE, ServerOpsCore.shipHideConfirmText("astral_Flash"))));
        if (ask.code() != 200 || !hideAsk.equals(ask.body()) || !crossedAsk.equals(crossed.body())
                || !afterAsk.equals(before)) {
            bad.add("ask " + ask.code() + " " + PeerText.bound(ask.body(), 90) + " | crossed " + PeerText.bound(crossed.body(),
                    60) + " | wrote before the confirm " + !afterAsk.equals(before));
        }
        if (!OpsConsoleForms.okJson("ship-hide wolf_Assault").equals(hide.body())
                || !afterHide.equals(before + "ship-hide wolf_Assault\n")) {
            bad.add("confirmed hide " + PeerText.bound(hide.body(), 60) + " appended '"
                    + PeerText.bound(afterHide.substring(Math.min(before.length(), afterHide.length())), 60) + "'");
        }
        if (!OpsConsoleForms.okJson("ship-show astral_Flash").equals(show.body())
                || !afterShow.equals(afterHide + "ship-show astral_Flash\n")) {
            bad.add("show " + PeerText.bound(show.body(), 60) + " appended '"
                    + PeerText.bound(afterShow.substring(Math.min(afterHide.length(), afterShow.length())), 60) + "'");
        }
        if (!afterRefused.equals(afterShow)) {
            bad.add("a refused body wrote " + (afterRefused.length() - afterShow.length()) + "ch");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad) + " (flips if Hide stops asking"
                + " first, a confirm minted for one row passes another's Hide, the route appends another line, Show"
                + " gains a dialog, or a custom / blank / unlawful id is minted or answered with other text)";
    }

    /** The IllegalArgumentException text {@code minter} throws, or null when it mints. */
    private static String refusalOf(Runnable minter) {
        try {
            minter.run();
        } catch (IllegalArgumentException refused) { // the text IS the caller's expected answer
            return refused.getMessage();
        }
        return null;
    }

    /**
     * Row {@code api-verb-ship-hide} - the REAL {@code /api/verb} route and
     * the REAL {@code /api/status} answer over the rig's loopback console. WHY: (1) the ship verbs ride the one verb
     * route's gates (a keyless POST = 403, a keyed GET = 405, both writing nothing) and a confirmed Hide appends
     * byte-for-byte {@link ServerOpsCore#shipHideLine}'s line; (2) the five shipped-ships status rows planted in the
     * followed status file reach the page through {@link OpsSnapshot} into {@code statusJson} as the server wrote them
     * (ids in order and case, the flag, the escaped verdict, the failed-save count), and a status file without them
     * answers the empty five (the optional-key law: a pre-ships server still reads).
     */
    private static String apiVerbShipHideRow(Rig rig) throws Exception {
        String r = "api-verb-ship-hide";
        List<String> planted = new ArrayList<>(keptSeatLines(rig.cmd.toString()));
        planted.addAll(List.of("ships.base_ids=wolf_Assault,astral_Flash,Doom_antimatter-srm",
                "ships.hidden_ids=astral_Flash", "ships.over_cap=1", "ships.last=hid astral_Flash \"for everyone\"",
                "ships.persist_failed=3"));
        setup(r, () -> Files.write(rig.roots.opsFile(), planted, StandardCharsets.UTF_8));
        String before = cmdText(rig);
        Resp keyless = call(rig, "POST", "/api/verb", null,
                "verb=ship-hide&id=Doom_antimatter-srm&confirmed=ship-hide:Doom_antimatter-srm");
        Resp get = call(rig, "GET", "/api/verb", rig.boot.token(), null);
        String mid = cmdText(rig);
        Resp go = post(rig, "/api/verb", "verb=ship-hide&id=Doom_antimatter-srm&confirmed=ship-hide:Doom_antimatter-srm");
        String after = cmdText(rig);
        Resp status = call(rig, "GET", "/api/status", rig.boot.token(), null);
        setup(r, () -> Files.write(rig.roots.opsFile(), keptSeatLines(rig.cmd.toString()), StandardCharsets.UTF_8));
        Resp preShips = call(rig, "GET", "/api/status", rig.boot.token(), null);
        String line = ServerOpsCore.shipHideLine("Doom_antimatter-srm");
        String fiveKeys = "\"shipsBaseIds\":[\"wolf_Assault\",\"astral_Flash\",\"Doom_antimatter-srm\"],"
                + "\"shipsHiddenIds\":[\"astral_Flash\"],\"shipsOverCap\":true,"
                + "\"shipsLast\":\"hid astral_Flash \\\"for everyone\\\"\",\"shipsPersistFailed\":3";
        String emptyFive = "\"shipsBaseIds\":[],\"shipsHiddenIds\":[],\"shipsOverCap\":false,\"shipsLast\":\"-\","
                + "\"shipsPersistFailed\":0";
        List<String> bad = new ArrayList<>();
        if (keyless.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(keyless.body()) || get.code() != 405
                || !mid.equals(before)) {
            bad.add("(1) keyless " + keyless.code() + " | GET " + get.code() + " | wrote " + !mid.equals(before)
                    + " (flips if a ship verb slips past the key / method gates)");
        }
        if (go.code() != 200 || !OpsConsoleForms.okJson(line).equals(go.body()) || !after.equals(before + line + "\n")) {
            bad.add("(1) confirmed hide " + go.code() + " " + PeerText.bound(go.body(), 80) + " appended "
                    + (after.length() - before.length()) + "ch (flips if the route appends other bytes than the"
                    + " minter's line)");
        }
        if (status.code() != 200 || !status.body().contains(fiveKeys)) {
            int at = status.body().indexOf("\"shipsBaseIds\"");
            bad.add("(2) status " + status.code() + " ships part "
                    + PeerText.bound(at < 0 ? "<absent>" : status.body().substring(at), 220) + " (flips if a ships"
                    + " row is dropped, renamed, read from another key, re-sorted / folded on the way, or the verdict"
                    + " reaches the JSON unescaped)");
        }
        if (preShips.code() != 200 || !preShips.body().contains(emptyFive)) {
            int at = preShips.body().indexOf("\"shipsBaseIds\"");
            bad.add("(2) pre-ships status " + preShips.code() + " ships part "
                    + PeerText.bound(at < 0 ? "<absent>" : preShips.body().substring(at), 160) + " (flips if a status"
                    + " file without the ships rows stops reading as the empty five)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** Kill asks Swing's confirm (key {@code kill}, naming the live writer's pid) before the order runs. */
    private static String killConfirmRow(Rig rig) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        rig.killMode.set((o, w, l) -> {
            calls.incrementAndGet();
            return outcome("stop written (selftest)");
        });
        Resp ask = post(rig, "/api/kill", "");
        int callsAfterAsk = calls.get();
        Resp go = post(rig, "/api/kill", "confirmed=kill");
        boolean ok = ask.body().contains("\"key\":\"kill\"")
                && ask.body().contains("Stop server pid " + ProcessHandle.current().pid()) && callsAfterAsk == 0
                && go.body().startsWith("{\"ok\":true") && go.body().contains("stop written (selftest)")
                && calls.get() == 1;
        return ok ? null : "ask " + PeerText.bound(ask.body(), 120) + " calls " + callsAfterAsk + "/" + calls.get()
                + " go " + PeerText.bound(go.body(), 80) + " (flips if Kill runs without its confirm, or the"
                + " writerPid gate stops naming the live writer)";
    }

    /** Close ops GUI: the ask round answers the {@code quit} confirm (nothing run, the running card
     *  kept); the confirmed press removes the card, answers ok with {@link OpsConsole#QUIT_DETAIL} and runs the
     *  hook ONCE. */
    private static String quitConfirmRow(Rig rig) throws Exception {
        Path card = OpsConsole.writeRunning(rig.pkg, new OpsConsole.Running(ProcessHandle.current().pid(),
                rig.boot.port(), "2026-10-27 11:15", "20261008-120000"));
        int before = rig.quits.get();
        Resp ask = post(rig, "/api/quit", "");
        boolean cardKept = Files.isRegularFile(card);
        int afterAsk = rig.quits.get();
        Resp go = post(rig, "/api/quit", "confirmed=" + OpsConsole.QUIT_KEY);
        boolean cardGone = !Files.exists(card);
        boolean ok = ask.body().contains("\"key\":\"" + OpsConsole.QUIT_KEY + "\"")
                && ask.body().contains(OpsConsoleForms.jsonEscape(OpsConsole.QUIT_TITLE)) && afterAsk == before
                && cardKept && go.body().startsWith("{\"ok\":true") && go.body().contains(OpsConsole.QUIT_DETAIL)
                && rig.quits.get() == before + 1 && cardGone;
        return ok ? null : "ask " + PeerText.bound(ask.body(), 120) + " hook " + (afterAsk - before) + "/"
                + (rig.quits.get() - before) + " card kept=" + cardKept + " gone=" + cardGone + " go "
                + PeerText.bound(go.body(), 80) + " (flips if Close runs without its confirm, leaves the card, or"
                + " the hook fires twice or not at all)";
    }

    /** Two concurrent Kills: the second answers {@value OpsConsole#KILL_RUNNING}; the first's
     * result follows; the flag is released for the next. */
    private static String killSingleFlightRow(Rig rig) throws Exception {
        String r = "web-kill-single-flight";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rig.killMode.set((o, w, l) -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) { // the row's later checks then fail by name
                Thread.currentThread().interrupt();
                System.out.println(r + ": kill fake interrupted");
            }
            return outcome("first kill done");
        });
        CompletableFuture<HttpResponse<String>> first = rig.client.sendAsync(
                request(rig.boot.port(), "POST", "/api/kill", rig.boot.token(), "confirmed=kill"),
                HttpResponse.BodyHandlers.ofString());
        setup(r, () -> {
            if (!entered.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the first Kill never reached the order");
            }
        });
        Resp second;
        try {
            second = post(rig, "/api/kill", "confirmed=kill");
        } finally {
            release.countDown();
        }
        HttpResponse<String> firstDone = first.get(30, TimeUnit.SECONDS);
        rig.killMode.set((o, w, l) -> outcome("third kill done"));
        Resp third = post(rig, "/api/kill", "confirmed=kill");
        boolean ok = second.body().contains(OpsConsole.KILL_RUNNING) && firstDone.body().contains("first kill done")
                && third.body().contains("third kill done");
        return ok ? null : "second " + PeerText.bound(second.body(), 100) + " | first "
                + PeerText.bound(firstDone.body(), 60) + " | third " + PeerText.bound(third.body(), 60)
                + " (flips if Kill stops being single-flight, or a finished Kill keeps the flag)";
    }

    /** A handler throw answers the FIXED 500 body (no class name, no message) and frees the Kill flag. */
    private static String fixed500Row(Rig rig) throws Exception {
        rig.killMode.set((o, w, l) -> {
            throw new IllegalStateException("boom-detail " + rig.root);
        });
        Resp boom = post(rig, "/api/kill", "confirmed=kill");
        rig.killMode.set((o, w, l) -> outcome("after the 500"));
        Resp after = post(rig, "/api/kill", "confirmed=kill");
        boolean ok = boom.code() == 500 && OpsConsole.INTERNAL_ERROR.equals(boom.body())
                && !boom.body().contains("IllegalStateException") && !boom.body().contains("boom")
                && after.body().contains("after the 500");
        return ok ? null : "500? " + boom.code() + " body " + PeerText.bound(boom.body(), 80) + " | after "
                + PeerText.bound(after.body(), 60) + " (flips if a handler throw echoes its exception, or the"
                + " Kill flag is not released in finally)";
    }

    /** Stdout teed into {@code captured} (the handler
     * threads print through {@code System.out}, so a row reads the console's own rows); the caller
     * restores {@code prior} in {@code finally}. */
    private static PrintStream tee(PrintStream prior, ByteArrayOutputStream captured) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                prior.write(b);
                captured.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                prior.write(b, off, len);
                captured.write(b, off, len);
            }
        }, true, StandardCharsets.UTF_8);
    }

    /**
     * The Kill outcome token. A terminal receipt row =
     * {@code {"ok":true,"outcome":"receipt","detail":<text>}} exactly; a killed life (no receipt) =
     * {@code escalated}; neither = {@code no_receipt}; {@link ServerOpsKill#NOTHING_TO_STOP} = the
     * "Kill refused" error with no {@code outcome}; stdout carries {@code cc-console kill outcome=receipt}.
     */
    private static String killOutcomeRow(Rig rig) throws Exception {
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        Resp receipt;
        Resp escalated;
        Resp noReceipt;
        Resp nothing;
        try {
            rig.killMode.set((o, w, l) -> new ServerOpsKill.Outcome(true, false, "stopped=true lives=1", List.of(),
                    false, "stopped with a receipt (selftest)"));
            receipt = post(rig, "/api/kill", "confirmed=kill");
            rig.killMode.set((o, w, l) -> new ServerOpsKill.Outcome(true, true, null, List.of(1234L), false,
                    "lives ended (selftest)"));
            escalated = post(rig, "/api/kill", "confirmed=kill");
            rig.killMode.set((o, w, l) -> outcome("stop written, no receipt (selftest)"));
            noReceipt = post(rig, "/api/kill", "confirmed=kill");
            rig.killMode.set((o, w, l) -> new ServerOpsKill.Outcome(false, false, null, List.of(), false,
                    ServerOpsKill.NOTHING_TO_STOP));
            nothing = post(rig, "/api/kill", "confirmed=kill");
        } finally {
            System.setOut(prior);
            rig.killMode.set((o, w, l) -> outcome("stop file written (selftest)"));
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        boolean ok = "{\"ok\":true,\"outcome\":\"receipt\",\"detail\":\"stopped with a receipt (selftest)\"}"
                .equals(receipt.body())
                && escalated.body().contains("\"outcome\":\"escalated\"")
                && noReceipt.body().contains("\"outcome\":\"no_receipt\"")
                && nothing.body().contains("\"title\":\"Kill refused\"") && !nothing.body().contains("\"outcome\"")
                && nothing.body().contains(ServerOpsKill.NOTHING_TO_STOP)
                && out.contains("cc-console kill outcome=receipt") && out.contains("cc-console kill outcome=escalated");
        return ok ? null : "receipt " + PeerText.bound(receipt.body(), 90) + " | escalated "
                + PeerText.bound(escalated.body(), 50) + " | none " + PeerText.bound(noReceipt.body(), 50)
                + " | nothing " + PeerText.bound(nothing.body(), 60) + " | row seen "
                + out.contains("cc-console kill outcome=receipt") + " (flips if the outcome token map, the answer's"
                + " outcome / detail keys, or the per-kill stdout row change)";
    }

    // ---- the mods POST rows -----------------------------------------

    /** A mod's own folder holding a lawful
     * {@code mod_info.json}; returns the folder, absolute + normalized. */
    private static Path stageMod(Path dir, String id) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(InstalledModScan.MOD_INFO), "{\"id\":\"" + id + "\",\"name\":\"Selftest " + id
                + "\",\"version\":\"1.0\"}", StandardCharsets.UTF_8);
        return dir.toAbsolutePath().normalize();
    }

    /** The props text with {@code rows} appended, one
     * per line (a missing final newline added first). */
    private static byte[] withRows(byte[] props, String... rows) {
        StringBuilder sb = new StringBuilder(new String(props, StandardCharsets.UTF_8));
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A form value, URL-encoded. */
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The file's {@code key} row as the scan reads
     * it ({@link InstalledModScan#configRowOrNull}: null = absent). */
    private static String propsRow(Path props, String key) throws Exception {
        return InstalledModScan.configRowOrNull(Files.readAllLines(props, StandardCharsets.UTF_8), key);
    }

    /**
     * {@code op=add} of a mod's own folder answers
     * ok with the row listed and writes the folder as the ONE {@code mods.roots} token (the rig's
     * file has no roots row); a {@code ;} path answers Swing's two-line refusal and a relative path
     * its refusal - neither changes a byte of the file. The props file is restored after.
     */
    private static String modsAddRow(Rig rig) throws Exception {
        String r = "web-mods-add";
        Path props = rig.roots.config();
        byte[][] saved = {null};
        Path[] mod = {null};
        setup(r, () -> {
            saved[0] = Files.readAllBytes(props);
            mod[0] = stageMod(rig.root.resolve("mods-add").resolve("cc_selftest_a"), "cc_selftest_a");
            if (InstalledModScan.configRowOrNull(Files.readAllLines(props, StandardCharsets.UTF_8),
                    InstalledModScan.MODS_ROOTS_KEY) != null) {
                throw new IllegalStateException("the rig's props already carry a mods.roots row");
            }
        });
        try {
            Resp add = post(rig, "/api/mods", "op=add&path=" + enc(mod[0].toString()));
            String roots = propsRow(props, InstalledModScan.MODS_ROOTS_KEY);
            byte[] afterAdd = Files.readAllBytes(props);
            String semiPath = "C:\\cc;selftest";
            Resp semi = post(rig, "/api/mods", "op=add&path=" + enc(semiPath));
            boolean semiSilent = Arrays.equals(afterAdd, Files.readAllBytes(props));
            Resp rel = post(rig, "/api/mods", "op=add&path=" + enc("mods/cc_selftest_a"));
            boolean relSilent = Arrays.equals(afterAdd, Files.readAllBytes(props));
            String wantSemi = OpsConsoleForms.errorJson("Mods", "Cannot list " + semiPath
                    + ": a ';' in the path (the mods.roots separator).\nMove or rename the folder first.");
            String wantRel = OpsConsoleForms.errorJson("Mods", "Cannot list mods/cc_selftest_a: the path must be absolute.");
            // The package's own serve-mods folder stays searched (PackageModSync.PACKAGE_DEFAULT_ROOTS)
            // and the added folder joins it as the second token - written with '/' only, the shape that
            // survives the packed launcher's Properties load (OpsModsPanel.rootToken)
            String wantRoots = PackageModSync.MODS_DIR + ";" + mod[0].toString().replace('\\', '/');
            boolean ok = add.body().startsWith("{\"ok\":true,\"roots\":[\"" + PackageModSync.MODS_DIR + "\",")
                    && add.body().contains("\"id\":\"cc_selftest_a\"")
                    && wantRoots.equals(roots) && wantSemi.equals(semi.body()) && semiSilent
                    && wantRel.equals(rel.body()) && relSilent;
            return ok ? null : "add " + PeerText.bound(add.body(), 120) + " | roots row "
                    + (roots == null ? "absent" : roots.equals(wantRoots) ? "= serve-mods;the folder" : roots.length() + "ch")
                    + " | semi " + PeerText.bound(semi.body(), 140) + " silent " + semiSilent + " | rel "
                    + PeerText.bound(rel.body(), 100) + " silent " + relSilent + " (flips if add stops writing the"
                    + " folder as one mods.roots token, stops re-scanning into the answer, drops the ';' refusal's"
                    + " second line, or writes before a refusal)";
        } finally {
            if (saved[0] != null) {
                Files.write(props, saved[0]);
            }
        }
    }

    /**
     * The AMENDED {@code web-mods-remove} legs on the rig's package
     * base - two mods staged INSIDE {@code <pkg>/serve-mods}. (a) the {@code mods.roots} row
     * ABSENT (the package default = serve-mods): Remove of one keeps the row absent (the serve-mods root is never
     * dropped or split), its id leaves {@code mods.enabled}, it stays listed (now off) and the answer's note is
     * {@link ServerOpsCore#modsRemovedLine} with its folder name; (b) a SPLIT row
     * {@code serve-mods/<a>;serve-mods/<b>} (an older Remove's): Remove of one folds the row back to
     * {@code serve-mods} and the note carries the repaired tail. Returns null when green, else the FAIL text;
     * the props file is the caller's to restore, the staged folders are removed here.
     */
    private static String keptRootLegs(Rig rig, Path props, byte[] base) throws Exception {
        Path sm = rig.pkg.resolve(PackageModSync.MODS_DIR);
        boolean smExisted = Files.exists(sm);
        try {
            setup("web-mods-remove", () -> {
                stageMod(sm.resolve("cc_selftest_k"), "cc_selftest_k");
                stageMod(sm.resolve("cc_selftest_m"), "cc_selftest_m");
                Files.write(props, withRows(base, OpsMatchRows.MODS_ENABLED_KEY + "=cc_selftest_k,cc_selftest_m"));
            });
            Resp absent = post(rig, "/api/mods", "op=remove&id=cc_selftest_k");
            String absentRoots = propsRow(props, InstalledModScan.MODS_ROOTS_KEY);
            String absentEnabled = propsRow(props, OpsMatchRows.MODS_ENABLED_KEY);
            setup("web-mods-remove", () -> Files.write(props, withRows(base, InstalledModScan.MODS_ROOTS_KEY
                    + "=serve-mods/cc_selftest_k;serve-mods/cc_selftest_m",
                    OpsMatchRows.MODS_ENABLED_KEY + "=cc_selftest_k,cc_selftest_m")));
            Resp split = post(rig, "/api/mods", "op=remove&id=cc_selftest_k");
            String splitRoots = propsRow(props, InstalledModScan.MODS_ROOTS_KEY);
            List<String> bad = new ArrayList<>();
            String plain = "\"note\":\"" + OpsConsoleForms.jsonEscape(
                    ServerOpsCore.modsRemovedLine("cc_selftest_k", "cc_selftest_k", false)) + "\"}";
            if (absentRoots != null || !"cc_selftest_m".equals(absentEnabled)
                    || !absent.body().contains("\"id\":\"cc_selftest_k\"") || !absent.body().endsWith(plain)) {
                bad.add("absent row: roots " + (absentRoots == null ? "absent" : PeerText.bound(absentRoots, 60))
                        + " enabled " + PeerText.bound(String.valueOf(absentEnabled), 40) + " answer "
                        + PeerText.bound(absent.body(), 160) + " (flips if Remove drops or splits the serve-mods root,"
                        + " writes a roots row the ABSENT default never needed, keeps the id on, unlists the mod, or"
                        + " the note leaves modsRemovedLine)");
            }
            String repaired = "\"note\":\"" + OpsConsoleForms.jsonEscape(
                    ServerOpsCore.modsRemovedLine("cc_selftest_k", "cc_selftest_k", true)) + "\"}";
            if (!PackageModSync.MODS_DIR.equals(splitRoots) || !split.body().endsWith(repaired)) {
                bad.add("split row: roots " + PeerText.bound(String.valueOf(splitRoots), 80) + " answer "
                        + PeerText.bound(split.body(), 160) + " (flips if a split serve-mods row is not folded back"
                        + " to serve-mods or the note loses its repaired tail)");
            }
            return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
        } finally {
            setup("web-mods-remove", () -> {
                if (smExisted) {
                    deleteTree(sm.resolve("cc_selftest_k"));
                    deleteTree(sm.resolve("cc_selftest_m"));
                } else {
                    deleteTree(sm);
                }
            });
        }
    }

    /**
     * {@code web-reach} - Check from the internet over the REAL
     * handler ({@code POST /api/reach}; {@code API_PATHS} lists it for {@code web-token-403}) and the core's ONE
     * shared dial, its {@code reachDialer} seam wrapped by a counter (the seam, {@code LAST_REACH} and the launcher
     * file restored in {@code finally}). Legs: a keyless POST = 403; a keyed GET = 405; a LF in the form = refused,
     * no dial; a blank Advertise = {@link ReachProbe#TYPE_ADDRESS}, no dial; the REAL dial of {@code 127.0.0.1} at a
     * just-closed loopback port (a bare host at {@code bindPort}) = silent or refused within 8 s with the
     * typed-address sentence; a HELD dial (the counter waits on a latch) - a status GET during it starts no
     * second dial, a press of ANOTHER port answers busy, the held press answers silent once released. The teed
     * stdout holds ONE {@code cc-console reach result=} row per press that reached the handler (the flight's for a
     * dial, {@code not_dialed} with control_char / address / start_first / busy for the rest) and none names
     * 127.0.0.1 or a port. RE-TARGETED, the web press gates
     * like Swing's {@code reachPress} - a FIRST leg with NO status file (no running server) answers
     * {@link ReachProbe#START_FIRST} with no dial, while its LF / blank twins still answer their own refusals
     * (the address is judged before the live gate); then {@code liveStatus} is planted (the brick rows' fixture,
     * the status file restored in {@code finally}) and the legs above run under it unchanged.
     */
    private static String reachRow(Rig rig) throws Exception {
        String r = "web-reach";
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path ops = rig.roots.opsFile();
        byte[][] priorLauncher = {null};
        byte[][] priorOps = {null};
        int[] closed = {0};
        setup(r, () -> {
            ServerOpsCore.ReachFlight running = ServerOpsCore.reachFlight.get();
            if (running != null && !running.result().isDone()) {
                throw new IllegalStateException("a reachability dial is still running");
            }
            priorLauncher[0] = takeFile(launcher);
            priorOps[0] = takeFile(ops); // no status file = no running server (the first legs)
            try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                closed[0] = probe.getLocalPort();
            }
        });
        int heldPort = 40001;
        int otherPort = 40002;
        ServerOpsCore.ReachDialer realDialer = ServerOpsCore.reachDialer;
        OpsSnapshot.Reach priorReach = ServerOpsCore.LAST_REACH.get();
        AtomicInteger dials = new AtomicInteger();
        AtomicBoolean hold = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServerOpsCore.reachDialer = (host, port, protonVpn) -> {
            dials.incrementAndGet();
            if (!hold.get()) {
                return realDialer.dial(host, port, protonVpn);
            }
            entered.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException stop) { // the row's finally released it; the dial answers anyway
                Thread.currentThread().interrupt();
            }
            return new ReachProbe.Result(ReachProbe.Verdict.SILENT, 5L, "held", "silent");
        };
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        Resp keyless;
        Resp get;
        Resp lf0;
        Resp blank0;
        Resp noLive;
        Resp lf;
        Resp blank;
        Resp real;
        long realMs;
        boolean held;
        Resp during;
        Resp busy;
        HttpResponse<String> heldAnswer;
        int[] counts = new int[4];
        int noLiveDials;
        try {
            keyless = call(rig, "POST", "/api/reach", null, "advertise=127.0.0.1&bindPort=" + closed[0]);
            get = call(rig, "GET", "/api/reach", rig.boot.token(), null);
            lf0 = post(rig, "/api/reach", "advertise=127.0.0.1%0A&bindPort=" + closed[0]);
            blank0 = post(rig, "/api/reach", "advertise=&bindPort=" + closed[0]);
            noLive = post(rig, "/api/reach", "bindHost=0.0.0.0&bindPort=" + closed[0] + "&advertise=127.0.0.1");
            noLiveDials = dials.get();
            setup(r, () -> Files.write(ops, liveStatus(rig, closed[0]), StandardCharsets.UTF_8));
            lf = post(rig, "/api/reach", "advertise=127.0.0.1%0A&bindPort=" + closed[0]);
            blank = post(rig, "/api/reach", "advertise=&bindPort=" + closed[0]);
            counts[0] = dials.get();
            long t0 = System.nanoTime();
            real = post(rig, "/api/reach", "bindHost=0.0.0.0&bindPort=" + closed[0] + "&advertise=127.0.0.1");
            realMs = (System.nanoTime() - t0) / 1_000_000L;
            counts[1] = dials.get();
            hold.set(true);
            CompletableFuture<HttpResponse<String>> first = rig.client.sendAsync(request(rig.boot.port(), "POST",
                    "/api/reach", rig.boot.token(), "advertise=127.0.0.1&bindPort=" + heldPort),
                    HttpResponse.BodyHandlers.ofString());
            held = entered.await(10, TimeUnit.SECONDS);
            during = call(rig, "GET", "/api/status", rig.boot.token(), null);
            counts[2] = dials.get();
            busy = post(rig, "/api/reach", "advertise=127.0.0.1&bindPort=" + otherPort);
            counts[3] = dials.get();
            release.countDown();
            heldAnswer = first.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            System.setOut(prior);
            ServerOpsCore.reachDialer = realDialer;
            ServerOpsCore.LAST_REACH.set(priorReach);
            setup(r, () -> {
                restoreFile(launcher, priorLauncher[0]);
                restoreFile(ops, priorOps[0]);
            });
        }
        List<String> bad = new ArrayList<>();
        if (keyless.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(keyless.body()) || get.code() != 405) {
            bad.add("keyless -> " + keyless.code() + " GET -> " + get.code() + " (the key guard or the POST-only"
                    + " method set left the route)");
        }
        if (!OpsConsoleForms.errorJson(OpsConsole.REACH_REFUSED, ReachProbe.START_FIRST).equals(noLive.body())
                || !lf0.body().startsWith("{\"error\":") || lf0.body().contains(ReachProbe.START_FIRST)
                || !OpsConsoleForms.errorJson(OpsConsole.REACH_REFUSED, ReachProbe.TYPE_ADDRESS).equals(blank0.body())
                || noLiveDials != 0) {
            bad.add("no running server: press " + PeerText.bound(noLive.body(), 100) + " | LF "
                    + PeerText.bound(lf0.body(), 60) + " | blank " + PeerText.bound(blank0.body(), 60) + " | dials "
                    + noLiveDials + " (flips if the web press dials with no running server - Swing's START_FIRST gate"
                    + " dropped - or the live gate runs before the control-char / address checks)");
        }
        if (!lf.body().startsWith("{\"error\":") || !lf.body().contains("\"title\":\"" + OpsConsole.REACH_REFUSED + "\"")
                || !OpsConsoleForms.errorJson(OpsConsole.REACH_REFUSED, ReachProbe.TYPE_ADDRESS).equals(blank.body())
                || counts[0] != 0) {
            bad.add("LF " + PeerText.bound(lf.body(), 80) + " | blank " + PeerText.bound(blank.body(), 80) + " | dials "
                    + counts[0] + " (flips if the control-char refusal or the Type-the-address gate is dropped, or a"
                    + " refused form still dials)");
        }
        String typed = "\"note\":\"" + OpsConsoleForms.jsonEscape(ReachProbe.note(ReachProbe.Verdict.SILENT,
                "127.0.0.1:" + closed[0], closed[0], 0L, false)) + "\"}";
        boolean realVerdict = real.body().startsWith("{\"ok\":true,\"verdict\":\"silent\",\"ms\":")
                || real.body().startsWith("{\"ok\":true,\"verdict\":\"refused\",\"ms\":");
        if (!realVerdict || !real.body().endsWith(typed) || realMs > 9_000L || counts[1] != 1) {
            bad.add("closed port -> " + PeerText.bound(real.body(), 120) + " in " + realMs + " ms, dials " + counts[1]
                    + " (flips if the press stops dialing the bare host at bindPort, waits past 8 s, or the answer"
                    + " loses its verdict / typed-address sentence)");
        }
        String heldNote = "\"note\":\"" + OpsConsoleForms.jsonEscape(ReachProbe.note(ReachProbe.Verdict.SILENT,
                "127.0.0.1:" + heldPort, heldPort, 5L, false)) + "\"}";
        String busyWant = "{\"ok\":true,\"verdict\":\"busy\",\"note\":\""
                + OpsConsoleForms.jsonEscape(ServerOpsCore.REACH_BUSY) + "\"}";
        if (!held || during.code() != 200 || !during.body().contains("\"reach\":") || counts[2] != 2
                || !busyWant.equals(busy.body()) || counts[3] != 2) {
            bad.add("held " + held + " status " + during.code() + " dials during/after busy " + counts[2] + "/" + counts[3]
                    + " busy " + PeerText.bound(busy.body(), 100) + " (flips if a status GET dials, a second target"
                    + " while one runs is dialed instead of answered busy, or the press stops sharing the ONE flight)");
        }
        if (heldAnswer.statusCode() != 200 || !heldAnswer.body().startsWith("{\"ok\":true,\"verdict\":\"silent\",\"ms\":5,")
                || !heldAnswer.body().endsWith(heldNote) || dials.get() != 2) {
            bad.add("held press -> " + PeerText.bound(heldAnswer.body(), 120) + " dials " + dials.get()
                    + " (flips if the held flight's result never reaches its press)");
        }
        List<String> rows = new ArrayList<>();
        for (String line : captured.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith("cc-console reach ")) {
                rows.add(line);
            }
        }
        List<String> results = rows.stream().filter(l -> l.startsWith("cc-console reach result=")).toList();
        boolean leaks = rows.stream().anyMatch(l -> l.contains("127.0.0.1") || l.contains(":" + closed[0])
                || l.contains(Integer.toString(heldPort)) || l.contains(Integer.toString(otherPort)));
        boolean tokens = results.stream().filter("cc-console reach result=not_dialed reason=control_char"::equals)
                .count() == 2
                && results.stream().filter("cc-console reach result=not_dialed reason=address"::equals).count() == 2
                && results.stream().filter("cc-console reach result=not_dialed reason=start_first"::equals).count() == 1
                && results.contains("cc-console reach result=not_dialed reason=busy")
                && results.contains("cc-console reach result=silent reason=silent")
                && results.stream().allMatch(l -> l.matches("cc-console reach result=[a-z_]+ reason=[a-z_]+"));
        if (results.size() != 8 || leaks || !tokens) {
            bad.add("stdout reach rows " + rows.size() + " (results " + results.size() + ") leak " + leaks + " tokens "
                    + tokens + " (flips if a press prints more or less than ONE bounded result row, or a row names the"
                    + " address or the port)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-status-reach} - {@code /api/status} REPORTS the
     * last dial. With {@code LAST_REACH} cleared the status answer carries {@code "reach":null}; one press of
     * {@code 127.0.0.1:40003} through a counting fake dial (REFUSED, 7 ms - no datagram) answers that verdict, and
     * the next status answer carries {@code reach} = {verdict refused, ms 7, the typed-address sentence, at}; the
     * fake dialed once. The seam, {@code LAST_REACH} and the launcher file are restored in {@code finally}.
     * AMENDED, the press runs under a planted
     * {@code liveStatus} (the web press now refuses {@link ReachProbe#START_FIRST} with no running server, row
     * {@code web-reach}); the status file is restored in {@code finally}.
     */
    private static String statusReachRow(Rig rig) throws Exception {
        String r = "web-status-reach";
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path ops = rig.roots.opsFile();
        byte[][] priorLauncher = {null};
        byte[][] priorOps = {null};
        setup(r, () -> {
            priorLauncher[0] = takeFile(launcher);
            priorOps[0] = takeFile(ops);
            Files.write(ops, liveStatus(rig, 40003), StandardCharsets.UTF_8);
        });
        int port = 40003;
        ServerOpsCore.ReachDialer realDialer = ServerOpsCore.reachDialer;
        OpsSnapshot.Reach priorReach = ServerOpsCore.LAST_REACH.get();
        AtomicInteger dials = new AtomicInteger();
        ServerOpsCore.LAST_REACH.set(null);
        ServerOpsCore.reachDialer = (host, p, protonVpn) -> {
            dials.incrementAndGet();
            return new ReachProbe.Result(ReachProbe.Verdict.REFUSED, 7L, "fake", "refused");
        };
        Resp before;
        Resp press;
        Resp after;
        try {
            before = call(rig, "GET", "/api/status", rig.boot.token(), null);
            press = post(rig, "/api/reach", "advertise=" + enc("127.0.0.1:" + port));
            after = call(rig, "GET", "/api/status", rig.boot.token(), null);
        } finally {
            ServerOpsCore.reachDialer = realDialer;
            ServerOpsCore.LAST_REACH.set(priorReach);
            setup(r, () -> {
                restoreFile(launcher, priorLauncher[0]);
                restoreFile(ops, priorOps[0]);
            });
        }
        String note = OpsConsoleForms.jsonEscape(ReachProbe.note(ReachProbe.Verdict.REFUSED, "127.0.0.1:" + port, port,
                7L, false));
        List<String> bad = new ArrayList<>();
        if (!before.body().contains(",\"reach\":null,")) {
            bad.add("before " + PeerText.bound(before.body(), 60) + " (flips if the status stops serving reach, or"
                    + " serves one before any dial)");
        }
        if (!("{\"ok\":true,\"verdict\":\"refused\",\"ms\":7,\"note\":\"" + note + "\"}").equals(press.body())) {
            bad.add("press " + PeerText.bound(press.body(), 120) + " (flips if the answer's verdict / ms / note keys drift)");
        }
        if (!after.body().contains(",\"reach\":{\"verdict\":\"refused\",\"ms\":7,\"note\":\"" + note + "\",\"at\":")
                || dials.get() != 1) {
            bad.add("after " + PeerText.bound(after.body(), 60) + " dials " + dials.get() + " (flips if the status"
                    + " stops carrying the last dial's verdict, or a status GET dials)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-record} - the Recording pick over the REAL
     * Start. With {@code launcher.properties} = {@code keep.me=1} and no record row, {@code /api/config} serves
     * {@code record} = off (the launcher default); a Start with {@code record=diag} reaches its spawn and leaves
     * EXACTLY {@code keep.me=1} + {@code record=diag} (CRLF kept); a Start with {@code record=Full} (case) is
     * refused before the flow (no spawn, the file untouched); {@code /api/config} then echoes {@code diag}. The
     * launcher file is restored in {@code finally}.
     */
    private static String startRecordRow(Rig rig) throws Exception {
        String r = "web-start-record";
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        String fields = "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400&descriptor=match-web-pkg.txt"
                + "&passwordAction=keep&confirmed=" + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed="
                + ServerOpsCore.CONFIRM_ARMED_DOOR;
        Path ops = rig.roots.opsFile();
        byte[][] priorLauncher = {null, null};
        setup(r, () -> {
            priorLauncher[0] = takeFile(launcher);
            priorLauncher[1] = takeFile(ops); // an earlier row's live status file would refuse the Start
            Files.write(launcher, "keep.me=1\r\n".getBytes(StandardCharsets.UTF_8));
        });
        byte[] want = "keep.me=1\r\nrecord=diag\r\n".getBytes(StandardCharsets.UTF_8);
        Resp config0;
        Resp diag;
        Resp refused;
        Resp config1;
        int spawns0;
        int spawns1;
        int spawns2;
        byte[] afterDiag;
        byte[] afterRefused;
        try {
            config0 = call(rig, "GET", "/api/config", rig.boot.token(), null);
            spawns0 = rig.spawned.size();
            diag = post(rig, "/api/start", fields + "&record=diag");
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterDiag = Files.readAllBytes(launcher);
            refused = post(rig, "/api/start", fields + "&record=Full");
            awaitStaging();
            spawns2 = rig.spawned.size();
            afterRefused = Files.readAllBytes(launcher);
            config1 = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            setup(r, () -> {
                restoreFile(launcher, priorLauncher[0]);
                restoreFile(ops, priorLauncher[1]);
            });
        }
        List<String> bad = new ArrayList<>();
        if (!config0.body().contains(",\"record\":\"off\"")) {
            bad.add("config before " + PeerText.bound(config0.body().substring(Math.max(0,
                    config0.body().indexOf("\"record\""))), 40) + " (flips if a package with no record row stops"
                    + " prefilling off)");
        }
        if (!diag.body().startsWith("{\"ok\":true") || spawns1 <= spawns0 || !Arrays.equals(want, afterDiag)) {
            bad.add("diag Start " + PeerText.bound(diag.body(), 100) + " spawns " + spawns0 + "/" + spawns1 + " file '"
                    + PeerText.bound(new String(afterDiag, StandardCharsets.UTF_8).replace("\r", "<CR>")
                            .replace("\n", "<LF>"), 80) + "' (flips if the Recording pick stops being saved as the"
                    + " record row right before the spawn, or the writer rewrites more than its row)");
        }
        if (!refused.body().startsWith("{\"error\":") || !refused.body().contains("\"title\":\"Start refused\"")
                || spawns2 != spawns1 || !Arrays.equals(afterDiag, afterRefused)) {
            bad.add("Full Start " + PeerText.bound(refused.body(), 100) + " spawns " + spawns1 + "/" + spawns2
                    + " (flips if a value outside off|diag|full is saved or reaches the spawn)");
        }
        if (!config1.body().contains(",\"record\":\"diag\"")) {
            bad.add("config after carries no record=diag (flips if /api/config stops echoing the box row)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-record-owner-untouched} - the Start's record row to save
     * ({@link OpsConsole#recordRowToSave}) is a PACKAGE row only: OWNER mode + the page's default {@code off} = null (the
     * row untouched - owner mode's own {@code record=} row, taken from its serve command, survives the Start), OWNER + {@code Full} = null too
     * (ignored, never parsed - the posted field is not owner mode's row; the launcher's {@code record_row} grammar still
     * guards the row on disk in both modes); PACKAGE + {@code off} = {@code off},
     * PACKAGE + blank = null, PACKAGE + {@code Full} = the launcher's refusal. Flips if an owner-mode web Start writes
     * the posted select over owner mode's row again, or if owner mode starts refusing the field.
     */
    private static String recordOwnerUntouchedRow() {
        List<String> bad = new ArrayList<>();
        if (OpsConsole.recordRowToSave(OpsRoots.Mode.OWNER, "off") != null) {
            bad.add("owner+off saved (flips if an owner-mode Start writes the page's default over owner mode's row)");
        }
        try {
            if (OpsConsole.recordRowToSave(OpsRoots.Mode.OWNER, "Full") != null) {
                bad.add("owner+Full saved (flips if owner mode saves an unparsed value)");
            }
        } catch (IllegalArgumentException refused) {
            bad.add("owner+Full refused '" + PeerText.bound(refused.getMessage(), 60)
                    + "' (flips if owner mode starts refusing a field it never saves)");
        }
        if (!"off".equals(OpsConsole.recordRowToSave(OpsRoots.Mode.PACKAGE, "off"))) {
            bad.add("package+off not saved (flips if the package pick stops being the row)");
        }
        if (OpsConsole.recordRowToSave(OpsRoots.Mode.PACKAGE, " ") != null) {
            bad.add("package+blank saved (flips if a page without the select starts writing the row)");
        }
        try {
            OpsConsole.recordRowToSave(OpsRoots.Mode.PACKAGE, "Full");
            bad.add("package+Full accepted (flips if the launcher's case-exact grammar stops gating the pick)");
        } catch (IllegalArgumentException expected) {
            // the launcher's own refusal text
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-arms-reach} - the AUTO reachability check
     * armed by the REAL Start ({@code POST /api/start}: the {@code web-start-lease} fields, the rig's recording
     * spawn, its loopback ProtonVPN gateway). The launcher file planted with the ProtonVPN rows the posted bind
     * host + Advertise match (IN the mode): the STARTED answer, then {@code REACH_ARMED} holds a NEW launch (not
     * the value before the press) whose {@code opsFile()} is the rig's status file; the {@code reachDialer} seam
     * (wrapped by a counter) saw 0 dials when the answer arrived and still 0 once the armed daemon ended
     * ({@code REACH_AUTO_ENDED} +1 within 5 s: the stand-in launcher exited 1 = FAILED at the daemon's first
     * poll) - the answer never waits on the check. The same Start with an Advertise naming another address
     * (TYPED mode, the mode rows re-planted) also starts and leaves {@code REACH_ARMED} unchanged. SETUP needs
     * 127.0.0.1 on an up interface (the lease bind check). The launcher + status files, the seam and
     * {@code REACH_ARMED} are restored in {@code finally}.
     */
    private static String startArmsReachRow(Rig rig) throws Exception {
        String r = "web-start-arms-reach";
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        Path ops = rig.roots.opsFile();
        String modeRows = "advertise.from=protonvpn\r\nadvertise.bind=127.0.0.1\r\nadvertise=203.0.113.5:40000\r\n";
        byte[][] prior = {null, null};
        setup(r, () -> {
            if (!ServerOpsLaunch.localAddresses().contains("127.0.0.1")) {
                throw new IllegalStateException("127.0.0.1 is on no up interface (the lease bind check cannot pass)");
            }
            prior[0] = takeFile(launcher);
            prior[1] = takeFile(ops); // a live status file would refuse the Start
            Files.writeString(launcher, modeRows, StandardCharsets.UTF_8);
        });
        String fields = "bindHost=127.0.0.1&bindPort=51337&tickRate=60&fortressDp=400&descriptor=match-web-pkg.txt"
                + "&passwordAction=keep&confirmed=" + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed="
                + ServerOpsCore.CONFIRM_ARMED_DOOR + "&advertise=";
        ServerOpsCore.ReachDialer realDialer = ServerOpsCore.reachDialer;
        LaneFollow.LaneLaunch priorArmed = ServerOpsCore.REACH_ARMED.get();
        AtomicInteger dials = new AtomicInteger();
        ServerOpsCore.reachDialer = (host, port, protonVpn) -> {
            dials.incrementAndGet();
            return new ReachProbe.Result(ReachProbe.Verdict.SILENT, 1L, "fixture", "silent");
        };
        Resp inMode;
        Resp typed;
        LaneFollow.LaneLaunch armedIn;
        LaneFollow.LaneLaunch armedTyped;
        int dialsAtAnswer;
        int dialsAfter;
        long answerMs;
        boolean ended;
        try {
            long ended0 = ServerOpsCore.REACH_AUTO_ENDED.get();
            long t0 = System.nanoTime();
            inMode = post(rig, "/api/start", fields + enc("203.0.113.5:40000"));
            answerMs = (System.nanoTime() - t0) / 1_000_000L;
            dialsAtAnswer = dials.get();
            armedIn = ServerOpsCore.REACH_ARMED.get();
            awaitStaging();
            long until = System.currentTimeMillis() + 5_000L;
            while (ServerOpsCore.REACH_AUTO_ENDED.get() <= ended0 && System.currentTimeMillis() < until) {
                Thread.sleep(20L);
            }
            ended = ServerOpsCore.REACH_AUTO_ENDED.get() > ended0;
            dialsAfter = dials.get();
            setup(r, () -> Files.writeString(launcher, modeRows, StandardCharsets.UTF_8));
            typed = post(rig, "/api/start", fields + enc("1.2.3.4:51337"));
            awaitStaging();
            armedTyped = ServerOpsCore.REACH_ARMED.get();
        } finally {
            ServerOpsCore.reachDialer = realDialer;
            ServerOpsCore.REACH_ARMED.set(priorArmed);
            setup(r, () -> {
                restoreFile(launcher, prior[0]);
                restoreFile(ops, prior[1]);
            });
        }
        System.out.println("  " + r + ": in-mode answer in " + answerMs + " ms, dials at the answer " + dialsAtAnswer
                + ", after the daemon ended " + dialsAfter + " (ended " + ended + ")");
        List<String> bad = new ArrayList<>();
        if (!inMode.body().startsWith("{\"ok\":true") || armedIn == null || armedIn == priorArmed
                || !ops.equals(armedIn.opsFile())) {
            bad.add("in-mode Start " + PeerText.bound(inMode.body(), 100) + " armed "
                    + (armedIn == null ? "nothing" : armedIn == priorArmed ? "the prior launch" : "a launch")
                    + " (flips if the web Start stops calling armReachCheck after a started press in the ProtonVPN"
                    + " mode, or arms a launch that does not follow this console's status file)");
        }
        if (dialsAtAnswer != 0 || dialsAfter != 0 || !ended) {
            bad.add("dials at the answer " + dialsAtAnswer + " / after " + dialsAfter + ", daemon ended " + ended
                    + " (flips if the Start answer waits on a dial, or a launch that never went LIVE is dialed)");
        }
        if (!typed.body().startsWith("{\"ok\":true") || armedTyped != armedIn) {
            bad.add("typed Start " + PeerText.bound(typed.body(), 100) + " re-armed " + (armedTyped != armedIn)
                    + " (flips if a Start out of the ProtonVPN mode arms the check - the arm must take the press's"
                    + " own mode)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code op=remove} by id drops the mod's root
     * token AND its id from {@code mods.enabled}, keeping the other root + id; remove by the mod's
     * folder path writes the SAME bytes; a bare folder-of-mods root (no listed mod's folder) is
     * refused and stays; an unknown id is refused - neither refusal changes a byte.
     * AMENDED: the by-id answer carries the package note without a folder
     * ({@link ServerOpsCore#modsRemovedLine}; the mod lives outside serve-mods), then
     * {@link #keptRootLegs} (the serve-mods root kept, a split row repaired).
     */
    private static String modsRemoveRow(Rig rig) throws Exception {
        String r = "web-mods-remove";
        Path props = rig.roots.config();
        byte[][] saved = {null, null};
        Path[] p = new Path[2];
        setup(r, () -> {
            saved[0] = Files.readAllBytes(props);
            p[0] = stageMod(rig.root.resolve("mods-rm").resolve("cc_selftest_b"), "cc_selftest_b");
            p[1] = rig.root.resolve("mods-rm").resolve("pack").toAbsolutePath().normalize();
            stageMod(p[1].resolve("cc_selftest_c"), "cc_selftest_c");
            saved[1] = withRows(saved[0], InstalledModScan.MODS_ROOTS_KEY + "=" + p[0] + ";" + p[1],
                    OpsMatchRows.MODS_ENABLED_KEY + "=cc_selftest_b,cc_selftest_c");
            Files.write(props, saved[1]);
        });
        byte[] staged = saved[1];
        try {
            Resp byId = post(rig, "/api/mods", "op=remove&id=cc_selftest_b");
            String roots = propsRow(props, InstalledModScan.MODS_ROOTS_KEY);
            String enabled = propsRow(props, OpsMatchRows.MODS_ENABLED_KEY);
            byte[] afterId = Files.readAllBytes(props);
            Files.write(props, staged);
            Resp byPath = post(rig, "/api/mods", "op=remove&path=" + enc(p[0].toString()));
            boolean pathSame = Arrays.equals(afterId, Files.readAllBytes(props));
            Files.write(props, staged);
            Resp bare = post(rig, "/api/mods", "op=remove&path=" + enc(p[1].toString()));
            boolean bareKept = Arrays.equals(staged, Files.readAllBytes(props));
            Resp unknown = post(rig, "/api/mods", "op=remove&id=cc_selftest_nobody");
            boolean unknownSilent = Arrays.equals(staged, Files.readAllBytes(props));
            String kept = keptRootLegs(rig, props, saved[0]);
            if (kept != null) {
                return kept;
            }
            boolean ok = byId.body().endsWith(",\"note\":\"" + OpsConsoleForms.jsonEscape(
                            ServerOpsCore.modsRemovedLine("cc_selftest_b", null, false)) + "\"}")
                    && byId.body().startsWith("{\"ok\":true,") && !byId.body().contains("\"id\":\"cc_selftest_b\"")
                    && byId.body().contains("\"id\":\"cc_selftest_c\"") && p[1].toString().equals(roots)
                    && "cc_selftest_c".equals(enabled) && byPath.body().startsWith("{\"ok\":true,") && pathSame
                    && OpsConsoleForms.errorJson("Mods", "no listed mod lives in that folder").equals(bare.body())
                    && bareKept && OpsConsoleForms.errorJson("Mods", "no listed mod has that id").equals(unknown.body())
                    && unknownSilent;
            return ok ? null : "byId " + PeerText.bound(byId.body(), 120) + " | roots "
                    + (roots == null ? "absent" : roots.equals(p[1].toString()) ? "= the pack" : roots.length() + "ch")
                    + " enabled " + (enabled == null ? "absent" : PeerText.bound(enabled, 60)) + " | byPath "
                    + PeerText.bound(byPath.body(), 60) + " same " + pathSame + " | bare " + PeerText.bound(bare.body(), 80)
                    + " kept " + bareKept + " | unknown " + PeerText.bound(unknown.body(), 80) + " silent " + unknownSilent
                    + " (flips if remove keeps the mod's root or its choice, drops the other mod's, finds the victim by"
                    + " path differently from by id, removes a bare root, writes on a refusal, or a package Remove"
                    + " of a mod outside serve-mods stops answering the modsRemovedLine note without a folder)";
        } finally {
            if (saved[0] != null) {
                Files.write(props, saved[0]);
            }
        }
    }

    /**
     * {@code op=use} {@code required} writes the id
     * into {@code mods.enabled}; {@code ON} (not a Use value) and an unlisted id are refused; the
     * same choice again changes no byte.
     */
    private static String modsUseRow(Rig rig) throws Exception {
        String r = "web-mods-use";
        Path props = rig.roots.config();
        byte[][] saved = {null};
        setup(r, () -> {
            saved[0] = Files.readAllBytes(props);
            Path d = stageMod(rig.root.resolve("mods-use").resolve("cc_selftest_d"), "cc_selftest_d");
            Files.write(props, withRows(saved[0], InstalledModScan.MODS_ROOTS_KEY + "=" + d));
        });
        try {
            Resp req = post(rig, "/api/mods", "op=use&id=cc_selftest_d&use=required");
            String enabled = propsRow(props, OpsMatchRows.MODS_ENABLED_KEY);
            byte[] afterReq = Files.readAllBytes(props);
            Resp on = post(rig, "/api/mods", "op=use&id=cc_selftest_d&use=ON");
            boolean onSilent = Arrays.equals(afterReq, Files.readAllBytes(props));
            Resp again = post(rig, "/api/mods", "op=use&id=cc_selftest_d&use=required");
            boolean againSilent = Arrays.equals(afterReq, Files.readAllBytes(props));
            Resp ghost = post(rig, "/api/mods", "op=use&id=cc_selftest_nobody&use=optional");
            boolean ghostSilent = Arrays.equals(afterReq, Files.readAllBytes(props));
            boolean ok = req.body().startsWith("{\"ok\":true,") && req.body().contains("\"use\":\"required\"")
                    && "cc_selftest_d".equals(enabled)
                    && OpsConsoleForms.errorJson("Mods", "use must be off, required or optional").equals(on.body())
                    && onSilent && again.body().startsWith("{\"ok\":true,") && againSilent
                    && OpsConsoleForms.errorJson("Mods", "no listed mod has that id").equals(ghost.body()) && ghostSilent;
            return ok ? null : "req " + PeerText.bound(req.body(), 120) + " | enabled "
                    + (enabled == null ? "absent" : PeerText.bound(enabled, 60)) + " | on " + PeerText.bound(on.body(), 80)
                    + " silent " + onSilent + " | again " + PeerText.bound(again.body(), 60) + " silent " + againSilent
                    + " | ghost " + PeerText.bound(ghost.body(), 80) + " silent " + ghostSilent + " (flips if use stops"
                    + " writing mods.enabled, accepts a case-folded value, rewrites an unchanged choice, or writes on a"
                    + " refusal)";
        } finally {
            if (saved[0] != null) {
                Files.write(props, saved[0]);
            }
        }
    }

    /**
     * {@code web-draft-survives-own-writes} - the console's OWN writes of the
     * two config files re-stamp the unsaved Setup draft instead of dropping it (a load drops a draft whose base is
     * older than the newer config file, which must stay a hand edit's privilege). Both files' times are set 10 s back
     * BEFORE each draft save, so the saved base is older than the console write that follows (a same-millisecond
     * write would pass with no re-stamp). Over the REAL routes: a draft row, then a Mods write ({@code /api/mods
     * op=use}, the {@code web-mods-use} recipe) - config still serves the row; a second row, then a launcher write
     * through the shared launcher-row save ({@code /api/lease}, the address-only fill) - config serves both.
     * That second row is the bind port, which the fill puts into the page's
     * field, so config serves it SETTLED to the lease answer's port (the typed {@code 51337} standing when no port
     * is found), the tick rate untouched, and no fill address row minted ({@code setup.bindHost} /
     * {@code setup.advertise} were never drafted). Stdout holds exactly the two saved rows ({@code n=1} then
     * {@code n=2}) then {@code settled n=1}, and no re-stamp failure. A route that wrote nothing = SETUP (the leg
     * never reached its write). The props, the launcher file and the draft are restored in {@code finally}. Flips
     * if a console-owned write drops the draft (the re-stamp removed from the Mods write or from the launcher-row
     * save), if the fill leaves the drafted port stale, or if a settle mints a row the draft did not hold.
     */
    private static String draftSurvivesOwnWritesRow(Rig rig) throws Exception {
        String r = "web-draft-survives-own-writes";
        Path draft = OpsSetupDraft.fileFor(rig.roots);
        Path props = rig.roots.config();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = takeFile(draft);
            prior[1] = Files.readAllBytes(props);
            prior[2] = takeFile(launcher);
            Path d = stageMod(rig.root.resolve("draft-mods").resolve("cc_selftest_dr"), "cc_selftest_dr");
            Files.write(props, withRows(prior[1], InstalledModScan.MODS_ROOTS_KEY + "=" + d));
            Files.writeString(launcher, "keep.me=1\n", StandardCharsets.UTF_8);
            backdate(props, launcher);
        });
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Resp mods;
        String enabled;
        Resp afterMods;
        Resp lease;
        Resp afterLease;
        System.setOut(tee(priorOut, captured));
        try {
            post(rig, "/api/draft", "id=setup.tickRate&value=90");
            mods = post(rig, "/api/mods", "op=use&id=cc_selftest_dr&use=required");
            enabled = propsRow(props, OpsMatchRows.MODS_ENABLED_KEY);
            afterMods = call(rig, "GET", "/api/config", rig.boot.token(), null);
            setup(r, () -> backdate(props, launcher));
            post(rig, "/api/draft", "id=setup.bindPort&value=51400");
            lease = post(rig, "/api/lease", "bindPort=51337");
            afterLease = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            System.setOut(priorOut);
            setup(r, () -> {
                OpsSetupDraft.clear(draft);
                restoreFile(draft, prior[0]);
                Files.write(props, prior[1]);
                restoreFile(launcher, prior[2]);
            });
        }
        boolean modsWrote = mods.body().startsWith("{\"ok\":true,") && "cc_selftest_dr".equals(enabled);
        boolean leaseSaved = lease.body().startsWith("{\"ok\":true,") && lease.body().contains("\"saved\":true");
        String fillPort = answerMember(lease.body(), "bindPort");
        setup(r, () -> {
            if (!modsWrote || !leaseSaved) {
                throw new IllegalStateException("a console write never happened: mods " + modsWrote + " lease "
                        + leaseSaved);
            }
            if (fillPort == null || fillPort.isBlank() || "51400".equals(fillPort)) { // the settle must CHANGE it
                throw new IllegalStateException("the fill answered no port distinct from the drafted one: "
                        + PeerText.bound(lease.body(), 100));
            }
        });
        List<String> bad = new ArrayList<>();
        if (!afterMods.body().startsWith("{\"draft\":{\"setup.tickRate\":\"90\"},")) {
            bad.add("after the Mods write config " + PeerText.bound(afterMods.body(), 100) + " (flips if the Mods"
                    + " write stops re-stamping the draft - a Mods press would drop the unsaved Setup)");
        }
        if (!afterLease.body().startsWith("{\"draft\":{\"setup.tickRate\":\"90\",\"setup.bindPort\":\"" + fillPort
                + "\"},")) {
            bad.add("after the launcher write config " + PeerText.bound(afterLease.body(), 120) + " (flips if the"
                    + " launcher-row save stops re-stamping the draft - a Fill / brick / lease press would drop the"
                    + " unsaved Setup - or the fill leaves the drafted port stale under the port it put in the field)");
        }
        String draftObject = draftObjectOf(afterLease.body());
        if (draftObject == null || draftObject.contains("\"setup.bindHost\"")
                || draftObject.contains("\"setup.advertise\"")) {
            bad.add("after the launcher write the draft object is " + PeerText.bound(String.valueOf(draftObject), 120)
                    + " (flips if a settle mints the fill's address rows the draft never held)");
        }
        List<String> rows = rowsOf(captured, "cc-console draft=");
        if (!List.of("saved id=setup.tickRate n=1", "saved id=setup.bindPort n=2", "settled n=1").equals(rows)) {
            bad.add("stdout draft rows " + rows + " (flips if the Mods write dropped the draft before the second row,"
                    + " a re-stamp failed, or the fill's save stopped settling the one held address row)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-draft-settled-by-fill} - a Fill settles the unsaved Setup
     * over the REAL routes on the rig's loopback gateway. Five rows drafted ({@code setup.tickRate}, {@code
     * setup.gameRoot}, then the three address rows holding stale typed values) after both config files are
     * backdated (the {@code web-draft-survives-own-writes} idiom), then {@code /api/lease bindPort=51337} (the
     * address-only fill). Config's draft object = the two untouched rows unchanged and the three address rows =
     * the lease ANSWER's own {@code bindHost} / {@code bindPort} / {@code advertise}, each in its drafted place;
     * stdout's draft rows = the five saves then {@code settled n=3}; no stdout line carries a stale or filled
     * address, a port or the drafted folder. A fill whose answer does not differ from every stale value = SETUP
     * (the row could not tell a settle from a keep). The props, the launcher file and the draft are restored in
     * {@code finally}. Flips if a settled id keeps its stale value, an unsettled row is lost or moved, a settle
     * is not counted, or the settle row leaks a value.
     */
    private static String draftSettledByFillRow(Rig rig) throws Exception {
        String r = "web-draft-settled-by-fill";
        Path draft = OpsSetupDraft.fileFor(rig.roots);
        Path props = rig.roots.config();
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        String gameRoot = rig.root.resolve("draft-settle-game").toString();
        String[][] drafted = {{"setup.tickRate", "90"}, {"setup.gameRoot", gameRoot}, {"setup.bindHost", "10.9.9.9"},
            {"setup.bindPort", "51400"}, {"setup.advertise", "192.0.2.9:51400"}};
        byte[][] prior = new byte[3][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = takeFile(draft);
            prior[1] = Files.readAllBytes(props);
            prior[2] = takeFile(launcher);
            Files.writeString(launcher, "keep.me=1\n", StandardCharsets.UTF_8);
            backdate(props, launcher);
        });
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        List<String> saveBodies = new ArrayList<>();
        Resp lease;
        Resp config;
        System.setOut(tee(priorOut, captured));
        try {
            for (String[] row : drafted) {
                saveBodies.add(post(rig, "/api/draft", "id=" + row[0] + "&value=" + enc(row[1])).body());
            }
            lease = post(rig, "/api/lease", "bindPort=51337");
            config = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            System.setOut(priorOut);
            setup(r, () -> {
                OpsSetupDraft.clear(draft);
                restoreFile(draft, prior[0]);
                Files.write(props, prior[1]);
                restoreFile(launcher, prior[2]);
            });
        }
        String leaseBody = lease.body();
        String host = answerMember(leaseBody, "bindHost");
        String port = answerMember(leaseBody, "bindPort");
        String advertise = answerMember(leaseBody, "advertise");
        setup(r, () -> {
            if (!saveBodies.stream().allMatch("{\"ok\":true}"::equals)) {
                throw new IllegalStateException("a draft save was refused: " + saveBodies);
            }
            if (!leaseBody.startsWith("{\"ok\":true,") || !leaseBody.contains("\"saved\":true") || host == null
                    || port == null || advertise == null || host.isBlank() || port.isBlank() || "10.9.9.9".equals(host)
                    || "51400".equals(port) || "192.0.2.9:51400".equals(advertise)) {
                throw new IllegalStateException("the fill was not saved with values distinct from the stale ones: "
                        + PeerText.bound(leaseBody, 160));
            }
        });
        List<String> bad = new ArrayList<>();
        String want = "{\"draft\":{\"setup.tickRate\":\"90\",\"setup.gameRoot\":\"" + ServerOpsCore.jsonEscape(gameRoot)
                + "\",\"setup.bindHost\":\"" + host + "\",\"setup.bindPort\":\"" + port + "\",\"setup.advertise\":\""
                + advertise + "\"},";
        if (!config.body().startsWith(want)) {
            bad.add("config " + PeerText.bound(config.body(), 220) + " (flips if a settled address row keeps its stale"
                    + " typed value, an unsettled row is lost, or a replaced row moves in the draft's order)");
        }
        List<String> rows = rowsOf(captured, "cc-console draft=");
        List<String> wantRows = new ArrayList<>();
        for (int i = 0; i < drafted.length; i++) {
            wantRows.add("saved id=" + drafted[i][0] + " n=" + (i + 1));
        }
        wantRows.add("settled n=3");
        if (!wantRows.equals(rows)) {
            bad.add("stdout draft rows " + rows + " (flips if the fill's save stops settling, miscounts the settled"
                    + " rows, or a refusal / re-stamp failure appears)");
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        String exitAddress = advertise.lastIndexOf(':') > 0 ? advertise.substring(0, advertise.lastIndexOf(':'))
                : advertise;
        for (String leak : List.of("10.9.9.9", "192.0.2.9", "51400", host, port, exitAddress, gameRoot)) {
            if (out.contains(leak)) {
                bad.add("stdout carries a drafted or filled value (" + leak.length() + " chars) (flips if a draft or"
                        + " settle row prints an address, a port or a folder)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-draft-fill-mints-nothing} - with NO draft file, {@code
     * /api/lease bindPort=51337} on the rig's loopback gateway (the address-only fill, saved) leaves no draft file,
     * config's draft object empty and no {@code cc-console draft=} row on stdout. A lease not saved = SETUP. The
     * draft and the launcher file are restored in {@code finally}. Flips if a fill's settle mints a draft (or a
     * row) the host never typed.
     */
    private static String draftFillMintsNothingRow(Rig rig) throws Exception {
        String r = "web-draft-fill-mints-nothing";
        Path draft = OpsSetupDraft.fileFor(rig.roots);
        Path launcher = rig.pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        byte[][] prior = new byte[2][];
        setup(r, () -> {
            awaitStaging();
            prior[0] = takeFile(draft);
            prior[1] = takeFile(launcher);
        });
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Resp lease;
        boolean minted;
        Resp config;
        System.setOut(tee(priorOut, captured));
        try {
            lease = post(rig, "/api/lease", "bindPort=51337");
            minted = Files.exists(draft);
            config = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            System.setOut(priorOut);
            setup(r, () -> {
                OpsSetupDraft.clear(draft);
                restoreFile(draft, prior[0]);
                restoreFile(launcher, prior[1]);
            });
        }
        String leaseBody = lease.body();
        setup(r, () -> {
            if (!leaseBody.startsWith("{\"ok\":true,") || !leaseBody.contains("\"saved\":true")) {
                throw new IllegalStateException("the fill was never saved: " + PeerText.bound(leaseBody, 120));
            }
        });
        List<String> bad = new ArrayList<>();
        if (minted || !config.body().startsWith("{\"draft\":{},")) {
            bad.add("draft file " + minted + ", config " + PeerText.bound(config.body(), 100) + " (flips if a fill"
                    + " mints an unsaved Setup the host never typed)");
        }
        List<String> rows = rowsOf(captured, "cc-console draft=");
        if (!rows.isEmpty()) {
            bad.add("stdout draft rows " + rows + " (flips if a fill with no draft writes or settles one)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** Each file's modified time set 10 s before now, so a draft saved next
     * has a base older than any write that follows. */
    private static void backdate(Path... files) throws IOException {
        java.nio.file.attribute.FileTime t = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()
                - 10_000L);
        for (Path f : files) {
            Files.setLastModifiedTime(f, t);
        }
    }

    /** A match pair under {@code <pkg>/match/} - the
     * descriptor {@code name} and its {@code .fingerprint} sidecar keyed to ITS match.id at this server's
     * handshake version (fixture jar / data components: the Apply re-keys only {@code match=}). */
    private static MatchDescriptor stageMatchPair(Rig rig, String name, MatchDescriptor d) throws IOException {
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), MatchDescriptorCodec.encode(d), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(name + ".fingerprint"), "fingerprint=" + ContentFingerprints.composite(
                "a".repeat(32), "b".repeat(32), HandshakeCodec.HANDSHAKE_VERSION, d.matchId()) + "\n",
                StandardCharsets.US_ASCII);
        return d;
    }

    /** A confirm key inside a {@code needsConfirm} answer. */
    private static final java.util.regex.Pattern CONFIRM_KEY = java.util.regex.Pattern.compile("\"key\":\"([^\"]+)\"");

    /** A POST re-sent with every confirm the server asks for (the
     *  page's {@code CC.send} loop, ≤ 4 rounds), so a row reaches the flow past confirms it does not test. */
    private static Resp postConfirmed(Rig rig, String path, String body) throws Exception {
        StringBuilder sent = new StringBuilder(body);
        Resp a = post(rig, path, sent.toString());
        for (int round = 0; round < 4 && a.body().startsWith("{\"needsConfirm\""); round++) {
            java.util.regex.Matcher m = CONFIRM_KEY.matcher(a.body());
            while (m.find()) {
                sent.append("&confirmed=").append(enc(m.group(1)));
            }
            a = post(rig, path, sent.toString());
        }
        return a;
    }

    /** The stdout rows that start with {@code prefix}, prefix cut. */
    private static List<String> rowsOf(ByteArrayOutputStream captured, String prefix) {
        List<String> rows = new ArrayList<>();
        for (String l : captured.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (l.startsWith(prefix)) {
                rows.add(l.substring(prefix.length()));
            }
        }
        return rows;
    }

    /** A string member's JSON text in a flat answer ({@code "key":"<text>"},
     *  the first match), null when absent. */
    private static String answerMember(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\":\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** The config answer's leading draft object {@code {...}}, null when
     *  the answer does not lead with one. */
    private static String draftObjectOf(String configBody) {
        String lead = "{\"draft\":";
        int end = configBody.indexOf("},\"draftUpdated\"");
        return configBody.startsWith(lead) && end > 0 ? configBody.substring(lead.length(), end + 1) : null;
    }

    /**
     * {@code web-dead-routes} - the two retired Setup routes ({@code POST
     * /api/match-text}, {@code POST /api/kit}) answer the plain 404 of any unknown path, keyed and keyless alike
     * (the guard funnel compares the EXACT path before it reads the key), and {@code /api/config} serves no kit
     * facts ({@code kitName} / {@code kitPresent}). Flips if either route is registered again (a keyed POST then
     * reaches a handler, a keyless one answers 403) or the config answer carries its kit facts again.
     */
    private static String deadRoutesRow(Rig rig) throws Exception {
        List<String> bad = new ArrayList<>();
        for (String path : List.of("/api/match-text", "/api/kit")) {
            for (String key : Arrays.asList(rig.boot.token(), null)) {
                Resp r = call(rig, "POST", path, key, "descriptor=");
                if (r.code() != 404 || !"404".equals(r.body())) {
                    bad.add("POST " + path + (key == null ? " keyless" : " keyed") + " -> " + r.code());
                }
            }
        }
        Resp config = call(rig, "GET", "/api/config", rig.boot.token(), null);
        if (config.code() != 200 || config.body().contains("\"kitName\"") || config.body().contains("\"kitPresent\"")) {
            bad.add("GET /api/config -> " + config.code() + " kit facts " + config.body().contains("\"kit"));
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad) + " (flips if /api/match-text"
                + " or /api/kit is registered again, or /api/config serves its kit facts again)";
    }

    /** How long {@code web-props-locked} holds {@link ServerOpsLaunch#PROPS_LOCK} while its POST runs (ms). */
    private static final long PROPS_HOLD_MS = 300L;

    /**
     * {@code web-props-locked} - the Mods write ({@code POST /api/mods op=use},
     * the {@code web-mods-use} recipe) runs its read-modify-write of the config file under
     * {@link ServerOpsLaunch#PROPS_LOCK}, the lock the WATCH lease daemon's clear rewrites that same file under.
     * This row's thread holds the lock for {@value #PROPS_HOLD_MS} ms while the POST runs on the console's own
     * request thread: at the end of the hold the file still holds the staged bytes and the POST is unanswered; after
     * the release it answers ok with {@code mods.enabled} written. Staged after {@link #awaitStaging} (no lease
     * daemon left to rewrite the file mid-row); the props are restored in {@code finally}. Flips if the Mods write
     * runs without the lock (the write lands inside the hold) or the POST stops writing {@code mods.enabled}.
     */
    private static String propsLockedRow(Rig rig) throws Exception {
        String r = "web-props-locked";
        Path props = rig.roots.config();
        byte[][] saved = {null};
        byte[][] staged = {null};
        setup(r, () -> {
            awaitStaging();
            saved[0] = Files.readAllBytes(props);
            Path d = stageMod(rig.root.resolve("mods-locked").resolve("cc_selftest_l"), "cc_selftest_l");
            staged[0] = withRows(saved[0], InstalledModScan.MODS_ROOTS_KEY + "=" + d);
            Files.write(props, staged[0]);
        });
        try {
            CompletableFuture<HttpResponse<String>> answer;
            boolean untouched;
            boolean answeredInHold;
            synchronized (ServerOpsLaunch.PROPS_LOCK) {
                answer = rig.client.sendAsync(request(rig.boot.port(), "POST", "/api/mods", rig.boot.token(),
                        "op=use&id=cc_selftest_l&use=required"), HttpResponse.BodyHandlers.ofString());
                Thread.sleep(PROPS_HOLD_MS);
                untouched = Arrays.equals(staged[0], Files.readAllBytes(props));
                answeredInHold = answer.isDone();
            }
            String body = answer.get(15, TimeUnit.SECONDS).body();
            String enabled = propsRow(props, OpsMatchRows.MODS_ENABLED_KEY);
            boolean ok = untouched && !answeredInHold && body.startsWith("{\"ok\":true,")
                    && "cc_selftest_l".equals(enabled);
            return ok ? null : "file untouched in the hold " + untouched + ", answered in the hold " + answeredInHold
                    + " | after the hold " + PeerText.bound(body, 100) + " enabled "
                    + (enabled == null ? "absent" : PeerText.bound(enabled, 60)) + " (flips if the Mods write stops"
                    + " taking PROPS_LOCK - it lands while another holder has the file - or the POST stops writing"
                    + " mods.enabled)";
        } finally {
            if (saved[0] != null) {
                Files.write(props, saved[0]);
            }
        }
    }

    /** The Start form the seat / over-cap rows post (the roster row's keyed, confirmed package Start). */
    private static String startFields(String descriptor) {
        return "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400&descriptor=" + enc(descriptor)
                + "&passwordAction=keep&confirmed=" + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed="
                + ServerOpsCore.CONFIRM_ARMED_DOOR;
    }

    /**
     * {@code web-start-seats} - Seats per address + All skills through the
     * REAL {@code POST /api/start} over the rig's package props: a crafted {@code seatsPerSource=abc} is refused with
     * the Seats-per-address sentence before anything is written or spawned; {@code seatsPerSource=3&allSkills=off}
     * reaches the spawn and writes {@code moderation.maxSeatsPerSource=3} + {@code server.officerSkillsAll=false} (a
     * props file lacking the rows gets each with its comment: the first Start from a window that has the fields
     * APPENDS them), and {@code /api/config} then serves {@code "3"} / {@code "off"}. Flips if the console's Start
     * drops the two parsed fields again. The props bytes are restored in {@code finally}.
     */
    private static String startSeatsRow(Rig rig) throws Exception {
        String r = "web-start-seats";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        String fields = startFields("match-web-pkg.txt");
        AtomicReference<byte[]> prior = new AtomicReference<>();
        setup(r, () -> {
            awaitStaging();
            prior.set(Files.readAllBytes(props));
        });
        Resp refused;
        Resp set;
        Resp config1;
        int spawns0;
        int spawns1;
        int spawns2;
        byte[] afterRefused;
        List<String> afterSet;
        try {
            spawns0 = rig.spawned.size();
            refused = post(rig, "/api/start", fields + "&seatsPerSource=abc&allSkills=off");
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterRefused = Files.readAllBytes(props);
            set = post(rig, "/api/start", fields + "&seatsPerSource=3&allSkills=off");
            awaitStaging();
            spawns2 = rig.spawned.size();
            afterSet = Files.readAllLines(props, StandardCharsets.UTF_8);
            config1 = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            setup(r, () -> Files.write(props, prior.get()));
        }
        String before = new String(prior.get(), StandardCharsets.UTF_8);
        boolean lacked = !before.contains(OpsMatchRows.SEATS_PER_SOURCE_KEY + "=")
                && !before.contains(OpsMatchRows.ALL_SKILLS_KEY + "=");
        List<String> bad = new ArrayList<>();
        if (!refused.body().contains(OpsMatchRows.R_SEATS_PER_SOURCE) || spawns1 != spawns0
                || !Arrays.equals(prior.get(), afterRefused)) {
            bad.add("seatsPerSource=abc " + PeerText.bound(refused.body(), 120) + " spawns " + spawns0 + "/" + spawns1
                    + " (flips if a crafted value is ignored, written or spawned)");
        }
        if (!set.body().startsWith("{\"ok\":true") || spawns2 <= spawns1
                || !afterSet.contains(OpsMatchRows.SEATS_PER_SOURCE_KEY + "=3")
                || !afterSet.contains(OpsMatchRows.ALL_SKILLS_KEY + "=false")
                || (lacked && !(afterSet.contains(OpsMatchRows.SEATS_PER_SOURCE_COMMENT)
                        && afterSet.contains(OpsMatchRows.ALL_SKILLS_COMMENT)))) {
            bad.add("3 / off " + PeerText.bound(set.body(), 120) + " spawns " + spawns1 + "/" + spawns2 + " lacked="
                    + lacked + " (flips if the console's Start drops Seats per address / All skills again)");
        }
        if (!config1.body().contains("\"seatsPerSource\":\"3\"") || !config1.body().contains("\"allSkills\":\"off\"")) {
            bad.add("config after " + PeerText.bound(config1.body(), 80) + " (the written rows stop prefilling)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-rngpins} - the replay-pins word through the REAL
     * {@code POST /api/start} over the rig's package props: a crafted {@code rngPins=maybe} is refused with the
     * replay-pins sentence before anything is written or spawned; {@code rngPins=off} reaches the spawn and writes
     * {@code rngpin.enabled=false} exactly once (no launch argument: the serve's own startup reads the file), and
     * {@code /api/config} then serves {@code "off"}. Flips if the form field, the page payload's key, the console's
     * StartFields rebuild or the flow's upsert drops the word. The props bytes are restored in {@code finally}.
     */
    private static String startRngPinsRow(Rig rig) throws Exception {
        String r = "web-start-rngpins";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        String fields = startFields("match-web-pkg.txt");
        AtomicReference<byte[]> prior = new AtomicReference<>();
        setup(r, () -> {
            awaitStaging();
            prior.set(Files.readAllBytes(props));
        });
        Resp refused;
        Resp set;
        Resp config1;
        int spawns0;
        int spawns1;
        int spawns2;
        byte[] afterRefused;
        List<String> afterSet;
        try {
            spawns0 = rig.spawned.size();
            refused = post(rig, "/api/start", fields + "&rngPins=maybe");
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterRefused = Files.readAllBytes(props);
            set = post(rig, "/api/start", fields + "&rngPins=off");
            awaitStaging();
            spawns2 = rig.spawned.size();
            afterSet = Files.readAllLines(props, StandardCharsets.UTF_8);
            config1 = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            setup(r, () -> Files.write(props, prior.get()));
        }
        List<String> bad = new ArrayList<>();
        if (!refused.body().contains(OpsMatchRows.R_RNG_PINS) || spawns1 != spawns0
                || !Arrays.equals(prior.get(), afterRefused)) {
            bad.add("rngPins=maybe " + PeerText.bound(refused.body(), 120) + " spawns " + spawns0 + "/" + spawns1
                    + " (flips if a crafted word is ignored, written or spawned)");
        }
        long pinRows = afterSet.stream().filter(l -> l.startsWith(OpsMatchRows.RNG_PINS_KEY + "=")).count();
        if (!set.body().startsWith("{\"ok\":true") || spawns2 <= spawns1 || pinRows != 1
                || !afterSet.contains(OpsMatchRows.RNG_PINS_KEY + "=false")) {
            bad.add("off " + PeerText.bound(set.body(), 120) + " spawns " + spawns1 + "/" + spawns2 + " rows=" + pinRows
                    + " (flips if the console's Start drops the replay-pins word)");
        }
        if (!config1.body().contains("\"rngPins\":\"off\"")) {
            bad.add("config after " + PeerText.bound(config1.body(), 80) + " (the written row stops prefilling)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-magicseam} - the MagicLib word through the REAL
     * {@code POST /api/start} over the rig's package props (the {@code web-start-rngpins} shape): a crafted
     * {@code magicSeam=maybe} is refused with the MagicLib sentence before anything is written or spawned;
     * {@code magicSeam=off} reaches the spawn and writes {@code magicseam.enabled=false} exactly once (no launch
     * argument: the serve's own startup reads the file) while the replay-pins rows stay byte-for-byte as they were (a
     * word landing in the neighbouring StartFields slot flips that), and {@code /api/config} then serves
     * {@code "magicSeam":"off"}. The props bytes are restored in {@code finally}.
     */
    private static String startMagicSeamRow(Rig rig) throws Exception {
        String r = "web-start-magicseam";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        String fields = startFields("match-web-pkg.txt");
        AtomicReference<byte[]> prior = new AtomicReference<>();
        setup(r, () -> {
            awaitStaging();
            prior.set(Files.readAllBytes(props));
        });
        Resp refused;
        Resp set;
        Resp config1;
        int spawns0;
        int spawns1;
        int spawns2;
        byte[] afterRefused;
        List<String> before;
        List<String> afterSet;
        try {
            before = Files.readAllLines(props, StandardCharsets.UTF_8);
            spawns0 = rig.spawned.size();
            refused = post(rig, "/api/start", fields + "&magicSeam=maybe");
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterRefused = Files.readAllBytes(props);
            set = post(rig, "/api/start", fields + "&magicSeam=off");
            awaitStaging();
            spawns2 = rig.spawned.size();
            afterSet = Files.readAllLines(props, StandardCharsets.UTF_8);
            config1 = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            setup(r, () -> Files.write(props, prior.get()));
        }
        List<String> bad = new ArrayList<>();
        if (!refused.body().contains(OpsMatchRows.R_MAGIC_SEAM) || spawns1 != spawns0
                || !Arrays.equals(prior.get(), afterRefused)) {
            bad.add("magicSeam=maybe " + PeerText.bound(refused.body(), 120) + " spawns " + spawns0 + "/" + spawns1
                    + " (flips if a crafted word is ignored, written or spawned)");
        }
        long seamRows = afterSet.stream().filter(l -> l.startsWith(OpsMatchRows.MAGIC_SEAM_KEY + "=")).count();
        if (!set.body().startsWith("{\"ok\":true") || spawns2 <= spawns1 || seamRows != 1
                || !afterSet.contains(OpsMatchRows.MAGIC_SEAM_KEY + "=false")) {
            bad.add("off " + PeerText.bound(set.body(), 120) + " spawns " + spawns1 + "/" + spawns2 + " rows=" + seamRows
                    + " (flips if the console's Start drops the MagicLib word)");
        }
        List<String> pinsBefore = before.stream().filter(l -> l.startsWith(OpsMatchRows.RNG_PINS_KEY + "=")).toList();
        List<String> pinsAfter = afterSet.stream().filter(l -> l.startsWith(OpsMatchRows.RNG_PINS_KEY + "=")).toList();
        if (!pinsBefore.equals(pinsAfter)) {
            bad.add("replay-pins rows " + pinsBefore + " -> " + pinsAfter
                    + " (flips if the MagicLib word is written through the replay-pins slot)");
        }
        if (!config1.body().contains("\"magicSeam\":\"off\"")) {
            bad.add("config after " + PeerText.bound(config1.body(), 80) + " (the written row stops prefilling)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-pilotship} - the player-ship word through the REAL
     * {@code POST /api/start} over the rig's package props (the {@code web-start-magicseam} shape): a crafted
     * {@code pilotShip=maybe} is refused with the player-ship sentence before anything is written or spawned;
     * {@code pilotShip=on} sent beside {@code magicSeam=off} reaches the spawn and writes {@code pilotship.enabled=true}
     * exactly once and {@code magicseam.enabled=false} exactly once (no launch argument: the serve's own startup reads
     * the file) - crossed StartFields slots, or one word written through both keys, flip one of the two values - and
     * {@code /api/config} then serves {@code "pilotShip":"on"} (the written {@code =true} row is counted directly; an
     * absent row serves on too, the shipped default). The props bytes are restored in
     * {@code finally}.
     */
    private static String startPilotShipRow(Rig rig) throws Exception {
        String r = "web-start-pilotship";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        String fields = startFields("match-web-pkg.txt");
        AtomicReference<byte[]> prior = new AtomicReference<>();
        setup(r, () -> {
            awaitStaging();
            prior.set(Files.readAllBytes(props));
        });
        Resp refused;
        Resp set;
        Resp config1;
        int spawns0;
        int spawns1;
        int spawns2;
        byte[] afterRefused;
        List<String> afterSet;
        try {
            spawns0 = rig.spawned.size();
            refused = post(rig, "/api/start", fields + "&pilotShip=maybe");
            awaitStaging();
            spawns1 = rig.spawned.size();
            afterRefused = Files.readAllBytes(props);
            set = post(rig, "/api/start", fields + "&pilotShip=on&magicSeam=off");
            awaitStaging();
            spawns2 = rig.spawned.size();
            afterSet = Files.readAllLines(props, StandardCharsets.UTF_8);
            config1 = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            setup(r, () -> Files.write(props, prior.get()));
        }
        List<String> bad = new ArrayList<>();
        if (!refused.body().contains(OpsMatchRows.R_PILOT_SHIP) || spawns1 != spawns0
                || !Arrays.equals(prior.get(), afterRefused)) {
            bad.add("pilotShip=maybe " + PeerText.bound(refused.body(), 120) + " spawns " + spawns0 + "/" + spawns1
                    + " (flips if a crafted word is ignored, written or spawned)");
        }
        long pilotRows = afterSet.stream().filter(l -> l.startsWith(OpsMatchRows.PILOT_SHIP_KEY + "=")).count();
        if (!set.body().startsWith("{\"ok\":true") || spawns2 <= spawns1 || pilotRows != 1
                || !afterSet.contains(OpsMatchRows.PILOT_SHIP_KEY + "=true")) {
            bad.add("on " + PeerText.bound(set.body(), 120) + " spawns " + spawns1 + "/" + spawns2 + " rows=" + pilotRows
                    + " (flips if the ops GUI's Start drops the player-ship word)");
        }
        long magicRows = afterSet.stream().filter(l -> l.startsWith(OpsMatchRows.MAGIC_SEAM_KEY + "=")).count();
        if (magicRows != 1 || !afterSet.contains(OpsMatchRows.MAGIC_SEAM_KEY + "=false")) {
            bad.add("MagicLib rows=" + magicRows + " (flips if the player-ship word is written through the MagicLib"
                    + " slot, or the two slots are crossed)");
        }
        if (!config1.body().contains("\"pilotShip\":\"on\"")) {
            bad.add("config after " + PeerText.bound(config1.body(), 80) + " (the written row stops prefilling)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-pvp} - PvP mode through the REAL {@code POST /api/start}
     * on the rig's package (the loopback console): a redacted 4-player fortress pair {@code match/web-pvp.txt} (its
     * sidecar keyed to its id) is staged and the status file taken aside. {@code /api/config} first serves
     * {@code "pvp":"off"} (the standing 1v1 is selected); {@code pvp=maybe} picking the pair is refused with
     * {@link OpsMatchApply#PVP_WORD} with NO spawn, the props and the pair byte-identical and no password file written;
     * {@code pvp=on} (every confirm sent) reaches the spawn and re-mints the pair as the PvP shape (4 players kept, a
     * NEW match.id, the sidecar re-keyed to it), and {@code /api/config} then serves {@code "pvp":"on"} for the now
     * selected file. Flips if the payload's word stops reaching the Start parse, a crafted word writes or spawns, the
     * switch is skipped, or the config member stops reading the selected file's shape. The props, the status file and
     * the pair are restored in {@code finally}.
     */
    private static String startPvpRow(Rig rig) throws Exception {
        String r = "web-start-pvp";
        Path props = rig.roots.config();
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        Path file = dir.resolve("web-pvp.txt");
        Path sidecar = dir.resolve("web-pvp.txt.fingerprint");
        Path password = dir.resolve("web-pvp.txt.password");
        byte[][] saved = {null, null};
        boolean[] dirExisted = {false};
        MatchDescriptor[] staged = {null};
        setup(r, () -> {
            awaitStaging();
            saved[0] = Files.readAllBytes(props);
            saved[1] = takeFile(rig.roots.opsFile());
            dirExisted[0] = Files.isDirectory(dir);
            staged[0] = stageMatchPair(rig, "web-pvp.txt", MatchDescriptors.redact(MatchDescriptors.mintPlayers(
                    MatchDescriptors.FORTRESS_SHAPE, 4, 7000f, 0.6f)));
        });
        String fields = startFields(OpsMatchApply.MATCH_DIR + "/web-pvp.txt");
        Resp config0;
        Resp refused;
        Resp on;
        Resp config1;
        int spawns0;
        int spawns1;
        int spawns2;
        boolean unwritten;
        MatchDescriptor after;
        String sideId;
        try {
            byte[] props0 = Files.readAllBytes(props);
            byte[] file0 = Files.readAllBytes(file);
            byte[] side0 = Files.readAllBytes(sidecar);
            config0 = call(rig, "GET", "/api/config", rig.boot.token(), null);
            spawns0 = rig.spawned.size();
            refused = post(rig, "/api/start", fields + "&pvp=maybe&pvpStation=-");
            awaitStaging();
            spawns1 = rig.spawned.size();
            unwritten = Arrays.equals(props0, Files.readAllBytes(props)) && Arrays.equals(file0, Files.readAllBytes(file))
                    && Arrays.equals(side0, Files.readAllBytes(sidecar)) && !Files.exists(password);
            on = postConfirmed(rig, "/api/start", fields + "&pvp=on&pvpStation=-");
            awaitStaging();
            spawns2 = rig.spawned.size();
            after = MatchDescriptorCodec.decode(Files.readAllLines(file, StandardCharsets.UTF_8));
            sideId = sidecarMatchId(sidecar);
            config1 = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            setup(r, () -> {
                Files.write(props, saved[0]);
                restoreFile(rig.roots.opsFile(), saved[1]);
                if (dirExisted[0]) {
                    for (Path p : List.of(file, sidecar, password)) {
                        Files.deleteIfExists(p);
                    }
                } else {
                    deleteTree(dir);
                }
            });
        }
        List<String> bad = new ArrayList<>();
        if (!config0.body().contains("\"pvp\":\"off\"")) {
            bad.add("config before " + PeerText.bound(config0.body(), 80) + " (flips if the pvp member is not served"
                    + " off for a selected non-PvP file)");
        }
        if (!refused.body().contains(OpsMatchApply.PVP_WORD) || spawns1 != spawns0 || !unwritten) {
            bad.add("pvp=maybe " + PeerText.bound(refused.body(), 120) + " spawns " + spawns0 + "/" + spawns1
                    + " unwritten=" + unwritten + " (flips if a crafted word is ignored, writes a file or spawns)");
        }
        if (!on.body().startsWith("{\"ok\":true") || spawns2 <= spawns1
                || !MatchDescriptors.PVP_SHAPE.equals(after.shape()) || after.seats().size() != 4
                || staged[0].matchId().equals(after.matchId()) || !after.matchId().equals(sideId)) {
            bad.add("pvp=on " + PeerText.bound(on.body(), 120) + " spawns " + spawns1 + "/" + spawns2 + " shape "
                    + after.shape() + " seats " + after.seats().size() + " new id=" + !staged[0].matchId()
                    .equals(after.matchId()) + " sidecar keyed=" + after.matchId().equals(sideId) + " (flips if the"
                    + " page's word stops reaching the switch, the switch is skipped, or the pair is not re-minted)");
        }
        if (!config1.body().contains("\"pvp\":\"on\"")) {
            bad.add("config after " + PeerText.bound(config1.body(), 80) + " (flips if the pvp member stops reading"
                    + " the selected file's shape - the page would then prefill Off and the next Start switch back)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-descriptor-overcap} - a picked descriptor of
     * 262145 B (a decodable match padded with blank lines, so ONLY the read cap makes it unreadable) through the REAL
     * {@code POST /api/start}: the console's read refuses it ({@code cc-console descriptor_unreadable
     * class=IOException}, exactly once). Flips if the web Start's descriptor read is uncapped again. The flow then
     * boots the pick as-is (the null-info path: the serve's own capped boot read is the refusal), so its answer is
     * printed, never asserted. The props bytes and the file are restored in {@code finally}.
     */
    private static String startDescriptorOverCapRow(Rig rig) throws Exception {
        String r = "web-start-descriptor-overcap";
        Path props = rig.pkg.resolve(ServeLauncher.DEFAULT_CONFIG);
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        Path over = dir.resolve("web-over.txt");
        boolean[] dirExisted = {false};
        AtomicReference<byte[]> prior = new AtomicReference<>();
        setup(r, () -> {
            awaitStaging();
            prior.set(Files.readAllBytes(props));
            dirExisted[0] = Files.isDirectory(dir);
            Files.createDirectories(dir);
            byte[] text = MatchDescriptorCodec.encode(MatchDescriptors.mint("1v1", 9000f, 0.7f))
                    .getBytes(StandardCharsets.UTF_8);
            byte[] padded = Arrays.copyOf(text, (int) ServeLauncher.MAX_CONFIG_BYTES + 1);
            Arrays.fill(padded, text.length, padded.length, (byte) '\n');
            Files.write(over, padded);
            ServerOpsCore.parseDescriptorInfo(Files.readAllLines(over, StandardCharsets.UTF_8)); // decodable uncapped
        });
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Resp a;
        int spawns0;
        int spawns1;
        byte[] after;
        System.setOut(tee(priorOut, captured));
        try {
            spawns0 = rig.spawned.size();
            a = post(rig, "/api/start", startFields(OpsMatchApply.MATCH_DIR + "/web-over.txt"));
            awaitStaging();
            spawns1 = rig.spawned.size();
            after = Files.readAllBytes(props);
        } finally {
            System.setOut(priorOut);
            setup(r, () -> {
                Files.write(props, prior.get());
                Files.deleteIfExists(over);
                if (!dirExisted[0]) {
                    deleteTree(dir);
                }
            });
        }
        List<String> bad = new ArrayList<>();
        List<String> rows = rowsOf(captured, "cc-console descriptor_unreadable ");
        if (!rows.equals(List.of("class=IOException"))) {
            bad.add("descriptor_unreadable rows " + rows + " (flips if the web Start's descriptor read is uncapped"
                    + " again - the padded file then decodes)");
        }
        System.out.println("web-start-descriptor-overcap flow answer " + PeerText.bound(a.body(), 40) + " spawns "
                + spawns0 + "/" + spawns1 + " propsChanged=" + !Arrays.equals(prior.get(), after));
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-shape-new-players} - New match shape's Players through the
     * REAL {@code POST /api/shape-new} on the rig's package (the {@code web-shape-new} staging: the props row on a
     * staged 1v1 + its sidecar, the status file aside, all restored in {@code finally}); bases: {@code teams:2:2} and
     * {@code bots:3} (one seat). {@code players=40} on the teams base writes a file that decodes to 40 seats; ABSENT
     * players writes the base's own count; {@code 0} / {@code 256} / {@code abc} / a 17-char value that WOULD parse as
     * 40 are R9 (the 16-char cap runs before the parse) with the folder unchanged; {@code 3} on the one-seat base is
     * T6's players range. Stdout = exactly those seven grammar rows (reason {@code players} for all five refusals).
     */
    private static String shapeNewPlayersRow(Rig rig) throws Exception {
        String r = "web-shape-new-players";
        Path props = rig.roots.config();
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        byte[][] saved = {null, null};
        MatchDescriptor[] bases = {null, null};
        setup(r, () -> {
            awaitStaging();
            saved[0] = Files.readAllBytes(props);
            String t = new String(saved[0], StandardCharsets.UTF_8);
            if (!t.contains("match.descriptor=match-web-pkg.txt")) {
                throw new IllegalStateException("the rig's props row is not match-web-pkg.txt");
            }
            stageMatchPair(rig, "match-ws.txt", MatchDescriptors.mint("1v1", 9000f, 0.7f));
            bases[0] = stageMatchPair(rig, "match-wt.txt", MatchDescriptors.mint("teams:2:2", 9000f, 0.7f));
            bases[1] = stageMatchPair(rig, "match-wb.txt", MatchDescriptors.mint("bots:3", 9000f, 0.7f));
            Files.writeString(props, t.replace("match.descriptor=match-web-pkg.txt",
                    "match.descriptor=match/match-ws.txt"), StandardCharsets.UTF_8);
            saved[1] = takeFile(rig.roots.opsFile());
        });
        String title = OpsConsole.SHAPE_NEW_TITLE;
        String teams = "&base=" + enc("match/match-wt.txt") + "&half=12000&cr=55&terrain=nebula";
        List<String> bad = new ArrayList<>();
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(tee(prior, captured));
        try {
            Resp forty = post(rig, "/api/shape-new", "name=wp40" + teams + "&players=40");
            Resp absent = post(rig, "/api/shape-new", "name=wpabs" + teams);
            int fortySeats = seatsOf(dir.resolve("match-wp40.txt"));
            int absentSeats = seatsOf(dir.resolve("match-wpabs.txt"));
            if (!forty.body().startsWith("{\"ok\":true") || fortySeats != 40) {
                bad.add("players=40 -> " + PeerText.bound(forty.body(), 100) + " seats " + fortySeats + " (flips if"
                        + " the route drops the players value on its way to MatchShapes.create)");
            }
            if (!absent.body().startsWith("{\"ok\":true") || absentSeats != bases[0].seats().size()) {
                bad.add("absent players -> " + PeerText.bound(absent.body(), 100) + " seats " + absentSeats + " (want "
                        + bases[0].seats().size() + "; flips if an older page that sends no players stops getting"
                        + " the base's count)");
            }
            List<String> before = listNames(dir);
            String p17 = "00000000000000040";
            for (String x : new String[] {"0", "256", "abc", p17}) {
                Resp a = post(rig, "/api/shape-new", "name=wpbad" + teams + "&players=" + enc(x));
                String want = OpsConsoleForms.errorJson(title, OpsMatchApply.R9_PLAYERS.replace("<x>",
                        OpsMatchApply.shown(x)));
                if (!want.equals(a.body()) || !before.equals(listNames(dir))) {
                    bad.add("players '" + x + "' -> " + PeerText.bound(a.body(), 100) + " (flips if a bad count, or"
                            + " one past 16 chars, is parsed or written)");
                }
            }
            int[] range = MatchDescriptors.playersRange(bases[1].shape());
            String t6 = cc.starsector.server.match.MatchShapes.CREATE_REFUSED
                    + cc.starsector.server.match.MatchShapes.T6_PLAYERS.replace("<shape>", bases[1].shape())
                            .replace("<min>", Integer.toString(range[0])).replace("<max>", Integer.toString(range[1]));
            Resp one = post(rig, "/api/shape-new", "name=wpone&base=" + enc("match/match-wb.txt")
                    + "&half=12000&cr=55&terrain=nebula&players=3");
            if (!OpsConsoleForms.errorJson(title, t6).equals(one.body()) || !before.equals(listNames(dir))) {
                bad.add("3 on a one-seat base -> " + PeerText.bound(one.body(), 100) + " (flips if T6's players range"
                        + " stops refusing, or writes)");
            }
        } finally {
            System.setOut(prior);
            setup(r, () -> {
                restoreFile(rig.roots.opsFile(), saved[1]);
                if (saved[0] != null) {
                    Files.write(props, saved[0]);
                }
                deleteTree(dir);
            });
        }
        List<String> rows = rowsOf(captured, "cc-console shape-new ");
        List<String> want = List.of("result=created shape=match-wp40", "result=created shape=match-wpabs",
                "result=refused reason=players", "result=refused reason=players", "result=refused reason=players",
                "result=refused reason=players", "result=refused reason=players");
        if (!want.equals(rows)) {
            bad.add("stdout rows " + rows + " (flips if a players refusal answers another token, e.g. createToken's"
                    + " 'other', or a value reaches stdout)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** A written shape file's decoded seat count, -1 when it is missing or does not decode. */
    private static int seatsOf(Path file) {
        try {
            return MatchDescriptorCodec.decode(Files.readAllLines(file, StandardCharsets.UTF_8)).seats().size();
        } catch (IOException | RuntimeException none) {
            return -1;
        }
    }

    /**
     * {@code web-shape-new} - New match shape ({@code POST
     * /api/shape-new}) over the REAL handler. The props row is pointed at a staged
     * {@code match/match-ws.txt} (+ its sidecar = the STANDING sidecar New re-keys); the status file is taken aside;
     * all restored in {@code finally}. Legs: no key = 403, GET = 405; then each refusal through the route with its
     * exact text and the folder unchanged: name {@code Bad} (T6 N7), {@code ws} (T6 exists), a 17-char half that
     * WOULD parse as 9000 (the 16-char cap runs BEFORE the parse), CR {@code 101} (R8), backdrop
     * {@code fog}, base {@code ../x} (R6), a held {@code serve.lock} (R1: New is pre-start); then {@code web-new1}
     * = created (T6's sentence), both files written, the shape's values = the form's, strictly redacted, its sidecar
     * re-keyed to the new id; then 62 more decodable shapes = T6 full (64). T6's ship belt is NOT reachable through
     * the route (the base is an offered decodable file, so the folder fields its ships; {@code MatchShapes} mints the
     * same roster) - {@code MatchShapesSelftest shape-new} pins it. Stdout = the nine grammar rows in order, never a
     * value, an id or a path.
     */
    private static String shapeNewRow(Rig rig) throws Exception {
        String r = "web-shape-new";
        Path props = rig.roots.config();
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        Path lock = rig.pkg.resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.LOCK_FILE);
        byte[][] saved = {null, null};
        boolean[] lockExisted = {false};
        setup(r, () -> {
            awaitStaging();
            saved[0] = Files.readAllBytes(props);
            String t = new String(saved[0], StandardCharsets.UTF_8);
            if (!t.contains("match.descriptor=match-web-pkg.txt")) {
                throw new IllegalStateException("the rig's props row is not match-web-pkg.txt");
            }
            stageMatchPair(rig, "match-ws.txt", MatchDescriptors.mint("1v1", 9000f, 0.7f));
            Files.writeString(props, t.replace("match.descriptor=match-web-pkg.txt",
                    "match.descriptor=match/match-ws.txt"), StandardCharsets.UTF_8);
            lockExisted[0] = Files.exists(lock);
            saved[1] = takeFile(rig.roots.opsFile());
        });
        String title = OpsConsole.SHAPE_NEW_TITLE;
        String base = "&base=" + enc("match/match-ws.txt");
        String good = "&half=12000&cr=55&terrain=nebula";
        List<String> bad = new ArrayList<>();
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String newId = null;
        System.setOut(tee(prior, captured));
        try {
            Resp keyless = call(rig, "POST", "/api/shape-new", null, "name=k1" + base + good);
            Resp get = call(rig, "GET", "/api/shape-new", rig.boot.token(), null);
            if (keyless.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(keyless.body()) || get.code() != 405) {
                bad.add("keyless -> " + keyless.code() + ", keyed GET -> " + get.code()
                        + " (flips if the route loses its key guard or stops refusing GET)");
            }
            List<String> before = listNames(dir);
            String half17 = "00000000000009000";
            String[][] refusals = {
                {"name Bad", "name=Bad" + base + good, cc.starsector.server.match.MatchShapes.CREATE_REFUSED
                    + cc.starsector.server.match.MatchShapes.T6_NAME},
                {"name ws", "name=ws" + base + good, "Create refused: ws already exists"},
                {"a 17-char half", "name=n1" + base + "&half=" + half17 + "&cr=55&terrain=nebula",
                    OpsMatchApply.R3_MAP_SIZE.replace("<x>", OpsMatchApply.shown(half17))},
                {"cr 101", "name=n1" + base + "&half=12000&cr=101&terrain=nebula",
                    OpsMatchApply.R8_DEPLOY_CR.replace("<x>", "101")},
                {"backdrop fog", "name=n1" + base + "&half=12000&cr=55&terrain=fog",
                    OpsMatchApply.BACKDROP_WORD.replace("<x>", "fog")},
                {"base ../x", "name=n1&base=" + enc("../x") + good, OpsMatchApply.R6_NOT_OFFERED}};
            for (String[] leg : refusals) {
                Resp a = post(rig, "/api/shape-new", leg[1]);
                if (!OpsConsoleForms.errorJson(title, leg[2]).equals(a.body()) || !before.equals(listNames(dir))) {
                    bad.add(leg[0] + " -> " + PeerText.bound(a.body(), 120) + " (flips if that refusal drifts from its"
                            + " text, a value past 16 chars is parsed, or a refusal writes)");
                }
            }
            Resp live;
            Files.createDirectories(lock.getParent());
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock held = ch.lock()) {
                live = post(rig, "/api/shape-new", "name=n1" + base + good);
            }
            if (!OpsConsoleForms.errorJson(title, OpsMatchApply.R1_LIVE).equals(live.body())
                    || !before.equals(listNames(dir))) {
                bad.add("held serve.lock -> " + PeerText.bound(live.body(), 80) + " (flips if New loses its live gate)");
            }
            Resp ok = post(rig, "/api/shape-new", "name=web-new1" + base + good);
            Path file = dir.resolve("match-web-new1.txt");
            Path side = dir.resolve("match-web-new1.txt.fingerprint");
            boolean shaped = false;
            if (Files.isRegularFile(file) && Files.isRegularFile(side)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                MatchDescriptor made = MatchDescriptorCodec.decode(lines);
                newId = made.matchId();
                shaped = MatchDescriptors.isStrictlyRedacted(lines) && made.halfWidth() == 12000f
                        && made.deployCr() == 0.55f && "nebula".equals(made.terrain()) && newId.equals(sidecarMatchId(side));
            }
            if (!OpsConsoleForms.shapeTextJson("match-web-new1", "created web-new1 - pick it and press Start; every"
                    + " join window fetches it.").equals(ok.body()) || !shaped) {
                bad.add("created -> " + PeerText.bound(ok.body(), 120) + " files shaped=" + shaped + " (flips if the"
                        + " route stops reaching MatchShapes.create with the parsed values + the standing sidecar, or the"
                        + " T6_CREATED text drifts)");
            }
            String one = MatchDescriptorCodec.encode(MatchDescriptors.redact(MatchDescriptors.mint("1v1", 9000f, 0.7f)));
            for (int i = 0; i < 62; i++) {
                Files.writeString(dir.resolve(String.format(Locale.ROOT, "f%02d.txt", i)), one, StandardCharsets.UTF_8);
            }
            List<String> fullBefore = listNames(dir);
            Resp full = post(rig, "/api/shape-new", "name=n2" + base + good);
            if (!OpsConsoleForms.errorJson(title, cc.starsector.server.match.MatchShapes.CREATE_REFUSED
                    + cc.starsector.server.match.MatchShapes.T6_FULL).equals(full.body())
                    || !fullBefore.equals(listNames(dir))) {
                bad.add("64 shapes -> " + PeerText.bound(full.body(), 100) + " (flips if N2 stops refusing at 64)");
            }
        } finally {
            System.setOut(prior);
            setup(r, () -> {
                if (!lockExisted[0]) {
                    Files.deleteIfExists(lock);
                }
                restoreFile(rig.roots.opsFile(), saved[1]);
                if (saved[0] != null) {
                    Files.write(props, saved[0]);
                }
                deleteTree(dir);
            });
        }
        List<String> rows = rowsOf(captured, "cc-console shape-new ");
        List<String> want = List.of("result=refused reason=name", "result=refused reason=exists",
                "result=refused reason=map_size", "result=refused reason=deploy_cr", "result=refused reason=backdrop_word",
                "result=refused reason=not_offered", "result=refused reason=server_live",
                "result=created shape=match-web-new1", "result=refused reason=full");
        String out = captured.toString(StandardCharsets.UTF_8);
        boolean leaked = out.contains("12000") || out.contains(rig.pkg.toString()) || out.contains("../x")
                || (newId != null && out.contains(newId));
        if (!want.equals(rows) || leaked) {
            bad.add("stdout rows " + rows + " leaked=" + leaked + " (flips if a POST prints no row or two, the grammar"
                    + " drifts, or a row carries a value, an id or a path)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** A folder's entry names, sorted ({@code []} when absent). */
    private static List<String> listNames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * {@code web-start-keeps-password} - the kept-password Start
     * through the REAL Start handler on the package rig. The standing row is A ({@code match-web-pkg.txt}, its
     * sidecar = the planted secret); a pair B ({@code match/web-kb.txt}) has NO sidecar. A keep Start (blank
     * password) picking B REACHES the spawn and writes B's {@code .password} = A's password (the ONE kept
     * password beside the pick), A's sidecar bytes unchanged; the keep read ran under {@code startLock}
     * ({@link OpsConsole#KEPT_READS} advanced, {@link OpsConsole#KEPT_READS_UNLOCKED} did not - the seam a reader
     * moved outside the lock flips). Props, the status file and {@code match/} are restored in {@code finally}.
     */
    private static String startKeepsPasswordRow(Rig rig) throws Exception {
        String r = "web-start-keeps-password";
        Path props = rig.roots.config();
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        byte[][] saved = {null, null};
        setup(r, () -> {
            awaitStaging();
            saved[0] = Files.readAllBytes(props);
            saved[1] = takeFile(rig.roots.opsFile());
            stageMatchPair(rig, "web-kb.txt", MatchDescriptors.mint("1v1", 9000f, 0.7f));
        });
        byte[] a0 = Files.readAllBytes(rig.sidecar);
        int reads0 = OpsConsole.KEPT_READS.get();
        int unlocked0 = OpsConsole.KEPT_READS_UNLOCKED.get();
        int spawns0 = rig.spawned.size();
        Resp keep;
        String bPassword;
        try {
            keep = postConfirmed(rig, "/api/start", "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400"
                    + "&descriptor=" + enc("match/web-kb.txt") + "&passwordAction=keep&password=&confirmed="
                    + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed=" + ServerOpsCore.CONFIRM_ARMED_DOOR);
            awaitStaging();
            bPassword = ServerOpsCore.sidecarPrefill(props, "match/web-kb.txt.password");
        } finally {
            setup(r, () -> {
                Files.write(props, saved[0]);
                restoreFile(rig.roots.opsFile(), saved[1]);
                deleteTree(dir);
            });
        }
        int reads = OpsConsole.KEPT_READS.get() - reads0;
        int unlocked = OpsConsole.KEPT_READS_UNLOCKED.get() - unlocked0;
        boolean aKept = Arrays.equals(a0, Files.readAllBytes(rig.sidecar));
        if (!keep.body().startsWith("{\"ok\":true") || rig.spawned.size() <= spawns0 || !SECRET.equals(bPassword)
                || !aKept || reads < 1 || unlocked != 0) {
            return "keep Start on B -> " + PeerText.bound(keep.body(), 160) + " spawns " + spawns0 + "/"
                    + rig.spawned.size() + " B's password is A's=" + SECRET.equals(bPassword) + " A kept=" + aKept
                    + " kept reads=" + reads + " outside the lock=" + unlocked + " (flips if a package keep resolves the"
                    + " PICKED shape's sidecar instead of the ONE kept password, or the keep read leaves startLock)";
        }
        return null;
    }

    /**
     * {@code web-reveal}'s ROTATE-then-reveal leg.
     * A Start that types a NEW password on A (the standing row) rotates the one package password; Show saved
     * passwords then answers the NEW one for A, for an offered pair B ({@code match/web-r.txt}, no sidecar of its
     * own) and for the blank pick; a {@code -tokens} twin name (real bytes) is still refused (the offered bound).
     * A's sidecar, the status file and {@code match/} are restored in {@code finally}.
     */
    private static List<String> revealRotateLeg(Rig rig) throws Exception {
        String r = "web-reveal";
        String rotated = "rotated-fixture-91";
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        byte[][] saved = {null, null};
        setup(r, () -> {
            awaitStaging();
            saved[0] = Files.readAllBytes(rig.sidecar);
            saved[1] = takeFile(rig.roots.opsFile());
            MatchDescriptor d = stageMatchPair(rig, "web-r.txt", MatchDescriptors.mint("1v1", 9000f, 0.7f));
            Files.writeString(dir.resolve("web-r-tokens.txt"), MatchDescriptorCodec.encode(d), StandardCharsets.UTF_8);
        });
        List<String> bad = new ArrayList<>();
        try {
            Resp start = postConfirmed(rig, "/api/start", "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400"
                    + "&descriptor=match-web-pkg.txt&passwordAction=replace&password=" + rotated + "&confirmed="
                    + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed=" + ServerOpsCore.CONFIRM_ARMED_DOOR);
            awaitStaging();
            String want = "\"password\":\"" + rotated + "\"";
            for (String pick : List.of("match-web-pkg.txt", "match/web-r.txt", "")) {
                Resp a = post(rig, "/api/reveal", "descriptor=" + enc(pick) + "&confirmed=reveal");
                if (!a.body().startsWith("{\"ok\":true," + want)) {
                    bad.add("after the rotate, pick '" + pick + "' -> " + PeerText.bound(a.body().replace(rotated, "<new>")
                            .replace(SECRET, "<old>"), 80) + " start=" + PeerText.bound(start.body(), 60) + " (flips if a"
                            + " package reveal reads the PICKED shape's sidecar instead of the one kept password)");
                }
            }
            Resp twin = post(rig, "/api/reveal", "descriptor=" + enc("match/web-r-tokens.txt") + "&confirmed=reveal");
            if (!twin.body().contains(OpsConsole.REVEAL_UNKNOWN_SHAPE) || twin.body().contains(rotated)) {
                bad.add("the -tokens twin -> " + PeerText.bound(twin.body(), 60) + " (flips if the reveal's offered set"
                        + " stops being the picker's capped set)");
            }
        } finally {
            setup(r, () -> {
                Files.write(rig.sidecar, saved[0]);
                restoreFile(rig.roots.opsFile(), saved[1]);
                deleteTree(dir);
            });
        }
        return bad;
    }

    /** The sidecar's {@code match=} component (null = none). */
    private static String sidecarMatchId(Path sidecar) throws IOException {
        return ContentFingerprints.componentOrNull(ContentFingerprints.parseSidecarFingerprint(
                Files.readAllLines(sidecar, StandardCharsets.ISO_8859_1), "fixture sidecar"), "match");
    }

    /**
     * {@code POST /api/match-settings} over the REAL handler
     * on the rig's package. The pair {@code match/web-a.txt} (+ its sidecar)
     * is staged; the status file is taken aside (an earlier row's live plant names THIS JVM = a live serve)
     * and everything is restored in {@code finally}. Legs: no key = 403; a keyed GET = 405; then (each a
     * keyed POST that reaches the handler) an LF in {@code value} = the control-char refusal; {@code op=bogus}
     * = {@link OpsConsole#MATCH_SETTINGS_BAD_OP}; {@code descriptor=../x} and the rig's own
     * {@code match-web-pkg.txt.password} = R6; then a POST with NO {@code confirmed} field = changed, a new
     * match.id in the file and the sidecar re-keyed to it (no confirm is asked first); the
     * same again = unchanged; the launcher's {@code run/serve.lock} held by THIS JVM (the
     * {@code OverlappingFileLockException} idiom {@code ServerOpsLaunch.lockHeld} reads as held) = R1; a
     * 17-char value = R3. Every refusal leaves both files byte-equal. Stdout = exactly the
     * eight grammar rows in order, and neither id, the values nor the file name.
     * No POST carries a {@code confirmed} field.
     */
    private static String matchSettingsRow(Rig rig) throws Exception {
        String r = "web-match-settings";
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        Path file = dir.resolve("web-a.txt");
        Path sidecar = dir.resolve("web-a.txt.fingerprint");
        Path lock = rig.pkg.resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.LOCK_FILE);
        MatchDescriptor[] fixture = {null};
        byte[][] status = {null};
        boolean[] lockExisted = {false};
        setup(r, () -> {
            awaitStaging();
            fixture[0] = stageMatchPair(rig, "web-a.txt", MatchDescriptors.mint("1v1", 9000f, 0.7f));
            lockExisted[0] = Files.exists(lock);
            status[0] = takeFile(rig.roots.opsFile());
        });
        String oldId = fixture[0].matchId();
        String path = "&descriptor=" + enc("match/web-a.txt");
        String title = OpsMatchApply.CONFIRM_TITLE;
        List<String> bad = new ArrayList<>();
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String newId = null;
        System.setOut(tee(prior, captured));
        try {
            Resp keyless = call(rig, "POST", "/api/match-settings", null, "op=size&value=15000" + path);
            Resp get = call(rig, "GET", "/api/match-settings", rig.boot.token(), null);
            if (keyless.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(keyless.body()) || get.code() != 405) {
                bad.add("keyless -> " + keyless.code() + ", keyed GET -> " + get.code()
                        + " (flips if the route loses its key guard or stops refusing GET)");
            }
            byte[] file0 = Files.readAllBytes(file);
            byte[] side0 = Files.readAllBytes(sidecar);
            String[][] refusals = {
                {"LF in value", "op=size&value=15%0A000" + path,
                    OpsConsoleForms.errorJson(title, OpsConsoleForms.CONTROL_CHAR)},
                {"op=bogus", "op=bogus&value=15000" + path,
                    OpsConsoleForms.errorJson(title, OpsConsole.MATCH_SETTINGS_BAD_OP)},
                {"../x", "op=size&value=15000&descriptor=" + enc("../x"),
                    OpsConsoleForms.errorJson(title, OpsMatchApply.R6_NOT_OFFERED)},
                {"a .password file", "op=size&value=15000&descriptor=" + enc("match-web-pkg.txt.password"),
                    OpsConsoleForms.errorJson(title, OpsMatchApply.R6_NOT_OFFERED)}};
            for (String[] leg : refusals) {
                Resp a = post(rig, "/api/match-settings", leg[1]);
                boolean kept = Arrays.equals(file0, Files.readAllBytes(file)) && Arrays.equals(side0, Files.readAllBytes(sidecar));
                if (!leg[2].equals(a.body()) || !kept) {
                    bad.add(leg[0] + " -> " + PeerText.bound(a.body(), 120) + " files kept=" + kept + " (flips if that"
                            + " refusal moves after a write, changes its text, or the offered bound is dropped)");
                }
            }
            // no confirmed field: the Apply must write on this one press (a confirm gate answers needsConfirm here)
            Resp changed = post(rig, "/api/match-settings", "op=size&value=15000" + path);
            MatchDescriptor now = MatchDescriptorCodec.decode(Files.readAllLines(file, StandardCharsets.UTF_8));
            newId = now.matchId();
            String keyed = sidecarMatchId(sidecar);
            // the note is OpsMatchApply.CHANGED with its tokens filled, typed here
            if (!changed.body().startsWith("{\"ok\":true,\"changed\":true,\"repaired\":false,\"note\":\"map size set to"
                    + " 15000. It applies from the next round, when you press Start; nothing is sent now - every player's"
                    + " next Join or Refresh fetches the new match.\"") || !changed.body().contains("\"matchTextChanged\":true,\"half\":\"15000\",\"station\":\"-\"")
                    || oldId.equals(newId) || now.halfWidth() != 15000f || !newId.equals(keyed)) {
                bad.add("applied -> " + PeerText.bound(changed.body(), 140) + " id rotated=" + !oldId.equals(newId)
                        + " half=" + now.halfWidth() + " sidecar keyed to the new id=" + newId.equals(keyed)
                        + " (flips if an Apply with no confirmed field asks a confirm or stops reaching the core, the"
                        + " answer shape drifts, or the sidecar is not re-keyed with the descriptor)");
            }
            byte[] file1 = Files.readAllBytes(file);
            byte[] side1 = Files.readAllBytes(sidecar);
            Resp again = post(rig, "/api/match-settings", "op=size&value=15000" + path);
            boolean againKept = Arrays.equals(file1, Files.readAllBytes(file)) && Arrays.equals(side1, Files.readAllBytes(sidecar));
            if (!again.body().startsWith("{\"ok\":true,\"changed\":false,\"repaired\":false,\"note\":\"web-a.txt: map size"
                    + " is already 15000.") || !again.body().contains("\"matchTextChanged\":false") || !againKept) {
                bad.add("same again -> " + PeerText.bound(again.body(), 120) + " files kept=" + againKept
                        + " (flips if an unchanged value re-mints or the answer claims a new match)");
            }
            Resp live;
            Files.createDirectories(lock.getParent());
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock held = ch.lock()) {
                live = post(rig, "/api/match-settings", "op=size&value=20000" + path);
            }
            Resp tooLong = post(rig, "/api/match-settings", "op=size&value=" + "1".repeat(17) + path);
            boolean refusedKept = Arrays.equals(file1, Files.readAllBytes(file)) && Arrays.equals(side1, Files.readAllBytes(sidecar));
            if (!OpsConsoleForms.errorJson(title, OpsMatchApply.R1_LIVE).equals(live.body())
                    || !tooLong.body().contains("Map size must be a whole number from 2000 to 60000") || !refusedKept) {
                bad.add("held serve.lock -> " + PeerText.bound(live.body(), 80) + " | 17 chars -> "
                        + PeerText.bound(tooLong.body(), 80) + " files kept=" + refusedKept + " (flips if the live gate"
                        + " stops reading the launcher lock / is not serveBusy, or a value past 16 chars is applied)");
            }
        } finally {
            System.setOut(prior);
            setup(r, () -> {
                if (!lockExisted[0]) {
                    Files.deleteIfExists(lock);
                }
                restoreFile(rig.roots.opsFile(), status[0]);
                deleteTree(dir);
            });
        }
        List<String> rows = new ArrayList<>();
        for (String l : captured.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (l.startsWith("cc-console match-settings ")) {
                rows.add(l.substring("cc-console match-settings ".length()));
            }
        }
        List<String> want = List.of("op=- result=refused:control_char", "op=- result=refused:op",
                "op=size result=refused:not_offered", "op=size result=refused:not_offered", "op=size result=changed",
                "op=size result=unchanged", "op=size result=refused:server_live", "op=size result=refused:map_size");
        String out = captured.toString(StandardCharsets.UTF_8);
        boolean leaked = out.contains(oldId) || (newId != null && out.contains(newId)) || out.contains("15000")
                || out.contains("20000") || out.contains("web-a") || out.contains(rig.pkg.toString());
        if (!want.equals(rows) || leaked) {
            bad.add("stdout rows " + rows + " leaked=" + leaked + " (flips if a POST prints no row or two, the"
                    + " grammar drifts, or a row carries a value, an id or a path)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-match-settings-players} - the {@code players} op word of
     * {@code POST /api/match-settings} over the REAL handler on the rig's package ({@code web-match-settings}'
     * staging: the {@code match/web-a.txt} pair, the status file taken aside, the held launcher lock; stdout teed;
     * all restored in {@code finally}). Legs: {@code op=players&value=5} = APPLIED on the one press (no
     * {@code confirmed} field, no confirm asked): 200 + {@code changed} with the core's filled
     * {@link OpsMatchApply#CHANGED} note, five seats, a new match.id and the sidecar re-keyed to it; then, each
     * keeping the bytes that Apply wrote, {@code value=0} = 200 + the core's {@link OpsMatchApply#R9_PLAYERS}
     * refusal; with {@code run/serve.lock} held = 200 + {@link OpsMatchApply#R1_LIVE}; {@code op=bogus} = 200 +
     * {@link OpsConsole#MATCH_SETTINGS_BAD_OP}, whose text names {@code players}. Stdout = exactly the four rows in
     * order and never the file name or the package path. Flips if {@code players} leaves the op map, a confirm is
     * asked again, a refusal answers 4xx / 500 or writes, or the unknown-op text stops naming players.
     * Leg 0 is the applied leg.
     */
    private static String matchSettingsPlayersRow(Rig rig) throws Exception {
        String r = "web-match-settings-players";
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        Path file = dir.resolve("web-a.txt");
        Path sidecar = dir.resolve("web-a.txt.fingerprint");
        Path lock = rig.pkg.resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.LOCK_FILE);
        byte[][] status = {null};
        boolean[] lockExisted = {false};
        MatchDescriptor[] fixture = {null};
        setup(r, () -> {
            awaitStaging();
            fixture[0] = stageMatchPair(rig, "web-a.txt", MatchDescriptors.mint("1v1", 9000f, 0.7f));
            lockExisted[0] = Files.exists(lock);
            status[0] = takeFile(rig.roots.opsFile());
        });
        String oldId = fixture[0].matchId();
        String path = "&descriptor=" + enc("match/web-a.txt");
        String title = OpsMatchApply.CONFIRM_TITLE;
        PrintStream prior = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Resp[] got = new Resp[4];
        MatchDescriptor applied;
        String keyed;
        boolean kept;
        System.setOut(tee(prior, captured));
        try {
            got[0] = post(rig, "/api/match-settings", "op=players&value=5" + path);
            applied = MatchDescriptorCodec.decode(Files.readAllLines(file, StandardCharsets.UTF_8));
            keyed = sidecarMatchId(sidecar);
            byte[] file1 = Files.readAllBytes(file);
            byte[] side1 = Files.readAllBytes(sidecar);
            got[1] = post(rig, "/api/match-settings", "op=players&value=0" + path);
            Files.createDirectories(lock.getParent());
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock held = ch.lock()) {
                got[2] = post(rig, "/api/match-settings", "op=players&value=5" + path);
            }
            got[3] = post(rig, "/api/match-settings", "op=bogus&value=5" + path);
            kept = Arrays.equals(file1, Files.readAllBytes(file)) && Arrays.equals(side1, Files.readAllBytes(sidecar));
        } finally {
            System.setOut(prior);
            setup(r, () -> {
                if (!lockExisted[0]) {
                    Files.deleteIfExists(lock);
                }
                restoreFile(rig.roots.opsFile(), status[0]);
                deleteTree(dir);
            });
        }
        List<String> bad = new ArrayList<>();
        // the applied leg's answer leads with the core's CHANGED note, filled as the core fills it
        String note = OpsMatchApply.CHANGED.replace("<what>", OpsMatchApply.Op.PLAYERS.what())
                .replace("<shape>", "web-a.txt").replace("<value>", "5");
        if (got[0].code() != 200 || !got[0].body().startsWith("{\"ok\":true,\"changed\":true,\"repaired\":false,\"note\":\""
                + OpsConsoleForms.jsonEscape(note) + "\",\"matchTextChanged\":true,") || applied.seats().size() != 5
                || oldId.equals(applied.matchId()) || !applied.matchId().equals(keyed)) {
            bad.add("players=5 -> " + got[0].code() + " " + PeerText.bound(got[0].body(), 160) + " seats="
                    + applied.seats().size() + " id rotated=" + !oldId.equals(applied.matchId()) + " sidecar keyed to"
                    + " the new id=" + applied.matchId().equals(keyed) + " (flips if the players Apply asks a confirm,"
                    + " stops reaching the core, answers another note, or the sidecar is not re-keyed)");
        }
        String[] names = {"players=0", "held serve.lock", "op=bogus"};
        String[] want = {
            OpsConsoleForms.errorJson(title, OpsMatchApply.R9_PLAYERS.replace("<x>", OpsMatchApply.shown("0"))),
            OpsConsoleForms.errorJson(title, OpsMatchApply.R1_LIVE),
            OpsConsoleForms.errorJson(title, OpsConsole.MATCH_SETTINGS_BAD_OP)};
        for (int i = 0; i < want.length; i++) {
            if (got[i + 1].code() != 200 || !want[i].equals(got[i + 1].body())) {
                bad.add(names[i] + " -> " + got[i + 1].code() + " " + PeerText.bound(got[i + 1].body(), 120) + " (flips"
                        + " if players leaves the op map, its core refusal drifts, or a refusal answers 4xx / 500)");
            }
        }
        if (!got[3].body().contains("players")) {
            bad.add("op=bogus -> " + PeerText.bound(got[3].body(), 120) + " (flips if the unknown-op text stops naming"
                    + " players)");
        }
        if (!kept) {
            bad.add("a match file changed after the Apply (flips if a players refusal or the live gate writes the"
                    + " pair)");
        }
        List<String> rows = rowsOf(captured, "cc-console match-settings ");
        String out = captured.toString(StandardCharsets.UTF_8);
        boolean leaked = out.contains("web-a") || out.contains(rig.pkg.toString());
        if (!List.of("op=players result=changed", "op=players result=refused:players",
                "op=players result=refused:server_live", "op=- result=refused:op").equals(rows) || leaked) {
            bad.add("stdout rows " + rows + " leaked=" + leaked + " (flips if players leaves the op map - its rows"
                    + " would read op=- - a POST prints no row or two, or a row carries the file name or a path)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-draft} - {@code POST /api/draft} over the REAL handler on
     * the rig's package, stdout teed, the draft file taken aside and deleted in {@code finally}. Legs: no key =
     * 403; a keyed GET = 405; then each refusal = 200 + the fixed {@code errorJson} (no {@code reason} member)
     * with no draft file written - an LF in the value, the join brick, the match password, a map field, no
     * {@code id}, a 513-char value; two lawful rows = {@code {"ok":true}}; {@code GET /api/config} then LEADS
     * with both rows in order and a parseable {@code draftUpdated}. Stdout = exactly the eight draft rows in
     * order, no refusal row names an id, and no row carries a value, the password or the package path.
     */
    private static String draftRow(Rig rig) throws Exception {
        String r = "web-draft";
        Path draft = OpsSetupDraft.fileFor(rig.roots);
        byte[][] prior = {null};
        setup(r, () -> {
            awaitStaging();
            prior[0] = takeFile(draft);
        });
        String title = OpsConsole.DRAFT_TITLE;
        List<String> bad = new ArrayList<>();
        PrintStream priorOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Resp config;
        System.setOut(tee(priorOut, captured));
        try {
            Resp keyless = call(rig, "POST", "/api/draft", null, "id=setup.tickRate&value=90");
            Resp get = call(rig, "GET", "/api/draft", rig.boot.token(), null);
            if (keyless.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(keyless.body()) || get.code() != 405
                    || Files.exists(draft)) {
                bad.add("keyless -> " + keyless.code() + ", keyed GET -> " + get.code() + " file=" + Files.exists(draft)
                        + " (flips if the route loses its key guard or stops refusing GET)");
            }
            String[][] refusals = {
                {"LF in value", "id=setup.tickRate&value=6%0A0", OpsConsoleForms.errorJson(title, OpsConsoleForms.CONTROL_CHAR)},
                {"the brick", "id=setup.brick.text&value=x", OpsConsoleForms.errorJson(title, OpsSetupDraft.REFUSED_ID)},
                {"the match password", "id=setup.password&value=" + enc(SECRET),
                    OpsConsoleForms.errorJson(title, OpsSetupDraft.REFUSED_ID)},
                {"a map field", "id=setup.map.half&value=15000", OpsConsoleForms.errorJson(title, OpsSetupDraft.REFUSED_ID)},
                {"no id", "value=x", OpsConsoleForms.errorJson(title, OpsSetupDraft.REFUSED_ID)},
                {"513 chars", "id=setup.tickRate&value=" + "7".repeat(OpsSetupDraft.MAX_VALUE_CHARS + 1),
                    OpsConsoleForms.errorJson(title, OpsSetupDraft.REFUSED_VALUE)}};
            for (String[] leg : refusals) {
                Resp a = post(rig, "/api/draft", leg[1]);
                if (a.code() != 200 || !leg[2].equals(a.body()) || a.body().contains("\"reason\"") || Files.exists(draft)) {
                    bad.add(leg[0] + " -> " + a.code() + " " + PeerText.bound(a.body(), 120) + " file=" + Files.exists(draft)
                            + " (flips if a refused id or value is written, or a refusal answers 4xx / 500 or a reason"
                            + " member - the page's note would read the HTTP code)");
                }
            }
            Resp tick = post(rig, "/api/draft", "id=setup.tickRate&value=90");
            Resp port = post(rig, "/api/draft", "id=setup.bindPort&value=51400");
            if (tick.code() != 200 || !"{\"ok\":true}".equals(tick.body()) || port.code() != 200
                    || !"{\"ok\":true}".equals(port.body())) {
                bad.add("lawful rows -> " + tick.code() + " " + PeerText.bound(tick.body(), 80) + " / " + port.code() + " "
                        + PeerText.bound(port.body(), 80) + " (flips if a lawful Setup field stops being kept)");
            }
            config = call(rig, "GET", "/api/config", rig.boot.token(), null);
        } finally {
            System.setOut(priorOut);
            setup(r, () -> {
                OpsSetupDraft.clear(draft);
                restoreFile(draft, prior[0]);
            });
        }
        String lead = "{\"draft\":{\"setup.tickRate\":\"90\",\"setup.bindPort\":\"51400\"},\"draftUpdated\":\"";
        String updated = null;
        if (config.body().startsWith(lead)) {
            int end = config.body().indexOf('"', lead.length());
            updated = end < 0 ? null : config.body().substring(lead.length(), end);
        }
        boolean instant;
        try {
            instant = updated != null && !updated.isEmpty() && Instant.parse(updated) != null;
        } catch (RuntimeException notAnInstant) { // the leg reads it as no instant
            instant = false;
        }
        if (!instant) {
            bad.add("config -> " + PeerText.bound(config.body(), 140) + " (flips if config stops carrying the draft"
                    + " as its LEADING members, in order, with its time)");
        }
        List<String> rows = new ArrayList<>();
        boolean leaked = false;
        for (String l : captured.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (l.startsWith("cc-console draft=")) {
                rows.add(l.substring("cc-console draft=".length()));
                leaked |= l.startsWith("cc-console draft=refused") && l.contains("setup.");
            }
            leaked |= l.matches(".*\\b90\\b.*") || l.contains("51400") || l.contains(SECRET)
                    || l.contains(rig.pkg.toString());
        }
        List<String> want = List.of("refused:control_char", "refused:id", "refused:id", "refused:id", "refused:id",
                "refused:value", "saved id=setup.tickRate n=1", "saved id=setup.bindPort n=2");
        if (!want.equals(rows) || leaked) {
            bad.add("stdout rows " + rows + " leaked=" + leaked + " (flips if a POST prints no row or two, the grammar"
                    + " drifts, a refusal names an id, or a row carries a value, a password or a path)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code /api/config} {@code matchSettings}. Two pairs
     * ({@code web-c} half 12000 / CR 55, {@code web-d} half 20000 / CR 80) and a junk {@code web-broken.txt}
     * are staged and the props row pointed at {@code match/web-c.txt} (restored in {@code finally}): the
     * plain config answers web-c's four fields (the standing row the config picks); {@code ?descriptor=}
     * web-d answers web-d's; the junk file answers four blanks + a problem; {@code ../x} answers
     * {@link OpsMatchApply#R6_NOT_OFFERED} without a read.
     */
    private static String configMatchSettingsRow(Rig rig) throws Exception {
        String r = "config-match-settings";
        Path props = rig.roots.config();
        Path dir = rig.pkg.resolve(OpsMatchApply.MATCH_DIR);
        byte[][] saved = {null};
        MatchDescriptor[] d = {null, null};
        setup(r, () -> {
            saved[0] = Files.readAllBytes(props);
            String text = new String(saved[0], StandardCharsets.UTF_8);
            if (!text.contains("match.descriptor=match-web-pkg.txt")) {
                throw new IllegalStateException("the rig's props row is not match-web-pkg.txt");
            }
            d[0] = stageMatchPair(rig, "web-c.txt", MatchDescriptors.mint("1v1", 12000f, 0.55f));
            d[1] = stageMatchPair(rig, "web-d.txt", MatchDescriptors.mint("1v1", 20000f, 0.8f));
            Files.writeString(dir.resolve("web-broken.txt"), "not a match file\n", StandardCharsets.UTF_8);
            Files.writeString(props, text.replace("match.descriptor=match-web-pkg.txt",
                    "match.descriptor=match/web-c.txt"), StandardCharsets.UTF_8);
        });
        try {
            String token = rig.boot.token();
            Resp standing = call(rig, "GET", "/api/config", token, null);
            Resp picked = call(rig, "GET", "/api/config?descriptor=" + enc("match/web-d.txt"), token, null);
            Resp broken = call(rig, "GET", "/api/config?descriptor=" + enc("match/web-broken.txt"), token, null);
            Resp escape = call(rig, "GET", "/api/config?descriptor=" + enc("../x"), token, null);
            List<String> bad = new ArrayList<>();
            String c = "\"matchSettings\":{\"half\":\"12000\",\"station\":\"-\",\"backdrop\":\""
                    + OpsConsoleForms.jsonEscape(d[0].terrain()) + "\",\"deployCr\":\"55\",\"problem\":\"\"}}";
            String dd = "\"matchSettings\":{\"half\":\"20000\",\"station\":\"-\",\"backdrop\":\""
                    + OpsConsoleForms.jsonEscape(d[1].terrain()) + "\",\"deployCr\":\"80\",\"problem\":\"\"}}";
            if (!standing.body().endsWith(c) || !picked.body().endsWith(dd)) {
                bad.add("standing ends " + PeerText.bound(standing.body().substring(Math.max(0, standing.body().length()
                        - 130)), 130) + " | picked ends " + PeerText.bound(picked.body().substring(Math.max(0,
                        picked.body().length() - 130)), 130) + " (flips if the config stops serving the picked shape's"
                        + " four fields, serves another shape's, or the field names drift)");
            }
            if (!broken.body().contains("\"matchSettings\":{\"half\":\"\",\"station\":\"\",\"backdrop\":\"\",\"deployCr\":\"\"")
                    || broken.body().endsWith("\"problem\":\"\"}}")) {
                bad.add("damaged descriptor -> no problem sentence (flips if a damaged file reads as values or blank)");
            }
            if (!escape.body().endsWith("\"problem\":\"" + OpsConsoleForms.jsonEscape(OpsMatchApply.R6_NOT_OFFERED)
                    + "\"}}")) {
                bad.add("../x -> not R6 (flips if the offered bound on the prefill read is dropped)");
            }
            return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
        } finally {
            setup(r, () -> {
                Files.write(props, saved[0]);
                deleteTree(dir);
            });
        }
    }

    static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (java.io.IOException | RuntimeException residue) { // reported, never fatal to the gate
            System.out.println("ops-console selftest: temp residue left (" + residue.getClass().getSimpleName() + ")");
        }
    }
}
