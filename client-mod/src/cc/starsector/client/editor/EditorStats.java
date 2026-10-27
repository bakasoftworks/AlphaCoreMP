package cc.starsector.client.editor;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.combat.CombatReadinessPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat.StatMod;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShieldSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.combat.CRPluginImpl;
import com.fs.starfarer.api.impl.hullmods.ConvertedHangar;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;

import cc.starsector.client.FleetContextMint;
import cc.starsector.client.net.ClientVariantDefinition;

/**
 * The editor's STAT family, carved off {@link EditorOracle} (the deferred-refactors EditorOracle row): the
 * {@code /stats} answer (a fleet member built from the variant, the CR preview, the shield / cloak / weapon rows) plus its
 * two private helpers. Helpers that the other routes share ({@code build}, {@code hullOrNull}, {@code num}, {@code nz},
 * {@code settingFloat}, {@code statFactor}, the union / captain persons) stay package-private on the oracle and are
 * called back. Same LOCK discipline as before: the route holds {@code EditorOracle.LOCK} around {@link #stats}.
 */
final class EditorStats {

    private EditorStats() {
    }

    /** The refit's stat readouts at a PREVIEW combat readiness ({@code crPct} 0..100, the SPA's bar; 100 = the design
     *  default) — vanilla's own refit path:
     *  crew the member, {@code RepairTracker.setCR}, {@code updateStats}, read. A bare {@code createFleetMember} has an
     *  EMPTY crew, and {@code RepairTracker.getCR()} is {@code cr × crewFraction}, so the earlier answers were computed at
     *  CR 0 (−10 % speed, +10 % damage taken, the malfunction chances). */
    static JSONObject stats(ClientVariantDefinition def, boolean allSkills, boolean skills, int crPct) throws JSONException {
        ShipHullSpecAPI hull = EditorOracle.hullOrNull(def.hullId());
        if (hull == null) {
            throw new IllegalArgumentException("unknown hull");
        }
        MutableCharacterStatsAPI person = EditorPersons.personStatsOrNull(allSkills);
        ShipVariantAPI v = EditorOracle.build(def, hull);
        FleetMemberAPI member = Global.getSettings().createFleetMember(FleetMemberType.SHIP, v);
        // Skills: a bare member + setCaptain never applied
        // a skill (FleetMember.updateStats wants a fleet). skills=1 = the bastion seat shape, typed end (OfficerLoadout
        // .attach): a campaign-free one-member FleetData fab (FleetContextMint) with the gate-2 token on a union-sheet
        // commander (EditorPersons.commanderPerson — a second person, so /validate's unionPerson never carries a token),
        // setFleetCommanderForStats(commander, fab) for the fleet-wide set, setCaptain(the ship set) for the personal
        // set — both from AllSkillsEnumerator (accessible skills only, never the deprecated ones). A throw leaves the
        // member bare and the answer says so (skillsError) — never a 500.
        String skillsError = null;
        boolean skillsApplied = false;
        if (skills) {
            try {
                PersonAPI commander = EditorPersons.commanderPerson();
                FleetDataAPI fab = FleetContextMint.fabForMember(member, commander);
                member.setFleetCommanderForStats(commander, fab);
                member.setCaptain(EditorPersons.captainPerson());
                skillsApplied = true;
            } catch (RuntimeException unavailable) {
                skillsError = String.valueOf(unavailable);
            }
        }
        // The preview CR (all public API: CrewCompositionAPI.setCrew / RepairTrackerAPI.setCR /
        // FleetMemberAPI.updateStats). Clamped here again (the route clamps too); a throwing setter leaves the
        // member as it was and the answer says so (crPreview.applied=false) — never a 500.
        float cr = Math.max(0, Math.min(100, crPct)) / 100f;
        String crApplyError = null;
        try {
            member.getCrewComposition().setCrew(member.getMaxCrew());
            member.getRepairTracker().setCR(cr);
            member.updateStats();
        } catch (RuntimeException unavailable) {
            crApplyError = String.valueOf(unavailable);
        }
        // ONE read: getStats() re-runs updateStats on demand, so every number below comes off this reference
        MutableShipStatsAPI st = member.getStats();
        JSONObject o = new JSONObject();
        o.put("hull", hull.getHullId());
        o.put("person", person == null ? "none" : "all-skills");
        o.put("skills", skillsApplied ? "applied" : "off");
        if (skillsError != null) {
            o.put("skillsError", skillsError);
        }
        o.put("hp", EditorOracle.num(st.getHullBonus().computeEffective(hull.getHitpoints())));
        o.put("armor", EditorOracle.num(st.getArmorBonus().computeEffective(hull.getArmorRating())));
        o.put("fluxCap", EditorOracle.num(st.getFluxCapacity().getModifiedValue()));
        o.put("fluxDiss", EditorOracle.num(st.getFluxDissipation().getModifiedValue()));
        // The active-venting rate = the engine's law: dissipation × 2 × ventRateMult —
        // Resistant Flux Conduits +25 % (+35 % built in), Safety Overrides modifyMult(0) → 0, which the SPA reads as "cannot vent"
        o.put("ventRate", EditorOracle.num(st.getFluxDissipation().getModifiedValue() * 2f * st.getVentRateMult().getModifiedValue()));
        // The effective per-capacitor / per-vent slopes (settings.json per-unit × the stat's percent+mult factor)
        // — the SPA's optimistic paint on a step; this answer reconciles it
        o.put("capSlope", EditorOracle.num(EditorOracle.settingFloat("fluxPerCapacitor", 200f) * EditorOracle.statFactor(st.getFluxCapacity())));
        o.put("ventSlope", EditorOracle.num(EditorOracle.settingFloat("dissipationPerVent", 10f) * EditorOracle.statFactor(st.getFluxDissipation())));
        o.put("maxSpeed", EditorOracle.num(st.getMaxSpeed().getModifiedValue()));
        o.put("acceleration", EditorOracle.num(st.getAcceleration().getModifiedValue()));
        o.put("turnRate", EditorOracle.num(st.getMaxTurnRate().getModifiedValue()));
        o.put("bays", st.getNumFighterBays().getModifiedInt());
        // Where each bay comes from (a bare "WS 027" / "bay 2" badge on the Fighters head means nothing) — the
        // hull's own bays are the stat's BASE; every flat source above it is named in application order (a Converted Hangar's 1 + the
        // Invictus's Vast Hangar bonus ride the ONE converted_hangar source — VastHangar.java feeds converted_hangar_mod, never this stat)
        JSONArray baySources = new JSONArray();
        try {
            for (StatMod sm : st.getNumFighterBays().getFlatMods().values()) {
                if (sm.value > 0f) {
                    JSONObject b = new JSONObject();
                    b.put("id", EditorOracle.nz(sm.source));
                    b.put("name", sourceName(sm.source));
                    b.put("n", Math.round(sm.value));
                    baySources.put(b);
                }
            }
        } catch (RuntimeException unavailable) {   // One bad source never 500s /stats
            o.put("baySourcesError", String.valueOf(unavailable));
        }
        o.put("baySources", baySources);
        // Vast Hangar buffs Converted Hangar on the Invictus — is it faithful? The numbers
        // already are — this member's stats run the game's own hullmod chain (VastHangar feeds converted_hangar_mod + the four negations,
        // ConvertedHangar reads them: bays, DP, crew). What was NOT faithful: the Converted Hangar HOVER served the generic csv/script text
        // (ScriptDescribed, no ship), so on the Invictus it still listed penalties Vast Hangar negates. The refit's own tooltip appends a
        // per-SHIP section naming the deployment-cost increase when it applies
        // and the penalties this hull waives — hullModNotes carries the same two facts in this editor's own words (never the game's
        // sentence), keyed by hullmod id, for the SPA's modTip to append.
        JSONObject hullModNotes = new JSONObject();
        try {
            if (v.hasHullMod("converted_hangar")) {
                JSONArray notes = new JSONArray();
                boolean crewIncrease = st.getDynamic().getMod("converted_hangar_no_crew_increase").computeEffective(0f) <= 0f;
                boolean rearmIncrease = st.getDynamic().getMod("converted_hangar_no_rearm_increase").computeEffective(0f) <= 0f;
                boolean dpIncrease = st.getDynamic().getMod("converted_hangar_no_dp_increase").computeEffective(0f) <= 0f;
                boolean refitPenalty = st.getDynamic().getMod("converted_hangar_no_refit_penalty").computeEffective(0f) <= 0f;
                int dpMod = ConvertedHangar.computeDPModifier(ConvertedHangar.getFighterOPCost(st));
                if (dpMod > 0 && dpIncrease) {
                    notes.put("Costs " + dpMod + " more to deploy with the hangar fitted.");
                }
                List<String> waived = new java.util.ArrayList<>();
                if (!refitPenalty) { waived.add("the slower refit and rate recovery"); }
                if (!rearmIncrease) { waived.add("the longer relaunch delay"); }
                if (!crewIncrease) { waived.add("the extra crew"); }
                if (!dpIncrease) { waived.add("the deployment-cost increase"); }
                if (!waived.isEmpty()) {
                    StringBuilder list = new StringBuilder();
                    for (int i = 0; i < waived.size(); i++) {
                        if (i > 0) {
                            list.append(i == waived.size() - 1 ? " and " : ", ");
                        }
                        list.append(waived.get(i));
                    }
                    notes.put("This hull waives " + list + " of the hangar.");
                }
                hullModNotes.put("converted_hangar", notes);
            }
        } catch (RuntimeException unavailable) {   // A throwing hullmod read leaves the notes empty
            o.put("hullModNotesError", String.valueOf(unavailable));
        }
        o.put("hullModNotes", hullModNotes);
        // The refit's CR readouts off the same member — max CR
        // (fraction), peak-performance seconds, deployment cost; guarded so one bad accessor never 500s the route
        try {
            o.put("cr", EditorOracle.num(st.getMaxCombatReadiness().getModifiedValue()));
            // A hull with NO peak limit (blank "peak CR sec" -> the loader's
            // 9999999 s default, CR loss/sec 0 — shuttlepod, onslaught_mk1) carries NO peakTime; the SPA then hides the row
            if (hull.getCRLossPerSecond() > 0f) {
                o.put("peakTime", EditorOracle.num(st.getPeakCRDuration().computeEffective(hull.getNoCRLossTime())));   // a StatBonus over the hull's base seconds
            }
            o.put("deployPoints", EditorOracle.num(member.getDeploymentPointsCost()));
        } catch (RuntimeException unavailable) {
            o.put("crError", String.valueOf(unavailable));
        }
        ShieldSpecAPI shield = hull.getShieldSpec();
        try {   // a throwing shield / cloak stat leaves the rows absent (the SPA hides them), never 500s /stats
            shieldBlock(o, hull, shield, st);
        } catch (RuntimeException unavailable) {
            o.put("shieldError", String.valueOf(unavailable));
        }
        // The refit's "weapon flux/sec" row = every non-decorative weapon's
        // spec sustained flux/sec summed — no ship stats in vanilla's sum either. The variant's fitted-slot list
        // ALREADY carries the hull's built-in weapons (an empty Onslaught lists its two TPC slots — the smoke
        // measured 600 with a second built-in pass = a double count), so one pass over it is vanilla's getAllWeapons.
        float weaponFlux = 0f;
        for (String slotId : v.getFittedWeaponSlots()) {
            try {   // one throwing (modded) FITTED weapon adds 0 and names itself, never 500s /stats
                weaponFlux += sustainedFlux(v.getWeaponSpec(slotId));
            } catch (RuntimeException unavailable) {
                o.put("weaponFluxError", slotId + ": " + unavailable);
            }
        }
        o.put("weaponFlux", EditorOracle.num(weaponFlux));
        // the RAW SPEC numbers the column's delta badges compare against (the SPA
        // compares, never computes) — the hull's own sheet values, no person, no CR
        JSONObject base = new JSONObject();
        base.put("hp", EditorOracle.num(hull.getHitpoints()));
        base.put("armor", EditorOracle.num(hull.getArmorRating()));
        base.put("fluxCap", EditorOracle.num(hull.getFluxCapacity()));
        base.put("fluxDiss", EditorOracle.num(hull.getFluxDissipation()));
        base.put("maxSpeed", EditorOracle.num(hull.getEngineSpec() == null ? 0f : hull.getEngineSpec().getMaxSpeed()));
        if (shield != null) {
            base.put("shieldArc", EditorOracle.num(shield.getArc()));
            base.put("fluxPerDamage", EditorOracle.num(shield.getFluxPerDamageAbsorbed()));
            base.put("shieldUpkeep", EditorOracle.num(shield.getUpkeepCost()));
            if (hull.isPhase()) {
                base.put("cloakFluxPerSec", EditorOracle.num(shield.getPhaseUpkeep()));
                base.put("cloakActivation", EditorOracle.num(shield.getPhaseCost()));
            }
        }
        o.put("base", base);
        o.put("crPreview", crPreview(cr, member, st, crApplyError));
        o.put("opCost", v.computeOPCost(person));
        o.put("opWeapons", v.computeWeaponOPCost(person));
        o.put("opHullMods", v.computeHullModOPCost(person));
        o.put("opUnused", v.getUnusedOP(person));
        o.put("opMax", hull.getOrdnancePoints(person));
        o.put("sModCap", Misc.getMaxPermanentMods(member, person));
        o.put("sMods", Misc.getCurrSpecialMods(v));
        JSONArray weapons = new JSONArray();
        for (String slotId : v.getFittedWeaponSlots()) {
            WeaponSpecAPI w = v.getWeaponSpec(slotId);
            if (w == null) {
                continue;
            }
            JSONObject wo = new JSONObject();
            wo.put("slot", slotId);
            try {   // the same per-BUILD getOrdnancePointCost(person, st) the catalog loop below guards —
                    // a throwing (modded) fitted weapon keeps a slot-named row with `error`, the rest of the build's rows still land
                wo.put("id", EditorOracle.nz(w.getWeaponId()));
                wo.put("name", EditorOracle.nz(w.getWeaponName()));
                weaponRow(wo, w, person, st);
            } catch (RuntimeException unavailable) {
                if (!wo.has("name")) {
                    wo.put("name", "?");
                }
                wo.put("error", String.valueOf(unavailable));
                o.put("weaponsError", slotId + ": " + unavailable);
            }
            weapons.put(wo);
        }
        o.put("weapons", weapons);
        // The build's per-type ammo law for CATALOG weapons (the picker rows / hovers show weapons that
        // are not fitted, so the per-slot `ammo` above cannot serve them): eff(base) = a·base + b — a StatBonus is affine in its base
        // (flat, then percent, then mult), so two probes pin it; the SPA rounds like the row above. Skills + Expanded Magazines /
        // Missile Racks are already in `st`.
        JSONObject ammoMul = new JSONObject();
        try {   // a throwing per-type ammo bonus leaves the law absent (the SPA keeps the spec ammo)
            for (WeaponType t : new WeaponType[] { WeaponType.BALLISTIC, WeaponType.ENERGY, WeaponType.MISSILE }) {
                StatBonus b = t == WeaponType.BALLISTIC ? st.getBallisticAmmoBonus() : t == WeaponType.ENERGY ? st.getEnergyAmmoBonus() : st.getMissileAmmoBonus();
                if (b == null) {
                    continue;
                }
                float b0 = b.computeEffective(0f);
                JSONObject law = new JSONObject();
                law.put("a", (double) (b.computeEffective(1f) - b0));
                law.put("b", (double) b0);
                ammoMul.put(t.name(), law);
            }
        } catch (RuntimeException unavailable) {
            o.put("ammoMulError", String.valueOf(unavailable));
        }
        o.put("ammoMul", ammoMul);
        weaponOpBlock(o, person, st);
        return o;
    }

