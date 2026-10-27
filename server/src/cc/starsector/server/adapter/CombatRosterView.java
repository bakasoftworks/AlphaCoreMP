package cc.starsector.server.adapter;

/**
 * Project-owned read model for the combat ship roster installed into the
 * authoritative entity repository before an inner simulation step.
 *
 * <p>Proved the smallest real roster admitted so far: one
 * spec-resolved ship, installed into a fresh repository and bound as the player
 * ship, then advanced through the direct {@link CombatStepBoundary} path. This
 * contract records that product-level precondition without exposing hull-spec,
 * variant-spec, factory, name-store, or internal engine repository identities.
 */
public interface CombatRosterView {

    /** Number of combat ships currently present in the authoritative roster. */
    int shipCount();

    /** Number of roster ships whose hull/variant/spec data is already resolved. */
    int specResolvedShipCount();

    /** True when the engine-facing world has a player ship reference bound. */
    boolean playerShipBound();

    /**
     * Count of server-owned ship network ids exposed for replication. The
     * current headless gate uses stable repository-order ids: 1..shipCount.
     */
    default int shipNetworkIdCount() {
        return shipCount();
    }

    /** First ship network id in the current compact identity range, or -1 when empty. */
    default long firstShipNetworkId() {
        return shipNetworkIdCount() == 0 ? -1L : 1L;
    }

    /** Last ship network id in the current compact identity range, or -1 when empty. */
    default long lastShipNetworkId() {
        int count = shipNetworkIdCount();
        return count == 0 ? -1L : count;
    }

    /** True when every roster ship has a positive server-owned network id. */
    default boolean stableShipNetworkIdsReady() {
        int count = shipNetworkIdCount();
        return count == shipCount()
                && (count == 0 || (firstShipNetworkId() > 0L && lastShipNetworkId() >= firstShipNetworkId()));
    }

    /** True when every roster ship carries resolved spec data. */
    default boolean allShipsSpecResolved() {
        return specResolvedShipCount() == shipCount();
    }

    /**
     * True for the closed rung-6c minimum: exactly one spec-resolved ship is in
     * the authoritative roster and the player-ship reference points at a ship.
     */
    default boolean singleSpecResolvedPlayerShipReady() {
        return shipCount() == 1 && specResolvedShipCount() == 1 && playerShipBound();
    }

    /**
     * True when the roster contains the requested number of spec-resolved ships
     * and has a bound player ship for command/input ownership.
     */
    default boolean specResolvedPlayerShipRosterReady(int requiredShipCount) {
        return requiredShipCount >= 1
                && shipCount() == requiredShipCount
                && allShipsSpecResolved()
                && playerShipBound();
    }
}
