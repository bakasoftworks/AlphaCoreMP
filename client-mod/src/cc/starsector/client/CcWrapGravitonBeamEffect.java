package cc.starsector.client;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.GravitonBeamEffect;

/**
 * {@code gravitonbeam} (+ {@code gazer_payload}, same class) — treated-replica
 * beam wrapper over the vanilla {@link GravitonBeamEffect}. The class has ZERO visual calls; its
 * one sim door installs a {@link GravitonBeamEffect.GravitonBeamDamageTakenMod} listener on the
 * TARGET ship and mutates its {@code MutableStats} shield-damage mult — authoritative state the
 * server already owns and wires. Replica branch = full no-op (suppression is visually lossless);
 * the read-only census below ({@link #advance}) runs purely so {@code doorShut} counts the frames
 * the parent would have installed/refreshed the debuff.
 * Gate on the SOURCE ship (the force-fired replica), never the target — audit risk note.
 */
public class CcWrapGravitonBeamEffect extends GravitonBeamEffect {

    static final String TAG = "graviton";

    /**
     * -- non-replica sources run the parent unchanged. A
     * treated replica installs nothing: its census, rewritten from the functional spec,
     * counts ONE {@code doorShut} on each damage rising edge ({@link #risingEdge}; the
     * parent's protected {@code wasZero} carries the previous frame) on which the beam has a ship
     * target, full brightness and a weapon, and its end point lies within the target shield's arc.
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (!CcReplicaEffectGate.isTreatedReplica(beam.getSource())) {
            super.advance(amount, engine, beam);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        float dur = beam.getDamage().getDpsDuration();
        boolean edge = risingEdge(wasZero, dur);
        wasZero = dur <= 0.0F;
        CombatEntityAPI target = beam.getDamageTarget();
        if (!edge || !(target instanceof ShipAPI) || beam.getBrightness() < 1.0F || beam.getWeapon() == null) {
            return;
        }
        ShieldAPI shield = ((ShipAPI) target).getShield();
        if (shield != null && shield.isWithinArc(beam.getTo())) {
            CcReplicaEffectGate.doorShut(TAG);
        }
    }

    /**
     * -- the damage rising edge, pure (shared with the
     * ion / tachyon cadence): this frame's damage-over-time duration is positive and the previous
     * frame's was zero ({@code prevZero}; an effect's first frame passes {@code true}).
     */
    static boolean risingEdge(boolean prevZero, float dpsDuration) {
        return prevZero && dpsDuration > 0.0F;
    }
}
