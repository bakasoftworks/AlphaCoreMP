package cc.starsector.client;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.impl.combat.TachyonLanceEffect;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * {@code tachyonlance} — treated-replica beam wrapper over the vanilla
 * {@link TachyonLanceEffect}: the {@link CcWrapIonBeamEffect} shape on the lance's numbers
 * (0.2–0.3s interval, width+5; the dropped sim arc's dam/emp scalars don't ride the visual).
 * Same one sim door ({@code spawnEmpArcPierceShields}), same visual analog
 * ({@code spawnEmpArcVisual}, both anchors on the damage target) + the lance's impact cue; see
 * the ion wrapper's doc for the WHY (arcs are not on the wire; fail direction = vanilla).
 */
public class CcWrapTachyonLanceEffect extends TachyonLanceEffect {

    static final String TAG = "tachyonlance";

    private final IntervalUtil fireInterval = new IntervalUtil(0.2F, 0.3F);
    private boolean wasZero = true;

    /** The lance crackle's thickness pad over the beam's width. */
    static final float ARC_WIDTH_PAD = 5.0F;
    /** The lance's impact cue. */
    static final String IMPACT_CUE = "tachyon_lance_emp_impact";

    /**
     * -- non-replica sources run the parent unchanged; a
     * treated replica runs the shared {@link CcWrapIonBeamEffect#replicaCrackle} on the lance's
     * numbers (rewritten from the functional spec).
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (!CcReplicaEffectGate.isTreatedReplica(beam.getSource())) {
            super.advance(amount, engine, beam);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        float dur = beam.getDamage().getDpsDuration();
        float step = CcWrapIonBeamEffect.cadenceStep(wasZero, dur);
        wasZero = dur <= 0.0F;
        CcWrapIonBeamEffect.replicaCrackle(engine, beam, fireInterval, step, ARC_WIDTH_PAD, IMPACT_CUE, TAG);
    }
}
