package cc.starsector.client.net;

import java.util.List;
import java.util.Map;

/**
 * One decoded server world-snapshot frame: the simulation
 * tick plus the per-ship {@link ServerEntityPose} records the server published
 * for that tick, decoded from the {@code AWSS} wire payload by
 * {@link AwssSnapshotReader}.
 *
 * <p>Entities preserve the server's ascending-{@code entityId} order. Pure Java,
 * no Starsector-API dependency.
 *
 * <p>{@code fragmentMarkers} is the frame's FRAGMENT_MARKER tail tier (schema v9):
 * per partially-carried entity, which ship-scoped tiers (and which
 * mount-index range) THIS frame asserts (ABSENT ≠ EMPTY semantics).
 * Empty on every pre-v9 frame — the two-argument
 * constructor is that back-compat case. The tier-granular merge that consumes the
 * markers is the B3 {@link SnapshotChunkMerger} store; at B1 the frame only carries them.
 */
public final class ServerSnapshotFrame {

    private final long simTick;
    private final List<ServerEntityPose> entities;
    private final List<ServerFragmentMarker> fragmentMarkers;
    private final List<ServerBeamState> beams;
    private final List<ServerMissileState> missiles;
    private final Map<Integer, String> contentDictionary;

    public ServerSnapshotFrame(long simTick, List<ServerEntityPose> entities) {
        this(simTick, entities, List.of(), List.of(), List.of());
    }

    public ServerSnapshotFrame(long simTick, List<ServerEntityPose> entities,
            List<ServerFragmentMarker> fragmentMarkers) {
        this(simTick, entities, fragmentMarkers, List.of(), List.of());
    }

    public ServerSnapshotFrame(long simTick, List<ServerEntityPose> entities,
            List<ServerFragmentMarker> fragmentMarkers, List<ServerBeamState> beams) {
        this(simTick, entities, fragmentMarkers, beams, List.of());
    }

    public ServerSnapshotFrame(long simTick, List<ServerEntityPose> entities,
            List<ServerFragmentMarker> fragmentMarkers, List<ServerBeamState> beams,
            List<ServerMissileState> missiles) {
        this(simTick, entities, fragmentMarkers, beams, missiles, Map.of());
    }

    public ServerSnapshotFrame(long simTick, List<ServerEntityPose> entities,
            List<ServerFragmentMarker> fragmentMarkers, List<ServerBeamState> beams,
            List<ServerMissileState> missiles, Map<Integer, String> contentDictionary) {
        this.simTick = simTick;
        this.entities = List.copyOf(entities);
        this.fragmentMarkers = List.copyOf(fragmentMarkers);
        this.beams = List.copyOf(beams);
        this.missiles = List.copyOf(missiles);
        this.contentDictionary = Map.copyOf(contentDictionary);
    }

    public long simTick() {
        return simTick;
    }

    public List<ServerEntityPose> entities() {
        return entities;
    }

    /** The frame's fragment-marker declarations (schema v9+); empty on pre-v9 frames. */
    public List<ServerFragmentMarker> fragmentMarkers() {
        return fragmentMarkers;
    }

    /**
     * The frame's kind=BEAM non-ship records (schema v12+); empty on pre-v12
     * frames and on beam-less v12+ frames. On a chunked stream the published merge view
     * carries the {@link SnapshotChunkMerger}'s newest-tick beam union (hold-last, like
     * every merged tier).
     */
    public List<ServerBeamState> beams() {
        return beams;
    }

    /**
     * The frame's kind=MISSILE non-ship records (schema v12+; source pair
     * resolved at v13+); empty on pre-v12 frames and on missile-less v12+ frames.
     * networkIds are stable per-lifetime allocator ids, so consecutive frames' lists
     * link by id (the slice-3 (e) pose lane's twin identity). On a chunked stream the
     * published merge view carries the {@link SnapshotChunkMerger}'s newest-tick
     * missile union (hold-last, like every merged tier).
     */
    public List<ServerMissileState> missiles() {
        return missiles;
    }

    /**
     * The frame's CONTENT-DICTIONARY announcements (schema v15+):
     * interned non-ship contentId → true content key (engine projectile spec id),
     * FRAME-GLOBAL and presentation-only. Empty on pre-v15 frames and between
     * cycling-window announcements; the consumer folds entries last-write-wins
     * into its own persistent catalog (a re-fed entry is a no-op re-announce).
     */
    public Map<Integer, String> contentDictionary() {
        return contentDictionary;
    }

    public int fragmentMarkerCount() {
        return fragmentMarkers.size();
    }

    public int entityCount() {
        return entities.size();
    }

    /** The pose at the given index, or {@code null} when the frame has no such entity. */
    public ServerEntityPose entityAt(int index) {
        if (index < 0 || index >= entities.size()) {
            return null;
        }
        return entities.get(index);
    }

    /** The first pose with the given {@code entityId}, or {@code null} when absent. */
    public ServerEntityPose entityById(long entityId) {
        for (ServerEntityPose pose : entities) {
            if (pose.entityId() == entityId) {
                return pose;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "ServerSnapshotFrame{simTick=" + simTick + ",entities=" + entities.size() + "}";
    }
}
