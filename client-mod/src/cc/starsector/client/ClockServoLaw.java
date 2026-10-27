package cc.starsector.client;

/**
 * The clock-servo CONTROL LAW as a pure, game-free class: the
 * code-motion extraction of {@code PredictReconcileCombatPlugin.advanceClockServo}'s arithmetic
 * (outlier guards, exp-decayed pacer/wall/corrected window, clamped inverse target, applied-mult
 * state), with every engine read and side effect left plugin-side. The plugin delegates each
 * measured frame via {@link #fold} and applies {@link #mult()} on {@code engine.getTimeMult()}
 * itself; this class touches no Starsector API, so the I.8 offline replay harness can construct it
 * on a bare JDK and re-run recorded series (or candidate parameters) with no game.
 *
 * Law provenance: the servo design record; clamp history and outlier
 * guards moved verbatim from the plugin's field block. The no-arg constructor IS the proven live
 * law; the parameterized constructor exists for harness A/B candidates only — the plugin must never
 * use it without a fired trigger + its own evidence round ([[dont-live-tweak-proven-mechanisms]]).
 */
public final class ClockServoLaw {

    /** EMA time-constant for the pacer-ratio window: long enough to smooth ms-quantization + scheduler
     *  jitter, short enough to re-track an fps-cap change within a few seconds. */
    public static final double SERVO_TC_SEC = 3.0d;
    /** Stay neutral until this much (decayed) wall-clock is in the window — the ratio is noise before that. */
    public static final double SERVO_MIN_WINDOW_SEC = 1.0d;
    /** Safety clamp — BOTH edges have now been the binding edge once, so both carry measured headroom.
     *  CEILING: an operator's fps:540 serverless verify measured the needed mult at 1.35–1.41
     *  (sleep(1)-regime deficit swings ~0.72–0.85 with Windows scheduler mood), so the original 1.40
     *  ceiling railed; 2.0 = ~40% headroom over the worst measured need. FLOOR: a 2026-06-10 live-run
     *  decode measured the NO-SLEEP regime — when the frame budget remainder ms-truncates to sleep(0)
     *  the loop free-runs PAST the fps cap (fps 463–753 at cap 540) while the engine still credits
     *  exactly 1/fpsTarget per frame, so raw ≈ fps/fpsTarget ran up to 1.41 and the needed mult down to
     *  540/753 ≈ 0.72; the old 0.90 floor stayed railed for ~117 s (servo-trace target 0.79–0.88) and the
     *  client ran up to +27%/s fast — the cooldown-phase weapon desync's source. 0.50 = the same ~40%
     *  headroom below the worst measured need. The clamp can never run away in either direction (the
     *  servo divides itself out of its own measurement — sanity, not the control law); a mult < 1 only
     *  SHRINKS sim steps, so the floor has no integrator-cap exposure. */
    public static final float SERVO_MIN_MULT = 0.50f;
    public static final float SERVO_MAX_MULT = 2.00f;
    /** Outlier guard: a frame whose real dt OR credited amount reaches the engine's own MAX_STEP clamp
     *  (1/30 s, STEP-7 disasm) is a load/GC hitch (engine drops time) or a post-load credit spike — its
     *  pacer ratio is meaningless, and folding it in is what slammed the servo onto its clamp during the
     *  fps:540 verify's spawn seconds. Excluded from the servo window (mirrors the pause-gap exclusion). */
    public static final double SERVO_OUTLIER_DT_SEC = 1.0d / 30.0d;
    /** I.11 guard split: the RAW-credit form of the outlier threshold. The engine clamps
     *  its credit local at MAX_STEP BEFORE the time-mult multiply, so a capped credit reads MAX_STEP in raw
     *  form regardless of the mult in effect — testing the post-mult amount made the exclusion threshold
     *  servo-mult-dependent. Epsilon under the exact 1/30 because the engine multiplies in float and the
     *  divide-back in double can land a hair under. */
    public static final double SERVO_OUTLIER_CREDIT_SEC = SERVO_OUTLIER_DT_SEC * 0.999d;

    private final double tcSec;
    private final double minWindowSec;
    private final float minMult;
    private final float maxMult;
    private final double outlierDtSec;
    private final double outlierCreditSec;

    private double pacerAccumSec;  // exp-decayed Σ raw pacer credit (amount / time-mult in effect)
    private double wallAccumSec;   // exp-decayed Σ real wall dt over the SAME measured frames
    private double corrAccumSec;   // exp-decayed Σ corrected credit (amount) — the "now" rate numerator
    private float mult = 1f;       // the mult the law currently commands (the plugin applies it)
    private float simSpeed = 1f;   // announced server sim-speed S — scales the target

    /** The proven live law — canonical constants, exactly the pre-extraction behavior. */
    public ClockServoLaw() {
        this(SERVO_TC_SEC, SERVO_MIN_WINDOW_SEC, SERVO_MIN_MULT, SERVO_MAX_MULT,
                SERVO_OUTLIER_DT_SEC, SERVO_OUTLIER_CREDIT_SEC);
    }

