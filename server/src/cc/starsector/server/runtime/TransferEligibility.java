package cc.starsector.server.runtime;

/**
 * W3 L1b-ii: the transfer drain's authoritative
 * target-eligibility recompute as a PURE truth table. The serve lane
 * ({@code CommandTransferServeLane}) feeds it FRESH facts each tick — authority occupancy/grace
 * truth plus the registry-snapshot inversion — never the published census, which is advisory
 * and up to a publish-belt stale. Pure and public deliberately: the lane itself
 * is engine-coupled and row-less (the {@code ModuleSeatServeLane} precedent), so the
 * eligibility LAW carries its contract rows here instead
 * ({@code CommandTransferContractRows}).
 *
 * <p>Deny text is server-authored, line-law-safe, in the roster verb's
 * {@code "transfer aborted - …"} vocabulary ({@code SeatRebind.transferSeatToHull})
 * so the player reads ONE deny voice whichever side of the verb the row died on.
 */
public final class TransferEligibility {

    /** The three drain paths: take an AI hull, take a PARKED seat's hull, or abort the row. */
    public enum Kind { AI_HULL, PARKED_TAKEOVER, DENY }

    /** {@code denyReason} is non-null exactly when {@code kind == DENY}. */
    public record Verdict(Kind kind, String denyReason) {}

    private static final Verdict AI_HULL = new Verdict(Kind.AI_HULL, null);
    private static final Verdict PARKED_TAKEOVER = new Verdict(Kind.PARKED_TAKEOVER, null);
    private static final Verdict GONE =
            new Verdict(Kind.DENY, "transfer aborted - target left the field");
    private static final Verdict OCCUPIED =
            new Verdict(Kind.DENY, "transfer aborted - target is occupied");
    private static final Verdict RESERVED =
            new Verdict(Kind.DENY, "transfer aborted - target is reserved (reconnect grace)");

    private TransferEligibility() {}

    /**
     * Classify one in-flight row's target. {@code targetIsSeatHull} = the target wire id is a
     * seat's FIXED wire id (descriptor seat map); the three seat facts are meaningful only then
     * (pass false otherwise): {@code seatOccupied} = a live connection holds that seat,
     * {@code seatInGraceWindow} = its grace window is still running (hull reserved for the
     * owner's reconnect), {@code seatParkedAbandoned} = the window expired unreclaimed
     * ({@code MatchSeatAuthority.abandonedEligible} — the one seat-hull takeover the packet
     * allows). {@code stableIdResolved} = the registry-snapshot inversion produced a stable
     * engine id for the target wire id (an unmapped id means the hull is gone/never deployed —
     * the verb could only deny later; classify it target-gone now).
     */
    public static Verdict classify(boolean targetIsSeatHull, boolean seatOccupied,
            boolean seatInGraceWindow, boolean seatParkedAbandoned, boolean stableIdResolved) {
        if (!targetIsSeatHull) {
            return stableIdResolved ? AI_HULL : GONE;
        }
        if (seatOccupied) {
            return OCCUPIED;
        }
        if (seatInGraceWindow) {
            return RESERVED;
        }
        if (!seatParkedAbandoned) {
            return GONE;
        }
        return stableIdResolved ? PARKED_TAKEOVER : GONE;
    }
}
