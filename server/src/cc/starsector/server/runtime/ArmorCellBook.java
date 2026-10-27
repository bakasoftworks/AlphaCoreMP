package cc.starsector.server.runtime;

import cc.starsector.server.runtime.AwsdSnapshotCodec.CellRun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * D-FOEARMOR per-cell send scheduler:
 * per entityId, the quantized
 * armor-cell image the capture retained ({@code snap}), the last value each cell crossed
 * the wire with ({@code lastSent}), and the redundancy countdown ({@code sendsLeft}) —
 * planned into v11 {@link CellRun} sections under a per-tick byte budget. ONE book per
 * serve, shared by the snapshot source (which {@link #observe}s every alive armored entity
 * per tick) and {@link DownEmissionComposer} (which {@link #stage}s per record inside
 * {@code composeAwsdLaneOrFallBack}). Serve-thread confined — no synchronization.
 *
 * <p><b>Scheduling law:</b> a cell is PENDING when {@code snap ≠ lastSent} (fresh
 * dirt — resets its redundancy to R) or {@code sendsLeft > 0} (loss-redundancy resend).
 * The tick plan fills sections with (a) pending cells, entities oldest-dirty-first, each
 * section ≤ {@link AwsdSnapshotCodec#CELL_BYTES_PER_RECORD_MAX} and ≤
 * {@link AwsdSnapshotCodec#CELL_RUNS_MAX_PER_RECORD} runs, all sections together under the
 * tick byte budget — the scheduler DEFERS leftover cells to the next tick, never fragments
 * a datagram; then (b) the REPAIR CURSOR: a global round-robin over every entity's DAMAGED
 * ({@code q < 255}) cells re-sending under its own sub-budget, bounding any loss/drift to
 * {@code Σdamaged / repairBytes} ticks. Runs merge across gaps ≤ 3 clean cells (absolute
 * values are free to re-send; 1 absorbed byte beats a 3-byte run header).
 *
 * <p><b>Pass discipline (the composer's phase-2 law):</b> planning is lazy at the
 * first {@link #stage} after a pass ends; {@link #commit} applies {@code lastSent}/
 * {@code sendsLeft}/cursor state for the sections actually staged and counts the meters;
 * {@link #rollback} discards everything — a refused tick leaves the dirty set intact, so
 * the next good tick re-plans the same cells. The composer calls exactly one of the two
 * per compose pass.
 *
 * <p><b>Budget ownership refinement vs the build map (recorded per its own escape
 * clause):</b> the map sketched {@code stage(entityId, tickBudgetLeft)} with the composer
 * tracking bytes; the budget lives HERE instead — oldest-dirty-first needs a cross-entity
 * plan the composer's single ascending-id pass cannot provide, and the composer stays
 * selection-sequencing (its own class doc law) with {@code stage(id)} one-arg. Knob VALUES
 * arrive as ctor ints (boot-resolve at open; the resolve functions land with the composer
 * wiring in the wire-bump commit).
 *
 * <p>Spawn default = full armor both sides: a new entity's {@code lastSent} seeds
 * all-255, so an already-damaged first sight is naturally dirty and an untouched grid never
 * costs a byte. A cellCount change under a reused entityId (round-wrap identity reuse)
 * re-seeds the entry.
 */
public final class ArmorCellBook {

    /** Merge runs across up to this many clean cells between pending ones (the cost law). */
    public static final int RUN_MERGE_GAP_MAX = 3;

    private final int tickBudgetBytes;
    private final int redundancy;
    private final int repairBudgetBytes;

    private final TreeMap<Long, Entry> entries = new TreeMap<>();

    // Repair cursor: persistent position (entity, next cell ordinal), advanced at commit.
    private long repairCursorEntity = -1L;
    private int repairCursorCell;

    // In-flight pass state (lazy-planned at the first stage() after a pass ends).
    private boolean passPlanned;
    private final HashMap<Long, List<CellRun>> plannedRuns = new HashMap<>();
    private final HashMap<Long, List<CellRun>> stagedRunsThisPass = new HashMap<>();
    private long plannedCursorEntity = -1L;
    private int plannedCursorCell;
    private long plannedDeferredCells;
    private long plannedDeferAgeMax;

    /**
     * Max {@code simTick} any {@link #observe} has seen (never decreases within a round;
     * {@link #clear} re-zeroes it with the entries it dates). The "now" of the deferral-AGE
     * meter.
     */
    private long latestObservedTick;

    // Cumulative round-line meters (committed only, the snapDictEntries discipline).
    private long cellBytes;
    private long cellRuns;
    private long cellsDeferred;
    private long deferAgeMaxTicks;

    public ArmorCellBook(int tickBudgetBytes, int redundancy, int repairBudgetBytes) {
        if (tickBudgetBytes < 0) {
            throw new IllegalArgumentException("tickBudgetBytes < 0: " + tickBudgetBytes);
        }
        if (redundancy < 1) {
            throw new IllegalArgumentException("redundancy < 1: " + redundancy);
        }
        if (repairBudgetBytes < 0) {
            throw new IllegalArgumentException("repairBudgetBytes < 0: " + repairBudgetBytes);
        }
        this.tickBudgetBytes = tickBudgetBytes;
        this.redundancy = redundancy;
        this.repairBudgetBytes = repairBudgetBytes;
    }

    /**
     * Record this tick's quantized cell image for an entity (the snapshot source's per-tick
     * call, BEFORE compose). {@code cells} is the retainer's live buffer — copied here,
     * never kept (the retainer's aliasing law). Null/empty = entity without a grid, no-op.
     * {@code simTick} stamps oldest-dirty ordering the first time an entity gains dirt.
     */
    public void observe(long entityId, byte[] cells, long simTick) {
        if (cells == null || cells.length == 0) {
            return;
        }
        if (cells.length > 0xFFFF) {
            throw new IllegalArgumentException("cellCount outside wire u16 ordinals: "
                    + cells.length + " (engine grids max out near 484)");
        }
        if (simTick > latestObservedTick) {
            latestObservedTick = simTick;
        }
        Entry e = entries.get(entityId);
        if (e == null || e.snap.length != cells.length) {
            e = new Entry(cells.length);
            entries.put(entityId, e);
        }
        System.arraycopy(cells, 0, e.snap, 0, cells.length);
        if (e.dirtySinceTick == 0L && e.hasPendingCell()) {
            e.dirtySinceTick = Math.max(1L, simTick);
            e.ageSinceTick = e.dirtySinceTick;
        }
    }

    /**
     * The planned cell section for this entity's record this tick (empty = 1-byte idle
     * tail). First call of a pass plans the whole tick across entities; every call marks
     * the entity's section STAGED so {@link #commit} applies exactly what rode a record.
     */
    public List<CellRun> stage(long entityId) {
        if (!passPlanned) {
            planPass();
        }
        List<CellRun> runs = plannedRuns.get(entityId);
        if (runs == null || runs.isEmpty()) {
            return List.of();
        }
        stagedRunsThisPass.put(entityId, runs);
        return runs;
    }

    /** Commit the staged pass: sent-state + redundancy countdown + cursor + meters. */
    public void commit() {
        for (Map.Entry<Long, List<CellRun>> staged : stagedRunsThisPass.entrySet()) {
            Entry e = entries.get(staged.getKey());
            if (e == null) {
                continue; // forgotten mid-pass; nothing to mark
            }
            int sectionBytes = AwsdSnapshotCodec.cellSectionBytes(staged.getValue());
            cellBytes += sectionBytes - AwsdSnapshotCodec.CELL_SECTION_MIN_BYTES;
            cellRuns += staged.getValue().size();
            for (CellRun run : staged.getValue()) {
                for (int i = 0; i < run.values().length; i++) {
                    int idx = run.startIdx() + i;
                    if (e.snap[idx] != e.lastSent[idx]) {
                        e.sendsLeft[idx] = (byte) (redundancy - 1);
                    } else if (e.sendsLeft[idx] > 0) {
                        e.sendsLeft[idx]--;
                    }
                    e.lastSent[idx] = e.snap[idx];
                }
            }
            if (!e.hasPendingCell()) {
                e.dirtySinceTick = 0L;
                e.ageSinceTick = 0L;
            }
        }
        cellsDeferred += plannedDeferredCells;
        if (plannedDeferAgeMax > deferAgeMaxTicks) {
            deferAgeMaxTicks = plannedDeferAgeMax;
        }
        repairCursorEntity = plannedCursorEntity;
        repairCursorCell = plannedCursorCell;
        endPass();
    }

    /** Discard the staged pass whole — the refused-tick leg of the phase-2 law. */
    public void rollback() {
        endPass();
    }

    /**
     * Join/adopt: every entity's damaged cells re-ride with full redundancy —
     * shared-artifact bytes, legal under encode-once; the new peer converges within
     * {@code Σdamaged / budget} ticks. Idempotent.
     */
    public void markAllDamagedDirty() {
        for (Entry e : entries.values()) {
            boolean any = false;
            for (int i = 0; i < e.snap.length; i++) {
                if (e.snap[i] != (byte) 0xFF) {
                    e.sendsLeft[i] = (byte) redundancy;
                    any = true;
                }
            }
            if (any && e.dirtySinceTick == 0L) {
                e.dirtySinceTick = 1L; // older than any live tick: adopters drain first
                // The AGE meter dates the join itself, not the synthetic ordering stamp —
                // else every adopter defer would read as "starved since match start".
                e.ageSinceTick = Math.max(1L, latestObservedTick);
            }
        }
    }

    /** Despawn evict (the composer's {@code onEntityDespawns} forward). */
    public void forget(long entityId) {
        entries.remove(entityId);
    }

    /** Round wrap: every grid re-mints and wire ids re-point — drop everything. */
    public void clear() {
        entries.clear();
        repairCursorEntity = -1L;
        repairCursorCell = 0;
        // Cumulative meters (incl. deferAgeMaxTicks) survive the wrap, as they always have;
        // only the "now" the ages are measured against re-zeroes with the entries it dates.
        latestObservedTick = 0L;
        endPass();
    }

    /** Cumulative committed cell-section bytes past the presence byte ({@code awsd_cell_bytes=}). */
    public long cellBytes() {
        return cellBytes;
    }

    /** Cumulative committed runs ({@code awsd_cell_runs=}). */
    public long cellRuns() {
        return cellRuns;
    }

    /** Cumulative pending cells deferred past their tick by the budget ({@code awsd_cell_deferred=}). */
    public long cellsDeferred() {
        return cellsDeferred;
    }

    /**
     * Cumulative max deferral AGE in ticks ({@code awsd_cell_defer_age_max=}): over every
     * committed pass, the largest {@code latestObservedTick - ageSinceTick} of a PENDING
     * entity that planned NOTHING that pass (a partially-planned entity is making progress
     * and does not count).
     *
     * <p>WHY: {@link #planPass} sorts pending entities
     * oldest-{@code dirtySinceTick}-first and {@link #commit} re-zeroes that stamp only when
     * an entity has NO pending cell left, while each entity's marginal section is capped at
     * {@code 96 - 1} B. So entities whose demand stays above the per-record cap (a
     * sustained wide hit re-dirtying carried cells every tick) keep their OLD stamps
     * forever, and three of them (95 + 95 + 66 B) exhaust the 256-B default tick budget —
     * any younger dirty entity then defers whole with NO upper bound until one of them goes
     * quiet. With redundancy >= 2 (the production default) ANY entity re-dirtied every tick
     * keeps its stamp the same way, whatever its size: a freshly carried cell still owes its
     * redundant send at {@link #commit}, so the entity is never pending-free
     * (the contract row pins the R=1 shape). {@link #cellsDeferred} counts cells, not age, so a flight cannot tell a 1-tick
     * defer from a 20-s starve; this meter can. The fix candidate (an age-fair planner) is
     * deliberately NOT built here — this meter tells whether the edge is ever hit in a real
     * flight first. Contract pin: {@code AwsdCellSectionContract
     * .starvationEdgeThreeOlderPersistentlyDirty}.
     *
     * <p>Age base = {@code ageSinceTick}, equal to {@code dirtySinceTick} except after
     * {@link #markAllDamagedDirty}, whose synthetic ordering stamp 1 would otherwise read
     * as "starved since match start".
     */
    public long deferAgeMaxTicks() {
        return deferAgeMaxTicks;
    }

    /** Tracked-entity count (contract pin). */
    public int trackedEntityCount() {
        return entries.size();
    }

    // ------------------------------------------------------------------ planning

    private void endPass() {
        passPlanned = false;
        plannedRuns.clear();
        stagedRunsThisPass.clear();
        plannedDeferredCells = 0L;
        plannedDeferAgeMax = 0L;
        plannedCursorEntity = repairCursorEntity;
        plannedCursorCell = repairCursorCell;
    }

    private void planPass() {
        passPlanned = true;
        plannedRuns.clear();
        stagedRunsThisPass.clear();
        plannedDeferredCells = 0L;
        plannedDeferAgeMax = 0L;
        int budget = tickBudgetBytes;

        // (a) pending cells, oldest-dirty entity first (tie: ascending entityId — the
        // TreeMap walk below is already id-ascending, and the sort is stable).
        List<Map.Entry<Long, Entry>> dirty = new ArrayList<>();
        for (Map.Entry<Long, Entry> en : entries.entrySet()) {
            if (en.getValue().hasPendingCell()) {
                dirty.add(en);
            }
        }
        dirty.sort((l, r) -> Long.compare(l.getValue().dirtySinceTick, r.getValue().dirtySinceTick));
        for (Map.Entry<Long, Entry> en : dirty) {
            if (budget <= 0) {
                plannedDeferredCells += en.getValue().pendingCellCount();
                foldDeferAge(en.getValue());
                continue;
            }
            Entry e = en.getValue();
            List<Integer> pending = new ArrayList<>();
            for (int i = 0; i < e.snap.length; i++) {
                if (e.snap[i] != e.lastSent[i] || e.sendsLeft[i] > 0) {
                    pending.add(i);
                }
            }
            BuiltRuns built = buildRuns(e, pending, budget,
                    AwsdSnapshotCodec.CELL_RUNS_MAX_PER_RECORD);
            if (!built.runs.isEmpty()) {
                plannedRuns.put(en.getKey(), built.runs);
                budget -= built.marginalBytes;
            } else {
                foldDeferAge(e); // budget left but under one run header: planned nothing
            }
            plannedDeferredCells += pending.size() - built.pendingCovered;
        }

        // (b) repair cursor under its own sub-budget within what's left of the tick's.
        int repairBudget = Math.min(repairBudgetBytes, budget);
        planRepair(repairBudget);
    }

    /**
     * Fold one wholly-deferred pending entity's age into the pass's planned max (applied
     * at {@link #commit}, discarded by {@link #rollback}); see {@link #deferAgeMaxTicks}.
     *
     */
    private void foldDeferAge(Entry e) {
        if (e.ageSinceTick <= 0L) {
            return;
        }
        long age = latestObservedTick - e.ageSinceTick;
        if (age > plannedDeferAgeMax) {
            plannedDeferAgeMax = age;
        }
    }

    /**
     * Walk the persistent cursor (ascending entityId from its position, wrapping once)
     * appending DAMAGED-cell repair runs to the plan; the planned end position becomes
     * the cursor only at {@link #commit}. Cells already covered by a planned run are
     * skipped (their bytes already ride).
     */
    private void planRepair(int repairBudget) {
        if (repairBudget <= 0 || entries.isEmpty()) {
            return;
        }
        Long startEntity = entries.ceilingKey(
                repairCursorEntity < 0 ? entries.firstKey() : repairCursorEntity);
        if (startEntity == null) {
            startEntity = entries.firstKey();
        }
        long atEntity = startEntity;
        int atCell = atEntity == repairCursorEntity ? repairCursorCell : 0;
        int visitedEntities = 0;
        int totalEntities = entries.size();
        while (repairBudget > 3 && visitedEntities <= totalEntities) {
            Entry e = entries.get(atEntity);
            List<CellRun> already = plannedRuns.get(atEntity);
            List<Integer> picks = new ArrayList<>();
            int i = atCell;
            for (; i < e.snap.length; i++) {
                if (e.snap[i] == (byte) 0xFF || covered(already, i)) {
                    continue;
                }
                // Cost bound: each pick worst-cases a fresh 3-B header + 1 value.
                if ((picks.size() + 1) * 4 > repairBudget) {
                    break;
                }
                picks.add(i);
            }
            if (!picks.isEmpty()) {
                List<CellRun> base = already == null ? List.of() : already;
                // BOTH per-record caps bind on the COMBINED base+repair section — the byte
                // caps alone don't bound run count (14 single-cell runs = 57 B < 96 B), and
                // an over-cap section is a codec throw that kills the serve loop (
                // 8 pending + 6 repair = 14 > 12). Uncovered picks defer to the cursor.
                BuiltRuns repair = buildRuns(e, picks,
                        remainingSectionBudget(base),
                        AwsdSnapshotCodec.CELL_RUNS_MAX_PER_RECORD - base.size());
                if (!repair.runs.isEmpty()) {
                    List<CellRun> merged = new ArrayList<>(base);
                    merged.addAll(repair.runs);
                    plannedRuns.put(atEntity, merged);
                    repairBudget -= repair.marginalBytes;
                }
            }
            if (i < e.snap.length) {
                // Budget ended inside this entity: cursor resumes here next tick.
                plannedCursorEntity = atEntity;
                plannedCursorCell = i;
                return;
            }
            Long next = entries.higherKey(atEntity);
            atEntity = next == null ? entries.firstKey() : next;
            atCell = 0;
            visitedEntities++;
        }
        plannedCursorEntity = atEntity;
        plannedCursorCell = atCell;
    }

    /** Marginal section budget left for an entity already carrying planned runs. */
    private static int remainingSectionBudget(List<CellRun> already) {
        int used = AwsdSnapshotCodec.cellSectionBytes(already)
                - AwsdSnapshotCodec.CELL_SECTION_MIN_BYTES;
        int runsLeft = AwsdSnapshotCodec.CELL_RUNS_MAX_PER_RECORD - already.size();
        if (runsLeft <= 0) {
            return 0;
        }
        return AwsdSnapshotCodec.CELL_BYTES_PER_RECORD_MAX
                - AwsdSnapshotCodec.CELL_SECTION_MIN_BYTES - used;
    }

    private record BuiltRuns(List<CellRun> runs, int marginalBytes, int pendingCovered) {
    }

    /**
     * Greedy run builder over ascending cell indices: merge across gaps ≤
     * {@link #RUN_MERGE_GAP_MAX}, split at {@link AwsdSnapshotCodec#CELL_RUN_LEN_MAX},
     * stop at the per-record section cap / the caller's run allowance / the caller's byte
     * budget — uncovered indices defer whole (never a fragmented datagram).
     * {@code runBudget} is the caller's remaining per-record RUN allowance, not always the
     * codec cap: the repair path appends to an already-planned base section, so the
     * combined section must respect {@link AwsdSnapshotCodec#CELL_RUNS_MAX_PER_RECORD}.
     */
    private BuiltRuns buildRuns(Entry e, List<Integer> indices, int byteBudget,
            int runBudget) {
        int sectionCap = Math.min(byteBudget,
                AwsdSnapshotCodec.CELL_BYTES_PER_RECORD_MAX
                        - AwsdSnapshotCodec.CELL_SECTION_MIN_BYTES);
        int runCap = Math.min(runBudget, AwsdSnapshotCodec.CELL_RUNS_MAX_PER_RECORD);
        List<CellRun> runs = new ArrayList<>();
        int bytes = 0;
        int covered = 0;
        int at = 0;
        while (at < indices.size() && runs.size() < runCap) {
            int start = indices.get(at);
            int end = start; // inclusive
            int taken = 1;
            while (at + taken < indices.size()) {
                int next = indices.get(at + taken);
                if (next - end > RUN_MERGE_GAP_MAX + 1
                        || next - start + 1 > AwsdSnapshotCodec.CELL_RUN_LEN_MAX) {
                    break;
                }
                end = next;
                taken++;
            }
            int len = end - start + 1;
            if (bytes + 3 + len > sectionCap) {
                // Try the bare head of this cluster if a shorter run still fits.
                len = Math.min(len, sectionCap - bytes - 3);
                if (len < 1) {
                    break;
                }
                end = start + len - 1;
                int newTaken = 0;
                for (int j = at; j < indices.size() && indices.get(j) <= end; j++) {
                    newTaken++;
                }
                taken = newTaken;
            }
            byte[] values = new byte[len];
            for (int j = 0; j < len; j++) {
                values[j] = e.snap[start + j];
            }
            runs.add(new CellRun(start, values));
            bytes += 3 + len;
            covered += taken;
            at += taken;
        }
        return new BuiltRuns(runs, bytes, covered);
    }

    private static boolean covered(List<CellRun> runs, int idx) {
        if (runs == null) {
            return false;
        }
        for (CellRun run : runs) {
            if (idx >= run.startIdx() && idx < run.startIdx() + run.values().length) {
                return true;
            }
        }
        return false;
    }

    private static final class Entry {
        final byte[] snap;
        final byte[] lastSent;
        final byte[] sendsLeft;
        long dirtySinceTick;
        /**
         * When this entity's current dirty spell began, for the deferral-AGE meter only
         * (never an ordering key).
         */
        long ageSinceTick;

        Entry(int cellCount) {
            snap = new byte[cellCount];
            lastSent = new byte[cellCount];
            java.util.Arrays.fill(lastSent, (byte) 0xFF); // spawn default: full armor
            sendsLeft = new byte[cellCount];
        }

        boolean hasPendingCell() {
            for (int i = 0; i < snap.length; i++) {
                if (snap[i] != lastSent[i] || sendsLeft[i] > 0) {
                    return true;
                }
            }
            return false;
        }

        int pendingCellCount() {
            int n = 0;
            for (int i = 0; i < snap.length; i++) {
                if (snap[i] != lastSent[i] || sendsLeft[i] > 0) {
                    n++;
                }
            }
            return n;
        }
    }
}
