package cc.starsector.client;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import cc.starsector.client.net.PredictSession;

/**
 * Loud-fail banner: a combat window whose predict
 * connect GAVE UP must never look like a served match. Before this banner the failure mode was a
 * real-looking LOCAL VANILLA round — descriptor-authored map, native victory, zero server
 * authority — which cost a tester two empty rounds in a stale-sidecar incident before
 * anyone knew the seats never connected.
 *
 * <p>Registered beside {@link PredictReconcileCombatPlugin} in the {@code cc_predict} mission
 * (both descriptor and fallback bodies) and keyed on TWO process-terminal flags ONLY — never
 * {@code predictSession() == null}, which also holds mid-connect (the menu-time retry daemon may
 * still land the session) and on ordinary non-predict launches:
 * {@link CcClientModPlugin#predictConnectGaveUp()} (the connect never landed — this round is
 * LOCAL VANILLA, or never started when it was held for the dial) and
 * {@link CcClientModPlugin#missionSessionRefused()} (a
 * re-pick found the app-scoped session defunct after the served session ended — relaunch
 * re-joins). Each keeps its own HUD line (the giveup text is byte-identical to the original); the
 * floaty is shared. Once either fires, every subsequent round in this app run carries the banner.
 * A third, NON-terminal key rides: {@link
 * CcClientModPlugin#autoSeatStaleBuild()} (the mission was built before a late WELCOME assigned
 * the AUTO seat — re-entering the mission recovers, and a clean bind clears the flag).
 * The dialing / claiming keys rode earlier; a VPN-flap fix
 * added LATE_SETTLED — on a lane that does not hold the round (below), the Play dial LANDED (or died) after the
 * round had already started local: the
 * old {@code dialing} key needed the dial in flight, so the moment the background connect
 * succeeded the banner went SILENT on a still-local round (the player read the vanished
 * "STILL CONNECTING" as "connected"). Key precedence
 * lives in {@link #keyFor} (pure, selftest-pinned) and the text in {@link #hudLine}.
 *
 * <p>The claim-at-play lane HOLDS a round that opens with the Play dial still in flight
 * ({@link CcClientModPlugin#lateAttachHold()}): the own ship stays parked and inert and the WELCOME binds into THIS
 * round when it lands, so nothing local is ever played. The keys are the same (the bind clears
 * {@code roundStartedWithoutSession}, so DIALING clears; a give-up reads GAVE_UP), but every text tells the hold's
 * truth: DIALING reads the waiting line (the kit's own attempt counter, plus the refusal being retried once the door
 * answered one), each fail-closed key reads "this round never started" instead of LOCAL VANILLA, the floaty reads
 * CONNECTING TO SERVER / SERVER CONNECT FAILED, and the ARMED log names the hold. The hold has no "late":
 * LATE_SETTLED is the non-holding lanes' key, and under the hold it is at most the frame between a landed dial and
 * its bind (or a dial that ended with neither a session nor a give-up).
 *
 * <p>Presentation = both native channels, re-posted on a timer so neither can quietly fade out:
 * the vanilla HUD message feed ({@code getCombatUI().addMessage}, the ChatLane leg-4 idiom —
 * strictly alternating Color/String segments, 15 s fade / 20 s removal, so re-post every 12 s)
 * and a red {@code addFloatingTextAlways} at the own ship (the MODE-label idiom:
 * settings/viewport-proof variant, engine-default trail args; re-post every 2 s ≈ its dwell).
 * Paused frames deliver {@code advance(0)} so the timers freeze with the clock — no stacking.
 *
 * <p>Knob {@code cc.predict.connectFailBanner} = {@code on|off}, DEFAULT ON; invalid THROWS at
 * class load ({@code DroneStrikeFxLane} contract). {@code off} = the original silent
 * LOCAL-VANILLA presentation byte-for-byte (the A/B lever). Render-thread only; fail-soft with a
 * capped error log — the uncapped giveup line in {@link CcClientModPlugin} stays the durable
 * record. Zero PRCP touch (margin-1 mandate): this class is its own collaborator.
 */
public class PredictFailBannerCombatPlugin extends BaseEveryFrameCombatPlugin {

    static final boolean ON = parseOnOff(System.getProperty("cc.predict.connectFailBanner", "on"));

    /** HUD feed re-post period (s): under the widget's 15 s fade so the line never disappears. */
    private static final float HUD_REPOST_SECONDS = 12f;
    /** Floaty re-post period (s): ≈ the engine-default dwell the MODE-label idiom rides. */
    private static final float FLOATY_REPOST_SECONDS = 2f;
    private static final int ERROR_LOG_MAX = 5;
    /** W13 leg 2c: the seat claim normally lands within the first snapshot (LAN ≪ 1 s); the
     *  "claiming seat" row appears only past this, re-posted every {@link #CLAIM_REPOST_SECONDS}. */
    private static final long CLAIM_NOTICE_AFTER_MILLIS = 2000L;
    private static final float CLAIM_REPOST_SECONDS = 5f;

