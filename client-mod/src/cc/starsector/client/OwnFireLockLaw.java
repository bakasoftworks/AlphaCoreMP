package cc.starsector.client;

/**
 * The OWN-ship FIRE-PHASE LOCK law (fire-lane step 3, the flux-primary
 * hardening), pure and game-free so the
 * offline contract battery ({@code client-mod/verify-ownfirelock}, driven)
 * exercises exactly the law the plugin ships — the
 * VelocityGlideLaw / ClockServoLaw extraction pattern. Knob {@code cc.predict.ownFireLock}
 * = {@code off|meter|apply}; caller = {@code PredictReconcileCombatPlugin.maintainOwnFireLock},
 * own ship only, FRESH snapshots only.
 *
 * <p><b>What it corrects.</b> The decl lane phase-locks only client-declared
 * manual/selected discharges; the two engines' own-ship flux/ammo/cooldown clocks otherwise
 * free-run, so autofire refire skew accumulates (server autofire discharges missing/late
 * client-side) and flux_gap plateaus at 27–34 % max in the
 * baselines. Locking the three fire-phase clocks to the authoritative carried values
 * collapses the skew at its source; step 4's reconcile treatments then make residual mispredictions
 * self-heal.
 *
 * <p><b>Why each guard:</b>
 * <ul>
 *   <li><b>Flux (primary):</b> tracker setters clamp ≥0 only; overload is a pure TIMER external
 *       writes can neither start nor clear; a vent ends ONLY at {@code curr <= 0}, so a mid-vent
 *       pin EXTENDS the vent — the one destructive flux-pin edge. Hence the mutual-normal-state
 *       gate: skip when the LOCAL tracker is venting/overloaded OR the carried
 *       {@code FLAG_VENTING}/{@code FLAG_OVERLOADED} is set (unknown carried flags fail closed).
 *       This mirrors the engine's own live-ship {@code setCurrFlux} precedent, which gates on
 *       {@code !isOverloadedOrVenting}. Write order is
 *       curr-then-hard, both clamped ({@code hard <= curr}).</li>
 *   <li><b>Ammo:</b> the engine's {@code setAmmo} does NOT clamp (a &gt;max write persists) and
 *       zeroes burst sub-shot timers at index ≥ newAmmo, so writes land only at QUIESCENT frames
 *       — {@code !isInBurst() && getBurstFireTimeRemaining() <= 0 && (getCooldownRemaining() > 0
 *       || !isFiring())} (idle or in-cooldown; never chargeup/firing/burst) — and are clamped to
 *       {@code [0, localMaxAmmo]} here. Reload accumulator untouched (v1 non-goal: divergence
 *       writes at quiescent frames re-converge via regen).</li>
 *   <li><b>Cooldown (one-way NUDGE):</b> the shared projectile tracker's
 *       cooldown setter takes its graceful timer-repositioning branch ONLY when
 *       already in cooldown state with {@code rem <= refireDelay}; its else branch ABORTS a live
 *       burst (zeroes counter + in-burst flag) — the defect. The guard set
 *       ({@code localCdRem > 0}, not in burst, target ≤ cooldown) guarantees the graceful branch
 *       BY CONSTRUCTION and mirrors the engine's own cooldown-reduce ship
 *       system. Only-SHORTEN is the sound direction under
 *       staleness: carried authCdRem overstates the server's true remaining by ≤RTT, so
 *       convergence is local phase ≤ auth (RTT-bounded), never ROF inflation — the engine
 *       re-imposes the full refireDelay on every real discharge regardless. Sub-{@link
 *       #FAST_MIN_COOLDOWN_SEC} weapons skip (vulcan class: carried cdRem is pure noise at
 *       RTT ≥ cooldown; their lock is flux + step-4 reconcile). {@link #cdEps} absorbs the AWSD
 *       u8 wire quantum (cooldown/255; the AWSS lane is float-exact so the floor dominates).
 *       Shorten magnitude is bounded by {@link #CD_MAX_PLAUSIBLE_SHORTEN_SEC}:
 *       beyond it the carried frame is stale poison and the law
 *       returns {@link #CD_SKIP_IMPLAUSIBLE}, counted LOUD by the caller.
 *       Beams: same law — their tracker's setter is a no-op outside chargedown (inherently
 *       safe); the PlasmaShot/AI-proxy facade's silent no-op is accepted.</li>
 *   <li><b>Kill-edge dark-gun settle ({@link #disableSettleActive}):</b> {@code respawnOwn}
 *       rebinds a FRESH hull at the SAME reserved-band wire id with no epoch guard, so a residual
 *       carried disabled=true bit on the reappear frame would darken healthy guns
 *       frame-1. For {@link #disableSettleTicks} after an own-hull epoch bump
 *       the weapon-state lane DEFERS the own-ship apply-DISABLE edge (counted, never silent);
 *       the repair edge stays live.</li>
 * </ul>
 *
 * <p>chargeLevel stays read-only presentation — no setter exists.
 */
public final class OwnFireLockLaw {

    // --- flux re-base (sub-lane 1) action codes ---
    /** Non-finite/absent inputs — no action, not counted as a state skip. */
    public static final int FLUX_NODATA = 0;
    /** Pin: write {@code out[0]} curr then {@code out[1]} hard ({@code NaN} = leave hard alone). */
    public static final int FLUX_PIN = 1;
    /** Mutual-normal-state gate failed (local or carried vent/overload; unknown carried flags fail closed). */
    public static final int FLUX_SKIP_STATE = 2;
    /** Agreeing frame — targets equal the local values exactly; zero actions by law. */
    public static final int FLUX_AGREE = 3;

