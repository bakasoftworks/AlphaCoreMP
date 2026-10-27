package cc.starsector.server.protocol;

/**
 * RESERVED-DORMANT server-&gt;client CONTROL message for house/operator-initiated signals:
 * the future lane for
 * operator-originated broadcasts (announcements, pause/administrative signals — roles
 * assigned by that future arc, none defined today).
 *
 * <p><b>Reservation, not a feature</b>: the server emits NOTHING on this kind until its
 * consumer arc; the D4.8 client jar TOLERATES the {@code HOUS} frame (count-ignored on the
 * predict CTRL lanes) so future house signals never force a &times;N re-kit. Wire framing =
 * {@code ReservedControlFrameCodec} (magic {@code "HOUS"}).
 *
 * <p><b>Role byte (frozen position)</b>: body byte 0 is the ROLE discriminator — one HOUSE
 * kind fans into many future operator signal roles without minting a new magic per signal.
 * Only the POSITION is frozen; role values are assigned at the consumer arc
 * ({@link #ROLE_RESERVED_SAMPLE} = 0 is burned by the golden fixture and stays no-meaning).
 * A tolerant client never reads the role; a consumer switches on it.
 *
 * <p>Charter intake statements:
 * <ul>
 *   <li><b>Size-vs-N</b>: operator broadcast — fixed-small per signal, independent of N;
 *       N-safe by construction under the 1164&nbsp;B datagram payload cap.</li>
 *   <li><b>L6 emission compliance</b>: at the consumer arc, emission rides the SEAT/MRST
 *       CTRL-spool idiom; no sim-path caller may block. No emission exists today.</li>
 * </ul>
 */
public final class HouseControlMessage {

    public static final String MESSAGE_ID = "server.house_control";
    public static final int SCHEMA_VERSION = 1;
    /** Body byte 0 = the role discriminator (position frozen; values assigned at the consumer arc). */
    public static final int ROLE_BYTE_OFFSET = 0;
    /** Role 0: burned by the golden fixture; carries no meaning, never to be assigned one. */
    public static final int ROLE_RESERVED_SAMPLE = 0;

    /**
     * Role 1 — KICKED notice (the reservation's FIRST consumer): the server
     * tells a kick victim it was deliberately removed BEFORE failing its session, so the
     * client renders a kicked banner instead of the "looks like a crash" silence window.
     * Body v1 (4 bytes): {@code u8 role(=1)} + {@code u8 reasonCode} + {@code u16 reserved0}.
     * No free text rides the wire — client prose is a code-switch on the client
     * ({@code describeRefusal} idiom). Best-effort by design: the death contract stays
     * wire-silence, a lost notice degrades to the peer's read-deadline path.
     */
    public static final int ROLE_KICKED_NOTICE = 1;
    /** Kicked reason 1: the in-match admin {@code /kick} verb. */
    public static final int KICKED_REASON_ADMIN_KICK = 1;
    /** Kicked reason 2: the ops-GUI / operator-file kick ({@code kickSeatSession}, O-0). */
    public static final int KICKED_REASON_OPERATOR_KICK = 2;
    /** Kicked reason 3: the seat-squat eviction — this claim never began
     *  exchanging past the age floor and a credentialed joiner needed its seat under a full
     *  ledger ({@code evictPendingSquatLocked}, session token {@code pending_evicted}). The
     *  victim's cid re-HELLO is a plain fresh claim (eviction is a session RESET, never a
     *  revocation) — the client's Play re-dial rejoins when a seat frees. */
    public static final int KICKED_REASON_PENDING_EVICTED = 3;
    /** Kicked reason 4 — the P27 BAN. The victim's
     *  session died because its cid or source key was banned; its re-HELLO meets the door's
     *  {@code banned} REJECT (terminal). Unlike 1..3 this is NOT a reset: the client must never
     *  say "you may rejoin" (its mirror lives in the client lane — {@code KickedNoticeLane}). */
    public static final int KICKED_REASON_BANNED = 4;
    public static final String CODEC_OWNER = "server.protocol.house_control.codec";
    public static final String VALIDATION_OWNER = "server.protocol.house_control.validation";
    public static final String RELEVANCE_RULE_ID = "house_control.reserved_dormant";
    public static final String COMPATIBILITY_PREDICATE_ID = "house_control_schema_v1";
    public static final String FAILURE_CODE = "HOUSE_CONTROL_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private HouseControlMessage() {
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
