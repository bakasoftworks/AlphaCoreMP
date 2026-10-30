package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import cc.starsector.server.ops.LaneFollow.LaneLaunch;
import cc.starsector.server.ops.LaneFollow.LaunchPhase;
import cc.starsector.server.ops.ServerOpsCore.OwnerBrief;
import cc.starsector.server.ops.ServerOpsCore.ServerState;
import cc.starsector.server.ops.ServerOpsCore.SpawnFn;
import cc.starsector.server.ops.ServerOpsCore.Ui;
import cc.starsector.server.ops.ServerOpsCore.WriterWatch;

/**
 * An ops shell's own state ("state owned by the window's lifecycle,
 * not disk files"): which lane it follows, the launch it is waiting on, the writer it saw alive,
 * the seats of the last run, the operator note, and the brief it is showing. Pure by construction:
 * no UI toolkit, no dialogs, no process spawns of its own (those ride the injected {@link Ui} /
 * {@link SpawnFn}), so the build gate REHEARSES a press end-to-end headless
 * ({@link OpsPressSelftest}: press → fake spawn → fake writer → render), which build GREEN alone
 * never did (an earlier ladder of eight defects surfaced under the operator's finger).
 *
 * <p>{@link StartFields} is the Start chain's params object every Start builds (the web console's
 * Start form, {@link ServerOpsLaunch}); the instance model is driven by that press rehearsal, one
 * thread per instance. {@link #tick}
 * polls the followed status file + the brief file and returns a {@link Render} — the complete
 * what-to-paint answer, so a shell is a painter and every decision is here or in
 * {@link ServerOpsCore}.
 *
 * <p>Brief lifetime stays the FILE's (the arm-watch anti-haunt at its brief-less arm must keep
 * hiding a crashed watch's brief from a later window); what the window OWNS is the forward:
 * {@link #pressWatch} hands the brief it is showing to the watch chain
 * ({@link ServerOpsCore#runWatchFlow(Ui, SpawnFn, OwnerBrief, Path)}), so the press re-arms it
 * instead of deleting it (D-BRIEFGONE).
 *
 */
public final class OpsWindowModel {

    /** How long an operator note (verb echo, lease result, brick copy) rides the detail line.
     * The old shell overwrote such notes on the very next poll tick (they showed for under a
     * second); a note without expiry would instead haunt the line forever. */
    public static final long NOTE_TTL_MS = 45_000L;

    /** The window's title stem; a followed lane is appended. */
    public static final String TITLE = "AlphaCoreMP - Server Ops";