    private CombatEngineAPI engine;
    /** Countdown accumulators; primed to fire on the first post-giveup UI-bearing frame. */
    private float hudCountdown;
    private float floatyCountdown;
    /** The key the last non-silent frame showed; a change re-posts + logs at once. */
    private Key lastKey;
    private int errors;

    /**
     * Which banner a frame shows, ranked: the process-terminal keys first, then the two
     * round-local W13 keys, then the informational claim notice. {@code null} = no banner.
     * Public for the {@code ChatInputLawSelftest} banner-keys rows only.
     */
    public enum Key { GAVE_UP, REFUSED, STALE_SEAT, DIALING, LATE_SETTLED, CLAIMING }

    /**
     * The one precedence table (giveUp wins when both somehow hold — a refused
     * session must first have connected; the stale-seat refusal is NOT
     * process-terminal so it ranks after the two terminal keys; W13 leg 2: a round that began
     * with no session is DIALING while the Play dial is still in flight and LATE_SETTLED once
     * that dial has landed or died — on a lane that does not hold, either way the round stays LOCAL until a re-Play,
     * so the banner must never go silent between the two; a HELD round's bind clears
     * {@code roundStartedWithoutSession}, so its DIALING clears at the bind and its give-up reads GAVE_UP; the claim
     * notice ranks last). Pure; the hold changes the TEXT ({@link #hudLine} {@code holdAttach}), never the key.
     * <p>{@code dialAttempted} gates LATE_SETTLED. A
     * serverless vanilla:/compare window starts its round session-less but NEVER dials, so since
     * LATE_SETTLED landed it painted a false CONNECT SETTLED LATE banner + red floaty; with no dial ever run the
     * session-less branch falls through to the claim notice (or silence) instead. The caller
     * passes {@code CcClientModPlugin.predictDialAttempt() > 0} (reset to 0 per dial plan).
     */
    public static Key keyFor(boolean gaveUp, boolean refused, boolean staleSeat,
            boolean roundStartedWithoutSession, boolean dialInFlight, boolean claimPendingLong,
            boolean dialAttempted) {
        if (gaveUp) {
            return Key.GAVE_UP;
        }
        if (refused) {
            return Key.REFUSED;
        }
        if (staleSeat) {
            return Key.STALE_SEAT;
        }
        if (roundStartedWithoutSession) {
            if (dialInFlight) {
                return Key.DIALING;
            }
            // LATE only when a dial actually ran this plan.
            return dialAttempted ? Key.LATE_SETTLED : (claimPendingLong ? Key.CLAIMING : null);
        }
        return claimPendingLong ? Key.CLAIMING : null;
    }

    /** The ARMED log's cause clause per key (the durable record beside the HUD line). */
    static String armedCause(Key key) {
        switch (key) {
            case GAVE_UP: return "connect gave up";
            case REFUSED: return "defunct-session re-pick refused (41)";
            case STALE_SEAT: return "auto-seat stale build refused";
            case DIALING: return "round started while the Play dial was in flight (W13 leg 2c)";
            case LATE_SETTLED: return "the Play dial settled AFTER the round started local";
            default: return "seat claim pending";
        }
    }

    /** {@link #armedCause(Key)} on a held round: the hold's own causes for the keys it reaches, never a LOCAL round.
     * */
    static String armedCause(Key key, boolean holdAttach) {
        if (holdAttach) {
            switch (key) {
                case DIALING: return "round held for the dial";
                case GAVE_UP: return "the dial gave up in the hold";
                case LATE_SETTLED: return "the dial settled in the hold (a live session binds next frame)";
                default: break;
            }
        }
        return armedCause(key);
    }

    /** The ARMED log row (one per key change): {@code holdAttach} = false is the original row byte-for-byte; a held
     *  round is never called LOCAL VANILLA. */
    static String armedLine(Key key, boolean holdAttach) {
        return "predict loud-fail banner ARMED key=" + key + ": " + armedCause(key, holdAttach)
                + (holdAttach ? " -> the round is held, own ship parked (no server authority)"
                        : " -> this round is LOCAL VANILLA (no server authority)")
                + "; banner repeats every " + HUD_REPOST_SECONDS + "s until teardown + re-arm";
    }

    /** The clause every held fail-closed line carries in place of "this round is LOCAL VANILLA". */
    private static final String NEVER_STARTED = "this round never started";

    /** "N attempt(s)": the give-up names the attempts actually made (a connect thread that died stops short of the
     *  budget). VALUE-DOMAIN: 0..n. */
    private static String attemptsMade(int n) {
        return n + (n == 1 ? " attempt" : " attempts");
    }

