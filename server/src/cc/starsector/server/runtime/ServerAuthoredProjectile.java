package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatEntityView;
import cc.starsector.server.protocol.NonShipEntitySnapshot;

/**
 * A deterministic, <em>server-authored</em> ballistic projectile.
 *
 * <p>The real combat engine cannot spawn a
 * projectile in a headless process: the projectile-spawn path executed inside the
 * simulation step touches OpenGL, and a headless server has no
 * GL context (and must never create one — that is a standing project non-goal). The
 * beam tier only survives headless because its one render resource (a textured-line
 * texture cache) was mappable and pre-seedable; the projectile/muzzle
 * spawn has its own unseeded render dependency.
 *
 * <p>This design resolves that the way snapshot-authoritative-only replication already
 * frames the rest of the world: the server owns authoritative truth and does not ask
 * the engine to produce a render-bearing projectile. The server already knows a
 * weapon fired — it issues the {@code FIRE} command itself (the command-authority
 * hook / the client-intent round-trip) — so it authors the
 * projectile record from that fire intent plus the projectile's ballistics (origin,
 * heading, speed, range) and replicates it as an ordinary {@link NonShipEntitySnapshot}
 * on the same AWSS wire the engine-read beam tier already crosses.
 *
 * <p>Motion is a straight ballistic line at a fixed per-tick velocity evaluated in
 * <em>closed form</em> from the spawn tick: the position at any tick is a pure
 * function of the spawn state, with no per-tick mutable accumulation, so the
 * projectile is fully deterministic and reproducible across runs (consistent with
 * the RNG-pin and snapshot-authority decisions — no dependence on the engine's unseedable motion/RNG order).
 * The projectile is alive for a fixed {@code lifetimeTicks} (its range divided by
 * speed, rounded by the caller) and then expires.
 *
 * <p>This type carries only scalar, version-independent state and never an
 * engine-internal class.
 */
public final class ServerAuthoredProjectile {

    private final int owner;
    private final String contentKey;
    private final float originX;
    private final float originY;
    private final float velocityXPerTick;
    private final float velocityYPerTick;
    private final long spawnTick;
    private final int lifetimeTicks;
    /** Raw weapon damage the engine will resolve; NaN/&lt;=0 means cosmetic (no authoritative effect). */
    private final float rawDamage;
    /** Damage-type key (e.g. {@code "ENERGY"}); blank when cosmetic or engine-default. */
    private final String damageTypeKey;

    private ServerAuthoredProjectile(
            int owner,
            String contentKey,
            float originX,
            float originY,
            float velocityXPerTick,
            float velocityYPerTick,
            long spawnTick,
            int lifetimeTicks,
            float rawDamage,
            String damageTypeKey) {
        if (contentKey == null) {
            throw new IllegalArgumentException("contentKey must be non-null (may be blank for NO_CONTENT)");
        }
        if (spawnTick < 0L) {
            throw new IllegalArgumentException("spawnTick must be >= 0; got " + spawnTick);
        }
        if (lifetimeTicks < 1) {
            throw new IllegalArgumentException("lifetimeTicks must be >= 1; got " + lifetimeTicks);
        }
        requireFinite("originX", originX);
        requireFinite("originY", originY);
        requireFinite("velocityXPerTick", velocityXPerTick);
        requireFinite("velocityYPerTick", velocityYPerTick);
        this.owner = owner;
        this.contentKey = contentKey;
        this.originX = originX;
        this.originY = originY;
        this.velocityXPerTick = velocityXPerTick;
        this.velocityYPerTick = velocityYPerTick;
        this.spawnTick = spawnTick;
        this.lifetimeTicks = lifetimeTicks;
        this.rawDamage = rawDamage;
        this.damageTypeKey = damageTypeKey == null ? "" : damageTypeKey;
    }

    /**
     * Author a <em>cosmetic</em> projectile from a weapon-fire event: the firing ship's
     * owner and muzzle position, the projectile spec id (its content key), the firing
     * heading, the projectile speed expressed per simulation tick, and a lifetime in
     * ticks (range / speed). The per-tick velocity is
     * {@code (cos heading, sin heading) * speed}. A cosmetic projectile crosses the wire
     * but lands no authoritative effect ({@link #carriesDamage()} is {@code false}); use
     * {@link #fromFireEventWithDamage} to give it engine-resolved damage.
     *
     */
    public static ServerAuthoredProjectile fromFireEvent(
            int owner,
            String contentKey,
            float originX,
            float originY,
            float headingRadians,
            float speedPerTick,
            int lifetimeTicks,
            long spawnTick) {
        return build(owner, contentKey, originX, originY, headingRadians, speedPerTick,
                lifetimeTicks, spawnTick, Float.NaN, "");
    }

