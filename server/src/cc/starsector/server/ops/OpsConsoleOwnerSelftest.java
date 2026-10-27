package cc.starsector.server.ops;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import cc.starsector.server.match.ContentFingerprintMint;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;

/**
 * The console's OWNER-mode rows, registered by one line at the end of
 * {@link OpsConsoleSelftest#run}. WHY a class of its own: the package selftest is several thousand lines, and the
 * owner rows need two rigs of their own - kept beside it, sharing its rig and HTTP helpers.
 *
 * <p>Three blocks. (1) Pure rows: the {@code --owner} grammar and claim check ({@link OpsConsole#ownerArgsOrNull},
 * {@link OpsConsole#ownerClaimRefusalOrNull}) and the served page's mode chrome over the same {@code html} /
 * {@code js} strings the package page rows read. (2) The PACKAGE rig: {@link OpsConsoleSelftest#stage}'s world,
 * re-booted with a {@link FakeOwnerLaunch} so a regressed package refusal can never run a real script, with
 * {@link OpsRoots#ROOTS} set to its roots (the mods roots and the join press read the global record). (3) The
 * OWNER rig ({@link #stageOwner}): the same staged temp world re-booted on {@link OpsRoots#ownerUnder} with the fake
 * and a follow seed, {@code ROOTS} set to the owner roots (the status no-file detail reads it); it nests inside the
 * package block so {@code web-followed-seed} reads both consoles. Every {@code ROOTS} swap is restored in a
 * {@code finally}, innermost first.
 *
 * <p>Each row prints {@code PASS <row>} or {@code FAIL <row>: <why> (flips if ...)}; a rig that never staged prints
 * {@code SETUP-<row>: ...} (an ERROR, never a mutant kill); a single LEG the machine cannot stage (a symbolic link
 * refused) prints {@code NOTE <row> <leg> skipped <SimpleName>} and the row goes on.
 */
final class OpsConsoleOwnerSelftest {

    private OpsConsoleOwnerSelftest() {
    }

    /** The owner rig's follow seed, under the rig's temp root (absent: the status names it). */
    static final String SEED_FILE = "seed-ops.txt";
    /** The brick legs' typed match password: a VALID one (8..64 letters / digits / dashes), else the brick refuses it. */
    private static final String BRICK_PASSWORD = "owner-brick-pass";
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");
    private static final String JOIN_OPEN = "<button type=\"button\" id=\"btn-join\" data-cc=\"verb.joinerGui\""
            + " aria-describedby=\"tip-join\" hidden>";

    /** A rig step that could not stage (the package selftest's shape, copied: its own is private). */
    private static final class SetupFail extends RuntimeException {
        SetupFail(String row, Exception cause) {
            super("SETUP-" + row + ": " + cause.getClass().getSimpleName() + " "
                    + PeerText.bound(String.valueOf(cause.getMessage()), 160), null, false, false);
        }
    }

    @FunctionalInterface
    interface Check {
        /** null = green, else the offence (naming the regression that flips it). */
        String run() throws Exception;
    }

    /**
     * Owner mode's two script spawns, recorded: a count per method and an optional planted throw. Never a process:
     * the answer is an already-exited fake.
     */
    static final class FakeOwnerLaunch implements OpsConsole.OwnerLaunch {
        final AtomicInteger devEditorCalls = new AtomicInteger();
        final AtomicInteger joinerGuiCalls = new AtomicInteger();
        /** Thrown by the next spawns while set (the fault legs); null = answer the fake. */
        final AtomicReference<IOException> fail = new AtomicReference<>();

        @Override
        public Process devEditor() throws IOException {
            devEditorCalls.incrementAndGet();
            return answer();
        }

        @Override
        public Process joinerGui() throws IOException {
            joinerGuiCalls.incrementAndGet();
            return answer();
        }

        private Process answer() throws IOException {
            IOException planted = fail.get();
            if (planted != null) {
                throw planted;
            }
            OpsPressSelftest.FakeProcess p = new OpsPressSelftest.FakeProcess();
            p.exit = 0;
            return p;
        }
    }

