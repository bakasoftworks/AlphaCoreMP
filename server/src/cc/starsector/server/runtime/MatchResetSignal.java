package cc.starsector.server.runtime;

/**
 * The decoded payload of one explicit {@code MATCH_RESET} control frame
 * ({@link MatchResetFrameCodec}, {@link cc.starsector.server.protocol.MatchResetControlMessage}).
 *
 * <p>Carries just enough for a predict client to act on the inline next-round boundary
 * without inferring it from snapshot side-effects: {@code resetSeq} is the 1-based ordinal
 * of this reset within the connection (the first reset is 1), and
 * {@code completedRoundIndex} / {@code nextRoundIndex} bracket it (the 0-based round that
 * just ended at roster-0, and the fresh round whose frames follow on the SAME socket). The
 * predict client decodes this to tear down + rebuild its local combat at exactly the right
 * point in the stream.
 */
public record MatchResetSignal(int resetSeq, int completedRoundIndex, int nextRoundIndex) {

    public MatchResetSignal {
        if (resetSeq < 1) {
            throw new IllegalArgumentException("resetSeq must be >= 1 (the first reset is 1); got " + resetSeq);
        }
        if (completedRoundIndex < 0) {
            throw new IllegalArgumentException("completedRoundIndex must be >= 0; got " + completedRoundIndex);
        }
        if (nextRoundIndex != completedRoundIndex + 1) {
            throw new IllegalArgumentException(
                    "nextRoundIndex must be completedRoundIndex + 1; got completed=" + completedRoundIndex
                            + " next=" + nextRoundIndex);
        }
    }
}
