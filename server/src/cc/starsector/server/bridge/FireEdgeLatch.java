package cc.starsector.server.bridge;

import static cc.starsector.server.bridge.PilotApplyState.FIRE_EDGE_MIN_TAP_TICKS;
import static cc.starsector.server.bridge.PilotApplyState.FIRE_EDGE_PRESS_PAD_TICKS;
import static cc.starsector.server.bridge.PilotApplyState.MAX_CREDIT_LOGS;

/**
 * The fireEdgeSeq depth-1 latch of {@link PilotApplyState} (held lane only), in its own file:
 * arm on a wrap-safe seq advance, the press-span clamp, the group-paired discharge
 * release, the expiry flux-gate split, and the five edge meters. WHY a carve: PAS had
 * grown past its 2150 non-blank-line WARN tier, and this is
 * the one block whose state no other class touches directly.
 * <p>Provenance: carved from PAS
 * with every body verbatim. The only edits to the moved text: the
 * two host fields the latch shares are reached through {@code host} ({@code host.lastObservedSimTick},
 * the credit clock the 4-arg {@link #observeFireEdge(int, int, long, boolean)} stamps;
 * {@code host.groupIdentityInverse}, read by {@link #releaseLatchOnDischarge}); {@code lastFireEdgeSeq},
 * {@code latchArmed} and {@code latchSawLostTap} went from {@code private} to package-private (PAS's
 * hull-loss and session-boundary resets write them); the three {@code FIRE_EDGE_*} links in
 * {@link #latchPressing}'s javadoc now name {@link PilotApplyState} (the constants stay there — external
 * rows read them); {@code fireEdgeLatchWindowTicks} is now this constructor's argument (PAS passes the
 * same {@code Math.max(1, declaredFireTimeoutTicks / 2)} it used to assign). PAS keeps a same-signature
 * delegate for every public member, so every caller reaches this code unchanged.
 * <p>Built as the LAST statement of PAS's constructor; this constructor only stores its arguments (it
 * never calls into {@code host}). Single-threaded per-ship sim state like PAS: no {@code synchronized},
 * no {@code volatile}, no mutable static in the moved block.
 */
final class FireEdgeLatch {

    private final PilotApplyState host;
    private final int fireEdgeLatchWindowTicks;
    // The fireEdgeSeq depth-1 latch — held-lane-only state.
    private boolean fireEdgeLatchEnabled = true; // cc.predict.fireEdgeLatch (default true)
    int lastFireEdgeSeq = -1; // -1 = no frame observed yet (first frame inits, never arms)
    boolean latchArmed;
    private long latchExpiryTick;
    private int latchGroup = -1; // frame's selectedWeaponGroup at arm (discharge release pairing)
    boolean latchSawLostTap; // >=1 pressing tick had fireHeld==false (the tap held lost)
    private int latchPressBudget; // Clamp: fireHeld=false pressing ticks left before drop
    private int edgeSwitchDisarmed; // Latches disarmed by a selected-group switch
    private int edgeRecovered;
    private int edgeExpired;
    private int edgeClampDropped; // Latches dropped by the press-span clamp (faithful drop)
    // The fire-edge expiry flux-gate split.
    private long latchArmedAtTick; // simTick of the FRESH arm (a re-arm extends the window, keeps it)
    private int latchFluxGate; // 0 none / 1 vent / 2 overload; sticky across the armed window
    private int latchFluxGateLast; // The LAST raw read (not sticky) = the row's gate_at_lapse
    private boolean latchGateReadDue; // an arm/re-arm owes the caller ONE flux read (never per tick)
    private long latchWireId; // the wire id the caller's gate read named (the expiry row's identity)
    private int edgeExpiredGated; // the edge_expired subset whose window saw a vent/overload
    private int edgeExpiryLogsEmitted; // pilot-fire-edge expired rows (capped at MAX_CREDIT_LOGS)

    FireEdgeLatch(PilotApplyState host, int fireEdgeLatchWindowTicks) {
        this.host = host;
        this.fireEdgeLatchWindowTicks = fireEdgeLatchWindowTicks;
    }

    /**
     * Enable/disable the fireEdgeSeq depth-1 latch ({@code cc.predict.fireEdgeLatch},
     * default true; active only under {@code fireLane=held}). Disabled = the seq is still
     * tracked (so a later enable never arms off a stale delta) but the latch never arms.
     */
    public void fireEdgeLatch(boolean enabled) {
        this.fireEdgeLatchEnabled = enabled;
    }

