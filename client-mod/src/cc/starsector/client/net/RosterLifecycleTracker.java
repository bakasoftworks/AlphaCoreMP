package cc.starsector.client.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client-side lifecycle consumer: derives entity-despawn events from
 * the authoritative snapshot stream the client already receives, so the puppet
 * plugin can remove a local puppet when the server entity it tracks leaves the
 * world. Before this, the client reacted only to <em>pose</em> updates and never
 * to an entity <em>leaving</em> — a despawned ship's puppet lingered forever.
 *
 * <p>This is the receiving-half mirror of the server's
 * {@code cc.starsector.server.runtime.EntityDespawnEventDetector}: the server
 * derives an {@code EntityDespawnEvent} when a stable {@code entityId} present in
 * the previous authoritative frame is absent from the current one, classifying the
 * reason from the entity's last-seen hull vs a destroyed-hull threshold. This
 * tracker performs the identical roster diff and classification over the
 * client-decoded {@link ServerSnapshotFrame} stream, using the
 * {@link ServerEntityPose#hitpoints() hull} the client now decodes. The game-free
 * cross-check streams real
 * codec-encoded frames over a loopback socket and asserts this tracker emits
 * exactly what the server detector emits over the same frames — keeping the two
 * mirrors in sync (the same discipline that keeps {@link AwssSnapshotReader} in
 * sync with the server codec).
 *
 * <p><strong>Threshold sync.</strong> {@link #DEFAULT_DESTROYED_HULL_THRESHOLD}
 * mirrors the server detector's {@code DEFAULT_DESTROYED_HULL_THRESHOLD} (800.0).
 * They must stay equal for the reason classification to match; the cross-check
 * verifier fails closed if they drift.
 *
 * <p><strong>Client-side lenience.</strong> Unlike the server detector (single,
 * strictly tick-ordered authoritative stream), the combat render loop polls a
 * latest-wins frame slot ({@code ServerSnapshotClient.latestFrame()}) and re-shows
 * the same frame between network updates. So a frame whose {@code simTick} is not
 * strictly newer than the last observed is treated as already-processed and yields
 * no despawns (rather than throwing as the server detector does on a non-increasing
 * tick). Removals are still caught when the roster shrinks across observed frames,
 * even if intermediate frames were skipped by latest-wins polling — a skipped frame
 * only shifts the attributed despawn tick to the first observed frame in which the
 * entity is absent.
 *
 * <p>Stateful and single-threaded: call {@link #observe} from the one combat thread.
 */
public final class RosterLifecycleTracker {

    /**
     * Default hull at or below which a vanished entity is classified
     * {@link ClientDespawnReason#DESTROYED} rather than
     * {@link ClientDespawnReason#DEPARTED}. Mirrors the server detector's
     * {@code EntityDespawnEventDetector.DEFAULT_DESTROYED_HULL_THRESHOLD}.
     */
    public static final float DEFAULT_DESTROYED_HULL_THRESHOLD = 800.0f;

    private final float destroyedHullThreshold;
    private Map<Long, ServerEntityPose> previousByEntityId = Map.of();
    private boolean sawFirstFrame = false;
    private long lastObservedTick = Long.MIN_VALUE;

    public RosterLifecycleTracker() {
        this(DEFAULT_DESTROYED_HULL_THRESHOLD);
    }

    public RosterLifecycleTracker(float destroyedHullThreshold) {
        if (!(destroyedHullThreshold >= 0.0f) || Float.isInfinite(destroyedHullThreshold)) {
            throw new IllegalArgumentException(
                    "destroyedHullThreshold must be finite and >= 0; got " + destroyedHullThreshold);
        }
        this.destroyedHullThreshold = destroyedHullThreshold;
    }

    /**
     * Fold one decoded frame into the tracker, returning the despawn events newly
     * derived by this frame (empty when the roster did not shrink, or when this
     * frame's tick is not strictly newer than the last observed — see the class
     * note on client-side lenience). Entities are diffed against the previous
     * observed frame by stable {@code entityId}.
     */
    public List<ClientEntityDespawn> observe(ServerSnapshotFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (sawFirstFrame && frame.simTick() <= lastObservedTick) {
            return List.of(); // already-observed / re-shown latest-wins frame — no diff
        }
        Map<Long, ServerEntityPose> currentByEntityId = new LinkedHashMap<>();
        for (ServerEntityPose pose : frame.entities()) {
            currentByEntityId.put(pose.entityId(), pose);
        }
        List<ClientEntityDespawn> emitted = new ArrayList<>();
        if (sawFirstFrame) {
            for (Map.Entry<Long, ServerEntityPose> previous : previousByEntityId.entrySet()) {
                long entityId = previous.getKey();
                if (currentByEntityId.containsKey(entityId)) {
                    continue;
                }
                emitted.add(new ClientEntityDespawn(
                        entityId, frame.simTick(), classify(previous.getValue())));
            }
        }
        previousByEntityId = currentByEntityId;
        sawFirstFrame = true;
        lastObservedTick = frame.simTick();
        return List.copyOf(emitted);
    }

    public float destroyedHullThreshold() {
        return destroyedHullThreshold;
    }

    private ClientDespawnReason classify(ServerEntityPose lastSeen) {
        return lastSeen.hitpoints() <= destroyedHullThreshold
                ? ClientDespawnReason.DESTROYED
                : ClientDespawnReason.DEPARTED;
    }
}
