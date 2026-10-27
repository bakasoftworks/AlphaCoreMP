package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.SeatRebind;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.match.WaveSchedule;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The serve loop's module-seat drain
 * family — voluntary-dismount execution and the fly-in drain's pre-mint MOUNT-EXISTING branch
 * (the TRUE-vacate consume moved to {@link CommandTransferServeLane})
 * — extracted own-file when the landing fired the
 * {@code PersistentPilotIntentCombatServer} 5000-nb whole-class trip (deferred-refactors PPICS
 * row; the {@code ServeBootWarm} precedent: extract the actual growth). One lane object per
 * ROUND (every field is round- or serve-scoped there), so the serve loop keeps thin call sites:
 * {@link #drainVacatesAndDismounts} pre-advance beside the (c) seat drain, {@link #tryMountClaim}
 * at the top of each fly-in. The arbitration LAW lives in {@link SeatRebindArbiter}; the engine
 * verbs live in {@code SeatRebind} behind {@link ModuleMountHook}; this class is the
 * decided ORDERING of existing seams and the W3 rebind's future extension point.
 *
 * <p>Constructed only when the serve armed the arbiter ({@code stationRowPresent} and a bound
 * mount hook), so {@code moduleSeats}/{@code mountHook} are never null here. A mount/dismount
 * THROW is serve-fatal by design (an engine re-key failure is never per-session — the
 * fly-in/wave-spawn law).
 */
final class ModuleSeatServeLane {

    private final SeatRebindArbiter moduleSeats;
    private final ModuleMountHook mountHook;
    private final PilotIntentDatagramServer door;
    private final MatchSeatAuthority authority;
    /** seatId → the seat's FIXED wire id (the drains run with no session/fly-in result in hand). */
    private final Map<Integer, Long> seatWireIdBySeatId;
    /** Nullable — shapes without a wave schedule. */
    private final WaveSchedule waveSchedule;
    private final EngineCombatBinding binding;
    private final PersistentPilotIntentCombatServer.PilotIntentStep pilotStep;
    private final GameplayEdgeEventDetector fireLaneDischargeDetector;
    private final AutofireTargetLane afTargetLane;
    private final ShipNetworkIdRegistry stableIdRegistry;
    private final Map<Integer, SeatAnnounceTracker.SeatAnnounce> lastAnnouncedVariantBySeat;
    private final List<byte[]> roundRosterAppendFrames;
    private final int round;
    /** The DEPLOYED station variant id — the per-family census key. */
    private final String stationVariantId;

    ModuleSeatServeLane(SeatRebindArbiter moduleSeats, ModuleMountHook mountHook,
            PilotIntentDatagramServer door, MatchSeatAuthority authority,
            Map<Integer, Long> seatWireIdBySeatId, WaveSchedule waveSchedule,
            EngineCombatBinding binding,
            PersistentPilotIntentCombatServer.PilotIntentStep pilotStep,
            GameplayEdgeEventDetector fireLaneDischargeDetector, AutofireTargetLane afTargetLane,
            ShipNetworkIdRegistry stableIdRegistry,
            Map<Integer, SeatAnnounceTracker.SeatAnnounce> lastAnnouncedVariantBySeat,
            List<byte[]> roundRosterAppendFrames, int round, String stationVariantId) {
        this.moduleSeats = moduleSeats;
        this.mountHook = mountHook;
        this.door = door;
        this.authority = authority;
        this.seatWireIdBySeatId = seatWireIdBySeatId;
        this.waveSchedule = waveSchedule;
        this.binding = binding;
        this.pilotStep = pilotStep;
        this.fireLaneDischargeDetector = fireLaneDischargeDetector;
        this.afTargetLane = afTargetLane;
        this.stableIdRegistry = stableIdRegistry;
        this.lastAnnouncedVariantBySeat = lastAnnouncedVariantBySeat;
        this.roundRosterAppendFrames = roundRosterAppendFrames;
        this.round = round;
        this.stationVariantId = stationVariantId;
    }

    /**
     * Pre-advance beside the (c) seat drain — voluntary dismounts execute: AI restore +
     * reverse re-key (fresh above-band id) + mount-loss DEPARTED, no kill — hull respawn then
     * follows the normal policy. The TRUE-seat-vacate consume that used to lead this drain
     * MOVED to {@link CommandTransferServeLane#pump} (the arbiter mints on
     * every serve, so vacate footprints must drain fortress or not); that lane pumps FIRST, so
     * a vacated MOUNTED seat's dismount deposit is already queued when this drain runs.
     * Returns the dismount count — a nonzero drain re-shapes the W3 transfer census
     * (it sets the census dirty mark).
     */
    int drainVacatesAndDismounts(long tick) throws IOException {
        int dismounts = 0;
        for (int seatId : moduleSeats.takeAllDismountRequests()) {
            dismounts++;
            long wireId = seatWireIdBySeatId.get(seatId);
            String token = moduleSeats.mountedTokenOrNull(seatId);
            String moduleStable = mountHook.dismount(seatId, wireId);
            stableIdRegistry.releaseForRebind(moduleStable);
            long freshModuleId = stableIdRegistry.networkIdFor(moduleStable);
            // The ECHO-arming loss record — this tick's despawn pass will see the
            // seat's wire id leave the roster (the re-key above) and must skip its seat block,
            // not re-record a loss the drain already recorded (doing so crashed a run).
            authority.recordVoluntaryDismount(seatId, tick);
            moduleSeats.noteDismounted(seatId);
            // The despawn twin's fire-lane hygiene (decl FIFO + staged frame) — the seat id has
            // no body until its next fly-in.
            // Keyed by the seat's FIXED wire id
            // (applyStateByShipId), never the seat id.
            int flushedDecls = pilotStep.flushDeclarationsOnHullLoss(wireId);
            pilotStep.clearStagedIntentOnHullLoss(wireId);
            fanModuleMountHandover(seatId, wireId, freshModuleId,
                    censusRowFor(token, stationVariantId).slotId(), false);
            System.out.println("npeer-serve module_dismount seat=" + seatId
                    + " wire_id=" + wireId + " token=" + token
                    + " stable_id=" + moduleStable + " fresh_module_id=" + freshModuleId
                    + " flushed_decls=" + flushedDecls
                    + " round=" + round + " tick=" + tick);
        }
        return dismounts;
    }

    /**
     * The fly-in drain's pre-mint claim consume: returns true when a valid claim
     * MOUNT-EXISTING replaced the hull deploy (the caller skips its mint path); a stale claim
     * prints LOUD and returns false — hull fly-in fallback, never silent.
     */
    boolean tryMountClaim(int seatId, long tick) throws IOException {
        SeatRebindArbiter.ClaimTake take = moduleSeats.takeClaim(seatId);
        if (take == null) {
            return false;
        }
        if (take.staleReason() != null) {
            System.out.println("npeer-serve module_claim_stale seat=" + seatId
                    + " reason=\"" + take.staleReason() + "\""
                    + " round=" + round + " tick=" + tick);
            return false;
        }
        MatchDescriptors.ModuleSeat seatRow = censusRowFor(take.token(), stationVariantId);
        long wireId = seatWireIdBySeatId.get(seatId);
        SeatRebind.ModuleMountResult mount =
                mountHook.mount(seatId, wireId, seatRow.slotId(), seatRow.moduleVariantId());
        // Re-key: the module's above-band first-seen id retires; the module now IS the
        // seat's fixed wire id. Then the fly-in family's fire resets on that id — the corpse's
        // baselines/mask must never survive onto the module body.
        long moduleOldId = stableIdRegistry.releaseForRebind(mount.stableId());
        stableIdRegistry.internReservedMount(mount.stableId(), wireId);
        int gpeeReset = fireLaneDischargeDetector.resetShipBaselines(wireId);
        binding.installFastTimeReinjectShim(wireId);
        int autofireOff = binding.disarmBuildTimeAutofire(wireId);
        authority.recordMountEstablished(seatId);
        moduleSeats.noteMounted(seatId, take.token());
        if (waveSchedule != null) {
            waveSchedule.armTimer(tick);
        }
        fanModuleMountHandover(seatId, wireId, moduleOldId, seatRow.slotId(), true);
        System.out.println("npeer-serve module_mount seat=" + seatId
                + " wire_id=" + wireId + " slot=" + seatRow.slotId()
                + " token=" + take.token() + " stable_id=" + mount.stableId()
                + " old_module_id=" + moduleOldId + " gpee_reset=" + gpeeReset
                + " autofire_off=" + autofireOff + " round=" + round + " tick=" + tick);
        // The mount IS a cross-variant refield (hull → module variant): the RAPP re-point fan +
        // the staleness family key off it.
        if (SeatAnnounceTracker.announceSeatVariantIfRepointed(door, seatId,
                new PersistentPilotIntentCombatServer.NPeerHooks.FlyInResult(wireId,
                        mount.stableId(), mount.variantId(), mount.descriptorVariantId(),
                        mount.side(), mount.shipName(), mount.crPerMille()),
                lastAnnouncedVariantBySeat, roundRosterAppendFrames)) {
            pilotStep.fireClassLane().latchMaskStalenessGate(wireId, tick, seatId);
            pilotStep.fireClassLane().rebindFireClassResolverAtRepoint(wireId, tick, seatId);
            afTargetLane.resetShip(wireId);
        }
        return true;
    }

    /**
     * W2b leg 2: the MMNT module-mount handover fan. Call sites honor the decided
     * ordering — MMNT before the RAPP re-point row (single-writer CTRL FIFO, sheet
     * build-verify (d)) — and pass the frame's decided fields: {@code otherWireId} = the
     * module's retiring above-band id at a mount ({@code mount}=true, kind 1), its fresh
     * above-band id at a dismount (kind 2). {@code slotId} is the ENGINE slot id verbatim
     * ({@code "WS 013"}) — the client's cluster census keys slots by engine slot id, so that
     * is the only vocabulary that resolves there; the friendly chat token ({@code citadel})
     * never rides the wire (the first live test failed on exactly that
     * mismatch: friendly tokens fanned, client parked both frames forever). An encode failure
     * is serve-fatal like the mount verbs (server-authored constants only ride this frame —
     * never wire input).
     */
    private void fanModuleMountHandover(int seatId, long seatWireId, long otherWireId,
            String slotId, boolean mount) throws IOException {
        int kind = mount ? ModuleMountFrameCodec.KIND_MOUNT : ModuleMountFrameCodec.KIND_DISMOUNT;
        byte[] frame = ModuleMountFrameCodec.encode(new ModuleMountFrameCodec.MountHandover(
                seatWireId, otherWireId, kind, slotId));
        door.fanModuleMount(frame, kind);
    }

    /** Census row for an arbiter-granted token; a non-census grant broke the census law.
     * Package-private (F-B): the transfer lane's module-SOURCE commit resolves the
     * mounted token's slot id through the same census law. Keyed by the
     * DEPLOYED station variant id (the arbiter's grant vocabulary was minted from the same
     * id, so token→row stays a bijection per serve). */
    static MatchDescriptors.ModuleSeat censusRowFor(String token, String stationVariantId) {
        for (MatchDescriptors.ModuleSeat row : MatchDescriptors.moduleSeatCensus(stationVariantId)) {
            if (row.token().equals(token)) {
                return row;
            }
        }
        throw new IllegalStateException("arbiter granted non-census token " + token
                + " (census law broken)");
    }

    /**
     * W2b leg 2: late-joiner MMNT catch-up — one kind-1 frame per currently MOUNTED
     * seat, sent to the newcomer BEFORE the round's RAPP catch-up replay (the live-fan order
     * law must hold on replay too: the client's mounted-table guard has to be warm when the
     * seat's RAPP row arrives). {@code moduleOldWireId=0} sentinel by design — the module's
     * retiring id was released at mount and is gone; the client resolves the body by ENGINE
     * slot id, old-id is stale-map cleanup only.
     */
    void replayMountsToSession(PilotIntentDatagramServer.PeerSession session) throws IOException {
        int replayed = 0;
        for (Map.Entry<Integer, Long> seat : seatWireIdBySeatId.entrySet()) {
            String token = moduleSeats.mountedTokenOrNull(seat.getKey());
            if (token == null) {
                continue;
            }
            byte[] frame = ModuleMountFrameCodec.encode(new ModuleMountFrameCodec.MountHandover(
                    seat.getValue(), 0L, ModuleMountFrameCodec.KIND_MOUNT,
                    censusRowFor(token, stationVariantId).slotId()));
            door.sendCtrlToSession(session, frame);
            replayed++;
        }
        if (replayed > 0) {
            System.out.println("npeer-serve module_mount_catchup session=" + session.sessionId()
                    + " mounts=" + replayed + " round=" + round);
        }
    }
}
