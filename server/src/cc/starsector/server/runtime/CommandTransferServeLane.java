package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.SeatRebind;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.protocol.SeatStateCause;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.BiConsumer; //
import java.util.function.Consumer; //
import java.util.function.IntFunction;

/**
 * W3 L1b-ii: the serve loop's per-round command-transfer
 * lane — the ALWAYS-constructed twin of {@link ModuleSeatServeLane} (which exists only when
 * module verbs armed: fortress row + bound mount hook). Pumped pre-advance FIRST each tick
 * (transferLane → moduleLane → graceLane → fly-in):
 * <ol>
 * <li>the door's dispatch-time tick heartbeat ({@code noteSimTick} — /transfer begin floors
 *     read it at most one frame stale);</li>
 * <li>the TRUE-seat-vacate consume, MOVED here from the module lane (D1: the arbiter mints on
 *     every serve, so a leaver's arbiter footprint — W3 flight, claim, mounted-seat dismount
 *     deposit — must drain fortress or not), plus the staged-intent
 *     clear-at-true-vacate: the GRACE-variant clear ({@code clearStagedIntentOnGraceVacate} —
 *     the hull lives; a mounted seat's later dismount drain still full-clears via
 *     {@code clearStagedIntentOnHullLoss}). With grace ON the ENTER_GRACE pump re-clears one
 *     tick later — idempotent, and this lane covers the grace-OFF serve the reaper class
 *     needs.</li>
 * <li>the L2 pod pump ({@link TransferPodLane}): the physical command-shuttle
 *     flights advance against THIS tick's in-flight snapshot (taken once, shared with the
 *     drain below); a timed-out flight's seat comes back here and its arbiter row aborts +
 *     fans LOUD. Inert one-check when no pod flies (frame-residency law) — nothing spawns
 *     pods until the P3 dock-gated commit lands.</li>
 * <li>the D3 transfer drain: every in-flight row re-checked authoritatively OFF the arbiter
 *     lock (winner still connected + mounted + un-latched + grace-clear; target re-classified
 *     from FRESH authority/registry facts through {@link TransferEligibility} — never the
 *     published census). The commit is DOCK-GATED: a surviving row with no
 *     pod mints its flight here (drain-owned — dispatch stays thin; a spawn deny aborts the
 *     row LOUD), and only a row whose pod finished landing commits through the
 *     {@link TransferRekeyHook} engine verb and the fly-in re-key/fire-reset family
 *     ({@code tryMountClaim} hygiene verbatim). RACE mode is live here: the first DOCKED row
 *     in drain order wins; {@code transferCommitWinner} losers fan their ready-made reason
 *     lines and their pods turn home. A failed re-check aborts the row LOUD (chat fan + log)
 *     — never silently. Transit PARK (L4 S1 — replaces the P5 v1 "seat
 *     keeps piloting" interim by design): the player
 *     detaches AT TRIGGER like vanilla — the mint branch parks the source hull under the
 *     PROVEN cautious grace-AI attach ({@code graceAttachTransitAi} → {@code
 *     graceAttachCautiousAi}; a station-DRONE source keeps its vanilla launcher-ring AI
 *     instead; recorded deviation: transit personality = cautious, revisit at the
 *     L6 live test) and parks the seat's
 *     staged-intent application ({@code parkTransit} — the upstream gate; the client still
 *     SENDS at S1, its TRANSIT mode is S2). Park state is SERVE-LANE-keyed
 *     ({@code transitParks}), NOT flight-keyed: pod-loss re-mints must keep the ORIGINAL
 *     saved personality. ONE-ENTITY POD:
 * the seat RIDES the pod — BEGIN re-keys the seat's wire id S onto
 *     the freshly spawned pod and the source onto a fresh id F ({@code mountSeatOnPod};
 *     the identity law), so from BEGIN on the source is addressed by F and S IS the
 *     pod: the park's caretaker pin is released/detached by F, NEVER by S while a flight
 *     exists. COMMIT unparks and releases the AI pin ({@code releaseGracePin(F)}) —
 *     AI-flown IS the vanilla end state; DOCK-BACK (a RETURNING flight landed home)
 *     re-keys S onto the source again and restores the saved personality by S; a STRAND
 *     leaves the seat flying the pod under S (unpark + pin release by F, no detach). The
 *     pod's RAPP row rides the seat's own repoint (S → {@code shuttlepod_cc_pod}) — there
 *     is no standalone pod row any more.</li>
 * <li>the D6 census publish: the {@code /transfer} target listing rebuilt from FRESH engine +
 *     authority facts and swapped into {@link TransferTargets} — dirty-flag driven (fly-in,
 *     despawn, session diff, grace transitions, module mounts, transfer commit/abort — the
 *     serve loop marks via {@link #markCensusDirty}) with a {@value #CENSUS_BELT_TICKS}-tick
 *     re-publish belt for any missed mark; NEVER per-tick (frame-residency law — staleness is
 *     harmless: dispatch is advisory, the drain re-checks). Null {@code transferTargets} =
 *     the D7 knob is off ({@code transfer.enabled=false}) — the lane still vacate-drains and
 *     heartbeats, dispatch stays honestly "unavailable".</li>
 * </ol>
 * One lane object per ROUND (the moduleLane precedent). An engine re-key THROW is serve-fatal
 * by the mount-family law; a null {@code transferRekey} hook (contract fakes) is the fail-soft
 * player-verb class — in-flight rows abort LOUD "unavailable" BEFORE any state is touched.
 * The class is public ONLY so the tests package reaches {@link #replayBeginFrameOrNull}
 * (the late-join replay seam); every constructor/member stays package-private.
 */
public final class CommandTransferServeLane {

    /** The pod's CLIENT-resolvable wire variant (the kit ships it; javadoc cross-ref:
     * {@code cc.starsector.client.net.ClientCommandTransfer.POD_WIRE_VARIANT_ID} — a
     * deliberate constant PAIR, not a parity-regex site). VARIANT LAW:
     * the RAPP row names THIS id while the server pod stays built {@code shuttlepod_xfer}
     * (vanilla-verbatim, runtime-minted, NOT client-resolvable; its isShuttlePod=true is
     * what keeps it out of the transfer census). One-entity pod: the row
     * is the SEAT's own repoint (S → this variant through {@code announceSeatVariantIfRepointed}
     * off the {@code mountSeatOnPod} result) — the standalone pod RAPP row is gone; a BEGIN
     * whose {@code pod == seatWireId} IS the client's bypass signal. */
    static final String POD_WIRE_VARIANT_ID = "shuttlepod_cc_pod";

    /** A seat's transit-park bookkeeping: the personality saved by the FIRST
     * engage (pod-loss re-mints skip the attach and keep it — flight-keyed state would die
     * with the pod and a second attach would re-save "cautious", corrupting the restore).
     * {@code moduleSource} (F-B): the seat rides a fortress module — no AI was
     * attached at engage (cautious SHIP-AI is a mismatched class for a module body; the
     * module sits AI-less = its ridden state), so the abort end skips the detach too.
     * {@code hulkSource} (a serve-FATAL seen live): the seat
     * flew its shuttle OUT OF ITS WRECK (admitted by {@code isSeatHulkInPlay}, seat unmounted);
     * the live wire map was vacated at {@code seat_mount_loss}, so the caretaker attach can
     * only THROW — and a wreck needs no caretaker. No attach at engage, no detach at the end.
     * The SAME marker parks a POD-BODY resume (RESUMED_POD_BODY):
     * the seat's hull IS the pod flying again — nothing to attach, nothing
     * to detach, no source (F = 0). */
    private record TransitPark(String savedPersonalityId, boolean moduleSource,
            boolean hulkSource) {}

    private final SeatRebindArbiter rebindArbiter;
    private final PilotIntentDatagramServer door;
    /** The serve step owning the staged-frame map — the clear's surface. */
    private final PersistentPilotIntentCombatServer.PilotIntentStep pilotStep;
    /** seatId → the seat's FIXED wire id (the drain runs with no session in hand). */
    private final Map<Integer, Long> seatWireIdBySeatId;
    private final MatchSeatAuthority authority;
    private final EngineCombatBinding binding;
    private final GameplayEdgeEventDetector fireLaneDischargeDetector;
    private final AutofireTargetLane afTargetLane;
    private final ShipNetworkIdRegistry stableIdRegistry;
    private final Map<Integer, SeatAnnounceTracker.SeatAnnounce> lastAnnouncedVariantBySeat;
    private final List<byte[]> roundRosterAppendFrames;
    /** Nullable — grace-OFF serves have no lane; PARKED targets are then unreachable too
     * ({@code abandonedEligible} needs a grace pump to ever mint PARKED). */
    private final SeatGraceLane graceLane;
    /** The round's pod lane — always constructed by the serve (a port-less
     * lane can never hold a flight, so the pump's hasFlights check stays one empty read). */
    private final TransferPodLane podLane;
    /** Nullable — contract fakes bind none (in-flight rows abort LOUD "unavailable"). */
    private final TransferRekeyHook transferRekey;
    /** Nullable — the D7 knob off ({@code transfer.enabled=false}): no census ever publishes,
     * dispatch stays "unavailable"; drain + heartbeat run regardless. */
    private final TransferTargets transferTargets;
    /** Nullable (the wave lane's null-guard verbatim — {@code engineBornGrowth} off):
     * the round's growth-drain dedup set. The mint branch adds the pod's stable id BEFORE
     * this tick's growth drain runs (pump precedes drain in-tick) — the growth seam
     * observes RAW shipList, so an un-deduped pod would double-announce as an engine-born
     * hull under its client-UNRESOLVABLE {@code shuttlepod_xfer} variant (a landmine). */
    private final Set<String> growthKnownKeys;
    private final int round;
    /** The census key — the DEPLOYED station variant id (default family on a
     * station-less serve, where the census-reading paths are inert). */
    private final String stationVariantId;

    /** seatId → transit-park bookkeeping (see the class javadoc transit-PARK item).
     * Engage is IDEMPOTENT at the mint branch — a pod-loss re-mint keeps the original entry. */
    private final Map<Integer, TransitPark> transitParks = new LinkedHashMap<>();

    /** W3 L4 S3: seatId → the target wire id the seat's LAST fanned
     * BEGIN named. The server retargets a flight in-place (ARB:transferBegin replaces the
     * row; TPL setTo-redirects or re-mints the pod) but the mint branch only fans on a pod
     * MINT — this map lets the drain re-fan KIND_BEGIN when a live-pod row's target changed
     * (a pod re-mint fans the fresh pod id via the mint branch as before). Cleared at the
     * abort chokepoint + after COMMIT; the lane is per-round, so wraps clear it with the
     * lane. */
    private final Map<Integer, Long> lastFannedTargetBySeat = new LinkedHashMap<>();

    /** D6 publish belt: even with no dirty mark, re-publish at most this stale (missed-mark
     * insurance; 300 ticks = 5 s at the 60/s serve — the packet's number). */
    private static final long CENSUS_BELT_TICKS = 300;
    /** Starts dirty: the round's first pump publishes the opening census. */
    private boolean censusDirty = true;
    private long lastPublishTick = Long.MIN_VALUE;
    /** The last publish's per-team tables (sim thread only; the drain's team gate reads it). */
    private SideCensus sideCensus = SideCensus.NONE;
    /** Late-join replay rows whose encode threw (a law bug, never
     * serve-fatal: printed on the transfer_catchup line); the guard logs the FIRST only. */
    private long replayEncodeFailed;
    private boolean replayEncodeFailLogged;

    /** Nullable (post-mint setter idiom; contract serves leave it unset): the serve loop's
     * worst-frames ledger, noted at the two convicted stall events (
     * the tag law) — {@code pod_spawn} at the mint branch, {@code transfer_commit}
     * at the commit — so a recurrence self-names in the worst_frame row's causes= fan and
     * {@code first=} becomes meaningful on this lane. */
    private WorstFramesLedger stallTags;

