package cc.starsector.client;

import cc.starsector.client.net.ClientGameplayEdgeEvent;
import cc.starsector.client.net.ServerEntityPose;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;

/**
 * The TELEPORT-RECONCILE lane, knob {@code cc.predict.teleportReconcile} =
 * {@code off|meter|apply} (default {@code apply} since a live run
 * proved the lane 1:1 live). The defect it exists
 * for: a replica displacer replays the engine-native jump with a locally-derived dest ≠
 * the authoritative dest; two ~300u near-simultaneous displacements leave a 0..600u residual
 * straddling the 300u hatch ⇒ random snap-bounce or a ~155 u/s smear. Truth source = the GPEE
 * wire-v3 {@code SYSTEM_JUMP} marker (durable exactly-once; authoritative post-jump
 * destX/destY). {@code meter}: count markers/windows/fences, no policy change. {@code apply}:
 * D3 marker hard-snap + per-entity snap-only window (glide disabled exactly where
 * discontinuities live), D4 never-cycled heal ({@code forceState(OUT,0)} — the seed lane's
 * faithful path), D5 own-ship transit patience (hatch + step-rebase suspended; the own marker
 * is the fence re-anchor). Fence ticks double as the interp arc's discontinuity fences (D6).
 * Invalid values THROW at class load.
 *
 * <p>K16 extraction (the 9500-backstop payment, extraction-first):
 * knob statics, the SYSTEM_JUMP marker fold, the per-entity {@link JumpState} (previously split
 * across {@code RemoteEntity} fields and the plugin's 1v1/own twins), the D3(iii)/D5 predicates,
 * the D4 heal and the meter family live HERE; the plugin keeps body/state resolution at the
 * marker fold, the {@code jumpSnap} seam (D3 hard-snap re-uses {@code reconcilePosition}) and
 * thin per-frame relays (the {@link FoeInterpLane} isolation idiom).
 */
final class TeleportJumpLane {

