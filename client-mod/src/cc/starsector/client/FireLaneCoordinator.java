package cc.starsector.client;

import cc.starsector.client.net.CcwrIntentSourceReader;
import cc.starsector.client.net.ClientGameplayEdgeEvent;
import cc.starsector.client.net.PredictSession;
import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import cc.starsector.server.runtime.PilotIntentFrame;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
/**
 * The OWN-FIRE-LOCK + DISCHARGE-RECONCILE lane COORDINATOR: the
 * in-plugin orchestration/state residue of the fire lanes, extracted VERBATIM from
 * {@link PredictReconcileCombatPlugin} (the deferred-refactors PRCP cohesion seam; method bodies
 * byte-identical modulo receivers — extraction map).
 * The pure law classes ({@link OwnFireLockLaw} / {@link DischargeReconcileLaw}) already lived
 * outside and STAY there; this class owns the lane STATE (pairing deques, treatment queues, the
 * mount re-arm latch, every lane counter) and the per-frame drivers the plugin calls.
 * Threading contract unchanged by the move: everything here is RENDER-THREAD only except
 * {@link #awsdLaneSeen} (net-thread write, volatile — the one cross-thread member).
 * Deliberately left behind in the plugin (inventory): {@code PredictLane},
 * {@code settleBoostClockAfterFluxPin} + its static boost-clock cache (foe-flux co-user),
 * the fire-decl send lane ({@code fireEdgeSeq}/{@code declareFireDischarge}), and the GAP-2
 * scan instruments.
 */
final class FireLaneCoordinator {

    /**
     * The owning plugin — the supplier of the mutable cross-lane state the moved bodies read
     * ({@code ownShip} rebinds across respawns; engine/presenter/fuseMeter/meter clocks).
     *
     */
    private final PredictReconcileCombatPlugin plugin;
    /** The own-discharge aim-rebase unit. BLANK final, built in the ctor AFTER {@code plugin} is set:
     *  a field initializer runs first and would capture a null plugin (every rebase NPE-swallowed).
     * */
    final AimRebaseLane aimRebase;

    FireLaneCoordinator(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
        this.aimRebase = new AimRebaseLane(plugin, this);
    }

    /**
     * The discharge agree/disagree pairing tolerance,
     * in server sim ticks: bounds the cross-JVM skew between a local sighting and its GPEE twin (the
     * agree case measured ±1 snapshot; 60 ticks = 1 s of margin). A later measurement REFUTED the
     * original "adjacent same-mount discharges cannot cross-pair inside the window" premise at MG
     * cadence (~6–10-tick refire ⇒ ~10 in-window same-mount shots): inside the tolerance, selection
     * now tie-breaks on aim (min |Δaim|, {@link #pollBest}) and the per-mount observed-cadence guard
     * skips the geometric rebase on any pair formed wider than the mount's own shot period
     * (crossshot-suspect class — a measured 175.47° kill-melee snap).
     */
    static long dischargeMatchTolTicks() {
        // ≈1 s (60 at default). De-static: per-call read so the
        // WELCOME-seeded rate is honored regardless of class-load order.
        return Math.round(1.0 * ServerTickRate.ticksPerSecond());
    }
    /** Cap for the per-miss discharge tick-pin lines (misses observed rare: 1 of 18 discharges in one measured run). */
    static final int DISCHARGE_MISS_LOG_MAX = 40;

    /**
     * The OWN-ship FIRE-PHASE LOCK lane, knob {@code cc.predict.ownFireLock}
     * = {@code off|meter|apply} (default {@code apply} since the E2 flip;
     * window-JVM only; own ship only). Decision logic is entirely the pure
     * {@link OwnFireLockLaw} (game-free contract);
     * the lane runs AFTER {@code maintainFluxReadout}/{@code maintainWeaponState} so flux_gap
     * stays the honest per-frame misprediction outcome meter (meter-samples-then-pin).
     * Invalid values THROW at class load.
     */
    static final String OWN_FIRE_LOCK_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.ownFireLock", System.getProperty("cc.predict.ownFireLock", "apply"));
    static final boolean OWN_FIRE_LOCK_METER = !"off".equals(OWN_FIRE_LOCK_MODE);
    static final boolean OWN_FIRE_LOCK_APPLY = "apply".equals(OWN_FIRE_LOCK_MODE);
    /** Per-event log cap for the own fire-lock lane (ammo/cd writes are divergence-driven, sparse). */
    static final int OWN_FIRE_LOCK_LOG_MAX = 60;
    /**
     * per-session cap of the {@code cd-phase-rebase-row} tell
     * (its OWN counter, never the 60-row lane cap above — one flight ran that cap out in round 0
     * and hid the 6 rebases behind a skipped voidblaster refire; LENGTHEN = the hypothesis this
     * row tests). A whole flight shows ~6 rows, so
     * 2000 is uncapped in practice; the cap only bounds a skew storm on the frame
     * thread. Past it the {@code ownlock_cd_phase_rebases} meter carries the count.
     */
    static final int CD_PHASE_REBASE_ROW_MAX = 2000;
    /**
     * per-combat cap of the {@code predict ownlock ammo}
     * row — its OWN counter ({@link #ownlockAmmoRowsLogged}), never the 60-row lane cap above. WHY:
     * a rig leg spent that shared cap in its first two bursts, so no regen burst carried a per-write
     * row and the in-step lost 4th shot stayed a guess.
     * A bound, never
     * unlimited: rig leg b1-leg8 ({@code sidelog-20261001-090153-seat1}) made 93 / 243 ammo writes
     * per round ({@code ownlock_ammo_writes} deltas).
     * At the bound the last row carries {@code [ammo row cap reached; counters continue]}; later
     * writes only count ({@code ownlock_ammo_writes}). The lost shot was since NAMED: the cooldown-tail
     * pre-debit, fixed by {@link OwnFireLockLaw#AMMO_HOLD_OWED}. DELETE TRIGGER: once a
     * post-fix rig z-leg reads {@code short=0}, this bound +
     * {@link #ownlockAmmoRowsLogged} go; the row returns to the shared lane cap.
     */
    static final int OWNLOCK_AMMO_ROW_MAX = 20000;
    /**
     * The OWN-ship BEAM-CYCLE phase lock, knob {@code cc.predict.ownBeamCycle}
     * = {@code off|meter|apply} (default {@code apply} — ships the (115)(f) fix; {@code meter} =
     * full state machine + census with ZERO writes, the A/B baseline arm; {@code off} =
     * kill-switch). All logic in {@link OwnBeamCycleLane} (class doc = the design + provenance);
     * the lane runs AFTER {@link #maintainOwnFireLock} each frame (mirror-then-grant order) and
     * SHIELDS its engaged mounts from that mirror's downward ammo write. Invalid values THROW at
     * class load.
     */
    static final String OWN_BEAM_CYCLE_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.ownBeamCycle", System.getProperty("cc.predict.ownBeamCycle", "apply"));
    static final boolean OWN_BEAM_CYCLE_METER = !"off".equals(OWN_BEAM_CYCLE_MODE);
    static final boolean OWN_BEAM_CYCLE_APPLY = "apply".equals(OWN_BEAM_CYCLE_MODE);
    /**
     * The OWN-discharge RECONCILE-TREATMENT lane
     * (fire-lane step 4), knob {@code cc.predict.dischargeReconcile} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip; window-JVM
     * only; own ship only). What the *_only meters could only COUNT, this lane RESOLVES:
     * a server_only expiry ⇒ ADOPT (spawn the authoritative bolt via the presenter's faithful
     * own-role path); a client_only expiry ⇒ RETIRE (fade the unconfirmed local bolt via the
     * engine's own reap/fizzle primitives); discrete-ordnance entries additionally take an
     * auth-vs-auth ammo-delta EARLY retire on the float-exact decode lane. Decision logic is
     * entirely the pure {@link DischargeReconcileLaw} (game-free contract);
     * counters count LAW DECISIONS in both
     * modes (meter/apply A/B cleanly); engine writes only under apply. Rides
     * {@code cc.predict.eventReplay} — the pairing machinery it treats only exists there.
     * Invalid values THROW at class load.
     */
    static final String DISCHARGE_RECONCILE_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.dischargeReconcile", System.getProperty("cc.predict.dischargeReconcile", "apply"));
    static final boolean DISCHARGE_RECONCILE_METER = !"off".equals(DISCHARGE_RECONCILE_MODE);
    static final boolean DISCHARGE_RECONCILE_APPLY = "apply".equals(DISCHARGE_RECONCILE_MODE);
    /** Per-event log cap for the discharge-reconcile lane (treatments are expiry-driven, sparse). */
    static final int DISCHARGE_RECONCILE_LOG_MAX = 60;

    long ownlockBoostClockZeros;     // ownlock sub-lane 1: same, own ship

    // The OWN fire-phase lock lane (see OWN_FIRE_LOCK_MODE / OwnFireLockLaw):
    // flux re-base (primary) + quiescent ammo apply + one-way cooldown nudge, own ship only,
    // FRESH frames only. Counters count LAW DECISIONS in both meter and apply modes (A/B-
    // comparable watches); the engine writes happen only under apply.
    final PredictReconcileCombatPlugin.PredictLane ownFireLockLane = new PredictReconcileCombatPlugin.PredictLane(OWN_FIRE_LOCK_LOG_MAX);
    // The own beam-cycle phase lock (see OWN_BEAM_CYCLE_MODE; all logic +
    // counters live in the lane class per the new-lane law).
    final OwnBeamCycleLane ownBeamCycle = new OwnBeamCycleLane();
    long ownlockFluxPins;        // sub-lane 1: curr(+hard) pins (apply) / would-pin (meter)
    long ownlockFluxBandSkips;   // FLUX_PIN inside OwnFluxPinLaw's band
    long ownlockFluxSkipsState;  // sub-lane 1: mutual-normal-state skips (vent/overload/unknown flags)
    long ownlockAmmoWrites;      // sub-lane 2: setAmmo writes / would-write
    long ownlockAmmoRowsLogged;  // sub-lane 2: ammo rows printed, against OWNLOCK_AMMO_ROW_MAX
    long ownlockAmmoSkipsBusy;   // sub-lane 2: quiescence-gate skips (chargeup/firing/burst)
    long ownlockCdNudges;        // sub-lane 3: setRemainingCooldownTo nudges / would-nudge
    long ownlockCdSkipsFast;     // sub-lane 3: FAST_MIN skips (vulcan class)
    long ownlockCdSkipsImplausible; // sub-lane 3: a shorten > plausibility bound
                                    // refused — this counter moving IS the stale-frame detector
    // one LOUD line per mount per combat — the refusal persists for the whole stale window,
    // and undeduped it burns the 60-line ownlock budget on one incident
    // (the counter carries the per-frame count, the line is the anchor)
    private final java.util.HashSet<Integer> ownlockSkipImplausibleLogged = new java.util.HashSet<Integer>();
    long ownlockCdSkipsBusy;     // sub-lane 3: burst-busy skips
    // refund-up / would-nudge deferred while the mount holds an
    // un-reconciled pending own local discharge (the per-frame re-arm loop's brake).
    long ownlockAmmoHoldPending; // sub-lane 2: refund-up hold-offs
    long ownlockCdHoldPending;   // sub-lane 3: nudge hold-offs
    // The mount RE-ARM LATCH — the hold-off persists past pending-clear
    // (pair OR retire) until server truth for the shot reaches the MOUNT tier. Root cause:
    // an ALTERNATING-group tube awaiting its rotation turn carries cd=0 + unchanged ammo, so the
    // post-retire refund+nudge pair re-armed it every ~31 ticks until the server's turn fired
    // (measured: client 8 fires/tube vs server 1). mountIndex → {armTick, gpeeTick,
    // timeoutTicks} (MIN_VALUE = unset; the press-release slot was RETIRED —
    // truth/timeout are the only releases now); render-thread only, like the ledger.
    final java.util.HashMap<Integer, long[]> mountRearmLatch = new java.util.HashMap<>();
    final OwnReloadHoldLane reloadHold = new OwnReloadHoldLane();
    final OwnBurstPhaseMeter burstPhase = new OwnBurstPhaseMeter(); // The three fire-chain edge rows
    long ownlockAmmoHoldLatched;  // sub-lane 2: refund-up latch hold-offs
    long ownlockAmmoHoldOwed;     // sub-lane 2: down writes held for an owed local shot
    long ownlockCdHoldLatched;    // sub-lane 3: nudge latch hold-offs
    long ownlockLatchArmed;       // latches armed (per pending-clear, incl. re-arms)
    long ownlockLatchRelTruth;    // released: carried frame passed the GPEE tick
    long ownlockLatchRelTimeout;  // released: native-scale fail-open (truth never came)
    final float[] ownlockFluxOut = new float[2]; // law out-params (render-thread only)
    final int[] ownlockAmmoOut = new int[1];
    final float[] ownlockCdOut = new float[1];
    // phase-edge cd re-base: OWN phase-state EDGES (auth FLAG_PHASED on known-flag
    // fresh frames, or the local hull's isPhased() edge) open a per-ship re-base window W ≈ the F1
    // grace; inside it the cd sub-lane bypasses FAST_MIN and re-bases BOTH directions (see
    // OwnFireLockLaw.CD_PHASE_REBASE — the s1 phase-edge autofire train storm's root fix).
    // Boolean baselines so the first observed frame seeds without minting a false edge.
    Boolean lastAuthPhased;
    Boolean lastLocalPhased;
    long phaseRebaseWindowUntil = Long.MIN_VALUE;
    /**
     * Which clock's edge LAST opened/extended the window
     * ({@code auth}/{@code local} — both edges in one frame ⇒ {@code local}, the later write;
     * {@code none} after a rewound-clock close) — written only in the three window branches of
     * {@link #updatePhaseRebaseWindow}, read by the cd-phase-rebase tell row. String constants:
     * no per-frame allocation. With {@link #cdPhaseRebaseRowsLogged} = that row's own count
     * against {@link #CD_PHASE_REBASE_ROW_MAX}.
     */
    String phaseRebaseOpener = "none";
    long cdPhaseRebaseRowsLogged;
    long ownlockPhaseWindows;    // sub-lane 3: phase-edge re-base windows opened
    long ownlockCdPhaseRebases;  // sub-lane 3: CD_PHASE_REBASE writes / would-write
    // CD_HOLD_PHASE_LENGTHEN, the FIRST refusal per mount per window (the law repeats it
    // every fresh frame until the gun fires); the mask clears wherever a window opens or closes.
    // Reader = the b4 phase channel + the flight grade's fire-chain row; delete with the tell row
    // once its acceptance bar holds on two flights.
    long ownlockCdPhaseLengthenRefused;
    long phaseLengthenRefusedMask;
    // The pair-seam lead drain (drainOwnLead) — writes,
    // the ticks they lengthened by, and pairs whose told wait asked for a drain but were left unwritten
    // (not the mount's latest local shot / already drained / ammo or chargeup weapon / gun idle / in
    // burst / fast / a bad rate). Reader = the b4 phase channel + the flight grade's fire-chain row.
    // Delete trigger: the server no longer serves a declared shot on its own cooldown clock.
    long ownlockCdLeadDrains;
    long ownlockCdLeadDrainTicks;
    long ownlockCdLeadDrainSkips;
    long cdLeadDrainRowsLogged;
    static final int CD_LEAD_DRAIN_ROW_MAX = 400;
    /** The drain's own meters: {@code ownlock_cd_lead_drain_threw}
     *  (a throw, never folded into the skips; announced once) and {@code ownlock_decl_wait_hist}, the
     *  GPEE v16 told waits of own paired shots in 8 buckets 0 / 1 / 2 / 3 / 4-5 / 6-8 / 9-12 / 13+
     *  ({@link #declWaitBucket}). Same reader + delete trigger as the drain counters above. */
    long ownlockCdLeadDrainThrew;
    final long[] ownlockDeclWaitHist = new long[8];
    /** Per own mount, the LOCAL shot tick the lead drain last
     *  wrote for (render thread): one write per mount per local cycle. Cleared with the round in
     *  {@code PredictReconcileCombatPlugin.finishMatchReset} (the tick space restarts there); no own-hull
     *  path clears it, nor any sibling per-mount map here — a stale tick can only skip one drain. */
    final java.util.HashMap<Integer, Long> mountLastDrainedShotTick = new java.util.HashMap<>();
    long ownlockCdHoldAuthArming; // window re-base refused, server mount arming
    long ownlockCdHoldAuthBurst;  // Nudge/re-base refused, carried frame sampled in a burst gap
    // sub-lane 4 — kill-edge dark-gun settle: own-hull epoch, bumped in respawnOwn +
    // finishMatchReset; while OwnFireLockLaw.disableSettleActive the own apply-DISABLE edge defers.
    int ownHullEpoch;
    long ownHullEpochBumpTick = Long.MIN_VALUE; // lastSnapshotSimTick at the bump
    int weaponStateDisableDeferred; // own disable edges deferred in the settle window (never silent)
    // sub-lane 5 — own-engine disagree detail: last lifecycle edge (respawn/MRST/kill).
    long lastLifecycleEdgeTick = Long.MIN_VALUE;