    // --- ammo apply (sub-lane 2) action codes ---
    /** Carried {@code FLAG_USES_AMMO} and local {@code usesAmmo()} did not BOTH hold (or sentinel ammo). */
    public static final int AMMO_SKIP_FLAGS = 0;
    /** Write the clamped {@code out[0]} via {@code setAmmo}. */
    public static final int AMMO_WRITE = 1;
    /** Not quiescent (chargeup/firing/burst) — the mid-burst sub-shot-timer mutation edge. */
    public static final int AMMO_SKIP_BUSY = 2;
    /** Clamped target already equals local ammo — no-op suppressed. */
    public static final int AMMO_AGREE = 3;
    /**
     * Refund-UP held off: the mount has an un-reconciled pending own
     * local discharge, so the carried auth ammo predates that shot; refunding would re-arm the
     * gun every carried frame (reaper 31 client fires per 502 ms press vs server 0).
     * The refund lands at reconcile resolution — pairing/retire removes the pending entry and
     * the next carried frame writes. Downward writes (auth &lt; local: the server fired MORE)
     * still apply.
     */
    public static final int AMMO_HOLD_PENDING = 4;
    /**
     * Refund-UP held off by the mount RE-ARM LATCH: the pending entry is
     * gone (paired or retired) but server truth for the shot has not yet arrived at the MOUNT tier.
     * An ALTERNATING-group tube awaiting its rotation turn carries {@code cd=0} + unchanged ammo —
     * wire-indistinguishable from ready — so writes stay held until the latch
     * releases (GPEE-passed frame / press released + horizon / native-scale timeout). Downward
     * truth writes stay live throughout, exactly like {@link #AMMO_HOLD_PENDING}.
     */
    public static final int AMMO_HOLD_LATCHED = 5;
    /**
     * DOWN write held for OWED local shots: the
     * carried count already debits discharges the server's own events announced (unpaired
     * {@code pendingAuthDischarges} entries on this mount) that the local mount has not fired yet;
     * writing it pre-debits them (the "cooldown-tail pre-debit": 0 on a last round ⇒ no local
     * shot). Held iff {@code target < local && local − target ≤ owed} on ANY quiescent frame — the
     * cooldown-EXPIRY frame included: the lock runs BEFORE
     * the engine fires in the same frame, so at {@code cd == 0}, not yet firing, the write would
     * take the very round the engine is about to fire (the {@code cdRem > 0} conjunct let it:
     * 102 such writes over six rig legs). Cooldown is never touched.
     * A released trigger keeps the count high only until the owed entries expire
     * ({@code FireLaneCoordinator.expireDischargePairs}, the RTT-derived grace) — then the
     * ordinary write lands.
     */
    public static final int AMMO_HOLD_OWED = 6;

