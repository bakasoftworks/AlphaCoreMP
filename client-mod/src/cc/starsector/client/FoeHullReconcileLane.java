package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.ArrayDeque;

/**
 * The PRCP cohesion seam — the foe/remote
 * HULL reconcile lane, extracted VERBATIM from {@link PredictReconcileCombatPlugin} (the adjourned
 * {@code FoeHullReconcileLane} extraction; trigger = first structural PRCP touch after the leg-5
 * proving watch; method bodies byte-identical modulo receivers). Owns the lane STATE (the local
 * frame clock, the stale/suspension latch, the 1v1 ledger twin, the cumulative totals) plus the
 * per-frame drivers, and hosts {@link HullLedger} + its replay-entry deque type.
 * PRCP keeps (the FireLaneCoordinator coupling model): the knob statics (doc order; booleans enter
 * via the ctor, the mode string is read back for the meter line), the shared {@code mirrorHull}
 * write primitive (own-ship reconcile co-user, called via the plugin backref), the shared
 * {@code FOE_HULL_FLOOR_FRAC} (HullCapCore axis-B co-user; passed in at construction), the
 * cap-listener {@code recordApplied} relays, and the bar/walker/meter feed call sites.
 * Threading contract unchanged by the move: render-thread only.
 */
final class FoeHullReconcileLane {

    /** Replay-entry expiry in LOCAL SIM SECONDS (was 120 RENDER frames = 0.22 s at
     *  a 540-fps client: the pre-ack expiry / bar-too-HIGH bug; the clock is the Σ of
     *  PRCP {@code advance(amount)} sim deltas: pause-immune, frame-rate-immune, and still immune
     *  to a frozen wire tick). Verified game-free by {@code CcHullLedgerContract} row (e). */
    private static final double HULL_REPLAY_EXPIRY_SEC = 2.0;
    // Stale-feed suspension threshold = FoeHullStaleLatchLaw.SUSPEND_TICKS (sim
    // ticks on the sim clock, never render frames — the WHY + the measured flight sit on the law).
    /** Seat-remote hull-walk stride, in FRESH SNAPSHOTS per ledger (first
     *  eligible snapshot samples immediately, then every Nth) — the fh walker's foe subject binds
     *  side-1 foes only ({@code walkSubjectFor}), so side-0 SEAT replicas (the wireId=2 class) had
     *  ZERO cadence samples when the debug bars were seen RESTING apart. */
    private static final int SEAT_HULL_WALK_EVERY = 32;
    /** Per-life line cap for the seat-remote hull walk (bounded log law; counters keep running). */
    private static final int SEAT_HULL_WALK_LINE_CAP = 300;
    /** Absorb/retire epsilon in hp — a single-frame move below this is not folded on its own; instead
     * the per-frame sub-EPS residual ACCUMULATES ({@code HullLedger.unreportedAcc})
     *  and folds once it crosses EPS, so a slow heal (the replica's LOCAL Combat Endurance elite regen,
     *  ≈0.07 hp per 540-fps frame) reaches the raw scalars instead of being dropped frame after frame
     *  and then overwritten by the apply-mode mirror write (the "client lower" bar artifact). */
    private static final float HULL_LEDGER_EPS = 0.5f;
    /** max-hull mirror dead band: the wire {@code maxHp} is the server
     *  engine's {@code round(hitpoints / hullLevel)} (AWSD keyframe i32), so ±1 hp of rounding is
     *  expected and must never flap a write; a real skew is a whole multiplier (×2 / ×3.5 / ×4 for a
     *  construction swarm) or a stat-mod bonus (whole percents). */
    private static final float MAX_HULL_MIRROR_EPS_HP = 2f;
    private static final float MAX_HULL_MIRROR_EPS_FRAC = 0.005f;
    private static final int MAX_HULL_MIRROR_LOG_MAX = 20;
    private static final int OWN_MAX_HULL_SKEW_LOG_MAX = 3;
    /** In snapshot labels: the LOCAL horizon law's pad / floor / unknown-RTT window
     *  ({@link #localHorizon}; the finite-ping cap is the 2-s backstop in labels), the late-twin ring
     *  width, and the push stamp before any snapshot label is known (resolved at the entry's first
     *  {@code onWire}). */
    static final int HZ_PAD = 6, HZ_MIN = 8, HZ_UNKNOWN = 30, TWIN_SLOTS = 4;
    static final long LABEL_UNSET = Long.MIN_VALUE;

    /**
     * The owning plugin — the supplier of the mutable cross-lane state the moved bodies read
     * ({@code foeShip} rebinds across respawns; the remote-entity registry; the shared
     * {@code mirrorHull} clamp), per the FireLaneCoordinator coupling model.
     */
    private final PredictReconcileCombatPlugin plugin;
    /** Knob booleans (the statics stay in PRCP, doc order): ledger meters on / write half on. */
    private final boolean meter;
    private final boolean apply;
    /** The axis-B floor fraction — shared with the HullCapCore cap math, so the value stays PRCP's. */
    private final float floorFrac;
    /**
     * The foe ARMOR reconcile collaborator (the 4-relay trick: FHRL
     * owns the armor lane's lifecycle so PRCP stays ZERO-byte). Stateless per-entity (no ledger),
     * own knob {@code cc.predict.foeArmorReconcile}, own meter flush. The
     * engine is now the shared {@link ArmorReconcileLane} ({@code foe()} instance; the own-ship twin
     * rides {@link OwnDetLane}).
     */
    private final ArmorReconcileLane armorLane = ArmorReconcileLane.foe();
    /** The foe PER-CELL armor lane (same 4-relay ownership; knob
     *  {@code cc.predict.armorCells}). Bound to the fold lane for the cellLive skip. */
    private final ArmorCellLane armorCellLane = ArmorCellLane.foe(ArmorCellLane.MODE);

    // === Lane state (render-thread only): the local SIM-TIME clock replay expiry runs on (
    // Σ advance() deltas — immune to a frozen wire tick AND to the render rate), the
    // consecutive-stale counter, and the suspension latch (stale feed ⇒ write + deque admission
    // off = pre-lane behavior exactly; resume clears every deque and reconciles from wire in one
    // step). ===
    private double hullLocalSimSec;
    /** Sim seconds elapsed since the last fresh snapshot (C22: the stale latch's clock; 0 on every fresh frame). */
    private double hullStaleSimSec;
    private boolean hullReconcileSuspended;
    private int hullSuspendEvents;
    /** 1v1-shape foe ledger twin (registry shape: per-RemoteEntity field); null until seeded. */
    HullLedger foeHullLedger;
    // Cumulative hull-ledger totals across closes (per-life detail = the per-entity CLOSE lines;
    // the resetForMatchReset totals line + the meter flush read these).
    private int hullLedgerCloses;
    private int hullPostCloseTotal;
    private int hullReplayExpiredTotal;
    private int hullLedgerGapTotal;
    private float hullMaxTrackErr;
    private float hullMaxHullGap;
    /** Shape A credit totals across every ledger, bumped LIVE at the ring seams (so a ledger a match
     *  reset drops without a CLOSE still counts); per-ledger detail = the CLOSE row.
     * */
    private int hullCreditMintedTotal;
    private int hullCreditedTotal;
    private int hullCreditExpiredTotal;
    private int hullCreditEvictedTotal;
    /** First-fresh-snapshot-after-RESUME drops the mint skipped (+ their hp). */
    private int hullCreditResumeSkippedTotal;
    private float hullCreditResumeSkippedHpTotal;
    private float hullCreditedHpTotal;
    private float hullCreditExpiredHpTotal;
    /** The CLOSE hp twins' lane totals (the CLOSEs held 2/40 + 3/39 mints, so
     *  mintedHp = creditedHp + expiredHp + evictedHp + clearedHp + live must hold from the summary line
     *  alone) + the {@link HullCreditRing#lateAfterExpiry} lane meters (the max age is a max, not a
     *  sum). */
    private float hullCreditMintedHpTotal;
    private float hullCreditEvictedHpTotal;
    private float hullCreditClearedHpTotal;
    private int hullCreditLateAfterExpiryTotal;
    private float hullCreditLateAfterExpiryHpTotal;
    private long hullCreditLateMaxAgeLabels;
    /** this frame's push stamp (the snapshot label, the {@code DamageWitness.snapTick}
     *  domain — never the per-ledger {@code lastSnapLabel}) and LOCAL horizon, cached by {@link #noteFrameInputs}
     *  so {@code recordApplied} never reads the plugin; then the D1/D2 lane totals ({@link #hzLaneTokens}),
     *  bumped LIVE at the ledger seams (the credit-total precedent above). */
    private long frameSnapLabel = LABEL_UNSET;
    private int frameLocalHorizon = HZ_UNKNOWN;
    private int hullHzRetiredTotal, hullHzLocalTotal, hullLateTwinTotal;
    private float hullHzLeftoverHpTotal, hullHzLocalHpTotal, hullLateTwinHpTotal, hullLateTwinLocalHpTotal,
            hullRegenShareHpTotal, hullRegenShareCappedHpTotal, hullReplayExpiredHpTotal, hullReplayClearedHpTotal,
            hullOpenAtCloseHpTotal, hullPushedHpTotal, hullFifoRetiredHpTotal;
    /** Set around ONE synchronous engine {@code applyDamage} of a server-dealt hit the
     *  local engine missed ({@code PredictReconcileCombatPlugin.applyWireAckedHit}): the ledger
     *  records the apply but pushes no replay entry. Render thread only; never left set. */
    private boolean wireAckedApply;
    private int hullWireAckedTotal;
    /** The open wire-acked window's hit SERVER tick ({@code -1} = unknown) — the order bound of its
     *  credit consume — and the lane totals of that consume's classes (exported on
     *  {@link #creditLaneTokens}). */
    private long wireAckedHitTick = -1L;
    private int hullWireAckedUntick;
    private int hullWireAckedStale;
    private float hullWireAckedStaleHp;
    private int hullWireAckedPrepaid;
    private float hullWireAckedPrepaidHp;
    private int hullWireAckedPrepaidExpired;
    /** The stash hp identity twins: prepaid_hp = netted + unmatched + expired_hp + cleared + live. */
    private float hullWireAckedNettedHp, hullWireAckedUnmatchedHp, hullWireAckedPrepaidExpiredHp, hullWireAckedStashClearedHp;
    /** Applies whose SOURCE was a server-killed ship body (the engine's own
     *  death explosion / a hulk's ram): server-dealt, already on the wire, recorded acked with NO replay
     *  entry. Classified by {@code PredictReconcileCombatPlugin.hullSourceClass} at the cap seam (renamed
     *  by a later fix; also counts door-det applies). */
    private int hullDeadSourceAckedTotal;
    /** What the OPEN window recorded, for {@link #undoWireAckedApply()}: the
     *  first ledger that recorded inside it + its exact {@code applied}/{@code unc} sums. A second
     *  body in the same window (a splash; the terminator apply is a point hit) is NOT stashed —
     *  its body is not restored either, so its record stands exactly as before. */
    private HullLedger wireAckedLedger;
    private float wireAckedRecApplied;
    private float wireAckedRecUnc;
    private int hullWireAckedRestoredTotal;
    /** The open window is an ACL armor REPLAY — its applies/restores
     *  count on the {@code _replay} twins, never the presenter's meters. */
    private boolean wireAckedReplay;
    private int hullWireAckedReplayTotal, hullWireAckedRestoredReplayTotal;
    /** max-hull mirror counters: writes landed (or would-land in meter mode) / log lines /
     *  own-ship skew snapshots (MEASURE ONLY — the own body is never written here). */
    private int maxHullMirrors;
    private int maxHullMirrorLogs;
    private int ownMaxHullSkewFrames;
    private int ownMaxHullSkewLogs;
    /** The per-round MAX SINGLE RECONCILE STEP (INFO only, never graded) — the
     *  largest move ONE apply-mode hull write in {@link #maintain} makes to ONE foe's displayed hull: step =
     *  {@code landed − eng}, {@code eng} = the engine hull maintain read before the write (it already carries every local
     *  apply since the previous write — the local hit shows at once, the reconcile never re-shows it), {@code landed} =
     *  what the write left ({@code mirrorHull}'s [1, max] clamp included). The bar is drawn once per frame, so the
     *  per-frame write IS the visible step: an amount-FIFO retire + a horizon retire (or two horizon retires) in one
     *  frame are ONE step, never two and never a per-round sum. The step is every cause the write carries: UP = an
     *  over-applied remainder leaving whole at the horizon, a credit-matched replica / wire-acked re-apply returned to
     *  wire, a server heal; DOWN = a wire drop past what is outstanding (an under-apply, a server-only hit); the RESUME
     *  write from wire either way. Not a step: the FIRST write of a fresh ledger ({@link HullLedger#stepArmed}: the
     *  replica adopting wire at a (re-)mint, never a reconcile move) and a frame with no write (meter / off mode,
     *  suspended). hp and frac ({@code step ÷} that foe's max hull at the write) are SEPARATE maxima per direction, each
     *  with the snapshot tick of its frame ({@code -1} = none this round; ticks are server sim ticks ≥ 0). Float compares
     *  + one divide on values maintain already holds — no engine read, no allocation, no log row. Printed by
     *  {@link #maxStepTokens}; reset by {@link #resetForMatchReset} (round wrap AND server-life reset — both run PRCP
     *  {@code finishMatchReset}) after its totals row prints the round's final values; a rejoin = a fresh plugin = a
     *  fresh lane. DELETE it (with the bar's fh_max_step rows) once the evaluation it serves is finished.
     * */
    private float stepUpMaxHp, stepUpMaxFrac, stepDownMaxHp, stepDownMaxFrac;
    private long stepUpMaxHpTick = -1L, stepUpMaxFracTick = -1L, stepDownMaxHpTick = -1L, stepDownMaxFracTick = -1L;

