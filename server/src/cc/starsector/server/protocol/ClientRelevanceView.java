package cc.starsector.server.protocol;

/**
 * Per-connection interest model used to filter an authoritative world snapshot
 * down to the entities one client should receive.
 *
 * <p>This is the first concrete answer to the "interest-management relevance
 * rules" open question. It is
 * deliberately simple and server-owned: a connection sees an entity when the
 * entity is owned by that connection (owner-scoped) or lies within a circular
 * relevance radius of the connection's view center (visibility-scoped). Both
 * scopes mirror the {@link ProtocolRelevanceRule} carried by
 * {@link WorldSnapshotMessage}.
 *
 * <p>It carries no engine identity; the view center is in the same
 * simulation-space coordinates as {@link AuthoritativeEntitySnapshot}.
 */
public record ClientRelevanceView(
        int connectionId,
        float centerX,
        float centerY,
        float relevanceRadius) {

    public ClientRelevanceView {
        if (connectionId < 0) {
            throw new IllegalArgumentException("connectionId must be >= 0; got " + connectionId);
        }
        requireFinite("centerX", centerX);
        requireFinite("centerY", centerY);
        if (!(relevanceRadius >= 0f) || !Float.isFinite(relevanceRadius)) {
            throw new IllegalArgumentException(
                    "relevanceRadius must be finite and >= 0; got " + relevanceRadius);
        }
    }

    /**
     * True when {@code entity} is relevant to this connection: owned by it, or
     * within the relevance radius of the view center.
     */
    public boolean isRelevant(AuthoritativeEntitySnapshot entity) {
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        if (entity.ownedBy(connectionId)) {
            return true;
        }
        float dx = entity.locX() - centerX;
        float dy = entity.locY() - centerY;
        double distanceSquared = (double) dx * dx + (double) dy * dy;
        double radiusSquared = (double) relevanceRadius * relevanceRadius;
        return distanceSquared <= radiusSquared;
    }

    /**
     * True when {@code nonShip} (a projectile/missile/beam) is relevant to this
     * connection. Unlike the ship tier, the non-ship critical-state tier
     * (WorldSnapshotMessage schema v2) is <em>visibility-scoped only</em>: a
     * transient entity is relevant when its position lies within the relevance
     * radius of the view center. It is deliberately not owner-scoped, since the
     * {@code owner} a non-ship carries is a combat side, not a per-connection
     * authority id; this mirrors {@link WorldSnapshotMessage#nonShipRelevanceRule()}
     * ({@code ownerScoped=false, visibilityScoped=true}).
     */
    public boolean isRelevant(NonShipEntitySnapshot nonShip) {
        if (nonShip == null) {
            throw new NullPointerException("nonShip");
        }
        float dx = nonShip.posX() - centerX;
        float dy = nonShip.posY() - centerY;
        double distanceSquared = (double) dx * dx + (double) dy * dy;
        double radiusSquared = (double) relevanceRadius * relevanceRadius;
        return distanceSquared <= radiusSquared;
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
