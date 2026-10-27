package cc.starsector.server.match;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import cc.starsector.server.runtime.DoorRejectReasons;

/**
 * password-mode seat ledger: under ONE shared match password, the seat key is the
 * joiner's unique client-instance id ({@code cid}) instead of a per-seat token — this ledger
 * resolves {@code (cid, desiredShipId)} to a descriptor seat, holding the cid→seat claims. It sits
 * BESIDE {@link MatchSeatAuthority} (token mode + the TCP gate keep their contract untouched); the
 * password door (build leg 3) consults this ledger to pick the seat, then drives the existing
 * authority machinery with the resolved seat's own descriptor credential — one occupancy truth.
 *
 * <p>Claim semantics (P3, frozen): {@code desiredShipId} names the descriptor seat wanted
 * ({@code 0} = first free seat in the descriptor's {@link MatchDescriptors#claimOrder}, which is
 * ascending seat-ordinal order on every stock shape); a ship id no descriptor seat
 * carries is {@link Status#SEAT_UNKNOWN}; a seat live-claimed by ANOTHER cid is
 * {@link Status#ALREADY_OCCUPIED} (reason string reused from the token door); no free seat left is
 * {@link Status#MATCH_FULL}. A cid that already holds a seat gets ITS OWN seat back
 * ({@link Status#REJOINED} — the rejoin/takeover lane); a desired-ship mismatch on rejoin is
 * surfaced ({@link Claim#desiredMismatch()}) for the door to log, never fatal.
 *
 * <p>{@link #vacate(String)} rides every door site that vacates the seat authority today (session
 * death / kick / takeover pre-release — build leg 3's call-site sweep), so a password seat frees
 * exactly when a token seat does. Idempotent, like the authority's vacate.
 *
 * <p>Game-free, self-contained, {@code synchronized} like {@link MatchSeatAuthority} (the door's
 * reader thread claims; teardown paths vacate).
 */
public final class PasswordSeatLedger {

    /** The outcome kind of a {@link #claim(String, long)}. */
    public enum Status {
        /** A free seat was resolved and is now claimed by this cid. */
        CLAIMED,
        /** This cid already held a seat — that same seat is returned (rejoin/takeover lane). */
        REJOINED,
        /** {@code desiredShipId=0} and every descriptor seat is claimed by another cid. */
        MATCH_FULL,
        /** {@code desiredShipId} names a ship no descriptor seat carries. */
        SEAT_UNKNOWN,
        /** The desired seat is live-claimed by another cid. */
        ALREADY_OCCUPIED,
        /** The requested side holds no free claimable seat while the OTHER side still does (both
         *  without one = {@link #MATCH_FULL}). Only a side-filtered {@link #claim(String, long, int)}
         *  mints it.
         *  <p>A verdict for the door's team table only: the table answers it by seating the joiner on the
         *  other side (the cross-fill), so the door never sends its {@code team_full} reason.
         * */
        SIDE_FULL
    }

    /**
     * The result of a {@link #claim(String, long)}: the {@link Status} plus, when admitted
     * ({@code CLAIMED}/{@code REJOINED}), the resolved descriptor seat. For rejections the seat is
     * null and {@link #rejectionReason()} carries the minted machine reason.
     */
    public record Claim(Status status, MatchDescriptor.Seat seat, boolean desiredMismatch) {
        public Claim {
            Objects.requireNonNull(status, "status");
            // From the parameters, not admitted() — record fields assign AFTER this body runs.
            boolean admits = status == Status.CLAIMED || status == Status.REJOINED;
            if (admits == (seat == null)) {
                throw new IllegalArgumentException(
                        "admitted claims carry their seat; rejections carry none — got status="
                                + status + " seat=" + seat);
            }
        }

        /** True iff this claim resolved a seat ({@code CLAIMED} or {@code REJOINED}). */
        public boolean admitted() {
            return status == Status.CLAIMED || status == Status.REJOINED;
        }

        /** The minted machine reason for a rejection ({@code match_full} / {@code seat_unknown} /
         * {@code seat_already_occupied} — the last reused from the token door; {@code team_full} for
         * {@link Status#SIDE_FULL}, kept as vocabulary though the door never sends it), null when
         * admitted. */
        public String rejectionReason() {
            return switch (status) {
                case CLAIMED, REJOINED -> null;
                case MATCH_FULL -> DoorRejectReasons.MATCH_FULL;
                case SEAT_UNKNOWN -> DoorRejectReasons.SEAT_UNKNOWN;
                case ALREADY_OCCUPIED -> DoorRejectReasons.SEAT_ALREADY_OCCUPIED;
                case SIDE_FULL -> DoorRejectReasons.TEAM_FULL;
            };
        }
    }

