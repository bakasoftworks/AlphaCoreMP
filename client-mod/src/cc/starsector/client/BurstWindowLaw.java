package cc.starsector.client;

/**
 * The OWN-SYSTEM BURST-WINDOW EXEMPTION law, measured on a live run:
 * pure and game-free so the offline replay harness
 * can replay exactly the law the plugin ships — the {@link VelocityGlideLaw}/ClockServoLaw
 * extraction pattern.
 *
 * <p><b>What it exempts, and why.</b> The felt "own-ship fights its lunge" defect (Orion
 * Drive) was measured NOT to be the 300-su snap hatch (never
 * fired; resid max 67) but STEP-16 itself: on a sustained authoritative velocity ramp the
 * per-tick auth step stays above the 5 u/s contact threshold EVERY fresh frame, so the
 * "one-shot" re-base storms (369 fires, 32+ consecutive), yanking the local velocity onto the
 * ~3-tick-stale auth track mid-burst — plus the full-rate position glide slaving the visual
 * pose to that same stale track. Both sims run the SAME script during an own-system burst
 * (the server simulates our own intents); local converges naturally and auth catches up in
 * ~16 ticks, so inside the window the corrections only fight the native engine.
 *
 * <p><b>The window.</b> The report-only latch, promoted: local
 * {@code own.getSystem().isActive()} re-arms expiry to {@code tick + TAIL_TICKS} each fresh
 * frame; the tail covers the post-ACTIVE overspeed decay (hull decel per-tick step falls
 * under STEP-16's 5 u/s threshold well inside 2 s). In-window ({@link #live}): (a) STEP-16
 * velocity overwrites are SUPPRESSED (detector + baseline + meters keep running); (b) the
 * item-④ glide-escape streak FREEZES (holds, neither advances nor fires); (c) the
 * own position glide drops to {@link #TETHER_RATE_PER_SEC} (weak tether). The 300-su snap
 * hatch stays ARMED throughout — a true fork still snaps ({@code reconcilePosition} keeps its
 * own distance branch). Outside the window every lane is byte-identical to before; the
 * parked-auth ESCAPE class (all 4 specimens OUTSIDE windows) stays fully guarded —
 * a global loosen was REFUSED (never-conceal-desync).
 *
 * <p><b>Vanilla bound (sync-gap):</b> the exemption restores the native lunge inside the
 * named symptom's window only; no above-vanilla scope.
 */
public final class BurstWindowLaw {

    /**
     * Sim ticks the window holds past the last locally-active sample (≈2 s at the 60 Hz fixed
     * step) — the tail, unchanged at promotion: {@code isActive()} spans
     * IN+ACTIVE+OUT, and the post-ACTIVE overspeed decays into cooldown inside the tail.
     */
    public static final long TAIL_TICKS = 120;
    /**
     * The in-window position-glide rate per second (weak tether; {@code alpha = 1-exp(-rate*dt)},
     * the proven frame-rate-independent form). Sized ~1/100 of {@code OWN_RECONCILE_RATE}
     * (56.9/s): worst-case in-window drag ≈ rate × resid ≈ 0.5 × 67 ≈ 34 u/s ≈ 5% of the
     * measured 619 u/s lunge peak — a bounded divergence brake, not a track-slave. Window-end:
     * the normal rate resumes ⇒ the ≤67-su residual glides closed smoothly, no snap (67 &lt; 300).
     * WATCH-tunable pending the verify arm.
     */
    public static final float TETHER_RATE_PER_SEC = 0.5f;

    private BurstWindowLaw() {
    }

    /**
     * Advance the latch one FRESH frame (the DD5 arithmetic, verbatim): a locally-active own
     * system re-arms the expiry; a backwards sim clock (round restart) self-heals to expired.
     * Returns the new {@code untilTick}.
     */
    public static long advance(boolean systemActive, long tickNow, long prevUntilTick) {
        long until = prevUntilTick;
        if (systemActive) {
            until = tickNow + TAIL_TICKS;
        }
        if (tickNow + TAIL_TICKS < until) {
            until = Long.MIN_VALUE; // sim clock went backwards (round restart)
        }
        return until;
    }

    /** True while the burst window (active + tail) is live at {@code tickNow}. */
    public static boolean live(long tickNow, long untilTick) {
        return tickNow <= untilTick;
    }
}
