package cc.starsector.server.platform;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * THE server person-mint recipe — the fold of the byte-identical {@code AllSkillsAttach.mintPerson}
 * ↔ {@code OfficerAttach.maybeAttach} inline mint (deferred-refactors "Person-mint recipe
 * duplication" row, ARMED, PAID at the DEFECT-1 landing). The
 * probe-proven sequence verbatim: {@code createPerson} → {@code setPersonality} →
 * {@code getStats().setSkipRefresh(true)} (an engine law: bare {@code setSkillLevel} NPEs
 * headless — the refresh opens with a campaign listener walk; skipRefresh is the engine's own
 * batch idiom and the SHIP apply chain reads {@code this.skills} directly) →
 * {@code setSkillLevel}×n → {@code setName} → {@code setLevel} → {@code setPortraitSprite}.
 * {@code setCaptain}/{@code setDefaultCommander} stay with the callers (the commander lane
 * attaches to a manager, not a member).
 *
 * <p>THE PORTRAIT IS STATS, not presentation, and this seam REQUIRES it (fail-closed on
 * null/empty — DEFECT-1 root cause): a fresh Person seeds the settings default
 * portrait, and {@code Person.isDefault()} is portrait-ONLY, so a
 * portrait-less mint reads "no officer" to {@code SupportDoctrine.isNoOfficer}
 * and the server ship stacks SD's nested Helmsmanship on top of the captain's elite
 * one — the 184.8-vs-172.85 top-speed desync.
 * The same predicate class gates SD Level2 (DP cost),
 * Level3 (CP regen, inverted, via {@code originalCaptain} — same Person object, heals live)
 * and the officer branches of CarrierGroup/CoordinatedManeuvers/WolfpackTactics/PhaseCorps/
 * CyberneticAugmentation/FighterUplink. Requiring the portrait HERE
 * makes a portrait-less server mint structurally impossible.
 *
 * <p>Resolution is off the PUBLIC interfaces, NEVER {@code settings.getClass()} (the probe
 * lesson: the impl is the inaccessible anonymous {@code StarfarerSettings$1}).
 */
public final class PersonMint {

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String CHAR_STATS_API =
            "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String FULL_NAME_CLASS = "com.fs.starfarer.api.characters.FullName";

    private static Handles handles;

    private PersonMint() {
    }

    /**
     * Mint a fresh Person: {@code personality} + {@code skills} (id → level) + name + level +
     * the REQUIRED non-default {@code portraitPath} (see class doc — the isDefault stats
     * marker; headless-safe, the path is a string on the Person and nothing loads it without
     * render). Throws on any failure — callers wrap with their own lane context (a
     * half-minted captain must never attach).
     */
    public static synchronized Object mint(String personality, Map<String, Float> skills,
            String firstName, String lastName, int level, String portraitPath) throws Exception {
        return mint(personality, skills, firstName, lastName, level, portraitPath, "");
    }

    /**
     * The AI-core twin — {@code aiCoreIdOrEmpty} non-empty ⇒
     * {@code PersonAPI.setAICoreId} after the stat lines (the vanilla
     * {@code AICoreOfficerPluginImpl.createPerson} order: id first, stats after; the id is
     * NOT a stat, so ordering is free — it rides last here so a throw before it leaves no
     * half-core). Empty = the human mint above, byte-identical.
     */
    public static synchronized Object mint(String personality, Map<String, Float> skills,
            String firstName, String lastName, int level, String portraitPath,
            String aiCoreIdOrEmpty) throws Exception {
        if (portraitPath == null || portraitPath.isEmpty()) {
            throw new IllegalStateException("person mint without a portrait — the portrait is"
                    + " the Person.isDefault() stats marker, never optional");
        }
        Handles h = handlesOrResolve();
        Object person = h.createPerson.invoke(h.settings);
        if (person == null) {
            throw new IllegalStateException("SettingsAPI.createPerson() returned null");
        }
        h.setPersonality.invoke(person, personality);
        Object stats = h.getStats.invoke(person);
        // ENGINE LAW: batch-set with the engine's own skipRefresh idiom, left ON.
        h.setSkipRefresh.invoke(stats, Boolean.TRUE);
        for (Map.Entry<String, Float> e : skills.entrySet()) {
            h.setSkillLevel.invoke(stats, e.getKey(), e.getValue());
        }
        Object fullName = h.fullNameCtor.newInstance(firstName, lastName, h.genderAny);
        h.setName.invoke(person, fullName);
        h.setLevel.invoke(stats, Integer.valueOf(level));
        h.setPortraitSprite.invoke(person, portraitPath);
        if (aiCoreIdOrEmpty != null && !aiCoreIdOrEmpty.isEmpty()) {
            h.setAICoreId.invoke(person, aiCoreIdOrEmpty);
        }
        return person;
    }

    private static Handles handlesOrResolve() throws Exception {
        if (handles != null) {
            return handles;
        }
        ClassLoader loader = PersonMint.class.getClassLoader();
        Handles h = new Handles();
        h.settings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings").invoke(null);
        if (h.settings == null) {
            throw new IllegalStateException("Global.getSettings() null at person mint — a"
                    + " game-free context cannot mint a captain (fail-closed; the callers'"
                    + " tables/knobs are empty or OFF there, so reaching this is a routing bug)");
        }
        Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
        Class<?> personApi = Class.forName(PERSON_API, false, loader);
        Class<?> statsApi = Class.forName(CHAR_STATS_API, false, loader);
        Class<?> fullName = Class.forName(FULL_NAME_CLASS, false, loader);
        Class<?> gender = Class.forName(FULL_NAME_CLASS + "$Gender", false, loader);
        h.createPerson = settingsApi.getMethod("createPerson");
        h.setPersonality = personApi.getMethod("setPersonality", String.class);
        h.getStats = personApi.getMethod("getStats");
        h.setSkipRefresh = statsApi.getMethod("setSkipRefresh", boolean.class);
        h.setSkillLevel = statsApi.getMethod("setSkillLevel", String.class, float.class);
        h.setLevel = statsApi.getMethod("setLevel", int.class);
        h.setName = personApi.getMethod("setName", fullName);
        h.setPortraitSprite = personApi.getMethod("setPortraitSprite", String.class);
        h.setAICoreId = personApi.getMethod("setAICoreId", String.class); //
        h.fullNameCtor = fullName.getConstructor(String.class, String.class, gender);
        h.genderAny = gender.getField("ANY").get(null);
        handles = h;
        return handles;
    }

    private static final class Handles {
        Object settings;
        Method createPerson;
        Method setPersonality;
        Method getStats;
        Method setSkipRefresh;
        Method setSkillLevel;
        Method setLevel;
        Method setName;
        Method setPortraitSprite;
        Method setAICoreId;
        java.lang.reflect.Constructor<?> fullNameCtor;
        Object genderAny;
    }
}