    CommandTransferServeLane(SeatRebindArbiter rebindArbiter, PilotIntentDatagramServer door,
            PersistentPilotIntentCombatServer.PilotIntentStep pilotStep,
            Map<Integer, Long> seatWireIdBySeatId, MatchSeatAuthority authority,
            EngineCombatBinding binding, GameplayEdgeEventDetector fireLaneDischargeDetector,
            AutofireTargetLane afTargetLane, ShipNetworkIdRegistry stableIdRegistry,
            Map<Integer, SeatAnnounceTracker.SeatAnnounce> lastAnnouncedVariantBySeat,
            List<byte[]> roundRosterAppendFrames, SeatGraceLane graceLane,
            TransferPodLane podLane, TransferRekeyHook transferRekey,
            TransferTargets transferTargets, Set<String> growthKnownKeysOrNull, int round,
            String stationVariantId) {
        this.rebindArbiter = rebindArbiter;
        this.door = door;
        this.pilotStep = pilotStep;
        this.seatWireIdBySeatId = seatWireIdBySeatId;
        this.authority = authority;
        this.binding = binding;
        this.fireLaneDischargeDetector = fireLaneDischargeDetector;
        this.afTargetLane = afTargetLane;
        this.stableIdRegistry = stableIdRegistry;
        this.lastAnnouncedVariantBySeat = lastAnnouncedVariantBySeat;
        this.roundRosterAppendFrames = roundRosterAppendFrames;
        this.graceLane = graceLane;
        this.podLane = podLane;
        this.transferRekey = transferRekey;
        this.transferTargets = transferTargets;
        this.growthKnownKeys = growthKnownKeysOrNull;
        this.round = round;
        this.stationVariantId = stationVariantId;
    }

    /** A sim-thread event re-shaped the hull/seat population — next pump re-publishes the
     * census (sim thread only; no lock — the flag rides the serve loop's own ordering). */

    void markCensusDirty() {
        censusDirty = true;
    }

    /** Post-mint arm of the {@link #stallTags} seam (the source.setHulkPoseCadence idiom). */
    void setStallTagLedger(WorstFramesLedger ledger) {
        this.stallTags = ledger;
    }

    /** P4: {@code hullWireId} left the world (the serve loop's despawn-event
     * consumer — DESTROYED and DEPARTED alike, and the re-key lanes' released ids: a flight
     * toward a retired id is equally dead). Every flight toward it aborts LOUD and its pod
     * turns home; the census re-publishes. Sim thread only, like every lane verb. */
    void noteHullDespawned(long hullWireId, long tick) {
        for (int seatId : rebindArbiter.abortFlightsTowardHull(hullWireId)) {
            fanAbort(seatId, "transfer aborted - target destroyed",
                    CommandTransferFrameCodec.REASON_TARGET_DESTROYED, hullWireId, tick);
        }
        censusDirty = true;
    }

    /** The seats THIS pump's TRUE-vacate drain took (the
     * door's own immutable list, re-pointed at the top of every pump — no copy, no lock); read by the pod
     * scenario driver, pumped right after, to abort a run whose seat left. DISPLACED is never in it. */
    private List<Integer> vacatedThisPump = List.of();

    boolean seatVacatedThisPump(int seatId) {
        return vacatedThisPump.contains(seatId);
    }

    /** The once-per-tick drain (sim thread, pre-advance FIRST — a true-vacated MOUNTED seat's
     * dismount deposit must be queued before the module lane's same-tick dismount drain). */
    void pump(long tick) throws IOException {
        door.noteSimTick(tick);
        vacatedThisPump = door.takeTrueSeatVacates();
        for (int seatId : vacatedThisPump) {
            // L3: a leaver's in-flight row dies with the vacate below — read
            // its target FIRST so the wire narration can carry it (PILOT_LEFT).
            Long leaverTarget = rebindArbiter.transferTargetOrNull(seatId);
            rebindArbiter.vacateSeat(seatId);
            long wireId = wireIdFor(seatId);
            boolean stagedDropped = pilotStep.clearStagedIntentOnGraceVacate(wireId);
            System.out.println("npeer-serve seat_vacate seat=" + seatId
                    + " wire_id=" + wireId + " staged_dropped=" + stagedDropped
                    + " round=" + round + " tick=" + tick);
            if (leaverTarget != null) {
                // Chat to the gone seat is a harmless no-op; the pod turn-home rides fanAbort.
                fanAbort(seatId, "transfer aborted - pilot left",
                        CommandTransferFrameCodec.REASON_PILOT_LEFT,
                        leaverTarget.longValue(), tick);
            }
            // F-A: a grace-OFF vacate re-shapes the census NOW (the vacated hull
            // lists "(abandoned)" immediately); without this mark only the 300-tick belt
            // caught it. Grace-ON vacates re-mark at their pump transitions — over-marking
            // is free.
            censusDirty = true;
        }
        // The return-home drain FIRST — a seat that asked
        // for its own hull aborts its flight softly (the pod turns RETURNING via the one
        // abort choke point; the client's abortOwn restores the player as vanilla lands)
        // BEFORE this tick's snapshot, so pump/drain never see the row. Keyed to the flight
        // the reader thread saw: a flight that ended or re-began meanwhile already narrated
        // itself — skip silently.
        for (Map.Entry<Integer, SeatRebindArbiter.ReturnHome> home
                : rebindArbiter.takeReturnHomeRequests().entrySet()) {
            int seatId = home.getKey();
            Long target = rebindArbiter.transferTargetOrNull(seatId);
            if (target == null || target.longValue() != home.getValue().targetHullId()) {
                continue;
            }
            if (podLane.isLandingOrDocked(seatId)) {
                // The engine's landing/COMMIT phase began — never an
                // ABORT mid-commit (the pod can only despawn while the engine's own landing
                // bookkeeping moves the officer anyway ⇒ the flight-#14 split). Refuse; the
                // flight commits this tick or the next, and the seat may transfer home after
                // boarding (the vacated-hull cooldown then applies as for any fresh begin).
                door.fanServerChat(seatId, "return-home refused - the shuttle is already"
                        + " boarding; transfer again once aboard");
                System.out.println("npeer-serve transfer_return_home_refused seat=" + seatId
                        + " target_wire=" + target + " reason=landing round=" + round
                        + " tick=" + tick);
                continue;
            }
            rebindArbiter.transferAbort(seatId);
            fanAbort(seatId, "returning to " + home.getValue().label(),
                    CommandTransferFrameCodec.REASON_RETURN_HOME, target.longValue(), tick);
        }
        // Parked retargets validate + apply BEFORE the snapshot, so the pod
        // pump turns the pod on the applied tick and the drain's re-fan branch fans the BEGIN.
        applyRetargets(tick);
        // ONE in-flight snapshot per tick, shared pod pump → drain: the pod
        // lane reads the same rows the drain then re-checks, so a dock this tick commits
        // this tick once P3 lands. A timed-out seat's abort below consumes its row — the
        // drain's own fresh transferTargetOrNull read then skips it (already-null law).
        LinkedHashMap<Integer, Long> flights = rebindArbiter.inFlightTransfers();
        if (podLane.hasFlights()) {
            Map<Long, String> stableByWireId = new LinkedHashMap<Long, String>();
            for (Map.Entry<String, Long> entry : stableIdRegistry.snapshot().entrySet()) {
                stableByWireId.put(entry.getValue(), entry.getKey());
            }
            for (TransferPodLane.PodEvent event : podLane.pump(tick, flights, stableByWireId)) {
                int seatId = event.seatId();
                if (event.kind() == TransferPodLane.EventKind.TIMED_OUT) {
                    Long timedOutTarget = rebindArbiter.transferAbort(seatId);
                    // KIND_ABORT explicit: the pump leaves a timed-out flight FLYING for
                    // fanAbortEnd's abortToReturning to turn home or strand THIS tick;
                    // the frame carries the pod id = S either way.
                    fanAbortEnd(seatId, "transfer aborted - command shuttle timed out",
                            CommandTransferFrameCodec.REASON_TIMEOUT,
                            CommandTransferFrameCodec.KIND_ABORT,
                            timedOutTarget == null ? 0L : timedOutTarget.longValue(), tick);
                } else if (event.kind() == TransferPodLane.EventKind.RETURNED) {
                    // DOCK-BACK: the return landing finished — re-key the seat onto
                    // its source (under F) and consume the flight.
                    dockBack(seatId, event.sourceWireId(), tick, stableByWireId);
                } else {
                    // STRANDED inside the pump (orphan row / retarget or landing lost): the
                    // flight already ended in place — the seat flies the pod, nothing follows
                    // (the STRANDED law).
                    // CONSUME the arbiter row HERE. Before this a
                    // landing_lost/retarget_lost row stayed LIVE and this tick's drain re-read
                    // it → spawnFlight → isPodBody → RESUMED_POD_BODY: an ABORT then a BEGIN
                    // in one tick, the flight timeout re-based, the seat re-flown at a target
                    // its pod just failed to reach. A seat that wants to fly again issues a
                    // fresh /transfer (RESUMED_POD_BODY by choice). The snapshot's stale row
                    // skips at the drain by the already-null law. Then end the park + free
                    // the source pin, LOUD.
                    Long droppedTarget = rebindArbiter.transferAbort(seatId);
                    System.out.println("npeer-serve pod_stranded_event seat=" + seatId
                            + " source_wire=" + event.sourceWireId()
                            + " row_dropped=" + droppedTarget
                            + " round=" + round + " tick=" + tick);
                    onStranded(seatId, event.sourceWireId(), tick, true);
                }
            }
        }
        drainTransfers(tick, flights);
        publishCensusIfDue(tick);
    }

    /**
     * The D6 census publish: owner-0 alive non-fighter/hulk/module/pod hulls
     * off one bounded engine read, joined to wire ids through the registry snapshot ({@link
     * #censusTable}); seat hulls (wire id ∈ the descriptor seat map) get their status from authority truth
     * (occupied &gt; grace &gt; parked — {@link TransferTargets#seatStatusOrNull}; a seat hull
     * in none of the three is dead/never-mounted and stays unlisted, mirroring the drain's
     * target-gone deny). A failed engine read publishes EMPTY — {@code /transfer} honestly
     * lists nothing rather than a stale table. The
     * pass builds the per-team {@link SideCensus} (the owner-0 table above, a north table off a
     * second engine read only while a seat flies for side 1, and the seat -&gt; team map) and
     * hands ALL of it to the door, which serves each seat its own team's table; the drain's
     * team gate reads the same census.
     */
    private void publishCensusIfDue(long tick) throws IOException {
        if (transferTargets == null) {
            return;
        }
        if (!censusDirty && lastPublishTick != Long.MIN_VALUE
                && tick - lastPublishTick < CENSUS_BELT_TICKS) {
            return;
        }
        censusDirty = false;
        lastPublishTick = tick;
        SortedSet<Integer> occupiedSeats = authority.occupiedSeatIds();
        Map<Integer, Integer> seatSides = seatSidesNow(occupiedSeats);
        List<EngineCombatBinding.TransferCensusRow> engineRows = binding.transferCensusOrNull(0);
        if (engineRows == null) {
            sideCensus = new SideCensus(List.of(), List.of(), seatSides);
            transferTargets.publish(sideCensus);
            return;
        }
        Map<String, Long> wireByStable = stableIdRegistry.snapshot();
        IntFunction<TransferTargets.Status> seatStatus = seatId -> TransferTargets.seatStatusOrNull(
                occupiedSeats.contains(seatId),
                graceLane != null && authority.seatGraceState(seatId)
                        == MatchSeatAuthority.SeatGraceState.GRACE,
                authority.abandonedEligible(seatId));
        List<TransferTargets.PublishRow> rows = censusTable(engineRows, wireByStable,
                seatWireIdBySeatId, seatStatus, rebindArbiter::seatNameOrNull);
        // Second pass: the authored fortress weapon-module seats, hook-census
        // -resolved — a live UNMOUNTED module lists as an AI row under its /module TOKEN (the
        // wire-id vocabulary: the row carries the module's own wire id; labels are
        // server-authored census tokens, never engine text). SKIP wireless (never fanned) and
        // MOUNTED (registry truth: a mounted module's stable rides its seat's wire id — it is
        // another seat's ride, never a /transfer target). A null hook (contract fakes) means
        // no module rows, matching the drain's fail-soft; the first pass never lists modules
        // (transferCensusOrNull's isStationModule exclusion), so the passes are disjoint.
        if (transferRekey != null) {
            for (Map.Entry<String, MatchDescriptors.ModuleSeat> module
                    : moduleCensusByStable().entrySet()) {
                Long wireId = wireByStable.get(module.getKey());
                if (wireId == null || seatIdForWireIdOrNull(wireId) != null) {
                    continue;
                }
                rows.add(new TransferTargets.PublishRow(wireId, module.getValue().token(),
                        TransferTargets.Status.AI, -1, null));
            }
        }
        // The north table, read only while a seat
        // flies for side 1 (a south-only match reads the engine once, as before). The door
        // serves each seat its own team's table; the drain's team gate stays the belt that
        // refuses a cross-team pick resolved off an earlier publish before any shuttle flies.
        List<TransferTargets.PublishRow> northRows = List.of();
        if (seatSides.containsValue(1)) {
            List<EngineCombatBinding.TransferCensusRow> northEngineRows =
                    binding.transferCensusOrNull(1);
            if (northEngineRows != null) {
                northRows = censusTable(northEngineRows, wireByStable, seatWireIdBySeatId,
                        seatStatus, rebindArbiter::seatNameOrNull);
            }
        }
        sideCensus = new SideCensus(rows, northRows, seatSides);
        transferTargets.publish(sideCensus);
    }

