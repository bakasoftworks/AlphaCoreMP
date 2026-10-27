package cc.starsector.client;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.impl.campaign.ids.BattleObjectives;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.skills.CommRelayScript;
import com.fs.starfarer.api.impl.campaign.skills.CoordinatedManeuversScript;
import com.fs.starfarer.api.impl.campaign.skills.ElectronicWarfareScript;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.DynamicStatsAPI;

import cc.starsector.client.net.AwsdSnapshotReader;

/**
 * The client half of the side-totals mirror: OVERWRITE-AFTER, API-pure
 * (a design decision; removePlugin-the-vanilla-scripts REJECTED — no {@code getPlugins()} exists, so
 * reaching vanilla instances would need engine-container reflection). Each frame this lane re-applies
 * the SAME stat ids the three vanilla side scripts wrote moments earlier, but computed from the
 * SERVER's authoritative raw totals (AWSD v9 trailing block, stashed by
 * {@link PredictReconcileCombatPlugin} on the reader thread) run through the vanilla rules
 * (the EW block and the witness comparator rewritten from the functional spec).
 * MutableStat is last-writer-wins per source id, so our write
 * simply stands. Totals null (v8 server / pre-first frame) ⇒ fully inert ⇒ vanilla behavior.
 *
 * <p><b>Ordering guarantee:</b> on FIRST advance the lane self {@code removePlugin(this)} +
 * {@code addPlugin(this)} once — plugins advance in insertion order, so the re-add appends us after
 * every plugin present (the three vanilla scripts included); applies start on the next advance.
 * Mid-combat addPlugin is routine vanilla behavior; {@code init} re-runs on the re-add (idempotent).
 *
 * <p><b>Deliberate choices (recorded per the brief):</b>
 * (1) HUD "Nav Buoy(s)" variant DROPPED — the generic "Coordinated Maneuvers" title always shows.
 * The buoys-only flag is the server's ship-flat-vs-buoy split, which is NOT on the wire; inferring
 * it from the local partial replica (whose ship dyn stats are missing BY DESIGN — the reason this
 * mirror exists) would show the wrong title exactly when the mirror matters. Numbers stay server
 * truth either way; cosmetic-only deviation.
 * (2) Vanilla's 20-frame skip after a player-ship change is NOT mirrored for stat writes — server
 * truth applies immediately (the skip exists so vanilla's LOCAL recompute settles after control
 * transfer; wire truth has nothing to settle). The witness DOES hold those 20 frames (below).
 * (3) Witness reads the STANDING value in {@code renderInUICoords} (post-all-advances), not
 * pre-write in advance as the brief sketched: at tail position a pre-write read sees the vanilla
 * scripts' fresh same-frame write under BOTH correct and broken ordering (indistinguishable); the
 * standing value after our write discriminates — if it is not ours, something overwrote after us.
 * SUSPECT needs the local vanilla recompute to materially differ from the server bonus (else the
 * clobber is invisible anyway) and 90 sustained frames; one log-once sideline, never a throw.
 *
 * <p><b>Carrier-born replicas:</b> a bay-born fighter is no deployed
 * member on the server, so neither vanilla side script ever writes to it; the client's
 * member-first replica IS one, and the vanilla scripts (each frame) and this lane's mirror loops
 * would both write to it. The per-ship loops below skip every body
 * {@link CarrierBornFighterFx#isCarrierBorn} names, and this lane — the one plugin ordered after
 * the vanilla scripts — re-strips those ids each frame ({@link CarrierBornFighterFx
 * #stripSideScriptWritesAll}), on the inert (no-totals) path too. The witness never samples one.
 *
 * <p><b>Security:</b> wire-derived inputs are five u16 centi fields per side (bounded ≤655.35 after
 * /100); no wire bytes reach strings, paths, or allocation sizes — HUD text is locally composed.
 */
public class SideTotalsOverrideLane extends BaseEveryFrameCombatPlugin {

