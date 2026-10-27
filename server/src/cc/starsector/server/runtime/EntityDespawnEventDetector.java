package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.EntityDespawnEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-side producer that derives explicit {@link EntityDespawnEvent}s from the
 * authoritative snapshot stream ({@link AuthoritativeWorldSnapshot} per completed
 * tick).
 *
 * <p>It feeds on the server's <em>full</em> authoritative roster — the source
 * snapshots before any per-peer relevance scoping or schema projection
 * ({@code WorldSnapshotPublicationPolicy.govern}). That ordering is deliberate: a
 * ship dropping out of one peer's relevance set must never look
 * like a despawn, so the detector only ever sees real world-membership changes.
 * When a stable {@code entityId} that was present in the previous frame is absent
 * from the current frame, the entity left the world and the detector emits one
 * despawn event keyed at the current frame's {@code simTick} — exactly the tick at
 * which that {@code entityId} first stops appearing (the implicit-despawn
 * tick), now made explicit.
 *
 * <p>The despawn {@link cc.starsector.server.protocol.EntityDespawnReason reason} is
 * classified from the entity's last authoritative hull: at or below
 * {@code destroyedHullThreshold} it is a combat {@code DESTROYED}, otherwise a
 * non-lethal {@code DEPARTED}. This is the same hull-driven kill criterion the real
 * engine lethal capture proved (
 * each killed ship's hull driven from 3200 to well under the kill threshold before it
 * vanished), so feeding that capture's frames in yields a {@code DESTROYED} event per
 * real kill.
 *
 * <p>The detector is stateful and single-threaded: {@link #observe} each frame in
 * tick order. A frame that re-introduces an {@code entityId} after a gap is treated
 * as a fresh presence — under a respawn-enabled serve a seat's wire id
 * legitimately DOES reappear on remount, and this diff-against-previous-frame shape
 * handles it by construction: the reappeared id's next disappearance emits a fresh
 * despawn event (each hull death fires T3 once).
 */
public final class EntityDespawnEventDetector {

    /**
     * Default hull at or below which a vanished entity is classified
     * {@code DESTROYED} rather than {@code DEPARTED}. Matches the real-engine lethal
     * capture's {@code kill_threshold_hull}; a
     * killed ship's last observed hull is driven well under this before it despawns.
     */
    public static final float DEFAULT_DESTROYED_HULL_THRESHOLD = 800.0f;

    private final float destroyedHullThreshold;
    private final List<EntityDespawnEvent> events = new ArrayList<>();
    private Map<Long, AuthoritativeEntitySnapshot> previousByEntityId = Map.of();
    private boolean sawFirstFrame = false;
    private long lastObservedTick = -1L;

    public EntityDespawnEventDetector() {
        this(DEFAULT_DESTROYED_HULL_THRESHOLD);
    }

    public EntityDespawnEventDetector(float destroyedHullThreshold) {
        if (!(destroyedHullThreshold >= 0.0f) || Float.isInfinite(destroyedHullThreshold)) {
            throw new IllegalArgumentException(
                    "destroyedHullThreshold must be finite and >= 0; got " + destroyedHullThreshold);
        }
        this.destroyedHullThreshold = destroyedHullThreshold;
    }

    /**
     * Fold one authoritative frame into the detector, returning the despawn events
     * newly emitted by this frame (empty when the roster did not shrink). Frames must
     * be supplied in non-decreasing {@code simTick} order.
     */
    public List<EntityDespawnEvent> observe(AuthoritativeWorldSnapshot frame) {
        Objects.requireNonNull(frame, "frame");
        if (sawFirstFrame && frame.simTick() < lastObservedTick) {
            throw new IllegalArgumentException(
                    "frames must be observed in non-decreasing simTick order; got " + frame.simTick()
                            + " after " + lastObservedTick);
        }
        Map<Long, AuthoritativeEntitySnapshot> currentByEntityId = new LinkedHashMap<>();
        for (AuthoritativeEntitySnapshot entity : frame.entities()) {
            currentByEntityId.put(entity.entityId(), entity);
        }
        List<EntityDespawnEvent> emitted = new ArrayList<>();
        if (sawFirstFrame) {
            for (Map.Entry<Long, AuthoritativeEntitySnapshot> previous : previousByEntityId.entrySet()) {
                long entityId = previous.getKey();
                if (currentByEntityId.containsKey(entityId)) {
                    continue;
                }
                EntityDespawnEvent event = new EntityDespawnEvent(
                        entityId, frame.simTick(), classify(previous.getValue()));
                emitted.add(event);
                events.add(event);
            }
        }
        previousByEntityId = currentByEntityId;
        sawFirstFrame = true;
        lastObservedTick = frame.simTick();
        return List.copyOf(emitted);
    }

    /** Fold every frame in order; returns all despawn events derived across the stream. */
    public List<EntityDespawnEvent> observeAll(List<AuthoritativeWorldSnapshot> frames) {
        Objects.requireNonNull(frames, "frames");
        for (AuthoritativeWorldSnapshot frame : frames) {
            observe(frame);
        }
        return events();
    }

    /** Every despawn event derived so far, in emission (tick, then entityId) order. */
    public List<EntityDespawnEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Whether {@code entityId} was present in the LAST observed frame,
     * i.e. its vanish (if any) has not been observed yet. The serve loop's unhealthy-removal
     * evidence ({@link DespawnReasonEvidence}) deposits ONLY while this holds: a repository
     * removal of an id whose despawn already published is a post-death hulk cleanup
     * (overkill vaporize / off-camera fade), and a seat's wire id is re-pointed at the T4
     * remount — a stale deposit would turn the refield hull's later retreat into DESTROYED.
     */
    public boolean wasPresentInLastObservedFrame(long entityId) {
        return previousByEntityId.containsKey(entityId);
    }

    public float destroyedHullThreshold() {
        return destroyedHullThreshold;
    }

    private cc.starsector.server.protocol.EntityDespawnReason classify(
            AuthoritativeEntitySnapshot lastSeen) {
        if (lastSeen.hitpoints() <= destroyedHullThreshold) {
            return cc.starsector.server.protocol.EntityDespawnReason.DESTROYED;
        }
        return cc.starsector.server.protocol.EntityDespawnReason.DEPARTED;
    }
}
