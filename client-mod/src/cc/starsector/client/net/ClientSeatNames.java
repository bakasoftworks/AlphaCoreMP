package cc.starsector.client.net;

/**
 * The decoded payload of one {@code SNMS} seat-name table the server streams down ONCE after both
 * seats are known (the seat-name handshake): the client-side analogue of the server's
 * {@code SeatNameFrameCodec.SeatNames}. Seat 1 = shipId 1, seat 2 = shipId 2 (the deploy-order id
 * contract). Either name may be the empty-string "no override" sentinel (that seat announced no
 * pick and the server had no config fallback) — the receiver keeps its own local fallback chain
 * for that seat.
 *
 * <p>Like its {@code MRST} sibling {@link ClientMatchReset}, this is a small client-owned mirror —
 * the mod deliberately does not bundle the server's record (its codec statics drag
 * {@code NetworkMessageRegistry} into the game JVM; see {@link SeatNameWriter}). The game-free
 * cross-check round-trips real server
 * {@code SeatNameFrameCodec} bytes through {@link ClientSeatNamesReader} and asserts this value
 * matches, keeping the two mirrors in sync.
 */
public final class ClientSeatNames {

    private final String seat1Name;
    private final String seat2Name;

    /**
     * @throws IllegalArgumentException on a null name (the wire sentinel for "no name" is the
     *     EMPTY string, never null). The reader translates this to an {@link java.io.IOException}
     *     so a bad frame fails closed like every other wire decode.
     */
    public ClientSeatNames(String seat1Name, String seat2Name) {
        if (seat1Name == null || seat2Name == null) {
            throw new IllegalArgumentException("seat names must be non-null (\"\" = the no-override sentinel); got seat1="
                    + seat1Name + " seat2=" + seat2Name);
        }
        this.seat1Name = seat1Name;
        this.seat2Name = seat2Name;
    }

    /** Seat 1's wire name, or {@code ""} when the server carried no name for that seat. */
    public String seat1Name() {
        return seat1Name;
    }

    /** Seat 2's wire name, or {@code ""} when the server carried no name for that seat. */
    public String seat2Name() {
        return seat2Name;
    }

    /** The wire name for a seat shipId (1 or 2), or {@code ""} for any other id / no name. */
    public String nameForSeat(long seatShipId) {
        if (seatShipId == 1L) {
            return seat1Name;
        }
        if (seatShipId == 2L) {
            return seat2Name;
        }
        return "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientSeatNames other)) {
            return false;
        }
        return seat1Name.equals(other.seat1Name) && seat2Name.equals(other.seat2Name);
    }

    @Override
    public int hashCode() {
        return 31 * seat1Name.hashCode() + seat2Name.hashCode();
    }

    @Override
    public String toString() {
        return "ClientSeatNames{seat1='" + seat1Name + "',seat2='" + seat2Name + "'}";
    }
}