    private final PredictReconcileCombatPlugin predict;
    private CombatEngineAPI engine;
    private boolean reinstalled;

    // Witness state (render-phase standing-value check; class doc item (3) above)
    private float lastWrittenBonusSide0 = Float.NaN;
    private boolean wroteThisFrame;
    private boolean everWrote; // 1290 (81) N5: warmup gate — only an active->lost feed announces
    private int witnessHoldFrames;
    private int suspectFrames;
    private boolean suspectLogged;
    private ShipAPI prevPlayerShip;

    public SideTotalsOverrideLane(PredictReconcileCombatPlugin predict) {
        this.predict = predict;
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine; // re-runs on the first-advance self re-add; idempotent
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        if (!reinstalled) {
            reinstalled = true;
            engine.removePlugin(this);
            engine.addPlugin(this); // append-at-tail: after the vanilla side scripts from now on
            return;
        }
        ShipAPI playerShip = engine.getPlayerShip();
        if (playerShip != prevPlayerShip) {
            prevPlayerShip = playerShip;
            witnessHoldFrames = 20; // vanilla scripts skip 20 frames here — don't grade their gap
        }
        AwsdSnapshotReader.SideTotalsBlock totals = predict.serverSideTotalsOrNull();
        if (totals == null) {
            if (everWrote) {
                // 1290 (81) N5 (dossier warmup gating): a v8-server/cold-start null is normal and
                // stays silent; a feed that was ACTIVE and went null = AWSD dropped mid-match.
                FallbackAnnounce.announceOnce("n5-side-totals-lost",
                        "side-totals feed lost mid-match -> local vanilla recompute stands");
            }
            wroteThisFrame = false;
            suspectFrames = 0;
            predict.fighterHud.fighterFx.stripSideScriptWritesAll(); // Vanilla's local write stands otherwise
            return; // inert: v8 server or no AWSD frame yet ⇒ vanilla behavior untouched
        }
        CombatFleetManagerAPI manager0 = engine.getFleetManager(0);
        CombatFleetManagerAPI manager1 = engine.getFleetManager(1);
        if (manager0 == null || manager1 == null) {
            wroteThisFrame = false;
            return;
        }
        // Team switch (/switchteams): the wire's side0/side1 blocks are ABSOLUTE; manager0 is the
        // OWN team's (the own hull is always engine owner 0), so the own team's block is picked
        // by its LIVE absolute side — before this a switched seat read the enemy's totals.
        boolean ownIsAbs1 = predict.ownDescSide == 1;
        AwsdSnapshotReader.SideTotals own = ownIsAbs1 ? totals.side1() : totals.side0();
        AwsdSnapshotReader.SideTotals other = ownIsAbs1 ? totals.side0() : totals.side1();
        float coordTotal0 = own.coordTotalCenti() / 100.0F;
        float coordMax0 = own.coordMaxCenti() / 100.0F;
        float coordTotal1 = other.coordTotalCenti() / 100.0F;
        float coordMax1 = other.coordMaxCenti() / 100.0F;
        // EW crossed as vanilla's (int)-truncated values ×100 — /100 restores the ints exactly
        float pTotal = own.ewTotalCenti() / 100.0F;
        float pMax = own.ewMaxCenti() / 100.0F;
        float eTotal = other.ewTotalCenti() / 100.0F;
        float eMax = other.ewMaxCenti() / 100.0F;

        // ---- Coordinated Maneuvers (vanilla updateForSide, includeSelf hardcoded true ⇒ uniform
        // bonus; station modules INCLUDED in the apply loop, excluded only from the server sum)
        float bonus0 = Math.min(coordMax0, Math.max(0.0F, coordTotal0));
        float bonus1 = Math.min(coordMax1, Math.max(0.0F, coordTotal1));
        applyCoordBonus(manager0, bonus0);
        applyCoordBonus(manager1, bonus1);
        predict.fighterHud.fighterFx.stripSideScriptWritesAll(); // After vanilla's re-write, before any sim read
        lastWrittenBonusSide0 = bonus0;
        wroteThisFrame = true;
        everWrote = true;

        // ---- Electronic Warfare. Rewritten from the
        // functional spec. Manager 0 (own team, rating P = pTotal) takes the
        // penalty the other side's rating E earns, capped by Emax; manager 1 the mirror image.
        // Inactive = no write this frame. Rules = the pure functions below (ewIsActive /
        // ewSidePenalty / ewSideApplies); the per-ship clamp + range write = applyEwPenaltyTo.
        boolean ewActive = ewIsActive(pTotal, eTotal, ElectronicWarfareScript.BASE_MAXIMUM);
        float playerPenalty = 0.0F;
        float enemyPenalty = 0.0F;
        if (ewActive) {
            playerPenalty = ewSidePenalty(eTotal, eMax, pTotal);
            enemyPenalty = ewSidePenalty(pTotal, pMax, eTotal);
            if (ewSideApplies(playerPenalty, eTotal)) {
                applyEwPenalty(manager0, playerPenalty, eMax);
            }
            if (ewSideApplies(enemyPenalty, pTotal)) {
                applyEwPenalty(manager1, enemyPenalty, pMax);
            }
        }

        // ---- Comm Relay CP rate (side-level stat; vanilla writes the flat unconditionally when a
        // commander exists — the server crossed 0 for a commanderless side, net-identical to its
        // unmodify)
        applyCpRate(manager0, own.cpRateCenti() / 100.0F);
        applyCpRate(manager1, other.cpRateCenti() / 100.0F);

        // ---- HUD repaint: AFTER the stat writes, same frame — vanilla painted LOCAL numbers
        // earlier in the plugin list; a same-KEY maintain wins the frame.
        if (playerShip == null) {
            return;
        }
        int playerOwner = playerShip.getOwner();
        if (playerOwner == 0 || playerOwner == 1) {
            float hudTotal = playerOwner == 0 ? coordTotal0 : coordTotal1;
            float hudMax = playerOwner == 0 ? coordMax0 : coordMax1;
            float hudBonus = playerOwner == 0 ? bonus0 : bonus1;
            if (hudMax > 0.0F && hudTotal > 0.0F) {
                String icon = Global.getSettings()
                        .getSpriteName("ui", "icon_tactical_coordinated_maneuvers");
                engine.maintainStatusForPlayerShip(CoordinatedManeuversScript.KEY_STATUS, icon,
                        "Coordinated Maneuvers: " + (int) Math.min(hudMax, hudTotal) + "%",
                        "+" + (int) hudBonus + "% top speed", false);
            }
        }
        if (ewActive) {
            String icon = Global.getSettings()
                    .getSpriteName("ui", "icon_tactical_electronic_warfare");
            if (playerPenalty > 0.0F || eTotal > 0.0F) {
                String data = "-" + (int) playerPenalty + "% weapon range"
                        + (eMax <= playerPenalty ? " (max)" : "");
                if (eMax <= 0.0F) {
                    data = "fully neutralized";
                }
                engine.maintainStatusForPlayerShip(ElectronicWarfareScript.KEY_STATUS_ENEMY_RATING,
                        icon, "Enemy ECM rating: " + (int) eTotal + "%", data, eMax > 0.0F);
            }
            if (enemyPenalty > 0.0F || pTotal > 0.0F) {
                String data = "-" + (int) enemyPenalty + "% enemy weapon range"
                        + (pMax <= enemyPenalty ? " (max)" : "");
                if (pMax <= 0.0F) {
                    data = "fully neutralized";
                }
                engine.maintainStatusForPlayerShip(ElectronicWarfareScript.KEY_STATUS, icon,
                        "ECM rating: " + (int) pTotal + "%", data, false);
            }
            if (playerPenalty > 0.0F && eMax > 0.0F) {
                int eccm = 100 - Math.round(playerShip.getMutableStats().getDynamic()
                        .getValue("electronic_warfare_penalty_mod", 100.0F));
                eccm = Math.max(0, Math.min(100, eccm));
                if (eccm != 0) {
                    engine.maintainStatusForPlayerShip(ElectronicWarfareScript.KEY_STATUS2, icon,
                            "On-board ECCM", eccm + "% enemy ecm neutralized", false);
                }
            }
        }
        // Vanilla CP HUD: side 0 only, gated on the POST-write mult (vanilla reuses the
        // coordinated-maneuvers icon here — quirk preserved)
        float withMult = manager0.getTaskManager(false).getCPRateMult();
        if (withMult != 1.0F) {
            String icon = Global.getSettings()
                    .getSpriteName("ui", "icon_tactical_coordinated_maneuvers");
            int pct = Math.round((withMult - 1.0F) * 100.0F);
            String data = (withMult < 1.0F ? "" : "+") + pct + "% cp recovery rate";
            engine.maintainStatusForPlayerShip(CommRelayScript.KEY_STATUS, icon,
                    "command network", data, withMult < 1.0F);
        }
    }

