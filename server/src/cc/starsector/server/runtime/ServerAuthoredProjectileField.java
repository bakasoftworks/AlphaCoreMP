package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NonShipEntitySnapshot;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * The server-owned set of live {@link ServerAuthoredProjectile}s and the per-tick
 * fold that turns them into replicated {@link NonShipEntitySnapshot} records.
 *
 *
 * <p>This is the authoritative projectile tier the headless server owns in place of
 * engine-spawned projectiles (the engine cannot spawn one without
 * a GL context). A weapon-fire event {@link #spawnFromFireEvent spawns} a projectile;
 * {@link #liveSnapshotsAt} produces that tick's ordered non-ship records for the live
 * publish loop and prunes projectiles that have expired, so the tracked set does not
 * grow without bound over a long stream.
 *
 * <p>{@code liveSnapshotsAt} assigns each live projectile a per-frame network id in
 * spawn order starting at the caller-provided {@code firstNetworkId} — the same
 * positional, ascending-unique convention {@code EngineBackedLiveWorldSnapshotSource}
 * uses for the engine-read non-ship tier — so a frame that carries both the
 * engine-read tier and the server-authored tier stays a single ascending-by-network-id
 * list. The interning of each projectile's content key into a {@code contentId} is the
 * same {@link ContentIdRegistry} path the engine-read tier uses.
 *
 * <p>Not thread-safe; a single live publisher owns one field. Callers must query with
 * monotonically non-decreasing ticks (the publish loop's {@code simTick}), which the
 * pruning relies on.
 */
public final class ServerAuthoredProjectileField {

    private final List<ServerAuthoredProjectile> projectiles = new ArrayList<>();

    /**
     * Author a projectile from a weapon-fire event and track it. Returns the created
     * projectile (for tests / callers that want to assert its trajectory).
     */
    public ServerAuthoredProjectile spawnFromFireEvent(
            int owner,
            String contentKey,
            float originX,
            float originY,
            float headingRadians,
            float speedPerTick,
            int lifetimeTicks,
            long spawnTick) {
        ServerAuthoredProjectile projectile = ServerAuthoredProjectile.fromFireEvent(
                owner, contentKey, originX, originY, headingRadians, speedPerTick, lifetimeTicks, spawnTick);
        projectiles.add(projectile);
        return projectile;
    }

    /** Track an already-built projectile. */
    public void add(ServerAuthoredProjectile projectile) {
        projectiles.add(Objects.requireNonNull(projectile, "projectile"));
    }

    /** Number of projectiles currently tracked (alive or not-yet-pruned). */
    public int trackedCount() {
        return projectiles.size();
    }

    /** Number of projectiles alive at {@code tick}. */
    public int liveCount(long tick) {
        int count = 0;
        for (ServerAuthoredProjectile projectile : projectiles) {
            if (projectile.aliveAt(tick)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Drop projectiles that have expired by {@code tick} ({@code expiryTick() <= tick}).
     * Monotonic-tick contract: a projectile expired at the current tick can never become
     * alive again, so pruning is safe for the strictly increasing publish-loop tick.
     * Returns the number removed.
     */
    public int pruneExpired(long tick) {
        int before = projectiles.size();
        projectiles.removeIf(projectile -> projectile.expiryTick() <= tick);
        return before - projectiles.size();
    }

    /**
     * Resolve server-authored projectile impacts at {@code tick}.
     *
     * <p>For each tracked projectile that {@link ServerAuthoredProjectile#carriesDamage()
     * carries damage} and is alive at {@code tick}, the first {@code targets} entry of a
     * <em>different</em> owner whose hit radius contains the projectile's closed-form
     * position is the hit. A hit projectile is <strong>consumed</strong> (removed from
     * the field) so it can neither strike twice nor keep crossing the wire after impact —
     * its last visible frame is the tick before impact. Cosmetic (no-damage) projectiles
     * and friendly-fire matches never produce an impact and are left tracked. Returns the
     * impacts in projectile-spawn order.
     *
     * <p>This is the only server-side geometry the engine-authoritative model needs: the
     * engine never sees a server-authored projectile, so it cannot
     * resolve the hit. The resulting {@link ServerAuthoredProjectileImpact}s carry the raw
     * weapon damage for the engine's authoritative {@code applyDamage} — the
     * server never computes the durability change.
     */
    public List<ServerAuthoredProjectileImpact> resolveImpactsAt(
            long tick, List<ServerAuthoredProjectileTarget> targets) {
        List<ServerAuthoredProjectileImpact> impacts = new ArrayList<>();
        if (targets == null || targets.isEmpty()) {
            return impacts;
        }
        Iterator<ServerAuthoredProjectile> it = projectiles.iterator();
        while (it.hasNext()) {
            ServerAuthoredProjectile projectile = it.next();
            if (!projectile.carriesDamage() || !projectile.aliveAt(tick)) {
                continue;
            }
            ServerAuthoredProjectileTarget hit = null;
            for (ServerAuthoredProjectileTarget target : targets) {
                if (target == null || target.owner() == projectile.owner()) {
                    continue;
                }
                float radius = target.hitRadius();
                if (projectile.distanceSqAt(tick, target.x(), target.y()) <= radius * radius) {
                    hit = target;
                    break;
                }
            }
            if (hit == null) {
                continue;
            }
            impacts.add(new ServerAuthoredProjectileImpact(
                    tick,
                    projectile.owner(),
                    hit.shipId(),
                    projectile.posXAt(tick),
                    projectile.posYAt(tick),
                    projectile.rawDamage(),
                    projectile.damageTypeKey(),
                    projectile.contentKey()));
            it.remove();
        }
        return impacts;
    }

    /**
     * The ordered {@link NonShipEntitySnapshot} records for every projectile alive at
     * {@code tick}, assigning network ids {@code firstNetworkId..} in spawn order and
     * interning each non-blank content key through {@code registry} (a null/blank key
     * crosses as {@link NonShipEntitySnapshot#NO_CONTENT}). Expired projectiles are
     * pruned first. Projectiles not yet spawned (future {@code spawnTick}) are skipped
     * without being pruned.
     */
    public List<NonShipEntitySnapshot> liveSnapshotsAt(
            long tick, ContentIdRegistry registry, long firstNetworkId) {
        if (firstNetworkId <= 0L) {
            throw new IllegalArgumentException("firstNetworkId must be > 0; got " + firstNetworkId);
        }
        pruneExpired(tick);
        List<NonShipEntitySnapshot> out = new ArrayList<>();
        long nextNetworkId = firstNetworkId;
        for (ServerAuthoredProjectile projectile : projectiles) {
            if (!projectile.aliveAt(tick)) {
                continue;
            }
            int contentId = NonShipEntitySnapshot.NO_CONTENT;
            String key = projectile.contentKey();
            if (registry != null && key != null && !key.isBlank()) {
                contentId = registry.contentIdFor(key);
            }
            out.add(projectile.snapshotAt(tick, nextNetworkId, contentId));
            nextNetworkId++;
        }
        return out;
    }
}
