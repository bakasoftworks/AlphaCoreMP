package cc.starsector.server.probes;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The confessing-gun probe: an observation-only {@code OnFireEffectPlugin} installed by
 * NAME onto projectile specs via the public API {@code ProjectileSpecAPI.setOnFireClassName}
 * ({@code RealEngineOnFireProbeMain} does the install pre-spawn). The engine resolves the name
 * through {@code ScriptStore} at weapon construction (by
 * {@code Class.forName(name, true, janinoLoader)} + {@code newInstance()} per weapon), so this
 * class needs the public no-arg ctor and communicates through STATIC state (one instance per
 * weapon; statics shared iff the loader delegated to the app classpath — which is itself one of
 * the probe's two questions).
 *
 * <p>This class lives in the probe-api compile unit (its sibling: the product emitter
 * {@code cc.starsector.server.fire.CcOnFireEmitter}): it must genuinely
 * {@code implements OnFireEffectPlugin} for the engine's cast, and
 * {@code server/src} deliberately compiles with no game jars on the classpath — so it lives in
 * its own unit compiled against {@code starfarer.api.jar} (the premain-separate-unit precedent).
 * Observation-only: records + prints, never throws, never touches the engine.
 */
public final class OnFireProbeEffect implements OnFireEffectPlugin {

    /** Total onFire callbacks dispatched (all weapons, all instances). */
    public static final AtomicInteger FIRES = new AtomicInteger();
    /** Callbacks whose projectile argument was null (dispatch guard says impossible; belt). */
    public static final AtomicInteger NULL_PROJECTILES = new AtomicInteger();
    /** Throwables swallowed inside the callback body (must stay 0 for a clean read). */
    public static final AtomicInteger CALLBACK_ERRORS = new AtomicInteger();
    /** Instances constructed — one per weapon built from a probe-bearing spec (latch census). */
    public static final AtomicInteger INSTANCES = new AtomicInteger();
    /**
     * First {@link #SAMPLE_CAP} callbacks, raw refs for the main's identity asserts:
     * {@code {projectile, weapon, weapon.getShip(), projectile.getSource(), engine}}.
     */
    public static final List<Object[]> SAMPLES =
            Collections.synchronizedList(new ArrayList<Object[]>());

    private static final int SAMPLE_CAP = 64;

    public OnFireProbeEffect() {
        INSTANCES.incrementAndGet();
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        try {
            int n = FIRES.incrementAndGet();
            if (projectile == null) {
                NULL_PROJECTILES.incrementAndGet();
            }
            Object weaponShip = weapon == null ? null : weapon.getShip();
            Object source = projectile == null ? null : projectile.getSource();
            if (SAMPLES.size() < SAMPLE_CAP) {
                SAMPLES.add(new Object[]{projectile, weapon, weaponShip, source, engine});
            }
            System.out.println("onfire-probe cb n=" + n
                    + " weapon=" + (weapon == null ? "null" : weapon.getId())
                    + " proj=" + (projectile == null ? "null" : projectile.getClass().getName())
                    + " src_is_weapon_ship=" + (source != null && source == weaponShip)
                    + " engine_nonnull=" + (engine != null));
        } catch (Throwable t) {
            CALLBACK_ERRORS.incrementAndGet();
            System.out.println("onfire-probe cb-error " + t);
        }
    }
}
