package cc.starsector.server.protocol;

/**
 * Registered client-&gt;server CONTROL message carrying ONE player-authored ship variant for
 * server validation — the W15 custom-variant-maker {@code VSUB} up half.
 * Body = {@code u16 submitSeq} + the
 * {@code VDEF} body verbatim ({@code VariantDefinitionFrameCodec.encodeBody}); the
 * {@code variantId} field is the CLIENT-LOCAL id — the server never registers it, it mints its
 * own canonical {@code cc-v<serial>-<slug>} and answers with a {@code VACK} receipt
 * ({@link ServerVariantReceiptMessage}). Rides the reliable in-order UP CTRL lane
 * ({@code DatagramReliability}, window 512), so there is no app-level retry; the client's 10 s
 * receipt timeout re-submit is dup-safe server-side (same body ⇒ same canonical).
 *
 * <p>LIVE SERVER INPUT SURFACE:
 * decoded fail-closed by {@code VariantSubmitFrameCodec} (every VDEF field law
 * re-checked, ≤ 1164 B frame, CRC32 + trailing-byte strict, no wire-derived allocation beyond
 * the declared ≤ 1144 B body); a malformed frame kills the session (post-admit CTRL law); an
 * observer's VSUB is dropped at ingest (seat-scoped kind); the ONE
 * player-authored field is {@code displayName} (≤ 48 printable single-line, quoted at every
 * sink). Wire codec: {@code VariantSubmitFrameCodec}..
 */
public final class ClientVariantSubmitMessage {

    public static final String MESSAGE_ID = "client.variant.submit";
    public static final int SCHEMA_VERSION = 2; // Dictionary ids: the VDEF body grammar bumped with it
    public static final String CODEC_OWNER = "server.client.variant_submit.codec";
    public static final String VALIDATION_OWNER = "server.client.variant_submit.validation";
    public static final String RELEVANCE_RULE_ID = "variant_submit.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "variant_submit_schema_v2";
    public static final String FAILURE_CODE = "VARIANT_SUBMIT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.AUTHORITY_INPUT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientVariantSubmitMessage() {
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
