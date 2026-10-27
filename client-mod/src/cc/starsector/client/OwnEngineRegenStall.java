package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

//
/**
 * The fix for the "engine flicker" after a Rift Lightning / EMP hit — the OWN-hull,
 * WIRE-SCOPED engine regen stall: the engine analog of {@link OwnWeaponRegenStall}.
 *
 * <p><b>The race.</b> The wire's per-engine disabled bit is a stateless per-tick sample. The own
 * engine's native health tracker (repair accrual
 * {@code dt /= getCombatEngineRepairTimeMult()}, HP += dt*rate after the 5 s post-damage hold)
 * re-enables a wire-disabled engine in a few seconds while the server's copy is still disabled;
 * the next snapshot disagrees and {@code FoeStateLaneCoordinator.applyEngineState} re-{@code disable()}s
 * it — the flame-out FX/sound re-fires on every edge = the flicker.
 *
 * <p><b>Shape.</b> Installed only while the fresh snapshot marks ≥1 own engine wire-disabled,
 * released ({@code unmodify}) on the first all-clear snapshot. Keyed stat mod, identity-tracked
 * (a respawned own hull starts clean), fail-soft. Why the earlier stall's engine exclusion no longer binds:
 * that stall held a LOCAL (never-on-wire) burnout over the controller's 0.5 flameout threshold
 * while the lane's ramp guard was dead; the ramp deferral is
 * now correct and the wire-driven {@code repair()} force-sets HP=max stat-blind, so
 * a local-only disable is still cleared on the next settled snapshot.
 */
final class OwnEngineRegenStall {

    static final String STAT_ID = "cc_own_engine_regen_stall";
    /** Same magnitude + never-0 law as the weapon stall (÷0 ⇒ +Inf ⇒ instant full heal). */
    static final float STALL_MULT = 1e6f;

    private ShipAPI stalledHull;
    private int installs;
    private int releases;
    private int throwsCount;

    /** Per fresh own snapshot: {@code anyWireDisabled} = ≥1 own engine wire-disabled. Idempotent. */
    void maintain(ShipAPI own, boolean anyWireDisabled, long simTick) {
        if (own == null) {
            return;
        }
        try {
            if (anyWireDisabled) {
                if (stalledHull == own) {
                    return;
                }
                own.getMutableStats().getCombatEngineRepairTimeMult().modifyMult(STAT_ID, STALL_MULT);
                stalledHull = own;
                installs++;
                PilotWatchLog.info("predict regen own-engine-regen-stall INSTALL @tick=" + simTick
                        + " (wire-disabled own engine; released on the first all-clear snapshot)");
            } else if (stalledHull != null) {
                release(own, simTick);
            }
        } catch (Throwable t) {
            throwsCount++;
            stalledHull = null; // native behavior stands; the next snapshot retries
        }
    }

    void release(ShipAPI own, long simTick) {
        ShipAPI hull = stalledHull;
        stalledHull = null;
        if (hull == null) {
            return;
        }
        try {
            hull.getMutableStats().getCombatEngineRepairTimeMult().unmodify(STAT_ID);
            releases++;
            PilotWatchLog.info("predict regen own-engine-regen-stall RELEASE @tick=" + simTick);
        } catch (Throwable t) {
            throwsCount++;
        }
    }

    boolean active() {
        return stalledHull != null;
    }

    String meterTokens() {
        return " predict_own_engine_regen_stall_installs=" + installs
                + " predict_own_engine_regen_stall_releases=" + releases
                + " predict_own_engine_regen_stall_throws=" + throwsCount;
    }
}
