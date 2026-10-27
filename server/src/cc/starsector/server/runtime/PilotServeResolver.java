package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchGracePolicy;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.match.MatchRespawnPolicy;
import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.match.PasswordSeatLedger;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.ops.OpsStatusFile;
import cc.starsector.server.ops.PhaseWindowStats;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static cc.starsector.server.runtime.PersistentPilotIntentCombatServer.*;

/**
 *
 * The former {@code PilotServeSession.resolve()} body, moved VERBATIM: every knob resolve, the match authority/passwords/wave schedule mint and the serve-level ledgers.
 * Runs synchronously on the caller's (sim) thread, so boot-line order, the stdout-lane install, the JFR recorder, the stall sampler and the JVM-global identity bind keep their positions.
 * The written fields below are package-private, same names as PSS's, and PSS copies them back after {@link #resolve()} returns (86 boot-only + 19 per-serve-mutable).
 * Do NOT add logic here: it is a verbatim mirror, proven by a carve-verify script.
 */
final class PilotServeResolver {
    private final PilotServeConfig config;
    private final EngineCombatBinding binding;
    private final NPeerHooks hooks;

    // the 105 fields resolve() writes; PSS mirrors each one back
    boolean eventLaneOn;
    boolean socketLiveness;
    boolean fireLaneDeclared;
    boolean fireEdgeLatch;
    boolean fireClassRoutingOn;
    double fireClassRateMax;
    Map<String, FireClassLaw.FireClass> fireClassOverrides;
    String netSinkPath;
    int netSinkMaxMiB;
    String gpeeLogPath;
    boolean shieldedRow;
    boolean hitCellRow;
    CustomVariantConfig customConfig;
    OperatorCommandFile operatorFile;
    boolean roundStartGate;
    boolean autoSpeedKnob;
    float autoSpeedFloor;
    float autoSpeedCeiling;
    boolean ioDecoupled;
    DecoupledStdoutLane stdoutLane;
    ServeBlockRecorder blockRecorder;
    SimStallStackSampler stallStacks;
    OpsStatusFile opsFile;
    PhaseWindowStats phaseWindowStats;
    WorstFramesLedger worstFrames;
    NearContactShieldLog nearContactLog;
    String respawnRaw;
    MatchRespawnPolicy respawnPolicy;
    MatchGracePolicy gracePolicy;
    DoorSourceHold.Policy chatSourceHold; // moderation.chatSourceHold
    int maxSeatsPerSource; // moderation.maxSeatsPerSource (0 = off; the owner's default 1)
    List<InetAddress> sourceCapExempt; // moderation.sourceCapExempt (literal addresses; empty default)
    TransferTargets transferTargets;
    long transferPodTimeoutTicks;
    boolean sampleBoost;
    boolean sampleSystem;
    FoeAutofireGateSampler afGateSampler;
    String peerGrade;
    int parityWalkEvery;
    boolean overrunLog;
    boolean canisterProbeOn;
    int waveSpawnPerTick;
    int hulkPoseEveryTicks;
    long asteroidSeed;
    RosterAppendCatchup<PilotIntentDatagramServer.PeerSession> rappCatchup;
    int asteroidRefreshTicks;
    int hulkPoseBurstCadenceTicks;
    int asteroidBumpCadenceTicks;
    int aimFanEveryTicks;
    boolean hulkCutGeometryOn;
    boolean onFireTruth;
    int downEveryTicks;
    MatchDescriptor npeerDescriptor;
    MatchSeatAuthority authority;
    float mapHalf;
    Map<Integer, String> opsSeatShips;
    String doorFingerprint;
    String matchPassword;
    String adminPassword;
    PasswordSeatLedger passwordLedger;
    int descriptorSeatCount;
    double fortressDpTotal;
    int waveRosterFullDp;
    double waveRosterMultiplier;
    double[] fortressWaveScale;
    WaveSchedule waveSchedule;
    boolean engineBornGrowth;
    boolean stationRowPresent;
    int stationRowSide;
    String censusVariantId;
    List<String> debugCatalogueSnapshot;
    List<WaveProbeRow> waveProbeRows;
    int rosterHullBound;
    boolean waveClearRoundEndKnob;
    boolean waveClearLatchRoundEnd;
    String waveClearLatchTag;
    float finaleCreepSu;
    Map<Long, Integer> seatIdByWireId;
    Map<Integer, Long> seatWireIdBySeatId;
    AdminSeatDebugLane adminDebug;
    SeatTeamLane seatTeams;
    long measureOwnedShipId;
    long seatBandCeiling;
    String bindHost;
    int bindPort;
    EngineBackedCombatStepBoundary baseStep;
    List<PilotRoundSummary> rounds;
    Map<Integer, SeatAnnounceTracker.SeatAnnounce> lastAnnouncedVariantBySeat;
    SeatRebindArbiter rebindArbiter;
    Set<Integer> flyInDeferredForTransfer;
    AdhocFoeSpawner adhocFoes;
    boolean moduleVerbsArmed;
    boolean clientConnected;
    boolean operatorEndMatch;
    Map<Long, PilotIntentDatagramServer.PeerSession> prevLive;
    Map<Long, PilotIntentDatagramServer.PeerSession> liveByIdScratch;
    WireRecordSink wireSink;
    GameplayEdgeEventWatchSink gpeeLog;
    ArmorCellBook armorCellBook;
    DownEmissionComposer downComposer;
    int resetFramesEmitted;
    boolean lastRoundMatchOver;
    String lastMatchOverTag;
    int lastMatchOverWinner;
    long lastMatchOverTick;
    boolean lastRoundOperatorEnd;
    long lastServedSimTick;
    boolean heldLaneFallback;
    boolean classRoutingFallback;
    long sessionDeclReceived;

