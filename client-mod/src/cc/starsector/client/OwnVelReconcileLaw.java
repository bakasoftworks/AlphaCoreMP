package cc.starsector.client;

/**
 * The AUTHORITY-GATED OWN-VELOCITY REBASE-AND-HOLD law (design adjudicated
 * between options: this law, option (a), CHOSEN; collision-impulse replication REFUTED-as-fix, accept-and-tighten
 * REJECTED). Pure and game-free (no engine imports, no logging) so the offline harness
 * ({@code client-mod/verify-ownvel}) drives
 * exactly the law the plugin ships — the {@link VelocityGlideLaw}/{@link BurstWindowLaw}
 * extraction pattern. State lives HERE (the lane-side new-lane-law shape);
 * {@code PredictReconcileCombatPlugin} keeps the knob statics, the pose feed, the meters and
 * the velocity writes.
 *
 * <p><b>What it fixes.</b> Per-side NATIVE-COLLISION
 * outcome divergence: each engine resolves ship-ship/hulk contact against ITS OWN foe poses,
 * so in dense melee the twin own-ships take different impulses (episode A: one-tick −129.5 u/s
 * ram on one side only; episode B: sustained overlap contact pinning the server twin 208→37.7
 * u/s while the client held 232–295 under IDENTICAL controls + jets state). The velocity gap
 * re-grows the position residual until the 300-su hatch snap (felt teleport #1) and the
 * burst-end haul-back (felt teleport #2).
 *
 * <p><b>The hole this closes.</b> Inside a burst window every own-velocity backstop
 * disarms at once: burstExempt suppresses STEP-16, controls-held + gap&gt;40 closes
 * {@link VelocityGlideLaw}, the item-④ escape streak freezes, and the position glide drops to
 * the weak tether — a collision-driven gap grows unopposed. This law's gate reads the
 * LOCAL-vs-AUTH <i>gap</i> (not the auth track's own step and not the control mask), so it
 * threads the jets-fighting cure and this hole at once.
 *
 * <p><b>Gate calibration (all measured).</b> Engage requires the fresh-frame gap
 * {@code g = |v_local − v_auth|} above {@link #gate} for {@link #ENGAGE_FRESH_FRAMES}
 * consecutive fresh frames. The gate is {@code max(GATE_FLOOR, dTicks × AUTH_STEP_PER_TICK ×
 * GATE_STALENESS_SCALE)}: {@code AUTH_STEP_PER_TICK} 12.5 = the p99 per-tick
 * thruster+jets authority (window median 1.71); {@code dTicks} = the fresh-frame
 * inter-snapshot delta — the staleness the client can MEASURE (cadence + loss/jitter gaps;
 * legit lead ≈ staleness × per-tick authority, so a starved feed raises the gate exactly when
 * legit lead can grow). The one-way transit term is not client-measurable today; the 80 u/s
 * floor carries it — the legit burst-class max latch measured 68 u/s @56 ms ping (an accepted
 * feel) vs the defect ≥95 u/s at its onset's FIRST sample. If a
 * higher-ping test ever shows legit engagement, {@link #GATE_STALENESS_SCALE} is the lever.
 *
 * <p><b>Action (the capped-slew write policy).</b> While engaged
 * — INCLUDING the engage frame — each fresh frame the caller writes toward {@code v_auth} by
 * {@link #slewStep}: {@code Δv = min(gap, max(SLEW_STEP_FLOOR_U, SLEW_RATE_PER_S2 × dt))}.
 * This RETIRED the one-shot {@code v_local ← v_auth} rebase and the 56.9/s exponential hold
 * (both authored the felt micro-snaps): every write sits under the 68 u/s accepted
 * felt bound by construction, the worst measured engage (233 u/s) spreads over ~4
 * writes ≈ 200 ms (the design intent), and in-hold
 * tracking is EXACT at fresh frames once the gap is inside one step. Release when the gap
 * stays under {@code gate × }{@link #RELEASE_GATE_FRAC} for {@link #RELEASE_HOLD_TICKS} sim
 * ticks (hysteresis — a contact era re-widening the gap re-arms the hold instead of flapping).
 *
 * <p><b>Fly-in ARM latch (with a traveldrive
 * hold belt; its v2 is the pose-adopted fly-in hold).</b> The
 * law catches divergence-FROM-convergence, so from construction and after every {@link #reset} a
 * life must witness {@link #ARM_FRESH_FRAMES} CONSECUTIVE fresh frames with {@code gap ≤ gate}
 * before engage COUNTING starts — kills the fly-in engage class (traveldrive +600 u/s)
 * and waits out post-adopt divergence eras. The first single-frame witness armed TRIVIALLY
 * at the spawn instant (ARMED simTick=1 gap=0); the bare 3-frame streak
 * then armed on the early burn ramp anyway (ARM simTick=9 — at fresh-per-tick
 * cadence a streak is 50 ms, FALSIFIED LIVE), so streak depth is the wrong axis: the
 * fallback belt holds the latch on the engine's fly-in signal instead — while the
 * caller's {@link #flyInHold} read is true, a frame neither counts toward the streak nor arms,
 * and the streak RESETS (a pre-burn partial streak cannot survive the burn). The belt read
 * the LOCAL drive alone ({@code getTravelDrive().isOn()}) and was BLIND to pose-adopted own
 * fly-ins (two recorded runs: the adopt seams kill or never light the local drive
 * — {@code turnOffTravelDriveAtAdopt} at initial adopt, a parked hull STEP-16-rebased onto the
 * server's launch at respawn — so the hull glides at 600-class with the drive reading OFF and
 * the belt INERT); {@link #flyInHold} therefore ORs the AUTH-side regime read: auth speed above
 * {@link #FLYIN_HOLD_AUTH_OVERMAX_FRAC} × the hull's own max-speed stat is a drive-raised glide
 * the local hull cannot lawfully track, whichever seam fielded it. Pre-arm eras keep
 * the whole pre-law backstop lattice (STEP-16/glide/escape/hatch all run while disengaged); a
 * never-converging life leaves the law off with the hatch as last resort (pre-law status quo).
 *
 * <p><b>Own position glide rate-cap (ENGAGED frames only).</b> While
 * engaged the caller's position glide is rate-capped via {@link #cappedGlideAlpha} so the
 * correction motion stays inside the vanilla ship-motion envelope ({@link #POS_SLEW_CAP_SU_S}:
 * 100 su ≈ 0.4 s; binds only past ~6 su at the full rate); disengaged frames glide at the
 * pre-law full exponential rate, so a small shield-ram correction snaps back. An interim fix had
 * extended the cap to ALL frames against the burst-END haul-back (residual banked at the
 * 0.5/s weak tether, hauled back in 2–4 render frames at window expiry) — in play that
 * all-frames cap read as the "smeary slow reconcile" of tiny rams, so
 * it is withdrawn; the 300-su hatch stays armed.
 *
 * <p><b>Scope pins.</b> Angular velocity + facing are OUT of v1 (own facing rides
 * the remoteAim lane server-side; the owner named POSITION teleports). Lawful under
 * the sync-gap doctrine's continuous-quantity carve-out (velocity masks no discrete event lane; the
 * carve-out's named example is dt-scaled ramming damage). Zero wire change: authoritative
 * {@code velX/velY} already ride the own AWSS pose.
 */
