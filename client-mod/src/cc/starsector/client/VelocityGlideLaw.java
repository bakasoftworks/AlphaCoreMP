package cc.starsector.client;

/**
 * The DRIFT-GATED OWN-SHIP VELOCITY GLIDE law,
 * pure and game-free so the offline replay harness ({@code client-mod/verify-glide})
 * replays exactly the law the plugin ships —
 * the ClockServoLaw extraction pattern.
 *
 * <p><b>What it corrects.</b> The own-ship reconcile is deliberately POSITION-only glide
 * (zero-RTT native control keeps velocity+facing local), so a residual velocity
 * error formed by latency-skewed thrust transients PERSISTS while drifting: both engines hold
 * velocity constant, the authoritative per-tick velocity step is ~0, and STEP-16's contact
 * detector (which watches the AUTH velocity's own step, not the local-vs-auth gap) can never fire
 * — measured as a frozen ~6.4 u/s vector gap over a full 30 s coast window, HUD-numbers-only
 * (~0.11 u steady-state position cost, ~100x under the 12 u budget).
 *
 *
 * <p><b>The gate — cannot fight player input by construction.</b> A glide step is applied only
 * when BOTH hold:
 * <ul>
 *   <li>{@code (heldControls & ~VELOCITY_NEUTRAL_MASK) == 0} — every VELOCITY-AFFECTING intent bit
 *       (thrust/strafe/decelerate) is idle, so no player thrust is live locally.
 *       Three intent classes are now EXEMPT — two MEASURED velocity-neutral, one engine-proven
 *       static (the vent bit, {@link #VENT_BIT}). Pure ROTATION (the
 *       turn bits — A/D or shift-aim, both land in the same engine-controller booleans) is
 *       EXEMPT: turning never writes velocity, so gliding under it fights nothing — measured on
 *       both recordings (deep turn-coast authStep max literally 0 to four decimals over n=13 TD1 + n=58 T1
 *       turn-only exchanges, with real rotation ~0.3 deg/tick).
 *       AND the ship-system bit 7 (Mudskipper Mk II = Maneuvering Jets): jets-while-
 *       movement-idle is velocity-neutral (deepSysCoast authStep 0 to four decimals over n=106 on the contact-
 *       light fixture) -- the QUIET guard owns any real jets
 *       impulse, so exempting the held bit fights nothing. Drifting-while-aiming is the most common
 *       coast pattern, so the rotation exemption is most of the live coverage (+28% TD1).
 *       AND the flux-VENT bit 8 ({@link #VENT_BIT}): a WINDOW-length state flag,
 *       not a held thrust — counting it velocity-affecting closed the gate for entire multi-second
 *       vent windows, freezing any vent-start velocity error until vent-end (observed live);
 *       venting writes no velocity at engine grade and real thrust during a vent
 *       still lands in bits 0&ndash;6 (constant doc); and</li>
 *   <li>the authoritative per-tick velocity step is QUIET ({@code <=} {@link #QUIET_AUTH_STEP_PER_TICK})
 *       — the server's copy is coasting too, so the latency tail of a just-released thrust (the
 *       server still integrating late intents) and every STEP-16 contact regime keep the gate
 *       CLOSED. {@code NaN} (no baseline yet) fails the comparison, so the gate starts closed.</li>
 * </ul>
 *
 * <p><b>The cap — and its persistence ESCAPE.</b> A gap above {@link #MAX_GAP}
 * is not this corrective's case (in-play HUD gaps measured p99=24, max ~29 u/s): it is either the
 * deployment DRIVE-IN (local enters the map at ~601 u/s vs the server's already-placed ~41 —
 * gliding that would visibly brake the drive-in) or a prediction fork owned by the SNAP/STEP-16
 * lanes. The law refuses to write and lets the position glide + snap hatch own it; once the
 * engine's own drive-in deceleration brings the gap under the cap, the glide engages normally.
 * But hands-off coasting keeps the auth copy QUIET, so STEP-16 (which watches the AUTH velocity's
 * own step) never fires and the snap hatch is POSITION-only — an IN-COAST velocity gap above the
 * cap had NO owner and parked there (90 u/s frozen for ~690 ticks after jets-boosted
 * strafe reversals). The escape: a streak of gate-open above-cap FRESH frames lasting
 * {@link #escapeThresholdTicks} on the sim-tick clock ({@link #escapeTicks}/{@link #escapeDue}) matures
 * into ONE snap-lane write — local velocity takes the authoritative value once, the STEP-16 shape.
 * One visible correction beats a minute of wrongness; the caller (plugin) owns the write.
 *
 * <p><b>The step.</b> Exponential, frame-rate-independent — the same {@code 1-exp(-rate*dt)}
 * time-constant form as the proven position reconcile, at a deliberately GENTLE rate: the frozen
 * 6.4 u/s gap closes to &lt;0.5 u/s in ~0.9 s, and the largest in-cap pull the law can impart is
 * {@code RATE*MAX_GAP = 120 u/s^2} for one frame, decaying immediately.
 */
