package cc.starsector.client;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.Arrays;

/**
 * The axis-A/B hull-cap BOUND, closed at the engine's own
 * seam. {@code HullCapCore.capIfLethal} runs at the {@code DamageTakenModifier} seam
 * ({@code Ship.applyDamage}); only AFTERWARDS does
 * {@code applyDamageInner} fold the SHOOTER's size-class stat (
 * {@code dmg_vs_ftr/frig/destr/CA/capital}, unmodified again afterwards, so the modifier never
 * carries it at the seam) and the TARGET's hull-path taken mults, then the
 * armor grid's hull step ({@code getHullDamageTakenMult}) and the dealt
 * record's {@code getDamageToTargetHullMult}. A bound read at the seam
 * without them UNDER-estimates: in one recorded match entity 511 took the own ship's {@code dtf=1.5} past
 * the 5 % floor and died locally ~48 s before the server.
 *
 * <p>Laws: every factor folds ONLY where it is &gt; 1 — the bound may over-estimate (a slightly
 * lower presented near-lethal hit, benign) and never shrinks. Pure over API types (no engine-internal import,
 * no game statics — classloader-B clean); no allocation, strings or boxing — it runs per capped
 * candidate hit on the engine's applyDamage path. Every method is total; a throw counts
 * {@link #resolveFaults} (exported by PRCP's debrief line as {@code predict_hull_cap_resolve_faults}).
 *
 * <p>Shooter resolution: the engine keys the size-class stat on
 * {@code applyDamage}'s SOURCE, which the taken
 * seam never sees; {@link #upperBound} therefore folds the LARGER of the param-resolved shooter's
 * stat and the damage record's own {@code getStats()} (EMP arcs
 * and the ship-system EMP carry the source ship's stats on the record
 * while their param is the arc / a String), and when NEITHER resolves (script {@code applyDamage}
 * without a param; ship collisions)
 * it assumes the worst case = the LARGER of the caller's FALLBACK shooter (PRCP passes the OWN ship)
 * and the {@link #replicaClassMax} slot (widened for mirrored captain
 * skills; the slot is NON-empty today — {@code drone_terminator} replicas carry
 * {@code terminator_core}, damage-to-fighters 2.0, so the sourceless fighter-victim bound already
 * widens; over-estimate only). Residual over-cap only: a shared
 * persistent DamageAPI (a beam's) can keep a stale {@code dmg_vs_*} key when the engine's early
 * return skips the unmodify, so the next hit folds the factor twice —
 * benign and bounded.
 */
final class HullCapBound {

    /** Throws caught inside this class (render thread only; plain field, the witness discipline). */
    static int resolveFaults;

    /**
     * The
     * REPLICA fallback slot: per victim size class ({@link #victimClass} order: fighter, frigate, destroyer,
     * cruiser, capital) the LARGEST {@code getDamageTo*} stat (folded only where &gt; 1) over the
     * treated replicas offered this round; 0 = nothing offered. WHY: the sourceless fallback
     * assumed only the OWN ship carries a size-class stat &gt; 1, which fails once replicas mirror
     * a captain's skills over the wire (the RAPP officerLine on growth rows) — and already fails
     * today ({@code drone_terminator}, dtf 2.0). WHY five floats, not the held
     * {@code ShipAPI}: that slot admitted on dtf only while the fold read the
     * victim-class stat (a Target Analysis replica, +% vs frigate..capital, was never admitted),
     * and the static reference kept the whole combat graph reachable until the reset. Offered by
     * PRCP's treatment ledger ({@code neutralizeRemoteBody}) and the legacy 1v1 foe bind, zeroed at
     * its round reset and combat init; render thread only (the {@link #resolveFaults} discipline).
     * All zero ⇒ {@link #upperBound} behaves exactly as before.
     */
    static final float[] replicaClassMax = new float[5];

    /** Fold {@code replica}'s five size-class stats into {@link #replicaClassMax}, per class the
     *  max (the slot only grows within a round — an over-estimate, benign; once per
     *  treated body, never per frame). Null ship / stats → no-op; a throw counts. Total. */
    static void offerReplicaFallback(ShipAPI replica) {
        try {
            MutableShipStatsAPI s = replica == null ? null : replica.getMutableStats();
            for (int c = 0; s != null && c < replicaClassMax.length; c++) {
                replicaClassMax[c] = Math.max(replicaClassMax[c], classStat(s, c));
            }
        } catch (Throwable t) {
            resolveFaults++;
        }
    }

    /** PRCP's round reset ({@code finishMatchReset}) and combat {@code init}: the slot (a static)
     *  never outlives its round. */
    static void resetReplicaFallback() {
        Arrays.fill(replicaClassMax, 0f);
    }

    private HullCapBound() {
    }

