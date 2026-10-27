package cc.starsector.client;

import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * The served-bomb BIND seam. Both
 * {@link CcWrapNovaBurstStats} and {@link CcWrapOrionDeviceStats} implement it, so the sourceless
 * adopt ({@code MissilePoseLane.adoptSourceless}) reaches the replica's OWN system-script instance
 * through the public {@code ShipAPI.getSystem().getScript()} read and a cast — no registry, no
 * reflection (the two wrappers are siblings, so neither may call the other's protected
 * {@code createBombImpactPlugin}, JLS 6.6.2.1). Combat-thread callers only.
 */
interface CcServedBombBinder {

    /** Install the FX-only mirror plugin ({@link CcServedBombMirror}) for a SERVED bomb body on
     *  this instance, so the plate impulse / jitter / particles ride the same script state the
     *  engine's own {@code apply} renders. {@code replica == null} = FX + flash only (no plate, no
     *  jitter, no slot math). Returns the bound system slot id, {@code "-"} when none resolved. */
    String bindServedBomb(ShipAPI replica, MissileAPI bomb, Vector2f loc);

    /** The vanilla {@code bombFadeInTime} of this instance's params (the spawn fade vanilla's
     *  {@code spawnBomb} applies; the adopt re-plays it on the served body). */
    float servedBombFadeIn();

    /** The remembered {@code (slot, time)} of this instance's last SKIPPED local mint on a served
     *  non-own ship, as the adopt row's {@code press=} token ({@link CcServedBombMirror#skipToken}):
     *  the per-bomb proof the local press path ran; {@code "none"} = never skipped. */
    String lastSkip();
}