    /** Vanilla {@code updateForSide}'s apply loop (includeSelf ⇒ the uniform bonus on every
     *  non-wing deployed entry) PLUS the switched replicas ({@link #isFlippedOnSide}). */
    private void applyCoordBonus(CombatFleetManagerAPI manager, float bonus) {
        CarrierBornFighterFx fx = predict.fighterHud.fighterFx;
        for (DeployedFleetMemberAPI member : manager.getDeployedCopyDFM()) {
            if (member == null || member.isFighterWing()) {
                continue;
            }
            ShipAPI ship = member.getShip();
            if (ship == null || fx.isCarrierBorn(ship)) {
                continue; // The server's bay-born fighter never carries it
            }
            ship.getMutableStats().getMaxSpeed()
                    .modifyPercent(CoordinatedManeuversScript.BONUS_ID, bonus);
        }
        List<ShipAPI> flipped = predict.teamChange.flippedBodies();
        for (int i = 0, n = flipped.size(); i < n; i++) {
            ShipAPI ship = flipped.get(i);
            if (isFlippedOnSide(ship, manager, fx)) {
                ship.getMutableStats().getMaxSpeed()
                        .modifyPercent(CoordinatedManeuversScript.BONUS_ID, bonus);
            }
        }
    }

    /** A body {@code RemoteTeamFlip} re-owned onto {@code manager}'s side
     *  sits in NEITHER local deployed set (the public API evicts, it cannot re-add — the server's
     *  join is an engine-internal registration, B-denied here), so the two per-ship walks visit it from
     *  {@code TeamChangeLane.flippedBodies()} under the same terms a deployed entry meets: this
     *  side's owner, not a fighter, not carrier-born (the list itself is pruned of the dead).
     *  The own hull never needs this: it stays in manager 0's set across its own switch. */
    private static boolean isFlippedOnSide(ShipAPI ship, CombatFleetManagerAPI manager,
            CarrierBornFighterFx fx) {
        return ship.getOwner() == manager.getOwner() && !ship.isFighter()
                && !fx.isCarrierBorn(ship);
    }