    // --- cooldown nudge (sub-lane 3) action codes ---
    /** Not in cooldown state ({@code cdRem <= 0}) — the graceful-branch precondition is unmet; no action. */
    public static final int CD_SKIP_IDLE = 0;
    /** Nudge: {@code setRemainingCooldownTo(out[0])} — graceful branch guaranteed by the guards. */
    public static final int CD_NUDGE = 1;
    /** In a burst (or burst fire time remains) — repositioning would abort it. */
    public static final int CD_SKIP_BUSY = 2;
    /** Fast-refire weapon ({@code cooldown < } {@link #FAST_MIN_COOLDOWN_SEC}) — carried cdRem is noise. */
    public static final int CD_SKIP_FAST = 3;
    /** Within EPS, auth ≥ local (only-shorten), or invalid auth — no action. */
    public static final int CD_AGREE = 4;
    /**
     * Would-nudge held off: the mount has an un-reconciled pending own
     * local discharge, so the carried auth cooldown predates that shot; shortening against it
     * re-arms the gun (the {@link #AMMO_HOLD_PENDING} twin — shorten-against-stale is the
     * poison). Skips/agrees keep their meanings; only the nudge itself is deferred.
     */
    public static final int CD_HOLD_PENDING = 5;
    /**
     * Would-nudge held off by the mount RE-ARM LATCH (the
     * {@link #AMMO_HOLD_LATCHED} twin): without the nudge hold a 15 s tube could never re-fire at
     * the 515 ms reconcile cadence — the loop needs BOTH writes. Skips/agrees keep their
     * meanings; only the nudge itself stays deferred until the latch releases.
     */
    public static final int CD_HOLD_LATCHED = 6;
    /**
     * would-shorten REFUSED: the EFFECTIVE magnitude
     * ({@code localCdRem − clamp(authCdRem, 0, cooldown)} — the post-clamp target, NOT the raw
     * requested gap: at {@code localCdRem > cooldown} the clamp amplifies past the request, the
     * d3 hole) exceeds {@link #CD_MAX_PLAUSIBLE_SHORTEN_SEC}, so the carried frame is
     * stale/inconsistent, not a correction (constant doc). The caller counts
     * and logs LOUD — this code firing IS the fire-lock stale-frame detector.
     */
    public static final int CD_SKIP_IMPLAUSIBLE = 8;
    /**
     * phase-edge cooldown RE-BASE: inside a phase-transition window the local cd
     * clock re-bases to the carried auth value in BOTH directions (phase time-dilation is applied
     * client-side at locally-predicted edges vs server edges, so per-dip cooldown-clock skew
     * accumulates past the ±1 s pairing tolerance — the 1271 s1 phase-edge autofire train storm,
     * cd_skips_fast 13668 vs 0). The window also bypasses {@link #FAST_MIN_COOLDOWN_SEC} (the
     * storm's guns are all sub-0.5 s — the skip is exactly what let the skew stand). Guards kept:
     * in-cooldown only ({@code localCdRem > 0} — the setter's graceful-branch precondition;
     * local-IDLE mounts stay untouchable, lengthening from idle is unsafe: the stray early shot
     * is F1's to adjudicate), not in burst, target clamped [0, cooldown], pending/latch holds
     * honored (shorten-or-rebase against a frame the local shot outran is the poison
     * regardless of window).
     */
    public static final int CD_PHASE_REBASE = 7;
    /**
     * D-PLASMAPHASE —
     * phase-window re-base REFUSED: the carried {@code authCdRem} is 0 while the server mount is
     * ARMING (wire firing flag raised or charge level nonzero). {@code getCooldownRemaining()} is
     * nonzero ONLY in the tracker's COOLDOWN state — IDLE, CHARGEUP and ACTIVE all read 0
     * — so a carried 0 beside an arming mount is a shot ABOUT TO
     * LAND, never a clock value. In a recorded match at tick 44057, the client's cloak edge led the
     * server's, its tracker had already counted the shot the phase gate swallowed (local 0.99 of
     * 1.01 in COOLDOWN) while the server was still charging the twin it fired at 44060 — the
     * re-base to 0 re-armed both plasma mounts ~1 s early (a ghost-burst hazard whenever fire is
     * held across the un-phase). Held instead: the F2 dilation re-base stays for nonzero-vs-nonzero
     * skews and for a 0 from an IDLE server mount (both ready ⇒ the press pairs).
     */
    public static final int CD_HOLD_AUTH_ARMING = 9;
    /**
     * Discriminator C —
     * the nudge/re-base is HELD when the carried {@code authCdRem} was sampled inside a BURST
     * GAP. In the engine {@code getCooldownRemaining()} is
     * {@code (1 - progress) * spec.refireDelay} whenever the tracker state is COOLDOWN — and the
     * gap between burst shots IS that state with the out-duration set to
     * {@code burstDelay}, so the wire sawtooths from ~refire
     * to ~0 once per gap: a number that is not a clock. The tell that separates the two, on
     * fields BOTH wires already carry: in a burst {@code getChargeLevel()} is {@code 1.0F}
     * exactly (the in-burst flag), while in a true cooldown it is
     * {@code 1 - progress} — the SAME fraction as {@code cdRem / cooldown}. Predicate:
     * {@code authCdRem > 0 && authChargeLevel >= 1 - 1.5/255 && authCdRem < cooldown - cdEps}.
     * A true cooldown's first tick has {@code progress >= dt / refire} so either clause 2 fails
     * (u8 253 at refire 2 s) or, at long refires, clause 3 does ({@code cdRem ≈ cooldown} inside
     * {@link #cdEps}); a 0.33 s gap at 60 t/s passes all three from its first tick. Beams and
     * ACTIVE read {@code cdRem = 0} (clause 1 fails) so {@link #CD_HOLD_AUTH_ARMING} is
     * untouched. 0 wire bytes, 0 server compute. Outranked by the holds.
     */
    public static final int CD_HOLD_AUTH_BURST = 10;
    /**
     * Phase-window
     * re-base REFUSED: the clamped target is LONGER than the local remaining on a MANUALLY fired
     * mount. A trigger-fired kit shot legitimately leads its server twin by the uplink lag, and
     * the carried value drained on the server's time multiplier, so copying it up erases the lead
     * and can delete the kit's twin of a server refire (kit 5333 → server 5337, the
     * re-base at 5338 pushed the kit's next shot to 5348). No write: the gun fires at its own
     * expiry and the pair holds through the held / declared lanes. An AUTOFIRE mount keeps the
     * both-direction {@link #CD_PHASE_REBASE} — its shots are never declared, so a run-ahead kit
     * clock there has no other correction (the storm class). Sits after every
     * hold and compares the CLAMPED target: a local remaining above the cooldown is still a
     * shorten. Removes a client-side delay.
     */
    public static final int CD_HOLD_PHASE_LENGTHEN = 11;

    // --- mount re-arm latch (fix 1) release codes ---
    /** Latch stays held — no release condition met this frame. */
    public static final int LATCH_HOLD = 0;
    /**
     * Truth passed: a carried FRESH frame with {@code frameTick > gpeeTick} arrived — that frame
     * was generated at-or-after the server's own fire on this mount, so its ammo/cd reflect it
     * (releasing AT the GPEE tick would re-open the re-tap race: the pre-fire frame still
     * carries {@code cd=0} and the nudge would falsely re-arm the tube).
     */
    public static final int LATCH_RELEASE_TRUTH = 1;
    // 2 was LATCH_RELEASE_PRESS — RETIRED: the fixed-1 s
    // fire-source-drop release could re-admit a slow ALTERNATING tube before server truth
    // (release + carried cd=0 frames + group-switch-back = the re-tap double, re-opened).
    // No scale fixes it: ≥1.5×cooldown from release never beats the timeout (release ≥ arm),
    // anything less lacks the rotation+RTT slop the freeze itself demanded — and rel_press=0
    // on the GREEN reference run (unexercised). The latch now releases at truth or timeout only;
    // the value 3 below is kept stable so cross-run log analysis can't conflate codes.
    /** Native-cooldown-scale timeout (fail-open): truth never arrived; resume carried writes. */
    public static final int LATCH_RELEASE_TIMEOUT = 3;

