package cc.starsector.server.runtime;

import java.util.Random;

/**
 * PURE, game-free asteroid-field law for the server keeper ({@link AsteroidFieldServeLane}).
 * No reflection, no engine: the spawn line, the per-body draw and the sweep
 * box are computed here off the ROUND's seeded {@link Random}, so a round's field is replayable from
 * {@code cc.server.asteroidSeed}. The law bodies were written from the functional spec,
 * items B1-B4; its numbers are game facts (tuning values) given by that spec.
 *
 * <p>WHY our own keeper and not the engine's field: our serve mission never builds an engine asteroid
 * field (it never calls {@code addAsteroidField}), {@code spawnAsteroid} bodies carry fieldId 1000 and
 * are never swept by the engine, and the engine's own per-spawn draws are unseeded - so the server owns
 * seeding, placement, sweeping and re-targeting, and the wire ships the result.
 *
 * <ul>
 *   <li>Size table: sprite index i = {@code asteroid{i+1}.png} (23x20 / 38x37 / 53x53 / 88x83) =>
 *       {@code r0 = (w+h) * 0.25 * 0.87}, {@code hp0 = r0 * 50},
 *       mass = the engine's r0 bucket (pinned; first-serve measurement rides the keeper's
 *       {@code asteroid_law} line).</li>
 *   <li>Spawn line ({@link #spawnLine}): 200 u outside the upwind edge of the square
 *       {@code [-mapHalf, +mapHalf]^2}; the field width {@code 2 * mapHalf} is the map width.</li>
 *   <li>Per-body draw ({@link #draw}): a fixed generator-call order + the initial downwind pre-scatter.</li>
 * </ul>
 *
 * Re-worded with the law bodies rewritten from the functional spec.
 */
public final class AsteroidLaw {

    //
    public static final int SIZE_COUNT = 4;
    private static final float[] R0 = {9.3525f, 16.3125f, 23.055f, 37.1925f};
    private static final float[] MASS0 = {20f, 101f, 201f, 320f};
    /** Spawn-line offset outside the upwind edge, 200 u (a game fact; spec B3). */
    public static final float LINE_OFFSET = 200f;
    /** Sweep margin and pre-advance span padding, 300 u (a game fact; spec B3). */
    public static final float SWEEP_MARGIN = 300f;
    /** Body speed range, 20..70 u/s (a game fact; spec B4). */
    public static final float SPEED_MIN = 20f;
    public static final float SPEED_MAX = 70f;
    /** OUR pinned corner law: closed segments with a 1e-3 u endpoint epsilon. */
    public static final float ENDPOINT_EPS_U = 1e-3f;
    /** pi/180 rounded to float: {@link #unit} multiplies degrees by it in float (spec B2 precision law). */
    private static final float DEG_TO_RAD_F = (float) (Math.PI / 180.0);
    private static final float FAR = 100000f;

    private AsteroidLaw() {
    }

    public static float r0(int size) {
        return R0[checkSize(size)];
    }

    public static float hp0(int size) {
        return R0[checkSize(size)] * 50f;
    }

    public static float mass0(int size) {
        return MASS0[checkSize(size)];
    }

    private static int checkSize(int size) {
        if (size < 0 || size >= SIZE_COUNT) {
            throw new IllegalArgumentException("asteroid size out of range: " + size);
        }
        return size;
    }

    /**
     * The 1-px spawn rectangle (integer-valued: the corner and the length are truncated toward zero)
     * + the initial pre-advance span (distance from the 200-u-shifted entry crossing to the exit
     * crossing, + 300). A vertical line (beyond the left/right edge) has width 1, a horizontal one
     * height 1. A geometry with no upwind entry or no distinct exit never throws: it gets the
     * bottom-edge fallback.
     */
    public record SpawnLine(float x, float y, float width, float height, float preAdvanceSpan) {
    }

    /**
     * Wraps an angle into [0, 360), in float: the result is congruent to {@code deg} mod 360; a tiny
     * negative input whose {@code + 360} rounds up to 360 wraps to 0; -0.0 gives +0.0; NaN or an
     * infinite input gives NaN. Written from the functional spec, item B1.
     *
     */
    public static float normalizeDeg(float deg) {
        float r = deg % 360f;
        if (r < 0f) {
            r += 360f;
        }
        return r >= 360f ? 0f : r + 0f;
    }

