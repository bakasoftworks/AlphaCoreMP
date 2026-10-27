package cc.starsector.server.ops;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.ops.OpsWindowModel.Render;
import cc.starsector.server.ops.OpsWindowModel.StartFields;
import cc.starsector.server.ops.ServerOpsCore.ServerState;
import cc.starsector.server.ops.ServerOpsCore.Ui;
import cc.starsector.server.runtime.VoiceConfig;

/**
 * The headless PRESS rows of the server-ops build gate ({@link OpsSelftests} chains {@link #run}) - the
 * press-surface rule: a press surface is graded
 * unattended through the {@code SpawnFn} seam; build GREEN alone never grades a press.
 * {@link #rehearsal()} drives {@link OpsWindowModel} end-to-end the way an operator's finger does - Start
 * press, fake spawn, a fake WRITER dropping lobby / run / final snapshots on the followed file, the renders;
 * then the W10 press with a brief showing, a launcher that dies, a launch that times out, the note expiry,
 * the brief file's show/hide and a previous server's leftover file. {@link #launchFollow()} pins the
 * launcher's verdicts; the parity-list, package brick and voice helper rows are pure. The fakes
 * ({@link FakeProcess}, {@link RecordingUi}, {@link #writeSnap}) serve the core's and the console's
 * selftests too. Every fixture is a temp dir.
 *
 */
public final class OpsPressSelftest {

    private OpsPressSelftest() {
    }

    /**
     * The failed rows of the running {@link #run}: {@link #check} prints and counts instead of throwing, so one
     * failed row never hides the rest of its own chain; {@link #run} resets it first and throws once at its end, so
     * the gate's per-chain guard still counts the chain. Main-thread only.
     */
    private static int failures;

    /** The press chain: every row below runs, then one throw if any failed. */
    static void run() throws Exception {
        failures = 0;
        rehearsal();
        launchFollow();
        parityListRows();
        pkgBrickLeaseFreeRows();
        voice();
        if (failures > 0) {
            throw new AssertionError("server-ops press selftest FAILED: " + failures
                    + " row(s) - see the FAIL lines above");
        }
    }

    /** A spawned parent the rehearsal controls: alive until {@link #exit} is set. */
    static final class FakeProcess extends Process {
        volatile Integer exit;
        /** The pid the Start chain stamps into a snapshot's {@code owner.pid} (positive, fake). */
        volatile long pid = 990_000_123L;
        /** The process tree the stage reaper
         * kills first ({@code null} = no handle tree: {@link Process#descendants} throws). */
        volatile List<ProcessHandle> tree;

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public java.util.stream.Stream<ProcessHandle> descendants() {
            return tree == null ? super.descendants() : tree.stream();
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exit == null ? 0 : exit;
        }

        @Override
        public int exitValue() {
            if (exit == null) {
                throw new IllegalThreadStateException("still running");
            }
            return exit;
        }

        @Override
        public void destroy() {
            exit = 137;
        }
    }

    /** Records confirms/fails/infos like the core's ScriptedUi; confirms everything. */
    static final class RecordingUi implements Ui {
        final List<String> failures = new ArrayList<>();
        final List<String> infos = new ArrayList<>();

