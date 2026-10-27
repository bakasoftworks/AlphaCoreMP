package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipSystemView;
import java.util.Objects;

/**
 * Immutable {@link ShipSystemView} snapshot read from the live engine bridge:
 * one ship's {@code ShipAPI.getSystem()} primary system, read through
 * the public-API {@code ShipSystemAPI} descriptors ({@code getId()} /
 * {@code getState()} / {@code isActive()} / {@code isOn()} /
 * {@code getCooldownRemaining()} / {@code getCooldown()} / {@code getEffectLevel()}
 * / {@code getAmmo()} / {@code getMaxAmmo()} / {@code isOutOfAmmo()}).
 */
public final class EngineBackedShipSystemView implements ShipSystemView {

    private final long shipId;
    private final String systemId;
    private final String state;
    private final boolean active;
    private final boolean on;
    private final float cooldownRemaining;
    private final float cooldown;
    private final float effectLevel;
    private final int ammo;
    private final int maxAmmo;
    private final boolean outOfAmmo;
    private final float moteTargetX;
    private final float moteTargetY;
    private final long moteTargetLockShipId;

    public EngineBackedShipSystemView(
            long shipId,
            String systemId,
            String state,
            boolean active,
            boolean on,
            float cooldownRemaining,
            float cooldown,
            float effectLevel,
            int ammo,
            int maxAmmo,
            boolean outOfAmmo) {
        this(shipId, systemId, state, active, on, cooldownRemaining, cooldown, effectLevel,
                ammo, maxAmmo, outOfAmmo, Float.NaN, Float.NaN, 0L);
    }

    /**
     * The mote-target form — {@code moteTargetX/Y} finite only for a
     * {@code mote_control} press whose attractor target the bridge read this frame;
     * {@code moteTargetLockShipId} = position shipId of the lock or 0 (empty space).
     */
    public EngineBackedShipSystemView(
            long shipId,
            String systemId,
            String state,
            boolean active,
            boolean on,
            float cooldownRemaining,
            float cooldown,
            float effectLevel,
            int ammo,
            int maxAmmo,
            boolean outOfAmmo,
            float moteTargetX,
            float moteTargetY,
            long moteTargetLockShipId) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        this.systemId = Objects.requireNonNull(systemId, "systemId");
        this.state = Objects.requireNonNull(state, "state");
        requireFinite("cooldownRemaining", cooldownRemaining);
        requireFinite("cooldown", cooldown);
        requireFinite("effectLevel", effectLevel);
        this.shipId = shipId;
        this.active = active;
        this.on = on;
        this.cooldownRemaining = cooldownRemaining;
        this.cooldown = cooldown;
        this.effectLevel = effectLevel;
        this.ammo = ammo;
        this.maxAmmo = maxAmmo;
        this.outOfAmmo = outOfAmmo;
        // Pair law: both finite or both NaN (a mixed pair can only be a corrupt read).
        boolean targetFinite = Float.isFinite(moteTargetX) && Float.isFinite(moteTargetY);
        boolean targetAbsent = Float.isNaN(moteTargetX) && Float.isNaN(moteTargetY);
        if (!targetFinite && !targetAbsent) {
            throw new IllegalArgumentException("moteTargetX/Y must be both finite or both NaN; got "
                    + moteTargetX + "/" + moteTargetY);
        }
        if (moteTargetLockShipId < 0L) {
            throw new IllegalArgumentException("moteTargetLockShipId must be >= 0; got " + moteTargetLockShipId);
        }
        this.moteTargetX = targetFinite ? moteTargetX : Float.NaN;
        this.moteTargetY = targetFinite ? moteTargetY : Float.NaN;
        this.moteTargetLockShipId = targetFinite ? moteTargetLockShipId : 0L;
    }

    @Override
    public float moteTargetX() {
        return moteTargetX;
    }

    @Override
    public float moteTargetY() {
        return moteTargetY;
    }

    @Override
    public long moteTargetLockShipId() {
        return moteTargetLockShipId;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public String systemId() {
        return systemId;
    }

    @Override
    public String state() {
        return state;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public boolean on() {
        return on;
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
    public float effectLevel() {
        return effectLevel;
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
    public boolean outOfAmmo() {
        return outOfAmmo;
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
