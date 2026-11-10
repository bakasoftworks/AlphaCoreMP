package cc.starsector.client;

import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
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
 * ({@link #stoppedOverload}) so the caller COUNTS it, never hides it; the OWN ship's caller refuses
 * such a hit before arming ({@link #overFluxCap}). Combat thread; one instance per apply, never retained.
 *
 * <p>THE CEILING IS A BOUND, NOT THE FOUR TERMS (found by the design
 * lens): the engine folds the SOURCE ship's size-class stat into the damage before the shield branch
 * and the victim's projectile / missile and per-type
 * shield-taken mults inside it. With those missing, a hit near max
 * flux overloaded the body INSIDE the apply; the overload start toggles the shield off,
 *the stop does not raise it, and
 * on the own ship intent bit 10 then lowered it on the SERVER. Each new factor folds only where &gt; 1 — an
 * over-estimate is free, the window restores.
 *
 * <p>TWO NUMBERS, NEVER ONE: {@link #shieldFluxCeiling} is the
 * EXACT bound and the own caller's refusal ({@link #overFluxCap}) tests it bare — a margin there refused
 * lawful hits that fit (a Reaper on a 2,100-capacity frigate lost its ripple and number). The slack rides
 * the ARM's room only: {@link #ARM_MARGIN} on a replica, {@link #OWN_ARM_MARGIN} on the own ship.
 *
 * <p>NO SHIELD RE-RAISE, BECAUSE NONE IS POSSIBLE INSIDE THE CALL: the overload's
 * {@code toggleOff} only sets a PENDING flag on the charge tracker — {@code isOn()} stays true for the rest
 * of the call, {@code toggleOn} acts only {@code if (isOff())}, and the NEXT shield advance folds the
 * shield.
 * The re-raise in {@link #restore()} was therefore dead code and is gone. What
 * holds an own overload shut is the exact refusal plus the own arm's wide room; one that still starts (a
 * foreign damage listener scaling past {@link #OWN_ARM_MARGIN}, or past max flux itself) is stopped,
 * COUNTED by the caller, and the shield still drops — a next-frame raise would be a visible refold the
 * engine never performs, so none is built.
 */
final class WireAckedFluxWindow {

    /** A replica's slack over the exact ceiling when the arm opens room (a listener's small scale-up, float
     *  order): free, see the class note. An under-estimate there costs a counted residue, nothing more. */
    static final float ARM_MARGIN = 1.05f;

    /** The OWN ship's slack over the exact ceiling when the arm opens room. Wide on purpose: an
     *  under-estimate here overloads the player's own ship inside the call and the SERVER then lowers its
     *  shield (the class note), while an over-estimate only lowers the flux further for the length of the
     *  call (the ripple, number, sound and hard-flux rise are level-independent). A hit this factor cannot
     *  cover still gets all the room there is: the arm clamps at zero flux. */
    static final float OWN_ARM_MARGIN = 4f;

    private final FluxTrackerAPI ft;
    private final float preFlux;
    private final float preHard;
    private final boolean preOvl;
    private final boolean preVent;
    /** TRUE after {@link #restore()} had to stop a local overload the apply began (the residue class; on
     *  the OWN ship a FAULT — the caller's {@link #overFluxCap} refusal + {@link #armOwn}'s room should have
     *  held, and the shield drops, see the class note). */
    boolean stoppedOverload;

    private WireAckedFluxWindow(FluxTrackerAPI ft, float preFlux, float preHard, boolean preOvl, boolean preVent) {
        this.ft = ft;
        this.preFlux = preFlux;
        this.preHard = preHard;
        this.preOvl = preOvl;
        this.preVent = preVent;
    }

    /** Snapshot the tracker and open the headroom for {@code damage} of {@code type} from {@code source}
     *  (the object handed to {@code applyDamage} as its source; null = none) through a REPLICA's shield:
     *  the exact ceiling × {@link #ARM_MARGIN}. Null when the body has no tracker or a read/write threw
     *  (the apply still runs unarmed — presentation lane). */
    static WireAckedFluxWindow arm(ShipAPI victim, float damage, DamageType type, Object source) {
        return arm(victim, damage, type, source, ARM_MARGIN);
    }

    /** {@link #arm} for the player's OWN ship: the exact ceiling × {@link #OWN_ARM_MARGIN}. Null = the
     *  caller refuses the apply (fail-closed: an unarmed shield apply could overload the own ship). The
     *  caller tests {@link #overFluxCap} FIRST. */
    static WireAckedFluxWindow armOwn(ShipAPI own, float damage, DamageType type, Object source) {
        return arm(own, damage, type, source, OWN_ARM_MARGIN);
    }

    private static WireAckedFluxWindow arm(ShipAPI victim, float damage, DamageType type, Object source,
            float margin) {
        FluxTrackerAPI ft = victim == null ? null : victim.getFluxTracker();
        if (ft == null) {
            return null;
        }
        try {
            float preFlux = ft.getCurrFlux();
            float preHard = ft.getHardFlux();
            boolean preOvl = ft.isOverloaded();
            boolean preVent = ft.isVenting();
            float need = shieldFluxCeiling(victim, damage, type, source) * margin; // +Infinity stays +Infinity
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
     *  the flag — the original order, kept). It never touches the shield: a
     *  raise here cannot land (the class note). */
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

    /** TRUE when a hit whose EXACT ceiling is {@code need} ({@link #shieldFluxCeiling}, no margin) cannot
     *  fit under {@code victim}'s max flux at ANY headroom (the engine overloads strictly above max;
     *the arm can open at most the whole bar, the {@code - 1} is
     *  float slack) — the apply would overload inside the call. The OWN ship's caller refuses on it; NaN
     *  reads as over (fail-closed). FALSE with no tracker or on a throw: {@link #armOwn} then returns null
     *  and the caller's unarmed path names it. */
    static boolean overFluxCap(ShipAPI victim, float need) {
        try {
            FluxTrackerAPI ft = victim == null ? null : victim.getFluxTracker();
            return ft != null && !(need < ft.getMaxFlux() - 1f);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** The MOST flux one hit from {@code source} can add through {@code victim}'s shield, EXACT — no
     *  margin ({@link #ceiling} over the live reads). 0 with no shield (armor/hull hits add no flux); the ceiling
     *  assumes the shield takes it — the headroom is temporary and restored either way. The size stat
     *  folds only for a SHIP source, as the engine does ({@code source instanceof Ship}); the record the
     *  9-argument {@code applyDamage} builds is neither missile nor dps and carries no stats,
     *so the projectile shield-taken mult is the live
     *  branch — the missile twin is folded beside it (the larger) for a future door that says missile. An
     *  unreadable stat = the unmodified mult. */
    static float shieldFluxCeiling(ShipAPI victim, float damage, DamageType type, Object source) {
        ShieldAPI shield = victim.getShield();
        if (shield == null || damage <= 0f) {
            return 0f;
        }
        float perPoint = shield.getFluxPerPointOfDamage();
        float typeMult = type != null ? type.getShieldMult() : 1f;
        float taken = 1f;
        float branchTaken = 1f;
        float typeTaken = 1f;
        try {
            MutableShipStatsAPI s = victim.getMutableStats();
            taken = s.getShieldDamageTakenMult().getModifiedValue();
            branchTaken = Math.max(atLeastOne(s.getProjectileShieldDamageTakenMult().getModifiedValue()),
                    atLeastOne(s.getMissileShieldDamageTakenMult().getModifiedValue()));
            MutableStat typed = type == DamageType.KINETIC ? s.getKineticShieldDamageTakenMult()
                    : type == DamageType.HIGH_EXPLOSIVE ? s.getHighExplosiveShieldDamageTakenMult()
                    : type == DamageType.FRAGMENTATION ? s.getFragmentationShieldDamageTakenMult()
                    : type == DamageType.ENERGY ? s.getEnergyShieldDamageTakenMult() : null;
            typeTaken = typed == null ? 1f : typed.getModifiedValue();
        } catch (Throwable ignore) {
            // stat unreadable: the mults read so far, the rest unmodified
        }
        float sourceSize = source instanceof ShipAPI
                ? HullCapBound.shooterSizeClassMult((ShipAPI) source, victim) : 1f;
        return ceiling(damage, perPoint, typeMult, taken, sourceSize, branchTaken, typeTaken);
    }

    /** The ceiling's arithmetic, pure (smoke rows {@code MineNativeFuseLawSmoke} F15a(v)): the engine's own four shield-hit terms
     *  ({@code damage × perPoint × typeMult × shieldTaken}, exact — a mult below 1 shrinks it as the engine
     *  does) × the three bound factors, each only where &gt; 1 (NaN folds nothing). NO margin: the arm adds
     *  its own, the refusal takes none (the class note). No positive damage → 0; a NaN product → +Infinity
     *  (unreadable = it does not fit: the arm clamps to zero flux, the own caller refuses); a negative
     *  one → 0. */
    static float ceiling(float damage, float perPoint, float typeMult, float shieldTaken, float sourceSize,
            float branchTaken, float typeTaken) {
        if (!(damage > 0f)) {
            return 0f;
        }
        float need = damage * perPoint * typeMult * shieldTaken * atLeastOne(sourceSize) * atLeastOne(branchTaken)
                * atLeastOne(typeTaken);
        return Float.isNaN(need) ? Float.POSITIVE_INFINITY : Math.max(0f, need);
    }

    private static float atLeastOne(float v) {
        return v > 1f ? v : 1f;
    }
}
