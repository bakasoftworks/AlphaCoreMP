package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipPoseSink;
import java.util.Objects;

/**
 * {@link ShipPoseSink} implemented on the motion-WRITE boundary
 * ({@code BaseEntity.setLoc}/{@code setVel} + an lwjgl {@code Vector2f}) on the
 * resolved owned ship.
 *
 * <p>The {@link EngineCombatBinding} owns the reflective engine crossing, the
 * owned-ship set, and the repository-order id resolution; this adapter is the
 * thin authoritative-pose write surface over it, mirroring how
 * {@link EngineBackedShipCommandSink} fronts the command boundary. The server's
 * tick loop calls this each tick to drive a server-controlled ship's pose
 * directly (the Path-B mechanism confirmed on the real engine).
 */
public final class EngineBackedShipPoseSink implements ShipPoseSink {

    private final EngineCombatBinding binding;

    public EngineBackedShipPoseSink(EngineCombatBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public Result writePose(long shipId, long simTick, float locX, float locY, float velX, float velY) {
        return binding.writeOwnedShipPose(shipId, simTick, locX, locY, velX, velY);
    }
}
