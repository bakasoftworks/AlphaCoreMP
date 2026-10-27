package cc.starsector.server.runtime;

/**
 * One client's per-tick PILOT FIRE input for the engine's own weapon-fire path.
 * The fire-side sibling of {@link PilotManeuverIntent}: where the
 * maneuver intent carries the movement keys, this carries the fire button + aim.
 *
 * <p>It is the raw human input Starsector itself takes when shooting: where the mouse points
 * ({@code cursorFacingDeg}, an absolute world angle — the shot is aimed there), which weapon
 * group the player has selected ({@code selectedWeaponGroup}), and whether the fire button is
 * held this tick ({@code fireHeld}). The server maps it ({@link PilotFireMapper}) to a
 * {@link PilotFireCommand} (discharge group N toward an aim direction), which the gate routes to
 * the engine's real fire path ({@code giveCommand(USE_SELECTED_GROUP, ...)}; on the real Mudskipper Mk
 * II's Hellbore) — so the projectile spawn / damage is the ENGINE's, driven by the client's own
 * fire button + aim, not a server-authored projectile (engine-authoritative-first).
 *
 * <p>Deliberately distinct from {@link LiveClientIntent} (the already-proven command-transport
 * fire lane that carries a fire flag over the socket): that lane is the wire; this is the
 * faithful pilot-input shape the server maps to the engine's fire control on the server tick.
 */
public record PilotFireIntent(
        long shipId, float cursorFacingDeg, int selectedWeaponGroup, boolean fireHeld) {

    public PilotFireIntent {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (!Float.isFinite(cursorFacingDeg)) {
            throw new IllegalArgumentException("cursorFacingDeg must be finite; got " + cursorFacingDeg);
        }
        // The DESELECTED sentinel is a legal carried state (the client engine's own
        // selection left the group list). FIRE while deselected is an ENGINE-side no-op on both
        // ends (nothing selected, nothing discharges) — the engine decides, not this record.
        if (selectedWeaponGroup < 0
                && selectedWeaponGroup != PilotIntentFrame.SELECTED_GROUP_DESELECTED) {
            throw new IllegalArgumentException(
                    "selectedWeaponGroup must be >= 0 or SELECTED_GROUP_DESELECTED (-2); got "
                            + selectedWeaponGroup);
        }
    }

    /** Fire button HELD this tick: discharge {@code selectedWeaponGroup} toward the cursor. */
    public static PilotFireIntent hold(long shipId, float cursorFacingDeg, int selectedWeaponGroup) {
        return new PilotFireIntent(shipId, cursorFacingDeg, selectedWeaponGroup, true);
    }

    /** Fire button RELEASED this tick: aim only, no discharge. */
    public static PilotFireIntent release(long shipId, float cursorFacingDeg, int selectedWeaponGroup) {
        return new PilotFireIntent(shipId, cursorFacingDeg, selectedWeaponGroup, false);
    }
}
