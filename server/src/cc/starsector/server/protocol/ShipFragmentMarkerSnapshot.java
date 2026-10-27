package cc.starsector.server.protocol;

/**
 * One fragment-marker record of the FRAGMENT_MARKER tail tier (schema v9): the frame's
 *
 * declaration that entity {@code entityId} is carried PARTIALLY in this frame,
 * asserting authority ONLY over the ship-scoped tiers set in {@code tierMask}
 * (and, when the WEAPON_MOUNT bit is set, only over mounts with
 * {@code mountIndex} in {@code [mountFrom, mountTo)}).
 *
 * <p>Semantics (ABSENT ≠ EMPTY): a masked tier with no record for the entity in
 * this frame is an authoritative EMPTY (clear); an unmasked tier is ABSENT (no
 * assertion — the client holds prior state). Unmarked entities in the same frame
 * keep whole-entity v1 semantics. The marked entity's 72 B ship record MUST also
 * ride in this frame (the anchor law) so every fragment frame stays self-joining.
 *
 * <p>Like the other snapshot tiers this carries only scalar, version-independent
 * state keyed by the stable network {@code entityId}; it never references an
 * engine-internal class.
 */
public record ShipFragmentMarkerSnapshot(long entityId, int tierMask, int mountFrom, int mountTo) {

    /** Frame asserts the entity's flux tier (schema v3 records). */
    public static final int TIER_FLUX = 1;
    /** Frame asserts the entity's weapon-mount tier, range-bounded to [mountFrom, mountTo). */
    public static final int TIER_WEAPON_MOUNT = 1 << 1;
    /** Frame asserts the entity's combat-side allegiance tier (schema v5 records). */
    public static final int TIER_ALLEGIANCE = 1 << 2;
    /** Frame asserts the entity's engine-disabled/flameout tier (schema v6 records). */
    public static final int TIER_ENGINE_STATE = 1 << 3;
    /** Frame asserts the entity's maneuver-command tier (schema v7 records). */
    public static final int TIER_MANEUVER = 1 << 4;
    /** Frame asserts the entity's primary ship-system tier (schema v8 records). */
    public static final int TIER_SYSTEM_STATE = 1 << 5;
    /** The 6 legal tier bits; a future ship-scoped tier takes the next bit. */
    public static final int TIER_MASK_BITS = 0x3F;
    /** All scalar (non-mount) tier bits — what an anchor fragment asserts. */
    public static final int ALL_SCALAR_TIERS = TIER_MASK_BITS & ~TIER_WEAPON_MOUNT;

    public ShipFragmentMarkerSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if ((tierMask & ~TIER_MASK_BITS) != 0 || tierMask == 0) {
            throw new IllegalArgumentException(
                    "tierMask must carry at least one of the 6 tier bits and nothing above 0x3f; got 0x"
                            + Integer.toHexString(tierMask));
        }
        if ((tierMask & TIER_WEAPON_MOUNT) == 0) {
            if (mountFrom != 0 || mountTo != 0) {
                throw new IllegalArgumentException(
                        "mount range must be 0/0 when the WEAPON_MOUNT bit is unset; got ["
                                + mountFrom + ", " + mountTo + ")");
            }
        } else if (mountFrom >= mountTo) {
            // MIN_VALUE/MAX_VALUE are legal bounds (the cross-frame range-tiling law uses
            // them for the first fragment's lower and the last fragment's upper bound).
            throw new IllegalArgumentException(
                    "mount range must satisfy mountFrom < mountTo when the WEAPON_MOUNT bit is set; got ["
                            + mountFrom + ", " + mountTo + ")");
        }
    }

    /**
     * Reconstruct from decoded wire fields, fail-closed: a corrupt frame whose mask
     * carries no tier bit (or any bit above the 6 tier bits), or whose mount range is
     * inconsistent with the WEAPON_MOUNT bit, is rejected by the canonical constructor
     * rather than carried.
     */
    public static ShipFragmentMarkerSnapshot fromWire(
            long entityId, int tierMask, int mountFrom, int mountTo) {
        return new ShipFragmentMarkerSnapshot(entityId, tierMask, mountFrom, mountTo);
    }
}
