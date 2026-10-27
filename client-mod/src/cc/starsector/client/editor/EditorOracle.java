package cc.starsector.client.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShieldSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;

import cc.starsector.client.CcClientModPlugin;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;
import cc.starsector.client.net.ClientVariantReceipt;
import cc.starsector.client.net.PredictSession;
import cc.starsector.client.net.VariantRejectHints;
import cc.starsector.client.net.VariantSubmitWriter;
import cc.starsector.server.runtime.DoorRejectReasons;

/**
 * The editor's ORACLE (W15 v1): every validity and stat question the
 * browser asks is answered by the RUNNING GAME through the public modding API — catalog exports
 * ({@code /data/*}), one hull's slot geometry ({@code /data/hull/<id>}), the validation pipeline
 * on a detached variant ({@code /validate}), a detached fleet member's {@code MutableShipStatsAPI}
 * ({@code /stats}), the sprite bytes ({@code /sprite/<hull>}) and the local save
 * ({@code /save} → {@code saves/common/cc_variants/<stem>.variant.data}). Nothing here is a
 * reimplementation of engine arithmetic (legal rule 3); the server re-runs its own pass on
 * submission and stays the arbiter.
 *
 * <p>Threading: every method runs under {@link #LOCK} on a responder thread — the drill
 * proved {@code createFleetMember(SHIP, v).getStats()} off-thread at load
 * AND at the title screen with identical numbers; the lock keeps the engine's unsynchronized
 * spec stores single-reader from this side. Classloader-B: public API + org.json + java.net
 * URL streams only; catches are {@code IOException} / {@code RuntimeException} / {@code Throwable}.
 */
final class EditorOracle {
    static final Object LOCK = new Object();
    /** The server's refit-table base for vents AND capacitors ({@code RealEngineVariantRegistrar.baseVentOrCapMax}). */
    static int baseVentOrCapMax(HullSize size) {
        if (size == null) {
            return 100;
        }
        switch (size) {
            case FIGHTER: return 5;
            case FRIGATE: return 10;
            case DESTROYER: return 20;
            case CRUISER: return 30;
            case CAPITAL_SHIP: return 50;
            default: return 100;
        }
    }

    private static final int MAX_SHIP_FILE_BYTES = 1024 * 1024;

    private EditorOracle() {
    }

    // ------------------------------------------------------------------ catalogs

    static JSONArray hulls(EditorTier.Tier tier) throws JSONException {
        JSONArray out = new JSONArray();
        for (ShipHullSpecAPI h : Global.getSettings().getAllShipHullSpecs()) {
            String id = h.getHullId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", nz(h.getHullName()));
            o.put("designation", nz(h.getDesignation()));
            o.put("size", h.getHullSize() == null ? "" : h.getHullSize().name());
            o.put("op", h.getOrdnancePoints(null));
            o.put("dp", num(h.getSuppliesToRecover()));   // Deployment points (the hull's base)
            o.put("bays", h.getFighterBays());
            o.put("sprite", nz(h.getSpriteName()));
            o.put("dHull", h.isDHull());
            o.put("phase", h.isPhase());
            o.put("shield", h.getShieldType() == null ? "" : h.getShieldType().name());
            o.put("builtInMods", new JSONArray(nzList(h.getBuiltInMods())));
            String reason = EditorTier.hullRefusal(h, tier);
            o.put("allowed", reason == null);
            o.put("reason", reason == null ? JSONObject.NULL : reason);
            out.put(o);
        }
        return out;
    }

    static JSONArray weapons(EditorTier.Tier tier) throws JSONException {
        JSONArray out = new JSONArray();
        for (WeaponSpecAPI w : Global.getSettings().getAllWeaponSpecs()) {
            String id = w.getWeaponId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            out.put(weaponJson(w, tier));
        }
        return out;
    }

    /** One weapon's catalog row — shared by {@link #weapons} and a hull's {@code builtInWeaponSpecs}. */
    static JSONObject weaponJson(WeaponSpecAPI w, EditorTier.Tier tier) throws JSONException {
            String id = w.getWeaponId();
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", nz(w.getWeaponName()));
            o.put("size", w.getSize() == null ? "" : w.getSize().name());
            o.put("type", w.getType() == null ? "" : w.getType().name());
            o.put("mountType", w.getMountType() == null ? "" : w.getMountType().name());
            o.put("op", num(w.getOrdnancePointCost(null)));
            o.put("range", num(w.getMaxRange()));
            o.put("damageType", w.getDamageType() == null ? "" : w.getDamageType().name());
            o.put("beam", w.isBeam());
            // The groups panel's Flux column = vanilla's sum of per-spec SUSTAINED flux/sec (no ship
            // stats); dps / flux-per-damage feed the hovers; the burst pair too ("burst (sustained)", the refit's idiom). No derived stats = 0.
            WeaponAPI.DerivedWeaponStatsAPI ds = w.getDerivedStats();
            o.put("flux", num(ds == null ? 0f : ds.getSustainedFluxPerSecond()));
            o.put("fluxBurst", num(ds == null ? 0f : ds.getFluxPerSecond()));
            o.put("dps", num(ds == null ? 0f : ds.getSustainedDps()));
            o.put("dpsBurst", num(ds == null ? 0f : ds.getDps()));
            o.put("fluxPerDamage", num(ds == null ? 0f : ds.getFluxPerDam()));
            o.put("pd", w.getAIHints() != null && w.getAIHints().contains(WeaponAPI.AIHints.PD));
            // The magazine on the rows + hovers; the build's ammo law rides /stats.ammoMul, the SPA applies it to this base
            o.put("usesAmmo", w.usesAmmo());
            o.put("maxAmmo", w.usesAmmo() ? w.getMaxAmmo() : 0);
            o.put("ammoRegen", w.usesAmmo() && w.getAmmoPerSecond() > 0);
            o.put("tier", w.getTier());
            o.put("role", nz(w.getPrimaryRoleStr()));
            String reason = EditorTier.weaponRefusal(w, tier);
            o.put("allowed", reason == null);
            o.put("reason", reason == null ? JSONObject.NULL : reason);
            return o;
    }

    static JSONArray hullMods(EditorTier.Tier tier) throws JSONException {
        JSONArray out = new JSONArray();
        for (HullModSpecAPI m : Global.getSettings().getAllHullModSpecs()) {
            String id = m.getId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", nz(m.getDisplayName()));
            JSONObject cost = new JSONObject();
            cost.put("FRIGATE", m.getCostFor(HullSize.FRIGATE));
            cost.put("DESTROYER", m.getCostFor(HullSize.DESTROYER));
            cost.put("CRUISER", m.getCostFor(HullSize.CRUISER));
            cost.put("CAPITAL_SHIP", m.getCostFor(HullSize.CAPITAL_SHIP));
            o.put("cost", cost);
            o.put("dmod", EditorTier.isDMod(m));
            o.put("hidden", m.isHidden());
            o.put("noBuildIn", m.hasTag("no_build_in"));
            o.put("tier", m.getTier());
            o.put("uiTags", new JSONArray(nzList(m.getUITags())));
            // The picker's "Design type" column — the
            // manufacturer word in settings.json's designTypeColors (SettingsAPI.getDesignTypeColor; "Common" for most)
            String maker = nz(m.getManufacturer());
            o.put("manufacturer", maker);
            java.awt.Color mc = maker.isEmpty() ? null : Global.getSettings().getDesignTypeColor(maker);
            o.put("manufacturerColor", mc == null ? JSONObject.NULL : String.format("#%02x%02x%02x", mc.getRed(), mc.getGreen(), mc.getBlue()));
            String reason = EditorTier.hullModRefusal(m, tier);
            o.put("allowed", reason == null);
            o.put("reason", reason == null ? JSONObject.NULL : reason);
            out.put(o);
        }
        return out;
    }

    /** A settings.json number, or {@code fallback} when the key is absent (a modded settings file). */
    static float settingFloat(String key, float fallback) {
        try {
            return Global.getSettings().getFloat(key);
        } catch (RuntimeException missing) {
            return fallback;
        }
    }

    /**
     * Modified / (base + flat mods) — the percent × mult factor a flat step (one
     * capacitor, one vent) is scaled by; 1 when the stat is empty. The SPA multiplies the settings.json per-unit
     * numbers by it to paint the next step's flux numbers on the click, before this answer's successor lands.
     */
    static float statFactor(MutableStat s) {
        float flat = s.getBaseValue();
        for (MutableStat.StatMod m : s.getFlatMods().values()) {
            flat += m.value;
        }
        float modified = s.getModifiedValue();
        return flat > 0f && modified > 0f ? modified / flat : 1f;
    }

