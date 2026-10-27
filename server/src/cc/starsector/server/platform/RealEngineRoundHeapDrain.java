package cc.starsector.server.platform;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Round-boundary heap drain for the long-lived headless {@code CombatEngine} — the product port of
 * the scout's proven soak-leak correctives
 * (the scout's {@code runFleetSoak}, knobs {@code cc.soak.*}).
 *
 * <p><b>Why the live server needs this.</b> The scout's 20-round fleet soak proved that a
 * {@code CombatEngine} kept alive across many rounds retains every prior round's whole fleet
 * (~90&nbsp;KB/ship) through four render-side-only drain paths that never run headless. The product
 * dedicated server is the <em>same</em> long-lived engine driven across reset rounds
 * ({@code RealEngineCombatBindingFactory.resetForRedeploy}), so before this port it carried the
 * identical unbounded-heap retention — the entire persistent-server thesis exposed. Each corrective
 * re-runs the engine's <em>own</em> between-battle cleanup at the per-round boundary (faithful, not a
 * reimplementation): the four mechanisms, in apply order, are
 *
 * <ol>
 *   <li><b>Entity sweep</b> (scout corrective 2). The engine's repository swap that
 *       a reset performs bypasses the engine's private entity-remove hook
 *       ({@code renderer.remove} + plugin cleanup), so every engine-spawned entity
 *       alive at swap time (projectiles, particles) stays pinned in the {@code LayeredRenderer} —
 *       and a {@code BallisticProjectile.source} back-reference then pins the round's whole fleet.
 *       Fix = route every object still in the OUTGOING repo through the PUBLIC engine removal flow
 *       ({@code removeObject(Object)} then {@code executeRemoves()}), exactly what the engine does
 *       for a naturally-removed entity. <b>Must run before the roster rollback swaps the repo away.</b></li>
 *   <li><b>Fog re-mint</b> (scout corrective 1). Headless, the per-side fog-of-war
 *       trackers insert a {@code Ship}-keyed entry every frame ({@code updateVisibility}) whose only
 *       removal path is render-side (player-POV fog fade), so every ship ever simulated stays a
 *       strong map key. Fix = re-mint both per-side trackers over the live combat map — the engine's
 *       own between-battle pattern (the two {@code setCombatMap} statements), NOT full {@code setCombatMap}
 *       (unaudited side effects).</li>
 *   <li><b>Anim clear</b> (scout corrective 3). The engine's entity-remove hook
 *       queues each removed entity's animations into the STATIC {@code AnimationManager.removing}
 *       lists of the named managers {@code "CEanim"}/{@code "CENonPauseanim"}, whose only drain
 *       ({@code advanceAll}) is render-driven — never headless. So the entity sweep above MOVES the
 *       retention from the renderer into {@code removing}. Fix = the engine's own
 *       {@code CombatEngine.destroyInstance} statements: {@code clear()}
 *       both named managers. <b>Must run after the entity sweep</b> (the sweep is what queues the
 *       round's animations).</li>
 *   <li><b>Particle clear</b> (scout corrective 4). {@code GenericTextureParticle}
 *       FX accumulate in the engine-held {@code DynamicParticleGroup}s (the uncapped groups inside
 *       the explosion/debris FX systems plus the engine's own group fields) because group aging
 *       ({@code DynamicParticleGroup.advance}) is {@code AnimationManager}-driven = render-only, and
 *       the anim clear above drops those managers anyway. Fix = empty each engine-held group via its
 *       PUBLIC {@code getParticles()} list — the long-lived-engine equivalent of
 *       {@code destroyInstance} dropping the holders.</li>
 *   <li><b>Plugin sweep</b> (corrective 5 — added for the omega F2 defect,
 *       not part of the scout port). Script-added {@code EveryFrameCombatPlugin}s live in the
 *       combat map's advanceable list, which the rollback never touches, so a plugin a round
 *       registered (omega {@code ShardSpawner} fades) advances into the next round on dead-round
 *       state. Fix = remove every plugin not in the round-open baseline via the engine's PUBLIC
 *       {@code removePlugin}; baseline law at {@link #pluginBaseline}.</li>
 * </ol>
 *
 * <p><b>Single master switch, no per-corrective toggles.</b> The scout shipped one {@code cc.soak.*}
 * toggle per corrective (the original verdict). That per-corrective burden was RELAXED for the
 * product (a decision update of 2026-06-13): correctives ship <b>default-ON</b>,
 * and the single master switch {@code cc.predict.correctivesOff} (read by the caller) flips ALL of
 * them off for desync-source debugging. The {@code cc.soak.*} namespace is deliberately NOT carried.
 *
 * <p><b>Fail-soft throughout (an optimization, never a reset blocker).</b> Both resolution
 * ({@link #resolve(Object)}) and application ({@link #drainRoundHeap()}) are best-effort and never
 * throw: a corrective whose handles cannot be resolved, or that errors mid-apply, is disabled (with
 * a recorded outcome) and the others still run. The drain re-running the engine's own cleanup must
 * not be able to fail a round — the roster reset keeps its own fail-closed semantics independently.
 *
 * <p><b>Option R — literal identities, pinned.</b> Like the sibling platform
 * classes, every engine-internal {@code com.fs.*} identity is rendered literally in the
 * {@code PINNED 0.98a-RC8} block, byte-identical to the proven scout lane; descriptor-grade
 * resolution is the documented next-RC upgrade (standing portability-drill trigger).
 *
 *
 *
 */
public final class RealEngineRoundHeapDrain {

    // ---- PINNED 0.98a-RC8 (descriptor-grade identity owed at the next RC). Option R. ----

    /** Engine-spawned entity repository; {@code toArray()} = the live object list to sweep. */
    private static final String OBJECT_REPOSITORY_CLASS = "com.fs.util.container.repo.ObjectRepository";
    /**
     * Wrapper the engine's {@code addPlugin} wraps every {@code EveryFrameCombatPlugin} in before
     * adding it to the combat map's advanceable list (its
     * binary name confirmed by the omega F2 kill stack). The unwrap
     * accessor's engine-internal name is non-ASCII, so it is resolved by SHAPE: the wrapper's one
     * declared zero-arg method returning {@code EveryFrameCombatPlugin}.
     */
    private static final String PLUGIN_WRAPPER_CLASS = "com.fs.starfarer.title.Object.L$Oo";
    /** The api plugin interface — the unwrap method's return type and {@code removePlugin}'s param. */
    private static final String EVERY_FRAME_PLUGIN_INTERFACE = "com.fs.starfarer.api.combat.EveryFrameCombatPlugin";
    /** Combat map's advanceable-list getter, a no-arg {@code List} method named {@code String}. */
    private static final String COMBAT_MAP_ADVANCEABLES_METHOD = "String";
    /** Static-managed animation registry ({@code DoNotObfuscate}); {@code getInstance(name)} + {@code clear()}. */
    private static final String ANIMATION_MANAGER_CLASS = "com.fs.graphics.anim.AnimationManager";
    /** Particle group ({@code DoNotObfuscate}); {@code getParticles()} returns the live list to empty. */
    private static final String DYNAMIC_PARTICLE_GROUP_CLASS = "com.fs.graphics.particle.DynamicParticleGroup";

    /** The two static named animation managers the engine's entity-remove hook queues into. */
    private static final String ANIM_MANAGER_CE = "CEanim";
    private static final String ANIM_MANAGER_CE_NON_PAUSE = "CENonPauseanim";

    /** The live engine singleton (stable across reset rounds). */
    private final Object engine;

    // ---- Entity sweep handles (corrective 2). ----
    private Method getObjects;
    private Method repoToArray;
    private Method removeObject;
    private Method executeRemoves;
    private boolean entitySweepEnabled;

    // ---- Fog re-mint handles (corrective 1). ----
    private Method getCombatMap;
    private Constructor<?> fogTrackerCtor;
    private Field fogMapField;
    private boolean fogRemintEnabled;

    // ---- Anim clear handles (corrective 3). ----
    private Method animGetInstance;
    private Method animClearMethod;
    private boolean animClearEnabled;

    // ---- Particle clear handles (corrective 4). ----
    private Method dpgGetParticles;
    private final List<Object> particleGroups = new ArrayList<>();
    private final List<String> particleGroupNames = new ArrayList<>();
    private boolean particleClearEnabled;

    // ---- Plugin sweep handles (corrective 5). ----
    private Method pluginGetCombatMap;
    private Method mapAdvanceables;
    private Class<?> pluginWrapperClass;
    private Method pluginUnwrap;
    private Method removePlugin;
    private boolean pluginSweepEnabled;

    /**
     * Round-open plugin BASELINE (corrective 5): the identity set of every
     * {@code EveryFrameCombatPlugin} registered on the engine when {@code create()} finished —
     * the boot's own plugins ({@code setCombatMap} re-adds the ScriptStore set) that every round
     * legitimately runs. Captured STATICALLY at
     * the factory's create tail (NOT at first-drain resolve — the first drain runs at warm-up pass
     * 0's reset, AFTER a warm-up round may already have leaked script-added plugins, which would
     * then be baselined and never swept; that exact ordering was the omega F2 kill).
     * Identity set: plugin instances have no meaningful equals.
     */
    private static final java.util.Set<Object> pluginBaseline =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static volatile boolean pluginBaselineCaptured;

    private RealEngineRoundHeapDrain(Object engine) {
        this.engine = engine;
    }

    /**
     * Resolve every corrective's handles against the live engine, each in its own try (fail-soft: a
     * resolution miss disables only that corrective). Idempotent to call once per process — the
     * caller caches the result. Never throws; an entirely-unresolved drain simply applies nothing.
     *
     * @param engine the live {@code CombatEngine} singleton (must be init'd; one round must have
     *               minted the fog trackers before the fog corrective can resolve).
     */
    public static RealEngineRoundHeapDrain resolve(Object engine) {
        RealEngineRoundHeapDrain drain = new RealEngineRoundHeapDrain(engine);
        ClassLoader loader = RealEngineRoundHeapDrain.class.getClassLoader();
        Class<?> engineClass = engine.getClass();
        drain.resolveEntitySweep(engineClass, loader);
        drain.resolveFogRemint(engineClass);
        drain.resolveAnimClear(loader);
        drain.resolveParticleClear(engineClass, loader);
        drain.resolvePluginSweep(engineClass, loader);
        System.out.println("real-engine-heap-drain resolve"
                + " entity_sweep=" + drain.entitySweepEnabled
                + " fog_remint=" + drain.fogRemintEnabled
                + " anim_clear=" + drain.animClearEnabled
                + " particle_clear=" + drain.particleClearEnabled
                + " particle_groups=" + drain.particleGroups.size()
                + " plugin_sweep=" + drain.pluginSweepEnabled
                + " plugin_baseline=" + (pluginBaselineCaptured ? pluginBaseline.size() : -1));
        return drain;
    }

    /**
     * Snapshot the engine's live plugin set as the round-open BASELINE for corrective 5. The ONE
     * production caller is the factory's create tail ({@code createInternal}, after roster deploy):
     * the complete round-1-open plugin set, before any round has run script code that could
     * {@code addPlugin}. Fail-soft and idempotent-by-guard: a second call (or any resolve/walk
     * error) leaves the first capture standing and only logs. Without a capture the sweep stays
     * disabled — sweeping against an unbaselined engine would remove the boot's own plugins.
     */
    public static void capturePluginBaseline(Object engine) {
        if (pluginBaselineCaptured) {
            return;
        }
        try {
            ClassLoader loader = engine.getClass().getClassLoader();
            Method getCombatMap = engine.getClass().getMethod("getCombatMap");
            Object combatMap = getCombatMap.invoke(engine);
            Method advanceables = combatMap.getClass()
                    .getMethod(COMBAT_MAP_ADVANCEABLES_METHOD);
            Class<?> wrapperClass = Class.forName(PLUGIN_WRAPPER_CLASS, false, loader);
            Method unwrap = resolvePluginUnwrap(wrapperClass, loader);
            for (Object entry : (List<?>) advanceables.invoke(combatMap)) {
                if (wrapperClass.isInstance(entry)) {
                    Object plugin = unwrap.invoke(entry);
                    if (plugin != null) {
                        pluginBaseline.add(plugin);
                    }
                }
            }
            pluginBaselineCaptured = true;
            System.out.println("real-engine-heap-drain plugin-baseline captured="
                    + pluginBaseline.size());
        } catch (Throwable t) {
            System.out.println("real-engine-heap-drain plugin-baseline capture SKIPPED "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * SHAPE-resolve the wrapper's unwrap accessor (its engine-internal name is non-ASCII): the one
     * declared zero-arg method returning {@code EveryFrameCombatPlugin}. Throws unless exactly one
     * matches — ambiguity means the pinned identity drifted (next-RC portability drill).
     */
    private static Method resolvePluginUnwrap(Class<?> wrapperClass, ClassLoader loader)
            throws Exception {
        Class<?> pluginInterface = Class.forName(EVERY_FRAME_PLUGIN_INTERFACE, false, loader);
        Method match = null;
        for (Method m : wrapperClass.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == pluginInterface) {
                if (match != null) {
                    throw new IllegalStateException("plugin wrapper has >1 zero-arg "
                            + pluginInterface.getSimpleName() + " accessor");
                }
                match = m;
            }
        }
        if (match == null) {
            throw new IllegalStateException("plugin wrapper has no zero-arg "
                    + pluginInterface.getSimpleName() + " accessor");
        }
        match.setAccessible(true);
        return match;
    }

    private void resolveEntitySweep(Class<?> engineClass, ClassLoader loader) {
        try {
            Class<?> objectRepositoryClass = Class.forName(OBJECT_REPOSITORY_CLASS, true, loader);
            getObjects = engineClass.getMethod("getObjects");
            repoToArray = objectRepositoryClass.getMethod("toArray");
            removeObject = engineClass.getMethod("removeObject", Object.class);
            executeRemoves = engineClass.getMethod("executeRemoves");
            entitySweepEnabled = true;
        } catch (Throwable t) {
            entitySweepEnabled = false;
        }
    }

    /**
     * The tracker-map field's engine-internal name contains a literal {@code .} ({@code "while.new"}), so it
     * is found by IDENTITY: the declared {@code Map} field whose value maps
     * {@code 0 ->} the same instance {@code getFogOfWar(0)} returns. Tracker class + its
     * {@code (combatMap, side)} ctor come from that instance.
     */
    private void resolveFogRemint(Class<?> engineClass) {
        try {
            getCombatMap = engineClass.getMethod("getCombatMap");
            Object fogSide0 = engineClass.getMethod("getFogOfWar", int.class)
                    .invoke(engine, Integer.valueOf(0));
            if (fogSide0 == null) {
                throw new IllegalStateException("getFogOfWar(0) returned null — trackers not minted");
            }
            fogTrackerCtor = fogSide0.getClass()
                    .getDeclaredConstructor(getCombatMap.getReturnType(), int.class);
            fogTrackerCtor.setAccessible(true);
            for (Field f : engineClass.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object val = f.get(engine);
                if (val instanceof Map && ((Map<?, ?>) val).get(Integer.valueOf(0)) == fogSide0) {
                    fogMapField = f;
                    break;
                }
            }
            if (fogMapField == null) {
                throw new IllegalStateException("no engine Map field maps 0 -> getFogOfWar(0)");
            }
            fogRemintEnabled = true;
        } catch (Throwable t) {
            fogRemintEnabled = false;
        }
    }

    private void resolveAnimClear(ClassLoader loader) {
        try {
            Class<?> animMgrClass = Class.forName(ANIMATION_MANAGER_CLASS, false, loader);
            animGetInstance = animMgrClass.getMethod("getInstance", String.class);
            animClearMethod = animMgrClass.getMethod("clear");
            animClearEnabled = true;
        } catch (Throwable t) {
            animClearEnabled = false;
        }
    }

    /**
     * TYPE-driven resolution: every engine declared field that IS a
     * {@code DynamicParticleGroup}, plus one level into engine field TYPES that declare a
     * {@code DynamicParticleGroup}(/array) member (= the explosion/debris FX systems).
     */
    private void resolveParticleClear(Class<?> engineClass, ClassLoader loader) {
        try {
            Class<?> dpgClass = Class.forName(DYNAMIC_PARTICLE_GROUP_CLASS, false, loader);
            dpgGetParticles = dpgClass.getMethod("getParticles");
            for (Field f : engineClass.getDeclaredFields()) {
                if (dpgClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object g = f.get(engine);
                    if (g != null) {
                        particleGroups.add(g);
                        particleGroupNames.add("engine." + f.getName());
                    }
                    continue;
                }
                boolean holderType = false;
                for (Field inner : f.getType().getDeclaredFields()) {
                    Class<?> it = inner.getType();
                    if (dpgClass.isAssignableFrom(it)
                            || (it.isArray() && dpgClass.isAssignableFrom(it.getComponentType()))) {
                        holderType = true;
                        break;
                    }
                }
                if (!holderType) {
                    continue;
                }
                f.setAccessible(true);
                Object sys = f.get(engine);
                if (sys == null) {
                    continue;
                }
                for (Field inner : f.getType().getDeclaredFields()) {
                    Class<?> it = inner.getType();
                    if (dpgClass.isAssignableFrom(it)) {
                        inner.setAccessible(true);
                        Object g = inner.get(sys);
                        if (g != null) {
                            particleGroups.add(g);
                            particleGroupNames.add(f.getName() + "." + inner.getName());
                        }
                    } else if (it.isArray() && dpgClass.isAssignableFrom(it.getComponentType())) {
                        inner.setAccessible(true);
                        Object arr = inner.get(sys);
                        if (arr == null) {
                            continue;
                        }
                        int arrLen = Array.getLength(arr);
                        for (int gi = 0; gi < arrLen; gi++) {
                            Object g = Array.get(arr, gi);
                            if (g != null) {
                                particleGroups.add(g);
                                particleGroupNames.add(f.getName() + "[" + gi + "]");
                            }
                        }
                    }
                }
            }
            particleClearEnabled = !particleGroups.isEmpty();
        } catch (Throwable t) {
            particleClearEnabled = false;
        }
    }

    /**
     * Corrective 5 handles: the combat map's advanceable list (where {@code addPlugin} wraps and
     * registers every script plugin), the wrapper's shape-resolved unwrap, and the engine's PUBLIC
     * {@code removePlugin} (the same flow the engine's own scripts use to retire a plugin).
     * Requires a captured baseline — {@link #capturePluginBaseline} runs at the factory's create
     * tail; a boot shape that never captured (probe/measure mains) leaves the sweep off.
     */
    private void resolvePluginSweep(Class<?> engineClass, ClassLoader loader) {
        try {
            if (!pluginBaselineCaptured) {
                throw new IllegalStateException("no plugin baseline captured");
            }
            pluginGetCombatMap = engineClass.getMethod("getCombatMap");
            Object combatMap = pluginGetCombatMap.invoke(engine);
            mapAdvanceables = combatMap.getClass().getMethod(COMBAT_MAP_ADVANCEABLES_METHOD);
            pluginWrapperClass = Class.forName(PLUGIN_WRAPPER_CLASS, false, loader);
            pluginUnwrap = resolvePluginUnwrap(pluginWrapperClass, loader);
            removePlugin = engineClass.getMethod("removePlugin",
                    Class.forName(EVERY_FRAME_PLUGIN_INTERFACE, false, loader));
            pluginSweepEnabled = true;
        } catch (Throwable t) {
            pluginSweepEnabled = false;
        }
    }

    /**
     * Apply the correctives in the proven scout order — entity sweep, then fog re-mint, then
     * anim clear, then particle clear, then the plugin sweep — on the OUTGOING round's engine state, between served rounds
     * (no {@code advanceInner} in flight). Each step is fail-soft and disables itself on error so a
     * later round does not re-throw. Returns a compact per-corrective outcome string for the caller's
     * reset log. Never throws.
     */
    public synchronized String drainRoundHeap() {
        String entitySweepOutcome = entitySweepEnabled ? sweepEntities() : "off";
        String fogRemintOutcome = fogRemintEnabled ? remintFog() : "off";
        String animClearOutcome = animClearEnabled ? clearAnimations() : "off";
        String particleClearOutcome = particleClearEnabled ? clearParticles() : "off";
        String pluginSweepOutcome = pluginSweepEnabled ? sweepPlugins() : "off";
        return "entity_sweep=" + entitySweepOutcome
                + " fog_remint=" + fogRemintOutcome
                + " anim_clear=" + animClearOutcome
                + " particle_clear=" + particleClearOutcome
                + " plugin_sweep=" + pluginSweepOutcome;
    }

    private String sweepEntities() {
        try {
            Object repoAtEnd = getObjects.invoke(engine);
            Object[] outgoing = (Object[]) repoToArray.invoke(repoAtEnd);
            for (Object oo : outgoing) {
                removeObject.invoke(engine, oo);
            }
            executeRemoves.invoke(engine);
            return "swept_" + outgoing.length;
        } catch (Throwable t) {
            entitySweepEnabled = false;
            return "error_sweep_disabled_" + t.getClass().getSimpleName();
        }
    }

    @SuppressWarnings("unchecked")
    private String remintFog() {
        try {
            Object combatMap = getCombatMap.invoke(engine);
            Map<Object, Object> fogMap = (Map<Object, Object>) fogMapField.get(engine);
            fogMap.put(Integer.valueOf(0), fogTrackerCtor.newInstance(combatMap, Integer.valueOf(0)));
            fogMap.put(Integer.valueOf(1), fogTrackerCtor.newInstance(combatMap, Integer.valueOf(1)));
            return "reminted";
        } catch (Throwable t) {
            fogRemintEnabled = false;
            return "error_remint_disabled_" + t.getClass().getSimpleName();
        }
    }

    private String clearAnimations() {
        try {
            animClearMethod.invoke(animGetInstance.invoke(null, ANIM_MANAGER_CE));
            animClearMethod.invoke(animGetInstance.invoke(null, ANIM_MANAGER_CE_NON_PAUSE));
            return "cleared";
        } catch (Throwable t) {
            animClearEnabled = false;
            return "error_clear_disabled_" + t.getClass().getSimpleName();
        }
    }

    /**
     * Corrective 5: remove every script-added plugin the outgoing round left
     * on the engine — anything in the combat map's advanceable list not in the round-open
     * {@link #pluginBaseline}. The rollback swaps the object repo but the combat map (and so the
     * plugin list) is process-lived, so a plugin a round's script code registered — e.g. omega's
     * {@code ShardSpawner} fade plugins — advances into the NEXT round holding dead-round ship
     * refs (the omega F2 kill: a leaked warm-up fade-in spawned off its stale source and NPE'd the
     * real round at t≈0). Collect-then-remove (removePlugin mutates the list being walked), each
     * through the engine's own PUBLIC {@code removePlugin} flow.
     */
    private String sweepPlugins() {
        try {
            Object combatMap = pluginGetCombatMap.invoke(engine);
            List<Object> leaked = new ArrayList<>();
            for (Object entry : (List<?>) mapAdvanceables.invoke(combatMap)) {
                if (pluginWrapperClass.isInstance(entry)) {
                    Object plugin = pluginUnwrap.invoke(entry);
                    if (plugin != null && !pluginBaseline.contains(plugin)) {
                        leaked.add(plugin);
                    }
                }
            }
            for (Object plugin : leaked) {
                removePlugin.invoke(engine, plugin);
            }
            return "swept_" + leaked.size();
        } catch (Throwable t) {
            pluginSweepEnabled = false;
            return "error_sweep_disabled_" + t.getClass().getSimpleName();
        }
    }

    private String clearParticles() {
        try {
            long purged = 0L;
            for (int gi = 0; gi < particleGroups.size(); gi++) {
                List<?> pl = (List<?>) dpgGetParticles.invoke(particleGroups.get(gi));
                purged += pl.size();
                pl.clear();
            }
            return "purged_" + purged;
        } catch (Throwable t) {
            particleClearEnabled = false;
            return "error_clear_disabled_" + t.getClass().getSimpleName();
        }
    }
}
