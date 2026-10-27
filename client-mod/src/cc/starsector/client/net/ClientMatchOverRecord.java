package cc.starsector.client.net;

/**
 * The decoded payload of one {@code MOVR} serve-end match verdict: the
 * client-side analogue of the server's {@code MatchOverFrameCodec.MatchOverRecord}. The server
 * fans at most ONE per serve — after its round loop exits on a latched matchOver (or an operator
 * end verb), BEFORE the goodbye BYE (the CTRL spool is per-session FIFO, so the verdict rides
 * ahead of the teardown, bounded best-effort). A frame-budget serve end fans NOTHING — no
 * outcome exists, teardown as today.
 *
 * <p>{@code outcome} names the server's ELWSS first-latch-wins verdict (the latch tag STRING
 * stays off the wire — the code map is the server codec's); {@code winnerSide} is the engine
 * FleetSide ordinal 0/1, or -1 = no victor (roster-0, operator end, fail-soft latch) — the
 * consumer maps "winner != own side" to the defeat presentation, so -1 never shows a victory.
 *
 * <p>Like its {@code SNMR}/{@code HKFR} siblings {@link ClientSeatNameRecord} and
 * {@link ClientHulkFractureRecord}, this is a small client-owned mirror — the mod deliberately
 * does not bundle the server's record (its codec statics drag {@code NetworkMessageRegistry}
 * into the game JVM). The game-free cross-check round-trips real server
 * {@code MatchOverFrameCodec} bytes through {@link ClientMatchOverRecordReader} and asserts
 * this value matches, keeping the two mirrors in sync.
 */
public final class ClientMatchOverRecord {

    /** Fortress serve: the attacker wave schedule + live hulls hit zero (DEFEND win).
     * Mirrors {@code MatchOverFrameCodec.OUTCOME_FORTRESS_CLEARED}. */
    public static final int OUTCOME_FORTRESS_CLEARED = 1;
    /** Fortress serve: the defended base hull went hulk (attacker win).
     * Mirrors {@code MatchOverFrameCodec.OUTCOME_BASE_HULK}. */
    public static final int OUTCOME_BASE_HULK = 2;
    /** Stable-roster serve: exactly one fighting side still holds live ships.
     * Mirrors {@code MatchOverFrameCodec.OUTCOME_PER_SIDE}. */
    public static final int OUTCOME_PER_SIDE = 3;
    /** Stable-roster serve: zero live ships remain anywhere (no victor).
     * Mirrors {@code MatchOverFrameCodec.OUTCOME_ROSTER_0}. */
    public static final int OUTCOME_ROSTER_0 = 4;
    /** Non-fortress wave serve: the wave side's schedule + live hulls hit zero.
     * Mirrors {@code MatchOverFrameCodec.OUTCOME_WAVE_CLEAR}. */
    public static final int OUTCOME_WAVE_CLEAR = 5;
    /** Operator end verbs — a stop, not a victory; {@code winnerSide} stays -1.
     * Mirrors {@code MatchOverFrameCodec.OUTCOME_OPERATOR_END}. */
    public static final int OUTCOME_OPERATOR_END = 6;

    private final long simTick;
    private final int outcome;
    private final int winnerSide;

    /**
     * @throws IllegalArgumentException on any field outside the server record's exact guards
     *     (negative simTick, an outcome outside the 1..6 vocabulary, a winnerSide other than
     *     -1 (none), 0 or 1). The reader translates this to an {@link java.io.IOException} so
     *     a bad frame fails closed like every other wire decode.
     */
    public ClientMatchOverRecord(long simTick, int outcome, int winnerSide) {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        if (outcome < OUTCOME_FORTRESS_CLEARED || outcome > OUTCOME_OPERATOR_END) {
            throw new IllegalArgumentException(
                    "outcome must be 1..6 (the match-over outcome vocabulary); got " + outcome);
        }
        if (winnerSide != -1 && winnerSide != 0 && winnerSide != 1) {
            throw new IllegalArgumentException(
                    "winnerSide must be -1 (none), 0 or 1; got " + winnerSide);
        }
        this.simTick = simTick;
        this.outcome = outcome;
        this.winnerSide = winnerSide;
    }

    /** The server sim tick the verdict latched on. */
    public long simTick() {
        return simTick;
    }

    /** One of the {@code OUTCOME_*} codes (1..6). */
    public int outcome() {
        return outcome;
    }

    /** The winning engine side (FleetSide ordinal 0/1), or -1 = no victor. */
    public int winnerSide() {
        return winnerSide;
    }

    /** A victor exists (0/1) — -1 outcomes (roster-0, operator end) never show a victory. */
    public boolean hasVictor() {
        return winnerSide >= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientMatchOverRecord other)) {
            return false;
        }
        return simTick == other.simTick && outcome == other.outcome
                && winnerSide == other.winnerSide;
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(simTick);
        h = 31 * h + outcome;
        return 31 * h + winnerSide;
    }

    @Override
    public String toString() {
        return "ClientMatchOverRecord{tick=" + simTick + ",outcome=" + outcome
                + ",winner=" + winnerSide + "}";
    }
}
