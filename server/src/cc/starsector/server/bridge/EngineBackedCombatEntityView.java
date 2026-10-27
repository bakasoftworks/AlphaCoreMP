package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatEntityView;
import java.util.Objects;

/**
 * Immutable {@link CombatEntityView} read from the live engine bridge:
 * a projectile/missile read via {@code CombatEntityAPI.getLocation()} +
 * {@code getOwner()} + {@code DamagingProjectileAPI.getProjectileSpecId()}, or a
 * beam read via {@code BeamAPI.getTo()} + its source ship owner.
 */
public final class EngineBackedCombatEntityView implements CombatEntityView {

    private final long networkId;
    private final Kind kind;
    private final int owner;
    private final float posX;
    private final float posY;
    private final String contentKey;
    private final long sourcePositionShipId;
    private final int sourceMountIndex;
    private final int missileFacingU16;
    private final int missilePoseFlags;

    public EngineBackedCombatEntityView(
            long networkId, Kind kind, int owner, float posX, float posY, String contentKey) {
        this(networkId, kind, owner, posX, posY, contentKey, 0L, -1);
    }

    /**
     * Sourced form (beams and missiles): the EMITTER + the
     * emitting weapon's {@code getAllWeapons()} identity-index ON THAT EMITTER, or
     * the {@code (0, -1)} sentinel pair when unresolved. Two id spaces share the
     * first slot, split at {@link cc.starsector.server.adapter.CombatEntityView#MISSILE_NETWORK_ID_BASE}:
     * below it the firing ship's POSITION shipId (roster enumeration order,
     * translated to a wire id at fold); at or above it — BEAM records
     * only — the emitting DEM child missile's v13a stable wire id, already in wire
     * space, whose mount ordinal is taken on the child's fx DRONE. Validation is
     * kind-agnostic — a missile passes with its stable networkId + source pair
     * unchanged.
     */
    public EngineBackedCombatEntityView(
            long networkId, Kind kind, int owner, float posX, float posY, String contentKey,
            long sourcePositionShipId, int sourceMountIndex) {
        this(networkId, kind, owner, posX, posY, contentKey,
                sourcePositionShipId, sourceMountIndex, 0, 0);
    }

    /**
     * Posed form (missiles, schema v16): the sourced form plus the missile-pose
     * pair — facing pre-quantized onto the u16 grid at the engine read plus the
     * {@code NonShipEntitySnapshot#POSE_FLAG_*} bits — or {@code (0, 0)} when the
     * pose read failed (whole-or-not-at-all, the source-pair discipline).
     */
    public EngineBackedCombatEntityView(
            long networkId, Kind kind, int owner, float posX, float posY, String contentKey,
            long sourcePositionShipId, int sourceMountIndex,
            int missileFacingU16, int missilePoseFlags) {
        if (networkId < 0L) {
            throw new IllegalArgumentException("networkId must be >= 0; got " + networkId);
        }
        this.kind = Objects.requireNonNull(kind, "kind");
        if (!Float.isFinite(posX) || !Float.isFinite(posY)) {
            throw new IllegalArgumentException("posX/posY must be finite; got " + posX + "," + posY);
        }
        if (sourcePositionShipId < 0L) {
            throw new IllegalArgumentException(
                    "sourcePositionShipId must be >= 0; got " + sourcePositionShipId);
        }
        // (N, -1) is the WEAPONLESS-SOURCED pair (a ship-system script
        // spawn — the Doom's mines — has a live source and no mount), mirroring the
        // NonShipEntitySnapshot law an earlier fix relaxed. This ctor kept the old "resolved
        // source needs a mount" law, so the binding's missile read THREW on every live mine and
        // nulled the whole missile tier — caught by the EngineBridgeSmoke source-pair rows.
        if (sourcePositionShipId == 0L ? sourceMountIndex != -1 : sourceMountIndex < -1) {
            throw new IllegalArgumentException("sourceMountIndex " + sourceMountIndex
                    + " inconsistent with sourcePositionShipId " + sourcePositionShipId);
        }
        if (missileFacingU16 < 0 || missileFacingU16 > 0xFFFF) {
            throw new IllegalArgumentException(
                    "missileFacingU16 out of range: " + missileFacingU16);
        }
        if (missilePoseFlags < 0 || missilePoseFlags > 0xFF) {
            throw new IllegalArgumentException(
                    "missilePoseFlags out of range: " + missilePoseFlags);
        }
        if (missilePoseFlags == 0 && missileFacingU16 != 0) {
            throw new IllegalArgumentException(
                    "missileFacingU16 " + missileFacingU16 + " without pose flags");
        }
        this.networkId = networkId;
        this.owner = owner;
        this.posX = posX;
        this.posY = posY;
        this.contentKey = contentKey == null ? "" : contentKey;
        this.sourcePositionShipId = sourcePositionShipId;
        this.sourceMountIndex = sourceMountIndex;
        this.missileFacingU16 = missileFacingU16;
        this.missilePoseFlags = missilePoseFlags;
    }

    @Override
    public long networkId() {
        return networkId;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public int owner() {
        return owner;
    }

    @Override
    public float posX() {
        return posX;
    }

    @Override
    public float posY() {
        return posY;
    }

    @Override
    public String contentKey() {
        return contentKey;
    }

    @Override
    public long sourcePositionShipId() {
        return sourcePositionShipId;
    }

    @Override
    public int sourceMountIndex() {
        return sourceMountIndex;
    }

    @Override
    public int missileFacingU16() {
        return missileFacingU16;
    }

    @Override
    public int missilePoseFlags() {
        return missilePoseFlags;
    }
}