    /** The wire-acked apply window (see {@link #wireAckedApply}); always paired with
     *  {@link #endWireAckedApply()} in a {@code finally}. No-arg = an unknown hit tick. */
    void beginWireAckedApply() {
        beginWireAckedApply(-1L);
    }

    /** The window with the hit's SERVER tick (the end event's): the credit consume inside it is
     *  ORDER-BOUND by it ({@code HullLedger.consumeWireAcked}); {@code -1} = unknown (any live
     *  credit, counted {@code wire_acked_untick}). */
    void beginWireAckedApply(long hitTick) {
        wireAckedApply = true;
        wireAckedHitTick = hitTick;
        wireAckedLedger = null;
        wireAckedRecApplied = 0f;
        wireAckedRecUnc = 0f;
    }

    /** The replay-window overload + the replica caps' read seams. */
    void beginWireAckedApply(long hitTick, boolean replay) {
        beginWireAckedApply(hitTick);
        wireAckedReplay = replay;
    }

    boolean wireAckedOpen() { return wireAckedApply; }

    ArmorCellLane armorCells() { return armorCellLane; } // the FOE instance (the replica caps' replay seam)

    void endWireAckedApply() {
        wireAckedApply = false;
        wireAckedReplay = false;
        wireAckedHitTick = -1L;
        wireAckedLedger = null;
    }

    /** The caller RESTORED the engine hull the open window's apply took (the
     *  hit is already in the wire; the subtraction was presentation only), so reverse exactly what
     *  the window recorded: both raw scalars and the frame's attribution. The next
     *  {@code absorbFrame} then sees a zero engine delta with zero attribution and folds NOTHING —
     *  a bare restore would fold the whole hit as an unexplained UP into {@code absorbedUp}, the
     *  CLOSE row's heal witness. {@code wireAcked} keeps its count (the apply happened). Call
     *  inside the window, after the restore; returns the hull amount reversed (0 = nothing was
     *  recorded — no ledger yet, a closed ledger, or a shield hit). */
    float undoWireAckedApply() {
        HullLedger l = wireAckedLedger;
        if (l == null || l.closed) {
            return 0f;
        }
        float undone = wireAckedRecApplied;
        l.rawHull += wireAckedRecApplied;
        l.rawHullUnc += wireAckedRecUnc;
        l.attributedThisFrame -= wireAckedRecApplied;
        l.wireAckedRestored++;
        if (wireAckedReplay) hullWireAckedRestoredReplayTotal++; else hullWireAckedRestoredTotal++;
        wireAckedLedger = null;
        wireAckedRecApplied = 0f;
        wireAckedRecUnc = 0f;
        return undone;
    }

    FoeHullReconcileLane(PredictReconcileCombatPlugin plugin, boolean meter, boolean apply,
            float floorFrac) {
        this.plugin = plugin;
        this.meter = meter;
        this.apply = apply;
        this.floorFrac = floorFrac;
        armorLane.bindCellLane(armorCellLane); // v11: cellLive disengages the whole-grid fold
    }

    /** The v11 cell-run delivery relay (NET/reader thread at PRCP's AWSD fold site —
     *  the cell lane's inbox is the cross-thread seam). */
    void onCellRecord(long entityId, long serverSimTick, java.util.List<ArmorCellLane.Run> runs) {
        armorCellLane.onRecord(entityId, serverSimTick, runs);
    }

    /** read-only shadow seam for DamageWitness's per-hit cells-vs-shadow
     *  terms (render thread; the lane's own NaN law rides through). */
    float cellShadow(long entityId, int ordinal) {
        return armorCellLane.shadowCell(entityId, ordinal);
    }

    float cellShadowSum(long entityId, int[] ordinals) {
        return armorCellLane.shadowSum(entityId, ordinals);
    }

    long cellShadowSectionTick(long entityId) {
        return armorCellLane.shadowSectionTick(entityId);
    }

    /** Caches this frame's push stamp + LOCAL horizon ({@link #beginFrame} feeds it
     *  from the plugin; the null-plugin contract calls it directly). */
    void noteFrameInputs(long snapLabel, double pingMillis) {
        frameSnapLabel = snapLabel;
        frameLocalHorizon = localHorizon(pingMillis, ServerTickRate.ticksPerSecond());
    }

    /** The D2 LOCAL horizon in labels:
     *  {@code clamp(ceil(pingMillis·tps/1000) + HZ_PAD, HZ_MIN, ceil(HULL_REPLAY_EXPIRY_SEC·tps))} — the
     *  finite-ping cap IS the 2-s backstop in labels. Why:
     *  at a fixed 30 a seat past ≈ 420 ms RTT saw every own hit leave whole before its
     *  twin landed = a per-hit bar rebound the 2-s law never had; under the cap no entry waits longer than the old
     *  law did and the twin window follows RTT. {@code pingMillis} = the snap-echo RTT (a full round trip, 1× in
     *  the label domain). A NaN / infinite / negative ping ⇒ {@link #HZ_UNKNOWN} 30 (an unknown RTT
     *  takes the short window; the NaN shape of {@code DischargeReconcileLaw.adjudicationGraceTicks}, whose 2×RTT
     *  is wall-clock); tps ≤ 0 ⇒ 60 (the {@code ArmorCellLane.armRemClock} idiom).
     * */
    static int localHorizon(double pingMillis, int tps) {
        if (!Double.isFinite(pingMillis) || pingMillis < 0d) {
            return HZ_UNKNOWN;
        }
        int t = tps > 0 ? tps : 60;
        double h = Math.ceil(pingMillis * t / 1000d) + HZ_PAD;
        int cap = (int) Math.ceil(HULL_REPLAY_EXPIRY_SEC * t); // the 2-s backstop in the label domain: 120 @ 60 tps
        return (int) Math.max(HZ_MIN, Math.min(cap, h));
    }

