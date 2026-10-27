package cc.starsector.server.runtime;

/**
 * Product-owned publication boundary for completed offline combat step results.
 *
 * <p>Implementations may persist snapshots, publish events, or hand results to a
 * test double. The sink receives immutable product records and never sees
 * engine-internal objects.
 */
@FunctionalInterface
public interface OfflineCombatStepSink {

    /** Publish one completed offline combat step result. */
    void publish(OfflineCombatStepResult result);
}