    /** The shield + cloak rows (one guard over every engine read of the block). */
    private static void shieldBlock(JSONObject o, ShipHullSpecAPI hull, ShieldSpecAPI shield, MutableShipStatsAPI st) throws JSONException {
        if (shield != null) {
            JSONObject s = new JSONObject();
            s.put("type", shield.getType() == null ? "" : shield.getType().name());
            s.put("arc", EditorOracle.num(st.getShieldArcBonus().computeEffective(shield.getArc())));
            s.put("upkeepMult", EditorOracle.num(st.getShieldUpkeepMult().getModifiedValue()));
            s.put("damageTakenMult", EditorOracle.num(st.getShieldDamageTakenMult().getModifiedValue()));
            s.put("fluxPerDamage", EditorOracle.num(shield.getFluxPerDamageAbsorbed()));
            // The groups dialog's "Shield flux / sec" = Ship.getShieldUpkeep() = the shield's
            // getUpkeep() = spec upkeep cost × the ship's shield-upkeep mult, floored at 0
            // — the engine's own formula on the engine's own numbers, no live Ship needed
            s.put("upkeep", EditorOracle.num(Math.max(0f, shield.getUpkeepCost() * st.getShieldUpkeepMult().getModifiedValue())));
            // The refit's "shield flux/dam" row = shieldDamageTakenMult × the spec's flux per damage
            // — it MOVES with the CR preview (damage taken ∓10 %)
            s.put("fluxPerDamageEff", EditorOracle.num(st.getShieldDamageTakenMult().getModifiedValue() * shield.getFluxPerDamageAbsorbed()));
            o.put("shield", s);
            if (hull.isPhase()) {
                // the refit's cloak rows = the cloak's own charge-tracker numbers
                // — the ship's cost bonuses over the spec's phase cost / upkeep
                JSONObject c = new JSONObject();
                c.put("activation", EditorOracle.num(st.getPhaseCloakActivationCostBonus().computeEffective(shield.getPhaseCost())));
                c.put("fluxPerSec", EditorOracle.num(st.getPhaseCloakUpkeepCostBonus().computeEffective(shield.getPhaseUpkeep())));
                o.put("cloak", c);
            }
        }
    }

