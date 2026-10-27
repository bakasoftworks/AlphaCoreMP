package cc.starsector.server.protocol;

/**
 * Registered client message carrying ONE F9 "mark" press
 * — {@code MARK} on the wire. WHY: F9 is PUBLIC (players may mark stuff in logs),
 * but a public stranger's sidelog
 * never reaches us and a {@code logs=off} player's {@code PilotWatchLog} sits at OFF — so the
 * mark is worth something only if it ALSO lands in the SERVER log. This frame is that capture.
 *
 * <p>Payload = the client's running mark count (u16, ≥1) + the client's last snapshot sim tick
 * (i64; {@code -1} = no snapshot seen yet). NO player text rides it (bounded, single-line
 * printable law trivially satisfied). The server prints one stdout row
 * {@code owner-marker seat=<n> #<k> clientTick=<t> serverTick=<T>} and rate-bounds it ≤1/s per
 * seat (policy, not protocol — a held key or a masher can't spam the serve log). Wire codec:
 * {@code MarkFrameCodec} (24-B fixed width, the PDCL/SNAM/SNNK CTRL template).
 *
 */
public final class ClientOwnerMarkMessage {

    public static final String MESSAGE_ID = "client.debug.owner_mark";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.client.owner_mark.codec";
    public static final String VALIDATION_OWNER = "server.client.owner_mark.validation";
    public static final String RELEVANCE_RULE_ID = "owner_mark.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "owner_mark_schema_v1";
    public static final String FAILURE_CODE = "OWNER_MARK_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientOwnerMarkMessage() {
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
