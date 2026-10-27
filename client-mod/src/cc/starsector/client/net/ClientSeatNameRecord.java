package cc.starsector.client.net;

/**
 * The decoded payload of one {@code SNMR} per-seat name record the N door fans on announce and
 * spools as admission catch-up (N-aware seat names): the client-side analogue of the
 * server's {@code SeatNameFrameCodec.SeatNameRecord}. One record names ONE seat — {@code seatId}
 * is the door's seat ordinal, {@code shipId} the wire id the name renders over. {@code name} may
 * be the empty-string "no name" sentinel in principle, but the server SKIPS sentinel-resolved
 * records (a name is never un-set), so a live frame always carries a
 * name; the consumer treats {@code ""} as a no-op regardless (the design's own empty-name law).
 *
 * <p>Like its {@code SNMS} sibling {@link ClientSeatNames}, this is a small client-owned mirror —
 * the mod deliberately does not bundle the server's record (its codec statics drag
 * {@code NetworkMessageRegistry} into the game JVM; see {@link SeatNameWriter}). The game-free
 * cross-check round-trips real server
 * {@code SeatNameFrameCodec} bytes through {@link ClientSeatNameRecordReader} and asserts this
 * value matches, keeping the two mirrors in sync.
 */
public final class ClientSeatNameRecord {

    private final int seatId;
    private final long shipId;
    private final String name;

    /**
     * @throws IllegalArgumentException on a seatId outside 1..65535, a negative shipId, or a null
     *     name (the wire sentinel for "no name" is the EMPTY string, never null) — the server
     *     record's exact guards. The reader translates this to an {@link java.io.IOException} so
     *     a bad frame fails closed like every other wire decode.
     */
    public ClientSeatNameRecord(int seatId, long shipId, String name) {
        if (seatId < 1 || seatId > 0xFFFF) {
            throw new IllegalArgumentException("seatId must be 1..65535; got " + seatId);
        }
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (name == null) {
            throw new IllegalArgumentException(
                    "name must be non-null (\"\" = the no-name sentinel)");
        }
        this.seatId = seatId;
        this.shipId = shipId;
        this.name = name;
    }

    /** The door's seat ordinal this record names (1..65535). */
    public int seatId() {
        return seatId;
    }

    /** The wire id the name renders over (the record's ship). */
    public long shipId() {
        return shipId;
    }

    /** The seat's resolved display name, or {@code ""} for the no-name sentinel. */
    public String name() {
        return name;
    }

    /** True when the record carries a name (not the {@code ""} sentinel). */
    public boolean hasName() {
        return !name.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientSeatNameRecord other)) {
            return false;
        }
        return seatId == other.seatId && shipId == other.shipId && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        int h = 31 * seatId + Long.hashCode(shipId);
        return 31 * h + name.hashCode();
    }

    @Override
    public String toString() {
        return "ClientSeatNameRecord{seat=" + seatId + ",shipId=" + shipId
                + ",name='" + name + "'}";
    }
}
