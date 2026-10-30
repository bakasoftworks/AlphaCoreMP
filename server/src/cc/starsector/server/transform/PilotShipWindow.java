package cc.starsector.server.transform;

/**
 * The player-ship window: the ONE holder the serve's ship-advance wrap (HOOK-0054) and the redirected
 * {@code getPlayerShip()} call sites (HOOK-0055) meet in. WHY it exists: the engine has one "player's ship"
 * and the serve has N human pilots; while a human-flown hull's OWN per-frame code runs (its hullmods,
 * listeners, ship-system and weapon scripts, all inside {@code Ship.advance(F)V}), a mod's redirected
 * {@code getPlayerShip()} answers THAT hull, as the pilot's own game does; everywhere else — the engine's own
 * readers, plugin loops, other threads, an unmarked hull — the answer is the engine's real one. Statics on
 * the system loader: one copy whatever loader defines the rewritten class; no allocation on a steady frame
 * (fixed arrays, identity scans), so the enter / exit pair costs a few compares per ship per frame.
 *
 * <p>Laws: {@link #enter} ALWAYS increments and {@link #exit} ALWAYS decrements the depth (nesting is real: a
 * deploy advances each spawned ship ten times from inside a system script); {@link #exit} never throws; the
 * answer is only ever read on the bound sim thread; a leak ({@link #checkBalanced} finds depth != 0 after a
 * step), a depth over {@link #MAX_DEPTH} or a mirror over {@link #MIRROR_CAPACITY} LATCHES the feature off for
 * the match and writes the receipt {@code latched:<reason>} — fail-safe towards today's behaviour. The mirror
 * (hull -> answer) is written by the pilot-mark writer at its re-point and clear edges only, never per frame;
 * a station module is marked with its root as the answer, so module code sees the root as the player, as in
 * single player. Main-written (the player-ship design: the window, the mirrored mark, the receipt; the holder
 * sits in the transform package so cc-premain.jar and cc-server.jar both pack it).
 */
public final class PilotShipWindow {

    /** Deepest nested window that still answers; deeper enters latch {@code overflow}. */
    public static final int MAX_DEPTH = 8;
    /** Marked hulls (seat hulls plus their station modules); one more latches {@code mirror_overflow}. */
    public static final int MIRROR_CAPACITY = 64;

    private static Thread simThread;
    private static int depth;
    private static final Object[] ANSWERS = new Object[MAX_DEPTH];
    private static final Object[] HULLS = new Object[MIRROR_CAPACITY];
    private static final Object[] ROOTS = new Object[MIRROR_CAPACITY];
    private static int marked;
    private static volatile boolean latched;
    private static volatile String latchReason = "";
    private static long windows;
    private static long scoped;
    private static long leaks;
    private static long offThread;
    private static long overflows;
    private static int maxDepth;

    private PilotShipWindow() {
    }

    /** Bind the sim thread once (the serve's step thread); later calls are ignored. */
    public static void bindSimThread(Thread t) {
        if (simThread == null && t != null) {
            simThread = t;
        }
    }

    public static boolean threadBound() {
        return simThread != null;
    }

    /** Window open for {@code ship}'s own advance: pushes the mirror's answer for it (null when unmarked). */
    public static void enter(Object ship) {
        int d = ++depth;
        windows++;
        if (d > maxDepth) {
            maxDepth = d;
        }
        if (d > MAX_DEPTH) {
            overflows++;
            if (!latched) {
                latch("overflow");
            }
            return;
        }
        Object answer = null;
        Thread st = simThread;
        if (st != null && st == Thread.currentThread()) {
            if (!latched && ship != null) {
                Object[] hulls = HULLS;
                int n = marked;
                for (int i = 0; i < n; i++) {
                    if (hulls[i] == ship) {
                        answer = ROOTS[i];
                        break;
                    }
                }
            }
        } else {
            offThread++;
        }
        ANSWERS[d - 1] = answer;
        if (answer != null) {
            scoped++;
        }
    }

    /** Window closed: pops; a call with no open window is ignored. Never throws. */
    public static void exit() {
        int d = depth;
        if (d <= 0) {
            return;
        }
        if (d <= MAX_DEPTH) {
            ANSWERS[d - 1] = null;
        }
        depth = d - 1;
    }

