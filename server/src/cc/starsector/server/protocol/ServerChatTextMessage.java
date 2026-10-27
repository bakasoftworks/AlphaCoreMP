package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message relaying ONE in-match chat line — the
 * text-chat DOWN half ({@code CHTD}). Fanned to EVERY live session (fanMatchReset
 * idiom: encode once, spool per session outside the lock), sender INCLUDED — the sender renders
 * its own line only on this echo, which doubles as delivery confirmation and keeps one display
 * path.
 *
 * <p>Self-contained attribution: the server stamps the sender's seat id plus its resolved
 * display name (the {@code SNAM} announce name when present, {@code "seat-"+seatId} otherwise)
 * INTO the frame, so a receiver needs no {@code SNMS}-ordering dependency to label the line.
 * The name rides the {@code SeatNameFrameCodec} 48-byte buffer law but is REQUIRED non-empty
 * here (the server always has a fallback — an empty stamp fails closed); the text rides the
 * {@code validateChatText} contract (trimmed, non-blank, ≤120 chars, ≤240 UTF-8 bytes,
 * single-line printable). Round-agnostic: the roundId barrier gates SNAP only — chat delivers
 * between rounds and the client holds it until a combat UI exists. Wire codec:
 * {@code ChatFrameCodec} (fixed 309-byte {@code CHTD} frame)..
 */
public final class ServerChatTextMessage {

    public static final String MESSAGE_ID = "server.chat.text";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.chat_text.codec";
    public static final String VALIDATION_OWNER = "server.protocol.chat_text.validation";
    public static final String RELEVANCE_RULE_ID = "chat_text.relay_all_sessions";
    public static final String COMPATIBILITY_PREDICATE_ID = "chat_text_relay_schema_v1";
    public static final String FAILURE_CODE = "CHAT_TEXT_RELAY_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerChatTextMessage() {
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
