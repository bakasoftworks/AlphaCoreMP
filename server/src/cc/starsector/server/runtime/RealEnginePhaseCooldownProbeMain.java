package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.platform.AllSkillsAttach;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OFFLINE phase-cloak COOLDOWN discriminator.
 * The reported symptom: own phase cooldown feels ~2 s despite the all-skills
 * captain. Engine law (spot-verified): the spec's stats-aware read branches phase cloaks
 * onto {@code MutableShipStatsAPI.getPhaseCloakCooldownBonus()}
 * — SystemsExpertise's generic system-cooldown −33% does NOT
 * apply; EXACTLY 3 vanilla movers exist ({@code phase_mastery} −50% base,
 * {@code field_modulation} −50% ELITE, {@code experimentalphasecoils} −80% hullmod — absent on
 * {@code grendel_Hypervelocity}). Expected ladder: base 2.0 / all-skills captain 0.5.
 *
 * <p><b>Three-way read per hull</b> (each a distinct failure seam): (i)
 * {@code stats.getPhaseCloakCooldownBonus().computeEffective(2.0)} + the modifier-id maps —
 * did the captain's skills LAND on the stat; (ii)
 * {@code cloak.getSpecAPI().getCooldown(stats)} — the live stats-aware spec read; (iii)
 * {@code cloak.getCooldown()} — the chargeTracker-HELD value, seeded at system construction
 * from (ii) — a 0.5 on (i)/(ii) with a
 * 2.0 here = seed-ordering staleness, the felt bug with a healthy stat.
 *
 * <p><b>A/B differential</b> (the core; AllSkillsProbe part-B idiom): two bare fleetData-less
 * {@code grendel_Hypervelocity} members spawned on side 1 (no side-0 commander contamination,
 * guard (d)) — one default captain (no-officer baseline), one with
 * {@link AllSkillsAttach#attachAllSkillsCaptain} applied pre-spawn (the SAME shared-enumerator
 * law the client twin applies to the own seat hull ⇒ a server verdict transfers to the client
 * own-ship by that law). The descriptor-fielded FOE grendel ({@code bastion:anchor} side-1
 * AI row) is dumped as an informative reference row only (foe officers are curated BY DESIGN).
 *
 * <p>Verdicts (all exit 0 — reproduction and refutation both count): {@code MECHANISM_FAITHFUL}
 * (2.0/0.5 as the ladder predicts ⇒ residual is perception/variant-gap → debrief the reporter);
 * {@code COOLDOWN_NOT_REDUCED} (all-skills hull still felt-2.0; cause names the seam:
 * {@code skills_missing_on_captain} | {@code statbonus_unmoved} | {@code felt_seed_stale});
 * {@code BASE_UNEXPECTED}; {@code PARTIAL_OR_MIXED}. Exit 2 = boot/spawn boundary captured;
 * 1 = infrastructure. A MEASUREMENT main (never wired into the product serve loop); llvmpipe
 * headless, zero watch spend.
 *
 * <p><b>Cycle timeline (the F2b measurement arm):</b> after the static ladder, both A/B
 * hulls run a live press→IN→ACTIVE→press→OUT→COOLDOWN→ready cycle, logging per wall-frame
 * {@code state / isPhased / effectLevel / cooldownRemaining / timeMult / advance-count} (the
 * advance count via an {@code AdvanceableListener} proxy — ground truth for the
 * {@code CombatEngine} fast-time extra passes).
 * Expected BEFORE signature: IN/OUT wall-frames ≈ spec/3 (tracker accumulates raw dt
 * once per pass), COOLDOWN wall-frames ≈ spec/1 (modifiers
 * unapplied at COOLDOWN). AFTER the F2b server exemption lands,
 * IN/OUT go wall-true (≈ spec/1) on piloted hulls with the knob ON. Summary rows:
 * {@code phase-cd-cycle} per hull; {@code phase-cd-end} verdict semantics unchanged.
 */
public final class RealEnginePhaseCooldownProbeMain {

