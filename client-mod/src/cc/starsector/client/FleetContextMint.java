package cc.starsector.client;

import cc.starsector.server.match.AllSkillsFleetLaw;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.skills.BaseSkillEffectDescription;
import com.fs.starfarer.api.impl.campaign.skills.PhaseCorps;

import com.fs.starfarer.campaign.fleet.FleetData;

import java.util.List;

/**
 * W4 all-skills side-0 fleet-context MINTER, CLIENT end: the TYPED
 * twin of the server's reflective {@code AllSkillsFleetData.mint} — java.lang.reflect is
 * classloader-B-DENIED in the mod jar, so this end mints with typed calls only.
 * The engine-typed {@link FleetData} reference is B-LEGAL (the adapter-class law,
 * as for {@code MissileSplitLane} et al): an UNOBFUSCATED campaign class name in
 * 0.98a-RC8 whose {@code (String, String)} ctor is campaign-free with both args null. Gate 2
 * rides the generated typed {@link FleetContextToken} instead of the server's Proxy.
 *
 * <p>The LAW (walk/echo/Mint shape) lives in the shared {@link AllSkillsFleetLaw}; the mint
 * DISCIPLINE here mirrors the server class step-for-step (pre-name, repair-tracker +
 * mothball guards, ONE lists-only sync + count assert, {@code setForceNoSync} freeze, token
 * install + {@code refreshCharacterStatsEffects(false)} re-derive, engine-statics echo).
 * The {@code fleet_ctx} echo EQUALITY assert at watch grading is the anti-drift guard across
 * the two mint vehicles — byte-identical echo bodies or the watch fails. FAIL-CLOSED
 * throughout: a half-built context is the defect re-minted (silent maxBonus).
 */
public final class FleetContextMint {

    private FleetContextMint() {
    }

    /** The engine-backed station read for the shared walk law ({@code getVariant(id).isStation()}). */
    public static AllSkillsFleetLaw.StationCheck stationCheck() {
        SettingsAPI settings = settingsOrThrow();
        return variantId -> {
            ShipVariantAPI variant = settings.getVariant(variantId);
            if (variant == null) {
                throw new IllegalStateException("getVariant('" + variantId + "') resolved null");
            }
            return variant.isStation();
        };
    }

    /**
     * Build the side-0 fleet context: shadow-mint the roster, fab the engine {@link FleetData},
     * freeze its sync, install the gate-2 token on {@code union}'s stats and re-derive its
     * character-stats effects — the typed mirror of {@code AllSkillsFleetData.mint} (that
     * class's javadoc carries the full design law; keep the two in step).
     */
    public static AllSkillsFleetLaw.Mint mint(List<String> variantIds, PersonAPI union) {
        if (variantIds == null || variantIds.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context mint: empty variant list");
        }
        if (union == null) {
            throw new IllegalStateException("all-skills fleet-context mint: null union person");
        }
        SettingsAPI settings = settingsOrThrow();

        // The fab: the engine's OWN FleetData, campaign-free with null name sources.
        FleetDataAPI fab = new FleetData((String) null, (String) null);
        for (int i = 0; i < variantIds.size(); i++) {
            FleetMemberAPI shadow =
                    settings.createFleetMember(FleetMemberType.SHIP, variantIds.get(i));
            if (shadow == null) {
                throw new IllegalStateException("createFleetMember(SHIP, '"
                        + variantIds.get(i) + "') returned null");
            }
            // Pre-name: FleetData.addFleetMember mints a name for a null-named member via
            // ShipNameStore/faction sources — a campaign seam we keep unexercised headless.
            shadow.setShipName("CC-SHADOW-" + (i + 1));
            // Fail-closed twin of FleetData.isInInvalidStateDueToGameLoadOrder:
            // a tracker-less member makes the lists-sync EMPTY the roster silently.
            if (shadow.getRepairTracker() == null) {
                throw new IllegalStateException("shadow member '" + variantIds.get(i)
                        + "' has a null RepairTracker — the fab lists-sync would silently"
                        + " empty the roster (invalid-load-order guard)");
            }
            if (shadow.isMothballed()) {
                throw new IllegalStateException("shadow member '" + variantIds.get(i)
                        + "' minted MOTHBALLED — it would silently drop from every sum");
            }
            fab.addFleetMember(shadow);
        }

        // ONE members-lists-only sync (the getSortedMembers idiom), then freeze forever:
        // the full sync path re-crews/re-derives — campaign machinery the fab must not run.
        fab.setOnlySyncMemberLists(true);
        int synced = fab.getNumMembers();
        fab.setOnlySyncMemberLists(false);
        fab.setForceNoSync(true);
        if (synced != variantIds.size()) {
            throw new IllegalStateException("fab FleetData synced " + synced
                    + " members for " + variantIds.size() + " shadow mints — a short roster"
                    + " inflates every threshold bonus (fail-closed)");
        }

        // Gate 2: the fail-closed CampaignFleetAPI token on the union commander's stats.
        FleetContextToken token = new FleetContextToken(fab);
        installFleetToken(union, token);

        return new AllSkillsFleetLaw.Mint(fab, token, engineEcho(fab, union, variantIds.size()));
    }

