package cc.starsector.server.match;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The MAXED captain a {@code /spawnfoe} /
 * {@code /spawnfriend} row toggled to {@code officer} fields, by HULL KIND — the requirement:
 * "a maxxed officer ... means different things depending on the kind of ship... remnants need
 * alpha cores, ziggurat needs its special officer, most ships need human officers". Pure and
 * game-free (contract rows pin every recipe); the engine reads that CLASSIFY a variant live in
 * {@code cc.starsector.server.platform.AdhocOfficerTable} (boot-warmed for every catalogue id,
 * never on the sim thread).
 *
 * <p>The three recipes:
 * <ul>
 * <li>{@link HullKind#AUTOMATED} ⇒ a REMNANT-FLEET alpha core: the vanilla
 *     {@code AICoreOfficerPluginImpl} core (level 7, the seven elite skills,
 *     reckless, portrait {@code portrait_ai2b}, name "Alpha Core", {@code aiCoreId=alpha_core} so
 *     {@code PersonAPI.isAICore()} reads true on BOTH ends; the {@code $autoPoints*} memory keys
 *     are campaign-only and never minted) PLUS the fleet generator's integrate-and-adapt step
 *     ({@code RemnantOfficerGeneratorPlugin.integrateAndAdaptCoreForAIFleet},
 *     applied to every non-omega core of a remnant fleet): level +1 and ONE adapted elite skill
 *     picked from the hull's loadout ({@link #remnantAlphaCore}). Asked after
 *     a test flight ("don't remnants have higher level officers than 7?") — yes, a fleet alpha is
 *     L8; the raw L7 core ({@link #alphaCore}) is what a lone salvaged core / the sim fields.
 *     Written as pure Java (the {@code data/cc/officers.txt} twins precedent; rewritten from a
 *     functional spec) because the API
 *     helper needs a live {@code FleetMemberAPI} on the spawn path and flips {@code skipRefresh}
 *     off (a headless NPE).</li>
 * <li>{@link HullKind#ZIGGURAT} ⇒ {@link FactionWaveTables#zigguratBossCaptain()} re-keyed to
 *     the picked variant (the boss table hard-codes {@code ziggurat_Experimental}; vanilla ships
 *     {@code ziggurat_Strike} too).</li>
 * <li>{@link HullKind#HUMAN} ⇒ the chosen "vanilla exceptional cap: level 7, five elite"
 *     ({@code settings.json exceptionalSleeperPodsOfficerLevel=7 / ...EliteSkills=5}), personality
 *     AGGRESSIVE: seven skills from the pool the hull's WEAPON MIX selects ({@link WeaponMix}),
 *     the first five elite. Name + portrait are deterministic per variant id (the same captain
 *     every time the same hull is picked, so the flight's log rows and the RAPP tail agree).</li>
 * </ul>
 * Every skill id below already rides {@code data/cc/officers.txt} or {@link FactionWaveTables}
 * (boot-validated there); the boot table re-validates each synthesized spec anyway.
 */
public final class AdhocOfficerFactory {

    private AdhocOfficerFactory() {
    }

    /** The hull classes the owner named; {@code HUMAN} = everything else. */
    public enum HullKind { AUTOMATED, ZIGGURAT, HUMAN }

    /** The fitted-weapon majority a human captain's pool follows; {@code MIXED} = no majority. */
    public enum WeaponMix { BALLISTIC, ENERGY, MISSILE, MIXED }

    /** One resolved captain: {@code kind} = the log label ({@code alpha} / {@code ziggurat} /
     *  {@code human-<mix>}), {@code spec} keyed to the picked variant id. */
    public record Pick(String kind, OfficerRosterCodec.OfficerSpec spec) {
        public Pick {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(spec, "spec");
        }

        /** The {@code officer=} log value: {@code <kind>:<level>}. */
        public String logLabel() {
            return kind + ":" + spec.level;
        }
    }

    /** The human recipe's level / elite count (the vanilla exceptional cap). */
    public static final int HUMAN_LEVEL = 7;
    public static final int HUMAN_ELITE = 5;
    public static final String HUMAN_PERSONALITY = "aggressive";

    /** The vanilla alpha recipe (the API source's {@code AICoreOfficerPluginImpl}). */
    public static final String ALPHA_CORE_ID = "alpha_core";
    public static final int ALPHA_LEVEL = 7;
    public static final String ALPHA_PORTRAIT = "graphics/portraits/portrait_ai2b.png";
    static final String[] ALPHA_SKILLS = {"helmsmanship", "target_analysis", "impact_mitigation",
            "field_modulation", "gunnery_implants", "combat_endurance", "damage_control"};

    /** The remnant-fleet integration (the API source's {@code RemnantOfficerGeneratorPlugin}). */
    public static final int REMNANT_ALPHA_LEVEL = ALPHA_LEVEL + 1;
    static final String SKILL_POINT_DEFENSE = "point_defense";
    static final String SKILL_BALLISTIC_MASTERY = "ballistic_mastery";
    static final String SKILL_ENERGY_WEAPON_MASTERY = "energy_weapon_mastery";
    static final String SKILL_MISSILE_SPECIALIZATION = "missile_specialization";
    static final String SKILL_POLARIZED_ARMOR = "polarized_armor";
    static final String SKILL_COMBAT_ENDURANCE = "combat_endurance";

    /**
     * The loadout facts the vanilla adapt step reads off a remnant hull (the API source's
     * {@code RemnantOfficerGeneratorPlugin}): size-weighted fitted weapons (small 1,
     * medium 2, large 4 — {@link #weaponWeight}), the PD-hinted share, the missile share, each
     * flight deck adding 4 to BOTH {@code weight} and {@code pdWeight}; {@code capitalOrStation}
     * = {@code member.isCapital() || member.isStation()}; {@code derelict} = the hull tag.
     * {@link #NONE} = an unweighed hull (the adapt step then adds Energy Weapon Mastery, exactly
     * as vanilla does for a weaponless member).
     */
    public record AlphaLoadout(float weight, float pdWeight, float missileWeight,
                               boolean capitalOrStation, boolean derelict) {
        public static final AlphaLoadout NONE = new AlphaLoadout(0f, 0f, 0f, false, false);

        /** Vanilla's size weight: MEDIUM 2, LARGE 4, anything else 1. */
        public static float weaponWeight(String weaponSizeName) {
            if ("MEDIUM".equals(weaponSizeName)) {
                return 2f;
            }
            if ("LARGE".equals(weaponSizeName)) {
                return 4f;
            }
            return 1f;
        }

        /** Vanilla's per-deck weight. */
        public static final float DECK_WEIGHT = 4f;
    }

    /** Seven-id pools, weapon-mix keyed; the first {@link #HUMAN_ELITE} are elite. */
    static final String[] BALLISTIC_POOL = {"ballistic_mastery", "target_analysis",
            "impact_mitigation", "helmsmanship", "combat_endurance", "damage_control",
            "polarized_armor"};
    static final String[] ENERGY_POOL = {"energy_weapon_mastery", "target_analysis",
            "field_modulation", "helmsmanship", "combat_endurance", "gunnery_implants",
            "systems_expertise"};
    static final String[] MISSILE_POOL = {"missile_specialization", "target_analysis",
            "impact_mitigation", "helmsmanship", "combat_endurance", "damage_control",
            "field_modulation"};
    static final String[] MIXED_POOL = {"helmsmanship", "target_analysis", "impact_mitigation",
            "field_modulation", "combat_endurance", "damage_control", "systems_expertise"};

    /** Deterministic flavour per variant id (portrait stems proven by {@link FactionWaveTables}). */
    static final String[] FIRST_NAMES = {"Ace", "Vera", "Kato", "Ines", "Rook", "Sable",
            "Juno", "Orin"};
    static final String[] LAST_NAMES = {"Halden", "Moraine", "Voss", "Tessaro", "Brandt",
            "Kael", "Ysolde", "Marek"};
    static final String[] PORTRAIT_STEMS = {"league00", "league01", "league02", "league03",
            "hegemony01", "hegemony03", "corporate01", "corporate02"};

    /** {@link #pick(String, HullKind, WeaponMix, AlphaLoadout)} with an unweighed loadout. */
    public static Pick pick(String variantId, HullKind kind, WeaponMix mix) {
        return pick(variantId, kind, mix, AlphaLoadout.NONE);
    }

    /** The captain for {@code variantId} classified as {@code kind} ({@code mix} matters for
     *  HUMAN only, {@code loadout} for AUTOMATED only — null = {@link AlphaLoadout#NONE}). */
    public static Pick pick(String variantId, HullKind kind, WeaponMix mix, AlphaLoadout loadout) {
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case AUTOMATED:
                return new Pick("alpha", remnantAlphaCore(variantId,
                        loadout == null ? AlphaLoadout.NONE : loadout));
            case ZIGGURAT:
                return new Pick("ziggurat",
                        FactionWaveTables.zigguratBossCaptain().withVariantId(variantId));
            default:
                WeaponMix m = mix == null ? WeaponMix.MIXED : mix;
                return new Pick("human-" + m.name().toLowerCase(java.util.Locale.ROOT),
                        human(variantId, m));
        }
    }

    /** The RAW vanilla alpha-core captain keyed to {@code variantId} (level 7, not integrated). */
    public static OfficerRosterCodec.OfficerSpec alphaCore(String variantId) {
        return alphaSpec(variantId, ALPHA_LEVEL, alphaSkills());
    }

    /**
     * The remnant-FLEET alpha captain: {@link #alphaCore} after the vanilla integrate-and-adapt
     * step, rewritten from the functional spec: level
     * {@link #REMNANT_ALPHA_LEVEL} (one over the raw core) and ONE elite skill adapted to the
     * hull's {@code loadout} (weighed off the variant by {@code AdhocOfficerTable.weighLoadout}),
     * decided in this order:
     * <ol>
     * <li>PD-heavy: a weighed hull (weight &gt; 0) whose PD weight is strictly more than a quarter
     *     of its weight AND whose missile weight is at most a fifth of it gains elite
     *     {@link #SKILL_POINT_DEFENSE}, and the step ENDS there (no capital swap);</li>
     * <li>otherwise ONE weapon skill: a derelict hull lacking {@link #SKILL_BALLISTIC_MASTERY}
     *     gains it; else a core lacking {@link #SKILL_ENERGY_WEAPON_MASTERY} gains it; else
     *     {@link #SKILL_MISSILE_SPECIALIZATION} — always elite;</li>
     * <li>then a capital or station lacking {@link #SKILL_POLARIZED_ARMOR} drops
     *     {@link #SKILL_COMBAT_ENDURANCE} and gains elite Polarized Armor.</li>
     * </ol>
     * An unweighed loadout ({@link AlphaLoadout#NONE} or any weight 0 = a weaponless hull) never
     * takes the PD branch. The raw core holds neither mastery, so the Missile Specialization
     * fallback is unreachable from {@link #alphaSkills} (kept for the rule's completeness). Pure
     * and game-free: the contract rows pin every reachable branch and are the oracle for the
     * parked headless measure of the engine's own integrate-and-adapt helper.
     * {@code loadout} non-null (the 4-arg {@link #pick} maps null to {@link AlphaLoadout#NONE}).
     *
     */
    public static OfficerRosterCodec.OfficerSpec remnantAlphaCore(String variantId,
                                                                  AlphaLoadout loadout) {
        Objects.requireNonNull(loadout, "loadout");
        Map<String, Float> skills = alphaSkills();
        float weight = loadout.weight();
        boolean pdHeavy = weight > 0f
                && loadout.pdWeight() * 4f > weight
                && loadout.missileWeight() * 5f <= weight;
        if (pdHeavy) {
            skills.put(SKILL_POINT_DEFENSE, 2.0f);
        } else {
            String weaponSkill;
            if (loadout.derelict() && !skills.containsKey(SKILL_BALLISTIC_MASTERY)) {
                weaponSkill = SKILL_BALLISTIC_MASTERY;
            } else if (!skills.containsKey(SKILL_ENERGY_WEAPON_MASTERY)) {
                weaponSkill = SKILL_ENERGY_WEAPON_MASTERY;
            } else {
                weaponSkill = SKILL_MISSILE_SPECIALIZATION;
            }
            skills.put(weaponSkill, 2.0f);
            if (loadout.capitalOrStation() && !skills.containsKey(SKILL_POLARIZED_ARMOR)) {
                skills.remove(SKILL_COMBAT_ENDURANCE);
                skills.put(SKILL_POLARIZED_ARMOR, 2.0f);
            }
        }
        return alphaSpec(variantId, REMNANT_ALPHA_LEVEL, skills);
    }

    private static Map<String, Float> alphaSkills() {
        Map<String, Float> skills = new LinkedHashMap<>();
        for (String id : ALPHA_SKILLS) {
            skills.put(id, 2.0f);
        }
        return skills;
    }

    private static OfficerRosterCodec.OfficerSpec alphaSpec(String variantId, int level,
                                                            Map<String, Float> skills) {
        // Name = the commodity's display name "Alpha Core" (the line grammar's first-space split
        // yields first="Alpha" last="Core" on decode — minted that way so the round trip is exact).
        return new OfficerRosterCodec.OfficerSpec(variantId, "Alpha", "Core", "reckless",
                ALPHA_PORTRAIT, level, skills, ALPHA_CORE_ID);
    }

    /** The maxed human captain for {@code variantId} on the {@code mix} pool. */
    public static OfficerRosterCodec.OfficerSpec human(String variantId, WeaponMix mix) {
        String[] pool = poolFor(mix);
        Map<String, Float> skills = new LinkedHashMap<>();
        for (int i = 0; i < pool.length; i++) {
            skills.put(pool[i], i < HUMAN_ELITE ? 2.0f : 1.0f);
        }
        int h = Math.floorMod(variantId.hashCode(), 0x7fffffff);
        String first = FIRST_NAMES[h % FIRST_NAMES.length];
        String last = LAST_NAMES[(h / FIRST_NAMES.length) % LAST_NAMES.length];
        String portrait = "graphics/portraits/portrait_"
                + PORTRAIT_STEMS[(h / (FIRST_NAMES.length * LAST_NAMES.length))
                        % PORTRAIT_STEMS.length] + ".png";
        return new OfficerRosterCodec.OfficerSpec(variantId, first, last, HUMAN_PERSONALITY,
                portrait, HUMAN_LEVEL, skills);
    }

    static String[] poolFor(WeaponMix mix) {
        switch (mix) {
            case BALLISTIC: return BALLISTIC_POOL;
            case ENERGY: return ENERGY_POOL;
            case MISSILE: return MISSILE_POOL;
            default: return MIXED_POOL;
        }
    }

    /**
     * The mix from fitted-weapon counts by type: the strict majority type (more than every
     * other type AND at least half of all fitted weapons); ties, no majority, or no weapons =
     * {@link WeaponMix#MIXED}. Fighters / decorative / built-in system slots are the caller's
     * to exclude (the boot table counts BALLISTIC / ENERGY / MISSILE types only).
     */
    public static WeaponMix mixOf(int ballistic, int energy, int missile) {
        int total = ballistic + energy + missile;
        if (total <= 0) {
            return WeaponMix.MIXED;
        }
        int max = Math.max(ballistic, Math.max(energy, missile));
        if (max * 2 < total) {
            return WeaponMix.MIXED;
        }
        if (ballistic == max && energy < max && missile < max) {
            return WeaponMix.BALLISTIC;
        }
        if (energy == max && ballistic < max && missile < max) {
            return WeaponMix.ENERGY;
        }
        if (missile == max && ballistic < max && energy < max) {
            return WeaponMix.MISSILE;
        }
        return WeaponMix.MIXED;
    }
}