public final class VelocityGlideLaw {

    /**
     * Authoritative per-tick velocity step (u/s per sim tick) above which the server is NOT
     * coasting and the gate stays closed. Pinned from the TD1 recording
     * (4136 exchanges, replayed by the harness): deep-drift exchanges (mask idle &ge; 60 ticks)
     * measured authStep p99.9 = 0 vs active-thrust steps up to ~3.5; STEP-16's contact threshold
     * is 5. 0.5 sits an order of magnitude above drift noise and well under thrust/contact.
     */
    public static final float QUIET_AUTH_STEP_PER_TICK = 0.5f;
    /**
     * The intent bits the gate IGNORES (class doc): the semantic turn bits
     * ({@code BIT_TURN_LEFT}=3, {@code BIT_TURN_RIGHT}=4 in {@code PredictReconcileCombatPlugin}).
     * Semantics confirmed from the recorded bytes, not the docs: exclusive-single-bit exchanges
     * show authStep mean 0.05&ndash;0.14 on bits 3/4 vs 0.5&ndash;1.2 on every thrust/strafe bit.
     */
    public static final int TURN_ONLY_MASK = (1 << 3) | (1 << 4);
    /**
     * The ship-system ACTIVATION bit ({@code BIT_USE_SYSTEM}=7 in {@code PredictReconcileCombatPlugin};
     * Mudskipper Mk II = Maneuvering Jets). EXEMPT from the gate alongside the turn bits: a fixture run measured
     * jets-while-movement-idle to be velocity-neutral (deepSysCoast authStep 0 to four decimals, n=106 contact-light
     * fixture); the independent QUIET guard owns any real jets impulse.
     */
    public static final int SYSTEM_BIT = 1 << 7;
    /**
     * The flux-VENT bit ({@code BIT_VENT_FLUX}=8 in {@code PredictReconcileCombatPlugin}) — a
     * WINDOW-length STATE flag (set from {@code FluxTrackerAPI.isVenting()} for the entire
     * multi-second vent), not a held thrust intent. EXEMPT (in a
     * live run, counting it velocity-affecting froze any vent-start velocity error
     * for the whole window). Engine-grade basis (static RE, 0.98a-RC8):
     * venting writes NO velocity — the flux tracker ({@code entities.ship.D}) never touches it,
     * {@code notifyVentingStarted()} is animation/travel-drive/retreat only, and
     * {@code canUseEngines()} returns {@code true} unconditionally ({@code Ship.advance} gates
     * {@code engineController.processCommands} on it), so REAL thrust during a vent still lands in
     * the controller booleans → bits 0&ndash;6 close the gate. Any engine-authored velocity change
     * mid-vent (contact) is owned by the independent QUIET guard, exactly as for turn/system.
     */
    public static final int VENT_BIT = 1 << 8;
    /**
     * The shield bit ({@code BIT_SHIELD_ON}=10 in {@code PredictReconcileCombatPlugin}) — a
     * held STATE mirror of {@code ShieldAPI.isOn()}, not a thrust intent. EXEMPT since
     * 2026-07-12: counting it velocity-affecting closed the
     * glide gate for every whole shield-hold window (the exact vent-bit class).
     * Engine-grade basis (static RE, 0.98a-RC8): raising/holding a
     * shield writes NO velocity — the shield state machine
     * is a pure desired-state/fader tracker, and the shield system ({@code combat.systems.G})
     * only READS ship velocity (two reads feed the activation/deactivation FX emitter; a third reads
     * angular velocity for pivot rate). Real thrust while shielded still lands in bits 0&ndash;6
     * and closes the gate; any engine-authored velocity change is owned by the QUIET guard.
     * Bit 9 (RETREATING) stays NOT exempt: the retreat travel drive moves the hull.
     */
    public static final int SHIELD_BIT = 1 << 10;
    /**
     * The phase-cloak bit ({@code BIT_PHASE_CLOAK}=11 in {@code PredictReconcileCombatPlugin}) — a
     * held STATE mirror of the cloak's {@code isOn()} (IN|ACTIVE), not a thrust intent. EXEMPT at
     * mint (2026-07-17): counting it velocity-affecting would close the glide
     * gate for ENTIRE phase windows — the documented freeze class the vent
     * ({@link #VENT_BIT}) and shield ({@link #SHIELD_BIT}) state bits already exemplify. Under
     * phased 3× dynamics the independent QUIET guard ({@link #QUIET_AUTH_STEP_PER_TICK} vs 3×-scaled
     * auth steps) self-closes the gate whenever the hull is actually maneuvering, so the exemption
     * fights nothing; the residual — convergent undercorrection while phased-COASTING — is measured
     * at the Move-3 WATCH (glide/escape counters + a rubber-band feel item), not pre-tuned.
     * D-M3-4 pins ride here: NO threshold changes pre-measure (STEP_REBASE=5,
     * {@code QUIET_AUTH_STEP_PER_TICK}=0.5, {@code ESCAPE_TICKS}≈60, {@code MAX_GAP}=40 untouched);
     * and corrections are NEVER timeMult-scaled (the C6 double-count loop: server corrections
     * already reflect phase — the fix lane, if ever needed, is phase-aware threshold
     * normalization, not scaling).
     */
    public static final int PHASE_BIT = 1 << 11;
    /**
     * The intent bits the gate IGNORES: the turn bits ({@link #TURN_ONLY_MASK}), the ship-system bit
     * ({@link #SYSTEM_BIT}), the vent-window state bit ({@link #VENT_BIT}), the shield-hold state
     * bit ({@link #SHIELD_BIT}) and the phase-cloak state bit ({@link #PHASE_BIT}) — turn/system
     * measured velocity-neutral, vent/shield/phase engine-proven state flags + QUIET-guarded
     * (class doc). Thrust/strafe/decel are NOT here: they stay velocity-affecting and keep the
     * gate closed.
     */
    /**
     * The carrier fighter-mode bit ({@code BIT_PULL_BACK_FIGHTERS}=12) — a
     * held STATE mirror of {@code isPullBackFighters()}, not a thrust intent; EXEMPT like the
     * shield/phase state bits (a level that sits at true on every non-carrier hull would
     * otherwise close the glide gate for the whole match).
     */
    public static final int PULL_BACK_BIT = 1 << 12;
    public static final int VELOCITY_NEUTRAL_MASK =
            TURN_ONLY_MASK | SYSTEM_BIT | VENT_BIT | SHIELD_BIT | PHASE_BIT | PULL_BACK_BIT;
    /** Local-vs-auth velocity gap (u/s) above which the law refuses to write (class doc: drive-in / fork). */
    public static final float MAX_GAP = 40f;
    /** Glide rate per second ({@code alpha = 1-exp(-rate*dt)}): TC ~0.33 s, 95% of a gap closed in ~1 s. */
    public static final float RATE_PER_SEC = 3.0f;
    /**
     * The escape threshold — sim ticks an in-coast above-cap gap must PERSIST before the escape fires
     * (~1 s at the 60 Hz fixed step). Calibration: the stuck episode measured ≥690 ticks (the
     * gap parked at 90 u/s ~ticks 4410–5100), so K=60 cuts it 10x+ under; the deploy
     * drive-in transient (~2 s decay) cannot mature it live — the rejoin lane's adoptInitialWorld
     * hard-snap zeroes the gap on the FIRST snapshot before this lane ever runs, and the QUIET
     * gate holds the streak at 0 while the auth copy is itself still maneuvering.
     */
    public static long escapeThresholdTicks() {
        // ≈1 s (60 at default). De-static: per-call read so the
        // WELCOME-seeded rate is honored regardless of class-load order. Named distinctly from
        // the {@link #escapeTicks} streak-advance law this threshold matures.
        return Math.round(1.0 * ServerTickRate.ticksPerSecond());
    }

