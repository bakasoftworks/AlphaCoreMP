package cc.starsector.server.protocol;

/**
 * Registered server-&gt;client CONTROL message carrying one full-state {@link SeatStateEvent} —
 * the {@code SEAT} seat-state replication family (wire shape frozen).
 * Emitted by the N-peer serve loop ONLY ({@code serveNPeer}, D9): a per-admit
 * BASELINE burst (one frame per descriptor seat, re-emitted post-{@code MATCH_RESET}) plus one
 * frame per seat-machine transition drained on the sim thread
 * ({@code MatchSeatAuthority.drainSeatEvents}). The legacy 1–2-seat serve never emits it, so
 * every existing client/kit stays byte-compatible until the N-mode kit lands.
 *
 * <p>Each frame is the seat's CURRENT two-axis state (idempotent, replay-safe): a client
 * materializes its seat table by overwriting per {@code seatId}, ordering by {@code seatSeq}
 * (one global monotone u32 mint, so per-seat order is monotone). Rides the UDP CTRL lane as a
 * payload magic — the datagram envelope codec is untouched. Wire codec:
 * {@code SeatStateFrameCodec} (fixed 38-byte frame); client mirror:
 * {@code ClientSeatStateReader}, byte-parity gated.
 *
 */
public final class ServerSeatStateEventMessage {

    public static final String MESSAGE_ID = "server.seat_state_event";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.seat_state_event.codec";
    public static final String VALIDATION_OWNER = "server.protocol.seat_state_event.validation";
    public static final String RELEVANCE_RULE_ID = "seat_state.full_state_seat_table";
    public static final String COMPATIBILITY_PREDICATE_ID = "seat_state_event_schema_v1";
    public static final String FAILURE_CODE = "SEAT_STATE_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerSeatStateEventMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_BROADCAST,
                ReliabilityLane.DURABLE_GAMEPLAY_EVENT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
