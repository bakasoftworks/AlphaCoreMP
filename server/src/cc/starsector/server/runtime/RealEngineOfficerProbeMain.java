package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Officer-skills headless PROBE (risks i–iii): answers, on the live headless
 * engine, the three unknowns gating the officer-skills build — (i) is the skill SpecStore
 * populated by our boot ({@code RealEngineContentInit} drives {@code ResourceLoaderState.init} →
 * the SpecStore load, which runs the skill loader unconditionally;
 * this reads it back via {@code Global.getSettings().getSkillSpec}); (ii) person minting headless
 * ({@code SettingsAPI.createPerson()} = {@code new Person()} —
 * the campaign-only {@code Global.getFactory()} lane, seeded by the campaign engine, is NOT needed);
 * (iii) does a REAL captain on a spawned member move ship stats through the engine's own
 * {@code instantiateForCombat → Ship ctor → CharacterStats.applyPersonalToShip} chain
 * without flipping CR/controls branches.
 *
 * <p><b>Differential design.</b> Two identical members spawn mid-combat through the proven
 * reserve-deploy lane ({@code RealEngineMidDeployProbeMain} shape): one carries a minted captain
 * with {@code gunnery_implants=1} + {@code ballistic_mastery=2} (ELITE), one is the no-captain
 * control. Expected deltas: autofireAimAccuracy 1.0 vs 0.0 (GunneryImplants +1.0
 * flat), ballisticProjectileSpeedMult ≥1.30 vs 1.0 (BallisticMastery elite +33%); CR must be
 * EQUAL both ships (neither skill touches CR — a divergence means a captain-triggered CR branch,
 * the risk-(iii) capture). Headless skill-set REQUIRES the engine's own {@code setSkipRefresh(true)}
 * batch idiom first — see the inline comment at the call site (first-run boundary capture; the
 * sequence any product attach must copy). All API calls resolve Methods on the PUBLIC API interfaces (the
 * SettingsAPI impl is an anonymous package-private class — concrete-class getMethod would fail
 * access), engine identities on the pinned public classes.
 *
 * <p>A MEASUREMENT main (never wired into the product serve loop): fail-closed, one process,
 * exits when done. Exit codes: 0 = all three risks retired (verdict=OFFICER_HEADLESS_PROVEN);
 * 2 = a spawn/attach call threw — the exact boundary the probe exists to capture (stack printed);
 * 1 = infrastructure failure or a differential mismatch. A wrapper script runs it.
 *
 */
