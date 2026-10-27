package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.SeekerFragmentMissileEffect;

/**
 * treated-replica wrapper over the Threat {@link SeekerFragmentMissileEffect}
 * (law + provenance: {@link FragmentCarrierPresentLaw}). Non-replica sources ride {@code super}
 * verbatim. The seeker's base carries no damage door of its own ({@code reportFragmentHit} is
 * the empty base, {@code explodeOnFizzling} false); the replica branch shuts only the
 * per-member fighter HP loss. The carrier
 * swarm still takes the birth latch ({@code custom2}, {@link ReplicaEffectLatchLaw}) so every
 * fragment carrier class reads alike.
 */
public class CcWrapSeekerFragmentMissileEffect extends SeekerFragmentMissileEffect
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
}
