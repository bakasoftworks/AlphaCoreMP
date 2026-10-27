package cc.starsector.client;

/**
 * Prox-charge ENGINE-DOOR det expectation.
 *
 * <p>WHY: when {@code MineBombEndFxLane.presentProxDoor} arms the engine's own fuse on a puppet,
 * the det the engine deals 1-2 render frames later is SERVER-DEALT already — the kind-6 END rode
 * the wire with its pose, the server's DamagingExplosion moved every victim's hull on the serve,
 * and the snapshot carries that drop. On a REMOTE replica the apply reached
 * {@code HullLedger.recordApplied} as an ordinary local prediction: a replay entry the wire never
 * retires (the drop landed BEFORE the local apply), held for the full 2.0 s expiry = the same
 * "darker, then heals up" rebound already fixed for dead sources.
 * The door's explosion carries the
 * FIRING SHIP as its report-seam source, and that same live ship also lands genuine local-first
 * hits, so source identity alone can never classify: the expectation is keyed by source identity
 * + the det pose + the arm tick, and a victim inside the explosion radius of that pose inside
 * {@link #WINDOW_TICKS} of the arm is the door's det. Nothing is consumed on a match (one
 * explosion touches several bodies); entries age out. Single-writer (render thread), bounded.
 */
final class DoorDetExpectation {

    /** Live expectations kept (a serve dets a handful of charges per second at most). */
    static final int RING = 8;

    /** The fuse dets 1-2 render frames after the arm (0.2 s at 60 t/s covers 10 fps). */
    static final long WINDOW_TICKS = 12L;

    /** Explosion radius when the missile spec is unreadable (a prox charge is ~250-300 su). */
    static final float FALLBACK_RADIUS = 300f;

    private final Object[] source = new Object[RING];
    private final float[] detX = new float[RING];
    private final float[] detY = new float[RING];
    private final float[] radius = new float[RING];
    private final long[] armTick = new long[RING];
    private int next;

    long armed;   // predict_door_det_armed
    long matched; // predict_door_det_acked (an apply classified server-dealt by an expectation)

    /** Arm an expectation at the door (source = {@code MissileAPI.getSource()}, the firing ship). */
    void arm(Object sourceShip, float x, float y, float explosionRadius, long simTick) {
        if (sourceShip == null) {
            return;
        }
        int i = next;
        next = (next + 1) % RING;
        source[i] = sourceShip;
        detX[i] = x;
        detY[i] = y;
        radius[i] = explosionRadius > 0f && Float.isFinite(explosionRadius)
                ? explosionRadius : FALLBACK_RADIUS;
        armTick[i] = simTick;
        armed++;
    }

    /**
     * True iff a live expectation names this source inside the window and the victim's body sits
     * inside the det's explosion reach. Never throws (pure arithmetic on stored primitives).
     */
    boolean matches(Object sourceShip, float victimX, float victimY, float victimRadius, long simTick) {
        if (sourceShip == null) {
            return false;
        }
        for (int i = 0; i < RING; i++) {
            if (source[i] == sourceShip
                    && matchCore(armTick[i], simTick, victimX - detX[i], victimY - detY[i],
                            radius[i], victimRadius)) {
                matched++;
                return true;
            }
        }
        return false;
    }

    /** Drop every expectation (match reset / own respawn — stale ship objects never match). */
    void clear() {
        for (int i = 0; i < RING; i++) {
            source[i] = null;
        }
    }

    /** The pure core (contract rows): inside the tick window AND inside radius + victimRadius. */
    static boolean matchCore(long armTick, long nowTick, float dx, float dy, float explosionRadius,
            float victimRadius) {
        long age = nowTick - armTick;
        if (age < 0L || age > WINDOW_TICKS) {
            return false;
        }
        float reach = explosionRadius + (victimRadius > 0f ? victimRadius : 0f);
        return dx * dx + dy * dy <= reach * reach;
    }
}