    /**
     * FROZEN: one pairing tolerance (1 s; 60 at the 60 Hz default,
     * tickrate-scaled; mirrors {@code DISCHARGE_MATCH_TOL_TICKS}, duplicated so this law stays
     * game-free — the DischargeReconcileLaw idiom). Since the A1 press-release retirement its one
     * remaining role is the {@link #latchTimeoutTicks} floor — the shortest honest hold for a
     * weapon whose native scale is unknowable.
     */
    public static long rearmPressHorizonTicks() {
        // De-static: per-call read so the WELCOME-seeded rate is honored
        // regardless of class-load order.
        return Math.round(1.0 * ServerTickRate.ticksPerSecond());
    }
    /**
     * FROZEN: the fail-open timeout scale — {@code 1.5 × getCooldown()}. The ALTERNATING
     * rotation bounds the server's own fire to ≤ (N−1)/N × chargedown &lt; 1 × cooldown after the
     * local shot (stagger = chargedown/4 observed); 1.5× covers that plus RTT/frame slop
     * while keeping a truth-less latch finite — worst case the old loop resumes at 1.5-cooldown
     * cadence (honestly counted), never a permanently dark gun.
     */
    public static final float REARM_TIMEOUT_COOLDOWN_SCALE = 1.5f;

    /**
     * FROZEN (s1): weapons with {@code getCooldown()} below this never take a cooldown nudge —
     * at RTT ≥ cooldown the carried remaining is stale noise; flux + step-4 reconcile own them.
     */
    public static final float FAST_MIN_COOLDOWN_SEC = 0.5f;
    /** FROZEN (s1): the EPS floor — below this a local-vs-auth cooldown gap is agreement. */
    public static final float CD_EPS_FLOOR_SEC = 0.1f;
    /**
     * The legacy one-way shorten's PLAUSIBILITY BOUND (seconds). A
     * genuine fire-phase skew between the two engines' clocks is bounded by the pairing
     * tolerance (1 s — beyond it the discharge pair would not even pair), and RTT staleness only
     * ever makes the carried value OVERSTATE the server's remaining, never understate it, so a
     * shorten request beyond this bound is a stale/inconsistent carried frame, not a correction.
     * One incident: a carried frame read {@code auth=0.0} for a 25 s Salamander one tick
     * after that same fire's missile linked from the same applied snapshot; the uncapped shorten
     * re-armed the mount ~25 s early ⇒ four phantom client fires, each a LOUD ghost-fade kill.
     * The law SKIPS, never clamps:
     * applying a capped slice of an implausible correction would still walk local toward the
     * poison value while the stale frame persists; truthful frames re-converge on their own.
     * {@link #CD_PHASE_REBASE} is deliberately NOT bound — phase time-dilation is the one lane
     * where a large re-base is the designed correction.
     */
    public static final float CD_MAX_PLAUSIBLE_SHORTEN_SEC = 1.0f;
    /** The AWSD per-mount u8 quantization step count ({@code cdRem} is carried as a fraction of cooldown). */
    public static final float WIRE_CD_QUANTA = 255f;
    /** Clause 2 of {@link #CD_HOLD_AUTH_BURST}: the in-burst charge level 1.0 minus 1.5 AWSD u8 quanta. */
    public static final float AUTH_BURST_CHARGE_MIN = 1f - 1.5f / WIRE_CD_QUANTA;
    /**
     * Sim ticks the own-ship apply-DISABLE edge stays deferred after an own-hull epoch bump
     * (respawn / match reset) — 120 at the 60 Hz default (the frozen s1 value), tickrate-scaled
     * like {@code VelocityGlideLaw.ESCAPE_TICKS}.
     */
    public static long disableSettleTicks() {
        return 2L * ServerTickRate.ticksPerSecond();
    }

    private OwnFireLockLaw() {
    }

    /**
     * Sub-lane 1 — the flux re-base decision (class doc). {@code out[0]} = curr target
     * ({@code clamp(authCurr, 0, localMax)}), {@code out[1]} = hard target
     * ({@code clamp(authHard, 0, currTarget)}, {@code NaN} when the frame carries no hard flux —
     * the caller leaves hard alone, the foe-flux shape). Returns a {@code FLUX_*} code;
     * {@code out} is written only on {@link #FLUX_PIN}.
     */
    public static int fluxAction(
            boolean localVenting, boolean localOverloaded,
            boolean authFlagsKnown, boolean authVenting, boolean authOverloaded,
            float localCurr, float localHard, float localMax,
            float authCurr, boolean authHardKnown, float authHard,
            float[] out) {
        if (!Float.isFinite(localCurr) || !Float.isFinite(localHard) || !Float.isFinite(localMax)
                || !Float.isFinite(authCurr) || authCurr < 0f || localMax < 0f
                || (authHardKnown && !Float.isFinite(authHard))) {
            return FLUX_NODATA;
        }
        if (!authFlagsKnown || localVenting || localOverloaded || authVenting || authOverloaded) {
            return FLUX_SKIP_STATE;
        }
        float currTarget = clamp(authCurr, 0f, localMax);
        float hardTarget = authHardKnown ? clamp(authHard, 0f, currTarget) : Float.NaN;
        if (localCurr == currTarget && (!authHardKnown || localHard == hardTarget)) {
            return FLUX_AGREE;
        }
        out[0] = currTarget;
        out[1] = hardTarget;
        return FLUX_PIN;
    }

