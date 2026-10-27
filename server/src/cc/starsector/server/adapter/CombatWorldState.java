package cc.starsector.server.adapter;

/**
 * Project-owned ownership boundary for the authoritative combat world state a
 * single simulation step reads.
 *
 * <p>A real (unpaused) authoritative step reads populated state: an entity
 * repository, a combat space, per-owner fog-of-war, and the engine time
 * multiplier. Full battle/deployment work will also bind fleet managers once
 * that boundary is mapped. The headless platform layer <em>populates</em> this
 * state directly and never goes through the visible resource/bootstrap chain, so
 * engine-internal identities (object repository, fleet-manager list, combat
 * map, fog-of-war maps, time-mult stat) stay hidden behind this interface and
 * the simulation adapter.
 *
 * <p>Derived from the minimal headless single-step precondition surface.
 * The current admitted
 * runtime contract is a direct inner step with combat space, fog-of-war for both
 * owners, a bound time multiplier, and either an empty roster
 * or one spec-resolved ship with a player-ship binding. The
 * visible resource bootstrap is still outside this boundary.
 * Specs are an entity-creation precondition, not an in-step lookup.
 */
public interface CombatWorldState {

    /** Fog-of-war owner id for the friendly side. */
    int OWNER_FRIENDLY = 0;

    /** Fog-of-war owner id for the enemy side. */
    int OWNER_ENEMY = 1;

    /** Number of entities currently in the authoritative repository. */
    int entityCount();

    /** Combat ship roster installed into the authoritative repository. */
    CombatRosterView roster();

    /** Number of per-side fleet managers bound for this combat. */
    int fleetManagerCount();

    /** True when a combat space is bound (required by both step branches). */
    boolean combatSpaceReady();

    /**
     * True when fog-of-war is bound for {@code owner} ({@link #OWNER_FRIENDLY} or
     * {@link #OWNER_ENEMY}); the unpaused step dereferences both, so a missing
     * entry would fault.
     */
    boolean fogOfWarReady(int owner);

    /** Engine time multiplier applied to the step amount; must be bound. */
    float timeMult();

    /** True when the direct inner step's shared world inputs are bound. */
    default boolean innerStepWorldReady() {
        return combatSpaceReady()
                && fogOfWarReady(OWNER_FRIENDLY)
                && fogOfWarReady(OWNER_ENEMY)
                && Float.isFinite(timeMult());
    }

    /**
     * True when every precondition for the admitted empty-roster unpaused step is
     * satisfied. Fleet managers are deliberately not required here: the closed
     * rung-6b/6c gates advanced with an empty fleet-manager list. Full deployment
     * semantics get their own adapter contract when mapped.
     */
    default boolean populatedForUnpausedStep() {
        return innerStepWorldReady();
    }

    /**
     * True when the closed rung-6c minimum is satisfied: the direct unpaused
     * world is ready and the roster contains exactly one spec-resolved player
     * ship.
     */
    default boolean populatedForSingleShipStep() {
        return populatedForUnpausedStep() && roster().singleSpecResolvedPlayerShipReady();
    }
}
