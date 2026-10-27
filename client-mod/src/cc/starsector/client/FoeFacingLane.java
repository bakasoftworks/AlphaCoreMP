package cc.starsector.client;

/**
 * The FOE-FACING lane (knob {@code cc.predict.foeFacing}; statics =
 * {@code PredictKnobConfig.FOE_FACING_MODE}): owns the sub-quantum facing
 * DEADBAND and the client's first angular telemetry, so the plugin keeps only a thin
 * touchpoint at the one facing write in {@code reconcileFoeTo} (the {@link FoeInterpLane}
 * isolation idiom).
 *
 * <p><b>The defect it exists for (C5 station spin, root-caused offline):</b> the wire
 * carries the station's angular velocity <em>exactly</em> (-2.000 °/s resolved losslessly on
 * the 0.5 °/s grid, never zero in 35 090 samples) and a facing resolved to 0.5°. At
 * 0.0333 °/tick a 0.5° facing grid only steps every 15 ticks, so the AWSD facing target
 * advances at <b>4 Hz in 0.5° jumps</b> with the {@code POSE_FACING} delta bit cleared in
 * between. The old law glided at {@code FOE_RECONCILE_RATE=155.3}/s (α≈0.25/frame at 540 fps
 * ⇒ 92.5 % of the residual closed inside one snapshot interval), which is close enough to a
 * snap that it <em>overwrote</em> the engine's own exact integration of the carried ω and
 * reproduced the staircase. Both halves of the signal arrived intact; the presentation law
 * discarded the smooth half.
 *
 * <p><b>The law this lane encodes:</b> never correct below the wire's own resolution — a
 * sub-quantum residual is quantization noise, not signal. The correction applied is the
 * residual that EXCEEDS one facing quantum ({@link #QUANTUM_DEG}), a <em>soft</em> deadband
 * so the response stays continuous in the residual (no boundary chatter) and the retained
 * error is bounded at ~1 quantum and cannot accumulate. Between wire steps the engine's own
 * free integrator (fed by the {@code setAngularVelocity(ω)} that
 * the same seam writes every frame) carries the rotation smoothly. Engine-faithful:
 * the engine already rotates the base at the authoritative rate; we stop fighting it.
 *
 * <p><b>Preconditions, and why each is required:</b>
 * <ul>
 *   <li>{@code Float.isFinite(ω) && ω != 0} — the deadband hands the between-steps motion to
 *       the engine's integrator, so it may only engage when there IS an integrator to hand it
 *       to. This also declines correctly inside the {@code ANG_VEL_GRID} dead zone
 *       (|ω| &lt; 0.25 °/s quantizes to 0): there the wire says "not turning" while the body
 *       may be, and the glide must stay in charge. There is no richer signal available — the
 *       {@code POSE_ANG_VEL} present-bit is consumed as hold-last inside the reader
 * and never reaches this seam.</li>
 *   <li>not an INTERPOLATED target — under {@code cc.predict.foeInterp=apply} the target comes
 *       from {@code SnapshotInterpolator.lerpAngleDeg}, i.e. already sub-quantum continuous;
 *       deadbanding that would chop a signal that is not stepped. The lane declines and the
 *       plain glide runs (counted, {@code interp_target}).</li>
 *   <li>not a teleport SNAP frame — the snap hatch owns that transit; the lane
 *       is never consulted there and {@link #noteSnap} re-arms the step meter across the
 *       discontinuity so a teleport never lands in the smoothness statistics.</li>
 * </ul>
 *
 * <p><b>Cost when it declines to help:</b> a fast turner (40 °/s ⇒ 0.67 °/tick) steps well
 * above the deadband every tick, so it is unaffected in kind and pays at most one quantum of
 * constant lag — i.e. strictly within the wire's own resolution. Nothing accumulates: ω is
 * re-written from the wire every frame, so the absent server {@code turnDeceleration} damping
 * (the top-ranked netcode threat) cannot bite here.
 *
 * <p><b>The telemetry (the owe).</b> Before this lane there was NO angular telemetry
 * anywhere on the client — {@link FoeInterpLane}'s {@code div_*} family is position-only (su)
 * — which made C5 acceptance ungradeable in the same way C1's mine lane is. Two
 * independent families are metered, because they move in OPPOSITE directions and the honest
 * record needs both:
 * <ul>
 *   <li><b>divergence</b> {@code adiv_*} (wrapped degrees, presented-vs-authoritative) — the
 *       MEAN is the price the deadband pays (it parks on a steady sub-quantum offset instead
 *       of chasing the target back down after every step: 0.126° vs 0.026° measured). The PEAK
 *       is bounded by one quantum either way and comes out LOWER than today's (0.283° vs
 *       0.485°), because the old law lets the error swing nearly a full quantum right after
 *       each wire crossing before snapping back — measured, not predicted;</li>
 *   <li><b>smoothness</b> {@code astep_*} — the frame-to-frame change of the PRESENTED angle,
 *       the quantity the defect is actually about. Column names mirror
 *       {@code CcwrStationSpin}'s server-side table (changes / change_hz / mean_step /
 *       max_step) so the two sides are read against each other directly. {@code astep_max} is
 *       the discriminator: on the measured station case the old law lurches ~0.12° in a single
 *       frame off a stalled baseline, while smooth integration of -2 °/s at 540 fps advances a
 *       constant ~0.0037° — a ~33× separation, measured by {@code FoeFacingLaneSmoke}'s A/B
 *       (not derived; the earlier 0.46 % / 4 Hz figures are the per-SNAPSHOT-interval view of the
 *       same thing).</li>
 * </ul>
 * Per-entity rows are kept as well as the aggregate ({@link #perEntityReport}): the station
 * base is one foe among ~30 and an aggregate alone would blur its signature.
 *
 * <p><b>{@code wire_quant_frac}</b> answers, first-hand and client-side, an open question:
 * a {@code .ccwr} reel records the pre-delta float32 truth, so the 0.5° staircase
 * is DERIVED from a measured ω plus code-pinned grid constants — never observed. The
 * fraction of wire facings that land on an exact multiple of the quantum is that observation.
 *
 * <p>Engine-free on purpose (no Starsector-API import, ids as plain longs) so both the deadband
 * and the meters are exercised by the game-free {@code FoeFacingLaneSmoke} in the build's
 * in-loop suite — including its offline A/B, which models the engine's integrator in one line
 * and measures both modes' step distributions.
 *
 * <p>Threading: RENDER thread only — but {@code synchronized} like its sibling, since the
 * meter line is emitted from the same seams that read {@link FoeInterpLane}.
 */