public final class RealEngineOfficerProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineMidDeployProbeMain. ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String HULL_VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String VARIANT_REGISTRY_CLASS = "com.fs.starfarer.loading.B";
    private static final String VARIANT_REGISTRY_METHOD = "o00000";
    private static final String FLEET_MEMBER_CLASS = "com.fs.starfarer.campaign.fleet.FleetMember";
    private static final String FLEET_MEMBER_TYPE_API = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";
    // ---- API-side identities (public API, stable). ----
    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String SKILL_SPEC_API = "com.fs.starfarer.api.characters.SkillSpecAPI";
    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String CHAR_STATS_API = "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String SHIP_STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String MUTABLE_STAT_CLASS = "com.fs.starfarer.api.combat.MutableStat";

    /** The owner-fleet skill set the build will attach + the fleetwide levers. */
    private static final String[] PROBED_SKILL_IDS = {
        "gunnery_implants", "ballistic_mastery", "point_defense", "combat_endurance",
        "crew_training", "helmsmanship", "target_analysis",
    };

    private static final String P = "officer-probe";

    private RealEngineOfficerProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int preFrames = 120;      // 2 s: combat live + settled before the differential spawn
        int settleFrames = 120;   // 2 s: captain aboard, engine stepping — stability + CR drift read
        float fixedDt = SimTickRate.fixedDt();
        String variantId = "grendel_Hypervelocity";  // the fielded ballistic hull the build targets
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--pre-frames": preFrames = Integer.parseInt(args[++i]); break;
                case "--settle-frames": settleFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--variant": variantId = args[++i]; break;
                default: break;
            }
        }
        System.out.println(P + "-begin pre_frames=" + preFrames + " settle_frames=" + settleFrames
                + " fixed_dt=" + fixedDt + " variant=" + variantId);

        // ---- Boot the SAME product lane the serve loop uses (L3 substrate + content + t=0 roster). ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEngineOfficerProbeMain.class.getClassLoader();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
        Object engine = engineClass.getMethod("getInstance").invoke(null);
        if (engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after create()");
        }

        // ---- Part A: skill SpecStore census (risk i). ----
        Class<?> settingsApiClass = Class.forName(SETTINGS_API, false, loader);
        Class<?> skillSpecApiClass = Class.forName(SKILL_SPEC_API, false, loader);
        Object settings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings").invoke(null);
        if (settings == null) {
            throw new IllegalStateException("Global.getSettings() null after content init");
        }
        Method getSkillSpec = settingsApiClass.getMethod("getSkillSpec", String.class);
        Method getSortedSkillIds = settingsApiClass.getMethod("getSortedSkillIds");
        Method specGetName = skillSpecApiClass.getMethod("getName");
        Method specIsCombatOfficer = skillSpecApiClass.getMethod("isCombatOfficerSkill");
        int totalSkillIds = ((List<?>) getSortedSkillIds.invoke(settings)).size();
        int specsFound = 0;
        StringBuilder specList = new StringBuilder();
        for (String id : PROBED_SKILL_IDS) {
            Object spec = null;
            try {
                spec = getSkillSpec.invoke(settings, id);
            } catch (InvocationTargetException e) {
                // getSkillSpec on a missing id may throw instead of returning null — that IS the read.
            }
            boolean found = spec != null;
            if (found) {
                specsFound++;
            }
            specList.append(' ').append(id).append('=').append(found
                    ? specGetName.invoke(spec) + "/officer:" + specIsCombatOfficer.invoke(spec)
                    : "MISSING");
        }
        boolean specsOk = specsFound == PROBED_SKILL_IDS.length;
        System.out.println(P + " specstore total_skill_ids=" + totalSkillIds
                + " probed_found=" + specsFound + "/" + PROBED_SKILL_IDS.length + specList);

        // ---- Part B: person minting + skill assignment (risk ii). ----
        Class<?> personApiClass = Class.forName(PERSON_API, false, loader);
        Class<?> charStatsApiClass = Class.forName(CHAR_STATS_API, false, loader);
        Method createPerson = settingsApiClass.getMethod("createPerson");
        Object person;
        try {
            person = createPerson.invoke(settings);
        } catch (InvocationTargetException e) {
            bail("CREATE_PERSON_THREW", e);
            return;
        }
        if (person == null) {
            throw new IllegalStateException("SettingsAPI.createPerson() returned null");
        }
        personApiClass.getMethod("setPersonality", String.class).invoke(person, "steady");
        Object personality = personApiClass.getMethod("getPersonalityAPI").invoke(person);
        Object charStats = personApiClass.getMethod("getStats").invoke(person);
        Method setSkillLevel = charStatsApiClass.getMethod("setSkillLevel", String.class, float.class);
        Method getSkillLevel = charStatsApiClass.getMethod("getSkillLevel", String.class);
        // The engine's OWN batch-set idiom (the CharacterStats ctor): skipRefresh
        // suppresses refreshCharacterStatsEffects, whose first act is a campaign listener
        // notification (ListenerUtil → Global.getSector(), null headless — first-run boundary
        // capture). The refresh applies only CHARACTER_STATS/FLEET effect types; the SHIP
        // apply chain (applyPersonalToShip → getShipEffects) reads this.skills directly
        // and is campaign-free, so headless leaves skipRefresh ON for good.
        charStatsApiClass.getMethod("setSkipRefresh", boolean.class).invoke(charStats, true);
        try {
            setSkillLevel.invoke(charStats, "gunnery_implants", 1.0f);
            setSkillLevel.invoke(charStats, "ballistic_mastery", 2.0f);
        } catch (InvocationTargetException e) {
            bail("SET_SKILL_THREW", e);
            return;
        }
        float rbGi = ((Number) getSkillLevel.invoke(charStats, "gunnery_implants")).floatValue();
        float rbBm = ((Number) getSkillLevel.invoke(charStats, "ballistic_mastery")).floatValue();
        // Product-faithful officer shape (run-2 finding): a skills-only captain still reads
        // Person.isDefault()=true (empty name + level<=1 is the engine's default-detection
        // shape) — owner officers carry names + levels, so the attach sets both.
        Class<?> fullNameClass = Class.forName("com.fs.starfarer.api.characters.FullName", false, loader);
        Class<?> genderClass = Class.forName("com.fs.starfarer.api.characters.FullName$Gender", false, loader);
        Object gender = genderClass.getField("ANY").get(null);
        Object fullName = fullNameClass.getConstructor(String.class, String.class, genderClass)
                .newInstance("CC", "Officer", gender);
        personApiClass.getMethod("setName", fullNameClass).invoke(person, fullName);
        findOneArg(charStatsApiClass, "setLevel").invoke(charStats, 5);
        // Person.isDefault() is ONLY "portrait == default_portrait" (run-3 finding) — the
        // portrait rider is thus also the non-default marker. A string set, no texture load.
        // Zero-padded name: portrait_mercenary1.png does not exist in 0.98a.
        personApiClass.getMethod("setPortraitSprite", String.class)
                .invoke(person, "graphics/portraits/portrait_mercenary01.png");
        boolean personOk = personality != null && rbGi == 1.0f && rbBm == 2.0f;
        System.out.println(P + " person minted class=" + person.getClass().getName()
                + " personality_nonNull=" + (personality != null)
                + " skill_rb_gunnery=" + rbGi + " skill_rb_ballistic=" + rbBm
                + " is_default_after_name_level=" + personApiClass.getMethod("isDefault").invoke(person));

        // ---- Pre-window: settle, then strip roster AI so the probe window is kill-free. ----
        Method getObjects = engineClass.getMethod("getObjects");
        Method repoGetList = getObjects.invoke(engine).getClass().getMethod("getList", Class.class);
        Method isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
        Method getFleetManager = engineClass.getMethod("getFleetManager", int.class);
        Method shipGetShipAI = shipClass.getMethod("getShipAI");
        Method shipSetShipAI = findOneArg(shipClass, "setShipAI");
        long tick = 0;
        for (int f = 0; f < preFrames; f++) {
            step.step(fixedDt, tick++);
        }
        List<?> rosterShips = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        int rosterStripped = 0;
        for (Object s : new ArrayList<Object>(rosterShips)) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
                rosterStripped++;
            }
        }
        Object manager1 = getFleetManager.invoke(engine, 1);
        if (manager1 == null) {
            throw new IllegalStateException("getFleetManager(1) null mid-combat");
        }
        System.out.println(P + " pre_spawn tick=" + tick + " roster_ships=" + rosterShips.size()
                + " roster_ai_stripped=" + rosterStripped);

        // ---- Part C: differential spawn — officer member vs control member (risk iii). ----
        Object variant = Class.forName(VARIANT_REGISTRY_CLASS, true, loader)
                .getMethod(VARIANT_REGISTRY_METHOD, String.class).invoke(null, variantId);
        Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
        if (variant == null || !hullVariantClass.isInstance(variant)) {
            throw new IllegalStateException("variant '" + variantId + "' did not resolve — pass --variant");
        }
        Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
        Class<?> memberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
        Class<?> fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
        Method memberSetCaptain = fleetMemberApiClass.getMethod("setCaptain", personApiClass);
        Method memberGetCaptain = fleetMemberApiClass.getMethod("getCaptain");
        Method captainIsDefault = personApiClass.getMethod("isDefault");

        Object officerMember = mintMember(fleetMemberClass, memberTypeClass, hullVariantClass,
                variant, "CC Officer Probe");
        try {
            memberSetCaptain.invoke(officerMember, person);
        } catch (InvocationTargetException e) {
            bail("SET_CAPTAIN_THREW", e);
            return;
        }
        Object controlMember = mintMember(fleetMemberClass, memberTypeClass, hullVariantClass,
                variant, "CC Control Probe");

        Method spawnFleetMember = manager1.getClass().getMethod("spawnFleetMember",
                fleetMemberApiClass, Class.forName(LWJGL_VECTOR2F_CLASS, false, loader),
                float.class, float.class);
        Object officerShip;
        Object controlShip;
        try {
            officerShip = spawnFleetMember.invoke(manager1, officerMember, null, 0.0f, 4.0f);
            controlShip = spawnFleetMember.invoke(manager1, controlMember, null, 0.0f, 4.0f);
        } catch (InvocationTargetException e) {
            bail("SPAWN_THREW", e);
            return;
        }
        step.step(fixedDt, tick++);  // engine's own executeAdds drain
        boolean officerAlive = (Boolean) isShipAlive.invoke(engine, officerShip);
        boolean controlAlive = (Boolean) isShipAlive.invoke(engine, controlShip);

        // ---- The differential reads: ship stats moved by the captain's skills, CR untouched. ----
        Class<?> shipStatsApiClass = Class.forName(SHIP_STATS_API, false, loader);
        Class<?> mutableStatClass = Class.forName(MUTABLE_STAT_CLASS, false, loader);
        Method getMutableStats = shipClass.getMethod("getMutableStats");
        Method getAutofireAim = shipStatsApiClass.getMethod("getAutofireAimAccuracy");
        Method getBallisticSpeed = shipStatsApiClass.getMethod("getBallisticProjectileSpeedMult");
        Method getModifiedValue = mutableStatClass.getMethod("getModifiedValue");
        Method getCurrentCR = shipClass.getMethod("getCurrentCR");
        Method controlsLocked = shipClass.getMethod("controlsLocked");

        float offAim = statOf(officerShip, getMutableStats, getAutofireAim, getModifiedValue);
        float ctlAim = statOf(controlShip, getMutableStats, getAutofireAim, getModifiedValue);
        float offBps = statOf(officerShip, getMutableStats, getBallisticSpeed, getModifiedValue);
        float ctlBps = statOf(controlShip, getMutableStats, getBallisticSpeed, getModifiedValue);
        float offCr = ((Number) getCurrentCR.invoke(officerShip)).floatValue();
        float ctlCr = ((Number) getCurrentCR.invoke(controlShip)).floatValue();
        boolean offLocked = (Boolean) controlsLocked.invoke(officerShip);
        boolean ctlLocked = (Boolean) controlsLocked.invoke(controlShip);
        boolean offCapDefault = (Boolean) captainIsDefault.invoke(memberGetCaptain.invoke(officerMember));
        boolean ctlCapDefault = (Boolean) captainIsDefault.invoke(memberGetCaptain.invoke(controlMember));
        System.out.println(P + " drained tick=" + tick
                + " officer_alive=" + officerAlive + " control_alive=" + controlAlive
                + " officer_captain_default=" + offCapDefault + " control_captain_default=" + ctlCapDefault
                + " officer_autofire=" + f2(offAim) + " control_autofire=" + f2(ctlAim)
                + " officer_ballistic_speed=" + f2(offBps) + " control_ballistic_speed=" + f2(ctlBps)
                + " officer_cr=" + f2(offCr) + " control_cr=" + f2(ctlCr)
                + " officer_locked=" + offLocked + " control_locked=" + ctlLocked);

        // ---- Settle window: captain aboard, engine stepping; AI stripped so nothing dies. ----
        for (Object s : new Object[]{officerShip, controlShip}) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
            }
        }
        for (int f = 0; f < settleFrames; f++) {
            step.step(fixedDt, tick++);
        }
        boolean offAliveEnd = (Boolean) isShipAlive.invoke(engine, officerShip);
        boolean ctlAliveEnd = (Boolean) isShipAlive.invoke(engine, controlShip);
        float offCrEnd = ((Number) getCurrentCR.invoke(officerShip)).floatValue();
        float ctlCrEnd = ((Number) getCurrentCR.invoke(controlShip)).floatValue();
        float offAimEnd = statOf(officerShip, getMutableStats, getAutofireAim, getModifiedValue);
        float ctlAimEnd = statOf(controlShip, getMutableStats, getAutofireAim, getModifiedValue);

        // DIFFERENTIAL predicates (run-2 lesson): the variant's own hullmods move the baseline
        // (armoredweapons grants +0.25 flat autofire accuracy on BOTH ships) — only the
        // officer-minus-control DELTA isolates the captain's skills.
        float aimDelta = offAim - ctlAim;
        float aimDeltaEnd = offAimEnd - ctlAimEnd;
        float bpsDelta = offBps - ctlBps;
        boolean deltaAim = aimDelta >= 0.99f && aimDelta <= 1.01f
                && aimDeltaEnd >= 0.99f && aimDeltaEnd <= 1.01f;
        boolean deltaBps = bpsDelta >= 0.30f && bpsDelta <= 0.36f;
        boolean crEqual = Math.abs(offCr - ctlCr) <= 0.001f && Math.abs(offCrEnd - ctlCrEnd) <= 0.001f;
        boolean pass = specsOk && personOk && officerAlive && controlAlive && offAliveEnd && ctlAliveEnd
                && !offLocked && !ctlLocked && deltaAim && deltaBps && crEqual && !offCapDefault;
        System.out.println(P + "-end verdict=" + (pass ? "OFFICER_HEADLESS_PROVEN" : "FAILED")
                + " specs_ok=" + specsOk
                + " person_ok=" + personOk
                + " officer_alive_end=" + offAliveEnd
                + " control_alive_end=" + ctlAliveEnd
                + " delta_autofire=" + deltaAim
                + " delta_ballistic_speed=" + deltaBps
                + " cr_equal=" + crEqual
                + " captain_nondefault=" + !offCapDefault
                + " aim_delta=" + f2(aimDelta) + " bps_delta=" + f2(bpsDelta)
                + " officer_autofire_end=" + f2(offAimEnd)
                + " officer_cr_end=" + f2(offCrEnd)
                + " control_cr_end=" + f2(ctlCrEnd)
                + " total_skill_ids=" + totalSkillIds);
        System.exit(pass ? 0 : 1);
    }

    private static Object mintMember(Class<?> fleetMemberClass, Class<?> memberTypeClass,
            Class<?> hullVariantClass, Object variant, String name) throws Exception {
        Object member = fleetMemberClass
                .getConstructor(int.class, hullVariantClass, memberTypeClass)
                .newInstance(1, variant, memberTypeClass.getField("SHIP").get(null));
        fleetMemberClass.getMethod("setShipName", String.class).invoke(member, name);
        // Faithful crew fix: full complement BEFORE spawn so nothing controls-locks.
        Object crewComp = fleetMemberClass.getMethod("getCrewComposition").invoke(member);
        float minCrew = ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(member)).floatValue();
        crewComp.getClass().getMethod("setCrew", float.class).invoke(crewComp, Math.max(minCrew, 1.0f));
        return member;
    }

    private static float statOf(Object ship, Method getMutableStats, Method statGetter,
            Method getModifiedValue) throws Exception {
        return ((Number) getModifiedValue.invoke(statGetter.invoke(getMutableStats.invoke(ship))))
                .floatValue();
    }

    /** Boundary capture: the throwing edge is the finding, not an infra failure. */
    private static void bail(String verdict, InvocationTargetException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        System.out.println(P + "-end verdict=" + verdict
                + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
        cause.printStackTrace(System.out);
        System.exit(2);
    }

    private static Method findOneArg(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("no one-arg " + name + " on " + cls.getName());
    }

    private static String f2(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }
}
