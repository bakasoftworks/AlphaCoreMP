package cc.starsector.server.protocol;

import cc.starsector.server.adapter.WeaponMountView;
import java.util.Objects;

/**
 * One ship weapon mount's authoritative combat-readiness state as it is replicated
 * to clients: the per-mount tier read off the weapon-mount hook ({@link WeaponMountView}).
 *
 * <p>This is the wire-carried counterpart of {@link WeaponMountView}, the per-weapon
 * (sub-ship) tier that sits one level below the ship-level {@link ShipFluxSnapshot}
 * flux/shield record. Flux and hull tell a client whether a ship is broadly
 * combat-effective; per-mount state tells it which guns can actually fire
 * <em>this</em> instant: a mount on cooldown, out of ammo, or disabled cannot
 * contribute damage even on a healthy, low-flux ship. The read was
 * observed driving on the real engine (engine-driven
 * {@code setAmmo} 20→10→20 and {@code setRemainingCooldownTo} 0→5→0 tracked through
 * the product binding); this record is what folds those scalars onto the AWSS wire.
 *
 *
 * <p>A ship can own several mounts, so a mount is keyed by the pair
 * ({@link #entityId()}, {@link #mountIndex()}): {@code entityId} is the same stable
 * network identity as the ship's {@link AuthoritativeEntitySnapshot} (so a decoder
 * pairs a mount to its ship) and {@code mountIndex} is the per-ship 0-based ordinal.
 * Like the other snapshot tiers it carries only scalar/version-independent state and
 * never a non-API engine class (the weapon impl stays inside the bridge): the
 * weapon spec id is interned to a numeric {@link #weaponContentId()} through the same
 * server-owned content table as the ship/non-ship content ids, so only a compact int
 * travels on each record. The five status booleans are packed onto the wire as a
 * single {@link #statusFlags()} int.
 */
