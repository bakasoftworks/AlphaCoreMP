package cc.starsector.client.net;

/**
 * The scalar law for host-sent ship numbers that reach an engine setter
 * as a value, not as a place: the combat-readiness (CR) clock and the flux record. The sibling of
 * {@link WireWorldLaw}, which holds the place, speed and facing bounds.
 *
 * <p>WHY: a joiner presents numbers sent by a host it does not trust, and a lawful server never sends any value
 * this law changes (its own codec refuses a non-finite or negative CR value and a non-finite flux value). A CR
 * of +Inf or a huge finite CR scales the ship's turn rate with no ceiling, and the engine's facing setter then
 * subtracts 360 until the value is at most 360, which never returns on a huge value ({@link WireWorldLaw}); a
 * non-finite flux value is stored by the engine's flux tracker as it is.
 *
 * <p>THE POSTURE: a record that fails its FINITE test is DROPPED whole by the pose attach (the pose keeps its
 * unknown tier, which the sparse tiers read as "unchanged"), never refused: a refused snapshot frame ends the
 * joiner's session. A FINITE value past a bound is HELD to the bound and the record attaches. The law does not
 * make a host honest about CR: the host is authoritative over it and may send 0. It keeps the engine's own
 * arithmetic in the range it was written for.
 *
 * <p>THE NUMBERS:
 * <ul>
 * <li>{@link #MAX_CR}: the engine's CR is a fraction in [0, 1]; the server holds its deploy seed to 1 at the
 *     source, so a lawful CR never exceeds it.</li>
 * <li>{@link #MAX_CR_DEP_SEC}: the raw deployed-seconds clock, which the own-ship CR pin writes into the local
 *     engine's deployed time. A lawful fight runs minutes to hours; the bound is 11.6 days.</li>
 * <li>{@link #MAX_CR_PEAK_SEC}: the effective peak-readiness duration. A hull row with no "peak CR sec" column
 *     loads 9,999,999 s (the never-lose-CR hulls), and the server sends that value unclamped; peak-time
 *     bonuses raise it further. The bound is 10 times the bare default.
 *</li>
 * </ul>
 *
 * <p>Both record predicates name the FIRST failing field (a constant token for a census row, never a host
 * value), or null when the record may attach. Pure and import-free: the game-free compile and the selftests
 * reach it.
 */
public final class WireScalarLaw {
    /** Inclusive upper bound of a wire CR fraction; a finite CR above it is held to it. */
    public static final float MAX_CR = 1f;
    /** Inclusive upper bound of the wire deployed-seconds clock, in seconds (11.6 days). */
    public static final float MAX_CR_DEP_SEC = 1.0e6f;
    /** Inclusive upper bound of the wire effective peak-readiness duration, in seconds. */
    public static final float MAX_CR_PEAK_SEC = 1.0e8f;

    private WireScalarLaw() {
    }

    /**
     * The first field of a CR record that drops it: {@code "cr"} when the CR is not finite or below 0 (NaN fails
     * {@code cr >= 0}), {@code "crDepSec"} / {@code "crPeakSec"} when that clock is not finite; null when the
     * record may attach. A finite clock below 0 or above its bound is held, never dropped.
     */
    public static String crRecordFault(float cr, float crDepSec, float crPeakSec) {
        if (!(cr >= 0f) || !Float.isFinite(cr)) {
            return "cr";
        }
        if (!Float.isFinite(crDepSec)) {
            return "crDepSec";
        }
        if (!Float.isFinite(crPeakSec)) {
            return "crPeakSec";
        }
        return null;
    }

    /** A CR that passed {@link #crRecordFault}: unchanged in [0, {@link #MAX_CR}], {@link #MAX_CR} above it. */
    public static float boundedCr(float cr) {
        return cr > MAX_CR ? MAX_CR : cr;
    }

    /** A finite deployed-seconds clock held to [0, {@link #MAX_CR_DEP_SEC}]. */
    public static float boundedCrDepSec(float sec) {
        return sec < 0f ? 0f : sec > MAX_CR_DEP_SEC ? MAX_CR_DEP_SEC : sec;
    }

    /** A finite peak-readiness duration held to [0, {@link #MAX_CR_PEAK_SEC}]. */
    public static float boundedCrPeakSec(float sec) {
        return sec < 0f ? 0f : sec > MAX_CR_PEAK_SEC ? MAX_CR_PEAK_SEC : sec;
    }

    /**
     * The first field of a flux record that drops it ({@code "currFlux"},
     * {@code "maxFlux"}, {@code "fluxLevel"}, {@code "hardFlux"}: NaN or either infinity), or null when the record
     * may attach. A FINITE value passes unchanged: a negative is lawful on this tier (the unknown sentinel is
     * -1), and the engine's own tracker clamps a finite value it is handed.
     */
    public static String fluxRecordFault(float currFlux, float maxFlux, float fluxLevel, float hardFlux) {
        if (!Float.isFinite(currFlux)) {
            return "currFlux";
        }
        if (!Float.isFinite(maxFlux)) {
            return "maxFlux";
        }
        if (!Float.isFinite(fluxLevel)) {
            return "fluxLevel";
        }
        if (!Float.isFinite(hardFlux)) {
            return "hardFlux";
        }
        return null;
    }
}
