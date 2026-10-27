package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The VANILLA-COMPARISON convention — measure the real engine's
 * projectile velocity-inheritance law headless (game-free, L3 llvmpipe substrate),
 * so any client/presenter flight law can be adjudicated against vanilla ground truth.
 *
 * <p><b>Seam:</b> the public {@code CombatEngine.spawnProjectile(ShipAPI, WeaponAPI, String
 * weaponId, Vector2f point, float angle, Vector2f inheritedVel)} overload —
 * it feeds the SAME static composition helper the
 * live weapon-fire path uses (the fire path passes
 * {@code ship.getVelocity()} verbatim), with the
 * inherited-velocity vector as an EXPLICIT argument. Static law under test
 * (this probe is the live-bytes confirmation):
 * {@code v0 = inheritedVel(FULL vector) + unit(angle) * specLaunchSpeed}; initial facing = the
 * launch ray; unguided ROCKET missiles then thrust along frozen facing
 * ({@code v += unit(facing)*accel*dt}, soft speed cap scales the
 * WHOLE vector direction-preserving), so the inherited lateral component PERSISTS below cap;
 * ballistic projectiles stay inert (spawn velocity forever).
 *
 * <p><b>Phases</b> (one spawned projectile each, tracked per tick): missile weapon at
 * inheritedVel (0,0) / (0,lat1) / (0,lat2) + ballistic weapon at (0,lat1), all fired along
 * angle 0° from a point offset from the seat-1 hull. Per phase the probe prints a dense
 * tick series ({@code vip s ...}) and computes: {@code k} = the measured spec launch-speed
 * scalar ((v0−velArg)·û(ray)), {@code resid} = |(v0−velArg) − k·û(ray)| (law says 0),
 * lateral hold below cap, and velocity-direction convergence toward facing (missile) /
 * velocity constancy (ballistic).
 *
 * <p>Exit codes: 0 = measured (verdict {@code VANILLA_FULL_INHERIT_CONFIRMED} or
 * {@code VANILLA_LAW_DEVIATES} — both findings, read the end line); 2 = boundary thrown
 * (fly-in/spawn/step — stack printed); 1 = infrastructure failure.
 *
 *
 */
public final class RealEngineVanillaInheritProbeMain {

    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String WEAPON_API = "com.fs.starfarer.api.combat.WeaponAPI";
    private static final String ENTITY_API = "com.fs.starfarer.api.combat.CombatEntityAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "vip";

    private RealEngineVanillaInheritProbeMain() {
    }

    private static final class Rig {
        Object engine;
        Method spawnProjectile;
        Method entityGetLocation;
        Method entityGetVelocity;
        Method entityGetFacing;
        Method entityWasRemoved;   // nullable: resolve-optional per impl class
        Method shipGetLocation;
        Method shipGetVelocity;
        Field vecX;
        Field vecY;
        java.lang.reflect.Constructor<?> vec2Ctor;
    }

    /** One phase's law verdict fields (measured, not asserted — deviation is a finding). */
    private static final class PhaseResult {
        final String name;
        float k = Float.NaN;       // (v0-velArg)·û(ray): the spec launch-speed scalar
        float resid = Float.NaN;   // |(v0-velArg) - k·û(ray)|: full-inherit law says 0
        float latHoldMaxErr;       // max |vy - velArgY| over pre-cap ticks (missile law: 0)
        float latEndErr;           // |vy - velArgY| at the last tracked tick (cap decay signal)
        float ballisticMaxDv;      // ballistic only: max |Δv| across ticks (inert law: 0)
        boolean converged;         // missile: |angle(v)-ray| shrank monotonically-ish
        int ticks;

        PhaseResult(String name) {
            this.name = name;
        }
    }

