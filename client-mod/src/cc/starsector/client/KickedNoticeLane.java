package cc.starsector.client;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.mission.FleetSide;

/**
 * The kick victim's presentation of the server's HOUS KICKED notice
 * ({@code ClientReservedControlReader.HOUSE_ROLE_KICKED_NOTICE}): before this lane a kick
 * was WIRE-SILENT by design (silence is the death contract), so a kicked player saw a
 * "looks like a crash" stale window until the read deadline. The notice flips that into
 * deliberate-removal UX the moment it lands: red HUD banner ({@code addMessage}, the
 * {@code PredictFailBannerCombatPlugin} idiom) + own-ship floaty + the native end-of-combat
 * as a Defeat ({@code endCombat}, the {@code MatchOverLane} drive idiom) so the player
 * reaches Results → Continue instead of a dead round.
 *
 * <p>Unlike {@code MatchOverLane} this lane does NO destroyed-roster injection — a kick is a
 * session verdict, not a battle verdict, so the drive stays pure public API (b-deny clean)
 * and short-circuits straight to the native defeat flow.
 *
 * <p>No free text rides the wire: the body carries a reason CODE and the prose below is a
 * client-side switch (the {@code describeRefusal} idiom). Drive-once + dead-latch shape =
 * {@code MatchOverLane}; presentation only — a failure never breaks the frame.
 */
final class KickedNoticeLane {

    private static final float END_DELAY_SECONDS = 1f;
    private static final int KICKED_REASON_ADMIN_KICK = 1; // mirrors the server constant
    private static final int KICKED_REASON_OPERATOR_KICK = 2; // mirrors the server constant
    /** wq P25 rider P25-c: the door released this one-way pending seat to
     *  another joiner — a session verdict, not an operator's; the banner prefix stays neutral. */
    private static final int KICKED_REASON_PENDING_EVICTED = 3; // mirrors the server constant
    /** wq P27: the operator BANNED this client — a person's verb, and
     *  TERMINAL: no code-4 row ever says "rejoin". */
    static final int KICKED_REASON_BANNED = 4; // mirrors the server constant

    /** The notice has been presented + the native end driven — poll-idempotent. */
    private boolean driven;
    /** Presentation threw — lane retired for the session, never re-attempted. */
    private boolean dead;

    /** Per active frame: {@code reasonCode} = the session's latched notice (-1 until mailed). */
    void advance(int reasonCode) {
        if (dead || driven || reasonCode < 0) {
            return;
        }
        try {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) {
                return;
            }
            CombatUIAPI ui = engine.getCombatUI();
            if (ui != null) {
                ui.addMessage(0, Color.RED, bannerPrefix(reasonCode), Color.ORANGE,
                        describeKick(reasonCode));
            }
            ShipAPI own = engine.getPlayerShip();
            if (own != null && own.getLocation() != null) {
                engine.addFloatingTextAlways(own.getLocation(),
                        // P27: a ban floats its own word.
                        reasonCode == KICKED_REASON_BANNED ? "BANNED"
                                : isOperatorKick(reasonCode) ? "KICKED" : "REMOVED",
                        60f, Color.RED, own, 0f, 0f, 1f, 1.5f, 0f, 1f);
            }
            engine.endCombat(END_DELAY_SECONDS, FleetSide.ENEMY);
            driven = true;
            PilotWatchLog.info("cc-kicked-notice drive reason=" + reasonCode);
        } catch (Throwable t) {
            dead = true; // presentation only — never break the frame
            PilotWatchLog.info("cc-kicked-notice DEAD: " + t);
        }
    }

    boolean hasDriven() {
        return driven;
    }

    /** Reasons 1–2 and 4 (4 = ban) are a person's verb; every other code (3 = seat
     *  released, unknown) is the server's own session law — P25-c: the banner never blames an
     *  operator for those. Code 4 joined the operator set. */
    static boolean isOperatorKick(int reasonCode) {
        return reasonCode == KICKED_REASON_ADMIN_KICK || reasonCode == KICKED_REASON_OPERATOR_KICK
                || reasonCode == KICKED_REASON_BANNED;
    }

    /** The HUD banner's red head (P25-c: neutral unless an operator acted).
     *  A ban gets its OWN head, never the KICKED one. */
    static String bannerPrefix(int reasonCode) {
        if (reasonCode == KICKED_REASON_BANNED) {
            return "BANNED FROM THIS SERVER: ";
        }
        return isOperatorKick(reasonCode) ? "KICKED BY SERVER OPERATOR: " : "REMOVED BY THE SERVER: ";
    }

    /** Reason-code prose (client-side switch — the wire carries only the code). */
    static String describeKick(int reasonCode) {
        switch (reasonCode) {
            case KICKED_REASON_ADMIN_KICK:
                return "an admin removed you from this round - you may rejoin.";
            case KICKED_REASON_OPERATOR_KICK:
                return "the server operator removed you from this round - you may rejoin.";
            case KICKED_REASON_PENDING_EVICTED:
                return "your seat was released to another joiner because this client never began"
                        + " exchanging with the server - press Play to rejoin.";
            // The P27 ban — terminal, never "rejoin".
            case KICKED_REASON_BANNED:
                return "the server operator banned this client - you cannot rejoin this server.";
            default:
                return "you were removed from this round - you may rejoin.";
        }
    }

    /**
     * P25-c: the mission-brief presenter of a latched kicked reason (the W13 WAITING-row shape,
     * {@code PredictDialLane.briefingRows}: ≤ 3 rows, ≤ 60 chars each). A pending-squat
     * victim (reason 3) is BY DEFINITION still in the brief — it never began exchanging — so
     * this, not the HUD banner, is the row it actually reads before Play.
     */
    static String[] briefingLines(int reasonCode, String where) {
        switch (reasonCode) {
            case KICKED_REASON_PENDING_EVICTED:
                return new String[] {
                        "AlphaCoreMP: SEAT RELEASED at " + where,
                        "another joiner took it - no exchange had begun",
                        "Press Play to rejoin" };
            case KICKED_REASON_ADMIN_KICK:
                return new String[] {
                        "AlphaCoreMP: KICKED by an admin at " + where,
                        "Play re-joins if the host allows it" };
            case KICKED_REASON_OPERATOR_KICK:
                return new String[] {
                        "AlphaCoreMP: KICKED by the host at " + where,
                        "Play re-joins if the host allows it" };
            // The P27 ban rows — no Play/rejoin offer.
            case KICKED_REASON_BANNED:
                return new String[] {
                        "AlphaCoreMP: BANNED at " + where,
                        "the operator banned this client",
                        "you cannot rejoin this server" };
            default:
                return new String[] {
                        "AlphaCoreMP: REMOVED by the server at " + where,
                        "Play re-joins if the host allows it" };
        }
    }
}
