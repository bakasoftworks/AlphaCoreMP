package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-tick, server-authoritative <em>command driver</em>: the consumer that routes
 * each server-owned ship's currently-held movement intent to <em>that</em> ship
 * through a {@link ShipCommandSink} every fixed step, so two separate clients each
 * pilot their OWN owned ship under genuine command authority.
 *
 * <p>This is the COMMAND complement of {@link ShipPoseDriver}, and the distinction is
 * the whole point. {@code ShipPoseDriver} writes an authoritative {@code (loc, vel)}
 * pose directly ({@code setLoc}/{@code setVel}) — the server <em>scripts</em>
 * the motion. This driver issues a movement COMMAND (
 * {@code ShipAPI.giveCommand(ACCELERATE/TURN_LEFT/…)}) and lets the engine's own
 * movement integration do the motion — the server <em>commands</em>, the engine
 * integrates. That is the authentic "two real windows each piloting their own ship"
 * model: the divergence between the two owned ships is a
 * consequence of each client's command, not a server-computed trajectory.
 *
 * <p>What was already in place is the per-ship sink: {@link EngineCombatBinding}'s
 * {@code submitPlayerShipMovement} resolves an owned ship by repository-order id and
 * issues the mapped command on it (a 2nd owned
 * ship's command lands on the 2nd ship, not {@code getPlayerShip()}), fronted by the
 * {@link cc.starsector.server.bridge.EngineBackedShipCommandSink} adapter. What was
 * missing is the layer above it: the live maneuver path drove only one bound ship
 * (the single-intent {@link ClientManeuverGate} and a driver that always commanded
 * repository ship 0). This driver is that missing layer — the multi-client → multi-ship
 * command router.
 *
 * <p>Held semantics (matching the per-frame nature of {@code ShipAPI.giveCommand}): a
 * held intent is re-issued every tick while held; a {@code null} held intent is a
 * <em>coast</em> — no command is issued, so the ship only drifts under the engine's
 * own integration and the motion that crosses back to that client is attributable to
 * its own command, not a server script. Maps a remote client's {@link LiveManeuverIntent}
 * (active maneuver → held movement; hold/no-command → coast) the same way
 * {@link ShipPoseDriver#applyManeuver} does, so the two drivers share intent semantics.
 *
 * <p>The driver never bypasses the sink's authority checks: it forwards each command and
 * records the {@link ShipCommandSink.Result}, so a ship that is not owned, an unmapped
 * command, or a malformed request fails closed exactly as the sink dictates. It holds no
 * engine reference itself — only the project-owned sink and the command {@code Source}.
 */
public final class ShipCommandDriver {

    /** Per-tick tally of what {@link #advance} did across the driven ships. */
    public record AdvanceSummary(long simTick, int shipsCommanded, int commandsApplied, int commandsRejected) { }

    /** Mutable per-ship held intent + last command result. */
    private static final class ShipState {
        ShipCommandSink.Movement intent; // null == coast (no command issued this tick)
        ShipCommandSink.Result lastResult;
    }

    private final ShipCommandSink sink;
    private final ShipCommandSink.Source source;
    // Insertion-ordered so a multi-ship advance issues commands deterministically.
    private final Map<Long, ShipState> ships = new LinkedHashMap<>();

    public ShipCommandDriver(ShipCommandSink sink, ShipCommandSink.Source source) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Register a server-owned ship as commandable. {@code shipId} is the snapshot's
     * stable repository-order id (the id scheme {@link ShipCommandSink} resolves).
     * Unlike {@link ShipPoseDriver}, no initial pose is held — the engine owns the
     * ship's position; this driver only routes commands. Re-registering an id clears
     * its held intent.
     */
    public void register(long shipId) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        ships.put(shipId, new ShipState());
    }

    /** Whether {@code shipId} has been registered with this driver. */
    public boolean isRegistered(long shipId) {
        return ships.containsKey(shipId);
    }

    /** Number of ships this driver may command each tick. */
    public int registeredShipCount() {
        return ships.size();
    }

    /**
     * Hold {@code intent} on {@code shipId} until changed. A {@code null} intent means
     * coast (no command issued; the ship drifts under the engine's own integration).
     */
    public void setIntent(long shipId, ShipCommandSink.Movement intent) {
        requireRegistered(shipId).intent = intent;
    }

    /**
     * Bridge a remote client's {@link LiveManeuverIntent} (held semantics) onto a
     * registered owned ship: an active maneuver becomes the held command; a
     * {@code hold}/no-command intent becomes a coast (no server-issued command), so the
     * resulting motion is attributable to the client's own intent.
     */
    public void applyManeuver(LiveManeuverIntent intent) {
        Objects.requireNonNull(intent, "intent");
        requireRegistered(intent.shipId());
        setIntent(intent.shipId(), intent.maneuver() ? intent.movement() : null);
    }

    /** The intent currently held on {@code shipId} ({@code null} == coast). */
    public ShipCommandSink.Movement intentOf(long shipId) {
        return requireRegistered(shipId).intent;
    }

    /** The {@link ShipCommandSink.Result} of the most recent command for {@code shipId}
     *  ({@code null} if it coasted / no command has been issued). */
    public ShipCommandSink.Result lastResult(long shipId) {
        return requireRegistered(shipId).lastResult;
    }

    /**
     * Advance one fixed step: for every registered ship that holds an active intent,
     * issue its held movement command to that specific owned ship through the sink and
     * record the result; a ship with a {@code null} intent coasts (no command). Returns
     * a tally of commanded / applied / rejected for the tick.
     */
    public AdvanceSummary advance(long simTick) {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        int commanded = 0;
        int applied = 0;
        int rejected = 0;
        for (Map.Entry<Long, ShipState> entry : ships.entrySet()) {
            ShipState s = entry.getValue();
            ShipCommandSink.Movement intent = s.intent;
            if (intent == null) {
                s.lastResult = null; // coast: no command issued this tick
                continue;
            }
            commanded++;
            ShipCommandSink.Result result = sink.submitMovement(entry.getKey(), source, simTick, intent);
            s.lastResult = result;
            if (result == ShipCommandSink.Result.APPLIED) {
                applied++;
            } else {
                rejected++;
            }
        }
        return new AdvanceSummary(simTick, commanded, applied, rejected);
    }

    /**
     * Adapt this driver to the fixed-step loop's advance hook so
     * {@link FixedStepServerLoop#run(long, FixedStepServerLoop.CombatAdvance)} can drive
     * it directly. The step amount is unused — the engine owns motion integration; the
     * driver only owns command issuance — so only the loop's {@code sim_tick} is taken.
     */
    public FixedStepServerLoop.CombatAdvance asCombatAdvance() {
        return (stepAmount, simTick) -> advance(simTick);
    }

    private ShipState requireRegistered(long shipId) {
        ShipState s = ships.get(shipId);
        if (s == null) {
            throw new IllegalArgumentException("ship " + shipId + " is not registered with this driver");
        }
        return s;
    }
}