    public static void main(String[] args) throws Exception {
        String missileWeapon = "hammer";
        String ballisticWeapon = "hveldriver";
        float lat1 = 250f;
        float lat2 = 125f;
        int trackFrames = 270;
        float fixedDt = SimTickRate.fixedDt();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--missile-weapon": missileWeapon = args[++i]; break;
                case "--ballistic-weapon": ballisticWeapon = args[++i]; break;
                case "--lat1": lat1 = Float.parseFloat(args[++i]); break;
                case "--lat2": lat2 = Float.parseFloat(args[++i]); break;
                case "--track-frames": trackFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin missile_weapon=" + missileWeapon
                + " ballistic_weapon=" + ballisticWeapon + " lat1=" + lat1 + " lat2=" + lat2
                + " track_frames=" + trackFrames + " fixed_dt=" + fixedDt
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        EngineCombatBinding binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        Rig rig = resolveRig();

        // Seat-1 fly-in = the projectile's source hull (owner/attribution realism); its engine AI
        // is idled per frame and it is parked motionless — inherited velocity enters ONLY through
        // the explicit spawnProjectile argument, never the hull's own motion.
        Object src;
        long tick = 1;
        try {
            src = RealEngineDemoRoster.midDeploySeat(1);
            step.step(fixedDt, tick++);
            for (int f = 0; f < 300; f++) {
                binding.idleOwnedShipAi(1L);
                step.step(fixedDt, tick++);
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=FLY_IN_THREW boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }
        Object srcVel = rig.shipGetVelocity.invoke(src);
        rig.vecX.setFloat(srcVel, 0f);
        rig.vecY.setFloat(srcVel, 0f);
        Object srcLoc = rig.shipGetLocation.invoke(src);
        float ox = rig.vecX.getFloat(srcLoc) + 400f;  // offset spawn point clear of the hull
        float oy = rig.vecY.getFloat(srcLoc) + 400f;
        System.out.println(P + " src_ready tick=" + tick + " spawn_origin=(" + f1(ox) + ","
                + f1(oy) + ")");

        PhaseResult[] results = new PhaseResult[4];
        try {
            results[0] = runPhase(rig, binding, step, src, missileWeapon + "_still",
                    missileWeapon, ox, oy, 0f, 0f, trackFrames, fixedDt, tick, false);
            tick += trackFrames + 1;
            results[1] = runPhase(rig, binding, step, src, missileWeapon + "_lat1",
                    missileWeapon, ox, oy, 0f, lat1, trackFrames, fixedDt, tick, false);
            tick += trackFrames + 1;
            results[2] = runPhase(rig, binding, step, src, missileWeapon + "_lat2",
                    missileWeapon, ox, oy, 0f, lat2, trackFrames, fixedDt, tick, false);
            tick += trackFrames + 1;
            results[3] = runPhase(rig, binding, step, src, ballisticWeapon + "_lat1",
                    ballisticWeapon, ox, oy, 0f, lat1, trackFrames, fixedDt, tick, true);
            tick += trackFrames + 1;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=PHASE_THREW boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        // Full-inherit law bar: the composition residual is sub-su/s and the pre-cap lateral
        // component holds the injected value to float noise (2 su/s on a 125-250 su/s injection).
        boolean lawHolds = true;
        StringBuilder summary = new StringBuilder();
        for (PhaseResult r : results) {
            boolean phaseOk = r.resid <= 2.0f && r.latHoldMaxErr <= 2.0f
                    && (r.ballisticMaxDv <= 0.5f || Float.isNaN(r.ballisticMaxDv));
            lawHolds = lawHolds && phaseOk;
            summary.append(" ").append(r.name).append("[k=").append(f1(r.k))
                    .append(" resid=").append(f2(r.resid))
                    .append(" lat_hold_err=").append(f2(r.latHoldMaxErr))
                    .append(" lat_end_err=").append(f1(r.latEndErr))
                    .append(Float.isNaN(r.ballisticMaxDv) ? "" : " max_dv=" + f2(r.ballisticMaxDv))
                    .append(" converged=").append(r.converged)
                    .append(" ticks=").append(r.ticks).append("]");
        }
        String verdict = lawHolds ? "VANILLA_FULL_INHERIT_CONFIRMED" : "VANILLA_LAW_DEVIATES";
        System.out.println(P + "-end verdict=" + verdict + summary + " end_tick=" + tick);
        System.exit(0);
    }

    /**
     * Spawn ONE projectile of {@code weaponId} at ({@code ox},{@code oy}) along ray 0° with
     * inherited velocity ({@code velX},{@code velY}); read v0 BEFORE any step (pure composition,
     * no thrust yet), then step+sample {@code trackFrames} ticks (dense to 45, then every 15).
     */
    private static PhaseResult runPhase(Rig rig, EngineCombatBinding binding,
            EngineBackedCombatStepBoundary step, Object src, String phase, String weaponId,
            float ox, float oy, float velX, float velY, int trackFrames, float fixedDt,
            long tick, boolean ballistic) throws Exception {
        PhaseResult r = new PhaseResult(phase);
        Object point = rig.vec2Ctor.newInstance(ox, oy);
        Object inherited = rig.vec2Ctor.newInstance(velX, velY);
        Object proj = rig.spawnProjectile.invoke(rig.engine, src, null, weaponId, point,
                0f, inherited);
        if (proj == null) {
            throw new IllegalStateException("spawnProjectile returned null for weaponId="
                    + weaponId + " (spec unknown to the engine?)");
        }
        float[] v0 = vecOf(rig, rig.entityGetVelocity.invoke(proj));
        // Ray is 0° ⇒ û(ray)=(1,0): k = along-ray excess over the injected vector, resid = the
        // off-ray remainder the full-inherit law says is zero.
        r.k = v0[0] - velX;
        r.resid = Math.abs(v0[1] - velY);
        float face0 = ((Number) rig.entityGetFacing.invoke(proj)).floatValue();
        System.out.println(P + " spawn phase=" + phase + " tick=" + tick
                + " vel_arg=(" + f1(velX) + "," + f1(velY) + ")"
                + " v0=(" + f2(v0[0]) + "," + f2(v0[1]) + ") face0=" + f2(face0)
                + " k=" + f2(r.k) + " resid=" + f2(r.resid));

        if (rig.entityWasRemoved == null) {
            rig.entityWasRemoved = optional(proj.getClass(), "wasRemoved");
        }
        float prevAbsOffRay = Math.abs(angleOffRayDeg(v0));
        float maxLatErr = 0f;
        float maxDv = 0f;
        float[] prevV = v0;
        float latEnd = 0f;
        boolean converged = true;
        int t = 0;
        for (; t < trackFrames; t++) {
            binding.idleOwnedShipAi(1L);
            step.step(fixedDt, tick + t);
            if (rig.entityWasRemoved != null && (Boolean) rig.entityWasRemoved.invoke(proj)) {
                System.out.println(P + " s phase=" + phase + " t=" + (t + 1) + " removed=true");
                break;
            }
            float[] v = vecOf(rig, rig.entityGetVelocity.invoke(proj));
            float[] loc = vecOf(rig, rig.entityGetLocation.invoke(proj));
            float face = ((Number) rig.entityGetFacing.invoke(proj)).floatValue();
            float spd = (float) Math.hypot(v[0], v[1]);
            float absOffRay = Math.abs(angleOffRayDeg(v));
            // Lateral persistence is a below-cap law: the cap scaling legitimately shrinks vy.
            if (!ballistic && spd < 495f) {
                maxLatErr = Math.max(maxLatErr, Math.abs(v[1] - velY));
            }
            if (ballistic) {
                maxDv = Math.max(maxDv, (float) Math.hypot(v[0] - prevV[0], v[1] - prevV[1]));
            }
            if (absOffRay > prevAbsOffRay + 0.75f) {
                converged = false;  // velocity direction moved AWAY from the ray
            }
            prevAbsOffRay = absOffRay;
            prevV = v;
            latEnd = Math.abs(v[1] - velY);
            if (t < 45 || t % 15 == 0 || t == trackFrames - 1) {
                System.out.println(P + " s phase=" + phase + " t=" + (t + 1)
                        + " x=" + f1(loc[0]) + " y=" + f1(loc[1])
                        + " vx=" + f2(v[0]) + " vy=" + f2(v[1]) + " spd=" + f1(spd)
                        + " face=" + f2(face) + " off_ray=" + f2(angleOffRayDeg(v)));
            }
        }
        r.latHoldMaxErr = ballistic ? 0f : maxLatErr;
        r.latEndErr = latEnd;
        r.ballisticMaxDv = ballistic ? maxDv : Float.NaN;
        r.converged = ballistic || converged;
        r.ticks = t;
        return r;
    }

    /** Signed angle of {@code v} off the 0° launch ray, degrees. */
    private static float angleOffRayDeg(float[] v) {
        return (float) Math.toDegrees(Math.atan2(v[1], v[0]));
    }

    private static Rig resolveRig() throws Exception {
        ClassLoader loader = RealEngineVanillaInheritProbeMain.class.getClassLoader();
        Rig r = new Rig();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        r.engine = engineClass.getMethod("getInstance").invoke(null);
        if (r.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        Class<?> weaponApi = Class.forName(WEAPON_API, false, loader);
        Class<?> entityApi = Class.forName(ENTITY_API, false, loader);
        Class<?> vec2 = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        r.spawnProjectile = engineClass.getMethod("spawnProjectile",
                shipApi, weaponApi, String.class, vec2, float.class, vec2);
        r.entityGetLocation = entityApi.getMethod("getLocation");
        r.entityGetVelocity = entityApi.getMethod("getVelocity");
        r.entityGetFacing = entityApi.getMethod("getFacing");
        Class<?> shipClass = Class.forName("com.fs.starfarer.combat.entities.Ship", false, loader);
        r.shipGetLocation = shipClass.getMethod("getLocation");
        r.shipGetVelocity = shipClass.getMethod("getVelocity");
        r.vecX = vec2.getField("x");
        r.vecY = vec2.getField("y");
        r.vec2Ctor = vec2.getConstructor(float.class, float.class);
        return r;
    }

    private static float[] vecOf(Rig rig, Object v) throws Exception {
        return new float[]{rig.vecX.getFloat(v), rig.vecY.getFloat(v)};
    }

    private static Method optional(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }

    private static String f2(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }
}
