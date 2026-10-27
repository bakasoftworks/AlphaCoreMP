package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * client-side per-frame KEEP-ALIVE for wire-family hulk pieces: the engine
 * FADES a hulk ~30 s continuously off-camera (
 * {@code sinceVisible > 30} → {@code hulkFader.fadeOut()} 0.5 s → {@code isExpired()} =
 * brightness ≤ 0 → CombatEngine removal) — a SINGLEPLAYER-camera optimization that removes
 * pieces the authoritative server owns forever (headless never fades). One faded piece desyncs
 * the family membership and drops every 2 Hz {@code HKPS} keyframe for the survivors — the
 * family-churn defect. The lane's law: family membership — and now
 * EXISTENCE — forks ONLY on wire edges.
 *
 * <p>{@code keepAlive} pins the fade's private clock ({@code sinceVisible = 0} every sweep;
 * race-free, the fade never STARTS with a 30 s margin) and pins the piece bright
 * ({@code Fader.forceIn()} + {@code setAlphaMult(1)} — the fade writes alphaMult from
 * brightness, so both need restoring). For the station-wreck ghost
 * flicker: the pin is UNCONDITIONAL per sweep, knob
 * {@code cc.predict.hulkPin} — the old brightness&lt;1 gate skipped the alphaMult restore
 * whenever brightness was already healed/full while alphaMult sat stale-low (a persistent
 * invisible-but-colliding ghost that flickers when anything touches alpha), and no legit hulk
 * alpha effect exists to protect (every writer checked: the only other alpha writer is RecallFxLane on
 * LIVE departing fighters, a different channel). {@code forceIn()} is idempotent at
 * brightness 1/IDLE. {@code off} = the older reactive heal
 * byte-for-byte (the A/B lever). NO {@code expired} write (by design: the
 * map-border hulk cull stays VANILLA both sides — the server's own culler fans ordinary
 * REMOVE edges).
 *
 * <p>Both driving fields are PRIVATE with no public API seam at all ⇒
 * {@code java.lang.invoke} VarHandles via {@code MethodHandles.privateLookupIn} (the loader-B
 * legal lane — {@code java.lang.reflect} is B-denied; unnamed-module packages are open, so the
 * private lookup succeeds — proven game-free by {@code HulkKeepAliveSmoke}, the feasibility
 * gate). FAIL-SOFT: any init/access failure = adapter DISABLED + a one-shot
 * log = today's vanilla fade, never a throw into the maintain sweep.
 */
final class HulkKeepAliveAdapter {

    /** Piece pinned; no heal needed (the steady state). */
    static final int OUTCOME_KEPT = 0;
    /** Piece pinned AND a started fade was healed (forceIn + alphaMult restore). */
    static final int OUTCOME_HEALED = 1;
    /** Nothing pinned: adapter disabled (init/access failure) or the piece is not an engine
     * {@code Ship} — the caller's {@code keepalive_disabled} tripwire counter. */
    static final int OUTCOME_DISABLED = 2;

    /** Unconditional per-sweep brightness+alpha pin, compiled ON (E3
     * hardwire; the {@code cc.predict.hulkPin} on|off
     * knob-resolved shape — the older reactive heal as the A/B lever — is in git). */
    static final boolean PIN_UNCONDITIONAL = true;

    private static final java.lang.invoke.VarHandle SINCE_VISIBLE;
    private static final java.lang.invoke.VarHandle HULK_FADER;
    private static final Throwable INIT_FAILURE;

    static {
        java.lang.invoke.VarHandle sinceVisible = null;
        java.lang.invoke.VarHandle hulkFader = null;
        Throwable failure = null;
        try {
            java.lang.invoke.MethodHandles.Lookup lookup =
                    java.lang.invoke.MethodHandles.privateLookupIn(
                            com.fs.starfarer.combat.entities.Ship.class,
                            java.lang.invoke.MethodHandles.lookup());
            sinceVisible = lookup.findVarHandle(
                    com.fs.starfarer.combat.entities.Ship.class, "sinceVisible", float.class);
            hulkFader = lookup.findVarHandle(
                    com.fs.starfarer.combat.entities.Ship.class, "hulkFader",
                    com.fs.graphics.util.Fader.class);
        } catch (Throwable t) {
            failure = t;
            sinceVisible = null;
            hulkFader = null;
        }
        SINCE_VISIBLE = sinceVisible;
        HULK_FADER = hulkFader;
        INIT_FAILURE = failure;
    }

    /** Latched on the first runtime access failure — one clean fall to DISABLED, never
     * per-piece throw spam. Render-thread written; volatile for the smoke/debrief reads. */
    private static volatile Throwable runtimeFailure;
    private static volatile boolean statusLogged;

    private HulkKeepAliveAdapter() {
    }

    /** True while the pin lane is live (both VarHandles resolved, no access failure yet). */
    static boolean isEnabled() {
        return SINCE_VISIBLE != null && HULK_FADER != null && runtimeFailure == null;
    }

    /** The init or first runtime failure, for the smoke/one-shot log; null when healthy. */
    static Throwable failure() {
        return INIT_FAILURE != null ? INIT_FAILURE : runtimeFailure;
    }

    /** The resolved VarHandle shapes, for the smoke assert + the one-shot ENABLED line. */
    static String describe() {
        if (SINCE_VISIBLE == null || HULK_FADER == null) {
            return "unresolved";
        }
        return "sinceVisible:" + SINCE_VISIBLE.varType().getName()
                + " hulkFader:" + HULK_FADER.varType().getName();
    }

    /**
     * Pin one wire-family piece against the off-camera fade (call every maintain sweep;
     * idempotent). Returns an {@code OUTCOME_*}; never throws.
     */
    static int keepAlive(ShipAPI piece) {
        if (!statusLogged) {
            statusLogged = true;
            // The first-watch one-shot line: the feasibility gate's in-game verdict.
            PilotWatchLog.info("predict hulk-keepalive: " + (isEnabled()
                    ? "ENABLED " + describe()
                            + (PIN_UNCONDITIONAL ? " pin=unconditional" : " pin=reactive")
                    : "DISABLED (" + failureText() + ")"));
        }
        if (!isEnabled() || !(piece instanceof com.fs.starfarer.combat.entities.Ship)) {
            return OUTCOME_DISABLED;
        }
        com.fs.starfarer.combat.entities.Ship ship =
                (com.fs.starfarer.combat.entities.Ship) piece;
        try {
            SINCE_VISIBLE.set(ship, 0f);
            com.fs.graphics.util.Fader fader =
                    (com.fs.graphics.util.Fader) HULK_FADER.get(ship);
            if (fader == null) {
                return OUTCOME_KEPT;
            }
            // wasFading keeps the fade_heals meter's "was actually fading" meaning; the
            // pin itself no longer depends on it (off = the old reactive gate, the A/B lever).
            boolean wasFading = fader.getBrightness() < 1f;
            if (PIN_UNCONDITIONAL || wasFading) {
                fader.forceIn();
                piece.setAlphaMult(1f);
            }
            return wasFading ? OUTCOME_HEALED : OUTCOME_KEPT;
        } catch (Throwable t) {
            runtimeFailure = t;
            PilotWatchLog.info("predict hulk-keepalive: DISABLED mid-run ("
                    + failureText() + ")");
            return OUTCOME_DISABLED;
        }
    }

    private static String failureText() {
        Throwable cause = failure();
        return cause == null ? "no cause recorded"
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

}
