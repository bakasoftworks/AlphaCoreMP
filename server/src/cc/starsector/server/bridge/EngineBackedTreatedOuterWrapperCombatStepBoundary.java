package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatWorldState;
import cc.starsector.server.adapter.TreatedOuterWrapperCombatStepBoundary;

/**
 * Treated outer-wrapper boundary driven by a live combat engine.
 *
 * <p>{@link #step(float, long)} calls
 * {@link EngineCombatBinding#invokeAdvanceOuter(float)} and is publishable only
 * when the caller proves the scoped keyboard treatment is installed. This keeps
 * the accepted direct-inner-step boundary honest while still allowing repeated
 * outer-wrapper publication to be evaluated by product code.
 */
public final class EngineBackedTreatedOuterWrapperCombatStepBoundary
        implements TreatedOuterWrapperCombatStepBoundary {

    private final CombatWorldState worldState;
    private final EngineCombatBinding binding;
    private final boolean keyboardTreatmentInstalled;

    public EngineBackedTreatedOuterWrapperCombatStepBoundary(
            CombatWorldState worldState,
            EngineCombatBinding binding,
            boolean keyboardTreatmentInstalled) {
        this.worldState = worldState;
        this.binding = binding;
        this.keyboardTreatmentInstalled = keyboardTreatmentInstalled;
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
        if (!keyboardTreatmentInstalled) {
            throw new IllegalStateException("keyboard treatment is not installed for treated outer wrapper");
        }
        binding.invokeAdvanceOuter(fixedDt);
    }

    @Override
    public boolean keyboardTreatmentInstalled() {
        return keyboardTreatmentInstalled;
    }
}
