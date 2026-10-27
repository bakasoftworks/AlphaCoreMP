package cc.starsector.server.adapter;

/**
 * Project-owned boundary for hashing authoritative combat state after a direct
 * offline step.
 *
 * <p>The hash algorithm and internal engine reads live behind this adapter.
 * Product/runtime code only requires a stable value captured after the step so
 * snapshots can name a deterministic state boundary without exposing repository,
 * ship, weapon, or collision internals.
 */
@FunctionalInterface
public interface CombatStateHashBoundary {

    /** Return a stable hash of the authoritative world at the snapshot point. */
    long stateHash(CombatWorldState worldState);
}
