package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.adapter.ShipPoseSink;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-tick, server-authoritative <em>pose driver</em>: the consumer that decides
 * WHAT pose each server-owned ship holds and writes it through a
 * {@link ShipPoseSink} every fixed step.
 *
 * <p>{@link ShipPoseSink} is the authoritative <em>write</em> mechanism (the engine's
 * {@code setLoc}/{@code setVel}, Path B, confirmed on the real engine);
 * the {@link EngineCombatBinding}-backed sink owns the engine
 * crossing. What was missing is the layer above it: something that, each tick,
 * turns a control decision (an NPC-AI choice or a remote client's held maneuver
 * intent) into a concrete pose and pushes it onto the owned ship. This driver is
 * that layer — the live multi-ship motion source. Drive two owned ships with
 * different held intents and each integrates its own deterministic trajectory, so
 * two puppets visibly diverge under server authority (the Spike 4 two-puppet
 * motion source named in the handoff).
 *
 * <p>Motion model (deterministic, fixed-step). Each registered ship carries a
 * server-side kinematic state — position, velocity, and a thrust {@code heading}
 * (radians). Per tick it applies the ship's currently held intent, then advances
 * position by the post-thrust velocity (semi-implicit Euler, {@code pos += vel*dt}
 * — the same per-frame integration the engine performs on a written velocity),
 * then writes the resulting {@code (loc, vel)} pose. Because the driver re-asserts
 * the authoritative {@code loc} every tick, it remains the single source of truth:
 * any carry the engine adds from the previously written velocity is overwritten on
 * the next tick, so the trajectory the driver computes is the trajectory the
 * snapshot reads. Intent semantics:
 * <ul>
 *   <li>{@code ACCELERATE} / {@code STRAFE_LEFT} / {@code STRAFE_RIGHT} — add
 *       thrust along the heading (or ±90°), clamped to {@code maxSpeed}.</li>
 *   <li>{@code DECELERATE} — brake along the current velocity by {@code accel*dt},
 *       flooring at rest.</li>
 *   <li>{@code STOP} — zero the velocity.</li>
 *   <li>{@code TURN_LEFT} / {@code TURN_RIGHT} — rotate the thrust heading by
 *       {@code turnRate*dt} (velocity unchanged this tick).</li>
 *   <li>a {@code null} held intent — <em>coast</em>: no thrust, position still
 *       integrates by the current velocity (drift).</li>
 * </ul>
 *
 * <p>The driver never bypasses the sink's authority checks: it forwards each write
 * and records the {@link ShipPoseSink.Result}, so a ship that is not owned, an
 * unbound write boundary, or a malformed pose fails closed exactly as the sink
 * dictates. It holds no engine reference itself — only the project-owned sink.
 */
public final class ShipPoseDriver {

    private static final double HALF_PI = Math.PI / 2.0;

    /**
     * Tunable kinematic limits shared by every driven ship. Server-authoritative
     * and configurable (never hard-coded into the motion math): {@code accel} in
     * world-units/s², {@code maxSpeed} in world-units/s, {@code turnRate} in rad/s.
     */
    public record Kinematics(float accel, float maxSpeed, float turnRateRadians) {
        public Kinematics {
            if (!(accel > 0f) || !Float.isFinite(accel)) {
                throw new IllegalArgumentException("accel must be a finite > 0; got " + accel);
            }
            if (!(maxSpeed > 0f) || !Float.isFinite(maxSpeed)) {
                throw new IllegalArgumentException("maxSpeed must be a finite > 0; got " + maxSpeed);
            }
            if (!(turnRateRadians > 0f) || !Float.isFinite(turnRateRadians)) {
                throw new IllegalArgumentException(
                        "turnRateRadians must be a finite > 0; got " + turnRateRadians);
            }
        }
    }

    /** Per-tick tally of what {@link #advance} did across the driven ships. */
    public record AdvanceSummary(long simTick, int shipsDriven, int posesApplied, int posesRejected) { }

    /** Mutable server-side kinematic state for one owned ship. */
    private static final class ShipState {
        float posX;
        float posY;
        float velX;
        float velY;
        float headingRadians;
        ShipCommandSink.Movement intent; // null == coast
        ShipPoseSink.Result lastResult;

        ShipState(float posX, float posY, float headingRadians) {
            this.posX = posX;
            this.posY = posY;
            this.headingRadians = headingRadians;
        }
    }

    private final ShipPoseSink sink;
    private final Kinematics kinematics;
    // Insertion-ordered so a multi-ship advance is deterministic.
    private final Map<Long, ShipState> ships = new LinkedHashMap<>();

