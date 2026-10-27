package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipAllegianceView;

/**
 * Immutable {@link ShipAllegianceView} snapshot read from the live engine bridge:
 * {@code CombatEntityAPI.getOwner()} + {@code ShipAPI.isAlive()}, with the
 * concrete {@code ShipAPI} kept behind the binding. A plain value holder (like
 * {@link EngineBackedCombatOutcomeView}); the outcome derivation that consumes a roster
 * of these lives in {@link cc.starsector.server.runtime.RosterCombatOutcome}.
 */
public final class EngineBackedShipAllegianceView implements ShipAllegianceView {

    private final long shipId;
    private final int owner;
    private final boolean alive;
    private final boolean seat;

    public EngineBackedShipAllegianceView(long shipId, int owner, boolean alive) {
        this(shipId, owner, alive, false);
    }

    public EngineBackedShipAllegianceView(long shipId, int owner, boolean alive, boolean seat) {
        this.shipId = shipId;
        this.owner = owner;
        this.alive = alive;
        this.seat = seat;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public int owner() {
        return owner;
    }

    @Override
    public boolean alive() {
        return alive;
    }

    @Override
    public boolean seat() {
        return seat;
    }
}
