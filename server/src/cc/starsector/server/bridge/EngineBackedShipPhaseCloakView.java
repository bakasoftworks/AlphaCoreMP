package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipPhaseCloakView;

import java.util.Objects;

/**
 * Immutable {@link ShipPhaseCloakView} snapshot read from the live engine bridge.
 * The concrete ship object, its {@code getPhaseCloak()}
 * result, and the {@code SystemState} enum stay inside {@link EngineCombatBinding};
 * this value carries only project-owned scalars.
 */
public final class EngineBackedShipPhaseCloakView implements ShipPhaseCloakView {

    private final long shipId;
    private final String cloakId;
    private final String state;
    private final boolean active;
    private final boolean on;
    private final float cooldownRemaining;
    private final float cooldown;
    private final float effectLevel;
    private final boolean phased;
    private final int ammo;

    public EngineBackedShipPhaseCloakView(
            long shipId,
            String cloakId,
            String state,
            boolean active,
            boolean on,
            float cooldownRemaining,
            float cooldown,
            float effectLevel,
            boolean phased,
            int ammo) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        this.cloakId = Objects.requireNonNull(cloakId, "cloakId");
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
        this.phased = phased;
        this.ammo = ammo;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public String cloakId() {
        return cloakId;
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
    public boolean phased() {
        return phased;
    }

    @Override
    public int ammo() {
        return ammo;
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
