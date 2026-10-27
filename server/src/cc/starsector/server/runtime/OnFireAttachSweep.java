package cc.starsector.server.runtime;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * The once-per-JVM PRODUCT pre-spawn sweep that installs the
 * onFire emitter ({@code cc.starsector.server.fire.CcOnFireEmitter}) by NAME onto every
 * projectile spec via the public-API {@code ProjectileSpecAPI.setOnFireClassName} — the exact
 * probe recipe ({@code RealEngineOnFireProbeMain}), productized. Weapon ctors latch
 * the plugin at build and specs are Settings-global, so a sweep run
 * post-content-init and STRICTLY before any ship deploy covers every weapon this JVM builds,
 * respawns included. Unlike the probe, the product sweep does NOT restore (process-lifetime
 * install; probes restore, product installs).
 *
 * <p>The walk is {@code getActuallyAllWeaponSpecs} — the engine's UNFILTERED spec
 * walk (exact disjoint union of {@code getAllWeaponSpecs} +
 * {@code getSystemWeaponSpecs}, which apply complementary filters) — so mounted SYSTEM
 * projectile weapons (tpc, heavy_adjudicator, fighter-chassis guns) confess through the same
 * seam. MISSILE-specClass specs join the seam too — MissileSpecAPI
 * redeclares the onFire trio byte-identical to ProjectileSpecAPI with NO inheritance relation,
 * so the missile branch resolves its OWN Method pair and
 * applies the identical treatment ({@code missile_installed}/{@code missile_wrapped}/
 * {@code missile_wrap_skipped} counters; landed ids ALSO register
 * {@link FireBirthSink#registerMissileSpecCovered} for kind visibility). The old Finding B
 * {@code no_proj_spec} boundary now catches only spec-less weapons (beams).
 *
 * <p>Wrap-don't-replace, the engine-first shape: a spec already carrying a vanilla onFire class is
 * wrapped ONLY when the original class eagerly resolves, is plugin-assignable, and
 * test-constructs AT SWEEP TIME — then the delegation is recorded in {@link FireBirthSink}
 * and the emitter (which chain-calls a fresh original per wrapper) replaces the name. Any
 * failure leaves that spec UNTOUCHED ({@code wrap_skipped}): behavior is provably
 * unchanged-or-not-installed.
 *
 * <p>Fail-soft ×3: (a) the emitter name is pre-checked resolvable on the app loader (a
 * classpath-missing run shape never breaks weapon build); (b) wrap failures skip per-spec;
 * (c) the whole sweep is caught to a boolean — failure = shadow off, boot continues. The knob
 * ({@code cc.server.onFireShadow}, default ON) is parsed OUTSIDE the guard: a malformed value
 * THROWs readable at boot (config error, not an engine fail-soft).
 */
public final class OnFireAttachSweep {

    /** Referenced by NAME only — resolution through ScriptStore is the seam being ridden. */
    static final String EMITTER = "cc.starsector.server.fire.CcOnFireEmitter";

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String WEAPON_SPEC_API = "com.fs.starfarer.api.loading.WeaponSpecAPI";
    private static final String PROJ_SPEC_API = "com.fs.starfarer.api.loading.ProjectileSpecAPI";
    private static final String MISSILE_SPEC_API = "com.fs.starfarer.api.loading.MissileSpecAPI";
    private static final String ONFIRE_PLUGIN_API = "com.fs.starfarer.api.combat.OnFireEffectPlugin";

    private OnFireAttachSweep() {
    }

    /**
     * Run the sweep unless the knob disables it. True = installed and
     * {@link FireBirthSink#enabled()} latched; false = shadow off (knob, unresolvable emitter,
     * or a caught sweep failure — each printed).
     */
    public static boolean runIfEnabled() {
        String raw = ServerConfig.resolve(
                "cc.server.onFireShadow", "CC_SERVER_ONFIRE_SHADOW", "server.onFireShadow", "true");
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "", "true", "1", "yes", "on" -> { }
            case "false", "0", "no", "off" -> {
                System.out.println("onfire-attach skipped reason=knob_off");
                return false;
            }
            default -> throw new IllegalArgumentException(
                    "cc.server.onFireShadow must be a boolean; got '" + raw + "'");
        }
        try {
            return sweep();
        } catch (Throwable t) {
            System.out.println("onfire-attach failed error=" + t.getClass().getName()
                    + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean sweep() throws Exception {
        ClassLoader loader = OnFireAttachSweep.class.getClassLoader();
        // (a) resolvable pre-check: the emitter must exist on the app classpath (ScriptStore
        // parent-delegates project names there — observed); a run shape without the
        // probe-api output must never leave a dangling name for weapon builds to die on.
        boolean resolvable;
        try {
            Class.forName(EMITTER, false, loader);
            resolvable = true;
        } catch (ClassNotFoundException | LinkageError emitterMissing) {
            resolvable = false;
        }
        if (!resolvable) {
            System.out.println("onfire-attach installed=0 wrapped=0 wrap_skipped=0 skipped_dup=0"
                    + " no_proj_spec=0 missile_installed=0 missile_wrapped=0"
                    + " missile_wrap_skipped=0 resolvable=false covered=0");
            return false;
        }

        Class<?> weaponSpecApi = Class.forName(WEAPON_SPEC_API, false, loader);
        Class<?> projSpecApi = Class.forName(PROJ_SPEC_API, false, loader);
        Class<?> missileSpecApi = Class.forName(MISSILE_SPEC_API, false, loader);
        Class<?> pluginApi = Class.forName(ONFIRE_PLUGIN_API, false, loader);
        Method getActuallyAllWeaponSpecs = Class.forName("com.fs.starfarer.api.SettingsAPI", false, loader)
                .getMethod("getActuallyAllWeaponSpecs");
        Method getProjectileSpec = weaponSpecApi.getMethod("getProjectileSpec");
        Method getWeaponId = weaponSpecApi.getMethod("getWeaponId");
        Method getOnFireClassName = projSpecApi.getMethod("getOnFireClassName");
        Method setOnFireClassName = projSpecApi.getMethod("setOnFireClassName", String.class);
        // MissileSpecAPI redeclares the trio with NO inheritance relation to ProjectileSpecAPI
        // — a ProjectileSpecAPI Method invoked on a missile spec would throw
        // IllegalArgumentException, so the branch carries its own independently resolved pair.
        Method missileGetOnFireClassName = missileSpecApi.getMethod("getOnFireClassName");
        Method missileSetOnFireClassName = missileSpecApi.getMethod("setOnFireClassName", String.class);

        Object settings = Class.forName(GLOBAL_CLASS, true, loader)
                .getMethod("getSettings").invoke(null);
        if (settings == null) {
            throw new IllegalStateException("Global.getSettings() null — sweep must run post-boot");
        }

        List<?> weaponSpecs = (List<?>) getActuallyAllWeaponSpecs.invoke(settings);
        // Value = the coverage OUTCOME for that projectile spec (emitter installed/wrapped or
        // already ours), so a later weapon spec sharing it registers covered iff the first
        // crossing actually landed the emitter (a wrap_skipped spec's dups stay uncovered).
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        int installed = 0;
        int wrapped = 0;
        int wrapSkipped = 0;
        int skippedDup = 0;
        int noProjSpec = 0;
        int missileInstalled = 0;
        int missileWrapped = 0;
        int missileWrapSkipped = 0;
        int covered = 0;
        for (Object ws : weaponSpecs) {
            Object ps = getProjectileSpec.invoke(ws);
            boolean projectile = ps != null && projSpecApi.isInstance(ps);
            boolean missile = !projectile && ps != null && missileSpecApi.isInstance(ps);
            if (!projectile && !missile) {
                noProjSpec++;
                continue;
            }
            if (missile) { // The KIND set, landed or not (FireBirthSink#registerMissileSpec)
                FireBirthSink.registerMissileSpec((String) getWeaponId.invoke(ws));
            }
            Boolean priorOutcome = seen.get(ps);
            if (priorOutcome != null) {
                skippedDup++;
                if (priorOutcome.booleanValue()) {
                    String weaponId = (String) getWeaponId.invoke(ws);
                    FireBirthSink.registerCovered(weaponId);
                    if (missile) {
                        FireBirthSink.registerMissileSpecCovered(weaponId);
                    }
                    covered++;
                }
                continue;
            }
            Method getOnFire = missile ? missileGetOnFireClassName : getOnFireClassName;
            Method setOnFire = missile ? missileSetOnFireClassName : setOnFireClassName;
            String prior = (String) getOnFire.invoke(ps);
            boolean landed;
            if (prior == null) {
                setOnFire.invoke(ps, EMITTER);
                if (missile) {
                    missileInstalled++;
                } else {
                    installed++;
                }
                landed = true;
            } else if (EMITTER.equals(prior)) {
                skippedDup++;
                landed = true;
            } else {
                // (b) wrap-don't-replace: prove the original constructible BEFORE touching the
                // spec; the emitter chain-calls a fresh original per wrapper at dispatch.
                try {
                    Class<?> original = Class.forName(prior, true, loader);
                    if (!pluginApi.isAssignableFrom(original)) {
                        throw new IllegalStateException(prior + " is not an OnFireEffectPlugin");
                    }
                    original.getDeclaredConstructor().newInstance();
                    FireBirthSink.registerDelegation(ps, prior);
                    setOnFire.invoke(ps, EMITTER);
                    if (missile) {
                        missileWrapped++;
                    } else {
                        wrapped++;
                    }
                    landed = true;
                } catch (Throwable wrapFailure) {
                    if (missile) {
                        missileWrapSkipped++;
                    } else {
                        wrapSkipped++;
                    }
                    landed = false;
                }
            }
            seen.put(ps, landed);
            if (landed) {
                String weaponId = (String) getWeaponId.invoke(ws);
                FireBirthSink.registerCovered(weaponId);
                if (missile) {
                    FireBirthSink.registerMissileSpecCovered(weaponId);
                }
                covered++;
            }
        }
        System.out.println("onfire-attach installed=" + installed
                + " wrapped=" + wrapped
                + " wrap_skipped=" + wrapSkipped
                + " skipped_dup=" + skippedDup
                + " no_proj_spec=" + noProjSpec
                + " missile_installed=" + missileInstalled
                + " missile_wrapped=" + missileWrapped
                + " missile_wrap_skipped=" + missileWrapSkipped
                + " resolvable=true"
                + " covered=" + covered);
        boolean success = installed + wrapped + missileInstalled + missileWrapped > 0;
        if (success) {
            FireBirthSink.setEnabled(true);
        }
        return success;
    }
}
