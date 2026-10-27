package cc.starsector.server.runtime;

import java.util.List;

/**
 * Boot-time pure-JVM warms for the npeer serve — every leg runs BEFORE the first round opens,
 * so no served frame pays a one-time JVM first-execution bill (classload / cold-interp /
 * LambdaForm spin). Extracted from {@code PersistentPilotIntentCombatServer} at the 5000-nb
 * PPICS anchor trip (deferred-refactors ledger); call order and behavior unchanged.
 * Legs 1-3 are state-neutral with zero engine contact: synthetic records, throwaway
 * detector instances, discarded sinks. Leg 4 is the one leg that touches the engine: it loads
 * measured vanilla classes and runs only static inits read to be safe at boot (see
 * {@link #warmEngineFirstUseClasses()}). Fail-soft throughout — a warm failure logs one row and
 * never kills a real serve.
 *
 * <p>Why the legs exist. Leg 1: the first in-round death paid a measured 164.7 ms
 * capture stall. Leg 2:
 * an attribution rig measured the remaining un-warmed fan/capture lanes
 * (live proof: stall rows 1 → 0, in-round cc classload firsts
 * collapsed to sub-stall stragglers). Leg 3: the round-0 tick-1 first-apply
 * classes three live runs' serve JFRs named on the sim thread. Leg 4:
 * the cold vanilla classes the first in-round ship death, missile, hit particle and fighter
 * escort pick loaded on the sim thread.
 */
final class ServeBootWarm {

    private ServeBootWarm() {}

    /**
     * Boot-time pure-JVM warm of the fracture lane — the first
     * in-round death paid a measured 164.7 ms capture stall — a
     * mega-stutter, dominated by one-time classload + first interpreted runs of the
     * detector/codec classes. A THROWAWAY detector folds a synthetic one-corpse family, then
     * a split tick (one fresh ref + canned rings) — exercising family mint, fold, pose
     * keyframes, cut-run derivation, quantize, and the codec record ctors — and every drain
     * is discarded. Touches NOTHING live (own detector instance, stub ring reader, synthetic
     * refs; zero engine contact by construction — the engine-side classload term is
     * D2-B's round-arm warm read, not this). Fail-soft: a warm failure logs and never kills
     * a real serve.
     */
    static void warmFractureLaneClasses() {
        try {
            Object parentRef = new Object();
            Object cloneRef = new Object();
            // Canned split geometry: parent rect [-2..0]x[-1..1], clone [0..2]x[-1..1] —
            // they share the (0,-1)/(0,1) edge, so each ring yields exactly one consecutive
            // cut run (length 2), well inside the i16 1/64-u quantize grid. Rings are CLOSED
            // on purpose (the engine walk shape) so openRing's duplicate-strip leg runs too.
            float[] parentRing = {-2f, -1f, 0f, -1f, 0f, 1f, -2f, 1f, -2f, -1f};
            float[] cloneRing = {0f, -1f, 2f, -1f, 2f, 1f, 0f, 1f, 0f, -1f};
            HulkFractureDetector warm = new HulkFractureDetector(
                    key -> 1L, ref -> false, 1,
                    ref -> ref == parentRef
                            ? new float[][] {parentRing.clone(), parentRing.clone()}
                            : new float[][] {cloneRing.clone(), cloneRing.clone()},
                    2); // burst cadence only (the window retired)
            warm.observe(1L, List.of(
                    new HulkFractureDetector.RemnantSighting(
                            "warm", parentRef, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false)));
            warm.observe(2L, List.of(
                    new HulkFractureDetector.RemnantSighting(
                            "warm", parentRef, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false),
                    new HulkFractureDetector.RemnantSighting(
                            "warm", cloneRef, 4f, 5f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, false)));
            warm.drainEdges();
            warm.drainPoseKeyframes();
            warm.drainCutGeometryRecords();
            warm.drainCutGeometrySkips();
            warm.reset();
        } catch (RuntimeException warmFailure) {
            System.out.println("npeer-serve fracture_warm_failed err=" + warmFailure);
        }
    }

    /**
     * Interpreted reps per fan-codec encode spin in {@link #warmFanCodecsAndCaptureLane()} —
     * past the HotSpot Tier3 (C1) invocation threshold (200) so the first REAL death fan runs
     * at least C1, never cold-interpreted (encode paths ran interpreted at
     * first death, C1 only 30 s later).
     */
    private static final int FAN_WARM_REPS = 600;

