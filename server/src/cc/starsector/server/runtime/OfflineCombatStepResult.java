package cc.starsector.server.runtime;

/**
 * Immutable product-owned result for one bounded offline combat step.
 *
 * <p>This is the rung-7 snapshot boundary: it records the completed server tick,
 * the clock-owned fixed amount, the inner-step effective amount, compact world
 * counts, a state hash supplied by the simulation adapter, and timing fields for
 * the step and snapshot capture. It intentionally contains no engine classes.
 */
public record OfflineCombatStepResult(
        long startedTick,
        long completedTick,
        float fixedStepAmount,
        float effectiveStepAmount,
        int entityCount,
        int shipCount,
        int shipNetworkIdCount,
        long firstShipNetworkId,
        long lastShipNetworkId,
        long stateHash,
        long stepStartedAtNanos,
        long stepCompletedAtNanos,
        long snapshotCompletedAtNanos) {

    public OfflineCombatStepResult(
            long startedTick,
            long completedTick,
            float fixedStepAmount,
            float effectiveStepAmount,
            int entityCount,
            int shipCount,
            long stateHash,
            long stepStartedAtNanos,
            long stepCompletedAtNanos,
            long snapshotCompletedAtNanos) {
        this(
                startedTick,
                completedTick,
                fixedStepAmount,
                effectiveStepAmount,
                entityCount,
                shipCount,
                defaultShipNetworkIdCount(shipCount),
                defaultFirstShipNetworkId(shipCount),
                defaultLastShipNetworkId(shipCount),
                stateHash,
                stepStartedAtNanos,
                stepCompletedAtNanos,
                snapshotCompletedAtNanos);
    }

    public OfflineCombatStepResult {
        if (startedTick < 0L) {
            throw new IllegalArgumentException("startedTick must be >= 0; got " + startedTick);
        }
        if (completedTick != startedTick + 1L) {
            throw new IllegalArgumentException("completedTick must be exactly one tick after startedTick");
        }
        if (!(fixedStepAmount > 0f) || !Float.isFinite(fixedStepAmount)) {
            throw new IllegalArgumentException("fixedStepAmount must be finite and > 0; got " + fixedStepAmount);
        }
        if (!Float.isFinite(effectiveStepAmount)) {
            throw new IllegalArgumentException("effectiveStepAmount must be finite; got " + effectiveStepAmount);
        }
        if (entityCount < 0) {
            throw new IllegalArgumentException("entityCount must be >= 0; got " + entityCount);
        }
        if (shipCount < 0) {
            throw new IllegalArgumentException("shipCount must be >= 0; got " + shipCount);
        }
        if (shipNetworkIdCount < 0 || shipNetworkIdCount > shipCount) {
            throw new IllegalArgumentException("shipNetworkIdCount must be in [0, shipCount]; got "
                    + shipNetworkIdCount + " for shipCount=" + shipCount);
        }
        if (shipNetworkIdCount == 0) {
            if (firstShipNetworkId != -1L || lastShipNetworkId != -1L) {
                throw new IllegalArgumentException("empty ship network identity range must use -1 sentinels");
            }
        } else if (firstShipNetworkId <= 0L || lastShipNetworkId < firstShipNetworkId) {
            throw new IllegalArgumentException("ship network identity range must be positive and ordered");
        }
        if (stepCompletedAtNanos < stepStartedAtNanos) {
            throw new IllegalArgumentException("stepCompletedAtNanos must be >= stepStartedAtNanos");
        }
        if (snapshotCompletedAtNanos < stepCompletedAtNanos) {
            throw new IllegalArgumentException("snapshotCompletedAtNanos must be >= stepCompletedAtNanos");
        }
    }

    /** Time spent inside the direct step boundary. */
    public long stepDurationNanos() {
        return stepCompletedAtNanos - stepStartedAtNanos;
    }

    /** Time spent capturing post-step counts and state hash. */
    public long snapshotDurationNanos() {
        return snapshotCompletedAtNanos - stepCompletedAtNanos;
    }

    public boolean stableShipNetworkIdentityRange() {
        return shipNetworkIdCount == shipCount
                && (shipNetworkIdCount == 0
                        || (firstShipNetworkId > 0L
                                && lastShipNetworkId - firstShipNetworkId + 1L == shipNetworkIdCount));
    }

    private static int defaultShipNetworkIdCount(int shipCount) {
        return shipCount;
    }

    private static long defaultFirstShipNetworkId(int shipCount) {
        return shipCount == 0 ? -1L : 1L;
    }

    private static long defaultLastShipNetworkId(int shipCount) {
        return shipCount == 0 ? -1L : shipCount;
    }
}