    /**
     * Iteration 6 row (g): the hullmod tooltip's text — every visible hullmod's
     * description for ONE hull size, {@code HullModSpecAPI.getDescription(size)} = the csv format
     * with the effect's {@code getDescriptionParam(i, size)} filled in (the codex / picker text,
     * no ship), plus the S-mod bonus text where the mod declares one ({@code getSModEffectFormat}).
     * Each call is guarded per mod: an effect that wants a sector or a broken format falls back to
     * the raw format with its {@code %s} marks read as "…", so one mod never blanks the list.
     * Game data, rendered by textContent — never player text.
     */
    static JSONObject hullModDescriptions(HullSize size, List<String> builtIn) throws JSONException {
        JSONObject out = new JSONObject();
        for (HullModSpecAPI m : Global.getSettings().getAllHullModSpecs()) {
            String id = m.getId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            // A hidden hullmod's text rides along ONLY when this hull builds it in
            // (the Monitor's Flux Shunt row is shown greyed there; every other hidden mod stays off the list, so no text)
            if (m.isHidden() && (builtIn == null || !builtIn.contains(id))) {
                continue;
            }
            JSONObject d = new JSONObject();
            d.put("d", descriptionOrFormat(m, size, false));
            String sFormat = m.getSModEffectFormat();
            if (sFormat != null && !sFormat.trim().isEmpty()) {
                d.put("s", descriptionOrFormat(m, size, true));
                // Vanilla heads the built-in line "S-mod penalty" in RED when the effect says so
                // (BaseHullMod.addSModSection → isSModEffectAPenalty: Heavy Armor, Expanded Missile Racks, the autoloader…). A word
                // heuristic is wrong both ways (autorepair's "Reduces…" is a bonus), so the effect's own bit rides along; unset = a bonus.
                try {
                    d.put("sNeg", m.getEffect() != null && m.getEffect().isSModEffectAPenalty());
                } catch (RuntimeException | LinkageError effectBroke) {
                    // unset = a bonus (the pre-round look)
                }
            }
            out.put(id, d);
        }
        return out;
    }

    private static String descriptionOrFormat(HullModSpecAPI m, HullSize size, boolean sMod) {
        String text = null;
        try {
            text = sMod ? m.getSModDescription(size) : m.getDescription(size);
        } catch (RuntimeException | LinkageError effectBroke) {
            text = null;   // the raw format below
        }
        if (text == null) {
            String format = sMod ? m.getSModEffectFormat() : m.getDescriptionFormat();
            text = format == null ? "" : format.replace("%s", "…");
        }
        if (!sMod && ScriptDescribed.isPlaceholder(text)) {   // The csv says "<Tooltip created in script>"
            String scripted = ScriptDescribed.text(m);
            // A sentinel with no hand text (the shrouded pair) is DROPPED, never served as "<created by script>"
            return scripted != null ? scripted : "";
        }
        return text.trim();
    }

    static JSONArray wings(EditorTier.Tier tier) throws JSONException {
        JSONArray out = new JSONArray();
        SettingsAPI settings = Global.getSettings();
        for (FighterWingSpecAPI g : settings.getAllFighterWingSpecs()) {
            String id = g.getId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", nz(g.getWingName()));
            o.put("op", num(g.getOpCost(null)));
            o.put("role", g.getRole() == null ? "" : g.getRole().name());
            o.put("numFighters", g.getNumFighters());
            o.put("tier", g.getTier());
            ShipHullSpecAPI fighterHull = null;
            try {
                ShipVariantAPI fv = g.getVariant();
                fighterHull = fv == null ? null : fv.getHullSpec();
            } catch (RuntimeException unresolved) {
                fighterHull = null;
            }
            String reason = EditorTier.wingRefusal(g, fighterHull, tier);
            o.put("allowed", reason == null);
            o.put("reason", reason == null ? JSONObject.NULL : reason);
            out.put(o);
        }
        return out;
    }

    // ------------------------------------------------------------------ one hull

    /** {@code null} when the id names no hull. */
    static JSONObject hull(String id, EditorTier.Tier tier, boolean allSkills) throws JSONException {
        ShipHullSpecAPI h = hullOrNull(id);
        if (h == null) {
            return null;
        }
        MutableCharacterStatsAPI person = EditorPersons.personStatsOrNull(allSkills);
        JSONObject o = new JSONObject();
        o.put("id", h.getHullId());
        o.put("name", nz(h.getHullName()));
        o.put("size", h.getHullSize() == null ? "" : h.getHullSize().name());
        o.put("sprite", nz(h.getSpriteName()));
        o.put("op", h.getOrdnancePoints(person));
        o.put("bays", h.getFighterBays());
        o.put("hp", num(h.getHitpoints()));
        o.put("armor", num(h.getArmorRating()));
        o.put("fluxCap", num(h.getFluxCapacity()));
        o.put("fluxDiss", num(h.getFluxDissipation()));
        o.put("collisionRadius", num(h.getCollisionRadius()));
        o.put("builtInMods", new JSONArray(nzList(h.getBuiltInMods())));
        o.put("builtInWings", new JSONArray(nzList(h.getBuiltInWings())));
        JSONObject builtInWeapons = new JSONObject();
        Map<String, String> biw = h.getBuiltInWeapons();
        if (biw != null) {
            for (Map.Entry<String, String> e : biw.entrySet()) {
                builtInWeapons.put(e.getKey(), nz(e.getValue()));
            }
        }
        o.put("builtInWeapons", builtInWeapons);
        // The built-in weapons' own rows (name / sprite / stats) — the catalog drops a
        // SYSTEM-hinted spec (the Onslaught's tpc: getAllWeaponSpecs() filters AIHints.SYSTEM), so the SPA read "tpc";
        // getWeaponSpec(id) resolves any id. The catalog stays the fittable universe; these ride the hull only.
        JSONObject builtInWeaponSpecs = new JSONObject();
        if (biw != null) {
            for (String wid : new java.util.LinkedHashSet<>(biw.values())) {
                WeaponSpecAPI bw = wid == null || wid.isEmpty() ? null : weaponOrNull(wid);
                if (bw != null) {
                    builtInWeaponSpecs.put(wid, weaponJson(bw, tier));
                }
            }
        }
        o.put("builtInWeaponSpecs", builtInWeaponSpecs);
        // The hull's deployment points = supplies to recover (FleetMember.getDeploymentPointsCost's base;
        // the Ship tab's LIVE number is /stats' deployPoints)
        o.put("dp", num(h.getSuppliesToRecover()));
        ShieldSpecAPI shield = h.getShieldSpec();
        if (shield != null) {
            JSONObject s = new JSONObject();
            s.put("type", shield.getType() == null ? "" : shield.getType().name());
            s.put("arc", num(shield.getArc()));
            s.put("radius", num(shield.getRadius()));
            s.put("centerX", num(shield.getCenterX()));
            s.put("centerY", num(shield.getCenterY()));
            s.put("upkeep", num(shield.getUpkeepCost()));
            s.put("fluxPerDamage", num(shield.getFluxPerDamageAbsorbed()));
            o.put("shield", s);
        }
        int base = baseVentOrCapMax(h.getHullSize());
        o.put("maxVents", ventOrCapMax(base, person == null ? null : person.getMaxVentsBonus()));
        o.put("maxCaps", ventOrCapMax(base, person == null ? null : person.getMaxCapacitorsBonus()));
        // iteration 6 row (g): the per-unit numbers the stepper's tooltip names = settings.json's
        // dissipationPerVent / fluxPerCapacitor (the game's constants; hullmod multipliers land on top in /stats,
        // whose delta the flux boxes already show) + every visible hullmod's description for THIS hull size
        o.put("perVent", num(settingFloat("dissipationPerVent", 10f)));
        o.put("perCap", num(settingFloat("fluxPerCapacitor", 200f)));
        o.put("modDesc", hullModDescriptions(h.getHullSize(), h.getBuiltInMods()));
        // The Ship tab's system line (the codex door to codex_system_<id>); name resolved through the API, "" when unset
        String sysId = nz(h.getShipSystemId());
        o.put("system", sysId);
        o.put("systemName", sysId.isEmpty() || Global.getSettings().getShipSystemSpec(sysId) == null ? "" : nz(Global.getSettings().getShipSystemSpec(sysId).getName()));
        o.put("center", spriteCenter(h));
        List<WeaponSpecAPI> allWeapons = new java.util.ArrayList<>(Global.getSettings().getAllWeaponSpecs());
        JSONArray slots = new JSONArray();
        for (WeaponSlotAPI s : h.getAllWeaponSlotsCopy()) {
            JSONObject so = new JSONObject();
            so.put("id", nz(s.getId()));
            so.put("size", s.getSlotSize() == null ? "" : s.getSlotSize().name());
            so.put("type", s.getWeaponType() == null ? "" : s.getWeaponType().name());
            so.put("angle", num(s.getAngle()));
            so.put("arc", num(s.getArc()));
            so.put("x", s.getLocation() == null ? 0 : num(s.getLocation().x));
            so.put("y", s.getLocation() == null ? 0 : num(s.getLocation().y));
            so.put("hardpoint", s.isHardpoint());
            so.put("builtIn", s.isBuiltIn());
            so.put("decorative", s.isDecorative());
            so.put("system", s.isSystemSlot());
            so.put("hidden", s.isHidden());
            so.put("stationModule", s.isStationModule());
            so.put("launchBay", s.getWeaponType() == WeaponType.LAUNCH_BAY);
            boolean fittable = EditorTier.isFittableSlot(s);
            so.put("fittable", fittable);
            // The size/type fit matrix is the ENGINE's answer per (slot, weapon) — the SPA filters
            // its pickers by this list and never re-derives SYNERGY/COMPOSITE/HYBRID/UNIVERSAL
            // itself; /validate re-asks weaponFits on submission.
            JSONArray fits = new JSONArray();
            if (fittable) {
                for (WeaponSpecAPI w : allWeapons) {
                    String wid = w.getWeaponId();
                    if (wid != null && !wid.isEmpty() && s.weaponFits(w)) {
                        fits.put(wid);
                    }
                }
            }
            so.put("fits", fits);
            slots.put(so);
        }
        o.put("slots", slots);
        String reason = EditorTier.hullRefusal(h, tier);
        o.put("allowed", reason == null);
        o.put("reason", reason == null ? JSONObject.NULL : reason);
        o.put("person", person == null ? "none" : "all-skills");
        return o;
    }