    /** The set-before-start field texts a Start press carries (the shell's form values). */
    public record StartFields(String bindHost, String bindPort, String tickText,
            String fortressDpText, String passwordText, String selDescriptor,
            ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
            String fortressWaveScaleText, String adminPasswordText,
            String waveCategoryText, // the wave-category field text
            String endlessText, // (on|off, null = none)
            String modsEnabledText, // (comma ids beyond cc_client)
            String modsOptionalText, // (client-optional ids; null = no control)
            // The terrain word + strength (WireKnob
            // TERRAIN / TERRAIN_LEVEL words; null word = no control = the rows stay untouched).
            String terrainText, String terrainLevelText,
            // The asteroid-field word (WireKnob ASTEROIDS;
            // null = no control = the match.asteroids row stays untouched).
            String asteroidsText,
            // The live-roster wave-DP field text
            // (match.waveRosterFullDp; 0/blank = static waves; null = no field = row untouched).
            String waveRosterFullDpText,
            // The live-roster wave multiplier field text
            // (match.waveRosterMultiplier; 1/blank = no change; null = no field = row untouched).
            String waveRosterMultiplierText,
            // The Seats-per-address field text
            // (moderation.maxSeatsPerSource; 0..255, blank refused; null = no field = row untouched)
            // and the All-skills word (server.officerSkillsAll; on|off; null = no control = row untouched).
            String seatsPerSourceText, String allSkillsText,
            // The replay-pins word (rngpin.enabled; on|off;
            // null = no control = row untouched).
            String rngPinsText,
            // The debug-watch posture a "Launch debug WATCH" press carries:
            // the upsert writes OpsMatchRows.CLIENT_DEBUG_KEY = this word ALWAYS (true = a test
            // match: hull bars + debug keys for every player; false = Start's normal play). A
            // boolean, never null: a plain Start must write false so no later serve life inherits
            // a debug watch's true (the Kill, write, spawn order of a Start).
            boolean debugWatch,
            // The page's PvP mode word (on|off; null = no control =
            // the picked file's mode stands) and its Station pick (a family, or blank / "-" = the fortress's
            // stock station), read only when Start switches a PvP file back to the fortress.
            String pvpText, String pvpStationText,
            // The MagicLib word (magicseam.enabled; on|off;
            // null = no control = row untouched) - the rngPinsText twin, a boot-time row.
            String magicSeamText,
            // The player-ship word (pilotship.enabled; on|off;
            // null = no control = row untouched) - the magicSeamText twin, a boot-time row.
            String pilotShipText) {
        /** The pre-player-ship 26-component shape (every caller before the
         * player-ship select) - no player-ship control = {@code null} = the {@code pilotship.enabled} row stays
         * untouched. */
        public StartFields(String bindHost, String bindPort, String tickText,
                String fortressDpText, String passwordText, String selDescriptor,
                ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
                String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
                String endlessText, String modsEnabledText, String modsOptionalText,
                String terrainText, String terrainLevelText, String asteroidsText,
                String waveRosterFullDpText, String waveRosterMultiplierText,
                String seatsPerSourceText, String allSkillsText, String rngPinsText, boolean debugWatch,
                String pvpText, String pvpStationText, String magicSeamText) {
            this(bindHost, bindPort, tickText, fortressDpText, passwordText, selDescriptor,
                    selInfo, joinerDefaultText, fortressWaveScaleText, adminPasswordText,
                    waveCategoryText, endlessText, modsEnabledText, modsOptionalText, terrainText,
                    terrainLevelText, asteroidsText, waveRosterFullDpText, waveRosterMultiplierText,
                    seatsPerSourceText, allSkillsText, rngPinsText, debugWatch, pvpText, pvpStationText,
                    magicSeamText, null);
        }

        /** The pre-MagicLib 25-component shape (every caller before the
         * MagicLib select) - no MagicLib control = {@code null} = the {@code magicseam.enabled} row stays untouched. */
        public StartFields(String bindHost, String bindPort, String tickText,
                String fortressDpText, String passwordText, String selDescriptor,
                ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
                String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
                String endlessText, String modsEnabledText, String modsOptionalText,
                String terrainText, String terrainLevelText, String asteroidsText,
                String waveRosterFullDpText, String waveRosterMultiplierText,
                String seatsPerSourceText, String allSkillsText, String rngPinsText, boolean debugWatch,
                String pvpText, String pvpStationText) {
            this(bindHost, bindPort, tickText, fortressDpText, passwordText, selDescriptor,
                    selInfo, joinerDefaultText, fortressWaveScaleText, adminPasswordText,
                    waveCategoryText, endlessText, modsEnabledText, modsOptionalText, terrainText,
                    terrainLevelText, asteroidsText, waveRosterFullDpText, waveRosterMultiplierText,
                    seatsPerSourceText, allSkillsText, rngPinsText, debugWatch, pvpText, pvpStationText, null);
        }

        /** The pre-PvP 23-component shape (every caller before the
         * PvP select, the Swing window included) - no PvP control = {@code null} = the picked file's mode stands. */
        public StartFields(String bindHost, String bindPort, String tickText,
                String fortressDpText, String passwordText, String selDescriptor,
                ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
                String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
                String endlessText, String modsEnabledText, String modsOptionalText,
                String terrainText, String terrainLevelText, String asteroidsText,
                String waveRosterFullDpText, String waveRosterMultiplierText,
                String seatsPerSourceText, String allSkillsText, String rngPinsText, boolean debugWatch) {
            this(bindHost, bindPort, tickText, fortressDpText, passwordText, selDescriptor,
                    selInfo, joinerDefaultText, fortressWaveScaleText, adminPasswordText,
                    waveCategoryText, endlessText, modsEnabledText, modsOptionalText, terrainText,
                    terrainLevelText, asteroidsText, waveRosterFullDpText, waveRosterMultiplierText,
                    seatsPerSourceText, allSkillsText, rngPinsText, debugWatch, null, null);
        }

        /** The pre-button 22-component shape (every Start caller before the debug-watch button)
         * — a plain Start = {@code debugWatch} false. */
        public StartFields(String bindHost, String bindPort, String tickText,
                String fortressDpText, String passwordText, String selDescriptor,
                ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
                String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
                String endlessText, String modsEnabledText, String modsOptionalText,
                String terrainText, String terrainLevelText, String asteroidsText,
                String waveRosterFullDpText, String waveRosterMultiplierText,
                String seatsPerSourceText, String allSkillsText, String rngPinsText) {
            this(bindHost, bindPort, tickText, fortressDpText, passwordText, selDescriptor,
                    selInfo, joinerDefaultText, fortressWaveScaleText, adminPasswordText,
                    waveCategoryText, endlessText, modsEnabledText, modsOptionalText, terrainText,
                    terrainLevelText, asteroidsText, waveRosterFullDpText, waveRosterMultiplierText,
                    seatsPerSourceText, allSkillsText, rngPinsText, false);
        }

        /** The pre-replay-pins 21-component shape (the
         * selftest goldens and every caller that predates the control) — no replay-pins control =
         * {@code null} = the {@code rngpin.enabled} row stays untouched. */
        public StartFields(String bindHost, String bindPort, String tickText,
                String fortressDpText, String passwordText, String selDescriptor,
                ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
                String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
                String endlessText, String modsEnabledText, String modsOptionalText,
                String terrainText, String terrainLevelText, String asteroidsText,
                String waveRosterFullDpText, String waveRosterMultiplierText,
                String seatsPerSourceText, String allSkillsText) {
            this(bindHost, bindPort, tickText, fortressDpText, passwordText, selDescriptor,
                    selInfo, joinerDefaultText, fortressWaveScaleText, adminPasswordText,
                    waveCategoryText, endlessText, modsEnabledText, modsOptionalText, terrainText,
                    terrainLevelText, asteroidsText, waveRosterFullDpText, waveRosterMultiplierText,
                    seatsPerSourceText, allSkillsText, null);
        }

        /** The pre-seat-cap 19-component shape (the selftest
         * goldens and every caller that predates the two fields) — no Seats-per-address field and no
         * All-skills control = {@code null} = both rows stay untouched. */
        public StartFields(String bindHost, String bindPort, String tickText,
                String fortressDpText, String passwordText, String selDescriptor,
                ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
                String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
                String endlessText, String modsEnabledText, String modsOptionalText,
                String terrainText, String terrainLevelText, String asteroidsText,
                String waveRosterFullDpText, String waveRosterMultiplierText) {
            this(bindHost, bindPort, tickText, fortressDpText, passwordText, selDescriptor,
                    selInfo, joinerDefaultText, fortressWaveScaleText, adminPasswordText,
                    waveCategoryText, endlessText, modsEnabledText, modsOptionalText, terrainText,
                    terrainLevelText, asteroidsText, waveRosterFullDpText, waveRosterMultiplierText,
                    null, null);
        }

        /** The pre-1408 17-component shape (the
         * selftest goldens + the positional Start delegate) — no roster-DP field and no
         * multiplier field = {@code null} = both rows stay untouched. */
        public StartFields(String bindHost, String bindPort, String tickText,
                String fortressDpText, String passwordText, String selDescriptor,
                ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
                String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
                String endlessText, String modsEnabledText, String modsOptionalText,
                String terrainText, String terrainLevelText, String asteroidsText) {
            this(bindHost, bindPort, tickText, fortressDpText, passwordText, selDescriptor,
                    selInfo, joinerDefaultText, fortressWaveScaleText, adminPasswordText,
                    waveCategoryText, endlessText, modsEnabledText, modsOptionalText, terrainText,
                    terrainLevelText, asteroidsText, null, null);
        }

        /** The pre-multiplier 18-component shape (the
         * selftest goldens that carry a roster-DP text) — no multiplier field = {@code null} =
         * the {@code match.waveRosterMultiplier} row stays untouched. */
        public StartFields(String bindHost, String bindPort, String tickText,
                String fortressDpText, String passwordText, String selDescriptor,
                ServerOpsCore.DescriptorInfo selInfo, String joinerDefaultText,
                String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
                String endlessText, String modsEnabledText, String modsOptionalText,
                String terrainText, String terrainLevelText, String asteroidsText,
                String waveRosterFullDpText) {
            this(bindHost, bindPort, tickText, fortressDpText, passwordText, selDescriptor,
                    selInfo, joinerDefaultText, fortressWaveScaleText, adminPasswordText,
                    waveCategoryText, endlessText, modsEnabledText, modsOptionalText, terrainText,
                    terrainLevelText, asteroidsText, waveRosterFullDpText, null);
        }

        /** The same fields with the bind the lease pre-step resolved. */
        public StartFields withBind(String host, String port) {
            return new StartFields(host, port, tickText, fortressDpText, passwordText,
                    selDescriptor, selInfo, joinerDefaultText, fortressWaveScaleText,
                    adminPasswordText, waveCategoryText, endlessText, modsEnabledText,
                    modsOptionalText, //
                    terrainText, terrainLevelText, //
                    asteroidsText, //
                    waveRosterFullDpText, //
                    waveRosterMultiplierText, seatsPerSourceText, allSkillsText,
                    rngPinsText, debugWatch, // the copy carries the posture
                    pvpText, pvpStationText, magicSeamText, pilotShipText);
        }

        /** The same fields with the debug-watch posture set — the button's press builds a Start's
         * fields and flips this, never a second form. */
        public StartFields withDebugWatch(boolean on) {
            return new StartFields(bindHost, bindPort, tickText, fortressDpText, passwordText,
                    selDescriptor, selInfo, joinerDefaultText, fortressWaveScaleText,
                    adminPasswordText, waveCategoryText, endlessText, modsEnabledText,
                    modsOptionalText, terrainText, terrainLevelText, asteroidsText,
                    waveRosterFullDpText, waveRosterMultiplierText, seatsPerSourceText,
                    allSkillsText, rngPinsText, on, pvpText, pvpStationText, magicSeamText, pilotShipText);
        }
    }