public final class OwnVelReconcileLaw {

    /**
     * Gate floor (u/s): above the measured legit burst-class max latch (68 u/s @56 ms ping, an
     * accepted feel), under the defect's onset first sample (≥95 u/s).
     */
    public static final float GATE_FLOOR = 80f;
    /** Per-tick thruster+jets authority (u/s per sim tick): the measured window p99 = 12.52. */
    public static final float AUTH_STEP_PER_TICK = 12.5f;
    /** Staleness multiplier on the authority-scaled term (legit lead ≈ staleness × authority; ×2 = margin). */
    public static final float GATE_STALENESS_SCALE = 2f;
    /** Consecutive over-gate FRESH frames required to engage (~3 snapshot periods ≈ 150 ms at cadence 3). */
    public static final int ENGAGE_FRESH_FRAMES = 3;
    /** Release fraction: disengage arming starts when the gap falls under {@code gate × this}. */
    public static final float RELEASE_GATE_FRAC = 0.25f;
    /** Sim ticks the gap must stay under the release fraction before disengage (~1 s at the 60 Hz fixed step). */
    public static final long RELEASE_HOLD_TICKS = 60;
    /**
     * Per-write velocity-slew floor (u/s): the perceptual quantity is the per-write velocity
     * discontinuity — 60 sits under the 68 u/s accepted felt bound, so every
     * write is sub-felt-class by construction. Named lever if still felt: 60 → 40.
     */
    public static final float SLEW_STEP_FLOOR_U = 60f;
    /**
     * Velocity-slew rate (u/s per second): consistent with the floor at nominal cadence
     * (1200 × 0.05 s = 60); the dt term takes over only on starved cadence, where
     * {@link #gate} is already ≥ 300 and a strong pull is right.
     */
    public static final float SLEW_RATE_PER_S2 = 1200f;
    /**
     * Own position-glide cap (su/s, ENGAGED own-glide frames; the interim all-frames
     * extension withdrawn): correction motion stays inside the vanilla ship-motion
     * envelope — under the ~300 u/s jets class the eye already tracks; dt-scaled so fps-safe
     * (fps 540 ⇒ 0.44 su/frame).
     */
    public static final float POS_SLEW_CAP_SU_S = 240f;
    /**
     * Consecutive converged FRESH frames required to ARM: the single-frame
     * witness armed at the spawn instant (gap=0 at simTick=1 PRE-burn) and let the
     * fly-in engage anyway; the streak outlasts that instant but NOT the burn ramp at
     * fresh-per-tick cadence — the {@code travelDriveOn} hold belt in
     * {@link #advance(float, float, float, float, long, boolean)} is what refuses the burn era
     * itself (class doc).
     */
    public static final int ARM_FRESH_FRAMES = 3;
    /**
     * Belt v2: the un-armed latch also holds while the AUTH speed exceeds this multiple of
     * the hull's own max-speed stat — the pose-adopted fly-in discriminator (class doc). Both
     * banked defect lives sat at ≥4× (auth 637 / grendel-class max ~60 ≈ 10×; auth 620 /
     * hyperion 150 ≈ 4.1×) while the lawful over-max envelope (0-flux boost + jets-class
     * systems) stays ≤ ~2×; 2.5 splits the bands with margin both ways. Named lever if a
     * legit-hold class ever presents: 2.5 → 3.
     */
    public static final float FLYIN_HOLD_AUTH_OVERMAX_FRAC = 2.5f;

