package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.impl.combat.IonCannonOnHitEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * The EMP ion slice —
 * treated-replica wrapper over the vanilla {@link IonCannonOnHitEffect}, the {@code onHitEffect}
 * of {@code ioncannon_shot.proj} AND {@code ionpulser_shot.proj} (so the ion cannon, the fighter
 * ion cannon and the ion pulser — one class, one wrapper). Non-replica bolts (the own ship's ion
 * fire, serverless windows) ride {@code super} verbatim.
 *
 * <p><b>The hole this shuts:</b> vanilla {@code onHit}
 * rolls {@code Math.random() > 0.75F} on every hull hit and spawns a DAMAGING {@code spawnEmpArc}
 * (the bolt's full damage again + its EMP). On a presented replica bolt that roll ran LOCALLY,
 * independent of the server's own roll on the same hit — a ghost arc (client rolled, server did
 * not) or a missed one (server rolled, client did not), plus a local disable the weapon-state lane
 * then repaired. The server's roll is the only one that counts: its arc lands through the engine's
 * own {@code EmpArcEntity.doDamage} and rides the wire as {@code ARC_HIT} flavor
 * {@code ARC_FLAVOR_ION} (5), which {@link ArcHitFxLane#presentIon} draws damage-free at the true
 * node with the wire-split floaty. So here the replica branch mints NOTHING: the roll site is the
 * door ({@code doorShut} per hull hit the vanilla body would have rolled on), and the local impact
 * point is handed to the lane ({@link ArcHitFxLane#noteLocalIonHit}) so the wire arc can start
 * where the bolt struck — vanilla's own from-point, which the wire does not carry.
 *
 * <p>Live only through the ON_HIT spec swap ({@code ClientEveryFrameEffectSwap.ON_HIT_SWAPS}):
 * a {@code BALLISTIC_AS_BEAM} bolt dispatches {@code onHit} from ITS spec's slot on a fresh
 * instance, never from an everyFrame plugin. Replica-ness =
 * {@link ReplicaPresentedFireRelay#onHitReplica} (birth latch first, source treatment as the
 * fallback; fail direction = vanilla, {@code CcReplicaEffectGate}). Damage-LESS by law:
 * hull/armor/EMP stay wire-authoritative.
 */
public class CcWrapIonCannonOnHitEffect extends IonCannonOnHitEffect {

    static final String TAG = "ioncannon";

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
        // vanilla: Math.random() > 0.75F → spawnEmpArc(dam, emp) — the server's roll is the one
        // that lands (ARC_HIT flavor ION); the local roll is the RN divergence, shut.
        CcReplicaEffectGate.doorShut(TAG);
        ArcHitFxLane.noteLocalIonHit((ShipAPI) target, point);
    }
}