    /**
     * The held round's lines ({@link #hudLine} with {@code holdAttach}): the same titles where the event is the
     * same, "this round never started" where the original says LOCAL VANILLA, the recovery = ESC, then select
     * Multiplayer Test and press Play again. {@code refusal} = GAVE_UP's terminal reason, or DIALING's last refusal the
     * dial is retrying through (null = none answered yet). Null = the key reads as on any lane (the claim notice).
     *
     *
     */
    private static String[] heldLine(Key key, String refusal, boolean lateSessionLive, int dialAttempt,
            int dialAttempts, boolean localRefusal, boolean refusalFromReelDoor) {
        switch (key) {
            case GAVE_UP:
                if (isMatchRefusal(refusal)) {
                    return new String[] {"AlphaCoreMP CANNOT JOIN THIS MATCH: ",
                            describeRefusal(refusal, refusalFromReelDoor) + " - " + NEVER_STARTED + ","
                            + " nothing here is served or scored."};
                }
                if (refusal != null) {
                    return new String[] {"AlphaCoreMP SERVER REFUSED THE JOIN: ",
                            describeRefusal(refusal, refusalFromReelDoor) + " - " + NEVER_STARTED + ","
                            + " nothing here is served or scored. Leave with ESC"
                            + afterRefusalHint(refusal, ", fix that"
                            + " (relaunch through the joiner if the kit or password changed),"
                            + " then select Multiplayer Test and press Play again.")};
                }
                if (localRefusal) {
                    return new String[] {"AlphaCoreMP JOIN NOT SENT: ",
                            "this build cannot form its join request (see the log) - " + NEVER_STARTED + ","
                            + " nothing here is served or scored. Leave with ESC and tell the host."};
                }
                return new String[] {"AlphaCoreMP SERVER CONNECT FAILED: ",
                        "no answer after " + attemptsMade(dialAttempt) + " - " + NEVER_STARTED + ". Leave with ESC,"
                        + " confirm the server is up, then select Multiplayer Test and press Play again."};
            case REFUSED:
                return new String[] {"AlphaCoreMP SESSION DEAD: ",
                        "this app run's served session has ended - " + NEVER_STARTED + ", nothing here is served"
                        + " or scored. Leave with ESC, then select Multiplayer Test and press Play again."};
            case STALE_SEAT:
                return new String[] {"AlphaCoreMP SEAT ASSIGNED LATE: ",
                        "the server assigned your ship after this round was built - " + NEVER_STARTED + "."
                        + " Leave with ESC and re-start the mission to fly your assigned seat."};
            case DIALING: {
                // the counters are the kit's own (attempt N of the plan's M), never peer bytes; a refusal the door
                // answered is named, so "the moment the server answers" is never told over an answer
                String progress = "attempt " + Math.max(1, dialAttempt) + " of " + dialAttempts;
                return new String[] {"AlphaCoreMP WAITING FOR THE SERVER: ", refusal == null
                        ? "connecting, " + progress + " - your ship stays parked and joins this round the moment"
                                + " the server answers. ESC leaves."
                        : describeRefusal(refusal, refusalFromReelDoor) + " - still retrying (" + progress + ") -"
                                + " your ship stays parked and joins this round the moment the server lets it in."
                                + " ESC leaves."};
            }
            case LATE_SETTLED:
                // a live session = the dial landed and the combat plugin binds it next frame; none = the dial ended
                // with neither a session nor a give-up
                return lateSessionLive
                        ? new String[] {"AlphaCoreMP CONNECTED: ",
                                "joining this round - your ship stays parked until the server fields it."}
                        : new String[] {"AlphaCoreMP SERVER CONNECT FAILED: ",
                                "the connect attempt ended without joining - " + NEVER_STARTED + ". Leave with ESC,"
                                + " then select Multiplayer Test and press Play again."};
            default:
                return null;
        }
    }

    /**
     * The floaty at the own ship, or null for none. Off the hold: the original "NOT CONNECTED - LOCAL VANILLA". On it:
     * CONNECTING TO SERVER while the key is DIALING, nothing while a landed dial's bind is a frame away, else SERVER
     * CONNECT FAILED — keyed on the KEY, never the in-flight flag (a give-up outranks that flag's last true frame, so
     * "connecting" never floats under CONNECT FAILED).
     */
    static String floatyText(Key key, boolean holdAttach, boolean lateSessionLive) {
        if (!holdAttach) {
            return "NOT CONNECTED - LOCAL VANILLA";
        }
        if (key == Key.DIALING) {
            return "CONNECTING TO SERVER";
        }
        return key == Key.LATE_SETTLED && lateSessionLive ? null : "SERVER CONNECT FAILED";
    }