    /**
     * The sprite's ship-centre in sprite pixels from the {@code .ship} file:
     * the API exports slot {@code x/y} in ship coordinates (x toward the bow, +y the
     * ship's left) but not the origin those are measured from; the public
     * {@code SettingsAPI.loadJSON(getShipFilePath())} lane reads the file the engine itself loaded.
     * A SKIN's path is its {@code .skin} file (the loader clones the base hull, then re-points the
     * path), which carries no {@code center}: the base hull's
     * {@code .ship} is read then (every vanilla skin needs it; the Lasher (LC)'s
     * mounts floated 13 sprite px high on the PNG-midpoint fallback). {@code JSONObject.NULL} when
     * unreadable — the SPA then falls back to the sprite's midpoint. {@code y} is measured from the
     * sprite's BOTTOM edge (the engine's GL convention; {@code height} rides along so the SPA can flip it).
     */
    private static Object spriteCenter(ShipHullSpecAPI h) throws JSONException {
        return spriteCenter(h, 0);
    }

    /**
     * The spec's own file first (a skin's {@code .skin} is NOT strict JSON — org.json
     * threw "Missing value" on every vanilla skin, so the miss is any branch, not just a missing key);
     * on a miss, the base hull's {@code .ship} ({@code getBaseHull()}, else the spec by
     * {@code getBaseHullId()}); {@code depth} bounds the walk (a base is a base — one hop).
     */
    private static Object spriteCenter(ShipHullSpecAPI h, int depth) throws JSONException {
        ShipHullSpecAPI baseHull = null;
        if (!h.isBaseHull() && depth < 2) {
            baseHull = h.getBaseHull();
            String baseId = h.getBaseHullId();
            if (baseHull == null && baseId != null && !baseId.isEmpty() && !baseId.equals(h.getHullId())) {
                baseHull = Global.getSettings().getHullSpec(baseId);
            }
            if (baseHull == h) {
                baseHull = null;
            }
        }
        Object own = spriteCenterOwn(h, baseHull == null);
        if (own != JSONObject.NULL || baseHull == null) {
            return own;
        }
        return spriteCenter(baseHull, depth + 1);
    }

    /** The centre from THIS spec's ship file; {@code JSONObject.NULL} on any miss ({@code logMiss}: the sidelog row). */
    private static Object spriteCenterOwn(ShipHullSpecAPI h, boolean logMiss) throws JSONException {
        String path = h.getShipFilePath();
        if (path == null || path.isEmpty() || path.contains("..")) {
            return JSONObject.NULL;
        }
        try {
            // SettingsAPI.loadJSON refuses the ABSOLUTE path getShipFilePath() carries (its filename
            // law: no ':' / '\'), so the file rides the same B-legal URL lane as the sprite.
            byte[] bytes = EditorAssets.readGameFile(path, MAX_SHIP_FILE_BYTES);
            if (bytes == null) {
                return logMiss ? noCenter(h, new IOException("not readable: " + EditorText.sanitize(path, 120))) : JSONObject.NULL;
            }
            JSONObject ship = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray c = ship.optJSONArray("center");
            if (c == null || c.length() < 2) {
                return JSONObject.NULL;
            }
            JSONObject o = new JSONObject();
            o.put("x", c.getDouble(0));
            o.put("y", c.getDouble(1));
            o.put("width", ship.optDouble("width", 0));
            o.put("height", ship.optDouble("height", 0));
            o.put("file", path);
            return o;
        } catch (JSONException unreadable) {
            return logMiss ? noCenter(h, unreadable) : JSONObject.NULL;
        } catch (RuntimeException unreadable) {
            return logMiss ? noCenter(h, unreadable) : JSONObject.NULL;
        }
    }

    private static Object noCenter(ShipHullSpecAPI h, Exception why) {
        PilotWatchLog.info("editor: no .ship centre for " + EditorText.sanitize(h.getHullId(), 60)
                + " (" + why.getClass().getSimpleName() + ": " + EditorText.sanitize(why.getMessage(), 160) + ")");
        return JSONObject.NULL;
    }

    // ------------------------------------------------------------------ validate

    /** The validate verdict. The API half runs under {@link #LOCK} (every API call the
     *  drill proved on a responder thread is serialised there); the game-thread wait — up to
     *  {@link EditorGameThread#GRACE_MS} per fresh question — runs OUTSIDE it (held under
     *  the lock it stalled every sprite/stats request behind a pending build). Callers take no lock. */
    static JSONObject validate(ClientVariantDefinition def, EditorTier.Tier tier, boolean allSkills)
            throws JSONException {
        Pending p;
        synchronized (LOCK) {
            p = validateApi(def, tier, allSkills);
        }
        if (p.askGame) {
            gamePass(p);
        }
        return finish(p.out, p.problems);
    }

    /** The API half's carry-over into the lock-free game pass: the verdict so far plus the candidate
     *  built for the game thread ({@code askGame} false = an early refusal, no game question). */
    private static final class Pending {
        final JSONObject out = new JSONObject();
        final JSONArray problems = new JSONArray();
        final ClientVariantDefinition def;
        boolean askGame;
        ShipVariantAPI candidate;
        RuntimeException buildFault;

        Pending(ClientVariantDefinition def) {
            this.def = def;
        }
    }

