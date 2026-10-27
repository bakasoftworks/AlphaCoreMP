package cc.starsector.server.bridge;

import static cc.starsector.server.bridge.EngineCombatBinding.readXY;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The GPEE fresh-spawn scan: per firing weapon, the just-spawned projectile's own velocity
 * bearing, muzzle position and (missiles only) birth facing, read off the engine's live
 * MISSILE then PROJECTILE lists and keyed by the weapon's engine object identity — the
 * {@code freshSpawn*} scalars {@link EngineCombatBinding#weaponMountStatesOrNull()} stamps
 * onto each mount view.
 *
 * <p>WHY its own file: carved VERBATIM out of {@code EngineCombatBinding}
 * (the ECB fresh-spawn scan carve) while
 * ECB sat 3 nb under its warn tier. This is the growth home for fresh-spawn read work —
 * the birth-speed read (the spawn's own |velocity| as the poll-path birth speed) lands
 * HERE, never in ECB.
 *
 * <p>Port = the five injected {@code projectileGet*} {@link Method} handles: the real engine
 * binds {@code DamagingProjectileAPI}/{@code CombatEntityAPI} interface Methods
 * ({@code RealEngineCombatBindingFactory} → the ECB Builder's {@code projectileSpawnReads},
 * still the one bind point), so a game-free fake drives this byte-identical reflective body
 * ({@code EngineBridgeSmoke} fresh-spawn rows). The per-round elapsed window stays ECB state
 * ({@link EngineCombatBinding#configureSimTickRate}) and arrives per call. No lock, no
 * thread: constructed once at the ECB ctor end, read on the caller's (sim) thread; the result
 * map is a per-call local.
 */
final class FreshSpawnScan {

    private final Object engine;
    private final Method combatGetProjectiles; // nullable: a copy of ECB's list handle (ECB keeps its own)
    private final Method combatGetMissiles; // nullable: a copy of ECB's list handle (ECB keeps its own)
    private final Method projectileGetElapsed; // nullable: GPEE-v3 freshSpawnBearingDeg reads NaN (tier stays available)
    private final Method projectileGetWeapon; // nullable: same v3 degrade
    private final Method projectileGetVelocity; // nullable: same v3 degrade
    private final Method projectileGetLocation; // nullable: GPEE-v8 freshSpawnMuzzleX/Y read NaN (same degrade)
    private final Method projectileGetFacing; // nullable: freshSpawnFacingDeg reads NaN (same degrade)

    FreshSpawnScan(Object engine, Method combatGetProjectiles, Method combatGetMissiles,
            Method projectileGetElapsed, Method projectileGetWeapon, Method projectileGetVelocity,
            Method projectileGetLocation, Method projectileGetFacing) {
        this.engine = engine;
        this.combatGetProjectiles = combatGetProjectiles;
        this.combatGetMissiles = combatGetMissiles;
        this.projectileGetElapsed = projectileGetElapsed;
        this.projectileGetWeapon = projectileGetWeapon;
        this.projectileGetVelocity = projectileGetVelocity;
        this.projectileGetLocation = projectileGetLocation;
        this.projectileGetFacing = projectileGetFacing;
    }

    /**
     * One fresh-spawn read result (GPEE v3 bearing — NaN for a zero-velocity missile: empty
     * launch speed — + v8 muzzle): the muzzle pair is the
     * just-spawned entity's own {@code getLocation()} — both finite (one captured point) or both
     * NaN (unbound handle / unreadable pair), never mixed.
     * {@code facingDeg} = a MISSILE-list entity's own {@code getFacing()} (else NaN) and
     * {@code missile} = it was scanned from the engine's MISSILE list — the poll path's
     * {@code LaunchFacingLaw} inputs (only a missile's facing reaches the wire aim).
     */
    record FreshSpawn(float bearingDeg, float muzzleX, float muzzleY, float facingDeg,
            boolean missile) {
    }

    /**
     * GPEE v3 read: the TRUE initial
     * bearing of each weapon's just-spawned projectile — {@code atan2} of the live projectile
     * entity's own velocity, the engine-composed line INCLUDING the per-shot spread jitter the
     * v2 mount-ray composition can never see — keyed by the firing weapon's engine object
     * identity ({@code DamagingProjectileAPI.getWeapon()}, reference-equal to the
     * {@code ShipAPI.getAllWeapons()} element the mount tier iterates); since v8 also the
     * entity's own {@code getLocation()}, the server-true muzzle position where the same spawn was
     * born. Scans missiles THEN projectiles
     * ({@code MissileAPI extends DamagingProjectileAPI}; the projectile list holds the
     * missiles too — the scan tags a missile spawn by the list it was found in
     * FIRST, and also carries its birth facing). First fresh projectile per
     * weapon wins (a linked-barrel multi-spawn carries one representative line). Fail-soft:
     * unbound handles or a failed list read return an empty map; a failing per-entity read
     * skips that entity; a failing location read alone degrades just that entry's muzzle
     * pair to NaN — the mount tier itself never degrades on this supplementary read.
     */
    Map<Object, FreshSpawn> freshSpawnBearingsByWeapon(float freshSpawnElapsedMaxSeconds) {
        if (projectileGetElapsed == null || projectileGetWeapon == null
                || projectileGetVelocity == null || combatGetProjectiles == null) {
            return Collections.emptyMap();
        }
        try {
            Map<Object, FreshSpawn> byWeapon = new IdentityHashMap<>();
            // The MISSILE list FIRST. The engine's
            // ObjectRepository files every entity under ALL its supertypes,
            // so a missile ALSO sits in getProjectiles();
            // first-per-weapon wins, so only this order tags a missile spawn missile=true.
            if (combatGetMissiles != null) {
                scanFreshSpawns(combatGetMissiles.invoke(engine), byWeapon, true, freshSpawnElapsedMaxSeconds);
            }
            scanFreshSpawns(combatGetProjectiles.invoke(engine), byWeapon, false, freshSpawnElapsedMaxSeconds);
            return byWeapon;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Collections.emptyMap();
        }
    }

    private void scanFreshSpawns(Object listValue, Map<Object, FreshSpawn> byWeapon,
            boolean missileList, float freshSpawnElapsedMaxSeconds) {
        if (!(listValue instanceof List<?>)) {
            return;
        }
        for (Object entity : (List<?>) listValue) {
            if (entity == null) {
                continue;
            }
            try {
                Object elapsedValue = projectileGetElapsed.invoke(entity);
                if (!(elapsedValue instanceof Number)) {
                    continue;
                }
                float elapsed = ((Number) elapsedValue).floatValue();
                if (!Float.isFinite(elapsed) || elapsed > freshSpawnElapsedMaxSeconds) {
                    continue;
                }
                Object weapon = projectileGetWeapon.invoke(entity);
                if (weapon == null || byWeapon.containsKey(weapon)) {
                    continue;
                }
                float[] velocity = readXY(projectileGetVelocity.invoke(entity));
                boolean lineOk = velocity != null && Float.isFinite(velocity[0])
                        && Float.isFinite(velocity[1]) && (velocity[0] != 0f || velocity[1] != 0f);
                if (!lineOk && !missileList) { // A zero-velocity missile (EMPTY
                    continue; // launch-speed column, mostly modded; vanilla 40-150) stays for its facing, NaN bearing
                }
                float bearingDeg = lineOk ? (float) Math.toDegrees(Math.atan2(velocity[1], velocity[0])) : Float.NaN;
                // v8 muzzle pair: finite-pair-else-NaN, own inner try so a location
                // failure degrades only the pair, never this entry's bearing.
                float muzzleX = Float.NaN;
                float muzzleY = Float.NaN;
                if (projectileGetLocation != null) {
                    try {
                        float[] location = readXY(projectileGetLocation.invoke(entity));
                        if (location != null
                                && Float.isFinite(location[0]) && Float.isFinite(location[1])) {
                            muzzleX = location[0];
                            muzzleY = location[1];
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // muzzle pair stays NaN; the bearing entry itself still lands.
                    }
                }
                // The birth facing, own inner try like
                // the muzzle pair — a failed read degrades only this field (a counted mount
                // fallback downstream), never the entry. MISSILE list only.
                float facingDeg = Float.NaN;
                if (missileList && projectileGetFacing != null) {
                    try {
                        Object facingValue = projectileGetFacing.invoke(entity);
                        if (facingValue instanceof Number
                                && Float.isFinite(((Number) facingValue).floatValue())) {
                            facingDeg = ((Number) facingValue).floatValue();
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // facing stays NaN; the bearing entry itself still lands.
                    }
                }
                byWeapon.put(weapon, new FreshSpawn(
                        bearingDeg < 0.0f ? bearingDeg + 360.0f : bearingDeg, muzzleX, muzzleY,
                        facingDeg, missileList));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // this entity's reads failed — skip it, keep scanning (per-scalar fail-soft)
            }
        }
    }
}
