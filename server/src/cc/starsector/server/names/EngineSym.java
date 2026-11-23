package cc.starsector.server.names;

/**
 * THE engine-name table: every engine class, package and member the server binds by reflection, as an
 * {@link EngineRow} keyed by our symbolic name, for exactly ONE game build ({@link #PIN}).
 *
 * <p>WHY one table: a game update renames every engine-internal name; with the names here, the update is one re-key of
 * this file, and the build's resolve leg proves every row against that build's jars (a class exists; a member with
 * exactly this name + descriptor + static bit is declared on its owner or up the owner's hierarchy).
 *
 * <p>GRAMMAR (the resolve leg refuses anything else, so a line tool can re-key the table): each row is ONE line,
 * <pre>
 *     public static final EngineRow KEY = EngineRow.factory("KEY", "owner"[, "name", "descriptor"]); //
 * </pre>
 * four-space indent, the key repeated as the first argument, a member name's non-ASCII character written as a
 * backslash-u escape (this file stays ASCII), and the row's home cite for the pinned build in the trailing comment.
 * A row is never split over two lines and never built from parts.
 *
 *
 */
public final class EngineSym {

    private EngineSym() {
    }

    /** The game build every row below is keyed for; the resolve leg refuses a build at any other version. */
    public static final String PIN = "0.98.5a-RC8";

    // Flare engine glow: the glow object is the RETURN type of the engine controller's getEngineGlow().
    public static final EngineRow GLOW_FLAG_NAME = EngineRow.method("GLOW_FLAG_NAME", "com/fs/starfarer/combat/entities/void", "oO0000", "()Z"); //
    public static final EngineRow GLOW_SLOT_STATE_NAME = EngineRow.method("GLOW_SLOT_STATE_NAME", "com/fs/starfarer/combat/entities/void", "o00000", "(Lcom/fs/starfarer/loading/specs/EngineSlot;)Lcom/fs/starfarer/combat/entities/void$Oo;"); //
    public static final EngineRow GLOW_SLOT_LEVEL_FIELD = EngineRow.field("GLOW_SLOT_LEVEL_FIELD", "com/fs/starfarer/combat/entities/void$Oo", "\u00D500000", "F"); //

    // Autofire plugin (the default AutofireAIPlugin) and its two autofire-manager implementations.
    public static final EngineRow AF_PLUGIN_CLASS = EngineRow.cls("AF_PLUGIN_CLASS", "com/fs/starfarer/combat/ai/O00O"); //
    public static final EngineRow AF_STATE_FIELD = EngineRow.field("AF_STATE_FIELD", "com/fs/starfarer/combat/ai/O00O", "\u00D500000", "Lcom/fs/starfarer/combat/ai/O00O$o;"); //
    public static final EngineRow AF_TARGET_FIELD = EngineRow.field("AF_TARGET_FIELD", "com/fs/starfarer/combat/ai/O00O", "\u00D8O0000", "Lcom/fs/starfarer/combat/o0OO/new;"); //
    public static final EngineRow AF_FF_LINE_FIELD = EngineRow.field("AF_FF_LINE_FIELD", "com/fs/starfarer/combat/ai/O00O", "return", "Z"); //
    public static final EngineRow AF_FF_PATH_FIELD = EngineRow.field("AF_FF_PATH_FIELD", "com/fs/starfarer/combat/ai/O00O", "\u00D5O0000", "Z"); //
    public static final EngineRow AF_CONSERVE_FIELD = EngineRow.field("AF_CONSERVE_FIELD", "com/fs/starfarer/combat/ai/O00O", "for", "Z"); //
    public static final EngineRow AF_SLEW_METHOD = EngineRow.method("AF_SLEW_METHOD", "com/fs/starfarer/combat/ai/O00O", "super", "(F)Z"); //
    public static final EngineRow MGR_V2_CLASS = EngineRow.cls("MGR_V2_CLASS", "com/fs/starfarer/combat/ai/attack/AutofireManagerV2"); //
    public static final EngineRow MGR_V2_TRACKER_FIELD = EngineRow.field("MGR_V2_TRACKER_FIELD", "com/fs/starfarer/combat/ai/attack/AutofireManagerV2", "\u00D300000", "Lcom/fs/starfarer/util/IntervalTracker;"); //
    public static final EngineRow MGR_V1_CLASS = EngineRow.cls("MGR_V1_CLASS", "com/fs/starfarer/combat/ai/attack/C"); //
    public static final EngineRow MGR_V1_TRACKER_FIELD = EngineRow.field("MGR_V1_TRACKER_FIELD", "com/fs/starfarer/combat/ai/attack/C", "\u00F500000", "Lcom/fs/starfarer/util/IntervalTracker;"); //