    /**
     * Hull-reconcile clock + stale-feed suspension:
     * replay expiry runs on LOCAL SIM TIME ({@code simDt} = the PRCP {@code advance(amount)} delta,
     * where paused/NaN/negative deltas leave the clock alone), and a stalled feed
     * suspends the write + deque admission (= pre-lane behavior exactly) so outstanding damage can
     * never integrate against a frozen wire baseline (the 1-hp-sliver-below-floor hole); resume
     * clears every deque and takes one authoritative step from wire. Called once per render frame,
     * before the per-entity lane blocks run.
     */
    void beginFrame(boolean freshSnapshot, float simDt) {
        // The per-frame push stamp + H (PRCP sets lastSnapshotSimTick first).
        if (plugin != null) {
            noteFrameInputs(plugin.lastSnapshotSimTick,
                    plugin.session != null ? plugin.session.netPingMillis() : Double.NaN);
        }
        boolean simAdvanced = simDt > 0f && simDt == simDt;
        if (simAdvanced) {
            hullLocalSimSec += simDt;
        }
        if (freshSnapshot) {
            if (hullReconcileSuspended) {
                hullReconcileSuspended = false;
                clearAllHullReplay();
                PilotWatchLog.info("predict v3 foe-hull-reconcile RESUME after " + staleTicks()
                        + " stale sim ticks: replay deques cleared, reconciling from wire");
            }
            hullStaleSimSec = 0d;
        } else {
            // C22: the latch counts SIM TICKS since the last fresh snapshot, never
            // render frames — a paused sim (simDt 0/NaN) leaves the stale clock alone exactly as it
            // leaves the replay-expiry clock alone.
            if (simAdvanced) {
                hullStaleSimSec += simDt;
            }
            if (!hullReconcileSuspended
                    && FoeHullStaleLatchLaw.suspendDue(hullStaleSimSec, ServerTickRate.ticksPerSecond())) {
                hullReconcileSuspended = true;
                hullSuspendEvents++;
                PilotWatchLog.info("predict v3 foe-hull-reconcile SUSPEND at " + staleTicks()
                        + " stale sim ticks: write+admission off (local integration + caps = today)");
            }
        }
    }

    /** C22: the stale clock in whole sim ticks at the negotiated rate (log rows + the latch test). */
    private int staleTicks() {
        return FoeHullStaleLatchLaw.staleTicks(hullStaleSimSec, ServerTickRate.ticksPerSecond());
    }

    /**
     * The per-replica hull LEDGER + baseline+replay reconcile (the ratified
     * design; knob {@code cc.predict.foeHullReconcile}). Runs once per frame per replica, LAST in
     * the per-entity lane block. The ledger half runs in EVERY mode (off included
     * — one read path for the raw instruments); the write half (apply, un-suspended) sets
     * engineHull := wire − Σ outstanding un-acked own hits via the proven
     * {@code PredictReconcileCombatPlugin.mirrorHull} clamp, lower-bounded at
     * {@code max(min(floor, wire), wire − outstanding)} — prediction-driven darkness stops at the
     * axis-B floor, truth-driven below-floor still passes. Axis C untouched ({@code killFoe}/
     * {@code killRemote} still own death). {@code re == null} = the 1v1 foe (lane-field ledger
     * twin); registry entities carry theirs on the entry. Fail-soft.
     */
    void maintain(ShipAPI ship, ServerEntityPose pose, boolean freshSnapshot, long simTick,
            PredictReconcileCombatPlugin.RemoteEntity re) {
        if (ship == null || pose == null || !ship.isAlive()) {
            return;
        }
        try {
            // Armor relay — before the ledger seed on purpose: armor is
            // ledger-independent and must not sit behind the ledger.closed post-kill return.
            armorLane.maintain(ship, pose, freshSnapshot);
            // v11 per-cell relay (own fail-soft inside; simNow = this lane's sim clock).
            armorCellLane.maintain(ship, pose, hullLocalSimSec, simTick); // + the snapshot tick (the stamp clock)
            // The max-hull mirror BEFORE the ledger seed / hull write — the denominator every
            // hull number below (floor, clamp, bars) divides by must be the server's first.
            if (freshSnapshot) {
                mirrorMaxHull(ship, pose, re, simTick);
            }
            HullLedger ledger = re != null ? re.ledger : foeHullLedger;
            if (ledger == null) {
                ledger = new HullLedger(pose.hitpoints(), ship.getHitpoints());
                if (re != null) {
                    re.ledger = ledger;
                } else {
                    foeHullLedger = ledger;
                }
            }
            if (ledger.closed) {
                return; // post-kill frames (respawn re-mints a fresh ledger)
            }
            float eng = ship.getHitpoints();
            ledger.absorbFrame(eng);
            if (freshSnapshot) {
                // The 3-arg onWire + the u8 quantum — simTick = this
                // snapshot's label (the credit clock); the quantum from the wire max hull (meter/off modes never
                // run the max-hull mirror), else the local max.
                ledger.onWire(pose.hitpoints(), simTick,
                        (pose.hasMaxHp() && pose.maxHp() > 0f ? pose.maxHp() : ship.getMaxHitpoints()) / 255f);
            }
            ledger.expire(hullLocalSimSec);
            if (meter && freshSnapshot) {
                float wire = pose.hitpoints();
                float target = hullReconcileTarget(ship, wire, ledger.outstanding);
                ledger.noteTrack(Math.abs(eng - wire), Math.abs(eng - target));
            }
            // The max hull is read ONCE here (was inside hullReconcileTarget) and
            // also denominates the max-step meter's frac; NaN = no write this frame, so nothing is metered.
            float writeMaxHp = Float.NaN;
            if (apply && !hullReconcileSuspended) {
                float wire = pose.hitpoints();
                writeMaxHp = ship.getMaxHitpoints();
                plugin.mirrorHull(ship, hullReconcileTarget(writeMaxHp, wire, ledger.outstanding));
            }
            // Post-write in apply (the value actually landed, post-clamp), last-read otherwise —
            // either way the next frame's absorb diffs against the value the engine left this
            // maintain with, so the write itself is never absorbed as an unreported move.
            ledger.refEngine = ship.getHitpoints();
            if (writeMaxHp == writeMaxHp) {
                noteReconcileStep(ledger, ledger.refEngine - eng, writeMaxHp, simTick);
            }
            // The seat-remote hull walk: one per-cadence row of every
            // presentation-relevant hull scalar for side-0 seat replicas, POST-write (eng = what
            // the renderer draws THIS frame). gapEW = |presented − wire| (the vanilla-bar parity;
            // rests 0 when the reconcile trues), gapRW = raw − wire SIGNED (the debug bar's
            // green-vs-orange resting gap, positive = client bar higher = the reported resting
            // gap). simTick is the F9/screenshot pairing key.
            if (meter && freshSnapshot && re != null && re.side == 0
                    && ledger.seatWalkSamples < SEAT_HULL_WALK_LINE_CAP
                    && ledger.seatWalkFresh++ % SEAT_HULL_WALK_EVERY == 0) {
                ledger.seatWalkSamples++;
                float eng2 = ledger.refEngine;
                float wire2 = pose.hitpoints();
                PilotWatchLog.info("predict seat-hull-walk [entity " + re.entityId + "]:"
                        + " tick=" + simTick
                        + " eng=" + fmt1(eng2) + " wire=" + fmt1(wire2)
                        + " raw=" + fmt1(ledger.rawHull) + " unc=" + fmt1(ledger.rawHullUnc)
                        + " outstanding=" + fmt1(ledger.outstanding)
                        + " gapEW=" + fmt1(Math.abs(eng2 - wire2))
                        + " gapRW=" + fmt1(ledger.rawHull - wire2)
                        + (ledger.seatWalkSamples == SEAT_HULL_WALK_LINE_CAP
                                ? " [line cap reached]" : ""));
            }
        } catch (Throwable ignore) {
            // presentation-side lane; never break the watch
        }
    }

    /**
     * The MAX-hull mirror (a live audit: the {@code attack_swarm_Construction} carrier
     * CLOSE row {@code rawHull=7000 wire=6698 refEng=2000}). The server engine's
     * max hull is not a variant constant: vanilla scripts grow it in place ({@code
     * ConstructionSwarmSystemScript.launchSwarm} multiplies the fighter's max hitpoints, hpMult
     * 2 / 3.5 / 4 for a destroyer / cruiser / capital build; {@code ThreatShipReclamationScript}
     * likewise) and server-side stat mods can skew it, while the replica is minted from the variant
     * and keeps the spec's. Every AWSD keyframe already carries the server's max hull ({@code maxHp}
     * i32 — once dropped at {@code resolvePose}), so the fix is the engine's own
     * setter with the wire's number: zero wire bytes, vanilla's own verb. Without it {@code mirrorHull}
     * clamps the wire hull to the LOCAL max (a 6698-hp carrier showed 2000/2000 = FULL until the
     * server's hull fell under 2000), the axis-B floor ({@code floorFrac × getMaxHitpoints}) and the
     * seat/debug bars ({@code SeatNameHullBarLane}, its fraction offset) all divided by the wrong
     * denominator. Fresh snapshots only; {@link #MAX_HULL_MIRROR_EPS_HP} / {@code _FRAC} dead band; a
     * shrink also clamps the current hull to the new max (vanilla pairs the two setters).
     * Write half gated on {@code apply} like the hull write (off / meter count + log only). Caller
     * wraps in the lane's fail-soft try.
     */
    private void mirrorMaxHull(ShipAPI ship, ServerEntityPose pose,
            PredictReconcileCombatPlugin.RemoteEntity re, long simTick) {
        if (!pose.hasMaxHp()) {
            return;
        }
        float wireMax = pose.maxHp();
        float localMax = ship.getMaxHitpoints();
        if (!(wireMax > 0f) || !(localMax > 0f)) {
            return;
        }
        if (Math.abs(wireMax - localMax) <= Math.max(MAX_HULL_MIRROR_EPS_HP, localMax * MAX_HULL_MIRROR_EPS_FRAC)) {
            return;
        }
        if (apply) {
            ship.setMaxHitpoints(wireMax);
            if (ship.getHitpoints() > wireMax) {
                ship.setHitpoints(wireMax);
            }
        }
        maxHullMirrors++;
        if (maxHullMirrorLogs < MAX_HULL_MIRROR_LOG_MAX) {
            maxHullMirrorLogs++;
            PilotWatchLog.info("predict maxhull mirror [entity " + (re != null ? String.valueOf(re.entityId) : "foe")
                    + "]: local=" + fmt1(localMax) + " -> wire=" + fmt1(wireMax)
                    + " hull=" + fmt1(ship.getHitpoints())
                    + " variant=" + variantIdOf(ship)
                    + " tick=" + simTick
                    + (apply ? "" : " (mode=" + PredictKnobConfig.FOE_HULL_RECONCILE_MODE + ", nothing written)")
                    + (maxHullMirrorLogs == MAX_HULL_MIRROR_LOG_MAX ? " [line cap reached]" : ""));
        }
    }