    // ---- PINNED 0.98a-RC8 identities (probe-local pin convention; RealEnginePhaseProbeMain). ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String HULL_VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String VARIANT_REGISTRY_CLASS = "com.fs.starfarer.loading.B";
    private static final String VARIANT_REGISTRY_METHOD = "o00000";
    private static final String FLEET_MEMBER_CLASS = "com.fs.starfarer.campaign.fleet.FleetMember";
    private static final String FLEET_MEMBER_TYPE_API = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";
    // ---- API-side identities (public API names, stable). ----
    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String CHAR_STATS_API = "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String SHIP_STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String SHIP_SYSTEM_API = "com.fs.starfarer.api.combat.ShipSystemAPI";
    private static final String SHIP_SYSTEM_SPEC_API = "com.fs.starfarer.api.combat.ShipSystemSpecAPI";
    private static final String STAT_BONUS_CLASS = "com.fs.starfarer.api.combat.StatBonus";
    private static final String STAT_MOD_CLASS = "com.fs.starfarer.api.combat.MutableStat$StatMod";

    /** The code-verified ladder: CSV phasecloak base / all-combat-skills captain. */
    private static final float EXPECT_BASE = 2.0f;
    private static final float EXPECT_ALLSKILLS = 0.5f;
    private static final float EPS = 1e-3f;

    private static final String P = "phase-cd";

    private RealEnginePhaseCooldownProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int settleFrames = 60;   // 1 s: catch any post-spawn refresh of the held cooldown
        float fixedDt = SimTickRate.fixedDt();
        int cycleCapFrames = 1200; // 20 s wall cap per hull's live cycle timeline
        int cycleDwellFrames = 60; // 1 s phased dwell before the uncloak press
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--settle-frames": settleFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--cycle-cap-frames": cycleCapFrames = Integer.parseInt(args[++i]); break;
                case "--cycle-dwell": cycleDwellFrames = Integer.parseInt(args[++i]); break;
                default: break;
            }
        }
        boolean knobOn = AllSkillsAttach.enabled();
        System.out.println(P + "-begin settle_frames=" + settleFrames + " fixed_dt=" + fixedDt
                + " knob_on=" + knobOn
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));
        if (!knobOn) {
            System.out.println(P + "-end verdict=KNOB_OFF — pass -Dcc.server.officerSkillsAll=true"
                    + " (the wrapper does); the attach lane fail-closes without it");
            System.exit(1);
        }

        EngineCombatBinding binding;
        try {
            binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=BOOT_OR_RESOLVE_THREW boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        Rig rig = buildRig();

        long tick = 0;
        step.step(fixedDt, tick++); // drain the t=0 pending-add queue (factory contract)

        // ---- kill-free window: strip AI + autofire everywhere (anchor-probe idiom). ----
        List<?> ships = rig.ships();
        Object foe = null;
        int stripped = 0;
        for (Object s : new ArrayList<Object>(ships)) {
            if (MatchDescriptors.ANCHOR_GRENDEL_VARIANT_ID.equals(rig.variantId(s))) {
                foe = s;
            }
            if (rig.getShipAI.invoke(s) != null) {
                rig.setShipAI.invoke(s, new Object[]{null});
                stripped++;
            }
            for (Object group : (List<?>) rig.getWeaponGroupsCopy.invoke(s)) {
                if ((Boolean) group.getClass().getMethod("isAutofiring").invoke(group)) {
                    group.getClass().getMethod("toggleOff").invoke(group);
                }
            }
        }
        System.out.println(P + " w1 repo_ships=" + ships.size() + " ai_stripped=" + stripped
                + " foe_grendel=" + (foe == null ? "absent" : "present"));

        // ---- A/B: bare side-1 members, SAME variant — default captain vs all-skills. ----
        Object manager1 = rig.getFleetManager.invoke(rig.engine, 1);
        if (manager1 == null) {
            System.out.println(P + "-end verdict=INFRA reasons=fleet_manager_1_null");
            System.exit(1);
            return;
        }
        Object memberNone = rig.mintMember(MatchDescriptors.ANCHOR_GRENDEL_VARIANT_ID,
                1, "CC PhaseCd None");
        Object memberAll = rig.mintMember(MatchDescriptors.ANCHOR_GRENDEL_VARIANT_ID,
                1, "CC PhaseCd AllSkills");
        AllSkillsAttach.attachAllSkillsCaptain(memberAll, "CC PhaseCd AllSkills");
        Object shipNone;
        Object shipAll;
        try {
            Method spawn = manager1.getClass().getMethod("spawnFleetMember",
                    rig.fleetMemberApiClass, rig.vec2Class, float.class, float.class);
            shipNone = spawn.invoke(manager1, memberNone, null, 180.0f, 4.0f);
            shipAll = spawn.invoke(manager1, memberAll, null, 180.0f, 4.0f);
        } catch (InvocationTargetException e) {
            Throwable cause = unwrap(e);
            System.out.println(P + "-end verdict=SPAWN_THREW boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        step.step(fixedDt, tick++);  // engine's own executeAdds drain
        for (Object s : new Object[]{shipNone, shipAll}) {
            if (rig.getShipAI.invoke(s) != null) {
                rig.setShipAI.invoke(s, new Object[]{null});
            }
        }

        // ---- the three-way read per hull (foe row informative only). ----
        Row rowFoe = foe == null ? null : rig.read("foe_descriptor", foe);
        Row rowNone = rig.read("no_officer", shipNone);
        Row rowAll = rig.read("all_skills", shipAll);
        if (rowNone.cloakMissing || rowAll.cloakMissing) {
            System.out.println(P + "-end verdict=INFRA reasons=phase_cloak_missing"
                    + " none=" + !rowNone.cloakMissing + " all=" + !rowAll.cloakMissing);
            System.exit(1);
            return;
        }
        if (rowFoe != null) {
            rowFoe.print();
        }
        rowNone.print();
        rowAll.print();

        // ---- settle: the held value must not drift under live stepping (refresh check). ----
        for (int f = 0; f < settleFrames; f++) {
            step.step(fixedDt, tick++);
        }
        float feltNoneEnd = rig.felt(shipNone);
        float feltAllEnd = rig.felt(shipAll);
        System.out.println(P + " settle tick=" + tick + " felt_none_end=" + f4(feltNoneEnd)
                + " felt_all_end=" + f4(feltAllEnd));

        // ---- cycle timeline: live press→uncloak→ready wall-frame measurement. ----
        tick = cycleTimeline(rig, step, binding, shipAll, "all_skills", fixedDt, tick,
                cycleCapFrames, cycleDwellFrames);
        tick = cycleTimeline(rig, step, binding, shipNone, "no_officer", fixedDt, tick,
                cycleCapFrames, cycleDwellFrames);

        // ---- verdict: every branch names its discriminant (assert 2.0/0.5). ----
        boolean baseOk = near(rowNone.specBase, EXPECT_BASE) && near(rowAll.specBase, EXPECT_BASE);
        boolean noneOk = near(rowNone.bonusEff, EXPECT_BASE) && near(rowNone.felt, EXPECT_BASE)
                && near(rowNone.specLive, EXPECT_BASE);
        boolean allOk = near(rowAll.bonusEff, EXPECT_ALLSKILLS) && near(rowAll.felt, EXPECT_ALLSKILLS)
                && near(rowAll.specLive, EXPECT_ALLSKILLS);
        String verdict;
        String cause;
        if (!baseOk) {
            verdict = "BASE_UNEXPECTED";
            cause = "spec_base_none_" + f4(rowNone.specBase) + "_all_" + f4(rowAll.specBase);
        } else if (noneOk && allOk) {
            verdict = "MECHANISM_FAITHFUL";
            cause = "ladder_holds_2.0_to_0.5";
        } else if (near(rowAll.felt, EXPECT_BASE)) {
            verdict = "COOLDOWN_NOT_REDUCED";
            if (rowAll.phaseMastery < 1.0f || rowAll.fieldModulation < 2.0f) {
                cause = "skills_missing_on_captain";
            } else if (near(rowAll.bonusEff, EXPECT_BASE)) {
                cause = "statbonus_unmoved";
            } else {
                cause = "felt_seed_stale_bonus_" + f4(rowAll.bonusEff);
            }
        } else {
            verdict = "PARTIAL_OR_MIXED";
            cause = "none_" + f4(rowNone.felt) + "_all_" + f4(rowAll.felt)
                    + "_bonus_" + f4(rowAll.bonusEff);
        }
        System.out.println(P + "-end verdict=" + verdict + " cause=" + cause
                + " base_ok=" + baseOk + " no_officer_ok=" + noneOk + " all_skills_ok=" + allOk
                + " felt_none=" + f4(rowNone.felt) + " felt_all=" + f4(rowAll.felt)
                + " felt_none_end=" + f4(feltNoneEnd) + " felt_all_end=" + f4(feltAllEnd)
                + " bonus_eff_all=" + f4(rowAll.bonusEff)
                + " spec_live_all=" + f4(rowAll.specLive)
                + " pm_level=" + f1(rowAll.phaseMastery) + " fm_level=" + f1(rowAll.fieldModulation));
        System.exit(0);
    }

    /**
     * Measurement arm: one live cloak cycle on {@code ship}, per-frame timeline rows to
     * stdout. Presses in only from IDLE with cooldown drained (the pressUntilPhased guard — a
     * per-frame press would toggle a charging cloak back off), dwells {@code dwellFrames} in
     * ACTIVE, presses out, runs to ready (IDLE + cooldown drained). The advance-count column is
     * an {@code AdvanceableListener} proxy delta — ground truth for the fast-time extra passes
     * (3 while phased = the pin). Measurement only: an incomplete cycle logs
     * {@code incomplete=true}; the static {@code phase-cd-end} verdict is never affected.
     */
    private static long cycleTimeline(Rig rig, EngineBackedCombatStepBoundary step,
            EngineCombatBinding binding, Object ship,
            String label, float fixedDt, long tick, int capFrames, int dwellFrames)
            throws Exception {
        Object cloak = rig.getPhaseCloak.invoke(ship);
        if (cloak == null) {
            System.out.println(P + "-cycle hull=" + label + " incomplete=true cause=no_cloak");
            return tick;
        }
        final AtomicLong advances = new AtomicLong();
        Object counter = Proxy.newProxyInstance(rig.loader,
                new Class<?>[]{rig.advanceableListenerClass}, (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "advance": advances.incrementAndGet(); return null;
                        case "toString": return "cc-phase-cd-advance-counter";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == margs[0];
                        default: return null;
                    }
                });
        rig.addListener.invoke(ship, counter);
        float chargeUp = ((Number) rig.sysGetChargeUp.invoke(cloak)).floatValue();
        float chargeDown = ((Number) rig.sysGetChargeDown.invoke(cloak)).floatValue();
        float cooldown = ((Number) rig.sysGetCooldown.invoke(cloak)).floatValue();
        Object stats = rig.getMutableStats.invoke(ship);
        int fPressIn = -1;
        int fIn = -1;
        int fActive = -1;
        int fPressOut = -1;
        int fCooldown = -1;
        int fReady = -1;
        int dwell = 0;
        int lastEdge = -100;
        float tmMax = Float.NaN;
        String prevState = "?";
        long advPrev = advances.get();
        for (int f = 0; f < capFrames && fReady < 0; f++) {
            String pre = String.valueOf(rig.sysGetState.invoke(cloak));
            float cdRemPre = ((Number) rig.sysGetCooldownRemaining.invoke(cloak)).floatValue();
            if (fPressOut < 0 && "IDLE".equals(pre) && cdRemPre <= 0f) {
                rig.giveCommand.invoke(ship, rig.cmdToggleCloak, null, 0);
                if (fPressIn < 0) {
                    fPressIn = f;
                }
            } else if (fPressIn >= 0 && fPressOut < 0 && "ACTIVE".equals(pre)
                    && ++dwell > dwellFrames) {
                rig.giveCommand.invoke(ship, rig.cmdToggleCloak, null, 0);
                fPressOut = f;
            }
            step.step(fixedDt, tick++);
            // A/B surface: the boundary's post-step visit only covers OWNED
            // hulls — these bare probe hulls need the direct entry (knob-gated inside; the
            // -Dcc.server.pilotedCloakWallClock lever decides BEFORE vs AFTER numbers).
            binding.pilotedCloakWallClockVisit(ship, step.effectiveStepAmount(fixedDt));
            String state = String.valueOf(rig.sysGetState.invoke(cloak));
            float cdRem = ((Number) rig.sysGetCooldownRemaining.invoke(cloak)).floatValue();
            float eff = ((Number) rig.sysGetEffectLevel.invoke(cloak)).floatValue();
            boolean phased = (Boolean) rig.isPhased.invoke(ship);
            float tm = ((Number) rig.statGetModifiedValue
                    .invoke(rig.statsGetTimeMult.invoke(stats))).floatValue();
            long adv = advances.get() - advPrev;
            advPrev = advances.get();
            if (phased && (Float.isNaN(tmMax) || tm > tmMax)) {
                tmMax = tm;
            }
            if (fIn < 0 && "IN".equals(state)) {
                fIn = f;
            }
            if (fActive < 0 && "ACTIVE".equals(state)) {
                fActive = f;
            }
            if (fPressOut >= 0 && fCooldown < 0 && "COOLDOWN".equals(state)) {
                fCooldown = f;
            }
            if (fCooldown >= 0 && "IDLE".equals(state) && cdRem <= 0f) {
                fReady = f;
            }
            boolean edge = !state.equals(prevState) || fReady == f
                    || fPressIn == f || fPressOut == f;
            if (edge) {
                lastEdge = f;
            }
            if (edge || f - lastEdge < 4 || f % 15 == 0) {
                System.out.println(P + "-t hull=" + label + " f=" + f + " state=" + state
                        + " phased=" + phased + " eff=" + f4(eff) + " cdrem=" + f4(cdRem)
                        + " tm=" + f4(tm) + " adv=" + adv);
            }
            prevState = state;
        }
        boolean incomplete = fPressIn < 0 || fPressOut < 0 || fCooldown < 0 || fReady < 0;
        int inFrames = fIn >= 0 && fActive >= 0 ? fActive - fIn : -1;
        int outFrames = fPressOut >= 0 && fCooldown >= 0 ? fCooldown - fPressOut : -1;
        int cdFrames = fCooldown >= 0 && fReady >= 0 ? fReady - fCooldown : -1;
        float expIn = chargeUp / fixedDt;
        float expOut = chargeDown / fixedDt;
        float expCd = cooldown / fixedDt;
        System.out.println(P + "-cycle hull=" + label + " incomplete=" + incomplete
                + " press_in_f=" + fPressIn + " in_f=" + fIn + " active_f=" + fActive
                + " press_out_f=" + fPressOut + " cooldown_f=" + fCooldown
                + " ready_f=" + fReady
                + " in_frames=" + inFrames + " out_frames=" + outFrames
                + " cd_frames=" + cdFrames
                + " exp_in_frames=" + f1(expIn) + " exp_out_frames=" + f1(expOut)
                + " exp_cd_frames=" + f1(expCd)
                + " dil_in=" + ratio(expIn, inFrames) + " dil_out=" + ratio(expOut, outFrames)
                + " dil_cd=" + ratio(expCd, cdFrames)
                + " tm_max=" + f4(tmMax)
                + " spec_up=" + f4(chargeUp) + " spec_down=" + f4(chargeDown)
                + " spec_cd=" + f4(cooldown));
        return tick;
    }

    /** Expected-over-observed wall frames: ≈3 = dilated (BEFORE), ≈1 = wall-true (AFTER). */
    private static String ratio(float expected, int observed) {
        return observed <= 0 ? "n/a" : String.format(Locale.ROOT, "%.2f", expected / observed);
    }

    /** One hull's three-way read + captain context; printed as a single greppable line. */
    private static final class Row {
        String label;
        boolean cloakMissing;
        String captain = "-";
        float phaseMastery = Float.NaN;
        float fieldModulation = Float.NaN;
        float specBase = Float.NaN;
        float specLive = Float.NaN;
        float bonusEff = Float.NaN;
        float felt = Float.NaN;
        float chargeUp = Float.NaN;
        float chargeDown = Float.NaN;
        String phaseMods = "none";
        float sysEff = Float.NaN;
        String sysMods = "none";

        void print() {
            System.out.println(P + " row=" + label + " captain=" + captain
                    + " pm_level=" + f1(phaseMastery) + " fm_level=" + f1(fieldModulation)
                    + " spec_base=" + f4(specBase) + " spec_live=" + f4(specLive)
                    + " bonus_eff2=" + f4(bonusEff) + " felt=" + f4(felt)
                    + " charge_up=" + f4(chargeUp) + " charge_down=" + f4(chargeDown)
                    + " phase_mods=" + phaseMods
                    + " sys_eff2=" + f4(sysEff) + " sys_mods=" + sysMods);
        }
    }

    // ---- reflective rig over the live engine (resolved once at boot) ----
    private static final class Rig {
        ClassLoader loader;
        Object engine;
        Class<?> shipClass;
        Class<?> fleetMemberApiClass;
        Class<?> vec2Class;
        Class<?> hullVariantClass;
        Class<?> fleetMemberClass;
        Class<?> memberTypeClass;
        Method getObjects;
        Method repoGetList;
        Method getFleetManager;
        Method getVariantRegistry;
        Method getVariant;
        Method getShipAI;
        Method setShipAI;
        Method getWeaponGroupsCopy;
        Method getMutableStats;
        Method getPhaseCloak;
        Method isPhased;
        Method giveCommand;
        Object cmdToggleCloak;
        Method addListener;
        Class<?> advanceableListenerClass;
        Method sysGetState;
        Method sysGetCooldownRemaining;
        Method sysGetEffectLevel;
        Method statsGetTimeMult;
        Method statGetModifiedValue;
        Method shipGetCaptain;
        Method personIsDefault;
        Method personGetStats;
        Method charGetSkillLevel;
        Method sysGetCooldown;
        Method sysGetChargeUp;
        Method sysGetChargeDown;
        Method sysGetSpecAPI;
        Method specGetCooldown;
        Method statsGetPhaseBonus;
        Method statsGetSystemBonus;
        Method bonusComputeEffective;
        Method bonusGetFlat;
        Method bonusGetPercent;
        Method bonusGetMult;
        Method modGetValue;

        List<?> ships() throws Exception {
            return (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        }

        String variantId(Object ship) {
            try {
                Object variant = getVariant.invoke(ship);
                if (variant == null) {
                    return "?";
                }
                Object id = variant.getClass().getMethod("getHullVariantId").invoke(variant);
                return id instanceof String ? (String) id : "?";
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "?";
            }
        }

        float felt(Object ship) throws Exception {
            Object cloak = getPhaseCloak.invoke(ship);
            return cloak == null ? Float.NaN
                    : ((Number) sysGetCooldown.invoke(cloak)).floatValue();
        }

        /** The full three-way read for one hull; never throws (NaN/"-" on a missing seam). */
        Row read(String label, Object ship) throws Exception {
            Row row = new Row();
            row.label = label;
            Object cloak = getPhaseCloak.invoke(ship);
            if (cloak == null) {
                row.cloakMissing = true;
                return row;
            }
            Object stats = getMutableStats.invoke(ship);
            Object spec = sysGetSpecAPI.invoke(cloak);
            row.specBase = ((Number) specGetCooldown.invoke(spec, new Object[]{null})).floatValue();
            row.specLive = ((Number) specGetCooldown.invoke(spec, stats)).floatValue();
            Object phaseBonus = statsGetPhaseBonus.invoke(stats);
            row.bonusEff = ((Number) bonusComputeEffective.invoke(phaseBonus, EXPECT_BASE)).floatValue();
            row.felt = ((Number) sysGetCooldown.invoke(cloak)).floatValue();
            row.chargeUp = ((Number) sysGetChargeUp.invoke(cloak)).floatValue();
            row.chargeDown = ((Number) sysGetChargeDown.invoke(cloak)).floatValue();
            row.phaseMods = mods(phaseBonus);
            Object sysBonus = statsGetSystemBonus.invoke(stats);
            row.sysEff = ((Number) bonusComputeEffective.invoke(sysBonus, EXPECT_BASE)).floatValue();
            row.sysMods = mods(sysBonus);
            try {
                Object captain = shipGetCaptain.invoke(ship);
                if (captain == null) {
                    row.captain = "null";
                } else {
                    row.captain = Boolean.TRUE.equals(personIsDefault.invoke(captain))
                            ? "default" : "attached";
                    Object cs = personGetStats.invoke(captain);
                    row.phaseMastery = ((Number) charGetSkillLevel
                            .invoke(cs, "phase_mastery")).floatValue();
                    row.fieldModulation = ((Number) charGetSkillLevel
                            .invoke(cs, "field_modulation")).floatValue();
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                row.captain = "unreadable";
            }
            return row;
        }

        /** id:kind=value CSV over the bonus's flat/percent/mult maps ("none" when unmodified). */
        String mods(Object bonus) throws Exception {
            Map<String, String> out = new TreeMap<String, String>();
            collect(out, (Map<?, ?>) bonusGetFlat.invoke(bonus), "flat");
            collect(out, (Map<?, ?>) bonusGetPercent.invoke(bonus), "pct");
            collect(out, (Map<?, ?>) bonusGetMult.invoke(bonus), "mult");
            return out.isEmpty() ? "none" : String.join(",", out.values());
        }

        private void collect(Map<String, String> out, Map<?, ?> map, String kind) throws Exception {
            if (map == null) {
                return;
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                float v = ((Number) modGetValue.invoke(e.getValue())).floatValue();
                out.put(kind + ":" + e.getKey(),
                        e.getKey() + ":" + kind + "=" + f4(v));
            }
        }

        /** Bare fleetData-less member, full crew — AllSkillsProbe idiom. */
        Object mintMember(String variantId, int owner, String name) throws Exception {
            Object variant = getVariantRegistry.invoke(null, variantId);
            if (variant == null || !hullVariantClass.isInstance(variant)) {
                throw new IllegalStateException("variant '" + variantId + "' did not resolve");
            }
            Object member = fleetMemberClass
                    .getConstructor(int.class, hullVariantClass, memberTypeClass)
                    .newInstance(owner, variant, memberTypeClass.getField("SHIP").get(null));
            fleetMemberClass.getMethod("setShipName", String.class).invoke(member, name);
            Object crewComp = fleetMemberClass.getMethod("getCrewComposition").invoke(member);
            float minCrew = ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(member))
                    .floatValue();
            crewComp.getClass().getMethod("setCrew", float.class)
                    .invoke(crewComp, Math.max(minCrew, 1.0f));
            return member;
        }
    }

    private static Rig buildRig() throws Exception {
        ClassLoader loader = RealEnginePhaseCooldownProbeMain.class.getClassLoader();
        Rig rig = new Rig();
        rig.loader = loader;
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        rig.shipClass = Class.forName(SHIP_CLASS, false, loader);
        rig.fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
        rig.vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        rig.hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
        rig.fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
        rig.memberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
        Class<?> personApi = Class.forName(PERSON_API, false, loader);
        Class<?> charStatsApi = Class.forName(CHAR_STATS_API, false, loader);
        Class<?> shipStatsApi = Class.forName(SHIP_STATS_API, false, loader);
        Class<?> systemApi = Class.forName(SHIP_SYSTEM_API, false, loader);
        Class<?> systemSpecApi = Class.forName(SHIP_SYSTEM_SPEC_API, false, loader);
        Class<?> statBonusClass = Class.forName(STAT_BONUS_CLASS, false, loader);
        Class<?> statModClass = Class.forName(STAT_MOD_CLASS, false, loader);
        rig.engine = engineClass.getMethod("getInstance").invoke(null);
        if (rig.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        rig.getObjects = engineClass.getMethod("getObjects");
        rig.repoGetList = rig.getObjects.invoke(rig.engine).getClass()
                .getMethod("getList", Class.class);
        rig.getFleetManager = engineClass.getMethod("getFleetManager", int.class);
        rig.getVariantRegistry = Class.forName(VARIANT_REGISTRY_CLASS, true, loader)
                .getMethod(VARIANT_REGISTRY_METHOD, String.class);
        rig.getVariant = rig.shipClass.getMethod("getVariant");
        rig.getShipAI = rig.shipClass.getMethod("getShipAI");
        rig.setShipAI = findOneArg(rig.shipClass, "setShipAI");
        rig.getWeaponGroupsCopy = rig.shipClass.getMethod("getWeaponGroupsCopy");
        rig.getMutableStats = rig.shipClass.getMethod("getMutableStats");
        rig.getPhaseCloak = rig.shipClass.getMethod("getPhaseCloak");
        rig.isPhased = rig.shipClass.getMethod("isPhased");
        Class<?> shipCommandClass = Class.forName(
                "com.fs.starfarer.api.combat.ShipCommand", false, loader);
        rig.giveCommand = rig.shipClass.getMethod("giveCommand",
                shipCommandClass, Object.class, int.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object toggle = Enum.valueOf((Class<Enum>) shipCommandClass,
                "TOGGLE_SHIELD_OR_PHASE_CLOAK");
        rig.cmdToggleCloak = toggle;
        rig.addListener = rig.shipClass.getMethod("addListener", Object.class);
        rig.advanceableListenerClass = Class.forName(
                "com.fs.starfarer.api.combat.listeners.AdvanceableListener", false, loader);
        rig.sysGetState = systemApi.getMethod("getState");
        rig.sysGetCooldownRemaining = systemApi.getMethod("getCooldownRemaining");
        rig.sysGetEffectLevel = systemApi.getMethod("getEffectLevel");
        rig.statsGetTimeMult = shipStatsApi.getMethod("getTimeMult");
        rig.statGetModifiedValue = rig.statsGetTimeMult.getReturnType()
                .getMethod("getModifiedValue");
        rig.shipGetCaptain = rig.shipClass.getMethod("getCaptain");
        rig.personIsDefault = personApi.getMethod("isDefault");
        rig.personGetStats = personApi.getMethod("getStats");
        rig.charGetSkillLevel = charStatsApi.getMethod("getSkillLevel", String.class);
        rig.sysGetCooldown = systemApi.getMethod("getCooldown");
        rig.sysGetChargeUp = systemApi.getMethod("getChargeUpDur");
        rig.sysGetChargeDown = systemApi.getMethod("getChargeDownDur");
        rig.sysGetSpecAPI = systemApi.getMethod("getSpecAPI");
        rig.specGetCooldown = systemSpecApi.getMethod("getCooldown", shipStatsApi);
        rig.statsGetPhaseBonus = shipStatsApi.getMethod("getPhaseCloakCooldownBonus");
        rig.statsGetSystemBonus = shipStatsApi.getMethod("getSystemCooldownBonus");
        rig.bonusComputeEffective = statBonusClass.getMethod("computeEffective", float.class);
        rig.bonusGetFlat = statBonusClass.getMethod("getFlatBonuses");
        rig.bonusGetPercent = statBonusClass.getMethod("getPercentBonuses");
        rig.bonusGetMult = statBonusClass.getMethod("getMultBonuses");
        rig.modGetValue = statModClass.getMethod("getValue");
        return rig;
    }

    private static boolean near(float value, float expect) {
        return Math.abs(value - expect) < EPS;
    }

    private static Method findOneArg(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("no one-arg " + name + " on " + cls.getName());
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }

    private static String f4(float f) {
        return String.format(Locale.ROOT, "%.4f", f);
    }
}
