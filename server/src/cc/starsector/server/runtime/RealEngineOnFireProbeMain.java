package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A game-free answer to the fire-push seam's
 * two unknowns in one run — (1) does {@code ScriptStore} resolve a PROJECT class name on the
 * serve JVM ({@code Class.forName(name, true, janinoLoader)}
 * — a Janino {@code JavaSourceClassLoader} whose parent delegation should reach the app
 * classpath), and (2) does the weapon fire path dispatch {@code onFire} headless — the dispatch
 * sits in the same method as the projectile spawn
 * and only the muzzle flash is viewport-gated, but "runs headless" was [inferred]
 * until this run.
 *
 * <p>Recipe: gated zero-hull boot (af-flyin sibling), then BEFORE any ship spawn install
 * {@code cc.starsector.server.probes.OnFireProbeEffect} by NAME via the public
 * {@code ProjectileSpecAPI.setOnFireClassName} onto every projectile spec whose onFire slot is
 * null (wrap-don't-replace: specs carrying a vanilla class are LEFT ALONE and counted; the weapon
 * latches the plugin at construction, so pre-spawn install covers every
 * weapon built after). ScriptStore resolution is pre-tested via {@code getOnFireEffect()} (the
 * exact call the weapon ctor makes) before a single shot. Then the af-flyin geometry: seat-1
 * Onslaught fly-in + evict + seat-2 foe fly-in + standoff park, srv autofire armed through the
 * engine's own group toggles, foe groups held OFF, and the loop steps until the plugin's static
 * counter moves. Install is guarded: prior names recorded, restored in {@code finally} (one-shot
 * JVM, belt anyway).
 *
 * <p>Verdicts (exit 0 = completed with a finding, read the end line): ONFIRE_DISPATCHED_HEADLESS
 * (fires &gt; 0, all sampled sources == the firing ship), ONFIRE_DISPATCHED_SRC_MISMATCH (fires
 * but identity asserts failed — still a dispatch proof), ONFIRE_SILENT_DESPITE_PROJECTILES (the
 * falsifier: projectiles observed, zero callbacks ⇒ some gate upstream of the onFire dispatch headless).
 * Exit 4 = SCRIPTSTORE_UNRESOLVED (resolution threw — fallback lane is instance-install); 3 =
 * INCONCLUSIVE_NO_DISCHARGE (rig never fired; fix geometry, don't read); 2 = boundary thrown;
 * 1 = infra.
 */
public final class RealEngineOnFireProbeMain {

    // ---- PINNED 0.98a-RC8 identities (af-flyin sibling set + the API surface). ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";
    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String WEAPON_SPEC_API = "com.fs.starfarer.api.loading.WeaponSpecAPI";
    private static final String PROJ_SPEC_API = "com.fs.starfarer.api.loading.ProjectileSpecAPI";
    private static final String WEAPON_API = "com.fs.starfarer.api.combat.WeaponAPI";
    /** The probe plugin, referenced by NAME only — the string IS the ScriptStore experiment. */
    private static final String PROBE_PLUGIN = "cc.starsector.server.probes.OnFireProbeEffect";

    private static final String P = "onfire-probe";

    private RealEngineOnFireProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        int capFrames = 1800;
        int targetFires = 5;
        float standoff = 700f;
        float fixedDt = SimTickRate.fixedDt();
        int sampleEvery = 60;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--cap-frames": capFrames = Integer.parseInt(args[++i]); break;
                case "--target-fires": targetFires = Integer.parseInt(args[++i]); break;
                case "--standoff": standoff = Float.parseFloat(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--sample-every": sampleEvery = Integer.parseInt(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin cap_frames=" + capFrames + " target_fires=" + targetFires
                + " standoff=" + standoff + " fixed_dt=" + fixedDt
                + " plugin=" + PROBE_PLUGIN
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        // ---- Gated zero-hull boot (content init runs here; specs live, no weapons built yet). ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEngineOnFireProbeMain.class.getClassLoader();
        Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
        Class<?> weaponSpecApi = Class.forName(WEAPON_SPEC_API, false, loader);
        Class<?> projSpecApi = Class.forName(PROJ_SPEC_API, false, loader);
        Method getAllWeaponSpecs = settingsApi.getMethod("getAllWeaponSpecs");
        Method getWeaponId = weaponSpecApi.getMethod("getWeaponId");
        Method getProjectileSpec = weaponSpecApi.getMethod("getProjectileSpec");
        Method getOnFireClassName = projSpecApi.getMethod("getOnFireClassName");
        Method setOnFireClassName = projSpecApi.getMethod("setOnFireClassName", String.class);
        Method getOnFireEffect = projSpecApi.getMethod("getOnFireEffect");

        Object settings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings")
                .invoke(null);
        if (settings == null) {
            System.out.println(P + "-end verdict=FAILED reason=global_settings_null_post_boot");
            System.exit(1);
            return;
        }

        // ---- Install phase, PRE-SPAWN (weapon ctor latches the plugin at build).
        // Wrap-don't-replace: specs already carrying a vanilla onFire class are skipped + counted.
        // Guarded: every installed spec is recorded for the finally-restore. ----
        List<?> weaponSpecs = (List<?>) getAllWeaponSpecs.invoke(settings);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        List<Object> installedSpecs = new ArrayList<Object>();
        int skippedPreexisting = 0;
        int noProjSpec = 0;
        Object hellboreProjSpec = null;
        for (Object ws : weaponSpecs) {
            Object ps = getProjectileSpec.invoke(ws);
            if (ps == null || !projSpecApi.isInstance(ps) || seen.put(ps, Boolean.TRUE) != null) {
                if (ps == null || !projSpecApi.isInstance(ps)) {
                    noProjSpec++;
                }
                continue;
            }
            String prior = (String) getOnFireClassName.invoke(ps);
            if (prior != null) {
                skippedPreexisting++;
                continue;
            }
            setOnFireClassName.invoke(ps, PROBE_PLUGIN);
            installedSpecs.add(ps);
            if ("hellbore".equals(getWeaponId.invoke(ws))) {
                hellboreProjSpec = ps;
            }
        }
        System.out.println(P + " install specs=" + weaponSpecs.size()
                + " installed=" + installedSpecs.size()
                + " skipped_preexisting=" + skippedPreexisting
                + " no_proj_spec=" + noProjSpec
                + " hellbore_installed=" + (hellboreProjSpec != null));
        if (installedSpecs.isEmpty()) {
            System.out.println(P + "-end verdict=FAILED reason=nothing_installed");
            System.exit(1);
            return;
        }

        int exitCode;
        try {
            exitCode = run(binding, step, loader, fixedDt, capFrames, targetFires, standoff,
                    sampleEvery, getOnFireEffect,
                    hellboreProjSpec != null ? hellboreProjSpec : installedSpecs.get(0));
        } finally {
            // Restore every mutated spec (prior value was null for all installed). One-shot JVM,
            // but the guarded-probe rule is seed/restore-and-finally regardless.
            int restored = 0;
            for (Object ps : installedSpecs) {
                try {
                    setOnFireClassName.invoke(ps, (Object) null);
                    restored++;
                } catch (Throwable t) {
                    System.out.println(P + " restore-error " + t);
                }
            }
            System.out.println(P + " restore restored=" + restored + "/" + installedSpecs.size());
        }
        System.exit(exitCode);
    }

    private static int run(EngineCombatBinding binding, EngineBackedCombatStepBoundary step,
            ClassLoader loader, float fixedDt, int capFrames, int targetFires, float standoff,
            int sampleEvery, Method getOnFireEffect, Object testSpec) throws Exception {

        // ---- ScriptStore pre-test: the EXACT resolution the weapon ctor performs.
        // A throw here is the falsifier branch on unknown (1) — report it, don't fire a shot. ----
        Class<?> probeClass;
        Object resolved;
        try {
            resolved = getOnFireEffect.invoke(testSpec);
            probeClass = Class.forName(PROBE_PLUGIN, true, loader);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + " scriptstore resolved=false boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.out.println(P + "-end verdict=SCRIPTSTORE_UNRESOLVED fires=0"
                    + " next=instance-install-fallback");
            return 4;
        }
        boolean sameClass = resolved != null && resolved.getClass() == probeClass;
        System.out.println(P + " scriptstore resolved=" + (resolved != null)
                + " same_class=" + sameClass
                + " loader=" + (resolved == null ? "n/a"
                        : String.valueOf(resolved.getClass().getClassLoader())));
        if (resolved == null || !sameClass) {
            // Resolved but to a DIFFERENT Class object: statics would not be shared — the stdout
            // cb lines would still prove dispatch, but the counter asserts go blind. Finding.
            System.out.println(P + "-end verdict=SCRIPTSTORE_FOREIGN_CLASS fires=unknown"
                    + " note=cb-lines-in-log-are-the-dispatch-signal");
            return 4;
        }
        AtomicInteger fires = (AtomicInteger) probeClass.getField("FIRES").get(null);
        AtomicInteger cbErrors = (AtomicInteger) probeClass.getField("CALLBACK_ERRORS").get(null);
        AtomicInteger instances = (AtomicInteger) probeClass.getField("INSTANCES").get(null);
        AtomicInteger nullProj = (AtomicInteger) probeClass.getField("NULL_PROJECTILES").get(null);
        @SuppressWarnings("unchecked")
        List<Object[]> samples = (List<Object[]>) probeClass.getField("SAMPLES").get(null);

        Rig rig = resolveRig(loader);
        long[] seatWireIds = RealEngineDemoRoster.descriptorSeatShipIds();
        if (seatWireIds.length != 2) {
            System.out.println(P + "-end verdict=FAILED reason=descriptor_seats_"
                    + seatWireIds.length);
            return 1;
        }

        // ---- af-flyin geometry: srv fly-in (autofire held OFF for setup), evict off the shared
        // corridor, foe fly-in, perpendicular standoff park, THEN arm. ----
        Object srv = flyIn(1, step, fixedDt, 0L);
        long tick = 1;
        ensureAutofire(rig, srv, false);
        System.out.println(P + " srv_ready tick=" + tick + " id=" + rig.shipGetId.invoke(srv)
                + " instances_so_far=" + instances.get());
        for (int f = 0; f < 300; f++) {
            idleSeats(binding);
            step.step(fixedDt, tick++);
        }
        Object srvLoc = rig.shipGetLocation.invoke(srv);
        float srvX0 = rig.vecX.getFloat(srvLoc);
        float evictDir = srvX0 >= 0f ? 1f : -1f;
        rig.vecX.setFloat(srvLoc, srvX0 - evictDir * 2500f);
        Object srvVel = rig.shipGetVelocity.invoke(srv);
        rig.vecX.setFloat(srvVel, 0f);
        rig.vecY.setFloat(srvVel, 0f);

        Object foe = flyIn(2, step, fixedDt, tick);
        tick++;
        ensureAutofire(rig, foe, false);
        for (int f = 0; f < 300; f++) {
            idleSeats(binding);
            step.step(fixedDt, tick++);
        }

        float[] foeAt = vecF(rig.shipGetLocation.invoke(foe), rig.vecX, rig.vecY);
        float parkDir = foeAt[0] >= 0f ? 1f : -1f;
        float srvX = foeAt[0] - parkDir * standoff;
        rig.vecX.setFloat(srvLoc, srvX);
        rig.vecY.setFloat(srvLoc, foeAt[1]);
        rig.vecX.setFloat(srvVel, 0f);
        rig.vecY.setFloat(srvVel, 0f);
        if (rig.shipSetFacing != null) {
            rig.shipSetFacing.invoke(srv, foeAt[0] > srvX ? 0f : 180f);
        }
        int armed = ensureAutofire(rig, srv, true);
        System.out.println(P + " parked tick=" + tick + " srv=(" + f1(srvX) + "," + f1(foeAt[1])
                + ") foe=(" + f1(foeAt[0]) + "," + f1(foeAt[1]) + ") autofire_groups_armed="
                + armed + " weapon_instances=" + instances.get());
        if (armed == 0) {
            System.out.println(P + "-end verdict=FAILED reason=no_autofire_groups_on_srv");
            return 1;
        }

        // ---- Step until the muzzle confesses (or the cap says the rig never fired). ----
        int maxProj = 0;
        int firesAtCap = 0;
        int prevFires = 0;
        int framesRun = 0;
        for (int f = 0; f < capFrames; f++) {
            idleSeats(binding);
            maybeVent(rig, srv);
            step.step(fixedDt, tick++);
            framesRun++;
            int now = fires.get();
            int proj = ((List<?>) rig.engineGetProjectiles.invoke(rig.engine)).size();
            maxProj = Math.max(maxProj, proj);
            if (f % sampleEvery == 0 || now != prevFires || f == capFrames - 1) {
                System.out.println(P + " s tick=" + tick + " fires=" + now + " proj=" + proj
                        + " instances=" + instances.get() + " cb_errors=" + cbErrors.get());
            }
            prevFires = now;
            if (now >= targetFires) {
                break;
            }
        }
        firesAtCap = fires.get();

        // ---- Identity asserts over the recorded samples (source == the firing srv hull). ----
        int sampled = 0;
        int srcIsSrv = 0;
        int weaponShipIsSrv = 0;
        int engineNonNull = 0;
        StringBuilder weaponIds = new StringBuilder();
        Method weaponApiGetId = Class.forName(WEAPON_API, false, loader).getMethod("getId");
        java.util.LinkedHashSet<String> distinctWeapons = new java.util.LinkedHashSet<String>();
        synchronized (samples) {
            for (Object[] s : samples) {
                sampled++;
                if (s[3] == srv) srcIsSrv++;
                if (s[2] == srv) weaponShipIsSrv++;
                if (s[4] != null) engineNonNull++;
                if (s[1] != null) {
                    distinctWeapons.add(String.valueOf(weaponApiGetId.invoke(s[1])));
                }
            }
        }
        for (String w : distinctWeapons) {
            if (weaponIds.length() > 0) weaponIds.append(',');
            weaponIds.append(w);
        }

        String verdict;
        int exit;
        if (firesAtCap > 0 && sampled > 0 && srcIsSrv == sampled && weaponShipIsSrv == sampled
                && cbErrors.get() == 0 && nullProj.get() == 0) {
            verdict = "ONFIRE_DISPATCHED_HEADLESS";
            exit = 0;
        } else if (firesAtCap > 0) {
            verdict = "ONFIRE_DISPATCHED_SRC_MISMATCH";
            exit = 0;
        } else if (maxProj > 0) {
            verdict = "ONFIRE_SILENT_DESPITE_PROJECTILES";
            exit = 0;
        } else {
            verdict = "INCONCLUSIVE_NO_DISCHARGE";
            exit = 3;
        }
        System.out.println(P + "-end verdict=" + verdict
                + " scriptstore_resolved=true same_class=true"
                + " fires=" + firesAtCap
                + " sampled=" + sampled
                + " src_is_srv=" + srcIsSrv
                + " weapon_ship_is_srv=" + weaponShipIsSrv
                + " engine_nonnull=" + engineNonNull
                + " null_projectiles=" + nullProj.get()
                + " cb_errors=" + cbErrors.get()
                + " weapon_instances=" + instances.get()
                + " distinct_weapons=" + (weaponIds.length() == 0 ? "-" : weaponIds)
                + " max_proj=" + maxProj
                + " frames_run=" + framesRun
                + " end_tick=" + tick);
        return exit;
    }

    // ---- rig (af-flyin idioms, slimmed to what this probe reads) ----

    private static final class Rig {
        Object engine;
        Method engineGetProjectiles;
        Method shipGetLocation;
        Method shipGetVelocity;
        Method shipGetId;
        Method shipGetWeaponGroupsCopy;
        Method shipGetFluxTracker;
        Method fluxGetLevel;
        Method fluxIsVenting;
        Method fluxVent;
        Method shipSetFacing;
        Field vecX;
        Field vecY;
        Method groupIsAutofiring;
        Method groupGetAiPlugins;
        Method groupToggleOn;
        Method groupToggleOff;
    }

    private static Rig resolveRig(ClassLoader loader) throws Exception {
        Rig r = new Rig();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        r.engine = engineClass.getMethod("getInstance").invoke(null);
        if (r.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        r.engineGetProjectiles = engineClass.getMethod("getProjectiles");
        Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
        r.shipGetLocation = shipClass.getMethod("getLocation");
        r.shipGetVelocity = shipClass.getMethod("getVelocity");
        r.shipGetId = shipClass.getMethod("getId");
        r.shipGetWeaponGroupsCopy = shipClass.getMethod("getWeaponGroupsCopy");
        r.shipGetFluxTracker = optional(shipClass, "getFluxTracker");
        r.shipSetFacing = optional(shipClass, "setFacing", float.class);
        Class<?> vec2 = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        r.vecX = vec2.getField("x");
        r.vecY = vec2.getField("y");
        return r;
    }

    private static void idleSeats(EngineCombatBinding binding) {
        binding.idleOwnedShipAi(1L);
        binding.idleOwnedShipAi(2L);
    }

    private static int ensureAutofire(Rig r, Object ship, boolean on) throws Exception {
        List<?> groups = (List<?>) r.shipGetWeaponGroupsCopy.invoke(ship);
        int autofiring = 0;
        for (Object g : groups) {
            if (r.groupIsAutofiring == null) {
                r.groupIsAutofiring = g.getClass().getMethod("isAutofiring");
                r.groupGetAiPlugins = g.getClass().getMethod("getAIPlugins");
                r.groupToggleOn = g.getClass().getMethod("toggleOn");
                r.groupToggleOff = g.getClass().getMethod("toggleOff");
            }
            boolean is = (Boolean) r.groupIsAutofiring.invoke(g);
            boolean hasPlugins = !((List<?>) r.groupGetAiPlugins.invoke(g)).isEmpty();
            if (on && !is && hasPlugins) {
                r.groupToggleOn.invoke(g);
            } else if (!on && is) {
                r.groupToggleOff.invoke(g);
            }
            if ((Boolean) r.groupIsAutofiring.invoke(g)) {
                autofiring++;
            }
        }
        return autofiring;
    }

    /** Best-effort flux relief through the engine's own vent (af-flyin idiom). */
    private static void maybeVent(Rig r, Object srv) {
        if (r.shipGetFluxTracker == null) {
            return;
        }
        try {
            Object tracker = r.shipGetFluxTracker.invoke(srv);
            if (tracker == null) {
                return;
            }
            if (r.fluxGetLevel == null) {
                r.fluxGetLevel = tracker.getClass().getMethod("getFluxLevel");
                r.fluxIsVenting = tracker.getClass().getMethod("isVenting");
                r.fluxVent = tracker.getClass().getMethod("ventFlux");
            }
            float level = ((Number) r.fluxGetLevel.invoke(tracker)).floatValue();
            if (level > 0.7f && !((Boolean) r.fluxIsVenting.invoke(tracker))) {
                r.fluxVent.invoke(tracker);
            }
        } catch (ReflectiveOperationException e) {
            r.shipGetFluxTracker = null;
            System.out.println(P + " vent_rig_disabled reason=" + e);
        }
    }

    private static Object flyIn(int seatId, EngineBackedCombatStepBoundary step, float fixedDt,
            long tick) {
        try {
            Object ship = RealEngineDemoRoster.midDeploySeat(seatId);
            step.step(fixedDt, tick);
            return ship;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=FLY_IN_THREW seat=" + seatId + " tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            throw new IllegalStateException("unreachable");
        }
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

    private static float[] vecF(Object v, Field x, Field y) throws Exception {
        return new float[]{x.getFloat(v), y.getFloat(v)};
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }
}
