package cc.starsector.server.names;

/**
 * The engine-name rows of the headless content init and the code beside it (the settings load, the resource and
 * texture registries, the script store, the focus-manager factory, the round heap drain's plugin wrapper and FX
 * registries, the hulk tesselator), in the {@link EngineSym} grammar; a row many areas name lives in {@link EngineSym}
 * instead, never here as well.
 */
public final class EngineSymContentInit {

    private EngineSymContentInit() {
    }

    // Settings singleton holder: its static load seeds Global.getSettings() (the load and the SettingsAPI factory are matched by shape).
    public static final EngineRow CONTENT_INIT_SETTINGS_CLASS = EngineRow.cls("CONTENT_INIT_SETTINGS_CLASS", "com/fs/starfarer/settings/StarfarerSettings"); //

    // Resource registry (its singleton is matched by shape) and the seed methods the launcher calls on it: add the absolute
    // and working directories (no-arg), add one directory, add the classpath, and the front-insert add-mod-directory
    // (path, isMod, modSpec) that puts a mod's files ahead of base content.
    public static final EngineRow CONTENT_INIT_RESOURCE_CLASS = EngineRow.cls("CONTENT_INIT_RESOURCE_CLASS", "com/fs/util/C"); //
    public static final EngineRow CONTENT_INIT_RESOURCE_ADD_BASE_DIRS_METHOD = EngineRow.method("CONTENT_INIT_RESOURCE_ADD_BASE_DIRS_METHOD", "com/fs/util/C", "class", "()V"); //
    public static final EngineRow CONTENT_INIT_RESOURCE_ADD_DIR_METHOD = EngineRow.method("CONTENT_INIT_RESOURCE_ADD_DIR_METHOD", "com/fs/util/C", "class", "(Ljava/lang/String;)V"); //
    public static final EngineRow CONTENT_INIT_RESOURCE_ADD_CLASSPATH_METHOD = EngineRow.method("CONTENT_INIT_RESOURCE_ADD_CLASSPATH_METHOD", "com/fs/util/C", "new", "()V"); //
    public static final EngineRow CONTENT_INIT_RESOURCE_ADD_MOD_DIR_METHOD = EngineRow.method("CONTENT_INIT_RESOURCE_ADD_MOD_DIR_METHOD", "com/fs/util/C", "o00000", "(Ljava/lang/String;ZLjava/lang/Object;)V"); //

    // Script store: its static jar-path List (matched by shape) is where the launcher registers mod jars.
    public static final EngineRow CONTENT_INIT_SCRIPT_STORE_CLASS = EngineRow.cls("CONTENT_INIT_SCRIPT_STORE_CLASS", "com/fs/starfarer/loading/scripts/ScriptStore"); //

    // Texture registry + its static no-arg getter of the texture map, and the texture loader whose init(Map) fills it headless.
    public static final EngineRow CONTENT_INIT_TEXTURE_REGISTRY_CLASS = EngineRow.cls("CONTENT_INIT_TEXTURE_REGISTRY_CLASS", "com/fs/graphics/G"); //
    public static final EngineRow CONTENT_INIT_TEXTURE_MAP_METHOD = EngineRow.staticMethod("CONTENT_INIT_TEXTURE_MAP_METHOD", "com/fs/graphics/G", "super", "()Ljava/util/Map;"); //
    public static final EngineRow CONTENT_INIT_RESOURCE_LOADER_STATE_CLASS = EngineRow.cls("CONTENT_INIT_RESOURCE_LOADER_STATE_CLASS", "com/fs/starfarer/loading/ResourceLoaderState"); //

    // UI class whose unique public static no-arg FocusManagerAPI method (matched by shape) is the launcher's focus-manager factory.
    public static final EngineRow CONTENT_INIT_FOCUS_MANAGER_FACTORY_CLASS = EngineRow.cls("CONTENT_INIT_FOCUS_MANAGER_FACTORY_CLASS", "com/fs/starfarer/ui/classsuper"); //

    // Round heap drain: the wrapper addPlugin puts around every EveryFrameCombatPlugin (its unwrap accessor is matched by
    // shape), the combat map's advanceable-list getter, and the two static-managed FX registries the drain empties.
    public static final EngineRow CONTENT_INIT_PLUGIN_WRAPPER_CLASS = EngineRow.cls("CONTENT_INIT_PLUGIN_WRAPPER_CLASS", "com/fs/starfarer/title/C/for$Oo"); //
    public static final EngineRow CONTENT_INIT_COMBAT_MAP_ADVANCEABLES_METHOD = EngineRow.method("CONTENT_INIT_COMBAT_MAP_ADVANCEABLES_METHOD", "com/fs/starfarer/combat/A/oOOO", "String", "()Ljava/util/List;"); //
    public static final EngineRow CONTENT_INIT_ANIMATION_MANAGER_CLASS = EngineRow.cls("CONTENT_INIT_ANIMATION_MANAGER_CLASS", "com/fs/graphics/anim/AnimationManager"); //
    public static final EngineRow CONTENT_INIT_DYNAMIC_PARTICLE_GROUP_CLASS = EngineRow.cls("CONTENT_INIT_DYNAMIC_PARTICLE_GROUP_CLASS", "com/fs/graphics/particle/DynamicParticleGroup"); //

    // Hulk-cut tesselator: its one static Random (the field name is engine-internal, so it is resolved by type) is re-armed per round.
    public static final EngineRow CONTENT_INIT_TESSELATOR_CLASS = EngineRow.cls("CONTENT_INIT_TESSELATOR_CLASS", "com/fs/starfarer/util/Tesselator"); //
}
