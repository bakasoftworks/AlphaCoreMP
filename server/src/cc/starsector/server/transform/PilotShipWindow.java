package cc.starsector.server.transform;

import cc.starsector.server.runtime.PilotControlNames;

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
 * single player. (the player-ship design: the window, the mirrored mark, the receipt; the holder
 * sits in the transform package so cc-premain.jar and cc-server.jar both pack it).
 *
 * <p>The pilot's held input rides beside the mirror, one slot per entry: the held LWJGL key codes (packed eight to
 * a long, as the pilot's frame carries them), the held mouse buttons and the pilot's own keys for the vanilla ship
 * controls. WHY here: a redirected key poll in a hull's own code must answer what THAT hull's pilot holds, and the
 * window already knows which entry it opened for ({@link #enter} records the matched entry per depth), so a read is
 * an index plus an eight-byte scan, never a second identity scan, and the state leaves with the entry when the mark
 * clears. The applied pilot frame writes it ({@link #pushPilotState}, sim thread, the root and its modules); the
 * redirected statics read it ({@link #keyDown}, {@link #buttonDown}). Bounds law: a key code outside 1..255 or a
 * button outside 0..7 answers false and never throws, as does every read outside a window, off the sim thread or
 * after a latch — an exception thrown out of a hull's advance kills the engine step.
 *
 *
 * <p>The seat's keymap rides beside the held input (the pilot's own binding of each of the {@link #CONTROL_BITS}
 * controls, packed in two longs as the HELLO sent it): a held control then answers the key or button the PILOT bound
 * it to, and a by-name control read ({@link #controlEntryOrMinusOne} + {@link #controlNameOrNull}) answers the
 * pilot's binding as the engine names it. A seat that sent no map answers exactly as before. WHY the holder links the
 * shared name table ({@link PilotControlNames}, the one class outside this package it touches): only a map-carrying
 * read reaches it, and every such read comes through the redirect target, which lives in the server classes beside
 * that table; the window pair and a map-less read never load it.
 */
public final class PilotShipWindow {

    /** Deepest nested window that still answers; deeper enters latch {@code overflow}. */
    public static final int MAX_DEPTH = 8;
    /** Marked hulls (seat hulls plus their station modules); one more latches {@code mirror_overflow}. */
    public static final int MIRROR_CAPACITY = 64;
    /** Control bits a pilot frame carries (the frame's control-name list); a wider map is cut here. */
    public static final int CONTROL_BITS = 16;
    /** Key codes packed per long (one byte each, ascending, zero pad). */
    private static final int KEY_SLOTS = 8;
    /** LWJGL key codes 0..255; 0 = no key and never answers. */
    private static final int KEY_CODES = 256;
    /** Mouse buttons a pilot frame carries (bit b = button b). */
    private static final int MOUSE_BUTTONS = 8;

    private static Thread simThread;
    private static int depth;
    private static final Object[] ANSWERS = new Object[MAX_DEPTH];
    /** Per open depth: the mirror entry {@link #enter} matched, -1 = none (an unmarked hull, off-thread, latched). */
    private static final int[] ENTRY = filled(MAX_DEPTH, -1);
    private static final Object[] HULLS = new Object[MIRROR_CAPACITY];
    private static final Object[] ROOTS = new Object[MIRROR_CAPACITY];
    private static final long[] PILOT_KEYS = new long[MIRROR_CAPACITY];
    private static final int[] PILOT_MOUSE = new int[MIRROR_CAPACITY];
    private static final int[] PILOT_CONTROLS = new int[MIRROR_CAPACITY];
    /** Per entry, per tenure: the control bits this hull's scripts asked about inside its window (zeroed on mark). */
    private static final int[] PILOT_ASKED = new int[MIRROR_CAPACITY];
    /**
     * The pilot's own binding of each control, packed as the seat's HELLO carries it: {@code PILOT_MAP_LO} = control
     * bytes 0..7, {@code PILOT_MAP_HI} = 8..15, byte i at bits {@code ((i & 7) << 3)..+7}; 0/0 = no map, the serve's
     * own {@link #CONTROL_BIT_FOR_KEY} answers. Per entry beside the held input, so it leaves with the entry.
     */
    private static final long[] PILOT_MAP_LO = new long[MIRROR_CAPACITY];
    private static final long[] PILOT_MAP_HI = new long[MIRROR_CAPACITY];
    /** The serve's own key for each control bit, inverted: key code -> control bit, -1 = none. Written at boot. */
    private static final int[] CONTROL_BIT_FOR_KEY = filled(KEY_CODES, -1);
    /** The engine-format name of each key code (null = unknown) and of each mouse button. Written at boot. */
    private static final String[] KEY_NAMES = new String[KEY_CODES];
    private static final String[] MOUSE_NAMES = new String[MOUSE_BUTTONS];
    private static int marked;
    private static volatile boolean latched;
    private static volatile String latchReason = "";
    private static long windows;
    private static long scoped;
    private static long leaks;
    private static long offThread;
    private static long overflows;
    private static int maxDepth;
    private static long keyPushes;
    private static long keyReads;
    private static long nameReads;
    private static long aiReads;
    private static long pushUnmarked;

    /**
     * The serve key-edge log: one {@code System.out} row per transition of the levels the serve answers a hull's own
     * key polls from (a control bit of the applied frame, a key code entering or leaving its eight slots), stamped
     * with the sim tick and seat {@link #noteApplied} stored for that frame. WHY: a hull mod that decides on a key
     * edge (a double tap) decides on the serve from these pushed levels and on a watching replica from the mark it
     * receives; counting the edges each side saw per bit tells which side judged a different input, and no other
     * per-frame record of the pushed levels exists. Laws: default OFF ({@code -Dcc.pilot.keyEdgeLog=true} arms it,
     * read once at class init), and OFF costs {@link #pushPilotState} one boolean test; ON prints at most
     * {@link #KEY_EDGE_ROW_CAP} rows per process, then one {@code key-edge ROW CAP reached} line and nothing more;
     * a row is written only past the push's own thread gate, so never off the bound sim thread; rows carry bounded
     * tokens only (ticks, seat numbers, bit indexes, key codes, hex levels). The census probes' sink laws (sysprop
     * arm, {@code System.out}, one cap line) are mirrored here rather than shared: this holder is packed into the
     * premain jar too and links nothing in the bridge package.
     *
     */
    public static final String KEY_EDGE_LOG_SYSPROP = "cc.pilot.keyEdgeLog";
    /** The key-edge log's arm as the JVM started (read once). */
    private static final boolean KEY_EDGE_LOG_ARMED =
            "true".equalsIgnoreCase(System.getProperty(KEY_EDGE_LOG_SYSPROP));
    /** Key-edge rows per process; the row that reaches it carries the one cap line. */
    public static final int KEY_EDGE_ROW_CAP = 4096;
    /**
     * The live gate {@link #pushPilotState} tests: the class-init arm, flipped only by {@link #keyEdgeLogForTests}
     * (a static final would fold, but no row could then drive the armed path in the suite's own JVM).
     */
    private static boolean keyEdgeLog = KEY_EDGE_LOG_ARMED;
    private static int keyEdgeRows;
    /**
     * The applied frame's sim tick (&gt;= 0) and seat (&gt;= 1, -1 = unknown) the next push's rows carry; 0/0 = never
     * noted.
     */
    private static long appliedTick;
    private static int appliedSeat;

    private PilotShipWindow() {
    }

    private static int[] filled(int n, int value) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, value);
        return a;
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

    /**
     * Window open for {@code ship}'s own advance: pushes the mirror's answer for it (null when unmarked) and the
     * matched entry's index (-1 when unmarked), which the pilot-state reads use.
     */
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
        int entry = -1;
        Thread st = simThread;
        if (st != null && st == Thread.currentThread()) {
            if (!latched && ship != null) {
                Object[] hulls = HULLS;
                int n = marked;
                for (int i = 0; i < n; i++) {
                    if (hulls[i] == ship) {
                        answer = ROOTS[i];
                        entry = i;
                        break;
                    }
                }
            }
        } else {
            offThread++;
        }
        ANSWERS[d - 1] = answer;
        ENTRY[d - 1] = entry;
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
            ENTRY[d - 1] = -1;
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
                if (ROOTS[i] != answer) {
                    clearPilotState(i); // another root's pilot state never answers for the new one
                }
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
        clearPilotState(n);
        marked = n + 1;
    }

    private static void clearPilotState(int i) {
        PILOT_KEYS[i] = 0L;
        PILOT_MOUSE[i] = 0;
        PILOT_CONTROLS[i] = 0;
        PILOT_ASKED[i] = 0;
        PILOT_MAP_LO[i] = 0L;
        PILOT_MAP_HI[i] = 0L;
    }

    /**
     * Mirror clear: removes every entry whose answer is {@code answer} (the root and its modules); the survivors
     * close up in order and carry their pilot state with them, and the freed tail is zeroed. A clear inside an open
     * window re-points each open depth's recorded entry to where it moved (a removed one to -1), so a read later in
     * that window still lands on its own hull's state.
     */
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
        int open = Math.min(depth, MAX_DEPTH);
        for (int i = 0; i < n; i++) {
            boolean drop = ROOTS[i] == answer;
            for (int k = 0; k < open; k++) {
                if (ENTRY[k] == i) {
                    ENTRY[k] = drop ? -1 : w; // w <= i: a re-pointed slot never matches a later i
                }
            }
            if (drop) {
                continue;
            }
            HULLS[w] = HULLS[i];
            ROOTS[w] = ROOTS[i];
            PILOT_KEYS[w] = PILOT_KEYS[i];
            PILOT_MOUSE[w] = PILOT_MOUSE[i];
            PILOT_CONTROLS[w] = PILOT_CONTROLS[i];
            PILOT_ASKED[w] = PILOT_ASKED[i];
            PILOT_MAP_LO[w] = PILOT_MAP_LO[i];
            PILOT_MAP_HI[w] = PILOT_MAP_HI[i];
            w++;
        }
        for (int i = w; i < n; i++) {
            HULLS[i] = null;
            ROOTS[i] = null;
            clearPilotState(i);
        }
        marked = w;
    }

    /**
     * The applied pilot frame's held input for {@code hull}: written into EVERY entry that answers the same root as
     * {@code hull}'s own entry (the root and its station modules), so module code hears its root's pilot, as it sees
     * its root as the player. Sim thread only, the {@link #mark} rule: a call off the bound thread is ignored and
     * counted ({@code off_thread}); a hull with no entry is ignored and counted ({@link #pushUnmarkedCount}). At most
     * {@link #MIRROR_CAPACITY} identity compares and five stores per entry; no allocation while the key-edge log
     * ({@link #KEY_EDGE_LOG_SYSPROP}) is OFF. {@code keyCodes} = up to
     * eight LWJGL codes, one per byte (ascending, zero pad, as the pilot frame packs them); {@code mouseButtons} bit b
     * = button b; {@code controlKeys} bit i = the pilot's own key for control bit i. This form carries no keymap:
     * it is {@link #pushPilotState(Object, long, int, int, long, long)} at 0/0, so the serve's own key for each
     * control answers.
     */
    public static void pushPilotState(Object hull, long keyCodes, int mouseButtons, int controlKeys) {
        pushPilotState(hull, keyCodes, mouseButtons, controlKeys, 0L, 0L);
    }

    /**
     * The applied pilot frame's held input PLUS the seat's packed HELLO keymap, written into every entry answering
     * {@code hull}'s root under the 4-arg form's rules ({@link #pushPilotState(Object, long, int, int)}). WHY the map
     * rides every push: it is the seat's, not the hull's, so a re-marked or compacted entry is never left holding a
     * stale map, and it costs two more stores per entry. {@code mapLo} = control bytes 0..7, {@code mapHi} = 8..15,
     * byte i at bits {@code ((i & 7) << 3)..+7}: 0 = unbound, 1..0xDF an LWJGL key code, 0xF0..0xF7 mouse button
     * 0..7; 0/0 = no map, the serve's own table answers. The bytes are taken as given (the handshake refuses an
     * unlawful one); the reads stay inside their own ranges whatever a byte holds. Armed, the key-edge log
     * ({@link #logKeyEdges}) names this push's transitions against the root's entry before the store.
     *
     */
    public static void pushPilotState(Object hull, long keyCodes, int mouseButtons, int controlKeys, long mapLo,
            long mapHi) {
        if (hull == null) {
            return;
        }
        Thread st = simThread;
        if (st != null && st != Thread.currentThread()) {
            offThread++;
            return;
        }
        int n = marked;
        Object root = null;
        for (int j = 0; j < n; j++) {
            if (HULLS[j] == hull) {
                root = ROOTS[j];
                break;
            }
        }
        if (root == null) {
            pushUnmarked++;
            return;
        }
        int mouse = mouseButtons & 0xFF;
        int controls = controlKeys & 0xFFFF;
        if (keyEdgeLog) {
            logKeyEdges(root, n, keyCodes, controls);
        }
        for (int i = 0; i < n; i++) {
            if (ROOTS[i] == root) {
                PILOT_KEYS[i] = keyCodes;
                PILOT_MOUSE[i] = mouse;
                PILOT_CONTROLS[i] = controls;
                PILOT_MAP_LO[i] = mapLo;
                PILOT_MAP_HI[i] = mapHi;
            }
        }
        keyPushes++;
    }

    /**
     * Stamps the applied pilot frame the next {@link #pushPilotState} belongs to: {@code simTick} (&gt;= 0, the serve's
     * tick the frame applies at) and {@code seat} (&gt;= 1, -1 = unknown), carried by the key-edge rows that push
     * prints. Two plain stores whatever the log's arm, so the stamp is true the moment a log arms; sim thread only
     * (the applier calls it right before its push).
     */
    public static void noteApplied(long simTick, int seat) {
        appliedTick = simTick;
        appliedSeat = seat;
    }

    /**
     * The armed key-edge log for one push: compares {@code keyCodes} / {@code controls} (already masked to 16 bits)
     * with what the FIRST entry answering {@code root} holds before the store (the root and its modules receive the
     * same levels, so one row set per root), and prints one row per changed control bit (ascending), then per key
     * code that entered the eight slots, then per code that left them (zero bytes are padding, never a code):
     * {@code pilotship key-edge tick=<t> seat=<s> bit=<i> <rise|fall> controls=0x<hex4>} and
     * {@code pilotship key-edge tick=<t> seat=<s> key=<code> <rise|fall> keys=0x<hex16>}, each carrying the NEW level.
     * An entry re-marked (its levels zeroed at the re-point) or compacted compares against whatever its slot holds,
     * so a stale slot yields at most one spurious edge per bit or code on the first push after it. Never throws; the
     * caller already passed the thread gate and found the root.
     */
    private static void logKeyEdges(Object root, int n, long keyCodes, int controls) {
        if (keyEdgeRows >= KEY_EDGE_ROW_CAP) {
            return;
        }
        int e = -1;
        for (int i = 0; i < n; i++) {
            if (ROOTS[i] == root) {
                e = i;
                break;
            }
        }
        if (e < 0) {
            return;
        }
        long oldKeys = PILOT_KEYS[e];
        int changed = PILOT_CONTROLS[e] ^ controls;
        if (changed == 0 && oldKeys == keyCodes) {
            return;
        }
        String head = "pilotship key-edge tick=" + appliedTick + " seat=" + appliedSeat;
        for (int b = 0; b < CONTROL_BITS && changed != 0; b++) {
            if (((changed >>> b) & 1) != 0) {
                keyEdgeRow(head + " bit=" + b + (((controls >>> b) & 1) != 0 ? " rise" : " fall") + " controls=0x"
                        + hex(controls, 4));
            }
        }
        if (oldKeys == keyCodes) {
            return;
        }
        for (int s = 0; s < KEY_SLOTS; s++) {
            int code = slotCode(keyCodes, s);
            if (code != 0 && !holdsBefore(keyCodes, s, code) && !holdsCode(oldKeys, code)) {
                keyEdgeRow(head + " key=" + code + " rise keys=0x" + hex(keyCodes, 16));
            }
        }
        for (int s = 0; s < KEY_SLOTS; s++) {
            int code = slotCode(oldKeys, s);
            if (code != 0 && !holdsBefore(oldKeys, s, code) && !holdsCode(keyCodes, code)) {
                keyEdgeRow(head + " key=" + code + " fall keys=0x" + hex(keyCodes, 16));
            }
        }
    }

    /** One counted key-edge row; the row that reaches {@link #KEY_EDGE_ROW_CAP} carries the one cap line. */
    private static void keyEdgeRow(String text) {
        if (keyEdgeRows >= KEY_EDGE_ROW_CAP) {
            return;
        }
        keyEdgeRows++;
        System.out.println(text);
        if (keyEdgeRows == KEY_EDGE_ROW_CAP) {
            System.out.println("key-edge ROW CAP reached (" + KEY_EDGE_ROW_CAP + "); further rows suppressed");
        }
    }

    private static int slotCode(long packed, int slot) {
        return (int) ((packed >>> (8 * slot)) & 0xFF);
    }

    /** Whether {@code code} sits in a slot below {@code slot} (a repeated code yields one row, not one per slot). */
    private static boolean holdsBefore(long packed, int slot, int code) {
        for (int s = 0; s < slot; s++) {
            if (slotCode(packed, s) == code) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsCode(long packed, int code) {
        return holdsBefore(packed, KEY_SLOTS, code);
    }

    /** {@code v}'s low {@code digits} nibbles as lowercase hex, zero-padded. */
    private static String hex(long v, int digits) {
        char[] c = new char[digits];
        long x = v;
        for (int d = digits - 1; d >= 0; d--) {
            c[d] = Character.forDigit((int) (x & 0xF), 16);
            x >>>= 4;
        }
        return new String(c);
    }

    /**
     * Test-only: sets the key-edge log's live arm (the class-init sysprop read cannot be flipped) and answers the prior
     * one so the row restores it; {@link #resetForTests} returns it to the class-init arm.
     */
    public static boolean keyEdgeLogForTests(boolean armed) {
        boolean prior = keyEdgeLog;
        keyEdgeLog = armed;
        return prior;
    }

    /**
     * Whether the open window's pilot holds LWJGL key {@code code}: one of the pilot's held key codes, or a control
     * whose bit the pilot holds on the pilot's own key, named by the key it is bound to — the pilot's own keymap when
     * the seat sent one (a held control answers ITS key, and the serve's key for that control answers nothing), else
     * the SERVE's own key for the control (so a script asking for the key of a vanilla control hears the pilot's
     * binding, whatever either side bound it to). A mouse-bound control never answers a key code. False outside a
     * window, off the sim thread, after a latch, for an unmarked hull and for any code outside 1..255 — never a
     * throw; at most sixteen compares, no allocation.
     */
    public static boolean keyDown(int code) {
        if (code < 1 || code >= KEY_CODES) {
            return false;
        }
        int e = pilotEntry();
        if (e < 0) {
            return false;
        }
        keyReads++;
        long lo = PILOT_MAP_LO[e];
        long hi = PILOT_MAP_HI[e];
        // a mouse byte (0xF0..) or an out-of-domain byte never names a key
        int asked = (lo | hi) == 0L ? (CONTROL_BIT_FOR_KEY[code] < 0 ? 0 : 1 << CONTROL_BIT_FOR_KEY[code])
                : code > PilotControlNames.KEY_MAX ? 0 : controlBitsFor(code, lo, hi);
        PILOT_ASKED[e] |= asked;
        long keys = PILOT_KEYS[e];
        for (int slot = 0; slot < KEY_SLOTS; slot++) {
            if ((int) ((keys >>> (slot << 3)) & 0xFFL) == code) {
                return true;
            }
        }
        return (PILOT_CONTROLS[e] & asked) != 0;
    }

    /**
     * The control bits whose keymap byte is {@code entry} (sixteen compares). WHY the reads record them: the held
     * controls go to other screens masked by what this hull's own scripts asked about, so a control no script polls
     * never leaves the serve.
     */
    private static int controlBitsFor(int entry, long lo, long hi) {
        int bits = 0;
        for (int i = 0; i < CONTROL_BITS; i++) {
            if (PilotControlNames.entry(lo, hi, i) == entry) {
                bits |= 1 << i;
            }
        }
        return bits;
    }

    /**
     * The control bits the windows of {@code hull}'s root asked about since its entries were marked (the OR over
     * every entry answering that root; 0 for an unmarked hull). Sim thread, the mark writer's per-frame read: at most
     * two passes of {@link #MIRROR_CAPACITY} identity compares, no allocation.
     */
    public static int askedControls(Object hull) {
        int n = marked;
        Object root = null;
        for (int j = 0; j < n && root == null; j++) {
            root = HULLS[j] == hull ? ROOTS[j] : null;
        }
        int asked = 0;
        for (int i = 0; root != null && i < n; i++) {
            asked |= ROOTS[i] == root ? PILOT_ASKED[i] : 0;
        }
        return asked;
    }

    /**
     * Whether the open window's pilot holds mouse button {@code b} (0..7): the pilot's held button bit, or — when the
     * seat sent a keymap — a held control the pilot bound to that button. False wherever {@link #keyDown} is.
     *
     */
    public static boolean buttonDown(int b) {
        if (b < 0 || b >= MOUSE_BUTTONS) {
            return false;
        }
        int e = pilotEntry();
        if (e < 0) {
            return false;
        }
        keyReads++;
        long lo = PILOT_MAP_LO[e];
        long hi = PILOT_MAP_HI[e];
        int asked = (lo | hi) == 0L ? 0 : controlBitsFor(PilotControlNames.MOUSE_BASE + b, lo, hi);
        PILOT_ASKED[e] |= asked;
        return ((PILOT_MOUSE[e] >>> b) & 1) != 0 || (PILOT_CONTROLS[e] & asked) != 0;
    }

    /**
     * The open window's mirror entry on the sim thread, else -1. The entry is re-checked against the window's answer:
     * a mark cleared inside the window (the compaction moves entries) answers -1 rather than another root's state.
     */
    private static int pilotEntry() {
        int d = depth;
        if (d < 1 || d > MAX_DEPTH || latched) {
            return -1;
        }
        Thread st = simThread;
        if (st == null || st != Thread.currentThread()) {
            return -1;
        }
        int e = ENTRY[d - 1];
        if (e < 0 || e >= marked || ROOTS[e] != ANSWERS[d - 1]) {
            return -1;
        }
        return e;
    }

    /**
     * Install the serve's own key for each control bit ({@code keyIndexPerControlBit[bit]} = an LWJGL key code, 0 =
     * unmapped), inverted into key -> bit. Called ONCE at serve boot, off-frame, after content init; a later call
     * replaces the whole map. Index 0 never maps, a code outside 1..255 is skipped, bits past {@link #CONTROL_BITS}
     * are cut, and two bits on one key keep the lower bit.
     */
    public static void installControlMap(int[] keyIndexPerControlBit) {
        int[] map = CONTROL_BIT_FOR_KEY;
        java.util.Arrays.fill(map, -1);
        if (keyIndexPerControlBit == null) {
            return;
        }
        int bits = Math.min(keyIndexPerControlBit.length, CONTROL_BITS);
        for (int bit = 0; bit < bits; bit++) {
            int key = keyIndexPerControlBit[bit];
            if (key >= 1 && key < KEY_CODES && map[key] < 0) {
                map[key] = bit;
            }
        }
    }

    /**
     * Install the engine-format name of every key code ({@code keyNames[code]}, null = unknown) and of the mouse
     * buttons ({@code mouseNames[b]}), the by-name control read's answer table ({@link #controlNameOrNull}). Called
     * ONCE at serve boot, off-frame, beside {@link #installControlMap}; a later call replaces both tables. A null array
     * clears its table; entries past 256 key codes or 8 buttons are cut. WHY a table installed at boot: the engine's
     * key names come from its input library, which the holder never touches on a frame.
     *
     */
    public static void installControlNames(String[] keyNames, String[] mouseNames) {
        java.util.Arrays.fill(KEY_NAMES, null);
        java.util.Arrays.fill(MOUSE_NAMES, null);
        if (keyNames != null) {
            System.arraycopy(keyNames, 0, KEY_NAMES, 0, Math.min(keyNames.length, KEY_CODES));
        }
        if (mouseNames != null) {
            System.arraycopy(mouseNames, 0, MOUSE_NAMES, 0, Math.min(mouseNames.length, MOUSE_BUTTONS));
        }
    }

    /**
     * The by-name control read's lookup: -1 outside a window (and off the sim thread, after a latch, for an unmarked
     * hull), when the window's hull's pilot sent no map (0/0) or when {@code name} is not one of the
     * {@link #CONTROL_BITS} control names a pilot frame carries; else that control's keymap byte as the seat sent it
     * (0 = unbound, 1..0xDF a key code, 0xF0..0xF7 a mouse button). -1 = the caller answers with the engine's own
     * string, so the window and map checks come FIRST and a read outside a window costs no name compare. Never
     * throws (a null name is unknown); counts {@code name_reads} when it answers from a map.
     *
     */
    public static int controlEntryOrMinusOne(String name) {
        int e = pilotEntry();
        if (e < 0) {
            return -1;
        }
        long lo = PILOT_MAP_LO[e];
        long hi = PILOT_MAP_HI[e];
        if ((lo | hi) == 0L) {
            return -1;
        }
        int i = PilotControlNames.controlIndexOrMinusOne(name);
        if (i < 0 || i >= CONTROL_BITS) {
            return -1;
        }
        nameReads++;
        return PilotControlNames.entry(lo, hi, i);
    }

    /**
     * The engine-format name of a keymap byte from the installed tables ({@link #installControlNames}): a key code
     * 1..0xDF reads the key table, 0xF0..0xF7 the mouse table; null for 0, any other byte, a code the table does not
     * name, or before the tables are installed. Never throws.
     */
    public static String controlNameOrNull(int entry) {
        if (entry >= 1 && entry <= PilotControlNames.KEY_MAX) {
            return KEY_NAMES[entry];
        }
        if (entry >= PilotControlNames.MOUSE_BASE && entry < PilotControlNames.MOUSE_BASE + MOUSE_BUTTONS) {
            return MOUSE_NAMES[entry - PilotControlNames.MOUSE_BASE];
        }
        return null;
    }

    /** Test-only: a copy of the installed key -> control bit map (-1 = none). */
    public static int[] controlMapForTests() {
        return CONTROL_BIT_FOR_KEY.clone();
    }

    /** Pushes ignored because the hull had no mirror entry (a frame applied before its mark landed, or after it cleared). */
    public static long pushUnmarkedCount() {
        return pushUnmarked;
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
        java.util.Arrays.fill(ENTRY, -1);
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

    /**
     * Counts one pilot-state read the redirect target answered for the window's pilot (an AI read answered null for
     * the window's root, the autopilot read answered true). Called on the sim thread only, inside an open window: the
     * twins substitute an answer nowhere else.
     */
    public static void countAiRead() {
        aiReads++;
    }

    /**
     * One bounded off-frame row per match: {@code pilotship windows=.. scoped=.. max_depth=.. leaks=.. off_thread=..
     * overflows=.. [latched=..] name_reads=.. key_pushes=.. key_reads=.. ai_reads=..} — {@code name_reads} = by-name
     * control reads answered from a pilot's keymap inside its window, {@code key_pushes} = applied pilot frames written
     * into an entry, {@code key_reads} = redirected key / button reads answered from a pilot's entry inside its window,
     * {@code ai_reads} = pilot-state reads answered for the window's pilot ({@link #countAiRead}). The two key counters
     * stay adjacent and in order; {@code ai_reads} closes the row (readers pin the tail).
     */
    public static String metersRow() {
        return "pilotship windows=" + windows + " scoped=" + scoped + " max_depth=" + maxDepth + " leaks=" + leaks
                + " off_thread=" + offThread + " overflows=" + overflows + (latched ? " latched=" + latchReason : "")
                + " name_reads=" + nameReads + " key_pushes=" + keyPushes + " key_reads=" + keyReads
                + " ai_reads=" + aiReads;
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
        java.util.Arrays.fill(ENTRY, -1);
        java.util.Arrays.fill(HULLS, null);
        java.util.Arrays.fill(ROOTS, null);
        java.util.Arrays.fill(PILOT_KEYS, 0L);
        java.util.Arrays.fill(PILOT_MOUSE, 0);
        java.util.Arrays.fill(PILOT_CONTROLS, 0);
        java.util.Arrays.fill(PILOT_ASKED, 0);
        java.util.Arrays.fill(PILOT_MAP_LO, 0L);
        java.util.Arrays.fill(PILOT_MAP_HI, 0L);
        java.util.Arrays.fill(CONTROL_BIT_FOR_KEY, -1);
        java.util.Arrays.fill(KEY_NAMES, null);
        java.util.Arrays.fill(MOUSE_NAMES, null);
        marked = 0;
        latched = false;
        latchReason = "";
        windows = 0;
        scoped = 0;
        leaks = 0;
        offThread = 0;
        overflows = 0;
        maxDepth = 0;
        keyPushes = 0;
        keyReads = 0;
        nameReads = 0;
        aiReads = 0;
        pushUnmarked = 0;
        keyEdgeLog = KEY_EDGE_LOG_ARMED;
        keyEdgeRows = 0;
        appliedTick = 0L;
        appliedSeat = 0;
    }
}
