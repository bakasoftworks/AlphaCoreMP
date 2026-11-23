package cc.starsector.server.names;

/**
 * Engine names the transform registry and its contract rows bind (the threat-table class whose constructor the type
 * swap rewrites, that constructor's two engine parameter types, the fighter launch bay the landing redirect rewrites)
 * plus the weapon impl class and the tracker package the chargeup rows open from the jars: one {@link EngineRow} per
 * line in the grammar of {@link EngineSym}, re-keyed with it on a game update and proven by the same resolve leg.
 *
 *
 */
public final class EngineSymTestsTable {

    private EngineSymTestsTable() {
    }

    // Threat-table type swap: the class whose constructor is rewritten, and that constructor's two engine parameter
    // types after the ship (the ship row is shared, in EngineSym).
    public static final EngineRow TESTS_TABLE_THREAT_CLASS = EngineRow.cls("TESTS_TABLE_THREAT_CLASS", "com/fs/starfarer/combat/ai/D"); //
    public static final EngineRow TESTS_TABLE_THREAT_CTOR_MANEUVER_CLASS = EngineRow.cls("TESTS_TABLE_THREAT_CTOR_MANEUVER_CLASS", "com/fs/starfarer/combat/ai/movement/maneuvers/N$o"); //
    public static final EngineRow TESTS_TABLE_THREAT_CTOR_MOVEMENT_CLASS = EngineRow.cls("TESTS_TABLE_THREAT_CTOR_MOVEMENT_CLASS", "com/fs/starfarer/combat/ai/movement/oOOO"); //

    // Fighter launch bay (the FighterLaunchBayAPI impl): the landing-location call-site redirect rewrites this class.
    public static final EngineRow TESTS_TABLE_LAUNCH_BAY_CLASS = EngineRow.cls("TESTS_TABLE_LAUNCH_BAY_CLASS", "com/fs/starfarer/combat/entities/ship/A/B"); //

    // Weapon chargeup: the WeaponAPI impl, and the package its charge tracker and state machine live in.
    public static final EngineRow TESTS_TABLE_WEAPON_IMPL_CLASS = EngineRow.cls("TESTS_TABLE_WEAPON_IMPL_CLASS", "com/fs/starfarer/combat/entities/ship/A/O0oOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO"); //
    public static final EngineRow TESTS_TABLE_SHIP_TRACKERS_PKG = EngineRow.pkg("TESTS_TABLE_SHIP_TRACKERS_PKG", "com/fs/starfarer/combat/entities/ship/trackers"); //
}
