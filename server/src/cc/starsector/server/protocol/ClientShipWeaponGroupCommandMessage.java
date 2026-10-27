package cc.starsector.server.protocol;

/**
 * Registered client authority-input message for one selected weapon-group intent.
 */
public final class ClientShipWeaponGroupCommandMessage {

    public static final String MESSAGE_ID = "client.ship_weapon_group.requested";
    public static final int SCHEMA_VERSION = 1;
    public static final String CODEC_OWNER = "server.client.ship_weapon_group_command.codec";
    public static final String VALIDATION_OWNER = "server.client.ship_weapon_group_command.validation";
    public static final String RELEVANCE_RULE_ID = "ship_weapon_group_command.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "ship_weapon_group_command_schema_v1";
    public static final String FAILURE_CODE = "SHIP_WEAPON_GROUP_COMMAND_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.AUTHORITY_INPUT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientShipWeaponGroupCommandMessage() {
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
