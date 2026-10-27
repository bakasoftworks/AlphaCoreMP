package cc.starsector.client.net;

/**
 * One decoded {@code ASTP} asteroid row ({@code server.asteroid.pose} schema v1):
 * the server keeper's ({@code AsteroidFieldServeLane}) authoritative existence + pose + hp of one
 * asteroid at the frame's {@code simTick}. The hand mirror of the server's
 * {@code AsteroidPoseFrameCodec.AsteroidRow} (the client never loads the server codec -- its static
 * registry initializer drags {@code NetworkMessageRegistry} into the game JVM).
 *
 *
 * <p>Fail-closed at construction (the {@link ClientPilotAimFrame} shape; the reader wraps the
 * {@link IllegalArgumentException} as {@link java.io.IOException}): id
 * {@code >= 1}; kind in {1 ALIVE, 2 DESTROYED, 3 EXPIRED}; size {@code 0..3}; ring {@code == 0}
 * (ring asteroids are OUT of v1 -- the API cannot set {@code fromRing}); radius finite in
 * {@code [1, 200]}; hp finite in {@code [0, 1e5]} with ALIVE {@code => hp > 0}, else {@code hp == 0};
 * {@code |loc| <= 100000}; {@code |vel| <= 10000}; facing finite; {@code |angVel| <= 3600}.
 * Comparisons are written so a NaN fails every bound.
 */
public record ClientAsteroidRow(int asteroidId, int kind, int size, int ring, float radius, float hp,
        float locX, float locY, float facing, float velX, float velY, float angVel) {

    //
    /** ALIVE: an unknown id MINTS, a known id ADOPTS. */
    public static final int KIND_ALIVE = 1;
    /** DESTROYED: hp reached 0 by damage -- the client kills via the engine's own applyDamage. */
    public static final int KIND_DESTROYED = 2;
    /** EXPIRED: off-map / retarget -- the client removes quietly. */
    public static final int KIND_EXPIRED = 3;

    public static final float MAX_RADIUS = 200f;
    public static final float MIN_RADIUS = 1f;
    public static final float MAX_HP = 1e5f;
    public static final float MAX_LOC_ABS = 100000f;
    public static final float MAX_VEL_ABS = 10000f;
    public static final float MAX_ANG_VEL_ABS = 3600f;

    public ClientAsteroidRow {
        // The exact-set row law (both trees implement it).
        if (asteroidId < 1) {
            throw new IllegalArgumentException("asteroid id must be >= 1: " + asteroidId);
        }
        if (kind != KIND_ALIVE && kind != KIND_DESTROYED && kind != KIND_EXPIRED) {
            throw new IllegalArgumentException("asteroid kind not in {1,2,3}: " + kind);
        }
        if (size < 0 || size > 3) {
            throw new IllegalArgumentException("asteroid size not in 0..3: " + size);
        }
        if (ring != 0) {
            throw new IllegalArgumentException("asteroid ring must be 0 (v1): " + ring);
        }
        if (!(radius >= MIN_RADIUS && radius <= MAX_RADIUS)) {
            throw new IllegalArgumentException("asteroid radius out of [1,200]: " + radius);
        }
        if (!(hp >= 0f && hp <= MAX_HP)) {
            throw new IllegalArgumentException("asteroid hp out of [0,1e5]: " + hp);
        }
        if (kind == KIND_ALIVE ? !(hp > 0f) : hp != 0f) {
            throw new IllegalArgumentException("asteroid hp " + hp + " illegal for kind " + kind);
        }
        if (!(Math.abs(locX) <= MAX_LOC_ABS && Math.abs(locY) <= MAX_LOC_ABS)) {
            throw new IllegalArgumentException("asteroid loc implausible: " + locX + "," + locY);
        }
        if (!(Math.abs(velX) <= MAX_VEL_ABS && Math.abs(velY) <= MAX_VEL_ABS)) {
            throw new IllegalArgumentException("asteroid vel implausible: " + velX + "," + velY);
        }
        if (!Float.isFinite(facing)) {
            throw new IllegalArgumentException("asteroid facing not finite: " + facing);
        }
        if (!(Math.abs(angVel) <= MAX_ANG_VEL_ABS)) {
            throw new IllegalArgumentException("asteroid angVel implausible: " + angVel);
        }
    }
}
