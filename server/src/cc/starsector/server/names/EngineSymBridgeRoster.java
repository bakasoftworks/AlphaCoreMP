package cc.starsector.server.names;

/**
 * Engine-name rows of the bridge roster area (the demo roster's deploy + wave admiral, the custom-variant registrar,
 * the all-skills fleet context): the rows only these sites read, in the {@link EngineSym} grammar, keyed for
 * {@link EngineSym#PIN} and proven by the same resolve leg; a row another area shares lives in {@link EngineSym}.
 *
 *
 */
public final class EngineSymBridgeRoster {

    private EngineSymBridgeRoster() {
    }

    public static final EngineRow BRIDGE_ROSTER_ADMIRAL_AI_CLASS = EngineRow.cls("BRIDGE_ROSTER_ADMIRAL_AI_CLASS", "com/fs/starfarer/combat/ai/admiral/AdmiralAI"); //
    public static final EngineRow BRIDGE_ROSTER_PLAYER_SHIP_FIELD = EngineRow.field("BRIDGE_ROSTER_PLAYER_SHIP_FIELD", "com/fs/starfarer/combat/CombatEngine", "OO0000", "Lcom/fs/starfarer/combat/entities/Ship;"); //
    public static final EngineRow BRIDGE_ROSTER_MAP_HEIGHT_METHOD = EngineRow.method("BRIDGE_ROSTER_MAP_HEIGHT_METHOD", "com/fs/starfarer/combat/A/oOOO", "super", "()F"); //
    public static final EngineRow BRIDGE_ROSTER_VARIANT_REGISTRY_PUT_METHOD = EngineRow.staticMethod("BRIDGE_ROSTER_VARIANT_REGISTRY_PUT_METHOD", "com/fs/starfarer/loading/OoOO", "super", "(Lcom/fs/starfarer/loading/specs/HullVariantSpec;Z)V"); //
    public static final EngineRow BRIDGE_ROSTER_FLEET_DATA_CLASS = EngineRow.cls("BRIDGE_ROSTER_FLEET_DATA_CLASS", "com/fs/starfarer/campaign/fleet/FleetData"); //
}