final class FoeFacingLane {

    /**
     * One wire facing quantum, degrees — the AWSD grid this deadband is keyed to (single
     * source of truth: the client reader's own constant, never a re-typed literal).
     */
    static final float QUANTUM_DEG = cc.starsector.client.net.AwsdSnapshotReader.FACING_GRID_DEG;

    /** Below this a per-frame facing change counts as "no change" (float noise floor, deg). */
    static final float STEP_EPS_DEG = 1.0e-4f;

    /** Tolerance for "this wire facing sits on the quantum grid" (deg). */
    static final float QUANT_EPS_DEG = 1.0e-3f;

    /** Cap on per-entity rows (bounded memory; overflow is counted, never silently dropped). */
    static final int MAX_TRACKED = 128;

    private final boolean meter;
    private final boolean apply;

    // --- aggregate meters (cumulative; the lane-meter family law — never cleared per round) ---
    private long samples;              // frames the lane was consulted with a finite facing
    private double adivSum;            // sum |shortest(presented, wire)| deg, BEFORE this frame's write
    private float adivMax;
    private long stepSamples;          // frames with a previous written facing to difference against
    private long stepChanges;          // ... of those, frames the presented angle actually moved
    private double stepSum;            // sum |step| over CHANGED frames (mirrors CcwrStationSpin mean_step)
    private float stepMax;
    private double elapsedSec;         // summed frame dt over metered frames (drives change_hz)
    private long bandHeldFrames;       // deadband engaged and swallowed the whole residual
    private long bandTrimmedFrames;    // deadband engaged, residual exceeded a quantum, trimmed
    private long omegaDeclinedFrames;  // precondition failed on ω (non-finite or zero)
    private long interpDeclinedFrames; // precondition failed: target came from the interpolator
    private long alteredFrames;        // frames the written facing actually differed from the plain glide
    private long snapReArms;           // teleport snaps that re-armed the step meter
    private long wireQuantHits;        // wire facings landing on an exact quantum multiple
    private long entityOverflow;       // per-entity rows refused by MAX_TRACKED

