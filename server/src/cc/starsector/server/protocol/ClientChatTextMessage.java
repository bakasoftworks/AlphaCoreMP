package cc.starsector.server.protocol;

/**
 * Registered client message carrying ONE in-match chat line typed by the seat's pilot — the
 * text-chat UP half ({@code CHTU}). Sent post-admission
 * on the CTRL lane whenever the pilot submits a non-blank line; the server rate-gates it
 * (per-session token bucket, policy not protocol) and relays it to ALL live sessions — including
 * the sender — as the {@code CHTD} DOWN frame ({@link ServerChatTextMessage}).
 *
 * <p>Carries only the text: a 2-byte UTF-8 byte-count prefix + a fixed 240-byte zero-padded
 * buffer under the {@code validateChatText} contract (trimmed, non-blank, ≤120 chars,
 * ≤240 UTF-8 bytes, single-line printable — the seat-name law widened to chat length). There is
 * NO length-0 sentinel: a blank chat line is never a valid frame. Attribution rides the DOWN
 * frame only (the server stamps the sender's resolved display name); the transport IS the
 * sender. Wire codec: {@code ChatFrameCodec} (fixed 256-byte {@code CHTU} frame)..
 *
 */
public final class ClientChatTextMessage {

    public static final String MESSAGE_ID = "client.chat.text";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.client.chat_text.codec";
    public static final String VALIDATION_OWNER = "server.client.chat_text.validation";
    public static final String RELEVANCE_RULE_ID = "chat_text.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "chat_text_schema_v1";
    public static final String FAILURE_CODE = "CHAT_TEXT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientChatTextMessage() {
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
