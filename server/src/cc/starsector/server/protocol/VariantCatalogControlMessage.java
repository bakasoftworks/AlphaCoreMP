package cc.starsector.server.protocol;

/**
 * Registered server-&gt;session CONTROL message delivering one ship-variant definition to an
 * admitted client — the W7c B2 {@code VDEF} wire-variant lane.
 * The server's boot catalog (scan+parse of the deployed {@code data/variants/**}, root ∪
 * {@code npc/}) is unicast-burst to each session once per admission at the seat-name catch-up
 * seam, BEFORE any {@code RAPP} replay row can name a wire-only variant (the hard ordering
 * constraint: catalog ≺ roster append). DOWN-only and request-channel-free — zero new server
 * input surface.
 *
 * <p>ONE variant per frame (CTRL has no cross-datagram reassembly), standard length-skippable
 * envelope, fail-closed like {@code RAPP}: a stale kit meeting {@code VDEF} dies at the client's
 * routeCtrl unknown-Kind throw — but emission is gated on the ARMED door (exact-fingerprint
 * admission ⇒ every admitted session runs the current, fold-capable kit; legacy/smoke doors emit
 * nothing), so that death is unreachable in practice. Wire codec:
 * {@code VariantDefinitionFrameCodec} (variable-body {@code VDEF} frame).
 *
 */
public final class VariantCatalogControlMessage {

    public static final String MESSAGE_ID = "server.variant.catalog";
    public static final int SCHEMA_VERSION = 2; // Dictionary ids: u16 table indices for hull/weapon/hullmod/wing/slot
    public static final String CODEC_OWNER = "server.protocol.variant_catalog.codec";
    public static final String VALIDATION_OWNER = "server.protocol.variant_catalog.validation";
    public static final String RELEVANCE_RULE_ID = "variant_catalog.spool_at_admission";
    public static final String COMPATIBILITY_PREDICATE_ID = "variant_catalog_schema_v2";
    public static final String FAILURE_CODE = "VARIANT_CATALOG_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private VariantCatalogControlMessage() {
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
