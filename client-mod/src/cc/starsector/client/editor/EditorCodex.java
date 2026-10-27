package cc.starsector.client.editor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionSpecAPI;
import com.fs.starfarer.api.characters.DescriptionSkillEffect;
import com.fs.starfarer.api.characters.LevelBasedEffect;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShieldSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.codex.CodexDataV2;
import com.fs.starfarer.api.impl.codex.CodexEntryPlugin;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * The editor's CODEX route family. The game builds its
 * own codex tree once at the title screen ({@code TitleScreenState.init} → {@link CodexDataV2#init}); this class
 * SERIALIZES that tree — titles, ids, icons, related sets, locked / visible flags come from the game's
 * {@link CodexEntryPlugin}s (engine-native seam, never a rebuilt list); only the filter TAGS are recomputed from
 * the specs by vanilla's own predicates ({@code CodexDataV2.configureTagDisplay} needs a live widget). Detail
 * pages carry vanilla's rows with vanilla's labels in vanilla's order;
 * the SPA paints text and computes no game stat.
 *
 * <p>Laws: every id is charset-gated by the caller ({@link EditorText#isId}) and resolved through
 * {@link CodexDataV2#getEntry} / the API — never a file name; every lane is GET-only, read-only, no file, no
 * session, no wire. Seven combat categories are served ({@link #SERVED}); the others are named in the tree's
 * {@code other} list. Locked entries list as locked and are never detailed on the codex page (vanilla's law); the editor's
 * HOVER view ({@link #entry(String, boolean)} with {@code forBuild}) details a locked spec the builder can already fit.
 */
final class EditorCodex {
    static final Set<String> SERVED = Set.of("ships", "stations", "fighters", "weapons", "hullmods", "ship_systems", "skills");
    /** The category glyph keys the {@code sprite/codex/<key>} lane may resolve ({@code SettingsAPI.getSpriteName("codex", key)}). */
    static final Set<String> ICON_KEYS = Set.of("ships", "stations", "fighters", "weapons", "hullmods", "ship_systems", "skills",
            "cat_up", "locked_entry", "special_items", "industries", "commodities", "stars_and_planets", "conditions", "abilities", "gallery");
    private static final String NEUTRAL_CR = "0.7";   // the codex member's CR: inside the neutral band (no CR modifier touches a row)
    /** The stock hull whose third description text is the game's limited-entry wording ({@code descriptions.csv}). */
    private static final String LIMITED_WORDS_DONOR_HULL = "tesseract";
    private static volatile byte[] treeCache;

    private EditorCodex() {
    }

    // ------------------------------------------------------------------ the tree

    /** {@code GET codex/tree}: the served categories with their entries, computed once per game process (the tree never
     *  changes at the title / in a match); null while the game has not built its codex yet (a 503 for the route). */
    static byte[] treeJsonBytes() throws JSONException {
        byte[] cached = treeCache;
        if (cached != null) {
            return cached;
        }
        CodexEntryPlugin root = CodexDataV2.ROOT;
        if (root == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("unlocked", CodexDataV2.codexFullyUnlocked());
        o.put("showLocked", settingBool("showLockedCodexEntries"));
        JSONArray cats = new JSONArray();
        JSONArray other = new JSONArray();
        for (CodexEntryPlugin cat : root.getChildren()) {
            if (cat == null || cat.getId() == null) {
                continue;
            }
            if (!SERVED.contains(cat.getId())) {
                if (cat.isVisible()) {
                    other.put(cat.getId());
                }
                continue;
            }
            JSONObject c = new JSONObject();
            c.put("id", cat.getId());
            c.put("title", nz(cat.getTitle()));
            c.put("icon", "sprite/codex/" + cat.getId());
            c.put("retainOrder", cat.isRetainOrderOfChildren());   // skills keep the game's declared order; the rest sort by sort title (vanilla's dialog)
            JSONArray entries = new JSONArray();
            addEntries(entries, cat, null);
            c.put("entries", entries);
            cats.put(c);
        }
        o.put("cats", cats);
        o.put("other", other);
        byte[] bytes = o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        treeCache = bytes;
        return bytes;
    }

    private static void addEntries(JSONArray out, CodexEntryPlugin node, String parentId) throws JSONException {
        for (CodexEntryPlugin e : node.getChildren()) {
            JSONObject row;
            try {
                row = e == null || e.getId() == null || !e.isVisible() ? null : entryRow(e, parentId);
            } catch (RuntimeException | LinkageError modEntryBroke) {
                row = null;   // a mod's entry that throws is skipped (vanilla paints "Mod error"); the rest of the list stands
            }
            if (row != null) {
                out.put(row);
            }
            if (e != null && !e.getChildren().isEmpty()) {
                addEntries(out, e, e.getId());   // a station's modules ride under their station
            }
        }
    }

    private static JSONObject entryRow(CodexEntryPlugin e, String parentId) throws JSONException {
        String kind = kind(e, parentId);
        if (kind == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("id", e.getId());
        o.put("kind", kind);
        if (parentId != null) {
            o.put("parent", parentId);
        }
        if (e.isLocked()) {
            o.put("locked", true);   // vanilla lists a locked row as "Locked entry": no title, no icon, no tags
            return o;
        }
        o.put("title", nz(e.getTitle()));
        o.put("sort", nz(e.getSortTitle()));
        o.put("icon", iconLane(e, kind));
        o.put("tags", tags(e, kind));
        return o;
    }

    /** hull | station | module | weapon | fighter | hullmod | system | skill by the entry's param type; null = not served. */
    static String kind(CodexEntryPlugin e, String parentId) {
        Object p = e.getParam();
        if (p instanceof ShipHullSpecAPI) {
            ShipHullSpecAPI h = (ShipHullSpecAPI) p;
            if (parentId != null) {
                return "module";
            }
            return h.getHints() != null && h.getHints().contains(ShipTypeHints.STATION) ? "station" : "hull";
        }
        if (p instanceof FleetMemberAPI) {
            return parentId != null ? "module" : "hull";
        }
        if (p instanceof WeaponSpecAPI) {
            return "weapon";
        }
        if (p instanceof FighterWingSpecAPI) {
            return "fighter";
        }
        if (p instanceof HullModSpecAPI) {
            return "hullmod";
        }
        if (p instanceof ShipSystemSpecAPI) {
            return "system";
        }
        if (p instanceof SkillSpecAPI) {
            return "skill";
        }
        return null;
    }

    private static ShipHullSpecAPI hullOf(CodexEntryPlugin e) {
        Object p = e.getParam();
        if (p instanceof ShipHullSpecAPI) {
            return (ShipHullSpecAPI) p;
        }
        if (p instanceof FleetMemberAPI) {
            return ((FleetMemberAPI) p).getHullSpec();
        }
        return null;
    }

    private static String iconLane(CodexEntryPlugin e, String kind) {
        Object p = e.getParam();
        switch (kind) {
            case "hull": case "station": case "module": {
                ShipHullSpecAPI h = hullOf(e);
                return h == null ? null : "sprite/" + h.getHullId();
            }
            case "weapon": return "sprite/weapon/" + ((WeaponSpecAPI) p).getWeaponId();
            case "fighter": return "sprite/wing/" + ((FighterWingSpecAPI) p).getId();
            case "hullmod": return "sprite/hullmod/" + ((HullModSpecAPI) p).getId();
            case "system": return "sprite/system/" + ((ShipSystemSpecAPI) p).getId();
            case "skill": return "sprite/skill/" + ((SkillSpecAPI) p).getId();
            default: return null;
        }
    }

    /** Vanilla's tag groups: every value a list so the SPA builds the chip groups uniformly. */
    private static JSONObject tags(CodexEntryPlugin e, String kind) throws JSONException {
        JSONObject t = new JSONObject();
        Object p = e.getParam();
        switch (kind) {
            case "hull": case "station": case "module": {
                ShipHullSpecAPI h = hullOf(e);
                if (h == null) {
                    break;
                }
                t.put("tech", one(h.getManufacturer()));
                t.put("size", one("station".equals(kind) ? "Stations" : sizeGroup(h.getHullSize())));
                String type = h.isCarrier() ? "Carriers" : h.isCivilianNonCarrier() ? "Civilian"
                        : (h.isPhase() || (h.getHints() != null && h.getHints().contains(ShipTypeHints.PHASE))) ? "Phase ships" : "Warships";
                t.put("type", one(type));
                break;
            }
            case "weapon": {
                WeaponSpecAPI w = (WeaponSpecAPI) p;
                t.put("tech", one(w.getManufacturer()));
                boolean fighterWeapon = w.getAIHints() != null && w.getAIHints().contains(WeaponAPI.AIHints.SYSTEM);
                t.put("size", one(fighterWeapon ? "Fighter" : w.getSize() == null ? "Other" : w.getSize().getDisplayName()));
                JSONArray type = new JSONArray();
                if (w.getType() != null) {
                    type.put(w.getType().getDisplayName());
                }
                WeaponType mt = w.getMountType();
                if (mt == WeaponType.HYBRID || mt == WeaponType.COMPOSITE || mt == WeaponType.SYNERGY || mt == WeaponType.UNIVERSAL) {
                    type.put(mt.getDisplayName());
                }
                if (w.isBeam()) {
                    type.put("Beam");
                }
                t.put("type", type);
                DamageType dt = w.getDamageType();
                t.put("dmg", one(dt == DamageType.HIGH_EXPLOSIVE ? "High explosive" : dt == DamageType.KINETIC ? "Kinetic"
                        : dt == DamageType.FRAGMENTATION ? "Fragmentation" : dt == DamageType.ENERGY ? "Energy" : "Other"));
                break;
            }
            case "fighter": {
                FighterWingSpecAPI f = (FighterWingSpecAPI) p;
                ShipHullSpecAPI h = f.getVariant() == null ? null : f.getVariant().getHullSpec();
                if (h != null) {
                    t.put("tech", one(h.getManufacturer()));
                }
                String role = f.getRole() == null ? "" : f.getRole().name();
                t.put("type", one("FIGHTER".equals(role) ? "Fighters" : "BOMBER".equals(role) ? "Bombers" : "INTERCEPTOR".equals(role) ? "Interceptors" : "Other"));
                break;
            }
            case "hullmod": {
                HullModSpecAPI m = (HullModSpecAPI) p;
                t.put("tech", one(m.getManufacturer()));
                JSONArray type = new JSONArray();
                if (m.getUITags() != null) {
                    for (String ui : m.getUITags()) {
                        if (ui != null && !ui.isEmpty()) {
                            type.put(ui);
                        }
                    }
                }
                if (m.hasTag("dmod")) {
                    type.put("D-mods");
                } else if (m.isHidden()) {
                    type.put("Intrinsic");
                }
                t.put("type", type);
                break;
            }
            case "system": {
                ShipSystemSpecAPI s = (ShipSystemSpecAPI) p;
                Description d = desc(s.getId(), Description.Type.SHIP_SYSTEM);
                t.put("type", one(d != null && d.hasText2() ? d.getText2().trim() : "Special"));
                break;
            }
            case "skill": {
                SkillSpecAPI k = (SkillSpecAPI) p;
                t.put("apt", one(k.hasTag("ai_core_only") ? "AI core" : k.getGoverningAptitudeName()));
                t.put("type", one(k.isElite() ? "Piloted ship" : "Other"));
                break;
            }
            default:
                break;
        }
        return t;
    }

    // ------------------------------------------------------------------ the entry

    /** {@code GET codex/entry/<id>}: null = unknown / invisible (404); {@code locked:true} alone = a locked entry (423). */
    static JSONObject entry(String id) throws JSONException {
        return entry(id, false);
    }

    /**
     * Why Threat / Omega weapons once showed far less on hover: those specs carry
     * {@code codex_unlockable} and stay LOCKED until met in a campaign, so the hover's lazy panel got the 423 stub. {@code forBuild}
     * = the editor's hover view ({@code ?b=1}): a locked entry the builder can already fit is detailed there; the codex page keeps
     * the lock (its calls never set the flag). Invisible entries stay 404 either way.
     */
    static JSONObject entry(String id, boolean forBuild) throws JSONException {
        CodexEntryPlugin e;
        try {
            e = CodexDataV2.getEntry(id);
        } catch (RuntimeException missing) {
            return null;
        }
        if (e == null || !e.isVisible()) {
            return null;
        }
        String parentId = e.getParent() == null || e.getParent().getParent() == null || e.getParent().getParent().getParent() == null
                ? null : e.getParent().getId();   // root → category → entry → (module): a module's parent is an entry
        String kind = kind(e, parentId);
        if (kind == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("kind", kind);
        if (e.isLocked() && !forBuild) {
            o.put("locked", true);
            return o;
        }
        o.put("title", nz(e.getTitle()));
        o.put("icon", iconLane(e, kind));
        try {
            switch (kind) {
                case "hull": case "station": case "module": shipDetail(o, hullOf(e), kind); break;
                case "weapon": weaponDetail(o, (WeaponSpecAPI) e.getParam()); break;
                case "fighter": fighterDetail(o, (FighterWingSpecAPI) e.getParam()); break;
                case "hullmod": hullmodDetail(o, (HullModSpecAPI) e.getParam()); break;
                case "system": systemDetail(o, (ShipSystemSpecAPI) e.getParam()); break;
                case "skill": skillDetail(o, (SkillSpecAPI) e.getParam()); break;
                default: break;
            }
        } catch (RuntimeException | LinkageError broke) {
            o.put("detailError", String.valueOf(broke));   // the page still paints the title + related (vanilla's error card)
        }
        o.put("related", related(e));
        return o;
    }

    private static JSONArray related(CodexEntryPlugin e) throws JSONException {
        List<CodexEntryPlugin> rows = new ArrayList<>();
        for (CodexEntryPlugin r : e.getRelatedEntries()) {
            try {
                if (r != null && r.getId() != null && r.isVisible() && !r.isLocked() && kind(r, null) != null) {
                    rows.add(r);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // a throwing related entry is dropped, the list stands
            }
        }
        if (!e.isRetainOrderOfRelatedEntries()) {
            rows.sort((a, b) -> {
                int ta = Float.compare(tier(a), tier(b));
                return ta != 0 ? ta : nz(a.getSortTitle()).compareToIgnoreCase(nz(b.getSortTitle()));
            });
        }
        JSONArray out = new JSONArray();
        for (CodexEntryPlugin r : rows) {
            String kind = kind(r, null);
            JSONObject o = new JSONObject();
            o.put("id", r.getId());
            o.put("title", nz(r.getTitle()));
            o.put("kind", kind);
            o.put("icon", iconLane(r, kind));
            o.put("cat", r.getParent() == null ? "" : nz(r.getParent().getTitle()));
            out.put(o);
        }
        return out;
    }

    private static float tier(CodexEntryPlugin r) {
        return r.getParent() == null ? 1000f : r.getParent().getCategorySortTierForRelatedEntries();
    }

    // ------------------------------------------------------------------ ships

    private static void shipDetail(JSONObject o, ShipHullSpecAPI h, String kind) throws JSONException {
        if (h == null) {
            return;
        }
        o.put("subtitle", join(sizeWord(h.getHullSize()), design(h.getManufacturer())));
        o.put("sprite", "sprite/" + h.getHullId());
        ShipVariantAPI v = codexVariant(h);
        boolean limited = v != null && v.hasTag("ship_limited_tooltip");
        Description d = desc(h.getDescriptionId(), Description.Type.SHIP);
        JSONArray paras = new JSONArray();
        if (limited) {
            if (d != null && d.hasText3()) {
                addParas(paras, d.getText3Paras());
            } else {
                // A limited hull with no third text of its own shows the game's stock limited words.
                // They are read from the install (five stock hulls carry them as their third text, this one among them),
                // never carried in this jar; a missing donor row = no paragraphs.
                Description stock = desc(LIMITED_WORDS_DONOR_HULL, Description.Type.SHIP);
                if (stock != null && stock.hasText3()) {
                    addParas(paras, stock.getText3Paras());
                }
            }
        } else {
            if (h.getDescriptionPrefix() != null && !h.getDescriptionPrefix().trim().isEmpty()) {
                para(paras, h.getDescriptionPrefix().trim(), "prefix");
            }
            if (d != null) {
                addParas(paras, d.getText1Paras());
            }
        }
        o.put("paras", paras);
        boolean station = "station".equals(kind);
        if (!limited && !station && !h.hasTag("no_sell") && !h.hasTag("monster")) {
            o.put("baseValue", Math.round(h.getBaseValue()));
        }
        JSONArray sections = new JSONArray();
        JSONArray lists = new JSONArray();
        if (!limited) {
            shipSections(sections, h, v, "module".equals(kind));
            shipLists(lists, h, v);
        }
        o.put("sections", sections);
        o.put("lists", lists);
        o.put("factions", factionsUsing(h.getBaseHullId(), true));
        if ("hull".equals(kind)) {
            JSONObject edit = new JSONObject();
            edit.put("hullId", h.getHullId());
            o.put("edit", edit);
        }
    }

    private static ShipVariantAPI codexVariant(ShipHullSpecAPI h) {
        String vid = h.getCodexVariantId() != null && !h.getCodexVariantId().isEmpty() ? h.getCodexVariantId() : h.getHullId() + "_Hull";
        ShipVariantAPI v = null;
        try {
            v = Global.getSettings().getVariant(vid);
        } catch (RuntimeException missing) {
            v = null;
        }
        if (v == null) {
            try {
                v = Global.getSettings().createEmptyVariant(h.getHullId() + "_cc_codex", h);
            } catch (RuntimeException unbuildable) {
                v = null;
            }
        }
        return v;
    }

    private static void shipSections(JSONArray sections, ShipHullSpecAPI h, ShipVariantAPI v, boolean module) throws JSONException {
        JSONArray log = new JSONArray();
        JSONArray combat = new JSONArray();
        FleetMemberAPI m = null;
        MutableShipStatsAPI st = null;
        if (v != null) {
            try {
                m = Global.getSettings().createFleetMember(FleetMemberType.SHIP, v);
                m.getCrewComposition().setCrew(m.getMinCrew());
                m.getRepairTracker().setCR(Float.parseFloat(NEUTRAL_CR));
                m.updateStats();
                st = m.getStats();
            } catch (RuntimeException unavailable) {
                st = null;
            }
        }
        if (st != null && !module) {
            row(log, "CR per deployment", pct(st.getCRPerDeploymentPercent().computeEffective(h.getCRToDeploy())));
            row(log, "Recovery rate (per day)", pct(st.getBaseCRRecoveryRatePercentPerDay().getModifiedValue()));
            row(log, "Recovery cost (supplies)", fmt(m.getDeploymentCostSupplies()));
            row(log, "Deployment points", fmt(m.getDeploymentPointsCost()));
            row(log, "Peak performance (sec)", h.getCRLossPerSecond() > 0f ? fmt(st.getPeakCRDuration().computeEffective(h.getNoCRLossTime())) : "Unlimited");
            row(log, "Crew complement", m.getMinCrew() + " / " + m.getMaxCrew());
            row(log, "Sensor profile", fmt(st.getSensorProfile().getModifiedValue()));
            row(log, "Sensor strength", fmt(st.getSensorStrength().getModifiedValue()));
            row(log, "Maintenance (supplies/mo)", fmt1(st.getSuppliesPerMonth().getModifiedValue()));
            row(log, "Cargo capacity", fmt(st.getCargoMod().computeEffective(h.getCargo())));
            row(log, "Maximum crew", fmt(st.getMaxCrewMod().computeEffective(h.getMaxCrew())));
            row(log, "Skeleton crew required", fmt(st.getMinCrewMod().computeEffective(h.getMinCrew())));
            row(log, "Fuel capacity", fmt(st.getFuelMod().computeEffective(h.getFuel())));
            row(log, "Maximum burn", fmt(st.getMaxBurnLevel().getModifiedValue()));
            row(log, "Fuel / light year", fmt1(st.getFuelUseMod().computeEffective(h.getFuelPerLY())));
        }
        row(log, h.hasTag("monster") ? "Relative size" : "Hull size", sizeWord(h.getHullSize()));
        row(log, "Ordnance points", fmt(h.getOrdnancePoints(null)));
        section(sections, "Logistical data", log);
        if (st != null) {
            row(combat, "Hull integrity", fmt(st.getHullBonus().computeEffective(h.getHitpoints())));
            row(combat, "Armor rating", fmt(st.getArmorBonus().computeEffective(h.getArmorRating())));
            ShieldSpecAPI sh = h.getShieldSpec();
            ShieldType type = sh == null ? null : sh.getType();
            if (type == null || type == ShieldType.NONE) {
                row(combat, "Defense", "None");
            } else if (type == ShieldType.PHASE) {
                String name = systemName(h.getShipDefenseId());
                row(combat, "Defense", name == null ? "Phase cloak" : name);
                row(combat, "Cloak activation cost", fmt(st.getPhaseCloakActivationCostBonus().computeEffective(sh.getPhaseCost())));
                row(combat, "Cloak upkeep/sec", fmt(st.getPhaseCloakUpkeepCostBonus().computeEffective(sh.getPhaseUpkeep())));
            } else {
                row(combat, "Defense", type == ShieldType.OMNI ? "Omni shield" : "Front shield");
                row(combat, "Shield arc", fmt(Math.min(360f, st.getShieldArcBonus().computeEffective(sh.getArc()))));
                row(combat, "Shield upkeep/sec", fmt(st.getShieldUpkeepMult().getModifiedValue() * sh.getUpkeepCost()));
                row(combat, "Shield flux/damage", fmt2(st.getShieldDamageTakenMult().getModifiedValue() * st.getShieldAbsorptionMult().getModifiedValue() * sh.getFluxPerDamageAbsorbed()));
            }
            row(combat, "Flux capacity", fmt(st.getFluxCapacity().getModifiedValue()));
            row(combat, "Flux dissipation", fmt(st.getFluxDissipation().getModifiedValue()));
            row(combat, "Top speed", fmt(st.getMaxSpeed().getModifiedValue()));
        } else {
            row(combat, "Hull integrity", fmt(h.getHitpoints()));
            row(combat, "Armor rating", fmt(h.getArmorRating()));
            row(combat, "Flux capacity", fmt(h.getFluxCapacity()));
            row(combat, "Flux dissipation", fmt(h.getFluxDissipation()));
        }
        section(sections, "Combat performance", combat);
    }

    private static void shipLists(JSONArray lists, ShipHullSpecAPI h, ShipVariantAPI v) throws JSONException {
        // Mounts: vanilla groups the weapon slots by size + type ("2× Small Ballistic"), then the fighter bays
        Map<String, Integer> mounts = new LinkedHashMap<>();
        for (WeaponSlotAPI s : h.getAllWeaponSlotsCopy()) {
            if (s == null || !s.isWeaponSlot() || s.isDecorative() || s.getSlotSize() == null || s.getWeaponType() == null) {
                continue;
            }
            String key = s.getSlotSize().getDisplayName() + " " + s.getWeaponType().getDisplayName();
            mounts.merge(key, 1, Integer::sum);
        }
        JSONArray mountItems = new JSONArray();
        for (Map.Entry<String, Integer> en : mounts.entrySet()) {
            item(mountItems, en.getValue() + "× " + en.getKey(), null);
        }
        if (h.getFighterBays() > 0) {
            item(mountItems, h.getFighterBays() + "× Fighter bay", null);
        }
        list(lists, "Mounts", mountItems);
        // Armaments: the built-in weapons and wings
        Map<String, Integer> arms = new LinkedHashMap<>();
        Map<String, String> armIds = new LinkedHashMap<>();
        if (h.getBuiltInWeapons() != null) {
            for (String wid : h.getBuiltInWeapons().values()) {
                String name = weaponName(wid);
                if (name != null) {
                    arms.merge(name, 1, Integer::sum);
                    armIds.put(name, CodexDataV2.getWeaponEntryId(wid));
                }
            }
        }
        JSONArray armItems = new JSONArray();
        for (Map.Entry<String, Integer> en : arms.entrySet()) {
            item(armItems, (en.getValue() > 1 ? en.getValue() + "× " : "") + en.getKey(), armIds.get(en.getKey()));
        }
        if (h.getBuiltInWings() != null) {
            for (String wid : h.getBuiltInWings()) {
                String name = wingName(wid);
                if (name != null) {
                    item(armItems, name, CodexDataV2.getFighterEntryId(wid));
                }
            }
        }
        list(lists, "Armaments", armItems);
        JSONArray modItems = new JSONArray();
        if (h.getBuiltInMods() != null) {
            for (String mid : h.getBuiltInMods()) {
                String name = hullmodName(mid);
                if (name != null) {
                    item(modItems, name, CodexDataV2.getHullmodEntryId(mid));
                }
            }
        }
        list(lists, "Hull mods", modItems);
        JSONArray sys = new JSONArray();
        String sid = h.getShipSystemId();
        String sname = systemName(sid);
        if (sname != null) {
            item(sys, sname, CodexDataV2.getShipSystemEntryId(sid));
        }
        list(lists, "System", sys);
        if (!h.isPhase()) {
            String did = h.getShipDefenseId();
            String dname = systemName(did);
            if (dname != null) {
                JSONArray def = new JSONArray();
                item(def, dname, CodexDataV2.getShipSystemEntryId(did));
                list(lists, "Special", def);
            }
        }
    }

    // ------------------------------------------------------------------ weapons

    private static void weaponDetail(JSONObject o, WeaponSpecAPI w) throws JSONException {
        String size = w.getSize() == null ? "" : w.getSize().getDisplayName();
        String mount = w.getMountType() == null ? "" : w.getMountType().getDisplayName();
        o.put("subtitle", join(size + " " + mount + " weapon", design(w.getManufacturer())));
        Description d = desc(w.getWeaponId(), Description.Type.WEAPON);
        JSONArray paras = new JSONArray();
        boolean attrib = d != null && d.hasText2() && d.getText2().trim().startsWith("-");
        if (d != null) {
            for (String p : d.getText1Paras()) {
                para(paras, p, attrib ? "quote" : "");
            }
            if (attrib) {
                para(paras, d.getText2().trim(), "attrib");
            }
        }
        o.put("paras", paras);
        if (!w.hasTag("no_sell")) {
            o.put("baseValue", Math.round(w.getBaseValue()));
        }
        JSONArray sections = new JSONArray();
        JSONArray primary = new JSONArray();
        DerivedWeaponStatsAPI ds = w.getDerivedStats();
        boolean standard = !w.hasTag("no_standard_data");
        Object proj = w.getProjectileSpec();
        boolean beam = w.isBeam();
        MissileSpecAPI missile = proj instanceof MissileSpecAPI ? (MissileSpecAPI) proj : null;
        ProjectileSpecAPI plain = missile == null && proj instanceof ProjectileSpecAPI ? (ProjectileSpecAPI) proj : null;
        ProjectileWeaponSpecAPI pw = w instanceof ProjectileWeaponSpecAPI ? (ProjectileWeaponSpecAPI) w : null;
        String role = w.getPrimaryRoleStr() != null && !w.getPrimaryRoleStr().trim().isEmpty() ? w.getPrimaryRoleStr().trim()
                : d != null && d.hasText2() && !attrib ? d.getText2().trim() : null;
        if (role != null) {
            row(primary, "Primary role", role);
        }
        WeaponType mt = w.getMountType();
        String mountHint = mt == WeaponType.HYBRID ? "fits a ballistic or an energy slot" : mt == WeaponType.COMPOSITE ? "fits a ballistic or a missile slot"
                : mt == WeaponType.SYNERGY ? "fits an energy or a missile slot" : mt == WeaponType.UNIVERSAL ? "fits any type of slot" : null;
        row(primary, "Mount type", size + ", " + mount, mountHint);
        if (mt != null && w.getType() != null && mt != w.getType()) {
            row(primary, "", "Counts as " + w.getType().getDisplayName() + " for stat modifiers");
        }
        float op = w.getOrdnancePointCost(null);
        row(primary, "Ordnance points", op <= 0 || w.getType() == WeaponType.SYSTEM ? "n/a" : fmt(op));
        if (standard) {
            row(primary, "Range", fmt(w.getMaxRange()));
            String damage = null;
            if (w.hasTag("damage_special")) {
                damage = "Special";
            } else if (missile != null && missile.getDamage() != null) {
                damage = fmt(missile.getDamage().getDamage());
            } else if (plain != null && plain.getDamage() != null) {
                damage = fmt(plain.getDamage().getDamage());
                if (w.getBurstSize() > 1) {
                    damage += " × " + w.getBurstSize();
                }
            } else if (beam && w.isBurstBeam() && ds != null) {
                damage = fmt(ds.getBurstDamage());
            }
            if (damage != null) {
                row(primary, "Damage", damage);
            }
        }
        if (ds != null && !w.isNoDPSInTooltip()) {
            boolean sustained = Math.abs(ds.getDps() - ds.getSustainedDps()) > 0.05f;
            row(primary, sustained ? "Damage / second (sustained)" : "Damage / second", sustained ? fmt1(ds.getDps()) + " (" + fmt1(ds.getSustainedDps()) + ")" : fmt1(ds.getDps()));
            float emp = beam && !w.isBurstBeam() ? ds.getEmpPerSecond() : ds.getEmpPerShot();
            if (emp > 0) {
                row(primary, beam && !w.isBurstBeam() ? "EMP DPS" : "EMP damage", fmt1(emp));
            }
            boolean fluxes = ds.getSustainedFluxPerSecond() > 0;
            if (fluxes) {
                boolean sustF = Math.abs(ds.getFluxPerSecond() - ds.getSustainedFluxPerSecond()) > 0.05f;
                row(primary, sustF ? "Flux / second (sustained)" : "Flux / second", sustF ? fmt1(ds.getFluxPerSecond()) + " (" + fmt1(ds.getSustainedFluxPerSecond()) + ")" : fmt1(ds.getFluxPerSecond()));
                if (pw != null) {
                    row(primary, "Flux / shot", fmt1(pw.getEnergyPerShot()));
                }
                row(primary, emp > 0 ? "Flux / non-EMP damage" : "Flux / damage", fmt2(ds.getFluxPerDam()));
            }
            if (!fluxes || (w.usesAmmo() && w.getAmmoPerSecond() <= 0)) {
                String note = !w.usesAmmo() ? "No flux cost to fire"
                        : (w.getDamageType() == DamageType.ENERGY && w.getType() != WeaponType.MISSILE ? "Limited charges (" : "Limited ammo (") + w.getMaxAmmo() + ")";
                row(primary, "", note);
            }
        }
        if (w.getCustomPrimary() != null && !w.getCustomPrimary().trim().isEmpty()) {
            row(primary, "", w.getCustomPrimary().trim());
        }
        section(sections, "Primary data", primary);
        JSONArray anc = new JSONArray();
        if (standard && w.getDamageType() != null) {
            boolean soft = beam || w.hasTag("damage_soft_flux");
            row(anc, "Damage type", w.getDamageType().getDisplayName() + (soft ? " (Beam)" : ""), nz(w.getDamageType().getDescription()) + (soft ? " (no hard flux)" : ""));
        }
        String speed = str(w.getSpeedStr()) != null ? str(w.getSpeedStr()) : missile != null ? str(missile.getSpeedDisplayName()) : null;
        if (speed != null) {
            row(anc, "Speed", speed);
        }
        String tracking = str(w.getTrackingStr()) != null ? str(w.getTrackingStr()) : missile != null ? str(missile.getManeuverabilityDisplayName()) : null;
        if (tracking != null) {
            row(anc, "Tracking", tracking);
        }
        if (missile != null && missile.getHullSpec() != null) {
            row(anc, "Hitpoints", fmt(missile.getHullSpec().getHitpoints()));
        }
        String acc = str(w.getAccuracyStr()) != null ? str(w.getAccuracyStr()) : pw != null && missile == null ? str(pw.getAccuracyDisplayName()) : beam ? "Perfect" : null;
        if (acc != null) {
            row(anc, "Accuracy", acc);
        }
        if (str(w.getTurnRateStr()) != null) {
            row(anc, "Turn rate", str(w.getTurnRateStr()));
        }
        if (w.usesAmmo() && w.getAmmoPerSecond() > 0) {
            boolean charges = w.getDamageType() == DamageType.ENERGY && w.getType() != WeaponType.MISSILE;
            row(anc, charges ? "Max charges" : "Max ammo", String.valueOf(w.getMaxAmmo()));
            row(anc, charges ? "Seconds / recharge" : "Seconds / reload", fmt1(w.getReloadSize() / w.getAmmoPerSecond()));
            row(anc, charges ? "Charges gained" : "Reload size", fmt(w.getReloadSize()));
        }
        if (w.getBurstSize() > 1) {
            row(anc, "Burst size", String.valueOf(w.getBurstSize()));
        }
        if (pw != null && ds != null) {
            row(anc, "Refire delay (seconds)", fmt1(pw.getRefireDelay() + (w.isInterruptibleBurst() ? 0f : ds.getBurstFireDuration())));
        } else if (beam && w.isBurstBeam()) {
            row(anc, "Refire delay (seconds)", fmt1(w.getBeamChargeupTime() + w.getBeamChargedownTime() + w.getBurstDuration()));
        }
        if (w.getCustomAncillary() != null && !w.getCustomAncillary().trim().isEmpty()) {
            row(anc, "", w.getCustomAncillary().trim());
        }
        section(sections, "Ancillary data", anc);
        o.put("sections", sections);
        o.put("lists", new JSONArray());
        o.put("factions", new JSONArray());
    }

    // ------------------------------------------------------------------ fighters

    private static void fighterDetail(JSONObject o, FighterWingSpecAPI f) throws JSONException {
        ShipVariantAPI v = f.getVariant();
        ShipHullSpecAPI h = v == null ? null : v.getHullSpec();
        String role = f.getRoleDesc() != null && !f.getRoleDesc().trim().isEmpty() ? f.getRoleDesc().trim() : ucFirst(f.getRole() == null ? "" : f.getRole().name());
        o.put("subtitle", join(role + " wing", h == null ? null : design(h.getManufacturer())));
        o.put("sprite", "sprite/wing/" + f.getId());
        JSONArray paras = new JSONArray();
        if (h != null) {
            Description d = desc(h.getHullId(), Description.Type.SHIP);
            if (d != null) {
                addParas(paras, d.getText1Paras());
            }
        }
        o.put("paras", paras);
        if (!f.hasTag("no_sell")) {
            o.put("baseValue", Math.round(f.getBaseValue()));
        }
        JSONArray sections = new JSONArray();
        JSONArray tech = new JSONArray();
        boolean swarm = f.hasTag("swarm_fighter");
        row(tech, "Primary role", role);
        if (!swarm) {
            row(tech, "Ordnance points", fmt(f.getOpCost(null)));
            if (h != null) {
                row(tech, "Crew per fighter", fmt(h.getMinCrew()));
            }
            row(tech, f.isSupport() ? "Maximum support range" : "Maximum engagement range", fmt(f.getRange()));
            row(tech, "Fighters in wing", String.valueOf(f.getNumFighters()));
            row(tech, "Base replacement time (seconds)", fmt(f.getRefitTime()));
        }
        if (h != null) {
            row(tech, "Hull integrity", fmt(h.getHitpoints()));
            if (h.getArmorRating() > 1) {
                row(tech, "Armor rating", fmt(h.getArmorRating()));
            }
            ShieldSpecAPI sh = h.getShieldSpec();
            ShieldType type = sh == null ? null : sh.getType();
            if (type == ShieldType.PHASE) {
                String name = systemName(h.getShipDefenseId());
                row(tech, "Defense", name == null ? "Phase cloak" : name);
            } else if (type == ShieldType.FRONT || type == ShieldType.OMNI) {
                row(tech, type == ShieldType.OMNI ? "Omni shield" : "Front shield", fmt(sh.getArc()) + "° arc, " + fmt2(sh.getFluxPerDamageAbsorbed()) + " flux/damage");
            }
            if (h.getEngineSpec() != null) {
                row(tech, "Top speed", fmt(h.getEngineSpec().getMaxSpeed()));
            }
        }
        section(sections, "Technical data", tech);
        o.put("sections", sections);
        JSONArray lists = new JSONArray();
        if (h != null) {
            JSONArray sys = new JSONArray();
            String sname = systemName(h.getShipSystemId());
            item(sys, sname == null ? "None" : sname, sname == null ? null : CodexDataV2.getShipSystemEntryId(h.getShipSystemId()));
            list(lists, "System", sys);
            if (!h.isPhase()) {
                String dname = systemName(h.getShipDefenseId());
                if (dname != null) {
                    JSONArray def = new JSONArray();
                    item(def, dname, CodexDataV2.getShipSystemEntryId(h.getShipDefenseId()));
                    list(lists, "Special", def);
                }
            }
        }
        Map<String, Integer> arms = new LinkedHashMap<>();
        Map<String, String> armIds = new LinkedHashMap<>();
        if (v != null) {
            for (String slot : v.getFittedWeaponSlots()) {
                WeaponSpecAPI w = v.getWeaponSpec(slot);
                if (w != null) {
                    arms.merge(nz(w.getWeaponName()), 1, Integer::sum);
                    armIds.put(nz(w.getWeaponName()), CodexDataV2.getWeaponEntryId(w.getWeaponId()));
                }
            }
        }
        JSONArray armItems = new JSONArray();
        for (Map.Entry<String, Integer> en : arms.entrySet()) {
            item(armItems, (en.getValue() > 1 ? en.getValue() + "× " : "") + en.getKey(), armIds.get(en.getKey()));
        }
        if (armItems.length() == 0) {
            item(armItems, "None", null);
        }
        list(lists, "Armaments", armItems);
        JSONArray modItems = new JSONArray();
        if (v != null) {
            for (String mid : v.getHullMods()) {
                String name = hullmodName(mid);
                if (name != null) {
                    item(modItems, name, CodexDataV2.getHullmodEntryId(mid));
                }
            }
        }
        if (modItems.length() == 0) {
            item(modItems, "None", null);
        }
        list(lists, "Hull mods", modItems);
        o.put("lists", lists);
        o.put("factions", factionsUsing(f.getId(), false));
    }

    // ------------------------------------------------------------------ hullmods, ship systems, skills

    private static void hullmodDetail(JSONObject o, HullModSpecAPI m) throws JSONException {
        o.put("subtitle", join("Hullmod", design(m.getManufacturer())));
        JSONArray paras = new JSONArray();
        String text = null;
        try {
            text = m.getDescription(HullSize.CAPITAL_SHIP);   // the codex fills the %s with the CAPITAL params
        } catch (RuntimeException | LinkageError effectBroke) {
            text = null;
        }
        if (text == null || text.trim().isEmpty()) {
            text = m.getDescriptionFormat() == null ? "" : m.getDescriptionFormat().replace("%s", "…");
        }
        if (ScriptDescribed.isPlaceholder(text)) {   // converted_hangar / hiressensors — vanilla's script words
            String scripted = ScriptDescribed.text(m);
            text = scripted != null ? scripted : "";   // A sentinel with no hand text is dropped, never painted raw
        }
        for (String p : text.trim().split("\n")) {
            if (!p.trim().isEmpty()) {
                para(paras, p.trim(), "");
            }
        }
        if (m.getSModEffectFormat() != null && !m.getSModEffectFormat().trim().isEmpty()) {
            String s = null;
            try {
                s = m.getSModDescription(HullSize.CAPITAL_SHIP);
            } catch (RuntimeException | LinkageError effectBroke) {
                s = null;
            }
            if (s != null && !s.trim().isEmpty()) {
                boolean penalty = false;   // Vanilla's red "S-mod penalty" heading when the effect says so
                try {
                    penalty = m.getEffect() != null && m.getEffect().isSModEffectAPenalty();
                } catch (RuntimeException | LinkageError effectBroke) {
                    penalty = false;
                }
                para(paras, (penalty ? "S-mod penalty (built in): " : "S-mod bonus (built in): ") + s.trim(), penalty ? "smod neg" : "smod");
            }
        }
        if (m.hasTag("dmod")) {
            para(paras, "This is a d-mod: lasting damage a hull carries until it is restored.", "dmod");
        }
        o.put("paras", paras);
        JSONArray sections = new JSONArray();
        JSONArray op = new JSONArray();
        row(op, "Frigate", cost(m.getFrigateCost()));
        row(op, "Destroyer", cost(m.getDestroyerCost()));
        row(op, "Cruiser", cost(m.getCruiserCost()));
        row(op, "Capital ship", cost(m.getCapitalCost()));
        section(sections, "Ordnance points", op);
        o.put("sections", sections);
        o.put("lists", new JSONArray());
        o.put("factions", new JSONArray());
    }

    private static void systemDetail(JSONObject o, ShipSystemSpecAPI s) throws JSONException {
        Description d = desc(s.getId(), Description.Type.SHIP_SYSTEM);
        o.put("subtitle", "Type: " + (d != null && d.hasText2() ? d.getText2().trim() : "Special"));
        JSONArray paras = new JSONArray();
        if (d != null) {
            addParas(paras, d.getText1Paras());
            if (d.hasText5()) {
                para(paras, "Effects", "head");
                addParas(paras, d.getText5Paras());
            }
        }
        o.put("paras", paras);
        JSONArray sections = new JSONArray();
        JSONArray rows = new JSONArray();
        boolean ammo = s.usesAmmo();
        int maxUses = ammo ? s.getMaxUses(null) : 0;
        if (ammo) {
            row(rows, "Max charges", String.valueOf(maxUses));
            if (s.getRegen(null) > 0) {
                row(rows, "Seconds / recharge", fmt1(1f / Math.max(1e-5f, s.getRegen(null))));
            }
        }
        float active = s.getActive(), cooldown = s.getCooldown(null);
        if (active > 0.1f) {
            row(rows, "Active duration (seconds)", fmt1(active));
        }
        if (cooldown > 0) {
            row(rows, "Cooldown (seconds)", fmt1(cooldown));
        }
        float reuse = s.getIn() + active + s.getOut() + cooldown;
        if ((!ammo || maxUses > 1) && (!s.isToggle() || cooldown > 0) && Math.abs(reuse - cooldown) > 0.1f && Math.abs(reuse - active) > 0.1f) {
            row(rows, "Reuse delay (seconds)", fmt1(reuse));
        }
        if (s.getFluxPerUse() > 0) {
            row(rows, "Flux / use", s.getFluxPerUse() <= 1 ? "Minimal" : fmt(s.getFluxPerUse()));
        }
        if (s.getFluxPerUseBaseCap() > 0) {
            row(rows, "Base flux / use", pct(s.getFluxPerUseBaseCap() * 100f));
        }
        if (s.getFluxPerSecondBaseRate() > 0) {
            row(rows, "Flux / second, of base rate", pct(s.getFluxPerSecondBaseRate() * 100f));
        }
        if (s.getFluxPerSecondBaseCap() > 0) {
            row(rows, "Base flux / second", pct(s.getFluxPerSecondBaseCap() * 100f));
        }
        if (s.getFluxPerSecond() > 0) {
            row(rows, "Flux / second", s.getFluxPerSecond() <= 1 ? "Minimal" : fmt(s.getFluxPerSecond()));
        }
        if (s.getCrPerUse() > 0) {
            row(rows, "Combat readiness / use", pct(s.getCrPerUse() * 100f));
        }
        boolean anyFlux = s.getFluxPerUse() > 0 || s.getFluxPerSecond() > 0 || s.getFluxPerUseBaseCap() > 0 || s.getFluxPerSecondBaseRate() > 0 || s.getFluxPerSecondBaseCap() > 0;
        if (s.isToggle() && cooldown > 0) {
            row(rows, "", "Can be toggled off");
        }
        if (anyFlux && s.generatesHardFlux()) {
            row(rows, "", "Generates hard flux");
        }
        if (s.isRequiresZeroFluxBoost()) {
            row(rows, "", "Requires zero flux boost to activate");
        }
        if (!s.isDissipationAllowed()) {
            row(rows, "", "No flux dissipation while active");
        } else if (!s.isHardDissipationAllowed()) {
            row(rows, "", "No hard flux dissipation while active");
        }
        if (!s.isFiringAllowed()) {
            row(rows, "", "Weapons disabled while active");
        }
        if (!s.isShieldAllowed()) {
            row(rows, "", "Shields disabled while active");
        }
        if (!s.isTurningAllowed()) {
            row(rows, "", "Maneuvering thrusters disabled while active");
        }
        if (!s.isVentingAllowed()) {
            row(rows, "", "Active flux venting disabled while active");
        }
        section(sections, "System data", rows);
        o.put("sections", sections);
        o.put("lists", new JSONArray());
        o.put("factions", new JSONArray());
    }

    private static void skillDetail(JSONObject o, SkillSpecAPI k) throws JSONException {
        String apt = k.hasTag("ai_core_only") ? "AI core" : nz(k.getGoverningAptitudeName());
        o.put("subtitle", join(apt.isEmpty() ? null : apt + " skill", str(k.getScopeStr())));
        JSONArray paras = new JSONArray();
        if (k.getDescription() != null) {
            for (String p : k.getDescription().split("\n")) {
                if (!p.trim().isEmpty()) {
                    para(paras, p.trim(), "");
                }
            }
        }
        JSONArray effects = new JSONArray();
        try {
            for (SkillSpecAPI.SkillEffectSpecAPI ef : k.getEffectsAPI()) {
                // the game's getAs*() accessors are UNCHECKED casts of the script instance (SkillSpec.SkillEffectSpec):
                // a level-based effect throws ClassCastException from getAsDescriptionEffect() and vice versa — each
                // accessor gets its own try (the unconditional pair swallowed every effect line)
                String line = null;
                try {
                    LevelBasedEffect lb = ef.getAsLevelBasedEffect();
                    line = lb == null ? null : lb.getEffectDescription(1f);
                } catch (RuntimeException | LinkageError notLevelBased) {
                    try {
                        DescriptionSkillEffect de = ef.getAsDescriptionEffect();
                        line = de == null ? null : de.getString();
                    } catch (RuntimeException | LinkageError notDescription) {
                        line = null;
                    }
                }
                if (line != null && !line.trim().isEmpty()) {
                    item(effects, line.trim(), null);
                }
            }
        } catch (RuntimeException | LinkageError effectsBroke) {
            // the description alone
        }
        o.put("paras", paras);
        o.put("sections", new JSONArray());
        JSONArray lists = new JSONArray();
        if (effects.length() > 0) {
            list(lists, "Effects", effects);
        }
        o.put("lists", lists);
        o.put("factions", new JSONArray());
    }

    // ------------------------------------------------------------------ sprites (the same read-only file lane as the oracle's)

    static byte[] systemSpritePng(String id) {
        ShipSystemSpecAPI s;
        try {
            s = Global.getSettings().getShipSystemSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
        String rel = s == null ? null : s.getIconSpriteName();
        return EditorAssets.safeSpritePath(rel) ? EditorAssets.readGameFile(rel, EditorAssets.MAX_SPRITE_BYTES) : null;
    }

    static byte[] skillSpritePng(String id) {
        SkillSpecAPI k;
        try {
            k = Global.getSettings().getSkillSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
        String rel = k == null ? null : k.getSpriteName();
        return EditorAssets.safeSpritePath(rel) ? EditorAssets.readGameFile(rel, EditorAssets.MAX_SPRITE_BYTES) : null;
    }

    /** The codex category glyphs ({@code settings.json} sprite category "codex"); the key set is static. */
    static byte[] codexIconPng(String key) {
        if (!ICON_KEYS.contains(key)) {
            return null;
        }
        String rel;
        try {
            rel = Global.getSettings().getSpriteName("codex", key);
        } catch (RuntimeException missing) {
            return null;
        }
        return EditorAssets.safeSpritePath(rel) ? EditorAssets.readGameFile(rel, EditorAssets.MAX_SPRITE_BYTES) : null;
    }

    // ------------------------------------------------------------------ helpers

    private static Description desc(String id, Description.Type type) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return Global.getSettings().getDescription(id, type);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    /** Vanilla's typography: a {@code "…"} paragraph is a quote (italic); a following {@code -} line is its attribution. */
    private static void addParas(JSONArray paras, List<String> texts) throws JSONException {
        if (texts == null) {
            return;
        }
        boolean lastQuote = false;
        for (String p : texts) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            String t = p.trim();
            boolean quote = t.length() > 2 && t.startsWith("\"") && t.endsWith("\"");
            boolean attrib = lastQuote && t.startsWith("-");
            para(paras, t, quote ? "quote" : attrib ? "attrib" : "");
            lastQuote = quote;
        }
    }

    private static void para(JSONArray paras, String text, String style) throws JSONException {
        JSONObject p = new JSONObject();
        p.put("t", text);
        p.put("style", style);
        paras.put(p);
    }

    private static void row(JSONArray rows, String k, String v) throws JSONException {
        row(rows, k, v, null);
    }

    private static void row(JSONArray rows, String k, String v, String hint) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("k", k);
        r.put("v", v);
        if (hint != null && !hint.isEmpty()) {
            r.put("hint", hint);
        }
        rows.put(r);
    }

    private static void section(JSONArray sections, String title, JSONArray rows) throws JSONException {
        if (rows.length() == 0) {
            return;
        }
        JSONObject s = new JSONObject();
        s.put("title", title);
        s.put("rows", rows);
        sections.put(s);
    }

    private static void item(JSONArray items, String text, String entryId) throws JSONException {
        JSONObject i = new JSONObject();
        i.put("t", text);
        if (entryId != null) {
            i.put("id", entryId);
        }
        items.put(i);
    }

    private static void list(JSONArray lists, String title, JSONArray items) throws JSONException {
        if (items.length() == 0) {
            return;
        }
        JSONObject l = new JSONObject();
        l.put("title", title);
        l.put("items", items);
        lists.put(l);
    }

    /** Vanilla's "used by" element: the intel-tab factions whose known ships / fighters carry the id, in the faction's UI colour. */
    private static JSONArray factionsUsing(String id, boolean ship) throws JSONException {
        JSONArray out = new JSONArray();
        if (id == null) {
            return out;
        }
        try {
            for (FactionSpecAPI f : Global.getSettings().getAllFactionSpecs()) {
                if (f == null || !f.isShowInIntelTab()) {
                    continue;
                }
                Set<String> known = ship ? f.getKnownShips() : f.getKnownFighters();
                if (known == null || !known.contains(id)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("name", ucFirst(nz(f.getDisplayName())));
                o.put("color", hex(f.getBaseUIColor()));
                out.put(o);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // no factions line
        }
        return out;
    }

    private static String systemName(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            ShipSystemSpecAPI s = Global.getSettings().getShipSystemSpec(id);
            return s == null ? null : str(s.getName());
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static String weaponName(String id) {
        try {
            WeaponSpecAPI w = id == null ? null : Global.getSettings().getWeaponSpec(id);
            return w == null ? null : str(w.getWeaponName());
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static String wingName(String id) {
        try {
            FighterWingSpecAPI f = id == null ? null : Global.getSettings().getFighterWingSpec(id);
            return f == null ? null : str(f.getWingName());
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static String hullmodName(String id) {
        try {
            HullModSpecAPI m = id == null ? null : Global.getSettings().getHullModSpec(id);
            return m == null ? null : str(m.getDisplayName());
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static boolean settingBool(String key) {
        try {
            return Global.getSettings().getBoolean(key);
        } catch (RuntimeException missing) {
            return false;
        }
    }

    private static JSONArray one(String v) {
        JSONArray a = new JSONArray();
        if (v != null && !v.trim().isEmpty()) {
            a.put(v.trim());
        }
        return a;
    }

    private static String sizeGroup(HullSize s) {
        if (s == null) {
            return "Other";
        }
        switch (s) {
            case FRIGATE: return "Frigates";
            case DESTROYER: return "Destroyers";
            case CRUISER: return "Cruisers";
            case CAPITAL_SHIP: return "Capitals";
            default: return "Other";
        }
    }

    private static String sizeWord(HullSize s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case FIGHTER: return "Fighter";
            case FRIGATE: return "Frigate";
            case DESTROYER: return "Destroyer";
            case CRUISER: return "Cruiser";
            case CAPITAL_SHIP: return "Capital ship";
            default: return "";
        }
    }

    /** The manufacturer line vanilla paints unless it is "common". */
    private static String design(String manufacturer) {
        String m = str(manufacturer);
        return m == null || "common".equalsIgnoreCase(m) ? null : m;
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b == null ? "" : b;
        }
        return b == null || b.isEmpty() ? a : a + " · " + b;
    }

    private static String cost(int c) {
        return c <= 0 ? "---" : String.valueOf(c);
    }

    private static String pct(float v) {
        return Math.round(v) + "%";
    }

    static String fmt(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return "";
        }
        float r = Math.round(v * 10f) / 10f;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static String fmt1(float v) {
        return fmt(v);
    }

    private static String fmt2(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return "";
        }
        float r = Math.round(v * 100f) / 100f;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static String hex(Color c) {
        return c == null ? "#dcdcdc" : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String ucFirst(String s) {
        String t = nz(s).toLowerCase();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String str(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
