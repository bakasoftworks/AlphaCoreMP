package cc.starsector.server.adapter;

/**
 * Project-owned authoritative <em>pose-write</em> surface for a server-owned ship.
 *
 * <p>This is deliberately distinct from {@link ShipCommandSink}. That sink
 * expresses <em>intent</em> (accelerate, fire, activate system) and
 * never publishes authoritative transforms —
 * movement integration stays owned by the combat engine and is observed through
 * {@link ShipMotionView}. {@code ShipPoseSink} is the complementary write
 * boundary: the server claiming motion <em>authority</em> over a ship it owns and
 * writing that ship's location/velocity directly, bypassing the engine's own
 * per-ship AI/order logic.
 *
 * <p>This is the Path-B mechanism confirmed on the real engine
 * ({@code BaseEntity.setLoc}/{@code setVel}): a written {@code loc}
 * holds across the engine's {@code advanceInner} (no snap-back) and a written
 * {@code vel} is integrated at {@code vel*dt}/frame and is not zeroed by the
 * ship's controller. The dedicated server uses this surface to drive an AI-owned
 * or otherwise server-controlled ship each tick. Only the server holds a
 * reference to this sink, so there is no player-facing {@code Source} — a pose
 * write is, by construction, server-authoritative.
 *
 * <p>Writes fail closed:
 * <ul>
 *   <li>{@link Result#REJECTED_NOT_OWNED} — the {@code shipId} is not in the
 *       server's configured owned-ship set.</li>
 *   <li>{@link Result#REJECTED_UNSUPPORTED} — the engine motion-write boundary is
 *       not mapped/bound.</li>
 *   <li>{@link Result#REJECTED_INVALID} — a malformed request (negative tick, a
 *       non-finite pose component, or an unresolvable owned id).</li>
 * </ul>
 */
public interface ShipPoseSink {

    /** Result of attempting an authoritative pose write for a tick. */
    enum Result { APPLIED, REJECTED_UNSUPPORTED, REJECTED_NOT_OWNED, REJECTED_INVALID }

    /**
     * Write the authoritative pose (location + velocity) of server-owned
     * {@code shipId} for {@code simTick}.
     *
     * <p>{@code shipId} is the snapshot's stable repository-order id (1-based) —
     * the same id scheme {@link ShipMotionView} reads and the network client
     * addresses ships by — not the engine's {@code getPlayerShip()} singleton.
     */
    Result writePose(long shipId, long simTick, float locX, float locY, float velX, float velY);
}
