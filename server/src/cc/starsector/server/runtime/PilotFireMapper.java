package cc.starsector.server.runtime;

/**
 * Server-tick mapping from a client's raw {@link PilotFireIntent} (mouse aim + selected weapon
 * group + fire button) to the {@link PilotFireCommand} the engine's own weapon-fire path consumes.
 * The fire-side sibling of {@link PilotManeuverMapper}.
 *
 * <p>It is deliberately engine-identity-free and pure (no engine imports, no per-call state), so
 * the same mapping drives the real-engine gate (
 * {@code RealEngineSubstrateMudskipperFireGate}) and, later, the live socket publisher — and so it
 * is exhaustively testable game-free.
 *
 * <p>Faithfulness rules (matching how a player shoots in Starsector):
 * <ul>
 *   <li>The shot is always AIMED at the cursor: {@code aimFacingDeg == cursorFacingDeg}
 *       (point-at-cursor aim, exactly how the player aims with the mouse).</li>
 *   <li>The fire button HELD discharges the selected weapon group toward that aim
 *       ({@code fire == true}, {@code weaponGroup == selectedWeaponGroup}).</li>
 *   <li>The fire button RELEASED holds fire ({@code fire == false}) while keeping the aim — so a
 *       client can track a target without shooting, matching the aim-only behaviour.</li>
 * </ul>
 */
public final class PilotFireMapper {

    private PilotFireMapper() {
    }

    /**
     * Map one client fire intent to the discharge instruction the engine fire path consumes.
     *
     * <p>A held trigger with the {@link PilotIntentFrame#SELECTED_GROUP_DESELECTED}
     * sentinel maps to {@link PilotFireCommand#holdFire} — with nothing selected the client's own
     * engine discharges nothing while the aim stays live, and that no-discharge-keep-aiming state
     * IS {@code holdFire}'s contract. Mapping it to a firing command instead would construct a
     * group-addressed discharge with no group (the record rejects that by invariant — its group
     * consumers, the gate lane, index real groups) and would crash the held serve lane
     * on the first fire-held deselect tick.
     */
    public static PilotFireCommand map(PilotFireIntent intent) {
        float aim = normalizeDeg(intent.cursorFacingDeg());
        if (intent.fireHeld()
                && intent.selectedWeaponGroup() != PilotIntentFrame.SELECTED_GROUP_DESELECTED) {
            return new PilotFireCommand(true, intent.selectedWeaponGroup(), aim);
        }
        return PilotFireCommand.holdFire(aim);
    }

    /** Normalize an angle to {@code [0,360)} (shared convention with {@link PilotManeuverMapper}). */
    static float normalizeDeg(float deg) {
        float r = deg % 360.0f;
        return r < 0.0f ? r + 360.0f : r;
    }
}
