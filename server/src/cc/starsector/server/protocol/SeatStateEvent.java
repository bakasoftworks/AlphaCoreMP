package cc.starsector.server.protocol;

import java.util.Objects;

/**
 * One full-state seat-state event — the payload of the {@code server.seat_state_event} v1 family
 * (wire shape frozen: {@code {simTick i64, seatId u16, occupied u8,
 * shipId i64 (0=NONE), cause u8, seatSeq u32}}; the codec/registry row land).
 * Produced by the {@link cc.starsector.server.match.MatchSeatAuthority} seat-machine
 * drain on the sim thread — single-writer simTick stamp + seatSeq mint.
 *
 * <p>Full-state per event (idempotent, replay-safe): {@code occupied}/{@code shipId} are the
 * seat's CURRENT two-axis state at emission, not a delta — a client materializes the seat table
 * by overwriting, ordering per seat by {@code seatSeq}. The four states read off the two axes:
 * (vacant, no hull) = ABSENT, (occupied, hull) = ACTIVE, (vacant, hull) = AFK coast,
 * (occupied, no hull) = PENDING fly-in or dead-latched.
 */
public record SeatStateEvent(
        long simTick, int seatId, boolean occupied, long shipId, SeatStateCause cause, long seatSeq) {

    public SeatStateEvent {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0, got: " + simTick);
        }
        if (seatId < 1 || seatId > 0xFFFF) {
            throw new IllegalArgumentException("seatId must fit u16 and be >= 1, got: " + seatId);
        }
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0 (0 = no hull), got: " + shipId);
        }
        Objects.requireNonNull(cause, "cause");
        if (seatSeq < 0L || seatSeq > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("seatSeq must fit u32, got: " + seatSeq);
        }
    }
}
