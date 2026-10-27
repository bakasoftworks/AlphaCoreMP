package cc.starsector.server.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The hit-cell
 * tap carved VERBATIM out of {@link ArcHitServeProbe} — the {@code DamageTakenModifier} proxy handler
 * ({@link HitCellTap}), its three grid handles, its per-hit cost meter and its install (the marker-dedupe
 * {@link #removeStale} + the add {@link #installHitCellTap}). WHY its own file: the probe grew +242 nb over the
 * hit-cell work, and the tap is a separable sim-thread listener behind its own INSTRUMENT knob
 * ({@code cc.server.hitCellRow}, default OFF) — it gets its own lens. Package-private: the probe keeps the public
 * face ({@code HIT_CELL_MARKER} alias, {@code armHitCell}, {@code hitCellMeterTokens}, the {@code *ForTest}
 * seams) as one-line delegates. It SHARES the probe's {@link HullSourceLedger} (this is the pre-hit PUSH side of
 * the ledger's hit stack; the probe's report capture is the pop side) and its {@link ServeProbeSupport.FailSoft}
 * (a quiet arc lane pops nothing, so the tap pushes nothing). Threading: sim thread only (the serve loop and the
 * engine advance share one thread). The ledger's hit-cell state is NOT carved (it is interleaved with the window
 * it rides). Fresh-file watch: a second listener landing here = re-think the boundary.
 * The armed install also PRE-WARMS the three handles off the live hull ({@link #prewarm}).
 *
 */
final class ArcHitCellTap {

    /**
     * The {@code toString()} of the SEPARATE hit-cell
     * {@code DamageTakenModifier} proxy — its OWN dedupe key ({@link #installHitCellTap}).
     *
     */
    public static final String HIT_CELL_MARKER = "cc-hit-cell-modifier";
    private static final String DAMAGE_TAKEN_MODIFIER =
            "com.fs.starfarer.api.combat.listeners.DamageTakenModifier";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";

    /** The owning probe's ledger + fail-soft budget (shared references) and its resolved {@code Ship} class
     *  ({@link #bindShipClass}) — the names the carved bodies read, unchanged. */
    private final HullSourceLedger hullSources;
    private final ServeProbeSupport.FailSoft failSoft;
    private Class<?> shipClass;
    /**
     * The hit-cell tap ({@code cc.server.hitCellRow}, {@link ArcHitServeProbe#armHitCell}) — armed, the
     * install adds a {@code DamageTakenModifier} proxy ({@link HitCellTap}) that notes each ship hit's
     * PRE-hit armor cell into {@link #hullSources} ({@link HullSourceLedger#notePreHit}); unarmed = no
     * proxy, zero per-hit cost. Three handles resolve ONCE ({@link #resolveHitCellHandles}; null
     * {@code gridGetCellAtLocation} = the tap body is inert). {@code hitCellFaults} = tap throws swallowed
     * (first logged). Cost instrument = the ArmorCellRetainer idiom (first sample apart, warm mean at
     * {@code HIT_COST_PRINT_AT}).
     */
    private boolean hitCellArmed;
    private Method shipGetArmorGrid;
    private Method gridGetGrid;
    private Method gridGetCellAtLocation;
    private long hitCellFaults;
    private static final Object[] NO_ARGS = new Object[0];
    private static final int[] HIT_COST_PRINT_AT = {20, 100, 600};
    private long hitCostFirstNanos = -1L;
    private long hitCostWarmNanos;
    private int hitCostWarmSamples;
    private int hitCostPrintIdx;
    private long hitCostMaxNanos;
    private int hitCostMaxAt;
    /**
     * The install-time pre-warm's iteration count ({@link #prewarm}). WHY 20: the serve JVM is
     * Temurin 17, whose {@code Method.invoke} runs a NATIVE accessor for a Method's first 15 calls
     * ({@code sun.reflect.inflationThreshold}, default 15) and GENERATES a bytecode accessor class on call 16 —
     * three handles ⇒ three class generations. The rig printed a warm mean of
     * 368 µs at n=20 against ≈ 3 µs steady: ≈ 7.4 ms of sim-thread time inside warm samples 1-20 (the inflation
     * cause is a HYPOTHESIS; {@code max_at=} on the cost line tests it). 20 clears 15 with margin, so that cost
     * lands at install (round open, where a hitch is invisible) instead of inside the first real hits.
     *
     */
    static final int PREWARM_CALLS = 20;
    /**
     * What {@link #removeStale} remembers for the old print site ({@link #installHitCellTap}) —
     * {@code removed} ACCUMULATES across this probe's install attempts (a retry that succeeds after a throwing
     * attempt still prints the true {@code prior_removed=}); {@code dedupeMiss} is the LATEST attempt's throw.
     *
     */
    private int removed;
    private Throwable dedupeMiss;

    ArcHitCellTap(HullSourceLedger hullSources, ServeProbeSupport.FailSoft failSoft) {
        this.hullSources = hullSources;
        this.failSoft = failSoft;
    }

    /** The probe's resolved {@code Ship} class — bound before the tap can be added or driven. */
    void bindShipClass(Class<?> c) {
        shipClass = c;
    }

    void arm(boolean on) {
        hitCellArmed = on;
    }

    boolean armed() {
        return hitCellArmed;
    }

    long faults() {
        return hitCellFaults;
    }

    int removed() {
        return removed;
    }

    /**
     * The tap-local handler of the {@code DamageTakenModifier} proxy. The
     * engine calls {@code modifyDamageTaken(param, target, damage, point, shieldHit)} INSIDE
     * {@code Ship.applyDamage} (BEFORE {@code applyDamageInner}, on the same
     * {@code point} the grid applies); a non-null return is a modifier id the engine later
     * {@code unmodify}s and a throw propagates into
     * the damage step — so the interface call ALWAYS returns {@code null} ({@link #modifyDamageTaken}:
     * every throw caught + counted, never rethrown) and {@code damage} is never read. The Object trio
     * answers here ({@code toString} = {@link #HIT_CELL_MARKER}, the dedupe key; identity hash/equals).
     *
     */
    private final class HitCellTap implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "toString":
                    return HIT_CELL_MARKER;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && args.length == 1 && proxy == args[0];
                default:
                    return modifyDamageTaken(args);
            }
        }
    }

    /** The tap's interface call: {@link #tapPreHit} under a catch-all; ALWAYS {@code null}.
     * */
    Object modifyDamageTaken(Object[] args) {
        if (!hitCellArmed || gridGetCellAtLocation == null || args == null || args.length != 5) {
            return null;
        }
        long t0 = System.nanoTime(); // the cost meter spans every armed call, not only the pushing ones
        Object target = args[1];
        try {
            tapPreHit(target, args[0], args[3], args[4]);
        } catch (Throwable failure) {
            hitCellFaults++;
            if (hitCellFaults == 1L) {
                System.out.println("arc-hit probe hit_cell_fault site=tap cause=" + failure.getClass().getName()
                        + " [counted hit_cell_faults=; that hit's slot reads unmapped]");
            }
            // The report still pops — give it its OWN unmapped slot, or a nested hit's
            // report would pop the outer hit's cell and print it as its own. Pushed with its kind.
            if (!failSoft.quiet() && shipClass.isInstance(target)) {
                hullSources.notePreHit(target, args[0], -1, Float.NaN, Float.NaN);
            }
        }
        recordHitCost(System.nanoTime() - t0);
        return null;
    }

    /**
     * Sim thread, inside {@code Ship.applyDamage}. No push when the arc lane is quiet (the probe's
     * {@link ArcHitServeProbe#capture} pops nothing then) or the target is not a Ship. A shield hit / null {@code point} pushes the unmapped
     * sentinel without touching the grid (push and pop pair 1:1 on every engine-gated call). Else:
     * grid → the LIVE {@code float[][]} (no copy) → {@code getCellAtLocation(point)} (null off-grid) →
     * {@link HullSourceLedger#notePreHit} with {@code ord = x·h + y} (the ArmorCellRetainer ordinal law =
     * the client's {@code cell=}), the cell's armor and the 21-cell
     * footprint sum ({@link #maskSum}) as they stand BEFORE the hit; off-grid ⇒ {@code -1}/NaN/NaN.
     *
     * <p>{@code param} = the engine's {@code args[0]}, handed through so the ledger classifies the
     * slot's kind HERE — before the kill edge's nested hits clear it.
     */
    private void tapPreHit(Object target, Object param, Object point, Object shieldHit)
            throws ReflectiveOperationException {
        if (failSoft.quiet() || target == null || !shipClass.isInstance(target)) {
            return;
        }
        int ord = -1;
        float pre = Float.NaN;
        float mask = Float.NaN;
        // A shield hit / null point pushes the unmapped sentinel with NO grid read, so every
        // engine-gated call pushes exactly the slot its own report pops (hit_cell_paired = matched).
        if (point != null && !Boolean.TRUE.equals(shieldHit)) {
            Object grid = shipGetArmorGrid.invoke(target, NO_ARGS);
            float[][] cells = grid == null ? null : (float[][]) gridGetGrid.invoke(grid, NO_ARGS);
            int[] cell = grid == null ? null : (int[]) gridGetCellAtLocation.invoke(grid, point);
            if (onGrid(cell, cells)) {
                ord = cell[0] * cells[0].length + cell[1];
                pre = cells[cell[0]][cell[1]];
                mask = maskSum(cells, cell[0], cell[1]);
            }
        }
        hullSources.notePreHit(target, param, ord, pre, mask);
    }

    /** {@code cell} ({@code getCellAtLocation}'s return; null off-grid) names a cell inside {@code cells} — the
     *  tap's and the pre-warm's ONE bounds law. */
    private static boolean onGrid(int[] cell, float[][] cells) {
        return cell != null && cell.length >= 2 && cells != null && cells.length > 0 && cells[0] != null
                && cell[0] >= 0 && cell[0] < cells.length && cell[1] >= 0 && cell[1] < cells[0].length;
    }

    /** The client's splash-footprint law ported verbatim: the 21-cell (5×5
     *  minus the four corners — the vanilla splash footprint) sum around {@code (cx,cy)}, grid-clipped.
     * */
    static float maskSum(float[][] cells, int cx, int cy) {
        float sum = 0f;
        int w = cells.length;
        int h = cells[0].length;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx * dx + dy * dy == 8) {
                    continue; // the four corners
                }
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < w && y >= 0 && y < h) {
                    sum += cells[x][y];
                }
            }
        }
        return sum;
    }

    /** Cost MEASURE (the ArmorCellRetainer idiom): per-tap nanos, the first sample apart, the warm mean
     *  printed at n = 20/100/600 as {@code hit-cell cost first_us= warm_us= n=}.
     *
     *  <p>+ the WORST single warm sample and its 1-based warm index ({@code max_us= max_at=}, before
     *  {@code n=}, which stays the last token) — a cumulative mean only INFERS a hitch (the rig's 368 µs at
     *  n=20); the max SHOWS it and where. The first warm sample always seeds it (so {@code max_at >= 1} even on a
     *  0 ns clock tick); the first (cold) sample never enters it. No allocation, no extra clock read.
     * */
    private void recordHitCost(long nanos) {
        if (hitCostFirstNanos < 0L) {
            hitCostFirstNanos = nanos;
            return;
        }
        hitCostWarmNanos += nanos;
        hitCostWarmSamples++;
        if (hitCostMaxAt == 0 || nanos > hitCostMaxNanos) {
            hitCostMaxNanos = nanos;
            hitCostMaxAt = hitCostWarmSamples;
        }
        if (hitCostPrintIdx < HIT_COST_PRINT_AT.length && hitCostWarmSamples == HIT_COST_PRINT_AT[hitCostPrintIdx]) {
            hitCostPrintIdx++;
            System.out.println("hit-cell cost first_us=" + tenthsUs(hitCostFirstNanos)
                    + " warm_us=" + tenthsUs(hitCostWarmNanos / hitCostWarmSamples)
                    + " max_us=" + tenthsUs(hitCostMaxNanos) + " max_at=" + hitCostMaxAt + " n=" + hitCostWarmSamples);
        }
    }

    /** Locale-free one-decimal microseconds. */
    private static double tenthsUs(long nanos) {
        return (nanos / 100L) / 10.0;
    }

    /**
     * The tap's marker-dedupe, called FIRST by
     * {@code ArcHitServeProbe.installOn} — before any resolve that can throw. The engine (and its global listener
     * manager) is REUSED across rounds, so a round whose install threw at a resolve used to leave the PRIOR round's
     * tap attached, pushing into a ledger nobody {@code endTick}s for the whole round (the drain retries until
     * quiet). Runs armed or not; walks a COPY before {@code removeListener}; never throws — the count and the miss
     * are remembered ({@link #removed} / {@link #dedupeMiss}) and print at the old site ({@link #installHitCellTap}),
     * so a successful install prints the same lines in the same order. A throwing install prints nothing here (the
     * drain's strike row is the only tell on that tick).
     */
    void removeStale(ClassLoader loader, Object manager, Method getListeners, Method removeListener) {
        dedupeMiss = null;
        try {
            Class<?> modifierClass = Class.forName(DAMAGE_TAKEN_MODIFIER, false, loader);
            List<?> existing = (List<?>) getListeners.invoke(manager, modifierClass);
            if (existing != null) {
                for (Object prior : new ArrayList<>(existing)) {
                    if (prior != null && HIT_CELL_MARKER.equals(prior.toString())) {
                        removeListener.invoke(manager, prior);
                        removed++;
                    }
                }
            }
        } catch (Throwable miss) {
            dedupeMiss = miss; // printed once, at the old site (installHitCellTap), as hit_cell_tap_unresolved
        }
    }

    /**
     * The hit-cell tap's OWN install — a SEPARATE single-interface proxy
     * over {@code DamageTakenModifier} on the SAME global manager. Its marker-dedupe runs on EVERY install,
     * armed or not: the engine is REUSED across rounds, so a prior round's tap would otherwise keep firing
     * into a ledger nobody {@code endTick}s (the Capture dedupe in {@code ArcHitServeProbe.installOn} walks {@code DamageListener} only;
     * the manager files a proxy under each interface). Armed ⇒ the
     * three grid handles resolve ONCE and the proxy is added; any miss = one line, no tap (armed rows then
     * carry unmapped {@code hitCell=-1} slots — never a guessed cell). Unarmed prints nothing.
     *
     *
     * <p>That dedupe now runs FIRST ({@link #removeStale}); this method is the old print site
     * (its remembered count, or its miss as the same {@code hit_cell_tap_unresolved} line with no tap added) and
     * the ADD, called LAST by the probe's install with the arc location type {@code vecClass}.
     *
     *
     * <p>{@code ship} = the live hull the probe's install holds (nullable — the test seams pass what
     * they like; null = no pre-warm, no line). Armed and non-null, the handles are PRE-WARMED ({@link #prewarm})
     * after they resolve and before the proxy is added (the tap cannot fire mid-pre-warm); its one line prints
     * right after the {@code installed} line, so only a tap that landed carries one.
     */
    void installHitCellTap(long simTick, ClassLoader loader, Object manager, Method addListener, Class<?> vecClass,
            Object ship) {
        try {
            if (dedupeMiss != null) {
                throw dedupeMiss;
            }
            if (!hitCellArmed) {
                if (removed > 0) {
                    System.out.println("arc-hit probe hit_cell_tap stale_removed=" + removed + " tick=" + simTick);
                }
                return;
            }
            Class<?> modifierClass = Class.forName(DAMAGE_TAKEN_MODIFIER, false, loader);
            Class<?> shipApi = Class.forName(SHIP_API, false, loader);
            resolveHitCellHandles(shipApi, vecClass);
            String prewarmLine = ship == null ? null : prewarm(simTick, shipApi, vecClass, ship);
            ServeProbeSupport.installListener(loader, modifierClass, new HitCellTap(), addListener, manager);
            System.out.println("arc-hit probe hit_cell_tap installed tick=" + simTick + " prior_removed=" + removed);
            if (prewarmLine != null) {
                System.out.println(prewarmLine);
            }
        } catch (Throwable miss) {
            gridGetCellAtLocation = null;
            System.out.println("arc-hit probe hit_cell_tap_unresolved tick=" + simTick + " armed=" + hitCellArmed
                    + " prior_removed=" + removed + " [no pre-hit cells] " + miss.getClass().getName());
        }
    }

    /**
     * The install-time pre-warm — {@link #PREWARM_CALLS} iterations of EXACTLY the three reads
     * {@link #tapPreHit} makes ({@code getArmorGrid()} → {@code getGrid()} + {@code getCellAtLocation(point)}, then
     * {@link #maskSum} when on-grid) at {@code point} = the hull's own {@code getLocation()}, each timed with
     * {@code System.nanoTime()}. READ-ONLY: it never calls {@link #tapPreHit}, {@link #modifyDamageTaken},
     * {@link HullSourceLedger#notePreHit} or {@link #recordHitCost} — a pushed slot with no report would mis-pair
     * the first real hit, a pre-warm sample would pollute the tap's cost meter. Engine side:
     * {@code Ship.getArmorGrid} returns its field; the ArmorGrid's {@code getGrid}
     * returns its live array; {@code getCellAtLocation} = a subtract into a FRESH
     * {@code Vector2f}, a rotate into another, a {@code getCellFor} holder — no field
     * write, no listener. Returns the ONE install line ({@code hit_cell_prewarm calls= preload_us= total_us= max_us= max_at=
     * tick=}; a location that is not a {@code vecClass} / a null grid = {@code calls=0 ... skipped=}); NEVER
     * throws — any throw is caught HERE ({@code hit_cell_prewarm_failed tick= <SimpleName>}), so it can never reach
     * {@link #installHitCellTap}'s outer catch (which nulls the cell handle and drops the tap).
     *
     * <p>FIRST, on every path, {@link HullSourceLedger#preloadHitSlots} — the ledger's two slot classes
     * load here, not inside the first armed hit (no meter, no pending slot). Untimed: the returned line's grammar is
     * unchanged ({@code MissileImpactMemoContractRows} R11 pins it byte-exact).
     *
     */
    private String prewarm(long simTick, Class<?> shipApi, Class<?> vecClass, Object ship) {
        try {
            long preloadT0 = System.nanoTime();
            hullSources.preloadHitSlots(ship); // + the kind resolves on the ship's loader
            long preloadNanos = System.nanoTime() - preloadT0; // The preload's own cost rides the line as preload_us=
            Object point = accessible(shipApi.getMethod("getLocation")).invoke(ship, NO_ARGS);
            if (!vecClass.isInstance(point)) {
                return "arc-hit probe hit_cell_prewarm calls=0 tick=" + simTick + " skipped=location_not_vec";
            }
            long totalNanos = 0L;
            long maxNanos = 0L;
            int maxAt = 0;
            for (int i = 1; i <= PREWARM_CALLS; i++) {
                long t0 = System.nanoTime();
                Object grid = shipGetArmorGrid.invoke(ship, NO_ARGS);
                if (grid == null) {
                    return "arc-hit probe hit_cell_prewarm calls=0 tick=" + simTick + " skipped=null_grid";
                }
                float[][] cells = (float[][]) gridGetGrid.invoke(grid, NO_ARGS);
                int[] cell = (int[]) gridGetCellAtLocation.invoke(grid, point);
                if (onGrid(cell, cells)) {
                    maskSum(cells, cell[0], cell[1]);
                }
                long nanos = System.nanoTime() - t0;
                totalNanos += nanos;
                if (maxAt == 0 || nanos > maxNanos) {
                    maxNanos = nanos;
                    maxAt = i;
                }
            }
            return "arc-hit probe hit_cell_prewarm calls=" + PREWARM_CALLS + " preload_us=" + tenthsUs(preloadNanos)
                    + " total_us=" + tenthsUs(totalNanos)
                    + " max_us=" + tenthsUs(maxNanos) + " max_at=" + maxAt + " tick=" + simTick;
        } catch (Throwable miss) {
            return "arc-hit probe hit_cell_prewarm_failed tick=" + simTick + " "
                    + SwallowCosmeticFxAuthoritativeDamageSink.unwrapReflectiveCause(miss).getClass().getSimpleName();
        }
    }

    /** {@code getArmorGrid()} off {@code ship} (ShipAPI in the engine), then {@code getGrid()} +
     *  {@code getCellAtLocation(vec)} off its return type (ArmorGridAPI) — all-or-nothing (the RECBF
     *  durability-handle idiom). */
    void resolveHitCellHandles(Class<?> ship, Class<?> vecClass) throws NoSuchMethodException {
        Method armorGrid = accessible(ship.getMethod("getArmorGrid"));
        Method grid = accessible(armorGrid.getReturnType().getMethod("getGrid"));
        Method cellAt = accessible(armorGrid.getReturnType().getMethod("getCellAtLocation", vecClass));
        shipGetArmorGrid = armorGrid;
        gridGetGrid = grid;
        gridGetCellAtLocation = cellAt;
    }

    /** Test seam body ({@code ArcHitServeProbe.hitCellProxyForTest}): the REAL {@link HitCellTap} behind a
     *  {@link Proxy} over {@code iface}. */
    <T> T proxyForTest(Class<T> iface) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, new HitCellTap()));
    }

    /** Test seam: the cost meter's sample count (the first sample + the warm ones) — every
     *  armed call is one sample. */
    int hitCostSamplesForTest() {
        return (hitCostFirstNanos >= 0L ? 1 : 0) + hitCostWarmSamples;
    }

    /** Test seam: the cost meter's state as {@code {firstNanos, warmSamples, warmNanos, maxNanos,
     *  maxAt}} (the values the cost line prints, unrounded). */
    long[] hitCostStatsForTest() {
        return new long[] {hitCostFirstNanos, hitCostWarmSamples, hitCostWarmNanos, hitCostMaxNanos, hitCostMaxAt};
    }

    private static Method accessible(Method m) {
        m.setAccessible(true);
        return m;
    }
}
