package cc.starsector.server.protocol;

/**
 * RESERVED-DORMANT server-&gt;client CONTROL message for descriptor-on-CTRL carriage:
 * the future lane where
 * joiners fetch the match shape (the {@code match.txt} descriptor content) over the wire
 * instead of from a per-match kit zip.
 *
 * <p><b>Reservation, not a feature</b>: the server emits NOTHING on this kind until its
 * consumer arc. What the reservation buys is kit longevity — the D4.8 client jar TOLERATES
 * the {@code DESC} frame (count-ignored on the predict CTRL lanes) so the consumer arc can
 * start emitting without forcing a &times;N re-kit ({@code routeCtrl} pre-B6 fail-dead THREW
 * on unknown kinds). Wire framing = {@code ReservedControlFrameCodec} (magic {@code "DESC"});
 * the envelope (not the body) is what is frozen — any reader can length-skip the frame.
 * {@code DESC} schema 2 is the match-document PROBE reply ({@link MatchDocumentMessage}, codec
 * {@code MatchDocFrameCodec}); this schema-1 kind stays the dormant reservation, and the
 * server's in-session reader still rejects any {@code DESC} schema but 1.
 *
 * <p>Charter intake statements:
 * <ul>
 *   <li><b>Size-vs-N</b>: per-MATCH payload (one descriptor), NOT per-entity — size is
 *       independent of N, bounded by descriptor text (~KBs). A descriptor larger than the
 *       1164&nbsp;B datagram payload cap needs CTRL-side chunking; that is the consumer
 *       arc's stated debt, not the reservation's.</li>
 *   <li><b>L6 emission compliance</b>: at the consumer arc, emission rides the SEAT/MRST
 *       idiom (non-blocking deposit into the bounded per-session CTRL spool, timer-drained);
 *       no sim-path caller may block. No emission exists today.</li>
 * </ul>
 */
public final class MatchDescriptorControlMessage {

    public static final String MESSAGE_ID = "server.match_descriptor_control";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.match_descriptor.codec";
    public static final String VALIDATION_OWNER = "server.protocol.match_descriptor.validation";
    public static final String RELEVANCE_RULE_ID = "match_descriptor.reserved_dormant";
    public static final String COMPATIBILITY_PREDICATE_ID = "match_descriptor_schema_v1";
    public static final String FAILURE_CODE = "MATCH_DESCRIPTOR_CONTROL_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private MatchDescriptorControlMessage() {
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
