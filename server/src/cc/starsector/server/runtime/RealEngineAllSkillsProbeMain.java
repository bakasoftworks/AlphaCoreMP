package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.platform.AllSkillsAttach;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * W4 all-skills COMMANDER headless PROBE: with the knob
 * {@code cc.server.officerSkillsAll=true}, boot the SAME product lane the serve loop uses and
 * prove on the live engine that the side-0 manager-level default commander (installed at the
 * addFleet seam) actually moves deployed-ship stats through
 * the engine's own fleetwide chain ({@code FleetMember.instantiateForCombat} → ship factory →
 * {@code CharacterStats.applyFleetwideToStats}) — and stays side-scoped (guard (d)).
 *
 * <p><b>Witness (source-precise, captain/hullmod-immune).</b> {@code crew_training} is
 * ALL_SHIPS_IN_FLEET-typed (vanilla .skill data, single requiredSkillLevel-1 group) and its
 * Level1 applies a FLAT mod on {@code MutableShipStatsAPI.getMaxCombatReadiness()} with desc
 * {@code "Crew Training skill"} (a constant in the skill class). The bonus is threshold-scaled by fleet combat
 * OP — but {@code computeAndCacheThresholdBonus} RETURNS THE FULL base bonus when fleetData is
 * null ({@code ifnonnull/fload} head), and every member in our lanes is
 * fleetData-less, so a working commander yields the full flat delta (+0.15) symmetrically on
 * both ends. Curated side-1 officers carry {@code combat_endurance} (also a max-CR mover), so a
 * naive modified-value read WOULD be contaminated — the probe therefore matches the stat's flat
 * mods by the crew_training DESC, which no captain skill or hullmod produces.
 *
 * <p>Asserts: (1) side-0 manager readback carries a non-null default commander whose stats hold
 * {@code crew_training=1}; (2) every non-fighter t=0 roster ship on side 0 carries a positive
 * max-CR flat-mod SUM and every side-1 roster ship carries none — the sum, not the crew_training
 * mod, because the legacy roster's Mudskippers are CIVILIAN-grade hulls and crew_training's own
 * Level1 opens with an {@code isCivilian(stats)} bail (scope {@code ALL_COMBAT_SHIPS}; first-run
 * finding) — commander skills that don't exclude civilians still land (+0.25 measured), so
 * the sum witnesses the commander on civilian hulls (legacy-boot-scoped: descriptor side-1
 * Remnants carry curated combat_endurance captains, so a descriptor-boot census would need the
 * desc-matched read); (3) a bare fleetData-less COMBAT-hull member spawned mid-combat on side 0
 * carries the crew_training mod itself (+0.15) while the identical side-1 spawn stays clean and
 * BOTH keep default captains (the buff is the commander lever, not a captain leak) — the max-CR
 * delta must be at least the crew_training mod (measured 0.45: crew_training plus two more
 * legitimate fleetwide max-CR movers at full null-fleetData bonus); (4) the mod survives a
 * settle window (no later unapply). Fighters are exempt by engine law (ALL_SHIPS apply skips
 * FIGHTER hulls, {@code applyFleetwideToStats}).
 *
 * <p><b>Part C (the (d) sysexp verify + fix-law writer audit):</b>
 * a THIRD member takes the product {@code AllSkillsAttach.attachAllSkillsCaptain} lane
 * (captain BEFORE spawn, the serve-loop shape) and its skill-keyed stat writers are asserted
 * against the vanilla campaign capture: {@code systems_expertise_ship_4}=0.9 on
 * shield+hull DmgTaken (the systems-expertise gap — pre-fix ours NEVER applied it),
 * {@code field_modulation_ship_0}=0.85 / {@code _ship_3}=+0.2 hardflux,
 * {@code damage_control_ship_1}=0.75, {@code cybernetic_augmentation_ships_2}=0.86 (0.86 iff
 * its script counts the CAPTAIN's 14 elite — predicted earlier, this row IS the verify),
 * {@code flux_regulation_ships_2}=5 pct (commander lane, tier-1 post-fix; pre-fix 10), and NO
 * {@code shield_modulation} key on ANY lane (the prime illegit writer).
 *
 * <p>A MEASUREMENT main (never wired into the product serve loop): fail-closed, one process,
 * exits when done. Exit codes: 0 = ALLSKILLS_COMMANDER_PROVEN; 2 = an install/spawn call threw —
 * the boundary the probe exists to capture (stack printed); 1 = infrastructure failure or an
 * assert mismatch.
 */
public final class RealEngineAllSkillsProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineOfficerProbeMain. ----
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
    // ---- API-side identities (public names, stable). ----
    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String CHAR_STATS_API = "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String SHIP_STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String MUTABLE_STAT_CLASS = "com.fs.starfarer.api.combat.MutableStat";
    private static final String STAT_MOD_CLASS = "com.fs.starfarer.api.combat.MutableStat$StatMod";

    /** The crew_training Level1 modifyFlat desc constant (the source-precise witness). */
    private static final String CT_DESC = "Crew Training skill";

    private static final String P = "allskills-probe";

    private RealEngineAllSkillsProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int preFrames = 120;      // 2 s: combat live + settled before the differential spawn
        int settleFrames = 120;   // 2 s: stability window — the mod must survive live stepping
        float fixedDt = SimTickRate.fixedDt();
        String variantId = "grendel_Hypervelocity";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--pre-frames": preFrames = Integer.parseInt(args[++i]); break;
                case "--settle-frames": settleFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--variant": variantId = args[++i]; break;
                default: break;
            }
        }
        boolean knobOn = AllSkillsAttach.enabled();
        System.out.println(P + "-begin pre_frames=" + preFrames + " settle_frames=" + settleFrames
                + " fixed_dt=" + fixedDt + " variant=" + variantId + " knob_on=" + knobOn);
        if (!knobOn) {
            System.out.println(P + "-end verdict=KNOB_OFF — pass -Dcc.server.officerSkillsAll=true"
                    + " (the wrapper does); a knob-off boot cannot exercise the commander seam");
            System.exit(1);
        }

        // ---- Boot the SAME product lane the serve loop uses; with the knob ON the addFleet ----
        // ---- seam installs the side-0 commander DURING create() (its log line lands above). ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEngineAllSkillsProbeMain.class.getClassLoader();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
        Object engine = engineClass.getMethod("getInstance").invoke(null);
        if (engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after create()");
        }

        // ---- Part A: side-0 manager readback — the installed commander with crew_training=1. ----
        Class<?> personApiClass = Class.forName(PERSON_API, false, loader);
        Class<?> charStatsApiClass = Class.forName(CHAR_STATS_API, false, loader);
        Method getFleetManager = engineClass.getMethod("getFleetManager", int.class);
        Object manager0 = getFleetManager.invoke(engine, 0);
        Object manager1 = getFleetManager.invoke(engine, 1);
        if (manager0 == null || manager1 == null) {
            throw new IllegalStateException("getFleetManager(0/1) null after create(): m0="
                    + manager0 + " m1=" + manager1);
        }
        Object commander = manager0.getClass().getMethod("getDefaultCommander").invoke(manager0);
        boolean commanderInstalled = commander != null;
        float cmdCrewTraining = -1.0f;
        if (commanderInstalled) {
            Object cmdStats = personApiClass.getMethod("getStats").invoke(commander);
            cmdCrewTraining = ((Number) charStatsApiClass.getMethod("getSkillLevel", String.class)
                    .invoke(cmdStats, "crew_training")).floatValue();
        }
        boolean commanderOk = commanderInstalled && cmdCrewTraining == 1.0f;
        // W15 editor D1: the union commander's CHARACTER stats must carry the
        // sheet's CHARACTER_STATS effects headless (PersonStatsRefresh) — the refit's max
        // vents/caps rule reads them. flux_regulation Level2/Level3 = +5 caps / +5 vents (reqLevel
        // 1) on a cruiser base of 30 → 35 on BOTH, the numbers the vanilla refit and the client
        // editor showed in a live run (server said 30 — the defect).
        float cmdVents30 = -1.0f;
        float cmdCaps30 = -1.0f;
        if (commanderInstalled) {
            Object cmdStats = personApiClass.getMethod("getStats").invoke(commander);
            Object ventBonus = charStatsApiClass.getMethod("getMaxVentsBonus").invoke(cmdStats);
            Object capBonus = charStatsApiClass.getMethod("getMaxCapacitorsBonus").invoke(cmdStats);
            Method computeEffective = ventBonus.getClass().getMethod("computeEffective", float.class);
            cmdVents30 = ((Number) computeEffective.invoke(ventBonus, 30.0f)).floatValue();
            cmdCaps30 = ((Number) computeEffective.invoke(capBonus, 30.0f)).floatValue();
        }
        boolean commanderVentsCapsOk = cmdVents30 == 35.0f && cmdCaps30 == 35.0f;
        System.out.println(P + " commander installed=" + commanderInstalled
                + " crew_training_level=" + cmdCrewTraining
                + " vents_max_base30=" + f2(cmdVents30) + " caps_max_base30=" + f2(cmdCaps30));

        // ---- Stat-read handles (resolve on the PUBLIC interfaces — the probe lesson). ----
        Class<?> shipStatsApiClass = Class.forName(SHIP_STATS_API, false, loader);
        Class<?> mutableStatClass = Class.forName(MUTABLE_STAT_CLASS, false, loader);
        Class<?> statModClass = Class.forName(STAT_MOD_CLASS, false, loader);
        Method getMutableStats = shipClass.getMethod("getMutableStats");
        Method getMaxCR = shipStatsApiClass.getMethod("getMaxCombatReadiness");
        Method getFluxDissipation = shipStatsApiClass.getMethod("getFluxDissipation");
        Method getFlatMods = mutableStatClass.getMethod("getFlatMods");
        Method getModifiedValue = mutableStatClass.getMethod("getModifiedValue");
        Method getBaseValue = mutableStatClass.getMethod("getBaseValue");
        Method modGetDesc = statModClass.getMethod("getDesc");
        Method modGetValue = statModClass.getMethod("getValue");
        Method shipGetOwner = shipClass.getMethod("getOwner");
        Method shipIsFighter = shipClass.getMethod("isFighter");

        // ---- Pre-window: settle, then census the t=0 roster (both sides, fighters exempt). ----
        Method getObjects = engineClass.getMethod("getObjects");
        Method repoGetList = getObjects.invoke(engine).getClass().getMethod("getList", Class.class);
        Method isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
        Method shipGetShipAI = shipClass.getMethod("getShipAI");
        Method shipSetShipAI = findOneArg(shipClass, "setShipAI");
        long tick = 0;
        for (int f = 0; f < preFrames; f++) {
            step.step(fixedDt, tick++);
        }
        List<?> rosterShips = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        int side0Seen = 0;
        int side0Buffed = 0;
        int side1Seen = 0;
        int side1Clean = 0;
        for (Object s : new ArrayList<Object>(rosterShips)) {
            if ((Boolean) shipIsFighter.invoke(s)) {
                continue;  // ALL_SHIPS apply skips FIGHTER hulls by engine law — exempt
            }
            int owner = ((Number) shipGetOwner.invoke(s)).intValue();
            float ct = crewTrainingFlat(s, getMutableStats, getMaxCR, getFlatMods,
                    modGetDesc, modGetValue);
            float flatSum = flatModSum(s, getMutableStats, getMaxCR, getFlatMods, modGetValue);
            float maxCr = statValue(s, getMutableStats, getMaxCR, getModifiedValue);
            System.out.println(P + " roster owner=" + owner + " ct_mod=" + f2(ct)
                    + " flat_sum=" + f2(flatSum) + " max_cr_stat=" + f2(maxCr));
            if (owner == 0) {
                side0Seen++;
                if (flatSum > 0.001f) {
                    side0Buffed++;
                }
            } else if (owner == 1) {
                side1Seen++;
                if (flatSum == 0.0f) {
                    side1Clean++;
                }
            }
        }
        boolean rosterSide0Ok = side0Seen > 0 && side0Buffed == side0Seen;
        boolean rosterSide1Ok = side1Seen > 0 && side1Clean == side1Seen;
        System.out.println(P + " roster-census side0=" + side0Buffed + "/" + side0Seen
                + " buffed, side1=" + side1Clean + "/" + side1Seen + " clean");

        // ---- Strip roster AI so the differential window is kill-free (officer-probe idiom). ----
        int rosterStripped = 0;
        for (Object s : new ArrayList<Object>(rosterShips)) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
                rosterStripped++;
            }
        }
        System.out.println(P + " pre_spawn tick=" + tick + " roster_ships=" + rosterShips.size()
                + " roster_ai_stripped=" + rosterStripped);

        // ---- Part B: bare fleetData-less members, SAME variant, side 0 vs side 1, NO captain. ----
        Object variant = Class.forName(VARIANT_REGISTRY_CLASS, true, loader)
                .getMethod(VARIANT_REGISTRY_METHOD, String.class).invoke(null, variantId);
        Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
        if (variant == null || !hullVariantClass.isInstance(variant)) {
            throw new IllegalStateException("variant '" + variantId + "' did not resolve — pass --variant");
        }
        Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
        Class<?> memberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
        Class<?> fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
        Method memberGetCaptain = fleetMemberApiClass.getMethod("getCaptain");
        Method captainIsDefault = personApiClass.getMethod("isDefault");

        Object member0 = mintMember(fleetMemberClass, memberTypeClass, hullVariantClass, variant,
                0, "CC AllSkills S0");
        Object member1 = mintMember(fleetMemberClass, memberTypeClass, hullVariantClass, variant,
                1, "CC AllSkills S1");
        Class<?> vector2fClass = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        Method spawn0 = manager0.getClass().getMethod("spawnFleetMember",
                fleetMemberApiClass, vector2fClass, float.class, float.class);
        Method spawn1 = manager1.getClass().getMethod("spawnFleetMember",
                fleetMemberApiClass, vector2fClass, float.class, float.class);
        Object ship0;
        Object ship1;
        try {
            ship0 = spawn0.invoke(manager0, member0, null, 0.0f, 4.0f);
            ship1 = spawn1.invoke(manager1, member1, null, 180.0f, 4.0f);
        } catch (InvocationTargetException e) {
            bail("SPAWN_THREW", e);
            return;
        }
        step.step(fixedDt, tick++);  // engine's own executeAdds drain
        boolean alive0 = (Boolean) isShipAlive.invoke(engine, ship0);
        boolean alive1 = (Boolean) isShipAlive.invoke(engine, ship1);

        float ct0 = crewTrainingFlat(ship0, getMutableStats, getMaxCR, getFlatMods,
                modGetDesc, modGetValue);
        float ct1 = crewTrainingFlat(ship1, getMutableStats, getMaxCR, getFlatMods,
                modGetDesc, modGetValue);
        float maxCr0 = statValue(ship0, getMutableStats, getMaxCR, getModifiedValue);
        float maxCr1 = statValue(ship1, getMutableStats, getMaxCR, getModifiedValue);
        float flux0 = fluxRatio(ship0, getMutableStats, getFluxDissipation, getModifiedValue, getBaseValue);
        float flux1 = fluxRatio(ship1, getMutableStats, getFluxDissipation, getModifiedValue, getBaseValue);
        boolean cap0Default = (Boolean) captainIsDefault.invoke(memberGetCaptain.invoke(member0));
        boolean cap1Default = (Boolean) captainIsDefault.invoke(memberGetCaptain.invoke(member1));
        float crDelta = maxCr0 - maxCr1;
        System.out.println(P + " drained tick=" + tick + " s0_alive=" + alive0 + " s1_alive=" + alive1
                + " s0_ct=" + f2(ct0) + " s1_ct=" + f2(ct1)
                + " s0_max_cr=" + f2(maxCr0) + " s1_max_cr=" + f2(maxCr1) + " cr_delta=" + f2(crDelta)
                + " s0_flux_ratio=" + f2(flux0) + " s1_flux_ratio=" + f2(flux1)
                + " s0_captain_default=" + cap0Default + " s1_captain_default=" + cap1Default);

        // ---- Settle window: the mod must survive live stepping (no later unapply). ----
        for (Object s : new Object[]{ship0, ship1}) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
            }
        }
        for (int f = 0; f < settleFrames; f++) {
            step.step(fixedDt, tick++);
        }
        boolean alive0End = (Boolean) isShipAlive.invoke(engine, ship0);
        boolean alive1End = (Boolean) isShipAlive.invoke(engine, ship1);
        float ct0End = crewTrainingFlat(ship0, getMutableStats, getMaxCR, getFlatMods,
                modGetDesc, modGetValue);
        float ct1End = crewTrainingFlat(ship1, getMutableStats, getMaxCR, getFlatMods,
                modGetDesc, modGetValue);

        // ---- Part C: SEAT-captain writer verify (the (d) sysexp check). ----
        Method getPercentMods = mutableStatClass.getMethod("getPercentMods");
        Method getMultMods = mutableStatClass.getMethod("getMultMods");
        Method getShieldDmg = shipStatsApiClass.getMethod("getShieldDamageTakenMult");
        Method getHullDmg = shipStatsApiClass.getMethod("getHullDamageTakenMult");
        Method getHardFluxFrac = shipStatsApiClass.getMethod("getHardFluxDissipationFraction");
        Object memberSeat = mintMember(fleetMemberClass, memberTypeClass, hullVariantClass,
                variant, 0, "CC AllSkills SEAT");
        AllSkillsAttach.attachAllSkillsCaptain(memberSeat, "allskills-probe-seat");
        Object shipSeat;
        try {
            shipSeat = spawn0.invoke(manager0, memberSeat, null, 90.0f, 4.0f);
        } catch (InvocationTargetException e) {
            bail("SEAT_SPAWN_THREW", e);
            return;
        }
        step.step(fixedDt, tick++);  // executeAdds drain — stats exist after instantiate
        Object seatStats = getMutableStats.invoke(shipSeat);
        Object shieldStat = getShieldDmg.invoke(seatStats);
        Object hullStat = getHullDmg.invoke(seatStats);
        Object hardFluxStat = getHardFluxFrac.invoke(seatStats);
        Object fluxDisStat = getFluxDissipation.invoke(seatStats);
        Method[] lanes = {getFlatMods, getPercentMods, getMultMods};
        float seSh = modValue(shieldStat, getMultMods, modGetValue, "systems_expertise_ship_4");
        float seHu = modValue(hullStat, getMultMods, modGetValue, "systems_expertise_ship_4");
        float fmSh = modValue(shieldStat, getMultMods, modGetValue, "field_modulation_ship_0");
        float caSh = modValue(shieldStat, getMultMods, modGetValue,
                "cybernetic_augmentation_ships_2");
        float dcHu = modValue(hullStat, getMultMods, modGetValue, "damage_control_ship_1");
        float fmHf = modValue(hardFluxStat, getFlatMods, modGetValue, "field_modulation_ship_3");
        float frFd = modValue(fluxDisStat, getPercentMods, modGetValue,
                "flux_regulation_ships_2");
        boolean smAbsent = !anyKeyContains(shieldStat, lanes, "shield_modulation")
                && !anyKeyContains(hullStat, lanes, "shield_modulation")
                && !anyKeyContains(hardFluxStat, lanes, "shield_modulation")
                && !anyKeyContains(fluxDisStat, lanes, "shield_modulation");
        Object seatCaptain = memberGetCaptain.invoke(memberSeat);
        Object seatCapStats = personApiClass.getMethod("getStats").invoke(seatCaptain);
        Method getSkillLevel = charStatsApiClass.getMethod("getSkillLevel", String.class);
        float capSm = ((Number) getSkillLevel.invoke(seatCapStats, "shield_modulation")).floatValue();
        float capFm = ((Number) getSkillLevel.invoke(seatCapStats, "field_modulation")).floatValue();
        float capSe = ((Number) getSkillLevel.invoke(seatCapStats, "systems_expertise")).floatValue();
        int capLevel = ((Number) charStatsApiClass.getMethod("getLevel")
                .invoke(seatCapStats)).intValue();
        // Level = ship-set size by PersonMint law; 14 = the reference save's elite-combat count.
        boolean seatCaptainOk = capSm == 0.0f && capFm == 2.0f && capSe == 2.0f && capLevel == 14;
        boolean seatSysexpOk = eq(seSh, 0.9f) && eq(seHu, 0.9f);
        // flux_reg = 10 HERE (not the vanilla capture's 5): Level1.apply ignores `level`; the
        // captured campaign's 5 is computeAndCacheThresholdBonus fleet-OP scaling, and fleetData-less
        // members take the FULL base by the null-fleetData law — context row.
        // ca 0.86 = the OPEN fix (fleet-commander elite count) — RED until it lands.
        boolean seatWritersOk = eq(fmSh, 0.85f) && eq(caSh, 0.86f) && eq(dcHu, 0.75f)
                && eq(fmHf, 0.2f) && eq(frFd, 10.0f);
        System.out.println(P + " seat captain_level=" + capLevel + " sm=" + f2(capSm)
                + " fm=" + f2(capFm) + " se=" + f2(capSe)
                + " | sysexp_shield=" + f2(seSh) + " sysexp_hull=" + f2(seHu)
                + " fm_shield=" + f2(fmSh) + " ca_shield=" + f2(caSh) + " dc_hull=" + f2(dcHu)
                + " fm_hardflux=" + f2(fmHf) + " fluxreg_pct=" + f2(frFd)
                + " sm_absent=" + smAbsent);

        boolean spawnSide0Ct = ct0 >= 0.05f && ct0 <= 0.50f && ct0End == ct0;
        boolean spawnSide1Clean = ct1 == 0.0f && ct1End == 0.0f;
        // The delta must carry AT LEAST the crew_training witness; other fleetwide movers
        // (support-doctrine-family max-CR skills at full null-fleetData bonus) stack on top.
        boolean deltaOk = crDelta >= ct0 - 0.01f && ct0 >= 0.05f;
        boolean captainsDefault = cap0Default && cap1Default;
        boolean pass = commanderOk && commanderVentsCapsOk && rosterSide0Ok && rosterSide1Ok
                && alive0 && alive1 && alive0End && alive1End
                && spawnSide0Ct && spawnSide1Clean && deltaOk && captainsDefault
                && seatCaptainOk && seatSysexpOk && seatWritersOk && smAbsent;
        System.out.println(P + "-end verdict=" + (pass ? "ALLSKILLS_COMMANDER_PROVEN" : "FAILED")
                + " commander_ok=" + commanderOk
                + " commander_vents_caps_ok=" + commanderVentsCapsOk
                + " roster_side0_buffed=" + rosterSide0Ok
                + " roster_side1_clean=" + rosterSide1Ok
                + " spawn_side0_ct=" + spawnSide0Ct
                + " spawn_side1_clean=" + spawnSide1Clean
                + " cr_delta_matches_ct=" + deltaOk
                + " captains_default=" + captainsDefault
                + " alive_end=" + (alive0End && alive1End)
                + " seat_captain_ok=" + seatCaptainOk
                + " seat_sysexp_ok=" + seatSysexpOk
                + " seat_writers_ok=" + seatWritersOk
                + " seat_sm_absent=" + smAbsent
                + " ct0=" + f2(ct0) + " ct0_end=" + f2(ct0End) + " ct1=" + f2(ct1)
                + " cr_delta=" + f2(crDelta));
        System.exit(pass ? 0 : 1);
    }

    /** One writer's value in the given lane map (keyed by source id), or NaN when absent. */
    private static float modValue(Object stat, Method laneGetter, Method modGetValue, String key)
            throws Exception {
        Map<?, ?> mods = (Map<?, ?>) laneGetter.invoke(stat);
        Object mod = mods == null ? null : mods.get(key);
        return mod == null ? Float.NaN : ((Number) modGetValue.invoke(mod)).floatValue();
    }

    /** Does ANY lane of the stat carry a writer whose source key contains {@code needle}? */
    private static boolean anyKeyContains(Object stat, Method[] laneGetters, String needle)
            throws Exception {
        for (Method g : laneGetters) {
            Map<?, ?> mods = (Map<?, ?>) g.invoke(stat);
            if (mods != null) {
                for (Object k : mods.keySet()) {
                    if (String.valueOf(k).contains(needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean eq(float got, float want) {
        return Math.abs(got - want) <= 0.001f;
    }

    /** Sum of flat mods on the ship's max-CR stat carrying the crew_training desc (0 if none). */
    private static float crewTrainingFlat(Object ship, Method getMutableStats, Method getMaxCR,
            Method getFlatMods, Method modGetDesc, Method modGetValue) throws Exception {
        Object stat = getMaxCR.invoke(getMutableStats.invoke(ship));
        Map<?, ?> flats = (Map<?, ?>) getFlatMods.invoke(stat);
        float sum = 0.0f;
        if (flats != null) {
            for (Object mod : flats.values()) {
                if (CT_DESC.equals(modGetDesc.invoke(mod))) {
                    sum += ((Number) modGetValue.invoke(mod)).floatValue();
                }
            }
        }
        return sum;
    }

    /** Sum of ALL flat mods on the ship's max-CR stat — the civilian-tolerant census witness. */
    private static float flatModSum(Object ship, Method getMutableStats, Method getMaxCR,
            Method getFlatMods, Method modGetValue) throws Exception {
        Object stat = getMaxCR.invoke(getMutableStats.invoke(ship));
        Map<?, ?> flats = (Map<?, ?>) getFlatMods.invoke(stat);
        float sum = 0.0f;
        if (flats != null) {
            for (Object mod : flats.values()) {
                sum += ((Number) modGetValue.invoke(mod)).floatValue();
            }
        }
        return sum;
    }

    private static float statValue(Object ship, Method getMutableStats, Method statGetter,
            Method getModifiedValue) throws Exception {
        return ((Number) getModifiedValue.invoke(statGetter.invoke(getMutableStats.invoke(ship))))
                .floatValue();
    }

    /** modified/base flux dissipation — the flux_regulation witness, logged informatively only. */
    private static float fluxRatio(Object ship, Method getMutableStats, Method getFluxDissipation,
            Method getModifiedValue, Method getBaseValue) throws Exception {
        Object stat = getFluxDissipation.invoke(getMutableStats.invoke(ship));
        float base = ((Number) getBaseValue.invoke(stat)).floatValue();
        float mod = ((Number) getModifiedValue.invoke(stat)).floatValue();
        return base == 0.0f ? 0.0f : mod / base;
    }

    private static Object mintMember(Class<?> fleetMemberClass, Class<?> memberTypeClass,
            Class<?> hullVariantClass, Object variant, int owner, String name) throws Exception {
        Object member = fleetMemberClass
                .getConstructor(int.class, hullVariantClass, memberTypeClass)
                .newInstance(owner, variant, memberTypeClass.getField("SHIP").get(null));
        fleetMemberClass.getMethod("setShipName", String.class).invoke(member, name);
        // Faithful crew fix: full complement BEFORE spawn so nothing controls-locks.
        Object crewComp = fleetMemberClass.getMethod("getCrewComposition").invoke(member);
        float minCrew = ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(member)).floatValue();
        crewComp.getClass().getMethod("setCrew", float.class).invoke(crewComp, Math.max(minCrew, 1.0f));
        return member;
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
