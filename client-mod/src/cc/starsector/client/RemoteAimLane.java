package cc.starsector.client;

import cc.starsector.client.net.ClientPilotAimFrame;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.WeaponAPI;

import org.lwjgl.util.vector.Vector2f;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Remote turret aim —
 * the AIMP remote-aim client lane: render-thread fold + apply of the server's batched
 * per-piloted-seat aim points, so a REMOTE pilot's manual turrets visibly track their cursor
 * between shots instead of resting at spawn facing. NEW-LANE-LAW collaborator of
 * {@link PredictReconcileCombatPlugin} (the {@link ModuleMountLane} internal-route shape): the
 * net-thread queue lives in the session ({@code takePilotAims()} is the poll surface);
 * {@link #maintain} runs per frame at the {@code advance()} maintainer block.
 *
 * <p><b>Mechanism (engine-native seam, byte-confirmed):</b> a live replica's
 * {@code mouseTarget} feeds every non-autofire weapon group each {@code Ship.advance}
 * ({@code Ship.advance} — NOT player-gated; chokepoint
 * {@code WeaponGroup.getParamIfNotInactiveHardpoint}: the selected group ALWAYS aims,
 * non-selected manual groups aim when the arc bears and the mount is no hardpoint; slew rate =
 * the weapon spec's own turn rate; nothing clears a live replica's mouseTarget). So this lane
 * writes {@code ship.getMouseTarget().set(x, y)} with the seat's latest wire aim and lets the
 * engine slew natively — no per-frame angle driver. Two enablers ride each driven ship:
 * a {@code SELECT_GROUP 0} self-heal whenever no group is selected (the
 * {@code maintainOwnMountedGroupSelect} idiom — ex-AI bodies have none), and
 * {@code setSuspendAutomaticTurning(false)} on its non-beam weapons — MANDATORY, not hygiene
 * (the suspend flag gates the engine's ENTIRE shared slew block,
 * and {@link SameSideAimLane} sets it true every frame on
 * same-side held mounts it steers; the hold walk steer-SKIPs AIMP-driven ships via
 * {@link #drives} so this clear is never fought).
 *
 * <p><b>Facing ownership (one writer at a time):</b> for a ship with a live AIMP row this
 * lane's native slew OWNS rest facing; the {@link SameSideAimLane} discharge snap stays
 * authoritative AT the shot (presentation lanes, disjoint moments — never-conceal-desync safe).
 *
 * <p><b>Scope v1:</b> piloted ships only (the server fans nothing else); the client
 * drops its OWN row (the selection-not-minting law); autofire groups stay untouched (their
 * local AI already slews them); the remote pilot's selected-group index is not on the wire —
 * selected + arc-bearing manual turrets slew, non-bearing hardpoints hold.
 *
 * <p><b>Inter-sample smoothing (the terminus-continuity fix):</b> the
 * fan delivers one sample per ~8 ticks (7.5 Hz) and the linked-group path consumes
 * {@code mouseTarget} RAW every frame — so a remote sweep renders as a stair-step (F9 window:
 * aim steps p50 387 su at 7.5 Hz while the true cursor moves ~2900 su/s continuously).
 * Fix: per-seat interpolation BETWEEN the two most recent REAL wire samples at this
 * write seam — each render frame the applied point advances {@code prev→latest} over exactly
 * ONE fan interval, the one the SERVER delivers ({@link ClientWireKnobs#aimFanSeconds}, the
 * {@code WireKnob.AIM_FAN_TICKS} row of every PSTB block — it replaced
 * a learned arrival-time estimate that could lock too small or stretch to twice the fan), and
 * HOLDS there (never extrapolates; bounded by the traverse-clamp testimony). Fan
 * unknown ({@code 0}) = the latest sample raw. Pause-then-move (a change after more than
 * {@link #GAP_FACTOR} fan intervals) and teleport-scale flicks ({@code SNAP_THROUGH_DIST}) SNAP —
 * a pause is not cadence, and a true instant flick is reproduced exactly, never swept across
 * invented cursor path. The AIMP fan cadence and its wire cost are untouched (the
 * 20+-Paladin condition); the interval rides the PSTB census, one u8 per admission.
 *
 * <p><b>Knob</b> {@code cc.predict.remoteAim} = {@code off|meter|apply} (default {@code apply}
 * — the missilePose default-ON precedent; knob-flip = rollback; {@code meter} folds + counts
 * without touching ships); <b>knob</b> {@code cc.predict.remoteAimSmooth} = {@code off|apply}
 * (default {@code apply}; {@code off} = the raw stair-step, the rollback lever).
 * <b>Meters</b>: {@code aim_rows} folded wire rows, {@code aim_applied} mouseTarget writes,
 * {@code aim_group_missing} SELECT_GROUP self-heals, plus skip/anomaly counters and the
 * {@code aim_smooth_*} family (lerp frames, snap classes, snap-excluded max applied step su,
 * the delivered fan in ms; the step max is graded only while a fan is delivered — the seat
 * glides — and raw-mode steps ride the INFO {@code aim_smooth_step_max_cold*} tokens) — the
 * {@code predict remote-aim-meter} line rides the standard
 * meter block and round totals log at the MRST boundary. Threading: render thread only, like
 * every sibling collaborator.
 */
final class RemoteAimLane {

    /** Validated {@code off|meter|apply} parse; anything else THROWS at class load (knobs.md law). */
    static final String REMOTE_AIM_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.remoteAim", System.getProperty("cc.predict.remoteAim", "apply"));
    static final boolean REMOTE_AIM_METER = !"off".equals(REMOTE_AIM_MODE);
    static final boolean REMOTE_AIM_APPLY = "apply".equals(REMOTE_AIM_MODE);

    /** Two-value knob (the {@link SameSideAimLane} local-parse idiom — the shared
     *  tri-state parse would admit {@code meter}, which this sub-lane has no use for). */
    static final String REMOTE_AIM_SMOOTH_MODE = parseSmoothMode("cc.predict.remoteAimSmooth",
            System.getProperty("cc.predict.remoteAimSmooth", "apply"));
    static final boolean REMOTE_AIM_SMOOTH = "apply".equals(REMOTE_AIM_SMOOTH_MODE);

    /** teleport-scale snap-through: ≈1.5× the max REAL sweep step the
     *  forensics observed (1613 su at 8-tick cadence, ~2900 su/s cursor). Above = an instant
     *  flick/seat-rebind — the true cursor jumped, so reproduce the jump exactly; sweeping it
     *  would invent cursor path the pilot never traced. */
    private static final float SNAP_THROUGH_DIST = 2500f;
    /** A change arriving more than this many delivered
     *  fan intervals after the previous one is a pause-then-move: it SNAPS (a pause is not
     *  cadence, so the glide never sweeps across it). */
    static final float GAP_FACTOR = 3f;
    static final int GROUP_HEAL_MAX_TRIES = 3;

    /** A12: the SELECT_GROUP 0 heal is due only while
     *  a body has no selected group AND is under the per-body bound; a body that cannot hold a
     *  selection (pod / module class) costs ≤ {@link #GROUP_HEAL_MAX_TRIES} commands, then stops. */
    static boolean groupHealDue(boolean selectedNull, int tries, boolean gaveUp) {
        return selectedNull && !gaveUp && tries < GROUP_HEAL_MAX_TRIES;
    }

    /** The give-up ROW is capped per lane (the consecutive reset lets a body that
     *  oscillates held/null give up once per cycle); the counter keeps counting. Pure (AimRebasePhaseSmoke). */
    static final int GROUP_HEAL_GAVE_UP_ROWS_MAX = 20;
    static boolean giveUpRowDue(int gaveUpSoFar) {
        return gaveUpSoFar <= GROUP_HEAL_GAVE_UP_ROWS_MAX;
    }

    static final int UNBOUND_GRADED = 0, UNBOUND_DEAD = 1, UNBOUND_PREBIND = 2;

    /** The #9 retire split: WHY an aim row was
     *  retired with no live body. Only two no-body states are ones the client itself produced
     *  and are not defects: {@link #UNBOUND_DEAD} = the server killed a BOUND body (the
     *  between-lives window; 39 of 39 on seat 1) and {@link #UNBOUND_PREBIND}
     *  = a registry row still awaiting first sight / respawn re-adopt (the spawn edge). Every
     *  other state stays on the graded {@code aim_unbound}: no registry row at all; a row killed
     *  before it ever bound — INCLUDING the bots-bind id-contract ERROR
     *  ({@code PredictReconcileCombatPlugin} "has NO descriptor variant row": {@code killed=true}
     *  with {@code ship == null}, which is why the DEAD arm needs {@code hasBody} and never keys
     *  on {@code killed} alone); a bound row whose body is locally dead while the wire says alive.
     *  {@code hasBody} = the registry's bound body (dead or alive), not the caller's live-ship
     *  view. Pure (AimRebasePhaseSmoke). */
    static int unboundClass(boolean hasRow, boolean killed, boolean hasBody) {
        if (hasRow && killed && hasBody) {
            return UNBOUND_DEAD;
        }
        if (hasRow && !killed && !hasBody) {
            return UNBOUND_PREBIND;
        }
        return UNBOUND_GRADED;
    }

    private final PredictReconcileCombatPlugin plugin;

    /** seat wireId → latest wire aim world point (mutated in place — zero steady-state churn;
     *  bounded by the wire row law, ≤64 live seats). */
    private final Map<Long, Aim> latest = new java.util.HashMap<Long, Aim>();

    /** Ships this lane wrote an aim to — the {@link #drives} steer-skip set. Weak: mid-round
     *  despawns collect; dead bodies are pruned each maintain; MRST clears explicitly. */
    private final java.util.Set<ShipAPI> drivenBodies =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<ShipAPI, Boolean>());

    private long aimRows;        // wire rows folded (own row excluded)
    private long aimApplied;     // mouseTarget writes
    private int aimGroupMissing; // SELECT_GROUP self-heals issued
    private int aimGroupHealGaveUp; // bodies whose heal hit GROUP_HEAL_MAX_TRIES (A12)
    private int aimSkippedOwn;   // own rows dropped (selection-not-minting)
    private int aimUnbound;      // rows retired with no live body, GRADED residue only (see unboundClass)
    /** The no-body retires the client itself
     *  produced ({@link #unboundClass}); exported as INFO beside {@code aim_unbound}. */
    private int aimUnboundDead;     // retired: server-decided death of a BOUND body (between lives)
    private int aimUnboundPrebind;  // retired: registry row awaiting first bind / respawn re-adopt
    private int aimTargetNull;   // driven ship with a null mouseTarget vector (unexpected)
    private int anomalies;       // per-ship apply throwables swallowed (fail-soft)
    private long aimSmoothLerp;      // frames that applied an interpolated (f<1) point
    private int aimSmoothSnapFirst;  // first-sample snaps (fresh seat row)
    private long aimSmoothSnapGap;   // pause-then-move snaps (stale-hold gap — expected often)
    private int aimSmoothSnapTp;     // teleport-scale snap-throughs (flick/rebind)
    private float aimSmoothStepMax;  // max per-frame APPLIED step su, snap-excluded, WARM (the
                                     // continuity face — raw mode records the stair-step here,
                                     // smooth mode the lerp step: the AB compares this token)
    /** The max step's SUBJECT (handoff NEXT 1): the wire snapshot tick + seat wire id of
     *  the frame that set {@link #aimSmoothStepMax} — 141521's 981.9 su RED-SEV was unattributable. */
    private long aimSmoothStepMaxTick = Long.MIN_VALUE;
    private long aimSmoothStepMaxWire;
    /** The COLD twin of the graded max + subject: steps
     *  minted while no fan is delivered (raw mode; INFO; run tallies). */
    private float aimSmoothStepMaxCold;
    private long aimSmoothStepMaxColdTick = Long.MIN_VALUE;
    private long aimSmoothStepMaxColdWire;
    private boolean firstApplyLogged;
    private boolean groupSelectLogged;
    private boolean anomalyLogged;

    RemoteAimLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** TRUE while this lane drives {@code ship}'s aim — the {@code reassertReplayDrivenHold}
     *  steer-skip guard (the discharge snap must not fight the native slew at rest). */
    boolean drives(ShipAPI ship) {
        return REMOTE_AIM_APPLY && drivenBodies.contains(ship);
    }

    /** Per-frame fold + apply (render thread, the advance() maintainer block; {@code amount}
     *  = the plugin's own advance delta — 0 on paused frames, so interp never drifts while
     *  paused). {@code off} leaves the session queue to its bounded drop-with-count posture
     *  by design (rollback). */
    void maintain(float amount) {
        if (!REMOTE_AIM_METER || plugin.session == null) {
            return; // session null = pre-bind advance frames (the smoke drives advance() bare)
        }
        // Null-tolerant take: the selftest smokes stub PredictSession via a reflection proxy
        // whose unstubbed methods return null — null = no frames, same as the default method.
        java.util.List<ClientPilotAimFrame> frames = plugin.session.takePilotAims();
        for (ClientPilotAimFrame frame : frames != null ? frames
                : java.util.Collections.<ClientPilotAimFrame>emptyList()) {
            for (int i = 0; i < frame.rowCount(); i++) {
                long wireId = frame.shipWireId(i);
                if (wireId == plugin.session.shipId()) {
                    // selection-not-minting: the shared fan carries every seat; the
                    // client's own aim is local input truth, never wire-driven.
                    aimSkippedOwn++;
                    continue;
                }
                aimRows++;
                Long key = Long.valueOf(wireId);
                Aim aim = latest.get(key);
                if (aim == null) {
                    aim = new Aim();
                    latest.put(key, aim);
                }
                foldSample(aim, frame.worldX(i), frame.worldY(i));
            }
        }
        if (latest.isEmpty()) {
            return;
        }
        if (REMOTE_AIM_APPLY && !drivenBodies.isEmpty()) {
            // Dead driven bodies leave the steer-skip set (their seat row's next hull re-adds).
            for (Iterator<ShipAPI> it = drivenBodies.iterator(); it.hasNext();) {
                ShipAPI body = it.next();
                if (body == null || !body.isAlive()) {
                    it.remove();
                }
            }
        }
        for (Iterator<Map.Entry<Long, Aim>> it = latest.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Aim> entry = it.next();
            entry.getValue().tSince += amount; // once per frame per row — the lerp clock
            PredictReconcileCombatPlugin.RemoteEntity re =
                    plugin.remoteEntities.get(entry.getKey());
            ShipAPI ship = re != null && !re.killed ? re.ship : null;
            if (ship == null || !ship.isAlive()) {
                // Seat between lives (killed/hull-less/hulked): retire the row — the fan's
                // change-gate + keepalive re-delivers a live seat's aim within ≈1 s.
                it.remove();
                // Bump exactly ONE bucket by WHY.
                // hasBody = re.ship (the registry's bound body, dead or alive), never the local
                // `ship` above, which is already nulled for a killed row.
                int cls = unboundClass(re != null, re != null && re.killed, re != null && re.ship != null);
                if (cls == UNBOUND_DEAD) {
                    aimUnboundDead++;
                } else if (cls == UNBOUND_PREBIND) {
                    aimUnboundPrebind++;
                } else {
                    aimUnbound++;
                }
                continue;
            }
            if (!REMOTE_AIM_APPLY) {
                continue; // meter mode: rows counted above, ships untouched
            }
            try {
                apply(entry.getKey().longValue(), ship, entry.getValue(), amount > 0f);
            } catch (Throwable t) {
                anomalies++;
                if (!anomalyLogged) {
                    anomalyLogged = true;
                    PilotWatchLog.error("predict remote-aim: apply FAILED seat="
                            + entry.getKey() + " -> row dropped (further anomalies counted"
                            + " silently)", t);
                }
                it.remove(); // never let a poison row retry every frame
            }
        }
    }

    /** Sample intake: first-sample snap, keepalive skip, same-frame overwrite, then the
     *  gap rule on the DELIVERED fan ({@link ClientWireKnobs#aimFanSeconds}; fan unknown = never a
     *  gap — the lerp is raw anyway) plus a distance snap. Runs in EVERY mode (state stays warm
     *  across knob flips); the lerp is gated at {@link #apply}. */
    void foldSample(Aim aim, float sx, float sy) {
        if (!aim.hasSample) {
            aim.hasSample = true;
            aim.prevX = sx;
            aim.prevY = sy;
            aim.x = sx;
            aim.y = sy;
            aim.tSince = 0f;
            aimSmoothSnapFirst++;
            return; // stepMeterArmed stays false — first apply re-arms without minting a step
        }
        if (sx == aim.x && sy == aim.y) {
            return; // keepalive re-delivery of the change-gated point: not cadence — tSince
                    // keeps growing so the NEXT move classifies as a gap snap, never a sweep
        }
        if (aim.tSince == 0f) {
            // 2nd+ fold inside ONE maintain (render hitch batched fans): latest-wins overwrite
            // — the glide origin stays the last point the lerp started from.
            aim.x = sx;
            aim.y = sy;
            return;
        }
        float dx = sx - aim.x;
        float dy = sy - aim.y;
        // The gap rule on the server's own fan interval.
        float fan = ClientWireKnobs.aimFanSeconds();
        boolean gap = fan > 0f && aim.tSince > fan * GAP_FACTOR;
        boolean far = dx * dx + dy * dy > SNAP_THROUGH_DIST * SNAP_THROUGH_DIST;
        if (gap || far) {
            aim.prevX = sx; // snap: lerp collapses to the new point immediately
            aim.prevY = sy;
            aim.stepMeterArmed = false; // legitimate jump — never contaminates the step meter
            if (far) {
                aimSmoothSnapTp++;
            } else {
                aimSmoothSnapGap++;
            }
        } else {
            aim.prevX = aim.x;
            aim.prevY = aim.y;
        }
        aim.x = sx;
        aim.y = sy;
        aim.tSince = 0f;
    }

    private void apply(long wireId, ShipAPI ship, Aim aim, boolean advanced) {
        Vector2f mouseTarget = ship.getMouseTarget();
        if (mouseTarget == null) {
            // The design expects a non-null vector on every live hull; carried honestly as a
            // counter (never fake a write) — the meter line exposes a broken premise.
            aimTargetNull++;
            return;
        }
        float ax = aim.x;
        float ay = aim.y;
        if (REMOTE_AIM_SMOOTH) {
            // Advance prev→latest over ONE delivered fan interval, then HOLD (f clamped —
            // interpolation between real samples only); fan unknown = f 1 = the latest sample raw.
            float f = glideFraction(aim.tSince);
            if (f < 1f) {
                ax = aim.prevX + (aim.x - aim.prevX) * f;
                ay = aim.prevY + (aim.y - aim.prevY) * f;
                aimSmoothLerp++;
            }
        }
        mouseTarget.set(ax, ay);
        aimApplied++;
        // Snap-excluded applied-step meter (the FoeFacingLane snap-re-arm idiom): raw mode
        // records the stair-step magnitude, smooth mode the lerp step — the AB's direct face.
        float sdx = ax - aim.lastAppliedX;
        float sdy = ay - aim.lastAppliedY;
        meterStep(aim, (float) Math.sqrt(sdx * sdx + sdy * sdy), wireId);
        aim.lastAppliedX = ax;
        aim.lastAppliedY = ay;
        boolean newlyDriven = drivenBodies.add(ship);
        // MANDATORY clear: suspendAutomaticTurning gates the engine's whole shared
        // slew block (manual AND autofire) — SameSideAimLane set it true on held mounts before
        // this ship's first AIMP row, and nothing else ever clears it. Idempotent; beams stay
        // relay-owned (disjoint flag sets).
        List<WeaponAPI> weapons = plugin.weaponLists.weapons(ship); // per-frame cache
        if (weapons != null) {
            for (int w = 0; w < weapons.size(); w++) {
                WeaponAPI weapon = weapons.get(w);
                if (weapon != null && !weapon.isBeam()) {
                    weapon.setSuspendAutomaticTurning(false);
                }
            }
        }
        // The self-heal idiom (one-shot log): an ex-AI body has NO selected group, and
        // WeaponGroup nulls the aim param for every non-selected non-bearing group.
        // bounded per BODY — the row re-points in place
        // (in CommandTransferLane and ModuleMountLane), so a new body re-arms the latch here.
        if (aim.groupHealBody != ship) {
            aim.groupHealBody = ship;
            aim.groupHealTries = 0;
            aim.groupHealGaveUp = false;
        }
        // Paused frames (amount 0) never try: Ship.giveCommand only QUEUES and
        // Ship.advance drains it, so a paused try is never consumed and would burn the bound.
        boolean selectedNull = advanced && ship.getSelectedGroupAPI() == null;
        if (advanced && !selectedNull) {
            // The bound counts CONSECUTIVE failures — a held group resets it,
            // so a body that can hold a selection is never charged for the frames its queued try drained.
            aim.groupHealTries = 0;
            aim.groupHealGaveUp = false;
        }
        if (groupHealDue(selectedNull, aim.groupHealTries, aim.groupHealGaveUp)) {
            ship.giveCommand(ShipCommand.SELECT_GROUP, null, 0);
            aim.groupHealTries++;
            aimGroupMissing++;
            if (!groupSelectLogged) {
                groupSelectLogged = true;
                PilotWatchLog.info("predict remote-aim: seat " + wireId + " had NO selected"
                        + " weapon group (ex-AI body) — SELECT_GROUP 0 issued (self-heals <="
                        + GROUP_HEAL_MAX_TRIES + " per body, counted as aim_group_missing)");
            }
        } else if (selectedNull && !aim.groupHealGaveUp) {
            aim.groupHealGaveUp = true; // tries hit the bound: this body never holds a group
            aimGroupHealGaveUp++;
            if (giveUpRowDue(aimGroupHealGaveUp)) // the row is capped, the meter is not
            PilotWatchLog.info("predict remote-aim: seat " + wireId + " body cannot hold a"
                    + " selected group after " + GROUP_HEAL_MAX_TRIES + " consecutive SELECT_GROUP 0"
                    + " hull=" + (ship.getHullSpec() == null ? "?" : ship.getHullSpec().getHullId())
                    + " — heal stopped (aim_group_heal_gave_up)"); // the class is measured, never named
        }
        if (!firstApplyLogged) {
            firstApplyLogged = true;
            PilotWatchLog.info("predict remote-aim: FIRST apply seat=" + wireId
                    + " aim=(" + fmt1(aim.x) + "," + fmt1(aim.y) + ") mode=" + REMOTE_AIM_MODE
                    + " newlyDriven=" + newlyDriven + " — native mouseTarget slew live");
        }
    }

    /** The lerp fraction {@code prev→latest} at
     *  {@code tSince} seconds after the latest change: the glide lasts exactly ONE delivered fan
     *  interval, then holds at 1; fan unknown ({@code 0}) = 1 = the latest sample raw. Pure
     *  (AimRebasePhaseSmoke R1 / R3 drive it on the API-less classpath). */
    static float glideFraction(float tSince) {
        float fan = ClientWireKnobs.aimFanSeconds();
        return fan > 0f ? Math.min(1f, tSince / fan) : 1f;
    }

    /** The step meter's production entry ({@link #apply}): subject tick = the wire snapshot tick. */
    void meterStep(Aim aim, float step, long wireId) {
        meterStep(aim, step, wireId, plugin.lastSnapshotSimTick);
    }

    /** The snap-excluded step meter's warm/cold split:
     *  an unarmed meter (first frame / after a snap) consumes the re-arm without minting; a WARM
     *  seat (a fan is delivered, so it glides) feeds the graded
     *  {@code aim_smooth_step_max*}, a raw one the INFO {@code aim_smooth_step_max_cold*}. The
     *  "REJECTED: flooring at the nominal fan" named a client-BAKED nominal; a delivered
     *  fan is the server's own cadence, so the true sample spacing is never shorter than it.
     *  {@code tick} is a parameter so
     *  AimRebasePhaseSmoke drives it plugin-less (its API-less classpath cannot build a plugin).
     *  Pure observation: writes meter state + {@code stepMeterArmed} only. */
    void meterStep(Aim aim, float step, long wireId, long tick) {
        if (!aim.stepMeterArmed) {
            aim.stepMeterArmed = true; // snap/first frame consumed without minting a step
            return;
        }
        if (ClientWireKnobs.aimFanSeconds() > 0f) {
            if (step > aimSmoothStepMax) {
                aimSmoothStepMax = step;
                aimSmoothStepMaxTick = tick;
                aimSmoothStepMaxWire = wireId;
            }
        } else if (step > aimSmoothStepMaxCold) {
            aimSmoothStepMaxCold = step;
            aimSmoothStepMaxColdTick = tick;
            aimSmoothStepMaxColdWire = wireId;
        }
    }

    /** This lane's meter line (the sibling {@code log*Meter} idiom — one line, greppable). */
    void logMeter(String where) {
        if (!REMOTE_AIM_METER) {
            return;
        }
        PilotWatchLog.info("predict remote-aim-meter [" + where + "]:"
                + " aim_mode=" + REMOTE_AIM_MODE
                + " aim_rows=" + aimRows
                + " aim_applied=" + aimApplied
                + " aim_group_missing=" + aimGroupMissing
                + " aim_group_heal_gave_up=" + aimGroupHealGaveUp
                + " aim_skipped_own=" + aimSkippedOwn
                + " aim_unbound=" + aimUnbound
                + " aim_unbound_dead=" + aimUnboundDead + " aim_unbound_prebind=" + aimUnboundPrebind
                + " aim_target_null=" + aimTargetNull
                + " aim_anomalies=" + anomalies
                + smoothTokens());
    }

    /** Meter-line suffix (append-safe: the turret-aim watch-bar grader reads tokens
     *  by name). {@code aim_smooth_ema_ms} KEEPS its name (graders key on it) and now prints the
     *  DELIVERED fan interval in ms ({@link ClientWireKnobs#aimFanSeconds}; ≈133 at the default
     *  8-tick fan at 60 Hz; 0 = unknown / raw). */
    String smoothTokens() {
        float fan = ClientWireKnobs.aimFanSeconds();
        return " aim_smooth_mode=" + REMOTE_AIM_SMOOTH_MODE
                + " aim_smooth_lerp=" + aimSmoothLerp
                + " aim_smooth_snap_first=" + aimSmoothSnapFirst
                + " aim_smooth_snap_gap=" + aimSmoothSnapGap
                + " aim_smooth_snap_tp=" + aimSmoothSnapTp
                + " aim_smooth_step_max=" + fmt1(aimSmoothStepMax)
                + " aim_smooth_step_max_tick="
                + (aimSmoothStepMaxTick == Long.MIN_VALUE ? "-" : Long.toString(aimSmoothStepMaxTick))
                + " aim_smooth_step_max_wire=" + aimSmoothStepMaxWire
                + " aim_smooth_ema_ms=" + Math.round(fan * 1000f)
                + " aim_smooth_step_max_cold=" + fmt1(aimSmoothStepMaxCold)
                + " aim_smooth_step_max_cold_tick=" + (aimSmoothStepMaxColdTick == Long.MIN_VALUE
                        ? "-" : Long.toString(aimSmoothStepMaxColdTick))
                + " aim_smooth_step_max_cold_wire=" + aimSmoothStepMaxColdWire;
    }

    /** MRST boundary — aim rows + driven set die with the round's bodies; run tallies stay. */
    void resetRound() {
        if (latest.isEmpty() && drivenBodies.isEmpty() && aimRows == 0) {
            return;
        }
        PilotWatchLog.info("predict remote-aim round totals: aim_rows=" + aimRows
                + " aim_applied=" + aimApplied + " aim_group_missing=" + aimGroupMissing
                + " aim_group_heal_gave_up=" + aimGroupHealGaveUp
                + " aim_skipped_own=" + aimSkippedOwn + " aim_unbound=" + aimUnbound
                + " aim_unbound_dead=" + aimUnboundDead + " aim_unbound_prebind=" + aimUnboundPrebind
                + " aim_target_null=" + aimTargetNull + " aim_anomalies=" + anomalies
                + smoothTokens());
        latest.clear(); // per-seat interp state (prev/latest/clock) dies with the round's rows
        drivenBodies.clear();
    }

    private static String fmt1(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Validated {@code off|apply} parse; anything else THROWS at class load (knobs.md law —
     *  the {@code SameSideAimLane} local two-value idiom). */
    private static String parseSmoothMode(String knob, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("off".equals(value) || "apply".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException(knob + " must be off|apply, got: " + raw);
    }

    /** One seat's wire-aim interp state (mutable — updated in place per fold):
     *  {@code prev→(x,y)} are the two most recent REAL samples, {@code tSince} the lerp clock
     *  (the glide length is the delivered fan, {@link ClientWireKnobs#aimFanSeconds}, never
     *  per-seat state). Zero steady-state churn. */
    static final class Aim {
        float x;              // latest wire sample (lerp target)
        float y;
        float prevX;          // previous wire sample (lerp origin)
        float prevY;
        float tSince;         // seconds since the latest CHANGE folded (keepalives excluded)
        float lastAppliedX;   // last point actually written — the step meter's base
        float lastAppliedY;
        boolean hasSample;    // first-fold snap flag
        boolean stepMeterArmed; // false across a snap: legit jumps never mint a step sample
        ShipAPI groupHealBody;  // the body the A12 heal latch belongs to (re-point = re-arm)
        int groupHealTries;     // SELECT_GROUP 0 issued to groupHealBody (≤ GROUP_HEAL_MAX_TRIES)
        boolean groupHealGaveUp; // groupHealBody cannot hold a selection (pod / module class)
    }
}
