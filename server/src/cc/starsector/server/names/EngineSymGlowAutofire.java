package cc.starsector.server.names;

/**
 * Engine-name rows of the glow / autofire / message-widget / deploy-sweep area: the AI chain the foe-autofire sampler
 * walks (ship AI class, its attack module's autofire-manager field), the combat message widget the headless context
 * seeds and self-posts through, and the combat engine's pending-add queue the deploy-window sweep drains. Same grammar
 * as {@link EngineSym} (one row per line, the key repeated, the home cite trailing); a row {@link EngineSym} already
 * holds is read from there, never repeated here.
 */
public final class EngineSymGlowAutofire {

    private EngineSymGlowAutofire() {
    }

    // Ship AI chain the foe-autofire sampler walks: Ship.getShipAI() -> BasicShipAI.getAttackAI() -> the manager field.
    public static final EngineRow GLOWAF_BASIC_SHIP_AI_CLASS = EngineRow.cls("GLOWAF_BASIC_SHIP_AI_CLASS", "com/fs/starfarer/combat/ai/BasicShipAI"); //
    public static final EngineRow GLOWAF_ATTACK_MANAGER_FIELD = EngineRow.field("GLOWAF_ATTACK_MANAGER_FIELD", "com/fs/starfarer/combat/ai/attack/AttackAIModule", "interface", "Lcom/fs/starfarer/combat/ai/attack/String;"); //

    // Combat message widget (the return type of the combat UI's getMessageWidget()): its font-size float and the
    // varargs message-add method.
    public static final EngineRow GLOWAF_MSG_WIDGET_FONT_SIZE_FIELD = EngineRow.field("GLOWAF_MSG_WIDGET_FONT_SIZE_FIELD", "com/fs/starfarer/o0OO/C", "super.new$while", "F"); //

    // Combat engine pending-add queue: addObject parks a new entity here until the next advance folds it in.
    public static final EngineRow GLOWAF_ENGINE_PENDING_ADDS_FIELD = EngineRow.field("GLOWAF_ENGINE_PENDING_ADDS_FIELD", "com/fs/starfarer/combat/CombatEngine", "super.super", "Ljava/util/List;"); //
}
