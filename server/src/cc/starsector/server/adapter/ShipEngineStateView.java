package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's per-engine DISABLED state at a simulation
 * step: which engines are knocked out (and which permanently), plus the engine
 * controller's derived flameout state. This is wire-carried state — flameout is
 * DERIVED (disabled-fraction &gt; 0.5 in the controller's per-advance
 * {@code computeEffectiveStats}) from per-engine health trackers whose trajectories
 * are independently stochastic on server and client (randomized repairTime,
 * randomized check intervals, malfunction rolls, locally-simulated hit patterns), so
 * a predictive client can never converge on it implicitly; the server's truth must
 * cross the wire.
 *
 * <p>All reads are public {@code ShipAPI}/{@code ShipEngineControllerAPI} surface,
 * kept behind the bridge: {@code getEngineController().getShipEngines()} and per
 * engine {@code isDisabled()} / {@code isPermanentlyDisabled()}; the controller's
 * {@code isFlamingOut()} / {@code isDisabled()} for the derived state code. Engine
 * bit {@code i} is the {@code getShipEngines()} list index — spec order,
 * deterministic per variant on both sides; a ship with more than 32 engines carries
 * its first 32 (fail-soft). The within-tick {@code shipId} matches the other
 * per-ship views read in the same tick (repository order).
 */
public interface ShipEngineStateView {

    /**
     * Within-tick repository-order id, the same {@code shipId} the motion/durability/
     * flux views read in the same tick use — for pairing the views that describe one
     * ship at one step. Not stable across ticks when the roster changes.
     */
    long shipId();

    /**
     * Bitset of disabled engines: bit {@code i} set iff
     * {@code getShipEngines().get(i).isDisabled()} (engines beyond index 31 are not
     * carried).
     */
    int disabledMask();

    /**
     * Bitset of permanently-disabled engines: bit {@code i} set iff
     * {@code getShipEngines().get(i).isPermanentlyDisabled()} (same indexing as
     * {@link #disabledMask()}).
     */
    int permDisabledMask();

    /**
     * The controller's derived 3-state flameout code: {@code 0} ACTIVE, {@code 1}
     * FLAMING_OUT ({@code isFlamingOut()}), {@code 2} FLAMED_OUT (controller
     * {@code isDisabled()}). Carried for METERS/validation only — a client derives
     * its own flameout by applying the per-engine bits through the real local state
     * machine, never by force-applying this code.
     */
    int controllerState();
}
