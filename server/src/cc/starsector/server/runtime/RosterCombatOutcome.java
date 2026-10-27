package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatOutcomeView;
import cc.starsector.server.adapter.ShipAllegianceView;
import java.util.Objects;

/**
 * Server-side, game-free producer of the authoritative combat {@link CombatOutcomeView},
 * derived from the server's OWN authoritative roster rather than the engine's
 * {@code getWinningSideId()} field.
 *
 * <p><b>Rule.</b> Count the live ships ({@link ShipAllegianceView#alive()}) on each of the
 * two fighting {@code FleetSide}s — owner {@link #FLEET_SIDE_PLAYER 0} and
 * {@link #FLEET_SIDE_ENEMY 1}; other owners (e.g. the neutral {@code 100}) are
 * non-combatant and never win. The match is <em>decided</em> iff exactly one of those two
 * sides still has at least one live ship, and that side is the winner. While both sides
 * still have live ships (undecided) or neither does (mutual destruction) the winning side
 * is {@code -1}.
 *
 * <p><b>Why roster-derived, not engine-read.</b> The engine's end-of-combat checker
 * computes its verdict entirely from {@code CombatFleetManager} deployment bookkeeping,
 * which the headless roster install deliberately empties (ships go into the
 * object repository only). Its fleet-manager getters synthesize empty non-null managers,
 * so the checker decides anyway and latches a <em>spurious early winner</em> blind to the
 * ships actually fighting. The server owns the object-repository roster and
 * derives the true verdict from it.
 *
 * <p>The result is a {@link CombatOutcomeView}, so it drops into the unchanged outcome
 * pipeline ({@link CombatOutcomeEventDetector} → {@link CombatOutcomeEventCodec} →
 * wire → consumer): only the producer's data source moves from the engine read
 * ({@code EngineCombatBinding.combatOutcomeOrNull()}) to this roster computation
 * ({@code EngineCombatBinding.combatOutcomeFromRosterOrNull()}).
 */
public final class RosterCombatOutcome implements CombatOutcomeView {

    /** Player fleet side: {@code CombatEntityAPI.getOwner()} ordinal for side 0. */
    public static final int FLEET_SIDE_PLAYER = 0;
    /** Enemy fleet side: {@code CombatEntityAPI.getOwner()} ordinal for side 1. */
    public static final int FLEET_SIDE_ENEMY = 1;
    /** Undecided sentinel, matching the engine's {@code getWinningSideId()} contract. */
    public static final int UNDECIDED = -1;

    private final int winningSideId;

    private RosterCombatOutcome(int winningSideId) {
        this.winningSideId = winningSideId;
    }

    /**
     * Derive the outcome from the authoritative per-ship allegiance roster. A null roster
     * or null entry is rejected fail-closed (the caller supplies a clean roster); an
     * <em>empty</em> roster is a legitimate undecided ({@code -1}) state, not an error.
     */
    public static RosterCombatOutcome decide(Iterable<? extends ShipAllegianceView> roster) {
        Objects.requireNonNull(roster, "roster");
        boolean playerHasLiveShip = false;
        boolean enemyHasLiveShip = false;
        for (ShipAllegianceView ship : roster) {
            Objects.requireNonNull(ship, "roster entry");
            if (!ship.alive()) {
                continue;
            }
            if (ship.owner() == FLEET_SIDE_PLAYER) {
                playerHasLiveShip = true;
            } else if (ship.owner() == FLEET_SIDE_ENEMY) {
                enemyHasLiveShip = true;
            }
        }
        if (playerHasLiveShip && !enemyHasLiveShip) {
            return new RosterCombatOutcome(FLEET_SIDE_PLAYER);
        }
        if (enemyHasLiveShip && !playerHasLiveShip) {
            return new RosterCombatOutcome(FLEET_SIDE_ENEMY);
        }
        return new RosterCombatOutcome(UNDECIDED);
    }

    @Override
    public int winningSideId() {
        return winningSideId;
    }

    /**
     * For the roster-derived producer the match is "over" exactly when it is decided:
     * there is no engine combat-over flag to read here (and the engine's render-coupled
     * one is unreliable headless — see {@link CombatOutcomeView}). So {@code combatOver()}
     * mirrors {@link #decided()}, an honest server-side value rather than a borrowed —
     * and headless-meaningless — engine flag.
     */
    @Override
    public boolean combatOver() {
        return decided();
    }
}
