package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatRosterView;

/**
 * {@link CombatRosterView} read from a live engine object repository.
 *
 * <p>Reads the ship roster through {@link EngineCombatBinding}. In the closed
 * rung-6c/7 gate every installed ship is spec-resolved by construction (the scout
 * only admits a spec-resolved {@code Ship}), so {@link #specResolvedShipCount()}
 * mirrors the live ship count; a mixed roster would need its own resolved-spec
 * read once larger rosters are admitted.
 */
public final class EngineBackedCombatRosterView implements CombatRosterView {

    private final EngineCombatBinding binding;

    public EngineBackedCombatRosterView(EngineCombatBinding binding) {
        this.binding = binding;
    }

    @Override
    public int shipCount() {
        return binding.shipListSize();
    }

    @Override
    public int specResolvedShipCount() {
        return binding.shipListSize();
    }

    @Override
    public boolean playerShipBound() {
        return binding.playerShipBound();
    }
}
