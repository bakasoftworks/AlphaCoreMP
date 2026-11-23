package cc.starsector.server.names;

/**
 * Engine-name rows only the bridge seams bind (deploy CR, the engine-born growth listener, the orbit-drone easing
 * module, the transfer pod's spawn and map clamp); the rows they share with other areas live in {@link EngineSym},
 * and every row here follows that file's grammar.
 */
public final class EngineSymBridgeSeams {

    private EngineSymBridgeSeams() {
    }

    // Deploy CR: the fleet repair tracker whose statics stamp deployment CR onto a ship and its stats.
    public static final EngineRow BRIDGE_SEAMS_CR_TRACKER_CLASS = EngineRow.cls("BRIDGE_SEAMS_CR_TRACKER_CLASS", "com/fs/starfarer/campaign/fleet/RepairTracker"); //

    // Engine-born growth: the live object repository's single listener slot interface.
    public static final EngineRow BRIDGE_SEAMS_REPO_LISTENER_CLASS = EngineRow.cls("BRIDGE_SEAMS_REPO_LISTENER_CLASS", "com/fs/util/container/repo/ObjectRepository$o"); //

    // Orbit drones: the combat copy of the smooth-movement easing module a station drone is driven through.
    public static final EngineRow BRIDGE_SEAMS_DRONE_EASING_CLASS = EngineRow.cls("BRIDGE_SEAMS_DRONE_EASING_CLASS", "com/fs/starfarer/campaign/fleet/SmoothMovementModuleCombatCopy"); //

    // Transfer pod: its AI, the spec fetch, the ship factory's 3-arg builder, and the combat map's four edge getters.
    public static final EngineRow BRIDGE_SEAMS_POD_TRANSFER_AI_CLASS = EngineRow.cls("BRIDGE_SEAMS_POD_TRANSFER_AI_CLASS", "com/fs/starfarer/combat/ai/movement/maneuvers/TransferCommandAI"); //
    public static final EngineRow BRIDGE_SEAMS_POD_SHIP_FACTORY_CLASS = EngineRow.cls("BRIDGE_SEAMS_POD_SHIP_FACTORY_CLASS", "com/fs/starfarer/loading/specs/_"); //
    public static final EngineRow BRIDGE_SEAMS_POD_SHIP_BUILD_METHOD = EngineRow.staticMethod("BRIDGE_SEAMS_POD_SHIP_BUILD_METHOD", "com/fs/starfarer/loading/specs/_", "o00000", "(Lcom/fs/starfarer/loading/specs/HullVariantSpec;Lcom/fs/starfarer/rpg/Person;Lcom/fs/starfarer/rpg/Person;)Lcom/fs/starfarer/combat/entities/Ship;"); //
    public static final EngineRow BRIDGE_SEAMS_MAP_MIN_X_METHOD = EngineRow.method("BRIDGE_SEAMS_MAP_MIN_X_METHOD", "com/fs/starfarer/combat/A/oOOO", "\u00D200000", "()F"); //
    public static final EngineRow BRIDGE_SEAMS_MAP_MAX_X_METHOD = EngineRow.method("BRIDGE_SEAMS_MAP_MAX_X_METHOD", "com/fs/starfarer/combat/A/oOOO", "\u00D600000", "()F"); //
    public static final EngineRow BRIDGE_SEAMS_MAP_MIN_Y_METHOD = EngineRow.method("BRIDGE_SEAMS_MAP_MIN_Y_METHOD", "com/fs/starfarer/combat/A/oOOO", "\u00D500000", "()F"); //
    public static final EngineRow BRIDGE_SEAMS_MAP_MAX_Y_METHOD = EngineRow.method("BRIDGE_SEAMS_MAP_MAX_Y_METHOD", "com/fs/starfarer/combat/A/oOOO", "void", "()F"); //
}
