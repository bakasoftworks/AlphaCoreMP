package cc.starsector.client.replica;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * The replica pilot window: the client's holder that the rewritten mod classes meet in (through
 * {@link ReplicaPilotCall}). WHY it exists: another player's ship is a replica on this screen, and its own mod scripts
 * (hullmod, ship system, weapon and beam per-frame code) would run their AI branch here while the serve runs them as
 * the pilot's. The client's load-time agent wraps each such per-ship entry method so this holder knows, for the
 * length of that call, whose script is running; while a window is open for a hull a human pilots, the twins answer
 * what that pilot's own game would (the pilot's held controls through this viewer's bindings, the pilot's ship as
 * the player ship, no AI on the flown hull, the viewer's screen untouched), and everywhere else they make the real
 * calls.
 *
 * <p>The stack: {@link #enter} ALWAYS pushes and {@link #exit} ALWAYS pops on the bound render thread (nesting is
 * real: one entry may call another ship's entry), {@link #MAX_DEPTH} deep; a deeper enter latches
 * {@code latched:overflow}. Thread-bound: the first enter binds the render thread; an enter, exit or twin read from
 * any other thread never touches the stack (the pair skips together, so it cannot unbalance it), answers the real
 * call and counts {@code thread_miss}. The per-frame belt ({@link #assertDepthZeroEachFrame}, called from the kit
 * plugin's advance, outside every window) finds a leaked window (an enter whose exit never ran) and latches
 * {@code latched:leak}. A latch of any kind, including the agent's own {@code latched:} receipt, is sticky OFF for
 * the process: every twin answers the real call from then on, because a class may still load rewritten after the
 * latch and nothing it asks may then be answered from a half-trusted state.
 *
 * <p>The index: at most {@link #INDEX_CAPACITY} ships (the serve's own mirror capacity), each with the SAME
 * {@code int[]} mark the remote pilot-mark writer publishes on the replica and overwrites in place every frame, never
 * a copy, so the twins read the pilot's held controls ({@code mark[4]}) live. One more ship latches
 * {@code latched:overflow}. Identity scans only; no allocation on any enter, exit or twin read.
 *
 * <p>The predicate ({@link #inWindowAsPilot}): the agent receipt {@code cc.pilot.replica} reads {@code applied:},
 * the ship (or, for a station module, its root) is indexed, its mark is at least {@link #MARK_LENGTH} long with seat
 * at least 1, role {@link #ROLE_REPLICA} and flag bit {@link #FLAG_REPLICA_FOLLOW}, and it is neither the bound own
 * ship nor the engine's player ship. WHY flag bit 2 and not the seat: a seat is set on every human-flown hull, also
 * while the serve's pilot-ship change is off, refused, latched or unarmed for it; the serve then runs that hull's AI
 * branch, so a client keyed on the seat alone would run the player branch and diverge. The serve sets bit 2 only
 * while it runs the hull's scripts as the pilot's. The own ship is excluded because this machine's own game already
 * answers it with the real keys.
 *
 * <p>Cost: outside every window a twin pays one compare ({@code depth == 0}); an enter on an unindexed ship pays at
 * most one parent read and two identity scans; the receipt and the engine's player ship are read only for an
 * indexed hull. Meters are plain counters on the render thread, read off-frame by {@link #metersRow}.
 *
 *
 */
public final class ReplicaPilotWindow {

    /** Deepest nested window that still answers; a deeper enter latches {@code overflow}. */
    public static final int MAX_DEPTH = 8;
    /** Indexed ships (piloted replicas); one more latches {@code overflow}. */
    public static final int INDEX_CAPACITY = 64;
    /**
     * The agent's receipt (the same spelling the client agent writes); the names live in the game-free
     * {@link ReplicaReceipt} so the HELLO side reads them without linking this engine-typed class.
     */
    public static final String RECEIPT_SYSPROP = ReplicaReceipt.SYSPROP;
    public static final String RECEIPT_APPLIED = ReplicaReceipt.APPLIED;
    static final String RECEIPT_LATCHED = "latched:";
    /** Mark slots: seat (1..255 for a human pilot, 0 = none), role, flags (u8), held controls (u16). */
    static final int MARK_SEAT = 0;
    static final int MARK_ROLE = 2;
    static final int MARK_FLAGS = 3;
    static final int MARK_CONTROLS = 4;
    static final int MARK_LENGTH = 5;
    /** The mark role of a client replica of the serve's mark. */
    public static final int ROLE_REPLICA = 1;
    /** Flag bit 2: the serve runs this hull's scripts as its pilot's. */
    public static final int FLAG_REPLICA_FOLLOW = 0x04;

    private static Thread renderThread;
    private static int depth;
    /** Per open depth: the window's root ship and its live mark; both null = the real calls answer. */
    private static final Object[] ROOT_AT = new Object[MAX_DEPTH];
    private static final int[][] MARK_AT = new int[MAX_DEPTH][];
    private static final Object[] SHIPS = new Object[INDEX_CAPACITY];
    private static final int[][] MARKS = new int[INDEX_CAPACITY][];
    private static int indexed;
    private static volatile Object ownShip;
    private static volatile boolean off;
    private static volatile String latchReason = "none";

    private static long enters;
    private static long exits;
    private static int maxDepth;
    private static long inWindowCalls;
    private static long keyReads;
    private static long screenDrops;
    private static long aiReads;
    private static long errors;
    private static final AtomicLong THREAD_MISS = new AtomicLong();

    private ReplicaPilotWindow() {
    }

    /**
     * Window open for the entry whose ship-bearing argument is {@code param}: a ship itself, a stats object's entity
     * when it is a ship, a weapon's ship, a beam's source; anything else opens a window that answers the real calls.
     */
    static void enter(Object param) {
        int slot = push();
        if (slot >= 0) {
            open(slot, param, false);
        }
    }

    /**
     * Window open for a dealt-damage entry: a projectile's or a beam's source is the dealer; anything else (an arc,
     * null) INHERITS the enclosing window. WHY inherit and never null: an underivable dealer still runs inside the
     * dealing entry's own call, and a null window there would hand this viewer's real keys to the replica's own
     * dealt modifier.
     */
    static void enterDealt(Object param) {
        int slot = push();
        if (slot < 0) {
            return;
        }
        if (param instanceof DamagingProjectileAPI || param instanceof BeamAPI) {
            open(slot, param, true);
        } else if (slot > 0 && !off) {
            ROOT_AT[slot] = ROOT_AT[slot - 1];
            MARK_AT[slot] = MARK_AT[slot - 1];
        }
    }

    /** Window closed: pops on the bound thread; never throws (the wrap's exit sits inside its own catch range). */
    static void exit() {
        if (Thread.currentThread() != renderThread) {
            THREAD_MISS.incrementAndGet();
            return;
        }
        exits++;
        int d = depth;
        if (d <= 0) {
            return;
        }
        if (d <= MAX_DEPTH) {
            ROOT_AT[d - 1] = null;
            MARK_AT[d - 1] = null;
        }
        depth = d - 1;
    }

    /** One push on the bound thread: the new slot, or -1 (another thread, or past the deepest slot). */
    private static int push() {
        Thread t = Thread.currentThread();
        Thread bound = renderThread;
        if (bound == null) {
            renderThread = t;
        } else if (bound != t) {
            THREAD_MISS.incrementAndGet();
            return -1;
        }
        int d = ++depth;
        enters++;
        if (d > maxDepth) {
            maxDepth = d;
        }
        if (d > MAX_DEPTH) {
            latch("overflow");
            return -1;
        }
        ROOT_AT[d - 1] = null;
        MARK_AT[d - 1] = null;
        return d - 1;
    }

    /** Fills {@code slot} when the derived ship is in a window as its pilot's; a throw counts and leaves it null. */
    private static void open(int slot, Object param, boolean dealt) {
        if (indexed == 0 || off) {
            return;
        }
        try {
            ShipAPI ship = dealt ? dealerOf(param) : shipOf(param);
            if (ship == null) {
                return;
            }
            ShipAPI parent = ship.getParentStation();
            ShipAPI root = parent != null ? parent : ship;
            int e = pilotEntry(root, ship);
            if (e >= 0) {
                ROOT_AT[slot] = root;
                MARK_AT[slot] = MARKS[e];
            }
        } catch (RuntimeException unreadable) {
            errors++; // the window answers the real calls; metersRow carries the count
            ROOT_AT[slot] = null;
            MARK_AT[slot] = null;
        }
    }

    private static ShipAPI shipOf(Object param) {
        if (param instanceof ShipAPI) {
            return (ShipAPI) param;
        }
        if (param instanceof MutableShipStatsAPI) {
            CombatEntityAPI e = ((MutableShipStatsAPI) param).getEntity();
            return e instanceof ShipAPI ? (ShipAPI) e : null;
        }
        if (param instanceof WeaponAPI) {
            return ((WeaponAPI) param).getShip();
        }
        if (param instanceof BeamAPI) {
            return ((BeamAPI) param).getSource();
        }
        return null;
    }

    private static ShipAPI dealerOf(Object param) {
        if (param instanceof DamagingProjectileAPI) {
            return ((DamagingProjectileAPI) param).getSource();
        }
        return param instanceof BeamAPI ? ((BeamAPI) param).getSource() : null;
    }

    /**
     * The predicate as a query: whether a window opened for {@code ship} now would answer as its pilot's. For the
     * kit's own diagnostics; the enter path evaluates the same predicate.
     */
    public static boolean inWindowAsPilot(ShipAPI ship) {
        if (ship == null || indexed == 0 || off || Thread.currentThread() != renderThread) {
            return false;
        }
        try {
            ShipAPI parent = ship.getParentStation();
            return pilotEntry(parent != null ? parent : ship, ship) >= 0;
        } catch (RuntimeException unreadable) {
            errors++;
            return false;
        }
    }

    /** The index entry answering for {@code root} (else {@code ship} itself) when the predicate holds; else -1. */
    private static int pilotEntry(ShipAPI root, ShipAPI ship) {
        int e = find(root);
        if (e < 0 && root != ship) {
            e = find(ship);
        }
        if (e < 0 || !replicaFollowMark(MARKS[e])) {
            return -1;
        }
        Object own = ownShip;
        if (own != null && (own == root || own == ship)) {
            return -1;
        }
        if (!receiptApplied()) {
            return -1;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        ShipAPI player = engine != null ? engine.getPlayerShip() : null;
        if (player != null && (player == root || player == ship)) {
            return -1;
        }
        return e;
    }

    private static int find(Object ship) {
        Object[] ships = SHIPS;
        int n = indexed;
        for (int i = 0; i < n; i++) {
            if (ships[i] == ship) {
                return i;
            }
        }
        return -1;
    }

    private static boolean replicaFollowMark(int[] m) {
        return m != null && m.length >= MARK_LENGTH && m[MARK_SEAT] >= 1 && m[MARK_ROLE] == ROLE_REPLICA
                && (m[MARK_FLAGS] & FLAG_REPLICA_FOLLOW) != 0;
    }

    /** The agent's receipt reads {@code applied:}; a {@code latched:} receipt latches this holder for the process. */
    private static boolean receiptApplied() {
        String r = System.getProperty(RECEIPT_SYSPROP);
        if (r == null) {
            return false;
        }
        if (r.startsWith(RECEIPT_LATCHED)) {
            latch("receipt");
            return false;
        }
        return r.startsWith(RECEIPT_APPLIED);
    }

    /** Sticky OFF for the process; writes the receipt unless the agent already latched it. Never throws. */
    private static void latch(String reason) {
        if (!off) {
            off = true;
            latchReason = reason;
            try {
                String cur = System.getProperty(RECEIPT_SYSPROP);
                if (!"receipt".equals(reason) && (cur == null || !cur.startsWith(RECEIPT_LATCHED))) {
                    System.setProperty(RECEIPT_SYSPROP, RECEIPT_LATCHED + reason);
                }
            } catch (RuntimeException unwritable) {
                errors++; // the holder is OFF either way; metersRow names the latch
            }
        }
        Arrays.fill(ROOT_AT, null);
        Arrays.fill(MARK_AT, null);
    }

    /**
     * The open pilot window's stack slot on the bound thread, else -1. Outside every window this is one compare; a
     * slot whose mark lost its seat or bit 2 since the enter answers -1 (the mark writer rewrites both in place).
     */
    static int pilotSlot() {
        int d = depth;
        if (d == 0) {
            return -1;
        }
        return pilotSlotInside(d);
    }

    private static int pilotSlotInside(int d) {
        if (d > MAX_DEPTH || d < 0) {
            return -1;
        }
        if (Thread.currentThread() != renderThread) {
            THREAD_MISS.incrementAndGet();
            return -1;
        }
        int[] m = MARK_AT[d - 1];
        if (m == null || m[MARK_SEAT] < 1 || (m[MARK_FLAGS] & FLAG_REPLICA_FOLLOW) == 0) {
            return -1;
        }
        inWindowCalls++;
        return d - 1;
    }

    /** The pilot's held controls (u16, bit i = control i) of an open slot, read live off the published mark. */
    static int controlsAt(int slot) {
        int[] m = MARK_AT[slot];
        return m == null ? 0 : m[MARK_CONTROLS] & 0xFFFF;
    }

    /** The window's root ship (the hull the pilot flies; a module's station root) of an open slot. */
    static Object rootAt(int slot) {
        return ROOT_AT[slot];
    }

    static void countKeyRead() {
        keyReads++;
    }

    static void countScreenDrop() {
        screenDrops++;
    }

    static void countAiRead() {
        aiReads++;
    }

    static void countError() {
        errors++;
    }

    /**
     * The mark writer's tenure edge: {@code ship} is a piloted replica whose mark is {@code mark} (the array it
     * rewrites in place). A republish replaces the array. One past {@link #INDEX_CAPACITY} latches.
     */
    public static void markPublished(ShipAPI ship, int[] mark) {
        if (ship == null || mark == null || foreignThread()) {
            return;
        }
        int e = find(ship);
        if (e >= 0) {
            MARKS[e] = mark;
            return;
        }
        int n = indexed;
        if (n >= INDEX_CAPACITY) {
            latch("overflow");
            return;
        }
        SHIPS[n] = ship;
        MARKS[n] = mark;
        indexed = n + 1;
    }

    /** The mark writer's clear edge: {@code ship} leaves the index (the last entry fills its slot). */
    public static void markRemoved(ShipAPI ship) {
        if (ship == null || foreignThread()) {
            return;
        }
        int e = find(ship);
        if (e < 0) {
            return;
        }
        int last = indexed - 1;
        SHIPS[e] = SHIPS[last];
        MARKS[e] = MARKS[last];
        SHIPS[last] = null;
        MARKS[last] = null;
        indexed = last;
    }

    /** This machine's own ship: never answered as a replica pilot's. */
    public static void bindOwn(ShipAPI ship) {
        ownShip = ship;
    }

    public static void clearOwn() {
        ownShip = null;
    }

    private static boolean foreignThread() {
        Thread bound = renderThread;
        if (bound != null && bound != Thread.currentThread()) {
            THREAD_MISS.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * The per-frame belt, called outside every window: depth must be 0. A non-zero depth is a leaked window (an
     * enter whose exit never ran, or an enter that threw past its own push); it latches {@code leak} and zeroes the
     * stack. Returns whether the stack was balanced.
     */
    public static boolean assertDepthZeroEachFrame() {
        if (foreignThread()) {
            return true;
        }
        if (depth == 0) {
            return true;
        }
        depth = 0;
        latch("leak");
        return false;
    }

    /**
     * Combat start: an empty stack, index and own ship, zeroed meters, and the viewer's bindings re-read at the next
     * in-window read (a rebind between matches is honoured). A latch stays: it is sticky for the process.
     */
    public static void clearForMatch() {
        if (foreignThread()) {
            return;
        }
        depth = 0;
        Arrays.fill(ROOT_AT, null);
        Arrays.fill(MARK_AT, null);
        Arrays.fill(SHIPS, null);
        Arrays.fill(MARKS, null);
        indexed = 0;
        ownShip = null;
        enters = 0;
        exits = 0;
        maxDepth = 0;
        inWindowCalls = 0;
        keyReads = 0;
        screenDrops = 0;
        aiReads = 0;
        errors = 0;
        THREAD_MISS.set(0);
        ReplicaPilotCall.forgetBindings();
    }

    /** Whether a latch turned the holder off for the process. */
    public static boolean latched() {
        return off;
    }

    /** One meter line, read off-frame at match end (racy reads of plain counters are acceptable for a meter). */
    public static String metersRow() {
        return "replica_window enters=" + enters + " exits=" + exits + " max_depth=" + maxDepth
                + " in_window_calls=" + inWindowCalls + " key_reads=" + keyReads + " screen_drops=" + screenDrops
                + " ai_reads=" + aiReads + " latched=" + latchReason + " thread_miss=" + THREAD_MISS.get()
                + " errors=" + errors + " receipt=" + receiptToken() + " indexed=" + indexed; // indexed= stays LAST: the smoke parses the tail
    }

    /**
     * The agent receipt as ONE bounded kv token for a debrief row: the kit's game runs detached on javaw, so the
     * agent's per-class stdout rows never reach a log, and the receipt system property is the only in-process trace
     * of what the pilot-replica pass did ({@code applied:<k> refused:<r>}, {@code latched:<reason>},
     * {@code disarmed:<reason>}, {@code off}, {@code armed}; {@code absent} when unset). Spaces become commas so the
     * kv reader keeps it as one token; anything outside a letter, digit, {@code :}, {@code ,}, {@code _}, {@code .},
     * {@code -} becomes {@code _}; at most 48 characters. Never throws.
     */
    public static String receiptToken() {
        String r;
        try {
            r = System.getProperty(RECEIPT_SYSPROP);
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
        if (r == null || r.isEmpty()) return "absent";
        StringBuilder sb = new StringBuilder(Math.min(r.length(), 48));
        for (int i = 0; i < r.length() && sb.length() < 48; i++) {
            char c = r.charAt(i);
            if (c == ' ') sb.append(',');
            else if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == ':' || c == ',' || c == '_' || c == '.' || c == '-') sb.append(c);
            else sb.append('_');
        }
        return sb.toString();
    }
}