    // own-ship discharge agree/disagree state: authoritative WEAPON_DISCHARGE event ticks
    // and LOCAL discharge stamps (snapshot tick at first projectile sighting — the lane's anchor clock)
    // each awaiting a counterpart within DISCHARGE_MATCH_TOL_TICKS. Both deques stay tick-ordered; entries
    // the tolerance window has passed by are flushed to the *_only counters. The miss a player sees
    // (server fired, client cooldown hadn't ended) lands in dischargeServerOnly — countable for the first
    // time. Render-thread only (projectile scan + drain both run in advance()).
    // Route A aim-rebase: both sides now carry a payload past the tick stamp — the auth side
    // keeps the event's wire aim, the local side keeps the predicted projectile + its first-sighting
    // anchor — so an agree-pair can rebase the local projectile onto the authoritative ray
    // (rebaseOwnProjectile). Local entries hold a strong projectile ref for at most
    // DISCHARGE_MATCH_TOL_TICKS before expiry flushes them.
    final ArrayDeque<PendingAuthDischarge> pendingAuthDischarges = new ArrayDeque<>();
    final ArrayDeque<PendingLocalDischarge> pendingLocalDischarges = new ArrayDeque<>();
    /** pair-class: latest wire FLAG_AUTOFIRE per own mount ordinal (bits 0-63; ≥64 uncached). */
    long serverAfMountMask;
    /** pair-class: sim-tick of each own mount's last observed wire autofire flip. */
    final java.util.HashMap<Integer, Long> serverAfEdgeTick = new java.util.HashMap<>();
    int dischargeAgree;       // predict_event_rebase_own_discharge_agree
    int dischargeServerOnly;  // predict_event_rebase_own_discharge_server_only (the miss class a player sees)
    int dischargeClientOnly;  // predict_event_rebase_own_discharge_client_only
    int dischargeMissLines;   // tick-pin lines emitted (capped at DISCHARGE_MISS_LOG_MAX)
    // verdict-at-coverage+grace: the ADJUDICATION deadline the ADOPT (auth-side)
    // expiry loop uses, refreshed once per new snapshot tick from the live RTT (netPingMillis →
    // DischargeReconcileLaw.adjudicationGraceTicks; NaN/unmeasurable falls back to the full legacy
    // tolerance — fail-open). Coverage advancing past tick+G IS proof-of-no-counterpart under the
    // CTRL-lane lag bound; the PAIRING tolerance (pollBest*/falseLateMatch) deliberately stays
    // ±DISCHARGE_MATCH_TOL_TICKS — only WHEN a pending entry adjudicates shrinks, never what may pair.
    // ASYMMETRIC adjudication: the RETIRE (local-side) loop reverted to the FULL
    // tolerance — 180241 measured ~50% of fast retires at G=9 killing REAL bolts (manual-press pair
    // skew is structural ≈ intent-uplink + serve + event-arrival, Δ 10–16 > 2×RTT+pad; the s1 phase
    // band ran 23–56), and a Δ∈(G,60] twin now PAIRS normally instead of minting adopt+retire churn.
    // F1's real win was always the adopt half (server truth presents at ~RTT); a client-only bolt
    // gets the full pairing window before removal (retire_false_late → 0 by construction for Δ≤60).
    long dischargeGraceTicks = dischargeMatchTolTicks();
    long graceRefreshTick = Long.MIN_VALUE;
    long dischargeAdjudicatedCoverage; // adjudicated_coverage — ADOPT side, inside the legacy tolerance
    long dischargeAdjudicatedExpiry;   // adjudicated_expiry — ADOPT side, waited the full legacy tolerance (NaN-RTT/backstop)
    long dischargeRetireAtTol;         // retire_at_tol — RETIRE-side adjudications (always full tolerance, M4(1))

    // in-window pair candidates skipped by the mount gate, counted ONCE per pending
    // entry — each is a cross-mount mispair the tick-only head matcher would have minted (the
    // aim_rebase_max=107.55° tail class).
    int dischargeMountMismatch;      // ..._aim_rebase_mount_mismatch
    // Pairings minted while the mount gate was INERT — either side
    // carried the −1 sentinel (resolveOwnMountIndex null/unlisted weapon; GPEE mountIndex unresolved —
    // both reachable in normal ops), so the match degraded to legacy pair-any without touching
    // mount_mismatch. mismatch=0 only certifies mount-true pairing when this is 0 too.
    int dischargePairAnyFallback;    // ..._aim_rebase_pair_any_fallback
    // TOTAL formed pairs — the pair-any RATE denominator, so a grader reads
    // pair_any_fallback/pairs_formed off one totals line instead of an uncontextualized count.
    int dischargePairsFormed;        // ..._aim_rebase_pairs_formed
    /** suspect-gate floor: below ~8 ticks the min-gap estimate is linked-barrel/burst noise. */
    static final long CROSSSHOT_PERIOD_FLOOR_TICKS = 8;
    /** Per-mount cadence observation (render thread, like the pending deques): last LOCAL
     *  discharge tick + min successive positive gap = the mount's observed shot period. */
    final java.util.HashMap<Integer, Long> mountLastLocalDischargeTick = new java.util.HashMap<>();
    final java.util.HashMap<Integer, Long> mountMinLocalGapTicks = new java.util.HashMap<>();
    /** The own-adopt mount SEED state (render thread,
     *  like the cadence maps above; see {@link #seedOwnAdoptMount}): bit i of the mask = own mount i
     *  has discharged LOCALLY since {@code ownMountSeedBody} (held by identity) was bound — mounts
     *  ≥ 64 never seed; {@code own_adopt_mount_seeded} / {@code own_adopt_mount_seed_faults} ride
     *  the discharge-reconcile summary line; the seed row is capped at the constant. */
    long ownMountSeedMask;
    ShipAPI ownMountSeedBody;
    long ownAdoptMountSeeded;
    long ownAdoptMountSeedFaults;
    static final int OWN_ADOPT_MOUNT_SEED_ROWS_MAX = 40;

    // === Fire-lane step 4 — discharge RECONCILE-TREATMENT state (see
    // DISCHARGE_RECONCILE_MODE / DischargeReconcileLaw). Render-thread only, like the pairing
    // deques it treats. expireDischargePairs never acts inline (it also runs MID-SCAN from
    // recordLocalDischarge — queueing makes treatment-at-the-drain-site safe BY CONSTRUCTION);
    // processDischargeTreatments drains these queues at the drainEdgeEvents site, post-scan
    // same frame. ===
    final ArrayDeque<PendingAuthDischarge> adoptTreatmentQueue = new ArrayDeque<>();
    final ArrayDeque<PendingLocalDischarge> retireTreatmentQueue = new ArrayDeque<>();
    /** Adopted own-role presenter spawns awaiting the projectile scan's first sight — identity-consumed there
     *  FIRST so an adopted bolt skips ALL local-discharge accounting (counting it would mint a
     *  false client_only; re-DECLARING it would make the server fire it AGAIN). */
    final Set<Object> presentedOwnProj = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    /** Retired-(tick,mount) accounting ring ({@code {tick, mount}}, cap {@link DischargeReconcileLaw#RETIRED_RING_CAP},
     *  drop-oldest): a later ADOPT matching one within tolerance ⇒ retire_false_late (self-heal, honestly counted).
     *  The matched entry is CONSUMED at the adopt (one false retire counts ONCE —
     *  180241: 39 counts = 17 distinct because every subsequent adopt re-matched the same entry). */
    final ArrayDeque<long[]> retiredRecentRing = new ArrayDeque<>();
    /** ADOPTED-(tick,mount) recent ring (same shape/cap as {@link #retiredRecentRing},
     *  drop-oldest): a LOCAL discharge recording within tolerance of one is the late twin of a
     *  server-first pair whose auth entry the adopt already consumed — marked echo-class at record
     *  ({@link DischargeReconcileLaw#adoptEchoClass}), counted {@code retire_adopt_echo} at
     *  adjudication (dedup, excluded from the graded true-divergence numerator). */
    final ArrayDeque<long[]> adoptedRecentRing = new ArrayDeque<>();
    /** Recently instant-PAIRED local (mount, tick) keys ({@code {tick, mount}}, small drop-oldest ring): a
     *  same-(mount,tick) sibling recorded AFTER its twin already paired is the linked-barrel 1:N class too —
     *  without this ring the auth-first ordering would leave the sibling unflagged and RETIRE a real bolt
     *  (the frozen flag-both rule extended to already-departed siblings; same errs-missed-over-false principle). */
    final ArrayDeque<long[]> recentPairedLocalRing = new ArrayDeque<>();
    static final int RECENT_PAIRED_RING_CAP = 16;
    /** Per-mount PRIOR-fresh-frame authoritative ammo cache: mountIndex → {ammo, usesAmmo(0/1), simTick}.
     *  The scan runs BEFORE this frame's pose resolves, so record-time reads need the prior frame
     *  (refreshed post-compare in maintainDischargeReconcile). */
    final java.util.HashMap<Integer, long[]> dischargeMountAuthCache = new java.util.HashMap<>();
    /** Sticky decode-lane signal: any AWSD fold ever seen ⇒ the session is on the delta lane whose
     *  hold-last windows repeat stale mount ammo at fresh ticks — the FALSE-early-retire hazard
     *  (frozen) — so {@code exactAmmoLane} fails closed for the rest of the session.
     *  Written on the net thread, read on the render thread; sticky-one-direction ⇒ tear-proof. */
    volatile boolean awsdLaneSeen;
    final PredictReconcileCombatPlugin.PredictLane dischargeReconcileLane = new PredictReconcileCombatPlugin.PredictLane(DISCHARGE_RECONCILE_LOG_MAX);
    long dischargeAdoptCount;          // adopt_count — server_only expiries adopted / would-adopt
    long dischargeAdoptSkipUnresolved; // adopt_skip_unresolved — no live own body (post-kill window)
    long dischargeRetireRemoved;       // retire_removed — bolts faded via the engine's deferred reap
    long dischargeRetireFlameout;      // retire_flameout — missiles fizzled via flameOut()
    long dischargeRetireEarlyAmmo;     // retire_early_ammo — ammo-delta early retires (either primitive)
    long dischargeRetireSkipClass;     // retire_skip_class — beam/burst>1/multiSpawn (1:N under-emit classes)
    long dischargeRetireSkipClassSubEps; // retire_skip_class_subeps — server-unconfirmable sub-epsilon class
    long dischargeRetireSubEpsConfirmed; // retire_subeps_confirmed — sub-eps bolt retired because the server CONFIRMED that mount
    /** Mounts the server has emitted ≥1 authoritative discharge
     *  on this session. A sub-eps mount in here is provably COVERED (the poll path never emits that
     *  class — {@code DischargeReconcileLaw#subEpsConfirmable}), so its unconfirmed local bolts
     *  retire like any class-ok bolt instead of flying a full phantom flight. */
    private final java.util.BitSet dischargeAuthConfirmedMounts = new java.util.BitSet();
    // The three declarations below split
    // to one field per line (javadoc above its field); mount_index_rejected renamed mount_index_oob —
    // the out-of-range event is only counted (never rejected: it still flows to the matchers).
    /** Wire mountIndex ceiling - real getAllWeapons
     *  ordinals sit far below; a garbled/hostile one would grow the BitSet (~256 MB) on the render thread. */
    static final int MOUNT_INDEX_MAX = 256;
    /** Auth discharges whose mountIndex >= MOUNT_INDEX_MAX
     *  (discharge-reconcile mount_index_oob; counted, the BitSet set skipped, the event still matched). */
    long dischargeMountIndexOob;
    /** Own fire-lock reconcile throws (ownlock-meter
     *  ownlock_reconcile_threw; first logged once). */
    long ownlockReconcileThrew;
    long dischargeRetireSkipGone;      // retire_skip_gone — already engine-reaped (hit) or swept
    long dischargeRetireFalseLate;     // retire_false_late — retired bolt a later adopt proved real (self-healed)
    long dischargeRetireAdoptEcho;     // retire_adopt_echo — local twin of an already-adopted server shot (M4(3) dedup class)
    long dischargeEarlyDisqualified;   // ammo-delta entries demoted to the 1 s path (ambiguity, incl. lane)
    long dischargeEarlyDroppedLanded;  // ammo-delta entries whose shot landed server-side (auth ammo fell)

    /** The shared pending-entry
     *  face {@link #pollBest} matches over — tick/mount/mismatch-once accounting + the aim
     *  metric (NaN = unknown ⇒ that candidate falls back to the Δtick discriminator). The two
     *  classes stay distinct (asymmetric payloads: immutable wire event vs live projectile). */
    interface PendingDischargeKey {
        long pairTick();
        int pairMount();
        boolean mismatchCounted();
        void countMismatch();
        /** The candidate's aim for the min-|Δaim| tie-break; NaN = unknown. */
        float pairAimDeg();
    }