    /** Harness-only candidate parameters (I.8 A/B replay) — never the plugin's constructor. */
    public ClockServoLaw(double tcSec, double minWindowSec, float minMult, float maxMult,
            double outlierDtSec, double outlierCreditSec) {
        this.tcSec = tcSec;
        this.minWindowSec = minWindowSec;
        this.minMult = minMult;
        this.maxMult = maxMult;
        this.outlierDtSec = outlierDtSec;
        this.outlierCreditSec = outlierCreditSec;
    }

    /**
     * One servo step for ONE measured frame: recover the engine's raw open-loop pacer credit from the
     * time-mult in effect when the engine computed {@code amount}, fold pacer + wall dt into the decayed
     * window, and update the clamped inverse {@link #mult()}. Pure — the caller reads the engine stat
     * and applies the mult (when {@link #applyReady()} and the return is non-NaN, mirroring the
     * pre-extraction apply condition exactly).
     *
     * @return the raw pacer credit this frame folded into the window; {@code NaN} when an outlier guard
     *         excluded the frame (window and mult untouched).
     */
    public double fold(float amount, double realDt, double multInEffect) {
        if (realDt >= outlierDtSec) {
            return Double.NaN; // hitch outlier — real time jumped (load/GC); its ratio is meaningless
        }
        double rawPacerDt = multInEffect > 0.01d ? amount / multInEffect : amount;
        if (rawPacerDt >= outlierCreditSec) {
            return Double.NaN; // post-load credit spike: the engine credited MAX_STEP (raw form — I.11)
        }
        double decay = Math.exp(-realDt / tcSec);
        pacerAccumSec = pacerAccumSec * decay + rawPacerDt;
        wallAccumSec = wallAccumSec * decay + realDt;
        corrAccumSec = corrAccumSec * decay + amount;
        if (wallAccumSec < minWindowSec || pacerAccumSec <= 0d) {
            return rawPacerDt; // warmup: stay neutral until the window carries signal
        }
        float target = (float) (wallAccumSec / pacerAccumSec);
        if (target < minMult) {
            target = minMult;
        } else if (target > maxMult) {
            target = maxMult;
        }
        mult = target * simSpeed;
        return rawPacerDt;
    }

    /**
     * Announced dilation: retarget the law at the server's ANNOUNCED
     * sim-speed {@code S} (the AWSD v5 {@code simSpeedPermille} scalar, wire-enforced to
     * [0.25, 2.0] upstream — this stays a pure store). The measured wall/pacer ratio keeps its
     * own clamp exactly as before (sanity on the MEASUREMENT — the servo divides itself out of
     * it); {@code S} then scales the commanded mult exactly, so the effective bound is
     * [{@code minMult}×S, {@code maxMult}×S] — corrected sim advance converges to S× wall,
     * matching the server's dilated pace. Default 1 = the pre-leg-6 law byte-identically.
     * Non-finite or non-positive values are refused (defensive; the wire cannot produce them).
     */
    public void setSimSpeed(float s) {
        if (Float.isFinite(s) && s > 0f) {
            simSpeed = s;
        }
    }

    /** The announced sim-speed {@code S} currently scaling the target (1 = undialated). */
    public float simSpeed() {
        return simSpeed;
    }

    /** The servo-off / stat-unavailable path: no modifier of ours is applied, so {@code amount} IS the
     *  raw credit — rate it for the trace, NaN-excluding a MAX_STEP credit (same guard as {@link #fold}). */
    public static double passthroughRawCredit(float amount) {
        return amount >= SERVO_OUTLIER_CREDIT_SEC ? Double.NaN : amount;
    }

    /** True once the window carries signal AND the last fold updated {@link #mult()} state — the
     *  pre-extraction post-fold apply condition ({@code wallAccum >= minWindow && pacerAccum > 0}). */
    public boolean applyReady() {
        return wallAccumSec >= minWindowSec && pacerAccumSec > 0d;
    }

    public double wallAccumSec() {
        return wallAccumSec;
    }

    public double pacerAccumSec() {
        return pacerAccumSec;
    }

    public double corrAccumSec() {
        return corrAccumSec;
    }

    public float mult() {
        return mult;
    }

    /** Teardown hygiene half ({@code removeClockServo}): re-neutralize the commanded mult, KEEPING the
     *  window (pre-extraction behavior — only the engine-side modifier is dropped). */
    public void neutralize() {
        mult = 1f;
    }

    /** Full state zero — harness use between replayed combats (a new plugin instance per combat starts
     *  fresh). The plugin itself never calls this: the deliberate no-feed→fed window carry (I.4/I.7,
     *  resetClockMeter's doc) depends on the law state surviving meter resets. */
    public void reset() {
        pacerAccumSec = 0d;
        wallAccumSec = 0d;
        corrAccumSec = 0d;
        mult = 1f;
        simSpeed = 1f;
    }
}
