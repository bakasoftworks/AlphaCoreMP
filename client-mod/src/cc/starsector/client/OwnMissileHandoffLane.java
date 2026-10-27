package cc.starsector.client;

import cc.starsector.client.net.ServerMissileState;
import com.fs.starfarer.api.combat.MissileAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashSet;
import java.util.Set;

/**
 * The OWN-missile HANDOFF lane, extracted from {@link MissilePoseLane}
 * (the ARMED deferred-refactors MPL split; cut re-adjudicated at pay time per
 * the crossing note: the second concern is own-missile HANDOFF, not nudge-correction).
 *
 * <p>CONCERN: own PREDICTED guided missiles ({@code Twin.own=true}) — link-only enroll at the
 * DamageWitness birth site → PURE LOCAL fire window (zero corrections; the
 * continuous nudge was retired as a permanent backward drag) → puppet HANDOFF at
 * {@link #OWN_HANDOFF_TICKS} with a short blend → ghost fade for twins the wire never
 * confirmed by X (the no-wire-record class). The wire-presentation concern (foe puppets,
 * presence-adopt, split moments) stays in the pose lane.
 *
 * <p>COUPLING (the FFC backref idiom): a pose-lane backref — the shared {@code Twin} ledger
 * ({@code twins}/{@code twinByWireId}) and {@code installPuppetAI}/{@code adoptPose} remain
 * the pose lane's; this lane owns the own-only knobs, counters, tombstones, the
 * enroll/drive/handoff/fade law, and its OWN capped per-event logger —
 * the shared budget starved 88% of a recorded run's handoff forensics. The
 * pose lane keeps the shared iteration passes (link claim, absence, prune) and dispatches the
 * own arms here — the ChatLane thin-relay shape.
 */
final class OwnMissileHandoffLane {

    /** The OWN-missile knob: own PREDICTED guided missiles
     *  fly PURE LOCAL through the fire window (zero corrections — the
     *  continuous nudge was retired as a permanent backward drag), then HAND OFF to a foe-style wire
     *  puppet at {@link #OWN_HANDOFF_TICKS} with a short blend (the hybrid,
     *  adopted PRIMARY). {@code meter} = the would-be-glide measurement lane, no writes.
     *  Default {@code apply}
     *  (the foeHullReconcile precedent); {@code meter}/{@code off} stay selectable. */
    static final String OWN_MISSILE_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.ownMissile", System.getProperty("cc.predict.ownMissile", "apply"));
    static final boolean OWN_MISSILE_METER = !"off".equals(OWN_MISSILE_MODE);
    static final boolean OWN_MISSILE_APPLY = "apply".equals(OWN_MISSILE_MODE);
    /** own-nudge glide rate (per second), the PRCP reconcile
     *  time-constant idiom (α = 1−exp(−rate·dt)). The continuous nudge was retired as the
     *  apply mechanism (spawn fork + unprojected target made it a permanent ~57 su/s backward
     *  drag per twin) — the rate now drives only the meter-side would-be measurement.
     *  Compiled 3.0 (E3 hardwire; the sysprop+env read is retired —
     *  the ownNudgeSum meter stays bit-identical, only its rate-sweep DOF goes, and nothing
     *  set it). */
    static final float OWN_NUDGE_RATE = 3.0f;
    /** Snap hatch — the PRCP SNAP_DISTANCE mirror: past this the
     *  server twin FORKED (re-acquire/flare-steal class). Also the handoff-blend POP
     *  threshold — a fork past it snaps to the wire pose instead of blending. Compiled 300
     *  (E3 hardwire; sysprop+env read retired). */
    static final float OWN_NUDGE_SNAP = 300f;
    private static final float OWN_NUDGE_SNAP_SQ = OWN_NUDGE_SNAP * OWN_NUDGE_SNAP;
    /** Hybrid handoff X in server sim ticks (~0.5 s at 60): the LOCAL FIRE WINDOW —
     *  zero corrections before it (launch feel stays local), puppet handoff at it. 30 clears
     *  BOTH the launch transient (mean 29° over the first 30 ticks) and the measured
     *  3–6-tick spawn fork; also the unlinked ghost-fade threshold. Compiled 30 (E3 hardwire;
     * sysprop+env read retired). */
    static final int OWN_HANDOFF_TICKS = 30;
    /** Handoff blend window in server sim ticks: the residual fork at X (~20–35 su
     *  measured) decays linearly over it instead of popping. 0 = instant pop (a
     *  legitimate feel experiment). Compiled 10 (E3 hardwire;
     *  sysprop+env read retired). */
    static final int OWN_HANDOFF_BLEND_TICKS = 10;
    /** Projection decay window (server sim ticks): post-handoff the presented pose
     *  leads the wire by chord × hz where hz = Δ × (1 − age/DECAY) — the per-twin measured
     *  horizon bleeds →0 so terminal presentation = TRUE wire pose (hit/miss stays honest).
     *  ~120 ≈ 2 s at 60; {@code 0} = projection OFF = raw adopt = the pre-projection shape (the
     *  no-re-roll rollback lever). Compiled 120 (E3 hardwire;
     *  sysprop+env read retired). */
    static final int OWN_HANDOFF_PROJ_DECAY_TICKS = 120;
    /** The GHOST window, SPLIT from {@link #OWN_HANDOFF_TICKS}
     *  ({@code ghostFadeDue} reusing the handoff window meant any handoff-at-link
     *  policy would flame out ~every never-linked twin at age 1). Default 60 = high-ping
     *  INSURANCE only (link age is ADDITIVE, ~4t base + 1t per 16.7 ms RTT — p99 6t
     *  @~40 ms / 19t @~250 ms with the 20–29 band EMPTY, so 30 only starts fading real fires
     *  near ~430 ms ping; 60 covers ~900 ms). NOT the fix for the fades: those were
     *  phantom CLIENT fires the server never made (the OwnFireLockLaw cd-zeroing defect)
     * — the fade killed them correctly, and no deadline saves a missile with no wire
     *  twin. Compiled 60 (E3 hardwire; sysprop+env read + the class-load
     *  range THROW retired — 60 ≤ {@link MissilePoseLane#LINK_WINDOW_TICKS} (120) now holds as a
     *  compiled invariant: past the link window the link-expiry RELEASE preempts the fade and the
     *  fade would silently never fire). */
    static final int OWN_GHOST_TICKS = 60;
    /** The per-twin PRESENTATION POLICY, latched at enroll (in-flight
     *  twins never change lane): {@code handoff} = today's hybrid bit-for-bit (X=30, blend,
     *  projection); {@code link} = PUPPET@LINK (the ship candidate — puppet handoff on the
     *  FIRST linked frame, hz off, blend off, facing raw, {@code cd ≡ 0} for life); {@code local}
     *  = never enroll (pure local flight, the original shape). {@code birth} is a RESERVED word
     *  (not built this session — arming it THROWS at class load, never a silent
     *  degrade). Default {@code link} — the OWNER ADOPTION after two graded blind A/B
     *  watches (link CLEAN @~40 ms, adopted at ~250 ms) ⇒ rollback to the hybrid is one knob
     *  word ({@code handoff}). */
    static final String OWN_MISSILE_POLICY = resolvePolicyKnob("cc.predict.ownMissilePolicy",
            System.getProperty("cc.predict.ownMissilePolicy"),
            System.getenv("CC_PREDICT_OWN_MISSILE_POLICY"));
    /** The ACTIVE base policy for NEW enrolls (volatile: flipped from the render-thread input
     *  pass, read at enroll on the same thread — volatile is belt-and-braces). The F8 toggle
     *  swaps it between {@code link} and {@code handoff} only ({@code local} disables the
     *  toggle — a mid-fight enroll gap would confound a grade). The blind
     *  START coin + A/B letter map are RETIRED with the adoption (the blind era ended
     *  with the grade); the start arm is simply the knob word and labels speak policy words. */
    private static volatile String activeBasePolicy = OWN_MISSILE_POLICY;

