package cc.starsector.server.runtime;

/**
 * Server-tick mapping from a client's raw {@link PilotManeuverIntent} (mouse aim + held
 * movement keys) to the {@link PilotManeuverCommand} the engine's own {@code BasicEngineAI}
 * controller consumes. This is the
 * "intent -&gt; {@code BasicEngineAI} mapping on the server tick" — the open frontier the demo
 * needed beyond the already-proven socket/command transport (which carries the intent wire).
 *
 * <p>It is deliberately engine-identity-free and pure (no engine imports, no per-call state), so
 * the same mapping drives the real-engine gate (
 * {@code RealEngineSubstrateMudskipperServerMoveGate}) and, later, the live socket publisher —
 * and so it is exhaustively testable game-free.
 *
 * <p>Faithfulness rules (matching the proven input stimulus, so routing through the
 * server preserves the measured fidelity):
 * <ul>
 *   <li>FACING always servos to the cursor: {@code desiredFacing == cursorFacingDeg}
 *       (point-at-cursor aim, exactly how the player aims with the mouse in Starsector).</li>
 *   <li>The held W/A/S/D keys define a SHIP-RELATIVE movement direction (forward W == along the
 *       nose; back S == 180 deg off; strafe left A == +90 deg; strafe right D == -90 deg). The
 *       mapper sums the held directions as unit vectors in the ship frame, rotates the resultant
 *       into the world by the cursor facing, and emits that as the desired HEADING with a speed
 *       fraction == the resultant magnitude clamped to 1 (so diagonals never exceed full
 *       throttle).</li>
 *   <li>No movement key held (or opposing keys cancelling on both axes) -&gt; a facing-only
 *       command (no thrust), so the controller just aims — matching the facing-only phases.
 * </li>
 * </ul>
 *
 * <p><b>Shift-gated faithfulness.</b> The rules above describe Starsector's
 * STRAFE-modifier (Shift-held) flight: nose locked to the cursor, A/D strafe. They are NOT the default
 * flight, where A/D <em>turn</em> the ship and the nose is whatever you've turned it to — the mouse only
 * aims weapons. The single-arg {@link #map(PilotManeuverIntent)} keeps the legacy modifier-held semantics
 * (every prior caller / gate sends no modifier bit, so it is byte-for-byte unchanged). The reported
 * "own ship always flies toward the cursor" bug was this lane being used unconditionally, so the new
 * {@link #map(PilotManeuverIntent, float)} reads {@link PilotManeuverIntent#STRAFE_MODIFIER} and only
 * locks the nose to the cursor when the human actually holds Shift; otherwise A/D turn and W/S thrust
 * along the current nose.
 */
public final class PilotManeuverMapper {

    /**
     * Default-mode (no Shift) turn target lead, in degrees off the current facing. A/D set
     * the desired facing this far ahead in the turn direction, which is well beyond what {@code BasicEngineAI}
     * can close in one tick, so the ship turns at its own max rate while A/D are held — Starsector's normal
     * A/D radius turn — and holds the current facing the instant A/D release (desired facing == current).
     */
    private static final float TURN_LEAD_DEG = 60.0f;

    private PilotManeuverMapper() {
    }

    /**
     * Map one client intent to the engine-control triple {@code BasicEngineAI} consumes, with the legacy
     * STRAFE-modifier (Shift-held) semantics: the nose ALWAYS servos to the cursor and A/D strafe. This is the
     * proven behavior; every existing caller (the self-test/duel gates)
     * sends no {@link PilotManeuverIntent#STRAFE_MODIFIER} bit, so this method's output is unchanged for them.
     * Live pilot lanes should call {@link #map(PilotManeuverIntent, float)} so default flight (A/D turn, no
     * cursor lock) is faithful.
     */
    public static PilotManeuverCommand map(PilotManeuverIntent intent) {
        float facing = normalizeDeg(intent.cursorFacingDeg());

        // Ship-frame movement axes, measured off the nose (which points at the cursor):
        //   forward (W) = 0 deg, back (S) = 180 deg, strafe left (A) = +90, strafe right (D) = -90.
        // Sum the held directions as a ship-frame vector (x = forward along nose, y = left).
        float forward = (intent.holds(PilotManeuverIntent.THRUST_FORWARD) ? 1.0f : 0.0f)
                - (intent.holds(PilotManeuverIntent.THRUST_BACK) ? 1.0f : 0.0f);
        float left = (intent.holds(PilotManeuverIntent.STRAFE_LEFT) ? 1.0f : 0.0f)
                - (intent.holds(PilotManeuverIntent.STRAFE_RIGHT) ? 1.0f : 0.0f);

        float mag = (float) Math.hypot(forward, left);
        if (mag < 1.0e-4f) {
            // No (or fully-cancelling) movement input: aim only, no thrust.
            return PilotManeuverCommand.facingOnly(facing);
        }
        // Offset of the movement vector from the nose, in degrees (left strafe is +90).
        float offsetDeg = (float) Math.toDegrees(Math.atan2(left, forward));
        float headingDeg = normalizeDeg(facing + offsetDeg);
        float fraction = Math.min(1.0f, mag);
        return new PilotManeuverCommand(facing, true, headingDeg, fraction);
    }

