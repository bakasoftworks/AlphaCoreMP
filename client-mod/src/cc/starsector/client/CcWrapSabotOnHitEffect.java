package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.impl.combat.SabotOnHitEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * The sabot-child twin of the ion-cannon on-hit wrapper
 * — treated-replica wrapper over the vanilla {@link SabotOnHitEffect}, the {@code onHitEffect} of
 * {@code sabot_warhead2.proj}: the BALLISTIC child every sabot weapon's MIRV stage splits into
 * (the stage-1 missile carries no onHit slot). Non-replica children (the own ship's sabot pods,
 * serverless windows) ride {@code super} verbatim.
 *
 * <p><b>The hole this shuts:</b> vanilla {@code onHit}
 * rolls {@code Math.random() < 0.25F} on every hull hit and spawns a DAMAGING {@code spawnEmpArc}
 * with {@code dam = 0.0F} + the child's EMP — the ion recipe at zero damage. On a presented child
 * that roll ran LOCALLY, independent of the server's, = ghost/missed arcs + a local EMP disable the
 * weapon-state lane then repaired ({@code CcWrapIonCannonOnHitEffect}'s class). The server's arc
 * rides the wire as {@code ARC_HIT} flavor {@code ARC_FLAVOR_SABOT} (6), which
 * {@link ArcHitFxLane#presentIon} draws damage-free (identical numbers) from the recorded local
 * impact point ({@link ArcHitFxLane#noteLocalIonHit}) to the wire node.
 *
 * <p>Live through the {@code ClientEveryFrameEffectSwap.ON_HIT_SWAPS} row reached by the sweep's
 * MIRV-CHILD leg (the child spec is no weapon's own projectile spec — it is resolved
 * from the engine's spec store and its slot rewritten ONCE; the split lane's per-split clone, its
 * by-id fallback and vanilla's own {@code MirvAI} clone all copy that class name, and the engine's
 * {@code BallisticProjectile} ctor latches a FRESH instance from it). Replica-ness =
 * {@link ReplicaPresentedFireRelay#onHitReplica} (the split lane latches its minted children at
 * birth; a vanilla-split child falls back to source treatment; fail direction = vanilla).
 * Damage-LESS by law: EMP stays wire-authoritative.
 */
public class CcWrapSabotOnHitEffect extends SabotOnHitEffect {

    static final String TAG = "sabot";

    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
            boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if (!ReplicaPresentedFireRelay.onHitReplica(projectile)) {
            super.onHit(projectile, target, point, shieldHit, damageResult, engine);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        if (shieldHit || !(target instanceof ShipAPI) || point == null) {
            return; // vanilla rolls nothing here either
        }
        // vanilla: Math.random() < 0.25F → spawnEmpArc(0, emp) — the server's roll is the one
        // that lands (ARC_HIT flavor SABOT); the local roll is the RN divergence, shut.
        CcReplicaEffectGate.doorShut(TAG);
        ArcHitFxLane.noteLocalIonHit((ShipAPI) target, point);
    }
}
