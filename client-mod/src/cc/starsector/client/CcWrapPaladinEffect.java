package cc.starsector.client;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.PaladinEffect;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/**
 * {@code guardian} — treated-replica beam wrapper over the vanilla
 * {@link PaladinEffect}. Its ONE call is the sim door: {@code spawnDamagingExplosion} (100 frag /
 * 50 emp per beam instance). On a treated replica the det is DROPPED ENTIRELY with no local
 * analog — the wire's whitelisted {@code PALADIN_DET} family already presents the authoritative
 * det (damage-less FX consumer); a local analog would double-mint the flash. The replica branch
 * runs a read-only census ({@link #advance}) purely so {@code doorShut} counts the frame the
 * parent would have detonated — the watch's proof the door was reached, not just absent.
 */
public class CcWrapPaladinEffect extends PaladinEffect {

    static final String TAG = "paladin";

    /** The beams of one weapon converge when every one ends within this many su of the first. */
    static final float CONVERGE_SU = 15.0F;

    /** The replica branch's own once-per-beam-instance latch (the parent's is private). */
    private boolean done = false;

    /**
     * -- non-replica sources run the parent unchanged. A
     * treated replica spawns nothing: its census, rewritten from the functional spec,
     * counts ONE {@code doorShut} per beam-effect instance, on the first frame where
     * the beam has a damage target, is at full brightness, is its weapon's first beam, and every
     * beam of that weapon ends within {@link #CONVERGE_SU} su of this beam's end point.
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (!CcReplicaEffectGate.isTreatedReplica(beam.getSource())) {
            super.advance(amount, engine, beam);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        if (done) {
            return;
        }
        CombatEntityAPI target = beam.getDamageTarget();
        WeaponAPI weapon = beam.getWeapon();
        if (target == null || beam.getBrightness() < 1.0F || weapon == null) {
            return;
        }
        List<BeamAPI> beams = weapon.getBeams();
        if (beams == null || beams.isEmpty() || beams.get(0) != beam) {
            return;
        }
        Vector2f end = beam.getTo();
        for (BeamAPI other : beams) {
            Vector2f to = other.getTo();
            if (!endsWithin(to.x, to.y, end.x, end.y, CONVERGE_SU)) {
                return;
            }
        }
        done = true;
        CcReplicaEffectGate.doorShut(TAG);
    }

    /** -- the convergence law, pure: true iff point
     *  {@code (ax, ay)} lies within {@code radius} su of {@code (bx, by)} (the boundary counts). */
    static boolean endsWithin(float ax, float ay, float bx, float by, float radius) {
        float dx = ax - bx;
        float dy = ay - by;
        return dx * dx + dy * dy <= radius * radius;
    }
}
