package cc.starsector.server.platform;

import cc.starsector.server.names.EngineSymContentInit;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The engine's OWN per-frame animation-layer
 * advance, made once per served sim tick.
 *
 * <p><b>Why it exists.</b> The windowed game advances the static animation manager {@code "CEanim"} once a
 * frame from its screen loop, one layer ABOVE the engine ({@code CombatState.traverse}'s "Pausable animations"
 * call, {@code AnimationManager.getInstance("CEanim").advanceAll(dt * timeMult)}, right before
 * {@code engine.advance}). The serve drives
 * {@code CombatEngine.advanceInner} directly, one layer down, so before this class NOTHING made that call. The
 * manager is where the engine keeps its particle systems (the 13 capped particle groups plus the UNCAPPED debris
 * and explosion systems, registered once by the engine constructor) and every
 * queued emitter. Un-advanced, the queues only grew and no particle ever aged: a measured 2.5-minute carrier
 * fight left 26,649 queued emitters, 23,702 never-expiring particles and 251 removed Ships held through the
 * {@code removing} list alone (a heap dump read).
 * A bastion serve is ONE continuous round, so the round-boundary drain ({@link RealEngineRoundHeapDrain}) never
 * ran to hide it.
 *
 * <p><b>What the call runs (read first-hand; none of it touches GL or a sim outcome).</b> The emitters
 * ({@code EmitterFactory}'s five shapes, {@code ContrailEmitter}) only add particles to the engine's groups; the
 * one delayed task only spawns an explosion PUFF in the explosion particle system; the
 * groups only age and expire particles ({@code DynamicParticleGroup.advance}). GL appears in those classes'
 * render / batch methods alone, and every particle constructor it reaches already ran headless (the heap held
 * {@code SmoothParticle} and {@code GenericTextureParticle} instances before this class). The emitters DO draw
 * from the shared {@code Math.random()} stream, exactly as the windowed game's animation layer does: a seed's
 * outcome moves, the distribution does not, and a pinned re-simulation stays equal leg to leg because the call
 * is made every tick with the tick's dt (never off a wall clock).
 *
 * <p><b>The round wrap.</b> {@link RealEngineRoundHeapDrain}'s anim clear ({@code destroyInstance}'s own two
 * statements) also drops the registrations the engine constructor made ONCE; the windowed game gets them back
 * from a fresh engine, the serve keeps one engine for the process. So {@link #arm} captures the engine-OWNED
 * registrations (the manager's entries that are values of the engine's own fields) and
 * {@link #reRegisterEngineOwned} replays them after that clear: the layer then opens every round the way a
 * fresh battle has it, with no stray emitter of the last round drawing randoms in the next.
 *
 * <p><b>Two coherent modes only.</b> ARMED = advance every tick + clear-and-re-register at a wrap. OFF = the
 * behaviour before this class (nothing advanced, a plain clear at a wrap): any resolve miss at {@link #arm}, a
 * throw from the engine's call (latched, ONE row, the serve keeps running), or the correctives master switch
 * {@code cc.predict.correctivesOff} (read by the caller, which then never arms).
 *
 * <p><b>Not done here, on purpose.</b> {@code "CENonPauseanim"} is advanced by the game too, but its one
 * producer is the weapon-arc UI, so headless it stays empty (the wrap's clear still covers it). The game passes
 * dt 0 while paused; the serve never steps a paused engine, so the pause flag is not read each tick.
 *
 * <p>Sim-thread cost: one reflective call a tick plus the engine's own work, which is its windowed
 * per-frame cost. Threading: {@link #arm} on the boot thread,
 * {@link #advance} on the sim thread, {@link #reRegisterEngineOwned} between rounds (no step in flight).
 */
public final class RealEngineAnimLayerAdvance {

    /** The named manager the engine registers its particle systems and emitters with. */
    static final String ANIM_MANAGER_CE = "CEanim";

    /** The armed instance, or null = OFF (never armed, or latched off by a throw). */
    private static volatile RealEngineAnimLayerAdvance armed;

    private final Object ceAnim;
    private final Method advanceAll;
    private final Method addAnimation;
    /** The engine constructor's own registrations, in the manager's order. */
    private final Object[] engineOwned;
    // The boxed dt of the last call: the fixed step makes it the same value tick after tick. Sim thread only.
    private float boxedFor = Float.NaN;
    private Float boxed;

    private RealEngineAnimLayerAdvance(Object ceAnim, Method advanceAll, Method addAnimation, Object[] engineOwned) {
        this.ceAnim = ceAnim;
        this.advanceAll = advanceAll;
        this.addAnimation = addAnimation;
        this.engineOwned = engineOwned;
    }

    /**
     * Arm against the live engine: called once at the binding factory's create tail. Returns the outcome for the
     * caller's boot row ({@code armed engine_owned=N}, or {@code OFF <why>}). Never throws.
     */
    public static String arm(Object engine) {
        try {
            return arm(engine, Class.forName(
                    EngineSymContentInit.CONTENT_INIT_ANIMATION_MANAGER_CLASS.className(), false,
                    RealEngineAnimLayerAdvance.class.getClassLoader()));
        } catch (Throwable t) {
            armed = null;
            return "OFF " + describe(t);
        }
    }

    /**
     * {@link #arm(Object)} against a named manager class (the contract rows hand in a stand-in with the same
     * members). The manager is a {@code DoNotObfuscate} class, so its method and list names hold across game
     * builds; one that renamed them reads {@code OFF} here, never a half-armed layer.
     */
    public static String arm(Object engine, Class<?> animManagerClass) {
        try {
            Method getInstance = animManagerClass.getMethod("getInstance", String.class);
            getInstance.setAccessible(true);
            Object ce = getInstance.invoke(null, ANIM_MANAGER_CE);
            Method advance = animManagerClass.getMethod("advanceAll", float.class);
            advance.setAccessible(true);
            Method add = null;
            for (Method m : animManagerClass.getMethods()) {
                if (m.getName().equals("addAnimation") && m.getParameterCount() == 1) {
                    add = m;
                }
            }
            if (ce == null || add == null) {
                throw new IllegalStateException("the manager has no \"" + ANIM_MANAGER_CE + "\" instance or addAnimation");
            }
            add.setAccessible(true);
            Object[] owned = captureEngineOwned(engine, animManagerClass, ce);
            if (owned.length == 0) {
                throw new IllegalStateException("no registered animation is a value of an engine field");
            }
            armed = new RealEngineAnimLayerAdvance(ce, advance, add, owned);
            return "armed engine_owned=" + owned.length;
        } catch (Throwable t) {
            armed = null;
            return "OFF " + describe(t);
        }
    }

    /**
     * The registrations the engine made for itself = the manager's entries (live, then pending) that are the
     * value of one of the engine's own instance fields. A ship's emitters are registered too, but no engine
     * field holds them, so they are left to live and die with their ship.
     */
    private static Object[] captureEngineOwned(Object engine, Class<?> animManagerClass, Object ce)
            throws ReflectiveOperationException {
        Set<Object> fieldValues = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Field f : engine.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                continue;
            }
            f.setAccessible(true);
            Object value = f.get(engine);
            if (value != null) {
                fieldValues.add(value);
            }
        }
        List<Object> owned = new ArrayList<>();
        for (String listName : new String[] {"animations", "added"}) {
            Field listField = animManagerClass.getDeclaredField(listName);
            listField.setAccessible(true);
            for (Object animation : (List<?>) listField.get(ce)) {
                if (fieldValues.remove(animation)) { // remove = each owned animation is captured once
                    owned.add(animation);
                }
            }
        }
        return owned.toArray();
    }

    /**
     * One animation-layer frame: the game's own {@code advanceAll(dt)} for {@code "CEanim"}. Called by the step
     * boundary right before the engine advance, with {@code fixedDt * timeMult} (the game's argument). OFF = one
     * volatile read. A non-finite or negative dt is skipped (an aged-by-NaN particle would never expire).
     */
    public static void advance(float dt) {
        RealEngineAnimLayerAdvance a = armed;
        if (a == null || !Float.isFinite(dt) || dt < 0f) {
            return;
        }
        try {
            if (a.boxed == null || dt != a.boxedFor) {
                a.boxed = Float.valueOf(dt);
                a.boxedFor = dt;
            }
            a.advanceAll.invoke(a.ceAnim, a.boxed);
        } catch (Throwable t) {
            latchOff("advanceAll", t);
        }
    }

    /**
     * Replay the engine constructor's registrations after the round wrap's {@code clear()} dropped them. Returns
     * how many were re-registered, or -1 when OFF (the wrap then stays the plain clear it always was).
     */
    public static int reRegisterEngineOwned() {
        RealEngineAnimLayerAdvance a = armed;
        if (a == null) {
            return -1;
        }
        try {
            for (Object animation : a.engineOwned) {
                a.addAnimation.invoke(a.ceAnim, animation);
            }
            return a.engineOwned.length;
        } catch (Throwable t) {
            latchOff("addAnimation", t);
            return -1;
        }
    }

    /** True while the layer is advanced each tick. */
    public static boolean live() {
        return armed != null;
    }

    /** Back to OFF. The contract rows' reset between cases; the round drain calls it when its clear did not run. */
    public static void disarm() {
        armed = null;
    }

    private static void latchOff(String call, Throwable t) {
        armed = null;
        System.out.println("real-engine-anim-layer LATCHED OFF at " + call + " (the serve continues with the layer"
                + " un-advanced, as before this class): " + describe(t));
    }

    private static String describe(Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        return cause.getClass().getName() + ": " + cause.getMessage();
    }
}
