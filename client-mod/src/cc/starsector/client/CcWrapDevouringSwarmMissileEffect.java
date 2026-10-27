package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.impl.combat.threat.DevouringSwarmMissileEffect;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * treated-replica wrapper over the Threat {@link DevouringSwarmMissileEffect}
 * (law + provenance: {@link FragmentCarrierPresentLaw}). Non-replica sources ride {@code super}
 * verbatim. Replica doors shut: the per-member fighter HP loss
 * and the {@code onHit} disintegrator
 * (11 hull-damage ticks on the target plus the
 * D6 heal-carry {@code sourceSwarm.addMember()} — both server-authoritative; the server's own
 * swarm growth was proven headless by the census probe {@code addMember=OK}). The hit
 * sound, {@code setDidDamage} (drives vanilla's STOP_AND_FADE of the other fragments) and the
 * dismantling base-damage negator listener (a damage REDUCER) stay.
 *
 * <p><b>The {@code onHit} door is live only through the ON_HIT spec swap</b>
 * ({@code devouring_swarm_missile.proj} onHitEffect → this class; the engine latches
 * a FRESH instance per carrier, so {@code onHit} derives replica-ness from the PROJECTILE —
 * {@link ReplicaPresentedFireRelay#onHitReplica} — never from the {@code onFire}-set field).
 * The carrier swarm carries its own latch in {@code custom2} ({@link ReplicaEffectLatchLaw})
 * so a later native fire on the same mount cannot flip a live swarm.
 */
public class CcWrapDevouringSwarmMissileEffect extends DevouringSwarmMissileEffect
        implements ReplicaPresentedFireRelay.PresentedFireAware {

    /** Birth-call scratch only (valid inside {@code onFire}'s synchronous swarm build). */
    private boolean replica;

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        replica = ReplicaPresentedFireRelay.latchAtFire(projectile);
        if (!replica) {
            super.onFire(projectile, weapon, engine);
            return;
        }
        if (!ReplicaPresentedFireRelay.beginReplicaShot(projectile, weapon, engine)) {
            return;
        }
        super.onFire(projectile, weapon, engine);
        CcReplicaEffectGate.visualMinted(FragmentCarrierPresentLaw.TAG);
    }

    @Override
    protected void swarmCreated(MissileAPI missile, RoilingSwarmEffect missileSwarm, RoilingSwarmEffect sourceSwarm) {
        super.swarmCreated(missile, missileSwarm, sourceSwarm);
        if (ReplicaPresentedFireRelay.latched(missile)) {
            missileSwarm.custom2 = ReplicaEffectLatchLaw.SWARM_LATCH;
        }
    }

    @Override
    protected float getHPLossPerTransferredMember() {
        return replica ? 0.0F : super.getHPLossPerTransferredMember();
    }

    @Override
    protected RoilingSwarmEffect.SwarmMember pickPrimaryFragment() {
        RoilingSwarmEffect.SwarmMember pick = super.pickPrimaryFragment();
        return pick == null && replica ? ReplicaPresentedFireRelay.anyMember(this.sourceSwarm) : pick;
    }

    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
            boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if (!ReplicaPresentedFireRelay.onHitReplica(projectile)) {
            super.onHit(projectile, target, point, shieldHit, damageResult, engine);
            return;
        }
        if (shieldHit || projectile == null || !(target instanceof ShipAPI)) {
            return; // vanilla's own no-op branches
        }
        CcReplicaEffectGate.doorShut(FragmentCarrierPresentLaw.TAG); // vanilla: DisintegratorEffect (hull ticks + addMember)
        // The disintegrator's LOOK (particles, loop, member carry-back) rides a
        // presentation-only subclass; its armor/hull writes stay server-authoritative (L3 F-L3-1).
        ReplicaDevourPresenter.present(engine, projectile, (ShipAPI) target, point);
        // The carrier's own hit cues, written from the functional
        // spec — didDamage + the hull-hit sound, as DevourEndLane's wire seam does.
        if (projectile instanceof MissileAPI) {
            MissileAPI missile = (MissileAPI) projectile;
            missile.setDidDamage(true);
            Global.getSoundPlayer().playSound("devouring_swarm_hit_ship", 1.0F, 1.0F, point, missile.getVelocity());
        }
    }
}
