package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;
import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.adapter.CombatWorldState;
import cc.starsector.server.adapter.WeaponMountView;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.PilotApplyState;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.bridge.TransferPodSpawner;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchGracePolicy;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.match.MatchRespawnPolicy;
import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.match.PasswordSeatLedger;
import cc.starsector.server.match.OfficerRosterCodec;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.platform.OfficerAttach;
import cc.starsector.server.platform.WaveShipNamer;
import cc.starsector.server.ops.OpsStatusFile;
import cc.starsector.server.ops.PhaseWindowStats;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.EntityDespawnReason;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;
import cc.starsector.server.protocol.SeatStateCause;
import cc.starsector.server.protocol.SeatStateEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import static cc.starsector.server.runtime.PersistentPilotIntentCombatServer.*;

/**
 * P23 (rides under R6): the N-peer serve as a METHOD
 * OBJECT. {@link PersistentPilotIntentCombatServer#serveNPeer} is still the ONE live entry point;
 * it mints one session per serve and calls {@link #run()}. Leg 1 = the skeleton: the former
 * static-method body moved here VERBATIM (same statements, same order, same rows — the
 * static helpers it calls stay on the server class via the static import). Later legs carve
 * {@link #run()} into one method per phase (prelude knob resolve, door open, per-round mint,
 * the frame loop, round close, serve-end fans, teardown), promoting the locals a phase shares
 * with a later one to fields. Never a behaviour change on this path (never-conceal-desync law):
 * proof = the contract suite + the lash rig rows (`run-cat-serve.ps1 -LashProof`). Public for the
 * static team seams the test package's rows call ({@link #adminThenTeamPicks}, {@link #flyInHolderObserved},
 * {@link #observedHolder}, {@link #wrapTeamSides}); the rest stays package-private.
 */
public final class PilotServeSession {

    private final PilotServeConfig config;
    private final EngineCombatBinding binding;
    private final NPeerHooks hooks;
    private final ListenerReady onReady;
    private final PickableVariants pickableOrNull;
    private final VariantCatalogStore variantCatalogOrNull;
    // -- phase-shared state: prelude locals promoted to fields (leg 1(c)); one phase per method --
    private Map<Integer, String> opsSeatShips;
    private PhaseWindowStats phaseWindowStats;
    private boolean lastRoundMatchOver;
    private String lastMatchOverTag;
    private int lastMatchOverWinner;
    private long lastMatchOverTick;
    private boolean lastRoundOperatorEnd;
    private long lastServedSimTick;
    private int resetFramesEmitted;
    private ServeBlockRecorder blockRecorder;
    private SimStallStackSampler stallStacks;
    private DecoupledStdoutLane stdoutLane;
    private boolean engineBornGrowth;
    private long transferPodTimeoutTicks;
    private WorstFramesLedger worstFrames;
    private int rosterHullBound;
    private MatchRespawnPolicy respawnPolicy;
    private int hulkPoseEveryTicks;
    private int hulkPoseBurstCadenceTicks;
    private int asteroidBumpCadenceTicks; // The asteroid lane's own cadence knob
    private boolean hulkCutGeometryOn;
    private boolean waveClearLatchRoundEnd;
    private String waveClearLatchTag;
    // The per-round cycle the drain consumer last logged (the
    // wave_cycle_wrap row's edge detector). Endless mode itself
    // is a resolve() LOCAL now — WaveSchedule owns the state, nothing read the mirror field
    // after resolve() (the /endless verb reads waveSchedule.endless() inside AdminWaveVerbs).
    /** The resolved {@code cc.server.waveClearRoundEnd} knob, kept so the
     * live {@code /endless on} verb can refuse beside the per-clear reset lane exactly as the
     * knob pair throws at boot (a field; was a mint-time local). */
    private boolean waveClearRoundEndKnob;
    private int waveCycleLast;
    private boolean stationRowPresent;
    private int stationRowSide;
    private float mapHalf;
    private String peerGrade;
    private boolean roundStartGate;
    private boolean operatorEndMatch;
    private boolean overrunLog;
    private AdminSeatDebugLane adminDebug;
    private SeatTeamLane seatTeams;
    /** The served shape is PvP: the wrap keeps the seat team sides and the reset redeploys on them. */
    private boolean pvpTeams;
    /** "The serve observed this session live at its last session diff" (the team-pick drain's gate): the
     *  poll runs after this frame's diff, so it is THIS frame's live set. */
    private final java.util.function.LongPredicate observedLiveSession =
            id -> this.prevLive != null && this.prevLive.containsKey(id);
    private double fortressDpTotal;
    /** {@code match.waveRosterFullDp} (0 = off); the live-roster fold
     *  installed per round in {@link #mintRound} and the last stage receipt already logged. */
    private int waveRosterFullDp;
    /** {@code match.waveRosterMultiplier} (1.0 = unset),
     *  the host's scaling modifier the per-round {@link #liveRosterDp} multiplies its share by. */
    private double waveRosterMultiplier;
    private cc.starsector.server.match.LiveRosterDp liveRosterDp;
    private WaveSchedule.StageReceipt lastStageReceiptLogged;
    private double[] fortressWaveScale;
    private MatchDescriptor npeerDescriptor;
    private AdhocFoeSpawner adhocFoes;
    private Map<Long, PilotIntentDatagramServer.PeerSession> liveByIdScratch;
    private Map<Long, PilotIntentDatagramServer.PeerSession> prevLive;
    private boolean socketLiveness;
    private Set<Integer> flyInDeferredForTransfer;
    /** The due seats whose skip by {@link #flyInHolderObserved} was already logged (one line per wait). */
    private final Set<Integer> flyInUnobservedHolder = new HashSet<Integer>();
    private int waveSpawnPerTick;
    private float finaleCreepSu;
    private List<WaveProbeRow> waveProbeRows;
    private int parityWalkEvery;
    private NearContactShieldLog nearContactLog;
    private boolean sampleBoost;
    private long measureOwnedShipId;
    private boolean sampleSystem;
    private FoeAutofireGateSampler afGateSampler;
    private boolean eventLaneOn;
    private Map<Long, Integer> seatIdByWireId;
    /**
     * Each seat's latest admitted HELLO flag bits (seat 1..255 -> bits; 0x08 = its game reports its MagicLib states),
     * written once per NEW session at admission and read by the pilot mark's flags resolver at each hull re-point —
     * the per-seat record beside the seat resolver, so a re-joined seat is judged on its NEW game. Session-lived (it
     * survives a round re-mint, like the seats).
     */
    private final Map<Integer, Integer> helloMagicBitsBySeat = new java.util.concurrent.ConcurrentHashMap<>();
    private int downEveryTicks;
    private long sessionDeclReceived;
    private String bindHost;
    private int bindPort;
    private MatchSeatAuthority authority;
    private int descriptorSeatCount;
    private String doorFingerprint;
    private String matchPassword;
    private PasswordSeatLedger passwordLedger;
    private SeatRebindArbiter rebindArbiter;
    private boolean moduleVerbsArmed;
    private TransferTargets transferTargets;
    private String adminPassword;
    private List<String> debugCatalogueSnapshot;
    /** The /spawnfoe|/spawnfriend catalogue ids the officer table warms at serve boot. */
    private List<String> adhocOfficerCatalogueIds;
    private String host;
    private int port;
    private boolean offLoopback;
    private String netSinkPath;
    private WireRecordSink wireSink;
    /** The door {@code attachSink} fed ({@link #teardown} has none in scope);
     *  its pinned drops over the WHOLE serve print as {@code door_pinned_drops=} — never recorded, so none is in the reel. */
    private PilotIntentDatagramServer sinkFeedDoor;
    /** The applied-input record ({@link MatchRecordRows}). ARMED only
     *  once the wire sink attached (an unrecorded serve encodes nothing: frame-loop residency is earned);
     *  the TAKES builder is a field so no handle is first resolved on a frame; {@link #rowSink} hands the
     *  lane-held rows (VSUB) to the same door; a recorder fault is counted + logged once, never the
     *  match's end; the ROSTER pair keeps that row change-only within a round. */
    private boolean recordRowsArmed;
    private final MatchRecordRows.TakesBuilder takes = new MatchRecordRows.TakesBuilder();
    private final MatchRecordRows.RowSink rowSink = (kind, payload, tick) -> {
        PilotIntentDatagramServer d = sinkFeedDoor;
        if (d != null) {
            d.recordRow(kind, payload, tick);
        }
    };
    private long recordRowFaults;
    private long lastRosterBits;
    private boolean rosterRowPending;
    /** The re-simulation's input source (null = a live serve), the
     *  replay door's session verbs as the feed calls them (typed as the adapter: {@link #teardown} reads the
     *  door's swallowed-send count through it, with no door in scope), and the per-frame live-seat scratch
     *  the take band checks recorded seats against (seats are u8). */
    private ReplayFeed replayFeed;
    private ReplayDoorAdapter replaySessionDoor;
    private final boolean[] replayLiveSeats = new boolean[256];
    /** The first record-tick mismatch printed (the rest count only). */
    private boolean recordTickMismatchLogged;
    /** The wave inflater knobs, resolved ONCE before admission arms
     *  (a blank seed draws a random one — never resolve twice) and read by the inflater install. */
    private WaveLoadoutInflater.Knobs waveKnobs;
    /** Fold: the last round the serve loop entered (-1 = none) — the END trailer's
     *  {@code last.round} key, so the re-sim knows how many rounds the reel's rows span. */
    private int lastRoundServed = -1;
    private int netSinkMaxMiB;
    private boolean ioDecoupled;
    private String gpeeLogPath;
    /** {@code cc.server.shieldedRow} (INSTRUMENT, default off) — arms the hull-source
     *  ledger's shield window at every probe mint ({@code type=shielded} rows; the specimen arena's decoy law). */
    private boolean shieldedRow;
    private boolean hitCellRow; // cc.server.hitCellRow (INSTRUMENT, default off) — arms the hit-cell tap per mint
    /** W15 v0: the {@code custom.*} family + the installed lane (null = OFF). */
    private CustomVariantConfig customConfig;
    private CustomVariantLane customLaneOrNull;
    /** The wave loadout inflater lane (null = off / game-free / no schedule). */
    private WaveLoadoutInflater waveInflaterOrNull;
    /** The admin wave/spawn verb lane, carved out of
     * {@link #handleOperatorCommand}; minted in {@link #mintMatchScope()}, and the owner of the
     * skip verb's deferred corpse-sweep slots ({@code waveVerbs.sweep}). */
    private AdminWaveVerbs waveVerbs;
    private GameplayEdgeEventWatchSink gpeeLog;
    private ArmorCellBook armorCellBook;
    private DownEmissionComposer downComposer;
    private String respawnRaw;
    private OperatorCommandFile operatorFile;
    /** The P27 ban-operator block (bans file + its off-frame sink + the ban verbs), carved VERBATIM
     *  out of this class — serve() boots it, the command belt drives it, teardown closes it. */
    private final BanOperatorLane banLane = new BanOperatorLane();
    /** The host's ship list (hidden-ships file + its off-frame writer +
     *  the six ship verbs) — serve() boots and applies it, the command belt drives it, teardown closes it. */
    private final HiddenShipsLane hiddenShips = new HiddenShipsLane();
    private OpsStatusFile opsFile;
    private List<PilotRoundSummary> rounds;
    private boolean clientConnected;
    private boolean autoSpeedKnob;
    private float autoSpeedFloor;
    private float autoSpeedCeiling;
    private EngineBackedCombatStepBoundary baseStep;
    private boolean fireEdgeLatch;
    private boolean fireLaneDeclared;
    private boolean heldLaneFallback;
    private boolean fireClassRoutingOn;
    private boolean classRoutingFallback;
    private double fireClassRateMax;
    private Map<String, FireClassLaw.FireClass> fireClassOverrides;
    private boolean onFireTruth;
    private boolean canisterProbeOn;
    private int aimFanEveryTicks;
    private long seatBandCeiling;
    private Map<Integer, Long> seatWireIdBySeatId;
    private WaveSchedule waveSchedule;
    private Map<Integer, SeatAnnounceTracker.SeatAnnounce> lastAnnouncedVariantBySeat;
    private String censusVariantId;
    private MatchGracePolicy gracePolicy;
    private DoorSourceHold.Policy chatSourceHold; // Handed to the door via PidsOpenParams
    private int maxSeatsPerSource; // Handed to the door via PidsOpenParams (0 = off)
    private List<java.net.InetAddress> sourceCapExempt; // The configured exempt addresses beside the cap
    // -- leg 3: door-wiring + match-scope locals promoted to fields (announceAndAwait / mintMatchScope) --
    private VoicePositionSlot voiceSlot;
    private VoiceRelay voice;
    private LobbyOperatorGate lobbyGate;
    private List<NPeerHooks.FlyInResult> pendingRoundPreInterns;
    private List<RosterAppendFrameCodec.AppendRow> pendingWrapRepoints; // stashed at the wrap, fanned after the MRST
    private ContentIdRegistry contentRegistry;
    private int effectiveTickRate;
    private int fallbackHeldTicks;
    private double simSpeed;
    private boolean autoSpeedArmed;
    private boolean autoSpeedDormantLogged;
    private boolean autoSnapPending;
    private SimSpeedGovernorLaw autoSpeedLaw;
    private ServeStdoutIoMeter ioMeter;
    private MatchLingerLaw lingerLaw;
    // -- leg 4: per-round mint locals promoted to fields (mintRound; re-assigned every round) --
    private PilotIntentStep pilotStep;
    private boolean declaredLaneActive;
    private boolean classRoutingActive;
    private GameplayEdgeEventDetector fireLaneDischargeDetector;
    private OnFireShadowComparator onFireShadow;
    private OnFireTruthMerger onFireTruthMerger;
    private CanisterFlakServeProbe canisterServeProbe;
    private PhaseDiveServeProbe phaseDiveProbe;
    private ArcHitServeProbe arcHitProbe;
    private CritMalfunctionServeProbe critProbe; //
    private AsteroidFieldServeLane asteroidLane; //
    private long asteroidSeed; //
    private int asteroidRefreshTicks; //
    private EntityDespawnEventDetector despawnDetector;
    private Map<Long, Long> departedShipDespawnTickById;
    private DespawnReasonEvidence despawnReasonEvidence;
    private MissileSplitEventDetector missileSplitDetector;
    private AutofireTargetLane afTargetLane;
    private PaladinDetLane paladinDetLane;
    private PilotAimFanLane aimFanLane;
    private int gpeeDownEvents;
    private int edspDownEvents;
    private int seatDownFrames;
    private int hkfrDownEvents;
    private int gpeeMissileEndIntact;
    private int gpeeMissileEndDestroyed;
    private int gpeeMissileEndFizzling;
    private int gpeeMissileEndShield;   // INTACT ends refined SHIELD_ABSORBED
    private int gpeeMissileEndNamed;    // Ends naming a victim ship (any cause)
    private int hkpsDownEvents;
    private int hkcgDownEvents;
    private int hkcgSkipEvents;
    private Set<String> hulkPoseFamiliesLogged;
    private Set<String> hulkCutGeometryCutsLogged;
    private int heldStreakTicks;
    private ShipNetworkIdRegistry stableIdRegistry;
    private List<byte[]> roundRosterAppendFrames;
    /** The per-session paced catch-up cursors over
     *  {@link #roundRosterAppendFrames} + the port over the tick's live map. */
    private RosterAppendCatchup<PilotIntentDatagramServer.PeerSession> rappCatchup;
    private final RappCatchupPort rappCatchupPort = new RappCatchupPort();
    private ModuleSeatServeLane moduleLane;
    private SeatGraceLane graceLane;
    private GrowthDespawnReclassifier growthReclassifier;
    private Set<String> growthKnownKeys;
    private Set<String> growthBornKeys;
    private TransferPodLane podLane;
    private CommandTransferServeLane transferLane;
    private VoiceAnchorLane voiceLane;
    private PodScenarioDriver podScenario;
    /** this round's engine port (the driver's own), re-minted per round with it —
     *  the {@code DESPAWN} operator arm's wire → ship resolver + remover; null before round 1. */
    private PodScenarioEnginePort podPort;
    private EngineBackedLiveWorldSnapshotSource source;
    private long lastSimTick;
    private boolean firstFlyInDone;
    // The two waveSkipHulkSweep* slots live in AdminWaveVerbs.sweep.
    private long waveClearHoldSinceLast;
    private long frameTargetNanos;
    private long pacerAnchorNanos;
    private int pacerAnchorFrame;
    private long pacerIntervalNanos;
    private int simSpeedChanges;
    private int autoSpeedChanges;
    private int stallFrames;
    /** Seats the round wrap found vacant/not-ready after the deploy read
     * (serve-cumulative; the round line's {@code wrap_vacated=}). */
    private int wrapVacatedTotal;
    /** Intents the (j) take consumed but refused to
     * stage because the seat held no mounted hull ({@code MatchSeatAuthority.isMounted} false —
     * a dead pilot's client keeps depositing until its refield). Serve-cumulative; the round
     * line's {@code staged_refused_unmounted=} (a {@code /suicide} round reads it &gt; 0). */
    private long stagedRefusedUnmounted;
    /** The pre-step (j) take's fresh-deposit age buckets (per round; the round line's {@code intent_take_age_frames=}). */
    private final IntentTakeAge intentTakeAge = new IntentTakeAge();
    /** Operator verbs whose dispatch threw a
     * RuntimeException the serve-thread fault belt caught ({@link #serveFrame}'s operator loop;
     * serve-cumulative; the round line's + the receipt's {@code operator_command_faults=}). */
    private int operatorCommandFaults;
    /** The constant chat receipt a faulted admin verb answers with
     *  (ASCII, under {@code ChatFrameCodec.TEXT_MAX_CHARS}; never the exception text). */
    private static final String OPERATOR_FAULT_RECEIPT =
            "that command failed on the server (logged) - the match continues";
    /** TEST-ONLY fault injector — the verb whose
     * EVERY dispatch throws at the top of {@link #handleOperatorCommand}
     * ({@code cc.serve.test.faultVerb}, read ONCE in the constructor; null = unarmed, the
     * production posture). Its only user is {@code ServeOperatorFaultRows}. */
    private final OperatorCommandFile.Verb injectedFaultVerb;
    private int detSplitRows;
    private int detEdgesSplitRows;
    private int overrunFrames;
    private long overrunMaxNanos;
    private int sprintEpisodes;
    private int sprintRunFrames;
    private int sprintRunMaxFrames;
    private long sprintRunStartNowNanos;
    private long sprintWallMaxNanos;
    private long debtNowNanos;
    private int snapFanSkippedTicks;
    private long fanPassMaxNanos;
    private long sendSlowestNanos;
    private long prevFrameNanos;
    private int workFrames;
    private long workNanosTotal;
    private long workMaxNanos;
    /** The default-OFF boost/system sampler's per-round tallies, carved VERBATIM. */
    private final BoostSystemSampleLane boostSystemLane = new BoostSystemSampleLane();
    private boolean matchOver;
    private boolean operatorEndRound;
    private int framesServed;

    PilotServeSession(
            PilotServeConfig config,
            EngineCombatBinding binding,
            NPeerHooks hooks,
            ListenerReady onReady,
            PickableVariants pickableOrNull,
            VariantCatalogStore variantCatalogOrNull) {
        this.config = config;
        this.binding = binding;
        this.hooks = hooks;
        this.onReady = onReady;
        this.pickableOrNull = pickableOrNull;
        this.variantCatalogOrNull = variantCatalogOrNull;
        // The TEST-ONLY fault-injector knob, read
        // ONCE here (never per frame). A set-but-unknown name stays UNARMED with one bounded row;
        // an armed injector confesses at boot so a leaked knob is never silent.
        String faultVerbName = System.getProperty("cc.serve.test.faultVerb");
        OperatorCommandFile.Verb faultVerb = null;
        if (faultVerbName != null) {
            try {
                faultVerb = OperatorCommandFile.Verb.valueOf(faultVerbName);
                System.out.println("npeer-serve fault_inject_armed verb=" + faultVerb
                        + " (TEST-ONLY cc.serve.test.faultVerb: every dispatch of this verb throws)");
            } catch (IllegalArgumentException unknownVerb) {
                System.out.println("npeer-serve fault_inject_ignored name="
                        + PeerText.quote(PeerText.bound(faultVerbName, 32)));
            }
        }
        this.injectedFaultVerb = faultVerb;
    }

    /** The serve: the former {@code serveNPeer} body, verbatim (leg 1). */
    PilotServeReceipt run() throws IOException {
        resolve();
        return serve();
    }

    /**
     * Phase 1 — resolve every knob, mint the match authority/passwords/wave schedule and the
     * serve-level ledgers: the former body, moved VERBATIM to {@link PilotServeResolver}.
     * It runs synchronously on this
     * thread, so boot-line order and every side effect are unchanged; fail-fast IAEs unchanged.
     * {@link #copyBack} copies back every field the body writes (105 names) — the set
     * must stay equal to the resolver's written set.
     * <p>A boot throw escapes {@link #run} OUTSIDE
     * {@link #serve}'s try, so before this catch the resources the resolver had already started
     * — the JFR block recording and, transitively via SeatRebindArbiter/moduleSeatCensus,
     * the stall-sampler daemon — stayed
     * live with neither copy-back nor teardown
     * (the operator poller and the ops run-writer start
     * lazily inside the serve; the stdout lane is a JVM-wide install-once wrapper with no
     * uninstall by design). The catch copies the resolver's fields back as they stand (partial)
     * and runs the serve's OWN {@link #teardown} in its own order; the boot failure always wins
     * (a teardown failure rides as suppressed). The success path keeps the carved shape unchanged.
     * Oracle: PilotRespawnServeContract axis G'' (JFR armed + the G' throw ⇒ recording closed).
     */
    private void resolve() throws IOException {
        PilotServeResolver r = new PilotServeResolver(config, binding, hooks);
        try {
            r.resolve();
        } catch (IOException | RuntimeException | Error bootFailure) {
            copyBack(r);
            try {
                teardown();
            } catch (RuntimeException | Error teardownFailure) {
                bootFailure.addSuppressed(teardownFailure);
            }
            throw bootFailure;
        }
        copyBack(r);
    }

    /** The 105 copy-back assignments (plan); the set is proven by the verify script. */
    private void copyBack(PilotServeResolver r) {
        this.eventLaneOn = r.eventLaneOn;
        this.socketLiveness = r.socketLiveness;
        this.fireLaneDeclared = r.fireLaneDeclared;
        this.fireEdgeLatch = r.fireEdgeLatch;
        this.fireClassRoutingOn = r.fireClassRoutingOn;
        this.fireClassRateMax = r.fireClassRateMax;
        this.fireClassOverrides = r.fireClassOverrides;
        this.netSinkPath = r.netSinkPath;
        this.netSinkMaxMiB = r.netSinkMaxMiB;
        this.gpeeLogPath = r.gpeeLogPath;
        this.shieldedRow = r.shieldedRow;
        this.hitCellRow = r.hitCellRow;
        this.customConfig = r.customConfig;
        this.operatorFile = r.operatorFile;
        this.roundStartGate = r.roundStartGate;
        this.autoSpeedKnob = r.autoSpeedKnob;
        this.autoSpeedFloor = r.autoSpeedFloor;
        this.autoSpeedCeiling = r.autoSpeedCeiling;
        this.ioDecoupled = r.ioDecoupled;
        this.stdoutLane = r.stdoutLane;
        this.blockRecorder = r.blockRecorder;
        this.stallStacks = r.stallStacks;
        this.opsFile = r.opsFile;
        this.phaseWindowStats = r.phaseWindowStats;
        this.worstFrames = r.worstFrames;
        this.nearContactLog = r.nearContactLog;
        this.respawnRaw = r.respawnRaw;
        this.respawnPolicy = r.respawnPolicy;
        this.gracePolicy = r.gracePolicy;
        this.chatSourceHold = r.chatSourceHold;
        this.maxSeatsPerSource = r.maxSeatsPerSource;
        this.sourceCapExempt = r.sourceCapExempt;
        this.transferTargets = r.transferTargets;
        this.transferPodTimeoutTicks = r.transferPodTimeoutTicks;
        this.sampleBoost = r.sampleBoost;
        this.sampleSystem = r.sampleSystem;
        this.afGateSampler = r.afGateSampler;
        this.peerGrade = r.peerGrade;
        this.parityWalkEvery = r.parityWalkEvery;
        this.overrunLog = r.overrunLog;
        this.canisterProbeOn = r.canisterProbeOn;
        this.waveSpawnPerTick = r.waveSpawnPerTick;
        this.hulkPoseEveryTicks = r.hulkPoseEveryTicks;
        this.asteroidSeed = r.asteroidSeed;
        this.rappCatchup = r.rappCatchup;
        this.asteroidRefreshTicks = r.asteroidRefreshTicks;
        this.hulkPoseBurstCadenceTicks = r.hulkPoseBurstCadenceTicks;
        this.asteroidBumpCadenceTicks = r.asteroidBumpCadenceTicks;
        this.aimFanEveryTicks = r.aimFanEveryTicks;
        // The resolved AIMP fan rides every PSTB as the read-only knob (1..255, else 0 = raw)
        WireKnobState.setDerived(WireKnob.AIM_FAN_TICKS, WireKnob.aimFanWireValue(aimFanEveryTicks));
        this.hulkCutGeometryOn = r.hulkCutGeometryOn;
        this.onFireTruth = r.onFireTruth;
        this.downEveryTicks = r.downEveryTicks;
        // The served snapshot stride rides every PSTB as the read-only knob (1..255, else 0 = raw)
        WireKnobState.setDerived(WireKnob.SNAP_STRIDE_TICKS, WireKnob.tickWireValue(downEveryTicks));
        this.npeerDescriptor = r.npeerDescriptor;
        this.authority = r.authority;
        this.mapHalf = r.mapHalf;
        this.opsSeatShips = r.opsSeatShips;
        this.doorFingerprint = r.doorFingerprint;
        this.matchPassword = r.matchPassword;
        this.adminPassword = r.adminPassword;
        this.passwordLedger = r.passwordLedger;
        this.descriptorSeatCount = r.descriptorSeatCount;
        this.fortressDpTotal = r.fortressDpTotal;
        this.waveRosterFullDp = r.waveRosterFullDp;
        this.waveRosterMultiplier = r.waveRosterMultiplier;
        this.fortressWaveScale = r.fortressWaveScale;
        this.waveSchedule = r.waveSchedule;
        this.engineBornGrowth = r.engineBornGrowth;
        this.stationRowPresent = r.stationRowPresent;
        this.stationRowSide = r.stationRowSide;
        this.censusVariantId = r.censusVariantId;
        this.debugCatalogueSnapshot = r.debugCatalogueSnapshot;
        this.waveProbeRows = r.waveProbeRows;
        this.rosterHullBound = r.rosterHullBound;
        this.waveClearRoundEndKnob = r.waveClearRoundEndKnob;
        this.waveClearLatchRoundEnd = r.waveClearLatchRoundEnd;
        this.waveClearLatchTag = r.waveClearLatchTag;
        this.finaleCreepSu = r.finaleCreepSu;
        this.seatIdByWireId = r.seatIdByWireId;
        this.seatWireIdBySeatId = r.seatWireIdBySeatId;
        this.adminDebug = r.adminDebug;
        this.seatTeams = r.seatTeams;
        this.measureOwnedShipId = r.measureOwnedShipId;
        this.seatBandCeiling = r.seatBandCeiling;
        this.bindHost = r.bindHost;
        this.bindPort = r.bindPort;
        this.baseStep = r.baseStep;
        this.rounds = r.rounds;
        this.lastAnnouncedVariantBySeat = r.lastAnnouncedVariantBySeat;
        this.rebindArbiter = r.rebindArbiter;
        this.flyInDeferredForTransfer = r.flyInDeferredForTransfer;
        this.adhocFoes = r.adhocFoes;
        this.moduleVerbsArmed = r.moduleVerbsArmed;
        this.clientConnected = r.clientConnected;
        this.operatorEndMatch = r.operatorEndMatch;
        this.prevLive = r.prevLive;
        this.liveByIdScratch = r.liveByIdScratch;
        this.wireSink = r.wireSink;
        this.gpeeLog = r.gpeeLog;
        this.armorCellBook = r.armorCellBook;
        this.downComposer = r.downComposer;
        this.resetFramesEmitted = r.resetFramesEmitted;
        this.lastRoundMatchOver = r.lastRoundMatchOver;
        this.lastMatchOverTag = r.lastMatchOverTag;
        this.lastMatchOverWinner = r.lastMatchOverWinner;
        this.lastMatchOverTick = r.lastMatchOverTick;
        this.lastRoundOperatorEnd = r.lastRoundOperatorEnd;
        this.lastServedSimTick = r.lastServedSimTick;
        this.heldLaneFallback = r.heldLaneFallback;
        this.classRoutingFallback = r.classRoutingFallback;
        this.sessionDeclReceived = r.sessionDeclReceived;
    }

    /**
     * The serve skeleton (P23 complete after leg 6 — every phase is its own method; the bodies
     * moved VERBATIM). Phase 2 = {@link #announceAndAwait} + {@link #mintMatchScope}
     * (leg 3); per round: {@link #mintRound} (leg 4) → {@link #holdRoundStartGate} → the frame
     * loop over {@link #serveFrame} (leg 6; {@code false} = the loop's old {@code break}) →
     * {@link #closeRound} → {@link #wrapRound} or the serve-ending {@code break} (leg 5 — both
     * breaks stay HERE, visible at their loops); phase 7 = {@link #fanServeEnd}, {@link #teardown},
     * {@link #receipt} (leg 2).
     */
    private PilotServeReceipt serve() throws IOException {
        // The P27 boot read BEFORE the socket opens
        // (a present-but-unreadable bans file throws = the serve refuses to boot, fail-closed);
        // installed by the door before its reader starts.
        BanSet bootBans = banLane.bootBans();
        // The hidden-ships boot READ beside the bans one (a damaged file =
        // memory-only + one loud row, never a refused boot); its overlay applies after the joiner default below.
        hiddenShips.boot();
        // The reel carries no hidden set (picks replay verbatim from it).
        if (config.replayReelOrNull() != null) {
            System.out.println("npeer-serve hidden_ships_replay_source=live_file (the reel carries no hidden set;"
                    + " the replay listing follows the host's current file)");
        }
        ModerationVerbWarmup.touch();
        // The hold arm is visible at boot (operator token only).
        System.out.println("npeer-serve ban_source_hold policy=" + chatSourceHold.token()
                + " (moderation.chatSourceHold; holds last the ban's term across restarts)");
        // A re-simulation opens its reel FIRST (a bad header or a
        // row-less reel throws = the re-sim refuses loud) and then the unreachable replay door with the
        // same parameters a live serve would hand the socket door.
        replayFeed = config.replayReelOrNull() == null ? null : ReplayFeed.open(config.replayReelOrNull());
        // The PvP shape keeps its teams across the wrap and speaks edge words.
        pvpTeams = MatchDescriptors.isPvpShape(authority.descriptor().shape());
        seatTeams.usePvpTeamWords(pvpTeams);
        PidsOpenParams doorParams = new PidsOpenParams(doorFingerprint,
                matchPassword, passwordLedger, pickableOrNull, variantCatalogOrNull,
                rebindArbiter, moduleVerbsArmed, transferTargets, bootBans, chatSourceHold,
                SourceSeatCap.of(maxSeatsPerSource, sourceCapExempt),
                // Password mode serves the match a probe's stale term asks
                // for; an unservable document throws here and the open fails loud like the sibling door mints.
                matchPassword == null ? null : ProbeMatchDoc.of(MatchDocPreflight.prepare(authority.descriptor())),
                // The PvP shape places joiners by team (the door's team table)
                MatchDescriptors.isPvpShape(authority.descriptor().shape()));
        try (PilotIntentDatagramServer door = replayFeed != null
                ? PilotIntentDatagramServer.openReplay(authority, descriptorSeatCount, doorParams)
                : PilotIntentDatagramServer.openMultiPeer(bindHost, bindPort, authority, descriptorSeatCount,
                        doorParams)) {
            // The perimeter liveness
            // assert is the FIRST thing after the door opens — the reader thread is already
            // live, so any later placement admits HELLOs for the announce window and then reaps
            // them on the throw. Fail-closed here; the try-with-resources closes the socket.
            PilotIntentDatagramServer.requireLivenessArmed(door.isOffLoopback(), socketLiveness);
            // The reader binds joiners' seat-name tables and
            // the live name fan defers to this cursor; set before acceptHs13 arms admission.
            door.setAdmissionCursor(rappCatchup);
            if (replayFeed != null) {
                replaySessionDoor = new ReplayDoorAdapter(door);
            }
            // A re-simulation never writes the host's bans file (no boot purge).
            if (!door.isReplay()) {
                banLane.openSink();
                hiddenShips.openSink(); // a re-simulation writes no hidden-ships file either
            }
            PilotServeReceipt early = announceAndAwait(door);
            if (early != null) {
                return early;
            }
            mintMatchScope();
            for (int round = 0; round < config.roundsPerSocket(); round++) {
                lastRoundServed = round;
                mintRound(door, round);
                holdRoundStartGate(door, round);
                for (int frame = 0; frame < config.framesPerSession(); frame++) {
                    if (!serveFrame(door, round, frame)) {
                        break;
                    }
                }
                if (replayFeed != null) {
                    replayFeed.noteRoundClosed(round, lastSimTick);
                }

                closeRound(door, round);
                // Round wrap (in the amended order): gated reset with the DEPLOYABLE set @
                // fire time (occupied ∧ combat-ready — a claimed-but-never-ready
                // seat stays ABSENT at the boundary and T1-flies-in on its first intent; returns
                // the redeployed seats' fresh identities — the NEXT round's
                // registry pre-interns them) → resetRoundSeats(SAME set — marks the named seats
                // MOUNTED per the MatchSeatAuthority ground truth, so round r+1 has NO fly-ins;
                // it emits nothing) → optional MRST fan (compose-once, ONE roundId bump, ONE
                // sink record) → seat BASELINE burst to every live session (the state
                // replication the reset did not emit). framesPerSession exhausted without
                // matchOver ends the serve (legacy posture).
                if (matchOver && round < config.roundsPerSocket() - 1) {
                    wrapRound(door, round);
                } else {
                    break;
                }
            }
            fanServeEnd(door);
        } finally {
            teardown();
        }
        return receipt();
    }

