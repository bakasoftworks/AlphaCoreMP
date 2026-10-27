package cc.starsector.server.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The headless FRAGMENT-SWARM CENSUS: proves on the server, per live ship
 * carrying the Threat {@code fragment_swarm} hullmod, whether vanilla's own fragment-weapon firing
 * law can pass headless — instead of reasoning about it.
 *
 * <p>The law it measures: every frame the fragment weapon's own effect
 * ({@code BaseFragmentMissileEffect.advance})
 * reads {@code RoilingSwarmEffect.getSwarmFor(ship)}, {@code active = swarm.getNumActiveMembers()}
 * and calls {@code weapon.setForceDisabled(active < required)}; {@code onFire} deletes the
 * shot when the swarm is null or no fragment can be picked. The swarm OBJECT is created by the
 * hullmod ({@code FragmentSwarmHullmod.advanceInCombat}); its MEMBERS are spawned only inside
 * {@code RoilingSwarmEffect.advance} (initial spawn and respawn) — a
 * {@code BaseCombatLayeredRenderingPlugin} whose {@code advance} needs {@code entity != null}
 * (set by the engine's {@code init} after {@code addLayeredRenderingPlugin}) and whose
 * {@code SwarmMember} ctor loads a sprite through {@code Global.getSettings().getSprite}.
 * The row therefore carries swarm presence, member/active/maintain counts, the {@code entity} and
 * {@code spawnedInitial} state, and per fragment weapon its {@code isForceDisabled()} — plus a fire
 * census (new live missiles whose weapon spec carries the {@code fragment} tag; a fragment shot that
 * survives {@code onFire} lives on the missile list, a deleted one never reaches it).
 *
 * <p>Armed ONLY by {@code -Dcc.probe.fragmentSwarmCensus=true} (or env
 * {@code CC_PROBE_FRAGMENT_SWARM_CENSUS=true}; registered in {@code PostureRegistry}).
 * OFF = one static boolean test per tick, zero engine reads. Fail-soft: any reflective failure disarms
 * the probe for the session and logs once; it can never refuse or stall the serve. The one MUTATING
 * diagnostic ({@code diag-addmember}: one {@code addMember()} on an EMPTY swarm to surface the ctor's
 * throwable) rolls its member back in a {@code finally} and runs once per swarm. Sink = capped
 * {@code System.out} rows ({@code server.out.log}), {@link #ROW_CAP} total. Reflection is server-side
 * only (never rides the client-mod jar); every handle is a public API method resolved off the live
 * instances' own loader.
 */
public final class FragmentSwarmCensusProbe {
    public static final String PROP = "cc.probe.fragmentSwarmCensus";
    public static final String ENV = "CC_PROBE_FRAGMENT_SWARM_CENSUS";
    public static final boolean ARMED = resolveArmed(System.getProperty(PROP), System.getenv(ENV));
    static final int CENSUS_EVERY_TICKS = 300;
    static final int MISSILE_SCAN_EVERY_TICKS = 20;
    static final int ROW_CAP = 4000;
    static final String HULLMOD = "fragment_swarm";
    static final String FRAGMENT_TAG = "fragment";
    private static final String SWARM_CLASS = "com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect";
    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";

    /** Arm / row-cap / disarm skeleton (carved to {@link CappedProbeSink}); the census logic stays here. */
    private static final CappedProbeSink SINK = new CappedProbeSink("fragswarm-probe", PROP, ROW_CAP);
    private static Api api;
    private static Map<Object, Boolean> seenMissiles = new IdentityHashMap<>();
    private static final Set<Object> DIAG_DONE = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String, Integer> FIRES_BY_WEAPON = new TreeMap<>();
    private static final Map<String, Integer> FIRES_BY_SOURCE = new TreeMap<>();
    private static long newMissilesTotal;
    private static long fragmentFiresTotal;

    // ---- unstable-fragment probe (per tick while armed) ----
    /** {@code weapon_data.csv} id of the Threat unstable fragment. */
    static final String UNSTABLE_WEAPON_ID = "unstable_fragment";
    /** {@code unstable_fragment_missile.proj} explosionSpec radius / the weapon's 750 base damage
     *  ({@code ProximityFuseAI.updateDamage} copies it into {@code setMaxDamage}) / FRAGMENTATION. */
    static final float UNSTABLE_DET_RADIUS = 120f;
    static final float UNSTABLE_DET_MAX_DAMAGE = 750f;
    static final String UNSTABLE_DET_DAMAGE_TYPE = "FRAGMENTATION";
    /** Per-end / per-det row cap (the census summary keeps counting past it). */
    static final int UNSTABLE_ROW_MAX = 400;
    private static final String EXPLOSION_SIMPLE_NAME = "DamagingExplosion";
    private static Map<Object, Boolean> tickMissiles = new IdentityHashMap<>();
    private static Map<Object, Long> unstableLive = new IdentityHashMap<>();
    private static Set<Object> seenExplosions = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Map<Object, Long> unstableDetsLive = new IdentityHashMap<>();
    private static long unstableFired, unstableEndDestroyed, unstableEndFizzling, unstableEndIntact,
            unstableEndDidDamage, unstableDetsBorn, unstableDetsHit, unstableDetsMiss,
            unstableDetsDamagedEntities, otherExplosionsBorn;
    private static int unstableRows;

    private FragmentSwarmCensusProbe() {
    }

    /** Pure arm law (contract-pinned; lives in {@link CappedProbeSink#resolveArmed}): sysprop {@code true}
     *  OR env {@code true}, case-insensitive; else OFF. */
    public static boolean resolveArmed(String sysprop, String env) {
        return CappedProbeSink.resolveArmed(sysprop, env);
    }

    /** Bound once against the first ship's own loader; every handle is a public API interface method. */
    private static final class Api {
        final Method globalGetCombatEngine;
        final Method engineGetMissiles;
        final Method shipGetId, shipGetHullSpec, shipIsAlive, shipIsHulk, shipIsFighter, shipGetOwner,
                shipGetCurrentCR, shipGetVariant, shipGetAllWeapons;
        final Method hullGetHullId;
        final Method variantHasHullMod;
        final Method weaponGetId, weaponGetSpec, weaponIsForceDisabled, weaponGetCooldownRemaining,
                weaponGetAmmo, weaponUsesAmmo;
        final Method specHasTag;
        final Method swarmGetSwarmFor, swarmGetNumActiveMembers, swarmGetNumMembersToMaintain,
                swarmGetMembers, swarmGetEntity, swarmAddMember, swarmRemoveMember;
        final Field swarmSpawnedInitial; // protected on RoilingSwarmEffect; nullable (row prints '-')
        final Field swarmParams; // nullable
        final Method missileGetWeapon, missileGetSource;
        // Unstable probe handles (all public API interface methods)
        final Method engineGetProjectiles, missileIsFizzling, entityGetHitpoints, entityGetLocation,
                projectileDidDamage;

        Api(Object ship) throws Exception {
            ClassLoader loader = ship.getClass().getClassLoader();
            Class<?> global = Class.forName(GLOBAL_CLASS, false, loader);
            Class<?> engineApi = Class.forName("com.fs.starfarer.api.combat.CombatEngineAPI", false, loader);
            Class<?> shipApi = Class.forName("com.fs.starfarer.api.combat.ShipAPI", false, loader);
            Class<?> hullApi = Class.forName("com.fs.starfarer.api.combat.ShipHullSpecAPI", false, loader);
            Class<?> variantApi = Class.forName("com.fs.starfarer.api.combat.ShipVariantAPI", false, loader);
            Class<?> weaponApi = Class.forName("com.fs.starfarer.api.combat.WeaponAPI", false, loader);
            Class<?> weaponSpecApi = Class.forName("com.fs.starfarer.api.loading.WeaponSpecAPI", false, loader);
            Class<?> entityApi = Class.forName("com.fs.starfarer.api.combat.CombatEntityAPI", false, loader);
            Class<?> missileApi = Class.forName("com.fs.starfarer.api.combat.MissileAPI", false, loader);
            Class<?> swarm = Class.forName(SWARM_CLASS, false, loader);
            globalGetCombatEngine = global.getMethod("getCombatEngine");
            engineGetMissiles = engineApi.getMethod("getMissiles");
            shipGetId = shipApi.getMethod("getId");
            shipGetHullSpec = shipApi.getMethod("getHullSpec");
            shipIsAlive = shipApi.getMethod("isAlive");
            shipIsHulk = shipApi.getMethod("isHulk");
            shipIsFighter = shipApi.getMethod("isFighter");
            shipGetOwner = shipApi.getMethod("getOwner");
            shipGetCurrentCR = shipApi.getMethod("getCurrentCR");
            shipGetVariant = shipApi.getMethod("getVariant");
            shipGetAllWeapons = shipApi.getMethod("getAllWeapons");
            hullGetHullId = hullApi.getMethod("getHullId");
            variantHasHullMod = variantApi.getMethod("hasHullMod", String.class);
            weaponGetId = weaponApi.getMethod("getId");
            weaponGetSpec = weaponApi.getMethod("getSpec");
            weaponIsForceDisabled = weaponApi.getMethod("isForceDisabled");
            weaponGetCooldownRemaining = weaponApi.getMethod("getCooldownRemaining");
            weaponGetAmmo = weaponApi.getMethod("getAmmo");
            weaponUsesAmmo = weaponApi.getMethod("usesAmmo");
            specHasTag = weaponSpecApi.getMethod("hasTag", String.class);
            swarmGetSwarmFor = swarm.getMethod("getSwarmFor", entityApi);
            swarmGetNumActiveMembers = swarm.getMethod("getNumActiveMembers");
            swarmGetNumMembersToMaintain = swarm.getMethod("getNumMembersToMaintain");
            swarmGetMembers = swarm.getMethod("getMembers");
            swarmGetEntity = swarm.getMethod("getEntity");
            swarmAddMember = swarm.getMethod("addMember");
            swarmRemoveMember = swarm.getMethod("removeMember", swarm.getMethod("addMember").getReturnType());
            swarmSpawnedInitial = declaredFieldOrNull(swarm, "spawnedInitial");
            swarmParams = declaredFieldOrNull(swarm, "params");
            missileGetWeapon = missileApi.getMethod("getWeapon");
            missileGetSource = missileApi.getMethod("getSource");
            Class<?> damagingProjApi =
                    Class.forName("com.fs.starfarer.api.combat.DamagingProjectileAPI", false, loader);
            engineGetProjectiles = engineApi.getMethod("getProjectiles");
            missileIsFizzling = missileApi.getMethod("isFizzling");
            entityGetHitpoints = entityApi.getMethod("getHitpoints");
            entityGetLocation = entityApi.getMethod("getLocation");
            projectileDidDamage = damagingProjApi.getMethod("didDamage");
        }

        private static Field declaredFieldOrNull(Class<?> c, String name) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * One tick sample over the engine's live ship list ({@code binding.shipList()}), called after the
     * engine advance; census rows every {@link #CENSUS_EVERY_TICKS}, the missile scan every
     * {@link #MISSILE_SCAN_EVERY_TICKS}.
     */
    static void sample(List<?> ships, long simTick) {
        if (!ARMED || SINK.closed() || ships == null || ships.isEmpty()) {
            return;
        }
        boolean census = simTick % CENSUS_EVERY_TICKS == 0L;
        boolean scan = simTick % MISSILE_SCAN_EVERY_TICKS == 0L;
        try {
            if (api == null) {
                api = new Api(ships.get(0));
            }
            if (!SINK.armedLogged()) {
                SINK.logArmed(" censusEvery=" + CENSUS_EVERY_TICKS + " missileScanEvery=" + MISSILE_SCAN_EVERY_TICKS
                        + " unstableScan=everyTick");
            }
            scanUnstable(simTick); // Every tick — the det lives 0.12 s (7 ticks)
            if (scan) {
                scanMissiles();
            }
            if (census) {
                for (Object ship : new ArrayList<>(ships)) {
                    censusShip(ship, simTick);
                }
                printFires(simTick);
                printUnstableCensus(simTick);
            }
        } catch (Throwable t) {
            SINK.disarm(t);
        }
    }

    private static void scanMissiles() throws Exception {
        Object engine = api.globalGetCombatEngine.invoke(null);
        if (engine == null) {
            return;
        }
        List<?> missiles = (List<?>) api.engineGetMissiles.invoke(engine);
        Map<Object, Boolean> next = new IdentityHashMap<>();
        for (Object m : missiles) {
            next.put(m, Boolean.TRUE);
            if (seenMissiles.containsKey(m)) {
                continue;
            }
            newMissilesTotal++;
            Object weapon = api.missileGetWeapon.invoke(m);
            if (weapon == null) {
                continue;
            }
            Object spec = api.weaponGetSpec.invoke(weapon);
            if (spec == null || !((Boolean) api.specHasTag.invoke(spec, FRAGMENT_TAG)).booleanValue()) {
                continue;
            }
            fragmentFiresTotal++;
            FIRES_BY_WEAPON.merge(String.valueOf(api.weaponGetId.invoke(weapon)), 1, Integer::sum);
            Object src = api.missileGetSource.invoke(m);
            FIRES_BY_SOURCE.merge(src == null ? "-" : String.valueOf(api.shipGetId.invoke(src)), 1, Integer::sum);
        }
        seenMissiles = next;
    }

    private static void censusShip(Object ship, long simTick) throws Exception {
        if (!((Boolean) api.shipIsAlive.invoke(ship)).booleanValue()
                || ((Boolean) api.shipIsHulk.invoke(ship)).booleanValue()) {
            return;
        }
        Object variant = api.shipGetVariant.invoke(ship);
        if (variant == null || !((Boolean) api.variantHasHullMod.invoke(variant, HULLMOD)).booleanValue()) {
            return;
        }
        Object hull = api.shipGetHullSpec.invoke(ship);
        String hullId = hull == null ? "-" : String.valueOf(api.hullGetHullId.invoke(hull));
        Object swarm = api.swarmGetSwarmFor.invoke(null, ship);
        int members = -1;
        int active = -1;
        int maintain = -1;
        String init = "-";
        String spawnedInitial = "-";
        String entity = "-";
        if (swarm != null) {
            List<?> list = (List<?>) api.swarmGetMembers.invoke(swarm);
            members = list == null ? -1 : list.size();
            active = ((Number) api.swarmGetNumActiveMembers.invoke(swarm)).intValue();
            maintain = ((Number) api.swarmGetNumMembersToMaintain.invoke(swarm)).intValue();
            entity = api.swarmGetEntity.invoke(swarm) == null ? "null" : "set";
            if (api.swarmSpawnedInitial != null) {
                spawnedInitial = String.valueOf(api.swarmSpawnedInitial.get(swarm));
            }
            if (api.swarmParams != null) {
                Object params = api.swarmParams.get(swarm);
                if (params != null) {
                    Field f = params.getClass().getField("initialMembers");
                    init = String.valueOf(f.get(params));
                }
            }
        }
        StringBuilder fw = new StringBuilder();
        int forced = 0;
        int fragWeapons = 0;
        for (Object w : (List<?>) api.shipGetAllWeapons.invoke(ship)) {
            Object spec = api.weaponGetSpec.invoke(w);
            if (spec == null || !((Boolean) api.specHasTag.invoke(spec, FRAGMENT_TAG)).booleanValue()) {
                continue;
            }
            fragWeapons++;
            boolean fd = ((Boolean) api.weaponIsForceDisabled.invoke(w)).booleanValue();
            if (fd) {
                forced++;
            }
            float cd = ((Number) api.weaponGetCooldownRemaining.invoke(w)).floatValue();
            boolean usesAmmo = ((Boolean) api.weaponUsesAmmo.invoke(w)).booleanValue();
            int ammo = usesAmmo ? ((Number) api.weaponGetAmmo.invoke(w)).intValue() : -1;
            if (fw.length() > 0) {
                fw.append(',');
            }
            fw.append(api.weaponGetId.invoke(w)).append(fd ? ":FORCED" : ":ok")
                    .append(String.format(Locale.ROOT, " cd=%.1f ammo=%d", cd, ammo));
        }
        SINK.row(String.format(Locale.ROOT,
                "fragswarm tick=%d ship=%s hull=%s owner=%s fighter=%s cr=%.2f swarm=%s members=%d active=%d"
                        + " maintain=%d init=%s spawnedInitial=%s entity=%s forced=%d/%d fw=[%s]",
                simTick, api.shipGetId.invoke(ship), hullId, api.shipGetOwner.invoke(ship),
                api.shipIsFighter.invoke(ship),
                // The proof field — a script-born Threat wing reads 0.50 (vanilla
                // tracker CR), never the 1.00 deploy floor that zeroed reclamation homing.
                ((Number) api.shipGetCurrentCR.invoke(ship)).floatValue(),
                swarm == null ? "absent" : "present", members, active, maintain,
                init, spawnedInitial, entity, forced, fragWeapons, fw));
        if (swarm != null && members == 0 && DIAG_DONE.add(swarm)) {
            diagAddMember(swarm, simTick, hullId);
        }
    }

    /**
     * Guarded mutating diagnostic (once per EMPTY swarm): one {@code addMember()} to surface the
     * {@code SwarmMember} ctor's headless verdict (the sprite load), rolled
     * back via {@code removeMember} in a finally so the census never changes the swarm it measures.
     */
    private static void diagAddMember(Object swarm, long simTick, String hullId) {
        Object added = null;
        try {
            added = api.swarmAddMember.invoke(swarm);
            Object sprite = added == null ? null : added.getClass().getField("sprite").get(added);
            System.out.println("fragswarm-diag tick=" + simTick + " hull=" + hullId + " addMember=OK sprite="
                    + (sprite == null ? "null" : sprite.getClass().getName()));
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println("fragswarm-diag tick=" + simTick + " hull=" + hullId + " addMember=THREW " + cause);
        } finally {
            if (added != null) {
                try {
                    api.swarmRemoveMember.invoke(swarm, added);
                } catch (Throwable ignored) {
                    // rollback best-effort; the diag is one member on a swarm the engine maintains anyway
                }
            }
        }
    }

    /**
     * The unstable-fragment END + DET probe: per tick,
     * (i) every NEW live missile whose weapon is {@link #UNSTABLE_WEAPON_ID} is tracked; a tracked
     * missile that left the list is triaged POST-MORTEM exactly as the wire's MISSILE_END
     * ({@code EngineCombatBinding} end-cause: hp≤0 ⇒ DESTROYED, else isFizzling ⇒ FIZZLING, else
     * INTACT) plus {@code didDamage()}; (ii) every NEW {@code DamagingExplosion} whose spec is the
     * unstable det fingerprint (r120 / 750 / FRAGMENTATION) is tracked; when it leaves the list
     * its {@code getDamagedAlready().size()} is the applied-damage truth. Vanilla's fuse
     * ({@code ProximityFuseAI.advance}) dets on {@code isFading()} (⇒ FIZZLING here) or on
     * proximity (⇒ INTACT); a PD kill (DESTROYED) never dets — so the split decides the client keying.
     */
    private static void scanUnstable(long simTick) throws Exception {
        Object engine = api.globalGetCombatEngine.invoke(null);
        if (engine == null) {
            return;
        }
        List<?> missiles = (List<?>) api.engineGetMissiles.invoke(engine);
        Map<Object, Boolean> now = new IdentityHashMap<>();
        for (Object m : missiles) {
            now.put(m, Boolean.TRUE);
            if (tickMissiles.containsKey(m)) {
                continue;
            }
            Object weapon = api.missileGetWeapon.invoke(m);
            if (weapon != null && UNSTABLE_WEAPON_ID.equals(api.weaponGetId.invoke(weapon))) {
                unstableLive.put(m, simTick);
                unstableFired++;
            }
        }
        tickMissiles = now;
        if (!unstableLive.isEmpty()) {
            java.util.Iterator<Map.Entry<Object, Long>> it = unstableLive.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Object, Long> e = it.next();
                Object m = e.getKey();
                if (now.containsKey(m)) {
                    continue;
                }
                // Read the entry BEFORE it.remove() — an IdentityHashMap entry throws
                // "Entry was removed" on any access after removal (the probe run
                // self-disarmed at tick 1800 on exactly this).
                long born = e.getValue().longValue();
                it.remove();
                float hp = ((Number) api.entityGetHitpoints.invoke(m)).floatValue();
                boolean fizz = ((Boolean) api.missileIsFizzling.invoke(m)).booleanValue();
                boolean dd = ((Boolean) api.projectileDidDamage.invoke(m)).booleanValue();
                String cause;
                if (hp <= 0f) {
                    cause = "DESTROYED";
                    unstableEndDestroyed++;
                } else if (fizz) {
                    cause = "FIZZLING";
                    unstableEndFizzling++;
                } else {
                    cause = "INTACT";
                    unstableEndIntact++;
                }
                if (dd) {
                    unstableEndDidDamage++;
                }
                if (unstableRows < UNSTABLE_ROW_MAX) {
                    unstableRows++;
                    System.out.println(String.format(Locale.ROOT,
                            "unstable-end tick=%d born=%d age=%d cause=%s didDamage=%s hp=%.0f",
                            simTick, born, simTick - born, cause, dd, hp));
                }
            }
        }
        List<?> projectiles = (List<?>) api.engineGetProjectiles.invoke(engine);
        Set<Object> explosionsNow = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object p : projectiles) {
            if (p == null || !EXPLOSION_SIMPLE_NAME.equals(p.getClass().getSimpleName())) {
                continue;
            }
            explosionsNow.add(p);
            if (seenExplosions.contains(p)) {
                continue;
            }
            if (!isUnstableDetSpec(p)) {
                otherExplosionsBorn++;
                continue;
            }
            unstableDetsLive.put(p, simTick);
            unstableDetsBorn++;
            if (unstableRows < UNSTABLE_ROW_MAX) {
                unstableRows++;
                float[] xy = xyOrNaN(api.entityGetLocation.invoke(p));
                System.out.println(String.format(Locale.ROOT,
                        "unstable-det BORN tick=%d loc=%.0f,%.0f liveFragments=%d",
                        simTick, xy[0], xy[1], unstableLive.size()));
            }
        }
        seenExplosions = explosionsNow;
        if (!unstableDetsLive.isEmpty()) {
            java.util.Iterator<Map.Entry<Object, Long>> it = unstableDetsLive.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Object, Long> e = it.next();
                Object d = e.getKey();
                if (explosionsNow.contains(d)) {
                    continue;
                }
                long born = e.getValue().longValue(); // before it.remove() (same law as above)
                it.remove();
                Object damaged = d.getClass().getMethod("getDamagedAlready").invoke(d);
                int n = damaged instanceof List<?> ? ((List<?>) damaged).size() : -1;
                if (n > 0) {
                    unstableDetsHit++;
                    unstableDetsDamagedEntities += n;
                } else {
                    unstableDetsMiss++;
                }
                if (unstableRows < UNSTABLE_ROW_MAX) {
                    unstableRows++;
                    System.out.println(String.format(Locale.ROOT,
                            "unstable-det END tick=%d born=%d lived=%d damagedAlready=%d",
                            simTick, born, simTick - born, n));
                }
            }
        }
    }

    /** Twin of {@code EngineCombatBinding.isPaladinDetSpec}: the immutable explosion spec's
     *  radius / maxDamage / damageType against the unstable det's values. */
    private static boolean isUnstableDetSpec(Object explosion) {
        try {
            Object spec = explosion.getClass().getMethod("getExplosionSpecIfExplosion").invoke(explosion);
            if (spec == null) {
                return false;
            }
            Class<?> sc = spec.getClass();
            Object radius = sc.getMethod("getRadius").invoke(spec);
            Object maxDamage = sc.getMethod("getMaxDamage").invoke(spec);
            Object type = sc.getMethod("getDamageType").invoke(spec);
            return radius instanceof Number
                    && Math.abs(((Number) radius).floatValue() - UNSTABLE_DET_RADIUS) <= 0.01f
                    && maxDamage instanceof Number
                    && Math.abs(((Number) maxDamage).floatValue() - UNSTABLE_DET_MAX_DAMAGE) <= 0.01f
                    && type instanceof Enum<?>
                    && UNSTABLE_DET_DAMAGE_TYPE.equals(((Enum<?>) type).name());
        } catch (Throwable t) {
            return false;
        }
    }

    private static float[] xyOrNaN(Object vector) {
        try {
            return new float[] {
                    vector.getClass().getField("x").getFloat(vector),
                    vector.getClass().getField("y").getFloat(vector)};
        } catch (Throwable t) {
            return new float[] {Float.NaN, Float.NaN};
        }
    }

    private static void printUnstableCensus(long simTick) {
        SINK.row("unstable-census tick=" + simTick + " fired=" + unstableFired
                + " live=" + unstableLive.size()
                + " ends={destroyed=" + unstableEndDestroyed + " fizzling=" + unstableEndFizzling
                + " intact=" + unstableEndIntact + " didDamage=" + unstableEndDidDamage + "}"
                + " dets={born=" + unstableDetsBorn + " live=" + unstableDetsLive.size()
                + " hit=" + unstableDetsHit + " miss=" + unstableDetsMiss
                + " damagedEntities=" + unstableDetsDamagedEntities + "}"
                + " otherExplosionsBorn=" + otherExplosionsBorn
                + " rowsUsed=" + unstableRows + "/" + UNSTABLE_ROW_MAX);
    }

    private static void printFires(long simTick) {
        SINK.row("fragswarm-fires tick=" + simTick + " newMissilesTotal=" + newMissilesTotal
                + " fragmentFiresTotal=" + fragmentFiresTotal + " byWeapon=" + FIRES_BY_WEAPON
                + " bySource=" + FIRES_BY_SOURCE);
    }
}
