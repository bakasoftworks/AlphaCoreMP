package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipSystemView;
import java.util.Objects;

/**
 * One ship's primary ship-SYSTEM readiness state as it is replicated to clients:
 * the system-state tier (schema v8), read off the bridge's
 * {@link ShipSystemView} (public {@code getSystem()} surface).
 *
 * <p>Why this is carried at all: a ship's primary system is frequently
 * combat-decisive (a fortress shield blocks damage entirely, a burn drive or
 * maneuvering jets change what a ship can be hit by) and its charge/cooldown
 * cycle is server-side truth a predictive client cannot converge on implicitly
 * mid-window — the steady-state lane replicates only activation EDGES, so a
 * client that joins (or re-acquires an entity) mid-activation has no edge to
 * replay and idles the replica's system. Carrying the phase + progress scalars
 * lets the client seed the real local system via the engine's own faithful
 * {@code forceState(state, progress)}/{@code setAmmo} path once per entity, then
 * hand back to edge-driven {@code useSystem}.
 *
 * <p>{@code stateCode} is the PROJECT-OWNED wire mapping of the engine's
 * {@code ShipSystemAPI$SystemState} phase — never an engine ordinal
 * (charter R1: wire values must not move if the engine reorders its enum).
 * {@code ammo} carries {@link #NO_AMMO} for a system that does not use charges
 * (the engine reports {@code Integer.MAX_VALUE} there — its own
 * {@code usesAmmo()} sentinel — which must never travel as a real count).
 * Toggle-system {@code on} is consciously EXCLUDED (the demo
 * roster has none; the escape hatch is a later tier, never a field edit). Keyed
 * by {@code entityId} to the same stable network identity as the ship's
 * {@link AuthoritativeEntitySnapshot}; like the other snapshot tiers it carries
 * only scalar, version-independent state and never an engine-internal class.
 */
public record ShipSystemStateSnapshot(
        long entityId, int stateCode, float effectLevel, float cooldownRemaining, int ammo) {

    /** PROJECT-OWNED wire code: system idle ({@code SystemState.IDLE}). */
    public static final int STATE_IDLE = 0;
    /** PROJECT-OWNED wire code: charge-up ramp ({@code SystemState.IN}). */
    public static final int STATE_CHARGEUP = 1;
    /** PROJECT-OWNED wire code: effect active ({@code SystemState.ACTIVE}). */
    public static final int STATE_ACTIVE = 2;
    /** PROJECT-OWNED wire code: charge-down ramp ({@code SystemState.OUT}). */
    public static final int STATE_CHARGEDOWN = 3;
    /** PROJECT-OWNED wire code: cooling down ({@code SystemState.COOLDOWN}). */
    public static final int STATE_COOLDOWN = 4;
    /** Wire value for a system that does not use charges (ammo −1 = no-ammo system). */
    public static final int NO_AMMO = -1;

    public ShipSystemStateSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if (stateCode < STATE_IDLE || stateCode > STATE_COOLDOWN) {
            throw new IllegalArgumentException(
                    "stateCode must be one of the project-owned codes 0..4; got " + stateCode);
        }
        if (ammo < NO_AMMO) {
            throw new IllegalArgumentException(
                    "ammo must be >= -1 (-1 = no-ammo system); got " + ammo);
        }
    }

    /**
     * Build the per-ship system-state snapshot from the project-owned
     * {@link ShipSystemView} read model, keyed to the ship's already-resolved network
     * {@code entityId} (distinct from the view's within-tick repository
     * {@code shipId()}). Normalizes the engine's {@code Integer.MAX_VALUE}
     * no-ammo sentinel to {@link #NO_AMMO} so the sentinel never travels the wire.
     */
    public static ShipSystemStateSnapshot fromView(ShipSystemView view, long entityId) {
        Objects.requireNonNull(view, "view");
        int ammo = view.ammo() == Integer.MAX_VALUE ? NO_AMMO : view.ammo();
        return new ShipSystemStateSnapshot(
                entityId,
                stateCodeFromViewState(view.state()),
                view.effectLevel(),
                view.cooldownRemaining(),
                ammo);
    }

    /**
     * Map the engine's {@code ShipSystemAPI$SystemState} enum NAME (as carried by
     * {@link ShipSystemView#state()}) to the project-owned wire code. Fail-closed:
     * an unknown name means the read model changed under us and must be looked at,
     * never silently carried.
     */
    public static int stateCodeFromViewState(String state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case "IDLE" -> STATE_IDLE;
            case "IN" -> STATE_CHARGEUP;
            case "ACTIVE" -> STATE_ACTIVE;
            case "OUT" -> STATE_CHARGEDOWN;
            case "COOLDOWN" -> STATE_COOLDOWN;
            default -> throw new IllegalArgumentException("unknown ship-system state: " + state);
        };
    }

    /**
     * Reconstruct from decoded wire fields, fail-closed: a corrupt frame whose state
     * code is outside the project-owned 0..4 range, whose ammo is below the −1
     * no-ammo sentinel, or whose entityId is non-positive is rejected by the
     * canonical constructor rather than carried.
     */
    public static ShipSystemStateSnapshot fromWire(
            long entityId, int stateCode, float effectLevel, float cooldownRemaining, int ammo) {
        return new ShipSystemStateSnapshot(entityId, stateCode, effectLevel, cooldownRemaining, ammo);
    }
}
