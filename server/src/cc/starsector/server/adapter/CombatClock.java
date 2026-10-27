package cc.starsector.server.adapter;

/**
 * Project-owned fixed-step provider for combat simulation.
 *
 * <p>Owns the amount passed into the engine's combat-advance boundary before any
 * movement integration, AI advance, weapon advance, collision, damage, or
 * snapshot publication for that simulation step. Render cadence, audio cadence,
 * local input polling, and network snapshot cadence do not feed this contract.
 *
 * <p>Derived from the CombatClock contract: a fixed
 * step with a configurable network tick, and the clock owns
 * the advance step amount. The {@code 7} ms trace value is validation-only, not
 * a product tickrate.
 */
public interface CombatClock {

    /** Monotonic server tick counter. */
    long simTick();

    /** Simulation time derived from completed fixed steps, not render frames. */
    double simTime();

    /** Configured fixed simulation step amount, in seconds. */
    float fixedDt();

    /** Step amount that will be passed to the next combat advance, in seconds. */
    float currentStepAmount();

    /** Accumulated wall-clock time not yet consumed by a fixed step, in seconds. */
    double accumulator();

    /** Number of catch-up steps taken this scheduling pass. */
    int catchUpCount();

    /** True when the server cannot keep up and is dropping/limiting catch-up. */
    boolean overloaded();
}
