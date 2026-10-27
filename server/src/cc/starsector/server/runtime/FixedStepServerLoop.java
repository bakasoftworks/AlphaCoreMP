package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatClock;
import cc.starsector.server.adapter.CombatRosterView;
import cc.starsector.server.adapter.CombatStateHashBoundary;
import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.adapter.CombatWorldState;
import cc.starsector.server.adapter.TreatedOuterWrapperCombatStepBoundary;
import java.util.Objects;

/**
 * Headless fixed-step server-loop skeleton (ladder rung 8 target).
 *
 * <p>Owns {@code sim_tick} and the fixed step and drives a {@link CombatAdvance}
 * hook at a stable cadence. The engine IS wired: the engine-backed
 * {@link #run(long, CombatStepBoundary)} / {@link #runOfflineStep} paths drive a real
 * {@link CombatStepBoundary}, and {@code PersistentLiveServerRunner} constructs this loop and
 * drives it with an {@code EngineBackedCombatStepBoundary}. The no-arg / {@code NO_OP}
 * default advance is the smoke-only path — it runs and exits cleanly without any
 * render/audio/UI/local-player dependency. It also implements {@link CombatClock} so downstream
 * code can read fixed-step values and be re-pointed at an engine-backed clock.
 *
 * <p>The clock's fixed step is the step target for the engine's inner simulation
 * step once mapped: {@link #run(long, CombatStepBoundary)} drives a
 * {@link CombatStepBoundary} (which bypasses the input-coupled outer wrapper,
 * and drives the inner step directly) at each fixed tick.
 *
 *
 * <p>Encodes the timing decision (fixed simulation step, configurable network tick): step
 * amount and tick count are explicit and versioned; nothing silently turns the
 * {@code 7} ms trace validation value into a product tickrate.
 */
public final class FixedStepServerLoop implements CombatClock {

    /** One fixed combat step. Driven by the engine adapter via the engine-backed run paths. */
    @FunctionalInterface
    public interface CombatAdvance {
        void advance(float stepAmount, long simTick);
    }

    private static final CombatAdvance NO_OP = (stepAmount, simTick) -> { };

    private final float fixedDt;
    private long simTick;
    private double simTime;

    public FixedStepServerLoop(float fixedDt) {
        if (!(fixedDt > 0f)) {
            throw new IllegalArgumentException("fixedDt must be > 0; got " + fixedDt);
        }
        this.fixedDt = fixedDt;
    }

    /** Run a bounded number of fixed steps with a no-op advance (smoke use). */
    public void run(long ticks) {
        run(ticks, NO_OP);
    }

    /** Run a bounded number of fixed steps, calling {@code advance} each step. */
    public void run(long ticks, CombatAdvance advance) {
        if (ticks < 0L) {
            throw new IllegalArgumentException("ticks must be >= 0; got " + ticks);
        }
        Objects.requireNonNull(advance, "advance");
        for (long i = 0; i < ticks; i++) {
            advance.advance(fixedDt, simTick);
            simTick++;
            simTime += fixedDt;
        }
    }

    /**
     * Run a bounded number of fixed steps, driving {@code step} each tick. This
     * is the engine-backed advance path: the clock owns the fixed step and the
     * tick count; the boundary owns the inner simulation step and the input-free
     * re-implementation of the outer wrapper (the original wrapper bypassed).
     */
    public void run(long ticks, CombatStepBoundary step) {
        Objects.requireNonNull(step, "step");
        run(ticks, (CombatAdvance) step::step);
    }

    /**
     * Run one bounded direct inner step and return the product-owned offline
     * snapshot/result for that completed tick.
     *
     * <p>This is the rung-7 product contract. It refuses to snapshot a boundary
     * that still depends on the input-coupled outer wrapper, then captures the
     * post-step world counts and adapter-supplied state hash.
     */
    public OfflineCombatStepResult runOfflineStep(
            CombatStepBoundary step,
            CombatStateHashBoundary stateHashBoundary) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        if (!step.directInnerStepReady()) {
            throw new IllegalStateException("direct inner step boundary is not ready");
        }

