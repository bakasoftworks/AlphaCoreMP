package cc.starsector.server.runtime;

/**
 * The server's typed acknowledgement of one client ship-control command on the lean
 * AWSS live path.
 *
 * <p>This is the return half the FIRE round-trip never had: the
 * server read the client's {@link LiveClientIntent} off the socket and threaded it
 * into the live command-authority step, but it sent back <em>no explicit
 * confirmation</em> — the client could only <em>infer</em> that its command was
 * applied by watching the per-tick {@code AuthoritativeWorldSnapshot} stream change
 * (a beam appearing, a ship accelerating). This ack is the explicit server&rarr;client
 * confirmation, encoded by {@link LiveCommandAckCodec} and bound to the registered
 * {@code server.ship_fire.result} message
 * ({@link cc.starsector.server.protocol.ServerShipFireCommandResultMessage}), which
 * until now existed only as {@code NetworkMessageRegistry} metadata with no codec and
 * no consumer.
 *
 * <p>{@code accepted} is the server's authoritative admission decision for the
 * command — the server is the source of truth, the client does not decide whether
 * its own command was applied. {@code shipId}, {@code weaponGroup}, and
 * {@code fireRequested} echo the command being acknowledged so the receiving client
 * can match the ack to the {@link LiveClientIntent} it sent.
 */
public record LiveCommandAck(long shipId, int weaponGroup, boolean fireRequested, boolean accepted) {

    public LiveCommandAck {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (weaponGroup < 0) {
            throw new IllegalArgumentException("weaponGroup must be >= 0; got " + weaponGroup);
        }
    }

    /** The server accepted and applied the client's command for {@code shipId}'s weapon group. */
    public static LiveCommandAck accept(long shipId, int weaponGroup, boolean fireRequested) {
        return new LiveCommandAck(shipId, weaponGroup, fireRequested, true);
    }

    /** The server rejected the client's command (e.g. unknown / not-owned ship). */
    public static LiveCommandAck reject(long shipId, int weaponGroup, boolean fireRequested) {
        return new LiveCommandAck(shipId, weaponGroup, fireRequested, false);
    }
}
