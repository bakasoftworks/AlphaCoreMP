package cc.starsector.server.protocol;

/**
 * Network snapshot publication cadence measured in completed simulation ticks.
 *
 * <p>This is intentionally not a fixed step amount. Changing this value changes
 * how often snapshots are published, not how the authoritative simulation
 * advances.
 */
public record SnapshotPublicationCadence(long everyCompletedSimulationTicks) {

    public SnapshotPublicationCadence {
        if (everyCompletedSimulationTicks <= 0L) {
            throw new IllegalArgumentException(
                    "everyCompletedSimulationTicks must be > 0; got " + everyCompletedSimulationTicks);
        }
    }

    /** Returns true when a completed simulation tick belongs to this send tick. */
    public boolean includesCompletedTick(long completedSimulationTick) {
        if (completedSimulationTick <= 0L) {
            throw new IllegalArgumentException(
                    "completedSimulationTick must be > 0; got " + completedSimulationTick);
        }
        return completedSimulationTick % everyCompletedSimulationTicks == 0L;
    }
}
