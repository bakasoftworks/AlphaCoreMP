package cc.starsector.server.runtime;

import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.EntityDespawnReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * per-round evidence ledger behind the same-tick DEPARTED→DESTROYED
 * correction of ship despawns (run in
 * {@code PersistentPilotIntentCombatServer}).
 *
 * <p>WHY: {@link EntityDespawnEventDetector} classifies from the LAST-SEEN hull against an
 * absolute threshold, so a ship whose killing blow lands and removes it inside ONE tick
 * reads DEPARTED whenever its previous sample was still above the threshold. The first
 * evidence was a corpse family born the same tick — a wreck proves a kill. A
 * dweller (hulk chance 0, {@code BaseDwellerShipCreator.initBasicShipProperties}) never
 * mints a corpse, so a station burst that took a shrouded tendril from 14.5 % to dead in
 * one tick published DEPARTED and the client evicted it with NO death FX (served run
 * {@code fidelity-shrouded/0904-062817}, wire id 3 @1693). The second evidence, kept here,
 * is the engine's own repository-removal callback truth captured by
 * {@code EngineBornGrowthSeam} for EVERY removed {@code Ship} (roster included):
 * {@code removedHealthy} = {@code !isHulk() && hitpoints > 0} at removal; an UNHEALTHY
 * removal of a non-growth id is a death (departures leave alive; growth ids ride
 * {@link GrowthDespawnReclassifier} instead and are never deposited here). No hull-family
 * special case — any hulkless kill class rides the same law.
 *
 * <p>Lifecycle: one instance per round, sim-thread only; entries are ship-scale and never
 * pruned within a round. A removal truth that arrives AFTER the DEPARTED event already
 * published is the caller's loud line ({@code despawn_reason_late_unhealthy_removal}) — the
 * rider watch decides whether that lag class exists before any code holds roster despawns
 * for it (the growth reclassifier's hold is deliberately NOT extended to roster ids: the
 * (f) seat-mount-loss loop reads the same list on the same tick).
 */
public final class DespawnReasonEvidence {

    /** Evidence label: a corpse family was born for this id this tick. */
    public static final String EVIDENCE_HULK_FAMILY_BORN_SAME_TICK = "hulk_family_born_same_tick";
    /** Evidence label: the engine removed this id while not healthy. */
    public static final String EVIDENCE_ENGINE_REMOVED_UNHEALTHY = "engine_removed_unhealthy";

    private final Map<Long, Long> unhealthyRemovedTickById = new HashMap<>();

    /**
     * Deposit one unhealthy repository removal for a NON-growth wire id at {@code simTick}.
     * Idempotent per id (a re-removal keeps the first tick).
     */
    public void recordUnhealthyRemoval(long wireId, long simTick) {
        unhealthyRemovedTickById.putIfAbsent(wireId, simTick);
    }

    /** Tick of the deposited unhealthy removal for {@code wireId}, or {@code null}. */
    public Long unhealthyRemovalTickOf(long wireId) {
        return unhealthyRemovedTickById.get(wireId);
    }

    /**
     * Resolve the evidence that turns a DEPARTED read for {@code wireId} into DESTROYED, or
     * {@code null} when none exists. Corpse-family evidence wins (it costs nothing and is
     * the older law); the unhealthy-removal entry is CONSUMED when it is the deciding
     * evidence so a later same-id despawn (T4 remount, fresh death) never re-uses it.
     */
    public String consumeEvidenceFor(long wireId, Set<Long> hulkFamilyRootsBornThisTick) {
        Objects.requireNonNull(hulkFamilyRootsBornThisTick, "hulkFamilyRootsBornThisTick");
        if (hulkFamilyRootsBornThisTick.contains(wireId)) {
            unhealthyRemovedTickById.remove(wireId);
            return EVIDENCE_HULK_FAMILY_BORN_SAME_TICK;
        }
        if (unhealthyRemovedTickById.remove(wireId) != null) {
            return EVIDENCE_ENGINE_REMOVED_UNHEALTHY;
        }
        return null;
    }

    /**
     * The per-tick DEPARTED→DESTROYED correction law, factored
     * out of the serve loop so the contract suite exercises the
     * REAL ordering: evidence is consumed for EVERY despawned id — a natively-DESTROYED kill's
     * deposit otherwise pended all round, and a seat wire id re-pointed at the T4 remount would
     * have had the refield hull's later healthy retreat (DEPARTED) rewritten DESTROYED on the
     * wire — but USED only on a DEPARTED read. Returns {@code tickDespawns} itself when nothing
     * was corrected (no per-tick copy), else a corrected copy keeping each ORIGINAL despawn tick.
     * {@code onCorrected} sees (original event, evidence label); {@code onStillDeparted} sees
     * every DEPARTED read no evidence overturned (the caller remembers it for the late-corpse
     * fan).
     */
    public List<EntityDespawnEvent> correctDepartedReads(List<EntityDespawnEvent> tickDespawns,
            Set<Long> hulkFamilyRootsBornThisTick,
            BiConsumer<EntityDespawnEvent, String> onCorrected,
            Consumer<EntityDespawnEvent> onStillDeparted) {
        List<EntityDespawnEvent> corrected = null;
        for (int i = 0; i < tickDespawns.size(); i++) {
            EntityDespawnEvent d = tickDespawns.get(i);
            String evidence = consumeEvidenceFor(d.entityId(), hulkFamilyRootsBornThisTick);
            if (d.reason() != EntityDespawnReason.DEPARTED) {
                continue;
            }
            if (evidence != null) {
                if (corrected == null) {
                    corrected = new ArrayList<>(tickDespawns);
                }
                corrected.set(i, EntityDespawnEvent.destroyed(d.entityId(), d.simTick()));
                onCorrected.accept(d, evidence);
            } else {
                onStillDeparted.accept(d);
            }
        }
        return corrected != null ? corrected : tickDespawns;
    }

    /** Unhealthy removals deposited but not yet consumed (diagnostics/tests). */
    public int pendingCount() {
        return unhealthyRemovedTickById.size();
    }
}