    /**
     * The HUD line for a key as {title, body} — pure so the selftest pins every wording
     * (public-audience text: each says what happened AND the exact recovery a stranger
     * needs, always "ESC, select Multiplayer Test again, Play" for the round-local keys —
     * the mission text re-renders only on a list RE-SELECT, never on Play). This 7-argument form = no local refusal
     * (byte-identical to the 8-argument form with {@code false}).
     */
    public static String[] hudLine(Key key, String refusal, boolean lateSessionLive,
            int dialAttempt, int dialAttempts, long claimSeat, long claimSeconds) {
        return hudLine(key, refusal, lateSessionLive, dialAttempt, dialAttempts, claimSeat, claimSeconds, false);
    }

    /**
     * {@link #hudLine(Key, String, boolean, int, int, long, long)} plus {@code localRefusal} =
     * {@code CcClientModPlugin.predictConnectLocalRefusal()}: a {@code GAVE_UP} with no door reason whose dial never
     * sent (this build could not form its join request) names that, never "confirm the server is up".
     *
     *
     */
    public static String[] hudLine(Key key, String refusal, boolean lateSessionLive,
            int dialAttempt, int dialAttempts, long claimSeat, long claimSeconds, boolean localRefusal) {
        return hudLine(key, refusal, lateSessionLive, dialAttempt, dialAttempts, claimSeat, claimSeconds, localRefusal,
                false);
    }

    /**
     * The 8-argument form plus {@code refusalFromReelDoor} = the dial plan's {@code reelDoor}: the GAVE_UP refusal
     * came from the kit's own in-process reel door, so its kit-only token may read the reel sentence
     * ({@link #describeRefusal(String, boolean)}). The 8-argument form passes {@code false} (fail safe: the generic
     * sentence, never a kit blame).
     *
     *
     */
    public static String[] hudLine(Key key, String refusal, boolean lateSessionLive, int dialAttempt,
            int dialAttempts, long claimSeat, long claimSeconds, boolean localRefusal, boolean refusalFromReelDoor) {
        return hudLine(key, refusal, lateSessionLive, dialAttempt, dialAttempts, claimSeat, claimSeconds, localRefusal,
                refusalFromReelDoor, false);
    }

    /**
     * The 9-argument form plus {@code holdAttach} = {@link CcClientModPlugin#lateAttachHold()}: this round was held for
     * the Play dial (own ship parked and inert), so no line says LOCAL ({@link #heldLine}). Under the hold
     * {@code refusal} also feeds DIALING: the last refusal the dial is retrying through
     * ({@link CcClientModPlugin#predictDialLastRefusal()}), read with the same door fact. {@code false} = the
     * 9-argument lines byte-for-byte (every shorter form passes it).
     *
     *
     */
    public static String[] hudLine(Key key, String refusal, boolean lateSessionLive, int dialAttempt,
            int dialAttempts, long claimSeat, long claimSeconds, boolean localRefusal, boolean refusalFromReelDoor,
            boolean holdAttach) {
        if (holdAttach) {
            String[] held = heldLine(key, refusal, lateSessionLive, dialAttempt, dialAttempts, localRefusal,
                    refusalFromReelDoor);
            if (held != null) {
                return held;
            }
        }
        switch (key) {
            case GAVE_UP:
                if (isMatchRefusal(refusal)) {
                    // this window's match file, not the door, stopped the join -
                    // the sentence carries its own fix (close the game), so no "select Multiplayer Test again" tail.
                    return new String[] {"AlphaCoreMP CANNOT JOIN THIS MATCH: ",
                            describeRefusal(refusal, refusalFromReelDoor) + " - this round is LOCAL VANILLA,"
                            + " nothing here is served or scored."};
                }
                if (refusal != null) {
                    // The door DID answer — say why, so a
                    // public tester fixes the cause instead of "confirming the server is up".
                    // The dial is per-click now — leave + re-select re-dials;
                    // a kit/credential/content refusal still needs the joiner (new kit or
                    // password) before that re-select can succeed.
                    return new String[] {"AlphaCoreMP SERVER REFUSED THE JOIN: ",
                            describeRefusal(refusal, refusalFromReelDoor) + " - this round is LOCAL VANILLA,"
                            + " nothing here is served or scored. Leave with ESC"
                            + afterRefusalHint(refusal, ", fix that"
                            + " (relaunch through the joiner if the kit or password changed),"
                            + " then select Multiplayer Test again.")};
                }
                if (localRefusal) {
                    return new String[] {"AlphaCoreMP JOIN NOT SENT: ",
                            "this build cannot form its join request (see the log) - this round is LOCAL VANILLA,"
                            + " nothing here is served or scored. Leave with ESC and tell the host."};
                }
                return new String[] {"AlphaCoreMP SERVER CONNECT FAILED: ",
                        "this round is LOCAL VANILLA - nothing here is served or scored."
                        + " Leave with ESC, confirm the server is up, then select"
                        + " Multiplayer Test again to reconnect."};
            case REFUSED:
                return new String[] {"AlphaCoreMP SESSION DEAD: ",
                        "this app run's served session has ended - this round is LOCAL"
                        + " VANILLA, nothing here is served or scored. Leave with ESC"
                        + " and select Multiplayer Test again to reconnect."};
            case STALE_SEAT:
                return new String[] {"AlphaCoreMP SEAT ASSIGNED LATE: ",
                        "the server assigned your ship after this round was built -"
                        + " this round is LOCAL VANILLA. Leave with ESC and re-start"
                        + " the mission to fly your assigned seat."};
            case DIALING: // a round that started local on a lane that does not hold (a held round: heldLine)
                return new String[] {"AlphaCoreMP STILL CONNECTING: ",
                        "attempt " + Math.max(1, dialAttempt) + " of " + dialAttempts
                        + " - this round started LOCAL (nothing here is served or scored)."
                        + " Leave with ESC, select Multiplayer Test again, then press Play"
                        + " once the mission text reads CONNECTED."};
            case LATE_SETTLED:
                // The dial landed (or died) after the round started — the
                // session, if live, is kept open for the re-Play to claim; on a lane that does not hold, nothing
                // binds it to THIS round (a started local round never re-attaches, W13 leg 2c). A held round
                // binds it instead (heldLine).
                return lateSessionLive
                        ? new String[] {"AlphaCoreMP CONNECTED LATE - THIS ROUND IS LOCAL: ",
                                "the server connected AFTER this round had started, so nothing"
                                + " here is served or scored. Leave with ESC, select Multiplayer"
                                + " Test again (the mission text then reads CONNECTED), then"
                                + " press Play to fly your seat."}
                        : new String[] {"AlphaCoreMP CONNECT SETTLED LATE - THIS ROUND IS LOCAL: ",
                                "the connect attempt ended AFTER this round had started, so"
                                + " nothing here is served or scored. Leave with ESC, select"
                                + " Multiplayer Test again (it re-dials), then press Play once"
                                + " the mission text reads CONNECTED."};
            default:
                return new String[] {"AlphaCoreMP: ",
                        "claiming seat " + claimSeat + "... (" + claimSeconds + " s) - your ship"
                        + " appears when the server fields it."};
        }
    }