    /**
     * Author a <em>damage-carrying</em> projectile: same fire
     * event as {@link #fromFireEvent}, plus the projectile's raw weapon damage and a
     * damage-type key. When this projectile's closed-form trajectory reaches a target,
     * the server hands that raw damage to the engine's authoritative
     * {@code applyDamage} for it to resolve armor/hull — the server never
     * computes the durability change itself. {@code rawDamage} must be finite and
     * positive (a non-positive value is a cosmetic projectile; use {@link #fromFireEvent}).
     */
    public static ServerAuthoredProjectile fromFireEventWithDamage(
            int owner,
            String contentKey,
            float originX,
            float originY,
            float headingRadians,
            float speedPerTick,
            int lifetimeTicks,
            long spawnTick,
            float rawDamage,
            String damageTypeKey) {
        if (!Float.isFinite(rawDamage) || rawDamage <= 0f) {
            throw new IllegalArgumentException(
                    "rawDamage must be finite and > 0 for a damage-carrying projectile; got " + rawDamage);
        }
        return build(owner, contentKey, originX, originY, headingRadians, speedPerTick,
                lifetimeTicks, spawnTick, rawDamage, damageTypeKey);
    }

    private static ServerAuthoredProjectile build(
            int owner,
            String contentKey,
            float originX,
            float originY,
            float headingRadians,
            float speedPerTick,
            int lifetimeTicks,
            long spawnTick,
            float rawDamage,
            String damageTypeKey) {
        requireFinite("headingRadians", headingRadians);
        requireFinite("speedPerTick", speedPerTick);
        if (speedPerTick < 0f) {
            throw new IllegalArgumentException("speedPerTick must be >= 0; got " + speedPerTick);
        }
        float vx = (float) Math.cos(headingRadians) * speedPerTick;
        float vy = (float) Math.sin(headingRadians) * speedPerTick;
        return new ServerAuthoredProjectile(
                owner, contentKey, originX, originY, vx, vy, spawnTick, lifetimeTicks,
                rawDamage, damageTypeKey);
    }

    /** The owning side, mirroring the engine's {@code CombatEntityAPI.getOwner()} convention. */
    public int owner() {
        return owner;
    }

    /** Version-independent content key (the projectile spec id); blank means NO_CONTENT. */
    public String contentKey() {
        return contentKey;
    }

    /** First tick this projectile exists. */
    public long spawnTick() {
        return spawnTick;
    }

    /** Number of ticks the projectile is alive before it expires. */
    public int lifetimeTicks() {
        return lifetimeTicks;
    }

    /** First tick at which the projectile no longer exists ({@code spawnTick + lifetimeTicks}). */
    public long expiryTick() {
        return spawnTick + lifetimeTicks;
    }

    /** True iff the projectile exists at {@code tick} (spawned, not yet expired). */
    public boolean aliveAt(long tick) {
        return tick >= spawnTick && tick < expiryTick();
    }

    /** World X position at {@code tick} (closed form from the spawn state). */
    public float posXAt(long tick) {
        return originX + velocityXPerTick * (tick - spawnTick);
    }

    /** World Y position at {@code tick} (closed form from the spawn state). */
    public float posYAt(long tick) {
        return originY + velocityYPerTick * (tick - spawnTick);
    }

    /**
     * True iff this projectile carries an authoritative damage payload.
     * A cosmetic projectile ({@link #fromFireEvent}) crosses the wire but
     * never produces a {@link ServerAuthoredProjectileImpact}.
     */
    public boolean carriesDamage() {
        return Float.isFinite(rawDamage) && rawDamage > 0f;
    }

    /** Raw weapon damage for the engine to resolve; only meaningful when {@link #carriesDamage()}. */
    public float rawDamage() {
        return rawDamage;
    }

    /** Damage-type key (e.g. {@code "ENERGY"}); blank means cosmetic or engine-default. */
    public String damageTypeKey() {
        return damageTypeKey;
    }

    /** Squared distance from the projectile's closed-form position at {@code tick} to {@code (x,y)}. */
    public float distanceSqAt(long tick, float x, float y) {
        float dx = posXAt(tick) - x;
        float dy = posYAt(tick) - y;
        return dx * dx + dy * dy;
    }

    /**
     * The first tick within this projectile's life at which its closed-form position is
     * within {@code hitRadius} of the <em>static</em> point {@code (targetX, targetY)},
     * or empty if it never is (a miss). This is the closed-form convenience for a target
     * that does not move; the live publish loop instead tests each tick against the
     * target's current position via {@link #distanceSqAt}.
     */
    public java.util.OptionalLong impactTickAgainst(float targetX, float targetY, float hitRadius) {
        if (!Float.isFinite(hitRadius) || hitRadius < 0f) {
            throw new IllegalArgumentException("hitRadius must be finite and >= 0; got " + hitRadius);
        }
        float r2 = hitRadius * hitRadius;
        for (long t = spawnTick; t < expiryTick(); t++) {
            if (distanceSqAt(t, targetX, targetY) <= r2) {
                return java.util.OptionalLong.of(t);
            }
        }
        return java.util.OptionalLong.empty();
    }

    /**
     * Build this projectile's authoritative wire record for {@code tick}: a
     * {@link CombatEntityView.Kind#PROJECTILE} {@link NonShipEntitySnapshot} at the
     * closed-form position with the caller-assigned {@code networkId} and interned
     * {@code contentId}.
     */
    public NonShipEntitySnapshot snapshotAt(long tick, long networkId, int contentId) {
        return new NonShipEntitySnapshot(
                networkId,
                CombatEntityView.Kind.PROJECTILE,
                owner,
                posXAt(tick),
                posYAt(tick),
                contentId);
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
