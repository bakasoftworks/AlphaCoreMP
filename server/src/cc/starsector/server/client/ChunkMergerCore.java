package cc.starsector.server.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongPredicate;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * The ONE shared source of the snapshot reassembly LAW. Until this extraction
 * the law lived TWICE ("parity by convention", spec): the
 * client-mod {@code SnapshotChunkMerger} and the server-tree {@link MirrorChunkMerger} each
 * carried a full implementation in its own type system, kept equivalent only by the
 * merger-equivalence gate. Both are now thin ADAPTERS (decode vocabulary + view synthesis)
 * over this kernel, which owns all law state and every law decision. Generic over {@code A},
 * the AWSD anchor payload (client: its private {@code AwsdAnchor} record; server:
 * {@code AwsdEntityBaseline}) and {@code M}, the mount payload
 * ({@code ServerWeaponMountState} vs {@code ShipWeaponMountSnapshot}); scalar-tier payloads
 * travel as opaque {@code Object} slots — the kernel reads only (tier, tick) freshness, never
 * payload content. Imports are pinned to {@code java.util} + {@code java.util.function}: this
 * class is folded into the client mod jar (the $pilotClasses lane), where check-b-deny-refs
 * scans it. The twins' documented FORM divergences are now visible ctor/callsite parameters:
 * the AWSD stale-keyframe gauge ({@code adoptKeyframe}'s candidate tick — arrival frame tick
 * on the client, the record's embedded anchorTick on the server; equivalent on encodable
 * wire, {@code AwsdSnapshotCodec} pins anchorTick = the minting frame's simTick), the NACK id
 * domain ({@code nackIdMax}), and the drop-counter vocabulary (the server twin publishes the
 * epoch+stale SUM; the client keeps the split getters).
 *
 * <p>Per-(entity, tier) latest-wins store over a server tick's AWSS chunks and fragments
 * plus the AWSD keyframe+delta lane and its
 * NACK queue. The wire carries no chunk-index/count at any layer: each
 * chunk is a full valid AWSS frame over a subset of the tick's records, and the transport's
 * latest-wins seq discipline may stale-drop a reordered same-tick chunk — so
 * merge-to-one-frame reassembly is unbuildable; this store instead tracks, per entity and per
 * ship-scoped TIER, the newest payload seen and the tick it arrived on.
 *
 * <p>Rules (v1 D1/D2 preserved; v2 additions from the frozen fragmentation spec):
 * <ul>
 *   <li><b>Adopt / covering reset</b> — {@link #beginAwssFrame} answers
 *       {@link FrameKind#IDENTITY} for a not-older MARKER-FREE frame when the store is empty
 *       (adopt) or every tracked id is present in the frame (covering): the store is wiped
 *       (anchors SURVIVE — sibling law below), the twin folds the frame's whole entities and
 *       publishes the DECODED FRAME OBJECT itself. This is the identity keeper: an unchunked
 *       (K=1) stream takes this path every frame, preserving whole-frame absence-death
 *       semantics.</li>
 *   <li><b>Adopt/covering guard (v2, load-bearing)</b> — a frame carrying ANY fragment marker
 *       is a partial-carriage declaration and is NEVER adopted or treated as covering, even
 *       when it happens to carry every tracked id: a fragment as the first-ever frame takes
 *       the merge branch and yields a partial cell, never wholesale truth.</li>
 *   <li><b>Ordering guard</b> (unconditional — a safety invariant, not knob-gated):
 *       a frame OLDER than the newest tick ever folded is forced down the merge branch
 *       ({@link #staleForcedMerges} counted), where the per-tier {@code tick >=} guards
 *       reject its stale assertions — adopt/covering would instead clear the store and
 *       regress state / absence-kill newer entities (the hazard). EQUAL-tick
 *       covering stays admitted and is benign: covering requires EVERY tracked id present, so
 *       all tracked ids are re-asserted with same-tick truth — nothing newer exists to lose
 *       (same-tick sibling entities BLOCK covering exactly when wiping them would hurt).
 *       {@link #finishAwsdFrame} raises the same high-water so a later stale AWSS frame
 *       cannot adopt/cover-wipe newer AWSD state (the cross-family hazard). Gate-passing
 *       datagrams are tick-ascending today (0 violations), so the guard is pure
 *       defense-in-depth.</li>
 *   <li><b>Merge, whole entity</b> — {@link #foldWhole}: an UNMARKED pose asserts
 *       whole-entity authority — every tier upserted under the per-tier {@code tick >=}
 *       guard, full mount range.</li>
 *   <li><b>Merge, fragment (ABSENT &ne; EMPTY)</b> — {@link #foldFragment}: a MARKED pose
 *       asserts ONLY its masked tiers — a masked tier's payload is copied verbatim
 *       ({@code null}/sentinel payloads are legal and MEANINGFUL: the authoritative EMPTY
 *       clear travels naturally, no special case); an unmasked tier is ABSENT — held
 *       untouched. The ship core rides every fragment (anchor law) and is always upserted.
 *       WEAPON_MOUNT authority is bounded to {@code mountIndex ∈ [mountFrom, mountTo)}:
 *       in-range stored mounts not newer than the frame are removed, then the carried
 *       in-range records are upserted — records present are truth, in-range indexes without
 *       records are removed, out-of-range mounts stay.</li>
 *   <li><b>Eviction is the death edge under merge</b> (D2): a tracked entity leaves the store
 *       only via {@link #evict} (the consumed EDSP despawn), {@link #clear} (the MRST round
 *       boundary), or a covering reset that no longer carries it.</li>
 * </ul>
 *
 * <p><b>AWSD lane </b>: anchors live in a SIBLING map beside the cell
 * store, NOT in Cell fields — a server FALLBACK tick emits full absolute AWSS frames whose
 * covering reset wipes+re-mints Cells, while the server law keeps baselines across fallback
 * ticks ("the next good tick resumes DELTAs vs pre-fallback anchors"); Cell-resident anchors
 * would desync-freeze ships &le;E after every fallback. The sibling map dies via
 * {@link #evict} (EDSP) + {@link #clear} (MRST/re-admit) — exactly the server-side baseline
 * (DEC) lifecycle — plus the over-cap covering prune in {@link #beginAwssFrame}. Every AWSD
 * fold is {@link #foldAwsd} at the frame tick (anchor+delta =
 * complete knowledge; an empty clean-floor delta folds too = confirmed-unchanged), so the
 * dense AWSD-borne tier ticks advance together. The v10 cloak tier folds by RECORD KIND
 * (AWSD v3, the TWIN LAW — kind, never cloak content, which would
 * diverge the twins on cloakless hulls): a KEYFRAME carries the v3 cloak block and asserts
 * the tier (value or authoritative-EMPTY sentinel); a DELTA carries no cloak section — the
 * twins' applyDelta copies the anchor's cloak, so asserting it would over-claim freshness by
 * up to ~E ticks — hold-last, cloak freshness = keyframe cadence (see
 * {@link #TIER_PHASE_CLOAK_LOCAL}).
 *
 * <p><b>NACK law </b>: every {@code awsdDroppedEpoch}-family drop
 * ({@link #beginDelta} missing-anchor/epoch-mismatch + {@link #deltaFailed}, the D5
 * applyDelta signal) queues one NACK per (id, epoch) — hold-last repeats the same rejected
 * epoch every tick and CTRL is reliable (a sent NACK delivers or the session dies), so ONE
 * SNNK per (id, epoch) suffices; a newer rejected epoch for the same id coalesces over the
 * pending one (latest advisory wins); the once-per-(id,epoch) throttle SURVIVES the drain
 * and dies on keyframe adopt / evict / clear; the pending queue is bounded
 * ({@link #AWSD_NACK_PENDING_MAX}, oldest dropped). {@code awsdDroppedStale} never queues —
 * a fresh anchor already stands, nothing to re-key. Ids outside {@code [0, nackIdMax]}
 * skip-count instead of queueing (the client's defensive SNNK u16 domain guard; the server
 * passes Long.MAX_VALUE — its AWSD encode already refuses wide ids).
 *
 * <p><b>Threading</b>: store methods are caller-serialized on the owning net/reader thread
 * (client: the plugin's {@code runNetLoop} + inline demux callbacks; server: the ghost
 * seat's reader, fed below decode — EDSP evicts on CTRL delivery, MRST clears on match
 * reset). The NACK queue is deposited on that thread and {@link #drainPendingNacks drained}
 * on the intent-pump thread under the internal lock — an inline reader-thread send would
 * starve the very ACK processing that frees the CTRL window (the reader is also the ACK
 * path). Meters are volatile: written by the owning thread, read for receipts from other
 * threads (client cost nil — its surfaces are net-thread-only anyway).
 *
 * <p><b>Meters</b>: {@link #fragmentsMerged} (marked frames folded) and
 * {@link #fragTierSpreadMax} (widest tick spread ever seen WITHIN one entity — the
 * intra-entity TEARING meter; under fragment loss one entity's tiers straddle ticks and
 * self-heal next tick). The tear meter EXCLUDES the cloak tick: the
 * cloak tier's keyframe-cadence lag is by-design staleness, not tear (the run-3 A5 root
 * cause — honest cloakTick aged the spread to kf-cadence−1 on every cloak roster).
 * {@link #cloakAgeMax} is the dedicated cloak-staleness meter (widest
 * {@code cellMax − cloakTick} ever published; expected bound = keyframe cadence − 1),
 * alongside the v1 {@link #chunksMerged}/{@link #mixedTickMax} (cross-entity spread of
 * per-entity as-of ticks). The {@link TierTicksView} projection's {@code minTick}/
 * {@code maxTick} still span ALL asserted tiers INCLUDING cloak — unchanged semantics: the
 * projection reports raw ages; only the tear METER excludes the by-design cloak lag.
 */
public final class ChunkMergerCore<A, M> {

    /**
     * Tier-tick sentinel for a tier no frame has ever asserted for the entity (possible only
     * on cells first created by a fragment): any real assertion, whatever its tick, wins.
     */
    public static final long NEVER_ASSERTED = Long.MIN_VALUE;

    // Canonical tier bits, numerically EQUAL to the wire marker bits (client
    // ServerFragmentMarker == server ShipFragmentMarkerSnapshot — each twin's static
    // initializer asserts its own marker class agrees, fail-fast at class load).
    public static final int TIER_FLUX = 1;
    public static final int TIER_WEAPON_MOUNT = 1 << 1;
    public static final int TIER_ALLEGIANCE = 1 << 2;
    public static final int TIER_ENGINE_STATE = 1 << 3;
    public static final int TIER_MANEUVER = 1 << 4;
    public static final int TIER_SYSTEM_STATE = 1 << 5;
    /** The wire marker-mask domain (bits above it are refused by both marker ctors). */
    public static final int TIER_MASK_BITS = 0x3F;

    /**
     * Merger-LOCAL pseudo-bit for the v10 PHASE_CLOAK tier. D-M2-3 deliberately
     * mints NO wire marker bit for the cloak tier (no {@code TIER_MASK_BITS} widening — the
     * frozen v9 golden refuses a widened mask), so this bit exists only inside this store:
     * {@link #foldWhole} asserts it with every other tier, while a fragment's wire tierMask
     * can never carry it (both marker constructors reject bits above 0x3F) — a fragmented
     * cloak ship therefore HOLDS its last cloak state until its next whole-entity frame (the
     * documented hold-last semantics; a lone cloak hull sits far under the frame cap, so the
     * shape is not expected live). On the AWSD lane (v3) the bit
     * follows RECORD KIND: {@link #foldAwsd} asserts it for a KEYFRAME (the v3 cloak block —
     * value or authoritative-EMPTY sentinel) and masks it out for a DELTA (no cloak section;
     * applyDelta copies the anchor's cloak, so asserting it would over-claim freshness by up
     * to ~E ticks).
     */
    public static final int TIER_PHASE_CLOAK_LOCAL = 1 << 6;

    /**
     * Merger-LOCAL pseudo-bit for the v14 SHIP-CR tier — the cloak
     * pseudo-bit pattern verbatim: no wire marker bit is minted ({@code TIER_MASK_BITS}
     * stays 0x3F; the frozen v9 golden refuses a widened mask), so a fragmented ship's
     * CR record HOLDS-LAST until its next whole-entity frame. {@link #foldWhole}
     * asserts it with every other tier. On the AWSD lane (v6 — the commit B
     * flip) the bit follows RECORD KIND like cloak: {@link #foldAwsd} asserts it for a
     * KEYFRAME (the v6 CR block — value or the peak==0 absent sentinel = authoritative
     * EMPTY) and masks it out for a DELTA (no CR section; applyDelta copies the
     * anchor's CR, so asserting it would over-claim freshness by up to ~E ticks).
     */
    public static final int TIER_SHIP_CR_LOCAL = 1 << 7;

    /**
     * Merger-LOCAL pseudo-bit for the ARMOR tier (the (43)(d) armor
     * carriage fix). Armor rode the CORE tier only, but only ONE family carries it: an AWSD
     * resolve always mints {@code armorFrac} while an AWSS-decoded pose is armor-less by
     * design — so any same-or-newer-tick AWSS core fold silently clobbered the armor a
     * moment-earlier AWSD fold delivered, and the client armor lane's {@code !hasArmor()}
     * gate spun blind on exactly the ships under fire (client OVER-STRIP
     * never reconciled). This bit gives armor its own freshness: {@link #foldAwsd} asserts
     * it on BOTH record kinds (anchor+delta = complete armor knowledge — deltas inherit the
     * anchor's armorFrac, so the resolved state is server-truth every fold; the DENSE law,
     * unlike cloak/CR), while the AWSS folds ({@link #foldWhole}/{@link #foldFragment})
     * never assert it — AWSS's pose vocabulary structurally carries no armor fraction, so
     * asserting would write the sentinel over real armor, re-minting the very bug. Armor
     * therefore holds-last across AWSS-fallback stretches (staleness bound = the NACK
     * re-key + round-robin keyframe cadence), and {@link #cellTearMinTick} excludes it from
     * the tear meter like cloak/CR (by-design hold-last lag is staleness, not tear). No
     * wire marker bit exists ({@code TIER_MASK_BITS} stays 0x3F — no wire change).
     */
    public static final int TIER_ARMOR_LOCAL = 1 << 8;

    /** Every tier this store tracks: the wire mask + the merger-local pseudo-bits. */
    public static final int ALL_TIERS = TIER_MASK_BITS
            | TIER_PHASE_CLOAK_LOCAL | TIER_SHIP_CR_LOCAL | TIER_ARMOR_LOCAL;

    // Scalar-tier ordinals into Cell.scalar/scalarTick — the twins build payload arrays in
    // this order (WEAPON_MOUNT is not a scalar; it lives in the per-index mount sub-store).
    public static final int I_FLUX = 0;
    public static final int I_ALLEGIANCE = 1;
    public static final int I_ENGINE = 2;
    public static final int I_MANEUVER = 3;
    public static final int I_SYSTEM = 4;
    public static final int I_CLOAK = 5;
    public static final int I_CR = 6;
    public static final int I_ARMOR = 7;
    public static final int SCALAR_TIER_COUNT = 8;

    /** Ordinal → tier bit (the fold-mask decode; index = the I_* ordinal). */
    private static final int[] SCALAR_TIER_BITS = {
            TIER_FLUX, TIER_ALLEGIANCE, TIER_ENGINE_STATE, TIER_MANEUVER, TIER_SYSTEM_STATE,
            TIER_PHASE_CLOAK_LOCAL, TIER_SHIP_CR_LOCAL, TIER_ARMOR_LOCAL};

    /** pending-NACK coalescing cap: drop-oldest. */
    private static final int AWSD_NACK_PENDING_MAX = 256;

    /**
     * The default bound on tracked ids, for the cell store
     * and the AWSD anchor map each: a NEW id past it is DROPPED and counted
     * ({@link #trackedCapDrops} / {@link #anchorCapDrops}), a stored id is never
     * displaced. Sizing: a served entity is named on the AWSD record by a u16 id in [1, 0xFFFF]
     * (the server's encoder refuses a wider one), and fighters ride the same ship list as hulls,
     * so a lawful wire names at most 65,535 distinct ids; the kit's own per-round roster cap
     * ({@code RosterAppendLane.MAX_ROUND_ENTITIES}) is 65,536 appended entries. This bound sits
     * at both, so it never binds on a lawful world: it is a hostile-door bound only. Unbounded,
     * sibling chunks that never cover or evict (AWSS carries 64-bit ids) grow the store at the
     * door's will while every chunk's publish walks it all. The anchor map's keys are u16 record
     * ids, so at this bound it holds every id a record can name: a refused keyframe whose next
     * delta drops, NACKs and draws a re-key that is refused again is a loop that cannot start
     * (pending NACKs coalesce separately at {@link #AWSD_NACK_PENDING_MAX}, drop-oldest).
     */
    public static final int DEFAULT_TRACKED_CAP = 65_536;

    /** One stored mount payload + the tick it arrived on (the v2 mount-tier sub-store). */
    public record MountSlot<M>(M mount, long tick) {
    }

    /**
     * One entity's per-(entity, tier) merge state: the ship core rides every
     * fragment (anchor law); each scalar tier holds its newest payload (twin-defined —
     * {@code null} is legal and meaningful, e.g. the server's authoritative-EMPTY record) +
     * the tick it was asserted on; mounts merge per index. {@link #NEVER_ASSERTED} marks a
     * tier no frame has ever asserted for the entity — any real assertion, whatever its
     * tick, wins.
     */
    public static final class Cell<M> {
        private Object core;
        private long coreTick = NEVER_ASSERTED;
        private final Object[] scalar = new Object[SCALAR_TIER_COUNT];
        private final long[] scalarTick = new long[SCALAR_TIER_COUNT];
        /** Ascending mountIndex — iteration order IS the published mount-order contract. */
        private final TreeMap<Integer, MountSlot<M>> mounts = new TreeMap<>();

        private Cell() {
            Arrays.fill(scalarTick, NEVER_ASSERTED);
        }

        /** The stored core payload (always asserted before any publish reaches the cell). */
        public Object core() {
            return core;
        }

        public long coreTick() {
            return coreTick;
        }

        /** The stored scalar-tier payload at ordinal {@code i} ({@code null} is meaningful). */
        public Object scalar(int i) {
            return scalar[i];
        }

        public long scalarTick(int i) {
            return scalarTick[i];
        }

        /** Stored mounts ascending by mountIndex (the view-order contract), read-only use. */
        public Collection<MountSlot<M>> mounts() {
            return mounts.values();
        }
    }

    /** {@link #beginAwssFrame} verdict: adopt/covering identity vs the merge branch. */
    public enum FrameKind {
        IDENTITY, MERGE
    }

    /** Per-cell publish callback ({@link #publish}) — ascending entityId. */
    public interface CellVisitor<M> {
        void visit(long entityId, Cell<M> cell);
    }

    /** One queued delta NACK: the rejected delta's entityId + ADVISORY epoch. */
    public record NackEntry(long entityId, int epoch) {
    }

    /**
     * Fork-A v0: one entity's stored per-tier ticks, read-only.
     * {@link #NEVER_ASSERTED} marks a tier (or an empty mount map) never asserted;
     * {@code minTick}/{@code maxTick} range over the asserted ticks only — ALL of them,
     * INCLUDING cloak (the tear METER excludes the by-design cloak keyframe-cadence lag;
     * this projection reports raw ages and spans everything) — so
     * {@code maxTick - minTick} is the entity's current intra-entity width and
     * {@code publishedTick - minTick} its worst-case age.
     */
    public record TierTicksView(long entityId, long coreTick, long fluxTick,
            long mountTickMin, long mountTickMax, long allegianceTick, long engineTick,
            long maneuverTick, long systemTick, long cloakTick, long minTick, long maxTick) {
    }

    /** Ascending entityId — iteration order IS the published-view order contract. */
    private final TreeMap<Long, Cell<M>> store = new TreeMap<>();
    /** AWSD anchors, SIBLING to {@code store} by design (see class doc): an AWSS covering
     * reset wipes Cells but must preserve anchors across a server fallback tick. */
    private final HashMap<Long, A> awsdAnchors = new HashMap<>();
    /** Newest tick ever folded since {@link #clear} — the ordering-guard high-water. */
    private long newestFoldedTick = Long.MIN_VALUE;

    private final ToLongFunction<A> anchorTickOf;
    private final ToIntFunction<A> epochOf;
    private final ToIntFunction<M> mountIndexOf;
    /** NACK id domain bound: client 0xFFFF (SNNK u16), server Long.MAX_VALUE (no guard). */
    private final long nackIdMax;
    /** Max ids the cell store and (separately) the anchor map hold; see {@link #DEFAULT_TRACKED_CAP}. */
    private final int trackedCap;

    /** NACK state: deposited under this lock on the owning thread, drained on the
     * intent-pump thread. Throttle + queue clear on keyframe adopt (that id), evict, clear. */
    private final Object nackLock = new Object();
    private final LinkedHashMap<Long, Integer> pendingNacks = new LinkedHashMap<>();
    private final HashMap<Long, Integer> lastNackedEpoch = new HashMap<>();

    // ---- meters (owning-thread written; volatile for cross-thread receipt reads) ----
    private volatile long chunksMerged;      // frames that took the merge (non-covering) branch
    private volatile long mixedTickMax;      // widest stored-tick spread ever published
    private volatile long fragmentsMerged;   // merge-branch frames carrying >=1 marker
    private volatile long fragTierSpreadMax; // widest NON-CLOAK/CR tick spread within one entity
    private volatile long cloakAgeMax;       // widest cellMax - cloakTick ever published
    private volatile long crAgeMax;          // widest cellMax - crTick ever published
    private volatile long staleForcedMerges; // frames the ordering guard forced down the merge
    private volatile long awsdKeyframes;     // AWSD keyframe records adopted (anchor stored)
    private volatile long awsdDeltas;        // AWSD delta records resolved vs anchor + folded
    private volatile long awsdDroppedEpoch;  // missing-anchor / epoch-mismatch / D5 delta drops
    private volatile long awsdDroppedStale;  // reordered stale keyframes dropped
    private volatile long awsdNacksQueued;   // NACKs deposited (post-throttle, post-coalesce)
    private volatile long awsdNacksSkippedWideId; // ids outside [0, nackIdMax] skip-counted
    private volatile long trackedCapDrops;   // folds refused: a NEW id with the store at trackedCap
    private volatile long anchorCapDrops;    // keyframes refused: a NEW id with the anchors at trackedCap

    /**
     * The default-bounded kernel ({@link #DEFAULT_TRACKED_CAP}).
     *
     * @param anchorTickOf the stored anchor's staleness gauge for {@link #adoptKeyframe}
     * @param epochOf the stored anchor's epoch for the {@link #beginDelta} D5 guard
     * @param mountIndexOf the mount payload's index for the range-bounded mount law
     * @param nackIdMax the NACK id domain bound (client 0xFFFF, server Long.MAX_VALUE)
     */
    public ChunkMergerCore(ToLongFunction<A> anchorTickOf, ToIntFunction<A> epochOf,
            ToIntFunction<M> mountIndexOf, long nackIdMax) {
        this(anchorTickOf, epochOf, mountIndexOf, nackIdMax, DEFAULT_TRACKED_CAP);
    }

    /** {@code trackedCap} (&ge; 1, else throws) bounds the
     * cell store and the anchor map each; size it at or above the largest lawful roster. */
    public ChunkMergerCore(ToLongFunction<A> anchorTickOf, ToIntFunction<A> epochOf,
            ToIntFunction<M> mountIndexOf, long nackIdMax, int trackedCap) {
        if (trackedCap < 1) {
            throw new IllegalArgumentException("trackedCap must be >= 1: " + trackedCap);
        }
        this.anchorTickOf = anchorTickOf;
        this.epochOf = epochOf;
        this.mountIndexOf = mountIndexOf;
        this.nackIdMax = nackIdMax;
        this.trackedCap = trackedCap;
    }

    // ------------------------------------------------------------------ AWSS law

    /**
     * Open one AWSS frame: apply the ordering guard, then answer
     * {@link FrameKind#IDENTITY} (adopt/covering — the store is wiped here, a carried id's
     * anchor SURVIVES per the sibling law (the over-cap prune drops the rest), and the twin
     * folds whole entities then publishes the decoded frame object itself) or {@link FrameKind#MERGE}
     * (the twin folds whole/fragment, calls {@link #finishAwssMerge}, publishes the synthesized view).
     *
     * @param markerFree true iff the frame carries no fragment markers
     * @param frameCarriesId whether the frame carries the given tracked entityId
     *        (the covering test)
     */
    public FrameKind beginAwssFrame(long tick, boolean markerFree, LongPredicate frameCarriesId) {
        boolean older = tick < newestFoldedTick;
        if (older) {
            staleForcedMerges++;
        } else {
            newestFoldedTick = tick;
        }
        if (!older && markerFree && (store.isEmpty() || covers(frameCarriesId))) {
            // NOTE: a carried id's anchor survives this wipe by design — the identity
            // frame is exactly what a server AWSD-fallback tick emits, and the server keeps its
            // baselines across it (the next good tick resumes DELTAs vs pre-fallback anchors).
            store.clear();
            pruneUncarriedAnchors(frameCarriesId);
            return FrameKind.IDENTITY;
        }
        return FrameKind.MERGE;
    }

    /** Close one merge-branch AWSS frame (identity frames never call this). */
    public void finishAwssMerge(boolean hadMarkers) {
        chunksMerged++;
        if (hadMarkers) {
            fragmentsMerged++;
        }
    }

    /** Whole-entity authority: all tiers asserted, full mount range (v1 upsert semantics).
     * The v10 cloak tier is asserted HERE (and by {@link #foldAwsd} keyframes) ONLY — no
     * wire marker bit exists for it (D-M2-3), so fragments hold-last. The ARMOR tier is
     * NEVER asserted by an AWSS fold ({@link #TIER_ARMOR_LOCAL} — the AWSS pose vocabulary
     * carries no armor fraction; asserting would clobber real AWSD armor with the
     * sentinel): armor holds-last until the next {@link #foldAwsd}. */
    public void foldWhole(long entityId, long tick, Object core, Object[] scalars,
            Iterable<M> mounts) {
        Cell<M> cell = cellFor(entityId);
        // A NEW id past the tracked cap (dropped, counted).
        if (cell == null) {
            return;
        }
        foldCore(cell, core, tick);
        foldScalars(cell, ALL_TIERS & ~TIER_ARMOR_LOCAL, scalars, tick);
        foldMounts(cell, mounts, Integer.MIN_VALUE, Integer.MAX_VALUE, tick);
    }

    /** Fragment authority: ship core always (anchor law), then only the masked tiers/range
     * ({@code wireTierMask} can never carry the cloak pseudo-bit — both marker ctors reject
     * bits above {@link #TIER_MASK_BITS}). */
    public void foldFragment(long entityId, long tick, int wireTierMask, Object core,
            Object[] scalars, Iterable<M> mounts, int mountFrom, int mountTo) {
        Cell<M> cell = cellFor(entityId);
        // A NEW id past the tracked cap (dropped, counted).
        if (cell == null) {
            return;
        }
        foldCore(cell, core, tick);
        foldScalars(cell, wireTierMask, scalars, tick);
        if ((wireTierMask & TIER_WEAPON_MOUNT) != 0) {
            foldMounts(cell, mounts, mountFrom, mountTo, tick);
        }
    }

    // ------------------------------------------------------------------ AWSD law

    /**
     * Fold one resolved AWSD state (keyframe, or anchor+delta) — ALL dense tiers assert at
     * the frame tick under the per-tier {@code tick >=} guards (complete knowledge;
     * {@link #foldWhole} semantics on the AWSD lane), mount authority = the full range. The
     * v10 cloak tier AND the v14 CR tier are asserted by RECORD KIND (the v3 TWIN LAW —
     * kind, never block content, which would diverge the twins on cloakless/CR-absent
     * hulls): a KEYFRAME carries the v3 cloak block + the v6 CR block and asserts both
     * ({@code null}/sentinel = authoritative EMPTY, the scalar law); a DELTA carries
     * neither section — applyDelta copies the anchor's fields, so asserting would
     * over-claim freshness by up to ~E ticks: hold-last, freshness = keyframe cadence.
     * The v8 sparse system tier is NOT twin-law: its section rides
     * DELTAs too, so it asserts on both kinds — a systemless hull's SYS_NONE resolves
     * null = authoritative EMPTY every fold, the same shape AWSS yields when a frame
     * carries no system record for the entity.
     */
    public void foldAwsd(long entityId, long tick, boolean keyframe, Object core,
            Object[] scalars, Iterable<M> mounts) {
        Cell<M> cell = cellFor(entityId);
        // A NEW id past the cap (counted), no delta counted.
        if (cell == null) {
            return;
        }
        foldCore(cell, core, tick);
        // Record-KIND twin law, cloak + CR alike (AWSD v6): a KEYFRAME carries both
        // blocks and asserts both pseudo-bits; a DELTA carries neither section —
        // applyDelta copies the anchor's fields, so asserting would over-claim
        // freshness by up to ~E ticks (hold-last; freshness = keyframe cadence).
        // The ARMOR tier asserts on BOTH kinds (the DENSE law):
        // deltas inherit the anchor's armorFrac, so anchor+delta is complete armor
        // knowledge at every fold, unlike the cloak/CR keyframe-only sections.
        foldScalars(cell,
                keyframe
                        ? ALL_TIERS
                        : ALL_TIERS & ~(TIER_PHASE_CLOAK_LOCAL | TIER_SHIP_CR_LOCAL),
                scalars, tick);
        foldMounts(cell, mounts, Integer.MIN_VALUE, Integer.MAX_VALUE, tick);
        if (!keyframe) {
            awsdDeltas++;
        }
    }

    /**
     * KEYFRAME adopt: reject a reordered stale keyframe (older than the standing anchor's
     * {@code anchorTickOf} gauge — {@link #awsdDroppedStale} counted, no NACK: a fresh
     * anchor already stands), else store the anchor, clear that id's NACK state (fresh
     * anchor — the next epoch's drops re-arm), and count. The candidate tick is the twin's
     * gauge choice: arrival frame tick (client) or the record's embedded anchorTick (server)
     * — equivalent on encodable wire. A NEW id with the anchor map at the tracked cap is
     * refused too ({@link #anchorCapDrops} counted, no NACK queued here).
     *
     * @return true iff adopted (the twin then folds the resolved keyframe)
     */
    public boolean adoptKeyframe(long entityId, long candidateTick, A anchor) {
        A stored = awsdAnchors.get(entityId);
        // The anchor-map bound — one compare per NEW id.
        if (stored == null && awsdAnchors.size() >= trackedCap) {
            anchorCapDrops++;
            return false;
        }
        if (stored != null && candidateTick < anchorTickOf.applyAsLong(stored)) {
            awsdDroppedStale++; // reordered stale keyframe — hold the newer anchor
            return false;
        }
        awsdAnchors.put(entityId, anchor);
        clearNackFor(entityId);
        awsdKeyframes++;
        return true;
    }

    /**
     * DELTA open: answer the standing anchor, or {@code null} on the D5 drop signals
     * (missing anchor / epoch mismatch — {@link #awsdDroppedEpoch} counted + NACK
     * queued; the twin holds-last). On success the twin applies the delta against the
     * returned anchor and routes an applyDelta throw through {@link #deltaFailed}.
     */
    public A beginDelta(long entityId, int epoch) {
        A anchor = awsdAnchors.get(entityId);
        if (anchor == null || epochOf.applyAsInt(anchor) != epoch) {
            awsdDroppedEpoch++; // no anchor (lost keyframe) / epoch mismatch — hold-last
            queueNack(entityId, epoch);
            return null;
        }
        return anchor;
    }

    /** The twin's applyDelta catch (the remaining D5 drop signal reachable there — a dirty
     * mount index absent from the anchor roster; roster changes ride keyframes): drop +
     * hold-last + NACK, same posture as the {@link #beginDelta} guards. */
    public void deltaFailed(long entityId, int epoch) {
        awsdDroppedEpoch++;
        queueNack(entityId, epoch);
    }

    /** Close one AWSD frame: raise the ordering-guard high-water so a later stale AWSS
     * frame cannot adopt/cover-wipe newer AWSD state (the hazard, cross-family). */
    public void finishAwsdFrame(long tick) {
        if (tick > newestFoldedTick) {
            newestFoldedTick = tick;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** The consumed EDSP despawn: under merge, store EVICTION is the death edge — the
     * entity's cell, AWSD anchor, and NACK state die together (server DEC mirror). */
    public void evict(long entityId) {
        store.remove(entityId);
        awsdAnchors.remove(entityId);
        clearNackFor(entityId);
    }

    /** The MRST round boundary: forget everything before the fresh round's first frame,
     * including the ordering-guard high-water (tick spaces do not compare across rounds)
     * and the AWSD anchors (the server clears its baselines at the same boundary). */
    public void clear() {
        store.clear();
        awsdAnchors.clear();
        newestFoldedTick = Long.MIN_VALUE;
        synchronized (nackLock) {
            lastNackedEpoch.clear();
            pendingNacks.clear();
        }
    }

    // ------------------------------------------------------------------ publish

    /**
     * Publish the merge-branch view: visit every cell ascending by entityId (the twin
     * synthesizes its view object per cell), folding the spread meters as a side effect —
     * TEAR spread = cellMax − the oldest asserted NON-CLOAK tick (the cloak
     * tier's keyframe-cadence lag is by-design staleness, not tear), cloak age =
     * cellMax − cloakTick when asserted (→ {@link #cloakAgeMax}), {@link #mixedTickMax}
     * over the cross-entity cellMax spread.
     *
     * @return the view tick: the max stored as-of tick, or {@code frameTick} when nothing
     *         is tracked (a marked frame with no entities — server-invalid per the anchor
     *         law; the twin publishes the empty view)
     */
    public long publish(long frameTick, CellVisitor<M> visitor) {
        if (store.isEmpty()) {
            return frameTick;
        }
        long maxAsOf = Long.MIN_VALUE;
        long minAsOf = Long.MAX_VALUE;
        for (Map.Entry<Long, Cell<M>> entry : store.entrySet()) {
            Cell<M> cell = entry.getValue();
            visitor.visit(entry.getKey().longValue(), cell);
            long cellMax = cellMaxTick(cell);
            long spread = cellMax - cellTearMinTick(cell);
            if (spread > fragTierSpreadMax) {
                fragTierSpreadMax = spread;
            }
            long cloakTick = cell.scalarTick[I_CLOAK];
            if (cloakTick != NEVER_ASSERTED && cellMax - cloakTick > cloakAgeMax) {
                cloakAgeMax = cellMax - cloakTick;
            }
            long crTick = cell.scalarTick[I_CR];
            if (crTick != NEVER_ASSERTED && cellMax - crTick > crAgeMax) {
                crAgeMax = cellMax - crTick;
            }
            if (cellMax > maxAsOf) {
                maxAsOf = cellMax;
            }
            if (cellMax < minAsOf) {
                minAsOf = cellMax;
            }
        }
        if (maxAsOf - minAsOf > mixedTickMax) {
            mixedTickMax = maxAsOf - minAsOf;
        }
        return maxAsOf;
    }

    // ------------------------------------------------------------------ NACK surfaces

    /**
     * Intent-pump drain: snapshot + clear pending under the lock; encode/send outside
     * it. The once-per-(id,epoch) throttle SURVIVES the drain — hold-last keeps rejecting the
     * same epoch every tick and the sent NACK's re-key is in flight; only a fresh keyframe
     * adopt (or evict/clear) re-arms the id.
     */
    public List<NackEntry> drainPendingNacks() {
        synchronized (nackLock) {
            if (pendingNacks.isEmpty()) {
                return List.of();
            }
            List<NackEntry> drained = new ArrayList<>(pendingNacks.size());
            for (Map.Entry<Long, Integer> entry : pendingNacks.entrySet()) {
                drained.add(new NackEntry(entry.getKey().longValue(), entry.getValue().intValue()));
            }
            pendingNacks.clear();
            return drained;
        }
    }

    /** Delta NACKs currently pending drain (test observability — the evict/clear purge pin). */
    public int pendingNackCount() {
        synchronized (nackLock) {
            return pendingNacks.size();
        }
    }

    // ------------------------------------------------------------------ meters

    /** Frames that took the merge (non-covering) branch. */
    public long chunksMerged() {
        return chunksMerged;
    }

    /** Widest cross-entity spread of per-entity as-of ticks ever published. */
    public long mixedTickMax() {
        return mixedTickMax;
    }

    /** Merge-branch frames that carried at least one fragment marker. */
    public long fragmentsMerged() {
        return fragmentsMerged;
    }

    /** Widest NON-CLOAK tick spread ever seen WITHIN one entity's tiers (intra-entity
     * tearing; the by-design cloak keyframe-cadence lag lives in {@link #cloakAgeMax}). */
    public long fragTierSpreadMax() {
        return fragTierSpreadMax;
    }

    /** Widest {@code cellMax − cloakTick} ever published (the dedicated
     * cloak-staleness meter; expected bound = keyframe cadence − 1). */
    public long cloakAgeMax() {
        return cloakAgeMax;
    }

    /** Widest {@code cellMax − crTick} ever published (the dedicated ship-CR
     * staleness meter, the cloak-age twin; expected bound = keyframe cadence − 1 once
     * AWSD v6 carries CR). */
    public long crAgeMax() {
        return crAgeMax;
    }

    /** Frames the ordering guard forced down the merge branch as older-than-newest
     * (0 on an in-order stream; nonzero only under reordered stale-fold arrivals). */
    public long staleForcedMerges() {
        return staleForcedMerges;
    }

    /** AWSD keyframe records adopted (anchor stored). */
    public long awsdKeyframes() {
        return awsdKeyframes;
    }

    /** AWSD delta records resolved vs their anchor and folded. */
    public long awsdDeltas() {
        return awsdDeltas;
    }

    /** AWSD deltas dropped hold-last on the D5 signals (no anchor / epoch mismatch /
     * anchor-roster-absent mount) — the NACK trigger family. */
    public long awsdDroppedEpoch() {
        return awsdDroppedEpoch;
    }

    /** AWSD keyframes dropped as reordered-stale (older than the standing anchor's tick). */
    public long awsdDroppedStale() {
        return awsdDroppedStale;
    }

    /** Delta NACKs deposited into the pending queue (post-throttle, post-coalesce). */
    public long awsdNacksQueued() {
        return awsdNacksQueued;
    }

    /** NACK defensive domain-guard skips (ids outside {@code [0, nackIdMax]} never NACK). */
    public long awsdNacksSkippedWideId() {
        return awsdNacksSkippedWideId;
    }

    // ------------------------------------------------------------------ projections

    /** Entities currently tracked (test observability). */
    public int trackedEntities() {
        return store.size();
    }

    /** AWSD anchors currently standing (test observability — the sibling-map size). */
    public int awsdAnchorCount() {
        return awsdAnchors.size();
    }

    /** The stored cell for one entity, or {@code null} when untracked (payload access for
     * the twins' store projections). */
    public Cell<M> cell(long entityId) {
        return store.get(entityId);
    }

    /** Every stored (entityId, cell) ascending by id (the twins' projection iteration),
     * read-only use. */
    public Collection<Map.Entry<Long, Cell<M>>> cells() {
        return store.entrySet();
    }

    /** Fork-A v0: the stored per-tier ticks for one entity, or {@code null} when untracked. */
    public TierTicksView tierTicks(long entityId) {
        Cell<M> cell = store.get(entityId);
        return cell == null ? null : ticksOf(entityId, cell);
    }

    /** Fork-A v0: every tracked entity's stored per-tier ticks, ascending entityId. */
    public List<TierTicksView> allTierTicks() {
        List<TierTicksView> out = new ArrayList<>(store.size());
        for (Map.Entry<Long, Cell<M>> entry : store.entrySet()) {
            out.add(ticksOf(entry.getKey().longValue(), entry.getValue()));
        }
        return out;
    }

    /** The bound on the cell store and the anchor map, each. */
    public int trackedCap() {
        return trackedCap;
    }

    /** Folds refused because a NEW id met a full store (cumulative, once per refused fold). */
    public long trackedCapDrops() {
        return trackedCapDrops;
    }

    /** Keyframes refused because a NEW id met a full anchor map (cumulative). */
    public long anchorCapDrops() {
        return anchorCapDrops;
    }

    // ------------------------------------------------------------------ internals

    /**
     * The over-cap covering prune: an identity frame drops
     * every anchor (and its NACK state) whose id it does not carry; a carried id keeps its anchor
     * (the fallback-tick law). Gated on a cap refusal: below the cap every anchored id holds a
     * cell and a covering frame carries every stored id, so nothing could match. Past it an
     * anchor can stand cell-less; once a full frame omits its id it is gone, so it cannot crowd
     * a live id out of the bounded map.
     */
    private void pruneUncarriedAnchors(LongPredicate frameCarriesId) {
        if ((trackedCapDrops | anchorCapDrops) == 0 || awsdAnchors.isEmpty()) {
            return;
        }
        Iterator<Long> ids = awsdAnchors.keySet().iterator();
        while (ids.hasNext()) {
            long id = ids.next().longValue();
            if (!frameCarriesId.test(id)) {
                ids.remove();
                clearNackFor(id);
            }
        }
    }

    private boolean covers(LongPredicate frameCarriesId) {
        for (Long storedId : store.keySet()) {
            if (!frameCarriesId.test(storedId.longValue())) {
                return false;
            }
        }
        return true;
    }

    /** The stored cell, a fresh one for a new id, or
     * {@code null} when a NEW id meets a store already at the tracked cap (dropped,
     * {@link #trackedCapDrops} counted — one compare, no allocation, per new id). */
    private Cell<M> cellFor(long entityId) {
        Cell<M> cell = store.get(entityId);
        if (cell == null) {
            if (store.size() >= trackedCap) {
                trackedCapDrops++;
                return null;
            }
            cell = new Cell<>();
            store.put(entityId, cell);
        }
        return cell;
    }

    private void foldCore(Cell<M> cell, Object core, long tick) {
        if (cell.core == null || tick >= cell.coreTick) {
            cell.core = core;
            cell.coreTick = tick;
        }
    }

    /**
     * Upsert each masked scalar tier under the per-tier {@code tick >=} guard, copying the
     * twin's payload ref verbatim — a masked tier the frame carried nothing for arrives as
     * the twin's EMPTY shape ({@code null} record / pose sentinel), so the authoritative
     * EMPTY clear needs no special case; an unmasked tier is ABSENT and held
     * untouched. The cloak ordinal folds only under {@link #TIER_PHASE_CLOAK_LOCAL} — set
     * by {@link #foldWhole}'s ALL_TIERS and {@link #foldAwsd}'s KEYFRAME path, never by a
     * wire marker mask.
     */
    private void foldScalars(Cell<M> cell, int tierMask, Object[] scalars, long tick) {
        for (int i = 0; i < SCALAR_TIER_COUNT; i++) {
            if ((tierMask & SCALAR_TIER_BITS[i]) != 0 && tick >= cell.scalarTick[i]) {
                cell.scalar[i] = scalars[i];
                cell.scalarTick[i] = tick;
            }
        }
    }

    /**
     * Range-bounded mount authority: within {@code [from, to)} the frame is truth at its
     * tick — stored mounts not newer than the frame are removed, then the carried in-range
     * records are (re)upserted; out-of-range mounts are untouched.
     */
    private void foldMounts(Cell<M> cell, Iterable<M> mounts, int from, int to, long tick) {
        Iterator<Map.Entry<Integer, MountSlot<M>>> inRange =
                cell.mounts.subMap(from, true, to, false).entrySet().iterator();
        while (inRange.hasNext()) {
            if (inRange.next().getValue().tick() <= tick) {
                inRange.remove();
            }
        }
        for (M mount : mounts) {
            int index = mountIndexOf.applyAsInt(mount);
            if (index < from || index >= to) {
                continue;
            }
            MountSlot<M> existing = cell.mounts.get(index);
            if (existing == null || tick >= existing.tick()) {
                cell.mounts.put(index, new MountSlot<>(mount, tick));
            }
        }
    }

    /** Owning-thread deposit: one queued NACK per (id, epoch) — triggers are the
     * {@code awsdDroppedEpoch} family ONLY, never {@code awsdDroppedStale}. */
    private void queueNack(long entityId, int epoch) {
        if (entityId < 0L || entityId > nackIdMax) {
            awsdNacksSkippedWideId++; // defensive: outside the twin's NACK id domain
            return;
        }
        synchronized (nackLock) {
            Integer last = lastNackedEpoch.get(entityId);
            if (last != null && last.intValue() == epoch) {
                return; // already NACKed this (id, epoch) — the re-key is in flight
            }
            lastNackedEpoch.put(entityId, epoch);
            pendingNacks.put(entityId, epoch);
            if (pendingNacks.size() > AWSD_NACK_PENDING_MAX) {
                pendingNacks.remove(pendingNacks.keySet().iterator().next());
            }
        }
        awsdNacksQueued++;
    }

    /** Keyframe adopt / evict: that id's throttle + queue die with its anchor state. */
    private void clearNackFor(long entityId) {
        synchronized (nackLock) {
            lastNackedEpoch.remove(entityId);
            pendingNacks.remove(entityId);
        }
    }

    /** Newest asserted tick in the cell (the entity's as-of tick; core is always asserted). */
    private long cellMaxTick(Cell<M> cell) {
        long max = cell.coreTick;
        for (int i = 0; i < SCALAR_TIER_COUNT; i++) {
            if (cell.scalarTick[i] > max) {
                max = cell.scalarTick[i];
            }
        }
        for (MountSlot<M> slot : cell.mounts.values()) {
            if (slot.tick() > max) {
                max = slot.tick();
            }
        }
        return max;
    }

    /** Oldest asserted tick in the cell over ALL tiers incl. cloak ({@link #NEVER_ASSERTED}
     * tiers carry no age) — the {@link TierTicksView} projection's minTick. */
    private long cellMinTick(Cell<M> cell) {
        long min = cell.coreTick;
        for (int i = 0; i < SCALAR_TIER_COUNT; i++) {
            if (cell.scalarTick[i] != NEVER_ASSERTED && cell.scalarTick[i] < min) {
                min = cell.scalarTick[i];
            }
        }
        for (MountSlot<M> slot : cell.mounts.values()) {
            if (slot.tick() < min) {
                min = slot.tick();
            }
        }
        return min;
    }

    /** Oldest asserted NON-CLOAK/CR/ARMOR tick (core, the wire-masked scalar tiers, mounts)
     * — the TEAR meter's floor (the cloak keyframe-cadence lag is
     * staleness, not tear; the CR tier and the armor tier —
     * hold-last across AWSS-fallback stretches by design — ride the same exclusion). */
    private long cellTearMinTick(Cell<M> cell) {
        long min = cell.coreTick;
        for (int i = 0; i < SCALAR_TIER_COUNT; i++) {
            if (i == I_CLOAK || i == I_CR || i == I_ARMOR) {
                continue;
            }
            if (cell.scalarTick[i] != NEVER_ASSERTED && cell.scalarTick[i] < min) {
                min = cell.scalarTick[i];
            }
        }
        for (MountSlot<M> slot : cell.mounts.values()) {
            if (slot.tick() < min) {
                min = slot.tick();
            }
        }
        return min;
    }

    private TierTicksView ticksOf(long entityId, Cell<M> cell) {
        long mountMin = NEVER_ASSERTED;
        long mountMax = NEVER_ASSERTED;
        for (MountSlot<M> slot : cell.mounts.values()) {
            if (mountMin == NEVER_ASSERTED || slot.tick() < mountMin) {
                mountMin = slot.tick();
            }
            if (slot.tick() > mountMax) {
                mountMax = slot.tick();
            }
        }
        return new TierTicksView(entityId, cell.coreTick, cell.scalarTick[I_FLUX],
                mountMin, mountMax, cell.scalarTick[I_ALLEGIANCE], cell.scalarTick[I_ENGINE],
                cell.scalarTick[I_MANEUVER], cell.scalarTick[I_SYSTEM],
                cell.scalarTick[I_CLOAK], cellMinTick(cell), cellMaxTick(cell));
    }
}
