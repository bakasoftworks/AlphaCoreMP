package cc.starsector.client;

import cc.starsector.client.net.ClientGameplayEdgeEvent;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.List;

/**
 * The same-side SNAP-AND-HOLD aim lane: replica turret bearing slaved to the
 * server's own discharge aim. The defect it exists for (owner-eye, run 0804-111915):
 * same-side fortress turrets fire wire-replayed discharges at the SERVER's bearing while the freed
 * native autofire slews toward its own LOCAL pick — "gauss turrets not pointing at enemies as they
 * fired at them". The picks diverge by construction (randomized retarget cadence + local-currAngle
 * tie-break), and the aim-ray→foe resolution measured
 * too unreliable to slave a target from (65.8% within 6°, ~34% >6° from ANY live foe),
 * so this lane pins the ANGLE, not the target.
 *
 * <p><b>Mechanism</b> (zero wire; the write order is pinned): at each same-side replay discharge
 * {@link #record} stores weapon → wire {@code aimFacingDeg} (+ the discharge's wire tick) — the
 * server mount's post-step {@code currAngle} at the discharge-detection tick for every non-missile
 * discharge; a MISSILE mount's is the launch facing = mount + {@code angleOffsets[k]}
 * (≤ 7°) (NaN = unbound read, skipped + counted, never faked).
 * Per frame, {@code reassertReplayDrivenHold}'s same-side non-beam walk calls {@link #steer}:
 * {@code setSuspendAutomaticTurning(true)} + {@code setCurrAngle(lastAim)} — the plugin dispatch
 * runs AFTER every ship advance, so ours is the frame's LAST
 * mount-angle write (write-wins, the proven {@code maintainBeamAim} idiom), and setCurrAngle raises
 * the tracker's snapped-this-frame flag so the NEXT frame's native slew skips its turn block
 * entirely — doubly robust. Suspend gates ONLY the
 * auto-turn block; the engine's per-frame arc clamp still runs and we re-write after it. The
 * tracker stores a hull-RELATIVE angle ({@code setCurrAngle(a)} ⇒ {@code a − pivotFacing},
 * {@code getCurrAngle} = rel + facing), so ONE write would follow the hull, but
 * the per-frame re-write of the same WORLD angle pins the mount in world space while the hull
 * turns. Pre-first-discharge mounts have no record and stay native-untouched by design. Beams stay
 * relay-owned (disjoint flag sets). Server mounts slew hard between their own
 * consecutive discharges (mean |Δaim| 20–40°), so per-discharge snap steps match server
 * multi-target reality; a slew-limit on the pin is REJECTED (never
 * add latency). {@code setShipTarget} slaving = the BANKED alternative (new wire surface +
 * PD-blind).
 *
 * <p><b>Hold law</b>
 * (against turrets that snapped unnaturally back to neutral):
 * the pure verdict is {@link #verdict}, smoke {@code SameSideAimReleaseSmoke}. Per steer frame:
 * <ol>
 *   <li><b>Hold expiry ⇒ RELEASE</b> — {@link #HOLD_SECONDS} after the pin's discharge tick with no
 *   newer discharge: {@code lastAim.remove} + {@code setSuspendAutomaticTurning(false)}; the native
 *   tracker owns the mount until the next discharge re-pins. The engine's own target-less turret
 *   law is 15 s (its idle clock runs even while suspended, so a mount
 *   with no LOCAL target then rests at once — turrets/hidden at 5× turn rate capped 360 °/s,
 *   hardpoints at 0.25×, missile hardpoints never). The SERVER's idle
 *   turrets did exactly that in the recorded match (parity walk @6600: every module mount at
 *   {@code facing + arcFacing}) while the client's stayed frozen at a stale pin. A released mount
 *   whose local autofire HAS a target tracks that local pick instead (the accepted divergence
 *   between discharges) — "rests like the server" holds for a quiet field. The
 *   engine's clock is "since the target was lost", ours "since the last discharge" (the wire's
 *   {@code AUTOFIRE_TARGET} event would be the exact clock — queued).</li>
 *   <li><b>Aim inside the arc ⇒ PIN</b> ({@code setCurrAngle(aim)}), except a held pin whose aim
 *   re-enters the arc through the FAR edge while the mount is parked at the other ⇒ RELEASE
 *   ({@code release_far_reentry}): a steer frame never jumps a mount across its arc — only a
 *   discharge moves a mount discontinuously.</li>
 *   <li><b>Aim outside the arc ⇒ PARK at an edge</b> (the D-BOMBFACING park,
 *   kept): a FRESH pin parks at the edge nearer the AIM (the server fired from there: its own
 *   clamp holds its mount at that edge); a HELD pin parks at the edge on the mount's CURRENT side
 *   — sticky, never flips. The earlier rule re-picked the edge nearer the aim EVERY frame, so
 *   on the rotating fortress (−14.7 °/s) a stale aim drifting behind the mount ({@code rel}
 *   wrapping ±180) flipped the park to the other edge = a one-frame jump across the whole arc
 *   through neutral ({@code clamped_sets} +74 454 in the 1 500-tick mark window, run max delta
 *   170°). The engine's own clamp is continuous — a server mount whose
 *   target drifts behind it stays on its edge — so the sticky park is the faithful mirror. An
 *   arc-exit RELEASE (the first cut) was rejected in review: it would hand a still-firing
 *   edge-hugging mount to the local tracker, which swings it to rest and the next discharge snaps
 *   it back — the complaint at discharge cadence.</li>
 * </ol>
 * 360° turrets never park (hold expiry still applies). Ticks are the wire's ({@code event.simTick()}
 * at record vs the latest snapshot tick at steer; a pre-snapshot sentinel ages nothing).
 * Residual (engine lane): a turn-rate-0 missile hardpoint released at hold expiry keeps its
 * drifted hull-relative angle (≤ arc/2 off centre) until its next discharge re-pins it — the server's
 * sits at centre; pre-existing under the old clamp, bounded by the small missile arcs.
 *
 * <p><b>Knob</b> {@code cc.predict.sameSideAim} = {@code snap} (default — the missilePose
 * default-ON precedent; knob-flip = rollback) | {@code native} (pre-lane behavior; the A arm of
 * the steering control). Catalog-only ({@code cc.mtrack.every} idiom). Invalid values
 * THROW at class load.
 *
 * <p><b>Meters</b> (cumulative per run, the debrief-bar law; appended to the
 * {@code same-side-hold-meter} line AFTER the grader's four pinned keys — append-safe by the
 * grade-holdmeter regex contract): {@code snap_sets} = per-frame in-arc pin writes;
 * {@code clamped_sets} = per-frame edge parks (the same key as before; the sticky park now);
 * {@code nan_skips} = discharges whose wire aim was NaN; {@code unresolved} = discharges whose
 * mount index resolved no local weapon; {@code presnap_gap_*} = |local currAngle −
 * wire aimFacingDeg| at each record, BEFORE any steer — under {@code native} this is the direct
 * "not tracking cleanly" quantifier; under {@code snap} it reads the server's own inter-discharge
 * slew, NOT zero; a multi-barrel MISSILE rack's gap reads its barrel offset (≤ 7°), not
 * tracking error. {@code release_hold_expiry} / {@code release_far_reentry} = pins released by each
 * law (one per release, not per frame). Gap meters run in BOTH modes (knob-independent metering,
 * the mtrack idiom). Threading: render thread only, like every sibling collaborator.
 */