    /**
     * The declared-shot press pad's boot kill switch ({@code cc.server.declPad} /
     * {@code CC_SERVER_DECL_PAD} / {@code server.declPad}, default {@code on}; strict on|off, the
     * {@code cc.server.fireClassRouting} shape). Static and side-effect free so {@link #resolve()}
     * fails a bad value at boot and every per-round {@code PilotIntentStep} mint reads the SAME
     * tuple without a new PSS field (MatchRecordMeta stamps the raw value).
     */
    static boolean resolveDeclPadKnob() {
        String declPad = ServerConfig.resolve(
                "cc.server.declPad", "CC_SERVER_DECL_PAD", "server.declPad", "on");
        return switch (declPad) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException(
                    "cc.server.declPad must be 'on' or 'off'; got '" + declPad + "'");
        };
    }

    PilotServeResolver(PilotServeConfig config, EngineCombatBinding binding, NPeerHooks hooks) {
        this.config = config;
        this.binding = binding;
        this.hooks = hooks;
    }

    /**
     * Phase 1 — resolve every knob, mint the match authority/passwords/wave schedule and the
     * serve-level ledgers (the former body up to the door open). Fail-fast IAEs unchanged.
     */
    void resolve() throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(hooks, "hooks");
        // D1 entry guards: the N door is UDP+duplex-only; every violation THROWS (house rule: an
        // invalid/ambiguous knob combination never serves silently). The transport guard lives
        // INSIDE resolve() since the TCP lane's full delete: UDP is the only constant, an explicit tcp THROWS
        // "TCP lane retired" there — the bare call keeps that fail-closed side effect.
        Transport.resolve();
        if (config.sendMode() != SendMode.DUPLEX) {
            throw new IllegalArgumentException("serveNPeer requires cc.net.sendMode=duplex"
                    + " (explicitly set); got sendMode="
                    + config.sendMode().name().toLowerCase(java.util.Locale.ROOT));
        }
        // awaitReentry needs no guard here: the PilotServeConfig ctor already THROWS on any
        // non-welded sendMode with awaitReentry=true, and duplex was just required above.
        String seat2PortFile = ServerConfig.resolvePath(
                "cc.server.seat2PortFile", "CC_SERVER_SEAT2_PORT_FILE", "server.seat2PortFile", "");
        if (!seat2PortFile.isEmpty()) {
            throw new IllegalArgumentException("serveNPeer has no seat-2 endpoint (every seat is a"
                    + " SESSION on the one door); got cc.server.seat2PortFile=" + seat2PortFile);
        }
        if (config.roundsPerSocket() > 1 && hooks.reset() == null) {
            throw new IllegalArgumentException("roundsPerSocket > 1 requires the gated reset hook"
                    + " (NPeerHooks.reset — the occupied-seat between-rounds redeploy)");
        }

        // The calling thread IS the sim thread for the whole serve,
        // and on a Windows box co-hosting the owner's client it gets descheduled behind its own
        // siblings (C2/GC/net workers) and foreground load at quantum expiry — the
        // adv_cpu≪wall stall class. MAX_PRIORITY maps to THREAD_PRIORITY_HIGHEST on Windows
        // HotSpot (exactly the "thr" rig config: 0 in-round stalls vs base 48 under 18
        // hogs); a process-class boost lifts every sibling too and still stalled.
        // One syscall at boot, zero per tick. Linux ignores Java priorities without
        // -XX:ThreadPriorityPolicy=1 + root ⇒ a silent no-op there (co-host regime = Windows).
        // The readback row is the per-flight proof the lever applied.
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        System.out.println("npeer-serve sim_thread_priority=" + Thread.currentThread().getPriority()
                + " thread=" + Thread.currentThread().getName());

        // Knobs resolved identically to the retired serve() lane (git history) where wired; the
        // legacy measurement/foe knobs are NOT wired at N (v1) — loud, never silent (D11: every
        // seat is a live client seat).
        eventLaneOn = ServerConfig.resolveBoolean(
                "cc.predict.eventReplay", "CC_PREDICT_EVENT_REPLAY", "predict.eventReplay", false);
        socketLiveness = ServerConfig.resolveBoolean(
                "cc.net.socketLiveness", "CC_NET_SOCKET_LIVENESS", "net.socketLiveness", false);
        String fireLane = ServerConfig.resolve(
                "cc.server.fireLane", "CC_SERVER_FIRE_LANE", "server.fireLane", "held");
        // (field) fireLaneDeclared;
        switch (fireLane) {
            case "declared" -> fireLaneDeclared = true;
            case "held" -> fireLaneDeclared = false;
            default -> throw new IllegalArgumentException(
                    "cc.server.fireLane must be 'declared' or 'held'; got '" + fireLane + "'");
        }
        // fireEdgeSeq depth-1 latch enable. The latch lives
        // INSIDE the held lane (the one-lane rule), so it is inert under fireLane=declared.
        // Compiled true (E3 hardwire): the `false` level-sample A/B
        // forensic baseline is retired; the knob-resolved shape is in git.
        fireEdgeLatch = true;
        // Class routing is
        // the per-TICK lane-choice master lever. Strict on|off
        // (house rule: invalid knobs never serve silently); exclusive with the legacy whole-ship
        // declared lane — routing IS the declared serve, per-tick — so the pair THROWS at boot.
        // Default ON since the step-3 live PASS — the
        // MatchRecordMeta stamp carries the SAME literal; flip both in lockstep.
        String fireClassRouting = ServerConfig.resolve(
                "cc.server.fireClassRouting", "CC_SERVER_FIRE_CLASS_ROUTING",
                "server.fireClassRouting", "on");
        // (field) fireClassRoutingOn;
        switch (fireClassRouting) {
            case "on" -> fireClassRoutingOn = true;
            case "off" -> fireClassRoutingOn = false;
            default -> throw new IllegalArgumentException(
                    "cc.server.fireClassRouting must be 'on' or 'off'; got '" + fireClassRouting + "'");
        }
        if (fireClassRoutingOn && fireLaneDeclared) {
            throw new IllegalArgumentException("cc.server.fireClassRouting=on is exclusive with"
                    + " cc.server.fireLane=declared (routing serves declarations per-tick; the"
                    + " whole-ship declared lane would double-select the FIRE owner)");
        }
        resolveDeclPadKnob(); // Boot fail-fast; each round's PilotIntentStep re-reads it
        // The S/M class-rate boundary — compiled to the law constant (E3 hardwire;
        // operator retune retired, the knob-resolved strict-loud parse is in
        // git). The threshold still lives in ONE place (FireClassLaw.DEFAULT_RATE_MAX).
        fireClassRateMax = FireClassLaw.DEFAULT_RATE_MAX;
        // parseOverrides THROWS on any malformed entry (FireClassLaw law) — resolved at boot so a
        // bad operator exception list can never reach a serving round.
        fireClassOverrides = FireClassLaw.parseOverrides(
                ServerConfig.resolve("cc.server.fireClassOverride", "CC_SERVER_FIRE_CLASS_OVERRIDE",
                        "server.fireClassOverride", ""));
        netSinkPath = ServerConfig.resolvePath(
                "cc.net.sinkPath", "CC_NET_SINK_PATH", "net.sinkPath", "");
        // >0 = the PUBLIC-serve sink shape (byte cap in MiB +
        // no-clobber across supervised lives, WireRecordSink.openBounded); 0 = every other lane's
        // unbounded overwrite-on-open, byte-identical. run-ops-serve.ps1 ferries 32768 (32 GiB
        // ≈ 5 h of a one-seat flight at the measured ~6 GB/h).
        netSinkMaxMiB = ServerConfig.resolveInt(
                "cc.net.sinkMaxMiB", "CC_NET_SINK_MAX_MIB", "net.sinkMaxMiB", 0);
        // per-run GPEE watch log (INSTRUMENT; blank/off = no sink, zero
        // serve-loop cost). ABSOLUTE path expected (the JVM's cwd is starsector-core) — the
        // render-watch launcher sets it per run beside the .ccwr; never wire-derived.
        gpeeLogPath = ServerConfig.resolve(
                "cc.server.gpeeLogPath", "CC_SERVER_GPEE_LOG_PATH", "server.gpeeLogPath", "").trim();
        if ("off".equalsIgnoreCase(gpeeLogPath)) {
            gpeeLogPath = "";
        }
        // The type=shielded ledger row (INSTRUMENT; false = the hot path and the
        // rows byte-identical). The specimen arena arms it (run-cat-serve -ArenaSeat) so a god-mode
        // decoy holding its shield up reports every hit, beams and DEM payloads included.
        shieldedRow = ServerConfig.resolveBoolean(
                "cc.server.shieldedRow", "CC_SERVER_SHIELDED_ROW", "server.shieldedRow", false);
        // The dmg-taken hit* tokens (INSTRUMENT; false = no modifier proxy, rows byte-identical).
        hitCellRow = ServerConfig.resolveBoolean(
                "cc.server.hitCellRow", "CC_SERVER_HIT_CELL_ROW", "server.hitCellRow", false);
        // W15 v0: the custom.* family — strict parse (a bad value THROWS here);
        // submissions=false is byte-identical OFF (no lane, no facts, one echo row at wiring).
        customConfig = CustomVariantConfig.resolve();
        // D6 O-0 operator house file (freeze): blank = no operator surface
        // (zero serve-loop behavior change); non-blank resolves LOUD here (Path.of THROWs on an
        // unusable path — house rule) and polls consume-on-read inside the frame loop at (a2).
        String operatorFilePath = ServerConfig.resolvePath(
                "cc.server.commandFile", "CC_SERVER_COMMAND_FILE", "server.commandFile", "");
        operatorFile =
                operatorFilePath.isEmpty() ? null : new OperatorCommandFile(operatorFilePath);
        // Round-start gate, REWRITTEN to first-attach:
        // hold round 0's frame-0 pacer anchor only until the
        // FIRST occupied seat latches combat_ready (per-MATCH latch ⇒ round 0 only), bounded
        // by clientWaitMillis; timeout proceeds = the pre-gate behavior. Later seats DROP IN
        // to the running round ((j) latch → (c) fly-in) — never held, never repositioned.
        // Synthetic joiners send intents immediately post-admit, so game-free lanes pass in
        // one pump.
        roundStartGate = ServerConfig.resolveBoolean(
                "cc.server.roundStartGate", "CC_SERVER_ROUND_START_GATE",
                "server.roundStartGate", true);
        // Auto sim-speed governor (a policy layer over the sim-speed
        // actuator): default OFF. The law sizes on the
        // speed-INVARIANT frameWorkNanos, never the overrun counters measured against the
        // pacer interval it manipulates. Floor/ceiling bound the governed 0.25-grid inside
        // the verb's [0.25, 2.0]; an invalid PAIR falls soft to 0.5/1.0 LOUD (operator-line
        // posture: receipt, never a boot kill — the manual verb range is unaffected).
        autoSpeedKnob = ServerConfig.resolveBoolean(
                "cc.sim.autoSpeed", "CC_SIM_AUTO_SPEED", "sim.autoSpeed", false);
        autoSpeedFloor = ServerConfig.resolveFloat(
                "cc.sim.autoSpeedFloor", "CC_SIM_AUTO_SPEED_FLOOR", "sim.autoSpeedFloor", 0.5f);
        autoSpeedCeiling = ServerConfig.resolveFloat(
                "cc.sim.autoSpeedCeiling", "CC_SIM_AUTO_SPEED_CEILING",
                "sim.autoSpeedCeiling", 1.0f);
        if (!SimSpeedGovernorLaw.validBounds(autoSpeedFloor, autoSpeedCeiling,
                OperatorCommandFile.SIM_SPEED_MIN, OperatorCommandFile.SIM_SPEED_MAX)) {
            System.out.println("npeer-serve sim_speed_auto_bounds_invalid floor=" + autoSpeedFloor
                    + " ceiling=" + autoSpeedCeiling + " (need 0.25-grid multiples,"
                    + " floor<=ceiling, inside [" + OperatorCommandFile.SIM_SPEED_MIN + ", "
                    + OperatorCommandFile.SIM_SPEED_MAX + "]; using defaults 0.5/1.0)");
            autoSpeedFloor = 0.5f;
            autoSpeedCeiling = 1.0f;
        }
        // Decoupled file I/O: bounded-queue writer threads own EVERY
        // per-tick file write (stdout log, ccwr sink, ops rewrite); the serve thread only
        // enqueues (bench: HDD sync-load 312 ms worst vs 0.24 ms enqueue; the disk, not
        // the write pattern, is the stall mechanism). Queue-full = fail-open drop-to-SYNC,
        // never a lost or reordered row (the no-loss logging law; BoundedChunkDrain). Default ON;
        // cc.io.decoupled=false is the sync rollback lever. The stdout lane installs HERE
        // (before the serve's first prints matter) and BEFORE the io meter below, so io_*
        // keeps meaning "serve-thread stdout blocking time" in both modes.
        ioDecoupled = ServerConfig.resolveBoolean(
                "cc.io.decoupled", "CC_IO_DECOUPLED", "io.decoupled", true);
        stdoutLane = DecoupledStdoutLane.installForServe(ioDecoupled);
        // The JFR block-catcher — names the NEXT zero-CPU stall's wait itself
        // (monitor/park/file/socket/safepoint/preempt). Blank or "off" = OFF; the
        // wrapper defaults it to <runDir>\serve-blocks.jfr. Off the sim thread by construction
        // (JFR's recorder thread writes); stopped + receipted in the teardown finally.
        blockRecorder = ServeBlockRecorder.startForServe(
                ServerConfig.resolvePath("cc.jfr.blocks", "CC_JFR_BLOCKS", "jfr.blocks", ""));
        // jstack-on-stall — armed once the step boundary exists (inside
        // the try below), closed in the teardown finally; ≤0 = OFF. Names the sim thread's
        // JFR-invisible wait BY FRAME while it is still blocked (the 398 ms stall class).
        final int stallStackMs = ServerConfig.resolveInt(
                "cc.sim.stallStackMs", "CC_SIM_STALL_STACK_MS", "sim.stallStackMs",
                SimStallStackSampler.DEFAULT_THRESHOLD_MS);
        stallStacks = null;
        // Ops snapshot surface: blank = OFF (zero serve change); non-blank resolves
        // LOUD here (Path.of THROWs on an unusable path — house rule) and the writer is
        // 1s-throttled + fail-soft after boot. command_file rides IN the snapshot so the GUI
        // self-discovers the verb drop.
        String opsFilePath = ServerConfig.resolvePath(
                "cc.server.opsFile", "CC_SERVER_OPS_FILE", "server.opsFile", "");
        opsFile = opsFilePath.isEmpty() ? null
                : new OpsStatusFile(opsFilePath,
                        operatorFilePath.isEmpty() ? "off" : operatorFilePath, ioDecoupled);
        // Scale-lane row 2: per-phase mean/p95 window stats — the
        // loop feeds the witness brackets every frame; the ops compose snapshots-and-resets
        // (LAW in PhaseWindowStats; ops-file-gated like the rest of the surface).
        phaseWindowStats = opsFile == null ? null : new PhaseWindowStats();
        if (opsFile != null) {
            opsFile.attachPhaseStats(phaseWindowStats);
        }
        // Always-on worst-frames ledger — deliberately UNconditional (no knob, no
        // lean-serve exemption): every round self-reports its top frames WITH attribution
        // (LAW + row grammar in WorstFramesLedger).
        worstFrames = new WorstFramesLedger(8);
        // Near-contact shield-geometry lane (the F9 shield-ram diff instrument).
        // Default OFF; knob value = the gate radius in world units, armed per-watch via the
        // -ServerEnv ferry only. WHY + row grammar + residency/security laws live in
        // NearContactShieldLog.
        int shieldContactRadius = ServerConfig.resolveInt(
                "cc.server.shieldContactLog", "CC_SERVER_SHIELD_CONTACT_LOG",
                "server.shieldContactLog", 0);
        nearContactLog =
                shieldContactRadius > 0 ? new NearContactShieldLog(shieldContactRadius) : null;
        // Respawn policy (v1 default = disabled: hull death dead-latches the seat for the
        // round). Parsed loud HERE (the consumer); the authority applies it INTERNALLY — the loop
        // only arms.
        respawnRaw = ServerConfig.resolve(
                "cc.match.respawnPolicy", "CC_MATCH_RESPAWN_POLICY", "match.respawnPolicy", "disabled");
        respawnPolicy =
                MatchRespawnPolicy.parse(respawnRaw, Math.round(1.0 / config.fixedDt()));
        // The legacy conflict guard, ported — matchOver LATCHES in the
        // snapshot source, and under cc.server.perSideRoundEnd a seat death latches that SAME
        // capture before any next-frame fly-in could land; respawn-enabled serves end rounds at
        // the frame budget (the D1 house rule above). The legacy G2/G3 twins stay dead at N:
        // the descriptor-minted authority ALWAYS exists here, and NPeerHooks requires flyIn.
        if (!respawnPolicy.disabled() && ServerConfig.resolveBoolean(
                "cc.server.perSideRoundEnd", "CC_SERVER_PER_SIDE_ROUND_END", "server.perSideRoundEnd", false)) {
            throw new IllegalArgumentException("cc.match.respawnPolicy=" + respawnRaw
                    + " conflicts with cc.server.perSideRoundEnd=true: a seat death latches"
                    + " matchOver before any fly-in can land — respawn-enabled serves end"
                    + " rounds at the frame budget");
        }
        // W5 reconnect grace:
        // the knob pair, parsed loud HERE (the consumer — the respawnPolicy precedent above) at
        // the serve's EFFECTIVE tick rate so a --fixed-dt override stays coherent. Compiled
        // default 0 = OFF (today's pilotless coast; toggle discipline — certified lanes stay
        // byte-identical); live=60 at watches, default flip is acceptance-watch-gated.
        // parkPolicy parses strictly even when OFF.
        String graceRaw = ServerConfig.resolve(
                "cc.match.graceSeconds", "CC_MATCH_GRACE_SECONDS", "match.graceSeconds", "0");
        String parkRaw = ServerConfig.resolve(
                "cc.match.parkPolicy", "CC_MATCH_PARK_POLICY", "match.parkPolicy", "stay-ai");
        gracePolicy =
                MatchGracePolicy.parse(graceRaw, parkRaw, Math.round(1.0 / config.fixedDt()));
        // The chat-ban SOURCE HOLD arm, parsed LOUD here (an
        // unknown token fails the boot naming off | observer | match).
        // compiled default match = every chat ban bars its address for the ban's term, across
        // restarts; off = A7 strict; server-settable.
        // The SAME policy governs console / command-file `ban <seat>` (live
        // and offline) — the knob keeps its name for the hosts' files; the template comment says "any ban".
        chatSourceHold = DoorSourceHold.Policy.parse(ServerConfig.resolve("cc.moderation.chatSourceHold",
                "CC_MODERATION_CHAT_SOURCE_HOLD", "moderation.chatSourceHold", "match"));
        // The per-address LIVE-SEAT allowance, parsed LOUD here (a junk row fails the boot naming 0..255;
        // the launcher refused it pre-write already). Compiled default 1 = one pilot seat per internet
        // address, this box exempt; 0 = off. Server-settable; the door binds the exempt set.
        //
        maxSeatsPerSource = SourceSeatCap.parseCap(ServerConfig.resolve("cc.moderation.maxSeatsPerSource",
                "CC_MODERATION_MAX_SEATS_PER_SOURCE", SourceSeatCap.ROW, "1"));
        // The operator's extra exempt addresses beside the allowance, parsed LOUD here (a hostname, a stray
        // comma or a 65th entry fails the boot naming the entry; literals only, so the row never causes a DNS
        // lookup). Empty default = this box's own addresses only; a VPN hairpin that arrives from the tunnel's
        // gateway address is named here.
        sourceCapExempt = SourceSeatCap.parseExempt(ServerConfig.resolve("cc.moderation.sourceCapExempt",
                "CC_MODERATION_SOURCE_CAP_EXEMPT", SourceSeatCap.ROW_EXEMPT, ""));
        // W3 command transfer:
        // the knob trio, parsed loud HERE (the consumer — the grace precedent above) at the
        // serve's EFFECTIVE tick rate. Strict even before the serve drain lands (L1b-ii): a
        // bad knob value must fail the boot, never the first /transfer.
        String transferContestRaw = ServerConfig.resolve(
                "cc.transfer.contest", "CC_TRANSFER_CONTEST", "transfer.contest", "priority");
        String transferBeginFloorRaw = ServerConfig.resolve("cc.transfer.beginFloorSeconds",
                "CC_TRANSFER_BEGIN_FLOOR_SECONDS", "transfer.beginFloorSeconds", "5");
        String transferHullCooldownRaw = ServerConfig.resolve("cc.transfer.hullCooldownSeconds",
                "CC_TRANSFER_HULL_COOLDOWN_SECONDS", "transfer.hullCooldownSeconds", "10");
        SeatRebindArbiter.TransferPolicy transferPolicy = SeatRebindArbiter.TransferPolicy.parse(
                transferContestRaw, transferBeginFloorRaw, transferHullCooldownRaw,
                Math.round(1.0 / config.fixedDt()));
        // D7 presentation gate: compiled default TRUE
        // — the flip owed since the L4 apply leg landed
        // (a commit flies the real command shuttle, no longer death-shaped) sat
        // unexecuted in this comment for ~400 sessions. Strict LOUD like the
        // trio (resolveBoolean's junk-as-false announce would flip an acceptance-gated knob
        // silently). OFF = null census: the door replies "/transfer unavailable" and the vanilla
        // Tab trigger is DENIED at idle (pod yanked home, no shuttle — designed, not a defect);
        // the lane still vacate-drains + heartbeats.
        boolean transferEnabled = SeatRebindArbiter.TransferPolicy.parseEnabledKnob(
                ServerConfig.resolve(
                        "cc.transfer.enabled", "CC_TRANSFER_ENABLED", "transfer.enabled",
                        "true"));
        transferTargets = transferEnabled ? new TransferTargets() : null;
        // W3 L2: the pod-flight wall clock, parsed loud
        // HERE at the serve's EFFECTIVE tick rate like the trio above; 0 = no timeout. The
        // knob is live-meaningful only once the P3 dock-gated commit spawns pods, but a bad
        // value must fail the boot NOW, never the first flight.
        transferPodTimeoutTicks = TransferPodLane.parseTimeoutTicks(
                ServerConfig.resolve("cc.transfer.podTimeoutSeconds",
                        "CC_TRANSFER_POD_TIMEOUT_SECONDS", "transfer.podTimeoutSeconds",
                        "120"),
                Math.round(1.0 / config.fixedDt()));
        sampleBoost = ServerConfig.resolveBoolean(
                "cc.measure.boostSample", "CC_MEASURE_BOOST_SAMPLE", "measure.boostSample", false);
        sampleSystem = ServerConfig.resolveBoolean(
                "cc.measure.systemSample", "CC_MEASURE_SYSTEM_SAMPLE", "measure.systemSample", false);
        // Foe autofire GATE sampler (default OFF = serve path byte-untouched)
        // — the autofire-flicker gate disambiguator over ONE wave-glimmer irpulse mount;
        // read-only + fail-soft, edge-triggered lines under a hard cap (class javadoc).
        boolean sampleAfGate = ServerConfig.resolveBoolean(
                "cc.measure.afGateSample", "CC_MEASURE_AF_GATE_SAMPLE", "measure.afGateSample",
                false);
        afGateSampler = sampleAfGate ? new FoeAutofireGateSampler() : null;
        // population-intake stamp: DECLARED, never verified — a ghost and a
        // human seat are wire-identical by design (the merger-equivalence gate is that proof), so
        // "proven at N" runs must SAY what drove their seats. Vocabulary: ghost |
        // shared-stack-ghost | headless-twin | human (free-form for mixed fleets, e.g.
        // "ghost:9,human:1"); whitespace collapses to '-' so the k=v meter grammar survives.
        // run-npeer-boot.ps1 declares ghost — its joiners are PilotIntentDatagramClient by
        // construction.
        peerGrade = ServerConfig.resolve(
                "cc.server.peerGrade", "CC_SERVER_PEER_GRADE", "server.peerGrade", "undeclared")
                .trim().replaceAll("\\s+", "-");
        // The parity walk is WIRED at N (same knob + the retired serve() lane's
        // twin walk site — git history — on the loop's post-capture read below); default 0 = OFF
        // = the product door serves byte-identical.
        parityWalkEvery = ServerConfig.resolveInt(
                "cc.parity.walkEvery", "CC_PARITY_WALK_EVERY", "parity.walkEvery", 0);
        // (84) feel-watch lean serve: gates the per-frame frame_overrun
        // witness println ONLY (~9.5% of a stutter-era server.out); work_frame_stall (>100 ms)
        // stays unconditional for post-hoc explainability. -Feel serves set this false.
        overrunLog = ServerConfig.resolveBoolean(
                "cc.serve.overrunLog", "CC_SERVE_OVERRUN_LOG", "serve.overrunLog", true);
        // The (62) canister-flak MEASURE probe
        // (a closed measure rider) walks every roster ship's phase cloak reflectively
        // EVERY tick for its edge detector — default OFF; arm it for a canister re-measure.
        canisterProbeOn = ServerConfig.resolveBoolean(
                "cc.serve.canisterProbe", "CC_SERVE_CANISTER_PROBE", "serve.canisterProbe", false);
        // wave-spawn stagger: max wave rows deployed per frame (per-hull warm cost
        // measured 4.5-6.3 ms; 1 keeps the pre bracket under the 16.67 ms frame
        // budget even on forensic serves). 0 = legacy batch (the rollback lever).
        waveSpawnPerTick = ServerConfig.resolveInt(
                "cc.serve.waveSpawnPerTick", "CC_SERVE_WAVE_SPAWN_PER_TICK",
                "serve.waveSpawnPerTick", 1);
        // The per-hull FLY-IN stagger window,
        // seconds — WaveSchedule.DEFAULT_FLY_IN_STAGGER_SEC carries the WHY; 0 = off.
        float waveFlyInStaggerSec = ServerConfig.resolveFloat(
                "cc.serve.waveFlyInStaggerSec", "CC_SERVE_WAVE_FLYIN_STAGGER_SEC",
                "serve.waveFlyInStaggerSec", WaveSchedule.DEFAULT_FLY_IN_STAGGER_SEC);
        // corpse-pose keyframe cadence — ticks between periodic HKPS pose
        // keyframes per corpse family (creation/edge ticks always emit); tickrate-agnostic
        // default = round(tickRate/2) ≈ 2 Hz; 0 = OFF (the rollback lever).
        hulkPoseEveryTicks = ServerConfig.resolveInt(
                "cc.server.hulkPoseEveryTicks", "CC_SERVER_HULK_POSE_EVERY_TICKS",
                "hulk.poseEveryTicks", Math.round(SimTickRate.ticksPerSecond() / 2f));
        asteroidSeed = ServerConfig.resolveInt("cc.server.asteroidSeed", "CC_SERVER_ASTEROID_SEED", "server.asteroidSeed", 1398); //
        // Paced
        // late-joiner RAPP catch-up cap (frames/tick/session; <= 0 = the legacy one-shot rollback lever).
        // Literal tuple: the knob checker pairs the alias from the call itself.
        rappCatchup = new RosterAppendCatchup<>(ServerConfig.resolveInt("cc.server.rappCatchupPerTick",
                "CC_SERVER_RAPP_CATCHUP_PER_TICK", "server.rappCatchupPerTick", RosterAppendCatchup.DEFAULT_CAP));
        System.out.println("npeer-serve rapp_catchup per_tick=" + rappCatchup.cap()
                + " reserve=" + RosterAppendCatchup.RESERVE
                + " mode=" + (rappCatchup.paced() ? "paced" : "legacy_one_shot"));
        asteroidRefreshTicks = ServerConfig.resolveInt("cc.server.asteroidRefreshTicks", "CC_SERVER_ASTEROID_REFRESH_TICKS", "server.asteroidRefreshTicks", 6); //
        // impulse-triggered follow-burst — a contact Δv on a hulk piece
        // emits that family's keyframes off-cadence for a ~1 s window, back to the periodic
        // cadence when quiet (average wire cost ≈ 0: scales with collisions only,
        // 64 B/keyframe). KNOB RESTORED (reverses
        // the hardwire for THIS row only: "it's a useful lever if we find corpses are
        // causing lag, or are not smooth enough"). Default formula:
        // burst update RATE = tickRate/4 Hz (15 Hz at 60 tick) ⇒ one update
        // every 4 ticks — cadence 4 flat, tickrate-agnostic; 0 = OFF (the rollback lever).
        hulkPoseBurstCadenceTicks = ServerConfig.resolveInt(
                "cc.server.hulkPoseBurstCadenceTicks", "CC_SERVER_HULK_POSE_BURST_CADENCE_TICKS",
                "hulk.poseBurstCadenceTicks", 4);
        // Asteroid cadence ("the asteroid update-rate knob should be
        // SEPARATE from the hulk knob"): the asteroid lane's bump/hp-row cadence cap. Default =
        // the hulk burst cadence (today's bytes unchanged; a hulk lane OFF still leaves the
        // rocks at 4), so the two knobs part company only when one is set.
        asteroidBumpCadenceTicks = ServerConfig.resolveInt(
                "cc.server.asteroidBumpCadenceTicks", "CC_SERVER_ASTEROID_BUMP_CADENCE_TICKS",
                "server.asteroidBumpCadenceTicks", hulkPoseBurstCadenceTicks > 0 ? hulkPoseBurstCadenceTicks : 4);
        // A CAP in ticks, min 1 — 0 is NOT "off" here (the hulk
        // knob's 0 = OFF idiom does not carry: the asteroid lane always emits bump rows, else the two
        // sides' rocks drift apart) and AsteroidFieldServeLane would silently clamp 0/negative to a
        // 1-tick = 60 Hz cap. Fail loud at boot, the fireClassRouting / engineBornGrowth shape.
        if (asteroidBumpCadenceTicks < 1) {
            throw new IllegalArgumentException("cc.server.asteroidBumpCadenceTicks=" + asteroidBumpCadenceTicks
                    + " must be >= 1 (a cadence cap in ticks; 0 is not OFF — rocks are switched by the"
                    + " asteroids knob, not by this cap)");
        }
        // AIMP per-piloted-seat aim-point fan cadence — ticks between
        // change-gated batched AIMP frames; tickrate-agnostic default = round(tickRate/8)
        // ≈ 8 Hz; 0 = OFF (the rollback lever). Keepalive re-emit + stale-intent bound ride
        // round(tickRate) ≈ 1 s inside the lane (AutofireTargetLane idiom).
        aimFanEveryTicks = ServerConfig.resolveInt(
                "cc.server.aimFanEveryTicks", "CC_SERVER_AIM_FAN_EVERY_TICKS",
                "aim.fanEveryTicks", Math.round(SimTickRate.ticksPerSecond() / 8f));
        // HKCG hulk-cut-geometry capture + fan — the server half of the
        // wire-carried carve (clients feed the engine's own two-arg splitShip with these
        // rings). Compiled ON (E3 hardwire, co-adopted with the client twin
        // per the twins-decide-together audit rule; the strict-boolean knob shape is in git).
        hulkCutGeometryOn = true;
        // Covered-mount WEAPON_DISCHARGE emission source —
        // confessed onFire births (true fire tick, exact per-shot bearing) vs the legacy
        // detector; all law in OnFireTruthMerger. Sweep-idiom strict boolean (the
        // cc.server.onFireShadow parse); inert-by-construction when the boot sweep did not
        // latch (covered set empty ⇒ the per-round merger is identity). Rollback =
        // server.onFireTruth=false.
        String onFireTruthRaw = ServerConfig.resolve(
                "cc.server.onFireTruth", "CC_SERVER_ONFIRE_TRUTH", "server.onFireTruth", "true");
        onFireTruth = switch (onFireTruthRaw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "", "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException(
                    "cc.server.onFireTruth must be a boolean; got '" + onFireTruthRaw + "'");
        };
        System.out.println("onfire-truth source=" + (onFireTruth ? "callback" : "detector"));
        // Every-other-world: global DOWN SNAP cadence divisor — the first
        // steady-state exercise of the sim-tick/net-tick separation. Off-ticks fan
        // CTRL only (GPEE/EDSP/SEAT every tick — deaths and fire edges lose nothing); SNAP
        // is latest-wins + merger hold-last, so a withheld fan is indistinguishable from
        // the loss the lane is designed for (the wire-cost law). Compose + record stay
        // every-tick. Default 1 = byte-identical to today; expressed in TICKS, never a
        // literal rate. k=2 default flip is feel-confirm-gated.
        // Strict parse: non-integer or < 1 THROWS at serve entry.
        String downEveryRaw = ServerConfig.resolve(
                "cc.net.downEveryTicks", "CC_NET_DOWN_EVERY_TICKS", "net.downEveryTicks", "1");
        // (field) downEveryTicks;
        try {
            downEveryTicks = Integer.parseInt(downEveryRaw.trim());
        } catch (NumberFormatException badInt) {
            throw new IllegalArgumentException(
                    "cc.net.downEveryTicks must be an integer >= 1; got '" + downEveryRaw + "'");
        }
        if (downEveryTicks < 1) {
            throw new IllegalArgumentException(
                    "cc.net.downEveryTicks must be an integer >= 1; got '" + downEveryRaw + "'");
        }
        System.out.println("npeer-serve down_every_ticks=" + downEveryTicks);
        // The measure samplers are WIRED at N (the last loop-differ
        // — measure-real-pilot-loop.ps1 drives THIS door now). Read-only + fail-soft with the
        // retired serve() lane's exact tally/print grammar (git history), keyed on the MIN seat
        // wire id (the D8 rule, descriptor-driven
        // at N). The legacy foe-idle leg deliberately does NOT port: every seat at N is a live
        // client seat (the retired foeInert knob is forced off by construction),
        // and the one-client measure admits ONE seat, so no foe deploys at all. Default OFF =
        // the product door serves byte-identical.
        if (sampleBoost || sampleSystem) {
            System.out.println("npeer-serve measure_samplers_armed boost_sample=" + sampleBoost
                    + " system_sample=" + sampleSystem
                    + " (read-only; no foe-idle at N: every seat is a live client seat)");
        }

        // Boot order: mint authority → arm ONCE → open the door (maxSessions = descriptor
        // seat count) → onReady → sink → chunk-gate → lobby → rounds. Decode-once:
        // the ops file needs the descriptor OBJECT for seat→ship names; same fail-closed decode.
        npeerDescriptor = decodeUdpSeatDescriptor();
        authority = new MatchSeatAuthority(npeerDescriptor);
        // Bind this serve's descriptor identity into every .ccwr header this JVM opens
        // from here on (the wire sink below) — fail-soft, a pure-record feature never boots RED.
        bindNpeerMatchIdentity(npeerDescriptor);
        // Map-size single source (absorbs the lockstep guard): the
        // descriptor IS the client's initMap truth, so the engine bounds this serve applies
        // below (applyCombatMapBounds) DERIVE from it — one truth, no knob read, no env ferry,
        // the split-brain class structurally dead. Non-square throws at boot (fail-closed).
        mapHalf = MatchDescriptors.serveMapHalf(npeerDescriptor);
        System.out.println("npeer-serve map_half=" + mapHalf + " (descriptor-derived)");
        opsSeatShips = opsFile == null
                ? Map.of() : OpsStatusFile.seatShipNames(npeerDescriptor);
        doorFingerprint = mintDoorFingerprint();
        // The <descriptor>.password sidecar arms password mode for THIS serve
        // (re-read per serve — persistence law; absent = token mode, bit-identical). Minted here
        // beside the fingerprint and consumed by the door fork below: the ledger resolves each
        // client-instance id to a descriptor seat; the authority stays the ONE occupancy truth.
        matchPassword = mintMatchPassword();
        // A REDACTED kit copy (placeholder tokens) never serves in token
        // mode — the authority above would have minted the placeholders live (fail-closed, readable).
        MatchPasswords.requireLiveTokensInTokenMode(npeerDescriptor, matchPassword);
        // A password-mode door serves the match document - one no join
        // window could adopt fails the boot here, loud, never a stranger's dead end (token mode serves none, skips it).
        preflightMatchDoc(npeerDescriptor, matchPassword);
        // The admin credential (null = no admin seat) — minted beside the match
        // password (its laws need both), threaded to the door after construct (D1: the same HELLO
        // slot; the door marks the presenting session admin ⇒ per-seat WELCOME bit3).
        adminPassword = mintAdminPassword(matchPassword);
        // D-SEATRACE: ships a first-free claim never hands out — the
        // lane's own pinned client (the render watch's window-1) owns them at the door. INFRA
        // knob (per-lane by nature); blank/off = none; a bad row throws here, at the open.
        java.util.Set<Long> reservedShipIds = PasswordSeatLedger.parseReservedShipIds(
                ServerConfig.resolve("cc.server.reservedShipIds", "CC_SERVER_RESERVED_SHIP_IDS",
                        "server.reservedShipIds", ""));
        passwordLedger = matchPassword == null ? null
                : new PasswordSeatLedger(npeerDescriptor, reservedShipIds);
        if (!reservedShipIds.isEmpty()) {
            System.out.println("npeer-serve reserved_ships=" + reservedShipIds
                    + " (first-free admission skips them; " + (matchPassword == null
                            ? "token mode - per-seat tokens never auto-assign, row inert)"
                            : "password mode)"));
        }
        authority.armSeatMachine(respawnPolicy);
        // W5: grace arms on top of the seat machine (a sub-state of its AFK state); an OFF
        // policy arms legally and stays inert — no lane is minted below, so the serve is
        // byte-identical to pre-grace builds.
        authority.armGraceMachine(gracePolicy);
        System.out.println("npeer-serve grace_policy " + gracePolicy);
        descriptorSeatCount = authority.mintedTokens().size();
        // A recording serve refuses at BOOT,
        // never at the sink open (whose failure is caught and served unrecorded): a positive byte cap
        // below the floor would truncate the flight. The seat count is only ADVISED against the bound:
        // the per-tick record load is one DOWN + one batched TAKES record + the UP arrivals, and a full
        // queue writes synchronously on the submitting thread (frame time, never a lost record), so
        // no seat count is refused.
        if (!netSinkPath.isEmpty()) {
            String refusal = WireRecordSink.refuseSinkCapOrNull(netSinkMaxMiB);
            if (refusal != null) {
                throw new IllegalArgumentException(refusal);
            }
            int hz = SimTickRate.ticksPerSecond();
            int seatBound = WireRecordSink.recordModeSeatBound(hz);
            if (descriptorSeatCount > seatBound) {
                System.out.println("npeer-serve record_seat_advisory seats=" + descriptorSeatCount
                        + " bound=" + seatBound + " hz=" + hz + " (past the bound a worst-case disk stall"
                        + " overflows the record queue onto the sim thread = frame time, never a lost record;"
                        + " teardown's ccwr_fallback_drains counts it)");
            }
        }
        // The stable-roster bound must cover the FULL descriptor roster, not just
        // the seats — bastion:A:B fields AI hulls (allies + grendels) alive from deploy, and the
        // seat-count bound tripped roster-bound-exceeded the moment seat-1 flew in (alive=3 >
        // bound=2 on bastion:1:1). Seats are a floor of the hull total, never the total itself.
        int descriptorHullCount = 0;
        for (MatchDescriptor.RosterRow row : npeerDescriptor.roster()) {
            descriptorHullCount += row.count();
        }
        // The shape-keyed wave schedule — null for every non-wave shape (no
        // schedule, no trigger, no bound change: those serves stay byte-identical). Scheduled
        // wave hulls are NOT descriptor rows, so the stable-roster bound below must learn the
        // schedule total too (the full-roster lesson above, one tier up).
        // match.fortressDp > 0 compiles the DP-SCALED fortress chain (the
        // hooks-injected engine fold is the DP truth); 0/absent = AUTO, byte-identical.
        fortressDpTotal = resolveFortressDpTotal();
        // The ONE match.waveRosterFullDp resolve (AUTO + knob throws in
        // FortressServeBootChecks.runIfFortress, the boot pre-flight).
        waveRosterFullDp = PersistentPilotIntentCombatServer.resolveWaveRosterFullDp();
        // The ONE match.waveRosterMultiplier resolve (a
        // set multiplier with the roster sizing off throws in the same boot pre-flight).
        waveRosterMultiplier = PersistentPilotIntentCombatServer.resolveWaveRosterMultiplier();
        // The per-wave scale row rides the same mint (fortress-only, SCALED-only —
        // the WaveSchedule mint throws on AUTO + scale, so a set row is never silently inert).
        fortressWaveScale = resolveFortressWaveScale();
        // The wave-category MODIFIER on the fortress chain (blank = remnant).
        String waveCategory = resolveWaveCategory();
        // The record header's match.pvp /
        // wave.pausedAtStart read the served shape (the sink opens after this resolve).
        MatchRecordMeta.bindShape(npeerDescriptor.shape());
        waveSchedule = WaveSchedule.forShape(
                npeerDescriptor.shape(), (int) Math.round(1.0 / config.fixedDt()),
                fortressDpTotal, fortressWaveScale, hooks.fortressDp(), waveCategory);
        if (waveSchedule != null) {
            // A PvP serve boots with the waves paused (the
            // players fight each other first); an admin's first pause toggle starts them, and
            // the pause outlives every round wrap. Every other shape boots unpaused, unchanged.
            waveSchedule.setPaused(WaveSchedule.startsPaused(npeerDescriptor.shape()));
            // The fly-in spread rides the SERVE schedule only (the category twins
            // below count hulls; the warm-up round measures per-hull cost — both unstaggered).
            waveSchedule.setFlyInStaggerSeconds(waveFlyInStaggerSec);
            System.out.println("npeer-serve wave_flyin_stagger sec=" + waveFlyInStaggerSec
                    + " (row 1 at the due tick, later rows spread over the window;"
                    + " 0 = off)");
        }
        // The category can now switch MID-SERVE (`/wave <type>` grafts the
        // unspawned tail immediately — WaveSchedule.retargetNextWave; the
        // round-boundary park is gone), so every roster bound minted below folds the HEAVIEST
        // category's chain, not the boot category's: the stable-roster read is a fail-closed
        // runaway THROW (serve-fatal), and a heavier faction switched in mid-match must never
        // be able to trip it. Non-fortress wave shapes ignore the category (forShape), so
        // their twin list is just the live schedule (byte-identical bound).
        List<WaveSchedule> categoryTwins = new ArrayList<>();
        if (waveSchedule != null) {
            categoryTwins.add(waveSchedule);
            if (MatchDescriptors.isFortressShape(npeerDescriptor.shape())) {
                for (String twinCategory : WaveSchedule.WAVE_CATEGORIES) {
                    if (!twinCategory.equals(waveSchedule.category())) {
                        categoryTwins.add(WaveSchedule.forShape(npeerDescriptor.shape(),
                                (int) Math.round(1.0 / config.fixedDt()), fortressDpTotal,
                                fortressWaveScale, hooks.fortressDp(), twinCategory));
                    }
                }
            }
        }
        int scheduleHullBound = 0;
        for (WaveSchedule twin : categoryTwins) {
            scheduleHullBound = Math.max(scheduleHullBound, twin.totalHullCount());
        }
        if (fortressDpTotal > 0.0d || fortressWaveScale != null
                || !"remnant".equals(waveCategory)
                || waveRosterMultiplier != cc.starsector.server.match.LiveRosterDp.MULTIPLIER_UNIT) {
            boolean fortressShape =
                    MatchDescriptors.isFortressShape(npeerDescriptor.shape());
            System.out.println(fortressShape
                    ? "npeer-serve fortress_dp mode=" + (fortressDpTotal > 0.0d
                                    ? "scaled" : "auto")
                            + " total=" + (int) fortressDpTotal
                            + " wave_scale=" + (fortressWaveScale == null ? "unit"
                                    : java.util.Arrays.toString(fortressWaveScale))
                            + " category=" + waveSchedule.category()
                            + " scheduled_hulls=" + waveSchedule.totalHullCount()
                            // Live waves are <= the graded static cap
                            + " roster_full_dp=" + (waveRosterFullDp > 0
                                    ? String.valueOf(waveRosterFullDp) : "off")
                            // The host's modifier on
                            // the live share (a set value on another shape reads IGNORED)
                            + " roster_multiplier=" + (waveRosterMultiplier
                                    == cc.starsector.server.match.LiveRosterDp.MULTIPLIER_UNIT
                                            ? "unit" : String.valueOf(waveRosterMultiplier))
                    : "npeer-serve fortress_dp IGNORED shape=" + npeerDescriptor.shape()
                            + " (match.fortressDp / match.fortressWaveScale /"
                            + " match.waveCategory / match.waveRosterMultiplier are"
                            + " fortress-only)");
        }
        // engine-born growth (bay fighters / system drones / engine-spawned
        // ships). STRICT bool (the waveClearRoundEnd idiom below); OFF = byte-identical serve —
        // no bound term, no seam install, no drain, no reclassifier (carriers still die on the
        // runaway throw exactly as today).
        String growthRaw = ServerConfig.resolve(
                "cc.server.engineBornGrowth", "CC_SERVER_ENGINE_BORN_GROWTH",
                "server.engineBornGrowth", "");
        engineBornGrowth = switch (growthRaw) {
            case "", "false" -> false;
            case "true" -> true;
            default -> throw new IllegalArgumentException(
                    "cc.server.engineBornGrowth must be 'true' or 'false'; got '"
                            + growthRaw + "'");
        };
        if (engineBornGrowth && hooks.growthSeam() == null) {
            throw new IllegalArgumentException("cc.server.engineBornGrowth=true but the caller"
                    + " bound no NPeerHooks.growthSeam hook (the waveSpawn"
                    + " nullity precedent)");
        }
        // The STAR FORTRESS serve: the module bound
        // term, a FOURTH stable-roster bound term beside descriptor/wave/growth. A station row
        // deploys ONE ledger hull but the engine mints its whole module cluster the same frame
        // (CFM auto-create + executeAdds), and those
        // modules are live repo Ships the alive-count bound sees. NO module allowance existed
        // before this term, so the fortress would trip
        // roster-bound-exceeded on its first published tick.
        //
        // Station-row detection is GAME-FREE here and mint-owned (MatchDescriptors authors the
        // station variant ids; the ENGINE-side detection seam is variant.isStation(), read at
        // the deploy lanes — RDR/HCC). The two agree by construction: the mint only ever
        // authors real station variant ids, and the deploy lanes fail closed if one is not.
        stationRowPresent = false;
        stationRowSide = -1;
        // The DEPLOYED station variant id (descriptor-owned — the family is a
        // descriptor-lane choice, never a knob). Threaded to every census consumer below so
        // the /module vocabulary matches the hull this serve actually fields.
        String stationVariantId = null;
        for (MatchDescriptor.RosterRow row : npeerDescriptor.roster()) {
            if (MatchDescriptors.isStationVariantId(row.variantId())) {
                stationRowPresent = true;
                stationVariantId = row.variantId();
                // The station's own side feeds the base-hulk winner
                // derivation (the opposed side wins) — RosterRow validated it to 0/1.
                stationRowSide = row.side();
                break;
            }
        }
        if (stationRowPresent && MatchDescriptors.isPvpShape(npeerDescriptor.shape())) {
            // A fail-closed backstop — the mint never
            // writes a station into PvP, so only a hand-edited file reaches here.
            throw new IllegalArgumentException("descriptor shape " + npeerDescriptor.shape()
                    + " fields a STATION roster row - PvP fields no station (re-mint the match"
                    + " file, or pick the fortress shape for a station)");
        }
        // The census id the arbiter + module lanes key on: the deployed station when one is
        // fielded, else the DEFAULT family (a station-less serve arms no module verbs, so its
        // census vocabulary is inert — this keeps the always-minted arbiter shape).
        censusVariantId = stationVariantId != null
                ? stationVariantId : MatchDescriptors.BASTION_STATION_VARIANT_ID;
        if (stationRowPresent && !engineBornGrowth) {
            // Merlons are ENGINE-born (the station's own DRONE_LAUNCHER modules mint drones the
            // deploy lanes never see): growth OFF would publish hulls clients never mint AND
            // leave the growth headroom out of the bound — a runaway throw mid-round.
            throw new IllegalArgumentException("descriptor shape " + npeerDescriptor.shape()
                    + " fields a STATION roster row but cc.server.engineBornGrowth is false —"
                    + " the station's module-hosted drones are engine-born and MUST ride the"
                    + " growth seam");
        }
        if (stationRowPresent && hooks.station() == null) {
            throw new IllegalArgumentException("descriptor shape " + npeerDescriptor.shape()
                    + " fields a STATION roster row but the caller bound no NPeerHooks.station"
                    + " hook (the waveSpawn nullity precedent)");
        }
        // Every module variant the round can field, flattened one entry per minted module:
        // its SIZE is the module bound term, and its CONTENTS ride the growth fold below
        // (module-hosted DRONE_LAUNCHER/wing capacity is growth headroom of its own).
        //
        // The fold is ENGINE-keyed, never allowlist-keyed — EVERY
        // roster row + EVERY scheduled variant of EVERY category twin rides
        // hooks.station().moduleVariantIds (= EngineBornGrowthSeam.stationModuleVariantIds:
        // EMPTY for a non-station, THROWS for a station with no cluster), independent of
        // stationRowPresent: the Domain-era Survey Mothership (hull hint STATION, six
        // STATION_MODULE children) is NOT a MatchDescriptors fortress id, and the adhoc
        // /spawnfoe lane can field ANY debug-catalogue variant — its worst case
        // (AdhocFoeSpawner.MAX_PENDING roots × the catalogue's LARGEST module cluster) is
        // budgeted here too, else the first `/spawnfoe station_derelict_survey_mothership_
        // Standard` trips `stable-roster live size out of bounds` the tick it spawns.
        // The debug catalogue is snapshotted ONCE here (registry keyset; quiescent by now) and
        // reused at door wiring below — a catalogue id whose cluster cannot be budgeted is
        // EXCLUDED from the /spawnfoe allowlist (fail-closed: unbudgetable ⇒ unspawnable),
        // never a boot refusal over a hull nobody asked for.
        debugCatalogueSnapshot = null;
        if (hooks.debugVariantIds() != null && hooks.waveSpawn() != null) {
            debugCatalogueSnapshot = hooks.debugVariantIds().get();
        }
        StationModuleBudget stationBudget = foldStationModuleBudget(
                npeerDescriptor.roster(), categoryTwins, debugCatalogueSnapshot, hooks.station());
        List<String> stationModuleVariantIds = stationBudget.moduleVariantIds();
        int moduleHullBound = stationBudget.boundTerm();
        if (debugCatalogueSnapshot != null) {
            debugCatalogueSnapshot = stationBudget.adhocCatalogue();
        }
        if (stationRowPresent || moduleHullBound > 0) {
            System.out.println("npeer-serve station_module_bound"
                    + " shape=" + npeerDescriptor.shape()
                    + " modules=" + stationModuleVariantIds.size()
                    + " adhoc_roots=" + stationBudget.adhocRootTerm()
                    + " adhoc_largest_modules=" + stationBudget.adhocLargestModules()
                    + " adhoc_largest_variant=" + stationBudget.adhocLargestVariantId()
                    + " adhoc_unbudgetable=" + stationBudget.adhocUnbudgetable().size()
                    + " bound_term=" + moduleHullBound
                    + " (engine-keyed fold, adhoc worst case included)");
        }
        // Growth bound term: capacityFor fold over EVERY hull the round can field
        // (descriptor roster rows — seat and AI alike — plus the wave schedule), boundTerm =
        // 2×Σ wing fighters + Σ max drones (peak alive exceeds Σ numFighters: returning
        // fighters overlap replacements, extra-deployments systems raise the per-bay gate —
        // err on headroom; the runaway throw stays the backstop, C9 posture). A capacityFor
        // THROW is fatal HERE, at serve entry — the bound must never silently under-count.
        int growthHullBound = 0;
        if (engineBornGrowth) {
            int growthFighters = 0;
            int growthDrones = 0;
            List<String> growthVariantIds = new ArrayList<>();
            for (MatchDescriptor.RosterRow row : npeerDescriptor.roster()) {
                for (int i = 0; i < row.count(); i++) {
                    growthVariantIds.add(row.variantId());
                }
            }
            if (!categoryTwins.isEmpty()) {
                // Fold the category twin with the HEAVIEST growth term (the
                // chain `/wave <type>` may switch to mid-match) — never the boot chain alone.
                List<String> heaviestIds = List.of();
                int heaviestTerm = -1;
                for (WaveSchedule twin : categoryTwins) {
                    int term = 0;
                    for (String twinVariantId : twin.variantIds()) {
                        term += hooks.growthSeam().capacityFor(twinVariantId).boundTerm();
                    }
                    if (term > heaviestTerm) {
                        heaviestTerm = term;
                        heaviestIds = twin.variantIds();
                    }
                }
                growthVariantIds.addAll(heaviestIds);
            }
            // Each station's MODULE variants fold in too — a module is a
            // hull in its own right, and a module-hosted DRONE_LAUNCHER/wing mints engine-born
            // hulls the growth term must cover (the merlon-drone headroom, d4).
            growthVariantIds.addAll(stationModuleVariantIds);
            for (String growthVariantId : growthVariantIds) {
                NPeerHooks.GrowthCapacity capacity =
                        hooks.growthSeam().capacityFor(growthVariantId);
                growthFighters += capacity.fighters();
                growthDrones += capacity.drones();
                growthHullBound += capacity.boundTerm();
            }
            System.out.println("npeer-serve growth_bound"
                    + " formula=2*sum_wing_fighters+sum_max_drones"
                    + " hull_rows=" + growthVariantIds.size()
                    + " fighters=" + growthFighters
                    + " drones=" + growthDrones
                    + " bound_term=" + growthHullBound);
        }
        // rig-only wave-probe rows (env knob, default OFF). Spawns ride the
        // EXACT waveSpawn lane at (c2b) below — same hook, same intern, same RAPP fan — so a
        // probe hull is byte-for-byte a wave hull; only the trigger differs (env tick, not a
        // schedule: the fortress rig shape is schedule-LESS by design). Fail-fast on a probe
        // with no hook: a silently-inert probe would read as a clean no-spawn and mis-pin.
        // Parsed HERE so the probe hulls fold into rosterHullBound (a probe spawn must never
        // trip the stable-roster bound it is diagnosing behind).
        waveProbeRows = parseWaveProbeRows(System.getenv("CC_RIG_WAVE_PROBE"),
                waveCategory);
        if (waveProbeRows != null) {
            if (hooks.waveSpawn() == null) {
                throw new IllegalArgumentException("CC_RIG_WAVE_PROBE set but the caller bound"
                        + " no NPeerHooks.waveSpawn hook — the probe needs the real wave lane");
            }
            StringBuilder probeSpec = new StringBuilder();
            for (WaveProbeRow probeRow : waveProbeRows) {
                if (probeSpec.length() > 0) {
                    probeSpec.append(',');
                }
                probeSpec.append(probeRow.variantId).append('@').append(probeRow.dueTick);
            }
            System.out.println("npeer-serve wave_probe armed rows=" + waveProbeRows.size()
                    + " spec=" + probeSpec + " (side-1 wave-lane probe)");
        }
        rosterHullBound = descriptorHullCount
                + scheduleHullBound // The heaviest switchable category's hulls
                + (waveProbeRows == null ? 0 : waveProbeRows.size())
                + growthHullBound
                + moduleHullBound;
        // Bastion's default is ONE
        // CONTINUOUS fight broken by waves — default OFF: a wave clear spawns the next wave
        // (the schedule's spawn-on-clear feed always rides the trigger's read now) and the
        // round only ends externally (operator verb / frame budget); no matchOver ⇒ no round
        // wrap ⇒ no gated reset, so seats keep hull/flux (survival). 'true' = the per-clear
        // RESET option (the legacy round machinery: matchOver when the wave side's live hulls
        // hit ZERO with the schedule drained + arming seen — the rig's proof lane). STRICT
        // boolean (the mintDoorFingerprint house rule); consulted only when a schedule
        // exists; the perSideRoundEnd×respawn boot-throw above is deliberately untouched
        // (the latch is legal WITH respawn).
        String waveClearRaw = ServerConfig.resolve(
                "cc.server.waveClearRoundEnd", "CC_SERVER_WAVE_CLEAR_ROUND_END",
                "server.waveClearRoundEnd", "");
        boolean waveClearRoundEnd = switch (waveClearRaw) {
            case "", "false" -> false;
            case "true" -> true;
            default -> throw new IllegalArgumentException(
                    "cc.server.waveClearRoundEnd must be 'true' or 'false'; got '"
                            + waveClearRaw + "'");
        };
        waveClearRoundEndKnob = waveClearRoundEnd; // The live /endless on gate
        // PvP has no win, so a per-clear round end there
        // is a contradiction (the endless-knob idiom below: two orders that cannot both hold
        // refuse the boot).
        boolean pvp = MatchDescriptors.isPvpShape(npeerDescriptor.shape());
        if (pvp && waveClearRoundEnd) {
            throw new IllegalArgumentException("cc.server.waveClearRoundEnd=true contradicts the"
                    + " PvP shape " + npeerDescriptor.shape() + " (PvP has no win: a cleared wave"
                    + " side never ends its round) - drop the knob or pick another shape");
        }
        // The fortress's commissioned WIN. Its shape has a beatable end (schedule
        // drained + wave side cleared = the DEFEND victory), so the latch is shape-LAW there,
        // ORed with the knob — a run that also set cc.server.waveClearRoundEnd changes nothing.
        // PvP never latches (the predicate is false there and the knob refused above).
        // The tag keeps the victory dump greppable apart from the base-hulk defeat dump.
        waveClearLatchRoundEnd = !pvp && (waveClearRoundEnd
                || WaveSchedule.latchesRoundEndOnWaveClear(npeerDescriptor.shape()));
        waveClearLatchTag =
                WaveSchedule.latchesRoundEndOnWaveClear(npeerDescriptor.shape())
                        ? "match-over-fortress-cleared" : "match-over-wave-clear";
        // ENDLESS MODE: resolved ONCE here (a bad value throws at serve
        // entry). Default ON. Collision law with the explicit per-clear RESET
        // lane (cc.server.waveClearRoundEnd=true — the rig proof lane, run-wave-rig.ps1):
        // an EXPLICIT endless=true beside it THROWS (two contradictory orders), the DEFAULT
        // yields to it with a loud row (the rig's opt-in is the more specific intent).
        // A LOCAL, not a field — the value is consumed here (the
        // schedule's setEndless + the row below); nothing read it after resolve().
        boolean matchEndless;
        Boolean endlessKnob = resolveMatchEndlessOrNull();
        String endlessSource;
        if (endlessKnob == null) {
            matchEndless = MATCH_ENDLESS_DEFAULT && !waveClearRoundEnd;
            endlessSource = waveClearRoundEnd
                    ? "default-yields-to-waveClearRoundEnd" : "default";
        } else {
            if (endlessKnob && waveClearRoundEnd) {
                throw new IllegalArgumentException("cc.match.endless=true contradicts"
                        + " cc.server.waveClearRoundEnd=true (an endless chain never latches"
                        + " the per-clear reset) — drop one of the two knobs");
            }
            matchEndless = endlessKnob;
            endlessSource = "knob";
        }
        if (waveSchedule != null) {
            if (hooks.waveSpawn() == null) {
                throw new IllegalArgumentException("descriptor shape " + npeerDescriptor.shape()
                        + " has a wave schedule but the caller bound no NPeerHooks.waveSpawn hook");
            }
            waveSchedule.setEndless(matchEndless);
            System.out.println("npeer-serve wave_schedule shape=" + npeerDescriptor.shape()
                    + " waves=" + waveSchedule.waveCount()
                    + " hulls=" + waveSchedule.totalHullCount()
                    + " wave_side=" + waveSchedule.waveSide()
                    + " clear_round_end=" + waveClearLatchRoundEnd
                    + " latch_tag=" + waveClearLatchTag
                    + " endless=" + matchEndless + " endless_source=" + endlessSource
                    + " pvp=" + pvp + " paused_at_boot=" + waveSchedule.paused()
                    + (matchEndless
                            ? " (F1: the chain wraps after its last clear; the latch's"
                                    + " schedule gate never opens — round ends by base loss,"
                                    + " operator verb or frame budget)"
                            : ""));
        }
        // The finale creep knob, resolved ONCE at serve entry (a bad
        // value throws HERE, never mid-round); the author only ever arms on a scheduled or
        // probe finale row (the derelict mothership), so a non-derelict serve carries it inert.
        finaleCreepSu = resolveFinaleCreepSu();
        System.out.println("npeer-serve finale_creep knob=" + finaleCreepSu
                + " hook=" + (hooks.finaleCreep() != null ? "bound" : "none")
                + " (derelict finale mothership creep su/s; 0 = vanilla drift)");
        // wireId→seatId off the minted tokens (game-free): the T3 seat-loss filter + fly-in keys.
        // The inverse feeds the W2b E4 module drains (mount/dismount key on the seat's FIXED
        // wire id with no session or fly-in result in hand).
        seatIdByWireId = new HashMap<>();
        seatWireIdBySeatId = new HashMap<>();
        for (String token : authority.mintedTokens()) {
            seatIdByWireId.put(authority.authorizedShipId(token), authority.authorizedSeatId(token));
            seatWireIdBySeatId.put(
                    authority.authorizedSeatId(token), authority.authorizedShipId(token));
        }
        // The admin /godmode + /teleport effect lane (sim thread only) — lives for
        // the whole serve so a god-mode latch survives rounds like any debug toggle would.
        adminDebug = new AdminSeatDebugLane(seatWireIdBySeatId,
                RealEngineDemoRoster::liveShipForWireIdOrNull,
                new cc.starsector.server.bridge.AdminSeatDebugEnginePort());
        // The terrain stat seam's knob-change hook (one pass over the live
        // ships on /terrain or /knob terrain*, serve thread, pre-advance; deploy-time installs
        // ride the CrDeploySeam sites).
        AdminTerrainVerb.reapplyHook = cc.starsector.server.bridge.TerrainStatSeam::reapplyAll;
        // The admin /switchteams effect lane (sim thread only; the next-fly-in side
        // per seat + the live flip through the engine port).
        seatTeams = new SeatTeamLane(seatWireIdBySeatId,
                RealEngineDemoRoster::liveShipForWireIdOrNull,
                RealEngineDemoRoster::seatSideOrNeg,
                new cc.starsector.server.bridge.SeatTeamEnginePort());
        // The D8 rule at N: the gated samplers measure the MIN seat
        // wire id, descriptor-driven — the retired serve() lane's minSeatWireId twin (1L on the
        // certified 1v1).
        measureOwnedShipId = Long.MAX_VALUE;
        for (Long wireId : seatIdByWireId.keySet()) {
            if (wireId < measureOwnedShipId) {
                measureOwnedShipId = wireId;
            }
        }
        // The per-round registry's reserved-band ceiling, authority-derived — identical to
        // the real path's descriptor-band ceiling by construction (N REQUIRES the descriptor, and
        // v1 pins seat wire ids to their seatId-order deploy positions).
        seatBandCeiling = 0L;
        for (Long wireId : seatIdByWireId.keySet()) {
            seatBandCeiling = Math.max(seatBandCeiling, wireId);
        }
        bindHost = ServerConfig.resolve(
                "cc.server.bindHost", "CC_SERVER_BIND_HOST", "server.bindHost", "");
        bindPort = ServerConfig.resolveInt(
                "cc.server.bindPort", "CC_SERVER_BIND_PORT", "server.bindPort", 0);

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        baseStep = new EngineBackedCombatStepBoundary(world, binding);
        // The calling thread IS the sim thread; the boundary publishes its
        // live step-start stamp; the daemon walks main's stack past the threshold, off-frame.
        stallStacks = SimStallStackSampler.startForServe(
                Thread.currentThread(), baseStep::liveStepStartNanos, stallStackMs);

        rounds = new ArrayList<>();
        resetFramesEmitted = 0;
        lastRoundMatchOver = false;
        clientConnected = false;
        heldLaneFallback = false;
        // Class-routing session fallback latch, the heldLaneFallback routed twin: once a
        // zero-decl session rode held past the window, later rounds boot with routing off too.
        classRoutingFallback = false;
        // D6 O-0 end-match latch (serve-scoped): set by the operator verb at (a2), consumed at
        // (m) — breaks BOTH loops WITHOUT matchOver so the round wrap's reset branch is skipped
        // and the serve falls straight to the fanGoodbye (bounded flush + BYE → exit).
        operatorEndMatch = false;
        // The last round's latched verdict, captured at round-end
        // bookkeeping (source and lastSimTick are round-scoped; the MOVR fan site after the
        // round loop is not). Tag null at the fan = no latch fired — operator end fans code 6,
        // a frame-budget end fans nothing (no outcome exists).
        lastMatchOverTag = null;
        lastMatchOverWinner = -1;
        lastMatchOverTick = -1L;
        lastRoundOperatorEnd = false;
        lastServedSimTick = -1L;
        sessionDeclReceived = 0L;
        // Hoisted SERVE-level (NOT per-round) so the per-tick baseline burst fires on
        // newly-live sessions only; the round-wrap burst covers boundaries (no duplicate
        // re-baseline of every session at each round's frame 0).
        prevLive = new HashMap<>();
        // The per-tick live-by-id map is OWNED and swapped with
        // prevLive at the end of the session observe — zero per-tick allocation at 32+ seats.
        liveByIdScratch = new HashMap<>();
        // W7 B1b: the last RAPP
        // ROW announced per seat (+ its descriptor id), serve-lifetime — never dropped on
        // vacate/BYE/wrap; absent = no window was ever told anything but the descriptor.
        // Survivors hold the announced row through the live fan; joiners get it through the
        // round-open seed (mintRound: the carried row, or the tracker row when ≠ descriptor).
        // A repointed-then-reset seat re-announces its descriptor variant once (believed ≠ D).
        lastAnnouncedVariantBySeat = new HashMap<>();
        wireSink = null;
        gpeeLog = null; // null = knob blank/off
        // (field) host;
        // (field) port;
        // (field) offLoopback;
        downComposer = null;
        armorCellBook = null;
        // W3 L1b-ii twin-gate split of the W2b E3 arming: the rebind arbiter
        // mints on EVERY serve — the W3 transfer verbs and the always-on vacate drain need it
        // fortress or not — while MODULE verb arming stays the fortress condition, threaded to
        // the door as its own boolean (ghost-module law preserved: dismount deposits need
        // mounts, which need moduleVerbsArmed; the vacate drain itself always runs).
        rebindArbiter = new SeatRebindArbiter(
                MatchDescriptors.moduleSeatTokens(censusVariantId), transferPolicy);
        // Seats whose respawn fly-in is DEFERRED behind an
        // in-flight transfer out of their wreck — one loud line per deferral episode.
        flyInDeferredForTransfer = new HashSet<Integer>();
        // The pending adhoc /spawnfoe + operator spawn-foe queue — sim-thread-only
        // (the operator switch and the (c2c) drain share the serve thread); serve-lifetime so
        // its counters span rounds. Chat deposits ride the AdminWaveQueue onto this thread.
        adhocFoes = new AdhocFoeSpawner();
        moduleVerbsArmed = stationRowPresent && hooks.moduleMount() != null;
    }
}
