package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.bridge.DescriptorLayout;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.protocol.SeatRole;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.NPeerHooks;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The serve-boot WARM-UP ROUND:
 * real, seatless, AI-flown combat on the real engine BEFORE the door binds, then the proven
 * between-rounds reset. The F9'd first-combat stall (a ~200–300 ms off-CPU wait
 * inside {@code adv_eng} at tick ~3200–4000 of the FIRST combat, concurrent with the
 * first-combat C2 compile storm — Σ~100 s of {@code AttackAIModule}/{@code StrafeTargetManeuverV2}
 * L4 monsters) is a once-per-JVM bill: JIT compiles and the ChunkPool prune happen the first
 * time real combat runs. Paying them here removes them from the SERVED round whichever
 * primitive the wait is. Engine-faithful by construction — the warm-up is the same engine,
 * the same step primitive and the same reset every multi-round serve already rides.
 *
 * <p>Boot slot ({@code PilotIntentCombatServerMain}): after the hooks are built, BEFORE the
 * RNG pin (a pinned run's stream starts at its seed AFTER warming) and BEFORE {@code serveNPeer}
 * (no client can join a warming server; the per-serve {@code ContentIdRegistry} and
 * {@code DownEmissionComposer} do not exist yet ⇒ zero cross-round ledger residue). Contract
 * tests call {@code serveNPeer} on fakes and never pass here.
 *
 * <p>One PASS: the shape's {@link WaveSchedule} minted fresh exactly as the serve mints it
 * (timer armed at tick 0 — the fortress fields no t=0 foes, w2 rides its 25 s fallback;
 * schedule-less shapes just run their t=0 rows); one PILOT seat per distinct seat hull
 * ({@link #warmupSeatIds}) flown in through the real fly-in hook (a seat hull with no intents
 * rides its engine AI, so the player-side hull classes warm too); an UNPACED tick loop over
 * {@link CombatStepBoundary#step} — the SAME primitive the serve loop's {@code publishNextTick} calls — with the due-wave drain before each tick and NO
 * capture/det/comp/send/intent/tail; the gated reset (rollback + a fresh zero-hull redeploy of
 * the descriptor's AI rows); then a COMPILE-QUIESCE wait. Timer waves only: spawn-on-clear waves
 * never fire (nothing observes enemy-live), so first contact — the storm — is what gets paid.
 *
 * <p>Why the passes are ADAPTIVE and the quiesce
 * exists: one unpaced pass is ~6 s of wall — C2 compiles take seconds EACH, so the queue the
 * pass floods is still draining when the bind opens and queued-pre-round work lands in-round by
 * time (the quiesce waits it out); a served round constructs FRESH AI on the redeployed rows
 * ({@code combat.ai.D.new} re-compiled in-round after pass 1 had already run it — a deopt on the
 * new objects' type profile), so a second pass, running on the code the first pass got
 * compiled, IS the served round's exact opening path; and HotSpot scales its tier-4 thresholds
 * UP by C2-queue length ({@code Tier4LoadFeedback}), so a method whose C1 profile only landed
 * mid-storm (one {@code AttackAIModule} method, a 9 s C2 compile) cannot qualify
 * for C2 until the queue is empty — which was 2.9 s into the served round. So: passes run until
 * one adds less than {@link #QUIET_COMPILE_MILLIS} of JIT time ({@link #MAX_PASSES} cap), each
 * followed by the reset and a quiesce that treats BOTH a moving compile-time counter AND a
 * busy process CPU (the counter moves only when a compile ENDS — a 9 s compile in flight
 * looks "still" to it) as not-yet-quiet.
 *
 * <p>Failure law: the warm-up itself is fail-SOFT (a thrown fly-in/spawn/tick logs one
 * {@code warmup_round_failed} row, the pass's reset still runs, and the remaining passes are
 * skipped); the reset is fail-CLOSED (a reset throw propagates — never serve on a dirty world).
 * One grade-anchor row {@code npeer-serve warmup_round …} carries the tick/pass/spawn/wall/
 * worst/quiesce/compile deltas; the flight's {@code serve-blocks.jfr} (started later, inside
 * {@code serveNPeer}) then measures only the residual in-round storm — the A/B metric for free.
 */
public final class ServeWarmupRound {

    /** Knob triple (registered BEHAVIOR in {@code PostureRegistry}; the roundStartGate class). */
    public static final String SYSPROP = "cc.serve.warmupRound";
    public static final String ENV = "CC_SERVE_WARMUP_ROUND";
    public static final String FILE_KEY = "serve.warmupRound";

    /**
     * Default warm-up length in sim ticks PER PASS: 90 s at 60 Hz — the fortress w2 at tick 1500
     * plus the measured stall band (ticks 3200–4000) inside it. {@code 0} = off.
     */
    public static final int DEFAULT_TICKS = 5400;

    /** Pass cap (a constant, not a knob): the adaptive stop below ends earlier. */
    public static final int MAX_PASSES = 4;

    /** A pass that adds less JIT time than this (ms) means the storm is paid: no further pass. */
    public static final long QUIET_COMPILE_MILLIS = 3_000L;

    /** Wall cap on ONE pass's tick loop (a constant, not a knob): a hung engine must not hold the boot. */
    public static final long WALL_CAP_MILLIS = 150_000L;

    /**
     * The production compile-quiesce: poll every 250 ms; still = counter unchanged AND process
     * CPU under 10 % of one core over the poll; still for ≥1.5 s; cap 60 s per pass.
     */
    public static final Quiesce COMPILE_QUIESCE = new Quiesce(250L, 1_500L, 60_000L, 0.10d);

    /** A tick past this is counted in the receipt's {@code ge80=} (the serve's stall-row tier). */
    private static final double SLOW_TICK_MILLIS = 80.0d;

    /** The between-rounds reset primitive, injected so the contract suite can spy it. */
    @FunctionalInterface
    public interface Reset {
        void reset(SortedSet<Integer> occupiedSeatIds);
    }

    /**
     * Compile-quiesce parameters: poll the JIT's cumulative compile-time counter (and, when
     * supplied, the process CPU clock) every {@code pollMillis}; a poll is "moving" if the
     * counter changed OR the process burned more than {@code busyCpuFraction} of one core across
     * the poll; done once still for {@code stableMillis}; give up after {@code capMillis} (the
     * row then prints {@code quiesce_cap_hit}).
     */
    public record Quiesce(long pollMillis, long stableMillis, long capMillis, double busyCpuFraction) {
    }

    /**
     * What the warm-up did (the grade-anchor row's fields): {@code ticks} sums every pass,
     * {@code passes} = passes that ran their tick loop, {@code passCompileMillis} = each pass's
     * JIT-time delta (fly-ins through quiesce; -1 without a compile clock), {@code quiesceMillis}
     * sums the waits, {@code failed} = a fail-soft throw ended the warm-up early.
     */
    public record Receipt(int ticks, int passes, List<Long> passCompileMillis, int flyIns,
            int spawned, long wallMillis, double worstMillis, int slowTicks, long quiesceMillis,
            boolean failed) {
    }

    /**
     * The pass count of this JVM's last warm-up, kept for the recording's header: -1 = no warm-up
     * ran, 0 = the warm-up was configured OFF (round 0 then fields the roster deployed before the
     * RNG re-arm, which a re-sim must know). Set at the end of every {@link #run} (a fail-soft
     * failed run too).
     */
    private static volatile int lastPasses = -1;

    /**
     * The tick total of this JVM's last warm-up (the receipt's {@code ticks}), kept beside the pass
     * count for the recording's header: a re-simulation forces the recorded pass count and asserts
     * it ran the same ticks. -1 = no warm-up ran, 0 = OFF.
     */
    private static volatile int lastTicks = -1;

    private ServeWarmupRound() {
    }

    /** The last warm-up's pass count: -1 never ran, 0 OFF, else the passes it ran. */
    public static int lastPassesOrNeg() {
        return lastPasses;
    }

    /** The last warm-up's tick total: -1 never ran, 0 OFF, else the ticks its passes stepped. */
    public static int lastTicksOrNeg() {
        return lastTicks;
    }

    /** The serve boot's warm-up OFF branch records that it skipped the warm-up (pass count 0). */
    public static void recordWarmupOff() {
        lastPasses = 0;
        lastTicks = 0;
    }

    /**
     * The warm-up's fly-in roster: ONE PILOT seat per distinct seat hull variant of
     * {@code descriptor} (the lowest seat id that maps to it), ascending seat ids, so a pass flies
     * in as many hulls as the descriptor has seat hull TYPES at any player count. WHY: what the
     * warm-up pays for is per hull and AI class (the JIT compiles a hull's AI, weapon and system
     * paths once, whichever ship runs them), never per seat; flying every seat made each pass scale
     * with the lobby (200 seats measured 800 fly-ins and 210 s of warm-up wall, 2 seats 8 fly-ins
     * and 16 s) while warming nothing the first seat of each hull had not already warmed. A seat
     * maps to its hull by the deploy layer's own law, never a second flatten: the seatId-sorted
     * FULL seat list's per-side ordinal ({@link DescriptorLayout#fullLayoutSideOrdinals}, every
     * seat counted as the deploy layer counts it) indexes that side's SEAT slots
     * ({@link DescriptorLayout#seatVariantSlotsBySide}). A PILOT seat whose ordinal has no SEAT
     * slot keeps its own fly-in, so the deploy layer's throw on it fails the warm-up soft exactly
     * as when every seat flew. Non-PILOT seats never fly.
     *
     *
     */
    public static List<Integer> warmupSeatIds(MatchDescriptor descriptor) {
        List<MatchDescriptor.Seat> seats = new ArrayList<>(descriptor.seats());
        seats.sort(Comparator.comparingInt(MatchDescriptor.Seat::seatId));
        int[] ordinals = DescriptorLayout.fullLayoutSideOrdinals(seats);
        List<List<String>> slotsBySide = DescriptorLayout.seatVariantSlotsBySide(descriptor.roster());
        Set<String> warmedVariants = new HashSet<>();
        List<Integer> roster = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            MatchDescriptor.Seat seat = seats.get(i);
            if (seat.role() != SeatRole.PILOT) {
                continue;
            }
            List<String> sideSlots = slotsBySide.get(seat.side());
            if (ordinals[i] >= sideSlots.size() || warmedVariants.add(sideSlots.get(ordinals[i]))) {
                roster.add(seat.seatId());
            }
        }
        return roster;
    }

    /**
     * The real-engine entry (the ONE production caller): builds the world/step pair on the
     * live binding, resolves the warm-up roster ({@link #warmupSeatIds}) and a per-pass schedule mint
     * exactly as {@code serveNPeer} does, runs {@link #run}, and prints the grade-anchor row.
     * A boot without a descriptor (the fallback 1v1 lane) has no seat fly-ins and no schedule:
     * it still runs its t=0 rows. Compile/classload deltas come from the platform beans (the
     * standard {@link CompilationMXBean} exposes total time only — per-method counts are the
     * rig's {@code jdk.Compilation} JFR read); the process-CPU clock is the JDK's
     * {@code com.sun.management.OperatingSystemMXBean} when present. A JVM without compile-time
     * monitoring runs every pass (row {@code compile_ms=-1}).
     */
    public static Receipt runOnRealEngine(EngineCombatBinding binding, NPeerHooks hooks,
            float fixedDt, int tickBudget) throws Exception {
        return runOnRealEngine(binding, hooks, fixedDt, tickBudget, MAX_PASSES, QUIET_COMPILE_MILLIS,
                WALL_CAP_MILLIS);
    }

    /**
     * {@link #runOnRealEngine(EngineCombatBinding, NPeerHooks, float, int)} with the pass cap, the
     * quiet threshold and the per-pass wall cap supplied: a re-simulation forces the RECORDED pass
     * count (a quiet threshold that never fires, a wall cap that never cuts a pass), so its roster
     * is fielded after the same passes as the recording's. The normal boot passes the constants and
     * prints the identical row.
     */
    public static Receipt runOnRealEngine(EngineCombatBinding binding, NPeerHooks hooks,
            float fixedDt, int tickBudget, int maxPasses, long quietCompileMillis, long wallCapMillis)
            throws Exception {
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        MatchDescriptor descriptor = RealEngineDemoRoster.deployedDescriptorOrNull();
        // One fly-in per distinct seat hull; the PILOT seat
        // count rides the grade row beside it so a boot log shows the dedup.
        List<Integer> hullSeatIds = List.of();
        int pilotSeats = 0;
        Supplier<WaveSchedule> scheduleMint = () -> null;
        if (descriptor != null) {
            hullSeatIds = warmupSeatIds(descriptor);
            for (MatchDescriptor.Seat seat : descriptor.seats()) {
                if (seat.role() == SeatRole.PILOT) {
                    pilotSeats++;
                }
            }
            String shape = descriptor.shape();
            int ticksPerSecond = (int) Math.round(1.0 / fixedDt);
            scheduleMint = () -> WaveSchedule.forShape(shape, ticksPerSecond,
                    PersistentPilotIntentCombatServer.resolveFortressDpTotal(),
                    PersistentPilotIntentCombatServer.resolveFortressWaveScale(),
                    hooks.fortressDp(),
                    PersistentPilotIntentCombatServer.resolveWaveCategory());
        }
        int waveSpawnPerTick = ServerConfig.resolveInt(
                "cc.serve.waveSpawnPerTick", "CC_SERVE_WAVE_SPAWN_PER_TICK",
                "serve.waveSpawnPerTick", 1);
        CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        boolean compileClock = compilation != null
                && compilation.isCompilationTimeMonitoringSupported();
        LongSupplier compileTimeOrNull = compileClock ? compilation::getTotalCompilationTime : null;
        LongSupplier processCpuOrNull = null;
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
            processCpuOrNull = () -> os.getProcessCpuTime() / 1_000_000L;
        }
        long compileBefore = compileClock ? compilation.getTotalCompilationTime() : -1L;
        long classesBefore = classes.getTotalLoadedClassCount();
        Receipt receipt = run(step, hooks, hullSeatIds, scheduleMint, fixedDt, tickBudget,
                maxPasses, quietCompileMillis, wallCapMillis, waveSpawnPerTick,
                COMPILE_QUIESCE, compileTimeOrNull, processCpuOrNull,
                RealEngineCombatBindingFactory::resetForRedeploy,
                (IntConsumer) pass -> CombatRngRearm.rearm(0, CombatRngRearm.SITE_WARMUP, pass));
        long compileDelta = compileClock ? compilation.getTotalCompilationTime() - compileBefore : -1L;
        StringBuilder perPass = new StringBuilder();
        for (Long passCompile : receipt.passCompileMillis()) {
            perPass.append(perPass.length() == 0 ? "" : ",").append(passCompile);
        }
        System.out.println("npeer-serve warmup_round ticks=" + receipt.ticks()
                + " budget=" + tickBudget
                + " passes=" + receipt.passes() + "/" + maxPasses
                + " pass_compile_ms=" + perPass
                + " fly_ins=" + receipt.flyIns()
                + " warm_seats=" + hullSeatIds.size() + "/" + pilotSeats
                + " spawned=" + receipt.spawned()
                + " wall_ms=" + receipt.wallMillis()
                + " worst_ms=" + String.format(Locale.ROOT, "%.2f", receipt.worstMillis())
                + " ge80=" + receipt.slowTicks()
                + " quiesce_ms=" + receipt.quiesceMillis()
                + " compile_ms=" + compileDelta
                + " classes_loaded=" + (classes.getTotalLoadedClassCount() - classesBefore)
                + " failed=" + receipt.failed()
                + " reset=ok");
        return receipt;
    }

    /**
     * The game-free body (contract-pinned). Per pass: a FRESH schedule from {@code scheduleMint}
     * (may return null = schedule-less), the fly-ins, the unpaced tick loop with the due-wave
     * drain, then the reset — ALWAYS the reset, exactly once per pass that started, even after a
     * fail-soft throw (which also ends the warm-up: the remaining passes are skipped); a reset
     * throw propagates. After each reset the compile-quiesce wait polls
     * {@code compileTimeOrNull} / {@code processCpuOrNull} ({@code null} = not watched) per
     * {@code quiesce}. Passes continue while the pass's compile-time delta exceeds
     * {@code quietCompileMillis}, up to {@code maxPasses}; without a compile clock every pass
     * runs (no quiet signal to stop on). No hook before the reset: {@link #run(CombatStepBoundary,
     * NPeerHooks, List, Supplier, float, int, int, long, long, int, Quiesce, LongSupplier,
     * LongSupplier, Reset, Runnable)} with a no-op.
     */
    public static Receipt run(CombatStepBoundary step, NPeerHooks hooks, List<Integer> hullSeatIds,
            Supplier<WaveSchedule> scheduleMint, float fixedDt, int tickBudget, int maxPasses,
            long quietCompileMillis, long wallCapMillis, int waveSpawnPerTick, Quiesce quiesce,
            LongSupplier compileTimeOrNull, LongSupplier processCpuOrNull, Reset reset) {
        return run(step, hooks, hullSeatIds, scheduleMint, fixedDt, tickBudget, maxPasses,
                quietCompileMillis, wallCapMillis, waveSpawnPerTick, quiesce, compileTimeOrNull,
                processCpuOrNull, reset, () -> { });
    }

    /**
     * The body above plus {@code beforeReset}, run IMMEDIATELY before EVERY pass's reset — a
     * failed pass's too, since its reset still runs. WHY: the LAST pass's reset fields round 0's
     * roster (ship ids, AI interval phases, the deploy shuffle all draw there), and which pass is
     * last depends on JIT timing; a hook that re-arms the RNG before every reset makes whichever
     * pass ends up last build the roster from the same re-armed state. A hook throw propagates
     * like a reset throw.
     */
    public static Receipt run(CombatStepBoundary step, NPeerHooks hooks, List<Integer> hullSeatIds,
            Supplier<WaveSchedule> scheduleMint, float fixedDt, int tickBudget, int maxPasses,
            long quietCompileMillis, long wallCapMillis, int waveSpawnPerTick, Quiesce quiesce,
            LongSupplier compileTimeOrNull, LongSupplier processCpuOrNull, Reset reset,
            Runnable beforeReset) {
        return run(step, hooks, hullSeatIds, scheduleMint, fixedDt, tickBudget, maxPasses,
                quietCompileMillis, wallCapMillis, waveSpawnPerTick, quiesce, compileTimeOrNull,
                processCpuOrNull, reset, (IntConsumer) pass -> beforeReset.run());
    }

    /**
     * The body above with {@code beforeReset} told the 0-based index of the pass whose reset follows:
     * the serve's RNG re-arm salts each pass's seed by it, so no two passes re-mint the same roster
     * ids.
     */
    public static Receipt run(CombatStepBoundary step, NPeerHooks hooks, List<Integer> hullSeatIds,
            Supplier<WaveSchedule> scheduleMint, float fixedDt, int tickBudget, int maxPasses,
            long quietCompileMillis, long wallCapMillis, int waveSpawnPerTick, Quiesce quiesce,
            LongSupplier compileTimeOrNull, LongSupplier processCpuOrNull, Reset reset,
            IntConsumer beforeReset) {
        long startNanos = System.nanoTime();
        int ticks = 0;
        int passesRun = 0;
        int flyIns = 0;
        int spawned = 0;
        double worstMillis = 0.0d;
        int slowTicks = 0;
        long quiesceMillis = 0L;
        boolean failed = false;
        List<Long> passCompileMillis = new ArrayList<>();
        for (int pass = 0; pass < maxPasses && !failed; pass++) {
            long passStartNanos = System.nanoTime();
            long passCompileBefore = compileTimeOrNull == null ? 0L : compileTimeOrNull.getAsLong();
            try {
                WaveSchedule schedule = scheduleMint.get();
                if (schedule != null) {
                    schedule.armTimer(0L);
                }
                for (Integer seatId : hullSeatIds) {
                    hooks.flyIn().deploy(seatId, null);
                    flyIns++;
                }
                passesRun++;
                long wallCapNanos = wallCapMillis * 1_000_000L;
                for (long tick = 0; tick < tickBudget; tick++) {
                    if (System.nanoTime() - passStartNanos > wallCapNanos) {
                        System.out.println("npeer-serve warmup_round wall_cap_hit ms=" + wallCapMillis
                                + " pass=" + pass + " tick=" + tick);
                        break;
                    }
                    if (schedule != null) {
                        for (WaveSchedule.WaveRow row : schedule.drainDue(tick, waveSpawnPerTick)) {
                            hooks.waveSpawn().spawn(row.side(), row.variantId(), row.shipName());
                            spawned++;
                        }
                    }
                    long tickStart = System.nanoTime();
                    step.step(fixedDt, tick);
                    double tickMillis = (System.nanoTime() - tickStart) / 1_000_000.0d;
                    ticks++;
                    if (tickMillis > worstMillis) {
                        worstMillis = tickMillis;
                    }
                    if (tickMillis >= SLOW_TICK_MILLIS) {
                        slowTicks++;
                    }
                }
            } catch (Exception | LinkageError warmFailure) {
                failed = true;
                System.out.println("npeer-serve warmup_round_failed pass=" + pass + " tick=" + ticks
                        + " err=" + warmFailure.getClass().getName() + ": "
                        + warmFailure.getMessage());
                // Full stack, not message-only: a warm-up throw is the FIRST place an engine seam
                // surfaces headless (the served round would hit the same seam in-round, where the
                // stack IS captured), and the omega F3 triage stalled on exactly this gap — the
                // message named the null (Global.getSector()) but not the caller.
                warmFailure.printStackTrace(System.out);
            }
            beforeReset.accept(pass);
            reset.reset(new TreeSet<>());
            quiesceMillis += awaitCompileQuiesce(compileTimeOrNull, processCpuOrNull, quiesce, pass);
            long passCompile = compileTimeOrNull == null ? -1L
                    : compileTimeOrNull.getAsLong() - passCompileBefore;
            passCompileMillis.add(passCompile);
            if (compileTimeOrNull != null && passCompile <= quietCompileMillis) {
                break;
            }
        }
        long wallMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        lastPasses = passesRun;
        lastTicks = ticks;
        return new Receipt(ticks, passesRun, List.copyOf(passCompileMillis), flyIns, spawned,
                wallMillis, worstMillis, slowTicks, quiesceMillis, failed);
    }

    /**
     * Block until neither the JIT's cumulative compile-time counter nor the process CPU clock
     * has moved for {@code quiesce.stableMillis()} (polled every {@code pollMillis}), or the cap;
     * returns the wall spent. Both clocks {@code null} = nothing to wait on (0 ms); a lone CPU
     * clock still counts (a compile in flight burns a core before the counter moves). An
     * interrupt ends the wait.
     */
    public static long awaitCompileQuiesce(LongSupplier compileTimeOrNull, LongSupplier processCpuOrNull,
            Quiesce quiesce, int pass) {
        if ((compileTimeOrNull == null && processCpuOrNull == null) || quiesce == null) {
            return 0L;
        }
        long start = System.nanoTime();
        long lastCompile = compileTimeOrNull == null ? 0L : compileTimeOrNull.getAsLong();
        long lastCpu = processCpuOrNull == null ? 0L : processCpuOrNull.getAsLong();
        long busyCpuMillisPerPoll = Math.round(quiesce.busyCpuFraction() * quiesce.pollMillis());
        long stillSince = start;
        while (true) {
            try {
                Thread.sleep(quiesce.pollMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = System.nanoTime();
            boolean moving = false;
            if (compileTimeOrNull != null) {
                long compile = compileTimeOrNull.getAsLong();
                moving |= compile != lastCompile;
                lastCompile = compile;
            }
            if (processCpuOrNull != null) {
                long cpu = processCpuOrNull.getAsLong();
                moving |= cpu - lastCpu > busyCpuMillisPerPoll;
                lastCpu = cpu;
            }
            if (moving) {
                stillSince = now;
            } else if (now - stillSince >= quiesce.stableMillis() * 1_000_000L) {
                break;
            }
            if (now - start >= quiesce.capMillis() * 1_000_000L) {
                System.out.println("npeer-serve warmup_round quiesce_cap_hit ms=" + quiesce.capMillis()
                        + " pass=" + pass);
                break;
            }
        }
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