    /** Policy-word resolver (knob discipline: THROWS at class load on anything
     *  outside {handoff, link, local} — including the reserved {@code birth}, which names a
     *  policy this build does not carry). */
    private static String resolvePolicyKnob(String prop, String sysRaw, String envRaw) {
        String raw = sysRaw == null || sysRaw.isBlank() ? envRaw : sysRaw;
        if (raw == null || raw.isBlank()) {
            // Link is the ADOPTED default (was handoff, then the coin).
            return "link";
        }
        String v = raw.trim();
        if ("handoff".equals(v) || "link".equals(v) || "local".equals(v)) {
            return v;
        }
        throw new IllegalArgumentException(prop + " must be handoff|link|local"
                + " (birth is reserved, not built), got '" + v + "'");
    }

    /** Per-lane log budget (was: one {@code MISSILE_LOG_MAX=400} SHARED with the
     *  foe pose lane) — the shared cap exhausted at ~t2670 of a recorded run's ~t17155
     *  and blinded 88% of the run's HANDOFF/hz forensics incl. both F9 windows.
     *  Sized from that run's census: 254 enroll + 241 link + 227 HANDOFF + end/absence/fade
     *  lines ≈ 1.3k for a full graded round ⇒ 2000 covers it with headroom. */
    private static final int OWN_MISSILE_LOG_MAX = 2000;

    /** The pose lane — shared Twin ledger, puppet install, pose adopt. */
    private final MissilePoseLane pose;

    /** this lane's OWN capped per-event logger (the DemDronePresenter /
     *  FireLaneCoordinator per-lane idiom): own enroll/link/HANDOFF/GHOST/end lines no longer
     *  compete with foe pose-lane lines for one budget. */
    private final PredictReconcileCombatPlugin.PredictLane lane =
            new PredictReconcileCombatPlugin.PredictLane(OWN_MISSILE_LOG_MAX);

    /** Tombstone — own wire ids whose LOCAL body died ({@code pruneLocalGone}): the
     *  id is NEVER re-claimable (a later same-mount shot stole freed ids in 5.2% of a recorded run's
     *  claims, 620–1704 su mispairs) and own ids are never presence-adopted anyway; pruned
     *  against the live-id set each fresh frame, round-clear mirror. */
    private final Set<Long> ownDeadIds = new HashSet<Long>();

    // === Cumulative counters (survive rounds — the FoeHull totals discipline) ===
    // Own-missile NUDGE counters (predict_own_missile_* tokens)
    int ownEnrolled;         // predict_own_missile_enrolled (link-only own twins)
    int ownMineEnrolled;     // predict_own_mine_enrolled (weaponless own mine twins)
    int ownMineLinked;       // predict_own_mine_linked (claimed by the (own,-1) wire pair)
    float ownMineLinkForkMax;// predict_own_mine_link_fork_max (|local − wire| su at link: the pop)
    int ownMineFadeRemoved;  // predict_own_mine_fade_removed (own mine twins the
                             //   wire never confirmed — REMOVED, never flameOut-faded; each one is
                             //   a client-only detonation that did NOT happen)
    int ownMineFizzleSkipped; // predict_own_mine_fizzle_skipped (a MINE's wire
                             //   FIZZLING pre-handoff is left to vanilla's own fuse AI, never
                             //   flameOut-relayed — mirrors MissilePoseLane's mineFizzleSkipped)
    int ownMineNearestOverLowest; // predict_own_mine_nearest_over_lowest (the
                             //   nearest-record claim picked a HIGHER id than the lowest unclaimed
                             //   one — each = a FIFO cross-pair avoided)
    int ownLinked;           // predict_own_missile_linked (own twin claimed its wire id)
    int ownLinkExpired;      // predict_own_missile_link_expired (released, body untouched)
    int ownNudgeFrames;      // predict_own_missile_nudge_frames (fresh frames corrected/measured)
    int ownNudgeSnaps;       // predict_own_missile_nudge_snaps (snap-hatch trips)
    double ownNudgeSum;      // predict_own_missile_nudge_sum (su; applied — or would-be at meter)
    float ownNudgeErrMax;    // predict_own_missile_nudge_err_max (su; largest pre-glide residual)
    int ownFizzleRelays;     // predict_own_missile_fizzle_relays (wire FIZZLING -> flameOut; TWO
                             //   sites: pre-handoff (real cap, never parked) + the puppet relay)
    // the same TWO sites split by the wire DUD bit.
    int ownDudRelays;        // predict_own_missile_dud_relayed (relays whose record said DUD)
    int ownArmedRelays;      // predict_own_missile_armed_relayed (no DUD bit = armed)
    int ownDudFlips;         // predict_own_missile_dud_flips (the bit moved after the
                             //   pre-handoff relay latch — arming clock — and was re-applied)
    int ownFizzleRelaysParked; // predict_own_missile_fizzle_relay_parked (the own share
                             //   of puppet relays that found flightTime parked at the cap)
    int ownPuppetEndFizzling;// predict_own_missile_puppet_end_fizzling (puppet wire
                             //   ends whose body was fizzling/fading = the death arc WAS playing)
    int ownEndFaded;         // predict_own_missile_end_faded (absence -> flameOut fade)
    int ownSplitReleased;    // predict_own_missile_split_released (MISSILE_SPLIT drops tracking)
    int ownLocalGone;        // predict_own_missile_local_gone (local body left play first)
    // Hybrid-handoff counters (predict_own_missile_* tokens)
    int ownHandoffs;         // predict_own_missile_handoffs (puppet handoffs fired)
    double ownHandoffForkSu; // predict_own_missile_handoff_fork_su (su; fork found at X, cum.)
    int ownHandoffGhostFaded;// predict_own_missile_ghost_faded (no wire record by X)
    int ownHandoffExempt;    // predict_own_missile_handoff_exempt (splitCapable enrolls, local for life)
    int ownPuppetEndRemoved; // predict_own_missile_puppet_end_removed (post-handoff wire end)
    // Counter split — the handoff-pop site no longer shares ownNudgeSnaps with the
    // measure-only site (the one-counter instrument defect)
    int ownHandoffPops;      // predict_own_missile_handoff_pops (residual past the hatch at X)
    double ownHandoffResidSu;// predict_own_missile_handoff_resid_su (su; PROJECTED residual at X, cum.
                             //   — the headline A/B metric vs the RAW _handoff_fork_su)
    // the PUPPET@LINK receipts (the percept≠metric fix: time-derivative
    // + unconditional-pop instruments; legacy tokens above stay untouched for comparability)
    int ownPolicyLinkEnrolls;    // predict_own_missile_policy_link_enrolls
    int ownPolicyHandoffEnrolls; // predict_own_missile_policy_handoff_enrolls
    int ownPolicyLocalSkips;     // predict_own_missile_policy_local_skips (no twin minted)
    int ownModeFlips;            // predict_own_missile_mode_flips (always 0 since the F8
                                 //   toggle was deleted; token kept so old-log joins still parse)
    int ownLinkForkCount;        // predict_own_missile_link_fork_count (fork measured at link)
    double ownLinkForkSu;        // predict_own_missile_link_fork_su (su; |local-wire| at link, cum.
                                 //   — the number the pivot rides on, never measured before this counter)
    float ownLinkForkMax;        // predict_own_missile_link_fork_max
    int ownLinkForkBackCount;    // predict_own_missile_link_fork_back_count (ship moving backward
                                 //   — the link-vs-birth discriminator stratum)
    double ownLinkForkBackSu;    // predict_own_missile_link_fork_back_su
    int ownAdoptPops;            // predict_own_missile_adopt_pops (UNCONDITIONAL — every handoff
                                 //   counts its ACTUAL presented jump; an earlier counting
                                 //   gap meant blend=0 pops could silently read as zero)
    double ownAdoptPopSu;        // predict_own_missile_adopt_pop_su (su; presented jump, cum.)
    float ownAdoptPopMax;        // predict_own_missile_adopt_pop_max
    int ownCdNonzeroFrames;      // predict_own_missile_cd_nonzero_frames (link-policy twins ONLY;
                                 //   any nonzero = build defect, void the feel grade)
    float ownCorrRateMax;        // predict_own_missile_corr_rate_max (su/s; d|cd|/dt — the eye's
                                 //   quantity: ≲120 tolerable, ≳250 fail)
    int ownCorrRateFrames;       // predict_own_missile_corr_rate_frames
    int ownCorrRateGt120;        // predict_own_missile_corr_rate_gt120
    int ownCorrRateGt250;        // predict_own_missile_corr_rate_gt250
    int ownNudgeSnapMissiles;    // predict_own_missile_nudge_snap_missiles (distinct TWINS — the
                                 //   re-cut; the frame-counting ownNudgeSnaps stays)