    /**
     * A NEW {cos, sin} pair for an angle in degrees (0 = +X, counter-clockwise positive). The radians
     * are {@code deg * DEG_TO_RAD_F} multiplied in float; cos and sin are evaluated in double on that
     * float value and each is rounded to float. Written from the functional spec, item B2.
     *
     */
    public static float[] unit(float deg) {
        float rad = deg * DEG_TO_RAD_F;
        return new float[] {(float) Math.cos(rad), (float) Math.sin(rad)};
    }

    /**
     * The upwind spawn line for drift {@code driftDeg} on the square {@code [-mapHalf, +mapHalf]^2}
     * (field centred on the origin, width {@code 2 * mapHalf}). The field enters from the edge the
     * drift blows FROM: with sx = -1 for a drift strictly between 90 and 270 deg (else +1) and sy = -1
     * above 180 deg (else +1), the entry is the first of LEFT (sx = +1), RIGHT (sx = -1), TOP (sy = -1),
     * BOTTOM (sy = +1) that the drift line through the origin crosses ({@link #edgeCrossing}). The
     * line's direction here is the DOUBLE cos/sin of the normalized drift (not the float {@link #unit}):
     * its float pair differs in magnitude by 1 ulp at 225 deg and ~4 ulps at 315 deg (measured),
     * which puts the 315-deg corner crossing 2.5e-3 u outside the side edge at mapHalf 7500 - past the
     * 1e-3 u epsilon, so the side edge would be lost. A side
     * entry gives a vertical line, a top/bottom entry a horizontal one; it sits {@link #LINE_OFFSET}
     * outside that edge, centred on the crossing, with length {@code |trunc(2*mapHalf / cos)|} (or
     * {@code / sin}) in double. {@code preAdvanceSpan} = the shifted entry point to the exit crossing
     * (the first other edge crossed more than 1 u away, L1) + {@link #SWEEP_MARGIN}. Never throws: a
     * non-positive or non-finite {@code mapHalf}, a non-finite drift, no entry, no exit or a non-finite
     * span returns {@link #fallback}. Written from the functional spec, item B3.
     *
     *
     */
    public static SpawnLine spawnLine(float mapHalf, float driftDeg) {
        float h = mapHalf;
        if (!(h > 0f) || !Float.isFinite(h)) {
            return fallback(mapHalf);
        }
        float theta = normalizeDeg(driftDeg);
        double rad = Math.toRadians(theta);
        double c = Math.cos(rad);
        double s = Math.sin(rad);
        // Index 0 = LEFT (x = -h), 1 = RIGHT (x = +h), 2 = TOP (y = +h), 3 = BOTTOM (y = -h); null = no crossing.
        float[][] hit = {
                edgeCrossing(true, -h, c, s, h),
                edgeCrossing(true, h, c, s, h),
                edgeCrossing(false, h, s, c, h),
                edgeCrossing(false, -h, s, c, h)};
        int sx = theta > 90f && theta < 270f ? -1 : 1;
        int sy = theta > 180f ? -1 : 1;
        int entry = hit[0] != null && sx > 0 ? 0
                : hit[1] != null && sx < 0 ? 1
                : hit[2] != null && sy < 0 ? 2
                : hit[3] != null && sy > 0 ? 3
                : -1;
        if (entry < 0) {
            return fallback(mapHalf);
        }
        float[] in = hit[entry];
        float[] out = null;
        for (int e = 0; e < hit.length && out == null; e++) {
            float[] p = hit[e];
            if (e != entry && p != null
                    && Math.abs((double) p[0] - in[0]) + Math.abs((double) p[1] - in[1]) > 1.0) {
                out = p;
            }
        }
        if (out == null) {
            return fallback(mapHalf);
        }
        boolean vertical = entry < 2;
        float fieldWidth = 2f * h;
        float len = Math.abs((int) (fieldWidth / (vertical ? c : s)));
        float shiftedX = vertical ? in[0] - LINE_OFFSET * sx : in[0];
        float shiftedY = vertical ? in[1] : in[1] - LINE_OFFSET * sy;
        float span = (float) (Math.hypot((double) out[0] - shiftedX, (double) out[1] - shiftedY) + SWEEP_MARGIN);
        if (!Float.isFinite(span)) {
            return fallback(mapHalf);
        }
        return vertical
                ? new SpawnLine((int) shiftedX, (int) (in[1] - len / 2f), 1f, len, span)
                : new SpawnLine((int) (in[0] - len / 2f), (int) shiftedY, len, 1f, span);
    }