    /**
     * Everything the shell paints after one tick. {@code launchFailure} is non-null exactly
     * once per failed launch (the dialog text); {@code briefBody} null = panel hidden;
     * {@code snap} null = no snapshot (bars read "-"); {@code leftover} = a previous server's
     * file (bars "-", no rows, calm detail); {@code starting} = a pressed launch still PENDING.
     */
    public record Render(String title, ServerState state, boolean starting, String headline,
            String detail, OpsSnapshot snap, boolean leftover, boolean matchOver,
            List<OpsSnapshot.SeatRow> seatRows, boolean startEnabled, boolean commandable,
            boolean killable, String briefTitle, String briefBody, String launchFailure) {
    }

    private final Path briefFile;
    private final AtomicReference<Path> followed;
    private LaneLaunch launch;
    /** The resolved launch's {@code current} process,
     * KEPT past LIVE (the launcher's life is a GUI state — between two lives no
     * writer is alive, yet the launcher is, so Kill must stay offered and Start refused);
     * cleared once it is dead. */
    private final AtomicReference<Process> launcherProcess = new AtomicReference<>();
    /** The CONSUMED launch's receipt coordinates,
     * kept from its resolution until the next {@link #follow} so the ticks after LIVE (or after a
     * calm terminal) can still read THIS press's terminal row. EDT-only like every other field
     * here (the Swing poll timer + the press listeners); the Kill thread never reads it. */
    private record Followed(Path pointer, long pressedAtMs, String logPath, Path flight) {
        /** {@code flight} = THIS press's resolved flight dir, pinned at consumption
         * or at the first later resolution ({@code null} until the pointer names one). */
        Followed withFlight(Path dir) {
            return new Followed(pointer, pressedAtMs, logPath, dir);
        }
    }