    /** One fitted weapon's per-build numbers (the body of the fitted-weapon loop, so its engine reads sit under
     *  ONE guard): effective OP, the ship's per-type range bonus over the spec range, the magazine through the per-type ammo bonus. */
    private static void weaponRow(JSONObject wo, WeaponSpecAPI w, MutableCharacterStatsAPI person, MutableShipStatsAPI st) throws JSONException {
        wo.put("op", EditorOracle.num(w.getOrdnancePointCost(person, st)));
        StatBonus rangeBonus = null;
        if (w.getType() == WeaponType.BALLISTIC) {
            rangeBonus = st.getBallisticWeaponRangeBonus();
        } else if (w.getType() == WeaponType.ENERGY) {
            rangeBonus = st.getEnergyWeaponRangeBonus();
        } else if (w.getType() == WeaponType.MISSILE) {
            rangeBonus = st.getMissileWeaponRangeBonus();
        }
        wo.put("baseRange", EditorOracle.num(w.getMaxRange()));
        wo.put("range", EditorOracle.num(rangeBonus == null ? w.getMaxRange() : rangeBonus.computeEffective(w.getMaxRange())));
        // The magazine — the spec's max ammo through the ship's per-type ammo bonus (Missile
        // Specialization et al); a weapon without ammo carries no row
        if (w.usesAmmo() && w.getMaxAmmo() > 0) {
            StatBonus ammoBonus = w.getType() == WeaponType.BALLISTIC ? st.getBallisticAmmoBonus()
                    : w.getType() == WeaponType.ENERGY ? st.getEnergyAmmoBonus()
                    : w.getType() == WeaponType.MISSILE ? st.getMissileAmmoBonus() : null;
            wo.put("baseAmmo", w.getMaxAmmo());
            wo.put("ammo", ammoBonus == null ? w.getMaxAmmo() : Math.round(ammoBonus.computeEffective(w.getMaxAmmo())));
        }
    }