    /**
     * The SHIP behind a damage seam's source object. At the MODIFY seam a projectile hit's param is
     * the projectile and a beam hit's the beam —
     * both resolve through {@code getSource()}; a {@link ShipAPI} is itself (the REPORT seam's source
     * of projectile damage is the firing ship; a script-chosen param may be one too);
     * anything else (an EMP arc, a String, null) or a throw → null. The ONE copy — {@link DamageWitness}'s
     * dsig census calls it too (its throws count into {@link #resolveFaults} as well).
     */
    static ShipAPI shooterOf(Object param) {
        try {
            if (param instanceof ShipAPI) {
                return (ShipAPI) param;
            }
            if (param instanceof DamagingProjectileAPI) {
                return ((DamagingProjectileAPI) param).getSource();
            }
            if (param instanceof BeamAPI) {
                return ((BeamAPI) param).getSource();
            }
        } catch (Throwable t) {
            resolveFaults++;
        }
        return null;
    }

    /**
     * The shooter's size-class damage stat exactly as the engine picks it: the
     * victim class is read off the PARENT STATION when the victim is a module, then fighter /
     * frigate / destroyer / cruiser / capital selects {@code getDamageTo*} on the shooter's stats.
     * Null shooter / victim / stats / stat, NaN, or any value &le; 1 (the bound never shrinks) → 1f;
     * a throw → 1f and counts.
     */
    static float shooterSizeClassMult(ShipAPI shooter, ShipAPI victim) {
        if (shooter == null || victim == null) {
            return 1f;
        }
        try {
            return sizeClassMult(shooter.getMutableStats(), victim);
        } catch (Throwable t) {
            resolveFaults++;
        }
        return 1f;
    }

    /**
     * {@link #shooterSizeClassMult} over a stats object directly — the damage record's own
     * {@code getStats()}: the engine builds the EMP-arc and ship-system-EMP
     * records with the SOURCE ship's stats while their seam param is not a ship. Null → 1f.
     */
    static float sizeClassMult(MutableShipStatsAPI s, ShipAPI victim) {
        if (s == null || victim == null) {
            return 1f;
        }
        try {
            return classStat(s, victimClass(victim));
        } catch (Throwable t) {
            resolveFaults++;
        }
        return 1f;
    }

    /**
     * The victim's size-class INDEX exactly as
     * the engine picks it, read off the PARENT STATION when the victim is a module:
     * 0 fighter, 1 frigate, 2 destroyer, 3 cruiser, 4 capital, -1 none. The ONE copy of the pick,
     * shared by {@link #sizeClassMult} and the {@link #replicaClassMax} fold (the slot
     * keys on the same class the fold reads). Non-null victim; a throw propagates to the caller's
     * counting catch.
     */
    static int victimClass(ShipAPI victim) {
        ShipAPI v = victim.getParentStation();
        if (v == null) {
            v = victim;
        }
        if (v.isFighter()) {
            return 0;
        }
        if (v.isFrigate()) {
            return 1;
        }
        if (v.isDestroyer()) {
            return 2;
        }
        if (v.isCruiser()) {
            return 3;
        }
        return v.isCapital() ? 4 : -1;
    }

    /** {@code s}'s {@code getDamageTo*} stat for class index {@code c} ({@link #victimClass} order),
     *  folded only where &gt; 1; -1 (no class) → 1f. */
    private static float classStat(MutableShipStatsAPI s, int c) {
        switch (c) {
            case 0: return atLeastOne(s.getDamageToFighters());
            case 1: return atLeastOne(s.getDamageToFrigates());
            case 2: return atLeastOne(s.getDamageToDestroyers());
            case 3: return atLeastOne(s.getDamageToCruisers());
            case 4: return atLeastOne(s.getDamageToCapital());
            default: return 1f;
        }
    }

    /** The {@link #replicaClassMax} entry for {@code victim}'s class; null victim / no class → 0f
     *  (folds nothing); a throw counts and folds nothing — total, so the caller's taken-mult fold
     *  still runs. */
    private static float replicaSlot(ShipAPI victim) {
        try {
            int c = victim == null ? -1 : victimClass(victim);
            return c < 0 ? 0f : replicaClassMax[c];
        } catch (Throwable t) {
            resolveFaults++;
        }
        return 0f;
    }