    /**
     * For the editor's skills toggle ({@code EditorStats}): a campaign-free ONE-member fab around a DETACHED
     * member the editor built from an in-memory variant — {@link #mint}'s steps on the member itself (pre-name, the
     * tracker + mothball guards, ONE lists-only sync + count assert, the {@code setForceNoSync} freeze, the gate-2
     * token on {@code commander}) so {@code FleetMember.updateStats} applies the captain's personal set (its gate is
     * a member with a fleet-data member list) and the commander's fleet-wide set resolves its thresholds over this
     * one ship — the bastion seat shape (a one-ship side). Never a match fleet: the member and the commander are the
     * editor's own detached objects; {@code addFleetMember} puts a default steady captain on a captain-less member
     * (the caller's {@code setCaptain} replaces it).
     */
    public static FleetDataAPI fabForMember(FleetMemberAPI member, PersonAPI commander) {
        if (member == null) {
            throw new IllegalStateException("editor fleet-context fab: null member");
        }
        if (commander == null) {
            throw new IllegalStateException("editor fleet-context fab: null commander person");
        }
        settingsOrThrow();
        FleetDataAPI fab = new FleetData((String) null, (String) null);
        if (member.getShipName() == null) {
            member.setShipName("CC-EDITOR");   // pre-name: a null name walks ShipNameStore (a campaign seam)
        }
        if (member.getRepairTracker() == null) {
            throw new IllegalStateException("editor member has a null RepairTracker — the fab lists-sync would"
                    + " silently empty the roster (invalid-load-order guard)");
        }
        if (member.isMothballed()) {
            throw new IllegalStateException("editor member minted MOTHBALLED — it would silently drop from every sum");
        }
        fab.addFleetMember(member);
        fab.setOnlySyncMemberLists(true);
        int synced = fab.getNumMembers();
        fab.setOnlySyncMemberLists(false);
        fab.setForceNoSync(true);
        if (synced != 1) {
            throw new IllegalStateException("editor fab FleetData synced " + synced
                    + " members for one editor member (fail-closed)");
        }
        installFleetToken(commander, new FleetContextToken(fab));
        return fab;
    }

    /**
     * Install {@code mint}'s gate-2 token on another commander person's stats (the mission
     * build's engine-default commander is a SECOND person carrying the union sheet) and
     * re-derive its character-stats effects.
     */
    public static void installCommanderFleetToken(AllSkillsFleetLaw.Mint mint, PersonAPI person) {
        if (mint == null || person == null) {
            throw new IllegalStateException("all-skills fleet-context: token install with "
                    + (mint == null ? "no mint" : "no person") + " — fail-closed");
        }
        installFleetToken(person, (CampaignFleetAPI) mint.fleetToken);
    }

    private static void installFleetToken(PersonAPI person, CampaignFleetAPI token) {
        MutableCharacterStatsAPI stats = person.getStats();
        if (stats == null) {
            throw new IllegalStateException("union person getStats() returned null");
        }
        stats.setFleet(token);
        // Character-level threshold effects (AuxiliarySupport family) first applied at skill
        // set with a null fleet — re-derive them now that both gates resolve. Engine idiom:
        // FleetData.setCommander does exactly this re-derive on commander change.
        stats.refreshCharacterStatsEffects(false);
    }

    /**
     * The canonical echo, computed by the ENGINE's own statics over the fab (never re-derived
     * project-side) and folded through the shared {@link AllSkillsFleetLaw#echoLine} — the
     * server twin computes the identical line reflectively, and grading asserts equality.
     */
    private static String engineEcho(FleetDataAPI fab, PersonAPI union, int members) {
        MutableCharacterStatsAPI unionStats = union.getStats();
        float phaseDp = BaseSkillEffectDescription.getPhaseOP(fab, unionStats);
        float combatDp = BaseSkillEffectDescription.getTotalCombatOP(fab, unionStats);
        float bays = BaseSkillEffectDescription.getNumFighterBays(fab);
        float phaseThr = BaseSkillEffectDescription.PHASE_OP_THRESHOLD;
        BaseSkillEffectDescription calc = new BaseSkillEffectDescription();
        float pcSpeed = calc.getThresholdBasedRoundedBonus(
                PhaseCorps.PHASE_SPEED_BONUS, phaseDp, phaseThr);
        float pcPeak = calc.getThresholdBasedRoundedBonus(
                PhaseCorps.PEAK_TIME_BONUS, phaseDp, phaseThr);
        return AllSkillsFleetLaw.echoLine(members, phaseDp, phaseThr, pcSpeed, pcPeak,
                combatDp, bays);
    }

    private static SettingsAPI settingsOrThrow() {
        SettingsAPI settings = Global.getSettings();
        if (settings == null) {
            throw new IllegalStateException("Global.getSettings() null at all-skills"
                    + " fleet-context mint — a game-free context cannot honor the knob");
        }
        return settings;
    }
}
