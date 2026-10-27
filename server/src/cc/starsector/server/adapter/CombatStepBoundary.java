package cc.starsector.server.adapter;

/**
 * Project-owned headless combat-step boundary.
 *
 * <p>The engine exposes two step entry points with the same descriptor: an
 * input-coupled outer step wrapper and an inner simulation step. The outer
 * wrapper carries an unconditional local-input crossing that has no place in a
 * headless server. This boundary therefore <em>bypasses the
 * outer wrapper and drives the inner simulation step directly</em>, and
 * re-implements in project-owned code the wrapper's cheap, input-free work:
 * scaling the step amount by the time multiplier and the configured combat-speed
 * multiplier, the engine's global AI pre/post bookkeeping, and post-step holo
 * cleanup. It supplies a fixed-step amount from {@link CombatClock}.
 *
 * <p>The inner simulation step itself was confirmed headless-clean (no native
 * crossing in its call surface); the direct runtime path was
 * then closed for paused, empty-roster unpaused, and one spec-resolved ship
 * unpaused steps.
 * Per-ship AI decisions reached during the step are modelled by
 * {@link AiAdvanceBoundary}; the world state the step reads is owned by
 * {@link CombatWorldState}.
 *
 * <p>This is the concrete realization of that input-crossing hook's promotion target.
 * Implementations are the simulation adapter's bridge to the engine; this
 * interface keeps the engine-internal step identity hidden.
 */
public interface CombatStepBoundary {

    /** The authoritative world state this step reads and advances. */
    CombatWorldState worldState();

    /**
     * Amount actually handed to the inner simulation step for a given fixed
     * step: {@code fixedDt * worldState().timeMult() * combatSpeedMult}. This is
     * the project-owned re-implementation of the outer wrapper's scaling, with no
     * dependency on local input.
     */
    float effectiveStepAmount(float fixedDt);

    /**
     * Advance the simulation by one fixed step at {@code simTick}: apply global
     * AI pre-step bookkeeping, drive the inner simulation step over
     * {@link #worldState()} with {@link #effectiveStepAmount(float)}, then apply
     * AI post-step bookkeeping and holo cleanup. Must never invoke the
     * input-coupled outer step wrapper.
     */
    void step(float fixedDt, long simTick);

    /**
     * Invariant guard: {@code true} iff the outer wrapper's local-input crossing
     * is bypassed for this boundary. A headless server
     * implementation must always report {@code true}.
     */
    boolean inputCouplingBypassed();

    /**
     * True when this boundary is ready for the admitted direct unpaused inner
     * step. This guard encodes the key product invariant: ready means bypassing
     * the input-coupled wrapper, never calling it.
     */
    default boolean directInnerStepReady() {
        return inputCouplingBypassed() && worldState().populatedForUnpausedStep();
    }

    /**
     * True when this boundary is ready for the closed one-ship direct inner step
     * proven.
     */
    default boolean singleShipDirectInnerStepReady() {
        return directInnerStepReady() && worldState().populatedForSingleShipStep();
    }

    // The step bracket's sub-stamps, so a stall row can say whether a spike sat
    // INSIDE the engine's own inner advance or in project-owned post-step work (the
    // engine-internal quiet-tick lead). 0 = the implementation does not stamp (game-free lanes).

    /** Nanos the last {@link #step} spent inside the engine's inner advance alone. */
    default long lastEngineAdvanceNanos() {
        return 0L;
    }

    /** Nanos the last {@link #step} spent in project-owned post-step work after the advance. */
    default long lastPostStepNanos() {
        return 0L;
    }

    /**
     * Nanos the last {@link #step} spent inside the fast-time reinject shim's {@code advance}
     * (our only code the engine calls back INTO during its advance — a slice of
     * {@link #lastEngineAdvanceNanos()}, not additive to it).
     */
    default long lastReinjectNanos() {
        return 0L;
    }

    /**
     * CPU nanos the sim thread ITSELF consumed inside the last engine inner advance
     * ({@code ThreadMXBean.getCurrentThreadCpuTime} bracket). Read next
     * to {@link #lastEngineAdvanceNanos()} (wall): wall far above cpu = the thread was DESCHEDULED
     * (OS contention — priority/affinity work, not engine RE); wall ≈ cpu = engine compute (read
     * the JFR at that tick). 0 = the implementation does not stamp (game-free lanes); -1 = the
     * JVM cannot measure thread CPU time. Windows granularity = the scheduler tick (~1–15.6 ms),
     * so it is a ≥100 ms-spike instrument, never a per-tick one.
     */
    default long lastEngineAdvanceCpuNanos() {
        return 0L;
    }

    /**
     * Instrumentation hook, never behavior: the OUTERMOST step wrapper announces the step BEFORE
     * its own pre-step work (the pilot-intent apply loop), so the off-frame stall sampler's live
     * window opens there instead of at the engine advance — three live runs stalled
     * 74-151 ms at round-0 tick 1, ~96 ms of it in that loop, invisible to a window opened later.
     * Default no-op (game-free lanes have no sampler).
     */
    default void beginLiveStep() {
    }
}
