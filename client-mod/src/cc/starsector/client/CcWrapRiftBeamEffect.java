package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.RiftBeamEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * {@code riftbeam} — treated-replica EVERYFRAME wrapper over the vanilla
 * {@link RiftBeamEffect}, installed by {@link ClientEveryFrameEffectSwap} (not the beam sweep:
 * riftbeam carries no beamEffect — this everyFrame plugin IS its mine engine). The one sim door
 * is the public {@code spawnMine} → {@code spawnProjectile("riftbeam_minelayer")} (~0.5 s cadence
 * once the force-fired replica ray ramps bright and long); vanilla's angle/interval-phase/target
 * RNG is unseeded, so a local mint can never match the server's authoritative mines — which
 * already arrive via the MISSILE_* record-diff lane (mines ARE missiles). The class
 * authors zero visuals, so the override loses nothing; interval/targeting state stays
 * vanilla via {@code super.advance()} (the {@link CcWrapRiftLanceEffect} door-override shape).
 */
public class CcWrapRiftBeamEffect extends RiftBeamEffect {

    static final String TAG = "riftbeam";

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (weapon != null && CcReplicaEffectGate.isTreatedReplica(weapon.getShip())) {
            CcReplicaEffectGate.replicaFrame(TAG);
        }
        super.advance(amount, engine, weapon);
    }

    @Override
    public void spawnMine(ShipAPI source, Vector2f mineLoc) {
        if (!CcReplicaEffectGate.isTreatedReplica(source)) {
            super.spawnMine(source, mineLoc);
            return;
        }
        // Vanilla would spawnProjectile("riftbeam_minelayer") here — the exact ghost-mine mint
        // frame; the watch pairs this count with the zero-client-mine entity scan.
        CcReplicaEffectGate.doorShut(TAG);
    }
}
