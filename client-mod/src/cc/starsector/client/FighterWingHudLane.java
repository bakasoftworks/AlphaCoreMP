package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.campaign.fleet.FleetMember;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.CombatFleetManager;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.combat.ai.M;
import com.fs.starfarer.ui.c;
import com.fs.starfarer.ui.d;
import com.fs.starfarer.ui.interfacenew;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The piloted carrier's vanilla FIGHTER HUD panel (wing icons + pips +
 * "replacement rate: NN%") fed from the wire, knob {@code cc.predict.fighterHud} (off|apply).
 *
 * <p>WHY a lane: fighters arrive as wire-born replicas ({@code RosterAppendLane} RAPP rows) with no
 * local wing, and the local bay advance is latched OFF ({@code cc.predict.ownBayMint}), so the
 * vanilla panel ({@code class.new.E}) sees ZERO wings sourced from the
 * piloted hull and its rate text reads 100% ({@code Ship.getSharedFighterReplacementRate}
 * counts every wing-less bay as 1.0). Two engine-native seams close that:
 * <ol>
 * <li><b>Wing registration (icons + pips):</b> one replica wing per bay of the piloted carrier,
 *   minted exactly the way the engine's own bay mint does it —
 *   {@code new FleetMember(owner, wingId, FIGHTER_WING)} → the fleet manager's member wrapper →
 *   {@code addWing(wrapper, x, y, false)} ({@code false} = no fighter mint) →
 *   {@code setSourceShip(carrier)} + {@code setSourceBay(bay)} — the panel's filter needs BOTH.
 *   Pips are lit by {@code wing.getMembers().size()} (pure list
 *   length), so a bound fighter replica whose RAPP row names this carrier+bay is
 *   {@code addMember}-ed and a reaped one {@code removeMember}-ed. The replica is NEVER
 *   {@code setWing}-ed: a wing only enters the engine repository through a ship whose
 *   {@code getWing()==wing}, so the replica wing stays
 *   INERT — no {@code M.advance}, no return orders, no local replacement mint.</li>
 * <li><b>Rate text:</b> the served per-carrier percent (AWSD v12 keyframe byte,
 *   {@code ServerEntityPose.fighterReplacementPct()}) is written over the panel's own label from
 *   this plugin's {@code advance}: {@code CombatState.advance} runs {@code widgetPanel.advance}
 *   (vanilla writes "100%") BEFORE {@code engine.advance}
 *   (us) and renders after both, so our {@code d.setText} is the frame's text.
 *   The bay's own wing field has NO public setter, so
 *   {@code bay.setCurrRate} could never move that number — hence the label, not the bays.
 *   Reach is all public, no reflection (B-deny law): {@code CombatState.getShipInfo()} →
 *   {@code interfacenew.getChildrenCopy()} → the panel → its {@code d} child whose text starts
 *   with {@value #RATE_PREFIX}. Text is our own formatted int (0..100), never wire text. The
 *   label is found ONCE and held (liveness = {@link EngineReach#attached}); the served
 *   row is {@code plugin.lastOwnPose}, no per-frame snapshot scan.</li>
 * </ol>
 * The panel only ever shows the piloted hull, so wings are registered for {@code plugin.ownShip}
 * and re-bound whenever that identity changes (bind, respawn, W3 transfer); a fighter's
 * carrier is resolved from its RAPP {@code carrierWireId} (own seat id ⇒ {@code ownShip}, else
 * the remote entity's body). ONE wing set per carrier BODY: the set is
 * identity-cached for the round and a re-piloted body REUSES it — a second mint would make the
 * panel count both sets (its filter is source-ship identity, and a W3 round-trip makes the
 * stale source ship the panel ship again). {@code removeDeployed} is deliberately NOT used HERE
 * (it moves fleet strength + REMOVES the member from its control group — vanilla's DEATH
 * verb, which {@link DeployedSetEvictLane} applies at the kill seams). A
 * no-longer-piloted live body keeps its inert set with a stale source — never shown. No retry
 * list: a fighter bound before its carrier was (re)registered is enrolled by
 * {@link #registerCarrier}'s rescan of {@code plugin.remoteEntities}; a fighter of a carrier
 * we do not pilot is simply not ours (the 1:N server tags EVERY bay-born fighter, both sides).
 * Fail-soft: a Throwable dead-latches the lane for the run. Render thread only; state is
 * round-scoped ({@link #resetForMatchReset}).
 *
 * <p>RELAY HOME: this lane is the designated fighter-logic home, so the
 * carrier-sourced stat writers on fighter replicas ({@link CarrierBornFighterFx}) are owned and
 * relayed from HERE — first line of every PRCP entry, BEFORE this lane's own knob/piloted gates —
 * keeping PRCP untouched (the NEW-LANE LAW). Carrier resolution is shared via
 * {@link #resolveCarrier(PredictReconcileCombatPlugin, long)}.
 *
 * <p>CLASS-LOAD LAW: this class references API types only; every engine-typed call sits in the
 * nested {@link EngineReach}, loaded lazily at first use — {@code RenderCorpseGuardSmoke} constructs the
 * plugin (and so this lane) in a JVM WITHOUT the engine jar, where an engine type in a
 * verifier assignability check is a {@code NoClassDefFoundError} at plugin {@code <init>}.
 *
 * <p>DRONE ROSTER: the lane is the {@link OwnCarrierDrones.Roster} — the
 * enrolled puppets ARE the piloted carrier's drones for the mod-shipped {@link CcDroneStrikeStats}
 * (Termination Sequence read "NO DRONES" on a piloted Tempest because vanilla walks the bay's
 * never-set wing). Installed while a carrier is registered, uninstalled at carrier change / round
 * wrap; a dead-latched lane answers null (vanilla path).
 */
final class FighterWingHudLane implements OwnCarrierDrones.Roster {

    /** Resolved {@code cc.predict.fighterHud} mode — the PRCP banner line reads this. */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.fighterHud", System.getProperty("cc.predict.fighterHud", "apply"));
    private static final boolean APPLY = "apply".equals(MODE);

    /** The vanilla label prefix — the reach key AND our own text's head. */
    static final String RATE_PREFIX = "replacement rate: ";
    static final int BAY_NONE = 0xFF;

    private final PredictReconcileCombatPlugin plugin;
    /** The carrier-sourced fighter writers, relayed from every entry below. */
    final CarrierBornFighterFx fighterFx;

    /** The carrier whose replica wings are registered (identity; {@code null} = none yet). */
    private ShipAPI wingedCarrier;
    /** Per-bay replica wings of {@link #wingedCarrier} (index = bay index; a slot may be null). */
    private FighterWingAPI[] wings;
    /** Every wing set minted this round, by carrier BODY — a re-piloted body reuses its set. */
    private final IdentityHashMap<ShipAPI, FighterWingAPI[]> mintedByCarrier = new IdentityHashMap<>();
    /** Enrolled fighter replica → its wing (for the reap-side {@code removeMember}). */
    private final IdentityHashMap<ShipAPI, FighterWingAPI> memberWing = new IdentityHashMap<>();
    /** The panel's rate label, held across frames; {@code null} = re-find. */
    private LabelAPI rateLabel;
    private boolean deadLatch;

    // meter (per round)
    private int registrations, reuses, wingsRegistered, enrolled, evicted, overrides, labelMisses, labelFinds;
    private int lastLoggedPct = Integer.MIN_VALUE;

    FighterWingHudLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
        this.fighterFx = new CarrierBornFighterFx(plugin);
    }

    /** Per live frame (PRCP {@code advance}, after the snapshot publish): keep the piloted
     *  carrier's wings registered (re-bound on identity change), override the rate label. */
    void advance() {
        fighterFx.advance(); // O(1) unless a fighter waits for its carrier's bind
        if (!APPLY || deadLatch) {
            return;
        }
        try {
            ShipAPI own = plugin.ownShip;
            if (own != wingedCarrier) {
                registerCarrier(own);
            }
            overrideRateLabel();
        } catch (Throwable t) {
            deadLatch = true;
            PilotWatchLog.error("predict fighter-hud: lane FAILED -> dead-latched for the run "
                    + meter(), t);
        }
    }

    /** Bind-site relay (PRCP, right after {@code re.ship = ship}): a fighter replica whose RAPP row
     *  names the PILOTED carrier+bay joins that bay's replica wing; any other carrier's fighter is
     *  not ours (if we later pilot that carrier, {@link #registerCarrier}'s rescan enrols it). */
    void onRemoteBound(PredictReconcileCombatPlugin.RemoteEntity re) {
        fighterFx.onRemoteBound(re); // carrier-sourced writers, ANY carrier, before our gates
        if (!APPLY || deadLatch || re.appendCarrierWireId == 0L || re.appendBayIndex == BAY_NONE) {
            return;
        }
        tryEnroll(re);
    }

    /** Reap-site relay ({@code FoeStateLaneCoordinator.reapFlaggedRemotes}, BEFORE {@code re.ship=null}):
     *  the dead replica leaves its wing so the pip dims ({@code getMembers().size()} law). */
    void onRemoteReaped(PredictReconcileCombatPlugin.RemoteEntity re) {
        fighterFx.onRemoteReaped(re); // The body leaves the apply latch
        if (!APPLY || deadLatch || re.ship == null) {
            return;
        }
        try {
            FighterWingAPI wing = memberWing.remove(re.ship);
            if (wing != null) {
                wing.removeMember(re.ship);
                evicted++;
            }
        } catch (Throwable t) {
            deadLatch = true;
            PilotWatchLog.error("predict fighter-hud: reap FAILED entity=" + re.entityId
                    + " -> dead-latched for the run " + meter(), t);
        }
    }

    /** Round wrap: replicas die with the round; wings of the dead bodies are unreachable to the
     *  next panel (identity filter), so only our bookkeeping resets. */
    void resetForMatchReset() {
        fighterFx.resetForMatchReset(); // round-scoped latch/pending/transient wings
        if (registrations > 0 || enrolled > 0 || overrides > 0) {
            PilotWatchLog.info("predict fighter-hud: round meter " + meter());
        }
        OwnCarrierDrones.uninstall(this);
        memberWing.clear();
        mintedByCarrier.clear();
        wings = null;
        wingedCarrier = null;
        rateLabel = null;
        labelFinds = 0;
        registrations = 0;
        reuses = 0;
        wingsRegistered = 0;
        enrolled = 0;
        evicted = 0;
        overrides = 0;
        labelMisses = 0;
        lastLoggedPct = Integer.MIN_VALUE;
    }

    /** {@link OwnCarrierDrones.Roster}: the piloted carrier's LIVE enrolled
     *  puppets — what vanilla {@code getDrones} would return had the bays' wings been set. Null
     *  for any other body, an unregistered carrier, or a dead-latched lane (the vanilla path).
     *  A fresh list per call: the script sorts/shuffles the list it gets
     *  ({@code DroneStrikeStats}) and the engine calls it every frame (small: 1-4 bodies). */
    @Override
    public List<ShipAPI> puppetDronesOf(ShipAPI carrier) {
        if (deadLatch || carrier == null || carrier != wingedCarrier || wings == null) {
            return null;
        }
        List<ShipAPI> out = new ArrayList<ShipAPI>();
        for (FighterWingAPI wing : wings) {
            if (wing == null) {
                continue;
            }
            for (ShipAPI fighter : wing.getWingMembers()) {
                if (fighter != null && fighter.isAlive() && !fighter.isHulk()) {
                    out.add(fighter);
                }
            }
        }
        return out;
    }

    String meter() {
        return "registrations=" + registrations + " reuses=" + reuses + " wings=" + wingsRegistered
                + " enrolled=" + enrolled + " evicted=" + evicted + " rate_overrides=" + overrides
                + " label_misses=" + labelMisses + " label_finds=" + labelFinds + " members=" + memberWing.size()
                + (deadLatch ? " DEAD" : "");
    }

    // ---------------------------------------------------------------------------------------------

    /** Drop the previous carrier's enrolments and bind {@code carrier}'s replica wing set — the
     *  round's cached set for a body seen before, else one freshly minted wing per bay. */
    private void registerCarrier(ShipAPI carrier) {
        for (Map.Entry<ShipAPI, FighterWingAPI> e : memberWing.entrySet()) {
            e.getValue().removeMember(e.getKey());
        }
        memberWing.clear();
        wings = null;
        OwnCarrierDrones.uninstall(this); // No carrier, no roster
        rateLabel = null; // the count change below re-mints the panel's label
        wingedCarrier = carrier;
        if (carrier == null) {
            return;
        }
        FighterWingAPI[] cached = mintedByCarrier.get(carrier);
        if (cached != null) {
            wings = cached;
            reuses++;
            OwnCarrierDrones.install(this);
            PilotWatchLog.info("predict fighter-hud: re-piloted a known carrier body -> reusing its "
                    + cached.length + " replica wing(s) " + meter());
            enrolBoundFighters();
            return;
        }
        List<FighterLaunchBayAPI> bays = carrier.getLaunchBaysCopy();
        if (bays.isEmpty()) {
            return; // not a carrier: nothing to register, the panel stays vanilla-empty
        }
        // Bays exist one per NON-NULL wing slot, in slot order,
        // so the k-th non-empty variant wing id is bay k's wing (law shared with the fx lane).
        List<String> wingIds = CarrierBornFighterFx.wingIdsInBayOrder(carrier);
        int owner = carrier.getOriginalOwner(); // the panel's manager + the wing's owner
        FighterWingAPI[] minted = new FighterWingAPI[bays.size()];
        int count = 0;
        for (int k = 0; k < bays.size() && k < wingIds.size(); k++) {
            minted[k] = EngineReach.registerWing(carrier, owner, wingIds.get(k), bays.get(k));
            count++;
        }
        wings = minted;
        mintedByCarrier.put(carrier, minted);
        OwnCarrierDrones.install(this);
        registrations++;
        wingsRegistered += count;
        PilotWatchLog.info("predict fighter-hud: registered " + count + " replica wing(s) on the piloted"
                + " carrier '" + FallbackAnnounce.sanitize(carrier.getHullSpec().getHullId(), 48)
                + "' bays=" + bays.size() + " wingSlots=" + wingIds.size() + " owner=" + owner
                + " (inert: no setWing, no local mint) " + meter());
        enrolBoundFighters();
    }

    /** Fighters already bound before this carrier was (re)registered — rejoin/respawn/transfer
     *  order — enrol now; the bind relay only sees fighters bound AFTER. */
    private void enrolBoundFighters() {
        for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
            if (re.ship != null && !re.killed && re.appendCarrierWireId != 0L
                    && re.appendBayIndex != BAY_NONE && !memberWing.containsKey(re.ship)) {
                tryEnroll(re);
            }
        }
    }

    /** Enrol {@code re.ship} into the piloted carrier's bay wing; a fighter of any other carrier
     *  (or of a carrier not yet registered) is left alone — {@link #enrolBoundFighters} revisits
     *  every bound fighter whenever the piloted carrier changes. */
    private void tryEnroll(PredictReconcileCombatPlugin.RemoteEntity re) {
        ShipAPI carrier = resolveCarrier(re.appendCarrierWireId);
        if (carrier == null || carrier != wingedCarrier || wings == null) {
            return;
        }
        int bay = re.appendBayIndex;
        if (bay < 0 || bay >= wings.length || wings[bay] == null) {
            PilotWatchLog.info("predict fighter-hud: entity " + re.entityId + " names bay " + bay
                    + " but the piloted carrier registered " + wings.length + " wing slot(s) -> not enrolled");
            return;
        }
        if (re.ship == null || memberWing.containsKey(re.ship)) {
            return;
        }
        wings[bay].addMember(re.ship);
        memberWing.put(re.ship, wings[bay]);
        enrolled++;
    }

    private ShipAPI resolveCarrier(long carrierWireId) {
        return resolveCarrier(plugin, carrierWireId);
    }

    /** A RAPP carrier wire id → its live body: own seat id ⇒ {@code plugin.ownShip}, else the
     *  remote entity's body; null while unbound. Shared with {@link CarrierBornFighterFx}. */
    static ShipAPI resolveCarrier(PredictReconcileCombatPlugin plugin, long carrierWireId) {
        if (plugin.session != null && carrierWireId == plugin.session.shipId()) {
            return plugin.ownShip;
        }
        PredictReconcileCombatPlugin.RemoteEntity carrier =
                plugin.remoteEntities.get(Long.valueOf(carrierWireId));
        return carrier == null ? null : carrier.ship;
    }

    /** Write the served percent over the panel's rate label (vanilla formatting).
     *  The label is CACHED — re-found only while none is held or the held one has
     *  been orphaned ({@link EngineReach#attached}); the own row comes from {@code plugin.lastOwnPose}
     *  (refreshed by every snapshot apply) instead of an O(N) {@code entityById}
     *  scan, which also keeps the last served number on a frame whose snapshot carries no own row.
     *  {@code setText} stays UNCONDITIONAL: vanilla rewrites "100%" on this same label every frame
     *  BEFORE us, so an equal-text skip would show
     *  vanilla's number, not ours. */
    private void overrideRateLabel() {
        if (wings == null) {
            return;
        }
        ServerEntityPose pose = plugin.lastOwnPose;
        if (pose == null) {
            return;
        }
        int pct = pose.fighterReplacementPct();
        if (pct < 0) {
            return; // frame carried no rate (older server / absent tier): the vanilla text stands
        }
        if (pct > 100) {
            pct = 100;
        }
        LabelAPI label = rateLabel;
        if (label == null || !EngineReach.attached(label)) {
            label = EngineReach.findRateLabel();
            rateLabel = label;
            if (label == null) {
                labelMisses++;
                return;
            }
            labelFinds++;
        }
        String num = pct + "%";
        if (num.length() == 3) {
            num = " " + num;
        } else if (num.length() == 2) {
            num = "  " + num;
        }
        label.setText(RATE_PREFIX + num);
        overrides++;
        if (pct != lastLoggedPct && (lastLoggedPct == Integer.MIN_VALUE || Math.abs(pct - lastLoggedPct) >= 10)) {
            lastLoggedPct = pct;
            PilotWatchLog.info("predict fighter-hud: rate label <- " + pct + "% " + meter());
        }
    }

    /** The engine-typed reach, loaded lazily (see the class-load law above). All public engine API. */
    static final class EngineReach {
        private EngineReach() {
        }

        /** The engine's own bay-mint idiom minus the fighter mint:
         *  a deployed, INERT wing whose source ship + bay satisfy the panel filter. */
        static FighterWingAPI registerWing(ShipAPI carrier, int owner, String wingId, FighterLaunchBayAPI bay) {
            CombatFleetManager mgr = CombatEngine.getInstance().getFleetManager(owner);
            FleetMember member = new FleetMember(owner, wingId, FleetMemberType.FIGHTER_WING);
            CombatFleetManager.O0 dfm = mgr.addWing(new CombatFleetManager.oo(member),
                    carrier.getLocation().x, carrier.getLocation().y, false);
            M wing = dfm.getWingIfFighter();
            wing.setSourceShip(carrier);
            wing.setSourceBay(bay);
            return wing;
        }

        /** A held label is live while it still hangs off its panel: the wing panel re-mints its
         *  labels through {@code interfacenew.clearChildren} (nulls each child's
         *  parent) whenever its wing count changes; the ship-info panel itself
         *  persists for the combat and only
         *  {@code setShip}s the wing panel on a pilot change ({@code class/new/return.java}). */
        static boolean attached(LabelAPI label) {
            return label instanceof d && ((d) label).getParent() != null;
        }

        /** The wing panel's rate label: ship-info panel → its child panels → a {@code d} whose text
         *  starts with {@value FighterWingHudLane#RATE_PREFIX}. Null between rounds / before the panel
         *  builds. Allocates two child-list copies — hence the caller's cache. */
        static LabelAPI findRateLabel() {
            Object ui = CombatEngine.getInstance().getCombatUI();
            if (!(ui instanceof CombatState)) {
                return null;
            }
            interfacenew shipInfo = ((CombatState) ui).getShipInfo();
            if (shipInfo == null) {
                return null;
            }
            for (c child : shipInfo.getChildrenCopy()) {
                if (!(child instanceof interfacenew)) {
                    continue;
                }
                for (c leaf : ((interfacenew) child).getChildrenCopy()) {
                    if (leaf instanceof d) {
                        String text = ((d) leaf).getText();
                        if (text != null && text.startsWith(RATE_PREFIX)) {
                            return (d) leaf;
                        }
                    }
                }
            }
            return null;
        }
    }
}