    private final java.util.LinkedHashMap<Long, Ent> ents =
            new java.util.LinkedHashMap<Long, Ent>();

    FoeFacingLane(boolean meter, boolean apply) {
        this.meter = meter;
        this.apply = apply;
    }

    /** Per-entity smoothness/divergence row + the presented-facing carry the step meter needs. */
    private static final class Ent {
        float lastWritten = Float.NaN;   // presented facing we wrote last frame (NaN = re-armed)
        long samples;
        long changes;
        double stepSum;
        float stepMax;
        double adivSum;
        float adivMax;
        double elapsedSec;
    }

    /**
     * RENDER thread — the one facing decision for one foe replica this frame. Returns the
     * facing to WRITE; the caller writes it unconditionally (the plain glide is the
     * {@code meter}/declined result, so there is no second law at the call site).
     *
     * @param entityId        wire entity id (per-entity meter row key)
     * @param current         the replica's facing right now — already carries the engine's
     *                        integration of ω since our last write, which is exactly what the
     *                        step meter must see
     * @param wireFacing      authoritative facing, degrees (finite; checked by the caller)
     * @param wireAngVel      authoritative angular velocity, deg/s — the deadband's precondition
     * @param alpha           this frame's {@code frameAlpha(FOE_RECONCILE_RATE, dt)} glide factor
     * @param dtSec           this frame's dt, seconds (drives {@code change_hz}); &lt;=0 is tolerated
     * @param interpolatedTarget the target came from the interpolator (declines)
     */
    synchronized float reconcileFacing(long entityId, float current, float wireFacing,
            float wireAngVel, float alpha, float dtSec, boolean interpolatedTarget) {
        float delta = shortestDeltaDeg(current, wireFacing);
        boolean omegaOk = Float.isFinite(wireAngVel) && wireAngVel != 0f;
        boolean deadband = omegaOk && !interpolatedTarget;

        float effDelta = delta;
        if (deadband) {
            // Soft deadband: keep only the residual that EXCEEDS one quantum. Continuous in
            // delta (no chatter at the boundary) and the retained error stays bounded at ~1
            // quantum, so it cannot accumulate.
            float clamped = delta > QUANTUM_DEG ? QUANTUM_DEG
                    : (delta < -QUANTUM_DEG ? -QUANTUM_DEG : delta);
            effDelta = delta - clamped;
        }

        float plainGlide = current + alpha * delta;
        float written = apply ? current + alpha * effDelta : plainGlide;

        if (meter) {
            samples++;
            if (dtSec > 0f && Float.isFinite(dtSec)) {
                elapsedSec += dtSec;
            }
            float adiv = Math.abs(delta);
            adivSum += adiv;
            if (adiv > adivMax) {
                adivMax = adiv;
            }
            if (!omegaOk) {
                omegaDeclinedFrames++;
            } else if (interpolatedTarget) {
                interpDeclinedFrames++;
            } else if (effDelta == 0f) {
                bandHeldFrames++;
            } else {
                bandTrimmedFrames++;
            }
            if (effDelta != delta) {
                // Counted in BOTH modes: in meter the deadband is computed but not written, so
                // this is the honest would-be measure the meter-first idiom wants.
                alteredFrames++;
            }
            if (onQuantumGrid(wireFacing)) {
                wireQuantHits++;
            }
            Ent e = row(entityId);
            if (e != null) {
                e.samples++;
                if (dtSec > 0f && Float.isFinite(dtSec)) {
                    e.elapsedSec += dtSec;
                }
                e.adivSum += adiv;
                if (adiv > e.adivMax) {
                    e.adivMax = adiv;
                }
                if (!Float.isNaN(e.lastWritten)) {
                    float step = Math.abs(shortestDeltaDeg(e.lastWritten, written));
                    stepSamples++;
                    if (step > STEP_EPS_DEG) {
                        stepChanges++;
                        stepSum += step;
                        e.changes++;
                        e.stepSum += step;
                        if (step > stepMax) {
                            stepMax = step;
                        }
                        if (step > e.stepMax) {
                            e.stepMax = step;
                        }
                    }
                }
                e.lastWritten = written;
            }
        }
        return written;
    }

