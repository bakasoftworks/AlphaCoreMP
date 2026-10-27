package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;

/**
 * D1 — a replica-source shield hit never AUTHORS a local overload on a wire-driven body (the rule + WHY =
 * {@link ReplicaOverflowGuardLaw}).
 * One reusable instance per replica damage cap; combat thread; no allocation per hit.
 *
 * <p>Mechanism (exact, no damage estimate): {@link #arm} at the cap's MODIFY seam sets the body's current flux
 * aside and zeroes it, the engine applies the hit onto zero, and {@link #settle} — the FIRST statement of the same
 * cap's {@code reportDamageApplied} — reads what the engine added and lands or drops it. HARD flux is never zeroed:
 * the engine computes it from its own field, independent of the current flux,
 * so a LAND keeps the engine's hard flux bit for bit — soft
 * conversion included — and a DROP restores the value set aside. Not detectable through the API, accepted: a
 * tracker in the engine's one-frame raise-flux state clamps instead of overloading, and a DROP there leaves the
 * flux at {@code pre} until the next pin.
 *
 * <p>There is no {@code finally} across the two listener calls: an arm whose settle never came is
 * restored and counted by the next arm, a settle without an arm (the engine skips MODIFY at multiplier 0) is a
 * no-op, and the per-frame flux pin rewrites the tracker either way.
 */
final class ReplicaOverflowGuard {

    /** What the guard needs from the plugin — an interface so the selftest drives the REAL arm / settle (a
     *  seam row) without a combat engine. */
    interface Host {
        boolean isOwnShip(ShipAPI s);

        /** Side-effect-free: {@code s} is a wire-driven replica body. */
        boolean isWireDrivenShip(ShipAPI s);

        boolean wireAckedOpen();

        /** A LAND wrote {@code curr}: the zero-flux boost clock was evaluated on the zeroed value. */
        void settleBoostClock(ShipAPI body, FluxTrackerAPI ft, float curr);
    }

    /** The guard's outcome counters, one set per plugin (exported by {@code HullCapLane.rogTokens}).
     * */
    static final class Meters {
        int land, drop, residue, stale, fail;
        /** An ARMED hit whose report-seam source is the pilot's own ship — the two seams disagree; must read 0. */
        int ownArm;
        /** An UNARMED non-own hit that overflowed this body's shield (the unresolved-shooter classes). */
        int unarmedOver;
    }

    private final Host host;
    private final Meters meters;
    private ShipAPI body;
    private FluxTrackerAPI ft;
    private float pre;
    private float preHard;
    private boolean armed;

    ReplicaOverflowGuard(Host host, Meters meters) {
        this.host = host;
        this.meters = meters;
    }

    /** MODIFY seam, shield hits only: {@code live} = the cap's current body, {@code param} = the seam's param
     *  (the projectile or the beam). Never throws. */
    void arm(ShipAPI live, CombatEntityAPI target, Object param) {
        try {
            if (armed) { // The last arm's settle never came — put its flux back before reading a new pre
                armed = false;
                meters.stale++;
                ft.setCurrFlux(pre);
            }
            if (live == null || target != live) {
                return;
            }
            ShipAPI shooter = HullCapBound.shooterOf(param); // The param is never the ship
            FluxTrackerAPI t = live.getFluxTracker();
            if (t == null) {
                return;
            }
            boolean own = shooter != null && host.isOwnShip(shooter);
            if (!ReplicaOverflowGuardLaw.arms(shooter != null, own, shooter != null && host.isWireDrivenShip(shooter),
                    t.isOverloaded(), t.isVenting(), host.wireAckedOpen())) {
                return;
            }
            pre = t.getCurrFlux();
            preHard = t.getHardFlux();
            body = live;
            ft = t;
            t.setCurrFlux(0f);
            armed = true;
        } catch (Throwable th) {
            meters.fail++;
        }
    }

    /** REPORT seam, first statement. Returns the dmg-apply row token ({@code ""} = this hit was not armed;
     * a DROP is computed onto zero and prints {@code shieldOver=0}, so the grader keys on the token). Never
     *  throws. */
    String settle(ShipAPI live, Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
        try {
            if (!armed) {
                if (result != null && live != null && target == live && !(source instanceof ShipAPI && host.isOwnShip((ShipAPI) source))
                        && result.getOverMaxDamageToShields() > 0f) {
                    meters.unarmedOver++;
                }
                return "";
            }
            armed = false;
            if (target != body) { // another body's report: the arm is stale
                meters.stale++;
                ft.setCurrFlux(pre);
                return "";
            }
            if (source instanceof ShipAPI && host.isOwnShip((ShipAPI) source)) {
                meters.ownArm++;
            }
            float add = ft.getCurrFlux();
            switch (ReplicaOverflowGuardLaw.settle(pre, add, ft.getMaxFlux(), ft.isOverloaded())) {
                case ReplicaOverflowGuardLaw.RESIDUE:
                    meters.residue++;
                    return " rog=res";
                case ReplicaOverflowGuardLaw.DROP:
                    ft.setCurrFlux(pre);
                    ft.setHardFlux(preHard);
                    meters.drop++;
                    return " rog=drop";
                default:
                    ft.setCurrFlux(pre + add);
                    host.settleBoostClock(body, ft, pre + add);
                    meters.land++;
                    return " rog=land";
            }
        } catch (Throwable th) {
            meters.fail++;
            return " rog=fail";
        }
    }
}
