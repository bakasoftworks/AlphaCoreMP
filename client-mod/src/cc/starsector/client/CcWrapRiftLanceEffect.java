package cc.starsector.client;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.RiftLanceEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * {@code riftlance} — treated-replica beam wrapper over the vanilla
 * {@link RiftLanceEffect}. Its mine door sits behind the public static
 * {@code MINE_SPAWN_CHANCE = 0.0F} (dormant-by-flag), but the static is mutable and the audit
 * verdict is "drop unconditionally — never trust the 0": the public {@code spawnMine} override
 * shuts it for replicas regardless of the flag. The hit-darkening nebula burst (the actual
 * stock-config visual, since the 0-chance roll always takes the else branch) stays verbatim via
 * inheritance. Accepted micro-gap: if a mod ever raises the static, a replica's winning roll
 * yields no darkening burst that frame (cosmetic, currently unreachable).
 */
public class CcWrapRiftLanceEffect extends RiftLanceEffect {

    static final String TAG = "riftlance";

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (CcReplicaEffectGate.isTreatedReplica(beam.getSource())) {
            CcReplicaEffectGate.replicaFrame(TAG);
        }
        super.advance(amount, engine, beam);
    }

    @Override
    public void spawnMine(ShipAPI source, Vector2f mineLoc) {
        if (!CcReplicaEffectGate.isTreatedReplica(source)) {
            super.spawnMine(source, mineLoc);
            return;
        }
        // Vanilla would spawnProjectile(riftlance_minelayer) here — unreachable at the stock
        // flag value; counted so a raised flag shows up as doorShut>0 instead of ghost mines.
        CcReplicaEffectGate.doorShut(TAG);
    }
}