    // Variant registry: one engine-internal name, two static overloads (no-arg = the id list, String = the lookup).
    public static final EngineRow VARIANT_REGISTRY_CLASS = EngineRow.cls("VARIANT_REGISTRY_CLASS", "com/fs/starfarer/loading/OoOO"); //
    public static final EngineRow VARIANT_REGISTRY_IDS_METHOD = EngineRow.staticMethod("VARIANT_REGISTRY_IDS_METHOD", "com/fs/starfarer/loading/OoOO", "super", "()Ljava/util/List;"); //
    public static final EngineRow VARIANT_REGISTRY_LOOKUP_METHOD = EngineRow.staticMethod("VARIANT_REGISTRY_LOOKUP_METHOD", "com/fs/starfarer/loading/OoOO", "super", "(Ljava/lang/String;)Lcom/fs/starfarer/loading/specs/HullVariantSpec;"); //

    // Combat entity classes the binding pre-warms (each was a package prefix + simple name).
    public static final EngineRow BALLISTIC_PROJECTILE_CLASS = EngineRow.cls("BALLISTIC_PROJECTILE_CLASS", "com/fs/starfarer/combat/entities/BallisticProjectile"); //
    public static final EngineRow PLASMA_SHOT_CLASS = EngineRow.cls("PLASMA_SHOT_CLASS", "com/fs/starfarer/combat/entities/PlasmaShot"); //
    public static final EngineRow MISSILE_CLASS = EngineRow.cls("MISSILE_CLASS", "com/fs/starfarer/combat/entities/Missile"); //
    public static final EngineRow DAMAGING_EXPLOSION_CLASS = EngineRow.cls("DAMAGING_EXPLOSION_CLASS", "com/fs/starfarer/combat/entities/DamagingExplosion"); //
    public static final EngineRow CUSTOM_COMBAT_ENTITY_CLASS = EngineRow.cls("CUSTOM_COMBAT_ENTITY_CLASS", "com/fs/starfarer/combat/entities/CustomCombatEntity"); //
    public static final EngineRow BEAM_WEAPON_RAY_CLASS = EngineRow.cls("BEAM_WEAPON_RAY_CLASS", "com/fs/starfarer/combat/entities/BeamWeaponRay"); //

    // Shared across areas: classes and one package many sites name. An area file (EngineSym<Area>.java) never
    // repeats a row of this file; a site that needs one of these reads it from here.
    public static final EngineRow COMBAT_ENGINE_CLASS = EngineRow.cls("COMBAT_ENGINE_CLASS", "com/fs/starfarer/combat/CombatEngine"); //
    public static final EngineRow SHIP_CLASS = EngineRow.cls("SHIP_CLASS", "com/fs/starfarer/combat/entities/Ship"); //
    public static final EngineRow HULL_VARIANT_SPEC_CLASS = EngineRow.cls("HULL_VARIANT_SPEC_CLASS", "com/fs/starfarer/loading/specs/HullVariantSpec"); //
    public static final EngineRow FLEET_MEMBER_CLASS = EngineRow.cls("FLEET_MEMBER_CLASS", "com/fs/starfarer/campaign/fleet/FleetMember"); //
    public static final EngineRow MOD_MANAGER_CLASS = EngineRow.cls("MOD_MANAGER_CLASS", "com/fs/starfarer/launcher/ModManager"); //
    public static final EngineRow SPEC_STORE_CLASS = EngineRow.cls("SPEC_STORE_CLASS", "com/fs/starfarer/loading/SpecStore"); //
    public static final EngineRow COMBAT_FLEET_MANAGER_CLASS = EngineRow.cls("COMBAT_FLEET_MANAGER_CLASS", "com/fs/starfarer/combat/CombatFleetManager"); //
    public static final EngineRow DEPLOYED_MEMBER_CLASS = EngineRow.cls("DEPLOYED_MEMBER_CLASS", "com/fs/starfarer/combat/CombatFleetManager$O0"); //
    public static final EngineRow OBJECT_REPOSITORY_CLASS = EngineRow.cls("OBJECT_REPOSITORY_CLASS", "com/fs/util/container/repo/ObjectRepository"); //
    public static final EngineRow GRAPHICS_PKG = EngineRow.pkg("GRAPHICS_PKG", "com/fs/graphics"); //

    // Phase-cloak charge tracker: its shared elapsed accumulator and its SystemState.
    public static final EngineRow CLOAK_TRACKER_ELAPSED_FIELD = EngineRow.field("CLOAK_TRACKER_ELAPSED_FIELD", "com/fs/starfarer/combat/systems/super", "\u00D500000", "F"); //
    public static final EngineRow CLOAK_TRACKER_STATE_FIELD = EngineRow.field("CLOAK_TRACKER_STATE_FIELD", "com/fs/starfarer/combat/systems/super", "o00000", "Lcom/fs/starfarer/api/combat/ShipSystemAPI$SystemState;"); //
}