    /**
     * The OWN ship's max-hull skew, MEASURE ONLY (the own body is the local sim's truth and
     * untreated by construction; a skew here is a finding to surface, never a silent write —
     * {@code SeatNameHullBarLane} noted the class without a measurement). Counts fresh snapshots
     * while skewed; first {@link #OWN_MAX_HULL_SKEW_LOG_MAX} get a line. Fail-soft.
     */
    void noteOwnMaxHullSkew(ShipAPI own, ServerEntityPose pose, boolean freshSnapshot, long simTick) {
        if (!freshSnapshot || own == null || pose == null || !pose.hasMaxHp()) {
            return;
        }
        try {
            float wireMax = pose.maxHp();
            float localMax = own.getMaxHitpoints();
            if (!(wireMax > 0f) || !(localMax > 0f)) {
                return;
            }
            if (Math.abs(wireMax - localMax) <= Math.max(MAX_HULL_MIRROR_EPS_HP, localMax * MAX_HULL_MIRROR_EPS_FRAC)) {
                return;
            }
            ownMaxHullSkewFrames++;
            if (ownMaxHullSkewLogs < OWN_MAX_HULL_SKEW_LOG_MAX) {
                ownMaxHullSkewLogs++;
                PilotWatchLog.info("predict own maxhull SKEW (measure only, never written):"
                        + " local=" + fmt1(localMax) + " wire=" + fmt1(wireMax)
                        + " variant=" + variantIdOf(own) + " tick=" + simTick);
            }
        } catch (Throwable ignore) {
            // measurement only; never break the own reconcile
        }
    }

