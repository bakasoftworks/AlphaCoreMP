package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.PilotApplyState;
import java.util.Map;
import java.util.Objects;

/**
 * The class-routing arm/repoint lane: the
 * routing/repoint cluster of {@code PersistentPilotIntentCombatServer.PilotIntentStep} —
 * round-boot arm, per-creation enrollment, the repoint-edge gate/rebind twins, the fail-open
 * disable, the belt-1 pose-owned demote sink, and the class-meter roll-ups — extracted own-file
 * as the owed 5000-nb whole-class-trip bite.
 * Pure move: every body and log line is verbatim from
 * the step; the lane shares the step's LIVE per-ship apply-state map (get/iterate only — state
 * creation stays in {@code PilotIntentStep.applyStateFor}, which calls {@link #enrollAtCreation}).
 *
 * <p>The pre-identified split names this router half ONE seam with the state half living in
 * {@code PilotApplyState} (a carve still queued):
 * when that carve is commissioned, this lane is its ready serve-side twin — the routing LAW stays
 * in {@link FireClassLaw} + the apply state; this class is the decided per-round ORDERING.
 */
public final class FireClassRoutingLane {

    /**
     * The one armed predicate every resolver build rides. Routing is
     * armed only when the round flag is on AND the overrides map exists — {@code classRoutingOn}
     * flips false on the session fallback (a fallback round must not rebuild a resolver), and
     * a null map means {@link #enableClassRouting} never ran this round, where
     * {@code fireClassResolverForShip}'s requireNonNull would NPE on the sim thread. Class is
     * public only for this contract reach; the ctor is public for the R5 row, every lane method
     * stays package-private.
     *
     */
    public static boolean routingArmed(boolean classRoutingOn, Map<?, ?> overrides) {
        return classRoutingOn && overrides != null;
    }

    private final EngineCombatBinding binding;
    /** The step's LIVE map — shared by reference; this lane never adds or removes entries. */
    private final Map<Long, PilotApplyState> applyStateByShipId;

    // Class routing: armed once per round right after
    // construction; every apply state created from then on enrolls with its own per-ship
    // BASE-spec resolver. classRoutingOn flips false on the session fallback so future
    // creations (drop-in seats) ride held with everyone else.
    private boolean classRoutingOn;
    private double fireClassRateMax;
    private Map<String, FireClassLaw.FireClass> fireClassOverrides;
    private int declRecencyWindowTicks;