    /** Per-build weapon OP: a Devastator on an Invictus should read 10 OP and be allowed, the list staying ORDERED by the base
     *  20. The picker priced every row off the catalog's bare getOrdnancePointCost(null); the game's cost is per BUILD —
     *  getOrdnancePointCost(person, st) runs the person's per-size OP stats, then the ship's dynamic {@code <size>_<type>_mod} / _beam_mod /
     *  _pd_mod (Heavy Ballistics Integration = large_ballistic_mod −10, any hullmod / skill of that
     *  family). weaponOp = the SPARSE map id → effective cost over every catalog weapon whose cost differs (the SPA keeps the bare op
     *  for the rest AND for the sort); weaponOpWhy = per dynamic key, the sources that moved it ("Heavy Ballistics Integration −10") —
     *  the SPA picks the keys the spec's size / type / beam / pd select, the same selection the game makes. */
    private static void weaponOpBlock(JSONObject o, MutableCharacterStatsAPI person, MutableShipStatsAPI st) throws JSONException {
        JSONObject weaponOp = new JSONObject();
        for (WeaponSpecAPI w : Global.getSettings().getAllWeaponSpecs()) {
            try {   // One throwing (modded) weapon spec keeps its bare catalog op, never 500s /stats
                float eff = w.getOrdnancePointCost(person, st);
                if (eff != w.getOrdnancePointCost(null)) {
                    weaponOp.put(EditorOracle.nz(w.getWeaponId()), EditorOracle.num(eff));
                }
            } catch (RuntimeException unavailable) {
                o.put("weaponOpError", String.valueOf(unavailable));
            }
        }
        o.put("weaponOp", weaponOp);
        try {
            o.put("weaponOpWhy", opModSources(st));
        } catch (RuntimeException unavailable) {
            o.put("weaponOpWhyError", String.valueOf(unavailable));
        }
    }

