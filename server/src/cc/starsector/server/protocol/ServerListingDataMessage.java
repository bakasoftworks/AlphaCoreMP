package cc.starsector.server.protocol;

/**
 * Registered server-&gt;requesting-session CONTROL message carrying ONE frame of a structured
 * command listing — the SS382 commission-B {@code LSTD} DOWN-only family.
 *
 * A no-arg {@code /ship}, {@code /module} or {@code /transfer} answers with LSTD frames
 * UNICAST to the requesting seat's session (the {@code sendCtrlToSession} idiom — never
 * fanned), replacing the retired 16-line CHTD feed dump; arg/usage/deny/rate replies stay
 * CHTD.
 *
 * <p>Latest-wins by design: a listing is a static snapshot keyed by a per-seat
 * {@code listingId} mint — a newer listing for the same seat supersedes any partial older
 * one client-side. Selection sends NO new UP surface: the picker answers through the
 * existing {@code CHTU} chat-command grammar unchanged, so resolution stays
 * server-authoritative exact-fold and a stale pick is the normal deny reply.
 *
 * <p>Wire codec: {@code ListingFrameCodec} (variable entry count, hard-lawed geometry, every
 * frame ≤ the 1200-byte datagram budget). Additive-no-bump adjudication:
 * the client demux is fail-closed on unknown magics, but kits are lockstep and
 * pre-LSTD kits are DEAD by manifest, so no wire/handshake version constant moves — the
 * registry entry is purely additive.
 */
public final class ServerListingDataMessage {

    public static final String MESSAGE_ID = "server.listing.data";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.listing_data.codec";
    public static final String VALIDATION_OWNER = "server.protocol.listing_data.validation";
    public static final String RELEVANCE_RULE_ID = "listing_data.unicast_requesting_seat";
    public static final String COMPATIBILITY_PREDICATE_ID = "listing_data_schema_v1";
    public static final String FAILURE_CODE = "LISTING_DATA_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerListingDataMessage() {
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