    /** A side's EW ship set: every non-wing deployed entry (minus carrier-born replicas) PLUS the
     *  switched replicas ({@link #isFlippedOnSide}), each through {@link #applyEwPenaltyTo} — LOCAL
     *  per-ship ECCM dyn stats are captain-derived and symmetric, so reading them off the replica
     *  is faithful. */
    private void applyEwPenalty(CombatFleetManagerAPI manager, float penalty, float maxPenalty) {
        CarrierBornFighterFx fx = predict.fighterHud.fighterFx;
        for (DeployedFleetMemberAPI member : manager.getDeployedCopyDFM()) {
            if (member == null || member.isFighterWing()) {
                continue;
            }
            ShipAPI ship = member.getShip();
            if (ship == null || fx.isCarrierBorn(ship)) {
                continue; //
            }
            applyEwPenaltyTo(ship, penalty, maxPenalty);
        }
        List<ShipAPI> flipped = predict.teamChange.flippedBodies(); //
        for (int i = 0, n = flipped.size(); i < n; i++) {
            ShipAPI ship = flipped.get(i);
            if (isFlippedOnSide(ship, manager, fx)) {
                applyEwPenaltyTo(ship, penalty, maxPenalty);
            }
        }
    }

    /** One ship's share of its side's EW penalty, rewritten from the functional spec:
     *  the side penalty scaled by the ship's
     *  {@code Stats.ELECTRONIC_WARFARE_PENALTY_MULT}, passed as the base value through its
     *  {@code Stats.ELECTRONIC_WARFARE_PENALTY_MOD} (the ECCM reduction), then
     *  {@link #ewShipPenaltyClamp}; the result lands as one multiplicative modifier
     *  ({@link #ewRangeMult}) under {@code ElectronicWarfareScript.PENALTY_ID} on the ballistic,
     *  energy and missile weapon-range bonuses — the same id
     *  {@code CarrierBornFighterFx.stripSideScriptWrites} un-modifies. The dynamic stats are read off
     *  the LOCAL replica (captain-derived, so the same on both ends).
     *
     * */
    private static void applyEwPenaltyTo(ShipAPI ship, float penalty, float maxPenalty) {
        MutableShipStatsAPI stats = ship.getMutableStats();
        DynamicStatsAPI dynamic = stats.getDynamic();
        float scaled = ewShipPenaltyBase(penalty,
                dynamic.getValue(Stats.ELECTRONIC_WARFARE_PENALTY_MULT));
        float modded = dynamic.getValue(Stats.ELECTRONIC_WARFARE_PENALTY_MOD, scaled);
        float shipPenalty = ewShipPenaltyClamp(modded, maxPenalty, penalty,
                dynamic.getValue(Stats.ELECTRONIC_WARFARE_PENALTY_MAX_FOR_SHIP_MOD, 0.0F));
        float mult = ewRangeMult(shipPenalty);
        stats.getBallisticWeaponRangeBonus().modifyMult(ElectronicWarfareScript.PENALTY_ID, mult);
        stats.getEnergyWeaponRangeBonus().modifyMult(ElectronicWarfareScript.PENALTY_ID, mult);
        stats.getMissileWeaponRangeBonus().modifyMult(ElectronicWarfareScript.PENALTY_ID, mult);
    }

