package cc.starsector.server.runtime;

import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.EntityDespawnReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * per-round evidence ledger behind the same-tick despawn-REASON corrections
 * of ship despawns (run in
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
 * <p>THE THIRD EVIDENCE (the accidental-retreat desync):
 * the SAME callback's HEALTHY truth, for the REVERSE correction. The absolute threshold misreads a
 * hull that leaves the world alive at or under it: the owner's afflictor (max 1500) retreated at
 * 719 hull and published DESTROYED — the seat cause read HULL_DEATH, the other window painted an
 * HE kill for a ship that flew off intact, and the own client (whose local engine had removed the
 * body in its own fly-off) sat stranded in the command shuttle because its killOwn exemption keyed
 * on DEPARTED. Any hull whose MAX sits under the threshold (a vanilla Omen, 750) departs the same
 * way. The engine had already said "alive and intact" at the removal and that truth was DISCARDED
 * for non-growth ids. Now a DESTROYED read whose id the engine removed HEALTHY within
 * {@link #HEALTHY_REMOVAL_WINDOW_TICKS} of the read becomes DEPARTED at its original tick, AFTER
 * the corpse-family and unhealthy evidence (a corpse born this tick still proves a kill):
 * precedence = family > unhealthy > healthy. General on purpose — every engine vanish of a live
 * hull (a retreat fly-off, a dive, {@code /despawn}, a transfer pod's end) is a departure, never a
 * kill — so it keys on {@code removedHealthy}, not on {@code isRetreating}. The detector's
 * threshold stays the fallback when no engine truth arrives.
 *
 * <p>Lifecycle: one instance per round, sim-thread only. Unhealthy entries are ship-scale and
 * never pruned within a round. Healthy entries are {@code put} (the LATEST tick wins — a same-tick
 * remove + re-add must never leave a stale earlier tick under a later real kill) and pruned at
 * each correction pass once older than the window, so nothing a read never consumed rots. A
 * removal truth that arrives AFTER the event already published is the caller's loud line
 * ({@code despawn_reason_late_unhealthy_removal} / {@code despawn_reason_late_healthy_removal}) —
 * the rider watch decides whether that lag class exists before any code holds roster despawns
 * for it (the growth reclassifier's hold is deliberately NOT extended to roster ids: the (f)
 * seat-mount-loss loop reads the same list on the same tick).
 */
public final class DespawnReasonEvidence {

    /** Evidence label: a corpse family was born for this id this tick. */
    public static final String EVIDENCE_HULK_FAMILY_BORN_SAME_TICK = "hulk_family_born_same_tick";
    /** Evidence label: the engine removed this id while not healthy. */
    public static final String EVIDENCE_ENGINE_REMOVED_UNHEALTHY = "engine_removed_unhealthy";
    /** Evidence label: the engine removed this id ALIVE and intact — a departure. */
    public static final String EVIDENCE_ENGINE_REMOVED_HEALTHY = "engine_removed_healthy";

    /**
     * How many ticks a healthy-removal deposit may PRECEDE the despawn read it decides. The common
     * path is 0: the fleet manager's retreat removal is flushed by the SAME engine advance
     * ({@code executeRemoves} runs after {@code CombatFleetManager.advance} inside one advance), the
     * snapshot after it lacks the ship, the (d2) drain deposits and this tick's read consumes. One
     * tick of slack mirrors the growth reclassifier's staged-remove class; anything older is a
     * stale deposit and never decides (a kill the very next tick carries its own unhealthy or
     * corpse evidence, which outranks it).
     */
    public static final long HEALTHY_REMOVAL_WINDOW_TICKS = 1L;

    private final Map<Long, Long> unhealthyRemovedTickById = new HashMap<>();
    private final Map<Long, Long> healthyRemovedTickById = new HashMap<>();

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
     * Deposit one HEALTHY repository removal ({@code !isHulk && hitpoints > 0}) for a NON-growth
     * wire id at {@code simTick}. The LATEST tick wins ({@code put}, never {@code putIfAbsent}): an
     * id removed and re-added inside one tick keeps its newest truth, so a stale earlier tick can
     * never sit under a later real kill of the same wire id.
     */
    public void recordHealthyRemoval(long wireId, long simTick) {
        healthyRemovedTickById.put(wireId, simTick);
    }

    /** Tick of the deposited healthy removal for {@code wireId}, or {@code null}. */
    public Long healthyRemovalTickOf(long wireId) {
        return healthyRemovedTickById.get(wireId);
    }

    /** The reason a read corrected on {@code evidence} ends with (the serve row's direction). */
    public static EntityDespawnReason correctedReasonFor(String evidence) {
        return EVIDENCE_ENGINE_REMOVED_HEALTHY.equals(evidence)
                ? EntityDespawnReason.DEPARTED : EntityDespawnReason.DESTROYED;
    }

    /**
     * Resolve the evidence that decides the reason of a despawn read for {@code wireId} at
     * {@code despawnTick}, or {@code null} when none exists. Precedence: corpse family (it costs
     * nothing and is the oldest law) > unhealthy removal (a hulkless kill) > healthy removal within
     * {@link #HEALTHY_REMOVAL_WINDOW_TICKS} before the read (a departure). EVERY deposit for the id
     * is CONSUMED by this read whether or not it decides, so a later same-id despawn (T4 remount,
     * fresh death) never re-uses it.
     */
    public String consumeEvidenceFor(long wireId, long despawnTick, Set<Long> hulkFamilyRootsBornThisTick) {
        Objects.requireNonNull(hulkFamilyRootsBornThisTick, "hulkFamilyRootsBornThisTick");
        Long healthyTick = healthyRemovedTickById.remove(wireId);
        if (hulkFamilyRootsBornThisTick.contains(wireId)) {
            unhealthyRemovedTickById.remove(wireId);
            return EVIDENCE_HULK_FAMILY_BORN_SAME_TICK;
        }
        if (unhealthyRemovedTickById.remove(wireId) != null) {
            return EVIDENCE_ENGINE_REMOVED_UNHEALTHY;
        }
        if (healthyTick != null) {
            long age = despawnTick - healthyTick;
            if (age >= 0L && age <= HEALTHY_REMOVAL_WINDOW_TICKS) {
                return EVIDENCE_ENGINE_REMOVED_HEALTHY;
            }
        }
        return null;
    }

    /**
     * The per-tick reason-correction law, factored
     * out of the serve loop so the contract suite exercises the
     * REAL ordering: evidence is consumed for EVERY despawned id — a natively-DESTROYED kill's
     * deposit otherwise pended all round, and a seat wire id re-pointed at the T4 remount would
     * have had the refield hull's later healthy retreat (DEPARTED) rewritten DESTROYED on the
     * wire. The evidence also decides the corpseless bit: {@code corpseless} holds exactly when
     * the deciding evidence is {@link #EVIDENCE_ENGINE_REMOVED_UNHEALTHY} (an unhealthy engine
     * removal with NO corpse family born this tick); corpse-family evidence or NO evidence
     * leaves it {@code false} — unknown is never corpseless, since a family may land a tick
     * late and the client's own wreck path must stand. Per read:
     * <ul>
     * <li>DEPARTED + kill evidence (family / unhealthy) ⇒ {@code destroyed(id, tick, corpseless)},
     *     then {@code onCorrected} sees (original event, evidence label);</li>
     * <li>DEPARTED, no kill evidence ⇒ verbatim, then {@code onStillDeparted} sees it (the caller
     *     remembers it for the late-corpse fan);</li>
     * <li>DESTROYED + healthy removal ⇒ {@code departed(id, tick)} at the ORIGINAL tick, then
     *     {@code onCorrected} sees (original event, evidence label) and {@code onStillDeparted}
     *     sees the restored DEPARTED (a corpse family landing late for it prints loud);</li>
     * <li>DESTROYED + corpseless (and not already so) ⇒ the same event re-minted corpseless,
     *     then {@code onCorpseless} sees (original event, evidence label);</li>
     * <li>DESTROYED otherwise ⇒ verbatim.</li>
     * </ul>
     * The direction of a correction is {@link #correctedReasonFor} of the label the sink receives.
     * Each pass also prunes healthy deposits older than the window behind this tick's reads.
     * Returns {@code tickDespawns} itself when no read was rewritten (no per-tick copy), else a
     * rewritten copy keeping each ORIGINAL despawn tick.
     */
    public List<EntityDespawnEvent> correctDepartedReads(List<EntityDespawnEvent> tickDespawns,
            Set<Long> hulkFamilyRootsBornThisTick,
            BiConsumer<EntityDespawnEvent, String> onCorrected,
            BiConsumer<EntityDespawnEvent, String> onCorpseless,
            Consumer<EntityDespawnEvent> onStillDeparted) {
        List<EntityDespawnEvent> corrected = null;
        long newestTick = Long.MIN_VALUE;
        for (int i = 0; i < tickDespawns.size(); i++) {
            EntityDespawnEvent d = tickDespawns.get(i);
            newestTick = Math.max(newestTick, d.simTick());
            String evidence = consumeEvidenceFor(d.entityId(), d.simTick(), hulkFamilyRootsBornThisTick);
            boolean corpseless = EVIDENCE_ENGINE_REMOVED_UNHEALTHY.equals(evidence);
            boolean healthy = EVIDENCE_ENGINE_REMOVED_HEALTHY.equals(evidence);
            if (d.reason() == EntityDespawnReason.DEPARTED) {
                if (evidence != null && !healthy) {
                    if (corrected == null) {
                        corrected = new ArrayList<>(tickDespawns);
                    }
                    corrected.set(i, EntityDespawnEvent.destroyed(d.entityId(), d.simTick(), corpseless));
                    onCorrected.accept(d, evidence);
                } else {
                    onStillDeparted.accept(d);
                }
            } else if (d.reason() == EntityDespawnReason.DESTROYED && healthy) {
                // The engine removed this hull alive and intact — a departure the
                // threshold misread. Original tick kept; the restored DEPARTED joins the
                // still-departed set so a late corpse family for it stays loud, never silent.
                if (corrected == null) {
                    corrected = new ArrayList<>(tickDespawns);
                }
                EntityDespawnEvent departed = EntityDespawnEvent.departed(d.entityId(), d.simTick());
                corrected.set(i, departed);
                onCorrected.accept(d, evidence);
                onStillDeparted.accept(departed);
            } else if (d.reason() == EntityDespawnReason.DESTROYED && corpseless && !d.corpseless()) {
                if (corrected == null) {
                    corrected = new ArrayList<>(tickDespawns);
                }
                corrected.set(i, EntityDespawnEvent.destroyed(d.entityId(), d.simTick(), true));
                onCorpseless.accept(d, evidence);
            }
        }
        if (newestTick != Long.MIN_VALUE) {
            pruneHealthyRemovalsOlderThan(newestTick - HEALTHY_REMOVAL_WINDOW_TICKS);
        }
        return corrected != null ? corrected : tickDespawns;
    }

    /**
     * Drop healthy deposits stamped BEFORE {@code tick}: a removal no despawn read consumed (the id
     * stayed in the roster — a same-tick remove + re-add) can no longer decide anything, so it must
     * not sit in the ledger all round.
     */
    public void pruneHealthyRemovalsOlderThan(long tick) {
        Iterator<Map.Entry<Long, Long>> it = healthyRemovedTickById.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < tick) {
                it.remove();
            }
        }
    }

    /** Removals (unhealthy + healthy) deposited but not yet consumed (diagnostics/tests). */
    public int pendingCount() {
        return unhealthyRemovedTickById.size() + healthyRemovedTickById.size();
    }
}
