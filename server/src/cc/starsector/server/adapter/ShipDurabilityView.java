package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's durability state at a simulation step.
 *
 * <p>The engine objects, armor-grid implementation, and any internal classes stay
 * inside the bridge. This view carries only scalar durability facts that can be
 * mixed into authoritative snapshot state.
 */
public interface ShipDurabilityView {

    /** Stable ship identity in the same repository order as {@link ShipMotionView}. */
    long shipId();

    /** Current hull hitpoints. */
    float hitpoints();

    /** Current hull level fraction. */
    float hullLevel();

    /**
     * Current armor pool as a fraction of its maximum:
     * {@code armorTotal / (armorCellCount × ArmorGridAPI.getMaxArmorInCell())}, or 0
     * when the ship has no armor cells. Computed bridge-side because
     * only the bridge holds the per-cell max; the denominator is engine-exact on both
     * sides — every cell mints at {@code getMaxArmorInCell()} — so a client holding the
     * same grid derives the identical fraction. Arrives pre-normalized like
     * {@link #hullLevel()}.
     */
    float armorLevel();

    /** Number of armor cells included in the armor snapshot. */
    int armorCellCount();

    /** Sum of all armor-cell values in the grid. */
    float armorTotal();

    /** Deterministic hash of armor grid dimensions and cell values. */
    long armorGridHash();

    /**
     * Quantized per-cell armor image (D-FOEARMOR), or {@code null} when the
     * source does not retain cells (fakes/tests — the default). Length =
     * {@link #armorCellCount()}; value law {@code round(clamp01(cell/maxInCell)·255)}, ordinal
     * = {@code x·h + y} with x the outer grid index ({@code ArmorCellRetainer} is the law's
     * home). The array is the retainer's LIVE buffer, valid for the capture tick — consumers
     * copy, never keep.
     */
    default byte[] armorCellsOrNull() {
        return null;
    }
}
