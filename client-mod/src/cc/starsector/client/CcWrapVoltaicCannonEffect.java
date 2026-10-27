package cc.starsector.client;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.combat.threat.VoltaicCannonEffect;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * treated-replica wrapper over the Threat
 * {@link VoltaicCannonEffect}. Non-replica sources ride {@code super} unchanged. On a treated
 * replica's presented shot the parent's {@code onHit} would mint {@code spawnEmpArcPierceShields}
 * arcs — damage-on-spawn EMP the server already applied authoritatively (the disabled bits ride
 * the wire) and a second, unseeded local author here. That door is shut; the replica branch
 * ({@link #onHit}, rewritten from the functional spec) mints its arcs
 * through the damage-free {@code spawnEmpArcVisual} overload with the ion-beam idiom's
 * both-anchors-on-target shape ({@code CcWrapIonBeamEffect}), plus one impact cue. Runs off the
 * local hit of the presented projectile (the engine dispatches {@code onHit} for it — no relay
 * needed).
 *
 * <p><b>Live only through the ON_HIT spec swap:</b> a projectile dispatches
 * {@code onHit} from ITS spec's slot ({@code voltaic_cannon_shot.proj} spawnType PLASMA ⇒ the
 * engine's plasma shot latches a FRESH instance), never
 * from the weapon's everyFrame plugin — until {@code ClientEveryFrameEffectSwap} rewrote the
 * projectile spec's onHit class this override was dead code and vanilla's damaging arcs ran on
 * presented shots. Replica-ness is derived from the projectile
 * ({@link ReplicaPresentedFireRelay#onHitReplica}: no relay reaches this class, so the birth latch
 * is never set and the source's treatment decides).
 */
public class CcWrapVoltaicCannonEffect extends VoltaicCannonEffect {

    static final String TAG = "voltaiccannon";
    /** The hit's impact cue, played once per hit that shows at least one arc. */
    static final String IMPACT_CUE = "voltaic_discharge_emp_impact";
    /** Each arc's thickness. */
    static final float ARC_WIDTH = 20.0F;

    /**
     * -- non-replica hits run the parent unchanged. A treated
     * replica's hit (engine, target and point present), rewritten from the functional spec:
     * {@link #arcCount} arcs between this instance's
     * {@code MIN_NUM_ARCS} and {@code MAX_NUM_ARCS}; pierce chance = 0 off a ship, else the shared
     * {@link CcWrapIonBeamEffect#pierceChance}; each arc shows unless the hit was on a shield and
     * its own uniform roll fails ({@link CcWrapIonBeamEffect#arcShows}). A shown arc = one
     * {@code doorShut} + a damage-free visual arc from the hit point to a hull node
     * ({@link EmpArcNodeLaw}, pierce form; both anchors on the target) in {@link #arcLook}. A hit
     * that showed any arc adds one {@code visualMinted} and {@link #IMPACT_CUE} at the hit point.
     */
    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
            boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if (!ReplicaPresentedFireRelay.onHitReplica(projectile)) {
            super.onHit(projectile, target, point, shieldHit, damageResult, engine);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        if (engine == null || target == null || point == null) {
            return;
        }
        float pierce = 0.0F;
        if (target instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) target;
            pierce = CcWrapIonBeamEffect.pierceChance(ship.getHardFluxLevel(),
                    ship.getMutableStats().getDynamic().getValue(Stats.SHIELD_PIERCED_MULT));
        }
        ProjectileSpecAPI spec = projectile != null ? projectile.getProjectileSpec() : null;
        Color fringe = spec != null && spec.getFringeColor() != null ? spec.getFringeColor() : Color.white;
        EmpArcEntityAPI.EmpArcParams look = arcLook();
        int arcs = arcCount(MIN_NUM_ARCS, MAX_NUM_ARCS, (float) Math.random());
        int shown = 0;
        for (int i = 0; i < arcs; i++) {
            if (!CcWrapIonBeamEffect.arcShows(shieldHit, pierce, (float) Math.random())) {
                continue;
            }
            CcReplicaEffectGate.doorShut(TAG);
            EmpArcEntityAPI arc = engine.spawnEmpArcVisual(point, target, EmpArcNodeLaw.pick(target, point, true),
                    target, ARC_WIDTH, fringe, Color.white, look);
            if (arc != null) {
                arc.setRenderGlowAtStart(false);
            }
            shown++;
        }
        if (shown > 0) {
            CcReplicaEffectGate.visualMinted(TAG);
            Global.getSoundPlayer().playSound(IMPACT_CUE, 1.0F, 1.0F, point, new Vector2f());
        }
    }

    /**
     * -- the arc count, pure: a uniform integer in
     * [{@code min}, {@code max}] from a {@code roll} in [0, 1) (a roll at or past 1 reads
     * {@code max}; {@code max <= min} reads {@code min}).
     */
    static int arcCount(int min, int max, float roll) {
        if (max <= min) {
            return min;
        }
        int n = min + (int) (roll * (max - min + 1));
        return n < min ? min : n > max ? max : n;
    }

    /** -- the arc look: segment length x8, zig-zag
     *  reduction 0.5, flicker x1, fade-out distance 1000, minimum fade-out x1, glow size 0.5,
     *  glow alpha 0.75 (the rest at the params' defaults; no glow at start is set per arc); the
     *  fields are assigned in field-name order. */
    private static EmpArcEntityAPI.EmpArcParams arcLook() {
        EmpArcEntityAPI.EmpArcParams p = new EmpArcEntityAPI.EmpArcParams();
        p.fadeOutDist = 1000.0F;
        p.flickerRateMult = 1.0F;
        p.glowAlphaMult = 0.75F;
        p.glowSizeMult = 0.5F;
        p.minFadeOutMult = 1.0F;
        p.segmentLengthMult = 8.0F;
        p.zigZagReductionFactor = 0.5F;
        return p;
    }
}