    OwnMissileHandoffLane(MissilePoseLane pose) {
        this.pose = pose;
        try {
            // Init echo — UNCAPPED (never rides the event budget): the arming
            // receipt for every own-lane knob. Without it a mis-armed env (the detach ferry
            // drops caller-shell $env:) grades default values as the tuned run undetectably.
            PilotWatchLog.info("predict own-missile knobs: mode=" + OWN_MISSILE_MODE
                    + " policy=" + OWN_MISSILE_POLICY
                    + " nudgeRate=" + OWN_NUDGE_RATE
                    + " nudgeSnap=" + OWN_NUDGE_SNAP
                    + " handoffTicks=" + OWN_HANDOFF_TICKS
                    + " blendTicks=" + OWN_HANDOFF_BLEND_TICKS
                    + " projDecayTicks=" + OWN_HANDOFF_PROJ_DECAY_TICKS
                    + " ghostTicks=" + OWN_GHOST_TICKS
                    + " logMax=" + OWN_MISSILE_LOG_MAX
                    // The LIVE arm receipt: policy= is the knob word, active=
                    // is what is actually FLYING; they differ after an F8 flip, and the flip
                    // survives combat RE-ENTRY (activeBasePolicy is static, the echo runs per
                    // lane ctor — without active= a fight-2 echo would re-print the knob word
                    // while the flipped arm kept flying). abMap/start=/armed= are retired.
                    + " active=" + activeBasePolicy);
        } catch (Throwable ignore) {
            // presentation lane; never break plugin construction
        }
    }

    /**
     * LINK-ONLY enroll of an OWN PREDICTED guided missile at its DamageWitness
     * birth site: the local body stays the flying truth — seeker KEPT, AI/velocity/facing
     * untouched, no puppet install, no maxFlightTime touch. The twin exists so the link pass
     * can claim its own-sourced wire id and {@link #nudgePose} can glide. Guided only (the
     * ratified scope; own flares/ballistic keep today's unmanaged local flight; own
     * MIRV children likewise — the stage-1 nudge ends at the split, {@code onSplit}).
     * NB the call site rides {@code DamageWitness.onOwnDischarge}, which is gated by
     * {@code cc.predict.dmgWitness} (default ON): witness-off starves this lane, and the
     * watch assert catches it (no cdx tokens ⇒ no apply grade).
     */
    void enrollOwn(Object spawned, long sourceEntityId, int sourceMountIndex, long spawnTick) {
        if (!OWN_MISSILE_METER || !(spawned instanceof MissileAPI)) {
            return;
        }
        try {
            MissileAPI missile = (MissileAPI) spawned;
            if (!missile.isGuided() || missile.isFlare()) {
                return;
            }
            // Policy LATCH: the ACTIVE base policy (the A/B toggle only ever
            // steers NEW enrolls; a latched twin never changes lane). The bySpec override
            // table retired never-populated (E3 — shape in git).
            String specId = MissilePoseLane.specIdOf(missile);
            String policy = activeBasePolicy;
            if ("local".equals(policy)) {
                ownPolicyLocalSkips++;
                lane.log("predict own-missile enroll SKIP (policy=local): src="
                        + sourceEntityId + " mount=" + sourceMountIndex + " tick=" + spawnTick);
                return; // pure local flight for life — the original shape, no twin
            }
            // Clamp the pre-first-snapshot Long.MIN_VALUE sentinel (the PRCP
            // call sites) — unclamped it overflows the expiry compare (never expires)
            // and sorts FIFO-first forever (steals later ids). The PRCP/FFC idiom.
            MissilePoseLane.Twin twin = new MissilePoseLane.Twin(missile, sourceEntityId,
                    sourceMountIndex, Math.max(0L, spawnTick), false, specId, true);
            twin.policyLink = "link".equals(policy);
            if (twin.policyLink) {
                ownPolicyLinkEnrolls++;
            } else {
                ownPolicyHandoffEnrolls++;
            }
            try {
                // Handoff-exempt gate, read while the factory AI is still intact:
                // behavior params = the MIRV family — vanilla splits it locally, so it keeps
                // pure local flight (a handoff would orphan its wire-side children).
                twin.splitCapable = missile.getBehaviorSpecParams() != null;
            } catch (Throwable ignore) {
                // unreadable params = treated plain (handoff-eligible); the fail direction is
                // cosmetic — a server split on a handed-off twin removes the body (onSplit)
            }
            if (twin.splitCapable) {
                ownHandoffExempt++;
            }
            pose.twins.put(missile, twin);
            ownEnrolled++;
            lane.log("predict own-missile enroll (link-only): src=" + sourceEntityId
                    + " mount=" + sourceMountIndex + " tick=" + spawnTick
                    + " policy=" + policy
                    + (twin.splitCapable ? " splitCapable" : "")
                    + (OWN_MISSILE_APPLY ? "" : " (meter)"));
        } catch (Throwable ignore) {
            // presentation lane; never break the discharge path it observes
            // A thrown enrollOwn silently loses the whole own-missile enroll.
            FallbackAnnounce.announceOnce("f8-own-enroll-threw", "enrollOwn threw "
                    + ignore.getClass().getSimpleName() + " -> own missile unmanaged");
        }
    }

