package cc.starsector.server.protocol;

/**
 * Registered client authority-input message for one predicted own-gun DISCHARGE DECLARATION —
 * the declared-discharge fire lane.
 *
 * <p>Where {@code client.ship_fire.requested} / the {@code PilotIntentFrame} {@code fireHeld} bit
 * carry the trigger LEVEL, this message carries each discharge the client's own predicting engine
 * actually EMITTED (detected by the client's projectile scan): {@code declSeq} + client tick +
 * the aim at the discharge instant. The server serves exactly the declared discharges via the same
 * aimed fire command ({@code USE_SELECTED_GROUP}) until its engine's own discharge is observed — eliminating both
 * miss directions (server-only and client-only discharges) by construction. Canonical design:
 * the project's decision record; wire codec: {@code FireDischargeDeclarationCodec} (PDCL).
 */
public final class ClientFireDischargeDeclarationMessage {

    public static final String MESSAGE_ID = "client.fire_discharge.declared";
    public static final int SCHEMA_VERSION = 5; // v4: + muzzleX/muzzleY/bearingDeg/speed (committed shot's ray; all-NaN = none); v5: SEMANTIC bump, same 66 B layout — bearingDeg/speed are the SHIP-frame launch ray (fired bearing + relative launch speed), never the travel-frame velocity; a v4 peer's travel-frame rays would be re-posed wrongly, so the strict schema fence is the lockstep guard
    public static final String CODEC_OWNER = "server.client.fire_discharge_declaration.codec";
    public static final String VALIDATION_OWNER = "server.client.fire_discharge_declaration.validation";
    public static final String RELEVANCE_RULE_ID = "fire_discharge_declaration.server_only";
    public static final String COMPATIBILITY_PREDICATE_ID = "fire_discharge_declaration_schema_v5";
    public static final String FAILURE_CODE = "FIRE_DISCHARGE_DECLARATION_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.AUTHORITY_INPUT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ClientFireDischargeDeclarationMessage() {
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