    private static Pending validateApi(ClientVariantDefinition def, EditorTier.Tier tier, boolean allSkills)
            throws JSONException {
        Pending pend = new Pending(def);
        JSONArray problems = pend.problems;
        JSONObject out = pend.out;
        out.put("tier", tier.name());
        out.put("applicability", "not-asked"); // replaced by the game pass at the end
        SettingsAPI settings = Global.getSettings();
        MutableCharacterStatsAPI person = EditorPersons.personStatsOrNull(allSkills);
        out.put("person", person == null ? "none" : "all-skills");
        // 1 hull resolves
        ShipHullSpecAPI hull = hullOrNull(def.hullId());
        if (hull == null) {
            problem(problems, 1, DoorRejectReasons.CUSTOM_HULL_DENIED, "unknown hull", def.hullId());
            return pend;
        }
        out.put("hull", hull.getHullId());
        // 2 hull policy
        String hullReason = EditorTier.hullRefusal(hull, tier);
        if (hullReason != null) {
            problem(problems, 2, DoorRejectReasons.CUSTOM_HULL_DENIED, hullReason, def.hullId());
        }
        // 12 v1 empty laws
        if (def.goalVariant()) {
            problem(problems, 12, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "goalVariant must be false", null);
        }
        if (!def.suppressedMods().isEmpty()) {
            problem(problems, 12, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "suppressedMods must be empty", null);
        }
        if (!def.tags().isEmpty()) {
            problem(problems, 12, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "tags must be empty", null);
        }
        if (!def.modules().isEmpty()) {
            problem(problems, 12, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "modules must be empty (v1)", null);
        }
        // 4 vents/caps
        int base = baseVentOrCapMax(hull.getHullSize());
        int maxVents = ventOrCapMax(base, person == null ? null : person.getMaxVentsBonus());
        int maxCaps = ventOrCapMax(base, person == null ? null : person.getMaxCapacitorsBonus());
        out.put("vents", pair("n", def.fluxVents(), "max", maxVents));
        out.put("caps", pair("n", def.fluxCapacitors(), "max", maxCaps));
        if (def.fluxVents() > maxVents) {
            problem(problems, 4, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "vents exceed max " + maxVents, null);
        }
        if (def.fluxCapacitors() > maxCaps) {
            problem(problems, 4, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "capacitors exceed max " + maxCaps, null);
        }
        // 5 per (slot, weapon) + 13 group law
        Set<String> fitted = new HashSet<>();
        int groupIndex = 0;
        for (ClientVariantDefinition.WeaponGroup g : def.weaponGroups()) {
            for (ClientVariantDefinition.WeaponMount m : g.weapons()) {
                String slotId = m.slotId();
                if (!fitted.add(slotId)) {
                    problem(problems, 13, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "slot in two groups", slotId);
                    continue;
                }
                WeaponSlotAPI slot = hull.getWeaponSlotAPI(slotId);
                if (slot == null) {
                    problem(problems, 5, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "no such slot on hull", slotId);
                    continue;
                }
                // A built-in slot with the hull's OWN built-in weapon = vanilla's .variant
                // shape (the Groups tab lists built-ins as group-changeable rows) — the server policy's exact-pairing law
                Map<String, String> biw = hull.getBuiltInWeapons();
                String own = biw == null ? null : biw.get(slotId);
                if (slot.isBuiltIn() && own != null) {
                    if (!own.equals(m.weaponId())) {
                        problem(problems, 5, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "built-in slot takes only its own weapon", slotId);
                    }
                    continue;
                }
                if (!EditorTier.isFittableSlot(slot)) {
                    problem(problems, 5, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "slot is not a player weapon slot", slotId);
                    continue;
                }
                WeaponSpecAPI w = weaponOrNull(m.weaponId());
                if (w == null) {
                    problem(problems, 5, DoorRejectReasons.CUSTOM_WEAPON_DENIED, "unknown weapon", m.weaponId());
                    continue;
                }
                String wReason = EditorTier.weaponRefusal(w, tier);
                if (wReason != null) {
                    problem(problems, 5, DoorRejectReasons.CUSTOM_WEAPON_DENIED, wReason, m.weaponId());
                }
                if (!slot.weaponFits(w)) {
                    problem(problems, 5, DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                            "weapon does not fit slot (" + slot.getSlotSize() + " " + slot.getWeaponType() + ")",
                            slotId + "=" + m.weaponId());
                }
            }
            groupIndex++;
        }
        out.put("groups", groupIndex);
        // 7 hullmods
        Set<String> seenMods = new HashSet<>();
        List<String> builtInMods = nzList(hull.getBuiltInMods());
        for (String modId : def.hullMods()) {
            if (!seenMods.add(modId)) {
                problem(problems, 7, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "hullmod listed twice", modId);
                continue;
            }
            HullModSpecAPI m = hullModOrNull(modId);
            if (m == null) {
                problem(problems, 7, DoorRejectReasons.CUSTOM_HULLMOD_DENIED, "unknown hullmod", modId);
                continue;
            }
            if (builtInMods.contains(modId)) {
                problem(problems, 7, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "hullmod is built in on this hull", modId);
            }
            String mReason = EditorTier.hullModRefusal(m, tier);
            if (mReason != null) {
                problem(problems, 7, DoorRejectReasons.CUSTOM_HULLMOD_DENIED, mReason, modId);
            }
        }
        // 11 s-mod set laws
        for (String s : def.sMods()) {
            if (!def.permaMods().contains(s)) {
                problem(problems, 11, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "sMods must be a subset of permaMods", s);
            }
        }
        for (String p : def.permaMods()) {
            if (!def.hullMods().contains(p)) {
                problem(problems, 11, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "permaMods must be a subset of hullMods", p);
            }
            if (!def.sMods().contains(p)) {
                problem(problems, 11, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "permaMods must equal sMods (v1)", p);
            }
            HullModSpecAPI m = hullModOrNull(p);
            if (m != null && m.hasTag("no_build_in")) {
                problem(problems, 11, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "hullmod cannot be built in", p);
            }
        }
        for (String s : def.sModdedBuiltIns()) {
            if (!builtInMods.contains(s)) {
                problem(problems, 11, DoorRejectReasons.CUSTOM_SHAPE_INVALID, "sModdedBuiltIns must be built in on this hull", s);
            }
        }
        // 6 wings (policy here; count vs bays after stats)
        for (String wingId : def.wings()) {
            FighterWingSpecAPI g = wingOrNull(wingId);
            if (g == null) {
                problem(problems, 6, DoorRejectReasons.CUSTOM_WING_DENIED, "unknown wing", wingId);
                continue;
            }
            ShipHullSpecAPI fighterHull = null;
            try {
                ShipVariantAPI fv = g.getVariant();
                fighterHull = fv == null ? null : fv.getHullSpec();
            } catch (RuntimeException unresolved) {
                fighterHull = null;
            }
            String gReason = EditorTier.wingRefusal(g, fighterHull, tier);
            if (gReason != null) {
                problem(problems, 6, DoorRejectReasons.CUSTOM_WING_DENIED, gReason, wingId);
            }
        }
        // 15 wire size (the VSUB body meter)
        int bodyBytes;
        try {
            // The meter runs the real encoder against the boot id table (u16 indices)
            bodyBytes = VariantSubmitWriter.bodyBytes(def, cc.starsector.server.runtime.IdTables.installedOrNull());
        } catch (RuntimeException unencodable) {
            bodyBytes = -1;
            // An id the boot table lacks is ALREADY named by the id rows above
            // (unknown hull / slot / weapon / hullmod / wing — the same engine data mints the table),
            // so the meter's refusal is surfaced only when NOTHING else explains it (the enumeration-
            // parity backstop) or when NO table is installed (the boot mint failed: every submit would
            // refuse, the player must see it). Otherwise it is a duplicate row (the smoke's bad-fit
            // banner law: one row per cause).
            String why = String.valueOf(unencodable.getMessage());
            boolean noTable = why.contains("none is installed");
            if (noTable || problems.length() == 0) {
                int cut = why.indexOf(" (wire format:");
                problem(problems, 15, DoorRejectReasons.CUSTOM_TOO_LARGE, noTable
                        ? "wire id table missing (the boot mint failed - see the log)"
                        : "variant is not wire-encodable: " + (cut > 0 ? why.substring(0, cut) : why), null);
            }
        }
        out.put("bytes", pair("body", bodyBytes, "cap", VariantSubmitWriter.MAX_BODY_BYTES));
        if (bodyBytes > VariantSubmitWriter.MAX_BODY_BYTES) {
            problem(problems, 15, DoorRejectReasons.CUSTOM_TOO_LARGE,
                    "VSUB body " + bodyBytes + " B exceeds " + VariantSubmitWriter.MAX_BODY_BYTES, null);
        }
        // 3 + 8 + 6(count) + 11(cap) + 14: the engine pass on a detached member.
        if (problems.length() > 0 && hullReason != null) {
            return pend;
        }
        try {
            ShipVariantAPI v = build(def, hull);
            FleetMemberAPI member = settings.createFleetMember(FleetMemberType.SHIP, v);
            PersonAPI captain = allSkills ? EditorPersons.unionPerson() : null;
            if (captain != null) {
                member.setCaptain(captain);
            }
            MutableShipStatsAPI st = member.getStats();
            int bays = st.getNumFighterBays().getModifiedInt();
            int builtInWings = nzList(hull.getBuiltInWings()).size();
            out.put("wings", pair("n", def.wings().size(), "bays", Math.max(0, bays - builtInWings)));
            if (def.wings().size() > Math.max(0, bays - builtInWings)) {
                problem(problems, 6, DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                        "wings " + def.wings().size() + " exceed free bays " + Math.max(0, bays - builtInWings), null);
            }
            int sModCap = Misc.getMaxPermanentMods(member, person);
            int sModCount = Misc.getCurrSpecialMods(v);
            out.put("sMods", pair("n", sModCount, "cap", sModCap));
            if (sModCount > sModCap) {
                problem(problems, 11, DoorRejectReasons.CUSTOM_SMOD_CAP, "s-mods " + sModCount + " exceed cap " + sModCap, null);
            }
            int opCost = v.computeOPCost(person);
            int opUnused = v.getUnusedOP(person);
            out.put("opCost", opCost);
            out.put("opUnused", opUnused);
            out.put("opMax", hull.getOrdnancePoints(person));
            if (opUnused < 0) {
                problem(problems, 14, DoorRejectReasons.CUSTOM_OP_EXCEEDED, "OP over budget by " + (-opUnused), null);
            }
        } catch (RuntimeException engine) {
            problem(problems, 8, DoorRejectReasons.CUSTOM_ENGINE_REJECTED,
                    engine.getClass().getSimpleName() + ": " + EditorText.sanitize(engine.getMessage(), 160), null);
        }
        // 9 applicability — decided by the GAME on the game thread: a fresh candidate
        // (the engine tags the drone's variant) through the refit's own gates for EVERY hullmod; the
        // build's own mods that the game refuses are problems with the game's reason; the whole map rides
        // to the SPA so it greys/refuses exactly like the refit. No answer ⇒ said so (server = arbiter).
        // The candidate is built HERE (API, under the lock); the wait for the game is the caller's, lock-free.
        pend.askGame = true;
        try {
            pend.candidate = build(def, hull);
        } catch (RuntimeException fault) {
            pend.buildFault = fault;
        }
        return pend;
    }

