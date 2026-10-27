package cc.starsector.client.net;

/**
 * Why an authoritative entity left the world, as classified client-side by
 * {@link RosterLifecycleTracker} from the entity's last-seen hull.
 *
 * <p>This is the client-side mirror of the server's
 * {@code cc.starsector.server.protocol.EntityDespawnReason}: the same two
 * world-level transitions, with the <em>same stable wire codes</em>
 * ({@code DESTROYED = 1}, {@code DEPARTED = 2}). The mod deliberately does not
 * bundle the server protocol graph (see {@link AwssSnapshotReader}); this small
 * pure-Java enum reproduces the reason taxonomy so the lifecycle tracker yields
 * the identical classification the server's {@code EntityDespawnEventDetector}
 * does over the same frame stream. The game-free cross-check
 * asserts the two stay in
 * sync by comparing on these wire codes.
 */
public enum ClientDespawnReason {

    /**
     * The entity was destroyed in combat: its last authoritative hull
     * ({@code hitpoints}) was at or below the destroyed-hull threshold before it
     * left the roster. Mirrors the server's lethal-death case.
     * A client kill feed / despawn FX scores this as a kill.
     */
    DESTROYED(1),

    /**
     * The entity left the battle space with hull still above the destroyed-hull
     * threshold (e.g. a retreat / non-lethal removal). Distinct from
     * {@link #DESTROYED} so a client does not score a departure as a kill.
     */
    DEPARTED(2);

    private final int wireCode;

    ClientDespawnReason(int wireCode) {
        this.wireCode = wireCode;
    }

    /** Stable on-wire code for this reason (never the ordinal); matches the server's. */
    public int wireCode() {
        return wireCode;
    }

    /** Resolve a reason from its {@link #wireCode()}; fail-closed on an unknown code. */
    public static ClientDespawnReason fromWireCode(int wireCode) {
        for (ClientDespawnReason reason : values()) {
            if (reason.wireCode == wireCode) {
                return reason;
            }
        }
        throw new IllegalArgumentException("unknown client despawn reason wire code: " + wireCode);
    }
}
