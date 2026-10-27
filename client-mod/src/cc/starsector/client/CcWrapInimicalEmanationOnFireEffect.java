package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.dweller.InimicalEmanationOnFireEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * Treated-replica wrapper over the Shrouded
 * {@link InimicalEmanationOnFireEffect}. Non-replica sources ride {@code super} verbatim
 * ({@code advance} is empty in vanilla, inherited).
 *
 * <p><b>Why:</b> the weapon's projectile is a blank non-colliding carrier
 * ({@code inimical_emanation_shot.proj} collisionClass NONE); the ENTIRE hit — damage, EMP, arc,
 * particles, impact sound — is the script's {@code onFire} arcing to a target
 * picked BY REFERENCE ({@code findTarget}). On a treated replica the presented shot
 * reaches {@code onFire} only through {@link ReplicaPresentedFireRelay}, and the OLD replica
 * branch re-ran {@code findTarget} locally and drew/printed from that LOCAL pick — an invented
 * target and a nominal number (D-INIMPHASEDMG + D-INIMDMGNUM).
 *
 * <p><b>Now (the ONE-presenter law; the client half of the
 * arc-hit contract):</b> the server names every
 * arc hit from its {@code DamageListener} seam and relays it as GPEE v13 {@code ARC_HIT}; the
 * {@link ArcHitFxLane} draws the arc to the server's node and prints the applied split. The replica
 * branch here is {@code replicaFrame} + the local {@code findTarget} as the victim-class switch:
 * a local SHIP ⇒ {@code doorShut} + return (vanilla's damaging {@code spawnEmpArc} and
 * the {@code RDRepairRateDebuff} add stay shut; the wire lane owns the hit); a local
 * MISSILE ⇒ vanilla's HIT visual (the same shape,
 * damage-free) drawn locally to the missile twin via {@link ArcHitFxLane#presentInimical} — the
 * server's probe names ship victims only, it emits nothing for a missile, which it damages
 * (usually kills) authoritatively — a survivor shows a survived arc hit on both sides, as vanilla
 * does; no local target ⇒ the whole call goes to the parent's
 * {@code onFire}, whose own re-run of the same search finds none and draws vanilla's NO-TARGET
 * visual itself (the miss flail — the server emits nothing for a miss, rewritten from the
 * functional spec). Zero wire bytes on the miss and missile paths; the ship
 * floaty lives in the lane.
 */
public class CcWrapInimicalEmanationOnFireEffect extends InimicalEmanationOnFireEffect
        implements ReplicaPresentedFireRelay.PresentedFireAware {

    static final String TAG = "inimical";

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        ShipAPI source = projectile.getSource();
        if (!CcReplicaEffectGate.isTreatedReplica(source)) {
            super.onFire(projectile, weapon, engine);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        ShipAPI ship = weapon.getShip();
        if (ship == null || engine == null) {
            return;
        }
        CombatEntityAPI target = this.findTarget(projectile, weapon, engine);
        if (target instanceof MissileAPI && target.getLocation() != null) {
            // Missile victim: no wire ARC_HIT exists for it (ArcHitServeProbe
            // ships-only), so vanilla's hit visual is drawn here, damage-free, anchored on
            // the missile twin; the sim door stays shut (the server damages, usually kills,
            // the missile). A LOCAL prediction (the two grids can disagree at a pose/liveness
            // boundary; only the ship pair is metered, ArcHitFxLane.doubleMintSuspect).
            CcReplicaEffectGate.doorShut(TAG);
            try {
                ArcHitFxLane.presentInimical(engine, ship, null, new Vector2f(target.getLocation()), target);
                ArcHitFxLane.noteLocalMissileArc(ship);
                CcReplicaEffectGate.visualMinted(TAG + "_missilearc");
            } catch (Throwable ignore) {
                // presentation only; never break the watch
            }
            return;
        }
        if (target != null) {
            // vanilla's spawnEmpArc (ENERGY damage + EMP) ship -> target and its RDRepairRateDebuff add:
            // census-only — the wire ARC_HIT lane presents the true hit.
            CcReplicaEffectGate.doorShut(TAG);
            return;
        }
        // Rewritten from the functional spec:
        // no local target => the WHOLE call goes to the parent's onFire. The check above
        // IS the parent's public findTarget, called with the same three arguments this method
        // received (this class does not override it), and that search is deterministic (no random;
        // the parent's one-shot extra range is never set non-zero, and a re-run with a smaller
        // range can only miss again) - so the parent's own re-run inside super.onFire finds no
        // target either and takes its visual-only no-target path (vanilla's miss flail). Its
        // damaging branch needs a target and is unreachable from here.
        super.onFire(projectile, weapon, engine);
        CcReplicaEffectGate.visualMinted(TAG);
    }
}
