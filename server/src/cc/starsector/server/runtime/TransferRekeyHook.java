package cc.starsector.server.runtime;

import cc.starsector.server.bridge.SeatRebind;

import java.io.IOException;

/**
 * W3 L1b-ii: the serve loop's command-transfer engine seam —
 * re-key a seat's FIXED wire id from its current hull onto a friendly target hull (source AI
 * restored, target AI yielded — the module mount/dismount idioms combined). The ONE real hook
 * ({@code PilotIntentCombatServerMain.buildTransferRekeyHook}) fronts
 * {@code SeatRebind.transferSeatToHull}; contract fakes bind none (a null hook keeps
 * the drain fail-soft: an in-flight row aborts LOUD "unavailable" — a player verb is never
 * serve-fatal, the seatSuicide law). A THROW is fatal (an engine re-key failure is never
 * per-session — the fly-in/mount law); normal-race ineligibility (target died/left/wrong
 * class) comes back as a typed DENY inside the result instead, decided atomically under the
 * roster lock with the re-key itself (no check-then-act window).
 *
 * <p>Top-level rather than a {@code NPeerHooks} nested interface DELIBERATELY: the
 * {@link ModuleMountHook} precedent — the serve loop file rides its deferred-refactors
 * whole-class trip, and hook interfaces are the growth that extracts cleanly.
 */
public interface TransferRekeyHook {

    /**
     * Re-key seat {@code seatId} (fixed wire id {@code seatWireId}) onto the target hull whose
     * deploy-recorded stable engine id is {@code targetStableId}. {@code targetHullWireId} is
     * the target's CURRENT wire id — for a PARKED-seat takeover it names the live-map entry the
     * re-key displaces (any OTHER live-map entry holding the target body is the F3 id-steal law
     * and throws). Returns either the re-keyed identity (deny == null) or a line-law-safe deny
     * reason; the caller owns the registry re-key, fire-lane resets, chat fans and RAPP
     * re-point (the {@link ModuleMountHook} caller-owns law).
     */
    SeatRebind.TransferRekeyResult transferSeatToHull(int seatId, long seatWireId,
            long targetHullWireId, String targetStableId) throws IOException;

    /**
     * W3 station-module transfer S1: the census walk — one row per live
     * UNFILTERED child module of the round's station base whose engine slot id is in
     * {@code slotIds} (the authored {@code MatchDescriptors.moduleSeatCensus} vocabulary).
     * EMPTY on a non-fortress serve (fail-soft by the verb's own law); the serve lane owns the
     * mounted-skip (registry truth: a MOUNTED module's stable rides its seat's wire id) and the
     * wire-id join. HOOK-routed deliberately — the census is transfer-lane growth, never
     * {@code EngineCombatBinding} growth (the WARN-tier carve consult, paid by
     * avoidance).
     */
    java.util.List<SeatRebind.StationModuleCensusRow> stationModuleCensus(
            java.util.Collection<String> slotIds) throws IOException;

    /**
     * W3 station-module transfer S2: the module twin of
     * {@link #transferSeatToHull} — re-key the seat onto an UNMOUNTED live station weapon
     * module resolved BY STABLE ID (parent implied). Typed DENIES for the normal races
     * (module gone / dead / claimed meanwhile — NO displacement path exists for modules);
     * the caller owns the registry re-key, arbiter mount note, MMNT fan, fire hygiene and
     * RAPP re-point (the caller-owns law, unchanged).
     */
    SeatRebind.TransferRekeyResult transferSeatToStationModule(int seatId, long seatWireId,
            long targetModuleWireId, String targetStableId) throws IOException;

    /**
     * One-entity pod: at BEGIN, re-key the seat's FIXED wire id
     * from its current hull (live-mapped source, or a lingering/gone wreck) onto the freshly
     * spawned pod — the seat RIDES the pod for the whole flight. No
     * source-AI op (the transit park's caretaker is already attached), no target-AI yield
     * (the pod keeps its {@code TransferCommandAI}), no retreat clear; the result's variant
     * is the client-resolvable {@code shuttlepod_cc_pod}, CR 1000‰, name = the seat's. The
     * caller owns the registry re-key (source → fresh id F, pod → S), fans and park.
     * <p>{@code podHandleOrNull} = the spawner's OWN ship handle (real-engine
     * FAIL): the spawn rides {@code CombatEngine.addObject}, which is pending-add until
     * the next advance, so a same-tick stable-id scan of {@code getShips()} MISSES the pod
     * (row {@code pod_mount_denied "command shuttle unavailable"}). The handle is authoritative
     * when its id matches {@code podStableId}; the engine-list scan stays the fallback.
     */
    SeatRebind.TransferRekeyResult mountSeatOnPod(int seatId, long seatWireId,
            String podStableId, Object podHandleOrNull) throws IOException;
}
