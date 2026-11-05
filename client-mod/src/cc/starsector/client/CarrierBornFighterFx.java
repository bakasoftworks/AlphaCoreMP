package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.skills.CoordinatedManeuversScript;
import com.fs.starfarer.api.impl.campaign.skills.ElectronicWarfareScript;
import com.fs.starfarer.api.impl.hullmods.ConvertedHangar;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.campaign.CharacterStats;
import com.fs.starfarer.combat.ai.M;
import com.fs.starfarer.combat.entities.ship.o0OO;
import com.fs.starfarer.loading.specs.FighterWingSpec;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * The carrier-sourced stat writers on wire-born FIGHTER
 * replicas, knob {@code cc.predict.fighterFx} (off|apply). Client-only; no wire field, no server
 * change ([[prefer-client-side-at-equal-quality]]: every input — carrier body, its captain, its
 * deployed member's commander, its variant — is already on the client).
 *
 * <p>WHY: vanilla births a fighter ONLY in a launch bay and, after
 * {@code setWing}/{@code addMember}, runs FOUR carrier-sourced writers on it: (i) the carrier
 * captain's {@code CharacterStats.applyShipFightersToStats} (SHIP_FIGHTERS effects, ids
 * {@code <skill>_ship_fighters_<idx>}); (ii) {@code ConvertedHangar.setFighterSkin}; (iii) every
 * carrier hullmod's {@code applyEffectsToFighterSpawnedByShip}; (iv) the carrier's deployed
 * member's commander chain ({@code getFleetCommanderForStats} → {@code getFleetData().getCommander()}
 * → {@code manager.getDefaultCommander()}) {@code applyAllFightersToStats} (ALL_FIGHTERS_IN_FLEET,
 * ids {@code <skill>_all_fighters_<idx>}). A bay-born fighter reaches the client as a RAPP v2 row
 * minted as a SHIP through {@code spawnFleetMember} ({@code PredictReconcileCombatPlugin
 * .spawnReplicaMemberFirst}), which bakes the ALL_SHIPS writers and never runs (i)–(iv) — the
 * census: client fighter {@code bwd/ewd/mwd} 1.10 vs server 1.25 =
 * {@code fighter_uplink_all_fighters_1:15.0} missing (44 DIFFER + 4 MIXED, one root cause).
 *
 * <p>HOW: the engine's OWN writers through an engine-TYPED adapter (id scheme + index law identical
 * to the server by construction; an API re-implementation would duplicate
 * the engine's own writer bodies and drift on the next engine update). The officer ×1.5
 * ({@code FighterUplink.isOfficer}: {@code fighter.getWing().getSourceShip().getCaptain()}
 * non-default, read AT APPLY TIME) rides the REPLICA WING: {@code new M(spec, owner, cr)} is a pure
 * field init (no fleet-manager registration), {@code setSourceShip(carrier)}
 * only — never {@code setSourceBay}, so {@code getSource()==null} and the local death path
 * can never report into a bay; {@code fighter.setWing(w)} → writers →
 * {@code setWing(previous)} in the SAME call, then the wing is ATTACHED for good (below).
 * The id-keyed mods persist; a same-id re-apply is a REPLACE by the engine's own
 * map semantics — the per-body latch below is belt-and-braces only.
 *
 * <p>THE PERSISTENT WING: vanilla gives every bay-born
 * fighter a wing for its whole life, and mod fighter scripts dereference it — {@code getWing()} null on
 * a replica = a Fatal on every window the moment a Valkazard Kai's blades overheat;
 * the engine's and the mods' carrier loops skip
 * a wing-less fighter the same way. So every carrier-born replica keeps the engine's own structure: ONE
 * source-less {@code M} per carrier body and bay ({@link #wingsByCarrier}), {@code setWing} +
 * {@code addMember} on the fighter, {@code removeMember} at its reap (the dead body keeps its wing —
 * vanilla) plus a dead leader handed to a live remaining member ({@code setLeader(next)}, the shape of
 * {@code M.advance}'s own re-pick, so the engine-loop sound, the radar blip and {@code getWingLeader()}
 * follow a live member) — never {@code null}: the radar builds every active wing's blip from
 * {@code getLeader().getSpec()}, and a leaderless wing Fataled both windows at one tick; no member left =
 * the dead leader stays, vanilla's wiped-wing state, until the next member attaches and takes the lead
 * ({@link #attachIfPromoted}).
 * WHY source-less is safe: with no source BAY, {@code isCarrierAlive()} is false, so
 * {@code orderReturn} is a plain list remove, {@code isReturning} is false, no replacement is requested
 * and no death reports into a bay.
 * WHY attached
 * only AFTER promotion: the engine enrols a ship's wing into its repository at promotion, and an enrolled
 * wing's {@code M.advance} runs every frame and dereferences a null leader once its members all died;
 *the gate is therefore
 * {@code engine.getShips().contains(ship)} — the PROMOTED list — never {@code isEntityInPlay}, which
 * includes the pending-add set. WHY the
 * attach is zero-frame: {@link SubstepHoldAI#advance} (every replica's AI) calls {@link #attachIfDue} as
 * its FIRST line — the engine advances a ship's AI BEFORE the ship (and its weapons) on every pass, and
 * promotion precedes it, so the wing is present at the fighter's FIRST weapon advance;
 *a body whose AI is not
 * ours is drained by {@link #advance} once per plugin frame. The former reason for a transient wing (a
 * persistent wing would arm {@code FighterAI}'s return / pull-back) is RETIRED: a replica's AI is
 * {@code SubstepHoldAI}, never {@code FighterAI}; the one
 * {@code FighterAI} mint is a mod's own {@code resetDefaultAI} takeover.
 * The one branch the wing ARMS — a mod effect's local {@code setForceFireOneFrame} on a replica beam — is
 * closed generally by {@code SubstepHoldAI}'s beam guard.
 *
 * <p>Scope = EVERY bound fighter replica whose RAPP row names a carrier (own, rival-seat, foe —
 * the 1:N server tags every bay-born fighter, both sides). Relayed by {@link FighterWingHudLane}
 * (the designated fighter-logic home; ZERO PRCP touch — the NEW-LANE LAW) from the bind
 * site BEFORE its own gates. A fighter bound before its carrier (map-order binds) waits in
 * {@link #pending} and is retried at every later bind + per frame while the list is non-empty.
 * Served windows only by construction (the relay only sees wire-born replicas). Fail-soft: a
 * Throwable dead-latches the lane for the run. Render thread only; round-scoped state.
 *
 * <p>SIDE-SCRIPT STRIP: the member-first replica is a DEPLOYED
 * FLEET MEMBER, and vanilla's per-frame side scripts write to every DFM that is not a wing —
 * {@code CoordinatedManeuversScript.updateForSide} ({@code MaxSpeed} percent
 * {@code coord_maneuvers_bonus}) and {@code ElectronicWarfareScript.applyPenalty}
 * (the three weapon-range mults {@code electronic_warfare_penalty}) — while the
 * server's bay-born fighter has NO member and never carries either (probe:
 * {@code native=absent twin=1.0}). {@link #stripSideScriptWrites} unmodifies those ids on every
 * carrier-born body: once after the writers, and EVERY FRAME via {@link #stripSideScriptWritesAll}
 * from {@link SideTotalsOverrideLane} — the one plugin ordered AFTER the vanilla scripts, which
 * re-write each frame, so a one-time strip cannot hold. {@link #isCarrierBorn} is that lane's
 * skip predicate (a plain {@code isFighter()} would also strip a fighter-hull ship the server
 * itself deployed as a member). {@code off} leaves the ids in place with the rest of the lane.
 *
 * <p>CLASS-LOAD LAW (as {@link FighterWingHudLane}): the outer class references API types only;
 * every engine-typed call sits in the nested {@link EngineReach}, loaded lazily at first use.
 *
 * <p>Proof: the fighter-fx probe (headless parity of a bay-born fighter vs the
 * client's member-first twin + {@link #applyWritersForProbe}); grade key in play = the
 * {@code predict dmg-dsig} line ({@code DamageWitness}) on fighter shooters.
 */
public final class CarrierBornFighterFx {

    /** Resolved {@code cc.predict.fighterFx} mode (off|apply; default apply). */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.fighterFx", System.getProperty("cc.predict.fighterFx", "apply"));
    private static final boolean APPLY = "apply".equals(MODE);
    /** Per-apply log lines are capped (fighters are minted by the dozen); the meter continues. */
    private static final int APPLY_LINE_MAX = 32;

    private final PredictReconcileCombatPlugin plugin;
    /** Apply-once latch keyed by the fighter BODY (cleared at round reset / reap only). */
    private final IdentityHashMap<ShipAPI, Boolean> applied = new IdentityHashMap<>();
    /** Every bound fighter body whose RAPP row named a carrier — the side-script strip
     *  set, filled at bind BEFORE the carrier resolves so a pending body is covered too. */
    private final IdentityHashMap<ShipAPI, Boolean> carrierBorn = new IdentityHashMap<>();
    /** Bound fighters whose carrier body was not yet resolvable (map-order binds). */
    private final List<PredictReconcileCombatPlugin.RemoteEntity> pending = new ArrayList<>();
    /** One persistent, source-less replica wing per carrier BODY and bay (class javadoc, THE PERSISTENT
     *  WING), minted lazily, shared by that bay's fighters as vanilla shares a bay's wing, never
     *  registered anywhere. */
    private final IdentityHashMap<ShipAPI, FighterWingAPI[]> wingsByCarrier = new IdentityHashMap<>();
    /** Fighters whose wing is minted but not yet attached: the body was still in the engine's
     *  pending-add set at apply time (a bind precedes promotion). Drained by {@link #attachIfDue}
     *  (the AI's first line) and {@link #advance}. */
    private final IdentityHashMap<ShipAPI, Attach> attachDue = new IdentityHashMap<>();
    /** Fighters carrying their replica wing ({@code setWing} + {@code addMember} done); the reap
     *  removes the member. */
    private final IdentityHashMap<ShipAPI, FighterWingAPI> attached = new IdentityHashMap<>();
    private boolean deadLatch, announced;

    // meter (per round)
    private int appliedCount, applyLines, pendingAdds, pendingRetries, noWing, reaped;
    private int attachedCount, attachLines;

    /** A due attach: the wing + what the log line names. */
    private static final class Attach {
        final FighterWingAPI wing;
        final long entityId;
        final int bay;

        Attach(FighterWingAPI wing, long entityId, int bay) {
            this.wing = wing;
            this.entityId = entityId;
            this.bay = bay;
        }
    }

    CarrierBornFighterFx(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Bind-site relay ({@link FighterWingHudLane#onRemoteBound}, first line): apply to a
     *  carrier-tagged fighter, then retry anything still pending (its carrier may be this bind). */
    void onRemoteBound(PredictReconcileCombatPlugin.RemoteEntity re) {
        if (!APPLY || deadLatch) {
            return;
        }
        try {
            announceOnce();
            if (re.appendCarrierWireId != 0L && re.appendBayIndex != FighterWingHudLane.BAY_NONE) {
                if (re.ship != null) {
                    carrierBorn.put(re.ship, Boolean.TRUE); // Stripped per frame from now on
                }
                tryApply(re);
            }
            drainPending();
        } catch (Throwable t) {
            deadLatch = true;
            PilotWatchLog.error("predict fighter-fx: bind FAILED entity=" + re.entityId
                    + " -> dead-latched for the run " + meter(), t);
        }
    }

    /** Per-frame relay: an O(1) no-op unless a fighter is waiting for its carrier's bind or for
     *  its promotion (the attach fallback for a body whose AI is not {@link SubstepHoldAI}). */
    void advance() {
        if (!APPLY || deadLatch || (pending.isEmpty() && attachDue.isEmpty())) {
            return;
        }
        try {
            drainPending();
            drainAttachDue();
        } catch (Throwable t) {
            deadLatch = true;
            PilotWatchLog.error("predict fighter-fx: pending drain FAILED -> dead-latched for the run "
                    + meter(), t);
        }
    }

    /** The zero-frame attach seam: {@link SubstepHoldAI#advance}'s FIRST
     *  line, every replica, every pass — an {@code isEmpty} read, then one identity-map get; the
     *  attach itself runs only for a body with a due wing (class javadoc, THE PERSISTENT WING). */
    void attachIfDue(ShipAPI ship) {
        if (attachDue.isEmpty() || deadLatch || ship == null || !attachDue.containsKey(ship)) {
            return;
        }
        try {
            attachIfPromoted(ship);
        } catch (Throwable t) {
            deadLatch = true;
            PilotWatchLog.error("predict fighter-fx: wing attach FAILED -> dead-latched for the run "
                    + meter(), t);
        }
    }

    /** Reap-site relay (BEFORE {@code re.ship=null}; {@link PredictReconcileCombatPlugin#respawnRemote} relays
     *  it too — that exit nulls the body outside the sweep): the body leaves the latch, the pending list and
     *  its wing's member list (the dead body itself keeps {@code getWing()} — vanilla), and a dead LEADER hands
     *  the lead to a live member (never {@code null}). Runs dead-latched too: the removal is cleanup, and a member left behind is a dead leader
     *  for the round. */
    void onRemoteReaped(PredictReconcileCombatPlugin.RemoteEntity re) {
        if (!APPLY) {
            return;
        }
        if (re.ship != null) {
            carrierBorn.remove(re.ship);
            if (applied.remove(re.ship) != null) {
                reaped++;
            }
            attachDue.remove(re.ship);
            FighterWingAPI wing = attached.remove(re.ship);
            if (wing != null) {
                try {
                    wing.removeMember(re.ship); // list remove only
                    if (wing.getLeader() == re.ship) {
                        // This wing is never enrolled, so M.advance's own re-pick never runs and the cached leader
                        // would stay the corpse (engine-loop sound silent, radar blip parked, getWingLeader() dead).
                        // Hand the lead to a live remaining member the way M.advance does — NEVER null: the radar
                        // builds a blip from getLeader().getSpec() for every active wing, and a wing with a null
                        // leader and no live member Fataled BOTH windows at the same tick. No member left = the
                        // dead leader stays (vanilla's own wiped-wing state, until the next member attaches and takes
                        // the lead in attachIfPromoted) and the wing leaves the active set with
                        // its last body.
                        //
                        ShipAPI next = null;
                        List<ShipAPI> members = wing.getWingMembers(); // a copy
                        if (members != null) {
                            for (ShipAPI m : members) {
                                if (m != null && m != re.ship && !m.isHulk()) {
                                    next = m;
                                    break;
                                }
                            }
                        }
                        if (next != null) {
                            wing.setLeader(next);
                        }
                    }
                } catch (Throwable t) {
                    deadLatch = true;
                    PilotWatchLog.error("predict fighter-fx: reap FAILED entity=" + re.entityId
                            + " -> dead-latched for the run " + meter(), t);
                }
            }
        }
        pending.remove(re);
    }

    /** {@code true} for a body the wire tagged as bay-born (class javadoc). */
    boolean isCarrierBorn(ShipAPI ship) {
        return ship != null && carrierBorn.containsKey(ship);
    }

    /** Per-frame relay ({@link SideTotalsOverrideLane}, ordered after the vanilla side
     *  scripts): re-strip every carrier-born body — the scripts re-write each frame. O(#bodies). */
    void stripSideScriptWritesAll() {
        if (!APPLY || deadLatch || carrierBorn.isEmpty()) {
            return;
        }
        for (ShipAPI fighter : carrierBorn.keySet()) {
            stripSideScriptWrites(fighter);
        }
    }

    /** Drop the vanilla side scripts' per-DFM ids the server's bay-born fighter never
     *  carries — {@code coord_maneuvers_bonus} (MaxSpeed percent) + {@code electronic_warfare_penalty}
     *  (the three weapon-range mults). Idempotent map removes; API-only. */
    static void stripSideScriptWrites(ShipAPI fighter) {
        MutableShipStatsAPI stats = fighter.getMutableStats();
        stats.getMaxSpeed().unmodify(CoordinatedManeuversScript.BONUS_ID);
        stats.getBallisticWeaponRangeBonus().unmodify(ElectronicWarfareScript.PENALTY_ID);
        stats.getEnergyWeaponRangeBonus().unmodify(ElectronicWarfareScript.PENALTY_ID);
        stats.getMissileWeaponRangeBonus().unmodify(ElectronicWarfareScript.PENALTY_ID);
    }

    /** Round wrap: replicas and their carriers die with the round; only bookkeeping resets. */
    void resetForMatchReset() {
        if (appliedCount > 0 || pendingAdds > 0 || noWing > 0) {
            PilotWatchLog.info("predict fighter-fx: round meter " + meter());
        }
        applied.clear();
        carrierBorn.clear();
        pending.clear();
        wingsByCarrier.clear();
        attachDue.clear();
        attached.clear();
        appliedCount = 0;
        applyLines = 0;
        pendingAdds = 0;
        pendingRetries = 0;
        noWing = 0;
        reaped = 0;
        attachedCount = 0;
        attachLines = 0;
    }

    String meter() {
        return "applied=" + appliedCount + " pending=" + pending.size() + " pending_adds=" + pendingAdds
                + " pending_retries=" + pendingRetries + " no_wing=" + noWing + " reaped=" + reaped
                + " carrier_born=" + carrierBorn.size() + " carriers=" + wingsByCarrier.size()
                + " attached=" + attachedCount + " attach_due=" + attachDue.size()
                + (deadLatch ? " DEAD" : "");
    }

    // ---------------------------------------------------------------------------------------------

    private void announceOnce() {
        if (!announced) {
            announced = true;
            PilotWatchLog.info("predict fighter-fx: mode=" + MODE + " (carrier captain SHIP_FIGHTERS + hullmod"
                    + " fighter effects + commander ALL_FIGHTERS via the replica wing (persistent, source-less))");
        }
    }

    /** {@code setWing} + {@code addMember} once the body is PROMOTED (class javadoc, THE PERSISTENT WING:
     *  the gate is the engine's promoted list, never {@code isEntityInPlay}); a pending-add body stays due. */
    private void attachIfPromoted(ShipAPI ship) {
        Attach due = attachDue.get(ship);
        if (due == null) {
            return;
        }
        if (plugin.engine == null || !plugin.engine.getShips().contains(ship)) {
            return; // still in the pending-add set: attaching now would enrol the wing at promotion
        }
        attachDue.remove(ship);
        ship.setWing(due.wing);
        due.wing.addMember(ship); // list add only
        // A wing wiped by reaps keeps its dead leader (onRemoteReaped, vanilla's wiped-wing state), and this wing is
        // never enrolled — so neither the engine's own re-pick (enrolled wings only) nor getLeader() (fills a NULL
        // field only) un-picks the corpse once a member attaches again: the radar blip and the engine-loop sound
        // would follow the wreck for the round, and a leader the engine later wipes is the same Fatal class as a
        // null lead. Hand the lead to the attaching member the way the reap does; a live member leader is left
        // alone.
        ShipAPI lead = due.wing.getLeader();
        if (lead == null || !lead.isAlive() || !due.wing.getWingMembers().contains(lead)) {
            due.wing.setLeader(ship);
        }
        attached.put(ship, due.wing);
        attachedCount++;
        if (attachLines < APPLY_LINE_MAX) {
            attachLines++;
            PilotWatchLog.info("predict fighter-fx: wing attached entity=" + due.entityId + " carrier="
                    + Integer.toHexString(System.identityHashCode(due.wing.getSourceShip())) + " bay=" + due.bay
                    + " members=" + due.wing.getWingMembers().size()
                    + (attachLines == APPLY_LINE_MAX ? " [line cap reached; meter continues]" : ""));
        }
    }

    private void drainAttachDue() {
        if (attachDue.isEmpty()) {
            return;
        }
        for (ShipAPI ship : new ArrayList<>(attachDue.keySet())) {
            attachIfPromoted(ship);
        }
    }

    /** Apply once per fighter body; a fighter whose carrier body is not (yet) bound waits. */
    private void tryApply(PredictReconcileCombatPlugin.RemoteEntity re) {
        ShipAPI fighter = re.ship;
        if (fighter == null || applied.containsKey(fighter)) {
            return;
        }
        ShipAPI carrier = FighterWingHudLane.resolveCarrier(plugin, re.appendCarrierWireId);
        if (carrier == null) {
            if (!pending.contains(re)) {
                pending.add(re);
                pendingAdds++;
                if (pendingAdds <= 4) {
                    PilotWatchLog.info("predict fighter-fx: entity " + re.entityId + " bound before its carrier "
                            + re.appendCarrierWireId + " -> pending until that bind " + meter());
                }
            }
            return;
        }
        int bay = re.appendBayIndex;
        FighterWingAPI wing = replicaWing(carrier, bay, fighter);
        if (wing == null) {
            noWing++;
        }
        String writers = EngineReach.applyWriters(fighter, carrier, wing);
        stripSideScriptWrites(fighter); // Same call shape as the probe; per-frame from here on
        applied.put(fighter, Boolean.TRUE);
        appliedCount++;
        if (wing != null) {
            // The wing stays with the fighter for its life — attached now if the body is
            // already promoted, else at its AI's first advance (attachIfDue) / the next plugin frame (advance).
            attachDue.put(fighter, new Attach(wing, re.entityId, bay));
            attachIfPromoted(fighter);
        }
        if (applyLines < APPLY_LINE_MAX) {
            applyLines++;
            PilotWatchLog.info("predict fighter-fx: applied carrier="
                    + Integer.toHexString(System.identityHashCode(carrier)) + " fighter="
                    + Integer.toHexString(System.identityHashCode(fighter)) + " entity=" + re.entityId
                    + " bay=" + bay + " " + writers + " bwdMods="
                    + fighter.getMutableStats().getBallisticWeaponDamageMult().getPercentMods().size()
                    + (applyLines == APPLY_LINE_MAX ? " [line cap reached; meter continues]" : ""));
        }
    }

    private void drainPending() {
        if (pending.isEmpty()) {
            return;
        }
        for (PredictReconcileCombatPlugin.RemoteEntity re : new ArrayList<>(pending)) {
            if (re.ship == null || re.killed || applied.containsKey(re.ship)) {
                pending.remove(re);
                continue;
            }
            if (FighterWingHudLane.resolveCarrier(plugin, re.appendCarrierWireId) == null) {
                continue;
            }
            pending.remove(re);
            pendingRetries++;
            tryApply(re);
        }
    }

    /** The round's replica wing for {@code carrier}'s bay {@code bay} (null when no wing spec
     *  resolves — the writers still run, only the officer ×1.5 hop is lost and the fighter stays
     *  wing-less, counted {@code no_wing}). */
    private FighterWingAPI replicaWing(ShipAPI carrier, int bay, ShipAPI fighter) {
        FighterWingAPI[] wings = wingsByCarrier.get(carrier);
        if (wings == null) {
            wings = new FighterWingAPI[Math.max(1, carrier.getLaunchBaysCopy().size())];
            wingsByCarrier.put(carrier, wings);
        }
        if (bay < 0 || bay >= wings.length) {
            return EngineReach.mintReplicaWing(carrier, bay, fighter); // out-of-range bay: still one wing, uncached
        }
        if (wings[bay] == null) {
            wings[bay] = EngineReach.mintReplicaWing(carrier, bay, fighter);
        }
        return wings[bay];
    }

    /** The carrier's variant wing ids in launch-bay order: bays exist one per NON-NULL wing slot,
     *  in slot order, so the k-th non-empty variant wing id
     *  is bay k's wing. Shared with {@link FighterWingHudLane#registerCarrier}. */
    static List<String> wingIdsInBayOrder(ShipAPI carrier) {
        int slots = carrier.getNumFighterBays();
        List<String> wingIds = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            String wingId = carrier.getVariant().getWingId(i);
            if (wingId != null && !wingId.isEmpty()) {
                wingIds.add(wingId);
            }
        }
        return wingIds;
    }

    /** Probe entry ({@code FighterFxParityProbe}): one fresh replica wing for {@code bay} + the
     *  four writers on {@code fighter} + the side-script strip, exactly the live lane's
     *  per-fighter writer call (the live path then attaches the wing and re-strips per frame from
     *  {@link SideTotalsOverrideLane}; the probe's twin stays wing-less — the writers are the parity under test). */
    public static String applyWritersForProbe(ShipAPI fighter, ShipAPI carrier, int bay) {
        String writers = EngineReach.applyWriters(fighter, carrier, EngineReach.mintReplicaWing(carrier, bay, fighter));
        stripSideScriptWrites(fighter);
        return writers;
    }

    /** The engine-typed reach, loaded lazily (class-load law above). */
    static final class EngineReach {
        private EngineReach() {
        }

        /** {@code new M(spec, owner, cr)} (field init only) + {@code setSourceShip}
         *  — never {@code setSourceBay} (class javadoc, THE PERSISTENT WING: no bay ⇒ no return, no
         *  replacement, no bay death report), never registered with a fleet manager. Spec = the carrier's
         *  wing for {@code bay}, else (bay/variant mismatch) the wing whose variant is the fighter's. */
        static FighterWingAPI mintReplicaWing(ShipAPI carrier, int bay, ShipAPI fighter) {
            List<String> wingIds = wingIdsInBayOrder(carrier);
            FighterWingSpecAPI spec = null;
            if (bay >= 0 && bay < wingIds.size()) {
                spec = Global.getSettings().getFighterWingSpec(wingIds.get(bay));
            }
            String fighterVariant = fighter.getVariant() == null ? null : fighter.getVariant().getHullVariantId();
            if (fighterVariant != null && (spec == null || !fighterVariant.equals(spec.getVariantId()))) {
                for (FighterWingSpecAPI s : Global.getSettings().getAllFighterWingSpecs()) {
                    if (fighterVariant.equals(s.getVariantId())) {
                        spec = s;
                        break;
                    }
                }
            }
            if (!(spec instanceof FighterWingSpec)) {
                return null;
            }
            M wing = new M((FighterWingSpec) spec, carrier.getOwner(), carrier.getCurrentCR());
            wing.setSourceShip(carrier);
            return wing;
        }

        /** The engine's post-{@code addMember} sequence verbatim in
         *  order — (i) captain SHIP_FIGHTERS, (ii) skin, (iii) hullmod fighter effects over the
         *  variant's hullmod set ({@code getHullMods()} IS the set {@code getAllMods()}
         *  walks), (iv) the commander chain — under the replica
         *  wing, restored in {@code finally} (the persistent attach is the caller's, gated on promotion).
         *  Returns a writer summary for the log line. */
        static String applyWriters(ShipAPI fighter, ShipAPI carrier, FighterWingAPI wingOrNull) {
            FighterWingAPI previous = fighter.getWing();
            if (wingOrNull != null) {
                fighter.setWing(wingOrNull);
            }
            try {
                o0OO stats = (o0OO) fighter.getMutableStats();
                ShipAPI.HullSize size = fighter.getHullSize();
                int captainRuns = 0;
                PersonAPI captain = carrier.getCaptain();
                if (captain != null && captain.getStats() != null) {
                    ((CharacterStats) captain.getStats()).applyShipFightersToStats(stats, size);
                    captainRuns = 1;
                }
                ConvertedHangar.setFighterSkin(fighter, carrier);
                int hullmods = 0;
                int hullmodsMissing = 0;
                for (String id : carrier.getVariant().getHullMods()) {
                    HullModSpecAPI mod = Global.getSettings().getHullModSpec(id);
                    if (mod == null || mod.getEffect() == null) {
                        hullmodsMissing++;
                        continue;
                    }
                    mod.getEffect().applyEffectsToFighterSpawnedByShip(fighter, carrier, id);
                    hullmods++;
                }
                String commander = "no-member";
                CombatFleetManagerAPI mgr = Global.getCombatEngine().getFleetManager(carrier.getOwner());
                DeployedFleetMemberAPI dfm = mgr.getDeployedFleetMember(carrier);
                if (dfm != null && dfm.getMember() != null) {
                    FleetMemberAPI member = dfm.getMember();
                    PersonAPI person;
                    if (member.getFleetCommanderForStats() != null) {
                        person = member.getFleetCommanderForStats();
                        commander = "member";
                    } else if (member.getFleetData() != null && member.getFleetData().getCommander() != null) {
                        person = member.getFleetData().getCommander();
                        commander = "fleet";
                    } else {
                        person = mgr.getDefaultCommander();
                        commander = "default";
                    }
                    if (person != null && person.getStats() != null) {
                        ((CharacterStats) person.getStats()).applyAllFightersToStats(stats, size);
                    } else {
                        commander = "null";
                    }
                }
                return "captain=" + captainRuns + " hullmods=" + hullmods
                        + (hullmodsMissing > 0 ? " hullmods_missing=" + hullmodsMissing : "")
                        + " commander=" + commander + " wing=" + (wingOrNull == null ? "NONE" : "replica");
            } finally {
                fighter.setWing(previous);
            }
        }
    }
}
