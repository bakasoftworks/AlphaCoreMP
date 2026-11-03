package cc.starsector.client;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * The held-input latch of the raw input-level sampler ({@code PredictReconcileCombatPlugin.captureOwnInputLevels}):
 * a key or mouse button that is already held when the sampler's gate opens is not a press the pilot made in combat,
 * so it never rides the wire until it is released.
 *
 * <p>WHY: the gate ({@link InputLevelGate#sampleAllowed}) empties the levels while the chat box, the picker, a vanilla
 * dialog or the codex owns the keyboard, or while the window is not focused. But the chat box closes on the key-DOWN
 * of Enter / Escape / Tab, and consuming that key event never clears the input library's held-key buffer: the last
 * character or two of the typed line, still physically held at that instant, read as held keys on the next ungated
 * frames. Without this latch those trailing keys of a typed line (a password line included) would travel to the host
 * as held key codes, into the match record and onto the control bits. The same falling edge happens when the picker,
 * a dialog or the codex closes, when the window regains focus, and on the first sampled frame of a combat (keys and
 * buttons still held from the menu or the join click).
 *
 * <p>LAW: on the gate's falling edge (the first sampled frame after a gated one) {@link #latchFrom} latches every key
 * code and mouse button down at that instant, replacing any older set. A latched code / button reads NOT held (in the
 * key codes, the button level and the control bits alike) until the first frame it reads not down, its own release,
 * when it leaves the latch for good. A fresh press after that release rides in the very frame it is pressed: the latch
 * adds no latency to any press made while the sampler runs. PURE (no input-library read; the caller hands in the
 * down states) so the law is pinned game-free. Render thread only, not thread-safe.
 *
 */
public final class HeldInputLatch {

    /** Latchable key codes are 1..{@code KEY_CODE_LIMIT}-1 (the input library's keyboard size; code 0 = no key). */
    public static final int KEY_CODE_LIMIT = 256;

    private final boolean[] latchedKeys = new boolean[KEY_CODE_LIMIT];
    private int latchedKeyCount;
    /** Bit b = mouse button b latched (buttons 0..7, the sampler's button level). */
    private int latchedButtons;

    /**
     * The falling edge: the latched set becomes exactly the codes {@code keyDown} answers true for (1..255) plus the
     * {@code buttonsDown} bits. A throw from {@code keyDown} leaves a partial set; the caller's next edge replaces it.
     */
    public void latchFrom(IntPredicate keyDown, int buttonsDown) {
        clear();
        for (int code = 1; code < KEY_CODE_LIMIT; code++) {
            if (keyDown.test(code)) {
                latchedKeys[code] = true;
                latchedKeyCount++;
            }
        }
        latchedButtons = buttonsDown;
    }

    /**
     * True = drop {@code code} this frame (latched and still down). A latched code read NOT down leaves the latch here
     * (its release) and answers false, so the sampler must visit every code every frame for releases to land. An
     * out-of-range code is never latched.
     */
    public boolean keyBlocked(int code, boolean downNow) {
        if (!isLatched(code)) {
            return false;
        }
        if (downNow) {
            return true;
        }
        latchedKeys[code] = false;
        latchedKeyCount--;
        return false;
    }

    /** Releases every latched button not in {@code buttonsDown}, then answers {@code buttonsDown} minus the still-latched. */
    public int filterButtons(int buttonsDown) {
        latchedButtons &= buttonsDown;
        return buttonsDown & ~latchedButtons;
    }

    /** Is {@code code} latched now (the last {@link #keyBlocked} of this code already applied its release)? */
    public boolean isLatched(int code) {
        return code > 0 && code < KEY_CODE_LIMIT && latchedKeys[code];
    }

    /** Key codes latched now (buttons excluded: {@link #latchedButtons}). */
    public int latchedCount() {
        return latchedKeyCount;
    }

    /** Mouse buttons latched now, as a bit mask. */
    public int latchedButtons() {
        return latchedButtons;
    }

    /**
     * The COMMAND half of the law: bit i set = {@code controlKeyIndex[i]} (this pilot's
     * keymap entry for {@code PilotIntentFrame.CONTROL_KEY_NAMES[i]}) is latched now — a key entry via
     * {@link #isLatched}, a mouse entry ({@code >= mouseBase}) via its button's bit in {@link #latchedButtons}; an
     * unmapped entry (0), an out-of-range code and a button past the mask never set a bit; a null index answers 0.
     * Pure, reads only (the frame's {@link #keyBlocked} / {@link #filterButtons} pass already landed the releases).
     * WHY: ship controls are engine-POLLED, so dropping a latched key from the wire levels is only half the law — the
     * same letter, bound to a ship control, would still steer the hull until its key-up; the caller blocks each marked
     * name's ship commands for the frame (the chat box's own lever). An unlatched key never sets a bit: zero latency.
     */
    public int latchedControls(int[] controlKeyIndex, int mouseBase) {
        if (controlKeyIndex == null) {
            return 0;
        }
        int bits = 0;
        for (int i = 0; i < controlKeyIndex.length && i < 32; i++) {
            int entry = controlKeyIndex[i];
            boolean latched;
            if (entry >= mouseBase) {
                int button = entry - mouseBase;
                latched = button < 32 && (latchedButtons >>> button & 1) != 0;
            } else {
                latched = isLatched(entry);
            }
            if (latched) {
                bits |= 1 << i;
            }
        }
        return bits;
    }

    /** Drops every latch (nothing blocked until the next {@link #latchFrom}). */
    public void clear() {
        if (latchedKeyCount != 0) {
            Arrays.fill(latchedKeys, false);
            latchedKeyCount = 0;
        }
        latchedButtons = 0;
    }
}
