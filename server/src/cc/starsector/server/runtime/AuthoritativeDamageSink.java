package cc.starsector.server.runtime;

/**
 * The boundary that applies a server-authored projectile's impact as an
 * <em>authoritative, engine-resolved</em> durability change.
 *
 * <p>The single real implementation lives in the bridge / scout (it cannot live in
 * {@code server/src}, which compiles with no game jars) and binds the engine's
 * GL-free damage descriptor
 * {@code com.fs.starfarer.api.combat.CombatEngineAPI.applyDamage(
 * java.lang.Object, com.fs.starfarer.api.combat.CombatEntityAPI,
 * org.lwjgl.util.vector.Vector2f, float, com.fs.starfarer.api.combat.DamageType,
 * float, boolean, boolean, java.lang.Object, boolean)}. That descriptor
 * was proven to run in a headless process with no OpenGL context by the C2
 * combat-interaction scout ({@code gate_combat_interaction_damage_boundary}: a target
 * ship's armor/hull dropped under {@code applyDamage} with no GL), so it is the
 * GL-free damage-application sink the projectile-spawn blocker left
 * open.
 *
 * <p>The server never reimplements damage. It supplies only the target, the impact
 * point, and the projectile's raw weapon damage/type; the engine owns armor
 * absorption, hull penetration, and the durability mutation. Implementations must be
 * fail-closed: a failed apply returns {@code false} and leaves no partial state it can
 * avoid. Keeping this an interface keeps the engine's internal identity behind a
 * project-owned boundary.
 */
public interface AuthoritativeDamageSink {

    /**
     * Apply one resolved impact's raw damage to the engine's authoritative ship state
     * and return {@code true} iff the engine accepted/resolved it. Must not throw for a
     * routine miss/guard; signal failure with {@code false}.
     */
    boolean apply(ServerAuthoredProjectileImpact impact);
}
