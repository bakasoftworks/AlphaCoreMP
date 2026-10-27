package cc.starsector.server.protocol;

import cc.starsector.server.adapter.CombatEntityView;
import java.util.Objects;

/**
 * One non-ship combat entity's authoritative per-frame state as it is replicated
 * to clients: a projectile, missile, or beam read off the live engine's per-frame
 * entity lists ({@link CombatEntityView}).
 *
 * <p>This is the wire-carried counterpart of {@link CombatEntityView}. The ship
 * tier ({@link AuthoritativeEntitySnapshot}) already crosses the socket; the
 * snapshot's critical-state tier ("active
 * projectiles, missiles, beams") needs these transient entities too. This first
 * crossing carries the minimum a client needs to place and attribute the entity:
 * a server network id, the entity kind, the owning side, a primary world position,
 * and an interned content id. Per-frame velocity and full beam-segment geometry
 * remain the documented follow-up the {@link CombatEntityView} contract names.
 *
 * <p>Like {@link AuthoritativeEntitySnapshot} it carries only scalar,
 * version-independent state and never an engine-internal class. {@code kind} is
 * encoded on the wire as its {@link Enum#ordinal() ordinal} (stable within the
 * product build) via {@link #kindWireCode()} / {@link #kindFromWireCode(int)}.
 */
