package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipEngineStateView;
import java.util.Objects;

/**
 * One ship's authoritative per-engine DISABLED state as it is replicated to clients:
 * the engine-state tier (schema v6), read off the bridge's
 * {@link ShipEngineStateView} (public {@code getEngineController().getShipEngines()}
 * surface).
 *
 * <p>Why this is carried at all: flameout is DERIVED state — the engine controller
 * recomputes it every advance from per-engine health trackers whose trajectories are
 * independently stochastic on server and client (randomized repairTime and check
 * intervals, malfunction rolls, locally-simulated hit patterns), so a predictive
 * client's foe can flame out when the server's didn't (the WATCH desync).
 * Carrying the per-engine bits lets the client apply {@code disable(perm)}/{@code
 * repair()} to its local foe and have the REAL local state machine derive
 * flameout/recovery/speed penalties — no {@code forceFlameout}, no faked end value.
 *
 * <p>Engine bit {@code i} in both masks is the {@code getShipEngines()} list index —
 * spec order, deterministic per variant on both sides; a ship with more than 32
 * engines carries its first 32 (fail-soft). {@code controllerState} ({@code 0}
 * ACTIVE / {@code 1} FLAMING_OUT / {@code 2} FLAMED_OUT) is carried for
 * METERS/validation only and is never force-applied. Keyed by {@code entityId} to the
 * same stable network identity as the ship's {@link AuthoritativeEntitySnapshot};
 * like the other snapshot tiers it carries only scalar, version-independent state and
 * never an engine-internal class.
 */
public record ShipEngineStateSnapshot(
        long entityId, int disabledMask, int permDisabledMask, int controllerState) {

    /** Controller derived-state codes ({@code ShipEngineControllerAPI} 3-state enum). */
    public static final int CONTROLLER_ACTIVE = 0;
    public static final int CONTROLLER_FLAMING_OUT = 1;
    public static final int CONTROLLER_FLAMED_OUT = 2;

    public ShipEngineStateSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if (controllerState != CONTROLLER_ACTIVE
                && controllerState != CONTROLLER_FLAMING_OUT
                && controllerState != CONTROLLER_FLAMED_OUT) {
            throw new IllegalArgumentException(
                    "controllerState must be 0 (ACTIVE), 1 (FLAMING_OUT), or 2 (FLAMED_OUT); got "
                            + controllerState);
        }
    }

    /**
     * Build the per-ship engine-state snapshot from the project-owned
     * {@link ShipEngineStateView} read model, keyed to the ship's already-resolved
     * network {@code entityId} (distinct from the view's within-tick repository
     * {@code shipId()}).
     */
    public static ShipEngineStateSnapshot fromView(ShipEngineStateView view, long entityId) {
        Objects.requireNonNull(view, "view");
        return new ShipEngineStateSnapshot(
                entityId, view.disabledMask(), view.permDisabledMask(), view.controllerState());
    }

    /**
     * Reconstruct from decoded wire fields, fail-closed: a corrupt frame whose
     * controller-state code is outside the 3-state enum (or whose entityId is
     * non-positive) is rejected by the canonical constructor rather than carried.
     * The two masks are accepted as-is — any bit pattern is a representable engine
     * set, and whether perm-disabled implies disabled is engine semantics the wire
     * layer does not assume.
     */
    public static ShipEngineStateSnapshot fromWire(
            long entityId, int disabledMask, int permDisabledMask, int controllerState) {
        return new ShipEngineStateSnapshot(entityId, disabledMask, permDisabledMask, controllerState);
    }
}
