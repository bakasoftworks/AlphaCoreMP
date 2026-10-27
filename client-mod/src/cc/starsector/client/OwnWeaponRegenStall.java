package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The OWN-hull, WIRE-SCOPED weapon regen stall. The
 * replica faces already stall local self-repair ({@link ReplicaCompDamageShield}
 * {@code REGEN_STALL_MULT} on {@code getCombatWeaponRepairTimeMult}); the own hull deliberately
 * took NO stall (an ENGINE-face stall once held a burnout over the controller's 0.5
 * flameout threshold = 414 wire repairs). This lane is the bounded shape that note asked for:
 * <ul>
 * <li><b>WEAPON face only</b> — weapons have no flameout controller/threshold cascade; a stalled
 * weapon simply stays disabled until the wire's repair edge, and the lane's wire-driven
 * {@code repair()} force-sets HP=max reading no stat, so server repairs land
 * instantly regardless. The engine face is never touched.</li>
 * <li><b>Wire-scoped</b> — installed only while the fresh snapshot marks ≥1 own mount disabled,
 * released ({@code unmodify}) on the first snapshot with none (or a mount-less pose). Outside
 * that window the own hull keeps vanilla self-heal exactly as before.</li>
 * </ul>
 *
 * <p><b>Why it ends the own-ship popup storm.</b> With the stall off, the own engine's native
 * regen auto-repaired a wire-disabled mount in a few seconds while the server still had it
 * disabled; the next snapshot disagreed and the lane re-{@code disable()}d it — vanilla's
 * "Offline"/"Online" cue on every edge (D4b). With the stall on for exactly the wire's disabled
 * window, the local mount holds until the wire's own repair edge: one Offline at the hit, one
 * Online at the repair — vanilla's cadence. A locally-PREDICTED disable the server never had is
 * still repaired by the lane on the next snapshot (wire clear + local disabled ⇒ {@code repair()}),
 * so the stall cannot strand a mount.
 *
 * <p>Keyed stat mod ({@code STAT_ID}), never the no-arg wipe; identity-tracked so a respawned own
 * hull (new instance) starts clean. Fail-soft: a throwing install leaves native behavior.
 */
final class OwnWeaponRegenStall {

    /** Greppable stat-mod key; distinct from the replica shield's so neither wipes the other. */
    static final String STAT_ID = "cc_own_weapon_regen_stall";
    /** Same magnitude + same never-0 law as the replica shield (÷0 ⇒ +Inf ⇒ instant full heal). */
    static final float STALL_MULT = 1e6f;

    private ShipAPI stalledHull;
    private int installs;
    private int releases;
    private int throwsCount;

    /**
     * Per fresh own snapshot: {@code anyWireDisabled} = at least one carried own mount is
     * wire-disabled this snapshot. Idempotent both ways.
     */
    void maintain(ShipAPI own, boolean anyWireDisabled, long simTick) {
        if (own == null) {
            return;
        }
        try {
            if (anyWireDisabled) {
                if (stalledHull == own) {
                    return;
                }
                own.getMutableStats().getCombatWeaponRepairTimeMult().modifyMult(STAT_ID, STALL_MULT);
                stalledHull = own;
                installs++;
                PilotWatchLog.info("predict regen own-weapon-regen-stall INSTALL @tick=" + simTick
                        + " (wire-disabled own mount; released on the first all-clear snapshot)");
            } else if (stalledHull != null) {
                release(own, simTick);
            }
        } catch (Throwable t) {
            throwsCount++;
            stalledHull = null; // native behavior stands; the next snapshot retries
        }
    }

    /** Release on the hull that carries the stall (a different instance = the old one is gone). */
    void release(ShipAPI own, long simTick) {
        ShipAPI hull = stalledHull;
        stalledHull = null;
        if (hull == null) {
            return;
        }
        try {
            hull.getMutableStats().getCombatWeaponRepairTimeMult().unmodify(STAT_ID);
            releases++;
            PilotWatchLog.info("predict regen own-weapon-regen-stall RELEASE @tick=" + simTick);
        } catch (Throwable t) {
            throwsCount++;
        }
    }

    boolean active() {
        return stalledHull != null;
    }

    String meterTokens() {
        return " predict_own_weapon_regen_stall_installs=" + installs
                + " predict_own_weapon_regen_stall_releases=" + releases
                + " predict_own_weapon_regen_stall_throws=" + throwsCount;
    }
}
