package cc.starsector.client;

import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;

/**
 * The claim-at-play placeholder flagship is a REAL
 * armed side-0 hull with NO server body until {@code PredictReconcileCombatPlugin.claimOwnSeatAtPose}
 * lands: on die → ESC → Play inside {@code respawnPolicy=cooldown:5} the server remounts the seat
 * only once {@code MatchSeatAuthority.allowsRemountAt} passes, so the player flew a local ghost
 * for up to 5 s (the whole round under {@code respawn=disabled}), shooting replicas the server
 * never saw hit and taking replayed fire on a hull that did not exist.
 *
 * <p>Held inert EVERY pending frame ({@link #hold}, ahead of every advance early-return like the
 * replay-driven hold re-assert): {@code FIRE} + {@code USE_SYSTEM} blocked (the engine drops the
 * queued commands in {@code Ship.advance} and clears the block set per frame — the
 * ship-system idiom, player input included), the one-frame hold-fire
 * gate (autofire groups; the replay-driven idiom) and {@link CollisionClass#NONE} (passthrough —
 * the puppet plugin's own-ship posture; no local hit lands on or leaves it). Movement stays the
 * player's: the claim snaps the hull to the wire pose either way. The engine shuttle anchor (a
 * build that fielded nothing) is skipped — no weapons, never in getShips. The collision class is
 * given back by {@link #release} at the settle (adopt-in-place keeps the body; the field branch
 * removes it; the round reset sweeps it — a removed body takes the write harmlessly). Fail-soft:
 * presentation only, never breaks the frame. Own file by the PRCP new-lane law; proxy-driven by
 * {@code JoinPipelineLawSmoke} L5.
 */
final class ClaimPlaceholderHold {

    /** The body currently held passthrough (null = none). */
    private ShipAPI held;
    /** {@link #held}'s collision class BEFORE the hold — what {@link #release} restores. */
    private CollisionClass priorClass;
    private boolean failLogged;

    /**
     * One frame of the hold over {@code own} while {@code claimPending}; a no-op otherwise.
     * Returns true the frame the hold ATTACHES to a body (the log gate) — later frames re-assert
     * the one-frame flags only.
     */
    boolean hold(boolean claimPending, ShipAPI own) {
        if (!claimPending || own == null) {
            return false;
        }
        try {
            if (own.isShuttlePod()) {
                return false;
            }
            own.blockCommandForOneFrame(ShipCommand.FIRE);
            own.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
            own.setHoldFireOneFrame(true);
            if (held == own) {
                return false;
            }
            held = own;
            priorClass = own.getCollisionClass();
            own.setCollisionClass(CollisionClass.NONE);
            PilotWatchLog.info("predict claim: placeholder flagship HELD inert while the seat claim is"
                    + " pending (fire + system blocked per frame, hold-fire, collision " + priorClass
                    + " -> NONE) variant="
                    + (own.getVariant() != null ? own.getVariant().getHullVariantId() : "?")
                    + " (ghost window)");
            return true;
        } catch (Throwable t) {
            if (!failLogged) {
                failLogged = true;
                PilotWatchLog.error("predict claim: placeholder hold threw (the ghost may act locally"
                        + " until the claim lands; one-shot log)", t);
            }
            return false;
        }
    }

    /**
     * Give the held body its pre-hold collision class back; a no-op with nothing held.
     * {@code settled} = the body the claim kept (log only; null = a round reset swept it).
     */
    void release(ShipAPI settled) {
        ShipAPI body = held;
        if (body == null) {
            return;
        }
        held = null;
        CollisionClass prior = priorClass != null ? priorClass : CollisionClass.SHIP;
        priorClass = null;
        try {
            body.setCollisionClass(prior);
        } catch (Throwable ignore) {
            // presentation only
        }
        PilotWatchLog.info("predict claim: placeholder hold RELEASED (collision -> " + prior + ") on the "
                + (settled == null ? "swept body (round reset)"
                        : body == settled ? "adopted-in-place seat hull" : "stale placeholder (removed)"));
    }

    /** The body under hold, or null. */
    ShipAPI held() {
        return held;
    }
}
