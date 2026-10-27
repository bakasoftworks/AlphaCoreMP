package cc.starsector.client.net;

/**
 * One decoded {@code DIVE} phase-dive-start event: the server's
 * per-frame rising-edge read of a phase-anchor hull's {@code emergencyDive} saw the
 * vanilla intercept fire, so this entity is now playing the ~1&nbsp;s emergency-dive
 * out and will leave the field alive (its later {@code EDSP} despawn carries reason
 * {@code DEPARTED}). Client mirror of the server's
 * {@code cc.starsector.server.protocol.PhaseDiveEvent}; decoded by
 * {@link ClientPhaseDiveReader}, presented by the {@code PhaseDivePresentLane}
 * (knob {@code cc.predict.phaseDive}).
 */
public final class ClientPhaseDive {

    private final long entityId;
    private final long simTick;

    public ClientPhaseDive(long entityId, long simTick) {
        if (entityId < 0) {
            throw new IllegalArgumentException("phase-dive entityId must be >= 0, got " + entityId);
        }
        if (simTick < 0) {
            throw new IllegalArgumentException("phase-dive simTick must be >= 0, got " + simTick);
        }
        this.entityId = entityId;
        this.simTick = simTick;
    }

    /** The diving hull's wire entity id (the id its snapshots and despawn carry). */
    public long entityId() {
        return entityId;
    }

    /** Server sim tick of the dive's rising edge. */
    public long simTick() {
        return simTick;
    }

    @Override
    public String toString() {
        return "ClientPhaseDive{entityId=" + entityId + ", simTick=" + simTick + "}";
    }
}