public record ShipWeaponMountSnapshot(
        long entityId,
        int mountIndex,
        int weaponContentId,
        float cooldownRemaining,
        float cooldown,
        int ammo,
        int maxAmmo,
        float chargeLevel,
        boolean firing,
        boolean usesAmmo,
        boolean disabled,
        boolean autofire,
        boolean selected) {

    /** Reserved {@code weaponContentId} when the weapon spec id is unknown/uninterned. */
    public static final int NO_CONTENT = 0;

    /** Wire bit for {@link #firing()} in {@link #statusFlags()}. */
    public static final int FLAG_FIRING = 1;
    /** Wire bit for {@link #usesAmmo()} in {@link #statusFlags()}. */
    public static final int FLAG_USES_AMMO = 1 << 1;
    /** Wire bit for {@link #disabled()} in {@link #statusFlags()}. */
    public static final int FLAG_DISABLED = 1 << 2;
    /**
     * Wire bit for {@link #autofire()} in {@link #statusFlags()}: the
     * mount's owning weapon GROUP is autofiring on the authoritative hull. Per-mount
     * duplication of a per-group fact so autofire rides the existing mount tier with
     * zero added wire bytes; a client re-derives the per-group state from its own
     * mount→group mapping.
     */
    public static final int FLAG_AUTOFIRE = 1 << 3;
    /**
     * Wire bit for {@link #selected()} in {@link #statusFlags()}: the
     * mount's owning weapon GROUP is the ship's SELECTED group on the authoritative hull.
     * Per-mount duplication of a per-group fact, exactly like {@link #FLAG_AUTOFIRE} — a
     * client re-derives the selected group ordinal from its own mount→group mapping and
     * converges its replica via {@code giveCommand(SELECT_GROUP)}; on the replica the
     * selected group's aim param passes the engine's group dispatch unconditionally
     * ({@code WeaponGroup.getParamIfNotInactiveHardpoint} first clause), which is what lets
     * a far out-of-arc cursor terminus replicate (the (114)(d) fix). Charter-R8 value
     * widening, not a schema bump: the bit rides the existing flags int (AWSS full-int,
     * AWSD u8 — bits 4–7 were spare), old writers never set it, and an older client
     * stores unknown bits verbatim and tests only named bits, so a stale peer degrades to
     * the pre-fix behavior instead of failing. Encode is gated by the rollback knob
     * {@code cc.server.mountSelectedFlag} (pre-widening bytes for A/B).
     */
    public static final int FLAG_SELECTED = 1 << 4;
    private static final int ALL_FLAGS =
            FLAG_FIRING | FLAG_USES_AMMO | FLAG_DISABLED | FLAG_AUTOFIRE | FLAG_SELECTED;

    public ShipWeaponMountSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if (mountIndex < 0) {
            throw new IllegalArgumentException("mountIndex must be >= 0; got " + mountIndex);
        }
        if (weaponContentId < 0) {
            throw new IllegalArgumentException("weaponContentId must be >= 0; got " + weaponContentId);
        }
        requireFinite("cooldownRemaining", cooldownRemaining);
        requireFinite("cooldown", cooldown);
        requireFinite("chargeLevel", chargeLevel);
        // ammo/maxAmmo are left unconstrained: when usesAmmo is false the engine reports
        // a sentinel (often a large/negative count), which is carried faithfully so a
        // client can still distinguish "energy/unlimited" from a finite pool.
    }

    /** Stable wire packing of the five status booleans into one int. */
    public int statusFlags() {
        int flags = 0;
        if (firing) {
            flags |= FLAG_FIRING;
        }
        if (usesAmmo) {
            flags |= FLAG_USES_AMMO;
        }
        if (disabled) {
            flags |= FLAG_DISABLED;
        }
        if (autofire) {
            flags |= FLAG_AUTOFIRE;
        }
        if (selected) {
            flags |= FLAG_SELECTED;
        }
        return flags;
    }

    /**
     * Build the per-mount snapshot from the project-owned {@link WeaponMountView}
     * read model, keyed to the owning ship's already-resolved network {@code entityId}
     * (distinct from the view's within-tick repository {@code shipId()}) and the
     * {@code weaponContentId} the caller interned from {@link WeaponMountView#weaponId()}.
     */
    public static ShipWeaponMountSnapshot fromView(
            WeaponMountView view, long entityId, int weaponContentId) {
        Objects.requireNonNull(view, "view");
        return new ShipWeaponMountSnapshot(
                entityId,
                view.mountIndex(),
                weaponContentId,
                view.cooldownRemaining(),
                view.cooldown(),
                view.ammo(),
                view.maxAmmo(),
                view.chargeLevel(),
                view.firing(),
                view.usesAmmo(),
                view.disabled(),
                view.autofire(),
                view.selected());
    }

    /**
     * Reconstruct a mount snapshot from its wire fields, decoding the status
     * booleans from a {@link #statusFlags()} int. Unknown high bits are rejected so a
     * corrupt flag byte fails closed rather than silently dropping state.
     */
    public static ShipWeaponMountSnapshot fromWire(
            long entityId,
            int mountIndex,
            int weaponContentId,
            float cooldownRemaining,
            float cooldown,
            int ammo,
            int maxAmmo,
            float chargeLevel,
            int statusFlags) {
        if ((statusFlags & ~ALL_FLAGS) != 0) {
            throw new IllegalArgumentException("unknown ship weapon-mount status flag bits: " + statusFlags);
        }
        return new ShipWeaponMountSnapshot(
                entityId,
                mountIndex,
                weaponContentId,
                cooldownRemaining,
                cooldown,
                ammo,
                maxAmmo,
                chargeLevel,
                (statusFlags & FLAG_FIRING) != 0,
                (statusFlags & FLAG_USES_AMMO) != 0,
                (statusFlags & FLAG_DISABLED) != 0,
                (statusFlags & FLAG_AUTOFIRE) != 0,
                (statusFlags & FLAG_SELECTED) != 0);
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
