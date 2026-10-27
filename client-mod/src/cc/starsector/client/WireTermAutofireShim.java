package cc.starsector.client;

import java.util.function.DoubleSupplier;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * The (114)(d) beam-terminus aim shim: a delegating {@link AutofireAIPlugin}
 * wrapper that, while ARMED, answers {@link #getTarget()} with the wire beam terminus
 * ({@code ServerBeamState.toX/toY} — the server engine's own cursor-stopped {@code getTo()})
 * instead of the native autofire pick.
 *
 * <p><b>Why THIS seam (the sketch corrected by bytes).</b> The engine's converge
 * length cap (length = min(range-grown,
 * dist(muzzle→aim)+2), gated on spec {@code convergeOnPoint} + a non-null aim arg) receives
 * its aim from the weapon-group advance. On a replica group whose auto-fire is ON, the group
 * rides {@code advanceAuto} (the player-override branch
 * needs {@code getPlayerShip()}), and {@code advanceAuto} DISCARDS the group-level
 * aim — each weapon advances with the per-weapon {@code AutofireAIPlugin.getTarget()}.
 * Ship {@code mouseTarget} therefore never reaches such a
 * weapon; there the plugin's target IS the aim seam the engine consults.
 * on a foe group mirrored auto-fire OFF the engine never advances or
 * consults this plugin (see the fail-safe drain paragraph); there the lane's own mount-angle write
 * from {@link #armedAim} ({@code BeamPresentationLane.maintainBeamAim}) is the aim seam. Installing this wrapper
 * in the group's LIVE plugin list ({@code WeaponGroupAPI.getAIPlugins()} returns the backing
 * list) is pure public API — no reflection, B-legal.
 *
 * <p><b>Chargedown keep-target.</b> The one aim
 * DISCARD on the consume path is the chargedown null
 * ({@code state == chargedown && !keepTargetWhileChargingDown ⇒ aim = null}) — and at guardian
 * {@code beamSpeed} 1e6 a SINGLE aim-null frame grows the ray to FULL RANGE,
 * the measured 48% out-of-arc armed-over class; the replica's force-fire cadence
 * enters local chargedown on its own envelope, so these frames land regardless of the server's
 * burst phase. {@link #installKeepTarget} closes the discard for the shim's whole tenure via
 * public {@link WeaponAPI#setKeepBeamTargetWhileChargingDown} (engine default false, never
 * spec-set); the prior value is captured in {@link #priorKeepTarget} so any future
 * uninstall sweep can restore exactly (today none exists — weapon, group list, and shim
 * despawn together under the weak-keyed install map).
 *
 * <p><b>Aim interpolation.</b> The wire
 * terminus steps at record cadence while the shooter's cursor sweeps per-frame — the visible observer
 * jerk. While armed, {@link #getTarget()} answers the LERP between
 * the two most recent DISTINCT wire termini over ONE served snapshot interval
 * ({@link #armedAim}); {@code BeamPresentationLane.maintainBeamAim} snaps the mount
 * angle from the same point, so the whole terminus (bearing + length) sweeps smoothly. O(1) per
 * beam per frame, zero wire and zero server cost — scale-safe for the stated "20+ ships with Paladins"
 * condition. Never-lengthen holds by convexity: dist(muzzle→lerp) ≤
 * max(endpoint dists), both endpoints server-authored capped termini. Cost disclosed:
 * ≤ one SERVED snapshot interval of terminus lag on the observer.
 * that bound holds while a render frame is shorter than one stride: {@code maintainBeamAim} arms and then reads at
 * ONE engine clock, so the frame a change arrives on draws f = 0, the previously seen terminus. On a renderer whose
 * frame spans more than one stride the BEARING is drawn one RENDER interval behind — more than one served interval
 * (pre-existing: the shim before the server-stride re-cut did the same).
 *
 * <p><b>the glide runs on
 * the snapshot stride the SERVER delivers.</b> The serve fans a world snapshot every
 * {@code net.downEveryTicks} sim ticks and delivers that stride as the read-only wire knob
 * {@code WireKnob.SNAP_STRIDE_TICKS} ({@link ClientWireKnobs#snapStrideSeconds}); every glide lasts
 * exactly ONE stride, however far apart the two changes were (never longer than one served
 * interval; the lag a slow renderer adds is the cost note above). The shim never reads the spacing it SAW between snapshots: a lost snapshot looks exactly
 * like a slower stream, so a seen spacing would stretch the glide across the loss
 * (the wire truth replaced that guess, which is deleted). Each change's snapshot tick is kept only for
 * the gap test. Fail-closed ⇒ snap to the newest terminus (the pre-interpolation behavior): a fresh arm; the
 * stride is unknown ({@code 0}: before the admission's first PSTB); the change is not at a LATER tick
 * than the previous change (the same snapshot, or behind it after a round wrap); the change came more
 * than {@link #GAP_FACTOR} strides after the previous one (a pause-then-move, or a loss run — both
 * read raw); or one stride is longer than {@link #GLIDE_MAX_S} (the pair rotates at the arm, and the next
 * {@link #armedAim} read collapses it onto the newest terminus, which applies raw).
 *
 * <p><b>Faithfulness.</b> While a wire beam record lives, the LOCAL engine computes length,
 * damage sweep, and render from the wire terminus via its own native cap — visual and local
 * damage agree by construction (the never-conceal bar; a render-only shorten was
 * REJECTED there). Never-lengthen holds: the wire to-point is the server's already-capped
 * (and possibly hit-shortened) terminus, so the local cap distance never exceeds the server
 * length (+2 su engine epsilon), and local sweep hits may only shorten further. Idle windows
 * delegate everything — the native AI keeps the (114)(c) idle slew behavior.
 *
 * <p><b>Fail-safe drain.</b> {@code armTicks} drains in {@link #advance} (called once per sim
 * tick by {@code advanceAuto}); the aim half re-arms per render frame from
 * {@code BeamPresentationLane.maintainBeamAim}. If the lane ever stops re-asserting, the
 * shim self-disarms within {@link #ARM_GRACE_TICKS} sim ticks and the weapon returns to the
 * native pick — same self-clearing idiom as {@code setForceFireOneFrame}.
 * the engine calls {@link #advance} only for a group whose auto-fire is ON,
 * so on an auto-fire-OFF group {@code armTicks} never drains through it — the lane's idle walk
 * ({@code BeamPresentationLane.releaseIdleBeamMounts} → {@link #disarmWireAim}) is what disarms
 * the shim there. Keep-target stays
 * set through the drain ON PURPOSE: lane death is exactly when the local retract charges down,
 * and re-opening the chargedown discard there would resurrect the one-frame run-out.
 */
final class WireTermAutofireShim implements AutofireAIPlugin {

    /** Sim ticks an arm survives without re-assert (render↔sim cadence slack; keeps the cap
     *  stable when sim ticks outpace render frames, drains fast if the lane dies). */
    static final int ARM_GRACE_TICKS = 3;

    /** Sim ticks the endpoint DROPPED at a pair rotation stays inside
     *  {@link #armedEnvelopeDist}: a ray metered just after a rotation was swept under the
     *  PREVIOUS pair's lerp (the endpoint race), so the gate meter's envelope must
     *  cover that endpoint for the ticks such a stale-swept ray can still be read. */
    private static final int DROPPED_GRACE_TICKS = 2;

    /** A terminus change arriving more
     *  than this many SERVED strides after the previous change snaps — a pause-then-move or a loss run,
     *  never swept across. VALUE-DOMAIN: this times a u8 stride is at most 765 ticks, computed in long. */
    static final int GAP_FACTOR = 3;
    /** A glide is never stretched past half a second: a served stride longer than this applies raw
     *  (a 255-tick stride passes it at every rate below 510 Hz; at the 600 Hz ceiling it glides 0.425 s). */
    static final float GLIDE_MAX_S = 0.5f;

    /** What {@link #armWireAim} did, for the lane's glide / snap census
     *  ({@code BeamPresentationLane}: {@code beam_term_glides} / {@code beam_term_snaps}; a flight cannot otherwise
     *  tell a live glide from one that snaps every change). FRESH = the first arm after idle or a disarm (no history);
     *  HELD = the terminus did not change; neither is counted. SNAP = a change that applies raw at once (a gap: the
     *  stride unknown, a tick not later than the last change's, or more than {@link #GAP_FACTOR} strides after it).
     *  GLIDE = a change that kept the glide pair — one whose single stride is past {@link #GLIDE_MAX_S} also reports
     *  GLIDE, and {@link #armedAim} draws it raw by collapsing the pair at its next read. */
    static final int ARM_FRESH = 0;
    static final int ARM_HELD = 1;
    static final int ARM_SNAP = 2;
    static final int ARM_GLIDE = 3;

    private final AutofireAIPlugin delegate;
    private final Vector2f wireAimPrev = new Vector2f();
    private final Vector2f wireAim = new Vector2f();
    private final Vector2f lerpAim = new Vector2f();
    /** The endpoint discarded at the last pair rotation (meter envelope only —
     *  never fed to the engine; the consumed aim stays the {@link #armedAim} pair lerp). */
    private final Vector2f wireAimDropped = new Vector2f();
    private int armTicks;
    private int droppedTicks;
    /** The glide's clock: the ENGINE's own unpaused elapsed time,
     *  READ LIVE ({@link #engineClockS}). Not a clock grown in {@link #advance}: the engine calls that only
     *  for a group whose auto-fire is ON (the engine's {@code WeaponGroup.advanceAuto}), so on a replica group that
     *  is OFF the glide never moved and the aim sat one record behind (lens fb F1). Not a clock our
     *  combat plugin feeds: it runs AFTER the ships' weapon groups in one engine frame
     *  ({@code CombatEngine.advanceInner}), so the engine's {@code getTarget()} read would lag a frame on
     *  an ON group. The engine grows this clock at the TOP of each unpaused frame, before any
     *  ship advances, so it is current for both readers (the engine's {@code getTarget()} and
     *  {@code BeamPresentationLane.maintainBeamAim}); paused frames do not grow it. VALUE-DOMAIN: seconds,
     *  the engine's FLOAT widened to double, non-decreasing within one combat engine, NaN with no engine;
     *  its step is ≈ 0.24 ms at 1 h and ≈ 2 ms at 8 h, so the glide fraction is quantized by step / glide
     *  (a bound, not a defect). The engine grows it as a FLOAT accumulator
     *  ({@code += amount} on a float field, in {@code CombatEngine.advance}), so every frame's add rounds to the
     *  clock's step (ulp): a bias of up to ulp/2 per frame, of one sign at a steady frame time dt, so f runs fast or
     *  slow by up to ulp / (2·dt) of itself (at 8 h and 60 fps the clock runs 5.5 % fast). Once dt < ulp/2 the add
     *  rounds away and the clock stops: f holds 0, the aim sits on the previous terminus — from 2^19 s (≈ 146 h) at
     *  60 fps, 2^17 s (≈ 36 h) at 240 fps. */
    private final DoubleSupplier clock;
    /** {@link #clock} read at the last terminus change (or the fresh arm); NaN if no engine was there. */
    private double changeS;
    /** The carrying frame's server tick at
     *  the last terminus change (or at the fresh arm): the gap test's reference, the shim's only tick state
     *  (the stride is the server's, read live from {@link ClientWireKnobs}). VALUE-DOMAIN: a signed 64-bit
     *  frame tick, non-negative and rising within a round, able to step backwards at a round wrap. */
    private long changeTick;
    /** The weapon's keep-target flag before {@link #installKeepTarget}; kept so a
     *  future uninstall sweep restores exactly (no such sweep exists today — see class doc). */
    private boolean priorKeepTarget;

    WireTermAutofireShim(AutofireAIPlugin delegate) {
        this(delegate, WireTermAutofireShim::engineClockS);
    }

    /** The rows' seam: the glide clock injected (the product
     *  constructor passes {@link #engineClockS}). */
    WireTermAutofireShim(AutofireAIPlugin delegate, DoubleSupplier clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    /** The engine's unpaused elapsed seconds
     *  ({@code getTotalElapsedTime(false)}, the clock {@code ArmorReconcileLane} also reads); NaN with no
     *  combat engine, which {@link #armedAim} reads as a snap. */
    static double engineClockS() {
        CombatEngineAPI e = Global.getCombatEngine();
        return e == null ? Double.NaN : e.getTotalElapsedTime(false);
    }

    /** Close the engine's chargedown aim-null for this shim's tenure.
     *  Pure {@link WeaponAPI}, B-legal; prior captured for exact restore. */
    void installKeepTarget(WeaponAPI weapon) {
        priorKeepTarget = weapon.isKeepBeamTargetWhileChargingDown();
        weapon.setKeepBeamTargetWhileChargingDown(true);
    }

    /**
     * Arm with this render frame's wire
     * terminus. {@code snapTick} = the carrying frame's tick, {@code ServerSnapshotFrame.simTick()}
     * (signed 64-bit), non-negative and rising within a round, repeated on every render frame until the
     * next snapshot lands, and able to step BACKWARDS at a round wrap ({@code Long.MIN_VALUE} is the
     * plugin's "no frame yet" field value, never a frame's tick). A change glides ONE served stride
     * ({@link ClientWireKnobs#snapStrideTicksValue}, a u8 {@code 0..255}, {@code 0} = unknown) unless it
     * is a gap: the stride is unknown, the change is not at a later tick than the previous change, or it
     * came more than {@link #GAP_FACTOR} strides after it — then it snaps. Every value is safe: the
     * product of the factor and a u8 stride is computed in long, and a backward step reads as a gap.
     * Returns the branch taken — {@link #ARM_FRESH}, {@link #ARM_HELD},
     * {@link #ARM_SNAP} or {@link #ARM_GLIDE} — the lane's census input; the decisions themselves are unchanged.
     */
    int armWireAim(float x, float y, long snapTick) {
        int code = ARM_HELD;
        if (armTicks <= 0) {
            // fresh arm after idle/lane-death: no history — snap
            wireAimPrev.set(x, y);
            wireAim.set(x, y);
            changeS = clock.getAsDouble();
            changeTick = snapTick;
            droppedTicks = 0; // intervening frames were fallback — the meter's armed gate owns them
            code = ARM_FRESH;
        } else if (x != wireAim.x || y != wireAim.y) {
            int stride = ClientWireKnobs.snapStrideTicksValue();
            long dChange = snapTick - changeTick;
            boolean gap = stride <= 0 || dChange <= 0 || dChange > (long) GAP_FACTOR * stride;
            changeTick = snapTick;
            if (gap) {
                // The snap drops curr (the endpoint the consumed aim sat at, or was gliding to)
                wireAimDropped.set(wireAim);
                wireAimPrev.set(x, y); // pause then move, a loss run, or no known stride: snap
                code = ARM_SNAP;
            } else {
                wireAimDropped.set(wireAimPrev);
                wireAimPrev.set(wireAim);
                code = ARM_GLIDE;
            }
            droppedTicks = DROPPED_GRACE_TICKS;
            wireAim.set(x, y);
            changeS = clock.getAsDouble();
        }
        armTicks = ARM_GRACE_TICKS;
        return code;
    }

    void disarmWireAim() {
        armTicks = 0;
    }

    /** Attribution read for the over-residual split: whether the wire aim
     *  is live THIS tick (an over-frame while armed = the spec-ignores-aim class; while
     *  disarmed = the record-gap/fallback class). */
    boolean isArmed() {
        return armTicks > 0;
    }

    /** The interpolated aim the engine consumes while armed: lerp(prev, curr,
     *  min(1, since / glide)). Glide = ONE served snapshot stride,
     *  {@link ClientWireKnobs#snapStrideSeconds} (the delivered ticks over the WELCOME's rate, both read on
     *  every call — the rate re-seeds on every admit), never the spacing this shim saw.
     * since = {@link #clock} read on THIS call minus {@link #changeS}, never a value cached at
     *  an arm, so the engine's {@code getTarget()} on an auto-fire-ON group reads its own frame's clock;
     *  a NaN or negative since (no engine, another engine, a clock stepped backwards) reads f = 1 — a
     *  broken clock snaps to the newest terminus, never holds the aim a record behind. Fail-closed to the
     *  newest terminus when the stride is unknown (glide 0) or one stride exceeds {@link #GLIDE_MAX_S}. Also the
     *  point {@code maintainBeamAim} snaps the mount angle from, so bearing and length sweep together.
     *  A glide of 0 also COLLAPSES the pair onto the
     *  newest terminus. The stride is read live, so without the collapse a stride zeroed by a re-admission and landing
     *  again inside one glide would drop f back below 1 and step the drawn point BACK toward the older terminus. The
     *  meter envelope's dropped end point ({@code wireAimDropped}) is not touched.
     *  Returns a reused buffer — read-and-drop, never retain. */
    Vector2f armedAim() {
        float glide = ClientWireKnobs.snapStrideSeconds();
        if (glide > GLIDE_MAX_S) {
            glide = 0f;
        }
        if (!(glide > 0f)) {
            wireAimPrev.set(wireAim);
        }
        double since = clock.getAsDouble() - changeS;
        float f = glide > 0f ? (since >= 0.0 ? (float) Math.min(1.0, since / glide) : 1f) : 1f;
        lerpAim.set(wireAimPrev.x + (wireAim.x - wireAimPrev.x) * f,
                wireAimPrev.y + (wireAim.y - wireAimPrev.y) * f);
        return lerpAim;
    }

    /** The never-lengthen ENVELOPE the armed aim is convex-bounded by: the larger of
     *  the endpoint distances from the muzzle, all endpoints server-authored termini.
     *  Lerp-phase-proof reference for the gate meter ({@code beam_term_aim_over}): the engine
     *  consumed some lerp of these endpoints at every tick since the last arm, so a live beam
     *  longer than this bound means the engine IGNORED the fed aim. For
     *  {@link #DROPPED_GRACE_TICKS} after a pair rotation the endpoint the rotation dropped is
     *  still included — a ray metered right after the rotation was swept under the previous
     *  pair's lerp (the endpoint race), and grading it against the new pair alone
     *  false-positives on fast closers. */
    float armedEnvelopeDist(float mx, float my) {
        float dpx = wireAimPrev.x - mx;
        float dpy = wireAimPrev.y - my;
        float dcx = wireAim.x - mx;
        float dcy = wireAim.y - my;
        float max = Math.max(dpx * dpx + dpy * dpy, dcx * dcx + dcy * dcy);
        if (droppedTicks > 0) {
            float ddx = wireAimDropped.x - mx;
            float ddy = wireAimDropped.y - my;
            max = Math.max(max, ddx * ddx + ddy * ddy);
        }
        return (float) Math.sqrt(max);
    }

    @Override
    public void advance(float amount) {
        if (armTicks > 0) {
            armTicks--;
        }
        if (droppedTicks > 0) {
            droppedTicks--;
        }
        delegate.advance(amount); // native AI stays warm — idle pose/threat pick is its job
    }

    @Override
    public Vector2f getTarget() {
        return armTicks > 0 ? armedAim() : delegate.getTarget();
    }

    @Override
    public boolean shouldFire() {
        return delegate.shouldFire(); // firing authority is the relay's, never the shim's
    }

    @Override
    public void forceOff() {
        delegate.forceOff();
    }

    @Override
    public ShipAPI getTargetShip() {
        return delegate.getTargetShip();
    }

    @Override
    public WeaponAPI getWeapon() {
        return delegate.getWeapon();
    }

    @Override
    public MissileAPI getTargetMissile() {
        return delegate.getTargetMissile();
    }
}
