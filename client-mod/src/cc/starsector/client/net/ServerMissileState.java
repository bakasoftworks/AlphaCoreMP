package cc.starsector.client.net;

/**
 * One server-authoritative MISSILE record decoded off the AWSS non-ship tier at
 * schema v12+: the missile's stable per-lifetime
 * networkId (the v13a high-base allocator, ids &ge; 1e9 — NOT a per-frame
 * ordinal), owning side, world position, the interned content id of the missile
 * spec, the v13 source binding: the launching ship's wire {@code entityId}
 * plus the {@link ServerWeaponMountState}-ordinal mount index, and the v16
 * missile pose: the server-minted facing quantized to a u16
 * step ({@link #FACING_STEP_DEG} — minted ONCE at the engine read, never
 * re-derived) plus the {@link #poseFlags()} byte. An unresolved source carries
 * the sentinels {@link #NO_SOURCE}/{@link #NO_SOURCE_MOUNT} (the server
 * resolves whole-or-not-at-all); a v12-stamped frame's missile records carry no
 * pair on the wire and are retained with the sentinels. A sub-v16 frame's
 * records (and a v16 record the server could not read whole) carry the (0, 0)
 * sentinel pose — {@link #hasPose()} is false and consumers keep their own
 * fallback law. Pure Java, no Starsector-API dependency; mirrors the server's
 * {@code NonShipEntitySnapshot} missile shape.
 *
 * <p>The slice-3 (e) missile pose lane consumes these: stable networkId →
 * per-lifetime twin identity (pose adoption + absence-despawn), source pair →
 * presented-puppet enrollment, pose → presented facing truth + the
 * {@link #fizzling()} death-arc relay.
 */
public record ServerMissileState(
        long networkId,
        int owner,
        float posX,
        float posY,
        int contentId,
        long sourceEntityId,
        int sourceMountIndex,
        int facingU16,
        int poseFlags) {

    /** Sentinel {@link #sourceEntityId()} for a missile whose source is unresolved. */
    public static final long NO_SOURCE = 0L;
    /** Sentinel {@link #sourceMountIndex()} paired with {@link #NO_SOURCE}. */
    public static final int NO_SOURCE_MOUNT = -1;
    /** Pose flag (schema v16): the pose pair was read whole off the live engine —
     *  {@link #facingU16()} is meaningful. Mirrors {@code NonShipEntitySnapshot.POSE_FLAG_VALID}. */
    public static final int POSE_FLAG_VALID = 0x1;
    /** Pose flag (schema v16): the server engine reports the missile FIZZLING
     *  ({@code MissileAPI.isFizzling()}) — burned out, drifting toward its fade. */
    public static final int POSE_FLAG_FIZZLING = 0x2;
    /** Pose flag: the server engine's controller reports the missile
     *  ACCELERATING this frame ({@code getEngineController().isAccelerating()} — the per-frame
     *  ACCELERATE command flag). Minted for MOTE specs only; vanilla lights a mote's engine
     *  flame + contrail on exactly these frames (MoteAIScript), a decision
     *  the flock's desired direction — never on the wire — makes unreproducible client-side.
     *  Mirrors {@code NonShipEntitySnapshot.POSE_FLAG_ACCELERATING}. */
    public static final int POSE_FLAG_ACCELERATING = 0x4;
    /** Pose flag: the server engine's fizzling missile reads UNARMED —
     *  minted only on FIZZLING frames, re-minted EVERY frame, = the server's
     *  {@code !MissileAPI.isArmed()} (the arming clock AND
     *  vanilla's once-per-missile {@code dudProbabilityOnFlameout} roll — a body flamed
     *  out inside its {@code armingTime} is DUD until the clock lands, then the bit CLEARS).
     * Bit sense 1 = dud, so an absent bit (an older server, a sentinel pose) reads
     *  ARMED = the legacy path. Mirrors {@code NonShipEntitySnapshot.POSE_FLAG_DUD}.
     * */
    public static final int POSE_FLAG_DUD = 0x8;
    /** The pose-flag bits this build understands; spare wire bits are MASKED at decode
     *  (never rejected), so a future bit needs no crash window. */
    public static final int POSE_FLAGS_MASK = POSE_FLAG_VALID | POSE_FLAG_FIZZLING | POSE_FLAG_ACCELERATING
            | POSE_FLAG_DUD;
    /** Facing wire grid: 360°/65536 per u16 step (quantized once server-side — the
     *  never-re-derive law; mirrors {@code NonShipEntitySnapshot.MISSILE_POSE_FACING_STEP_DEG}). */
    public static final float FACING_STEP_DEG = 360f / 65536f;

    /** The pre-v16 shape: source pair only, sentinel (0, 0) pose. */
    public ServerMissileState(long networkId, int owner, float posX, float posY, int contentId,
            long sourceEntityId, int sourceMountIndex) {
        this(networkId, owner, posX, posY, contentId, sourceEntityId, sourceMountIndex, 0, 0);
    }

    /** True when the server bound this missile to a launching ship + mount. */
    public boolean hasSource() {
        return sourceEntityId != NO_SOURCE;
    }

    /** True when the record carries a server-minted pose ({@link #facingU16()} meaningful). */
    public boolean hasPose() {
        return (poseFlags & POSE_FLAG_VALID) != 0;
    }

    /** True when the server engine reports this missile fizzling (burnout → drift → fade). */
    public boolean fizzling() {
        return (poseFlags & POSE_FLAG_FIZZLING) != 0;
    }

    /** True when the server engine reports this fizzling missile UNARMED ({@code !isArmed()}: the
     *  dud coin, OR still inside its armingTime) — a dud that bounces harmlessly instead of detonating.
     *  Re-minted every frame: it can CLEAR when the arming clock lands, so a relay
     *  re-samples fresh records. Only meaningful under {@link #fizzling()}; false
     *  (= armed, the legacy path) whenever the server minted no DUD bit. */
    public boolean dud() {
        return (poseFlags & POSE_FLAG_DUD) != 0;
    }

    /** True when the server engine's controller reported ACCELERATE this frame (
     *  mote records only — every other record reads false). Only meaningful under {@link #hasPose()}. */
    public boolean accelerating() {
        return (poseFlags & POSE_FLAG_ACCELERATING) != 0;
    }

    /** The server facing in degrees [0, 360) — only meaningful under {@link #hasPose()}. */
    public float facingDeg() {
        return facingU16 * FACING_STEP_DEG;
    }
}