    private static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.teleportReconcile", System.getProperty("cc.predict.teleportReconcile", "apply"));
    static final boolean METER = !"off".equals(MODE);
    static final boolean APPLY = "apply".equals(MODE);
    /** D3 snap-only window tail past a marker (server ticks ≈ OUT 0.5 s + jitter; a shared const). */
    private static final long SNAP_WINDOW_TICKS = 45L;
    /** D1 teleport-class system ids — mirror of the server detector's set (keep in lockstep).
     * Package-read by {@link SpawnSystemGate}'s seed gate as its
     * JSON-unreadable FALLBACK classifier (its primary is spec-type engine truth). */
    static final java.util.Set<String> TELEPORT_CLASS_SYSTEM_IDS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "displacer", "displacer_degraded", "displacer_threat", "skimmer_drone",
                    "phaseteleporter"));
    /** Per-event log cap for the teleport lane (markers are rare — ~10/fight measured). */
    private static final int JUMP_LOG_MAX = 40;

    /**
     * Per-replica teleport-jump state (K16: one holder per {@code RemoteEntity}, twins for the
     * 1v1 foe live in the lane). All fields are round-local; {@link #reset()} is the fresh-hull /
     * round-boundary law (fresh class read + fresh fences).
     */
    static final class JumpState {
        /** D1 — teleport-class latch (null until the bound replica's system id is read once). */
        Boolean teleportClass;
        /** D3/D6 — last SYSTEM_JUMP marker tick = the discontinuity fence (interp arc consumes). */
        long fenceTick = Long.MIN_VALUE;
        /** D3 — snap-only window end tick (markerTick + {@link #SNAP_WINDOW_TICKS}). */
        long snapOnlyUntilTick = Long.MIN_VALUE;
        /** D4 — marker applied; heal owed if the local system never cycled (consumed in maintain). */
        boolean healPending;

        void reset() {
            teleportClass = null;
            fenceTick = Long.MIN_VALUE;
            snapOnlyUntilTick = Long.MIN_VALUE;
            healPending = false;
        }
    }

    /** The D3 hard-snap seam back into the plugin ({@code reconcilePosition(body, x, y, 1, true)}). */
    private final PredictReconcileCombatPlugin plugin;

    // Meter family (cumulative per match; logged via logMeters, never reset mid-match).
    private int jumpMarkersOwn;        // own-ship SYSTEM_JUMP markers presented
    private int jumpMarkersFoe;        // foe/remote SYSTEM_JUMP markers presented
    private int jumpMarkersUnbound;    // markers with no bound local body at present time
    private int jumpSnapsApplied;      // apply: D3 marker hard-snaps to the authoritative dest
    private int jumpHealsApplied;      // apply: D4 forceState(OUT,0)+setAmmo never-cycled heals
    private int jumpSnapOnlyFrames;    // entity-frames reconciled under the D3 snap-only window
    private int jumpFacingSnapFrames;  // Facing snap hatch: direct facing sets on position-snapped frames
    private int jumpOwnPatienceFrames; // frames the own hatch+rebase sat suspended (D5)
    private int jumpLogLines;          // capped per-marker log (JUMP_LOG_MAX)
    /** D5 — own patience window end tick (re-armed each frame the own teleport system runs its window). */
    private long ownJumpPatienceUntilTick = Long.MIN_VALUE;
    /** D5/D6 — own SYSTEM_JUMP fence tick: poses before it are pre-jump geometry (interp arc consumes). */
    private long ownJumpFenceTick = Long.MIN_VALUE;
    /** D3/D6 1v1 twins of the per-entity {@link JumpState} (registry entities carry their own). */
    private long foeJumpFenceTick = Long.MIN_VALUE;
    private long foeSnapOnlyUntilTick = Long.MIN_VALUE;
    private boolean foeJumpHealPending;

    TeleportJumpLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Present a {@code SYSTEM_JUMP} marker (GPEE wire v3): the server detector's
     * IN→{ACTIVE,OUT} teleport-class edge carrying the authoritative post-jump destination. Foe leg
     * (D3): stamp the entity's discontinuity fence + snap-only window, then under {@code apply}
     * hard-snap the replica to the dest (velocity/facing stay with the replica's own replayed jump —
     * D4 allow-then-pin; the pose lane refines next frame) and arm the never-cycled heal. Own leg
     * (D5): the marker is the fence re-anchor only — the patience window itself runs off the LOCAL
     * system state ({@link #maintainOwnPatience}). Held-until-due by the plugin's drain, so the fold
     * lands at T_s ≥ T like every edge; a marker with no bound local body is counted, never guessed.
     *
     * @param body  the resolved local body (null for the own leg and unbound markers — plugin context)
     * @param state the per-entity holder (registry shape), or null on the 1v1 shape (lane twins)
     */
    void presentSystemJump(ClientGameplayEdgeEvent event, boolean own, ShipAPI body, JumpState state) {
        if (!METER) {
            return; // lane off — the event still hit the plugin's capped eventReplay log
        }
        if (own) {
            jumpMarkersOwn++;
            ownJumpFenceTick = event.simTick();
            body = null; // the own leg never snaps — patience (D5) owns the transit
        } else if (body == null) {
            jumpMarkersUnbound++;
            return;
        } else if (state != null) {
            jumpMarkersFoe++;
            state.fenceTick = event.simTick();
            state.snapOnlyUntilTick = event.simTick() + SNAP_WINDOW_TICKS;
            if (APPLY) {
                state.healPending = true;
            }
        } else {
            jumpMarkersFoe++;
            foeJumpFenceTick = event.simTick();
            foeSnapOnlyUntilTick = event.simTick() + SNAP_WINDOW_TICKS;
            if (APPLY) {
                foeJumpHealPending = true;
            }
        }
        if (body != null && APPLY) {
            plugin.jumpSnap(body, event.destX(), event.destY());
            jumpSnapsApplied++;
        }
        if (jumpLogLines < JUMP_LOG_MAX) {
            jumpLogLines++;
            PilotWatchLog.info("predict teleport jump marker #" + jumpLogLines + ": entity="
                    + event.entityId() + (own ? " (OWN)" : "") + " tick=" + event.simTick()
                    + " dest=(" + event.destX() + "," + event.destY() + ")"
                    + " mode=" + MODE
                    + (body != null && APPLY ? " SNAPPED" : ""));
        }
    }

    /** Is this hull's primary system in the teleport class? Fail-soft {@code false}. */
    private static boolean teleportClassOf(ShipAPI ship) {
        Boolean cls = classifyOrNull(ship);
        return cls != null && cls.booleanValue();
    }

    /** The D1 classify core: a clean read returns TRUE/FALSE; a THROW returns {@code null} so the
     * registry cache ({@link #snapOnly(ShipAPI, JumpState, long)}) never latches a throw-derived
     * FALSE (1290 (83) CL-9 — the fresh-per-frame callers treat null as not-teleport-class). */
    private static Boolean classifyOrNull(ShipAPI ship) {
        try {
            ShipSystemAPI sys = ship != null ? ship.getSystem() : null;
            return Boolean.valueOf(sys != null && sys.getId() != null
                    && TELEPORT_CLASS_SYSTEM_IDS.contains(sys.getId()));
        } catch (Throwable ignore) {
            // 1290 (81) R3: a throwing classify means the snap-only window never engages — the
            // jump-smear this lane exists to kill returns for that entity, silently.
            FallbackAnnounce.announceOnce("r3-teleport-classify-threw", "teleportClassOf threw "
                    + ignore.getClass().getSimpleName() + " -> treated not-teleport-class");
            return null;
        }
    }

    /**
     * The snap-only window predicate for one registry replica: teleport-class
     * (D1-latched here on first consult) AND (local system inside its jump window ({@code isActive()}
     * = IN|ACTIVE|OUT) OR within {@link #SNAP_WINDOW_TICKS} of a marker). Inside it the plugin's
     * {@code reconcilePosition} glide branch is disabled — errors snap regardless of magnitude,
     * killing the smear exactly where discontinuities live; ordinary combat glide untouched.
     * {@code meter} counts the frames; only {@code apply} returns true.
     */
    boolean snapOnly(ShipAPI ship, JumpState state, long simTick) {
        if (state.teleportClass == null) {
            Boolean cls = classifyOrNull(ship);
            if (cls == null) {
                // 1290 (83) CL-9: classify threw — not-teleport-class for THIS frame only; the
                // latch stays empty so the next frame re-derives instead of fossilizing FALSE.
                return false;
            }
            state.teleportClass = cls;
        }
        return snapOnly(ship, state.teleportClass.booleanValue(), state.snapOnlyUntilTick, simTick);
    }

    /** The 1v1 twin of {@link #snapOnly(ShipAPI, JumpState, long)} — class read fresh per frame
     *  (the pre-K16 single-foe behavior, verbatim), window off the lane's twins. */
    boolean foeSnapOnly(ShipAPI foeShip, long simTick) {
        return snapOnly(foeShip, teleportClassOf(foeShip), foeSnapOnlyUntilTick, simTick);
    }

    private boolean snapOnly(ShipAPI ship, boolean teleportClass, long snapOnlyUntilTick,
            long simTick) {
        if (!METER || !teleportClass) {
            return false;
        }
        boolean inWindow = simTick <= snapOnlyUntilTick;
        if (!inWindow) {
            try {
                ShipSystemAPI sys = ship != null ? ship.getSystem() : null;
                inWindow = sys != null && sys.isActive();
            } catch (Throwable ignore) {
                return false;
            }
        }
        if (!inWindow) {
            return false;
        }
        jumpSnapOnlyFrames++;
        return APPLY;
    }

    /** The 1v1 foe's D6 discontinuity fence (registry replicas read {@code state.fenceTick}). */
    long foeFenceTick() {
        return foeJumpFenceTick;
    }

    /**
     * Consume a pending never-cycled heal for a registry replica (call on FRESH
     * frames only, where the pose's v8 scalars are at hand): a marker applied while the LOCAL
     * system never cycled this window (state IDLE|COOLDOWN — missed press / no charges) re-syncs
     * via the seed lane's faithful path ({@code forceState(OUT, 0)} = a just-opened OUT window,
     * + {@code setAmmo}), so post-jump OUT visuals still present. A locally-cycled system
     * (IN|ACTIVE|OUT) is the D4 allow-then-pin happy path — nothing to heal. No v8 tier on this
     * frame yet ⇒ the debt stays pending, retried on a later fresh frame (the seed-lane contract).
     */
    void maintainHeal(JumpState state, ShipAPI ship, ServerEntityPose pose, long simTick) {
        if (state.healPending && consumeHeal(ship, pose, simTick)) {
            state.healPending = false;
        }
    }

    /** The 1v1 twin of {@link #maintainHeal} (call on FRESH frames only). */
    void maintainFoeHeal(ShipAPI foeShip, ServerEntityPose pose, long simTick) {
        if (foeJumpHealPending && consumeHeal(foeShip, pose, simTick)) {
            foeJumpHealPending = false;
        }
    }

    /** @return true when the pending heal is CONSUMED (applied or moot); false = retry later. */
    private boolean consumeHeal(ShipAPI ship, ServerEntityPose pose, long simTick) {
        if (ship == null || pose == null || !ship.isAlive()) {
            return true; // body gone — the heal is moot
        }
        if (!pose.hasSystemState()) {
            return false;
        }
        try {
            ShipSystemAPI sys = ship.getSystem();
            if (sys == null) {
                return true;
            }
            ShipSystemAPI.SystemState state = sys.getState();
            if (state != ShipSystemAPI.SystemState.IDLE
                    && state != ShipSystemAPI.SystemState.COOLDOWN) {
                return true; // locally cycled — the marker snap already pinned it (happy path)
            }
            sys.forceState(ShipSystemAPI.SystemState.OUT, 0f);
            int authAmmo = pose.systemAmmo();
            if (authAmmo >= 0 && authAmmo != sys.getAmmo()) {
                sys.setAmmo(authAmmo);
            }
            jumpHealsApplied++;
            if (jumpLogLines < JUMP_LOG_MAX) {
                jumpLogLines++;
                PilotWatchLog.info("predict teleport jump HEAL @tick=" + simTick + " sysId="
                        + sys.getId() + " local=" + state + " -> forceState(OUT,0)"
                        + (authAmmo >= 0 ? " ammo=" + authAmmo : "")
                        + " (never-cycled window, D4)");
            }
            return true;
        } catch (Throwable ignore) {
            return true; // fail-soft one-shot: never retry-spam (the seed-lane discipline)
        }
    }

    /**
     * own-ship transit patience: while the OWN teleport-class system runs its jump
     * window (local {@code isActive()} = IN|ACTIVE|OUT), extended {@link #SNAP_WINDOW_TICKS}
     * past the last in-window frame (marker/RTT margin), the 300u hatch and the step-rebase are
     * suspended — both sides run the REAL system (the server replays USE_SYSTEM), so the frames
     * where the two engines jump ~a frame apart need patience, not correction (the teleporter
     * HALVES velocity ⇒ |Δv|=|v|/2 would false-trip rebase on every Hyperion jump; skipping the
     * whole rebase call also leaves its baseline pre-window, and the per-tick normalization dilutes
     * the across-window step below trip — by design). The own {@code SYSTEM_JUMP} marker is the
     * re-anchor: a held pose frame older than the fence tick is pre-jump geometry and is skipped
     * rather than reconciled backwards (the pose lane is ONE volatile frame — a frame-level gate
     * suffices). {@code meter} counts the frames; only {@code apply} suspends.
     */
    boolean maintainOwnPatience(ShipAPI own, long simTick) {
        if (!METER) {
            return false;
        }
        boolean patience = false;
        try {
            if (teleportClassOf(own)) {
                ShipSystemAPI sys = own.getSystem();
                if (sys != null && sys.isActive()) {
                    ownJumpPatienceUntilTick = simTick + SNAP_WINDOW_TICKS;
                    patience = true;
                }
            }
        } catch (Throwable ignore) {
            return false;
        }
        if (!patience && simTick <= ownJumpPatienceUntilTick) {
            patience = true; // margin tail past the local window (marker/RTT)
        }
        if (!patience && simTick < ownJumpFenceTick) {
            patience = true; // held pose predates the own jump fence — pre-jump geometry
        }
        if (patience) {
            jumpOwnPatienceFrames++;
        }
        return patience && APPLY;
    }

    /** Facing snap hatch touchpoint — the plugin's reconcileFoeTo counts its direct facing sets. */
    void noteFacingSnapFrame() {
        jumpFacingSnapFrames++;
    }

    /** Round boundary — the 1v1 twins + own fence/patience clear (fences/windows are round-local;
     *  per-entity holders reset via {@link JumpState#reset()} in the plugin's registry loop). */
    void resetRound() {
        foeJumpFenceTick = Long.MIN_VALUE;
        foeSnapOnlyUntilTick = Long.MIN_VALUE;
        foeJumpHealPending = false;
        ownJumpFenceTick = Long.MIN_VALUE;
        ownJumpPatienceUntilTick = Long.MIN_VALUE;
    }

    /** The teleport-meter census line (silent at {@code off}; cumulative per match). */
    void logMeters(String where) {
        if (!METER) {
            return;
        }
        PilotWatchLog.info("predict teleport-meter [" + where + "]:"
                + " mode=" + MODE
                + " jump_markers_own=" + jumpMarkersOwn
                + " jump_markers_foe=" + jumpMarkersFoe
                + " jump_markers_unbound=" + jumpMarkersUnbound
                + " jump_snaps_applied=" + jumpSnapsApplied
                + " jump_heals_applied=" + jumpHealsApplied
                + " jump_snaponly_frames=" + jumpSnapOnlyFrames
                + " jump_facing_snap_frames=" + jumpFacingSnapFrames
                + " jump_own_patience_frames=" + jumpOwnPatienceFrames);
    }
}
