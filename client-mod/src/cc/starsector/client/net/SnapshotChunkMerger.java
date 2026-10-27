package cc.starsector.client.net;

import cc.starsector.client.FallbackAnnounce;
import cc.starsector.server.client.ChunkMergerCore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CLIENT-tree ADAPTER of the shared
 * {@link ChunkMergerCore} reassembly law (the source-sharing extraction:
 * until then this class carried a full second copy of the law, kept equivalent to the
 * server-tree {@code MirrorChunkMerger} only by convention + the merger-equivalence
 * gate). The law — adopt/covering/merge, the ordering guard, per-tier
 * {@code tick >=} upserts, ABSENT &ne; EMPTY, the anchor + mount-range laws, the
 * record-KIND v10 cloak twin law (AWSD v3, incl. {@code TIER_PHASE_CLOAK_LOCAL}), the
 * sibling anchor-map law, and the NACK throttle — lives in the kernel; this class
 * contributes only the client-tree DECODE VOCABULARY ({@link ServerSnapshotFrame} /
 * {@link AwsdSnapshotReader}) and VIEW SYNTHESIS (whole-world frames rebuilt from stored
 * per-tier pose refs). Law javadoc of record:
 * {@code ChunkMergerCore}.
 *
 * <p><b>Scalar payloads = the pose object itself</b>, for ALL scalar tiers: the decoded
 * {@link ServerEntityPose} carries every tier's fields including the sentinels a
 * masked-absent tier decodes to, so the authoritative EMPTY clear travels naturally through
 * the kernel's verbatim payload copy (value-identical to the old per-field Cell copies).
 * {@link #synthesize} reads tier {@code i}'s fields from the stored pose at ordinal
 * {@code i}; a {@code null} slot (a tier never asserted — possible only on cells first
 * created by a fragment) synthesizes the pose UNKNOWN_* sentinels / defaults, exactly the
 * old virgin-Cell values.
 *
 * <p>The twins' documented FORM divergences are visible kernel ctor/callsite parameters
 * here: the AWSD stale-keyframe gauge = the ARRIVAL FRAME tick (the server twin passes the
 * record's embedded anchorTick — equivalent on encodable wire); {@code nackIdMax} = 0xFFFF
 * (the defensive SNNK u16 domain guard + {@link #awsdNacksSkippedWideId} skip counter); the
 * drop counters stay SPLIT ({@link #awsdDroppedEpoch}/{@link #awsdDroppedStale} — the
 * server twin publishes the sum).
 *
 * <p>Net-thread-only surfaces (the plugin's {@code runNetLoop} + its inline demux
 * callbacks); the kernel's NACK queue is internally locked for the intent-pump drain
 * (uncontended here — the plugin drains on the same net thread).
 */
public final class SnapshotChunkMerger {

    /**
     * Tier-tick sentinel for a tier no frame has ever asserted for the entity (possible only
     * on cells first created by a fragment): any real assertion, whatever its tick, wins.
     */
    public static final long NEVER_ASSERTED = ChunkMergerCore.NEVER_ASSERTED;

    static {
        // Fail-fast at class load: the kernel's canonical tier bits must be
        // numerically EQUAL to this tree's wire marker bits — the fold laws mask by the
        // kernel constants while markers arrive in ServerFragmentMarker vocabulary.
        if (ChunkMergerCore.TIER_FLUX != ServerFragmentMarker.TIER_FLUX
                || ChunkMergerCore.TIER_WEAPON_MOUNT != ServerFragmentMarker.TIER_WEAPON_MOUNT
                || ChunkMergerCore.TIER_ALLEGIANCE != ServerFragmentMarker.TIER_ALLEGIANCE
                || ChunkMergerCore.TIER_ENGINE_STATE != ServerFragmentMarker.TIER_ENGINE_STATE
                || ChunkMergerCore.TIER_MANEUVER != ServerFragmentMarker.TIER_MANEUVER
                || ChunkMergerCore.TIER_SYSTEM_STATE != ServerFragmentMarker.TIER_SYSTEM_STATE
                || ChunkMergerCore.TIER_MASK_BITS != ServerFragmentMarker.TIER_MASK_BITS) {
            throw new IllegalStateException(
                    "ChunkMergerCore tier bits diverge from ServerFragmentMarker");
        }
    }

    /**
     * Fork-A v0: one entity's stored per-tier ticks, read-only.
     * {@link #NEVER_ASSERTED} marks a tier (or an empty mount map) never asserted;
     * {@code minTick}/{@code maxTick} range over the asserted ticks only — ALL of them,
     * INCLUDING cloak (the tear METER excludes the by-design cloak keyframe-cadence lag;
     * this projection reports raw ages and spans everything) — so
     * {@code maxTick - minTick} is the entity's current intra-entity tear width and
     * {@code publishedTick - minTick} its worst-case age.
     */
    public record TierTicks(long entityId, long coreTick, long fluxTick, long mountTickMin,
            long mountTickMax, long allegianceTick, long engineTick, long maneuverTick,
            long systemTick, long cloakTick, long minTick, long maxTick) {
    }

    /** One entity's AWSD anchor: the adopted keyframe (wire-quantized), its epoch, and the
     * tick it was adopted at (the keyframe's frame tick — the delta reorder/staleness gauge). */
    private record AwsdAnchor(AwsdSnapshotReader.AwsdKeyframe kf, int epoch, long anchorTick) {
    }

    /** One queued delta NACK: the rejected delta's entityId (u16-guarded) + ADVISORY epoch. */
    public record PendingNack(int entityId, int epoch) {
    }

    /** The shared law kernel: anchor payload = the private {@link AwsdAnchor}
     * record, mount payload = the wire mount record; frame-tick anchor gauge + the 0xFFFF
     * NACK id domain are this tree's documented FORM choices (see class doc). */
    private final ChunkMergerCore<AwsdAnchor, ServerWeaponMountState> core;

    /** Default bound on each same-tick union (beams,
     * missiles, dictionary entries): a lawful union is one tick's own list split across its
     * chunks (the dictionary rides chunk #0 alone), far below it; unbounded, a held tick fed
     * sibling chunks of fresh ids grows the union, re-copied per chunk, without limit. */
    public static final int DEFAULT_UNION_TICK_CAP = ChunkMergerCore.DEFAULT_TRACKED_CAP;
    private final int unionTickCap;
    /** Union records dropped at {@link #unionTickCap} (cumulative, all three unions). */
    private long unionCapDrops;
    /** Latched by the first cap drop of any kind: the one announce, then a single boolean test. */
    private boolean capDropAnnounced;

    /** The default-bounded merger ({@link ChunkMergerCore#DEFAULT_TRACKED_CAP},
     * {@link #DEFAULT_UNION_TICK_CAP}). */
    public SnapshotChunkMerger() {
        this(ChunkMergerCore.DEFAULT_TRACKED_CAP, DEFAULT_UNION_TICK_CAP);
    }

    /** Explicit bounds: {@code trackedCap} for the cell
     * store and the anchor map (each), {@code unionTickCap} (&ge; 1) per same-tick union. */
    public SnapshotChunkMerger(int trackedCap, int unionTickCap) {
        if (unionTickCap < 1) {
            throw new IllegalArgumentException("unionTickCap must be >= 1: " + unionTickCap);
        }
        this.core = new ChunkMergerCore<>(AwsdAnchor::anchorTick, AwsdAnchor::epoch,
                ServerWeaponMountState::mountIndex, 0xFFFFL, trackedCap);
        this.unionTickCap = unionTickCap;
    }

    /**
     * The newest-tick BEAM accumulator, OUTSIDE the kernel — beams are transient
     * frame-scoped records (no per-entity cell identity to merge into). Chunks of one tick
     * each carry a subset of the non-ship list, so same-tick frames UNION by networkId; a
     * newer tick replaces wholesale (a beam absent from the newest covering tick is dead —
     * the non-ship tier is always whole per frame the server encodes it into); a reordered
     * stale frame's beams are ignored (the posture). Every published frame carries
     * the accumulator (hold-last across beam-less AWSD deltas, exactly the cell tiers' law).
     */
    private long beamTick = Long.MIN_VALUE;
    private List<ServerBeamState> beams = java.util.Collections.emptyList();

    /**
     * The newest-tick MISSILE accumulator — {@link #beams}' law verbatim
     * (transient frame-scoped records outside the kernel: same-tick UNION by networkId,
     * newer tick replaces wholesale, reordered stale frames ignored, hold-last across
     * missile-less AWSD deltas). Missile networkIds are stable per-lifetime, so the
     * union key is the same identity the (e) pose lane links twins by.
     */
    private long missileTick = Long.MIN_VALUE;
    private List<ServerMissileState> missiles = java.util.Collections.emptyList();

    /**
     * The newest-tick CONTENT-DICTIONARY accumulator — {@link #beams}' law
     * verbatim on a third twin (frame-global announcements outside the kernel: same-tick
     * UNION by contentId — chunk #0 alone carries the dict, so a same-tick sibling chunk's
     * empty map must not wipe it — newer tick replaces wholesale, reordered stale frames
     * ignored, hold-last across dict-less frames). Hold-last re-feeds are harmless: the
     * downstream catalog fold is last-write-wins idempotent, a re-fed entry is a no-op
     * re-announce.
     */
    private long dictTick = Long.MIN_VALUE;
    private Map<Integer, String> dictEntries = java.util.Collections.emptyMap();

    /**
     * Work-queue (38)(a) (the seat-2 snapshot-starvation grade): the
     * TIMESTAMP shadow of the kernel's cumulative drop/mixed meters. The kernel is tick-blind
     * at its drop callsites; this adapter has the frame tick in hand, so the shadow lives
     * here — first/last frame tick of the {@code awsdDroppedEpoch} family (no anchor / epoch
     * mismatch / roster-absent mount), and the frame tick that minted the standing
     * {@code mixedTickMax}. Cumulative like the kernel meters (NOT reset in {@link #clear} —
     * tick spaces restart per round, so a cross-round read is disambiguated by the periodic
     * merger-meter timeline, not by these fields). Net-thread writes; the plugin's meter
     * flush reads from the render thread — benign torn reads, the standing meter posture.
     */
    private long awsdDropFirstTick = Long.MIN_VALUE;
    private long awsdDropLastTick = Long.MIN_VALUE;
    private long mixedTickMaxTick = Long.MIN_VALUE;

    /**
     * The (43)(d) armor-carriage mechanism meter: synthesized poses whose
     * CORE pose lacked armor (an AWSS fold clobbered the armor-bearing AWSD core at a
     * same-or-newer tick — the prime suspect, live-counted) but whose ARMOR tier
     * ({@code I_ARMOR}, asserted only by AWSD folds) re-stamped it. Every count is a publish
     * that would have been armor-less pre-fix — i.e., a frame the armor lane's
     * {@code !hasArmor()} gate would have silently skipped. Net-thread written, meter-flush
     * read (benign torn reads, the standing meter posture).
     */
    private long armorRestamps;

    /**
     * Fold one decoded frame in and return the frame to publish: the decoded object itself on
     * the adopt/covering paths (marker-free frames only), a synthesized whole-world frame on
     * the merge path. Branch verdict, ordering guard, and fold laws = the kernel's
     * ({@code beginAwssFrame} carries the guard javadoc).
     */
    public ServerSnapshotFrame onFrame(ServerSnapshotFrame frame) {
        long tick = frame.simTick();
        foldBeams(tick, frame.beams());
        foldMissiles(tick, frame.missiles());
        foldContentDictionary(tick, frame.contentDictionary());
        ChunkMergerCore.FrameKind kind = core.beginAwssFrame(tick,
                frame.fragmentMarkerCount() == 0, id -> frame.entityById(id) != null);
        if (kind == ChunkMergerCore.FrameKind.IDENTITY) {
            for (ServerEntityPose pose : frame.entities()) {
                core.foldWhole(pose.entityId(), tick, pose, scalarsFor(pose),
                        pose.weaponMounts());
            }
            // The identity path never sees a stale tick (the ordering guard forces those
            // down the merge branch), so the accumulators differ from the frame's own lists
            // only on a same-tick union — rebuild the published frame just for that case.
            noteCapDrops();
            return beams == frame.beams() && missiles == frame.missiles()
                    && dictEntries == frame.contentDictionary() ? frame
                    : new ServerSnapshotFrame(tick, frame.entities(), frame.fragmentMarkers(),
                            beams, missiles, dictEntries);
        }
        Map<Long, ServerFragmentMarker> markerById;
        if (frame.fragmentMarkerCount() == 0) {
            markerById = java.util.Collections.emptyMap();
        } else {
            markerById = new HashMap<>();
            for (ServerFragmentMarker marker : frame.fragmentMarkers()) {
                // Anchor law: the marked entity's ship record is in this frame, so the fold
                // below reaches it via its pose; a marker without a pose (a server-side
                // invariant violation) asserts nothing here — fail-safe hold.
                markerById.put(Long.valueOf(marker.entityId()), marker);
            }
        }
        for (ServerEntityPose pose : frame.entities()) {
            ServerFragmentMarker marker = markerById.get(Long.valueOf(pose.entityId()));
            if (marker == null) {
                core.foldWhole(pose.entityId(), tick, pose, scalarsFor(pose),
                        pose.weaponMounts());
            } else {
                core.foldFragment(pose.entityId(), tick, marker.tierMask(), pose,
                        scalarsFor(pose), pose.weaponMounts(),
                        marker.mountFrom(), marker.mountTo());
            }
        }
        core.finishAwssMerge(frame.fragmentMarkerCount() > 0);
        return publishView(tick);
    }

    /**
     * Fold one decoded AWSD delta+keyframe frame in and return the synthesized whole-world
     * frame to publish. Per record — KEYFRAME: kernel stale-adopt guard,
     * then anchor stored + ALL tiers folded at the frame tick; DELTA: kernel anchor/epoch
     * guards (drop + counter + hold-last + NACK), apply cumulatively vs the returned
     * anchor (an applyDelta throw routes through the kernel's {@code deltaFailed} — the D5
     * roster-absent-mount signal), then fold at the frame tick with the cloak tier masked
     * out per the record-KIND twin law (kernel {@code foldAwsd}). Raises the ordering-guard
     * high-water so a later stale AWSS frame cannot adopt/cover-wipe newer AWSD state.
     */
    public ServerSnapshotFrame onAwsdFrame(AwsdSnapshotReader.AwsdFrame frame) {
        long tick = frame.simTick();
        for (AwsdSnapshotReader.AwsdRecord record : frame.records()) {
            long entityId = record.entityId();
            if (record.kind() == AwsdSnapshotReader.KIND_KEYFRAME) {
                if (!core.adoptKeyframe(entityId, tick,
                        new AwsdAnchor(record.keyframe(), record.epoch(), tick))) {
                    continue; // reordered stale keyframe (hold the newer anchor) or anchor cap
                }
                foldAwsdPose(entityId, record.keyframe(), tick, true);
            } else {
                AwsdAnchor anchor = core.beginDelta(entityId, record.epoch());
                if (anchor == null) {
                    noteEpochDrop(tick);
                    continue; // no anchor / epoch mismatch — hold-last until the re-key
                }
                AwsdSnapshotReader.AwsdKeyframe current;
                try {
                    current = AwsdSnapshotReader.applyDelta(record, anchor.kf());
                } catch (IllegalArgumentException d5Drop) {
                    // The reader's remaining D5 drop signal reachable here (a dirty mount
                    // index absent from the anchor roster — roster changes ride keyframes):
                    // drop + hold-last + NACK, same posture as the kernel epoch guard.
                    // 1290 (83) CL-5 Q-a: unappliable is NOT the expected epoch-mismatch class
                    // (the NACK re-key covers that) — the first one confesses; behavior unchanged.
                    FallbackAnnounce.announceOnce("q-a-awsd-delta-unappliable",
                            "entity=" + entityId + " -> drop+hold-last+NACK");
                    core.deltaFailed(entityId, record.epoch());
                    noteEpochDrop(tick);
                    continue;
                }
                foldAwsdPose(entityId, current, tick, false);
            }
        }
        core.finishAwsdFrame(tick);
        return publishView(tick);
    }

    /** The consumed EDSP despawn: drop the entity so the next covering frame kills on absence
     * (cell, AWSD anchor, and NACK state die together — the kernel sibling-map law). */
    public void evict(long entityId) {
        core.evict(entityId);
    }

    /** The MRST round boundary: forget everything before the fresh round's first frame,
     * including the ordering-guard high-water (tick spaces do not compare across rounds)
     * and the AWSD anchors (the server clears its baselines at the same boundary). */
    public void clear() {
        core.clear();
        beamTick = Long.MIN_VALUE;
        beams = java.util.Collections.emptyList();
        missileTick = Long.MIN_VALUE;
        missiles = java.util.Collections.emptyList();
        dictTick = Long.MIN_VALUE;
        dictEntries = java.util.Collections.emptyMap();
    }

    /** Fold one frame's beam list into the newest-tick accumulator (see the field doc). */
    private void foldBeams(long tick, List<ServerBeamState> frameBeams) {
        if (tick > beamTick) {
            beamTick = tick;
            beams = frameBeams;
        } else if (tick == beamTick && !frameBeams.isEmpty() && frameBeams != beams) {
            // The ref-guard keeps a re-fed duplicate frame a no-op, so the covering
            // identity contract (publish the decoded object itself) survives re-feeds.
            if (beams.isEmpty()) {
                beams = frameBeams;
            } else {
                Map<Long, ServerBeamState> byId = new java.util.LinkedHashMap<>();
                for (ServerBeamState beam : beams) {
                    byId.put(Long.valueOf(beam.networkId()), beam);
                }
                for (ServerBeamState beam : frameBeams) {
                    Long id = Long.valueOf(beam.networkId());
                    if (admitToUnion(byId, id)) {
                        byId.put(id, beam);
                    }
                }
                beams = new ArrayList<>(byId.values());
            }
        }
        // tick < beamTick: a reordered stale frame — hold the newer beams (the ordering-guard posture).
    }

    /** Fold one frame's missile list into the newest-tick accumulator — {@link #foldBeams}'
     * law verbatim on the missile twin (see the field doc). */
    private void foldMissiles(long tick, List<ServerMissileState> frameMissiles) {
        if (tick > missileTick) {
            missileTick = tick;
            missiles = frameMissiles;
        } else if (tick == missileTick && !frameMissiles.isEmpty() && frameMissiles != missiles) {
            if (missiles.isEmpty()) {
                missiles = frameMissiles;
            } else {
                Map<Long, ServerMissileState> byId = new java.util.LinkedHashMap<>();
                for (ServerMissileState missile : missiles) {
                    byId.put(Long.valueOf(missile.networkId()), missile);
                }
                for (ServerMissileState missile : frameMissiles) {
                    Long id = Long.valueOf(missile.networkId());
                    if (admitToUnion(byId, id)) {
                        byId.put(id, missile);
                    }
                }
                missiles = new ArrayList<>(byId.values());
            }
        }
        // tick < missileTick: a reordered stale frame — hold the newer missiles.
    }

    /** Fold one frame's content-dictionary announcements into the newest-tick accumulator —
     * {@link #foldBeams}' law verbatim on the third twin (see the field doc). */
    private void foldContentDictionary(long tick, Map<Integer, String> frameDict) {
        if (tick > dictTick) {
            dictTick = tick;
            dictEntries = frameDict;
        } else if (tick == dictTick && !frameDict.isEmpty() && frameDict != dictEntries) {
            if (dictEntries.isEmpty()) {
                dictEntries = frameDict;
            } else {
                Map<Integer, String> byId = new java.util.LinkedHashMap<>(dictEntries);
                for (Map.Entry<Integer, String> entry : frameDict.entrySet()) {
                    if (admitToUnion(byId, entry.getKey())) {
                        byId.put(entry.getKey(), entry.getValue());
                    }
                }
                dictEntries = byId;
            }
        }
        // tick < dictTick: a reordered stale frame — hold the newer entries.
    }

    /** The per-tick union bound: a held id is always
     * re-admitted (latest wins), a NEW id only below {@link #unionTickCap}, else dropped and
     * counted. A newer tick replaces the union wholesale, so the bound is per tick. */
    private boolean admitToUnion(Map<?, ?> union, Object id) {
        if (union.size() < unionTickCap || union.containsKey(id)) {
            return true;
        }
        unionCapDrops++;
        return false;
    }

    /** Announce the first cap drop of any kind once (a
     * dropped id is missing from merge-path views); then one boolean test per published frame.
     * The counts keep rising in {@link #meterTokens}. */
    private void noteCapDrops() {
        if (capDropAnnounced
                || (core.trackedCapDrops() | core.anchorCapDrops() | unionCapDrops) == 0) {
            return;
        }
        capDropAnnounced = true;
        FallbackAnnounce.announceOnce("snap-merger-cap-drop",
                "cells=" + core.trackedCapDrops() + " anchors=" + core.anchorCapDrops()
                        + " union=" + unionCapDrops + " trackedCap=" + core.trackedCap()
                        + " unionTickCap=" + unionTickCap + " -> new ids dropped, counted");
    }

    // ------------------------------------------------------------------ meters

    /** Frames that took the merge branch (0 on an unchunked stream). */
    public long chunksMerged() {
        return core.chunksMerged();
    }

    /** Widest cross-entity spread of per-entity as-of ticks ever published from the merge branch. */
    public long mixedTickMax() {
        return core.mixedTickMax();
    }

    /** Merge-branch frames that carried at least one fragment marker (0 without fragmentation). */
    public long fragmentsMerged() {
        return core.fragmentsMerged();
    }

    /** Widest NON-CLOAK tick spread ever seen WITHIN one entity's tiers (the intra-entity
     * tearing meter; the by-design cloak keyframe-cadence lag lives in
     * {@link #cloakAgeMax}, not here). */
    public long fragTierSpreadMax() {
        return core.fragTierSpreadMax();
    }

    /** Widest {@code cellMax − cloakTick} ever published (the dedicated
     * cloak-staleness meter; expected bound = AWSD keyframe cadence − 1). */
    public long cloakAgeMax() {
        return core.cloakAgeMax();
    }

    /** Widest {@code cellMax − crTick} ever published (the dedicated ship-CR
     * staleness meter, the cloak-age twin; expected bound = AWSD keyframe cadence − 1
     * once AWSD v6 carries CR). */
    public long crAgeMax() {
        return core.crAgeMax();
    }

    /** Frames the ordering guard forced down the merge branch as older-than-newest
     * (0 on an in-order stream; nonzero only under reordered stale-fold arrivals). */
    public long staleForcedMerges() {
        return core.staleForcedMerges();
    }

    /** AWSD keyframes adopted as anchors and folded. */
    public long awsdKeyframes() {
        return core.awsdKeyframes();
    }

    /** AWSD deltas applied vs their anchors and folded. */
    public long awsdDeltas() {
        return core.awsdDeltas();
    }

    /** AWSD deltas dropped hold-last on the D5 signals (no anchor / epoch mismatch /
     * anchor-roster-absent mount) — the step-7 NACK trigger family. */
    public long awsdDroppedEpoch() {
        return core.awsdDroppedEpoch();
    }

    /** AWSD keyframes dropped as reordered-stale (older than the standing anchor's tick). */
    public long awsdDroppedStale() {
        return core.awsdDroppedStale();
    }

    /** Work-queue (38)(a): first frame tick of the {@link #awsdDroppedEpoch} family
     * ({@code Long.MIN_VALUE} = never; round-boundary caveat = the field doc). */
    public long awsdDropFirstTick() {
        return awsdDropFirstTick;
    }

    /** Work-queue (38)(a): last frame tick of the {@link #awsdDroppedEpoch} family. */
    public long awsdDropLastTick() {
        return awsdDropLastTick;
    }

    /** Work-queue (38)(a): the frame tick that minted the standing {@link #mixedTickMax}. */
    public long mixedTickMaxTick() {
        return mixedTickMaxTick;
    }

    /**
     * Work-queue (38)(a) — the merger-meter TIMELINE tokens (leading-space separated, the
     * counter-line discipline): the cumulative kernel meters whose per-flush deltas
     * ARE the starvation timeline (the grade read foe-interp deltas exactly this way),
     * plus the timestamp shadow ({@code -1} = never). Emitted on the plugin's periodic meter
     * flush; the teardown twin is {@link #teardownTokens}.
     */
    public String meterTokens() {
        return " awsd_kf=" + core.awsdKeyframes()
                + " awsd_delta=" + core.awsdDeltas()
                + " awsd_dropped_epoch=" + core.awsdDroppedEpoch()
                + " awsd_dropped_stale=" + core.awsdDroppedStale()
                + " awsd_drop_first_tick=" + tickOrMinus1(awsdDropFirstTick)
                + " awsd_drop_last_tick=" + tickOrMinus1(awsdDropLastTick)
                + " snap_chunks_merged=" + core.chunksMerged()
                + " snap_mixed_tick_max=" + core.mixedTickMax()
                + " snap_mixed_tick_max_tick=" + tickOrMinus1(mixedTickMaxTick)
                + " armor_restamps=" + armorRestamps
                + " snap_cap_drop_cells=" + core.trackedCapDrops()
                + " snap_cap_drop_anchors=" + core.anchorCapDrops()
                + " snap_cap_drop_union=" + unionCapDrops;
    }

    /** Kernel folds refused at the tracked cap. */
    public long trackedCapDrops() {
        return core.trackedCapDrops();
    }

    /** AWSD keyframes refused at the anchor-map cap. */
    public long anchorCapDrops() {
        return core.anchorCapDrops();
    }

    /** Same-tick union records (beams, missiles, dictionary entries) dropped at the per-tick cap. */
    public long unionCapDrops() {
        return unionCapDrops;
    }

    /**
     * The merger-owned token block of the plugin's {@code runNetLoop: ended} line —
     * byte-identical key order/format to the pre-extraction inline concat (consumers regex
     * per-key off that line), with the (38)(a)
     * timestamp keys appended after the existing family.
     */
    public String teardownTokens() {
        return " snap_chunks_merged=" + core.chunksMerged()
                + " snap_mixed_tick_max=" + core.mixedTickMax()
                + " snap_fragments_merged=" + core.fragmentsMerged()
                + " snap_frag_tier_spread_max=" + core.fragTierSpreadMax()
                + " snap_cloak_age_max=" + core.cloakAgeMax()
                + " awsd_kf=" + core.awsdKeyframes()
                + " awsd_delta=" + core.awsdDeltas()
                + " awsd_dropped_epoch=" + core.awsdDroppedEpoch()
                + " awsd_dropped_stale=" + core.awsdDroppedStale()
                + " awsd_drop_first_tick=" + tickOrMinus1(awsdDropFirstTick)
                + " awsd_drop_last_tick=" + tickOrMinus1(awsdDropLastTick)
                + " snap_mixed_tick_max_tick=" + tickOrMinus1(mixedTickMaxTick)
                + " armor_restamps=" + armorRestamps;
    }

    /** Synthesized publishes whose armor-less core the armor tier
     * re-stamped — each one a would-have-been silent armor-lane skip pre-fix (see the
     * field doc). */
    public long armorRestamps() {
        return armorRestamps;
    }

    /** Work-queue (38)(a): stamp one epoch-family delta drop at its frame tick. */
    private void noteEpochDrop(long tick) {
        if (awsdDropFirstTick == Long.MIN_VALUE) {
            awsdDropFirstTick = tick;
        }
        awsdDropLastTick = tick;
    }

    /** {@code Long.MIN_VALUE} (never) prints {@code -1} — a tick no real frame can carry. */
    private static String tickOrMinus1(long tick) {
        return tick == Long.MIN_VALUE ? "-1" : Long.toString(tick);
    }

    /** AWSD anchors currently standing (test observability — the sibling-map size). */
    public int awsdAnchorCount() {
        return core.awsdAnchorCount();
    }

    /** Delta NACKs deposited into the pending queue (post-throttle, post-coalesce). */
    public long awsdNacksQueued() {
        return core.awsdNacksQueued();
    }

    /** NACK defensive u16 guard skips (ids > 0xFFFF never NACK — the server AWSD encode
     * already refuses such ids, so this is unreachable off the real wire; the guard is the
     * kernel's {@code nackIdMax} parameter). */
    public long awsdNacksSkippedWideId() {
        return core.awsdNacksSkippedWideId();
    }

    /** Delta NACKs currently pending drain (test observability — the evict/clear purge pin). */
    public int pendingNackCount() {
        return core.pendingNackCount();
    }

    /**
     * Drain surface: snapshot + clear the pending queue (the plugin deposits these into
     * its CTRL send path). The once-per-(id,epoch) throttle SURVIVES the drain — hold-last keeps
     * rejecting the same epoch every tick and the sent NACK's re-key is in flight; only a fresh
     * keyframe adopt (or evict/clear) re-arms the id. The (int) narrowing is safe: the kernel's
     * 0xFFFF id domain guard skip-counts anything wider before it can queue.
     */
    public List<PendingNack> drainPendingNacks() {
        List<ChunkMergerCore.NackEntry> drained = core.drainPendingNacks();
        if (drained.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<PendingNack> out = new ArrayList<>(drained.size());
        for (ChunkMergerCore.NackEntry entry : drained) {
            out.add(new PendingNack((int) entry.entityId(), entry.epoch()));
        }
        return out;
    }

    /** Entities currently tracked (test observability). */
    public int trackedEntities() {
        return core.trackedEntities();
    }

    /** Fork-A v0: the stored per-tier ticks for one entity, or {@code null} when untracked. */
    public TierTicks tierTicks(long entityId) {
        ChunkMergerCore.TierTicksView view = core.tierTicks(entityId);
        return view == null ? null : toTierTicks(view);
    }

    /** Fork-A v0: every tracked entity's stored per-tier ticks, ascending entityId. */
    public List<TierTicks> allTierTicks() {
        List<ChunkMergerCore.TierTicksView> views = core.allTierTicks();
        List<TierTicks> out = new ArrayList<>(views.size());
        for (ChunkMergerCore.TierTicksView view : views) {
            out.add(toTierTicks(view));
        }
        return out;
    }

    // ------------------------------------------------------------------ adapter internals

    /** The kernel's projection record mapped 1:1 onto the client's public {@link TierTicks}. */
    private static TierTicks toTierTicks(ChunkMergerCore.TierTicksView v) {
        return new TierTicks(v.entityId(), v.coreTick(), v.fluxTick(), v.mountTickMin(),
                v.mountTickMax(), v.allegianceTick(), v.engineTick(), v.maneuverTick(),
                v.systemTick(), v.cloakTick(), v.minTick(), v.maxTick());
    }

    /** The kernel scalar-payload row for one pose: the POSE OBJECT fills every ordinal (the
     * pose carries all 7 tiers' fields incl. masked-absent sentinels — see class doc). */
    private static Object[] scalarsFor(ServerEntityPose pose) {
        Object[] scalars = new Object[ChunkMergerCore.SCALAR_TIER_COUNT];
        Arrays.fill(scalars, pose);
        return scalars;
    }

    /** One resolved AWSD state into the kernel at the frame tick (anchor+delta = complete
     * knowledge; cloak asserted by RECORD KIND inside {@code foldAwsd} — the twin law). */
    private void foldAwsdPose(long entityId, AwsdSnapshotReader.AwsdKeyframe state, long tick,
            boolean keyframe) {
        ServerEntityPose pose = AwsdSnapshotReader.resolvePose(state);
        core.foldAwsd(entityId, tick, keyframe, pose, scalarsFor(pose), pose.weaponMounts());
    }

    /**
     * Synthesize the whole-world merge view: every tracked entity in ascending-id order,
     * stamped with the max stored tick (the kernel updates the spread + cloak-age meters as
     * a publish side effect). An empty store publishes the empty view at the frame tick
     * (a marked frame with no entities — server-invalid per the anchor law).
     */
    private ServerSnapshotFrame publishView(long frameTick) {
        List<ServerEntityPose> merged = new ArrayList<>(core.trackedEntities());
        long mixedBefore = core.mixedTickMax();
        long viewTick = core.publish(frameTick, (id, cell) -> merged.add(synthesize(cell)));
        if (core.mixedTickMax() > mixedBefore) {
            mixedTickMaxTick = frameTick; // (38)(a): the publish that minted the new max
        }
        noteCapDrops();
        return new ServerSnapshotFrame(viewTick, merged, List.of(), beams, missiles, dictEntries);
    }

    /**
     * One cell's published pose: the stored core pose with every scalar tier's fields
     * overridden from that tier's stored pose ref + the merged mount list. A {@code null}
     * scalar slot is a never-asserted tier (fragment-created cell): synthesize the pose
     * UNKNOWN_* sentinels / defaults — exactly the old virgin-Cell values, so the
     * authoritative-EMPTY vs never-asserted distinction publishes identically to the
     * pre-extraction per-field store.
     */
    private ServerEntityPose synthesize(
            ChunkMergerCore.Cell<ServerWeaponMountState> cell) {
        ServerEntityPose base = (ServerEntityPose) cell.core();
        Collection<ChunkMergerCore.MountSlot<ServerWeaponMountState>> slots = cell.mounts();
        List<ServerWeaponMountState> mounts;
        if (slots.isEmpty()) {
            mounts = java.util.Collections.emptyList();
        } else {
            mounts = new ArrayList<>(slots.size());
            for (ChunkMergerCore.MountSlot<ServerWeaponMountState> slot : slots) {
                mounts.add(slot.mount());
            }
        }
        ServerEntityPose flux = (ServerEntityPose) cell.scalar(ChunkMergerCore.I_FLUX);
        ServerEntityPose alleg = (ServerEntityPose) cell.scalar(ChunkMergerCore.I_ALLEGIANCE);
        ServerEntityPose engine = (ServerEntityPose) cell.scalar(ChunkMergerCore.I_ENGINE);
        ServerEntityPose maneuver = (ServerEntityPose) cell.scalar(ChunkMergerCore.I_MANEUVER);
        ServerEntityPose system = (ServerEntityPose) cell.scalar(ChunkMergerCore.I_SYSTEM);
        ServerEntityPose cloak = (ServerEntityPose) cell.scalar(ChunkMergerCore.I_CLOAK);
        ServerEntityPose cr = (ServerEntityPose) cell.scalar(ChunkMergerCore.I_CR);
        // Armor rides its own tier (asserted only by AWSD folds — see
        // ChunkMergerCore.TIER_ARMOR_LOCAL), so an AWSS core fold no longer strips it from
        // the published pose. A re-stamp over an armor-less core is the counted mechanism.
        ServerEntityPose armor = (ServerEntityPose) cell.scalar(ChunkMergerCore.I_ARMOR);
        boolean armorCarried = armor != null && armor.hasArmor();
        if (armorCarried && !base.hasArmor()) {
            armorRestamps++;
        }
        return base.toBuilder()
                .armorFrac(armorCarried ? armor.armorFrac() : ServerEntityPose.UNKNOWN_ARMOR)
                .fleetSide(alleg == null
                        ? ServerEntityPose.UNKNOWN_FLEET_SIDE : alleg.fleetSide())
                .currFlux(flux == null ? ServerEntityPose.UNKNOWN_FLUX : flux.currFlux())
                .maxFlux(flux == null ? ServerEntityPose.UNKNOWN_FLUX : flux.maxFlux())
                .fluxLevel(flux == null ? ServerEntityPose.UNKNOWN_FLUX : flux.fluxLevel())
                .hardFlux(flux == null ? ServerEntityPose.UNKNOWN_FLUX : flux.hardFlux())
                .fluxFlags(flux == null
                        ? ServerEntityPose.UNKNOWN_FLUX_FLAGS : flux.fluxFlags())
                .shieldFacingU8(flux == null
                        ? ServerEntityPose.UNKNOWN_SHIELD_FACING : flux.shieldFacingU8())
                .engineDisabledMask(engine == null ? 0 : engine.engineDisabledMask())
                .enginePermDisabledMask(engine == null ? 0 : engine.enginePermDisabledMask())
                .engineControllerState(engine == null
                        ? ServerEntityPose.UNKNOWN_ENGINE_STATE
                        : engine.engineControllerState())
                .maneuverCommandMask(maneuver == null
                        ? ServerEntityPose.UNKNOWN_MANEUVER_MASK
                        : maneuver.maneuverCommandMask())
                .weaponMounts(mounts)
                .systemStateCode(system == null
                        ? ServerEntityPose.UNKNOWN_SYSTEM_STATE : system.systemStateCode())
                .systemEffectLevel(system == null ? 0f : system.systemEffectLevel())
                .systemCooldownRemaining(system == null ? 0f : system.systemCooldownRemaining())
                .systemAmmo(system == null ? -1 : system.systemAmmo())
                .cloakStateCode(cloak == null
                        ? ServerEntityPose.UNKNOWN_PHASE_CLOAK_STATE : cloak.cloakStateCode())
                .cloakEffectLevel(cloak == null ? 0f : cloak.cloakEffectLevel())
                .cloakCooldownRemaining(cloak == null ? 0f : cloak.cloakCooldownRemaining())
                .cloakAmmo(cloak == null ? -1 : cloak.cloakAmmo())
                // The kernel's cloak-tier assert tick rides the pose so
                // the foe-cloak lane can tell a FRESH keyframe value from a held one (the v10
                // cloak block is keyframe-only; NEVER_ASSERTED == UNKNOWN_CLOAK_TIER_TICK).
                .cloakTierTick(cell.scalarTick(ChunkMergerCore.I_CLOAK))
                .cr(cr == null ? ServerEntityPose.UNKNOWN_CR : cr.cr())
                .crDepSec(cr == null ? 0f : cr.crDepSec())
                .crPeakSec(cr == null ? 0f : cr.crPeakSec())
                .build();
    }
}
