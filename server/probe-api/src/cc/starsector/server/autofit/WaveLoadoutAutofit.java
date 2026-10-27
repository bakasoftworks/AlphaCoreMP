package cc.starsector.server.autofit;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.fleets.DefaultFleetInflater;
import com.fs.starfarer.api.impl.campaign.fleets.DefaultFleetInflaterParams;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wave loadout VARIETY the vanilla way: a wave hull inflated by the engine's OWN campaign fleet inflater over a
 * one-member fleet shell, headless. The goal variant becomes the single member of an empty fleet of the wave's
 * faction, the published {@link DefaultFleetInflater} runs on that fleet exactly as it runs on a spawned campaign
 * fleet (the arsenal buckets, the variety draw, the autofit, the s-mod and d-mod passes are ALL its code), and what
 * comes back is read off the member as {@code .variant}-shaped JSON. Nothing of the inflater is re-implemented here:
 * the picking math is bit-identical to a campaign fleet's because it IS the campaign fleet's.
 *
 *
 *
 * <p>Lives in the probe-api compile unit because it is typed against the game API (the unit's charter,
 * {@code server/build.ps1}); the product unit ({@code server/src}) stays game-jar-free and calls the statics here BY
 * NAME ({@code WaveLoadoutInflater.AUTOFIT_CLASS}) with JDK-typed arguments, receiving the inflated variant as
 * {@code .variant}-shaped JSON text — the exact shape {@code VariantCatalogStore.parseVariantText} reads and the VDEF
 * codec carries, so what the server registers is byte-for-byte what the wire delivers (server authority:
 *the client never re-derives).
 *
 * <p>The three things this shell decides FOR the inflater: the per-member stream key — the inflater re-seeds on the
 * member's ship NAME, so the authored wave SLOT ("CC WAVE w.i", the stable per-member identity the schedule already
 * carries) is written as the name before the call (the display name is picked at spawn, after inflation); the quality
 * ({@link #defaultQuality}: a real market's quality, stood in for per faction; the {@code wave.quality} knob overrides
 * it); and the {@code wave.dmods=false} dial — the inflater's d-mod pass (quality-driven, after the fit, never on a
 * station) is undone on the body ({@link #undoDModPass}), the fit itself untouched. A non-player autofit CLEARS the
 * weapon groups; the wire carries weapons only inside groups, so the engine's own {@code autoGenerateWeaponGroups}
 * (the generator a groupless AI variant gets at ship build) runs here before serialization. D-mods ride the wire as
 * ordinary {@code hullMods} + {@code permaMods} entries and the D twin as the {@code hullId} — both are in the u16 id
 * tables (the catalog walks {@code getAllShipHullSpecs}/{@code getAllHullModSpecs}), and the client's own variant loader
 * resolves them like any vanilla (D) variant.
 */
public final class WaveLoadoutAutofit {

    /** Skip tokens ({@link #skipReasonOrNull}); the product lane logs them verbatim. */
    public static final String SKIP_GOAL_UNKNOWN = "goal_unknown";
    public static final String SKIP_NO_AUTOFIT = "no_autofit";
    public static final String SKIP_NO_AUTOFIT_UNLESS_PLAYER = "no_autofit_unless_player";
    public static final String SKIP_FIGHTER = "fighter";

    /**
     * The ONE JSON {@link #inflateToVariantJson} returns is the parent's {@code .variant} shape plus this key — a JSON
     * array of the station modules' OWN {@code .variant} bodies (the engine's autofit refits them in place),
     *in the
     * SAME order as the parent's {@code modules} map (slot ids sorted, the
     * {@code EngineBornGrowthSeam.stationModuleVariantIds} order law). Each module body's id is {@code <parentId>} +
     * {@link #MODULE_ID_INFIX} + its index. The product lane registers, fans and spools the module bodies BEFORE the
     * parent: both engines resolve a module id lazily through the variant registry,
     * so a parent is never fieldable before
     * its modules are known. A module-less hull carries an empty array.
     */
    public static final String MODULE_BODIES_KEY = "moduleBodies";
    public static final String MODULE_ID_INFIX = "_m";

    private WaveLoadoutAutofit() {
    }

    // ------------------------------------------------------------------ the by-name statics

    /** The faction the booted bare sector holds ({@code FactionManager} ctor loads every spec —
     * recon L3 headline), else the sector-free twin {@code createBaseFaction}; null = no such id. */
    public static FactionAPI factionOrNull(String factionId) {
        // Probe: FactionManager.getFaction hands an UNKNOWN id a spec-less placeholder (getDoctrine
        // NPEs) — the loaded spec table is the truth (category persean_league vs faction id "persean").
        if (Global.getSettings() != null && Global.getSettings().getFactionSpec(factionId) == null) {
            return null;
        }
        FactionAPI f = null;
        if (Global.getSector() != null) {
            f = Global.getSector().getFaction(factionId);
        }
        if (f == null && Global.getSettings() != null) {
            f = Global.getSettings().createBaseFaction(factionId);
        }
        return f;
    }

    /**
     * Vanilla's own per-spawner quality override, where one exists — {@code FleetFactoryV3} skips
     * the market update and takes the number verbatim. Remnant fleets are BUILT at
     * 1.0 by both remnant managers ({@code RemnantSeededFleetManager},
     * {@code RemnantStationFleetManager}: the {@code Float qualityOverride} ctor arg,
     * {@code FleetParamsV3}). No other wave-category faction has a spawner-side override.
     *
     *
     */
    private static final java.util.Map<String, Float> VANILLA_QUALITY_OVERRIDE =
            java.util.Map.of("remnant", 1.0F, "omega", 1.0F, "derelict", 0.5F);

    /**
     * Faction-pinned quality:
     * a vanilla fleet's quality is its faction's best ship PRODUCER's
     * {@code production_quality_mod} (keyed faction + econ group) — Orbital
     * Works +0.2, corrupted nanoforge +0.2 / pristine +0.5,
     * NO producer = {@code qualityPenaltyForImports} −0.25
     * — pinned per faction from {@code data/campaign/econ/*.json}
     * (nanoforge placements are hardcoded there, never procedural): hegemony Chicomoztoc pristine,
     * persean Kazeron pristine, tritachyon Culann corrupted, sindrian_diktat Sindria corrupted,
     * luddic_church Asher corrupted, pirates Kapteyn bare Orbital Works, luddic_path none.
     */
    private static final java.util.Map<String, Float> CORE_PRODUCER_QUALITY = java.util.Map.of(
            "hegemony", 0.7F, "persean", 0.7F, "tritachyon", 0.4F, "sindrian_diktat", 0.4F,
            "luddic_church", 0.4F, "pirates", 0.2F, "luddic_path", -0.25F);

    /**
     * The spawn market's {@code fleet_quality_mod} stability term at a STABLE capital — stability 10:
     * {@code (stability − 5) × 0.05} ({@code FleetFactoryV3.getShipQualityModForStability}, applied by
     * {@code PopulationAndInfrastructure}). The stand-in models the faction's own capital as
     * the wave's source; the {@code wave.quality} knob overrides the whole number when an operator
     * wants another market.
     */
    private static final float CORE_MARKET_STABILITY_TERM = 0.25F;

    /**
     * The quality float a vanilla fleet of this faction inflates at (the inflater's tier-probability and
     * UPGRADE-coin input). Vanilla's own override wins; otherwise the
     * faction's pinned core PRODUCER ({@link #CORE_PRODUCER_QUALITY}) + a stable capital's stability
     * term ({@link #CORE_MARKET_STABILITY_TERM}) + the doctrine contribution, added once — the same
     * single appearance a market-backed vanilla fleet gets (the market-less
     * {@code BASE_QUALITY_WHEN_NO_MARKET} is only the fallback for an
     * unpinned faction — as the default it fielded a d-mod on EVERY hegemony hull in a live match).
     * NOT the engine's economy-less number (that is {@code qualityPenaltyForImports} -0.25
     * + 0.5 = 0.25) — a bare-sector market is not the field.
     */
    public static float defaultQuality(String factionId) {
        Float vanillaOverride = VANILLA_QUALITY_OVERRIDE.get(factionId);
        if (vanillaOverride != null) {
            return vanillaOverride;
        }
        float doctrine = requireFaction(factionId).getDoctrine().getShipQualityContribution();
        Float producer = CORE_PRODUCER_QUALITY.get(factionId);
        if (producer == null) {
            // A faction outside the pinned table: the market-less vanilla fleet (the number before the faction pins).
            return FleetFactoryV3.BASE_QUALITY_WHEN_NO_MARKET + doctrine;
        }
        // hegemony 0.95 (≈ 1 in 4 hulls carries a d-mod), persean 1.075, tritachyon 1.15, diktat 0.90,
        // church 0.65, pirates 0.45 (every hull 1–4), path 0 (every hull 3–5) — vanilla's own d-mod law
        // reads the number; the lane's wave.qualityPerWave ramp climbs from it.
        return Math.max(0F, producer + CORE_MARKET_STABILITY_TERM + doctrine);
    }

    /** Boot-row datum: the faction's known-weapon census (0 = the pool is empty, inflation inert). */
    public static int knownWeaponCount(String factionId) {
        return requireFaction(factionId).getKnownWeapons().size();
    }

    /** The faction's known weapon ids after tag expansion — the pool {@link #inflateToVariantJson}
     * draws from; the engine proof's membership set ({@code RealEngineWaveInflateProbeMain} leg C). */
    public static List<String> knownWeaponIds(String factionId) {
        return new ArrayList<>(requireFaction(factionId).getKnownWeapons());
    }

    /** The faction's known fighter wing ids (the same proof's wing membership set). */
    public static List<String> knownFighterIds(String factionId) {
        return new ArrayList<>(requireFaction(factionId).getKnownFighters());
    }

    /**
     * The inflater's own per-member skip law, asked BEFORE the call so the lane can log the reason
     * ({@code no_autofit} on hull or variant, {@code no_autofit_unless_player} for a non-player faction)
     *plus this lane's one bound (a fighter-size "hull" =
     * a wing-only row). A module-bearing hull (the derelict mothership) is inflatable since Phase 2b — its modules
     * ride {@link #MODULE_BODIES_KEY}. Null = inflatable.
     */
    public static String skipReasonOrNull(String goalVariantId) {
        ShipVariantAPI goal = resolveGoalOrNull(goalVariantId);
        if (goal == null) {
            return SKIP_GOAL_UNKNOWN;
        }
        if (goal.getHullSpec().hasTag("no_autofit") || goal.hasTag("no_autofit")) {
            return SKIP_NO_AUTOFIT;
        }
        if (goal.getHullSpec().hasTag("no_autofit_unless_player")
                || goal.hasTag("no_autofit_unless_player")) {
            return SKIP_NO_AUTOFIT_UNLESS_PLAYER;
        }
        if (goal.getHullSpec().getHullSize() == ShipAPI.HullSize.FIGHTER) {
            return SKIP_FIGHTER;
        }
        return null;
    }

    /**
     * Inflate ONE hull: the engine's {@link DefaultFleetInflater#inflate} over a one-member fleet of
     * {@code factionId} whose member is the goal variant, named {@code memberKey}. Returns the inflated variant as
     * {@code .variant}-shaped JSON text under {@code newVariantId} (its module bodies under
     * {@link #MODULE_BODIES_KEY}). Throws (IllegalArgumentException / IllegalStateException / the engine's own
     * RuntimeException) on any fault — the caller falls back to the stock goal loud, never a half-fit.
     *
     * @param qualityOrNaN NaN = {@link #defaultQuality}
     * @param averageSModsOrNeg negative = unset (the inflater's null: no s-mods, no s-mod stream draw)
     * @param dmods false = the {@code wave.dmods=false} knob: the inflater's d-mod pass undone
     *              ({@link #undoDModPass}); the fit is identical either way (the pass runs after it)
     */
    public static String inflateToVariantJson(String goalVariantId, String memberKey, String factionId,
            float qualityOrNaN, long seed, int averageSModsOrNeg, boolean dmods, String newVariantId) {
        if (seed == 0L) {
            // Misc.getRandom(0, n) hands back the SHARED global RNG — never deterministic.
            throw new IllegalArgumentException("seed 0 is the shared-RNG trap");
        }
        FactionAPI faction = requireFaction(factionId);
        if (faction.isPlayerFaction()) {
            throw new IllegalArgumentException("player faction goal swap unsupported");
        }
        float quality = Float.isNaN(qualityOrNaN) ? defaultQuality(factionId) : qualityOrNaN;
        ShipVariantAPI target = resolveGoalOrNull(goalVariantId);
        if (target == null) {
            throw new IllegalArgumentException("goal variant unknown: " + goalVariantId);
        }

        // The fleet shell the inflater reads: one member built from the goal, the wave slot as its ship name (the
        // inflater's per-member stream key), nothing placed in any location — the shell lives for this call only.
        CampaignFleetAPI fleet = Global.getFactory().createEmptyFleet(factionId, memberKey, false);
        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, goalVariantId);
        member.setShipName(memberKey);
        fleet.getFleetData().addFleetMember(member);
        ShipVariantAPI before = member.getVariant();

        DefaultFleetInflaterParams params = new DefaultFleetInflaterParams();
        params.seed = seed;
        params.quality = quality;
        params.averageSMods = averageSModsOrNeg >= 0 ? Integer.valueOf(averageSModsOrNeg) : null;
        DefaultFleetInflater inflater = new DefaultFleetInflater(params);
        fleet.setInflater(inflater);
        inflater.inflate(fleet);

        ShipVariantAPI current = member.getVariant();
        if (current == null || current == before) {
            throw new IllegalStateException("the engine left " + goalVariantId + " uninflated (skip reason: "
                    + skipReasonOrNull(goalVariantId) + ")");
        }
        if (!dmods) {
            undoDModPass(current, target);
        }
        if (current.getWeaponGroups().isEmpty()) {
            current.autoGenerateWeaponGroups(); // the engine's own grouping for a groupless AI variant
        }

        // The station modules the engine's autofit refit in place (each stock module cloned under its stock id):
        // serialize every module as its OWN body under <parentId>_m<i> (slot ids sorted = a stable index), and point
        // the parent's modules map at those ids. Vanilla nests one level only; a module that itself declares modules
        // is a content shape this lane has never seen — THROW, never a half-station.
        List<String> moduleSlots = new ArrayList<>(current.getStationModules().keySet());
        Collections.sort(moduleSlots);
        Map<String, String> moduleIds = new LinkedHashMap<>();
        JSONArray moduleBodies = new JSONArray();
        for (int i = 0; i < moduleSlots.size(); i++) {
            String slotId = moduleSlots.get(i);
            ShipVariantAPI module = current.getModuleVariant(slotId);
            if (module == null) {
                throw new IllegalStateException("module variant for slot " + slotId + " missing after the inflate on "
                        + current.getHullSpec().getHullId());
            }
            if (!module.getStationModules().isEmpty()) {
                throw new IllegalStateException("nested station modules on " + module.getHullSpec().getHullId()
                        + " (slot " + slotId + ") — unsupported shape");
            }
            if (module.getWeaponGroups().isEmpty()) {
                module.autoGenerateWeaponGroups();
            }
            String moduleId = newVariantId + MODULE_ID_INFIX + i;
            moduleIds.put(slotId, moduleId);
            moduleBodies.put(toVariantJson(module, moduleId, Map.of()));
        }
        JSONObject parent = toVariantJson(current, newVariantId, moduleIds);
        try {
            parent.put(MODULE_BODIES_KEY, moduleBodies);
        } catch (JSONException impossible) {
            throw new IllegalStateException("module bodies JSON build failed", impossible);
        }
        return parent.toString();
    }

    /**
     * The {@code wave.dmods=false} dial: the inflater always ends with its quality-driven d-mod pass (the D-hull twin,
     * then perma d-mods, never on a station); a pristine wave is that body with the pass undone — the hull the goal
     * names, and every d-mod the pass added removed (a d-mod the goal itself carries stays). The fit is untouched: the
     * pass runs after it, and nothing after the pass reads its stream.
     */
    private static void undoDModPass(ShipVariantAPI current, ShipVariantAPI target) {
        if (!current.getHullSpec().getHullId().equals(target.getHullSpec().getHullId())) {
            current.setHullSpecAPI(target.getHullSpec());
        }
        Set<String> goalMods = new LinkedHashSet<>(target.getHullMods());
        for (String id : new ArrayList<>(current.getHullMods())) {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec != null && spec.hasTag(Tags.HULLMOD_DMOD) && !goalMods.contains(id)) {
                current.removePermaMod(id);
                current.removeMod(id);
            }
        }
    }

    // ------------------------------------------------------------------ serialization

    /** The {@code .variant} file shape ({@code VariantCatalogStore.parseVariantChecked} keys). Every
     * fitted non-built-in weapon must sit in a group, else THROW (a weapon the wire would drop).
     * {@code moduleIds} = the parent's {@code modules} map (slot → the minted module body id), empty for
     * a module or a module-less hull. */
    private static JSONObject toVariantJson(ShipVariantAPI v, String id, Map<String, String> moduleIds) {
        try {
            JSONObject o = new JSONObject();
            o.put("variantId", id);
            o.put("hullId", v.getHullSpec().getHullId());
            o.put("displayName", v.getDisplayName() == null ? "" : v.getDisplayName());
            o.put("goalVariant", false);
            o.put("fluxVents", v.getNumFluxVents());
            o.put("fluxCapacitors", v.getNumFluxCapacitors());
            o.put("hullMods", toArray(v.getNonBuiltInHullmods()));
            o.put("permaMods", toArray(v.getPermaMods()));
            o.put("sMods", toArray(v.getSMods()));
            o.put("sModdedBuiltIns", toArray(v.getSModdedBuiltIns()));
            o.put("suppressedMods", toArray(v.getSuppressedMods()));
            List<String> wings = new ArrayList<>();
            for (String wing : v.getNonBuiltInWings()) {
                if (wing != null && !wing.isEmpty()) {
                    wings.add(wing);
                }
            }
            o.put("wings", toArray(wings));
            o.put("tags", toArray(v.getTags()));
            JSONArray groups = new JSONArray();
            Set<String> grouped = new LinkedHashSet<>();
            for (WeaponGroupSpec group : v.getWeaponGroups()) {
                JSONObject groupJson = new JSONObject();
                groupJson.put("autofire", group.isAutofireOnByDefault());
                groupJson.put("mode", group.getType() == WeaponGroupType.ALTERNATING ? "ALTERNATING" : "LINKED");
                JSONObject weapons = new JSONObject();
                for (String slotId : group.getSlots()) {
                    String weaponId = v.getWeaponId(slotId);
                    if (weaponId != null) {
                        weapons.put(slotId, weaponId);
                        grouped.add(slotId);
                    }
                }
                groupJson.put("weapons", weapons);
                groups.put(groupJson);
            }
            o.put("weaponGroups", groups);
            for (String slotId : v.getFittedWeaponSlots()) {
                WeaponSlotAPI slot = v.getHullSpec().getWeaponSlotAPI(slotId);
                boolean builtIn = slot == null || slot.isBuiltIn() || slot.isDecorative() || slot.isSystemSlot();
                if (!builtIn && !grouped.contains(slotId)) {
                    throw new IllegalStateException("fitted weapon slot " + slotId + " (" + v.getWeaponId(slotId)
                            + ") is in no weapon group after autoGenerateWeaponGroups");
                }
            }
            JSONObject modules = new JSONObject();
            for (Map.Entry<String, String> module : moduleIds.entrySet()) {
                modules.put(module.getKey(), module.getValue());
            }
            o.put("modules", modules);
            return o;
        } catch (JSONException impossible) {
            throw new IllegalStateException("variant JSON build failed", impossible);
        }
    }

    private static JSONArray toArray(Collection<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static FactionAPI requireFaction(String factionId) {
        FactionAPI f = factionOrNull(factionId);
        if (f == null) {
            throw new IllegalArgumentException("faction unknown: " + factionId);
        }
        return f;
    }

    /** The goal de-referenced to its stock original, as the inflater itself does with a member's variant.
     * */
    private static ShipVariantAPI resolveGoalOrNull(String goalVariantId) {
        ShipVariantAPI goal = Global.getSettings().getVariant(goalVariantId);
        if (goal != null && goal.getOriginalVariant() != null) {
            ShipVariantAPI original = Global.getSettings().getVariant(goal.getOriginalVariant());
            if (original != null) {
                goal = original;
            }
        }
        return goal;
    }
}
