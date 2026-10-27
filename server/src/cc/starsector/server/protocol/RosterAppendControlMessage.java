package cc.starsector.server.protocol;

/**
 * Registered server-&gt;all-sessions CONTROL message appending mid-round wave-spawned hulls to
 * every client's replica roster — the {@code RAPP} lane. The client replica
 * roster is descriptor-built and unknown-ids-inert (the 1270 step-2 law), so a wire id first
 * seen mid-round would otherwise never mint client-side (the ghost match: wave hulls on
 * wire+server but clientside-nonexistent). Fanned to EVERY live session at the serve loop's
 * (c2) wave drain, one frame per drained wave (≤16 rows; multi-frame allowed), and replayed
 * per-round to late joiners at the newly-live-session seam.
 *
 * <p>Rows are {wireId, ABSOLUTE side, variantId, shipName, crPerMille, carrierWireId,
 * bayIndex} (the v2 carrier+bay tail places a carrier-born fighter for the client's wing
 * HUD; 0/0xFF = not a fighter) — side is
 * descriptor-absolute (each client maps relative to its own descriptor side at append;
 * relative-in-frame would be wrong for side-1 seats). NO spawn pose: the client's existing
 * first-sight adopt spawns at the first authoritative wire pose + hardSnap. Deliberately a NEW
 * CTRL magic (not DESC-body, not a HOUS role-byte): a stale kit meeting {@code RAPP} dies at
 * the client's routeCtrl unknown-Kind throw instead of silently fielding the ghost match —
 * fail-closed BY DESIGN (admission-D6 statement). Wire codec: {@code RosterAppendFrameCodec}
 * (variable-body {@code RAPP} frame)..
 */
public final class RosterAppendControlMessage {

    public static final String MESSAGE_ID = "server.roster.append";
    /** v2: rows gain {@code u32 carrierWireId | u8 bayIndex}.
     *  v3: rows gain {@code UTF officerLine} (empty = no captain) — the
     *  {@code OfficerRosterCodec} grammar line of the captain the server ATTACHED, so the
     *  client twin's stats derive from the same skills (the "boss heals" overshoot).
     *  v4: the officer line may carry the optional eighth field
     *  {@code |<aiCoreId>} (closed set {@code OfficerRosterCodec.AI_CORE_IDS}) — a pre-v4 kit's
     *  7-field parser would fail the whole frame on an AI-core row, so the schema bumps and an
     *  old kit fails closed at the stream instead (roll FIRST). Frame bytes otherwise unchanged.
     *  v5: every row gains a trailing {@code u8 flags} (bit0 = AT_REST:
     *  the server hull was placed with {@code burnDur 0}, so the client twin mints with no
     *  parity burn; other bits reject) — a v4 kit's 8-field parser would mis-frame the flags
     *  byte as the next row's wireId, so the schema bumps and an old kit fails closed at the
     *  stream (roll FIRST; no handshake bump — the v4 precedent). */
    public static final int SCHEMA_VERSION = 5;
    public static final String CODEC_OWNER = "server.protocol.roster_append.codec";
    public static final String VALIDATION_OWNER = "server.protocol.roster_append.validation";
    public static final String RELEVANCE_RULE_ID = "roster_append.fan_all_sessions";
    public static final String COMPATIBILITY_PREDICATE_ID = "roster_append_schema_v5";
    public static final String FAILURE_CODE = "ROSTER_APPEND_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private RosterAppendControlMessage() {
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