    /** EW is active this frame iff the combined rating, clamped to {@code baseMaximum}
     *  ({@code ElectronicWarfareScript.BASE_MAXIMUM} at the call), rounds above zero. Ratings are
     *  wire totals: whole numbers in 0..655 (u16 centi / 100), never negative.
     *
     * */
    static boolean ewIsActive(float ratingSide0, float ratingSide1, float baseMaximum) {
        return Math.round(Math.min(baseMaximum, ratingSide0 + ratingSide1)) > 0;
    }

    /** The penalty one side suffers: the whole part of the opposing rating capped by the opposing
     *  side's max penalty; when this side has its own rating ({@code ownRating > 0}) that is scaled
     *  by the opposing share {@code opposing / (opposing + own)} and rounded (half up). Result is a
     *  whole number &gt;= 0 for the wire's non-negative inputs.
     *
     * */
    static float ewSidePenalty(float opposingRating, float opposingMax, float ownRating) {
        float penalty = (int) Math.min(opposingRating, opposingMax);
        if (ownRating > 0.0F) {
            penalty = Math.round(penalty * (opposingRating / (opposingRating + ownRating)));
        }
        return penalty;
    }

    /** A side's ships are written when its penalty is positive OR the opposing side has any
     *  rating at all (a 0 side penalty is then still written through the per-ship path).
     *
     * */
    static boolean ewSideApplies(float sidePenalty, float opposingRating) {
        return sidePenalty > 0.0F || opposingRating > 0.0F;
    }

