package cc.starsector.client;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.RiftCascadeEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * {@code riftcascade} — treated-replica beam wrapper over the vanilla
 * {@link RiftCascadeEffect}. The ONLY sim door is the public {@code spawnMine} method (≤5
 * unseeded {@code riftcascade_minelayer} mines); everything else in {@code advance()} — the
 * marching {@code spawnEmpArcVisual} arcs, negative particles, all counter bookkeeping — is
 * already visual/neutral and stays VERBATIM via inheritance. So the override shuts just that
 * door: replica → drop the mine (server mines ride the {@code MISSILE_*} sourceless record-diff;
 * a local mint would double them) but KEEP the {@code prevMineLoc} write, which feeds
 * {@code getNextArcLoc}'s arc-spacing geometry — dropping it drifts the kept visuals (audit
 * risk note). The counter writes ({@code spawned}/{@code doneSpawningMines}) live in the parent's
 * advance and still run, so the arc march terminates exactly like vanilla.
 */
public class CcWrapRiftCascadeEffect extends RiftCascadeEffect {

    static final String TAG = "riftcascade";

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
        // Vanilla would spawnProjectile(riftcascade_minelayer) + damage-modifier setup here.
        CcReplicaEffectGate.doorShut(TAG);
        this.prevMineLoc = mineLoc;
    }
}
