package cc.starsector.client.net;

/**
 * One decoded {@code AIMP} batched pilot-aim control frame
 * (remote turret aim): the server's per-due-tick fan of every piloted seat's live aim WORLD
 * point, quantized on the fixed-point 1/8-world-unit grid. Rows ride parallel arrays sized by
 * the wire row count (bounded — {@link ClientPilotAimReader#MAX_ROWS}; wire-driven allocation
 * stays capped). Row law re-checked here at construction (positive ship
 * wire id, |q| plausibility) — the {@code PilotAimFrameCodec.PilotAimRow} mirror; the reader
 * wraps a violation as {@link java.io.IOException}.
 */
public final class ClientPilotAimFrame {

    private final long[] shipWireIds;
    private final int[] qx;
    private final int[] qy;

    ClientPilotAimFrame(long[] shipWireIds, int[] qx, int[] qy) {
        if (shipWireIds == null || qx == null || qy == null
                || shipWireIds.length != qx.length || shipWireIds.length != qy.length) {
            throw new IllegalArgumentException("pilot-aim row arrays null or length-skewed");
        }
        int rows = shipWireIds.length;
        if (rows < 1 || rows > ClientPilotAimReader.MAX_ROWS) {
            throw new IllegalArgumentException("pilot-aim row count invalid: " + rows);
        }
        for (int i = 0; i < rows; i++) {
            if (shipWireIds[i] < 1L) {
                throw new IllegalArgumentException("pilot-aim shipWireId must be positive: "
                        + shipWireIds[i]);
            }
            if (Math.abs(qx[i]) > ClientPilotAimReader.MAX_QUANT_ABS
                    || Math.abs(qy[i]) > ClientPilotAimReader.MAX_QUANT_ABS) {
                throw new IllegalArgumentException("pilot-aim point implausible: qx=" + qx[i]
                        + " qy=" + qy[i]);
            }
        }
        this.shipWireIds = shipWireIds;
        this.qx = qx;
        this.qy = qy;
    }

    public int rowCount() {
        return shipWireIds.length;
    }

    public long shipWireId(int row) {
        return shipWireIds[row];
    }

    /** Row {@code row}'s aim world X — de-quantized ({@code q / 8f} is exact in binary). */
    public float worldX(int row) {
        return qx[row] / (float) ClientPilotAimReader.GRID_STEPS_PER_UNIT;
    }

    /** Row {@code row}'s aim world Y — de-quantized ({@code q / 8f} is exact in binary). */
    public float worldY(int row) {
        return qy[row] / (float) ClientPilotAimReader.GRID_STEPS_PER_UNIT;
    }

    @Override
    public String toString() {
        return "ClientPilotAimFrame{rows=" + shipWireIds.length + "}";
    }
}
