package cc.starsector.server.runtime;

/**
 * The engine-control triple {@link PilotManeuverMapper} produces from a
 * {@link PilotManeuverIntent}: exactly the inputs the engine's own {@code BasicEngineAI}
 * movement controller consumes each tick — a desired FACING (always; the ship
 * servos its nose toward the cursor) and, when a movement key is held, a desired world HEADING
 * plus a speed fraction (the controller then issues the engine-internal thrust commands that
 * {@code EngineController} integrates).
 *
 * <p>{@code headingActive == false} models "no movement key held": the mapper sets only the
 * desired facing and leaves the heading at the controller's unset sentinel, so
 * {@code BasicEngineAI} only servos facing and issues no thrust (matching the facing-only phases).
 * When {@code headingActive == false} the heading/fraction fields are 0
 * and must be ignored.
 */
public record PilotManeuverCommand(
        float desiredFacingDeg,
        boolean headingActive,
        float desiredHeadingDeg,
        float speedFraction) {

    public PilotManeuverCommand {
        if (!Float.isFinite(desiredFacingDeg)) {
            throw new IllegalArgumentException("desiredFacingDeg must be finite; got " + desiredFacingDeg);
        }
        if (headingActive) {
            if (!Float.isFinite(desiredHeadingDeg)) {
                throw new IllegalArgumentException(
                        "active heading requires finite desiredHeadingDeg; got " + desiredHeadingDeg);
            }
            if (!(speedFraction > 0.0f) || speedFraction > 1.0f) {
                throw new IllegalArgumentException(
                        "active speedFraction must be in (0,1]; got " + speedFraction);
            }
        }
    }

    /** Facing-only command: servo the nose to {@code desiredFacingDeg}, no thrust. */
    public static PilotManeuverCommand facingOnly(float desiredFacingDeg) {
        return new PilotManeuverCommand(desiredFacingDeg, false, 0.0f, 0.0f);
    }
}
