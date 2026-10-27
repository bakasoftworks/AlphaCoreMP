package cc.starsector.server.runtime;

import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.WeaponGroup;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.WeaponMount;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Variant-maker v0 (policy tiers + pipeline, the ENGINE-FREE
 * half): the PURE allowlist + shape law a submitted custom variant must pass BEFORE any engine
 * object is touched (a design law — policy is contract-tested over API predicates
 * transcribed from the variant census script, never a hand list).
 *
 * <p>Inputs are a {@link Catalog} of engine-independent FACTS (ids, sizes, hints, tags, slot ids,
 * built-ins, mod provenance) — at boot the bridge snapshots them from {@code SettingsAPI} on the
 * sim thread ONCE; the suite hands in fixtures. Precedence: the BASE filter
 * always wins; then the owner override file's {@code deny} beats the tier; {@code allow} beats
 * the tier AND the mod-defined rule; mod-defined ids (skins lose their vanilla tags at load, so a
 * tag-driven tier cannot grade them) are DENIED in every tier unless allowed; then the tier.
 *
 * <p>{@link #check(VariantDefinition)} runs the pipeline steps that need no engine: 1 hull
 * resolves, 2 hull policy, 5/6/7 the ID + slot-identity parts (slot exists on the hull, not
 * built-in, fitted once; weapon / wing / hullmod ids resolve and pass policy; hullmods unique and
 * not the hull's built-ins), 11 the s-mod SET laws ({@code sMods ⊆ permaMods ⊆ hullMods},
 * {@code permaMods == sMods}, no {@code no_build_in}, s-modded built-ins ARE built-ins), 12 the
 * v1 EMPTY laws (suppressedMods / tags / modules, {@code goalVariant=false}) and 13 the group
 * law (every fitted slot in exactly one group). Vents/caps vs size+bonus, wings ≤ bays,
 * {@code weaponFits}, applicability, the s-mod CAP and the OP budget need engine stats and ride
 * the sim-thread registrar (steps #3/#4/#5-fits/#6-count/#8-#10/#14). Reason tokens are the
 * {@link DoorRejectReasons#custom()} vocabulary — the client sentence, the smoke sweep and this
 * class share ONE home.
 *
 * <p>Security: every id here is CLIENT-chosen text already bounded by the codec
 * laws; this class only ever compares it against catalog keys and never echoes it unquoted — the
 * {@link Refusal#detail()} is operator log text and the caller quotes it.
 */
public final class VariantPolicy {

    /** Policy tiers; {@code STANDARD} is the default (a vanilla player can own it). */
    public enum Tier {
        STRICT, STANDARD, PERMISSIVE;

        /** Strict parse of the {@code custom.tier} knob (lower-case tokens; THROWS otherwise). */
        public static Tier parse(String raw) {
            String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            switch (v) {
                case "strict": return STRICT;
                case "standard": return STANDARD;
                case "permissive": return PERMISSIVE;
                default:
                    throw new IllegalArgumentException(
                            "custom.tier must be strict|standard|permissive, got: " + raw);
            }
        }
    }

    /** One hull's engine-independent facts (the {@code ShipHullSpecAPI} reads, snapshotted). */
    public record HullFacts(
            String id,
            /** {@code ShipAPI.HullSize} name: FIGHTER / FRIGATE / DESTROYER / CRUISER / CAPITAL_SHIP. */
            String hullSize,
            /** {@code ShipTypeHints} names. */
            Set<String> hints,
            Set<String> tags,
            boolean defaultDHull,
            boolean hasModuleAnchor,
            /** Any slot with {@code isStationModule()} — a station base. */
            boolean hasStationModuleSlot,
            /** Built-in hullmod ids (never fitted by a player; never s-modded except via sModdedBuiltIns). */
            Set<String> builtInMods,
            /** Ids of the slots a player may fit: {@code getAllWeaponSlotsCopy()} minus built-in /
             *  decorative / system / hidden / station-module / launch-bay slots. */
            Set<String> fittableSlotIds,
            boolean modDefined,
            /** Built-in weapons, slot id → weapon id ({@code getBuiltInWeapons()}): a group may carry a
             *  built-in slot ONLY paired with this exact weapon. */
            Map<String, String> builtInWeapons,
            /** EVERY slot id in {@code getAllWeaponSlotsCopy()} order (built-in, decorative, system,
             *  hidden, station-module and launch-bay slots included) — the per-hull u16 slot
             *  index domain; folded into the id-table digest. */
            List<String> slotOrder) {
        public HullFacts {
            Objects.requireNonNull(id, "id");
            hullSize = Objects.requireNonNull(hullSize, "hullSize");
            hints = Set.copyOf(hints);
            tags = Set.copyOf(tags);
            builtInMods = Set.copyOf(builtInMods);
            fittableSlotIds = Set.copyOf(fittableSlotIds);
            builtInWeapons = Map.copyOf(builtInWeapons);
            slotOrder = List.copyOf(slotOrder);
        }
    }

    /** One weapon's facts ({@code WeaponSpecAPI}). */
    public record WeaponFacts(
            String id,
            /** {@code WeaponAPI.WeaponType} name. */
            String type,
            Set<String> tags,
            /** {@code getAIHints()} contains {@code SYSTEM}. */
            boolean systemHint,
            boolean modDefined) {
        public WeaponFacts {
            Objects.requireNonNull(id, "id");
            type = Objects.requireNonNull(type, "type");
            tags = Set.copyOf(tags);
        }
    }

    /** One hullmod's facts ({@code HullModSpecAPI}). */
    public record HullModFacts(
            String id,
            boolean hidden,
            boolean hiddenEverywhere,
            Set<String> tags,
            /** {@code hull_mods.csv} {@code unlocked} — a no_drop mod that is unlocked anyway. */
            boolean alwaysUnlocked,
            boolean modDefined) {
        public HullModFacts {
            Objects.requireNonNull(id, "id");
            tags = Set.copyOf(tags);
        }

        public boolean isDMod() {
            return tags.contains("dmod");
        }
    }

    /** One fighter wing's facts ({@code FighterWingSpecAPI} + its fighter hull's hints). */
    public record WingFacts(
            String id,
            Set<String> tags,
            /** The wing declares a variant (a wing without one cannot be built). */
            boolean hasVariant,
            /** The fighter hull's {@code ShipTypeHints} (HIDE_IN_CODEX rides here in STRICT). */
            Set<String> fighterHullHints,
            boolean modDefined) {
        public WingFacts {
            Objects.requireNonNull(id, "id");
            tags = Set.copyOf(tags);
            fighterHullHints = Set.copyOf(fighterHullHints);
        }
    }

    /** The boot snapshot: every spec the engine knows, by id. */
    public record Catalog(
            Map<String, HullFacts> hulls,
            Map<String, WeaponFacts> weapons,
            Map<String, HullModFacts> hullMods,
            Map<String, WingFacts> wings) {
        public Catalog {
            hulls = Collections.unmodifiableMap(new LinkedHashMap<>(hulls));
            weapons = Collections.unmodifiableMap(new LinkedHashMap<>(weapons));
            hullMods = Collections.unmodifiableMap(new LinkedHashMap<>(hullMods));
            wings = Collections.unmodifiableMap(new LinkedHashMap<>(wings));
        }

        //
        /** The boot id table over this universe (the server's real mint path; moved off
         *  {@code IdTables} so that class stays bundle-clean). */
        public IdTables idTables() {
            Map<String, List<String>> slots = new LinkedHashMap<>();
            Map<String, Map<String, String>> builtIns = new LinkedHashMap<>();
            for (HullFacts hull : hulls.values()) {
                slots.put(hull.id(), hull.slotOrder());
                builtIns.put(hull.id(), hull.builtInWeapons());
            }
            return IdTables.of(hulls.keySet(), weapons.keySet(), hullMods.keySet(), wings.keySet(),
                    slots, builtIns);
        }
    }

    /**
     * The owner override file: {@code { "hulls": {"deny":[..],"allow":[..]},
     * "weapons": …, "hullmods": …, "wings": …, "modDefined": "deny"|"allow" }}. Read at boot;
     * an id the catalog does not know is a BOOT ERROR (a typo must never silently widen or
     * narrow the allowlist).
     */
    public record Overrides(
            Set<String> hullAllow, Set<String> hullDeny,
            Set<String> weaponAllow, Set<String> weaponDeny,
            Set<String> hullModAllow, Set<String> hullModDeny,
            Set<String> wingAllow, Set<String> wingDeny,
            boolean modDefinedAllowed) {
        public Overrides {
            hullAllow = Set.copyOf(hullAllow);
            hullDeny = Set.copyOf(hullDeny);
            weaponAllow = Set.copyOf(weaponAllow);
            weaponDeny = Set.copyOf(weaponDeny);
            hullModAllow = Set.copyOf(hullModAllow);
            hullModDeny = Set.copyOf(hullModDeny);
            wingAllow = Set.copyOf(wingAllow);
            wingDeny = Set.copyOf(wingDeny);
            // An id in BOTH lists is a contradiction the check
            // would resolve silently (deny wins at hullRefusal) — a BOOT ERROR, like an unknown id.
            disjointOrThrow("hulls", hullAllow, hullDeny);
            disjointOrThrow("weapons", weaponAllow, weaponDeny);
            disjointOrThrow("hullmods", hullModAllow, hullModDeny);
            disjointOrThrow("wings", wingAllow, wingDeny);
        }

        private static void disjointOrThrow(String category, Set<String> allow, Set<String> deny) {
            for (String id : allow) {
                if (deny.contains(id)) {
                    throw new IllegalArgumentException("custom.policyFile: '" + category + "' lists '" + id
                            + "' in BOTH deny and allow");
                }
            }
        }

        /** No file: nothing allowed past the tier, nothing denied below it, mod-defined DENIED. */
        public static Overrides none() {
            return new Overrides(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), false);
        }

        /** Parse + validate against the catalog; THROWS on an unknown key, id or mode. */
        public static Overrides parse(JSONObject json, Catalog catalog) {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(catalog, "catalog");
            Set<String> knownKeys = Set.of("hulls", "weapons", "hullmods", "wings", "modDefined");
            for (String key : keysOf(json)) {
                if (!knownKeys.contains(key)) {
                    throw new IllegalArgumentException("custom.policyFile: unknown top-level key '"
                            + key + "' (expected hulls|weapons|hullmods|wings|modDefined)");
                }
            }
            boolean modDefinedAllowed = false;
            if (json.has("modDefined")) {
                String mode = json.optString("modDefined", "deny").trim().toLowerCase(Locale.ROOT);
                switch (mode) {
                    case "deny": modDefinedAllowed = false; break;
                    case "allow": modDefinedAllowed = true; break;
                    default:
                        throw new IllegalArgumentException(
                                "custom.policyFile: modDefined must be deny|allow, got '" + mode + "'");
                }
            }
            return new Overrides(
                    idList(json, "hulls", "allow", catalog.hulls().keySet()),
                    idList(json, "hulls", "deny", catalog.hulls().keySet()),
                    idList(json, "weapons", "allow", catalog.weapons().keySet()),
                    idList(json, "weapons", "deny", catalog.weapons().keySet()),
                    idList(json, "hullmods", "allow", catalog.hullMods().keySet()),
                    idList(json, "hullmods", "deny", catalog.hullMods().keySet()),
                    idList(json, "wings", "allow", catalog.wings().keySet()),
                    idList(json, "wings", "deny", catalog.wings().keySet()),
                    modDefinedAllowed);
        }

        private static Set<String> idList(JSONObject root, String category, String mode,
                Set<String> known) {
            if (!root.has(category)) {
                return Set.of();
            }
            JSONObject section;
            try {
                section = root.getJSONObject(category);
            } catch (JSONException e) {
                throw new IllegalArgumentException("custom.policyFile: '" + category
                        + "' must be an object with deny/allow arrays", e);
            }
            for (String key : keysOf(section)) {
                if (!key.equals("deny") && !key.equals("allow")) {
                    throw new IllegalArgumentException("custom.policyFile: '" + category + "." + key
                            + "' unknown (expected deny|allow)");
                }
            }
            JSONArray ids = section.optJSONArray(mode);
            if (ids == null) {
                return Set.of();
            }
            Set<String> out = new LinkedHashSet<>();
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.optString(i, null);
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("custom.policyFile: '" + category + "." + mode
                            + "[" + i + "]' is not a string id");
                }
                if (!known.contains(id)) {
                    throw new IllegalArgumentException("custom.policyFile: '" + category + "." + mode
                            + "' names an id the engine does not know: '" + id + "'");
                }
                out.add(id);
            }
            return out;
        }

        private static List<String> keysOf(JSONObject json) {
            String[] names = JSONObject.getNames(json);
            return names == null ? List.of() : List.of(names);
        }
    }

    /** One refusal: the wire token (the custom reject vocabulary) + operator-log detail (never wire). */
    public record Refusal(String token, String detail) {
        public Refusal {
            if (!DoorRejectReasons.custom().contains(token)) {
                throw new IllegalArgumentException("not a custom reject token: " + token);
            }
            detail = detail == null ? "" : detail;
        }
    }

    /** Tier census for the boot line, meant to be diffed per serve. */
    public record Census(int hullsAdmitted, int hullsTotal, int weaponsAdmitted, int weaponsTotal,
            int hullModsAdmitted, int hullModsTotal, int wingsAdmitted, int wingsTotal,
            int modDefinedTotal) {
    }

    // ---- census vocabularies (f1-census.py :67-71, from data/world/factions/*.faction knownShips.tags)
    static final Set<String> MARKET_FACTION_TAGS = Set.of("hegemony", "heg_aux_bp", "XIV_bp", "ind",
            "independent", "sindrian_diktat", "lions_guard", "LG_bp", "luddic_church", "LC_bp",
            "luddic_path", "LP_bp", "merc", "persean", "pirates", "pirate_bp", "tritachyon", "base_bp");
    static final Set<String> NPC_WEAPON_TAGS = Set.of("threat", "dweller");
    static final Set<String> NPC_HULLMOD_TAGS = Set.of("threat", "dweller", "fragment", "shrouded");
    static final Set<String> NPC_WING_TAGS = Set.of("threat", "dweller", "swarm_fighter", "fragment");
    static final Set<String> BASE_DENIED_WEAPON_TYPES = Set.of("DECORATIVE", "SYSTEM", "LAUNCH_BAY",
            "STATION_MODULE", "BUILT_IN");
    /** A known trap: the engine rewrites this prefix on lookup. */
    public static final String WENDIGO_PREFIX = "wendigo";

    private final Catalog catalog;
    private final Tier tier;
    private final Overrides overrides;
    private final boolean dModsAllowed;

    public VariantPolicy(Catalog catalog, Tier tier, Overrides overrides, boolean dModsAllowed) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.tier = Objects.requireNonNull(tier, "tier");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.dModsAllowed = dModsAllowed;
    }

    public Tier tier() {
        return tier;
    }

    public Catalog catalog() {
        return catalog;
    }

    public boolean dModsAllowed() {
        return dModsAllowed;
    }

    // ------------------------------------------------------------------ per-id verdicts

    /** null = allowed; else the census reason token (operator detail, never the wire). */
    public String hullRefusal(String id) {
        HullFacts h = catalog.hulls().get(id);
        if (h == null) {
            return "unknown";
        }
        String base = hullBase(h);
        if (base != null) {
            return base;
        }
        if (overrides.hullDeny().contains(id)) {
            return "override_deny";
        }
        if (overrides.hullAllow().contains(id)) {
            return null;
        }
        if (h.modDefined() && !overrides.modDefinedAllowed()) {
            return "mod_defined";
        }
        return hullTier(h);
    }

    public String weaponRefusal(String id) {
        WeaponFacts w = catalog.weapons().get(id);
        if (w == null) {
            return "unknown";
        }
        String base = weaponBase(w);
        if (base != null) {
            return base;
        }
        if (overrides.weaponDeny().contains(id)) {
            return "override_deny";
        }
        if (overrides.weaponAllow().contains(id)) {
            return null;
        }
        if (w.modDefined() && !overrides.modDefinedAllowed()) {
            return "mod_defined";
        }
        return weaponTier(w);
    }

    public String hullModRefusal(String id) {
        HullModFacts m = catalog.hullMods().get(id);
        if (m == null) {
            return "unknown";
        }
        String base = hullModBase(m);
        if (base != null) {
            return base;
        }
        if (overrides.hullModDeny().contains(id)) {
            return "override_deny";
        }
        if (overrides.hullModAllow().contains(id)) {
            return null;
        }
        if (m.modDefined() && !overrides.modDefinedAllowed()) {
            return "mod_defined";
        }
        // D-mods ride the knob in every tier that would otherwise admit them.
        if (m.isDMod() && !dModsAllowed) {
            return "dmod_knob";
        }
        return hullModTier(m);
    }

    public String wingRefusal(String id) {
        WingFacts g = catalog.wings().get(id);
        if (g == null) {
            return "unknown";
        }
        String base = wingBase(g);
        if (base != null) {
            return base;
        }
        if (overrides.wingDeny().contains(id)) {
            return "override_deny";
        }
        if (overrides.wingAllow().contains(id)) {
            return null;
        }
        if (g.modDefined() && !overrides.modDefinedAllowed()) {
            return "mod_defined";
        }
        return wingTier(g);
    }

    // ------------------------------------------------------------------ base filters (every tier)

    static String hullBase(HullFacts h) {
        if ("FIGHTER".equals(h.hullSize())) {
            return "fighter";
        }
        if (h.hints().contains("STATION")) {
            return "station";
        }
        if (h.hasStationModuleSlot()) {
            return "station_base";
        }
        if (h.hints().contains("MODULE") || h.hasModuleAnchor() || h.id().startsWith("module_")) {
            return "station_module";
        }
        if (h.defaultDHull() || h.id().endsWith("_default_D")) {
            return "dhull_twin";
        }
        return null;
    }

    static String weaponBase(WeaponFacts w) {
        if (BASE_DENIED_WEAPON_TYPES.contains(w.type())) {
            return "type_" + w.type().toLowerCase(Locale.ROOT);
        }
        if (w.systemHint()) {
            return "hint_system";
        }
        return null;
    }

    static String hullModBase(HullModFacts m) {
        if (m.hiddenEverywhere()) {
            return "hidden_everywhere";
        }
        if (m.hidden() && !m.isDMod()) {
            return "hidden";
        }
        return null;
    }

    static String wingBase(WingFacts g) {
        if (!g.hasVariant()) {
            return "no_variant";
        }
        return null;
    }

    // ------------------------------------------------------------------ tier predicates (f1-census.py)

    private String hullTier(HullFacts h) {
        if (tier == Tier.PERMISSIVE) {
            return null;
        }
        // STANDARD (Misc.isUnboardable: UNBOARDABLE unless a recovery tag; AutomatedShips adds auto_rec)
        if (h.hints().contains("UNBOARDABLE") && !h.tags().contains("auto_rec")) {
            return "unboardable";
        }
        if (h.tags().contains("monster")) {
            return "monster";
        }
        if (h.tags().contains("unrecoverable")) {
            return "unrecoverable";
        }
        if (tier == Tier.STANDARD) {
            return null;
        }
        // STRICT (Codex-visible, non-restricted, faction/bp-tagged)
        if (h.hints().contains("HIDE_IN_CODEX") || h.tags().contains("hide_in_codex")) {
            return "hide_in_codex";
        }
        if ("shuttlepod".equals(h.id())) {
            return "codex_skips";
        }
        if (h.tags().contains("restricted")) {
            return "restricted";
        }
        if (h.builtInMods().contains("automated")) {
            return "automated";
        }
        if (!marketFielded(h.tags())) {
            return "not_market_fielded";
        }
        return null;
    }

    private String weaponTier(WeaponFacts w) {
        if (tier == Tier.PERMISSIVE) {
            return null;
        }
        boolean noDrop = w.tags().contains("no_drop");
        boolean noSell = w.tags().contains("no_sell");
        if (noDrop && noSell && !anyBpTag(w.tags())) {
            return "no_drop_no_sell";
        }
        if (noDrop && intersects(w.tags(), NPC_WEAPON_TAGS)) {
            return "npc_only";
        }
        if (tier == Tier.STANDARD) {
            return null;
        }
        if (w.tags().contains("hide_in_codex")) {
            return "hide_in_codex";
        }
        if (w.tags().contains("restricted")) {
            return "restricted";
        }
        if (!marketFielded(w.tags())) {
            return "not_market_fielded";
        }
        if (noSell && noDrop) {
            return "no_sell_no_drop";
        }
        return null;
    }

    private String hullModTier(HullModFacts m) {
        if (tier == Tier.PERMISSIVE) {
            return null;
        }
        if (intersects(m.tags(), NPC_HULLMOD_TAGS)) {
            return "npc_faction";
        }
        if (tier == Tier.STANDARD) {
            return null;
        }
        if (m.isDMod()) {
            return "dmod";
        }
        if (m.tags().contains("hide_in_codex")) {
            return "hide_in_codex";
        }
        if (m.tags().contains("restricted")) {
            return "restricted";
        }
        if (m.tags().contains("no_drop") && !m.alwaysUnlocked()) {
            return "no_drop_gated";
        }
        return null;
    }

    private String wingTier(WingFacts g) {
        if (tier == Tier.PERMISSIVE) {
            return null;
        }
        if (g.tags().contains("no_drop") && g.tags().contains("no_sell") && !anyBpTag(g.tags())) {
            return "no_drop_no_sell";
        }
        if (intersects(g.tags(), NPC_WING_TAGS)) {
            return "npc_swarm";
        }
        if (tier == Tier.STANDARD) {
            return null;
        }
        if (g.tags().contains("hide_in_codex") || g.fighterHullHints().contains("HIDE_IN_CODEX")) {
            return "hide_in_codex";
        }
        if (g.tags().contains("restricted")) {
            return "restricted";
        }
        if (!marketFielded(g.tags())) {
            return "not_market_fielded";
        }
        return null;
    }

    static boolean marketFielded(Set<String> tags) {
        return intersects(tags, MARKET_FACTION_TAGS) || anyBpTag(tags);
    }

    static boolean anyBpTag(Set<String> tags) {
        for (String t : tags) {
            if (t.endsWith("_bp")) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String t : a) {
            if (b.contains(t)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the engine-free pipeline

    /**
     * Pipeline steps 1, 2, 5/6/7 (identity + policy halves), 11 (set laws), 12, 13 over ONE
     * submitted definition. Returns {@code null} when every engine-free law holds, else the FIRST
     * refusal in pipeline order (the oracle mirrors the same order so both ends name the same
     * step). Never touches the engine; never throws on content (the codec already bounded it).
     */
    public Refusal check(VariantDefinition v) {
        Objects.requireNonNull(v, "variant");
        // 1 + 2: the hull resolves and passes policy.
        HullFacts hull = catalog.hulls().get(v.hullId());
        if (hull == null) {
            return new Refusal(DoorRejectReasons.CUSTOM_HULL_DENIED, "hull unknown");
        }
        String hullWhy = hullRefusal(v.hullId());
        if (hullWhy != null) {
            return new Refusal(DoorRejectReasons.CUSTOM_HULL_DENIED, "hull " + hullWhy);
        }
        // 12: v1 EMPTY laws.
        if (v.goalVariant()) {
            return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "goalVariant must be false");
        }
        if (!v.suppressedMods().isEmpty()) {
            return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "suppressedMods must be empty");
        }
        if (!v.tags().isEmpty()) {
            return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "tags must be empty");
        }
        if (!v.modules().isEmpty()) {
            return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "modules must be empty (v1)");
        }
        // 5 (identity half) + 13: slots exist, are fittable, and each is fitted ONCE across groups.
        Set<String> fitted = new HashSet<>();
        for (WeaponGroup group : v.weaponGroups()) {
            for (WeaponMount mount : group.weapons()) {
                // Vanilla's .variant lists a built-in weapon in its group under the
                // hull's own slot + weapon id (onslaught_Standard: WS 016 = tpc) — accepted as EXACTLY that pairing
                // (the group is the player's; the weapon never is); any other weapon on a built-in slot refused as before
                String builtIn = hull.builtInWeapons().get(mount.slotId());
                if (builtIn != null) {
                    if (!builtIn.equals(mount.weaponId())) {
                        return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                                "built-in slot takes only its own weapon: " + mount.slotId());
                    }
                    if (!fitted.add(mount.slotId())) {
                        return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                                "slot fitted twice: " + mount.slotId());
                    }
                    continue;
                }
                if (!hull.fittableSlotIds().contains(mount.slotId())) {
                    return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                            "slot not fittable on hull: " + mount.slotId());
                }
                if (!fitted.add(mount.slotId())) {
                    return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                            "slot fitted twice: " + mount.slotId());
                }
                String weaponWhy = weaponRefusal(mount.weaponId());
                if (weaponWhy != null) {
                    return new Refusal(DoorRejectReasons.CUSTOM_WEAPON_DENIED,
                            "weapon " + mount.weaponId() + " " + weaponWhy);
                }
            }
        }
        // 6 (identity half): wings resolve + policy (count ≤ bays needs stats — registrar).
        for (String wing : v.wings()) {
            String wingWhy = wingRefusal(wing);
            if (wingWhy != null) {
                return new Refusal(DoorRejectReasons.CUSTOM_WING_DENIED, "wing " + wing + " " + wingWhy);
            }
        }
        // 7: hullmods resolve, pass policy, are unique, and are not the hull's built-ins.
        Set<String> mods = new HashSet<>();
        for (String mod : v.hullMods()) {
            if (!mods.add(mod)) {
                return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "hullmod listed twice: " + mod);
            }
            if (hull.builtInMods().contains(mod)) {
                return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                        "hullmod is built into the hull: " + mod);
            }
            String modWhy = hullModRefusal(mod);
            if (modWhy != null) {
                return new Refusal(DoorRejectReasons.CUSTOM_HULLMOD_DENIED, "hullmod " + mod + " " + modWhy);
            }
        }
        // 11 (set laws): sMods ⊆ permaMods ⊆ hullMods, permaMods == sMods, no no_build_in,
        // s-modded built-ins are built-ins (uncounted toward the cap — registrar).
        Set<String> perma = new HashSet<>(v.permaMods());
        if (perma.size() != v.permaMods().size()) {
            return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "permaMods repeats an id");
        }
        Set<String> sMods = new HashSet<>(v.sMods());
        if (sMods.size() != v.sMods().size()) {
            return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "sMods repeats an id");
        }
        if (!mods.containsAll(perma)) {
            return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "permaMods must be a subset of hullMods");
        }
        if (!perma.equals(sMods)) {
            return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "permaMods must equal sMods (v1)");
        }
        for (String s : v.sMods()) {
            HullModFacts m = catalog.hullMods().get(s);
            if (m != null && m.tags().contains("no_build_in")) {
                return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "hullmod cannot be built in: " + s);
            }
        }
        Set<String> sBuiltIns = new HashSet<>();
        for (String s : v.sModdedBuiltIns()) {
            if (!sBuiltIns.add(s)) {
                return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID, "sModdedBuiltIns repeats an id");
            }
            if (!hull.builtInMods().contains(s)) {
                return new Refusal(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                        "sModdedBuiltIns names a mod the hull does not build in: " + s);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ census

    /** Admitted-per-category counts under THIS policy (the boot line). */
    public Census census() {
        int h = 0, w = 0, m = 0, g = 0, modDefined = 0;
        for (HullFacts f : catalog.hulls().values()) {
            if (hullRefusal(f.id()) == null) {
                h++;
            }
            if (f.modDefined()) {
                modDefined++;
            }
        }
        for (WeaponFacts f : catalog.weapons().values()) {
            if (weaponRefusal(f.id()) == null) {
                w++;
            }
            if (f.modDefined()) {
                modDefined++;
            }
        }
        for (HullModFacts f : catalog.hullMods().values()) {
            if (hullModRefusal(f.id()) == null) {
                m++;
            }
            if (f.modDefined()) {
                modDefined++;
            }
        }
        for (WingFacts f : catalog.wings().values()) {
            if (wingRefusal(f.id()) == null) {
                g++;
            }
            if (f.modDefined()) {
                modDefined++;
            }
        }
        return new Census(h, catalog.hulls().size(), w, catalog.weapons().size(),
                m, catalog.hullMods().size(), g, catalog.wings().size(), modDefined);
    }

    /** Refusal-reason histogram for one category (the boot census diff line). */
    public Map<String, Integer> hullRefusalHistogram() {
        Map<String, Integer> out = new TreeMap<>();
        for (HullFacts f : catalog.hulls().values()) {
            String why = hullRefusal(f.id());
            if (why != null) {
                out.merge(why, 1, Integer::sum);
            }
        }
        return out;
    }

    /** One stdout row: {@code custom-variant policy tier=… hulls=a/b weapons=a/b hullmods=a/b wings=a/b mod_defined=n dmods=…}. */
    public String bootLine() {
        Census c = census();
        return "custom-variant policy tier=" + tier.name().toLowerCase(Locale.ROOT)
                + " hulls=" + c.hullsAdmitted() + "/" + c.hullsTotal()
                + " weapons=" + c.weaponsAdmitted() + "/" + c.weaponsTotal()
                + " hullmods=" + c.hullModsAdmitted() + "/" + c.hullModsTotal()
                + " wings=" + c.wingsAdmitted() + "/" + c.wingsTotal()
                + " mod_defined=" + c.modDefinedTotal()
                + " mod_defined_allowed=" + overrides.modDefinedAllowed()
                + " dmods=" + (dModsAllowed ? "allow" : "deny")
                + " overrides=" + (overrides.hullAllow().size() + overrides.hullDeny().size()
                        + overrides.weaponAllow().size() + overrides.weaponDeny().size()
                        + overrides.hullModAllow().size() + overrides.hullModDeny().size()
                        + overrides.wingAllow().size() + overrides.wingDeny().size());
    }
}
