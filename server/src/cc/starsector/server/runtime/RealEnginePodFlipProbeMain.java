package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.bridge.SeatRebind;
import cc.starsector.server.bridge.TransferPodSpawner;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.platform.AllSkillsAttach;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The POD-FLIP probe: the cheapest
 * headless proof of the ONE-ENTITY transfer strand BEFORE any live in-game test.
 * Repurposed from the retired pose-deploy probe (same gated boot + reflection idiom as
 * {@link RealEngineZeroHullProbeMain}); server leg only, no client.
 *
 * <p><b>What it drives:</b> the REAL {@link TransferPodSpawner} against a real engine — a vanilla
 * xfer pod spawned from a SOURCE toward a TARGET seat hull, then the round-trip
 * {@code toPilotable} (strand: xfer &rarr; cc_pod spec, collision NONE &rarr; SHIP, AI null, at rest,
 * inside the 250-su inset) &harr; {@code toFlight} (resume: cc_pod &rarr; xfer, NONE,
 * {@code TransferCommandAI}), one engine advance between each flip, plus the DOCKED strand (pushed
 * clear of the target's collision radius, lift-off gate open) and {@code abortLanding}.
 *
 * <p><b>Legs</b> (each prints {@code probe-pod-flip leg=<X> ...}; the run ends with
 * {@code probe-pod-flip RESULT=PASS|FAIL}):
 * <ul>
 * <li>A spawn: {@code isShuttlePod()} true, {@code getCollisionClass()} NONE, {@code getAI()} non-null.</li>
 * <li>B {@code toPilotable(pod, null)} + 1 tick: {@code isShuttlePod()} false, variant id
 *     {@code shuttlepod_cc_pod}, collision SHIP, AI null, {@code isLiftingOffOrLanding()} false,
 *     velocity (0,0), location inside the live map bounds inset 250 su.</li>
 * <li>C {@code toFlight(pod, target)} + 1 tick: {@code isShuttlePod()} true, collision NONE, AI is a
 *     {@code TransferCommandAI}, {@code isPodBody(pod)} false.</li>
 * <li>D DOCKED strand: {@code beginLanding} &rarr; ~3.5 s of ticks until {@code isFinishedLanding}
 *     &rarr; {@code toPilotable(pod, target)} + 1 tick: {@code isLanding} false,
 *     {@code isLiftingOffOrLanding} false, collision SHIP, distance(pod, target) &gt; the target's
 *     collision radius, {@code isPodBody(pod)} true.</li>
 * <li>E lift-off: {@code toFlight} &rarr; {@code beginLanding} &rarr; &ge;1 tick landing &rarr;
 *     {@code abortLanding(pod)} true + 1 tick: {@code isLanding} false.</li>
 * <li>F teardown: {@code despawnPod(pod)}.</li>
 * <li>G the transfer captain: the real {@code mountSeatOnPod} + {@code transferSeatToHull} onto a
 *     fresh wave on the seat's side, twice — see {@code legG}.</li>
 * </ul>
 *
 * <p>Exit codes: 0 = RESULT=PASS; 1 = RESULT=FAIL (a leg assertion failed; the per-leg line names the
 * check) or infrastructure failure; 2 = a boundary captured (boot/step/deploy/flip threw — stack
 * printed).
 *
 */
public final class RealEnginePodFlipProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineZeroHullProbeMain. ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";
    /** Vanilla's transfer-command AI (the engine's own class; it
     * {@code implements AI} only) — asserted by simple class name, never by package. */
    private static final String TRANSFER_AI_SIMPLE_NAME = "TransferCommandAI";

    private static final String P = "probe-pod-flip";

    /** The inset: {@code toPilotable} clamps the pose 250 su inside the map (the engine's
     * edge push starts 200 su inside). */
    private static final float MAP_INSET_SU = 250.0f;
    /** Vanilla's landing shrink is 3 s; D advances up to this many seconds waiting for
     * {@code isFinishedLanding} (the pod first has to FLY to the target). */
    private static final float DOCK_WAIT_MAX_S = 20.0f;
    /** Landing settle window before the DOCKED strand flip (the "~3.5 s" of the packet). */
    private static final float DOCK_SETTLE_S = 3.5f;

    private static final List<String> FAILS = new ArrayList<String>();

    private RealEnginePodFlipProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int warmFrames = 60;    // 1 s zero-hull stepping (path proven by the zero-hull probe)
        float fixedDt = SimTickRate.fixedDt();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--warm-frames": warmFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin warm_frames=" + warmFrames + " fixed_dt=" + fixedDt
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        // ---- Gated boot, EMPTY occupancy (the zero-hull-proven serveNPeer boot shape). ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEnginePodFlipProbeMain.class.getClassLoader();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
        Object engine = engineClass.getMethod("getInstance").invoke(null);
        if (engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        Method getObjects = engineClass.getMethod("getObjects");
        Method repoGetList = getObjects.invoke(engine).getClass().getMethod("getList", Class.class);
        Method shipGetLocation = shipClass.getMethod("getLocation");
        Method shipGetVelocity = shipClass.getMethod("getVelocity");
        Method shipGetShipAI = shipClass.getMethod("getShipAI");
        Method shipSetShipAI = findOneArg(shipClass, "setShipAI");
        Method shipGetAI = shipClass.getMethod("getAI");
        Method shipIsShuttlePod = shipClass.getMethod("isShuttlePod");
        Method shipGetCollisionClass = shipClass.getMethod("getCollisionClass");
        Method shipGetVariant = shipClass.getMethod("getVariant");
        // `isLiftingOffOrLanding` is a PRIVATE FIELD (recomputed
        // in advance = isAnimatedLaunch && sinceLaunch <= 1); the public `isLiftingOff()`
        // masks it with !isLanding — read the gate field itself (server-side reflection).
        java.lang.reflect.Field shipIsLiftingOffOrLanding =
                shipClass.getDeclaredField("isLiftingOffOrLanding");
        shipIsLiftingOffOrLanding.setAccessible(true);
        Method shipGetCollisionRadius = shipClass.getMethod("getCollisionRadius");
        Class<?> vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        Field vecX = vec2Class.getField("x");
        Field vecY = vec2Class.getField("y");

        // This gated boot never rides applyCombatMapBounds — read the LIVE engine dims.
        float mapHalfX = ((Number) engineClass.getMethod("getMapWidth").invoke(engine)).floatValue() * 0.5f;
        float mapHalfY = ((Number) engineClass.getMethod("getMapHeight").invoke(engine)).floatValue() * 0.5f;
        System.out.println(P + " map half_x=" + f1(mapHalfX) + " half_y=" + f1(mapHalfY)
                + " inset_su=" + f1(MAP_INSET_SU));

        int bootRepo = ((List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass)).size();
        if (bootRepo != 0) {
            System.out.println(P + " RESULT=FAIL reason=boot_repo_not_empty repo=" + bootRepo);
            System.exit(1);
            return;
        }

        // ---- Warm window (proven path; a throw here is still a captured boundary). ----
        long tick = 0;
        try {
            for (int f = 0; f < warmFrames; f++) {
                step.step(fixedDt, tick++);
            }
        } catch (Throwable t) {
            boundary("WARM_STEP_THREW", tick, t);
            return;
        }
        // The shape's t=0 FOE deploys INTO the warm window; strip its AI so nothing engages the
        // pod mid-probe (this measures the flip seam, not combat).
        List<?> warmRepo = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        int foeAiStripped = 0;
        for (Object s : warmRepo) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
                foeAiStripped++;
            }
        }
        System.out.println(P + " warm_done tick=" + tick + " repo_baseline=" + warmRepo.size()
                + " foe_ai_stripped=" + foeAiStripped);

        // ---- Deploy TARGET (seat 1, descriptor hull) + SOURCE (seat 2; FOE fallback). ----
        Object target;
        Object source;
        String sourceKind;
        RealEngineDemoRoster.SeatDeployResult seat1 = null;
        RealEngineDemoRoster.SeatDeployResult seat2 = null;
        try {
            RealEngineDemoRoster.SeatDeployResult t1 = RealEngineDemoRoster.midDeploySeat(1, null);
            seat1 = t1;
            target = t1.ship();
            System.out.println(P + " deployed target seat=1 variant=" + t1.variantId());
        } catch (Throwable t) {
            boundary("TARGET_DEPLOY_THREW", tick, t);
            return;
        }
        try {
            RealEngineDemoRoster.SeatDeployResult t2 = RealEngineDemoRoster.midDeploySeat(2, null);
            seat2 = t2;
            source = t2.ship();
            sourceKind = "seat2:" + t2.variantId();
        } catch (Throwable t) {
            // No second seat in the shape: the AI-stripped t=0 FOE is a fine launch point
            // (the spawner only reads its pose/owner for the pod's start).
            source = null;
            for (Object s : warmRepo) {
                if (s != target) {
                    source = s;
                    break;
                }
            }
            sourceKind = "foe-fallback(" + unwrap(t).getClass().getSimpleName() + ")";
            if (source == null) {
                boundary("SOURCE_DEPLOY_THREW", tick, t);
                return;
            }
        }
        // Strip the fresh deploys' AI too (probe idiom — the product shims the pilot the same tick).
        for (Object s : new Object[]{target, source}) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[]{null});
            }
        }
        System.out.println(P + " deployed source=" + sourceKind);
        // Drain step (pending-add -> repo) so both hulls are live engine objects for the spawner.
        try {
            step.step(fixedDt, tick++);
        } catch (Throwable t) {
            boundary("DRAIN_STEP_THREW", tick, t);
            return;
        }

        TransferPodSpawner spawner = new TransferPodSpawner();
        Object pod = null;
        try {
            // ---- Leg A: spawn. ----
            pod = spawner.spawnPodOrNull(source, target);
            if (pod == null) {
                System.out.println(P + " leg=A spawn=null");
                fail("A.spawnPodOrNull returned null");
                finish(spawner, pod);
                return;
            }
            step.step(fixedDt, tick++);
            String aCollision = collisionName(shipGetCollisionClass.invoke(pod));
            boolean aShuttle = (Boolean) shipIsShuttlePod.invoke(pod);
            Object aAi = shipGetAI.invoke(pod);
            System.out.println(P + " leg=A tick=" + tick + " shuttle_pod=" + aShuttle
                    + " collision=" + aCollision + " ai=" + className(aAi)
                    + " pos=" + vec(shipGetLocation.invoke(pod), vecX, vecY));
            check("A.isShuttlePod", aShuttle);
            check("A.collision==NONE", "NONE".equals(aCollision));
            check("A.getAI!=null", aAi != null);

            // ---- Leg B: toPilotable(pod, null) + one tick. ----
            boolean bOk = spawner.toPilotable(pod, null);
            step.step(fixedDt, tick++);
            boolean bShuttle = (Boolean) shipIsShuttlePod.invoke(pod);
            String bVariant = hullVariantId(shipGetVariant.invoke(pod));
            String bCollision = collisionName(shipGetCollisionClass.invoke(pod));
            Object bAi = shipGetAI.invoke(pod);
            boolean bLifting = shipIsLiftingOffOrLanding.getBoolean(pod);
            float[] bVel = vecF(shipGetVelocity.invoke(pod), vecX, vecY);
            float[] bPos = vecF(shipGetLocation.invoke(pod), vecX, vecY);
            boolean bInside = Math.abs(bPos[0]) <= mapHalfX - MAP_INSET_SU
                    && Math.abs(bPos[1]) <= mapHalfY - MAP_INSET_SU;
            System.out.println(P + " leg=B tick=" + tick + " to_pilotable=" + bOk
                    + " shuttle_pod=" + bShuttle + " variant=" + bVariant
                    + " collision=" + bCollision + " ai=" + className(bAi)
                    + " lifting_or_landing=" + bLifting
                    + " vel=(" + f1(bVel[0]) + "," + f1(bVel[1]) + ")"
                    + " pos=(" + f1(bPos[0]) + "," + f1(bPos[1]) + ") inside_inset=" + bInside);
            check("B.toPilotable", bOk);
            check("B.!isShuttlePod", !bShuttle);
            check("B.variant==" + MatchDescriptors.BASTION_SHUTTLEPOD_VARIANT_ID,
                    MatchDescriptors.BASTION_SHUTTLEPOD_VARIANT_ID.equals(bVariant));
            check("B.collision==SHIP", "SHIP".equals(bCollision));
            check("B.getAI==null", bAi == null);
            check("B.!isLiftingOffOrLanding", !bLifting);
            check("B.velocity==(0,0)", bVel[0] == 0.0f && bVel[1] == 0.0f);
            check("B.inside_250su_inset", bInside);

            // ---- Leg C: toFlight(pod, target) + one tick. ----
            boolean cOk = spawner.toFlight(pod, target);
            step.step(fixedDt, tick++);
            boolean cShuttle = (Boolean) shipIsShuttlePod.invoke(pod);
            String cCollision = collisionName(shipGetCollisionClass.invoke(pod));
            Object cAi = shipGetAI.invoke(pod);
            boolean cPodBody = spawner.isPodBody(pod);
            System.out.println(P + " leg=C tick=" + tick + " to_flight=" + cOk
                    + " shuttle_pod=" + cShuttle + " collision=" + cCollision
                    + " ai=" + className(cAi) + " pod_body=" + cPodBody);
            check("C.toFlight", cOk);
            check("C.isShuttlePod", cShuttle);
            check("C.collision==NONE", "NONE".equals(cCollision));
            check("C.getAI is TransferCommandAI",
                    cAi != null && TRANSFER_AI_SIMPLE_NAME.equals(cAi.getClass().getSimpleName()));
            check("C.!isPodBody", !cPodBody);

            // ---- Leg D: DOCKED strand. ----
            boolean dBegin = spawner.beginLanding(pod, target);
            int maxTicks = (int) Math.ceil(DOCK_WAIT_MAX_S / fixedDt);
            int settleTicks = (int) Math.ceil(DOCK_SETTLE_S / fixedDt);
            int landingTicks = 0;
            int waited = 0;
            boolean finished = false;
            for (; waited < maxTicks; waited++) {
                step.step(fixedDt, tick++);
                if (spawner.isLanding(pod)) {
                    landingTicks++;
                }
                if (spawner.isFinishedLanding(pod)) {
                    finished = true;
                    break;
                }
            }
            // Settle: vanilla's shrink is 3 s; give the docked state the packet's ~3.5 s.
            for (int f = 0; f < settleTicks; f++) {
                step.step(fixedDt, tick++);
            }
            float dPreDist = spawner.distanceOrNaN(pod, target);
            System.out.println(P + " leg=D landing begin=" + dBegin + " landing_ticks=" + landingTicks
                    + " waited_ticks=" + waited + " finished=" + finished
                    + " pre_flip_dist=" + f1(dPreDist));
            boolean dOk = spawner.toPilotable(pod, target);
            step.step(fixedDt, tick++);
            boolean dLanding = spawner.isLanding(pod);
            boolean dLifting = shipIsLiftingOffOrLanding.getBoolean(pod);
            String dCollision = collisionName(shipGetCollisionClass.invoke(pod));
            float dDist = spawner.distanceOrNaN(pod, target);
            float dRadius = ((Number) shipGetCollisionRadius.invoke(target)).floatValue();
            boolean dPodBody = spawner.isPodBody(pod);
            System.out.println(P + " leg=D tick=" + tick + " to_pilotable=" + dOk
                    + " landing=" + dLanding + " lifting_or_landing=" + dLifting
                    + " collision=" + dCollision + " dist=" + f1(dDist)
                    + " target_radius=" + f1(dRadius) + " pod_body=" + dPodBody
                    + " pos=" + vec(shipGetLocation.invoke(pod), vecX, vecY));
            check("D.beginLanding", dBegin);
            check("D.isFinishedLanding(before " + f1(DOCK_WAIT_MAX_S) + "s)", finished);
            check("D.toPilotable(docked)", dOk);
            check("D.!isLanding", !dLanding);
            check("D.!isLiftingOffOrLanding", !dLifting);
            check("D.collision==SHIP", "SHIP".equals(dCollision));
            check("D.dist>target_radius", !Float.isNaN(dDist) && dDist > dRadius);
            check("D.isPodBody", dPodBody);

            // ---- Leg E: lift-off (abortLanding mid-landing). ----
            boolean eFlight = spawner.toFlight(pod, target);
            step.step(fixedDt, tick++);
            boolean eBegin = spawner.beginLanding(pod, target);
            int eLandingTicks = 0;
            for (int f = 0; f < maxTicks; f++) {
                step.step(fixedDt, tick++);
                if (spawner.isLanding(pod)) {
                    eLandingTicks++;
                    if (eLandingTicks >= 2) {
                        break;
                    }
                }
                if (spawner.isFinishedLanding(pod)) {
                    break;
                }
            }
            boolean eLandingSeen = eLandingTicks >= 1;
            boolean eAbort = spawner.abortLanding(pod);
            step.step(fixedDt, tick++);
            boolean eLanding = spawner.isLanding(pod);
            System.out.println(P + " leg=E tick=" + tick + " to_flight=" + eFlight
                    + " begin=" + eBegin + " landing_ticks=" + eLandingTicks
                    + " abort=" + eAbort + " landing_after=" + eLanding);
            check("E.toFlight", eFlight);
            check("E.beginLanding", eBegin);
            check("E.landing_seen>=1tick", eLandingSeen);
            check("E.abortLanding", eAbort);
            check("E.!isLanding", !eLanding);
        } catch (Throwable t) {
            boundary("LEG_THREW", tick, t);
            return;
        }

        // ---- Leg F: teardown; then leg G (the transfer captain) on the same engine. ----
        //
        teardownF(spawner, pod);
        legG(step, fixedDt, tick, seat2 != null ? seat2 : seat1, seat2 != null);
        verdict();
    }

    /**
     * Leg G — the transfer CAPTAIN follows the seat and
     * the hull it leaves gets its own original back, through the REAL re-key verbs
     * ({@code mountSeatOnPod} at BEGIN, {@code transferSeatToHull} at the dock) on a fresh wave
     * hull fielded on the SEAT's own side (a foreign-side target is a typed deny). Hop 1: the
     * wave's captain becomes the 14-elite seat captain, its max speed rises, its member's
     * stats commander is untouched (captain only, never the pair), and the vacated spawn hull
     * carries its original (the BEGIN verb's own row prints {@code sourceCaptain=reverted:L14},
     * the level-14 all-skills seat captain restored). Hop 2 (onto a second fresh wave) proves the revert is not vacuous:
     * the FIRST wave sheds the 14-elite captain for its own original. Needs the all-skills knob
     * ON (the launcher sets it). A step that could not stage reads {@code SETUP-G.*}, never a
     * captain verdict.
     */
    private static void legG(EngineBackedCombatStepBoundary step, float fixedDt, long tick,
            RealEngineDemoRoster.SeatDeployResult seat, boolean seat2Minted) {
        if (seat == null) {
            System.out.println(P + " leg=G SETUP-no-seat (neither seat deployed)");
            FAILS.add("SETUP-G.no_seat");
            return;
        }
        int seatId = seat2Minted ? 2 : 1;
        int side = seat.side();
        String waveVariant = side == 0 ? MatchDescriptors.CERTIFIED_VARIANT_ID
                : MatchDescriptors.BASTION_GRENDEL_VARIANT_ID;
        TransferPodSpawner spawner = new TransferPodSpawner();
        String phase = "wave1_deploy";
        try {
            System.out.println(P + " leg=G seat=" + seatId + (seat2Minted ? "" : " (seat 2 not"
                    + " minted by the shape)") + " side=" + side + " wave_variant=" + waveVariant
                    + " knob_allskills=" + AllSkillsAttach.enabled());
            Object seatHull = seat.ship();
            RealEngineDemoRoster.WaveDeployResult w1 =
                    RealEngineDemoRoster.midDeployWaveShip(side, waveVariant, "Probe Wave G1",
                            wavePose(side, -1500f));
            step.step(fixedDt, tick++);
            Object wave1 = spawner.resolveShipOrNull(w1.stableId());
            if (wave1 == null) {
                setupFail("wave1_resolve");
                return;
            }
            int eliteBefore = eliteCount(call(wave1, "getCaptain"));
            float speedBefore = maxSpeed(wave1);
            Object member1 = call(wave1, "getFleetMember");
            Object fcBefore = member1 == null ? null : call(member1, "getFleetCommanderForStats");
            int owner1 = ((Number) call(wave1, "getOwner")).intValue();

            phase = "hop1_pod";
            Object pod1 = spawner.spawnPodOrNull(seatHull, wave1);
            String pod1Stable = pod1 == null ? null : spawner.stableIdOrNull(pod1);
            if (pod1Stable == null) {
                setupFail("hop1_pod_spawn");
                return;
            }
            phase = "hop1_begin";
            // The BEGIN verb's own printed row is
            // captured (stdout teed for this one call, restored in finally) so the leg asserts
            // the revert token byte-exact, the same literal the kit's twin prints.
            ByteArrayOutputStream begin1Out = new ByteArrayOutputStream();
            PrintStream realOut = System.out;
            SeatRebind.TransferRekeyResult begin1;
            System.setOut(teeOut(realOut, begin1Out));
            try {
                begin1 = SeatRebind.mountSeatOnPod(seatId, seat.wireId(), pod1Stable, pod1);
            } finally {
                System.setOut(realOut);
            }
            if (begin1.denyReason() != null) {
                setupFail("hop1_begin_deny");
                return;
            }
            boolean beginRevert = captainIsOriginal(seatHull);
            String beginToken = sourceCaptainToken(begin1Out.toString());
            Object beginLevel = call(call(call(seatHull, "getCaptain"), "getStats"), "getLevel");
            phase = "hop1_commit";
            SeatRebind.TransferRekeyResult commit1 =
                    SeatRebind.transferSeatToHull(seatId, seat.wireId(), 70001L, w1.stableId());
            spawner.despawnPod(pod1);
            step.step(fixedDt, tick++);
            boolean sideOk = commit1.denyReason() == null && owner1 == side;
            int eliteAfter = eliteCount(call(wave1, "getCaptain"));
            float speedAfter = maxSpeed(wave1);
            Object fcAfter = member1 == null ? null : call(member1, "getFleetCommanderForStats");
            boolean fcKept = fcAfter == fcBefore;
            boolean leverUp = speedAfter > speedBefore;
            System.out.println(P + " leg=G hop=1 captain_elite=" + eliteAfter
                    + " captain_elite_before=" + eliteBefore
                    + " max_speed_before=" + f1(speedBefore) + " max_speed_after=" + f1(speedAfter)
                    + " lever=" + (leverUp ? "up" : "flat") + " fc_kept=" + fcKept
                    + " side_ok=" + sideOk + " begin_revert=" + beginRevert
                    + " begin_token=" + beginToken + " begin_level=" + beginLevel
                    + " source_allskills=" + (begin1.sourceAllSkillsCaptain() ? 1 : 0)
                    + " captain_fallback=" + (begin1.sourceCaptainFallback() ? 1 : 0)
                    + " deny=" + (commit1.denyReason() == null ? "none" : "\"" + commit1.denyReason() + "\""));
            check("G.captain_elite==14", eliteAfter == 14);
            check("G.lever_up", leverUp);
            check("G.fc_kept", fcKept);
            check("G.side_ok", sideOk);
            check("G.begin_revert", beginRevert);
            check("G.begin_revert_L14", "reverted:L14".equals(beginToken));
            // The verb site's two source-captain facts, read on the REAL
            // Person after the revert (knob ON, the L14 mint): the bit set, the fallback not.
            check("G.source_allskills_bit", begin1.sourceAllSkillsCaptain() && !begin1.sourceCaptainFallback());

            phase = "wave2_deploy";
            RealEngineDemoRoster.WaveDeployResult w2 =
                    RealEngineDemoRoster.midDeployWaveShip(side, waveVariant, "Probe Wave G2",
                            wavePose(side, 1500f));
            step.step(fixedDt, tick++);
            Object wave2 = spawner.resolveShipOrNull(w2.stableId());
            Object pod2 = wave2 == null ? null : spawner.spawnPodOrNull(wave1, wave2);
            String pod2Stable = pod2 == null ? null : spawner.stableIdOrNull(pod2);
            if (pod2Stable == null) {
                setupFail("hop2_stage");
                return;
            }
            phase = "hop2_begin";
            SeatRebind.TransferRekeyResult begin2 =
                    SeatRebind.mountSeatOnPod(seatId, seat.wireId(), pod2Stable, pod2);
            if (begin2.denyReason() != null) {
                setupFail("hop2_begin_deny");
                return;
            }
            phase = "hop2_commit";
            SeatRebind.TransferRekeyResult commit2 =
                    SeatRebind.transferSeatToHull(seatId, seat.wireId(), 70002L, w2.stableId());
            spawner.despawnPod(pod2);
            step.step(fixedDt, tick++);
            int firstElite = eliteCount(call(wave1, "getCaptain"));
            boolean firstReverted = captainIsOriginal(wave1) && firstElite != 14;
            int secondElite = eliteCount(call(wave2, "getCaptain"));
            System.out.println(P + " leg=G hop=2 hop2_first_target_reverted=" + firstReverted
                    + " first_target_elite=" + firstElite + " second_target_elite=" + secondElite
                    + " deny=" + (commit2.denyReason() == null ? "none" : "\"" + commit2.denyReason() + "\""));
            check("G.hop2_first_target_reverted", firstReverted);
            check("G.hop2_second_target_elite==14", commit2.denyReason() == null && secondElite == 14);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            boolean staging = phase.startsWith("wave") || phase.endsWith("_pod");
            System.out.println(P + " leg=G " + (staging ? "SETUP-" : "") + "threw phase=" + phase
                    + " err=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            FAILS.add((staging ? "SETUP-G." : "G.") + phase + "_threw");
        }
    }

    /** An AT-REST wave pose on the seat's half of the map: a fly-in's travel drive inflates
     *  max speed several-fold until the re-key turns it off, which would mask the captain
     *  lever (measured: 704.4 in-burn vs 119.8 after). */
    private static float[] wavePose(int side, float x) {
        return side == 0 ? new float[] {x, -3000f, 90f} : new float[] {x, 3000f, 270f};
    }

    private static void setupFail(String what) {
        System.out.println(P + " leg=G SETUP-" + what + " (the leg could not stage)");
        FAILS.add("SETUP-G." + what);
    }

    /** The body carries its own creation-time captain: same name + elite count (identity is
     *  never compared — a re-key verb may hand back a different object of the same person);
     *  a null original = the engine's default person (no elites, never the seat's name). */
    private static boolean captainIsOriginal(Object ship) throws Exception {
        Object cap = call(ship, "getCaptain");
        Object orig = call(ship, "getOriginalCaptain");
        if (cap == null) {
            return false;
        }
        if (orig == null) {
            return eliteCount(cap) == 0 && !personName(cap).startsWith(AllSkillsAttach.OFFICER_FIRST_NAME);
        }
        return personName(cap).equals(personName(orig)) && eliteCount(cap) == eliteCount(orig);
    }

    private static int eliteCount(Object person) throws Exception {
        if (person == null) {
            return -1;
        }
        int n = 0;
        for (Object sl : (List<?>) call(call(person, "getStats"), "getSkillsCopy")) {
            if (((Number) call(sl, "getLevel")).floatValue() >= 2f) {
                n++;
            }
        }
        return n;
    }

    private static String personName(Object person) throws Exception {
        Object name = call(person, "getName");
        return name == null ? "" : String.valueOf(call(name, "getFullName"));
    }

    private static float maxSpeed(Object ship) throws Exception {
        return ((Number) call(call(call(ship, "getMutableStats"), "getMaxSpeed"),
                "getModifiedValue")).floatValue();
    }

    /** A no-arg public getter by name (null target → null). */
    private static Object call(Object target, String name) throws Exception {
        return target == null ? null : target.getClass().getMethod(name).invoke(target);
    }

    /** Bytes one verb call may leave in a capture buffer; the rest still reach the real stream. */
    private static final int CAPTURE_CAP_BYTES = 64 * 1024;

    /**
     * A stream that writes every byte to the real
     * stdout AND (up to {@link #CAPTURE_CAP_BYTES}) into {@code capture}, so a leg can read a
     * verb's own printed row without the run's log losing a line.
     */
    private static PrintStream teeOut(PrintStream real, ByteArrayOutputStream capture) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                real.write(b);
                if (capture.size() < CAPTURE_CAP_BYTES) {
                    capture.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                real.write(b, off, len);
                int room = CAPTURE_CAP_BYTES - capture.size();
                if (room > 0) {
                    capture.write(b, off, Math.min(len, room));
                }
            }

            @Override
            public void flush() {
                real.flush();
            }
        }, true);
    }

    /** The {@code sourceCaptain=} token of the captured mountSeatOnPod row, or null when the
     *  verb printed no such row. */
    private static String sourceCaptainToken(String captured) {
        int row = captured.indexOf("mountSeatOnPod seat=");
        int at = row < 0 ? -1 : captured.indexOf(" sourceCaptain=", row);
        if (at < 0) {
            return null;
        }
        int start = at + " sourceCaptain=".length();
        int end = start;
        while (end < captured.length() && !Character.isWhitespace(captured.charAt(end))) {
            end++;
        }
        return captured.substring(start, end);
    }

    /** Leg F + verdict (the early-exit path). */
    private static void finish(TransferPodSpawner spawner, Object pod) {
        teardownF(spawner, pod);
        verdict();
    }

    /** Leg F: despawn (total, never throws). */
    private static void teardownF(TransferPodSpawner spawner, Object pod) {
        if (pod != null) {
            spawner.despawnPod(pod);
        }
        System.out.println(P + " leg=F despawned=" + (pod != null));
    }

    /** Print RESULT, exit 0 only on PASS. */
    private static void verdict() {
        boolean pass = FAILS.isEmpty();
        System.out.println(P + " RESULT=" + (pass ? "PASS" : "FAIL")
                + " fails=" + FAILS.size() + (pass ? "" : " failed=" + String.join(",", FAILS)));
        System.exit(pass ? 0 : 1);
    }

    private static void check(String name, boolean cond) {
        System.out.println(P + "   check " + name + " " + (cond ? "PASS" : "FAIL"));
        if (!cond) {
            FAILS.add(name);
        }
    }

    private static void fail(String name) {
        check(name, false);
    }

    /** {@code CollisionClass} enum constant name ("NONE"/"SHIP"/...), or "null". */
    private static String collisionName(Object collisionClass) {
        return collisionClass == null ? "null" : String.valueOf(collisionClass);
    }

    /** {@code getVariant().getHullVariantId()} reflectively (the spec flip is a bare field write —
     * so the live read IS the post-flip identity), or "null". */
    private static String hullVariantId(Object variant) throws Exception {
        if (variant == null) {
            return "null";
        }
        return String.valueOf(variant.getClass().getMethod("getHullVariantId").invoke(variant));
    }

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    /** Boundary exit (verdict named by the phase that threw; stack printed, exit 2). */
    private static void boundary(String verdict, long tick, Throwable t) {
        Throwable cause = unwrap(t);
        System.out.println(P + " RESULT=FAIL boundary=" + verdict + " tick=" + tick
                + " threw=" + cause.getClass().getName() + ": " + cause.getMessage());
        cause.printStackTrace(System.out);
        System.exit(2);
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Method findOneArg(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("no one-arg " + name + " on " + cls.getName());
    }

    private static String vec(Object v, Field x, Field y) throws Exception {
        return v == null ? "null" : "(" + f1(x.getFloat(v)) + "," + f1(y.getFloat(v)) + ")";
    }

    private static float[] vecF(Object v, Field x, Field y) throws Exception {
        return new float[]{x.getFloat(v), y.getFloat(v)};
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }
}
