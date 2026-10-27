package cc.starsector.client;

import java.util.ArrayList;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.CryofluxTransducerEffect;
import com.fs.starfarer.api.impl.combat.dweller.PseudoparticleJetEffect;

/**
 * Replica jet fuzz (replica = solid red streak, vanilla = fuzzy soft
 * particles) — treated-replica wrapper over the Shrouded {@link PseudoparticleJetEffect}.
 * Non-replica sources ride {@code super} verbatim.
 *
 * <p><b>Why:</b> the fuzz is one layered rendering plugin PER SHOT, built ONLY in the weapon
 * script's {@code onFire} ({@code createTrail} →
 * {@code addLayeredRenderingPlugin}, pinned to the shot); the solid streak that remains is the
 * engine's own BALLISTIC_AS_BEAM ray body. A presented replica shot is minted by
 * {@code spawnProjectile}, which never runs {@code onFire}.
 * Trigger = the presented shot via
 * {@link ReplicaPresentedFireRelay} (precedent {@link CcWrapKineticFragmentMissileEffect}).
 *
 * <p>Replica branch = vanilla's code with {@code prev} forced null and the
 * {@code cryo_prev_*} customData never written: the per-shot particle plugin renders (vanilla's
 * own class, {@code createTrail}), the inherited {@code advance} still plays the jet loop
 * as vanilla does, and the one SIM touch — the previous shot's position nudge
 * (guarded by {@code trail.prev != null}) — is dead on every replica trail, so
 * no client-side position write lands on a wire-presented ray. {@code onHit} stays vanilla (the
 * {@code .proj} slot: impact nebula + sound, FX only). Zero wire bytes, zero server compute.
 */
public class CcWrapPseudoparticleJetEffect extends PseudoparticleJetEffect
        implements ReplicaPresentedFireRelay.PresentedFireAware {

    static final String TAG = "pseudojet";

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (!ReplicaPresentedFireRelay.latchAtFire(projectile)) {
            super.onFire(projectile, weapon, engine);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        if (engine == null || projectile.getProjectileSpec() == null || projectile.getWeapon() == null) {
            return; // ParticleData reads proj.getWeapon().getRange()/getProjectileSpeed()
        }
        // vanilla's cryo_prev_<ship>_<slot> customData + the prev-shot nudge it feeds
        CcReplicaEffectGate.doorShut(TAG);
        CryofluxTransducerEffect trail = this.createTrail(projectile, null);
        CombatEntityAPI e = engine.addLayeredRenderingPlugin(trail);
        e.getLocation().set(projectile.getLocation());
        if (this.trails == null) {
            this.trails = new ArrayList<CryofluxTransducerEffect>();
        }
        this.trails.add(0, trail);
        CcReplicaEffectGate.visualMinted(TAG);
    }
}