    /** Consecutive over-gate fresh frames toward {@link #ENGAGE_FRESH_FRAMES} (disengaged only). */
    private int engageStreak;
    /** True while the hold owns the own-velocity write (exactly-one-owner). */
    private boolean engaged;
    /** Sim ticks the gap has stayed under the release fraction (engaged only). */
    private long releaseTicks;
    /** Engage counting stays refused until this life witnesses convergence. */
    private boolean armed;
    /** Consecutive converged fresh frames toward {@link #ARM_FRESH_FRAMES} (un-armed only). */
    private int armStreak;

    /**
     * The engage/release gate (u/s) for a fresh frame that arrived {@code dTicks} sim ticks
     * after the previous one (class doc: the measurable-staleness scaling; {@code dTicks}
     * floors at 1).
     */
    public static float gate(long dTicks) {
        return Math.max(GATE_FLOOR, Math.max(1L, dTicks) * AUTH_STEP_PER_TICK * GATE_STALENESS_SCALE);
    }

    /**
     * Belt v2 — the composite fly-in hold read, ONE source for the {@code advance} belt AND
     * the caller's settled/haul D-METER stratification (the pollution rider:
     * a pose-adopted glide must stratify out of "settled" exactly like a local burn). True while
     * the LOCAL drive burns ({@code driveOn} = the {@code getTravelDrive().isOn()} read) OR
     * the AUTH speed sits above {@link #FLYIN_HOLD_AUTH_OVERMAX_FRAC} × the hull's own max-speed
     * stat (the pose-adopted fly-in: the authority rides a drive-raised regime the local hull
     * cannot lawfully hold, whichever adopt/rebase seam fielded it — class doc). Fail-soft: a
     * non-finite/non-positive {@code maxSpeedU} (unreadable stat) or non-finite auth components
     * degrade to the drive read alone (= the original belt's behavior).
     */
    public static boolean flyInHold(float authVx, float authVy, float maxSpeedU, boolean driveOn) {
        if (driveOn) {
            return true;
        }
        if (!(maxSpeedU > 0f) || !Float.isFinite(maxSpeedU)
                || !Float.isFinite(authVx) || !Float.isFinite(authVy)) {
            return false;
        }
        float authSpeedSq = authVx * authVx + authVy * authVy;
        float bound = FLYIN_HOLD_AUTH_OVERMAX_FRAC * maxSpeedU;
        return authSpeedSq > bound * bound;
    }

    /**
     * Harness/legacy convenience: {@link #advance(float, float, float, float, long, boolean)}
     * with no fly-in hold — normal-flight rows drive this form; the shipping caller passes
     * the composed {@link #flyInHold} read.
     */
    public boolean advance(float localVx, float localVy, float authVx, float authVy, long dTicks) {
        return advance(localVx, localVy, authVx, authVy, dTicks, false);
    }

