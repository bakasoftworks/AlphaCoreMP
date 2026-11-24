package cc.starsector.server.platform;

import cc.starsector.server.names.EngineRow;
import cc.starsector.server.names.EngineSymContentInit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The engine's OWN two statements around its inner step: the reset of the AI's
 * hulk-obstacle cache, made right before and right after every served sim tick.
 *
 * <p><b>Why it exists.</b> The windowed game steps the engine through the OUTER {@code CombatEngine.advance}, which
 * nulls one static list of the AI's helper class right before {@code advanceInner} and again right after it.
 *The list
 * is the AI's per-frame cache of the bodies that can block a line of fire without being a target: every non-fighter
 * Ship that is a hulk with no station slot, or that is untargetable. The first query of a frame builds it from the
 * engine's live Ship list; the strike-fire gate and the threat evaluation read it.
 *The serve drives {@code advanceInner}
 * directly, one layer down, so before this class NOTHING made either statement: the list was built once, at the
 * first query after boot, and stayed FROZEN for the life of the process. An AI ship kept weighing the bodies of
 * that first tick (removed ones included, at their last position) and never saw a hulk made later; the same list
 * rode on into every later round. A heap dump named it as a holder of removed Ships, which is
 * how the gap was found.
 *
 * <p><b>What the two calls run (read first-hand).</b> Each is one plain assignment of null to that static:
 * no GL, no input, no UI, no random draw. The rebuild they cause is the engine's own once-a-frame walk of its Ship
 * list, made inside the step by the first AI that asks.
 *
 * <p><b>Two modes only.</b> ARMED = both calls every tick. OFF = the behaviour before this class: a resolve miss at
 * {@link #arm()}, a throw from either call (latched, ONE row, the serve keeps running), or the correctives master
 * switch {@code cc.predict.correctivesOff} (read by the caller, which then never arms).
 *
 * <p>Sim-thread cost: two reflective calls of a one-statement static a tick; they earn the thread as the
 * engine's own frame statements around its step. Threading: {@link #arm} on the boot thread, {@link #beforeStep} /
 * {@link #afterStep} on the sim thread.
 */
public final class RealEngineHulkCacheBracket {

    private static final Object[] NO_ARGS = new Object[0];

    /** The armed pair, or null = OFF (never armed, or latched off by a throw). */
    private static volatile RealEngineHulkCacheBracket armed;

    private final Method preStep;
    private final Method postStep;

    private RealEngineHulkCacheBracket(Method preStep, Method postStep) {
        this.preStep = preStep;
        this.postStep = postStep;
    }

    /**
     * Arm against the engine's AI helper class: called once at the binding factory's create tail. Returns the
     * outcome for the caller's boot row ({@code armed}, or {@code OFF <why>}). Never throws.
     */
    public static String arm() {
        try {
            EngineRow pre = EngineSymContentInit.CONTENT_INIT_AI_HULK_CACHE_PRE_STEP_METHOD;
            EngineRow post = EngineSymContentInit.CONTENT_INIT_AI_HULK_CACHE_POST_STEP_METHOD;
            Class<?> owner = Class.forName(pre.className(), false, RealEngineHulkCacheBracket.class.getClassLoader());
            return arm(pre.method(owner), post.method(owner));
        } catch (Throwable t) {
            armed = null;
            return "OFF " + describe(t);
        }
    }

    /**
     * {@link #arm()} against two named methods (the contract rows hand in a stand-in's). Each must be a static
     * no-arg method: anything else reads {@code OFF}, never a half-armed bracket.
     */
    public static String arm(Method preStep, Method postStep) {
        try {
            for (Method m : new Method[] {preStep, postStep}) {
                if (m == null || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                    throw new IllegalStateException("not a static no-arg method: " + m);
                }
                m.setAccessible(true);
            }
            armed = new RealEngineHulkCacheBracket(preStep, postStep);
            return "armed";
        } catch (Throwable t) {
            armed = null;
            return "OFF " + describe(t);
        }
    }

    /** The engine's statement right before its inner step. Called by the step boundary. OFF = one volatile read. */
    public static void beforeStep() {
        RealEngineHulkCacheBracket a = armed;
        if (a == null) {
            return;
        }
        try {
            a.preStep.invoke(null, NO_ARGS);
        } catch (Throwable t) {
            latchOff("the pre-step reset", t);
        }
    }

    /** The engine's statement right after its inner step. Called by the step boundary. OFF = one volatile read. */
    public static void afterStep() {
        RealEngineHulkCacheBracket a = armed;
        if (a == null) {
            return;
        }
        try {
            a.postStep.invoke(null, NO_ARGS);
        } catch (Throwable t) {
            latchOff("the post-step reset", t);
        }
    }

    /** True while the bracket is made each tick. */
    public static boolean live() {
        return armed != null;
    }

    /** Back to OFF. The contract rows' reset between cases; the serve never calls it. */
    public static void disarm() {
        armed = null;
    }

    private static void latchOff(String call, Throwable t) {
        armed = null;
        System.out.println("real-engine-hulk-cache-bracket LATCHED OFF at " + call + " (the serve continues without"
                + " the bracket, as before this class): " + describe(t));
    }

    private static String describe(Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        return cause.getClass().getName() + ": " + cause.getMessage();
    }
}