    /** The build's weapon-OP modifiers by dynamic key (the 15 {@code <size>_<ballistic|energy|missile|beam|pd>_mod} stats
     *  the game consults), each a text of its sources with the flat / percent / mult they add; a key nothing moved is absent. */
    private static JSONObject opModSources(MutableShipStatsAPI st) throws JSONException {
        JSONObject out = new JSONObject();
        for (String size : new String[] { "small", "medium", "large" }) {
            for (String kind : new String[] { "ballistic", "energy", "missile", "beam", "pd" }) {
                String key = size + "_" + kind + "_mod";
                StatBonus m = st.getDynamic().getMod(key);   // a dynamic mod is a StatBonus (the bays stat above is a MutableStat: getFlatMods)
                StringBuilder sb = new StringBuilder();
                for (StatMod sm : m.getFlatBonuses().values()) {
                    if (sm.value != 0f) { modText(sb, sm.source, (sm.value > 0 ? "+" : "−") + EditorOracle.num(Math.abs(sm.value))); }
                }
                for (StatMod sm : m.getPercentBonuses().values()) {
                    if (sm.value != 0f) { modText(sb, sm.source, (sm.value > 0 ? "+" : "−") + EditorOracle.num(Math.abs(sm.value)) + "%"); }
                }
                for (StatMod sm : m.getMultBonuses().values()) {
                    if (sm.value != 1f) { modText(sb, sm.source, "×" + EditorOracle.num(sm.value)); }
                }
                if (sb.length() > 0) {
                    out.put(key, sb.toString());
                }
            }
        }
        return out;
    }