    private VelocityGlideLaw() {
    }

    /**
     * The drift gate (class doc): no velocity-affecting bit held (turn + system bits exempt) AND the
     * authoritative copy coasting. {@code NaN}/negative {@code authStepPerTick} = no baseline =
     * closed; the quiet condition also keeps the gate closed through the latency tail of a
     * just-released thrust (turn-only exchanges right after release measured authStep up to ~2.9).
     */
    public static boolean gateOpen(int heldControls, float authStepPerTick) {
        return (heldControls & ~VELOCITY_NEUTRAL_MASK) == 0
                && authStepPerTick >= 0f
                && authStepPerTick <= QUIET_AUTH_STEP_PER_TICK;
    }

    /**
     * The authoritative per-tick velocity step between two fresh snapshots — mirrors the STEP-16
     * detector arithmetic in {@code PredictReconcileCombatPlugin.maintainStepRebase} exactly
     * (the harness recomputes it from the recording; the plugin feeds the gate its live value).
     */
    public static float authStepPerTick(float prevVx, float prevVy, float vx, float vy, long dTicks) {
        float dvx = vx - prevVx;
        float dvy = vy - prevVy;
        return (float) Math.sqrt(dvx * dvx + dvy * dvy) / Math.max(1L, dTicks);
    }

    /**
     * One glide step: when the gate is open and the gap is finite, nonzero, and within
     * {@link #MAX_GAP}, write the glided local velocity into {@code out[0..1]} and return
     * {@code true}; otherwise return {@code false} with {@code out} untouched (no write — the
     * caller leaves the local engine's velocity alone).
     */
    public static boolean step(int heldControls, float authStepPerTick,
            float localVx, float localVy, float authVx, float authVy, float dt, float[] out) {
        if (!gateOpen(heldControls, authStepPerTick)) {
            return false;
        }
        if (!Float.isFinite(localVx) || !Float.isFinite(localVy)
                || !Float.isFinite(authVx) || !Float.isFinite(authVy)) {
            return false;
        }
        float gapX = authVx - localVx;
        float gapY = authVy - localVy;
        float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY);
        if (gap == 0f || gap > MAX_GAP) {
            return false;
        }
        float a = alpha(dt);
        out[0] = localVx + a * gapX;
        out[1] = localVy + a * gapY;
        return true;
    }

    /**
     * Advance the item-④ escape streak by one FRESH frame (class doc, "The cap — and its
     * persistence ESCAPE"): while the gate is open AND the finite local-vs-auth gap exceeds
     * {@link #MAX_GAP}, the streak grows by this frame's sim-tick delta; ANY break — a
     * velocity-affecting bit held, a loud auth step (NaN fails closed), or the gap back at/under
     * the cap — resets it to 0. A single frame advances at most {@code ESCAPE_TICKS/2} (and at
     * least 1, covering duplicate/rewound ticks), so a stale-feed resume's tick jump can never
     * one-shot-mature the escape on a condition that was unmeasured while the feed was down.
     */
    public static long escapeTicks(int heldControls, float authStepPerTick,
            float localVx, float localVy, float authVx, float authVy,
            long prevTicks, long dTicks) {
        if (!gateOpen(heldControls, authStepPerTick)) {
            return 0L;
        }
        if (!Float.isFinite(localVx) || !Float.isFinite(localVy)
                || !Float.isFinite(authVx) || !Float.isFinite(authVy)) {
            return 0L;
        }
        float gapX = authVx - localVx;
        float gapY = authVy - localVy;
        float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY);
        if (gap <= MAX_GAP) {
            return 0L;
        }
        long adv = Math.max(1L, Math.min(dTicks, escapeThresholdTicks() / 2));
        return Math.max(0L, prevTicks) + adv;
    }

    /** True when the escape streak has matured (class doc): the caller takes ONE snap-lane write. */
    public static boolean escapeDue(long aboveCapTicks) {
        return aboveCapTicks >= escapeThresholdTicks();
    }

    /** Frame-rate-independent glide factor — the proven reconcile form ({@code 1-exp(-rate*dt)}, clamped). */
    public static float alpha(float dt) {
        if (!(dt > 0f)) {
            return 0f;
        }
        float a = 1f - (float) Math.exp(-RATE_PER_SEC * dt);
        if (a < 0f) {
            return 0f;
        }
        return Math.min(a, 1f);
    }
}
