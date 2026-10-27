package cc.starsector.client.net;

import java.util.List;

/**
 * One decoded {@code ASTP} batched asteroid frame: the server keeper's
 * rows at {@code simTick} (1..{@link ClientAsteroidFrameReader#MAX_ROWS} rows -- wire-driven
 * allocation stays capped). {@code roundEpoch} is NOT on the wire: the
 * reader constructs the frame with epoch 0 and {@code PredictReconcileDatagramSession} re-wraps it
 * with its net-thread MRST epoch at intake, so the render-side {@code AsteroidLane} can tell a
 * pre-reset frame from a post-reset one without trusting its own drain timing.
 *
 */
public record ClientAsteroidFrame(long simTick, List<ClientAsteroidRow> rows, int roundEpoch) {

    public ClientAsteroidFrame {
        // fail-closed frame law (rowCount 1..29, simTick >= 0).
        if (simTick < 0L) {
            throw new IllegalArgumentException("asteroid frame simTick negative: " + simTick);
        }
        if (rows == null) {
            throw new IllegalArgumentException("asteroid frame rows null");
        }
        if (rows.size() < 1 || rows.size() > ClientAsteroidFrameReader.MAX_ROWS) {
            throw new IllegalArgumentException("asteroid frame row count invalid: " + rows.size());
        }
        rows = List.copyOf(rows); // immutable + null-element refusal (NPE on a null row)
    }

    /**
     * The rows a queue-cap drop must NEVER lose: DESTROYED / EXPIRED. A death row is sent ONCE and
     * never re-sent (the keeper's REFRESH re-sends ALIVE only, one body per refresh tick), so a
     * death row dropped at the {@code PredictReconcileDatagramSession} intake cap left a permanent
     * colliding ghost twin on this seat. A
     * proposed alternative, a fixed orphan-expiry window, is wrong for a round-robin REFRESH and is not built.
     * Same tick + epoch; {@code null} when the frame carries no death row.
     */
    public ClientAsteroidFrame deathRowsOnly() {
        List<ClientAsteroidRow> deaths = null;
        for (ClientAsteroidRow r : rows) {
            if (r.kind() != ClientAsteroidRow.KIND_ALIVE) {
                if (deaths == null) {
                    deaths = new java.util.ArrayList<>(4);
                }
                deaths.add(r);
            }
        }
        return deaths == null ? null : new ClientAsteroidFrame(simTick, deaths, roundEpoch);
    }
}
