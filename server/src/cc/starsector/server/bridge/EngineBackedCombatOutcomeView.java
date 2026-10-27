package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatOutcomeView;

/**
 * Immutable {@link CombatOutcomeView} snapshot read from the live engine bridge:
 * the public {@code CombatEngineAPI.getWinningSideId()} and
 * {@code isCombatOver()} reads, with the engine-internal {@code CombatEngine} kept
 * behind the binding. Carries the two crossed scalars verbatim; the render-free
 * {@link #decided()} match-end signal is derived on the interface.
 */
public final class EngineBackedCombatOutcomeView implements CombatOutcomeView {

    private final int winningSideId;
    private final boolean combatOver;

    public EngineBackedCombatOutcomeView(int winningSideId, boolean combatOver) {
        this.winningSideId = winningSideId;
        this.combatOver = combatOver;
    }

    @Override
    public int winningSideId() {
        return winningSideId;
    }

    @Override
    public boolean combatOver() {
        return combatOver;
    }
}