    /**
     * Shift-gated faithful mapping (the "own ship always flies toward the cursor"
     * fix). Replicates Starsector's two flight modes instead of unconditionally locking the nose to the cursor:
     * <ul>
     *   <li><b>{@link PilotManeuverIntent#STRAFE_MODIFIER} held</b> (the human holds Shift): the nose servos to
     *       the cursor and A/D strafe — delegates to the legacy {@link #map(PilotManeuverIntent)}.</li>
     *   <li><b>modifier released</b> (the default): A/D <em>turn</em> the ship at its own rate (A = turn left /
     *       CCW, D = turn right / CW) by leading the desired facing {@link #TURN_LEAD_DEG} off the CURRENT
     *       facing; the nose is NOT servoed to the cursor; W/S thrust forward/back along the current nose. With
     *       no A/D the desired facing equals the current facing (the ship holds its heading, no cursor pull),
     *       and with no W/S it is a facing-only command (turn / idle, no thrust).</li>
     * </ul>
     * {@code currentFacingDeg} is the ship's actual facing this tick (e.g. {@code ship.getFacing()}); it is used
     * only in the default mode to compute the relative turn target, so the server's authoritative own-ship pose
     * tracks the window's native player flight (and the reconcile stops dragging the local ship toward the
     * cursor). Pure + engine-identity-free, like the rest of this mapper, so it stays game-free testable.
     */
    public static PilotManeuverCommand map(PilotManeuverIntent intent, float currentFacingDeg) {
        if (intent.holds(PilotManeuverIntent.STRAFE_MODIFIER)) {
            return map(intent); // Shift held: faithful strafe + face-cursor (the legacy lane)
        }
        float current = normalizeDeg(currentFacingDeg);
        // A = turn left (CCW, +deg), D = turn right (CW, -deg). Lead the desired facing in the turn direction
        // so BasicEngineAI turns at the ship's max rate while held; 0 -> hold the current facing.
        int turnSign = (intent.holds(PilotManeuverIntent.STRAFE_LEFT) ? 1 : 0)
                - (intent.holds(PilotManeuverIntent.STRAFE_RIGHT) ? 1 : 0);
        float desiredFacing = turnSign == 0
                ? current
                : normalizeDeg(current + turnSign * TURN_LEAD_DEG);
        // W/S thrust along the CURRENT nose (which is itself turning when A/D are held). A/D do NOT thrust here.
        float forward = (intent.holds(PilotManeuverIntent.THRUST_FORWARD) ? 1.0f : 0.0f)
                - (intent.holds(PilotManeuverIntent.THRUST_BACK) ? 1.0f : 0.0f);
        if (Math.abs(forward) < 1.0e-4f) {
            return PilotManeuverCommand.facingOnly(desiredFacing); // turning and/or idle, no thrust
        }
        float headingDeg = forward > 0.0f ? current : normalizeDeg(current + 180.0f);
        return new PilotManeuverCommand(desiredFacing, true, headingDeg, 1.0f);
    }

    /** Normalize an angle to {@code [0,360)}. */
    static float normalizeDeg(float deg) {
        float r = deg % 360.0f;
        return r < 0.0f ? r + 360.0f : r;
    }
}
