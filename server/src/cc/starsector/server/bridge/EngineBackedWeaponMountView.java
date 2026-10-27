package cc.starsector.server.bridge;

import cc.starsector.server.adapter.WeaponMountView;
import java.util.Objects;

/**
 * Immutable {@link WeaponMountView} snapshot read from the live engine bridge:
 * one {@code ShipAPI.getAllWeapons()} element's
 * {@code WeaponAPI.getId()} + cooldown/ammo/firing/disabled/charge state.
 */
public final class EngineBackedWeaponMountView implements WeaponMountView {

    private final long shipId;
    private final int mountIndex;
    private final String weaponId;
    private final float cooldownRemaining;
    private final float cooldown;
    private final boolean firing;
    private final boolean usesAmmo;
    private final int ammo;
    private final int maxAmmo;
    private final boolean disabled;
    private final float chargeLevel;
    private final float currAngle;
    private final float projectileSpeed;
    private final float shipVelX;
    private final float shipVelY;
    private final float freshSpawnBearingDeg;
    private final float freshSpawnMuzzleX;
    private final float freshSpawnMuzzleY;
    private final float freshSpawnFacingDeg;
    private final boolean freshSpawnMissile;
    private final boolean autofire;
    private final boolean selected;

    /**
     * Start a builder with every optional engine read at its fail-soft absent default —
     * {@code currAngleDeg} {@link Float#NaN} (unbound/failed {@code getCurrAngle}); the GPEE v2
     * flight-bearing composition trio {@code projectileSpeed}/{@code shipVelX}/{@code shipVelY}
     * {@link Float#NaN} per scalar; the GPEE v3 fresh-spawn TRUE bearing
     * + v8 muzzle pair + birth facing {@link Float#NaN} and missile flag {@code false} (no
     * fresh spawn matched this mount); {@code autofire}
     * {@code false} (unbound/groupless reads carry as "not autofiring");
     * {@code selected} {@code false} (unbound/knob-off reads carry as
     * "not selected") — exactly the defaults the pre-extraction telescoping constructor
     * chain threaded.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Field-named builder (the
     * {@code ServerEntityPose} idiom on the bridge side): the one construction
     * path. The single mint ({@code EngineCombatBinding.readWeaponMounts}) routes through this
     * instead of a positional 19-arg constructor, so the NEXT mount-view field costs one field
     * + one builder line — a positional full-args call would instead silently rebind to a
     * shorter back-compat constructor and DROP the new read (the ctor-fan-out hazard that
     * taxed the v8 17→19-arg widen).
     */
    public static final class Builder {
        private long shipId;
        private int mountIndex;
        private String weaponId;
        private float cooldownRemaining;
        private float cooldown;
        private boolean firing;
        private boolean usesAmmo;
        private int ammo;
        private int maxAmmo;
        private boolean disabled;
        private float chargeLevel;
        private float currAngleDeg = Float.NaN;
        private float projectileSpeed = Float.NaN;
        private float shipVelX = Float.NaN;
        private float shipVelY = Float.NaN;
        private float freshSpawnBearingDeg = Float.NaN;
        private float freshSpawnMuzzleX = Float.NaN;
        private float freshSpawnMuzzleY = Float.NaN;
        private float freshSpawnFacingDeg = Float.NaN;
        private boolean freshSpawnMissile;
        private boolean autofire;
        private boolean selected;

        public Builder shipId(long shipId) {
            this.shipId = shipId;
            return this;
        }

        public Builder mountIndex(int mountIndex) {
            this.mountIndex = mountIndex;
            return this;
        }

        public Builder weaponId(String weaponId) {
            this.weaponId = weaponId;
            return this;
        }

        public Builder cooldownRemaining(float cooldownRemaining) {
            this.cooldownRemaining = cooldownRemaining;
            return this;
        }

        public Builder cooldown(float cooldown) {
            this.cooldown = cooldown;
            return this;
        }

        public Builder firing(boolean firing) {
            this.firing = firing;
            return this;
        }