public record NonShipEntitySnapshot(
        long networkId,
        CombatEntityView.Kind kind,
        int owner,
        float posX,
        float posY,
        int contentId,
        long sourceEntityId,
        int sourceMountIndex,
        int poseFacingU16,
        int poseFlags) {

    /** Reserved content id for an entity whose content key is unknown/blank. */
    public static final int NO_CONTENT = 0;
    /**
     * Sentinel source wire id for a beam (v12) or missile (v13)
     * whose emitter is unresolved. A resolved record carries the emitting SHIP's wire
     * entityId, or — for a vanilla DEM payload beam — the emitting
     * child MISSILE's v13a stable wire id; the
     * {@link CombatEntityView#MISSILE_NETWORK_ID_BASE} partition tells the two apart
     * with no discriminator bit, so a reader that means "ship" must range-check.
     */
    public static final long NO_SOURCE = 0L;
    /** Sentinel source mount index paired with {@link #NO_SOURCE}. */
    public static final int NO_SOURCE_MOUNT = -1;
    /**
     * Missile-pose flag (v16): the pose pair was read whole off the live
     * engine this frame — {@link #poseFacingU16} is meaningful. A record without
     * this bit carries the (0, 0) sentinel pose.
     */
    public static final int POSE_FLAG_VALID = 0x1;
    /**
     * Missile-pose flag (v16): the engine reports the missile FIZZLING
     * ({@code MissileAPI.isFizzling()}) — burned out and drifting toward its fade.
     */
    public static final int POSE_FLAG_FIZZLING = 0x2;
    /**
     * Missile-pose flag: the engine's controller reports the
     * missile ACCELERATING this frame ({@code MissileAPI.getEngineController()
     * .isAccelerating()} = the per-frame ACCELERATE command flag, cleared at the top of every
     * {@code processCommands} and set by the command).
     * Minted for MOTE specs (the Ziggurat's {@code mote}/
     * {@code mote_hf}) and every FLARE spec ({@code isFlare()});
     * every other missile record carries 0 here. WHY: vanilla lights
     * a mote's engine flame + contrail on exactly the frames its steering issues
     * ACCELERATE, a decision the client cannot
     * reproduce from pose + velocity (the flock's desired direction never crosses the
     * wire); the bit hands the client the engine's own decision at zero byte cost.
     */
    public static final int POSE_FLAG_ACCELERATING = 0x4;
    /**
     * Missile-pose flag: the engine reports the FIZZLING
     * missile a DUD — {@code !MissileAPI.isArmed()}. Vanilla rolls the dud coin ONCE at
     * birth ({@code armedWhileFizzling =
     * (float) Math.random() > spec.getDudProbabilityOnFlameout()}) and {@code isArmed()}
     * folds it in only while fizzling; a dud fizzling missile bounces off hulls
     * and shields dealing nothing. The client's local body rolls its OWN coin, so it can
     * never mirror the server's twin without this verdict — the client mirrors the bit
     * onto its local body. Minted ONLY on FIZZLING frames (the one mint rule =
     * {@link #dudFlag}). Bit sense 1 = dud, so an older server's 0 reads as the
     * armed/today path on a new client; a spare bit above the old mask was MASKED (never
     * rejected) by every prior reader ({@code AuthoritativeWorldSnapshotCodec} decode
     * {@code & POSE_FLAGS_MASK}), so there is no crash window and no schema bump.
     */
    public static final int POSE_FLAG_DUD = 0x8;
    /**
     * The pose-flag bits this build understands. Spare bits are written 0 and
     * MASKED (never rejected) at decode, so a future bit needs no crash window.
     */
    public static final int POSE_FLAGS_MASK = POSE_FLAG_VALID | POSE_FLAG_FIZZLING | POSE_FLAG_ACCELERATING
            | POSE_FLAG_DUD;
    /**
     * The ONE home of the DUD mint rule: a
     * missile carries {@link #POSE_FLAG_DUD} only when it is FIZZLING and the engine
     * reports it unarmed ({@code !isArmed()} = the
     * arming clock AND the birth coin — the bit is NOT the coin alone).
     * A non-fizzling missile never mints it, not even while unarmed inside its armingTime
     * (the client's local body keeps its own arming clock); a FIZZLING one inside that
     * window IS minted and the bit CLEARS when the clock lands — re-minted every frame,
     * the client re-samples every fresh record (F1); fizzling+armed = 0.
     */
    public static int dudFlag(boolean fizzling, boolean armed) {
        return fizzling && !armed ? POSE_FLAG_DUD : 0;
    }
    /**
     * Missile-pose facing wire grid (v16): 360°/65536 per u16 step. The
     * quantization happens ONCE, at the {@code EngineCombatBinding} engine read
     * (the v11 never-re-derive law); every carrier hands the u16 through
     * verbatim — no float round-trip, no cross-codec divergence.
     */
    public static final float MISSILE_POSE_FACING_STEP_DEG = 360f / 65536f;

    public NonShipEntitySnapshot {
        if (networkId <= 0L) {
            throw new IllegalArgumentException("networkId must be > 0; got " + networkId);
        }
        Objects.requireNonNull(kind, "kind");
        if (contentId < 0) {
            throw new IllegalArgumentException("contentId must be >= 0; got " + contentId);
        }
        requireFinite("posX", posX);
        requireFinite("posY", posY);
        // Beams (v12) and missiles (v13: they join the source pair): the
        // source binding resolves whole-or-not-at-all — a projectile kind, or a
        // beam/missile the server could not bind, carries the sentinel pair
        // (NO_SOURCE, NO_SOURCE_MOUNT); a resolved beam/missile carries both halves.
        if (kind != CombatEntityView.Kind.BEAM && kind != CombatEntityView.Kind.MISSILE
                && sourceEntityId != NO_SOURCE) {
            throw new IllegalArgumentException(
                    "sourceEntityId is beam/missile-only; got " + sourceEntityId + " on kind " + kind);
        }
        if (sourceEntityId < 0L) {
            throw new IllegalArgumentException("sourceEntityId must be >= 0; got " + sourceEntityId);
        }
        // A resolved source WITH the sentinel mount (N, -1) is the
        // WEAPONLESS-SOURCED pair — a ship-system script spawn (MineStrikeStats'
        // spawnProjectile(ship, null, ...)) has a live source ship and no mount. It lets the
        // firer's own client link its local newborn (the own-twin idiom) instead of adopting a
        // second body; every other client routes mount -1 to the sourceless arm unchanged.
        if (sourceEntityId == NO_SOURCE ? sourceMountIndex != NO_SOURCE_MOUNT
                : sourceMountIndex < NO_SOURCE_MOUNT) {
            throw new IllegalArgumentException("sourceMountIndex " + sourceMountIndex
                    + " inconsistent with sourceEntityId " + sourceEntityId);
        }
        // The pose pair is MISSILE-only, and (0, 0) is the one legal
        // sentinel — flags 0 with a nonzero facing has no meaning on any kind.
        if (poseFacingU16 < 0 || poseFacingU16 > 0xFFFF) {
            throw new IllegalArgumentException("poseFacingU16 out of range: " + poseFacingU16);
        }
        if (poseFlags < 0 || poseFlags > 0xFF) {
            throw new IllegalArgumentException("poseFlags out of range: " + poseFlags);
        }
        if (kind != CombatEntityView.Kind.MISSILE && (poseFlags != 0 || poseFacingU16 != 0)) {
            throw new IllegalArgumentException(
                    "pose is missile-only; got flags " + poseFlags + " facing " + poseFacingU16
                            + " on kind " + kind);
        }
        if (poseFlags == 0 && poseFacingU16 != 0) {
            throw new IllegalArgumentException(
                    "poseFacingU16 " + poseFacingU16 + " without pose flags");
        }
    }

    /**
     * Pose-less back-compat shape (every record before the v16 missile-pose
     * widening): the (0, 0) sentinel pose, byte- and value-identical to what the
     * frozen v1..v15 decode paths produce.
     */
    public NonShipEntitySnapshot(
            long networkId,
            CombatEntityView.Kind kind,
            int owner,
            float posX,
            float posY,
            int contentId,
            long sourceEntityId,
            int sourceMountIndex) {
        this(networkId, kind, owner, posX, posY, contentId, sourceEntityId, sourceMountIndex, 0, 0);
    }

    /**
     * Source-less back-compat shape (every record before the v12 beam-source
     * tier, and every non-beam kind since): the sentinel pair, byte- and
     * value-identical to what the frozen v1..v11 decode paths produce.
     */
    public NonShipEntitySnapshot(
            long networkId,
            CombatEntityView.Kind kind,
            int owner,
            float posX,
            float posY,
            int contentId) {
        this(networkId, kind, owner, posX, posY, contentId, NO_SOURCE, NO_SOURCE_MOUNT);
    }

    /** Stable wire encoding of {@link #kind()} (the enum ordinal). */
    public int kindWireCode() {
        return kind.ordinal();
    }

    /** Decode a {@link CombatEntityView.Kind} from its {@link #kindWireCode()}. */
    public static CombatEntityView.Kind kindFromWireCode(int wireCode) {
        CombatEntityView.Kind[] kinds = CombatEntityView.Kind.values();
        if (wireCode < 0 || wireCode >= kinds.length) {
            throw new IllegalArgumentException("non-ship entity kind wire code out of range: " + wireCode);
        }
        return kinds[wireCode];
    }

    /**
     * Build the per-frame snapshot from the project-owned {@link CombatEntityView}
     * read model. {@code networkId} is the server-assigned id for this entity: for
     * PROJECTILE/BEAM (and the authored projectile tier) a per-frame ordinal (the
     * source assigns 1..n across those kinds so the replicated list is ordered and
     * unique); for MISSILE (v13a) the server-LIFETIME stable id partitioned
     * at {@link CombatEntityView#MISSILE_NETWORK_ID_BASE} — the ranges never collide.
     * {@code contentId} is the caller-interned content key ({@link #NO_CONTENT} when
     * the view's key is blank).
     */
    public static NonShipEntitySnapshot fromView(CombatEntityView view, long networkId, int contentId) {
        return fromView(view, networkId, contentId, NO_SOURCE, NO_SOURCE_MOUNT);
    }

    /**
     * {@link #fromView(CombatEntityView, long, int)} with the source
     * binding: {@code sourceEntityId} is the EMITTER's wire id and
     * {@code sourceMountIndex} the mount ordinal ON THAT EMITTER. For a roster ship
     * the caller first translates the view's position-domain ship id through the
     * frame's position→network map, and the ordinal is the
     * {@code ServerWeaponMountState} one; for a DEM payload beam the
     * view already hands over a wire id at or above
     * {@link CombatEntityView#MISSILE_NETWORK_ID_BASE} — the emitting child missile's
     * v13a stable id, passed through verbatim — and the ordinal is taken on that
     * child's fx DRONE. Either way the pair stays whole-or-not-at-all (the compact
     * constructor): both halves real, or {@link #NO_SOURCE}/{@link #NO_SOURCE_MOUNT}.
     */
    public static NonShipEntitySnapshot fromView(
            CombatEntityView view, long networkId, int contentId,
            long sourceEntityId, int sourceMountIndex) {
        Objects.requireNonNull(view, "view");
        // The pose pair rides the view's accessors verbatim (minted
        // once at the EngineCombatBinding read; 0/0 for every non-missile kind and
        // every pose-less read — the defaults), so call sites need no churn.
        return new NonShipEntitySnapshot(
                networkId,
                view.kind(),
                view.owner(),
                view.posX(),
                view.posY(),
                contentId,
                sourceEntityId,
                sourceMountIndex,
                view.missileFacingU16(),
                view.missilePoseFlags());
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
