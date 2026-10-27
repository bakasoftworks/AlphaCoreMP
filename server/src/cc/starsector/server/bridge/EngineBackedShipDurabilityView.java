package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipDurabilityView;

/**
 * Immutable {@link ShipDurabilityView} snapshot read from the live engine bridge.
 */
public final class EngineBackedShipDurabilityView implements ShipDurabilityView {

    private final long shipId;
    private final float hitpoints;
    private final float hullLevel;
    private final float armorLevel;
    private final int armorCellCount;
    private final float armorTotal;
    private final long armorGridHash;
    private final byte[] armorCells;

    /** Cell-less convenience (fakes/tests): no retained cells, {@code armorCellsOrNull() == null}. */
    public EngineBackedShipDurabilityView(
            long shipId,
            float hitpoints,
            float hullLevel,
            float armorLevel,
            int armorCellCount,
            float armorTotal,
            long armorGridHash) {
        this(shipId, hitpoints, hullLevel, armorLevel, armorCellCount, armorTotal, armorGridHash, null);
    }

    public EngineBackedShipDurabilityView(
            long shipId,
            float hitpoints,
            float hullLevel,
            float armorLevel,
            int armorCellCount,
            float armorTotal,
            long armorGridHash,
            byte[] armorCells) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (armorCellCount < 0) {
            throw new IllegalArgumentException("armorCellCount must be >= 0; got " + armorCellCount);
        }
        if (armorCells != null && armorCells.length != armorCellCount) {
            throw new IllegalArgumentException("armorCells length " + armorCells.length
                    + " != armorCellCount " + armorCellCount);
        }
        requireFinite("hitpoints", hitpoints);
        requireFinite("hullLevel", hullLevel);
        requireFinite("armorLevel", armorLevel);
        requireFinite("armorTotal", armorTotal);
        this.shipId = shipId;
        this.hitpoints = hitpoints;
        this.hullLevel = hullLevel;
        this.armorLevel = armorLevel;
        this.armorCellCount = armorCellCount;
        this.armorTotal = armorTotal;
        this.armorGridHash = armorGridHash;
        this.armorCells = armorCells;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public float hitpoints() {
        return hitpoints;
    }

    @Override
    public float hullLevel() {
        return hullLevel;
    }

    @Override
    public float armorLevel() {
        return armorLevel;
    }

    @Override
    public int armorCellCount() {
        return armorCellCount;
    }

    @Override
    public float armorTotal() {
        return armorTotal;
    }

    @Override
    public long armorGridHash() {
        return armorGridHash;
    }

    /** Aliased retainer buffer, valid for the capture tick ({@link ShipDurabilityView} law). */
    @Override
    public byte[] armorCellsOrNull() {
        return armorCells;
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
