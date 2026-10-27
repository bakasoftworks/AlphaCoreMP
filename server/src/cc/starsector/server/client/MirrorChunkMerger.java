package cc.starsector.server.client;

import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ShipAllegianceSnapshot;
import cc.starsector.server.protocol.ShipCrSnapshot;
import cc.starsector.server.protocol.ShipEngineStateSnapshot;
import cc.starsector.server.protocol.ShipFluxSnapshot;
import cc.starsector.server.protocol.ShipFragmentMarkerSnapshot;
import cc.starsector.server.protocol.ShipManeuverCommandSnapshot;
import cc.starsector.server.protocol.ShipPhaseCloakStateSnapshot;
import cc.starsector.server.protocol.ShipSystemStateSnapshot;
import cc.starsector.server.protocol.ShipWeaponMountSnapshot;
import cc.starsector.server.runtime.AwsdEntityBaseline;
import cc.starsector.server.runtime.AwsdNackFrameCodec;
import cc.starsector.server.runtime.AwsdSnapshotCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The server-tree ADAPTER of the shared {@link ChunkMergerCore} reassembly law (
 * the source-sharing extraction; until then this class was a full SECOND
 * implementation of the client-mod {@code SnapshotChunkMerger} rules, kept equivalent only
 * by convention + the gate). The law — adopt/covering/merge, the ordering
 * guard, per-tier {@code tick >=} upserts, ABSENT &ne; EMPTY, the anchor/mount-range laws,
 * the record-KIND cloak twin law, the sibling anchor-map law, and the NACK throttle
 * — lives in the kernel; this class contributes only the server-tree DECODE VOCABULARY
 * (protocol records looked up per entity, {@code AwsdSnapshotCodec} resolution) and VIEW
 * SYNTHESIS (the stored {@code cell.core} objects — the ghost seat never re-synthesizes
 * poses; tier records stay in kernel scalar slots, read by the equivalence gate's
 * normalized {@link #projectStore projection}). Extracted whole from
 * {@link PilotIntentDatagramClient} so the merger-equivalence gate can drive
 * it directly.
 *
 * <p>The formerly documented FORM divergences from the client twin are now visible
 * ctor/callsite parameters of the shared kernel: the AWSD stale-keyframe gauge compares the
 * record's embedded anchorTick ({@code AwsdEntityBaseline::anchorTick} + the
 * {@code adoptKeyframe} callsite; the client passes the arrival frame tick — equivalent on
 * encodable wire, {@code AwsdSnapshotCodec} pins anchorTick = the minting frame's simTick);
 * there is no NACK id guard ({@code nackIdMax} = Long.MAX_VALUE — the server AWSD encode
 * already refuses wide ids); stale-keyframe and epoch/anchor delta drops publish as ONE
 * combined {@link #awsdDropped} counter (the receipt vocabulary — the kernel counts them
 * split, this adapter sums).
 *
 * <p>Threading = the owning client's: store methods ({@link #onFrame}, {@link #onAwsdFrame},
 * {@link #evict}, {@link #clear}) are reader-thread-only; the NACK queue is deposited on the
 * reader thread and {@link #drainPendingNacks drained} on the intent-pump thread under the
 * kernel's internal lock. Kernel meters are volatile: written by the reader thread, read for
 * the receipt from the main thread.
 */
public final class MirrorChunkMerger {

    /** Tier-tick sentinel: no frame has ever asserted this tier for the entity. */
    static final long NEVER_ASSERTED = ChunkMergerCore.NEVER_ASSERTED;

    static {
        // Fail-fast at class load: the kernel's canonical tier bits must be
        // numerically EQUAL to this tree's wire marker bits — the fold laws mask by the
        // kernel constants while markers arrive in ShipFragmentMarkerSnapshot vocabulary.
        if (ChunkMergerCore.TIER_FLUX != ShipFragmentMarkerSnapshot.TIER_FLUX
                || ChunkMergerCore.TIER_WEAPON_MOUNT != ShipFragmentMarkerSnapshot.TIER_WEAPON_MOUNT
                || ChunkMergerCore.TIER_ALLEGIANCE != ShipFragmentMarkerSnapshot.TIER_ALLEGIANCE
                || ChunkMergerCore.TIER_ENGINE_STATE != ShipFragmentMarkerSnapshot.TIER_ENGINE_STATE
                || ChunkMergerCore.TIER_MANEUVER != ShipFragmentMarkerSnapshot.TIER_MANEUVER
                || ChunkMergerCore.TIER_SYSTEM_STATE != ShipFragmentMarkerSnapshot.TIER_SYSTEM_STATE
                || ChunkMergerCore.TIER_MASK_BITS != ShipFragmentMarkerSnapshot.TIER_MASK_BITS) {
            throw new IllegalStateException(
                    "ChunkMergerCore tier bits diverge from ShipFragmentMarkerSnapshot");
        }
    }

    /** The view to publish: entities ascending by id (or the frame's own list on the
     * identity path) + the view tick (frame tick on identity, max stored as-of on merge). */
    public record MergedView(List<AuthoritativeEntitySnapshot> entities, long viewTick) {
    }

    /** AWSD fold outcome: the merged view + the resolved arriving record for the own ship
     * ({@code null} when the frame carried none that survived the drop guards) — the owning
     * client keys its movement observation to the ARRIVING record, not the stored view. */
    public record AwsdView(MergedView view, AuthoritativeEntitySnapshot ownArrived) {
    }

    /** equivalence-gate projection: one entity's stored tier records + the tick
     * each was asserted on ({@code null} record = authoritative EMPTY when its tick is
     * asserted, never-asserted when the tick is {@link #NEVER_ASSERTED}); mounts ascending
     * by index. Read-only — the gate compares this against the client merger's published
     * pose values paired with its {@code tierTicks()} ages. */
    public record CellProjection(long entityId,
            AuthoritativeEntitySnapshot core, long coreTick,
            ShipFluxSnapshot flux, long fluxTick,
            ShipAllegianceSnapshot allegiance, long allegianceTick,
            ShipEngineStateSnapshot engine, long engineTick,
            ShipManeuverCommandSnapshot maneuver, long maneuverTick,
            ShipSystemStateSnapshot system, long systemTick,
            ShipPhaseCloakStateSnapshot phaseCloak, long phaseCloakTick,
            ShipCrSnapshot crState, long crTick,
            List<MountProjection> mounts) {
    }

    /** One stored mount + its arrival tick (ascending mountIndex in the cell projection). */
    public record MountProjection(ShipWeaponMountSnapshot mount, long tick) {
    }

    /** One entity's stored SYSTEM-tier record + the tick it was asserted
     * on ({@code null} record = authoritative EMPTY when the tick is asserted, or
     * never-asserted/untracked). Read-only, single-cell — the offline press-audit harness
     * samples this per published view without materializing {@link #projectStore}. */
    public record SystemProjection(ShipSystemStateSnapshot system, long systemTick) {
    }

    /** The shared law kernel: anchor payload = the codec baseline, mount
     * payload = the wire mount record; anchorTick gauge + no-NACK-guard are this tree's
     * documented FORM choices (see class doc). */
    private final ChunkMergerCore<AwsdEntityBaseline, ShipWeaponMountSnapshot> core =
            new ChunkMergerCore<>(AwsdEntityBaseline::anchorTick, AwsdEntityBaseline::epoch,
                    ShipWeaponMountSnapshot::mountIndex, Long.MAX_VALUE);

    /**
     * Fold one decoded AWSS frame in and return the view to publish: the frame's own entity
     * list on the identity (adopt/covering) path — marker-free, not older-than-newest, and
     * empty-store-or-covering — or the synthesized store view on the merge path. Rules =
     * the shared kernel's: a marked frame is NEVER adopted or covering; an
     * unmarked pose asserts whole-entity authority; a marked pose asserts ONLY its masked
     * tiers (ABSENT &ne; EMPTY — masked-with-no-record clears, unmasked holds; mounts
     * range-bounded; the ship core rides every fragment, anchor law).
     */
    public MergedView onFrame(AuthoritativeWorldSnapshot snap) {
        long tick = snap.simTick();
        ChunkMergerCore.FrameKind kind = core.beginAwssFrame(tick,
                snap.fragmentMarkers().isEmpty(), id -> frameCarries(snap, id));
        if (kind == ChunkMergerCore.FrameKind.IDENTITY) {
            for (AuthoritativeEntitySnapshot entity : snap.entities()) {
                core.foldWhole(entity.entityId(), tick, entity,
                        scalarsFor(snap, entity.entityId()),
                        mountsFor(snap, entity.entityId()));
            }
            return new MergedView(snap.entities(), tick);
        }
        Map<Long, ShipFragmentMarkerSnapshot> markerById;
        if (snap.fragmentMarkers().isEmpty()) {
            markerById = java.util.Collections.emptyMap();
        } else {
            markerById = new HashMap<>();
            for (ShipFragmentMarkerSnapshot marker : snap.fragmentMarkers()) {
                // Anchor law: the marked entity's ship record is in this frame, so the
                // fold below reaches it via its pose; a marker without a pose (a server-
                // side invariant violation) asserts nothing here — fail-safe hold.
                markerById.put(marker.entityId(), marker);
            }
        }
        for (AuthoritativeEntitySnapshot entity : snap.entities()) {
            long entityId = entity.entityId();
            ShipFragmentMarkerSnapshot marker = markerById.get(entityId);
            if (marker == null) {
                core.foldWhole(entityId, tick, entity,
                        scalarsFor(snap, entityId), mountsFor(snap, entityId));
            } else {
                core.foldFragment(entityId, tick, marker.tierMask(), entity,
                        scalarsFor(snap, entityId), mountsFor(snap, entityId),
                        marker.mountFrom(), marker.mountTo());
            }
        }
        core.finishAwssMerge(!snap.fragmentMarkers().isEmpty());
        return synthesizeView(tick);
    }

    /**
     * AWSD (delta+keyframe) application — KEYFRAME: kernel stale-adopt
     * guard, then anchor stored + fold; DELTA: kernel anchor/epoch guards (drop + counter +
     * hold-last + NACK), apply vs the returned anchor (an applyDelta throw routes
     * through the kernel's {@code deltaFailed}), then fold ALL tiers at the frame tick
     * (anchor+delta = complete knowledge — the clean 5-B delta folds too; cloak by RECORD
     * KIND, the kernel twin law). Raises the ordering-guard high-water so a later stale
     * AWSS frame cannot adopt/cover-wipe newer AWSD state.
     */
    public AwsdView onAwsdFrame(AwsdSnapshotCodec.AwsdFrame frame, long ownShipId) {
        long tick = frame.simTick();
        AuthoritativeEntitySnapshot ownArrived = null;
        for (AwsdSnapshotCodec.AwsdRecord record : frame.records()) {
            long entityId = record.entityId();
            boolean keyframe = record.kind() == AwsdSnapshotCodec.KIND_KEYFRAME;
            AwsdEntityBaseline resolved;
            if (keyframe) {
                AwsdEntityBaseline candidate = record.keyframe();
                if (!core.adoptKeyframe(entityId, candidate.anchorTick(), candidate)) {
                    continue; // reordered stale keyframe — hold the newer anchor
                }
                resolved = candidate;
            } else {
                AwsdEntityBaseline anchor = core.beginDelta(entityId, record.epoch());
                if (anchor == null) {
                    continue; // no anchor / epoch mismatch — hold-last until the re-key
                }
                try {
                    resolved = AwsdSnapshotCodec.applyDelta(record, anchor);
                } catch (IllegalArgumentException drop) {
                    // D5 drop signal: roster-absent mount (epoch rides the kernel guard)
                    core.deltaFailed(entityId, record.epoch());
                    continue;
                }
            }
            foldResolved(entityId, resolved, tick, keyframe);
            if (entityId == ownShipId) {
                ownArrived = AwsdSnapshotCodec.resolvePose(resolved);
            }
        }
        core.finishAwsdFrame(tick);
        return new AwsdView(synthesizeView(tick), ownArrived);
    }

    /** The consumed EDSP despawn: under merge, store EVICTION is the death edge — the
     * entity's cell, AWSD anchor, and NACK state die together (server DEC mirror). */
    public void evict(long entityId) {
        core.evict(entityId);
    }

    /** The MRST round boundary: forget everything before the fresh round's first frame,
     * including the ordering-guard high-water (tick spaces do not compare across rounds). */
    public void clear() {
        core.clear();
    }

    /** Intent-pump drain: snapshot + clear pending under the kernel lock; encode/send
     * outside it. The once-per-(id,epoch) throttle SURVIVES the drain — only a fresh
     * keyframe adopt (or evict/clear) re-arms the id. */
    public List<AwsdNackFrameCodec.AwsdNack> drainPendingNacks() {
        List<ChunkMergerCore.NackEntry> drained = core.drainPendingNacks();
        if (drained.isEmpty()) {
            return List.of();
        }
        List<AwsdNackFrameCodec.AwsdNack> out = new ArrayList<>(drained.size());
        for (ChunkMergerCore.NackEntry entry : drained) {
            out.add(new AwsdNackFrameCodec.AwsdNack((int) entry.entityId(), entry.epoch()));
        }
        return out;
    }

    // ------------------------------------------------------------------ meters

    /** Frames that took the merge (non-covering) branch. */
    public long chunksMerged() {
        return core.chunksMerged();
    }

    /** Widest cross-entity spread of per-entity as-of ticks ever published. */
    public long mixedTickMax() {
        return core.mixedTickMax();
    }

    /** Merge-branch frames that carried at least one fragment marker. */
    public long fragmentsMerged() {
        return core.fragmentsMerged();
    }

    /** Widest NON-CLOAK tick spread ever seen WITHIN one entity (intra-entity tearing;
     * the by-design cloak keyframe-cadence lag lives in
     * {@link #cloakAgeMax}, not here). */
    public long fragTierSpreadMax() {
        return core.fragTierSpreadMax();
    }

    /** Widest {@code cellMax − cloakTick} ever published (the dedicated
     * cloak-staleness meter; expected bound = AWSD keyframe cadence − 1). */
    public long cloakAgeMax() {
        return core.cloakAgeMax();
    }

    /** Frames the ordering guard forced down the merge branch as older-than-newest
     * (0 on an in-order stream — the client-mod {@code staleForcedMerges} twin). */
    public long staleForcedMerges() {
        return core.staleForcedMerges();
    }

    /** AWSD keyframe records adopted (anchor stored). */
    public long awsdKeyframes() {
        return core.awsdKeyframes();
    }

    /** AWSD delta records resolved vs their anchor and folded. */
    public long awsdDeltas() {
        return core.awsdDeltas();
    }

    /** AWSD drops: stale keyframes + missing-anchor/epoch-mismatch deltas (one combined
     * counter — the receipt's {@code snap_awsd_dropped} vocabulary; the kernel counts them
     * split). */
    public long awsdDropped() {
        return core.awsdDroppedEpoch() + core.awsdDroppedStale();
    }

    // ------------------------------------------------------------------ projection

    /** The read-only store projection for the equivalence gate, ascending
     * entityId (the same order every published view uses). */
    public List<CellProjection> projectStore() {
        List<CellProjection> out = new ArrayList<>(core.trackedEntities());
        for (Map.Entry<Long, ChunkMergerCore.Cell<ShipWeaponMountSnapshot>> entry
                : core.cells()) {
            ChunkMergerCore.Cell<ShipWeaponMountSnapshot> cell = entry.getValue();
            List<MountProjection> mounts = new ArrayList<>(cell.mounts().size());
            for (ChunkMergerCore.MountSlot<ShipWeaponMountSnapshot> slot : cell.mounts()) {
                mounts.add(new MountProjection(slot.mount(), slot.tick()));
            }
            out.add(new CellProjection(entry.getKey().longValue(),
                    (AuthoritativeEntitySnapshot) cell.core(), cell.coreTick(),
                    (ShipFluxSnapshot) cell.scalar(ChunkMergerCore.I_FLUX),
                    cell.scalarTick(ChunkMergerCore.I_FLUX),
                    (ShipAllegianceSnapshot) cell.scalar(ChunkMergerCore.I_ALLEGIANCE),
                    cell.scalarTick(ChunkMergerCore.I_ALLEGIANCE),
                    (ShipEngineStateSnapshot) cell.scalar(ChunkMergerCore.I_ENGINE),
                    cell.scalarTick(ChunkMergerCore.I_ENGINE),
                    (ShipManeuverCommandSnapshot) cell.scalar(ChunkMergerCore.I_MANEUVER),
                    cell.scalarTick(ChunkMergerCore.I_MANEUVER),
                    (ShipSystemStateSnapshot) cell.scalar(ChunkMergerCore.I_SYSTEM),
                    cell.scalarTick(ChunkMergerCore.I_SYSTEM),
                    (ShipPhaseCloakStateSnapshot) cell.scalar(ChunkMergerCore.I_CLOAK),
                    cell.scalarTick(ChunkMergerCore.I_CLOAK),
                    (ShipCrSnapshot) cell.scalar(ChunkMergerCore.I_CR),
                    cell.scalarTick(ChunkMergerCore.I_CR),
                    mounts));
        }
        return out;
    }

    /** The single-entity SYSTEM-tier read behind
     * {@link SystemProjection} — {@code null} when the entity is untracked. */
    public SystemProjection systemFor(long entityId) {
        for (Map.Entry<Long, ChunkMergerCore.Cell<ShipWeaponMountSnapshot>> entry
                : core.cells()) {
            if (entry.getKey().longValue() == entityId) {
                ChunkMergerCore.Cell<ShipWeaponMountSnapshot> cell = entry.getValue();
                return new SystemProjection(
                        (ShipSystemStateSnapshot) cell.scalar(ChunkMergerCore.I_SYSTEM),
                        cell.scalarTick(ChunkMergerCore.I_SYSTEM));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ internals

    /** The kernel scalar-payload row for one entity of an AWSS frame, ordinal order
     * ({@code I_FLUX..I_CLOAK}) — record-or-null per tier: a missing record IS the
     * authoritative EMPTY, so the kernel's verbatim copy needs no special case. */
    private static Object[] scalarsFor(AuthoritativeWorldSnapshot snap, long entityId) {
        Object[] scalars = new Object[ChunkMergerCore.SCALAR_TIER_COUNT];
        scalars[ChunkMergerCore.I_FLUX] =
                recordFor(snap.fluxStates(), ShipFluxSnapshot::entityId, entityId);
        scalars[ChunkMergerCore.I_ALLEGIANCE] =
                recordFor(snap.allegianceStates(), ShipAllegianceSnapshot::entityId, entityId);
        scalars[ChunkMergerCore.I_ENGINE] =
                recordFor(snap.engineStates(), ShipEngineStateSnapshot::entityId, entityId);
        scalars[ChunkMergerCore.I_MANEUVER] = recordFor(
                snap.maneuverCommands(), ShipManeuverCommandSnapshot::entityId, entityId);
        scalars[ChunkMergerCore.I_SYSTEM] =
                recordFor(snap.systemStates(), ShipSystemStateSnapshot::entityId, entityId);
        scalars[ChunkMergerCore.I_CLOAK] = recordFor(
                snap.phaseCloakStates(), ShipPhaseCloakStateSnapshot::entityId, entityId);
        scalars[ChunkMergerCore.I_CR] = recordFor(
                snap.crStates(), ShipCrSnapshot::entityId, entityId);
        return scalars;
    }

    /** The frame's mount records for one entity (the frame-global list pre-filtered by
     * entityId — the kernel bounds by mountIndex range). */
    private static List<ShipWeaponMountSnapshot> mountsFor(
            AuthoritativeWorldSnapshot snap, long entityId) {
        List<ShipWeaponMountSnapshot> out = new ArrayList<>();
        for (ShipWeaponMountSnapshot mount : snap.weaponMounts()) {
            if (mount.entityId() == entityId) {
                out.add(mount);
            }
        }
        return out;
    }

    /** Fold a resolved AWSD state (keyframe, or anchor+delta) into the
     * kernel — scalar payloads from the codec's resolvers ({@code resolvePhaseCloakOrNull}
     * / {@code resolveCrOrNull} fill the cloak/CR slots; the kernel masks both out for a
     * DELTA per the record-KIND twin law), mounts from {@code resolveMounts}. */
    private void foldResolved(long entityId, AwsdEntityBaseline state, long tick,
            boolean keyframe) {
        Object[] scalars = new Object[ChunkMergerCore.SCALAR_TIER_COUNT];
        scalars[ChunkMergerCore.I_FLUX] = AwsdSnapshotCodec.resolveFlux(state);
        scalars[ChunkMergerCore.I_ALLEGIANCE] = AwsdSnapshotCodec.resolveAllegiance(state);
        scalars[ChunkMergerCore.I_ENGINE] = AwsdSnapshotCodec.resolveEngineState(state);
        scalars[ChunkMergerCore.I_MANEUVER] = AwsdSnapshotCodec.resolveManeuver(state);
        // v8: the system slot fills like cloak — null on the 0xFF
        // SYS_NONE sentinel (armor modules carry no ship system).
        scalars[ChunkMergerCore.I_SYSTEM] = AwsdSnapshotCodec.resolveSystemStateOrNull(state);
        scalars[ChunkMergerCore.I_CLOAK] = AwsdSnapshotCodec.resolvePhaseCloakOrNull(state);
        // v6: the CR slot fills like cloak — null on the peak==0
        // absent sentinel; the kernel asserts I_CR on keyframes only (twin law).
        scalars[ChunkMergerCore.I_CR] = AwsdSnapshotCodec.resolveCrOrNull(state);
        AuthoritativeEntitySnapshot resolvedPose = AwsdSnapshotCodec.resolvePose(state);
        // The armor tier's payload = the resolved pose (armorLevel is a
        // pose field in this vocabulary, no per-tier armor record exists). The kernel
        // asserts it on BOTH kinds (dense law); nothing server-side consumes the slot yet —
        // it is filled for twin-law symmetry with the client adapter.
        scalars[ChunkMergerCore.I_ARMOR] = resolvedPose;
        core.foldAwsd(entityId, tick, keyframe, resolvedPose,
                scalars, AwsdSnapshotCodec.resolveMounts(state));
    }

    /** Merge-grade view synthesis: stored cores ascending by id, stamped with the max
     * stored as-of tick (the kernel updates the spread meters as a side effect). */
    private MergedView synthesizeView(long frameTick) {
        List<AuthoritativeEntitySnapshot> merged = new ArrayList<>(core.trackedEntities());
        long viewTick = core.publish(frameTick,
                (id, cell) -> merged.add((AuthoritativeEntitySnapshot) cell.core()));
        return new MergedView(merged, viewTick);
    }

    private static boolean frameCarries(AuthoritativeWorldSnapshot snap, long entityId) {
        for (AuthoritativeEntitySnapshot entity : snap.entities()) {
            if (entity.entityId() == entityId) {
                return true;
            }
        }
        return false;
    }

    /** First record with the entity's id, or {@code null} (frames are small; linear is fine). */
    private static <T> T recordFor(List<T> records, ToLongFunction<T> idOf, long entityId) {
        for (T record : records) {
            if (idOf.applyAsLong(record) == entityId) {
                return record;
            }
        }
        return null;
    }
}