    private static Color titleColor(Key key) {
        switch (key) {
            case DIALING: case LATE_SETTLED: return Color.ORANGE;
            case CLAIMING: return Color.YELLOW;
            default: return Color.RED;
        }
    }

    private static Color bodyColor(Key key) {
        switch (key) {
            case DIALING: case LATE_SETTLED: return Color.YELLOW;
            case CLAIMING: return Color.WHITE;
            default: return Color.ORANGE;
        }
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!ON || engine == null) {
            return;
        }
        long claimSince = CcClientModPlugin.claimPendingSinceMillis();
        Key key = keyFor(CcClientModPlugin.predictConnectGaveUp(), CcClientModPlugin.missionSessionRefused(),
                CcClientModPlugin.autoSeatStaleBuild(), CcClientModPlugin.roundStartedWithoutSession(),
                CcClientModPlugin.predictDialInFlight(),
                claimSince != 0L && System.currentTimeMillis() - claimSince >= CLAIM_NOTICE_AFTER_MILLIS,
                CcClientModPlugin.predictDialAttempt() > 0); // a never-dialed window is not LATE
        if (key == null) {
            lastKey = null;
            return;
        }
        boolean claiming = key == Key.CLAIMING;
        // the claim-at-play hold (own ship parked, the round never started), read once per banner frame so the ARMED
        // row, the HUD line and the floaty agree: no text says LOCAL over a held ship
        boolean hold = CcClientModPlugin.lateAttachHold();
        try {
            if (key != lastKey && !claiming) {
                // One line per key CHANGE (a round sees at most a handful): the durable record of
                // WHICH banner the player saw, e.g. DIALING -> LATE_SETTLED when the Play dial
                // lands mid-round (that transition used to go SILENT).
                lastKey = key;
                PilotWatchLog.info(armedLine(key, hold));
                hudCountdown = 0f; // a key change re-posts at once, never waits out the old timer
            }
            hudCountdown -= amount;
            floatyCountdown -= amount;
            if (hudCountdown <= 0f) {
                CombatUIAPI ui = engine.getCombatUI();
                if (ui != null) {
                    // GAVE_UP names the terminal refusal; the held DIALING line names the one being retried
                    String refusal = key == Key.GAVE_UP ? CcClientModPlugin.predictConnectRefusedReason()
                            : hold && key == Key.DIALING ? CcClientModPlugin.predictDialLastRefusal() : null;
                    PredictSession late = key == Key.LATE_SETTLED ? CcClientModPlugin.predictSession() : null;
                    PredictDialPlan plan = PredictDialLane.predictDialPlan; // the stored plan: its reelDoor = every dial's door
                    String[] line = hudLine(key, refusal, late != null && !late.isDefunct(),
                            CcClientModPlugin.predictDialAttempt(), CcClientModPlugin.predictDialAttempts(),
                            CcClientModPlugin.claimSeat(), (System.currentTimeMillis() - claimSince) / 1000L,
                            key == Key.GAVE_UP && CcClientModPlugin.predictConnectLocalRefusal(),
                            bannerReelDoor(plan), hold); // The plan's door fact, never the token's
                    ui.addMessage(0, titleColor(key), line[0], bodyColor(key), line[1]);
                    hudCountdown = claiming ? CLAIM_REPOST_SECONDS : HUD_REPOST_SECONDS;
                }
            }
            if (floatyCountdown <= 0f && !claiming) {
                PredictSession late = hold && key == Key.LATE_SETTLED ? CcClientModPlugin.predictSession() : null;
                String floaty = floatyText(key, hold, late != null && !late.isDefunct());
                ShipAPI own = engine.getPlayerShip();
                if (floaty != null && own != null && own.getLocation() != null) {
                    engine.addFloatingTextAlways(own.getLocation(), floaty, 60f,
                            hold && key == Key.DIALING ? Color.YELLOW : Color.RED, own, 0f, 0f, 1f, 1.5f, 0f, 1f);
                    floatyCountdown = FLOATY_REPOST_SECONDS;
                }
            }
        } catch (Throwable t) {
            errors++;
            if (errors <= ERROR_LOG_MAX) {
                PilotWatchLog.error("predict loud-fail banner: frame FAILED (banner retries;"
                        + " errors=" + errors + "): " + t, t);
            }
        }
    }

    /** A {@code banned} refusal carries NO retry
     *  prompt — every retry is one terminal HELLO the door refuses; only the operator lifts a ban
     *  (A14: never "you may rejoin"). Any other refusal keeps {@code otherwise} (the caller's
     *  retry / re-select tail, which starts with its own separator). */
    public static String afterRefusalHint(String refusal, String otherwise) {
        return "banned".equals(refusal) ? ". " + BANNED_HINT : otherwise;
    }

    public static final String BANNED_HINT = "Ask the server operator - a banned client is refused at the door.";

    /**
     * CLIENT-ONLY give-up token, never sent on the wire: the in-game click probe saw the door's match document
     * frames, which the door sends only to a match term it does not hold - the host changed the match after this
     * window launched.
     *
     *
     */
    public static final String MATCH_STALE_AFTER_LAUNCH = "match_stale_after_launch";

    /** The {@code MatchDocumentLaws.REASON_*} family prefix: a latched {@code "<reason> <field>"} from the mission
     *  builder's refusal of this window's match file. */
    static final String MATCH_DOC_PREFIX = "match_doc_";

    /** A match-document refusal ({@code match_doc_*}, with or without its field) or {@link #MATCH_STALE_AFTER_LAUNCH}. */
    static boolean isMatchRefusal(String reason) {
        return reason != null && (reason.startsWith(MATCH_DOC_PREFIX) || MATCH_STALE_AFTER_LAUNCH.equals(reason));
    }

    /**
     * The match-document sentences, or {@code null} for any other token. {@code reason} = the latched
     * {@code "<reason> <field>"} (the mission builder's refusal token; the field is OUR label, never file text):
     * {@code match_doc_variant_missing <n>} names the missing-ship count, every other {@code match_doc_*} quotes
     * reason + field, {@link #MATCH_STALE_AFTER_LAUNCH} the after-launch change. Pure, so rows pin every wording.
     *
     *
     */
    static String describeMatchRefusal(String reason) {
        if (!isMatchRefusal(reason)) {
            return null;
        }
        if (MATCH_STALE_AFTER_LAUNCH.equals(reason)) {
            return "The host changed the match after you launched. Close the game and press R in the join console";
        }
        int space = reason.indexOf(' ');
        String token = space < 0 ? reason : reason.substring(0, space);
        if (cc.starsector.server.match.MatchDocumentLaws.REASON_VARIANT_MISSING.equals(token)) {
            return "The host's match uses a ship your game lacks (" + (space < 0 ? "?" : reason.substring(space + 1))
                    + " missing) - close the game; the join window's mods line names what to install, else tell the"
                    + " host";
        }
        return "Your match file is unusable (" + reason + ") - close the game and run join-cc again: the window"
                + " fetches a fresh copy";
    }

    /**
     * The door's machine reason → one tester-legible clause. The token set is
     * the server's WELCOME-REJECTED reason table (scattered literals: {@code
     * PilotIntentDatagramServer} admission + {@code SeatAdmission} + the kit_outdated bounce
     * ladder) — an unmapped token falls through as {@code server said '<token>'}, so drift
     * costs legibility, never correctness. {@code reason} is already scrubbed at the store.
     * This ONE-argument form = a token from a door that is NOT the kit's reel door
     * ({@code describeRefusal(reason, false)}): a caller that does not know its door fails SAFE — the generic sentence,
     * never a kit blame.
     */
    public static String describeRefusal(String reason) {
        return describeRefusal(reason, false);
    }

    /**
     * {@link #describeRefusal(String)} with the door fact: {@code fromReelDoor} = the dial plan's {@code reelDoor}
     * (PASSED from the plan, never inferred from the token or the host text). WHY: {@code reel_no_secret_only} is
     * KIT-minted ({@code ReelLoopbackDoor.REJECT_NO_SECRET_ONLY}) but arrives as free, unauthenticated door text — from
     * any other door (a remote or forged REJECT) it takes the generic {@code server said '<token>'} arm. Every other
     * token reads the same under both values. Public for the KB4 rows ({@code ChatInputLawSelftest}).
     *
     *
     */
    public static String describeRefusal(String reason, boolean fromReelDoor) {
        String matchText = describeMatchRefusal(reason);
        if (matchText != null) {
            return matchText;
        }
        switch (reason) {
            case "kit_outdated":
                return "your joiner kit is OUTDATED (the server runs a newer build) - get the"
                        + " current kit";
            case "match_password_required":
                return "this server needs a match password and none was sent";
            case "match_password_mismatch":
                return "WRONG match password";
            case "session_table_full":
                return "the server is FULL";
            case "match_full":
                // W13: every PILOT seat is taken — NOT terminal,
                // the click-started dial keeps retrying until one frees (the queue leg
                // replaces that polling with an ordered wait-list).
                return "the match is FULL (every pilot seat is taken)";
            case "team_full":
                // The picked team's seats are all taken while the other team
                // still has room (both full is match_full). The door seats such a pick on the other team and says so
                // in chat, so this token is not expected from a current door; an older door's token is NOT terminal
                // (isTerminalRefusal's default) - the dial retries it like any refusal, so the sentence names no route.
                return "That team is full.";
            case "seat_already_occupied":
                return "your seat is already taken";
            case "seat_unknown":
                // PasswordSeatLedger mints it for a seat number the
                // password door does not have — the ONE door token this table lacked.
                return "the server has no such seat - check the seat number with the host";
            case "seat_token_required":
            case "seat_token_unknown":
            case "client_id_required":
                return "your join credentials were not accepted - rejoin through the joiner ("
                        + reason + ")";
            case "content_fingerprint_required":
            case "content_fingerprint_mismatch":
                return "CONTENT MISMATCH - your kit or mods differ from the server's: close the game and run join-cc"
                        + " again; its window says what to fix";
            // Observer posture: the three CLIENT-minted refusals
            // (PredictReconcileDatagramSession.connect's bit gate) + the door's own three.
            case "observer_not_granted":
                return "this server did not grant OBSERVER admission (older server build?)";
            case "observer_unrequested":
                return "the server answered with an OBSERVER seat this window never asked for ("
                        + reason + ")";
            case "observer_bound_nonzero":
                // Review lens: minted only under observer=true — the window DID ask; the
                // fault is a bound ship on a seat-less admit (protocol break), never "unrequested".
                return "the server bound a ship to this window's OBSERVER seat - protocol break ("
                        + reason + ")";
            case "observer_password_mode_only":
                return "OBSERVER mode needs a match-password server (this one runs seat tokens)";
            case "observer_full":
                return "the server's OBSERVER slots are all taken";
            case "observer_cid_in_use":
                return "this client id already pilots a live seat - relaunch the observer window";
            // The P27 ban door token (DoorRejectReasons.BANNED).
            case "banned":
                return "you are BANNED from this server";
            // THE FLIP stage Y: DoorRejectReasons.HANDSHAKE_REFUSED - the password was right, the
            // sealed join request was not lawful. A shipped kit never authors one; a mis-built kit can.
            case "handshake_refused":
                return "the server could not read this kit's join request - re-extract the newest kit zip and retry";
            // The match document rides the join: the host re-applied the match after this
            // window fetched it, so the session claim's term is stale; R in the join console fetches the current one.
            case "match_stale":
                return "THE HOST CHANGED THE MATCH - close the game, then press R in the join console: it fetches"
                        + " the new match";
            // CLIENT-minted (PredictDatagramDial) - WELCOME bound another ship.
            case "bound_mismatch":
                return "this window asked for a different ship than the server bound to this window's"
                        + " credential - relaunch through the joiner (" + reason + ")";
            // KIT-minted (ReelLoopbackDoor.REJECT_NO_SECRET_ONLY), never a server token;
            // the kit's sentence ONLY from the kit's own door, else the generic arm below.
            case "reel_no_secret_only":
                if (fromReelDoor) {
                    return "the local replay door refused this join: it carried a match password (kit fault - a"
                            + " replay needs none)";
                }
                break;
            // DoorRejectReasons.SOURCE_SEAT_CAP - the host's seats-per-address allowance is
            // used up by this player's address. NOT terminal (isTerminalRefusal's default): a seat of theirs freeing
            // admits the retry, so the dial keeps going.
            case "source_seat_cap":
                return "this server's seats-per-address limit is reached for your address - wait for one of your"
                        + " seats to free, or ask the host";
            default:
                break;
        }
        return "server said '" + reason + "'";
    }

    /**
     * Is this door refusal TERMINAL for the running game
     * process — one where retrying the HELLO can never succeed, so the connect loop stops at the
     * first decisive reply instead of burning its full attempt budget (≈ 12.5 min bare)? Terminal = a kit/credential/
     * content class (a different kit, password, token or mod build is the only fix, all of which
     * mean a relaunch through the joiner). NOT terminal = a seat/table class that CAN clear while
     * the player waits at the menu ({@code seat_already_occupied} while the old seat's grace
     * drains, {@code session_table_full}/{@code match_full} when someone leaves) — and every
     * unknown token, which keeps the pre-rider retry behaviour (fail-open on table drift). {@code team_full} is
     * retrying too: the door seats a full team pick on the other team, so the token is not expected from a
     * handshake-14 door; an older door's token retries within the attempt budget like any refusal.
     * Same
     * token vocabulary as {@link #describeRefusal(String, boolean)}; keep the two switches side by side.
     * Public for the selftest rows ({@code ChatInputLawSelftest}) only. This ONE-argument form = a token from
     * a door that is NOT the kit's reel door ({@code isTerminalRefusal(reason, false)}): a caller that does not know
     * its door fails SAFE — keeps retrying, never a kit-token stop.
     */
    public static boolean isTerminalRefusal(String reason) {
        return isTerminalRefusal(reason, false);
    }

    /** The banner's door fact ({@link #advance}): the stored dial plan's {@code reelDoor}; a null plan (an ordinary
     *  launch) = false. Hoisted so a row pins it (JoinPipelineLawSmoke KB4h).
     * */
    static boolean bannerReelDoor(PredictDialPlan plan) {
        return plan != null && plan.reelDoor;
    }

    /**
     * {@link #isTerminalRefusal(String)} with the door fact: {@code fromReelDoor} = the dial plan's {@code reelDoor},
     * passed by the dial loop ({@code PredictDialLane.connectPredictSession}). WHY: the kit-only token
     * {@code reel_no_secret_only} is terminal ONLY from the kit's own door — from any other door a remote or forged
     * REJECT would end the dial loop on attempt 1. Every other token is the same under both values.
     *
     *
     */
    public static boolean isTerminalRefusal(String reason, boolean fromReelDoor) {
        if (reason == null) {
            return false;
        }
        if (isMatchRefusal(reason)) {
            return true; // a match file this game cannot build, or one the host replaced: no re-dial fixes either
        }
        switch (reason) {
            case "kit_outdated":
            case "match_password_required":
            case "match_password_mismatch":
            case "seat_token_required":
            case "seat_token_unknown":
            case "client_id_required":
            case "content_fingerprint_required":
            case "content_fingerprint_mismatch":
            // An observer bit the door will never grant (older server / a
            // token-mode server) or a bit we never asked for — re-HELLOing changes nothing.
            // observer_full / observer_cid_in_use stay NON-terminal (a slot or the live pilot
            // session can clear while the window waits at the menu).
            case "observer_not_granted":
            case "observer_unrequested":
            case "observer_bound_nonzero":
            case "observer_password_mode_only":
            // A ban never clears while the window waits —
            // terminal on the FIRST dial.
            case "banned":
            // A re-HELLO with the same knobs binds the same other seat again.
            case "bound_mismatch":
            // A stale match term re-HELLOs to the same refusal until the match file is refreshed.
            case "match_stale":
                return true;
            // KIT-minted (ReelLoopbackDoor.REJECT_NO_SECRET_ONLY) — the
            // in-process reel door refuses a seal that carries a typed secret; every re-dial would
            // seal the same secret again and earn the same refusal. Terminal only from that door.
            case "reel_no_secret_only":
                return fromReelDoor;
            default:
                return false;
        }
    }

    private static boolean parseOnOff(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException("cc.predict.connectFailBanner must be on|off; got '"
                + raw + "'");
    }
}
