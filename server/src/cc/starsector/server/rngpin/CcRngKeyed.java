package cc.starsector.server.rngpin;

import java.util.HashMap;
import java.util.Map;

/**
 * caller-keyed ORDER-INDEPENDENT seed derivation for the rewritten
 * {@code java.util.Random.<init>()V} — the product port of the scout-proven
 * rng-pin agent's holder.
 *
 * <p><b>BOOTSTRAP-LOADABLE ONLY — {@code java.util}/{@code java.lang} imports, nothing else.</b>
 * When HOOK-0044 arms, {@code CcTransformPremain} appends {@code cc-rngpin-holder.jar} (this class,
 * packaged from the same {@code server/classes} compile) to the bootstrap class-loader search: the
 * rewritten bootstrap {@code java.util.Random} must link against it, arbitrarily early, from the
 * bootstrap loader. Any dependency outside the bootstrap-visible JDK packages breaks that link.
 * Never touch this class in the premain before the bootstrap append — the system-loader copy (the
 * one on the app classpath) would load first and split the armed state.
 *
 * <p>The chain pin alone (keep the JDK's {@code seedUniquifier()} multiply-chain, pin only
 * {@code nanoTime}) makes every no-arg seed a function of master seed + GLOBAL construction order —
 * replay/A-B grade only. This holder replaces the derived seed entirely:
 *
 * <pre>  seed = splitmix64(masterSeed ^ (fnv1a64(callerClassName) + perCallerIndex * GOLDEN))</pre>
 *
 * so a site's seed depends only on the master seed, WHICH class constructs, and how many times that
 * class has constructed so far — constructions by OTHER classes are irrelevant (the contract's
 * perturb axis measures exactly this).
 *
 * <p><b>Two-phase arming (product addition over the scout):</b> {@link #init} (premain, before the
 * retransform) makes {@link #deriveKeyedSeed} callable; {@link #confirmArmed} (premain, only after
 * the fail-closed site-count checks pass) flips {@link #isArmed}. Record consumers
 * ({@code MatchRecordMeta}'s {@code rng.keyed}) read {@link #isArmed()}, so a JVM records
 * {@code true} iff ITS OWN {@code Random.<init>} rewrite verifiably landed — never the request
 * knob. In an un-armed JVM the app-classpath copy of this class answers {@code false} honestly.
 *
 * <p>Determinism contract: single-threaded per caller class ({@code synchronized}, so concurrent
 * same-class constructions are well-defined but scheduling-ordered). The headless combat census
 * measured all constructions on thread=main; revisit if that changes.
 */
public final class CcRngKeyed {
    /** SplitMix64 increment (golden ratio), spreads per-caller indices. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private static StackWalker walker;
    private static long masterSeed;
    private static boolean ready;
    private static boolean armed;
    private static final Map<String, long[]> perCallerIndex = new HashMap<>();
    /**
     * The ISOLATION side stream. Salts the side master off the live one, so a
     * construction inside a {@code CombatRngRearm.isolate} window never reproduces a live seed.
     */
    private static final long ISOLATION_SALT = 0x3C79AC492BA7B653L;
    /** The thread inside an isolation window (null = none); its constructions index {@link #isolatedIndex}. */
    private static Thread isolatedThread;
    private static final Map<String, long[]> isolatedIndex = new HashMap<>();

    private CcRngKeyed() {
    }

    /** Called once from the premain (HOOK-0044 only), AFTER the bootstrap append, BEFORE the retransform. */
    public static synchronized void init(long seed) {
        if (ready) {
            throw new IllegalStateException("[rng-keyed] init called twice");
        }
        walker = StackWalker.getInstance();
        masterSeed = seed;
        ready = true;
    }

    /** Called once from the premain ONLY after the retransform's fail-closed site checks passed. */
    public static synchronized void confirmArmed() {
        if (!ready) {
            throw new IllegalStateException("[rng-keyed] confirmArmed before init — fail-closed");
        }
        armed = true;
    }