    private static void gamePass(Pending p) throws JSONException {
        // Session 4: keyed by (hull, mods, perma-mods, s-mods) — cached answers return at once,
        // a fresh question waits ≤ GRACE_MS then answers `pending` (the SPA re-validates until it lands).
        JSONObject out = p.out;
        JSONArray problems = p.problems;
        ClientVariantDefinition def = p.def;
        EditorGameThread.Answer a;
        String key = EditorGameThread.key(def);
        if (p.buildFault != null) {
            a = EditorGameThread.Answer.unavailable("candidate not buildable: " + p.buildFault.getClass().getSimpleName());
        } else {
            try {
                a = EditorGameThread.applicability(key, p.candidate);
            } catch (RuntimeException fault) {
                a = EditorGameThread.Answer.unavailable("game question failed: " + fault.getClass().getSimpleName());
            }
        }
        out.put("applicabilityKey", key);
        if (!a.available()) {
            out.put("applicability", a.state);
            out.put("applicabilityNote", EditorText.sanitize(a.note, 160));
            return;
        }
        out.put("applicability", "game");
        out.put("applicabilityCached", a.cached);
        out.put("applicabilityMs", a.tookMs);
        out.put("applicabilityNote", (a.cached ? "cached" : "fresh, " + a.tookMs + " ms") + " (frame " + a.frame + " on "
                + a.thread + ")" + (a.errors > 0 ? ", " + a.errors + " mod(s) errored" : ""));
        JSONObject mods = new JSONObject();
        for (Map.Entry<String, EditorGameThread.ModGate> e : a.mods.entrySet()) {
            EditorGameThread.ModGate g = e.getValue();
            if (g.clean()) {
                continue; // absent = the game has no objection (keeps the payload to the exceptions)
            }
            JSONObject o = new JSONObject();
            o.put("ok", g.applicable);
            o.put("reason", EditorText.sanitize(g.reason, 120));
            o.put("show", g.show);
            o.put("now", g.now);
            o.put("nowReason", EditorText.sanitize(g.nowReason, 120));
            mods.put(e.getKey(), o);
        }
        out.put("mods", mods);
        for (String modId : def.hullMods()) {
            EditorGameThread.ModGate g = a.mods.get(modId);
            if (g != null && !g.applicable) {
                problem(problems, 9, DoorRejectReasons.CUSTOM_HULLMOD_INAPPLICABLE,
                        g.reason.isEmpty() ? "the game refuses this hullmod on this ship" : EditorText.sanitize(g.reason, 120), modId);
            }
        }
    }

    // ------------------------------------------------------------------ stats


    // ------------------------------------------------------------------ save

    /** Decision (4): the file stem is OURS ({@link EditorText#saveStem}); the request's id is replaced. */
    static JSONObject save(ClientVariantDefinition def) throws JSONException, IOException {
        return save(def, null);
    }

    /**
     * {@code targetStem} non-null = a stem OUR OWN law minted (the peer pick-save's {@code -2}..{@code -9}
     * suffix, {@link PeerBuilds#saveFromRegistry}); null = the name-derived stem. The fixed bug: the
     * peer save used to report {@code <stem>-2} while this method re-derived the plain stem from the
     * display name and OVERWROTE the first copy — the suffix law never landed a file ({@code deleted=2}).
     */
    static JSONObject save(ClientVariantDefinition def, String targetStem) throws JSONException, IOException {
        return save(def, targetStem, true);
    }

    /**
     * {@link #save(ClientVariantDefinition, String)} with the first-time auto-select switchable off: the host card's
     * Promote saves KEEP-ONLY ({@code POST save?keep=1}) - a Promote never changes what the host flies next, so a host
     * who launched with "(server default)" (an empty {@code current.data}) must not find the promoted build as their
     * next spawn. Every other Save keeps the zero-click first-time flow.
     */
    static JSONObject save(ClientVariantDefinition def, String targetStem, boolean autoSelect)
            throws JSONException, IOException {
        ShipHullSpecAPI hull = hullOrNull(def.hullId());
        if (hull == null) {
            throw new IllegalArgumentException("unknown hull");
        }
        if (targetStem != null && !EditorText.isId(targetStem)) {
            throw new IllegalArgumentException("bad save stem");
        }
        String stem = targetStem != null ? targetStem : EditorText.saveStem(hull.getHullId(), def.displayName());
        ClientVariantDefinition canonical = new ClientVariantDefinition(stem, def.hullId(),
                def.displayName(), false, def.fluxVents(), def.fluxCapacitors(), def.hullMods(),
                def.permaMods(), def.sMods(), def.sModdedBuiltIns(), def.suppressedMods(),
                def.wings(), def.tags(), def.weaponGroups(), def.modules());
        String text = variantDocument(canonical).toString(2);
        // Our own output re-read under the reader's law before it touches disk.
        ClientVariantDefinition reparsed = ClientVariantJson.parse(text);
        if (!stem.equals(reparsed.variantId())) {
            throw new IllegalStateException("save document stem drift");
        }
        SettingsAPI settings = Global.getSettings();
        String name = "cc_variants/" + stem + ".variant";
        boolean existed = settings.fileExistsInCommon(name);
        settings.writeTextFileToCommon(name, text);
        indexUpdate(settings, stem, true);
        // Save auto-selects the CURRENT BUILD only when nothing is current (the first-time flow stays
        // zero-click); otherwise FLY is the explicit choice.
        boolean autoSelected = autoSelect && CcClientModPlugin.currentBuild().isEmpty()
                && CcClientModPlugin.setCurrentBuild(stem);
        JSONObject o = new JSONObject();
        o.put("saved", true);
        o.put("id", stem);
        o.put("current", CcClientModPlugin.currentBuild());
        o.put("autoSelected", autoSelected);
        o.put("next", CcClientModPlugin.nextConnectPick());
        o.put("overwritten", existed);
        o.put("file", "saves/common/" + name + ".data");
        o.put("bytes", text.length());
        PilotWatchLog.info("editor save: id=" + stem + " hull=" + EditorText.sanitize(def.hullId(), 64)
                + " bytes=" + text.length() + (existed ? " (overwrote)" : ""));
        return o;
    }

    // ------------------------------------------------------------------ saved builds
    // SettingsAPI has NO common-file listing and java.io.File is B-denied here, so the
    // editor keeps its own index: cc_variants/index = {"stems":[…]} written by save + delete, pruned against
    // fileExistsInCommon at list time. Every stem passes the id law BEFORE any file access and the path is
    // composed only as "cc_variants/<stem>.variant". Names stay DATA.

    static final String INDEX_NAME = "cc_variants/index";
    static final int INDEX_CAP = 200;

    private static java.util.List<String> indexRead(SettingsAPI settings) {
        java.util.List<String> stems = new java.util.ArrayList<>();
        if (!settings.fileExistsInCommon(INDEX_NAME)) {
            return stems;
        }
        try {
            String text = settings.readTextFileFromCommon(INDEX_NAME);
            if (text == null || text.length() > 64 * 1024) {
                return stems;
            }
            JSONArray arr = new JSONObject(text).optJSONArray("stems");
            for (int i = 0; arr != null && i < arr.length() && stems.size() < INDEX_CAP; i++) {
                String s = arr.optString(i, "");
                if (EditorText.isId(s) && !stems.contains(s)) {
                    stems.add(s);
                }
            }
        } catch (IOException | JSONException | RuntimeException unreadable) {
            PilotWatchLog.info("editor saved index unreadable (" + unreadable.getClass().getSimpleName() + "): rebuilt empty");
        }
        return stems;
    }