        public Builder usesAmmo(boolean usesAmmo) {
            this.usesAmmo = usesAmmo;
            return this;
        }

        public Builder ammo(int ammo) {
            this.ammo = ammo;
            return this;
        }

        public Builder maxAmmo(int maxAmmo) {
            this.maxAmmo = maxAmmo;
            return this;
        }

        public Builder disabled(boolean disabled) {
            this.disabled = disabled;
            return this;
        }

        public Builder chargeLevel(float chargeLevel) {
            this.chargeLevel = chargeLevel;
            return this;
        }

        public Builder currAngleDeg(float currAngleDeg) {
            this.currAngleDeg = currAngleDeg;
            return this;
        }

        public Builder projectileSpeed(float projectileSpeed) {
            this.projectileSpeed = projectileSpeed;
            return this;
        }

        public Builder shipVelX(float shipVelX) {
            this.shipVelX = shipVelX;
            return this;
        }

        public Builder shipVelY(float shipVelY) {
            this.shipVelY = shipVelY;
            return this;
        }

        public Builder freshSpawnBearingDeg(float freshSpawnBearingDeg) {
            this.freshSpawnBearingDeg = freshSpawnBearingDeg;
            return this;
        }

        public Builder freshSpawnMuzzleX(float freshSpawnMuzzleX) {
            this.freshSpawnMuzzleX = freshSpawnMuzzleX;
            return this;
        }

        public Builder freshSpawnMuzzleY(float freshSpawnMuzzleY) {
            this.freshSpawnMuzzleY = freshSpawnMuzzleY;
            return this;
        }

        /** The fresh spawn's birth facing + whether it
         *  came from the MISSILE list — the poll path's {@code LaunchFacingLaw} inputs. */
        public Builder freshSpawnFacingDeg(float freshSpawnFacingDeg) {
            this.freshSpawnFacingDeg = freshSpawnFacingDeg;
            return this;
        }

        public Builder freshSpawnMissile(boolean freshSpawnMissile) {
            this.freshSpawnMissile = freshSpawnMissile;
            return this;
        }

        public Builder autofire(boolean autofire) {
            this.autofire = autofire;
            return this;
        }

        public Builder selected(boolean selected) {
            this.selected = selected;
            return this;
        }

        /** Validates exactly as the canonical constructor always did (it IS that call). */
        public EngineBackedWeaponMountView build() {
            return new EngineBackedWeaponMountView(
                    shipId, mountIndex, weaponId, cooldownRemaining, cooldown, firing,
                    usesAmmo, ammo, maxAmmo, disabled, chargeLevel, currAngleDeg,
                    projectileSpeed, shipVelX, shipVelY, freshSpawnBearingDeg,
                    freshSpawnMuzzleX, freshSpawnMuzzleY, freshSpawnFacingDeg, freshSpawnMissile,
                    autofire, selected);
        }
    }