    /**
     * Sub-lane 2's quiescence gate (class doc): idle OR in-cooldown; chargeup/firing/burst block.
     * {@code getCooldownRemaining() > 0} ⇔ in-cooldown-state on both functional weapon families
     * (s1 finding 2); {@code isFiring()} alone is unusable (true during cooldown) — it only
     * disambiguates the {@code cdRem == 0} states.
     */
    public static boolean ammoQuiescent(
            boolean inBurst, float burstFireTimeRemaining, float cooldownRemaining, boolean firing) {
        return !inBurst && burstFireTimeRemaining <= 0f && (cooldownRemaining > 0f || !firing);
    }

    /**
     * Sub-lane 2 — the ammo-apply decision (class doc). {@code out[0]} = the clamped write target
     * ({@code [0, localMaxAmmo]} — the engine's own setter does NOT clamp), written only on
     * {@link #AMMO_WRITE}; write only on real post-clamp divergence. Delegates to the 13-arg form
     * with no owed discharges — the decision before the owed hold, byte-identical.
     */
    public static int ammoAction(
            boolean carriedUsesAmmo, boolean localUsesAmmo,
            boolean inBurst, float burstFireTimeRemaining, float cooldownRemaining, boolean firing,
            int localAmmo, int localMaxAmmo, int authAmmo,
            boolean pendingLocalDischarge, boolean rearmLatched,
            int[] out) {
        return ammoAction(carriedUsesAmmo, localUsesAmmo, inBurst, burstFireTimeRemaining,
                cooldownRemaining, firing, localAmmo, localMaxAmmo, authAmmo,
                pendingLocalDischarge, rearmLatched, 0, out);
    }

    /**
     * The ammo decision with the burst-edge OWED hold
     * ({@link #AMMO_HOLD_OWED}); {@code owedAuthDischarges} = the caller's count of unpaired own
     * authoritative discharges on this mount (≥ 0; ≤ 0 never holds). Guard order: flags → busy →
     * agree → pending → latched → owed → write ({@code out[0]} only on {@link #AMMO_WRITE}).
     */
    public static int ammoAction(
            boolean carriedUsesAmmo, boolean localUsesAmmo,
            boolean inBurst, float burstFireTimeRemaining, float cooldownRemaining, boolean firing,
            int localAmmo, int localMaxAmmo, int authAmmo,
            boolean pendingLocalDischarge, boolean rearmLatched, int owedAuthDischarges,
            int[] out) {
        if (!carriedUsesAmmo || !localUsesAmmo || authAmmo < 0 || localMaxAmmo < 0) {
            return AMMO_SKIP_FLAGS;
        }
        if (!ammoQuiescent(inBurst, burstFireTimeRemaining, cooldownRemaining, firing)) {
            return AMMO_SKIP_BUSY;
        }
        int target = Math.max(0, Math.min(authAmmo, localMaxAmmo));
        if (target == localAmmo) {
            return AMMO_AGREE;
        }
        if (pendingLocalDischarge && target > localAmmo) {
            return AMMO_HOLD_PENDING; // refund-up deferred to reconcile resolution
        }
        if (rearmLatched && target > localAmmo) {
            return AMMO_HOLD_LATCHED; // Deferred past pending-clear until server truth
        }
        if (target < localAmmo && localAmmo - target <= owedAuthDischarges) {
            return AMMO_HOLD_OWED; // The down write would pre-debit an owed local shot
        }
        out[0] = target;
        return AMMO_WRITE;
    }

    /**
     * The caller's owed-walk prune (the ledger is walked only where {@link #AMMO_HOLD_OWED}
     * can fire) — a quiescent frame carrying a DOWN divergence. It lives HERE, on the hold's own
     * quiescence test, so the prune cannot drift from the law again
     * (the in-game prune had copied {@code cdRem > 0} where no game-free row reached, so a
     * law-only fix would have changed nothing). Contract rows o9–o11.
     */
    public static boolean owedWalkNeeded(
            boolean inBurst, float burstFireTimeRemaining, float cooldownRemaining, boolean firing,
            int localAmmo, int authAmmo) {
        return authAmmo < localAmmo
                && ammoQuiescent(inBurst, burstFireTimeRemaining, cooldownRemaining, firing);
    }

    /**
     * Sub-lane 3's agreement EPS: {@code max(}{@link #CD_EPS_FLOOR_SEC}{@code , 2 × cooldown/255)}
     * — twice the AWSD wire quantum (frozen s1). Applying the quantum term on the float-exact
     * AWSS lane too only widens agreement (fewer nudges — the safe direction), so the law needs
     * no decode-lane knowledge.
     */
    public static float cdEps(float localCooldown) {
        float quantum = localCooldown > 0f ? localCooldown / WIRE_CD_QUANTA : 0f;
        return Math.max(CD_EPS_FLOOR_SEC, 2f * quantum);
    }

    /**
     * Sub-lane 3 — the one-way cooldown-nudge decision (class doc). {@code out[0]} = the
     * {@code setRemainingCooldownTo} target ({@code clamp(authCdRem, 0, localCooldown)}), written
     * only on {@link #CD_NUDGE}. Guard order fixes the meter semantics: idle → busy → fast →
     * agree/nudge. Delegates to the 9-arg form with the phase-edge window closed — the
     * behavior before the phase-edge re-base, byte-identical.
     */
    public static int cooldownAction(
            float localCdRem, float localCooldown,
            boolean inBurst, float burstFireTimeRemaining,
            float authCdRem,
            boolean pendingLocalDischarge, boolean rearmLatched,
            float[] out) {
        return cooldownAction(localCdRem, localCooldown, inBurst, burstFireTimeRemaining,
                authCdRem, pendingLocalDischarge, rearmLatched, false, out);
    }