    /**
     * Door wiring + announce (leg 3): join announce, WELCOME rate/flags, admin, the wire sink (opened
     * before admission arms), debug + joiner defaults, the voice relay, the fixed-bind row, onReady,
     * the GPEE log, the DOWN
     * composer, the {@code npeer-serve open} row, the lobby snapshot, then the client-wait
     * gate. Returns the no-session receipt (the early exit of {@link #serve()}, unchanged) or
     * null once a session is admitted; {@code door} is the try-with-resources variable of
     * {@link #serve()}.
     */
    private PilotServeReceipt announceAndAwait(PilotIntentDatagramServer door)
            throws IOException {
        // The owner-visible "X joined" line — armed on every real serve,
        // fanned by the door on a session's FIRST two-way datagram (never on admission:
        // the half-open readmission flap announced nothing real). Default OFF in
        // the door so the smoke lane's seq-pinned captures stay byte-identical.
        door.setJoinAnnounce(true);
        // Joiner default: set BELOW the debug-variants catalogue (
        // on a debug door it canonicalizes against that catalogue too), still before the
        // port publishes.
        // Every v3 WELCOME advertises the per-match rate. Set BEFORE the
        // port publishes (onReady/port file) — the first HELLO races effectiveTickRate (a field
        // since P23 leg 3, written in mintMatchScope()), and the door must never advertise the default to a
        // non-default match. Same source: the EFFECTIVE dt (--fixed-dt wins).
        // Out-of-[10,600] rates (wall-clock-decoupled test rigs drive dt=1e-6) have NO
        // in-contract advertisement — the client seed refuses anything outside the
        // ServerTickRate bounds by design — so the door keeps its default, loud.
        int announceRate = (int) Math.round(1.0 / config.fixedDt());
        if (announceRate >= 10 && announceRate <= 600) {
            door.setWelcomeTickRate(announceRate);
        } else {
            System.out.println("npeer-serve welcome_tick_rate_out_of_contract rate="
                    + announceRate + " advertising=60 (client seeds refuse out-of-[10,600])");
        }
        // Every v5 WELCOME carries the server-owned
        // match-config flags — same set-before-publish law as the rate above. The values
        // come from the server levers (bit0 cc.server.officerSkillsAll; bit1
        // cc.server.clientDebug: client debug affordances — F8/F10/F11
        // verbs + dev HUD rows — live ONLY in a debug-flagged match; F9 mark is PUBLIC
        // (its MARK capture lands on this log in every serve);
        // watch tooling arms it, public serves default OFF); the codec itself stays
        // platform-pure. Re-admits
        // re-seed the client for free (B4 lane).
        final boolean clientDebug = ClientDebugLease.clientDebugUnderLease(ServerConfig.resolveBoolean(
                "cc.server.clientDebug", "CC_SERVER_CLIENT_DEBUG",
                "server.clientDebug", false));
        // W12 voice L2: the voice sheet resolves + validates jointly HERE
        // (a bad knob combination throws = the boot-refusal law); enabled rides WELCOME
        // matchFlags bit 2 (v9), the numbers ride VCFG at admit. The relay + its
        // position slot are composed once per serve; the anchor lane is per round.
        final VoiceConfig voiceConfig = VoiceConfig.resolve();
        door.setWelcomeMatchFlags(
                (cc.starsector.server.platform.AllSkillsAttach.enabled() ? 1 : 0)
                        | (clientDebug ? 2 : 0)
                        | (voiceConfig.enabled() ? 4 : 0));
        // The custom-variant policy rides the v11 WELCOME tail UNCONDITIONALLY
        // (resolved up front by PilotServeResolver whether or not the submission lane installs below) — the SPA
        // editor locks its tier to the server's and greys Submit at a submissions-OFF serve.
        door.setWelcomeCustomConfig(customConfig.wireTier(), customConfig.perSeat(),
                customConfig.sModCap(), customConfig.submissions());
        door.setAdminPassword(adminPassword); // null = no admin seat
        // The wave knobs resolve and the wire sink opens BEFORE
        // admission arms, so a HELLO admitted on a fixed bind port before the port publishes is still in
        // the reel (and the header can carry the resolved knobs). The open stays fail-soft: a failed
        // open serves the match unrecorded.
        if (waveSchedule != null) {
            waveKnobs = WaveLoadoutInflater.resolveKnobs();
        }
        if (!netSinkPath.isEmpty()) {
            try {
                // Fold: the header carries the npeer re-sim block (the R2 wire-layouts
                // contract's META keys) — the wave knobs resolved just above, the asteroid seed, the born-growth posture, the
                // descriptor's seats and the tick rate — so a replay reads its context from the reel, never
                // from the live config. The knobs' seed IS the effective one (a blank seed drew once above).
                WaveLoadoutInflater.Knobs wk = waveKnobs;
                MatchRecordMeta.RecordContext ctx = new MatchRecordMeta.RecordContext(
                        wk == null ? 0L : wk.seed(), wk != null && wk.enabled(),
                        wk == null ? "" : "quality=" + wk.qualityOrNaN() + ";smods=" + wk.averageSModsOrNeg()
                                + ";dmods=" + wk.dmods() + ";qpw=" + wk.qualityPerWave(),
                        (int) asteroidSeed, engineBornGrowth, descriptorSeatCount, announceRate);
                LinkedHashMap<String, String> header = MatchRecordMeta.forNpeerRecord(ctx);
                wireSink = netSinkMaxMiB > 0
                        ? WireRecordSink.openBounded(netSinkPath, header, ioDecoupled, netSinkMaxMiB * 1024L * 1024L)
                        : WireRecordSink.open(netSinkPath, header, ioDecoupled);
                door.attachSink(wireSink);
                sinkFeedDoor = door;
                recordRowsArmed = true;
                System.out.println("npeer-serve wire_sink path=" + wireSink.path()
                        + " max_mib=" + netSinkMaxMiB);
            } catch (Throwable open) {
                System.out.println("npeer-serve wire_sink_open_failed path=" + netSinkPath
                        + " error=" + open.getClass().getName()
                        + ": " + (open.getMessage() == null ? "" : open.getMessage()));
            }
        }
        // THE FLIP stage 1: the serve path ARMS the version-13 door — after the admin secret (the
        // shares bind it; the door refuses the reverse order), before the port is published below. A replay
        // door admits no HELLO (its sessions come from the reel), so it is never armed.
        if (replayFeed == null) {
            door.acceptHs13();
        }
        // The /debugships+/spawnfoe FULL-registry catalogue — hook-supplied
        // (null supplier on every contract/legacy caller ⇒ the door honestly replies
        // "debug picking unavailable"). Snapshot ONCE here at wiring: registry load-time
        // writes are quiescent by door-open and the engine map is UNSYNCHRONIZED — never
        // re-read per tick. The boot row is the smoke's anchor.
        // The snapshot taken at the station-module fold above (already
        // stripped of unbudgetable station ids) — one registry read per serve, never two.
        if (hooks.debugVariantIds() != null) {
            List<String> debugIds = debugCatalogueSnapshot != null
                    ? debugCatalogueSnapshot : hooks.debugVariantIds().get();
            if (debugIds != null && !debugIds.isEmpty()) {
                PickableVariants debugCatalogue = PickableVariants.fromIds(debugIds);
                door.setDebugVariants(debugCatalogue);
                adhocOfficerCatalogueIds = debugCatalogue.spawnIds(); // Warmed at serve boot, spawnable ids only
                System.out.println("npeer-serve debug-variants catalog="
                        + debugCatalogue.ids().size());
                // Since a recorded test run ended over a
                // 611-hull join window, the hello pick, the joiner default and the join-window
                // PROBE list the curated mod scan (the mod's own `data/variants` ships) on EVERY
                // door, the debug door included. The widening to the pilotable
                // registry catalogue (`widenPicksForDebugDoor`)
                // was RETIRED here and its code DELETED — never re-wire it.
                // The FULL catalogue above stays admin-only (`/debugships` + `/spawnfoe`).
            }
        }
        // The server-owned joiner default variant — the ONE
        // `match.joinerDefaultVariant` row (host GUI writes it at Start; the
        // `set-joiner-default` operator verb changes it live). Blank/absent = descriptor
        // row. Canonicalized + refused loud by the door (never a hull the serve can't
        // field). Set BEFORE the port publishes: the first HELLO must see it — and AFTER
        // the debug-variants catalogue above (its registry widening).
        String joinerDefaultRaw = ServerConfig.resolve("cc.match.joinerDefaultVariant",
                "CC_MATCH_JOINER_DEFAULT_VARIANT", "match.joinerDefaultVariant", "");
        if (!joinerDefaultRaw.isBlank()) {
            door.setJoinerDefaultVariant(joinerDefaultRaw);
        }
        // W15 v0: the custom-variant submission lane — installed
        // BEFORE the port publishes, only when armed; composition root = CustomVariantWiring.
        System.out.println(customConfig.bootLine());
        if (customConfig.submissions()) {
            // The hooks may name who composes the lane (a contract serve over
            // the fake world); none = the engine-bound install.
            customLaneOrNull = hooks.customsInstaller() == null
                    ? CustomVariantWiring.install(door, customConfig, pickableOrNull)
                    : hooks.customsInstaller().install(door, customConfig, pickableOrNull);
            if (replayFeed != null && customLaneOrNull != null) {
                // The catalog promotion is a wall-clock input no
                // row records, so a re-simulation never lets it reach the sim thread.
                customLaneOrNull.setTickHook(null);
                System.out.println("npeer-serve replay catalog_hook=off");
                // A re-admitted submission the engine accepts must never
                // write its variant file or ledger line (player text) onto this host's disk: the writer is
                // detached and retired before any row is read, and each accept it would have taken is counted
                // on the reel's END (replay.persistSuppressed).
                CustomVariantPersistSink opened = customLaneOrNull.persistSinkOrNull();
                customLaneOrNull.setPersistSink(null);
                if (opened != null) {
                    opened.close();
                }
                System.out.println("npeer-serve replay persist_sink=off");
            }
            // The customs side of the host's ship verbs:
            // the lane's KeptSide delegates to CustomVariantOps' six statics over THIS serve's custom lane
            // + door (the kept listing, the removed inbox tokens, the three queued-store verbs that book a K1 final).
            // A null lane (install refused) answers 'customs are off' inside the statics, the same as KeptSide.NONE.
            hiddenShips.keptSide(new HiddenShipsLane.KeptSide() {
                @Override
                public HiddenShipsLane.HostParts hostParts() {
                    CustomVariantOps.HostParts parts = CustomVariantOps.hostParts(customLaneOrNull);
                    return new HiddenShipsLane.HostParts(parts.kept(), parts.removed());
                }

                @Override
                public String keptStemOrNull(String id) {
                    return CustomVariantOps.keptStemOrNull(customLaneOrNull, id);
                }

                @Override
                public String removedStemOrNull(String id) {
                    return CustomVariantOps.removedStemOrNull(customLaneOrNull, id);
                }

                @Override
                public String shipKeep(OperatorCommandFile.Command c) {
                    return CustomVariantOps.shipKeep(customLaneOrNull, door, c);
                }

                @Override
                public String shipRemoveKept(OperatorCommandFile.Command c, String keptStem) {
                    return CustomVariantOps.shipRemoveKept(customLaneOrNull, door, c, keptStem);
                }

                @Override
                public String shipRestoreKept(OperatorCommandFile.Command c, String removedStem) {
                    return shipRestoreKept(c, removedStem, null);
                }

                @Override
                public String shipRestoreKept(OperatorCommandFile.Command c, String removedStem, String supersedesOrNull) {
                    return CustomVariantOps.shipRestoreKept(customLaneOrNull, door, c, removedStem, supersedesOrNull);
                }
            });
        } else if (!System.getProperty(CatalogPromotionLane.PROMOTE_DIR_PROPERTY, "").isBlank()) {
            // The arm said ARMED — the serve says why the lane is not live
            System.out.println("catalog-promote off reason=custom_submissions_off knob="
                    + CatalogPromotionLane.PROMOTE_DIR_PROPERTY);
        }
        // The hidden-ships overlay APPLY —
        // after the joiner default is read (applied first, a hidden default would read unpickable and a later show
        // would never restore it), after the custom lane installs (its boot read names the kept ships that will
        // list, so a file hiding every shipped ship un-hides none while kept ships remain; a re-simulation never
        // lists one), and before the port publishes, so the first HELLO and join probe already omit a hidden ship.
        CatalogPromotionLane keptCatalog = customLaneOrNull == null || replayFeed != null ? null
                : customLaneOrNull.promoOrNull();
        hiddenShips.applyBoot(door, pickableOrNull, keptCatalog == null ? List.of() : keptCatalog.keptBootListable());
        //Walk the kept-boot snapshot NOW, on this
        // thread (the sim thread), so every kept ship is registered and on the listing before the port publishes —
        // the lobby's join probe, /ship and the editor's list carry it from the first HELLO. Before this the walk
        // waited for the first round-start gate's drainTick, and a host who restarted and looked saw it gone.
        if (keptCatalog != null) {
            keptCatalog.bootWalk();
        }
        // The wave loadout inflater — vanilla's fleet inflation over every wave row,
        // one VDEF per inflated hull through the carriage; installed HERE (sim thread,
        // before the port publishes) so the whole chain pre-inflates at boot and the frame loop
        // stages from a cache. Knob parse THROWS at serve entry (never silently inert).
        if (waveSchedule != null) {
            // Verb: the lane installs REGARDLESS of the boot knob (a disabled lane
            // costs nothing — preInflate returns, the stage hook hands the row back) so that
            // `/wave inflate on` is a live dial on a serve booted `wave.inflate=false` too
            // (server-settable over the wire); the boot row carries `enabled=`.
            waveInflaterOrNull = WaveLoadoutInflater.installOrNull(door, waveSchedule, customConfig, waveKnobs);
        }
        if (opsFile != null) {
            // Step 3(f): custom.count/rejected/banned/submissions rows (lane OFF = zeros/off);
            // + the rejected split, pending/cap, policy knobs, export readback.
            opsFile.attachCustomStats(() -> CustomVariantOps.stats(customLaneOrNull, customConfig));
            // The ships.* rows (the console's Hide / Show list), customs on or off.
            opsFile.attachShipStats(() -> hiddenShips.stats(pickableOrNull));
            // The ops GUI's MagicLib line + per-seat cell - the pilot mark's
            // own FOLLOW inputs (the live receipt, this match's door, each seat's latest admitted HELLO bits), read
            // at each compose; the status file judges each seat through MagicSeamFollow, the one predicate.
            opsFile.attachMagicSeam(() -> new OpsStatusFile.MagicSeam(MagicSeamFollow.receiptOrNull(),
                    doorFingerprint != null, seat -> helloMagicBitsBySeat.getOrDefault(seat, 0)));
            // The P27 ban rows (bans= + ban.<i>.*).
            // A mapped VIEW: compose reads size() + the first MAX_BAN_ROWS rows only.
            opsFile.attachBans(() -> {
                // LIVE entries only — an expired ban is not listed.
                List<BanSet.Entry> entries = door.bans().snapshot(System.currentTimeMillis());
                return new java.util.AbstractList<cc.starsector.server.ops.OpsStatusFile.BanRow>() {
                    @Override
                    public cc.starsector.server.ops.OpsStatusFile.BanRow get(int i) {
                        BanSet.Entry e = entries.get(i);
                        return new cc.starsector.server.ops.OpsStatusFile.BanRow(
                                BanSet.kindToken(e.kind()), e.key(), e.atMillis(),
                                BanSet.originToken(e.origin()));
                    }

                    @Override
                    public int size() {
                        return entries.size();
                    }
                };
            });
        }
        voiceSlot = voiceConfig.enabled() && replayFeed == null
                ? new VoicePositionSlot() : null;
        voice = voiceConfig.enabled() && replayFeed == null
                ? new VoiceRelay(voiceConfig, new VoiceRelay.Transport() {
                    @Override
                    public void sendVoice(Object handle, long voiceSeq, byte[] vcdnPart) {
                        door.sendVoiceDatagram(
                                (PilotIntentDatagramServer.PeerSession) handle, voiceSeq,
                                vcdnPart);
                    }

                    @Override
                    public int roundId() {
                        return door.currentRoundId();
                    }

                    @Override
                    public void sendCtrl(Object handle, byte[] ctrlFrame) {
                        door.sendCtrlToSession(
                                (PilotIntentDatagramServer.PeerSession) handle, ctrlFrame);
                    }
                }, voiceSlot)
                : null;
        if (voice != null) {
            door.attachVoice(voice); // door.close() closes the relay thread too
            voice.start();
        }
        System.out.println("npeer-serve voice " + voiceConfig.summary()
                + " relay=" + (voice != null ? VoiceRelay.THREAD_NAME : "off"));
        if (clientDebug) {
            System.out.println("npeer-serve client_debug ON -> WELCOME matchFlags bit1"
                    + " (watch posture; public serves leave cc.server.clientDebug off)");
        }
        if (!bindHost.isEmpty() || bindPort != 0) {
            System.out.println("npeer-serve fixed_bind bind_host="
                    + (bindHost.isEmpty() ? "auto" : bindHost) + " bind_port=" + bindPort
                    + " advertised=" + door.host() + ":" + door.port());
        }
        host = door.host();
        port = door.port();
        offLoopback = door.isOffLoopback();
        // The fail-closed perimeter liveness assert already ran in serve() the
        // instant the door opened; this is the posture row only.
        System.out.println("npeer-serve socket_liveness armed=" + socketLiveness
                + " off_loopback=" + offLoopback);
        if (onReady != null) {
            onReady.onReady(host, port);
        }
        if (!gpeeLogPath.isEmpty()) {
            gpeeLog = GameplayEdgeEventWatchSink.openOrNull(gpeeLogPath); // prints open/fail itself
        }
        // Resolved ONCE at boot: a mid-serve knob flip never changes emission shape
        // (the shared DOWN-emission composer — the same seam the legacy door
        // composes through, so a DOWN-path capability lands once for both lanes).
        // armDeltaLane=true: THIS loop wires the AWSD baseline
        // lifecycle (despawn evict + round clear below); the legacy door arms its own
        // composer door-internally.
        // ONE ArmorCellBook per serve, shared by the composer
        // (stage/commit per record) and every round's snapshot source (observe feed);
        // null when cc.net.armorCells is off — nothing armor-cell runs at all. Knob
        // values are ctor ints (boot-resolve discipline, the resolveAtOpen law).
        armorCellBook = DownEmissionComposer.armorCellsEnabled()
                ? new ArmorCellBook(
                        DownEmissionComposer.armorCellsTickBytesKnob(),
                        DownEmissionComposer.armorCellsRedundancyKnob(),
                        DownEmissionComposer.armorCellsRepairBytesKnob())
                : null;
        downComposer = DownEmissionComposer.resolveAtOpen(true, armorCellBook);
        System.out.println("npeer-serve open seats=" + descriptorSeatCount
                + " respawn_policy=" + respawnRaw + " " + downComposer.openLine()
                + " door_fingerprint=" + (doorFingerprint == null ? "off" : doorFingerprint)
                + " admission=" + (matchPassword == null ? "token" : "password")
                + " operator_file=" + (operatorFile == null ? "off" : operatorFile.file())
                + " ops_file=" + (opsFile == null ? "off" : opsFile.file())
                // the file-I/O mode this serve runs (appended field only).
                + " io=" + (ioDecoupled ? "decoupled" : "sync")
                + " host=" + host + " port=" + port + " off_loopback=" + offLoopback);
        // the lobby snapshot (phase=lobby) BEFORE the await blocks: stamp_ms
        // staleness during the block IS the GUI's "listening, nobody joined" signal.
        if (opsFile != null) {
            opsFile.writeLobby(host, port, descriptorSeatCount);
        }
        // D-LOBBYEND: the operator file is polled DURING the
        // client-wait too — end-match/end-round in phase=lobby end the serve on the same
        // clean-exit-0 path as the no-session fuse (run-supervised clean_exit STOPS; the
        // final snapshot re-enables Start); other lobby-read verbs carry into (a2).
        // A re-simulation mints every frame-0 session of the reel
        // first (the gate's takes name them), so the wait below returns at once.
        if (replayFeed != null) {
            System.out.println("npeer-serve replay pre_mint sessions=" + replayFeed.preMint(replaySessionDoor));
        }
        // a replay reads no operator file, so its gate never polls and never calls the arm consumer
        lobbyGate = new LobbyOperatorGate(replayFeed == null ? operatorFile : null, cmd -> armAdminCid(door, cmd));
        if (!door.awaitLiveSessions(1, config.clientWaitMillis(), lobbyGate)) {
            if (lobbyGate.endVerb() != null) {
                System.out.println("npeer-serve operator_" + lobbyGate.endVerb() + "_lobby host="
                        + host + " port=" + port + " (no session admitted; clean exit — the"
                        + " supervisor stops, Start re-enables on the final snapshot)");
                if (opsFile != null) {
                    opsFile.writeFinal(door.bytesSentTotal(), door.readerCounters().bytesReceivedTotal,
                            door.sendFailures(), List::of);
                }
            } else {
                System.out.println("npeer-serve no_session_admitted within "
                        + config.clientWaitMillis() + "ms host=" + host + " port=" + port);
                // D-OPSFILE rider: the fuse end is the same clean
                // exit — without a final snapshot the file stays at the last lobby stamp
                // with a dead pid, and a standalone GUI reads GONE instead of MATCH ENDED.
                if (opsFile != null) {
                    opsFile.writeFinal(door.bytesSentTotal(), door.readerCounters().bytesReceivedTotal,
                            door.sendFailures(), List::of);
                }
            }
            return new PilotServeReceipt(
                    host, port, offLoopback, false, 0, 0, 0, false, false, rounds, null);
        }
        clientConnected = true;
        return null;
    }

    /**
     * Match-scope mint (leg 3): the round-crossing registries, the effective tick rate, the
     * sim-speed governor state, the stdout I/O meter, the boot warms and the linger law — all
     * fields now (they survive round wraps; the round loop reads and reassigns them).
     */
    private void mintMatchScope() {
        // The previous round wrap's gated-reset identities — the next round's registry
        // pre-interns (empty on the round-0 zero-hull boot; fly-ins intern at (c)).
        pendingRoundPreInterns = List.of();
        pendingWrapRepoints = new ArrayList<>();
        // Registry wiring: ONE content catalog per serve, minted here at
        // bootstrap and shared by every round's source — contentIds PERSIST across round
        // resets (the persistence law; the client catalog fold relies on it), unlike the per-round
        // stableIdRegistry below. Feeds the per-record contentId intern + the v15
        // content-dictionary fold inside each round's snapshot source.
        contentRegistry = new ContentIdRegistry();
        // rate-coupled tick windows derive from the EFFECTIVE dt (--fixed-dt wins
        // over cc.sim.tickRate) — the legacy lane's twin locals; historical values at 60.
        effectiveTickRate = (int) Math.round(1.0 / config.fixedDt());
        fallbackHeldTicks = (int) Math.round(FIRE_LANE_FALLBACK_HELD_SECONDS * effectiveTickRate);
        // The announced-dilation SIM-SPEED multiplier (K2(b): dt frozen
        // in-round; only steps/wall-second vary). Persists across round wraps — the
        // operator set it; a boundary must not silently reset the pace. A field since P23
        // leg 3: the ops-verb dispatch (handleOperatorCommand, (a2)) runs on this serve thread.
        simSpeed = 1.0d;
        // Governor arm-state — match-scoped like simSpeed (the operator armed
        // it; a round boundary must not silently disarm), window/dwell reset per round at
        // the pacer locals. armed-at-boot rides the knob; the verb outranks either way,
        // and auto NEVER re-arms itself after a manual set-sim-speed disarm.
        autoSpeedArmed = autoSpeedKnob;
        autoSpeedDormantLogged = false;
        autoSnapPending = autoSpeedKnob;
        autoSpeedLaw = new SimSpeedGovernorLaw(
                autoSpeedFloor, autoSpeedCeiling,
                (long) (config.fixedDt() * 1_000_000_000.0d));
        // Stdout I/O accounting for the stall witness — JVM-wide
        // idempotent install, aimed at THIS serve thread; fail-soft null (io_* print 0).
        ioMeter = ServeStdoutIoMeter.installForCurrentThread();
        // Boot warms: D2-A pure-JVM fracture-lane classload warm +
        // D3(ii) unconditional officer-table load — both BEFORE the first round opens,
        // so no served frame pays the one-time bill (fortress shapes already paid the
        // table read in their boot checks; this covers every other shape).
        ServeBootWarm.warmFractureLaneClasses();
        // Boot warm leg 2 — fan-codec encode side + t0-capture/t90/
        // wave-mint classload lanes (the rig's measured un-warmed remainder).
        ServeBootWarm.warmFanCodecsAndCaptureLane();
        ServeBootWarm.warmPilotApplyLaneClasses(); // leg 3: tick-1 first-apply classes
        ServeBootWarm.warmEngineFirstUseClasses(); // leg 4: measured cold vanilla first-use classes
        cc.starsector.server.platform.OfficerAttach.bootWarmTable();
        // The /spawnfoe|/spawnfriend 'officer' picks — classified from the
        // engine's variant facts + skill-checked + wire-round-tripped HERE, once per serve, so
        // the (c2c) drain does one map lookup on the sim thread.
        cc.starsector.server.platform.AdhocOfficerTable.bootWarm(adhocOfficerCatalogueIds);
        // post-defeat linger — law + knob in MatchLingerLaw; the hold
        // itself is the frame-loop break check below (serve-ending round only).
        lingerLaw = new MatchLingerLaw(
                MatchLingerLaw.resolveMatchLingerSec(), config.fixedDt());
        // The admin wave/spawn verb lane. Every input is already
        // final for the serve — waveSchedule + the fortress dials + mapHalf from resolve(),
        // adhocFoes from resolve(), waveInflaterOrNull from announceAndAwait, the
        // waveClearRoundEnd knob from resolve(), effectiveTickRate from just above. serve()
        // runs announceAndAwait -> mintMatchScope -> the round loop, and handleOperatorCommand
        // is only reached from serveFrame, so the lane exists before any press can arrive.
        waveVerbs = new AdminWaveVerbs(new AdminWaveVerbs.Inputs(config, hooks, npeerDescriptor,
                fortressDpTotal, fortressWaveScale, waveSchedule, waveInflaterOrNull, adhocFoes,
                mapHalf, effectiveTickRate, waveClearRoundEndKnob));
    }

    /**
     * Per-round mint (leg 4): the pilot step + fire-lane routing, the event lanes / probes /
     * detectors, the per-round counters, the stable-id registry + roster append-log, the module /
     * grace / transfer / pod / voice-anchor lanes, the {@code /podtest} driver, the fresh snapshot
     * source + its triggers, tick rate + map bounds, the round-0 warms and the DISCARDED receipt
     * pre-warm — every local the round-start gate, the frame loop or the round close reads is a
     * field now (re-assigned here every round = the former per-iteration re-declaration).
     * {@code door} is the try-with-resources variable of {@link #serve()}; {@code round} its
     * loop variable.
     */
    /**
     * The seat's DESCRIPTOR roster variant — the live-roster fold's last
     * fallback (fielded ?? next-respawn override ?? this), read before a seat's first fly-in.
     * The same seat-id-sorted side ordinal → roster slot walk as {@code DescriptorLayout.seatSlot}
     * (its public halves); null for a seat the descriptor does not mint (the fold counts it
     * {@code unresolved=}). A walk fault is counted by the fold as unresolved and logged once here.
     */
    private String descriptorSeatVariantOrNull(int seatId) {
        try {
            List<MatchDescriptor.Seat> seats = new ArrayList<>(npeerDescriptor.seats());
            seats.sort(java.util.Comparator.comparingInt(MatchDescriptor.Seat::seatId));
            int seatIndex = -1;
            for (int i = 0; i < seats.size(); i++) {
                if (seats.get(i).seatId() == seatId) {
                    seatIndex = i;
                    break;
                }
            }
            if (seatIndex < 0) {
                return null;
            }
            int side = seats.get(seatIndex).side();
            int ordinal = cc.starsector.server.bridge.DescriptorLayout
                    .fullLayoutSideOrdinals(seats)[seatIndex];
            List<String> slots = cc.starsector.server.bridge.DescriptorLayout
                    .seatVariantSlotsBySide(npeerDescriptor.roster()).get(side);
            return ordinal < slots.size() ? slots.get(ordinal) : null;
        } catch (RuntimeException fault) {
            if (!descriptorSeatVariantFaultLogged) {
                descriptorSeatVariantFaultLogged = true;
                System.out.println("npeer-serve wave_dp_basis descriptor_seat_fault seat=" + seatId
                        + " err=" + fault.getClass().getSimpleName()
                        + " (the roster fold counts it unresolved; logged once)");
            }
            return null;
        }
    }

    private boolean descriptorSeatVariantFaultLogged;