    /** A pending AUTHORITATIVE discharge — event tick + the rebase aim already
     *  resolved through {@link AimRebaseLane#rebaseAimDeg} (true flight bearing, or the ray fallback; NaN = both unbound).
     *  + the GPEE v1 mountIndex (−1 = unresolvable ⇒ legacy pair-any). */
    static final class PendingAuthDischarge implements PendingDischargeKey {
        final long tick;
        final float rebaseAimDeg;
        final int mount;
        /** The immutable wire event, kept so a server_only expiry can ADOPT —
         *  the presenter's own-role spawn needs the full payload (mount/aim/bearing/tick). */
        final ClientGameplayEdgeEvent event;
        boolean mountMismatchCounted; // aim_rebase_mount_mismatch counts a passed-over entry ONCE

        PendingAuthDischarge(long tick, float rebaseAimDeg, int mount, ClientGameplayEdgeEvent event) {
            this.tick = tick;
            this.rebaseAimDeg = rebaseAimDeg;
            this.mount = mount;
            this.event = event;
        }

        @Override public long pairTick() { return tick; }
        @Override public int pairMount() { return mount; }
        @Override public boolean mismatchCounted() { return mountMismatchCounted; }
        @Override public void countMismatch() { mountMismatchCounted = true; }
        @Override public float pairAimDeg() { return rebaseAimDeg; }
    }

    /** A pending LOCAL discharge — snapshot-tick stamp + the predicted projectile and where the
     *  scan first sighted it (the rebase rotation anchor; ≤1 render frame past the true muzzle).
     *  + the local {@code getAllWeapons()} mount ordinal (−1 ⇒ legacy pair-any). */
    static final class PendingLocalDischarge implements PendingDischargeKey {
        final long tick;
        final DamagingProjectileAPI proj;
        final float anchorX;
        final float anchorY;
        final int mount;
        boolean mountMismatchCounted; // aim_rebase_mount_mismatch counts a passed-over entry ONCE
        // Retire/early-retire payload, resolved at RECORD time.
        /** Static class-gate half ({@code DischargeReconcileLaw.retireClassOk}, 4-arg:
         *  shape half AND server-confirmability half). */
        final boolean retireClassOk;
        /** Server-unconfirmable class ({@code subEpsilonClass} — no ammo
         *  signal + refire at/below the detector epsilon, the irpulse class); splits the
         *  skip_class meter so the under-emit INFO channel stays honest. */
        final boolean subEpsClass;
        /** The SHAPE half alone ({@code retireClassOk} 2-arg: !beam, burst≤1) —
         *  the per-coverage narrowing re-admits a sub-eps entry only when this half holds. */
        final boolean shapeOk;
        /** Late twin of an already-ADOPTED server shot (record-time match
         *  against the adopted-recent ring) — dedup class, never a treatment token. */
        final boolean adoptEcho;
        /** Dynamic class-gate half: a same-(mount,recordTick) sibling exists(ed) — flagged BOTH ways. */
        boolean multiSpawn;
        /** Ammo-delta early-retire eligibility (born-once; ambiguity only ever demotes it). */
        boolean ammoEligible;
        /** PRIOR-fresh-frame auth mount ammo at record ({@code −1} = no cache ⇒ never eligible). */
        final int authAmmoAtRecord;
        /** The prior fresh frame's simTick the cache value was read at. */
        final long authTickAtRecord;

        /** The discharging group's autofire state AT FIRE TIME (record-time
         *  capture — the rebase-time read races the toggle mirror; pair-scoped classification). */
        final boolean afAtFire;

        /** The projectile's velocity heading (deg) AT
         *  SIGHTING (record time), the local twin of the wire's BIRTH flight bearing. WHY: a
         *  thrusting rocket's live heading swings from its birth bearing toward its facing within
         *  ticks (hammer: launch 40 su/s, accel 750), so comparing the wire birth bearing with the
         *  LIVE heading at a late pair measured thrust physics, not aim error (measured: Δ −117.65°
         *  on a TRUE twin). NaN = unknown (MovingRay zero-vel class). */
        final float birthHeadingDeg;

        PendingLocalDischarge(long tick, DamagingProjectileAPI proj, float anchorX, float anchorY,
                int mount, boolean retireClassOk, boolean subEpsClass, boolean shapeOk,
                boolean adoptEcho, boolean ammoEligible, int authAmmoAtRecord,
                long authTickAtRecord, boolean afAtFire, float birthHeadingDeg) {
            this.birthHeadingDeg = birthHeadingDeg;
            this.afAtFire = afAtFire;
            this.shapeOk = shapeOk;
            this.tick = tick;
            this.proj = proj;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.mount = mount;
            this.retireClassOk = retireClassOk;
            this.subEpsClass = subEpsClass;
            this.adoptEcho = adoptEcho;
            this.ammoEligible = ammoEligible;
            this.authAmmoAtRecord = authAmmoAtRecord;
            this.authTickAtRecord = authTickAtRecord;
        }

        @Override public long pairTick() { return tick; }
        @Override public int pairMount() { return mount; }
        @Override public boolean mismatchCounted() { return mountMismatchCounted; }
        @Override public void countMismatch() { mountMismatchCounted = true; }
        /** the tie-break compares BIRTH quantities (the wire flight bearing
         *  is a birth bearing); live heading only when the birth heading is unknown. */
        @Override public float pairAimDeg() {
            return Float.isFinite(birthHeadingDeg) ? birthHeadingDeg : liveAimDeg(proj);
        }
    }


    /**
     * The OWN fire-phase lock lane
     * (see {@link #OWN_FIRE_LOCK_MODE}): flux re-base in the mutual normal state (primary),
     * quiescent-frame ammo apply, and the one-way only-shorten cooldown NUDGE — every decision in
     * the pure {@link OwnFireLockLaw}, applied here through the engine's own public setters
     * ({@code FluxTrackerAPI.setCurrFlux}/{@code setHardFlux} curr-first;
     * {@code WeaponAPI.setAmmo}/{@code setRemainingCooldownTo}). FRESH snapshots only — the
     * freshness gate also absorbs AWSD hold-last windows. Counters count law
     * decisions in meter AND apply modes; writes happen only under apply. Fail-soft like every
     * reconcile lane.
     */
    void maintainOwnFireLock(ShipAPI own, ServerEntityPose pose, boolean freshSnapshot,
            long simTick) {
        if (!OWN_FIRE_LOCK_METER || !freshSnapshot || own == null || pose == null || !own.isAlive()) {
            return;
        }
        try {
            boolean carriedAny = false;
            // Sub-lane 1 — flux re-base (primary): pin only in the MUTUAL normal state (a mid-vent
            // pin extends the vent; unknown carried flags fail closed to skip).
            FluxTrackerAPI ft = own.getFluxTracker();
            if (ft != null && pose.hasFlux()) {
                carriedAny = true;
                boolean flagsKnown = pose.hasFluxFlags();
                int flags = flagsKnown ? pose.fluxFlags() : 0;
                int action = OwnFireLockLaw.fluxAction(
                        ft.isVenting(), ft.isOverloaded(),
                        flagsKnown,
                        (flags & 2) != 0,  // ShipFluxSnapshot.FLAG_VENTING
                        (flags & 1) != 0,  // ShipFluxSnapshot.FLAG_OVERLOADED
                        ft.getCurrFlux(), ft.getHardFlux(), ft.getMaxFlux(),
                        pose.currFlux(), pose.hasHardFlux(), pose.hardFlux(),
                        ownlockFluxOut);
                // fluxAction pins on EXACT
                // inequality (the RTT-old saw-tooth); OwnFluxPinLaw's dead band gates it. staleTicks = 0:
                // this lane keeps no inter-snapshot delta, so the band is the 2 % design floor only.
                if (action == OwnFireLockLaw.FLUX_PIN && !OwnFluxPinLaw.pairPinDue(ft.getCurrFlux(),
                        ownlockFluxOut[0], ft.getHardFlux(), ownlockFluxOut[1],
                        OwnFluxPinLaw.band(ft.getMaxFlux(), 0L, 0f, 1))) {
                    ownlockFluxBandSkips++;
                } else if (action == OwnFireLockLaw.FLUX_PIN) {
                    ownlockFluxPins++;
                    if (OWN_FIRE_LOCK_APPLY) {
                        ft.setCurrFlux(ownlockFluxOut[0]); // curr-first
                        if (!Float.isNaN(ownlockFluxOut[1])) {
                            ft.setHardFlux(ownlockFluxOut[1]);
                        }
                        if (PredictReconcileCombatPlugin.settleBoostClockAfterFluxPin(own, ft, ownlockFluxOut[0])) {
                            ownlockBoostClockZeros++;
                        }
                    }
                } else if (action == OwnFireLockLaw.FLUX_SKIP_STATE) {
                    ownlockFluxSkipsState++;
                }
            }
            // phase-edge window resolve (fresh frames only, this method's gate):
            // an OWN phase-state edge on either clock opens the re-base window for ~G ticks.
            boolean phaseWindow = updatePhaseRebaseWindow(own, pose, simTick);
            // Sub-lanes 2+3 — per carried mount (v4 order contract: same variant, same index).
            if (pose.hasWeaponMounts()) {
                carriedAny = true;
                List<WeaponAPI> weapons = own.getAllWeapons();
                if (weapons != null) {
                    // Mounts holding an un-reconciled pending own local
                    // discharge (the pairing ledger) — the carried frame predates those
                    // shots, so refund-up/cd-shorten there re-arms the gun every frame (
                    // reaper 31 fires/502 ms press vs server 0). Snapshot once per call.
                    java.util.HashSet<Integer> pendingMounts = null;
                    if (!pendingLocalDischarges.isEmpty()) {
                        pendingMounts = new java.util.HashSet<>();
                        for (PendingLocalDischarge pl : pendingLocalDischarges) {
                            pendingMounts.add(pl.mount);
                        }
                    }
                    for (cc.starsector.client.net.ServerWeaponMountState mount : pose.weaponMounts()) {
                        int idx = mount.mountIndex();
                        if (idx < 0 || idx >= weapons.size()) {
                            continue;
                        }
                        if (idx < 64) {
                            // pair-class cache: latest wire autofire state + flip tick (the
                            // transition-window guard input; carried frames only — fail-soft).
                            boolean sAf = mount.autofiring();
                            if (sAf != ((serverAfMountMask >>> idx & 1L) != 0L)) {
                                serverAfMountMask ^= 1L << idx;
                                serverAfEdgeTick.put(idx, simTick);
                            }
                        }
                        WeaponAPI weapon = weapons.get(idx);
                        if (weapon == null) {
                            continue;
                        }
                        boolean pendingHere = pendingMounts != null && pendingMounts.contains(idx);
                        // The mount re-arm latch — evaluate release FIRST
                        // (a truth-passed frame IS the sync frame; its writes must go live now),
                        // then hold refund-up/nudge while it stands. The press
                        // clock is GONE — a fire-source drop (incl. group-switch-away) no longer
                        // releases; truth or the native-scale timeout do.
                        boolean latchedHere = false;
                        long[] latch = mountRearmLatch.isEmpty() ? null : mountRearmLatch.get(idx);
                        if (latch != null) {
                            int la = OwnFireLockLaw.latchAction(simTick, latch[0], latch[1],
                                    latch[2]);
                            if (la == OwnFireLockLaw.LATCH_HOLD) {
                                latchedHere = true;
                            } else {
                                mountRearmLatch.remove(idx);
                                if (la == OwnFireLockLaw.LATCH_RELEASE_TRUTH) {
                                    ownlockLatchRelTruth++;
                                } else {
                                    ownlockLatchRelTimeout++;
                                }
                            }
                        }
                        // The reload-hold mirror runs BEFORE the ammo/cooldown laws
                        reloadHold.onCarriedMount(own, weapon, idx, mount, simTick);
                        burstPhase.onCarriedMount(own, weapon, idx, mount, simTick, pendingHere); // reads only, POST flux pin + hold apply
                        boolean inBurst = weapon.isInBurst();
                        float burstRem = weapon.getBurstFireTimeRemaining();
                        float cdRem = weapon.getCooldownRemaining();
                        int maxAmmo = weapon.getMaxAmmo(); // the law's read, reused by the ammo row
                        boolean firing = weapon.isFiring();
                        // owed: walked only where the hold can fire - a quiescent down divergence, the
                        // cooldown-EXPIRY frame included. The prune is the law's own QUIESCENCE test
                        // plus a raw auth < local check - the law itself compares the
                        // CLAMPED target, which differs only for local > max. A local so the
                        // ammo row can print the value the law was handed (the rig's proof row needs it).
                        int owedHere = !pendingAuthDischarges.isEmpty() && OwnFireLockLaw.owedWalkNeeded(
                                inBurst, burstRem, cdRem, firing, weapon.getAmmo(), mount.ammo())
                                ? owedAuthDischarges(idx) : 0;
                        int ammoAction = OwnFireLockLaw.ammoAction(
                                mount.usesAmmo(), weapon.usesAmmo(),
                                inBurst, burstRem, cdRem, firing,
                                weapon.getAmmo(), maxAmmo, mount.ammo(),
                                pendingHere, latchedHere, owedHere,
                                ownlockAmmoOut);
                        if (ammoAction == OwnFireLockLaw.AMMO_WRITE) {
                            if (OWN_BEAM_CYCLE_APPLY && ownBeamCycle.shieldsAmmoMirror(idx)) {
                                // Mirror shield: the beam-cycle lane owns this mount's
                                // ammo while starve-engaged — the downward auth-0 write would
                                // stomp the banked/granted restart charge every fresh frame.
                                ownBeamCycle.mirrorHolds++;
                            } else {
                                ownlockAmmoWrites++;
                                // OWN bound OWNLOCK_AMMO_ROW_MAX (not
                                // the lane's 60); prefix byte-identical, tail tokens = values the law already read.
                                if (ownlockAmmoRowsLogged < OWNLOCK_AMMO_ROW_MAX) {
                                    ownlockAmmoRowsLogged++;
                                    PilotWatchLog.info("predict ownlock ammo @tick=" + simTick
                                            + ": mount=" + idx + " local=" + weapon.getAmmo()
                                            + " -> auth=" + ownlockAmmoOut[0]
                                            + " max=" + maxAmmo + " cd=" + cdRem + " burst=" + inBurst
                                            + " pend=" + pendingHere + " latch=" + latchedHere
                                            + " owed=" + owedHere
                                            + (OWN_FIRE_LOCK_APPLY ? "" : " (meter)")
                                            + (ownlockAmmoRowsLogged == OWNLOCK_AMMO_ROW_MAX
                                                    ? " [ammo row cap reached; counters continue]" : ""));
                                }
                                if (OWN_FIRE_LOCK_APPLY) {
                                    weapon.setAmmo(ownlockAmmoOut[0]);
                                }
                            }
                        } else if (ammoAction == OwnFireLockLaw.AMMO_SKIP_BUSY) {
                            ownlockAmmoSkipsBusy++;
                        } else if (ammoAction == OwnFireLockLaw.AMMO_HOLD_PENDING) {
                            ownlockAmmoHoldPending++;
                        } else if (ammoAction == OwnFireLockLaw.AMMO_HOLD_LATCHED) {
                            ownlockAmmoHoldLatched++;
                        } else if (ammoAction == OwnFireLockLaw.AMMO_HOLD_OWED) {
                            // A shielded mount
                            // counts as its WRITE would — the beam-cycle lane owns that mount's ammo.
                            if (OWN_BEAM_CYCLE_APPLY && ownBeamCycle.shieldsAmmoMirror(idx)) {
                                ownBeamCycle.mirrorHolds++;
                            } else {
                                ownlockAmmoHoldOwed++;
                            }
                        }
                        boolean authArming = mount.firing() || mount.chargeLevel() > 0f;
                        // the "local arming counts as
                        // busy" guard (weapon.isFiring() || getChargeLevel() > 0) is REVERTED — the engine's
                        // isFiring() = state != IDLE and cdRem > 0 ONLY in COOLDOWN,
                        // so that flag was true on every frame the law passes its idle guard = sub-lane 3
                        // OFF (nudge/phase-rebase/auth-arming/implausible all unreachable), concealing m4.
                        // The abort-branch narrative is refuted too (OwnFireLockLaw's guards
                        // guarantee the GRACEFUL branch by construction). m4 candidate = a graceful
                        // post-burst nudge to an auth cooldownRemaining the server sampled mid-burst-gap
                        // ((1 - gapProgress) * refire, not a clock).
                        // The discriminator: the carried charge
                        // level rides along — in a burst gap it is exactly 1.0 while cdRem sawtooths.
                        // The group read runs only where the law could refuse — a
                        // window frame whose clamped target is longer than the local remaining.
                        boolean manualHere = phaseWindow && cdRem > 0f
                                && Math.min(mount.cooldownRemaining(), weapon.getCooldown()) > cdRem
                                && manuallyFired(own, weapon);
                        int cdAction = OwnFireLockLaw.cooldownAction(
                                cdRem, weapon.getCooldown(), inBurst, burstRem,
                                mount.cooldownRemaining(), pendingHere, latchedHere,
                                phaseWindow, authArming, mount.chargeLevel(),
                                manualHere, ownlockCdOut);
                        if (cdAction == OwnFireLockLaw.CD_HOLD_AUTH_BURST) {
                            ownlockCdHoldAuthBurst++;
                            if (ownlockCdHoldAuthBurst <= 8) { // the tell rows; counter carries the rest
                                PilotWatchLog.info("predict ownlock cd-hold-auth-burst @tick=" + simTick
                                        + " mount=" + idx + " local=" + cdRem
                                        + " auth=" + mount.cooldownRemaining()
                                        + " charge=" + mount.chargeLevel()
                                        + " (cd=" + weapon.getCooldown() + ")"
                                        + " inBurst=" + inBurst + " burstRem=" + burstRem);
                            }
                        } else if (cdAction == OwnFireLockLaw.CD_HOLD_AUTH_ARMING) {
                            ownlockCdHoldAuthArming++;
                            if (ownlockCdHoldAuthArming <= 8) { // the tell rows; counter carries the rest
                                ownFireLockLane.log("predict ownlock cd-hold-auth-arming @tick=" + simTick
                                        + ": mount=" + idx + " local=" + cdRem
                                        + " auth=" + mount.cooldownRemaining()
                                        + " firing=" + mount.firing() + " charge=" + mount.chargeLevel()
                                        + " (cd=" + weapon.getCooldown() + ")");
                            }
                        } else if (cdAction == OwnFireLockLaw.CD_NUDGE) {
                            ownlockCdNudges++;
                            ownFireLockLane.log("predict ownlock cd-nudge @tick=" + simTick
                                    + ": mount=" + idx + " local=" + cdRem
                                    + " -> auth=" + ownlockCdOut[0]
                                    + " (cd=" + weapon.getCooldown() + ")"
                                    + (OWN_FIRE_LOCK_APPLY ? "" : " (meter)"));
                            // Uncapped tell (nudges are ~15/flight; the lane's row cap hid the m4 pair)
                            PilotWatchLog.info("predict ownlock cd-nudge @tick=" + simTick + " mount=" + idx
                                    + " local=" + cdRem + " -> auth=" + ownlockCdOut[0]
                                    + " inBurst=" + inBurst + " burstRem=" + burstRem);
                            if (OWN_FIRE_LOCK_APPLY) {
                                weapon.setRemainingCooldownTo(ownlockCdOut[0]);
                            }
                        } else if (cdAction == OwnFireLockLaw.CD_PHASE_REBASE
                                || cdAction == OwnFireLockLaw.CD_HOLD_PHASE_LENGTHEN) {
                            // A refused lengthen writes nothing; it counts and rows ONCE
                            // per mount per window (refused=true — never a WRITE row).
                            boolean refused = cdAction == OwnFireLockLaw.CD_HOLD_PHASE_LENGTHEN;
                            boolean firstRefusal = refused
                                    && (idx >= 64 || (phaseLengthenRefusedMask >>> idx & 1L) == 0L);
                            if (firstRefusal) {
                                phaseLengthenRefusedMask |= idx < 64 ? 1L << idx : 0L;
                                ownlockCdPhaseLengthenRefused++;
                            } else if (!refused) {
                                ownlockCdPhaseRebases++;
                                ownFireLockLane.log("predict ownlock cd-phase-rebase @tick=" + simTick
                                        + ": mount=" + idx + " local=" + cdRem
                                        + " -> auth=" + ownlockCdOut[0]
                                        + " (cd=" + weapon.getCooldown() + ")"
                                        + (OWN_FIRE_LOCK_APPLY ? "" : " (meter)"));
                            }
                            // The uncapped-in-practice twin (own
                            // CD_PHASE_REBASE_ROW_MAX cap) — dir=lengthen is the refire-skip case.
                            if ((!refused || firstRefusal) && cdPhaseRebaseRowsLogged < CD_PHASE_REBASE_ROW_MAX) {
                                cdPhaseRebaseRowsLogged++;
                                float target = refused ? Math.max(0f,
                                        Math.min(mount.cooldownRemaining(), weapon.getCooldown()))
                                        : ownlockCdOut[0];
                                PilotWatchLog.info("predict ownlock cd-phase-rebase-row @tick=" + simTick
                                        + " mount=" + idx + " local=" + cdRem
                                        + " auth=" + mount.cooldownRemaining() + " target=" + target
                                        + " dir=" + (target > cdRem ? "lengthen" : target < cdRem ? "shorten" : "same")
                                        + " cd=" + weapon.getCooldown() + " charge=" + mount.chargeLevel()
                                        + " pending=" + pendingHere + " latched=" + latchedHere
                                        + " inBurst=" + inBurst + " burstRem=" + burstRem
                                        + " until=" + phaseRebaseWindowUntil + " opener=" + phaseRebaseOpener
                                        + " authPhased=" + lastAuthPhased + " localPhased=" + lastLocalPhased
                                        // Read fresh for the row, so a `dir=lengthen manual=1`
                                        // WRITE row (no refused=true) proves the wiring broken.
                                        + " manual=" + (manuallyFired(own, weapon) ? 1 : 0)
                                        + (refused ? " refused=true" : "")
                                        + (OWN_FIRE_LOCK_APPLY ? "" : " (meter)"));
                            }
                            if (!refused && OWN_FIRE_LOCK_APPLY) {
                                weapon.setRemainingCooldownTo(ownlockCdOut[0]);
                            }
                        } else if (cdAction == OwnFireLockLaw.CD_SKIP_IMPLAUSIBLE) {
                            ownlockCdSkipsImplausible++;
                            if (ownlockSkipImplausibleLogged.add(idx)) {
                                float refusedTarget = Math.max(0f,
                                        Math.min(mount.cooldownRemaining(), weapon.getCooldown()));
                                ownFireLockLane.log("predict ownlock cd-skip-IMPLAUSIBLE @tick="
                                        + simTick + ": mount=" + idx + " local=" + cdRem
                                        + " auth=" + mount.cooldownRemaining()
                                        + " target=" + refusedTarget
                                        + " (cd=" + weapon.getCooldown() + "; effective shorten > "
                                        + OwnFireLockLaw.CD_MAX_PLAUSIBLE_SHORTEN_SEC
                                        + "s refused - stale carried frame; one line per mount)");
                            }
                        } else if (cdAction == OwnFireLockLaw.CD_SKIP_FAST) {
                            ownlockCdSkipsFast++;
                        } else if (cdAction == OwnFireLockLaw.CD_SKIP_BUSY) {
                            ownlockCdSkipsBusy++;
                        } else if (cdAction == OwnFireLockLaw.CD_HOLD_PENDING) {
                            ownlockCdHoldPending++;
                        } else if (cdAction == OwnFireLockLaw.CD_HOLD_LATCHED) {
                            ownlockCdHoldLatched++;
                        }
                    }
                }
            }
            if (carriedAny) {
                ownFireLockLane.framesCarried++;
            } else {
                ownFireLockLane.framesMissing++;
            }
        } catch (Throwable t) {
            // reconcile lane; never break the watch
            if (ownlockReconcileThrew++ == 0) { //
                // The message rides the package
                // scrub (FallbackAnnounce.sanitize: control/non-ASCII -> '?', capped at 160 chars).
                PilotWatchLog.info("predict ownlock reconcile threw (logged once): " + t.getClass().getName() + ": "
                        + FallbackAnnounce.sanitize(t.getMessage(), 160));
            }
        }
    }