    /**
     * Sub-lane 3, phase-edge form — {@code phaseEdgeWindow} true opens the phase-transition
     * re-base branch ({@link #CD_PHASE_REBASE} doc): FAST_MIN bypass + BOTH-direction re-base to
     * the carried auth value, in-cooldown/not-busy/pending/latch guards all kept. With the window
     * closed the decision is byte-identical to the 8-arg law. Guard order: idle → busy → fast
     * (skip unless window) → invalid-auth AGREE → window branch (eps-agree → holds → re-base) →
     * legacy one-way lane (holds → plausibility bound → nudge).
     */
    public static int cooldownAction(
            float localCdRem, float localCooldown,
            boolean inBurst, float burstFireTimeRemaining,
            float authCdRem,
            boolean pendingLocalDischarge, boolean rearmLatched,
            boolean phaseEdgeWindow,
            float[] out) {
        return cooldownAction(localCdRem, localCooldown, inBurst, burstFireTimeRemaining,
                authCdRem, pendingLocalDischarge, rearmLatched, phaseEdgeWindow, false, out);
    }

    /**
     * Sub-lane 3 with the {@link #CD_HOLD_AUTH_ARMING} guard: {@code authArming} = the carried
     * mount's firing flag or a nonzero charge level (the same wire row as {@code authCdRem}). With
     * it false the decision is byte-identical to the 9-arg law.
     */
    public static int cooldownAction(
            float localCdRem, float localCooldown,
            boolean inBurst, float burstFireTimeRemaining,
            float authCdRem,
            boolean pendingLocalDischarge, boolean rearmLatched,
            boolean phaseEdgeWindow, boolean authArming,
            float[] out) {
        return cooldownAction(localCdRem, localCooldown, inBurst, burstFireTimeRemaining,
                authCdRem, pendingLocalDischarge, rearmLatched, phaseEdgeWindow, authArming,
                Float.NaN, out);
    }

    /** Clause set of {@link #CD_HOLD_AUTH_BURST}: the carried frame was sampled in a burst gap. */
    static boolean authBurstGap(float authCdRem, float authChargeLevel, float localCooldown) {
        return authCdRem > 0f
                && authChargeLevel >= AUTH_BURST_CHARGE_MIN
                && authCdRem < localCooldown - cdEps(localCooldown);
    }

    /**
     * The meter row laws of {@code OwnBurstPhaseMeter} (pure; the
     * standalone battery compiles this file alone). {@code authBurstRise}: the carried charge crossed
     * {@link #AUTH_BURST_CHARGE_MIN} UPWARD = the server's burst start; a steady in-burst 1.0 is no rise (one
     * row per burst, never per frame); NaN either side = unknown = false (a NaN prev is the seed, no row).
     * {@code fluxGateHeld}: vanilla autofire's flux gate, STRICT like the engine's own
     * ({@code cost > avail} refuses; {@code avail} = max − curr); NaN = false, never a fabricated gate.
     */
    public static boolean authBurstRise(float prevCharge, float curCharge) {
        return prevCharge < AUTH_BURST_CHARGE_MIN && curCharge >= AUTH_BURST_CHARGE_MIN;
    }

    public static boolean fluxGateHeld(float cost, float avail) {
        return cost > avail;
    }

    /**
     * Sub-lane 3 with the {@link #CD_HOLD_AUTH_BURST} guard (m4 discriminator C):
     * {@code authChargeLevel} = the carried mount's charge level from the SAME wire row as
     * {@code authCdRem} ({@code NaN} = unknown ⇒ the guard is off and the decision is
     * byte-identical to the 10-arg law). The burst hold sits after the holds and the
     * arming hold in both lanes, before any write.
     */
    public static int cooldownAction(
            float localCdRem, float localCooldown,
            boolean inBurst, float burstFireTimeRemaining,
            float authCdRem,
            boolean pendingLocalDischarge, boolean rearmLatched,
            boolean phaseEdgeWindow, boolean authArming,
            float authChargeLevel,
            float[] out) {
        return cooldownAction(localCdRem, localCooldown, inBurst, burstFireTimeRemaining,
                authCdRem, pendingLocalDischarge, rearmLatched, phaseEdgeWindow, authArming,
                authChargeLevel, false, out);
    }

