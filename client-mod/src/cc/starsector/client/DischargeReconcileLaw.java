package cc.starsector.client;

/**
 * The OWN-discharge RECONCILE-TREATMENT law (fire-lane step 4; frozen design),
 * pure and game-free so the offline contract battery
 * ({@code client-mod/verify-dischargereconcile}, driven by its own runner script)
 * exercises exactly the law the plugin ships —
 * the OwnFireLockLaw extraction pattern. Knob {@code cc.predict.dischargeReconcile} =
 * {@code off|meter|apply}; caller = {@code PredictReconcileCombatPlugin} (treatment queues at
 * the edge-event drain site + the {@code maintainDischargeReconcile} early-retire pass), own
 * ship only.
 *
 * <p><b>What it corrects.</b> The pairing meters honestly COUNT unpaired own discharges
 * (server_only / client_only) but nothing ever resolved them visually: a server_only bolt the
 * client never predicted stays invisible (a miss class seen live), and a client_only
 * bolt the server never confirmed flies (and visually hits) forever. Step 4 turns each expiry
 * into a treatment: server_only ⇒ ADOPT (spawn the authoritative bolt via the presenter's
 * faithful path, own role), client_only ⇒ RETIRE (fade the unconfirmed bolt via the engine's
 * own reap/fizzle primitives), plus a faster ammo-delta EARLY retire for discrete ordnance.
 *
 * <p><b>Why each guard:</b>
 * <ul>
 *   <li><b>Retire class gate:</b> coverage-split emission truth: on an
 *       UNCOVERED mount (no projectile spec — the poll-detector source) the server collapses
 *       signals — one cooldown-rise per fire CYCLE, one ammo event per (mount,tick), adjacent-
 *       tick same-shot collapse — so burst weapons (devastator 1:12) and linked multi-barrel
 *       mounts (1:N at {@code getBurstSize()==1}) legitimately under-emit there. A COVERED mount
 *       post-S2 ({@code OnFireTruthMerger}) emits every confessed birth, adjacent-tick burst
 *       internals included — only the same-tick multiSpawn collapse still holds for it. The gate
 *       BEHAVIOR stays as-is for both classes (a per-coverage narrowing is a separate MEASURED
 *       change — routed as residue, never folded into a comment fix); retiring a burst/linked
 *       mount's extra real bolts would fade live ordnance.
 *       Gate: {@code !isBeam() && getBurstSize() <= 1 && !multiSpawn}
 *       ({@code multiSpawn} = another local discharge recorded at the same (mount, recordTick) —
 *       the zero-content-knowledge linked-barrel closure; stale-snapshot tick-collapse
 *       false-positives fail BENIGN = skip). Beams never appear in getProjectiles() as
 *       discharge-paired entries, but the gate stays explicit.</li>
 *   <li><b>Retire primitives are the engine's own:</b> {@code removeEntity} = the deferred,
 *       silent pending-remove queue (the engine's expired-reap path — no explosion/FX/damage);
 *       missiles instead take {@code MissileAPI.flameOut()} = the engine's OWN fizzle
 *       (flame-out + fade, no warhead) — the faithful torpedo-fade grammar. A bolt that already
 *       HIT is health≤0 ⇒ engine-reaped ⇒ {@code isEntityInPlay} false ⇒ SKIP_GONE by
 *       construction: expiry treatments only ever touch still-flying bolts.</li>
 *   <li><b>Ammo-delta early retire is auth-vs-auth ⇒ ownFireLock-immune:</b> both compare
 *       operands are the wire's own mount ammo (record-time value from the PRIOR-fresh-frame
 *       cache vs the current fresh frame) — never a locally-written value. The one fatal
 *       hazard is the AWSD hold-last window (a stale mount ammo repeated at a fresh snapshot
 *       tick reads as "no decrement" ⇒ FALSE early retire of a real torpedo), hence
 *       {@code exactAmmoLane} (float-exact AWSS decode lane only, fail-closed false).
 *       Regenerating launchers ({@code getAmmoPerSecond() > 0}) are excluded at eligibility —
 *       regen could mask a real decrement. Errs missed-over-false ALWAYS: any ambiguity
 *       (mount absent, usesAmmo flip, ammo above record, non-exact lane) DISQUALIFIES the
 *       entry down to the 1 s pairing path.</li>
 *   <li><b>False-late self-heal accounting:</b> a retired (tick, mount) that a later ADOPT
 *       matches within the pairing tolerance means the faded bolt was real and the treatments
 *       self-healed (the adopt respawned it) — counted {@code retire_false_late}, never
 *       silent.</li>
 * </ul>
 */
