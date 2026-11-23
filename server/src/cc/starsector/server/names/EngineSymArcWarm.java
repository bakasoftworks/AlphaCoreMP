package cc.starsector.server.names;

/**
 * Engine names of the arc-hit serve probe and the boot warm (area ArcWarm): the EMP arc entity, its impact-set spec
 * type and the spec store's typed lookup that resolves one by id, the two fuse-family missile AIs the burst index keys
 * on, and the vanilla classes whose cold first use the boot warm pays before round 0. Rows follow the grammar of
 * {@link EngineSym}; a row that table already holds is read from there, never repeated here.
 *
 */
public final class EngineSymArcWarm {

    private EngineSymArcWarm() {
    }

    // EMP arc flavor: the arc entity, its impact-set field type, and the static store lookup (spec class, id) -> spec.
    public static final EngineRow ARCWARM_EMP_ARC_CLASS = EngineRow.cls("ARCWARM_EMP_ARC_CLASS", "com/fs/starfarer/combat/systems/EmpArcEntity"); //
    public static final EngineRow ARCWARM_IMPACT_SET_CLASS = EngineRow.cls("ARCWARM_IMPACT_SET_CLASS", "com/fs/starfarer/loading/specs/K"); //
    public static final EngineRow ARCWARM_SPEC_LOOKUP_METHOD = EngineRow.staticMethod("ARCWARM_SPEC_LOOKUP_METHOD", "com/fs/starfarer/loading/SpecStore", "o00000", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;"); //

    // Fuse-family missile AIs: each holds the burst spec object its explosion carries (also a boot-warm first use).
    public static final EngineRow ARCWARM_PROXIMITY_FUSE_AI_CLASS = EngineRow.cls("ARCWARM_PROXIMITY_FUSE_AI_CLASS", "com/fs/starfarer/combat/ai/ProximityFuseAI"); //
    public static final EngineRow ARCWARM_GUIDED_PROXIMITY_FUSE_AI_CLASS = EngineRow.cls("ARCWARM_GUIDED_PROXIMITY_FUSE_AI_CLASS", "com/fs/starfarer/combat/ai/GuidedProximityFuseAI"); //

    // Measured cold vanilla first-use classes the boot warm loads off the frame loop.
    public static final EngineRow ARCWARM_MISSILE_AI_CLASS = EngineRow.cls("ARCWARM_MISSILE_AI_CLASS", "com/fs/starfarer/combat/ai/missile/MissileAI"); //
    public static final EngineRow ARCWARM_MISSILE_AI_STATE_ENUM_CLASS = EngineRow.cls("ARCWARM_MISSILE_AI_STATE_ENUM_CLASS", "com/fs/starfarer/combat/ai/missile/MissileAI$o"); //
    public static final EngineRow ARCWARM_TEXTURE_PARTICLE_CLASS = EngineRow.cls("ARCWARM_TEXTURE_PARTICLE_CLASS", "com/fs/graphics/particle/GenericTextureParticle"); //
    public static final EngineRow ARCWARM_ESCORT_PICK_COMPARATOR_CLASS = EngineRow.cls("ARCWARM_ESCORT_PICK_COMPARATOR_CLASS", "com/fs/starfarer/combat/ai/movement/maneuvers/EscortTargetManeuverV3$4"); //
    public static final EngineRow ARCWARM_HEATSEEKER_AI_CLASS = EngineRow.cls("ARCWARM_HEATSEEKER_AI_CLASS", "com/fs/starfarer/combat/ai/missile/HeatseekerAI"); //
}
