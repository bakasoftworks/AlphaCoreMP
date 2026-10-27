package cc.starsector.client.net;

/**
 * The decoded payload of one {@code SEAT} seat-state event the server streams down (seat-state
 * replication, wire shape frozen): the client-side analogue of
 * the server's {@code SeatStateEvent}. Full-state per event — {@code occupied}/{@code shipId}
 * are the seat's CURRENT two-axis state at emission, not a delta — so a client materializes its
 * seat table by overwriting per {@code seatId}, ordering by {@code seatSeq} (a global monotone
 * u32 mint; per-seat order is monotone). The four states read off the two axes: (vacant, no
 * hull) = ABSENT, (occupied, hull) = ACTIVE, (vacant, hull) = AFK coast, (occupied, no hull) =
 * PENDING fly-in or dead-latched.
 *
 * <p>Like its {@code SNMS} sibling {@link ClientSeatNames}, this is a small client-owned mirror —
 * the mod deliberately does not bundle the server's record (its codec statics drag
 * {@code NetworkMessageRegistry} into the game JVM). The game-free cross-check
 * round-trips real server
 * {@code SeatStateFrameCodec} bytes through {@link ClientSeatStateReader} and asserts this value
 * matches, keeping the two mirrors in sync.
 */
public final class ClientSeatStateEvent {

    /** Frozen {@code cause u8} wire vocabulary — mirrors {@code SeatStateCause} (never renumber). */
    public static final int CAUSE_BASELINE = 0;
    public static final int CAUSE_CLAIM = 1;
    public static final int CAUSE_VACATE_BYE = 2;
    public static final int CAUSE_VACATE_SILENCE = 3;
    public static final int CAUSE_VACATE_DISPLACED = 4;
    public static final int CAUSE_HULL_DEATH = 5;
    public static final int CAUSE_HULL_DEPARTED = 6;
    public static final int CAUSE_REMOUNT = 7;
    /** Highest cause code schema v1 knows; decode fails closed above it. */
    public static final int CAUSE_MAX = CAUSE_REMOUNT;

    private final long simTick;
    private final int seatId;
    private final boolean occupied;
    private final long shipId;
    private final int causeCode;
    private final long seatSeq;

    /**
     * @throws IllegalArgumentException on any field outside the frozen wire bounds (simTick &lt; 0,
     *     seatId outside u16/&lt; 1, shipId &lt; 0, an unknown cause code, seatSeq outside u32). The
     *     reader translates this to an {@link java.io.IOException} so a bad frame fails closed
     *     like every other wire decode.
     */
    public ClientSeatStateEvent(
            long simTick, int seatId, boolean occupied, long shipId, int causeCode, long seatSeq) {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0, got: " + simTick);
        }
        if (seatId < 1 || seatId > 0xFFFF) {
            throw new IllegalArgumentException("seatId must fit u16 and be >= 1, got: " + seatId);
        }
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0 (0 = no hull), got: " + shipId);
        }
        if (causeCode < 0 || causeCode > CAUSE_MAX) {
            throw new IllegalArgumentException("unknown seat state cause wire code: " + causeCode);
        }
        if (seatSeq < 0L || seatSeq > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("seatSeq must fit u32, got: " + seatSeq);
        }
        this.simTick = simTick;
        this.seatId = seatId;
        this.occupied = occupied;
        this.shipId = shipId;
        this.causeCode = causeCode;
        this.seatSeq = seatSeq;
    }

    /** Sim tick the seat machine stamped at drain (single-writer, sim thread). */
    public long simTick() {
        return simTick;
    }

    /** Descriptor seat id (u16, &gt;= 1). */
    public int seatId() {
        return seatId;
    }

    /** The seat's CURRENT occupancy axis at emission. */
    public boolean occupied() {
        return occupied;
    }

    /** The seat's CURRENT mounted hull wire id, or {@code 0} = no hull. */
    public long shipId() {
        return shipId;
    }

    /** The transition (or BASELINE) cause wire code — one of the {@code CAUSE_*} constants. */
    public int causeCode() {
        return causeCode;
    }

    /** Global monotone emission sequence (u32); per-seat order is monotone. */
    public long seatSeq() {
        return seatSeq;
    }

    /** True when this seat currently has a mounted hull ({@code shipId != 0}). */
    public boolean hasHull() {
        return shipId != 0L;
    }

    /** The cause code's frozen name, for logs. */
    public String causeName() {
        switch (causeCode) {
            case CAUSE_BASELINE: return "BASELINE";
            case CAUSE_CLAIM: return "CLAIM";
            case CAUSE_VACATE_BYE: return "VACATE_BYE";
            case CAUSE_VACATE_SILENCE: return "VACATE_SILENCE";
            case CAUSE_VACATE_DISPLACED: return "VACATE_DISPLACED";
            case CAUSE_HULL_DEATH: return "HULL_DEATH";
            case CAUSE_HULL_DEPARTED: return "HULL_DEPARTED";
            case CAUSE_REMOUNT: return "REMOUNT";
            default: return "UNKNOWN_" + causeCode; // unreachable: the constructor rejects it
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientSeatStateEvent other)) {
            return false;
        }
        return simTick == other.simTick && seatId == other.seatId && occupied == other.occupied
                && shipId == other.shipId && causeCode == other.causeCode && seatSeq == other.seatSeq;
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(simTick);
        h = 31 * h + seatId;
        h = 31 * h + Boolean.hashCode(occupied);
        h = 31 * h + Long.hashCode(shipId);
        h = 31 * h + causeCode;
        h = 31 * h + Long.hashCode(seatSeq);
        return h;
    }

    @Override
    public String toString() {
        return "ClientSeatStateEvent{tick=" + simTick + ",seat=" + seatId
                + ",occupied=" + occupied + ",ship=" + shipId
                + ",cause=" + causeName() + ",seq=" + seatSeq + "}";
    }
}
