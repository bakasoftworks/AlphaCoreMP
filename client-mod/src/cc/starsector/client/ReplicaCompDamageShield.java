package cc.starsector.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.fs.starfarer.api.combat.ShipAPI;

import cc.starsector.client.net.ServerEntityPose;

/**
 * The replica component-damage shield. HARDWIRED unconditional at the E2
 * roll: the {@code cc.predict.foeCompDmgSuppress} class-(ii) probation rollback lever was
 * RATIFIED and retired per the auto-hardwire law
 * — the env-read is deleted, the pre-fix shape is recoverable from git.
 *
 * <p><b>Defect (owner: flameout-FX flicker + repair spam on EMP'd foe hulls).</b>
 * The differentiator proved the wire {@code engineDisabledMask} STABLE
 * per ship (textbook flameout arcs, zero disable↔repair oscillation) while the observing client
 * churned REPAIR-side (run-total 3027 repairs vs 1233 disables; the ramp gate fired 0×):
 * the client rolls its OWN stochastic component damage on replica hulls — a second RNG stream
 * the server never sees — and the v6 apply lane repairs each spurious local flameout 1–2 t
 * later; local re-arcs re-disable; every edge re-fires flameout/repair FX + sound.
 *
 * <p><b>Mechanism at the bytes.</b> Every local component (engine/weapon)
 * disable from damage funnels through the engine's {@code Ship.applyDamage} component loop:
 * per affected component, damage =
 * locationShare × (EMP + hullDmg + armorDmg), scaled by exactly TWO single-consumer stats —
 * {@code getEngineDamageTakenMult} / {@code getWeaponDamageTakenMult}; a
 * whole-engine-tree grep finds no other consumer of either. The stochastic EMP-arc path rides the
 * SAME loop ({@code EmpArcEntity.doDamage} → {@code Ship.applyDamage}; the random component
 * pick = {@code EmpArcEntity.findTarget}'s {@code WeightedRandomPicker.pick()}).
 *
 * <p><b>The fix — source-suppress, NOT a repair debounce.</b> On a replica (non-own)
 * hull whose wire pose carries the authoritative tier AND whose apply lane is live, install a
 * one-time {@code modifyMult(ID, 0f)} on that face's stat: local rolls can no longer author
 * component disables, so the v6 engine-state / v4 weapon-state apply lanes become the ONLY
 * author — the disable side is proven faithful (applies attribute ~1:1 to wire
 * set-edges). Engine face ⇐ {@code FOE_ENGINE_APPLY} + pose carries engine state; weapon face ⇐
 * {@code WEAPON_STATE_APPLY} + pose carries mounts + never the {@code "own"} pose. The weapon
 * face is the SAME one mechanism and is the v4 lane's own founding defect (locally-presented
 * fire disabling replica mounts, PRCP knob javadoc), so it rides the same lever. Wire-coverage
 * gating lives at the CALL SITES (each maintain's carried/mounts branch) — a hull the wire does
 * not cover keeps native local behavior, fail-safe.
 *
 * <p><b>Faithfulness.</b> Engine-native seam — the damage-taken-mult MutableStat is
 * the engine's own scaling mechanism (the Solar Shielding hullmod idiom); zero wire change;
 * visuals untouched (EMP still computes ⇒ arc FX/floaties render;
 * only the final component-damage multiplier is zeroed). Never-conceal: the v6 3-state meter +
 * mask-fork compare still run pre-apply every metered frame — the shield removes a
 * client-only RNG stream, not evidence; a genuine server-side divergence still screams. The OWN
 * hull is deliberately untouched (its prediction stays fully native; an own-side sighting of
 * this class is its own residual leg).
 *
 * <p>Round reset clears the identity sets (a respawned hull is a fresh instance and re-installs
 * on first maintain); the stat mod dies with the ship instance, so no uninstall path exists or
 * is needed. Cumulative counters ride the v6 engine-meter flush line ({@link #meterTokens()}).
 * Own-file per the PRCP growth-anchor law
 * and the new-lane law.
 *
 * <p><b>Efficacy amendment.</b> The 21st watch churned ANYWAY
 * with the shield proven installed at spawn — the single-funnel claim above is TRUE for the
 * DAMAGE side only (static RE, whole-tree author enumeration): the per-engine health
 * tracker's own {@code advance} is a NON-damage disable author — it rolls
 * {@code getEngineMalfunctionChance()} every 0.5–1.5&nbsp;s on any engine used last frame
 * (CR-driven, so it wakes late-round), and its HP regen
 * auto-re-flames a disabled engine in ~8–20&nbsp;s — neither path
 * reads the two damage-taken mults, so this shield cannot stop that churn class. Vanilla
 * writers CANNOT wipe our keyed mod (a whole-tree sweep: the only whole-map wipe, no-arg
 * {@code unmodify()}, has 5 call sites tree-wide, none on these stats), so the liveness verify
 * below ({@code lost}/{@code reassert} counters + counted re-assert) exists to close that lead
 * with LIVE zeros and to catch object-identity residue. The 22nd watch graded lost=0 LIVE both
 * seats (lead (i) closed; this shield HOLDS as the damage-side half).
 *
 * <p><b>Malfunction/regen extension.</b> The
 * non-damage authors get the same engine-native treatment, unconditional since the E2
 * hardwire (pre-fix shape in git):
 * <ul>
 * <li><b>Malfunction roll</b> ((c)(i)): {@code modifyMult(ID, 0f)} on
 * {@code getEngineMalfunctionChance} + {@code getWeaponMalfunctionChance} — the roll guard is
 * {@code chance > 0f && Math.random() < chance}, so a
 * keyed mult-0 starves it airtight (CR writes are keyed FLAT, base 0, no clamp).
 * {@code getCriticalMalfunctionChance} is deliberately NOT zeroed: the
 * roll guard's short-circuit already starves the crit branch, and {@code applyCRToShip} reads that
 * stat's mult mods into LowCRShipDamageSequence severity — zeroing it would change an unrelated
 * consumer. Server-side crits still arrive by wire: {@code Ship.applyCriticalMalfunction} lands
 * as a per-engine PERM disable + mount-tier disabled bit (c3 byte-check PAID).
 * Amendment: the OWN hull DOES take a keyed crit-chance zero
 * ({@link #ensureOwnCritFace}) — own weapon rolls stay native (a residual) and own engine
 * rolls are native whenever OWN_ENGINE_APPLY is off, so the nested crit could
 * fire LOCALLY: a permanently dead local weapon the wire never repairs (PRCP repairs only
 * !isPermanentlyDisabled()) + a hull write the server never made. The zero starves only the
 * nested roll; the applyCRToShip severity consumer reads the mult mods at instantiation, before
 * this face installs, so it is untouched.</li>
 * <li><b>Auto-regen re-flame</b> ((c)(ii)): {@code modifyMult(ID, REGEN_STALL_MULT)} on
 * {@code getCombatEngineRepairTimeMult} + {@code getCombatWeaponRepairTimeMult} — each stat's
 * sole reader is the tracker's regen divisor, so LARGE stalls
 * local self-repair; NEVER 0 (÷0 ⇒ +Inf increment ⇒ instant full-heal, inverted).
 * Wire-driven {@code repair()} force-sets HP=max and reads no stat, so server repairs land
 * instantly regardless.</li>
 * <li><b>Own-hull face</b> (conditional; condition VERIFIED):
 * malfunction suppression ONLY on the OWN hull's engine face, {@link #ensureOwnEngineFace} —
 * MUST gate on {@code OWN_ENGINE_APPLY} (c1: suppressed rolls + non-apply lane = ZERO own
 * malfunctions, vanilla-feel broken). The own DAMAGE-taken mults stay native (outside the shield's scope);
 * own WEAPON rolls stay native (residual — the weapon-lane meters adjudicate if a jam-churn
 * face shows). <b>The own face takes NO regen stall (23rd-watch regression):</b> the own
 * hull keeps native local damage prediction, so its locally-predicted engine disables MUST keep
 * vanilla self-heal — stalling them held a one-sided burnout's disabled fraction over the
 * controller's 0.5 flameout threshold for the whole server-repair window (local controller
 * stuck in a flameout the wire never had; 414 wire repairs fought it ≈ the owner's flicker +
 * sound spam + "full flameout instead of half"). The stall is only safe where local damage
 * authorship is ALSO suppressed — i.e. the replica faces, which keep it.
 * <b>Amendment:</b> {@link OwnEngineRegenStall} now installs a
 * WIRE-SCOPED engine regen stall on the own hull (only while ≥1 own engine is wire-disabled,
 * released on the first all-clear snapshot) — the storm needed a LOCAL-only burnout held
 * over the flameout threshold while the ramp guard was a dead predicate; the guard is fixed
 * (ITEM C) and the wire repair() is stat-blind, so the stall cannot pin a local flameout the
 * wire never had. The regression's signature (repair-ramp deferrals, apply-repairs per tick, the own-engine
 * disagree streak, "full flameout instead of half") is the flight's grade criterion: a
 * re-appearance reverts to the stall-off shape, never another backstop.</li>
 * </ul>
 * The threshold cascade ((c)(iii), the owner's system-spam face) needs no author of its own:
 * with (i)+(ii) suppressed the wire is the sole component author, so the local
 * disabled-fraction the cascade derives from converges to the server's own flameout shape;
 * residual = the client-local 5–11&nbsp;s re-flameout lockout timer, adjudicated by
 * the fo=/mc= tokens at the fix-verify flight.
 */
