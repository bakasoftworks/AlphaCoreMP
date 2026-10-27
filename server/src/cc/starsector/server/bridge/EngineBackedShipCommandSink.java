package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipCommandSink;
import java.util.Objects;

/**
 * {@link ShipCommandSink} implemented on the public {@code ShipAPI.giveCommand}
 * boundary.
 *
 * <p>The binding owns the reflective engine crossing and stable API enum values.
 * This adapter accepts mapped movement and weapon intent for the bound player
 * ship; unsupported command categories fail closed until their engine
 * boundaries are mapped.
 */
public final class EngineBackedShipCommandSink implements ShipCommandSink {

    private final EngineCombatBinding binding;

    public EngineBackedShipCommandSink(EngineCombatBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public Result submitMovement(long shipId, Source source, long simTick, Movement intent) {
        return binding.submitPlayerShipMovement(shipId, source, simTick, intent);
    }

    @Override
    public Result selectWeaponGroup(long shipId, Source source, long simTick, int group) {
        return binding.selectPlayerShipWeaponGroup(shipId, source, simTick, group);
    }

    @Override
    public Result submitFire(long shipId, Source source, long simTick, boolean fire) {
        return binding.submitPlayerShipFire(shipId, source, simTick, fire);
    }

    @Override
    public Result activateSystem(long shipId, Source source, long simTick, int systemId) {
        return binding.activatePlayerShipSystem(shipId, source, simTick, systemId);
    }
}
