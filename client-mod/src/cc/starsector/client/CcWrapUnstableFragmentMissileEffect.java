package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.UnstableFragmentMissileEffect;
import com.fs.starfarer.api.util.FaderUtil;

/**
 * treated-replica wrapper over the Threat {@link UnstableFragmentMissileEffect}
 * (law + provenance: {@link FragmentCarrierPresentLaw}). Non-replica sources ride {@code super}
 * verbatim. Replica doors shut: the per-member fighter HP loss
 * and the per-frame proximity-fuse AI vanilla
 * drives from {@code swarmAdvance} (a local
 * {@code explode} author; the server's fuse ends the wire track, the missile lane presents that
 * end). The primary fragment's bouncing flash stays.
 *
 * <p>{@code swarmAdvance} runs frames after {@code onFire} from the carrier swarm's own
 * {@code advance}, so it reads the SWARM's birth latch ({@code custom2},
 * {@link ReplicaEffectLatchLaw}) — never the per-weapon instance field (a flip under a
 * live replica swarm would have sent it to {@code super.swarmAdvance}, which casts
 * {@code custom1} to {@code MissileAIPlugin}). {@code custom1} — vanilla's fuse-AI slot — is
 * never written here.
 */
public class CcWrapUnstableFragmentMissileEffect extends UnstableFragmentMissileEffect
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
            // The fuse door is shut for this carrier's whole life — counted once here, not per frame.
            CcReplicaEffectGate.doorShut(FragmentCarrierPresentLaw.TAG);
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
    protected void swarmAdvance(float amount, MissileAPI missile, RoilingSwarmEffect swarm) {
        if (swarm == null || !ReplicaEffectLatchLaw.swarmReplica(swarm.custom2)) {
            super.swarmAdvance(amount, missile, swarm);
            return;
        }
        // From the functional spec: the proximity fuse
        // stays SHUT — a replica never runs it (the server's fuse ends the wire track); only the primary flash runs:
        // the first member without one gets a 0.25 s in / 0.75 s out flash bouncing at both ends, started fading in
        // (an idle flash is cleared by the member's own advance before it ever shows).
        java.util.List<RoilingSwarmEffect.SwarmMember> members = swarm.getMembers();
        RoilingSwarmEffect.SwarmMember first = members == null || members.isEmpty() ? null : members.get(0);
        if (first != null && first.flash == null) {
            first.flash = new FaderUtil(0F, 0.25F, 0.75F, true, true);
            first.flash.fadeIn();
        }
    }
}
