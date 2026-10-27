package cc.starsector.client.net;

/**
 * The interpolated render pose of one server entity at a chosen render time
 * (the velocity triple was added for the foe-interp
 * lane).
 *
 * <p>Unlike {@link ServerEntityPose} (a raw authoritative snapshot field-for-field),
 * this carries only the kinematics a render-side consumer applies: position, facing,
 * and the lerped velocity triple. The retired puppet lane reads position+facing only
 * (its zero-velocity scheme — the "velocity tug-of-war" fix — ignores the
 * velocity fields). The live foe-interp lane instead substitutes this pose as the
 * reconcile <em>target</em>, and that law hard-sets engine velocity from the target
 * every frame — so the velocity fields must be populated on EVERY construction path,
 * including the clamp/hold paths ({@link #of}): a zero there would hard-stop the foe's
 * rendered motion on every buffer-end hold frame (the zero-velocity hold trap).
 *
 * <p>Pure Java, no Starsector-API dependency, so {@link SnapshotInterpolator} and this
 * type are exercised by the game-free interpolation verifier.
 */
public final class InterpolatedPose {

    private final long entityId;
    private final float locX;
    private final float locY;
    private final float facing;
    private final float velX;
    private final float velY;
    private final float angularVelocity;

    public InterpolatedPose(long entityId, float locX, float locY, float facing,
            float velX, float velY, float angularVelocity) {
        this.entityId = entityId;
        this.locX = locX;
        this.locY = locY;
        this.facing = facing;
        this.velX = velX;
        this.velY = velY;
        this.angularVelocity = angularVelocity;
    }

    /**
     * Snapshot of a raw server pose with no interpolation (single-frame / clamp /
     * fence-hold case) — carries the pose's raw velocity triple, never zeros
     * (the zero-velocity hold trap).
     */
    static InterpolatedPose of(ServerEntityPose pose) {
        return new InterpolatedPose(pose.entityId(), pose.locX(), pose.locY(), pose.facing(),
                pose.velX(), pose.velY(), pose.angularVelocity());
    }

    public long entityId() {
        return entityId;
    }

    public float locX() {
        return locX;
    }

    public float locY() {
        return locY;
    }

    /** Facing, in degrees (Starsector convention). */
    public float facing() {
        return facing;
    }

    public float velX() {
        return velX;
    }

    public float velY() {
        return velY;
    }

    /** Angular velocity, degrees/second (Starsector convention). */
    public float angularVelocity() {
        return angularVelocity;
    }

    @Override
    public String toString() {
        return "InterpolatedPose{id=" + entityId
                + ",loc=(" + locX + "," + locY + ")"
                + ",facing=" + facing
                + ",vel=(" + velX + "," + velY + ")"
                + ",angVel=" + angularVelocity + "}";
    }
}
