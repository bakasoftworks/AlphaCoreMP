package cc.starsector.server.names;

/**
 * Engine-name rows for the headless measurement probes (the fighter-wing registration proof and its siblings) that no
 * other area binds: the fleet manager's deploy-request wrapper the wing proof builds and the deployed fighter-wing
 * class it drives. Same grammar as {@link EngineSym} (one line per row, the key repeated as the first argument, the
 * trailing home cite); shared rows stay in {@link EngineSym}.
 *
 *
 */
public final class EngineSymProbesB {

    private EngineSymProbesB() {
    }

    public static final EngineRow PROBES_B_DEPLOY_REQUEST_CLASS = EngineRow.cls("PROBES_B_DEPLOY_REQUEST_CLASS", "com/fs/starfarer/combat/CombatFleetManager$oo"); //
    public static final EngineRow PROBES_B_FIGHTER_WING_CLASS = EngineRow.cls("PROBES_B_FIGHTER_WING_CLASS", "com/fs/starfarer/combat/ai/oO0O"); //
}
