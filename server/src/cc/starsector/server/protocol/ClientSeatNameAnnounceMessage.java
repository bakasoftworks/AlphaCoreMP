package cc.starsector.server.protocol;

/**
 * Registered client message announcing the client's OWN locally-picked seat display name — the
 * seat-names-on-the-wire handshake's UP half ({@code SNAM});
 * the full admission/seat
 * front-end stays later. Sent exactly ONCE, immediately at connect and BEFORE the client's first
 * receive — the server reads it as a bounded pre-loop read right after that seat's accept
 * ({@code PilotIntentSocketServer.awaitSeatName}); mid-stream it is a protocol violation by design
 * (the up-lane demux stays fail-closed on its magic).
 *
 * <p>Carries the announcing ship id (cross-check only — the transport IS the seat) plus a
 * length-prefixed 48-byte zero-padded UTF-8 name buffer. {@code nameLen 0} is the "no local pick"
 * sentinel (a valid frame; the server falls back to its {@code cc.match.seatNName} knobs, so a
 * name-less client never stalls the handshake); {@code nameLen > 0} carries a name under the
 * {@code parseSeatName} contract (trimmed single-line printable, ≤24 chars). Wire
 * codec: {@code SeatNameFrameCodec} (fixed 71-byte {@code SNAM} frame).
 *
 */
public final class ClientSeatNameAnnounceMessage {

    public static final String MESSAGE_ID = "client.seat_name.announce";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.client.seat_name_announce.codec";
    public static final String VALIDATION_OWNER = "server.client.seat_name_announce.validation";
    public static final String RELEVANCE_RULE_ID = "seat_name_announce.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "seat_name_announce_schema_v1";
    public static final String FAILURE_CODE = "SEAT_NAME_ANNOUNCE_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.AUTHORITY_INPUT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientSeatNameAnnounceMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.CLIENT_REQUEST,
                ReliabilityLane.DURABLE_GAMEPLAY_EVENT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