    private void mintRound(PilotIntentDatagramServer door, int round) throws IOException {
        pilotStep = new PilotIntentStep(
                baseStep, binding, config.retryBudgetTicks(), effectiveTickRate, fireEdgeLatch);
        // The human-pilot mark needs the seat behind each
        // wire id. A seat's wire id is FIXED for the match (the same law
        // CommandTransferServeLane.seatIdForWireIdOrNull reads over the inverse map), so the
        // lambda reads the session FIELD at call time, never a captured snapshot: a
        // later-assigned map is still seen. -1 = unknown wire (an AI hull, a null map before the
        // resolver ran) = no mark, because the applier writes the mark only for seat id >= 1.
        pilotStep.setSeatIdResolver(wire -> {
            Integer seat = (seatIdByWireId == null) ? null : seatIdByWireId.get(wire);
            return seat == null ? -1 : seat;
        });
        // The mark's FOLLOW flag, judged at each hull re-point from the live
        // seam receipt, this match's door, and the seat's latest admitted HELLO bits (the one predicate's home is
        // MagicSeamFollow). Read at call time, never captured: a later admission is still seen.
        pilotStep.setPilotMarkFlagsResolver(seat -> MagicSeamFollow.markFlags(MagicSeamFollow.receiptOrNull(),
                doorFingerprint != null, helloMagicBitsBySeat.getOrDefault(seat, 0)));
        declaredLaneActive = fireLaneDeclared && !heldLaneFallback;
        pilotStep.setDeclaredFireLane(declaredLaneActive);
        if (hooks.finaleCreep() != null) {
            // The finale dies with the round (the gated reset
            // re-deploys the world); the author ticks PRE-STEP through this round's
            // step decorator.
            hooks.finaleCreep().disarm();
            pilotStep.setFinaleCreep(hooks.finaleCreep());
        }
        // Routing armed per-round (declaredLaneActive's routed twin): every apply
        // state — existing and future computeIfAbsent creations — enrolls with its own
        // per-ship BASE-spec resolver. Decl-recency window ≈2 s of effective ticks
        // (cont-map item 1: the mixing-seam belt the session-level gate cannot see).
        classRoutingActive = fireClassRoutingOn && !classRoutingFallback;
        if (classRoutingActive) {
            pilotStep.fireClassLane().enableClassRouting(fireClassRateMax, fireClassOverrides,
                    Math.max(1, (int) Math.round(2.0 * effectiveTickRate)));
        }
        fireLaneDischargeDetector = new GameplayEdgeEventDetector();
        // Per-round shadow fold of confessed onFire
        // births against the detector's discharges — observe-only; inert (all zeros)
        // unless the boot sweep latched FireBirthSink.enabled().
        onFireShadow = new OnFireShadowComparator();
        // Per-round emission-source merger — covered-mount
        // discharges ride confessed births; the comparator above keeps folding the RAW
        // detector stream (the shadow net stays a net, pre-merge by construction).
        onFireTruthMerger = new OnFireTruthMerger(onFireTruth);
        // Measure riders: observe-only shadows (escort-package
        // predicate + canister-flak defense slot); own-file per the growth-anchor law.
        canisterServeProbe =
                canisterProbeOn ? new CanisterFlakServeProbe() : null; // Default OFF
        // phase-dive rising-edge probe — gate log at anchor
        // deaths, dive-start detection + floaty-stall nudge, DEPARTED-override latch,
        // DIVE ctrl frames; own-file per the growth-anchor law.
        phaseDiveProbe = new PhaseDiveServeProbe();
        // ARC_HIT(9): the engine's own DamageListener seam names every
        // EMP-arc hit (victim, node point, split, flavor); own-file per the
        // growth-anchor law — PPICS carries field + drain + concat + meters only.
        arcHitProbe = new ArcHitServeProbe();
        critProbe = new CritMalfunctionServeProbe(); //
        asteroidLane = new AsteroidFieldServeLane(new AsteroidFieldServeLane.ReflectivePort(), round, mapHalf, asteroidSeed, //
                asteroidBumpCadenceTicks, asteroidRefreshTicks); // Its own knob (cc.server.asteroidBumpCadenceTicks)
        arcHitProbe.armShieldWindow(shieldedRow); // knob-gated type=shielded rows
        if (shieldedRow) {
            System.out.println("npeer-serve shielded_row armed=true knob=cc.server.shieldedRow");
        }
        /** knob-gated hit* tokens on dmg-taken (armed BEFORE the lazy install). */
        arcHitProbe.armHitCell(hitCellRow);
        if (hitCellRow) {
            System.out.println("npeer-serve hit_cell_row armed=true knob=cc.server.hitCellRow");
        }
        // ALWAYS minted at N: the T3 seat-loss consume is lane-INDEPENDENT; WIRE
        // emission of its frames stays eventLaneOn-gated at the compose below.
        despawnDetector = new EntityDespawnEventDetector();
        // Ship despawns published DEPARTED this round (id -> tick) — read
        // by the (f3) hulk fan to print loud when a corpse family arrives LATE for an
        // already-published DEPARTED (instrumentation only; same-tick evidence corrects
        // inline at the observe). Ship-scale per round, never pruned.
        departedShipDespawnTickById = new HashMap<>();
        // Second same-tick evidence for that correction — UNHEALTHY
        // repository removals of NON-growth ids deposited at the (d2) growth drain (a
        // hulkless dweller kill mints no corpse family, so the despawn rule alone published DEPARTED).
        despawnReasonEvidence = new DespawnReasonEvidence();
        // per-round MIRV split detector — pure fold over the
        // published snap's non-ship missile records; contentId resolves through the
        // bootstrap registry's key into the binding's per-spec isMirv cache
        // (fail-soft FALSE end-to-end). A later change widened the fold with the
        // binding's projectile-child-birth + end-cause-triage side channels (the
        // sabot BALLISTIC-children lane). Its events ride the compose below SEPARATE
        // from effectiveEdges: never through the OnFireTruthMerger (not a discharge
        // truth-source) and never through dispatchDischargeEdges (no decl FIFO).
        missileSplitDetector = new MissileSplitEventDetector(
                contentId -> binding.isMirvContentKey(contentRegistry.keyForOrNull(contentId)));
        // The arc-hit probe's missile last-impact memo joins the binding's
        // delist triage, so every MISSILE_END names its victim + shield verdict (MissileImpactMemo).
        binding.setMissileImpactMemo(arcHitProbe.missileImpacts());
        // per-round autofire-TARGET pick lane — poll+diff over SEAT
        // ships' autofire plugins, AUTOFIRE_TARGET (v9) on pick-change + slow keepalive.
        // Rides the compose below SEPARATE from effectiveEdges like the split detector:
        // never through the OnFireTruthMerger (not a discharge truth-source) and never
        // through dispatchDischargeEdges (no decl FIFO).
        afTargetLane = new AutofireTargetLane();
        // per-round Paladin-det event lane — mints
        // PALADIN_DET (v10) off the binding's PROJECTILE-read birth side channel.
        // Same compose law as its sibling event lanes (separate from
        // effectiveEdges; never through the merger or the decl FIFO).
        paladinDetLane = new PaladinDetLane();
        // The round's AIMP aim-point fan lane — per-round mint
        // resets the change-gate (first due tick re-emits every live seat's aim into
        // the fresh world); null = OFF knob, keeping every drain call site inert.
        aimFanLane = aimFanEveryTicks > 0
                ? new PilotAimFanLane(aimFanEveryTicks,
                        Math.round(SimTickRate.ticksPerSecond()))
                : null;
        gpeeDownEvents = 0;
        edspDownEvents = 0;
        seatDownFrames = 0;
        hkfrDownEvents = 0;
        // MISSILE_END per-round meters (by end-cause wire code).
        gpeeMissileEndIntact = 0;
        gpeeMissileEndDestroyed = 0;
        gpeeMissileEndFizzling = 0;
        gpeeMissileEndShield = 0;
        gpeeMissileEndNamed = 0;
        hkpsDownEvents = 0;
        hkcgDownEvents = 0;
        // HKCG capture skips (each = one bare ADD the client cuts
        // locally) — INFO row per skip below + hkcg_skips= on the counter tail.
        hkcgSkipEvents = 0;
        // Log law: hulk_pose_fan prints FIRST-per-family only (periodic keyframes
        // would spam); key = rootEntityId:deathTick (one wire id can front two corpse
        // families across a T4 remount).
        hulkPoseFamiliesLogged = new HashSet<>();
        // Log law: hulk_cut_geometry_fan prints FIRST-per-cut only (4 records per
        // cut); key = rootEntityId:deathTick:cutSeq (the client latch key).
        hulkCutGeometryCutsLogged = new HashSet<>();
        heldStreakTicks = 0;
        intentTakeAge.reset();
        // The N lane mints its OWN per-round registry — never
        // newSeatReservedRegistry(), whose RealEngineDemoRoster statics are populated
        // only by the real deploy lanes (game-free they are empty ⇒ a ceiling-0 band
        // whose first fly-in intern THROWS). Identity rides the hooks instead: the
        // authority-derived band + the previous wrap's reset identities.
        stableIdRegistry = new ShipNetworkIdRegistry(seatBandCeiling);
        // per-round RAPP append-log — frames AS SENT, replayed to late
        // joiners at the newly-live seam below (a joiner admitted after a wave_spawn
        // otherwise repeats the ghost match verbatim). Waves are round-local, so
        // a fresh list per round IS the round-boundary reset.
        // The cursors walked the
        // OLD log — clear them AND every flagged session's flag (a flag surviving the round =
        // a session that never receives a live RAPP again), zero the round meters. Normally a
        // no-op here: closeRound's reset-deploy block already reset them; this is
        // the teardown-path backstop (a last-session-dead round never reaches closeRound's fans).
        rappCatchup.resetRound(rappCatchupPort, rappCatchupPort,
                roundRosterAppendFrames == null ? 0 : roundRosterAppendFrames.size());
        roundRosterAppendFrames = new ArrayList<>();
        // W2b E4: the round's module-seat drain lane — minted only when the
        // MODULE verbs armed (fortress + bound mount hook — the twin
        // gate; the arbiter itself now always exists); null keeps every module call
        // site inert, the arming's own law.
        moduleLane = !moduleVerbsArmed ? null
                : new ModuleSeatServeLane(rebindArbiter, hooks.moduleMount(), door,
                        authority, seatWireIdBySeatId, waveSchedule, binding, pilotStep,
                        fireLaneDischargeDetector, afTargetLane, stableIdRegistry,
                        lastAnnouncedVariantBySeat, roundRosterAppendFrames, round,
                        censusVariantId);
        // W5: the round's grace engine lane — minted only when the policy is
        // ON; null keeps every grace call site inert (the moduleLane law), so an OFF
        // serve never even pumps. Per-round object = the wrap reset of its bookkeeping.
        // Constructed BEFORE the transfer lane, which takes it for the D5 PARKED-target
        // takeover pre-steps.
        graceLane = gracePolicy.off() ? null
                : new SeatGraceLane(authority, binding, pilotStep, door,
                        seatWireIdBySeatId, gracePolicy, round);
        // Arm site (ii) capture: a wrapped round re-deploys its ready seats at
        // t=0 (no fly_in exec) — remember that BEFORE the list resets; the arm itself
        // must wait for waveSchedule.newRound() below (which clears the latch).
        boolean seatsPreMountedAtRoundOpen = !pendingRoundPreInterns.isEmpty();
        for (NPeerHooks.FlyInResult redeploy : pendingRoundPreInterns) {
            stableIdRegistry.internReserved(redeploy.stableId(), redeploy.wireId());
            // The gated reset re-deployed this seat's hull at t=0 (a
            // fresh ship object) — install the DD1 shim on it exactly like the mid-round
            // fly-in lane above, so shim coverage stays UNIFORM across both mount paths.
            binding.installFastTimeReinjectShim(redeploy.wireId());
            // The reset-deploy tenure edge is
            // recorded HERE, on the step that serves this round — the wrap's step died at the mint
            // above — with the FIELDED id; + the build-time disarm the reset path never ran.
            pilotStep.recordDeclaredGroups(redeploy.wireId(), redeploy.variantId(),
                    declaredGroupSlotSetsFor(variantCatalogOrNull, redeploy.variantId()));
            int autofireOff = binding.disarmBuildTimeAutofire(redeploy.wireId());
            System.out.println("npeer-serve round_open_autofire_disarm seat="
                    + seatIdByWireId.get(redeploy.wireId()) + " wire_id=" + redeploy.wireId()
                    + " groups_off=" + autofireOff);
        }
        // A redeployed seat that
        // fields its carried PICK (≠ its descriptor row) announces nothing at the wrap (believed
        // == fielded, the announce lands in the DYING round's replay log), so THIS round's
        // late-joiner replay would let a post-wrap joiner believe the DESCRIPTOR hull for that
        // seat. Fan the pick's RAPP row AND append it to the fresh replay log (why:
        // a session mid catch-up at the wrap had its cursor abandoned and the live fan skipped it,
        // so it may never have seen the row — a known-id duplicate elsewhere is the harmless path).
        List<RosterAppendFrameCodec.AppendRow> carriedPickRows = new ArrayList<>();
        for (NPeerHooks.FlyInResult redeploy : pendingRoundPreInterns) {
            if (redeploy.variantId() != null && redeploy.descriptorVariantId() != null
                    && !redeploy.variantId().equals(redeploy.descriptorVariantId())) {
                carriedPickRows.add(new RosterAppendFrameCodec.AppendRow(redeploy.wireId(),
                        redeploy.side(), redeploy.variantId(), redeploy.shipName(),
                        redeploy.crPerMille()));
            }
        }
        // The wrap's deferred seat re-points ride THIS fan,
        // after the MRST. A carried-pick row for the same wire id wins: both are built from the SAME
        // reset fly-in (identical bytes), so one row suffices. Cleared here — never leaks a round.
        pendingWrapRepoints.removeIf(
                r -> carriedPickRows.stream().anyMatch(c -> c.wireId() == r.wireId()));
        carriedPickRows.addAll(pendingWrapRepoints);
        pendingWrapRepoints.clear();
        // The round-open tracker seed — a seat whose
        // last announced row ≠ its descriptor and that carried no row (vacant at the wrap)
        // seeds that row, so a post-wrap joiner believes what the survivors hold.
        int trackerRows = SeatAnnounceTracker.addTrackerSeedRows(
                lastAnnouncedVariantBySeat, carriedPickRows);
        if (!carriedPickRows.isEmpty()) {
            fanRosterAppendChunks(door, carriedPickRows, roundRosterAppendFrames);
            System.out.println("npeer-serve round_pick_rows_replayed round=" + round
                    + " rows=" + carriedPickRows.size()
                    + " carried=" + (carriedPickRows.size() - trackerRows)
                    + " tracker=" + trackerRows
                    + " (carried picks + wrap re-points + the tracker's"
                    + " vacant-seat rows ride this round's late-joiner replay)");
        }
        // pre-seed the round's deploy-ledger hulls so their wire ids
        // equal the dense descriptor ordinals BY CONSTRUCTION. Without this, non-seat
        // ids mint at the FIRST CAPTURE in engine-repo enumeration order — and fly-in
        // deploy() speed-sorts its batch before spawning, so
        // near-equal hulls land in a per-run order (the fortress twin-glimmer swap)
        // while the client resolves variant BY numeric wire id (PRCP variantById).
        // networkIdFor is idempotent and the ledger is fresh per round (gated reset
        // redeploys; NPeerHooks javadoc); keys the seat lanes reserved above resolve
        // to their band ids untouched. A null seam is the back-compat ctor's legal
        // no-hook shape (contract fakes; engineBornGrowth=true fail-fasts null at
        // boot) — the interface default List.of() only covers hooks that BIND a seam.
        NPeerHooks.GrowthSeamHook deployLedgerSeam = hooks.growthSeam();
        if (deployLedgerSeam != null) {
            for (String ledgerStableId : deployLedgerSeam.deployedHullStableIds()) {
                stableIdRegistry.networkIdFor(ledgerStableId);
            }
        }
        // per-round growth state — seam listener installed on THIS
        // round's CURRENT repository (the gated reset mints a fresh one; round 0's came
        // from the boot deploy), fresh reclassifier, and the known-keys set that
        // classifies engine-born at the drain. Interned-ness CANNOT classify there:
        // capture first-sight-interns everything inside publishNextTick before the
        // drain runs — so the deploy-lane keys (pre-interns here, fly-ins at (c),
        // waves at (c2)) are tracked explicitly instead.
        growthReclassifier = null;
        growthKnownKeys = null;
        growthBornKeys = null;
        if (engineBornGrowth) {
            growthReclassifier = new GrowthDespawnReclassifier();
            growthKnownKeys = new HashSet<>();
            growthBornKeys = new HashSet<>();
            for (NPeerHooks.FlyInResult redeploy : pendingRoundPreInterns) {
                growthKnownKeys.add(redeploy.stableId());
            }
            // Rig catch: the round's t=0 deploy-ledger hulls (descriptor
            // seats + AI rows) are deploy-lane, not engine-born — seed them or the
            // first drain growth-announces hulls clients already minted from the
            // descriptor contract.
            growthKnownKeys.addAll(hooks.growthSeam().deployedHullStableIds());
            hooks.growthSeam().install();
        }
        // W3 L1b-ii: the round's command-transfer lane — ALWAYS
        // constructed (its vacate consume + tick heartbeat + transfer drain are
        // serve-wide law, fortress or not); pumped pre-advance FIRST below. A null
        // hooks.transferRekey() (contract fakes) keeps the drain fail-soft.
        // W3 L2: the round's pod lane — always minted (inert one-check
        // until the P3 dock-gated drain spawns pods; the spawner is the ONLY
        // server-side pod mint, vanilla's own trigger being headless-NPE).
        // L4 S1: constructed HERE, after the growth block, so the serve
        // lane can thread the round's growthKnownKeys for the mint-branch pod dedup
        // (nullable — engineBornGrowth off; first lane use is far below, inert move).
        // The spawner rides a local so the round-0 warm below can
        // prefetch through the SAME instance's handle cache the lane will use.
        TransferPodSpawner podSpawner = new TransferPodSpawner();
        podLane = new TransferPodLane(
                podSpawner, transferPodTimeoutTicks, round);
        transferLane = new CommandTransferServeLane(
                rebindArbiter, door, pilotStep, seatWireIdBySeatId, authority, binding,
                fireLaneDischargeDetector, afTargetLane, stableIdRegistry,
                lastAnnouncedVariantBySeat, roundRosterAppendFrames, graceLane,
                podLane, hooks.transferRekey(), transferTargets, growthKnownKeys,
                round, censusVariantId);
        // The stall-tag law: the transfer lane's convicted stall events
        // (pod_spawn / transfer_commit) self-name in worst_frame causes= rows.
        transferLane.setStallTagLedger(worstFrames);
        // W12 voice L2: the per-round anchor lane — binds the position
        // slot to this round's seats; its onTick is the ONE on-frame voice cost.
        voiceLane = voice == null ? null
                : new VoiceAnchorLane(voiceSlot, seatWireIdBySeatId, podLane);
        // The /podtest driver + port are one per round;
        // a scenario still RUNNING settles FAIL "round ended" to its receipt target before the re-mint.
        String podRoundEnd = podScenario == null ? null : podScenario.roundEnded(lastSimTick);
        if (podRoundEnd != null && podScenario.receiptTargetOrNull() != null) {
            waveReceipt(door, podScenario.receiptTargetOrNull(), podRoundEnd);
        }
        podPort = new PodScenarioEnginePort(podLane, podSpawner, rebindArbiter,
                seatWireIdBySeatId, authority, binding, stableIdRegistry, door,
                growthKnownKeys, roundRosterAppendFrames, transferLane, round);
        podScenario = new PodScenarioDriver(podPort, round, effectiveTickRate);
        pendingRoundPreInterns = List.of();
        // matchOver ARMING is source-side — both triggers suppressed until ≥2
        // fighting sides were seen alive this round (a fly-in-populated battlefield must
        // not end the round before the second side arrives).
        source =
                EngineBackedLiveWorldSnapshotSource.stableRosterMatchOverArmed(
                        pilotStep, binding, config.framesPerSession(),
                        Math.max(config.shipCount(),
                                Math.max(descriptorSeatCount, rosterHullBound)),
                        config.fixedDt(),
                        contentRegistry, stableIdRegistry, Map.of(), null);
        if (!respawnPolicy.disabled()) {
            // Sibling rule: the SAME roster-0 hold on the N lane
            // (fixes the npeer twin of the legacy mutual-kill residual at the same root).
            // Gated on the policy — npeer arms the seat machine even under disabled, but
            // a disabled-policy serve must keep the pre-hold latch byte-identical.
            if (MatchDescriptors.holdsEmptyField(npeerDescriptor.shape())) {
                // PvP fields no station, so an all-gone
                // field would latch the roster-0 end and exit the serve clean (never relaunched).
                // The hold answers true: the next joiner flies in on the same round (same match
                // id, pause and table). One log per empty window (the source consults this only
                // on an empty, armed tick; a gap in the ticks opens a new window). Operator / F10 /
                // frame-budget ends still break the frame loop first.
                long[] lastHeldTick = {Long.MIN_VALUE};
                source.setRespawnAwaitingRemount(() -> {
                    long tick = lastSimTick + 1;
                    if (tick != lastHeldTick[0] && tick != lastHeldTick[0] + 1) {
                        System.out.println("npeer-serve roster0_held tick=" + tick
                                + " respawn_pending=" + authority.anySeatAwaitingRemount()
                                + " (pvp empty field)");
                    }
                    lastHeldTick[0] = tick;
                    return true;
                });
            } else {
                source.setRespawnAwaitingRemount(authority::anySeatAwaitingRemount);
            }
        }
        // Hand the serve's shared cell book to this round's
        // fresh source (post-mint setter idiom; null = cells dark, source no-ops).
        source.setArmorCellBook(armorCellBook);
        // Arm periodic corpse-pose keyframes on the source's fracture
        // detector (post-mint, pre-first-observe — the setRespawnAwaitingRemount idiom).
        source.setHulkPoseCadence(hulkPoseEveryTicks);
        // Arm the impulse burst cadence (same idiom; change-gated).
        source.setHulkPoseBurst(hulkPoseBurstCadenceTicks);
        // Arm the cut-geometry capture rider (same idiom; capture is
        // OFF at mint so undrained lanes never accumulate records).
        source.setHulkCutGeometry(hulkCutGeometryOn);
        // the god-mode replica bit, re-armed on EVERY round's fresh source.
        source.setGodAppliedWire(adminDebug::godAppliedWirePredicateOrNull);
        // D3 — the travel-drive fire-lock bit, re-armed on
        // EVERY round's fresh source like the god arm (the binding re-resolves the seat hulls
        // per read; null predicate ⇒ no per-ship fold work).
        source.setDriveLockWire(binding::driveLockWirePredicateOrNull);
        if (waveSchedule != null) {
            // (the round-boundary category park once lived here; the admin verb now
            // applies immediately at its drain — see SET_WAVE_CATEGORY.)
            // Fresh per-round wave state (the
            // fresh-source-per-round idiom — a round wrap re-runs the whole schedule)
            // + the wave-clear trigger (same post-mint setter idiom). The enemy-live
            // FEED always arms — the spawn-on-clear chain is the continuous fight's
            // pacing; the knob gates only the matchOver LATCH (the per-clear reset
            // option).
            waveSchedule.newRound();
            // The live-roster ratio, re-installed per round (this serve's
            // door, the round's fresh DP memo) when match.waveRosterFullDp APPLIES (fortress +
            // SCALED; AUTO + knob already killed the boot in FortressServeBootChecks.runIfFortress;
            // a non-fortress shape reads false = IGNORED like the sibling knobs).
            if (WaveSchedule.requireRosterFullDpMode(npeerDescriptor.shape(), fortressDpTotal,
                    waveRosterFullDp)) {
                liveRosterDp = new cc.starsector.server.match.LiveRosterDp(
                        authority::occupiedSeatIds, door::seatFieldedVariantOrNull,
                        door::seatVariantOverrideOrNull, this::descriptorSeatVariantOrNull,
                        cc.starsector.server.bridge.FortressServeBootChecks.engineRawDpOrNaN(),
                        MatchDescriptors.ZERO_DP_SEAT_EXEMPT_VARIANT_IDS, waveRosterFullDp,
                        waveRosterMultiplier);
                // The supplier the wave stage reads is wrapped so the
                // ratio it CONSUMED lands in the record (change-only; the round's first read always).
                final cc.starsector.server.match.LiveRosterDp rosterDp = liveRosterDp;
                final int rosterRound = round;
                rosterRowPending = true;
                // A re-simulation INJECTS the recorded ratio (the live
                // derivation reads the door's pick store, which a replay leaves untouched) and re-records it.
                java.util.function.DoubleSupplier rosterSupplier = replayFeed == null
                        ? () -> recordedRosterRatio(door, rosterDp, rosterRound)
                        : () -> recordRosterValue(door, replayFeed.rosterRatio(rosterRound, lastSimTick + 1,
                                rosterDp::ratio), rosterRound);
                waveSchedule.setLiveRosterRatio(rosterSupplier);
                lastStageReceiptLogged = null;
                // The host's share modifier rides the
                // round row; ratio = clamp(multiplier x seat DP / full_dp, floor, 1).
                System.out.println("npeer-serve wave_dp_basis mode=roster round=" + round
                        + " full_dp=" + waveRosterFullDp
                        + " floor=" + cc.starsector.server.match.LiveRosterDp.FLOOR
                        + " multiplier=" + liveRosterDp.multiplier()
                        + " (each curve wave stages its longest prefix under"
                        + " ratio x static target; the compiled chain stays the cap)");
            }
            if (seatsPreMountedAtRoundOpen) {
                // Arm site (ii): ready seats are mounted at round-open on a
                // wrapped round — the first-seat-mount clock starts at tick 0.
                waveSchedule.armTimer(0L);
            }
            source.setWaveClearRoundEnd(waveSchedule.waveSide(),
                    waveSchedule::observeEnemyLive, waveSchedule::drained,
                    waveClearLatchRoundEnd, waveClearLatchTag,
                    // The latch requires the zero SUSTAINED the same hold
                    // the spawn side uses (no instant DEFEND victory under fading
                    // shard children).
                    waveSchedule.clearHoldTicks());
        }
        if (stationRowPresent) {
            // The engine-native LOSE seam — the round ends when
            // the fortress BASE hull turns hulk (same post-mint setter idiom as every
            // sibling trigger above, so the stableRoster(...) factory signatures stay
            // put). The hook's checked IOException is re-thrown UNCHECKED here because
            // the source's trigger surface is a plain BooleanSupplier: either way the
            // read failure is FATAL and loud — a broken lose seam must never degrade
            // into "base still alive".
            source.setStationLost(() -> {
                try {
                    return hooks.station().baseLost();
                } catch (IOException stationReadFailure) {
                    throw new java.io.UncheckedIOException(
                            "station base-hulk lose seam read failed — refusing to keep"
                                    + " the round alive on an unreadable base",
                            stationReadFailure);
                }
            }, stationRowSide);
        }
        binding.configureSimTickRate(effectiveTickRate);
        EngineCombatBinding.MapBoundsResult mapBounds = binding.applyCombatMapBounds(mapHalf);
        System.out.println("npeer-map-bounds round=" + round
                + " applied=" + mapBounds.applied()
                + " half=" + mapBounds.half()
                + " width=" + mapBounds.widthBefore() + "->" + mapBounds.widthAfter()
                + " height=" + mapBounds.heightBefore() + "->" + mapBounds.heightAfter()
                + (mapBounds.error().isEmpty() ? "" : " error=" + mapBounds.error()));
        // Round-0 corpse-read warm on ONE live roster ship
        // (post-roster-deploy, pre-frame-0) — pre-pays the first-corpse engine-internal
        // bounds/segment/point classload bill; read-only by the static-RE
        // verdict (the binding helper's doc). Distinct log prefix: never a
        // round-line field, so no sweep/verdict parser folds it.
        if (round == 0) {
            System.out.println("npeer-serve hulk_read_warm "
                    + source.warmRemnantReadPaths());
            // Capture-lane classload/accessor warm — same
            // pre-frame-0 slot; pre-pays the first-sighting bill for every point-entity
            // class the capture discriminates on (in one recorded serve the t2274 first DamagingExplosion
            // was the only mid-combat >100 ms stall; CustomCombatEntity never-seen).
            System.out.println("npeer-serve capture_warm "
                    + source.warmCaptureLaneClasses());
            // Transfer-pod-lane warm — same
            // pre-frame-0 slot. Prefetch-only (no engine add/remove); the codec
            // first-touch covers the mint branch's first XFER encode, a
            // cost the spawner-side warm can't reach. Values are the minimal legal
            // BEGIN shape (seat/from/to=1, pod=0); the frame goes nowhere.
            String codecWarm;
            try {
                CommandTransferServeLane.encodeTransferFrame(
                        CommandTransferFrameCodec.KIND_BEGIN, 1L, 1L, 1L, 0L,
                        CommandTransferFrameCodec.REASON_NONE);
                codecWarm = "ok";
            } catch (RuntimeException codecWarmFailure) {
                codecWarm = "err=" + codecWarmFailure;
            }
            System.out.println("npeer-serve pod_spawn_warm "
                    + podSpawner.warmSpawnCycle() + " codec=" + codecWarm);
        }

        // The first published snapshot stamps simTick 0 (fresh source
        // per round), so -1 makes the frame-0 fly-in check seatsDueForFlyIn(0) and the
        // pre-first-publish baseline stamp max(lastSimTick,0) exact.
        lastSimTick = -1L;
        firstFlyInDone = false;
        // (b) the skip verb's deferred corpse sweep due tick (-1 =
        // unarmed; round-scoped, so a round wrap discards a pending sweep) and (a) the
        // wave_clear_hold transition-log latch (last seen clearHoldSinceTick).
        waveVerbs.sweep.due = -1L; // The slots live in the carved verb lane
        // ONE follow-up sweep slot — a killing press while a sweep is
        // already armed keeps the EARLIER due tick (never slides it past the 2.0 s
        // child delay) and books its own corpses a second sweep after the sighting window.
        waveVerbs.sweep.followUp = -1L;
        waveClearHoldSinceLast = -1L;
        waveCycleLast = 1; // F1: newRound() resets the schedule's cycle to 1
        frameTargetNanos = (long) (config.fixedDt() * 1_000_000_000.0d);
        // re-anchorable pacer — deadline(f) = anchor + (f - anchorFrame)
        // * interval. A sim-speed change re-anchors at NOW + re-derives the interval
        // (no catch-up sprint: past debt is forgiven at the re-anchor, the
        // negative-Δ posture). interval == frameTargetNanos at speed 1.0 — byte-
        // identical schedule to the pre-1280 absolute pacer.
        pacerAnchorNanos = -1L;
        pacerAnchorFrame = 0;
        pacerIntervalNanos = (long) (frameTargetNanos / simSpeed);
        simSpeedChanges = 0;
        // per-round governor reset — window+dwell restart, so a round
        // boundary can never flap the speed. The roundStartGate hold serves no
        // frames, so its wall time never reaches the window: absorb-via-round-reset;
        // the first W+dwell frames per round are ungoverned BY
        // DESIGN (rider (b) — not a bug at watch time).
        autoSpeedLaw.reset();
        autoSpeedChanges = 0;
        stallFrames = 0;
        detSplitRows = 0; // Capped det_split rows emitted this round
        detEdgesSplitRows = 0; // Capped det_edges_split rows (the finer edges split)
        overrunFrames = 0;
        overrunMaxNanos = 0L;
        // Sprint-debt meters: the
        // CombatClock vocabulary finally metered on the LIVE pacer — debtNowNanos ≙
        // accumulator(), sprint frames ≙ catchUpCount() (adapter/CombatClock.java's
        // stubs + FixedStepServerLoop's hard-0s stay untouched; overloaded() = P3,
        // parked with the adaptive-tickrate WAIT). An episode = a maximal run of
        // consecutive in-debt frames (deadline already passed at the pacer); wall
        // lengths reuse the pacer's own arithmetic — zero extra syscalls per frame.
        sprintEpisodes = 0;
        sprintRunFrames = 0;
        sprintRunMaxFrames = 0;
        sprintRunStartNowNanos = 0L;
        sprintWallMaxNanos = 0L;
        debtNowNanos = 0L;
        // Ticks whose SNAP fan was withheld by the
        // cc.net.downEveryTicks cadence divisor (CTRL + record stay every-tick).
        snapFanSkippedTicks = 0;
        fanPassMaxNanos = 0L;   // B5 E1(b): slowest whole per-tick send fan this round
        sendSlowestNanos = 0L;  // B5 E1(b): slowest single sendTickToSession this round
        prevFrameNanos = -1L;   // last frame's work+tail, the
                                     // frame_overrun row's attribution term (-1 = none yet)
        workFrames = 0;          // Utilization meter: EVERY served frame
        workNanosTotal = 0L;    // loop-body work outside the pacer sleep
        workMaxNanos = 0L;      // worst single frame's work
        // Measure tallies at N: reset in BoostSystemSampleLane.
        boostSystemLane.resetRound(config);
        matchOver = false;
        // D6 O-0 end-round latch (round-scoped): forces the round boundary at (m) — the
        // EXISTING wrap then fires (gated reset → MRST fan → baseline burst), keeping the
        // round-barrier law (roundId bumps at MRST emission, once per BOUNDARY — a raw
        // mid-frame fanMatchReset would bump it with no boundary). On the LAST round the
        // wrap condition is false, so end-round there ends the serve (documented).
        operatorEndRound = false;
        framesServed = 0;
        // Round-0 receipt pre-warm — the tick-3600 heartbeat
        // receipt was the FIRST-EVER PilotRoundSummary build + roundCounterTail run
        // (~60 accessor/static sites read nowhere else) and cost a measured 239 ms
        // pre-bracket stall. Build one summary + tail here from the
        // freshly-minted all-zero collaborators and DISCARD both — pure reads, zero
        // engine contact, pre-pacer-anchor so no served frame pays. NEVER println
        // the tail: watch-verdict.ps1 folds the LAST unclosed-round round_teardown
        // row into its verdict sums, so an emitted warm row would poison every
        // walk-away watch verdict.
        if (round == 0) {
            PilotRoundSummary warmSummary = new PilotRoundSummary(
                    round, framesServed, matchOver,
                    pilotStep.framesApplied(), pilotStep.movementCommandsIssued(),
                    pilotStep.facingPins(), pilotStep.aimPins(),
                    pilotStep.systemActivations(), pilotStep.ventsIssued(),
                    pilotStep.firesIssued(),
                    pilotStep.declarationsReceived(), pilotStep.declarationsServed(),
                    pilotStep.declarationsExpired(),
                    overrunFrames, overrunMaxNanos,
                    workFrames, workNanosTotal, workMaxNanos,
                    pilotStep.retreatsIssued(), pilotStep.targetApplies(),
                    pilotStep.edgeRecovered(), pilotStep.edgeExpired(),
                    pilotStep.edgeSwitchDisarmed(), pilotStep.shieldHolds(),
                    pilotStep.edgeClampDropped(), pilotStep.systemCancelPresses(),
                    pilotStep.tubeOrdinalConformed(),
                    sprintEpisodes, sprintRunMaxFrames, sprintWallMaxNanos,
                    0L, snapFanSkippedTicks,
                    pilotStep.cloakToggles(), pilotStep.ventRePresses(),
                    pilotStep.retreatReasserts(), pilotStep.retreatRearmSkips(),
                    pilotStep.declPressesIssued(), pilotStep.declPressWaitTicks(),
                    // Warm row is discarded — NEVER drain the adopt-miss
                    // counter here (reset-on-read would eat the round's misses).
                    0, 0, "-", 0L, 0,
                    pilotStep.retreatEdgeQuarantined(),
                    0L, // Same law — never drain the pre-advance meter here
                    pilotStep.declarationsServedByCredit(), // cumulative read, no drain
                    // Credit ledger (cumulative reads, no drain)
                    pilotStep.sumApplyStates(PilotApplyState::continuationCreditsMinted),
                    pilotStep.sumApplyStates(PilotApplyState::continuationCreditsExpiredUnspent),
                    pilotStep.sumApplyStates(PilotApplyState::continuationCreditsCapped),
                    pilotStep.sumApplyStates(PilotApplyState::creditFenceRefusals),
                    pilotStep.sumApplyStates(PilotApplyState::dischargeUnmatchedNonburst),
                    pilotStep.sumApplyStates(PilotApplyState::lastShotCreditsMinted), // credits minted on a burst's last shot
                    pilotStep.sumApplyStates(PilotApplyState::edgeExpiredGated), //
                    pilotStep.sumApplyStates(PilotApplyState::cloakDiveFreezeTicks), //
                    pilotStep.sumApplyStates(PilotApplyState::retreatDiveFreezeTicks), // bit-9 apply ticks frozen by a dive
                    pilotStep.sumApplyStates(PilotApplyState::declLockWaitTicks), //
                    pilotStep.fireLockReadErrors(), // fire-lock reads that threw
                    pilotStep.pilotMarkWrites(), // pilot-mark meters
                    pilotStep.pilotMarkClears(),
                    pilotStep.abilityEdgeQuarantined());
            String warmTailDiscarded = DownLaneSeams.roundCounterTail(warmSummary, frameTargetNanos,
                    fanPassMaxNanos, sendSlowestNanos, door, pilotStep,
                    fireLaneDischargeDetector, downComposer, peerGrade,
                    onFireShadow, onFireTruthMerger, afTargetLane, paladinDetLane,
                    arcHitProbe, gpeeDownEvents,
                    gpeeMissileEndIntact, gpeeMissileEndDestroyed,
                    gpeeMissileEndFizzling, gpeeMissileEndShield, gpeeMissileEndNamed,
                    edspDownEvents, seatDownFrames, authority.seatRecordsCoalesced(), hkfrDownEvents,
                    hkpsDownEvents, hkcgDownEvents, hkcgSkipEvents,
                    source.hulkPoseImpulseTriggers(),
                    source.hulkPoseBurstEmits(),
                    source.hulkObserveNsMax(), source.hulkObserveNsAvg(),
                    stallFrames, simSpeedChanges, autoSpeedChanges, aimFanLane,
                    stagedRefusedUnmounted, intentTakeAge);
            if (warmTailDiscarded.isEmpty()) {
                throw new IllegalStateException(
                        "receipt pre-warm produced an empty tail (impossible)");
            }
        }
    }

    /**
     * Round-start gate (leg 5): the first-attach hold — round 0 waits (pumping
     * intent-take + combat_ready itself) until the FIRST occupied seat latches combat_ready, an
     * operator end verb, or {@code clientWaitMillis}; rounds > 0 and a disarmed gate fall through.
     * Every exit of its {@code while (true)} is internal; the interrupt {@code throw} propagates.
     */
    private void holdRoundStartGate(PilotIntentDatagramServer door, int round) throws IOException {
        // Round-start gate, REWRITTEN to first-attach (it
        // supersedes the all-attach hold): hold
        // round 0 (the pacer anchor + all sim advance) only until the FIRST occupied
        // seat latches combat_ready — that attach IS round start, so player 1's
        // float-then-snap (no authoritative anchor while the gate held
        // for player 2) dies structurally. Later seats DROP IN to the running round:
        // the (j) latch fires their fly-in at (c), which fields ONLY the new seat's
        // hull (midDeploySeat spawns ONE member via the engine's own spawnFleetMember;
        // no other hull is touched — verified at the rewrite). The (j) latch fires on
        // a seat's first TAKEN intent, so the hold pumps intent-take +
        // recordCombatReady itself — holding without pumping deadlocks (nothing else
        // drains the deposit slot before the frame loop runs). Round >0 wraps
        // re-deploy ready seats at t=0 (per-MATCH latch) — round 0 only. Timeout
        // (clientWaitMillis) proceeds = the pre-gate behavior, logged.
        if (roundStartGate && round == 0) {
            if (replayFeed != null) {
                replayRoundStartGate(door, round);
                return;
            }
            long gateStartNanos = System.nanoTime();
            long gateBoundNanos = config.clientWaitMillis() * 1_000_000L;
            boolean gateReady = false;
            int gatePumpedIntents = 0;
            // The 1-based pass ordinal the gate TAKES rows carry.
            int gatePass = 0;
            while (true) {
                gatePass++;
                // The custom-variant drain
                // lives in the tick band, which does not run while this gate holds — a
                // joiner's outbox VSUB (sent right after WELCOME, BEFORE its first intent)
                // sat deposited until tick 0 and its held pick seeded AFTER the tick-0
                // fly-in. Drain here too (same sim thread = the registry's one writer) so
                // the ACCEPT ≺ seed ≺ first fly-in order holds for player 1 as well.
                if (customLaneOrNull != null) {
                    customLaneOrNull.drainTick(round, -1L, MatchRecordRows.BAND_GATE,
                            recordRowsArmed ? rowSink : null);
                }
                takes.reset();
                for (PilotIntentDatagramServer.PeerSession session : door.liveSessions()) {
                    PilotIntentFrame intent = session.takeDepositedIntentOrNull();
                    if (intent != null) {
                        gatePumpedIntents++;
                        if (authority.recordCombatReady(session.seatId())) {
                            System.out.println("npeer-serve combat_ready seat="
                                    + session.seatId() + " round=" + round
                                    + " frame=0 tick=-1"
                                    + " (first pilot intent at the round-start gate;"
                                    + " seat deployable from here)");
                        }
                        // The SAME
                        // isMounted gate as the (j) take — at the round-start hold no seat holds a
                        // hull yet (the fly-in reads deployableSeatIds; R6's pre-fly-in refusal
                        // still flew in), so the fan's "only seats with a live hull" law holds
                        // without a round-0 exception. Behavior change:
                        // a frame staged HERE used to be held-replayed from frame 0 by the
                        // pilot step (the phantom round-N press — a pre-mount
                        // USE_SHIP_SYSTEM deposit fired at fly-in); refusing it drops that press.
                        // The client's next deposit is taken at the (j) site once the seat mounts,
                        // so a HELD key still activates on the first mounted tick.
                        boolean gateStaged = authority.isMounted(session.seatId());
                        if (gateStaged) {
                            pilotStep.stage(intent);
                        } else {
                            stagedRefusedUnmounted++;
                        }
                        if (recordRowsArmed) {
                            addTakeRow(session, gateStaged, intent);
                        }
                    }
                }
                // ONE gate TAKES row per pass that took >= 1 intent.
                if (recordRowsArmed && takes.takeCount() > 0) {
                    door.recordRow(MatchRecordRows.KIND_TAKES,
                            takes.encode(MatchRecordRows.BAND_GATE, round, gatePass), -1L);
                }
                if (!authority.deployableSeatIds().isEmpty()) {
                    gateReady = true;
                    break;
                }
                // The operator file is polled HERE too — a
                // seat admitted with its pilot still in the mission brief holds this
                // gate, (a2) has not run yet, and an end verb written meanwhile was
                // CONSUMED and lost (flight #14 arm #1: "End match" did nothing). Either
                // end verb latches the D6 end-match exit (frame 0 serves, then the
                // goodbye fan — the lobby's "nothing started" reading); other verbs
                // carry into (a2) exactly as from the lobby wait.
                if (lobbyGate.getAsBoolean()) {
                    operatorEndMatch = true;
                    System.out.println("npeer-serve operator_" + lobbyGate.endVerb()
                            + "_pretick round=" + round + " (round-start gate held;"
                            + " goodbye fan after the first served frame)");
                    break;
                }
                if (System.nanoTime() - gateStartNanos >= gateBoundNanos) {
                    break;
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted at the round-start gate", ie);
                }
            }
            System.out.println("npeer-serve round_start_gate "
                    + (gateReady ? "ready" : operatorEndMatch ? "operator_end"
                            : "timeout_proceed")
                    + " occupied=" + authority.occupiedSeatIds().size()
                    + " deployable=" + authority.deployableSeatIds().size()
                    + " pumped_intents=" + gatePumpedIntents
                    + " held_ms=" + (System.nanoTime() - gateStartNanos) / 1_000_000L);
        }
    }

    /**
     * The round-start gate of a re-simulation — ONE pass per recorded
     * gate TAKES row, in file order (the recording's pass count is wall-clock-shaped, so the row SEQUENCE is
     * the key and the row's own ordinal is written back unchanged). Each pass runs the live gate's body over
     * the recorded takes: first the custom-variant drain (the recorded VSUB rows ahead of its TAKES row,
     * re-admitted and drained; the final call, with no TAKES row left, takes the trailing ones), then the
     * combat-ready latch, the staged bit RECOMPUTED
     * from this sim (a disagreement is counted, never forced) and the re-recorded row. No sleep and no
     * lobby or timeout exit: those inputs are not recorded. A take naming a seat no frame-0 session minted
     * (a session that took at the gate and left before frame 0) is counted and still served like any other take.
     */
    private void replayRoundStartGate(PilotIntentDatagramServer door, int round) throws IOException {
        int rows = 0;
        int declsIgnored = 0;
        MatchRecordRows.TakesRow row;
        while (true) {
            // Live drains at the top of each pass, before it takes, so
            // the VSUB rows ahead of this TAKES row are offered and drained BEFORE the row is claimed (the mint
            // serials then follow the recording's order); the call that finds no TAKES row left offers the
            // trailing rows. Nothing else deposits in a replay, so no second drain runs per pass.
            replayVsubs(replayFeed.gateVsubsBeforeNextTake(round), round, -1L, MatchRecordRows.BAND_GATE);
            row = replayFeed.takeGate(round);
            if (row == null) {
                break;
            }
            rows++;
            takes.reset();
            for (MatchRecordRows.TakeEntry e : row.takes()) {
                int seat = e.seat();
                if (!replayFeed.isMinted(seat)) {
                    replayFeed.noteAdmitUnminted(round, seat); // counted, and still served below
                }
                PilotIntentFrame intent = PilotIntentWireCodec.decodeDatagram(e.frame());
                if (authority.recordCombatReady(seat)) {
                    System.out.println("npeer-serve combat_ready seat="
                            + seat + " round=" + round
                            + " frame=0 tick=-1"
                            + " (first pilot intent at the round-start gate;"
                            + " seat deployable from here)");
                }
                boolean gateStaged = authority.isMounted(seat);
                if (gateStaged) {
                    pilotStep.stage(intent);
                } else {
                    stagedRefusedUnmounted++;
                }
                if (gateStaged != e.staged()) {
                    replayFeed.noteStagedMismatch(round, -1L, seat, e.staged(), gateStaged);
                }
                if (recordRowsArmed) {
                    addReplayTakeRow(e, gateStaged);
                }
            }
            declsIgnored += row.decls().size();
            if (recordRowsArmed && takes.takeCount() > 0) {
                door.recordRow(MatchRecordRows.KIND_TAKES,
                        takes.encode(MatchRecordRows.BAND_GATE, round, row.gateOrdinal()), -1L);
            }
        }
        if (declsIgnored > 0) {
            // the live gate never polls declarations, so a recorded one has no apply site here
            System.out.println("npeer-serve round_start_gate replay_decls_ignored count=" + declsIgnored);
        }
        System.out.println("npeer-serve round_start_gate replay rows=" + rows
                + " occupied=" + authority.occupiedSeatIds().size()
                + " deployable=" + authority.deployableSeatIds().size());
        if (authority.deployableSeatIds().isEmpty()) {
            System.out.println("npeer-serve round_start_gate replay_not_ready (the recording's gate ended on a"
                    + " timeout or an operator end, neither of which is recorded)");
        }
    }

    /**
     * re-admit recorded VSUB rows through the custom lane ONE at a time
     * in file order: offer the row's recorded submit frame, then drain the ledger to empty before the next, so
     * the mints run in the recording's order and each verdict row is written before the next row is offered.
     * The drain is stamped (round, tick, band) as live's drain at the calling site was, so this replay's own
     * reel records each verdict where the recording did. A frame naming another submit sequence than its row
     * is counted on the feed. A row with no custom lane refuses the reel: unreachable while the
     * custom-submissions knob sits inside the behavior hash a replay must match.
     */
    private void replayVsubs(List<MatchRecordRows.VsubRow> rows, int round, long tick, byte band) throws IOException {
        for (MatchRecordRows.VsubRow r : rows) {
            if (customLaneOrNull == null) {
                throw new IOException("reel VSUB row at round " + round + " seat " + r.seat()
                        + ": no custom lane (custom.submissions off)");
            }
            int frameSeq = customLaneOrNull.replayOffer(r.sessionId(), r.seat(), r.authorKey(), r.body());
            if (frameSeq != r.submitSeq()) {
                replayFeed.noteVsubOfferMismatch(round, r.seat(), r.submitSeq(), frameSeq);
            }
            while (customLaneOrNull.ledger().pendingCount() > 0) {
                if (customLaneOrNull.drainTick(round, tick, band, recordRowsArmed ? rowSink : null) == 0) {
                    break;
                }
            }
        }
    }

    /**
     * The Math.random() caller census's armed state, read ONCE at
     * class init (the premain arms before {@code main}), so an unarmed serve never loads the holder on the
     * sim thread at frame 0 — the per-frame cost when OFF is this one static boolean.
     */
    private static final boolean RNG_CENSUS_ON = cc.starsector.server.rngpin.CcRngRedirect.censusArmed();