    /**
     * Advance one FRESH own-pose frame (class doc). {@code flyInHold} = the caller's composed
     * fly-in read ({@link #flyInHold}: local drive ON or auth-over-max — belt v2; the original
     * form passed the bare drive read): while true and un-armed,
     * the belt refuses the frame and resets the ARM streak (an armed life is
     * unaffected — the belt targets the fly-in ARM class only). Returns {@code true} exactly on
     * the ENGAGE edge — the caller takes the capped-slew write that frame, then holds while
     * {@link #engaged()} stays true. Non-finite inputs fail closed (full reset): a torn pose
     * must never latch a hold.
     */
    public boolean advance(float localVx, float localVy, float authVx, float authVy, long dTicks,
            boolean flyInHold) {
        if (!Float.isFinite(localVx) || !Float.isFinite(localVy)
                || !Float.isFinite(authVx) || !Float.isFinite(authVy)) {
            reset();
            return false;
        }
        if (!armed && flyInHold) {
            // The fallback belt (a live run FALSIFIED the bare streak; its
            // v2 widened the read to pose-adopted glides): the fly-in era's frames never
            // witness convergence, and a pre-burn partial streak dies with the burn — arming
            // resumes from zero once the hold releases.
            armStreak = 0;
            return false;
        }
        float gapX = authVx - localVx;
        float gapY = authVy - localVy;
        float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY);
        float gate = gate(dTicks);
        if (!armed) {
            // The fly-in ARM latch: a life must witness convergence on
            // ARM_FRESH_FRAMES CONSECUTIVE fresh frames before engage counting starts —
            // divergence-FROM-convergence only; the spawn-instant gap=0 frame no longer arms.
            if (gap <= gate) {
                armStreak++;
                if (armStreak >= ARM_FRESH_FRAMES) {
                    armed = true;
                }
            } else {
                armStreak = 0;
            }
            return false;
        }
        if (!engaged) {
            if (gap > gate) {
                engageStreak++;
            } else {
                engageStreak = 0;
            }
            if (engageStreak >= ENGAGE_FRESH_FRAMES) {
                engaged = true;
                engageStreak = 0;
                releaseTicks = 0L;
                return true;
            }
            return false;
        }
        if (gap < gate * RELEASE_GATE_FRAC) {
            // Stale-resume clamp (the VelocityGlideLaw#escapeTicks idiom): one frame advances
            // at most half the threshold, so a feed-gap tick jump cannot one-shot a release
            // on a condition that was unmeasured while the feed was down.
            releaseTicks += Math.max(1L, Math.min(dTicks, RELEASE_HOLD_TICKS / 2));
            if (releaseTicks >= RELEASE_HOLD_TICKS) {
                engaged = false;
                releaseTicks = 0L;
            }
        } else {
            releaseTicks = 0L;
        }
        return false;
    }

    /** True while the hold owns the own-velocity write (the caller's standdown + tether-override read). */
    public boolean engaged() {
        return engaged;
    }

    /** True once this life has witnessed convergence — the caller's ARM-edge log read. */
    public boolean armed() {
        return armed;
    }

    /**
     * The engaged per-write velocity step (u/s) toward the authoritative velocity for a fresh
     * frame that arrived {@code dtSeconds} after the previous one: never exceeds the
     * gap (a write lands EXACTLY on {@code v_auth} once the gap is inside one step), never
     * under the sub-felt floor. A non-positive/non-finite gap writes nothing (fail-closed).
     */
    public static float slewStep(float gap, float dtSeconds) {
        if (!(gap > 0f)) {
            return 0f;
        }
        return Math.min(gap, Math.max(SLEW_STEP_FLOOR_U, SLEW_RATE_PER_S2 * dtSeconds));
    }

    /**
     * The own position-glide alpha (ENGAGED own-glide frames): the caller's
     * tether alpha, rate-capped so the glide moves at most {@link #POS_SLEW_CAP_SU_S} su per
     * second against the residual it is closing. A non-positive/non-finite residual or dt leaves
     * the alpha uncapped (nothing measured to slew against — the caller's residual sample is
     * fail-soft).
     */
    public static float cappedGlideAlpha(float alpha, float residSu, float dtSeconds) {
        if (!(residSu > 0f) || !(dtSeconds > 0f)) {
            return alpha;
        }
        return Math.min(alpha, POS_SLEW_CAP_SU_S * dtSeconds / residSu);
    }

    /**
     * Full reset (round reset, own respawn, mount adopt — wherever the sibling step-rebase/glide
     * baselines re-arm): a discontinuous pose stream ends any engage episode with the life, and
     * the ARM latch re-latches — the new life must witness convergence again.
     */
    public void reset() {
        engaged = false;
        engageStreak = 0;
        releaseTicks = 0L;
        armed = false;
        armStreak = 0;
    }
}
