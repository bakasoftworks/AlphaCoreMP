package cc.starsector.server.runtime;

/**
 * The {@code intent_take_age_frames} round-line meter —
 * how the serve loop's FRESH intent takes split across the frame. A sample = a fresh deposit
 * taken at {@code PilotServeSession}'s pre-step (j) site AND staged (the seat held a mounted
 * hull); refused-unmounted takes, pristine re-takes ({@code PeerSession.takeDepositedIntentOrNull}
 * returns the restage) and the round-start gate's take are not sampled.
 *
 * <p>{@code age = applyTick - (R + 1)}, R = the DOWN stamp ({@code PeerSession.latestStampTick})
 * the door captured at DEPOSIT time on the reader thread. With the pre-step take
 * a deposit that lands in the frame's sleep reads age 0 (applied at R+1); a deposit that lands during the
 * step/detect/compose window reads age 1. This is a TAKE age, not an apply age: it cannot see a
 * revert of the take site by construction (a post-fan take also reads age 0/1) — the site is
 * pinned by a source row, not by this meter.
 *
 * <p>Buckets {@code a0/a1/a2p/oob}: {@code oob} = R is {@code NO_STAMP} (a deposit before the
 * session's first DOWN fan) or {@code R >= applyTick} (a cross-round carry: the old round's tick
 * against the new round's restarted clock). A RESUME takeover does not carry the deposit stamp
 * (only {@code latestStampTick}), so a takeover's first sample may read the predecessor's frozen
 * stamp and land in {@code a2p} — one sample per takeover, accepted. Reset per round
 * in {@code mintRound}; the walk-away receipt shares {@code roundCounterTail}, so the token rides
 * it too. Sim thread only (counters, no per-tick row).
 */
final class IntentTakeAge {

    /** Sentinel for "the last take returned no FRESH deposit" (null or a pristine re-take); never
     * a real DOWN stamp (stamps are ticks &ge; 0 or {@code NO_STAMP}). */
    static final long NOT_FRESH = Long.MAX_VALUE;

    private final long[] counts = new long[4];

    /** Pure bucket law: -1 = no sample; 0/1/2 = age 0 / 1 / &ge; 2; 3 = out of bounds. The
     * {@code NO_STAMP} guard runs BEFORE any subtraction ({@code applyTick - (MIN_VALUE + 1)}
     * overflows positive and would read as a2p). */
    static int bucket(long applyTick, long depositStamp) {
        if (depositStamp == NOT_FRESH) {
            return -1;
        }
        if (depositStamp == PilotIntentDatagramServer.NO_STAMP || depositStamp >= applyTick) {
            return 3;
        }
        long age = applyTick - (depositStamp + 1);
        return age == 0L ? 0 : (age == 1L ? 1 : 2);
    }

    void sample(long applyTick, long depositStamp) {
        int b = bucket(applyTick, depositStamp);
        if (b >= 0) {
            counts[b]++;
        }
    }

    void reset() {
        java.util.Arrays.fill(counts, 0L);
    }

    String token() {
        return " intent_take_age_frames=" + counts[0] + "/" + counts[1] + "/" + counts[2]
                + "/" + counts[3];
    }
}
