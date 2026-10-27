package cc.starsector.server.runtime;

/**
 * A live ship the server tests its authored projectiles against for impact.
 *
 *
 * <p>The headless engine never sees a server-authored projectile — it cannot spawn
 * one without a GL context — so it also cannot resolve the hit.
 * The server therefore does the minimal point-in-radius test itself. This carries
 * only the scalar facts that test needs (the engine ship handle and any engine-internal
 * class stay inside the bridge): the target's stable {@code shipId} (so the resolved
 * impact names which ship took the hit), its {@code owner} (to skip friendly fire),
 * its world position, and a hit radius.
 *
 * <p>This is impact <em>geometry</em> only. The authoritative damage math — armor
 * absorption, hull penetration, durability mutation — stays in the engine via
 * {@link AuthoritativeDamageSink} ({@code CombatEngineAPI.applyDamage}),
 * so the server never reimplements damage (that is a standing non-goal).
 */
public final class ServerAuthoredProjectileTarget {

    private final long shipId;
    private final int owner;
    private final float x;
    private final float y;
    private final float hitRadius;

    public ServerAuthoredProjectileTarget(long shipId, int owner, float x, float y, float hitRadius) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("target position must be finite");
        }
        if (!Float.isFinite(hitRadius) || hitRadius < 0f) {
            throw new IllegalArgumentException("hitRadius must be finite and >= 0; got " + hitRadius);
        }
        this.shipId = shipId;
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.hitRadius = hitRadius;
    }

    /** Stable ship identity, in the same repository-order convention the durability view uses. */
    public long shipId() {
        return shipId;
    }

    /** Owning side ({@code CombatEntityAPI.getOwner()} convention); a projectile never hits its own side. */
    public int owner() {
        return owner;
    }

    /** Target world X this tick. */
    public float x() {
        return x;
    }

    /** Target world Y this tick. */
    public float y() {
        return y;
    }

    /** Hit radius around the target world position (e.g. the ship collision radius). */
    public float hitRadius() {
        return hitRadius;
    }
}