    /** Per-ship step 1 — the side penalty times the ship's penalty multiplier (the value the
     *  ECCM mod then sees as its base).
     *
     * */
    static float ewShipPenaltyBase(float sidePenalty, float penaltyMult) {
        return sidePenalty * penaltyMult;
    }

    /** Per-ship step 2 — the post-mod value floored at 0, then capped at
     *  {@code maxPenalty + sidePenalty * maxForShipMod} (the ship's own cap raise; 0 = none).
     *
     * */
    static float ewShipPenaltyClamp(float moddedPenalty, float maxPenalty, float sidePenalty,
            float maxForShipMod) {
        float floored = Math.max(0.0F, moddedPenalty);
        return Math.min(floored, maxPenalty + sidePenalty * maxForShipMod);
    }

    /** A percent penalty as the weapon-range multiplier {@code 1 - penalty / 100}.
     *
     * */
    static float ewRangeMult(float shipPenalty) {
        return 1.0F - shipPenalty / 100.0F;
    }

    private void applyCpRate(CombatFleetManagerAPI manager, float cpRate) {
        manager.getTaskManager(false).getCPRateModifier()
                .modifyFlat(CommRelayScript.BONUS_ID, cpRate);
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (!wroteThisFrame) {
            return;
        }
        wroteThisFrame = false;
        if (suspectLogged || engine == null) {
            return;
        }
        if (witnessHoldFrames > 0) {
            witnessHoldFrames--;
            suspectFrames = 0;
            return;
        }
        ShipAPI sample = firstEligibleShip(engine.getFleetManager(0));
        if (sample == null) {
            return;
        }
        MutableStat.StatMod mod = sample.getMutableStats().getMaxSpeed()
                .getPercentStatMod(CoordinatedManeuversScript.BONUS_ID);
        float standing = mod == null ? Float.NaN : mod.value;
        if (Math.abs(standing - lastWrittenBonusSide0) <= 0.01F) {
            suspectFrames = 0; // our write stands — ordering healthy
            return;
        }
        float localBonus = localCoordBonusSide0();
        if (Math.abs(localBonus - lastWrittenBonusSide0) <= 0.25F) {
            suspectFrames = 0; // local ≈ server: a clobber is invisible, don't grade it
            return;
        }
        suspectFrames++;
        if (suspectFrames >= 90) {
            suspectLogged = true;
            PilotWatchLog.info("cc-sidetotals ordering=SUSPECT standing=" + standing
                    + " ours=" + lastWrittenBonusSide0 + " local=" + localBonus
                    + " frames=" + suspectFrames);
        }
    }

    private ShipAPI firstEligibleShip(CombatFleetManagerAPI manager) {
        if (manager == null) {
            return null;
        }
        CarrierBornFighterFx fx = predict.fighterHud.fighterFx;
        for (DeployedFleetMemberAPI member : manager.getDeployedCopyDFM()) {
            if (member == null || member.isFighterWing()) {
                continue;
            }
            ShipAPI ship = member.getShip();
            if (ship != null && !fx.isCarrierBorn(ship)) { // A stripped body reads NaN
                return ship;
            }
        }
        return null;
    }