    /**
     * Sub-lane 3 with the {@link #CD_HOLD_PHASE_LENGTHEN} refusal: {@code manualGroup}
     * = the mount's shots are trigger-fired input (the caller's complement of the declaration
     * lane's autofire skip). {@code false} ⇒ byte-identical to the 11-arg law.
     */
    public static int cooldownAction(
            float localCdRem, float localCooldown,
            boolean inBurst, float burstFireTimeRemaining,
            float authCdRem,
            boolean pendingLocalDischarge, boolean rearmLatched,
            boolean phaseEdgeWindow, boolean authArming,
            float authChargeLevel, boolean manualGroup,
            float[] out) {
        if (!(localCdRem > 0f)) {
            return CD_SKIP_IDLE;
        }
        if (inBurst || burstFireTimeRemaining > 0f) {
            return CD_SKIP_BUSY;
        }
        if (!(localCooldown >= FAST_MIN_COOLDOWN_SEC) && !phaseEdgeWindow) {
            return CD_SKIP_FAST;
        }
        if (!Float.isFinite(authCdRem) || authCdRem < 0f) {
            return CD_AGREE;
        }
        if (phaseEdgeWindow) {
            if (Math.abs(authCdRem - localCdRem) <= cdEps(localCooldown)) {
                return CD_AGREE;
            }
            if (pendingLocalDischarge) {
                return CD_HOLD_PENDING; // the pending holds outrank the window (stale-frame poison)
            }
            if (rearmLatched) {
                return CD_HOLD_LATCHED; // the latch hold likewise
            }
            if (authArming && !(authCdRem > 0f)) {
                return CD_HOLD_AUTH_ARMING; // D-PLASMAPHASE: a 0 from CHARGEUP/ACTIVE is not a clock
            }
            if (authBurstGap(authCdRem, authChargeLevel, localCooldown)) {
                return CD_HOLD_AUTH_BURST; // m4: a burst-gap sawtooth sample is not a clock either
            }
            float rebase = clamp(authCdRem, 0f, localCooldown);
            if (manualGroup && rebase > localCdRem) {
                return CD_HOLD_PHASE_LENGTHEN; // Never lengthen a trigger-fired gun
            }
            out[0] = rebase;
            return CD_PHASE_REBASE;
        }
        if (authCdRem < localCdRem - cdEps(localCooldown)) {
            if (pendingLocalDischarge) {
                return CD_HOLD_PENDING; // Never shorten against a frame the shot outran
            }
            if (rearmLatched) {
                return CD_HOLD_LATCHED; // Deferred past pending-clear until server truth
            }
            if (authBurstGap(authCdRem, authChargeLevel, localCooldown)) {
                return CD_HOLD_AUTH_BURST; // m4: the post-burst graceful nudge to a gap sample
            }
            float target = clamp(authCdRem, 0f, localCooldown);
            if (localCdRem - target > CD_MAX_PLAUSIBLE_SHORTEN_SEC) {
                // stale-frame poison, never a correction. Bounds the EFFECTIVE
                // shorten (post-clamp target): when localCdRem > localCooldown the clamp itself
                // amplifies the write past the requested gap (contract row d3 caught this).
                return CD_SKIP_IMPLAUSIBLE;
            }
            out[0] = target;
            return CD_NUDGE;
        }
        return CD_AGREE;
    }

    /**
     * The told-wait CUSHION of {@link #leadDrainTicks}, in server
     * ticks: the part of a server-told wait the kit KEEPS. A cushion, not a floor: after a drain the kit's
     * next declaration still reaches the server this many ticks BEFORE the server's gun readies, so this
     * much uplink jitter or cooldown mismatch does not move the server-authoritative shot.
     */
    public static final int LEAD_WAIT_KEEP_TICKS = 2;
    /** Deadband of {@link #leadDrainTicks}: a smaller excess over the cushion is not a carried lead. */
    public static final int LEAD_DRAIN_MIN_TICKS = 2;
    /**
     * The most ONE shot may be delayed, in milliseconds of served time (a larger lead drains over the
     * next pairs; bounds a wrong stamp). The bound BUILT is the
     * bound ASKED — up to about 0.15 s on a held-trigger refire; the earlier
     * constant (20 ticks) read 0.33 s at 60 Hz and 0.67 s on a 30 Hz serve. In ticks =
     * {@link #leadDrainCapTicks}.
     */
    public static final int LEAD_DRAIN_MAX_MS = 150;

    /** {@link #LEAD_DRAIN_MAX_MS} at the served rate, floored: 9 ticks at 60 Hz, 4 at 30 Hz ({@code tps <= 0} → 60). */
    public static int leadDrainCapTicks(int tps) {
        return (tps > 0 ? tps : 60) * LEAD_DRAIN_MAX_MS / 1000;
    }

    /**
     * The CAUSAL guard of the pair-seam drain: the local shot's tick
     * and the server's discharge tick are both server ticks, so a correct pair shows {@code lag =
     * transit + wait >= wait}. A told wait LONGER than the pair's whole lag belongs to another shot (a
     * high-ping mis-pair with the next local shot) and is never drained. It narrows that double drain,
     * it does not close it (a second drain still passes from a round trip of
     * about one cycle plus the wait).
     */
    public static boolean leadPairCausal(long pairLagTicks, int toldWaitTicks) {
        return toldWaitTicks >= 0 && pairLagTicks >= toldWaitTicks;
    }

    /**
     * The pair-seam LEAD DRAIN, the
     * narrower rule the phase-window design left unbuilt. A trigger-fired kit shot leads its server twin by
     * the transit, and the server serves a declaration when ITS gun is ready — so a kit cooldown that
     * ends before the server's (one started at a boosted rate that ends at a lower one: Ammo Feeder /
     * phase exits) makes each declaration arrive early and WAIT on the server, and that lead is carried
     * from volley to volley under a held trigger (pair lags 4 → 12 ticks, past the
     * foe-hull hold, so the hit was shown, taken back and shown again). {@code toldWaitTicks} is the
     * server's own count of that wait — the GPEE v16 declared wait (raw server ticks from the consumed
     * declaration's arrival to its discharge; a READY gun reads 0; {@code -1} = absent): wire truth,
     * never a clock or ping inference (an earlier ping-based excess is deleted).
     * Returns the ticks to lengthen the mount's running cooldown by, in this order:
     * absent ({@code < 0}) → 0;
     * a wait of at least half a cooldown ({@code 0.5 × cooldownSec × tps}, compared in WHOLE ticks: a
     * float cooldown such as 0.6f sits a hair above its decimal, and the exact-half wait must still
     * read implausible) or a non-finite cooldown → 0,
     * IMPLAUSIBLE — a wait near a whole cooldown is a wrong stamp (a late declaration behind a
     * server-first press, a second queued declaration), never a lead;
     * {@code excess = wait − }{@link #LEAD_WAIT_KEEP_TICKS} below {@link #LEAD_DRAIN_MIN_TICKS} → 0;
     * else {@code min(excess, }{@link #leadDrainCapTicks}{@code (tps))} — never more than
     * {@link #LEAD_DRAIN_MAX_MS} of delay on one shot.
     * The KEEP ticks are the cushion (see the constant): in steady state the wait sits near KEEP, inside
     * the deadband, and nothing is written. {@code tps <= 0} falls back to 60. Never a copy of the
     * server's clock — the phase-window design's mechanisms 1–3 stay true.
     */
    public static int leadDrainTicks(int toldWaitTicks, float cooldownSec, int tps) {
        if (toldWaitTicks < 0) {
            return 0;
        }
        int t = tps > 0 ? tps : 60;
        if (!Float.isFinite(cooldownSec) || toldWaitTicks >= Math.ceil(0.5d * cooldownSec * t - 1e-3d)) {
            return 0;
        }
        int excess = toldWaitTicks - LEAD_WAIT_KEEP_TICKS;
        return excess < LEAD_DRAIN_MIN_TICKS ? 0 : Math.min(excess, leadDrainCapTicks(t));
    }

