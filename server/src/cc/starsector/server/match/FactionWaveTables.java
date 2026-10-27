package cc.starsector.server.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seven MAJOR-FACTION fortress wave
 * tables, their per-wave officer RULE, and the one-wave ZIGGURAT boss category — the
 * {@link WaveSchedule#fortressWavePattern} arms for every category the recon added
 * ({@code WaveSchedule} itself is past its size tier, so the data lives here).
 *
 * <p>PROVENANCE: every table was authored faction by faction
 * from VANILLA
 * data only — doctrine fleet generation is campaign machinery that never runs headless,
 * so the tables are hand-authored vanilla variant ids: each id was
 * opened on disk and its {@code "variantId"} read, each hull's engine DP read from
 * {@code ship_data.csv supplies/rec} (or the skin's {@code suppliesToRecover} override), and
 * every wave DP-summed inside the ±15% band of the ratified AUTO targets 20.3 / 29 / 40.6 /
 * 52.2 / 72.5 / 98.6 / 145 at the seat basis 58. Rows are SMALL-FIRST (the spawn order: screen,
 * then heavies) and the requested sub-faction elites escalate from wave 5 — XIV
 * Battlegroup (Hegemony), Lion's Guard (Diktat), pather / church skins, pirate skins. The per-wave
 * DP tables + every judgement call live in the authoring notes; any pick can be vetoed
 * after play-testing like every other authored table.
 *
 * <p>THE OFFICER RULE (the "increasing officer levels and officer frequency as
 * waves go on" ask): officers are ROW-carried {@link OfficerRosterCodec.OfficerSpec}s
 * ({@link WaveSchedule.WaveRow#officer()}), attached server-side at the roster's attach site —
 * NO {@code _cc_} twin per faction hull, no mod data, no wire field (the client never officers
 * a wave foe). Per wave index 0..6 (waves 2..8) the LAST {@link #OFFICER_SHARE} rows of the
 * small-first order (= the heaviest hulls) carry a level-{@link #OFFICER_LEVEL} captain whose
 * skills are the first {@code level} ids of the faction's pool ({@link #SKILL_POOLS} — every
 * id already validated by {@code officers.txt} / the vanilla Ziggurat captain); waves 7 and 8
 * make the first two skills ELITE. Personality follows the faction's doctrine aggression
 * (recon L5): steady for Hegemony / League / Tri-Tachyon, aggressive for Church / Diktat /
 * Pirates, reckless for the Path. The SCALED cyclic fill copies each proto's spec, so officer
 * COUNT grows with the wave's DP while the TIER stays authored. Every spec is skill-checked at
 * boot for EVERY category ({@code OfficerAttach.validateRowSpecs}).
 *
 * <p>ZIGGURAT ("the boss version of the ziggurat (not the player version)"): a
 * {@link WaveSchedule#BOSS_ONLY_CATEGORIES} member — ONE timer-armed wave of
 * {@code ziggurat_Experimental} (the {@code ziggurat_HF} duplicate-id file is never named),
 * captained by the vanilla black-site boss VERBATIM ({@code TTBlackSite.createZigguratCaptain}):
 * "Motes", reckless, level 10, ten skills elite ({@code navigation}
 * dropped — a campaign skill, inert on a captain). The boss AI is the engine's own
 * {@code zigguratOnEnemySide} config (no bespoke class exists in vanilla either).
 */
public final class FactionWaveTables {

    private FactionWaveTables() {
    }

    /** Officered rows per wave index (from the END of the small-first row order). */
    static final int[] OFFICER_SHARE = {0, 1, 1, 2, 2, 3, 4};
    /** Officer level per wave index (0 = the wave fields no officer). */
    static final int[] OFFICER_LEVEL = {0, 3, 4, 5, 6, 7, 8};
    /** Wave index from which the first two skills are ELITE (level 2). */
    static final int ELITE_FROM_WAVE_INDEX = 5;

    private static final Map<String, String[]> SKILL_POOLS = new LinkedHashMap<>();
    private static final Map<String, String> PERSONALITY = new LinkedHashMap<>();
    private static final Map<String, String> DISPLAY = new LinkedHashMap<>();
    private static final Map<String, String[]> PORTRAITS = new LinkedHashMap<>();

    static {
        pool("hegemony", "steady", "Hegemony",
                new String[] {"ballistic_mastery", "impact_mitigation", "helmsmanship",
                        "target_analysis", "damage_control", "combat_endurance", "point_defense",
                        "polarized_armor"},
                "hegemony01", "hegemony03", "hegemony05", "hegemony07");
        pool("persean_league", "steady", "Persean League",
                new String[] {"helmsmanship", "ballistic_mastery", "target_analysis",
                        "impact_mitigation", "field_modulation", "combat_endurance",
                        "damage_control", "systems_expertise"},
                "league00", "league01", "league02", "league03");
        pool("tritachyon", "steady", "Tri-Tachyon",
                new String[] {"energy_weapon_mastery", "helmsmanship", "field_modulation",
                        "target_analysis", "systems_expertise", "combat_endurance",
                        "gunnery_implants", "impact_mitigation"},
                "corporate01", "corporate02", "corporate03", "corporate04");
        pool("luddic_church", "aggressive", "Luddic Church",
                new String[] {"ballistic_mastery", "impact_mitigation", "damage_control",
                        "polarized_armor", "helmsmanship", "combat_endurance", "target_analysis",
                        "point_defense"},
                "luddic00", "luddic01", "luddic02", "luddic03");
        pool("luddic_path", "reckless", "Luddic Path",
                new String[] {"impact_mitigation", "ballistic_mastery", "helmsmanship",
                        "combat_endurance", "damage_control", "missile_specialization",
                        "target_analysis", "polarized_armor"},
                "luddic04", "luddic05", "luddic06", "luddic07");
        pool("sindrian_diktat", "aggressive", "Sindrian Diktat",
                new String[] {"target_analysis", "helmsmanship", "energy_weapon_mastery",
                        "ballistic_mastery", "field_modulation", "combat_endurance",
                        "impact_mitigation", "systems_expertise"},
                "diktat01", "diktat02", "diktat03", "diktat04");
        pool("pirates", "aggressive", "Pirate",
                new String[] {"helmsmanship", "impact_mitigation", "missile_specialization",
                        "ballistic_mastery", "combat_endurance", "damage_control",
                        "target_analysis", "point_defense"},
                "hegemony14", "luddic07", "diktat14", "corporate10");
    }

    private static void pool(String category, String personality, String display,
            String[] skills, String... portraitStems) {
        SKILL_POOLS.put(category, skills);
        PERSONALITY.put(category, personality);
        DISPLAY.put(category, display);
        String[] paths = new String[portraitStems.length];
        for (int i = 0; i < portraitStems.length; i++) {
            paths[i] = "graphics/portraits/portrait_" + portraitStems[i] + ".png";
        }
        PORTRAITS.put(category, paths);
    }

    /** The Ziggurat boss variant (vanilla; the black-site boss fields exactly this loadout). */
    public static final String ZIGGURAT_BOSS_VARIANT_ID = "ziggurat_Experimental";
    /** The vanilla boss portrait ({@code settings.json graphics.characters.ziggurat_captain}). */
    public static final String ZIGGURAT_BOSS_PORTRAIT = "graphics/portraits/characters/zig_captain.png";

    /** The vanilla black-site captain, verbatim minus {@code navigation} (inert on a captain). */
    public static OfficerRosterCodec.OfficerSpec zigguratBossCaptain() {
        Map<String, Float> skills = new LinkedHashMap<>();
        for (String id : new String[] {"helmsmanship", "target_analysis", "impact_mitigation",
                "gunnery_implants", "energy_weapon_mastery", "combat_endurance", "polarized_armor",
                "missile_specialization", "field_modulation", "damage_control"}) {
            skills.put(id, 2.0f);
        }
        return new OfficerRosterCodec.OfficerSpec(ZIGGURAT_BOSS_VARIANT_ID, "Motes", "",
                "reckless", ZIGGURAT_BOSS_PORTRAIT, 10, skills);
    }

    /**
     * The authored pattern for one of the categories this class owns (waves 2–8 for a faction,
     * decorated by the officer rule; ONE boss wave for {@code ziggurat}). Any other category
     * THROWS — {@link WaveSchedule#fortressWavePattern} routes only its own arms here.
     */
    public static List<List<WaveSchedule.WaveRow>> pattern(String category) {
        switch (category) {
            case "ziggurat":
                return List.of(List.of(new WaveSchedule.WaveRow(1, ZIGGURAT_BOSS_VARIANT_ID,
                        WaveSchedule.SLOT_NAME_PREFIX + "2.1", zigguratBossCaptain())));
            case "hegemony":
                return decorate(category, hegemony());
            case "persean_league":
                return decorate(category, perseanLeague());
            case "tritachyon":
                return decorate(category, tritachyon());
            case "luddic_church":
                return decorate(category, luddicChurch());
            case "luddic_path":
                return decorate(category, luddicPath());
            case "sindrian_diktat":
                return decorate(category, sindrianDiktat());
            case "pirates":
                return decorate(category, pirates());
            default:
                throw new IllegalArgumentException("FactionWaveTables owns no table for '"
                        + category + "'");
        }
    }

    /** True for the seven faction categories (the officer rule applies; ziggurat excluded). */
    public static boolean isFactionCategory(String category) {
        return SKILL_POOLS.containsKey(category);
    }

    /** The officer rule for one row: null below the share line, else the wave's captain. */
    static OfficerRosterCodec.OfficerSpec officerFor(String category, int waveIndex,
            int rowIndex, int rowCount, String variantId) {
        int share = OFFICER_SHARE[waveIndex];
        int level = OFFICER_LEVEL[waveIndex];
        if (share <= 0 || level <= 0 || rowIndex < rowCount - share) {
            return null;
        }
        String[] pool = SKILL_POOLS.get(category);
        Map<String, Float> skills = new LinkedHashMap<>();
        for (int i = 0; i < level && i < pool.length; i++) {
            skills.put(pool[i], waveIndex >= ELITE_FROM_WAVE_INDEX && i < 2 ? 2.0f : 1.0f);
        }
        String[] portraits = PORTRAITS.get(category);
        return new OfficerRosterCodec.OfficerSpec(variantId, DISPLAY.get(category), "Captain",
                PERSONALITY.get(category), portraits[rowIndex % portraits.length], level, skills);
    }

    private static List<List<WaveSchedule.WaveRow>> decorate(String category,
            List<List<WaveSchedule.WaveRow>> bare) {
        if (bare.size() != OFFICER_SHARE.length) {
            throw new IllegalStateException(category + " table carries " + bare.size()
                    + " waves, the officer rule " + OFFICER_SHARE.length);
        }
        List<List<WaveSchedule.WaveRow>> out = new ArrayList<>(bare.size());
        for (int w = 0; w < bare.size(); w++) {
            List<WaveSchedule.WaveRow> rows = bare.get(w);
            List<WaveSchedule.WaveRow> decorated = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                WaveSchedule.WaveRow row = rows.get(i);
                decorated.add(new WaveSchedule.WaveRow(row.side(), row.variantId(),
                        row.shipName(), officerFor(category, w, i, rows.size(), row.variantId())));
            }
            out.add(List.copyOf(decorated));
        }
        return List.copyOf(out);
    }

    /** One authored wave: owner wave number + variant ids in spawn order (small-first). */
    private static List<WaveSchedule.WaveRow> wave(int w, String... ids) {
        List<WaveSchedule.WaveRow> rows = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            rows.add(new WaveSchedule.WaveRow(1, ids[i],
                    WaveSchedule.SLOT_NAME_PREFIX + w + "." + (i + 1)));
        }
        return List.copyOf(rows);
    }

    // ---- the seven tables (lane files carry the per-wave DP math) ----

    /** HEGEMONY: XIV Battlegroup from w5; w8 = the full XIV line + onslaughts. */
    private static List<List<WaveSchedule.WaveRow>> hegemony() {
        return List.of(
                wave(2, "kite_hegemony_Interceptor", "hound_hegemony_Standard", "lasher_Standard",
                        "lasher_Assault", "centurion_Assault", "wolf_hegemony_Assault"),
                wave(3, "lasher_CS", "lasher_Strike", "wolf_hegemony_CS", "gremlin_Strike",
                        "enforcer_Balanced"),
                wave(4, "hound_hegemony_Standard", "enforcer_Assault", "enforcer_CS",
                        "condor_Attack", "falcon_Attack"),
                wave(5, "wolf_hegemony_PD", "enforcer_Escort", "enforcer_XIV_Elite",
                        "condor_Support", "eagle_Assault"),
                wave(6, "condor_Strike", "falcon_xiv_Escort", "eagle_xiv_Elite",
                        "dominator_Assault"),
                wave(7, "falcon_xiv_Elite", "mora_Assault", "dominator_XIV_Elite",
                        "legion_xiv_Elite"),
                wave(8, "enforcer_XIV_Elite", "falcon_xiv_Elite", "eagle_xiv_Elite",
                        "dominator_XIV_Elite", "onslaught_Elite", "onslaught_xiv_Elite"));
    }

    /** PERSEAN LEAGUE: no elite skin line exists — the up-armed stock loadouts escalate. */
    private static List<List<WaveSchedule.WaveRow>> perseanLeague() {
        return List.of(
                wave(2, "vigilance_Standard", "vigilance_Strike", "wayfarer_Standard",
                        "brawler_Assault"),
                wave(3, "vigilance_FS", "brawler_Support", "brawler_Assault", "monitor_Escort",
                        "hammerhead_Balanced"),
                wave(4, "vigilance_Standard", "vigilance_Strike", "gemini_Standard",
                        "hammerhead_Support", "sunder_Assault"),
                wave(5, "brawler_Elite", "hammerhead_Overdriven", "sunder_CS", "drover_Strike",
                        "falcon_Attack"),
                wave(6, "sunder_Overdriven", "drover_Support", "falcon_CS", "heron_Attack",
                        "gryphon_Standard"),
                wave(7, "monitor_Escort", "heron_Strike", "eagle_Assault", "gryphon_DEM",
                        "conquest_Standard"),
                wave(8, "brawler_Elite", "monitor_Escort", "eagle_Balanced", "champion_Elite",
                        "conquest_Elite", "pegasus_Balanced"));
    }

    /** TRI-TACHYON: phase ships in every wave; astral/odyssey/paragon close. */
    private static List<List<WaveSchedule.WaveRow>> tritachyon() {
        return List.of(
                wave(2, "kite_original_Stock", "brawler_tritachyon_Standard", "omen_PD",
                        "shade_Assault"),
                wave(3, "brawler_tritachyon_Standard", "omen_PD", "afflictor_Strike",
                        "shrike_Attack"),
                wave(4, "tempest_Attack", "scarab_Experimental", "shrike_Support",
                        "harbinger_Strike"),
                wave(5, "shade_Assault", "hyperion_Attack", "medusa_Attack", "fury_Attack"),
                wave(6, "omen_PD", "afflictor_Strike", "medusa_CS", "anubis_Standard",
                        "aurora_Attack"),
                wave(7, "hyperion_Strike", "phantom_Elite", "doom_Attack", "odyssey_Balanced"),
                wave(8, "shade_Assault", "afflictor_Strike", "harbinger_Strike", "astral_Elite",
                        "paragon_Elite"));
    }

    /** LUDDIC CHURCH: outdated-hull screens + carriers; retribution / invictus close. */
    private static List<List<WaveSchedule.WaveRow>> luddicChurch() {
        return List.of(
                wave(2, "shepherd_Frontier", "hound_luddic_church_Standard", "cerberus_Standard",
                        "lasher_luddic_church_Standard", "vanguard_Outdated"),
                wave(3, "cerberus_Hardened", "vanguard_Outdated2", "vanguard_Strike",
                        "buffalo2_Fighter_Support", "condor_Strike"),
                wave(4, "cerberus_Shielded", "vanguard_Attack", "mule_Fighter_Support",
                        "manticore_Balanced", "venture_Balanced"),
                wave(5, "hound_luddic_path_Attack", "cerberus_luddic_path_Attack", "buffalo2_FS",
                        "condor_Support", "manticore_Support", "mora_Torpedo"),
                wave(6, "lasher_luddic_path_Raider", "manticore_luddic_path_Strike",
                        "venture_Outdated", "eradicator_Outdated", "eradicator_Fighter_Support"),
                wave(7, "manticore_Assault", "venture_pather_Attack", "mora_Assault",
                        "eradicator_Outdated2", "retribution_Standard"),
                wave(8, "condor_Attack", "eradicator_Assault", "invictus_Support",
                        "invictus_Standard"));
    }

    /** LUDDIC PATH: pather-skin swarms; the converted-tanker prometheus2 pair closes. */
    private static List<List<WaveSchedule.WaveRow>> luddicPath() {
        return List.of(
                wave(2, "kite_luddic_path_Raider", "kite_luddic_path_Strike",
                        "hound_luddic_path_Attack", "cerberus_luddic_path_Attack",
                        "lasher_luddic_path_Raider", "gremlin_luddic_path_Strike"),
                wave(3, "kite_luddic_path_Strike", "hound_luddic_path_Attack",
                        "cerberus_luddic_path_Attack", "lasher_luddic_path_Raider",
                        "brawler_pather_Raider", "enforcer_Assault"),
                wave(4, "kite_luddic_path_Raider", "hound_luddic_path_Attack",
                        "cerberus_luddic_path_Attack", "gremlin_luddic_path_Strike",
                        "brawler_pather_Raider", "enforcer_Balanced", "hammerhead_Balanced"),
                wave(5, "kite_luddic_path_Strike", "lasher_luddic_path_Raider",
                        "brawler_pather_Raider", "enforcer_Overdriven",
                        "manticore_luddic_path_Strike", "venture_pather_Attack"),
                wave(6, "lasher_luddic_path_Raider", "gremlin_luddic_path_Strike",
                        "hammerhead_Overdriven", "manticore_luddic_path_Strike",
                        "venture_pather_Attack", "eradicator_Assault"),
                wave(7, "gremlin_luddic_path_Strike", "brawler_pather_Raider",
                        "sunder_Overdriven", "manticore_luddic_path_Strike",
                        "venture_pather_Attack", "eradicator_Assault", "eradicator_Strike"),
                wave(8, "brawler_pather_Raider", "manticore_luddic_path_Strike",
                        "venture_pather_Attack", "eradicator_Assault", "eradicator_Overdriven",
                        "prometheus2_Standard", "prometheus2_Standard"));
    }

    /** SINDRIAN DIKTAT: Lion's Guard from w5; executor_Standard w7, executor_Elite w8. */
    private static List<List<WaveSchedule.WaveRow>> sindrianDiktat() {
        return List.of(
                wave(2, "mercury_Attack", "centurion_Starting", "wayfarer_Standard",
                        "brawler_Starting", "brawler_Support"),
                wave(3, "mercury_Standard", "mercury_PD", "centurion_Assault", "brawler_Assault",
                        "brawler_Elite", "hammerhead_Balanced"),
                wave(4, "wayfarer_Starting", "hammerhead_Support", "sunder_Assault",
                        "falcon_Attack"),
                wave(5, "centurion_LG_Assault", "brawler_LG_Elite", "hammerhead_Elite",
                        "hammerhead_LG_Balanced", "sunder_LG_Assault", "falcon_CS"),
                wave(6, "centurion_LG_Assault", "hammerhead_LG_Elite", "sunder_LG_CS",
                        "falcon_Escort", "falcon_LG_Attack", "eagle_Assault"),
                wave(7, "falcon_LG_CS", "eagle_Balanced", "eagle_LG_Assault", "executor_Standard"),
                wave(8, "sunder_LG_Assault", "falcon_LG_Attack", "eagle_LG_Support",
                        "executor_Standard", "executor_Elite"));
    }

    /** PIRATES: pirate-skin swarm; venture_p w5, atlas2 debut w7, three Atlas Mk.II w8. */
    private static List<List<WaveSchedule.WaveRow>> pirates() {
        return List.of(
                wave(2, "kite_pirates_Raider", "mudskipper2_CS", "mudskipper2_Hellbore",
                        "hound_d_pirates_Standard", "hound_d_pirates_Shielded",
                        "cerberus_d_pirates_Standard", "wolf_d_pirates_Attack"),
                wave(3, "cerberus_d_pirates_Shielded", "gremlin_d_pirates_Strike",
                        "shade_d_pirates_Assault", "vanguard_pirates_Strike", "shrike_p_Attack"),
                wave(4, "hound_d_pirates_Overdriven", "vanguard_pirates_Attack",
                        "mule_d_pirates_Standard", "mule_d_pirates_Smuggler",
                        "enforcer_d_pirates_Strike", "manticore_pirates_Support"),
                wave(5, "afflictor_d_pirates_Strike", "shrike_p_Attack", "enforcer_d_pirates_Strike",
                        "manticore_pirates_Assault", "venture_p_Pirate"),
                wave(6, "gremlin_d_pirates_Strike", "manticore_pirates_Support", "venture_p_Pirate",
                        "eradicator_pirates_Attack", "falcon_p_Strike"),
                wave(7, "afflictor_d_pirates_Strike", "manticore_pirates_Assault",
                        "eradicator_pirates_Attack", "eradicator_pirates_Overdriven",
                        "falcon_p_Strike", "atlas2_Standard"),
                wave(8, "manticore_pirates_Assault", "eradicator_pirates_Attack",
                        "eradicator_pirates_Overdriven", "falcon_p_Strike", "atlas2_Standard",
                        "atlas2_Standard", "atlas2_Standard"));
    }
}