    /**
     * Builds the lane over the step's live apply-state map; public for contract reach (the R5
     * unrouted-repoint wiring row).
     */
    public FireClassRoutingLane(EngineCombatBinding binding,
            Map<Long, PilotApplyState> applyStateByShipId) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.applyStateByShipId = Objects.requireNonNull(applyStateByShipId, "applyStateByShipId");
    }

    /**
     * Arm per-tick class routing for this round. Called once
     * right after construction (no apply states exist yet) — enrollment happens at apply-state
     * creation ({@code PilotIntentStep.applyStateFor} → {@link #enrollAtCreation}), so drop-in
     * seats route too. Exclusive with {@code setDeclaredFireLane}(true) by the serve loop's
     * boot guard.
     */
    void enableClassRouting(double rateMax, Map<String, FireClassLaw.FireClass> overrides,
            int declRecencyWindowTicks) {
        this.fireClassRateMax = rateMax;
        this.fireClassOverrides = Objects.requireNonNull(overrides, "overrides");
        this.declRecencyWindowTicks = declRecencyWindowTicks;
        this.classRoutingOn = true; // AFTER requireNonNull
    }

    /**
     * Enrollment at apply-state creation: a routed round hands each NEW state its own
     * per-ship BASE-spec resolver + the decl-recency window; a non-routed round leaves the
     * state untouched. Called only from {@code PilotIntentStep.applyStateFor}'s computeIfAbsent.
     */
    void enrollAtCreation(long shipId, PilotApplyState state) {
        // Audit: classRoutingOn is set true ONLY by enableClassRouting,
        // which requireNonNull's the overrides BEFORE it sets the flag (a null argument throws
        // with the flag still false) — a null cannot reach the resolver here.
        //
        // Rides the shared predicate anyway so the two build sites cannot drift.
        if (routingArmed(classRoutingOn, fireClassOverrides)) {
            state.enableClassRouting(binding.fireClassResolverForShip(
                    shipId, fireClassRateMax, fireClassOverrides));
            state.declRecencyWindowTicks(declRecencyWindowTicks);
        }
    }

    /**
     * Session fallback: revert EVERY ship — existing states loudly, future creations by
     * the cleared flag — from class routing to the held lane for the rest of the serve.
     */
    void disableClassRoutingAll(String reason) {
        classRoutingOn = false;
        for (PilotApplyState state : applyStateByShipId.values()) {
            state.disableClassRouting(reason);
        }
    }

    /**
     * The belt-1 sink: a pose-lane-OWNED birth confessed on this ship's mount demotes the
     * mount (and its group) to held when the router currently owns it. NEVER creates a state
     * — foe hulls and never-declared ships have none and need no demote.
     */
    void demotePoseOwnedMount(long shipId, int mountIndex) {
        PilotApplyState state = applyStateByShipId.get(shipId);
        if (state != null && state.mountRoutedClassS(mountIndex)) {
            state.demoteMountToHeld(mountIndex, "pose-owned");
        }
    }

    /**
     * The seat REFIELDED onto a DIFFERENT variant ({@code seat_variant_repoint}
     * announced): arm its mask-staleness gate so the client's stale pre-death shape trio cannot
     * re-arm the fresh hull (arm/release law = {@code PilotApplyState.armMaskStalenessGate} +
     * {@code EngineCombatBinding.maskStalenessGateHolds}). {@code get} not {@code computeIfAbsent}
     * (the idiom): no state means the seat never applied a frame, so no stale sample exists
     * to hold — fail-closed no-arm, logged loud either way.
     */
    void latchMaskStalenessGate(long shipId, long refieldTick, int seatId) {
        PilotApplyState state = applyStateByShipId.get(shipId);
        if (state == null) {
            System.out.println("npeer-serve mask_gate no-arm reason=no_state seat=" + seatId
                    + " wire_id=" + shipId + " tick=" + refieldTick);
            return;
        }
        if (!state.armMaskStalenessGate(refieldTick)) {
            System.out.println("npeer-serve mask_gate no-arm reason=no_mask seat=" + seatId
                    + " wire_id=" + shipId + " tick=" + refieldTick);
            return;
        }
        System.out.println("npeer-serve mask_gate armed seat=" + seatId
                + " wire_id=" + shipId + " tick=" + refieldTick
                + " stale_mask=0x" + Integer.toHexString(state.maskGateStaleMask()));
    }

    /**
     * The {@link #latchMaskStalenessGate} twin, server
     * side: across a cross-variant {@code seat_variant_repoint} the resolver caches
     * and the demote trio keep judging the DEAD hull's roster under the reused wire id (a
     * stale class-S verdict on a rapid-fire mount feeds the decl→group-FIRE loop).
     * Rebind a FRESH resolver + reset the trio jointly; the appended UNCACHED snapshot is
     * the (91) probe splitting stale-cache from client↔server mount-ordinal skew. Missing
     * or unrouted states no-op LOUDLY — a rebind never re-enables routing past a
     * fail-open revert.
     */
    void rebindFireClassResolverAtRepoint(long shipId, long refieldTick, int seatId) {
        PilotApplyState state = applyStateByShipId.get(shipId);
        if (state == null) {
            System.out.println("pilot-fire-class repoint_rebind no-op reason=no_state seat="
                    + seatId + " wire_id=" + shipId + " tick=" + refieldTick);
            return;
        }
        // Gate BEFORE building a resolver. overrides null =
        // enableClassRouting never ran this round (routing off) → fireClassResolverForShip's
        // requireNonNull NPE'd on the sim thread at an unguarded cross-variant refield;
        // classRoutingOn false = the session fallback latched, and a fallback round must not
        // rebuild a resolver either. overrides= tells the two causes apart on the no-op line
        // (false = never enabled; true = fallback latched); armed= alone is false for both.
        //
        if (!routingArmed(classRoutingOn, fireClassOverrides)) {
            System.out.println("pilot-fire-class repoint_rebind no-op reason=not_routed seat="
                    + seatId + " wire_id=" + shipId + " tick=" + refieldTick
                    + " armed=" + classRoutingOn
                    + " overrides=" + (fireClassOverrides != null)); //
            return;
        }
        // The snapshot below (fireClassSnapshotForShip) is reached only past this guard, so
        // it never sees a null map either (it does not requireNonNull, but stays behind it).
        String cleared = state.rebindClassRoutingAtRepoint(binding.fireClassResolverForShip(
                shipId, fireClassRateMax, fireClassOverrides));
        if (cleared == null) {
            System.out.println("pilot-fire-class repoint_rebind no-op reason=not_routed seat="
                    + seatId + " wire_id=" + shipId + " tick=" + refieldTick);
            return;
        }
        System.out.println("pilot-fire-class repoint_rebind seat=" + seatId
                + " wire_id=" + shipId + " tick=" + refieldTick + " " + cleared + " "
                + binding.fireClassSnapshotForShip(shipId, fireClassRateMax,
                        fireClassOverrides));
    }

    /** Class-routing meters, summed across the per-ship apply states. */
    int laneClassDrops() {
        int total = 0;
        for (PilotApplyState state : applyStateByShipId.values()) {
            total += state.laneClassDrops();
        }
        return total;
    }

    /** The group-law subset of {@link #laneClassDrops} (mixed/demoted groups). */
    int laneGroupDrops() {
        int total = 0;
        for (PilotApplyState state : applyStateByShipId.values()) {
            total += state.laneGroupDrops();
        }
        return total;
    }

    int classDemotes() {
        int total = 0;
        for (PilotApplyState state : applyStateByShipId.values()) {
            total += state.classDemotes();
        }
        return total;
    }

    int routingReverts() {
        int total = 0;
        for (PilotApplyState state : applyStateByShipId.values()) {
            total += state.routingReverts();
        }
        return total;
    }

    int classResolverRebinds() {
        int total = 0;
        for (PilotApplyState state : applyStateByShipId.values()) {
            total += state.classResolverRebinds();
        }
        return total;
    }

    /**
     * The declared-shot pad's four round-line meters summed across the per-ship
     * apply states, as one token run in the line's order ({@code armed bridged lapsed
     * refused_served}).
     */
    String declPadTokens() {
        int armed = 0;
        int bridged = 0;
        int lapsed = 0;
        int refusedServed = 0;
        for (PilotApplyState state : applyStateByShipId.values()) {
            armed += state.declPad().armed();
            bridged += state.declPad().bridged();
            lapsed += state.declPad().lapsed();
            refusedServed += state.declPad().refusedServed();
        }
        return " decl_pad_armed=" + armed + " decl_pad_bridged=" + bridged
                + " decl_pad_lapsed=" + lapsed + " decl_pad_refused_served=" + refusedServed;
    }
}