    private Followed lastFollowed;
    /** {@link #lastFollowed}'s terminal row once seen (a terminal row is final per flight);
     * {@code null} = not seen yet (re-read while the state is not live). */
    private LaneFollow.TerminalRow lastTerminal;
    private WriterWatch writerWatch = new WriterWatch();
    private List<OpsSnapshot.SeatRow> lastRunSeats = List.of();
    private OpsSnapshot lastSnap;
    /** The last painted tick ({@link #remember}); {@link #liveSnapshot} reads it, never ticks. */
    private Render lastRender;
    private String note;
    private long noteAtMs;
    private OwnerBrief brief;
    private String briefKey;
    private String lane;

    public OpsWindowModel(Path opsFile, Path briefFile) {
        this.followed = new AtomicReference<>(Objects.requireNonNull(opsFile, "opsFile"));
        this.briefFile = Objects.requireNonNull(briefFile, "briefFile");
    }

    /** The status file the window currently polls. */
    public Path followed() {
        return followed.get();
    }

    /** The last parsed snapshot (null = none) — verbs read the command file + seats off it. */
    public OpsSnapshot lastSnapshot() {
        return lastSnap;
    }

    /** The brief the window is showing (null = none); the W10 press forwards it. */
    public OwnerBrief brief() {
        return brief;
    }

