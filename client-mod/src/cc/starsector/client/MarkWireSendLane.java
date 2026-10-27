package cc.starsector.client;

import cc.starsector.client.net.MarkWriter;
import cc.starsector.client.net.PredictSession;
import java.util.function.Supplier;

/**
 * F9 wire-MARK send lane: every
 * F9 press ALSO reaches the SERVER log as one {@code MARK} CTRL frame (count + client tick, no
 * text), so a public stranger's mark lands where we can read it even when their sidelog never
 * reaches us and a {@code logs=off} player's {@code PilotWatchLog} sits at OFF.
 *
 * <p>Own file per NEW-LANE-LAW (PRCP sits 11 nb under its WarnNb): the plugin's F9 branch calls
 * {@link #send} once per press; everything else — the ≤1/s client-side mirror of the server's
 * per-seat rate law (a conforming client never trips the server's drop counter), the
 * {@code Long.MIN_VALUE}→{@code -1} no-snapshot sentinel map, fail-soft transport errors
 * (a session without a MARK lane, or a dead CTRL window, must never break the press's local
 * row/floaty), and the bounded diagnostics — lives here.
 *
 * <p>Meters: {@link #markTx} (frames handed to the session), {@link #markRateSkipped} (presses
 * inside the 1 s window — the local row + floaty still fired; the server row's {@code #k}
 * simply skips numbers), {@link #markSendFailed} (transport refusals, first {@value
 * #FAIL_LOG_MAX} logged).
 */
final class MarkWireSendLane {

    /** Client-side mirror of the server's per-seat mark rate law (policy: ≤1 mark per second). */
    static final long MIN_SEND_INTERVAL_MILLIS = 1_000L;
    /** Bounded diagnostics: log the first N transport refusals, count the rest. */
    static final int FAIL_LOG_MAX = 3;

    /** Game-free by construction (the verifier compiles this file): the session is supplied, not the plugin. */
    private final Supplier<PredictSession> session;
    private long lastSendMillis; // 0 = never sent
    int markTx;
    int markRateSkipped;
    int markSendFailed;

    MarkWireSendLane(Supplier<PredictSession> session) {
        this.session = session;
    }

    /**
     * One F9 press: hand a {@code MARK} frame to the session unless inside the 1 s window.
     * {@code count} = the plugin's running {@code ownerMarkers} (≥1); {@code lastSnapshotSimTick}
     * may be {@code Long.MIN_VALUE} (no snapshot yet) — mapped to the wire's {@code -1}.
     * Fail-soft: any transport refusal is counted (+ first few logged), never thrown.
     */
    void send(int count, long lastSnapshotSimTick) {
        long now = System.currentTimeMillis();
        if (lastSendMillis != 0L && now - lastSendMillis < MIN_SEND_INTERVAL_MILLIS) {
            markRateSkipped++;
            return;
        }
        long tick = lastSnapshotSimTick == Long.MIN_VALUE
                ? MarkWriter.NO_SNAPSHOT_TICK : Math.max(MarkWriter.NO_SNAPSHOT_TICK, lastSnapshotSimTick);
        try {
            session.get().sendOwnerMark(Math.min(count, 0xFFFF), tick);
            lastSendMillis = now;
            markTx++;
        } catch (Throwable t) {
            markSendFailed++;
            if (markSendFailed <= FAIL_LOG_MAX) {
                PilotWatchLog.info("owner-marker wire send failed #" + count + ": " + t
                        + (markSendFailed == FAIL_LOG_MAX ? " [log cap reached; mark_send_failed continues]" : ""));
            }
        }
    }

    /** One census token for the round line: {@code mark_tx=/mark_rate_skipped=/mark_send_failed=}. */
    String censusTokens() {
        return " mark_tx=" + markTx + " mark_rate_skipped=" + markRateSkipped
                + " mark_send_failed=" + markSendFailed;
    }
}
