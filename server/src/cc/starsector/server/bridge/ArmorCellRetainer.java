package cc.starsector.server.bridge;

import java.util.IdentityHashMap;

/**
 *
 * Hash-gated retention of the quantized armor
 * cells beside the per-tick grid walk. {@link EngineCombatBinding#armorGridSnapshot} already
 * visits every cell and folds an FNV hash; this collaborator keeps, per LIVE grid identity
 * ({@code ArmorGridAPI.getGrid()} returns the live {@code float[][]}), the u8-quantized cell
 * image and re-quantizes ONLY when the hash moved — the sole sim-thread addition the design
 * pays (the frame-loop residency law: warm-vs-first cost is MEASURED here before anything
 * else relocates; no ServeBootWarm leg until that read says one is needed).
 *
 * <p><b>Quantize law (both trees must match — 26/26 pilot-codec discipline):</b>
 * {@code q = round(clamp01(cell / maxInCell) · 255)}; 255 ⇔ {@code maxInCell} exactly; a
 * zero/negative {@code maxInCell} grid has no pool and quantizes all-zero. <b>Ordinal law:</b>
 * cell ordinal = {@code x·h + y} with {@code x} the OUTER {@code float[][]} index — exactly
 * the walk/hash visit order, so {@code q[i]} is the i-th visited cell.
 *
 * <p><b>Aliasing:</b> {@link #retain} returns the live retained buffer (no per-tick copy).
 * It is stable until the grid's next hash move; cross-tick consumers copy, never keep.
 * Serve-thread only — no synchronization.
 *
 * <p><b>Instrument rows (B0):</b> one {@code armor-cells spawn entity=<posId> cellCount=<n>
 * maxInCell=<f>} row per retained grid (closes the cellCount GAPs the design listed), and
 * {@code armor-cells cost first_us=<f> warm_us=<f> n=<samples>} at n ∈ {20, 100, 600}
 * (interim prints so a short stand still yields a reading; the 600-row is the binding one).
 */
public final class ArmorCellRetainer {

    /** Bounded memory across round churn: overflow clears the map (re-retained next tick). */
    public static final int MAX_RETAINED_GRIDS = 1024;

    private static final int[] COST_PRINT_AT = { 20, 100, 600 };

    private final IdentityHashMap<float[][], Retained> byGrid = new IdentityHashMap<>();

    private long firstQuantizeNanos = -1L;
    private long warmNanosTotal;
    private int warmSamples;
    private int nextCostPrintIdx;

    /**
     * Returns the retained quantized cells for this grid, re-quantizing only when
     * {@code hash} differs from the grid's last retained hash.
     *
     * @param grid      the live engine grid (identity key; dims immutable per identity)
     * @param cellCount total cells as counted by the caller's walk over the same grid
     * @param maxInCell engine per-cell max (every cell mints at it)
     * @param hash      the walk's FNV over dims + cell bits (the free dirty signal)
     * @param posId     position shipId (1..N) for the once-per-grid spawn row
     */
    public byte[] retain(float[][] grid, int cellCount, float maxInCell, long hash, long posId) {
        Retained r = byGrid.get(grid);
        if (r == null) {
            if (byGrid.size() >= MAX_RETAINED_GRIDS) {
                byGrid.clear();
                System.out.println("armor-cells retained-grid cap " + MAX_RETAINED_GRIDS
                        + " hit; map cleared");
            }
            r = new Retained(new byte[cellCount]);
            byGrid.put(grid, r);
            long startedAt = System.nanoTime();
            quantize(grid, maxInCell, r.q);
            recordSample(System.nanoTime() - startedAt);
            r.lastHash = hash;
            System.out.println("armor-cells spawn entity=" + posId
                    + " cellCount=" + cellCount + " maxInCell=" + maxInCell);
        } else if (hash != r.lastHash) {
            long startedAt = System.nanoTime();
            quantize(grid, maxInCell, r.q);
            recordSample(System.nanoTime() - startedAt);
            r.lastHash = hash;
        }
        return r.q;
    }

    /** Contract/telemetry accessor. */
    public int retainedGridCount() {
        return byGrid.size();
    }

    /** The quantize law, walk order = the hash walk (outer x, inner y). */
    public static void quantize(float[][] grid, float maxInCell, byte[] out) {
        if (maxInCell <= 0f) {
            java.util.Arrays.fill(out, (byte) 0);
            return;
        }
        int i = 0;
        for (float[] row : grid) {
            for (float value : row) {
                float frac = value / maxInCell;
                if (!(frac > 0f)) {
                    frac = 0f;
                } else if (frac > 1f) {
                    frac = 1f;
                }
                out[i++] = (byte) Math.round(frac * 255f);
            }
        }
    }

    private void recordSample(long nanos) {
        if (firstQuantizeNanos < 0L) {
            firstQuantizeNanos = nanos;
            return;
        }
        warmNanosTotal += nanos;
        warmSamples++;
        if (nextCostPrintIdx < COST_PRINT_AT.length && warmSamples == COST_PRINT_AT[nextCostPrintIdx]) {
            nextCostPrintIdx++;
            System.out.println("armor-cells cost first_us=" + tenthsUs(firstQuantizeNanos)
                    + " warm_us=" + tenthsUs(warmNanosTotal / warmSamples)
                    + " n=" + warmSamples);
        }
    }

    /** Locale-free one-decimal microseconds. */
    private static double tenthsUs(long nanos) {
        return (nanos / 100L) / 10.0;
    }

    private static final class Retained {
        final byte[] q;
        long lastHash;

        Retained(byte[] q) {
            this.q = q;
        }
    }
}