final class ReplicaCompDamageShield {

    /** The MutableStat modifier id (greppable; never collides with a hullmod id). */
    private static final String STAT_ID = "cc_replica_comp_dmg_shield";

    /** Regen stall factor — LARGE, NEVER 0f: the stat is a repair-TIME divisor's numerator
     *  partner (the tracker divides dt by it), and mult-0 ⇒ ÷0 ⇒ +Inf increment
     *  ⇒ instant full-heal, the exact inversion of the fix. */
    private static final float REGEN_STALL_MULT = 1e6f;

    private final PredictReconcileCombatPlugin.PredictLane engineSink;
    private final PredictReconcileCombatPlugin.PredictLane weaponSink;

    /** Hulls shielded this round, per face (identity keys: a respawn is a fresh instance). */
    private final Set<ShipAPI> engineShielded =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final Set<ShipAPI> weaponShielded =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final Set<ShipAPI> ownEngineShielded =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    /** Liveness: hulls whose verify/re-assert THREW — never retried (the no-retry law). */
    private final Set<ShipAPI> engineDead =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final Set<ShipAPI> weaponDead =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final Set<ShipAPI> ownEngineDead =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());

    private int engineShips; // cumulative engine-face installs (persist across rounds)
    private int weaponShips; // cumulative weapon-face installs (persist across rounds)
    // α liveness: mult read non-zero on an already-shielded hull = the mod is GONE
    // (statically near-impossible per the sweep — a live non-zero flags object-identity
    // residue); each loss is re-asserted and both edges counted (loss EVENTS when it sticks,
    // per-frame climb if something re-wipes — the counter SHAPE differentiates).
    private int engineMultLost;
    private int engineMultReasserts;
    private int weaponMultLost;
    private int weaponMultReasserts;
    private int ownEngineShips; // cumulative own-engine-face installs (persist across rounds)
    // The own crit face's identity + dead sets and counter.
    private final Set<ShipAPI> ownCritShielded =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final Set<ShipAPI> ownCritDead =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private int ownCritShips; // cumulative own-crit-face installs (persist across rounds)

    ReplicaCompDamageShield(PredictReconcileCombatPlugin.PredictLane engineSink,
            PredictReconcileCombatPlugin.PredictLane weaponSink) {
        this.engineSink = engineSink;
        this.weaponSink = weaponSink;
    }

    /** K23 relay ({@code FoeStateLaneCoordinator.resetForMatchReset}): round-scoped sets die with the round. */
    void resetForMatchReset() {
        engineShielded.clear();
        weaponShielded.clear();
        ownEngineShielded.clear();
        engineDead.clear();
        weaponDead.clear();
        ownEngineDead.clear();
        ownCritShielded.clear(); //
        ownCritDead.clear();
    }

    /**
     * Engine face — call sites: both foe engine-state maintains, inside the carried branch.
     * First call per hull installs; every later call runs the α liveness verify
     * (value read: our mult-0 forces {@code getModifiedValue()==0} regardless of other keyed
     * mods, so non-zero ⇔ our key is absent) + counted re-assert. A transient install failure
     * self-heals through the verify path; a hull that throws AGAIN goes to the dead set.
     */
    void ensureEngineFace(ShipAPI ship, ServerEntityPose pose, long simTick) {
        if (!PredictKnobConfig.FOE_ENGINE_APPLY
                || ship == null || engineDead.contains(ship)) {
            return;
        }
        if (engineShielded.add(ship)) {
            try {
                ship.getMutableStats().getEngineMalfunctionChance().modifyMult(STAT_ID, 0f);
                ship.getMutableStats().getCombatEngineRepairTimeMult()
                        .modifyMult(STAT_ID, REGEN_STALL_MULT);
                // damage-taken LAST: it is the α liveness canary, so canary-0 ⇒ whole face landed
                ship.getMutableStats().getEngineDamageTakenMult().modifyMult(STAT_ID, 0f);
                engineShips++;
                engineSink.log("predict comp-dmg-shield ENGINE ship=" + pose.entityId()
                        + " @tick=" + simTick);
            } catch (Throwable ignore) {
                // fail-soft like every reconcile lane — native local behavior remains (= pre-fix);
                // the shielded set retains the attempt; the verify path below gets ONE retry and
                // a second throw parks the hull in the dead set
            }
            return;
        }
        try {
            float v = ship.getMutableStats().getEngineDamageTakenMult().getModifiedValue();
            if (v != 0f) {
                engineMultLost++;
                // whole-instance residue loses every keyed mod together — re-assert the full face
                ship.getMutableStats().getEngineMalfunctionChance().modifyMult(STAT_ID, 0f);
                ship.getMutableStats().getCombatEngineRepairTimeMult()
                        .modifyMult(STAT_ID, REGEN_STALL_MULT);
                ship.getMutableStats().getEngineDamageTakenMult().modifyMult(STAT_ID, 0f);
                engineMultReasserts++;
                engineSink.log("predict comp-dmg-shield LOST ENGINE ship=" + pose.entityId()
                        + " mult=" + v + " @tick=" + simTick + " re-asserted");
            }
        } catch (Throwable ignore) {
            engineDead.add(ship); // no-retry: a throwing hull is never re-probed
        }
    }

    /**
     * Weapon face — call site: {@code maintainWeaponState}, non-own poses, inside the mounts
     * branch. Same install-then-verify shape as the engine face.
     */
    void ensureWeaponFace(ShipAPI ship, ServerEntityPose pose, long simTick) {
        if (!PredictKnobConfig.WEAPON_STATE_APPLY
                || ship == null || weaponDead.contains(ship)) {
            return;
        }
        if (weaponShielded.add(ship)) {
            try {
                ship.getMutableStats().getWeaponMalfunctionChance().modifyMult(STAT_ID, 0f);
                ship.getMutableStats().getCombatWeaponRepairTimeMult()
                        .modifyMult(STAT_ID, REGEN_STALL_MULT);
                // damage-taken LAST: it is the α liveness canary, so canary-0 ⇒ whole face landed
                ship.getMutableStats().getWeaponDamageTakenMult().modifyMult(STAT_ID, 0f);
                weaponShips++;
                weaponSink.log("predict comp-dmg-shield WEAPON ship=" + pose.entityId()
                        + " @tick=" + simTick);
            } catch (Throwable ignore) {
                // fail-soft — see the engine face
            }
            return;
        }
        try {
            float v = ship.getMutableStats().getWeaponDamageTakenMult().getModifiedValue();
            if (v != 0f) {
                weaponMultLost++;
                // whole-instance residue loses every keyed mod together — re-assert the full face
                ship.getMutableStats().getWeaponMalfunctionChance().modifyMult(STAT_ID, 0f);
                ship.getMutableStats().getCombatWeaponRepairTimeMult()
                        .modifyMult(STAT_ID, REGEN_STALL_MULT);
                ship.getMutableStats().getWeaponDamageTakenMult().modifyMult(STAT_ID, 0f);
                weaponMultReasserts++;
                weaponSink.log("predict comp-dmg-shield LOST WEAPON ship=" + pose.entityId()
                        + " mult=" + v + " @tick=" + simTick + " re-asserted");
            }
        } catch (Throwable ignore) {
            weaponDead.add(ship); // no-retry: a throwing hull is never re-probed
        }
    }

    /**
     * Own-hull ENGINE face — call site: {@code maintainOwnEngineState} (PRCP), carried branch.
     * Gated on {@code OWN_ENGINE_APPLY} — with the own apply lane live the wire is
     * the malfunction author of record (server rolls ride the tier as per-engine masks),
     * so suppressing the LOCAL roll leaves vanilla-feel intact.
     * Malfunction stat ONLY here — no damage-taken mults (damage prediction scope).
     * The own-hull regen stall is NOT this method's: it is
     * the WIRE-SCOPED {@link OwnEngineRegenStall}, installed/released by
     * {@code FoeStateLaneCoordinator.applyEngineState} — see the class javadoc's own-hull bullet
     * and its amendment (the unscoped stall was the regression).
     */
    void ensureOwnEngineFace(ShipAPI ship, ServerEntityPose pose, long simTick) {
        if (!PredictKnobConfig.OWN_ENGINE_APPLY
                || ship == null || ownEngineDead.contains(ship)) {
            return;
        }
        if (ownEngineShielded.add(ship)) {
            try {
                // malfunction-0 ONLY — the own-hull regen stall lives in OwnEngineRegenStall
                // (wire-scoped), never an unscoped stat write here (an unscoped one was a regression).
                // No liveness verify either: with the regen stat untouched HERE no own-face stat is
                // value-discriminating (malfunction reads 0 pre-CR regardless), and durability
                // is closed LIVE by the foe-face α (22nd/23rd watches, lost=0).
                ship.getMutableStats().getEngineMalfunctionChance().modifyMult(STAT_ID, 0f);
                ownEngineShips++;
                engineSink.log("predict comp-dmg-shield OWN-ENGINE ship=" + pose.entityId()
                        + " @tick=" + simTick);
            } catch (Throwable ignore) {
                ownEngineDead.add(ship); // fail-soft; a throwing hull is never re-probed
            }
        }
    }

    /**
     * Own-hull CRIT face — call
     * site: PRCP's own-ship block, fresh frames, NOT behind {@code OWN_ENGINE_METER}. A keyed
     * {@code modifyMult(STAT_ID, 0f)} on {@code getCriticalMalfunctionChance}: crits are
     * server-decided and arrive on the GPEE v14 {@code CRIT_MALFUNCTION} lane
     * ({@link CritMalfunctionLane}), so a LOCAL nested crit roll
     * would be a second, unwired author. NOT gated on {@code OWN_ENGINE_APPLY}: it starves only
     * the nested crit roll — the outer jam roll and its native presentation stay. Install-once
     * per hull instance; a throwing hull goes to the dead set (the {@link #ensureOwnEngineFace}
     * fail-soft shape).
     */
    void ensureOwnCritFace(ShipAPI ship, ServerEntityPose pose, long simTick) {
        if (ship == null || pose == null || ownCritDead.contains(ship)) {
            return;
        }
        if (ownCritShielded.add(ship)) {
            try {
                ship.getMutableStats().getCriticalMalfunctionChance().modifyMult(STAT_ID, 0f);
                ownCritShips++;
                engineSink.log("predict comp-dmg-shield OWN-CRIT ship=" + pose.entityId()
                        + " @tick=" + simTick);
            } catch (Throwable ignore) {
                ownCritDead.add(ship); // fail-soft; a throwing hull is never re-probed
            }
        }
    }

    /** Debrief tokens appended to the v6 engine-meter flush line (the counter-line discipline). */
    String meterTokens() {
        return " comp_dmg_shield_engine_ships=" + engineShips
                + " comp_dmg_shield_weapon_ships=" + weaponShips
                + " comp_dmg_shield_engine_lost=" + engineMultLost
                + " comp_dmg_shield_engine_reasserts=" + engineMultReasserts
                + " comp_dmg_shield_weapon_lost=" + weaponMultLost
                + " comp_dmg_shield_weapon_reasserts=" + weaponMultReasserts
                + " comp_dmg_shield_own_engine_ships=" + ownEngineShips
                + " comp_dmg_shield_own_crit_ships=" + ownCritShips; //
    }
}