    /**
     * Boot warm leg 2 — the fan-codec ENCODE side plus the
     * t0-capture / t90-first-projectile / wave-mint classload lanes the attribution rig
     * measured un-warmed ({@link #warmFractureLaneClasses()} covers detector + record structs
     * only; the first in-round death still paid 41/44 classloads + cold-interp encode). Ground
     * truth for every name here = a measuring rig's classload-log
     * first-execution bursts, plus the re-flight's residual stragglers. Two halves, both
     * state-neutral with zero engine contact:
     * (i) eager classload+init of the measured burst classes (t0 capture bridge views +
     *     snapshot records = the 251 ms cap target; t90 projectile/edge lane; wave-mint +
     *     straggler codecs), class literals where public, {@code Class.forName} for the
     *     private/anonymous nested ones (string drift fails soft AND loud, one row per miss);
     * (ii) encode-spin the verified fan codecs on synthetic records to a discarded sink —
     *     pays top-level codec classload + LambdaForm/method-handle spins + C1 seeding.
     * Fail-soft per class and per spin leg: a miss logs and never kills a serve. The one
     * summary row ({@code warm_fan_codecs}) is the rig re-run's grade anchor: miss=0 and
     * post-warm first-death windows near-quiet.
     */
    static void warmFanCodecsAndCaptureLane() {
        long warmStartNanos = System.nanoTime();
        int inited = 0;
        int missed = 0;
        Class<?>[] burstClasses = {
                // t0 first-capture lane: bridge views + snapshot records (cap=251.19 ms).
                cc.starsector.server.bridge.EngineBackedShipMotionView.class,
                cc.starsector.server.bridge.EngineBackedShipDurabilityView.class,
                cc.starsector.server.bridge.EngineBackedShipLifecycleView.class,
                cc.starsector.server.bridge.EngineBackedShipAllegianceView.class,
                cc.starsector.server.bridge.EngineBackedShipIdentityView.class,
                cc.starsector.server.bridge.EngineBackedShipPhaseCloakView.class,
                cc.starsector.server.bridge.EngineBackedShipFluxView.class,
                cc.starsector.server.bridge.EngineBackedWeaponMountView.class,
                cc.starsector.server.bridge.EngineBackedShipEngineStateView.class,
                cc.starsector.server.bridge.EngineBackedShipManeuverCommandView.class,
                cc.starsector.server.bridge.EngineBackedShipSystemView.class,
                cc.starsector.server.bridge.EngineBackedShipCrView.class,
                cc.starsector.server.bridge.HulkDestroyedFlagReader.class,
                cc.starsector.server.bridge.EngineBornGrowthSeam.class,
                cc.starsector.server.bridge.SideTotalsCapture.class,
                cc.starsector.server.protocol.AuthoritativeEntitySnapshot.class,
                cc.starsector.server.protocol.AuthoritativeWorldSnapshot.class,
                cc.starsector.server.protocol.AuthoritativeWorldSnapshot.Builder.class,
                cc.starsector.server.protocol.NonShipEntitySnapshot.class,
                cc.starsector.server.protocol.ShipFluxSnapshot.class,
                cc.starsector.server.protocol.ShipWeaponMountSnapshot.class,
                cc.starsector.server.protocol.ShipAllegianceSnapshot.class,
                cc.starsector.server.protocol.ShipEngineStateSnapshot.class,
                cc.starsector.server.protocol.ShipManeuverCommandSnapshot.class,
                cc.starsector.server.protocol.ShipSystemStateSnapshot.class,
                cc.starsector.server.protocol.ShipPhaseCloakStateSnapshot.class,
                cc.starsector.server.protocol.ShipCrSnapshot.class,
                cc.starsector.server.protocol.ShipFragmentMarkerSnapshot.class,
                cc.starsector.server.protocol.ContentDictionaryEntry.class,
                AuthoritativeWorldSnapshotCodec.class,
                DownTickComposition.class,
                // t90 lane: first projectile capture + first gameplay-edge encode.
                cc.starsector.server.bridge.EngineBackedCombatEntityView.class,
                FireBirthSink.FireBirth.class,
                FireBirthSink.PoseOwnedBirth.class,
                GameplayEdgeEventCodec.class,
                cc.starsector.server.protocol.GameplayEdgeEvent.class,
                // wave-mint + straggler firsts (t1500 pre / det-fallback / match end).
                cc.starsector.server.bridge.RealEngineDemoRoster.WaveDeployResult.class,
                PersistentPilotIntentCombatServer.NPeerHooks.WaveSpawnResult.class,
                RosterAppendFrameCodec.class,
                RosterAppendFrameCodec.AppendRow.class,
                MatchOverFrameCodec.class,
                MatchOverFrameCodec.MatchOverRecord.class,
                FallbackAnnounce.class,
        };
        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
        for (Class<?> burst : burstClasses) {
            try {
                lookup.ensureInitialized(burst);
                inited++;
            } catch (Throwable miss) {
                missed++;
                System.out.println("npeer-serve warm_class_miss name=" + burst.getName()
                        + " err=" + miss.getClass().getSimpleName());
            }
        }
        // Private/anonymous nested types a literal cannot name (drift fails soft + loud above).
        String[] nestedBurstClasses = {
                "cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource$BuiltSnapshot",
                "cc.starsector.server.bridge.FreshSpawnScan$FreshSpawn",
                "cc.starsector.server.bridge.EngineCombatBinding$ArmorGridSnapshot",
                "cc.starsector.server.bridge.RealEngineDemoRoster$WaveMintHandles",
                "cc.starsector.server.bridge.SideTotalsCapture$Handles",
                "cc.starsector.server.runtime.GameplayEdgeEventDetector$MountKey",
                "cc.starsector.server.runtime.GameplayEdgeEventDetector$MountBaseline",
                "cc.starsector.server.runtime.GameplayEdgeEventDetector$MountEmission",
                "cc.starsector.server.runtime.MissileSplitEventDetector$EndCause",
                "cc.starsector.server.runtime.OnFireShadowComparator$Key",
                "cc.starsector.server.protocol.GameplayEdgeEvent$1",
        };
        for (String nested : nestedBurstClasses) {
            try {
                Class.forName(nested, true, ServeBootWarm.class.getClassLoader());
                inited++;
            } catch (Throwable miss) {
                missed++;
                System.out.println("npeer-serve warm_class_miss name=" + nested
                        + " err=" + miss.getClass().getSimpleName());
            }
        }
        int spins = 0;
        long sunkBytes = 0L;
        try {
            // Synthetic fan records mirroring the D2-A warm's canned split: family root id 1,
            // death tick 1, encode tick 2, two pieces sharing the canned rect's cut edge
            // (i16 coords = the float rect x 64; cut run = ring indices 1,2).
            short[] cutPoints = {-128, -64, 0, -64, 0, 64, -128, 64};
            int[] cutRun = {1, 2};
            for (int rep = 0; rep < FAN_WARM_REPS; rep++) {
                byte[] frame = HulkFractureFrameCodec.encode(new HulkFractureFrameCodec.FractureEdge(
                        2L, 1L, 1L, HulkFractureFrameCodec.EDGE_KIND_PIECE_ADDED, 0, 2));
                sunkBytes += frame.length;
                frame = HulkPoseFrameCodec.encode(new HulkPoseFrameCodec.PoseKeyframe(
                        2L, 1L, 1L, 0, 2, 0f, 0f, 0f, 0f, 0f, 0f));
                sunkBytes += frame.length;
                frame = HulkCutGeometryFrameCodec.encode(new HulkCutGeometryFrameCodec.CutGeometryRecord(
                        2L, 1L, 1L, 0, HulkCutGeometryFrameCodec.PIECE_SLOT_PARENT,
                        HulkCutGeometryFrameCodec.RING_KIND_EXACT, cutPoints, cutRun));
                sunkBytes += frame.length;
                frame = HulkCutGeometryFrameCodec.encode(new HulkCutGeometryFrameCodec.CutGeometryRecord(
                        2L, 1L, 1L, 0, HulkCutGeometryFrameCodec.PIECE_SLOT_CLONE,
                        HulkCutGeometryFrameCodec.RING_KIND_VISUAL, cutPoints, null));
                sunkBytes += frame.length;
                frame = EntityDespawnEventCodec.toBytes(new cc.starsector.server.protocol.EntityDespawnEvent(
                        1L, 2L, cc.starsector.server.protocol.EntityDespawnReason.values()[0]));
                sunkBytes += frame.length;
                spins++;
            }
        } catch (RuntimeException spinFailure) {
            System.out.println("npeer-serve warm_fan_spin_failed err=" + spinFailure);
        }
        try {
            // Wave-mint fan half (separate leg: an AppendRow validation drift must not cost
            // the core death-fan spins above; the classload still lands before any throw).
            for (int rep = 0; rep < FAN_WARM_REPS; rep++) {
                sunkBytes += RosterAppendFrameCodec.encode(List.of(
                        new RosterAppendFrameCodec.AppendRow(1L, 0, "warm", "warm", 1000))).length;
            }
        } catch (RuntimeException | java.io.IOException spinFailure) {
            System.out.println("npeer-serve warm_roster_spin_failed err=" + spinFailure);
        }
        System.out.println("npeer-serve warm_fan_codecs classes=" + inited + " miss=" + missed
                + " spins=" + spins + " bytes=" + sunkBytes
                + " ms=" + ((System.nanoTime() - warmStartNanos) / 1_000_000L));
    }

