package cc.starsector.server.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Headless refresh-equivalent for a minted Person's CHARACTER stats (W15 v1 editor D1).
 *
 *
 * <p>The engine's {@code MutableCharacterStats.refreshCharacterStatsEffects} is the only vanilla
 * path that turns a skill sheet into character-stat bonuses ({@code getMaxVentsBonus},
 * {@code getMaxCapacitorsBonus}, officer count, …), and its FIRST line is a campaign listener
 * walk that NPEs headless on {@code Global.getSector()} (ENGINE LAW) —
 * so every server mint runs under {@code setSkipRefresh(true)} and its character stats stay
 * BARE. The vanilla refit reads max vents/caps off the PLAYER's REFRESHED character stats
 * (in the refit UI), the client editor oracle refreshes its detached union
 * person in-game, so the server's bare union commander disagreed with both (30 vs 35 for a
 * cruiser).
 *
 * <p>This is the engine's own loop MINUS the campaign calls (in
 * {@code CharacterStats}): for every skill on
 * the sheet → {@code SettingsAPI.getSkillSpec(id).getEffectsAPI()} → each effect whose
 * {@code getType()} is {@code CHARACTER_STATS} and whose governing skill's level meets
 * {@code getRequiredSkillLevel()} → {@code getAsStatsEffect().apply(stats, id + "_stats_" + i,
 * level)}, the effect index counting EVERY effect of the skill (the engine's id law, so a later
 * real refresh unapplies the same ids). No unapply pass: the stats are bare by construction
 * (skipRefresh from the mint), and this runs once per person.
 *
 * <p>Every vanilla {@code CharacterStatsSkillEffect.apply} in this engine build touches only the
 * stats object it is handed (the 18 implementations were read; {@code Global.getSector()} appears
 * only in description text / {@code isInCampaign()} guards). FAIL-CLOSED regardless: a throwing
 * effect, a missing spec or a sheet that applied NOTHING propagates — a half-refreshed commander
 * is a refit-rule desync by construction, and the caller's knob is ON.
 */
public final class PersonStatsRefresh {

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String CHAR_STATS_API =
            "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String SKILL_SPEC_API = "com.fs.starfarer.api.characters.SkillSpecAPI";
    private static final String EFFECT_SPEC_API = SKILL_SPEC_API + "$SkillEffectSpecAPI";
    private static final String STATS_EFFECT_API =
            "com.fs.starfarer.api.characters.CharacterStatsSkillEffect";
    /** {@code SkillEffectType.CHARACTER_STATS} — compared by enum name, the enumerator's idiom. */
    private static final String TYPE_CHARACTER_STATS = "CHARACTER_STATS";

    private static Handles handles;

    private PersonStatsRefresh() {
    }

    /** What one refresh did — logged by the caller, asserted by the probe. */
    public static final class Result {
        public final int skills;
        public final int effectsSeen;
        public final int applied;

        Result(int skills, int effectsSeen, int applied) {
            this.skills = skills;
            this.effectsSeen = effectsSeen;
            this.applied = applied;
        }

        @Override
        public String toString() {
            return "skills=" + skills + " effects_seen=" + effectsSeen + " applied=" + applied;
        }
    }

