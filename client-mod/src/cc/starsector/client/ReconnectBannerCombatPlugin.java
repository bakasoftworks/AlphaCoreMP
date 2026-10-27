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
 * The mid-match "reconnecting (N s)" banner. The kit seat
 * sat 2.6 min on a dead lane (NAT lease remap: inbound froze, the re-HELLO + socket
 * recreate went unanswered) while the only on-screen tell was the small bottom-right net line
 * flipping to {@code ping reconnecting…}; the player read it as "disconnected for no apparent
 * reason". This lane makes a B4 re-HELLO episode ({@link PredictSession#reconnecting()}) as loud
 * as the connect-fail banner, with the elapsed seconds so the player can tell a blip from a dead
 * lease — and says what happens next (prediction free-runs; re-admission is automatic).
 *
 * <p>Registered beside {@link PredictFailBannerCombatPlugin} in the {@code cc_predict} mission
 * (both bodies) and keyed on the live session's {@link PredictSession#reconnectingElapsedMillis()}
 * ONLY ({@code -1} = live or no session): the connect-never-landed / defunct-session states stay
 * that plugin's, a kicked seat never arms the re-HELLO (its {@code kickedTerminal} latch drops the
 * flag), and a match-end BYE clears it — so the banner is exactly "the server stopped answering
 * mid-match". ZERO wire: it reads two volatiles the transport already keeps.
 *
 * <p>Deliberate rejoin: the DELIBERATE re-baseline arm (ESC mid-round
 * then Play on the kept-open takeover lane — {@code armReAdmission}) rides the same {@code reconnecting}
 * flag for the ~10 ms between BYE and the fresh WELCOME, and posted a LOST (0 s) + RECONNECTED (1 s)
 * pair on a rejoin the player asked for. Gate: while {@link PredictSession#reconnectingDeliberate()}
 * and the time since the arm ({@code elapsed} alone: a deliberate arm's
 * {@code silentBefore} is the player's own ESC stay, not an outage, and it defeated the window on
 * every stay > 3 s) is under {@link #DELIBERATE_QUIET_MILLIS}, nothing is presented — a real death
 * in that window (fallback arm: involuntary, presented as {@code silentBefore + elapsed}) shows at
 * once. A terminal refusal inside the window skips this class's red line;
 * {@link PredictFailBannerCombatPlugin}'s SESSION DEAD still posts.
 *
 * <p>Presentation = the {@link PredictFailBannerCombatPlugin} idiom: the vanilla HUD message feed
 * ({@code addMessage}, strictly alternating Color/String segments, 15 s fade — re-posted every 10 s
 * carrying the elapsed count at post time) + an orange {@code addFloatingTextAlways} at the own
 * ship every 2 s ≈ its dwell, which is the live counter. On re-admission ONE green feed line
 * reports the outage length. Elapsed rides the session's monotonic clock, so a paused frame
 * ({@code advance(0)}) freezes only the re-post timers, never the count.
 *
 * <p>Terminal-failure gate: the flag also drops WITHOUT a
 * re-admission — a kick ({@code kickedTerminal}), a {@code WELCOME_REJECTED} / session-id-0 /
 * schema / observer-mismatch refusal at re-admit, or the match-over BYE. The green line is
 * gated on {@link PredictSession#reAdmits()} having ADVANCED since the arm; otherwise ONE red
 * terminal line says the seat was never re-admitted (the sidelog word is {@code TERMINAL}, never
 * {@code CLEARED}, so a drop self-test counts only true re-admits).
 *
 * <p>Knob {@code cc.predict.reconnectBanner} = {@code on|off}, DEFAULT ON; invalid THROWS at class
 * load (the {@code DroneStrikeFxLane} contract). {@code off} = the older net-line-only
 * presentation byte-for-byte. Render-thread only; fail-soft with a capped error log. Zero PRCP
 * touch (the PRCP margin mandate): this class is its own collaborator.
 */
public class ReconnectBannerCombatPlugin extends BaseEveryFrameCombatPlugin {

    static final boolean ON = parseOnOff(System.getProperty("cc.predict.reconnectBanner", "on"));

    /** HUD feed re-post period (s): under the widget's 15 s fade so the line never disappears. */
    private static final float HUD_REPOST_SECONDS = 10f;
    /** Floaty re-post period (s): ≈ the engine-default dwell — this IS the live counter. */
    private static final float FLOATY_REPOST_SECONDS = 2f;
    private static final int ERROR_LOG_MAX = 5;
    /** felt-outage floor under which a DELIBERATE re-baseline episode stays silent (≥ the
     * fresh-nonce 4-send escalation window ~2.75 s; a rejoin that lands takes ~10 ms). */
    static final long DELIBERATE_QUIET_MILLIS = 3000L;

    private CombatEngineAPI engine;
    private float hudCountdown;
    private float floatyCountdown;
    /** Presented state: an episode is on screen; cleared (with the green line) at re-admission. */
    private boolean shown;
    private long lastElapsedMillis;
    /** {@link PredictSession#reAdmits()} at the arm — the clear is a re-admission only if it advanced. */
    private long reAdmitsAtArm;
    private int episodes;
    private int errors;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    /** The session this frame reads (a test seam: {@code JoinPipelineLawSmoke} overrides it). */
    PredictSession liveSession() {
        return CcClientModPlugin.predictSession();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!ON || engine == null) {
            return;
        }
        try {
            PredictSession session = liveSession();
            // Sample reAdmits BEFORE the episode
            // reads — the receive thread's adopt does `reAdmits++; reconnecting = false` between
            // this frame's volatile reads, and a post-adopt sample at the arm read "NOT RE-ADMITTED"
            // (RED) on a seat that had just landed. A pre-episode sample reads as re-admitted.
            long reAdmitsBefore = session == null ? 0L : session.reAdmits();
            // A read-order race: sample the deliberate
            // flag BEFORE elapsed — reconnectingDeliberate() is `reconnecting && reconnectDeliberate`,
            // so an adopt landing between the two reads turned a deliberate episode non-deliberate
            // and posted the LOST/RECONNECTED pair. Read first, an adopt in between makes elapsed
            // read -1 and the frame falls into the not-reconnecting branch below.
            boolean deliberate = session != null && session.reconnectingDeliberate();
            // silentBefore BEFORE elapsed too: an adopt between the two
            // reads made an involuntary episode's post + CLEARED count drop the felt silence
            // ("after 1 s" on a 57 s outage); both are reconnecting-gated, so a mid-read adopt
            // still lands in the not-reconnecting branch below.
            long silentBefore = session == null ? 0L : session.reconnectingSilentBeforeArmMillis();
            long elapsed = session == null ? -1L : session.reconnectingElapsedMillis();
            if (elapsed < 0L) {
                if (shown) {
                    shown = false;
                    long seconds = Math.max(1L, (lastElapsedMillis + 500L) / 1000L);
                    // A torn-down session (null) never re-admitted either.
                    boolean readmitted = session != null && session.reAdmits() > reAdmitsAtArm;
                    CombatUIAPI ui = engine.getCombatUI();
                    if (readmitted) {
                        PilotWatchLog.info("predict reconnect banner: CLEARED episode=" + episodes
                                + " after " + seconds + " s (re-admitted, reAdmits="
                                + session.reAdmits() + ")");
                        if (ui != null) {
                            ui.addMessage(0, Color.GREEN, "AlphaCoreMP RECONNECTED: ", Color.WHITE,
                                    "the server is answering again after " + seconds
                                    + " s - your ship snaps back to the served state.");
                        }
                    } else {
                        PilotWatchLog.info("predict reconnect banner: TERMINAL episode=" + episodes
                                + " after " + seconds + " s (re-HELLO ended WITHOUT a re-admit:"
                                + " rejected, kicked, session gone or match over)");
                        if (ui != null) {
                            ui.addMessage(0, Color.RED, "AlphaCoreMP NOT RE-ADMITTED: ", Color.ORANGE,
                                    "the server stopped answering " + seconds + " s ago and this"
                                    + " seat was NOT re-admitted (refused, kicked, or the match"
                                    + " ended) - nothing here is served now. Leave with ESC and"
                                    + " re-start the mission to re-dial.");
                        }
                    }
                }
                return;
            }
            // Present the outage the player FELT — the silence before the arm
            // (~60 s deadline) plus the time since it; the arm-relative count alone read
            // "after 1 s" on a 56 s kill leg.
            // A DELIBERATE arm's silentBefore is the player's own ESC stay
            // (the mission-exit BYE vacates the seat, nothing arrives in the menu) — not a felt
            // outage. It defeated the quiet window on every stay > 3 s ("reconnecting (22 s)" then
            // "RECONNECTED" on a rejoin that landed in 10 ms). A deliberate episode counts from
            // the arm; an involuntary one keeps the felt silence before it.
            lastElapsedMillis = deliberate ? elapsed : elapsed + silentBefore;
            if (!shown && deliberate && lastElapsedMillis < DELIBERATE_QUIET_MILLIS) {
                return; // A rejoin the player asked for — quiet unless it fails to land
            }
            long seconds = lastElapsedMillis / 1000L;
            if (!shown) {
                shown = true;
                episodes++;
                reAdmitsAtArm = reAdmitsBefore; // The pre-episode sample (see above)
                hudCountdown = 0f;
                floatyCountdown = 0f;
                PilotWatchLog.info("predict reconnect banner: ARMED episode=" + episodes
                        + " (session death mid-match -> re-HELLO loop live; banner repeats every "
                        + HUD_REPOST_SECONDS + " s with the elapsed count until re-admit)");
            }
            hudCountdown -= amount;
            floatyCountdown -= amount;
            if (hudCountdown <= 0f) {
                CombatUIAPI ui = engine.getCombatUI();
                if (ui != null) {
                    ui.addMessage(0, Color.RED, "AlphaCoreMP CONNECTION LOST: ", Color.ORANGE,
                            "reconnecting (" + seconds + " s) - the server stopped answering;"
                            + " ships free-run on local prediction until it does."
                            + " Re-admission is automatic; past ~60 s your seat may be freed"
                            + " and a mission re-entry re-dials.");
                    hudCountdown = HUD_REPOST_SECONDS;
                }
            }
            if (floatyCountdown <= 0f) {
                ShipAPI own = engine.getPlayerShip();
                if (own != null && own.getLocation() != null) {
                    engine.addFloatingTextAlways(own.getLocation(), "RECONNECTING " + seconds + " s",
                            60f, Color.ORANGE, own, 0f, 0f, 1f, 1.5f, 0f, 1f);
                    floatyCountdown = FLOATY_REPOST_SECONDS;
                }
            }
        } catch (Throwable t) {
            errors++;
            if (errors <= ERROR_LOG_MAX) {
                PilotWatchLog.error("predict reconnect banner: frame FAILED (banner retries;"
                        + " errors=" + errors + "): " + t, t);
            }
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
        throw new IllegalStateException("cc.predict.reconnectBanner must be on|off; got '"
                + raw + "'");
    }
}