    /**
     * Phase 4 (leg 6b): ONE frame of the round — pacer, session diff/admit,
     * operator poll ({@link #handleOperatorCommand}), pumps, seat fly-in, wave/probe/adhoc drains, publish, samplers, edge
     * observe/fold/merge, probes, despawn triage, hulk fans, compose/fan, UP drain, heartbeat,
     * work bookkeeping, auto-speed, ops feed, stall row, frame-exit checks, frame pacing.
     * Returns {@code false} where the loop body said {@code break} (operator end-match, operator
     * end-round, linger-done / non-linger match over) — the frame loop in {@link #serve} breaks
     * on it; {@code true} = next frame. The interrupt {@code throw}s propagate unchanged. Every
     * per-frame local stays local ({@code snap}/{@code tickEdges} included); the counters and
     * latches the round close reads were promoted at leg 4.
     */
    private boolean serveFrame(PilotIntentDatagramServer door, int round, int frame) throws IOException {
        // A re-simulation serves only the frames its reel holds — the
        // reel ending the round first ends it here (counted), before anything in the frame runs.
        if (replayFeed != null && !replayFeed.hasFrame(round, lastSimTick + 1)) {
            replayFeed.noteReelEndedRound(round, lastSimTick + 1);
            return false;
        }
        // re-arm the pinned RNG at the top of the round's
        // frame 0, before anything in the frame can draw. Discards every wall-clock-variable
        // draw between the roster reset and the first tick (lobby wait, the round-start gate's
        // passes, the round-0 warms). A no-op unpinned; handles were resolved at boot.
        if (frame == 0) {
            CombatRngRearm.rearm(round, "frame0");
            // A pinned round also restarts the engine's own interval timers right after the re-arm: the
            // engine is built once at boot, so its corner-cheese and combat-over timers carried a pre-round
            // clock and an unpinned duration into every round, re-rolling at a per-JVM tick — the pins-ON
            // proof's first two desyncs. Vanilla's fresh engine per battle
            // starts them here. Unpinned serves keep the engine untouched.
            if (CombatRngRearm.isArmed()) {
                int timers = binding.restartEngineIntervalTimers();
                System.out.println("rng_interval_restart round=" + round + " timers=" + timers);
            }
        }
        // (a) pacer: absolute deadlines anchored at round frame 0 (the anchor
        // frame skips the sleep/overrun arithmetic, else a phantom frame-0 overrun;
        // there is no first-exchange to anchor on — joins are mid-round-normal at N).
        if (config.paceToWallClock()) {
            if (frame == 0) {
                pacerAnchorNanos = System.nanoTime();
                pacerAnchorFrame = 0;
            } else {
                long deadlineNanos = pacerAnchorNanos
                        + (long) (frame - pacerAnchorFrame) * pacerIntervalNanos;
                long sleepNanos = deadlineNanos - System.nanoTime();
                if (sleepNanos > 0L) {
                    try {
                        Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while pacing npeer frames", ie);
                    }
                    // Deadline in the future ⇒ on schedule; any
                    // open sprint episode closes here (wall max already folded).
                    debtNowNanos = 0L;
                    sprintRunFrames = 0;
                } else if (sleepNanos < 0L) {
                    overrunFrames++;
                    if (-sleepNanos > overrunMaxNanos) {
                        overrunMaxNanos = -sleepNanos;
                    }
                    // measuredNow re-derived from values already in
                    // hand (pure arithmetic — the pacer's own nanoTime read).
                    debtNowNanos = -sleepNanos;
                    long measuredNow = deadlineNanos - sleepNanos;
                    if (sprintRunFrames == 0) {
                        sprintEpisodes++;
                        sprintRunStartNowNanos = measuredNow;
                    }
                    sprintRunFrames++;
                    if (sprintRunFrames > sprintRunMaxFrames) {
                        sprintRunMaxFrames = sprintRunFrames;
                    }
                    long runWallNanos = measuredNow - sprintRunStartNowNanos;
                    if (runWallNanos > sprintWallMaxNanos) {
                        sprintWallMaxNanos = runWallNanos;
                    }
                    // (38) stutter lane: per-frame overrun witness —
                    // tick-pins EVERY behind-schedule frame (the round line's
                    // overrun_max collapses a whole round to one number and let the
                    // frame-0 warm-up mask the felt mid-run stalls). prev_frame_ms =
                    // the PRIOR frame's work+tail (>budget ⇒ this overrun is fresh
                    // debt; small ⇒ repayment inside a sprint), sprint_frames =
                    // position in the episode (1 = onset). Emitted pre-work-bracket
                    // on the decoupled stdout lane (enqueue-only). Gated by
                    // cc.serve.overrunLog ((84) lean serve, 1290) — the
                    // >100 ms work_frame_stall witness stays unconditional.
                    if (overrunLog) {
                        System.out.println(String.format(java.util.Locale.ROOT,
                                "npeer-serve frame_overrun tick=%d round=%d frame=%d"
                                        + " overrun_ms=%.2f prev_frame_ms=%.2f"
                                        + " sprint_frames=%d",
                                lastSimTick, round, frame,
                                -sleepNanos / 1_000_000.0d,
                                prevFrameNanos < 0L ? 0.0d
                                        : prevFrameNanos / 1_000_000.0d,
                                sprintRunFrames));
                    }
                } else {
                    // sleepNanos == 0 exact: on schedule (episode closes).
                    debtNowNanos = 0L;
                    sprintRunFrames = 0;
                }
            }
        }
        // Work meter: everything below to (m) is body work — this lane
        // never blocks on a peer (spooled sends, non-blocking takes), so the measure
        // is pure serve cost, the capacity-truth utilization number.
        long frameWorkStartNanos = System.nanoTime();
        // per-bracket I/O baselines — stdout counter + ccwr
        // sink write+flush total (all threads; the DOWN write is this thread's).
        long witnessIoFrameStart = ServeStdoutIoMeter.nanosOf(ioMeter);
        long witnessCcwrFrameStart = door.sinkIoNanosTotal();
        // The command-transfer input journal, at the tick top BEFORE the
        // tick's first sim-thread arbiter touch (the operator poll's verbs, a join's in-flight replay, the
        // transfer lane's pump all run below). A re-simulation first re-applies the frame's recorded TRANSFER
        // rows on the arbiter (outcome compared); the drain then records every journaled row in deposit
        // order at PRE (the tick about to be served), so both reels carry the same rows. RESIDUAL, bounded
        // to one tick and not closed here: a reader-thread begin landing AFTER this drain but before a later
        // arbiter read of the same tick is acted on by live tick T yet stamped T+1, so the replay applies it
        // one tick later; closing it moves the begin onto the sim thread (a live-path design change).
        if (replayFeed != null) {
            replayFeed.replayTransfers(round, lastSimTick + 1, rebindArbiter);
        }
        for (MatchRecordRows.TransferRow journaled : rebindArbiter.drainTransferJournal(round)) {
            if (recordRowsArmed) {
                door.recordRow(MatchRecordRows.KIND_TRANSFER, MatchRecordRows.encodeTransfer(journaled,
                        MatchRecordRows.BAND_PRE, round), lastSimTick + 1);
            }
        }
        // (b) session observe: the door owns admission/death; the loop only diffs the
        // live set — newly-dead fold their stats, newly-live get liveness + the seat
        // BASELINE burst (one SEAT frame per minted seat at max(lastSimTick,0)).
        // A re-simulation first applies the frame's recorded session
        // edges at the replay door (file order: every DEAD before the LIVE rows that follow it), ONCE and
        // before any site below, so the diff re-derives the recording's newly-dead / newly-live sets.
        if (replayFeed != null) {
            replayFeed.applySessions(replaySessionDoor, round, lastSimTick + 1);
            // Then hand the door the sessions the recording's NEXT frame
            // ends for a kick / ban, so the replayed verb ends them at this frame's command point, as live did.
            door.replayStageCommandDeaths(replayFeed.commandDeathsAhead(round, lastSimTick + 1));
        }
        List<PilotIntentDatagramServer.PeerSession> live = door.liveSessions();
        Map<Long, PilotIntentDatagramServer.PeerSession> liveById = liveByIdScratch;
        liveById.clear();
        for (PilotIntentDatagramServer.PeerSession session : live) {
            liveById.put(session.sessionId(), session);
        }
        for (Map.Entry<Long, PilotIntentDatagramServer.PeerSession> prev : prevLive.entrySet()) {
            if (!liveById.containsKey(prev.getKey())) {
                PilotIntentDatagramServer.PeerSession dead = prev.getValue();
                if (recordRowsArmed) {
                    recordSessRow(door, MatchRecordRows.SESS_DEAD, dead, dead.departureReason(), "", round);
                }
                transferLane.markCensusDirty();
                System.out.println("npeer-serve session_dead id=" + dead.sessionId()
                        + " seat=" + dead.seatId() + " round=" + round + " frame=" + frame
                        + " reason=" + dead.departureReason() //
                        + " ctrl_spool=" + dead.ctrlSpoolDepth()
                        + " pinned_intent_drops=" + dead.pinnedIntentDrops()
                        + " pinned_decl_drops=" + dead.pinnedDeclarationDrops() //
                        + " stats=" + dead.stats()
                        // Per-seat lifetime bandwidth (appended fields)
                        + " tx_bytes=" + dead.bytesSent()
                        + " rx_bytes=" + dead.bytesReceived());
                // The dead session's send-gap total (once per death), read by watch-verdict R4
                System.out.println(SnapSendGapMeter.exportFinalRow(dead.snapSendGap, round, dead.seatId(), dead.shipId()));
            }
        }
        // Round-close counters are
        // UNREACHABLE on walk-away watches — nothing ends a round early post-B4(3b),
        // and the watch reaper hard-kills first (TerminateProcess: no shutdown hook
        // can save them) — so the LAST live session's death flushes them as an
        // explicit receipt. A receipt, NOT a round close: no round-end semantics
        // fire (the shadow's ±1 window stays open, no summary row is added, the
        // round keeps serving under the roster-0 hold). Re-arms per >0→0 transition
        // (a rejoin + re-walk-away emits a fresh receipt; sweeps take the last).
        // Distinct prefix ON PURPOSE: every round-line parser anchors on
        // '^npeer-intent-rounds round=' and SUMS across lines — this receipt must
        // never double-count there.
        // The original claim that reaper teardown is covered by
        // this same edge was FALSIFIED by a measured run — a zombie client JVM kept
        // seat 2's silence deadline fed after the player walked away (session death
        // needs the CLIENT PROCESS's sends to stop; a wedged client never stops),
        // so the transition never fired and the hard kill flushed nothing. Fix =
        // the reason=heartbeat twin below: same receipt, same build site (the two
        // reasons structurally cannot drift), every 60 s of served frames. The
        // round-close line still supersedes receipts for any round that closes;
        // for the trailing unclosed round, sweeps take the LAST receipt.
        boolean lastSessionDied = !prevLive.isEmpty() && liveById.isEmpty();
        boolean receiptHeartbeatDue =
                frame > 0 && frame % ROUND_RECEIPT_HEARTBEAT_FRAMES == 0;
        if (lastSessionDied || receiptHeartbeatDue) {
            long teardownDebtNanos = 0L;
            if (config.paceToWallClock() && pacerAnchorNanos != -1L) {
                teardownDebtNanos = Math.max(0L, System.nanoTime()
                        - (pacerAnchorNanos
                                + (long) (framesServed - pacerAnchorFrame)
                                        * pacerIntervalNanos));
            }
            PilotRoundSummary flush = new PilotRoundSummary(
                    round, framesServed, matchOver,
                    pilotStep.framesApplied(), pilotStep.movementCommandsIssued(),
                    pilotStep.facingPins(), pilotStep.aimPins(),
                    pilotStep.systemActivations(), pilotStep.ventsIssued(),
                    pilotStep.firesIssued(),
                    pilotStep.declarationsReceived(), pilotStep.declarationsServed(),
                    pilotStep.declarationsExpired(),
                    overrunFrames, overrunMaxNanos,
                    workFrames, workNanosTotal, workMaxNanos,
                    pilotStep.retreatsIssued(), pilotStep.targetApplies(),
                    pilotStep.edgeRecovered(), pilotStep.edgeExpired(),
                    pilotStep.edgeSwitchDisarmed(), pilotStep.shieldHolds(),
                    pilotStep.edgeClampDropped(), pilotStep.systemCancelPresses(),
                    pilotStep.tubeOrdinalConformed(),
                    sprintEpisodes, sprintRunMaxFrames, sprintWallMaxNanos,
                    teardownDebtNanos, snapFanSkippedTicks,
                    pilotStep.cloakToggles(), pilotStep.ventRePresses(),
                    pilotStep.retreatReasserts(), pilotStep.retreatRearmSkips(),
                    pilotStep.declPressesIssued(), pilotStep.declPressWaitTicks(),
                    pilotStep.declAdoptServes(), pilotStep.declAdoptRejects(),
                    pilotStep.declAdoptRejectReasons(),
                    cc.starsector.server.runtime.AdoptServeRegistry.roundCaptureMisses(),
                    pilotStep.declAdoptBatched(),
                    pilotStep.retreatEdgeQuarantined(),
                    // CUMULATIVE like its family neighbour
                    // adopt_serves — the reset-on-read roundWrites() under-read the grader's MAX
                    cc.starsector.server.bridge.WeaponChargeupPreAdvance.writes(),
                    pilotStep.declarationsServedByCredit(), //
                    // Credit ledger
                    pilotStep.sumApplyStates(PilotApplyState::continuationCreditsMinted),
                    pilotStep.sumApplyStates(PilotApplyState::continuationCreditsExpiredUnspent),
                    pilotStep.sumApplyStates(PilotApplyState::continuationCreditsCapped),
                    pilotStep.sumApplyStates(PilotApplyState::creditFenceRefusals),
                    pilotStep.sumApplyStates(PilotApplyState::dischargeUnmatchedNonburst),
                    pilotStep.sumApplyStates(PilotApplyState::lastShotCreditsMinted), // credits minted on a burst's last shot
                    pilotStep.sumApplyStates(PilotApplyState::edgeExpiredGated), //
                    pilotStep.sumApplyStates(PilotApplyState::cloakDiveFreezeTicks), //
                    pilotStep.sumApplyStates(PilotApplyState::retreatDiveFreezeTicks), // bit-9 apply ticks frozen by a dive
                    pilotStep.sumApplyStates(PilotApplyState::declLockWaitTicks), //
                    pilotStep.fireLockReadErrors(), // fire-lock reads that threw
                    pilotStep.pilotMarkWrites(), // pilot-mark meters
                    pilotStep.pilotMarkClears(),
                    pilotStep.abilityEdgeQuarantined());
            worstFrames.flushRound(round); // (104)(d): a truncated round confesses
            // Cause note AFTER the flush — flushRound resets per-tick causes,
            // so a note before it can never reach its own frame's observe().
            worstFrames.note(lastSimTick + 1, "round_receipt");
            System.out.println("npeer-serve round_teardown reason="
                    + (lastSessionDied ? "last_session_dead" : "heartbeat")
                    + " round=" + round + " frame=" + frame
                    + DownLaneSeams.roundCounterTail(flush, frameTargetNanos, fanPassMaxNanos,
                            sendSlowestNanos, door, pilotStep,
                            fireLaneDischargeDetector, downComposer, peerGrade,
                            onFireShadow, onFireTruthMerger, afTargetLane,
                            paladinDetLane, arcHitProbe, gpeeDownEvents,
                            gpeeMissileEndIntact, gpeeMissileEndDestroyed,
                            gpeeMissileEndFizzling, gpeeMissileEndShield, gpeeMissileEndNamed,
                            edspDownEvents, seatDownFrames, authority.seatRecordsCoalesced(), hkfrDownEvents,
                            hkpsDownEvents, hkcgDownEvents, hkcgSkipEvents,
                            source.hulkPoseImpulseTriggers(),
                            source.hulkPoseBurstEmits(),
                            source.hulkObserveNsMax(), source.hulkObserveNsAvg(),
                            stallFrames, simSpeedChanges, autoSpeedChanges,
                            aimFanLane, stagedRefusedUnmounted, intentTakeAge)
                    + rappCatchup.roundTokens(roundRosterAppendFrames) //
                    // The receipt shares the round line's tail —
                    // walk-away watches grade from THIS row, so the tenure meters ride here too.
                    + " tenure_applied=" + door.readerCounters().tenureApplied
                    + " tenure_spool_ms_max=" + door.readerCounters().tenureSpoolMsMax
                    + " tenure_clears=" + door.readerCounters().tenureClears
                    + " wrap_vacated=" + wrapVacatedTotal // walk-away watches grade wrap_vacated from the receipt too
                    + " operator_command_faults=" + operatorCommandFaults); // The belt's count, receipt twin
            printSnapSendRows(round, live); // The receipt's per-session send-gap rows (empty on lastSessionDied)
        }
        for (PilotIntentDatagramServer.PeerSession session : live) {
            if (prevLive.containsKey(session.sessionId())) {
                continue;
            }
            if (recordRowsArmed) {
                recordSessRow(door, MatchRecordRows.SESS_LIVE, session, "",
                        door.seatVariantOverrideOrNull(session.seatId()), round);
            }
            // A NEW session on an EXISTING wire id re-inits the fire-edge memory (before the step).
            pilotStep.onSessionBoundary(session.shipId(), session.sessionId());
            clientConnected = true;
            transferLane.markCensusDirty();
            if (socketLiveness) {
                // Per-session (the endpoint-level configureLiveness is legacy-gated).
                session.configureLiveness(
                        SOCKET_LIVENESS_READ_DEADLINE_MILLIS,
                        SOCKET_LIVENESS_DECLARATION_BUDGET, config.clientWaitMillis());
            }
            long baselineTick = Math.max(lastSimTick, 0L);
            // The seat baseline is encoded ONCE per admission
            // on this thread either way. PACED: the stamp only (latestStampTick set at admit,
            // nothing sent) — the rows ride the admission cursor's SEAT segment below, sent through
            // the same spool-only CTRL path (no per-frame sink record). LEGACY (cap <= 0): the
            // burst, verbatim.
            boolean pacedAdmit = rappCatchup.paced();
            List<byte[]> seatSnapshot = encodeSeatEvents(authority.snapshotSeatBaseline(baselineTick));
            door.sendTickToSession(session, baselineTick, pacedAdmit ? List.of() : seatSnapshot,
                    List.of());
            System.out.println("npeer-serve session_live id=" + session.sessionId()
                    + " seat=" + session.seatId() + " client=" + PeerText.quoteCid(session.clientId())
                    + " baseline_tick=" + baselineTick
                    + " round=" + round + " frame=" + frame
                    + " liveness=" + socketLiveness);
            // The seat's admission record of its HELLO's MagicLib reports
            // (bit 0x08 = its game reports its subsystem states, 0x10 = its subsystem class was patched), read
            // off the admitting F2 per NEW session, so a re-HELLO re-reads it. Bounded tokens only (a seat
            // number and two bits, never a name); observers (seat 0) have no hull and no row.
            if (session.seatId() >= 1) {
                int magicBits = session.helloMagicBits;
                System.out.println("magic-seat seat=" + session.seatId()
                        + " up=" + ((magicBits & Handshake13.FLAG_MAGIC_UP) != 0 ? 1 : 0)
                        + " replica=" + ((magicBits & Handshake13.FLAG_MAGIC_REPLICA) != 0 ? 1 : 0));
                // Record the bits for the mark's FOLLOW flag (this session's
                // boundary above already cleared the seat's mark, so the next applied frame re-points and reads
                // them), and say once per admission why MagicLib abilities will NOT follow this pilot — only
                // where MagicLib is evidently in the match (the same predicate the mark uses).
                helloMagicBitsBySeat.put(session.seatId(), magicBits);
                String receipt = MagicSeamFollow.receiptOrNull();
                String followWhy = MagicSeamFollow.whyOrNull(receipt, doorFingerprint != null, magicBits);
                if (followWhy != null && MagicSeamFollow.magicLibEvident(receipt, magicBits)) {
                    System.out.println("magic-follow seat=" + session.seatId() + " follow=0 why=" + followWhy);
                }
            }
            // A new peer re-rides every
            // damaged cell at full redundancy — shared-artifact bytes, legal
            // under encode-once; idempotent, so N joiners in one tick is fine.
            if (armorCellBook != null) {
                armorCellBook.markAllDamagedDirty();
            }
            // Mounted-seat MMNT replay BEFORE the RAPP catch-up — wire
            // order must match the live fan law (MMNT precedes the seat's RAPP
            // row), so the newcomer's mounted-table guard is warm when the RAPP
            // rows land (late-joiner spurious-spawn hazard). Synchronous on both
            // paths (a handful of frames); on the paced path it therefore also precedes the
            // cursor's SEAT rows.
            if (moduleLane != null) {
                moduleLane.replayMountsToSession(session);
            }
            // late-joiner catch-up: replay the round's RAPP append-log
            // (frames as sent) right after the seat baseline. Reliable-ordered CTRL;
            // client apply is idempotent (known id ⇒ ignore), so an arrival-vs-
            // catch-up duplicate is harmless.
            asteroidLane.armJoinSweep(session.sessionId()); // Joiner converges from the shared ASTP fan
            // (arm-only — sends nothing at admit, so hoisting it above the RAPP catch-up keeps the
            // wire byte-identical.)
            if (pacedAdmit) {
                // The PACED
                // admission cursor replaces every per-seat and per-round burst (a >~1024-frame log,
                // or 255 seats' baseline + name rows, killed the joiner ctrl_window_stall in its own
                // admission tick). Minted for EVERY paced admission: segments SEAT (the snapshot
                // above) → RAPP (the round log, possibly empty) → XFER (the in-flight BEGINs, taken
                // and sent whole at RAPP completion — no synchronous transfer replay here, else
                // every BEGIN twice) → SNMR (the names the reader bound). One step runs NOW (a
                // small admission still leaves in the admit tick once the reader has provisioned
                // the session); the rest drains under budget() and the global cap below.
                if (!roundRosterAppendFrames.isEmpty()) {
                    System.out.println("npeer-serve roster_append_catchup_begin session="
                            + session.sessionId()
                            + " frames=" + roundRosterAppendFrames.size()
                            + " round=" + round + " frame=" + frame);
                }
                rappCatchupPort.bind(door, liveById, round, frame);
                rappCatchup.mint(session.sessionId(), session, frame, rappCatchupPort, seatSnapshot);
                rappCatchup.drainOne(session.sessionId(), roundRosterAppendFrames, frame,
                        rappCatchupPort, rappCatchupPort);
                continue;
            }
            if (!roundRosterAppendFrames.isEmpty()) {
                // cc.server.rappCatchupPerTick <= 0: the LEGACY one-shot replay, verbatim (the
                // rollback lever; RosterAppendCatchupContractRows R2 pins that it kills a
                // >1024-frame joiner — the row that proves the paced path is what saves it).
                for (byte[] appendFrame : roundRosterAppendFrames) {
                    door.sendCtrlToSession(session, appendFrame);
                }
                System.out.println("npeer-serve roster_append_catchup session="
                        + session.sessionId()
                        + " frames=" + roundRosterAppendFrames.size()
                        + " round=" + round + " frame=" + frame);
            }
            // In-flight transfer BEGINs replay AFTER the RAPP
            // catch-up — the DELIBERATE MMNT-ordering inversion (a replayed BEGIN
            // references roster ids RAPP just taught).
            transferLane.replayTransfersToSession(session);
        }
        // ONE paced drain step per
        // tick for every active cursor, BEFORE any of this tick's live RAPP fans (wave/probe/
        // adhoc/growth/repoint all run later) — a cursor whose session left liveById is
        // abandoned (D5). A cursor minted THIS tick is skipped (its one admit-tick step ran above).
        // EVERY tick, active cursors or none: the drain carries the
        // index sweep that ages out retired cursors' tombstones and never-minted pre-cursors; gated
        // on activeCount() it never ran after an admit-tick completion, and the tombstone outlived
        // the serve. O(1) when the index holds only live cursors (row C10).
        rappCatchupPort.bind(door, liveById, round, frame);
        rappCatchup.drainAll(roundRosterAppendFrames, frame, rappCatchupPort, rappCatchupPort);
        liveByIdScratch = prevLive; // swap: last tick's map is next tick's scratch
        prevLive = liveById;
        long preSessEndNanos = System.nanoTime();
        // (a2) operator poll (D6 O-0, freeze): throttled consume-on-read house
        // verbs, polled AFTER (b): observe-then-poll, so a team pick lands before its seat's first (c) fly-in.
        // kick fires NOW — after (b) diffed the live set, so the death folds
        // (session_dead line + stats) at the NEXT frame's (b); end-round/end-match latch and take
        // effect at (m) AFTER this tick is served (the matchOver posture; end-match
        // wins over end-round). Rejected lines log + ignore (fail-soft, freeze law).
        // Admin /wave verbs (door-queued by the reader thread) ride the
        // SAME switch as a THIRD source after the house file — one semantic for the
        // hotkey, the GUI, the operator file and the chat verb.
        // A re-simulation applies the frame's recorded commands
        // instead (the operator file is never polled). A drained command carrying a session receipt is a
        // stray (a chat verb reached the sealed door; every PidsChatVerbs mint carries one). The door's
        // own receipt-0 seat-vacate deposits (queueGodModeOffLocked on a replayed death / kick) are NOT:
        // the SOURCE's copies ride the reel's CMD rows (sink contract row R3 pins it).
        OperatorCommandFile.ParseResult polled = replayFeed != null
                ? replayFeed.commandsAt(round, lastSimTick + 1) : adminThenTeamPicks(door, observedLiveSession);
        if (replayFeed != null) {
            for (OperatorCommandFile.Command drained : door.drainAdminCommands().commands()) {
                if (drained.receiptSessionId() != 0L) {
                    replayFeed.noteStray(0);
                }
            }
            // A re-simulation applies the recorded TEAM_PICK rows; a
            // sealed door's own team deposit is a counted stray, never applied.
            for (int i = door.drainTeamPicks(observedLiveSession).commands().size(); i > 0; i--) {
                replayFeed.noteStray(0);
            }
        }
        if (operatorFile != null && replayFeed == null) {
            polled = OperatorCommandFile.ParseResult.concat(OperatorCommandFile.ParseResult
                    .concat(lobbyGate.takeCarry(), operatorFile.poll()), polled); // D-LOBBYEND carry first
        }
        if (!polled.isEmpty()) {
            for (String reject : polled.rejected()) {
                System.out.println("npeer-serve operator_command_ignored line=" + reject
                        + " round=" + round + " frame=" + frame);
            }
            // The operator FAULT BELT. serve() is
            // try (door) … finally teardown(), so before this catch ANY RuntimeException in any
            // operator verb (the chat admin deposits ride this lane too) ended the MATCH — the
            // crash class this belt closes. One bounded row per fault (verb enum + exception SimpleName, never
            // a message: player text can ride one), counted, never swallowed; the next command
            // and the next frame run. IOException still propagates (a door I/O fault IS a
            // serve-level event). Residency: runs only when polled is non-empty.
            for (OperatorCommandFile.Command command : polled.commands()) {
                // The CMD row at its apply, before the verb runs.
                if (recordRowsArmed) {
                    door.recordRow(MatchRecordRows.KIND_CMD, MatchRecordRows.encodeCmd(new MatchRecordRows.CmdRow(
                            command.verb().name(), command.seatId(), command.simSpeed(), command.autoOn(),
                            command.receiptSessionId(), OperatorCommandFile.recordedText(command)),
                            MatchRecordRows.BAND_PRE, round),
                            lastSimTick + 1);
                }
                try {
                    handleOperatorCommand(door, command, round, frame);
                } catch (RuntimeException fault) {
                    operatorCommandFaults++;
                    System.out.println("npeer-serve operator_command_fault verb=" + command.verb()
                            + " err=" + fault.getClass().getSimpleName() + " round=" + round
                            + " frame=" + frame);
                    // The FIRST fault's frames (≤ 12),
                    // the PIDS onTimerFault idiom — StackTraceElement text is code (class/method/
                    // line), never the message (player text can ride one); later faults count only.
                    if (operatorCommandFaults == 1) {
                        StackTraceElement[] st = fault.getStackTrace();
                        for (int i = 0; i < Math.min(12, st.length); i++) {
                            System.out.println("npeer-serve operator_command_fault_at depth=" + i + " " + st[i]);
                        }
                    }
                    // The design's CONSTANT receipt
                    // — the admin whose CHAT verb faulted hears it; a house/GUI command has
                    // no requester (waveReceipt no-ops on receiptSessionId 0). Its own belt: a
                    // receipt send must never re-raise into the frame.
                    try {
                        waveReceipt(door, command, OPERATOR_FAULT_RECEIPT);
                    } catch (RuntimeException receiptFault) {
                        System.out.println("npeer-serve operator_command_fault_receipt err="
                                + receiptFault.getClass().getSimpleName());
                    }
                    // The belt SETTLES — a verb that
                    // faulted after its bans.add/remove (before its own persistBans) leaves the set
                    // dirty; ONE queued persist keeps that ban across a restart (memory
                    // stays authoritative, persistBans hands off, no I/O here). A clean set prints
                    // nothing. Its own belt: a settle must never re-raise into the frame.
                    // A memory-only serve (!banLane.sinkOpen()) has nothing
                    // to settle — without the guard persistBans returned "none" without rendering,
                    // the bit stayed dirty and EVERY later fault printed a settle row.
                    try {
                        if (banLane.sinkOpen() && door.bans().dirty()) {
                            System.out.println("npeer-serve operator_command_fault_settle persist="
                                    + banLane.persistBans(door));
                        }
                    } catch (RuntimeException settleFault) {
                        System.out.println("npeer-serve operator_command_fault_settle err="
                                + settleFault.getClass().getSimpleName());
                    }
                }
            }
        }
        // Pre sub-brackets: the pre lane's phases are mostly
        // SILENT (operator-file poll, liveSessions diff, receipt build, quiet drains)
        // so a pre-dominant stall row carried causes=- and stayed unattributable
        // (t13137, THE felt stutter). Three extra stamps partition pre, in run order, into
        // sess|op|seat|wave (each column = its own block); the remainder confesses out-of-lane time (safepoint/
        // scheduler). Same residency class as the seven existing witness stamps.
        long preOpEndNanos = System.nanoTime();
        // (c) seat drain: fly in every due seat BEFORE this tick's advance so the
        // deployed ship exists at publish. Due = occupied ∧ combat-ready (
        // the latch lands at (j) below on the seat's first taken intent, so a
        // claim's T1 fly-in earliest lands the NEXT frame's (c)). A due seat whose holder (b) has not observed waits a frame (observe-before-fly).
        // Intern the SAME frame (D5) so the seat's
        // AWSS entityId equals its descriptor seat.ship from its first snapshot.
        // MOUNT intern: a T4 remount re-interns a wire id the DEAD
        // hull's stable id still claims — the plain internReserved THROWS there, so
        // the fly-in site uses the re-pointing mount surface (T1 first mounts take
        // its unclaimed-id path, byte-identical to before).
        // W3 L1b-ii: the transfer lane pumps FIRST — door tick
        // heartbeat + TRUE-vacate consume (arbiter footprint + staged
        // clear); a vacated MOUNTED seat's dismount deposit is thereby queued
        // before the module drain below consumes deposits this same tick.
        transferLane.pump(lastSimTick + 1);
        // The scenario driver reads the lane's settled state
        // and seeds next-tick mutations; its verdict mails to the depositor.
        String podVerdict = podScenario.pump(lastSimTick + 1);
        if (podVerdict != null && podScenario.receiptTargetOrNull() != null) {
            waveReceipt(door, podScenario.receiptTargetOrNull(), podVerdict);
        }
        // The ≤8-frames/session LSTD listing
        // drip rides the same per-tick cadence as the pump's door heartbeat.
        door.dripListingFrames();
        // W2b E4: module-seat drains, pre-advance beside (c) —
        // voluntary dismounts (the vacate consume MOVED to the transfer lane, D1);
        // the lane owns the decided ordering (own-file ModuleSeatServeLane — the
        // 5000-nb trip extraction).
        if (moduleLane != null
                && moduleLane.drainVacatesAndDismounts(lastSimTick + 1) > 0) {
            transferLane.markCensusDirty();
        }
        // W5: grace pump, pre-advance AFTER the module drain — a module
        // seat's dismount above clears its pending grace mark before the pump
        // resolves marks (module seats never enter GRACE: the restored station AI
        // IS the module's grace), so this order is load-bearing.
        if (graceLane != null && graceLane.pump(lastSimTick + 1) > 0) {
            transferLane.markCensusDirty();
        }
        List<Integer> dueSeats = authority.seatsDueForFlyIn(lastSimTick + 1);
        if (!flyInUnobservedHolder.isEmpty()) {
            flyInUnobservedHolder.retainAll(dueSeats); // a wait that ended unflown (vacated) re-arms its line
        }
        for (int seatId : dueSeats) {
            // observe-before-fly - a holder admitted after (b)
            // read the live set is not yet due this frame; it flies at the next (c), after (b) observes it and
            // (a2) applies its team pick. Reads only the observed set, so a re-simulation decides it alike.
            if (!flyInHolderObserved(seatId, liveById, flyInUnobservedHolder, round, lastSimTick + 1)) {
                continue;
            }
            //
            // The defer covers ONLY the grant→BEGIN window — a /transfer row minted on
            // the reader thread after this tick's transferLane.pump ran. Once the
            // pod BEGINs, beginOntoSpawnedPod records the mount (a dead seat is
            // piloting again) so a live flight never lists here; an abort consumes
            // the row before its end (the fanAbortEnd ORDER LAW), so a stale row
            // never defers a fly-in either. A STRANDED seat flies the pod, no
            // fly-in follows (one-entity strand).
            Long transferTarget = rebindArbiter.transferTargetOrNull(seatId);
            if (transferTarget != null) {
                if (flyInDeferredForTransfer.add(seatId)) {
                    System.out.println("npeer-serve fly_in_deferred_transfer seat="
                            + seatId + " round=" + round + " tick=" + (lastSimTick + 1));
                    if (recordRowsArmed) {
                        recordFlyInRow(door, round, new MatchRecordRows.FlyInRow(seatId,
                                MatchRecordRows.FLYIN_DEFERRED_TRANSFER, "", -1, transferTarget,
                                0L, "", "", "", 0, "", 0, 0L));
                    }
                }
                continue;
            }
            flyInDeferredForTransfer.remove(seatId);
            worstFrames.note(lastSimTick + 1, "seat_fly_in");
            transferLane.markCensusDirty();
            NPeerHooks.FlyInResult flyIn;
            if (moduleLane != null
                    && moduleLane.tryMountClaim(seatId, lastSimTick + 1)) {
                // W2b E4: a granted module claim consumes pre-mint —
                // MOUNT-EXISTING replaces the hull deploy; stale ⇒ loud + hull
                // fallback in the lane.
                if (recordRowsArmed) {
                    recordFlyInRow(door, round, new MatchRecordRows.FlyInRow(seatId,
                            MatchRecordRows.FLYIN_MODULE_CLAIM, "", -1, 0L, 0L, "", "", "", 0, "", 0, 0L));
                }
                continue;
            } else {
                // W7 B1b: the fly-in honors the seat's /ship override (null =
                // descriptor); the real hook's twin falls back to descriptor on
                // an unresolvable override, never aborts mid-deploy.
                // + the seat's /switchteams side (-1 = descriptor) — the
                // engine's null-loc placement gives the matching edge + facing.
                // Both inputs read ONCE into locals, so the
                // FLYIN row records exactly what the deploy was handed.
                String overrideIn = replayFeed != null
                        ? replayFeed.flyInOverrideOrNull(round, lastSimTick + 1, seatId)
                        : door.seatVariantOverrideOrNull(seatId);
                int sideIn = seatTeams.nextFlyInSideOrNeg(seatId);
                flyIn = hooks.flyIn().deploy(seatId, overrideIn, sideIn);
                if (recordRowsArmed) {
                    recordFlyInRow(door, round, flyInDeployRow(seatId, overrideIn, sideIn, flyIn));
                }
            }
            stableIdRegistry.internReservedMount(flyIn.stableId(), flyIn.wireId());
            // The repoint above hands the DEAD hull's wire
            // id to this fresh hull — any GPEE baseline surviving it belongs to
            // the corpse, and a fresh ammo read below the stale baseline
            // fabricates a phantom volley at the first fold.
            // Reset BEFORE the hull's first advance;
            // dropped=0 is the T1 first-mount read.
            int gpeeReset = fireLaneDischargeDetector.resetShipBaselines(flyIn.wireId());
            System.out.println("npeer-serve fly_in_gpee_reset seat=" + seatId
                    + " wire_id=" + flyIn.wireId() + " dropped=" + gpeeReset);
            if (growthKnownKeys != null) {
                growthKnownKeys.add(flyIn.stableId()); // seat lane, never growth-announced
            }
            // Install the DD1 fast-time reinject shim on the fresh
            // seat hull, UNIFORMLY (all seats — advance() no-ops outside fast time;
            // only phased hulls ever enter it). Fail-soft: unbound seams
            // (fakes) / an unresolvable hull just skip — the serve is never failed by
            // a shim miss (a phased seat then degrades to pass-1-only coverage, the
            // pre-M3 posture, and the install log names it).
            binding.installFastTimeReinjectShim(flyIn.wireId());
            // The engine ship factory arms variant-default
            // autofire groups at build, and every seat suppression lane is gated on an
            // APPLIED client intent frame — impossible at this tick (the pre-step (j) take
            // below refuses a seat whose mount record is still queued) — so one advance
            // would run the server's own autofire AI on the client's body (the
            // phantom respawn hammer). Disarm BEFORE the hull's first advance; the
            // client's real mask re-converges at its first applied frame.
            int autofireOff = binding.disarmBuildTimeAutofire(flyIn.wireId());
            System.out.println("npeer-serve fly_in_autofire_disarm seat=" + seatId
                    + " wire_id=" + flyIn.wireId() + " groups_off=" + autofireOff);
            authority.recordMountEstablished(seatId);
            if (waveSchedule != null) {
                // The wave fallback timer measures from the
                // round's FIRST live-seat mount, not round-open (the desync:
                // live seats boot minutes late — the round clock had already
                // burned the 90 s fallback into a seatless field). Idempotent
                // latch: remounts and later seats never re-base the clock.
                waveSchedule.armTimer(lastSimTick + 1);
            }
            if (!firstFlyInDone) {
                firstFlyInDone = true;
                hooks.firstFlyInSelfCheck().run();
            }
            // facing= pairs with the client's own-flyin-facing rows
            // (1-decimal like the instrument; NaN on a fake binding).
            // side= + loc= ride the TAIL (every parser keys on the head) —
            // the /switchteams rig reads the engine-chosen edge here (side 0 = south,
            // y < 0 / 90; side 1 = north, y > 0 / 270). This row, not the roster's
            // midDeploySeat row: the boot warm-up rounds print that one too.
            float[] flyInLoc = binding.shipLocationOrNull(flyIn.wireId());
            System.out.println("npeer-serve fly_in seat=" + seatId
                    + " wire_id=" + flyIn.wireId() + " stable_id=" + flyIn.stableId()
                    + " round=" + round + " tick=" + (lastSimTick + 1)
                    + " facing=" + String.format(java.util.Locale.ROOT, "%.1f",
                            binding.shipFacingOrNaN(flyIn.wireId()))
                    + " side=" + flyIn.side()
                    + " loc=" + (flyInLoc == null ? "?" : String.format(java.util.Locale.ROOT,
                            "%.0f,%.0f", flyInLoc[0], flyInLoc[1])));
            // Every fly-in is a hull-tenure edge — record the
            // variant's file-truth groups so the group-identity map rebuilds against
            // the fresh hull (repointed or not).
            pilotStep.recordDeclaredGroups(flyIn.wireId(), flyIn.variantId(),
                    declaredGroupSlotSetsFor(variantCatalogOrNull, flyIn.variantId()));
            // A respawn fly-in arms the refield stale-frame gate — until the
            // client echoes a snapshot that carries this hull, its frames are samples of the
            // DEAD body and press nothing here (a vent held at death cut this
            // burn after one tick). A first mount (no hull loss before it) never arms.
            if (pilotStep.armRefieldGateAtFlyIn(flyIn.wireId(), lastSimTick + 1)) {
                System.out.println("npeer-serve refield_gate arm seat=" + seatId
                        + " wire_id=" + flyIn.wireId() + " tick=" + (lastSimTick + 1));
            }
            // A cross-variant refield arms the mask-staleness gate —
            // the client keeps sampling the DEAD variant's shape trio until its own
            // respawn edge (the phantom-volley re-arm). Same-variant refields
            // never arm (measured benign, 114848 controls).
            if (SeatAnnounceTracker.announceSeatVariantIfRepointed(door, seatId, flyIn,
                    lastAnnouncedVariantBySeat, roundRosterAppendFrames)) {
                pilotStep.fireClassLane().latchMaskStalenessGate(
                        flyIn.wireId(), lastSimTick + 1, seatId);
                // The mask gate's server-side twin — the class
                // resolver + demote trio judged the DEAD hull's roster under this
                // wire id (defect (91)(a) stale-S loop). Rebind + joint reset; the
                // hull exists here (the disarm above just ran on it), so the probe
                // snapshot reads the LIVE new roster.
                pilotStep.fireClassLane().rebindFireClassResolverAtRepoint(
                        flyIn.wireId(), lastSimTick + 1, seatId);
                // Fresh hull ⇒ fresh mount ordinals — drop the
                // pick-diff state so the next poll re-emits live picks.
                afTargetLane.resetShip(flyIn.wireId());
            }
        }
        long preSeatEndNanos = System.nanoTime();
        // (c2) wave drain: spawn every due scheduled wave row
        // BEFORE this tick's advance so the hulls ride the engine's pending-add
        // queue into it and exist at publish — the seat-drain twin.
        // intern the stable id AT MINT (capture's later first-sight intern finds
        // the key already present — same id) and fan the RAPP
        // roster-append rows in the same iteration, ahead of this tick's SNAP fan.
        // Hook nullity was fail-fasted at serve entry; a spawn THROW is fatal (an
        // engine deploy failure is never per-session).
        int waveSpawnsThisTick = 0; // (c2c) budgets on the leftover
        if (waveSchedule != null) {
            // The skip verb's deferred corpse sweep (armed at
            // the SKIP_WAVE drain; the WaveSchedule constant carries the WHY).
            // Two field derefs into the carved lane's holder —
            // no new allocation, no new work on the sim thread.
            if (waveVerbs.sweep.due >= 0L
                    && lastSimTick + 1 >= waveVerbs.sweep.due) {
                // Promote the follow-up slot (or -1 = none) into the armed one.
                waveVerbs.sweep.due = waveVerbs.sweep.followUp;
                waveVerbs.sweep.followUp = -1L;
                int swept = hooks.waveSkip()
                        .removeSideHulks(waveSchedule.waveSide());
                System.out.println("npeer-serve wave_skip_hulk_sweep removed="
                        + swept + " round=" + round + " tick=" + (lastSimTick + 1)
                        + " (dead ShardSpawner parents leave play before their"
                        + " 2-3 s child delay; the fracture family-vanish cascade"
                        + " removes the client corpses)");
            }
            List<RosterAppendFrameCodec.AppendRow> appendRows = null;
            if (waveInflaterOrNull != null) {
                // The armed warm walk pays WARM_BUDGET_NANOS of rows per
                // tick here, ahead of the drain, so a staged row is a cache hit once the walk has
                // passed it; idle = one null read. Sim-thread inventory row: wave_inflate_warm.
                waveInflaterOrNull.warmStep();
            }
            List<WaveSchedule.WaveRow> dueWaveRows =
                    waveSchedule.drainDue(lastSimTick + 1, waveSpawnPerTick);
            // Observability: ONE row per plan wrap, printed BEFORE this drain's
            // spawn rows (the wrap happens inside drainDue and cycle N's wave 2 stages in the SAME
            // drain, so the log reads wrap -> its wave 2.1 spawn — the row
            // used to trail the spawns). Greppable key for the headless proof (E-ORDER) + debrief.
            int cycleNow = waveSchedule.cycle();
            if (cycleNow != waveCycleLast) {
                System.out.println("npeer-serve wave_cycle_wrap cycle=" + cycleNow
                        + " tick=" + waveSchedule.lastWrapTick()
                        + " round=" + round
                        + " waves=" + waveSchedule.waveCount()
                        + " (F1 endless: the last wave cleared, the plan restarts at wave 2)");
                waveCycleLast = cycleNow;
            }
            // ONE row per live-roster wave stage (the receipt drainDue
            // recorded), printed before the spawn rows like the wrap row above. read_us = the
            // roster read's wall cost on the sim thread (the residency-law measurement).
            WaveSchedule.StageReceipt stageReceipt = waveSchedule.lastStageReceiptOrNull();
            if (stageReceipt != null && stageReceipt != lastStageReceiptLogged
                    && liveRosterDp != null) {
                lastStageReceiptLogged = stageReceipt;
                System.out.println("npeer-serve wave_dp_basis wave=" + (stageReceipt.wave() + 2)
                        + " cycle=" + stageReceipt.cycle() + " round=" + round + " mode=roster"
                        + " ratio=" + String.format(java.util.Locale.ROOT, "%.2f",
                                stageReceipt.ratio())
                        + " target=" + String.format(java.util.Locale.ROOT, "%.0f",
                                stageReceipt.liveTarget())
                        + " static_target=" + String.format(java.util.Locale.ROOT, "%.0f",
                                stageReceipt.staticTarget())
                        + " rows=" + stageReceipt.rowsStaged() + "/" + stageReceipt.rowsCompiled()
                        + " read_us=" + (stageReceipt.readNanos() / 1000L)
                        + " " + liveRosterDp.lastReadRow() + " faults=" + liveRosterDp.faults()
                        + " multiplier=" + liveRosterDp.multiplier()
                        + " (the longest prefix under ratio x static target,"
                        + " min 1 row)");
            }
            for (WaveSchedule.WaveRow waveRow : dueWaveRows) {
                waveSpawnsThisTick++;
                worstFrames.note(lastSimTick + 1, "wave_spawn");
                // pre_wave sub-stamp — per-spawn engine cost on the row itself
                // (001649 tick 1506: 145 ms on the LAST of 7 spawns, unattributed).
                // The engine member + the RAPP row carry a
                // vanilla-style Remnant DISPLAY name ("TTDS Nova"); the authored
                // "CC WAVE w.i" stays the wave SLOT id (schedule/tests/log key).
                // A game-free or failed namer yields null ⇒ the PUBLIC form of the slot
                // name ships ("AlphaCoreMP WAVE w.i" — W11; slot= below is
                // still the authored id, the tooling regexes key on it).
                String waveDisplayName = WaveShipNamer.displayNameOrNull(waveRow.shipName());
                String waveShipName = waveDisplayName != null
                        ? waveDisplayName
                        : cc.starsector.server.match.WaveSchedule.publicWaveName(waveRow.shipName());
                long spawnStartNanos = System.nanoTime();
                // A row-carried officer (faction officer rule / Ziggurat
                // boss) rides the 5-arg hook; a bare row keeps the 3-arg call byte-identical
                // (contract fakes implement only that one).
                // An INFLATED row fields its server-minted id (registered +
                // VDEF-spooled at stage); the officer table is keyed by the GOAL id, so a bare
                // inflated row pre-resolves its table captain and rides it as the row spec.
                String spawnVariantId = waveRow.spawnVariantId();
                OfficerRosterCodec.OfficerSpec rowOfficer = waveRow.officer();
                if (rowOfficer == null && waveRow.inflatedVariantId() != null) {
                    rowOfficer = OfficerAttach.tableSpecOrNull(waveRow.variantId());
                }
                // The spec is keyed by the GOAL id; the hull is fielded
                // under the inflated id, and the RAPP v3 own-variant law threw inside this
                // frame (hegemony w4.5 falcon_Attack = a serve-killing fatal). Re-key to the
                // fielded id so the attach, the wire row and the client twin agree.
                // The re-keyed line is +17 chars over the one BOOT
                // round-tripped, so the guard re-runs the round trip HERE and drops the captain
                // before the spawn (unofficered on both ends = no stat desync) rather than
                // throwing inside serveFrame. Same-id rows cost nothing (identity return).
                if (rowOfficer != null) {
                    rowOfficer = WaveOfficerRowGuard.rekeyForWireOrNull(rowOfficer,
                            spawnVariantId, "wave", waveRow.shipName(), round, lastSimTick + 1);
                }
                NPeerHooks.WaveSpawnResult spawned = rowOfficer == null
                        ? hooks.waveSpawn().spawn(
                                waveRow.side(), spawnVariantId, waveShipName)
                        : hooks.waveSpawn().spawn(waveRow.side(), spawnVariantId,
                                waveShipName, null, rowOfficer);
                long spawnNanos = System.nanoTime() - spawnStartNanos;
                // Root + module band intern (wave lane's own RAPP below)
                long waveWireId = internWaveHull(stableIdRegistry, growthKnownKeys,
                        spawned, waveRow.variantId());
                if (appendRows == null) {
                    appendRows = new ArrayList<>();
                }
                // The captain the roster ACTUALLY attached rides the v3 officer
                // tail (row spec or table row; null = bare) — the client twin fields the same.
                // CONTAINED — a codec/grammar reject drops the captain
                // (bare row + one officer_row_dropped), never the serve (the blast radius of the fatal above).
                appendRows.add(WaveOfficerRowGuard.officeredOrBare(
                        waveWireId, waveRow.side(), spawnVariantId,
                        waveShipName, spawned.crPerMille(), spawned.officerOrNull(),
                        "wave", round, lastSimTick + 1, spawned.atRest())); //
                System.out.println("npeer-serve wave_spawn side=" + waveRow.side()
                        + " variant=" + waveRow.variantId()
                        + " inflated=" + (waveRow.inflatedVariantId() == null ? "none"
                                : waveRow.inflatedVariantId())
                        + " name=" + waveShipName
                        + " slot=" + waveRow.shipName()
                        + " wire_id=" + waveWireId
                        + " officer=" + (spawned.officerOrNull() == null ? "none"
                                : spawned.officerOrNull().fullName().replace(' ', '_')
                                        + "/L" + spawned.officerOrNull().level
                                        + "/" + spawned.officerOrNull().skills.size())
                        + " stable_id=" + spawned.stableId()
                        + " round=" + round + " tick=" + (lastSimTick + 1)
                        + " spawn_ms=" + String.format(java.util.Locale.ROOT, "%.2f",
                                spawnNanos / 1_000_000.0d)
                        + " mint{" + RealEngineDemoRoster.lastWaveMintSplit() + "}");
                // The derelict FINALE row (and only it) arms the
                // creep author toward the fortress — the scheduled lane IS the
                // finale; the adhoc /spawnfoe drain (c2c) never arms.
                armFinaleCreepIfDue(hooks.finaleCreep(), waveRow, finaleCreepSu,
                        spawned.stableId(), waveWireId, "wave", lastSimTick + 1);
                // The finale ESCORT rows (9.2..) enlist on the
                // author, which orders them onto the root once both are in play.
                enlistFinaleEscortIfDue(hooks.finaleCreep(), waveRow, finaleCreepSu,
                        spawned.stableId(), waveWireId, "wave", lastSimTick + 1);
            }
            if (appendRows != null) {
                long fanStartNanos = System.nanoTime();
                fanRosterAppendChunks(door, appendRows, roundRosterAppendFrames);
                System.out.println("npeer-serve wave_fan rows=" + appendRows.size()
                        + " fan_ms=" + String.format(java.util.Locale.ROOT, "%.2f",
                                (System.nanoTime() - fanStartNanos) / 1_000_000.0d)
                        + " round=" + round + " tick=" + (lastSimTick + 1));
            }
            // Observability: one row per clear-hold transition —
            // armed at the first zero-enemy drain read; a reset with NO spawn this
            // tick means enemies re-entered mid-hold (late shard children CAUGHT);
            // a spawn-consumed reset shows as the adjacent wave_spawn rows instead.
            long holdSince = waveSchedule.clearHoldSinceTick();
            if (holdSince != waveClearHoldSinceLast) {
                if (holdSince >= 0L) {
                    System.out.println("npeer-serve wave_clear_hold armed tick="
                            + (lastSimTick + 1)
                            + " hold_ticks=" + waveSchedule.clearHoldTicks()
                            + " round=" + round
                            + " (spawn-on-clear drains only if still zero at the"
                            + " hold end)");
                } else if (waveSpawnsThisTick == 0) {
                    System.out.println("npeer-serve wave_clear_hold reset tick="
                            + (lastSimTick + 1)
                            + " held=" + (lastSimTick + 1 - waveClearHoldSinceLast)
                            + " round=" + round
                            + " (enemies re-entered inside the hold - no wave"
                            + " spawned)");
                }
                waveClearHoldSinceLast = holdSince;
            }
        }
        // (c2b) wave-probe drain — the (c2) body on env-due rows
        // (side 1 fixed, one-shot). Deliberately the SAME lane end-to-end; a spawn
        // THROW stays fatal, same as (c2) (an engine deploy failure is never
        // per-session), and the fatal path's stack IS probe evidence.
        if (waveProbeRows != null) {
            List<RosterAppendFrameCodec.AppendRow> probeAppendRows = null;
            for (WaveProbeRow probeRow : waveProbeRows) {
                if (probeRow.spawned || probeRow.dueTick > lastSimTick + 1) {
                    continue;
                }
                probeRow.spawned = true;
                worstFrames.note(lastSimTick + 1, "wave_probe_spawn");
                NPeerHooks.WaveSpawnResult spawned = hooks.waveSpawn().spawn(
                        1, probeRow.variantId, probeRow.shipName);
                probeRow.stableId = spawned.stableId();
                long probeWireId = internWaveHull(stableIdRegistry, growthKnownKeys,
                        spawned, probeRow.variantId); //
                if (probeAppendRows == null) {
                    probeAppendRows = new ArrayList<>();
                }
                // Contained like the wave mint above — the probe lane keeps
                // its deliberate spawn-throw fail-fast, but a codec reject on the officer TAIL is
                // a config fault, not an engine-deploy failure.
                probeAppendRows.add(WaveOfficerRowGuard.officeredOrBare(
                        probeWireId, 1, probeRow.variantId,
                        probeRow.shipName, spawned.crPerMille(), spawned.officerOrNull(),
                        "probe", round, lastSimTick + 1, spawned.atRest())); //
                System.out.println("npeer-serve wave_probe_spawn side=1"
                        + " variant=" + probeRow.variantId
                        + " name=" + probeRow.shipName
                        + " wire_id=" + probeWireId
                        + " stable_id=" + spawned.stableId()
                        + " round=" + round + " tick=" + (lastSimTick + 1));
                // The probe REHEARSES the finale faithfully — a
                // probe row naming the finale variant arms the creep exactly as
                // the (c2) drain would (headless proof of the boss leg without
                // fighting eight waves first).
                WaveSchedule.WaveRow probeAsRow = new WaveSchedule.WaveRow(1,
                        probeRow.variantId, probeRow.shipName);
                armFinaleCreepIfDue(hooks.finaleCreep(), probeAsRow, finaleCreepSu,
                        spawned.stableId(), probeWireId, "probe", lastSimTick + 1);
                // A probe row named like a finale escort slot (CC WAVE
                // 9.2..) enlists too — the headless rehearsal of the escort order.
                enlistFinaleEscortIfDue(hooks.finaleCreep(), probeAsRow,
                        finaleCreepSu, spawned.stableId(), probeWireId, "probe",
                        lastSimTick + 1);
            }
            if (probeAppendRows != null) {
                fanRosterAppendChunks(door, probeAppendRows, roundRosterAppendFrames);
            }
        }
        // The /godmode lane re-applies to a seat's CURRENT hull each tick
        // (respawn = a new hull) — one map read per god-mode seat, nothing when the
        // set is empty (frame-loop residency law: the minimum coherent read only).
        adminDebug.applyTick();
        // (c2c) adhoc /spawnfoe + operator spawn-foe drain — the (c2)
        // lane (pending-add before advance, intern at mint, RAPP fan ahead of this
        // tick's SNAP fan) with TWO deliberate deltas: spawns ride ONLY the wave
        // stagger's LEFTOVER per-frame budget (scheduled rows outrank; each spawn
        // is ~4.5-6.3 ms of sim thread — never batch past waveSpawnPerTick), and
        // each spawn is try-wrapped FAIL-SOFT (an admin-typed id must never kill a
        // serve with players on — the scheduled lane above keeps its descriptor-
        // authored fail-fast). Ids were door/parse-validated; the engine's own
        // preconditions (deployed round, descriptor serve, side-1 manager) surface
        // through the same catch as adhoc_spawn_fail rows. shipName = the id: an
        // honest debug label.
        if (adhocFoes.hasPending() && hooks.waveSpawn() != null) {
            List<RosterAppendFrameCodec.AppendRow> adhocAppendRows = new ArrayList<>();
            while (waveSpawnsThisTick < waveSpawnPerTick && adhocFoes.hasPending()) {
                AdhocFoeSpawner.Entry adhocEntry = adhocFoes.pollNextEntry();
                waveSpawnsThisTick++;
                worstFrames.note(lastSimTick + 1, "adhoc_foe_spawn");
                // The fail-soft spawn body = AdhocFoeDrain.spawnOne,
                // carved VERBATIM so a contract row can drive it; the frame keeps the budget,
                // the worst-frame note, the receipt send and the RAPP fan.
                AdhocFoeSpawner.Entry adhocExhausted = AdhocFoeDrain.spawnOne(adhocFoes,
                        adhocEntry, hooks.waveSpawn(), stableIdRegistry, growthKnownKeys,
                        adhocAppendRows, mapHalf, round, lastSimTick + 1);
                // The entry's ONE honest receipt at
                // exhaustion — real outcome, chat deposits only (file/GUI = 0).
                if (adhocExhausted != null
                        && adhocExhausted.receiptSessionId() != 0L) {
                    door.sendAdminReceipt(adhocExhausted.receiptSessionId(),
                            adhocExhausted.receipt());
                }
            }
            if (!adhocAppendRows.isEmpty()) {
                fanRosterAppendChunks(door, adhocAppendRows, roundRosterAppendFrames);
            }
        }
        asteroidLane.preAdvance(lastSimTick + 1, WireKnobState.get(WireKnob.ASTEROIDS)); // Sweep + retarget BEFORE the advance
        // The (j)+(k) UP take + declaration drain run PRE-STEP (label
        // kept) — a deposit that lands in the frame's sleep applies at THIS tick (R+1), no longer the next.
        long applyTick = lastSimTick + 1; // the tick this frame's step serves: meter age + FireBirthSink
        long witnessIntentStartNanos = System.nanoTime();
        long witnessIoIntentStart = ServeStdoutIoMeter.nanosOf(ioMeter);
        // (j)+(k) UP: latest-wins intent take (NEVER stage(null) — it clears every
        // seat's staged frame) + per-session declaration drain; the fail-open
        // held streak is the AGGREGATE across seats (per-session deferred).
        boolean anyFireHeld = false;
        takes.reset();
        if (replayFeed != null) {
            anyFireHeld = replayTakeBand(live, round, frame, applyTick);
        }
        for (PilotIntentDatagramServer.PeerSession session
                : replayFeed == null ? live : List.<PilotIntentDatagramServer.PeerSession>of()) {
            PilotIntentFrame intent = session.takeDepositedIntentOrNull();
            if (intent != null) {
                // combat-ready gate: the FIRST intent taken from a
                // seat's session latches it deployable — HELLO admission alone no
                // longer fields a hull (the boot-shred shape: pilotless
                // seats shredded while the window is still loading). The client's
                // first intent is its documented "combat began" signal, so no new
                // CTRL kind and no client change (D5-clean).
                if (authority.recordCombatReady(session.seatId())) {
                    System.out.println("npeer-serve combat_ready seat="
                            + session.seatId() + " round=" + round + " frame=" + frame
                            + " tick=" + lastSimTick
                            + " (first pilot intent; seat deployable from here)");
                }
                // Stage ONLY while the seat holds a
                // mounted hull (the ONE seat-state owner; same lock + precondition as the
                // recordCombatReady call above). The despawn drain drops a dead hull's staged
                // frame once; without this gate the dead pilot's still-depositing client
                // re-staged it the next tick — the AIMP fan then fanned a hull-less seat's aim
                // and the refield applied a frame sampled off no hull ((66b) F2 undercut).
                // ALSO refuse while the seat's SEAT transition is
                // still queued ((g) drains it) — on a mount tick the take now runs AFTER the mount, and
                // a first apply at T would put the seat's AIMP row and any first-advance discharge
                // ahead of its SEAT frame (a remount's GPEE hits the peers' dead body). The refused
                // frame's successor applies at T+1, as before the take moved. Pin: RoundSpool R6 / R6e.
                // ALSO refuse at applyTick 0 — a wrap re-fields
                // seats mounted with no pending transition, so a deposit carried across it would apply
                // in round r+1's first step, the one the fresh per-round detectors take as their
                // first-sight seed (an uncovered tick-0 discharge is never emitted); its
                // successor applies at tick 1.
                // Pin: IntentTakeOrderContractRows R9.
                boolean staged = applyTick > 0 && authority.isMounted(session.seatId())
                        && !authority.hasPendingTransition(session.seatId());
                if (staged) {
                    pilotStep.stage(intent);
                    intentTakeAge.sample(applyTick, session.takenDepositStampTick());
                } else {
                    stagedRefusedUnmounted++;
                }
                if (recordRowsArmed) {
                    addTakeRow(session, staged, intent);
                }
                if (intent.fireHeld()) {
                    anyFireHeld = true;
                }
            }
            FireDischargeDeclaration declaration;
            while ((declaration = session.pollDeclaration()) != null) {
                pilotStep.enqueueDeclaration(declaration, applyTick);
                sessionDeclReceived++;
                if (recordRowsArmed) {
                    addDeclRow(session, declaration);
                }
            }
        }
        // The frame's TAKES row — EVERY served frame, an empty one
        // included (the re-simulation asserts tick coverage) — after the take loop, before the advance,
        // no lock held, stamped with the tick the step serves.
        if (recordRowsArmed) {
            door.recordRow(MatchRecordRows.KIND_TAKES,
                    takes.encode(MatchRecordRows.BAND_PRE, round, 0), applyTick);
        }
        if (declaredLaneActive && sessionDeclReceived == 0L) {
            heldStreakTicks = anyFireHeld ? heldStreakTicks + 1 : 0;
            if (heldStreakTicks >= fallbackHeldTicks) {
                heldLaneFallback = true;
                declaredLaneActive = false;
                pilotStep.setDeclaredFireLane(false);
                worstFrames.note(lastSimTick + 1, "fire_lane_fallback");
                System.out.println("pilot-fire-lane fallback_to_held round=" + round
                        + " frame=" + frame + " held_streak=" + heldStreakTicks
                        + " (zero declarations this session; legacy held lane serves FIRE"
                        + " from here, latched for the session)");
            }
        }
        // Session fallback, the routed twin (lanes are
        // boot-exclusive so heldStreakTicks is free here): a session that NEVER
        // declares while holding fire is a decl-stream-dead client — a routed
        // class-S selection would go silent, so the whole serve reverts to held
        // LOUD ((83) law) and stays there (latch above survives round wraps).
        if (classRoutingActive && sessionDeclReceived == 0L) {
            heldStreakTicks = anyFireHeld ? heldStreakTicks + 1 : 0;
            if (heldStreakTicks >= fallbackHeldTicks) {
                classRoutingFallback = true;
                classRoutingActive = false;
                pilotStep.fireClassLane().disableClassRoutingAll(
                        "session zero-decl held_streak=" + heldStreakTicks);
                worstFrames.note(lastSimTick + 1, "fire_class_fallback");
                System.out.println("pilot-fire-class fallback_to_held round=" + round
                        + " frame=" + frame + " held_streak=" + heldStreakTicks
                        + " (zero declarations this session; held lane serves FIRE for"
                        + " every ship from here, latched for the serve)");
            }
        }
        long witnessIntentNanos = System.nanoTime() - witnessIntentStartNanos;
        long witnessIoIntentNanos = ServeStdoutIoMeter.nanosOf(ioMeter) - witnessIoIntentStart;
        // Witness brackets: phase-boundary stamps partitioning
        // the frame's work (pre|step|det|comp|send|intent + the post-measure tail)
        // — pure nanoTime reads, folded into ONE loud line only past
        // STALL_FRAME_NANOS (the work-frame-stall class).
        long witnessPreEndNanos = System.nanoTime();
        long witnessIoPreEnd = ServeStdoutIoMeter.nanosOf(ioMeter);
        // (d) step (applies the previously-staged intents) THEN capture.
        // Stamp the tick the advance's confessed births belong to
        // (no-op while the shadow is off).
        FireBirthSink.beginTick(applyTick);
        AuthoritativeWorldSnapshot snap = source.publishNextTick();
        if (snap == null) {
            throw new IOException("npeer snapshot source returned null at round " + round
                    + " frame " + frame);
        }
        // Every PRE row of this frame was stamped applyTick, every
        // POST row and the DOWN carry snap.simTick() — a recording where they differ mis-keys its rows, so
        // each mismatch is a recorder fault (the reel is then refused by a re-simulation). One compare.
        if (recordRowsArmed && snap.simTick() != applyTick) {
            recordRowFaults++;
            if (!recordTickMismatchLogged) {
                recordTickMismatchLogged = true;
                System.out.println("npeer-serve record_tick_mismatch apply_tick=" + applyTick
                        + " sim_tick=" + snap.simTick());
            }
        }
        // This tick's side-totals ride every AWSD frame from the
        // compose below (v9 trailing block; hold-last upstream, ZERO pre-first).
        downComposer.setSideTotals(source.lastSideTotals());
        if (voiceLane != null) {
            voiceLane.onTick(snap); // W12 L2: seat anchors → position slot (no alloc)
        }
        long witnessStepEndNanos = System.nanoTime();
        long witnessIoStepEnd = ServeStdoutIoMeter.nanosOf(ioMeter);
        // (d2) growth drain: edges the seam captured during THIS tick's
        // advance. Capture already first-sight-interned every alive spawn inside
        // publishNextTick, so networkIdFor returns the SAME wire id;
        // engine-born = stableKey NOT seeded by the pre-intern/fly-in/wave lanes.
        // RAPP fan rides the shared chunk helper AHEAD of this tick's SNAP fan
        // (the ordering law); removal truth feeds the reclassifier the EDSP
        // compose consumes below. growthKnownKeys.add doubles as the per-round
        // announce dedupe (the live bay mints fresh Ships, this is belt).
        if (engineBornGrowth) {
            List<RosterAppendFrameCodec.AppendRow> growthRows = null;
            int fspnOrdinal = 0; // the 0-based ADDED-row ordinal within this drain (FSPN rows)
            for (NPeerHooks.GrowthEvent growthEvent : hooks.growthSeam().drain()) {
                if (growthEvent.added()) {
                    if (!growthKnownKeys.add(growthEvent.stableKey())) {
                        continue; // deploy-lane hull or defensive re-add
                    }
                    growthBornKeys.add(growthEvent.stableKey());
                    long growthWireId =
                            stableIdRegistry.networkIdFor(growthEvent.stableKey());
                    growthReclassifier.registerGrowthId(growthWireId);
                    if (growthRows == null) {
                        growthRows = new ArrayList<>();
                    }
                    // A bay-born fighter's carrier is
                    // a deploy-lane hull already interned by the positional walk —
                    // LOOKUP only (networkIdForOrNull), never mint: an unknown
                    // carrier key (fx/ghost mint) degrades to the absent shape.
                    Long carrierWire = growthEvent.carrierStableKey().isEmpty()
                            ? null
                            : stableIdRegistry.networkIdForOrNull(
                                    growthEvent.carrierStableKey());
                    long carrierWireId = carrierWire == null ? 0L : carrierWire;
                    int bayIndex = carrierWire == null
                            ? RosterAppendFrameCodec.BAY_NONE : growthEvent.bayIndex();
                    // The engine captain rides the
                    // row's v3 officerLine verbatim (validated at the GrowthEvent, which admits
                    // a line on carrier-less adds only — the AppendRow carrier law holds by
                    // construction); "" re-blanked the ShardSpawner wing's PD/GI/IM=2 captain.
                    growthRows.add(new RosterAppendFrameCodec.AppendRow(
                            growthWireId, growthEvent.side(), growthEvent.variantId(),
                            growthEvent.shipName(), growthEvent.crPerMille(),
                            carrierWireId, bayIndex, growthEvent.officerLine()));
                    // The FSPN row — the fighter keyed by its carrier's
                    // seat + bay + this drain's ordinal (wire ids renumber on a spawn-order change).
                    if (recordRowsArmed) {
                        Integer carrierSeat = seatIdByWireId.get(carrierWireId);
                        door.recordRow(MatchRecordRows.KIND_FSPN, MatchRecordRows.encodeFspn(
                                new MatchRecordRows.FspnRow(growthWireId, growthEvent.stableKey(),
                                        carrierWireId, carrierSeat == null ? -1 : carrierSeat,
                                        growthEvent.carrierStableKey(), bayIndex, fspnOrdinal,
                                        growthEvent.side(), growthEvent.variantId()),
                                MatchRecordRows.BAND_POST, round), snap.simTick());
                    }
                    fspnOrdinal++;
                    System.out.println("npeer-serve growth_spawn side=" + growthEvent.side()
                            + " variant=" + WireTextLaw.foldUnprintable(growthEvent.variantId())
                            + " name=" + WireTextLaw.foldUnprintable(growthEvent.shipName())
                            + " wire_id=" + growthWireId
                            + " carrier_wire_id=" + carrierWireId
                            + " bay=" + bayIndex
                            + " officer=" + (growthEvent.officerLine().isEmpty() ? 0 : 1)
                            + " stable_id=" + growthEvent.stableKey()
                            + " round=" + round + " tick=" + snap.simTick());
                } else if (growthBornKeys.contains(growthEvent.stableKey())) {
                    growthReclassifier.recordSeamTruth(
                            stableIdRegistry.networkIdFor(growthEvent.stableKey()),
                            growthEvent.removedHealthy());
                } else if (!growthEvent.removedHealthy()) {
                    // A NON-growth (roster/wave) Ship removed
                    // UNHEALTHY (isHulk || hitpoints<=0 at the repo callback) is a
                    // death the detector reads DEPARTED when the kill blow removed
                    // it inside one tick with no corpse family (hulk chance 0 —
                    // the shrouded tendril, served 0904-062817 id=3 @1693). The
                    // loop below consumes it THIS tick. LOOKUP only — an
                    // unknown key is never minted. Truth landing AFTER a DEPARTED
                    // already published = loud line (the lag class is measured
                    // before any code holds roster despawns for it).
                    Long removedWireId = stableIdRegistry.networkIdForOrNull(
                            growthEvent.stableKey());
                    if (removedWireId != null
                            && despawnDetector.wasPresentInLastObservedFrame(
                                    removedWireId)) {
                        // Vanish NOT yet observed ⇒ this tick's despawn read will
                        // consume it. (A removal of an id whose despawn already
                        // published — overkill vaporize / off-camera hulk fade of
                        // a dead seat hull — must NOT deposit: the wire id is
                        // re-pointed at the T4 remount and a stale deposit would
                        // flip the refield hull's later retreat to DESTROYED.)
                        //
                        despawnReasonEvidence.recordUnhealthyRemoval(
                                removedWireId, snap.simTick());
                        // Loud per deposit (ship-scale: only hulkless deaths land
                        // here) — pairs with the same tick's `despawn id=` row to
                        // prove the callback lands the tick of absence.
                        System.out.println("npeer-serve unhealthy_removal id="
                                + removedWireId
                                + " variant=" + growthEvent.variantId()
                                + " round=" + round + " tick=" + snap.simTick());
                    } else if (removedWireId != null) {
                        Long departedAtTick =
                                departedShipDespawnTickById.get(removedWireId);
                        if (departedAtTick != null
                                && departedAtTick < snap.simTick()
                                && snap.simTick() - departedAtTick <= 10L) {
                            System.out.println("npeer-serve"
                                    + " despawn_reason_late_unhealthy_removal id="
                                    + removedWireId
                                    + " published=DEPARTED@" + departedAtTick
                                    + " removed_tick=" + snap.simTick()
                                    + " (correction MISSED - published event stands)"
                                    + " round=" + round);
                        }
                    }
                }
            }
            if (growthRows != null) {
                fanRosterAppendChunks(door, growthRows, roundRosterAppendFrames);
            }
        }
        long detGrowthEnd = System.nanoTime(); // Det sub-bracket: growth drain + RAPP fan
        // Server-side parity-audit walk on the freshly-captured tick (
        // the retired serve() lane's twin site): the engine is at exactly the
        // post-step state snap
        // encodes, and every live mount's entityId was interned at its fly-in above, so
        // the walk keys the SAME entityIds the wire carries. Keyed on snap.simTick()
        // (what the client keys on), read-only + fail-soft, no-op when off.
        if (parityWalkEvery > 0 && snap.simTick() % parityWalkEvery == 0L) {
            binding.walkParityRoster("server", snap.simTick(), stableIdRegistry);
        }
        // BEFORE logHullDrops — its endTick() clears the self-report window (tickPositionShipIds is minted later ⇒ the binding's own read, paid only on crit ticks)
        List<GameplayEdgeEvent> critEdges = critProbe.drain(snap.simTick(), binding::positionShipIdByEngineShip, source::lastEntityIdByPositionShipId, arcHitProbe.hullSources());
        // per-tick damage-taken observation,
        // hull-drop-gated — `pilot-dmg-taken` for human seats (wire id) + `dmg-taken` for
        // every other alive roster ship (registry entity id = what the wire carries), each
        // hulldelta row carrying the ShipStatSig tsig{} (D-DMGDIV gate). Fail-soft inside
        // the binding; one reflective hull read per alive ship per tick.
        binding.logHullDrops(snap.simTick(), stableIdRegistry, arcHitProbe.hullSources()); // src= join
        // near-contact shield-geometry rows on the freshly-captured
        // tick (null = knob off, zero serve change; fail-soft inside the class).
        if (nearContactLog != null) {
            nearContactLog.observe(snap);
        }
        // wave-probe sampler — every 60 ticks, one raw
        // engine-truth line per spawned probe hull (post-step, so the first sample
        // sees the hull after its pending-add drained). Fail-soft by construction:
        // the hook's sampler catches inside itself; null = no engine bound.
        if (waveProbeRows != null && snap.simTick() % 60L == 0L) {
            for (WaveProbeRow probeRow : waveProbeRows) {
                if (probeRow.stableId == null) {
                    continue;
                }
                String sample = hooks.waveSpawn().probeSampleLine(probeRow.stableId);
                System.out.println("npeer-serve wave_probe_sample"
                        + " tick=" + snap.simTick()
                        + " name=" + probeRow.shipName
                        + " stable_id=" + probeRow.stableId
                        + " " + (sample == null ? "sampler=UNBOUND" : sample));
            }
        }
        // Flux-rise + system-engage measurement samples at N (default OFF): BoostSystemSampleLane.
        boostSystemLane.sample(binding, frame, measureOwnedShipId, sampleBoost, sampleSystem);
        // per-frame autofire-gate read on the armed foe mount —
        // post-advance like the samplers above; the call never throws (fail-soft
        // is inside the sampler).
        if (afGateSampler != null) {
            afGateSampler.sample(snap.simTick());
        }
        long detReadersEnd = System.nanoTime(); // Parity walk + hull-drop read + samplers
        // (e) detectors: GPEE observe exactly as the retired serve() lane did (same
        // tier args + gating + the B4(3)-w fix-(1) position→network re-key
        // — its site's comment = git history); EDSP observes EVERY tick — T3 consumes
        // it below regardless of the wire lane.
        // An enabled fireEdgeSeq latch needs the edge observe too
        // (its release rides observed discharges — the retired serve() twin's
        // comment, git history).
        List<GameplayEdgeEvent> tickEdges = List.of();
        // Mounts tier hoisted (same single read) — the shadow fold below
        // needs to know whether discharge detection actually SAW this tick.
        List<WeaponMountView> mountsTier = null;
        boolean edgeObserveRan = declaredLaneActive || eventLaneOn
                || pilotStep.fireEdgeLatchEnabled();
        long edgesMountsEnd = detReadersEnd; // Edges sub-stamp: mounts tier read
        if (edgeObserveRan) {
            mountsTier = binding.weaponMountStatesOrNull();
            edgesMountsEnd = System.nanoTime();
            tickEdges = fireLaneDischargeDetector.observe(
                    snap.simTick(),
                    eventLaneOn ? binding.shipFluxStatesOrNull() : null,
                    mountsTier,
                    eventLaneOn ? binding.shipSystemStatesOrNull() : null,
                    source.lastEntityIdByPositionShipId(),
                    // same-tick post-step poses — the SYSTEM_JUMP
                    // marker's dest source (consulted only on a jump edge).
                    snap.entities());
        }
        long edgesObserveEnd = System.nanoTime(); // Discharge detector observe
        // Shadow fold: drain this advance's confessed births every tick the
        // shadow is live; a tick the detector could not see (gate off / mounts tier
        // fail-soft null) folds unobserved — never classified (the detector's own
        // fail-soft law mirrored: a flap must not fabricate divergence).
        // Births + the ONE per-tick position-map read hoisted — the fold
        // and the truth merge below translate off the SAME map.
        // ONE positional-id roster read per
        // tick — the birth fold, pose-owned demote, escort/phase-dive probes and the
        // af-target lane all translate off this SAME post-step roster (nothing spawns
        // inside the det bracket); it was rebuilt up to 4× per tick before.
        Map<Object, Long> tickPositionShipIds = binding.positionShipIdByEngineShip();
        List<FireBirthSink.FireBirth> tickBirths = List.of();
        Map<Object, Long> birthPositionShipIds = null;
        if (FireBirthSink.enabled()) {
            tickBirths = FireBirthSink.drainUpTo(snap.simTick());
            birthPositionShipIds = tickBirths.isEmpty() ? null : tickPositionShipIds;
            if (edgeObserveRan && mountsTier != null) {
                onFireShadow.fold(snap.simTick(), tickEdges, mountsTier, tickBirths,
                        birthPositionShipIds,
                        source.lastEntityIdByPositionShipId(),
                        FireBirthSink.coveredWeaponIds(), round);
            } else {
                onFireShadow.foldUnobserved(snap.simTick(), tickBirths);
            }
            // Pose-lane-OWNED ordnance confessed on a
            // routed class-S mount demotes it to held for the session. Drained every
            // enabled tick even with routing off (an undrained list would pin dead
            // engine ship refs); applied only while routing is live. Map reuse: the
            // birth fold's per-tick read when present, else one resolve here.
            List<FireBirthSink.PoseOwnedBirth> poseOwnedBirths =
                    FireBirthSink.drainPoseOwned();
            if (classRoutingActive && !poseOwnedBirths.isEmpty()
                    && tickPositionShipIds != null) {
                for (FireBirthSink.PoseOwnedBirth poseBirth : poseOwnedBirths) {
                    Long poseWireId = tickPositionShipIds.get(poseBirth.ship());
                    if (poseWireId != null) {
                        pilotStep.fireClassLane().demotePoseOwnedMount(
                                poseWireId, poseBirth.mountIndex());
                    }
                }
            }
        }
        long edgesBirthsEnd = System.nanoTime(); // Birth drain + shadow fold + pose-owned demote
        // Truth-source merge — AFTER the shadow fold (the comparator reads
        // RAW tickEdges): covered-mount detector discharges suppressed, confessed
        // births admitted at the TRUE fire tick; only on ticks the detector observed
        // (an unobserved tick stays identity — a gate flap must not fabricate a
        // half-truth stream). The merged list feeds the decl dispatch + compose below.
        List<GameplayEdgeEvent> effectiveEdges = tickEdges;
        if (edgeObserveRan && mountsTier != null) {
            effectiveEdges = onFireTruthMerger.merge(snap.simTick(), tickEdges,
                    mountsTier, tickBirths, birthPositionShipIds,
                    source.lastEntityIdByPositionShipId(),
                    FireBirthSink.coveredWeaponIds(),
                    // The published flux + cloak tiers select
                    // the merger's 9-arg overload, whose det_fallback branch refuses a no-twin discharge on a
                    // PHASED shooter (the engine cancels the burst and spawns nothing: the phase-in phantom).
                    snap.fluxStates(), snap.phaseCloakStates());
        }
        // gpee_aim_* count the detector discharges the WIRE carries.
        fireLaneDischargeDetector.countServedAim(effectiveEdges,
                edgeObserveRan && mountsTier != null ? onFireTruthMerger : null);
        long edgesMergeEnd = System.nanoTime(); // Truth merge
        // The effective edge stream + per-discharge bearing
        // tier/weaponId → the per-run watch log (instrument; null = knob blank/off).
        // Format + enqueue ONLY — the file write+flush
        // rides the sink's own thread; the measured stalls were this call's synchronous
        // NTFS flush on an HDD volume (every stall tick = an edge tick, io_det=0.00).
        if (gpeeLog != null && !effectiveEdges.isEmpty()) {
            gpeeLog.appendTick(round, effectiveEdges, onFireTruthMerger);
        }
        long detEdgesEnd = System.nanoTime(); // Discharge detector + birth drain + truth merge + gpee log
        // Measure riders off the SAME tick's raw surfaces
        // (RAW tickEdges, pre-merge — the (v) question is the DETECTOR's blindness).
        // The canister probe's cloak edge-detector needs the full
        // reflective cloak walk EVERY tick, so it is knob-gated (default OFF), not lazy.
        if (canisterServeProbe != null) {
            canisterServeProbe.tick(snap.simTick(), binding.shipPhaseCloakStatesOrNull(),
                    binding::projectileEntitiesOrNull, binding::missileEntitiesOrNull,
                    tickEdges);
        }
        // F4c: BEFORE the despawn observe below, so a same-tick dive start is
        // latched when the DEPARTED override reads it.
        phaseDiveProbe.tick(snap.simTick(), () -> tickPositionShipIds,
                source::lastEntityIdByPositionShipId);
        // Drain this tick's arc hits UNCONDITIONALLY (take-and-clear,
        // never stale); the wire ride stays eventLaneOn-gated at the compose below.
        List<GameplayEdgeEvent> arcHitEdges = arcHitProbe.drain(snap.simTick(),
                () -> tickPositionShipIds, source::lastEntityIdByPositionShipId,
                binding::seatShipObjects, binding::missileStableIdOrMinus1); // beam feed
        // Split edges fold off the SAME
        // published snap the despawn detector consumes, widened by the binding's
        // per-tick side channels — projectile-child births (the sabot ballistic
        // lane) + the vanished-parent end-cause triage (the false-FX veto); rare
        // (volley-scale) — one INFO row each, lane-tagged for the watch dossier.
        List<GameplayEdgeEvent> splitEdges = missileSplitDetector.observe(snap,
                binding.lastMirvChildBirthsXY(),
                binding.lastRemovedMissileEndCauses(),
                // The delisted missiles' last ship impacts, resolved to wire
                // ids through the arc-hit probe's supplier pair (victim unresolved ⇒ ABSENT).
                MissileImpactMemo.toContacts(binding.lastRemovedMissileImpacts(),
                        tickPositionShipIds, source.lastEntityIdByPositionShipId()));
        List<String> splitLanes = missileSplitDetector.lanesForLastObserve();
        for (int splitIdx = 0; splitIdx < splitEdges.size(); splitIdx++) {
            GameplayEdgeEvent splitEdge = splitEdges.get(splitIdx);
            System.out.println("npeer-serve missile_split parent=" + splitEdge.entityId()
                    + " x=" + splitEdge.splitX() + " y=" + splitEdge.splitY()
                    + " lane=" + (splitIdx < splitLanes.size()
                            ? splitLanes.get(splitIdx) : "?")
                    + " round=" + round + " tick=" + snap.simTick());
        }
        // End-cause markers for
        // EVERY vanished missile (sourced included) off the SAME observe (the
        // detector diffed the record out this tick). Metered per round, never
        // per-event logged (missile-family volume, ~hundreds/match).
        List<GameplayEdgeEvent> missileEndEdges =
                missileSplitDetector.endEventsForLastObserve();
        for (int endIdx = 0; endIdx < missileEndEdges.size(); endIdx++) {
            GameplayEdgeEvent endEdge = missileEndEdges.get(endIdx);
            switch (endEdge.endCauseCode()) {
                case GameplayEdgeEvent.END_CAUSE_DESTROYED -> gpeeMissileEndDestroyed++;
                case GameplayEdgeEvent.END_CAUSE_FIZZLING -> gpeeMissileEndFizzling++;
                case GameplayEdgeEvent.END_CAUSE_SHIELD_ABSORBED -> gpeeMissileEndShield++; //
                default -> gpeeMissileEndIntact++;
            }
            if (endEdge.endVictimWireId() > 0L) {
                gpeeMissileEndNamed++;
            }
        }
        // autofire-target pick poll+diff — unconditional per tick
        // like its sibling event lanes; the wire ride stays eventLaneOn-gated at the
        // compose below. Identity resolves through the discharge-detector map pair.
        List<GameplayEdgeEvent> afTargetEdges = afTargetLane.observe(
                snap.simTick(), seatIdByWireId,
                tickPositionShipIds,
                source.lastEntityIdByPositionShipId());
        // Paladin dets born this tick (binding side
        // channel, the replaced-at-start law). Rare (det-scale, ~408/reel) —
        // one INFO row each for the watch dossier (missile_split idiom).
        List<GameplayEdgeEvent> paladinDetEdges = paladinDetLane.observe(
                snap.simTick(), binding.lastPaladinDets(),
                binding.lastPaladinDetSkipUnknown());
        for (GameplayEdgeEvent detEdge : paladinDetEdges) {
            System.out.println("npeer-serve paladin_det src=" + detEdge.entityId()
                    + " mount=" + detEdge.mountIndex()
                    + " x=" + detEdge.detX() + " y=" + detEdge.detY()
                    + " round=" + round + " tick=" + snap.simTick());
        }
        List<EntityDespawnEvent> tickDespawns = despawnDetector.observe(snap);
        if (!tickDespawns.isEmpty()) {
            transferLane.markCensusDirty();
        }
        // hulk-evidence reason correction, BEFORE both consumers
        // (EDSP compose + the (f) T3 seat loop read the same corrected list). The
        // detector classifies from the LAST-SEEN hull, so a kill blow that lands
        // and despawns inside one tick misreads DEPARTED (wire_id=1
        // healthy at tick 10140, seat_mount_loss cause=HULL_DEPARTED @10146 while
        // hulk_pose_fan minted its wreck the SAME tick — and the client SKIPPED
        // the death presentation on the DEPARTED read). A wreck exists ⇒ that
        // ship was DESTROYED; departed ships never mint a corpse family.
        // Second evidence = the engine's own UNHEALTHY repository
        // removal deposited at the (d2) drain this tick (a hulkless kill — the
        // shrouded tendril — mints no corpse family); DespawnReasonEvidence holds
        // the WHY.
        Set<Long> hulkRootsBorn = source.hulkFamilyRootsBornThisTick();
        if (!tickDespawns.isEmpty()) {
            // The correction law lives in
            // DespawnReasonEvidence.correctDepartedReads (contract-rowed) — it
            // consumes for EVERY despawned id BEFORE the reason filter, so a
            // natively-DESTROYED kill's deposit never pends all round and a seat
            // id re-pointed at the T4 remount cannot have its refield hull's later
            // healthy retreat (DEPARTED) flipped to DESTROYED on the wire.
            final int roundNow = round;
            final long tickNow = snap.simTick();
            tickDespawns = despawnReasonEvidence.correctDepartedReads(
                    tickDespawns, hulkRootsBorn,
                    (d, evidence) -> System.out.println(
                            "npeer-serve despawn_reason_corrected id="
                            + d.entityId() + " DEPARTED->DESTROYED"
                            + " evidence=" + evidence
                            + " round=" + roundNow + " tick=" + tickNow),
                    // Still-DEPARTED ship despawn: remember it so a LATE corpse
                    // family (the detector doc hedges "~1-2 ticks") prints loud at
                    // the (f3) fan below instead of passing silently — the rider
                    // watch decides whether that lag class exists before any code
                    // holds events for it.
                    d -> departedShipDespawnTickById.put(d.entityId(), tickNow));
        }
        // Dive despawns classify DEPARTED — the inverse of the hulk-evidence correction above.
        // A dived hull LEAVES alive at HP=1 (which reads under the detector's
        // DESTROYED threshold) and mints no corpse family, so this override runs
        // AFTER the hulk correction and holds the last word for latched ids.
        // The latch is consumed ONE-SHOT at the first despawn of the latched
        // id (take-first, even on a native DEPARTED read — one dive pairs with
        // exactly one despawn); wire ids rebind to the refield hull at respawn
        // (the EDGE law), so the old LEVEL read turned every post-respawn death
        // of the once-dived seat into DEPARTED.
        if (!tickDespawns.isEmpty()) {
            List<EntityDespawnEvent> diveCorrected = null;
            for (int di = 0; di < tickDespawns.size(); di++) {
                EntityDespawnEvent d = tickDespawns.get(di);
                if (!phaseDiveProbe.takeDiveLatch(d.entityId())
                        || d.reason() == EntityDespawnReason.DEPARTED) {
                    continue;
                }
                if (diveCorrected == null) {
                    diveCorrected = new ArrayList<>(tickDespawns);
                }
                diveCorrected.set(di,
                        EntityDespawnEvent.departed(d.entityId(), d.simTick()));
                System.out.println("npeer-serve phase_anchor_departed_override id="
                        + d.entityId() + " " + d.reason() + "->DEPARTED"
                        + " evidence=dive_latch_taken"
                        + " round=" + round + " tick=" + snap.simTick());
            }
            if (diveCorrected != null) {
                tickDespawns = diveCorrected;
            }
        }
        long detDespawnEnd = System.nanoTime(); // Canister/escort/dive probes + split/afTarget/paladin/despawn observes
        // growth-id despawns HOLD ≤2 ticks for seam truth (fighter
        // max hull sits under the detector's 800 DESTROYED threshold — absence alone
        // would mint an HE death for every dock). The RAW list keeps feeding the
        // composer evict below + the T3 seat loop (eviction is reason-agnostic
        // ground truth; seats are never growth ids) — ONLY the EDSP compose arg
        // takes the folded list. Null reclassifier (growth OFF) = verbatim.
        List<EntityDespawnEvent> composedDespawns = growthReclassifier == null
                ? tickDespawns : growthReclassifier.fold(tickDespawns, snap.simTick());
        // The WIRE-truth despawn row, one per composed EDSP event —
        // the server's own LIFE/DEATH seam for EVERY entity class. Fighters / growth ids
        // mint no hulk_pose_fan, seat_mount_loss or dmg-taken hull=0 row: a Threat swarm
        // unit removed by an engine script (no damage row, engine seam unread) left
        // the threat flight with NO server row at all, so grade-foe-hull.py step 0
        // read "no kill seam" for a death the client had applied on this very event.
        // Printed AFTER the growth fold so the row IS what rides (at= the event's tick).
        for (EntityDespawnEvent d : composedDespawns) {
            System.out.println("npeer-serve despawn id=" + d.entityId()
                    + " reason=" + d.reason() + " at=" + d.simTick()
                    + " round=" + round + " tick=" + snap.simTick());
        }
        // AWSD anchor evict — UNGATED (the compose arg below
        // stays eventLaneOn-gated for the WIRE; eviction is ground truth every tick).
        downComposer.onEntityDespawns(tickDespawns);
        // AWSD NACK drain — the reader thread's per-session
        // deposits fold into the composer's pending-keyframe set HERE, before the
        // compose at (h) (a separate drain: the (j) UP take ran after compose until
        // a later change moved it pre-step), so a NACKed entity re-keys THIS tick when present.
        for (PilotIntentDatagramServer.PeerSession session : live) {
            downComposer.requestKeyframes(session.takeAwsdNacksOrEmpty());
        }
        // W7b /suicide drain — post-capture, so the kill rides the
        // next advance: the reader-thread deposit becomes a sim-thread engine
        // kill HERE. The dispatch-time mounted check was advisory; this re-check
        // is the truth. Null hook (contract fakes) = fail-soft refuse, the
        // operator-verb law.
        for (PilotIntentDatagramServer.PeerSession session : live) {
            // A re-simulation drains the recorded /suicide of the seat
            // (the sealed door's own flag, never set, is still taken: a set one is a counted stray).
            boolean suicideAsked = replayFeed == null ? session.takeSuicideRequested()
                    : replayFeed.suicideRequested(round, snap.simTick(), session.seatId(),
                            session.takeSuicideRequested());
            if (!suicideAsked) {
                continue;
            }
            int suicideSeatId = session.seatId();
            // Each branch names its outcome; one SUIC row per request.
            int suicideOutcome;
            if (hooks.seatSuicide() == null) {
                suicideOutcome = MatchRecordRows.SUIC_REFUSED_NO_HOOK;
                System.out.println("npeer-serve seat_suicide_refused seat="
                        + suicideSeatId + " reason=no_hook_bound"
                        + " round=" + round + " tick=" + snap.simTick());
            } else if (rebindArbiter.isMounted(suicideSeatId)) {
                suicideOutcome = MatchRecordRows.SUIC_REFUSED_MODULE_MOUNTED;
                // W2b E4: drain-time twin of the dispatch refusal — a /suicide staged
                // before the mount landed must never free-kill a fortress module.
                System.out.println("npeer-serve seat_suicide_refused seat="
                        + suicideSeatId + " reason=module_mounted"
                        + " round=" + round + " tick=" + snap.simTick());
            } else if (authority.isMounted(suicideSeatId)
                    && !authority.isDeadLatched(suicideSeatId)) {
                boolean killed = hooks.seatSuicide().killSeatHull(session.shipId());
                suicideOutcome = killed ? MatchRecordRows.SUIC_KILLED : MatchRecordRows.SUIC_NOT_KILLED;
                System.out.println("npeer-serve seat_suicide seat=" + suicideSeatId
                        + " wire_id=" + session.shipId() + " killed=" + killed
                        + " round=" + round + " tick=" + snap.simTick());
            } else {
                suicideOutcome = MatchRecordRows.SUIC_SKIPPED_NOT_MOUNTED;
                System.out.println("npeer-serve seat_suicide_skipped seat="
                        + suicideSeatId + " reason=not_mounted"
                        + " round=" + round + " tick=" + snap.simTick());
            }
            if (recordRowsArmed) {
                door.recordRow(MatchRecordRows.KIND_SUIC, MatchRecordRows.encodeSuic(new MatchRecordRows.SuicRow(
                        suicideSeatId, session.shipId(), suicideOutcome), MatchRecordRows.BAND_POST, round),
                        snap.simTick());
            }
            if (replayFeed != null) {
                replayFeed.noteSuicideOutcome(round, snap.simTick(), suicideSeatId, session.shipId(), suicideOutcome);
            }
        }
        // W15 v0: custom-variant drain — mint → engine pass → register →
        // VDEF fan → VACK on the sim thread, ≤ 4/tick (+ ≤ 1 catalog-promote pass first),
        // in the /suicide band so a registered id is resolvable before any RAPP
        // row can name it.
        if (replayFeed != null) {
            // The frame's recorded VSUB rows, re-admitted and drained at
            // this site's stamps; the live drain below then finds the ledger empty.
            replayVsubs(replayFeed.vsubsAt(round, snap.simTick(), MatchRecordRows.BAND_POST), round, snap.simTick(),
                    MatchRecordRows.BAND_POST);
        }
        if (customLaneOrNull != null) {
            customLaneOrNull.drainTick(round, snap.simTick(), MatchRecordRows.BAND_POST,
                    recordRowsArmed ? rowSink : null);
        }
        // lane-UNGATED dispatch through the one leaf (the retired
        // serve() twin's comment — git history; the SIBLING-LANE SWEEP landed it in
        // both then-live datagram lanes, and this is the lane that remains).
        // GPEE v16: the returned list carries each consumed declaration's declared
        // wait on its discharge edge (the SAME instance when nothing was stamped) — the compose and
        // the CCWR recorder below read it; countServedAim / gpeeLog above saw the unstamped edges.
        effectiveEdges = pilotStep.dispatchDischargeEdges(effectiveEdges);
        // (f) T3: a descriptor-band despawn IS a seat mount loss — the ONE seat-death
        // truth (roster ABSENCE stays the client's kill edge; the seat machine prices
        // respawn policy internally). The "!mounted THROW is unreachable" claim
        // was FALSIFIED by voluntary dismount: the
        // W2b drain records the loss + re-keys the module PRE-advance, so this pass
        // then sees the seat's wire id leave the roster as a bookkeeping ECHO of a
        // loss already fully recorded — live map included (dismountStationModule
        // vacates it, so even the vacate below would double-throw). The seat machine
        // arms that expectation tick-stamped (recordVoluntaryDismount) and the
        // consult below consumes it one-shot; every OTHER seat-mapped despawn stays
        // unconditional fail-closed — a !mounted throw there is still a wiring bug.
        // The SAME despawn vacates the roster's live wire map through
        // the hook (this lane's T4 remount carried the identical latent crash — the
        // fly-in guard THROWS on the dead hull's never-vacated entry, twin of a crash
        // the legacy lane hit); unconditional like the mount-loss record — the hull leaving
        // the world is ground truth regardless of respawn policy.
        for (EntityDespawnEvent despawn : tickDespawns) {
            // W3 L2 P4: the hull leaving the world aborts every
            // transfer flight toward it — seat-mapped or AI alike; the arbiter
            // rows consume LOUD and the pods turn home (serve-lane fanAbort).
            transferLane.noteHullDespawned(despawn.entityId(), snap.simTick());
            Integer lostSeatId = seatIdByWireId.get(despawn.entityId());
            if (lostSeatId != null) {
                // A parked takeover's commit frees the displaced
                // seat's own wire id (loss already recorded at the drain pre-step) —
                // consume that one-shot id-verified echo FIRST; mutually exclusive
                // with the dismount echo by cause, all other despawns fail-closed.
                if (authority.consumeTakeoverDespawnEcho(
                        lostSeatId, despawn.entityId(), snap.simTick())) {
                    System.out.println("npeer-serve seat_mount_loss_skipped"
                            + " reason=takeover_displaced seat=" + lostSeatId
                            + " wire_id=" + despawn.entityId()
                            + " round=" + round + " tick=" + snap.simTick());
                    continue;
                }
                if (authority.consumeDismountDespawnEcho(lostSeatId, snap.simTick())) {
                    System.out.println("npeer-serve seat_mount_loss_skipped"
                            + " reason=voluntary_dismount seat=" + lostSeatId
                            + " wire_id=" + despawn.entityId()
                            + " round=" + round + " tick=" + snap.simTick());
                    continue;
                }
                SeatStateCause lossCause =
                        despawn.reason() == EntityDespawnReason.DESTROYED
                                ? SeatStateCause.HULL_DEATH : SeatStateCause.HULL_DEPARTED;
                authority.recordMountLoss(lostSeatId, lossCause, snap.simTick());
                hooks.flyIn().vacate(lostSeatId, despawn.entityId());
                // W2b E4: a module-mounted seat's body leaving the world frees the
                // seat AND deadens the slot — later claims on it deny/stale-loud.
                String deadToken = rebindArbiter.mountedTokenOrNull(lostSeatId);
                if (deadToken != null) {
                    rebindArbiter.noteSlotDead(deadToken);
                    rebindArbiter.noteDismounted(lostSeatId);
                }
                // The legacy operator line, ported — same-tick
                // dual-loss parity keys on the trailing tick= (the tickOf anchor).
                System.out.println("npeer-serve seat_mount_loss seat=" + lostSeatId
                        + " wire_id=" + despawn.entityId() + " cause=" + lossCause
                        + " round=" + round + " tick=" + snap.simTick());
                // The retired serve() lane's hull-loss
                // decl-flush mirror (the finding-B fix landed in
                // serve() only at first, so this path still jammed the dead hull's
                // pending decl FIFO into expiries).
                // Keyed by the seat's FIXED wire
                // id (applyStateByShipId), never the seat id (equal only under v1).
                int flushedDecls =
                        pilotStep.flushDeclarationsOnHullLoss(despawn.entityId());
                if (flushedDecls > 0) {
                    System.out.println("pilot-fire-decl flushed reason=hull_loss ship="
                            + lostSeatId + " wire_id=" + despawn.entityId()
                            + " count=" + flushedDecls
                            + " round=" + round + " tick=" + snap.simTick());
                }
                // Drop the dead hull's staged frame + retreat
                // edge memory — its last frame (bit-9=1 mid-dive/retreat) otherwise
                // replays onto the refield hull and instantly re-departs it.
                if (pilotStep.clearStagedIntentOnHullLoss(despawn.entityId())) {
                    System.out.println("npeer-serve staged_intent_dropped"
                            + " reason=hull_loss seat=" + lostSeatId
                            + " wire_id=" + despawn.entityId()
                            + " round=" + round + " tick=" + snap.simTick());
                }
            }
        }
        long detSeatEnd = System.nanoTime(); // Reclassifier fold + evict + drains + edge dispatch + seat-mount loss
        // (f1b) drain hulk-cut-geometry records into the HKCG ctrl
        // fan BEFORE the (f2) HKFR loop — drain-loop order alone puts a cut's 4
        // geometry records ahead of its paired PIECE_ADDED edge on the strict
        // per-session FIFO ctrl spool, so the client latch is warm at edge arrival.
        // Operator line = FIRST record per cut only (parent-exact leads);
        // volume rides hkcg_down.
        for (HulkCutGeometryFrameCodec.CutGeometryRecord cutRecord
                : source.drainCutGeometryRecords()) {
            worstFrames.note(snap.simTick(), "hulk_cut_fan");
            door.fanHulkCutGeometry(cutRecord);
            hkcgDownEvents++;
            if (hulkCutGeometryCutsLogged.add(cutRecord.rootEntityId() + ":"
                    + cutRecord.deathTick() + ":" + cutRecord.cutSeq())) {
                System.out.println("npeer-serve hulk_cut_geometry_fan root="
                        + cutRecord.rootEntityId()
                        + " deathTick=" + cutRecord.deathTick()
                        + " cutSeq=" + cutRecord.cutSeq()
                        + " points=" + cutRecord.points().length / 2
                        + " tick=" + cutRecord.simTick()
                        + " round=" + round);
            }
        }
        // (f1c) drain capture-skip descriptors — one INFO row per
        // skip (the observability gap: the aggregate counter printed nowhere
        // and the shape-divergence diagnosis cost a guess). Skip volume = bare-ADD
        // volume (single-digit per match), so every row prints un-deduped.
        for (HulkFractureDetector.CutGeometrySkip cutSkip
                : source.drainCutGeometrySkips()) {
            hkcgSkipEvents++;
            System.out.println("npeer-serve hulk_cut_geometry_skip root="
                    + cutSkip.rootEntityId()
                    + " deathTick=" + cutSkip.deathTick()
                    + " leg=" + cutSkip.leg()
                    + " fresh=" + cutSkip.freshCount()
                    + " moved=" + cutSkip.movedCount()
                    + " parent_points=" + cutSkip.parentPoints()
                    + " clone_points=" + cutSkip.clonePoints()
                    + " tick=" + cutSkip.simTick()
                    + " round=" + round);
        }
        // (f2) drain server-authoritative hulk-fracture edges into
        // the HKFR ctrl fan (the SNMR lane — reliable ctrl spool, deliberately
        // CROSS-lane vs the (h) EDSP death edge; the client parks early records).
        for (HulkFractureFrameCodec.FractureEdge fractureEdge
                : source.drainFractureEdges()) {
            worstFrames.note(snap.simTick(), "hulk_fracture_fan");
            door.fanHulkFracture(fractureEdge);
            hkfrDownEvents++;
        }
        // (f3) drain corpse-pose keyframes into the HKPS ctrl fan —
        // AFTER the same tick's HKFR edges on the SAME reliable ctrl spool (the
        // client's rank interpretation is race-free by ordering). Operator line =
        // FIRST keyframe per family only (the once-only log law); volume rides hkps_down.
        for (HulkPoseFrameCodec.PoseKeyframe poseKeyframe
                : source.drainHulkPoseKeyframes()) {
            worstFrames.note(snap.simTick(), "hulk_pose_fan");
            door.fanHulkPose(poseKeyframe);
            hkpsDownEvents++;
            if (hulkPoseFamiliesLogged.add(
                    poseKeyframe.rootEntityId() + ":" + poseKeyframe.deathTick())) {
                System.out.println("npeer-serve hulk_pose_fan root="
                        + poseKeyframe.rootEntityId()
                        + " deathTick=" + poseKeyframe.deathTick()
                        + " pieces=" + poseKeyframe.pieceCount()
                        + " tick=" + poseKeyframe.simTick()
                        + " round=" + round);
                // late-evidence instrument: this family's root was
                // already published DEPARTED on an EARLIER tick — the same-tick
                // correction missed it (the "~1-2 tick" first-sight lag class).
                // Loud only; the rider watch decides whether the class is real.
                // Gap-bounded to the documented lag class so a genuine
                // depart -> T4 remount -> real death (same wire id, gap of
                // hundreds of ticks) never prints a stale-entry false positive.
                Long departedAtTick = departedShipDespawnTickById
                        .remove(poseKeyframe.rootEntityId());
                if (departedAtTick != null
                        && departedAtTick < poseKeyframe.simTick()
                        && poseKeyframe.simTick() - departedAtTick <= 10L) {
                    System.out.println(
                            "npeer-serve despawn_reason_late_hulk_evidence id="
                            + poseKeyframe.rootEntityId()
                            + " published=DEPARTED@" + departedAtTick
                            + " hulk_family_born_tick=" + poseKeyframe.simTick()
                            + " (correction MISSED - published event stands)"
                            + " round=" + round);
                }
            }
        }
        asteroidLane.observe(snap.simTick(), door::fanAsteroidPose); // post-step poll, after HKPS, before AIMP
        // (f4) AIMP per-piloted-seat aim-point fan —
        // change-gated quantized batch off the staged PINT frames (aim already
        // arrives UP per tick; this fans the freshest per-seat point DOWN at
        // ~8 Hz). The shared frames of each due tick — ceil(rows / 64) of them, every due row in
        // exactly one, all fanned THIS tick, never a seat rotation across ticks
        // — to ALL sessions (the rule is selection-not-minting; the
        // client drops its OWN row). Lane never throws (fail-soft disarm); first-per-round operator
        // line only (the same once-only log law).
        if (aimFanLane != null) {
            List<byte[]> aimFrames = aimFanLane.composeDueFramesOrEmpty(
                    snap.simTick(), pilotStep.stagedIntentsView());
            if (!aimFrames.isEmpty()) {
                worstFrames.note(snap.simTick(), "pilot_aim_fan"); // once per due tick, whatever its frame count
                for (int i = 0; i < aimFrames.size(); i++) {
                    door.fanPilotAim(aimFrames.get(i));
                }
                if (aimFanLane.dueTicksComposed() == 1) {
                    System.out.println("npeer-serve pilot_aim_fan first rows="
                            + aimFanLane.lastTickRowCount()
                            + " every_ticks=" + aimFanEveryTicks
                            + " tick=" + snap.simTick()
                            + " round=" + round
                            + " frames=" + aimFrames.size());
                }
            }
        }
        // (g) this tick's SEAT delta frames (single-writer stamp inside the authority).
        List<byte[]> seatFrames = encodeSeatEvents(authority.drainSeatEvents(snap.simTick()));
        long witnessDetectEndNanos = System.nanoTime();
        // The det bracket was ONE monolithic timer over ~26 sub-steps, so
        // 7/12 of one run's >100 ms stalls read causes=- and the rest named a coincidental fan.
        // Six fixed stamps (the minimum coherent split; ~0.2 µs/tick) name the burner on stall ticks.
        detSplitRows = DetBracketAttribution.attributeDetSplit(worstFrames, snap.simTick(), round, detSplitRows,
                witnessStepEndNanos, detGrowthEnd, detReadersEnd, detEdgesEnd, detDespawnEnd,
                detSeatEnd, witnessDetectEndNanos);
        // Finer split naming the burner INSIDE the det_edges
        // bracket on stall ticks (WHY + format: DetBracketAttribution.attributeDetEdgesSplit).
        detEdgesSplitRows = DetBracketAttribution.attributeDetEdgesSplit(
                detEdgesSplitRows, snap.simTick(), round, detReadersEnd,
                edgesMountsEnd, edgesObserveEnd, edgesBirthsEnd, edgesMergeEnd,
                detEdgesEnd, effectiveEdges.size(), tickBirths.size());
        long witnessIoDetectEnd = ServeStdoutIoMeter.nanosOf(ioMeter);
        // (h) compose ONCE above the fan: the shared composer splits the AWSS
        // under the datagram payload cap and encodes every frame
        // once; ctrl lane = GPEE + EDSP + SEAT in wire order.
        // Lane edges concat at the compose arg ONLY (identity when
        // no lane fired this tick); order + WHY = DownLaneSeams.concatDownEdges.
        List<GameplayEdgeEvent> downEdges = DownLaneSeams.concatDownEdges(effectiveEdges,
                splitEdges, missileEndEdges, afTargetEdges, paladinDetEdges, arcHitEdges, critEdges);
        // Drain UNCONDITIONALLY (pending must not accumulate
        // across lane-off ticks); the wire ride stays eventLaneOn-gated like its
        // sibling event lanes. DIVE frames are ctrl-fan-only (SEAT precedent —
        // outside the v1 KIND_DOWN record contract).
        List<cc.starsector.server.protocol.PhaseDiveEvent> diveStarts =
                phaseDiveProbe.drainDiveStarts();
        // Each dive start freezes that seat's bit-9 retreat lane —
        // the level re-assert was clearing the dive script's per-frame
        // setRetreating(true) ahead of the CFM reaper's read every tick (a recorded
        // run measured retreat_reasserts=2981 == dive→round-end ticks), so the dived
        // hull never reaped. Hull loss (the reap's DEPARTED) unfreezes the seat.
        for (cc.starsector.server.protocol.PhaseDiveEvent diveStart : diveStarts) {
            pilotStep.latchRetreatLaneOnDive(diveStart.entityId());
        }
        DownTickComposition comp = downComposer.compose(snap,
                eventLaneOn ? downEdges : List.<GameplayEdgeEvent>of(),
                eventLaneOn ? composedDespawns : List.<EntityDespawnEvent>of(),
                eventLaneOn ? diveStarts
                        : List.<cc.starsector.server.protocol.PhaseDiveEvent>of());
        List<byte[]> ctrlFrames = new ArrayList<>(
                comp.gpeeFrames().size() + comp.edspFrames().size()
                        + comp.diveFrames().size() + seatFrames.size());
        ctrlFrames.addAll(comp.gpeeFrames());
        ctrlFrames.addAll(comp.edspFrames());
        ctrlFrames.addAll(comp.diveFrames());
        ctrlFrames.addAll(seatFrames);
        gpeeDownEvents += comp.gpeeFrames().size();
        edspDownEvents += comp.edspFrames().size();
        seatDownFrames += seatFrames.size();
        // (i) fan the SAME bytes per live session, then ONE endpoint-level KIND_DOWN
        // record — EVERY tick, zero-session ticks included (SEAT frames ride the ctrl
        // fan but NOT the v1 record contract). B5 E0: the fan
        // START rotates with the tick, so under egress pressure tail-of-fan loss lands
        // as jitter shared across all seats instead of a standing join-order penalty;
        // E1(b) meters the pass (per-round maxes ride the round line below).
        // Off-ticks withhold the SNAP fan only —
        // ctrl frames + compose + recordTickDown are untouched (record-as-composed
        // keeps the v1 every-tick record contract + every parity walker whole).
        boolean snapThinned =
                downEveryTicks > 1 && snap.simTick() % downEveryTicks != 0L;
        if (snapThinned) {
            snapFanSkippedTicks++;
        }
        List<byte[]> snapFramesToFan =
                snapThinned ? List.<byte[]>of() : comp.snapFrames();
        long witnessComposeEndNanos = System.nanoTime();
        long witnessIoComposeEnd = ServeStdoutIoMeter.nanosOf(ioMeter);
        int fanCount = live.size();
        long fanPassStartNanos = System.nanoTime();
        int fanStart = fanCount == 0 ? 0 : Math.floorMod(snap.simTick(), fanCount);
        for (int fi = 0; fi < fanCount; fi++) {
            PilotIntentDatagramServer.PeerSession session =
                    live.get((fanStart + fi) % fanCount);
            long sendStartNanos = System.nanoTime();
            door.sendTickToSession(session, snap.simTick(), ctrlFrames, snapFramesToFan);
            long sendNanos = System.nanoTime() - sendStartNanos;
            if (sendNanos > sendSlowestNanos) {
                sendSlowestNanos = sendNanos;
            }
            // send-gap meter; send end = the two stamps above, NO new clock read.
            session.snapSendGap.record(sendStartNanos + sendNanos, snap.simTick(),
                    !snapFramesToFan.isEmpty(), downEveryTicks);
        }
        long fanPassNanos = System.nanoTime() - fanPassStartNanos;
        if (fanCount > 0 && fanPassNanos > fanPassMaxNanos) {
            fanPassMaxNanos = fanPassNanos;
        }
        door.recordTickDown(snap.simTick(), comp.composedDownRecord());
        long witnessSendEndNanos = System.nanoTime();
        long witnessIoSendEnd = ServeStdoutIoMeter.nanosOf(ioMeter);
        // (l) rung-0 heartbeat every 300 ticks.
        if (snap.simTick() % 300L == 0L) {
            System.out.println("npeer-serve tick=" + snap.simTick()
                    + " live_sessions=" + live.size()
                    + " occupied=" + authority.occupiedSeatIds().size()
                    + " match_over_armed=" + source.matchOverArmed());
        }
        // (m) bookkeeping + round end. Operator end-match breaks WITHOUT matchOver
        // (the wrap's reset branch is matchOver-gated, so the serve falls straight
        // to the fanGoodbye); end-round rides the matchOver lane — the operator verb
        // bypasses the ≥2-sides ARMING (an explicit command, not a trigger).
        long frameWorkNanos = System.nanoTime() - frameWorkStartNanos;
        workFrames++;
        workNanosTotal += frameWorkNanos;
        if (frameWorkNanos > workMaxNanos) {
            workMaxNanos = frameWorkNanos;
        }
        framesServed++;
        lastSimTick = snap.simTick();
        // The Math.random() caller census's tick boundary (one static boolean unarmed).
        if (RNG_CENSUS_ON) {
            cc.starsector.server.rngpin.CcRngRedirect.censusTick(snap.simTick());
        }
        // A2/A3 governor — feed the speed-INVARIANT work measure, ask
        // the law, apply any committed decision via the manual verb's exact
        // actuator body (same permille seam, same re-anchor; provenance is
        // invisible to the client by design). Dormant with a one-shot receipt
        // when there is nothing to govern (no wall pacing) or no announcement
        // carrier (AWSS-only serve).
        if (autoSpeedArmed) {
            if (!config.paceToWallClock() || !downComposer.deltaLaneOn()) {
                if (!autoSpeedDormantLogged) {
                    autoSpeedDormantLogged = true;
                    System.out.println("npeer-serve sim_speed_auto_dormant reason="
                            + (config.paceToWallClock() ? "delta_lane_off"
                                    : "no_wall_pacing")
                            + " round=" + round + " frame=" + frame
                            + " (armed but nothing to govern; one-shot)");
                }
            } else {
                double autoTarget = Double.NaN;
                String autoReason = null;
                if (autoSnapPending) {
                    // Manual speeds may be off-grid — arming
                    // snaps the working speed onto the governed 0.25 grid inside
                    // [floor, ceiling] so every auto permille is a 250-multiple.
                    autoSnapPending = false;
                    double snapped = autoSpeedLaw.snapToGrid(simSpeed);
                    if (snapped != simSpeed) {
                        autoTarget = snapped;
                        autoReason = "arm_snap";
                    }
                }
                autoSpeedLaw.feed(frameWorkNanos);
                if (Double.isNaN(autoTarget)) {
                    double decided = autoSpeedLaw.decide(simSpeed);
                    if (!Double.isNaN(decided)) {
                        autoTarget = decided;
                        autoReason = decided < simSpeed ? "govern_down"
                                : "govern_up";
                    }
                }
                if (!Double.isNaN(autoTarget)) {
                    simSpeed = autoTarget;
                    simSpeedChanges++;
                    autoSpeedChanges++;
                    downComposer.setSimSpeedPermille(
                            (int) Math.round(simSpeed * 1000.0d));
                    pacerIntervalNanos = (long) (frameTargetNanos / simSpeed);
                    if (config.paceToWallClock() && pacerAnchorNanos != -1L) {
                        pacerAnchorNanos = System.nanoTime();
                        pacerAnchorFrame = frame;
                    }
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "npeer-serve sim_speed_auto speed=%.2f reason=%s"
                                    + " window_over=%d interval_ms=%.2f round=%d"
                                    + " frame=%d (pacer re-anchored; dt frozen;"
                                    + " announced on every AWSD frame)",
                            simSpeed, autoReason,
                            autoSpeedLaw.windowOverAtDecision(),
                            pacerIntervalNanos / 1_000_000.0d, round, frame));
                }
            }
        }
        // per-frame ops feed: throttles internally (1s); the seat-row
        // supplier only runs on an actual write.
        long witnessTailNanos = 0L;
        if (opsFile != null) {
            long witnessTailStartNanos = System.nanoTime();
            opsFile.onFrame(host, port, descriptorSeatCount, round, framesServed,
                    lastSimTick, frameWorkNanos, workFrames, workNanosTotal,
                    frameTargetNanos, door.bytesSentTotal(), door.readerCounters().bytesReceivedTotal,
                    overrunFrames, debtNowNanos, door.sendFailures(),
                    () -> opsSeatRows(live, opsSeatShips, door, CustomVariantOps.ledgerOrNull(customLaneOrNull)));
            witnessTailNanos = System.nanoTime() - witnessTailStartNanos;
        }
        // (38) frame_overrun attribution feed: the NEXT frame's pacer pairs its
        // overrun with this frame's full serve cost (work + ops tail).
        prevFrameNanos = frameWorkNanos + witnessTailNanos;
        // The loud stall line — per-phase breakdown + dominant phase
        // (the witness; tail is OUTSIDE frameWorkNanos, so it gets
        // its own trigger clause — a disk-stalled ops write must not hide).
        // Appended fields only: reason= names the trigger
        // clause (the dominant=tail total_ms=0.97 receipt read as a quirk);
        // adv=/cap= split the step bracket at the engine-advance/wire-capture seam;
        // io_*= are the bracket's serve-thread stdout write+flush nanos and
        // io_ccwr= the frame's sink write+flush delta (all threads) — a stall row
        // now NAMES compute-vs-blocking-file-I/O directly.
        if (frameWorkNanos > STALL_FRAME_NANOS || witnessTailNanos > STALL_FRAME_NANOS) {
            stallFrames++;
            long[] phaseNanos = {
                    witnessPreEndNanos - frameWorkStartNanos - witnessIntentNanos,
                    witnessStepEndNanos - witnessPreEndNanos,
                    witnessDetectEndNanos - witnessStepEndNanos,
                    witnessComposeEndNanos - witnessDetectEndNanos,
                    witnessSendEndNanos - witnessComposeEndNanos,
                    witnessIntentNanos,
                    witnessTailNanos};
            String[] phaseNames = {"pre", "step", "det", "comp", "send", "intent",
                    "tail"};
            int dominant = 0;
            for (int p = 1; p < phaseNanos.length; p++) {
                if (phaseNanos[p] > phaseNanos[dominant]) {
                    dominant = p;
                }
            }
            String stallReason = frameWorkNanos > STALL_FRAME_NANOS
                    ? (witnessTailNanos > STALL_FRAME_NANOS ? "work+tail" : "work")
                    : "tail";
            System.out.println(String.format(java.util.Locale.ROOT,
                    "npeer-serve work_frame_stall tick=%d round=%d frame=%d"
                            + " total_ms=%.2f dominant=%s pre=%.2f step=%.2f det=%.2f"
                            + " comp=%.2f send=%.2f intent=%.2f tail=%.2f"
                            + " adv=%.2f cap=%.2f io_pre=%.2f io_step=%.2f io_det=%.2f"
                            + " io_comp=%.2f io_send=%.2f io_intent=%.2f io_ccwr=%.2f"
                            + " reason=%s"
                            // Appended fields only: the pre
                            // sub-split (op|sess|seat|wave; remainder = out-of-lane).
                            + " pre_op=%.2f pre_sess=%.2f pre_seat=%.2f pre_wave=%.2f"
                            // Appended fields only: adv= split into
                            // the engine's inner advance vs project post-step;
                            // ftreinj= = the reinject shim's slice INSIDE adv_eng.
                            + " adv_eng=%.2f adv_post=%.2f ftreinj=%.2f"
                            // Appended fields only: adv_cpu= = the
                            // sim thread's OWN CPU ms inside adv_eng (wall≫cpu =
                            // descheduled; -1 = unmeasurable); wall_ms= = epoch ms
                            // at row mint (end of this frame's work+tail) — the
                            // anchor that maps a spike tick onto serve.jfr events.
                            // Stall-row-only cost.
                            + " adv_cpu=%.2f wall_ms=%d",
                    lastSimTick, round, frame,
                    frameWorkNanos / 1_000_000.0d, phaseNames[dominant],
                    phaseNanos[0] / 1_000_000.0d, phaseNanos[1] / 1_000_000.0d,
                    phaseNanos[2] / 1_000_000.0d, phaseNanos[3] / 1_000_000.0d,
                    phaseNanos[4] / 1_000_000.0d, phaseNanos[5] / 1_000_000.0d,
                    phaseNanos[6] / 1_000_000.0d,
                    source.lastAdvanceNanos() / 1_000_000.0d,
                    source.lastCaptureNanos() / 1_000_000.0d,
                    (witnessIoPreEnd - witnessIoFrameStart - witnessIoIntentNanos) / 1_000_000.0d,
                    (witnessIoStepEnd - witnessIoPreEnd) / 1_000_000.0d,
                    (witnessIoDetectEnd - witnessIoStepEnd) / 1_000_000.0d,
                    (witnessIoComposeEnd - witnessIoDetectEnd) / 1_000_000.0d,
                    (witnessIoSendEnd - witnessIoComposeEnd) / 1_000_000.0d,
                    witnessIoIntentNanos / 1_000_000.0d,
                    (door.sinkIoNanosTotal() - witnessCcwrFrameStart) / 1_000_000.0d,
                    stallReason,
                    (preOpEndNanos - preSessEndNanos) / 1_000_000.0d, // (b) runs first, then (a2), then (c)
                    (preSessEndNanos - frameWorkStartNanos) / 1_000_000.0d,
                    (preSeatEndNanos - preOpEndNanos) / 1_000_000.0d,
                    (witnessPreEndNanos - preSeatEndNanos - witnessIntentNanos) / 1_000_000.0d,
                    source.lastEngineAdvanceNanos() / 1_000_000.0d,
                    source.lastPostStepNanos() / 1_000_000.0d,
                    source.lastReinjectNanos() / 1_000_000.0d,
                    EngineBackedCombatStepBoundary.advCpuMs(
                            source.lastEngineAdvanceCpuNanos()),
                    System.currentTimeMillis()));
        }
        // Scale-lane row 2: every frame's brackets feed the window (fed AFTER the
        // onFrame compose above, so a window never includes its own write frame).
        if (phaseWindowStats != null) {
            phaseWindowStats.feed(
                    witnessPreEndNanos - frameWorkStartNanos - witnessIntentNanos,
                    witnessStepEndNanos - witnessPreEndNanos,
                    witnessDetectEndNanos - witnessStepEndNanos,
                    witnessComposeEndNanos - witnessDetectEndNanos,
                    witnessSendEndNanos - witnessComposeEndNanos,
                    witnessIntentNanos,
                    witnessTailNanos);
        }
        // (104)(d): every frame feeds the worst-frames ledger (steady-state cost =
        // one compare; rows flush at the round boundary).
        worstFrames.observe(lastSimTick, frame, frameWorkNanos,
                witnessPreEndNanos - frameWorkStartNanos - witnessIntentNanos,
                witnessStepEndNanos - witnessPreEndNanos,
                witnessDetectEndNanos - witnessStepEndNanos,
                witnessComposeEndNanos - witnessDetectEndNanos,
                witnessSendEndNanos - witnessComposeEndNanos,
                witnessIntentNanos,
                witnessTailNanos,
                source.lastAdvanceNanos(), source.lastCaptureNanos(),
                preOpEndNanos - preSessEndNanos,
                preSessEndNanos - frameWorkStartNanos,
                preSeatEndNanos - preOpEndNanos,
                witnessPreEndNanos - preSeatEndNanos - witnessIntentNanos,
                source.lastEngineAdvanceNanos(), source.lastPostStepNanos(),
                source.lastReinjectNanos(), source.lastEngineAdvanceCpuNanos());
        if (operatorEndMatch) {
            return false;
        }
        if (operatorEndRound) {
            matchOver = true;
            return false;
        }
        if (source.matchOver()) {
            // On the serve-ending round a decisive outcome
            // with a surviving side holds the serve open — NORMAL frames keep
            // running (intents, sim, snapshots) so survivors can retreat — before
            // the MOVR fan/goodbye below. Non-terminal rounds reset immediately as
            // before; operator ends break above; the frame budget truncates the
            // hold via the loop bound. ELWSS triggers are !matchOver-guarded, so
            // the latch neither re-fires nor re-dumps during the hold.
            matchOver = true;
            boolean serveEnding = round >= config.roundsPerSocket() - 1;
            boolean sessionsEmpty = door.liveSessions().isEmpty();
            if (serveEnding && lingerLaw.noteDecisiveLatch(frame,
                    source.matchOverWinnerSide(), sessionsEmpty)) {
                System.out.println("npeer-serve linger_hold tag="
                        + source.matchOverTag()
                        + " winner=" + source.matchOverWinnerSide()
                        + " ticks=" + lingerLaw.lingerTicks()
                        + " sec=" + lingerLaw.lingerSec());
            }
            if (!serveEnding || !lingerLaw.holding(frame, sessionsEmpty)) {
                if (lingerLaw.armed()) {
                    System.out.println("npeer-serve linger_done reason="
                            + lingerLaw.endReason(sessionsEmpty)
                            + " frame=" + frame);
                }
                return false;
            }
        }
        if (!config.paceToWallClock() && config.framePaceMillis() > 0L) {
            try {
                Thread.sleep(config.framePaceMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while pacing npeer frames", ie);
            }
        }
        return true;
    }

    /**
     * Apply ONE {@code admin-cid} arm: the operator's own seat key goes onto the door (a later HELLO with
     * that cid is admitted as admin and outside the per-address seat cap; a live holder is elevated now),
     * then ONE {@code operator_admin_cid} stdout row. Two callers, one row shape: the in-round dispatch
     * ({@link #handleOperatorCommand}) and the client-wait gate ({@link LobbyOperatorGate}), which applies
     * an arm read in the lobby at once instead of carrying it, so the window it was minted for is admitted
     * as admin rather than plain and capped. That same gate is polled in each round's start hold too (the
     * pre-tick loop above (a2), {@code lobbyGate.getAsBoolean()}), so an arm read there is applied the same
     * way (the merged lens's door reader). The row prints the cid in the door's own log form
     * ({@code PeerText.quoteCid}: a prefix of a long cid), never the key; a replayed arm's redacted text
     * decodes to no cid ("").
     * <p>Reel: a lobby or round-start-hold arm writes NO CMD row — the reel's CMD row is written only at the
     * in-round (a2) apply site, with its round and tick. A re-simulation loses nothing by it:
     * it reads no operator file, and its door answers {@code replay} to every arm and admits nobody.
     * <p>Threads + faults: both callers are the sim thread (the in-round poll; the wait-slice probe), so
     * no new lock or thread; the door takes only its own lock, sends any receipt after that hold, and never
     * throws (every refusal is a status token), so the lobby caller, which has no fault belt, needs none.
     *
     */
    private void armAdminCid(PilotIntentDatagramServer door, OperatorCommandFile.Command command) {
        PilotIntentDatagramServer.ArmOutcome o = door.armAdminCid(command.text());
        System.out.println("npeer-serve operator_admin_cid client="
                + PeerText.quoteCid(BanSet.cidOfKeyOrNull(command.text()))
                + " elevated=" + o.elevated() + " status=" + o.status());
    }

    /**
     * Operator-command dispatch (leg 6a): ONE house verb from the per-frame poll — the operator
     * file, the D-LOBBYEND carry and the door-queued admin/chat verbs ride this single switch.
     * Each {@code return} was a {@code continue} of the command loop in
     * {@link #serveFrame}: the switch was that loop body's only statement, so "next command"
     * == "leave this method". Verbatim otherwise (the rejected-line rows print before the loop).
     */
    private void handleOperatorCommand(PilotIntentDatagramServer door,
            OperatorCommandFile.Command command, int round, int frame) throws IOException {
        // TEST-ONLY injector (null = unarmed; a
        // null compare per COMMAND, never per frame) — the fault belt's row proves the catch.
        if (injectedFaultVerb != null && command.verb() == injectedFaultVerb) {
            throw new IllegalStateException("injected operator fault (cc.serve.test.faultVerb)");
        }
        switch (command.verb()) {
            case KICK -> {
                // Text = the parser's EXPECTED-occupant cid
                // key ("" = the frame-time occupant); the door compares it under its lock, reason = its
                // bounded token. The legacy tail stays on no_live_session only (ServeOperatorFaultRows R2
                // pins it; dropping it means changing that row too).
                PilotIntentDatagramServer.KickOutcome kick = door.kickSeatSession(command.seatId(),
                        command.text() == null ? "" : command.text());
                System.out.println("npeer-serve operator_kick seat="
                        + command.seatId() + " kicked=" + kick.kicked() + " reason=" + kick.reason()
                        + " round=" + round + " frame=" + frame
                        + ("no_live_session".equals(kick.reason()) ? " (no live session on that seat)" : ""));
            }
            // Arm the operator's own seat key on the door — the
            // ONE apply shared with the lobby gate (armAdminCid below).
            case ADMIN_CID -> armAdminCid(door, command);
            // The P27 ban verbs — the memory
            // mutation happens NOW via the door; the file rewrite rides the off-frame sink.
            case BAN, BAN_SRC, BAN_CID, BAN_SESSION, BAN_IP, UNBAN, BANS ->
                    banLane.operatorBan(door, command, round, frame);
            case POD_TEST -> {
                String podReceipt = podScenario.start(command, lastSimTick + 1,
                        door.chatVerbs::sessionHoldsSeat);
                waveReceipt(door, command, podReceipt);
                System.out.println("npeer-serve operator_pod_test scenario="
                        + command.text() + " seat=" + command.seatId()
                        + " receipt=\"" + podReceipt + "\" round=" + round
                        + " frame=" + frame);
            }
            case GOD_MODE -> {
                // The effect + the honest receipt (D5 law).
                // receipt 0 → house-file twin, else chat twin — AdminSeatDebugLane#godModeCommand.
                String godReceipt = adminDebug.godModeCommand(command.seatId(), command.text(),
                        command.receiptSessionId(), door::seatHasLiveSession, door.chatVerbs::sessionHoldsSeat);
                waveReceipt(door, command, godReceipt);
                System.out.println("npeer-serve operator_god_mode seat="
                        + command.seatId() + " arg=" + command.text()
                        + " receipt=\"" + godReceipt + "\" round=" + round
                        + " frame=" + frame + " silent=" + (command.receiptSessionId() == 0L));
            }
            case TELEPORT -> {
                String tpReceipt = adminDebug.teleportCommand(command.seatId(), command.text(), mapHalf,
                        command.receiptSessionId(), door.chatVerbs::sessionHoldsSeat);
                waveReceipt(door, command, tpReceipt);
                System.out.println("npeer-serve operator_teleport seat="
                        + command.seatId() + " pose=" + command.text()
                        + " receipt=\"" + tpReceipt + "\" map_half=" + mapHalf
                        + " round=" + round + " frame=" + frame);
            }
            case SWITCH_TEAM -> {
                // The effect + the honest receipt (D5 law); a door-minted silent
                // clear (seat vacate) returns "" and mails nobody.
                String teamReceipt = seatTeams.switchTeam(command.seatId(), command.text());
                if (!teamReceipt.isEmpty()) {
                    waveReceipt(door, command, teamReceipt);
                    transferLane.markCensusDirty();
                }
                System.out.println("npeer-serve operator_switch_team seat="
                        + command.seatId() + " mode=" + command.text()
                        + " receipt=\"" + teamReceipt + "\" round=" + round
                        + " frame=" + frame + " silent=" + SeatTeamLane.MODE_CLEAR.equals(command.text()));
            }
            case TEAM_PICK -> {
                // A SET of the seat's next fly-in side (the door decided the
                // room); the holder re-check refuses a seat that changed hands since. A house-file pick carries
                // no session and skips the check. Nobody is mailed: the /team receipt went out at the door.
                int pickSide = SeatTeamLane.sideOfWordOrNeg(command.text());
                long holderSession = command.receiptSessionId();
                int pickSeat = command.seatId();
                boolean picked = seatTeams.pickTeam(pickSeat, pickSide, () -> holderSession == 0L
                        || door.chatVerbs.sessionHoldsSeat(holderSession, pickSeat));
                if (picked) {
                    transferLane.markCensusDirty();
                    System.out.println("npeer-serve team_pick seat=" + pickSeat + " side=" + pickSide
                            + " round=" + round + " frame=" + frame);
                } else {
                    System.out.println("npeer-serve team_pick_refused seat=" + pickSeat + " side=" + pickSide
                            + " reason=\"" + SeatTeamLane.SEAT_CHANGED + "\" refused_total="
                            + seatTeams.teamPickRefusedCount() + " round=" + round + " frame=" + frame);
                }
            }
            case END_ROUND -> {
                // A CHAT-sourced end (/wave wrap, the
                // receipt target != 0) is REFUSED on the serve's last round (an END_ROUND there
                // ends the serve, not a wrap) and latched on a double press, both with constant
                // receipts. The house file / ops GUI / web mint receiptSessionId 0 and keep
                // today's behaviour byte-identical (waveReceipt no-ops for 0). `round` is the
                // 0-based serve counter; the success receipt prints it 1-based.
                boolean chatSourced = command.receiptSessionId() != 0L;
                if (chatSourced && round >= config.roundsPerSocket() - 1) {
                    waveReceipt(door, command, ChatCommands.WRAP_REFUSED_LAST_ROUND);
                    System.out.println("npeer-serve operator_end_round refused=last_round round="
                            + round + " frame=" + frame);
                    return;
                }
                if (chatSourced && operatorEndMatch) {
                    // The file/carry lines drain BEFORE the door lines in the
                    // same frame and END_MATCH wins the switch — the serve ends, so a "next round"
                    // receipt would lie; refuse with the serve-ends constant instead.
                    waveReceipt(door, command, ChatCommands.WRAP_REFUSED_LAST_ROUND);
                    System.out.println("npeer-serve operator_end_round refused=match_ending round="
                            + round + " frame=" + frame);
                    return;
                }
                if (chatSourced && operatorEndRound) {
                    waveReceipt(door, command, ChatCommands.WRAP_ALREADY_ENDING);
                    System.out.println("npeer-serve operator_end_round refused=already_ending"
                            + " round=" + round + " frame=" + frame);
                    return;
                }
                if (chatSourced) {
                    waveReceipt(door, command, ChatCommands.wrapReceipt(round));
                }
                operatorEndRound = true;
                System.out.println("npeer-serve operator_end_round round="
                        + round + " frame=" + frame
                        + " (round boundary forced at this tick's (m))");
                worstFrames.note(lastSimTick + 1, "operator_end");
            }
            case END_MATCH -> {
                operatorEndMatch = true;
                worstFrames.note(lastSimTick + 1, "operator_end");
                System.out.println("npeer-serve operator_end_match round="
                        + round + " frame=" + frame
                        + " (goodbye fan after this tick; wins over end-round)");
            }
            // The nine custom-variant house verbs, keep / keep-export /
            // forget included — one helper owns the semantics (a WARN-tier file stays thin);
            // receipts ride the chat-reply path (waveReceipt) for a chat-sourced mint (none yet), rows print inside the helper.
            case CUSTOM_SUBMISSIONS, CUSTOM_REJECT, CUSTOM_BAN, CUSTOM_EXPORT, CUSTOM_ALLOW, CUSTOM_UNBAN, CUSTOM_KEEP, CUSTOM_KEEP_EXPORT, CUSTOM_FORGET -> {
                // A forget passes the listing floor first (never the last
                // ship on /ship), and a queued forget counts as pending for the next floor check.
                boolean forget = command.verb() == OperatorCommandFile.Verb.CUSTOM_FORGET;
                String floorRefusal = forget ? hiddenShips.customForgetFloorOrNull(pickableOrNull, command.text()) : null;
                // The console's Keep of a kept ship the host REMOVED is that
                // ship's Put back - the ship lane owns it (one path with /shiprestore: the copy standing in its place
                // leaves, the link survives); null = any other custom verb or name, which the custom helper answers.
                HiddenShipsLane.Verdict putBack = hiddenShips.customKeepExportOrNull(door, pickableOrNull, command);
                String customReceipt = floorRefusal != null ? floorRefusal
                        : putBack != null ? putBack.receipt()
                        : CustomVariantOps.handle(customLaneOrNull, door, command);
                if (forget && floorRefusal == null) {
                    hiddenShips.noteCustomForget(command.text(), customReceipt);
                }
                waveReceipt(door, command, customReceipt);
                // A receipt can carry a player-named ship - quoted + escaped,
                // so a double quote in a name never forges a token on this row
                System.out.println("npeer-serve operator_custom verb=" + command.verb()
                        + " receipt=" + PeerText.quote(customReceipt) + " round=" + round + " frame=" + frame);
            }
            // The host's ship-list verbs — the SERVER classifies the id
            // (a shipped id hides / shows here, a kept ship goes to the custom store's side); memory only on this
            // thread, the hidden-ships save is an enqueue; one row per verb, the receipt through the chat return leg.
            case SHIP_HIDE, SHIP_SHOW, SHIP_REMOVE, SHIP_RESTORE, SHIP_LIST, SHIP_KEEP -> {
                HiddenShipsLane.Verdict ship = hiddenShips.handle(door, pickableOrNull, command);
                waveReceipt(door, command, ship.receipt());
                System.out.println("npeer-serve operator_ship verb=" + command.verb() + " id=" + command.text()
                        + " receipt=" + PeerText.quote(ship.receipt()) + " round=" + round + " frame=" + frame
                        + " persist=" + ship.persist());
            }
            // The
            // /wave + /endless family owns its own file (AdminWaveVerbs, the CustomVariantOps
            // idiom — a WARN-tier file stays thin). Bodies moved VERBATIM; every row/receipt
            // still prints inside the lane, and each arm's `return` still ends the command.
            case SKIP_WAVE, PAUSE_WAVE, SET_WAVE_CATEGORY, SET_WAVE_NUMBER, SET_ENDLESS,
                    SET_WAVE_INFLATE -> waveVerbs.handle(door, command, round, frame, lastSimTick);
            // The wire-delivered client knob census (/knob) — set + PSTB re-spool.
            case SET_KNOB -> AdminKnobVerb.handle(door, command, round, frame);
            // The terrain pair — set both knobs, re-spool, re-apply, receipt
            case SET_TERRAIN -> AdminTerrainVerb.handle(door, command, round, frame);
            // W12 voice L2: live voice-sheet retune —
            // the relay validates (fail-soft, logged) and re-sends VCFG.
            case VOICE -> {
                String r = voice == null ? "voice disabled"
                        : voice.retune(command.text());
                System.out.println("npeer-serve operator_voice text="
                        + command.text() + " result=" + r
                        + " round=" + round + " frame=" + frame);
            }
            // The server-owned joiner default — canonicalized +
            // refused loud by the door; applies to every FRESH claim from
            // now on (an already-admitted seat keeps its seed/pick).
            case SET_JOINER_DEFAULT -> {
                String standing = door.setJoinerDefaultVariant(command.text());
                System.out.println("npeer-serve operator_joiner_default"
                        + " requested=" + (command.text().isEmpty()
                                ? "none" : command.text())
                        + " standing=" + standing
                        + " round=" + round + " frame=" + frame);
            }
            // The adhoc spawn
            // arm, moved VERBATIM into AdminWaveVerbs (ENQUEUE ONLY; the (c2c) drain still
            // spawns on the wave stagger's leftover budget).
            case SPAWN_FOE, SPAWN_FRIEND -> waveVerbs.spawn(door, command, round, frame);
            case DESPAWN -> {
                // The R-targeted hull VANISHES through the
                // engine's own removeObject on this sim thread (the transfer-pod primitive) — never
                // a kill: no hulk, no explosion, so the EDSP classifier reads a healthy removal as
                // DEPARTED and every client evicts the replica corpse-free (FoeStateLaneCoordinator's
                // non-fighter DEPARTED path). The port holds every refusal (a seat's
                // wire, a pod, an unknown or off-field id); the REAL outcome mails as the receipt
                // (D5 law). No wire change: roster absence + the EDSP reason carry it.
                String despawnReceipt = podPort == null ? "despawn refused: no round engine"
                        : podPort.despawnByWire(command.text());
                waveReceipt(door, command, despawnReceipt);
                System.out.println("npeer-serve operator_despawn wire=" + command.text()
                        + " receipt=\"" + despawnReceipt + "\" round=" + round
                        + " frame=" + frame);
            }
            // announced-dilation SIM SPEED (K2(b): frozen dt,
            // vary steps/wall-second). Applies from the NEXT frame's pacer:
            // interval re-derives, anchor re-bases at NOW (no catch-up
            // sprint). The AWSD v5 simSpeedPermille carrier announces the
            // speed on EVERY delta-lane frame — an AWSS-only serve has no
            // carrier, so the verb REFUSES there (log + ignore): a client
            // that can never learn the speed must never be dilated against.
            case SET_SIM_SPEED -> {
                if (!downComposer.deltaLaneOn()) {
                    System.out.println("npeer-serve sim_speed_refused"
                            + " reason=delta_lane_off speed="
                            + command.simSpeed() + " round=" + round
                            + " frame=" + frame
                            + " (AWSS-only serve has no announcement"
                            + " carrier; arm cc.net.snapDelta)");
                    return;
                }
                // Manual outranks — ANY applied manual set
                // disarms the governor; only an explicit sim-speed-auto
                // on re-arms (no flap mode exists).
                if (autoSpeedArmed) {
                    autoSpeedArmed = false;
                    System.out.println("npeer-serve sim_speed_auto_disarmed"
                            + " reason=manual_set round=" + round
                            + " frame=" + frame
                            + " (re-arm = sim-speed-auto on; auto never"
                            + " re-arms itself)");
                }
                simSpeed = command.simSpeed();
                simSpeedChanges++;
                downComposer.setSimSpeedPermille(
                        (int) Math.round(simSpeed * 1000.0d));
                pacerIntervalNanos = (long) (frameTargetNanos / simSpeed);
                if (config.paceToWallClock() && pacerAnchorNanos != -1L) {
                    pacerAnchorNanos = System.nanoTime();
                    pacerAnchorFrame = frame;
                }
                System.out.println(String.format(java.util.Locale.ROOT,
                        "npeer-serve sim_speed speed=%.2f interval_ms=%.2f"
                                + " round=%d frame=%d (pacer re-anchored;"
                                + " dt frozen — steps/wall-second change;"
                                + " announced on every AWSD frame)",
                        simSpeed, pacerIntervalNanos / 1_000_000.0d,
                        round, frame));
            }
            // Governor arm/disarm. Arming mirrors the manual
            // verb's delta-lane refusal (a client that can never learn
            // the speed must never be auto-dilated against); it resets
            // window+dwell and governs from the CURRENT speed (the
            // arm-snap to the 0.25 grid applies at the next served
            // frame's governor block). off keeps the current speed.
            case SET_SIM_SPEED_AUTO -> {
                if (command.autoOn()) {
                    if (!downComposer.deltaLaneOn()) {
                        System.out.println("npeer-serve sim_speed_auto_refused"
                                + " reason=delta_lane_off round=" + round
                                + " frame=" + frame
                                + " (AWSS-only serve has no announcement"
                                + " carrier; arm cc.net.snapDelta)");
                        return;
                    }
                    autoSpeedArmed = true;
                    autoSpeedDormantLogged = false;
                    autoSnapPending = true;
                    autoSpeedLaw.reset();
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "npeer-serve sim_speed_auto_arm on floor=%.2f"
                                    + " ceiling=%.2f round=%d frame=%d"
                                    + " (governs from current speed %.2f;"
                                    + " window+dwell reset; manual"
                                    + " set-sim-speed disarms)",
                            autoSpeedFloor, autoSpeedCeiling, round, frame,
                            simSpeed));
                } else {
                    autoSpeedArmed = false;
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "npeer-serve sim_speed_auto_arm off round=%d"
                                    + " frame=%d (speed stays %.2f)",
                            round, frame, simSpeed));
                }
            }
        }
    }

    /**
     * Round close (leg 5): the boundary debt tally, the {@link PilotRoundSummary} (+ its append to
     * {@code rounds}), the counters line, the gated boost/system sample prints, the growth-seam
     * uninstall. The wrap-or-break decision that follows stays in {@link #serve()}.
     */
    private void closeRound(PilotIntentDatagramServer door, int round) throws IOException {
        // Unrepaid debt at the round boundary — the only wall-time
        // truly lost to a stall (the per-round re-anchor forgives it silently).
        // ONE extra nanoTime per round; sentinel check (nanoTime origin is arbitrary,
        // so -1 is the unset mark, not a sign test).
        long debtEndNanos = 0L;
        if (config.paceToWallClock() && pacerAnchorNanos != -1L) {
            debtEndNanos = Math.max(0L, System.nanoTime()
                    - (pacerAnchorNanos
                            + (long) (framesServed - pacerAnchorFrame)
                                    * pacerIntervalNanos));
        }
        PilotRoundSummary summary = new PilotRoundSummary(
                round, framesServed, matchOver,
                pilotStep.framesApplied(), pilotStep.movementCommandsIssued(),
                pilotStep.facingPins(), pilotStep.aimPins(),
                pilotStep.systemActivations(), pilotStep.ventsIssued(),
                pilotStep.firesIssued(),
                pilotStep.declarationsReceived(), pilotStep.declarationsServed(),
                pilotStep.declarationsExpired(),
                overrunFrames, overrunMaxNanos,
                workFrames, workNanosTotal, workMaxNanos,
                pilotStep.retreatsIssued(), pilotStep.targetApplies(),
                pilotStep.edgeRecovered(), pilotStep.edgeExpired(),
                pilotStep.edgeSwitchDisarmed(), pilotStep.shieldHolds(),
                pilotStep.edgeClampDropped(), pilotStep.systemCancelPresses(),
                pilotStep.tubeOrdinalConformed(),
                sprintEpisodes, sprintRunMaxFrames, sprintWallMaxNanos, debtEndNanos,
                snapFanSkippedTicks,
                pilotStep.cloakToggles(), pilotStep.ventRePresses(),
                pilotStep.retreatReasserts(), pilotStep.retreatRearmSkips(),
                pilotStep.declPressesIssued(), pilotStep.declPressWaitTicks(),
                pilotStep.declAdoptServes(), pilotStep.declAdoptRejects(),
                pilotStep.declAdoptRejectReasons(),
                cc.starsector.server.runtime.AdoptServeRegistry.roundCaptureMisses(),
                pilotStep.declAdoptBatched(),
                pilotStep.retreatEdgeQuarantined(),
                cc.starsector.server.bridge.WeaponChargeupPreAdvance.writes(), // cumulative
                pilotStep.declarationsServedByCredit(), // burst-continuation credit serves
                // Credit ledger (minted/expired/capped/fence_refused/nonburst)
                pilotStep.sumApplyStates(PilotApplyState::continuationCreditsMinted),
                pilotStep.sumApplyStates(PilotApplyState::continuationCreditsExpiredUnspent),
                pilotStep.sumApplyStates(PilotApplyState::continuationCreditsCapped),
                pilotStep.sumApplyStates(PilotApplyState::creditFenceRefusals),
                pilotStep.sumApplyStates(PilotApplyState::dischargeUnmatchedNonburst),
                    pilotStep.sumApplyStates(PilotApplyState::lastShotCreditsMinted), // credits minted on a burst's last shot
                    pilotStep.sumApplyStates(PilotApplyState::edgeExpiredGated), //
                    pilotStep.sumApplyStates(PilotApplyState::cloakDiveFreezeTicks), //
                    pilotStep.sumApplyStates(PilotApplyState::retreatDiveFreezeTicks), // bit-9 apply ticks frozen by a dive
                    pilotStep.sumApplyStates(PilotApplyState::declLockWaitTicks), //
                    pilotStep.fireLockReadErrors(), // fire-lock reads that threw
                    pilotStep.pilotMarkWrites(), // pilot-mark meters
                    pilotStep.pilotMarkClears(),
                    pilotStep.abilityEdgeQuarantined());
        rounds.add(summary);
        lastRoundMatchOver = matchOver;
        // Carry the round's verdict out of round scope for the
        // MOVR fan (first-latch-wins on the source; operator end-round rides matchOver
        // with a null tag and must fan as code 6, not as a latch).
        lastMatchOverTag = source.matchOverTag();
        lastMatchOverWinner = source.matchOverWinnerSide();
        lastMatchOverTick = source.matchOverSimTick();
        lastRoundOperatorEnd = operatorEndRound;
        lastServedSimTick = Math.max(lastSimTick, 0L);
        // Close the shadow's ±1 window before its counters print below.
        onFireShadow.closeRound(round);
        // The counter tail is shared with the round_teardown
        // receipt (ONE formatter — a field appended there lands in both lines by
        // construction, so the receipt can never drift behind the round line).
        arcHitProbe.flushHistTail(); // The round's last partial arc_hit_hist bucket precedes its round line
        System.out.println("crit-malfunction round" + critProbe.roundTokens()); //
        System.out.println("asteroid round" + asteroidLane.roundTokens()); //
        // The terrain stat seam's per-birth reflective cost + its
        // skips, cumulative over the serve like appliedCount — the batch flight reads the corona cost here
        int terrainTimed = cc.starsector.server.bridge.TerrainStatSeam.timedCallCount();
        System.out.println("terrain-stat round applied=" + cc.starsector.server.bridge.TerrainStatSeam.appliedCount()
                + " terrain_apply_ns_avg=" + (terrainTimed == 0 ? 0L
                        : cc.starsector.server.bridge.TerrainStatSeam.appliedNanosTotal() / terrainTimed)
                + " terrain_apply_ns_max=" + cc.starsector.server.bridge.TerrainStatSeam.appliedNanosMax()
                + " terrain_skipped_off=" + cc.starsector.server.bridge.TerrainStatSeam.skippedOffCount()
                + " terrain_resolve_failed_skips=" + cc.starsector.server.bridge.TerrainStatSeam.resolveFailedSkipCount());
        System.out.println(adminDebug.godRoundLineAndReset()); // Always printed (a 0-row = silent lane)
        worstFrames.flushRound(round); // (104)(d) rows precede their round line
        System.out.println("npeer-intent-rounds round=" + round
                + DownLaneSeams.roundCounterTail(summary, frameTargetNanos, fanPassMaxNanos,
                        sendSlowestNanos, door, pilotStep, fireLaneDischargeDetector,
                        downComposer, peerGrade, onFireShadow, onFireTruthMerger,
                        afTargetLane, paladinDetLane, arcHitProbe, gpeeDownEvents, gpeeMissileEndIntact,
                        gpeeMissileEndDestroyed, gpeeMissileEndFizzling, gpeeMissileEndShield, gpeeMissileEndNamed,
                        edspDownEvents, seatDownFrames, authority.seatRecordsCoalesced(),
                        hkfrDownEvents, hkpsDownEvents, hkcgDownEvents,
                        hkcgSkipEvents, source.hulkPoseImpulseTriggers(),
                        source.hulkPoseBurstEmits(),
                        source.hulkObserveNsMax(), source.hulkObserveNsAvg(),
                        stallFrames, simSpeedChanges,
                        autoSpeedChanges, aimFanLane, stagedRefusedUnmounted, intentTakeAge)
                + rappCatchup.roundTokens(roundRosterAppendFrames) //
                // Tenure admit meters, LAST tokens.
                + " tenure_applied=" + door.readerCounters().tenureApplied
                + " tenure_spool_ms_max=" + door.readerCounters().tenureSpoolMsMax
                + " tenure_clears=" + door.readerCounters().tenureClears
                + " wrap_vacated=" + wrapVacatedTotal // The r1 skip count, exported
                + " operator_command_faults=" + operatorCommandFaults); // The belt's count
        printSnapSendRows(round, door.liveSessions()); // One send-gap row per tabled session
        // Gated measurement lines at N: printed by BoostSystemSampleLane.
        boostSystemLane.printRound(round, summary, sampleBoost, sampleSystem);

        // Growth seam OFF before the wrap — the gated reset mints a
        // FRESH repository (uninstall targets the round's CURRENT one) and the break
        // branch falls straight to teardown; the serve finally re-runs uninstall as an
        // idempotent backstop for throw paths.
        if (engineBornGrowth) {
            hooks.growthSeam().uninstall();
        }
    }

    /**
     * Stage one take into the frame's TAKES row — the seat, its take
     * kind off the door's last-take getters, whether the serve STAGED it, and the applied frame re-encoded
     * verbatim. A builder refusal (a second take for one seat, a full table) is a RECORDER fault:
     * counted + logged once, never the match's end — the take itself was already applied.
     */
    private void addTakeRow(PilotIntentDatagramServer.PeerSession session, boolean staged,
            PilotIntentFrame intent) {
        try {
            int foldMask = session.takenFoldMask();
            takes.addTake(session.seatId(),
                    MatchRecordRows.takeKindOf(session.takenDepositStampTick(), foldMask), foldMask,
                    staged, PilotIntentWireCodec.encodeFrame(intent));
        } catch (RuntimeException fault) {
            noteRecordRowFault(MatchRecordRows.KIND_TAKES, fault);
        }
    }

    /** Stage one polled declaration into the frame's TAKES row
     *  (the same recorder-fault belt as {@link #addTakeRow}). */
    private void addDeclRow(PilotIntentDatagramServer.PeerSession session,
            FireDischargeDeclaration declaration) {
        try {
            takes.addDecl(session.seatId(), FireDischargeDeclarationCodec.encodeFrame(declaration));
        } catch (RuntimeException fault) {
            noteRecordRowFault(MatchRecordRows.KIND_TAKES, fault);
        }
    }

    /**
     * The (j)+(k) take band of a re-simulation — the frame's recorded
     * TAKES row applied in place of the door's deposits. The sealed door's own deposits are drained and
     * counted as strays first; a recorded seat no live replay session holds is counted and STILL applied
     * (every consumer keys on the seat or the frame's ship). The staged bit is RECOMPUTED with the live
     * expression and staged per this sim (a disagreement with the recording is counted, never forced) and
     * re-recorded; the take meter is skipped (the row carries no deposit stamp). All takes, then all
     * declarations, which keeps each seat's take ahead of its own declarations. A frame that does not
     * decode throws (an instrument fault ends the re-simulation loud).
     *
     * @return whether any applied frame held fire (the fail-open streak input)
     */
    private boolean replayTakeBand(List<PilotIntentDatagramServer.PeerSession> live, int round, int frame,
            long applyTick) throws IOException {
        java.util.Arrays.fill(replayLiveSeats, false);
        for (PilotIntentDatagramServer.PeerSession session : live) {
            int seat = session.seatId();
            if (seat >= 0 && seat < replayLiveSeats.length) {
                replayLiveSeats[seat] = true;
            }
            if (session.takeDepositedIntentOrNull() != null) {
                replayFeed.noteStray(seat);
            }
            while (session.pollDeclaration() != null) {
                replayFeed.noteStray(seat);
            }
        }
        MatchRecordRows.TakesRow row = replayFeed.takePre(round, applyTick);
        boolean anyFireHeld = false;
        for (MatchRecordRows.TakeEntry e : row.takes()) {
            int seat = e.seat();
            if (seat < 0 || seat >= replayLiveSeats.length || !replayLiveSeats[seat]) {
                replayFeed.noteSeatNotLive(round, applyTick, seat);
            }
            PilotIntentFrame intent = PilotIntentWireCodec.decodeDatagram(e.frame());
            if (authority.recordCombatReady(seat)) {
                System.out.println("npeer-serve combat_ready seat="
                        + seat + " round=" + round + " frame=" + frame
                        + " tick=" + lastSimTick
                        + " (first pilot intent; seat deployable from here)");
            }
            boolean staged = applyTick > 0 && authority.isMounted(seat)
                    && !authority.hasPendingTransition(seat);
            if (staged) {
                pilotStep.stage(intent);
            } else {
                stagedRefusedUnmounted++;
            }
            if (staged != e.staged()) {
                replayFeed.noteStagedMismatch(round, applyTick, seat, e.staged(), staged);
            }
            if (recordRowsArmed) {
                addReplayTakeRow(e, staged);
            }
            if (intent.fireHeld()) {
                anyFireHeld = true;
            }
        }
        for (MatchRecordRows.DeclEntry d : row.decls()) {
            int seat = d.seat();
            if (seat < 0 || seat >= replayLiveSeats.length || !replayLiveSeats[seat]) {
                replayFeed.noteSeatNotLive(round, applyTick, seat);
            }
            pilotStep.enqueueDeclaration(FireDischargeDeclarationCodec.decodeDatagram(d.frame()), applyTick);
            sessionDeclReceived++;
            if (recordRowsArmed) {
                addReplayDeclRow(d);
            }
        }
        return anyFireHeld;
    }

    /** re-record one replayed take with this sim's staged bit (the
     *  recorded frame bytes verbatim; the same recorder-fault belt as {@link #addTakeRow}). */
    private void addReplayTakeRow(MatchRecordRows.TakeEntry e, boolean staged) {
        try {
            takes.addTake(e.seat(), e.takeKind(), e.foldMask(), staged, e.frame());
        } catch (RuntimeException fault) {
            noteRecordRowFault(MatchRecordRows.KIND_TAKES, fault);
        }
    }

    /** re-record one replayed declaration (the {@link #addDeclRow} belt). */
    private void addReplayDeclRow(MatchRecordRows.DeclEntry d) {
        try {
            takes.addDecl(d.seat(), d.frame());
        } catch (RuntimeException fault) {
            noteRecordRowFault(MatchRecordRows.KIND_TAKES, fault);
        }
    }

    /** The recorder-fault meter — every fault counted, the FIRST one
     *  logged (kind byte + exception SimpleName only, never a message). */
    private void noteRecordRowFault(byte kind, RuntimeException fault) {
        recordRowFaults++;
        if (recordRowFaults == 1L) {
            System.out.println("npeer-serve record_row_fault kind=" + kind + " err="
                    + fault.getClass().getSimpleName() + " (first; later faults count only)");
        }
    }

    /** One SESS row (PRE band, the tick about to be served). */
    private void recordSessRow(PilotIntentDatagramServer door, int event,
            PilotIntentDatagramServer.PeerSession session, String reason, String override, int round) {
        door.recordRow(MatchRecordRows.KIND_SESS, MatchRecordRows.encodeSess(new MatchRecordRows.SessRow(
                event, session.sessionId(), session.seatId(), session.shipId(),
                MatchRecordRows.sessFlags(session.observer(), session.admin(), session.helloMagicBits), reason, override),
                MatchRecordRows.BAND_PRE, round), lastSimTick + 1);
    }

    /** One FLYIN row (PRE band, the tick about to be served). */
    private void recordFlyInRow(PilotIntentDatagramServer door, int round, MatchRecordRows.FlyInRow row) {
        door.recordRow(MatchRecordRows.KIND_FLYIN,
                MatchRecordRows.encodeFlyIn(row, MatchRecordRows.BAND_PRE, round), lastSimTick + 1);
    }

    /** The path-1 FLYIN row — the inputs the deploy was handed, its
     *  result, and an accepted custom pick's body CRC (0 for a stock pick or an unknown id). */
    private MatchRecordRows.FlyInRow flyInDeployRow(int seatId, String overrideIn, int sideIn,
            NPeerHooks.FlyInResult flyIn) {
        long customBodyCrc = 0L;
        SubmittedVariantLedger ledger = customLaneOrNull == null ? null : customLaneOrNull.ledgerOrNull();
        if (ledger != null && overrideIn != null && overrideIn.startsWith(SubmittedVariantLedger.ID_PREFIX)) {
            SubmittedVariantLedger.Accepted accepted = ledger.acceptedOrNull(overrideIn);
            customBodyCrc = accepted == null ? 0L : accepted.bodyCrc();
        }
        return new MatchRecordRows.FlyInRow(seatId, MatchRecordRows.FLYIN_HULL_DEPLOY, overrideIn, sideIn,
                0L, flyIn.wireId(), flyIn.stableId(), flyIn.variantId(), flyIn.descriptorVariantId(),
                flyIn.side(), flyIn.shipName(), flyIn.crPerMille(), customBodyCrc);
    }

    /** The live-roster supplier the wave stage reads, wrapped — it
     *  returns exactly {@code dp.ratio()} and records it as a ROSTER row when its raw bits differ from the
     *  last recorded value (the round's first read always records). Read at each curve wave's STAGE
     *  (once per staged wave, on the sim thread's wave drain), never per tick. The record step rides the
     *  recorder-fault belt ({@link #noteRecordRowFault}): a throw inside the
     *  schedule's supplier would otherwise flip the sim's ratio to the stage's fallback — an instrument
     *  must never change the match, so {@code r} is returned whatever the recorder does. */
    private double recordedRosterRatio(PilotIntentDatagramServer door,
            cc.starsector.server.match.LiveRosterDp dp, int round) {
        return recordRosterValue(door, dp.ratio(), round);
    }

    /** The record step of {@link #recordedRosterRatio}, split out so a
     *  re-simulation's injected ratio is re-recorded by the same change-only rule; returns {@code r} unchanged. */
    private double recordRosterValue(PilotIntentDatagramServer door, double r, int round) {
        long bits = Double.doubleToRawLongBits(r);
        if (recordRowsArmed && (rosterRowPending || bits != lastRosterBits)) {
            try {
                door.recordRow(MatchRecordRows.KIND_ROSTER, MatchRecordRows.encodeRoster(
                        new MatchRecordRows.RosterRow(r, authority.occupiedSeatIds().size()),
                        MatchRecordRows.BAND_PRE, round), lastSimTick + 1);
                lastRosterBits = bits;
                rosterRowPending = false;
            } catch (RuntimeException fault) {
                noteRecordRowFault(MatchRecordRows.KIND_ROSTER, fault);
            }
        }
        return r;
    }

    /**
     * The live serve's operator-queue take: the admin queue FIRST, then the door's team decisions as
     * {@code TEAM_PICK} commands (a {@code /team} SET, a claim whose seat plays for the other team, a PvP
     * admin switch), so a seat vacate's or a takeover's queued silent clear runs before the pick and can
     * never erase a team the door just restored. {@code observed} = the live set of this frame's session diff,
     * which runs first, so a pick lands before the seat drain of the frame that first sees its session.
     *
     */
    public static OperatorCommandFile.ParseResult adminThenTeamPicks(PilotIntentDatagramServer door,
            java.util.function.LongPredicate observed) {
        OperatorCommandFile.ParseResult admin = door.drainAdminCommands();
        return OperatorCommandFile.ParseResult.concat(admin, door.drainTeamPicks(observed));
    }

    /**
     * Observe-before-fly: a due seat flies only when a session holding it is in {@code observed}, this frame's
     * session-diff live set. A re-simulation occupies a seat at its LIVE row's frame ({@link ReplayFeed#applySessions}
     * mints through the replay door, whose {@code replayMint} claims the seat), so a claim admitted after the diff
     * waits one frame here and both runs fly it at the same tick, its team pick applied; one stdout line per wait.
     *
     *
     */
    public static boolean flyInHolderObserved(int seatId, Map<Long, PilotIntentDatagramServer.PeerSession> observed,
            Set<Integer> logged, int round, long tick) {
        if (observedHolder(seatId, observed)) {
            logged.remove(seatId);
            return true;
        }
        if (logged.add(seatId)) {
            System.out.println("npeer-serve fly_in_unobserved_holder seat=" + seatId + " round=" + round
                    + " tick=" + tick);
        }
        return false;
    }

    /** True iff a session in {@code observed} holds {@code seatId} (a pilot seat, 1..64; seat 0 = the observers,
     *  never a holder). */
    public static boolean observedHolder(int seatId, Map<Long, PilotIntentDatagramServer.PeerSession> observed) {
        if (seatId < 1) {
            return false;
        }
        for (PilotIntentDatagramServer.PeerSession s : observed.values()) {
            if (s.seatId() == seatId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The wrap's team-side decision: the PvP shape keeps every seat's side (the players' own choice) and
     * hands it to the gated reset; every other shape clears the sides first, so the reset gets an empty map
     * and redeploys from the descriptor exactly as before.
     *
     */
    public static Map<Integer, Integer> wrapTeamSides(SeatTeamLane lane, boolean pvp) {
        if (!pvp) {
            lane.clearAll();
        }
        return Map.copyOf(lane.sidesBySeat());
    }

    /**
     * Round wrap (leg 5): the body of the {@code matchOver && round < last} branch — the per-seat
     * pick snapshot the reset redeploys (picks survive), transfer census EMPTY
     * publish, arbiter/pod/park/orbit-arm clears, the gated reset with the DEPLOYABLE set, {@code resetRoundSeats}, the optional MRST fan, the seat BASELINE burst.
     * Runs only when the serve continues into round {@code round + 1}.
     */
    private void wrapRound(PilotIntentDatagramServer door, int round) throws IOException {
        SortedSet<Integer> deployable = authority.deployableSeatIds();
        // The wrap KEEPS every seat's pick (the W7 B1b
        // v1 descriptor-pure law is replaced). Snapshot each deployable
        // seat's CURRENT override (admit seed or /ship; override ?? descriptor — fielded is NOT
        // a fallback: a banned custom must stop respawning) on this serve thread,
        // BEFORE the gated reset and outside the factory lock; only the pick-request lane
        // resets (requested/held custom picks still drop).
        Map<Integer, String> wrapVariants = new HashMap<>();
        // A re-simulation takes the recorded WRAP picks (the door's
        // pick store is reader-written and stays untouched in a replay) instead of reading the door.
        if (replayFeed != null) {
            wrapVariants.putAll(replayFeed.wrapPicks(round, lastSimTick, deployable));
        }
        for (Integer seatId : replayFeed == null ? deployable : java.util.Collections.<Integer>emptySortedSet()) {
            String pick = door.seatVariantOverrideOrNull(seatId);
            if (pick != null) {
                wrapVariants.put(seatId, pick);
            }
        }
        wrapVariants = Map.copyOf(wrapVariants);
        // The WRAP row — the pick snapshot the gated reset redeploys
        // (reader-written state the next round consumes), stamped with the round's last served tick.
        if (recordRowsArmed) {
            door.recordRow(MatchRecordRows.KIND_WRAP, MatchRecordRows.encodeWrap(
                    MatchRecordRows.wrapOf(round + 1, deployable, wrapVariants),
                    MatchRecordRows.BAND_WRAP, round), lastSimTick);
        }
        // Fold: the store retains by
        // OCCUPANCY, not by the redeploy set — a fresh claim whose tenure gate is still armed
        // (not deployable) has, or is about to have, its OWN seed in the store, and a seat
        // admitted but not yet combat-ready holds its own pick too; only a VACATED seat's pick
        // dies with its tenure. The redeploy itself stays the deployable set.
        door.onRoundWrap(authority.occupiedSeatIds());
        // The same law for the /switchteams sides — the gated reset redeploys
        // every seat from its DESCRIPTOR side, so a surviving override would disagree.
        // EXCEPT the PvP shape, whose teams are the players' choice: the
        // sides survive the wrap and the gated reset redeploys each seat on its kept side (sidesBySeat below).
        Map<Integer, Integer> wrapSides = wrapTeamSides(seatTeams, pvpTeams);
        if (transferTargets != null) {
            // D6 round-wrap law: publish EMPTY — the old census dies with the
            // round; the next round's lane starts dirty and re-publishes.
            transferTargets.publish(List.of());
        }
        // W2b E4 + W3: modules re-mint and transfer flights/floors reset with the
        // round — every arbiter table clears.
        rebindArbiter.clearRound();
        // W3 L2 P4: every pod leaves the world with the round —
        // BEFORE the gated reset (the pods live in THIS round's engine world; a
        // fresh per-round lane re-mints, so only this call despawns them).
        podLane.clearRound();
        // L4 S1: parks never outlive the round.
        // the pilotStep (and its park set) is re-minted per round by mintRound, so
        // this clear is a belt on the dying step; the serve-lane park map dies with its lane.
        pilotStep.clearTransitParks();
        // W3 drone-orbit: arms die with the round — the binding
        // outlives it and seat wire ids recur, so THIS clear is load-bearing.
        binding.clearOrbitDroneArms();
        // The owned-body reseed tables die with
        // the round too — the next round's redeploy is a first seed (prev=null), never a
        // cross-round reason=rebody / fake hulldelta.
        binding.clearOwnedBodyRound();
        // `round` is the round that just ended, so the
        // gated reset below fields round + 1's roster (ship ids, AI phases, the deploy shuffle
        // all draw in it) — re-arm that round's seed IMMEDIATELY before it. A no-op unpinned.
        CombatRngRearm.rearm(round + 1, "wrap");
        List<NPeerHooks.FlyInResult> resetFlyIns = hooks.reset().reset(deployable, wrapVariants, wrapSides);
        if (resetFlyIns == null) {
            throw new IOException("npeer gated reset hook returned null after round "
                    + round + " — it must return the redeployed occupied seats'"
                    + " (wireId, stableId) identities");
        }
        pendingRoundPreInterns = resetFlyIns;
        // W7 B1b, as amended: the reset fly-in
        // reports the FIELDED hull (the carried pick, or the descriptor row when the pick was
        // refused), so a seat re-announces only when that differs from what its clients
        // believe (e.g. a mid-round /ship pick not yet respawned, or a fallback).
        // the row is NOT fanned here — a pre-MRST row is stamped with the
        // OLD round client-side and drains in the old world — it is stashed in pendingWrapRepoints
        // and mintRound fans it AFTER the MRST into the NEW round's replay log (live sessions and
        // late joiners alike). Legacy identity-only reset hooks skip inside the helper.
        // Drop every catch-up cursor FIRST —
        // a session still flagged would be withheld the re-announce (the fan skips flagged
        // sessions; that fan is now mintRound's, whose own resetRound runs first
        // too) and would carry the stale repointed variant into the next round. The
        // round-end line already printed this round's rapp_* tokens.
        rappCatchup.resetRound(rappCatchupPort, rappCatchupPort, roundRosterAppendFrames.size());
        pendingWrapRepoints.clear();
        for (NPeerHooks.FlyInResult resetFlyIn : resetFlyIns) {
            Integer resetSeatId = seatIdByWireId.get(resetFlyIn.wireId());
            // (The group record for this tenure lives in mintRound's pre-intern loop.)
            // no mask-gate / resolver-rebind / pick-diff latch here — mintRound's fresh
            // step/lane serves the rebind + pick-diff reset; the mask gate's wrap stale-mask premise: unmeasured.
            if (resetSeatId != null) {
                SeatAnnounceTracker.announceSeatVariantIfRepointed(door, resetSeatId, resetFlyIn,
                        lastAnnouncedVariantBySeat, roundRosterAppendFrames, pendingWrapRepoints);
            }
        }
        wrapVacatedTotal += authority.resetRoundSeats(deployable);
        // Round wrap drops every AWSD anchor (the reset
        // re-points seat wire ids) — epoch counters persist inside the composer.
        downComposer.clearBaselines();
        System.out.println("npeer-serve reset_fired after round=" + round
                + " occupied=" + authority.occupiedSeatIds().size()
                + " deployable=" + deployable.size());
        if (config.emitMatchResetFrame()) {
            resetFramesEmitted++;
            int fanned = door.fanMatchReset(
                    new MatchResetSignal(resetFramesEmitted, round, round + 1));
            System.out.println("npeer-serve match_reset_frame_fanned seq="
                    + resetFramesEmitted + " completed_round=" + round
                    + " next_round=" + (round + 1) + " sessions=" + fanned);
        }
        long baselineTick = Math.max(lastSimTick, 0L);
        List<byte[]> baseline = encodeSeatEvents(authority.snapshotSeatBaseline(baselineTick));
        int burst = 0;
        if (rappCatchup.paced()) {
            // The re-baseline as SEAT SEGMENTS, never one
            // burst — at 255 seats the burst was 255 rows to each of 255 sessions in one tick (a
            // serve-thread stall, and a kill for a session still draining its admission spool).
            // Each live session is stamped now (as before) and handed the ONE shared encode
            // (held by reference, never mutated): a still-admitting session's unsent SEAT rows are
            // REPLACED (the client keeps the newest seat sequence), anyone else gets a seat-only
            // cursor; the next ticks' drains send them under the global per-tick cap, on CTRL
            // after the reset frame above, exactly where the burst landed. The port is bound over
            // a FRESH map of the door's live sessions — a session missing from the bound map would
            // silently get no re-baseline (once per round; the next tick's drain re-binds it to
            // that tick's live map).
            Map<Long, PilotIntentDatagramServer.PeerSession> wrapLive = new HashMap<>();
            List<PilotIntentDatagramServer.PeerSession> liveNow = door.liveSessions();
            for (PilotIntentDatagramServer.PeerSession session : liveNow) {
                wrapLive.put(session.sessionId(), session);
            }
            rappCatchupPort.bind(door, wrapLive, round, rappCatchupPort.frame);
            for (PilotIntentDatagramServer.PeerSession session : liveNow) {
                door.sendTickToSession(session, baselineTick, List.of(), List.of());
                rappCatchup.replaceSeatSnapshot(session.sessionId(), session, baseline,
                        rappCatchupPort.frame, rappCatchupPort);
                burst++;
            }
        } else {
            for (PilotIntentDatagramServer.PeerSession session : door.liveSessions()) {
                door.sendTickToSession(session, baselineTick, baseline, List.of());
                burst++;
            }
        }
        System.out.println("npeer-serve boundary_baseline_burst tick=" + baselineTick
                + " sessions=" + burst);
    }

    /**
     * Serve-end fans (leg 2): the MOVR verdict BEFORE the goodbye, the bounded CTRL flush +
     * BYE fan, then the final ops snapshot. Runs at the end of the try body only (a throw or
     * the client-wait early return skip it, as before); {@code door} is the try-with-resources
     * variable of {@link #serve()}.
     */
    private void fanServeEnd(PilotIntentDatagramServer door) {
        // Fan the MOVR serve-end verdict BEFORE the goodbye — the CTRL
        // spool is per-session FIFO and fanGoodbye BYEs only after spool-drain (or its
        // bound), so the verdict rides ahead of the teardown, bounded best-effort. A
        // latched matchOver fans its ELWSS tag's code; an operator end fans OPERATOR_END
        // with no victor; a frame-budget end fans NOTHING (no outcome exists — teardown
        // as today).
        int movrOutcome = -1;
        int movrWinner = -1;
        long movrTick = Math.max(lastServedSimTick, 0L);
        if (lastMatchOverTag != null) {
            try {
                movrOutcome = MatchOverFrameCodec.outcomeCodeForTag(lastMatchOverTag);
                movrWinner = lastMatchOverWinner;
                movrTick = Math.max(lastMatchOverTick, 0L);
            } catch (IllegalArgumentException unknownTag) {
                // Fail-soft: the tag map THROWS on an unmapped latch
                // tag, and a new latch site must never turn a served match into exit 1 —
                // skip the fan loudly instead.
                System.out.println("npeer-serve match_over_fan_skipped reason="
                        + unknownTag.getMessage());
            }
        } else if (operatorEndMatch || lastRoundOperatorEnd) {
            movrOutcome = MatchOverFrameCodec.OUTCOME_OPERATOR_END;
        }
        if (movrOutcome != -1) {
            door.fanMatchOver(new MatchOverFrameCodec.MatchOverRecord(
                    movrTick, movrOutcome, movrWinner));
        }
        // end-of-match goodbye: per-session bounded CTRL flush + BYE (the legacy
        // shutdownOutput posture at N — the bounds run concurrently so one dead peer cannot
        // stall the wrap; joiners get a clean exit instead of burning their read deadline to
        // udp_silence). Swallow-and-log mirrors the retired serve() lane's peer-gone guard: a
        // goodbye failure
        // never turns a served match into exit 1.
        // The serve-end send-gap rows, BEFORE the goodbye — every session
        // still tabled (a peer's BYE answer un-lives it); round = the last closed round (closeRound precedes this).
        printSnapSendRows(Math.max(0, rounds.size() - 1), door.liveSessions());
        // The player-ship window's match meters (windows opened, scoped to a
        // pilot, deepest nesting, leaks, off-thread calls), once, here at the serve end and never on a frame. The
        // per-class rewrite rows and counts are the premain's: it prints them itself (a separate unit, unlinked here).
        System.out.println(cc.starsector.server.transform.PilotShipWindow.metersRow());
        try {
            int byes = door.fanGoodbye();
            System.out.println("npeer-serve goodbye byes=" + byes);
        } catch (IOException peerGone) {
            System.out.println("npeer-serve goodbye_skipped reason=" + peerGone);
        }
        // Final snapshot (phase=final, throttle bypassed) AFTER the goodbye so
        // the byte totals include the BYE fan; run fields re-compose from the last frame.
        if (opsFile != null) {
            opsFile.writeFinal(door.bytesSentTotal(), door.readerCounters().bytesReceivedTotal,
                    door.sendFailures(),
                    () -> opsSeatRows(door.liveSessions(), opsSeatShips, door,
                            CustomVariantOps.ledgerOrNull(customLaneOrNull)));
        }
    }

    /**
     * One {@code npeer-serve snap_send} row per given session ({@link SnapSendGapMeter#exportRow}: seat = the session's
     * seat id, wire_id = its ship wire id, 0/0 for an observer) — the shared print of the round line, the
     * round_teardown receipt and the serve end. SIM THREAD ONLY: the meter's one writer is this thread's fan, so the
     * plain-field reads are coherent by program order.
     */
    private static void printSnapSendRows(int round, List<PilotIntentDatagramServer.PeerSession> sessions) {
        for (PilotIntentDatagramServer.PeerSession s : sessions) {
            System.out.println(SnapSendGapMeter.exportRow(s.snapSendGap, round, s.seatId(), s.shipId()));
        }
    }

    /**
     * The serve finally (leg 2): growth-listener backstop, operator poller, deflater, GPEE log,
     * wire sink, ops run-writer, JFR block recorder, stall sampler. Called from the finally of
     * {@link #serve()} on EVERY exit (normal, early return, throw) in this order.
     */
    private void teardown() {
        // Act 1b: the null guard is for the boot-throw path only — the resolver's
        // "engineBornGrowth=true but no growthSeam hook" IAE fires right after the flag is set,
        // and the flag now reaches here through the partial copy-back; on the success path the
        // seam is proven non-null at boot, so the guard changes nothing there.
        if (engineBornGrowth && hooks.growthSeam() != null) {
            // Idempotent backstop (the normal path already uninstalled pre-wrap): a throw
            // mid-round must not leave the listener on a live engine repo — and this
            // cleanup must never mask the original failure.
            try {
                hooks.growthSeam().uninstall();
            } catch (IOException | RuntimeException growthCleanupFailure) {
                System.out.println("npeer-serve growth_uninstall_backstop_failed reason="
                        + growthCleanupFailure);
            }
        }
        if (operatorFile != null) {
            operatorFile.stop(); // cc-op-poller daemon — tidy, never blocks exit
        }
        banLane.closeSink();
        hiddenShips.closeSink(); // drains a pending hidden-ships rewrite (at most 2 s), then stops
        if (downComposer != null) {
            downComposer.close(); // one deflater per serve — end() owed
        }
        if (gpeeLog != null) {
            gpeeLog.close(); // prints npeer-serve gpee_log_closed lines=N
        }
        if (customLaneOrNull != null) {
            customLaneOrNull.closePersist(); // step 3(e): catch-up feed + persist_sink_closed row
        }
        if (wireSink != null) {
            // Fold: the orderly close appends the END trailer with the serve's own
            // closing counters (the END caller keys of the R2 wire-layouts contract; record.row.faults = main's
            // addition so a reel whose recorder faulted is refused by the re-sim, never trusted silently);
            // the sink's own latches (disk dead, cap hit) keep writing their END / none themselves.
            LinkedHashMap<String, String> endKeys = new LinkedHashMap<>();
            endKeys.put("last.round", Integer.toString(lastRoundServed));
            endKeys.put("rng.rearm.count", Long.toString(CombatRngRearm.rearmCount()));
            endKeys.put("rng.redirect.uuids", Long.toString(cc.starsector.server.rngpin.CcRngRedirect.uuids()));
            endKeys.put("rng.redirect.landingKeys",
                    Long.toString(cc.starsector.server.rngpin.CcRngRedirect.landingKeys()));
            endKeys.put("rng.redirect.landingKeyFallbacks",
                    Long.toString(cc.starsector.server.rngpin.CcRngRedirect.landingKeyFallbacks()));
            endKeys.put("warmup.passes", Integer.toString(ServeWarmupRound.lastPassesOrNeg()));
            endKeys.put("record.row.faults", Long.toString(recordRowFaults));
            // the transfer journal's drops (cap overflow, a round's undrained tail) and the rows still undrained
            // at the end - a nonzero = a begin the reel never carried, said in the trailer
            endKeys.put("transfer.journal.overflow", Long.toString(rebindArbiter.transferJournalOverflow()));
            endKeys.put("transfer.journal.roundCleared", Long.toString(rebindArbiter.transferJournalRoundCleared()));
            endKeys.put("transfer.journal.undrainedAtEnd", Integer.toString(rebindArbiter.transferJournalSize()));
            if (replayFeed != null) {
                // A re-simulation's own reel carries its graded
                // counters (every other counter rides the summary row only).
                try {
                    endKeys.put("replay.take.stagedMismatch", Long.toString(replayFeed.stagedMismatches()));
                    endKeys.put("replay.strayDeposits", Long.toString(replayFeed.strayDeposits()));
                    endKeys.put("replay.rowsConsumed", Long.toString(replayFeed.rowsConsumed()));
                    endKeys.put("replay.suppressedSends", Long.toString(replaySuppressedSends()));
                    endKeys.put("replay.admitUnminted", Long.toString(replayFeed.admitUnminted()));
                    endKeys.put("replay.transferOutcomeMismatch", Long.toString(replayFeed.transferOutcomeMismatches()));
                    // The re-admitted VSUB rows whose frame disagreed with
                    // the row, and the accepts kept off disk (the replay detached the writer at boot).
                    endKeys.put("replay.vsubOfferMismatch", Long.toString(replayFeed.vsubOfferMismatches()));
                    endKeys.put("replay.persistSuppressed",
                            Integer.toString(customLaneOrNull == null ? 0 : customLaneOrNull.registeredCount()));
                } catch (RuntimeException fault) {
                    System.out.println("npeer-serve replay_end_keys_failed err=" + fault.getClass().getSimpleName());
                }
            }
            wireSink.closeWithEnd(endKeys);
            System.out.println("npeer-serve wire_sink_closed path=" + wireSink.path()
                    + " down=" + wireSink.recordsDown() + " up=" + wireSink.recordsUp()
                    + " door_pinned_drops=" + (sinkFeedDoor == null ? 0L
                            : sinkFeedDoor.pinnedIntentDrops() + sinkFeedDoor.pinnedDeclarationDrops())
                    + " error=" + (wireSink.error().isEmpty() ? "none" : wireSink.error())
                    + " rows=" + wireSink.recordsRows() + " row_faults=" + recordRowFaults);
        }
        if (replayFeed != null) {
            // The re-simulation's summary prints with or without a sink,
            // then the reel closes. The feed opens BEFORE the door, so a door that failed to open leaves no
            // adapter: its reads report 0 and one row says so (the open's own throw stays the serve's exit).
            if (replaySessionDoor == null) {
                System.out.println("npeer-serve replay_door unopened");
            }
            try (ReplayFeed feed = replayFeed) {
                System.out.println("npeer-serve replay_feed_summary " + feed.summaryTokens()
                        + " suppressed_sends=" + replaySuppressedSends()
                        + " bans_unpersisted=" + banLane.bansUnpersisted()
                        + " hidden_ships_unpersisted=" + hiddenShips.replayUnpersisted() //
                        + " custom_unpersisted=" + CustomVariantOps.replayUnpersisted() // RR-1's scope-stop fold (JVM-cumulative)
                        + " replay_cmd_noop=" + (replaySessionDoor == null ? 0L : replaySessionDoor.replayCmdNoop()));
            } catch (IOException | RuntimeException fault) {
                System.out.println("npeer-serve replay_feed_summary_failed err=" + fault.getClass().getSimpleName());
            }
        }
        if (opsFile != null) {
            // An exception before writeFinal must not leak the
            // decoupled run-writer thread (idempotent after a normal writeFinal).
            opsFile.retireRunWriter();
        }
        if (blockRecorder != null) {
            blockRecorder.stopAndDump(); // Receipt row: jfr_blocks state=dumped file= bytes=
        }
        if (stallStacks != null) {
            stallStacks.close(); // Receipt row: sim_stall_sampler state=closed blocks=
        }
    }

    /** The replay door's swallowed sends (0 when the door never opened);
     *  the counter outlives the door's close, so the teardown reads it after the serve's door is gone. */
    private long replaySuppressedSends() {
        return replaySessionDoor == null ? 0L : replaySessionDoor.suppressedSends();
    }

    /**
     * The replay door's session verbs as {@link ReplayFeed} calls them.
     * {@code mint} discards the minted session; {@code die} hands the recorded departure token through (the
     * door refuses anything but a bounded token, and the feed calls it only for a live id — a refusal ends
     * the re-simulation loud, never caught here). The door has no per-id liveness query, so {@code isLive}
     * scans the live set at EACH call (a replayed session leaves only through the door, never a cache).
     */
    private static final class ReplayDoorAdapter implements ReplayFeed.SessionDoor {
        private final PilotIntentDatagramServer door;

        ReplayDoorAdapter(PilotIntentDatagramServer door) {
            this.door = door;
        }

        @Override
        public void mint(long sessionId, int seat, long shipId, boolean observer, boolean admin, int helloMagicBits) {
            door.replayMint(sessionId, seat, shipId, observer, admin, helloMagicBits);
        }

        @Override
        public void die(long sessionId, String departureReason) {
            door.replayDie(sessionId, departureReason);
        }

        @Override
        public void clearTenureGate(int seat) {
            door.replayClearTenureGate(seat);
        }

        @Override
        public boolean isLive(long sessionId) {
            for (PilotIntentDatagramServer.PeerSession s : door.liveSessions()) {
                if (s.sessionId() == sessionId) {
                    return true;
                }
            }
            return false;
        }

        long suppressedSends() {
            return door.suppressedSends();
        }

        long replayCmdNoop() {
            return door.replayCmdNoop();
        }
    }

    /**
     * After the teardown (leg 2): the decoupled-lane stats row, the npeer-intent-served row
     * and the receipt (normal exit only, as before).
     */
    private PilotServeReceipt receipt() {
        // decoupled-lane teardown truth — queue depth + fail-open fallback
        // counts per lane (nonzero fallback_drains = the disk outran an 8192-chunk queue and
        // the submitting thread ate one synchronous drain; out_*=-1 = lane install fail-soft).
        if (ioDecoupled) {
            System.out.println("npeer-serve io_decoupled_stats"
                    + " out_chunks=" + (stdoutLane == null ? -1L : stdoutLane.chunks())
                    + " out_fallback_drains="
                    + (stdoutLane == null ? -1L : stdoutLane.fallbackDrains())
                    + " out_max_depth=" + (stdoutLane == null ? -1L : stdoutLane.maxQueueDepth())
                    + " ccwr_fallback_drains="
                    + (wireSink == null ? 0L : wireSink.decoupledFallbackDrains())
                    + " ccwr_max_depth=" + (wireSink == null ? 0L : wireSink.decoupledMaxQueueDepth())
                    + " ops_writes=" + (opsFile == null ? 0L : opsFile.decoupledRunWrites())
                    + " ops_skips=" + (opsFile == null ? 0L : opsFile.decoupledRunSkips()));
        }
        System.out.println("npeer-intent-served rounds=" + rounds.size()
                + " reset_frames=" + resetFramesEmitted
                + " last_match_over=" + lastRoundMatchOver
                + " client_connected=" + clientConnected
                + " held_lane_fallback=" + heldLaneFallback
                + " host=" + host + " port=" + port + " off_loopback=" + offLoopback
                // Declared population grade (appended field only).
                + " peer_grade=" + peerGrade
                // the transfer journal's three end meters (appended fields only)
                + " transfer_journal_overflow=" + rebindArbiter.transferJournalOverflow()
                + " transfer_journal_round_cleared=" + rebindArbiter.transferJournalRoundCleared()
                + " transfer_journal_undrained=" + rebindArbiter.transferJournalSize());
        return new PilotServeReceipt(host, port, offLoopback, clientConnected,
                rounds.size(), resetFramesEmitted, 0, lastRoundMatchOver, heldLaneFallback,
                rounds, null);
    }

    /**
     * The real cursor port (PeerSession over the
     * tick's {@code liveById}, or the round wrap's own live map) + the completion/abandon listener.
     * ONE instance per serve; the serve loop re-points {@link #bind} before each drain (serve-loop
     * thread only, no per-tick allocation).
     */
    private final class RappCatchupPort
            implements RosterAppendCatchup.Port<PilotIntentDatagramServer.PeerSession>,
            RosterAppendCatchup.Listener<PilotIntentDatagramServer.PeerSession> {
        private PilotIntentDatagramServer door;
        private Map<Long, PilotIntentDatagramServer.PeerSession> live;
        private int round;
        private int frame;

        void bind(PilotIntentDatagramServer door,
                Map<Long, PilotIntentDatagramServer.PeerSession> live, int round, int frame) {
            this.door = door;
            this.live = live;
            this.round = round;
            this.frame = frame;
        }

        @Override
        public boolean isLive(PilotIntentDatagramServer.PeerSession session) {
            return live != null && live.get(session.sessionId()) == session;
        }

        @Override
        public int headroom(PilotIntentDatagramServer.PeerSession session) {
            return session.ctrlHeadroom();
        }

        @Override
        public void send(PilotIntentDatagramServer.PeerSession session, byte[] frameBytes) {
            door.sendCtrlToSession(session, frameBytes);
        }

        @Override
        public int spoolDepth(PilotIntentDatagramServer.PeerSession session) {
            return session.ctrlSpoolDepth();
        }

        @Override
        public void setFlag(PilotIntentDatagramServer.PeerSession session, boolean on) {
            session.setRosterCatchupActive(on);
        }

        /** The reader thread marks a session provisioned once its admission burst is spooled;
         *  until then the cursor sees headroom 0. */
        @Override
        public boolean provisioned(PilotIntentDatagramServer.PeerSession session) {
            return session.provisioned();
        }

        /** The XFER segment: the in-flight transfer BEGINs, asked once at RAPP completion and
         *  sent whole by the cursor (it replaces the synchronous transfer replay — a BEGIN still
         *  follows every roster id RAPP taught). */
        @Override
        public List<byte[]> xferSnapshot(PilotIntentDatagramServer.PeerSession session) {
            return transferLane.inFlightBeginFrames(session);
        }

        /** A SEAT baseline row: the spool-only primitive, no per-frame sink record (the serve
         *  tick's one stamped record is the seat rows' sink truth). */
        @Override
        public void sendSeat(PilotIntentDatagramServer.PeerSession session, byte[] frameBytes) {
            door.sendSeatBaselineToSession(session, frameBytes);
        }

        @Override
        public void completed(long sessionId, PilotIntentDatagramServer.PeerSession session,
                RosterAppendCatchup.Cursor cursor, int logSize) {
            // The line reports a RAPP catch-up that delivered
            // log frames, as it always has — a cursor that sent no RAPP frame (an empty-log
            // admission, a wrap's seat-only re-baseline, a cursor whose RAPP was dropped at the
            // wrap) prints nothing here: the wave rig asserts NO such line for a joiner admitted
            // before any spawn. The in-flight transfer BEGINs already left in the cursor's own
            // XFER step (no replay here: it would send every BEGIN twice). A cursor a round wrap
            // finds with its RAPP walked but names still draining reports HERE at the wrap, in its
            // own round (RosterAppendCatchup.resetRound) — so frame= and ticks= never name a later
            // round.
            if (!cursor.rappCompleted() || cursor.rappSent() == 0) {
                return;
            }
            // The EXISTING line name + its field order (session frames round frame — the
            // run-wave-rig W-C check anchors `frames=N round=R`), new meters appended; sent= keeps
            // its RAPP-frames meaning, sent_all= counts every segment.
            System.out.println("npeer-serve roster_append_catchup session=" + sessionId
                    + " frames=" + logSize
                    + " round=" + round + " frame=" + frame
                    + " sent=" + cursor.rappSent()
                    + " ticks=" + (frame - cursor.startTick())
                    + " stalled_ticks=" + cursor.stalledTicks()
                    + " sent_all=" + cursor.sent()
                    + " spool_max=" + cursor.spoolMax());
        }

        @Override
        public void abandoned(long sessionId, PilotIntentDatagramServer.PeerSession session,
                RosterAppendCatchup.Cursor cursor, int logSize, String reason) {
            System.out.println("npeer-serve roster_append_catchup_abandoned session=" + sessionId
                    + " reason=" + reason + " sent=" + cursor.rappSent() + " of=" + logSize
                    + " round=" + round + " frame=" + frame);
        }
    }
}
