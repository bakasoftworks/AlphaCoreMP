package cc.starsector.server.runtime;

import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity;
import java.io.IOException;

/**
 * Server-side seat-admission decision for one connecting client — demo-program R8b-runner-wiring.
 *
 * <p>This is the per-connection bridge the runner uses to consume the R8 admission components built
 * dark: it resolves a handshake's seat credential against the stateful
 * {@link MatchSeatAuthority} ledger and returns the <b>token-bound</b> authority identity — the
 * ship the seat owns ({@link MatchSeatAuthority#authorizedShipId(String)}) — that REPLACES the
 * client-asserted {@code ownedShipId}. A connection can therefore command only the ship its
 * credential owns; "first-to-connect wins the seat" (the guessable path the seat-credential idea
 * closes) is gone.
 *
 * <p>It is the caller-side complement of the stateless gate:
 * <ul>
 *   <li>the {@link cc.starsector.server.client.SnapshotClientAdmissionGate} (R8a/R8c) sees ONE
 *       handshake and rejects a blank/unminted token or wrong-universe fingerprint, writing a
 *       well-formed rejecting admission ack;</li>
 *   <li>this decision adds the two <em>cross-connection</em> concerns the gate cannot hold — the
 *       <b>duplicate-seat policy</b> (a token seats at most one live connection) and the
 *       <b>arbiter seed</b> (token → bound ship) — by claiming the seat in the ledger.</li>
 * </ul>
 *
 * <p>Fail-closed: a blank, unminted, or already-seated token throws {@link SeatAdmissionRejected}
 * (an {@link IOException}, so it flows into the runner's existing accept-loop failure handling that
 * closes the sockets) carrying a minted machine reason. Only a freshly-SEATED token returns an
 * identity. Stateless except for the claim it makes on the passed authority (the ledger owns that
 * state); a fresh {@code MatchSeatAuthority} is minted per match, so nothing leaks across sessions.
 *
 * <p>The logic here is exercised at unit grade ({@code seatAdmissionResolvesAuthorizedIdentityOrRejects}
 * in {@code ServerContractTests}) — NOT dark. Driving it end-to-end through the live runner accept
 * loop needs a client that actually delivers a non-blank {@code seatToken} in its handshake (R6
 * token-delivery), the same end-to-end exercise R8a/R8c also defer; the runner consumes this
 * decision opt-in/default-off until then.
 */
public final class SeatAdmission {

    private SeatAdmission() {
    }

    /**
     * Resolve {@code seatToken} against {@code authority} into the token-bound authority identity
     * for {@code clientId}, claiming the seat in the ledger. Throws {@link SeatAdmissionRejected}
     * with a minted reason if the token is blank ({@code seat_token_required}), unminted
     * ({@code seat_token_unknown}), or already holds a live seat ({@code seat_already_occupied}).
     */
    public static ClientAuthorityIdentity authorize(
            MatchSeatAuthority authority, String clientId, String seatToken) throws SeatAdmissionRejected {
        return authorize(authority, clientId, seatToken, false);
    }

    /**
     * The N-peer door's form — {@code armTenureGate} is the
     * door's FRESH-claim verdict, passed through to {@link MatchSeatAuthority#claim(String,
     * boolean)} so the tenure gate arms in the claim's own monitor step (no window between the
     * occupancy add and a later mark in which a sim frame could fly the previous tenure's pick).
     */
    public static ClientAuthorityIdentity authorize(
            MatchSeatAuthority authority, String clientId, String seatToken, boolean armTenureGate)
            throws SeatAdmissionRejected {
        if (authority == null) {
            throw new IllegalArgumentException("authority must not be null when seat enforcement is engaged");
        }
        if (seatToken == null || seatToken.isBlank()) {
            throw new SeatAdmissionRejected(DoorRejectReasons.SEAT_TOKEN_REQUIRED, clientId);
        }
        MatchSeatAuthority.Seating seating = authority.claim(seatToken, armTenureGate);
        if (!seating.seated()) {
            String reason;
            if (seating.status() == MatchSeatAuthority.Status.ALREADY_SEATED) {
                reason = DoorRejectReasons.SEAT_ALREADY_OCCUPIED;
            } else {
                reason = DoorRejectReasons.SEAT_TOKEN_UNKNOWN;
            }
            throw new SeatAdmissionRejected(reason, clientId);
        }
        // The seat token, not the client-asserted ownedShipId, binds the ship this connection may command.
        return new ClientAuthorityIdentity(clientId, seating.shipId());
    }

    /** A fail-closed seat-admission rejection carrying a minted machine reason. */
    public static final class SeatAdmissionRejected extends IOException {
        private final String reason;

        public SeatAdmissionRejected(String reason, String clientId) {
            super("seat admission rejected (" + reason + ") for client " + clientId);
            this.reason = reason;
        }

        /** The minted machine reason (e.g. {@code seat_token_unknown}, {@code seat_already_occupied}). */
        public String reason() {
            return reason;
        }
    }
}
