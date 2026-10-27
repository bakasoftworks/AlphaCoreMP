package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * The door's REJECT-print sampler
 * — at most one {@code pilot-datagram-admission
 * REJECT} line per second per REASON; the next printed line of a reason carries
 * {@code suppressed=N} (the count withheld since its last print). The REJECT REPLY is never
 * sampled (one per answered guess — the ledger paces those); only the log line is.
 *
 * <p>Reasons are SERVER constants ({@code DoorRejectReasons} + {@code kit_outdated}), never wire
 * text; more than {@code maxReasons} distinct keys fold into {@value #OTHER} (the table is bounded
 * whatever the caller passes). Pure, clock-injected, NOT thread-safe: the endpoint uses it only
 * under its admission lock. Memory ≈ a few KB at the {@link #forDoor()} bound. No knob.
 */
public final class DoorLogSampler {

    /** The door's print interval per reason. */
    public static final long DOOR_MIN_INTERVAL_MILLIS = 1_000L;
    /** The door's distinct-reason bound (beyond it everything reads {@value #OTHER}). */
    public static final int DOOR_MAX_REASONS = 32;
    /** The overflow bucket's key. */
    public static final String OTHER = "other";

    private static final class ReasonState {
        boolean printed;
        long lastPrintMillis;
        long suppressed;
        long total;
    }

    private final long minIntervalMillis;
    private final int maxReasons;
    private final Map<String, ReasonState> reasons = new HashMap<>();
    private long suppressedTotal;

    /** The door sampler (1 000 ms, 32 reasons). */
    public static DoorLogSampler forDoor() {
        return new DoorLogSampler(DOOR_MIN_INTERVAL_MILLIS, DOOR_MAX_REASONS);
    }

    /** Explicit bounds (contract rows);
     * {@code minIntervalMillis >= 1}, {@code maxReasons >= 1}. */
    public DoorLogSampler(long minIntervalMillis, int maxReasons) {
        if (minIntervalMillis < 1L || maxReasons < 1) {
            throw new IllegalArgumentException("log sampler bounds: minIntervalMillis >= 1 and"
                    + " maxReasons >= 1; got " + minIntervalMillis + "/" + maxReasons);
        }
        this.minIntervalMillis = minIntervalMillis;
        this.maxReasons = maxReasons;
    }

    /** Counts one REJECT of {@code reason};
     * true ⇒ print it now (then {@link #takeSuppressed} for the tail), false ⇒ it was suppressed.
     * A backwards clock prints (and re-stamps) — never a stuck reason. */
    public boolean shouldPrint(String reason, long nowMillis) {
        ReasonState st = stateFor(reason);
        st.total++;
        if (!st.printed || nowMillis - st.lastPrintMillis >= minIntervalMillis
                || nowMillis < st.lastPrintMillis) {
            st.printed = true;
            st.lastPrintMillis = nowMillis;
            return true;
        }
        st.suppressed++;
        suppressedTotal++;
        return false;
    }

    /** The count suppressed since the reason's
     * last print (0 if none), reset to 0. */
    public long takeSuppressed(String reason) {
        ReasonState st = reasons.get(bucketOf(reason));
        if (st == null) {
            return 0L;
        }
        long n = st.suppressed;
        st.suppressed = 0L;
        return n;
    }

    /** Lines suppressed over the sampler's life (the door line's {@code reject_log_suppressed=}). */
    public long suppressedTotal() {
        return suppressedTotal;
    }

    /** REJECTs counted for {@code reason} (its bucket), printed or not. */
    public long total(String reason) {
        ReasonState st = reasons.get(bucketOf(reason));
        return st == null ? 0L : st.total;
    }

    /** Distinct reason keys held (≤ maxReasons + 1 incl. {@value #OTHER}). */
    public int trackedReasons() {
        return reasons.size();
    }

    public long minIntervalMillis() {
        return minIntervalMillis;
    }

    public int maxReasons() {
        return maxReasons;
    }

    private String bucketOf(String reason) {
        if (reason == null || reason.isEmpty()) {
            return OTHER;
        }
        if (reasons.containsKey(reason)) {
            return reason;
        }
        int distinct = reasons.containsKey(OTHER) ? reasons.size() - 1 : reasons.size();
        return distinct >= maxReasons ? OTHER : reason;
    }

    private ReasonState stateFor(String reason) {
        return reasons.computeIfAbsent(bucketOf(reason), k -> new ReasonState());
    }
}
