package cc.starsector.client;

import cc.starsector.client.net.WireWorldLaw;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import java.awt.Color;
import org.lwjgl.util.vector.Vector2f;

/**
 * THE ONE DOOR to the engine's EMP arc: every {@code spawnEmpArcVisual} the client makes goes through
 * {@link #spawn}, which holds the arc's length inside {@link WireWorldLaw#MAX_ARC_SU}.
 *
 * <p>WHY: the engine builds an arc's graphic by walking its LENGTH with a float accumulator, one allocated point a
 * pass.
 * The walk's cost is linear in the length, and from about 3.4e7 su (2.7e8 at
 * the mote attractor's segment length) the accumulator stops advancing and the loop allocates until the heap is
 * gone. Both ends of an arc come from a host the joiner does not trust (a wire point, or the place of a body the
 * host poses), and the world law bounds each END, not the distance between two of them: two lawful places sit up to
 * 2.8e7 su apart.
 *
 * <p>THE POSTURE: an arc is an effect, so nothing here refuses the event that asked for it. A terminus farther than
 * the bound from its origin is drawn at the bound along the same line (a NEW vector: the caller's own is never
 * written); an origin or terminus that is not finite draws no arc ({@code null}, which every caller already takes
 * from the engine). A clamped arc DROPS its far anchor: the engine keeps the terminus as an offset in the anchor's
 * frame and re-derives it every render, so a kept
 * anchor up to 2.8e7 su away would swing the drawn end with every degree it turns (no rebuild, so never a cost;
 * the bound would just mean nothing on screen). A lane's own gate runs first and keeps its own posture (the arc-hit
 * lane SKIPS an event whose point is outside its 1e6 per-axis ceiling, which bounds a place, not a length).
 *
 * <p>THE NUMBER: 20,000 su is set by what one
 * event may COST, not by a refusal's price, since nothing here refuses. The longest lawful arc is the mote
 * attractor's, 3,000 su times the +50% system-range skill, about 4,600; the bound is 4.3 times that. At the bound
 * a default-segment arc is about 13,300 points (under 1 MB with the warp list), drawn in two passes a frame, and
 * the walk sits 1,680 times under its stall. How MANY arcs a second = the rate budget ({@link #POINTS_PER_SECOND}).
 *
 * <p>A call to {@code spawnEmpArcVisual} anywhere else in the client is a defect; the {@code ArcBeltRows} source
 * scan refuses it.
 */
final class ArcBelt {
    /**
     * THE RATE BUDGET: the
     * length bound holds ONE arc, this holds how many. WHY: a host's ARC_HIT edge draws one arc an event and the edge
     * drain presents a whole backlog in one frame, so a host at line rate built gigabytes of arc points a second.
     *
     * <p>THE PRICE of an arc is its estimated points, length / (1.5 x segmentLengthMult),
     * never under {@link #POINTS_FLOOR} (what
     * an arc costs at any length: the entity, its flicker, two slot specs when anchored, the graphic, two passes a
     * frame); an arc its caller will warp is priced twice (the engine keeps a second list of the same size).
     * THE BUCKET holds at most {@link #POINTS_BURST} and refills at {@link #POINTS_PER_SECOND} per
     * ENGINE second ({@code getTotalElapsedTime(false)}: arcs die on that clock and it stands still while paused, so
     * the live set is at most the refill times the longest life, about 1 s, plus the burst; a lower read = a new
     * engine = a full bucket; a joiner's clock runs at most 2x wall time). An arc priced over the tokens in hand is
     * NOT DRAWN ({@code null}); the event that asked for it is never refused. The first refusal writes ONE error
     * row; {@link #refused()} counts them all, and is the number that reconciles a lane's own arc census.
     *
     * <p>THE NUMBERS (the ruling over the lens): the lawful peak, every site at its fastest in a 400-DP battle with
     * every roll lucky, is 140,000 to 155,000 points a second (two estimates, neither measured; a busy fight is
     * 20,000 to 60,000; the wire stream alone tops out near 49,000), so 400,000 leaves 2.6x where the judge's
     * 200,000 left 1.3x. The burst = one second of refill, so a hitch or a released hold loses no lawful arc; over a
     * long pause the backlog's stale arcs beyond the burst are dropped and the row fires in a lawful match.
     * BOUNDED: the points built and kept (about 23 MB live at the worst), including the no-target arcs of the two
     * wrapped vanilla fire scripts and the shock repeater's fire-time arc, which ARE driven per host event (a
     * presented shot) and pay through {@link #charge} before vanilla draws them: the wrappers charge their own, the
     * presented-fire relay charges the shock repeater's (one arc of the weapon's range), and that relay's own budget
     * also bounds how many relayed onFire calls run at all ({@link PresentedFireRelayLaw#pay}). NOT bounded: a warped
     * arc's per-frame garbage (about 20x its price) and the frame time of the live set;
     * the other arcs vanilla's own scripts draw on replicas (the Cryoflux residue).
     *
     */
    static final int POINTS_PER_SECOND = 400_000;
    /** The bucket's size: the most estimated points one frame may build. */
    static final int POINTS_BURST = 400_000;
    /** The least any arc costs, in estimated points. */
    static final int POINTS_FLOOR = 256;