    /** The first-free walk: {@link MatchDescriptors#claimOrder} computed ONCE at construction
     *  (round-robin across a side's hull groups; ascending seat id on every stock shape).
     * */
    private final List<MatchDescriptor.Seat> claimOrder;
    private final Map<Long, MatchDescriptor.Seat> seatsByShipId =
            new HashMap<Long, MatchDescriptor.Seat>();
    private final Map<String, MatchDescriptor.Seat> claimsByCid =
            new HashMap<String, MatchDescriptor.Seat>();
    private final Map<Integer, String> cidBySeatId = new HashMap<Integer, String>();
    /** D-SEATRACE: ships a first-free claim never resolves — held
     * for the client that PINS them by explicit ship id (empty = every seat auto-assignable). */
    private final Set<Long> reservedShipIds;
    private final MatchDescriptor descriptor;

    public PasswordSeatLedger(MatchDescriptor descriptor) {
        this(descriptor, Set.of());
    }

    /**
     * D-SEATRACE: {@code reservedShipIds} = descriptor ships a
     * first-free ({@code desiredShipId=0}) claim SKIPS. The seat a local/host client pins by
     * explicit ship id is held for it at the door, so an AUTO joiner that merely dials first can
     * never take it — the race: the kit joiner seated first on ship 1, then the pinned
     * render client bounced {@code seat_already_occupied} into an empty mission. Explicit pins to
     * a reserved ship claim normally; a reserved id no seat carries fails loud (a typo would
     * otherwise reserve nothing, silently). Server-authoritative by construction: the door
     * decides, never launch order ("launch the joiner later" was rejected as the only fix).
     */
    public PasswordSeatLedger(MatchDescriptor descriptor, Set<Long> reservedShipIds) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(reservedShipIds, "reservedShipIds");
        for (MatchDescriptor.Seat seat : descriptor.seats()) {
            // The descriptor constructor guarantees distinct seat ids; ship ids are distinct by
            // the same roster construction (one hull per seat) — fail loud if that ever drifts,
            // a silent overwrite would make desiredShipId resolution ambiguous.
            if (seatsByShipId.put(seat.shipId(), seat) != null) {
                throw new IllegalArgumentException("descriptor carries two seats with ship id "
                        + seat.shipId() + " — desiredShipId resolution requires distinct seat"
                        + " ships");
            }
        }
        for (Long id : reservedShipIds) {
            if (!seatsByShipId.containsKey(id)) {
                throw new IllegalArgumentException("reserved ship id " + id + " names no"
                        + " descriptor seat (seat ships: " + seatsByShipId.keySet()
                        + ") — server.reservedShipIds must name real seats (D-SEATRACE)");
            }
        }
        this.reservedShipIds = Set.copyOf(reservedShipIds);
        this.claimOrder = List.copyOf(MatchDescriptors.claimOrder(descriptor));
        this.descriptor = descriptor;
    }

    /** The descriptor this ledger resolves against (the door's team table reads its seat sides from it).
     * */
    public MatchDescriptor descriptor() {
        return descriptor;
    }

    /** The ships first-free claims skip (D-SEATRACE); empty = none reserved. */
    public Set<Long> reservedShipIds() {
        return reservedShipIds;
    }

    /**
     * The {@code server.reservedShipIds} row grammar: comma-separated positive ship ids, blanks
     * ignored; {@code off}/{@code none} (any case) = empty. Anything else throws — this is boot
     * config, never wire input, so fail loud at the serve open.
     */
    public static Set<Long> parseReservedShipIds(String csv) {
        Set<Long> out = new LinkedHashSet<Long>();
        if (csv == null) {
            return out;
        }
        String whole = csv.trim();
        if (whole.isEmpty() || whole.equalsIgnoreCase("off") || whole.equalsIgnoreCase("none")) {
            return out;
        }
        for (String tok : whole.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(t);
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("server.reservedShipIds: '" + t
                        + "' is not a ship id (comma-separated positive integers, or off)");
            }
            if (id <= 0L) {
                throw new IllegalArgumentException(
                        "server.reservedShipIds: ship id must be > 0, got " + id);
            }
            out.add(id);
        }
        return out;
    }

    /**
     * Resolve {@code (cid, desiredShipId)} to a seat claim — see the class doc for the frozen
     * semantics. {@code cid} must be non-blank; {@code desiredShipId} must be {@code >= 0}
     * ({@code 0} = first-free; the wire field is unsigned, a negative here is a caller bug).
     */
    public synchronized Claim claim(String cid, long desiredShipId) {
        return claim(cid, desiredShipId, -1);
    }

    /**
     * {@link #claim(String, long)} filtered by descriptor seat SIDE ({@code -1} = any side, byte-identical to
     * the two-argument form; {@code 0} / {@code 1} = that side only). A cid that already holds a seat keeps it
     * ({@code REJOINED}, whatever the side asked). First-free walks {@link MatchDescriptors#claimOrder} for a
     * free, unreserved seat OF THAT SIDE. A pinned {@code desiredShipId} meets the side's cap too: a pin to a
     * free seat on the other side is refused with the side's verdict when the asked side has no free claimable
     * seat left. The side verdict: {@link Status#SIDE_FULL} only while the OTHER side still holds a free
     * claimable seat, else {@link Status#MATCH_FULL} — so the door's team table cross-fills a joiner (the
     * other side's seat) only when that side could seat them.
     *
     */
    public synchronized Claim claim(String cid, long desiredShipId, int side) {
        String key = requireCid(cid);
        if (desiredShipId < 0L) {
            throw new IllegalArgumentException(
                    "desiredShipId must be >= 0 (0 = first free seat); got " + desiredShipId);
        }
        if (side < -1 || side > 1) {
            throw new IllegalArgumentException("side must be -1 (any), 0 or 1; got " + side);
        }
        MatchDescriptor.Seat held = claimsByCid.get(key);
        if (held != null) {
            boolean mismatch = desiredShipId != 0L && desiredShipId != held.shipId();
            return new Claim(Status.REJOINED, held, mismatch);
        }
        MatchDescriptor.Seat resolved;
        if (desiredShipId != 0L) {
            resolved = seatsByShipId.get(desiredShipId);
            if (resolved == null) {
                return new Claim(Status.SEAT_UNKNOWN, null, false);
            }
            if (cidBySeatId.containsKey(resolved.seatId())) {
                return new Claim(Status.ALREADY_OCCUPIED, null, false);
            }
            if (side >= 0 && resolved.side() != side && !hasFreeClaimableSeatLocked(side)) {
                return new Claim(sideVerdictLocked(side), null, false);
            }
        } else {
            resolved = null;
            for (MatchDescriptor.Seat seat : claimOrder) {
                // D-SEATRACE: a reserved ship is only ever claimed by its explicit pin above.
                if ((side < 0 || seat.side() == side) && !cidBySeatId.containsKey(seat.seatId())
                        && !reservedShipIds.contains(seat.shipId())) {
                    resolved = seat;
                    break;
                }
            }
            if (resolved == null) {
                return new Claim(side < 0 ? Status.MATCH_FULL : sideVerdictLocked(side), null, false);
            }
        }
        claimsByCid.put(key, resolved);
        cidBySeatId.put(resolved.seatId(), key);
        return new Claim(Status.CLAIMED, resolved, false);
    }

    /**
     * Release {@code cid}'s seat claim so the seat can be re-claimed. Idempotent: vacating a cid
     * with no live claim returns {@code false}.
     */
    public synchronized boolean vacate(String cid) {
        MatchDescriptor.Seat held = claimsByCid.remove(requireCid(cid));
        if (held == null) {
            return false;
        }
        cidBySeatId.remove(held.seatId());
        return true;
    }

    /** The seat this cid currently claims, or null. */
    public synchronized MatchDescriptor.Seat claimedSeatOrNull(String cid) {
        return claimsByCid.get(requireCid(cid));
    }

    /** The number of seats currently claimed. */
    public synchronized int claimedCount() {
        return claimsByCid.size();
    }

    /** A snapshot of the seat ids live-claimed right now (the door's team count derives from it at decide
     *  time, never from a kept counter). */
    public synchronized Set<Integer> claimedSeatIds() {
        return Set.copyOf(cidBySeatId.keySet());
    }

    /** The descriptor seat whose ship is {@code shipId}, or null. */
    public MatchDescriptor.Seat seatForShipOrNull(long shipId) {
        return seatsByShipId.get(shipId);
    }

    /** Is any seat of {@code side} ({@code -1} = any side) free and first-free claimable (not reserved)?
     * */
    public synchronized boolean hasFreeClaimableSeat(int side) {
        return hasFreeClaimableSeatLocked(side);
    }

    private boolean hasFreeClaimableSeatLocked(int side) {
        for (MatchDescriptor.Seat seat : claimOrder) {
            if ((side < 0 || seat.side() == side) && !cidBySeatId.containsKey(seat.seatId())
                    && !reservedShipIds.contains(seat.shipId())) {
                return true;
            }
        }
        return false;
    }

    /** The verdict for a side with no free claimable seat: SIDE_FULL while the other side has one. */
    private Status sideVerdictLocked(int side) {
        return hasFreeClaimableSeatLocked(1 - side) ? Status.SIDE_FULL : Status.MATCH_FULL;
    }

    private static String requireCid(String cid) {
        if (cid == null || cid.isBlank()) {
            throw new IllegalArgumentException("client-instance id must not be null/blank (the"
                    + " password door rejects a blank clientId before the ledger is consulted)");
        }
        return cid;
    }
}
