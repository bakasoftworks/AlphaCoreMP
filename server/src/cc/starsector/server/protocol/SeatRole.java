package cc.starsector.server.protocol;

/**
 * The declared role of an admitted seat (demo-program R1 wire-freeze reservation,
 * 2026-06-11): what kind of participant this connection claims to be. Reserved and
 * carried in the admission handshake now — BEFORE the codec accretes more consumers —
 * enforced at the door by R8 (admission enforcement) and consumed by the seat-table
 * work the admiral-seat musing displaced.
 *
 * <p>Wire codes are explicit and frozen: {@code 1=PILOT}, {@code 2=ADMIRAL},
 * {@code 3=SPECTATOR}. Code {@code 0} is deliberately invalid (an unset byte is a
 * malformed handshake, never a silent default), and unknown codes THROW — the same
 * invalid-values-THROW discipline as the predict-lane knobs.
 *
 * <p>The composed seat object this field belongs to (one identity per admitted
 * connection, outliving both hull and socket): <b>identity</b> = the per-seat opaque
 * token (seat-credential), <b>role</b> = this field (admiral-seat), <b>mount</b> =
 * {@code clientOwnedShipId} — the seat's <em>current hull</em>, mutable by a future
 * server-authoritative RESEAT event, never the seat's identity (death-contract:
 * "the seat outlives the hull").
 */
public enum SeatRole {

    /** Flies one hull through the pilot-intent lane (today's only realized role). */
    PILOT(1),

    /** Issues fleet orders through the command economy; owns no single hull. */
    ADMIRAL(2),

    /** Pure snapshot consumer; no command lane at all (NB.7 spectator admission). */
    SPECTATOR(3);

    private final int wireCode;

    SeatRole(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static SeatRole fromWireCode(int wireCode) {
        for (SeatRole role : values()) {
            if (role.wireCode == wireCode) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown seat role wire code: " + wireCode);
    }
}