    /** The launch the window is waiting on (null = none). */
    public LaneLaunch pendingLaunch() {
        return launch;
    }

    /** Is the last resolved launch's process (the launcher, once the Start chain swapped it in)
     * still alive? Read by {@link #apply}'s Start / Kill enablement. */
    public boolean launcherAlive() {
        Process p = launcherProcess.get();
        return p != null && p.isAlive();
    }

    /** The followed process (the stage, then the
     * launcher the chain swapped in — set while PENDING too), {@code null} = none / dead and
     * cleared: the Kill order's known launcher ({@code ServerOpsKill.killPublicServe}). */
    public Process launcherProcess() {
        return launcherProcess.get();
    }

    /** The RUNNING serve's snapshot as the window last PAINTED it ({@code null}
     * = no live writer) — the brick's compare key ({@link ServerOpsLaunch#brickEndpoint}). A PURE
     * read of the last {@link #tick}: the earlier shape ran its own tick here,
     * and a tick is not a read — it consumes the one-shot {@code launchFailure} and nulls the
     * pending launch, so a brick press landing on the launch's terminal tick silently ate the
     * failure dialog (the window fell to a calm STOPPED). The ≤1 s poll staleness is moot: a
     * serve's bound endpoint never changes while it runs. */
    public OpsSnapshot liveSnapshot() {
        Render r = lastRender;
        // Killable now also means "launcher alive" (the relaunch gap) - the
        // brick's key stays the LIVE writer's snapshot only.
        return r != null && r.snap() != null && ServerOpsCore.liveServer(r.state()) ? r.snap() : null;
    }

    /** Every {@link #apply} exit passes through here so {@link #liveSnapshot} can read the tick
     * the shell painted instead of running one. */
    private Render remember(Render r) {
        lastRender = r;
        return r;
    }

    /** Record an operator note for the detail line (rides {@link #NOTE_TTL_MS}). */
    public void note(String message, long nowMs) {
        this.note = message;
        this.noteAtMs = nowMs;
    }

    /**
     * Follow a pressed lane (D-OPSFILE/D-GUIREUSE): re-point the poll,
     * forget the previous lane's writer + seat rows, and hold the STARTING render until the
     * lane's first live snapshot ({@link ServerOpsCore#launchPhase}).
     */
    public void follow(LaneLaunch l) {
        followed.set(l.opsFile());
        writerWatch = new WriterWatch();
        lastRunSeats = List.of();
        launch = l;
        lane = l.lane();
        lastFollowed = null;
        lastTerminal = null;
    }