    /** Witness comparator: the Coordinated Maneuvers bonus a LOCAL recompute would leave on every
     *  non-fighter side-0 ship's max-speed percent modifier ({@code CoordinatedManeuversScript.BONUS_ID}),
     *  from the local replica — rewritten from the functional spec.
     *  Inputs: manager 0's fleet commanders ({@code Stats.COORDINATED_MANEUVERS_MAX}, default 0),
     *  its deployed members that are neither fighter wings nor station modules
     *  ({@code Stats.COORDINATED_MANEUVERS_FLAT}, default 0), and the objectives side 0 owns of type
     *  {@code BattleObjectives.NAV_BUOY}; the law = {@link #coordCap} / {@link #coordSumCounts} /
     *  {@link #coordSum} / {@link #coordBonus}. Null members / ships / commanders (or a commander
     *  without stats) are skipped.
     *  Called only from {@code renderInUICoords} once the standing value differs from ours.
     *
     * */
    private float localCoordBonusSide0() {
        CombatFleetManagerAPI manager = engine.getFleetManager(0);
        if (manager == null) {
            return 0.0F;
        }
        List<PersonAPI> commanders = manager.getAllFleetCommanders();
        int commanderCount = commanders == null ? 0 : commanders.size();
        float[] commanderMaxMods = new float[commanderCount];
        int counted = 0;
        for (int i = 0; i < commanderCount; i++) {
            PersonAPI commander = commanders.get(i);
            if (commander == null || commander.getStats() == null) {
                continue;
            }
            commanderMaxMods[counted++] = commander.getStats().getDynamic()
                    .getValue(Stats.COORDINATED_MANEUVERS_MAX, 0.0F);
        }
        float cap = coordCap(CoordinatedManeuversScript.BASE_MAXIMUM, commanderMaxMods, counted);
        float memberFlat = 0.0F;
        for (DeployedFleetMemberAPI member : manager.getDeployedCopyDFM()) {
            if (member == null || !coordSumCounts(member.isFighterWing(), member.isStationModule())) {
                continue;
            }
            ShipAPI ship = member.getShip();
            if (ship != null) {
                memberFlat += ship.getMutableStats().getDynamic()
                        .getValue(Stats.COORDINATED_MANEUVERS_FLAT, 0.0F);
            }
        }
        int ownedNavBuoys = 0;
        List<BattleObjectiveAPI> objectives = engine.getObjectives();
        if (objectives != null) {
            for (BattleObjectiveAPI objective : objectives) {
                if (objective != null && objective.getOwner() == manager.getOwner()
                        && BattleObjectives.NAV_BUOY.equals(objective.getType())) {
                    ownedNavBuoys++;
                }
            }
        }
        return coordBonus(cap,
                coordSum(memberFlat, ownedNavBuoys, CoordinatedManeuversScript.PER_BUOY));
    }

    /** The side's Coordinated Maneuvers cap: the largest {@code baseMaximum + maxMod} over the
     *  first {@code count} commander entries; no commander ({@code count <= 0}) = 0.
     *
     * */
    static float coordCap(float baseMaximum, float[] commanderMaxMods, int count) {
        if (count <= 0) {
            return 0.0F;
        }
        float cap = baseMaximum + commanderMaxMods[0];
        for (int i = 1; i < count; i++) {
            cap = Math.max(cap, baseMaximum + commanderMaxMods[i]);
        }
        return cap;
    }

    /** Sum membership: a deployed member adds its flat share unless it is a fighter wing or a
     *  station module.
     *
     * */
    static boolean coordSumCounts(boolean fighterWing, boolean stationModule) {
        return !fighterWing && !stationModule;
    }

    /** The side's sum: the members' flat shares plus {@code perBuoy} per owned nav buoy.
     *
     * */
    static float coordSum(float memberFlatTotal, int ownedNavBuoys, float perBuoy) {
        return memberFlatTotal + ownedNavBuoys * perBuoy;
    }

    /** The uniform bonus: 0 when the cap or the sum is not positive, else {@code min(cap, sum)}
     *  (no per-ship own-share subtraction).
     *
     * */
    static float coordBonus(float cap, float sum) {
        if (cap <= 0.0F || sum <= 0.0F) {
            return 0.0F;
        }
        return Math.min(cap, sum);
    }
}
