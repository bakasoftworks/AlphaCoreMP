package cc.starsector.server.adapter;

/**
 * Project-owned read model for a replicated <em>non-ship</em> combat entity: a
 * projectile, missile, or beam read off the live combat engine's per-frame entity
 * lists ({@code CombatEngineAPI.getProjectiles()},
 * {@code getMissiles()}, {@code getBeams()}).
 *
 * <p>This is the first non-ship entity boundary in the project. The ship views
 * ({@link ShipMotionView}, {@link ShipDurabilityView}, {@link ShipIdentityView})
 * cover the deployed roster; the combat snapshot's critical-state tier
 * ("active projectiles, missiles, beams")
 * also needs these transient entities, which spawn and die mid-combat and are not
 * part of the roster. This first cut carries the minimum a client needs to place
 * and attribute an entity: a server network id, the entity kind, the owning side,
 * a primary world position, and the content/weapon id.
 *
 * <p>"Primary world position" is the entity's gameplay-relevant point and is
 * well-defined for every kind with no overloading: the live {@code getLocation()}
 * for a projectile/missile, and the impact ("to") endpoint for a beam. Per-frame
 * velocity for point entities and full beam-segment geometry (the "from" endpoint)
 * are a documented follow-up increment, not part of this first crossing.
 */
public interface CombatEntityView {

    /** Which non-ship combat entity kind this view describes. */
    enum Kind { PROJECTILE, MISSILE, BEAM }

    /**
     * MISSILE network ids are server-lifetime STABLE ids allocated at or
     * above this base, partitioned away from the 1..n per-frame ordinals every other kind
     * carries — the two ranges can never collide.
     */
    long MISSILE_NETWORK_ID_BASE = 1_000_000_000L;

    /**
     * Server-owned network id for this entity. For PROJECTILE/BEAM (and the authored
     * projectile tier) this is a per-frame ordinal (1..n, repository order, unique across
     * kinds within the frame). For MISSILE (since v13a) it is a per-LIFETIME stable id
     * ≥ {@link #MISSILE_NETWORK_ID_BASE}: the same engine missile keeps the same id every
     * frame it lives, so a client can track a missile across frames without kinematic
     * relinking.
     */
    long networkId();

    /** Which non-ship entity kind this is. Never null. */
    Kind kind();

    /** Owning side as the engine reports it (e.g. {@code 0}/{@code 1}; {@code -1} neutral/unowned). */
    int owner();

    /** Primary world position X: projectile/missile location, or beam impact endpoint. Always finite. */
    float posX();

    /** Primary world position Y: projectile/missile location, or beam impact endpoint. Always finite. */
    float posY();

    /**
     * Version-independent content key: the projectile spec id for
     * projectiles/missiles, or the weapon spec id for beams. Never {@code null};
     * may be blank when the engine exposes no id for the entity.
     */
    String contentKey();

    /**
     * Source binding, EMITTER half (beams; missiles since v13b; DEM beams).
     * Two id spaces, held apart by the same
     * {@link #MISSILE_NETWORK_ID_BASE} partition the network ids use: BELOW the base
     * this is the emitting ship as its POSITION shipId (1..n, the same roster
     * enumeration the ship/mount tiers walk) and the snapshot source translates it to
     * the wire entityId at fold time; AT OR ABOVE the base it is ALREADY A WIRE ID —
     * the v13a stable missile id of the DEM child whose cosmetic fx drone emits this
     * beam — and it crosses the fold verbatim, because the
     * position→network map holds ships only and would miss it back to the sentinel.
     * {@code 0} when unresolved. Beam or missile only — PROJECTILE returns {@code 0};
     * only a BEAM can carry a partition-range (missile) value.
     */
    default long sourcePositionShipId() {
        return 0L;
    }

    /**
     * Source binding, mount half (beams; missiles since v13b; DEM beams):
     * the identity-index of the firing weapon in the
     * {@code getAllWeapons()} list of the EMITTING PLATFORM that
     * {@link #sourcePositionShipId()} names — for a roster ship the SAME ordinal
     * contract the per-mount weapon tier ({@code ServerWeaponMountState}) carries;
     * for a DEM payload beam the ordinal on the CHILD'S FX DRONE, the engine object
     * that actually owns the beam's weapon — never a mount on the named child missile
     * itself, nor on its parent ship. {@code -1} when unresolved. Resolves
     * whole-or-not-at-all with {@link #sourcePositionShipId()}: two real halves, or
     * the {@code (0, -1)} pair.
     */
    default int sourceMountIndex() {
        return -1;
    }

    /**
     * Missile pose, facing half (v16): the missile's world facing quantized
     * onto the 360°/65536 u16 grid — minted ONCE at the engine read
     * ({@code NonShipEntitySnapshot#MISSILE_POSE_FACING_STEP_DEG}; the v11
     * never-re-derive law), carried verbatim from here to the wire. MISSILE only;
     * {@code 0} for every other kind and for a pose-less read (gate on
     * {@link #missilePoseFlags()}, never on the facing value).
     */
    default int missileFacingU16() {
        return 0;
    }

    /**
     * Missile pose, status half (v16):
     * {@code NonShipEntitySnapshot#POSE_FLAG_VALID} when the pose pair was read whole
     * off the live engine this frame, plus {@code POSE_FLAG_FIZZLING} when the engine
     * reports the missile burned out ({@code MissileAPI.isFizzling()}), plus (MOTE specs;
     * + FLARE specs) {@code POSE_FLAG_ACCELERATING} when the engine controller
     * reports the ACCELERATE command this frame (the mote's engine-flame truth). Resolves
     * whole-or-not-at-all with {@link #missileFacingU16()}: {@code 0} means no pose
     * (the sentinel pair). MISSILE only.
     */
    default int missilePoseFlags() {
        return 0;
    }
}
