package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatFleetManagerAPI;

/**
 * The "Incoming vessels" feed-spam suppressor.
 * On spawning in, the upper-left combat
 * feed was "UTTERLY spammed" with ship-incoming messages — every one of them OUR mirror-spawn
 * calls landing in the message-eligible fleet manager, dominated by growth-driven Merlon-drone
 * births.
 *
 * <p><b>The seam is vanilla's own</b>: the single emitter is
 * {@code CombatFleetManager.deploy()} and its OUTER gate
 * is the API flag {@code isSuppressDeploymentMessages} — byte-verified: the flag's only
 * behavioral read is that gate; every spawn verb we use ({@code spawnShipOrWing} all overloads →
 * explicit-loc {@code spawnFleetMember} → {@code deploy(spec)})
 * funnels through it synchronously, with no per-hull/fighter exemption (drone births DO emit),
 * and suppression wins even over the devMode owner-bypass. Vanilla precedent for the
 * save → set-true → spawn → restore shape: {@code ShardSpawner} (vanilla restores
 * sequentially; we restore in {@code finally}, strictly safer, identical on the happy path).
 *
 * <p><b>Knob</b>: {@code cc.predict.deployFeedMute} = {@code on | off | window:<seconds>},
 * DEFAULT {@code on}. The request: <i>"a setting to suppress ship-incoming messages
 * (toggleable and with settings for when to do it, like for the first x seconds of the match or
 * whatever you think is good)"</i> — shape delegated. Default is whole-match {@code on} rather
 * than a window because the dominant source (drone births) continues ALL match, and side-1 wave
 * arrivals never emit in vanilla anyway (its fleet-owner gate) — a window default would let
 * the defect return at the first mid-match drone launch. {@code window:N} mutes only the first
 * N seconds of each round (the window re-arms on match reset — every round has its own spawn
 * burst); {@code off} = the behavior before this gate, the A/B lever
 * ({@code JAVA_TOOL_OPTIONS=-Dcc.predict.deployFeedMute=off}). Catalog-only special — no
 * launcher plumbing; invalid values THROW at class load (the {@code knobs.md} discipline).
 *
 * <p><b>Scope</b>: OUR six mirror-spawn sites only (respawnOwn, the two match-reset rebuild
 * shapes, and the spawn-on-sight registry pair) — the flag is saved and restored per call, so
 * anything vanilla emits outside our calls stays vanilla forever. Fail-OPEN per site: a mute
 * that throws spawns unmuted (worst case = today's message), and the restore failure counter is
 * the watch trap. Render-thread only, like every sibling collaborator.
 */
final class DeployFeedGate {

    private static final int MODE_ON = 0;
    private static final int MODE_OFF = 1;
    private static final int MODE_WINDOW = 2;

