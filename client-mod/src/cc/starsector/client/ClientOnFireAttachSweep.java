package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * The once-per-JVM CLIENT pre-combat sweep that installs the onFire
 * emitter ({@link CcClientOnFireEmitter}) by NAME onto every projectile spec via the supported
 * {@code ProjectileSpecAPI.setOnFireClassName} — the server {@code OnFireAttachSweep} (S1)
 * ported to the mod side: DIRECT API calls (the client compiles against the API jars — no
 * reflection needed), and {@code MethodHandles.publicLookup().findConstructor} for the wrap
 * test-construct (NEVER {@code getDeclaredConstructor}: loader B denies {@code java.lang.reflect}
 * by constant-pool name — check-b-deny-refs is the gate). Weapon ctors latch the plugin at
 * build and specs are Settings-global, so a sweep at {@code onApplicationLoad} covers every
 * weapon this JVM builds, respawns included. Process-lifetime install, no restore (S1 law).
 *
 * <p>Wrap-don't-replace: a spec already carrying a vanilla onFire class is
 * wrapped ONLY when the original eagerly resolves, is plugin-assignable, and test-constructs AT
 * SWEEP TIME — then the delegation is recorded in {@link ClientFireBirthSink} and the emitter
 * (which chain-calls a fresh original per wrapper) replaces the name. Any failure leaves that
 * spec UNTOUCHED ({@code wrap_skipped}): behavior is provably unchanged-or-not-installed.
 *
 * <p>Fail-soft ×3 (the S1 discipline): (a) emitter-name resolvable pre-check; (b) wrap failures
 * skip per-spec; (c) the whole sweep is caught to a boolean — failure = decl lane off (pure
 * scan behavior), boot continues. Compiled ON (E3 hardwire; the
 * {@code cc.predict.onFireDecl} knob-resolved shape is in git).
 */
final class ClientOnFireAttachSweep {

    /** Referenced by NAME only — ScriptStore resolution through the mod-jar loader chain is the
     *  seam being ridden (statics shared with the plugin, statically confirmed). */
    static final String EMITTER = "cc.starsector.client.CcClientOnFireEmitter";

    private ClientOnFireAttachSweep() {
    }

    /**
     * Run the sweep unless the knob disables it. True = installed and
     * {@link ClientFireBirthSink#installed()} latched; false = decl lane off (knob, no specs,
     * or a caught sweep failure — each logged).
     */
    static boolean runIfEnabled() {
        // In the CC server JVM this mod also loads (content fixtures), and
        // this sweep must stand down — the server's OnFireAttachSweep is that JVM's
        // confession lane, and a mod-jar emitter name left on the specs is unresolvable
        // through the server's app-loader wrap lane (wrap_skipped=53 covered=0: the
        // foe-irpulse presentation hole). The role property is set by
        // PilotIntentCombatServerMain before boot; absent on every real client.
        if ("server".equals(System.getProperty("cc.jvm.role"))) {
            PilotWatchLog.info("onfire-attach-client skipped reason=server_jvm");
            return false;
        }
        // Compiled ON (E3 hardwire): the cc.predict.onFireDecl
        // knob-resolved shape — run-vanilla-compare's =false purity mode included — is in git.
        try {
            return sweep();
        } catch (Throwable t) {
            PilotWatchLog.error("onfire-attach-client failed error=" + t.getClass().getName()
                    + ": " + t.getMessage(), t);
            return false;
        }
    }