        @Override
        public boolean confirm(String key, String title, String message) {
            return true;
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

    /** The fake writer: one {@code ccops.v=1} snapshot in the REAL grammar
     * ({@link OpsStatusFile#composeLines}), parsed back by the REAL {@link OpsSnapshot}. */
    static void writeSnap(Path file, long stampMs, long pid, String phase, int round, int frame,
            List<OpsStatusFile.SeatRow> seats, String commandFile) throws Exception {
        long[][] bps = new long[seats.size()][2];
        List<String> lines = OpsStatusFile.composeLines(stampMs, pid, phase, "127.0.0.1", 51337,
                7, round, frame, frame, 4, 60.0, 12.5, 3.2, 1000, 500, 100, 50, 0, 0.0, 0,
                commandFile, seats, bps, PhaseWindowStats.Snapshot.EMPTY);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    static long deadPid() {
        for (long pid = 999_999_991L; pid < 1_000_000_100L; pid++) {
            if (ProcessHandle.of(pid).isEmpty()) {
                return pid;
            }
        }
        throw new AssertionError("no free pid found for the leftover row");
    }

    /** {@code lock()} retried past the chain's own {@link ServerOpsLaunch#lockFileHeld} probe. WHY: the
     * rehearsal plays the launcher's lock hold from THIS JVM, and the chain's probe ({@code tryLock},
     * won, released at once; polled every 100 ms) overlaps a blocking {@code lock()} for a moment -
     * the JDK then answers {@link java.nio.channels.OverlappingFileLockException} instead of waiting
     * (a real launcher is another process; only the rehearsal can collide).
     * Bounded ~2 s. */
    static java.nio.channels.FileLock lockPastProbe(java.nio.channels.FileChannel ch) throws java.io.IOException {
        for (int i = 0; ; i++) {
            try {
                return ch.lock();
            } catch (java.nio.channels.OverlappingFileLockException probeWindow) {
                if (i >= 200) {
                    throw probeWindow;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw probeWindow;
                }
            }
        }
    }

    /** Wait (bounded) for the real Start-chain thread
     * of the last press to end; a chain that never clears {@link ServerOpsLaunch#STAGING} FAILS
     * {@code row} (the stage FakeProcess has exited by then: only a lost {@code finally} keeps it). */
    static void awaitChain(String row) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (ServerOpsLaunch.STAGING.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        check(!ServerOpsLaunch.STAGING.get(), row + ": the Start chain never cleared STAGING within"
                + " 10 s (flips if the chain's finally loses STAGING.set(false))");
    }

    static void rehearsal() throws Exception {
        Path dir = Files.createTempDirectory("cc-ops-rehearsal");
        // The probe + the real chain thread read a TEMP
        // package root (never owner mode's live package); its stage receipt names a
        // fake run snapshot, so a stage that exits 0 makes the chain spawn the launcher.
        Path pkg = dir.resolve("ops-pkg");
        Path snapRoot = dir.resolve("run-snap");
        Files.createDirectories(pkg);
        Files.createDirectories(snapRoot);
        Files.writeString(snapRoot.resolve("build.stamp"), "sha256:0123456789abcdef",
                StandardCharsets.US_ASCII);
        Files.writeString(pkg.resolve(ServerOpsLaunch.STAGE_RECEIPT), "snaproot=" + snapRoot + "\n",
                StandardCharsets.US_ASCII);
        OpsRoots.ROOTS.set(OpsRoots.ownerUnder(pkg)); // the whole record is the seam
        Path publicOps = dir.resolve("public").resolve("ops-status.txt");
        Path watchOps = dir.resolve("watch").resolve("ops-status.txt");
        Path briefFile = dir.resolve("brief").resolve("current.txt");
        Path fwdDir = dir.resolve("forward");
        Path props = dir.resolve("cc-server.properties");
        Files.writeString(props, "", StandardCharsets.UTF_8);
        long me = ProcessHandle.current().pid();
        String cmdFile = dir.resolve("cmd.txt").toString();
        List<List<String>> spawned = new ArrayList<>();
        List<FakeProcess> procs = new ArrayList<>();
        ServerOpsCore.SpawnFn spawn = cmd -> {
            spawned.add(cmd);
            FakeProcess p = new FakeProcess();
            procs.add(p);
            return p;
        };
        StartFields fields = new StartFields("127.0.0.1", "51337", "", "", "", null, null, "",
                null, "", "remnant", "on", "", "", // ...on = endless; "" = mods; "" = optional mods
                null, null, // No terrain control = rows untouched
                null); // No asteroids control = row untouched
        OpsWindowModel m = new OpsWindowModel(publicOps, briefFile);
        // Real clock: writerAlive's pid-reuse guard reads a stamp OLDER than this JVM's start
        // instant as a dead writer, so a fixed past epoch would never go LIVE.
        long t = System.currentTimeMillis();

        // 1. windows-up: nothing running, Start offered, brief hidden.
        Render r = m.tick(t);
        check(r.state() == ServerState.STOPPED && !r.starting() && r.startEnabled()
                && !r.commandable() && !r.killable() && r.snap() == null
                && r.detail().startsWith("no ops file at ") && r.briefBody() == null
                && r.seatRows().isEmpty() && r.title().equals(OpsWindowModel.TITLE),
                "windows-up render: STOPPED, Start offered, no brief");

        // 2. a brief file appears / vanishes (file-driven, the arm-watch anti-haunt contract).
        Files.createDirectories(briefFile.getParent());
        Files.write(briefFile, List.of("# armed 20260826-1", "row A", "row B"),
                StandardCharsets.UTF_8);
        r = m.tick(t += 1000);
        check("row A\nrow B".equals(r.briefBody()) && r.briefTitle().contains("armed 20260826-1"),
                "brief file shows with its stamp");
        Files.delete(briefFile);
        r = m.tick(t += 1000);
        check(r.briefBody() == null && m.brief() == null, "brief file gone = panel hidden");
        Files.write(briefFile, List.of("# armed 20260826-2", "row A"), StandardCharsets.UTF_8);
        r = m.tick(t += 1000);
        check("row A".equals(r.briefBody()), "brief re-armed shows again");

        // 3. the Start press: spawn recorded, window follows the public lane, STARTING until
        //    a LIVE snapshot stamped after the press — a leftover final on the path never
        //    reads as the new lane.
        writeSnap(publicOps, t - 60_000, deadPid(), "final", 2, 5000, List.of(), cmdFile);
        RecordingUi ui = new RecordingUi();
        long pressed = t += 1000;
        check(m.pressStart(ui, spawn, props, fields, publicOps, pressed) && spawned.size() == 1
                && ui.failures.isEmpty() && ui.infos.size() == 1
                && spawned.get(0).equals(ServerOpsLaunch.stageCommand(pkg,
                        props.toAbsolutePath().normalize(), "127.0.0.1", 51337, me)),
                "Start press spawns the STAGE command once (the launcher waits on its exit)");
        r = m.tick(t += 1000);
        // The running stage is killable while PENDING (re-pinned)
        check(r.starting() && "STARTING".equals(r.headline()) && !r.startEnabled()
                && !r.commandable() && r.killable() && r.seatRows().isEmpty()
                && r.detail().startsWith(ServerOpsCore.PUBLIC_LANE + " launched 1 s ago")
                && r.detail().contains("  |  start requested (bind 127.0.0.1:51337")
                && r.title().endsWith(ServerOpsCore.PUBLIC_LANE) && r.launchFailure() == null
                && "row A".equals(r.briefBody()),
                "pressed = STARTING over the old final, note appended, brief kept");
        // The stage exits 0 -> the REAL chain thread
        // spawns the launcher through pressStart's wrapper, which swaps the followed launch's current.
        // The test plays the launcher's lock hold (the chain keeps STAGING until it sees
        // run/serve.lock held; the FakeProcess launcher never locks anything itself).
        Files.createDirectories(pkg.resolve(ServeLauncher.RUN_DIR));
        try (java.nio.channels.FileChannel heldLock = java.nio.channels.FileChannel.open(
                pkg.resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.LOCK_FILE),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                java.nio.channels.FileLock lk = heldLock.lock()) {
            procs.get(0).exit = 0;
            awaitChain("chain-rehearsal");
        }
        check(spawned.size() == 2 && spawned.get(1).equals(ServerOpsLaunch.launchCommand(pkg,
                props.toAbsolutePath().normalize())) && m.pendingLaunch() != null
                && m.pendingLaunch().current().get() == procs.get(1)
                && m.pendingLaunch().exitCodeOrNull() == null
                && Long.toString(procs.get(1).pid).equals(Files.readString(
                        snapRoot.resolve("owner.pid"), StandardCharsets.US_ASCII)),
                "chain-rehearsal: stage exit 0 -> the launch vector through the same spawn, the"
                        + " followed launch's current = the launcher, owner.pid re-stamped (flips if"
                        + " pressStart stops sharing its wrapper's reference as current)");
        // the fake writer: lobby → LISTENING (the start note is done), commandable
        writeSnap(publicOps, t, me, "lobby", 0, 0, List.of(), cmdFile);
        r = m.tick(t += 1000);
        check(!r.starting() && r.state() == ServerState.LISTENING && r.commandable()
                && r.killable() && !r.startEnabled() && r.launchFailure() == null
                && !r.detail().contains("start requested") && m.pendingLaunch() == null,
                "first live lobby snapshot = LISTENING, launch resolved");
        check(m.liveSnapshot() == r.snap(),
                "liveSnapshot = the painted live snapshot (the brick's compare key)");
        // run with two seats → RUN, rows painted
        List<OpsStatusFile.SeatRow> two = List.of(
                new OpsStatusFile.SeatRow(1, "alice", "10.0.0.2:5000", "aurora", true, 10, 5, 0),
                new OpsStatusFile.SeatRow(2, "bob", "10.0.0.3:5000", "grendel", true, 10, 5, 0));
        writeSnap(publicOps, t, me, "run", 0, 600, two, cmdFile);
        r = m.tick(t += 1000);
        check(r.state() == ServerState.RUN && r.seatRows().size() == 2 && !r.matchOver()
                && r.snap() != null && r.commandable() && r.killable(),
                "run snapshot = RUN with its seat rows");
        // a verb echo note rides the detail line, then expires
        m.note("sent 'kick 2'", t);
        r = m.tick(t);
        check(r.detail().endsWith("  |  sent 'kick 2'"), "note appended to the detail line");
        r = m.tick(t + OpsWindowModel.NOTE_TTL_MS + 1);
        check(!r.detail().contains("kick 2"), "note expires after NOTE_TTL_MS");
        // The relaunch GAP - the life's writer is
        // dead but the launcher lives: Kill stays offered, Start refused (it would rewrite the
        // file the next life boots on).
        writeSnap(publicOps, t, deadPid(), "run", 0, 700, two, cmdFile);
        r = m.tick(t += 1000);
        check(m.launcherAlive() && r.killable() && !r.startEnabled() && !r.commandable()
                && m.liveSnapshot() == null,
                "launcher-alive-gap: writer dead + launcher alive = killable, Start refused, no"
                        + " brick key (flips if the model drops the launcher's life from enablement)");
        // the supervisor's clean end: the life wrote final, the launcher exits 0
        procs.get(1).exit = 0;
        // final → MATCH ENDED, last run rows kept, Start offered again
        writeSnap(publicOps, t, me, "final", 0, 900, List.of(), cmdFile);
        r = m.tick(t += 1000);
        check(r.state() == ServerState.EXITED && r.matchOver() && "MATCH ENDED".equals(r.headline())
                && r.seatRows().size() == 2 && r.startEnabled() && !r.commandable()
                && !r.killable() && !m.launcherAlive(),
                "final = MATCH ENDED with who played, Start offered (launcher-alive-gap: a dead"
                        + " launcher restores today's values)");
        ServerOpsLaunch.STAGING.set(true);
        try {
            r = m.tick(t);
            check(!r.startEnabled(), "launcher-alive-staging: a Start still staging keeps Start"
                    + " refused (flips if apply stops reading STAGING)");
        } finally {
            ServerOpsLaunch.STAGING.set(false);
        }
        check(m.tick(t).startEnabled(), "launcher-alive-staging: cleared = Start offered again");

        // 4. the W10 press with a brief showing: the FORWARD (D-BRIEFGONE), follow the watch.
        int before = spawned.size();
        check(m.pressWatch(ui, spawn, fwdDir, watchOps, t += 1000) && spawned.size() == before + 1,
                "W10 press spawns");
        List<String> wcmd = spawned.get(spawned.size() - 1);
        // Positions DERIVED from the fixed command's length (the added
        // `-Observer` flag grew it to 6); the path rides AFTER the flag, never at the flag's slot.
        int fixedLen = ServerOpsLaunch.watchCommand().size();
        check(wcmd.subList(0, fixedLen).equals(ServerOpsLaunch.watchCommand())
                && wcmd.size() == fixedLen + 2 && "-OwnerBriefFile".equals(wcmd.get(fixedLen))
                && Files.readString(Path.of(wcmd.get(fixedLen + 1)), StandardCharsets.UTF_8).equals("row A\n"),
                "W10 forwards the SHOWN brief body via -OwnerBriefFile");
        r = m.tick(t += 1000);
        check(r.starting() && r.title().endsWith(ServerOpsCore.WATCH_LANE)
                && m.followed().equals(watchOps) && "row A".equals(r.briefBody()),
                "window follows the watch lane, brief still shown");
        writeSnap(watchOps, t, me, "lobby", 0, 0, List.of(), cmdFile);
        r = m.tick(t += 1000);
        check(r.state() == ServerState.LISTENING && !r.starting(), "watch lane goes LISTENING");
        // the watch parent exits 0 after detaching — no verdict, the lane stays live
        procs.get(procs.size() - 1).exit = 0;
        r = m.tick(t += 1000);
        check(r.state() == ServerState.LISTENING && r.launchFailure() == null,
                "parent exit 0 is no verdict");
        writeSnap(watchOps, t, me, "final", 0, 100, List.of(), cmdFile);
        r = m.tick(t += 1000);
        check(r.state() == ServerState.EXITED, "watch lane ends");

        // 5. a launcher that dies before any live snapshot = ONE failure dialog, then the
        //    window shows what the file says (the old final), Start offered.
        check(m.pressStart(ui, spawn, props, fields, publicOps, t += 1000), "re-press Start");
        procs.get(procs.size() - 1).exit = 1;
        awaitChain("chain-rehearsal"); // The stage's exit 1 ends its chain
        r = m.tick(t += 1000);
        check(!r.starting() && r.launchFailure() != null
                && r.launchFailure().contains("exited with code 1")
                && r.state() == ServerState.EXITED && r.startEnabled(),
                "dead launcher = failure text once, back to the file's state");
        r = m.tick(t += 1000);
        check(r.launchFailure() == null, "the failure is reported once");

        // 5b. A life dying under the supervisor while PENDING = the FAILED
        //     headline + the exception line (launch still pending — a later life may go live);
        //     the parent's exit 1 then LEADS the dialog with that line.
        Path pointer = dir.resolve("ops-serve").resolve("latest.txt");
        Path flight = pointer.resolveSibling("flight-20260830-002633");
        Files.createDirectories(flight.resolve("lives"));
        long pressedWithPointer = t += 1000;
        check(m.pressStart(ui, spawn, props, fields, publicOps, pointer, pressedWithPointer),
                "press Start with a flight pointer");
        Files.writeString(pointer, "flight-20260830-002633\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(pointer,
                java.nio.file.attribute.FileTime.fromMillis(pressedWithPointer + 3000));
        Files.writeString(flight.resolve(LaneFollow.RECEIPT_FILE),
                "life=1 pid=14788\nlife=1 exit=1\nlife=1 archived=2\nlife=2 pid=21304\n",
                StandardCharsets.UTF_8);
        Files.writeString(flight.resolve("lives").resolve("life-1.err.log"),
                "[cc-transform] armed: [HOOK-0043]\n"
                        + "java.net.BindException: Cannot assign requested address: bind\n"
                        + "\tat java.base/sun.nio.ch.Net.bind0(Native Method)\n",
                StandardCharsets.UTF_8);
        r = m.tick(t += 4000);
        check("FAILED".equals(r.headline()) && !r.starting() && r.state() == ServerState.GONE
                && r.detail().startsWith("life 1/6 exited code 1 - java.net.BindException:"
                        + " Cannot assign requested address: bind - supervisor relaunching  |  ")
                && !r.startEnabled() && r.launchFailure() == null && m.pendingLaunch() != null,
                "a dead life = FAILED + the exception line, still pending");
        // Review find: the brick's liveSnapshot() is a pure read of the painted tick — it
        // reports no live serve while PENDING and never consumes the launch (the shape
        // ticked, which could eat the one-shot failure dialog and null the launch).
        check(m.liveSnapshot() == null && m.pendingLaunch() != null,
                "liveSnapshot while PENDING = null, and the launch stays pending");
        procs.get(procs.size() - 1).exit = 1;
        awaitChain("chain-rehearsal");
        r = m.tick(t += 1000);
        check(r.launchFailure() != null
                && r.launchFailure().startsWith("life 1/6 exited code 1 - java.net.BindException")
                && r.launchFailure().contains("exited with code 1") && m.pendingLaunch() == null,
                "the dialog leads with the life line, the launcher line follows");

        // 6. no live snapshot within the grace = TIMED_OUT once.
        check(m.pressStart(ui, spawn, props, fields, publicOps, t += 1000), "press again");
        r = m.tick(t + ServerOpsCore.LAUNCH_GRACE_MS + 1);
        check(r.launchFailure() != null && r.launchFailure().contains("within")
                && !r.starting(), "no snapshot past the grace = timed-out text");
        // End that press's still-waiting stage so its chain clears STAGING.
        procs.get(procs.size() - 1).exit = 1;
        awaitChain("chain-rehearsal");

        // 7. a fresh window over a previous server's leftover file: calm STOPPED, Start
        //    offered, no rows, bars "-" (snap null in the render).
        writeSnap(publicOps, t, deadPid(), "lobby", 0, 0, List.of(), cmdFile);
        OpsWindowModel fresh = new OpsWindowModel(publicOps, briefFile);
        r = fresh.tick(t += 1000);
        check(r.state() == ServerState.STOPPED && r.leftover() && r.startEnabled()
                && r.snap() == null && r.seatRows().isEmpty()
                && r.detail().startsWith("leftover snapshot from a previous server"),
                "leftover file = calm STOPPED");

        // 8. a Start refused by validation never follows anything.
        RecordingUi bad = new RecordingUi();
        OpsWindowModel m2 = new OpsWindowModel(publicOps, briefFile);
        int spawnsBefore = spawned.size();
        check(!m2.pressStart(bad, spawn, props, new StartFields("127.0.0.1", "51337", "sixty",
                "", "", null, null, "", null, "", "remnant", "on", "", "", // "" = mods; "" = optional
                null, null, // No terrain control
                null), publicOps, t) // No asteroids control
                && bad.failures.size() == 1
                && spawned.size() == spawnsBefore && m2.pendingLaunch() == null,
                "a refused Start spawns nothing and follows nothing");

        // 9. The PACKAGE twin of
        //    press 3 - the same window press under packaged(pkg2) (no stage tool, no repo) through
        //    the DEFAULT-arg pressStart, so the pointer + log the window follows resolve from the
        //    record. The owner-mode presses above stay unchanged.
        Path pkg2 = dir.resolve("unpacked-pkg");
        Files.createDirectories(pkg2);
        OpsRoots.ROOTS.set(OpsRoots.packaged(pkg2));
        OpsRoots pr = OpsRoots.get();
        // A package Start needs a match password + a descriptor row
        Files.writeString(pr.config(), "match.descriptor=match.txt\n", StandardCharsets.UTF_8);
        StartFields pkgFields = new StartFields("0.0.0.0", "51337", "", "", "fixture-pw", null, null, "",
                null, "", "remnant", "on", "", "", null, null, null);
        OpsWindowModel mp = new OpsWindowModel(pr.opsFile(), briefFile);
        RecordingUi pui = new RecordingUi();
        int pkgSpawn0 = spawned.size();
        check(mp.pressStart(pui, spawn, pr.config(), pkgFields, pr.opsFile(), t += 1000)
                && spawned.size() == pkgSpawn0 + 1 && pui.failures.isEmpty()
                && spawned.get(pkgSpawn0).equals(ServerOpsLaunch.launchCommand(pkg2,
                        pr.config().toAbsolutePath().normalize()))
                && mp.pendingLaunch() != null
                && mp.pendingLaunch().current().get() == procs.get(procs.size() - 1)
                && mp.pendingLaunch().process() == procs.get(procs.size() - 1)
                && pr.pointer().equals(mp.pendingLaunch().flightPointer())
                && pkg2.resolve(ServeLauncher.RUN_DIR).resolve(OpsRoots.START_LOG_FILE).toString()
                        .equals(mp.pendingLaunch().logPath())
                && pui.infos.size() == 1 && !pui.infos.get(0).contains("staging"),
                "rehearsal-pkg: a package-mode press spawns the LAUNCHER first (no stage), the"
                        + " window follows it with the record's run/latest.txt pointer and"
                        + " run/ops-start.out.log (flips if package mode still stages, or the"
                        + " default press stops resolving its pointer / log from the record)");
        Files.createDirectories(pkg2.resolve(ServeLauncher.RUN_DIR));
        try (java.nio.channels.FileChannel heldPkgLock = java.nio.channels.FileChannel.open(
                pkg2.resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.LOCK_FILE),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                java.nio.channels.FileLock lk = lockPastProbe(heldPkgLock)) {
            awaitChain("rehearsal-pkg"); // the test plays the launcher's lock hold
        }
        String pkgConfig = Files.readString(pr.config(), StandardCharsets.UTF_8);
        check(pkgConfig.contains("server.bindHost=0.0.0.0") && pkgConfig.contains("server.bindPort=51337")
                && !Files.exists(pkg2.resolve(ServerOpsLaunch.STAGE_RECEIPT))
                && !Files.exists(pkg2.resolve(ServeLauncher.DEFAULT_TEMPLATE)),
                "rehearsal-pkg: the bind pair landed in the package's config, no stage receipt, no"
                        + " serve.argv (flips if the window's press skips the package bind rows)");
        procs.get(procs.size() - 1).exit = 0;
        OpsRoots.ROOTS.set(OpsRoots.owner());
        System.out.println("ops-window rehearsal PASS");
    }

    /** A flight fixture under {@code run}: {@code latest.txt} → {@code flight}, the receipt
     * rows, the pointer stamped {@code pointerMs} (the pointer law's mtime). */
    static Path writeFlightFixture(Path run, String flight, String receipt, long pointerMs)
            throws Exception {
        Path dir = run.resolve(flight);
        Files.createDirectories(dir.resolve("lives"));
        Path pointer = run.resolve("latest.txt");
        Files.writeString(pointer, flight + "\n", StandardCharsets.US_ASCII);
        Files.setLastModifiedTime(pointer, java.nio.file.attribute.FileTime.fromMillis(pointerMs));
        Files.writeString(dir.resolve(LaneFollow.RECEIPT_FILE), receipt, StandardCharsets.US_ASCII);
        return pointer;
    }

    /**
     * The launcher's verdicts as the window sees
     * them — the receipt's terminal rows ({@link LaneFollow#parseTerminalRow}), the exit-78 text,
     * the 320 cut, the STAGING-guarded exit-0 verdict, the PENDING-Kill fold, and the REFUSED /
     * STOPPED renders. Temp fixtures only (never owner mode's live package); every
     * FakeProcess ends exited.
     */
    static void launchFollow() throws Exception {
        Path dir = Files.createTempDirectory("cc-ops-launch-follow");
        Path briefFile = dir.resolve("brief").resolve("current.txt");
        // (1) terminal-parse: each terminal kind + lives; the reason token; none; malformed
        List<String> lifeRows = List.of("supervisor_pid=36532", "life=1 pid=20300",
                "life=1 timer_optout=-2", "life=1 exit=1");
        Object[][] five = {
                {"stopped=true lives=1", LaneFollow.Terminal.STOPPED, 1, ""},
                {"clean_exit=true lives=2", LaneFollow.Terminal.CLEAN_EXIT, 2, ""},
                {"refused_exit=true lives=1", LaneFollow.Terminal.REFUSED_EXIT, 1, ""},
                {"relaunch_budget_exhausted=true lives=6", LaneFollow.Terminal.BUDGET_EXHAUSTED, 6, ""},
                {"refused_prelaunch=true reason=locked", LaneFollow.Terminal.REFUSED_PRELAUNCH, 0,
                        "locked"}};
        for (Object[] c : five) {
            List<String> receipt = new ArrayList<>(lifeRows);
            receipt.add(c[0] + "\r");
            LaneFollow.TerminalRow t = LaneFollow.parseTerminalRow(receipt);
            check(t != null && t.kind() == c[1] && t.lives() == (Integer) c[2]
                    && t.reason().equals(c[3]) && t.rowText().equals(c[0]),
                    "terminal-parse: '" + c[0] + "' read as " + t + " (flips if parseTerminalRow"
                            + " drops or misreads that terminal row)");
        }
        check(LaneFollow.parseTerminalRow(lifeRows) == null,
                "terminal-parse: a receipt with no terminal row = null (flips if a life= row is read"
                        + " as a verdict)");
        // "life=2 timer_optout=0" AFTER a terminal row = the async applier's late row (ServeSupervisor
        // writes it whenever the helper answers)
        LaneFollow.TerminalRow kept = LaneFollow.parseTerminalRow(List.of("stopped=true lives=1",
                "clean_exit=true lives=2", "life=2 timer_optout=0", "stopped=true lives=x",
                "refused_prelaunch=true reason=Bad Token"));
        check(kept != null && kept.kind() == LaneFollow.Terminal.CLEAN_EXIT && kept.lives() == 2,
                "terminal-parse: the LAST well-formed row wins, a late life=N timer_optout row and a malformed"
                        + " 'stopped=true lives=x' / off-vocabulary reason are skipped, got " + kept
                        + " (flips if a malformed or late row is accepted or the first row wins)");

        // (2) exit78-text: 78 is final, never "relaunching"; other codes keep their texts
        String t78 = new LaneFollow.LifeFailure(1, 78, false, "X").detailText();
        check(t78.equals("life 1/6 exited code 78 - X - the server REFUSED (exit 78, final)")
                && new LaneFollow.LifeFailure(1, 1, false, "").detailText()
                        .endsWith(" - supervisor relaunching"),
                "exit78-text: '" + t78 + "' (flips if exit 78 reads 'supervisor relaunching' again"
                        + " or the 78 branch swallows other codes)");

        // (3) cut-320: a 300-char exception head survives whole; a 400-char one is cut at 320
        String head300 = "java.lang.IllegalStateException: " + "k".repeat(300 - 33);
        Path err = dir.resolve("life-1.err.log");
        Files.writeString(err, "[cc-transform] armed\n" + head300 + "\n\tat x.y(Unknown Source)\n",
                StandardCharsets.US_ASCII);
        String got300 = LaneFollow.firstExceptionLine(err);
        Files.writeString(err, head300 + "q".repeat(100) + "\n", StandardCharsets.US_ASCII);
        String got400 = LaneFollow.firstExceptionLine(err);
        check(head300.length() == 300 && got300.equals(head300) && got400.length() == 320,
                "cut-320: a 300-char line came back " + got300.length() + " chars, a 400-char one "
                        + got400.length() + " (flips if the cut reverts to 240 or is dropped)");

        // (4) phase-exit0-staging: the pure decision, then the model's own STAGING wiring
        LaneFollow.LaneLaunch pub = LaneFollow.LaneLaunch.publicServe(1_000_000L, null);
        LaneFollow.LaneLaunch dbg = LaneFollow.LaneLaunch.debugWatch(1_000_000L, null);
        check(LaneFollow.launchPhase(pub, null, false, 1_000_500L, 0, true) == LaneFollow.LaunchPhase.PENDING
                && LaneFollow.launchPhase(pub, null, false, 1_000_500L, 0, false)
                        == LaneFollow.LaunchPhase.FAILED
                && LaneFollow.launchPhase(dbg, null, false, 1_000_500L, 0, false)
                        == LaneFollow.LaunchPhase.PENDING
                && LaneFollow.launchPhase(pub, null, false, 1_000_500L, null, false)
                        == LaneFollow.LaunchPhase.PENDING,
                "phase-exit0-staging: public exit 0 = PENDING while staging, a verdict once cleared;"
                        + " the watch lane's 0 stays PENDING (flips if the STAGING guard is dropped or"
                        + " the lane test is lost)");
        Path ops4 = dir.resolve("p4").resolve("ops-status.txt");
        Path noPointer = dir.resolve("p4").resolve("latest.txt");
        OpsWindowModel m4 = new OpsWindowModel(ops4, briefFile);
        FakeProcess exited0 = new FakeProcess();
        exited0.exit = 0;
        long now = System.currentTimeMillis();
        m4.follow(new LaneFollow.LaneLaunch(ServerOpsCore.PUBLIC_LANE, ops4,
                dir.resolve("p4.log").toString(), now, exited0, noPointer, null));
        Render r;
        ServerOpsLaunch.STAGING.set(true);
        try {
            r = m4.tick(now + 1000);
        } finally {
            ServerOpsLaunch.STAGING.set(false);
        }
        check(r.starting() && m4.pendingLaunch() != null,
                "phase-exit0-staging: the model keeps a public exit 0 PENDING while STAGING (flips if"
                        + " apply stops passing ServerOpsLaunch.STAGING)");
        r = m4.tick(now + 2000);
        check(!r.starting() && m4.pendingLaunch() == null && r.launchFailure() != null
                && r.launchFailure().contains("exited with code 0"),
                "phase-exit0-staging: cleared = the exit-0 verdict reaches the window (flips if apply"
                        + " passes a constant staging=true)");

        // (5) pending-killable: the PENDING-Kill fold
        Path ops5 = dir.resolve("p5").resolve("ops-status.txt");
        OpsWindowModel m5 = new OpsWindowModel(ops5, briefFile);
        FakeProcess stage5 = new FakeProcess();
        m5.follow(new LaneFollow.LaneLaunch(ServerOpsCore.PUBLIC_LANE, ops5,
                dir.resolve("p5.log").toString(), now, stage5, dir.resolve("p5").resolve("latest.txt"),
                null));
        r = m5.tick(now + 1000);
        check(r.starting() && r.killable() && !r.startEnabled() && m5.launcherProcess() == stage5,
                "pending-killable: a PENDING launch whose current lives = killable + launcherProcess()"
                        + " is it (flips if the PENDING render drops killable or the set)");
        stage5.exit = 1;
        r = m5.tick(now + 2000);
        check(!r.killable() && r.launchFailure() != null,
                "pending-killable: its exit 1 = the FAILED dialog, nothing left to kill");

        // (6) render-refused: refused_prelaunch + the launcher's console sentence = calm REFUSED
        Path run6 = dir.resolve("p6").resolve("run");
        Path ops6 = run6.resolve("ops").resolve("ops-status.txt");
        long pressed6 = System.currentTimeMillis();
        Path pointer6 = writeFlightFixture(run6, "flight-20261007-101500",
                "supervisor_pid=36532\nrefused_prelaunch=true reason=sidecar_stale\n", pressed6 + 1000);
        Path log6 = dir.resolve("p6").resolve("ops-start.out.log");
        Files.writeString(log6, "STAGED x\nops-start: launcher pid 36532\n"
                + "serve-launcher refused: the password sidecar is older than the kit - re-pack\n",
                StandardCharsets.US_ASCII);
        FakeProcess launcher6 = new FakeProcess();
        launcher6.exit = 2;
        OpsWindowModel m6 = new OpsWindowModel(ops6, briefFile);
        m6.follow(new LaneFollow.LaneLaunch(ServerOpsCore.PUBLIC_LANE, ops6, log6.toString(),
                pressed6, launcher6, pointer6, null));
        r = m6.tick(pressed6 + 2000);
        Render r6b = m6.tick(pressed6 + 3000);
        check("REFUSED".equals(r.headline()) && r.state() == ServerState.STOPPED && !r.starting()
                && r.detail().startsWith("REFUSED before launch: sidecar_stale - serve-launcher refused:"
                        + " the password sidecar is older than the kit")
                && r.launchFailure() == null && r.startEnabled() && m6.pendingLaunch() == null
                && "REFUSED".equals(r6b.headline()) && r6b.launchFailure() == null,
                "render-refused: got '" + r.headline() + "' / '" + r.detail() + "' / dialog "
                        + (r.launchFailure() != null) + " (flips if parseTerminalRow misses the row,"
                        + " the refusal becomes a dialog, or the REFUSED render is not kept)");

        // (7) render-stopped-after-live: LIVE, then the writer dies and the receipt says stopped
        long jvmStart = ProcessHandle.current().info().startInstant()
                .map(java.time.Instant::toEpochMilli).orElse(-1L);
        check(jvmStart > 0, "SETUP-render-stopped-after-live: no start instant for this JVM (the"
                + " pid-reuse guard cannot stage a dead writer)");
        long me = ProcessHandle.current().pid();
        Path run7 = dir.resolve("p7").resolve("run");
        Path ops7 = run7.resolve("ops").resolve("ops-status.txt");
        long pressed7 = System.currentTimeMillis();
        Path pointer7 = writeFlightFixture(run7, "flight-20261007-101600",
                "supervisor_pid=36533\nlife=1 pid=" + me + "\n", pressed7 + 1000);
        FakeProcess launcher7 = new FakeProcess();
        OpsWindowModel m7 = new OpsWindowModel(ops7, briefFile);
        m7.follow(new LaneFollow.LaneLaunch(ServerOpsCore.PUBLIC_LANE, ops7,
                dir.resolve("p7.log").toString(), pressed7, launcher7, pointer7, null));
        writeSnap(ops7, pressed7 + 500, me, "run", 0, 600, List.of(), "off");
        r = m7.tick(pressed7 + 1500);
        check(r.state() == ServerState.RUN && m7.pendingLaunch() == null,
                "SETUP-render-stopped-after-live: the fake writer never went LIVE (" + r.state() + ")");
        // the writer dies: same pid, a stamp older than this JVM's start = dead (pid-reuse guard)
        writeSnap(ops7, jvmStart - 5000, me, "run", 0, 700, List.of(), "off");
        r = m7.tick(pressed7 + 2500);
        check(r.state() == ServerState.GONE,
                "SETUP-render-stopped-after-live: a writer seen alive then dead is not GONE ("
                        + r.state() + ")");
        Files.writeString(run7.resolve("flight-20261007-101600").resolve(LaneFollow.RECEIPT_FILE),
                "life=1 exit=1\nstopped=true lives=1\n", StandardCharsets.US_ASCII,
                java.nio.file.StandardOpenOption.APPEND);
        launcher7.exit = 0;
        r = m7.tick(pressed7 + 3500);
        check(r.state() == ServerState.STOPPED && "STOPPED".equals(r.headline()) && !r.starting()
                && r.detail().startsWith("stopped by Kill / stop file (lives 1)") && !r.killable()
                && r.startEnabled() && r.launchFailure() == null && r.snap() == null,
                "render-stopped-after-live: got " + r.state() + " '" + r.headline() + "' / '"
                        + r.detail() + "' (flips if the post-LIVE tick stops reading THIS press's"
                        + " terminal row - the stop reads red GONE 'killed or crashed' again)");

        // (8) render-stopped-then-foreign-gone: after this shell's
        // calm STOPPED, ANOTHER flight (a second shell's Start / a hand-run serve.bat on the same
        // package) rewrites the shared pointer, its writer goes live, then dies - that is THAT
        // serve's red GONE, never our cached "stopped by Kill"; and its crash snapshot shows.
        writeFlightFixture(run7, "flight-20261007-101700",
                "supervisor_pid=36534\nlife=1 pid=" + me + "\n", pressed7 + 4000);
        writeSnap(ops7, pressed7 + 4500, me, "run", 0, 800, List.of(), "off");
        r = m7.tick(pressed7 + 5000);
        check(r.state() == ServerState.RUN,
                "SETUP-render-stopped-then-foreign-gone: the foreign writer never went live ("
                        + r.state() + ")");
        writeSnap(ops7, jvmStart - 5000, me, "run", 0, 900, List.of(), "off");
        r = m7.tick(pressed7 + 6000);
        check(r.state() == ServerState.GONE && !"STOPPED".equals(r.headline())
                && !r.detail().startsWith("stopped by Kill") && r.snap() != null,
                "render-stopped-then-foreign-gone: got " + r.state() + " '" + r.headline() + "' / '"
                        + r.detail() + "' snap=" + (r.snap() != null) + " (flips if the calm overlay"
                        + " outlives this press's flight - a foreign crash painted calm STOPPED with"
                        + " its snapshot hidden)");
        System.out.println("ops-window launch-follow PASS");
    }


    /**
     * Rows {@code parity-list-sane} and
     * {@code pkg.setup.deadControls} - the {@link OpsParity} lists
     * themselves: no id listed twice, no owner-only / dead id listed as package-visible, the
     * owner + dead set equal to the pinned release-cut list, the map controls package-visible.
     * Pure list rows (the page's twin of every listed id is {@code OpsConsole}'s
     * {@code parity-web-twin} row).
     */
    static void parityListRows() {
        String sane = "parity-list-sane";
        Set<String> pkgList = new LinkedHashSet<>(OpsParity.PACKAGE_IDS);
        Set<String> ownerDead = new LinkedHashSet<>(OpsParity.OWNER_IDS);
        ownerDead.addAll(OpsParity.DEAD_PACKAGE_IDS);
        Set<String> clash = new LinkedHashSet<>(ownerDead);
        clash.retainAll(pkgList);
        // The OWNER + DEAD set IS the release-cut list (a package hides exactly these), pinned verbatim
        // - a new id here = a release cut, decided deliberately;
        // it replaces the deleted WEB_TWIN_PENDING hatch. verb.watch
        // LEFT the cut list - a package's Launch debug WATCH is a Start with the debug posture (no script).
        Set<String> cutList = Set.of("verb.devEditor");
        check(pkgList.size() == OpsParity.PACKAGE_IDS.size()
                && ownerDead.size() == OpsParity.OWNER_IDS.size() + OpsParity.DEAD_PACKAGE_IDS.size()
                && clash.isEmpty() && ownerDead.equals(cutList),
                sane + ": PACKAGE_IDS " + OpsParity.PACKAGE_IDS.size() + " entries / "
                        + pkgList.size() + " distinct, OWNER + DEAD " + (OpsParity.OWNER_IDS.size()
                        + OpsParity.DEAD_PACKAGE_IDS.size()) + " / " + ownerDead.size()
                        + " distinct, owner/dead ids listed as package-visible " + clash
                        + ", owner + dead = " + ownerDead + " vs the pinned release-cut list " + cutList
                        + " - flips if an id is listed twice, an owner-only / dead control is"
                        + " listed as package-visible, or a control joins the cut list without the pinned list changing");
        System.out.println("  PASS " + sane + " (" + pkgList.size() + " package ids, "
                + ownerDead.size() + " owner + dead)");
        // Row pkg.setup.deadControls - the map controls LEFT, EMPTYING
        // the dead list: map half / station / backdrop are live package controls (their Applies
        // re-make the picked shape), so all three and their Applies are package-visible ids.
        String dead = "pkg.setup.deadControls";
        List<String> formerDead = List.of("setup.map.half", "setup.map.station", "setup.map.backdrop",
                "setup.map.apply", "setup.map.stationApply", "setup.map.backdropApply");
        List<String> notPkg = new ArrayList<>(formerDead);
        notPkg.removeAll(OpsParity.PACKAGE_IDS);
        check(OpsParity.DEAD_PACKAGE_IDS.isEmpty() && notPkg.isEmpty()
                        && Collections.disjoint(formerDead, OpsParity.OWNER_IDS),
                dead + ": DEAD_PACKAGE_IDS " + OpsParity.DEAD_PACKAGE_IDS + ", former dead / owner map ids"
                        + " not package-visible " + notPkg + " - flips if a map control is listed dead or"
                        + " owner-only in a package again (a package shows the map row)");
        System.out.println("  PASS " + dead);
    }

    /**
     * Row {@code pkg-brick-lease-free} - the package
     * Copy join brick's endpoint ({@link ServerOpsLaunch#packageBrickEndpoint}, no lease) over its
     * four cases, the placeholder probed WITH a live serve on its port (so only the placeholder
     * rule can refuse it), then the brick {@link ServerOpsCore#joinBrick} composes from it.
     */
    static void pkgBrickLeaseFreeRows() {
        String row = "pkg-brick-lease-free";
        OpsSnapshot on51337 = OpsSnapshot.parse(OpsStatusFile.composeLines(1L, 1L, "lobby",
                "0.0.0.0", 51337, 7, 0, 0, 0, 4, 60.0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, "off",
                List.of(), new long[0][], PhaseWindowStats.Snapshot.EMPTY));
        List<String> placeholder = new ArrayList<>();
        for (String typed : new String[] {OpsSetupPrefill.packageAdvertise("51337"), "", "  -  ",
                "1.2.3.4", ":51337", "1.2.3.4:port"}) {
            placeholder.add(refusalOf(() -> ServerOpsLaunch.packageBrickEndpoint(typed, on51337)));
        }
        check(placeholder.stream().allMatch(ServerOpsLaunch.PKG_BRICK_TYPE_FIRST::equals),
                row + ": placeholder / blank / - / no port / no host / a word port gave " + placeholder
                        + " (want the type-first text each, a live serve on 51337) - flips if the"
                        + " placeholder or the host:port check is dropped (a dead brick ships)");
        List<String> shape = new ArrayList<>(); // the address part's shape
        for (String typed : new String[] {"http://1.2.3.4:51337", "a b:51337", "\"1.2.3.4\":51337"}) {
            shape.add(refusalOf(() -> ServerOpsLaunch.packageBrickEndpoint(typed, on51337)));
        }
        check(shape.stream().allMatch(ServerOpsLaunch.PKG_BRICK_HOST_SHAPE::equals),
                row + ": a scheme / a space / a quote in the address part gave " + shape
                        + " (want the host-shape text each) - flips if a brick the joiner's parser cannot"
                        + " read (three colon-split, whitespace-free parts) ships, is saved and re-served");
        String noLive = refusalOf(() -> ServerOpsLaunch.packageBrickEndpoint("1.2.3.4:51337", null));
        check(ServerOpsLaunch.PKG_BRICK_START_FIRST.equals(noLive),
                row + ": no running server gave '" + noLive + "' - flips if a brick ships with no live"
                        + " serve to confirm its port");
        String moved = refusalOf(() -> ServerOpsLaunch.packageBrickEndpoint("1.2.3.4:60000", on51337));
        check(("Advertise names port 60000 but the running server listens on 51337 - fix Advertise or"
                        + " restart the server").equals(moved),
                row + ": a port mismatch gave '" + moved + "' - flips if a brick names a port the"
                        + " running serve does not listen on");
        String endpoint = ServerOpsLaunch.packageBrickEndpoint(" 1.2.3.4:51337 ", on51337);
        String brick = ServerOpsCore.joinBrick(endpoint, "fixture-pw");
        check("1.2.3.4:51337".equals(endpoint) && "1.2.3.4:51337:fixture-pw".equals(brick),
                row + ": endpoint '" + endpoint + "' brick '" + brick + "' (want 1.2.3.4:51337 and"
                        + " 1.2.3.4:51337:fixture-pw) - flips if the typed endpoint is not trimmed or"
                        + " the brick stops composing endpoint:password");
        System.out.println("  PASS " + row);
    }

    /** The refusal message, or {@code "accepted"} when {@code action} returned. */
    private static String refusalOf(java.util.function.Supplier<String> action) {
        try {
            action.get();
            return "accepted";
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    /** W12 L5: the OpsVoicePanel pure helpers — the formula at the
     * defaults, the properties merge (unrelated + non-panel voice rows preserved), the
     * changed-knob → verb diff, and the refusal-message → offending-field mapping. */
    //
    static void voice() {
        // 1. The bandwidth formula at the worked case's values (48 kbps / 20 ms / 6 talkers).
        check(OpsVoicePanel.talkerFrameBytes(48, 20) == 131.0,
                "voice talker frame = 120 B payload + 11 B tag = 131 B");
        double perListener = OpsVoicePanel.perListenerBytesPerSec(48, 20, 6);
        check(perListener == 42_500.0 && Math.round(perListener / 1000.0) == 43L,
                "voice per-listener = 42500 B/s (~42-43 kB/s)");
        double egress = OpsVoicePanel.egressBytesPerSec(48, 20, 6, 32);
        check(egress == 1_317_500.0 && Math.round(egress * 8.0 / 1_000_000.0) == 11L,
                "voice egress at 32 players = 1.32 MB/s (~11 Mbit/s)");
        check(OpsVoicePanel.readout(48, 20, 6).equals(
                "per listener ≈ 42.5 kB/s at 6 talkers; egress at 32 players ≈ 10.5 Mbit/s"),
                "voice readout line reads at the defaults");

        // 2. fromProps reads the panel rows AND carries the non-panel lowDelay knob.
        List<String> props = List.of("sim.tickRate=45", "voice.lowDelay=true",
                "voice.bitrateKbps=64", "voice.maxTalkers=4");
        VoiceConfig current = OpsVoicePanel.fromProps(props);
        check(current.kbps() == 64 && current.maxTalkers() == 4 && current.lowDelay()
                && current.frameMs() == VoiceConfig.DEFAULT_FRAME_MS,
                "fromProps reads present rows + defaults the absent ones + carries lowDelay");

        // 3. the properties merge: nine panel rows written, every unrelated row preserved.
        VoiceConfig desired = current.retune("bitrateKbps", "96");
        List<String> merged = OpsVoicePanel.withVoiceRows(props, desired);
        check(merged.contains("sim.tickRate=45") && merged.contains("voice.lowDelay=true")
                && merged.contains("voice.bitrateKbps=96") && merged.contains("voice.maxTalkers=4")
                && merged.contains(OpsVoicePanel.VOICE_COMMENT)
                && OpsVoicePanel.fromProps(merged).kbps() == 96,
                "voice.* rows written, unrelated + lowDelay rows preserved, re-read stable");
        // idempotent: re-applying the same sheet does not grow the block.
        check(OpsVoicePanel.withVoiceRows(merged, desired).equals(merged),
                "withVoiceRows is idempotent (block stripped + re-added)");

        // 4. changed-knob diff => ONE atomic `voice set` line for what moved:
        // a multi-knob move rides the SAME single line, and it parses as ONE
        // operator command (never one verb per knob — the half-apply defect).
        check("voice set bitrateKbps=96".equals(OpsVoicePanel.applyVerb(current, desired)),
                "one changed knob = one voice set line");
        check(OpsVoicePanel.applyVerb(current, current) == null, "no change = no verb");
        VoiceConfig far = current.retuneAll(java.util.Map.of("proxFullSu", "3000",
                "proxCullSu", "4000"));
        String two = OpsVoicePanel.applyVerb(current, far);
        check("voice set proxFullSu=3000 proxCullSu=4000".equals(two),
                "two changed knobs = ONE voice set line (PANEL_KNOBS order)");
        cc.starsector.server.runtime.OperatorCommandFile.ParseResult pr =
                cc.starsector.server.runtime.OperatorCommandFile.parse(List.of(two));
        check(pr.commands().size() == 1 && pr.rejected().isEmpty()
                && pr.commands().get(0).text().equals("set proxFullSu=3000 proxCullSu=4000"),
                "the Apply line parses as exactly one VOICE command");

        // 5. a refused combination names the offending field(s) the Apply reddens.
        try {
            new VoiceConfig(true, 48, 20, 5, true, false, false, 6, 3000, 2500, 0);
            check(false, "prox crossing should have thrown");
        } catch (IllegalArgumentException bad) {
            check(OpsVoicePanel.offendingKnobs(bad.getMessage())
                    .containsAll(List.of("proxFullSu", "proxCullSu")),
                    "a prox full>=cull crossing reddens both prox fields");
        }
        System.out.println("ops-window voice PASS");
    }

    /** One row: a failure prints {@code FAIL <what>} and counts into {@link #failures}, then the chain goes on. */
    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