public final class DischargeReconcileLaw {

    // --- retire (client_only expiry) action codes ---
    /** Class gate failed (beam / burst>1 / multiSpawn sibling) — the 1:N under-emit classes. */
    public static final int RETIRE_SKIP_CLASS = 0;
    /** Fade via the engine's silent deferred reap ({@code CombatEngineAPI.removeEntity}). */
    public static final int RETIRE_REMOVE = 1;
    /** Missile: the engine's own fizzle ({@code MissileAPI.flameOut()} — no warhead, fades). */
    public static final int RETIRE_FLAMEOUT = 2;
    /** Ref no longer in play (already hit ⇒ engine-reaped, or swept) — nothing to fade. */
    public static final int RETIRE_SKIP_GONE = 3;

    // --- ammo-delta early-retire (discrete ordnance) action codes ---
    /** Keep waiting — auth clock not advanced far enough to certify "no decrement" yet. */
    public static final int EARLY_WAIT = 0;
    /** Auth ammo unchanged after ≥ {@link #earlyRetireMinTicks} — the shot never landed server-side. */
    public static final int EARLY_RETIRE = 1;
    /** Auth ammo BELOW record — the shot landed server-side; drop eligibility (pairing/1 s decides). */
    public static final int EARLY_DROP_LANDED = 2;
    /** Ambiguity (non-exact lane / mount absent / usesAmmo flip / ammo above record) — 1 s path only. */
    public static final int EARLY_DISQUALIFY = 3;

    // --- adopt (server_only expiry) action codes ---
    /** Spawn the authoritative bolt via the presenter's faithful own-role path. */
    public static final int ADOPT = 1;
    /** No live own body to spawn from (post-kill window) — counted, never faked. */
    public static final int ADOPT_SKIP_UNRESOLVED = 0;

    /**
     * FROZEN (s4): the pairing tolerance the treatments inherit — mirrors the plugin's
     * {@code DISCHARGE_MATCH_TOL_TICKS} (≈1 s; 60 at the 60 Hz default,
     * tickrate-scaled). Duplicated here so the law stays game-free; the contract battery
     * asserts the halving relation below. De-static: a per-call read of
     * {@link ServerTickRate#ticksPerSecond()} — the WELCOME-seeded rate (which can land AFTER
     * this class loads) is honored no matter the class-load order; the value at any given
     * rate is unchanged.
     */
    public static long matchTolTicks() {
        return Math.round(1.0 * ServerTickRate.ticksPerSecond());
    }

    /**
     * FROZEN (s4): auth-clock advance required before "ammo unchanged" certifies a miss —
     * {@code DISCHARGE_MATCH_TOL_TICKS / 2} (30 at the 60 Hz default, tickrate-scales).
     */
    public static long earlyRetireMinTicks() {
        return matchTolTicks() / 2;
    }

    /** FROZEN (s4): retired-(tick,mount) accounting ring capacity (drop-oldest). */
    public static final int RETIRED_RING_CAP = 64;

    /**
     * The server detector's discharge-emission epsilon, mirrored
     * game-free (the {@code MATCH_TOL_TICKS} duplication idiom; source of truth:
     * {@code cc.starsector.server.runtime.GameplayEdgeEventDetector.COOLDOWN_RISE_EPSILON}).
     * The detector confirms a discharge ONLY via a {@code cooldownRemaining} rise above this,
     * or an ammo decrement — a no-ammo weapon whose refire delay sits at/below it (irpulse:
     * chargedown=0, charge-ramp refire) NEVER emits, so its real server-fired bolts are
     * structurally unconfirmable and must never be retire-adjudicated.
     */
    public static final float SERVER_EMIT_COOLDOWN_EPSILON_SEC = 1e-3f;

    /**
     * verdict-at-coverage grace floor: the shortest honest adjudication wait
     * (8 ticks at the 60 Hz default, tickrate-scaled) — below this the grace
     * would race healthy same-frame pairing (agree Δ measured ±1 snapshot).
     */
    public static long graceFloorTicks() {
        return Math.max(1L, Math.round(8.0 * ServerTickRate.ticksPerSecond() / 60.0));
    }