    /**
     * The write half of {@link #leadDrainTicks} ({@code rate} added):
     * the lengthened remaining cooldown, or {@code NaN} = no write. Guards = the
     * cooldown law's graceful-branch set (in COOLDOWN, not in a burst: the {@link #CD_SKIP_IDLE} /
     * {@link #CD_SKIP_BUSY} preconditions of {@code setRemainingCooldownTo}) plus the fast-gun skip (a
     * sub-{@link #FAST_MIN_COOLDOWN_SEC} gun refires inside any lag and re-pairs ambiguously); a
     * non-finite or non-positive {@code rate} writes nothing. {@code rate} = the mount's cooldown-advance
     * rate (its weapon type's rate-of-fire multiplier × {@code min(1, ship time multiplier)}); one tick is
     * charged as {@code min(1, rate) / tps} s of cooldown: a rate below 1 is charged AT the rate (the
     * lengthen delays the next shot by exactly the drained ticks), while a BOOSTED gun (rate above 1) is
     * charged at 1 and so UNDER-corrected on purpose — the boost may end inside the cycle, and an
     * over-correction would make the server's gun wait for the declaration. The target never exceeds the
     * cooldown.
     */
    public static float leadDrainTarget(float localCdRem, float localCooldown,
            boolean inBurst, float burstFireTimeRemaining, int drainTicks, float rate, int tps) {
        if (drainTicks <= 0 || !(rate > 0f) || !Float.isFinite(rate) || !(localCdRem > 0f) || inBurst
                || burstFireTimeRemaining > 0f || !(localCooldown >= FAST_MIN_COOLDOWN_SEC)) {
            return Float.NaN;
        }
        float lengthen = drainTicks * Math.min(1f, rate) / (float) (tps > 0 ? tps : 60);
        float target = Math.min(localCooldown, localCdRem + lengthen);
        return target > localCdRem ? target : Float.NaN;
    }

    /**
     * Fix-1 — the mount re-arm latch's fail-open timeout in sim ticks:
     * {@code max(}{@link #rearmPressHorizonTicks}{@code , }{@link #REARM_TIMEOUT_COOLDOWN_SCALE}
     * {@code × cooldown × tickrate)}. Non-finite/non-positive cooldown falls to the floor —
     * a weapon whose native scale is unknowable gets the shortest honest hold, never a long one.
     */
    public static long latchTimeoutTicks(float nativeCooldownSec) {
        if (!Float.isFinite(nativeCooldownSec) || nativeCooldownSec <= 0f) {
            return rearmPressHorizonTicks();
        }
        long scaled = Math.round((double) REARM_TIMEOUT_COOLDOWN_SCALE * nativeCooldownSec
                * ServerTickRate.ticksPerSecond());
        return Math.max(rearmPressHorizonTicks(), scaled);
    }

    /**
     * Fix-1 — the per-fresh-frame latch decision for one still-latched mount. Sentinel
     * {@code Long.MIN_VALUE} = unset for {@code gpeeTick} (no server discharge seen on the mount
     * yet). Release priority within a frame: truth → timeout. A frame tick BEHIND the arm tick
     * (rewound clock) releases {@link #LATCH_RELEASE_TIMEOUT} — fail-open on clock anomaly, the
     * latch must never outlive its own clock's meaning. The fire-source (press) release was
     * RETIRED (see the release-code comment above): a fire-source drop —
     * including a group switch away — no longer releases; truth or the native-scale timeout do.
     */
    public static int latchAction(long frameTick, long armTick, long gpeeTick, long timeoutTicks) {
        if (gpeeTick != Long.MIN_VALUE && frameTick > gpeeTick) {
            return LATCH_RELEASE_TRUTH;
        }
        if (frameTick < armTick || frameTick - armTick > timeoutTicks) {
            return LATCH_RELEASE_TIMEOUT;
        }
        return LATCH_HOLD;
    }

    /**
     * Sub-lane 4 — true while the own-ship apply-DISABLE edge stays deferred (class doc):
     * within {@link #disableSettleTicks} of the last own-hull epoch bump.
     * {@code bumpTick == Long.MIN_VALUE} = no bump yet = never active. A {@code simTick} behind
     * the bump tick (a rewound clock — server restart) also reads active: fail-closed, the
     * residual-bit window is exactly when clocks are discontinuous.
     */
    public static boolean disableSettleActive(long simTick, long bumpTick) {
        return bumpTick != Long.MIN_VALUE && simTick - bumpTick < disableSettleTicks();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
