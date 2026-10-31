package cc.starsector.client;

import cc.starsector.client.net.DoorProbe;
import cc.starsector.client.net.PredictReconcileClientSession;
import cc.starsector.client.net.PredictReconcileDatagramSession;
import cc.starsector.client.net.PredictSession;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.runtime.PilotControlNames;
import com.fs.starfarer.api.Global;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * The predict JOIN DIAL: the Multiplayer Test click dial ({@link #dialPredictAtJoin}) and the Play dial
 * ({@link #dialPredictAtPlay}) with their shared start-a-dial verdict table ({@link #dialVerdict}),
 * {@link #DIAL_LOCK} and the dial / probe volatiles, the give-up and mission-init latches, the empty-pick door
 * probe, the connect thread that publishes {@link CcClientModPlugin#predictSession}, the post-round click
 * discriminator, the mission-brief rows and the one-per-connect seat name + posture echo.
 *
 * <p>WHY a class of its own: the dial was most of {@link CcClientModPlugin}'s non-blank lines, and it touches
 * the plugin at two seams only - the session registry {@link CcClientModPlugin#predictSession} (cleared by a
 * fresh dial and the connect thread's give-up, published by its connect; read by the dials and the briefing)
 * and {@link CcClientModPlugin#nextConnectPick()}. The plugin keeps no lock, thread or dial state. Moved
 * VERBATIM (bodies, javadoc, every log / fallback line, every throw message, both thread spawns and every
 * {@code synchronized (DIAL_LOCK)} block); the plugin keeps a one-line delegate for every member a caller
 * outside this class reached, so the mission definition, the combat plugins and the fail banner call the
 * names they always called.
 *
 * <p>Write law: {@link PredictDialPlanner#preparePredictDial} writes {@link #predictDialHost},
 * {@link #predictDialPort}, {@link #predictDialAttempts} and {@link #predictDialPlan} once at app load, in
 * that order; every other field here is written only by this class (the selftests stage fields directly or
 * by reflection).
 *
 *
 */
final class PredictDialLane {

    private PredictDialLane() {
    }

    /**
     * R6 front-door v0 — predict-lane connect retry budget. The predict session is opened at
     * application load (the menu). Before this, a SINGLE connect attempt there failed <em>terminally</em>
     * when the owner's server was not yet bound — the only recovery was relaunching the whole game
     * ({@code front-door} idea, the {@code FAILED->BROWSE} "nothing" cell). Instead retry the whole
     * connect on a backoff so a joiner can sit at the menu while the owner starts the server.
     * {@value #PREDICT_CONNECT_ATTEMPTS} attempts × ({@value #PREDICT_CLICK_HELLO_TIMEOUT_MILLIS} + {@value #PREDICT_CONNECT_BACKOFF_MILLIS}) ms
     * ≈ 12.5 min bare (+ the door probes when they run — the briefing prints the honest figure,
     * {@link #dialHorizonMillisPerAttempt} × attempts; "≈ 5 min" was attempts × backoff alone)
     * spans a comfortable "join, then host starts" window. Success on attempt 1 (the owner's launcher waits for
     * the port-file before launching the window) keeps the proven path byte-identical; exhausting the
     * budget falls closed to LOCAL VANILLA — the prior end state, just retried first. The budget can be
     * overridden by {@code cc.predict.connectAttempts} (then {@code CC_PREDICT_CONNECT_ATTEMPTS}) so a
     * test/WATCH can shrink it without a rebuild; ≤ 0 keeps the default.
     */
    static final int PREDICT_CONNECT_ATTEMPTS = 150;
    static final long PREDICT_CONNECT_BACKOFF_MILLIS = 2000L;

    /**
     * W13 leg 1b: the CLICK dial's per-attempt HELLO horizon. The
     * datagram session's 15 s default is a boot-dial relic — under the 150-attempt click loop
     * it made a silent door cost ≈ 42 min to give-up while the briefing promised ~5. The HELLO
     * retransmits every 250 ms inside either horizon, so a late bind is still caught within
     * 250 ms; only the per-attempt give-up shrinks. The honest horizon the briefing prints is
     * {@link #dialHorizonMillisPerAttempt} × attempts.
     */
    private static final int PREDICT_CLICK_HELLO_TIMEOUT_MILLIS = 3000;

    /**
     * W13 leg 1b mash guard: a post-round define with the LMB DOWN is a
     * hold/mash (never a list click — the define runs in the title's {@code prepare()}, before
     * its first rendered frame) whenever a combat-plugin frame inside this tail window ALSO saw
     * it DOWN. The results dialog dismisses on RELEASE and the 0.5 s fade-out follows, so a normal
     * dismiss click ends ≥ 0.5 s before the last combat frame — outside this window.
     */
    private static final long LMB_COMBAT_TAIL_MILLIS = 300L;

    /**
     * W13: everything the dial needs that is resolved ONCE at application load (credential mint,
     * fingerprint self-compute, transport, posture) — {@code null} = an ordinary non-predict
     * launch. The endpoint is re-resolved at each dial (a port file re-minted by a restarted
     * server wins; these values are the fallback), so the plan carries the app-load endpoint too.
     */
    static volatile PredictDialPlan predictDialPlan;

    /**
     * The join-pipeline selftest seam — the start-a-dial decision table
     * shared by {@link #dialPredictAtJoin} (click) and {@link #dialPredictAtPlay} (Play), pure so
     * {@code JoinPipelineLawSmoke} pins it game-free; the callers sample the inputs under
     * {@link #DIAL_LOCK} and own the log rows + side effects.
     */
    enum DialVerdict {
        /** A LIVE (non-defunct) session: no re-dial — the re-pick rides the takeover lane. */
        SESSION_LIVE,
        /** A dial is in flight and stays: same endpoint, or the auto re-select, or its thread is
         *  already gone — a re-click never doubles the HELLO. */
        LET_RUN,
        /** A dial is in flight toward an endpoint that MOVED (re-minted port file): cancel it
         *  and dial the new one (leg 1b). */
        MOVED_MID_DIAL,
        /** The engine's post-round auto re-select with no live session: a seat is claimed by a
         *  click only. */
        AUTO_RESELECT_NO_DIAL,
        /** A fresh dial is due (first / prior gave up / prior DEFUNCT / prior ended). */
        FRESH
    }

    static DialVerdict dialVerdict(boolean sessionLive, boolean autoReselect, boolean dialInFlight,
            boolean inFlightThreadKnown, boolean endpointMoved) {
        if (sessionLive) {
            return DialVerdict.SESSION_LIVE;
        }
        if (dialInFlight) {
            return (autoReselect || !endpointMoved || !inFlightThreadKnown)
                    ? DialVerdict.LET_RUN : DialVerdict.MOVED_MID_DIAL;
        }
        return autoReselect ? DialVerdict.AUTO_RESELECT_NO_DIAL : DialVerdict.FRESH;
    }

    /** Leg 1b click discriminator: after a round, LMB up = the engine's auto re-select; LMB
     *  down AND seen down in the combat tail = a hold / click-mash spanning the fade = also the
     *  auto re-select. With no round since the last define every define is a click. */
    static boolean isAutoReselect(boolean afterRound, boolean lmbDown, boolean lmbHeldFromCombat) {
        return afterRound && (!lmbDown || lmbHeldFromCombat);
    }

    /** The fresh-dial reason of record; {@code first} = "first click" / "first Play". */
    static String freshDialWhy(boolean priorSessionPresent, boolean priorDialGaveUp, int dialCount,
            String first) {
        return priorSessionPresent ? "prior session DEFUNCT"
                : priorDialGaveUp ? "prior dial gave up"
                        : dialCount == 0 ? first : "prior dial ended";
    }

    /** The in-flight dial's endpoint vs. the re-resolved one (a re-minted port file moves it). */
    private static boolean endpointMoved(DialEndpoint target) {
        return !target.host.equals(predictDialHost) || target.port != predictDialPort;
    }

    /** W13: serializes the start-a-dial decision (the engine's define thread vs. the connect
     *  thread's completion) — a re-click while a dial is in flight must never mint a second HELLO
     *  on the same client id (a same-cid takeover of ourselves). */
    private static final Object DIAL_LOCK = new Object();
    /** W13: a connect thread is running (cleared by that thread on landing / give-up). */
    private static volatile boolean predictDialInFlight;
    /** W13: dials started this process (the thread-name suffix + the log discriminator). */
    private static volatile int predictDialCount;
    /** W13: the in-flight (or last) dial's attempt counter, 1-based; 0 = not started. */
    private static volatile int predictDialAttempt;
    /** W13: the in-flight (or last) dial's attempt budget. */
    static volatile int predictDialAttempts;
    /** W13: the in-flight (or last) dial's endpoint, for the mission briefing rows. */
    static volatile String predictDialHost = "";
    static volatile int predictDialPort;
    /** W13: the LAST decisive door refusal the in-flight dial saw (scrubbed, ≤64), or null —
     *  the briefing's "waiting: the match is FULL" row while the loop keeps retrying. */
    private static volatile String predictDialLastRefusal;
    /** W13: a combat round was entered since the last cc_predict define — the engine re-selects
     *  the PLAYED mission on the return to the title with NO click ({@code TitleScreenState
     *  .checkNewHighScore} → {@code selectMission}, one results-bearing exit path), so the first
     *  define after a round with the mouse button UP is that auto re-select, not a join click.
     *  Set by {@link #markRoundEntered}, consumed by {@link #dialPredictAtJoin}. */
    private static volatile boolean roundEnteredSinceLastDefine;
    /** W13 leg 1b: the in-flight dial's thread — interrupted (cancelled) by a re-click whose
     *  re-resolved endpoint differs from the one that dial is HELLOing (a serve re-minted on
     *  another port after the click); null when no dial is in flight. */
    private static volatile Thread predictDialThread;
    /** W13 leg 1b mash guard ({@link #LMB_COMBAT_TAIL_MILLIS}): wall-clock of the last
     *  combat-plugin frame, and of the last such frame that saw the LMB DOWN (0 = never). */
    private static volatile long lastCombatFrameMillis;
    private static volatile long lastLmbDownCombatMillis;

    // ---- W13 leg 2: CLAIM AT PLAY state ----
    /** Leg 2a: the click's NON-claiming door probe thread (null = none in flight). */
    private static volatile Thread predictProbeThread;
    private static volatile boolean predictProbeInFlight;
    private static volatile int predictProbeCount;
    private static volatile int predictProbeAttempt;
    /** Leg 2a: the newest probe answer (null until a try lands; served / refused / no answer). */
    private static volatile DoorProbe.Result predictProbeResult;
    private static volatile String predictProbeHost = "";
    private static volatile int predictProbePort;
    /** Leg 2c: wall-clock the combat plugin began waiting for the own hull to field (0 = none). */
    private static volatile long claimPendingSinceMillis;
    private static volatile long claimSeat;
    /** Leg 2c: the current round's combat init found NO session after its bounded wait. */
    private static volatile boolean roundStartedWithoutSession;

    /**
     * loud-fail: TRUE once a predict dial burned its WHOLE retry budget (or hit
     * a terminal refusal) — the state behind the two-empty-rounds incident (every ship
     * left locally driven, the window a real-looking LOCAL VANILLA round). Distinct from
     * {@link CcClientModPlugin#predictSession} {@code == null}, which also holds mid-dial, before the first click
     * and on an ordinary non-predict launch (no endpoint configured). W13 / rejoin L3: PER-DIAL,
     * no longer process-terminal — the next Multiplayer Test click starts a fresh dial and clears
     * it ({@link #dialPredictAtJoin}); the in-combat banner stays up for the rest of a round that
     * started under it.
     */
    private static volatile boolean predictConnectGaveUp;

    /**
     * TRUE when the LAST give-up was a LOCAL refusal: this build could not form its join request (the dial's
     * {@code IllegalArgumentException} / {@code IllegalStateException} — a field the version-13 flight cannot carry,
     * a connect precondition, a broken hs-13 provider), so NOTHING was sent and a retry refuses the same way. WHY a
     * latch: with {@link #predictConnectRefusedReason} null the give-up read "No answer from the host" and the banner
     * told a stranger to "confirm the server is up". Written ONLY in {@link #connectPredictSession}'s give-up (before
     * {@link #predictConnectGaveUp}), cleared ONLY in {@link #startFreshDial}; every reader also requires
     * {@link #predictConnectGaveUp}. Package-private so rows can stage it.
     *
     *
     */
    static volatile boolean predictConnectLocalRefusal;

    /**
     * The door's machine reason behind a give-up, or null when
     * the connect simply never got a decisive answer (server not up / unreachable — the
     * generic banner line). Set ONCE beside {@link #predictConnectGaveUp} from the LAST typed
     * refusal the retry loop saw (the HELLO's {@code WELCOME REJECTED} reason, else the
     * empty-pick probe's); already scrubbed to printable ASCII ≤64 at the store (the wire-text
     * class), so the banner may render it verbatim.
     */
    private static volatile String predictConnectRefusedReason;

    /**
     * The match file this window launched with cannot be built ({@link MatchMissionBuilder#loadFailure()}, a
     * {@code match_doc_*} reason + field): the dial planner left NO plan, and this arms the give-up latches the
     * way the dial loop's terminal refusal does - reason first, then the flag - so the briefing and the in-combat
     * banner name the fix. WHY no extra terminal latch: only {@link #startFreshDial} clears these, and both of its
     * callers return first on a null plan ({@link #dialPredictAtJoin}'s plan check, {@link #dialPredictAtPlay}'s
     * {@link #claimAtPlayLane}), so nothing in this process can dial or clear them.
     *
     *
     */
    static void armMatchFileRefusal(String refusal) {
        predictConnectRefusedReason = FallbackAnnounce.sanitize(refusal, 64);
        predictConnectLocalRefusal = false;
        predictConnectGaveUp = true;
    }

    /**
     * loud-fail: whether the LAST predict dial gave up (whole retry budget
     * burned / terminal refusal — see {@link #predictConnectGaveUp}; W13: cleared by the next
     * click's fresh dial). {@link PredictFailBannerCombatPlugin} is the consumer; it must key on
     * THIS, never on {@link CcClientModPlugin#predictSession()} {@code == null} (also null mid-dial, before the
     * first click and on non-predict launches).
     */
    public static boolean predictConnectGaveUp() {
        return predictConnectGaveUp;
    }

    /** The scrubbed door reason behind {@link #predictConnectGaveUp()}, or null
     *  = no decisive refusal was ever received (see {@link #predictConnectRefusedReason}). */
    public static String predictConnectRefusedReason() {
        return predictConnectRefusedReason;
    }

    /** Whether the LAST give-up was a LOCAL refusal ({@link #predictConnectLocalRefusal}: nothing was sent);
     *  meaningful only while {@link #predictConnectGaveUp()} holds. */
    public static boolean predictConnectLocalRefusal() {
        return predictConnectLocalRefusal;
    }

    /**
     * TRUE once mission init found the app-scoped predict session DEFUNCT —
     * a re-pick after the served session ended (post-match BYE / terminal failure), the state
     * behind the silent-blank report. Reachable only by a Play/Restart that skipped
     * the mission-list click (a click re-dials a defunct session — W13 / rejoin L3,
     * {@link #dialPredictAtJoin} — and clears this); healthy exits never arm it (the kept-open
     * session passes the defunct gate untouched). {@link PredictFailBannerCombatPlugin} presents it.
     */
    private static volatile boolean missionSessionRefused;

    /** Whether a mission init refused the defunct app-scoped session — the
     * banner plugin's second key (see {@link #missionSessionRefused}). */
    public static boolean missionSessionRefused() {
        return missionSessionRefused;
    }

    /** Race guard: TRUE while the last mission init refused to bind a LIVE
     * session because the mission was built BEFORE the (late) WELCOME assigned the seat —
     * binding would drive a hull the local fleet did not field. NOT process-terminal (unlike
     * the two keys above): re-entering the mission rebuilds on the seeded seat and the next
     * successful bind clears this. {@link PredictFailBannerCombatPlugin} presents it. */
    private static volatile boolean autoSeatStaleBuild;

    /** The stale-build refusal latch — the banner plugin's third key. */
    public static boolean autoSeatStaleBuild() {
        return autoSeatStaleBuild;
    }

    /** Mission-init writer for {@link #autoSeatStaleBuild} (set on refusal, cleared on bind). */
    static void setAutoSeatStaleBuild(boolean value) {
        autoSeatStaleBuild = value;
    }

    /** Latched when a mission init armed the kept-open re-admission (the
     * {@code armReAdmission()==true} result carried into the plugin); the predict plugin's
     * {@link RejoinRebaselineLane} consumes it at first own-hull sight to scope the re-join
     * deploy-burn kill — a first-ever join never sets it. */
    private static volatile boolean reAdmissionArmedThisInit;

    /** Read-and-clear {@link #reAdmissionArmedThisInit} (the (56) first-sight consume). */
    static boolean consumeReAdmissionArmed() {
        boolean armed = reAdmissionArmedThisInit;
        reAdmissionArmedThisInit = false;
        return armed;
    }

    /**
     * The mission-init acquisition of {@link CcClientModPlugin#predictSession()}, with the
     * defunct gate: {@code null} (mid-connect / non-predict / gaveUp) and LIVE sessions pass
     * through unchanged (those lanes keep their exact prior behavior); a DEFUNCT one arms
     * {@link #missionSessionRefused} (one ERROR on the flip) and returns {@code null}, so the
     * caller's existing connect-fail hold-open lane presents instead of consuming a session
     * whose first receive throws (net thread death → silent LOCAL VANILLA).
     */
    public static PredictSession predictSessionForMissionInit() {
        PredictSession s = CcClientModPlugin.predictSession;
        if (s == null || !s.isDefunct()) {
            // A LIVE session kept open across a mission exit re-baselines on
            // re-pick — deliberate fresh-nonce re-HELLO (server: same-token TAKEOVER, fresh
            // sessionId re-fires the newly-live catch-up) + UP-intent hold until fresh-world
            // adopt. armReAdmission consumes the exit latch, so this fires at most once per
            // kept-open exit (idempotent across the double mission-init poll) and never on a
            // first-ever init or mid-connect null.
            if (s != null && s.armReAdmission()) {
                reAdmissionArmedThisInit = true; // (56): the plugin-side re-join scope key
                PilotWatchLog.info("mission init: LIVE kept-open predict session -> deliberate"
                        + " fresh-nonce re-HELLO armed (TAKEOVER re-baseline; UP intents held"
                        + " until fresh-world adopt)");
            }
            return s;
        }
        if (!missionSessionRefused) {
            missionSessionRefused = true;
            PilotWatchLog.error("mission init: app-scoped predict session is DEFUNCT (served"
                    + " session ended) -> re-pick refused; hold-open + AlphaCoreMP SESSION DEAD banner"
                    + " present; leave with ESC and select Multiplayer Test again to re-dial"
                    + " (W13 / rejoin L3)",
                    new IllegalStateException("defunct app-scoped predict session"));
        }
        return null;
    }

    /**
     * W13: DIAL the
     * predict session at the player's click on the Multiplayer Test mission — the cc_predict
     * {@code MissionDefinition.defineMission} prelude calls this FIRST, before the match build, so
     * the build's bounded first-attempt wait ({@link AssignedSeatFromWire}) absorbs a
     * LAN WELCOME and fields the server-assigned seat on the very first click. The engine's
     * title-screen auto-define never reaches here: {@code mission_list.csv} keeps cc_predict OFF
     * row 1 (the auto-selected row), so this fires on a player action only.
     *
     * <p>Decision table (under {@link #DIAL_LOCK}): no plan ⇒ ordinary launch, no-op. A LIVE
     * session (not defunct, not kicked) ⇒ no-op — the re-pick rides the kept-open takeover lane
     * ({@link #predictSessionForMissionInit}). A dial IN FLIGHT ⇒ no-op while the re-resolved
     * endpoint is the one it is HELLOing (a re-click never doubles the HELLO; the budget runs
     * from the click that started it) — but a re-click whose endpoint MOVED (a serve re-minted
     * on another port after the click) CANCELS that dial (thread interrupt; leg 1b)
     * and falls through to a fresh one. Otherwise (first click; prior dial gave up; prior
     * session DEFUNCT after a match-end BYE / transport death; prior session KICKED) ⇒ a FRESH
     * dial: the dead session is closed, the per-dial latches ({@link #predictConnectGaveUp},
     * {@link #predictConnectRefusedReason}, {@link #predictConnectLocalRefusal}, {@link #missionSessionRefused}) and the seat seed
     * clear, the endpoint is re-resolved (a port file re-minted by a restarted server wins), and
     * a new connect thread runs the proven {@link #connectPredictSession} loop. This IS rejoin L3
     * with trigger = the join click: post-match re-entry
     * no longer needs a game relaunch. Never throws (the define must always finish): any failure
     * logs and leaves the window LOCAL VANILLA for this pick.
     */
    public static void dialPredictAtJoin() {
        final PredictDialPlan plan = predictDialPlan;
        if (plan == null) {
            return; // ordinary launch (no cc.predict.* endpoint) — nothing to dial
        }
        try {
            // The engine path + input state behind THIS define, one row per define.
            PilotWatchLog.info("define provenance: " + defineProvenance());
            if (claimAtPlayLane(plan)) {
                // W13 leg 2a — claim upon play: on a registry
                // shape the click no longer claims a seat — it runs the NON-claiming door probe for
                // the briefing rows; the dial (= the claim) moves to Play
                // (PredictReconcileCombatPlugin.init -> dialPredictAtPlay).
                probeAtJoin(plan);
                return;
            }
            synchronized (DIAL_LOCK) {
                // Click vs. the engine's post-round auto re-select (see roundEnteredSinceLastDefine):
                // a define with no round since the last one is always a click (the title's own
                // auto-select is row 1 = a vanilla mission); after a round, the LMB-down state the
                // list hotspot fires on tells them apart. LWJGL
                // unavailable (verify harness) = fail-open, treated as a click. Leg 1b mash guard
                // (LMB_COMBAT_TAIL_MILLIS): DOWN now AND DOWN at a combat frame in the tail = a
                // hold / click-mash spanning the fade, which is the auto re-select, not a click.
                boolean afterRound = roundEnteredSinceLastDefine;
                roundEnteredSinceLastDefine = false;
                boolean lmbDown = leftMouseButtonDownOrUnknown();
                boolean lmbHeldFromCombat = lmbDown && lmbSeenDownInCombatTail();
                boolean autoReselect = isAutoReselect(afterRound, lmbDown, lmbHeldFromCombat);
                PredictSession live = CcClientModPlugin.predictSession;
                boolean sessionLive = live != null && !live.isDefunct();
                if (sessionLive) {
                    // A KICKED session is LIVE here on purpose: its re-pick rides the proven
                    // armReAdmission lane (clears kickedTerminal, fresh-nonce re-HELLO at Play).
                    PilotWatchLog.info("join " + (autoReselect ? "auto-reselect" : "click")
                            + ": predict session LIVE (shipId=" + live.shipId()
                            + (live.isKickedTerminal() ? ", KICKED" : "")
                            + ") -> no re-dial (the re-pick rides the kept-open takeover lane)");
                    return;
                }
                // The endpoint is re-resolved per define: a server restarted on a new port re-mints
                // the port file, and the click is the earliest moment the player means it.
                DialEndpoint target = resolveDialEndpoint(plan);
                if (target == null) {
                    return; // the endpoint changed kind since launch: no dial (resolveDialEndpoint logged it)
                }
                Thread inFlight = predictDialThread;
                DialVerdict verdict = dialVerdict(sessionLive, autoReselect, predictDialInFlight,
                        inFlight != null, predictDialInFlight && endpointMoved(target));
                String why;
                switch (verdict) {
                case LET_RUN:
                    PilotWatchLog.info("join " + (autoReselect ? "auto-reselect" : "click")
                            + ": predict dial already IN FLIGHT (dial #" + predictDialCount
                            + " attempt " + predictDialAttempt + " of " + predictDialAttempts
                            + ", " + predictDialHost + ":" + predictDialPort
                            + ") -> letting it run (a re-click never doubles the HELLO)");
                    return;
                case MOVED_MID_DIAL:
                    // Leg 1b: the endpoint the in-flight dial is HELLOing is gone — cancel it
                    // (its late writes are gated on the dial number below) and dial the new one.
                    PilotWatchLog.info("join click: predict endpoint MOVED while dial #"
                            + predictDialCount + " was in flight " + predictDialHost + ":"
                            + predictDialPort + " -> " + target.host + ":" + target.port
                            + " (re-minted port file) -> cancelling it and re-dialing");
                    inFlight.interrupt();
                    why = "endpoint moved mid-dial";
                    break;
                case AUTO_RESELECT_NO_DIAL:
                    PilotWatchLog.info("join auto-reselect (post-round, "
                            + (lmbHeldFromCombat ? "LMB held/mashed through the fade" : "no click")
                            + "): predict session "
                            + (live != null ? "DEFUNCT" : predictConnectGaveUp ? "gave up" : "absent")
                            + " -> NOT re-dialing (a seat is claimed by a click only); select"
                            + " Multiplayer Test to reconnect");
                    return;
                default:
                    why = freshDialWhy(live != null, predictConnectGaveUp, predictDialCount, "first click");
                    break;
                }
                startFreshDial(plan, live, target, why, "join click");
            }
        } catch (Throwable t) {
            PilotWatchLog.error("join click: predict dial FAILED to start -> this pick stays LOCAL"
                    + " VANILLA; select Multiplayer Test again to retry", t);
        }
    }

    /** The keymap as 32 lower-case hex digits, byte 0 first (key codes and mouse slots only: never text).
     * */
    static String keymapHex(byte[] keymap) {
        StringBuilder hex = new StringBuilder(keymap.length * 2);
        for (byte b : keymap) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** How many of the keymap's entries carry a binding (non-zero). */
    static int keymapMapped(byte[] keymap) {
        int mapped = 0;
        for (byte b : keymap) {
            if (b != 0) {
                mapped++;
            }
        }
        return mapped;
    }

    /**
     * The fresh-dial body shared by the click dial ({@link #dialPredictAtJoin}, legacy lanes) and
     * the Play dial ({@link #dialPredictAtPlay}, W13 leg 2c) — caller holds {@link #DIAL_LOCK} and
     * has already decided a FRESH dial is due ({@code live} = the defunct session to close, or
     * null). {@code trigger} prefixes the log rows ({@code join click} / {@code play}); every
     * auditor-grepped row lives in {@link #connectPredictSession} and stays byte-identical.
     */
    private static void startFreshDial(PredictDialPlan plan, PredictSession live, DialEndpoint target,
            String why, String trigger) {
        if (live != null) {
            try {
                live.close(); // idempotent, never throws (PredictSession contract)
                ChatLane.forgetAuthFor(live); // The closed session's kept /auth line goes with it
            } catch (Throwable t) {
                PilotWatchLog.error(trigger + ": closing the " + why + " session threw"
                        + " (continuing with the re-dial)", t);
            }
            CcClientModPlugin.predictSession = null;
        }
        final String host = target.host;
        final int port = target.port;
        if (!host.equals(plan.host) || port != plan.port) {
            PilotWatchLog.info(trigger + ": predict endpoint MOVED since launch " + plan.host
                    + ":" + plan.port + " -> " + host + ":" + port + " (re-minted port file)");
        }
        // rejoin L3: the once process-terminal latches are PER-DIAL.
        predictConnectGaveUp = false;
        predictConnectRefusedReason = null;
        predictConnectLocalRefusal = false;
        missionSessionRefused = false;
        predictDialLastRefusal = null;
        predictDialAttempt = 0;
        predictDialAttempts = plan.attempts;
        predictDialHost = host;
        predictDialPort = port;
        predictDialCount++;
        final int dialNo = predictDialCount;
        PilotWatchLog.info(trigger + ": opening predict session (dial #" + dialNo + ", " + why
                + "; background thread, retrying connect up to " + plan.attempts + "x, ~"
                + (dialHorizonMillisPerAttempt(plan) / 1000L) + " s per attempt ("
                + PREDICT_CLICK_HELLO_TIMEOUT_MILLIS + " ms HELLO + "
                + PREDICT_CONNECT_BACKOFF_MILLIS + " ms backoff) from the " + trigger + ") host=" + host
                + " port=" + port + " shipId=" + plan.shipId + " group=" + plan.group
                + " transport=" + plan.transport
                + (plan.observer ? " posture=OBSERVER (seat 0 / ship 0, no own hull)" : "")
                + " variant=" + (plan.variantId.isEmpty() ? "(server default)" : plan.variantId));
        // The HELLO pick is the CURRENT BUILD (saves/common/cc_variants/current.data — the
        // editor's FLY, Save-when-none, or the join window's combo on Launch), else the window's launch
        // pick — READ at DIAL time, per connect, so a FLY at the title flies on the next Multiplayer Test
        // select and every reconnect re-reads it. The HELLO carries it (the door holds it for the VSUB)
        // and CurrentBuildLane.submitLocalCustomVariant reads its file — one pick, both places.
        final String variantPick = CcClientModPlugin.nextConnectPick();
        if (!variantPick.equals(plan.variantId)) {
            PilotWatchLog.info(trigger + ": HELLO pick = current build '"
                    + FallbackAnnounce.sanitize(variantPick, 64) + "' (current.data) over the window pick "
                    + (plan.variantId.isEmpty() ? "(none)" : "'" + plan.variantId + "'"));
        }
        // the mission builder fields it at t=0 (as a probed default does) — re-seated on EVERY dial, not
        // only when it differs from the window pick: a saved-then-deleted stem would otherwise stay the
        // t=0 hull (its VDEF fold outlives the file) while the HELLO carries the window pick
        JoinVariantPick.set(variantPick);
        // The pilot's own control keymap, snapshotted HERE on the game thread
        // (the engine settings and the key-name table are game-thread reads) and handed to the connect thread, which
        // seals it into the HELLO so the serve answers this seat's by-name control reads with this pilot's own
        // bindings. The session keeps this copy for every re-HELLO (a timer thread that must never read the engine);
        // a lookup that fails leaves its entry 0, and an all-zero map means "the serve's own bindings answer".
        final byte[] keymap = PilotControlNames.resolveKeymap(
                n -> Global.getSettings().getControlStringForEnumName(n), Keyboard::getKeyIndex);
        PilotWatchLog.info(trigger + ": HELLO keymap " + keymapHex(keymap) + " (" + keymapMapped(keymap) + "/"
                + keymap.length + " controls bound; 00 = unbound or unexpressible, f0+ = a mouse button)");
        Thread connectThread = new Thread(() -> {
            try {
                connectPredictSession(dialNo, host, port, plan.shipId, plan.group, plan.attempts,
                        plan.transport, plan.clientId, plan.seatToken, plan.lane,
                        plan.handshakeVersion, plan.fingerprint, variantPick, plan.probePassword,
                        plan.observer, plan.reelDoor, keymap);
            } catch (Throwable t) {
                PilotWatchLog.error("predict session connect thread died (dial #" + dialNo
                        + ") -> gave up: a held round stays parked, any other window stays LOCAL VANILLA (the"
                        + " banner names the reason); ESC leaves, Play re-dials", t);
                if (predictDialCount == dialNo) {
                    predictConnectGaveUp = true;
                }
            } finally {
                synchronized (DIAL_LOCK) {
                    // Leg 1b: a CANCELLED dial (superseded by a later number) must never
                    // clear its successor's in-flight flag or settle its builder wait.
                    if (predictDialCount == dialNo) {
                        predictDialInFlight = false;
                        predictDialThread = null;
                        AssignedSeatFromWire.markFirstAttemptSettled(); // never leave a waiter hanging
                    }
                }
            }
        }, "cc-predict-connect-" + dialNo);
        connectThread.setDaemon(true);
        // Arm the mission builder's bounded first-attempt wait BEFORE the thread
        // runs — the click caller builds the mission right after this returns, on this thread
        // (the Play caller has no builder waiting; the seed still feeds the mission lanes).
        // A re-dial also DROPS the prior seat seed: the fresh HELLO may land another seat.
        AssignedSeatFromWire.markConnectStarted(dialNo > 1);
        predictDialInFlight = true;
        predictDialThread = connectThread;
        connectThread.start();
    }

    // ------------------------------------------------------------------------------------------
    // W13 leg 2 — CLAIM AT PLAY.
    // Registry shapes + pilot posture only: the legacy lanes
    // (1v1 / vanilla: shapes, the observer) keep the leg-1b click dial byte-identical.
    // ------------------------------------------------------------------------------------------

    /** Leg 2: the click-probe / Play-dial lane applies — a non-observer plan on a registry-shape
     *  descriptor (the shape is what makes a zero-hull build legal, MatchMissionBuilder's
     *  observer refusal). Never throws (descriptor unreadable ⇒ legacy lane). */
    private static boolean claimAtPlayLane(PredictDialPlan plan) {
        if (plan == null || plan.observer) {
            return false;
        }
        try {
            MatchDescriptor d = MatchMissionBuilder.cachedDescriptor();
            return d != null && MatchMissionBuilder.isRegistryShape(d.shape());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Leg 2a — the click's NON-claiming door probe: the JoinGui's pre-launch listing fetch
     * ({@link DoorProbe#fetch13}, the match password sealed: a PROBE-flagged HELLO the door answers with its ship listing and
     * never admits — no seat, no slot, no ledger row), on a background thread so the define
     * returns at once; {@link #joinBriefingRows} reads the published answer on every re-click.
     * Leg 1b's laws govern it: one probe in flight per endpoint (a re-click lets it run), a MOVED
     * endpoint cancels + re-probes, a silent door is retried up to the dial budget with the dial
     * backoff, and a decisive answer (served / refused) ends the loop. The token lane (no match
     * password) has no probe HELLO ⇒ no thread. Never runs while a dial is in flight or a
     * session is LIVE (their rows win). The wire carries NO seat count (the listing = hull ids +
     * the host default), so the rows say reachable / default hull, not "N of M seats free" —
     * build adjudication, wire ZERO. Never throws.
     */
    private static void probeAtJoin(PredictDialPlan plan) {
        synchronized (DIAL_LOCK) {
            roundEnteredSinceLastDefine = false; // the click discriminator is moot: a probe never claims
            PredictSession live = CcClientModPlugin.predictSession;
            if ((live != null && !live.isDefunct()) || predictDialInFlight) {
                PilotWatchLog.info("join click: predict " + (predictDialInFlight ? "dial IN FLIGHT"
                        : "session LIVE (shipId=" + live.shipId() + ")") + " -> no door probe"
                        + " (the briefing shows the session state; claim-at-play lane)");
                return;
            }
            if (plan.probePassword == null) {
                PilotWatchLog.info("join click: token lane (no match password) -> no door probe;"
                        + " Play dials + claims (claim-at-play lane)");
                return;
            }
            DialEndpoint target = resolveDialEndpoint(plan);
            if (target == null) {
                return; // the endpoint changed kind since launch: no probe (resolveDialEndpoint logged it)
            }
            if (predictProbeInFlight) {
                Thread inFlight = predictProbeThread;
                boolean moved = !target.host.equals(predictProbeHost) || target.port != predictProbePort;
                if (!moved || inFlight == null) {
                    PilotWatchLog.info("join click: door probe already IN FLIGHT (probe #" + predictProbeCount
                            + " try " + predictProbeAttempt + ", " + predictProbeHost + ":" + predictProbePort
                            + ") -> letting it run");
                    return;
                }
                PilotWatchLog.info("join click: predict endpoint MOVED while probe #" + predictProbeCount
                        + " was in flight -> cancelling it and re-probing " + target.host + ":" + target.port);
                inFlight.interrupt();
            }
            final String host = target.host;
            final int port = target.port;
            predictProbeHost = host;
            predictProbePort = port;
            predictProbeResult = null;
            predictProbeAttempt = 0;
            predictProbeCount++;
            final int probeNo = predictProbeCount;
            PilotWatchLog.info("join click: door PROBE #" + probeNo + " (non-claiming; claim-at-play lane) host="
                    + host + " port=" + port + " -> background thread, up to " + plan.attempts + " tries");
            Thread t = new Thread(() -> runDoorProbeLoop(probeNo, plan, host, port),
                    "cc-predict-probe-" + probeNo);
            t.setDaemon(true);
            predictProbeInFlight = true;
            predictProbeThread = t;
            t.start();
        }
    }

    private static void runDoorProbeLoop(int probeNo, PredictDialPlan plan, String host, int port) {
        try {
            for (int attempt = 1; attempt <= plan.attempts; attempt++) {
                if (predictProbeCount != probeNo || Thread.currentThread().isInterrupted()) {
                    return; // superseded / cancelled
                }
                predictProbeAttempt = attempt;
                DoorProbe.Result r;
                try {
                    // The sealed probe, unconditionally - no version-12 datagram exists to pick
                    r = DoorProbe.fetch13(host, port, plan.probePassword, plan.clientId, plan.fingerprint);
                } catch (Throwable t) {
                    r = DoorProbe.Result.failed("probe threw: " + t);
                }
                if (predictProbeCount != probeNo) {
                    return;
                }
                predictProbeResult = r;
                if (r.served()) {
                    MatchMissionBuilder.noteProbedDefaultHull(r.defaultId); // The next build's placeholder guess
                }
                if (r.sawDocument) {
                    // The door sends its match document only to a match term it
                    // does not hold, so document frames at the click = the host changed the match after this window
                    // launched. The window cannot rebuild its mission from them; it TELLS (a client-only token, never
                    // sent), and Play still dials - the door's own refusal stays the authority. Under DIAL_LOCK with
                    // the probe number re-checked: a Play that superseded this probe has already cleared the latches.
                    boolean first;
                    synchronized (DIAL_LOCK) {
                        first = predictProbeCount == probeNo && !PredictFailBannerCombatPlugin.MATCH_STALE_AFTER_LAUNCH
                                .equals(predictConnectRefusedReason);
                        if (first) {
                            predictConnectRefusedReason = PredictFailBannerCombatPlugin.MATCH_STALE_AFTER_LAUNCH;
                            predictConnectGaveUp = true;
                        }
                    }
                    if (first) {
                        PilotWatchLog.info("door probe #" + probeNo + " try " + attempt + ": the door sent its match"
                                + " document -> the host changed the match after launch ("
                                + PredictFailBannerCombatPlugin.MATCH_STALE_AFTER_LAUNCH + "); the briefing says close"
                                + " the game and press R in the join console");
                    }
                }
                // listRefused: this build refused the list it was sent — decisive, no retry.
                // localRefusal: a version-13 request this build cannot form —
                // nothing was sent and every retry refuses the same way, so it is decisive too.
                boolean decisive = r.served() || r.rejectReason != null || r.listRefused || r.localRefusal;
                if (attempt == 1 || decisive) {
                    PilotWatchLog.info("door probe #" + probeNo + " try " + attempt + ": "
                            + (r.served() ? "SERVED ids=" + r.ids.size() + " hidden=" + r.hidden
                                    + " total=" + r.totalAvailable + " default=" + r.defaultId
                                    : r.rejectReason != null ? "REFUSED " + FallbackAnnounce.sanitize(r.rejectReason, 64)
                                            : r.listRefused ? "LIST REFUSED by this build (" + r.error + ")"
                                            : r.localRefusal ? "NOT SENT by this build (" + r.error + ")"
                                            : "no answer (" + r.error + ")")
                            + (decisive ? "" : " -> retrying every " + PREDICT_CONNECT_BACKOFF_MILLIS
                                    + " ms up to " + plan.attempts + " tries (one log)"));
                }
                if (decisive) {
                    return;
                }
                try {
                    Thread.sleep(PREDICT_CONNECT_BACKOFF_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            PilotWatchLog.info("door probe #" + probeNo + ": no answer after " + plan.attempts
                    + " tries -> stopped (select the mission again to re-probe; Play still dials)");
        } finally {
            synchronized (DIAL_LOCK) {
                if (predictProbeCount == probeNo) {
                    predictProbeInFlight = false;
                    predictProbeThread = null;
                }
            }
        }
    }

    /**
     * Leg 2c — the PLAY-time dial (= the claim). {@code PredictReconcileCombatPlugin.init} calls
     * this BEFORE its session acquisition; TRUE = init should hold its widened bounded wait for a
     * WELCOME (a dial was started here or is in flight on this endpoint), FALSE = nothing pending
     * (no plan / legacy lane / the endpoint changed kind since launch ({@link #resolveDialEndpoint})
     * / session already LIVE — the kept-open takeover lane re-HELLOs in
     * {@link #predictSessionForMissionInit}). Same decision table as the click dial minus the
     * click discriminator (Play is always deliberate): LIVE ⇒ no-op; in flight, same endpoint ⇒
     * let it run; MOVED ⇒ cancel + fresh; else a fresh dial (defunct / gave up / first). Any
     * in-flight door probe is cancelled first (one HELLO source toward the door's per-source
     * budget). Never throws.
     */
    public static boolean dialPredictAtPlay() {
        final PredictDialPlan plan = predictDialPlan;
        if (!claimAtPlayLane(plan)) {
            return false;
        }
        try {
            synchronized (DIAL_LOCK) {
                Thread probe = predictProbeThread;
                if (probe != null) {
                    predictProbeCount++; // supersede: the probe loop's finally leaves these flags alone
                    predictProbeInFlight = false;
                    predictProbeThread = null;
                    probe.interrupt();
                }
                PredictSession live = CcClientModPlugin.predictSession;
                if (live != null && !live.isDefunct()) {
                    PilotWatchLog.info("play: predict session LIVE (shipId=" + live.shipId()
                            + (live.isKickedTerminal() ? ", KICKED" : "")
                            + ") -> no re-dial (the re-pick rides the kept-open takeover lane)");
                    return false;
                }
                DialEndpoint target = resolveDialEndpoint(plan);
                if (target == null) {
                    return false; // the endpoint changed kind since launch: no dial, nothing for init to wait on
                }
                Thread inFlight = predictDialThread;
                // Play is always deliberate: autoReselect=false (the click discriminator is the join lane's).
                DialVerdict verdict = dialVerdict(false, false, predictDialInFlight,
                        inFlight != null, predictDialInFlight && endpointMoved(target));
                String why;
                if (verdict == DialVerdict.LET_RUN) {
                    PilotWatchLog.info("play: predict dial already IN FLIGHT (dial #" + predictDialCount
                            + " attempt " + predictDialAttempt + " of " + predictDialAttempts + ", "
                            + predictDialHost + ":" + predictDialPort
                            + ") -> letting it run; combat init waits for it");
                    return true;
                } else if (verdict == DialVerdict.MOVED_MID_DIAL) {
                    PilotWatchLog.info("play: predict endpoint MOVED while dial #" + predictDialCount
                            + " was in flight " + predictDialHost + ":" + predictDialPort + " -> "
                            + target.host + ":" + target.port + " -> cancelling it and re-dialing");
                    inFlight.interrupt();
                    why = "endpoint moved mid-dial";
                } else {
                    why = freshDialWhy(live != null, predictConnectGaveUp, predictDialCount, "first Play");
                }
                startFreshDial(plan, live, target, why, "play");
                return true;
            }
        } catch (Throwable t) {
            PilotWatchLog.error("play: predict dial FAILED to start -> no new dial: the round waits parked if an"
                    + " earlier dial is still in flight, else stays LOCAL VANILLA (the banner names the reason);"
                    + " ESC leaves, Play re-dials", t);
            return false;
        }
    }

    /** Leg 2c: the combat plugin's claim state for {@link PredictFailBannerCombatPlugin}. */
    static void noteClaimPending(long seat) {
        claimSeat = seat;
        claimPendingSinceMillis = System.currentTimeMillis();
    }

    static void noteClaimSettled() {
        claimPendingSinceMillis = 0L;
    }

    public static long claimPendingSinceMillis() {
        return claimPendingSinceMillis;
    }

    public static long claimSeat() {
        return claimSeat;
    }

    static void noteRoundStartedWithoutSession(boolean value) {
        roundStartedWithoutSession = value;
    }

    public static boolean roundStartedWithoutSession() {
        return roundStartedWithoutSession;
    }

    /**
     * The late-attach HOLD flag: TRUE while a cc_predict window opened with the
     * Play dial still in flight sits parked and inert, waiting to bind the WELCOME into THIS round.
     * Writer = the combat plugin alone (set at init's end when the dial is in flight, cleared at the
     * bind, at the next init and in every teardown — a stale TRUE would pick the hold wording for a
     * LOCAL window). Reader = the fail banner's key / text choice. The hold has NO timer of its own:
     * its limit is the dial's own give-up ({@link #PREDICT_CONNECT_ATTEMPTS} attempts, the banner
     * counts them); the abandoned-window watchdog (the plugin's 10 s stale-advance constant) is the
     * only other exit, and ESC leaves at any time.
     */
    private static volatile boolean lateAttachHold;

    static void noteLateAttachHold(boolean value) {
        lateAttachHold = value;
    }

    public static boolean lateAttachHold() {
        return lateAttachHold;
    }

    /** The LAST door refusal the in-flight dial is retrying through (scrubbed, ≤ 64), or null while
     *  no refusal has been answered — the hold text names it ({@link #predictDialLastRefusal}). */
    public static String predictDialLastRefusal() {
        return predictDialLastRefusal;
    }

    public static boolean predictDialInFlight() {
        return predictDialInFlight;
    }

    public static int predictDialAttempt() {
        return predictDialAttempt;
    }

    public static int predictDialAttempts() {
        return predictDialAttempts;
    }

    /**
     * Leg 2a briefing rows (written as AlphaCoreMP from the start):
     * session state first, then the Play dial, then the click probe's answer. ≤ 3 rows, ≤ 60
     * chars each ({@link #briefingRows}).
     */
    private static String[] claimAtPlayBriefingRows(PredictDialPlan plan, PredictSession live) {
        String dialWhere = predictDialHost + ":" + predictDialPort;
        if (live != null && !live.isDefunct()) {
            if (live.isKickedTerminal()) {
                // P25-c: reason-aware (a seat-released victim reads it HERE, never in combat)
                return briefingRows(KickedNoticeLane.briefingLines(live.kickedNoticeReason(), dialWhere));
            }
            return briefingRows("AlphaCoreMP: CONNECTED to " + dialWhere,
                    "Your ship is seat " + live.shipId(),
                    "Press Play when the host says GO");
        }
        if (predictDialInFlight) {
            String refusal = predictDialLastRefusal;
            String progress = "attempt " + Math.max(1, predictDialAttempt) + " of " + predictDialAttempts;
            // one-argument (non-reel) form — a reel plan is an observer plan, never on this lane
            return briefingRows("AlphaCoreMP: connecting to " + dialWhere + " (" + progress + ")",
                    refusal != null ? PredictFailBannerCombatPlugin.describeRefusal(refusal)
                            : "no reply yet - retrying in the background",
                    "Press Play - your ship waits parked until connected");
        }
        if (predictConnectGaveUp) {
            String refusal = predictConnectRefusedReason;
            if (refusal == null && predictConnectLocalRefusal) {
                return localRefusalGiveUpRows();
            }
            if (PredictFailBannerCombatPlugin.MATCH_STALE_AFTER_LAUNCH.equals(refusal)) {
                // The click probe's tell, its two sentences one per row (each
                // row is clipped at 60 chars) - never "Press Play to try again" under a "close the game" fix.
                return briefingRows("AlphaCoreMP: NOT CONNECTED",
                        "The host changed the match after you launched.",
                        "Close the game and press R in the join console.");
            }
            return briefingRows("AlphaCoreMP: NOT CONNECTED", // non-reel form: claim-at-play never runs a reel plan
                    refusal == null ? "No answer from the host at " + dialWhere
                            : PredictFailBannerCombatPlugin.describeRefusal(refusal),
                    "banned".equals(refusal) ? PredictFailBannerCombatPlugin.BANNED_HINT
                            : "Press Play to try again");
        }
        String where = predictProbeHost.isEmpty() ? plan.host + ":" + plan.port
                : predictProbeHost + ":" + predictProbePort;
        if (plan.probePassword == null) {
            return briefingRows("AlphaCoreMP: server " + where,
                    "Press Play to connect and claim your seat");
        }
        DoorProbe.Result r = predictProbeResult;
        if (r != null && r.served()) {
            int offered = Math.max(r.totalAvailable, r.ids.size() + r.hidden);
            return briefingRows("AlphaCoreMP: server reachable at " + where,
                    r.defaultId != null ? "Default hull: " + r.defaultId : offered + " hull(s) offered",
                    "Press Play to claim a seat when the host says GO");
        }
        if (r != null && r.rejectReason != null) {
            return briefingRows("AlphaCoreMP: server at " + where + " refused", // non-reel form: a reel plan never probes
                    PredictFailBannerCombatPlugin.describeRefusal(FallbackAnnounce.sanitize(r.rejectReason, 64)),
                    "Select this mission again to refresh");
        }
        if (r != null && r.listRefused) { // K-F1: an answer this build refused — never "no answer yet - retrying"
            return briefingRows("AlphaCoreMP: server at " + where + " answered",
                    "It sent a mod list this build cannot accept - tell the host",
                    "Select this mission again to re-check");
        }
        if (r != null && r.localRefusal) { // Nothing was sent — never "no answer yet - retrying"
            return briefingRows("AlphaCoreMP: nothing was sent to " + where,
                    "This build cannot form its join request (see the log) - tell the host",
                    "Select this mission again to re-check");
        }
        if (predictProbeInFlight) {
            return briefingRows("AlphaCoreMP: checking " + where + " (try " + Math.max(1, predictProbeAttempt) + ")",
                    r == null ? "waiting for the server to answer" : "no answer yet - retrying in the background",
                    "Select this mission again to refresh");
        }
        return briefingRows("AlphaCoreMP: no answer from " + where,
                "Select this mission again to re-check",
                "Press Play to try connecting anyway");
    }

    /** W13 leg 1b: the endpoint one define / Play dials or probes ({@link #resolveDialEndpoint}). */
    private static final class DialEndpoint {
        final String host;
        final int port;

        DialEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Briefing-row latch: the LAST
     * {@link #resolveDialEndpoint} refused — the endpoint changed kind since launch, so every click
     * and every Play is refused until a relaunch. WHY a latch: the refusal used to live only in the
     * log, and {@link #joinBriefingRows} kept inviting a click that could never dial. Cleared by the
     * next resolve that reads the launch-time kind again. Package-private for row L3i.
     */
    static volatile boolean predictEndpointKindMoved;

    /**
     * W13 leg 1b: the per-define endpoint resolve — a re-minted port file wins over the plan's
     * app-load values; a null re-resolve, or an unreadable / non-numeric port (one log), keeps the
     * plan's. Returns {@code null} when the re-resolved endpoint's provenance differs from the
     * plan's ({@link PredictDialPlan#provenanceMoved}: the plan's credentials were minted for the
     * other kind of door — a live plan's typed password must never reach the in-process reel replay
     * door): every caller then neither dials nor probes; one log row per call + one announce per
     * process name the two kinds, never a host or a value.
     *
     * <p>The provenance refusal.
     */
    private static DialEndpoint resolveDialEndpoint(PredictDialPlan plan) {
        String host = plan.host;
        int port = plan.port;
        ClientLaunchKnobs.PredictEndpoint endpoint = ClientLaunchKnobs.resolvePredictEndpoint();
        predictEndpointKindMoved = PredictDialPlan.provenanceMoved(plan.reelDoor, endpoint);
        if (predictEndpointKindMoved) {
            String kinds = plan.reelDoor ? "reel replay door -> live server" : "live server -> reel replay door";
            PilotWatchLog.info("predict endpoint re-resolve: the endpoint changed kind since launch (" + kinds
                    + ") -> NO dial, NO door probe (the plan's credential was minted for the launch-time"
                    + " endpoint; relaunch the window)");
            FallbackAnnounce.announceOnce("predict-endpoint-kind-moved", "predict endpoint changed kind since"
                    + " launch (" + kinds + ") -> no dial; relaunch the window");
            return null;
        }
        if (endpoint != null) {
            try {
                int p = Integer.parseInt(endpoint.port);
                if (p > 0) {
                    host = endpoint.host;
                    port = p;
                }
            } catch (NumberFormatException e) {
                PilotWatchLog.error("join click: re-resolved predict port not parseable: '"
                        + endpoint.port + "' -> keeping the app-load endpoint", e);
            }
        }
        return new DialEndpoint(host, port);
    }

    /** W13 leg 1b: the click dial's honest per-attempt cost on a SILENT door — the HELLO horizon
     *  + the backoff, plus the default-pick door probe where the loop runs one per attempt
     *  (password lane, empty pick, pilot posture — {@link #connectPredictSession}). */
    private static long dialHorizonMillisPerAttempt(PredictDialPlan plan) {
        long per = PREDICT_CLICK_HELLO_TIMEOUT_MILLIS + PREDICT_CONNECT_BACKOFF_MILLIS;
        boolean probes = !plan.observer && "udp".equals(plan.transport) && plan.variantId.isEmpty()
                && plan.probePassword != null;
        if (probes) {
            per += (long) DoorProbe.TRIES * DoorProbe.RECEIVE_TIMEOUT_MILLIS;
        }
        return per;
    }

    /** W13: combat init (a round entered) — arms the post-round auto-reselect discriminator. */
    static void markRoundEntered() {
        roundEnteredSinceLastDefine = true;
    }

    /**
     * W13 leg 1b — the combat plugin calls this at the top of EVERY advance
     * (paused / fading frames included): stamps the frame and, when LWJGL's left button is DOWN,
     * the last-DOWN stamp the post-round mash guard reads ({@link #LMB_COMBAT_TAIL_MILLIS}). A
     * failed Mouse read (no display, harness) leaves the DOWN stamp alone — the guard never arms.
     */
    public static void markCombatFrame() {
        long now = System.currentTimeMillis();
        lastCombatFrameMillis = now;
        try {
            if (org.lwjgl.input.Mouse.isCreated() && org.lwjgl.input.Mouse.isButtonDown(0)) {
                lastLmbDownCombatMillis = now;
            }
        } catch (Throwable t) {
            // no display / no LWJGL (verify harness): the guard simply never arms
        }
    }

    private static boolean lmbSeenDownInCombatTail() {
        long down = lastLmbDownCombatMillis;
        return down != 0L && lastCombatFrameMillis - down <= LMB_COMBAT_TAIL_MILLIS;
    }

    /**
     * Define provenance (a test window once defined with no remembered click): ONE row per
     * define naming the ENGINE path that reached {@code defineMission} plus the input state at that
     * instant, so a test run classifies the define without the operator's memory. Frames kept = the
     * title-screen / ui classes only, innermost first (engine-internal simple names shortened to 8 chars
     * + length): a list-row click reads {@code ...notifyHotspotClicked <- <ui hotspot>} (the ui
     * hotspot's LMB-down dispatch); the title's own row-1 auto-select reads
     * {@code notifyHotspotClicked <- <init>}; the post-round auto re-select reads
     * {@code selectMission <- TitleScreenState.checkNewHighScore}; the reset-fleet dialog reads
     * {@code setMission <- <dialog>.dialogDismissed}. Never throws.
     */
    static String defineProvenance() {
        StringBuilder sb = new StringBuilder(200);
        try {
            boolean mouse = org.lwjgl.input.Mouse.isCreated();
            sb.append("lmb=").append(mouse ? (org.lwjgl.input.Mouse.isButtonDown(0) ? "DOWN" : "up") : "n/a");
            sb.append(" mouse=").append(mouse
                    ? org.lwjgl.input.Mouse.getX() + "," + org.lwjgl.input.Mouse.getY() : "n/a");
            sb.append(" focus=").append(org.lwjgl.opengl.Display.isCreated()
                    ? (org.lwjgl.opengl.Display.isActive() ? "yes" : "NO") : "n/a");
        } catch (Throwable t) {
            sb.append("input=unreadable");
        }
        sb.append(" path=");
        try {
            int kept = 0;
            for (StackTraceElement f : new Throwable().getStackTrace()) {
                String cls = f.getClassName();
                if (!cls.startsWith("com.fs.starfarer.title.") && !cls.startsWith("com.fs.starfarer.ui.")) {
                    continue;
                }
                String simple = cls.substring(cls.lastIndexOf('.') + 1);
                if (simple.length() > 12) {
                    simple = simple.substring(0, 8) + "(" + simple.length() + ")";
                }
                if (kept > 0) {
                    sb.append(" <- ");
                }
                sb.append(simple).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
                if (++kept >= 10) {
                    break;
                }
            }
            if (kept == 0) {
                sb.append("(no title/ui frame)");
            }
        } catch (Throwable t) {
            sb.append("(unreadable)");
        }
        return sb.toString();
    }

    /** W13: LWJGL's left-button state (the list hotspot fires on LMB-down, so a click-driven
     *  define sees it DOWN); any failure (no display, harness) reads as DOWN = a click. */
    private static boolean leftMouseButtonDownOrUnknown() {
        try {
            return !org.lwjgl.input.Mouse.isCreated() || org.lwjgl.input.Mouse.isButtonDown(0);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * W13: the mission-briefing rows the cc_predict {@code MissionDefinition} adds after the build
     * ({@code api.addBriefingItem} — the vanilla preview-text lane, so the connection state is
     * visible on the Missions screen with zero new UI). Composed AFTER the build's bounded wait,
     * so a LAN click already reads CONNECTED. Re-rendered on every click (each list click re-runs
     * {@code defineMission}; Play does not). Never throws.
     */
    public static String[] joinBriefingRows() {
        try {
            PredictDialPlan plan = predictDialPlan;
            if (plan == null) {
                String refusal = predictConnectGaveUp ? predictConnectRefusedReason : null;
                if (refusal != null) {
                    return matchFileRefusalRows(refusal); // the planner armed it: the endpoint was fine, the match was not
                }
                return ClientLaunchKnobs.predictEndpointKnobPresent()
                        ? briefingRows("AlphaCoreMP: no server endpoint at launch - LOCAL VANILLA",
                                "(port file unreadable or port unset)",
                                "Relaunch through the joiner kit (join-cc.bat)")
                        : briefingRows("AlphaCoreMP: not launched via the joiner kit - LOCAL only",
                                "Use join-cc.bat to play online");
            }
            String where = predictDialHost + ":" + predictDialPort;
            PredictSession live = CcClientModPlugin.predictSession;
            if (predictEndpointKindMoved && !predictDialInFlight && (live == null || live.isDefunct())) {
                // rider b3b-briefing-row: every click / Play is refused now — never invite another one
                return briefingRows("AlphaCoreMP: NOT CONNECTED",
                        "The server endpoint changed since this window launched",
                        "Close this window and launch it again");
            }
            if (claimAtPlayLane(plan)) {
                return claimAtPlayBriefingRows(plan, live); // W13 leg 2a: probe / Play rows
            }
            if (live != null && !live.isDefunct()) {
                if (live.isKickedTerminal()) {
                    // P25-c: reason-aware (a seat-released victim reads it HERE, never in combat)
                    return briefingRows(KickedNoticeLane.briefingLines(live.kickedNoticeReason(), where));
                }
                return briefingRows("AlphaCoreMP: CONNECTED to " + where,
                        plan.observer ? "OBSERVER posture (no own hull)"
                                : "Your ship is seat " + live.shipId(),
                        "Press Play when the host says GO");
            }
            if (predictDialInFlight) {
                String refusal = predictDialLastRefusal;
                String progress = "attempt " + Math.max(1, predictDialAttempt) + " of "
                        + predictDialAttempts;
                if (refusal != null) {
                    return briefingRows("AlphaCoreMP: WAITING at " + where + " (" + progress + ")",
                            PredictFailBannerCombatPlugin.describeRefusal(refusal, plan.reelDoor), //
                            "Select this mission again to refresh");
                }
                // Leg 1b: the horizon is the honest silent-door cost, not attempts x backoff.
                long minutes = Math.max(1L, Math.round(predictDialAttempts
                        * dialHorizonMillisPerAttempt(plan) / 60000.0));
                return briefingRows("AlphaCoreMP: connecting to " + where + " - no reply yet",
                        progress + ", retrying for ~" + minutes + " min in the background",
                        "Select this mission again to refresh");
            }
            if (predictConnectGaveUp) {
                String refusal = predictConnectRefusedReason;
                if (refusal == null && predictConnectLocalRefusal) {
                    return localRefusalGiveUpRows();
                }
                return briefingRows("AlphaCoreMP: NOT CONNECTED",
                        refusal == null ? "No answer from the host at " + where
                                : PredictFailBannerCombatPlugin.describeRefusal(refusal, plan.reelDoor),
                        "banned".equals(refusal) ? PredictFailBannerCombatPlugin.BANNED_HINT
                                : "Select this mission again to retry");
            }
            if (live != null) {
                return briefingRows("AlphaCoreMP: the served session has ended",
                        "Select this mission again to reconnect");
            }
            return briefingRows("AlphaCoreMP: not connected yet",
                    "Select this mission to connect to " + where);
        } catch (Throwable t) {
            PilotWatchLog.error("join briefing rows FAILED (rows omitted)", t);
            return new String[0];
        }
    }

    /**
     * The no-plan briefing when {@link #armMatchFileRefusal} fired: the banner's match-file sentence cut into rows of
     * at most 60 chars (a longer field is clipped by {@link #briefingRows}), never the "no server endpoint" rows -
     * the endpoint resolved; the match file is what failed.
     *
     *
     */
    private static String[] matchFileRefusalRows(String refusal) {
        int space = refusal.indexOf(' ');
        String token = space < 0 ? refusal : refusal.substring(0, space);
        if (cc.starsector.server.match.MatchDocumentLaws.REASON_VARIANT_MISSING.equals(token)) {
            return briefingRows("AlphaCoreMP: NOT CONNECTED - LOCAL VANILLA",
                    "The host's match uses a ship your game lacks ("
                            + (space < 0 ? "?" : refusal.substring(space + 1)) + " missing)",
                    "Close the game; the join window's mods line says more");
        }
        return briefingRows("AlphaCoreMP: NOT CONNECTED - LOCAL VANILLA",
                "Your match file is unusable (" + refusal + ")",
                "Close the game and run join-cc again");
    }

    /** Leg 1b: the engine's Tactical Briefing widget is five SINGLE-LINE
     *  labels sized for vanilla's ≤ ~60-char rows — every row is clipped to that width. */
    private static final int BRIEFING_ROW_MAX_CHARS = 60;

    /**
     * The give-up rows of BOTH briefing builders ({@link #claimAtPlayBriefingRows}, {@link #joinBriefingRows}) when
     * {@link #predictConnectLocalRefusal} holds and no door reason was kept: nothing was sent, so never "No answer
     * from the host" (the {@code localRefusal} probe rows' wording family; each row ≤ 60 chars).
     *
     *
     */
    private static String[] localRefusalGiveUpRows() {
        return briefingRows("AlphaCoreMP: NOT CONNECTED", "This build cannot form its join request (see the log)",
                "Tell the host; retrying cannot help");
    }

    private static String[] briefingRows(String... rows) {
        String[] out = new String[rows.length];
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i] == null ? "" : rows[i];
            out[i] = r.length() <= BRIEFING_ROW_MAX_CHARS ? r
                    : r.substring(0, BRIEFING_ROW_MAX_CHARS - 3) + "...";
        }
        return out;
    }

    /**
     * Open the predict session OFF the engine's thread (mirrors {@code PilotSessionBoot.connectPilotSession}),
     * RETRYING the connect on a backoff (R6 front-door v0). W13: runs on the click-started dial
     * thread ({@link #dialPredictAtJoin}). The session is published to {@link CcClientModPlugin#predictSession} on
     * the first attempt that lands, so a joiner who clicks before the host's server has bound
     * connects when it comes up — no game relaunch, no re-click. When the server is already bound
     * at the click (attempt 1 succeeds) this is byte-for-byte the old single-attempt path. After
     * the whole budget is exhausted {@link CcClientModPlugin#predictSession} stays {@code null} (fail-closed: a held
     * round never starts, any other round is LOCAL VANILLA) until the next click re-dials. The connect
     * usually lands while the joiner waits at the menu, before combat init snapshots
     * {@link CcClientModPlugin#predictSession()}. On the claim-at-play lane a connect that only completes AFTER the
     * mission is entered is bound into THAT round: the combat plugin holds it, own ship parked, until
     * the WELCOME lands or the dial gives up ({@link #lateAttachHold()}). On every other lane it is
     * NOT picked up — that round stays local, and the next Play claims the session.
     * <p>{@code lane} + {@code handshakeVersion} = the plan's (the planner
     * sets the live Handshake13.VERSION), handed unchanged to the datagram dial — and the version to the
     * default-pick probe.
     *
     * <p>{@code reelDoor} = the plan's {@code PredictDialPlan.reelDoor} (the dial's door kind — a re-resolve of the
     * other kind never dials, {@link #resolveDialEndpoint}): the ONLY source of the terminal check's door fact, so the
     * kit-only refusal token stops the loop only from the kit's own reel door.
     *
     * <p>{@code keymap} = the 16-byte control keymap {@link #startFreshDial} read on the game thread; every attempt's
     * HELLO seals the same array, this thread never reads the engine settings.
     */
    private static void connectPredictSession(int dialNo, String host, int port, long shipId,
            int group, int attempts, String transport, String clientId, String seatToken,
            cc.starsector.server.runtime.Handshake13Flight.Lane lane, int handshakeVersion,
            String fingerprint, String variantId, String probePassword, boolean observer, boolean reelDoor,
            byte[] keymap) {
        // An EMPTY pick on the password lane adopts the door's published
        // default BEFORE the HELLO (pick = HELLO variantId AND the mission's t=0 hull). The probe
        // re-runs ahead of each attempt until the door answers decisively (a listing, with or
        // without a default, or a REJECT); a no-reply just means the door isn't up yet — the
        // very case this retry loop exists for.
        String pick = variantId;
        // An observer fields no hull, so the default-pick probe has nothing to decide.
        boolean probeDecided = observer || !("udp".equals(transport) && variantId.isEmpty()
                && probePassword != null);
        // The LAST decisive door refusal seen on this loop (typed HELLO reject
        // or the probe's); null = the door never answered decisively (timeouts only).
        String lastRefusal = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (dialCancelled(dialNo, attempt)) {
                return; // leg 1b: superseded by a re-click on a MOVED endpoint
            }
            predictDialAttempt = attempt; // W13: the briefing row's progress counter
            if (!probeDecided) {
                DoorProbe.Result probe = probeServerDefaultPick(host, port, probePassword,
                        clientId, fingerprint, handshakeVersion);
                if (probe.served() && probe.defaultId != null) {
                    pick = probe.defaultId;
                    JoinVariantPick.set(pick); // the mission builder fields it at t=0
                }
                if (probe.rejectReason != null) {
                    lastRefusal = probe.rejectReason;
                }
                probeDecided = probe.served() || probe.rejectReason != null;
            }
            try {
                PredictSession session = "udp".equals(transport)
                        ? PredictReconcileDatagramSession.connect(
                                host, port, shipId, group, clientId, seatToken, lane,
                                handshakeVersion, fingerprint,
                                pick, observer, // The observer posture
                                PREDICT_CLICK_HELLO_TIMEOUT_MILLIS, // leg 1b: the click horizon
                                keymap)
                        : PredictReconcileClientSession.connect(host, port, shipId, group);
                if (dialCancelled(dialNo, attempt)) {
                    // Leg 1b: cancelled between the WELCOME and here — never publish over the
                    // successor dial; the door's seat is released by the BYE this close sends.
                    session.close();
                    return;
                }
                // Under AUTO (requested 0) the session's shipId is the WELCOME-
                // assigned seat — every consumer below (name announce, log discriminator, the
                // OPEN line) speaks the CONCRETE id from here on.
                long resolvedShipId = session.shipId();
                PilotWatchLog.adoptSeat(resolvedShipId);
                try {
                    // seat-name handshake: the ONE SNAM announce rides immediately at
                    // connect, BEFORE the first receive (the server's bounded pre-loop awaitSeatName
                    // read follows its accept) — and BEFORE the session is published, so a consumer
                    // can never race in front of it.
                    announceSeatName(session, resolvedShipId);
                    // The ONE PSTE posture echo rides right behind the SNAM,
                    // same reliable CTRL lane, BEFORE the session is published. UDP lane only
                    // (the interface default THROWS on the retired TCP twin); unconditional —
                    // detection must not be knob-suppressible (no eventReplay gate: the posture
                    // lane exists to expose local re-posturing, so a knob opt-out defeats it).
                    if ("udp".equals(transport)) {
                        sendPostureEcho(session, resolvedShipId);
                    }
                } catch (Throwable t) {
                    session.close(); // don't leak the socket into the retry loop below
                    throw t;
                }
                synchronized (DIAL_LOCK) {
                    // Leg 1b: the publish is atomic against a re-click starting dial #(n+1).
                    if (predictDialCount != dialNo) {
                        PilotWatchLog.info("predict session dial #" + dialNo + " landed AFTER being"
                                + " superseded by dial #" + predictDialCount + " -> closing it");
                        session.close();
                        return;
                    }
                    CcClientModPlugin.predictSession = session;
                }
                // W15 v0: ONE VSUB per connect, right after the
                // publish — a local custom pick (saves/common/cc_variants/<id>.variant.data)
                // rides the reliable CTRL lane; the door HOLDS the HELLO pick <= 30 s for it.
                // Fail-soft: no file / bad file / dead lane = server default + the hold expiry.
                if ("udp".equals(transport) && !observer) {
                    CurrentBuildLane.submitLocalCustomVariant(session, pick); //
                }
                AssignedSeatFromWire.markFirstAttemptSettled(); // Seed adopted in connect() above
                PilotWatchLog.info("predict session OPEN (background) on attempt " + attempt + " -> " + host + ":"
                        + port + " shipId=" + resolvedShipId
                        + (observer ? " (OBSERVER — seat 0 / ship 0)"
                                : shipId == 0L ? " (AUTO — server-assigned)" : "")
                        + " group=" + group + " transport=" + transport
                        // The effective SO_RCVBUF, udp only (so -1 = unreadable)
                        + ("udp".equals(transport) ? " rcvbuf=" + session.udpReceiveBufferBytes() : ""));
                return;
            } catch (Throwable t) {
                if (dialCancelled(dialNo, attempt)) {
                    return; // leg 1b: the interrupt landed inside the HELLO / probe — not a failure
                }
                // Attempt 1 has its verdict (failed / refused) — release the mission
                // builder's bounded wait NOW so a server-down launch keeps its fast fallback.
                AssignedSeatFromWire.markFirstAttemptSettled();
                // Terminal refusals: a TERMINAL door refusal (kit/credential/content class — see
                // PredictFailBannerCombatPlugin.isTerminalRefusal) ends the loop on THIS attempt;
                // retrying a HELLO the door will refuse identically only burns the attempt budget (≈ 12.5 min bare)
                // while the player sits at the menu. Seat/table refusals and timeouts keep retrying.
                // A LOCAL refusal — an
                // argument the dial refuses (IllegalArgumentException: the flight constructor's field,
                // a connect precondition) or its warm's provider failure (IllegalStateException) — is
                // refused identically on every attempt, so it ends the loop now. No door spoke:
                // lastRefusal stays as it was.
                boolean localRefusal = t instanceof IllegalArgumentException
                        || t instanceof IllegalStateException;
                boolean terminal = localRefusal;
                if (t instanceof PredictReconcileDatagramSession.AdmissionRejected) {
                    lastRefusal = ((PredictReconcileDatagramSession.AdmissionRejected) t).reason;
                    terminal = PredictFailBannerCombatPlugin.isTerminalRefusal(lastRefusal, reelDoor);
                    // W13: the briefing's "WAITING - the match is FULL" row while this loop keeps
                    // retrying (scrubbed at the store as wire text).
                    predictDialLastRefusal = FallbackAnnounce.sanitize(lastRefusal, 64);
                }
                if (attempt == 1 && !terminal) {
                    PilotWatchLog.error("predict session connect attempt 1 FAILED host=" + host + " port=" + port
                            + " -> retrying up to " + attempts + "x every " + PREDICT_CONNECT_BACKOFF_MILLIS
                            + "ms (waiting for the server to bind)", t);
                }
                if (attempt == attempts || terminal) {
                    // Publish the scrubbed reason + the local-refusal latch BEFORE the flag
                    // (the banner reads flag-then-reason on the render thread; all volatile).
                    String refusal = lastRefusal == null ? null
                            : FallbackAnnounce.sanitize(lastRefusal, 64); //
                    predictConnectRefusedReason = refusal;
                    predictConnectLocalRefusal = localRefusal;
                    PilotWatchLog.error("predict session connect gave up "
                            + (terminal
                                    ? "on attempt " + attempt + " of " + attempts
                                            + (localRefusal ? " (LOCAL refusal - retrying cannot help)"
                                                    : " (TERMINAL refusal - retrying cannot help)")
                                    : "after " + attempts + " attempts")
                            + " host=" + host + " port=" + port + " refusal="
                            + (refusal == null ? "none (no decisive door reply)" : refusal)
                            + " -> ships left locally driven (window stays LOCAL VANILLA, no reconcile;"
                            + " W13: the next Multiplayer Test click re-dials)", t);
                    CcClientModPlugin.predictSession = null;
                    predictConnectGaveUp = true; // The in-combat banner's ONE trigger
                    return;
                }
            }
            try {
                Thread.sleep(PREDICT_CONNECT_BACKOFF_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (dialCancelled(dialNo, attempt)) {
                    return; // leg 1b: cancelled in the backoff
                }
                PilotWatchLog.info("predict session connect retry interrupted -> LOCAL VANILLA");
                return;
            }
        }
    }

    /** Leg 1b: this dial was CANCELLED (its thread interrupted by a re-click on a MOVED endpoint,
     *  or a newer dial number already owns the latches). Logs once per dial, then stays true. */
    private static boolean dialCancelled(int dialNo, int attempt) {
        if (!Thread.currentThread().isInterrupted() && predictDialCount == dialNo) {
            return false;
        }
        if (predictDialCancelLogged != dialNo) {
            predictDialCancelLogged = dialNo;
            PilotWatchLog.info("predict session dial #" + dialNo + " CANCELLED on attempt " + attempt
                    + " (a re-click restarted the dial on a moved endpoint; dial #"
                    + predictDialCount + " owns the session now)");
        }
        return true;
    }

    private static volatile int predictDialCancelLogged;

    /**
     * Host-window t=0 hull swap: the in-game
     * EMPTY-pick fallback. The kit's JoinGui pre-probes the door and launches the game with the
     * concrete id, so the kit never swaps; a window launched with NO pick (the watch rig's host
     * window; a kit joiner whose pre-launch probe transiently failed) used to carry {@code ""} in
     * the HELLO, the server armed {@code pendingVariantPick=joinerDefault} at admit, and the
     * mission built at t=0 on the DESCRIPTOR hull ~6 s before the RAPP re-field — the visible
     * grendel→aurora swap. Fix = run the IDENTICAL probe the kit runs ({@link DoorProbe#fetch}:
     * PROBE HELLO → raw LSTD, ≤2×1.5 s, on this connect thread, long before the build) and adopt
     * the door's published default ({@link DoorProbe.Result#defaultId}, offerable-filtered) as THE
     * pick — byte-for-byte what the kit would have sent. Server-owned value, wire-delivered live,
     * never baked into the kit; no schema bump, no new frame. Never throws: any failure keeps the
     * empty pick (the server still seeds its default at admit — only the t=0 hull differs).
     * Log rows are the grading seam: {@code predict variant pick: EMPTY -> …}.
     * <p>The probe is {@link DoorProbe#fetch13} (the password sealed, no version-12 datagram) at every
     * {@code handshakeVersion}: the parameter stays on the signature (the drop is parked)
     * and picks nothing.
     */
    private static DoorProbe.Result probeServerDefaultPick(String host, int port,
            String password, String clientId, String fingerprint, int handshakeVersion) {
        DoorProbe.Result probe;
        try {
            probe = DoorProbe.fetch13(host, port, password, clientId, fingerprint);
        } catch (Throwable t) {
            PilotWatchLog.error("predict variant pick: EMPTY -> door probe threw (keeping the"
                    + " empty pick; the server seeds its default at admit)", t);
            return DoorProbe.Result.failed("probe threw: " + t);
        }
        if (probe.served()) {
            if (probe.defaultId != null) {
                // door-supplied text is wire text — scrub at the
                // log seam (log-injection class; the package sanitizer is the ONE guard).
                PilotWatchLog.info("predict variant pick: EMPTY -> probed the door's server"
                        + " default '" + FallbackAnnounce.sanitize(probe.defaultId, 64)
                        + "' (adopted: HELLO variantId + the"
                        + " mission's t=0 hull; " + probe.ids.size() + " pickable listed)");
            } else {
                PilotWatchLog.info("predict variant pick: EMPTY -> the door named no default ("
                        + probe.ids.size() + " pickable listed) — keeping the empty pick"
                        + " (descriptor row; the server seeds at admit)");
            }
        } else if (probe.rejectReason != null) {
            PilotWatchLog.info("predict variant pick: EMPTY -> the door REJECTED the probe: "
                    + FallbackAnnounce.sanitize(probe.rejectReason, 64) //
                    + " — keeping the empty pick (the HELLO will surface"
                    + " the same reason)");
        } else if (probe.localRefusal) { // Nothing was sent, and the dial refuses the same inputs
            PilotWatchLog.info("predict variant pick: EMPTY -> door probe NOT SENT by this build ("
                    + probe.error + ") — keeping the empty pick (the dial refuses the same inputs)");
        } else {
            PilotWatchLog.info("predict variant pick: EMPTY -> door probe got no decisive reply ("
                    + probe.error + ") — re-probing before the next connect attempt");
        }
        return probe;
    }

    /**
     * Seat-name handshake (client half): announce the OWN seat's locally-picked name in
     * one {@code SNAM} frame, gated on {@code cc.predict.eventReplay} — the lane's ONE knob (both
     * JVMs run it {@code true} for every 1v1 WATCH; legacy knob-off lanes send NOTHING here and
     * stay byte-identical). Own-name source order: the seat-ordinal-neutral
     * {@code cc.match.playerName} knob first — a seat-3+ gauntlet joiner has no
     * {@code seat<N>Name} — then the legacy {@code cc.match.seat<N>Name} for wire ids 1/2 (the
     * SAME value the joiner's {@code join-cc.bat} prompt / the host's {@code -Seat1Name} write;
     * {@code join-cc.bat} now emits BOTH knobs from the one prompt), each read literally per the
     * scan idiom. Unset/blank sends the no-pick SENTINEL frame
     * (nameLen 0) rather than nothing: the server's bounded pre-loop {@code awaitSeatName} read
     * must never stall waiting for a name-less client. A name the wire contract rejects (e.g.
     * over the 48-byte UTF-8 cap — stricter than the combat plugin's char-count parse) also falls
     * soft to the sentinel, loudly: the server then serves its config fallback instead of this
     * seat losing the whole session. An {@link IOException} propagates to the caller's retry loop
     * (the socket is already dead).
     */
    private static void announceSeatName(PredictSession session, long shipId)
            throws IOException {
        // default-ON twin of PredictReconcileCombatPlugin.EVENT_REPLAY_ENABLED — the two
        // sites must agree (same key, same "true" default, explicit =false still wins).
        if ("false".equals(System.getProperty("cc.predict.eventReplay", "true"))) {
            return; // knob-off lanes: zero new bytes on the wire (the gating lean)
        }
        String raw = System.getProperty("cc.match.playerName");
        String src = "cc.match.playerName";
        if (raw == null || raw.trim().isEmpty()) {
            raw = shipId == 1L ? System.getProperty("cc.match.seat1Name")
                    : shipId == 2L ? System.getProperty("cc.match.seat2Name") : null;
            src = "cc.match.seat" + shipId + "Name";
        }
        String name = (raw == null || raw.trim().isEmpty()) ? null : raw.trim();
        try {
            session.sendSeatName(name);
            PilotWatchLog.info("predict seat-name announce: SNAM sent shipId=" + shipId
                    + (name == null ? " name=<none> (no local pick -> sentinel; server config fallback)"
                            : " name='" + name + "' (" + src + ")"));
        } catch (RuntimeException invalid) {
            PilotWatchLog.error("predict seat-name announce: name REJECTED by the wire contract"
                    + " -> announcing the no-pick sentinel instead (server config fallback)", invalid);
            session.sendSeatName(null);
        }
    }

    /**
     * Lane posture (client half of the echo lane): send the ONE {@code PSTE}
     * posture echo and log the reported digests where the operator reads attach truth. The resolve
     * happens inside {@link PredictSession#sendPostureEcho} (the bundled shared census); this
     * wrapper only narrates — rows/unknown/hash logging rides the sidelog dump at PSTB receipt
     * (the block carries the allow-listed tag), so attach logs just the send fact. An
     * {@link IOException} propagates to the caller's retry loop (the socket is already dead);
     * a RuntimeException (registry resolve failure — would be a build-time census bug, the
     * suite pins it) also propagates: a client that cannot state its posture must not attach
     * quietly.
     */
    private static void sendPostureEcho(PredictSession session, long shipId) throws IOException {
        session.sendPostureEcho();
        PilotWatchLog.info("predict posture echo: PSTE sent shipId=" + shipId
                + " (live census digests; server verdicts vs compiled baseline)");
    }
}
