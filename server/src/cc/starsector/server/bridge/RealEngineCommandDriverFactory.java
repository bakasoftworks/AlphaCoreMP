package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.runtime.DedicatedCombatServerMain;
import cc.starsector.server.runtime.ShipCommandDriver;

/**
 * Product (real-engine) {@link ShipCommandDriver} factory for the {@code --command-driven} mode of
 * {@link DedicatedCombatServerMain} — the real-engine sibling of the game-free
 * {@code FakeCommandDriverFactory}. It is named by {@code --command-driver-factory} on the
 * real-engine intent-up serve ((B)): each connected client supplies its own owned
 * ship's per-frame {@code LiveManeuverIntent} over the socket, and this driver routes those
 * commands through an {@link EngineBackedShipCommandSink} over the real
 * {@link RealEngineCombatBindingFactory}-supplied binding (so the server commands, the real engine
 * integrates). The companion AI-suppression seam lives on the binding itself
 * ({@link EngineCombatBinding#maybeSuppressShipAi}), so this driver carries no engine reference and
 * needs no suppression logic of its own — it stays byte-identical to the fake factory's shape.
 *
 * <p>{@code create(EngineCombatBinding)} is the reflectively-invoked entrypoint
 * ({@link DedicatedCombatServerMain} {@code buildCommandDriver}: a public static
 * {@code create(EngineCombatBinding)} returning a {@link ShipCommandDriver}). It registers the two
 * server-owned demo ships {@code {1, 2}} as commandable, holding no initial intent — each client's
 * inbound maneuver intent sets its own ship's command per frame.
 */
public final class RealEngineCommandDriverFactory {

    private RealEngineCommandDriverFactory() {
    }

    /** Reflectively-invoked entrypoint: build the two-owned-ship command driver over the real binding. */
    public static ShipCommandDriver create(EngineCombatBinding binding) {
        if (binding == null) {
            throw new IllegalArgumentException("binding must not be null");
        }
        EngineBackedShipCommandSink sink = new EngineBackedShipCommandSink(binding);
        ShipCommandDriver driver = new ShipCommandDriver(sink, ShipCommandSink.Source.PLAYER);
        // Register the two demo-roster owned ships as commandable; their held intents start empty
        // (coast) and are set per-frame by each client's inbound LiveManeuverIntent over the socket.
        driver.register(1L);
        driver.register(2L);
        return driver;
    }
}
