package cc.starsector.server.protocol;

/**
 * Registered server result message for one applied/rejected selected weapon group input.
 */
public final class ServerShipWeaponGroupCommandResultMessage {

    public static final String MESSAGE_ID = "server.ship_weapon_group.result";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.protocol.ship_weapon_group_result.codec";
    public static final String VALIDATION_OWNER = "server.protocol.ship_weapon_group_result.validation";
    public static final String RELEVANCE_RULE_ID = "ship_weapon_group_result.requesting_client_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "ship_weapon_group_result_schema_v1";
    public static final String FAILURE_CODE = "SHIP_WEAPON_GROUP_RESULT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerShipWeaponGroupCommandResultMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_OWNER_ONLY_UPDATE,
                ReliabilityLane.DURABLE_GAMEPLAY_EVENT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
