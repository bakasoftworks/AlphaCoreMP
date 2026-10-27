package cc.starsector.server.adapter;

/**
 * Project-owned read model for one of a ship's weapon mounts at a simulation step.
 *
 *
 * <p>The engine ship, its {@code getAllWeapons()} list, the internal weapon impl
 * ({@code com.fs.starfarer.combat.entities.ship.A.*}), and any other engine-internal classes
 * stay inside the bridge. This view carries only scalar/boolean per-mount facts
 * that can be mixed into authoritative snapshot state — the per-weapon
 * combat-readiness tier that sits one level below the ship-level
 * {@link ShipFluxView} / {@link ShipDurabilityView}.
 *
 * <p>Flux and hull tell a client whether a ship is broadly combat-effective;
 * per-mount state tells it which guns can actually fire <em>this</em> instant: a
 * mount on cooldown, out of ammo, or disabled cannot contribute damage even on a
 * healthy ship. This is the first per-weapon (sub-ship) status read; a ship can
 * own several mounts, so the bridge returns a flat list and each view tags its
 * owning {@link #shipId()} plus the per-ship {@link #mountIndex()} ordinal.
 */
public interface WeaponMountView {

    /**
     * Stable identity of the owning ship in the same within-tick repository order
     * as {@link ShipMotionView}/{@link ShipFluxView}. Several mounts on the same
     * ship share this id; pair with {@link #mountIndex()} for a unique mount key.
     */
    long shipId();

    /** Per-ship weapon ordinal (0-based, in {@code ShipAPI.getAllWeapons()} order). */
    int mountIndex();

    /** Weapon spec id of the mounted weapon ({@code WeaponAPI.getId()}); never null. */
    String weaponId();

    /** Seconds of cooldown left before this mount may fire again (0 when ready). */
    float cooldownRemaining();

    /** This mount's full cooldown duration in seconds. */
    float cooldown();

    /** True when the mount is firing this frame. */
    boolean firing();

    /** True when the mount consumes a finite ammo pool (vs energy/unlimited). */
    boolean usesAmmo();

    /** Current ammo count ({@link #usesAmmo()} false → engine-reported, often a sentinel). */
    int ammo();

    /** Maximum ammo capacity. */
    int maxAmmo();

    /** True when the mount is disabled (destroyed/knocked out) and cannot fire. */
    boolean disabled();

    /** Charge level (0..1) for charge-up weapons; 0 for instant-fire mounts. */
    float chargeLevel();

    /**
     * True when this mount's owning weapon GROUP is autofiring on the authoritative
     * hull ({@code WeaponGroupAPI.isAutofiring()} of the group that contains this
     * weapon). Per-mount duplication of a per-group fact so the state
     * rides the existing mount tier; a client re-derives per-group state from its
     * own mount→group mapping. Optional, additive read like {@link #currAngle()}:
     * {@code false} when the producing bridge does not bind it or the read fails
     * (fail-soft — an unknown state is carried as "not autofiring", never a throw).
     */
    default boolean autofire() {
        return false;
    }

    /**
     * True when this mount's owning weapon GROUP is the ship's SELECTED group on the
     * authoritative hull ({@code ShipAPI.getSelectedGroupAPI()} identity vs the group
     * that contains this weapon — the selection mirror).
     * Per-mount duplication of a per-group fact like {@link #autofire()}; a client
     * re-derives the selected group ordinal from its own mount→group mapping.
     * Optional, additive read: {@code false} when the producing bridge does not bind
     * it, the read fails, or the {@code cc.server.mountSelectedFlag} rollback knob is
     * off (fail-soft — an unknown state is carried as "not selected", never a throw).
     */
    default boolean selected() {
        return false;
    }

    /**
     * The mount's current world-facing in degrees ({@code WeaponAPI.getCurrAngle()})
     * — the {@code aimFacingDeg} payload of a {@code WEAPON_DISCHARGE} edge event
     * for every non-missile discharge and the
     * missile FALLBACK ({@code LaunchFacingLaw}; a missile carries its fresh
     * spawn's birth facing, {@link #freshSpawnFacingDeg()}). Optional, additive read:
     * {@link Float#NaN} when the producing bridge does not bind it (fail-soft —
     * absence is carried honestly, never substituted with a fake angle).
     */
    default float currAngle() {
        return Float.NaN;
    }

    /**
     * The stat-modified projectile muzzle speed of the mounted weapon
     * ({@code WeaponAPI.getProjectileSpeed()}) — one input of the GPEE v2
     * {@code flightBearingDeg} spawn composition. Optional,
     * additive read like {@link #currAngle()}: {@link Float#NaN} when unbound/failed.
     */
    default float projectileSpeed() {
        return Float.NaN;
    }

    /**
     * The owning ship's velocity X at the same post-step read instant
     * ({@code ShipAPI.getVelocity()}) — the shooter-velocity term of the GPEE v2
     * {@code flightBearingDeg} composition. Per-mount duplication of a ship-level
     * fact so the discharge detector composes from ONE view. Optional, additive:
     * {@link Float#NaN} when unbound/failed.
     */
    default float shipVelX() {
        return Float.NaN;
    }

    /** {@link #shipVelX()}'s Y component. */
    default float shipVelY() {
        return Float.NaN;
    }

    /**
     * GPEE v3: the TRUE bearing
     * (degrees, [0,360)) the projectile this mount just spawned actually flies —
     * read off the live just-spawned projectile entity's own velocity, so it
     * carries the engine's per-shot spread jitter that the v2
     * {@code shooterVel + projectileSpeed·û(mountRay)} composition can never see.
     * Optional, additive: {@link Float#NaN} when no fresh spawn matched this mount
     * at the read (non-discharge ticks, beams, unbound handles, degenerate
     * velocity) — the discharge detector then falls back to the v2 composition,
     * never a faked value.
     */
    default float freshSpawnBearingDeg() {
        return Float.NaN;
    }

    /**
     * The just-spawned entity's own {@code getFacing()}
     * (degrees, as read) — for a MISSILE its launch angle ({@code currAngle} + the barrel's
     * angle offset + the server's spread draw, 0 for hammer/locust), the poll path's input to
     * {@code LaunchFacingLaw}. Optional, additive like {@link #freshSpawnBearingDeg()}:
     * {@link Float#NaN} when no fresh spawn matched this mount, the match was not a MISSILE-list
     * entity (the read is gated), or the facing read failed.
     */
    default float freshSpawnFacingDeg() {
        return Float.NaN;
    }

    /** Whether the matched fresh spawn came from the engine's MISSILE list (only a
     *  missile's facing reaches the wire aim); {@code false} when no fresh spawn matched. */
    default boolean freshSpawnMissile() {
        return false;
    }

    /**
     * GPEE v8 (spawn truth): world X of the
     * just-spawned projectile matched to this mount at the read — the engine's true
     * birth position ({@code CombatEntityAPI.getLocation()} on the same fresh entity
     * {@link #freshSpawnBearingDeg()} reads — finite even when that bearing is NaN for a
     * zero-velocity MISSILE — an empty launch-speed column). Pair-atomic with
     * {@link #freshSpawnMuzzleY()}: both finite or both {@link Float#NaN} (no fresh
     * spawn matched / unbound handle — honest absence, never a substituted point).
     */
    default float freshSpawnMuzzleX() {
        return Float.NaN;
    }

    /** {@link #freshSpawnMuzzleX()}'s Y component (same pair-atomic NaN law). */
    default float freshSpawnMuzzleY() {
        return Float.NaN;
    }
}
