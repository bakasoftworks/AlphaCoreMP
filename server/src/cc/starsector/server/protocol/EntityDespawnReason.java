package cc.starsector.server.protocol;

/**
 * Why an authoritative entity left the world, carried by an
 * {@link EntityDespawnEvent}.
 *
 * <p>The point of an <em>explicit</em> despawn event (and therefore of this
 * reason) is to disambiguate a roster change that the snapshot stream alone
 * cannot: under snapshot-authoritative replication the server simply stops including a ship in the next
 * {@link AuthoritativeWorldSnapshot} when it is destroyed, but a relevance-scoped
 * peer ({@link ClientRelevanceView}) <em>also</em> stops
 * receiving a ship the moment it leaves that peer's interest set. From roster
 * diff alone those two are indistinguishable. The despawn event is emitted from
 * the server's full authoritative roster (never a per-peer relevance-scoped
 * frame), and this reason states which world-level transition it was — so a
 * client never confuses "destroyed" with "out of my interest range" (the latter
 * never produces a despawn event at all).
 */
public enum EntityDespawnReason {

    /**
     * The entity was destroyed in combat: its last authoritative hull
     * ({@code hitpoints}) was at or below the destroyed-hull threshold before it
     * left the roster. This is the lethal-death case proven on the real engine
     * (the engine's {@code isAlive()} flip / organic roster shrink).
     *
     */
    DESTROYED(1),

    /**
     * The entity left the battle space with hull still above the destroyed-hull
     * threshold (e.g. a retreat / non-lethal removal). Distinct from
     * {@link #DESTROYED} so a client kill feed does not score a departure as a
     * kill.
     */
    DEPARTED(2);

    private final int wireCode;

    EntityDespawnReason(int wireCode) {
        this.wireCode = wireCode;
    }

    /** Stable on-wire code for this reason (never the ordinal). */
    public int wireCode() {
        return wireCode;
    }

    /** Resolve a reason from its {@link #wireCode()}; fail-closed on an unknown code. */
    public static EntityDespawnReason fromWireCode(int wireCode) {
        for (EntityDespawnReason reason : values()) {
            if (reason.wireCode == wireCode) {
                return reason;
            }
        }
        throw new IllegalArgumentException("unknown entity despawn reason wire code: " + wireCode);
    }
}