    /**
     * The snapshot pad inside the grace: one applied-snapshot stride plus
     * reorder margin (2 ticks at the 60 Hz default, tickrate-scaled). GPEE rides the reliable
     * in-order CTRL lane decoupled from latest-wins SNAP, so after a drop an event lands
     * ~RTT/RTO AFTER applied coverage passed its tick — the grace must cover that lag.
     */
    public static long graceSnapPadTicks() {
        return Math.max(1L, Math.round(2.0 * ServerTickRate.ticksPerSecond() / 60.0));
    }

    private DischargeReconcileLaw() {
    }

    /** The static half of the retire class gate (weapon-shape; {@code multiSpawn} is the dynamic half). */
    public static boolean retireClassOk(boolean beam, int burstSize) {
        return !beam && burstSize <= 1;
    }

    /**
     * The full static class gate: the shape half above AND the
     * server-confirmability half ({@link #subEpsilonClass}). A weapon the server detector
     * structurally cannot confirm (no ammo signal, refire at/below the emission epsilon)
     * class-skips retirement — before this gate, irpulse-class REAL bolts escaped hard
     * retirement only via skip_gone (the 1 s wait let short-lived bolts resolve first); the
     * F1 shorter grace would have made the hole bite.
     */
    public static boolean retireClassOk(boolean beam, int burstSize, boolean usesAmmo,
            float cooldownSec) {
        return retireClassOk(beam, burstSize) && !subEpsilonClass(usesAmmo, cooldownSec);
    }

    /**
     * True when the server detector structurally cannot see this weapon's discharges: no ammo
     * decrement (not {@code usesAmmo}) AND no visible cooldown re-arm (refire delay at/below
     * {@link #SERVER_EMIT_COOLDOWN_EPSILON_SEC} — the irpulse chargedown-0 class). NaN/unreadable
     * cooldown lands sub-epsilon: errs missed-over-false (skip, never retire-adjudicate).
     */
    public static boolean subEpsilonClass(boolean usesAmmo, float cooldownSec) {
        return !usesAmmo && !(cooldownSec > SERVER_EMIT_COOLDOWN_EPSILON_SEC);
    }

    /**
     * The verdict-at-coverage adjudication grace, in sim ticks: with a live RTT
     * measurement ({@code netPingMillis()} — NaN when unmeasurable), {@code 2×RTT + }
     * {@link #graceSnapPadTicks} clamped to
     * [{@link #graceFloorTicks}, {@link #matchTolTicks}]; an unmeasurable/invalid RTT falls
     * back to the full legacy tolerance (fail-open — never adjudicate faster than the lane can
     * prove events arrive). The 60-tick expiry semantics survive as the cap: a pending entry
     * never waits LONGER than before this fix.
     */
    public static long adjudicationGraceTicks(double pingMillis) {
        long tol = matchTolTicks();
        if (!Double.isFinite(pingMillis) || pingMillis < 0.0d) {
            return tol;
        }
        long rttTicks = (long) Math.ceil(
                2.0d * pingMillis / 1000.0d * ServerTickRate.ticksPerSecond());
        long grace = rttTicks + graceSnapPadTicks();
        long floor = graceFloorTicks();
        if (grace < floor) {
            grace = floor;
        }
        if (grace > tol) {
            grace = tol;
        }
        return grace;
    }

    /**
     * The RETIRE decision for one expired client_only entry (class doc). Gate order fixes the
     * meter semantics: class (incl. multiSpawn) → gone → primitive by projectile family.
     */
    public static int retireAction(boolean classOk, boolean multiSpawn, boolean inPlay, boolean missile) {
        if (!classOk || multiSpawn) {
            return RETIRE_SKIP_CLASS;
        }
        if (!inPlay) {
            return RETIRE_SKIP_GONE;
        }
        return missile ? RETIRE_FLAMEOUT : RETIRE_REMOVE;
    }

    /**
     * Record-time ammo-delta eligibility (class doc): mount identity resolved, ammo-backed on
     * BOTH sides, non-regenerating, retire-class-clean, and a PRIOR-fresh-frame auth ammo
     * actually in hand ({@code cacheHit} + non-negative). Anything else is born on the 1 s
     * path — eligibility is never granted later.
     */
    public static boolean ammoEligibleAtRecord(int mount, boolean carriedUsesAmmo,
            boolean localUsesAmmo, float ammoPerSecond, boolean classOk,
            boolean cacheHit, int authAmmoAtRecord) {
        return mount >= 0 && carriedUsesAmmo && localUsesAmmo && ammoPerSecond <= 0f
                && classOk && cacheHit && authAmmoAtRecord >= 0;
    }