    /** The never-throw fallback: the bottom edge, full width, diagonal span. */
    private static SpawnLine fallback(float mapHalf) {
        return new SpawnLine(-mapHalf, -mapHalf - LINE_OFFSET, 2f * mapHalf, 1f,
                (float) (2.0 * mapHalf * Math.sqrt(2.0)) + SWEEP_MARGIN);
    }

    /**
     * Where the drift line (through the origin along the unit direction, reach {@link #FAR} each way)
     * crosses one edge of the square, or {@code null} when it does not. {@code edge} is the edge's own
     * coordinate (x for a side edge, y for top/bottom), {@code across} the direction component normal to
     * the edge, {@code along} the component parallel to it. OUR pinned corner law: the edge (|a| <= h)
     * and the reach (|t| <= FAR) are closed and extended by {@link #ENDPOINT_EPS_U}, with t and the
     * along-edge coordinate a computed in double, so an exact-corner drift finds both edges. The
     * point carries the edge coordinate exactly and a rounded to float; a zero divisor
     * or any NaN means no crossing. Written from the functional spec, item B3 step 1.
     *
     */
    private static float[] edgeCrossing(boolean sideEdge, float edge, double across, double along, float h) {
        if (across == 0.0) {
            return null;
        }
        double t = edge / across;
        double a = t * along;
        if (!(Math.abs(t) <= (double) FAR + ENDPOINT_EPS_U) || !(Math.abs(a) <= (double) h + ENDPOINT_EPS_U)) {
            return null;
        }
        return sideEdge ? new float[] {edge, (float) a} : new float[] {(float) a, edge};
    }

    /** One seeded body draw (engine units: deg, u, u/s, deg/s). */
    public record Draw(int size, float x, float y, float vx, float vy, float facing, float angVel, float scale) {
    }

    /**
     * One seeded body draw on the round's {@link Random} (each U = one {@code nextFloat()}). The call
     * ORDER is our law - a seeded round replays from it (row L9 pins it): U x, U y, [U pre-advance, only
     * when {@code initial}], {@code nextInt(SIZE_COUNT)} size, U speed, U heading, U angVel, U scale,
     * U facing = 8 calls, 9 when initial. The point lies on {@code line}; an initial draw then moves
     * downwind along {@code unit(driftDeg)} (never the body's heading) by U x {@code preAdvanceSpan}.
     * Speed 20..70 u/s along a heading within +-10 deg of the drift (not wrapped), angVel [-15, 15)
     * deg/s, scale [0.75, 1], facing [0, 360) deg; the velocity is speed x {@code unit(heading)}.
     * Float arithmetic. Written from the functional spec, item B4.
     *
     */
    public static Draw draw(Random rng, SpawnLine line, float driftDeg, boolean initial) {
        float x = line.x() + rng.nextFloat() * line.width();
        float y = line.y() + rng.nextFloat() * line.height();
        if (initial) {
            float advance = rng.nextFloat() * line.preAdvanceSpan();
            float[] downwind = unit(driftDeg);
            x += advance * downwind[0];
            y += advance * downwind[1];
        }
        int size = rng.nextInt(SIZE_COUNT);
        float speed = SPEED_MIN + rng.nextFloat() * (SPEED_MAX - SPEED_MIN);
        float heading = driftDeg + rng.nextFloat() * 20f - 10f;
        float angVel = rng.nextFloat() * 30f - 15f;
        float scale = 0.75f + rng.nextFloat() * 0.25f;
        float facing = rng.nextFloat() * 360f;
        float[] dir = unit(heading);
        return new Draw(size, x, y, speed * dir[0], speed * dir[1], facing, angVel, scale);
    }

    /** The round's seed: {@code seedKnob ^ (round * golden)} - stable, distinct per round. */
    public static long roundSeed(long seedKnob, int round) {
        return seedKnob ^ (round * 0x9E3779B97F4A7C15L);
    }

    /** The round's drift angle = the FIRST draw of the round's Random, U[0,360). */
    public static float driftDeg(Random rng) {
        return rng.nextFloat() * 360f;
    }

    /** Beyond {@code mapHalf + margin} on either axis: the keeper's off-map sweep (OUR strict centred box). */
    public static boolean outside(float x, float y, float mapHalf, float margin) {
        float lim = mapHalf + margin;
        return x < -lim || x > lim || y < -lim || y > lim;
    }
}
