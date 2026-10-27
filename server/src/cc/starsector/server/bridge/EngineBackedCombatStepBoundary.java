package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.adapter.CombatWorldState;

/**
 * {@link CombatStepBoundary} over a live combat engine.
 *
 * <p>This is the concrete realization of the promotion target:
 * {@link #step(float, long)} drives the engine's inner simulation step directly
 * through {@link EngineCombatBinding#invokeAdvanceInner(float)} and never touches
 * the input-coupled outer wrapper, so {@link #inputCouplingBypassed()} is always
 * {@code true}. The step amount handed to the engine is the project-owned
 * re-implementation of the wrapper's scaling
 * ({@code fixedDt * timeMult * combatSpeedMult}); local input is never read.
 */
public final class EngineBackedCombatStepBoundary implements CombatStepBoundary {

    private final CombatWorldState worldState;
    private final EngineCombatBinding binding;
    // Diagnostic, default-OFF: when -Dcc.server.crReadback=true, log each live ship's
    // combat-readiness right before and across the first advances, to measure whether the
    // deploy CR (0.7) survives executeAdds or is re-derived to ~0 (the suspected
    // cause of ships retreating). Pure reads via the binding; no effect on any other lane.
    private final boolean crReadback = cc.starsector.server.runtime.ServerConfig.resolveBoolean(
            "cc.server.crReadback", "CC_SERVER_CR_READBACK", "server.crReadback", false);

    // sim-thread CPU time across the engine advance (adv_cpu= on the stall row,
    // the preemption-vs-compute discriminator). Two thread-CPU reads per tick (~1 µs
    // each: GetThreadTimes / clock_gettime); the bean is resolved ONCE here. Fail-closed: the
    // sentinel is -1L NANOS, and both print sites route through advCpuMs() so the row reads
    // adv_cpu=-1.00 (never the -0.00 a bare /1e6 would print) — an instrument
    // can never refuse the serve.
    private final java.lang.management.ThreadMXBean threadCpu;
    private final boolean threadCpuSupported;

    public EngineBackedCombatStepBoundary(CombatWorldState worldState, EngineCombatBinding binding) {
        this.worldState = worldState;
        this.binding = binding;
        java.lang.management.ThreadMXBean bean = null;
        boolean supported = false;
        try {
            bean = java.lang.management.ManagementFactory.getThreadMXBean();
            supported = bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
        } catch (RuntimeException | LinkageError e) {
            supported = false;
        }
        this.threadCpu = bean;
        this.threadCpuSupported = supported;
    }

    @Override
    public CombatWorldState worldState() {
        return worldState;
    }

    @Override
    public float effectiveStepAmount(float fixedDt) {
        return fixedDt * worldState.timeMult() * binding.combatSpeedMult();
    }

    @Override
    public void step(float fixedDt, long simTick) {
        if (crReadback && simTick == 0L) {
            // Pre-drain: ships are still on the pending-add queue (executeAdds has not run),
            // so this typically reads ships=0 — it documents the drain boundary.
            binding.logCombatReadinessReadback("pre-advance", simTick);
        }
        // Bracket the engine advance vs our post-step so the serve loop's stall
        // row splits adv= into adv_eng= / adv_post= / ftreinj= (two nanoTime reads per tick).
        long advanceStartNanos = System.nanoTime();
        // Publish the LIVE step start for the off-frame
        // SimStallStackSampler (one volatile write here or in beginLiveStep(), one clear at the
        // end of step()) — with beginLiveStep's nanoTime + the two guard reads, the sim thread's
        // whole jstack-on-stall cost.
        // Stamp only if no wrapper opened the window
        // earlier via beginLiveStep() (0L = between steps; never <= 0L — a nanoTime origin is
        // arbitrary, a negative stamp is legal).
        if (liveStepStartNanos == 0L) {
            liveStepStartNanos = advanceStartNanos;
        }
        long cpuStartNanos = threadCpuSupported ? threadCpu.getCurrentThreadCpuTime() : -1L;
        // The engaged-carrier band pre-snap runs on the SAME list the
        // engine is about to advance, AFTER staged pilot intents applied (PPICS step order) and
        // BEFORE Ship.advance — the dt→0 limit of the vanilla band law (EngagedCarrierBandSnap).
        if (engagedCarrierBandSnap) {
            EngagedCarrierBandSnap.apply(binding.shipList(), effectiveStepAmount(fixedDt));
        }
        binding.invokeAdvanceInner(effectiveStepAmount(fixedDt));
        long cpuEndNanos = threadCpuSupported ? threadCpu.getCurrentThreadCpuTime() : -1L;
        long advanceEndNanos = System.nanoTime();
        // post-step: repace piloted hulls' cloak trackers to wall time.
        // Lives HERE (not in the serve wrapper) so every consumer of this boundary — the serve
        // loop AND the phase-cooldown probe — exercises the identical production path. Knob-gated
        // + fail-closed inside; the wall baseline is the same effective step a non-fast ship gets.
        binding.pilotedCloakWallClockPostStep(effectiveStepAmount(fixedDt));
        // side-1 deployment-point pool rollup (two public reads / 600 ticks).
        binding.strengthPoolRollup(simTick);
        // Headless fragment-swarm census; OFF = one static boolean test.
        if (FragmentSwarmCensusProbe.ARMED) {
            FragmentSwarmCensusProbe.sample(binding.shipList(), simTick);
        }
        // Headless mote census (the Ziggurat generator-stuck diff); same law.
        if (MoteCensusProbe.ARMED) {
            MoteCensusProbe.sample(binding.shipList(), simTick);
        }
        // Headless Shrouded Dweller census (alive/hulk/mounts/deaths); same law.
        if (DwellerCensusProbe.ARMED) {
            DwellerCensusProbe.sample(binding.shipList(), simTick);
        }
        // Headless flare glow census (command flag vs the engine glow's own flag); same law.
        if (FlareGlowProbe.ARMED) {
            FlareGlowProbe.sample(binding.shipList(), simTick);
        }
        if (crReadback && isCrReadbackTick(simTick)) {
            binding.logCombatReadinessReadback("post-advance", simTick);
        }
        lastEngineAdvanceNanos = advanceEndNanos - advanceStartNanos;
        lastPostStepNanos = System.nanoTime() - advanceEndNanos;
        lastReinjectNanos = binding.drainReinjectTickNanos();
        lastEngineAdvanceCpuNanos = threadCpuSupported ? cpuEndNanos - cpuStartNanos : -1L;
        liveStepStartNanos = 0L; // Step over — the sampler sees "between steps"
    }

