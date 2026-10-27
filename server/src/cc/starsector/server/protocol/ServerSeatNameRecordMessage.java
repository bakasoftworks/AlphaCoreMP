package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message carrying ONE seat's resolved display name —
 * the N-aware {@code SNMR} per-seat record the multi-peer door fans where the legacy
 * two-seat {@code SNMS} table cannot follow (SNMS is structurally two-seat and its serve lane
 * died with the legacy door).
 *
 * <p>Fixed 73-byte frame: seatId (u16) + shipId (i64) + one name buffer (nameLen +
 * 48-byte zero-padded UTF-8; {@code nameLen 0} = the no-override sentinel — a valid frame a
 * client treats as a no-op, though the server's fan/catch-up skip sentinel-resolved records:
 * a name is never un-set, so they say nothing). Fanned reactively in the {@code fanChat} shape
 * (encode once, spool per live session): on a stored {@code SNAM} announce to ALL sessions, and
 * on ADMIT/TAKEOVER as a full-table catch-up to the newcomer only — names are session metadata,
 * not per-tick seat state, so the record never rides the serve loop's tick fan. Server resolve
 * chain per seat: wire announce → the server-JVM {@code cc.match.seat1Name}/{@code seat2Name}
 * knobs for seats 1/2 (the legacy SNMS fallback semantics) → the sentinel. Wire codec:
 * {@code SeatNameFrameCodec}.
 */
public final class ServerSeatNameRecordMessage {

    public static final String MESSAGE_ID = "server.seat_name_record";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.seat_name_record.codec";
    public static final String VALIDATION_OWNER = "server.protocol.seat_name_record.validation";
    public static final String RELEVANCE_RULE_ID = "seat_name_record.session_name_fan";
    public static final String COMPATIBILITY_PREDICATE_ID = "seat_name_record_schema_v1";
    public static final String FAILURE_CODE = "SEAT_NAME_RECORD_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerSeatNameRecordMessage() {
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