    /** The open window's answer on the sim thread, else null (off-thread, no window, latched, unmarked). */
    public static Object answerOrNull() {
        int d = depth;
        if (d < 1 || d > MAX_DEPTH || latched) {
            return null;
        }
        Thread st = simThread;
        if (st == null || st != Thread.currentThread()) {
            return null;
        }
        return ANSWERS[d - 1];
    }

    /** Mirror write: {@code hull} answers {@code answer} (itself, or its root for a station module). */
    public static void mark(Object hull, Object answer) {
        if (hull == null || answer == null) {
            return;
        }
        Thread st = simThread;
        if (st != null && st != Thread.currentThread()) {
            offThread++;
            return;
        }
        int n = marked;
        for (int i = 0; i < n; i++) {
            if (HULLS[i] == hull) {
                ROOTS[i] = answer;
                return;
            }
        }
        if (n >= MIRROR_CAPACITY) {
            latch("mirror_overflow");
            return;
        }
        HULLS[n] = hull;
        ROOTS[n] = answer;
        marked = n + 1;
    }

    /** Mirror clear: removes every entry whose answer is {@code answer} (the root and its modules). */
    public static void unmarkAnswer(Object answer) {
        if (answer == null) {
            return;
        }
        Thread st = simThread;
        if (st != null && st != Thread.currentThread()) {
            offThread++;
            return;
        }
        int n = marked;
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (ROOTS[i] == answer) {
                continue;
            }
            HULLS[w] = HULLS[i];
            ROOTS[w] = ROOTS[i];
            w++;
        }
        for (int i = w; i < n; i++) {
            HULLS[i] = null;
            ROOTS[i] = null;
        }
        marked = w;
    }

    /**
     * The step guard, called after every engine step: true when every window closed; else resets the depth,
     * counts a leak, latches the feature off for the match (receipt {@code latched:leak}) and returns false.
     */
    public static boolean checkBalanced() {
        if (depth == 0) {
            return true;
        }
        depth = 0;
        java.util.Arrays.fill(ANSWERS, null);
        leaks++;
        latch("leak");
        return false;
    }

    public static boolean latched() {
        return latched;
    }

    public static int depth() {
        return depth;
    }

    public static int markedCount() {
        return marked;
    }

    /** One bounded off-frame row per match: {@code pilotship windows=.. scoped=.. max_depth=.. leaks=.. off_thread=..}. */
    public static String metersRow() {
        return "pilotship windows=" + windows + " scoped=" + scoped + " max_depth=" + maxDepth + " leaks=" + leaks
                + " off_thread=" + offThread + " overflows=" + overflows + (latched ? " latched=" + latchReason : "");
    }

    private static void latch(String reason) {
        latched = true;
        latchReason = reason;
        try {
            // The receipt word is overwritten only while the feature could be live. The serve mirrors the mark at every
            // re-point whether or not the premain armed anything (it cannot know), so with the host row OFF an overflow
            // here must not turn "off" (or a "refused:…") into "latched:…" — the ops MATCH line and ReplayBoot's class
            // compare read that word (the serve-wiring lane's finding at the merge). A JVM with no receipt at all (the suite's holder rows)
            // still records the latch.
            String cur = System.getProperty(TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP);
            boolean dormant = cur != null && (cur.equals(TransformRegistry.PILOT_SHIP_RECEIPT_OFF)
                    || cur.startsWith(TransformRegistry.PILOT_SHIP_RECEIPT_REFUSED_PREFIX));
            if (!dormant) {
                System.setProperty(TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP,
                        TransformRegistry.PILOT_SHIP_RECEIPT_LATCHED_PREFIX + reason);
            }
        } catch (RuntimeException ignored) {
            // a receipt write must never reach the sim thread as a throw; the latch itself already holds
        }
    }

    /** Test-only: every static back to the JVM-start state, the bound thread included. */
    public static void resetForTests() {
        simThread = null;
        depth = 0;
        java.util.Arrays.fill(ANSWERS, null);
        java.util.Arrays.fill(HULLS, null);
        java.util.Arrays.fill(ROOTS, null);
        marked = 0;
        latched = false;
        latchReason = "";
        windows = 0;
        scoped = 0;
        leaks = 0;
        offThread = 0;
        overflows = 0;
        maxDepth = 0;
    }
}
