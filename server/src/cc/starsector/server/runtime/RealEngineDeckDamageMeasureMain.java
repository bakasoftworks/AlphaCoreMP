package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ShipEngineStateSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The CONTROLLED headless engine-deck damage measure. The recorded-lane
 * re-measure ({@code CcwrEngineStateDump} on a rendered run) proved the v6 tier
 * crosses every frame but engine masks stayed {@code 0x0} the whole run — with hit geometry
 * UNCONTROLLED (a player's aim), so it cannot discriminate hypothesis (0) "shots missed the deck /
 * extent mismatch" from (i)/(ii) "damage never reaches the engine trackers headless". This
 * measure controls the geometry: after warmup it applies an AIMED Hellbore-class hit AT the
 * foe's engine-nozzle {@code getLocation()} through the engine's own GL-free
 * {@code CombatEngineAPI.applyDamage} descriptor (the exact projectile-impact
 * family path, a measurement stimulus, not a product shortcut), then samples the v6 tier plus
 * the RAW tracker/controller reads for ~15 s and maps the outcome per the decided design:
 *
 * <ul>
 *   <li>masks fill + ctrl cycles (FLAMING_OUT → FLAMED_OUT → repair) ⇒ hypothesis (0):
 *       the engine models flameout fine headless; the live question becomes aim/extent;</li>
 *   <li>masks fill but ctrl never leaves ACTIVE despite &gt;0.5 disabled fraction, or the
 *       FLAMING_OUT transient never progresses (the glow-coupled wind-down, the named
 *       render-adjacent risk) ⇒ (ii) at that exact transition;</li>
 *   <li>masks never fill ⇒ (i)/(ii) at the tracker-damage edge.</li>
 * </ul>
 *
 * <p>Dual read on purpose: the published AWSS v6 tier (the serve-lane fold,
 * {@link EngineCombatBinding#shipEngineStatesOrNull}) beside the RAW per-engine tracker mask +
 * controller booleans read directly off the foe ship — so a fold-vs-engine disagreement (the
 * already-ruled-out hypothesis (iii)) would still be visible if it somehow reappeared.
 *
 * <p>Hit recipe options mirror the two proven stimuli: the default is Hellbore-class
 * {@code HIGH_EXPLOSIVE 750, emp 0} (the owner domain fact under test: ONE Hellbore shot
 * flames the Mudskipper's ENTIRE deck); {@code --damage-type ENERGY --damage 200 --emp 1500}
 * is the scout component-probe recipe (EMP feeds component damage without deep armor
 * cracking) as a fallback/control arm. Both ships' AI is idled per frame (existing
 * {@link EngineCombatBinding#idleOwnedShipAi} lane) so the aimed stimulus is the ONLY damage
 * source and the 5 s-gated timed repair is observable un-confounded.
 *
 * <p>A measurement, NOT a WATCH (headless, no socket, no operator). Non-API literals below
 * ({@code CombatEngine}, {@code entities.Ship}) are the same PINNED 0.98a-RC8 identities
 * {@link RealEngineCombatBindingFactory} documents; this is a triage instrument like
 * {@code CcwrEngineStateDump}, not product serve-path code. The per-hit cosmetic damage-FX NPE
 * ({@code com.fs.graphics}) lands AFTER the durability change and is swallowed as
 * applied ({@link SwallowCosmeticFxAuthoritativeDamageSink#isHeadlessCosmeticFxNpe} + the
 * scout's stack-class check); anything else fails the hit loudly.
 *
 *
 */
public final class RealEngineDeckDamageMeasureMain {

    private RealEngineDeckDamageMeasureMain() {
    }

    public static void main(String[] args) throws Exception {
        int warmupFrames = 30;
        int sampleFrames = 900; // ~15s at 1/60: covers the <=1.5s disable check + 5s repair gate + ramp
        float fixedDt = SimTickRate.fixedDt();
        float damage = 750.0f;  // Hellbore-class HE
        float emp = 0.0f;
        String damageTypeName = "HIGH_EXPLOSIVE";
        String hitFramesCsv = "5,15"; // sample-relative, the proven probe cadence
        String nozzleCsv = "0"; // per-hit target nozzle, cycled when shorter than the hit list
        boolean idleAi = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--warmup": warmupFrames = Integer.parseInt(args[++i]); break;
                case "--sample-frames": sampleFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--damage": damage = Float.parseFloat(args[++i]); break;
                case "--emp": emp = Float.parseFloat(args[++i]); break;
                case "--damage-type": damageTypeName = args[++i]; break;
                case "--hit-frames": hitFramesCsv = args[++i]; break;
                case "--nozzle": nozzleCsv = args[++i]; break;
                case "--no-idle-ai": idleAi = false; break;
                default: break;
            }
        }
        List<Integer> hitFrameList = new java.util.ArrayList<>();
        for (String tok : hitFramesCsv.split(",")) {
            if (!tok.trim().isEmpty()) {
                hitFrameList.add(Integer.parseInt(tok.trim()));
            }
        }
        hitFrameList.sort(null);
        Set<Integer> hitFrames = new HashSet<>(hitFrameList);
        List<Integer> nozzleList = new java.util.ArrayList<>();
        for (String tok : nozzleCsv.split(",")) {
            if (!tok.trim().isEmpty()) {
                nozzleList.add(Integer.parseInt(tok.trim()));
            }
        }

        System.out.println("deck-damage-measure-begin warmup=" + warmupFrames
                + " sample_frames=" + sampleFrames + " fixed_dt=" + fixedDt
                + " damage=" + damage + " emp=" + emp + " damage_type=" + damageTypeName
                + " hit_frames=" + hitFramesCsv + " nozzles=" + nozzleCsv + " idle_ai=" + idleAi);

        // ---- Bring up the real engine + the SAME live source the pilot serve loop builds ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        EngineBackedLiveWorldSnapshotSource source = EngineBackedLiveWorldSnapshotSource.stableRoster(
                step, binding, warmupFrames + sampleFrames, 2, fixedDt,
                null, new ShipNetworkIdRegistry(), Map.of(), null);

        // ---- Resolve the stimulus + raw-read handles (PINNED RC8 identities, measurement-only) ----
        ClassLoader loader = RealEngineDeckDamageMeasureMain.class.getClassLoader();
        Class<?> engineClass = Class.forName("com.fs.starfarer.combat.CombatEngine", true, loader);
        Object engine = engineClass.getMethod("getInstance").invoke(null);
        Class<?> shipClass = Class.forName("com.fs.starfarer.combat.entities.Ship", false, loader);
        Class<?> combatEntityApi = Class.forName("com.fs.starfarer.api.combat.CombatEntityAPI", false, loader);
        Class<?> vector2f = Class.forName("org.lwjgl.util.vector.Vector2f", false, loader);
        Class<?> damageTypeClass = Class.forName("com.fs.starfarer.api.combat.DamageType", false, loader);
        Constructor<?> vec2Ctor = vector2f.getConstructor(float.class, float.class);
        Method applyDamage = engineClass.getMethod("applyDamage",
                Object.class, combatEntityApi, vector2f, float.class, damageTypeClass,
                float.class, boolean.class, boolean.class, Object.class, boolean.class);
        Object damageType = damageTypeClass.getField(damageTypeName).get(null);

        // ---- Warmup FIRST: the roster is pending-add until the first advance drains the queue,
        // so getList() is empty pre-step (AI idled so the aimed stimulus is the only damage source).
        for (int f = 0; f < warmupFrames; f++) {
            if (idleAi) { binding.idleOwnedShipAi(1L); binding.idleOwnedShipAi(2L); }
            source.publishNextTick();
        }

        Object repo = engineClass.getMethod("getObjects").invoke(engine);
        List<?> ships = (List<?>) repo.getClass().getMethod("getList", Class.class).invoke(repo, shipClass);
        if (ships == null || ships.size() != 2) {
            throw new IllegalStateException("expected 2 roster ships, got " + (ships == null ? -1 : ships.size()));
        }
        Method getOwner = shipClass.getMethod("getOwner");
        Object sourceShip = null;
        Object foeShip = null;
        for (Object ship : ships) {
            int owner = ((Number) getOwner.invoke(ship)).intValue();
            if (owner == 0) { sourceShip = ship; } else if (owner == 1) { foeShip = ship; }
        }
        if (sourceShip == null || foeShip == null) {
            throw new IllegalStateException("owner-0/owner-1 roster pair not resolved");
        }

        Method getEngineController = shipClass.getMethod("getEngineController");
        Class<?> engCtrlApi = Class.forName("com.fs.starfarer.api.combat.ShipEngineControllerAPI", false, loader);
        Method getShipEngines = engCtrlApi.getMethod("getShipEngines");
        Method ctrlIsFlamingOut = engCtrlApi.getMethod("isFlamingOut");
        Method ctrlIsDisabled = engCtrlApi.getMethod("isDisabled");
        Class<?> shipEngApi = Class.forName(
                "com.fs.starfarer.api.combat.ShipEngineControllerAPI$ShipEngineAPI", false, loader);
        Method engIsDisabled = shipEngApi.getMethod("isDisabled");
        Method engGetLocation = shipEngApi.getMethod("getLocation");
        Method engGetHitpoints = shipEngApi.getMethod("getHitpoints");
        Method getHullLevel = shipClass.getMethod("getHullLevel");

        Object foeCtrl = getEngineController.invoke(foeShip);
        List<?> foeEngines = foeCtrl == null ? null : (List<?>) getShipEngines.invoke(foeCtrl);
        int engineCount = foeEngines == null ? -1 : foeEngines.size();
        if (foeEngines == null || foeEngines.isEmpty()) {
            throw new IllegalStateException("foe engine controller/engines unavailable (count=" + engineCount + ")");
        }
        for (int n : nozzleList) {
            if (n < 0 || n >= engineCount) {
                throw new IllegalStateException("--nozzle " + n + " out of range 0.." + (engineCount - 1));
            }
        }
        Object targetNozzle = foeEngines.get(nozzleList.get(0)); // the hp-tracked nozzle
        StringBuilder nozzleHp = new StringBuilder();
        for (int e = 0; e < engineCount; e++) {
            if (e > 0) { nozzleHp.append(','); }
            nozzleHp.append(((Number) engGetHitpoints.invoke(foeEngines.get(e))).floatValue());
        }
        System.out.println("deck-damage-measure foe_deck engine_count=" + engineCount
                + " nozzle_hp=[" + nozzleHp + "]");

        // ---- Sample loop: aimed hit(s) at the FRESH nozzle location, then step + dual read ----
        int firstMaskFrame = -1, maskAtLatch = 0, maxMaskPop = 0, maskClearedFrame = -1;
        int firstFlamingFrame = -1, firstFlamedOutFrame = -1, ctrlBackActiveFrame = -1;
        boolean v6FoeMaskSeen = false, v6FoeNonActiveSeen = false;
        float minNozzleHp = Float.MAX_VALUE, minHull = Float.MAX_VALUE;
        String lastLine = "";
        for (int s = 0; s < sampleFrames; s++) {
            if (idleAi) { binding.idleOwnedShipAi(1L); binding.idleOwnedShipAi(2L); }
            if (hitFrames.contains(s)) {
                int hitIdx = hitFrameList.indexOf(s);
                int nozzle = nozzleList.get(hitIdx % nozzleList.size());
                Object loc = engGetLocation.invoke(foeEngines.get(nozzle));
                float x = loc.getClass().getField("x").getFloat(loc);
                float y = loc.getClass().getField("y").getFloat(loc);
                Object point = vec2Ctor.newInstance(x, y);
                String hitOutcome = applyAimedHit(engine, applyDamage, sourceShip, foeShip, point,
                        damage, damageType, emp);
                System.out.println("deck-damage-measure hit frame=" + s
                        + " nozzle=" + nozzle + " x=" + x + " y=" + y
                        + " damage=" + damage + " type=" + damageTypeName + " emp=" + emp
                        + " outcome=" + hitOutcome);
                if (hitOutcome.startsWith("threw_")) {
                    throw new IllegalStateException("aimed applyDamage failed at frame " + s + ": " + hitOutcome);
                }
            }
            AuthoritativeWorldSnapshot snap = source.publishNextTick();
            if (snap == null) {
                throw new IllegalStateException("live source returned null snapshot at sample " + s);
            }

            // RAW tracker/controller reads (the engine truth the fold should mirror).
            int rawMask = 0;
            for (int e = 0; e < Math.min(engineCount, 32); e++) {
                if (Boolean.TRUE.equals(engIsDisabled.invoke(foeEngines.get(e)))) { rawMask |= (1 << e); }
            }
            boolean flaming = Boolean.TRUE.equals(ctrlIsFlamingOut.invoke(foeCtrl));
            boolean flamedOut = Boolean.TRUE.equals(ctrlIsDisabled.invoke(foeCtrl));
            float nozzleHpNow = ((Number) engGetHitpoints.invoke(targetNozzle)).floatValue();
            float hullNow = ((Number) getHullLevel.invoke(foeShip)).floatValue();
            minNozzleHp = Math.min(minNozzleHp, nozzleHpNow);
            minHull = Math.min(minHull, hullNow);

            if (rawMask != 0 && firstMaskFrame < 0) { firstMaskFrame = s; maskAtLatch = rawMask; }
            maxMaskPop = Math.max(maxMaskPop, Integer.bitCount(rawMask));
            if (firstMaskFrame >= 0 && rawMask == 0 && maskClearedFrame < 0) { maskClearedFrame = s; }
            if (flaming && firstFlamingFrame < 0) { firstFlamingFrame = s; }
            if (flamedOut && firstFlamedOutFrame < 0) { firstFlamedOutFrame = s; }
            if (firstFlamingFrame >= 0 && !flaming && !flamedOut && ctrlBackActiveFrame < 0) {
                ctrlBackActiveFrame = s;
            }

            // Published v6 tier (the serve-lane fold), both ships.
            StringBuilder v6 = new StringBuilder();
            List<ShipEngineStateSnapshot> states = snap.engineStates();
            for (int i = 0; i < states.size(); i++) {
                ShipEngineStateSnapshot st = states.get(i);
                if (i > 0) { v6.append(' '); }
                v6.append("id").append(st.entityId()).append(":ctrl=").append(st.controllerState())
                        .append(",dis=0x").append(Integer.toHexString(st.disabledMask()))
                        .append(",perm=0x").append(Integer.toHexString(st.permDisabledMask()));
                if (st.disabledMask() != 0 || st.permDisabledMask() != 0) { v6FoeMaskSeen = true; }
                if (st.controllerState() != ShipEngineStateSnapshot.CONTROLLER_ACTIVE) { v6FoeNonActiveSeen = true; }
            }
            String line = "v6[" + v6 + "] raw[mask=0x" + Integer.toHexString(rawMask)
                    + " flaming=" + flaming + " flamedOut=" + flamedOut + "]"
                    + " nozzle_hp=" + nozzleHpNow + " foe_hull=" + hullNow;
            if (!line.equals(lastLine)) {
                System.out.println("deck-damage-measure frame=" + s + " tick=" + snap.simTick() + " " + line);
                lastLine = line;
            }
        }

        // ---- Outcomes map ----
        boolean majorityDisabled = maxMaskPop * 2 > engineCount;
        boolean foeDied = minHull <= 0.0f;
        String outcome;
        if (firstMaskFrame < 0) {
            outcome = "masks_never_filled__hypothesis_i_or_ii_at_tracker_damage_edge";
        } else if (firstFlamingFrame < 0 && majorityDisabled) {
            outcome = "masks_majority_filled_ctrl_never_left_active__hypothesis_ii_at_ACTIVE_to_FLAMING_OUT";
        } else if (firstFlamingFrame < 0) {
            outcome = "masks_filled_below_flameout_threshold__damage_reaches_trackers_extent_is_the_question";
        } else if (firstFlamedOutFrame < 0 && ctrlBackActiveFrame < 0) {
            outcome = "flaming_out_never_progressed__hypothesis_ii_at_glow_wind_down";
        } else if (maskClearedFrame < 0) {
            outcome = "ctrl_cycled_but_masks_never_repaired__timed_repair_dormant";
        } else {
            outcome = "masks_filled_ctrl_cycled_repair_ran__hypothesis_0_model_fine_headless";
        }
        if (foeDied) {
            // A dead foe leaves the wire and never repairs: post-death observations do not
            // discriminate the hypotheses — rerun with a non-lethal (ENERGY+EMP) stimulus.
            outcome = "confounded_foe_died__" + outcome;
        }
        System.out.println("deck-damage-measure-end engine_count=" + engineCount
                + " first_mask_frame=" + firstMaskFrame
                + " mask_at_latch=0x" + Integer.toHexString(maskAtLatch)
                + " max_mask_pop=" + maxMaskPop
                + " majority_disabled=" + majorityDisabled
                + " first_flaming_frame=" + firstFlamingFrame
                + " first_flamed_out_frame=" + firstFlamedOutFrame
                + " ctrl_back_active_frame=" + ctrlBackActiveFrame
                + " mask_cleared_frame=" + maskClearedFrame
                + " min_nozzle_hp=" + minNozzleHp
                + " min_foe_hull=" + minHull
                + " foe_died=" + foeDied
                + " v6_mask_seen=" + v6FoeMaskSeen
                + " v6_non_active_seen=" + v6FoeNonActiveSeen
                + " outcome=" + outcome);
    }

    /**
     * One aimed {@code applyDamage} hit; the headless cosmetic-FX NPE (lands AFTER the durability change)
     * is swallowed as applied — classified by the product sink's message marker OR the
     * scout probe's stack-class scan (the jdk-23 lane can throw marker-less NPEs).
     */
    private static String applyAimedHit(Object engine, Method applyDamage, Object sourceShip,
            Object foeShip, Object point, float damage, Object damageType, float emp) {
        try {
            applyDamage.invoke(engine, sourceShip, foeShip, point, damage, damageType, emp,
                    Boolean.TRUE, Boolean.FALSE, null, Boolean.FALSE);
            return "applied";
        } catch (Throwable t) {
            Throwable cause = (t instanceof InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            if (SwallowCosmeticFxAuthoritativeDamageSink.isHeadlessCosmeticFxNpe(cause)) {
                return "applied_cosmetic_fx_npe_swallowed";
            }
            if (cause instanceof NullPointerException
                    && Arrays.stream(cause.getStackTrace())
                            .anyMatch(el -> el.getClassName().startsWith("com.fs.graphics"))) {
                return "applied_cosmetic_fx_npe_swallowed";
            }
            return "threw_" + cause.getClass().getName()
                    + (cause.getMessage() == null ? "" : (": " + cause.getMessage()));
        }
    }
}
