package cc.starsector.server.protocol;

/**
 * Registered server-&gt;session CONTROL message answering ONE {@code VSUB} submission with a
 * structured verdict — the W15 custom-variant-maker {@code VACK} down half.
 * Body =
 * {@code u16 submitSeq | u8 verdict | u8 code | u8-len id}: verdict ACCEPTED carries code 0 and
 * the server-MINTED canonical id ({@code cc-v<serial>-<slug>}); verdict REJECTED carries the
 * 1-based index of a {@code DoorRejectReasons.custom()} reason token (the ONE reject vocabulary,
 * bundled into the mod jar so both ends switch on the same list) and echoes the client-LOCAL id
 * so the author can correlate without parsing text. Never free text — the client renders the
 * token to a sentence.
 *
 * <p>UNICAST ONLY, to the session that sent the VSUB (capability by first use: a client that
 * never submits never sees a {@code VACK}, so no handshake bump — an unknown DOWN magic would
 * kill a stale kit). Emitted from the reader thread (policy/lane refusals) or the sim thread
 * (post-registration verdict) via {@code sendCtrlToSession}. Wire codec:
 * {@code VariantReceiptFrameCodec} (bounded ≤ 89 B frame)..
 *
 */
public final class ServerVariantReceiptMessage {

    public static final String MESSAGE_ID = "server.variant.receipt";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.variant_receipt.codec";
    public static final String VALIDATION_OWNER = "server.protocol.variant_receipt.validation";
    public static final String RELEVANCE_RULE_ID = "variant_receipt.unicast_to_submitter";
    public static final String COMPATIBILITY_PREDICATE_ID = "variant_receipt_schema_v1";
    public static final String FAILURE_CODE = "VARIANT_RECEIPT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerVariantReceiptMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.DURABLE_GAMEPLAY_EVENT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
