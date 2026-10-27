package cc.starsector.server.runtime;

import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.PilotIntentStep;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.PilotRoundSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * The TWO seams every new server-side down lane touches, carved VERBATIM out of
 * {@link PersistentPilotIntentCombatServer}:
 * the compose-arg concat of the per-tick lane edge lists and the shared
 * round-line meters tail ({@link #roundCounterTail}). Pure reads + list building — no
 * round-end semantics, no state. A new lane adds its list to {@link #concatDownEdges} and
 * its meters to the tail here, never in the serve loop.
 */
final class DownLaneSeams {

    private DownLaneSeams() {
    }

    /**
     * Split edges concat AFTER the merged discharge/window edges at the
     * compose arg ONLY — identity (the SAME list instance) when no lane fired this tick.
     * Wire order every lane landed on: splits, missile ends, autofire-target
     * picks, Paladin dets, arc hits,
     * crit malfunctions.
     */
    static List<GameplayEdgeEvent> concatDownEdges(List<GameplayEdgeEvent> effectiveEdges,
            List<GameplayEdgeEvent> splitEdges, List<GameplayEdgeEvent> missileEndEdges,
            List<GameplayEdgeEvent> afTargetEdges, List<GameplayEdgeEvent> paladinDetEdges,
            List<GameplayEdgeEvent> arcHitEdges,
            List<GameplayEdgeEvent> critEdges) { // critEdges LAST
        if (splitEdges.isEmpty() && missileEndEdges.isEmpty() && afTargetEdges.isEmpty()
                && paladinDetEdges.isEmpty() && arcHitEdges.isEmpty() && critEdges.isEmpty()) {
            return effectiveEdges;
        }
        List<GameplayEdgeEvent> downEdges = new ArrayList<>(
                effectiveEdges.size() + splitEdges.size() + missileEndEdges.size()
                        + afTargetEdges.size() + paladinDetEdges.size() + arcHitEdges.size()
                        + critEdges.size());
        downEdges.addAll(effectiveEdges);
        downEdges.addAll(splitEdges);
        downEdges.addAll(missileEndEdges);
        downEdges.addAll(afTargetEdges);
        downEdges.addAll(paladinDetEdges);
        downEdges.addAll(arcHitEdges);
        downEdges.addAll(critEdges);
        return downEdges;
    }
    /**
     * ONE formatter so the receipt structurally cannot drift a field behind the round line
     * (the append-only grammar rule now has a single home). Pure reads: no round-end
     * semantics live here.
     */
    static String roundCounterTail(
            PilotRoundSummary summary,
            long frameTargetNanos,
            long fanPassMaxNanos,
            long sendSlowestNanos,
            PilotIntentDatagramServer door,
            PilotIntentStep pilotStep,
            GameplayEdgeEventDetector fireLaneDischargeDetector,
            DownEmissionComposer downComposer,
            String peerGrade,
            OnFireShadowComparator onFireShadow,
            OnFireTruthMerger onFireTruthMerger,
            AutofireTargetLane afTargetLane,
            PaladinDetLane paladinDetLane,
            ArcHitServeProbe arcHitProbe,
            int gpeeDownEvents,
            int gpeeMissileEndIntact,
            int gpeeMissileEndDestroyed,
            int gpeeMissileEndFizzling,
            int gpeeMissileEndShield,
            int gpeeMissileEndNamed,
            int edspDownEvents,
            int seatDownFrames,
            long seatRecordsCoalesced,
            int hkfrDownEvents,
            int hkpsDownEvents,
            int hkcgDownEvents,
            int hkcgSkipEvents,
            long hkpsImpulseTriggers,
            long hkpsBurstEmits,
            long hkpsObserveNsMax,
            long hkpsObserveNsAvg,
            int stallFrames,
            int simSpeedChanges,
            int autoSpeedChanges,
            PilotAimFanLane aimFanLane,
            long stagedRefusedUnmounted,
            IntentTakeAge intentTakeAge) {
        return " frames=" + summary.framesServed()
                + " match_over=" + summary.matchOver()
                + " frames_applied=" + summary.framesApplied()
                + " movement_commands=" + summary.movementCommandsIssued()
                + " facing_pins=" + summary.facingPins()
                + " aim_pins=" + summary.aimPins()
                + " system_activations=" + summary.systemActivations()
                + " vents=" + summary.ventsIssued()
                + " fires=" + summary.firesIssued()
                + " fire_decl_received=" + summary.fireDeclReceived()
                + " fire_decl_served=" + summary.fireDeclServed()
                + " fire_decl_served_credit=" + summary.fireDeclServedCredit() // subset of served
                // The credit ledger — minted vs expired-unspent vs capped vs
                // fence-refused, and in-window non-burst unmatched discharges (never minted).
                + " credit_minted=" + summary.fireDeclCreditMinted()
                + " credit_expired=" + summary.fireDeclCreditExpired()
                + " credit_capped=" + summary.fireDeclCreditCapped()
                + " credit_fence_refused=" + summary.fireDeclCreditFenceRefused()
                + " discharge_unmatched_nonburst=" + summary.fireDischargeUnmatchedNonburst()
                + " credit_last_shot=" + summary.fireLastShotCreditsMinted() // last-shot credits minted
                + " fire_decl_expired=" + summary.fireDeclExpired()
                // An UNROUTED expiry = a declaration that
                // entered (or would have entered) the FIFO and the matcher never consumed (timeout /
                // overflow / adopt-reject / hull-loss or re-route flush / lane-held); the class
                // router's intake drops (lane-class + lane-group, both in laneClassDrops) are
                // excluded — flights grade THIS term. Same pilotStep map feeds both sums, so it
                // cannot go negative; clamped at 0 anyway.
                + " fire_decl_expired_unrouted=" + Math.max(0L,
                        (long) summary.fireDeclExpired() - pilotStep.fireClassLane().laneClassDrops())
                // press-gate meters: presses ≈ served post-fix (~1 per serve);
                // waits = ticks a pending head held silent (233236 strobed those, 50 tail shots).
                + " fire_decl_presses=" + summary.fireDeclPresses()
                + " fire_decl_wait_ticks=" + summary.fireDeclWaitTicks()
                // leg-B reasoned-expiry detail (adjudicates the starvation
                // [H] at the next organic watch) + the leg-A held-dwell advance meter.
                + " fire_decl_expiry_detail=" + pilotStep.fireDeclExpiryDetail()
                + " alt_dwell_advances=" + pilotStep.altDwellAdvances()
                + " adopt_serves=" + summary.adoptServes() // OPTION-A lane
                + " adopt_batched=" + summary.adoptBatched() // volley batch
                + " adopt_preadvanced=" + summary.adoptPreadvanced() // chargeup pre-pay writes
                + " adopt_rejects=" + summary.adoptRejects()
                + ("-".equals(summary.adoptRejectReasons()) ? ""
                        : "(" + summary.adoptRejectReasons() + ")")
                + " adopt_capture_miss=" + summary.adoptCaptureMiss()
                // class-routing meters (fire-decl family adjacency, the MISSILE_END meters' precedent):
                // drops = intake filter working as designed (class-H decls under routing);
                // demotes = belt 1+2 mount demotions; reverts = whole-ship routing→held;
                // rebinds = seat_variant_repoint resolver rebinds (fresh caches + trio reset).
                + " fire_class_drops=" + pilotStep.fireClassLane().laneClassDrops()
                + " fire_class_demotes=" + pilotStep.fireClassLane().classDemotes()
                + " fire_class_reverts=" + pilotStep.fireClassLane().routingReverts()
                + " fire_class_rebinds=" + pilotStep.fireClassLane().classResolverRebinds()
                // group-law subset of drops (S mounts of mixed/demoted groups) —
                // appended AFTER rebinds: the door contracts match the drops/demotes/reverts
                // triple as one adjacent string.
                + " fire_class_group_drops=" + pilotStep.fireClassLane().laneGroupDrops()
                + pilotStep.fireClassLane().declPadTokens() // decl_pad_armed/bridged/lapsed/refused_served
                + " gpee_down=" + gpeeDownEvents
                + " edsp_down=" + edspDownEvents
                + " seat_frames=" + seatDownFrames
                // same-tick SEAT records folded latest-wins at the authority drain.
                + " seat_coalesced=" + seatRecordsCoalesced
                + " overrun_frames=" + summary.overrunFrames()
                + " overrun_max_ms="
                + String.format(java.util.Locale.ROOT, "%.2f", summary.overrunMaxMillis())
                // B5 E1: egress meters — fan-pass/send maxes + the endpoint's cumulative
                // swallowed send failures (PilotIntentDatagramServer.sendFailures()).
                + " fan_pass_max_ms="
                + String.format(java.util.Locale.ROOT, "%.2f", fanPassMaxNanos / 1_000_000.0d)
                + " send_max_ms="
                + String.format(java.util.Locale.ROOT, "%.2f", sendSlowestNanos / 1_000_000.0d)
                + " send_failures=" + door.sendFailures()
                // capacity meters (appended fields only — grammar rule):
                // util_pct/work_max_ms per round; bytes_* cumulative endpoint-lifetime
                // door totals (the send_failures= posture — sweeps diff across rounds).
                + " util_pct="
                + String.format(java.util.Locale.ROOT, "%.1f",
                        summary.utilizationPercent(frameTargetNanos))
                + " work_max_ms="
                + String.format(java.util.Locale.ROOT, "%.2f", summary.workMaxMillis())
                + " bytes_sent=" + door.bytesSentTotal()
                + " bytes_recv=" + door.readerCounters().bytesReceivedTotal
                // group/autofire mirror meters (appended fields only).
                + " group_mirrors=" + pilotStep.groupMirrors()
                + " autofire_toggles=" + pilotStep.autofireToggles()
                // GPEE bearing-source meters (appended fields only):
                // fresh = v3 jittered read; composed = silent v2 fallback; unbound = NaN.
                + " gpee_bearing_fresh=" + fireLaneDischargeDetector.bearingFreshCount()
                + " gpee_bearing_composed=" + fireLaneDischargeDetector.bearingComposedCount()
                + " gpee_bearing_unbound=" + fireLaneDischargeDetector.bearingUnboundCount()
                // WIRE-served poll-path
                // MISSILE discharges (covered twins excluded) whose aim carried the
                // birth facing / fell back to the mount — nonzero fallback = a launch lost its offset.
                + " gpee_aim_facing=" + fireLaneDischargeDetector.aimFacingCount()
                + " gpee_aim_fallback=" + fireLaneDischargeDetector.aimFallbackCount()
                // family-adjacent field, the MISSILE_END meters' precedent:
                // mount-tier same-tick double-folds swallowed — nonzero = two live rows
                // shared one wire id inside a single observed tick (the 1759 swap class).
                + " gpee_same_tick_double_fold="
                + fireLaneDischargeDetector.sameTickDoubleFoldCount()
                // appended field: cooldown rises swallowed as reload re-arms —
                // the Missile Autoloader hold's first pin one tick after its setAmmo.
                + " gpee_reload_rearm_suppressed="
                + fireLaneDischargeDetector.reloadRearmSuppressedCount()
                // Covered cooldown-only rises
                // swallowed as burst ends with no spawn (empty slot / disable edge / overload-vent).
                + " gpee_burst_end_suppressed=" + fireLaneDischargeDetector.burstEndSuppressedCount()
                // SYSTEM_JUMP meters (appended fields only): emitted teleport
                // markers + pose-unbound skips (a marker with no dest is never sent).
                + " gpee_jump=" + fireLaneDischargeDetector.jumpEmittedCount()
                + " gpee_jump_pose_unbound=" + fireLaneDischargeDetector.jumpPoseUnboundCount()
                // AUTOFIRE_TARGET lane meters (appended fields only):
                // pick-change emits + keepalive re-emits + unresolvable-target skips.
                + " gpee_af_target=" + afTargetLane.emitCount()
                + " gpee_af_target_keepalive=" + afTargetLane.keepaliveCount()
                + " gpee_af_target_skip_unresolved=" + afTargetLane.skipUnresolvedCount()
                // PALADIN_DET lane meters (appended fields only):
                // fingerprint-matched det emits + whitelist-law counted+skipped news.
                + " paladin_det_emitted=" + paladinDetLane.emitCount()
                + " paladin_det_skip_unknown=" + paladinDetLane.skipUnknownCount()
                // ARC_HIT meters (appended fields only): listener-seam emits,
                // drain-time unresolved ids, non-ship (missile) targets, arcs seen;
                // capture-side null results + TOTAL damage callbacks
                // the sim-thread listener paid (the volume meter for the probe).
                + " arc_hit_emitted=" + arcHitProbe.emitCount()
                + " arc_hit_lash=" + arcHitProbe.lashEmitCount()
                // appended: lash arcs on an overloaded victim — the uncapped L-ARC witness.
                + " arc_hit_lash_ovl=" + arcHitProbe.lashOverloadedCount()
                // appended: projectile-ion arcs the probe flavored ION (5) — the
                // -IonProof rig's uncapped I-METER witness.
                + " arc_hit_ion=" + arcHitProbe.ionEmitCount()
                // appended: the sabot MIRV child's dam-0 twin of the ion arc.
                + " arc_hit_sabot=" + arcHitProbe.sabotEmitCount()
                + " arc_hit_skip_unresolved=" + arcHitProbe.skipUnresolvedCount()
                + " arc_hit_skip_nonship=" + arcHitProbe.skipNonShipCount()
                + " arc_hit_seen=" + arcHitProbe.seenCount()
                + " arc_hit_skip_nullresult=" + arcHitProbe.skipNullResultCount()
                + " arc_hit_callbacks=" + arcHitProbe.callbackCount()
                // v15, appended: the missile last-impact memo (recorded/taken/pruned + unknown).
                + arcHitProbe.missileImpacts().meterTokens()
                // appended: seat-beam-on-missile reports / beam-missile-hit rows
                // printed + capped / open-table overflow / unresolved source / null result / feed faults.
                + arcHitProbe.beamMissileMeterTokens()
                // blind-spot meter (family-adjacent, appended): per-victim arc-dealt
                // hull for the whole flight (top-N `<tgt>:<n>/<hull>`, the rest counted by _more);
                // the per-bucket attribution = the `npeer-serve arc_hit_hist` rows (_rows meter).
                + " arc_hit_by_tgt=" + arcHitProbe.byTargetSummary()
                + " arc_hit_by_tgt_more=" + arcHitProbe.byTargetMore()
                + " arc_hit_hist_rows=" + arcHitProbe.histRowCount()
                // blind-spot meter (appended): the hull-source ledger behind the dmg-taken
                // `src=` fields — hull/armor-touching listener reports, rows attributed / read as
                // script (direct hull write) / read as blind (listener quiet).
                + " hull_src_reports=" + arcHitProbe.hullSources().reportCount()
                + " hull_src_attr=" + arcHitProbe.hullSources().attributedCount()
                + " hull_src_script=" + arcHitProbe.hullSources().unattributedCount()
                + " hull_src_blind=" + arcHitProbe.hullSources().blindRowCount()
                // The shield window's meters ride ONLY while armed
                // (cc.server.shieldedRow) — unarmed round lines stay byte-identical.
                + (arcHitProbe.hullSources().shieldWindowArmed()
                        ? " hull_src_shield=" + arcHitProbe.hullSources().shieldReportCount()
                                + "/" + arcHitProbe.hullSources().shieldedRowCount()
                        : "")
                // hit-cell tap meters, ONLY while armed (cc.server.hitCellRow) — "" unarmed.
                + arcHitProbe.hitCellMeterTokens()
                // MISSILE_END meters (appended fields only): sourceless
                // missile ends by post-mortem cause (intact = clean removeObject — the
                // EOL-mine self-destruct signature; destroyed = shot down hp≤0).
                + " gpee_missile_end_intact=" + gpeeMissileEndIntact
                + " gpee_missile_end_destroyed=" + gpeeMissileEndDestroyed
                + " gpee_missile_end_fizzling=" + gpeeMissileEndFizzling
                // v15, appended: INTACT ends the server saw shield-absorbed (the
                // devour end-hook skips them) + ends naming a victim ship (any cause).
                + " gpee_missile_end_shield=" + gpeeMissileEndShield
                + " gpee_missile_end_named=" + gpeeMissileEndNamed
                // AWSD meters (appended fields only): cumulative
                // per-serve = the send_failures=/bytes_* posture — sweeps diff across rounds.
                + " awsd_kf=" + downComposer.awsdKeyframes()
                + " awsd_delta=" + downComposer.awsdDeltas()
                + " awsd_fallback_ticks=" + downComposer.awsdFallbackTicks()
                // appended field only: content-dictionary entries the AWSD
                // carrier lane actually announced. 0 with live missiles = the tier is being
                // stripped again (the exact blocker: client catalog never populated).
                + " snap_dict_entries=" + downComposer.snapDictEntries()
                // appended field only: over-cap SNAP wire payloads
                // dropped by the never-die guard (steady state 0; nonzero = a lane minted
                // an unbounded frame and the guard saved the serve).
                + " snap_over_cap_dropped=" + downComposer.snapOverCapDropped()
                // SNNK NACK meters (appended fields only): rx = door
                // lifetime valid arrivals; kf = committed out-of-turn re-keys.
                + " awsd_nack_rx=" + door.awsdNacksReceived()
                + " awsd_nack_kf=" + downComposer.awsdNackKeyframes()
                // MARK F9 owner-mark meters (appended fields only): rx = rows
                // printed on this log; drop = inside the per-seat 1 s window (0 for a
                // conforming client — its lane mirrors the window).
                + " mark_rx=" + door.ownerMarksReceived()
                + " mark_rate_drop=" + door.ownerMarksRateDropped()
                // D-FOEARMOR cell meters (appended fields only): committed
                // section bytes / runs / budget-deferred cells — all 0 when cells dark.
                + " awsd_cell_bytes=" + downComposer.awsdCellBytes()
                + " awsd_cell_runs=" + downComposer.awsdCellRuns()
                + " awsd_cell_deferred=" + downComposer.awsdCellsDeferred()
                // sync-risk meter (appended field only): max ticks a
                // wholly-deferred dirty entity waited — the oldest-first starvation tell.
                + " awsd_cell_defer_age_max=" + downComposer.awsdCellDeferAgeMax()
                // carry-cell meters (appended fields only): retreat/target applies +
                // the fireEdgeSeq latch bars.
                + " retreats=" + summary.retreatsIssued()
                + " target_applies=" + summary.targetApplies()
                + " edge_recovered=" + summary.edgeRecovered()
                + " edge_expired=" + summary.edgeExpired()
                // appended fields only: group-switch latch disarms.
                + " edge_switch_disarmed=" + summary.edgeSwitchDisarmed()
                // appended field only: shield bit-10 hold-replay issues.
                + " shield_holds=" + summary.shieldHolds()
                // appended field only: press-span clamp drops.
                + " edge_clamp_dropped=" + summary.edgeClampDropped()
                // appended field only: system cancel-mirror presses.
                + " system_cancel_pressed=" + summary.systemCancelPresses()
                // appended field only: active-tube pointer conforms.
                + " tube_ordinal_conformed=" + summary.tubeOrdinalConformed()
                // appended field only: declared population grade.
                + " peer_grade=" + peerGrade
                // appended fields only: confessing-gun S1 shadow agreement —
                // per-round comparator counters + the sink's JVM-lifetime error counters
                // (the send_failures= cumulative posture; verifier asserts both 0).
                + " onfire_births=" + onFireShadow.births()
                + " onfire_match=" + onFireShadow.matches()
                + " onfire_skew1=" + onFireShadow.skew1()
                + " onfire_cb_only=" + onFireShadow.cbOnly()
                + " onfire_det_only=" + onFireShadow.detOnly()
                + " onfire_unresolved=" + onFireShadow.unresolved()
                + " onfire_unobserved=" + onFireShadow.unobserved()
                + " onfire_cb_errors=" + FireBirthSink.cbErrors()
                + " onfire_deleg_errors=" + FireBirthSink.delegErrors()
                // later amendments — appended-only tail;
                // onfire_delegated (JVM-lifetime, like the error counters) separates
                // "wrapped spec never fired" from "original resolved + chain-called".
                + " onfire_skew1_bf=" + onFireShadow.skew1BirthFirst()
                + " onfire_skew1_df=" + onFireShadow.skew1DetFirst()
                + " onfire_uncovered=" + onFireShadow.uncovered()
                + " onfire_delegated=" + FireBirthSink.delegated()
                // appended field only: HKFR fracture edges fanned.
                + " hkfr_down=" + hkfrDownEvents
                // appended field only: HKPS pose keyframes fanned.
                + " hkps_down=" + hkpsDownEvents
                // appended field only: HKCG cut-geometry records fanned.
                + " hkcg_down=" + hkcgDownEvents
                // truth-source merge (appended fields only): per-round merger
                // counters — steady state src_birth == suppressed.
                + " onfire_src_birth=" + onFireTruthMerger.srcBirth()
                + " onfire_suppressed=" + onFireTruthMerger.suppressed()
                + " onfire_lookup_miss=" + onFireTruthMerger.lookupMiss()
                + " onfire_src_dropped=" + onFireTruthMerger.srcDropped()
                + " onfire_src_uncovered=" + onFireTruthMerger.srcUncovered()
                + " onfire_src_nan_bearing=" + onFireTruthMerger.srcNanBearing()
                // sprint-debt meters (appended fields only): stall→sprint
                // episode census on the live pacer + unrepaid debt at the boundary.
                + " sprint_episodes=" + summary.sprintEpisodes()
                + " sprint_run_max=" + summary.sprintRunMaxFrames()
                + " sprint_wall_max_ms="
                + String.format(java.util.Locale.ROOT, "%.2f", summary.sprintWallMaxMillis())
                + " debt_end_ms="
                + String.format(java.util.Locale.ROOT, "%.2f", summary.debtEndMillis())
                // appended field only: every-other-world divisor skips.
                + " snap_fan_skipped=" + summary.snapFanSkippedTicks()
                // appended field only: cloak bit-11 mismatch-edge toggles.
                + " cloak_toggles=" + summary.cloakToggles()
                // appended fields only: bit-11 toggles withheld under the dive latch
                // + the bit-9 sibling. Both are INFO —
                // a CLEAN dive withholds nothing (a recorded run read 0; > 0 only when the player pressed cloak
                // mid-dive). The flight pin = phased flips across the walk rows inside [dive, DEPARTED] <= 1
                // AND 0 wire OUT ticks of the diving hull, read by rig-grep; no bar.
                + " cloak_dive_freeze_ticks=" + summary.cloakDiveFreezeTicks()
                + " retreat_dive_freeze_ticks=" + summary.retreatDiveFreezeTicks()
                // ray-head ticks waited under the hull's fire lock; reader =
                // the arena rig's R-LOCK INFO row (this exact token); delete at done-when (iii) ×2.
                + " fire_lock_waits=" + summary.fireLockWaitTicks()
                + " fire_lock_read_errors=" + summary.fireLockReadErrors() // Same reader, same delete
                // appended field only: vent mismatch re-presses (vents= stays
                // rising-edge-only — the split keeps the classic meter's meaning).
                + " vent_represses=" + summary.ventRePresses()
                // appended field only: non-edge retreat-flag level re-asserts
                // (bit-9 vs isRetreating disagreement ticks healed; 0 = none of that shape).
                + " retreat_reasserts=" + summary.retreatReasserts()
                // appended field only: held-TRUE bit-9 frames ignored between a
                // hull loss and the first bit-false frame (0 = no stale-held retreat hit a refield).
                + " retreat_rearm_skips=" + summary.retreatRearmSkips()
                // appended field only: TRUE bit-9 frames absorbed inside
                // the post-re-key edge quarantine (0 = no spurious post-transfer retreat level).
                + " retreat_edge_quarantined=" + summary.retreatEdgeQuarantined()
                // appended field only: HKCG capture skips — bare ADDs the
                // client cut locally; per-leg detail = the hulk_cut_geometry_skip INFO rows.
                + " hkcg_skips=" + hkcgSkipEvents
                // appended fields only: confession-lane health — emitter ctor
                // census (JVM-lifetime; post-fix control arithmetic: == covered mounts built,
                // t=0 AI rows INCLUDED) + covered det discharges served WITHOUT an admitted
                // birth twin at T/T-1 (per-round; each = a real shot the confession missed —
                // 0 is the healthy read). Caveat: the ==covered-mounts identity is
                // ROUND-0-ONLY — the counter is cumulative and never resets between rounds,
                // so on a roundsPerSocket>1 socket later round lines repeat/accumulate it;
                // any scripted assert binds round 0 or diffs per-round deltas first.
                + " onfire_instances=" + FireBirthSink.instances()
                + " onfire_det_fallback=" + onFireTruthMerger.detFallback()
                // per-round: no-twin covered discharges refused on a phased shooter
                + " onfire_det_phased=" + onFireTruthMerger.detPhased()
                // appended field only: emitter composed-bearing births
                // (JVM-lifetime, like onfire_births) — the zero-velocity MovingRay class now
                // serves the engine's own composed line instead of NaN; bearing-source
                // visibility so a silent regress back to NaN serves stays diagnosable.
                + " onfire_birth_composed=" + FireBirthSink.birthsComposed()
                // appended field only: ray-class births whose bearing rode the
                // jittered birth facing — the composed count above is now the DEGRADE class
                // (a ray serve that regresses there is the spread-free wire coming back).
                + " onfire_birth_facing=" + FireBirthSink.birthsFacing()
                // on-fire
                // MISSILE discharges whose aim carried the birth facing / fell back to the mount.
                + " onfire_aim_facing=" + FireBirthSink.aimFacings()
                + " onfire_aim_fallback=" + FireBirthSink.aimFallbacks()
                // appended fields only: impulse follow-burst — contact Δv
                // detections + off-cadence burst keyframe sets on the fracture detector
                // (detector-lifetime = per-round on the fresh-source-per-round mint).
                + " hkps_impulse_triggers=" + hkpsImpulseTriggers
                + " hkps_burst_emits=" + hkpsBurstEmits
                // appended fields only: hulk lane sim-thread
                // cost per observe tick (remnant read + fold), worst / mean ns — the
                // asteroid_observe_ns twin; 0/0 when the lane never ran this round.
                + " hkps_observe_ns_max=" + hkpsObserveNsMax
                + " hkps_observe_ns_avg=" + hkpsObserveNsAvg
                // appended fields only: (5) leg-7 work-frame-stall count (loud
                // work_frame_stall rows carry the per-phase detail) + (3) operator sim-speed
                // changes this round.
                + " stall_frames=" + stallFrames
                + " sim_speed_changes=" + simSpeedChanges
                // appended field only: governor-committed changes this round
                // (arm_snap + govern_down/up; each also counts in sim_speed_changes).
                + " sim_speed_auto_changes=" + autoSpeedChanges
                // AIMP aim-fan meters (appended fields only): composed batch
                // frames + changed-row emits + keepalive re-emits + stale-intent /
                // implausible-value skips; all 0 when the lane is OFF
                // (cc.server.aimFanEveryTicks=0 ⇒ null lane).
                + " aimp_frames=" + (aimFanLane == null ? 0 : aimFanLane.framesComposed())
                + " aimp_rows=" + (aimFanLane == null ? 0 : aimFanLane.rowsEmitted())
                + " aimp_keepalive=" + (aimFanLane == null ? 0 : aimFanLane.keepaliveCount())
                + " aimp_skip_stale=" + (aimFanLane == null ? 0 : aimFanLane.staleSkipCount())
                + " aimp_skip_implausible="
                + (aimFanLane == null ? 0 : aimFanLane.implausibleSkipCount())
                // Taken intents the
                // (j) stage gate refused while the seat held no mounted hull (serve-cumulative;
                // > 0 on any round with a hull loss — the dead pilot's client keeps depositing).
                + " staged_refused_unmounted=" + stagedRefusedUnmounted
                // appended fields only: per-lane WIRE bytes the composer
                // minted this serve — every session receives one copy, so bytes_sent/N minus
                // their sum = the per-session residual (ctrl retransmits, acks, per-session
                // sends, datagram headers). Cumulative per serve (the bytes_* posture).
                + " wire_gpee_bytes=" + downComposer.wireGpeeBytes()
                + " wire_edsp_bytes=" + downComposer.wireEdspBytes()
                + " wire_dive_bytes=" + downComposer.wireDiveBytes()
                + " wire_snap_bytes=" + downComposer.wireSnapBytes()
                + " wire_carrier_bytes=" + downComposer.wireCarrierBytes()
                + " wire_awsd_bytes=" + downComposer.wireAwsdBytes()
                // The edge_expired subset
                // whose armed window saw a vent/overload (the bar grades edge_expired - edge_expired_gated).
                + " edge_expired_gated=" + summary.edgeExpiredGated()
                // appended field only: the per-round intent TAKE-age buckets a0/a1/a2p/oob.
                + intentTakeAge.token()
                // The door's three CHTU drop meters,
                // boot-lifetime counts (>= 0) read through the door-lock getters like mark_rate_drop= — empty
                // token bucket / unseated sender (null-authority door only) / stale (BYE'd, failed or
                // seat-vacated) sender, plus a /module claim undone at its re-check. Counts only: no chat text.
                + " chat_rate_drop=" + door.chatRateDropped()
                + " chat_unseated_drop=" + door.chatUnseatedDropped()
                + " chat_stale_drop=" + door.chatStaleSessionDropped()
                // The scan-row-1 door meters, boot-lifetime
                // counts (>= 0) through the same door-lock getters — non-CHTU CTRL / PINT from a BYE'd or failed session,
                // and PSTE echoes past the first inside the per-session 1 s window (decoded, not printed). Counts only.
                // Token style = this door group's (chat_stale_drop= / mark_rate_drop=), renamed to match.
                + " ctrl_stale_drop=" + door.ctrlStaleSessionDropped()
                + " snap_stale_drop=" + door.snapStaleSessionDropped()
                + " posture_rate_drop=" + door.postureEchoRateDropped()
                // appended field only: PDCLs dropped at a full per-session pending FIFO
                // (MAX_PENDING_DECLARATIONS) — write-only since birth. Reader = UpDoorLivenessRateRows PF1.
                + " pdcl_fifo_drop=" + door.declarationsDropped();
    }
}
