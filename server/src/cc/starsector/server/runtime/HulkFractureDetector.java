package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Server-side producer that derives explicit {@link HulkFractureFrameCodec.FractureEdge}s from
 * per-tick remnant sightings — the GPEE-detector idiom (a pure function of post-step
 * engine state; no engine patching). The authoritative engine splits corpses naturally
 * (untouched); this detector watches the repository's dead remnants, groups them into corpse
 * FAMILIES (all pieces of one corpse share the parent hull's engine id — {@code clone()} keeps
 * it), and emits an edge whenever a family gains or loses a piece.
 *
 * <p>Emission laws (each pinned by a ServerContractTests row):
 * <ul>
 *   <li>Families are keyed by the remnants' stable engine id; per-piece identity is the engine
 *       object REFERENCE (the views' positional {@code shipId} renumbers every tick — never
 *       track with it).</li>
 *   <li>{@code rootEntityId} is pinned at family creation via the injected resolver
 *       ({@code ShipNetworkIdRegistry.networkIdForOrNull}); an unresolved key skips the family
 *       (counter, never an intern) and retries naturally on the next sighting — a T4 remount
 *       RELEASES dead keys, so late resolution can genuinely fail.</li>
 *   <li>{@code deathTick} identifies the family on the wire (schema v2): the simTick of
 *       the walk that FIRST SIGHTED the corpse (within ~1-2 ticks of its EDSP death tick),
 *       stamped on every edge — one wire id can front two coexisting corpse families across a
 *       remount, and the client binds by NEAREST death tick, so a late joiner that missed
 *       earlier deaths desyncs nothing (v1's per-wire-id ordinal required full observation
 *       from tick 0 and is deleted).</li>
 *   <li>A new family's baseline is ONE piece (the corpse itself — death is EDSP's edge, never
 *       emitted here); a first sighting with {@code k} pieces emits {@code k-1} ADDs
 *       ({@code familyPieceCount} 2..k).</li>
 *   <li>Same-tick removals emit BEFORE adds; removal {@code sizeRank}s are computed against the
 *       PRE-tick piece set (bbox area, largest = 0) and emitted largest-rank-first so a
 *       sequential rank-indexed applier stays valid.</li>
 *   <li>A whole family vanishing emits {@code k}× REMOVE at {@code sizeRank} 0 with
 *       {@code familyPieceCount} k-1..0 — a self-consistent remove-largest cascade (the
 *       engine's ≤75-radius explode-remove applies to pieces under bombardment too).</li>
 *   <li>Every REMOVE reads the vanished piece's RETAINED ref through the injected
 *       {@code destroyedRefReader} — {@code wasDestroyed=true} (the engine's vaporize)
 *       emits {@code EDGE_KIND_PIECE_REMOVED_DESTROYED}; a fail-soft
 *       {@code false} read emits plain kind 2 (the quiet presentation, safe direction).</li>
 *   <li>Module-detach bit (schema v4): a family whose tick group carries a
 *       sighting observed MODULE-DETACHED (binding read: {@code getParentStation()} present,
 *       {@code getStationSlot()} null) emits ONE {@code ROOT_DETACHED} (kind 4) edge — latched
 *       per family, first sight or a late overkill transition, after that tick's fold edges;
 *       membership untouched. Plain corpses (no parent station) never emit one.</li>
 *   <li>{@link #reset()} at a round wrap clears all state and must never emit a remove
 *       flood; a {@code null} sightings read is NO observation (a fail-soft binding flap must
 *       not fabricate vanish edges).</li>
 *   <li>Pose keyframes (second product of the same observation): with a nonzero
 *       injected cadence, a family emits one {@link HulkPoseFrameCodec.PoseKeyframe} per piece
 *       in rank order (bbox area desc, the fold rank) whenever the family was CREATED this
 *       tick, emitted an EDGE this tick, or {@code simTick - lastPoseEmit >= cadence}. A
 *       NaN-pose piece skips with a counter (membership and pose are separate products);
 *       {@code lastPoseEmit} advances on fire regardless of skips; cadence 0 = OFF (the
 *       rollback lever) emits nothing. Keyframes queue in {@link #drainPoseKeyframes()},
 *       drained AFTER the same tick's edges onto the SAME ctrl spool (rank-race-free).</li>
 *   <li>Impulse burst (the contact-responsiveness commission; re-cut
 *       CHANGE-GATED): with a nonzero burst cadence, a tracked piece's
 *       velocity stepping off its LAST-EMITTED keyframe velocity beyond
 *       {@link #IMPULSE_VEL_EPS_US} (or angular velocity beyond
 *       {@link #IMPULSE_ANG_EPS_DEG_S}) is a contact impulse — hulks are ballistic, the
 *       engine applies collision response as its LAST advance phase, and this walk reads
 *       post-step state, so the Δv check detects the impulse the SAME tick with zero latency
 *       (read from the engine code: the only subscribable engine seam is collision-DAMAGE —
 *       contact-onset only, blind to zero-damage nudges and the per-frame separation push —
 *       so the poll is authoritative on the merits). While a deviation STANDS the family's
 *       due-check is the burst cadence, so the first post-quiet detection emits that tick and
 *       sustained contact rate-caps at the burst cadence; a keyframe re-bases the deviation, so
 *       a family that then coasts is SILENT until the periodic cadence is due — the asteroid
 *       lane's law ({@code AsteroidFieldServeLane}: dv vs the last EMITTED row). The former
 *       TIME window (~1 s of unconditional burst-cadence keyframes after a contact) is RETIRED:
 *       under change-gating it was inert for emission and its ~15 unchanged keyframes per
 *       contact were each a client hard adopt (a first second of visible snaps).
 *       Burst cadence 0 = the lane OFF (the rollback lever, the pre-burst law verbatim).</li>
 *   <li>Cut geometry (third product; parent-ID law reworked): with a
 *       non-null injected {@link HulkRingReader}, a fold ADD observed as EXACTLY one fresh
 *       ref captures when the family resolves an unambiguous PARENT — a 1-piece family's
 *       only sighted track IS the parent (NO renderOffset-moved requirement: the engine
 *       snaps both pieces' bbox centers to the armor-grid cell before deriving offsets,
 *       so an unmoved parent RO is the COMMON case on a real
 *       cut, and {@code c_i = prevRO − RO_i} stays exact at zero delta); a multi-piece
 *       family takes the unique RO-moved track, tiebroken by the unique track whose bbox
 *       area SHRANK this sweep (both channels read pre-refresh — the track's previous-tick
 *       RO is the parent's pre-split value). The capture reads both pieces'
 *       exact+visual rings one-shot, reconstructs them into the parent's pre-split local
 *       frame ({@code c_i = prevRO − RO_i}), derives the shared cut run (tol 0.01,
 *       pre-quantize), quantizes to the i16 1/64-u wire grid, and queues 4
 *       {@link HulkCutGeometryFrameCodec.CutGeometryRecord}s (pieceSlot × ringKind)
 *       in {@link #drainCutGeometryRecords()} — the serve loop drains those BEFORE the same
 *       tick's edges, so the FIFO ctrl spool delivers geometry ahead of the paired ADD. EVERY
 *       emitted PIECE_ADDED consumes a per-family {@code cutSeq} ordinal (captured or not —
 *       the client derives the same ordinal by counting applied ADDs); ANY capture failure
 *       counts one per-leg skip ({@link #cutGeometrySkipsByLeg()}) and queues one drainable
 *       {@link CutGeometrySkip} descriptor (for observability — the serve loop prints one
 *       INFO row per skip), and the edge fans bare (the client falls back to its local
 *       cut — capture is fail-soft end-to-end, never blocks the edge).</li>
 * </ul>
 *
 * <p>Stateful and single-threaded like {@link EntityDespawnEventDetector}: {@link #observe}
 * once per tick in order, {@link #drainEdges()} then {@link #drainPoseKeyframes()} from the
 * serve loop. Never throws on engine pathology — out-of-range ordinals (&gt;255 pieces or
 * death ordinals) skip with a counter rather than propagating into the serve loop.
 */
public final class HulkFractureDetector {

    /**
     * One per-tick remnant observation from the binding walk: the remnant's stable engine id
     * (pieces inherit the parent's — the family key), the engine object reference (piece
     * identity), its bbox area (the engine's own H cut-largest metric; fail-soft
     * collisionRadius² upstream), its six-float pose (the keyframe payload — all-NaN when
     * the pose read failed; per-piece fail-soft, never nulls the walk), and its renderOffset
     * (the engine's public {@code getRenderOffset()} — STATIC between splits, the exact
     * center-recovery channel {@code c_i = prevRO − RO_i}; NaN pair when the read failed,
     * which fail-softs the cut-geometry capture only, never membership).
     */
    public record RemnantSighting(String stableKey, Object ref, float boundsArea,
            float locX, float locY, float facing, float velX, float velY, float angVel,
            float renderOffX, float renderOffY, boolean moduleDetached) {
        public RemnantSighting {
            Objects.requireNonNull(stableKey, "stableKey");
            Objects.requireNonNull(ref, "ref");
        }

        /** Detach-bit-less sighting (the pre-v4 shape): moduleDetached false —
         * the fail-soft direction (no detach edge = today's behavior). */
        public RemnantSighting(String stableKey, Object ref, float boundsArea,
                float locX, float locY, float facing, float velX, float velY, float angVel,
                float renderOffX, float renderOffY) {
            this(stableKey, ref, boundsArea, locX, locY, facing, velX, velY, angVel,
                    renderOffX, renderOffY, false);
        }

        /** Membership-only sighting (pose + renderOffset unread): all components NaN. */
        public RemnantSighting(String stableKey, Object ref, float boundsArea) {
            this(stableKey, ref, boundsArea,
                    Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                    Float.NaN, Float.NaN);
        }

        /** Membership-only sighting carrying the module-detach observation. */
        public RemnantSighting(String stableKey, Object ref, float boundsArea,
                boolean moduleDetached) {
            this(stableKey, ref, boundsArea,
                    Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                    Float.NaN, Float.NaN, moduleDetached);
        }

        /** Pose-carrying sighting with the renderOffset unread (cut capture disabled). */
        public RemnantSighting(String stableKey, Object ref, float boundsArea,
                float locX, float locY, float facing, float velX, float velY, float angVel) {
            this(stableKey, ref, boundsArea, locX, locY, facing, velX, velY, angVel,
                    Float.NaN, Float.NaN);
        }
    }

    /**
     * Injected ring reader: one remnant's {exact, visual} bound rings as flat x,y float
     * arrays (the raw {@code origSegments} walk — seed p1 then every p2 — possibly CLOSED;
     * {@link #openRing} strips the closing duplicate), or {@code null} on ANY failure
     * (fail-soft: the capture skips with a counter, the edge fans bare).
     */
    public interface HulkRingReader {
        float[][] ringsOrNull(Object shipRef);
    }

    /**
     * Observability: one skipped cut-geometry capture — the leg that failed
     * plus the gate context ({@code freshCount}/{@code movedCount} from the fold pre-scan;
     * exact-ring point counts, {@code -1} where the capture failed before the ring read).
     * Queued per skip and drained by the serve loop into one INFO row each (the gap:
     * the aggregate counter printed nowhere and cost the shape-divergence diagnosis a
     * guess).
     */
    public record CutGeometrySkip(long simTick, long rootEntityId, long deathTick, String leg,
            int freshCount, int movedCount, int parentPoints, int clonePoints) {
        public CutGeometrySkip {
            Objects.requireNonNull(leg, "leg");
        }
    }

    /** Skip leg: more than one fresh ref this tick (a 1→3 same-tick split is ambiguous). */
    public static final String SKIP_LEG_MULTI_FRESH = "multi_fresh";
    /** Skip leg: no unambiguous parent (multi-piece with no unique moved/shrunk track,
     * or a 1-piece family whose only track was not sighted this tick). */
    public static final String SKIP_LEG_NO_PARENT = "no_parent";
    /** Skip leg: the ADD's cutSeq ordinal overflowed the u8 wire field. */
    public static final String SKIP_LEG_ORDINAL = "ordinal";
    /** Skip leg: the parent's renderOffset channel (previous-tick or current) is NaN. */
    public static final String SKIP_LEG_PARENT_RO_NAN = "parent_ro_nan";
    /** Skip leg: the fresh piece's renderOffset read failed (NaN). */
    public static final String SKIP_LEG_CLONE_RO_NAN = "clone_ro_nan";
    /** Skip leg: a ring read returned null or the wrong shape. */
    public static final String SKIP_LEG_RING_READ = "ring_read";
    /** Skip leg: an opened ring was degenerate or outside the wire point bounds. */
    public static final String SKIP_LEG_RING_BOUNDS = "ring_bounds";
    /** Skip leg: the shared cut run failed to derive (no single consecutive run). */
    public static final String SKIP_LEG_CUT_RUN = "cut_run";
    /** Skip leg: quantization overflowed the i16 1/64-u wire grid (or hit NaN). */
    public static final String SKIP_LEG_QUANTIZE = "quantize";
    /** Skip leg: record validation rejected the assembled capture (last fail-closed net). */
    public static final String SKIP_LEG_RECORD = "record";

    private static final class PieceTrack {
        final Object ref;
        float lastArea;
        float locX;
        float locY;
        float facing;
        float velX;
        float velY;
        float angVel;
        float renderOffX;
        float renderOffY;
        // The velocity this piece's LAST emitted keyframe carried — the impulse
        // trigger's baseline (NaN until the first keyframe; a NaN-skipped emit leaves it
        // stale, so a real Δv keeps re-triggering until a keyframe actually goes out).
        float lastKfVelX = Float.NaN;
        float lastKfVelY = Float.NaN;
        float lastKfAngVel = Float.NaN;

        PieceTrack(RemnantSighting sighting) {
            this.ref = sighting.ref();
            refresh(sighting);
        }

        /** Per-observe refresh: rank input + the latest pose (keyframes read these) +
         * renderOffset (the capture compares a sighting against the PRE-refresh value:
         * the previous tick's RO is the parent's pre-split offset). */
        void refresh(RemnantSighting sighting) {
            this.lastArea = sighting.boundsArea();
            this.locX = sighting.locX();
            this.locY = sighting.locY();
            this.facing = sighting.facing();
            this.velX = sighting.velX();
            this.velY = sighting.velY();
            this.angVel = sighting.angVel();
            this.renderOffX = sighting.renderOffX();
            this.renderOffY = sighting.renderOffY();
        }

        /** True when all six pose components are finite (keyframe-eligible). */
        boolean poseFinite() {
            return Float.isFinite(locX) && Float.isFinite(locY) && Float.isFinite(facing)
                    && Float.isFinite(velX) && Float.isFinite(velY) && Float.isFinite(angVel);
        }
    }

    private static final class Family {
        final long rootEntityId;
        final long deathTick; // firstSightTick — the family's wire identity (schema v2)
        final List<PieceTrack> pieces = new ArrayList<>();
        long lastPoseEmit; // The tick of the last keyframe fire (init = creation tick)
        int cutSeq; // per-family PIECE_ADDED ordinal — EVERY emitted ADD consumes one
        // ONE ROOT_DETACHED edge per family — latched at the first
        // sighting that reads module-detached (first sight or a late overkill transition).
        boolean rootDetachEmitted;

        Family(long rootEntityId, long deathTick) {
            this.rootEntityId = rootEntityId;
            this.deathTick = deathTick;
            this.lastPoseEmit = deathTick;
        }
    }

    /** Impulse sizing: a ballistic server piece's velocity is byte-constant between
     * contacts (sim-ballistic residual p99 = 0.01u, v constant across whole families),
     * so the linear eps needs only to clear float noise while staying far under the smallest
     * measured real contact (6.8 u/s in the contact census); the angular twin catches a
     * spin-dominant glance (census: every angVel outlier >5°/s was a contact event). */
    public static final float IMPULSE_VEL_EPS_US = 0.5f;
    public static final float IMPULSE_ANG_EPS_DEG_S = 2f;

    private final Function<String, Long> wireIdResolver;
    private final java.util.function.Predicate<Object> destroyedRefReader;
    private final int poseKeyframeEveryTicks;
    private final int burstCadenceTicks;
    private final HulkRingReader ringReader;
    private final Map<String, Family> familiesByKey = new LinkedHashMap<>();
    private final List<HulkFractureFrameCodec.FractureEdge> pending = new ArrayList<>();
    private final List<HulkPoseFrameCodec.PoseKeyframe> pendingPose = new ArrayList<>();
    private final List<HulkCutGeometryFrameCodec.CutGeometryRecord> pendingCutGeometry =
            new ArrayList<>();
    private long unresolvedFamilySkips;
    private long ordinalOverflowSkips;
    private long poseNanSkips;
    private long impulseTriggers;
    private long burstEmits;
    private long cutGeometryCaptureSkips;
    // per-leg reason counters (detector-lifetime, like every counter above) + the
    // pending skip descriptors the serve loop drains into INFO rows.
    private final Map<String, Long> cutGeometrySkipsByLeg = new LinkedHashMap<>();
    private final List<CutGeometrySkip> pendingCutGeometrySkips = new ArrayList<>();

    /** {@link #HulkFractureDetector(Function, java.util.function.Predicate, int, HulkRingReader)}
     * with the cut-geometry capture OFF (null ring reader). */
    public HulkFractureDetector(Function<String, Long> wireIdResolver,
            java.util.function.Predicate<Object> destroyedRefReader, int poseKeyframeEveryTicks) {
        this(wireIdResolver, destroyedRefReader, poseKeyframeEveryTicks, null);
    }

    /**
     * @param wireIdResolver stable engine id → wire entity id, or null when the id was never
     *        interned (or its dead key was released by a remount) — the family is skipped.
     * @param destroyedRefReader reads the retained engine ref's {@code wasDestroyed} at a
     *        vanish observation — {@code true} = the engine vaporized the piece, the REMOVE
     *        emits {@code EDGE_KIND_PIECE_REMOVED_DESTROYED}; the reader must be fail-soft
     *        {@code false} (kind 2 = the quiet presentation, the safe failure direction). The
     *        live binding passes {@code HulkDestroyedFlagReader::wasDestroyed}.
     * @param poseKeyframeEveryTicks ticks between periodic per-family pose keyframes
     *        (creation/edge ticks always emit); 0 = OFF, no keyframes ever queue — the rollback
     *        lever AND the safe default for lanes that never drain them.
     * @param ringReader one-shot exact+visual ring reads at a captured cut; {@code null}
     *        = capture OFF entirely (no records, no counters) — the mint default, so no
     *        records ever queue for lanes that never drain them (the pose-cadence precedent).
     *        The live binding passes its {@code hulkRingsOrNull} walk.
     */
    public HulkFractureDetector(Function<String, Long> wireIdResolver,
            java.util.function.Predicate<Object> destroyedRefReader, int poseKeyframeEveryTicks,
            HulkRingReader ringReader) {
        this(wireIdResolver, destroyedRefReader, poseKeyframeEveryTicks, ringReader, 0);
    }

    /**
     * {@link #HulkFractureDetector(Function, java.util.function.Predicate, int, HulkRingReader)}
     * plus the impulse burst cadence (the window parameter retired — class doc):
     *
     * @param poseBurstCadenceTicks the due-check while a family carries a standing contact
     *        deviation vs its last emitted keyframes (the first post-quiet detection emits that
     *        tick; sustained contact rate-caps here; a detection inside the cap gap coalesces
     *        onto the next cadence tick); 0 = the impulse lane OFF (the rollback lever — the
     *        periodic cadence alone, the pre-burst law verbatim); negative = a caller bug, throws.
     */
    public HulkFractureDetector(Function<String, Long> wireIdResolver,
            java.util.function.Predicate<Object> destroyedRefReader, int poseKeyframeEveryTicks,
            HulkRingReader ringReader, int poseBurstCadenceTicks) {
        this.wireIdResolver = Objects.requireNonNull(wireIdResolver, "wireIdResolver");
        this.destroyedRefReader = Objects.requireNonNull(destroyedRefReader, "destroyedRefReader");
        if (poseKeyframeEveryTicks < 0) {
            throw new IllegalArgumentException("poseKeyframeEveryTicks must be >= 0 (0 = OFF);"
                    + " got " + poseKeyframeEveryTicks);
        }
        if (poseBurstCadenceTicks < 0) {
            throw new IllegalArgumentException("poseBurstCadenceTicks must be >= 0 (0 = OFF);"
                    + " got " + poseBurstCadenceTicks);
        }
        this.poseKeyframeEveryTicks = poseKeyframeEveryTicks;
        this.burstCadenceTicks = poseBurstCadenceTicks;
        this.ringReader = ringReader;
    }

    /** Kind for one vanished piece — read the RETAINED ref at the vanish observation
     * (the engine sets {@code wasDestroyed} and removes the piece inside one damage
     * application, so the piece's last sighting always precedes the write; only the retained
     * ref carries the flag). Reader contract is fail-soft {@code false} ⇒ kind 2. */
    private int removeKindFor(Object ref) {
        return destroyedRefReader.test(ref)
                ? HulkFractureFrameCodec.EDGE_KIND_PIECE_REMOVED_DESTROYED
                : HulkFractureFrameCodec.EDGE_KIND_PIECE_REMOVED;
    }

    /**
     * Fold one tick's remnant sightings. {@code null} = the binding read failed this tick —
     * no observation at all (tracked families are retained, nothing emitted).
     */
    public void observe(long simTick, List<RemnantSighting> sightings) {
        familyRootsBornLastObserve.clear();
        if (sightings == null) {
            return;
        }
        Map<String, List<RemnantSighting>> byKey = new LinkedHashMap<>();
        for (RemnantSighting sighting : sightings) {
            byKey.computeIfAbsent(sighting.stableKey(), k -> new ArrayList<>()).add(sighting);
        }
        // Whole-family vanish first (removals precede adds globally as well as per family).
        Iterator<Map.Entry<String, Family>> tracked = familiesByKey.entrySet().iterator();
        while (tracked.hasNext()) {
            Map.Entry<String, Family> entry = tracked.next();
            if (byKey.containsKey(entry.getKey())) {
                continue;
            }
            Family family = entry.getValue();
            // Rank the vanished pieces (bbox area desc): cascade edge i removes the largest
            // remaining = ranked[i] — the piece whose retained ref the kind read targets.
            List<PieceTrack> ranked = new ArrayList<>(family.pieces);
            ranked.sort((a, b) -> Float.compare(b.lastArea, a.lastArea));
            for (int i = 0; i < ranked.size(); i++) {
                pending.add(new HulkFractureFrameCodec.FractureEdge(
                        simTick, family.rootEntityId, family.deathTick,
                        removeKindFor(ranked.get(i).ref), 0, ranked.size() - 1 - i));
            }
            tracked.remove();
        }
        for (Map.Entry<String, List<RemnantSighting>> entry : byKey.entrySet()) {
            Family family = familiesByKey.get(entry.getKey());
            boolean created = false;
            int edgesBefore = pending.size();
            if (family == null) {
                createFamily(simTick, entry.getKey(), entry.getValue());
                family = familiesByKey.get(entry.getKey());
                created = family != null; // unresolved/overflow skips mint no family
            } else {
                foldFamily(simTick, family, entry.getValue());
            }
            if (family != null) {
                // Any sighting reading module-detached latches the family's
                // ONE kind-4 ROOT_DETACHED edge (membership untouched; emitted after this
                // tick's fold edges). Trailing the fold ALSO forces a pose-keyframe set below
                // (edge-emitted leg), so the client gets fresh drift pose the same tick.
                if (!family.rootDetachEmitted && anyModuleDetached(entry.getValue())) {
                    family.rootDetachEmitted = true;
                    pending.add(new HulkFractureFrameCodec.FractureEdge(
                            simTick, family.rootEntityId, family.deathTick,
                            HulkFractureFrameCodec.EDGE_KIND_ROOT_DETACHED, 0,
                            family.pieces.size()));
                }
                // Emit law: created ∨ edge-emitted ∨ cadence due. The keyframes queue after
                // this tick's edges by construction (drained second onto the same ctrl spool).
                maybeEmitPoseKeyframes(simTick, family, created || pending.size() > edgesBefore);
            }
        }
    }

    /**
     * Queue one pose keyframe per piece, in rank order (bbox area desc — the fold
     * rank), when forced (creation/edge tick) or the cadence is due. NaN-pose pieces skip with
     * a counter; {@code lastPoseEmit} advances on fire regardless of skips (a family whose
     * every pose read fails must not retry at full tick rate); cadence 0 = OFF. The impulse burst as
     * re-cut (the asteroid law — class doc bullet): a standing contact
     * deviation (Δv vs the last EMITTED keyframe) makes the burst cadence THIS tick's due-check
     * — the first post-quiet detection emits this tick (its gap is ≥ the burst cadence in all
     * but back-to-back-emit corners), sustained contact rate-caps here, and because the
     * deviation persists until a keyframe goes out, a detection inside the cap gap coalesces
     * onto the next burst-cadence tick. The emitted keyframe re-bases the deviation, so a
     * coasting family is silent until the periodic cadence is due. The burst cadence never
     * DELAYS a due periodic emit ({@code min}; never add latency).
     */
    private void maybeEmitPoseKeyframes(long simTick, Family family, boolean forceEmit) {
        if (poseKeyframeEveryTicks <= 0) {
            return;
        }
        boolean impulse = burstCadenceTicks > 0 && hasImpulseSinceLastKeyframe(family);
        if (impulse) {
            impulseTriggers++;
        }
        int cadence = impulse
                ? Math.min(burstCadenceTicks, poseKeyframeEveryTicks) : poseKeyframeEveryTicks;
        if (!forceEmit && simTick - family.lastPoseEmit < cadence) {
            return;
        }
        List<PieceTrack> ranked = new ArrayList<>(family.pieces);
        ranked.sort((a, b) -> Float.compare(b.lastArea, a.lastArea));
        int pieceCount = ranked.size();
        if (pieceCount < 1 || pieceCount > 0xFF) {
            return; // structurally unreachable (fold caps at 255; sighted families are nonempty)
        }
        if (!forceEmit && impulse) {
            burstEmits++; // Meter: keyframe sets emitted on a standing contact deviation
        }
        for (int rank = 0; rank < ranked.size(); rank++) {
            PieceTrack piece = ranked.get(rank);
            if (!piece.poseFinite()) {
                poseNanSkips++;
                continue;
            }
            pendingPose.add(new HulkPoseFrameCodec.PoseKeyframe(
                    simTick, family.rootEntityId, family.deathTick, rank, pieceCount,
                    piece.locX, piece.locY, piece.facing, piece.velX, piece.velY, piece.angVel));
            piece.lastKfVelX = piece.velX;
            piece.lastKfVelY = piece.velY;
            piece.lastKfAngVel = piece.angVel;
        }
        family.lastPoseEmit = simTick;
    }

    /** Any tracked piece whose CURRENT (post-step, post-collision-phase) velocity
     * deviates from its last-keyframed velocity beyond the impulse eps pair. Pieces with no
     * emitted keyframe yet (or a non-finite refresh this tick) cannot deviate. */
    private static boolean hasImpulseSinceLastKeyframe(Family family) {
        for (PieceTrack piece : family.pieces) {
            if (!piece.poseFinite() || !Float.isFinite(piece.lastKfVelX)) {
                continue;
            }
            float dvx = piece.velX - piece.lastKfVelX;
            float dvy = piece.velY - piece.lastKfVelY;
            if (dvx * dvx + dvy * dvy > IMPULSE_VEL_EPS_US * IMPULSE_VEL_EPS_US
                    || Math.abs(piece.angVel - piece.lastKfAngVel) > IMPULSE_ANG_EPS_DEG_S) {
                return true;
            }
        }
        return false;
    }

    private void createFamily(long simTick, String stableKey, List<RemnantSighting> sightings) {
        Long rootEntityId = wireIdResolver.apply(stableKey);
        if (rootEntityId == null || rootEntityId < 1L) {
            // Never interned (or the T4 remount released the dead key): skip — the family
            // retries on its next sighting; a permanently unresolved corpse stays local-only.
            unresolvedFamilySkips++;
            return;
        }
        if (sightings.size() > 0xFF) {
            ordinalOverflowSkips++; // u8 wire field; unreachable in any real match
            return;
        }
        // firstSightTick = this walk's simTick — the family's wire identity (schema v2):
        // the corpse is first sighted within ~1-2 ticks of its EDSP death tick.
        Family family = new Family(rootEntityId, simTick);
        for (RemnantSighting sighting : sightings) {
            family.pieces.add(new PieceTrack(sighting));
            if (family.pieces.size() >= 2) {
                // Baseline is 1 (the EDSP-covered corpse): k first-sighted pieces = k-1 ADDs.
                // Each ADD consumes a cutSeq ordinal, but a first sighting has no
                // previous-tick renderOffset — these cuts are bare by construction.
                family.cutSeq++;
                pending.add(new HulkFractureFrameCodec.FractureEdge(
                        simTick, rootEntityId, family.deathTick,
                        HulkFractureFrameCodec.EDGE_KIND_PIECE_ADDED, 0, family.pieces.size()));
            }
        }
        familiesByKey.put(stableKey, family);
        familyRootsBornLastObserve.add(rootEntityId);
    }

    /**
     * Wire ids of corpse families born on the LAST {@link #observe} pass — the
     * engine-native "a wreck exists ⇒ that ship was DESTROYED" evidence the serve loop uses to
     * correct the despawn detector's hull-threshold guess (a kill blow that lands and despawns
     * inside one tick leaves the last-seen hull healthy, misreading as DEPARTED, as one
     * recorded run showed). Departed ships never mint a family.
     * Cleared at each observe entry, so a caller reads exactly the current tick's births.
     */
    public Set<Long> familyRootsBornLastObserve() {
        return familyRootsBornLastObserve;
    }

    private final Set<Long> familyRootsBornLastObserve = new HashSet<>();

    private void foldFamily(long simTick, Family family, List<RemnantSighting> sightings) {
        // Capture pre-scan — BEFORE any refresh, so parent identification reads each
        // track's previous-tick renderOffset (the parent's pre-split value) and
        // previous-tick bbox area. Parent law: a 1-piece family's only
        // sighted track IS the parent — NO moved requirement (the engine's armor-grid
        // cell-snap keeps the parent RO bit-identical on most cuts, and c_i = prevRO − RO_i
        // stays exact at zero delta); a multi-piece family takes the unique RO-moved track
        // ("moved" = both offsets finite AND either component changed — RO is STATIC between
        // splits), tiebroken by the unique track whose bbox area SHRANK this sweep (a carve
        // lops area off the parent), else no parent resolves.
        int freshCount = 0;
        int movedCount = 0;
        PieceTrack parentTrack = null;
        RemnantSighting parentSighting = null;
        if (ringReader != null) {
            int preFoldSize = family.pieces.size();
            PieceTrack singleTrack = null;
            RemnantSighting singleSighting = null;
            PieceTrack movedTrack = null;
            RemnantSighting movedSighting = null;
            int movedShrunkCount = 0;
            PieceTrack movedShrunkTrack = null;
            RemnantSighting movedShrunkSighting = null;
            int shrunkCount = 0;
            PieceTrack shrunkTrack = null;
            RemnantSighting shrunkSighting = null;
            for (RemnantSighting sighting : sightings) {
                PieceTrack known = trackFor(family, sighting.ref());
                if (known == null) {
                    freshCount++;
                    continue;
                }
                if (preFoldSize == 1) {
                    singleTrack = known;
                    singleSighting = sighting;
                }
                boolean moved = Float.isFinite(known.renderOffX)
                        && Float.isFinite(known.renderOffY)
                        && Float.isFinite(sighting.renderOffX())
                        && Float.isFinite(sighting.renderOffY())
                        && (known.renderOffX != sighting.renderOffX()
                                || known.renderOffY != sighting.renderOffY());
                boolean shrunk = Float.isFinite(sighting.boundsArea())
                        && Float.isFinite(known.lastArea)
                        && sighting.boundsArea() < known.lastArea;
                if (moved) {
                    movedCount++;
                    movedTrack = known;
                    movedSighting = sighting;
                    if (shrunk) {
                        movedShrunkCount++;
                        movedShrunkTrack = known;
                        movedShrunkSighting = sighting;
                    }
                }
                if (shrunk) {
                    shrunkCount++;
                    shrunkTrack = known;
                    shrunkSighting = sighting;
                }
            }
            if (preFoldSize == 1) {
                // The only track is the parent IF sighted (a same-tick vanish alongside a
                // fresh ref is not a cut — no parent resolves).
                parentTrack = singleTrack;
                parentSighting = singleSighting;
            } else if (movedCount == 1) {
                parentTrack = movedTrack;
                parentSighting = movedSighting;
            } else if (movedCount >= 2 && movedShrunkCount == 1) {
                parentTrack = movedShrunkTrack;
                parentSighting = movedShrunkSighting;
            } else if (movedCount == 0 && shrunkCount == 1) {
                parentTrack = shrunkTrack;
                parentSighting = shrunkSighting;
            }
        }
        // Latch the parent's RO channel NOW — the fold loop below refreshes tracks in
        // sighting order, so the parent may refresh before the fresh sighting captures.
        Object parentRef = parentTrack == null ? null : parentTrack.ref;
        float prevRoX = parentTrack == null ? Float.NaN : parentTrack.renderOffX;
        float prevRoY = parentTrack == null ? Float.NaN : parentTrack.renderOffY;
        float parentRoX = parentSighting == null ? Float.NaN : parentSighting.renderOffX();
        float parentRoY = parentSighting == null ? Float.NaN : parentSighting.renderOffY();
        // Pre-tick ranks: largest bbox area = 0 (stable sort keeps first-seen order on ties).
        List<PieceTrack> ranked = new ArrayList<>(family.pieces);
        ranked.sort((a, b) -> Float.compare(b.lastArea, a.lastArea));
        // Removals before adds, emitted largest-rank-first with post-edge counts.
        for (int rank = ranked.size() - 1; rank >= 0; rank--) {
            PieceTrack piece = ranked.get(rank);
            if (sightingFor(sightings, piece.ref) != null) {
                continue;
            }
            family.pieces.remove(piece);
            pending.add(new HulkFractureFrameCodec.FractureEdge(
                    simTick, family.rootEntityId, family.deathTick,
                    removeKindFor(piece.ref), rank, family.pieces.size()));
        }
        for (RemnantSighting sighting : sightings) {
            PieceTrack known = trackFor(family, sighting.ref());
            if (known != null) {
                known.refresh(sighting);
                continue;
            }
            if (family.pieces.size() >= 0xFF) {
                ordinalOverflowSkips++; // u8 wire field; unreachable in any real match
                continue;
            }
            int addOrdinal = family.cutSeq++;
            if (ringReader != null) {
                captureCutGeometryOrSkip(simTick, family, addOrdinal, freshCount, movedCount,
                        parentRef, prevRoX, prevRoY, parentRoX, parentRoY, sighting);
            }
            family.pieces.add(new PieceTrack(sighting));
            pending.add(new HulkFractureFrameCodec.FractureEdge(
                    simTick, family.rootEntityId, family.deathTick,
                    HulkFractureFrameCodec.EDGE_KIND_PIECE_ADDED, 0, family.pieces.size()));
        }
    }

    /**
     * one-shot cut capture for one emitted ADD — ALL-or-NOTHING: on the single-new-ref
     * signature with a RESOLVED parent (the parent law — see the fold pre-scan), read both pieces'
     * exact+visual rings, reconstruct them into the parent's pre-split local frame
     * ({@code c_i = prevRO − RO_i}), derive the shared cut run (tol 0.01, pre-quantize),
     * quantize (i16 × 1/64 u), and queue the 4 records (parent-exact, parent-visual,
     * clone-exact, clone-visual). ANY gate miss or step failure counts one per-leg skip
     * ({@link #cutGeometrySkipsByLeg()}) plus one drainable {@link CutGeometrySkip}
     * descriptor and queues NO records — the edge fans bare and the client falls back to its
     * local cut. Never throws into the fold.
     */
    private void captureCutGeometryOrSkip(long simTick, Family family, int addOrdinal,
            int freshCount, int movedCount, Object parentRef,
            float prevRoX, float prevRoY, float parentRoX, float parentRoY,
            RemnantSighting freshSighting) {
        if (freshCount != 1) {
            skipCutGeometry(simTick, family, SKIP_LEG_MULTI_FRESH, freshCount, movedCount,
                    -1, -1);
            return;
        }
        if (parentRef == null) {
            skipCutGeometry(simTick, family, SKIP_LEG_NO_PARENT, freshCount, movedCount, -1, -1);
            return;
        }
        if (addOrdinal > 0xFF) {
            skipCutGeometry(simTick, family, SKIP_LEG_ORDINAL, freshCount, movedCount, -1, -1);
            return;
        }
        if (!Float.isFinite(prevRoX) || !Float.isFinite(prevRoY)
                || !Float.isFinite(parentRoX) || !Float.isFinite(parentRoY)) {
            skipCutGeometry(simTick, family, SKIP_LEG_PARENT_RO_NAN, freshCount, movedCount,
                    -1, -1);
            return;
        }
        float cloneRoX = freshSighting.renderOffX();
        float cloneRoY = freshSighting.renderOffY();
        if (!Float.isFinite(cloneRoX) || !Float.isFinite(cloneRoY)) {
            skipCutGeometry(simTick, family, SKIP_LEG_CLONE_RO_NAN, freshCount, movedCount,
                    -1, -1);
            return;
        }
        float[][] parentRings = ringReader.ringsOrNull(parentRef);
        float[][] cloneRings = ringReader.ringsOrNull(freshSighting.ref());
        if (parentRings == null || parentRings.length != 2
                || cloneRings == null || cloneRings.length != 2) {
            skipCutGeometry(simTick, family, SKIP_LEG_RING_READ, freshCount, movedCount, -1, -1);
            return;
        }
        float[] parentExact = openRing(parentRings[0]);
        float[] parentVisual = openRing(parentRings[1]);
        float[] cloneExact = openRing(cloneRings[0]);
        float[] cloneVisual = openRing(cloneRings[1]);
        int parentPoints = parentExact == null ? -1 : parentExact.length / 2;
        int clonePoints = cloneExact == null ? -1 : cloneExact.length / 2;
        if (ringOutOfBounds(parentExact) || ringOutOfBounds(parentVisual)
                || ringOutOfBounds(cloneExact) || ringOutOfBounds(cloneVisual)) {
            skipCutGeometry(simTick, family, SKIP_LEG_RING_BOUNDS, freshCount, movedCount,
                    parentPoints, clonePoints);
            return;
        }
        // Feed frame: each piece's local ring + its recovered center, all in the
        // parent's PRE-SPLIT local frame — exactly what the two-arg carve consumes.
        translate(parentExact, prevRoX - parentRoX, prevRoY - parentRoY);
        translate(parentVisual, prevRoX - parentRoX, prevRoY - parentRoY);
        translate(cloneExact, prevRoX - cloneRoX, prevRoY - cloneRoY);
        translate(cloneVisual, prevRoX - cloneRoX, prevRoY - cloneRoY);
        int[] parentRun = cutRunOrNull(parentExact, cloneExact, CUT_MATCH_TOLERANCE);
        int[] cloneRun = cutRunOrNull(cloneExact, parentExact, CUT_MATCH_TOLERANCE);
        if (parentRun == null || cloneRun == null || parentRun.length != cloneRun.length) {
            skipCutGeometry(simTick, family, SKIP_LEG_CUT_RUN, freshCount, movedCount,
                    parentPoints, clonePoints);
            return;
        }
        short[] qParentExact = quantizeOrNull(parentExact);
        short[] qParentVisual = quantizeOrNull(parentVisual);
        short[] qCloneExact = quantizeOrNull(cloneExact);
        short[] qCloneVisual = quantizeOrNull(cloneVisual);
        if (qParentExact == null || qParentVisual == null
                || qCloneExact == null || qCloneVisual == null) {
            skipCutGeometry(simTick, family, SKIP_LEG_QUANTIZE, freshCount, movedCount,
                    parentPoints, clonePoints);
            return;
        }
        try {
            pendingCutGeometry.addAll(List.of(
                    new HulkCutGeometryFrameCodec.CutGeometryRecord(
                            simTick, family.rootEntityId, family.deathTick, addOrdinal,
                            HulkCutGeometryFrameCodec.PIECE_SLOT_PARENT,
                            HulkCutGeometryFrameCodec.RING_KIND_EXACT, qParentExact, parentRun),
                    new HulkCutGeometryFrameCodec.CutGeometryRecord(
                            simTick, family.rootEntityId, family.deathTick, addOrdinal,
                            HulkCutGeometryFrameCodec.PIECE_SLOT_PARENT,
                            HulkCutGeometryFrameCodec.RING_KIND_VISUAL, qParentVisual, null),
                    new HulkCutGeometryFrameCodec.CutGeometryRecord(
                            simTick, family.rootEntityId, family.deathTick, addOrdinal,
                            HulkCutGeometryFrameCodec.PIECE_SLOT_CLONE,
                            HulkCutGeometryFrameCodec.RING_KIND_EXACT, qCloneExact, cloneRun),
                    new HulkCutGeometryFrameCodec.CutGeometryRecord(
                            simTick, family.rootEntityId, family.deathTick, addOrdinal,
                            HulkCutGeometryFrameCodec.PIECE_SLOT_CLONE,
                            HulkCutGeometryFrameCodec.RING_KIND_VISUAL, qCloneVisual, null)));
        } catch (RuntimeException rejected) {
            // Record validation is the last fail-closed net (never throws into the fold).
            skipCutGeometry(simTick, family, SKIP_LEG_RECORD, freshCount, movedCount,
                    parentPoints, clonePoints);
        }
    }

    /** Count one capture skip (aggregate + per-leg) and queue its drainable descriptor. */
    private void skipCutGeometry(long simTick, Family family, String leg,
            int freshCount, int movedCount, int parentPoints, int clonePoints) {
        cutGeometryCaptureSkips++;
        cutGeometrySkipsByLeg.merge(leg, 1L, Long::sum);
        pendingCutGeometrySkips.add(new CutGeometrySkip(simTick, family.rootEntityId,
                family.deathTick, leg, freshCount, movedCount, parentPoints, clonePoints));
    }

    /** Cut-run coordinate-match tolerance (pre-quantize, per component). */
    public static final float CUT_MATCH_TOLERANCE = 0.01f;

    private static boolean ringOutOfBounds(float[] openRing) {
        return openRing == null
                || openRing.length / 2 < HulkCutGeometryFrameCodec.MIN_POINTS
                || openRing.length / 2 > HulkCutGeometryFrameCodec.MAX_POINTS;
    }

    private static void translate(float[] ring, float dx, float dy) {
        for (int i = 0; i < ring.length; i += 2) {
            ring[i] += dx;
            ring[i + 1] += dy;
        }
    }

    /**
     * Copy {@code ring} (flat x,y pairs) dropping the closing duplicate point (exact coord
     * match with the first) if present — engine bound walks yield CLOSED rings, the wire
     * carries OPEN; {@code null}/odd/degenerate (&lt; 3 points after opening) ⇒
     * {@code null}. Pure; always returns a fresh array.
     */
    public static float[] openRing(float[] ring) {
        if (ring == null || (ring.length & 1) != 0) {
            return null;
        }
        int points = ring.length / 2;
        if (points >= 2 && ring[0] == ring[ring.length - 2] && ring[1] == ring[ring.length - 1]) {
            points--;
        }
        if (points < 3) {
            return null;
        }
        return java.util.Arrays.copyOf(ring, points * 2);
    }

    /**
     * cut-run derivation: the indices of {@code ringA} points (flat x,y pairs, OPEN)
     * that coordinate-match ANY {@code ringB} point within {@code tol} per component. Valid
     * only when the matches form EXACTLY ONE wrap-aware consecutive run of length ≥ 2 and
     * &lt; the ring's point count (the shared cut chain: endpoints first/last, interiors
     * between, in this ring's own traversal order — wrap-aware, e.g. {@code [n-2, n-1, 0, 1]});
     * anything else ⇒ {@code null}. Pure.
     */
    public static int[] cutRunOrNull(float[] ringA, float[] ringB, float tol) {
        if (ringA == null || ringB == null
                || (ringA.length & 1) != 0 || (ringB.length & 1) != 0) {
            return null;
        }
        int n = ringA.length / 2;
        int m = ringB.length / 2;
        if (n < 3 || m < 3) {
            return null;
        }
        boolean[] shared = new boolean[n];
        int sharedCount = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (Math.abs(ringA[i * 2] - ringB[j * 2]) <= tol
                        && Math.abs(ringA[i * 2 + 1] - ringB[j * 2 + 1]) <= tol) {
                    shared[i] = true;
                    sharedCount++;
                    break;
                }
            }
        }
        if (sharedCount < 2 || sharedCount >= n) {
            return null;
        }
        // One false→true transition around the cycle per run; sharedCount < n guarantees at
        // least one false exists, so run count == transition count.
        int runStarts = 0;
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (shared[i] && !shared[(i + n - 1) % n]) {
                runStarts++;
                start = i;
            }
        }
        if (runStarts != 1) {
            return null;
        }
        int[] run = new int[sharedCount];
        for (int k = 0; k < sharedCount; k++) {
            run[k] = (start + k) % n;
        }
        return run;
    }

    /**
     * Quantize a flat x,y float ring to the wire grid (i16 × 1/64 u, round-to-nearest);
     * any component non-finite or quantizing outside the i16 range (±512 u) ⇒ {@code null}.
     * Pure.
     */
    public static short[] quantizeOrNull(float[] ring) {
        if (ring == null) {
            return null;
        }
        short[] out = new short[ring.length];
        for (int i = 0; i < ring.length; i++) {
            if (!Float.isFinite(ring[i])) {
                return null;
            }
            int q = Math.round(ring[i] * 64f);
            if (q < Short.MIN_VALUE || q > Short.MAX_VALUE) {
                return null;
            }
            out[i] = (short) q;
        }
        return out;
    }

    /** Any sighting in this family's tick group observed module-detached (the binding
     * reads {@code getParentStation()!=null && getStationSlot()==null}; split clones copy the
     * root's fields at cut time, so a clone can only read detached if the root already did). */
    private static boolean anyModuleDetached(List<RemnantSighting> sightings) {
        for (RemnantSighting sighting : sightings) {
            if (sighting.moduleDetached()) {
                return true;
            }
        }
        return false;
    }

    private static RemnantSighting sightingFor(List<RemnantSighting> sightings, Object ref) {
        for (RemnantSighting sighting : sightings) {
            if (sighting.ref() == ref) {
                return sighting;
            }
        }
        return null;
    }

    private static PieceTrack trackFor(Family family, Object ref) {
        for (PieceTrack piece : family.pieces) {
            if (piece.ref == ref) {
                return piece;
            }
        }
        return null;
    }

    /** Drain the edges emitted since the last drain, in emission order. */
    public List<HulkFractureFrameCodec.FractureEdge> drainEdges() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<HulkFractureFrameCodec.FractureEdge> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    /**
     * Drain the pose keyframes emitted since the last drain, in emission order (the
     * serve loop fans these AFTER the same tick's edges — same reliable ctrl spool, so the
     * client's rank interpretation is race-free). Always empty at cadence 0.
     */
    public List<HulkPoseFrameCodec.PoseKeyframe> drainPoseKeyframes() {
        if (pendingPose.isEmpty()) {
            return List.of();
        }
        List<HulkPoseFrameCodec.PoseKeyframe> drained = List.copyOf(pendingPose);
        pendingPose.clear();
        return drained;
    }

    /**
     * Drain the cut-geometry records captured since the last drain, in capture order
     * (4 per captured cut: parent-exact, parent-visual, clone-exact, clone-visual). Records
     * queue the same tick as their PIECE_ADDED edge; the serve loop drains these BEFORE
     * {@link #drainEdges()} so the strict per-session FIFO ctrl spool delivers the geometry
     * ahead of the paired ADD (the client latch is warm at edge arrival). Always empty with a
     * null ring reader (capture OFF).
     */
    public List<HulkCutGeometryFrameCodec.CutGeometryRecord> drainCutGeometryRecords() {
        if (pendingCutGeometry.isEmpty()) {
            return List.of();
        }
        List<HulkCutGeometryFrameCodec.CutGeometryRecord> drained =
                List.copyOf(pendingCutGeometry);
        pendingCutGeometry.clear();
        return drained;
    }

    /**
     * Drain the cut-geometry capture skips recorded since the last drain, in skip order —
     * the serve loop prints one {@code hulk_cut_geometry_skip} INFO row per descriptor (the
     * observability the shape-divergence diagnosis lacked). Always empty with a null ring
     * reader (capture OFF skips nothing).
     */
    public List<CutGeometrySkip> drainCutGeometrySkips() {
        if (pendingCutGeometrySkips.isEmpty()) {
            return List.of();
        }
        List<CutGeometrySkip> drained = List.copyOf(pendingCutGeometrySkips);
        pendingCutGeometrySkips.clear();
        return drained;
    }

    /** True while any corpse family is tracked (the source keeps observing until close-out). */
    public boolean tracking() {
        return !familiesByKey.isEmpty();
    }

    /** Round wrap: drop ALL state without emitting (a reset must never emit a remove flood). */
    public void reset() {
        familiesByKey.clear();
        pending.clear();
        pendingPose.clear();
        pendingCutGeometry.clear();
        pendingCutGeometrySkips.clear();
    }

    /** Families skipped because their stable key resolved to no wire id (skipped, never interned). */
    public long unresolvedFamilySkips() {
        return unresolvedFamilySkips;
    }

    /** Edges skipped at the u8 piece-count ceiling (&gt;255 pieces — engine pathology). */
    public long ordinalOverflowSkips() {
        return ordinalOverflowSkips;
    }

    /** Pose keyframes skipped because a piece's pose read failed upstream (all-NaN). */
    public long poseNanSkips() {
        return poseNanSkips;
    }

    /** Impulse detections (one per observed tick per family while a contact's Δv vs
     * the last EMITTED keyframe persists — a detection inside the cap gap counts every tick
     * until its keyframe goes out). */
    public long impulseTriggers() {
        return impulseTriggers;
    }

    /** Keyframe sets emitted on a standing contact deviation (excludes creation/edge-
     * forced sets — those emit under the pre-burst law regardless). Since the change-gate
     * this is ≈ one per contact per family, never a window's worth. */
    public long burstEmits() {
        return burstEmits;
    }

    /** ADDs whose cut capture was skipped (gate miss or any capture-step failure) — each
     * fans bare and the client falls back to its local cut. Only counts with capture ON.
     * Per-leg breakdown: {@link #cutGeometrySkipsByLeg()}. */
    public long cutGeometryCaptureSkips() {
        return cutGeometryCaptureSkips;
    }

    /** per-leg capture-skip counters (SKIP_LEG_* → count, first-hit order; legs never
     * hit are absent). The sum equals {@link #cutGeometryCaptureSkips()}. Defensive copy. */
    public Map<String, Long> cutGeometrySkipsByLeg() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cutGeometrySkipsByLeg));
    }
}