    /**
     * The per-fresh-frame EARLY-RETIRE decision for one still-eligible entry (class doc).
     * Errs missed-over-false: every ambiguous edge lands in {@link #EARLY_DISQUALIFY} (the
     * 1 s pairing path), never in {@link #EARLY_RETIRE}.
     */
    public static int earlyRetireAction(boolean exactAmmoLane,
            boolean mountCarried, boolean carriedUsesAmmo,
            long authTickAtRecord, long authTickNow,
            int authAmmoAtRecord, int authAmmoNow) {
        if (!exactAmmoLane || !mountCarried || !carriedUsesAmmo
                || authAmmoNow < 0 || authAmmoNow > authAmmoAtRecord) {
            return EARLY_DISQUALIFY;
        }
        if (authAmmoNow < authAmmoAtRecord) {
            return EARLY_DROP_LANDED;
        }
        if (authTickNow - authTickAtRecord < earlyRetireMinTicks()) {
            return EARLY_WAIT;
        }
        return EARLY_RETIRE;
    }

    /** The ADOPT decision for one expired server_only entry (the presenter owns mount resolve). */
    public static int adoptAction(boolean shooterResolvable) {
        return shooterResolvable ? ADOPT : ADOPT_SKIP_UNRESOLVED;
    }

    /** The catch-up age an adopted spawn presents at ({@code ≥ 0} by expiry construction). */
    public static long adoptAgeTicks(long snapshotTick, long eventTick) {
        return Math.max(0L, snapshotTick - eventTick);
    }

    /**
     * True when a later ADOPT matches a recently-RETIRED (tick, mount) within the pairing
     * tolerance — the {@code retire_false_late} self-heal accounting (class doc). Mount gate
     * mirrors the pairing: equal, or either side the −1 pair-any sentinel.
     */
    public static boolean falseLateMatch(long retiredTick, int retiredMount,
            long eventTick, int eventMount) {
        if (Math.abs(eventTick - retiredTick) > matchTolTicks()) {
            return false;
        }
        return retiredMount < 0 || eventMount < 0 || retiredMount == eventMount;
    }

    /**
     * The MIRROR-direction dedup class: true when a LOCAL discharge recorded
     * at (recordTick, recordMount) is the late twin of a recently-ADOPTED server event. The
     * adopt consumed the auth entry at coverage+grace BEFORE the local twin recorded, so
     * pairing can never see the pair — without this class one skewed server-first pair counts
     * as adopt AND removed (the double-present candidate). An echo-class entry
     * counts {@code retire_adopt_echo} at adjudication (dedup — excluded from the graded
     * true-divergence numerator); under apply the local twin is removed (the adopted spawn
     * already presents this server shot). Tolerance + mount gate = {@link #falseLateMatch}
     * exactly (same tick-skew physics, opposite arrival order).
     */
    public static boolean adoptEchoClass(long adoptedEventTick, int adoptedMount,
            long recordTick, int recordMount) {
        return falseLateMatch(adoptedEventTick, adoptedMount, recordTick, recordMount);
    }

    /**
     * The per-coverage narrowing the retire-class-gate
     * doc above routed as residue. {@link #subEpsilonClass} models the POLL detector's blindness;
     * a COVERED mount (projectile spec ⇒ post-S2 {@code OnFireTruthMerger} birth truth) emits EVERY
     * discharge regardless of refire (one measured run: 403 server irpulse emits on entity 1 while 197
     * client_only irpulse bolts flew full phantom flights under the class skip). Because the poll
     * path never emits this class, ANY authoritative discharge on a sub-eps mount proves the
     * covered path ⇒ that mount is fully confirmable ⇒ its unconfirmed local bolts are genuine
     * over-prediction and retire like any class-ok bolt (the shape half still applies). A mount
     * never confirmed stays skip-class: errs missed-over-false. Only the phantom's FLIGHT is
     * reconciled — {@code client_only} keeps counting, {@code retire_false_late} stays the net.
     */
    public static boolean subEpsConfirmable(boolean subEpsClass, boolean shapeOk,
            boolean mountAuthConfirmed) {
        return subEpsClass && shapeOk && mountAuthConfirmed;
    }
}