    /**
     *
     * The live team of every occupied seat (seat id &gt; 0 -&gt; 0 or 1), the key of its
     * transfer table; a seat whose team does not read (no deployed round) stays out, and
     * the drain's team gate then leaves it to the commit-time check.
     */
    private Map<Integer, Integer> seatSidesNow(SortedSet<Integer> occupiedSeats) {
        Map<Integer, Integer> sides = new LinkedHashMap<Integer, Integer>();
        for (int seatId : occupiedSeats) {
            Long wireId = seatWireIdBySeatId.get(seatId);
            if (wireId == null) {
                continue;
            }
            int side = SeatRebind.seatLiveSideOrNeg(seatId, wireId.longValue());
            if (side == 0 || side == 1) {
                sides.put(seatId, side);
            }
        }
        return Map.copyOf(sides);
    }

    /**
     *
     * One team's transfer table off its engine census, in roster order: a hull with no wire
     * id yet is unlisted (never fanned to clients); a non-seat hull lists as {@code AI}; a
     * seat hull carries its seat status (occupied &gt; grace &gt; parked) and its seat name,
     * and stays unlisted when it has none of the three (dead or never mounted, the drain's
     * target-gone deny). {@code seatStatusOrNull} runs only for seat hulls and
     * {@code seatNameOrNull} only for listed ones. Public ONLY for contract reach.
     */
    public static List<TransferTargets.PublishRow> censusTable(
            List<EngineCombatBinding.TransferCensusRow> engineRows,
            Map<String, Long> wireByStable, Map<Integer, Long> seatWireIdBySeatId,
            IntFunction<TransferTargets.Status> seatStatusOrNull,
            IntFunction<String> seatNameOrNull) {
        List<TransferTargets.PublishRow> rows =
                new ArrayList<TransferTargets.PublishRow>(engineRows.size());
        for (EngineCombatBinding.TransferCensusRow engineRow : engineRows) {
            Long wireId = wireByStable.get(engineRow.stableId());
            if (wireId == null) {
                continue;
            }
            String label = engineRow.shipNameOrNull() == null ? "" : engineRow.shipNameOrNull();
            Integer seatId = seatIdIn(seatWireIdBySeatId, wireId);
            if (seatId == null) {
                rows.add(new TransferTargets.PublishRow(wireId, label,
                        TransferTargets.Status.AI, -1, null));
                continue;
            }
            TransferTargets.Status status = seatStatusOrNull.apply(seatId);
            if (status == null) {
                continue;
            }
            rows.add(new TransferTargets.PublishRow(wireId, label, status, seatId,
                    seatNameOrNull.apply(seatId)));
        }
        return rows;
    }

