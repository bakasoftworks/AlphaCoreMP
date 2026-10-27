package cc.starsector.client.net;

/**
 * The decoded payload of one {@code HKPS} server-authoritative corpse-pose keyframe:
 * the client-side analogue of the server's {@code HulkPoseFrameCodec.PoseKeyframe}.
 * The server's detector fans one per corpse-family piece at a low cadence (plus on family
 * creation and on edge ticks) so the client ADOPTS the server's corpse kinematics — hulk drift
 * is deterministic ballistic coast, so one exact adoption holds between keyframes (aimed shots
 * at a diverged local corpse stop missing the authoritative one).
 *
 * <p>{@code rootEntityId} + {@code deathTick} identify the corpse family exactly like the
 * {@code HKFR} edge (nearest-death-tick bind; pose records bind AND latch like edges — an
 * unsplit corpse's family latches off its first keyframe, the main case with no edges at all);
 * {@code sizeRank} names the piece by bbox area, largest = 0 (the REMOVE idiom);
 * {@code pieceCount} is the family size this keyframe set describes (the client drops the
 * record on a ranked-size mismatch); the six floats are the exact pose to adopt: location,
 * facing (deg), velocity, angular velocity (deg/s).
 *
 * <p>Like its {@code HKFR} sibling {@link ClientHulkFractureRecord}, this is a small
 * client-owned mirror — the mod deliberately does not bundle the server's record (its codec
 * statics drag {@code NetworkMessageRegistry} into the game JVM). The game-free cross-check
 * ({@code CcClientSeatNameVerifier}'s HKPS legs) round-trips real server
 * {@code HulkPoseFrameCodec} bytes through {@link ClientHulkPoseRecordReader} and asserts this
 * value matches, keeping the two mirrors in sync.
 */
public final class ClientHulkPoseRecord {

    private final long simTick;
    private final long rootEntityId;
    private final long deathTick;
    private final int sizeRank;
    private final int pieceCount;
    private final float locX;
    private final float locY;
    private final float facing;
    private final float velX;
    private final float velY;
    private final float angVel;

    /**
     * @throws IllegalArgumentException on any field outside the server record's exact guards
     *     (negative simTick, rootEntityId &lt; 1, a deathTick outside {@code 0..simTick} — a
     *     family's keyframes never precede its death — a pieceCount outside 1..255, a sizeRank
     *     outside {@code 0..pieceCount-1}, or any non-finite pose component). The reader
     *     translates this to an {@link java.io.IOException} so a bad frame fails closed like
     *     every other wire decode.
     */
    public ClientHulkPoseRecord(long simTick, long rootEntityId, long deathTick, int sizeRank,
            int pieceCount, float locX, float locY, float facing, float velX, float velY,
            float angVel) {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        if (rootEntityId < 1L) {
            throw new IllegalArgumentException(
                    "rootEntityId must be >= 1 (a wire entity id); got " + rootEntityId);
        }
        if (deathTick < 0L) {
            throw new IllegalArgumentException("deathTick must be >= 0; got " + deathTick);
        }
        if (deathTick > simTick) {
            throw new IllegalArgumentException("deathTick must be <= simTick (a family's"
                    + " keyframes never precede its death); got deathTick=" + deathTick
                    + " simTick=" + simTick);
        }
        if (pieceCount < 1 || pieceCount > 0xFF) {
            throw new IllegalArgumentException(
                    "pieceCount must be 1..255 (a keyframe describes a live family); got "
                            + pieceCount);
        }
        if (sizeRank < 0 || sizeRank >= pieceCount) {
            throw new IllegalArgumentException("sizeRank must be 0.." + (pieceCount - 1)
                    + " (< pieceCount); got " + sizeRank);
        }
        if (!Float.isFinite(locX) || !Float.isFinite(locY) || !Float.isFinite(facing)
                || !Float.isFinite(velX) || !Float.isFinite(velY) || !Float.isFinite(angVel)) {
            throw new IllegalArgumentException("pose components must all be finite; got locX="
                    + locX + " locY=" + locY + " facing=" + facing + " velX=" + velX
                    + " velY=" + velY + " angVel=" + angVel);
        }
        this.simTick = simTick;
        this.rootEntityId = rootEntityId;
        this.deathTick = deathTick;
        this.sizeRank = sizeRank;
        this.pieceCount = pieceCount;
        this.locX = locX;
        this.locY = locY;
        this.facing = facing;
        this.velX = velX;
        this.velY = velY;
        this.angVel = angVel;
    }

    /** The server sim tick the keyframe was emitted on. */
    public long simTick() {
        return simTick;
    }

    /** The corpse family's wire id (the originally-alive ship's entity id). */
    public long rootEntityId() {
        return rootEntityId;
    }

    /** The family's wire identity: the server detector's firstSightTick (~the EDSP death tick;
     * the HKFR law). Clients bind by same rootId + nearest {@code |mintTick − deathTick|}. */
    public long deathTick() {
        return deathTick;
    }

    /** This piece's bbox-area size rank (largest = 0 — the REMOVE idiom). */
    public int sizeRank() {
        return sizeRank;
    }

    /** The family piece count this keyframe set describes (the client's ranked-size assert). */
    public int pieceCount() {
        return pieceCount;
    }

    /** The exact pose to adopt: world-x. */
    public float locX() {
        return locX;
    }

    /** The exact pose to adopt: world-y. */
    public float locY() {
        return locY;
    }

    /** The exact pose to adopt: facing (deg). */
    public float facing() {
        return facing;
    }

    /** The exact pose to adopt: velocity-x (u/s). */
    public float velX() {
        return velX;
    }

    /** The exact pose to adopt: velocity-y (u/s). */
    public float velY() {
        return velY;
    }

    /** The exact pose to adopt: angular velocity (deg/s). */
    public float angVel() {
        return angVel;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientHulkPoseRecord other)) {
            return false;
        }
        return simTick == other.simTick && rootEntityId == other.rootEntityId
                && deathTick == other.deathTick && sizeRank == other.sizeRank
                && pieceCount == other.pieceCount
                && Float.floatToIntBits(locX) == Float.floatToIntBits(other.locX)
                && Float.floatToIntBits(locY) == Float.floatToIntBits(other.locY)
                && Float.floatToIntBits(facing) == Float.floatToIntBits(other.facing)
                && Float.floatToIntBits(velX) == Float.floatToIntBits(other.velX)
                && Float.floatToIntBits(velY) == Float.floatToIntBits(other.velY)
                && Float.floatToIntBits(angVel) == Float.floatToIntBits(other.angVel);
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(simTick);
        h = 31 * h + Long.hashCode(rootEntityId);
        h = 31 * h + Long.hashCode(deathTick);
        h = 31 * h + sizeRank;
        h = 31 * h + pieceCount;
        h = 31 * h + Float.floatToIntBits(locX);
        h = 31 * h + Float.floatToIntBits(locY);
        h = 31 * h + Float.floatToIntBits(facing);
        h = 31 * h + Float.floatToIntBits(velX);
        h = 31 * h + Float.floatToIntBits(velY);
        return 31 * h + Float.floatToIntBits(angVel);
    }

    @Override
    public String toString() {
        return "ClientHulkPoseRecord{tick=" + simTick + ",rootId=" + rootEntityId
                + ",deathTick=" + deathTick + ",rank=" + sizeRank + ",pieces=" + pieceCount
                + ",loc=(" + locX + "," + locY + "),facing=" + facing
                + ",vel=(" + velX + "," + velY + "),angVel=" + angVel + "}";
    }
}
