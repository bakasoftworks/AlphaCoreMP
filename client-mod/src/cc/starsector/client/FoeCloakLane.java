package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * The foe PHASE-CLOAK reconcile lane — the per-twin cloak edge law ({@link #maintainFoeCloak}:
 * bit-owned edges, keyframe repair, the retry net, the held re-press) plus the overload-lead
 * memory the flux-flags lane shares with it ({@link #maintainLocalOverloadLead} — the
 * {@code lead}/{@code wait} wire knob). Owns the per-twin {@link CloakEdgeMemory}.
 *
 * <p><b>Provenance.</b> Carved VERBATIM out of {@link FoeStateLaneCoordinator}
 * (the FSLC carve row, trigger fired
 * at 2370 lines after the landing). Zero policy change: bodies moved byte-for-byte with only
 * {@code FoeStateLaneCoordinator.}/{@code plugin.} receiver qualifications and javadoc
 * requalifications; {@code cloakMemoryFor} + {@code maintainLocalOverloadLead} went
 * {@code private}→package for the ONE caller that stayed behind (the flux-flags lane's
 * overload-edge bookkeeping in {@link FoeStateLaneCoordinator#maintainFoeFluxFlags}, which
 * marks the wire-begun / wire-confirmed / local-lead transitions on the same memory).
 * {@code FoeCloakEdgeLawSmoke} drives every seam here through {@code plugin.foeState.cloak}.
 *
 * <p><b>ORDER RISK (inherited — do not "clean up").</b> The lane's CALL POSITION is unchanged in
 * both per-entity orderings: the 1v1 chain in the plugin's {@code advance()} (…Shield → Cloak)
 * and the registry walk in {@link FoeStateLaneCoordinator#maintainRemoteEntities} (…Shield →
 * Cloak → hull). The flux-flags lane runs BEFORE this lane on both paths — the branch B
 * restore ({@link #restoreCloakToWire}) lands on the same frame, ahead of the cloak edge law.
 *
 * <p><b>What stays plugin-side (reached via {@code plugin.}):</b> the knob statics
 * ({@code FOE_CLOAK_*}, {@code FOE_FLUX_APPLY}), the meter counters ({@code foeCloakSeeds},
 * {@code foeCloakApplyToggles}, {@code foeCloakBitEdgeApplies}, {@code foeCloakHeldRetries},
 * {@code foeCloakSeedPhaseGateSkips}, {@code foeFluxFlagsOverloadStops}) and the two log sinks
 * ({@code foeCloakLane}, {@code foeFluxFlagsLane}); {@code seedProgressFor} +
 * {@code systemStateForCode} stay where their other callers are.
 *
 * <p>Fresh-file growth watch: ONE concern (the cloak edge law + its overload-lead memory);
 * re-adjudicate at the {@code 30-warn-tiers.ps1} WarnNb.
 */
final class FoeCloakLane {

    private final PredictReconcileCombatPlugin plugin;

    FoeCloakLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Remedy A: per-twin memory for {@link #maintainFoeCloak} — the phased bit the
     *  PRIOR fresh frame carried (edge detection) + the last cloak-tier assert tick seen (keyframe
     *  freshness). Weak-keyed on the replica: a new round's / re-field's ships mint fresh memories,
     *  no reset plumbing (the {@code ArcHitFxLane.phasedLastFresh} precedent). */
    static final class CloakEdgeMemory {
        int lastBit = -1;                                            // -1 unseen, 0 false, 1 true
        long lastTierTick = ServerEntityPose.UNKNOWN_CLOAK_TIER_TICK;
        /** The tick a TOGGLE was pressed from IDLE (bit edge or held re-press)
         * and not yet seen ON — the retry net's clock; -1 = nothing pending. */
        long pendingOnTick = -1L;
        /** The twin state the last {@code SKIP held} row named — one row per
         *  state per un-phased episode; null once the twin is on again. */
        ShipSystemAPI.SystemState heldSkipState;
        /** The tick the twin's tracker first read overloaded WITHOUT the wire
         *  flag (a locally-begun overload — the owner's own system executed ahead of the wire); -1 = none. */
        long localOvlRiseTick = -1L;
        /** The twin's current overload has been seen with the wire flag up (wire-begun,
         *  or a local rise the wire confirmed) — its end is a plain wire end, never the lead law. */
        boolean ovlWireConfirmed;
        /** The replica's tracker has read VENTING at least once inside the
         *  current wire vent window — a later idle frame under the raised flag is the drained tail
         *  ({@link FoeVentWindowLaw}), never re-begun; cleared the frame the wire flag reads down. */
        boolean ventWindowStarted;
        /** The current window's first suppressed tail frame has been
         *  logged (one sizing row per window: the pinned flux the tail began at); cleared with the window. */
        boolean ventTailLogged;
        /** The tracker has read
         *  OVERLOADED at least once inside the current wire overload window — a later idle frame
         *  under the raised flag is the expired tail ({@link OverloadWindowLaw}), never re-begun;
         *  cleared the frame the wire flag reads down. Distinct from {@link #ovlWireConfirmed}. */
        boolean ovlBegunThisWindow;
        /** The twin cloak's local state
         *  TRANSITION ring for {@link CloakRepairLaw} — chronological, oldest first, state
         *  ordinals; written only when the state changes (a compare on a steady frame). */
        final int[] cloakRingStates = new int[CloakRepairLaw.RING_CAP];
        /** The entry ticks paired with {@link #cloakRingStates}. */
        final long[] cloakRingTicks = new long[CloakRepairLaw.RING_CAP];
        /** Live entries in the ring. */
        int cloakRingCount;
    }

    /** Fresh keyframes whose differing tier
     *  state the twin HELD inside {@link CloakRepairLaw#LAG_WINDOW_TICKS} — the wire behind the
     *  local engine across a tier boundary, the rewind SKIPPED ({@link CloakRepairLaw#repairDue}).
     *  Export token (main wires it beside {@code predict_foe_cloak_seeds}):
     *  {@code predict_foe_cloak_kf_repair_lag_skips}. */
    long cloakKfRepairLagSkips;
    private final java.util.Map<ShipAPI, CloakEdgeMemory> cloakEdgeMemory =
            new java.util.WeakHashMap<ShipAPI, CloakEdgeMemory>();

    /** {@link #cloakAction} verdicts (remedy A) — ONE per fresh frame per twin. */
    static final int CLOAK_ACT_NONE = 0;
    /** Fresh keyframe, twin state differs ⇒ {@code forceState(tier state, tier progress)}. */
    static final int CLOAK_ACT_KF_REPAIR = 1;
    /** Bit rose (server IN start), twin off ⇒ the toggle verb when the twin is IDLE (the engine's own
     *  activation path: sound + FX); a twin NOT idle (COOLDOWN/OUT — the verb is refused there) gets
     *  {@code forceState(IN, 0)} on the edge tick instead, and a press refused FROM idle is forced two
     *  fresh frames later (the server re-phased 31 ticks after its
     *  collision-off while the twin's cooldown still ran; the refused press waited 12 ticks for a
     *  keyframe = 4 client-only hits on a server-phased hull). The IDLE
     *  press is decided by the TWIN's own precondition ({@link #cloakPressLegal}) — a refusal the
     *  engine would give anyway is forced on the edge tick, and a hull pinned within one activation
     *  cost of max gets flux headroom for the press ({@link #giveCloakPressHeadroom}). */
    static final int CLOAK_ACT_BIT_ON = 2;
    /** Bit fell (server collision-off, mid-chargedown), twin still on ⇒ {@code forceState(OUT, 0.5)}. */
    static final int CLOAK_ACT_BIT_OFF_FORCE = 3;
    /** Held tier and bit both ON, twin off ⇒ re-press (a refused press / a locally un-phased twin).
     *  The press is issued only from a LEGAL IDLE twin with no retry pending —
     *  the engine refuses the verb from OUT/COOLDOWN, and a re-press beside the retry net's force
     *  drains against the forced IN and un-phases it. */
    static final int CLOAK_ACT_HELD_ON = 4;
    /** Held tier and bit both OFF, twin on ⇒ re-press. */
    static final int CLOAK_ACT_HELD_OFF = 5;

    /**
     * The remedy-A decision table, pure and game-free ({@code FoeCloakEdgeLawSmoke}
     * drives it through a recorded flight's reel). Precedence: a FRESH keyframe whose state differs
     * from the twin's; else a bit EDGE; else a HELD tier that AGREES with the bit (the retry net).
     * A held tier that DISAGREES with the bit never acts — that disagreement is exactly the 1–15
     * tick keyframe lag the bit already edged (a held IDLE after a rising edge must not revert the
     * IN the bit applied) or the OUT chargedown window (bit true ~15 ticks past a keyframe OUT) —
     * the strobe class this lane must never enter.
     *
     * @param priorBit the phased bit the PRIOR fresh frame carried: -1 unseen, 0 false, 1 true
     */
    static int cloakAction(boolean tierFresh, boolean tierStateDiffers, boolean tierUsable,
            boolean heldOn, boolean bitCarried, boolean bit, int priorBit, boolean localOn) {
        if (tierFresh && tierStateDiffers) {
            return CLOAK_ACT_KF_REPAIR;
        }
        boolean edge = bitCarried && priorBit >= 0 && (priorBit == 1) != bit;
        if (edge) {
            if (bit && !localOn) {
                return CLOAK_ACT_BIT_ON;
            }
            return !bit && localOn ? CLOAK_ACT_BIT_OFF_FORCE : CLOAK_ACT_NONE;
        }
        if (bitCarried && tierUsable) {
            if (bit && heldOn && !localOn) {
                return CLOAK_ACT_HELD_ON;
            }
            if (!bit && !heldOn && localOn) {
                return CLOAK_ACT_HELD_OFF;
            }
        }
        return CLOAK_ACT_NONE;
    }

    /**
     * K23 relay (the plugin's {@code finishMatchReset} → {@link FoeStateLaneCoordinator#resetForMatchReset}
     * → here): the bit-edge + keyframe-freshness + overload-lead memory is per-round —
     * weak-keyed, but a reused hull must not carry a stale edge.
     */
    void resetForMatchReset() {
        cloakEdgeMemory.clear();
    }

    /**
     * The foe PHASE-CLOAK reconcile lane (see {@link PredictKnobConfig#FOE_CLOAK_MODE}), the
     * bastion grendel phased-window fix; re-cut as remedy A:
     * the per-tick {@code FLAG_PHASED} bit OWNS the twin's cloak edge, the v10 tier
     * only seeds/repairs on a FRESH keyframe. Runs on FRESH snapshots only. Two authoritative
     * inputs ride the pose, of different freshness:
     * <ul>
     *   <li>{@link ServerEntityPose#isPhasedBit()} — the server's {@code Ship.isPhased()} on the
     *       tick it flipped (the flux-flags byte rides every delta). The engine SETS it at IN start
     *       and CLEARS it mid-chargedown ({@code PhaseCloakStats.apply}: OUT with
     *       {@code effectLevel <= 0.5}; phasecloak down = 0.5 s ⇒ ~15 ticks after OUT start), so
     *       the rising edge IS the server's IN start and the falling edge IS the server's
     *       collision-off instant — never the OUT start (an earlier analysis mis-read the reel there).</li>
     *   <li>the v10 cloak tier ({@link ServerEntityPose#cloakStateCode()} + effect/cooldown/ammo)
     *       — written ONLY on the entity's keyframe (E=15 staggered by id; deltas hold-last), so
     *       1–15 ticks stale between keyframes; {@link ServerEntityPose#cloakTierTick()} changing
     *       = a fresh keyframe landed.</li>
     * </ul>
     * Decision = {@link #cloakAction} (pure; {@code FoeCloakEdgeLawSmoke} drives it through the
     * recorded flight's reel), ONE action per fresh frame — {@code giveCommand} is queued to the ship's
     * next advance, so a second action in the same frame would
     * double-act (the FRONT-shield 27k-toggle strobe precedent;
     * {@link PredictReconcileCombatPlugin#foeCloakApplyToggles} stays the strobe meter):
     * <ol>
     *   <li>FRESH keyframe whose state differs from the twin's ⇒ {@link #repairFoeCloakFromKeyframe}
     *       ({@code forceState(state, progress)} — the engine's own mid-window seam; the OUT case
     *       lands the twin's chargedown at the server's progress, so the twin's own PhaseCloakStats
     *       clears its collision on the server's tick instead of 0.5 s after a late toggle).</li>
     *   <li>else a bit EDGE: rising with the twin off ⇒ the toggle verb (the engine's own legality
     *       — at-cap, overload — gates the resulting {@code fire()}); falling with the twin still on
     *       (the OUT keyframe not landed yet) ⇒ {@code forceState(OUT, 0.5)} = the server's
     *       collision-off instant by construction.</li>
     *   <li>else the retry net: a HELD tier that AGREES with the bit re-presses — a refused press
     *       (twin cooldown / flux) or a twin the local engine un-phased on its own. A held tier that
     *       DISAGREES with the bit never acts (the keyframe-lag window the bit already edged, and
     *       the OUT chargedown window — the strobe class).</li>
     * </ol>
     * Meter (BOTH call sites — the foe-shield precedent: in bastion the grendel is a
     * REGISTRY remote, never the primary foe): COLLISION parity — the twin's {@code isPhased()} vs
     * the carried bit on every fresh frame (one disagree frame per edge = the ≤1-tick residual; the
     * earlier meter compared the held tier and read the keyframe lag as disagreement). Without
     * flux flags it falls back to the tier-vs-{@code isOn()} compare. This lane NEVER consumes
     * GPEE {@code SYSTEM_WINDOW} edges (the server's edge detector keys {@code getSystem()}, owned
     * by {@link FoeStateLaneCoordinator#maintainFoeSystemState}); {@link FoeStateLaneCoordinator#maintainFoeShield} skips phase-cloak hulls — on
     * cloak hulls THIS lane owns the toggle verb. NO vent handling. Fail-soft
     * like every reconcile lane.
     */
    void maintainFoeCloak(ShipAPI foe, ServerEntityPose pose, boolean freshSnapshot,
            long simTick, boolean meterCompare) {
        if (!PredictKnobConfig.FOE_CLOAK_METER || !freshSnapshot || foe == null || pose == null || !foe.isAlive()) {
            return;
        }
        try {
            ShipSystemAPI cloak = foe.getPhaseCloak();
            if (cloak == null) {
                return; // not a phase hull — nothing to reconcile
            }
            CloakEdgeMemory mem = cloakMemoryFor(foe);
            // The transition ring for the
            // kf-repair lag law — the twin's state as this frame found it (before any action);
            // a steady frame is one int compare, no write.
            ShipSystemAPI.SystemState ringNow = cloak.getState();
            if (ringNow != null) {
                mem.cloakRingCount = CloakRepairLaw.record(mem.cloakRingStates, mem.cloakRingTicks,
                        mem.cloakRingCount, ringNow.ordinal(), simTick);
            }
            boolean bitCarried = pose.hasFluxFlags();
            boolean bit = pose.isPhasedBit();
            int priorBit = mem.lastBit;
            if (bitCarried) {
                mem.lastBit = bit ? 1 : 0;
            }
            // (83) CL-1: a tier-less frame is counted, never announced (AWSS stamps are per-frame
            // VALUE-based — a cloak-less frame stamps <v10 legitimately; serve-capability shortfall
            // is confessed ONCE at admission, n1-server-schema-below-client). A carried tier with
            // an unknown future wire code is tolerated the same way — never guess an on/off boundary.
            ShipSystemAPI.SystemState auth = pose.hasPhaseCloakState()
                    ? PredictReconcileCombatPlugin.systemStateForCode(pose.cloakStateCode()) : null;
            boolean tierUsable = auth != null;
            boolean tierFresh = tierUsable && pose.cloakTierTick() != mem.lastTierTick;
            if (tierUsable) {
                mem.lastTierTick = pose.cloakTierTick();
            }
            boolean heldOn = auth == ShipSystemAPI.SystemState.IN
                    || auth == ShipSystemAPI.SystemState.ACTIVE;
            boolean localOn = cloak.isOn();
            if (meterCompare) {
                if (!bitCarried && !tierUsable) {
                    plugin.foeCloakLane.framesMissing++;
                } else {
                    plugin.foeCloakLane.framesCarried++;
                    boolean localPhased = foe.isPhased();
                    if (bitCarried ? localPhased == bit : localOn == heldOn) {
                        plugin.foeCloakLane.agree++;
                    } else {
                        plugin.foeCloakLane.disagree++;
                        plugin.foeCloakLane.log("predict v10 foe-cloak disagree @tick=" + simTick
                                + " entity=" + pose.entityId()
                                + (bitCarried ? ": localPhased=" + localPhased + " bit=" + bit
                                        : ": localOn=" + localOn + " authOn=" + heldOn)
                                + " authState=" + auth + " tierFresh=" + tierFresh
                                + " localState=" + cloak.getState());
                    }
                }
            }
            if (!PredictKnobConfig.FOE_CLOAK_APPLY) {
                return;
            }
            // A diving replica's cloak is the dive
            // playout's forced IN (PhaseDivePresentLane) — no toggle, force or keyframe repair fights it.
            if (plugin.phaseDive.isDiving(foe)) {
                return;
            }
            boolean tierStateDiffers = tierUsable && cloak.getState() != auth;
            // The repair row is gated by
            // CloakRepairLaw — a differing keyframe state the twin HELD inside the lag window is
            // the RTT-old wire behind a local tier boundary; a forceState would rewind (the
            // re-cloak strobe). The GATED boolean rides cloakAction's tierStateDiffers (its only
            // use is the repair row), so cloakAction stays pure + unchanged and a skipped repair
            // falls through to the bit-edge / held rows exactly as an agreeing keyframe would.
            // The disagree meter above is untouched (the divergence stays visible).
            boolean wireSeen = tierFresh && tierStateDiffers
                    && CloakRepairLaw.seenInWindow(mem.cloakRingStates, mem.cloakRingTicks,
                            mem.cloakRingCount, auth.ordinal(), simTick);
            boolean repairDue = CloakRepairLaw.repairDue(tierFresh, tierStateDiffers, wireSeen);
            if (tierFresh && tierStateDiffers && !repairDue) {
                cloakKfRepairLagSkips++;
                plugin.foeCloakLane.log("predict v10 foe-cloak SKIP kf-lag @tick=" + simTick
                        + " entity=" + pose.entityId() + " authState=" + auth
                        + " localState=" + cloak.getState() + " window=" + CloakRepairLaw.LAG_WINDOW_TICKS);
            }
            int action = cloakAction(tierFresh, repairDue, tierUsable, heldOn,
                    bitCarried, bit, priorBit, localOn);
            if (action == CLOAK_ACT_BIT_ON && cloak.getState() != ShipSystemAPI.SystemState.IDLE) {
                // The toggle verb is refused
                // outside IDLE (engine legality) and the old retry net waited for a keyframe — 12
                // ticks of a hittable twin the server had phased. The rising bit IS the server's
                // collision truth (its own legality already passed), so the twin mirrors it by the
                // same forceState the keyframe repair uses; IN progress 0 = this tick's IN start.
                forceCloakIn(cloak, pose, simTick, "bit", 0f);
                mem.pendingOnTick = -1L;
                action = CLOAK_ACT_NONE;
            } else if (action == CLOAK_ACT_BIT_ON && !cloakPressLegal(foe, cloak)) {
                // The IDLE press
                // is decided by the TWIN's own precondition, never the keyframe's authState (stale at
                // the edge — both refused rises logged authState=IDLE and waited 1–2 ticks for the
                // retry net). A refusal headroom cannot lift (engine disabled, zero-flux-boost rule,
                // out of ammo, overloaded, venting) gets the same edge-tick force as the non-IDLE
                // twin; the retry net stays as the backstop behind both.
                forceCloakIn(cloak, pose, simTick, "bit-illegal", 0f);
                mem.pendingOnTick = -1L;
                action = CLOAK_ACT_NONE;
            } else if (action == CLOAK_ACT_HELD_ON && mem.pendingOnTick >= 0L) {
                // A press from IDLE is already pending —
                // the retry net owns it (one press, then forceState two fresh frames later). A held
                // re-press here queues a toggle that DRAINS AGAINST the retry's forced IN and un-phases
                // the twin under a true bit (the verb from IN ⇒ OUT).
                action = CLOAK_ACT_NONE;
            } else if (action == CLOAK_ACT_HELD_ON
                    && (cloak.getState() != ShipSystemAPI.SystemState.IDLE || !cloakPressLegal(foe, cloak))) {
                // The engine refuses the verb from OUT (cooldown > 0) and COOLDOWN
                // outright and an illegal IDLE press the same way — the re-press was
                // a per-frame no-op (5-7 `src=held` rows per kicked twin on both seats). Nothing
                // is forced either: a twin the engine un-phased on its own (a pinned overload, a
                // cap-refused upkeep) is obeying the law the server's ship obeys; the rising-EDGE law
                // covers a cooling twin, and the held press lands the frame the twin is IDLE and legal.
                if (mem.heldSkipState != cloak.getState()) {
                    mem.heldSkipState = cloak.getState();
                    plugin.foeCloakLane.log("predict v10 foe-cloak SKIP held @tick=" + simTick
                            + " entity=" + pose.entityId() + " localState=" + cloak.getState()
                            + " legal=" + cloakPressLegal(foe, cloak) + " authState=" + auth);
                }
                action = CLOAK_ACT_NONE;
            }
            if (localOn) {
                mem.heldSkipState = null;
            }
            String pressNote = "";
            switch (action) {
                case CLOAK_ACT_KF_REPAIR:
                    repairFoeCloakFromKeyframe(foe, cloak, pose, auth, simTick);
                    mem.pendingOnTick = -1L;
                    break;
                case CLOAK_ACT_BIT_ON:
                case CLOAK_ACT_HELD_ON:
                case CLOAK_ACT_HELD_OFF: {
                    Vector2f aim = foe.getMouseTarget();
                    if (aim == null) {
                        aim = foe.getLocation();
                    }
                    if (action != CLOAK_ACT_HELD_OFF) {
                        pressNote = giveCloakPressHeadroom(foe, cloak); // the IDLE press headroom
                    }
                    foe.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, aim, 0);
                    plugin.foeCloakApplyToggles++;
                    if (action == CLOAK_ACT_BIT_ON) {
                        plugin.foeCloakBitEdgeApplies++;
                    } else {
                        plugin.foeCloakHeldRetries++;
                    }
                    if (action != CLOAK_ACT_HELD_OFF) {
                        // the retry net's clock: ANY press from IDLE (bit edge, or a held re-press —
                        // a refused held press is forced after two fresh frames, never re-pressed
                        // every frame)
                        mem.pendingOnTick = simTick;
                        mem.heldSkipState = null;
                    }
                    plugin.foeCloakLane.log("predict v10 foe-cloak APPLY toggle @tick=" + simTick
                            + " entity=" + pose.entityId()
                            + " src=" + (action == CLOAK_ACT_BIT_ON ? "bit" : "held")
                            + " authOn=" + (action != CLOAK_ACT_HELD_OFF)
                            + " bit=" + bit + " authState=" + auth
                            + " localState=" + cloak.getState() + pressNote);
                    break;
                }
                case CLOAK_ACT_BIT_OFF_FORCE: {
                    // The server's collision-off instant: OUT at effectLevel 0.5 = progress 0.5
                    // (seedProgressFor's OUT law, p = 1 - level). The twin's own PhaseCloakStats
                    // clears its collision on its next apply and the last quarter-second of
                    // fade-in plays out from here — the engine's own state machine, nothing painted.
                    ShipSystemAPI.SystemState before = cloak.getState();
                    cloak.forceState(ShipSystemAPI.SystemState.OUT, 0.5f);
                    plugin.foeCloakSeeds++;
                    plugin.foeCloakBitEdgeApplies++;
                    mem.pendingOnTick = -1L;
                    plugin.foeCloakLane.log("predict v10 foe-cloak APPLY force @tick=" + simTick
                            + " entity=" + pose.entityId()
                            + " src=bit forceState(OUT,p=0.5) authState=" + auth
                            + " localStateBefore=" + before);
                    break;
                }
                default:
                    break;
            }
            // The retry net for a press refused FROM IDLE (flux-locked etc.): the toggle
            // is queued to the twin's next advance, so one fresh frame later isOn() tells; two
            // frames on with the bit still up and the twin still off ⇒ force IN with the elapsed
            // progress. Cleared by any force, by the twin coming on, or by the bit falling — the
            // force only ever fires under a TRUE bit, so the OUT-window no-strobe law holds. Never
            // in a frame that PRESSED (bit or held): the queued toggle would drain against the
            // forced IN and toggle it OUT.
            if (mem.pendingOnTick >= 0L && action != CLOAK_ACT_BIT_ON && action != CLOAK_ACT_HELD_ON) {
                if (localOn || (bitCarried && !bit)) {
                    mem.pendingOnTick = -1L;
                } else if (bitCarried && bit && simTick - mem.pendingOnTick >= 2L) {
                    float p = Math.min(1f, (simTick - mem.pendingOnTick) / 30f);
                    forceCloakIn(cloak, pose, simTick, "bit-retry", p);
                    mem.pendingOnTick = -1L;
                }
            }
        } catch (Throwable ignore) {
            // presentation-side meter/apply; never break the watch
        }
    }

    /**
     * The rising-bit FORCE path — the toggle verb cannot land (the twin is
     * not IDLE) or a press from IDLE went unanswered. The bit is the server's collision truth on
     * this tick; the twin mirrors it through the engine's own {@code forceState} (the keyframe
     * repair's verb), and its {@code PhaseCloakStats} paints the phase-in from there.
     */
    private void forceCloakIn(ShipSystemAPI cloak, ServerEntityPose pose, long simTick, String src,
            float progress) {
        ShipSystemAPI.SystemState before = cloak.getState();
        cloak.forceState(ShipSystemAPI.SystemState.IN, progress);
        plugin.foeCloakSeeds++;
        plugin.foeCloakBitEdgeApplies++;
        plugin.foeCloakLane.log("predict v10 foe-cloak APPLY force @tick=" + simTick
                + " entity=" + pose.entityId()
                + " src=" + src + " forceState(IN,p=" + progress + ")"
                + " localStateBefore=" + before);
    }

    /**
     * The engine's OWN precondition for a cloak toggle from IDLE, read on
     * the TWIN — never the keyframe's authState (stale at the edge).
     * From the engine's own toggle path:
     * {@code canBeActivated()}
     * (zero-flux-boost / engine-disabled rules) && not out of ammo && the flux tracker's cap-checked
     * {@code increaseFlux(fluxPerUse)} — refused overloaded, venting, or at
     * {@code curr + fluxPerUse > max}. The at-cap clause is NOT a refusal here: the toggle path lifts
     * it through {@link #giveCloakPressHeadroom}; only a max below the press need itself refuses.
     * A COOLDOWN/OUT twin is not this predicate's business: the caller's {@code != IDLE} branch
     * forces it on the edge (the "cooldown lever").
     */
    static boolean cloakPressLegal(ShipAPI foe, ShipSystemAPI cloak) {
        FluxTrackerAPI ft = foe.getFluxTracker();
        return ft != null && cloak.canBeActivated() && !cloak.isOutOfAmmo()
                && !ft.isOverloadedOrVenting() && ft.getMaxFlux() - cloakPressNeed(cloak) - 1f >= 0f;
    }

    /**
     * The frame time the press headroom
     * budgets for the activation advance's OWN upkeep. In the engine's toggle path the
     * IDLE press charges {@code fluxPerUse} and the SAME call then charges the per-frame
     * upkeep {@code fluxPerSecond × dt}; a cap-refused upkeep drops the tracker to OUT with
     * mirrored progress (≤ 1 frame) whenever {@code P.canBeDeactivated()} holds (no ship/asteroid
     * overlap — refreshed every 50–100 ms — or overloaded/venting). The earlier headroom left ONE flux of
     * room after the cost, so a 320 flux/s Ziggurat (5.3 per 60 Hz frame) was kicked on its
     * activation frame: seat 2 logged IDLE → OUT → COOLDOWN and the retry net's {@code bit-retry}
     * two ticks later on all three presses; seat 1's twin read {@code canBeDeactivated() == false} at
     * those frames and kept the phase. 0.1 s = a 10 fps client's frame (32 flux on the Ziggurat).
     */
    static final float CLOAK_HEADROOM_UPKEEP_SEC = 0.1f;

    /**
     * The LEAD grace — how long the fluxflags lane keeps an overload the
     * local engine began WITHOUT the wire flag before stopping it (the wire confirms inside it). Sized
     * to one-way latency + 2 ticks, capped at 8; a measured test match led by 3 ticks over a
     * VPN. A fixed 6 until a measured one-way estimate reaches the client (the session carries none).
     */
    static final long CLOAK_LEAD_GRACE_TICKS = 6L;

    /** Per-twin cloak + overload memory, minted on first touch (weak-keyed on the replica). */
    CloakEdgeMemory cloakMemoryFor(ShipAPI foe) {
        CloakEdgeMemory mem = cloakEdgeMemory.get(foe);
        if (mem == null) {
            mem = new CloakEdgeMemory();
            cloakEdgeMemory.put(foe, mem);
        }
        return mem;
    }

    /**
     * An overload the LOCAL engine
     * began on the twin WITHOUT the wire flag — the owner's own Acausal Disruptor executes locally
     * ~3 ticks before the server's flag lands, and the engine's own kick has already taken the twin's
     * cloak OUT by the time this lane sees it. The server-settable wire knob
     * {@code WireKnob.FOE_CLOAK_LEAD} picks the branch ({@link ClientWireKnobs#foeCloakWait}):
     * {@code lead} (A, default) keeps the local overload through {@link #CLOAK_LEAD_GRACE_TICKS} so
     * the twin stays coherently overloaded AND decloaked through the lead (the wire confirms inside
     * it — the steady branch in {@link FoeStateLaneCoordinator#maintainFoeFluxFlags}; a lead the server never confirms is
     * stopped at the grace end, the one-overload cost of a wrong local prediction); {@code wait} (B)
     * stops it at once AND forces the cloak back to the wire's state the same frame (the BEGIN
     * branch's engine kick decloaks it when the wire's overload lands). Neither branch delays a
     * decloak past the grace — the standing rule: never add latency; B is an
     * A/B test arm, not a default.
     */
    void maintainLocalOverloadLead(ShipAPI foe, ServerEntityPose pose, FluxTrackerAPI ft,
            CloakEdgeMemory mem, long simTick) {
        boolean wait = ClientWireKnobs.foeCloakWait();
        if (mem.localOvlRiseTick < 0L) {
            mem.localOvlRiseTick = simTick;
            plugin.foeFluxFlagsLane.log("predict foe-fluxflags overload LOCAL-RISE @tick=" + simTick
                    + " entity=" + pose.entityId() + " mode=" + (wait ? "wait" : "lead")
                    + " grace=" + CLOAK_LEAD_GRACE_TICKS);
        }
        long led = simTick - mem.localOvlRiseTick;
        if (!wait && led < CLOAK_LEAD_GRACE_TICKS) {
            return; // branch A: the lead stands until the wire confirms or the grace ends
        }
        ft.stopOverload();
        plugin.foeFluxFlagsOverloadStops++;
        mem.localOvlRiseTick = -1L;
        String note = wait ? restoreCloakToWire(foe, pose, simTick) : " reason=grace-expired";
        plugin.foeFluxFlagsLane.log("predict foe-fluxflags overload LOCAL-STOP @tick=" + simTick
                + " entity=" + pose.entityId() + " mode=" + (wait ? "wait" : "lead")
                + " led=" + led + note);
    }

    /**
     * Branch B: put a twin the local overload kicked OUT back to the wire's cloak state on the
     * same frame — the bit (this frame's collision truth) when carried, else the held tier; nothing
     * when the wire is not phased or the twin is still on. {@link #forceCloakIn} is the engine's own
     * seam (the rising-bit force); progress = the tier's own when it is IN, else a completed IN
     * (ACTIVE on the next advance). Returns the LOCAL-STOP row's note.
     */
    private String restoreCloakToWire(ShipAPI foe, ServerEntityPose pose, long simTick) {
        ShipSystemAPI cloak = foe.getPhaseCloak();
        if (cloak == null || !PredictKnobConfig.FOE_CLOAK_APPLY) {
            return " restored=none";
        }
        // The same dive guard as maintainFoeCloak.
        if (plugin.phaseDive.isDiving(foe)) {
            return " restored=none reason=dive";
        }
        ShipSystemAPI.SystemState auth = pose.hasPhaseCloakState()
                ? PredictReconcileCombatPlugin.systemStateForCode(pose.cloakStateCode()) : null;
        boolean wireOn = pose.hasFluxFlags() ? pose.isPhasedBit()
                : auth == ShipSystemAPI.SystemState.IN || auth == ShipSystemAPI.SystemState.ACTIVE;
        if (!wireOn || cloak.isOn()) {
            return " restored=none cloak=" + cloak.getState() + " wireOn=" + wireOn;
        }
        float progress = auth == ShipSystemAPI.SystemState.IN
                ? FoeStateLaneCoordinator.seedProgressFor(auth, pose.cloakEffectLevel(), pose.cloakCooldownRemaining(), cloak)
                : 1f;
        forceCloakIn(cloak, pose, simTick, "ovl-wait", progress);
        cloakMemoryFor(foe).pendingOnTick = -1L;
        return " restored=IN(p=" + progress + ")";
    }

    /** The flux the engine's activation advance charges: the cost plus one frame's upkeep. */
    static float cloakPressNeed(ShipSystemAPI cloak) {
        return cloak.getFluxPerUse() + Math.max(0f, cloak.getFluxPerSecond()) * CLOAK_HEADROOM_UPKEEP_SEC;
    }

    /**
     * The pinned wire flux on the rise tick ALREADY carries the server's
     * activation cost ({@code maintainFoeFlux} pins before this lane runs), so a hull within one
     * activation cost of max would pay it TWICE and the tracker refuses the press (170228: Ziggurat
     * curr 25824 + 324 > max 26000 at both refused rises; every rise with room pressed clean). Give
     * the tracker headroom for exactly this press — the wire-acked hit idiom, the per-frame
     * pin ({@code maintainFoeFlux}, gated on the SAME {@code FOE_FLUX_APPLY} knob — under
     * {@code foeFlux=meter} the twin's flux is its own and is never pinned at the wire value, so no
     * headroom and no write) restores the wire value next frame — so the engine's own activation
     * path runs (sound + FX) instead of a forceState. The room budgets the activation frame's
     * upkeep too ({@link #cloakPressNeed}). Returns the APPLY-row note ("" when nothing was written).
     */
    private static String giveCloakPressHeadroom(ShipAPI foe, ShipSystemAPI cloak) {
        FluxTrackerAPI ft = foe.getFluxTracker();
        if (ft == null || !PredictKnobConfig.FOE_FLUX_APPLY) {
            return "";
        }
        float cost = cloak.getFluxPerUse();
        float need = cloakPressNeed(cloak);
        float room = ft.getMaxFlux() - need - 1f;
        if (cost <= 0f || room < 0f || ft.getCurrFlux() <= room) {
            return "";
        }
        float preHard = ft.getHardFlux();
        ft.setCurrFlux(room);
        ft.setHardFlux(Math.min(preHard, room));
        return " headroom=" + (int) room + " need=" + (int) cost + " upkeep=" + (int) (need - cost);
    }

    /**
     * The keyframe seed, generalized (was the one-shot {@code seedFoeCloak}):
     * the mid-window PHASE-CLOAK repair on the {@code getPhaseCloak()} slot, the
     * {@link FoeStateLaneCoordinator#seedFoeSystemState} clone. {@link #maintainFoeCloak} calls it on every FRESH keyframe
     * whose tier state differs from the twin's — the first one for an entity IS the old seed (a
     * mid-round joiner / spawn-on-sight bind adopting a mid-phase replica); later ones are a missed
     * or refused edge, or the OUT chargedown the per-tick bit cannot place (the toggle verb cannot
     * reach a mid-window progress). Engine's own public {@code ShipSystemAPI.forceState(state,
     * progress)} + {@code setAmmo} on the cloak object; progress math = {@link FoeStateLaneCoordinator#seedProgressFor}
     * verbatim over the v10 tier's cloak scalars. Rides the SAME {@code cc.predict.foeCloak=apply}
     * opt-in — no extra knob.
     *
     * <p>AT-CAP GATE (D-M2-4): {@code forceState} bypasses engine legality, and force-phasing a
     * replica whose flux the foe-flux lane pins AT CAP would locally re-phase a ship the server
     * just force-unphased. A phase-ON target ({@code IN|ACTIVE}) is therefore additionally gated
     * on the SAME frame's carried collision truth ({@link ServerEntityPose#isPhasedBit()}); a
     * refusal is counted ({@link PredictReconcileCombatPlugin#foeCloakSeedPhaseGateSkips}) and the
     * bit lane reaches the on-state legally at its next edge instead.
     *
     * @return true when a force/ammo write was applied this frame
     */
    boolean repairFoeCloakFromKeyframe(ShipAPI foe, ShipSystemAPI cloak, ServerEntityPose pose,
            ShipSystemAPI.SystemState auth, long simTick) {
        ShipSystemAPI.SystemState before = cloak.getState();
        boolean stateDiffers = before != auth;
        boolean phaseOnTarget = auth == ShipSystemAPI.SystemState.IN
                || auth == ShipSystemAPI.SystemState.ACTIVE;
        FluxTrackerAPI ft = foe != null ? foe.getFluxTracker() : null;
        boolean engineKicks = ft != null && ft.isOverloadedOrVenting();
        if (stateDiffers && phaseOnTarget && (!pose.isPhasedBit() || engineKicks)) {
            // The at-cap gate above: this frame's carried collision truth does not back a
            // phase-ON force (at-cap forced unphase in flight, or flux flags not carried).
            // The kf snap-back class: nor does an
            // overloaded/venting twin — the engine kicks a forced phase-on on its next advance
            // ("nothing is forced against an engine kick", extended from held to keyframe).
            plugin.foeCloakSeedPhaseGateSkips++;
            if (engineKicks) {
                plugin.foeCloakLane.log("predict v10 foe-cloak SKIP kf @tick=" + simTick
                        + " entity=" + pose.entityId() + " authState=" + auth + " localState="
                        + before + " reason=overloaded-or-venting");
            }
            stateDiffers = false;
        }
        int authAmmo = pose.cloakAmmo();
        boolean ammoDiffers = authAmmo >= 0 && authAmmo != cloak.getAmmo();
        float progress = FoeStateLaneCoordinator.seedProgressFor(auth, pose.cloakEffectLevel(),
                pose.cloakCooldownRemaining(), cloak);
        if (stateDiffers) {
            cloak.forceState(auth, progress);
        }
        if (ammoDiffers) {
            cloak.setAmmo(authAmmo);
        }
        if (stateDiffers || ammoDiffers) {
            plugin.foeCloakSeeds++;
            plugin.foeCloakLane.log("predict v10 foe-cloak APPLY force @tick=" + simTick
                    + " entity=" + pose.entityId() + " src=kf"
                    + (stateDiffers ? " forceState(" + auth + ",p=" + progress + ")" : "")
                    + (ammoDiffers ? " setAmmo(" + authAmmo + ")" : "")
                    + " localStateBefore=" + before);
        }
        return stateDiffers || ammoDiffers;
    }
}
