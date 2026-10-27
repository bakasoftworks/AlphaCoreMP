package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatRosterView;
import cc.starsector.server.adapter.CombatWorldState;

/**
 * {@link CombatWorldState} read from a live combat engine through
 * {@link EngineCombatBinding}.
 *
 * <p>{@link #entityCount()} reflects the authoritative repository's ship list: the
 * closed rung-7 roster contains only the installed ship(s) (no projectiles,
 * asteroids, or other entities are seeded), so the entity count equals the ship
 * count for this milestone. {@link #combatSpaceReady()} and {@link #timeMult()} are
 * live reads; {@link #fogOfWarReady(int)} reflects the caller's asserted fog
 * binding, justified by the unpaused step that
 * dereferences both owners' fog-of-war without faulting.
 */
public final class EngineBackedCombatWorldState implements CombatWorldState {

    private final EngineCombatBinding binding;
    private final CombatRosterView roster;

    public EngineBackedCombatWorldState(EngineCombatBinding binding) {
        this.binding = binding;
        this.roster = new EngineBackedCombatRosterView(binding);
    }

    @Override
    public int entityCount() {
        return binding.shipListSize();
    }

    @Override
    public CombatRosterView roster() {
        return roster;
    }

    @Override
    public int fleetManagerCount() {
        return binding.fleetManagerCount();
    }

    @Override
    public boolean combatSpaceReady() {
        return binding.combatMapBound();
    }

    @Override
    public boolean fogOfWarReady(int owner) {
        return (owner == OWNER_FRIENDLY || owner == OWNER_ENEMY) && binding.fogBound();
    }

    @Override
    public float timeMult() {
        return binding.timeMult();
    }
}