    /**
     * The OWN
     * MINE twin. The local engine runs the piloted ship's Mine Strike itself and spawns a
     * WEAPONLESS mine ({@code spawnProjectile(ship, null, "minelayer2", …)});
     * the PRCP own-projectile scan drops weaponless bodies before the discharge witness, so no
     * twin existed, and the wire mine — degraded to (0,-1) by the server's weapon-keyed source
     * read — was adopted as a SECOND body: 14 sprites for 7 uses in the mark frames. The server
     * now emits the WEAPONLESS-SOURCED pair (own, -1) and this enroll mints the twin that pair
     * claims in the pose lane's link pass. Link policy ALWAYS (a mine is stationary: the first
     * linked frame hands off — puppet AI installed, pose := wire, one pop inside the spec's
     * 0.5 s fade-in); {@code sourcelessMine} so the mine-end lane may paint the wire end.
     * Never suppresses or delays the local spawn. Unclaimed
     * (the server refused the use) ⇒ ghost-fades like any own twin; an own wire mine with no
     * local newborn stays adoptable on the sourceless arm (fail-open = the pre-fix shape).
     */
    void enrollOwnMine(Object spawned, long sourceEntityId, long spawnTick) {
        if (!OWN_MISSILE_METER || !(spawned instanceof MissileAPI)) {
            return;
        }
        try {
            MissileAPI missile = (MissileAPI) spawned;
            if (!missile.isMine() || pose.twins.containsKey(missile)) {
                return;
            }
            if ("local".equals(activeBasePolicy)) {
                ownPolicyLocalSkips++;
                return;
            }
            String specId = MissilePoseLane.specIdOf(missile);
            MissilePoseLane.Twin twin = new MissilePoseLane.Twin(missile, sourceEntityId,
                    ServerMissileState.NO_SOURCE_MOUNT, Math.max(0L, spawnTick), false, specId, true);
            twin.policyLink = true;
            twin.sourcelessMine = true;
            pose.twins.put(missile, twin);
            ownMineEnrolled++;
            lane.log("predict own-mine enroll (link-only): src=" + sourceEntityId
                    + " spec=" + specId + " tick=" + spawnTick
                    + " ai=" + (twin.mine ? "native" : "puppet")
                    + placementTokens(missile)
                    + (OWN_MISSILE_APPLY ? "" : " (meter)"));
        } catch (Throwable ignore) {
            // presentation lane; never break the scan that observes the newborn
        }
    }

    /** The PLACEMENT instrument on the enroll row: where the LOCAL
     *  MineStrikeStats put the newborn relative to the own ship and its aim point
     *  ({@code getMouseTarget()}), joined against the server's {@code cc-mine-spawn} row by
     *  id. A logged run reconstructed the client mine ~990 su straight ahead vs the server's
     *  ~420 su off the nose — PLAUSIBLE, unmeasured; this measures it. Fail-soft: an
     *  unreadable term prints {@code ?}. */
    private String placementTokens(MissileAPI missile) {
        try {
            Vector2f m = missile.getLocation();
            com.fs.starfarer.api.combat.ShipAPI ship = pose.plugin.ownShip;
            Vector2f s = ship != null ? ship.getLocation() : null;
            if (m == null || s == null) {
                return " loc=? ship=?";
            }
            float dx = m.x - s.x;
            float dy = m.y - s.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float bearing = norm180((float) Math.toDegrees(Math.atan2(dy, dx)) - ship.getFacing());
            Vector2f aim = ship.getMouseTarget();
            String aimTok = " aim=?";
            if (aim != null) {
                float ax = aim.x - s.x;
                float ay = aim.y - s.y;
                float mx = m.x - aim.x;
                float my = m.y - aim.y;
                aimTok = " aim=" + (int) aim.x + "," + (int) aim.y
                        + " aimDist=" + (int) Math.sqrt(ax * ax + ay * ay)
                        + " mineToAim=" + (int) Math.sqrt(mx * mx + my * my);
            }
            // mineFacing = the LOCAL newborn's facing, paired by id
            // with the server row's mineFacing= for the link-snap read (facing= stays the SHIP's).
            return " loc=" + (int) m.x + "," + (int) m.y + " ship=" + (int) s.x + "," + (int) s.y
                    + " facing=" + (int) ship.getFacing() + " mineFacing=" + (int) missile.getFacing()
                    + " dist=" + (int) dist + " bearing=" + (int) bearing + aimTok;
        } catch (Throwable ignore) {
            return " loc=? (unreadable)";
        }
    }

    /** The link-pass own arm: link is LINK-ONLY for own twins — the measure lane
     *  seeds its wire baseline here; a link landing PAST the window hands off
     *  immediately ({@link #drive} decides), and a {@code link}-policy twin hands off HERE
     *  (the first linked frame IS its X). */
    void onLinked(MissilePoseLane.Twin t, ServerMissileState rec, long tick) {
        ownLinked++;
        boolean ownMine = t.sourceMountIndex == ServerMissileState.NO_SOURCE_MOUNT; //
        if (ownMine) {
            ownMineLinked++;
        }
        // The per-twin projection horizon Δ = linkTick − spawnTick (clamped
        // [0,15]t): in client-visible tick space this IS upstream birth fork + transport +
        // interp delay (the 6–9t budget, measured per twin, not assumed). The clamp
        // also absorbs the sentinel-clamped round-edge enroll (spawnTick=0 ⇒ a
        // huge raw Δ ⇒ 15t cap, not a runaway lead).
        t.horizonTicks = (int) Math.min(15L, Math.max(0L, tick - t.spawnTick));
        // Fork@link, the never-before-measured number the pivot rides on
        // (closing an evidence gap): |local − wire| at the first linked frame, stratified by
        // own-SHIP motion direction (shipAlong<0 = backward, the link-vs-birth discriminator).
        float fork = -1f;
        float shipAlong = Float.NaN;
        Vector2f loc = t.missile.getLocation();
        if (loc != null) {
            float fdx = loc.x - rec.posX();
            float fdy = loc.y - rec.posY();
            fork = (float) Math.sqrt(fdx * fdx + fdy * fdy);
            ownLinkForkCount++;
            ownLinkForkSu += fork;
            if (fork > ownLinkForkMax) {
                ownLinkForkMax = fork;
            }
            if (ownMine && fork > ownMineLinkForkMax) {
                ownMineLinkForkMax = fork; // The mine pop the grade reads
            }
            try {
                com.fs.starfarer.api.combat.ShipAPI ship =
                        pose.plugin.engine != null ? pose.plugin.engine.getPlayerShip() : null;
                Vector2f v = ship != null ? ship.getVelocity() : null;
                if (v != null) {
                    double rad = Math.toRadians(ship.getFacing());
                    shipAlong = (float) (v.x * Math.cos(rad) + v.y * Math.sin(rad));
                    if (shipAlong < 0f) {
                        ownLinkForkBackCount++;
                        ownLinkForkBackSu += fork;
                    }
                }
            } catch (Throwable ignore) {
                // stratification is a nicety; the fork number stands alone
            }
        }
        lane.log("predict own-missile link: id=" + t.wireId + " src="
                + t.sourceEntityId + " mount=" + t.sourceMountIndex
                + " spawnTick=" + t.spawnTick + " linkTick=" + tick
                + " hz=" + t.horizonTicks + " fork=" + fork + " shipAlong=" + shipAlong
                + " policy=" + (t.policyLink ? "link" : "handoff"));
        if (t.policyLink) {
            // Structural cd≡0: a link-policy twin never projects (hz basis
            // zeroed AFTER the log captured the measured Δ) and never blends.
            t.horizonTicks = 0;
        }
        drive(t, rec, tick);
    }