    /** Leg 3's tally for the boot-warm contract row: {@code inited + missed} = the list length. */
    record PilotApplyWarmTally(int inited, int missed, List<String> initedNames) {}

    /** A leg-3 string warm: the binary name + the type it must be ({@code null} = any). */
    private record NestedWarm(String name, Class<?> mustBe) {}

    /**
     * Boot warm leg 3 — the round-0 tick-1 FIRST-APPLY
     * classes. The serve JFRs of three live runs ({@code jdk.ClassLoad} ≥ 5 ms
     * with stacks, cut at the tick-1 {@code worst_frame} window) show the same cc.* set loading
     * on the sim thread inside {@code PilotIntentStep.step}: the first {@code applyPilotIntent}
     * per seat ({@code applyStateFor}'s three factory closures, the fire map / declare path, the
     * zero-flux probe gate), 5-17 ms each, ≈ 89 of the first run's 96 ms outside the engine; plus the
     * same tick's pre / det / compose firsts (transfer census, aim fan, AWSD delta, RAPP
     * catch-up). Round-1 tick 1 was already clean ⇒ a once-per-PROCESS bill, paid HERE before
     * round 0 opens (off-frame; the tick-1 {@code worst_frame} grade is not widened).
     * Leg-2 idiom: class literals where public, strings for package-private / private /
     * anonymous names. CALLEE CHECK: every listed {@code <clinit>} is game-free (no engine
     * class, no live ship, no reflection handle); {@code OwnCarrierZeroFluxProbe} binds its
     * reflective API lazily on the first ARMED sample, never at init. Anonymous
     * {@code MountProbeLane$N} numbering follows source order, so each carries the
     * interface it must implement: a drifted number is a loud miss, never a silent warm of the
     * wrong closure. One summary row + one {@code warm_class_miss} row per miss.
     */
    static PilotApplyWarmTally warmPilotApplyLaneClasses() {
        long warmStartNanos = System.nanoTime();
        List<String> initedNames = new java.util.ArrayList<>();
        int missed = 0;
        Class<?>[] firstApplyClasses = {
                // PilotIntentStep.step: applyStateFor's closure types + the apply / fire path.
                cc.starsector.server.bridge.PilotApplyState.MountReadinessProbe.class,
                cc.starsector.server.bridge.PilotApplyState.MountFireDriver.class,
                cc.starsector.server.bridge.PilotApplyState.FireClassResolver.class,
                cc.starsector.server.bridge.EngineCombatBinding.PilotApplyResult.class,
                FireDischargeDeclaration.class,
                PilotFireIntent.class,
                PilotFireMapper.class,
                PilotFireCommand.class,
                FireClassLaw.FireClass.class,
                // Same tick, other phases: transfer census, aim fan, AWSD delta, RAPP catch-up.
                cc.starsector.server.bridge.EngineCombatBinding.TransferCensusRow.class,
                PilotAimFrameCodec.class,
                PilotAimFrameCodec.PilotAimRow.class,
                AwsdSnapshotCodec.MountDelta.class,
                RosterAppendCatchup.Cursor.class,
        };
        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
        for (Class<?> first : firstApplyClasses) {
            try {
                lookup.ensureInitialized(first);
                initedNames.add(first.getName());
            } catch (Throwable miss) {
                missed++;
                System.out.println("npeer-serve warm_class_miss name=" + first.getName()
                        + " err=" + miss.getClass().getSimpleName());
            }
        }
        NestedWarm[] nestedFirstApply = {
                // fireClassResolverForShip / mountReadinessProbeForShip closures ($N drifts).
                new NestedWarm("cc.starsector.server.bridge.MountProbeLane$1",
                        cc.starsector.server.bridge.PilotApplyState.FireClassResolver.class),
                new NestedWarm("cc.starsector.server.bridge.MountProbeLane$2",
                        cc.starsector.server.bridge.PilotApplyState.MountReadinessProbe.class),
                new NestedWarm("cc.starsector.server.bridge.OwnCarrierZeroFluxProbe", null),
                new NestedWarm("cc.starsector.server.runtime.PilotAimFanLane$SeatState", null),
        };
        for (NestedWarm nested : nestedFirstApply) {
            if (warmNestedOrMiss(nested.name(), nested.mustBe())) {
                initedNames.add(nested.name());
            } else {
                missed++;
            }
        }
        System.out.println("npeer-serve warm_pilot_apply_lane inited=" + initedNames.size()
                + " miss=" + missed + " ms=" + String.format(java.util.Locale.ROOT, "%.2f",
                        (System.nanoTime() - warmStartNanos) / 1_000_000.0d));
        return new PilotApplyWarmTally(initedNames.size(), missed, List.copyOf(initedNames));
    }