    public ShipPoseDriver(ShipPoseSink sink, Kinematics kinematics) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.kinematics = Objects.requireNonNull(kinematics, "kinematics");
    }

    /**
     * Register a server-owned ship at an initial pose. {@code shipId} is the
     * snapshot's stable repository-order id (the id scheme {@link ShipPoseSink}
     * resolves). Re-registering an id resets its kinematic state.
     */
    public void register(long shipId, float startX, float startY, float headingRadians) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (!Float.isFinite(startX) || !Float.isFinite(startY) || !Float.isFinite(headingRadians)) {
            throw new IllegalArgumentException("initial pose components must be finite");
        }
        ships.put(shipId, new ShipState(startX, startY, headingRadians));
    }

    /** Whether {@code shipId} has been registered with this driver. */
    public boolean isRegistered(long shipId) {
        return ships.containsKey(shipId);
    }

    /** Number of ships this driver advances each tick. */
    public int registeredShipCount() {
        return ships.size();
    }

    /**
     * Hold {@code intent} on {@code shipId} until changed. A {@code null} intent
     * means coast (no thrust; the ship keeps drifting at its current velocity).
     */
    public void setIntent(long shipId, ShipCommandSink.Movement intent) {
        requireRegistered(shipId).intent = intent;
    }

    /**
     * Bridge a remote client's {@link LiveManeuverIntent} (held semantics) onto a
     * registered owned ship: an active maneuver becomes the held thrust intent; a
     * {@code hold}/no-command intent becomes a coast (no server-issued thrust), so
     * the resulting motion is attributable to the client's own intent.
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

    /**
     * Advance every registered ship one fixed step: apply its held intent, integrate
     * its pose, and write the authoritative pose through the sink. Returns a tally of
     * applied vs. rejected writes for the tick.
     */
    public AdvanceSummary advance(long simTick, float dt) {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        if (!(dt > 0f) || !Float.isFinite(dt)) {
            throw new IllegalArgumentException("dt must be a finite > 0; got " + dt);
        }
        int applied = 0;
        int rejected = 0;
        for (Map.Entry<Long, ShipState> entry : ships.entrySet()) {
            ShipState s = entry.getValue();
            integrate(s, dt);
            ShipPoseSink.Result result =
                    sink.writePose(entry.getKey(), simTick, s.posX, s.posY, s.velX, s.velY);
            s.lastResult = result;
            if (result == ShipPoseSink.Result.APPLIED) {
                applied++;
            } else {
                rejected++;
            }
        }
        return new AdvanceSummary(simTick, ships.size(), applied, rejected);
    }

    /**
     * Adapt this driver to the fixed-step loop's advance hook so
     * {@link FixedStepServerLoop#run(long, FixedStepServerLoop.CombatAdvance)} can
     * drive it directly (the loop owns {@code sim_tick} and the fixed step).
     */
    public FixedStepServerLoop.CombatAdvance asCombatAdvance() {
        return (stepAmount, simTick) -> advance(simTick, stepAmount);
    }

    public float poseX(long shipId) { return requireRegistered(shipId).posX; }
    public float poseY(long shipId) { return requireRegistered(shipId).posY; }
    public float velocityX(long shipId) { return requireRegistered(shipId).velX; }
    public float velocityY(long shipId) { return requireRegistered(shipId).velY; }
    public float headingRadians(long shipId) { return requireRegistered(shipId).headingRadians; }

    /** The {@link ShipPoseSink.Result} of the most recent write for {@code shipId}, or {@code null}. */
    public ShipPoseSink.Result lastResult(long shipId) {
        return requireRegistered(shipId).lastResult;
    }

    private void integrate(ShipState s, float dt) {
        ShipCommandSink.Movement intent = s.intent;
        if (intent != null) {
            switch (intent) {
                case ACCELERATE -> thrust(s, s.headingRadians, dt);
                case STRAFE_LEFT -> thrust(s, s.headingRadians + HALF_PI, dt);
                case STRAFE_RIGHT -> thrust(s, s.headingRadians - HALF_PI, dt);
                case DECELERATE -> brake(s, dt);
                case STOP -> { s.velX = 0f; s.velY = 0f; }
                case TURN_LEFT -> s.headingRadians += kinematics.turnRateRadians() * dt;
                case TURN_RIGHT -> s.headingRadians -= kinematics.turnRateRadians() * dt;
            }
        }
        // Semi-implicit Euler: integrate position with the post-thrust velocity.
        s.posX += s.velX * dt;
        s.posY += s.velY * dt;
    }

    private void thrust(ShipState s, double directionRadians, float dt) {
        s.velX += (float) Math.cos(directionRadians) * kinematics.accel() * dt;
        s.velY += (float) Math.sin(directionRadians) * kinematics.accel() * dt;
        clampSpeed(s);
    }

    private void clampSpeed(ShipState s) {
        float max = kinematics.maxSpeed();
        float speedSq = s.velX * s.velX + s.velY * s.velY;
        if (speedSq > max * max) {
            float scale = max / (float) Math.sqrt(speedSq);
            s.velX *= scale;
            s.velY *= scale;
        }
    }

    private void brake(ShipState s, float dt) {
        float speedSq = s.velX * s.velX + s.velY * s.velY;
        if (speedSq <= 0f) {
            return;
        }
        float speed = (float) Math.sqrt(speedSq);
        float decel = kinematics.accel() * dt;
        if (decel >= speed) {
            s.velX = 0f;
            s.velY = 0f;
            return;
        }
        float scale = (speed - decel) / speed;
        s.velX *= scale;
        s.velY *= scale;
    }

    private ShipState requireRegistered(long shipId) {
        ShipState s = ships.get(shipId);
        if (s == null) {
            throw new IllegalArgumentException("ship " + shipId + " is not registered with this driver");
        }
        return s;
    }
}