    /**
     * RENDER thread — the teleport snap hatch wrote {@code facing} directly.
     * Carries the presented angle across the discontinuity WITHOUT minting a step sample: a
     * teleport is a legitimate jump and must never contaminate the smoothness statistics.
     */
    synchronized void noteSnap(long entityId, float facing) {
        if (!meter) {
            return;
        }
        Ent e = row(entityId);
        if (e != null) {
            e.lastWritten = facing;
            snapReArms++;
        }
    }

    /**
     * RENDER thread — I9 round hygiene (MRST {@code finishMatchReset} + respawn re-adopt):
     * drop the presented-facing carry so a cross-round pose jump never mints a step sample.
     * Cumulative meters persist (the lane-meter family law, {@link FoeInterpLane#resetRoundLocal}).
     */
    synchronized void resetRoundLocal() {
        for (Ent e : ents.values()) {
            e.lastWritten = Float.NaN;
        }
    }

    /** Meter-line tokens (the lane-meter block idiom). */
    synchronized String meterTokens() {
        double adivMean = samples > 0 ? adivSum / samples : 0d;
        double stepMean = stepChanges > 0 ? stepSum / stepChanges : 0d;
        double changeHz = elapsedSec > 0d ? stepChanges / elapsedSec : 0d;
        double zeroFrac = stepSamples > 0 ? 1d - ((double) stepChanges / stepSamples) : 0d;
        double quantFrac = samples > 0 ? (double) wireQuantHits / samples : 0d;
        return " quantum_deg=" + fmt4(QUANTUM_DEG)
                + " samples=" + samples
                + " adiv_mean_deg=" + fmt4(adivMean)
                + " adiv_max_deg=" + fmt4(adivMax)
                + " astep_samples=" + stepSamples
                + " astep_changes=" + stepChanges
                + " astep_change_hz=" + fmt2(changeHz)
                + " astep_zero_frac=" + fmt4(zeroFrac)
                + " astep_mean_deg=" + fmt4(stepMean)
                + " astep_max_deg=" + fmt4(stepMax)
                + " band_held=" + bandHeldFrames
                + " band_trimmed=" + bandTrimmedFrames
                + " omega_declined=" + omegaDeclinedFrames
                + " interp_declined=" + interpDeclinedFrames
                + " altered_frames=" + alteredFrames
                + " snap_rearms=" + snapReArms
                + " wire_quant_frac=" + fmt4(quantFrac)
                + " tracked=" + ents.size()
                + " ent_overflow=" + entityOverflow;
    }

