package cc.starsector.client;

/**
 * The late-attach decisions, pure (no engine, no statics)
 * so the game-free selftest pins every row. A cc_predict window whose claim wait expires with the
 * claim-at-play dial still in flight HOLDS — the placeholder parked + held inert, the served statics
 * taken — and binds the WELCOME into THIS round when it lands, instead of running the round
 * local-vanilla while the session it waited for is only claimed by the next Play.
 *
 * <p>Bounds: the hold has no timer of its own. Its limit is the dial's own give-up
 * ({@link PredictDialLane#PREDICT_CONNECT_ATTEMPTS} attempts, the banner counts them); the window's
 * only other exit is the combat plugin's stale-advance watchdog
 * ({@code PredictReconcileCombatPlugin.ADVANCE_STALE_TEARDOWN_MILLIS}), and ESC leaves at any time.
 *
 * <p>Ordering law: the dial's connect thread publishes a session and sets its give-up flag BEFORE its
 * finally clears the in-flight flag. So give-up outranks in-flight (both read TRUE in that window),
 * and the per-frame caller samples give-up + in-flight BEFORE the session: a publish landing between
 * the reads then reads BIND or WAIT, never STOP with a live session.
 */
final class LateAttach {

    /** What init does with a window that found no session after the claim wait. */
    enum Decision { BIND, HOLD, LOCAL }

    /** One frame of the hold's poll. */
    enum Step { BIND, WAIT, STOP }

    private LateAttach() {
    }

    /**
     * Init's choice: a session binds; a legacy lane ({@code !claimDial}: observer, 1v1 click dial,
     * vanilla compare) keeps today's local round; a claim dial that gave up is local (give-up outranks
     * in-flight); a claim dial still in flight HOLDS; anything else is local.
     */
    static Decision decide(boolean claimDial, boolean sessionPresent, boolean dialInFlight, boolean gaveUp) {
        if (sessionPresent) {
            return Decision.BIND;
        }
        if (!claimDial || gaveUp) {
            return Decision.LOCAL;
        }
        return dialInFlight ? Decision.HOLD : Decision.LOCAL;
    }

    /**
     * The hold's per-frame step: a session binds (it outranks a still-set in-flight flag — the real
     * publish order); a give-up stops; a dial in flight waits; a dial that ended with neither (a
     * defunct session reads absent) stops — an ended dial is never polled again.
     */
    static Step step(boolean sessionPresent, boolean dialInFlight, boolean gaveUp) {
        if (sessionPresent) {
            return Step.BIND;
        }
        if (gaveUp) {
            return Step.STOP;
        }
        return dialInFlight ? Step.WAIT : Step.STOP;
    }

    /**
     * The bind gate, decided under the class lock the hold watchdog tears down under: only the ACTIVE
     * window (a torn-down one reads {@code active} null, a superseded one another window), still
     * unbound and still polling, may bind — a window the watchdog restored must never re-take a
     * session with its statics already given back.
     */
    static boolean holdPollMayBind(boolean activeIsThis, boolean sessionNull, boolean polling) {
        return activeIsThis && sessionNull && polling;
    }
}