    /**
     * The hull-path post-seam mults, each folded only where &gt; 1: the victim's
     * non-shield branch ({@code isDps} → beam; {@code isMissile} →
     * missile, else non-dps → projectile; then the per-type kinetic / high-explosive / fragmentation
     * / energy taken mult), the armor grid's hull step {@code getHullDamageTakenMult},
     * and the DEALT record's {@code getDamageToTargetHullMult}
     * ({@code damage.getStats()} — read off the same DamageAPI on the
     * same hull path). Null victim / damage → 1f; a throw counts and keeps the factors folded so far
     * (&ge; 1f, the conservative direction).
     */
    static float targetTakenMult(ShipAPI victim, DamageAPI damage) {
        if (victim == null || damage == null) {
            return 1f;
        }
        float m = 1f;
        try {
            MutableShipStatsAPI s = victim.getMutableStats();
            if (s != null) {
                boolean dps = damage.isDps();
                if (dps) {
                    m *= atLeastOne(s.getBeamDamageTakenMult());
                }
                if (damage.isMissile()) {
                    m *= atLeastOne(s.getMissileDamageTakenMult());
                } else if (!dps) {
                    m *= atLeastOne(s.getProjectileDamageTakenMult());
                }
                DamageType type = damage.getType();
                if (type == DamageType.KINETIC) {
                    m *= atLeastOne(s.getKineticDamageTakenMult());
                } else if (type == DamageType.HIGH_EXPLOSIVE) {
                    m *= atLeastOne(s.getHighExplosiveDamageTakenMult());
                } else if (type == DamageType.FRAGMENTATION) {
                    m *= atLeastOne(s.getFragmentationDamageTakenMult());
                } else if (type == DamageType.ENERGY) {
                    m *= atLeastOne(s.getEnergyDamageTakenMult());
                }
                m *= atLeastOne(s.getHullDamageTakenMult());
            }
            MutableShipStatsAPI dealt = damage.getStats();
            if (dealt != null) {
                m *= atLeastOne(dealt.getDamageToTargetHullMult());
            }
        } catch (Throwable t) {
            resolveFaults++;
        }
        return m;
    }

    /**
     * The cap math of {@code HullCapCore.capIfLethal}, extracted verbatim: {@code NaN} = the cap does
     * not engage (no positive upper bound, or even the worst case leaves the hull above the floor —
     * axis A, the hit applies in FULL); else the mult that scales the hit so its worst-case hull
     * damage is at most {@code hull - floor} (axis B), clamped to [0, 1]. NaN inputs → NaN.
     */
    static float capMult(float hull, float floor, float upperHullDamage) {
        if (!(upperHullDamage > 0f) || hull - upperHullDamage > floor) {
            return Float.NaN;
        }
        float allowed = hull - floor;
        float mult = allowed <= 0f ? 0f : allowed / upperHullDamage;
        if (mult < 0f) mult = 0f;
        if (mult > 1f) mult = 1f;
        return mult;
    }

    /**
     * The COMPOSED worst-case hull damage of one hit at the taken seam (
     * the one line {@code HullCapCore.capIfLethal} calls, so a selftest row pins the composition):
     * {@code max(getDamage(), getBaseDamage())} × the size-class factor × {@link #targetTakenMult}.
     * The size-class factor = the LARGER of the param-resolved shooter's stat and the damage record's
     * {@code getStats()} stat; when NEITHER resolves, the larger of {@code fallbackShooter}'s
     * stat (see the class javadoc) and the {@link #replicaClassMax} slot's entry for the victim's
     * class. A
     * throw part-way keeps what is folded so far (never below the base) and counts.
     */
    static float upperBound(ShipAPI victim, DamageAPI damage, Object param, ShipAPI fallbackShooter) {
        float upper = Math.max(damage.getDamage(), damage.getBaseDamage());
        try {
            ShipAPI shooter = shooterOf(param);
            MutableShipStatsAPI dealt = damage.getStats();
            float size = 1f;
            if (shooter != null) {
                size = shooterSizeClassMult(shooter, victim);
            }
            if (dealt != null) {
                size = Math.max(size, sizeClassMult(dealt, victim));
            }
            if (shooter == null && dealt == null) {
                // The worst case over the own ship AND the replica class slot (the larger folds)
                size = Math.max(size, shooterSizeClassMult(fallbackShooter, victim));
                size = Math.max(size, replicaSlot(victim));
            }
            upper *= size;
            upper *= targetTakenMult(victim, damage);
        } catch (Throwable t) {
            resolveFaults++;
        }
        return upper;
    }

    /**
     * The LETHAL transition of one apply: the body was alive before
     * ({@code hpBefore > 0}, or unknown = NaN when the witness stash is missing) and is at hull
     * &le; 0 after. A hit on an already-dead body (corpse / hulk, {@code hpBefore == 0}) is NOT
     * one — {@link DamageWitness}'s past-cap kill-row budget is spent on transitions only.
     */
    static boolean lethalTransition(float hpBefore, float hpAfter) {
        if (Float.isNaN(hpAfter) || !(hpAfter <= 0f)) {
            return false;
        }
        return Float.isNaN(hpBefore) || hpBefore > 0f;
    }

    /** A stat's modified value when it is &gt; 1; null, NaN, &le; 1 → 1f. */
    private static float atLeastOne(MutableStat stat) {
        if (stat == null) {
            return 1f;
        }
        float v = stat.getModifiedValue();
        return v > 1f ? v : 1f;
    }
}