    /**
     * One line per tracked entity, ordered by sample count (descending) — the station base is
     * picked out of the fleet by id (the bind log names it, {@code band COMMITTED base id=}).
     * Column names mirror {@code CcwrStationSpin} so server and client tables read side by side.
     */
    synchronized String perEntityReport() {
        java.util.List<java.util.Map.Entry<Long, Ent>> rows =
                new java.util.ArrayList<java.util.Map.Entry<Long, Ent>>(ents.entrySet());
        java.util.Collections.sort(rows, new java.util.Comparator<java.util.Map.Entry<Long, Ent>>() {
            @Override
            public int compare(java.util.Map.Entry<Long, Ent> a, java.util.Map.Entry<Long, Ent> b) {
                long d = b.getValue().samples - a.getValue().samples;
                return d == 0 ? Long.compare(a.getKey().longValue(), b.getKey().longValue()) : (d > 0 ? 1 : -1);
            }
        });
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Long, Ent> r : rows) {
            Ent e = r.getValue();
            double stepMean = e.changes > 0 ? e.stepSum / e.changes : 0d;
            double changeHz = e.elapsedSec > 0d ? e.changes / e.elapsedSec : 0d;
            double adivMean = e.samples > 0 ? e.adivSum / e.samples : 0d;
            sb.append("\n  id=").append(r.getKey())
                    .append(" samples=").append(e.samples)
                    .append(" changes=").append(e.changes)
                    .append(" change_hz=").append(fmt2(changeHz))
                    .append(" mean_step_deg=").append(fmt4(stepMean))
                    .append(" max_step_deg=").append(fmt4(e.stepMax))
                    .append(" adiv_mean_deg=").append(fmt4(adivMean))
                    .append(" adiv_max_deg=").append(fmt4(e.adivMax));
        }
        return sb.toString();
    }

    /** Shortest signed arc {@code from -> to}, degrees in (-180,180] — the same law as PredictReconcileCombatPlugin's. */
    static float shortestDeltaDeg(float from, float to) {
        return ((to - from + 540f) % 360f) - 180f;
    }

    /** Does this wire facing land on an exact multiple of the facing quantum? */
    static boolean onQuantumGrid(float deg) {
        float steps = deg / QUANTUM_DEG;
        return Math.abs(steps - Math.round(steps)) * QUANTUM_DEG <= QUANT_EPS_DEG;
    }

    /** Drop a reaped entity's row so it stops occupying one of the
     *  {@link #MAX_TRACKED} slots (the flight pinned {@code tracked=128} from ~40 % in:
     *  dead fighters held the slots and every later foe was refused as {@code entityOverflow}).
     *  Called from the registry reap sweep; a never-tracked id is a no-op. */
    void release(long entityId) {
        ents.remove(Long.valueOf(entityId));
    }

    private Ent row(long entityId) {
        Long key = Long.valueOf(entityId);
        Ent e = ents.get(key);
        if (e == null) {
            if (ents.size() >= MAX_TRACKED) {
                entityOverflow++;
                return null;
            }
            e = new Ent();
            ents.put(key, e);
        }
        return e;
    }

    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String fmt4(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    // --- package-private accessors for the game-free smoke / offline A/B ---
    long samplesForTest() {
        return samples;
    }

    long stepChangesForTest() {
        return stepChanges;
    }

    long stepSamplesForTest() {
        return stepSamples;
    }

    float stepMaxForTest() {
        return stepMax;
    }

    double stepMeanForTest() {
        return stepChanges > 0 ? stepSum / stepChanges : 0d;
    }

    float adivMaxForTest() {
        return adivMax;
    }

    double adivMeanForTest() {
        return samples > 0 ? adivSum / samples : 0d;
    }

    long bandHeldFramesForTest() {
        return bandHeldFrames;
    }

    long bandTrimmedFramesForTest() {
        return bandTrimmedFrames;
    }

    long omegaDeclinedFramesForTest() {
        return omegaDeclinedFrames;
    }

    long interpDeclinedFramesForTest() {
        return interpDeclinedFrames;
    }

    long alteredFramesForTest() {
        return alteredFrames;
    }

    long snapReArmsForTest() {
        return snapReArms;
    }

    long wireQuantHitsForTest() {
        return wireQuantHits;
    }

    long entityOverflowForTest() {
        return entityOverflow;
    }

    int trackedForTest() {
        return ents.size();
    }
}