    /**
     * One leg-3 string warm: load WITHOUT init, check the drift guard, then init — a drifted
     * name never runs a stranger's {@code <clinit>}. {@code false} = a miss, already logged
     * (absent name, drifted anonymous number, or a throwing init).
     */
    static boolean warmNestedOrMiss(String name, Class<?> mustBe) {
        ClassLoader loader = ServeBootWarm.class.getClassLoader();
        try {
            Class<?> loaded = Class.forName(name, false, loader);
            if (mustBe != null && !mustBe.isAssignableFrom(loaded)) {
                System.out.println("npeer-serve warm_class_miss name=" + name
                        + " err=drift want=" + mustBe.getSimpleName());
                return false;
            }
            Class.forName(name, true, loader);
            return true;
        } catch (Throwable miss) {
            System.out.println("npeer-serve warm_class_miss name=" + name
                    + " err=" + miss.getClass().getSimpleName());
            return false;
        }
    }

    /** Leg 4's tally: {@code inited + loaded + missed} = {@code names.size()}; {@code names} = the
     *  binary names the leg walked, in order (the contract row's drift pin). All counts are >= 0. */
    record EngineFirstUseWarmTally(int inited, int loaded, int missed, List<String> names) {}

    /** One leg-4 touch: INITED (loaded + static init run), LOADED (defined, init deliberately
     *  skipped: its static init reads the engine's settings singleton and none is up), MISSED (logged). */
    enum FirstUseOutcome { INITED, LOADED, MISSED }