    private EngineBackedWeaponMountView(
            long shipId,
            int mountIndex,
            String weaponId,
            float cooldownRemaining,
            float cooldown,
            boolean firing,
            boolean usesAmmo,
            int ammo,
            int maxAmmo,
            boolean disabled,
            float chargeLevel,
            float currAngleDeg,
            float projectileSpeed,
            float shipVelX,
            float shipVelY,
            float freshSpawnBearingDeg,
            float freshSpawnMuzzleX,
            float freshSpawnMuzzleY,
            float freshSpawnFacingDeg,
            boolean freshSpawnMissile,
            boolean autofire,
            boolean selected) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (mountIndex < 0) {
            throw new IllegalArgumentException("mountIndex must be >= 0; got " + mountIndex);
        }
        this.weaponId = Objects.requireNonNull(weaponId, "weaponId");
        requireFinite("cooldownRemaining", cooldownRemaining);
        requireFinite("cooldown", cooldown);
        requireFinite("chargeLevel", chargeLevel);
        this.shipId = shipId;
        this.mountIndex = mountIndex;
        this.cooldownRemaining = cooldownRemaining;
        this.cooldown = cooldown;
        this.firing = firing;
        this.usesAmmo = usesAmmo;
        this.ammo = ammo;
        this.maxAmmo = maxAmmo;
        this.disabled = disabled;
        this.chargeLevel = chargeLevel;
        if (Float.isInfinite(currAngleDeg)) {
            throw new IllegalArgumentException("currAngleDeg must be finite or NaN; got " + currAngleDeg);
        }
        this.currAngle = currAngleDeg;
        requireFiniteOrNaN("projectileSpeed", projectileSpeed);
        requireFiniteOrNaN("shipVelX", shipVelX);
        requireFiniteOrNaN("shipVelY", shipVelY);
        requireFiniteOrNaN("freshSpawnBearingDeg", freshSpawnBearingDeg);
        requireFiniteOrNaN("freshSpawnMuzzleX", freshSpawnMuzzleX);
        requireFiniteOrNaN("freshSpawnMuzzleY", freshSpawnMuzzleY);
        requireFiniteOrNaN("freshSpawnFacingDeg", freshSpawnFacingDeg);
        // GPEE v8 pair law (shot-fidelity-budget): one captured point or one honest
        // absence — a half-finite pair can only be a read bug, rejected at construction.
        if (Float.isNaN(freshSpawnMuzzleX) != Float.isNaN(freshSpawnMuzzleY)) {
            throw new IllegalArgumentException(
                    "freshSpawnMuzzleX/Y must be both finite or both NaN; got "
                            + freshSpawnMuzzleX + "/" + freshSpawnMuzzleY);
        }
        this.projectileSpeed = projectileSpeed;
        this.shipVelX = shipVelX;
        this.shipVelY = shipVelY;
        this.freshSpawnBearingDeg = freshSpawnBearingDeg;
        this.freshSpawnMuzzleX = freshSpawnMuzzleX;
        this.freshSpawnMuzzleY = freshSpawnMuzzleY;
        this.freshSpawnFacingDeg = freshSpawnFacingDeg;
        this.freshSpawnMissile = freshSpawnMissile;
        this.autofire = autofire;
        this.selected = selected;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public int mountIndex() {
        return mountIndex;
    }

    @Override
    public String weaponId() {
        return weaponId;
    }

    @Override
    public float cooldownRemaining() {
        return cooldownRemaining;
    }

    @Override
    public float cooldown() {
        return cooldown;
    }

    @Override
    public boolean firing() {
        return firing;
    }

    @Override
    public boolean usesAmmo() {
        return usesAmmo;
    }

    @Override
    public int ammo() {
        return ammo;
    }

    @Override
    public int maxAmmo() {
        return maxAmmo;
    }

    @Override
    public boolean disabled() {
        return disabled;
    }

    @Override
    public float chargeLevel() {
        return chargeLevel;
    }

    @Override
    public float currAngle() {
        return currAngle;
    }

    @Override
    public float projectileSpeed() {
        return projectileSpeed;
    }

    @Override
    public float shipVelX() {
        return shipVelX;
    }

    @Override
    public float shipVelY() {
        return shipVelY;
    }

    @Override
    public float freshSpawnBearingDeg() {
        return freshSpawnBearingDeg;
    }

    @Override
    public float freshSpawnMuzzleX() {
        return freshSpawnMuzzleX;
    }

    @Override
    public float freshSpawnMuzzleY() {
        return freshSpawnMuzzleY;
    }

    @Override
    public float freshSpawnFacingDeg() {
        return freshSpawnFacingDeg;
    }

    @Override
    public boolean freshSpawnMissile() {
        return freshSpawnMissile;
    }

    @Override
    public boolean autofire() {
        return autofire;
    }

    @Override
    public boolean selected() {
        return selected;
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }

    private static void requireFiniteOrNaN(String label, float value) {
        if (Float.isInfinite(value)) {
            throw new IllegalArgumentException(label + " must be finite or NaN; got " + value);
        }
    }
}
