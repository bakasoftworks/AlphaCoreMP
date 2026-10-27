package cc.starsector.client.net;

/**
 * The decoded payload of one explicit {@code MATCH_RESET} control frame the server
 * streams inline on the persistent socket between two rounds (L1 session-lifecycle):
 * the client-side analogue of the server's
 * {@code cc.starsector.server.runtime.MatchResetSignal} / {@code MatchResetControlMessage}.
 *
 * <p>The read-only persistent-socket reset left the round boundary IMPLICIT
 * in the entity-count sequence (a 0-count snapshot then a fresh roster). A predict client
 * runs its own local engine + reconcile, so it cannot infer that boundary from snapshot
 * side-effects — it needs the boundary marked EXPLICITLY to tear down its local combat and
 * rebuild for the next round at exactly the right point in the stream. This carries just
 * enough to do that: {@code resetSeq} is the 1-based ordinal of this reset within the
 * connection (the first reset is 1), and {@code completedRoundIndex} / {@code nextRoundIndex}
 * bracket it (the 0-based round that just ended at roster-0, and the fresh round whose frames
 * follow on the SAME socket).
 *
 * <p>Like its despawn sibling {@link ClientEntityDespawn}, this is a small client-owned
 * mirror — the mod deliberately does not bundle the server's {@code MatchResetSignal}
 * record (its codec pulls in {@code DataInputStream}, blocked by the in-game script sandbox;
 * see {@link WireBytes}). It enforces the same field invariants as the server record so a
 * malformed frame fails closed identically; the game-free cross-check
 * streams real server
 * {@code MatchResetFrameCodec} bytes and asserts {@link ClientMatchResetReader} decodes
 * the same signal, keeping the two mirrors in sync.
 *
 * <p>{@link #roundEpoch()} is LOCAL, never on the wire —
 * the datagram session re-wraps the decoded reset with its ctrl round epoch AFTER the per-MRST
 * increment ({@link #withRoundEpoch}), so the roster-append lane retires exactly the epoch this
 * reset opened (by value). The public 3-arg constructor (the reader, the smokes) stays epoch 0;
 * {@code equals}/{@code hashCode}/{@code toString} stay on the three wire fields.
 */
public final class ClientMatchReset {

    private final int resetSeq;
    private final int completedRoundIndex;
    private final int nextRoundIndex;
    private final int roundEpoch;

    /**
     * @throws IllegalArgumentException if the fields violate the server record's invariants
     *     ({@code resetSeq >= 1}, {@code completedRoundIndex >= 0},
     *     {@code nextRoundIndex == completedRoundIndex + 1}). The reader translates this to an
     *     {@link java.io.IOException} so a bad frame fails closed like every other wire decode.
     */
    public ClientMatchReset(int resetSeq, int completedRoundIndex, int nextRoundIndex) {
        this(resetSeq, completedRoundIndex, nextRoundIndex, 0);
    }

    private ClientMatchReset(int resetSeq, int completedRoundIndex, int nextRoundIndex, int roundEpoch) {
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
        this.resetSeq = resetSeq;
        this.completedRoundIndex = completedRoundIndex;
        this.nextRoundIndex = nextRoundIndex;
        this.roundEpoch = roundEpoch;
    }

    /** This reset re-wrapped with the session's ctrl round epoch (intake-only, never on the wire). */
    public ClientMatchReset withRoundEpoch(int epoch) {
        return new ClientMatchReset(resetSeq, completedRoundIndex, nextRoundIndex, epoch);
    }

    /** The ctrl round epoch this reset opened (local; 0 = un-stamped — the reader, the smokes). */
    public int roundEpoch() {
        return roundEpoch;
    }

    /** 1-based ordinal of this reset within the connection (the first reset is 1). */
    public int resetSeq() {
        return resetSeq;
    }

    /** 0-based round that just ended at roster-0. */
    public int completedRoundIndex() {
        return completedRoundIndex;
    }

    /** The fresh round whose frames follow on the same socket; always {@code completedRoundIndex + 1}. */
    public int nextRoundIndex() {
        return nextRoundIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientMatchReset other)) {
            return false;
        }
        return resetSeq == other.resetSeq
                && completedRoundIndex == other.completedRoundIndex
                && nextRoundIndex == other.nextRoundIndex;
    }

    @Override
    public int hashCode() {
        int result = resetSeq;
        result = 31 * result + completedRoundIndex;
        result = 31 * result + nextRoundIndex;
        return result;
    }

    @Override
    public String toString() {
        return "ClientMatchReset{resetSeq=" + resetSeq
                + ",completedRoundIndex=" + completedRoundIndex
                + ",nextRoundIndex=" + nextRoundIndex + "}";
    }
}
