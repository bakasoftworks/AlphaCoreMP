package cc.starsector.server.fire;

import cc.starsector.server.runtime.FireBirthSink;
import cc.starsector.server.runtime.LaunchFacingLaw;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.lang.reflect.Method;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/**
 * The confessing gun: the PRODUCT {@code OnFireEffectPlugin} the boot-time
 * {@code OnFireAttachSweep} installs by NAME onto projectile specs — the engine's own fire push
 * (it dispatches headless [observed]) confessing every
 * discharge birth into the static {@link FireBirthSink} for the shadow agreement fold against
 * the poll-and-guess detector. The engine resolves the name through ScriptStore at weapon
 * construction ({@code Class.forName} on the janino loader
 * whose parent delegation reaches the app classpath, so these statics ARE the sink's) and builds
 * one instance per weapon: hence the public no-arg ctor and the per-instance delegate below.
 *
 * <p>{@code mountIndex} is reproduced emitter-side as
 * {@code weapon.getShip().getAllWeapons().indexOf(weapon)} — the SAME raw positional ordinal the
 * mount-view walk assigns ({@code EngineCombatBinding} local {@code mountIndex++} over the
 * identical list; identity equals), −1 on any null.
 *
 * <p>Wrap lane: a wrapper built from a spec whose vanilla plugin the sweep recorded lazily
 * resolves ONE fresh original instance for itself ({@link FireBirthSink#newDelegateOrNull} —
 * fresh-per-wrapper preserves the engine's per-weapon instance semantics) and chain-calls it
 * after recording. The engine dispatch has no guard, so this class NEVER throws: the whole body
 * is caught to {@link FireBirthSink#cbError()}, the chained call separately to
 * {@link FireBirthSink#delegError()} (a delegate's own throw is its failure, not ours — and it
 * must not crash the serve).
 *
 * <p>Birth kinematics: the projectile's velocity is its FINAL flight velocity
 * before the engine dispatches onFire (the factory composes shooter-vel + û(facing)·moveSpeed
 * and the ctor sets it strictly before the onFire dispatch [observed]) — so
 * {@code flightBearingDeg} read here is the exact per-shot line including spread jitter, the
 * value the detector's v3 fresh-spawn read approximates one tick later.
 *
 * <p>Composed fallback: {@code BALLISTIC_AS_BEAM} births are {@code MovingRay}s
 * whose ENTITY velocity is never set — the ray advances via its extender, which the engine
 * seeds with the shooter's velocity (the projectile factory's ray branch → {@code MovingRay}
 * ctor → extender ctor takes {@code ship.getVelocity()}), so the engine's own flight line for
 * this class IS {@code shooterVel + projectileSpeed·û(mount ray)}. When the velocity read is
 * degenerate this composes that line (mirror of
 * {@code GameplayEdgeEventDetector.composeFlightBearingDeg}; tick-exact — the dispatch is
 * synchronous inside the fire call) instead of serving {@code NaN},
 * which the client rebase answered with a raw-ray fallback that dropped the velocity term
 * (~19° at fast strafe — the nospread regression). {@code NaN} only when
 * composition fails too; never a faked bearing. Missile births normally take
 * the direct-velocity branch (the factory-set velocity IS the launch line);
 * if a missile birth ever falls to the composed branch, its
 * {@code getProjectileSpeed()} term is the spec's MAX-not-launch speed — the threaded weaponId
 * lets the sink print its one-shot per-weapon degrade receipt.
 *
 * <p>Facing composition (the symptom "the victim's client hits, the shooter's
 * misses"): the mount-ray composition above carries NO per-shot spread draw, so a
 * ray-class weapon's bolts reached every victim collimated (three constant bearings behind 324
 * discharges) while the shooter's own engine scattered them. The zero-velocity ray's
 * {@code getFacing()} IS the jittered spawn angle on the birth tick (the factory lays the
 * from→to segment along it; the extender flies that segment's direction plus the shooter
 * velocity), so the degenerate-velocity branch now composes with the FACING first
 * ({@link FireBirthSink.BearingTier#FACING}) and falls to the mount ray only when the facing is
 * unreadable — zero added latency, never a next-tick position read. A
 * MISSILE's birth facing ALSO rides {@code aimFacingDeg} ({@link LaunchFacingLaw}); every other
 * discharge keeps the mount angle there.
 */
public final class CcOnFireEmitter implements OnFireEffectPlugin {

    /** The engine-less probe:
     *  {@code getEngineLocations()} is public on the concrete Missile class but not on
     *  {@code MissileAPI}, so it resolves reflectively ONCE from the first missile seen (the
     *  client MissilePoseLane F6 idiom; this unit runs server-side — no classloader-B deny).
     *  Empty list = free-fall bomb (pose-lane-owned); any throw = not-bomb (fail toward NOT
     *  demoting — held is never wrongly forced). Engine dispatch is single-threaded (serve
     *  thread), so plain statics suffice. */
    private static Method engineLocations;
    private static boolean engineLocationsUnresolvable;