    private static void indexWrite(SettingsAPI settings, java.util.List<String> stems) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("stems", new JSONArray(stems));
            settings.writeTextFileToCommon(INDEX_NAME, o.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("index json", impossible);
        }
    }

    private static void indexUpdate(SettingsAPI settings, String stem, boolean present) throws IOException {
        java.util.List<String> stems = indexRead(settings);
        boolean had = stems.remove(stem);
        if (present) {
            stems.add(0, stem);
            while (stems.size() > INDEX_CAP) {
                stems.remove(stems.size() - 1);
            }
        }
        if (present || had) {
            indexWrite(settings, stems);
        }
    }

    /** {@code GET saved}: the index ∩ the files that exist, each summarized from its own text (a malformed
     *  file is listed with {@code error} so Delete can still reach it); the index is pruned as a side effect. */
    static JSONObject savedList() throws JSONException, IOException {
        SettingsAPI settings = Global.getSettings();
        java.util.List<String> stems = indexRead(settings);
        java.util.List<String> kept = new java.util.ArrayList<>();
        JSONArray rows = new JSONArray();
        for (String stem : stems) {
            String name = "cc_variants/" + stem + ".variant";
            if (!settings.fileExistsInCommon(name)) {
                continue;
            }
            kept.add(stem);
            JSONObject row = new JSONObject();
            row.put("id", stem);
            try {
                String text = settings.readTextFileFromCommon(name);
                ClientVariantDefinition d = ClientVariantJson.parse(text);
                row.put("hullId", d.hullId());
                row.put("displayName", d.displayName());
                row.put("bytes", text.length());
            } catch (IOException | RuntimeException bad) {
                row.put("error", "unreadable");
            }
            rows.put(row);
        }
        if (kept.size() != stems.size()) {
            indexWrite(settings, kept);
        }
        JSONObject o = new JSONObject();
        o.put("saved", rows);
        o.put("n", rows.length());
        o.put("current", CcClientModPlugin.currentBuild()); // the SPA badges this row NEXT SPAWN
        return o;
    }

    /** {@code POST saved/<stem>/fly} (the FLY button): the saved build becomes the CURRENT BUILD;
     *  null when no such file (404 — a deleted or never-saved stem is never made current). The caller has
     *  id-law-gated the stem; the plugin re-gates it before the one SettingsAPI write. */
    static JSONObject savedFly(String stem) throws JSONException {
        return savedFly(stem, false);
    }

    /**
     * The keep-only Fly: a Promote is never the player's next respawn.
     * The host card's Promote posts {@code keepOnly:true}: the build is still SUBMITTED (FlyLive reads
     * {@code flown} and sends the VSUB with the keep-only bit, so the server mints the ship) but {@code current.data}
     * is NOT written - the next CONNECT's pick and the SPA's NEXT SPAWN badge stay what they were. Skipping the write
     * while answering {@code flown=false} would make FlyLive reply not-flown and nothing would reach the server, so
     * {@code flown} is true by construction here (the stem passed the id law in the route; the only refusal the
     * write could add is the plugin's re-gate of that same law).
     */
    static JSONObject savedFly(String stem, boolean keepOnly) throws JSONException {
        SettingsAPI settings = Global.getSettings();
        if (!settings.fileExistsInCommon("cc_variants/" + stem + ".variant")) {
            return null;
        }
        boolean set = keepOnly || CcClientModPlugin.setCurrentBuild(stem);
        JSONObject o = new JSONObject();
        o.put("flown", set);
        o.put("id", stem);
        o.put("current", CcClientModPlugin.currentBuild());
        o.put("next", CcClientModPlugin.nextConnectPick());
        PilotWatchLog.info("editor fly: id=" + stem + (keepOnly ? " keep_only=1" : set ? "" : " (refused)"));
        return o;
    }

    /** {@code GET saved/<stem>}: the stored document, re-read under the reader's law and re-emitted in the
     *  canonical shape (never the raw text); null when no such file. The caller has id-law-gated the stem. */
    static JSONObject savedLoad(String stem) throws JSONException, IOException {
        SettingsAPI settings = Global.getSettings();
        String name = "cc_variants/" + stem + ".variant";
        if (!settings.fileExistsInCommon(name)) {
            return null;
        }
        ClientVariantDefinition d = ClientVariantJson.parse(settings.readTextFileFromCommon(name));
        JSONObject o = variantDocument(d);
        o.put("id", stem);
        return o;
    }

    // ------------------------------------------------------------------ server builds (iteration 9, owner #4)
    // The SERVER's offerable variant ids reach the editor JVM through ONE file the join window publishes after a served
    // probe (JoinCore.publishServerBuilds → saves/common/cc_variants/server-builds.data = {"ids":[…],"defaultId":"…",
    // "stamp":ms}); reading this list needs no credential and no dial. The editor as a whole does hold one secret and does
    // send: the host card's sign-in keeps the typed admin secret in JVM memory for one queued send (never on disk, never
    // logged), and its ship verbs, like Fly's /ship, ride the live session's chat (HostShips, FlyLive). The file is wire-DERIVED data:
    // 64 KB cap, lenient parse, every id re-gated by the id law BEFORE it names anything, resolved only
    // through SettingsAPI.getVariant (the game's own registry — no file, no path), unknown ⇒ a greyed row, cap 200.

    /** SettingsAPI name of the published file (the game appends {@code .data}). */
    static final String SERVER_BUILDS_NAME = "cc_variants/server-builds";
    static final int SERVER_BUILDS_MAX_TEXT = 64 * 1024;
    static final int SERVER_BUILDS_CAP = 200;

    /** {@code GET server}: the published ids, each resolved against the game's variant registry. */
    static JSONObject serverBuildsList() throws JSONException {
        SettingsAPI settings = Global.getSettings();
        JSONObject o = new JSONObject();
        JSONArray rows = new JSONArray();
        o.put("server", rows);
        o.put("connected", false);
        // wq P24: the peers group — the customs other players got accepted THIS session, already
        // folded into the engine's registry (VDEF fan-out; evicted at teardown); listed before the roster file so a
        // missing / stale file never hides them. Same row shape; Load rides GET server/<id> like a roster row.
        JSONArray peers = PeerBuilds.peerRows(SERVER_BUILDS_CAP);
        o.put("peers", peers);
        String text = null;
        try {
            if (settings.fileExistsInCommon(SERVER_BUILDS_NAME)) {
                text = settings.readTextFileFromCommon(SERVER_BUILDS_NAME);
            }
        } catch (IOException | RuntimeException unreadable) {
            text = null;
        }
        if (text == null || text.isEmpty() || text.length() > SERVER_BUILDS_MAX_TEXT) {
            return o;
        }
        JSONObject in;
        try {
            in = new JSONObject(text);
        } catch (JSONException malformed) {
            return o;
        }
        JSONArray ids = in.optJSONArray("ids");
        String def = in.optString("defaultId", "");
        o.put("connected", true);
        o.put("defaultId", EditorText.isId(def) ? def : "");
        o.put("stamp", in.optLong("stamp", 0L));
        int n = ids == null ? 0 : Math.min(ids.length(), SERVER_BUILDS_CAP);
        for (int i = 0; i < n; i++) {
            String id = ids.optString(i, "");
            if (!EditorText.isId(id)) {
                continue;   // a lawless id never names anything (a tampered file cannot traverse)
            }
            JSONObject row = new JSONObject();
            row.put("id", id);
            ShipVariantAPI v = registeredVariant(id);
            if (v != null) {
                row.put("loadable", true);
                row.put("hullId", v.getHullSpec().getHullId());
                row.put("hullName", String.valueOf(v.getHullSpec().getHullName()));
                row.put("displayName", EditorText.sanitize(String.valueOf(v.getDisplayName()), 48));
                row.put("op", v.getHullSpec().getOrdnancePoints(null));
            } else {
                // The registry forgets a wire-delivered custom at combat teardown (Law B) and the
                // kit ships no file for it — the fold's disk cache answers instead (same row shape, cached:true).
                ClientVariantDefinition d = ServerBuildCache.cached(id);
                ShipHullSpecAPI hull = d == null ? null : hullOrNull(d.hullId());
                if (hull == null) {
                    row.put("loadable", false);
                    row.put("why", d == null
                            ? "delivered by the server at match start — not cached from a connection yet (Fly still works)"
                            : "cached from the server, but its hull is not in this game");
                } else {
                    row.put("loadable", true);
                    row.put("cached", true);
                    row.put("hullId", hull.getHullId());
                    row.put("hullName", String.valueOf(hull.getHullName()));
                    row.put("displayName", EditorText.sanitize(String.valueOf(d.displayName()), 48));
                    row.put("op", hull.getOrdnancePoints(null));
                }
            }
            rows.put(row);
        }
        // An id the roster ALSO carries (a peer's custom the server published) is a
        // roster row already — drop it from the peers group so it renders and counts once.
        java.util.Set<String> rosterIds = new java.util.HashSet<String>();
        for (int i = 0; i < rows.length(); i++) {
            rosterIds.add(rows.getJSONObject(i).getString("id"));
        }
        JSONArray peersOnly = new JSONArray();
        for (int i = 0; i < peers.length(); i++) {
            JSONObject row = peers.getJSONObject(i);
            if (!rosterIds.contains(row.getString("id"))) {
                peersOnly.put(row);
            }
        }
        o.put("peers", peersOnly);
        o.put("n", rows.length());
        return o;
    }

    /** {@code GET server/<id>}: the registered variant as a DRAFT document (the saved-build shape, empty
     *  {@code variantId}, {@code draft:true}); an id the registry no longer holds (evicted at
     *  teardown, not shipped by the kit) loads from the fold's disk cache when its hull is known here; null when
     *  neither knows the id. The caller id-law-gated it. */
    static JSONObject serverBuildLoad(String id) throws JSONException {
        ShipVariantAPI v = registeredVariant(id);
        JSONObject o;
        if (v != null) {
            o = variantDocumentFromApi(v);
        } else {
            ClientVariantDefinition d = ServerBuildCache.cached(id);
            if (d == null || hullOrNull(d.hullId()) == null) {
                return null;
            }
            o = variantDocument(d);
            o.put("variantId", "");
            o.put("cached", true);
        }
        o.put("id", id);
        o.put("draft", true);
        return o;
    }

    /** {@code SettingsAPI.getVariant} without its throw: null for an unknown id or a hull-less spec. */
    static ShipVariantAPI registeredVariant(String id) {
        try {
            ShipVariantAPI v = Global.getSettings().getVariant(id);
            return v == null || v.getHullSpec() == null ? null : v;
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    /** The saved-build document shape composed from a live {@link ShipVariantAPI} (the readers BootVariantRegistry
     *  already proves): groups in order with their slot→weapon map, mods / perma / S-mods / S-modded built-ins, wings. */
    static JSONObject variantDocumentFromApi(ShipVariantAPI v) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("variantId", "");
        o.put("hullId", v.getHullSpec().getHullId());
        o.put("displayName", EditorText.sanitize(String.valueOf(v.getDisplayName()), 48));
        o.put("goalVariant", false);
        o.put("quality", 1);
        o.put("fluxVents", v.getNumFluxVents());
        o.put("fluxCapacitors", v.getNumFluxCapacitors());
        // Loading afflictor_Antimatter raised "phasefield: hullmod is built in on this hull / hidden" x2:
        // the engine's variant set carries the HULL's built-ins; the editor models those on the hull (greyed, not
        // removable), so a draft lists only the variant's OWN mods — else the checker flags every built-in twice.
        java.util.List<String> ownMods = new java.util.ArrayList<String>(v.getHullMods());
        ownMods.removeAll(nzList(v.getHullSpec().getBuiltInMods()));
        o.put("hullMods", new JSONArray(ownMods));
        o.put("permaMods", new JSONArray(new java.util.ArrayList<String>(v.getPermaMods())));
        o.put("sMods", new JSONArray(new java.util.ArrayList<String>(v.getSMods())));
        o.put("sModdedBuiltIns", new JSONArray(new java.util.ArrayList<String>(v.getSModdedBuiltIns())));
        o.put("wings", new JSONArray(new java.util.ArrayList<String>(v.getNonBuiltInWings())));   // getWings() leads with the hull's built-in wings; the document lists the free bays'
        JSONArray groups = new JSONArray();
        for (com.fs.starfarer.api.loading.WeaponGroupSpec g : v.getWeaponGroups()) {
            JSONObject go = new JSONObject();
            go.put("mode", g.getType() == com.fs.starfarer.api.loading.WeaponGroupType.ALTERNATING ? "ALTERNATING" : "LINKED");
            go.put("autofire", g.isAutofireOnByDefault());
            JSONObject weapons = new JSONObject();
            for (String slot : g.getSlots()) {
                String wid = v.getWeaponId(slot);
                if (slot != null && wid != null) {
                    weapons.put(slot, wid);
                }
            }
            go.put("weapons", weapons);
            groups.put(go);
        }
        o.put("weaponGroups", groups);
        return o;
    }

    /** {@code POST saved/<stem>/delete}: removes the file (a no-op when absent) and its index row. */
    static JSONObject savedDelete(String stem) throws JSONException, IOException {
        SettingsAPI settings = Global.getSettings();
        String name = "cc_variants/" + stem + ".variant";
        boolean existed = settings.fileExistsInCommon(name);
        if (existed) {
            settings.deleteTextFileFromCommon(name);
        }
        indexUpdate(settings, stem, false);
        CcClientModPlugin.clearCurrentBuildIf(stem); // deleting the current build clears current.data
        JSONObject o = new JSONObject();
        o.put("deleted", existed);
        o.put("id", stem);
        o.put("current", CcClientModPlugin.currentBuild());
        o.put("next", CcClientModPlugin.nextConnectPick());
        PilotWatchLog.info("editor delete: id=" + stem + (existed ? "" : " (no such file)"));
        return o;
    }

    /** How long {@code /submit} waits for the server's VACK before answering "sent" (the receipt then
     *  lands in the game's chat through the combat lane's drain). */
    static final long SUBMIT_WAIT_MS = 3000L;

    /**
     * {@code /submit} (in-match only): hand the variant to the LIVE predict
     * session over the SAME {@code VSUB} lane the join path uses ({@link PredictSession#sendVariantSubmit}
     * — the reliable CTRL frame, seq'd by the session, server dup-keyed by body CRC) and wait up to
     * {@link #SUBMIT_WAIT_MS} for the {@code VACK}. The verdict words are the server's own: accepted +
     * the canonical id the seat may fly, or the reject token + the {@link VariantRejectHints} fix hint.
     * NEVER under {@link #LOCK} (a 3 s wait there would stall every sprite/stats request). Fail-soft like
     * {@code submitLocalCustomVariant}: no session, a defunct one, a dead lane or an over-budget body
     * answer {@code submitted=false} with a plain reason and one sidelog row. The local id is the save
     * stem, so a Save under this name is the same variant the server answered (a re-HELLO with that pick
     * dup-accepts it). An ACCEPT is the seat's next-respawn pick server-side (the door seeds
     * the submitter's seat and says "next respawn: <id>" in the chat — the build's own name, never the
     * word "custom").
     * Player-authored text never reaches the log unsanitized (the stem is charset-bounded).
     */
    static JSONObject submit(ClientVariantDefinition def) throws JSONException {
        return submit(def, false);
    }

    /**
     * {@link #submit(ClientVariantDefinition)} with the
     * keep-only ask: when {@code keepOnly} the VSUB body carries the keep-only bit, so the server's accept
     * mints the id (the reply's {@code id}, unchanged) WITHOUT seeding the seat's next-respawn pick — the
     * host's Promote adds a ship and never changes what the host flies next. A transport that cannot set
     * the bit throws and the answer is {@code not-sent} (never a silent plain submission).
     */
    static JSONObject submit(ClientVariantDefinition def, boolean keepOnly) throws JSONException {
        JSONObject o = new JSONObject();
        ShipHullSpecAPI hull;
        synchronized (LOCK) {
            hull = hullOrNull(def.hullId());
        }
        if (hull == null) {
            return refused(o, "no-hull", "unknown hull");
        }
        PredictSession session = CcClientModPlugin.predictSession();
        if (session == null || session.isDefunct()) {
            return refused(o, "no-session", session == null
                    ? "not connected to a server — join a match first"
                    : "the server connection is gone — re-select the mission to reconnect");
        }
        String stem = EditorText.saveStem(hull.getHullId(), def.displayName());
        ClientVariantDefinition canonical = new ClientVariantDefinition(stem, def.hullId(),
                def.displayName(), false, def.fluxVents(), def.fluxCapacitors(), def.hullMods(),
                def.permaMods(), def.sMods(), def.sModdedBuiltIns(), def.suppressedMods(),
                def.wings(), def.tags(), def.weaponGroups(), def.modules());
        Set<Integer> seen = new HashSet<Integer>();
        for (ClientVariantReceipt r : receiptsOrEmpty(session)) {
            seen.add(r.submitSeq());
        }
        int bodyBytes;
        try {
            bodyBytes = VariantSubmitWriter.bodyBytes(canonical, cc.starsector.server.runtime.IdTables.installedOrNull());
            session.sendVariantSubmit(canonical, keepOnly);
        } catch (IOException | RuntimeException notSent) {
            PilotWatchLog.error("editor submit: id=" + stem + " NOT sent", notSent);
            return refused(o, "not-sent", "not sent: " + EditorText.sanitize(nz(notSent.getMessage()), 120));
        }
        PilotWatchLog.info("editor submit: vsub_sent local_id=" + stem + " hull=" + EditorText.sanitize(def.hullId(), 64)
                + " body_bytes=" + bodyBytes + "/" + VariantSubmitWriter.MAX_BODY_BYTES + " keep_only=" + keepOnly);
        o.put("submitted", true);
        o.put("localId", stem);
        o.put("bodyBytes", bodyBytes);
        long started = System.currentTimeMillis();
        ClientVariantReceipt receipt = null;
        while (receipt == null && System.currentTimeMillis() - started < SUBMIT_WAIT_MS) {
            for (ClientVariantReceipt r : receiptsOrEmpty(session)) {
                if (!seen.contains(r.submitSeq())) {
                    receipt = r;
                }
            }
            if (receipt == null) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        o.put("waitedMs", System.currentTimeMillis() - started);
        o.put("answered", receipt != null);
        if (receipt == null) {
            PilotWatchLog.info("editor submit: id=" + stem + " no VACK within " + SUBMIT_WAIT_MS + " ms (the chat toasts it when it lands)");
            return o;
        }
        o.put("accepted", receipt.accepted());
        o.put("id", receipt.variantId());
        o.put("seq", receipt.submitSeq());
        if (!receipt.accepted()) {
            o.put("reasonToken", receipt.reasonToken());
            String hint = VariantRejectHints.hintOrNull(receipt.reasonToken());
            o.put("hint", hint == null ? JSONObject.NULL : hint);
        }
        PilotWatchLog.info("editor submit: vack seq=" + receipt.submitSeq() + (receipt.accepted() ? " accepted id=" : " rejected:" + receipt.reasonToken() + " id=")
                + EditorText.sanitize(receipt.variantId(), 64) + " waited_ms=" + (System.currentTimeMillis() - started));
        return o;
    }

    private static JSONObject refused(JSONObject o, String state, String message) throws JSONException {
        o.put("submitted", false);
        o.put("state", state);
        o.put("message", message);
        return o;
    }

    private static List<ClientVariantReceipt> receiptsOrEmpty(PredictSession session) {
        try {
            return session.peekVariantReceipts();
        } catch (RuntimeException peekFailed) {
            return java.util.Collections.emptyList();
        }
    }

    /** The {@code .variant} document in the vanilla vocabulary the reader ({@link ClientVariantJson}) parses. */
    static JSONObject variantDocument(ClientVariantDefinition d) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("variantId", d.variantId());
        o.put("hullId", d.hullId());
        o.put("displayName", d.displayName());
        o.put("goalVariant", false);
        o.put("quality", 1);
        o.put("fluxVents", d.fluxVents());
        o.put("fluxCapacitors", d.fluxCapacitors());
        o.put("hullMods", new JSONArray(d.hullMods()));
        o.put("permaMods", new JSONArray(d.permaMods()));
        o.put("sMods", new JSONArray(d.sMods()));
        o.put("sModdedBuiltIns", new JSONArray(d.sModdedBuiltIns()));
        o.put("wings", new JSONArray(d.wings()));
        JSONArray groups = new JSONArray();
        for (ClientVariantDefinition.WeaponGroup g : d.weaponGroups()) {
            JSONObject go = new JSONObject();
            go.put("mode", g.alternating() ? "ALTERNATING" : "LINKED");
            go.put("autofire", g.autofire());
            JSONObject weapons = new JSONObject();
            for (ClientVariantDefinition.WeaponMount m : g.weapons()) {
                weapons.put(m.slotId(), m.weaponId());
            }
            go.put("weapons", weapons);
            groups.put(go);
        }
        o.put("weaponGroups", groups);
        return o;
    }

    // ------------------------------------------------------------------ auto-assign weapon groups (iteration 6 row (d))

    /** {@code POST autogroups}: the game's OWN weapon-group auto-assign on a detached variant built from the
     *  document — {@code ShipVariantAPI.autoGenerateWeaponGroups()} = {@code HullVariantSpec.autoGenerateWeaponGroupsV2}
     *  (pure spec clustering: no engine, no sector, no game thread), the same call the refit's
     *  "Auto-assign" button makes. Answer = the generated groups in order:
     *  {@code {groups:[{mode, autofire, slots:[…]}]}}; the SPA maps every fitted slot it knows, the rest (a hull's
     *  built-in weapons) it leaves to the game. Caller holds {@link #LOCK}. */
    static JSONObject autoGroups(ClientVariantDefinition def) throws JSONException {
        ShipHullSpecAPI hull = hullOrNull(def.hullId());
        if (hull == null) {
            throw new IllegalArgumentException("unknown hull");
        }
        ShipVariantAPI v = build(def, hull);
        v.autoGenerateWeaponGroups();
        JSONArray groups = new JSONArray();
        for (WeaponGroupSpec g : v.getWeaponGroups()) {
            JSONObject go = new JSONObject();
            go.put("mode", g.getType() == WeaponGroupType.ALTERNATING ? "ALTERNATING" : "LINKED");
            go.put("autofire", g.isAutofireOnByDefault());
            go.put("slots", new JSONArray(g.getSlots()));
            groups.put(go);
        }
        JSONObject o = new JSONObject();
        o.put("groups", groups);
        o.put("n", groups.length());
        return o;
    }

    // ------------------------------------------------------------------ helpers

    static ShipVariantAPI build(ClientVariantDefinition def, ShipHullSpecAPI hull) {
        SettingsAPI settings = Global.getSettings();
        ShipVariantAPI v = settings.createEmptyVariant(def.variantId(), hull);
        v.setSource(VariantSource.REFIT);
        v.setVariantDisplayName(def.displayName());
        v.setNumFluxVents(def.fluxVents());
        v.setNumFluxCapacitors(def.fluxCapacitors());
        for (String m : def.hullMods()) {
            v.addMod(m);
        }
        for (String m : def.permaMods()) {
            v.addPermaMod(m, def.sMods().contains(m));
        }
        for (ClientVariantDefinition.WeaponGroup g : def.weaponGroups()) {
            WeaponGroupSpec spec = new WeaponGroupSpec(g.alternating() ? WeaponGroupType.ALTERNATING
                    : WeaponGroupType.LINKED);
            spec.setAutofireOnByDefault(g.autofire());
            for (ClientVariantDefinition.WeaponMount m : g.weapons()) {
                v.addWeapon(m.slotId(), m.weaponId());
                spec.addSlot(m.slotId());
            }
            v.addWeaponGroup(spec);
        }
        int builtInWings = nzList(hull.getBuiltInWings()).size();
        for (int i = 0; i < def.wings().size(); i++) {
            v.setWingId(builtInWings + i, def.wings().get(i));
        }
        return v;
    }

    static ShipHullSpecAPI hullOrNull(String id) {
        if (!EditorText.isId(id)) {
            return null;
        }
        try {
            return Global.getSettings().getHullSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static WeaponSpecAPI weaponOrNull(String id) {
        if (!EditorText.isId(id)) {
            return null;
        }
        try {
            return Global.getSettings().getWeaponSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static HullModSpecAPI hullModOrNull(String id) {
        if (!EditorText.isId(id)) {
            return null;
        }
        try {
            return Global.getSettings().getHullModSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static FighterWingSpecAPI wingOrNull(String id) {
        if (!EditorText.isId(id)) {
            return null;
        }
        try {
            return Global.getSettings().getFighterWingSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static int ventOrCapMax(int base, StatBonus bonusOrNull) {
        return bonusOrNull == null ? base : (int) bonusOrNull.computeEffective(base);
    }

    private static void problem(JSONArray problems, int step, String token, String detail, String subject)
            throws JSONException {
        JSONObject p = new JSONObject();
        p.put("step", step);
        p.put("token", token);
        p.put("detail", detail);
        p.put("subject", subject == null ? JSONObject.NULL : subject);
        problems.put(p);
    }

    private static JSONObject finish(JSONObject out, JSONArray problems) throws JSONException {
        out.put("ok", problems.length() == 0);
        out.put("problems", problems);
        return out;
    }

    private static JSONObject pair(String k1, int v1, String k2, int v2) throws JSONException {
        JSONObject o = new JSONObject();
        o.put(k1, v1);
        o.put(k2, v2);
        return o;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    private static List<String> nzList(java.util.Collection<String> c) {
        return c == null ? List.of() : List.copyOf(c);
    }

    /** JSON-safe number: non-finite ⇒ 0, rounded to 2 decimals (the old org.json rejects NaN). */
    static double num(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            return 0;
        }
        return Math.round(f * 100.0) / 100.0;
    }
}