    static List<String> run(String html, String js) {
        List<String> fails = new ArrayList<>();
        row(fails, "owner-claim-cwd-mismatch", OpsConsoleOwnerSelftest::claimRow);
        row(fails, "owner-args-dash-refused", OpsConsoleOwnerSelftest::argsRow);
        row(fails, "parity-owner-twin", () -> parityOwnerTwin(html, js));
        row(fails, "page-chrome-owner-hides-package-ids", () -> chromePackageIdsRow(js));
        row(fails, "page-chrome-pkg-hides-owner-ids", () -> chromeOwnerIdsRow(html, js));
        row(fails, "page-owner-chrome", () -> ownerChromeRow(html, js));
        row(fails, "page-watch-owner", () -> ownerWatchPageRow(html, js));
        row(fails, "sweep-closes-recorded-set", OpsConsoleOwnerSelftest::sweepClosesRow);
        row(fails, "sweep-start-mismatch-untouched", OpsConsoleOwnerSelftest::sweepStartMismatchRow);
        row(fails, "sweep-holder-moved-no-sweep", OpsConsoleOwnerSelftest::sweepHolderMovedRow);
        row(fails, "sweep-once", OpsConsoleOwnerSelftest::sweepOnceRow);
        row(fails, "sweep-backstop-path-and-time", OpsConsoleOwnerSelftest::sweepBackstopRow);
        row(fails, "sweep-skips-foreign-subtree", OpsConsoleOwnerSelftest::sweepSkipsForeignRow);
        row(fails, "sweep-skips-unknown-command", OpsConsoleOwnerSelftest::sweepSkipsUnknownRow);
        OpsRoots before = OpsRoots.ROOTS.get();
        packageBlock(fails);
        // NO row checked the ROOTS restores: a leaked swap would point
        // every later selftest in this JVM at a rig's record
        row(fails, "owner-roots-restored", () -> OpsRoots.ROOTS.get() == before ? null
                : "ROOTS left on another record (flips if a block's finally stops restoring the prior roots)");
        return fails;
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

    // ---- the debug watch's join-window sweep (JoinWindowSweep) - fakes only, never a real process.
    //

    private static final Instant SWEEP_T0 = Instant.parse("2026-10-18T10:00:00Z");
    private static final String CMD = "C:\\Windows\\System32\\cmd.exe";
    /** Where the kit launcher's bare {@code powershell} (its debrief) resolves on a stock Windows. */
    private static final String DEBRIEF = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";

    /** A fake process: fixed pid / start / executable, a settable alive flag, its children. */
    private static final class FakeProc implements JoinWindowSweep.Proc {
        final long pid;
        final Instant start;
        final String command;
        final List<JoinWindowSweep.Proc> kids;
        volatile boolean alive = true;

        FakeProc(long pid, Instant start, String command, FakeProc... kids) {
            this.pid = pid;
            this.start = start;
            this.command = command;
            this.kids = List.<JoinWindowSweep.Proc>of(kids);
        }

        @Override public long pid() { return pid; }
        @Override public Instant startOrNull() { return start; }
        @Override public String commandOrNull() { return command; }
        @Override public boolean isAlive() { return alive; }
        @Override public List<JoinWindowSweep.Proc> children() { return kids; }
    }

    /** The fake box: its process list, every kill in order as {@code <pid>:gentle|force} (a forcible kill, or a
     *  gentle one while {@code jvmHonours}, marks the process dead), {@code awaitExit} = "is it dead now". */
    private static final class FakeOs implements JoinWindowSweep.Os {
        final List<JoinWindowSweep.Proc> all = new ArrayList<>();
        final List<String> kills = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger allReads = new AtomicInteger();
        boolean jvmHonours;

        @Override
        public List<JoinWindowSweep.Proc> all() {
            allReads.incrementAndGet();
            return List.copyOf(all);
        }

        @Override
        public void kill(JoinWindowSweep.Proc p, boolean force) {
            kills.add(p.pid() + (force ? ":force" : ":gentle"));
            if (force || jvmHonours) {
                ((FakeProc) p).alive = false;
            }
        }

        @Override
        public boolean awaitExit(JoinWindowSweep.Proc p, long boundMs) {
            return !p.isAlive();
        }

        @Override
        public String systemRoot() {
            return "C:\\Windows";
        }
    }

    /** A lease daemon's world: the config + its lease (stamp written), a fake serve, a launch and its holder. */
    private record SweepRig(Path root, Path cfg, Path lease, OpsPressSelftest.FakeProcess serve,
            LaneFollow.LaneLaunch launch, AtomicReference<LaneFollow.LaneLaunch> holder) {
    }

    private static SweepRig stageSweepRig(String row) {
        Path[] made = new Path[3];
        setup(row, () -> {
            made[0] = Files.createTempDirectory("cc-sweep-rig");
            made[1] = made[0].resolve(ServeLauncher.DEFAULT_CONFIG);
            made[2] = ServerOpsLaunch.watchLeaseFile(made[1]);
            Files.writeString(made[1], "# config\r\n", StandardCharsets.UTF_8);
            Files.writeString(made[2], "stamp-sweep\n", StandardCharsets.US_ASCII);
        });
        OpsPressSelftest.FakeProcess serve = new OpsPressSelftest.FakeProcess();
        LaneFollow.LaneLaunch launch = new LaneFollow.LaneLaunch(ServerOpsCore.WATCH_LANE,
                made[0].resolve("ops-status.txt"), "log", System.currentTimeMillis(), serve, null);
        return new SweepRig(made[0], made[1], made[2], serve, launch, new AtomicReference<>(launch));
    }

    /** The rig's temp tree removed; a residue is said once, never fatal. */
    private static void dropSweepRig(SweepRig g) {
        try (Stream<Path> walk = Files.walk(g.root())) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException residue) {
            System.out.println("NOTE sweep rig residue " + residue.getClass().getSimpleName());
        }
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier cond, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                return false;
            }
            Thread.sleep(50L);
        }
        return true;
    }

    /**
     * {@code sweep-closes-recorded-set}: the REAL lease daemon ({@link ServerOpsCore#armWatchLease}) over the console's
     * END leg ({@link JoinWindowSweep#endLeg}) with two recorded windows: once the serve exits, the scrub runs, then
     * each window's descendants end deepest first (a live JVM gets the gentle request, then the forcible kill past the
     * bound), then the window handle itself, window 1 before window 2; one count row, closed=2.
     */
    private static String sweepClosesRow() throws Exception {
        String r = "sweep-closes-recorded-set";
        SweepRig g = stageSweepRig(r);
        try {
            Path kitA = g.root().resolve("gameA").resolve("mods").resolve("CC_Client");
            Path kitB = g.root().resolve("gameB").resolve("mods").resolve("CC_Client");
            FakeProc game = new FakeProc(103, SWEEP_T0.plusSeconds(20),
                    kitA.resolve("jre-cc").resolve("bin").resolve("javaw.exe").toString());
            FakeProc choice = new FakeProc(104, SWEEP_T0.plusSeconds(90), "C:\\Windows\\System32\\choice.exe");
            FakeProc windowA = new FakeProc(102, SWEEP_T0.plusMillis(50), CMD, game, choice);
            FakeProc conhostA = new FakeProc(101, SWEEP_T0.plusMillis(40), "C:\\Windows\\System32\\conhost.exe");
            FakeProc outerA = new FakeProc(100, SWEEP_T0, CMD, conhostA, windowA);
            FakeProc debrief = new FakeProc(203, SWEEP_T0.plusSeconds(95), DEBRIEF);
            FakeProc windowB = new FakeProc(202, SWEEP_T0.plusSeconds(1), CMD, debrief);
            FakeProc outerB = new FakeProc(200, SWEEP_T0.plusSeconds(1), CMD, windowB);
            FakeOs os = new FakeOs();
            os.all.addAll(List.of(outerA, conhostA, windowA, game, choice, outerB, windowB, debrief));
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            JoinWindowSweep.WindowSet set = new JoinWindowSweep.WindowSet(os, events::add);
            set.record(List.of(new JoinWindowSweep.Window(outerA, SWEEP_T0, kitA)));
            set.record(List.of(new JoinWindowSweep.Window(outerB, SWEEP_T0.plusSeconds(1), kitB)));
            long ended = ServerOpsCore.WATCH_LEASE_ENDED.get();
            boolean armed = ServerOpsCore.armWatchLease(g.launch(), g.holder()::get, g.cfg(),
                    JoinWindowSweep.endLeg(() -> events.add("scrub"), set));
            g.serve().exit = 0;
            boolean done = awaitTrue(() -> events.size() >= 2 && ServerOpsCore.WATCH_LEASE_ENDED.get() > ended, 6_000L);
            List<String> want = List.of("104:force", "103:gentle", "103:force", "102:force", "101:force", "100:force",
                    "203:force", "202:force", "200:force");
            List<String> rows = List.of("scrub", "cc-console watch sweep closed=2 gone=0 detached=0");
            if (!armed || !done || !rows.equals(List.copyOf(events)) || !want.equals(List.copyOf(os.kills))) {
                return "the END leg closes both recorded windows, children before parents and each window last (flips"
                        + " if the kill order inverts, a handle or a descendant is skipped, the JVM skips its gentle"
                        + " request, or the sweep leaves the END leg): armed=" + armed + " done=" + done + " events="
                        + events + " kills=" + os.kills;
            }
            return null;
        } finally {
            dropSweepRig(g);
        }
    }

    /** {@code sweep-start-mismatch-untouched}: a recorded pid whose process now has ANOTHER start instant (a reused
     *  pid), and a dead handle, are counted gone - nothing is killed. */
    private static String sweepStartMismatchRow() {
        Path kit = Paths.get("C:\\Games\\Starsector\\mods\\CC_Client");
        FakeProc reused = new FakeProc(310, SWEEP_T0.plusSeconds(3600), CMD,
                new FakeProc(311, SWEEP_T0.plusSeconds(3601), CMD));
        FakeProc dead = new FakeProc(320, SWEEP_T0, CMD);
        dead.alive = false;
        FakeOs os = new FakeOs();
        List<String> rows = new ArrayList<>();
        JoinWindowSweep.Tally t = JoinWindowSweep.sweep(List.of(new JoinWindowSweep.Window(reused, SWEEP_T0, kit),
                new JoinWindowSweep.Window(dead, SWEEP_T0, kit)), os, rows::add);
        if (!os.kills.isEmpty() || t.closed() != 0 || t.gone() != 2
                || !rows.equals(List.of("cc-console watch sweep closed=0 gone=2 detached=0"))) {
            return "a reused pid (another start instant) and a dead handle are counted gone, never touched (flips if the"
                    + " start-instant guard is dropped - a stranger's process would be killed): kills=" + os.kills
                    + " rows=" + rows;
        }
        return null;
    }

    /**
     * {@code sweep-holder-moved-no-sweep}: the REAL lease daemon whose holder a newer Start replaced (and whose serve
     * then ended too) returns before its END leg - the old launch's windows are not swept, the scrub does not run, the
     * lease is left for the newer watch.
     */
    private static String sweepHolderMovedRow() throws Exception {
        String r = "sweep-holder-moved-no-sweep";
        SweepRig g = stageSweepRig(r);
        try {
            Path kit = g.root().resolve("game").resolve("mods").resolve("CC_Client");
            FakeProc outer = new FakeProc(500, SWEEP_T0, CMD, new FakeProc(501, SWEEP_T0.plusMillis(50), CMD));
            FakeOs os = new FakeOs();
            os.all.add(outer);
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            JoinWindowSweep.WindowSet set = new JoinWindowSweep.WindowSet(os, events::add);
            set.record(List.of(new JoinWindowSweep.Window(outer, SWEEP_T0, kit)));
            long ended = ServerOpsCore.WATCH_LEASE_ENDED.get();
            boolean armed = ServerOpsCore.armWatchLease(g.launch(), g.holder()::get, g.cfg(),
                    JoinWindowSweep.endLeg(() -> events.add("scrub"), set));
            g.holder().set(new LaneFollow.LaneLaunch(ServerOpsCore.WATCH_LANE, g.root().resolve("ops-status.txt"),
                    "log", System.currentTimeMillis(), new OpsPressSelftest.FakeProcess(), null));
            g.serve().exit = 0; // the old serve ends as well: only the holder check keeps its END leg from running
            // the daemon's own exit meter, never a sleep (a loaded box let the sleep pass before the daemon ran)
            if (!awaitTrue(() -> ServerOpsCore.WATCH_LEASE_ENDED.get() > ended, 6_000L)) {
                return "the holder-moved daemon never exited within 6 s (ended=" + ended + ")";
            }
            if (!armed || !events.isEmpty() || !os.kills.isEmpty() || set.size() != 1
                    || !Files.isRegularFile(g.lease())) {
                return "a holder-moved exit sweeps nothing (flips if the sweep rides the holder-moved exit - a newer"
                        + " Start's daemon would close the windows of a watch it replaced): armed=" + armed + " events="
                        + events + " kills=" + os.kills + " left=" + set.size();
            }
            return null;
        } finally {
            dropSweepRig(g);
        }
    }

    /** {@code sweep-once}: the END leg run a second time finds an empty set - its row counts nothing and no process
     *  list is read again; a THIRD set whose scrub throws is still swept (the leg's {@code finally}; the throw
     *  propagates to the daemon's own catch). */
    private static String sweepOnceRow() {
        Path kit = Paths.get("C:\\Games\\Starsector\\mods\\CC_Client");
        FakeProc outer = new FakeProc(600, SWEEP_T0, CMD, new FakeProc(601, SWEEP_T0.plusMillis(50), CMD));
        FakeOs os = new FakeOs();
        List<String> rows = new ArrayList<>();
        JoinWindowSweep.WindowSet set = new JoinWindowSweep.WindowSet(os, rows::add);
        set.record(List.of(new JoinWindowSweep.Window(outer, SWEEP_T0, kit)));
        Runnable leg = JoinWindowSweep.endLeg(() -> { }, set);
        leg.run();
        leg.run();
        List<String> want = List.of("cc-console watch sweep closed=1 gone=0 detached=0",
                "cc-console watch sweep closed=0 gone=0 detached=0");
        if (!want.equals(rows) || !List.of("601:force", "600:force").equals(os.kills) || os.allReads.get() != 1) {
            return "a second END leg finds an empty set (flips if the sweep does not clear the set - the second run"
                    + " would count the dead window as gone): rows=" + rows + " kills=" + os.kills;
        }
        FakeProc outer3 = new FakeProc(610, SWEEP_T0, CMD);
        FakeOs os3 = new FakeOs();
        List<String> rows3 = new ArrayList<>();
        JoinWindowSweep.WindowSet set3 = new JoinWindowSweep.WindowSet(os3, rows3::add);
        set3.record(List.of(new JoinWindowSweep.Window(outer3, SWEEP_T0, kit)));
        boolean thrown = false;
        try {
            JoinWindowSweep.endLeg(() -> { throw new IllegalStateException("scrub"); }, set3).run();
        } catch (IllegalStateException expected) {
            thrown = true;
        }
        if (!thrown || !List.of("cc-console watch sweep closed=1 gone=0 detached=0").equals(rows3)
                || !List.of("610:force").equals(os3.kills)) {
            return "a throwing scrub still sweeps and the throw propagates (flips if the sweep leaves the finally - a"
                    + " scrub fault would strand the windows): thrown=" + thrown + " rows=" + rows3 + " kills=" + os3.kills;
        }
        return null;
    }

    /**
     * {@code sweep-backstop-path-and-time}: the backstop ends a process whose executable is under the window's kit
     * folder and that started at or after the window's spawn (the kit's own runtime: the detached game) - never one
     * under another folder (the game's own jre, a sibling {@code CC_Client2}), one started before the press, or one
     * the OS would not describe. On Windows the kit path compares case-blind, and an unparsable executable path is
     * one fault row while the rest still run.
     */
    private static String sweepBackstopRow() {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path game = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("cc-sweep-game");
        Path kit = game.resolve("mods").resolve("CC_Client");
        String javaw = kit.resolve("jre-cc").resolve("bin").resolve("javaw.exe").toString();
        FakeProc gone = new FakeProc(700, SWEEP_T0, CMD);
        gone.alive = false;
        FakeOs os = new FakeOs();
        os.jvmHonours = true;
        if (win) {
            os.all.add(new FakeProc(709, SWEEP_T0.plusSeconds(5), "C:\\x<y\\javaw.exe"));
        }
        os.all.add(new FakeProc(701, SWEEP_T0.plusSeconds(10), javaw));
        os.all.add(new FakeProc(702, SWEEP_T0.plusSeconds(10),
                game.resolve("jre").resolve("bin").resolve("javaw.exe").toString()));
        os.all.add(new FakeProc(703, SWEEP_T0.minusSeconds(1), javaw));
        os.all.add(new FakeProc(704, SWEEP_T0.plusSeconds(10), game.resolve("mods").resolve("CC_Client2")
                .resolve("jre-cc").resolve("bin").resolve("javaw.exe").toString()));
        os.all.add(new FakeProc(705, null, javaw));
        os.all.add(new FakeProc(706, SWEEP_T0, kit.resolve("jre-cc").resolve("bin").resolve("java.exe").toString()));
        os.all.add(new FakeProc(707, SWEEP_T0.plusSeconds(10), null));
        if (win) {
            os.all.add(new FakeProc(708, SWEEP_T0.plusSeconds(10), javaw.toUpperCase(Locale.ROOT)));
        }
        List<String> rows = new ArrayList<>();
        JoinWindowSweep.sweep(List.of(new JoinWindowSweep.Window(gone, SWEEP_T0, kit)), os, rows::add);
        List<String> wantKills = win ? List.of("701:gentle", "706:gentle", "708:gentle")
                : List.of("701:gentle", "706:gentle");
        List<String> wantRows = win
                ? List.of("cc-console watch sweep closed=0 gone=1 detached=3",
                        "cc-console watch sweep fault=InvalidPathException")
                : List.of("cc-console watch sweep closed=0 gone=1 detached=2");
        if (!wantKills.equals(os.kills) || !wantRows.equals(rows)) {
            return "the backstop ends exactly the kit-runtime processes started at/after the press (flips if the path"
                    + " match widens - another folder, a sibling prefix - or the start-instant floor drops): kills="
                    + os.kills + " rows=" + rows;
        }
        return null;
    }

    /**
     * {@code sweep-skips-foreign-subtree}: a window whose UI (the game's own javaw) cold-started the host's browser - a
     * browser-shaped child under the javaw, with a child of its own that LOOKS like a console tool - and whose debrief
     * opened an editor from {@code System32} that is not one of the window's tools. The sweep ends the window's own
     * processes (its cmds, its console host named in another case, the UI, the game's launcher and its JVM, the
     * debrief) and leaves both foreign subtrees whole. {@code skipped=2} counts SUBTREES, not processes: the walk never
     * descends into a foreign one, so the browser's child is never read.
     */
    private static String sweepSkipsForeignRow() {
        Path game = Paths.get("C:\\Games\\Starsector");
        Path jreBin = game.resolve("jre").resolve("bin");
        FakeProc tab = new FakeProc(905, SWEEP_T0.plusSeconds(31), CMD);
        FakeProc browser = new FakeProc(904, SWEEP_T0.plusSeconds(30),
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", tab);
        FakeProc ui = new FakeProc(903, SWEEP_T0.plusSeconds(2), jreBin.resolve("javaw.exe").toString(), browser);
        FakeProc gameJvm = new FakeProc(907, SWEEP_T0.plusSeconds(41), jreBin.resolve("java.exe").toString());
        FakeProc launcher = new FakeProc(906, SWEEP_T0.plusSeconds(40), game.resolve("starsector.exe").toString(),
                gameJvm);
        FakeProc editor = new FakeProc(909, SWEEP_T0.plusSeconds(96), "C:\\Windows\\System32\\notepad.exe");
        FakeProc debrief = new FakeProc(908, SWEEP_T0.plusSeconds(95), DEBRIEF, editor);
        FakeProc inner = new FakeProc(902, SWEEP_T0.plusMillis(50), CMD, ui, launcher, debrief);
        FakeProc conhost = new FakeProc(901, SWEEP_T0.plusMillis(40), "C:\\WINDOWS\\system32\\CONHOST.EXE");
        FakeProc outer = new FakeProc(900, SWEEP_T0, CMD, conhost, inner);
        FakeOs os = new FakeOs();
        os.all.addAll(List.of(outer, conhost, inner, ui, browser, tab, launcher, gameJvm, debrief, editor));
        List<String> rows = new ArrayList<>();
        JoinWindowSweep.sweep(List.of(new JoinWindowSweep.Window(outer, SWEEP_T0,
                game.resolve("mods").resolve("CC_Client"))), os, rows::add);
        List<String> want = List.of("907:gentle", "907:force", "908:force", "906:force", "903:gentle", "903:force",
                "902:force", "901:force", "900:force");
        if (!want.equals(os.kills) || !browser.alive || !tab.alive || !editor.alive
                || !List.of("cc-console watch sweep closed=1 gone=0 detached=0 skipped=2").equals(rows)) {
            return "the sweep ends only the window's own processes and leaves each foreign subtree whole, counted once"
                    + " (flips if the walk ends or walks into an executable off its list - the host's browser, its"
                    + " child or an editor would be killed - if an entry drops or turns case-sensitive - the UI, the"
                    + " game's launcher, its JVM, the debrief or the console host would outlive the watch - or the"
                    + " skipped count goes per process or silent): kills=" + os.kills + " rows=" + rows;
        }
        return null;
    }

    /** {@code sweep-skips-unknown-command}: a descendant whose executable the OS will not give (null), gives EMPTY, or
     *  gives unparsable is unknown - never ended and never walked into, even when its own child looks like a console
     *  tool - and each counts once as skipped, while the window's own prompt still ends.
     * */
    private static String sweepSkipsUnknownRow() {
        FakeProc nullCmd = new FakeProc(1011, SWEEP_T0.plusSeconds(5), null,
                new FakeProc(1012, SWEEP_T0.plusSeconds(6), CMD));
        FakeProc emptyCmd = new FakeProc(1013, SWEEP_T0.plusSeconds(5), "",
                new FakeProc(1014, SWEEP_T0.plusSeconds(6), CMD));
        FakeProc odd = new FakeProc(1016, SWEEP_T0.plusSeconds(5), "C:\\x<y\\cmd.exe");
        FakeProc choice = new FakeProc(1015, SWEEP_T0.plusSeconds(90), "C:\\Windows\\System32\\choice.exe");
        FakeProc outer = new FakeProc(1000, SWEEP_T0, CMD,
                new FakeProc(1010, SWEEP_T0.plusMillis(50), CMD, nullCmd, emptyCmd, choice, odd));
        FakeOs os = new FakeOs();
        List<String> rows = new ArrayList<>();
        JoinWindowSweep.sweep(List.of(new JoinWindowSweep.Window(outer, SWEEP_T0,
                Paths.get("C:\\Games\\Starsector\\mods\\CC_Client"))), os, rows::add);
        if (!List.of("1015:force", "1010:force", "1000:force").equals(os.kills)
                || !List.of("cc-console watch sweep closed=1 gone=0 detached=0 skipped=3").equals(rows)) {
            return "an undescribed descendant is skipped whole and counted (flips if a null, empty or unparsable"
                    + " executable is taken as the window's own - ended, or walked into so its console-shaped child"
                    + " dies - or the skipped count misses one): kills=" + os.kills + " rows=" + rows;
        }
        return null;
    }

    private static void setup(String row, OpsConsoleSelftest.Step step) {
        try {
            step.run();
        } catch (Exception e) { // rethrown as the SETUP line
            throw new SetupFail(row, e);
        }
    }

    /** A rig that never staged: its SETUP line once (the package stage's own, else this block's), then the partial
     *  rig torn down. */
    private static void setupFailed(List<String> fails, String row, RuntimeException failed, OpsConsoleSelftest.Rig rig) {
        String line = String.valueOf(failed.getMessage()).startsWith("SETUP-") ? failed.getMessage()
                : "SETUP-" + row + ": " + failed.getClass().getSimpleName();
        System.out.println(line);
        fails.add(line);
        teardown(rig);
    }

    // ---- pure rows -------------------------------------------------------------------------------

    /** Claim check (i) over real temp folders: a different folder, the same one, a dotted spelling of it, an absent
     *  child, and (where the machine allows one) a symbolic link to it. */
    private static String claimRow() throws Exception {
        String r = "owner-claim-cwd-mismatch";
        Path[] base = {null};
        try {
            setup(r, () -> {
                base[0] = Files.createTempDirectory("cc-owner-claim");
                Files.createDirectories(base[0].resolve("a").resolve("sub"));
                Files.createDirectories(base[0].resolve("b"));
            });
            Path a = base[0].resolve("a");
            Path b = base[0].resolve("b");
            List<String> bad = new ArrayList<>();
            String other = OpsConsole.ownerClaimRefusalOrNull(b, a);
            if (!String.format(Locale.ROOT, OpsConsole.OWNER_CWD_MISMATCH, b, a.toAbsolutePath()).equals(other)) {
                bad.add("another folder -> " + PeerText.bound(String.valueOf(other), 120));
            }
            if (OpsConsole.ownerClaimRefusalOrNull(a, a) != null) {
                bad.add("the working directory itself refused");
            }
            Path dotted = a.resolve("sub").resolve("..");
            if (OpsConsole.ownerClaimRefusalOrNull(dotted, a) != null) {
                bad.add("a dotted spelling of the working directory refused");
            }
            Path absent = a.resolve("absent");
            String gone;
            try {
                gone = OpsConsole.ownerClaimRefusalOrNull(absent, a);
            } catch (RuntimeException threw) { // the row's offence: the sentence is owed, never a throw
                gone = "threw " + threw.getClass().getSimpleName();
            }
            if (!String.format(Locale.ROOT, OpsConsole.OWNER_CWD_MISMATCH, absent, a.toAbsolutePath()).equals(gone)) {
                bad.add("an absent root -> " + PeerText.bound(String.valueOf(gone), 120));
            }
            Path link = base[0].resolve("link");
            boolean linked;
            try {
                Files.createSymbolicLink(link, a);
                linked = true;
            } catch (IOException | UnsupportedOperationException | SecurityException refused) { // a leg, not the rig
                System.out.println("NOTE " + r + " link skipped " + refused.getClass().getSimpleName());
                linked = false;
            }
            if (linked && OpsConsole.ownerClaimRefusalOrNull(link, a) != null) {
                bad.add("a link whose target is the working directory refused");
            }
            return bad.isEmpty() ? null : bad + " (flips if the claim compares path strings instead of real paths,"
                    + " accepts another folder, refuses a link to the working directory, or throws on an absent root)";
        } finally {
            if (base[0] != null) {
                OpsConsoleSelftest.deleteTree(base[0]);
            }
        }
    }

    /** The PURE grammar: a dash ops file, a count of 0 or 4 positionals and a port outside 0..65535 are the usage
     *  line; the full form, the bare root and the trailing {@code --open} parse. */
    private static String argsRow() {
        List<String> bad = new ArrayList<>();
        String[][] refused = {{"--owner", "R", "-x"}, {"--owner", "R", "ops.txt", "70000"},
            {"--owner", "R", "ops.txt", "-1"}, {"--owner"}, {"--owner", "R", "a", "1", "b"}, {"--owner", "--open"}};
        for (String[] args : refused) {
            OpsConsole.OwnerArgs got = OpsConsole.ownerArgsOrNull(args);
            if (got != null) {
                bad.add(String.join(" ", args) + " -> " + got);
            }
        }
        Map<String, OpsConsole.OwnerArgs> accepted = Map.of(
                "--owner R ops.txt 7718 --open", new OpsConsole.OwnerArgs("R", "ops.txt", 7718, true),
                "--owner R", new OpsConsole.OwnerArgs("R", null, 7718, false),
                "--owner R --open", new OpsConsole.OwnerArgs("R", null, 7718, true),
                "--owner R ops.txt 0", new OpsConsole.OwnerArgs("R", "ops.txt", 0, false));
        for (Map.Entry<String, OpsConsole.OwnerArgs> e : accepted.entrySet()) {
            OpsConsole.OwnerArgs got = OpsConsole.ownerArgsOrNull(e.getKey().split(" "));
            if (!e.getValue().equals(got)) {
                bad.add(e.getKey() + " -> " + got);
            }
        }
        return bad.isEmpty() ? null : bad + " (flips if an ops file starting with a dash, a positional count outside"
                + " 1..3 or a port outside 0..65535 is accepted, or the default port leaves 7718 - the launcher prints"
                + " that address without asking the console)";
    }

    /** Every owner control has its page twin: {@code data-cc="<id>"} in the served HTML or the JS. */
    static String parityOwnerTwin(String html, String js) {
        if (OpsParity.OWNER_IDS.isEmpty()) {
            return "OpsParity.OWNER_IDS is empty (flips if the owner list is emptied - the row would check nothing)";
        }
        List<String> missing = new ArrayList<>();
        for (String id : OpsParity.OWNER_IDS) {
            String literal = "data-cc=\"" + id + "\"";
            if (!html.contains(literal) && !js.contains(literal)) {
                missing.add(id);
            }
        }
        System.out.println("parity-owner-twin ids=" + OpsParity.OWNER_IDS.size() + " missing=" + missing);
        return missing.isEmpty() ? null : "owner control(s) without a page twin " + missing + " (the owner list holds"
                + " the Dev editor alone today, so this checks one id; flips when a later owner id lands in"
                + " OpsParity.OWNER_IDS without its page data-cc twin, or the Dev editor button leaves the page)";
    }

    /** The page's package-only array in {@code CC.packageChrome} = the Java list,
     *  shown only under {@code c.packaged === true}, and the rule runs on every config. */
    private static String chromePackageIdsRow(String js) {
        Set<String> want = new TreeSet<>(OpsParity.PACKAGE_ONLY_IDS);
        List<String> page = chromeIds(js, "apply(c.packaged === true, [");
        List<String> bad = new ArrayList<>();
        if (want.isEmpty()) {
            bad.add("the package-only list is empty");
        }
        if (page == null) {
            bad.add("no apply(c.packaged === true, [...]) in CC.packageChrome");
        } else if (!want.equals(new TreeSet<>(page)) || page.size() != want.size()) {
            bad.add("page " + page + " vs list " + want);
        }
        if (!js.contains("CC.packageChrome(c);")) {
            bad.add("refreshConfig never runs CC.packageChrome");
        }
        return bad.isEmpty() ? null : bad + " (flips when the page's literal copy drifts from"
                + " OpsParity.PACKAGE_ONLY_IDS, loses its packaged test, or stops running - an owner page would then"
                + " show a package-only control)";
    }

    /** The page's owner array = {@code OWNER_IDS} under {@code c.packaged === false}; each owner control starts
     *  {@code hidden} in the HTML (no flash on a package page); the Dev editor click and the Joiner GUI label are
     *  in the JS. */
    private static String chromeOwnerIdsRow(String html, String js) {
        List<String> page = chromeIds(js, "apply(c.packaged === false, [");
        List<String> bad = new ArrayList<>();
        if (page == null) {
            bad.add("no apply(c.packaged === false, [...]) in CC.packageChrome");
        } else if (!new TreeSet<>(OpsParity.OWNER_IDS).equals(new TreeSet<>(page))
                || page.size() != OpsParity.OWNER_IDS.size()) {
            bad.add("page " + page + " vs list " + OpsParity.OWNER_IDS);
        }
        for (String id : OpsParity.OWNER_IDS) {
            String tag = openTag(html, "data-cc=\"" + id + "\"");
            if (tag == null) {
                bad.add(id + " has no page element");
            } else if (!hiddenAttr(tag)) {
                bad.add(id + " does not start hidden");
            }
        }
        if (!js.contains("'/api/dev-editor'")) {
            bad.add("no '/api/dev-editor' click");
        }
        if (!js.contains("'Joiner GUI'")) {
            bad.add("no 'Joiner GUI' label");
        }
        return bad.isEmpty() ? null : bad + " (flips when the owner array drifts from OpsParity.OWNER_IDS, an owner"
                + " control is served visible to a package page, or the Dev editor press / Joiner GUI label leaves"
                + " the script)";
    }

    /** {@code #btn-dev-editor}: once, right after the unchanged join button, a hidden plain button labelled
     *  {@code Dev editor} carrying {@code data-cc="verb.devEditor"}; the join relabel sits under the owner test in
     *  the players part's {@code onConfig}. */
    private static String ownerChromeRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        int ids = count(html, "id=\"btn-dev-editor\"");
        if (ids != 1) {
            bad.add("#btn-dev-editor x" + ids);
        }
        int joins = count(html, JOIN_OPEN);
        int joinAt = html.indexOf(JOIN_OPEN);
        int joinEnd = joinAt < 0 ? -1 : html.indexOf("</button>\n", joinAt);
        if (joins != 1 || joinEnd < 0) {
            bad.add("the join button tag x" + joins + " (its tag must stay byte-identical)");
        } else {
            int devAt = joinEnd + "</button>\n".length();
            int gt = html.indexOf('>', devAt);
            int close = gt < 0 ? -1 : html.indexOf("</button>", gt);
            String tag = gt < 0 ? "" : html.substring(devAt, gt + 1);
            String label = close < 0 ? "" : html.substring(gt + 1, close);
            if (!tag.startsWith("<button ") || !tag.contains(" id=\"btn-dev-editor\"")) {
                bad.add("the element after the join button is not #btn-dev-editor: "
                        + PeerText.bound(tag, 100));
            } else {
                for (String attr : List.of(" type=\"button\"", " data-cc=\"verb.devEditor\"")) {
                    if (!tag.contains(attr)) {
                        bad.add("#btn-dev-editor lacks" + attr);
                    }
                }
                if (!hiddenAttr(tag)) {
                    bad.add("#btn-dev-editor is not hidden");
                }
                if (!"Dev editor".equals(label)) {
                    bad.add("#btn-dev-editor label '" + PeerText.bound(label, 40) + "'");
                }
            }
        }
        int relabel = js.indexOf("CC.text(CC.el('btn-join'), 'Joiner GUI')");
        int test = relabel < 0 ? -1 : js.lastIndexOf("if (", relabel);
        if (relabel < 0 || test < 0 || !js.startsWith("if (c.packaged === false) {", test)
                || js.lastIndexOf("onConfig", relabel) < 0) {
            bad.add("the Joiner GUI relabel is not under if (c.packaged === false) in onConfig");
        }
        return bad.isEmpty() ? null : bad + " (flips if the Dev editor button leaves the bar, moves off the join"
                + " button, loses its id / data-cc / hidden / label, the join tag changes, or the Joiner GUI label"
                + " swap stops being owner-only)";
    }

    /** The quoted ids of the array right after {@code opener} inside {@code CC.packageChrome}; null = absent. */
    private static List<String> chromeIds(String js, String opener) {
        int fn = js.indexOf("packageChrome(c) {");
        if (fn < 0) {
            return null;
        }
        int end = js.indexOf("\n  },", fn);
        int at = js.indexOf(opener, fn);
        if (at < 0 || (end >= 0 && at > end)) {
            return null;
        }
        int from = at + opener.length();
        int close = js.indexOf(']', from);
        if (close < 0) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        Matcher m = QUOTED.matcher(js.substring(from, close));
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /** The whole open tag around the first {@code literal}; null = absent. */
    private static String openTag(String html, String literal) {
        int at = html.indexOf(literal);
        int lt = at < 0 ? -1 : html.lastIndexOf('<', at);
        int gt = at < 0 ? -1 : html.indexOf('>', at);
        return lt < 0 || gt < 0 ? null : html.substring(lt, gt + 1);
    }

    private static boolean hiddenAttr(String tag) {
        return tag.contains(" hidden>") || tag.contains(" hidden ");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    // ---- the rigs --------------------------------------------------------------------------------

    /**
     * The package rig ({@link OpsConsoleSelftest#stage}) re-booted with a recording owner-spawn fake, its rows run
     * with {@code ROOTS} = its roots, then the owner block nested inside (so a row reads both consoles).
     */
    private static void packageBlock(List<String> fails) {
        OpsConsoleSelftest.Rig pkg = new OpsConsoleSelftest.Rig();
        FakeOwnerLaunch pkgFx = new FakeOwnerLaunch();
        try {
            OpsConsoleSelftest.stage(pkg);
            setup("web-owner-pkg-rig", () -> reboot(pkg, pkg.roots, pkgFx, null));
        } catch (RuntimeException failed) { // the SETUP line, then the partial rig removed
            setupFailed(fails, "web-owner-pkg-rig", failed, pkg);
            return;
        }
        OpsRoots prior = OpsRoots.ROOTS.get();
        OpsRoots.ROOTS.set(pkg.roots);
        // Between rows the static seam sits at OwnerWatch.LIVE and
        // requireWatchScript guarantees the real script, so a boot-capture regression would run the real
        // arm-standing-watch.ps1 from the build gate: a throwing guard seam for both blocks (the owner block runs
        // inside this try; every row restores to it), the prior seam back in the finally
        OpsConsole.OwnerWatch guard = OpsConsole.ownerWatch;
        OpsConsole.ownerWatch = seam(pkg, "watch-guard", new WatchSpawn(false, new IOException("no row armed a seam")),
                recordingKill(new ArrayList<>(), true));
        try {
            row(fails, "web-dev-editor-pkg-refused", () -> devEditorPkgRow(pkg, pkgFx));
            row(fails, "web-join-pkg-unchanged", () -> joinPkgRow(pkg, pkgFx));
            row(fails, "web-mods-pkg-unchanged", () -> modsPkgRow(pkg));
            row(fails, "web-watch-pkg-unchanged", () -> watchPkgRow(pkg));
            row(fails, "web-apply-pkg-unchanged", () -> applyPkgRow(pkg));
            ownerBlock(fails, pkg);
        } finally {
            OpsConsole.ownerWatch = guard;
            OpsRoots.ROOTS.set(prior);
            teardown(pkg);
        }
    }

    private static void ownerBlock(List<String> fails, OpsConsoleSelftest.Rig pkg) {
        OpsConsoleSelftest.Rig owner = new OpsConsoleSelftest.Rig();
        FakeOwnerLaunch fx = new FakeOwnerLaunch();
        OpsRoots prior;
        try {
            prior = stageOwner(owner, fx);
        } catch (RuntimeException failed) { // the SETUP line, then the partial rig removed
            setupFailed(fails, "web-owner-rig", failed, owner);
            return;
        }
        try {
            row(fails, "web-dev-editor-owner", () -> devEditorOwnerRow(owner, fx));
            row(fails, "web-join-owner-spawns-joiner-gui", () -> joinOwnerRow(owner, fx));
            row(fails, "web-brick-owner", () -> brickOwnerRow(owner));
            row(fails, "web-mods-owner-roots", () -> modsOwnerRow(owner));
            row(fails, "web-browser-owner-not-asked", () -> browserOwnerRow(owner));
            row(fails, "web-followed-seed", () -> followedSeedRow(owner, pkg));
            // The Arm WATCH press, its follow swap and live refusal, Kill's
            // force branch and the map size Apply - AFTER the seed row (they re-point what the console follows); each
            // row swaps OpsConsole.ownerWatch for recorders and restores it
            row(fails, "web-watch-owner-arms", () -> watchArmsRow(owner));
            row(fails, "web-watch-followed-swap", () -> followedSwapRow(owner));
            row(fails, "web-watch-live-refused", () -> liveRefusedRow(owner));
            row(fails, "web-kill-watch-lane-force", () -> killForceRow(owner));
            row(fails, "web-apply-mapsize-owner", () -> applyOwnerRow(owner, "web-apply-mapsize-owner", "size", "8000",
                    "half", p -> String.valueOf(Math.round(MatchDescriptorCodec.decode(
                            Files.readAllLines(p, StandardCharsets.UTF_8)).halfWidth())), true));
            // The two other owner Applies through the same generic row, then the prefill
            // beside the package's
            row(fails, "web-apply-station-owner", () -> applyOwnerRow(owner, "web-apply-station-owner", "station",
                    "hightech", "station", p -> OpsMatchApply.stationFamilyPrefill(List.of(p)), false));
            row(fails, "web-apply-backdrop-owner", () -> applyOwnerRow(owner, "web-apply-backdrop-owner", "backdrop",
                    "nebula", "backdrop", p -> OpsMatchApply.terrainBackdropPrefill(List.of(p)), false));
            row(fails, "web-apply-prefill-owner", () -> applyPrefillRow(owner, pkg));
        } finally {
            OpsRoots.ROOTS.set(prior);
            teardown(owner);
        }
    }

    /**
     * The owner rig: {@link OpsConsoleSelftest#stage}'s temp world (gateway, recorders, join fakes, client), its
     * console re-booted on {@link OpsRoots#ownerUnder} {@code <root>/owner} with {@code fx} and the follow seed
     * {@code <root>/}{@value #SEED_FILE}; then {@code ROOTS} = the owner roots.
     *
     * @return the prior {@code ROOTS}, for the caller's {@code finally}
     */
    static OpsRoots stageOwner(OpsConsoleSelftest.Rig rig, FakeOwnerLaunch fx) {
        OpsConsoleSelftest.stage(rig);
        setup("web-owner-rig", () -> {
            OpsRoots owner = OpsRoots.ownerUnder(rig.root.resolve("owner"));
            Files.createDirectories(owner.opsFile().getParent());
            reboot(rig, owner, fx, rig.root.resolve(SEED_FILE));
        });
        OpsRoots prior = OpsRoots.ROOTS.get();
        OpsRoots.ROOTS.set(rig.roots);
        return prior;
    }

    /** The rig's console stopped and booted again on {@code roots} through the ONE boot path, the rig's own seams
     *  kept (lease responder, recorders, join fakes) plus {@code fx} and {@code seed}. */
    private static void reboot(OpsConsoleSelftest.Rig rig, OpsRoots roots, FakeOwnerLaunch fx, Path seed)
            throws IOException {
        OpsConsole.Boot old = rig.boot;
        rig.boot = null;
        old.stop();
        rig.roots = roots;
        rig.boot = OpsConsole.boot(roots, 0, rig.recorder, rig.kill, new VpnLeaseProbe.Env(
                InetAddress.getLoopbackAddress(), rig.gateway.getLocalPort(), 3000, rig.leaseLogs,
                Clock.systemUTC()), rig.joinFx, fx, seed);
    }

    private static void teardown(OpsConsoleSelftest.Rig rig) {
        if (rig.boot != null) {
            rig.boot.stop();
        }
        if (rig.gateway != null) {
            rig.gateway.close();
        }
        if (rig.root != null) {
            OpsConsoleSelftest.deleteTree(rig.root);
        }
    }

    // ---- package rig rows -------------------------------------------------------------------------

    /** The ONE package-mode refusal row: keyless = the key refusal, a keyed GET = 405, a keyed POST = the exact
     *  refusal JSON + its stdout row and no spawn. */
    private static String devEditorPkgRow(OpsConsoleSelftest.Rig rig, FakeOwnerLaunch fx) throws Exception {
        OpsConsoleSelftest.Resp keyless = OpsConsoleSelftest.call(rig, "POST", "/api/dev-editor", null, "");
        OpsConsoleSelftest.Resp get = OpsConsoleSelftest.call(rig, "GET", "/api/dev-editor", rig.boot.token(), null);
        OpsConsoleSelftest.Resp[] press = {null};
        List<String> out = OpsConsoleSelftest.captureLines(
                () -> press[0] = OpsConsoleSelftest.post(rig, "/api/dev-editor", ""));
        List<String> bad = new ArrayList<>();
        if (keyless.code() != 403 || !OpsConsole.REFUSED_TOKEN.equals(keyless.body())) {
            bad.add("keyless POST -> " + keyless.code());
        }
        if (get.code() != 405) {
            bad.add("keyed GET -> " + get.code());
        }
        if (press[0].code() != 200 || !"{\"ok\":false,\"detail\":\"refused:not_owner\"}".equals(press[0].body())) {
            bad.add("POST -> " + press[0].code() + " " + PeerText.bound(press[0].body(), 120));
        }
        if (!out.contains("cc-console dev-editor result=refused:not_owner")) {
            bad.add("no refused:not_owner row");
        }
        if (fx.devEditorCalls.get() != 0 || fx.joinerGuiCalls.get() != 0) {
            bad.add("spawns devEditor=" + fx.devEditorCalls.get() + " joinerGui=" + fx.joinerGuiCalls.get());
        }
        return bad.isEmpty() ? null : bad + " (the one package-mode refusal row - the package join, mods and brick"
                + " stay pinned by the existing web-join, web-mods-* and web-brick rows; flips if a package console"
                + " runs the dev editor script, answers anything but the refusal, or the route leaves the key gate)";
    }

    /** The package join press is still the package's Join from this PC: no joiner GUI spawn, note or row. */
    private static String joinPkgRow(OpsConsoleSelftest.Rig rig, FakeOwnerLaunch fx) throws Exception {
        int gui = fx.joinerGuiCalls.get();
        OpsConsoleSelftest.Resp[] press = {null};
        List<String> out = OpsConsoleSelftest.captureLines(() -> press[0] = OpsConsoleSelftest.post(rig, "/api/join", ""));
        List<String> bad = new ArrayList<>();
        if (fx.joinerGuiCalls.get() != gui) {
            bad.add("joiner GUI spawned x" + (fx.joinerGuiCalls.get() - gui));
        }
        if (press[0].body().contains(OpsConsoleForms.jsonEscape(OpsConsole.JOINER_GUI_NOTE))) {
            bad.add("answered the joiner GUI note");
        }
        if (out.stream().anyMatch(l -> l.contains("result=spawned-joiner-gui"))) {
            bad.add("printed result=spawned-joiner-gui");
        }
        return bad.isEmpty() ? null : bad + " (flips if a package console's join press takes the owner branch)";
    }

    /** A package's mods roots stay its own folder when no roots row is saved. */
    private static String modsPkgRow(OpsConsoleSelftest.Rig rig) throws Exception {
        String r = "web-mods-pkg-unchanged";
        Path config = rig.roots.config();
        byte[][] prior = {null};
        setup(r, () -> {
            prior[0] = OpsConsoleSelftest.takeFile(config);
            Files.writeString(config, "# posture\nmatch.descriptor=match-web-pkg.txt\nmatch.fortressDp=400\n",
                    StandardCharsets.UTF_8);
        });
        OpsConsoleSelftest.Resp get;
        try {
            get = OpsConsoleSelftest.call(rig, "GET", "/api/mods", rig.boot.token(), null);
        } finally {
            setup(r, () -> OpsConsoleSelftest.restoreFile(config, prior[0]));
        }
        String want = "{" + rootsJson(PackageModSync.PACKAGE_DEFAULT_ROOTS) + ",";
        return get.code() == 200 && get.body().startsWith(want) ? null : "GET /api/mods -> " + get.code() + " "
                + PeerText.bound(get.body(), 120) + " (flips if a package console shows the repository's mod roots)";
    }

    // ---- owner rig rows ---------------------------------------------------------------------------

    /** Dev editor in owner mode: ONE spawn + the note naming the script; a planted throw = the titled error with
     *  the class only. */
    private static String devEditorOwnerRow(OpsConsoleSelftest.Rig rig, FakeOwnerLaunch fx) throws Exception {
        int dev = fx.devEditorCalls.get();
        int gui = fx.joinerGuiCalls.get();
        OpsConsoleSelftest.Resp[] press = {null, null};
        List<String> out = OpsConsoleSelftest.captureLines(
                () -> press[0] = OpsConsoleSelftest.post(rig, "/api/dev-editor", ""));
        int devAfter = fx.devEditorCalls.get();
        List<String> faultOut;
        fx.fail.set(new IOException("x-marker"));
        try {
            faultOut = OpsConsoleSelftest.captureLines(
                    () -> press[1] = OpsConsoleSelftest.post(rig, "/api/dev-editor", ""));
        } finally {
            fx.fail.set(null);
        }
        List<String> bad = new ArrayList<>();
        String wantOk = "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(OpsConsole.DEV_EDITOR_NOTE) + "\"}";
        if (!wantOk.equals(press[0].body()) || !OpsConsole.DEV_EDITOR_NOTE.contains(ServerOpsCore.DEV_EDITOR_SCRIPT)) {
            bad.add("press -> " + PeerText.bound(press[0].body(), 120));
        }
        if (devAfter != dev + 1 || fx.joinerGuiCalls.get() != gui) {
            bad.add("spawns devEditor +" + (devAfter - dev) + " joinerGui +" + (fx.joinerGuiCalls.get() - gui));
        }
        if (!out.contains("cc-console dev-editor result=spawned")) {
            bad.add("no result=spawned row");
        }
        String fault = press[1].body();
        if (!fault.endsWith("\"title\":\"Dev editor\"}") || fault.contains("x-marker") || fault.contains("\"ok\":true")) {
            bad.add("fault -> " + PeerText.bound(fault.replace("x-marker", "<marker>"), 120));
        }
        if (!faultOut.contains("cc-console dev-editor result=fault")) {
            bad.add("no result=fault row");
        }
        return bad.isEmpty() ? null : bad + " (flips if owner mode stops spawning the dev editor once per press,"
                + " changes its note, or a failed spawn answers ok or prints the exception's message)";
    }

    /** The join press in owner mode = ONE joiner GUI spawn + its note; the package join press is never reached. */
    private static String joinOwnerRow(OpsConsoleSelftest.Rig rig, FakeOwnerLaunch fx) throws Exception {
        int gui = fx.joinerGuiCalls.get();
        int dev = fx.devEditorCalls.get();
        int joinSpawns = rig.joinFx.spawns.size();
        OpsConsoleSelftest.Resp[] press = {null};
        List<String> out = OpsConsoleSelftest.captureLines(() -> press[0] = OpsConsoleSelftest.post(rig, "/api/join", ""));
        List<String> bad = new ArrayList<>();
        String want = "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(OpsConsole.JOINER_GUI_NOTE) + "\"}";
        if (!want.equals(press[0].body())) {
            bad.add("press -> " + PeerText.bound(press[0].body(), 120));
        }
        if (fx.joinerGuiCalls.get() != gui + 1 || fx.devEditorCalls.get() != dev) {
            bad.add("spawns joinerGui +" + (fx.joinerGuiCalls.get() - gui) + " devEditor +"
                    + (fx.devEditorCalls.get() - dev));
        }
        if (rig.joinFx.spawns.size() != joinSpawns) {
            bad.add("the package join press spawned x" + (rig.joinFx.spawns.size() - joinSpawns));
        }
        if (!out.contains("cc-console join result=spawned-joiner-gui")) {
            bad.add("no result=spawned-joiner-gui row");
        }
        return bad.isEmpty() ? null : bad + " (flips if owner mode's join press stops spawning the joiner GUI once,"
                + " changes its note, or falls through to the package Join from this PC press)";
    }

    /**
     * Copy join brick in owner mode. (a) A silent gateway and no running serve = the owner-only refusal (a package
     * would brick the typed Advertise). (b) An answering gateway plus a fresh port line = the brick of the LEASE
     * endpoint (not the typed one), {@code advertiseSaved:false}, a {@code fill}, and no launcher row written.
     */
    private static String brickOwnerRow(OpsConsoleSelftest.Rig rig) throws Exception {
        String r = "web-brick-owner";
        Path launcher = rig.roots.pkg().resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
        String portLine = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC) + " | Port pair 1->51340";
        Matcher pair = VpnLeaseProbe.PORT_PAIR.matcher(portLine);
        String leasePort = pair.find() ? pair.group(2) : "?";
        String typed = "advertise=" + URLEncoder.encode("203.0.113.5:51400", StandardCharsets.UTF_8);
        String password = "&passwordAction=replace&password=" + BRICK_PASSWORD;
        byte[][] prior = {null};
        setup(r, () -> prior[0] = OpsConsoleSelftest.takeFile(launcher));
        OpsConsoleSelftest.Resp[] answers = {null, null};
        List<String> silentOut;
        Map<String, String> rows = Map.of();
        boolean launcherWritten;
        try {
            rig.silent.set(true);
            silentOut = OpsConsoleSelftest.captureLines(
                    () -> answers[0] = OpsConsoleSelftest.post(rig, "/api/brick", typed + password));
            rig.silent.set(false);
            setup(r, () -> {
                Files.createDirectories(rig.leaseLogs);
                Files.writeString(rig.leaseLogs.resolve("client-logs.txt"), portLine + "\r\n", StandardCharsets.UTF_8);
            });
            answers[1] = OpsConsoleSelftest.post(rig, "/api/brick", typed + password);
            launcherWritten = Files.exists(launcher);
            if (launcherWritten) {
                rows = ServeLauncher.readLauncherRows(launcher, System.out);
            }
        } finally {
            rig.silent.set(false);
            setup(r, () -> {
                if (Files.exists(rig.leaseLogs)) {
                    OpsConsoleSelftest.deleteTree(rig.leaseLogs);
                }
                OpsConsoleSelftest.restoreFile(launcher, prior[0]);
            });
        }
        List<String> bad = new ArrayList<>();
        String silent = answers[0].body();
        if (!silent.contains("VPN lease pull failed (") || !silent.contains("no server is running to confirm")
                || silent.contains("\"ok\":true")) {
            bad.add("(a) silent gateway -> " + PeerText.bound(silent.replace(BRICK_PASSWORD, "<pw>"), 160));
        }
        if (!silentOut.contains("cc-console lease result=unchecked reason=gateway_silent press=brick")) {
            bad.add("(a) no unchecked lease row");
        }
        String brick = OpsConsoleForms.jsonEscape(ServerOpsCore.joinBrick("203.0.113.5:" + leasePort, BRICK_PASSWORD));
        String leased = answers[1].body();
        if (!leased.startsWith("{\"ok\":true,\"brick\":\"" + brick + "\",\"advertiseSaved\":false,\"fill\":{")) {
            bad.add("(b) lease brick -> " + PeerText.bound(leased.replace(BRICK_PASSWORD, "<pw>"), 160));
        }
        if (rows.containsKey(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE)) {
            bad.add("(b) an advertise row was saved");
        }
        return bad.isEmpty() ? null : bad + " (flips if owner mode bricks the typed Advertise without a lease or a"
                + " running serve, bricks anything but the lease endpoint, or saves a launcher row as the package"
                + " brick does)";
    }

    /** Owner mode with no roots row searches the repository's default mod roots; a rescan answers the same and
     *  writes nothing. */
    private static String modsOwnerRow(OpsConsoleSelftest.Rig rig) throws Exception {
        String r = "web-mods-owner-roots";
        Path config = rig.roots.config();
        byte[][] prior = {null};
        setup(r, () -> prior[0] = OpsConsoleSelftest.takeFile(config));
        OpsConsoleSelftest.Resp get;
        OpsConsoleSelftest.Resp rescan;
        boolean written;
        try {
            get = OpsConsoleSelftest.call(rig, "GET", "/api/mods", rig.boot.token(), null);
            rescan = OpsConsoleSelftest.post(rig, "/api/mods", "op=rescan");
            written = Files.exists(config);
        } finally {
            setup(r, () -> OpsConsoleSelftest.restoreFile(config, prior[0]));
        }
        String roots = rootsJson(InstalledModScan.DEFAULT_ROOTS);
        List<String> bad = new ArrayList<>();
        if (!get.body().startsWith("{" + roots + ",")) {
            bad.add("GET -> " + PeerText.bound(get.body(), 120));
        }
        if (!rescan.body().startsWith("{\"ok\":true," + roots + ",")) {
            bad.add("rescan -> " + PeerText.bound(rescan.body(), 120));
        }
        if (written) {
            bad.add("the rescan wrote the server properties");
        }
        return bad.isEmpty() ? null : bad + " (flips if owner mode shows the package's own mods folder instead of"
                + " the repository's default roots, or a rescan writes)";
    }

    /** Owner mode's address lines: not asked = the KEYLESS address + {@code result=not-asked}, no spawn; asked =
     *  the keyed {@code open} line + ONE browser spawn of the fixed argv. */
    private static String browserOwnerRow(OpsConsoleSelftest.Rig rig) throws Exception {
        List<List<String>> spawned = new ArrayList<>();
        OpsConsole.BrowserSpawn recorder = argv -> spawned.add(List.copyOf(argv));
        String token = rig.boot.token();
        List<String> quiet = OpsConsoleSelftest.captureLines(
                () -> OpsConsole.printOwnerAddress(rig.boot, false, true, recorder));
        int afterQuiet = spawned.size();
        List<String> asked = OpsConsoleSelftest.captureLines(
                () -> OpsConsole.printOwnerAddress(rig.boot, true, true, recorder));
        String url = "http://127.0.0.1:" + rig.boot.port() + "/#" + token;
        String root = System.getenv("SystemRoot");
        List<String> argv = OpsConsole.browserCommand(root == null || root.isBlank() ? "C:\\Windows" : root, url);
        List<String> bad = new ArrayList<>();
        if (!List.of("address: http://127.0.0.1:" + rig.boot.port() + "/", "cc-console browser result=not-asked")
                .equals(quiet) || String.join("\n", quiet).contains(token) || afterQuiet != 0) {
            bad.add("not asked: " + quiet.size() + " line(s) keyed=" + String.join("\n", quiet).contains(token)
                    + " spawns=" + afterQuiet);
        }
        if (!List.of("open " + url, "cc-console browser result=opened").equals(asked) || spawned.size() != 1
                || !argv.equals(spawned.get(0))) {
            bad.add("asked: " + asked.size() + " line(s) spawns=" + spawned.size());
        }
        return bad.isEmpty() ? null : bad + " (flips if owner mode prints the key or opens a browser unasked, or an"
                + " asked open stops spawning the fixed browser command once)";
    }

    /**
     * The positional ops file seeds what the console follows: the owner console's no-file status names the SEED
     * even while its roots' own status file holds a live snapshot; the package console's names its roots' file.
     * Both read with {@code ROOTS} = the owner roots (the no-file detail names the file only outside a package).
     */
    private static String followedSeedRow(OpsConsoleSelftest.Rig owner, OpsConsoleSelftest.Rig pkg) throws Exception {
        String r = "web-followed-seed";
        Path planted = owner.roots.opsFile();
        setup(r, () -> Files.write(planted, OpsStatusFile.composeLines(System.currentTimeMillis(),
                ProcessHandle.current().pid(), "run", "127.0.0.1", 51398, 7, 1, 2, 2L, 4, 60.0, 12.5, 3.2, 1000L,
                500L, 100L, 50L, 0, 0.0, 0L, owner.cmd.toString(), List.of(), new long[0][],
                PhaseWindowStats.Snapshot.EMPTY), StandardCharsets.UTF_8));
        OpsConsoleSelftest.Resp ownerStatus;
        OpsConsoleSelftest.Resp pkgStatus;
        try {
            ownerStatus = OpsConsoleSelftest.call(owner, "GET", "/api/status", owner.boot.token(), null);
            pkgStatus = OpsConsoleSelftest.call(pkg, "GET", "/api/status", pkg.boot.token(), null);
        } finally {
            setup(r, () -> Files.deleteIfExists(planted));
        }
        Path seed = owner.root.resolve(SEED_FILE);
        List<String> bad = new ArrayList<>();
        if (!ownerStatus.body().contains(detail(ServerOpsCore.noFileDetail(seed.toString(), false)))) {
            bad.add("owner status " + PeerText.bound(ownerStatus.body(), 160));
        }
        if (!pkgStatus.body().contains(detail(ServerOpsCore.noFileDetail(pkg.roots.opsFile().toString(), false)))) {
            bad.add("package status " + PeerText.bound(pkgStatus.body(), 160));
        }
        return bad.isEmpty() ? null : bad + " (flips if the positional ops file stops seeding what the owner console"
                + " follows, or a package console stops following its roots' status file)";
    }

    // ---- the Arm WATCH press, Kill's force branch and the owner map Applies ----------------------------

    /** The watch status file's name inside each row's own seam folder. */
    private static final String WATCH_STATUS = "ops-status.txt";
    /** The planted brief: its stamp line, then the body the press must forward. */
    private static final String BRIEF_TEXT = "# armed 20261017-1200\nfixture brief body\n";
    /** The pid the force-kill legs' status file names (fake: the seam's kill only records it; &gt; 0). */
    private static final long FORCE_PID = 424242L;
    /** The package rig's descriptor an owner-rig Start boots (copied beside the owner config). */
    private static final String START_DESCRIPTOR = "match-web-pkg.txt";
    private static final String ARMED_ROW = "cc-console watch result=armed";
    private static final String ARMED_HEAD = "{\"ok\":true,\"detail\":\"debug WATCH handed to ";
    private static final Pattern CONFIRM_KEY = Pattern.compile("\"key\":\"([^\"]*)\"");
    /** The system properties the owner Applies read their descriptor sets and deployed mod root from. */
    private static final List<String> APPLY_PROPERTIES = List.of(OpsMatchApply.MAP_SIZE_DESCRIPTORS_PROPERTY,
            OpsMatchApply.STATION_DESCRIPTORS_PROPERTY, OpsMatchApply.MAP_SIZE_DEPLOYED_ROOT_PROPERTY);
    /** The Applies fixture's prefill: the owner config's map half, the fortress descriptor's family and backdrop. */
    private static final String FIXTURE_PREFILL =
            "{\"half\":\"9000\",\"station\":\"lowtech\",\"backdrop\":\"hyper\",\"deployCr\":\"\",\"problem\":\"\"}";

    /**
     * The Arm WATCH seam's spawn - records every argv and answers a fake
     * process: already exited ({@code alive} false, so the next press may arm) or still running ({@code exit} unset,
     * so the console's live gate refuses); {@code fail} set = the spawn throws it after recording. Never a process.
     */
    static final class WatchSpawn implements ServerOpsCore.SpawnFn {
        final List<List<String>> argv = Collections.synchronizedList(new ArrayList<>());
        final List<OpsPressSelftest.FakeProcess> made = Collections.synchronizedList(new ArrayList<>());
        private final boolean alive;
        private final IOException fail;

        WatchSpawn(boolean alive, IOException fail) {
            this.alive = alive;
            this.fail = fail;
        }

        @Override
        public Process spawn(List<String> cmd) throws IOException {
            argv.add(List.copyOf(cmd));
            if (fail != null) {
                throw fail;
            }
            OpsPressSelftest.FakeProcess p = new OpsPressSelftest.FakeProcess();
            if (!alive) {
                p.exit = 0;
            }
            made.add(p);
            return p;
        }
    }

    /** One row's Arm WATCH seams under {@code <rig root>/<dir>} - the
     *  watch status file, the forward folder, the brief file (absent until a leg plants it) and {@code kill}. */
    private static OpsConsole.OwnerWatch seam(OpsConsoleSelftest.Rig rig, String dir, ServerOpsCore.SpawnFn spawn,
            OpsConsole.PidKill kill) {
        Path base = rig.root.resolve(dir);
        return new OpsConsole.OwnerWatch(spawn, base.resolve(WATCH_STATUS), base.resolve("forward"),
                base.resolve("brief").resolve("current.txt"), kill);
    }

    /** A force-kill that records the pid and answers the Swing note -
     *  signalled, or gone. */
    private static OpsConsole.PidKill recordingKill(List<Long> pids, boolean signalled) {
        return pid -> {
            pids.add(pid);
            return signalled ? OpsConsole.PidKill.signalledNote(pid) : OpsConsole.PidKill.goneNote(pid);
        };
    }

    /** The press checks for the watch script under the working
     *  directory before it spawns, so a run from elsewhere cannot stage these rows (SETUP, never a kill). */
    private static void requireWatchScript(String row) {
        if (!Files.isRegularFile(Paths.get(ServerOpsCore.WATCH_SCRIPT))) {
            throw new SetupFail(row, new IOException("the watch script is not under the working directory"));
        }
    }

    private static OpsConsoleSelftest.Resp status(OpsConsoleSelftest.Rig rig) throws Exception {
        return OpsConsoleSelftest.call(rig, "GET", "/api/status", rig.boot.token(), null);
    }

    private static OpsConsoleSelftest.Resp watchPress(OpsConsoleSelftest.Rig rig, String body) throws Exception {
        return OpsConsoleSelftest.post(rig, "/api/watch", body);
    }

    /** The status answer's no-file detail for {@code opsFile} (the owner roots: the detail names the file). */
    private static String noFile(Path opsFile) {
        return detail(ServerOpsCore.noFileDetail(opsFile.toString(), false));
    }

    private static List<Path> forwards(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("forward-")).sorted().toList();
        }
    }

    private static String confirmKey(String body) {
        Matcher m = CONFIRM_KEY.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /** The package selftest's settle wait (the staging hold + the lease daemon), FAILABLE here: a hold
     *  still held after its bound is a fault the row shows, never a silent next press. */
    private static void awaitStaging() throws InterruptedException {
        OpsConsoleSelftest.awaitStaging();
        if (ServerOpsLaunch.STAGING.get()) {
            throw new IllegalStateException("staging still held after 10 s");
        }
    }

    /** The keyed, confirmed Start form (the package selftest's, copied: its own is private). */
    private static String startFields(String descriptor) {
        return "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400&descriptor="
                + URLEncoder.encode(descriptor, StandardCharsets.UTF_8) + "&passwordAction=keep&confirmed="
                + ServerOpsCore.CONFIRM_TOKEN_MODE_START + "&confirmed=" + ServerOpsCore.CONFIRM_ARMED_DOOR;
    }

    /**
     * A Start on the owner rig - a started one points what the console
     * follows back at the launcher's status file. The package rig's descriptor is copied beside the owner config and
     * that config planted when absent; any further confirm the answer asks for is answered in ONE more round; the
     * staging hold is awaited both sides. The stage spawn is the rig's recorder (an exited fake): no script runs.
     */
    private static OpsConsoleSelftest.Resp ownerStart(OpsConsoleSelftest.Rig rig, String row) throws Exception {
        Path config = rig.roots.config();
        Path descriptor = config.resolveSibling(START_DESCRIPTOR);
        setup(row, () -> {
            if (!Files.exists(descriptor)) {
                Files.copy(rig.pkg.resolve(START_DESCRIPTOR), descriptor);
            }
            if (!Files.exists(config)) {
                Files.writeString(config, "# posture\nmatch.descriptor=" + START_DESCRIPTOR
                        + "\nmatch.fortressDp=400\n", StandardCharsets.UTF_8);
            }
            awaitStaging();
        });
        String fields = startFields(START_DESCRIPTOR);
        OpsConsoleSelftest.Resp r = OpsConsoleSelftest.post(rig, "/api/start", fields);
        if (r.body().startsWith("{\"needsConfirm\"")) {
            StringBuilder more = new StringBuilder(fields);
            Matcher m = CONFIRM_KEY.matcher(r.body());
            while (m.find()) {
                more.append("&confirmed=").append(URLEncoder.encode(m.group(1), StandardCharsets.UTF_8));
            }
            r = OpsConsoleSelftest.post(rig, "/api/start", more.toString());
        }
        awaitStaging();
        return r;
    }

    /**
     * {@code web-watch-owner-arms} - the owner-mode Arm WATCH press spawns
     * the watch script's FIXED argv and nothing of the request. (A) no brief file and a body carrying a game folder and
     * a password: the brief-less argv with no request byte in it or the answer, the armed row, the launch registered
     * (status's launch member names the watch lane and the seam's status file). (B) a planted brief: ONE forward file
     * holding its body, the argv naming it. (C) a folder at the brief path: one brief_unreadable row, still armed
     * brief-less, no second forward file.
     */
    private static String watchArmsRow(OpsConsoleSelftest.Rig rig) throws Exception {
        String r = "web-watch-owner-arms";
        requireWatchScript(r);
        WatchSpawn spawn = new WatchSpawn(false, null);
        OpsConsole.OwnerWatch seams = seam(rig, "watch-arms", spawn, recordingKill(new ArrayList<>(), true));
        Path brief = seams.briefFileOrNull();
        OpsConsoleSelftest.Resp[] press = new OpsConsoleSelftest.Resp[3];
        List<List<String>> out = new ArrayList<>();
        OpsConsoleSelftest.Resp st;
        List<Path> fwd;
        String fwdText = null;
        int fwdAfter;
        OpsConsole.OwnerWatch prior = OpsConsole.ownerWatch;
        try {
            OpsConsole.ownerWatch = seams;
            out.add(OpsConsoleSelftest.captureLines(
                    () -> press[0] = watchPress(rig, "gameRoot=C%3A%5Cevil&password=leak-me")));
            st = status(rig);
            setup(r, () -> {
                Files.createDirectories(brief.getParent());
                Files.writeString(brief, BRIEF_TEXT, StandardCharsets.UTF_8);
            });
            out.add(OpsConsoleSelftest.captureLines(() -> press[1] = watchPress(rig, "")));
            fwd = forwards(seams.forwardDir());
            if (fwd.size() == 1) {
                fwdText = Files.readString(fwd.get(0), StandardCharsets.UTF_8);
            }
            setup(r, () -> {
                Files.delete(brief);
                Files.createDirectories(brief);
            });
            out.add(OpsConsoleSelftest.captureLines(() -> press[2] = watchPress(rig, "")));
            fwdAfter = forwards(seams.forwardDir()).size();
        } finally {
            OpsConsole.ownerWatch = prior;
        }
        List<String> bad = new ArrayList<>();
        List<List<String>> argv = List.copyOf(spawn.argv);
        for (int i = 0; i < press.length; i++) {
            if (!press[i].body().startsWith(ARMED_HEAD) || !out.get(i).contains(ARMED_ROW)) {
                bad.add("(" + (char) ('A' + i) + ") press -> " + PeerText.bound(press[i].body(), 120));
            }
        }
        boolean leaked = argv.stream().flatMap(List::stream).anyMatch(a -> a.contains("evil") || a.contains("leak-me"))
                || press[0].body().contains("evil") || press[0].body().contains("leak-me");
        if (leaked) {
            bad.add("(A) a request field reached the argv or the answer");
        }
        if (argv.size() != 3) {
            bad.add("spawns x" + argv.size() + " for three presses");
        } else {
            if (!ServerOpsLaunch.watchCommand((Path) null).equals(argv.get(0))) {
                bad.add("(A) the argv is not the brief-less watch command");
            }
            if (fwd.size() == 1 && !ServerOpsLaunch.watchCommand(fwd.get(0)).equals(argv.get(1))) {
                bad.add("(B) the argv does not name the one forward file");
            }
            if (!ServerOpsLaunch.watchCommand((Path) null).equals(argv.get(2))) {
                bad.add("(C) the argv is not the brief-less watch command");
            }
        }
        int launchAt = st.body().indexOf("\"launch\":{");
        int launchEnd = launchAt < 0 ? -1 : st.body().indexOf('}', launchAt);
        String launch = launchEnd < 0 ? "" : st.body().substring(launchAt, launchEnd);
        if (!launch.contains(ServerOpsCore.WATCH_LANE + " ")
                || !launch.contains(ServerOpsCore.jsonEscape(seams.watchOps().toAbsolutePath().normalize().toString()))) {
            bad.add("(A) the status launch member names no watch launch: " + PeerText.bound(launch, 160));
        }
        if (fwd.size() != 1 || !"fixture brief body\n".equals(fwdText)) {
            bad.add("(B) forward files x" + fwd.size() + " body "
                    + (fwdText == null ? "none" : "'" + PeerText.bound(fwdText, 60) + "'"));
        }
        long unreadable = out.get(2).stream().filter(l -> l.startsWith("cc-console watch brief_unreadable class="))
                .count();
        if (unreadable != 1 || out.get(0).stream().anyMatch(l -> l.contains("brief_unreadable"))) {
            bad.add("(C) brief_unreadable rows x" + unreadable);
        }
        if (fwdAfter != 1) {
            bad.add("(C) forward files x" + fwdAfter + " after the unreadable brief");
        }
        return bad.isEmpty() ? null : bad + " (flips if the owner Arm WATCH branch is missing, a request field reaches"
                + " the argv, the launch is not registered, the server brief stops forwarding, or an unreadable brief"
                + " stops logging once / stops the press)";
    }

    /**
     * {@code web-watch-followed-swap} - an armed press re-points the console
     * at the seam's watch status file with a FRESH follow (new writer watch, no seat rows): status names that file and
     * says {@code watchLane:true}. Then a press whose spawn THROWS (a seam with its own, different status file) answers
     * the fault and swaps nothing: the same follow instance, status still names the armed press's file. Then a started
     * Start points it back at the roots' status file with another fresh follow, {@code watchLane:false}. The row's
     * start state is the previous row's watch file (not the roots' file): the seed is pinned by
     * {@code web-followed-seed}.
     */
    private static String followedSwapRow(OpsConsoleSelftest.Rig rig) throws Exception {
        String r = "web-watch-followed-swap";
        requireWatchScript(r);
        OpsConsole console = rig.boot.console();
        List<Long> kills = Collections.synchronizedList(new ArrayList<>());
        OpsConsole.OwnerWatch good = seam(rig, "watch-swap", new WatchSpawn(false, null), recordingKill(kills, true));
        WatchSpawn throwing = new WatchSpawn(false, new IOException("planted spawn failure"));
        OpsConsole.OwnerWatch fault = seam(rig, "watch-fault", throwing, recordingKill(kills, true));
        OpsConsole.Follow before = console.followed.get();
        OpsConsole.Follow armedFollow;
        OpsConsole.Follow faultFollow;
        OpsConsole.Follow startFollow;
        OpsConsoleSelftest.Resp[] resp = new OpsConsoleSelftest.Resp[2];
        OpsConsoleSelftest.Resp st1;
        OpsConsoleSelftest.Resp st2;
        OpsConsoleSelftest.Resp st3;
        OpsConsoleSelftest.Resp start;
        List<String> faultOut;
        OpsConsole.OwnerWatch prior = OpsConsole.ownerWatch;
        try {
            OpsConsole.ownerWatch = good;
            resp[0] = watchPress(rig, "");
            armedFollow = console.followed.get();
            st1 = status(rig);
            OpsConsole.ownerWatch = fault;
            faultOut = OpsConsoleSelftest.captureLines(() -> resp[1] = watchPress(rig, ""));
            faultFollow = console.followed.get();
            OpsConsole.ownerWatch = good;
            st2 = status(rig);
            start = ownerStart(rig, r);
            startFollow = console.followed.get();
            st3 = status(rig);
        } finally {
            OpsConsole.ownerWatch = prior;
        }
        Path watchAbs = good.watchOps().toAbsolutePath().normalize();
        Path faultAbs = fault.watchOps().toAbsolutePath().normalize();
        List<String> bad = new ArrayList<>();
        if (!resp[0].body().startsWith(ARMED_HEAD)) {
            bad.add("press -> " + PeerText.bound(resp[0].body(), 120));
        }
        if (!watchAbs.equals(armedFollow.opsFile()) || armedFollow.writerWatch() == before.writerWatch()
                || !armedFollow.lastRunSeats().isEmpty()) {
            bad.add("the armed press's follow is not a fresh one on the watch file");
        }
        if (!st1.body().contains(noFile(watchAbs)) || !st1.body().contains("\"watchLane\":true")) {
            bad.add("status after the press " + PeerText.bound(st1.body(), 160));
        }
        if (resp[1].body().contains("\"ok\":true") || !resp[1].body().contains("\"title\":\"Watch failed\"")
                || !faultOut.contains("cc-console watch result=fault") || throwing.argv.size() != 1) {
            bad.add("the throwing press -> " + PeerText.bound(resp[1].body(), 120) + " spawns x" + throwing.argv.size());
        }
        if (faultFollow != armedFollow || !st2.body().contains(noFile(watchAbs)) || st2.body().contains(noFile(faultAbs))) {
            bad.add("a failed press swapped what the console follows");
        }
        if (!start.body().startsWith("{\"ok\":true")) {
            bad.add("the owner Start -> " + PeerText.bound(start.body(), 160));
        }
        if (!rig.roots.opsFile().equals(startFollow.opsFile()) || startFollow.writerWatch() == armedFollow.writerWatch()
                || !st3.body().contains(noFile(rig.roots.opsFile())) || !st3.body().contains("\"watchLane\":false")) {
            bad.add("after the Start: status " + PeerText.bound(st3.body(), 160));
        }
        if (!kills.isEmpty()) {
            bad.add("a force-kill ran x" + kills.size());
        }
        return bad.isEmpty() ? null : bad + " (flips if status keeps reading the launcher's file after an armed press,"
                + " a swap keeps the old writer watch, a failed press swaps, or a started Start never swaps back)";
    }

    /**
     * {@code web-watch-live-refused} - a watch whose spawned process is
     * still running blocks a second press: the Start's own live refusal under the press's title, the
     * {@code refused:live} row, still ONE spawn; a Start while it lives is refused with the same sentence (the launch
     * IS registered as this console's). The fake is ended in {@code finally} so later presses may arm.
     */
    private static String liveRefusedRow(OpsConsoleSelftest.Rig rig) throws Exception {
        String r = "web-watch-live-refused";
        requireWatchScript(r);
        WatchSpawn alive = new WatchSpawn(true, null);
        OpsConsole.OwnerWatch seams = seam(rig, "watch-live", alive, recordingKill(new ArrayList<>(), true));
        OpsConsoleSelftest.Resp[] resp = new OpsConsoleSelftest.Resp[2];
        List<String> secondOut;
        OpsConsoleSelftest.Resp start;
        String live = null;
        OpsConsole.OwnerWatch prior = OpsConsole.ownerWatch;
        try {
            OpsConsole.ownerWatch = seams;
            resp[0] = watchPress(rig, "");
            secondOut = OpsConsoleSelftest.captureLines(() -> resp[1] = watchPress(rig, ""));
            start = ownerStart(rig, r);
            if (!alive.made.isEmpty()) { // the expected sentence, computed while the fake still runs
                live = OpsConsole.liveLaunchRefusal(new LaneFollow.LaneLaunch(ServerOpsCore.WATCH_LANE,
                        seams.watchOps(), ServerOpsCore.WATCH_LOG, 0L, alive.made.get(0)));
            }
        } finally {
            OpsConsole.ownerWatch = prior;
            for (OpsPressSelftest.FakeProcess p : alive.made) {
                p.exit = 0;
            }
        }
        List<String> bad = new ArrayList<>();
        if (!resp[0].body().startsWith(ARMED_HEAD) || live == null) {
            bad.add("first press -> " + PeerText.bound(resp[0].body(), 120));
        } else {
            if (!OpsConsoleForms.errorJson(OpsConsole.OWNER_WATCH_TITLE, live).equals(resp[1].body())) {
                bad.add("second press -> " + PeerText.bound(resp[1].body(), 160));
            }
            if (!OpsConsoleForms.errorJson("Start refused", live).equals(start.body())) {
                bad.add("a Start while the watch lives -> " + PeerText.bound(start.body(), 160));
            }
        }
        if (!secondOut.contains("cc-console watch result=refused:live") || secondOut.contains(ARMED_ROW)) {
            bad.add("no refused:live row for the second press");
        }
        if (alive.argv.size() != 1) {
            bad.add("spawns x" + alive.argv.size());
        }
        return bad.isEmpty() ? null : bad + " (flips if a second press re-arms over a live watch, or the armed launch"
                + " stops being the console's registered launch)";
    }

    /**
     * {@code web-kill-watch-lane-force} - on the WATCH lane (an armed press,
     * the seam's status file naming pid {@value #FORCE_PID}) Kill asks the Swing force question, then ends that pid
     * through the seam: {@code force_signalled} with the signalled note, or {@code force_gone} with the gone note
     * (a second seam on the same status file); the console's stop-file kill is never called. After a Start points the
     * console back at the launcher's status file, Kill is that stop-file kill again (called once, no force row).
     */
    private static String killForceRow(OpsConsoleSelftest.Rig rig) throws Exception {
        String r = "web-kill-watch-lane-force";
        requireWatchScript(r);
        List<Long> killed = Collections.synchronizedList(new ArrayList<>());
        WatchSpawn spawn = new WatchSpawn(false, null);
        OpsConsole.OwnerWatch signalled = seam(rig, "watch-kill", spawn, recordingKill(killed, true));
        OpsConsole.OwnerWatch gone = new OpsConsole.OwnerWatch(spawn, signalled.watchOps(), signalled.forwardDir(),
                signalled.briefFileOrNull(), recordingKill(killed, false));
        Path watchOps = signalled.watchOps();
        AtomicInteger killFnCalls = new AtomicInteger();
        OpsConsole.KillFn priorKill = rig.killMode.get();
        OpsConsoleSelftest.Resp[] resp = new OpsConsoleSelftest.Resp[3];
        List<List<String>> out = new ArrayList<>();
        OpsConsoleSelftest.Resp armed;
        OpsConsoleSelftest.Resp ask;
        OpsConsoleSelftest.Resp askPublic;
        OpsConsoleSelftest.Resp start;
        int callsOnWatch;
        int killedOnWatch;
        OpsConsole.OwnerWatch prior = OpsConsole.ownerWatch;
        try {
            rig.killMode.set((o, w, l) -> {
                killFnCalls.incrementAndGet();
                return priorKill.kill(o, w, l);
            });
            OpsConsole.ownerWatch = signalled;
            armed = watchPress(rig, "");
            setup(r, () -> {
                Files.createDirectories(watchOps.getParent());
                Files.write(watchOps, OpsStatusFile.composeLines(System.currentTimeMillis(), FORCE_PID, "run",
                        "127.0.0.1", 51398, 7, 1, 2, 2L, 4, 60.0, 12.5, 3.2, 1000L, 500L, 100L, 50L, 0, 0.0, 0L,
                        rig.cmd.toString(), List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY),
                        StandardCharsets.UTF_8);
            });
            ask = OpsConsoleSelftest.post(rig, "/api/kill", "");
            String key = "confirmed=" + URLEncoder.encode(confirmKey(ask.body()), StandardCharsets.UTF_8);
            out.add(OpsConsoleSelftest.captureLines(() -> resp[0] = OpsConsoleSelftest.post(rig, "/api/kill", key)));
            OpsConsole.ownerWatch = gone;
            out.add(OpsConsoleSelftest.captureLines(() -> resp[1] = OpsConsoleSelftest.post(rig, "/api/kill", key)));
            callsOnWatch = killFnCalls.get();
            killedOnWatch = killed.size();
            OpsConsole.ownerWatch = signalled;
            start = ownerStart(rig, r);
            askPublic = OpsConsoleSelftest.post(rig, "/api/kill", "");
            String publicKey = "confirmed=" + URLEncoder.encode(confirmKey(askPublic.body()), StandardCharsets.UTF_8);
            out.add(OpsConsoleSelftest.captureLines(
                    () -> resp[2] = OpsConsoleSelftest.post(rig, "/api/kill", publicKey)));
        } finally {
            OpsConsole.ownerWatch = prior;
            rig.killMode.set(priorKill);
            setup(r, () -> Files.deleteIfExists(watchOps));
        }
        String forceAsk = "Force-kill server pid " + FORCE_PID + "? (fallback - prefer End match)";
        List<String> bad = new ArrayList<>();
        if (!armed.body().startsWith(ARMED_HEAD)) {
            bad.add("press -> " + PeerText.bound(armed.body(), 120));
        }
        if (!ask.body().startsWith("{\"needsConfirm\":[") || !ask.body().contains("\"message\":\"" + forceAsk + "\"")) {
            bad.add("the watch-lane Kill asks " + PeerText.bound(ask.body(), 160));
        }
        String wantSignalled = "{\"ok\":true,\"outcome\":\"force_signalled\",\"detail\":\"kill signalled to pid "
                + FORCE_PID + "\"}";
        if (!wantSignalled.equals(resp[0].body()) || !out.get(0).contains("cc-console kill result=force_signalled")) {
            bad.add("signalled -> " + PeerText.bound(resp[0].body(), 120));
        }
        String wantGone = "{\"ok\":true,\"outcome\":\"force_gone\",\"detail\":\"pid " + FORCE_PID
                + " not found (already gone?)\"}";
        if (!wantGone.equals(resp[1].body()) || !out.get(1).contains("cc-console kill result=force_gone")) {
            bad.add("gone -> " + PeerText.bound(resp[1].body(), 120));
        }
        if (killedOnWatch != 2 || killed.stream().anyMatch(p -> p != FORCE_PID) || callsOnWatch != 0) {
            bad.add("watch lane: pid kills " + killed + " stop-file kills x" + callsOnWatch);
        }
        if (!start.body().startsWith("{\"ok\":true")) {
            bad.add("the owner Start -> " + PeerText.bound(start.body(), 160));
        }
        if (askPublic.body().contains("Force-kill") || !resp[2].body().startsWith("{\"ok\":true,\"outcome\":\"")
                || killFnCalls.get() != 1 || killed.size() != 2
                || out.get(2).stream().noneMatch(l -> l.startsWith("cc-console kill outcome="))
                || out.get(2).stream().anyMatch(l -> l.contains("result=force_"))) {
            bad.add("public lane: " + PeerText.bound(resp[2].body(), 120) + " stop-file kills x" + killFnCalls.get());
        }
        return bad.isEmpty() ? null : bad + " (flips if Kill on the watch lane writes the stop file, skips or changes"
                + " the pid, loses the force question or its tokens, or the public lane stops reaching the stop-file kill)";
    }

    /** The owner Applies' fixture - {@code dir} holds a minimal deployed
     *  kit and ONE fortress descriptor with its sidecar; {@code props} = the rig's config. */
    private static final class ApplyFx {
        final Path dir;
        final Path kit;
        final Path descriptor;
        final Path sidecar;
        final Path props;
        final String[] priorProperties = new String[APPLY_PROPERTIES.size()];
        boolean propertiesSet;
        boolean propsTaken;
        byte[] priorProps;
        List<Path> live = List.of();
        List<byte[]> liveBytes = List.of();
        /** true = the unstage found a LIVE file changed and put its snapshot back (the row fails). */
        boolean liveRestored;

        ApplyFx(Path dir, Path props) {
            this.dir = dir;
            this.kit = dir.resolve("kit");
            this.descriptor = dir.resolve("fortress.txt");
            this.sidecar = Paths.get(descriptor + ".fingerprint");
            this.props = props;
        }

        /** The descriptor, its sidecar and the config (when staged), as bytes (null = absent). */
        List<byte[]> bytes() throws IOException {
            return snapshot(props == null ? List.of(descriptor, sidecar) : List.of(descriptor, sidecar, props));
        }
    }

    /**
     * Stages {@code fx} (the match-apply selftest's minimal kit and its
     * fortress descriptor at half 7000, family lowtech, backdrop hyper), the config {@code map.half=9000} (its prior
     * bytes kept), then points the three properties at it. FIRST it snapshots the LIVE files an Apply would write with
     * the properties at their prior values ({@link #liveFiles}), so the row can prove the run left them untouched.
     */
    private static void stageApply(ApplyFx fx, String row) {
        setup(row, () -> {
            // A config outside the rig would be the OWNER's
            // live file: refuse to stage rather than write it
            if (fx.props != null && !fx.props.toAbsolutePath().normalize().startsWith(fx.dir.getParent())) {
                throw new IllegalStateException("the Applies config is outside the rig");
            }
            fx.live = liveFiles();
            fx.liveBytes = snapshot(fx.live);
            Files.createDirectories(fx.kit.resolve("jars"));
            Files.createDirectories(fx.kit.resolve("data").resolve("hulls"));
            Files.writeString(fx.kit.resolve("mod_info.json"), "{\"id\":\"cc_client\",\"name\":\"fixture\"}\n");
            Files.write(fx.kit.resolve("jars").resolve("cc-client-mod.jar"), new byte[] {1, 2, 3, 4, 5});
            Files.writeString(fx.kit.resolve("data").resolve("hulls").resolve("fixture.csv"), "id,x\nfix,1\n");
            String text = MatchDescriptorCodec.encode(MatchDescriptors.redact(MatchDescriptors.withTerrain(
                    MatchDescriptors.mint(MatchDescriptors.FORTRESS_SHAPE, 7000f, 0.6f,
                            MatchDescriptors.stationVariantForFamily("lowtech")), "hyper")));
            Files.writeString(fx.descriptor, text, StandardCharsets.UTF_8);
            Files.writeString(fx.kit.resolve("match.txt"), text, StandardCharsets.UTF_8);
            Files.write(fx.sidecar, ContentFingerprintMint.mintSidecarLines(fx.kit), StandardCharsets.US_ASCII);
            if (fx.props != null) { // The package row stages no config
                fx.priorProps = OpsConsoleSelftest.takeFile(fx.props);
                fx.propsTaken = true;
                Files.writeString(fx.props, "# fixture\nmap.half=9000\n", StandardCharsets.UTF_8);
            }
            for (int i = 0; i < APPLY_PROPERTIES.size(); i++) {
                fx.priorProperties[i] = System.getProperty(APPLY_PROPERTIES.get(i));
            }
            fx.propertiesSet = true;
            System.setProperty(OpsMatchApply.MAP_SIZE_DESCRIPTORS_PROPERTY, fx.descriptor.toString());
            System.setProperty(OpsMatchApply.STATION_DESCRIPTORS_PROPERTY, fx.descriptor.toString());
            System.setProperty(OpsMatchApply.MAP_SIZE_DEPLOYED_ROOT_PROPERTY, fx.kit.toString());
        });
    }

    /** {@link #stageApply}'s inverse - the properties back to their prior
     *  values (absent = cleared), the config's prior bytes, the fixture folder removed. */
    private static void unstageApply(ApplyFx fx, String row) {
        if (fx.propertiesSet) {
            for (int i = 0; i < APPLY_PROPERTIES.size(); i++) {
                if (fx.priorProperties[i] == null) {
                    System.clearProperty(APPLY_PROPERTIES.get(i));
                } else {
                    System.setProperty(APPLY_PROPERTIES.get(i), fx.priorProperties[i]);
                }
            }
        }
        setup(row, () -> {
            // A LIVE file the run changed goes back to its snapshot - and the row FAILS on the
            // flag, never a quiet repair
            for (int i = 0; i < fx.live.size(); i++) {
                Path f = fx.live.get(i);
                if (!Arrays.equals(Files.isRegularFile(f) ? Files.readAllBytes(f) : null, fx.liveBytes.get(i))) {
                    OpsConsoleSelftest.restoreFile(f, fx.liveBytes.get(i));
                    fx.liveRestored = true;
                }
            }
            if (fx.propsTaken) {
                OpsConsoleSelftest.restoreFile(fx.props, fx.priorProps);
            }
            if (Files.exists(fx.dir)) {
                OpsConsoleSelftest.deleteTree(fx.dir);
            }
        });
    }

    /** The files an owner Apply writes when its three properties hold their
     *  current values (unset = the live defaults) - the repository's config, each descriptor of the two sets and its
     *  sidecar, absolute. */
    private static List<Path> liveFiles() {
        List<Path> files = new ArrayList<>();
        files.add(OpsRoots.owner().config());
        List<Path> sets = new ArrayList<>(OpsMatchApply.mapSizeDescriptorFiles());
        sets.addAll(OpsMatchApply.stationDescriptorFiles());
        for (Path d : sets) {
            files.add(d.toAbsolutePath().normalize());
            files.add(Paths.get(d.toAbsolutePath().normalize() + ".fingerprint"));
        }
        return files;
    }

    private static List<byte[]> snapshot(List<Path> files) throws IOException {
        List<byte[]> out = new ArrayList<>();
        for (Path f : files) {
            out.add(Files.isRegularFile(f) ? Files.readAllBytes(f) : null);
        }
        return out;
    }

    private static boolean sameBytes(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** The {@code /api/config} answer's {@code matchSettings} member (its last); null = absent. */
    private static String matchSettings(OpsConsoleSelftest.Resp config) {
        String body = config.body();
        int at = body.indexOf("\"matchSettings\":");
        return at < 0 || !body.endsWith("}") ? null : body.substring(at + "\"matchSettings\":".length(), body.length() - 1);
    }

    private static OpsConsoleSelftest.Resp config(OpsConsoleSelftest.Rig rig) throws Exception {
        return OpsConsoleSelftest.call(rig, "GET", "/api/config", rig.boot.token(), null);
    }

    @FunctionalInterface
    private interface DescriptorRead {
        /** The descriptor's value of the setting an Apply changes, as the form types it. */
        String read(Path descriptor) throws IOException;
    }

    /**
     * One owner map Apply over the {@link ApplyFx} fixture. The prefill
     * reads the fixture; {@code op=<op>&value=<value>} with NO {@code confirmed} field is APPLIED on the one press
     * (no confirm is asked): it answers {@code changed:true} with {@code member} = {@code value}, re-authors the
     * descriptor ({@code decoded}) and re-mints its sidecar, and (size) rewrites the config's {@code map.half} -
     * else leaves the config alone; then ({@code sizeLegs} only) a deploy-CR op is the package-only refusal and a
     * non-number size the core's value refusal; then the same Apply again answers {@code changed:false} - each
     * keeping the bytes the Apply wrote; the next prefill shows the value. The stdout row carries each result
     * token. The live files ({@link #liveFiles}) stay byte-identical and the config written is the rig's own.
     * The first press applies; no leg carries a {@code confirmed} field.
     */
    private static String applyOwnerRow(OpsConsoleSelftest.Rig rig, String r, String op, String value, String member,
            DescriptorRead decoded, boolean sizeLegs) throws Exception {
        ApplyFx fx = new ApplyFx(rig.root.resolve("apply-" + op), rig.roots.config());
        OpsConsoleSelftest.Resp[] resp = new OpsConsoleSelftest.Resp[4];
        List<List<String>> out = new ArrayList<>();
        String prefill;
        String prefillAfter;
        List<byte[]> before;
        List<byte[]> afterRefused;
        List<byte[]> afterAgain;
        List<byte[]> after;
        String decodedAfter;
        List<String> propsAfter;
        try {
            stageApply(fx, r);
            prefill = matchSettings(config(rig));
            before = fx.bytes();
            out.add(OpsConsoleSelftest.captureLines(() -> resp[0] = OpsConsoleSelftest.post(rig, "/api/match-settings",
                    "op=" + op + "&value=" + value)));
            after = fx.bytes();
            decodedAfter = decoded.read(fx.descriptor);
            propsAfter = Files.readAllLines(fx.props, StandardCharsets.UTF_8);
            if (sizeLegs) {
                out.add(OpsConsoleSelftest.captureLines(() -> {
                    resp[1] = OpsConsoleSelftest.post(rig, "/api/match-settings", "op=cr&value=50");
                    resp[2] = OpsConsoleSelftest.post(rig, "/api/match-settings", "op=size&value=abc");
                }));
            } else {
                out.add(List.of());
            }
            afterRefused = fx.bytes();
            out.add(OpsConsoleSelftest.captureLines(() -> resp[3] = OpsConsoleSelftest.post(rig, "/api/match-settings",
                    "op=" + op + "&value=" + value)));
            afterAgain = fx.bytes();
            prefillAfter = matchSettings(config(rig));
        } finally {
            unstageApply(fx, r);
        }
        String row = "cc-console match-settings op=" + op + " result=";
        List<String> bad = new ArrayList<>();
        if (!FIXTURE_PREFILL.equals(prefill)) {
            bad.add("prefill " + PeerText.bound(String.valueOf(prefill), 160));
        }
        if (!resp[0].body().startsWith("{\"ok\":true,\"changed\":true,")
                || !resp[0].body().contains("\"" + member + "\":\"" + value + "\"") || !out.get(0).contains(row + "changed")) {
            bad.add("applied -> " + PeerText.bound(resp[0].body(), 200));
        }
        if (sizeLegs) {
            if (!OpsConsoleForms.errorJson(OpsMatchApply.CONFIRM_TITLE, OpsConsole.MATCH_SETTINGS_NOT_PACKAGE)
                    .equals(resp[1].body()) || !out.get(1).contains("cc-console match-settings op=cr result=refused:not_package")) {
                bad.add("deploy CR -> " + PeerText.bound(resp[1].body(), 120));
            }
            if (!resp[2].body().startsWith("{\"error\":\"") || !resp[2].body().endsWith("\"title\":\""
                    + OpsMatchApply.CONFIRM_TITLE + "\"}") || !out.get(1).contains(row + "refused:value")) {
                bad.add("a non-number size -> " + PeerText.bound(resp[2].body(), 120));
            }
        }
        if (!sameBytes(after, afterRefused)) {
            bad.add("a refused Apply wrote");
        }
        if (!resp[3].body().startsWith("{\"ok\":true,\"changed\":false,")
                || !resp[3].body().contains("\"" + member + "\":\"" + value + "\"") || !out.get(2).contains(row + "unchanged")
                || !sameBytes(after, afterAgain)) {
            bad.add("the same Apply again -> " + PeerText.bound(resp[3].body(), 200) + " same bytes="
                    + sameBytes(after, afterAgain));
        }
        if (!value.equals(decodedAfter) || Arrays.equals(before.get(1), after.get(1))) {
            bad.add("the descriptor reads '" + decodedAfter + "', sidecar re-minted=" + !Arrays.equals(before.get(1),
                    after.get(1)));
        }
        boolean propsOk = sizeLegs ? value.equals(OpsMatchApply.mapHalfFieldPrefill(propsAfter))
                : Arrays.equals(before.get(2), after.get(2));
        if (!propsOk) {
            bad.add("the config after: " + PeerText.bound(String.join("|", propsAfter), 120));
        }
        if (prefillAfter == null || !prefillAfter.contains("\"" + member + "\":\"" + value + "\"")) {
            bad.add("prefill after " + PeerText.bound(String.valueOf(prefillAfter), 160));
        }
        if (!fx.props.startsWith(rig.root) || fx.liveRestored || !sameBytes(fx.liveBytes, snapshot(fx.live))) {
            bad.add("a live file changed or the config is outside the rig: " + fx.live.size() + " live file(s)");
        }
        return bad.isEmpty() ? null : bad + " (flips if the owner Apply branch asks a confirm, the file set it writes or"
                + " the prefill drifts, a package-only op, a bad value or an unchanged value writes, or an Apply touches a"
                + " live file)";
    }

    /**
     * {@code web-apply-prefill-owner}: the owner console's
     * {@code /api/config} {@code matchSettings} prefill reads the staged fixture (the config's half, the descriptor's
     * family and backdrop = {@link #FIXTURE_PREFILL}) while the package console's prefill, read beside it, is unmoved
     * by the staging and differs from the owner's.
     */
    private static String applyPrefillRow(OpsConsoleSelftest.Rig owner, OpsConsoleSelftest.Rig pkg) throws Exception {
        String r = "web-apply-prefill-owner";
        ApplyFx fx = new ApplyFx(owner.root.resolve("apply-prefill"), owner.roots.config());
        String pkgBefore = matchSettings(config(pkg));
        String ownerPrefill;
        String pkgAfter;
        try {
            stageApply(fx, r);
            ownerPrefill = matchSettings(config(owner));
            pkgAfter = matchSettings(config(pkg));
        } finally {
            unstageApply(fx, r);
        }
        List<String> bad = new ArrayList<>();
        if (!FIXTURE_PREFILL.equals(ownerPrefill)) {
            bad.add("owner prefill " + PeerText.bound(String.valueOf(ownerPrefill), 160));
        }
        if (pkgBefore == null || !pkgBefore.equals(pkgAfter)) {
            bad.add("package prefill " + PeerText.bound(String.valueOf(pkgBefore), 120) + " -> "
                    + PeerText.bound(String.valueOf(pkgAfter), 120));
        }
        if (pkgAfter != null && pkgAfter.equals(ownerPrefill)) {
            bad.add("the package prefill equals the owner's");
        }
        return bad.isEmpty() ? null : bad + " (flips if the owner prefill stops reading the config half + the"
                + " descriptor's family and backdrop, or the package prefill follows the staged fixture)";
    }

    /**
     * {@code web-apply-pkg-unchanged}: with the owner Applies'
     * fixture staged (its three properties pointed at a sentinel descriptor and kit, no config), a PACKAGE console's
     * {@code op=size} naming an unoffered descriptor still answers the package refusal R6 - the owner branch
     * never runs: the sentinel descriptor, its sidecar and the package config stay byte-identical.
     */
    private static String applyPkgRow(OpsConsoleSelftest.Rig pkg) throws Exception {
        String r = "web-apply-pkg-unchanged";
        ApplyFx fx = new ApplyFx(pkg.root.resolve("apply-pkg"), null);
        List<Path> watched = List.of(fx.descriptor, fx.sidecar, pkg.roots.config());
        OpsConsoleSelftest.Resp[] resp = new OpsConsoleSelftest.Resp[1];
        List<byte[]> before;
        List<byte[]> after;
        List<String> out;
        try {
            stageApply(fx, r);
            before = snapshot(watched);
            out = OpsConsoleSelftest.captureLines(() -> resp[0] = OpsConsoleSelftest.post(pkg, "/api/match-settings",
                    "op=size&value=8000&descriptor=nope.txt"));
            after = snapshot(watched);
        } finally {
            unstageApply(fx, r);
        }
        List<String> bad = new ArrayList<>();
        if (!OpsConsoleForms.errorJson(OpsMatchApply.CONFIRM_TITLE, OpsMatchApply.R6_NOT_OFFERED).equals(resp[0].body())) {
            bad.add("press -> " + PeerText.bound(resp[0].body(), 160));
        }
        if (before.get(0) == null || before.get(2) == null || !sameBytes(before, after)) {
            bad.add("the sentinel descriptor, its sidecar or the package config changed (or never staged)");
        }
        if (out.stream().anyMatch(l -> l.contains("result=changed"))) {
            bad.add("stdout " + out);
        }
        return bad.isEmpty() ? null : bad + " (flips if a package console takes the owner Apply branch, answers anything"
                + " but R6 for an unoffered descriptor, or writes the sentinel or its config)";
    }

    /**
     * {@code page-watch-owner}: the repository console's Arm WATCH
     * chrome over the served js - the package shape intact ({@link OpsConsolePageSelftest#pageWatch}); in
     * {@code onConfig} the {@code repoConsole} flag set from {@code packaged === false} and followed by the show, label
     * and {@code tip-watch} lines; the tip constant's first line; in {@code startVia} the {@code /api/watch &&
     * repoConsole} branch BEFORE the form read, posting {@code {}} with its note and reading no form field; and no
     * Force-kill text in the script (the server mints the WATCH lane's Kill question).
     */
    private static String ownerWatchPageRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        String pkgShape = OpsConsolePageSelftest.pageWatch(html, js);
        if (pkgShape != null) {
            bad.add("page-watch: " + PeerText.bound(pkgShape, 160));
        }
        int onConfigAt = js.indexOf("onConfig: (c) => {");
        int flagAt = js.indexOf("repoConsole = c.packaged === false;");
        String shown = flagAt < 0 ? "" : js.substring(flagAt, Math.min(js.length(), flagAt + 400));
        int showAt = shown.indexOf("if (repoConsole) {");
        int visibleAt = shown.indexOf("CC.show(CC.el('btn-watch'), true);");
        int labelAt = shown.indexOf("CC.text(CC.el('btn-watch'), 'Arm WATCH (observer)');");
        int tipAt = shown.indexOf("CC.text(CC.el('tip-watch'), REPO_WATCH_TIP);");
        if (flagAt < 0 || onConfigAt < 0 || onConfigAt > flagAt || showAt < 0 || visibleAt < showAt
                || labelAt < visibleAt || tipAt < labelAt) {
            bad.add("onConfig: the repoConsole flag and its show / label / tip lines are not there in order");
        }
        if (!js.contains("const REPO_WATCH_TIP = 'Arms the standing test match from this repository: a VPN lease and the kit'")) {
            bad.add("the REPO_WATCH_TIP first line");
        }
        int viaAt = js.indexOf("const startVia = (path, btnId) => {");
        int branchAt = viaAt < 0 ? -1 : js.indexOf("if (path === '/api/watch' && repoConsole) {", viaAt);
        int formAt = viaAt < 0 ? -1 : js.indexOf("const fields = ", viaAt);
        String branch = branchAt < 0 || formAt < branchAt ? "" : js.substring(branchAt, formAt);
        if (branch.isEmpty() || !branch.contains("CC.send(path, {}, {busy: btn, noteText: 'asked for the observer WATCH'})")
                || branch.contains("CC.el('f-") || branch.contains("startForm")) {
            bad.add("startVia: the repository branch is missing, sits after the form read, or reads the form");
        }
        if (js.contains("Force-kill")) {
            bad.add("the script carries Force-kill text");
        }
        return bad.isEmpty() ? null : bad + " (flips if the repository console's WATCH chrome leaves onConfig, the tip"
                + " text or the startVia branch drifts, the branch reads the form, or the page mints the force question)";
    }

    /**
     * {@code web-watch-pkg-unchanged} - a PACKAGE console's
     * {@code /api/watch} with {@code os.name=Linux} still answers the join refusal R9 before any Start (the
     * {@code web-watch-linux} pin): the Arm WATCH seam (a counting recorder) is never reached, no package spawn, no
     * watch row; its status never names the seam's file and carries no {@code watchLane}. {@code os.name} and the seam
     * are restored in {@code finally}.
     */
    private static String watchPkgRow(OpsConsoleSelftest.Rig pkg) throws Exception {
        String r = "web-watch-pkg-unchanged";
        WatchSpawn spawn = new WatchSpawn(false, null);
        OpsConsole.OwnerWatch seams = seam(pkg, "watch-pkg", spawn, recordingKill(new ArrayList<>(), true));
        setup(r, OpsConsoleOwnerSelftest::awaitStaging);
        int spawns0 = pkg.spawned.size();
        OpsConsoleSelftest.Resp[] resp = new OpsConsoleSelftest.Resp[1];
        List<String> out;
        OpsConsoleSelftest.Resp st;
        int spawns1;
        String priorOs = System.getProperty("os.name");
        OpsConsole.OwnerWatch prior = OpsConsole.ownerWatch;
        try {
            OpsConsole.ownerWatch = seams;
            System.setProperty("os.name", "Linux");
            out = OpsConsoleSelftest.captureLines(() -> resp[0] = watchPress(pkg, startFields(START_DESCRIPTOR)));
            awaitStaging();
            spawns1 = pkg.spawned.size();
            st = status(pkg);
        } finally {
            System.setProperty("os.name", priorOs);
            OpsConsole.ownerWatch = prior;
        }
        List<String> bad = new ArrayList<>();
        if (!OpsConsoleForms.errorJson(OpsJoinSelf.TITLE, OpsJoinSelf.R9).equals(resp[0].body())) {
            bad.add("press -> " + PeerText.bound(resp[0].body(), 120));
        }
        if (!spawn.argv.isEmpty() || spawns1 != spawns0 || out.stream().anyMatch(l -> l.startsWith("cc-console watch result="))) {
            bad.add("seam spawns x" + spawn.argv.size() + " package spawns +" + (spawns1 - spawns0));
        }
        String watchText = ServerOpsCore.jsonEscape(seams.watchOps().toAbsolutePath().normalize().toString());
        if (st.body().contains(watchText) || st.body().contains("\"watchLane\"")) {
            bad.add("status " + PeerText.bound(st.body(), 160));
        }
        return bad.isEmpty() ? null : bad + " (flips if the owner Arm WATCH branch fires in package mode, or a package"
                + " status grows the watch lane)";
    }

    private static String detail(String text) {
        return "\"detail\":\"" + ServerOpsCore.jsonEscape(text) + "\"";
    }

    private static String rootsJson(List<String> roots) {
        StringBuilder sb = new StringBuilder("\"roots\":[");
        for (int i = 0; i < roots.size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(OpsConsoleForms.jsonEscape(roots.get(i))).append('"');
        }
        return sb.append(']').toString();
    }
}
