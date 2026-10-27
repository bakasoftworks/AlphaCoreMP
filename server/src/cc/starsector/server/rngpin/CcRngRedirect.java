package cc.starsector.server.rngpin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The static targets of the two call-site redirects
 * (HOOK-0049, HOOK-0050): the only engine non-determinism sources a pinned replay cannot avoid
 * once the {@code Random} constructor is keyed — random-UUID ship ids (they seed the AI's
 * point-move randomness and key the fighter landing slot) and the identity-hash landing seed.
 *
 * <p><b>BOOTSTRAP-LOADABLE ONLY — {@code java.lang} / {@code java.lang.reflect} / {@code java.util}
 * imports, nothing else.</b> The redirected game classes ({@code Misc}, the landing-bay class) load
 * through the game's own loaders and link against THIS class; the premain appends
 * {@code cc-rngpin-holder.jar} (this class, packaged from the same compile) to the bootstrap search
 * so every loader resolves the one armed copy. Any dependency outside the bootstrap-visible JDK
 * packages breaks that link. Never touch this class in the premain before the bootstrap append —
 * the system-loader copy on the app classpath would load first and split the armed state (the
 * same law as {@link CcRngKeyed}); that is why the redirects arm ONLY after the keyed constructor
 * pin's append.
 *
 * <p>{@link #keyedUuid()} keys by the CALLER class with its own per-caller index, exactly the
 * {@link CcRngKeyed} derivation shape with its own master seed and map: ships, fleet members and
 * persons each own a stream, so an on-thread pass whose length the wall clock decides (a catalog
 * promotion, a time-budgeted warm walk) cannot shift the ship ids. {@link #rearm(long)} resets the
 * master seed and every per-caller index (the per-round re-arm seam).
 *
 * <p>Determinism contract: {@code synchronized}; every engine caller found by a static read runs
 * on the sim thread. The counters are exposed for the arm receipt and the record's meta.
 */
public final class CcRngRedirect {
    /** SplitMix64 increment (golden ratio), spreads per-caller indices. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    /** Concrete fighter classes whose {@code getId} lookup is cached (the engine has one: its ship class). */
    private static final int GETID_CACHE_CAP = 16;

    private static StackWalker walker;
    private static long masterSeed;
    private static boolean inited;
    private static final Map<String, long[]> perCallerIndex = new HashMap<>();
    /** Per concrete class: its public {@code getId()}, or null when it has none (negative-cached). */
    private static final Map<Class<?>, Method> getIdByClass = new HashMap<>();
    private static long uuids;
    private static long landingKeys;
    private static long landingKeyFallbacks;
    /**
     * The ISOLATION side stream — the same shape as {@link CcRngKeyed}'s. Inside a
     * {@code CombatRngRearm.isolate} window the opening thread's mints index {@link #isolatedIndex} under
     * {@code masterSeed ^ ISOLATION_SALT}: the wave inflater's engine pass builds a campaign fleet + member
     * per row ({@code Misc.genUID} → here, the SAME caller classes an in-round spawn mints through), at a tick
     * the wall clock decides, so without the side stream every later in-round id would shift run-to-run.
     * Side ids never repeat within a round (the side map clears only at {@link #rearm}) and never meet a
     * live id (a different master); the census ignores the window's draws (they are restored, so they are
     * not the live stream's).
     */
    private static final long ISOLATION_SALT = 0x3C79AC492BA7B653L;
    private static Thread isolatedThread;
    private static final Map<String, long[]> isolatedIndex = new HashMap<>();

    // ---- the Math.random() caller CENSUS ----
    // A DIAGNOSTIC, default OFF, armed by the premain from -Dcc.rngpin.census=<ticks>: the premain's
    // MathRandomCensusApplier rewrites every `invokestatic Math.random()D` outside the JDK into
    // `random(int site)` below, which draws EXACTLY ONE Math.random() per call (the JVM-global stream is
    // untouched — a census-ON re-sim takes the same draws as a census-OFF one) and, while a window is
    // open, counts the draw against its call site. WHY: two re-sims of one reel diverged at tick 8 by
    // exactly three Math.random draws before the carrier's first zero-flux band snap; 319
    // engine sites across 83 files made the static route wide, so the census names the site per tick.
    // Window = from the frame-0 re-arm (censusOpen, CombatRngRearm) for <ticks> sim ticks; one
    // `rng_census tick=T total=N s<id>=<n> …` line per tick on stdout (an UNPACED re-sim's log; never
    // a shipping-default path — the println rides the sim thread by design, diagnostic only).
    /** The census is armed in this JVM (a plain boolean read: the one per-frame cost when OFF). */
    private static volatile boolean censusArmed;
    private static int censusTicks;
    private static boolean censusOpen;
    private static int censusTicksPrinted;
    private static Thread censusThread;
    private static final StringBuilder censusLine = new StringBuilder(2048);
    /** Site id → its name (class.method+desc#ordinal), registered by the transformer at rewrite time. */
    private static String[] censusNames = new String[64];
    private static int censusSiteCount;
    /** Per site: draws this tick (sim thread); a site's name line prints once, at its first counted draw. */
    private static int[] censusCounts = new int[64];
    private static boolean[] censusNamed = new boolean[64];
    /** Draws from a thread other than the one that opened the window: {@code s<id>@<thread>} → count. */
    private static final Map<String, int[]> censusOffThread = new HashMap<>();
    private static long censusTotal;
    /** Per site: ever drew OFF the census thread — such a site still gets its name line. */
    private static boolean[] censusSeenOff = new boolean[64];
    /** The tick the open window is accumulating (the last printed tick + 1; 0 before the first print). */
    private static long censusTickNow;
    // ---- the per-site STACK capture ----
    // -Dcc.rngpin.censusStack=<sub[,sub]> (env CC_RNGPIN_CENSUS_STACK): while the window is open, every
    // counted draw whose site NAME contains one of the substrings prints the first CENSUS_STACK_FRAMES
    // frames past this class as `rng_census_stack tick=T site=<name> frames=<class.method>|…`. WHY: the
    // census named the draw site (IntervalUtil.nextInterval) but not the OWNER whose advance(dt) is fed
    // unshared time — the stack names it. Diagnostic only; null = OFF (no walker call on the draw path).
    private static final int CENSUS_STACK_FRAMES = 14;
    private static String[] censusStackMatch;
    private static StackWalker censusWalker;
    private static final StringBuilder censusStackLine = new StringBuilder(1024);

    private CcRngRedirect() {
    }

    /**
     * The census transformer's registration of one rewritten {@code Math.random()} call site (premain
     * side, at class rewrite): returns the site id the rewritten bytecode pushes. Names are
     * {@code <class>.<method><descriptor>#<ordinal>}; ids are dense from 0.
     */
    public static synchronized int censusSite(String name) {
        if (censusSiteCount == censusNames.length) {
            censusNames = java.util.Arrays.copyOf(censusNames, censusSiteCount * 2);
            censusCounts = java.util.Arrays.copyOf(censusCounts, censusSiteCount * 2);
            censusNamed = java.util.Arrays.copyOf(censusNamed, censusSiteCount * 2);
            censusSeenOff = java.util.Arrays.copyOf(censusSeenOff, censusSiteCount * 2);
        }
        censusNames[censusSiteCount] = name;
        return censusSiteCount++;
    }

    /** Arms the census for {@code ticks} sim ticks per window (premain, once; the window opens at {@link #censusOpen}). */
    public static synchronized void censusArm(int ticks) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("[rng-census] ticks must be > 0, got " + ticks);
        }
        censusTicks = ticks;
        censusWalker = StackWalker.getInstance();
        censusArmed = true;
    }

    /**
     * Arms the per-site STACK capture (premain, once, after {@link #censusArm}): a counted draw whose site
     * name contains any of {@code substrings} prints its caller frames. Refuses an empty list or an
     * unarmed census — a stack request without a window would never print and must not pass silently.
     */
    public static synchronized void censusArmStack(String[] substrings) {
        if (!censusArmed) {
            throw new IllegalStateException("[rng-census] censusArmStack before censusArm — the stack capture needs a window");
        }
        if (substrings == null || substrings.length == 0) {
            throw new IllegalArgumentException("[rng-census] censusArmStack needs at least one site-name substring");
        }
        censusStackMatch = substrings.clone();
    }

    /** True once the premain armed the census in THIS JVM (the app-classpath copy answers false honestly). */
    public static boolean censusArmed() {
        return censusArmed;
    }

    /** The armed stack-capture substrings (a copy), or null when the capture is OFF. */
    public static synchronized String[] censusStackMatch() {
        return censusStackMatch == null ? null : censusStackMatch.clone();
    }

    /** Sites registered so far (the arm receipt). */
    public static synchronized int censusSites() {
        return censusSiteCount;
    }

    /**
     * The redirected {@code Math.random()}: exactly one draw from the JVM-global stream, counted against
     * {@code site} while a window is open. Never synchronizes on the draw itself (the stream's own CAS
     * orders it exactly as the original call would).
     */
    public static double random(int site) {
        double d = Math.random();
        if (censusArmed) {
            censusCount(site);
        }
        return d;
    }

    private static synchronized void censusCount(int site) {
        if (!censusOpen) {
            return;
        }
        if (isolatedThread != null && Thread.currentThread() == isolatedThread) {
            return; // an isolated draw is restored after its window: not a live-stream draw
        }
        censusTotal++;
        boolean known = site >= 0 && site < censusSiteCount;
        if (Thread.currentThread() == censusThread) {
            if (known) {
                censusCounts[site]++;
            }
        } else {
            if (known) {
                censusSeenOff[site] = true;
            }
            String key = "s" + site + "@" + Thread.currentThread().getName().replace(' ', '_');
            int[] n = censusOffThread.get(key);
            if (n == null) {
                n = new int[1];
                censusOffThread.put(key, n);
            }
            n[0]++;
        }
        if (censusStackMatch != null && known && censusStackWanted(censusNames[site])) {
            censusPrintStack(site);
        }
    }

    private static boolean censusStackWanted(String siteName) {
        for (String sub : censusStackMatch) {
            if (siteName.contains(sub)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One {@code rng_census_stack tick=T site=<name> frames=<f1>|<f2>|…} line: the first
     * {@value #CENSUS_STACK_FRAMES} frames past this class (the rewritten site's method first, then its
     * callers), each {@code class.method}. The tick = the one the open window is accumulating (the next
     * {@code rng_census tick=} line this draw is counted in).
     */
    private static void censusPrintStack(int site) {
        censusStackLine.setLength(0);
        censusStackLine.append("rng_census_stack tick=").append(censusTickNow).append(" site=").append(censusNames[site])
                .append(" frames=");
        censusWalker.walk(frames -> {
            frames.dropWhile(f -> f.getClassName().equals("cc.starsector.server.rngpin.CcRngRedirect"))
                    .limit(CENSUS_STACK_FRAMES)
                    .forEach(f -> censusStackLine.append(f.getClassName()).append('.').append(f.getMethodName()).append('|'));
            return null;
        });
        if (censusStackLine.charAt(censusStackLine.length() - 1) == '|') {
            censusStackLine.setLength(censusStackLine.length() - 1);
        }
        if (Thread.currentThread() != censusThread) {
            censusStackLine.append(" thread=").append(Thread.currentThread().getName().replace(' ', '_'));
        }
        System.out.println(censusStackLine);
    }

    /**
     * Opens a window on the calling thread (the sim thread, at the frame-0 re-arm): counts restart, the
     * next {@link #censusTick} prints tick 1 of the window. A no-op unarmed.
     */
    public static synchronized void censusOpen() {
        if (!censusArmed) {
            return;
        }
        censusOpen = true;
        censusTicksPrinted = 0;
        censusTickNow = 0L;
        censusThread = Thread.currentThread();
        java.util.Arrays.fill(censusCounts, 0);
        censusOffThread.clear();
        censusTotal = 0L;
        System.out.println("rng_census open sites=" + censusSiteCount + " ticks=" + censusTicks
                + " thread=" + censusThread.getName().replace(' ', '_')
                + (censusStackMatch == null ? "" : " stack=" + String.join(",", censusStackMatch)));
    }

    /**
     * Called by the serve after each sim advance with the tick just completed: prints the draws counted
     * since the previous call as that tick's line, and closes the window after {@code ticks} lines. A
     * plain boolean read when the census is not armed (the per-frame cost of the OFF default).
     */
    public static void censusTick(long tick) {
        if (!censusArmed) {
            return;
        }
        censusTickLocked(tick);
    }

    private static synchronized void censusTickLocked(long tick) {
        if (!censusOpen) {
            return;
        }
        for (int i = 0; i < censusSiteCount; i++) {
            if ((censusCounts[i] != 0 || censusSeenOff[i]) && !censusNamed[i]) {
                censusNamed[i] = true;
                System.out.println("rng_census_site id=" + i + " name=" + censusNames[i]);
            }
        }
        censusLine.setLength(0);
        censusLine.append("rng_census tick=").append(tick).append(" total=").append(censusTotal);
        for (int i = 0; i < censusSiteCount; i++) {
            if (censusCounts[i] != 0) {
                censusLine.append(" s").append(i).append('=').append(censusCounts[i]);
                censusCounts[i] = 0;
            }
        }
        if (!censusOffThread.isEmpty()) {
            for (Map.Entry<String, int[]> e : new java.util.TreeMap<>(censusOffThread).entrySet()) {
                censusLine.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]);
            }
            censusOffThread.clear();
        }
        censusTotal = 0L;
        censusTickNow = tick + 1;
        System.out.println(censusLine);
        if (++censusTicksPrinted >= censusTicks) {
            censusOpen = false;
            System.out.println("rng_census close ticks=" + censusTicksPrinted);
        }
    }

    /** Called once from the premain, AFTER the bootstrap append, BEFORE the redirect transformer registers. */
    public static synchronized void init(long master) {
        if (inited) {
            throw new IllegalStateException("[rng-redirect] init called twice");
        }
        walker = StackWalker.getInstance();
        masterSeed = master;
        inited = true;
    }

    /**
     * Re-keys every stream from {@code roundSeed} (any sign): sets the master seed and clears every
     * per-caller index, so the next mint per caller class is index 0 again. False before
     * {@link #init} (a JVM where the redirects never armed — nothing to re-key).
     */
    public static synchronized boolean rearm(long roundSeed) {
        if (!inited) {
            return false;
        }
        masterSeed = roundSeed;
        perCallerIndex.clear();
        isolatedIndex.clear();
        return true;
    }

    /** True once the premain armed the redirects in THIS JVM (the app-classpath copy answers false honestly). */
    public static synchronized boolean isInited() {
        return inited;
    }

    /**
     * Opens the ISOLATION window on the calling thread (the caller is
     * {@code CombatRngRearm.isolate}, sim thread): until {@link #isolateEnd()} this thread's
     * {@link #keyedUuid()} mints take the side stream (see {@link #ISOLATION_SALT}) and its
     * {@link #random(int)} draws go uncounted by the census. Other threads are untouched. Not reentrant:
     * the caller nests. A no-op before {@link #init} for the mints (nothing mints there); the census skip
     * still applies.
     */
    public static synchronized void isolateBegin() {
        isolatedThread = Thread.currentThread();
    }

    /** Closes the window {@link #isolateBegin()} opened (the live map was never touched). */
    public static synchronized void isolateEnd() {
        isolatedThread = null;
    }

    /**
     * The redirected {@code UUID.randomUUID()} inside {@code Misc.genUID()}: a version-4, IETF-variant
     * UUID (the same 36-character shape) derived from the master seed + the caller class (the first
     * frame past this class and {@code Misc}) + that class's per-caller index. Throws before
     * {@link #init} — the premain never registers the redirect before init, so this fires only in a
     * mis-armed JVM (fail-closed, never a silent random id).
     */
    public static synchronized UUID keyedUuid() {
        if (!inited) {
            throw new IllegalStateException("[rng-redirect] keyedUuid before init — fail-closed");
        }
        String caller = walker.walk(frames -> frames
                .dropWhile(f -> f.getClassName().equals("cc.starsector.server.rngpin.CcRngRedirect")
                        || f.getClassName().equals("com.fs.starfarer.api.util.Misc"))
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
        long msb = splitMix64(master ^ (fnv1a64(caller) + index * GOLDEN));
        long lsb = splitMix64(msb + GOLDEN);
        msb &= ~0xF000L; // version 4, exactly as UUID.randomUUID() sets it
        msb |= 0x4000L;
        lsb &= 0x3FFFFFFFFFFFFFFFL; // IETF variant
        lsb |= 0x8000000000000000L;
        uuids++;
        return new UUID(msb, lsb);
    }

    /**
     * The redirected {@code fighter.hashCode()} feeding the landing-slot seed: the fighter's engine id
     * hashed ({@code getId().hashCode()}), deterministic while the id redirect pins the ids; the engine
     * still multiplies it and seeds its own {@code Random}. Never throws into the landing path: a
     * class without a public {@code getId()}, a non-String / null id or any reflective failure
     * counts one {@link #landingKeyFallbacks()} and returns the original identity hash. A null
     * fighter throws the same {@code NullPointerException} the original call would.
     */
    public static synchronized int landingKey(Object fighter) {
        Class<?> type = fighter.getClass();
        try {
            Method getId;
            if (getIdByClass.containsKey(type)) {
                getId = getIdByClass.get(type);
            } else {
                getId = lookupGetId(type);
                if (getIdByClass.size() < GETID_CACHE_CAP) {
                    getIdByClass.put(type, getId);
                }
            }
            if (getId != null) {
                Object id = getId.invoke(fighter);
                if (id instanceof String) {
                    landingKeys++;
                    return id.hashCode();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // counted below: the fallback counter is this path's one meter
        }
        landingKeyFallbacks++;
        return fighter.hashCode();
    }

    /** UUIDs minted through {@link #keyedUuid()} in this JVM (all callers, all re-arms). */
    public static synchronized long uuids() {
        return uuids;
    }

    /** Landing keys served from the fighter's id. */
    public static synchronized long landingKeys() {
        return landingKeys;
    }

    /** Landing keys that fell back to the identity hash (no usable id) — a nonzero count means unpinned landings. */
    public static synchronized long landingKeyFallbacks() {
        return landingKeyFallbacks;
    }

    /** The public no-arg {@code getId()} of {@code type} (declared or inherited), or null when it has none. */
    private static Method lookupGetId(Class<?> type) {
        try {
            return type.getMethod("getId");
        } catch (NoSuchMethodException e) {
            return null;
        }
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