    // toggleAbMode (the F8 A/B flip) DELETED — the handoff arm won
    // its adoption and the toggle went caller-less with the key. activeBasePolicy now
    // only ever holds its boot value (the adopted policy from OWN_MISSILE_POLICY).

    /** The per-linked-own-twin fresh-frame decision (dispatched from the pose
     *  lane's {@code driveOwnOrAdopt}): a live own twin MEASURES only (zero corrections —
     *  the continuous nudge is retired) until the handoff window closes, then puppets.
     *  Handoff-exempt ({@code splitCapable}) own twins stay measure-only local for life;
     *  meter mode never hands off. */
    void drive(MissilePoseLane.Twin t, ServerMissileState rec, long tick) {
        // A link-policy twin's X is its FIRST linked fresh frame — no window wait.
        if (OWN_MISSILE_APPLY && !t.splitCapable
                && (t.policyLink || tick - t.spawnTick >= OWN_HANDOFF_TICKS)) {
            handoff(t, rec, tick);
            return;
        }
        nudgePose(t, rec, tick);
    }

    /** The OWNER-proposed hybrid HANDOFF (adopted PRIMARY): after the local
     *  fire window the own missile becomes a foe-style wire puppet — puppet AI installed
     *  (seeker gone ⇒ no tug-of-war, no guidance fork; facing/vel wire-driven from here).
     *  PROJECTED handoff: the blend target at X is the wire pose forward-projected
     *  by the measured per-twin horizon Δ along the wire chord — the backward warp
     *  (p50 16 / p95 70 su on ~every handoff) was the RAW-adopt fork, first-order
     *  removed here because Δ measures exactly the birth-fork + transport + interp terms.
     *  The blend absorbs (and the snap hatch judges) the PROJECTED residual, not
     *  the raw fork. Facing captured at X slerps over the same window (snap was
     *  p50 12° / p90 36°). Apply-gated; pre-handoff mtrack pos lines stay RAW by
     *  construction (zero writes), post-handoff lines carry {@code ho=1}. */
    private void handoff(MissilePoseLane.Twin t, ServerMissileState rec, long tick) {
        t.handedOff = true;
        t.handoffTick = tick;
        ownHandoffs++;
        // Chord basis at X (su/tick): wire delta when a prior sample at an earlier
        // tick exists (the measure frames maintain lastWire*); LOCAL-velocity fallback when
        // dt=0 (link landed at/past X — the first wire record IS the handoff frame).
        long dt = t.lastWireTick == Long.MIN_VALUE ? 0L : tick - t.lastWireTick;
        if (dt > 0) {
            t.chordXPt = (rec.posX() - t.lastWireX) / dt;
            t.chordYPt = (rec.posY() - t.lastWireY) / dt;
        } else {
            Vector2f vel = t.missile.getVelocity();
            long rate = ServerTickRate.ticksPerSecond();
            if (vel != null && rate > 0) {
                t.chordXPt = vel.x / rate;
                t.chordYPt = vel.y / rate;
            }
        }
        float hz = t.policyLink ? 0f : hzTicks(t, tick);
        float raw = 0f;
        float resid = 0f;
        float pop = 0f;
        Vector2f loc = t.missile.getLocation();
        if (loc != null) {
            float rawDx = loc.x - rec.posX();
            float rawDy = loc.y - rec.posY();
            raw = (float) Math.sqrt(rawDx * rawDx + rawDy * rawDy);
            ownHandoffForkSu += raw; // stays RAW — 205838 comparability
            float dx = loc.x - (rec.posX() + t.chordXPt * hz);
            float dy = loc.y - (rec.posY() + t.chordYPt * hz);
            float residSq = dx * dx + dy * dy;
            resid = (float) Math.sqrt(residSq);
            ownHandoffResidSu += resid;
            // A link-policy twin NEVER blends — the correction budget is paid as
            // one honest pop at the muzzle (~20 su median expected), inside FX.
            if (!t.policyLink && residSq <= OWN_NUDGE_SNAP_SQ && OWN_HANDOFF_BLEND_TICKS > 0) {
                t.blendX = dx;
                t.blendY = dy;
                if (rec.hasPose()) {
                    // Local facing read BEFORE adoptPose overwrites it; norm180
                    // ⇒ the blend takes the shortest arc. Sentinel-pose records (no wire
                    // facing) skip the capture — their facing keeps today's fallback shape.
                    t.facingBlendDeg = norm180(t.missile.getFacing() - rec.facingDeg());
                }
            } else if (residSq > OWN_NUDGE_SNAP_SQ) {
                ownHandoffPops++; // The pop site's OWN counter (split from
                                  // ownNudgeSnaps); facing blend stays zeroed on pop
            }
            // The UNCONDITIONAL adopt-pop receipt: the ACTUAL presented
            // jump this handoff frame (blend engaged ⇒ presented == local ⇒ 0; otherwise the
            // residual pops). The legacy branch could read 0 exactly when every handoff
            // IS a pop (blend=0, resid ≤ snap) — this counter cannot.
            pop = (t.blendX != 0f || t.blendY != 0f) ? 0f : resid;
            ownAdoptPops++;
            ownAdoptPopSu += pop;
            if (pop > ownAdoptPopMax) {
                ownAdoptPopMax = pop;
            }
        }
        t.thrustAi = pose.installPuppetAI(t.missile);
        lane.log("predict own-missile HANDOFF: id=" + t.wireId + " tick=" + tick
                + " age=" + (tick - t.spawnTick) + "t raw=" + raw + "su resid=" + resid
                + "su pop=" + pop + "su hz=" + hz + "t blend="
                + ((t.blendX != 0f || t.blendY != 0f) ? OWN_HANDOFF_BLEND_TICKS : 0) + "t"
                + " policy=" + (t.policyLink ? "link" : "handoff"));
        pose.adoptPose(t, rec, tick);
    }

    /** The decaying projection horizon (ticks): Δ × max(0, 1 − age/DECAY);
     *  0 when the knob is 0 (raw adopt — the rollback shape) or the twin measured no
     *  horizon. */
    private static float hzTicks(MissilePoseLane.Twin t, long tick) {
        if (OWN_HANDOFF_PROJ_DECAY_TICKS <= 0 || t.horizonTicks <= 0) {
            return 0f;
        }
        long age = tick - t.handoffTick;
        if (age >= OWN_HANDOFF_PROJ_DECAY_TICKS) {
            return 0f;
        }
        return t.horizonTicks * (1f - age / (float) OWN_HANDOFF_PROJ_DECAY_TICKS);
    }

