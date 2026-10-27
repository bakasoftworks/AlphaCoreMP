package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's combat-readiness (CR) clock at a
 * simulation step.
 *
 * <p>CR is the engine's fatigue meter: peak-performance time (PPT) counts down
 * while a ship is deployed against countable opposition, then CR itself decays
 * and degrades the hull toward malfunctions. A predict client's LOCAL engine
 * scans its own (different) roster for that in-combat gate — locked carriers and
 * absent seats freeze the local clock while the authoritative ship fatigues
 * — so the server carries the authoritative CR facts and the
 * client pins its local readouts to them. This view carries only scalars; the
 * engine ship, its mutable stats, and the hull spec stay inside the bridge.
 */
public interface ShipCrView {

    /** Stable ship identity in the same repository order as {@link ShipMotionView}. */
    long shipId();

    /** Current combat readiness (the 0..1 fraction the HUD shows as CR%). */
    float currentCr();

    /**
     * RAW deployed-seconds accumulator ({@code ShipAPI.getTimeDeployedForCRReduction()}).
     * Never "PPT remaining" — the consumer subtracts from its local peak itself.
     *
     */
    float timeDeployed();

    /**
     * Effective peak-performance duration in seconds (stats-modified base + no-CR-loss
     * time). Never-lose-CR hulls read ≥ 1e5 s by design — finite but huge.
     */
    float peakCr();

    /**
     * Sentinel {@link #fighterReplacementPct()}: the read is unavailable on this bridge
     * (handle unbound / non-finite read) - the wire then carries ABSENT, never a fake 0 %.
     */
    int NO_FIGHTER_REPLACEMENT_PCT = -1;

    /**
     * The ship's SHARED fighter-replacement rate exactly as the vanilla fighter panel PRINTS
     * it ({@code (int)(ShipAPI.getSharedFighterReplacementRate() * 100)},
     * truncation, clamped 0..100), or
     * {@link #NO_FIGHTER_REPLACEMENT_PCT}. Rides the CR view because that is the existing
     * per-ship scalar read already on the sim thread (one extra public getter, no new walk);
     * a bay-less hull reads 100 (the engine returns 1.0 for it).
     */
    int fighterReplacementPct();
}