    // Product default ON. The ONLY off switch is the headless A/B/A probe
    // (RealEngineZeroFluxBandProbeMain) — never a knob, never a lane posture.
    private boolean engagedCarrierBandSnap = true;

    /** Probe-only A/B lever for the pre-snap (product path is always ON). */
    public EngineBackedCombatStepBoundary withEngagedCarrierBandSnap(boolean on) {
        this.engagedCarrierBandSnap = on;
        return this;
    }

    /** Pre-snaps applied since boot (the probe's witness that the snap, not luck, held the band). */
    public static long engagedCarrierPreSnapCount() {
        return EngagedCarrierBandSnap.preSnapCount();
    }

    // nanoTime at the start of the step IN PROGRESS, 0 between steps.
    // Written by the sim thread, read by the SimStallStackSampler daemon (the only cross-thread
    // field on this boundary; the sub-stamps below stay sim-thread-only).
    private volatile long liveStepStartNanos;

    /** Live step-start stamp for the off-frame stall sampler ({@code 0} = not inside a step). */
    public long liveStepStartNanos() {
        return liveStepStartNanos;
    }

    /**
     * The outer pilot step opens the sampler's live window BEFORE its intent
     * apply loop (stamp only if 0, so a second call keeps the first stamp); {@link #step}'s own
     * stamp then keeps it and the end-of-step clear closes it.
     */
    @Override
    public void beginLiveStep() {
        if (liveStepStartNanos == 0L) {
            liveStepStartNanos = System.nanoTime();
        }
    }

    // The step's sub-stamps (sim-thread-only write+read; 0 before the first step).
    private long lastEngineAdvanceNanos;
    private long lastPostStepNanos;
    private long lastReinjectNanos;
    private long lastEngineAdvanceCpuNanos; // -1 when the JVM cannot measure it

    @Override
    public long lastEngineAdvanceCpuNanos() {
        return lastEngineAdvanceCpuNanos;
    }

    /**
     * Display law for the {@code adv_cpu=} row field: the unmeasurable
     * sentinel ({@code -1L} nanos) prints as {@code -1.00}, never as the {@code -0.00} a bare
     * nanos/1e6 yields — a reader must never mistake "no instrument" for "parked" (the
     * DESCHED verdict reads {@code adv_cpu}≪wall). Both print sites (the {@code work_frame_stall}
     * row and {@code worst_frame}) route through here.
     */
    public static double advCpuMs(long advCpuNanos) {
        return advCpuNanos < 0L ? -1.0d : advCpuNanos / 1_000_000.0d;
    }

    @Override
    public long lastEngineAdvanceNanos() {
        return lastEngineAdvanceNanos;
    }

    @Override
    public long lastPostStepNanos() {
        return lastPostStepNanos;
    }

    @Override
    public long lastReinjectNanos() {
        return lastReinjectNanos;
    }

    /** Sparse CR-readback schedule across the engagement window (min_dist hit ~frame 203). */
    private static boolean isCrReadbackTick(long simTick) {
        return simTick == 0L || simTick == 1L || simTick == 2L || simTick == 5L
                || simTick == 10L || simTick == 30L || simTick == 60L
                || simTick == 120L || simTick == 200L || simTick == 300L;
    }

    @Override
    public boolean inputCouplingBypassed() {
        return true;
    }
}