    /** Raw knob text, kept verbatim for the meter echo. */
    private static final String DEPLOY_FEED_MUTE_RAW =
            System.getProperty("cc.predict.deployFeedMute", "on");
    private static final int MODE;
    private static final float WINDOW_SECONDS;
    static {
        String value = DEPLOY_FEED_MUTE_RAW.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            MODE = MODE_ON;
            WINDOW_SECONDS = 0f;
        } else if ("off".equals(value)) {
            MODE = MODE_OFF;
            WINDOW_SECONDS = 0f;
        } else if (value.startsWith("window:")) {
            float seconds = Float.parseFloat(value.substring("window:".length()));
            if (!(seconds > 0f)) {
                throw new IllegalArgumentException(
                        "cc.predict.deployFeedMute window seconds must be > 0, got: "
                                + DEPLOY_FEED_MUTE_RAW);
            }
            MODE = MODE_WINDOW;
            WINDOW_SECONDS = seconds;
        } else {
            throw new IllegalArgumentException(
                    "cc.predict.deployFeedMute must be on|off|window:<seconds>, got: "
                            + DEPLOY_FEED_MUTE_RAW);
        }
    }

    /** The owning plugin — the sibling-collaborator backref ({@link SpawnHullmodGate} idiom). */
    @SuppressWarnings("unused")
    private final PredictReconcileCombatPlugin plugin;

    private float roundElapsed;    // seconds since round start (window mode's clock)
    private int mutedSpawns;       // spawn calls that ran with our suppression armed
    private int passedSpawns;      // spawn calls left vanilla (knob off / window expired)
    private int restoreFailures;   // restores that threw — the flag may be stuck suppressed
    private int gateThrows;        // mute-side throws (failed OPEN: spawned unmuted)
    private boolean firstMuteLogged;
    private boolean firstPassLogged;

    DeployFeedGate(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Per active (unpaused) frame — the window clock. */
    void advance(float amount) {
        roundElapsed += amount;
    }

    /** Match reset: every round has its own spawn burst, so the window re-arms. */
    void resetRound() {
        roundElapsed = 0f;
    }

    private boolean active() {
        return MODE == MODE_ON || (MODE == MODE_WINDOW && roundElapsed < WINDOW_SECONDS);
    }

    /**
     * Arm suppression on {@code mgr} for one of our spawn calls. Returns the restore token (the
     * manager's PRIOR flag state, the ShardSpawner save) — {@code null} when inactive or on a
     * throw, which makes {@link #unmute} a no-op and the spawn behave exactly as without this gate.
     */
    Boolean mute(CombatFleetManagerAPI mgr) {
        if (!active() || mgr == null) {
            passedSpawns++;
            if (!firstPassLogged && MODE != MODE_OFF) {
                firstPassLogged = true;
                PilotWatchLog.info("predict deploy-feed-mute: window expired ("
                        + WINDOW_SECONDS + "s) -> spawns emit vanilla feed lines from here");
            }
            return null;
        }
        try {
            boolean prev = mgr.isSuppressDeploymentMessages();
            mgr.setSuppressDeploymentMessages(true);
            mutedSpawns++;
            if (!firstMuteLogged) {
                firstMuteLogged = true;
                PilotWatchLog.info("predict deploy-feed-mute: ACTIVE mode=" + describeMode()
                        + " (first muted spawn; counters on the eventReplay-meter line)");
            }
            return Boolean.valueOf(prev);
        } catch (Throwable t) {
            gateThrows++;
            // fail OPEN — the spawn proceeds unmuted; worst case is today's feed line
            return null;
        }
    }

    /** Restore the manager's saved flag state in the caller's {@code finally}. */
    void unmute(CombatFleetManagerAPI mgr, Boolean saved) {
        if (saved == null || mgr == null) {
            return;
        }
        try {
            mgr.setSuppressDeploymentMessages(saved.booleanValue());
        } catch (Throwable t) {
            restoreFailures++;
            PilotWatchLog.error("predict deploy-feed-mute: restore FAILED -- the manager may be"
                    + " stuck suppressing vanilla deployment messages", t);
        }
    }

    private static String describeMode() {
        return MODE == MODE_ON ? "on" : MODE == MODE_OFF ? "off" : "window:" + WINDOW_SECONDS;
    }

    /**
     * The gate's fragment of the {@code predict B3 eventReplay-meter} line, appended beside
     * {@link SpawnHullmodGate#meterTokens} by {@link LocalFireCensus}. Key names are PINNED — the
     * grader parses them by name. The bar for a served-station watch: {@code _muted_spawns>=1}
     * with {@code _restore_failures=0} and {@code _gate_throws=0} (default {@code on} mode;
     * {@code _passed_spawns} stays 0 there — nonzero means the knob was not at default).
     */
    String meterTokens() {
        return " predict_event_rebase_deploy_feed_mute=" + describeMode()
                + " predict_event_rebase_feed_muted_spawns=" + mutedSpawns
                + " predict_event_rebase_feed_passed_spawns=" + passedSpawns
                + " predict_event_rebase_feed_restore_failures=" + restoreFailures
                + " predict_event_rebase_feed_gate_throws=" + gateThrows;
    }
}
