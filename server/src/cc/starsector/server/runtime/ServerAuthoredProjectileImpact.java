package cc.starsector.server.runtime;

/**
 * A resolved impact of a server-authored projectile against a live target ship.
 *
 *
 * <p>When a damage-carrying {@link ServerAuthoredProjectile}'s closed-form trajectory
 * reaches a target this tick, the field resolves this record and hands it to
 * {@link AuthoritativeDamageSink}. It carries which ship took the hit
 * ({@code targetShipId}), where ({@code impactX/Y} — the projectile's closed-form
 * position at the impact tick), and the projectile's <em>raw</em> weapon damage and
 * damage type for the engine to resolve. The engine owns the durability math
 * (armor absorption, hull penetration); the server only authors the projectile and
 * detects the impact, because the engine never sees a server-authored projectile.
 */
public final class ServerAuthoredProjectileImpact {

    private final long impactTick;
    private final int sourceOwner;
    private final long targetShipId;
    private final float impactX;
    private final float impactY;
    private final float rawDamage;
    private final String damageTypeKey;
    private final String contentKey;

    public ServerAuthoredProjectileImpact(
            long impactTick,
            int sourceOwner,
            long targetShipId,
            float impactX,
            float impactY,
            float rawDamage,
            String damageTypeKey,
            String contentKey) {
        if (impactTick < 0L) {
            throw new IllegalArgumentException("impactTick must be >= 0; got " + impactTick);
        }
        if (!Float.isFinite(impactX) || !Float.isFinite(impactY)) {
            throw new IllegalArgumentException("impact point must be finite");
        }
        if (!Float.isFinite(rawDamage) || rawDamage <= 0f) {
            throw new IllegalArgumentException("rawDamage must be finite and > 0; got " + rawDamage);
        }
        this.impactTick = impactTick;
        this.sourceOwner = sourceOwner;
        this.targetShipId = targetShipId;
        this.impactX = impactX;
        this.impactY = impactY;
        this.rawDamage = rawDamage;
        this.damageTypeKey = damageTypeKey == null ? "" : damageTypeKey;
        this.contentKey = contentKey == null ? "" : contentKey;
    }

    /** The simulation tick the projectile reached its target. */
    public long impactTick() {
        return impactTick;
    }

    /** The firing side this impact is attributed to ({@code CombatEntityAPI.getOwner()} convention). */
    public int sourceOwner() {
        return sourceOwner;
    }

    /** Stable id of the ship that took the hit (the durability view's repository-order id). */
    public long targetShipId() {
        return targetShipId;
    }

    /** Impact world X (the projectile's closed-form position at the impact tick). */
    public float impactX() {
        return impactX;
    }

    /** Impact world Y (the projectile's closed-form position at the impact tick). */
    public float impactY() {
        return impactY;
    }

    /** Raw weapon damage to hand the engine; the engine resolves armor/hull (never the server). */
    public float rawDamage() {
        return rawDamage;
    }

    /** Version-independent damage-type key (e.g. {@code "ENERGY"}); blank means engine default. */
    public String damageTypeKey() {
        return damageTypeKey;
    }

    /** The projectile spec id (its content key); may be blank. */
    public String contentKey() {
        return contentKey;
    }
}
