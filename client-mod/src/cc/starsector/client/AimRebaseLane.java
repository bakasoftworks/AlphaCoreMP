package cc.starsector.client;

import cc.starsector.client.net.ClientGameplayEdgeEvent;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.loading.ProjectileSpawnType;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import org.lwjgl.util.vector.Vector2f;

import static cc.starsector.client.FireLaneCoordinator.DISCHARGE_MISS_LOG_MAX;
import static cc.starsector.client.FireLaneCoordinator.dischargeMatchTolTicks;

/**
 * The own-discharge AIM-REBASE unit of {@link FireLaneCoordinator}: the rebase
 * law ({@link #rebaseAimDeg} / {@link #rebaseOwnProjectile}), the owner-marker A/B phase + strict
 * bucket meters, and the rebase-only counters {@link EdgeEventLane} prints. WHY: FLC stood at 2529 nb
 * against its 2351 WARN tier and this was its one self-contained 786-nb block. Provenance: moved
 * VERBATIM from {@code FireLaneCoordinator};
 * the only body edits are the four {@code fireLanes.} reads of state
 * that STAYS in FLC and {@link #isAutofireGroupDischarge} widened from private (FLC calls it).
 * RENDER-THREAD only, like FLC's lane state; FLC builds it in its ctor.
 *
 */
final class AimRebaseLane {

    private final PredictReconcileCombatPlugin plugin;
    private final FireLaneCoordinator fireLanes;

