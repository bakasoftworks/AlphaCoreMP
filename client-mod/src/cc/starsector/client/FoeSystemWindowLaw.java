package cc.starsector.client;

/**
 * Foe replica SYSTEM-WINDOW law: one APPLY press per wire system window, never a re-press on the
 * tail.
 *
 * <p>WHY: {@code FoeStateLaneCoordinator.maintainFoeSystemState} pressed {@code useSystem()}
 * whenever {@code serverOpen && !localActive && canBeActivated()} on a fresh frame — a LEVEL test
 * with no per-window memory. A charge-based / short system whose LOCAL window ended before the
 * lagged wire OFF edge read "open, idle, can activate" again and was pressed a SECOND time (a
 * phantom second activation with its sound/FX/stat mods; only spawn families were gated, by
 * {@code SpawnSystemGate}) — the {@link FoeVentWindowLaw} vent-restart shape on the system axis.
 * The law: once a press TOOK (or the system was already seen active) inside an open wire window,
 * a later idle frame under the same window is the tail and is suppressed (metered), never
 * re-pressed; the wire CLOSE ({@code serverOpen} FALSE or null) clears the memory so the next
 * window presses fresh. A press the engine refused (never read active after) leaves the memory
 * clear so the press retries next fresh frame, exactly as before.
 *
 * <p>Client-side on purpose (zero wire; the window is still authority); adds no latency (the
 * press edge is unchanged); the agree/disagree meter is untouched so the tail stays visible. The
 * CANCEL press is not this law. Caller: {@code maintainFoeSystemState}. Smoke:
 * {@code FoeSystemWindowLawSmoke}.
 *
 * <p><b>Carried tail</b> — The replica is pressed when the ON edge
 * is applied, so its local window runs at least one press-to-active delay behind the wire. With a
 * 1–2 tick OFF→ON gap (a 2-charge damper firing its second charge the tick the first ends) the
 * PREVIOUS window's local tail still reads active on the frame the next ON lands; the old latch
 * took that as "this window already ran" and suppressed every frame of window 2 (seat-1
 * {@code repress_suppressed} 26→234 with {@code apply_presses} flat). The fix keys the memory on
 * the window (the ON edge's {@code simTick}, see {@link Window}) and marks activity seen at the
 * key change as {@code carry}: it never latches, and the carry ends the first frame the tail reads
 * idle — so window 2 is pressed at the first instant the engine can accept it (no hold, no mask).
 */
final class FoeSystemWindowLaw {

    /** Nothing to do (window closed, system already active, or the engine would refuse). */
    static final int NONE = 0;
    /** Window open, system idle + activatable, no press seen this window ⇒ {@code useSystem()}. */
    static final int PRESS = 1;
    /** Window open, system idle again after a press took this window ⇒ the tail: no re-press. */
    static final int SUPPRESS_REPRESS = 2;

    private FoeSystemWindowLaw() {
    }

    /**
     * The per-fresh-frame verdict. WHY = the class javadoc
     * (the charge-system double press).
     */
    static int action(boolean authOpen, boolean localActive, boolean canActivate,
            boolean pressedThisWindow) {
        if (!authOpen || localActive || !canActivate) {
            return NONE;
        }
        return pressedThisWindow ? SUPPRESS_REPRESS : PRESS;
    }

    /**
     * The memory's next value AFTER this frame's action ran.
     * latches once the system reads active under an open window (a press that took, or a window
     * already running); false the moment the window is closed (the next window presses fresh).
     * Activity {@code carry}-ed in from the
     * previous window's local tail never latches — only this window's own activation does.
     */
    static boolean nextPressed(boolean authOpen, boolean localActiveAfter, boolean prevPressed,
            boolean carry) {
        return authOpen && (prevPressed || (localActiveAfter && !carry));
    }

    /**
     * The carry's next value. The previous
     * window's tail stays "carried" only while it still reads active; once it reads idle the
     * carry is gone for good (a later activation is this window's own).
     */
    static boolean nextCarry(boolean prevCarry, boolean localActiveAfter) {
        return prevCarry && localActiveAfter;
    }

    /** {@link Window#key} / the caller's {@code openTick} when no ON edge was ever applied. */
    static final long NO_WINDOW = -1L;

    /**
     * One replica's system-window memory. ONE
     * allocation per replica (the caller's weak map), never per frame. {@link #key} = the
     * {@code simTick} of the last APPLIED ON edge (unique per entity per window; an OFF leaves it;
     * ticks are ≥ 0, {@link #NO_WINDOW} = none). Per window: {@code pressed} (the latch),
     * {@code carry} (the previous window's tail still active), {@code carriedOpen} (the window
     * opened carried — the carried-tail press meter), and the two once-per-window row flags.
     */
    static final class Window {
        long key = NO_WINDOW;
        /** The simTick of a v8 SEED that left the replica active
         *  ({@code forceState} into IN/ACTIVE/OUT, no ON edge applied yet). A late ON at or before
         *  it is THIS window's own activity — never carried, so the tail latches and suppresses. */
        long seededAt = NO_WINDOW;
        boolean pressed;
        boolean carry;
        boolean carriedOpen;
        boolean rowLogged;
        boolean disagreeLogged;
        /** The simTick of an APPLY press issued while the
         *  system read IDLE ({@link #NO_WINDOW} = none pending) — the next fresh frame grades it
         *  (still IDLE ⇒ the engine refused it: {@code predict_foe_system_idle_refused}). */
        long idlePressTick = NO_WINDOW;
        /** The replica's travel drive was ON at that press (the {@code _drive} share of the meter). */
        boolean idlePressDrive;

        /**
         * Frame start, before the action: a key that differs from the stored one is a NEW wire
         * window — reset the latch + row flags and carry what the replica already runs.
         *
         * @return true when a real window (key ≥ 0) opened here — the caller logs ONE
         *         {@code window-open} row; a change TO {@link #NO_WINDOW} (a reset) only clears.
         */
        boolean rekey(long openTick, boolean localActive) {
            if (openTick == key) {
                return false;
            }
            key = openTick;
            pressed = false;
            idlePressTick = NO_WINDOW; // a pending press never grades across windows
            idlePressDrive = false;
            rowLogged = false;
            disagreeLogged = false;
            carry = openTick >= 0L && localActive && openTick > seededAt; // seeded activity is never a tail
            carriedOpen = carry;
            return openTick >= 0L;
        }

        /**
         * After the action: the carry first (it survives only while the tail read active at the
         * frame start AND after the action — a synchronous activation by our own press can never
         * pass for the tail), then the latch against the updated carry; the wire CLOSE clears the
         * suppress-row flag (the earlier slot-[1] rule).
         */
        void advance(boolean authOpen, boolean localActive, boolean activeAfter) {
            carry = nextCarry(carry, localActive && activeAfter);
            pressed = nextPressed(authOpen, activeAfter, pressed, carry);
            if (!authOpen) {
                rowLogged = false;
            }
        }
    }
}
