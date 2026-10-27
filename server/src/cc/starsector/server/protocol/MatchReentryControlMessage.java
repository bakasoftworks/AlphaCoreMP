package cc.starsector.server.protocol;

/**
 * Registered client-&gt;server CONTROL message: the explicit next-round RE-ENTRY request a
 * client sends UP the persistent socket to ask the server to deploy the next round (L1
 * session-lifecycle). It is the up-channel COMPLEMENT of the server-&gt;client
 * {@link MatchResetControlMessage} ("the round you were watching is over") — together they
 * form the bidirectional round-boundary handshake on a human-piloted persistent socket:
 * server says <em>round over</em> (MRST down), the client tears down its local combat, the
 * human re-enters, and the client says <em>I am back, deploy the next round</em> (this
 * message, up).
 *
 * <p>Why it exists: the intent-up persistent socket has a strict
 * per-tick ping-pong (snapshot down, intent up). At a roster-0 match-over the human spends
 * seconds-to-tens-of-seconds on the results screen and relaunching the mission, during which
 * NO client thread drains the socket. A server that resumes producing the next round into
 * that gap back-pressures (and, with the R10 {@code PeerSendGate} armed, would
 * DOWNSHIFT-&gt;KICK the silent seat). The faithful fix is <strong>pause-until-reentry</strong>:
 * after the round boundary the server produces nothing until this explicit re-entry frame
 * arrives, so the client's reader can simply block across the gap with no neutral-intent
 * invention. An explicit frame (distinct from a per-tick {@code LiveManeuverIntent}) also
 * disambiguates a real re-entry from a stray buffered intent — the server will not start the
 * next round on anything but this signal.
 *
 * <p>Its wire framing is {@code MatchReentryFrameCodec} (a distinct {@code "MRER"} magic so
 * it is never confused with an {@code "LMVI"} maneuver-intent frame on the same up-channel);
 * it rides the durable gameplay-event lane so it is never dropped. The server's
 * {@code pause-until-reentry} read is default-OFF ({@code -Dcc.server.awaitReentry}); the
 * existing single-stream / read-only round-loop paths stay byte-identical..
 *
 */
public final class MatchReentryControlMessage {

    public static final String MESSAGE_ID = "client.match_reentry.requested";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.match_reentry.codec";
    public static final String VALIDATION_OWNER = "server.protocol.match_reentry.validation";
    public static final String RELEVANCE_RULE_ID = "match_reentry.next_round_request";
    public static final String COMPATIBILITY_PREDICATE_ID = "match_reentry_schema_v1";
    public static final String FAILURE_CODE = "MATCH_REENTRY_CONTROL_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.AUTHORITY_INPUT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private MatchReentryControlMessage() {
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