        return runStepAndSnapshot(
                step.worldState(),
                step.effectiveStepAmount(fixedDt),
                stateHashBoundary,
                startedTick -> step.step(fixedDt, startedTick));
    }

    /**
     * Run one treated outer-wrapper step and return the product-owned offline
     * snapshot/result for that completed tick.
     *
     * <p>This does not weaken {@link CombatStepBoundary}: an outer-wrapper step
     * remains input-coupled, and is publishable only under the explicit runtime
     * treatment contract represented by
     * {@link TreatedOuterWrapperCombatStepBoundary}.
     */
    public OfflineCombatStepResult runTreatedOuterWrapperOfflineStep(
            TreatedOuterWrapperCombatStepBoundary step,
            CombatStateHashBoundary stateHashBoundary) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        if (!step.treatedOuterWrapperPublicationReady()) {
            throw new IllegalStateException("treated outer wrapper boundary is not ready");
        }

        return runStepAndSnapshot(
                step.worldState(),
                step.effectiveStepAmount(fixedDt),
                stateHashBoundary,
                startedTick -> step.step(fixedDt, startedTick));
    }

    private OfflineCombatStepResult runStepAndSnapshot(
            CombatWorldState worldState,
            float effectiveStepAmount,
            CombatStateHashBoundary stateHashBoundary,
            TickAdvance advance) {
        long startedTick = simTick;
        float fixedStepAmount = fixedDt;
        long stepStartedAtNanos = System.nanoTime();
        advance.step(startedTick);
        long stepCompletedAtNanos = System.nanoTime();

        simTick++;
        simTime += fixedStepAmount;

        CombatRosterView roster = worldState.roster();
        long stateHash = stateHashBoundary.stateHash(worldState);
        long snapshotCompletedAtNanos = System.nanoTime();

        return new OfflineCombatStepResult(
                startedTick,
                simTick,
                fixedStepAmount,
                effectiveStepAmount,
                worldState.entityCount(),
                roster.shipCount(),
                roster.shipNetworkIdCount(),
                roster.firstShipNetworkId(),
                roster.lastShipNetworkId(),
                stateHash,
                stepStartedAtNanos,
                stepCompletedAtNanos,
                snapshotCompletedAtNanos);
    }

    /**
     * Run a bounded offline loop and publish one snapshot/result per completed
     * direct inner step.
     *
     * <p>This is the rung-8 publication contract. It keeps the loop and result
     * delivery in product-owned code while the real engine bridge remains behind
     * {@link CombatStepBoundary} and {@link CombatStateHashBoundary}.
     */
    public void runOfflineSteps(
            long ticks,
            CombatStepBoundary step,
            CombatStateHashBoundary stateHashBoundary,
            OfflineCombatStepSink sink) {
        if (ticks < 0L) {
            throw new IllegalArgumentException("ticks must be >= 0; got " + ticks);
        }
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        Objects.requireNonNull(sink, "sink");
        for (long i = 0; i < ticks; i++) {
            sink.publish(runOfflineStep(step, stateHashBoundary));
        }
    }

    /**
     * Run a bounded treated outer-wrapper loop and publish one snapshot/result
     * per completed step.
     */
    public void runTreatedOuterWrapperOfflineSteps(
            long ticks,
            TreatedOuterWrapperCombatStepBoundary step,
            CombatStateHashBoundary stateHashBoundary,
            OfflineCombatStepSink sink) {
        if (ticks < 0L) {
            throw new IllegalArgumentException("ticks must be >= 0; got " + ticks);
        }
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stateHashBoundary, "stateHashBoundary");
        Objects.requireNonNull(sink, "sink");
        for (long i = 0; i < ticks; i++) {
            sink.publish(runTreatedOuterWrapperOfflineStep(step, stateHashBoundary));
        }
    }

    @FunctionalInterface
    private interface TickAdvance {
        void step(long simTick);
    }

    @Override public long simTick() { return simTick; }
    @Override public double simTime() { return simTime; }
    @Override public float fixedDt() { return fixedDt; }
    @Override public float currentStepAmount() { return fixedDt; }
    @Override public double accumulator() { return 0d; }
    @Override public int catchUpCount() { return 0; }
    @Override public boolean overloaded() { return false; }
}