    private static String variantIdOf(ShipAPI ship) {
        try {
            return ship.getVariant() != null ? ship.getVariant().getHullVariantId() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** The write target: {@code max(min(floor, wire), wire − outstanding)} (floor = the
     *  axis-B {@code FOE_HULL_FLOOR_FRAC} math; {@code mirrorHull} adds the [1, max] clamp). */
    private float hullReconcileTarget(ShipAPI ship, float wireHp, float outstanding) {
        return hullReconcileTarget(ship.getMaxHitpoints(), wireHp, outstanding);
    }

    /** The same target over a max hull the caller already read (the apply write: one read feeds the target and the
     *  max-step meter's frac). */
    private float hullReconcileTarget(float maxHp, float wireHp, float outstanding) {
        float floor = Math.max(1f, floorFrac * maxHp);
        return Math.max(Math.min(floor, wireHp), wireHp - outstanding);
    }

    /** The walker's ledger lookup: the 1v1 twin when the ship anchors it, else the registry entry's. */
    HullLedger hullLedgerFor(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        if (foeHullLedger != null && ship == plugin.foeShip) {
            return foeHullLedger;
        }
        for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
            if (re.ship == ship) {
                return re.ledger;
            }
        }
        return null;
    }

    /** One resolved walk subject — id/ship for the walk line, ledger scalars +
     *  wire hp for the hull triplet ({@code NaN} = that half unavailable, walker omits the field). */
    static final class WalkSubject {
        final long id;
        final ShipAPI ship;
        final float rawHull;
        final float rawHullUnc;
        final float wireHp;

        WalkSubject(long id, ShipAPI ship, HullLedger ledger, ServerEntityPose pose) {
            this.id = id;
            this.ship = ship;
            this.rawHull = ledger != null ? ledger.rawHull : Float.NaN;
            this.rawHullUnc = ledger != null ? ledger.rawHullUnc : Float.NaN;
            this.wireHp = pose != null ? pose.hitpoints() : Float.NaN;
        }
    }

    /**
     * The parity walker's foe SUBJECT, re-bound across deaths (an old
     * instrument debt: the anchor-only subject covered 18.6% of one run, death-weighted by
     * construction). While the anchor foe LIVES it stays the subject (exact pre-(43)(c) shape, 1v1
     * twin included) — except its pose, which is re-looked-up via the registry when the roster gave
     * it none: on the fortress shape every roster row is side 0, {@code primaryFoeId} never latches,
     * and the anchor pose alone left the walker vehicle-less whole-run (1287 close-out (4), the
     * "extend-walker-to-wave-foes" option — minted here because on the canonical BASTION shape a
     * roster-key-only re-bind would be dead code). Anchor dead or unbound: lowest-id live,
     * non-fighter (the anchor-capture rule), open-ledgered side-1 registry foe with a
     * pose in THIS snapshot. Nothing eligible: the dead anchor as today (1v1 post-kill emission
     * unchanged), or null when no anchor exists (walker skips the foe line). Stateless — resolved
     * fresh each walk tick, so round resets and respawns need no wiring here.
     */
    WalkSubject walkSubjectFor(ShipAPI anchorFoe, ServerEntityPose anchorPose,
            ServerSnapshotFrame snapshot) {
        if (anchorFoe != null && anchorFoe.isAlive()) {
            ServerEntityPose pose = anchorPose;
            long id = pose != null ? pose.entityId() : -1L;
            if (pose == null && snapshot != null) {
                for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
                    if (re.ship == anchorFoe) {
                        pose = snapshot.entityById(re.entityId);
                        if (pose != null) {
                            id = re.entityId;
                        }
                        break;
                    }
                }
            }
            return new WalkSubject(id, anchorFoe, hullLedgerFor(anchorFoe), pose);
        }
        if (snapshot != null) {
            for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
                if (re.side != 1 || re.ship == null || !re.ship.isAlive() || re.ship.isFighter()
                        || re.ledger == null || re.ledger.closed) {
                    continue;
                }
                ServerEntityPose pose = snapshot.entityById(re.entityId);
                if (pose != null) {
                    // remoteEntities is a TreeMap: first eligible hit = the lowest live id
                    return new WalkSubject(re.entityId, re.ship, re.ledger, pose);
                }
            }
        }
        if (anchorFoe == null) {
            return null;
        }
        return new WalkSubject(anchorPose != null ? anchorPose.entityId() : -1L, anchorFoe,
                hullLedgerFor(anchorFoe), anchorPose);
    }

    /** Stale-feed RESUME support: drop every ledger's un-acked replay entries (on resume the
     *  write takes one authoritative step from wire instead of replaying a stall's backlog). */
    private void clearAllHullReplay() {
        if (foeHullLedger != null) {
            foeHullLedger.clearReplay();
        }
        for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
            if (re.ledger != null) {
                re.ledger.clearReplay();
            }
        }
    }

    /** Hull ledgers are per-life: the cumulative totals line, then reseed via
     *  the null-out (the lazy maintain re-mints from WIRE next round; NEVER from the reconciled
     *  scalar). Cumulative totals + the local sim clock persist across rounds. */
    void resetForMatchReset() {
        PilotWatchLog.info("predict foe-hull-ledger match-reset totals: closes=" + hullLedgerCloses
                + " postClose=" + hullPostCloseTotal + " replayExpired=" + hullReplayExpiredTotal
                + " ledgerGap=" + hullLedgerGapTotal + " suspends=" + hullSuspendEvents
                + maxStepTokens());
        armorLane.resetForMatchReset();
        armorCellLane.resetForMatchReset();
        foeHullLedger = null;
        hullReconcileSuspended = false;
        hullStaleSimSec = 0d;
        // The max-step meter is per ROUND (unlike the cumulative totals) — the
        // totals row above carries the ended round's final values, then it restarts.
        stepUpMaxHp = stepUpMaxFrac = stepDownMaxHp = stepDownMaxFrac = 0f;
        stepUpMaxHpTick = stepUpMaxFracTick = stepDownMaxHpTick = stepDownMaxFracTick = -1L;
    }

    /**
     * The foe hull ledger/reconcile counters, one greppable line per meter flush
     * (the counter-line discipline). Silent when {@code cc.predict.foeHullReconcile=off}
     * (the ledger itself still runs in off — it feeds the debug bars + walk lines in every mode).
     */
    void logFoeHullReconcileMeter(String where) {
        // Armor flushes on its OWN knob — before the hull !meter early-return on purpose.
        armorLane.logMeter(where);
        armorCellLane.logMeter(where);
        if (!meter) {
            return;
        }
        HullLedger foe = foeHullLedger;
        PilotWatchLog.info("predict v3 foe-hull-meter [" + where + "]:"
                + " mode=" + PredictKnobConfig.FOE_HULL_RECONCILE_MODE
                + " suspended=" + hullReconcileSuspended
                + " predict_foe_hull_suspends=" + hullSuspendEvents
                + " predict_foe_hull_closes=" + hullLedgerCloses
                + " predict_foe_hull_post_close=" + hullPostCloseTotal
                + " predict_foe_hull_replay_expired=" + hullReplayExpiredTotal
                + " predict_foe_hull_ledger_gap=" + hullLedgerGapTotal
                + " predict_foe_hull_wire_acked=" + hullWireAckedTotal
                + " predict_foe_hull_wire_acked_restored=" + hullWireAckedRestoredTotal
                + " predict_foe_hull_dead_src_acked=" + hullDeadSourceAckedTotal
                + " predict_maxhull_mirrors=" + maxHullMirrors
                + " predict_own_maxhull_skew_frames=" + ownMaxHullSkewFrames
                + " predict_foe_hull_track_err_max=" + fmt1(hullMaxTrackErr)
                + " predict_foe_hull_gap_max=" + fmt1(hullMaxHullGap)
                + creditLaneTokens()
                + hzLaneTokens()
                + maxStepTokens()
                + (foe != null ? " | foe rawHull=" + fmt1(foe.rawHull)
                        + " rawHullUnc=" + fmt1(foe.rawHullUnc)
                        + " wire=" + fmt1(foe.prevWire)
                        + " outstanding=" + fmt1(foe.outstanding)
                        + " entries=" + foe.entries.size() : ""));
    }

    /** The Shape A credit lane totals of the meter line (each token space-led), one builder so the
     *  contract's summary-identity row reads exactly what the line prints.
     * */
    String creditLaneTokens() {
        return " predict_foe_hull_credit_minted=" + hullCreditMintedTotal
                + " predict_foe_hull_credited=" + hullCreditedTotal
                + " predict_foe_hull_credited_hp=" + fmt1(hullCreditedHpTotal)
                + " predict_foe_hull_credit_expired=" + hullCreditExpiredTotal
                + " predict_foe_hull_credit_expired_hp=" + fmt1(hullCreditExpiredHpTotal)
                + " predict_foe_hull_credit_evicted=" + hullCreditEvictedTotal
                + " predict_foe_hull_credit_resume_skipped=" + hullCreditResumeSkippedTotal // post-RESUME drops the mint skipped
                + " predict_foe_hull_credit_resume_skipped_hp=" + fmt1(hullCreditResumeSkippedHpTotal)
                + " predict_foe_hull_credit_minted_hp=" + fmt1(hullCreditMintedHpTotal)
                + " predict_foe_hull_credit_evicted_hp=" + fmt1(hullCreditEvictedHpTotal)
                + " predict_foe_hull_credit_cleared_hp=" + fmt1(hullCreditClearedHpTotal)
                + " predict_foe_hull_credit_late_after_expiry=" + hullCreditLateAfterExpiryTotal
                + " predict_foe_hull_credit_late_after_expiry_hp=" + fmt1(hullCreditLateAfterExpiryHpTotal)
                + " predict_foe_hull_credit_late_max_age=" + hullCreditLateMaxAgeLabels
                // The wire-acked order-bound consume classes
                + " predict_foe_hull_wire_acked_untick=" + hullWireAckedUntick
                + " predict_foe_hull_wire_acked_stale=" + hullWireAckedStale
                + " predict_foe_hull_wire_acked_stale_hp=" + fmt1(hullWireAckedStaleHp)
                + " predict_foe_hull_wire_acked_prepaid=" + hullWireAckedPrepaid
                + " predict_foe_hull_wire_acked_prepaid_hp=" + fmt1(hullWireAckedPrepaidHp)
                + " predict_foe_hull_wire_acked_prepaid_expired=" + hullWireAckedPrepaidExpired
                + " predict_foe_hull_wire_acked_netted_hp=" + fmt1(hullWireAckedNettedHp)
                + " predict_foe_hull_wire_acked_unmatched_hp=" + fmt1(hullWireAckedUnmatchedHp)
                + " predict_foe_hull_wire_acked_prepaid_expired_hp=" + fmt1(hullWireAckedPrepaidExpiredHp)
                + " predict_foe_hull_wire_acked_stash_cleared_hp=" + fmt1(hullWireAckedStashClearedHp)
                + " predict_foe_hull_wire_acked_replay=" + hullWireAckedReplayTotal // the ACL armor-REPLAY twin
                + " predict_foe_hull_wire_acked_restored_replay=" + hullWireAckedRestoredReplayTotal;
    }

    /** The lane totals of the meter line (each token space-led; one builder so the contract's
     *  twin row reads exactly what prints) + the LOCAL horizon H this frame ran. The signed per-seat meter is
     *  DERIVED by the grader, never a token: plus = {@code hz_leftover_hp − late_twin_hp +
     *  replay_expired_hp + replay_cleared_hp + open_at_close_hp}; minus = {@code credit_expired_hp +
     *  credit_evicted_hp} ({@link #creditLaneTokens}). The hz-local
     *  count and regen-share hp totals are kept but unprinted (their tokens deleted unread):
     * {@link #hzUnprintedTotalsForTest}. {@code hz_local_hp} — the {@code SRC_LOCAL} share of
     *  {@code hz_leftover_hp}: hull an OWN-ship / non-ship / unresolved source dealt locally that the horizon
     *  retired with no server twin — is RE-PRINTED with its reader, the
     *  {@code fh_hz_retire} detail; delete trigger =
     *  the close of the follow-up that splits out the own-shot lead residual. */
    String hzLaneTokens() {
        return " predict_foe_hull_hz_retired=" + hullHzRetiredTotal
                + " predict_foe_hull_hz_leftover_hp=" + fmt1(hullHzLeftoverHpTotal)
                + " predict_foe_hull_hz_local_hp=" + fmt1(hullHzLocalHpTotal)
                + " predict_foe_hull_late_twin=" + hullLateTwinTotal
                + " predict_foe_hull_late_twin_hp=" + fmt1(hullLateTwinHpTotal)
                + " predict_foe_hull_late_twin_local_hp=" + fmt1(hullLateTwinLocalHpTotal)
                + " predict_foe_hull_regen_share_capped_hp=" + fmt1(hullRegenShareCappedHpTotal)
                + " predict_foe_hull_replay_expired_hp=" + fmt1(hullReplayExpiredHpTotal)
                + " predict_foe_hull_replay_cleared_hp=" + fmt1(hullReplayClearedHpTotal)
                + " predict_foe_hull_open_at_close_hp=" + fmt1(hullOpenAtCloseHpTotal)
                + " predict_foe_hull_pushed_hp=" + fmt1(hullPushedHpTotal)
                + " predict_foe_hull_fifo_retired_hp=" + fmt1(hullFifoRetiredHpTotal)
                + " predict_foe_hull_hz_local_horizon=" + frameLocalHorizon;
    }

    /** Contract x13's read of the two lane totals {@link #hzLaneTokens} does not print, in this order:
     *  {@code {hzLocal count, regenShareHp}} summed over every ledger (their round-line tokens were
     *  deleted as unread; hzLocalHp left this accessor when its token was re-printed).
     * */
    float[] hzUnprintedTotalsForTest() {
        return new float[] { hullHzLocalTotal, hullRegenShareHpTotal };
    }

    /** The max-step fold for ONE apply-mode write ({@link #stepUpMaxHp} = the definition): a fresh ledger's first write
     *  only arms it (the adoption snap); else the up / down hp and frac maxima take the step on a strict {@code >} (the
     *  first frame of a tie keeps its tick). {@code maxHp} = the max hull the write's target used (&gt; 0 whenever the
     *  write landed). */
    private void noteReconcileStep(HullLedger ledger, float step, float maxHp, long tick) {
        if (!ledger.stepArmed) {
            ledger.stepArmed = true;
            return;
        }
        float mag = Math.abs(step);
        float frac = maxHp > 0f ? mag / maxHp : 0f;
        if (step > 0f) {
            if (mag > stepUpMaxHp) {
                stepUpMaxHp = mag;
                stepUpMaxHpTick = tick;
            }
            if (frac > stepUpMaxFrac) {
                stepUpMaxFrac = frac;
                stepUpMaxFracTick = tick;
            }
        } else if (step < 0f) {
            if (mag > stepDownMaxHp) {
                stepDownMaxHp = mag;
                stepDownMaxHpTick = tick;
            }
            if (frac > stepDownMaxFrac) {
                stepDownMaxFrac = frac;
                stepDownMaxFracTick = tick;
            }
        }
    }

    /** The max-step tokens (each space-led; magnitudes ≥ 0 — the direction is in the name; frac 4 decimals, ROOT
     *  locale; tick {@code -1} = no step that direction this round), ONE builder printed by the meter line AND the
     *  {@link #resetForMatchReset} totals row: the meter flushes every 300 frames, so a step after a round's last
     *  flush reaches the bar only on that row. */
    String maxStepTokens() {
        return " predict_foe_hull_max_step_up_hp=" + fmt1(stepUpMaxHp)
                + " predict_foe_hull_max_step_up_hp_tick=" + stepUpMaxHpTick
                + " predict_foe_hull_max_step_up_frac=" + String.format(java.util.Locale.ROOT, "%.4f", stepUpMaxFrac)
                + " predict_foe_hull_max_step_up_frac_tick=" + stepUpMaxFracTick
                + " predict_foe_hull_max_step_down_hp=" + fmt1(stepDownMaxHp)
                + " predict_foe_hull_max_step_down_hp_tick=" + stepDownMaxHpTick
                + " predict_foe_hull_max_step_down_frac=" + String.format(java.util.Locale.ROOT, "%.4f", stepDownMaxFrac)
                + " predict_foe_hull_max_step_down_frac_tick=" + stepDownMaxFracTick;
    }

    /** One-decimal format, ROOT locale (grep-stable) — the lane's private twin of PRCP's fmt1
     *  (the FoeInterpLane private-formatter pattern). */
    private static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** One un-acked own-local hit awaiting wire acknowledgement (FIFO; hull is partial-consumed);
     *  stamped with the lane's local SIM clock. */
    private static final class HullReplayEntry {
        final double simSec;
        float hull;
        /** The push's snapshot label ({@link #LABEL_UNSET} until its first snapshot), its
         *  horizon in labels, and its {@code HullCreditRing.SRC_*} class. */
        long label;
        final int horizon;
        final int cls;

        HullReplayEntry(double simSec, float hull, long label, int horizon, int cls) {
            this.simSec = simSec;
            this.hull = hull;
            this.label = label;
            this.horizon = horizon;
            this.cls = cls;
        }
    }

    /** Verifier read ({@code CcHullLedgerContract}): the lane's local sim clock in seconds. */
    double localSimSec() {
        return hullLocalSimSec;
    }

    /**
     * The UNCONDITIONAL raw hull ledger, one per replica life (the raw-ledger
     * lane): {@code rawHull} = what engine hull would read with NO reconcile
     * (wire-seeded, minus every as-applied local hit, plus absorbed unattributed movers), the one
     * read path for debug bars / walk lines in every knob mode; {@code rawHullUnc} additionally
     * subtracts what the axis-B clamp ate (the client-would-have-killed meter, leg-1's ≥127% class
     * made visible). <b>Completeness by construction</b>: any engine-hull delta the attributed
     * lane (cap listeners) did not explain is absorbed per frame as an {@code unreported} entry —
     * covers the census silent movers (in-combat repair up; crit malfunction / {@code disable()}
     * down); the SUB-EPS per-frame residual accumulates across frames
     * ({@code unreportedAcc}) so the replica's LOCAL Combat Endurance regen (the server's own
     * listener law, re-minted on every seat replica) reaches the scalars at high fps
     * too: at knob=apply the top debug bar's raw−wire gap then reads the TRUE Σdealt−Σapplied
     * residual instead of the server's regen ({@code absorbedUp} = the CLOSE-line heal witness)
     * — so at knob≠apply {@code rawHull} ≡ engine hull exactly (modulo the ≈0 seed gap); an
     * unexplained DOWN while open is additionally a LOUD one-shot {@code ledger_gap} (silent
     * axis-A-loss guard, a proving-watch FAIL criterion). The replay deque holds attributed
     * own-local damage only (unreported/heal never enter): baseline+replay family (b) computes
     * engineHull := wire − Σ outstanding at the write site. CLOSED on the axis-C kill edge
     * (pre-blow, so the theater HE never enters); reseeded from WIRE only. Non-static: reads the
     * enclosing suspension flag + bumps the cumulative totals (the FoeDamageCap precedent).
     */
    final class HullLedger {
        /** Raw local-applied scalar — the debug-bar green source + walk-line {@code hullRaw}. */
        float rawHull;
        /** {@code rawHull} minus what the axis-B clamp ate — the client-would-have-killed scalar. */
        float rawHullUnc;
        /** Latest wire hull (the replay baseline; also walk-line {@code hullWire} at emit). */
        float prevWire;
        /** The reference engine hull the per-frame absorb diffs against: last value WRITTEN
         *  (apply) or last read (other modes) — set at the end of every maintain. */
        float refEngine;
        /** As-applied hull damage recorded since the last absorb (cap-listener lane). */
        float attributedThisFrame;
        /** The un-folded sub-EPS residual carried across frames (|acc| < EPS between folds). */
        float unreportedAcc;
        /** Σ UP folds this life — the heal witness (≈ the replica's local regen at apply). */
        float absorbedUp;
        /** Σ un-acked replay entries (the write's subtrahend). */
        float outstanding;
        boolean closed;
        private boolean ledgerGapLogged;
        final ArrayDeque<HullReplayEntry> entries = new ArrayDeque<HullReplayEntry>();
        int pushed;
        int retired;
        int expired;
        int unrepUp;
        int unrepDown;
        int ledgerGap;
        int postClose;
        int wireAcked;
        /** wire-acked applies whose hull subtraction was restored + un-recorded. */
        int wireAckedRestored;
        /** dead-source (server-killed body) applies recorded acked — {@link #recordApplied(float, float, boolean)}. */
        int deadSourceAcked;
        float maxTrackErr;
        float maxHullGap;
        /** seat-remote hull walk: fresh snapshots seen / rows emitted (per-life). */
        int seatWalkFresh;
        int seatWalkSamples;
        /** False until this ledger's first apply-mode write frame, which only sets it: that write is the replica ADOPTING
         *  wire at a (re-)mint (a respawn, a re-admission, a round's re-seed, a module re-point), never a reconcile step —
         *  the max-step meter ({@code noteReconcileStep}) skips it. Ctor-path only. */
        boolean stepArmed;
        /** Shape A unmatched-drop credits — {@link HullCreditRing}. */
        final HullCreditRing credits = new HullCreditRing();
        /** The label of the last fresh snapshot {@link #onWire} saw (the credit clock); 0 before the
         *  first one, when the ring is still empty so nothing can be consumed. */
        long lastSnapLabel;
        /** Armed by {@link #clearReplay} (the RESUME per-ledger step) — the
         *  first fresh snapshot after a stale-feed resume diffs against a PRE-STALL {@code prevWire},
         *  so its drop is the whole stall (reconciled from wire by the resume), never a credit; the
         *  hold is consumed ONCE, by that snapshot, and counted only when it carries a drop > EPS
         *  (a flat first snapshot spends it uncounted — contract row q "rise"). */
        boolean resumeMintHold;
        int creditResumeSkipped;
        float creditResumeSkippedHp;
        /** {@code absorbedUp} at the last {@link #onWire} (SNAPPED there) and the
         *  signed retire carry in [−quantum, 0]; the meters (per ledger; {@code hzCloseTokens}
         *  prints the conserved hp subset, lane twins = {@code hzLaneTokens}); the 4-slot ALL-class late-twin ring (hp left, retire label, horizon,
         *  class; free-first, else oldest). Ctor-path only: every re-mint starts clean.
         * */
        float absorbedUpAtWire;
        float retireCarry;
        int hzRetired, hzLocal, lateTwin;
        float hzLeftoverHp, hzLocalHp, lateTwinHp, lateTwinLocalHp, regenShareHp, regenShareCappedHp,
                replayExpiredHp, replayClearedHp, pushedHp, fifoRetiredHp;
        private final float[] twinHp = new float[TWIN_SLOTS];
        private final long[] twinLabel = new long[TWIN_SLOTS];
        private final int[] twinHorizon = new int[TWIN_SLOTS];
        private final int[] twinCls = new int[TWIN_SLOTS];

        HullLedger(float wireHp, float engineHp) {
            rawHull = wireHp;
            rawHullUnc = wireHp;
            prevWire = wireHp;
            // Seed refEngine from ENGINE, not wire: a hit landing between bind
            // and the first maintain must not fire a false ledger_gap; the exactness claim holds
            // modulo the ≈0 seed gap.
            refEngine = engineHp;
        }

        /** Cap-listener record: as-applied hull damage (pre-restoreFx) + the one-shot clamp record
         *  ({@code NaN} = unclamped). rawHull recording never pauses until close; deque admission
         *  is gated on the enclosing suspension. */
        void recordApplied(float appliedHull, float clampUpper) {
            recordApplied(appliedHull, clampUpper, HullCreditRing.SRC_LOCAL);
        }

        /** The boolean form (contract rows g-i): {@code serverDealt} = {@link HullCreditRing#SRC_SERVER_DEALT},
         *  else {@link HullCreditRing#SRC_LOCAL}. */
        void recordApplied(float appliedHull, float clampUpper, boolean serverDealt) {
            recordApplied(appliedHull, clampUpper,
                    serverDealt ? HullCreditRing.SRC_SERVER_DEALT : HullCreditRing.SRC_LOCAL);
        }

        /** {@code sourceClass} SERVER_DEALT = the hit is already on the wire: its report-seam
         *  source is a server-killed ship body inside the kill window,
         * or a prox-charge ENGINE-DOOR det the presenter armed
         *  ({@code DoorDetExpectation}). Classified by {@code PredictReconcileCombatPlugin.hullSourceClass}
         *  ({@code ServerDealtSourceLaw}); the branch below; the 2-arg form is the ordinary apply.
         *  Shape A: a REPLICA push first uses up live credits
         *  (a wire drop that reached {@link #onWire} before this apply) and only the remainder enters
         *  {@code entries}; a SERVER_DEALT apply uses them up too (its drop IS the credit); LOCAL never
         *  does (an own hit leads the wire). Nothing is used while suspended. */
        void recordApplied(float appliedHull, float clampUpper, int sourceClass) {
            float applied = appliedHull > 0f ? appliedHull : 0f;
            float unc = Float.isNaN(clampUpper) ? applied : Math.max(applied, clampUpper);
            if (applied <= 0f && unc <= 0f) {
                return; // shield hit / zero record — nothing hull-moving to ledger
            }
            if (closed) {
                postClose++;
                hullPostCloseTotal++;
                return; // the theater kill blow (expect exactly 1 per kill) never enters rawHull
            }
            rawHull -= applied;
            rawHullUnc -= unc;
            attributedThisFrame += applied;
            if (wireAckedApply) {
                // A SERVER-DEALT hit the
                // local engine missed, re-applied through the engine for its presentation — the
                // wire already carries it, so NO replay entry (the next write returns the body to
                // wire truth); rawHull still records the local apply (the raw instrument's law)
                // until the caller restores the engine hull and undoes it.
                wireAcked++;
                if (wireAckedReplay) hullWireAckedReplayTotal++; else hullWireAckedTotal++;
                if (wireAckedLedger == null || wireAckedLedger == this) {
                    wireAckedLedger = this;
                    wireAckedRecApplied += applied;
                    wireAckedRecUnc += unc;
                }
                if (!hullReconcileSuspended) {
                    consumeWireAcked(applied); // ORDER-BOUND by the hit's server tick
                }
                return;
            }
            if (sourceClass == HullCreditRing.SRC_SERVER_DEALT) {
                // This branch now also serves the
                // door-det class (a prox-charge ENGINE-DOOR det the server already dealt)
                // and the dead-source class is gated
                // to the 30 t kill window (posthumous projectiles are ordinary). Counter names kept.
                // The SOURCE is a server-killed ship body — the engine's own
                // death explosion (or a hulk's ram). Local death is server-decided (axis C), so this
                // hit is SERVER-DEALT and its drop rides the wire already — in the death snapshot
                // itself: the wire dropped BEFORE the local explosion applied, so a
                // pushed entry had nothing to FIFO-retire against and held the neighbour 1869.8 below
                // wire for the full HULL_REPLAY_EXPIRY_SEC = the reported "hull bar rebounded a second
                // or two later" (seat-hull-walk [entity 2] ticks 2268-2364 → 2396). Record the raw
                // apply + attribute the frame (no ledger_gap), push NO entry: the next write returns
                // the body to wire truth. The engine's own FX/number/armor stand (mult untouched).
                deadSourceAcked++;
                hullDeadSourceAckedTotal++;
                if (!hullReconcileSuspended) {
                    consumeCredit(applied); // the drop was the credit: result discarded (no entry either way)
                }
                return;
            }
            if (sourceClass == HullCreditRing.SRC_REPLICA && !hullReconcileSuspended) {
                applied = consumeCredit(applied); // only the uncredited remainder enters (0 = nothing)
            }
            if (applied > 0f && !hullReconcileSuspended) {
                // Stamped with the lane's per-frame label; LOCAL waits
                // the RTT horizon, a REPLICA remainder the credit window mirrored (CREDIT_TICKS).
                entries.addLast(new HullReplayEntry(hullLocalSimSec, applied, frameSnapLabel,
                        sourceClass == HullCreditRing.SRC_LOCAL ? frameLocalHorizon : HullCreditRing.CREDIT_TICKS,
                        sourceClass));
                outstanding += applied;
                pushed++;
                pushedHp += applied;
                hullPushedHpTotal += applied;
            }
        }

        /** Per-frame completeness absorb: fold the engine-hull delta the attributed lane did not
         *  explain into both raw scalars (UP silent = repair class; DOWN also LOUD ledger_gap).
         *  The residual ACCUMULATES across frames and folds when |acc| crosses
         *  EPS — a big single-frame move folds at once exactly as before; a slow sub-EPS mover
         *  (the local elite-regen heal at 540 fps) folds every few frames instead of never; a
         *  slow accumulated DOWN is still the LOUD one-shot ledger_gap. */
        void absorbFrame(float eng) {
            float unreported = eng - refEngine + attributedThisFrame;
            attributedThisFrame = 0f;
            unreportedAcc += unreported;
            if (unreportedAcc > HULL_LEDGER_EPS) {
                unrepUp++;
                absorbedUp += unreportedAcc;
                rawHull += unreportedAcc;
                rawHullUnc += unreportedAcc;
                unreportedAcc = 0f;
            } else if (unreportedAcc < -HULL_LEDGER_EPS) {
                unrepDown++;
                ledgerGap++;
                hullLedgerGapTotal++;
                rawHull += unreportedAcc;
                rawHullUnc += unreportedAcc;
                if (!ledgerGapLogged) {
                    ledgerGapLogged = true;
                    PilotWatchLog.error("predict foe-hull-ledger GAP: unexplained DOWN engine-hull"
                            + " move " + fmt1(unreportedAcc) + " hp (attributed lane missed a"
                            + " hull-moving path; absorbed — rawHull stays exact; proving-watch"
                            + " FAIL criterion)", null);
                }
                unreportedAcc = 0f;
            }
        }

        /** Fresh-snapshot retire: FIFO amount-consumption up to the wire drop (over-retire errs
         *  toward truth — multi-attacker inflation degrades to pure baseline ≤RTT). Heal/flat
         *  retires nothing (entries expire at W instead). Shape A:
         * {@code snapLabel} = the snapshot's sim-tick label; dead credits expire on it first,
         *  and a drop the FIFO loop could not retire (&gt; EPS, un-suspended) mints a credit a later
         *  replica push uses up — the wire-first inversion no longer strands that push as outstanding.
         *  This 2-arg form = D1 inert ({@code quantum} 0): the pre-D1 contract rows keep their meaning. */
        void onWire(float wireHp, long snapLabel) {
            onWire(wireHp, snapLabel, 0f);
        }

        /** {@code quantum} = the wire hull's u8 step (max hull /
         *  255; ≤ 0 or NaN = D1 off). Order: D1 snap (before the RESUME-hold return, F4(a)) → D2 horizon sweep
         *  (before the FIFO, F4(b)) → D1 {@code avail} = net + regen share + carry into the FIFO → the stash and
         *  the mint see only the NET drop's unmatched part (h3) minus the late-twin pairing (F5) → the hold +
         *  mint. */
        void onWire(float wireHp, long snapLabel, float quantum) {
            lastSnapLabel = snapLabel;
            int exp0 = credits.expired;
            float expHp0 = credits.expiredHp;
            credits.expire(snapLabel);
            hullCreditExpiredTotal += credits.expired - exp0;
            hullCreditExpiredHpTotal += credits.expiredHp - expHp0;
            float regenRaw = absorbedUp - absorbedUpAtWire;
            absorbedUpAtWire = absorbedUp; // F2(a): SNAP — a non-regen UP spends at most one quantum, once
            boolean d1 = quantum > 0f;
            float regenShare = 0f;
            if (d1) {
                regenShare = Math.max(0f, Math.min(regenRaw, quantum));
                if (regenRaw > quantum) {
                    regenShareCappedHp += regenRaw - quantum;
                    hullRegenShareCappedHpTotal += regenRaw - quantum;
                }
            }
            float net = prevWire - wireHp;
            prevWire = wireHp;
            sweepHorizon(snapLabel);
            float drop = net + regenShare + retireCarry; // D1 avail (= net when D1 is off: the carry stays 0)
            float out0 = outstanding;
            if (d1 && drop <= 0f) {
                retireCarry = Math.max(drop, -quantum); // a rise is debt the next regen shares repay first
                drop = 0f;
            } else {
                retireCarry = 0f;
            }
            while (drop > 0f && !entries.isEmpty()) {
                HullReplayEntry head = entries.peekFirst();
                float consumed = Math.min(head.hull, drop);
                head.hull -= consumed;
                outstanding -= consumed;
                drop -= consumed;
                if (head.hull <= HULL_LEDGER_EPS) {
                    outstanding -= head.hull; // retire the sliver with the entry
                    entries.pollFirst();
                    retired++;
                }
            }
            fifoRetiredHp += out0 - outstanding;
            hullFifoRetiredHpTotal += out0 - outstanding;
            regenShareHp += regenShare;
            hullRegenShareHpTotal += regenShare;
            if (outstanding < 0f) {
                outstanding = 0f;
            }
            drop = Math.min(drop, Math.max(0f, net)); // h3: a regen-phase leftover mints nothing
            // An apply-first wire-acked hit's own drop
            // is netted out BEFORE the mint (never an orphan credit); a too-late stash is counted.
            int pxp0 = credits.prepaidExpired;
            float net0 = credits.nettedHp, unm0 = credits.unmatchedHp, pxh0 = credits.prepaidExpiredHp;
            drop = credits.netPrepaid(drop, snapLabel);
            hullWireAckedPrepaidExpired += credits.prepaidExpired - pxp0;
            hullWireAckedNettedHp += credits.nettedHp - net0;
            hullWireAckedUnmatchedHp += credits.unmatchedHp - unm0;
            hullWireAckedPrepaidExpiredHp += credits.prepaidExpiredHp - pxh0;
            drop = pairLateTwins(drop, snapLabel);
            boolean holdMint = resumeMintHold; // the first snapshot after RESUME
            resumeMintHold = false;
            if (drop > HULL_LEDGER_EPS && !hullReconcileSuspended) { // a wire RISE (drop < 0) mints nothing
                if (holdMint) {
                    creditResumeSkipped++;
                    creditResumeSkippedHp += drop;
                    hullCreditResumeSkippedTotal++;
                    hullCreditResumeSkippedHpTotal += drop;
                    return;
                }
                int ev0 = credits.evicted;
                float mintHp0 = credits.mintedHp; // The hp twins' lane totals
                float evHp0 = credits.evictedHp;
                credits.mint(drop, snapLabel);
                hullCreditMintedTotal++;
                hullCreditEvictedTotal += credits.evicted - ev0;
                hullCreditMintedHpTotal += credits.mintedHp - mintHp0;
                hullCreditEvictedHpTotal += credits.evictedHp - evHp0;
            }
        }

        /** The D2 sweep over EVERY entry (horizons differ by class, so never head-only): an
         *  {@link #LABEL_UNSET} stamp resolves to this label and stays; an entry {@code horizon} labels
         *  old — or stamped past this label (a round-wrap restart) — leaves WHOLE into the meters and the
         *  late-twin ring. */
        private void sweepHorizon(long snapLabel) {
            if (entries.isEmpty()) {
                return;
            }
            java.util.Iterator<HullReplayEntry> it = entries.iterator();
            while (it.hasNext()) {
                HullReplayEntry e = it.next();
                if (e.label == LABEL_UNSET) {
                    e.label = snapLabel;
                } else if (snapLabel - e.label >= e.horizon || snapLabel < e.label) {
                    it.remove();
                    outstanding -= e.hull;
                    hzRetired++;
                    hzLeftoverHp += e.hull;
                    hullHzRetiredTotal++;
                    hullHzLeftoverHpTotal += e.hull;
                    if (e.cls == HullCreditRing.SRC_LOCAL) {
                        hzLocal++;
                        hzLocalHp += e.hull;
                        hullHzLocalTotal++;
                        hullHzLocalHpTotal += e.hull;
                    }
                    // free-first (spent ≤ EPS / aged out / pre-restart),
                    // else the oldest retire label — a round-robin cursor overwrote a LIVE slot while a dead one sat.
                    int s = -1, old = 0;
                    for (int c = 0; c < TWIN_SLOTS && s < 0; c++) {
                        long age = snapLabel - twinLabel[c];
                        if (twinHp[c] <= HULL_LEDGER_EPS || age < 0L || age > twinHorizon[c]) s = c;
                        else if (twinLabel[c] < twinLabel[old]) old = c;
                    }
                    if (s < 0) s = old;
                    twinHp[s] = e.hull;
                    twinLabel[s] = snapLabel;
                    twinHorizon[s] = e.horizon;
                    twinCls[s] = e.cls;
                }
            }
        }

        /** A retired entry's twin arriving within its horizon of the retire is WITHHELD from the
         *  mint (slot order; a slot is dead at ≤ EPS), so it never feeds an unrelated push; returns what is left to
         *  mint. Pairs only a drop that could mint (&gt; EPS), so float dust never counts a twin.
         * The slot order + the EPS dead test (= the ring's free test and
         *  {@link #twinLiveHp}'s) were added in a later revision. */
        private float pairLateTwins(float mintable, long snapLabel) {
            for (int s = 0; s < TWIN_SLOTS && mintable > HULL_LEDGER_EPS; s++) {
                long age = snapLabel - twinLabel[s];
                if (twinHp[s] <= HULL_LEDGER_EPS || age < 0L || age > twinHorizon[s]) {
                    continue;
                }
                float p = Math.min(mintable, twinHp[s]);
                twinHp[s] -= p;
                mintable -= p;
                lateTwin++;
                lateTwinHp += p;
                hullLateTwinTotal++;
                hullLateTwinHpTotal += p;
                if (twinCls[s] == HullCreditRing.SRC_LOCAL) {
                    lateTwinLocalHp += p;
                    hullLateTwinLocalHpTotal += p;
                }
            }
            return mintable;
        }

        /** Verifier reads ({@code CcHullLedgerContract} x-rows): the oldest entry's stamp ({@link #LABEL_UNSET}
         *  when empty) and the live late-twin hp at {@code snapLabel} — read with the pairer's own dead test (≤ EPS),
         * so it sums exactly what {@code pairLateTwins} can pair. */
        long headLabel() {
            return entries.isEmpty() ? LABEL_UNSET : entries.peekFirst().label;
        }

        float twinLiveHp(long snapLabel) {
            float sum = 0f;
            for (int s = 0; s < TWIN_SLOTS; s++) {
                long age = snapLabel - twinLabel[s];
                if (twinHp[s] > HULL_LEDGER_EPS && age >= 0L && age <= twinHorizon[s]) {
                    sum += twinHp[s];
                }
            }
            return sum;
        }

        /** Uses up live credits against {@code applied} at {@link #lastSnapLabel} + bumps the lane
         *  totals (the late-after-expiry meters too); returns the remainder.
         * */
        private float consumeCredit(float applied) {
            return consumeCredit(applied, Long.MIN_VALUE);
        }

        /** The wire-acked consume ORDER-BOUND by the window's hit server tick {@code T}
         *  (the end event rides CTRL, the hull drop SNAP — nothing orders them). {@code T < 0}
         *  = unknown: any live credit, counted untick. {@code T > lastSnapLabel} = the apply came FIRST
         *  (its drop is not in yet): PREPAID, netted by the {@link #onWire} at a label {@code >= T}.
         *  {@code T < lastSnapLabel - CREDIT_TICKS} = its own credit is dead: consume NOTHING (never
         *  steal an unrelated replica credit), counted stale. Else only credits minted at {@code >= T}.
         * */
        private void consumeWireAcked(float applied) {
            long hit = wireAckedHitTick;
            if (!(applied > 0f)) {
                return;
            }
            if (hit < 0L) {
                hullWireAckedUntick++;
                consumeCredit(applied, Long.MIN_VALUE);
            } else if (hit > lastSnapLabel) {
                credits.prepay(applied, hit);
                hullWireAckedPrepaid++;
                hullWireAckedPrepaidHp += applied;
            } else if (hit < lastSnapLabel - HullCreditRing.CREDIT_TICKS) {
                hullWireAckedStale++;
                hullWireAckedStaleHp += applied;
            } else {
                consumeCredit(applied, hit);
            }
        }

        /** {@link #consumeCredit(float)} using only credits minted at a label {@code >= minLabel}. */
        private float consumeCredit(float applied, long minLabel) {
            int late0 = credits.lateAfterExpiry;
            float lateHp0 = credits.lateAfterExpiryHp;
            float rem = credits.consume(applied, lastSnapLabel, minLabel);
            if (rem < applied) {
                hullCreditedTotal++;
                hullCreditedHpTotal += applied - rem;
            }
            hullCreditLateAfterExpiryTotal += credits.lateAfterExpiry - late0;
            hullCreditLateAfterExpiryHpTotal += credits.lateAfterExpiryHp - lateHp0;
            hullCreditLateMaxAgeLabels = Math.max(hullCreditLateMaxAgeLabels, credits.lateMaxAgeLabels);
            return rem;
        }

        /** Expire entries older than W LOCAL SIM SECONDS — the pre-D2 ≤ 2 s darker-then-heal-up bound
         *  (a disclosed felt artifact; counted so watches can grade it).
         *  under D2 every entry leaves at its horizon ≤ ceil(W·tps) labels ({@link #localHorizon}), so this
         *  backstop is reached only when label push+H has not arrived by push+2.0 s LOCAL: a feed stall, which
         *  {@code FoeHullStaleLatchLaw.SUSPEND_TICKS} (30 = 0.5 s) turns into a write SUSPEND from
         *  {@link #beginFrame} (nothing is presented from there) and whose RESUME {@link HullLedger#clearReplay}
         *  books; a cap-horizon entry (H near 120) whose last label lands after 2.0 s local; snapshot jitter near
         *  the cap (H 117 at 1850 ms RTT leaves a 50-ms margin, while SUSPEND needs > 0.5 s); or a server label
         *  rate below the local sim rate — the last two never SUSPEND. KEPT ONE ROLL as the
         *  instrument: {@code replay_expired_hp} on a D2 reel reads 0 on every seat whose RTT sits well under
         *  1.9 s, or names the stall / the cap seat (INFO there, never "a stall" by itself); the delete
         *  (this method, W, {@code HullReplayEntry.simSec}, contract rows e, x12, x13, the bars' + term) is queued.
         * */
        void expire(double nowSimSec) {
            while (!entries.isEmpty()
                    && nowSimSec - entries.peekFirst().simSec >= HULL_REPLAY_EXPIRY_SEC) {
                HullReplayEntry e = entries.pollFirst();
                outstanding -= e.hull;
                expired++;
                hullReplayExpiredTotal++;
                replayExpiredHp += e.hull; // The backstop's hp (the signed meter's + side)
                hullReplayExpiredHpTotal += e.hull;
            }
            if (outstanding < 0f) {
                outstanding = 0f;
            }
        }

        /** Fresh-frame tracking meters: |eng−wire| (the felt desync) and |eng−target| (how tightly
         *  the reconcile holds its target — meaningful in meter mode too, where target is unwritten). */
        void noteTrack(float hullGap, float trackErr) {
            if (hullGap > maxHullGap) {
                maxHullGap = hullGap;
            }
            if (trackErr > maxTrackErr) {
                maxTrackErr = trackErr;
            }
            if (hullGap > hullMaxHullGap) {
                hullMaxHullGap = hullGap;
            }
            if (trackErr > hullMaxTrackErr) {
                hullMaxTrackErr = trackErr;
            }
        }

        /** Stale-feed resume support: drop every un-acked entry (the next write is one
         *  authoritative step from wire). */
        void clearReplay() {
            // The cleared hp is booked, and the D1 carry / regen
            // snap and the late-twin ring reset with the deque (the resume reconciles from wire).
            replayClearedHp += outstanding;
            hullReplayClearedHpTotal += outstanding;
            retireCarry = 0f;
            absorbedUpAtWire = absorbedUp;
            java.util.Arrays.fill(twinHp, 0f);
            entries.clear();
            outstanding = 0f;
            float clrHp0 = credits.clearedHp; // The cleared-hp lane total
            float stash0 = credits.clearedStashHp;
            credits.clear(); // A resume reconciles from wire, credits included
            hullCreditClearedHpTotal += credits.clearedHp - clrHp0;
            hullWireAckedStashClearedHp += credits.clearedStashHp - stash0;
            resumeMintHold = true; // the stall's whole drop is never a credit
        }

        /** Axis-C close (pre-blow) + the per-entity CLOSE summary line (counters + scalars). */
        void close(String tag, long entityId) {
            if (closed) {
                return;
            }
            closed = true;
            hullLedgerCloses++;
            hullOpenAtCloseHpTotal += outstanding; // The young hp still open (the + side)
            PilotWatchLog.info("predict foe-hull-ledger CLOSE [" + tag + " entity " + entityId + "]:"
                    + " rawHull=" + fmt1(rawHull) + " rawHullUnc=" + fmt1(rawHullUnc)
                    + " wire=" + fmt1(prevWire) + " refEng=" + fmt1(refEngine)
                    + " outstanding=" + fmt1(outstanding) + " entries=" + entries.size()
                    + " pushed=" + pushed + " retired=" + retired + " expired=" + expired
                    + " wireAcked=" + wireAcked + " wireAckedRestored=" + wireAckedRestored
                    + " deadSrcAcked=" + deadSourceAcked
                    + " unrepUp=" + unrepUp + " unrepDown=" + unrepDown + " ledgerGap=" + ledgerGap
                    + " absorbedUp=" + fmt1(absorbedUp)
                    + " trackErrMax=" + fmt1(maxTrackErr) + " hullGapMax=" + fmt1(maxHullGap)
                    + creditCloseTokens() + hzCloseTokens());
        }

        /** The meters of the CLOSE row (per ledger; each token space-led; one builder so the
         *  contract's twin row reads exactly what prints; lane twins = {@code hzLaneTokens}). Conservation, every
         *  term printed (outstanding by the row itself):
         *  {@code pushedHp = fifoRetiredHp + hzLeftoverHp + replayExpiredHp + replayClearedHp + outstanding}.
         *  The per-ledger hz-retired / hz-local / late-twin / regen-share
         *  fields are kept but unprinted here (their CLOSE tokens deleted unread). */
        String hzCloseTokens() {
            return " hzLeftoverHp=" + fmt1(hzLeftoverHp)
                    + " replayExpiredHp=" + fmt1(replayExpiredHp) + " replayClearedHp=" + fmt1(replayClearedHp)
                    + " pushedHp=" + fmt1(pushedHp) + " fifoRetiredHp=" + fmt1(fifoRetiredHp);
        }

        /** The Shape A credit meters of the CLOSE row (per ledger; each token space-led), one builder
         *  so the contract's summary-identity row reads exactly what the row prints.
         * */
        String creditCloseTokens() {
            return " creditMinted=" + credits.minted + " credited=" + credits.credited
                    + " creditedHp=" + fmt1(credits.creditedHp) + " creditExpired=" + credits.expired
                    + " creditExpiredHp=" + fmt1(credits.expiredHp) + " creditEvicted=" + credits.evicted
                    + " creditResumeSkipped=" + creditResumeSkipped // post-RESUME drops the mint skipped
                    + " creditResumeSkippedHp=" + fmt1(creditResumeSkippedHp)
                    // The hp twins (mintedHp = credited + expired + evicted + cleared + live)
                    + " creditMintedHp=" + fmt1(credits.mintedHp) + " creditEvictedHp=" + fmt1(credits.evictedHp)
                    + " creditClearedHp=" + fmt1(credits.clearedHp)
                    + " creditLateAfterExpiry=" + credits.lateAfterExpiry
                    + " creditLateAfterExpiryHp=" + fmt1(credits.lateAfterExpiryHp)
                    + " creditLateMaxAge=" + credits.lateMaxAgeLabels;
        }
    }
}
