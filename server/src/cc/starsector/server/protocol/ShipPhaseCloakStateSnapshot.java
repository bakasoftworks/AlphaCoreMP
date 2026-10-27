package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipPhaseCloakView;
import java.util.Objects;

/**
 * One ship's PHASE-CLOAK readiness state as it is replicated to clients: the
 * phase-cloak tier (schema v10), read off the bridge's
 * {@link ShipPhaseCloakView} (the separate {@code ShipAPI.getPhaseCloak()} slot —
 * NOT the primary {@code getSystem()} the {@link ShipSystemStateSnapshot} tier
 * carries; a Grendel's primary system is its ammofeed, its cloak lives here).
 *
 * <p>Why this is carried at all: the cloak is a state machine whose activation
 * EDGES are the only thing a steady-state lane replicates, so a client that joins
 * (or first sights an entity) mid-cloak has no edge to replay and idles the
 * replica's cloak — a hittable-looking phased foe. Carrying the phase + progress
 * scalars lets the client seed the real local cloak via the engine's own faithful
 * {@code forceState(state, progress)} path once per entity, then hand back to
 * edge-driven sync. The per-frame collision truth rides
 * separately as the flux tier's {@link ShipFluxSnapshot#FLAG_PHASED} status bit.
 *
 * <p>Wire layout = the v8 SYSTEM_STATE 24-byte record VERBATIM (D-M2-1: entityId
 * u64 / stateCode i32 / effectLevel f32bits / cooldownRemaining f32bits /
 * ammo i32) — code reuse both halves; no cloak spec id travels (the client
 * re-derives the cloak object from its local hull). {@code stateCode} is the
 * PROJECT-OWNED wire mapping of the engine's {@code ShipSystemAPI$SystemState}
 * phase — never an engine ordinal (charter R1). {@code ammo} carries
 * {@link #NO_AMMO}: the cloak is a toggle system and the view deliberately reads
 * no charge trio; the slot exists only to keep the v8 layout verbatim.
 *
 * <p>NO fragment-marker tier bit exists for this tier (D-M2-3: TIER_MASK_BITS
 * stays 0x3F — widening it forks the frozen v9 golden and stale-kit marker
 * decodes). A fragmented ship's cloak record therefore CANNOT be asserted by any
 * fragment frame: the chunker omits it from fragments and the client merger
 * HOLDS-LAST until the next whole-entity frame carries the ship. At the demo
 * rosters a cloak ship never fragments (a lone Grendel ≈ 740 B « the 1164 B cap).
 * Keyed by {@code entityId} to the same stable network identity as the ship's
 * {@link AuthoritativeEntitySnapshot}; like the other snapshot tiers it carries
 * only scalar, version-independent state and never an engine-internal class.
 */
public record ShipPhaseCloakStateSnapshot(
        long entityId, int stateCode, float effectLevel, float cooldownRemaining, int ammo) {

    /** PROJECT-OWNED wire code: cloak idle ({@code SystemState.IDLE}). */
    public static final int STATE_IDLE = 0;
    /** PROJECT-OWNED wire code: charge-up ramp ({@code SystemState.IN}). */
    public static final int STATE_CHARGEUP = 1;
    /** PROJECT-OWNED wire code: effect active ({@code SystemState.ACTIVE}). */
    public static final int STATE_ACTIVE = 2;
    /** PROJECT-OWNED wire code: charge-down ramp ({@code SystemState.OUT}). */
    public static final int STATE_CHARGEDOWN = 3;
    /** PROJECT-OWNED wire code: cooling down ({@code SystemState.COOLDOWN}). */
    public static final int STATE_COOLDOWN = 4;
    /** Wire value for the chargeless cloak (layout parity with the v8 tier: ammo −1 = no-ammo). */
    public static final int NO_AMMO = -1;

    public ShipPhaseCloakStateSnapshot {
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
     * Build the per-ship phase-cloak snapshot from the project-owned
     * {@link ShipPhaseCloakView} read model, keyed to the ship's already-resolved
     * network {@code entityId} (distinct from the view's within-tick repository
     * {@code shipId()}). The view reads no charge trio (the cloak is a toggle
     * system), so {@code ammo} always carries {@link #NO_AMMO}.
     */
    public static ShipPhaseCloakStateSnapshot fromView(ShipPhaseCloakView view, long entityId) {
        Objects.requireNonNull(view, "view");
        return new ShipPhaseCloakStateSnapshot(
                entityId,
                stateCodeFromViewState(view.state()),
                view.effectLevel(),
                view.cooldownRemaining(),
                NO_AMMO);
    }

    /**
     * Map the engine's {@code ShipSystemAPI$SystemState} enum NAME (as carried by
     * {@link ShipPhaseCloakView#state()}) to the project-owned wire code. Fail-closed:
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
            default -> throw new IllegalArgumentException("unknown phase-cloak state: " + state);
        };
    }

    /**
     * Reconstruct from decoded wire fields, fail-closed: a corrupt frame whose state
     * code is outside the project-owned 0..4 range, whose ammo is below the −1
     * no-ammo sentinel, or whose entityId is non-positive is rejected by the
     * canonical constructor rather than carried.
     */
    public static ShipPhaseCloakStateSnapshot fromWire(
            long entityId, int stateCode, float effectLevel, float cooldownRemaining, int ammo) {
        return new ShipPhaseCloakStateSnapshot(entityId, stateCode, effectLevel, cooldownRemaining, ammo);
    }
}
