package cc.starsector.server.runtime;

/**
 * The fire instruction {@link PilotFireMapper} produces from a {@link PilotFireIntent}: the
 * inputs the engine's own weapon-fire path consumes on the server tick
 * — whether to discharge this tick ({@code fire}), which weapon group
 * ({@code weaponGroup}), and the world direction to aim ({@code aimFacingDeg}).
 *
 * <p>The aim is carried as a FACING (degrees), not a world point, so this command — like
 * {@link PilotManeuverCommand} — stays engine-identity-free and exhaustively testable game-free;
 * the gate converts the facing to the concrete aim point the engine's
 * {@code giveCommand(USE_SELECTED_GROUP, location, flags)} wants.
 *
 * <p>{@code fire == false} models "fire button released": no discharge this tick. When
 * {@code fire == false} the {@code weaponGroup} is the no-group sentinel {@code -1} and must be
 * ignored; {@code aimFacingDeg} stays meaningful (the ship is still aimed).
 */
public record PilotFireCommand(boolean fire, int weaponGroup, float aimFacingDeg) {

    /** No-group sentinel used when {@code fire == false}. */
    public static final int NO_GROUP = -1;

    public PilotFireCommand {
        if (!Float.isFinite(aimFacingDeg)) {
            throw new IllegalArgumentException("aimFacingDeg must be finite; got " + aimFacingDeg);
        }
        if (fire) {
            if (weaponGroup < 0) {
                throw new IllegalArgumentException(
                        "a firing command requires weaponGroup >= 0; got " + weaponGroup);
            }
        } else if (weaponGroup != NO_GROUP) {
            throw new IllegalArgumentException(
                    "a non-firing command must use NO_GROUP (-1); got " + weaponGroup);
        }
    }

    /** Hold fire: aim at {@code aimFacingDeg}, discharge nothing this tick. */
    public static PilotFireCommand holdFire(float aimFacingDeg) {
        return new PilotFireCommand(false, NO_GROUP, aimFacingDeg);
    }
}
