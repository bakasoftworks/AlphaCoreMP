package cc.starsector.server.protocol;

/**
 * The cause of a seat-state transition — the {@code cause u8} vocabulary of the
 * {@code server.seat_state_event} family (wire shape frozen; the family's
 * message/codec/registry row land). Wire codes are FROZEN — never
 * renumber; new causes append.
 *
 * <p>Seat-machine mapping: {@code CLAIM} = T1 (both the occupancy flip and its
 * fly-in completing), the three {@code VACATE_*} = T2 by door site (BYE / liveness silence /
 * displacement), {@code HULL_DEATH}/{@code HULL_DEPARTED} = T3 mount-loss, {@code REMOUNT} = T4.
 * {@code BASELINE} marks the per-admit full-table burst (and its post-reset re-emission), not a
 * transition.
 */
public enum SeatStateCause {
    BASELINE(0),
    CLAIM(1),
    VACATE_BYE(2),
    VACATE_SILENCE(3),
    VACATE_DISPLACED(4),
    HULL_DEATH(5),
    HULL_DEPARTED(6),
    REMOUNT(7);

    private final int wireCode;

    SeatStateCause(int wireCode) {
        this.wireCode = wireCode;
    }

    /** The frozen {@code cause u8} wire value. */
    public int wireCode() {
        return wireCode;
    }

    /** The cause carrying the frozen wire code, or {@link IllegalArgumentException} (fail-closed decode). */
    public static SeatStateCause fromWireCode(int wireCode) {
        for (SeatStateCause cause : values()) {
            if (cause.wireCode == wireCode) {
                return cause;
            }
        }
        throw new IllegalArgumentException("unknown seat state cause wire code: " + wireCode);
    }
}
