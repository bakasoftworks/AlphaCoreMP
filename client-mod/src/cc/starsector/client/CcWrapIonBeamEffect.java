package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.combat.IonBeamEffect;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lwjgl.util.vector.Vector2f;

/**
 * {@code ionbeam} — treated-replica beam wrapper over the vanilla
 * {@link IonBeamEffect}. {@link ClientBeamEffectSwap} installs this NAME on the beam spec;
 * non-replica sources ride {@code super.advance()} unchanged; a treated replica runs a gate and
 * cadence rewritten from the functional spec ({@link #replicaCrackle}) in
 * place of the parent's one sim door — {@code spawnEmpArcPierceShields} (unseeded-RNG damaging
 * arc, ghost divergence): a damage-free {@code spawnEmpArcVisual} with both anchors on the
 * struck ship (the localized crackle; terminus = an {@link EmpArcNodeLaw} hull node) plus the
 * weapon's impact cue. EMP arcs are NOT on the wire (zero server capture — audit seam report),
 * so the visual must be minted locally, not awaited.
 *
 * <p>Replica cadence state ({@code fireInterval}/{@code wasZero}) lives in this class's own
 * fields because the parent's are private; the replica branch never calls
 * {@code super.advance()}, so the two never both advance. This class also hosts the shared crackle
 * laws ({@link #cadenceStep}, {@link #pierceChance}, {@link #arcShows}) the tachyon and voltaic
 * cannon wrappers call.
 */
public class CcWrapIonBeamEffect extends IonBeamEffect {

    static final String TAG = "ionbeam";

    private final IntervalUtil fireInterval = new IntervalUtil(0.25F, 1.75F);
    private boolean wasZero = true;

    /** The ion crackle's thickness pad over the beam's width. */
    static final float ARC_WIDTH_PAD = 9.0F;
    /** The ion crackle's impact cue (the id the weapon's own arc carries). */
    static final String IMPACT_CUE = "tachyon_lance_emp_impact";
    /** The pierce law's hard-flux offset: a shield is never pierced below this hard-flux level. */
    static final float PIERCE_FLUX_OFFSET = 0.1F;

    /**
     * -- non-replica sources run the parent unchanged; a
     * treated replica runs {@link #replicaCrackle} on the ion numbers (rewritten from the
     * functional spec).
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (!CcReplicaEffectGate.isTreatedReplica(beam.getSource())) {
            super.advance(amount, engine, beam);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        float dur = beam.getDamage().getDpsDuration();
        float step = cadenceStep(wasZero, dur);
        wasZero = dur <= 0.0F;
        replicaCrackle(engine, beam, fireInterval, step, ARC_WIDTH_PAD, IMPACT_CUE, TAG);
    }

    /**
     * -- the replica crackle shared by this wrapper and
     * {@link CcWrapTachyonLanceEffect} (one shape, parameterised by the wrapper's own interval,
     * width pad and cue). With a ship target at full brightness the cadence {@code interval}
     * advances by {@code step} ({@link #cadenceStep}); each time it elapses: shield hit = the
     * target has a shield and the beam's previous-frame ray end lies within its arc; a shield hit
     * shows only when one uniform roll passes {@link #pierceChance}. A shown crackle = one
     * {@code doorShut}, then the presentation: a damage-free visual arc from that ray end to a
     * hull node ({@link EmpArcNodeLaw}, pierce form; both anchors on the target) at beam width +
     * {@code widthPad} in the beam's fringe / core colours, one {@code visualMinted}, and
     * {@code cue} at the ray end.
     */
    static void replicaCrackle(CombatEngineAPI engine, BeamAPI beam, IntervalUtil interval, float step,
            float widthPad, String cue, String tag) {
        CombatEntityAPI target = beam.getDamageTarget();
        if (!(target instanceof ShipAPI) || beam.getBrightness() < 1.0F) {
            return;
        }
        interval.advance(step);
        if (!interval.intervalElapsed()) {
            return;
        }
        ShipAPI ship = (ShipAPI) target;
        Vector2f point = beam.getRayEndPrevFrame();
        if (point == null) {
            return;
        }
        ShieldAPI shield = ship.getShield();
        boolean shieldHit = shield != null && shield.isWithinArc(point);
        float pierce = pierceChance(ship.getHardFluxLevel(),
                ship.getMutableStats().getDynamic().getValue(Stats.SHIELD_PIERCED_MULT));
        if (!arcShows(shieldHit, pierce, (float) Math.random())) {
            return;
        }
        CcReplicaEffectGate.doorShut(tag);
        engine.spawnEmpArcVisual(point, ship, EmpArcNodeLaw.pick(ship, point, true), ship,
                beam.getWidth() + widthPad, beam.getFringeColor(), beam.getCoreColor());
        CcReplicaEffectGate.visualMinted(tag);
        Global.getSoundPlayer().playSound(cue, 1.0F, 1.0F, point, new Vector2f());
    }

    /**
     * -- the cadence step, pure: the frame's damage-over-time
     * duration on a rising edge ({@link CcWrapGravitonBeamEffect#risingEdge}), else 0 (the timer
     * advances ONLY on rising-edge frames).
     */
    static float cadenceStep(boolean prevZero, float dpsDuration) {
        return CcWrapGravitonBeamEffect.risingEdge(prevZero, dpsDuration) ? dpsDuration : 0.0F;
    }

    /**
     * -- the pierce law, pure (shared with
     * {@link CcWrapVoltaicCannonEffect}): (hard-flux level - {@link #PIERCE_FLUX_OFFSET}) times
     * the target's {@code SHIELD_PIERCED_MULT}; unclamped (a value at or below 0 never passes).
     */
    static float pierceChance(float hardFluxLevel, float piercedMult) {
        return (hardFluxLevel - PIERCE_FLUX_OFFSET) * piercedMult;
    }

    /** -- an arc shows when it did not hit a shield, or its
     *  uniform {@code roll} in [0, 1) is below {@code pierceChance}. Pure. */
    static boolean arcShows(boolean shieldHit, float pierceChance, float roll) {
        return !shieldHit || roll < pierceChance;
    }
}
