package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * treated-replica wrapper over the Threat
 * {@link VoltaicDischargeOnFireEffect}. Non-replica sources ride {@code super} verbatim;
 * {@code advance} is inherited on both branches (its {@code setForceDisabled} by fragment count
 * is vanilla's own mount law — the weapon-state lane skips force-disabled mounts, D4b(3)).
 *
 * <p><b>Why (the ONE-presenter law; the client half of the
 * arc-hit contract):</b> vanilla's {@code onFire}
 * picks a swarm fragment and arcs fragment→target with the DAMAGING
 * {@code spawnEmpArc} to a target picked BY REFERENCE (vanilla's {@code findTarget}). The OLD
 * replica branch re-ran that pick locally and drew to the LOCAL target — an invented terminus.
 * Now the server names every arc hit from its
 * {@code DamageListener} seam (GPEE v13 {@code ARC_HIT}) and the {@link ArcHitFxLane} draws the
 * fragment→true-node arc. Here the local {@code findTarget} is the victim-class switch: a local
 * SHIP ⇒ {@code doorShut} + return (the damaging arc stays shut; the wire lane
 * owns the hit); a local MISSILE ⇒ vanilla's HIT visual
 * (its shape, damage-free) drawn locally fragment→missile twin via
 * {@link ArcHitFxLane#presentVoltaic} — the server's probe names ship victims only, it emits
 * nothing for a missile, which it damages (usually kills) authoritatively — a survivor shows a
 * survived arc hit on both sides, as vanilla does; no local target
 * ⇒ the whole call goes to the parent's {@code onFire}, whose own re-run of the same search finds
 * none and draws vanilla's NO-TARGET visual itself (fragment flash + the miss flail — the server
 * emits nothing for a miss, rewritten from the functional spec).
 * Trigger = the presented shot via {@link ReplicaPresentedFireRelay}
 * (the presenter's spawn never runs {@code onFire}).
 */
public class CcWrapVoltaicDischargeOnFireEffect extends VoltaicDischargeOnFireEffect
        implements ReplicaPresentedFireRelay.PresentedFireAware {

    static final String TAG = "voltaicdischarge";

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        ShipAPI source = projectile.getSource();
        if (!CcReplicaEffectGate.isTreatedReplica(source)) {
            super.onFire(projectile, weapon, engine);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(source);
        if (swarm == null || swarm.getAttachedTo() == null || engine == null || weapon.getShip() == null) {
            return;
        }
        CombatEntityAPI target = this.findTarget(projectile, weapon, engine);
        if (target instanceof MissileAPI && target.getLocation() != null) {
            // Missile victim: no wire ARC_HIT exists for it (ArcHitServeProbe
            // ships-only), so the hit visual (local fragment pick + flash) is drawn
            // here, damage-free, anchored on the missile twin; the sim door stays shut.
            // A LOCAL prediction (the two grids can disagree at a pose/liveness boundary;
            // the ship pair is metered by ArcHitFxLane.doubleMintSuspect, the rest is not).
            CcReplicaEffectGate.doorShut(TAG);
            // With NO fragment within 150 of the target vanilla draws
            // nothing at all — mirror that; presentVoltaic alone would fall back to the hull location.
            if (pickFragmentTowardsPointWithinRange(swarm, target.getLocation(), 150.0F) == null) {
                return;
            }
            try {
                ArcHitFxLane.presentVoltaic(engine, weapon.getShip(), null, new Vector2f(target.getLocation()), target);
                ArcHitFxLane.noteLocalMissileArc(weapon.getShip());
                CcReplicaEffectGate.visualMinted(TAG + "_missilearc");
            } catch (Throwable ignore) {
                // presentation only; never break the watch
            }
            return;
        }
        if (target != null) {
            // vanilla's damaging spawnEmpArc, fragment -> target: census-only — the wire
            // ARC_HIT lane presents the true hit.
            CcReplicaEffectGate.doorShut(TAG);
            return;
        }
        // Rewritten from the functional spec:
        // no local target => the WHOLE call goes to the parent's onFire. The check above
        // IS the parent's public findTarget, called with the same three arguments this method
        // received (this class does not override it), and that search has no random and no state -
        // so the parent's own re-run inside super.onFire finds no target either and takes its
        // visual-only no-target path (vanilla's fragment flash + miss flail). Its damaging branch
        // needs a target and is unreachable from here.
        super.onFire(projectile, weapon, engine);
        CcReplicaEffectGate.visualMinted(TAG);
    }
}