    /** A leg-4 target: the binary name + whether its static init reads {@code Global.getSettings()}. */
    private record EngineFirstUse(String name, boolean clinitReadsSettings) {}

    private static final String ENGINE_GLOBAL = "com.fs.starfarer.api.Global";

    /** The measured cold vanilla first-use classes, in measurement order (see the leg's javadoc). */
    private static final List<EngineFirstUse> ENGINE_FIRST_USE_CLASSES = List.of(
            new EngineFirstUse("com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator", true),
            new EngineFirstUse("com.fs.starfarer.combat.ai.missile.MissileAI", false),
            new EngineFirstUse("com.fs.starfarer.combat.ai.missile.MissileAI$o", false),
            new EngineFirstUse("com.fs.graphics.particle.GenericTextureParticle", false),
            new EngineFirstUse("com.fs.starfarer.combat.ai.movement.maneuvers.EscortTargetManeuverV3$4", false));

    /**
     * Boot warm leg 4 — the cold VANILLA first-use classes.
     * The worst served frame of a live flight (68 ms) was the round's first ship death: about 58 ms
     * of it was one vanilla classload on the sim thread — the ship-breakup path calls the static
     * {@code StarSystemGenerator.getNormalRandom(Random, float, float)}, whose first call runs that
     * class's static init, which constructs {@code RingGenPlugin} and five sibling terrain plugins.
     *An earlier flight's serve JFR named four more
     * in-round vanilla firsts of 5 ms or more: {@code MissileAI} + its inner enum (first missile fire,
     * then its first advance), {@code GenericTextureParticle} (first hit particle) and
     * {@code EscortTargetManeuverV3$4} (first fighter escort pick).
     * Paid HERE, once per process, before round 0 opens (boot, off the frame loop); a
     * re-simulation walks the same boot path, so a serve and its re-sim stay alike.
     *
     * <p>Init vs load-only, per class (a static init that throws at boot would leave the class
     * unusable for the whole process = a fatal at its first in-round use, the opposite of a warm):
     * <ul>
     * <li>{@code StarSystemGenerator}: INIT when the engine's settings singleton is up, else LOAD-ONLY.
     *     Its static init = collection news, {@code random = new Random()}, the six terrain-plugin
     *     news (default constructors; only {@code MagFieldGenPlugin} has a static init, two
     *     {@code java.awt.Color} arrays), the nebula/background pickers, AND
     *     {@code DEBUG = Global.getSettings().isDevMode()} — the one read that needs a booted
     *     engine (a null singleton = NPE = a poisoned class). A serve reaching this call has
     *     booted settings (the officer-table boot warms beside this call read the same singleton).
     *     Its static {@code random} is unseeded (under the keyed constructor pin it is keyed by its own
     *     class, a counter no other caller shares, cleared at every round's re-arm), and nothing in
     *     combat reads it: the combat caller passes its own fresh {@code Random}, and the static's
     *     readers are campaign-only (Misc's market / picker / beacon / catalog-id helpers, the core
     *     lifecycle plugin, People, the campaign procgen / theme / mission / encounter code). So an init at boot moves
     *     no combat draw.</li>
     * <li>{@code MissileAI}, {@code GenericTextureParticle}, {@code EscortTargetManeuverV3$4} (a
     *     {@code Comparator} closure): INIT — none has a static init, nor do their superclass
     *     ({@code BaseParticle}) or interfaces, so init = load + link; the
     *     measured bill is the define.</li>
     * <li>{@code MissileAI}'s inner enum: INIT — its static init builds its four constants only.</li>
     * </ul>
     * Loader = this class's loader = the serve's app loader, which carries the engine jars; a JVM
     * without them (the game-free contract suite) misses every name, by design. One summary row +
     * one {@code warm_class_miss} row per miss (+ one {@code warm_class_load_only} row per skipped
     * init); never a throw out.
     */
    static EngineFirstUseWarmTally warmEngineFirstUseClasses() {
        long warmStartNanos = System.nanoTime();
        int inited = 0;
        int loaded = 0;
        int missed = 0;
        List<String> names = new java.util.ArrayList<>();
        for (EngineFirstUse first : ENGINE_FIRST_USE_CLASSES) {
            names.add(first.name());
            switch (warmEngineClassOrMiss(first.name(), first.clinitReadsSettings())) {
                case INITED -> inited++;
                case LOADED -> loaded++;
                case MISSED -> missed++;
            }
        }
        System.out.println("npeer-serve warm_engine_first_use inited=" + inited + " loaded=" + loaded
                + " miss=" + missed + " ms=" + String.format(java.util.Locale.ROOT, "%.2f",
                        (System.nanoTime() - warmStartNanos) / 1_000_000.0d));
        return new EngineFirstUseWarmTally(inited, loaded, missed, List.copyOf(names));
    }

