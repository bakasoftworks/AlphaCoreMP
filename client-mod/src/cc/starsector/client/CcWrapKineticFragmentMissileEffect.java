package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.KineticFragmentMissileEffect;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * treated-replica wrapper over the Threat {@link KineticFragmentMissileEffect}
 * (law + provenance: {@link FragmentCarrierPresentLaw}). Non-replica sources ride {@code super}
 * verbatim. On a presented replica carrier the vanilla {@code onFire} swarm build runs (the
 * shot's only visual) with two doors shut: the per-member fighter HP loss
 * and the other-fragment
 * {@code applyDamage} in {@code reportFragmentHit}
 * — the hit particles stay, and the impact CUE that call's trailing
 * {@code playSound=true} carried is played explicitly (the replica branch had lost it):
 * the engine's light-tier kinetic hit sounds ({@code hit_hull_light} / {@code hit_shield_light_gun})
 * chosen by the target's shield arc at the fragment.
 *
 * <p>{@code reportFragmentHit} runs frames after {@code onFire} from the carrier swarm's own
 * {@code advance}, so it reads the SWARM's birth latch ({@code custom2},
 * {@link ReplicaEffectLatchLaw}) — never the per-weapon instance field, which a later fire on the
 * same mount would flip.
 */
public class CcWrapKineticFragmentMissileEffect extends KineticFragmentMissileEffect
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
    protected void reportFragmentHit(MissileAPI missile, RoilingSwarmEffect.SwarmMember p,
            RoilingSwarmEffect swarm, CombatEntityAPI target) {
        if (swarm == null || !ReplicaEffectLatchLaw.swarmReplica(swarm.custom2)) {
            super.reportFragmentHit(missile, p, swarm, target);
            return;
        }
        CcReplicaEffectGate.doorShut(FragmentCarrierPresentLaw.TAG); // vanilla: applyDamage(target, KINETIC, ..., playSound)
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || p == null) {
            return;
        }
        float size = 80.0F;
        engine.addHitParticle(p.loc, new Vector2f(), size, 0.5F, FRINGE_COLOR);
        engine.addHitParticle(p.loc, new Vector2f(), size * 0.25F, 1.0F, CORE_COLOR);
        boolean shielded = target instanceof ShipAPI && ((ShipAPI) target).getShield() != null
                && ((ShipAPI) target).getShield().isOn() && ((ShipAPI) target).getShield().isWithinArc(p.loc);
        Global.getSoundPlayer().playSound(shielded ? "hit_shield_light_gun" : "hit_hull_light", 1.0F, 1.0F, p.loc, new Vector2f());
    }
}
