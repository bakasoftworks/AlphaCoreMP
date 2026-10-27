package cc.starsector.server.runtime;

import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.EntityDespawnReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * despawn-REASON reclassification for engine-born growth entities
 * (bay fighters / drones / engine-spawned ships announced through the growth seam).
 *
 * <p>The snapshot-absence detector ({@link EntityDespawnEventDetector}) classifies
 * DESTROYED iff the last-seen hull is at or under 800 — correct for every capital-band
 * hull it was built against, but fighter max hull sits UNDER that threshold, so
 * snapshot absence alone misclassifies EVERY fighter dock/land as a kill (the client
 * would mint an HE death for each returning fighter). The growth seam's repository
 * removal callback carries the truth ({@code removedHealthy} = {@code !isHulk} and
 * {@code hitpoints > 0} at removal), but seam removal can LAG snapshot absence by one
 * tick ({@code land()} stages the remove during ship advance vs the flush order) — so
 * growth-id despawn events HOLD up to {@link #MAX_HOLD_TICKS} ticks for seam truth,
 * then fall back to the detector's reason with a loud line. Held events keep their
 * ORIGINAL {@code simTick} when released. Non-growth ids pass through untouched (zero
 * behavior change), and the AWSD anchor evict stays fed by the RAW detector list at
 * the call site (eviction is reason-agnostic ground truth, never held).
 *
 * <p>WIRE-MEASURED (a recorded strike reel): {@code reclassify_timeout}
 * is an ACCEPTED kill-shape class — all 8 timeout rows were real mid-field kills
 * (hull_last 0.030–0.942) whose repository-removal callback never arrived in-window,
 * fallback DESTROYED correct 8/8, while every dock/land (9/9 DEPARTED daggers, hull
 * 1.000 at the bay) resolved via seam truth inside the window; dock traffic staying
 * timeout-free is the standing watch-for. Hold STAYS {@link #MAX_HOLD_TICKS} — the
 * proposed per-id return-flight hold was REFUTED by that measurement (return flights stay
 * wire-present to the landing; absence fires AT removal). Truth that still arrives
 * post-timeout is consumed-and-dropped LOUD ({@code reclassify_late_truth}) — the
 * fallback event already left on the EDSP lane, so it is never republished and never
 * left pending.
 *
 * <p>Per-round lifecycle like the detector: mint fresh each round, sim-thread only.
 */
public final class GrowthDespawnReclassifier {

    /**
     * Maximum ticks a growth-id despawn waits for seam truth: absence and seam removal
     * land the same tick on the common path, at most one tick apart on the staged-remove
     * path — 2 covers both with one tick of slack while keeping the client's despawn
     * presentation effectively immediate (≪ one frame of perceived delay at 60 tps).
     */
    public static final int MAX_HOLD_TICKS = 2;

    private record HeldDespawn(EntityDespawnEvent event, long heldSinceTick) {
    }

    private final Set<Long> growthEntityIds = new HashSet<>();
    private final Map<Long, Boolean> seamRemovedHealthyById = new HashMap<>();
    private final Map<Long, HeldDespawn> heldById = new LinkedHashMap<>();
    /** Ids whose hold expired → the fallback reason published (late-truth tombstones). */
    private final Map<Long, EntityDespawnReason> timedOutReasonById = new HashMap<>();

    /** Mark one wire id as growth-born (call at the announce drain, before its despawn). */
    public void registerGrowthId(long entityId) {
        growthEntityIds.add(entityId);
    }

    /**
     * Deposit seam removal truth for one growth id ({@code removedHealthy} =
     * alive-and-intact at the repository removal callback ⇒ DEPARTED). May arrive
     * before OR up to {@link #MAX_HOLD_TICKS} ticks after the detector's absence event;
     * consumed by the fold that releases the matching despawn. Truth arriving AFTER the
     * hold already expired is consumed-and-dropped with a loud line
     * — the fallback event already published, so a duplicate despawn is
     * never minted and the entry never rots in the pending map.
     */
    public void recordSeamTruth(long entityId, boolean removedHealthy) {
        EntityDespawnReason published = timedOutReasonById.get(entityId);
        if (published != null) {
            EntityDespawnReason truth =
                    removedHealthy ? EntityDespawnReason.DEPARTED : EntityDespawnReason.DESTROYED;
            System.out.println("growth-despawn reclassify_late_truth id=" + entityId
                    + " removed_healthy=" + removedHealthy
                    + " published_reason=" + published
                    + " agrees=" + (truth == published)
                    + " (truth after hold expiry: event already published, truth dropped)");
            return;
        }
        seamRemovedHealthyById.put(entityId, removedHealthy);
    }

    /**
     * Fold one tick's detector events, returning the events to publish THIS tick:
     * previously-held growth despawns whose seam truth arrived (re-reasoned, original
     * tick) or whose hold expired (detector reason, loud line), then this tick's
     * events — non-growth ids verbatim, growth ids re-reasoned immediately when truth
     * is already present, else held. Call once per tick in non-decreasing tick order.
     */
    public List<EntityDespawnEvent> fold(List<EntityDespawnEvent> detectorEvents, long simTick) {
        Objects.requireNonNull(detectorEvents, "detectorEvents");
        List<EntityDespawnEvent> out = new ArrayList<>(detectorEvents.size() + heldById.size());
        // Held releases first (older despawnTicks stay ahead on the EDSP lane).
        var held = heldById.entrySet().iterator();
        while (held.hasNext()) {
            Map.Entry<Long, HeldDespawn> entry = held.next();
            long entityId = entry.getKey();
            HeldDespawn pending = entry.getValue();
            Boolean truth = seamRemovedHealthyById.remove(entityId);
            if (truth != null) {
                out.add(reclassified(pending.event(), truth));
                held.remove();
            } else if (simTick - pending.heldSinceTick() >= MAX_HOLD_TICKS) {
                System.out.println("growth-despawn reclassify_timeout id=" + entityId
                        + " held_since_tick=" + pending.heldSinceTick()
                        + " fallback_reason=" + pending.event().reason()
                        + " tick=" + simTick
                        + " (no seam removal within " + MAX_HOLD_TICKS + " ticks)");
                out.add(pending.event());
                held.remove();
                timedOutReasonById.put(entityId, pending.event().reason());
            }
        }
        for (EntityDespawnEvent event : detectorEvents) {
            long entityId = event.entityId();
            if (!growthEntityIds.contains(entityId)) {
                out.add(event);
                continue;
            }
            Boolean truth = seamRemovedHealthyById.remove(entityId);
            if (truth != null) {
                out.add(reclassified(event, truth));
                continue;
            }
            // A fresh hold supersedes any timeout tombstone for this id (same-id
            // re-despawn is unreachable by construction — belt only).
            timedOutReasonById.remove(entityId);
            HeldDespawn displaced = heldById.put(entityId, new HeldDespawn(event, simTick));
            if (displaced != null) {
                // Defensive: growth stable keys are fresh UUIDs, a same-id re-despawn
                // inside the hold window should be unreachable — never drop it silently.
                System.out.println("growth-despawn reclassify_displaced id=" + entityId
                        + " held_since_tick=" + displaced.heldSinceTick()
                        + " fallback_reason=" + displaced.event().reason()
                        + " tick=" + simTick);
                out.add(displaced.event());
            }
        }
        return out;
    }

    /** Growth despawns currently awaiting seam truth (diagnostics/tests). */
    public int heldCount() {
        return heldById.size();
    }

    /** Seam truths deposited but not yet consumed by a matching despawn (diagnostics/tests). */
    public int pendingTruthCount() {
        return seamRemovedHealthyById.size();
    }

    private static EntityDespawnEvent reclassified(EntityDespawnEvent event, boolean removedHealthy) {
        EntityDespawnReason reason =
                removedHealthy ? EntityDespawnReason.DEPARTED : EntityDespawnReason.DESTROYED;
        if (reason == event.reason()) {
            return event;
        }
        return new EntityDespawnEvent(event.entityId(), event.simTick(), reason);
    }
}