    /**
     * One leg-4 touch: load WITHOUT init first (an absent
     * name = a miss before any static init runs); a class whose static init reads the settings
     * singleton is initialized only when that singleton is up, else left loaded-only. Every
     * failure is a logged MISSED, never a throw.
     */
    static FirstUseOutcome warmEngineClassOrMiss(String name, boolean clinitReadsSettings) {
        ClassLoader loader = ServeBootWarm.class.getClassLoader();
        try {
            Class.forName(name, false, loader);
            if (clinitReadsSettings && !engineSettingsUp(loader)) {
                System.out.println("npeer-serve warm_class_load_only name=" + name + " why=no_settings");
                return FirstUseOutcome.LOADED;
            }
            Class.forName(name, true, loader);
            return FirstUseOutcome.INITED;
        } catch (Throwable miss) {
            System.out.println("npeer-serve warm_class_miss name=" + name
                    + " err=" + miss.getClass().getSimpleName());
            return FirstUseOutcome.MISSED;
        }
    }

    /** {@code Global.getSettings() != null}; an absent API jar (no {@code Global}) = false, which the
     *  caller counts as a load-only touch and logs once. */
    private static boolean engineSettingsUp(ClassLoader loader) {
        try {
            return Class.forName(ENGINE_GLOBAL, true, loader).getMethod("getSettings").invoke(null) != null;
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }
}