    /**
     *
     * One publish's census split by team: the south (side 0) and north (side 1) tables and
     * the seat -&gt; team map that picks a seat's table. The north table is empty while no
     * seat flies for side 1. Immutable.
     */
    public record SideCensus(List<TransferTargets.PublishRow> south,
            List<TransferTargets.PublishRow> north, Map<Integer, Integer> seatSides) {

        /** Before the first publish: no seat has a team, so the gate never refuses. */
        static final SideCensus NONE = new SideCensus(List.of(), List.of(), Map.of());

        public SideCensus {
            south = List.copyOf(south);
            north = List.copyOf(north);
            seatSides = Map.copyOf(seatSides);
        }

        /**
         * The team gate: the cross-team refusal when {@code seatId}'s team is known and
         * {@code targetWireId} lists ONLY on the other team's table; null otherwise (a
         * hull on the seat's own table, or on neither, goes on to the drain's own checks).
         */
        public String crossTeamDenyOrNull(int seatId, long targetWireId) {
            Integer side = seatSides.get(seatId);
            if (side == null) {
                return null;
            }
            boolean own = lists(side == 1 ? north : south, targetWireId);
            boolean other = lists(side == 1 ? south : north, targetWireId);
            return !own && other ? SeatRebind.DENY_NOT_FRIENDLY : null;
        }

        private static boolean lists(List<TransferTargets.PublishRow> table, long wireId) {
            for (TransferTargets.PublishRow row : table) {
                if (row.hullWireId() == wireId) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * ONE hook-census call → stableId → authored census row (token + slot id) — the
     * drain's module-branch key and the publish second pass's source. The join is by SLOT id
     * (module identity is by slot, never variant — WS 012/WS 014 share a variant); a hook row
     * whose slot is outside the authored vocabulary is dropped (the census-law belt: the walk
     * never invents slots). EMPTY on a non-fortress serve (the verb's own fail-soft) and on a
     * null hook.
     */
    private Map<String, MatchDescriptors.ModuleSeat> moduleCensusByStable() throws IOException {
        if (transferRekey == null) {
            return Map.of();
        }
        Map<String, MatchDescriptors.ModuleSeat> bySlot = new LinkedHashMap<>();
        for (MatchDescriptors.ModuleSeat seat : MatchDescriptors.moduleSeatCensus(stationVariantId)) {
            bySlot.put(seat.slotId(), seat);
        }
        Map<String, MatchDescriptors.ModuleSeat> byStable = new LinkedHashMap<>();
        for (SeatRebind.StationModuleCensusRow row
                : transferRekey.stationModuleCensus(bySlot.keySet())) {
            MatchDescriptors.ModuleSeat seat = bySlot.get(row.slotId());
            if (seat != null) {
                byStable.put(row.stableId(), seat);
            }
        }
        return byStable;
    }

    /**
     * The drain's target-shape classification, shared with {@link #applyRetargets}:
     * seat-hull facts off the authority (occupied / reconnect-grace / parked-abandoned)
     * + registered-or-not — the SAME verdict a retarget must pass before its row moves.
     */
    private TransferEligibility.Verdict classifyTarget(Integer targetSeatId, String targetStable,
            SortedSet<Integer> occupiedSeats) {
        boolean seatHull = targetSeatId != null;
        boolean occupied = seatHull && occupiedSeats.contains(targetSeatId);
        boolean inGrace = seatHull && graceLane != null
                && authority.seatGraceState(targetSeatId)
                        == MatchSeatAuthority.SeatGraceState.GRACE;
        boolean parked = seatHull && authority.abandonedEligible(targetSeatId);
        return TransferEligibility.classify(seatHull, occupied, inGrace, parked,
                targetStable != null);
    }

    /**
     * A GRANTED mid-flight
     * retarget PARKS at the arbiter ({@code takeRetargetRequests}) and the live row keeps the
     * PRIOR target until this step validates the new id with the drain's own facts — a
     * registered stable id ({@link #classifyTarget}) and census-alive — and moves the row
     * ({@code transferRetargetApply}; a PRIORITY contest lost since the grant denies there).
     * A deny leaves the prior flight untouched: chat + LOUD, no frame (the retarget class is
     * chat-only by the arbiter's {@code denyReason} law — the client's denyOwn guard drops
     * mid-transit frames). Before this validation the begin overwrote the row and an unregistered id
     * STRANDED the seat in the pod pump (retarget_lost) BEFORE the drain's DENY — pilotable
     * mid-space with a live home a plain abort would have flown to. Runs BEFORE the tick's
     * snapshot; the common tick reads one empty take and returns (frame-residency law).
     */
    private void applyRetargets(long tick) {
        LinkedHashMap<Integer, Long> requests = rebindArbiter.takeRetargetRequests();
        if (requests.isEmpty()) {
            return;
        }
        Map<Long, String> stableByWireId = new LinkedHashMap<Long, String>();
        for (Map.Entry<String, Long> entry : stableIdRegistry.snapshot().entrySet()) {
            stableByWireId.put(entry.getValue(), entry.getKey());
        }
        SortedSet<Integer> occupiedSeats = authority.occupiedSeatIds();
        for (Map.Entry<Integer, Long> request : requests.entrySet()) {
            int seatId = request.getKey();
            long targetWireId = request.getValue();
            Long prior = rebindArbiter.transferTargetOrNull(seatId);
            if (prior == null) {
                // The flight ended between the grant and this pump (vacate/abort/commit
                // raced the park) — nothing to move, the seat already heard why.
                System.out.println("npeer-serve transfer_retarget_dropped seat=" + seatId
                        + " target_wire=" + targetWireId + " reason=no_flight"
                        + " round=" + round + " tick=" + tick);
                continue;
            }
            String targetStable = stableByWireId.get(targetWireId);
            TransferEligibility.Verdict verdict = classifyTarget(
                    seatIdForWireIdOrNull(targetWireId), targetStable, occupiedSeats);
            // The drain's team gate, at the retarget too.
            String teamDeny = sideCensus.crossTeamDenyOrNull(seatId, targetWireId);
            String deny;
            if (teamDeny != null) {
                deny = teamDeny.substring("transfer aborted - ".length());
            } else if (verdict.kind() == TransferEligibility.Kind.DENY) {
                deny = verdict.denyReason().startsWith("transfer aborted - ")
                        ? verdict.denyReason().substring("transfer aborted - ".length())
                        : verdict.denyReason();
            } else if (!binding.isTransferTargetAlive(targetStable)) {
                deny = "target destroyed";
            } else if (!rebindArbiter.transferRetargetApply(seatId, targetWireId, tick)) {
                deny = "target already claimed";
            } else {
                deny = null;
            }
            if (deny != null) {
                door.fanServerChat(seatId, "retarget refused - " + deny
                        + "; still flying to the previous target");
                System.out.println("npeer-serve transfer_retarget_denied seat=" + seatId
                        + " target_wire=" + targetWireId + " prior_wire=" + prior
                        + " reason=\"" + deny + "\" round=" + round + " tick=" + tick);
                continue;
            }
            System.out.println("npeer-serve transfer_retarget_applied seat=" + seatId
                    + " prior_wire=" + prior + " target_wire=" + targetWireId
                    + " round=" + round + " tick=" + tick);
        }
    }

    /**
     * The D3 drain: the pump's shared in-flight snapshot
     * (non-consuming — a row that fails a transient re-check survives for the next tick
     * unless aborted here), each row re-checked authoritatively, survivors committed through
     * the engine verb. The common tick reads one empty snapshot and returns —
     * frame-residency law.
     */
    private void drainTransfers(long tick, LinkedHashMap<Integer, Long> flights)
            throws IOException {
        if (flights.isEmpty()) {
            return;
        }
        if (transferRekey == null) {
            // Unbound engine verb (contract fakes): the seatSuicide fail-soft class — abort
            // every row LOUD before any authority/grace state is touched.
            for (int seatId : flights.keySet()) {
                Long droppedTarget = rebindArbiter.transferAbort(seatId);
                fanAbort(seatId, "transfer unavailable on this server",
                        CommandTransferFrameCodec.REASON_UNAVAILABLE,
                        droppedTarget == null ? 0L : droppedTarget.longValue(), tick);
            }
            return;
        }
        // Fresh per-pump facts, built once: the connected-seat set (the session-observe read)
        // and the registry-snapshot inversion (wireId → stable engine id; covers seat hulls
        // and above-band AI hulls alike — the registry has no reverse map).
        Set<Integer> connectedSeats = new LinkedHashSet<Integer>();
        for (PilotIntentDatagramServer.PeerSession session : door.liveSessions()) {
            connectedSeats.add(session.seatId());
        }
        Map<Long, String> stableByWireId = new LinkedHashMap<Long, String>();
        for (Map.Entry<String, Long> entry : stableIdRegistry.snapshot().entrySet()) {
            stableByWireId.put(entry.getValue(), entry.getKey());
        }
        SortedSet<Integer> occupiedSeats = authority.occupiedSeatIds();
        // ONE hook-census call per flights-bearing pump — the module branch below
        // keys on it at the verb call ONLY (everything upstream — eligibility, alive gate,
        // pod flight, dock gate — is target-shape-agnostic by design).
        Map<String, MatchDescriptors.ModuleSeat> moduleByStable = moduleCensusByStable();
        for (Map.Entry<Integer, Long> row : flights.entrySet()) {
            int seatId = row.getKey();
            long targetWireId = row.getValue();
            Long fresh = rebindArbiter.transferTargetOrNull(seatId);
            if (fresh == null || fresh.longValue() != targetWireId) {
                // Raced away mid-pump (retarget or vacate on the reader thread) — the next
                // tick's snapshot owns the row's new shape; nothing to do for the old one.
                continue;
            }
            String winnerAbort = winnerAbortReasonOrNull(seatId, connectedSeats,
                    stableByWireId.get(wireIdFor(seatId)));
            if (winnerAbort != null) {
                rebindArbiter.transferAbort(seatId);
                fanAbort(seatId, winnerAbort,
                        CommandTransferFrameCodec.REASON_WINNER_INELIGIBLE, targetWireId, tick);
                continue;
            }
            // The team gate — a hull of the OTHER team
            // is refused here, before a shuttle flies or a parked owner is unseated (the commit
            // would refuse it only at the dock, after both, with the same words).
            String teamDeny = sideCensus.crossTeamDenyOrNull(seatId, targetWireId);
            if (teamDeny != null) {
                rebindArbiter.transferAbort(seatId);
                fanAbort(seatId, teamDeny,
                        CommandTransferFrameCodec.REASON_TARGET_INELIGIBLE, targetWireId, tick);
                continue;
            }
            Integer targetSeatId = seatIdForWireIdOrNull(targetWireId);
            String targetStable = stableByWireId.get(targetWireId);
            TransferEligibility.Verdict verdict =
                    classifyTarget(targetSeatId, targetStable, occupiedSeats);
            if (verdict.kind() == TransferEligibility.Kind.DENY) {
                rebindArbiter.transferAbort(seatId);
                fanAbort(seatId, verdict.denyReason(),
                        CommandTransferFrameCodec.REASON_TARGET_INELIGIBLE, targetWireId, tick);
                continue;
            }
            // W3-G: flight-phase death-to-hulk re-check — a hulk stays in
            // the world (no despawn, so noteHullDespawned never fires) and keeps its registry
            // entry (so classify() marches the row on), leaving the pod to chase a corpse to a
            // dock-time deny. Census-consistent aliveness, fail-closed; ONE insert before the
            // pod/commit block covers AI_HULL and PARKED_TAKEOVER alike. Client abortOwn is
            // reason-agnostic — the pod turns home and the seat restores.
            if (!binding.isTransferTargetAlive(targetStable)) {
                rebindArbiter.transferAbort(seatId);
                fanAbort(seatId, "transfer aborted - target destroyed",
                        CommandTransferFrameCodec.REASON_TARGET_DESTROYED, targetWireId, tick);
                System.out.println("npeer-serve transfer_flight_target_dead seat=" + seatId
                        + " target_wire=" + targetWireId + " round=" + round + " tick=" + tick);
                continue;
            }
            long seatWireId = wireIdFor(seatId);
            // P3: the commit is dock-gated on the physical flight — a surviving
            // row with no pod mints one here (drain-owned; a RETURNING straggler is
            // superseded inside spawnFlight), and an undocked pod means no commit this tick.
            // The pod pump already advanced every flight against THIS tick's snapshot, so
            // isDocked is tick-fresh (a dock this tick commits this tick).
            if (!podLane.hasPod(seatId)) {
                TransferPodLane.Begin begin = podLane.spawnFlight(seatId,
                        stableByWireId.get(seatWireId), targetWireId, targetStable, tick);
                if (begin == TransferPodLane.Begin.DENIED) {
                    rebindArbiter.transferAbort(seatId);
                    fanAbort(seatId, "transfer aborted - command shuttle unavailable",
                            CommandTransferFrameCodec.REASON_ENGINE_DENY, targetWireId, tick);
                    continue;
                }
                if (begin == TransferPodLane.Begin.SPAWNED) {
                    // The seat leaves its hull onto the pod NOW.
                    if (!beginOntoSpawnedPod(seatId, seatWireId, targetWireId, tick)) {
                        continue;
                    }
                } else if (begin == TransferPodLane.Begin.RESUMED_POD_BODY) {
                    beginFromPodBody(seatId, seatWireId, targetWireId, tick);
                } else if (begin == TransferPodLane.Begin.RETARGETED_RETURNING) {
                    // A RETURNING pod turned toward the new target in place — the
                    // seat is still aboard and the park still stands; BEGIN fan only
                    // (from = the source under F, pod = S).
                    fanTransferFrame(CommandTransferFrameCodec.KIND_BEGIN, seatId,
                            fromIdOrSeat(seatId, seatWireId), targetWireId, seatWireId,
                            CommandTransferFrameCodec.REASON_NONE);
                    lastFannedTargetBySeat.put(seatId, targetWireId);
                    System.out.println("npeer-serve transfer_return_retargeted seat=" + seatId
                            + " target_wire=" + targetWireId + " round=" + round
                            + " tick=" + tick);
                } else {
                    // ALREADY_FLYING is unreachable behind the hasPod gate — LOUD no-op.
                    System.out.println("npeer-serve transfer_begin_noop seat=" + seatId
                            + " begin=" + begin + " round=" + round + " tick=" + tick);
                }
            } else {
                // Server half: a live-pod row whose target changed since the last
                // fanned BEGIN = an in-place retarget (TPL setTo — the pod IS the seat's
                // hull, so the pod id S is stable by construction) — re-fan BEGIN so
                // clients track the episode's new target under the same pod.
                Long lastFanned = lastFannedTargetBySeat.get(seatId);
                if (lastFanned == null || lastFanned.longValue() != targetWireId) {
                    fanTransferFrame(CommandTransferFrameCodec.KIND_BEGIN, seatId,
                            fromIdOrSeat(seatId, seatWireId), targetWireId, seatWireId,
                            CommandTransferFrameCodec.REASON_NONE);
                    lastFannedTargetBySeat.put(seatId, targetWireId);
                    System.out.println("npeer-serve transfer_retarget_refan seat=" + seatId
                            + " target_wire=" + targetWireId + " pod_wire=" + seatWireId
                            + " round=" + round + " tick=" + tick);
                }
            }
            if (!podLane.isDocked(seatId)) {
                continue;
            }
            boolean parkedTakeover = verdict.kind() == TransferEligibility.Kind.PARKED_TAKEOVER;
            if (parkedTakeover) {
                // D5 pre-steps BEFORE the verb: detach the suppress-whitelisted grace AI (its
                // restore leaves a sane hull if the verb denies) and unseat the parked owner —
                // recordMountLoss HULL_DEPARTED, the CROSS-seat vacate class (bind kept:
                // never recordVoluntaryDismount — wrong telemetry label, no id-verify; the
                // commit DOES produce a despawn of the parked seat's freed id, swallowed by the
                // takeover echo armed at the rekey below — the parked-takeover despawn crash).
                // The owner re-admits into the normal respawn path.
                if (graceLane != null) {
                    graceLane.clearForTakeover(targetSeatId, tick);
                }
                authority.recordMountLoss(targetSeatId, SeatStateCause.HULL_DEPARTED, tick);
            }
            SeatRebindArbiter.TransferCommit commit = rebindArbiter.transferCommitWinner(seatId, tick);
            if (commit.targetHullId() != targetWireId) {
                // Sub-tick retarget between the fresh-read above and the commit: the flight
                // (toward the NEW target) is consumed — an honest rare loss, LOUD.
                System.out.println("npeer-serve transfer_commit_mismatch seat=" + seatId
                        + " checked_target=" + targetWireId
                        + " committed_target=" + commit.targetHullId()
                        + " round=" + round + " tick=" + tick);
                fanAbort(seatId, "transfer aborted - target changed mid-commit",
                        CommandTransferFrameCodec.REASON_COMMIT_RACE, targetWireId, tick);
                fanLosers(commit, tick);
                continue;
            }
            // The ONE branch point — a census-known module stable commits through
            // the module verb (stable-id resolve, no displacement path); every other target
            // rides the hull verb unchanged. The rekey SOURCE is the pod
            // (live-mapped under S since BEGIN) — the source-half already happened at BEGIN;
            // this is the TARGET-half, shared with the dock-back through seatOntoHull.
            MatchDescriptors.ModuleSeat moduleSeat = moduleByStable.get(targetStable);
            HullMount mount = seatOntoHull(seatId, seatWireId, targetWireId, targetStable,
                    moduleSeat, tick, MOUNT_KIND_COMMIT);
            if (mount.rekey().denyReason() != null) {
                // Engine-fact deny under the roster lock (mid-flight target death race) — the
                // commit consumed the arbiter row (as transferAbort would have); the flight is
                // STILL ALIVE in the pod lane (DOCKED), so fanAbort's abort end turns it home
                // onto a live source or strands the seat on the pod.
                fanAbort(seatId, mount.rekey().denyReason(),
                        CommandTransferFrameCodec.REASON_ENGINE_DENY, targetWireId, tick);
                fanLosers(commit, tick);
                continue;
            }
            if (parkedTakeover) {
                // W3-FIX: the release inside seatOntoHull freed the PARKED seat's own
                // fixed wire id (oldTargetId), which the despawn pass will see leave the
                // snapshot THIS tick — but the mount loss was already recorded at the
                // pre-step. Arm the one-shot id-verified echo so that single despawn is
                // swallowed; every other seat-mapped despawn stays fail-closed. (The older
                // comment's "a takeover never produces a despawn" premise was FALSE for parked
                // takeover — the crash this fixes.)
                authority.armTakeoverDespawnEcho(targetSeatId, mount.oldTargetId(), tick);
            }
            fanLosers(commit, tick);
            // L3 COMMIT — MMNT stale-map semantics verbatim: from = the
            // source hull's above-band id F (minted at BEGIN; 0 for a pod-body flight), to =
            // the target's RETIRING old id; pod = 0 (despawned with the commit).
            // WIRE LAW: the codec REJECTS from < 1 - a pod-body
            // flight has no F, so it fans from = S ("nothing else survives"; the one-entity
            // client ignores from on COMMIT - the source re-homed at BEGIN).
            fanTransferFrame(CommandTransferFrameCodec.KIND_COMMIT, seatId,
                    mount.sourceWireId() > 0L ? mount.sourceWireId() : seatWireId,
                    mount.oldTargetId(), 0L, CommandTransferFrameCodec.REASON_NONE);
            lastFannedTargetBySeat.remove(seatId);
            System.out.println("npeer-serve transfer_commit seat=" + seatId
                    + " wire_id=" + seatWireId + " target_wire=" + targetWireId
                    + " source_stable=" + mount.rekey().sourceStableId()
                    + " target_stable=" + mount.rekey().targetStableId()
                    + " fresh_source_id=" + mount.sourceWireId()
                    + " old_target_id=" + mount.oldTargetId()
                    + " parked_seat=" + (parkedTakeover ? String.valueOf(targetSeatId) : "none")
                    + " module_slot=" + (moduleSeat == null ? "none" : moduleSeat.slotId())
                    + " losers=" + commit.losers().size()
                    + " flushed_decls=" + mount.flushedDecls()
                    + " gpee_reset=" + mount.gpeeReset()
                    + " autofire_off=" + mount.autofireOff()
                    + " round=" + round + " tick=" + tick);
        }
    }

    /**
     * BEGIN onto a freshly SPAWNED pod — the
     * SOURCE-half of the former commit block, moved here: the seat leaves its hull onto the
     * pod NOW and rides it for the whole flight. Order: stable read (null ⇒ despawn BEFORE
     * the fan, so the generic kind read honestly DENIES — no pod ever crossed the wire) →
     * growth dedup → park engage (its caretaker attach resolves S = the source, still
     * live-mapped) → {@code mountSeatOnPod} (deny ⇒ despawn + abort + park end BY S: nothing
     * was re-keyed) → registry: releaseForRebind(source) → F = networkIdFor(source)
     * → internReservedMount(pod, S) (never mint a pod id first) → the moved source-half
     * (hulk mount record, declaration/staged hygiene, fire-lane baselines, module-source
     * DISMOUNT under F, source RAPP under F, the seat's own RAPP repoint S → cc_pod + the
     * fire-class latches, hull-vacated cooldown on F) → BEGIN fan (from = F, to = target,
     * pod = S). Returns false when the row aborted (the caller skips to the next row).
     */
    private boolean beginOntoSpawnedPod(int seatId, long seatWireId, long targetWireId,
            long tick) throws IOException {
        // Stall-tag law: the spawn is a convicted stall class — self-name it.
        if (stallTags != null) {
            stallTags.note(tick, "pod_spawn");
        }
        String podStable = podLane.podStableIdOrNull(seatId);
        if (podStable == null) {
            // An unidentifiable pod must not exist on a lifted wire: its
            // growth-seam capture would announce the client-unresolvable shuttlepod_xfer
            // variant.
            podLane.despawnFlight(seatId, tick);
            rebindArbiter.transferAbort(seatId);
            System.out.println("npeer-serve pod_stable_null seat=" + seatId
                    + " round=" + round + " tick=" + tick);
            fanAbort(seatId, "transfer aborted - command shuttle unavailable",
                    CommandTransferFrameCodec.REASON_ENGINE_DENY, targetWireId, tick);
            return false;
        }
        if (growthKnownKeys != null) {
            growthKnownKeys.add(podStable); // lane-announced (the seat's RAPP repoint), never growth
        }
        // moduleSource read BEFORE the rekey flips any arbiter state (F-B): TRUE =
        // the seat rides a fortress module and this BEGIN is a transfer-OUT of it.
        boolean moduleSource = rebindArbiter.isMounted(seatId);
        engageTransitPark(seatId, seatWireId, tick);
        // The lane's own pod handle rides along — the spawn is pending-add on
        // the engine this tick, so a stable-id scan alone denies (a real-engine FAIL).
        SeatRebind.TransferRekeyResult rekey =
                transferRekey.mountSeatOnPod(seatId, seatWireId, podStable,
                        podLane.podObjectOrNull(seatId));
        if (rekey.denyReason() != null) {
            // Nothing re-keyed yet: S is still the source, so the park end's detach by S
            // hits the right hull. The despawn precedes the fan so hasPod reads false and
            // the generic kind honestly DENIES; fanAbortEnd's own park end then no-ops on
            // the already-emptied map.
            podLane.despawnFlight(seatId, tick);
            rebindArbiter.transferAbort(seatId);
            endTransitParkOnAbort(seatId, seatWireId, tick);
            System.out.println("npeer-serve pod_mount_denied seat=" + seatId
                    + " reason=\"" + rekey.denyReason() + "\" round=" + round
                    + " tick=" + tick);
            fanAbort(seatId, "transfer aborted - command shuttle unavailable",
                    CommandTransferFrameCodec.REASON_ENGINE_DENY, targetWireId, tick);
            return false;
        }
        // Registry re-key: the source hull retires onto a FRESH above-band id F (a
        // released above-band id never re-points a client's stale binding); the seat's
        // reserved wire id S re-points onto the pod.
        stableIdRegistry.releaseForRebind(rekey.sourceStableId());
        long freshSourceId = stableIdRegistry.networkIdFor(rekey.sourceStableId());
        stableIdRegistry.internReservedMount(podStable, seatWireId);
        podLane.notePodWireId(seatId, seatWireId);
        podLane.noteSourceWireId(seatId, freshSourceId);
        if (!authority.isMounted(seatId)) {
            // A dead seat that flew its shuttle out of its
            // wreck is piloting again (the pod) — NONE→HULL here (the fly-in's own mount
            // record), else the seat reads unmounted while piloting and the deferred respawn
            // fly-in replaces its body the very next tick. Landing this at BEGIN closes the
            // flyInDeferredForTransfer window for the whole flight.
            authority.recordMountEstablished(seatId);
            System.out.println("npeer-serve transfer_begin_from_hulk seat=" + seatId
                    + " source_stable=" + rekey.sourceStableId()
                    + " round=" + round + " tick=" + tick);
        }
        // The ModuleSeatServeLane.tryMountClaim hygiene family: the old hull's declarations/staged intent
        // must not carry onto the pod, and the fire lane's baselines re-arm on the seat's
        // re-pointed body (no shim/autofire re-arm — the pod carries no weapons; the commit
        // does those on the target).
        // Keyed by the seat's FIXED wire id
        // (applyStateByShipId), never the seat id.
        int flushedDecls = pilotStep.flushDeclarationsOnHullLoss(seatWireId);
        pilotStep.clearStagedIntentOnHullLoss(seatWireId);
        int gpeeReset = fireLaneDischargeDetector.resetShipBaselines(seatWireId);
        // F-B module-SOURCE inserts: the seat transferred OUT of a fortress
        // module. Arbiter truth FIRST — noteDismounted mirrors the /module-off drain's table
        // clear — then the MMNT KIND_DISMOUNT fan (otherWireId = the module's FRESH above-band
        // id F; the MSSL dismount-fan field shape verbatim) so the client's own-mounted table
        // clears (a stale table latches holdsOwnRespawn and suppresses the seat's respawn edge
        // all round), ordered before the RAPP rows (MMNT≺RAPP, single-writer CTRL FIFO).
        // recordVoluntaryDismount + the dismount echo are deliberately SKIPPED: the mover
        // KEEPS its mount axis, re-pointed onto the pod — the seat's wire id never leaves the
        // roster, so no despawn wants swallowing. noteHullVacated below arms the hull cooldown
        // on the fresh module id, same-as-hulls (accepted).
        if (moduleSource) {
            String sourceToken = rebindArbiter.mountedTokenOrNull(seatId);
            rebindArbiter.noteDismounted(seatId);
            door.fanModuleMount(ModuleMountFrameCodec.encode(
                    new ModuleMountFrameCodec.MountHandover(seatWireId, freshSourceId,
                            ModuleMountFrameCodec.KIND_DISMOUNT,
                            ModuleSeatServeLane.censusRowFor(sourceToken,
                                    stationVariantId).slotId())),
                    ModuleMountFrameCodec.KIND_DISMOUNT);
            System.out.println("npeer-serve transfer_module_source seat=" + seatId
                    + " token=" + sourceToken + " fresh_source_id=" + freshSourceId
                    + " round=" + round + " tick=" + tick);
        }
        // The re-homed SOURCE's identity rides the SAME fan —
        // one RAPP row (fresh id F + the engine-true variant read pre-verb) fanned BEFORE the
        // seat's repoint row and the KIND_BEGIN frame (single-writer CTRL FIFO), so every
        // client folds the retiree's true shape before any apply can infer it from the
        // post-repoint seat row (observers registered the retired aurora as a MERLON under 54
        // and seat1 later FIELDED it). Unconditional — a same-variant transfer fans no seat
        // repoint yet still owes the source row. Module-source rows are inert client-side
        // (the fold count-ignores module rows). Empty variant read = fail-closed to the
        // legacy client inference, LOUD. Late-join replay rides roundRosterAppendFrames.
        if (rekey.sourceVariantId() != null && !rekey.sourceVariantId().isEmpty()) {
            PersistentPilotIntentCombatServer.fanRosterAppendChunks(door,
                    List.of(sourceAnnounceRow(freshSourceId, rekey)), roundRosterAppendFrames);
            // The row's two captain facts + its pose flag;
            // captain_fallback=1 counts a curated captain the bare row cannot carry.
            System.out.println("npeer-serve transfer_source_announce seat=" + seatId
                    + " fresh_source_id=" + freshSourceId
                    + " variant=" + rekey.sourceVariantId()
                    + " all_skills=" + (rekey.sourceAllSkillsCaptain() ? 1 : 0)
                    + " captain_fallback=" + (rekey.sourceCaptainFallback() ? 1 : 0)
                    + " at_rest=1"
                    + " round=" + round + " tick=" + tick);
        } else {
            System.out.println("npeer-serve transfer_source_announce SKIPPED (empty source"
                    + " variant) seat=" + seatId + " fresh_source_id=" + freshSourceId
                    + " round=" + round + " tick=" + tick);
        }
        // The seat's own RAPP repoint S → cc_pod (THIS is the pod's wire row — no
        // standalone pod row; fanned BEFORE the XFER BEGIN that references S, the
        // replay-ordering law's mint-time twin) + the fire-class latches on the re-pointed
        // body, as the commit does for its target.
        if (SeatAnnounceTracker.announceSeatVariantIfRepointed(door, seatId,
                new PersistentPilotIntentCombatServer.NPeerHooks.FlyInResult(seatWireId,
                        rekey.targetStableId(), rekey.variantId(),
                        rekey.descriptorVariantId(), rekey.side(), rekey.shipName(),
                        rekey.crPerMille()),
                lastAnnouncedVariantBySeat, roundRosterAppendFrames)) {
            pilotStep.fireClassLane().latchMaskStalenessGate(seatWireId, tick, seatId);
            pilotStep.fireClassLane().rebindFireClassResolverAtRepoint(seatWireId, tick,
                    seatId);
            afTargetLane.resetShip(seatWireId);
        }
        rebindArbiter.noteHullVacated(freshSourceId, tick);
        censusDirty = true;
        // L3 BEGIN: from = the source's fresh id F, to = the target, pod = S — the
        // client's one-entity bypass signal.
        fanTransferFrame(CommandTransferFrameCodec.KIND_BEGIN, seatId, freshSourceId,
                targetWireId, seatWireId, CommandTransferFrameCodec.REASON_NONE);
        lastFannedTargetBySeat.put(seatId, targetWireId);
        System.out.println("npeer-serve transfer_begin seat=" + seatId
                + " wire_id=" + seatWireId + " target_wire=" + targetWireId
                + " source_stable=" + rekey.sourceStableId() + " pod_stable=" + podStable
                + " fresh_source_id=" + freshSourceId + " module_source=" + moduleSource
                + " flushed_decls=" + flushedDecls + " gpee_reset=" + gpeeReset
                + " round=" + round + " tick=" + tick);
        return true;
    }

    /**
     * The re-homed SOURCE's announce row under its fresh
     * wire id {@code freshId} (u32, &gt; 0): the variant, ship name and CR the re-key read, never an
     * officer line, and the {@link RosterAppendFrameCodec#FLAG_ALL_SKILLS_CAPTAIN} bit iff the
     * re-key says the hull's restored captain IS its all-skills seat mint (that person is past the
     * officer-line grammar, so the bit is the only way to say it). {@code atRest} is TRUE on both
     * arms: the source is a posed LIVE hull, so a client that MINTS the twin from this row (a late
     * join, a rejoin mid-transfer) places it at its pose instead of flying it in; a client that
     * already holds the body binds the row to it and reads neither field.
     *
     * Public for the contract rows.
     */
    public static RosterAppendFrameCodec.AppendRow sourceAnnounceRow(long freshId,
            SeatRebind.TransferRekeyResult rekey) {
        if (rekey.sourceAllSkillsCaptain()) {
            return RosterAppendFrameCodec.AppendRow.allSkillsCaptain(freshId, rekey.side(),
                    rekey.sourceVariantId(), rekey.shipName(), rekey.sourceCrPerMille(), true);
        }
        return new RosterAppendFrameCodec.AppendRow(freshId, rekey.side(), rekey.sourceVariantId(),
                rekey.shipName(), rekey.sourceCrPerMille(), 0L, RosterAppendFrameCodec.BAY_NONE, "",
                true);
    }

    /**
     * BEGIN from a POD BODY (RESUMED_POD_BODY): the
     * seat's hull IS a stranded pod that just flew again as itself — no spawn, no re-key (S
     * is the pod already), no source (F = 0), no RAPP row (the seat already announces
     * cc_pod). Park = the hulk-source marker reused (nothing to attach, nothing to detach —
     * see {@link TransitPark}), idempotent like {@link #engageTransitPark}; BEGIN fan
     * (from = S, to = target, pod = S).
     */
    private void beginFromPodBody(int seatId, long seatWireId, long targetWireId, long tick) {
        boolean stagedDropped = pilotStep.clearStagedIntentOnGraceVacate(seatWireId);
        transitParks.putIfAbsent(seatId, new TransitPark(null, false, true));
        pilotStep.parkTransit(seatWireId);
        podLane.notePodWireId(seatId, seatWireId);
        System.out.println("npeer-serve transit_park seat=" + seatId
                + " wire_id=" + seatWireId + " pod_body=true attach=skipped"
                + " staged_dropped=" + stagedDropped
                + " round=" + round + " tick=" + tick);
        censusDirty = true;
        fanTransferFrame(CommandTransferFrameCodec.KIND_BEGIN, seatId, seatWireId,
                targetWireId, seatWireId, CommandTransferFrameCodec.REASON_NONE);
        lastFannedTargetBySeat.put(seatId, targetWireId);
    }

    /** {@link #seatOntoHull}'s {@code kind}: the P3 commit (pod → target; the park ends with
     * the pin released by F — AI-flown IS the vanilla end state). */
    private static final String MOUNT_KIND_COMMIT = "transfer_commit";
    /** {@link #seatOntoHull}'s {@code kind}: the dock-back (pod → the source under F;
     * the park ends with the saved personality RESTORED by S — the source is the seat's hull
     * again). */
    private static final String MOUNT_KIND_DOCK_BACK = "transfer_dock_back";

    /** {@link #seatOntoHull}'s result: the engine verb's answer (deny ⇒ the other fields are
     * 0 — nothing mutated), the flight's source id F ({@code sourceWireId}; 0 for a pod-body
     * flight), the target's RETIRING old id, and the hygiene counters for the caller's log. */
    private record HullMount(SeatRebind.TransferRekeyResult rekey, long sourceWireId,
            long oldTargetId, int flushedDecls, int gpeeReset, int autofireOff) {}

    /**
     * The TARGET-half of a seat re-key — the pod
     * (live-mapped under S) hands the seat to {@code targetStable} — shared by the P3 COMMIT
     * ({@code kind} = {@link #MOUNT_KIND_COMMIT}, target = the flight's target) and the
     * DOCK-BACK ({@link #MOUNT_KIND_DOCK_BACK}, target = the flight's source under F). Engine
     * verb first (module targets through the module verb; a deny returns with
     * nothing mutated — the flight stays alive for the caller's abort end / strand); then, in
     * order: the stall tag; F read BEFORE the despawn; {@code despawnFlight} AFTER the rekey
     * (the pod is the rekey SOURCE); registry
     * {@code releaseForRebind(podStable)} (frees S) → {@code oldTargetId =
     * releaseForRebind(targetStable)} → {@code internReservedMount(targetStable, S)}; drone
     * orbit arm; declaration/staged hygiene; the park end (per {@code kind}); fire-lane
     * baselines/shim/autofire on the re-pointed body; the module-TARGET noteMounted + MMNT
     * MOUNT (MMNT≺RAPP); the seat's RAPP repoint + latches; census mark. The caller owns the
     * takeover echo, loser fans, the XFER frame and its log line.
     */
    private HullMount seatOntoHull(int seatId, long seatWireId, long targetWireId,
            String targetStable, MatchDescriptors.ModuleSeat moduleSeatOrNull, long tick,
            String kind) throws IOException {
        SeatRebind.TransferRekeyResult rekey = moduleSeatOrNull != null
                ? transferRekey.transferSeatToStationModule(
                        seatId, seatWireId, targetWireId, targetStable)
                : transferRekey.transferSeatToHull(
                        seatId, seatWireId, targetWireId, targetStable);
        if (rekey.denyReason() != null) {
            return new HullMount(rekey, 0L, 0L, 0, 0, 0);
        }
        // Stall-tag law: the commit machinery is the other convicted stall class
        // (first commit 208–227 ms) — noted before the machinery runs so the tag
        // stands even if a downstream verb throws serve-fatal. One tag for both kinds.
        if (stallTags != null) {
            stallTags.note(tick, "transfer_commit");
        }
        // F BEFORE the despawn (the flight carries it); the despawn AFTER the rekey.
        long sourceWireId = podLane.sourceWireIdOrZero(seatId);
        podLane.despawnFlight(seatId, tick);
        // Registry re-key: the pod's stable releases S; the seat's reserved wire id
        // re-points onto the target (the mount surface frees the target's old id — for a
        // parked takeover that id was the parked seat's own wire id; for a dock-back it is F).
        stableIdRegistry.releaseForRebind(rekey.sourceStableId());
        long oldTargetId = stableIdRegistry.releaseForRebind(rekey.targetStableId());
        stableIdRegistry.internReservedMount(rekey.targetStableId(), seatWireId);
        // W3 drone-orbit arm: evaluated on the freshly re-keyed body — a
        // non-drone target clears any prior arm; per-tick validation keeps a stale arm
        // harmless either way (a fresh respawn body is never a drone).
        binding.armOrbitDroneSeatIfEligible(seatWireId);
        // The ModuleSeatServeLane.tryMountClaim hygiene family verbatim: the pod's declarations/staged
        // intent must not carry, and the fire lane's baselines/mask/autofire state must
        // re-arm on the seat's re-pointed body.
        // Keyed by the seat's FIXED wire id
        // (applyStateByShipId), never the seat id.
        int flushedDecls = pilotStep.flushDeclarationsOnHullLoss(seatWireId);
        pilotStep.clearStagedIntentOnHullLoss(seatWireId);
        if (MOUNT_KIND_DOCK_BACK.equals(kind)) {
            // DOCK-BACK park end: S is the source again — unpark + the tolerant
            // detach/personality restore BY S (hulk/module parks skip inside).
            endTransitParkOnAbort(seatId, seatWireId, tick);
        } else {
            // L4 S1 COMMIT park end: unpark beside the staged clear; the retired
            // source hull keeps its cautious AI (AI-flown IS the vanilla end state — NO
            // detach, NO personality restore; captain/personality fate = L5) but the lane
            // PIN must release under F or the AI stays lane-owned forever. A pod-body flight
            // has no source (F = 0) — nothing pinned, nothing to release.
            transitParks.remove(seatId);
            pilotStep.unparkTransit(seatWireId);
            boolean pinReleased = sourceWireId != 0L && binding.releaseGracePin(sourceWireId);
            System.out.println("npeer-serve transit_park_commit seat=" + seatId
                    + " fresh_source_id=" + sourceWireId + " pin_released=" + pinReleased
                    + " round=" + round + " tick=" + tick);
        }
        int gpeeReset = fireLaneDischargeDetector.resetShipBaselines(seatWireId);
        binding.installFastTimeReinjectShim(seatWireId);
        int autofireOff = binding.disarmBuildTimeAutofire(seatWireId);
        if (moduleSeatOrNull != null) {
            // module-commit inserts: arbiter truth FIRST — the seat now RIDES a
            // fortress module (winnerAbort's isMounted then denies /transfer FROM it;
            // /module dismount + replayMountsToSession work unchanged off the same note) —
            // then the MMNT kind-1 fan BEFORE the RAPP re-point row below (the MMNT≺RAPP
            // law: the client's mounted-table guard must be warm when the seat's RAPP row
            // lands; single-writer CTRL FIFO). otherWireId = the module's RETIRING old id,
            // slot id ENGINE-verbatim — the MSSL mount-fan fields exactly.
            rebindArbiter.noteMounted(seatId, moduleSeatOrNull.token());
            door.fanModuleMount(ModuleMountFrameCodec.encode(
                    new ModuleMountFrameCodec.MountHandover(seatWireId, oldTargetId,
                            ModuleMountFrameCodec.KIND_MOUNT, moduleSeatOrNull.slotId())),
                    ModuleMountFrameCodec.KIND_MOUNT);
        }
        if (SeatAnnounceTracker.announceSeatVariantIfRepointed(door, seatId,
                new PersistentPilotIntentCombatServer.NPeerHooks.FlyInResult(seatWireId,
                        rekey.targetStableId(), rekey.variantId(),
                        rekey.descriptorVariantId(), rekey.side(), rekey.shipName(),
                        rekey.crPerMille()),
                lastAnnouncedVariantBySeat, roundRosterAppendFrames)) {
            pilotStep.fireClassLane().latchMaskStalenessGate(seatWireId, tick, seatId);
            pilotStep.fireClassLane().rebindFireClassResolverAtRepoint(seatWireId, tick,
                    seatId);
            afTargetLane.resetShip(seatWireId);
        }
        censusDirty = true;
        return new HullMount(rekey, sourceWireId, oldTargetId, flushedDecls, gpeeReset,
                autofireOff);
    }

    /**
     * DOCK-BACK: a RETURNING flight's landing
     * finished on its source (under F) — the TARGET-half with target = that source: hull or
     * module (the seat left a module at BEGIN ⇒ it re-mounts it: noteMounted + MMNT MOUNT
     * inside {@link #seatOntoHull}). A deny (the source died/went unlisted during the
     * landing) STRANDS the seat on the pod where it sits ({@code strandFlight} +
     * {@link #onStranded}); ok ⇒ COMMIT fan (from = 0, to = F, pod = 0 — the client leg's
     * COMMIT-after-ABORT = the dock-back) + chat.
     */
    private void dockBack(int seatId, long sourceWireId, long tick,
            Map<Long, String> stableByWireId) throws IOException {
        long seatWireId = wireIdFor(seatId);
        String sourceStable = sourceWireId == 0L ? null : stableByWireId.get(sourceWireId);
        if (sourceStable == null || transferRekey == null) {
            System.out.println("npeer-serve transfer_dock_back_denied seat=" + seatId
                    + " source_wire=" + sourceWireId + " reason=source_unlisted"
                    + " round=" + round + " tick=" + tick);
            podLane.strandFlight(seatId, tick);
            onStranded(seatId, sourceWireId, tick, true);
            return;
        }
        MatchDescriptors.ModuleSeat moduleSeat = moduleCensusByStable().get(sourceStable);
        HullMount mount = seatOntoHull(seatId, seatWireId, sourceWireId, sourceStable,
                moduleSeat, tick, MOUNT_KIND_DOCK_BACK);
        if (mount.rekey().denyReason() != null) {
            System.out.println("npeer-serve transfer_dock_back_denied seat=" + seatId
                    + " source_wire=" + sourceWireId
                    + " reason=\"" + mount.rekey().denyReason() + "\""
                    + " round=" + round + " tick=" + tick);
            podLane.strandFlight(seatId, tick);
            onStranded(seatId, sourceWireId, tick, true);
            return;
        }
        String home = mount.rekey().shipName() == null || mount.rekey().shipName().isEmpty()
                ? mount.rekey().variantId() : mount.rekey().shipName();
        door.fanServerChat(seatId, "returned to " + home);
        // WIRE LAW: from = S, never 0 (the codec rejects from < 1 - the earlier 0L here
        // would have thrown IllegalArgumentException on the sim thread at the first dock-back);
        // to = F = the RETIRING id the seat re-keys onto (the client contract).
        fanTransferFrame(CommandTransferFrameCodec.KIND_COMMIT, seatId, seatWireId, sourceWireId,
                0L, CommandTransferFrameCodec.REASON_NONE);
        lastFannedTargetBySeat.remove(seatId);
        System.out.println("npeer-serve transfer_dock_back seat=" + seatId
                + " wire_id=" + seatWireId + " source_wire=" + sourceWireId
                + " source_stable=" + sourceStable
                + " pod_stable=" + mount.rekey().sourceStableId()
                + " old_target_id=" + mount.oldTargetId()
                + " module_slot=" + (moduleSeat == null ? "none" : moduleSeat.slotId())
                + " flushed_decls=" + mount.flushedDecls()
                + " gpee_reset=" + mount.gpeeReset()
                + " autofire_off=" + mount.autofireOff()
                + " round=" + round + " tick=" + tick);
    }
    /** Winner-side authoritative re-check; null = still eligible to commit.
     *  {@code seatStable} = the seat wire id's registry stable (the dead hull's until a fly-in
     *  re-points it; null = no row). */
    private String winnerAbortReasonOrNull(int seatId, Set<Integer> connectedSeats,
            String seatStable) {
        if (!connectedSeats.contains(seatId)) {
            return "transfer aborted - pilot disconnected";
        }
        // F-B: the old mounted deny is LIFTED — a module-riding
        // seat's flight is legal, begin-then-respawn-into-a-claimed-module included (the
        // commit's module-source branch drains the arbiter mount + fans KIND_DISMOUNT).
        // authority.isMounted stays the floor: a module rider reads TRUE there
        // (recordMountEstablished at mount), so "you have no ship" never mis-fires on it.
        // An UNMOUNTED seat whose wreck still lingers
        // keeps its flight — vanilla parity (a dead pilot transfers out of an intact hulk;
        // the pod launches from the wreck, the commit re-mounts the seat on the target, the
        // respawn fly-in yields meanwhile). Only a seat with NO body left has "no ship".
        // A seat with a LIVE FLIGHT is never "you have no ship" — a
        // wreck REMOVED mid-flight (fractured/exploded away) keeps its flight committing
        // to a live destination (vanilla parity: `from` is never read after
        // spawn). Pre-mint rows (no flight yet) still abort here — the (ii) law.
        if (!authority.isMounted(seatId) && !binding.isSeatHulkInPlay(seatStable)
                && !podLane.hasFlight(seatId)) {
            return "transfer aborted - you have no ship";
        }
        if (authority.isDeadLatched(seatId)) {
            return "transfer aborted - seat is out this round";
        }
        if (graceLane != null && authority.seatGraceState(seatId)
                != MatchSeatAuthority.SeatGraceState.NONE) {
            return "transfer aborted - seat in reconnect grace";
        }
        return null;
    }

    /**
     * L3, L4 S1: encode one XFER frame — {@code pod} is real on BEGIN and
     * the abort classes now (COMMIT keeps 0, the pod despawns with it; the
     * schema law "decode admits ≥0" is UNCHANGED both ends). Every encoded value is
     * server-authored, so an encode throw is a law bug — serve-fatal LOUD
     * ({@link IllegalStateException}), which also keeps {@link #noteHullDespawned}'s
     * signature checked-exception-free.
     */
    static byte[] encodeTransferFrame(int kind, long seatWireId, long from, long to, long pod,
            int reason) {
        try {
            return CommandTransferFrameCodec.encode(new CommandTransferFrameCodec
                    .TransferControl(seatWireId, from, to, pod, kind, reason));
        } catch (IOException lawBug) {
            throw new IllegalStateException("XFER encode failed on server-authored values"
                    + " (kind=" + kind + " seat=" + seatWireId + ")", lawBug);
        }
    }

    private void fanTransferFrame(int kind, int seatId, long from, long to, long pod,
            int reason) {
        door.fanTransfer(encodeTransferFrame(kind, wireIdFor(seatId), from, to, pod, reason),
                kind);
    }

    private void fanAbort(int seatId, String reason, int reasonCode, long targetWireId,
            long tick) {
        // The one generic kind read: a flight that EXISTS aborts (ABORT), a
        // row that dies with no flight denies (DENY). Read BEFORE fanAbortEnd turns the pod —
        // the timeout site bypasses this (its pump already flipped hasPod false) with an
        // explicit KIND_ABORT.
        boolean hadPod = podLane.hasPod(seatId);
        fanAbortEnd(seatId, reason, reasonCode,
                hadPod ? CommandTransferFrameCodec.KIND_ABORT
                        : CommandTransferFrameCodec.KIND_DENY,
                targetWireId, tick);
    }

    /**
     * ORDER LAW: every caller consumes
     * the seat's arbiter row ({@code transferAbort} / the commit / the vacate / the toward-hull
     * sweep) BEFORE calling here, and this method never touches the arbiter. The order is
     * load-bearing for the reader-thread race, not for the fly-in: {@code transferBegin} is
     * synchronized on the arbiter and can land inside this method's wall-clock window. Row
     * already gone ⇒ a fresh begin MINTS a surviving row (honored next tick as a by-choice
     * re-fly: RETARGETED_RETURNING / RESUMED_POD_BODY). Row still live ⇒ the begin would PARK a
     * retarget that the late {@code transferAbort} then deletes with the row — a silently
     * swallowed begin behind a "transferring to X" ack. No serve-lane fixture pins this order
     * (the arbiter halves are pinned in {@code CommandTransferContractRows}); a reorder is a
     * behaviour change that rides the row-null-after-abort seam.
     */
    private void fanAbortEnd(int seatId, String reason, int reasonCode, int kind,
            long targetWireId, long tick) {
        long seatWireId = wireIdFor(seatId);
        // L4 S1: the pod id on the abort frame IS S for any
        // live flight (0 = no flight); F = the source's fresh id (0 = no flight / pod-body
        // flight). Both read BEFORE abortToReturning turns/consumes the flight.
        long podWireId = podLane.podWireIdOrZero(seatId);
        long sourceWireId = podLane.sourceWireIdOrZero(seatId);
        // allowReturnHome: nobody flies home when the pilot is GONE — PILOT_LEFT, or
        // the winner re-check's "pilot disconnected" / "seat in reconnect grace" lines, which
        // ride the shared REASON_WINNER_INELIGIBLE code and are told apart by their (server-
        // authored, constant) text. Everything else turns the pod home onto a live source.
        boolean pilotGone = reasonCode == CommandTransferFrameCodec.REASON_PILOT_LEFT
                || reason.contains("disconnected") || reason.contains("reconnect grace");
        // Every fanned abort means the seat's row is consumed. The ONE abort choke point
        // also ends the park, by outcome: STRANDED ⇒ the seat keeps flying the pod
        // under S (unpark + pin release by F, NEVER a detach by S); RETURNING ⇒ the park
        // STAYS and ends at the dock-back (or the strand that replaces it); NONE ⇒ no
        // flight exists, so nothing was ever re-keyed away from S (the pod_stable_null /
        // mount-deny paths despawned before fanning; DENY classes were never parked) — end
        // the park by S, a no-op on an empty map.
        TransferPodLane.AbortEnd end = podLane.abortToReturning(seatId, tick, !pilotGone);
        if (end == TransferPodLane.AbortEnd.STRANDED) {
            onStranded(seatId, sourceWireId, tick, false); // this method fans the end below
        } else if (end == TransferPodLane.AbortEnd.NONE) {
            endTransitParkOnAbort(seatId, seatWireId, tick);
        }
        // The episode is over — the next flight's BEGIN re-arms the re-fan map.
        lastFannedTargetBySeat.remove(seatId);
        // Any abort may follow census-shaping pre-steps (a PARKED takeover's mount-loss lands
        // before a verb deny) — over-marking is free, a missed mark waits out the belt.
        censusDirty = true;
        door.fanServerChat(seatId, reason);
        System.out.println("npeer-serve transfer_abort seat=" + seatId
                + " reason=\"" + reason + "\" end=" + end + " pilot_gone=" + pilotGone
                + " round=" + round + " tick=" + tick);
        // The abort END rides fromWireId - RETURNING fans from = F (the
        // home the pod flies back to; the dock-back COMMIT follows), every other end fans
        // from = S (STRANDED: the seat flies the pod, nothing follows). The client reads
        // from != seat as "a dock-back is coming, keep the transit"; no new wire field.
        long fromWireId = end == TransferPodLane.AbortEnd.RETURNING && sourceWireId > 0L
                ? sourceWireId : seatWireId;
        fanTransferFrame(kind, seatId, fromWireId, targetWireId, podWireId, reasonCode);
    }

    /**
     * STRAND end: the flight ended in
     * place and the seat is FLYING THE POD under S — the pod lane already flipped the body
     * pilotable. Unpark the seat's input, release the source's caretaker pin by F (a
     * pod-body flight has none), NEVER detach by S (S is the pod), chat + LOUD. Safe on a
     * GONE seat (rider (vii)): the chat is a no-op, the map removes are idempotent, and the
     * pod stays the seat's hull under S for the ordinary seat-vacate/grace path to reap.
     */
    /** Re-fan source: the BEGIN re-fans' {@code from} — the source under F, or
     *  S when the flight has no source (a pod-body flight: the codec rejects {@code from < 1},
     *  so the {@code sourceWireIdOrZero} here threw on the sim thread at the first
     *  retarget of a resumed pod). Same law as the COMMIT / dock-back sites. */
    private long fromIdOrSeat(int seatId, long seatWireId) {
        long sourceWireId = podLane.sourceWireIdOrZero(seatId);
        return sourceWireId > 0L ? sourceWireId : seatWireId;
    }

    private void onStranded(int seatId, long sourceWireId, long tick, boolean fanEnd) {
        long seatWireId = wireIdFor(seatId);
        TransitPark park = transitParks.remove(seatId);
        pilotStep.unparkTransit(seatWireId);
        boolean pinReleased = sourceWireId != 0L && binding.releaseGracePin(sourceWireId);
        Long lastTarget = lastFannedTargetBySeat.remove(seatId);
        censusDirty = true;
        door.fanServerChat(seatId, "shuttle stranded - you are flying the pod");
        if (fanEnd) {
            // Before the fix, a PUMP-detected strand (return_lost, a raced /
            // orphan row, a denied dock-back) fanned CHAT ONLY — the owner client held a
            // RETURNING transit forever (its timeout belt is gone) and every remote
            // replica stayed CollisionClass.NONE. Fan the STRANDED end per its from-law
            // (from = S; to = the last announced target or 0 — ABORT admits 0; pod = S).
            // fanAbortEnd fans its own frame and passes false. NO pod-body
            // RESUME follows in-tick any more (the pump's STRANDED arm consumes the row) —
            // a later /transfer re-flies the pod body by the seat's choice.
            fanTransferFrame(CommandTransferFrameCodec.KIND_ABORT, seatId, seatWireId,
                    lastTarget != null ? lastTarget.longValue() : 0L, seatWireId,
                    CommandTransferFrameCodec.REASON_OTHER);
        }
        System.out.println("npeer-serve pod_stranded_seat seat=" + seatId
                + " wire_id=" + seatWireId + " source_wire=" + sourceWireId
                + " parked=" + (park != null) + " pin_released=" + pinReleased
                + " round=" + round + " tick=" + tick);
    }

    /**
     * L4 S1 park engage (the two-layer order verbatim: staged clear → attach →
     * park). IDEMPOTENT: a pod-loss re-mint finds the seat already parked and keeps the
     * ORIGINAL saved personality (a second attach would re-save "cautious" — restore
     * corruption); the re-mint's second BEGIN fan is the existing L3 semantic. An attach
     * THROW is serve-fatal by the mount-family law (a half-parked hull must not serve).
     */
    private void engageTransitPark(int seatId, long seatWireId, long tick) {
        if (transitParks.containsKey(seatId)) {
            return;
        }
        boolean stagedDropped = pilotStep.clearStagedIntentOnGraceVacate(seatWireId);
        if (rebindArbiter.isMounted(seatId)) {
            // F-B module-SOURCE park: NO cautious-AI attach (mismatched SHIP-AI
            // class for a module body — it sits AI-less during transit, its ridden state);
            // still staged-clear + park. The commit's releaseGracePin then honestly reads
            // false (logged there, benign); the abort end skips the detach off the marker.
            transitParks.put(seatId, new TransitPark(null, true, false));
            pilotStep.parkTransit(seatWireId);
            System.out.println("npeer-serve transit_park seat=" + seatId
                    + " wire_id=" + seatWireId + " module_source=true attach=skipped"
                    + " staged_dropped=" + stagedDropped
                    + " round=" + round + " tick=" + tick);
            return;
        }
        if (!authority.isMounted(seatId)) {
            // A WRECK source — the drain admitted
            // this unmounted row via isSeatHulkInPlay (winnerAbortReasonOrNull), and the seat's
            // live-map row was vacated at seat_mount_loss, so graceAttachTransitAi's live-hull
            // resolve can only THROW (the serve-FATAL). A wreck needs no caretaker AI
            // (nothing flies it; vanilla leaves a hulk AI-less): staged-clear + park only. The
            // abort end skips the detach off the marker; the commit's releaseGracePin reads
            // false honestly (same as the module-source path above).
            transitParks.put(seatId, new TransitPark(null, false, true));
            pilotStep.parkTransit(seatWireId);
            System.out.println("npeer-serve transit_park seat=" + seatId
                    + " wire_id=" + seatWireId + " hulk_source=true attach=skipped"
                    + " staged_dropped=" + stagedDropped
                    + " round=" + round + " tick=" + tick);
            return;
        }
        // drone-aware — a station drone source keeps its
        // launcher ring through transit (vanilla drone AI); every other hull takes the
        // cautious attach as before. The transit_park row's ai_class= names the path.
        EngineCombatBinding.GraceAttachResult attach = binding.graceAttachTransitAi(seatWireId);
        transitParks.put(seatId, new TransitPark(attach.savedPersonalityId(), false, false));
        pilotStep.parkTransit(seatWireId);
        System.out.println("npeer-serve transit_park seat=" + seatId
                + " wire_id=" + seatWireId
                + " saved_personality=" + attach.savedPersonalityId()
                + " ai_class=" + attach.aiClassName()
                + " staged_dropped=" + stagedDropped
                + " round=" + round + " tick=" + tick);
    }

    /**
     * L4 S1 park end with RESTORE: unpark + the tolerant detach BY
     * {@code detachWireId} ({@code graceDetachAiIfLive} — a source hull destroyed mid-flight
     * is a LEGAL end state, null result, logged; the dead pin prunes at the next attach),
     * BELTED against grace double-own: transferLane pumps BEFORE graceLane each tick, so the
     * abort-then-attach ordering is structurally safe, but if a live grace episode DOES own
     * the pin, the park must not strip it (skip + log, never corrupt the restore).
     * The detach id is the caller's — S on the BEGIN-deny path (nothing
     * re-keyed, S is still the source) and on the DOCK-BACK (S is the source AGAIN); a
     * flight in progress never comes here (its source lives under F; STRAND releases the
     * pin by F without a detach, see {@link #onStranded}).
     */
    private void endTransitParkOnAbort(int seatId, long detachWireId, long tick) {
        TransitPark park = transitParks.remove(seatId);
        if (park == null) {
            return;
        }
        long seatWireId = wireIdFor(seatId);
        pilotStep.unparkTransit(seatWireId);
        if (park.hulkSource()) {
            // Nothing was attached at engage (wreck or pod-body source) — unpark
            // only, LOUD.
            System.out.println("npeer-serve transit_park_end seat=" + seatId
                    + " wire_id=" + seatWireId + " detach=skipped_hulk_source"
                    + " round=" + round + " tick=" + tick);
            return;
        }
        if (park.moduleSource()) {
            // F-B: nothing was attached at engage — unpark only, LOUD.
            System.out.println("npeer-serve transit_park_end seat=" + seatId
                    + " wire_id=" + seatWireId + " detach=skipped_module_source"
                    + " round=" + round + " tick=" + tick);
            return;
        }
        if (graceLane != null && graceLane.ownsEpisode(seatId)) {
            System.out.println("npeer-serve transit_park_end seat=" + seatId
                    + " wire_id=" + seatWireId + " detach=skipped_grace_owns"
                    + " round=" + round + " tick=" + tick);
            return;
        }
        EngineCombatBinding.GraceDetachResult detach =
                binding.graceDetachAiIfLive(detachWireId, park.savedPersonalityId());
        System.out.println("npeer-serve transit_park_end seat=" + seatId
                + " wire_id=" + seatWireId + " detach_wire=" + detachWireId
                + (detach == null ? " detach=hull_gone"
                        : " restored_personality=" + park.savedPersonalityId()
                                + " autofire_off=" + detach.autofireOff()
                                + " shim=" + detach.shimReinstalled())
                + " round=" + round + " tick=" + tick);
    }

    /** Race losers drained by a commit get their ready-made reason lines on EVERY post-commit
     * path (their flights are consumed even when the winner's own row then denied — a silent
     * consume would conceal the loss). PRIORITY commits carry none by construction. */
    private void fanLosers(SeatRebindArbiter.TransferCommit commit, long tick) {
        for (SeatRebindArbiter.TransferAbort loser : commit.losers()) {
            // to=0: TransferAbort rows carry no target — the wire's not-carried loser class.
            fanAbort(loser.seatId(), loser.reasonLine(),
                    CommandTransferFrameCodec.REASON_RACE_LOST, 0L, tick);
        }
    }

    /**
     * L3 late-join replay: one BEGIN per in-flight arbiter row, sent to the
     * ONE newly-live session — the arbiter state IS the replay source (no append-log; a row
     * that aborts before a joiner arrives is simply never replayed). Called by the serve loop
     * at session_live AFTER the RAPP catch-up on the UNPACED admission (a paced one takes
     * {@link #inFlightBeginFrames} into its cursor) — the deliberate MMNT-ordering inversion: a
     * replayed BEGIN references roster ids RAPP just taught.
     * The catch-up is best-effort BY DESIGN: a per-row throw (the codec's serve-fatal law-bug
     * ISE included) is counted, not let onto the serve tick, and surfaces ONLY on the
     * {@code transfer_catchup ... failed_total=} line (+ the first-only
     * {@code transfer_catchup_fail} line) — a deliberate departure from the class's LOUD law.
     *
     */
    void replayTransfersToSession(PilotIntentDatagramServer.PeerSession session) {
        replayInFlightTo(session, frame -> door.sendCtrlToSession(session, frame));
    }

    /**
     * The PACED admission's XFER segment: the same in-flight BEGIN frames
     * {@link #replayTransfersToSession} would send, collected for the admission cursor, which asks
     * once at the joiner's RAPP completion and sends them all in that step (the BEGIN still follows
     * every roster id RAPP taught). Same {@code transfer_catchup} line ({@code frames=} = the frames
     * handed over) and the same first-only fail line; no flight in the air = an empty list and no
     * line. Serve thread.
     */
    List<byte[]> inFlightBeginFrames(PilotIntentDatagramServer.PeerSession session) {
        ArrayList<byte[]> out = new ArrayList<>();
        return replayInFlightTo(session, out::add) ? out : List.of();
    }

    /** The replay body both callers share: false = no flight in the air (nothing sent, no
     *  line). */
    private boolean replayInFlightTo(PilotIntentDatagramServer.PeerSession session,
            Consumer<byte[]> sink) {
        LinkedHashMap<Integer, Long> flights = rebindArbiter.inFlightTransfers();
        if (flights.isEmpty()) {
            return false;
        }
        // The loop rides the door-free seam below so
        // TransferReplayFromIdContractRows R5 drives the SAME call-site wiring.
        int[] counts = replayFlightsTo(flights, seatWireIdBySeatId, podLane, sink,
                (seatId, lawBug) -> {
                    replayEncodeFailed++;
                    if (!replayEncodeFailLogged) {
                        replayEncodeFailLogged = true;
                        System.out.println("npeer-serve transfer_catchup_fail seat=" + seatId
                                + " err=" + lawBug + " (first only; total on transfer_catchup)"
                                + " round=" + round);
                    }
                });
        System.out.println("npeer-serve transfer_catchup session=" + session.sessionId()
                + " frames=" + counts[0] + " skipped=" + counts[1] + " failed=" + counts[2]
                + " failed_total=" + replayEncodeFailed + " round=" + round);
        return true;
    }

    /**
     * The late-join replay loop, door-free — one BEGIN per in-flight row into
     * {@code sink}; a per-row throw goes to {@code onFail} (the belt) and counts as failed.
     * Returns {sent, skipped, failed}. Public ONLY for contract reach
     * ({@code TransferReplayFromIdContractRows} R5); not a serve API.
     *
     */
    public static int[] replayFlightsTo(Map<Integer, Long> flights,
            Map<Integer, Long> seatWireIdBySeatId, TransferPodLane podLane,
            Consumer<byte[]> sink, BiConsumer<Integer, RuntimeException> onFail) {
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (Map.Entry<Integer, Long> row : flights.entrySet()) {
            int seatId = row.getKey();
            // L4 S1: the replayed BEGIN carries the live
            // flight's ids — from = the source under F, or S when the flight has none (a
            // pod-body flight: the fromIdOrSeat law), pod = S (0 pre-mint); a pre-mint row
            // (target 0) is SKIPPED — the live fan teaches it when it mints. It references
            // roster ids the RAPP catch-up just taught (the deliberate ordering inversion
            // below). The replay is best-effort catch-up — an encode
            // throw is counted + logged once, never let out onto the serve tick.
            try {
                byte[] frame = replayBeginFrameOrNull(wireIdIn(seatWireIdBySeatId, seatId),
                        podLane.sourceWireIdOrZero(seatId), row.getValue().longValue(),
                        podLane.podWireIdOrZero(seatId));
                if (frame == null) {
                    skipped++;
                    continue;
                }
                sink.accept(frame);
                sent++;
            } catch (RuntimeException lawBug) {
                failed++;
                onFail.accept(seatId, lawBug); //
            }
        }
        return new int[] {sent, skipped, failed};
    }

    /**
     * The late-join replay's BEGIN for one arbiter row, pure and
     * side-effect-free. Null when {@code targetOrZero < 1} (a pre-mint row has no BEGIN to
     * replay yet); else {@code from} = the source, or the seat when the flight has none (the
     * {@link #fromIdOrSeat} law — the codec rejects {@code from < 1}, so the raw
     * {@code sourceWireIdOrZero} threw on the serve tick for a resumed-pod flight). Public ONLY
     * for contract reach ({@code TransferReplayFromIdContractRows}); not a serve API.
     *
     */
    public static byte[] replayBeginFrameOrNull(long seatWireId, long sourceOrZero,
            long targetOrZero, long podOrZero) {
        if (targetOrZero < 1L) {
            return null;
        }
        return encodeTransferFrame(CommandTransferFrameCodec.KIND_BEGIN, seatWireId,
                sourceOrZero > 0L ? sourceOrZero : seatWireId, targetOrZero, podOrZero,
                CommandTransferFrameCodec.REASON_NONE);
    }

    /** The seat whose FIXED wire id is {@code wireId}, or null (an AI/above-band hull). */
    private Integer seatIdForWireIdOrNull(long wireId) {
        return seatIdIn(seatWireIdBySeatId, wireId);
    }

    /**
     * The {@link #seatIdForWireIdOrNull} law over an explicit map (the static census table's reach). */
    private static Integer seatIdIn(Map<Integer, Long> seatWireIdBySeatId, long wireId) {
        for (Map.Entry<Integer, Long> seat : seatWireIdBySeatId.entrySet()) {
            if (seat.getValue().longValue() == wireId) {
                return seat.getKey();
            }
        }
        return null;
    }

    private long wireIdFor(int seatId) {
        return wireIdIn(seatWireIdBySeatId, seatId); //
    }

    /** The {@link #wireIdFor} law over an explicit map (the static replay seam's reach).
     * */
    private static long wireIdIn(Map<Integer, Long> seatWireIdBySeatId, int seatId) {
        Long wireId = seatWireIdBySeatId.get(seatId);
        if (wireId == null) {
            throw new IllegalStateException("transfer lane: seat " + seatId + " has no fixed"
                    + " wire id (the descriptor seat map is total by construction)");
        }
        return wireId.longValue();
    }
}