    /** Signed shortest-arc degrees in (−180, 180]. */
    private static float norm180(float deg) {
        float d = deg % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /** The own-twin FRESH-frame divergence MEASURE: the
     *  continuous position nudge is RETIRED as an apply mechanism (the unprojected stale
     *  target made it a permanent ~57 su/s backward drag per twin, 19% of missile speed).
     *  Pre-handoff apply frames and meter mode both measure the would-be glide (rate/snap
     *  knobs) WITHOUT touching the body. Terminal relays unchanged: one-shot wire-FIZZLING
     *  relay = direct {@code flameOut()} — pre-handoff own missiles keep their REAL
     *  {@code maxFlightTime}, so local fizzle state stays wire-comparable. The first frame
     *  after enroll/link only seeds the wire baseline ({@code dt=0} ⇒ no measurement). */
    private void nudgePose(MissilePoseLane.Twin t, ServerMissileState rec, long tick) {
        t.missedFresh = 0;
        ownNudgeFrames++;
        long dt = t.lastWireTick == Long.MIN_VALUE ? 0L : tick - t.lastWireTick;
        if (dt > 0) {
            Vector2f loc = t.missile.getLocation();
            if (loc != null) {
                float dx = rec.posX() - loc.x;
                float dy = rec.posY() - loc.y;
                float errSq = dx * dx + dy * dy;
                float err = (float) Math.sqrt(errSq);
                if (err > ownNudgeErrMax) {
                    ownNudgeErrMax = err;
                }
                if (errSq > OWN_NUDGE_SNAP_SQ) {
                    ownNudgeSnaps++;
                    ownNudgeSum += err;
                    if (!t.snapLatched) {
                        // The MISSILE-cut snap counter (judge: 004140's "26
                        // snaps" was ONE twin re-tripping per frame; the frame counter stays
                        // for comparability, this one counts distinct twins).
                        t.snapLatched = true;
                        ownNudgeSnapMissiles++;
                    }
                    lane.log("predict own-missile fork past snap (measure-only): id="
                            + t.wireId + " tick=" + tick + " err=" + err);
                } else {
                    float alpha = 1f - (float) Math.exp(-OWN_NUDGE_RATE * dt
                            / (double) ServerTickRate.ticksPerSecond());
                    float cx = alpha * dx;
                    float cy = alpha * dy;
                    ownNudgeSum += Math.sqrt(cx * cx + cy * cy);
                }
            }
        }
        if (rec.fizzling() && !t.fizzled && t.mine) {
            // Mirroring MissilePoseLane's mine-gated relay: a MINE's wire
            // FIZZLING is its flight-time expiry, which vanilla's own fuse AI already turns into
            // ping → fuse → explode on the local body; a flameOut relay here would kill it mid-arc
            // (and IS a fuse trigger). t.fizzled stays FALSE so the wire-end explode() door fires.
            ownMineFizzleSkipped++;
        } else if (rec.fizzling() && !t.fizzled) {
            t.fizzled = true;
            t.dudApplied = rec.dud(); // the verdict this relay writes
            ownFizzleRelays++;
            // The local body takes the SERVER twin's dud roll
            // (never its own birth coin) before the death arc; counted in meter mode too.
            if (rec.dud()) {
                ownDudRelays++;
            } else {
                ownArmedRelays++;
            }
            if (OWN_MISSILE_APPLY) {
                try {
                    t.missile.setArmedWhileFizzling(MissileDudRelayLaw.armedOnRelay(true, rec.dud()));
                    t.missile.flameOut();
                } catch (Throwable ignore) {
                    // presentation nicety; the absence fade still ends the twin
                }
            }
            lane.log("predict own-missile fizzle relay: id=" + t.wireId + " tick=" + tick
                    + " dud=" + rec.dud() + (OWN_MISSILE_APPLY ? "" : " (meter)"));
        } else if (MissileDudRelayLaw.rearmOnFresh(rec.fizzling(), t.fizzled, rec.dud(), t.dudApplied)) {
            // The wire bit is !isArmed() and moves with the arming clock —
            // re-apply a moved verdict after the latch (idempotent field write; a mine never latches).
            t.dudApplied = rec.dud();
            ownDudFlips++;
            if (OWN_MISSILE_APPLY) {
                try {
                    t.missile.setArmedWhileFizzling(MissileDudRelayLaw.armedOnRelay(true, rec.dud()));
                } catch (Throwable ignore) {
                    // presentation nicety; the absence fade still ends the twin
                }
            }
        }
        if (t.lastWireTick != tick) {
            t.lastWireX = rec.posX();
            t.lastWireY = rec.posY();
            t.lastWireTick = tick;
        }
    }

    /** The post-handoff own-arm PRESENTATION law (the {@code adoptPose} own hook,
     *  called after {@code loc := wire}): presented = wire + chord × hz (the decaying
     *  projected horizon, (b)) + blend residual × f ((c)) — one convergence instead of 60
     *  tugs/s forever, and the projection lead bleeds →0 so terminal presentation = TRUE
     *  wire pose. {@code cdx/cdy} := the FULL presented-minus-wire offset
     *  each frame, so the offline differ recovers RAW wire divergence by subtraction again
     *  (un-vacuouses the leg (iii) receipt — an earlier build left the tokens writer-less).
     *  The chord refreshes on every frame with a prior wire sample ({@code lastWire*} is
     *  pre-update here — {@code adoptPose} writes it after the own hook). */
    void presentPose(MissilePoseLane.Twin t, Vector2f loc, ServerMissileState rec, long tick) {
        long dt = t.lastWireTick == Long.MIN_VALUE ? 0L : tick - t.lastWireTick;
        if (dt > 0) {
            t.chordXPt = (rec.posX() - t.lastWireX) / dt;
            t.chordYPt = (rec.posY() - t.lastWireY) / dt;
        }
        float hz = t.policyLink ? 0f : hzTicks(t, tick);
        float f = blendF(t, tick);
        float ox = t.chordXPt * hz + t.blendX * f;
        float oy = t.chordYPt * hz + t.blendY * f;
        // The correction-VELOCITY receipt (the percept: d|cd|/dt in su/s,
        // measured against the ≲120 tolerable / ≳250 fail band; cdx/cdy still hold LAST frame's
        // offset here). Fresh frames only (dt>0) — the same cadence the offsets change on.
        // The FIRST sample of a twin is SEEDING, never a measurement — cdx/cdy are
        // written ONLY below, so a handoff-policy twin's first post-handoff frame would read its
        // whole initial offset (chord × hz + blend at f=1) as one frame of correction: ~900–2700
        // su/s of pure bookkeeping on a frame whose presented position is provably continuous,
        // once per twin, poisoning corr_rate_max/gt120/gt250. It records the baseline and counts
        // nothing; link twins are structurally unaffected (their ox/oy ≡ 0).
        if (dt > 0 && t.corrSeeded) {
            float rdx = ox - t.cdx;
            float rdy = oy - t.cdy;
            float rate = (float) (Math.sqrt(rdx * rdx + rdy * rdy) / dt
                    * ServerTickRate.ticksPerSecond());
            ownCorrRateFrames++;
            if (rate > ownCorrRateMax) {
                ownCorrRateMax = rate;
            }
            if (rate > 120f) {
                ownCorrRateGt120++;
            }
            if (rate > 250f) {
                ownCorrRateGt250++;
            }
        }
        // The cd≡0 build-defect detector (judge kill criterion: ANY nonzero cd
        // under link voids the feel grade): measured, never assumed.
        if (t.policyLink && (ox != 0f || oy != 0f)) {
            ownCdNonzeroFrames++;
        }
        loc.x += ox;
        loc.y += oy;
        t.cdx = ox;
        t.cdy = oy;
        t.corrSeeded = true; // A baseline now exists — deltas measure from here
    }

    /** The shared blend factor (position residual AND facing ride the SAME
     *  window): linear 1→0 over {@link #OWN_HANDOFF_BLEND_TICKS}; self-clears all three blend
     *  fields at expiry (pop leaves them zero from birth). */
    private float blendF(MissilePoseLane.Twin t, long tick) {
        if (t.blendX == 0f && t.blendY == 0f && t.facingBlendDeg == 0f) {
            return 0f;
        }
        float f = OWN_HANDOFF_BLEND_TICKS <= 0 ? 0f
                : 1f - (tick - t.handoffTick) / (float) OWN_HANDOFF_BLEND_TICKS;
        if (f > 0f) {
            return f;
        }
        t.blendX = 0f;
        t.blendY = 0f;
        t.facingBlendDeg = 0f;
        return 0f;
    }

    /** Presented facing for a handed-off own twin (the {@code adoptPose} facing
     *  own hook, wire-pose branch only): wireFacing + captured offset × f over the SAME blend
     *  window (offset norm180'd at capture ⇒ shortest arc); identity once the window closes
     *  — kills the measured launch facing SNAP (p50 12° / p90 36° / 35 of 224
     *  >30°). */
    float presentedFacing(MissilePoseLane.Twin t, float wireFacingDeg, long tick) {
        float f = blendF(t, tick);
        return f > 0f ? wireFacingDeg + t.facingBlendDeg * f : wireFacingDeg;
    }

    /** The prune own arm: an own twin's local death TOMBSTONES its wire id —
     *  never freed for re-claim (the free-and-readopt self-heal is correct by polarity for
     *  FOE puppets only). */
    void tombstone(long wireId) {
        ownDeadIds.add(wireId);
    }

    /** The link-claim tombstone consult. */
    boolean isTombstoned(long wireId) {
        return ownDeadIds.contains(wireId);
    }

    /** Tombstone GC (live-id prune; unconditional — this lane can run with the
     *  pose lane off). */
    void gcTombstones(Set<Long> liveIds) {
        ownDeadIds.retainAll(liveIds);
    }

    /** The absence own arm, handed-off shape: a handed-off own twin IS a foe-style
     *  puppet — the wire end is the authoritative end and prompt removal is the faithful end
     *  shape; the mid-flight-pop rejection covered LOCAL-real bodies only
     *  (a handed-off twin ends like an enemy-ship missile). The pose lane removes the ledger entries. */
    void onPuppetAbsence(MissilePoseLane.Twin t, long tick) {
        ownPuppetEndRemoved++;
        boolean fizz = false; // was the death arc playing when the wire ended it?
        try {
            fizz = t.missile.isFizzling();
        } catch (Throwable ignore) {
            // unreadable body ⇒ counted as not fizzling
        }
        if (fizz) {
            ownPuppetEndFizzling++;
        }
        lane.log("predict own-missile puppet absence: id=" + t.wireId
                + " gone " + t.missedFresh + " fresh frames at tick=" + tick
                + " fizz=" + fizz + " -> remove");
        retireOwnPuppet(t);
    }

    /** The absence own arm, pre-handoff shape: twin-absence is the authoritative
     *  end, but removeEntity is REJECTED on own bodies (a mid-flight pop on the player's own
     *  ordnance) — the same flameOut fade as the fizzle relay closes the measured
     *  +21..+50-tick outlive class; the real fizzle window completes naturally.
     *  EXCEPT a MINE ({@link #fadeByRemoval}): it kept the engine fuse AI, and
     *  a fade is that AI's DETONATION trigger — reachable
     *  here on a wire-DESTROYED end, whose door returns with the body still in play
     *  (in {@code MineBombEndFxLane}). */
    void onAbsenceFade(MissilePoseLane.Twin t, long tick) {
        ownEndFaded++;
        boolean remove = fadeByRemoval(t); //
        lane.log("predict own-missile absence: id=" + t.wireId + " gone "
                + t.missedFresh + " fresh frames at tick=" + tick
                + " -> " + (remove ? "remove (mine: a fade would TRIGGER the kept fuse)"
                        : "flameOut fade") + (OWN_MISSILE_APPLY ? "" : " (meter)"));
        if (OWN_MISSILE_APPLY && !t.fizzled) {
            if (remove) {
                ownMineFadeRemoved++;
                retireOwnPuppet(t); // the engine's own silent despawn — no FX, no damage
                return;
            }
            try {
                t.missile.flameOut();
            } catch (Throwable ignore) {
                // presentation nicety; tracking still ends
            }
        }
    }

    /** TRUE when the unlinked own twin is due the GHOST kill (the no-wire-record class):
     *  at X with no wire record the body fades instead of flying ~9 s on local-only damage. */
    boolean ghostFadeDue(MissilePoseLane.Twin t, long tick) {
        // The GHOST window is its OWN knob (was OWN_HANDOFF_TICKS;
        // under handoff-at-link that reuse would flame out every never-linked twin at age 1).
        return t.own && OWN_MISSILE_APPLY && !t.splitCapable
                && tick - t.spawnTick >= OWN_GHOST_TICKS;
    }

    /** GHOST kill — flameOut, not removal (the mid-flight-pop law holds for
     *  a local-real body); tracking released. LOUD: measured link ages are 0–6t @~40 ms and
     *  15–19t @~250 ms ping, so every fade at the 60t default is signal — the one
     *  measured producer is a phantom client fire the server never made (the OwnFireLockLaw
     *  cd-zeroing defect; also: fire-stream blackout, server-side instant death, link
     *  defect). Accepted residual: a link arriving PAST the window leaves the server twin
     *  unpresented (measured ≈0 on a healthy wire). */
    void onGhostFade(MissilePoseLane.Twin t, long tick) {
        ownHandoffGhostFaded++;
        boolean remove = fadeByRemoval(t); //
        lane.log("predict own-missile GHOST fade at handoff window: src="
                + t.sourceEntityId + " mount=" + t.sourceMountIndex + " spawnTick="
                + t.spawnTick + " tick=" + tick + " (no wire record by X)"
                + (remove ? " -> remove (mine: an unconfirmed mine is a body the server never had)"
                        : " -> flameOut fade"));
        if (!t.fizzled) {
            if (remove) {
                // ghostFadeDue() already required OWN_MISSILE_APPLY.
                ownMineFadeRemoved++;
                retireOwnPuppet(t);
                return;
            }
            try {
                t.missile.flameOut();
            } catch (Throwable ignore) {
                // presentation nicety; tracking release still stands
            }
        }
    }

    /** The link-expire own arm: an own twin the wire never confirmed — RELEASE
     *  tracking, body untouched (local flight keeps; the original status quo for this shell). */
    void onLinkExpired(MissilePoseLane.Twin t, long tick) {
        ownLinkExpired++;
        lane.log("predict own-missile link EXPIRED: src=" + t.sourceEntityId
                + " mount=" + t.sourceMountIndex + " spawnTick=" + t.spawnTick
                + " tick=" + tick + " -> release (local flight keeps)");
    }

    /** The {@code retire} sibling for handed-off OWN puppets: gated on the OWN
     *  knob (handoffs only exist under it; the pose-lane knob must not decide). */
    private void retireOwnPuppet(MissilePoseLane.Twin t) {
        if (OWN_MISSILE_APPLY && pose.plugin.engine != null
                && pose.plugin.engine.isEntityInPlay(t.missile)) {
            pose.plugin.engine.removeEntity(t.missile);
        }
    }

    /** The own-fade TREATMENT law, pure: a twin that KEPT the engine fuse AI
     *  (a mine, {@link MissilePoseLane#keepsNativeAi}) ends by the engine's silent REMOVAL,
     *  never by {@code flameOut()}. {@code flameOut} parks flightTime past max
     *  = the body IS fading, and the fuse AI turns a fade
     *  into {@code trigger()} → {@code explode()} = a real DamagingExplosion the SERVER never
     *  dealt (client-only damage, incl. the own ship via MISSILE_FF — the never-conceal-desync
     *  class). Removal is same-tick: no FX is painted and no body is delayed.
     *  Static + pure: the selftest row (MineNativeFuseLawSmoke F5). */
    static boolean fadeByRemoval(MissilePoseLane.Twin t) {
        return t != null && t.mine;
    }

    /** The MISSILE_SPLIT own arm (dispatched from the pose lane's {@code onSplit}): drop
     *  tracking; a handed-off twin additionally loses its body (no local seeker left). */
    void onSplit(MissilePoseLane.Twin t, long id, long simTick) {
        ownSplitReleased++;
        pose.twins.remove(t.missile);
        pose.twinByWireId.remove(id);
        if (t.handedOff) {
            // Guard: a handed-off twin has NO local seeker left to split —
            // leaving the body would strand a driverless puppet (thrust + 1e9 flight
            // time). Remove it; the splitCapable exempt-gate makes this path
            // unreachable unless the behavior-params read failed at enroll.
            retireOwnPuppet(t);
            lane.log("predict own-missile split on HANDED-OFF twin: id=" + id
                    + " tick=" + simTick + " -> remove (exempt-gate miss)");
        } else {
            // An own stage-1's split is LOCAL-REAL — vanilla plays its own
            // moment and children on this client; the wire end of the stage-1 id must
            // never absence-fade a body about to split locally. Drop tracking only
            // (own MIRV children stay unmanaged — the original status quo).
            lane.log("predict own-missile split RELEASE: id=" + id + " tick="
                    + simTick);
        }
    }

    /** The cc-mtrack pos-line cumulative-correction suffix for an OWN twin under
     *  apply ({@code " cdx=<f> cdy=<f>"}), empty otherwise: additive tokens so the offline
     *  differ recovers RAW divergence by subtraction (mtrack samples AFTER lane writes) — and
     *  their PRESENCE is the watch-vehicle apply receipt, leg (iii). */
    String corrTokens(MissileAPI missile) {
        if (!OWN_MISSILE_APPLY) {
            return "";
        }
        MissilePoseLane.Twin t = pose.twins.get(missile);
        return t != null && t.own
                ? " cdx=" + t.cdx + " cdy=" + t.cdy + (t.handedOff ? " ho=1" : "")
                : "";
    }

    /** The lane's meter-line contribution (byte-identical token block — the graders join on
     *  these names; appended by the pose lane's {@code logMeter}). */
    String meterTokens() {
        return " predict_own_missile_enrolled=" + ownEnrolled
                + " predict_own_mine_enrolled=" + ownMineEnrolled //
                + " predict_own_mine_linked=" + ownMineLinked
                + " predict_own_mine_link_fork_max=" + ownMineLinkForkMax
                + " predict_own_mine_nearest_over_lowest=" + ownMineNearestOverLowest
                + " predict_own_mine_fade_removed=" + ownMineFadeRemoved
                + " predict_own_mine_fizzle_skipped=" + ownMineFizzleSkipped
                + " predict_own_missile_linked=" + ownLinked
                + " predict_own_missile_link_expired=" + ownLinkExpired
                + " predict_own_missile_nudge_frames=" + ownNudgeFrames
                + " predict_own_missile_nudge_snaps=" + ownNudgeSnaps
                + " predict_own_missile_nudge_sum=" + (float) ownNudgeSum
                + " predict_own_missile_nudge_err_max=" + ownNudgeErrMax
                + " predict_own_missile_fizzle_relays=" + ownFizzleRelays
                + " predict_own_missile_dud_relayed=" + ownDudRelays
                + " predict_own_missile_armed_relayed=" + ownArmedRelays
                + " predict_own_missile_dud_flips=" + ownDudFlips
                + " predict_own_missile_fizzle_relay_parked=" + ownFizzleRelaysParked
                + " predict_own_missile_puppet_end_fizzling=" + ownPuppetEndFizzling
                + " predict_own_missile_end_faded=" + ownEndFaded
                + " predict_own_missile_split_released=" + ownSplitReleased
                + " predict_own_missile_local_gone=" + ownLocalGone
                + " predict_own_missile_handoffs=" + ownHandoffs
                + " predict_own_missile_handoff_fork_su=" + (float) ownHandoffForkSu
                + " predict_own_missile_ghost_faded=" + ownHandoffGhostFaded
                + " predict_own_missile_handoff_exempt=" + ownHandoffExempt
                + " predict_own_missile_puppet_end_removed=" + ownPuppetEndRemoved
                + " predict_own_missile_handoff_pops=" + ownHandoffPops
                + " predict_own_missile_handoff_resid_su=" + (float) ownHandoffResidSu
                + " predict_own_missile_policy_link_enrolls=" + ownPolicyLinkEnrolls
                + " predict_own_missile_policy_handoff_enrolls=" + ownPolicyHandoffEnrolls
                + " predict_own_missile_policy_local_skips=" + ownPolicyLocalSkips
                + " predict_own_missile_mode_flips=" + ownModeFlips
                + " predict_own_missile_link_fork_count=" + ownLinkForkCount
                + " predict_own_missile_link_fork_su=" + (float) ownLinkForkSu
                + " predict_own_missile_link_fork_max=" + ownLinkForkMax
                + " predict_own_missile_link_fork_back_count=" + ownLinkForkBackCount
                + " predict_own_missile_link_fork_back_su=" + (float) ownLinkForkBackSu
                + " predict_own_missile_adopt_pops=" + ownAdoptPops
                + " predict_own_missile_adopt_pop_su=" + (float) ownAdoptPopSu
                + " predict_own_missile_adopt_pop_max=" + ownAdoptPopMax
                + " predict_own_missile_cd_nonzero_frames=" + ownCdNonzeroFrames
                + " predict_own_missile_corr_rate_max=" + ownCorrRateMax
                + " predict_own_missile_corr_rate_frames=" + ownCorrRateFrames
                + " predict_own_missile_corr_rate_gt120=" + ownCorrRateGt120
                + " predict_own_missile_corr_rate_gt250=" + ownCorrRateGt250
                + " predict_own_missile_nudge_snap_missiles=" + ownNudgeSnapMissiles;
    }

    /** Round reset (called from the pose lane's {@code resetForMatchReset}): per-round
     *  identity state clears — counters stay cumulative across rounds. */
    void resetForMatchReset() {
        ownDeadIds.clear();
    }
}
