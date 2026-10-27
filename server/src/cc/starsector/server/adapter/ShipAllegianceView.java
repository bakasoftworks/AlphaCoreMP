package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's combat ALLEGIANCE at a simulation step:
 * which side ({@code FleetSide} ordinal) the ship fights for, plus whether it is still
 * a live combatant. This is the pairing the headless server folds into the
 * authoritative combat OUTCOME — "which side still has live ships" — as
 * opposed to the per-ship motion/durability/flux tiers (what a ship is doing) or the
 * lifecycle tier (its stable cross-tick identity, {@link ShipLifecycleView}).
 *
 * <p>Both reads are public {@code CombatEntityAPI}/{@code ShipAPI} surface, kept behind
 * the bridge: {@code CombatEntityAPI.getOwner()} (the owning side) and
 * {@code ShipAPI.isAlive()} (liveness). Owner {@code 0} and {@code 1} are the
 * two fighting {@code FleetSide}s; other values (e.g. the neutral {@code 100}) are
 * non-combatant and cannot win the match. The within-tick {@code shipId} matches the
 * other per-ship views read in the same tick (repository order).
 *
 * <p>Why a dedicated allegiance tier instead of the engine's own
 * {@code getWinningSideId()}: that engine field is computed from
 * {@code CombatFleetManager} deployment bookkeeping, which the headless roster install
 * deliberately empties — so it latches a spurious early winner blind to the
 * ships actually fighting in the object repository. The server
 * therefore derives the outcome from this roster read, which it authoritatively owns.
 */
public interface ShipAllegianceView {

    /**
     * Within-tick repository-order id, the same {@code shipId} the motion/durability/
     * lifecycle views read in the same tick use — for pairing the views that describe one
     * ship at one step. Not stable across ticks when the roster changes; use the
     * {@link ShipLifecycleView#stableKey() lifecycle stableKey} for cross-tick identity.
     */
    long shipId();

    /**
     * The owning side ({@code CombatEntityAPI.getOwner()}): {@code 0} / {@code 1} for the
     * two fighting {@code FleetSide}s, other values (e.g. the neutral {@code 100}) for
     * non-combatant entities that cannot win the match.
     */
    int owner();

    /** Whether this ship is still a live combatant ({@code ShipAPI.isAlive()}). */
    boolean alive();

    /**
     * Whether this ship is a PLAYER SEAT's live body or one of its parts
     * (modules, fighters, drones). Only a read that ASKED for the flag carries it — the plain
     * roster read answers {@code false} for every row. The wave-clear count reads it so a
     * player who switched to the waves' team is never counted as a wave ship (a living player
     * would otherwise hold the next wave back forever); the OUTCOME folds ignore it — a player
     * hull fights for its side like any other.
     */
    default boolean seat() {
        return false;
    }
}