    private static int clamped;
    private static int skipped;
    private static int refused;
    private static int chargeOverlong;
    private static float tokens = POINTS_BURST;
    private static float budgetClock = Float.POSITIVE_INFINITY;

    private ArcBelt() {
    }

    /** The engine's seven-argument {@code spawnEmpArcVisual} behind the belt. */
    static EmpArcEntityAPI spawn(CombatEngineAPI engine, Vector2f from, CombatEntityAPI fromAnchor, Vector2f to,
            CombatEntityAPI toAnchor, float thickness, Color fringe, Color core) {
        Vector2f end = terminus(from, to);
        if (end == null || !admit(engine, from, end, 1f, 1)) {
            return null;
        }
        return engine.spawnEmpArcVisual(from, fromAnchor, end, end == to ? toAnchor : null, thickness, fringe, core);
    }

    /** The engine's eight-argument {@code spawnEmpArcVisual} (with params; null = the engine's defaults) behind the belt. */
    static EmpArcEntityAPI spawn(CombatEngineAPI engine, Vector2f from, CombatEntityAPI fromAnchor, Vector2f to,
            CombatEntityAPI toAnchor, float thickness, Color fringe, Color core, EmpArcEntityAPI.EmpArcParams params) {
        return spawn(engine, from, fromAnchor, to, toAnchor, thickness, fringe, core, params, 1);
    }

    /** {@link #spawn} for an arc the caller then calls {@code setWarping} on: the same arc, priced for its two lists. */
    static EmpArcEntityAPI spawnWarped(CombatEngineAPI engine, Vector2f from, CombatEntityAPI fromAnchor, Vector2f to,
            CombatEntityAPI toAnchor, float thickness, Color fringe, Color core, EmpArcEntityAPI.EmpArcParams params) {
        return spawn(engine, from, fromAnchor, to, toAnchor, thickness, fringe, core, params, 2);
    }

    private static EmpArcEntityAPI spawn(CombatEngineAPI engine, Vector2f from, CombatEntityAPI fromAnchor, Vector2f to,
            CombatEntityAPI toAnchor, float thickness, Color fringe, Color core, EmpArcEntityAPI.EmpArcParams params,
            int lists) {
        Vector2f end = terminus(from, to);
        if (end == null || !admit(engine, from, end, params == null ? 1f : params.segmentLengthMult, lists)) {
            return null;
        }
        return engine.spawnEmpArcVisual(from, fromAnchor, end, end == to ? toAnchor : null, thickness, fringe, core,
                params);
    }

    /**
     * The rate budget's gate for one arc that passed the length bound: true = draw it (its price is taken), false =
     * no arc. A segment mult that is not above zero draws nothing either (the engine's walk never ends on a step
     * that is not positive; counted with {@link #skipped()}).
     */
    private static boolean admit(CombatEngineAPI engine, Vector2f from, Vector2f end, float mult, int lists) {
        return take(engine, Math.hypot((double) end.x - from.x, (double) end.y - from.y), mult, lists);
    }

