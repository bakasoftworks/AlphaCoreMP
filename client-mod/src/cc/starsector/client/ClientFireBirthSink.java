package cc.starsector.client;

import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Confessing-gun stage S3, CLIENT half — the STATIC birth sink the client onFire
 * emitter ({@link CcClientOnFireEmitter}) confesses into, mirroring the server's
 * {@code FireBirthSink} shape. The engine resolves the emitter NAME through ScriptStore at
 * weapon construction on the SAME URLClassLoader chain that loads this mod's plugin, statically
 * confirmed,
 * so these statics ARE shared with the emitter's instances.
 *
 * <p>Delivery contract: {@link #deliver} hands a covered-spec discharge birth to the bound
 * {@link PredictReconcileCombatPlugin#onOwnFireBirth} at the TRUE muzzle instant — the push
 * replacement for the projectile scan's first-sighting anchor (≤1 frame late) on covered
 * specs; the scan stays the uncovered/fallback lane (dedup via {@code seenOwnProj}). Fail-soft
 * ×2: no bound consumer ⇒ {@code prebind} count + drop (falls to the scan); a callback arriving
 * off the combat/render thread ⇒ {@code threadMiss} count + drop (onFire dispatch is
 * engine-advance = same thread by recon — this makes wrongness harmless, never a data race
 * into render-thread plugin state).
 *
 * <p>Wrap lane: the sweep's wrap-don't-replace delegations live here (projectile-spec identity
 * → original class name); {@link #newDelegateOrNull} builds a FRESH original per wrapper via
 * {@code MethodHandles.publicLookup().findConstructor} — NEVER java.lang.reflect (loader B
 * denies it by constant-pool name; check-b-deny-refs is the gate).
 */
final class ClientFireBirthSink {

    /** The bound consumer window's plugin (bind at {@code init()} right after the active-slot
     *  claim, unbind at {@code teardownActive} — serverless windows stay bound-and-inert). */
    private static volatile PredictReconcileCombatPlugin consumer;
    /** The combat/render thread captured at bind — the thread-check fail-soft's reference. */
    private static volatile Thread mainThread;
    /** Latched true by a SUCCESSFUL {@link ClientOnFireAttachSweep} only (reel-echo surface). */
    private static volatile boolean installed;

    /** Sweep-recorded wrap delegations: projectile-spec identity → original onFire class name
     *  (written once at app load, read at combat time — the app-load pre-warm publication idiom). */
    private static final Map<Object, String> delegationByProjSpec = new IdentityHashMap<Object, String>();

    private static long instances;
    private static long cbErrors;
    private static long delegErrors;
    private static long prebind;
    private static long threadMiss;

    private ClientFireBirthSink() {
    }

    /** Bind the live combat window (plugin init, combat/render thread). */
    static void bind(PredictReconcileCombatPlugin plugin, Thread thread) {
        consumer = plugin;
        mainThread = thread;
    }

    /** Drop the dying window's binding (teardownActive) — deliveries fall to {@code prebind}. */
    static void unbind() {
        consumer = null;
        mainThread = null;
    }

    /** Latched by a successful sweep only — the one writer. */
    static void setInstalled(boolean value) {
        installed = value;
    }

    /** Whether the sweep landed the emitter this JVM (false ⇒ pure scan behavior). */
    static boolean installed() {
        return installed;
    }

    /** Confess one covered-spec discharge birth (emitter callback; the emitter guards throws). */
    static void deliver(DamagingProjectileAPI projectile, WeaponAPI weapon) {
        PredictReconcileCombatPlugin c = consumer;
        if (c == null) {
            prebind++;
            return;
        }
        if (Thread.currentThread() != mainThread) {
            threadMiss++;
            return;
        }
        c.onOwnFireBirth(projectile, weapon);
    }

    /** Sweep-side: record that {@code projSpec} (identity key) wraps {@code originalClassName}
     *  — called only AFTER the sweep test-constructed the original successfully. */
    static void registerDelegation(Object projSpec, String originalClassName) {
        delegationByProjSpec.put(projSpec, originalClassName);
    }

    /**
     * Emitter-side lazy resolve: a FRESH instance of the original plugin wrapped for
     * {@code projSpec}, or {@code null} when the spec wraps nothing. Fresh-per-wrapper preserves
     * the engine's per-weapon instance semantics. Construction failure counts
     * {@link #delegError()} and returns {@code null} — loud counter, never a throw.
     */
    static Object newDelegateOrNull(Object projSpec) {
        String className = delegationByProjSpec.get(projSpec);
        if (className == null) {
            return null;
        }
        try {
            Class<?> cls = Class.forName(className, true, ClientFireBirthSink.class.getClassLoader());
            return MethodHandles.publicLookup()
                    .findConstructor(cls, MethodType.methodType(void.class)).invoke();
        } catch (Throwable t) {
            delegErrors++;
            return null;
        }
    }

    /** Emitter ctor census (one per weapon built from an emitter-bearing spec). */
    static void instanceBuilt() {
        instances++;
    }

    /** A throwable swallowed inside the emitter's own callback body (bar: 0). */
    static void cbError() {
        cbErrors++;
    }

    /** A wrap-lane delegation failure — resolve, construct, or chained call (bar: 0). */
    static void delegError() {
        delegErrors++;
    }

    // Cumulative counters (never round-reset — the server sink's discipline; meter emits read
    // them via the predict_onfire_client_* keys).
    static long instances() {
        return instances;
    }

    static long cbErrors() {
        return cbErrors;
    }

    static long delegErrors() {
        return delegErrors;
    }

    static long prebind() {
        return prebind;
    }

    static long threadMiss() {
        return threadMiss;
    }
}
