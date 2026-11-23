package cc.starsector.client.net;

/**
 * The world-magnitude law for host-sent kinematics: one bound each for a coordinate, a speed, a facing and an
 * angular rate, shared by every reader and lane that carries a host number into the engine as a place, a velocity
 * or an angle.
 *
 * <p>WHY a finite check is not enough: the engine walks unbounded loops on a huge FINITE value. Its collision / AI
 * grid insert casts {@code offset + coordinate / cell} to int and loops up to the saturated bound, which never ends
 * from about 6.4e11 su; its fog-of-war reveal steps a float by 500, which stops advancing from about 8.6e9 su; a
 * ship's facing setter subtracts 360 until the value is at most 360, which stops changing from about 8.6e9 degrees;
 * a ship AI maneuver steps a facing by 10, which stops advancing from about 2.7e8 degrees on a NEGATIVE facing (the
 * setter adds 360 only once). A joiner whose host sends one such value hangs for good.
 *
 *
 *
 * <p>THE NUMBERS sit far above every value a lawful host sends, because a refused snapshot frame ends the joiner's
 * session, and far below the smallest engine threshold a SINGLE value reaches. A LENGTH between two lawful places is
 * not a single value: two corners of the world sit 2.8e7 su apart, and the engine's EMP arc walks its length with a
 * float accumulator, one allocated point a pass, linear in the length and without end from about 3.4e7 su.
 *So a sink that walks a length takes its own belt,
 * {@link #MAX_ARC_SU} through {@link #arcScale}:
 * <ul>
 * <li>{@link #MAX_COORD_SU}: the largest lawful map half is 60,000 su; vanilla parks a phase-anchored ship at
 *     y = -1,000,000 and a 1,000,000 su/s projectile fades for one second, so lawful rows reach about 1.1e6. The
 *     bound is 9 times that and 200 times below the nearest engine threshold (a 2.1e9 viewport loop).</li>
 * <li>{@link #MAX_SPEED_SU_PER_S}: the engine clamps ships, fighters, hulks and asteroids to 600 su/s every step;
 *     a phased ship, a missile and a projectile are not clamped. The bound is over 160 times the clamp, and a body
 *     left to coast at it needs more than five hours to reach the nearest threshold.</li>
 * <li>{@link #MAX_FACING_DEG}: a lawful facing stays within about one turn of [0, 360]; at the bound the engine's
 *     subtract loop costs under 3,000 steps a call.</li>
 * <li>{@link #MAX_ANGULAR_RATE_DEG_PER_S}: a hull turns under 1,500 degrees a second under its own engines, but a
 *     collision writes spin directly (the impulse over a moment of m * R * R / 4), so a small body rammed fast can
 *     lawfully pass any tidy number. At the bound a joiner's largest engine
 *     step (1/15 s under the clock servo's top multiplier) turns 667 degrees times the ship's own time multiplier;
 *     the facing setter adds 360 once, so a negative facing can trail a step until the next frame re-snaps it.</li>
 * <li>{@link #MAX_ARC_SU}: set by what one arc may COST, not by a refusal's price (an arc past it is drawn short,
 *     never refused). The longest lawful arc is the mote attractor's, about 4,600 su with the system-range skill;
 *     the bound is 4.3 times that, about 13,300 allocated points at the default segment length, and 1,680 times
 *     under the walk's stall (2^25 su; 2^27 and 2^28 at the segment multipliers 4 and 8 the client also uses).</li>
 * <li>{@link #MAX_MUZZLE_DRIFT_SU}: how far a presented shot's wire muzzle may sit from the shooter's LOCAL fire
 *     point and still steer the spawn; past it the shot spawns at the local fire point ({@link #muzzleDriftOk}).</li>
 * </ul>
 *
 * <p>THE TWO POSTURES: a PLACE (a coordinate, a facing) outside its bound is
 * REFUSED: nothing lawful is near the bound, and a place clamped to the edge is as wrong as the hostile one. A RATE
 * (a velocity, an angular velocity) outside its bound is BOUNDED to it, never refused: a lawful spike exists (the
 * collision spin above), a refused frame ends the session, and a rate only leads a body until the next frame
 * re-snaps its place. A non-finite rate is still the caller's refusal ({@link #boundedSpeed} and
 * {@link #boundedAngularRate} hand NaN back unchanged).
 *
 * <p>Both place predicates are false for NaN and for either infinity: {@code Math.abs(v) <= bound} fails on all
 * three, so a caller needs no separate finite check ({@link #muzzleDriftOk} likewise, through its distance). Pure and import-free: the game-free compile and the selftests reach it.
 */
public final class WireWorldLaw {
    /** Inclusive per-axis bound of a world coordinate, in su. */
    public static final float MAX_COORD_SU = 1.0e7f;
    /** Inclusive per-axis bound of a wire velocity, and the magnitude bound of a derived one, in su per second. */
    public static final float MAX_SPEED_SU_PER_S = 1.0e5f;
    /** Inclusive bound of {@code |facing|}, in degrees. */
    public static final float MAX_FACING_DEG = 1.0e6f;
    /** Inclusive bound of {@code |angular velocity|}, in degrees per second. */
    public static final float MAX_ANGULAR_RATE_DEG_PER_S = 1.0e4f;

    /**
     * Inclusive bound of the LENGTH of one engine arc, in su: the distance between the two ends the client hands
     * {@code spawnEmpArcVisual} (the one caller is {@code cc.starsector.client.ArcBelt}).
     */
    public static final float MAX_ARC_SU = 2.0e4f;