final class SameSideAimLane {

    /** Validated {@code snap|native} parse; anything else THROWS at class load (knobs.md law). */
    static final String SAME_SIDE_AIM_MODE = parseMode("cc.predict.sameSideAim",
            System.getProperty("cc.predict.sameSideAim", "snap"));
    static final boolean SAME_SIDE_AIM_SNAP = "snap".equals(SAME_SIDE_AIM_MODE);

    private static String parseMode(String knob, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("snap".equals(value) || "native".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException(knob + " must be snap|native, got: " + raw);
    }

    /** Per-run record log cap (the sibling-lane discipline; counters continue past it). */
    private static final int AIM_LOG_MAX = 20;

    /**
     * The engine's target-less turret hold before it returns the mount to rest (15 s);
     * a pin older than this with no newer discharge is released so the
     * native tracker performs that same return.
     */
    static final int HOLD_SECONDS = 15;

    /** A mount within this many degrees of an arc edge counts as PARKED there (float slack). */
    static final float EDGE_EPS_DEG = 0.5f;

    /** {@link #verdict}: write the pinned aim ({@code setCurrAngle(aim)}). */
    static final int ACT_PIN_AIM = 0;
    /** {@link #verdict}: park the mount at the arc's + edge ({@code centre + half}). */
    static final int ACT_PARK_PLUS = 1;
    /** {@link #verdict}: park the mount at the arc's − edge ({@code centre − half}). */
    static final int ACT_PARK_MINUS = 2;
    /** {@link #verdict}: release — the pin is older than the engine's 15 s hold. */
    static final int ACT_RELEASE_HOLD_EXPIRY = 3;
    /** {@link #verdict}: release — the held aim re-entered the arc through the far edge. */
    static final int ACT_RELEASE_FAR_REENTRY = 4;

    /**
     * One pin: the wire discharge aim (world deg), the wire tick it fired at, whether steered yet,
     * and (spec F6 route (b)) the wire entity id + mount index it came from —
     * so a release row can name its mount without a reverse map (exact, O(1), no new allocation).
     */
    static final class Pin {
        final float aim;
        final long tick;
        final long entityId;
        final int mount;
        boolean steered; // false until the first steer frame after the record (the FRESH frame)

        Pin(float aim, long tick, long entityId, int mount) {
            this.aim = aim;
            this.tick = tick;
            this.entityId = entityId;
            this.mount = mount;
        }
    }

    /** {@link #aimReleaseRing} kind codes (index into its kind names). */
    static final int RING_KIND_RELEASE_HOLD_EXPIRY = 0;
    static final int RING_KIND_RELEASE_FAR_REENTRY = 1;

    /**
     * The same-side aim RECORD ring (256 rows, dump 48): every
     * finite-aim discharge record, written BEFORE the {@link #AIM_LOG_MAX} cap check (the hulk-ring
     * precedent: {@link FractureFamilyCoordinator#logPoseAdopt}). WHY: the 20-row run budget is spent in the first seconds of fortress fire,
     * so a late F9 had no row naming the mount (spec F5/F7). Never cleared per round.
     */
    final EntityEventRing aimRecordRing = new EntityEventRing(
            "predict same-side-aim aim-ring", 256, 48, new String[] {"record"},
            "mount", null, null, null, null, "wireAim", "localAim", "gap");

    /**
     * The same-side aim RELEASE ring (64 rows, dump 32): one
     * row per released pin (hold expiry / far re-entry) with its mount, pin tick and the local angle
     * the mount carries on from. WHY: the releases were counter-only ({@link #meterTokens}); no log
     * named WHICH mount a release let go (spec F5/F8).
     */
    final EntityEventRing aimReleaseRing = new EntityEventRing(
            "predict same-side-aim aim-ring", 64, 32,
            new String[] {"release_hold_expiry", "release_far_reentry"},
            "mount", null, null, null, "pin_tick", "localAim", null, null);

    private boolean aimRingDumpFailLogged; // the fail-soft dump wrapper logs once per run

    /** The owning plugin — the sibling-collaborator backref (held for cross-lane reads later). */
    @SuppressWarnings("unused")
    private final PredictReconcileCombatPlugin plugin;

    /**
     * weapon → last wire pin. Weak keys: MRST sweeps the round's bodies (explicit
     * {@link #resetRound} clear), weak refs cover mid-round despawns — the
     * {@code sameSideMountLastRel} idiom.
     */
    private final java.util.Map<WeaponAPI, Pin> lastAim = new java.util.WeakHashMap<>();

    private long snapSets;        // per-frame in-arc pin writes (snap mode only)
    private long clampedSets;     // per-frame edge parks (the park, sticky)
    private long releaseHoldExpiry; // pins released by the 15 s target-less hold
    private long releaseFarReentry; // pins released because the aim re-entered through the far edge
    private int nanSkips;         // same-side discharges carrying NaN aimFacingDeg (unbound read)
    private int unresolved;       // same-side discharges whose mountIndex resolved no weapon
    private long gapCount;        // pre-snap gap samples (one per finite-aim record, BOTH modes)
    private double gapSum;        // sum of |local currAngle − wire aim| degrees, [0,180] each
    private float gapMax;         // max single gap since run start
    private int aimLogLines;      // run-lifetime log budget

    SameSideAimLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * One same-side replay discharge: meter the pre-snap gap and (snap mode) pin the mount's
     * authoritative aim. Caller guards side (owner == sideAuthority()); this method resolves the
     * mount by the wire {@code mountIndex} — the same {@code getAllWeapons()} ordinal contract the
     * presenter uses (both engines load the same variant). Beam mounts are relay-owned: skipped.
     */
    void record(ClientGameplayEdgeEvent event, ShipAPI shooter) {
        List<WeaponAPI> weapons = shooter.getAllWeapons();
        WeaponAPI weapon = weapons != null && event.mountIndex() < weapons.size()
                ? weapons.get(event.mountIndex()) : null;
        if (weapon == null) {
            unresolved++;
            return;
        }
        if (weapon.isBeam()) {
            return; // beam presentation + aim are relay-owned — never contested here
        }
        float aim = event.aimFacingDeg();
        if (Float.isNaN(aim)) {
            nanSkips++; // unbound server read, carried honestly as NaN — never steer from a fake
            return;
        }
        // The direct "not tracking cleanly" quantifier: local mount ray vs the server's discharge
        // aim, sampled BEFORE any steer this frame ([0,180] fold — the slew-meter idiom).
        float gap = Math.abs(((weapon.getCurrAngle() - aim + 540f) % 360f) - 180f);
        gapCount++;
        gapSum += gap;
        if (gap > gapMax) {
            gapMax = gap;
        }
        lastAim.put(weapon, new Pin(aim, event.simTick(), event.entityId(), event.mountIndex()));
        // The ring sees EVERY record, before the capped live line.
        aimRecordRing.record(event.simTick(), 0, event.entityId(), event.mountIndex(),
                Integer.MIN_VALUE, Long.MIN_VALUE, aim, weapon.getCurrAngle(), gap);
        if (aimLogLines < AIM_LOG_MAX) {
            aimLogLines++;
            PilotWatchLog.info("predict same-side-aim record #" + aimLogLines
                    + ": entity=" + event.entityId() + " mount=" + event.mountIndex()
                    + " wireAim=" + fmt1(aim) + " localAim=" + fmt1(weapon.getCurrAngle())
                    + " gap=" + fmt1(gap) + " tick=" + event.simTick() + " mode=" + SAME_SIDE_AIM_MODE
                    + (aimLogLines == AIM_LOG_MAX ? " [aim log cap; counters continue]" : ""));
        }
    }

    /**
     * Per-frame steer for ONE same-side non-beam held weapon (called from the
     * {@code reassertReplayDrivenHold} walk — the held set IS the subject set). No record yet ⇒
     * native-untouched. {@code native} mode ⇒ no-op (records still accumulate for the gap meter).
     * {@code simTick} = the latest snapshot's wire tick (the hold-expiry clock; the pre-snapshot
     * {@code Long.MIN_VALUE} sentinel, or any tick older than the pin, ages nothing).
     */
    void steer(WeaponAPI weapon, long simTick) {
        if (!SAME_SIDE_AIM_SNAP) {
            return;
        }
        Pin pin = lastAim.get(weapon);
        if (pin == null) {
            return;
        }
        long age = simTick < pin.tick ? 0L : simTick - pin.tick;
        float centre = arcCentre(weapon);
        float half = halfArc(weapon);
        float rel = foldRel(pin.aim, centre);
        float curRel = foldRel(weapon.getCurrAngle(), centre);
        int act = verdict(rel, half, curRel, !pin.steered, age, holdTicks());
        pin.steered = true;
        switch (act) {
            case ACT_RELEASE_HOLD_EXPIRY:
            case ACT_RELEASE_FAR_REENTRY:
                // Release — the native tracker owns the mount until the next
                // discharge re-pins. Un-suspend is mandatory here: nothing else clears the sticky
                // flag on a live replica (the RemoteAimLane / OwnMountLifecycleLane
                // idiom). No angle write on the release frame (hull-relative store —
                // the mount carries on from where it was).
                lastAim.remove(weapon);
                weapon.setSuspendAutomaticTurning(false);
                if (act == ACT_RELEASE_HOLD_EXPIRY) {
                    releaseHoldExpiry++;
                } else {
                    releaseFarReentry++;
                }
                // One ring row per release (tick = the steer's wire tick;
                // pin_tick raw so age-at-release = tick - pin_tick; the pre-snapshot sentinel
                // ages nothing so it never expires a hold — a far re-entry on it records
                // Long.MIN_VALUE, outside every windowed dump).
                aimReleaseRing.record(simTick, act == ACT_RELEASE_HOLD_EXPIRY
                        ? RING_KIND_RELEASE_HOLD_EXPIRY : RING_KIND_RELEASE_FAR_REENTRY,
                        pin.entityId, pin.mount, Integer.MIN_VALUE, pin.tick,
                        weapon.getCurrAngle(), Float.NaN, Float.NaN);
                return;
            case ACT_PARK_PLUS:
                weapon.setSuspendAutomaticTurning(true);
                weapon.setCurrAngle(centre + half);
                clampedSets++;
                return;
            case ACT_PARK_MINUS:
                weapon.setSuspendAutomaticTurning(true);
                weapon.setCurrAngle(centre - half);
                clampedSets++;
                return;
            default:
                weapon.setSuspendAutomaticTurning(true);
                weapon.setCurrAngle(pin.aim);
                snapSets++;
                return;
        }
    }

    /**
     * The pure hold law (smoke: {@code SameSideAimReleaseSmoke}). {@code rel} = the pinned world
     * aim relative to the arc centre, folded to [−180, 180); {@code half} = arc/2 (≥ 180 for a
     * 360° turret — never parks); {@code curRel} = the mount's CURRENT angle relative to the
     * centre (same fold); {@code fresh} = the first steer frame after the record;
     * {@code ageTicks} = now − the pin's discharge tick. Hold expiry is judged first (the engine's
     * own law); an edge-exact aim ({@code |rel| == half}) is inside, matching the engine's
     * inclusive clamp.
     */
    static int verdict(float rel, float half, float curRel, boolean fresh, long ageTicks,
            long holdTicks) {
        if (ageTicks >= holdTicks) {
            return ACT_RELEASE_HOLD_EXPIRY;
        }
        if (half >= 180f) {
            return ACT_PIN_AIM;
        }
        boolean inside = rel >= -half && rel <= half;
        if (fresh) {
            return inside ? ACT_PIN_AIM : (rel >= 0f ? ACT_PARK_PLUS : ACT_PARK_MINUS);
        }
        if (inside) {
            boolean parkedPlus = curRel >= half - EDGE_EPS_DEG;
            boolean parkedMinus = curRel <= -half + EDGE_EPS_DEG;
            if ((parkedPlus && rel < 0f) || (parkedMinus && rel > 0f)) {
                return ACT_RELEASE_FAR_REENTRY; // came around behind: never jump across the arc
            }
            return ACT_PIN_AIM;
        }
        return curRel >= 0f ? ACT_PARK_PLUS : ACT_PARK_MINUS; // sticky: the mount's own side
    }

    /** {@link #HOLD_SECONDS} in wire ticks at the seeded server rate (read per call: the WELCOME seeds it). */
    static long holdTicks() {
        return (long) HOLD_SECONDS * ServerTickRate.ticksPerSecond();
    }

    /** {@code angle − centre} folded to [−180, 180) — the arc-relative bearing (pure; smoke-covered). */
    static float foldRel(float angleDeg, float centreDeg) {
        return ((angleDeg - centreDeg) % 360f + 540f) % 360f - 180f;
    }

    /** The mount's arc centre in world degrees ({@code facing + arcFacing}; a shipless mount reads facing 0). */
    private static float arcCentre(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        return (ship != null ? ship.getFacing() : 0f) + weapon.getArcFacing();
    }

    /** Half the mount's arc; a shipless mount reads as a full turret (never parks). */
    private static float halfArc(WeaponAPI weapon) {
        return weapon.getShip() == null ? 180f : weapon.getArc() * 0.5f;
    }

    /**
     * This lane's fragment of the {@code same-side-hold-meter} line — MUST stay appended after the
     * grader's four pinned keys ({@code grade-holdmeter.ps1} matches that prefix unanchored, so
     * trailing keys ride free; inserting between them breaks the match to NODATA).
     */
    String meterTokens() {
        double mean = gapCount > 0 ? gapSum / gapCount : 0d;
        return " same_side_aim_mode=" + SAME_SIDE_AIM_MODE
                + " same_side_aim_snap_sets=" + snapSets
                + " same_side_aim_nan_skips=" + nanSkips
                + " same_side_aim_unresolved=" + unresolved
                + " same_side_aim_presnap_gap_count=" + gapCount
                + " same_side_aim_presnap_gap_mean=" + fmt1((float) mean)
                + " same_side_aim_presnap_gap_max=" + fmt1(gapMax)
                + " same_side_aim_clamped_sets=" + clampedSets
                + " same_side_aim_release_hold_expiry=" + releaseHoldExpiry
                + " same_side_aim_release_far_reentry=" + releaseFarReentry;
    }

    /**
     * Flush both aim rings as
     * {@code predict same-side-aim aim-ring [where] marker #N} rows (record ring, then release ring).
     * Called by the PRCP F9 owner-marker hook and at teardown, the SAME two seams as
     * {@link FractureFamilyCoordinator#dumpAdoptRing} (wrapper copied from it). WHY: a late
     * F9 must name which mount recorded / released after the 20-row live cap ran out (spec F9).
     * Never throws: a failure logs ONCE per run.
     */
    void dumpRings(String where, long markerNo, long simTick) {
        try {
            for (String row : aimRecordRing.dumpRows(where, markerNo, simTick)) {
                PilotWatchLog.info(row);
            }
            for (String row : aimReleaseRing.dumpRows(where, markerNo, simTick)) {
                PilotWatchLog.info(row);
            }
        } catch (Throwable t) {
            if (!aimRingDumpFailLogged) {
                aimRingDumpFailLogged = true;
                PilotWatchLog.info("predict same-side-aim aim-ring dump FAILED "
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + " (logged once)");
            }
        }
    }

    /** Round boundary: the aim records die with the round's bodies; run-level tallies stay (the
     * aim rings too — run-level forensics). */
    void resetRound() {
        lastAim.clear();
    }

    private static String fmt1(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
