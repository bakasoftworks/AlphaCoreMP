package cc.starsector.server.runtime;

/**
 * The decoded payload of one explicit next-round RE-ENTRY request
 * ({@link MatchReentryFrameCodec}, {@link cc.starsector.server.protocol.MatchReentryControlMessage}).
 *
 * <p>The up-channel complement of {@link MatchResetSignal}: a client sends this after it has
 * torn down the round that ended at roster-0 and the human has re-entered, to tell the server
 * "deploy the next round". {@code reentrySeq} is the 1-based ordinal of this re-entry within
 * the connection (the first re-entry is 1, and is expected to match the {@code resetSeq} of
 * the {@link MatchResetSignal} it answers); {@code completedRoundIndex} / {@code nextRoundIndex}
 * bracket it (the 0-based round that just ended, and the fresh round the client is asking the
 * server to start on the SAME socket).
 */
public record MatchReentrySignal(int reentrySeq, int completedRoundIndex, int nextRoundIndex) {

    public MatchReentrySignal {
        if (reentrySeq < 1) {
            throw new IllegalArgumentException("reentrySeq must be >= 1 (the first re-entry is 1); got " + reentrySeq);
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