    /**
     * Inclusive bound of the distance from the shooter's LOCAL fire point
     * to a presented shot's wire muzzle, in su: inside it the wire muzzle is the spawn point, past it the shot
     * spawns at the local fire point and the event still presents whole.
     *
     * <p>WHY: the wire muzzle is a host number, bounded per axis only, so without this a host births a shot anywhere
     * in the world, far from the hull that fired it. A vanilla fire script that runs on the presented shot then draws
     * its arc from that hull to the shot, and the engine's arc walk costs one allocated point a step of its length.
     *
     * <p>THE NUMBER: the lawful drift is the shooter's travel between the fire tick and the present, plus the fire
     * point's swing as the hull turns. Measured, the largest over 24 runs holding the meter is 215 su, a FLOOR (no
     * hitch and no teleporting hull in that data); modelled, normal play reaches 510 su, a one-second hitch 1,710 and
     * a teleport 1,600. The bound is 1.75 times the modelled hitch. It is NEVER scaled by the event's age: the host
     * chooses the age.
     */
    public static final float MAX_MUZZLE_DRIFT_SU = 3.0e3f;

    private static final double MAX_SPEED_SQ = (double) MAX_SPEED_SU_PER_S * MAX_SPEED_SU_PER_S;
    private static final double MAX_ARC_SQ = (double) MAX_ARC_SU * MAX_ARC_SU;

    private WireWorldLaw() {
    }

    /** True when {@code v} is a finite world coordinate inside the bound. */
    public static boolean coordOk(float v) {
        return Math.abs(v) <= MAX_COORD_SU;
    }

    /** True when {@code deg} is a finite facing inside the bound. */
    public static boolean facingOk(float deg) {
        return Math.abs(deg) <= MAX_FACING_DEG;
    }

    /**
     * A wire velocity component held inside the speed bound: {@code v} unchanged inside it, the signed bound
     * outside (either infinity included). NaN comes back as NaN, so the caller's finite check still refuses it.
     */
    public static float boundedSpeed(float v) {
        return v > MAX_SPEED_SU_PER_S ? MAX_SPEED_SU_PER_S : v < -MAX_SPEED_SU_PER_S ? -MAX_SPEED_SU_PER_S : v;
    }

    /**
     * A wire angular velocity held inside the angular-rate bound: {@code v} unchanged inside it, the signed bound
     * outside (either infinity included). NaN comes back as NaN, so the caller's finite check still refuses it.
     */
    public static float boundedAngularRate(float v) {
        return v > MAX_ANGULAR_RATE_DEG_PER_S ? MAX_ANGULAR_RATE_DEG_PER_S
                : v < -MAX_ANGULAR_RATE_DEG_PER_S ? -MAX_ANGULAR_RATE_DEG_PER_S : v;
    }

    /**
     * A coordinate led along its velocity: {@code base + velocity * seconds} when that lands inside the world
     * bound, else {@code base} itself. The lead's time is tick arithmetic on host-chosen ticks, so a lawful
     * coordinate and a lawful velocity can still multiply out of the world; the caller's own base is the answer
     * then, never a clamp to the edge (a body pinned at 1e7 is as wrong as one at 1e12, and costs a lawful body
     * nothing: its led place is in the world).
     */
    public static float ledCoord(float base, float velocity, float seconds) {
        float led = base + velocity * seconds;
        return coordOk(led) ? led : base;
    }

    /**
     * One component of the pair {@code (vx, vy)} after the pair is scaled to a magnitude inside the speed bound;
     * {@code component} unchanged when the pair is already inside (two multiplies and a compare), 0 when either
     * component is not finite. For a velocity DERIVED from two wire positions, which no decode law can bound: it
     * is a clamp, never a refusal, so a position step (an id re-used, a teleport) costs one frame of lead.
     */
    public static float boundedSpeedComponent(float component, float vx, float vy) {
        double m2 = (double) vx * vx + (double) vy * vy;
        if (m2 <= MAX_SPEED_SQ) {
            return component;
        }
        if (!(m2 < Double.POSITIVE_INFINITY)) {
            return 0f;
        }
        return (float) (component * (MAX_SPEED_SU_PER_S / Math.sqrt(m2)));
    }

    /**
     * The scale that brings the segment from {@code (fx, fy)} to {@code (tx, ty)} inside the arc bound: exactly 1
     * when its length is already inside {@link #MAX_ARC_SU} (the caller keeps its own far end), the bound over the
     * length (below 1) when it is longer, NaN when any of the four is not finite (the caller draws no arc). A
     * clamp, never a refusal: an arc is an effect, and the event that asked for it stands.
     */
    public static float arcScale(float fx, float fy, float tx, float ty) {
        double dx = (double) tx - fx;
        double dy = (double) ty - fy;
        double m2 = dx * dx + dy * dy;
        if (m2 <= MAX_ARC_SQ) {
            return 1f;
        }
        if (!(m2 < Double.POSITIVE_INFINITY)) {
            return Float.NaN;
        }
        return (float) (MAX_ARC_SU / Math.sqrt(m2));
    }

    /**
     * True ONLY when the distance from the local fire point
     * {@code (lx, ly)} to the wire muzzle {@code (mx, my)}, computed in double, is at most
     * {@link #MAX_MUZZLE_DRIFT_SU}; false when any of the four is NaN or infinite (the distance is then NaN or
     * infinite, and the comparison fails), so the caller takes its local fire point.
     */
    public static boolean muzzleDriftOk(float lx, float ly, float mx, float my) {
        double dx = (double) mx - lx;
        double dy = (double) my - ly;
        return Math.sqrt(dx * dx + dy * dy) <= MAX_MUZZLE_DRIFT_SU;
    }
}
