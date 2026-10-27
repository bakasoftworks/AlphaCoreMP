package cc.starsector.server.protocol;

/**
 * Registered server-&gt;both-clients CONTROL message distributing BOTH resolved seat display names
 * — the seat-names-on-the-wire handshake's DOWN half ({@code SNMS}). Emitted exactly
 * once per connected client transport at the both-seats-known point (before the wire record sink
 * attaches — {@code .ccwr} fixtures stay pure PINT/PDCL, decided — and before the first
 * {@code AWSS}, so the client's demuxed read routes it ahead of its first snapshot).
 *
 * <p>Carries the session name table the server resolved per seat (fallback chain wire → the
 * {@code cc.match.seatNName} config knob → the long-standing deploy default) as ONE combined
 * fixed 112-byte frame (seat 1 = shipId 1 first), so a client applies both names atomically:
 * net-thread stash, render-thread apply at/around ship bind — BOTH screens' tags render one wire
 * truth from one typed name per side (the per-screen type-the-same-string interim is
 * deleted). {@code nameLen 0} = no-override sentinel (receiver falls back to its local knob).
 * Names are session-scoped: a {@code MATCH_RESET} does NOT re-trigger the handshake. Wire codec:
 * {@code SeatNameFrameCodec}.
 */
public final class ServerSeatNamesMessage {

    public static final String MESSAGE_ID = "server.seat_names";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.seat_names.codec";
    public static final String VALIDATION_OWNER = "server.protocol.seat_names.validation";
    public static final String RELEVANCE_RULE_ID = "seat_names.session_name_table";
    public static final String COMPATIBILITY_PREDICATE_ID = "seat_names_schema_v1";
    public static final String FAILURE_CODE = "SEAT_NAMES_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerSeatNamesMessage() {
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