    private static boolean sweep() {
        ClassLoader loader = ClientOnFireAttachSweep.class.getClassLoader();
        // (a) resolvable pre-check — the emitter is compiled into this very jar, so this is a
        // receipt-parity formality vs the server sweep; it must never leave a dangling name.
        boolean resolvable;
        try {
            Class.forName(EMITTER, false, loader);
            resolvable = true;
        } catch (ClassNotFoundException | LinkageError emitterMissing) {
            resolvable = false;
        }
        if (!resolvable) {
            PilotWatchLog.info("onfire-attach-client installed=0 wrapped=0 wrap_skipped=0"
                    + " skipped_dup=0 no_proj_spec=0 resolvable=false covered=0");
            return false;
        }
        SettingsAPI settings = Global.getSettings();
        // Weapon-spec census closure (rode the jar fork like the server twin):
        // getActuallyAllWeaponSpecs() includes system/hidden specs getAllWeaponSpecs() omits.
        List<WeaponSpecAPI> weaponSpecs = settings == null ? null : settings.getActuallyAllWeaponSpecs();
        if (weaponSpecs == null || weaponSpecs.isEmpty()) {
            // Fail-soft: a spec-less run shape means nothing to cover — pure scan behavior.
            PilotWatchLog.info("onfire-attach-client installed=0 wrapped=0 wrap_skipped=0"
                    + " skipped_dup=0 no_proj_spec=0 resolvable=true covered=0 (no weapon specs)");
            return false;
        }
        // Value = the coverage OUTCOME for that projectile spec, so a later weapon spec sharing
        // it counts covered iff the first crossing actually landed the emitter (server donor law).
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        int installed = 0;
        int wrapped = 0;
        int wrapSkipped = 0;
        int skippedDup = 0;
        int noProjSpec = 0;
        int covered = 0;
        // MissileSpecAPI does NOT extend ProjectileSpecAPI, so every
        // missile/flare launcher falls into no_proj_spec and the onFire seam (our best local-fire
        // witness) is STRUCTURALLY BLIND to flares — which is precisely why class A cannot be closed
        // from recorded artifacts. Split the bucket so the blindness is a measured number instead of
        // an inference. Census only: nothing is installed on a missile spec here (the emitter is an
        // OnFireEffectPlugin the missile lane never dispatches).
        int noProjSpecMissile = 0;
        int noProjSpecNull = 0;
        int noProjSpecOther = 0;
        StringBuilder missileIds = new StringBuilder();
        for (WeaponSpecAPI ws : weaponSpecs) {
            Object psRaw = ws.getProjectileSpec();
            if (!(psRaw instanceof ProjectileSpecAPI)) {
                noProjSpec++; // beams/missiles/null — the scan lane keeps them (~65/118 covered)
                if (psRaw == null) {
                    noProjSpecNull++;
                } else if (psRaw instanceof MissileSpecAPI) {
                    noProjSpecMissile++;
                    // MissileSpecAPI has no getId() (unlike ProjectileSpecAPI) — key the
                    // census on the WEAPON id, which every WeaponSpecAPI carries.
                    String wid = ws.getWeaponId();
                    if (wid != null && missileIds.length() < 900) {
                        if (missileIds.length() > 0) {
                            missileIds.append(',');
                        }
                        missileIds.append(wid);
                    }
                } else {
                    noProjSpecOther++;
                }
                continue;
            }
            ProjectileSpecAPI ps = (ProjectileSpecAPI) psRaw;
            Boolean priorOutcome = seen.get(ps);
            if (priorOutcome != null) {
                skippedDup++;
                if (priorOutcome.booleanValue()) {
                    covered++;
                }
                continue;
            }
            String prior = ps.getOnFireClassName();
            boolean landed;
            if (prior == null) {
                ps.setOnFireClassName(EMITTER);
                installed++;
                landed = true;
            } else if (EMITTER.equals(prior)) {
                skippedDup++;
                landed = true;
            } else {
                // (b) wrap-don't-replace: prove the original constructible BEFORE touching the
                // spec; the emitter chain-calls a fresh original per wrapper at dispatch.
                try {
                    Class<?> original = Class.forName(prior, true, loader);
                    if (!OnFireEffectPlugin.class.isAssignableFrom(original)) {
                        throw new IllegalStateException(prior + " is not an OnFireEffectPlugin");
                    }
                    MethodHandles.publicLookup()
                            .findConstructor(original, MethodType.methodType(void.class)).invoke();
                    ClientFireBirthSink.registerDelegation(ps, prior);
                    ps.setOnFireClassName(EMITTER);
                    wrapped++;
                    landed = true;
                } catch (Throwable wrapFailure) {
                    wrapSkipped++;
                    landed = false;
                    // 1290 (81) S9: this plugin never wraps -> its births scan <=1 frame late all
                    // session; before this row only the aggregate count reported.
                    FallbackAnnounce.announceOnce("s9-onfire-wrap-failed#" + prior, "wrap failed: "
                            + wrapFailure.getClass().getSimpleName());
                }
            }
            seen.put(ps, Boolean.valueOf(landed));
            if (landed) {
                covered++;
            }
        }
        PilotWatchLog.info("onfire-attach-client installed=" + installed
                + " wrapped=" + wrapped
                + " wrap_skipped=" + wrapSkipped
                + " skipped_dup=" + skippedDup
                + " no_proj_spec=" + noProjSpec
                + " no_proj_spec_missile=" + noProjSpecMissile
                + " no_proj_spec_null=" + noProjSpecNull
                + " no_proj_spec_other=" + noProjSpecOther
                + " resolvable=true"
                + " covered=" + covered
                + " missile_spec_weapons=" + (missileIds.length() == 0 ? "(none)" : missileIds.toString()));
        boolean success = installed + wrapped > 0;
        if (success) {
            ClientFireBirthSink.setInstalled(true);
        }
        return success;
    }
}
