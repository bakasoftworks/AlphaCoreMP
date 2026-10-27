package cc.starsector.server.runtime;

/**
 * One client's per-tick PILOT input for the faithful {@code BasicEngineAI} movement path
 * (step 2 of the 1v1 combat demo).
 *
 * <p>This is the FAITHFUL-movement sibling of {@link LiveManeuverIntent}, and is deliberately a
 * different wire shape because it targets a different engine boundary. {@code LiveManeuverIntent}
 * carries a single {@code ShipCommandSink.Movement} ordinal that the server routes through the
 * public {@code ShipAPI.giveCommand} enum — the path that, measured, does
 * NOT faithfully rotate/translate a headless ship. This intent instead carries the raw human
 * pilot input Starsector itself takes: where the mouse points ({@code cursorFacingDeg}, an
 * absolute world angle) plus a bitset of the movement keys held this tick ({@code heldControls}:
 * W/S forward-back, A/D strafe). The server maps it ({@link PilotManeuverMapper}) to the
 * desired-facing / desired-heading / speed-fraction the engine's own {@code BasicEngineAI}
 * controller consumes (proven faithful headless),
 * so the per-tick motion is the engine's, driven by the client's own aim + keys —
 * not a server script and not the unfaithful public enum.
 */
public record PilotManeuverIntent(long shipId, float cursorFacingDeg, int heldControls) {

    /** Thrust forward along the nose (the W key). */
    public static final int THRUST_FORWARD = 1 << 0;
    /** Thrust backward, 180 deg off the nose (the S key). */
    public static final int THRUST_BACK = 1 << 1;
    /** Strafe left, +90 deg off the nose (the A key). */
    public static final int STRAFE_LEFT = 1 << 2;
    /** Strafe right, -90 deg off the nose (the D key). */
    public static final int STRAFE_RIGHT = 1 << 3;
    /**
     * The strafe MODIFIER is held (Starsector's Shift key). When set, the
     * {@code STRAFE_LEFT}/{@code STRAFE_RIGHT} (A/D) bits mean STRAFE and the nose servos to the cursor (the
     * legacy single-arg {@link PilotManeuverMapper#map(PilotManeuverIntent)} behavior). When CLEAR (the
     * default), A/D instead TURN the ship and the nose is NOT servoed to the cursor — exactly Starsector's
     * normal flight. The {@code map(intent, currentFacingDeg)} overload reads this bit to pick the mode; the
     * legacy single-arg {@code map} ignores it (it is always the modifier-held semantics). Modeling Shift as a
     * bit here keeps it on the existing {@code heldControls} int, so the wire codec is byte-unchanged.
     */
    public static final int STRAFE_MODIFIER = 1 << 4;

    /** Mask of every defined held-control bit (including the strafe modifier). */
    public static final int ALL_CONTROLS =
            THRUST_FORWARD | THRUST_BACK | STRAFE_LEFT | STRAFE_RIGHT | STRAFE_MODIFIER;

    public PilotManeuverIntent {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (!Float.isFinite(cursorFacingDeg)) {
            throw new IllegalArgumentException("cursorFacingDeg must be finite; got " + cursorFacingDeg);
        }
        if ((heldControls & ~ALL_CONTROLS) != 0) {
            throw new IllegalArgumentException(
                    "heldControls has bits outside the defined controls: " + heldControls);
        }
    }

    /** Whether {@code controlBit} (one of the {@code *} constants) is held this tick. */
    public boolean holds(int controlBit) {
        return (heldControls & controlBit) != 0;
    }

    /** Aim only: point at the cursor, hold no movement key (facing servo, no thrust). */
    public static PilotManeuverIntent aim(long shipId, float cursorFacingDeg) {
        return new PilotManeuverIntent(shipId, cursorFacingDeg, 0);
    }
}
