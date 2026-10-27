package cc.starsector.client;

import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The wire-acked FLUX idiom, carved OUT of
 * {@code PredictReconcileCombatPlugin.applyWireAckedHit} (PRCP sits under its
 * HardNb, so the own-shield presenter shares this instead of a copy landing IN). A hit the wire ALREADY
 * booked is presented through the engine's own {@code applyDamage}; the local flux the apply adds would
 * book it a second time, so the tracker is given headroom for exactly this hit's shield flux BEFORE the
 * apply and the pre-apply flux + flags are restored AFTER it.
 *
 * <p>WHY headroom, not a bare restore (a "double overload" mark, MEASURED from the
 * reel + the dmg-apply rows): the engine overloads SYNCHRONOUSLY on overflow — {@code beginOverload}, the
 * overload sound and the "Overloaded!" floaty fire INSIDE {@code increaseFlux}
 * — and a restore cannot un-fire those one-shots (the flags lane
 * stopping the local overload a frame later showed as "overloaded! text, no effects").
 * The ripple, number, sound and hard-flux rise are level-independent, so the presentation is
 * intact at any headroom. The wire owns flux (the per-frame pin rewrites it next frame anyway); the
 * client never decides an overload. Residue: a body whose max flux is below the hit's shield flux still
 * overflows (fighter-class caps) — {@link #restore()} stops that overload and flags it
 * ({@link #stoppedOverload}) so the caller COUNTS it, never hides it. Combat thread; one instance per
 * apply, never retained.
 */
final class WireAckedFluxWindow {

    private final FluxTrackerAPI ft;
    private final float preFlux;
    private final float preHard;
    private final boolean preOvl;
    private final boolean preVent;
    /** TRUE after {@link #restore()} had to stop a local overload the apply began (the residue class). */
    boolean stoppedOverload;

    private WireAckedFluxWindow(FluxTrackerAPI ft, float preFlux, float preHard, boolean preOvl, boolean preVent) {
        this.ft = ft;
        this.preFlux = preFlux;
        this.preHard = preHard;
        this.preOvl = preOvl;
        this.preVent = preVent;
    }

    /** Snapshot the tracker and open the headroom for {@code damage} of {@code type} through the victim's
     *  shield. Null when the body has no tracker or a read/write threw (the caller decides: a replica's
     *  apply still runs unarmed — presentation lane; the OWN ship's apply is refused, fail-closed). */
    static WireAckedFluxWindow arm(ShipAPI victim, float damage, DamageType type) {
        FluxTrackerAPI ft = victim == null ? null : victim.getFluxTracker();
        if (ft == null) {
            return null;
        }
        try {
            float preFlux = ft.getCurrFlux();
            float preHard = ft.getHardFlux();
            boolean preOvl = ft.isOverloaded();
            boolean preVent = ft.isVenting();
            float need = shieldFluxCeiling(victim, damage, type);
            float room = ft.getMaxFlux() - need - 1f;
            if (room < preFlux) {
                float f = Math.max(0f, room);
                ft.setCurrFlux(f);
                ft.setHardFlux(Math.min(preHard, f));
            }
            return new WireAckedFluxWindow(ft, preFlux, preHard, preOvl, preVent);
        } catch (Throwable ignore) {
            return null; // presentation lane: the caller's fail-soft / fail-closed choice
        }
    }

    /** Restore the pre-apply flux + flags, stopping an overload/vent the apply began. TRUE = the restore
     *  landed; FALSE = it threw (the flux pin rewrites the tracker next frame; the flags lane slaves the
     *  flags). {@link #stoppedOverload} is set right after {@code stopOverload()} and BEFORE the flux writes,
     *  so a throw in the writes never hides the residue (a throw from {@code stopOverload()} itself precedes
     *  the flag — the original order, kept). */
    boolean restore() {
        try {
            if (!preOvl && ft.isOverloaded()) {
                ft.stopOverload();
                stoppedOverload = true;
            }
            if (!preVent && ft.isVenting()) {
                ft.stopVenting();
            }
            ft.setCurrFlux(preFlux);
            ft.setHardFlux(preHard);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** The MOST flux one hit can add through {@code victim}'s shield: damage × the shield's
     *  flux-per-damage × the damage type's shield multiplier × the body's shield-damage-taken mult
     *  (the engine's own shield-hit terms). 0 with no shield (armor/hull hits add no flux); the
     *  ceiling assumes the shield takes it — the headroom is temporary and restored either way. */
    static float shieldFluxCeiling(ShipAPI victim, float damage, DamageType type) {
        ShieldAPI shield = victim.getShield();
        if (shield == null || damage <= 0f) {
            return 0f;
        }
        float perPoint = shield.getFluxPerPointOfDamage();
        float typeMult = type != null ? type.getShieldMult() : 1f;
        float taken = 1f;
        try {
            taken = victim.getMutableStats().getShieldDamageTakenMult().getModifiedValue();
        } catch (Throwable ignore) {
            // stat unreadable: the unmodified mult
        }
        float need = damage * perPoint * typeMult * taken;
        return Float.isFinite(need) && need > 0f ? need : 0f;
    }
}
