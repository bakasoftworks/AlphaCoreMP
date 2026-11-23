package cc.starsector.server.names;

/**
 * Engine-name rows of the bridge's real-engine binding factory area (the factory, the combat binding, the reflection
 * binding and the engine interval timers): the boot's map, campaign, sprite, fader, viewport and debris classes, the
 * combat-UI interface and its message widget's post method, the retreat engage static, the ship's internal
 * use-selected-group command constant and the engine's interval-timer type. Same grammar as {@link EngineSym}; a row
 * {@link EngineSym} already holds is read from there, never repeated here.
 *
 *
 */
public final class EngineSymBridgeFactory {

    private EngineSymBridgeFactory() {
    }

    // Real-engine boot: the combat map, the advance amount arg type, the campaign singleton, the background sprite.
    public static final EngineRow BF_COMBAT_MAP_CLASS = EngineRow.cls("BF_COMBAT_MAP_CLASS", "com/fs/starfarer/combat/A/oOOO"); //
    public static final EngineRow BF_ADVANCE_AMOUNT_CLASS = EngineRow.cls("BF_ADVANCE_AMOUNT_CLASS", "com/fs/starfarer/util/super/B"); //
    public static final EngineRow BF_CAMPAIGN_ENGINE_CLASS = EngineRow.cls("BF_CAMPAIGN_ENGINE_CLASS", "com/fs/starfarer/campaign/CampaignEngine"); //
    public static final EngineRow BF_BACKGROUND_SPRITE_CLASS = EngineRow.cls("BF_BACKGROUND_SPRITE_CLASS", "com/fs/graphics/Sprite"); //

    // Damage-path FX seams: the combat-UI interface, the real message widget and its varargs post (both cited from
    // the jar: the mirror's file for that class is case-shadowed by a same-name class on a case-insensitive disk), the
    // fader the widget seeds, the viewport and the debris system.
    public static final EngineRow BF_COMBAT_UI_CLASS = EngineRow.cls("BF_COMBAT_UI_CLASS", "com/fs/starfarer/combat/K"); //
    public static final EngineRow BF_MESSAGE_WIDGET_CLASS = EngineRow.cls("BF_MESSAGE_WIDGET_CLASS", "com/fs/starfarer/o0OO/C"); //
    public static final EngineRow BF_MESSAGE_WIDGET_POST_METHOD = EngineRow.method("BF_MESSAGE_WIDGET_POST_METHOD", "com/fs/starfarer/o0OO/C", "super", "([Ljava/lang/Object;)Lcom/fs/starfarer/o0OO/D/D;"); //
    public static final EngineRow BF_FADER_CLASS = EngineRow.cls("BF_FADER_CLASS", "com/fs/graphics/util/Fader"); //
    public static final EngineRow BF_COMBAT_VIEWPORT_CLASS = EngineRow.cls("BF_COMBAT_VIEWPORT_CLASS", "com/fs/starfarer/combat/CombatViewport"); //
    public static final EngineRow BF_DEBRIS_SYSTEM_CLASS = EngineRow.cls("BF_DEBRIS_SYSTEM_CLASS", "com/fs/starfarer/renderers/damage/DebrisParticleSystem"); //

    // Ship-command and retreat seams: the concrete weapon group, the internal command enum's use-selected-group
    // constant, the retreat maneuver and its static engage.
    public static final EngineRow BF_WEAPON_GROUP_CLASS = EngineRow.cls("BF_WEAPON_GROUP_CLASS", "com/fs/starfarer/combat/systems/WeaponGroup"); //
    public static final EngineRow BF_USE_SELECTED_GROUP_FIELD = EngineRow.staticField("BF_USE_SELECTED_GROUP_FIELD", "com/fs/starfarer/combat/entities/Ship$oo", "oO0000", "Lcom/fs/starfarer/combat/entities/Ship$oo;"); //
    public static final EngineRow BF_RETREAT_MANEUVER_CLASS = EngineRow.cls("BF_RETREAT_MANEUVER_CLASS", "com/fs/starfarer/combat/ai/movement/maneuvers/RetreatManeuver"); //
    public static final EngineRow BF_RETREAT_ENGAGE_METHOD = EngineRow.staticMethod("BF_RETREAT_ENGAGE_METHOD", "com/fs/starfarer/combat/ai/movement/maneuvers/RetreatManeuver", "o00000", "(Lcom/fs/starfarer/combat/entities/Ship;Z)V"); //

    // The engine-internal interval timer the frame-0 restart finds by type name.
    public static final EngineRow BF_INTERVAL_TRACKER_CLASS = EngineRow.cls("BF_INTERVAL_TRACKER_CLASS", "com/fs/starfarer/util/IntervalTracker"); //
}