    /**
     * Maintain the phase-edge re-base window (fresh frames only; caller =
     * {@link #maintainOwnFireLock}). An OWN phase-state EDGE on either clock — the carried auth
     * bit ({@code pose.isPhasedBit()}, read only when the frame's flux flags are known) or the
     * local hull's {@code isPhased()} (best-effort; a throw skips that clock, never the window
     * logic) — opens/extends the window to {@code simTick + }{@link #dischargeGraceTicks} (W ≈ G:
     * the same RTT bound governs how stale the carried cd can be). Boolean baselines seed on the
     * first observed frame without minting a false edge. Rewound clock ⇒ close (a stale window
     * must never outlive its own clock's meaning). Returns true while the window is open.
     */
    boolean updatePhaseRebaseWindow(ShipAPI own, ServerEntityPose pose, long simTick) {
        if (pose.hasFluxFlags()) {
            boolean authPhased = pose.isPhasedBit();
            if (lastAuthPhased != null && authPhased != lastAuthPhased.booleanValue()) {
                phaseRebaseWindowUntil = simTick + dischargeGraceTicks;
                phaseRebaseOpener = "auth";
                ownlockPhaseWindows++;
                phaseLengthenRefusedMask = 0L;
            }
            lastAuthPhased = Boolean.valueOf(authPhased);
        }
        try {
            boolean localPhased = own.isPhased();
            if (lastLocalPhased != null && localPhased != lastLocalPhased.booleanValue()) {
                phaseRebaseWindowUntil = simTick + dischargeGraceTicks;
                phaseRebaseOpener = "local";
                ownlockPhaseWindows++;
                phaseLengthenRefusedMask = 0L;
            }
            lastLocalPhased = Boolean.valueOf(localPhased);
        } catch (Throwable ignore) {
            // local phase read is best-effort; the auth edge alone still opens the window
        }
        if (phaseRebaseWindowUntil != Long.MIN_VALUE
                && simTick < phaseRebaseWindowUntil - dischargeGraceTicks) {
            phaseRebaseWindowUntil = Long.MIN_VALUE;
            phaseRebaseOpener = "none";
            phaseLengthenRefusedMask = 0L;
        }
        return phaseRebaseWindowUntil != Long.MIN_VALUE && simTick <= phaseRebaseWindowUntil;
    }

    /**
     * Whether {@code weapon}'s shots on the own hull are trigger-fired INPUT —
     * the complement of {@code EdgeEventLane.declareFireDischarge}'s autofire skip, the SAME
     * predicate (a group that is not autofiring, or the SELECTED group: selection takes manual
     * control while the toggle still reads true). The LOCAL group state, not the wire's
     * {@code mount.autofiring()}: the question is whether THIS kit's shot is declared / held-lane
     * paired. No group or a throw ⇒ false = the both-direction re-base stands. Cost:
     * {@code getWeaponGroupFor} walks the groups and copies each visited group's weapon list
     * in the engine, so the caller consults it only on a window
     * frame that could lengthen, and once more per capped tell row.
     */
    private static boolean manuallyFired(ShipAPI own, WeaponAPI weapon) {
        try {
            WeaponGroupAPI g = own.getWeaponGroupFor(weapon);
            return g != null && (!g.isAutofiring() || g == own.getSelectedGroupAPI());
        } catch (Throwable t) {
            return false;
        }
    }


    /**
     * Record one LOCAL own-ship discharge on the snapshot-tick clock and try to
     * pair it with a pending authoritative discharge. Pairing is MOUNT-TRUE —
     * head-of-queue tick-only matching inside the 60-tick window cross-paired 16 autofire mounts +
     * manual fire (and same-mount-stream refires ~6–15 ticks « 60) and {@link AimRebaseLane#rebaseOwnProjectile}
     * then faithfully rotated the WRONG bolt: the aim_rebase_max=107.55° tail. Identity
     * exists on both sides already (GPEE v1 {@code mountIndex}; the local {@code getAllWeapons()}
     * ordinal — the AWSS v4 cross-side order contract); sentinel −1 = legacy pair-any. The
     * projectile scan runs before this frame's {@link EdgeEventLane#drainEdgeEvents}, so expire here too —
     * otherwise a stale entry would linger and mint TWO false misses from one real pair.
     */
    void recordLocalDischarge(long snapTick, DamagingProjectileAPI proj) {
        expireDischargePairs(snapTick);
        int mount = resolveOwnMountIndex(proj);
        noteLocalCadence(mount, snapTick); // Observe the mount's own shot period
        bindSeedBody(); // this mount has now fired locally — never seed it again
        ownMountSeedMask = OwnMountSeedLaw.markMountFired(ownMountSeedMask, mount);
        Vector2f loc = proj.getLocation();
        float anchorX = loc != null ? loc.x : 0f;
        float anchorY = loc != null ? loc.y : 0f;
        // At sighting live == birth — this ONE read is both the tie-break
        // query and the entry's recorded birth heading.
        float sightingHeadingDeg = liveAimDeg(proj);
        PendingAuthDischarge auth = pollBest(pendingAuthDischarges, snapTick, mount,
                sightingHeadingDeg); // the tie-break query = this bolt's live heading
        if (auth != null) {
            dischargeAgree++;
            // auth-first ordering — the event beat the sighting, so rebase right here at
            // spawn (the projectile has barely flown; the rotation about the anchor is ~pure heading).
            aimRebase.rebaseOwnProjectile(proj, anchorX, anchorY, auth.rebaseAimDeg, sightingHeadingDeg,
                    auth.event.birthSpeed(), snapTick,
                    Math.abs(snapTick - auth.tick), mount, snapTick,
                    aimRebase.isAutofireGroupDischarge(proj)); // auth-first: fire time IS now
            noteLocalPaired(snapTick, mount); // Departed twin still flags a late sibling
            armRearmLatch(mount, snapTick, auth.tick); // Hold until a frame PAST the fire
        } else {
            pendingLocalDischarges.addLast(
                    newPendingLocalDischarge(snapTick, proj, anchorX, anchorY, mount,
                            sightingHeadingDeg));
        }
    }

