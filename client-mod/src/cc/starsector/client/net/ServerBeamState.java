package cc.starsector.client.net;

/**
 * One server-authoritative BEAM record decoded off the AWSS non-ship tier at
 * schema v12+: the beam's networkId, owning side, primary world
 * position — the server writes the beam's IMPACT point ({@code getTo()}) —
 * the interned content id of the firing weapon spec, and the v12 source
 * binding: the EMITTER's wire {@code entityId} plus the mount ordinal ON THAT
 * EMITTER. Two id spaces share the field, split at {@link #MISSILE_SOURCE_BASE}
 * with no discriminator bit: below it the firing SHIP + its
 * {@link ServerWeaponMountState}-ordinal mount ({@link #hasShipSource()});
 * at or above it a vanilla DEM payload beam's owning child MISSILE by its v13a
 * stable wire id + the ordinal on that child's fx DRONE (
 * {@link #hasDemChildSource()}). An unresolved source carries the sentinels
 * {@link #NO_SOURCE}/{@link #NO_SOURCE_MOUNT} (the server resolves
 * whole-or-not-at-all). Pure Java, no Starsector-API dependency; mirrors the
 * server's {@code NonShipEntitySnapshot} beam shape.
 *
 * <p>The client beam-presentation lane consumes the SHIP-source records: source
 * entityId → replay-driven hull, mountIndex → local weapon, aim = bearing(local
 * muzzle → this record's to-point) — it must range-check first, or a DEM-child
 * source misses the hull map. DEM-child records go to the DEM presenter
 * instead. Projectile/missile non-ship records are decoded past and dropped
 * (no client consumer yet).
 */
public record ServerBeamState(
        long networkId,
        int owner,
        float toX,
        float toY,
        int contentId,
        long sourceEntityId,
        int sourceMountIndex) {

    /** Sentinel {@link #sourceEntityId()} for a beam whose source is unresolved. */
    public static final long NO_SOURCE = 0L;
    /** Sentinel {@link #sourceMountIndex()} paired with {@link #NO_SOURCE}. */
    public static final int NO_SOURCE_MOUNT = -1;
    /**
     * The v13a MISSILE id partition base (server
     * {@code CombatEntityView.MISSILE_NETWORK_ID_BASE}): the server mints missile wire
     * ids from here upward while ship entityIds are small ordinals, so a source id at or
     * above this names a MISSILE and never a ship — no discriminator bit is needed.
     */
    public static final long MISSILE_SOURCE_BASE = 1_000_000_000L;

    /** True when the server bound this beam to any source (ship OR DEM child missile). */
    public boolean hasSource() {
        return sourceEntityId != NO_SOURCE;
    }

    /**
     * True when the server bound this beam to a firing roster SHIP + mount — the v12
     * beam-aim lane's records.
     */
    public boolean hasShipSource() {
        return sourceEntityId != NO_SOURCE && sourceEntityId < MISSILE_SOURCE_BASE;
    }

    /**
     * True when the server attributed this beam to the DEM child MISSILE whose cosmetic
     * fx drone emits it: {@link #sourceEntityId()} is that child's v13a
     * stable missile id and {@link #sourceMountIndex()} the mount ordinal on the DRONE,
     * not on the child. The DEM presenter binds on the id directly instead of guessing
     * the nearest child.
     */
    public boolean hasDemChildSource() {
        return sourceEntityId >= MISSILE_SOURCE_BASE;
    }
}
