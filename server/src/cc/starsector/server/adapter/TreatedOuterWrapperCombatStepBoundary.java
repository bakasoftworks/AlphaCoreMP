package cc.starsector.server.adapter;

/**
 * Headless combat-step boundary for the treated outer {@code CombatEngine.advance}
 * wrapper.
 *
 * <p>This is deliberately separate from {@link CombatStepBoundary}. A boundary
 * that calls the outer wrapper cannot honestly claim
 * {@link CombatStepBoundary#inputCouplingBypassed()}; it is publishable only
 * when the runtime has installed the scoped keyboard zero-state treatment that
 * owns the wrapper's local-input reads.
 */
public interface TreatedOuterWrapperCombatStepBoundary {

    /** The authoritative world state this step reads and advances. */
    CombatWorldState worldState();

    /**
     * Effective simulation amount expected after the outer wrapper applies time
     * and combat-speed scaling to {@code fixedDt}.
     */
    float effectiveStepAmount(float fixedDt);

    /** Advance the simulation by one fixed step through the treated wrapper. */
    void step(float fixedDt, long simTick);

    /** True only when the scoped outer-wrapper keyboard treatment is installed. */
    boolean keyboardTreatmentInstalled();

    /** True when this treated outer-wrapper boundary is ready for publication. */
    default boolean treatedOuterWrapperPublicationReady() {
        return keyboardTreatmentInstalled() && worldState().populatedForUnpausedStep();
    }
}