    /**
     * The ACTUAL armed state of THIS JVM's {@code Random.<init>} rewrite (two-phase confirmed) —
     * the value {@code MatchRecordMeta} records as {@code rng.keyed} and {@code CombatRngPinBoot}
     * enforces the request knob against.
     */
    public static synchronized boolean isArmed() {
        return armed;
    }

    /**
     * Whether {@link #init} ran in THIS JVM (the derivation is callable). Distinct from
     * {@link #isArmed()}: the per-round re-arm only needs a live master seed + index map to reset,
     * and reports the keyed axis from this answer.
     */
    public static synchronized boolean isReady() {
        return ready;
    }

    /**
     * The per-round RE-ARM: replace the master seed with the round's seed and CLEAR every caller's
     * construction index. WHY: without the reset, each class's index on entering round 1 equals its
     * construction count through the warm-up, and the warm-up's pass count is JIT-timed (it stops
     * on the first compile-quiet pass), so every keyed {@code Random} built after it would get a
     * seed that varies run to run. After a re-arm a site's seed depends only on the round seed,
     * its class and its count WITHIN the round. Synchronized with {@link #deriveKeyedSeed}, so a
     * construction never sees a half-reset state.
     *
     * @return {@code false} when {@link #init} never ran (an un-armed JVM has nothing to re-arm;
     *         the caller reports it), else {@code true}
     */
    public static synchronized boolean rearm(long roundSeed) {
        if (!ready) {
            return false;
        }
        masterSeed = roundSeed;
        perCallerIndex.clear();
        isolatedIndex.clear();
        return true;
    }

    /**
     * Opens the ISOLATION window on the calling thread (the caller is
     * {@code CombatRngRearm.isolate}, sim thread). Until {@link #isolateEnd()}, THIS thread's keyed
     * constructions index a side map under a salted master, so an on-thread pass whose row count the wall
     * clock decides (the wave inflater's warm walk building vanilla campaign objects after the frame-0
     * re-arm) can neither advance nor replay the live per-caller indices the combat sim's own constructions
     * key on. Side seeds never repeat within a round (the side map clears only at {@link #rearm}) and share
     * no master with the live stream. Other threads are untouched. Not reentrant: the caller nests.
     * A no-op before {@link #init} (nothing derives there).
     */
    public static synchronized void isolateBegin() {
        if (!ready) {
            return;
        }
        isolatedThread = Thread.currentThread();
    }

    /** Closes the window {@link #isolateBegin()} opened (any thread may close; the live map was never touched). */
    public static synchronized void isolateEnd() {
        isolatedThread = null;
    }

    /**
     * Called from the rewritten {@code java.util.Random.<init>()V} with the chain-derived seed on
     * the stack; DISCARDS it and returns the caller-keyed order-independent seed (which then flows
     * into {@code Random(long)} exactly as the original seed would). Inside an isolation window
     * ({@link #isolateBegin()}, the opening thread only) the side map + salted master key the seed instead.
     */
    public static synchronized long deriveKeyedSeed(long ignoredChainSeed) {
        if (!ready) {
            throw new IllegalStateException("[rng-keyed] deriveKeyedSeed before init — fail-closed");
        }
        String caller = walker.walk(frames -> frames
                .dropWhile(f -> f.getClassName().equals("cc.starsector.server.rngpin.CcRngKeyed")
                        || f.getClassName().equals("java.util.Random"))
                .map(StackWalker.StackFrame::getClassName)
                .findFirst()
                .orElse("?"));
        boolean isolated = isolatedThread != null && Thread.currentThread() == isolatedThread;
        Map<String, long[]> map = isolated ? isolatedIndex : perCallerIndex;
        long[] counter = map.get(caller);
        if (counter == null) {
            counter = new long[1];
            map.put(caller, counter);
        }
        long index = counter[0]++;
        long master = isolated ? masterSeed ^ ISOLATION_SALT : masterSeed;
        return splitMix64(master ^ (fnv1a64(caller) + index * GOLDEN));
    }

    private static long fnv1a64(String s) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001B3L;
        }
        return h;
    }

    private static long splitMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
