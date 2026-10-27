package cc.starsector.client.net;

/**
 * One weapon mount's authoritative combat-readiness state, decoded from the schema-v4
 * per-mount tier of the server's {@code AWSS} world-snapshot frame (
 * client decode).
 *
 * <p>This is the client-side projection of the server's {@code ShipWeaponMountSnapshot}:
 * the mount is keyed by ({@code entityId}, {@link #mountIndex()}) on the wire; here the
 * {@code entityId} half is implicit — the decode path attaches each mount to its ship's
 * {@link ServerEntityPose} ({@link ServerEntityPose#weaponMounts()}). {@code mountIndex}
 * is the per-ship 0-based ordinal of {@code ShipAPI.getAllWeapons()} on the SERVER's
 * engine (the bridge enumerates that list in order); both sides instantiate the same
 * variant, so the same index resolves the same local {@code WeaponAPI} — the same
 * order-contract class as the schema-v6 spec-order engine masks.
 *
 * <p>All wire scalars are kept (not just the {@code disabled} bit the reconcile lane
 * consumes) so the game-free codec cross-check
 * can assert the whole record
 * round-trips bit-for-bit against the real server codec.
 *
 * <p>Pure Java with no Starsector-API dependency (net-package rule).
 */
public final class ServerWeaponMountState {

    /** Wire bit for {@link #firing()} — mirrors {@code ShipWeaponMountSnapshot.FLAG_FIRING}. */
    public static final int FLAG_FIRING = 1;
    /** Wire bit for {@link #usesAmmo()} — mirrors {@code ShipWeaponMountSnapshot.FLAG_USES_AMMO}. */
    public static final int FLAG_USES_AMMO = 1 << 1;
    /** Wire bit for {@link #disabled()} — mirrors {@code ShipWeaponMountSnapshot.FLAG_DISABLED}. */
    public static final int FLAG_DISABLED = 1 << 2;
    /**
     * Wire bit for {@link #autofiring()} — mirrors {@code ShipWeaponMountSnapshot.FLAG_AUTOFIRE}:
     * the mount's owning weapon GROUP is autofiring on the authoritative hull.
     */
    public static final int FLAG_AUTOFIRE = 1 << 3;
    /**
     * Wire bit for {@link #selected()} — mirrors {@code ShipWeaponMountSnapshot.FLAG_SELECTED}:
     * the mount's owning weapon GROUP is the ship's SELECTED group on the
     * authoritative hull. Charter-R8 value widening of the existing flags int (no schema bump);
     * unknown high bits were always stored verbatim here, so a client older than this flag simply never
     * tested this bit.
     */
    public static final int FLAG_SELECTED = 1 << 4;

    private final int mountIndex;
    private final int weaponContentId;
    private final float cooldownRemaining;
    private final float cooldown;
    private final int ammo;
    private final int maxAmmo;
    private final float chargeLevel;
    private final int statusFlags;

    public ServerWeaponMountState(
            int mountIndex,
            int weaponContentId,
            float cooldownRemaining,
            float cooldown,
            int ammo,
            int maxAmmo,
            float chargeLevel,
            int statusFlags) {
        this.mountIndex = mountIndex;
        this.weaponContentId = weaponContentId;
        this.cooldownRemaining = cooldownRemaining;
        this.cooldown = cooldown;
        this.ammo = ammo;
        this.maxAmmo = maxAmmo;
        this.chargeLevel = chargeLevel;
        this.statusFlags = statusFlags;
    }

    /** Per-ship 0-based mount ordinal (server {@code getAllWeapons()} order — see class doc). */
    public int mountIndex() {
        return mountIndex;
    }

    /** Interned weapon spec id (server-owned content table); {@code 0} = unknown/uninterned. */
    public int weaponContentId() {
        return weaponContentId;
    }

    public float cooldownRemaining() {
        return cooldownRemaining;
    }

    public float cooldown() {
        return cooldown;
    }

    /** Ammo count; a sentinel (often large/negative) when {@link #usesAmmo()} is false. */
    public int ammo() {
        return ammo;
    }

    public int maxAmmo() {
        return maxAmmo;
    }

    public float chargeLevel() {
        return chargeLevel;
    }

    /** The packed status int exactly as carried on the wire. */
    public int statusFlags() {
        return statusFlags;
    }

    public boolean firing() {
        return (statusFlags & FLAG_FIRING) != 0;
    }

    public boolean usesAmmo() {
        return (statusFlags & FLAG_USES_AMMO) != 0;
    }

    /**
     * The authoritative per-mount disabled bit — the scalar the {@code cc.predict.weaponState}
     * reconcile lane compares against the local {@code WeaponAPI.isDisabled()} (the
     * w1 client-only 6.147 s weapon disable had {@code disabled=false} here the whole run).
     */
    public boolean disabled() {
        return (statusFlags & FLAG_DISABLED) != 0;
    }

    /**
     * true when this mount's owning weapon GROUP is autofiring on the authoritative
     * hull. Per-mount duplication of a per-group fact — the {@code cc.predict.foeAutofire} lane
     * re-derives per-group state from the local replica's own mount→group mapping and diffs it
     * against {@code WeaponGroupAPI.isAutofiring()}.
     */
    public boolean autofiring() {
        return (statusFlags & FLAG_AUTOFIRE) != 0;
    }

    /**
     * true when this mount's owning weapon GROUP is the ship's SELECTED group
     * on the authoritative hull. The {@code cc.predict.foeSelection} lane re-derives the
     * selected group ordinal from the local replica's own mount→group mapping and converges
     * via {@code giveCommand(SELECT_GROUP)} — the (114)(d) far-out-of-arc beam-terminus fix.
     */
    public boolean selected() {
        return (statusFlags & FLAG_SELECTED) != 0;
    }

    @Override
    public String toString() {
        return "ServerWeaponMountState{mount=" + mountIndex
                + ",contentId=" + weaponContentId
                + ",cdRem=" + cooldownRemaining
                + ",cd=" + cooldown
                + ",ammo=" + ammo + "/" + maxAmmo
                + ",charge=" + chargeLevel
                + ",firing=" + firing()
                + ",disabled=" + disabled() + "}";
    }
}
