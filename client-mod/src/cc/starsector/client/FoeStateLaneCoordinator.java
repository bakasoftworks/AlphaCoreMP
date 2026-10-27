package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * The foe-state reconcile family — every per-entity lane that folds the authoritative wire
 * state of a NON-own hull (1v1 primary foe or bots-registry remote) into its local replica:
 * position/velocity/facing dead-reckon ({@link #reconcileFoe}/{@link #reconcileFoeTo}),
 * engine state, primary-system window + mid-window seed, maneuver commands, flux and
 * flux-flags, shield, the phase-cloak lane (a {@link FoeCloakLane} sibling — the
 * flux-flags lane's overload-edge bookkeeping shares its per-twin memory), autofire groups,
 * per-mount weapon state, and the registry driver
 * ({@link #maintainRemoteEntities}) with its server-decided kill split
 * ({@link #killRemote}/{@link #killFighterRemote}) and the phase-dive enroll drain.
 *
 * <p><b>Provenance.</b> A verbatim extraction out of {@link PredictReconcileCombatPlugin}.
 *
 *
 * Zero policy change: bodies moved byte-for-byte with only
 * {@code plugin.}/{@code PredictReconcileCombatPlugin.} receiver qualifications, javadoc
 * requalifications, and {@code private}→package on the relayed primitives; the
 * flameout-ramp gate + engine mask-fork meter moved unchanged.
 *
 * <p><b>ORDER RISK (the named risk — do not "clean up").</b> The per-entity lane CALL ORDER
 * is preserved identically on the 1v1 path ({@code foeShip} — the inline chain retained in
 * the plugin's {@code advance()}) and the registry path ({@code remoteEntities} —
 * {@link #maintainRemoteEntities} here), including the deliberate FluxFlags↔Autofire
 * TRANSPOSE between the two paths (1v1: …Flux → FluxFlags → Autofire → Shield…; registry:
 * …Flux → Autofire → FluxFlags → Shield…), the per-lane {@code meterCompare} flags (NOT a
 * clean 1v1-vs-registry proxy), the two engine-state variants by NAME
 * ({@link #maintainFoeEngineState} metered/guarded vs {@link #maintainRemoteEngineState}
 * guard-free), and the {@code "foe"}/{@code "remote"} grader-parsed label split. Never fold
 * the two orderings into one canonical sequencer.
 *
 * <p><b>What stays plugin-side (reached via {@code plugin.}):</b> knob statics
 * ({@code FOE_*}, {@code WEAPON_STATE_*}, {@code BEAM_PRESENTATION_*}, bit masks), every
 * meter counter except the beam family (lane-owned since the E2 {@link BeamPresentationLane} extraction), the shared lane
 * objects/log sinks, the reflected foe-state instance fields ({@code foeShip},
 * {@code foeKilled}, {@code capFoeDamage}, {@code foePoseSeen} — the FoePoseSeenLatchSmoke
 * reflection contract), the 1v1 seed latches, and the package-shared statics
 * {@code firstOther}/{@code systemStateForCode}/{@code settleBoostClockAfterFluxPin}
 * (external call sites byte-untouched).
 *
 * <p>Growth watch: the cloak lane was the first carve (2370 → ~1840 lines);
 * the next candidate + trigger live in the FSLC row.
 */
final class FoeStateLaneCoordinator {

    private final PredictReconcileCombatPlugin plugin;
    /** Dead bodies leave the fleet-manager deployed set (logic in the lane). */
    final DeployedSetEvictLane deployedEvict = new DeployedSetEvictLane();
    /** The foe phase-cloak lane — carved VERBATIM out of this file (the
     *  {@code deferred-refactors.md} FSLC row, trigger fired); both per-entity orderings call
     *  it at the SAME position they called the inlined body ({@code cloak.maintainFoeCloak}). */
    final FoeCloakLane cloak;

    /** {@link #fluxDisagreeRing} state bits (spec F8: rendered as four named 0/1 tokens). */
    static final int FLUX_RING_OVL_LOCAL = 1;
    static final int FLUX_RING_OVL_AUTH = 2;
    static final int FLUX_RING_VENT_LOCAL = 4;
    static final int FLUX_RING_VENT_AUTH = 8;

    /**
     * The foe-fluxflags DISAGREE ring (256 rows, dump 48,
     * coalesced per foe streak). WHY: the per-frame disagree rows spend the shared 400-line
     * {@code foeFluxFlagsLane} budget inside a few vent windows, so a late F9 had no row naming
     * WHICH foe disagreed.
     * Run-level forensics: never cleared at a round boundary (the hulk-ring precedent).
     */
    final EntityEventRing fluxDisagreeRing = new EntityEventRing(
            "predict foe-fluxflags flag-ring", 256, 48, new String[] {"disagree"},
            null, new String[] {"ovl_local", "ovl_auth", "vent_local", "vent_auth"},
            "frames", "last_tick", null, null, null, null);

    /**
     * The foe-fluxflags vent TAIL ring (32 rows, dump 16):
     * one row per wire vent window whose local vent drained early. A separate ring so the disagree
     * flood never evicts these once-per-window rows inside the 900-tick window (spec F7).
     */
    final EntityEventRing fluxTailRing = new EntityEventRing(
            "predict foe-fluxflags flag-ring", 32, 16, new String[] {"vent_tail"},
            null, null, null, null, null, "pinnedFlux", null, null);

    /** The vent TAIL live row's OWN run budget (never the shared 400). */
    static final int VENT_TAIL_LOG_MAX = 20;
    private boolean fluxRingDumpFailLogged; // the fail-soft dump wrapper logs once per run

    /** Replica flux pins SKIPPED
     *  inside the 2-cell dead band ({@link FoeFluxPinLaw#PIN_SKIP_BAND}). Lane-held (the
     *  {@code cloakKfRepairLagSkips} precedent); exported via {@link #fluxPinMeterTokens}. */
    long foeFluxPinBandSkips;
    /** Pins SKIPPED because the local vent drain leads the
     *  lagging wire ({@link FoeFluxPinLaw#PIN_SKIP_VENT_DRAIN}); exported with the band skips. */
    long foeFluxPinVentDrainSkips;
    /** wire-overloaded frames whose local overload
     *  already ran this window ({@link OverloadWindowLaw#OVL_ACT_SUPPRESS_RESTART}) — no re-begin. */
    long foeFluxFlagsOverloadRestartSuppressed;
    /** APPLY presses suppressed on a system window's
     *  tail ({@link FoeSystemWindowLaw#SUPPRESS_REPRESS}). */
    long foeSystemRepressSuppressed;
    /** The REPLICA engine re-disable backstop. A
     *  SEPARATE instance from {@code plugin.engineRedisable} so the own meter
     *  ({@code predict_own_engine_redisable_backstopped}) and its confess flag stay own-only; its
     *  {@code backstopped()} IS {@code predict_foe_engine_redisable_backstopped}. Cleared in
     *  {@link #resetForMatchReset}. */
    final EngineRedisableBackstop foeEngineRedisable = new EngineRedisableBackstop();
    /** per-replica guard memory, weak-keyed like
     *  the cloak lane's edge memory — [0]/[1] VACANT (the system window moved to
     *  {@link #foeSystemWindows}), [2] the current overload window's restart row logged. */
    private final java.util.Map<ShipAPI, boolean[]> foeGuardMemory =
            new java.util.WeakHashMap<ShipAPI, boolean[]>();
    /** per-replica system-window memory (keyed by
     *  the ON edge's simTick, carried-tail aware) — ONE record per replica, weak-keyed like
     *  {@link #foeGuardMemory} and cleared with it per round. */
    private final java.util.Map<ShipAPI, FoeSystemWindowLaw.Window> foeSystemWindows =
            new java.util.WeakHashMap<ShipAPI, FoeSystemWindowLaw.Window>();
    /** APPLY presses issued in a window that opened
     *  with the previous window's local tail still active (the old latch suppressed every one). */
    long foeSystemCarriedTailPresses;
    /** APPLY presses issued at IDLE that STILL read IDLE on
     *  the next fresh frame — the engine refused them. WHY: a client-only travel drive blocks
     *  USE_SYSTEM,
     *  distinct from a vanilla cooldown refusal (already the {@code carried_tail} class, never
     *  pressed at IDLE). The modset T5 class read ~377 here pre-fix; the press TOOK iff the state
     *  left IDLE by the next fresh frame (the queued command dispatches in the same
     *  {@code Ship.advance}). */
    private long foeSystemIdleRefused;
    /** The {@link #foeSystemIdleRefused} share whose press saw the replica's travel drive ON. */
    private long foeSystemIdleRefusedDrive;

    /** The per-replica guard slots (created on first use). */
    private boolean[] guardMemoryFor(ShipAPI foe) {
        boolean[] m = foeGuardMemory.get(foe);
        if (m == null) {
            m = new boolean[3];
            foeGuardMemory.put(foe, m);
        }
        return m;
    }

    /** Guard B tokens — main splices them beside
     *  {@code predict_foe_flux_apply_frames} (the meter-export law; PRCP is outside this lane). */
    String fluxPinMeterTokens() {
        return " predict_foe_flux_pin_band_skips=" + foeFluxPinBandSkips
                + " predict_foe_flux_pin_vent_drain_skips=" + foeFluxPinVentDrainSkips;
    }

    /** Guard E token — beside
     *  {@code predict_foe_fluxflags_vent_restart_suppressed}. */
    String fluxFlagsGuardMeterTokens() {
        return " predict_foe_fluxflags_overload_restart_suppressed=" + foeFluxFlagsOverloadRestartSuppressed;
    }

    /** Guard D token — beside
     *  {@code predict_foe_system_apply_presses}. */
    String systemGuardMeterTokens() {
        return " predict_foe_system_repress_suppressed=" + foeSystemRepressSuppressed
                + " predict_foe_system_carried_tail_presses=" + foeSystemCarriedTailPresses
                + " predict_foe_system_idle_refused=" + foeSystemIdleRefused //
                + " predict_foe_system_idle_refused_drive=" + foeSystemIdleRefusedDrive;
    }

    /** Guard C token — beside
     *  {@code engineRedisable.meterTokens()}. */
    String engineGuardMeterTokens() {
        return " predict_foe_engine_redisable_backstopped=" + foeEngineRedisable.backstopped();
    }

    FoeStateLaneCoordinator(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
        this.cloak = new FoeCloakLane(plugin);
    }


    /**
     * K23 enabling step (idempotent; the plugin's {@code finishMatchReset} relays here): the
     * foe-family ROUND-scoped latches/maps die with the round; cumulative meter counters
     * persist (they live plugin-side with their emitters).
     */
    void resetForMatchReset() {
        plugin.foeSystemSeeded = false; // The seed is once per entity per join/ROUND
        cloak.resetForMatchReset(); // bit-edge + keyframe-freshness memory is per-round (weak-keyed, but a reused hull must not carry a stale edge)
        plugin.lastShieldFacingU8.clear(); // keep-last facing is per-round state
        plugin.engineStateLastMeterTick = Long.MIN_VALUE; // 1v1 same-tick meter guard re-arms
        plugin.compDmgShield.resetForMatchReset(); // per-round identity sets
        plugin.remoteReapQueue.clear(); // The registry rebuild already dropped/reset every entry
        // The replica backstop's identity map + the guard
        // windows are per-round (a reused hull must not carry a stale hold/window).
        foeEngineRedisable.clear();
        foeGuardMemory.clear();
        foeSystemWindows.clear(); // The system-window records are per-round too
        plugin.foeSystemLogBudget.resetKeys(); // the row budget's key table is per-round like the windows (ceiling stays per-combat with the lane's cap)
    }

    /**
     * The reap sweep: drop every entry the fighter kill
     * lane flagged LAST frame from {@link PredictReconcileCombatPlugin#remoteEntities}. Runs at the
     * top of {@link #maintainRemoteEntities}, BEFORE the walk, so (a) the kill (which fires inside
     * that walk) never mutates the map under its own iterator and (b) every lane saw one full
     * render frame of {@code killed=true} on the entry first. Fighters only (flagged in
     * {@link #killFighterRemote}): the engine instant-removes a fighter corpse and its growth
     * wire id is never re-issued, so the entry is dead weight — before this sweep it stayed forever and
     * the per-frame registry walks (~17 sites, one O(N²)) grew with the CUMULATIVE launch count
     * (858 rows, ~90 alive: the client frame overruns). A same-id re-life in the one
     * frame between flag and sweep ({@code respawnRemote} clears {@code reapTick}) or a RAPP
     * re-append (a NEW object under the key) is skipped by the identity check. The recall
     * hold-and-animate ({@link RecallFxLane}) holds its own body reference, so a DEPARTED fighter
     * reaps like a DESTROYED one. {@code variantById} rows stay (id-keyed lookups only, never
     * walked). Fail-soft, render-thread only.
     */
    void reapFlaggedRemotes(long simTick) {
        java.util.List<PredictReconcileCombatPlugin.RemoteEntity> queue = plugin.remoteReapQueue;
        if (queue.isEmpty()) {
            return; // the O(1) common frame
        }
        int reaped = 0;
        try {
            for (int i = 0; i < queue.size(); i++) {
                PredictReconcileCombatPlugin.RemoteEntity re = queue.get(i);
                Long key = Long.valueOf(re.entityId);
                if (re.reapTick == Long.MIN_VALUE || plugin.remoteEntities.get(key) != re) {
                    continue; // un-flagged (same-id re-life) or re-appended under a new object
                }
                plugin.remoteEntities.remove(key);
                if (re.ship != null && re.ship == plugin.foeShip) {
                    plugin.foeShip = null; // the respawnRemote idiom: re-anchored by the next side-1 spawn
                }
                // Side maps keyed by this id/body that had no kill-time eviction (the audit):
                // the facing lane's 128-slot table (dead rows starved live foes), the keep-last
                // shield facing, the body-treatment ledger. Lanes that already self-evict on
                // killed/dead (RemoteAimLane, despawnReasonByEntity, FoeInterpLane) need nothing.
                plugin.foeFacing.release(re.entityId);
                plugin.lastShieldFacingU8.remove(key);
                plugin.forgetRemoteBodyTreatment(re.ship);
                // The replay-driven hold set is walked EVERY
                // frame and had no reap-side remove — 453 reaped bodies stayed in it (the walk grew
                // 1.4 % → 8.9 % of the render thread; a REMOVED fighter keeps owner 0, so it took the
                // full weapon walk). Drop the body here, while it is still reachable.
                plugin.unregisterReplayDriven(re.ship);
                plugin.fighterHud.onRemoteReaped(re); // Leave the replica wing while the body is still reachable (pip dims)
                re.ship = null; // the dead ShipAPI + ledger/interp state stop being reachable here
                re.ledger = null;
                reaped++;
            }
        } catch (Throwable t) {
            PilotWatchLog.error("predict reap: sweep FAILED (queue=" + queue.size() + ") tick=" + simTick, t);
        } finally {
            queue.clear();
        }
        if (reaped > 0) {
            plugin.remoteReaped += reaped;
            if (plugin.remoteReaped <= 8 || (plugin.remoteReaped % 100) < reaped) {
                PilotWatchLog.info("predict reap: " + reaped + " fighter entr" + (reaped == 1 ? "y" : "ies")
                        + " dropped from the registry (total=" + plugin.remoteReaped
                        + " registry=" + plugin.remoteEntities.size() + ") tick=" + simTick);
            }
        }
    }

    /**
     * The per-frame registry block (bots shapes only): for each expected remote
     * entity, pose present and hull &gt; 0 ⇒ the proven foe treatment ({@link #reconcileFoe} +
     * the per-entity engine-state apply); pose ABSENT or hull ≤ 0 ⇒ the per-entity server-decided
     * real death ({@link #killRemote}). Absence IS the wire death contract (the server's roster
     * snapshot drops dead ships) — an unbound entry that never appears on the wire is left alone
     * (counted in the teardown census, visible in the smoke debrief).
     */
    void maintainRemoteEntities(ServerSnapshotFrame snapshot, float alpha, boolean freshSnapshot) {
        reapFlaggedRemotes(snapshot.simTick()); // Last frame's fighter kills leave the map
        for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
            if (re.ship == null) {
                continue; // not bound (yet) — bindRemoteEntities retries each frame
            }
            if (re.droneGate) {
                // Runs for killed entries too — a drone hulk flips back to SHIP
                plugin.droneGateLane.apply(re);
            }
            ServerEntityPose pose = snapshot.entityById(re.entityId);
            if (pose != null && pose.hitpoints() > 0f) {
                // FORENSIC stamp only — a pre-clear stale view consumed
                // after the epoch bump can stamp the DEAD session's roster with the new epoch
                // (W5-D4), so no death lane keys on this; the reconnect-hold log row prints it
                // to confess exactly that race when it recurs.
                re.sightEpoch = plugin.reAdmitEpoch;
                if (re.rekeyPoseHold) {
                    // First alive pose after a mount-lifecycle re-key — the
                    // structural CTRL≺AWSD gap closed normally; absence is a real signal again.
                    re.rekeyPoseHold = false;
                    re.rekeyHoldSinceTick = Long.MIN_VALUE;
                }
                if (re.adoptHoldSinceTick != Long.MIN_VALUE) {
                    // Pose-land reset, the rekey-hold idiom's own clear: the
                    // body is alive again, so the kill lane stopped firing — a stamp that
                    // survived here would age past the hold budget and insta-eject the LIVE
                    // body at a LATER same-id transfer's first absence (W3-H re-opened).
                    re.adoptHoldSinceTick = Long.MIN_VALUE;
                }
                if (re.killed) {
                    // The REMOTE fly-in respawn edge: a killed entry's wire id
                    // reappearing alive means the server remounted that seat's fresh hull at the SAME
                    // id. Reset the entry UNBOUND and let the proven spawn-on-sight adopt re-field it.
                    plugin.respawnRemote(re);
                    continue;
                }
                plugin.godMirror.maintain(re.ship, pose); // The wire bit alone gates
                if (!re.stationModule) {
                    // A station MODULE is never pose-reconciled and never
                    // engine-driven — CombatEngine.updateStationModuleLocations
                    // re-derives its location AND facing from the parent every frame, so a pose
                    // write is erased inside the frame and the authoritative correction rides the
                    // BASE. The identity/hull/state/death lanes below all apply unchanged.
                    // Class latch + snap-only reconcile inside a teleport
                    // discontinuity window (lane-owned since K16).
                    reconcileFoe(re.ship, pose, alpha,
                            plugin.teleportLane.snapOnly(re.ship, re.jump, snapshot.simTick()),
                            re.jump.fenceTick); // fence-aware seam (per-entity D6 fence)
                    maintainRemoteEngineState(re.ship, pose, freshSnapshot, snapshot.simTick());
                }
                maintainWeaponState(re.ship, pose, freshSnapshot, snapshot.simTick(), "remote");
                // per-entity one-shot mid-window system seed (no edge seen yet).
                if (!re.sysSeeded && re.sysServerOpen == null && freshSnapshot) {
                    re.sysSeeded = seedFoeSystemState(re.ship, pose, snapshot.simTick());
                }
                // per-entity system-window apply + METER: in bastion EVERY
                // ship is a REGISTRY remote, never the primary foe, so meterCompare=false here was
                // the whole reason graded_frames read 0. Both call sites grade, the
                // shield/cloak-lane precedent; the registry/primary ship sets are
                // disjoint so no double-count.
                maintainFoeSystemState(re.ship, re.sysServerOpen, re.sysOpenTick, re.entityId,
                        freshSnapshot, snapshot.simTick(), true);
                // Consume a pending never-cycled heal (pose scalars at hand; after
                // the system lane, so an in-flight useSystem press had its chance first).
                if (freshSnapshot) {
                    plugin.teleportLane.maintainHeal(re.jump, re.ship, pose, snapshot.simTick());
                }
                // per-entity maneuver-command apply (apply-only; the meter compare stays 1v1).
                maintainFoeManeuver(re.ship, pose, freshSnapshot, snapshot.simTick(), false);
                // per-entity flux + autofire apply (apply-only; the meters stay 1v1).
                maintainFoeFlux(re.ship, pose, freshSnapshot, snapshot.simTick(), false);
                maintainFoeAutofire(re.ship, pose, freshSnapshot, snapshot.simTick(), false);
                // per-entity selected-group apply (apply-only; the meter stays 1v1).
                maintainFoeSelection(re.ship, pose, freshSnapshot, snapshot.simTick(), false);
                // per-entity flux-flags lane + METER — in bastion the boss is a
                // REGISTRY remote, never the primary foe; the ship sets are disjoint so no
                // double-count (the shield/cloak-lane precedent).
                maintainFoeFluxFlags(re.ship, pose, freshSnapshot, snapshot.simTick(), true);
                // per-entity shield apply + METER — from seat 2 the boss
                // Onslaught is a REGISTRY remote, never the primary foe (
                // carried=0/agree=0 while apply_commands=27k); registry/primary ship sets are
                // disjoint so no double-count. Sibling lanes keep their 1v1-scoped meters.
                maintainFoeShield(re.ship, pose, freshSnapshot, snapshot.simTick(), true);
                // Per-entity cloak lane — meter at BOTH sites like the shield
                // lane (in bastion the grendel is a REGISTRY remote, never the primary-foe 1v1
                // path, and the two ship sets are disjoint so no double-count). The
                // one-shot seed folded INTO the lane (its first fresh keyframe repairs = the seed).
                cloak.maintainFoeCloak(re.ship, pose, freshSnapshot, snapshot.simTick(), true);
                // per-entity hull ledger + reconcile (the same lane as the 1v1 foe).
                plugin.hullLane.maintain(re.ship, pose, freshSnapshot, snapshot.simTick(), re);
                // The beam-presentation RELAY — per render frame, never fresh-gated.
                plugin.beamPres.maintainBeamPresentation(re.ship, pose, snapshot.simTick(), "remote");
            } else if (plugin.reAdmitAbsenceHoldActive(snapshot.simTick())) {
                // The re-admission absence-hold: inside the hold window
                // absence NEVER kills, sightEpoch regardless. The "seen-post-clear-then-
                // absent = genuine death" corollary trusted the epoch stamp as a post-refill
                // witness, but the stamp can ride a PRE-clear view consumed after the epoch bump
                // (unordered channels; flight 1: ARMED @407807 vs merger clear @407816) — that
                // raced stamp + a post-clear partial view killed the ALIVE Aurora (W5-D4). A
                // true death inside the window presents at the bounded expiry — the
                // trade, remote flavor. sightEpoch==epoch in the row below = the race recurred.
                if (plugin.reAdmitHoldRemoteSkipLogs < 4) {
                    plugin.reAdmitHoldRemoteSkipLogs++;
                    PilotWatchLog.info("predict reconnect-hold: entity " + re.entityId
                            + " absent inside the re-admission refill gap — kill held"
                            + " (sightEpoch=" + re.sightEpoch + " epoch=" + plugin.reAdmitEpoch
                            + ") tick=" + snapshot.simTick());
                }
            } else {
                killRemote(re, pose, snapshot.simTick());
            }
        }
    }

    /**
     * The per-entity engine-state lane: the 1v1 meter's {@code engineStateLastMeterTick}
     * guard would skip every entity after the first on the same tick, so this guard-free variant runs
     * the {@link #applyEngineState} apply directly (fresh frames only; carried/missing counters
     * shared with the 1v1 meter; the local-vs-auth disagree compare stays 1v1-only — per-entity
     * compare noise would swamp the capped log).
     */
    void maintainRemoteEngineState(ShipAPI ship, ServerEntityPose pose, boolean freshSnapshot, long simTick) {
        if (!PredictKnobConfig.FOE_ENGINE_METER || !freshSnapshot || ship == null || !ship.isAlive()) {
            return;
        }
        try {
            if (!pose.hasEngineState()) {
                plugin.foeEngineLane.framesMissing++;
                return;
            }
            plugin.foeEngineLane.framesCarried++;
            // wire-covered replica ⇒ local component-damage rolls yield authorship
            plugin.compDmgShield.ensureEngineFace(ship, pose, simTick);
            ShipEngineControllerAPI ctrl = ship.getEngineController();
            if (ctrl != null && PredictKnobConfig.FOE_ENGINE_APPLY) {
                applyEngineState(ship, ctrl, pose, simTick, false);
            }
            // Vanilla's flamed-out-fighter = SHIP
            // collision rule (Ship.getCollisionClass) on the wire's ctrl=2 — the
            // decision, prior-class memory and meters live in FighterFlameoutClassLaw.
            if (ctrl != null && FighterFlameoutClassLaw.MODE != FighterFlameoutClassLaw.MODE_OFF
                    && ship.isFighter()) {
                FighterFlameoutClassLaw law = FighterFlameoutClassLaw.live(plugin);
                int r = law.step(true, pose.entityId(), System.identityHashCode(ship),
                        pose.engineControllerState(), ctrl.isDisabled(), ctrl.isFlamingOut(),
                        ship.getCollisionClass(), com.fs.starfarer.api.combat.CollisionClass.SHIP,
                        com.fs.starfarer.api.combat.CollisionClass.NONE, simTick);
                if ((r & FighterFlameoutClassLaw.R_WRITE) != 0) {
                    ship.setCollisionClass((com.fs.starfarer.api.combat.CollisionClass) law.writeClass());
                }
                if ((r & FighterFlameoutClassLaw.R_LOG) != 0) {
                    PilotWatchLog.info(law.edgeRow());
                }
            }
        } catch (Throwable ignore) {
            // presentation-side apply; never break the watch
        }
    }

    /**
     * The per-entity server-decided real death — the {@link OwnSeatLifecycleLane#killFoe} body per
     * registry entry: snap to the last authoritative pose, clear the per-entity cap (axis C), then
     * the massive shield-bypassing HE blow so the engine runs its REAL death. Idempotent
     * per entity via {@code re.killed}; fail-soft.
     *
     * <p>Fighters route by the wire EDSP reason instead ({@link #killFighterRemote})
     * — the server's reclassifier holds the EDSP ≤2 ticks for seam truth but AWSS roster absence is
     * NOT held, so a fighter's absence can precede its reason by 1-2 net ticks: no recorded reason
     * yet ⇒ HOLD the kill ≤{@link PredictReconcileCombatPlugin#FIGHTER_REASON_HOLD_TICKS}, timeout ⇒ DESTROYED-lightweight +
     * loud one-shot (also covers eventLaneOn=false serves). Non-fighters NEVER hold (verbatim path).
     *
     * <p>Every {@code bots kill} log line carries {@code tick=} (the
     * authoritative snapshot tick) — wave/bot deaths mint no hulk-adopt line, so this is the only
     * client-side death tick offline audits (audit-armor-join death cuts) can key on.
     */
    void killRemote(PredictReconcileCombatPlugin.RemoteEntity re, ServerEntityPose lastPose, long simTick) {
        if (re.killed || re.ship == null || plugin.engine == null || !re.ship.isAlive()) {
            return;
        }
        // The mount-lifecycle re-key gap guard (F at the mount re-point, G at
        // the dismount re-key): MMNT rides CTRL pre-advance, so the row's id is structurally
        // absent from snapshots until its first post-re-key pose lands — killing here blows a
        // LIVE body and the downstream repair/mint makes
        // it permanent. Bounded: a real edge-death falls through loud after the hold.
        if (re.rekeyPoseHold) {
            if (re.rekeyHoldSinceTick == Long.MIN_VALUE) {
                re.rekeyHoldSinceTick = simTick;
                PilotWatchLog.info("predict rekey-hold: entity " + re.entityId + " absent inside"
                        + " the mount re-key gap — holding for first pose (max "
                        + PredictReconcileCombatPlugin.MOUNT_REKEY_POSE_HOLD_TICKS
                        + " ticks) tick=" + simTick);
                return;
            }
            if (simTick - re.rekeyHoldSinceTick
                    <= PredictReconcileCombatPlugin.MOUNT_REKEY_POSE_HOLD_TICKS) {
                return;
            }
            re.rekeyPoseHold = false;
            PilotWatchLog.info("predict rekey-hold: entity " + re.entityId + " TIMED OUT after "
                    + (simTick - re.rekeyHoldSinceTick) + " ticks — no pose ever landed;"
                    + " treating the absence as a real death tick=" + simTick);
        }
        // A despawn/absence for the id an in-flight command transfer
        // names as its adopt target must NOT eject the live body — the COMMIT re-key retires the
        // target id as a same-tick DEPARTED (an id retirement, not a death), and at E3 @170416
        // this lane removed the full-hull Aurora during the COMMIT's bounded swap wait, so the
        // adopt then honestly wreck-adopted a hull the server held alive at hullFrac=1.0. Sits
        // BEFORE the reason remove-take so a held DEPARTED stays takeable at the fall-through;
        // the adopt retires the row itself, ending the hold. Bounded past SWAP_FORCE_MILLIS so a
        // genuine departure never followed by an adopt still removes, loud.
        if (plugin.xfer.isPendingAdoptTarget(re.entityId)) {
            if (re.adoptHoldSinceTick == Long.MIN_VALUE) {
                re.adoptHoldSinceTick = simTick;
                PilotWatchLog.info("predict adopt-hold: entity " + re.entityId + " despawn/absence"
                        + " held — pending transfer adopt target (max "
                        + PredictReconcileCombatPlugin.TRANSFER_ADOPT_POSE_HOLD_TICKS
                        + " ticks) tick=" + simTick);
                return;
            }
            if (simTick - re.adoptHoldSinceTick
                    <= PredictReconcileCombatPlugin.TRANSFER_ADOPT_POSE_HOLD_TICKS) {
                return;
            }
            long heldTicks = simTick - re.adoptHoldSinceTick;
            re.adoptHoldSinceTick = Long.MIN_VALUE;
            PilotWatchLog.info("predict adopt-hold: entity " + re.entityId + " TIMED OUT after "
                    + heldTicks + " ticks — no adopt consumed it; treating the despawn as real"
                    + " tick=" + simTick);
        }
        // The body is leaving the field by SOME exit
        // below (fighter/dive/departed/destroyed) — drop any pending-mount park stamp keyed by
        // its id (a seat's fixed wire id IS its body's id; a non-seat id is a free map miss).
        plugin.xfer.forgetSeatPark(re.entityId);
        // Remove-take the wire reason (net-thread put in onDespawnEvent; absent ⇒ null). The
        // non-fighter path honors DEPARTED corpse-free (was: map hygiene
        // only); DESTROYED/absent keep the verbatim death path below.
        cc.starsector.client.net.ClientDespawnReason reason;
        synchronized (plugin.despawnReasonByEntity) {
            reason = plugin.despawnReasonByEntity.remove(Long.valueOf(re.entityId));
        }
        if (re.ship.isFighter()) {
            if (reason == null) {
                if (re.reasonHoldSinceTick == Long.MIN_VALUE) {
                    re.reasonHoldSinceTick = simTick;
                    return; // first sight of the absence — wait for the trailing reason
                }
                if (simTick - re.reasonHoldSinceTick <= PredictReconcileCombatPlugin.FIGHTER_REASON_HOLD_TICKS) {
                    return; // still inside the hold window — the reason may land next net tick
                }
                PilotWatchLog.info("predict bots kill: FIGHTER entity " + re.entityId
                        + " reason-hold TIMED OUT after " + (simTick - re.reasonHoldSinceTick)
                        + " ticks (no EDSP reason: eventLaneOn=false serve or lost event)"
                        + " -> DESTROYED-lightweight fallback tick=" + simTick);
            }
            killFighterRemote(re, lastPose, reason, simTick);
            return;
        }
        // A dive-latched replica left the field ALIVE (emergency dive) —
        // the lane played the dive; its despawn is a corpse-free removal: no death FX, no
        // fracture mint. meter/off (or an unenrolled id) decline ⇒ the verbatim path below.
        if (plugin.phaseDive.finishDeparture(plugin.engine, re.entityId, re.ship)) {
            re.killed = true;
            re.deathTick = simTick; // r1 kill tick (ServerDealtSourceLaw)
            re.capActive = false; // same bookkeeping as the death path — the entity is gone
            if (re.ledger != null) {
                re.ledger.close("remote", re.entityId);
            }
            deployedEvict.evict(plugin.engine, re.ship, "dive-departed"); //
            PilotWatchLog.info("predict bots kill: REMOTE entity " + re.entityId + " (side "
                    + re.side + ") emergency-dive DEPARTED -> corpse-free removal tick=" + simTick);
            return;
        }
        // Belt: a non-fighter DEPARTED without a dive enrollment (knob-off
        // dive, retreat, a depart-hold timeout's trailing signal) is still a DEPARTURE — the
        // server audits reasons (despawn_reason_corrected flips DEPARTED->DESTROYED when a hulk
        // family is born same-tick), so honoring it corpse-free is wire truth; presenting it as
        // a death is what minted an observer corpse in a test run. Bookkeeping matches the dive path.
        if (reason == cc.starsector.client.net.ClientDespawnReason.DEPARTED) {
            re.killed = true;
            re.deathTick = simTick; // r1 kill tick (ServerDealtSourceLaw)
            re.capActive = false;
            if (re.ledger != null) {
                re.ledger.close("remote", re.entityId);
            }
            deployedEvict.evict(plugin.engine, re.ship, "departed"); //
            plugin.unregisterReplayDriven(re.ship); // A departed body leaves the per-frame hold walk
            if (plugin.engine != null) {
                plugin.engine.removeEntity(re.ship);
            }
            PilotWatchLog.info("predict bots kill: REMOTE entity " + re.entityId + " (side "
                    + re.side + ") DEPARTED (no dive) -> corpse-free removal tick=" + simTick);
            return;
        }
        re.killed = true;
        re.deathTick = simTick; // r1 kill tick (ServerDealtSourceLaw)
        // The dweller entry is flagged for the reap sweep BEFORE the try — the
        // fighter lane's order (killFighterRemote) — so a throw inside the presentation can never
        // leave a killed entry walked per frame.
        boolean dweller = DwellerHullLaw.isDweller(re.ship);
        if (dweller && !re.droneGate) {
            re.reapTick = simTick;
            plugin.remoteReapQueue.add(re);
        }
        try {
            if (lastPose != null) {
                reconcilePosition(re.ship, lastPose.locX(), lastPose.locY(), 1.0f);
            }
            re.capActive = false; // axis C: the authoritative kill blow must pass the cap untouched
            // Close on the same axis-C edge — the HE blow never enters rawHull.
            if (re.ledger != null) {
                re.ledger.close("remote", re.entityId);
            }
            // A Shrouded Dweller hull leaves NO corpse in vanilla (creator pins
            // hulkChanceOverride 0; the server's engine runs the same law and publishes no hulk
            // row) — route it to the instant-remove death, mint no fracture family (that is what
            // pinned the invisible hulk alive), and flag the entry for the reap sweep exactly as
            // the fighter lane does for a body the engine removes on the spot.
            if (dweller) {
                plugin.presenter.presentServerFighterDeath(re.ship, lastPose != null ? lastPose.facing() : Float.NaN);
                deployedEvict.evict(plugin.engine, re.ship, "dweller-destroyed");
                PilotWatchLog.info("predict bots kill: REMOTE entity " + re.entityId + " (side " + re.side
                        + ") DWELLER server-decided death -> instant-remove, no hulk/fracture tick=" + simTick);
                return;
            }
            plugin.presenter.presentServerDeath(re.ship, lastPose != null ? lastPose.facing() : Float.NaN);
            deployedEvict.evict(plugin.engine, re.ship, "destroyed"); // AFTER the death so `gone` counts the listener
            plugin.fractures.mintFractureFamily(re.entityId, re.ship); // deathTick-keyed mint (v2)
            PilotWatchLog.info("predict bots kill: REMOTE entity " + re.entityId + " (side " + re.side
                    + ") server-decided death applied locally tick=" + simTick);
        } catch (Throwable ignore) {
            // the death is decided server-side; a local FX failure must not break the watch
        }
    }

    /**
     * The fighter kill lane — the server keeps no fighter corpse (hulkChance 0 ⇒
     * its engine took the instant-remove branch), so neither may the replica. DEPARTED (carrier
     * recall/retreat) = the recall hold-and-animate (~0.6 s of vanilla's own
     * RecallDeviceStats presentation on the local body, then {@code removeEntity}; knob
     * {@code cc.predict.recallFx}, {@code off} = the older silent reap); DESTROYED or reason
     * timeout = the lightweight presenter death (instant-remove hulk roll, NO fracture-family
     * mint, NO collision flip — the hull-spec FIGHTER class stays).
     * Bookkeeping matches {@link #killRemote} exactly: killed latch, axis-C cap clear, ledger close.
     */
    void killFighterRemote(PredictReconcileCombatPlugin.RemoteEntity re, ServerEntityPose lastPose,
            cc.starsector.client.net.ClientDespawnReason reason, long simTick) {
        re.killed = true;
        re.deathTick = simTick; // r1 kill tick (ServerDealtSourceLaw)
        if (!re.droneGate) {
            // Flag for the next-frame reap sweep ({@link #reapFlaggedRemotes}) —
            // a fighter entry has no future life. The drone-gate guard is the
            // boundary (a hulk-capable drone flips back to SHIP on its killed entry); by
            // construction fighters never gate, so it documents the class, not a live branch.
            re.reapTick = simTick;
            plugin.remoteReapQueue.add(re);
        }
        try {
            re.capActive = false; // axis C: the authoritative kill blow must pass the cap untouched
            if (re.ledger != null) {
                re.ledger.close("remote", re.entityId);
            }
            boolean departed = reason == cc.starsector.client.net.ClientDespawnReason.DEPARTED;
            if (departed) {
                // Recall hold-and-animate on the local body (RecallFxLane);
                // declined (knob off / dead body) ⇒ silent: a recall leaves no corpse and no FX.
                plugin.unregisterReplayDriven(re.ship); // Leaves the per-frame hold walk either way (the recall lane holds its fire itself)
                if (!plugin.recallFx.beginDeparture(re.ship)) {
                    plugin.engine.removeEntity(re.ship);
                }
            } else {
                if (lastPose != null) {
                    reconcilePosition(re.ship, lastPose.locX(), lastPose.locY(), 1.0f);
                }
                // A reclamation swarm ending AT a fabricator DOCKED — its
                // members merged into the fabricator's cloud; the body leaves silently.
                if (plugin.reclamationFx.onReplicaRemoved(re.ship, plugin.engine)) {
                    plugin.engine.removeEntity(re.ship);
                } else {
                    plugin.presenter.presentServerFighterDeath(re.ship,
                            lastPose != null ? lastPose.facing() : Float.NaN);
                }
            }
            // THE stutter fix — a dead SHIP-type fighter member is never listener-
            // evicted (!isFighter gate) and never isFighterWing()-filtered by checkCornerCheese.
            deployedEvict.evict(plugin.engine, re.ship, departed ? "fighter-departed" : "fighter-destroyed");
            PilotWatchLog.info("predict bots kill: FIGHTER entity " + re.entityId + " (side "
                    + re.side + ") " + (departed
                            ? (RecallFxLane.ON ? "DEPARTED -> recall hold-and-animate" : "DEPARTED -> silent remove")
                            : "DESTROYED -> lightweight death")
                    + " reason=" + (reason != null ? reason : "timeout-fallback")
                    + " tick=" + simTick);
        } catch (Throwable ignore) {
            // the death is decided server-side; a local FX failure must not break the watch
        }
    }

    /**
     * drain-take the session's queued {@code DIVE} start records on the
     * render thread and enroll the local body (own hull or remote replica) into the
     * presentation lane. Enroll-only — the paired {@code EDSP DEPARTED} later takes the
     * corpse-free removal in {@link #killRemote}/{@link OwnSeatLifecycleLane#killOwn}.
     */
    void drainPhaseDives() {
        if (plugin.session == null) {
            return;
        }
        java.util.List<cc.starsector.client.net.ClientPhaseDive> dives = plugin.session.takePhaseDives();
        if (dives == null || dives.isEmpty()) {
            return; // null: a proxy-stubbed selftest session bypasses the interface default
        }
        for (cc.starsector.client.net.ClientPhaseDive dive : dives) {
            boolean own = dive.entityId() == plugin.session.shipId();
            ShipAPI body;
            if (own) {
                body = plugin.ownShip;
            } else {
                PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(Long.valueOf(dive.entityId()));
                body = re != null ? re.ship : null;
            }
            plugin.phaseDive.beginDive(dive.entityId(), body, own, plugin.engine);
        }
    }

    /**
     * Smooth-correct a ship's POSITION toward the authoritative {@code (authX,authY)}:
     * glide a fraction {@code alpha} of the residual, unless the error exceeds {@link PredictKnobConfig#SNAP_DISTANCE} —
     * then snap (the prediction forked beyond smoothing). Position only; velocity/facing are handled by
     * the caller (native control for the own ship; dead-reckon for the foe). Fail-soft.
     */
    void reconcilePosition(ShipAPI ship, float authX, float authY, float alpha) {
        reconcilePosition(ship, authX, authY, alpha, false);
    }

    /** The {@code snapOnly} variant: inside a teleport discontinuity window
     *  the glide branch is disabled — the residual snaps regardless of magnitude. Returns whether
     *  the position SNAPPED (vs glided or skipped) — the facing snap hatch keys off it. */
    boolean reconcilePosition(ShipAPI ship, float authX, float authY, float alpha, boolean snapOnly) {
        if (ship == null || !ship.isAlive() || !Float.isFinite(authX) || !Float.isFinite(authY)) {
            return false;
        }
        // A PARKED dive body takes no pose —
        // own reconcile, replica, death and teleport snaps all funnel here (PhaseDivePresentLane#holdParkedPose).
        if (plugin.phaseDive.holdParkedPose(ship, authY)) {
            return false;
        }
        try {
            Vector2f loc = ship.getLocation();
            float dx = authX - loc.x;
            float dy = authY - loc.y;
            if (snapOnly || dx * dx + dy * dy > PredictKnobConfig.SNAP_DISTANCE_SQ) {
                loc.x = authX;
                loc.y = authY;
                return true;
            }
            loc.x += alpha * dx;
            loc.y += alpha * dy;
        } catch (Throwable ignore) {
            // reconcile is presentation-side; never break the watch on a correction hiccup
        }
        return false;
    }

    /**
     * Reconcile the dead-reckoned foe: smooth-correct its position toward truth, set its
     * velocity to the last authoritative {@code (velX,velY)} so the engine carries it forward between
     * snapshots (dead-reckoning, NOT freezing), and take the authoritative facing (which never forks —
     * measured 0.00 deg) with light smoothing. Fail-soft. {@code snapOnly}
     * rides through to the position half and, on frames where the position
     * actually snapped, opens the facing snap hatch (direct set, no glide).
     */
    void reconcileFoe(ShipAPI ship, ServerEntityPose auth, float alpha, boolean snapOnly) {
        if (ship == null || !ship.isAlive() || auth == null) {
            return;
        }
        reconcileFoeTo(ship, auth.entityId(), auth.locX(), auth.locY(), auth.velX(), auth.velY(),
                auth.facing(), auth.angularVelocity(), alpha, snapOnly, false);
    }

    /**
     * The fence-aware foe reconcile seam: under {@code foeInterp=apply} the
     * buffered interpolated pose substitutes the reconcile TARGET (position, velocity,
     * facing, angular velocity — kinematics only); the glide/SNAP law stays untouched and
     * keeps absorbing residuals. {@code targetFor} returns {@code null} — live-pose
     * fallback, the 4-arg law verbatim — in off/meter mode, on any frame the buffer cannot
     * serve this entity (cold start, absent from brackets — silent, counted), and inside a
     * snap-only teleport window (I8: that lane owns the transit).
     * {@code fenceTick} = the entity's D6 jump fence (hold-then-jump inside the
     * interpolator, never a lerped smear across the teleport).
     */
    void reconcileFoe(ShipAPI ship, ServerEntityPose auth, float alpha, boolean snapOnly,
            long fenceTick) {
        cc.starsector.client.net.InterpolatedPose target = plugin.foeInterp.targetFor(auth, fenceTick, snapOnly);
        if (target == null) {
            reconcileFoe(ship, auth, alpha, snapOnly);
        } else if (ship != null && ship.isAlive()) {
            reconcileFoeTo(ship, auth.entityId(), target.locX(), target.locY(),
                    target.velX(), target.velY(), target.facing(), target.angularVelocity(),
                    alpha, snapOnly, true);
        }
    }

    /** The scalar core of {@link #reconcileFoe} — the target comes from the live pose or the
     *  buffered substitute ({@code interpTarget}, which declines the
     * facing deadband: an interpolated target is already sub-quantum continuous). */
    void reconcileFoeTo(ShipAPI ship, long entityId, float x, float y, float velX, float velY,
            float facing, float angVel, float alpha, boolean snapOnly, boolean interpTarget) {
        try {
            // The residual BEFORE the correction feeds the foe-step meter below.
            Vector2f loc0 = ship.getLocation();
            float rdx = x - loc0.x;
            float rdy = y - loc0.y;
            boolean snapped = reconcilePosition(ship, x, y, alpha, snapOnly);
            plugin.foeStep.note(entityId, x, y, velX, velY, rdx, rdy, snapped, alpha, snapOnly,
                    plugin.lastSnapshotSimTick);
            Vector2f vel = ship.getVelocity();
            if (Float.isFinite(velX) && Float.isFinite(velY)) {
                vel.x = velX;
                vel.y = velY;
            }
            if (Float.isFinite(facing)) {
                if (snapOnly && snapped) {
                    // Facing snap hatch: across a teleport discontinuity the glide
                    // wears pre-jump facing ~2–4 frames over an INSTANT position snap; auth facing
                    // at the marker tick already IS the engine's post-jump angle [measured 25/25].
                    ship.setFacing(facing);
                    plugin.teleportLane.noteFacingSnapFrame();
                    // Carry the presented angle across the discontinuity
                    // WITHOUT minting a step sample — a teleport is a legitimate jump and must
                    // never contaminate the smoothness statistics the C5 grade reads.
                    plugin.foeFacing.noteSnap(entityId, facing);
                } else if (PredictKnobConfig.FOE_FACING_METER) {
                    // The sub-quantum deadband + angular telemetry. In
                    // meter mode the lane returns the plain glide verbatim (zero behavior
                    // change) and only measures; in apply it withholds sub-quantum corrections
                    // so the engine's integration of the carried ω carries the rotation.
                    ship.setFacing(plugin.foeFacing.reconcileFacing(entityId, ship.getFacing(), facing,
                            angVel, alpha, plugin.foeFacingFrameDtSec, interpTarget));
                } else {
                    ship.setFacing(PredictReconcileCombatPlugin.smoothAngleDeg(ship.getFacing(), facing, alpha));
                }
            }
            if (Float.isFinite(angVel)) {
                ship.setAngularVelocity(angVel);
            }
        } catch (Throwable ignore) {
            // presentation-side dead-reckon; never break the watch
        }
    }

    /**
     * The schema-v6 foe engine-state lane (see {@link PredictKnobConfig#FOE_ENGINE_STATE_MODE}). Runs on
     * FRESH snapshots only: the tier is slow-moving idempotent state and the meter counts on the
     * snapshot-tick clock (the discipline). Meter: compare the LOCAL foe controller's
     * derived 3-state code ({@code flamingOut?1:disabled?2:0} — the server bridge's exact mapping)
     * against the carried authoritative {@code controllerState}. Apply (opt-in): hand the per-engine
     * masks to {@link #applyEngineState} so the REAL local state machine derives flameout.
     * Fail-soft like every reconcile lane.
     */
    void maintainFoeEngineState(
            ShipAPI foe, ServerEntityPose foePose, boolean freshSnapshot, long simTick) {
        if (!PredictKnobConfig.FOE_ENGINE_METER || !freshSnapshot || foe == null || foePose == null || !foe.isAlive()
                || simTick == plugin.engineStateLastMeterTick) {
            return;
        }
        plugin.engineStateLastMeterTick = simTick;
        try {
            if (!foePose.hasEngineState()) {
                plugin.foeEngineLane.framesMissing++;
                return;
            }
            plugin.foeEngineLane.framesCarried++;
            // wire-covered replica ⇒ local component-damage rolls yield authorship
            plugin.compDmgShield.ensureEngineFace(foe, foePose, simTick);
            ShipEngineControllerAPI ctrl = foe.getEngineController();
            if (ctrl == null) {
                return;
            }
            int localState = ctrl.isFlamingOut() ? 1 : (ctrl.isDisabled() ? 2 : 0);
            int authState = foePose.engineControllerState();
            if (localState == authState) {
                plugin.foeEngineLane.agree++;
            } else {
                plugin.foeEngineLane.disagree++;
                plugin.foeEngineLane.log("predict v6 engine-state disagree @tick=" + simTick
                        + ": local=" + localState + " auth=" + authState
                        + " disabledMask=0x" + Integer.toHexString(foePose.engineDisabledMask())
                        + " permMask=0x" + Integer.toHexString(foePose.enginePermDisabledMask()));
            }
            // The 3-state compare above is blind to per-engine drift — the
            // modset-echo corpus carried engine_damage_penalty mult forks up to 2x on rows where it
            // read agree. Meter the ≤32-engine disabled mask itself (pre-apply, so a same-tick edge
            // counts once; PERSISTENT drift shows as this counter outrunning the apply counters).
            List<ShipEngineControllerAPI.ShipEngineAPI> engines = ctrl.getShipEngines();
            if (engines != null) {
                int n = Math.min(engines.size(), 32);
                int localMask = 0;
                for (int i = 0; i < n; i++) {
                    ShipEngineControllerAPI.ShipEngineAPI eng = engines.get(i);
                    if (eng != null && eng.isDisabled()) {
                        localMask |= 1 << i;
                    }
                }
                int wireMask = foePose.engineDisabledMask() & (n == 32 ? -1 : (1 << n) - 1);
                if (localMask != wireMask) {
                    plugin.engineStateMaskForkTicks++;
                    // The fork line carries the same α/β riders as the APPLY lines
                    plugin.foeEngineLane.log("predict v6 engine-state MASK fork @tick=" + simTick
                            + ": local=0x" + Integer.toHexString(localMask)
                            + " wire=0x" + Integer.toHexString(wireMask)
                            + " state=" + localState + "|" + authState
                            + engineRiderTokens(foe, ctrl, localMask, wireMask));
                }
            }
            if (PredictKnobConfig.FOE_ENGINE_APPLY) {
                applyEngineState(foe, ctrl, foePose, simTick, false);
            }
        } catch (Throwable ignore) {
            // presentation-side meter/apply; never break the watch
        }
    }

    /**
     * The foe SYSTEM-WINDOW reconcile lane (see {@link PredictKnobConfig#FOE_SYSTEM_STATE_MODE}). Runs on
     * FRESH snapshots only (the tier is edge-derived idempotent state; the meter counts on the
     * snapshot-tick clock). {@code serverOpen} = the derived authoritative
     * window-open state for this foe (latest ON/OFF edge; {@code null} = none seen yet). Meter
     * ({@code meterCompare}, primary foe only): compare the replica's local {@code getSystem().isActive()}
     * against {@code serverOpen}. Apply (opt-in, all foe replicas): while the window is open and the
     * local system is not yet active and {@code canBeActivated()}, press {@code ShipAPI.useSystem()} —
     * the engine's own held-key buffered-input path (mirror of the server's {@code applySystemBit}; NOT
     * {@code forceState} — a redundant press during cooldown is a harmless no-op the tracker refuses);
     * while the window is CLOSED and the local toggle system is still {@code isOn()} (IN|ACTIVE), press
     * once per mismatched fresh snapshot — the cancel twin folding the replica at the
     * cancel instant instead of its own duration end (engine legality gates the press: non-toggle
     * systems ignore it mid-cycle, OUT never presses since isOn=false).
     * The local engine then derives IN/ACTIVE/OUT/COOLDOWN + effectLevel + stat mods itself. Fail-soft.
     * BOTH presses now route through {@link SpawnSystemGate} — an entity-SPAWNING
     * system's press is skipped (the wire already delivers that ordnance), and its frames leave the
     * agree/disagree population so the gate cannot poison the graded channel.
     * {@code openTick} = the window KEY (the last applied ON edge's simTick;
     * {@link FoeSystemWindowLaw#NO_WINDOW} = no ON ever applied ⇒ "no window"); {@code entityId} =
     * the wire id every row carries as {@code e=} (signed; -1 = none), budgeted per entity by
     * {@code plugin.foeSystemLogBudget}.
     */
    void maintainFoeSystemState(ShipAPI foe, Boolean serverOpen, long openTick, long entityId,
            boolean freshSnapshot, long simTick, boolean meterCompare) {
        if (!PredictKnobConfig.FOE_SYSTEM_METER || !freshSnapshot || foe == null || !foe.isAlive()) {
            return;
        }
        try {
            ShipSystemAPI sys = foe.getSystem();
            if (sys == null) {
                return; // no primary system on this hull — nothing to reconcile
            }
            boolean localActive = sys.isActive();
            // The window memory is KEYED by the ON
            // edge's simTick — a new key resets the latch and CARRIES a still-active previous-window
            // tail (it never latches; FoeSystemWindowLaw class javadoc). ONE row per window, exempt
            // from the per-entity 40 (its own bound = the 64-entity map + the global ceiling).
            FoeSystemWindowLaw.Window win = windowFor(foe);
            if (win.rekey(openTick, localActive) && plugin.foeSystemLogBudget.admitExempt(entityId)) {
                plugin.foeSystemLane.log("predict foe-system window-open e=" + entityId + " on=" + openTick
                        + " @tick=" + simTick + " localActive=" + localActive + " state=" + sys.getState()
                        + " ammo=" + sys.getAmmo() + " carry=" + win.carry + " open=" + serverOpen); // open= the derived authoritative window state
            }
            // Grade a pending IDLE press on the
            // FIRST later fresh frame — still IDLE ⇒ refused (a press that took and already ended
            // reads COOLDOWN/OUT, never IDLE). Two long compares per fresh foe frame.
            if (win.idlePressTick != FoeSystemWindowLaw.NO_WINDOW && simTick > win.idlePressTick) {
                if (sys.getState() == ShipSystemAPI.SystemState.IDLE) {
                    foeSystemIdleRefused++;
                    if (win.idlePressDrive) {
                        foeSystemIdleRefusedDrive++;
                    }
                }
                win.idlePressTick = FoeSystemWindowLaw.NO_WINDOW;
                win.idlePressDrive = false;
            }
            // Classify FIRST — a gated spawn family never activates locally, so grading
            // it would mint a disagree on every open-window frame; those frames ride gatedFrames.
            if (meterCompare && !plugin.spawnGate.noteGatedFrame(sys)) {
                if (serverOpen == null) {
                    plugin.foeSystemLane.framesMissing++;
                } else {
                    plugin.foeSystemLane.framesCarried++;
                    if (localActive == serverOpen.booleanValue()) {
                        plugin.foeSystemLane.agree++;
                    } else {
                        plugin.foeSystemLane.disagree++;
                        if (!win.disagreeLogged) {
                            win.disagreeLogged = true; // ONE row per window (key)
                            if (plugin.foeSystemLogBudget.admit(entityId)) {
                                plugin.foeSystemLane.log("predict v3 foe-system disagree @tick=" + simTick
                                        + ": localActive=" + localActive + " serverOpen=" + serverOpen
                                        + " state=" + sys.getState() + " e=" + entityId);
                            }
                        }
                    }
                }
            }
            // ONE APPLY press per wire
            // window — a short/charge system whose local window ended before the lagged OFF edge was
            // pressed AGAIN by the old level test. The law + its memory advance = FoeSystemWindowLaw.
            boolean authOpen = Boolean.TRUE.equals(serverOpen);
            // main (wave-2 review, regression #3): a TOGGLE system is steady ON under an open window — a
            // local drop (an unconfirmed local overload) is re-pressed to match the wire, never held as a tail.
            boolean toggle = sys.getSpecAPI() != null && sys.getSpecAPI().isToggle();
            int sysAct = PredictKnobConfig.FOE_SYSTEM_APPLY
                    ? FoeSystemWindowLaw.action(authOpen, localActive, authOpen && sys.canBeActivated(),
                            win.pressed && !toggle)
                    : FoeSystemWindowLaw.NONE;
            if (sysAct == FoeSystemWindowLaw.SUPPRESS_REPRESS) {
                foeSystemRepressSuppressed++;
                if (!win.rowLogged) {
                    win.rowLogged = true; // one row per foe-window, never per frame
                    if (plugin.foeSystemLogBudget.admit(entityId)) {
                        plugin.foeSystemLane.log("predict foe-system APPLY re-press SUPPRESSED @tick=" + simTick
                                + " sysId=" + sys.getId() + " state=" + sys.getState()
                                + " (local window ended before the wire OFF; counter continues) e=" + entityId);
                    }
                }
            } else if (sysAct == FoeSystemWindowLaw.PRESS) {
                if (plugin.spawnGate.press(foe, sys, simTick, "APPLY")) { // Spawn family = wire-delivered
                    plugin.localFire.onSystemPress(foe, simTick); // roll (I)(c): per-ship press stamp (upper-bound co-index)
                    plugin.foeSystemApplyPresses++;
                    if (sys.getState() == ShipSystemAPI.SystemState.IDLE) { // Graded next fresh frame
                        win.idlePressTick = simTick;
                        ShipSystemAPI drive = foe.getTravelDrive();
                        win.idlePressDrive = drive != null && drive.isActive();
                    }
                    if (win.carriedOpen && !toggle) {
                        foeSystemCarriedTailPresses++; // The old latch suppressed this one
                    }
                    if (plugin.foeSystemLogBudget.admit(entityId)) {
                        plugin.foeSystemLane.log("predict v3 foe-system APPLY useSystem() @tick=" + simTick
                                + " sysId=" + sys.getId() + " e=" + entityId);
                    }
                }
            } else if (PredictKnobConfig.FOE_SYSTEM_APPLY && Boolean.FALSE.equals(serverOpen) && sys.isOn()) {
                // Cancel TWIN (mirror of the server's applySystemBit low-bit branch):
                // the authoritative window is CLOSED but this replica's toggle system is still
                // IN|ACTIVE (isOn) — press the engine's own useSystem() once per mismatched fresh
                // snapshot so the replica folds (ACTIVE+canBeDeactivated→OUT) instead of burning to
                // its own duration end. Non-toggle systems ignore mid-cycle presses (engine law), and
                // OUT is never pressed (isOn=false there). The SYSTEM_WINDOW OFF edge itself stays at
                // the isActive boundary — seed/audit consumers depend on it — so replica
                // overshoot is bounded by server-out+RTT, which this press then folds early.
                if (plugin.spawnGate.press(foe, sys, simTick, "CANCEL")) { // Gated WITH the apply arm
                    plugin.localFire.onSystemPress(foe, simTick); // The CANCEL twin presses too — censused alike
                    plugin.foeSystemCancelPresses++;
                    if (plugin.foeSystemLogBudget.admit(entityId)) {
                        plugin.foeSystemLane.log("predict v3 foe-system CANCEL useSystem() @tick=" + simTick
                                + " sysId=" + sys.getId() + " e=" + entityId);
                    }
                }
            }
            // Advance the window memory AFTER the action (a
            // press that took latches; the wire CLOSE — FALSE or null — clears the latch + row flag);
            // the carry advances first and a carried tail never latches (Window.advance).
            win.advance(authOpen, localActive, sys.isActive());
        } catch (Throwable t) {
            // presentation-side meter/apply; never break the watch — logs once (catch law:
            // the guard-D window code rides inside this try).
            FallbackAnnounce.announceOnce("foe-system-apply", String.valueOf(t));
        }
    }

    /**
     * The mid-window system SEED for a replica whose window opened before this
     * client had edge knowledge: {@code serverOpen == null} (caller-gated) means no GPEE
     * SYSTEM_WINDOW edge has ever arrived for this entity — a mid-round joiner missed the round's
     * earlier edges entirely, and {@code useSystem()} cannot reach a mid-window phase. One shot per
     * entity per join/round (the caller latches on {@code true}); rides the SAME
     * {@code predict.foeSystemState=apply} opt-in as the edge lane — no new knob. Seeds via the
     * engine's own public {@code ShipSystemAPI.forceState(state, progress)} + {@code setAmmo}
     * — the faithful lane; the local engine then runs the window forward itself.
     *
     * <p>{@code progress} semantics:
     * IN sets elapsed = inDur×p and effectLevel during IN is
     * elapsed/inDur ⇒ p = effectLevel EXACTLY; OUT sets elapsed = outDur×p with effectLevel =
     * 1−elapsed/outDur ⇒ p = 1−effectLevel; COOLDOWN sets elapsed = total−total×p ⇒ p = the
     * REMAINING fraction = cooldownRemaining/getCooldown(); ACTIVE sets elapsed = activeDur×p — the
     * v8 tier carries no active-elapsed (effectLevel pegs 1.0), so p = 0 (window treated as just
     * opened; error bounded by one active duration, and only on mid-ACTIVE joins).
     *
     * @return true when the one shot is CONSUMED (a v8-tier-carrying pose was evaluated — even if
     *         state and ammo already agreed — or this hull has no system / an unknown future state
     *         code arrived); false = no tier on this frame yet, retry on a later fresh one.
     */
    boolean seedFoeSystemState(ShipAPI foe, ServerEntityPose pose, long simTick) {
        if (!PredictKnobConfig.FOE_SYSTEM_APPLY || foe == null || pose == null || !pose.hasSystemState() || !foe.isAlive()) {
            return false;
        }
        try {
            ShipSystemAPI sys = foe.getSystem();
            if (sys == null) {
                return true; // no primary system on this hull — nothing to ever seed
            }
            ShipSystemAPI.SystemState auth = PredictReconcileCombatPlugin.systemStateForCode(pose.systemStateCode());
            if (auth == null) {
                return true; // future/unknown wire code — tolerate-and-latch, never guess a phase
            }
            // The SEED is the same family — a forceState INTO an open window runs the
            // spawn script (ammo + IDLE/OUT/COOLDOWN seeding still run; those phases spawn nothing).
            boolean stateDiffers = sys.getState() != auth && !plugin.spawnGate.gatesSeed(sys, auth);
            int authAmmo = pose.systemAmmo();
            boolean ammoDiffers = authAmmo >= 0 && authAmmo != sys.getAmmo();
            float progress = seedProgressFor(auth, pose.systemEffectLevel(),
                    pose.systemCooldownRemaining(), sys);
            if (stateDiffers) {
                sys.forceState(auth, progress);
                if (sys.isActive()) { // IN/ACTIVE/OUT (engine ShipSystem.isActive)
                    // The seeded activity is THIS window's own — a late ON
                    // at/before this tick must not carry it as a previous-window tail (Window#seededAt).
                    windowFor(foe).seededAt = simTick;
                }
            }
            if (ammoDiffers) {
                sys.setAmmo(authAmmo);
            }
            if (stateDiffers || ammoDiffers) {
                plugin.foeSystemSeeds++;
                PilotWatchLog.info("predict v8 foe-system SEED @tick=" + simTick + " sysId=" + sys.getId()
                        + (stateDiffers ? " forceState(" + auth + ",p=" + progress + ")" : "")
                        + (ammoDiffers ? " setAmmo(" + authAmmo + ")" : "")
                        + " (no edge knowledge yet — D4 one-shot per entity per join/round)");
            }
            return true;
        } catch (Throwable ignore) {
            return true; // fail-soft one-shot: never let a throwing seed retry-spam the lane
        }
    }

    /** The per-replica system-window record, minted ONCE (never per frame;
     *  the get/put extracted so the v8 seed can stamp {@code seededAt} before any ON edge). */
    private FoeSystemWindowLaw.Window windowFor(ShipAPI foe) {
        FoeSystemWindowLaw.Window win = foeSystemWindows.get(foe);
        if (win == null) {
            win = new FoeSystemWindowLaw.Window();
            foeSystemWindows.put(foe, win);
        }
        return win;
    }

    /** {@link #seedFoeSystemState}/{@link FoeCloakLane#repairFoeCloakFromKeyframe} progress derivation (semantics in the
     *  system seed's javadoc; the caller supplies its own tier's effectLevel/cooldownRemaining
     *  scalars — v8 primary-system or v10 cloak — the MATH is shared);
     *  clamped to [0,1]. Package-visible: the PRCP own-cloak Fix-3
     *  apply forces the OWN cloak with the same derivation. */
    static float seedProgressFor(ShipSystemAPI.SystemState auth, float authEffectLevel,
            float authCooldownRemaining, ShipSystemAPI sys) {
        float p;
        switch (auth) {
            case IN:
                p = authEffectLevel;
                break;
            case OUT:
                p = 1f - authEffectLevel;
                break;
            case COOLDOWN:
                float total = sys.getCooldown();
                p = total > 0f ? authCooldownRemaining / total : 0f;
                break;
            default:
                p = 0f; // ACTIVE (no elapsed on the wire) + IDLE
        }
        if (!Float.isFinite(p)) {
            return 0f;
        }
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    /** Wire bits 0..6 = the 7 command booleans; bit 7 = {@link #MANEUVER_BIT_ENGINE_FLARE}. */
    static final int MANEUVER_COMMAND_BITS = 0x7F;
    /** Server {@code ShipManeuverCommandSnapshot.MANEUVER_BIT_ENGINE_FLARE}. */
    static final int MANEUVER_BIT_ENGINE_FLARE = 7;

    /**
     * The schema-v7 foe MANEUVER-COMMAND lane (see {@link PredictKnobConfig#FOE_MANEUVER_MODE}), the foe
     * engine-plume fix. Unlike the sibling lanes this lane's APPLY runs EVERY FRAME, not just on
     * fresh snapshots: the engine clears each ship's command queue at the top of every
     * {@code processCommands}, so plume flare/glow/contrail state (and the engine-sound loop that
     * rides glow level) only presents while commands re-arrive each frame — a fresh-frames-only
     * apply would strobe at snapshot cadence. The meter still counts on fresh frames only
     * (snapshot-tick clock) and for the primary foe only ({@code meterCompare}
     * — per-entity compare noise would swamp the capped log). Apply re-issues
     * {@code ShipAPI.giveCommand} per set carried bit (bits 0..6 via {@link PredictReconcileCombatPlugin#MANEUVER_COMMAND_FOR_BIT};
     * the server's own UP-replay lane proves the enqueue) — the replica's own controller then flares
     * plumes, emits contrails, drives engine sound, and integrates genuine between-snapshot thrust
     * prediction (pose reconcile stays authoritative). Fail-soft like every reconcile lane.
     *
     * <p><b>Bit 7 = the server's real engine-flare flag:</b>
     * a command-less glow (vanilla's drone follower pokes the glow directly
     * — the ravelin launch-out plume) has no command
     * bit, so the server also carries the glow's own "flaring now" getter as the byte's free
     * top bit. While it is set and bit 0 is clear this lane replays vanilla's exact pair on
     * the same per-frame cadence, inside the same apply guard: {@code forceShowAccelerating()}
     * (API surface; it only UN-BLOCKS a show) + {@code
     * giveCommand(ACCELERATE)}, the API-legal path to {@code showAccelerating(dt)}
     * that actually flares. The thrust side-effect is the one every
     * replica's bit-0 re-issue already lives with (bounded by the per-snapshot velocity
     * re-pin); on the launch-out leg facing = motion direction. The meter compares the 7
     * command bits only ({@link #MANEUVER_COMMAND_BITS}) — the local controller has no
     * flare boolean to mirror. Own predicted ship: not driven by this lane (no own-ship effect).
     */
    void maintainFoeManeuver(ShipAPI foe, ServerEntityPose pose, boolean freshSnapshot,
            long simTick, boolean meterCompare) {
        if (!PredictKnobConfig.FOE_MANEUVER_METER || foe == null || pose == null || !foe.isAlive()) {
            return;
        }
        try {
            if (!pose.hasManeuverCommandMask()) {
                if (freshSnapshot && meterCompare) {
                    plugin.foeManeuverLane.framesMissing++;
                }
                return;
            }
            int authMask = pose.maneuverCommandMask();
            if (freshSnapshot && meterCompare) {
                plugin.foeManeuverLane.framesCarried++;
                ShipEngineControllerAPI ctrl = foe.getEngineController();
                if (ctrl != null) {
                    int localMask = 0;
                    if (ctrl.isAccelerating())          { localMask |= (1 << PredictReconcileCombatPlugin.BIT_ACCELERATE); }
                    if (ctrl.isAcceleratingBackwards()) { localMask |= (1 << PredictReconcileCombatPlugin.BIT_ACCELERATE_BACKWARDS); }
                    if (ctrl.isDecelerating())          { localMask |= (1 << PredictReconcileCombatPlugin.BIT_DECELERATE); }
                    if (ctrl.isTurningLeft())           { localMask |= (1 << PredictReconcileCombatPlugin.BIT_TURN_LEFT); }
                    if (ctrl.isTurningRight())          { localMask |= (1 << PredictReconcileCombatPlugin.BIT_TURN_RIGHT); }
                    if (ctrl.isStrafingLeft())          { localMask |= (1 << PredictReconcileCombatPlugin.BIT_STRAFE_LEFT); }
                    if (ctrl.isStrafingRight())         { localMask |= (1 << PredictReconcileCombatPlugin.BIT_STRAFE_RIGHT); }
                    if (localMask == (authMask & MANEUVER_COMMAND_BITS)) {
                        plugin.foeManeuverLane.agree++;
                    } else {
                        plugin.foeManeuverLane.disagree++;
                        plugin.foeManeuverLane.log("predict v7 foe-maneuver disagree @tick=" + simTick
                                + ": local=0x" + Integer.toHexString(localMask)
                                + " auth=0x" + Integer.toHexString(authMask));
                    }
                }
            }
            if (PredictKnobConfig.FOE_MANEUVER_APPLY && authMask != 0) {
                for (int bit = 0; bit < PredictReconcileCombatPlugin.MANEUVER_COMMAND_FOR_BIT.length; bit++) {
                    if ((authMask & (1 << bit)) != 0) {
                        foe.giveCommand(PredictReconcileCombatPlugin.MANEUVER_COMMAND_FOR_BIT[bit], null, 0);
                        plugin.foeManeuverApplyCommands++;
                    }
                }
                // Bit 7 = server flare flag with no ACCELERATE command behind it
                // (a command-less glow) — replay vanilla's pair.
                if ((authMask & (1 << MANEUVER_BIT_ENGINE_FLARE)) != 0
                        && (authMask & (1 << PredictReconcileCombatPlugin.BIT_ACCELERATE)) == 0) {
                    ShipEngineControllerAPI ctrl = foe.getEngineController();
                    if (ctrl != null) {
                        ctrl.forceShowAccelerating();
                    }
                    foe.giveCommand(ShipCommand.ACCELERATE, null, 0);
                    plugin.foeManeuverApplyCommands++;
                }
            }
        } catch (Throwable ignore) {
            // presentation-side meter/apply; never break the watch
        }
    }

    /**
     * The foe FLUX apply lane (see {@link PredictKnobConfig#FOE_FLUX_MODE}), the R-screen/hover
     * target-flux fix. Like {@link #maintainFoeManeuver} the APPLY runs EVERY FRAME, not just on
     * fresh snapshots — but as an idempotent value pin (the mirrorHull cadence), not a command
     * re-issue: the replica generates ~no flux itself, so between snapshots the pinned value
     * simply holds. Apply writes the engine's own setters curr-first ({@code setCurrFlux} then
     * {@code setHardFlux}); {@code getFluxLevel}/{@code getMinFlux} — the values
     * the vanilla reticle bar fill + 3px hard-flux tick read — are the tracker's own
     * derivations. The meter samples fresh frames only (primary foe): the local-vs-authoritative
     * {@code fluxLevel} delta, max increases pinned via the capped lane log (the
     * {@link PredictKnobConfig#FLUX_PIN_MIN_STEP} discipline). Fail-soft like every reconcile lane.
     */
    void maintainFoeFlux(ShipAPI foe, ServerEntityPose pose, boolean freshSnapshot,
            long simTick, boolean meterCompare) {
        if (!PredictKnobConfig.FOE_FLUX_METER || foe == null || pose == null || !foe.isAlive()) {
            return;
        }
        try {
            //
            // The wire carries CR for EVERY ship (AWSS SHIP_CR tier
            // v14 / AWSD keyframe crU8) but only the own ship consumed it (maintainOwnCr) — a foe
            // replica kept its CrDeploySeam deploy value forever while the server's Construction
            // Swarm spent 2–10 % per built hull and
            // Reclamation swarms refunded it. Rides the foeFlux knob (the
            // same R-hover readout family); the own lane's u8 change-guard (1290 (92)) keeps a
            // same-cell dequant from minting a phantom cr_effect; setCurrentCR alone drives the
            // readout + CR malfunction stats, the deploy clock stays local (no PPT pin for foes).
            // The pin stays every-frame (the lane's idempotent
            // cadence) but the COUNTERS are fresh-gated like maintainOwnCr's — a same-cell frame
            // between snapshots is not a "quant skip" and a re-pin of a locally-decayed Remnant
            // cell is not a wire step; `predict_foe_cr_pin_frames` = wire CR steps applied.
            if (pose.hasCr()) {
                float wireCr = pose.cr();
                if (Math.round(foe.getCurrentCR() * 255f) != Math.round(wireCr * 255f)) {
                    if (PredictKnobConfig.FOE_FLUX_APPLY) {
                        foe.setCurrentCR(wireCr);
                    }
                    if (freshSnapshot) {
                        plugin.foeCrPinFrames++;
                    }
                } else if (freshSnapshot) {
                    plugin.foeCrQuantSkips++;
                }
            } else if (freshSnapshot) {
                plugin.foeCrMissing++;
            }
            FluxTrackerAPI ft = foe.getFluxTracker();
            if (ft == null) {
                return;
            }
            if (!pose.hasFlux()) {
                if (freshSnapshot && meterCompare) {
                    plugin.foeFluxLane.framesMissing++;
                }
                return;
            }
            if (freshSnapshot && meterCompare) {
                plugin.foeFluxLane.framesCarried++;
                // Rider: a zero-capacity replica (armor module / station
                // segment) reads getFluxLevel() = 0/0 = NaN — pin level 0 like the server's
                // EngineCombatBinding fix so the meter samples it instead of drifting its
                // denominator on the isFinite skip.
                float localFoeLevel = ft.getMaxFlux() > 0f ? ft.getFluxLevel() : 0f;
                float d = Math.abs(localFoeLevel - pose.fluxLevel());
                if (Float.isFinite(d)) {
                    plugin.foeFluxSamples++;
                    plugin.foeFluxSum += d;
                    if (d >= plugin.foeFluxMaxDelta + PredictKnobConfig.FLUX_PIN_MIN_STEP) {
                        plugin.foeFluxLane.log("predict foe-flux level-delta new max " + d
                                + " @tick=" + simTick + ": local=" + localFoeLevel
                                + " auth=" + pose.fluxLevel());
                    }
                    if (d > plugin.foeFluxMaxDelta) {
                        plugin.foeFluxMaxDelta = d;
                    }
                }
            }
            if (PredictKnobConfig.FOE_FLUX_APPLY) {
                // The dead band + the
                // vent-drain guard (FoeFluxPinLaw). The meters above were computed BEFORE this skip
                // (never conceal); hard flux follows the curr decision (skip both or pin both). The
                // band is on the WIRE's grid (pose.maxFlux(), the AWSD dequant's multiplier).
                boolean authVenting = pose.hasFluxFlags()
                        && (pose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_VENTING) != 0;
                int pinAct = FoeFluxPinLaw.action(ft.getCurrFlux(), pose.currFlux(), ft.getHardFlux(),
                        pose.hasHardFlux() ? pose.hardFlux() : Float.NaN, pose.maxFlux(),
                        ft.isVenting(), authVenting); // the hard axis rides the band too
                if (pinAct == FoeFluxPinLaw.PIN_SKIP_BAND) {
                    foeFluxPinBandSkips++;
                    return;
                }
                if (pinAct == FoeFluxPinLaw.PIN_SKIP_VENT_DRAIN) {
                    foeFluxPinVentDrainSkips++;
                    return;
                }
                ft.setCurrFlux(pose.currFlux());
                if (pose.hasHardFlux()) {
                    ft.setHardFlux(pose.hardFlux());
                }
                plugin.foeFluxApplyFrames++;
                if (PredictReconcileCombatPlugin.settleBoostClockAfterFluxPin(foe, ft, pose.currFlux())) {
                    plugin.foeFluxBoostClockZeros++;
                }
            }
        } catch (Throwable t) {
            // presentation-side meter/apply; never break the watch — logs once (catch law:
            // the guard-B band code rides inside this try).
            FallbackAnnounce.announceOnce("foe-flux-apply", String.valueOf(t));
        }
    }

    /**
     * The foe FLUX-FLAGS reconcile lane (see {@link PredictKnobConfig#FOE_FLUXFLAGS_MODE}), the
     * BUG-A fix. Meter (fresh frames, BOTH call sites — the shield/cloak-lane disjoint-set
     * precedent): the replica tracker's {@code isOverloaded()}/{@code isVenting()} vs the carried
     * {@code FLUX_FLAG_OVERLOADED}/{@code FLUX_FLAG_VENTING}. Apply: mismatch-edge slaving through
     * the engine's OWN verbs, immediate in both directions — a foe replica's state never feeds the
     * UP-intent sampler, so there is no echo hazard (contrast {@link PredictReconcileCombatPlugin#maintainOwnFlux}). Engine
     * facts this leans on:
     * {@code beginOverloadWithTotalBaseDuration} is STATE-ONLY (no-op while venting or
     * already overloaded) — the vanilla sound + "Overloaded!" floaty are CALLER-side one-shots,
     * so the rise edge plays them explicitly (the per-frame overload loop-sound rides
     * the tracker's own advance); {@code ventFlux} is the native vent path with its own start
     * FX (no-op if overloaded / ventRate≤0 / already venting);
     * {@code stopOverload}/{@code stopVenting} are unconditional state writes.
     * The engine forbids overload-while-venting and vent-while-overloaded, so the auth flags are
     * mutually exclusive; overload edges are handled first and a venting replica is
     * {@code stopVenting}-ed before begin (begin would no-op while venting).
     */
    void maintainFoeFluxFlags(ShipAPI foe, ServerEntityPose pose, boolean freshSnapshot,
            long simTick, boolean meterCompare) {
        if (!PredictKnobConfig.FOE_FLUXFLAGS_METER || foe == null || pose == null || !foe.isAlive()) {
            return;
        }
        try {
            FluxTrackerAPI ft = foe.getFluxTracker();
            if (ft == null) {
                return;
            }
            // The remote lash-overload colouring's per-frame half (a held floaty's
            // release + the colour reset once the overload ended) — every frame, before the edges,
            // ahead of the flags gate so a flag-less pose can never strand a painted hull.
            plugin.arcHitLane.maintainRemoteLashOverload(foe, ft, pose.entityId());
            if (!pose.hasFluxFlags()) {
                if (freshSnapshot && meterCompare) {
                    plugin.foeFluxFlagsLane.framesMissing++;
                }
                return;
            }
            boolean authOvl = (pose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_OVERLOADED) != 0;
            boolean authVent = (pose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_VENTING) != 0;
            // The phase gate reads the PRIOR fresh frame's collision truth (the cloak
            // drops ≤ 4 ticks after a lash overload, so the rise frame may already read unphased)
            // OR this frame's — recorded here, before the edges, on the same flux-flags byte.
            boolean phasedNow = pose.isPhasedBit();
            boolean phasedPrior = plugin.arcHitLane.rememberPhased(foe, phasedNow, freshSnapshot);
            if (freshSnapshot && meterCompare) {
                plugin.foeFluxFlagsLane.framesCarried++;
                if (ft.isOverloaded() == authOvl && ft.isVenting() == authVent) {
                    plugin.foeFluxFlagsLane.agree++;
                } else {
                    plugin.foeFluxFlagsLane.disagree++;
                    // The ring sees EVERY disagree (coalesced per foe streak)
                    // BEFORE the capped live line; the live line gains a trailing entity= (spec F12:
                    // appended at the end, no parser reads past `auth=`).
                    fluxDisagreeRing.recordOrExtend(simTick, 0, pose.entityId(),
                            (ft.isOverloaded() ? FLUX_RING_OVL_LOCAL : 0)
                            | (authOvl ? FLUX_RING_OVL_AUTH : 0)
                            | (ft.isVenting() ? FLUX_RING_VENT_LOCAL : 0)
                            | (authVent ? FLUX_RING_VENT_AUTH : 0));
                    if (plugin.foeFluxFlagsLane.canLog()) {
                        plugin.foeFluxFlagsLane.log("predict foe-fluxflags disagree @tick=" + simTick
                                + ": overloaded local=" + ft.isOverloaded() + " auth=" + authOvl
                                + " venting local=" + ft.isVenting() + " auth=" + authVent
                                + " entity=" + pose.entityId());
                    }
                }
            }
            if (!PredictKnobConfig.FOE_FLUXFLAGS_APPLY) {
                return;
            }
            // ONE begin per wire overload
            // window — the old level test re-began (sound + floaty) when the local overload expired
            // before the lagged wire fall. The law + its memory advance = OverloadWindowLaw.
            FoeCloakLane.CloakEdgeMemory ovlMem = cloak.cloakMemoryFor(foe);
            int ovlAct = OverloadWindowLaw.action(authOvl, ft.isOverloaded(), ovlMem.ovlBegunThisWindow);
            if (ovlAct == OverloadWindowLaw.OVL_ACT_SUPPRESS_RESTART) {
                foeFluxFlagsOverloadRestartSuppressed++;
                // The wire STILL says OVERLOADED — hold the
                // engine's own state SILENTLY (beginOverloadWithTotalBaseDuration is state-only;
                // the sound + floaty are the caller one-shots this branch exists to suppress). A tail that left
                // the twin idle stranded ovlWireConfirmed=TRUE (only the wire-END branch below clears it), so the
                // next local lead was stopped on its first frame — and showed the twin un-overloaded vs authority.
                if (ft.isVenting()) {
                    ft.stopVenting();
                }
                ft.beginOverloadWithTotalBaseDuration(PredictKnobConfig.SLAVE_OVERLOAD_BASE_SECONDS);
                boolean[] g = guardMemoryFor(foe);
                if (!g[2]) {
                    g[2] = true; // one row per foe-window, never per frame
                    plugin.foeFluxFlagsLane.log("predict foe-fluxflags overload restart SUPPRESSED @tick="
                            + simTick + " entity=" + pose.entityId()
                            + " (local overload expired before the wire fall; held, counter continues)");
                }
            } else if (ovlAct == OverloadWindowLaw.OVL_ACT_BEGIN) {
                if (ft.isVenting()) {
                    ft.stopVenting(); // begin no-ops while venting
                }
                ft.beginOverloadWithTotalBaseDuration(PredictKnobConfig.SLAVE_OVERLOAD_BASE_SECONDS);
                ft.playOverloadSound();
                // A wire LASH arc on this PHASED hull inside the lookback ⇒ the
                // Overseer's phase-field disruption (bright EMP overload
                // colour + its own floaty, reset when the overload ends); phased but no arc yet ⇒ hold
                // the plain floaty for a trailing arc (the flag can beat the hit by one tick across a
                // snapshot edge); unphased ⇒ vanilla's plain floaty now (vanilla's
                // lash overload exists only when the target is phased — an unphased hull that
                // overloads inside the window did so on its own flux).
                boolean phaseHull = phasedPrior || phasedNow;
                boolean arcInWindow = plugin.arcHitLane.lashHitRecently(foe);
                boolean lashed = phaseHull && arcInWindow;
                if (lashed) {
                    plugin.arcHitLane.paintRemoteLashOverload(foe, ft, false, pose.entityId());
                } else if (phaseHull) {
                    plugin.arcHitLane.holdOverloadFloaty(foe);
                } else if (arcInWindow) {
                    plugin.arcHitLane.noteUnphasedLashRise(foe, ft, pose.entityId());
                } else {
                    ft.showOverloadFloatyIfNeeded(); // plain "Overloaded!", vanilla's own gate + text
                }
                plugin.foeFluxFlagsOverloadBegins++;
                plugin.foeFluxFlagsLane.log("predict foe-fluxflags overload BEGIN @tick=" + simTick
                        + " entity=" + pose.entityId() + (lashed ? " lash=PHASE-FIELD-DISRUPTION" : "")
                        + " phased=" + (phasedPrior ? "prior" : "-") + "/" + (phasedNow ? "now" : "-"));
                FoeCloakLane.CloakEdgeMemory mem = cloak.cloakMemoryFor(foe);
                mem.ovlWireConfirmed = true; // wire-begun: its end below is a plain wire end
                mem.localOvlRiseTick = -1L;
            } else if (authOvl) {
                // Steady wire overload on an overloaded twin: a LOCAL rise the wire
                // has now confirmed (the lead resolved inside the grace) is a wire overload
                // from here — and so is one that rose on the wire's own tick with no lead at all.
                FoeCloakLane.CloakEdgeMemory mem = cloak.cloakMemoryFor(foe);
                if (!mem.ovlWireConfirmed) {
                    if (mem.localOvlRiseTick >= 0L) {
                        plugin.foeFluxFlagsLane.log("predict foe-fluxflags overload LEAD-CONFIRMED @tick="
                                + simTick + " entity=" + pose.entityId()
                                + " led=" + (simTick - mem.localOvlRiseTick));
                    }
                    mem.ovlWireConfirmed = true;
                    mem.localOvlRiseTick = -1L;
                }
            } else if (ft.isOverloaded()) {
                FoeCloakLane.CloakEdgeMemory mem = cloak.cloakMemoryFor(foe);
                if (mem.ovlWireConfirmed) {
                    ft.stopOverload(); // the wire overload ended
                    plugin.foeFluxFlagsOverloadStops++;
                    mem.ovlWireConfirmed = false;
                } else {
                    cloak.maintainLocalOverloadLead(foe, pose, ft, mem, simTick);
                }
            }
            // Advance the overload-window memory AFTER the
            // action (a begin that took latches; the wire fall clears it + the one-row log slot).
            // Every call, like the vent law's advance below — a begin on a non-fresh frame counts.
            ovlMem.ovlBegunThisWindow = OverloadWindowLaw.nextBegun(authOvl, ft.isOverloaded(),
                    ovlMem.ovlBegunThisWindow);
            if (!authOvl) {
                boolean[] g = foeGuardMemory.get(foe); // no allocation on the common idle frame
                if (g != null) {
                    g[2] = false;
                }
            }
            // ONE native begin per wire vent window. The per-frame flux
            // pin restores the wire's residue after the LOCAL vent drains it to 0 and ends itself,
            // so the old "auth up + tracker idle ⇒ ventFlux()" rule restarted the vent (+ start FX)
            // every tail frame until the wire END (vent_begins +8/+14 inside one
            // serve vent's END window). The law + its memory advance = FoeVentWindowLaw.
            FoeCloakLane.CloakEdgeMemory ventMem = cloak.cloakMemoryFor(foe);
            switch (FoeVentWindowLaw.action(authVent, ft.isVenting(), ft.isOverloaded(),
                    ventMem.ventWindowStarted)) {
                case FoeVentWindowLaw.VENT_ACT_BEGIN:
                    ft.ventFlux(); // native path incl. vent-start FX; retries next frame if refused
                    plugin.foeFluxFlagsVentBegins++;
                    break;
                case FoeVentWindowLaw.VENT_ACT_SUPPRESS_RESTART:
                    plugin.foeFluxFlagsVentRestartSuppressed++; // the drained tail: hold, no re-flash
                    if (!ventMem.ventTailLogged) {
                        // ONE sizing row per window — the wire flux the tail began at
                        // (a tail longer than the wire lag = a frame hitch drained the pin early).
                        ventMem.ventTailLogged = true;
                        fluxTailRing.record(simTick, 0, pose.entityId(), Integer.MIN_VALUE,
                                Integer.MIN_VALUE, Long.MIN_VALUE, pose.currFlux(), Float.NaN, Float.NaN);
                        // The TAIL row has its OWN run budget —
                        // the disagree flood can no longer starve it out of the shared 400 lines.
                        if (plugin.foeFluxFlagsVentTailLogLines < VENT_TAIL_LOG_MAX) {
                            plugin.foeFluxFlagsVentTailLogLines++;
                            PilotWatchLog.info("predict foe-fluxflags vent TAIL @tick=" + simTick
                                    + " entity=" + pose.entityId() + " pinnedFlux=" + pose.currFlux()
                                    + " (local vent drained; held until the wire END, no restart)"
                                    + (plugin.foeFluxFlagsVentTailLogLines == VENT_TAIL_LOG_MAX
                                            ? " [vent TAIL log cap reached; vent_tail_rows_dropped counts on]"
                                            : ""));
                        } else {
                            plugin.foeFluxFlagsVentTailRowsDropped++;
                        }
                    }
                    break;
                case FoeVentWindowLaw.VENT_ACT_STOP:
                    ft.stopVenting();
                    plugin.foeFluxFlagsVentStops++;
                    break;
                default:
                    break;
            }
            ventMem.ventWindowStarted = FoeVentWindowLaw.nextStarted(authVent, ft.isVenting(),
                    ventMem.ventWindowStarted);
            if (!authVent) {
                ventMem.ventTailLogged = false; // the window closed: the next tail logs its own row
            }
        } catch (Throwable t) {
            // presentation-side meter/apply; never break the watch — logs once (catch law:
            // the guard-E overload window code rides inside this try).
            FallbackAnnounce.announceOnce("foe-fluxflags-apply", String.valueOf(t));
        }
    }

    /**
     * Flush both foe-fluxflags rings as
     * {@code predict foe-fluxflags flag-ring [where] marker #N} rows (disagree ring, then the vent
     * TAIL ring). Called by the PRCP F9 owner-marker hook and at teardown, the SAME two seams as
     * {@link FractureFamilyCoordinator#dumpAdoptRing} (its wrapper copied from that method). WHY: a late
     * F9 must still name which foe disagreed after the shared live budget ran out (spec F9). Never
     * throws: a failure logs ONCE per run.
     */
    void dumpFluxRings(String where, long markerNo, long simTick) {
        try {
            for (String row : fluxDisagreeRing.dumpRows(where, markerNo, simTick)) {
                PilotWatchLog.info(row);
            }
            for (String row : fluxTailRing.dumpRows(where, markerNo, simTick)) {
                PilotWatchLog.info(row);
            }
        } catch (Throwable t) {
            if (!fluxRingDumpFailLogged) {
                fluxRingDumpFailLogged = true;
                PilotWatchLog.info("predict foe-fluxflags flag-ring dump FAILED "
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + " (logged once)");
            }
        }
    }

    /**
     * The foe SHIELD reconcile lane (see {@link PredictKnobConfig#FOE_SHIELD_MODE}), the seat-2 boss
     * shield fix. Meter (fresh frames, BOTH call sites — from seat 2 the boss is a REGISTRY
     * remote, never the primary foe; a test run measured carried=0/agree=0 there): the replica's
     * {@code getShield().isOn()} vs the pose-carried {@code FLAG_SHIELD_ON}. Apply is the
     * unified state-gated press law, TYPE-INDEPENDENT — the engine pins EVERY
     * shield's charge tracker to FRONT flip semantics (engine code hardcodes
     * {@code ShieldType.FRONT}; the tracker's OMNI-LEVEL branch is dead code), so a
     * command-present frame FLIPS desired, command-free frames
     * KEEP state, and raises start only from IDLE. Press
     * {@code giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK, aim, 0)} iff {@code authOn ? (!isOn()
     * && getActiveArc()==0f) : isOn()} — the vanilla shield AI's own gating
     * (lower gated {@code isOn()}, raise gated idle; idle ⇔
     * that exact predicate — unfold is 0 only at IDLE, frozen &gt;0 through retraction) driven
     * by the authoritative state. Supersedes the falsified earlier law split: the per-frame OMNI
     * hold strobed desired every frame, and per-frame FRONT edge-toggling
     * strobed the fold fader (27k toggles, the replica never unfolded).
     * PHASE GUARD: cloak hulls skipped — the SAME verb phase-toggles there. aim: the
     * wire-carried shield facing when known (a point projected from the replica's
     * location along the authoritative facing, so the engine's own OMNI tracking turns toward
     * the server's shield), else {@code getMouseTarget()}, null → {@code getLocation()} (the
     * engine's own no-command param default). Shield-facing apply (OMNI
     * only — FRONT facing is structurally the ship facing the pose lane already writes):
     * {@code forceFacing(u8 * SHIELD_FACING_STEP_DEG)} every frame while the replica shield is
     * on — the engine's own OMNI shield AI drives facing through the same public seam
     * ({@code forceFacing}), so this is the native path,
     * not a stat hack; facing UNKNOWN = KEEP-LAST-FORCED via {@link PredictReconcileCombatPlugin#lastShieldFacingU8}.
     * Facing is consumed only under {@code FLAG_HAS_SHIELD} (shieldless records mint 0).
     */
    void maintainFoeShield(ShipAPI foe, ServerEntityPose pose, boolean freshSnapshot,
            long simTick, boolean meterCompare) {
        if (!PredictKnobConfig.FOE_SHIELD_METER || foe == null || pose == null || !foe.isAlive()) {
            return;
        }
        try {
            if (!pose.hasFluxFlags()) {
                if (freshSnapshot && meterCompare) {
                    plugin.foeShieldLane.framesMissing++;
                }
                return;
            }
            boolean authOn = (pose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_SHIELD_ON) != 0;
            ShieldAPI shield = foe.getShield();
            if (freshSnapshot && meterCompare) {
                plugin.foeShieldLane.framesCarried++;
                boolean localOn = shield != null && shield.isOn();
                if (localOn == authOn) {
                    plugin.foeShieldLane.agree++;
                } else {
                    plugin.foeShieldLane.disagree++;
                    plugin.foeShieldLane.log("predict foe-shield disagree @tick=" + simTick
                            + ": local=" + localOn + " auth=" + authOn
                            + (shield == null ? " (replica shieldless)" : ""));
                }
            }
            // The wire-carried shield facing, KEEP-LAST-FORCED. Only meaningful
            // under FLAG_HAS_SHIELD (shieldless records mint 0 — never consume it bare).
            Integer facingU8 = null;
            if ((pose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_HAS_SHIELD) != 0) {
                if (pose.hasShieldFacing()) {
                    facingU8 = pose.shieldFacingU8();
                    plugin.lastShieldFacingU8.put(pose.entityId(), facingU8);
                } else {
                    facingU8 = plugin.lastShieldFacingU8.get(pose.entityId());
                }
            }
            // Phase guard STAYS (verb partition, comment-pinned mutual exclusion):
            // on phase-cloak hulls the SAME verb toggles the cloak — maintainFoeCloak OWNS it there.
            if (PredictKnobConfig.FOE_SHIELD_APPLY && shield != null && foe.getPhaseCloak() == null) {
                boolean localOn = shield.isOn();
                boolean omni = shield.getType() == ShieldType.OMNI;
                // Unified state-gated press law, TYPE-INDEPENDENT (the engine pins
                // every shield's tracker to FRONT flip semantics: a command-present frame FLIPS
                // desired, command-free frames KEEP state; raises start only from IDLE):
                // raise only at idle (!isOn && activeArc==0 ⇔ IDLE — the unfold
                // fraction stays frozen >0 through the 0.35s retraction), lower only while on,
                // NEVER press mid-retraction (a swallowed press self-heals at the next idle frame).
                if (authOn ? (!localOn && shield.getActiveArc() == 0f) : localOn) {
                    Vector2f aim = null;
                    if (facingU8 != null) {
                        // aim-from-wire: a point 1000 su out along the authoritative facing
                        // (the engine's shield advance only reads the aim DIRECTION from the
                        // ship's location — never degenerate, unlike the getLocation fallback).
                        double rad = Math.toRadians(
                                facingU8 * ServerEntityPose.SHIELD_FACING_STEP_DEG);
                        Vector2f loc = foe.getLocation();
                        aim = new Vector2f(loc.x + (float) (Math.cos(rad) * 1000.0),
                                loc.y + (float) (Math.sin(rad) * 1000.0));
                    }
                    if (aim == null) {
                        aim = foe.getMouseTarget();
                    }
                    if (aim == null) {
                        aim = foe.getLocation();
                    }
                    // press-shadow: pre-size read BEFORE the press (self-swallowing
                    // — a diagnostic failure must never cost the press), sample line right after.
                    boolean shadow = plugin.pressShadow.shouldSample(pose.entityId());
                    int preCmds = shadow ? plugin.pressShadow.commandsSize(foe) : -1;
                    foe.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, aim, 0);
                    plugin.foeShieldApplyCommands++;
                    if (shadow) {
                        plugin.pressShadow.samplePress(foe, pose, simTick, preCmds, shield, omni);
                    }
                }
                // Shield facing apply, OMNI only (FRONT facing is structurally the ship facing the
                // pose lane already writes): pin the replica shield to the authoritative facing
                // while on — the engine's own OMNI AI drives facing through this same public
                // seam every frame, so per-frame re-force is the native cadence.
                if (omni && facingU8 != null && shield.isOn()) {
                    float facingDeg = facingU8 * ServerEntityPose.SHIELD_FACING_STEP_DEG;
                    shield.forceFacing(facingDeg);
                    plugin.foeShieldFacingForced++;
                    if (!plugin.foeShieldFacingForcedLogged) {
                        plugin.foeShieldFacingForcedLogged = true;
                        plugin.foeShieldLane.log("predict omni foe-shield facing FIRST force @tick="
                                + simTick + " entity=" + pose.entityId() + " u8=" + facingU8
                                + " deg=" + facingDeg);
                    }
                }
            }
        } catch (Throwable ignore) {
            // presentation-side meter/apply; never break the watch
        }
    }


    /**
     * The foe AUTOFIRE reconcile lane (see {@link PredictKnobConfig#FOE_AUTOFIRE_MODE}), the enemy
     * target reticle "AUTOFIRE:" rows fix. Per-group authoritative state is DERIVED from the
     * carried per-mount {@code FLAG_AUTOFIRE} bits via the LOCAL replica's own mount→group
     * mapping (both sides instantiate the same variant — the v4 mount tier's order contract):
     * any carried mount with the bit ⇒ its group is ON; a group with NO carried mount stays
     * UNKNOWN and is left alone. Runs on FRESH snapshots only, and the apply is
     * DIFF/edge-triggered — {@code giveCommand(TOGGLE_AUTOFIRE, null, g)} FLIPS state, so an
     * unconditional re-issue would strobe; this is the exact DOWN mirror of the
     * server's UP {@code EngineCombatBinding.applyAutofireMask}, including its 31-group cap.
     * Display-state only: the standing per-frame {@code setHoldFireOneFrame} gate keeps an
     * autofire-ON replica group from actually discharging. Fail-soft like every
     * reconcile lane.
     */
    void maintainFoeAutofire(ShipAPI foe, ServerEntityPose pose, boolean freshSnapshot,
            long simTick, boolean meterCompare) {
        if (!PredictKnobConfig.FOE_AUTOFIRE_METER || !freshSnapshot || foe == null || pose == null || !foe.isAlive()) {
            return;
        }
        try {
            if (!pose.hasWeaponMounts()) {
                if (meterCompare) {
                    plugin.foeAutofireLane.framesMissing++;
                }
                return;
            }
            List<WeaponAPI> weapons = plugin.weaponLists.weapons(foe); // per-frame cache
            List<WeaponGroupAPI> groups = foe.getWeaponGroupsCopy();
            if (weapons == null || groups == null || groups.isEmpty()) {
                return;
            }
            int cap = Math.min(groups.size(), 31); // the server applyAutofireMask group cap
            int knownMask = 0;  // groups with >=1 carried mount — only these have derived auth state
            int authOnMask = 0; // groups the authoritative hull says are autofiring
            for (cc.starsector.client.net.ServerWeaponMountState mount : pose.weaponMounts()) {
                int idx = mount.mountIndex();
                if (idx < 0 || idx >= weapons.size()) {
                    continue;
                }
                WeaponAPI weapon = weapons.get(idx);
                if (weapon == null) {
                    continue;
                }
                WeaponGroupAPI wg = foe.getWeaponGroupFor(weapon);
                int g = wg == null ? -1 : groups.indexOf(wg);
                if (g < 0 || g >= cap) {
                    continue;
                }
                knownMask |= (1 << g);
                if (mount.autofiring()) {
                    authOnMask |= (1 << g);
                }
            }
            if (knownMask == 0) {
                if (meterCompare) {
                    plugin.foeAutofireLane.framesMissing++;
                }
                return;
            }
            if (meterCompare) {
                plugin.foeAutofireLane.framesCarried++;
            }
            for (int g = 0; g < cap; g++) {
                if ((knownMask & (1 << g)) == 0) {
                    continue;
                }
                WeaponGroupAPI wg = groups.get(g);
                if (wg == null) {
                    continue;
                }
                boolean auth = (authOnMask & (1 << g)) != 0;
                boolean local = wg.isAutofiring();
                if (local == auth) {
                    if (meterCompare) {
                        plugin.foeAutofireLane.agree++;
                    }
                    continue;
                }
                if (meterCompare) {
                    plugin.foeAutofireLane.disagree++;
                    plugin.foeAutofireLane.log("predict foe-autofire disagree @tick=" + simTick
                            + ": group=" + g + " local=" + local + " auth=" + auth);
                }
                if (PredictKnobConfig.FOE_AUTOFIRE_APPLY) {
                    foe.giveCommand(ShipCommand.TOGGLE_AUTOFIRE, null, g);
                    plugin.foeAutofireApplyToggles++;
                    plugin.foeAutofireLane.log("predict foe-autofire APPLY toggle group=" + g
                            + " -> " + auth + " @tick=" + simTick);
                }
            }
        } catch (Throwable ignore) {
            // presentation-side meter/apply; never break the watch
        }
    }

    /**
     * The foe SELECTED-GROUP reconcile lane (the (114)(d) far-aim terminus fix;
     * see {@link PredictKnobConfig#FOE_SELECTION_MODE}). The authoritative selected
     * group is DERIVED from the carried per-mount {@code FLAG_SELECTED} bits via the LOCAL
     * replica's own mount→group mapping (the v4 mount tier's order contract), then converged
     * with the ABSOLUTE {@code giveCommand(SELECT_GROUP, null, idx)} — engine-verified safe:
     * the engine's handler is a plain {@code this.selected} assign ({@code isToggle} hardcoded false),
     * no autofire touch, idempotent on repeat, so unlike
     * the TOGGLE_AUTOFIRE lane a re-issue cannot strobe; the apply still runs on mismatch only.
     * Why it matters: a replica's SELECTED group's aim param passes the engine group dispatch
     * unconditionally ({@code WeaponGroup.getParamIfNotInactiveHardpoint} first clause) while a
     * non-selected manual group's param is NULLED beyond the arc+30° slop — the
     * far-out-of-arc full-range terminus run-out. Mirroring the seat's real selection puts the
     * replica beam on the same aim the server converges. NO mount carrying the bit ⇒ the lane
     * self-disarms (old server / server knob off / seat DESELECTED — the disclosed
     * divergence window). Runs on FRESH snapshots only; fail-soft like every reconcile lane.
     */
    void maintainFoeSelection(ShipAPI foe, ServerEntityPose pose, boolean freshSnapshot,
            long simTick, boolean meterCompare) {
        if (!PredictKnobConfig.FOE_SELECTION_METER || !freshSnapshot || foe == null
                || pose == null || !foe.isAlive()) {
            return;
        }
        try {
            if (!pose.hasWeaponMounts()) {
                return;
            }
            List<WeaponAPI> weapons = plugin.weaponLists.weapons(foe); // per-frame cache
            List<WeaponGroupAPI> groups = foe.getWeaponGroupsCopy();
            if (weapons == null || groups == null || groups.isEmpty()) {
                return;
            }
            int authIdx = -1;
            for (cc.starsector.client.net.ServerWeaponMountState mount : pose.weaponMounts()) {
                if (!mount.selected()) {
                    continue;
                }
                int idx = mount.mountIndex();
                if (idx < 0 || idx >= weapons.size()) {
                    continue;
                }
                WeaponAPI weapon = weapons.get(idx);
                if (weapon == null) {
                    continue;
                }
                WeaponGroupAPI wg = foe.getWeaponGroupFor(weapon);
                int g = wg == null ? -1 : groups.indexOf(wg);
                if (g >= 0) {
                    authIdx = g;
                    break;
                }
            }
            if (authIdx < 0) {
                if (meterCompare) {
                    plugin.foeSelectionLane.framesMissing++;
                }
                return;
            }
            if (meterCompare) {
                plugin.foeSelectionLane.framesCarried++;
            }
            int local = groups.indexOf(foe.getSelectedGroupAPI());
            if (local == authIdx) {
                if (meterCompare) {
                    plugin.foeSelectionLane.agree++;
                }
                return;
            }
            if (meterCompare) {
                plugin.foeSelectionLane.disagree++;
                plugin.foeSelectionLane.log("predict foe-selection disagree @tick=" + simTick
                        + ": local=" + local + " auth=" + authIdx);
            }
            if (PredictKnobConfig.FOE_SELECTION_APPLY) {
                foe.giveCommand(ShipCommand.SELECT_GROUP, null, authIdx);
                plugin.foeSelectionApplyCommands++;
                plugin.foeSelectionLane.log("predict foe-selection APPLY group=" + authIdx
                        + " (was " + local + ") @tick=" + simTick);
            }
        } catch (Throwable ignore) {
            // presentation-side meter/apply; never break the watch
        }
    }

    /**
     * Engine-state apply lane, ship-agnostic (controller + pose; formerly {@code applyFoeEngineState}
     * — the OWN lane is wired through it too) — drive the authoritative per-engine
     * disabled bits through the engine's REAL damage/repair mechanism (edge-triggered: only
     * engines whose local state contradicts the carried bit are touched), so the local controller
     * derives flameout, recovery, float text and the speed penalty itself. A locally-perm-disabled
     * engine the server says is clear is left alone ({@code repair()} would resurrect what the
     * engine model says cannot repair). {@code own} routes counters + the capped log to the
     * own-engine lane and arms the own-hull spawn-settle DISABLE defer.
     */
    void applyEngineState(
            ShipAPI ship, ShipEngineControllerAPI ctrl, ServerEntityPose pose, long simTick, boolean own) {
        // Repair-ramp gate, narrowed once and then PREDICATE FIXED:
        // the engine controller implements
        // isFlamedOut() as isDisabled() || isFlamingOut(), so the earlier ramp test
        // isFlamingOut() && !isFlamedOut() was identically FALSE — a dead guard (hence the eternal
        // *_repair_ramp_deferred=0). The ramp phase IS isFlamingOut() alone (state==FLAMING_OUT;
        // settled flamed-out reads ctrl.isDisabled()). The gate is load-bearing because of the
        // ramp's byte mechanics: while FLAMING_OUT
        // every engine health-tracker FORCE-disables on each 0.2–0.6 s interval elapse (stat- and
        // HP-blind) and repair accrual is off; the controller leaves the ramp only when EVERY
        // engine glow fades to 0 — and each repair() here re-lights that engine's glow. A wire
        // repair landing mid-ramp therefore both re-arms the next forced disable's per-engine
        // FX/sound AND blocks ramp-settle indefinitely: the self-sustaining flameout/repair storm
        // of a long test run (733-tick open streak, ~0.62 repairs/tick, zero storm-window
        // disables, wire steady-ONLINE, quiet space). Deferring ONLY repair edges, ONLY while
        // isFlamingOut(), lets the ramp settle within its ~1–2 s glow fade; repairs then flow in
        // the SETTLED state, dropping the disabled fraction for one clean "Engines Online" exit —
        // preserving the earlier gate's anti-deadlock property (a wrong local flameout can still be repaired
        // away: settled repairs stay live). The disable edge stays live in all phases (it aligns
        // with the ramp transient).
        boolean flameoutRamp = ctrl.isFlamingOut();
        List<ShipEngineControllerAPI.ShipEngineAPI> engines = ctrl.getShipEngines();
        if (engines == null) {
            return;
        }
        int count = Math.min(engines.size(), 32); // wire carries the first 32, in spec order
        // Mechanism-leg riders (meter-only): pre-apply local mask (β) + the
        // per-entity fork counter — the mask-fork meter is 1v1-only and the 1v1 lane never
        // runs in bastion (every ship a registry remote), so its 0 was structurally blind there.
        int localMask = 0;
        for (int i = 0; i < count; i++) {
            ShipEngineControllerAPI.ShipEngineAPI eng = engines.get(i);
            if (eng != null && eng.isDisabled()) {
                localMask |= 1 << i;
            }
        }
        int wireMask = pose.engineDisabledMask() & (count == 32 ? -1 : (1 << count) - 1);
        if (!own && localMask != wireMask) {
            plugin.engineStateMaskForkEntTicks++;
        }
        for (int i = 0; i < count; i++) {
            ShipEngineControllerAPI.ShipEngineAPI eng = engines.get(i);
            if (eng == null) {
                continue;
            }
            boolean authDisabled = (pose.engineDisabledMask() & (1 << i)) != 0;
            boolean authPerm = (pose.enginePermDisabledMask() & (1 << i)) != 0;
            if (own && !authDisabled) {
                plugin.engineRedisable.onWireClear(ship, i); // A clear edge frees the hold
            } else if (!authDisabled) {
                // The replica twin of
                // the own clear edge, on the lane's SEPARATE backstop (own meter stays own-only).
                foeEngineRedisable.onWireClear(ship, i);
            }
            if (authDisabled && !eng.isDisabled()) {
                if (own && OwnFireLockLaw.disableSettleActive(simTick, plugin.fireLanes.ownHullEpochBumpTick)) {
                    // The weapon lane's sub-lane 4 rule for the own hull — a fresh hull at the
                    // SAME wire id must not inherit a residual carried mask on the reappear frame
                    // (an engine-mask inherit would mint a spawn flameout; the repair edge stays live).
                    plugin.ownEngineDisableDeferred++;
                    plugin.weaponLane.log("predict own-engine APPLY disable DEFERRED (settle) engine=" + i
                            + " @tick=" + simTick + " epoch=" + plugin.fireLanes.ownHullEpoch);
                    continue;
                }
                // The engine analog of the weapon re-disable backstop — never re-disable() an
                // own engine within HOLD_TICKS of our own apply while the wire bit stayed set (the
                // native health-tracker repair racing a held bit re-fired the flame-out FX/sound
                // per snapshot = the reported "engine flicker"). Counted; confessed once.
                if (own && !plugin.engineRedisable.allowDisable(ship, i, simTick)) {
                    if (plugin.engineRedisable.confessOnce()) {
                        PilotWatchLog.info("predict backstop own-engine re-disable BACKSTOPPED engine=" + i
                                + " @tick=" + simTick
                                + " (local auto-repair raced a held wire bit; counter continues)");
                    }
                    continue;
                }
                // The SAME backstop for
                // REPLICAS — a native health-tracker repair racing a held wire bit was re-disable()d
                // every fresh snapshot, re-firing the flameout FX/sound (the flicker class,
                // fixed own-only). Counted (predict_foe_engine_redisable_backstopped); confessed
                // once on the replica instance's own flag.
                // main (wave-2 review, laws #1): METER-ONLY on replicas — a 3 s hold would leave a natively
                // repaired engine LIT while the wire says disabled (own ships have OwnEngineRegenStall; replicas
                // have no stall yet, so the hold would be their main path = a remedy worse than the flicker).
                // The disable below still runs every fresh snapshot (= today); the counter measures the flicker
                // rate for the replica regen-stall design.
                if (!own && !foeEngineRedisable.allowDisable(ship, i, simTick)
                        && foeEngineRedisable.confessOnce()) {
                    PilotWatchLog.info("predict backstop foe-engine re-disable WOULD-BACKSTOP engine=" + i
                            + " ship=" + pose.entityId() + " @tick=" + simTick
                            + " (replica auto-repair raced a held wire bit; metered, disable applied)");
                }
                eng.disable(authPerm);
                if (own) {
                    plugin.ownEngineApplyDisables++;
                    plugin.weaponLane.log("predict own-engine APPLY disable(perm=" + authPerm
                            + ") engine=" + i + " @tick=" + simTick
                            + engineRiderTokens(ship, ctrl, localMask, wireMask));
                } else {
                    plugin.engineStateApplyDisables++;
                    // ship= rider (the ship token was missing from these rows)
                    plugin.foeEngineLane.log("predict v6 engine-state APPLY disable(perm=" + authPerm
                            + ") engine=" + i + " ship=" + pose.entityId() + " @tick=" + simTick
                            + engineRiderTokens(ship, ctrl, localMask, wireMask));
                }
            } else if (!authDisabled && eng.isDisabled() && !eng.isPermanentlyDisabled()) {
                if (flameoutRamp) {
                    // ITEM C, narrowed: the ramp transiently over-disables past the authoritative
                    // mask; a repair() here churns against it. Defer to the settled/active tick.
                    if (own) {
                        plugin.ownEngineRepairRampDeferred++;
                    } else {
                        plugin.engineStateRepairRampDeferred++;
                    }
                    continue;
                }
                eng.repair();
                if (own) {
                    plugin.ownEngineApplyRepairs++;
                    plugin.weaponLane.log("predict own-engine APPLY repair engine=" + i
                            + " @tick=" + simTick
                            + engineRiderTokens(ship, ctrl, localMask, wireMask));
                } else {
                    plugin.engineStateApplyRepairs++;
                    plugin.foeEngineLane.log("predict v6 engine-state APPLY repair engine=" + i
                            + " ship=" + pose.entityId() + " @tick=" + simTick
                            + engineRiderTokens(ship, ctrl, localMask, wireMask));
                }
            }
        }
        if (own) {
            // The engine analog of D4b(1) — the own hull's engine regen is
            // stalled ONLY for the wire's disabled window (installed while ≥1 own engine is
            // wire-disabled, released on the first all-clear snapshot). The ITEM C ramp deferral
            // and the settle deferral above stay as they were; remote hulls untouched.
            plugin.ownEngineStall.maintain(ship, wireMask != 0 && !OwnFireLockLaw.disableSettleActive(simTick, plugin.fireLanes.ownHullEpochBumpTick), simTick); // A settle-deferred residual mask never stalls a fresh hull
        }
    }

    /**
     * Rider tokens on every capped engine APPLY line (built lazily — edge
     * branches only, never per quiet frame): {@code mult=} α shield-mult liveness at apply time
     * ({@code getEngineDamageTakenMult().getModifiedValue()}; NaN = the read threw), {@code fo=}
     * β controller phase (isFlamingOut/isFlamedOut bits), {@code lm=}/{@code wm=} the pre-apply
     * local vs wire disabled masks. Splits the leads at the next owner-tier flight:
     * mult≠0 at an APPLY edge ⇒ stat-object/identity face; mult=0 + churn ⇒ non-damage source.
     */
    private static String engineRiderTokens(
            ShipAPI ship, ShipEngineControllerAPI ctrl, int localMask, int wireMask) {
        float mult = Float.NaN;
        float malf = Float.NaN;
        try {
            if (ship != null) {
                mult = ship.getMutableStats().getEngineDamageTakenMult().getModifiedValue();
                // Static RE: the NON-damage disable author rolls getEngineMalfunctionChance
                // (health-tracker advance) — mc>0 on a repair APPLY is
                // that author's live fingerprint (CR-driven, so expect it to rise late-round).
                malf = ship.getMutableStats().getEngineMalfunctionChance().getModifiedValue();
            }
        } catch (Throwable ignore) {
            // NaN token — the line still lands
        }
        return " mult=" + mult + " mc=" + malf + " fo=" + (ctrl.isFlamingOut() ? 1 : 0)
                + (ctrl.isFlamedOut() ? 1 : 0)
                + " lm=0x" + Integer.toHexString(localMask)
                + " wm=0x" + Integer.toHexString(wireMask);
    }

    /**
     * The schema-v4 weapon-state reconcile lane (see
     * {@link PredictKnobConfig#WEAPON_STATE_MODE}). Runs per pose on FRESH snapshots only (own ship, foe ship, and
     * each bots-registry entity share this one function — no tick guard; counters count per
     * pose-frame). Meter: per carried mount, compare local {@code WeaponAPI.isDisabled()} against
     * the authoritative disabled bit. Apply: reconcile a disagreement through the engine's REAL
     * mount damage/repair mechanism — edge-triggered, only mounts whose local state contradicts
     * the carried bit are touched, so the local engine keeps deriving disable FX/float text and
     * the repair itself. A mount index past the local weapon list (roster/variant mismatch) is
     * left alone (counted: {@code …_mount_absent_local}). Fail-soft like every reconcile lane.
     * Also feeds the charge-glow present-from-wire lane
     * ({@link WeaponGlowLane}) — per carried mount, before the disabled compare.
     */
    void maintainWeaponState(
            ShipAPI ship, ServerEntityPose pose, boolean freshSnapshot, long simTick, String who) {
        if (!PredictKnobConfig.WEAPON_STATE_METER || !freshSnapshot || ship == null || pose == null || !ship.isAlive()) {
            return;
        }
        try {
            boolean own = "own".equals(who);
            if (!pose.hasWeaponMounts()) {
                plugin.weaponLane.framesMissing++;
                if (own) {
                    plugin.ownWeaponStall.release(ship, simTick); // D4b(1): no wire coverage ⇒ no stall
                }
                List<WeaponAPI> local = plugin.weaponLists.weapons(ship);
                if (local == null || local.isEmpty()) {
                    plugin.weaponMissingMountless++;
                } else {
                    plugin.weaponMissingArmed++;
                }
                return;
            }
            plugin.weaponLane.framesCarried++;
            if (!own) {
                // wire-covered replica ⇒ local mount-damage rolls yield authorship
                plugin.compDmgShield.ensureWeaponFace(ship, pose, simTick);
            }
            List<WeaponAPI> weapons = plugin.weaponLists.weapons(ship); // per-frame cache
            if (weapons == null) {
                return;
            }
            // A dweller hull's mounts are governed by its own creator's per-frame
            // force-disable stagger, and isDisabled() FOLDS forceDisabled — the
            // wire bit is the server's stagger phase, the local bit the local phase; reconciling
            // them is the Online/Offline storm. The local creator is the equal-fidelity author.
            boolean dwellerHull = !own && DwellerHullLaw.isDweller(ship);
            if (dwellerHull) {
                plugin.weaponStateDwellerSkipFrames++;
            }
            boolean anyWireDisabled = false;
            for (cc.starsector.client.net.ServerWeaponMountState mount : pose.weaponMounts()) {
                int idx = mount.mountIndex();
                if (idx < 0 || idx >= weapons.size()) {
                    plugin.weaponMountAbsentLocal++;
                    continue;
                }
                WeaponAPI weapon = weapons.get(idx);
                if (weapon == null) {
                    continue;
                }
                plugin.weaponGlow.maintain(weapon, mount, who, simTick);
                boolean authDisabled = mount.disabled();
                if (dwellerHull) {
                    continue; // glow still presents; the disabled compare is creator-owned
                }
                // D4b(3), per-mount arm: a LOCALLY script-force-disabled mount (VoltaicDischarge
                // fragment gate, swarm-launcher hold) reads isDisabled()==true for a non-EMP
                // reason — neither direction is an EMP edge for the lane to apply.
                if (weapon.isForceDisabled()) {
                    plugin.weaponStateForceDisabledSkips++;
                    continue;
                }
                if (authDisabled) {
                    anyWireDisabled = true;
                } else {
                    plugin.weaponRedisable.onWireClear(ship, idx); // D4b(2): a clear edge frees the hold
                }
                boolean localDisabled = weapon.isDisabled();
                if (localDisabled == authDisabled) {
                    plugin.weaponLane.agree++;
                    continue;
                }
                plugin.weaponLane.disagree++;
                plugin.weaponLane.log("predict v4 weapon-state disagree @tick=" + simTick
                        + ": " + who + " mount=" + idx
                        + " local=" + localDisabled + " auth=" + authDisabled
                        + " localDisabledDur=" + weapon.getDisabledDuration());
                if (!PredictKnobConfig.WEAPON_STATE_APPLY) {
                    continue;
                }
                if (authDisabled) {
                    // Dark-gun settle: a fresh own hull at the SAME
                    // wire id must not inherit a residual carried disabled bit on the reappear
                    // frame — defer the OWN apply-DISABLE edge for DISABLE_SETTLE_TICKS after
                    // an own-hull epoch bump (counted, never silent; the repair edge stays live).
                    if ("own".equals(who)
                            && OwnFireLockLaw.disableSettleActive(simTick, plugin.fireLanes.ownHullEpochBumpTick)) {
                        plugin.fireLanes.weaponStateDisableDeferred++;
                        plugin.weaponLane.log("predict v4 weapon-state disable DEFERRED (settle) own"
                                + " mount=" + idx + " @tick=" + simTick + " epoch=" + plugin.fireLanes.ownHullEpoch);
                        continue;
                    }
                    // Never re-disable() a mount within HOLD_TICKS of our own
                    // apply while the wire bit stayed set — the local auto-repair race can no
                    // longer re-fire the Offline cue per snapshot. Counted; confessed once.
                    if (!plugin.weaponRedisable.allowDisable(ship, idx, simTick)) {
                        if (plugin.weaponRedisable.confessOnce()) {
                            PilotWatchLog.info("predict backstop weapon-state re-disable BACKSTOPPED " + who
                                    + " mount=" + idx + " @tick=" + simTick
                                    + " (local auto-repair raced a held wire bit; counter continues)");
                        }
                        continue;
                    }
                    weapon.disable();
                    plugin.weaponStateApplyDisables++;
                    if (own) {
                        // The own hull's server-authored disable
                        // gets its visible cause — one arc onto the mount + the impact cue.
                        EmpArcNodeLaw.ownDisableArc(ship, weapon);
                    }
                    plugin.weaponLane.log("predict v4 weapon-state APPLY disable " + who
                            + " mount=" + idx + " @tick=" + simTick);
                } else if (weapon.isPermanentlyDisabled()) {
                    // The no-resurrect guard (the v6 engine lane's rule): repair() would revive
                    // what the local engine model says cannot repair. Count it — a persistent
                    // perm-vs-clear split is a real divergence the meter must surface.
                    plugin.weaponStatePermSkips++;
                } else {
                    weapon.repair();
                    plugin.weaponStateApplyRepairs++;
                    plugin.weaponLane.log("predict v4 weapon-state APPLY repair " + who
                            + " mount=" + idx + " @tick=" + simTick);
                }
            }
            if (own) {
                // The own hull's weapon regen is stalled ONLY for the wire's
                // disabled window (installed while ≥1 own mount is wire-disabled, released on the
                // first all-clear snapshot) — the engine face is never touched.
                plugin.ownWeaponStall.maintain(ship, anyWireDisabled, simTick);
            }
        } catch (Throwable ignore) {
            // presentation-side meter/apply; never break the watch
        }
    }
}