    /**
     * Apply the CHARACTER_STATS effects of {@code skills} (id → level, the sheet the person was
     * minted with) to {@code person}'s stats. Throws on ANY failure (see class doc); the caller
     * treats the person as unusable then.
     */
    public static synchronized Result applyCharacterStatsEffects(Object person,
            Map<String, Float> skills) throws Exception {
        if (person == null) {
            throw new IllegalStateException("character-stats refresh: null person");
        }
        if (skills == null || skills.isEmpty()) {
            throw new IllegalStateException("character-stats refresh: empty skill sheet");
        }
        Handles h = handlesOrResolve();
        Object stats = h.getStats.invoke(person);
        if (stats == null) {
            throw new IllegalStateException("character-stats refresh: PersonAPI.getStats() null");
        }
        int effectsSeen = 0;
        int applied = 0;
        for (Map.Entry<String, Float> skill : skills.entrySet()) {
            String skillId = skill.getKey();
            Object spec;
            try {
                spec = h.getSkillSpec.invoke(h.settings, skillId);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("character-stats refresh: getSkillSpec('"
                        + skillId + "') threw " + e.getCause(), e.getCause());
            }
            if (spec == null) {
                throw new IllegalStateException("character-stats refresh: no SkillSpec for '"
                        + skillId + "'");
            }
            List<?> effects = (List<?>) h.getEffectsAPI.invoke(spec);
            if (effects == null) {
                continue;
            }
            int index = 0;
            for (Object effect : effects) {
                effectsSeen++;
                String id = skillId + "_stats_" + index;
                index++;
                Object type = h.effectGetType.invoke(effect);
                if (type == null || !TYPE_CHARACTER_STATS.equals(((Enum<?>) type).name())) {
                    continue;
                }
                String governing = (String) h.effectGetGoverningSkill.invoke(effect);
                if (governing == null) {
                    governing = skillId;
                }
                float level = ((Number) h.getSkillLevel.invoke(stats, governing)).floatValue();
                float required = ((Number) h.effectGetRequiredSkillLevel.invoke(effect))
                        .floatValue();
                if (level < required) {
                    continue;
                }
                Object statsEffect = h.effectGetAsStatsEffect.invoke(effect);
                if (statsEffect == null) {
                    throw new IllegalStateException("character-stats refresh: " + id
                            + " is CHARACTER_STATS but getAsStatsEffect() is null");
                }
                try {
                    h.statsEffectApply.invoke(statsEffect, stats, id, Float.valueOf(level));
                } catch (InvocationTargetException e) {
                    throw new IllegalStateException("character-stats refresh: " + id + " ("
                            + statsEffect.getClass().getName() + ").apply threw " + e.getCause(),
                            e.getCause());
                }
                applied++;
            }
        }
        if (applied == 0) {
            throw new IllegalStateException("character-stats refresh applied NOTHING over "
                    + skills.size() + " skills / " + effectsSeen + " effects — the sheet or the"
                    + " walk is wrong (fail-closed)");
        }
        return new Result(skills.size(), effectsSeen, applied);
    }

    private static Handles handlesOrResolve() throws Exception {
        if (handles != null) {
            return handles;
        }
        ClassLoader loader = PersonStatsRefresh.class.getClassLoader();
        Handles h = new Handles();
        h.settings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings").invoke(null);
        if (h.settings == null) {
            throw new IllegalStateException("Global.getSettings() null at character-stats refresh"
                    + " — a game-free context cannot refresh a person (fail-closed)");
        }
        // PUBLIC interfaces only (the probe lesson: the impls are inaccessible).
        Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
        Class<?> personApi = Class.forName(PERSON_API, false, loader);
        Class<?> statsApi = Class.forName(CHAR_STATS_API, false, loader);
        Class<?> skillSpecApi = Class.forName(SKILL_SPEC_API, false, loader);
        Class<?> effectSpecApi = Class.forName(EFFECT_SPEC_API, false, loader);
        Class<?> statsEffectApi = Class.forName(STATS_EFFECT_API, false, loader);
        h.getSkillSpec = settingsApi.getMethod("getSkillSpec", String.class);
        h.getStats = personApi.getMethod("getStats");
        h.getSkillLevel = statsApi.getMethod("getSkillLevel", String.class);
        h.getEffectsAPI = skillSpecApi.getMethod("getEffectsAPI");
        h.effectGetType = effectSpecApi.getMethod("getType");
        h.effectGetGoverningSkill = effectSpecApi.getMethod("getGoverningSkill");
        h.effectGetRequiredSkillLevel = effectSpecApi.getMethod("getRequiredSkillLevel");
        h.effectGetAsStatsEffect = effectSpecApi.getMethod("getAsStatsEffect");
        h.statsEffectApply = statsEffectApi.getMethod("apply", statsApi, String.class, float.class);
        handles = h;
        return handles;
    }

    private static final class Handles {
        Object settings;
        Method getSkillSpec;
        Method getStats;
        Method getSkillLevel;
        Method getEffectsAPI;
        Method effectGetType;
        Method effectGetGoverningSkill;
        Method effectGetRequiredSkillLevel;
        Method effectGetAsStatsEffect;
        Method statsEffectApply;
    }
}