    private boolean delegateResolved;
    private OnFireEffectPlugin delegate;

    /** The widest per-shot draw any vanilla spread reaches is well under this; a
     *  birth facing further off the mount ray is a degenerate segment read, not a draw. */
    private static final float FACING_SANITY_BAND_DEG = 60f;

    /** Signed smallest angular difference a−b in degrees, in [-180, 180). */
    private static float angDiffDeg(float a, float b) {
        float d = (a - b) % 360f;
        if (d < -180f) {
            d += 360f;
        }
        if (d >= 180f) {
            d -= 360f;
        }
        return d;
    }

    private static boolean engineLess(MissileAPI missile) {
        try {
            if (engineLocations == null) {
                if (engineLocationsUnresolvable) {
                    return false;
                }
                engineLocations = missile.getClass().getMethod("getEngineLocations");
            }
            Object raw = engineLocations.invoke(missile);
            return raw instanceof List<?> slots && slots.isEmpty();
        } catch (Throwable t) {
            engineLocationsUnresolvable = engineLocations == null;
            return false;
        }
    }

    public CcOnFireEmitter() {
        FireBirthSink.instanceBuilt();
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        try {
            Object ship = weapon == null ? null : weapon.getShip();
            int mountIndex = -1;
            if (ship != null) {
                List<WeaponAPI> allWeapons = weapon.getShip().getAllWeapons();
                if (allWeapons != null) {
                    mountIndex = allWeapons.indexOf(weapon);
                }
            }
            // adopt-serve re-pose: if the serve loop force-fired this mount to
            // serve a client-declared ray (AdoptServeRegistry posted same-tick, ship IDENTITY +
            // mount key), pose the newborn onto EXACTLY that ray BEFORE every capture below —
            // typed in-place sets (the client idiom), so the birth record and GPEE
            // muzzle-truth carry the ADOPTED ray to victims for free. No entry = organic birth,
            // untouched.
            float[] adoptedRay = cc.starsector.server.runtime.AdoptServeRegistry.consume(
                    ship, mountIndex);
            if (adoptedRay != null && projectile != null) {
                Vector2f adoptLoc = projectile.getLocation();
                if (adoptLoc != null) {
                    adoptLoc.set(adoptedRay[0], adoptedRay[1]);
                }
                // PDCL v5: the adopted ray is the
                // SHIP-frame launch ray — facing = the fired ray (vanilla: facing never inherits
                // ship velocity), velocity = the engine's own birth composition serverShipVel(now)
                // + launchSpeed·û(ray) (the ship velocity is the reconciled own-vel). The
                // v4 shape set both from the client's travel-frame vector, so a served Salamander
                // was born FACING the ship's travel direction.
                projectile.setFacing(adoptedRay[2]);
                Vector2f adoptVel = projectile.getVelocity();
                if (adoptVel != null && Float.isFinite(adoptedRay[3])) {
                    double adoptRad = Math.toRadians(adoptedRay[2]);
                    float shipVx = 0f;
                    float shipVy = 0f;
                    Vector2f adoptShipVel = weapon.getShip() == null ? null : weapon.getShip().getVelocity();
                    if (adoptShipVel != null && Float.isFinite(adoptShipVel.x)
                            && Float.isFinite(adoptShipVel.y)) {
                        shipVx = adoptShipVel.x;
                        shipVy = adoptShipVel.y;
                    }
                    adoptVel.set(shipVx + (float) (adoptedRay[3] * Math.cos(adoptRad)),
                            shipVy + (float) (adoptedRay[3] * Math.sin(adoptRad)));
                }
            }
            // A MISSILE's aimFacingDeg = its birth facing
            // (vanilla's launch angle, barrel offset included; read AFTER the adopt re-pose above,
            // which IS the born facing) through the one LaunchFacingLaw the poll path shares;
            // every other discharge keeps the mount angle. A missile whose facing is unreadable
            // falls back to the mount, COUNTED (onfire_aim_fallback on the round line).
            float mountDeg = weapon == null ? Float.NaN : weapon.getCurrAngle();
            boolean isMissile = projectile instanceof MissileAPI;
            float projFacing = projectile == null ? Float.NaN : projectile.getFacing();
            float aimFacingDeg = LaunchFacingLaw.pick(isMissile, projFacing, mountDeg);
            if (LaunchFacingLaw.fallback(isMissile, projFacing)) {
                FireBirthSink.aimFallback(weapon == null ? null : weapon.getId());
            } else if (isMissile) {
                FireBirthSink.aimFacing();
            }
            float flightBearingDeg = Float.NaN;
            // GPEE v6: |v0| rides the SAME branch its bearing came from —
            // measured hypot on the direct read, composed magnitude on the fallback; NaN
            // only when both fail (honest absence, never a faked value).
            float birthSpeed = Float.NaN;
            // Which branch the bearing rode — threaded into the birth
            // record so the watch log can split direct (jittered) vs composed (mount-ray)
            // per mount/weapon; the run-level birthComposed() counter stays as is.
            FireBirthSink.BearingTier tier = FireBirthSink.BearingTier.DIRECT;
            Vector2f velocity = projectile == null ? null : projectile.getVelocity();
            if (velocity != null && Float.isFinite(velocity.x) && Float.isFinite(velocity.y)
                    && (velocity.x != 0f || velocity.y != 0f)) {
                float bearing = (float) Math.toDegrees(Math.atan2(velocity.y, velocity.x));
                flightBearingDeg = bearing < 0f ? bearing + 360f : bearing;
                birthSpeed = (float) Math.hypot(velocity.x, velocity.y);
            } else if (weapon != null && weapon.getShip() != null) {
                Vector2f shipVel = weapon.getShip().getVelocity();
                float speed = weapon.getProjectileSpeed();
                // The per-shot JITTERED angle, read on the BIRTH tick (zero
                // added latency). A zero-velocity ray's getFacing() is the from→to
                // segment the factory laid down along the jittered spawn angle (to = from +
                // û(angle)·width/2; getFacing = atan2(to − from)),
                // and its extender then flies exactly
                // û(facing)·moveSpeed + shipVel — so the composition
                // below with the FACING is this bolt's own engine flight line, spread included;
                // the mount ray stays the fallback only (measured: 57 distinct
                // track bearings behind 3 carried constants). Sanity band: a facing
                // more than FACING_SANITY_BAND_DEG off the mount ray is a degenerate read (a
                // zero-length segment reads 0°), never a spread draw — fall back, never fake.
                float facingDeg = projFacing;
                boolean facingUsable = Float.isFinite(facingDeg) && Float.isFinite(mountDeg)
                        && Math.abs(angDiffDeg(facingDeg, mountDeg)) <= FACING_SANITY_BAND_DEG;
                float rayDeg = facingUsable ? facingDeg : mountDeg;
                if (shipVel != null && Float.isFinite(shipVel.x) && Float.isFinite(shipVel.y)
                        && Float.isFinite(rayDeg) && Float.isFinite(speed) && speed > 0f) {
                    double rayRad = Math.toRadians(rayDeg);
                    double vx = shipVel.x + speed * Math.cos(rayRad);
                    double vy = shipVel.y + speed * Math.sin(rayRad);
                    if (vx != 0.0 || vy != 0.0) {
                        float bearing = (float) Math.toDegrees(Math.atan2(vy, vx));
                        flightBearingDeg = bearing < 0f ? bearing + 360f : bearing;
                        birthSpeed = (float) Math.hypot(vx, vy);
                        if (facingUsable) {
                            tier = FireBirthSink.BearingTier.FACING;
                            FireBirthSink.birthFacing(weapon.getId());
                        } else {
                            tier = FireBirthSink.BearingTier.COMPOSED;
                            FireBirthSink.birthComposed(weapon.getId());
                        }
                    }
                }
            }
            // GPEE v8: the birth POSITION beside the birth
            // velocity — projectile.getLocation() is the engine's true spawn point, set
            // before the onFire dispatch like the velocity [observed]. Pair
            // law: both finite or both NaN — never a half-captured point.
            float muzzleX = Float.NaN;
            float muzzleY = Float.NaN;
            Vector2f location = projectile == null ? null : projectile.getLocation();
            if (location != null && Float.isFinite(location.x) && Float.isFinite(location.y)) {
                muzzleX = location.x;
                muzzleY = location.y;
            }
            FireBirthSink.record(ship, mountIndex, weapon == null ? null : weapon.getId(),
                    aimFacingDeg, flightBearingDeg, birthSpeed, muzzleX, muzzleY, tier);
            // Pose-lane-OWNED ordnance — guided or
            // flare by FACTORY AI (read at birth, before any AI replace could flip it; the
            // MissilePoseLane classification law) or engine-less free-fall bomb — confessed so
            // the serve loop demotes routed class-S mounts to held (declared serve gains
            // ~nothing for ordnance the pose lanes fly).
            if (projectile instanceof MissileAPI missile
                    && (missile.isGuided() || missile.isFlare() || engineLess(missile))) {
                FireBirthSink.recordPoseOwned(ship, mountIndex);
            }
            if (!delegateResolved) {
                delegateResolved = true;
                Object projSpec = weapon == null || weapon.getSpec() == null
                        ? null : weapon.getSpec().getProjectileSpec();
                Object resolved = projSpec == null ? null : FireBirthSink.newDelegateOrNull(projSpec);
                if (resolved instanceof OnFireEffectPlugin plugin) {
                    delegate = plugin;
                }
            }
            if (delegate != null) {
                try {
                    delegate.onFire(projectile, weapon, engine);
                } catch (Throwable delegateFailure) {
                    FireBirthSink.delegError();
                }
            }
        } catch (Throwable t) {
            FireBirthSink.cbError();
        }
    }
}