    private static void modText(StringBuilder sb, String source, String amount) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(sourceName(source)).append(' ').append(amount);
    }

    /** A stat source id as the owner reads it: a hullmod's display name, else a skill's name, else the id itself. */
    private static String sourceName(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        try {
            HullModSpecAPI hm = Global.getSettings().getHullModSpec(id);
            if (hm != null && hm.getDisplayName() != null) {
                return hm.getDisplayName();
            }
        } catch (RuntimeException ignored) {
            // not a hullmod id
        }
        try {
            SkillSpecAPI sk = Global.getSettings().getSkillSpec(id);
            if (sk != null && sk.getName() != null) {
                return sk.getName();
            }
        } catch (RuntimeException ignored) {
            // not a skill id
        }
        return id;
    }

    /** A weapon spec's sustained flux/sec for the refit's sum; a decorative / absent spec counts 0. */
    private static float sustainedFlux(WeaponSpecAPI w) {
        if (w == null || w.getType() == WeaponType.DECORATIVE) {
            return 0f;
        }
        WeaponAPI.DerivedWeaponStatsAPI ds = w.getDerivedStats();
        return ds == null ? 0f : ds.getSustainedFluxPerSecond();
    }

    /** The CR bar's own rows: the installed CR plugin's thresholds on THIS ship's stats (a hullmod / skill mult
     *  moves them) and its tooltip rows ({@code getCREffectDetails}: Speed &amp; maneuverability · Damage taken ·
     *  Damage dealt · [Fighter refit time] · Autofire accuracy · Malfunction risk · [Missile magazines], each
     *  BONUS / PENALTY / NEUTRAL) — the SPA paints them, computes nothing. The improve / degrade thresholds are
     *  {@link CRPluginImpl} public methods off the interface; a foreign plugin leaves them absent (the SPA then
     *  draws no ticks). Guarded like the CR block: an accessor throw = {@code error}, never a 500. */
    private static JSONObject crPreview(float cr, FleetMemberAPI member, MutableShipStatsAPI st, String applyError) throws JSONException {
        JSONObject cp = new JSONObject();
        cp.put("cr", Math.round(cr * 100f));
        cp.put("applied", applyError == null);
        if (applyError != null) {
            cp.put("applyError", applyError);
        }
        try {
            CombatReadinessPlugin plugin = Global.getSettings().getCRPlugin();
            if (plugin == null) {
                cp.put("error", "no CR plugin");
                return cp;
            }
            cp.put("malfunction", EditorOracle.num(plugin.getMalfunctionThreshold(st)));
            cp.put("critical", EditorOracle.num(plugin.getCriticalMalfunctionThreshold(st)));
            if (plugin instanceof CRPluginImpl) {
                cp.put("improve", EditorOracle.num(((CRPluginImpl) plugin).getImproveThreshold(st)));
                cp.put("degrade", EditorOracle.num(((CRPluginImpl) plugin).getDegradeThreshold(st)));
            }
            JSONArray effects = new JSONArray();
            List<CombatReadinessPlugin.CREffectDetail> details = plugin.getCREffectDetails(cr, member);
            if (details != null) {
                for (CombatReadinessPlugin.CREffectDetail d : details) {
                    if (d == null) {
                        continue;
                    }
                    JSONObject e = new JSONObject();
                    e.put("desc", EditorOracle.nz(d.getDesc()));
                    e.put("value", EditorOracle.nz(d.getValue()));
                    e.put("type", d.getType() == null ? "NEUTRAL" : d.getType().name());
                    effects.put(e);
                }
            }
            cp.put("effects", effects);
        } catch (RuntimeException unavailable) {
            cp.put("error", String.valueOf(unavailable));
        }
        return cp;
    }
}