    /**
     * The fireEdgeSeq observe (held lane, every applied frame): arm the depth-1 latch on a
     * wrap-safe advance ({@code !=} compare — 255→0 is an advance, never {@code >}). The FIRST
     * observed frame initializes the memory and never arms (mid-match join safety). Re-arm while
     * armed just resets the expiry window — depth-1: deltas collapse, nothing queues. The frame's
     * {@code selectedWeaponGroup} is captured at arm for the discharge release pairing.
     *
     * <p>A frame whose {@code selectedWeaponGroup} left the armed
     * {@code latchGroup} DISARMS the latch first (counts {@code edge_switch_disarmed}) — the
     * group-blind press would otherwise fire the hull's newly-selected group (a shot the
     * client's engine never emitted from that tap) with no SELECT_GROUP, and the group-paired
     * {@link #releaseLatchOnDischarge} could never clear it. A same-frame seq advance then
     * re-arms below, keyed to the NEW group (re-key). The compare is total: a frame's
     * {@code selectedWeaponGroup} is {@code >= 0} or DESELECTED (−2) by the frame ctor guard,
     * never the producers' internal −1, so {@code !=} is a real switch, not an unknown.
     */
    public void observeFireEdge(int fireEdgeSeq, int selectedWeaponGroup, long simTick) {
        observeFireEdge(fireEdgeSeq, selectedWeaponGroup, simTick, true);
    }

    /**
     * The tap-latch exclusion overload ({@code armAllowed=false}): while a class-S group is
     * selected under routing, PDCL rides reliable transport and IS the tap carrier — the
     * clamp machinery stays class-H-only, so the latch must never arm there. The seq is still
     * tracked (and a group switch still disarms) so a later class-H selection never arms off
     * a stale delta. It also records the sim clock the continuation credit
     * ages against.
     */
    public void observeFireEdge(int fireEdgeSeq, int selectedWeaponGroup, long simTick,
            boolean armAllowed) {
        host.lastObservedSimTick = simTick; // the continuation-credit clock (every non-pending applied frame)
        if (latchArmed && selectedWeaponGroup != latchGroup) {
            latchArmed = false;
            latchSawLostTap = false;
            edgeSwitchDisarmed++;
        }
        if (lastFireEdgeSeq < 0) {
            lastFireEdgeSeq = fireEdgeSeq;
            return;
        }
        if (fireEdgeSeq == lastFireEdgeSeq) {
            return;
        }
        lastFireEdgeSeq = fireEdgeSeq;
        if (!fireEdgeLatchEnabled || !armAllowed) {
            return; // knob off / class-S exclusion: track the seq, never arm
        }
        latchGateReadDue = true; // Both paths below arm — one gate read due
        if (latchArmed) {
            latchExpiryTick = simTick + fireEdgeLatchWindowTicks;
            latchPressBudget = FIRE_EDGE_MIN_TAP_TICKS; // A fresh tap = a fresh span
            return;
        }
        latchArmed = true;
        latchArmedAtTick = simTick;
        latchFluxGate = 0;
        latchFluxGateLast = 0;
        latchExpiryTick = simTick + fireEdgeLatchWindowTicks;
        latchGroup = selectedWeaponGroup;
        latchSawLostTap = false;
        latchPressBudget = FIRE_EDGE_MIN_TAP_TICKS;
    }

    /**
     * Whether the caller owes ONE flux-gate read this tick — true on an arming
     * tick (fresh or re-arm) and on the expiry tick ONLY, never every armed tick (the
     * engine read does not ride the frame loop). Pure state.
     */
    public boolean latchGateReadDue(long simTick) {
        return latchArmed && (latchGateReadDue || simTick >= latchExpiryTick);
    }

    /** Record a gate read (clears the due flag); a non-zero gate stays sticky. */
    public void noteLatchFluxGate(int gate, long wireId) {
        latchGateReadDue = false;
        latchWireId = wireId;
        latchFluxGateLast = gate;
        latchFluxGate = gate != 0 ? gate : latchFluxGate; // sticky: a later 0 never clears a seen gate
    }