    /** A {@link Ui} whose info line lands in this model's note (and the shell's, if any). */
    public Ui noting(Ui shell, long nowMs) {
        return new Ui() {
            @Override
            public boolean confirm(String key, String title, String message) {
                return shell.confirm(key, title, message);
            }

            @Override
            public void fail(String title, String message) {
                shell.fail(title, message);
            }

            @Override
            public void info(String message) {
                note(message, nowMs);
                shell.info(message);
            }
        };
    }

    /**
     * The Start press: the whole flow is {@link ServerOpsCore#runStartFlow}; on a requested
     * spawn the window follows {@code publicOps} (the public lane's status file — the live
     * {@link OpsRoots#opsFile} for the real window, a fixture for the rehearsal).
     *
     * @return true iff the spawn was requested (the window now follows the lane)
     */
    public boolean pressStart(Ui shell, SpawnFn spawn, Path propsFile, StartFields f,
            Path publicOps, long nowMs) {
        return pressStart(shell, spawn, propsFile, f, publicOps, OpsRoots.get().pointer(), nowMs);
    }

    /** The same press with the supervisor's flight pointer named (the real
     * window passes the live {@link OpsRoots#pointer}; the rehearsal a fixture). */
    public boolean pressStart(Ui shell, SpawnFn spawn, Path propsFile, StartFields f,
            Path publicOps, Path flightPointer, long nowMs) {
        note = null;
        AtomicReference<Process> proc = new AtomicReference<>();
        // The record IS the params object - the
        // ServerOpsLaunch record entry reads every field off it (no positional unpacking here).
        boolean requested = ServerOpsLaunch.runStartFlow(noting(shell, nowMs), cmd -> {
            Process p = spawn.spawn(cmd);
            proc.set(p);
            return p;
        }, propsFile, f);
        if (requested) {
            // Proc IS the launch's current - the chain spawns the launcher
            // through this same wrapper, so its set swaps the followed process.
            follow(new LaneLaunch(ServerOpsCore.PUBLIC_LANE, publicOps,
                    OpsRoots.get().startLog().toString(), nowMs, proc.get(), flightPointer, proc));
        }
        return requested;
    }

    /**
     * The W10 press: {@link ServerOpsCore#runWatchFlow(Ui, SpawnFn, OwnerBrief, Path)} with the
     * brief this window shows FORWARDED (D-BRIEFGONE), then follow {@code watchOps}.
     *
     * @return true iff the spawn was requested
     */
    public boolean pressWatch(Ui shell, SpawnFn spawn, Path forwardDir, Path watchOps,
            long nowMs) {
        note = null;
        AtomicReference<Process> proc = new AtomicReference<>();
        boolean requested = ServerOpsLaunch.runWatchFlow(noting(shell, nowMs), cmd -> {
            Process p = spawn.spawn(cmd);
            proc.set(p);
            return p;
        }, brief, forwardDir);
        if (requested) {
            follow(new LaneLaunch(ServerOpsCore.WATCH_LANE, watchOps, ServerOpsCore.WATCH_LOG,
                    nowMs, proc.get()));
        }
        return requested;
    }