    /**
     * THE CHARGE for an arc a VANILLA script will draw on a replica, the
     * caller's next call: it makes NO engine arc call, it only pays. WHY: the two wrapped fire scripts
     * ({@link CcWrapInimicalEmanationOnFireEffect}, {@link CcWrapVoltaicDischargeOnFireEffect}) hand a no-target shot
     * to vanilla's own {@code onFire}, which spawns its arc directly, outside {@link #spawn}; the shot comes per host
     * event and the edge drain presents a whole backlog in one frame, so the COUNT of shots is the cost and nothing
     * else gates it.
     *
     * <p>The length is the double distance {@code from -> to} plus {@code padSu} (how far past {@code to} the script
     * draws its end). A length that is not at most {@link WireWorldLaw#MAX_ARC_SU} (NaN included), or a null end,
     * is REFUSED here and counted with {@link #chargeOverlong()}: the arc is never clamped, since vanilla draws it.
     * Inside the bound the rate budget prices it exactly as {@link #spawn} would (lists x the points of the length
     * at {@code mult}), against the SAME bucket, clock, {@link #refused()} count and error row. True = the caller
     * may draw it (its price is taken); false = the caller draws nothing.
     */
    static boolean charge(CombatEngineAPI engine, Vector2f from, Vector2f to, float padSu, float mult, int lists) {
        double length = from == null || to == null ? Double.NaN
                : Math.hypot((double) to.x - from.x, (double) to.y - from.y) + padSu;
        if (!(length <= WireWorldLaw.MAX_ARC_SU)) {
            chargeOverlong++;
            return false;
        }
        return take(engine, length, mult, lists);
    }

    /** The rate budget itself, shared by {@link #admit} and {@link #charge}: one bucket, one clock, one census. */
    private static boolean take(CombatEngineAPI engine, double length, float mult, int lists) {
        if (!(mult > 0f)) {
            skipped++;
            return false;
        }
        float now = engine.getTotalElapsedTime(false);
        if (now < budgetClock) { // the first read, or a new engine
            tokens = POINTS_BURST;
            budgetClock = now;
        } else if (now > budgetClock) {
            tokens = Math.min(POINTS_BURST, tokens + (now - budgetClock) * POINTS_PER_SECOND);
            budgetClock = now;
        } // a clock that is not a number refills nothing
        float price = lists * (float) Math.max(POINTS_FLOOR, length / (1.5 * mult));
        if (price > tokens) {
            if (++refused == 1) {
                PilotWatchLog.error("arc belt: the rate budget (" + POINTS_PER_SECOND + " estimated points a second, "
                        + POINTS_BURST + " at once) is spent, an arc is not drawn (a host sending far more arcs than"
                        + " a fight makes, or the backlog of a long pause; later ones are counted silently)", null);
            }
            return false;
        }
        tokens -= price;
        return true;
    }

    /**
     * The terminus the engine may walk to: {@code to} itself (the same instance) when the arc is inside the bound, a
     * new point at the bound along {@code from -> to} when it is longer, {@code null} when either end is not finite.
     */
    static Vector2f terminus(Vector2f from, Vector2f to) {
        float scale = WireWorldLaw.arcScale(from.x, from.y, to.x, to.y);
        if (scale == 1f) {
            return to;
        }
        if (!(scale < 1f)) {
            skipped++;
            return null;
        }
        clamped++;
        // double: a difference of two finite floats can pass Float.MAX_VALUE
        return new Vector2f((float) (from.x + ((double) to.x - from.x) * scale),
                (float) (from.y + ((double) to.y - from.y) * scale));
    }

    /** Arcs drawn short at the bound this JVM (a census number; a lawful match keeps it 0). */
    static int clamped() {
        return clamped;
    }

    /** Arcs not drawn for a non-finite end this JVM (a census number; a lawful match keeps it 0). */
    static int skipped() {
        return skipped;
    }

    /** Arcs not drawn because the rate budget was spent this JVM (a census number; a lawful match keeps it 0). */
    static int refused() {
        return refused;
    }

    /**
     * Charges refused for a length past the arc bound, not finite, or not measurable (a null end) this JVM (a census
     * number; a lawful match keeps it 0).
     */
    static int chargeOverlong() {
        return chargeOverlong;
    }
}