    /**
     * Whether the latch is pressing FIRE this tick (held lane: the press decision becomes
     * {@code fireHeld || latchPressing(...)}). Owns the bounded window: past expiry the latch
     * disarms and counts {@code edge_expired}. A pressing tick whose {@code fireHeld} is false
     * marks the latch as having recovered a tap the held stream lost ({@code edge_recovered}
     * on discharge release).
     *
     * <p>The press-span clamp: the latch replays the
     * wire-observed press SPAN instead of pressing until discharge. The pre-clamp law converted
     * a tap into a sustained press that outlived the weapon's cooldown — the server fired
     * cooldown-blocked taps the client's engine dropped (vanilla drops them:
     * {@link PilotApplyState#FIRE_EDGE_MIN_TAP_TICKS} evidence), and by bridging the gap between two adjacent
     * empty taps it merged their release edges, walking the engine's ALTERNATING next-tube
     * pointer one advance behind the client's (the pointer advances on trigger
     * RELEASE, so press spans and their releases must match
     * the client's). A {@code fireHeld=true} tick means the held stream owns the press — the
     * latch keeps only {@link PilotApplyState#FIRE_EDGE_PRESS_PAD_TICKS} past the observed release; a never-
     * visible press keeps the {@link PilotApplyState#FIRE_EDGE_MIN_TAP_TICKS} synthetic span. Budget exhausted
     * with no discharge = the faithful drop, counted {@code edge_clamp_dropped}.
     */
    public boolean latchPressing(boolean fireHeld, long simTick) {
        if (!latchArmed) {
            return false;
        }
        if (simTick >= latchExpiryTick) {
            latchArmed = false;
            latchSawLostTap = false;
            edgeExpired++;
            // The gated subset (a vent/overload inside the
            // window blocked the press — 213444 r1) + ONE capped row per lapse; edge_expired stays TOTAL.
            if (latchFluxGate != 0) {
                edgeExpiredGated++;
            }
            if (edgeExpiryLogsEmitted < MAX_CREDIT_LOGS) {
                edgeExpiryLogsEmitted++;
                System.out.println("pilot-fire-edge expired wire_id=" + latchWireId + " tick=" + simTick + " armed_at="
                        + latchArmedAtTick + " group=" + latchGroup + " held=" + (fireHeld ? 1 : 0) + " gate="
                        + (latchFluxGate == 2 ? "overload" : latchFluxGate == 1 ? "vent" : "none")
                        + " gate_at_lapse=" + (latchFluxGateLast == 2 ? "overload" : latchFluxGateLast == 1 ? "vent" : "none")
                        + (edgeExpiryLogsEmitted == MAX_CREDIT_LOGS ? " (log capped; counters continue)" : ""));
            }
            return false;
        }
        if (fireHeld) {
            latchPressBudget = FIRE_EDGE_PRESS_PAD_TICKS;
            return true;
        }
        if (latchPressBudget <= 0) {
            latchArmed = false;
            latchSawLostTap = false;
            edgeClampDropped++;
            return false;
        }
        latchPressBudget--;
        latchSawLostTap = true;
        return true;
    }

    /**
     * The latch release on an observed own-ship discharge, paired to the latch group:
     * a resolvable discharge from a DIFFERENT group (an autofire group) never
     * releases the latch; either ordinal -1 (legacy/unbound seams/unresolvable) = release-on-any.
     */
    public void releaseLatchOnDischarge(int dischargeGroupOrdinal) {
        if (!latchArmed) {
            return;
        }
        // a wire-ordinal seam:
        // latchGroup is CLIENT-space (captured from the frame) while the discharge ordinal
        // is SERVER-space (groupOrdinalForMount). Translate server→client through the
        // identity map's inverse before pairing; untranslatable (-1) = release-on-any, the
        // ordinal-unresolvable law one line down. Identity map = byte-identical compare.
        if (dischargeGroupOrdinal >= 0 && host.groupIdentityInverse != null) {
            dischargeGroupOrdinal = dischargeGroupOrdinal < host.groupIdentityInverse.length
                    ? host.groupIdentityInverse[dischargeGroupOrdinal] : -1;
        }
        if (latchGroup >= 0 && dischargeGroupOrdinal >= 0 && dischargeGroupOrdinal != latchGroup) {
            return;
        }
        latchArmed = false;
        if (latchSawLostTap) {
            edgeRecovered++;
        }
        latchSawLostTap = false;
    }

    /** Whether the fireEdgeSeq latch is currently armed (contract surface). */
    public boolean fireEdgeLatchArmed() {
        return latchArmed;
    }

    /** Meter: latches released by a discharge after ≥1 pressing tick with fireHeld=false. */
    public int edgeRecovered() {
        return edgeRecovered;
    }

    /** Meter: latches whose bounded window lapsed with no releasing discharge. */
    public int edgeExpired() {
        return edgeExpired;
    }

    /** Meter: the {@link #edgeExpired()} subset whose window saw a vent/overload. */
    public int edgeExpiredGated() {
        return edgeExpiredGated;
    }

    /** Meter: latches dropped by the press-span clamp (the faithful vanilla drop). */
    public int edgeClampDropped() {
        return edgeClampDropped;
    }

    /** Meter: latches disarmed by a selected-group switch inside the window. */
    public int edgeSwitchDisarmed() {
        return edgeSwitchDisarmed;
    }
}