    /** One poll tick: read the followed status file + the brief file, derive the render. */
    public Render tick(long nowMs) {
        Path ops = followed.get();
        OpsSnapshot snap = null;
        String problem = null;
        if (Files.isRegularFile(ops)) {
            try {
                snap = OpsSnapshot.parseFile(ops);
            } catch (IOException | RuntimeException e) {
                problem = e.getMessage();
            }
        }
        List<String> briefLines = null;
        boolean keepBrief = false;
        if (Files.isRegularFile(briefFile)) {
            try {
                briefLines = Files.readAllLines(briefFile, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException halfWritten) {
                keepBrief = true; // a half-written file shows on the next tick; never a dialog
            }
        }
        return apply(snap, problem, ops, briefLines, nowMs, keepBrief);
    }

    /**
     * The pure step behind {@link #tick} (also the rehearsal's direct entry): snapshot +
     * parse problem + brief lines → render. {@code keepBrief} = the brief file exists but was
     * unreadable this tick (half-written): keep showing the current brief.
     */
    public Render apply(OpsSnapshot snap, String problem, Path ops, List<String> briefLines,
            long nowMs, boolean keepBrief) {
        lastSnap = snap;
        if (!keepBrief) {
            OwnerBrief parsed = ServerOpsCore.parseBrief(briefLines);
            String key = parsed == null ? null : parsed.stamp() + "" + parsed.body();
            if (!Objects.equals(key, briefKey)) {
                briefKey = key;
                brief = parsed;
            }
        }
        String briefTitle = brief == null ? null : ServerOpsCore.briefTitle(brief);
        String briefBody = brief == null ? null : brief.body();
        String title = lane == null ? TITLE : TITLE + " - " + lane;
        String noteText = note != null && nowMs - noteAtMs <= NOTE_TTL_MS ? note : null;
        boolean pidAlive = snap != null && ServerOpsCore.writerAlive(snap);
        boolean diedOnWatch = snap != null && writerWatch.observe(snap, pidAlive);
        String launchFailure = null;
        LaneLaunch pending = launch;
        if (pending != null) {
            // The staging truth makes a public exit 0 a verdict once the
            // chain has swapped in (and finished with) the launcher.
            LaunchPhase phase = LaneFollow.launchPhase(pending, snap, pidAlive, nowMs,
                    pending.exitCodeOrNull(), ServerOpsLaunch.STAGING.get());
            // A life that died under the supervisor shows FAILED (red, GONE's
            // colour: it died on THIS press) + the exception line + "relaunching" instead of
            // "STARTING" for 4 minutes; the launch stays pending (a later life may still go live).
            LaneFollow.LifeFailure life = LaneFollow.lifeFailure(pending.flightPointer(),
                    pending.pressedAtMs());
            // The PENDING-Kill handling - the followed
            // process (stage, then launcher) is known to the Kill while PENDING too; and THIS
            // press's terminal row: a refusal before life 1 or an operator's stop is a CALM end
            // (consumed now, no dialog), never "STARTING" / a red failure.
            Process current = pending.current().get();
            launcherProcess.set(current);
            LaneFollow.TerminalRow term = phase == LaunchPhase.LIVE ? null
                    : LaneFollow.terminalRow(pending.flightPointer(), pending.pressedAtMs());
            boolean calmEnd = term != null && (term.kind() == LaneFollow.Terminal.REFUSED_PRELAUNCH
                    || term.kind() == LaneFollow.Terminal.STOPPED);
            if (phase == LaunchPhase.PENDING && !calmEnd) {
                String detail = (life == null ? "" : life.detailText() + "  |  ")
                        + LaneFollow.launchPendingText(pending, nowMs)
                        + (noteText == null ? "" : "  |  " + noteText);
                return remember(new Render(title,
                        life == null ? ServerState.STOPPED : ServerState.GONE,
                        life == null, life == null ? "STARTING" : "FAILED", detail, null,
                        false, false, List.of(), false, false,
                        current != null && current.isAlive(), briefTitle, briefBody, null));
            }
            launch = null;
            lastFollowed = new Followed(pending.flightPointer(), pending.pressedAtMs(),
                    pending.logPath(),
                    LaneFollow.flightDirOrNull(pending.flightPointer(), pending.pressedAtMs()));
            lastTerminal = term;
            if (phase == LaunchPhase.LIVE) {
                note = null; // the lane is live: the start note has done its job
                noteText = null;
            } else if (!calmEnd && (term == null || term.kind() != LaneFollow.Terminal.CLEAN_EXIT)) {
                // a clean exit's final snapshot already says MATCH ENDED (no dialog for it)
                launchFailure = LaneFollow.launchFailureText(pending, phase,
                        LaneFollow.logTail(Paths.get(pending.logPath()), 15), life);
            }
        }
        ServerState state = snap == null ? ServerState.STOPPED
                : ServerOpsCore.deriveState(snap.phase(),
                        snap.isStale(nowMs, ServerOpsCore.STALE_THRESHOLD_MS), pidAlive,
                        diedOnWatch);
        boolean leftover = snap != null && state == ServerState.STOPPED;
        boolean matchOver = state == ServerState.EXITED;
        String detail;
        List<OpsSnapshot.SeatRow> rows;
        if (snap == null || leftover) {
            detail = leftover ? ServerOpsCore.leftoverDetailText(snap, nowMs)
                    : problem == null
                    ? ServerOpsCore.noFileDetail(String.valueOf(ops),
                            OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE)
                    : "ops file unreadable: " + problem;
            rows = List.of();
        } else {
            if (ServerOpsCore.rememberRunSeats(snap)) {
                lastRunSeats = snap.seats();
            }
            detail = matchOver ? ServerOpsCore.finalDetailText(snap)
                    : state == ServerState.GONE ? ServerOpsCore.goneDetailText(snap)
                    : ServerOpsCore.runDetailText(snap);
            rows = ServerOpsCore.displaySeatRows(snap, matchOver, lastRunSeats);
        }
        // THIS press's calm terminal over a dead /
        // missing writer - a Kill's stop reads STOPPED (never red "killed or crashed" GONE), a
        // pre-life refusal reads REFUSED + the launcher's own sentence (never "no ops file").
        String headline = ServerOpsCore.stateHeadline(state);
        OpsSnapshot shown = leftover ? null : snap;
        LaneFollow.TerminalRow term = followedTerminal(state);
        if (term != null && term.kind() == LaneFollow.Terminal.REFUSED_PRELAUNCH) {
            state = ServerState.STOPPED;
            headline = "REFUSED";
            detail = LaneFollow.refusedText(term, Paths.get(lastFollowed.logPath()));
            shown = null;
        } else if (term != null && term.kind() == LaneFollow.Terminal.STOPPED) {
            state = ServerState.STOPPED;
            headline = "STOPPED";
            detail = LaneFollow.stoppedText(term);
            shown = null;
        }
        if (noteText != null) {
            detail = detail + "  |  " + noteText;
        }
        boolean live = snap != null && ServerOpsCore.liveServer(state);
        boolean commandable = live && !"off".equals(snap.commandFile());
        // The LAUNCHER's life gates both buttons - in
        // the relaunch gap no writer is alive but the launcher is (Kill stays offered; a Start
        // would rewrite the file the next life boots on), and a Start still staging refuses another.
        boolean launcherUp = launcherAlive();
        if (!launcherUp) {
            launcherProcess.set(null);
        }
        return remember(new Render(title, state, false, headline,
                detail, shown, leftover, matchOver, rows,
                ServerOpsCore.startEnabled(state) && !ServerOpsLaunch.STAGING.get() && !launcherUp,
                commandable, live || launcherUp, briefTitle, briefBody, launchFailure));
    }

    /** The consumed launch's terminal row while the state is neither live nor a clean final
     * (STOPPED / GONE): cached once seen (final per flight), else re-read each such tick.
     * {@code null} = no consumed launch, a live / ended state, or no terminal row yet. */
    private LaneFollow.TerminalRow followedTerminal(ServerState state) {
        if (lastFollowed == null) {
            return null;
        }
        if (ServerOpsCore.liveServer(state) || state == ServerState.EXITED) {
            if (lastTerminal != null) {
                // A writer alive or cleanly ended AFTER this
                // press's terminal row is another story (a second shell's Start, a hand-run
                // serve.bat on the same package) - its later death is THAT serve's red GONE,
                // never our calm stop. Pinned by render-stopped-then-foreign-gone.
                lastFollowed = null;
                lastTerminal = null;
            }
            return null;
        }
        // the SHARED pointer (run/latest.txt) naming another flight ends this press's story too
        // (a foreign flight that never showed this window a live writer)
        Path dir = LaneFollow.flightDirOrNull(lastFollowed.pointer(), lastFollowed.pressedAtMs());
        if (lastFollowed.flight() == null) {
            if (dir == null) {
                return null; // no flight dir yet (a refusal before the pointer): nothing to read
            }
            lastFollowed = lastFollowed.withFlight(dir);
        } else if (dir != null && !dir.equals(lastFollowed.flight())) {
            lastFollowed = null;
            lastTerminal = null;
            return null;
        }
        if (lastTerminal == null) {
            lastTerminal = LaneFollow.terminalRowOf(lastFollowed.flight());
        }
        return lastTerminal;
    }
}