    /**
     * record-time retire/early-retire payload resolve (frozen): the static
     * class gate off the discharging weapon, same-(mount, recordTick) sibling multiSpawn flagging
     * (BOTH directions — pending siblings AND already-paired siblings via the recent-paired ring:
     * without the ring, auth-first ordering pairs bolt 1 instantly and a linked-barrel bolt 2
     * would record unflagged and RETIRE a real bolt), and the ammo-delta eligibility off the
     * PRIOR-fresh-frame auth cache. Fail-soft: any resolve failure lands the entry on the plain
     * 1 s path (classOk = false / eligible = false — errs missed-over-false).
     */
    PendingLocalDischarge newPendingLocalDischarge(long snapTick, DamagingProjectileAPI proj,
            float anchorX, float anchorY, int mount, float birthHeadingDeg) {
        boolean classOk = false;
        boolean subEps = true; // fail-closed: an unresolvable weapon is server-unconfirmable
        boolean shapeOk = false; // Shape half alone (unresolvable ⇒ false, fail-closed)
        boolean eligible = false;
        boolean multiSpawn = false;
        int authAmmoAtRecord = -1;
        long authTickAtRecord = Long.MIN_VALUE;
        try {
            WeaponAPI w = proj.getWeapon();
            if (w != null) {
                // The 4-arg gate mirrors the server detector's structural
                // under-emit predicate — a weapon whose refire the detector cannot see (irpulse:
                // no ammo, chargedown 0 ≤ epsilon) must never be retire-adjudicated.
                subEps = DischargeReconcileLaw.subEpsilonClass(w.usesAmmo(), w.getCooldown());
                int burst = w.getSpec() != null ? w.getSpec().getBurstSize() : Integer.MAX_VALUE;
                shapeOk = DischargeReconcileLaw.retireClassOk(w.isBeam(), burst);
                classOk = DischargeReconcileLaw.retireClassOk(w.isBeam(), burst,
                        w.usesAmmo(), w.getCooldown());
                long[] cached = mount >= 0 ? dischargeMountAuthCache.get(mount) : null;
                if (cached != null) {
                    authAmmoAtRecord = (int) cached[0];
                    authTickAtRecord = cached[2];
                }
                eligible = DischargeReconcileLaw.ammoEligibleAtRecord(mount,
                        cached != null && cached[1] != 0L, w.usesAmmo(), w.getAmmoPerSecond(),
                        classOk, cached != null, authAmmoAtRecord);
            }
            if (mount >= 0) {
                for (PendingLocalDischarge sib : pendingLocalDischarges) {
                    if (sib.mount == mount && sib.tick == snapTick) {
                        sib.multiSpawn = true;
                        multiSpawn = true;
                    }
                }
                if (!multiSpawn) {
                    for (long[] paired : recentPairedLocalRing) {
                        if (paired[0] == snapTick && paired[1] == mount) {
                            multiSpawn = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            classOk = false;
            subEps = true; // errs missed-over-false: never retire what the server can't confirm
            eligible = false;
        }
        // record-time echo-class resolve — the adopt consumed the auth entry
        // BEFORE this twin recorded (server-first skew), so pairing can never see the pair.
        boolean adoptEcho = false;
        for (long[] adopted : adoptedRecentRing) {
            if (DischargeReconcileLaw.adoptEchoClass(adopted[0], (int) adopted[1], snapTick, mount)) {
                adoptEcho = true;
                break;
            }
        }
        PendingLocalDischarge entry = new PendingLocalDischarge(snapTick, proj, anchorX, anchorY,
                mount, classOk, subEps, shapeOk, adoptEcho, eligible, authAmmoAtRecord,
                authTickAtRecord, aimRebase.isAutofireGroupDischarge(proj), birthHeadingDeg);
        entry.multiSpawn = multiSpawn;
        return entry;
    }

    /**
     * Arm (or refresh) the mount re-arm latch at a pending-ledger removal
     * (see {@link #mountRearmLatch}). Called at ALL FOUR removal sites — both pair paths (with
     * the GPEE tick: truth releases at the first fresh frame PAST it) and both retire paths
     * (gpee unset: GPEE/press/timeout decide later). A refresh keeps the best known GPEE tick
     * (multiSpawn sibling retires re-arm the same mount). Timeout scales off the weapon's native
     * cooldown; an unresolvable weapon falls to the law's floor (shortest honest hold).
     */
    void armRearmLatch(int mount, long nowTick, long gpeeTick) {
        if (mount < 0) {
            return;
        }
        try {
            float cooldown = Float.NaN;
            List<WeaponAPI> weapons = plugin.weaponLists.weapons(plugin.ownShip); // the frame's list, no allocation
            if (weapons != null && mount < weapons.size() && weapons.get(mount) != null) {
                cooldown = weapons.get(mount).getCooldown();
            }
            long[] prior = mountRearmLatch.get(mount);
            long gpee = gpeeTick;
            if (prior != null && prior[1] != Long.MIN_VALUE && (gpee == Long.MIN_VALUE || prior[1] > gpee)) {
                gpee = prior[1];
            }
            mountRearmLatch.put(mount, new long[] {nowTick, gpee,
                    OwnFireLockLaw.latchTimeoutTicks(cooldown)});
            ownlockLatchArmed++;
        } catch (Throwable ignore) {
            // reconcile lane; never break the watch
        }
    }

    /** Remember an instant-paired local (tick, mount) key (drop-oldest ring;
     *  render thread) so a same-key sibling recorded after its twin departed still flags multiSpawn. */
    void noteLocalPaired(long tick, int mount) {
        if (mount < 0) {
            return;
        }
        pushBounded(recentPairedLocalRing, RECENT_PAIRED_RING_CAP, new long[] {tick, mount});
    }

    /** Deferred-refactors FLC ring-push fold (paid at the window): the ONE
     *  bounded drop-oldest push body for the three (tick, mount) accounting rings. */
    static void pushBounded(ArrayDeque<long[]> ring, int cap, long[] entry) {
        if (ring.size() >= cap) {
            ring.pollFirst();
        }
        ring.addLast(entry);
    }

    /** The authoritative side of {@link #recordLocalDischarge} (same B2(b)
     *  mount-true match; an event can outrun the applied snapshot, so |Δtick| tolerates both signs). */
    void matchAuthDischarge(ClientGameplayEdgeEvent event) {
        if (event.mountIndex() >= MOUNT_INDEX_MAX) {
            dischargeMountIndexOob++; //
        } else if (event.mountIndex() >= 0) {
            // The server just CONFIRMED a discharge on this mount — from
            // here on a sub-eps local bolt on it is retire-adjudicable (subEpsConfirmable).
            dischargeAuthConfirmedMounts.set(event.mountIndex());
        }
        float authAim = aimRebase.rebaseAimDeg(event); // resolve ONCE (the unbound counter must not double-count)
        PendingLocalDischarge local = pollBest(pendingLocalDischarges, event.simTick(),
                event.mountIndex(), authAim); // the tie-break query = the wire flight bearing
        if (local != null) {
            dischargeAgree++;
            // local-first ordering (the common predicted case) — the projectile has flown a
            // few ticks on the client's aim; rotate it about its sighting anchor onto the wire bearing.
            aimRebase.rebaseOwnProjectile(local.proj, local.anchorX, local.anchorY, authAim,
                    local.birthHeadingDeg, event.birthSpeed(), event.simTick(),
                    Math.abs(event.simTick() - local.tick), local.mount, local.tick,
                    local.afAtFire);
            noteLocalPaired(local.tick, local.mount); // Departed twin still flags a late sibling
            armRearmLatch(local.mount, event.simTick(), event.simTick()); // Hold until a frame PAST the fire
            drainOwnLead(local, event);
        } else {
            pendingAuthDischarges.addLast(
                    new PendingAuthDischarge(event.simTick(), authAim, event.mountIndex(), event));
            // A GPEE on an already-LATCHED mount is the truth signal even unpaired (the
            // local twin already retired) — stamp it so the latch releases at the frame past it.
            long[] latch = event.mountIndex() >= 0 ? mountRearmLatch.get(event.mountIndex()) : null;
            if (latch != null && (latch[1] == Long.MIN_VALUE || event.simTick() > latch[1])) {
                latch[1] = event.simTick();
            }
        }
    }

    /**
     * The pair-seam lead drain (law +
     * WHY = {@link OwnFireLockLaw#leadDrainTicks}): the server just fired the twin of a local shot and
     * TOLD how long that shot's declaration waited for its gun ({@link
     * ClientGameplayEdgeEvent#declaredWaitTicks()}, GPEE v16). When the law asks for a drain, the
     * mount's running cooldown is lengthened, so its NEXT declaration reaches the server just before the
     * server's gun readies instead of waiting there while the kit's bolt flies ahead of its twin. The
     * stamp IS the declaration verdict (only a consumed declaration is stamped — an autofire shot carries
     * none), so no group walk and no ping read run here. Kit-first pairs only (the one call site, {@link
     * #matchAuthDischarge}'s paired branch). In order: (1) the event's mount is the local shot's mount
     * and the wait is present — every such wait lands in {@code ownlock_decl_wait_hist}; (2) the law
     * ({@code drain <= 0} = absent / implausible / deadband: nothing written, nothing counted); (3) ONE
     * write per mount per local cycle — the shot is the mount's LATEST local shot ({@link
     * #mountLastLocalDischargeTick}) and not the one last drained ({@link #mountLastDrainedShotTick}) —
     * and the pair is CAUSAL ({@link OwnFireLockLaw#leadPairCausal}: a wait longer than the pair's own
     * lag is another shot's);
     * (4) NOT-CARRIED waits are skipped: an ammo weapon (its regeneration runs on its own clock) and a
     * chargeup weapon (an unbound chargeup class pays it twice on the server every cycle); (5) the
     * write guards of {@link OwnFireLockLaw#leadDrainTarget} at the mount's cooldown-advance rate
     * ({@link #cooldownAdvanceRate}). A refusal in (3)-(5) counts one {@code ownlock_cd_lead_drain_skips};
     * a throw counts {@code ownlock_cd_lead_drain_threw} and announces once; a drain is counted only
     * after its write returned. Render thread (the edge-event drain), once per paired own discharge
     * event, the frame's cached weapon list ({@code plugin.weaponLists}; no allocation). The
     * phase-window refusal ({@code CD_HOLD_PHASE_LENGTHEN}) is untouched, and the one-way nudge cannot
     * undo the write: the carried cooldown stays above the lengthened local value.
     */
    private void drainOwnLead(PendingLocalDischarge local, ClientGameplayEdgeEvent event) {
        try {
            int wait = event.declaredWaitTicks();
            ShipAPI own = plugin.ownShip;
            if (wait < 0 || local.mount < 0 || event.mountIndex() != local.mount || own == null) {
                return;
            }
            ownlockDeclWaitHist[declWaitBucket(wait)]++;
            List<WeaponAPI> weapons = plugin.weaponLists.weapons(own);
            WeaponAPI weapon = weapons != null && local.mount < weapons.size() ? weapons.get(local.mount) : null;
            if (weapon == null) {
                return;
            }
            int tps = ServerTickRate.ticksPerSecond();
            float cooldown = weapon.getCooldown();
            int drain = OwnFireLockLaw.leadDrainTicks(wait, cooldown, tps);
            if (drain <= 0) {
                return;
            }
            Long latest = mountLastLocalDischargeTick.get(local.mount);
            Long drained = mountLastDrainedShotTick.get(local.mount);
            if (latest == null || latest.longValue() != local.tick
                    || (drained != null && drained.longValue() == local.tick)
                    || !OwnFireLockLaw.leadPairCausal(event.simTick() - local.tick, wait)) {
                ownlockCdLeadDrainSkips++;
                return;
            }
            com.fs.starfarer.api.loading.WeaponSpecAPI spec = weapon.getSpec();
            if (weapon.usesAmmo() || spec == null || spec.getChargeTime() > 0f) {
                ownlockCdLeadDrainSkips++;
                return;
            }
            float rate = cooldownAdvanceRate(own, weapon);
            float cdRem = weapon.getCooldownRemaining();
            float target = OwnFireLockLaw.leadDrainTarget(cdRem, cooldown, weapon.isInBurst(),
                    weapon.getBurstFireTimeRemaining(), drain, rate, tps);
            if (target != target) {
                ownlockCdLeadDrainSkips++;
                return;
            }
            if (OWN_FIRE_LOCK_APPLY) {
                weapon.setRemainingCooldownTo(target);
            }
            mountLastDrainedShotTick.put(local.mount, Long.valueOf(local.tick));
            ownlockCdLeadDrains++;
            ownlockCdLeadDrainTicks += drain;
            if (cdLeadDrainRowsLogged < CD_LEAD_DRAIN_ROW_MAX) {
                cdLeadDrainRowsLogged++;
                PilotWatchLog.info("predict ownlock cd-lead-drain @tick=" + event.simTick() + " mount=" + local.mount
                        + " shot=" + local.tick + " lag=" + (event.simTick() - local.tick) + " wait=" + wait
                        + " drain=" + drain + " rate=" + rate + " local=" + cdRem + " -> target=" + target
                        + " cd=" + cooldown + (OWN_FIRE_LOCK_APPLY ? "" : " (meter)"));
            }
        } catch (Throwable t) {
            ownlockCdLeadDrainThrew++; // reconcile lane; never break the watch
            FallbackAnnounce.announceOnce("f2-flc-lead-drain-threw", "own lead drain threw "
                    + t.getClass().getSimpleName() + " -> no write");
        }
    }

    /** The {@code ownlock_decl_wait_hist} bucket of a told wait
     *  ({@code >= 0}): 0 / 1 / 2 / 3 / 4-5 / 6-8 / 9-12 / 13+ = indices 0..7. */
    static int declWaitBucket(int wait) {
        return wait <= 3 ? wait : wait <= 5 ? 4 : wait <= 8 ? 5 : wait <= 12 ? 6 : 7;
    }

    /**
     * An own mount's cooldown-advance rate for {@link
     * OwnFireLockLaw#leadDrainTarget}: the weapon type's rate-of-fire multiplier (ballistic / energy /
     * missile; any other type 1) × {@code min(1, ship time multiplier)} — the engine's own cooldown
     * advance (the engine clamps the ship's time
     * multiplier to 1). Public API reads only; {@code ==} on the enum (no synthetic switch map).
     */
    static float cooldownAdvanceRate(ShipAPI own, WeaponAPI weapon) {
        com.fs.starfarer.api.combat.MutableShipStatsAPI stats = own.getMutableStats();
        WeaponAPI.WeaponType type = weapon.getType();
        float rof = 1f;
        if (type == WeaponAPI.WeaponType.BALLISTIC) {
            rof = stats.getBallisticRoFMult().getModifiedValue();
        } else if (type == WeaponAPI.WeaponType.ENERGY) {
            rof = stats.getEnergyRoFMult().getModifiedValue();
        } else if (type == WeaponAPI.WeaponType.MISSILE) {
            rof = stats.getMissileRoFMult().getModifiedValue();
        }
        return rof * Math.min(1f, stats.getTimeMult().getModifiedValue());
    }

    /** The burst-edge OWED count = {@link #pendingAuthDischarges} entries on
     *  own mount ordinal {@code mount} (unpaired by construction; ≥ 0); −1 entries count for NO mount. One bounded walk. */
    int owedAuthDischarges(int mount) {
        int n = 0;
        for (PendingAuthDischarge a : pendingAuthDischarges) {
            if (mount >= 0 && a.mount == mount) {
                n++;
            }
        }
        return n;
    }

    /** The discharging weapon's {@code getAllWeapons()} ordinal (the B1 fix-(3)
     *  idiom {@link EdgeEventLane#declareFireDischarge} uses; the AWSS v4 cross-side order contract), −1 =
     *  unresolvable ⇒ the legacy pair-any sentinel. */
    int resolveOwnMountIndex(DamagingProjectileAPI proj) {
        try {
            WeaponAPI w = proj != null ? proj.getWeapon() : null;
            if (w != null && plugin.ownShip != null) {
                return plugin.ownShip.getAllWeapons().indexOf(w);
            }
        } catch (Throwable ignore) {
            // identity resolve is best-effort; −1 = the legacy pair-any sentinel
            // A THROW here is systemic (unlike benign weaponless -1); the
            // pair-any gate itself stays counter-only (high-volume, expected under MG spam).
            FallbackAnnounce.announceOnce("f2-flc-mount-resolve-threw", "own-mount resolve threw "
                    + ignore.getClass().getSimpleName() + " -> -1 legacy pair-any");
        }
        return -1;
    }

    /**
     * Best pending match, ONE generic body for both sides (the
     * deferred-refactors discharge-pairing-twin fold): scan the whole (expiry-bounded) deque, keep
     * candidates inside the tick tolerance AND the mount gate (either side −1 = pair-any; in-window
     * candidates rejected ONLY by the mount gate count once each into aim_rebase_mount_mismatch —
     * head-only + a mount gate would deadlock mixed-mount traffic), then select by the aim
     * metric: min |Δaim| against {@code queryAimDeg} among candidates whose aim is known. Measurement
     * showed why Δtick alone mispairs — at MG cadence several same-mount shots share the window,
     * the true twin can be attrited, and tick-nearest then grabs a NEIGHBOR up to ~175° off across
     * a kill-melee turret swing; aim IS the identity the rebase consumes. Aim-known candidates
     * outrank aim-unknown ones; |Δtick| breaks aim ties; all-unknown falls back to pure min |Δtick|
     * (the earlier tick-only behavior). Unpaired entries still expire into the honest *_only meters.
     */
    private <T extends PendingDischargeKey> T pollBest(ArrayDeque<T> deque, long tick, int mount,
            float queryAimDeg) {
        T best = null;
        long bestDtick = Long.MAX_VALUE;
        float bestDaim = Float.NaN;
        boolean bestByAim = false;
        for (T c : deque) {
            long dTick = Math.abs(tick - c.pairTick());
            if (dTick > dischargeMatchTolTicks()) {
                continue;
            }
            if (mount >= 0 && c.pairMount() >= 0 && mount != c.pairMount()) {
                if (!c.mismatchCounted()) {
                    c.countMismatch();
                    dischargeMountMismatch++;
                }
                continue;
            }
            float dAim = Float.NaN;
            if (Float.isFinite(queryAimDeg)) {
                float candAim = c.pairAimDeg();
                if (Float.isFinite(candAim)) {
                    dAim = Math.abs(((queryAimDeg - candAim + 540f) % 360f) - 180f);
                }
            }
            boolean take;
            if (Float.isFinite(dAim)) {
                take = !bestByAim || dAim < bestDaim
                        || (dAim == bestDaim && dTick < bestDtick);
                if (take) {
                    bestByAim = true;
                }
            } else {
                take = best == null || (!bestByAim && dTick < bestDtick);
            }
            if (take) {
                bestDaim = dAim;
                bestDtick = dTick;
                best = c;
            }
        }
        if (best != null) {
            deque.remove(best);
            dischargePairsFormed++; // (83) CL-13: the pair-any RATE denominator
            if (mount < 0 || best.pairMount() < 0) {
                dischargePairAnyFallback++; // B2(1b): gate inert this pairing — silent pair-any degrade
            }
        }
        return best;
    }

    /** A projectile's LIVE velocity heading (deg); NaN = unknown (null/zero-vel MovingRay
     *  class, or a read that threw) ⇒ the caller falls back to the Δtick discriminator. */
    static float liveAimDeg(DamagingProjectileAPI proj) {
        try {
            Vector2f vel = proj != null ? proj.getVelocity() : null;
            if (vel == null || (vel.x == 0f && vel.y == 0f)) {
                // (z) An un-advanced RAY answers its two-end birth heading (the
                // bearing it will fly), so the tie-break ranks it by aim on both pairing sides
                // instead of falling to pure Δtick — a mis-paired twin now ROTATES the ray at birth,
                // so the aim discriminator has to be live. Non-ray: NaN as before.
                return proj != null ? AimRebaseLane.rayBirthHeadingDeg(proj) : Float.NaN;
            }
            return (float) Math.toDegrees(Math.atan2(vel.y, vel.x));
        } catch (Throwable ignore) {
            return Float.NaN;
        }
    }

    /** Observe the mount's own LOCAL shot cadence (min successive positive gap):
     *  the crossshot-suspect gate's period source. Non-positive gaps (linked-barrel same-tick
     *  twins, rewound clock) only reset the anchor tick, never the period. */
    void noteLocalCadence(int mount, long tick) {
        if (mount < 0) {
            return;
        }
        Long last = mountLastLocalDischargeTick.put(mount, Long.valueOf(tick));
        if (last == null) {
            return;
        }
        long gap = tick - last.longValue();
        if (gap <= 0) {
            return;
        }
        Long prior = mountMinLocalGapTicks.get(mount);
        if (prior == null || gap < prior.longValue()) {
            mountMinLocalGapTicks.put(mount, Long.valueOf(gap));
        }
    }

    /** The mount's observed shot period for the suspect gate: min successive local gap,
     *  floored at {@link #CROSSSHOT_PERIOD_FLOOR_TICKS}; unknown cadence ⇒ MAX (gate inert —
     *  fail-open, the earlier ungated behavior). */
    long crossshotPeriodTicks(int mount) {
        Long minGap = mountMinLocalGapTicks.get(mount);
        if (minGap == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(CROSSSHOT_PERIOD_FLOOR_TICKS, minGap.longValue());
    }

    /**
     * Flush pending discharge stamps the adjudication window has passed by. An authoritative
     * discharge with no local counterpart is the miss class a player sees (server fired; the client's
     * sim-time-lagged cooldown hadn't ended — visible to a player, uncountable by the meter until now). A
     * local discharge with no authoritative counterpart is the opposite sign. Each expiry emits one capped
     * tick-pinned line so a single miss is findable post-run without an offline ccwr decode.
     *
     * <p>verdict-at-coverage+grace: {@code snapshotTick} here IS applied coverage
     * (it advances only on fresh applies — verified), so adjudicating at
     * {@code coverage − tick > G} (G = the RTT-derived {@link #dischargeGraceTicks}, refreshed
     * once per new tick) shrinks the felt correction window 1 s → ~RTT while the CTRL-lane lag
     * bound keeps the verdict sound; a NaN RTT falls back to the full legacy tolerance, which
     * also survives as the cap/backstop. Treatments themselves are unchanged (queue here, drain
     * same frame at the drain site).
     */
    void expireDischargePairs(long snapshotTick) {
        if (snapshotTick != graceRefreshTick) {
            graceRefreshTick = snapshotTick;
            double ping;
            try {
                ping = plugin.session != null ? plugin.session.netPingMillis() : Double.NaN;
            } catch (Throwable ignore) {
                ping = Double.NaN;
            }
            dischargeGraceTicks = DischargeReconcileLaw.adjudicationGraceTicks(ping);
        }
        PendingAuthDischarge a;
        while ((a = pendingAuthDischarges.peekFirst()) != null && snapshotTick - a.tick > dischargeGraceTicks) {
            pendingAuthDischarges.pollFirst();
            dischargeServerOnly++;
            if (snapshotTick - a.tick > dischargeMatchTolTicks()) {
                dischargeAdjudicatedExpiry++;
            } else {
                dischargeAdjudicatedCoverage++;
            }
            logDischargeMiss("server-only", a.tick, snapshotTick, dischargeGraceTicks);
            // Never act inline (this also runs MID-SCAN from recordLocalDischarge) —
            // queue the treatment; processDischargeTreatments drains at the drain site, same frame.
            if (DISCHARGE_RECONCILE_METER) {
                adoptTreatmentQueue.addLast(a);
            }
        }
        // ASYMMETRIC: the retire (local) side always waits the FULL pairing
        // tolerance — a Δ∈(G,60] server twin then pairs normally instead of minting adopt+retire
        // churn, and fast-retire's measured ~50% false precision (180241, G=9 on s2 manual play)
        // never kills another real bolt. Adopt (auth side, above) keeps coverage+grace.
        PendingLocalDischarge l;
        while ((l = pendingLocalDischarges.peekFirst()) != null && snapshotTick - l.tick > dischargeMatchTolTicks()) {
            pendingLocalDischarges.pollFirst();
            dischargeClientOnly++;
            dischargeRetireAtTol++;
            armRearmLatch(l.mount, snapshotTick, Long.MIN_VALUE); // Unconfirmed — hold past pending-clear
            logDischargeMiss("client-only", l.tick, snapshotTick, dischargeMatchTolTicks());
            if (DISCHARGE_RECONCILE_METER) {
                retireTreatmentQueue.addLast(l);
            }
        }
    }

    /**
     * The capped discharge-miss tick-pin line (the counters keep counting past the cap).
     * {@code boundTicks} = the bound THIS side expired on — grace G for server-only, the full pairing
     * tolerance for client-only (a flight printed 60 on server-only rows that expired at G = 9).
     */
    void logDischargeMiss(String side, long tick, long snapshotTick, long boundTicks) {
        if (dischargeMissLines >= DISCHARGE_MISS_LOG_MAX) {
            return;
        }
        dischargeMissLines++;
        PilotWatchLog.info("predict pairing discharge-miss #" + dischargeMissLines + " (" + side + "): tick=" + tick
                + " unmatched after " + boundTicks + " ticks (snapTick=" + snapshotTick + ")"
                + (dischargeMissLines == DISCHARGE_MISS_LOG_MAX ? " [pin cap reached; counters continue]" : ""));
    }

    /**
     * Drain this frame's expiry treatment queues (see the
     * state block). RETIREs run first so the retired ring is current when ADOPTs check false-late.
     * Counters count LAW DECISIONS in both modes; engine writes only under apply.
     */
    void processDischargeTreatments(long snapshotTick) {
        if (!DISCHARGE_RECONCILE_METER) {
            return;
        }
        try {
            PendingLocalDischarge l;
            while ((l = retireTreatmentQueue.pollFirst()) != null) {
                executeRetire(l, snapshotTick, false);
            }
            PendingAuthDischarge a;
            while ((a = adoptTreatmentQueue.pollFirst()) != null) {
                executeAdopt(a, snapshotTick);
            }
        } catch (Throwable ignore) {
            // treatment lane; never break the watch
        }
    }

    /**
     * One RETIRE treatment — an expired client_only entry ({@code early} = the ammo-delta path):
     * {@link DischargeReconcileLaw#retireAction} decides; the engine write is its OWN grammar —
     * {@code removeEntity} = the deferred silent reap, missiles {@code flameOut()} = the engine's
     * own fizzle (no warhead). Already-dealt local damage deliberately stands.
     */
    void executeRetire(PendingLocalDischarge l, long snapshotTick, boolean early) {
        boolean missile = l.proj instanceof MissileAPI;
        boolean inPlay = l.proj != null && plugin.engine != null && plugin.engine.isEntityInPlay(l.proj);
        // (A measured run: 197 phantom irpulse bolts flew full flight because the
        // sub-eps class-skip assumed a blind server detector — while the same run's GPEE carried
        // 403 server irpulse emits). A sub-eps mount the server has confirmed on is COVERED ⇒
        // its unconfirmed bolt is genuine over-prediction (own autofire/arc lead during turns);
        // retire it like any class-ok bolt. client_only keeps counting — the divergence stays
        // visible; retire_false_late (a later ADOPT re-spawns it) stays the self-heal net.
        boolean subEpsConfirmed = !l.retireClassOk && DischargeReconcileLaw.subEpsConfirmable(
                l.subEpsClass, l.shapeOk, l.mount >= 0 && dischargeAuthConfirmedMounts.get(l.mount));
        int action = DischargeReconcileLaw.retireAction(l.retireClassOk || subEpsConfirmed,
                l.multiSpawn, inPlay, missile);
        if (action == DischargeReconcileLaw.RETIRE_SKIP_CLASS) {
            if (l.subEpsClass) {
                dischargeRetireSkipClassSubEps++; // own INFO token
            } else {
                dischargeRetireSkipClass++;
            }
            return;
        }
        if (l.adoptEcho) {
            // Dedup, not a treatment token: the adopted spawn already presents
            // this server shot; under apply the local twin is removed so the pair never presents
            // doubled. Deliberately NOT added to the retired ring (the adopt already happened —
            // ring entry could only mint a false retire_false_late) and excluded from the graded
            // true-divergence numerator. Class gate stays FIRST (burst/beam/sub-eps entries keep
            // class-skipping — a 1:N class echo could over-remove real shells).
            dischargeRetireAdoptEcho++;
            dischargeReconcileLane.log("predict discharge-reconcile RETIRE-ECHO @snapTick=" + snapshotTick
                    + ": tick=" + l.tick + " mount=" + l.mount
                    + " (dedup: adopted twin already presented)"
                    + (DISCHARGE_RECONCILE_APPLY ? "" : " (meter)"));
            if (DISCHARGE_RECONCILE_APPLY && inPlay) {
                if (missile) {
                    ((MissileAPI) l.proj).flameOut();
                } else {
                    plugin.engine.removeEntity(l.proj);
                }
            }
            return;
        }
        if (action == DischargeReconcileLaw.RETIRE_SKIP_GONE) {
            dischargeRetireSkipGone++;
            return;
        }
        if (early) {
            dischargeRetireEarlyAmmo++;
        } else if (action == DischargeReconcileLaw.RETIRE_FLAMEOUT) {
            dischargeRetireFlameout++;
        } else {
            dischargeRetireRemoved++;
        }
        if (subEpsConfirmed) {
            dischargeRetireSubEpsConfirmed++; // Subset of the primitive counters above
        }
        pushBounded(retiredRecentRing, DischargeReconcileLaw.RETIRED_RING_CAP,
                new long[] {l.tick, l.mount});
        dischargeReconcileLane.log("predict discharge-reconcile RETIRE" + (early ? " (early-ammo)" : "")
                + " @snapTick=" + snapshotTick + ": tick=" + l.tick + " mount=" + l.mount
                + " via=" + (action == DischargeReconcileLaw.RETIRE_FLAMEOUT ? "flameOut" : "remove")
                + (DISCHARGE_RECONCILE_APPLY ? "" : " (meter)"));
        if (DISCHARGE_RECONCILE_APPLY) {
            if (action == DischargeReconcileLaw.RETIRE_FLAMEOUT) {
                ((MissileAPI) l.proj).flameOut();
            } else {
                plugin.engine.removeEntity(l.proj);
            }
        }
    }

    /**
     * One ADOPT treatment — an expired server_only entry: false-late accounting against the
     * retired ring (a match means the faded bolt was real; this adopt respawns it — self-healing,
     * honestly counted), then the presenter's faithful OWN-role spawn under apply. The spawned
     * bolt joins {@link #presentedOwnProj} so the scan consumes it FIRST
     * (counting it would mint a false client_only; re-DECLARING it would make the server fire it
     * AGAIN); devastator-spec shells land role "presented" via the fuse meter, the foe idiom.
     */
    void executeAdopt(PendingAuthDischarge a, long snapshotTick) {
        java.util.Iterator<long[]> ring = retiredRecentRing.iterator();
        while (ring.hasNext()) {
            long[] r = ring.next();
            if (DischargeReconcileLaw.falseLateMatch(r[0], (int) r[1], a.tick, a.mount)) {
                // CONSUME the matched entry — one false retire counts ONCE
                // (180241: the tick-4402 retire counted once per subsequent adopt, 39 vs 17).
                ring.remove();
                dischargeRetireFalseLate++;
                dischargeReconcileLane.log("predict discharge-reconcile FALSE-LATE: retired tick="
                        + r[0] + " mount=" + r[1] + " matches adopt tick=" + a.tick
                        + " mount=" + a.mount + " (self-heals: the adopt respawns it)");
                break;
            }
        }
        // Every adjudicated server_only event is server-confirmed truth at
        // (tick, mount) — remember it so a LATE local twin records echo-class (dedup), whatever
        // the adopt outcome below (skip_unresolved included: the server still fired).
        pushBounded(adoptedRecentRing, DischargeReconcileLaw.RETIRED_RING_CAP,
                new long[] {a.tick, a.mount});
        burstPhase.onAdopt(plugin.ownShip, a.mount, a.tick); // last_adopt + the local-state row
        boolean resolvable = plugin.ownShip != null && plugin.ownShip.isAlive();
        long ageTicks = DischargeReconcileLaw.adoptAgeTicks(snapshotTick, a.tick);
        if (DischargeReconcileLaw.adoptAction(resolvable) == DischargeReconcileLaw.ADOPT_SKIP_UNRESOLVED) {
            dischargeAdoptSkipUnresolved++;
            dischargeReconcileLane.log("predict discharge-reconcile ADOPT SKIP (unresolved own body)"
                    + " @snapTick=" + snapshotTick + ": tick=" + a.tick + " mount=" + a.mount);
            return;
        }
        if (!DISCHARGE_RECONCILE_APPLY) {
            dischargeAdoptCount++;
            dischargeReconcileLane.log("predict discharge-reconcile ADOPT @snapTick=" + snapshotTick
                    + ": tick=" + a.tick + " mount=" + a.mount + " age=" + ageTicks + " (meter)");
            return;
        }
        Object spawned = plugin.presenter.presentOwnAdoptedDischarge(a.event, plugin.ownShip, ageTicks);
        if (spawned == null) {
            dischargeAdoptSkipUnresolved++; // presenter-level skip (mount unresolved / spawn threw)
            return;
        }
        dischargeAdoptCount++;
        seedOwnAdoptMount(a.event.mountIndex(), a.event.aimFacingDeg(), a.tick);
        presentedOwnProj.add(spawned);
        // mtrack registers BEFORE enroll (factory-AI class read).
        plugin.mtrack.register(spawned, a.event.entityId(), a.event.mountIndex(),
                a.event.simTick(), "own", null);
        // Own-ADOPT twins are lane-managed (wire-born,
        // no local prediction to protect); own PREDICTED missiles stay unenrolled.
        plugin.missileLane.enroll(spawned, a.event.entityId(), a.event.mountIndex(), a.event.simTick());
        // Adopted devastator-spec shells land role "presented" (the foe idiom).
        plugin.fuseMeter.tagPresented(spawned, a.event, ageTicks, plugin.advFrames, snapshotTick);
        dischargeReconcileLane.log("predict discharge-reconcile ADOPT @snapTick=" + snapshotTick
                + ": tick=" + a.tick + " mount=" + a.mount + " age=" + ageTicks);
    }

    /**
     * The own-adopt mount SEED (reader 01 Q5/Q6). The
     * wire carries no mount angle and the rejoin adopt restores ammo/cooldown only, so after a
     * server-first stretch the local turret starts wherever the local engine left it (
     * 5 adopted server-only shots, then the first local shot on mount 9 paired 54.88°
     * off). An adopted discharge's bound wire aim is the mount angle at that tick (a missile rack's
     * carries its barrel offset, ≤ 7°, which the cursor/AI re-aims): write
     * it into the local mount when {@link OwnMountSeedLaw#ownAdoptSeedDecision} holds — the
     * mount has not fired locally on this body, the aim is bound, it is not a beam, and its
     * group's autofire TOGGLE is on. {@code isAutofiring()} reads
     * the toggle only — vanilla forces the SELECTED group's autofire off and aims it by the cursor
     * while the toggle still reads true, so a selected, toggled group IS seeded (a recorded trigger
     * mount was exactly that, {@code sel=true}); the row's {@code sel=} token measures the class on
     * the flight. No suspend: the autofire AI keeps turning from the seeded angle. Cost = one bit
     * test + one mount/group read per own-adopt, nothing per frame; no delay.
     * The capped row is the start-angle vs target-pick discriminator. A throw counts
     * {@code own_adopt_mount_seed_faults} (the adopt itself already spawned).
     */
    private void seedOwnAdoptMount(int idx, float aim, long tick) {
        try {
            bindSeedBody();
            if (idx < 0 || idx >= 64) {
                return;
            }
            WeaponAPI weapon = plugin.ownShip.getAllWeapons().get(idx);
            WeaponGroupAPI g = plugin.ownShip.getWeaponGroupFor(weapon);
            if (!OwnMountSeedLaw.ownAdoptSeedDecision(OwnMountSeedLaw.mountFired(ownMountSeedMask, idx),
                    !Float.isNaN(aim), weapon.isBeam(), g != null && g.isAutofiring())) {
                return;
            }
            float local = weapon.getCurrAngle();
            seedMountAngle(weapon, aim);
            if (++ownAdoptMountSeeded <= OWN_ADOPT_MOUNT_SEED_ROWS_MAX) {
                PilotWatchLog.info("predict own-adopt mount-seed mount=" + idx
                        + " local=" + PredictReconcileCombatPlugin.fmt2(local)
                        + " wire=" + PredictReconcileCombatPlugin.fmt2(aim) + " tick=" + tick
                        + " sel=" + (g == plugin.ownShip.getSelectedGroupAPI() ? 1 : 0)); // the selected-group bit
            }
        } catch (Throwable t) {
            ownAdoptMountSeedFaults++;
        }
    }

    /** The seed mask's body binding — IDENTITY, not equals. Every own-body change resets the mask
     *  through this ONE check, no per-path hook: first join (null → body), respawn (new body),
     *  round wrap / MRST (new body or a new plugin instance), the post-lift rejoin (a fresh
     *  mission = a new plugin), the RosterAppend re-point (new body). A RESUME after a stale feed
     *  keeps the SAME body ⇒ the mask is kept (the local mount's history is continuous). */
    private void bindSeedBody() {
        if (plugin.ownShip != ownMountSeedBody) {
            ownMountSeedBody = plugin.ownShip;
            ownMountSeedMask = 0L;
        }
    }

    /** The ONE seed write: {@code setCurrAngle} WITHOUT {@code setSuspendAutomaticTurning} (the
     *  {@code SameSideAimLane} pin write minus its suspend — a suspended mount would freeze on the
     *  seeded angle). */
    static void seedMountAngle(WeaponAPI w, float aim) {
        w.setCurrAngle(aim);
    }

    /**
     * The ammo-delta EARLY-retire pass + the per-mount PRIOR-fresh-frame auth
     * cache refresh. Auth-vs-auth by construction: both compare operands
     * are wire values (the record-time cache vs this fresh frame's mounts) — ownFireLock-immune.
     * Fresh frames only; the cache refresh runs POST-compare so record-time reads always see the
     * PRIOR frame. Errs missed-over-false: ambiguity demotes to the 1 s pairing path, never
     * retires; the hold-last hazard is excluded wholesale by the sticky {@link #awsdLaneSeen}
     * decode-lane signal (fail-closed).
     */
    void maintainDischargeReconcile(ServerEntityPose pose, boolean freshSnapshot, long simTick) {
        if (!PredictReconcileCombatPlugin.EVENT_REPLAY_ENABLED || !DISCHARGE_RECONCILE_METER || !freshSnapshot || pose == null) {
            return;
        }
        try {
            if (!pose.hasWeaponMounts()) {
                return;
            }
            boolean exactLane = !awsdLaneSeen;
            java.util.Iterator<PendingLocalDischarge> it = pendingLocalDischarges.iterator();
            List<PendingLocalDischarge> earlyRetires = null;
            while (it.hasNext()) {
                PendingLocalDischarge l = it.next();
                if (!l.ammoEligible || l.multiSpawn) {
                    continue;
                }
                cc.starsector.client.net.ServerWeaponMountState mnt = null;
                for (cc.starsector.client.net.ServerWeaponMountState m : pose.weaponMounts()) {
                    if (m.mountIndex() == l.mount) {
                        mnt = m;
                        break;
                    }
                }
                int action = DischargeReconcileLaw.earlyRetireAction(exactLane,
                        mnt != null, mnt != null && mnt.usesAmmo(),
                        l.authTickAtRecord, simTick,
                        l.authAmmoAtRecord, mnt != null ? mnt.ammo() : -1);
                if (action == DischargeReconcileLaw.EARLY_DISQUALIFY) {
                    l.ammoEligible = false;
                    dischargeEarlyDisqualified++;
                } else if (action == DischargeReconcileLaw.EARLY_DROP_LANDED) {
                    l.ammoEligible = false;
                    dischargeEarlyDroppedLanded++;
                } else if (action == DischargeReconcileLaw.EARLY_RETIRE) {
                    // Resolved BEFORE the 1 s expiry — client_only deliberately never counts it
                    // (the treatments count what RESOLVED the miss, frozen).
                    it.remove();
                    armRearmLatch(l.mount, simTick, Long.MIN_VALUE); // the loop's exact edge
                    if (earlyRetires == null) {
                        earlyRetires = new ArrayList<>(4);
                    }
                    earlyRetires.add(l);
                }
            }
            if (earlyRetires != null) {
                for (PendingLocalDischarge l : earlyRetires) {
                    executeRetire(l, simTick, true);
                }
            }
            for (cc.starsector.client.net.ServerWeaponMountState m : pose.weaponMounts()) {
                dischargeMountAuthCache.put(m.mountIndex(),
                        new long[] {m.ammo(), m.usesAmmo() ? 1L : 0L, simTick});
            }
        } catch (Throwable ignore) {
            // reconcile lane; never break the watch
        }
    }


    /**
     * The own fire-lock lane counters, one greppable line per meter flush (the
     * counter-line discipline). Silent when {@code cc.predict.ownFireLock=off}.
     */
    void logOwnFireLockMeter(String where) {
        if (!OWN_FIRE_LOCK_METER) {
            return;
        }
        PilotWatchLog.info("predict ownlock-meter [" + where + "]:"
                + " mode=" + OWN_FIRE_LOCK_MODE
                + " ownlock_carried=" + ownFireLockLane.framesCarried
                + " ownlock_missing=" + ownFireLockLane.framesMissing
                + " ownlock_flux_pins=" + ownlockFluxPins
                + " ownlock_flux_band_skips=" + ownlockFluxBandSkips // the ownlock_ family prefix
                + " ownlock_boost_clock_zeros=" + ownlockBoostClockZeros
                + " ownlock_flux_skips_state=" + ownlockFluxSkipsState
                + " ownlock_ammo_writes=" + ownlockAmmoWrites
                + " predict_ownlock_ammo_rows=" + ownlockAmmoRowsLogged
                + " ownlock_ammo_skips_busy=" + ownlockAmmoSkipsBusy
                + " ownlock_cd_nudges=" + ownlockCdNudges
                + " ownlock_cd_skips_implausible=" + ownlockCdSkipsImplausible
                + " ownlock_cd_skips_fast=" + ownlockCdSkipsFast
                + " ownlock_cd_skips_busy=" + ownlockCdSkipsBusy
                + " ownlock_phase_windows=" + ownlockPhaseWindows
                + " ownlock_cd_phase_rebases=" + ownlockCdPhaseRebases
                + " ownlock_cd_phase_lengthen_refused=" + ownlockCdPhaseLengthenRefused
                + " ownlock_cd_lead_drains=" + ownlockCdLeadDrains
                + " ownlock_cd_lead_drain_ticks=" + ownlockCdLeadDrainTicks
                + " ownlock_cd_lead_drain_skips=" + ownlockCdLeadDrainSkips
                // The drain's throw token + the told-wait histogram
                + " ownlock_cd_lead_drain_threw=" + ownlockCdLeadDrainThrew
                + " ownlock_decl_wait_hist=" + ownlockDeclWaitHist[0] + "/" + ownlockDeclWaitHist[1] + "/"
                + ownlockDeclWaitHist[2] + "/" + ownlockDeclWaitHist[3] + "/" + ownlockDeclWaitHist[4] + "/"
                + ownlockDeclWaitHist[5] + "/" + ownlockDeclWaitHist[6] + "/" + ownlockDeclWaitHist[7]
                + " ownlock_cd_hold_auth_arming=" + ownlockCdHoldAuthArming
                + " ownlock_cd_hold_auth_burst=" + ownlockCdHoldAuthBurst
                + " ownlock_ammo_hold_pending=" + ownlockAmmoHoldPending
                + " ownlock_cd_hold_pending=" + ownlockCdHoldPending
                + " ownlock_ammo_hold_latched=" + ownlockAmmoHoldLatched
                + " ownlock_ammo_hold_owed=" + ownlockAmmoHoldOwed
                + " ownlock_cd_hold_latched=" + ownlockCdHoldLatched
                + " ownlock_latch_armed=" + ownlockLatchArmed
                + " ownlock_latch_rel_truth=" + ownlockLatchRelTruth
                // rel_press retired — the token is gone, not zero-stuffed
                + " ownlock_latch_rel_timeout=" + ownlockLatchRelTimeout
                + " weapon_state_disable_deferred=" + weaponStateDisableDeferred
                + " ownlock_reconcile_threw=" + ownlockReconcileThrew //
                + " own_hull_epoch=" + ownHullEpoch
                + reloadHold.meterTokens() + burstPhase.meterTokens());
    }

    /**
     * The own beam-cycle drive (own ship, every frame; thin relay — logic in
     * {@link OwnBeamCycleLane}) and its counter line. Silent/inert when
     * {@code cc.predict.ownBeamCycle=off}.
     */
    void maintainOwnBeamCycle(com.fs.starfarer.api.combat.ShipAPI own, ServerEntityPose pose,
            boolean freshSnapshot, long simTick) {
        if (OWN_BEAM_CYCLE_METER) {
            ownBeamCycle.advanceFrame(own, pose, freshSnapshot, simTick, OWN_BEAM_CYCLE_APPLY);
        }
    }

    void logOwnBeamCycleMeter(String where) {
        if (!OWN_BEAM_CYCLE_METER) {
            return;
        }
        PilotWatchLog.info("predict own-beam-cycle [" + where + "]:"
                + " mode=" + OWN_BEAM_CYCLE_MODE
                + ownBeamCycle.meterTokens());
    }

    /**
     * The discharge-reconcile lane counters, one greppable line per meter flush
     * (the counter-line discipline). Silent when
     * {@code cc.predict.dischargeReconcile=off}.
     */
    void logDischargeReconcileMeter(String where) {
        if (!DISCHARGE_RECONCILE_METER) {
            return;
        }
        long corrections = dischargeAdoptCount + dischargeRetireRemoved + dischargeRetireFlameout
                + dischargeRetireEarlyAmmo;
        String perMin = "n/a";
        if (plugin.combatWallStartNanos != Long.MIN_VALUE) {
            double minutes = (System.nanoTime() - plugin.combatWallStartNanos) / 60_000_000_000.0d;
            if (minutes > 0.01d) {
                perMin = PredictReconcileCombatPlugin.fmt2(corrections / minutes);
            }
        }
        PilotWatchLog.info("predict discharge-reconcile [" + where + "]:"
                + " mode=" + DISCHARGE_RECONCILE_MODE
                + " exact_ammo_lane=" + !awsdLaneSeen
                + " adopt_count=" + dischargeAdoptCount
                + " adopt_skip_unresolved=" + dischargeAdoptSkipUnresolved
                + " own_adopt_mount_seeded=" + ownAdoptMountSeeded
                + " own_adopt_mount_seed_faults=" + ownAdoptMountSeedFaults
                + " retire_removed=" + dischargeRetireRemoved
                + " retire_flameout=" + dischargeRetireFlameout
                + " retire_early_ammo=" + dischargeRetireEarlyAmmo
                + " retire_skip_class=" + dischargeRetireSkipClass
                + " retire_skip_class_subeps=" + dischargeRetireSkipClassSubEps
                + " retire_subeps_confirmed=" + dischargeRetireSubEpsConfirmed
                + " retire_skip_gone=" + dischargeRetireSkipGone
                + " retire_false_late=" + dischargeRetireFalseLate
                + " retire_adopt_echo=" + dischargeRetireAdoptEcho
                + " early_disqualified=" + dischargeEarlyDisqualified
                + " early_dropped_landed=" + dischargeEarlyDroppedLanded
                + " adjudicated_coverage=" + dischargeAdjudicatedCoverage
                + " adjudicated_expiry=" + dischargeAdjudicatedExpiry
                + " retire_at_tol=" + dischargeRetireAtTol
                + " mount_index_oob=" + dischargeMountIndexOob //
                + " grace_ticks=" + dischargeGraceTicks
                + " corrections_per_min=" + perMin);
    }

    // === AUTOFIRE LEAD-ERROR METER (measure-only; feeds the
    // autofire-feel arc). The engine's autofire solver leads in the SHOOTER frame — it subtracts
    // own velocity because the spawned bolt inherits ship velocity — so a
    // client whose PREDICTED own-velocity ≠ server truth mis-subtracts and over-leads in the
    // strafe direction, ping-scaled (observed in play). Three signed-arc components (deg),
    // sampled per autofiring projectile-weapon mount with a live target, at fresh snapshots:
    //   lead_err       = engine aim vs textbook intercept @ client ownVel (solver-health control);
    //   ownvel_term    = intercept@ownVel vs intercept@0 (the term strafe corrupts);
    //   vel_divergence = intercept@clientOwnVel vs intercept@serverOwnVel (reconcile pose) — the
    //                    ping-dependent number the Aurora-A/B watch regresses.
    // Classed autofire/manual mirroring declareFireDischarge (SELECTED autofiring group = manual
    // control); non-autofiring groups carry no engine aim and are unmeasurable. Ballistic-solve
    // weapons only (ProjectileWeaponSpecAPI): beams and MISSILE racks skip. ===

    /** One class-lane (autofire | manual) of lead-error aggregates — count + meanAbs/maxAbs per
     *  component, plus per-mount maxAbs triples lead|ownvel|veldiv (mount = getAllWeapons()
     *  ordinal; -1 = unresolved). Cumulative across the watch like the rebase counters. */
    private static final class LeadBank {
        int count;
        double leadErrSumAbs;
        float leadErrMaxAbs;
        double ownvelSumAbs;
        float ownvelMaxAbs;
        double velDivSumAbs;
        float velDivMaxAbs;
        final java.util.HashMap<Integer, float[]> mountMaxAbs = new java.util.HashMap<>();

        void record(int mount, float leadErr, float ownvelTerm, float velDiv) {
            count++;
            float a = Math.abs(leadErr);
            float b = Math.abs(ownvelTerm);
            float c = Math.abs(velDiv);
            leadErrSumAbs += a;
            if (a > leadErrMaxAbs) {
                leadErrMaxAbs = a;
            }
            ownvelSumAbs += b;
            if (b > ownvelMaxAbs) {
                ownvelMaxAbs = b;
            }
            velDivSumAbs += c;
            if (c > velDivMaxAbs) {
                velDivMaxAbs = c;
            }
            float[] m = mountMaxAbs.get(mount);
            if (m == null) {
                m = new float[3];
                mountMaxAbs.put(mount, m);
            }
            if (a > m[0]) {
                m[0] = a;
            }
            if (b > m[1]) {
                m[1] = b;
            }
            if (c > m[2]) {
                m[2] = c;
            }
        }
    }

    final LeadBank autofireLeadAutofire = new LeadBank();
    final LeadBank autofireLeadManual = new LeadBank();
    // Preallocated intercept scratch (render-thread only, per the class threading contract)
    private final Vector2f leadRefClient = new Vector2f();
    private final Vector2f leadRefZero = new Vector2f();
    private final Vector2f leadRefServer = new Vector2f();

    /**
     * The lead-error meter's driver — called from the plugin's fresh-snapshot reconcile block beside
     * {@link #maintainOwnFireLock} (sampling at snapshot arrival keeps the pose-velocity compare
     * at its freshest). Measure-only: reads engine + pose state, writes nothing back.
     */
    void maintainAutofireLeadMeter(ShipAPI own, ServerEntityPose pose, boolean freshSnapshot,
            long simTick) {
        if (!freshSnapshot || own == null || pose == null || !own.isAlive()) {
            return;
        }
        try {
            List<WeaponGroupAPI> groups = own.getWeaponGroupsCopy();
            if (groups == null || groups.isEmpty()) {
                return;
            }
            Vector2f ownLoc = own.getLocation();
            Vector2f ownVel = own.getVelocity();
            if (ownLoc == null || ownVel == null) {
                return;
            }
            WeaponGroupAPI selected = own.getSelectedGroupAPI();
            float ownR = own.getCollisionRadius();
            List<WeaponAPI> allWeapons = own.getAllWeapons();
            for (WeaponGroupAPI grp : groups) {
                if (grp == null || !grp.isAutofiring()) {
                    continue; // off autofire the player aims by mouse — no engine aim to grade
                }
                LeadBank bank = (grp == selected) ? autofireLeadManual : autofireLeadAutofire;
                List<WeaponAPI> weapons = grp.getWeaponsCopy();
                if (weapons == null) {
                    continue;
                }
                for (WeaponAPI w : weapons) {
                    if (w == null || !(w.getSpec() instanceof ProjectileWeaponSpecAPI)) {
                        continue; // ballistic-solve class only — beams + MISSILE racks skip
                    }
                    float projSpeed = w.getProjectileSpeed();
                    if (!(projSpeed > 0f) || Float.isInfinite(projSpeed)) {
                        continue;
                    }
                    AutofireAIPlugin af = grp.getAutofirePlugin(w);
                    if (af == null) {
                        continue;
                    }
                    ShipAPI tgt = af.getTargetShip();
                    Vector2f engineAim = af.getTarget();
                    if (tgt == null || engineAim == null || !tgt.isAlive()) {
                        continue;
                    }
                    Vector2f tgtLoc = tgt.getLocation();
                    Vector2f tgtVel = tgt.getVelocity();
                    if (tgtLoc == null || tgtVel == null) {
                        continue;
                    }
                    float tgtR = tgt.getCollisionRadius();
                    interceptPoint(ownLoc, ownR, ownVel.x, ownVel.y, tgtLoc, tgtVel, tgtR,
                            projSpeed, leadRefClient);
                    interceptPoint(ownLoc, ownR, 0f, 0f, tgtLoc, tgtVel, tgtR,
                            projSpeed, leadRefZero);
                    interceptPoint(ownLoc, ownR, pose.velX(), pose.velY(), tgtLoc, tgtVel, tgtR,
                            projSpeed, leadRefServer);
                    float engineDeg = bearingDegFrom(ownLoc, engineAim);
                    float clientDeg = bearingDegFrom(ownLoc, leadRefClient);
                    float zeroDeg = bearingDegFrom(ownLoc, leadRefZero);
                    float serverDeg = bearingDegFrom(ownLoc, leadRefServer);
                    if (Float.isNaN(engineDeg) || Float.isNaN(clientDeg) || Float.isNaN(zeroDeg)
                            || Float.isNaN(serverDeg)) {
                        continue; // degenerate geometry or NaN pose vel — no honest sample
                    }
                    bank.record(allWeapons == null ? -1 : allWeapons.indexOf(w),
                            signedArcDeg(clientDeg, engineDeg),
                            signedArcDeg(zeroDeg, clientDeg),
                            signedArcDeg(serverDeg, clientDeg));
                }
            }
        } catch (Throwable ignore) {
            // measure-only lane: never let the meter break the render frame
        }
    }

    /**
     * Pure reimplementation of the engine autofire intercept and its closing dot
     * with aimAcc pinned 1 (the aimAcc multiplies drop) and the ballistic
     * ship-shooter branch only (the missile branches never apply to our shooter).
     * Own velocity enters BOTH the lead vector and the closing dot — zeroing it
     * for the @0 reference must zero both, which passing it as plain floats guarantees.
     */
    private static void interceptPoint(Vector2f shooterLoc, float shooterR, float ownVelX,
            float ownVelY, Vector2f tgtLoc, Vector2f tgtVel, float tgtR, float projSpeed,
            Vector2f out) {
        float dx = shooterLoc.x - tgtLoc.x;
        float dy = shooterLoc.y - tgtLoc.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float relX = tgtVel.x - ownVelX;
        float relY = tgtVel.y - ownVelY;
        float closing = 0f;
        if (dist > 0f) {
            closing = (dx / dist) * relX + (dy / dist) * relY;
        }
        dist -= (shooterR + tgtR) * 0.75f;
        if (dist < 0f) {
            dist = 0f;
        }
        float approach = closing + projSpeed;
        if (approach <= 10f) {
            approach = 20f;
        }
        float t = dist / approach;
        t = (dist - closing * t) / approach;
        if (t < 0f) {
            t = 0f;
        }
        if (t > 10f) {
            t = 10f;
        }
        out.x = tgtLoc.x + relX * t;
        out.y = tgtLoc.y + relY * t;
    }

    /** Heading of {@code to} as seen from {@code from}, degrees; NaN on zero offset. */
    private static float bearingDegFrom(Vector2f from, Vector2f to) {
        float dx = to.x - from.x;
        float dy = to.y - from.y;
        if (dx == 0f && dy == 0f) {
            return Float.NaN;
        }
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /** Signed shortest arc from {@code fromDeg} to {@code toDeg} (the {@link #pollBest} aim-delta idiom). */
    private static float signedArcDeg(float fromDeg, float toDeg) {
        return ((toDeg - fromDeg + 540f) % 360f) - 180f;
    }

    /**
     * One greppable line per meter flush; rows land per-seat in the watch
     * sidelogs. Always-on (measure-only, no knob): a zero count is itself the lane-alive proof.
     */
    void logAutofireLeadMeter(String where) {
        PilotWatchLog.info("predict autofire-lead-meter [" + where + "]:"
                + leadBankTokens("_autofire", autofireLeadAutofire)
                + leadBankTokens("_manual", autofireLeadManual));
    }

    private static String leadBankTokens(String cls, LeadBank b) {
        StringBuilder mounts = new StringBuilder();
        for (java.util.Map.Entry<Integer, float[]> e : b.mountMaxAbs.entrySet()) {
            if (mounts.length() > 0) {
                mounts.append(',');
            }
            float[] m = e.getValue();
            mounts.append(e.getKey()).append(':')
                    .append(PredictReconcileCombatPlugin.fmt2(m[0])).append('|')
                    .append(PredictReconcileCombatPlugin.fmt2(m[1])).append('|')
                    .append(PredictReconcileCombatPlugin.fmt2(m[2]));
        }
        int n = b.count;
        return " predict_autofire_lead_count" + cls + "=" + n
                + " predict_autofire_lead_err_mean_abs" + cls + "="
                + PredictReconcileCombatPlugin.fmt2(n > 0 ? b.leadErrSumAbs / n : 0d)
                + " predict_autofire_lead_err_max_abs" + cls + "="
                + PredictReconcileCombatPlugin.fmt2(b.leadErrMaxAbs)
                + " predict_autofire_ownvel_term_mean_abs" + cls + "="
                + PredictReconcileCombatPlugin.fmt2(n > 0 ? b.ownvelSumAbs / n : 0d)
                + " predict_autofire_ownvel_term_max_abs" + cls + "="
                + PredictReconcileCombatPlugin.fmt2(b.ownvelMaxAbs)
                + " predict_autofire_vel_divergence_mean_abs" + cls + "="
                + PredictReconcileCombatPlugin.fmt2(n > 0 ? b.velDivSumAbs / n : 0d)
                + " predict_autofire_vel_divergence_max_abs" + cls + "="
                + PredictReconcileCombatPlugin.fmt2(b.velDivMaxAbs)
                + " predict_autofire_lead_mounts" + cls + "="
                + (mounts.length() == 0 ? "none" : mounts.toString());
    }
}