    AimRebaseLane(PredictReconcileCombatPlugin plugin, FireLaneCoordinator fireLanes) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.fireLanes = java.util.Objects.requireNonNull(fireLanes, "fireLanes");
        liveFallbackClassifyThrewLogged = false; // re-armed per combat (the lane is built per plugin)
    }

    /**
     * pair-scoped autofire classification for the aim-rebase graded max, knob
     * {@code cc.predict.pairClass} = {@code strict|legacy} (default {@code strict}). STRICT folds
     * a rebase pair off the graded {@code _nospread} bar when autofire was involved on EITHER
     * side of the pair: the client group's state AT FIRE TIME (record-time capture — the
     * rebase-time read races the toggle mirror), the mount's latest wire
     * {@code FLAG_AUTOFIRE}, or a wire autofire flip within the pairing window (the
     * toggle-transition leak class — 013437 s2 nospread_settled=158.62 with the
     * rebase-time fold already live). LEGACY = the original rebase-time-only check (the
     * corpus-comparable population; one-flip rollback). METRIC LABEL ONLY — the rebase mutation
     * is identical in both modes; feel cannot move. Compiled strict (E3 hardwire;
     * the sysprop read + the legacy corpus-rollback population are retired —
     * nothing set it, and no gate/grader keys on the strict|legacy label).
     */
    static final boolean PAIR_CLASS_STRICT = true;

    long dischargeAimRebasePairClassFolded; // strict folds beyond the rebase-time check (leak meter)

    // === own-discharge AIM-REBASE (the graze-flip class): the server's
    // WEAPON_DISCHARGE event already carries the authoritative aim; ship.weapon.getCurrAngle is
    // wire-UNCOVERED and persistently diverged (160849: 476/480 walks, ≤5.48°), so a graze-margin shot
    // can hit on one side and miss on the other. On every agree-pair the locally-predicted
    // projectile is rotated ABOUT ITS FIRST-SIGHTING ANCHOR onto the authoritative ray (heading-only
    // would leave flight-so-far × sin(Δ) ≈ 4–5 su of lateral error at the measured Δ — the same order
    // as the graze margin it must kill). Client-only, no wire bytes; inherits the eventReplay knob
    // (EVENT_REPLAY_ENABLED gates recordLocalDischarge; drainEdgeEvents is the replay lane itself).
    // Fail-soft skips: NaN wire aim (the server's "aim read unbound" marker) and a projectile already
    // out of play (hit/expired before the event arrived). ===
    int dischargeAimRebased;         // predict_event_rebase_own_discharge_aim_rebase_applied
    // The share of applied that was a ray paired AT sighting;
    // reader = watch-verdict.ps1's identity row text; delete once the batch flight's own-hit split is graded.
    int dischargeAimRebasedRayBirth; // predict_event_rebase_own_discharge_aim_rebase_applied_ray_birth
    // GPEE v6: rebases whose speed came from the wire |v0| instead of the
    // preserved-local magnitude (the m2 twin of the replica speed override) + the max-|Δ| meter
    // (|wire |v0| − local muzzle speed| — the own-side speed-estimate error, measured).
    int dischargeSpeedRebased;           // predict_event_rebase_own_discharge_speed_rebase_applied
    float dischargeSpeedRebaseMaxAbsUps; // predict_event_rebase_own_discharge_speed_rebase_max_abs_ups
    // Applied rebases on a MISSILE — the
    // live velocity was ROTATED by the birth-line Δ with |v| kept; the wire birth speed was NOT
    // written (a thrusting rocket paired N ticks late is no longer at its birth speed/heading).
    int dischargeSpeedRebaseSkippedAgedMissile; // ..._speed_rebase_skipped_aged_missile
    // Applied rebases whose Δ fell back to the LIVE velocity heading because the
    // local birth heading was unknown (NaN at sighting) — the old live-heading Δ definition, counted.
    int dischargeAimRebaseBirthHeadingLiveFallback; // ..._aim_rebase_birth_heading_live_fallback
    // The fallback split by body class. The old token
    // stays == _ray + _other (the verdict parser keys on it). _ray = a ray-spawned body
    // (ProjectileSpawnType BALLISTIC_AS_BEAM/BEAM — MovingRay, zero velocity at sighting, flies
    // straight so live heading == birth heading: benign); _other = anything else (namable via the
    // capped live-fallback row, cap LIVE_FALLBACK_LOG_MAX).
    int dischargeAimRebaseBirthHeadingLiveFallbackRay;   // ..._birth_heading_live_fallback_ray
    int dischargeAimRebaseBirthHeadingLiveFallbackOther; // ..._birth_heading_live_fallback_other
    static final int LIVE_FALLBACK_LOG_MAX = 20;
    int liveFallbackLogLines;
    // STATIC since the static rebaseBodyLocation seam classifies through
    // isRaySpawnBody — the constructor re-arms it, so the spawn-type-threw row still prints once per
    // combat.
    static boolean liveFallbackClassifyThrewLogged;
    int dischargeAimRebaseSkipUnbound; // ..._aim_rebase_skip_unbound (wire aim NaN)
    int dischargeAimRebaseSkipGone;    // ..._aim_rebase_skip_gone (projectile no longer in play)
    int dischargeAimRebaseSkipZeroVel; // ..._aim_rebase_skip_zero_vel (MovingRay-class spawn:
                                               // no velocity to rebase; EXPECTED for vulcan/beam own fire)
    // A GUIDED missile's velocity heading at pair time is
    // mid-guidance steering, not the launch line — Δ-rotating it back onto the stale wire aim
    // yanks the missile off its own faithful track (the guidance-turn pollution class).
    // Guided pairs SKIP the rebase whole; the server missile stays authoritative via snapshots.
    int dischargeAimRebaseSkipGuided;  // ..._aim_rebase_skip_guided (guided missile: mid-guidance
                                               // heading is not the launch line; rebase would corrupt it)
    int dischargeAimRebaseSkipFlare;   // ..._aim_rebase_skip_flare (a decoy's launch
                                               // bearing is an independent random roll per side)
    float dischargeAimRebaseMaxAbsDeg; // ..._aim_rebase_max_abs_deg (largest |Δ| applied)
    // The global max split by SPREAD CLASS —
    // per-shell spread is rolled independently per JVM, so on a weapon whose spec spread can
    // exceed the ≤6° serve-error bar the |Δ| floor is spread noise the serve path cannot remove
    // (devastator measured ±8–10°/side ⇒ floor ~16–20° on a perfectly-served shot). The step-5
    // bar reads _nospread; _spread is the excluded class's own max (INFO channel).
    float dischargeAimRebaseMaxAbsDegNospread; // ..._aim_rebase_max_abs_deg_nospread
    // The GRADED nospread max now
    // excludes samples inside the 2 s lifecycle settle window (respawn/MRST/kill edges yank the
    // hull mid-flight — kill-edge |Δ| is lifecycle noise, not serve error); raw _nospread stays
    // as the INFO channel, excluded samples honestly counted. Rewound clock ⇒ settle active ⇒
    // excluded (the disableSettleActive fail-closed idiom).
    float dischargeAimRebaseMaxAbsDegNospreadSettled; // ..._aim_rebase_max_abs_deg_nospread_settled
    int dischargeAimRebaseNospreadKillEdgeExcluded;   // ..._aim_rebase_nospread_kill_edge_excluded
    // MissileAPI bodies leave the graded _nospread /
    // _nospread_settled maxes (Δ is now birth-vs-birth and a low-launch rocket — hammer
    // 40 su/s — makes degrees ill-conditioned; 175812 seat-1 nospread 16.27° = all missile rows)
    // and land HERE (INFO until a data-set bar). Same settle / hull-gap exclusions as nospread
    // (those two counters keep counting both classes).
    float dischargeAimRebaseMaxAbsDegNospreadMissile;        // ..._max_abs_deg_nospread_missile
    float dischargeAimRebaseMaxAbsDegNospreadMissileSettled; // ..._max_abs_deg_nospread_missile_settled
    // Settled samples excluded because the own HULL pose
    // itself diverged >15° from the last wire facing (re-field fly-in class — the own-pose
    // lanes grade that state, the aim comparison is meaningless there). Counted, never silent.
    int dischargeAimRebaseNospreadHullGapExcluded;    // ..._aim_rebase_nospread_hullgap_excluded
    float dischargeAimRebaseMaxAbsDegSpread;   // ..._aim_rebase_max_abs_deg_spread
    float dischargeAimRebaseMaxAbsDegGuided;   // ..._aim_rebase_max_abs_deg_guided (INFO: the
                                               // would-be |Δ| on skipped guided pairs — guidance-turn
                                               // magnitude, expected large; never fed to the bar maxes)
    // The do-not-re-raise class: an AUTOFIRING
    // non-selected group's discharge is engine behavior the server's autofire mirror reproduces
    // on ITS OWN clock — the wire aim vs the local slew is pure cross-JVM timing noise the serve
    // path cannot remove, so its |Δ| folds off the graded _nospread bar (rebase STILL applies;
    // meter classification only). Classification mirrors declareFireDischarge's autofire skip
    // EXACTLY (selected group = manual control ⇒ stays _nospread); null/threw errs _nospread.
    int dischargeAimRebaseAutofireFolded;      // ..._aim_rebase_autofire_folded (INFO)
    float dischargeAimRebaseMaxAbsDegAutofire; // ..._aim_rebase_max_abs_deg_autofire (INFO)
    /** per-kind sampled-line counts (key = outcome kind up to any ':'), each capped at
     * DISCHARGE_MISS_LOG_MAX — replaces the shared cap 'applied' used to exhaust. */
    final java.util.Map<String, Integer> aimRebaseKindLogLines =
            new java.util.HashMap<String, Integer>();
    /** A/B-slew PREP — operator-marker-fenced phase accumulator over the aim-rebase
     * meters: the capped per-sample lines exhaust in the first few % of a session (one recorded run: seat2
     * tick 2246 of ~21600) and the NEW-MAX line is monotone (a later phase with a SMALLER max is
     * invisible), so per-fire-mode comparison needs phase-local maxima. Folds mirror the
     * cumulative classification 1:1 at the same branch sites; a phase CLOSES (one uncapped row +
     * reset) at each owner F9 marker and at teardown. Positive control: AimRebasePhaseSmoke. */
    static final class AimRebasePhase {
        long fromTick;                 // tick of the previous close (row shows the span)
        int applied;
        int autofireFolded;
        float maxAbsDeg;
        float maxAbsDegSpread;
        float maxAbsDegAutofire;
        float maxAbsDegNospread;
        float maxAbsDegNospreadSettled;
        int settledKillEdgeExcluded;
        int settledHullGapExcluded;

        /** cls mirrors the classification outcome: 's' spread, 'a' autofire-folded,
         * 'k' nospread settle-excluded (kill edge), 'g' nospread hull-gap-excluded,
         * 'n' nospread settled-counted. */
        void fold(char cls, float absDeg) {
            applied++;
            if (absDeg > maxAbsDeg) {
                maxAbsDeg = absDeg;
            }
            switch (cls) {
                case 's':
                    if (absDeg > maxAbsDegSpread) {
                        maxAbsDegSpread = absDeg;
                    }
                    break;
                case 'a':
                    autofireFolded++;
                    if (absDeg > maxAbsDegAutofire) {
                        maxAbsDegAutofire = absDeg;
                    }
                    break;
                default:
                    if (absDeg > maxAbsDegNospread) {
                        maxAbsDegNospread = absDeg;
                    }
                    if (cls == 'k') {
                        settledKillEdgeExcluded++;
                    } else if (cls == 'g') {
                        settledHullGapExcluded++;
                    } else if (absDeg > maxAbsDegNospreadSettled) {
                        maxAbsDegNospreadSettled = absDeg;
                    }
                    break;
            }
        }

        /** One row + reset; returns the formatted row so the smoke asserts content. */
        String close(int phaseNo, String label, long toTick) {
            String row = "predict aim-rebase phase #" + phaseNo + " [" + label + "]:"
                    + " from_tick=" + fromTick + " to_tick=" + toTick
                    + " applied=" + applied
                    + " autofire_folded=" + autofireFolded
                    + " max_abs_deg=" + maxAbsDeg
                    + " nospread=" + maxAbsDegNospread
                    + " nospread_settled=" + maxAbsDegNospreadSettled
                    + " spread=" + maxAbsDegSpread
                    + " autofire=" + maxAbsDegAutofire
                    + " settled_kill_edge_excluded=" + settledKillEdgeExcluded
                    + " settled_hullgap_excluded=" + settledHullGapExcluded;
            fromTick = toTick;
            applied = 0;
            autofireFolded = 0;
            maxAbsDeg = 0f;
            maxAbsDegSpread = 0f;
            maxAbsDegAutofire = 0f;
            maxAbsDegNospread = 0f;
            maxAbsDegNospreadSettled = 0f;
            settledKillEdgeExcluded = 0;
            settledHullGapExcluded = 0;
            return row;
        }
    }

    /** STRICT-pair per-shot angle histogram: the acceptance meter for the
     * autofire-TARGET lane. A prior analysis proved the >45° mass of the cumulative
     * {@code aim_rebase_max_abs_deg_autofire} meter is cross-mount/wide-tick MISPAIRING artifact
     * (a perfect lane still shows a huge max), so the lane's verify watch grades on THESE buckets,
     * which fold ONLY pairs that are mount-true AND tick-tight ({@link #STRICT_PAIR_DTICK_MAX} —
     * the offline strict re-pair gate, af-pairs CSV). Classes tracked: 'a' autofire (the graded
     * lane) and 'n' settled nospread (the healthy-baseline comparator); 'k'/'g'/'s' never enter.
     * The presentation pairing itself stays UNTOUCHED (a cosmetic rebase grabbing a
     * neighbor is not the defect — it just may not GRADE the lane). */
    static final class StrictShotBuckets {
        /** Bucket upper edges (deg); the last bucket is the >45° tail. Chosen around the
         *  measured scales: autofire p50 1.0 / p90 3.6 / p99 18.6 vs nospread ~1.3. */
        static final float[] EDGES_DEG = {1f, 2f, 5f, 10f, 20f, 45f};
        final int[] af = new int[EDGES_DEG.length + 1];
        final int[] ns = new int[EDGES_DEG.length + 1];
        /** Class 'm' = a settled nospread MISSILE pair — folded here,
         *  never into {@link #ns} (the graded bolt-aim comparator). */
        final int[] nsm = new int[EDGES_DEG.length + 1];
        float afMax;
        float nsMax;
        float nsmMax;
        int afLoose;   // autofire folds excluded by the strict gate — the artifact class, counted
        int nsLoose;
        int nsmLoose;

        /** + class 'm' (settled nospread missile) into
         *  {@link #nsm}; 'a'/'n' unchanged; every other class never enters. */
        void fold(char cls, float absDeg, boolean strictPair) {
            if (cls == 'm') {
                if (strictPair) {
                    nsm[bucketOf(absDeg)]++;
                    if (absDeg > nsmMax) {
                        nsmMax = absDeg;
                    }
                } else {
                    nsmLoose++;
                }
                return;
            }
            if (cls == 'a') {
                if (strictPair) {
                    af[bucketOf(absDeg)]++;
                    if (absDeg > afMax) {
                        afMax = absDeg;
                    }
                } else {
                    afLoose++;
                }
            } else if (cls == 'n') {
                if (strictPair) {
                    ns[bucketOf(absDeg)]++;
                    if (absDeg > nsMax) {
                        nsMax = absDeg;
                    }
                } else {
                    nsLoose++;
                }
            }
        }

        static int bucketOf(float absDeg) {
            for (int i = 0; i < EDGES_DEG.length; i++) {
                if (absDeg <= EDGES_DEG[i]) {
                    return i;
                }
            }
            return EDGES_DEG.length;
        }

        static String hist(int[] b) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(b[i]);
            }
            return sb.toString();
        }

        static int sum(int[] b) {
            int n = 0;
            for (int c : b) {
                n += c;
            }
            return n;
        }

        /** Row body (no prefix) + reset — the phase twin of {@link AimRebasePhase#close}.
         *  + the nsm_* missile tokens (after ns_loose). */
        String closeRow() {
            String row = "af_pairs=" + sum(af) + " af_hist=" + hist(af) + " af_max=" + afMax
                    + " af_loose=" + afLoose
                    + " ns_pairs=" + sum(ns) + " ns_hist=" + hist(ns) + " ns_max=" + nsMax
                    + " ns_loose=" + nsLoose
                    + " nsm_pairs=" + sum(nsm) + " nsm_hist=" + hist(nsm) + " nsm_max=" + nsmMax
                    + " nsm_loose=" + nsmLoose
                    + " edges_deg=1/2/5/10/20/45 dtick_max=" + STRICT_PAIR_DTICK_MAX;
            java.util.Arrays.fill(af, 0);
            java.util.Arrays.fill(ns, 0);
            java.util.Arrays.fill(nsm, 0);
            afMax = 0f;
            nsMax = 0f;
            nsmMax = 0f;
            afLoose = 0;
            nsLoose = 0;
            nsmLoose = 0;
            return row;
        }
    }

    /** Strict gate: |Δtick| bound for the bucket meters (both pairing sites pass
     *  pairDtick as an abs). Mirrors the offline strict re-pair; mount-true is the other half
     *  (pair-any rides its own counter, bar 0). */
    static final long STRICT_PAIR_DTICK_MAX = 5;

    final AimRebasePhase abPhase = new AimRebasePhase();
    /** Per-phase strict buckets (reset each close, beside {@link #abPhase}). */
    final StrictShotBuckets abPhaseStrict = new StrictShotBuckets();
    /** Run-cumulative strict buckets (never reset; the teardown meters read these). */
    final StrictShotBuckets runStrict = new StrictShotBuckets();
    int abPhaseCount;

    /** The af-strict-outlier row: a STRICT pair whose |Δ|
     *  tops this = the FAIL bracket of the {@code aim_rebase_max_abs_deg} bar
     *  (the grader's: 0.52 pass / 12.08 fail) logs a capped row (any class). WHY: a
     *  seat-1 72.68 (an unseeded mount 9, first local shot 39 ticks after the reclaim) and
     *  the kit's own m4 179.99 at tick 842 (a STRICT pair) could not be classified from
     *  the bar alone; the row pins start-angle vs target-pick divergence. Meter only: the rebase and
     *  every counter stay. {@link #afStrictOutlierRows} counts every outlier (past the cap too) and
     *  rides the strict row of {@link #closeAimRebasePhase} (each F9 marker + teardown). */
    static final float AF_STRICT_OUTLIER_DEG = 12.08f;
    static final int AF_STRICT_OUTLIER_ROWS_MAX = 20;
    int afStrictOutlierRows;

    private void foldStrict(char cls, float absDeg, boolean strictPair) {
        abPhaseStrict.fold(cls, absDeg, strictPair);
        runStrict.fold(cls, absDeg, strictPair);
    }

    /** Close the current A/B phase (owner F9 marker or teardown) — two uncapped lines
     *  (the classic phase row + the strict-bucket row). */
    void closeAimRebasePhase(String label, long toTick) {
        abPhaseCount++;
        PilotWatchLog.info(abPhase.close(abPhaseCount, label, toTick));
        PilotWatchLog.info("predict aim-rebase strict #" + abPhaseCount + " [" + label + "]: "
                + abPhaseStrict.closeRow() + " predict_aim_rebase_af_strict_outlier_rows=" + afStrictOutlierRows);
    }
    // Discharges whose v2 flightBearingDeg was NaN (unbound/legacy) — the rebase
    // fell back to the raw mount ray. Bar: 0 on a current-schema wire; >0 means the server's
    // per-scalar reads went unbound, not a client fault.
    int dischargeBearingUnbound;     // predict_event_rebase_own_discharge_bearing_unbound

    // Formed pairs whose |Δtick| exceeded the mount's OBSERVED shot period (min
    // successive local gap, floor CROSSSHOT_PERIOD_FLOOR_TICKS) — at MG cadence the true twin can
    // be attrited (SKIP-gone class) and tick-nearest pairing then grabbed a NEIGHBORING shot up to
    // ~175° away across a kill-melee turret swing. The pair still
    // adjudicates (agree/latch/ring accounting); only the geometric rebase is skipped — a mispaired
    // rebase snaps a live bolt onto the wrong shot's bearing mid-flight.
    int dischargeCrossshotSuspect;   // ..._aim_rebase_crossshot_suspect

    /**
     * The aim the own-discharge rebase should fly: the v2 wire-carried TRUE
     * initial flight bearing (the server's engine-faithful {@code shooterVel + projSpeed·û(ray)}
     * composition — what the server's bolt actually flies), falling back to {@code aimFacingDeg} (the
     * mount ray, or a missile's launch facing) when the bearing is NaN (server-side
     * read unbound / legacy frame; counted, never faked). The ray-only fallback keeps the pre-v2 behavior: correct for a stationary
     * shooter, up to ~13° short at combat speeds (measured).
     */
    float rebaseAimDeg(ClientGameplayEdgeEvent event) {
        float bearing = event.flightBearingDeg();
        if (Float.isFinite(bearing)) {
            return bearing;
        }
        dischargeBearingUnbound++;
        return event.aimFacingDeg();
    }

    /**
     * Rebase one locally-predicted own projectile onto the AUTHORITATIVE discharge
     * line ({@code authAimDeg} = {@link #rebaseAimDeg}: the v2 wire-carried
     * TRUE flight bearing — mount ray + shooter velocity, the line the server's bolt actually flies —
     * with the raw ray as the legacy/unbound fallback): rotate the flown offset
     * about the first-sighting anchor by Δ = (wire aim − the local BIRTH velocity heading, captured
     * at sighting; the live heading only when that is NaN) and fix the
     * velocity via {@link #rebaseVelocity}: a non-missile gets exactly the wire aim (speed = the v6
     * wire {@code |v0|} when usable — the full server vector — else the
     * preserved-local muzzle speed, the pre-v6 behavior); a MISSILE keeps its live |v| and has its
     * live velocity ROTATED by Δ — both sides of Δ are birth quantities, so thrust physics since
     * birth is never folded in, and no birth speed is written onto an aged thrusting rocket (a
     * measured case: the old live-heading Δ rotated a TRUE-twin hammer torpedo by −117.65° and
     * stalled it 455 → 143 su/s).
     * Kills the graze-flip class AND (v2) the aim_rebase
     * 5–13° velocity-term strip: the client's shot flies the bearing the server's shot flies, so
     * hit/miss can no longer diverge on the wire-uncovered {@code weapon.getCurrAngle}.
     *
     * <p>FACING IS NEVER TOUCHED (the vanilla pointing law):
     * an unguided projectile keeps pointing down the ray it was FIRED along while velocity
     * inheritance pulls its travel sideways — heading and flight line are decoupled for the whole
     * unguided family (bolts included; missiles burn thrust along FACING, so a facing error also
     * bends the whole arc). The engine's native spawn already faced this projectile down the
     * client's fired ray, which IS the vanilla pointing up to spawn-geometry prediction divergence;
     * the old {@code setFacing(authAimDeg)} here snapped it onto the COMPOSED flight line at +2t
     * (missiles 30–57° off-ray, bolts ~11–16° at combat strafe speeds) — the felt
     * defect. Rotating facing BY Δ was considered and rejected: Δ folds the client↔server
     * velocity-inheritance divergence — not a pointing error — into facing, and re-introduces a
     * visible +2t snap; leaving native facing untouched is both simpler and closer to the server's
     * true pointing (server facing = server ray; client ray ≈ server ray up to hull-facing
     * prediction error, the smaller term).
     * Presentation-only corrective on an agree-pair; the *_only (unmatched) signs never rebase —
     * with no authoritative aim there is nothing faithful to rebase to.
     *
     * <p>{@code pairDtick}/{@code mount}/{@code spawnTick} thread in from the two
     * pairing sites — the crossshot-suspect gate (pair wider than the mount's observed shot
     * period ⇒ skip the geometric mutation, count + capped line) and the NEW-MAX line's
     * mispair-vs-serve-error discriminators.
     *
     * <p>The live-fallback count splits _ray/_other (+ a
     * capped naming row, {@link #noteBirthHeadingLiveFallback}); a MissileAPI body on the
     * nospread branch folds into the _nospread_missile maxes + strict class 'm', never the
     * graded _nospread maxes / strict ns. The rebase mutation itself is unchanged.
     */
    void rebaseOwnProjectile(DamagingProjectileAPI proj, float anchorX, float anchorY,
            float authAimDeg, float localBirthHeadingDeg, float wireBirthSpeedUps, long tick,
            long pairDtick, int mount, long spawnTick, boolean afAtFire) {
        try {
            if (!Float.isFinite(authAimDeg)) {
                dischargeAimRebaseSkipUnbound++;
                logAimRebase("SKIP-unbound", tick, Float.NaN, Float.NaN);
                return;
            }
            if (proj == null || plugin.engine == null || !plugin.engine.isEntityInPlay(proj)) {
                dischargeAimRebaseSkipGone++;
                logAimRebase("SKIP-gone", tick, authAimDeg, Float.NaN);
                return;
            }
            Vector2f vel = proj.getVelocity();
            float rayBirthDeg = Float.NaN;
            if (vel == null || (vel.x == 0f && vel.y == 0f)) {
                // A zero-velocity own projectile is a
                // MovingRay-class spawn (vulcan/beam): getVelocity() is rayExtender-internal
                // (zero at spawn), getFacing() is never set on rays, so the old fallback read
                // a garbage heading and Δ-rotated the bolt's LOCATION about the anchor (a
                // measured 28.72° error). A ray body now takes its birth
                // heading from its own two ends ({@link #rayBirthHeadingDeg}) and is rebased by
                // the b1 two-end rotation alone; anything else still SKIPs whole (the class simple
                // name in the capped line stays the ray-vs-ballistic discriminator).
                rayBirthDeg = rayBirthHeadingDeg(proj);
                if (!Float.isFinite(rayBirthDeg)) {
                    dischargeAimRebaseSkipZeroVel++;
                    logAimRebase("SKIP-zero-vel:" + proj.getClass().getSimpleName(),
                            tick, authAimDeg, Float.NaN);
                    return;
                }
            }
            boolean rayBirth = Float.isFinite(rayBirthDeg);
            if (proj instanceof MissileAPI && ((MissileAPI) proj).isFlare()) {
                // A flare is a DECOY whose launch bearing is an independent
                // random roll per side (flarelauncher3 spread 90°, SeekerFlareAI is NOT a
                // GuidedMissileAI so the guided skip below misses it) — the wire bearing is the
                // SERVER's roll, not a better estimate of this one. Rebasing would yank the
                // owner's own flare up to ~180° an RTT after launch. The server's flares stay
                // authoritative for seduction; the local volley is the engine's own picture.
                dischargeAimRebaseSkipFlare++;
                logAimRebase("SKIP-flare", tick, authAimDeg, Float.NaN);
                return;
            }
            if (proj instanceof MissileAPI && ((MissileAPI) proj).isGuided()) {
                // By pair time a guided missile has been
                // STEERING — its velocity heading is guidance output, not the launch line, so the
                // Δ vs the wire launch aim measures the guidance turn (expected large; a measured
                // nospread=177.6 was exactly this pollution). Rebasing would yank a
                // mid-guidance missile back onto the stale launch line: the exclusion IS the fix
                // (faithfulness-POSITIVE — the server missile stays authoritative via snapshots).
                // Would-be Δ recorded on the INFO channel; no vel/loc/facing mutation. Runtime
                // truth: Missile.isGuided() == getAI() instanceof GuidedMissileAI,
                // true for swarmer/heatseeker-class AI (MissileAI/HeatseekerAI implement it).
                float guidedHeadingDeg = (float) Math.toDegrees(Math.atan2(vel.y, vel.x));
                float guidedDeltaDeg = ((authAimDeg - guidedHeadingDeg + 540f) % 360f) - 180f;
                float guidedAbsDeg = Math.abs(guidedDeltaDeg);
                if (guidedAbsDeg > dischargeAimRebaseMaxAbsDegGuided) {
                    dischargeAimRebaseMaxAbsDegGuided = guidedAbsDeg;
                }
                dischargeAimRebaseSkipGuided++;
                logAimRebase("SKIP-guided", tick, authAimDeg, guidedDeltaDeg);
                return;
            }
            // Δ is the BIRTH-line correction — wire birth bearing vs the local
            // birth heading (sighting-time read); the live heading is the counted NaN fallback.
            // (z): an un-advanced ray's two-end heading IS its birth heading (the sighting read is NaN).
            boolean birthKnown = rayBirth || Float.isFinite(localBirthHeadingDeg);
            float headingDeg = rayBirth ? rayBirthDeg : birthKnown ? localBirthHeadingDeg
                    : (float) Math.toDegrees(Math.atan2(vel.y, vel.x));
            float deltaDeg = foldDeltaDeg(authAimDeg, headingDeg); // shortest signed arc
            // crossshot-suspect gate: a pair formed WIDER than the mount's own
            // observed shot period is likelier a neighboring same-mount shot than the true twin
            // (twin attrited — the SKIP-gone class proved live). Adjudication stands
            // (agree/latch already counted at the pairing site); only the geometric rebase skips.
            if (mount >= 0 && pairDtick > fireLanes.crossshotPeriodTicks(mount)) {
                dischargeCrossshotSuspect++;
                logAimRebase("SKIP-crossshot", tick, authAimDeg, deltaDeg);
                return;
            }
            if (!birthKnown) {
                noteBirthHeadingLiveFallback(proj, tick, deltaDeg);
            }
            // (z): a ray-birth rebase makes NO velocity write — the vector is the extender's own
            // (0,0), and the engine rewrites it from the two ends on its first advance.
            //
            // A ray's two ends turn by the DIRECTION delta that lands its
            // flown bearing on the wire's (the engine composes dir·speed + the ship velocity it copied
            // at spawn, so turning the ends by the bearing delta under-turns the bearing by the ship's
            // share — rayDirectionDeltaDeg), and a ray still at birth pivots on its TAIL = the muzzle
            // (the anchor is the sighting head: pivoting there swung the muzzle end by width/2·Δ).
            // Computed BEFORE the velocity write below: rebaseVelocity rotates `vel` in place, and the
            // flown ray's u is read back from the vector the engine composed.
            float rotDeg = deltaDeg;
            if (rayBirth || isRaySpawnBody(proj)) {
                float dirDeg = rayDirectionDeltaDeg(proj, vel, authAimDeg);
                if (Float.isFinite(dirDeg)) {
                    rotDeg = dirDeg;
                } else {
                    // The NaN fallback keeps the bearing delta (right: a SKIP
                    // would leave the full error) but was silent — its own capped kind; reader = the
                    // z-legs Z-COUNTERS rows_ray_dir_nan; delete when the legs read 0.
                    logAimRebase("ray-dir-nan", tick, authAimDeg, deltaDeg);
                }
                Vector2f tail = rayBirth ? proj.getTailEnd() : null;
                if (tail != null) {
                    anchorX = tail.x;
                    anchorY = tail.y;
                }
            }
            if (!rayBirth) {
                float localSpeed = (float) Math.hypot(vel.x, vel.y);
                int velOutcome = rebaseVelocity(vel, authAimDeg, deltaDeg,
                        proj instanceof MissileAPI, wireBirthSpeedUps);
                if (velOutcome == REBASE_VEL_MISSILE_ROTATED) {
                    dischargeSpeedRebaseSkippedAgedMissile++;
                } else if (velOutcome == REBASE_VEL_WIRE_SPEED) {
                    dischargeSpeedRebased++;
                    float speedAbsDelta = Math.abs(wireBirthSpeedUps - localSpeed);
                    if (speedAbsDelta > dischargeSpeedRebaseMaxAbsUps) {
                        dischargeSpeedRebaseMaxAbsUps = speedAbsDelta;
                    }
                }
            }
            // A ray body moves BOTH ends — see rebaseBodyLocation; the ray
            // rotation + anchor = the direction-delta block above.
            rebaseBodyLocation(proj, anchorX, anchorY, rotDeg);
            // NO setFacing — native (fired-ray) pointing stands; see the method javadoc.
            dischargeAimRebased++;
            if (rayBirth) {
                dischargeAimRebasedRayBirth++; // the birth-paired ray share of applied
            }
            float absDeg = Math.abs(deltaDeg);
            // strict-pair gate for the bucket METERS only — the rebase and
            // every existing counter above/below are unchanged (pairDtick arrives as an abs
            // from both pairing sites).
            boolean strictPair = mount >= 0 && pairDtick <= STRICT_PAIR_DTICK_MAX;
            if (absDeg > dischargeAimRebaseMaxAbsDeg) {
                dischargeAimRebaseMaxAbsDeg = absDeg;
            }
            boolean afNow = isAutofireGroupDischarge(proj);
            boolean afPair = !afNow && pairClassAutofireInvolved(afAtFire, mount, tick);
            // The af-strict-outlier row, every class.
            if (strictPair && absDeg > AF_STRICT_OUTLIER_DEG && ++afStrictOutlierRows <= AF_STRICT_OUTLIER_ROWS_MAX) {
                PilotWatchLog.info("predict aim-rebase af-strict-outlier tick=" + tick + " mount=" + mount
                        + " weapon=" + weaponIdOrNa(proj) + " dTheta=" + deltaDeg + " pairDtick=" + pairDtick
                        + " af=" + afNow + " pair=" + afPair + " sel=" + selectedGroupFlag(proj));
            }
            if (isSpreadClassWeapon(proj)) {
                abPhase.fold('s', absDeg);
                if (absDeg > dischargeAimRebaseMaxAbsDegSpread) {
                    dischargeAimRebaseMaxAbsDegSpread = absDeg;
                }
            } else if (afNow || afPair) {
                // autofire-slew timing noise, not serve error — the rebase
                // above already applied; only the graded-max classification changes.
                // afPair = the pair-scoped fold (autofire at fire time or on the
                // wire though the rebase-time read said manual) — the toggle-transition leak
                // class, counted separately so the next graded run proves the leak member.
                if (afPair) {
                    dischargeAimRebasePairClassFolded++;
                }
                abPhase.fold('a', absDeg);
                foldStrict('a', absDeg, strictPair);
                dischargeAimRebaseAutofireFolded++;
                if (absDeg > dischargeAimRebaseMaxAbsDegAutofire) {
                    dischargeAimRebaseMaxAbsDegAutofire = absDeg;
                }
            } else {
                // A missile body keeps the nospread classification (settle /
                // hull-gap exclusions, phase fold) but its maxes + strict bucket are its own.
                boolean missile = proj instanceof MissileAPI;
                boolean newRawMax;
                if (missile) {
                    newRawMax = absDeg > dischargeAimRebaseMaxAbsDegNospreadMissile;
                    if (newRawMax) {
                        dischargeAimRebaseMaxAbsDegNospreadMissile = absDeg;
                    }
                } else {
                    newRawMax = absDeg > dischargeAimRebaseMaxAbsDegNospread;
                    if (newRawMax) {
                        dischargeAimRebaseMaxAbsDegNospread = absDeg;
                    }
                }
                // The GRADED max excludes the 2 s lifecycle settle
                // window (kill/MRST/respawn edges + the deploy edge — the same
                // clock sub-lane 4 uses); rewound clock reads settle-active ⇒ excluded
                // (fail-closed). The hull-gap class is excluded too: a >15° own-hull-vs-wire
                // facing divergence (re-field fly-in) sits OUTSIDE any fixed edge window (one watch saw
                // 157°/165° at ticks 30406/30439, ~1.1k ticks after the armed respawn
                // edge) — excluded by state, not by clock.
                boolean newSettledMax = false;
                if (OwnFireLockLaw.disableSettleActive(tick, fireLanes.lastLifecycleEdgeTick)) {
                    dischargeAimRebaseNospreadKillEdgeExcluded++;
                    abPhase.fold('k', absDeg);
                } else if (plugin.ownHullFacingGapDeg() > 15f) {
                    dischargeAimRebaseNospreadHullGapExcluded++;
                    abPhase.fold('g', absDeg);
                } else {
                    // phase fold BEFORE the cumulative-max compare — a settled sample below the
                    // cumulative run max still belongs to ITS phase's max (the A/B point).
                    abPhase.fold('n', absDeg);
                    if (missile) {
                        foldStrict('m', absDeg, strictPair);
                        if (absDeg > dischargeAimRebaseMaxAbsDegNospreadMissileSettled) {
                            dischargeAimRebaseMaxAbsDegNospreadMissileSettled = absDeg;
                            newSettledMax = true;
                        }
                    } else {
                        foldStrict('n', absDeg, strictPair);
                        if (absDeg > dischargeAimRebaseMaxAbsDegNospreadSettled) {
                            dischargeAimRebaseMaxAbsDegNospreadSettled = absDeg;
                            newSettledMax = true;
                        }
                    }
                }
                if (newRawMax || newSettledMax) {
                    logAimRebaseNewMax(tick, authAimDeg, deltaDeg, newRawMax, newSettledMax, proj,
                            pairDtick, mount, spawnTick, birthKnown, missile, rayBirth);
                }
            }
            // Its own kind (= its own 40-row cap; the text before ':' is the kind, so
            // "applied:ray-birth" had spent applied's cap — the cap-starvation class).
            logAimRebase(rayBirth ? "ray-birth:applied" : "applied", tick, authAimDeg, deltaDeg);
        } catch (Throwable t) {
            // presentation-side corrective; never break the watch (same discipline as reconcileFoe)
            dischargeAimRebaseSkipGone++;
            logAimRebase("SKIP-threw:" + t.getClass().getSimpleName(), tick, authAimDeg, Float.NaN);
        }
    }

    /**
     * The rebase's LOCATION half: rotate the body about the
     * first-sighting anchor by Δ. {@code head} = {@code getLocation()}; {@code tailOrNull} = a
     * ray body's {@code getTailEnd()}, null for every other class. WHY the tail: a MovingRay's
     * {@code getLocation()} is its HEAD ({@code float.getLocation() == getTo()}) and the engine
     * re-derives the flight direction from tail to head on every advance ({@code Utils.class(from, to)}
     * normalised; {@code MovingRay.getTailEnd()}
     * hands out that live {@code from} vector). Moving the head alone pivoted the ray about its
     * unmoved tail, turning it by ≈ Δ·d/L (d = head distance from the anchor, L = ray length —
     * ≈ 2.7× at d 250 su, L 90 su): two watches put 230 of 277 clean-join own hits
     * on the FAR side of the server's line.
     * Rotating both ends is a rigid rotation, so the direction turns by exactly Δ — the same
     * in-place two-end move vanilla's own scripts make. A tail that IS the head vector (never
     * seen; defensive) is rotated once.
     */
    static void rotateBodyAbout(Vector2f head, Vector2f tailOrNull, float anchorX, float anchorY,
            float deltaDeg) {
        double dRad = Math.toRadians(deltaDeg);
        float cos = (float) Math.cos(dRad);
        float sin = (float) Math.sin(dRad);
        float dx = head.x - anchorX;
        float dy = head.y - anchorY;
        head.set(anchorX + dx * cos - dy * sin, anchorY + dx * sin + dy * cos);
        if (tailOrNull != null && tailOrNull != head) {
            float tx = tailOrNull.x - anchorX;
            float ty = tailOrNull.y - anchorY;
            tailOrNull.set(anchorX + tx * cos - ty * sin, anchorY + tx * sin + ty * cos);
        }
    }

    /**
     * The rebase's location step as ONE seam
     * that {@link #rebaseOwnProjectile} and {@code AimRebaseVectorSmoke} V8 both call, so a row pins
     * the call-site choice "tail iff ray" ({@link #isRaySpawnBody}), not only the
     * {@link #rotateBodyAbout} helper. A null {@code getLocation()} moves nothing.
     */
    static void rebaseBodyLocation(DamagingProjectileAPI proj, float anchorX, float anchorY,
            float deltaDeg) {
        Vector2f loc = proj.getLocation();
        if (loc != null) {
            rotateBodyAbout(loc, isRaySpawnBody(proj) ? proj.getTailEnd() : null,
                    anchorX, anchorY, deltaDeg);
        }
    }

    /**
     * The birth heading, in the WIRE's frame, of a ray body the engine
     * has not advanced yet. WHY: a MovingRay's {@code getVelocity()} is its extender's velocity
     * vector, (0,0) until the first advance, so an own ray paired AT sighting — the wire birth
     * already in hand — was skipped whole (SKIP-zero-vel: 633 of 3001 own pairs on the eight b1
     * rig legs). The engine spawns the ray
     * with from = the muzzle and to = from + dir·width/2, and
     * every advance flies it along unit(from→to) at move speed PLUS the ship velocity it copied
     * at spawn (the spawning call passes
     * {@code ship.getVelocity()}) — the vector the server's wire bearing is the atan2 of. The
     * local twin of that bearing is therefore atan2(unit(tail→head)·moveSpeed + sourceVel).
     * NaN = not a ray, or a degenerate body (a null end, tail == head, a non-positive move
     * speed): the caller keeps the skip.
     */
    static float rayBirthHeadingDeg(DamagingProjectileAPI proj) {
        if (!isRaySpawnBody(proj)) {
            return Float.NaN;
        }
        Vector2f head = proj.getLocation();
        Vector2f tail = proj.getTailEnd();
        if (head == null || tail == null) {
            return Float.NaN;
        }
        float dx = head.x - tail.x;
        float dy = head.y - tail.y;
        float len = (float) Math.hypot(dx, dy);
        float speed = proj.getMoveSpeed();
        if (!(len > 0f) || !(speed > 0f)) { // NaN-safe: a NaN fails both
            return Float.NaN;
        }
        ShipAPI source = proj.getSource();
        Vector2f sourceVel = source == null ? null : source.getVelocity();
        float vx = dx / len * speed + (sourceVel == null ? 0f : sourceVel.x);
        float vy = dy / len * speed + (sourceVel == null ? 0f : sourceVel.y);
        return (float) Math.toDegrees(Math.atan2(vy, vx));
    }

    /**
     * The rotation a RAY's two ends need so that the bearing the engine then
     * flies lands on {@code authAimDeg}. WHY not the bearing delta: the engine flies
     * unit(tail→head)·moveSpeed + u, u = the ship velocity copied at spawn,
     * so turning the ends by the bearing delta Δ turns the bearing by only
     * s(s + u·d)/|s·d + u|²·Δ (V9's own numbers: 1.35° short on a 10° Δ). Closed form: the new
     * direction d' satisfies s·d' + u = λ·ŵ with ŵ the wire bearing and
     * λ = u·ŵ + √(s² − |u|² + (u·ŵ)²). u is read back from a flown ray's velocity (v − s·d; the
     * composition inverted exactly) and from the source ship at birth. NaN = not a usable ray, or
     * the ship outruns its shot (no direction reaches the wire bearing): the caller keeps Δ.
     */
    static float rayDirectionDeltaDeg(DamagingProjectileAPI proj, Vector2f vel, float authAimDeg) {
        Vector2f head = proj.getLocation();
        Vector2f tail = proj.getTailEnd();
        float speed = proj.getMoveSpeed();
        if (head == null || tail == null || !(speed > 0f) || !Float.isFinite(authAimDeg)) {
            return Float.NaN;
        }
        float dx = head.x - tail.x;
        float dy = head.y - tail.y;
        float len = (float) Math.hypot(dx, dy);
        if (!(len > 0f)) {
            return Float.NaN;
        }
        dx /= len;
        dy /= len;
        float ux;
        float uy;
        if (vel != null && (vel.x != 0f || vel.y != 0f)) {
            ux = vel.x - dx * speed;
            uy = vel.y - dy * speed;
        } else {
            ShipAPI source = proj.getSource();
            Vector2f sourceVel = source == null ? null : source.getVelocity();
            ux = sourceVel == null ? 0f : sourceVel.x;
            uy = sourceVel == null ? 0f : sourceVel.y;
        }
        double w = Math.toRadians(authAimDeg);
        double wx = Math.cos(w);
        double wy = Math.sin(w);
        double uw = ux * wx + uy * wy;
        double disc = (double) speed * speed - ((double) ux * ux + (double) uy * uy) + uw * uw;
        if (!(disc >= 0d)) {
            return Float.NaN;
        }
        double lambda = uw + Math.sqrt(disc);
        if (!(lambda > 0d)) {
            return Float.NaN;
        }
        float newDirDeg = (float) Math.toDegrees(Math.atan2(lambda * wy - uy, lambda * wx - ux));
        return foldDeltaDeg(newDirDeg, (float) Math.toDegrees(Math.atan2(dy, dx)));
    }

    /** {@link #rebaseVelocity} outcome: missile — live velocity rotated by Δ, |v| kept. */
    static final int REBASE_VEL_MISSILE_ROTATED = 0;
    /** {@link #rebaseVelocity} outcome: non-missile — heading = auth, speed = wire |v0|. */
    static final int REBASE_VEL_WIRE_SPEED = 1;
    /** {@link #rebaseVelocity} outcome: non-missile — heading = auth, local |v| kept (wire NaN/implausible). */
    static final int REBASE_VEL_LOCAL_SPEED = 2;

    /** Shortest signed arc {@code a − b} in (−180, 180]
     *  deg, the fold the rebase always used (lifted pure so {@code AimRebaseVectorSmoke} pins the
     *  ±180 wrap: auth 170 vs birth −170 ⇒ −20). */
    static float foldDeltaDeg(float aDeg, float bDeg) {
        return ((aDeg - bDeg + 540f) % 360f) - 180f;
    }

    /**
     * The pure velocity half of
     * {@link #rebaseOwnProjectile}, in place on {@code vel}. WHY: the wire birth speed + bearing
     * are BIRTH quantities; writing them onto a thrusting rocket paired tens of ticks late stalled
     * a TRUE-twin hammer torpedo 455 → 143 su/s.
     * A MISSILE therefore keeps its live |v| and has its live vector rotated by the
     * birth-line Δ (≈0-5° on a true pair; at age 0 live == birth so heading lands on auth exactly
     * as before). A non-missile (ballistic: live == birth for its whole flight) keeps the
     * behaviour verbatim: heading = {@code authAimDeg}, speed = the wire
     * {@code |v0|} when finite and in [0, MAX_PLAUSIBLE_BIRTH_SPEED_UPS], else the local |v|.
     * Returns one of the {@code REBASE_VEL_*} outcomes for the caller's meters.
     */
    static int rebaseVelocity(Vector2f vel, float authAimDeg, float deltaDeg, boolean missile,
            float wireBirthSpeedUps) {
        if (missile) {
            double dRad = Math.toRadians(deltaDeg);
            float cos = (float) Math.cos(dRad);
            float sin = (float) Math.sin(dRad);
            float vx = vel.x;
            float vy = vel.y;
            vel.set(vx * cos - vy * sin, vx * sin + vy * cos);
            return REBASE_VEL_MISSILE_ROTATED;
        }
        float speed = (float) Math.hypot(vel.x, vel.y);
        int outcome = REBASE_VEL_LOCAL_SPEED;
        if (Float.isFinite(wireBirthSpeedUps) && wireBirthSpeedUps >= 0f
                && wireBirthSpeedUps <= ClientGameplayEdgeEvent.MAX_PLAUSIBLE_BIRTH_SPEED_UPS) {
            speed = wireBirthSpeedUps;
            outcome = REBASE_VEL_WIRE_SPEED;
        }
        double authRad = Math.toRadians(authAimDeg);
        vel.set(speed * (float) Math.cos(authRad), speed * (float) Math.sin(authRad));
        return outcome;
    }

    /**
     * pair-scoped autofire involvement beyond the rebase-time group read: the
     * fire-time capture, the mount's latest wire {@code FLAG_AUTOFIRE}, or a wire autofire flip
     * within the pairing window ([HYPOTHESIS] the observed leak's mechanism = toggle-transition
     * windows; the fold is member-agnostic — ANY autofire involvement leaves the strict bar).
     * Unknowns contribute FALSE (errs toward the graded bar — the spread-discriminator
     * discipline below); a genuinely-manual serve-path jam has autofire uninvolved everywhere
     * and still grades. No-op under {@code cc.predict.pairClass=legacy}.
     */
    private boolean pairClassAutofireInvolved(boolean afAtFire, int mount, long tick) {
        if (!PAIR_CLASS_STRICT) {
            return false;
        }
        if (afAtFire) {
            return true;
        }
        if (mount < 0 || mount >= 64) {
            return false;
        }
        if ((fireLanes.serverAfMountMask >>> mount & 1L) != 0L) {
            return true;
        }
        Long edge = fireLanes.serverAfEdgeTick.get(mount);
        return edge != null && tick - edge <= dischargeMatchTolTicks();
    }

    /**
     * Spread-class discriminator for the
     * aim-rebase max split: a weapon whose projectile spec's max spread exceeds the 6° serve-error
     * bar can breach it on pure per-JVM spread-roll noise (the two engines roll per-shell spread
     * independently), so its |Δ| folds into the _spread max instead of the barred _nospread max.
     * Unknown/weaponless/non-projectile-spec spawns fold NON-spread — errs toward AMBER, never
     * hides a real serve-path jam behind the spread label.
     */
    static boolean isSpreadClassWeapon(DamagingProjectileAPI proj) {
        WeaponAPI weapon = proj.getWeapon();
        if (weapon == null) {
            return false;
        }
        Object spec = weapon.getSpec();
        return spec instanceof ProjectileWeaponSpecAPI
                && ((ProjectileWeaponSpecAPI) spec).getMaxSpread() > 6f;
    }

    /**
     * autofire-group discriminator for the aim-rebase max split:
     * ANY autofiring group's discharge counts as autofire here — the engine's autofire
     * slew owns the aim whether or not the group is also the SELECTED one, so selected-autofire
     * shells leave the graded _nospread bar (kills the do-not-re-raise false-AMBER class).
     * INTENTIONAL divergence from the decl lane ({@code declareFireDischarge}): a SELECTED
     * autofiring group's discharges are still DECLARED — that clause is a load-bearing SEND
     * decision (the server mirror needs them), this one is a meter label only (RAW rows
     * unimpeded). Null anywhere or a throw errs toward _nospread — AMBER over
     * hidden, the isSpreadClassWeapon discipline.
     */
    boolean isAutofireGroupDischarge(DamagingProjectileAPI proj) {
        try {
            WeaponAPI weapon = proj.getWeapon();
            ShipAPI own = plugin.ownShip;
            if (weapon == null || own == null) {
                return false;
            }
            WeaponGroupAPI grp = own.getWeaponGroupFor(weapon);
            return grp != null && grp.isAutofiring();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The capped per-rebase line (counters keep counting past the cap; cap is
     * per outcome kind). */
    void logAimRebase(String what, long tick, float authAimDeg, float deltaDeg) {
        // The cap is PER OUTCOME KIND (key = {@code what} up to any
        // ':' discriminator) — the old single shared cap let 'applied' exhaust all 40 slots
        // before one SKIP-zero-vel line landed (269 skips, zero samples). Kind
        // cardinality is bounded by the caller (applied/SKIP-unbound/SKIP-gone/SKIP-zero-vel/
        // SKIP-guided/SKIP-crossshot/SKIP-threw/ray-birth/ray-dir-nan), so the worst case stays ≤9×40 one-liners.
        int colon = what.indexOf(':');
        String kind = colon >= 0 ? what.substring(0, colon) : what;
        Integer prev = aimRebaseKindLogLines.get(kind);
        int n = prev == null ? 0 : prev.intValue();
        if (n >= DISCHARGE_MISS_LOG_MAX) {
            return;
        }
        n++;
        aimRebaseKindLogLines.put(kind, Integer.valueOf(n));
        PilotWatchLog.info("predict aim-rebase " + kind + " #" + n + " (" + what + "): tick=" + tick
                + " authAim=" + authAimDeg + " dTheta=" + deltaDeg
                + (n == DISCHARGE_MISS_LOG_MAX
                        ? " [kind log cap reached; counters continue]" : ""));
    }

    /**
     * The UNCAPPED NEW-MAX line: fires only when a nospread max (raw or
     * settled) INCREASES, so it is monotone-bounded (naturally ≤ ~a dozen lines/run) and never
     * needs the per-kind cap that left 180241's 9.29°/8.37° max events unlogged (the 'applied'
     * sampler exhausted its 40 slots by tick 2947). Names the mechanism before any threshold
     * change on the ≤6.0° settled bar. + pairDtick/mount/spawnTick — one
     * like-for-like watch converts the mispair inference to [observed] (a big dTheta with
     * pairDtick ≈ the mount period is a neighbor-pair; pairDtick 0–2 is a real serve gap).
     * dTheta is vs the local BIRTH heading ({@code dvs=birth}; the
     * NaN-birth live fallback prints {@code dvs=live});
     * {@code max_abs_deg_nospread} on older flights folded missile thrust physics into Δ
     * (a measured −117.65° was a TRUE twin) — not comparable across that boundary.
     * + trailing {@code bar=nospread|nospread_missile} —
     * which max pair rose (missile bodies now own the _nospread_missile maxes); the weapon-id
     * read failure is logged once (was a silent catch).
     */
    void logAimRebaseNewMax(long tick, float authAimDeg, float deltaDeg,
            boolean raw, boolean settled, DamagingProjectileAPI proj,
            long pairDtick, int mount, long spawnTick, boolean deltaVsBirth, boolean missile,
            boolean rayBirth) {
        String weaponId = weaponIdOrNa(proj);
        PilotWatchLog.info("predict aim-rebase NEW-MAX ("
                + (raw && settled ? "raw+settled" : (raw ? "raw" : "settled"))
                + "): tick=" + tick + " authAim=" + authAimDeg + " dTheta=" + deltaDeg
                + " weapon=" + weaponId + " pairDtick=" + pairDtick + " mount=" + mount
                + " spawnTick=" + spawnTick + " dvs=" + (deltaVsBirth ? "birth" : "live")
                + " ray_birth=" + (rayBirth ? 1 : 0) // a birth-paired ray's max
                + " bar=" + (missile ? "nospread_missile" : "nospread"));
    }

    private boolean weaponIdReadThrewLogged;

    /** The discharging weapon's id, {@code "n/a"} when
     *  weaponless/unreadable; a throwing read logs ONCE (never silent). Shared by the NEW-MAX and
     *  live-fallback rows. */
    String weaponIdOrNa(DamagingProjectileAPI proj) {
        try {
            WeaponAPI w = proj.getWeapon();
            if (w != null && w.getId() != null) {
                return w.getId();
            }
        } catch (Throwable t) {
            if (!weaponIdReadThrewLogged) {
                weaponIdReadThrewLogged = true;
                PilotWatchLog.info("predict aim-rebase weapon-id read threw (logged once): " + t);
            }
        }
        return "n/a";
    }

    private boolean selReadThrewLogged;

    /** The af-strict-outlier row's {@code sel=}: 1 = the
     *  discharging mount's group IS the own ship's selected group (the FLC own-adopt mount-seed
     *  idiom), 0 = another group, -1 = unreadable (no own ship / weapon / group; a throw logs once). */
    int selectedGroupFlag(DamagingProjectileAPI proj) {
        try {
            ShipAPI own = plugin.ownShip;
            WeaponAPI w = proj.getWeapon();
            WeaponGroupAPI g = own == null || w == null ? null : own.getWeaponGroupFor(w);
            return g == null ? -1 : (g == own.getSelectedGroupAPI() ? 1 : 0);
        } catch (Throwable t) {
            if (!selReadThrewLogged) {
                selReadThrewLogged = true;
                PilotWatchLog.info("predict aim-rebase af-strict-outlier sel read threw (logged once): "
                        + t.getClass().getSimpleName());
            }
            return -1;
        }
    }

    /** A ray-spawned body (engine spawn type
     *  BALLISTIC_AS_BEAM / BEAM, the MovingRay class whose velocity is zero at sighting). WHY the
     *  spawn type and not the class name: it is the engine's own api-side declaration (the .proj
     *  {@code spawnType}), rename-proof. A throwing read logs once and answers false (the
     *  non-ray side — errs toward naming the row, never toward hiding it as benign). */
    static boolean isRaySpawnBody(DamagingProjectileAPI proj) {
        try {
            ProjectileSpawnType st = proj.getSpawnType();
            return st == ProjectileSpawnType.BALLISTIC_AS_BEAM || st == ProjectileSpawnType.BEAM;
        } catch (Throwable t) {
            if (!liveFallbackClassifyThrewLogged) {
                liveFallbackClassifyThrewLogged = true;
                PilotWatchLog.info("predict v16 aim-rebase isRaySpawnBody spawn-type read threw"
                        + " (any caller; logged once; counted as ray=false): " + t);
            }
            return false;
        }
    }

    /** Count one birth-heading live
     *  fallback into the legacy sum token AND its _ray/_other half, then the capped naming row
     *  ({@link #LIVE_FALLBACK_LOG_MAX}) so a non-ray fallback is namable by weapon + class. */
    void noteBirthHeadingLiveFallback(DamagingProjectileAPI proj, long tick, float deltaDeg) {
        dischargeAimRebaseBirthHeadingLiveFallback++;
        boolean ray = isRaySpawnBody(proj);
        if (ray) {
            dischargeAimRebaseBirthHeadingLiveFallbackRay++;
        } else {
            dischargeAimRebaseBirthHeadingLiveFallbackOther++;
        }
        if (liveFallbackLogLines >= LIVE_FALLBACK_LOG_MAX) {
            return;
        }
        liveFallbackLogLines++;
        PilotWatchLog.info("predict v16 aim-rebase live-fallback weapon=" + weaponIdOrNa(proj)
                + " class=" + proj.getClass().getSimpleName() + " dvs=live ray=" + ray
                + " tick=" + tick + " dTheta=" + deltaDeg + " n=" + liveFallbackLogLines
                + (liveFallbackLogLines == LIVE_FALLBACK_LOG_MAX
                        ? " [log cap reached; counters continue]" : ""));
    }
}
