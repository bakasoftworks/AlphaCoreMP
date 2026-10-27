package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipAllegianceView;
import java.util.Objects;

/**
 * One ship's authoritative combat ALLEGIANCE (which {@code FleetSide} it fights for) as
 * it is replicated to clients: the friend/foe tier read off the bridge's
 * {@link ShipAllegianceView} ({@code CombatEntityAPI.getOwner()}).
 *
 * <p>This is the wire-carried counterpart of {@link ShipAllegianceView}'s {@code owner()}.
 * Until now every replicated per-ship record carried only {@code ownerConnectionId} — which
 * <em>network connection</em> controls the ship, a session-authority concept — but NOT which
 * combat side it fights for. A client could puppet a ship's kinematics but had no way to
 * render friend vs foe (the open playability delta: the
 * full static roster + ids already cross the wire, but without combat side a battle cannot be
 * drawn). This record folds the per-ship {@code FleetSide} onto the AWSS wire so a client can
 * colour each puppet by side.
 *
 * <p>It is keyed by {@code entityId} to the same stable network identity as the ship's
 * {@link AuthoritativeEntitySnapshot}, so a decoder pairs allegiance to its ship. Like the
 * other snapshot tiers it carries only scalar, version-independent state and never an
 * engine-internal class (the {@code FleetSide} read stays inside the bridge). The
 * {@code fleetSide} ordinal mirrors {@link ShipAllegianceView#owner()}: {@code 0} / {@code 1}
 * are the two fighting sides, other values (e.g. the neutral {@code 100}) are non-combatant.
 */
public record ShipAllegianceSnapshot(long entityId, int fleetSide) {

    public ShipAllegianceSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
    }

    /**
     * Build the per-ship allegiance snapshot from the project-owned
     * {@link ShipAllegianceView} read model, keyed to the ship's already-resolved network
     * {@code entityId} (distinct from the view's within-tick repository {@code shipId()}).
     * Only the combat side ({@link ShipAllegianceView#owner()}) is carried; the view's
     * {@code alive()} liveness is already derivable from the ship's hull/roster membership
     * on the same frame, so it is not duplicated here.
     */
    public static ShipAllegianceSnapshot fromView(ShipAllegianceView view, long entityId) {
        Objects.requireNonNull(view, "view");
        return new ShipAllegianceSnapshot(entityId, view.owner());
    }
}
