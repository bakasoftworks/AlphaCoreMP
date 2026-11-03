package cc.starsector.client;

/**
 * The raw input-level sampler's gate ({@code PredictReconcileCombatPlugin.captureOwnInputLevels}): may this frame's
 * held keys, mouse buttons and control keys be read and shipped to the server at all? PURE (seven booleans in, a
 * verdict out) so the truth table is pinned game-free; the plugin reads the seven facts once per frame and writes
 * EMPTY levels whenever this says no.
 *
 * <p>Six gates, any one forcing EMPTY: the own-ship read is unavailable (no own ship, a recorded intent source, a
 * command-transfer transit, the own death latch); the chat box owns the keyboard; the picker panel owns the
 * keyboard; the game window is not the active foreground window (the display not created, or
 * {@code Display.isActive()} false); a vanilla combat dialog shows ({@code CombatEngineAPI.isUIShowingDialog()});
 * the codex shows ({@code SettingsAPI.isShowingCodex()}: the hotkey codex, which the dialog read never sees, so
 * without this gate a codex search term's letters would ride as held keys; the codex opened from the deploy screen
 * is a combat dialog, so the dialog gate already holds for it).
 * Text a player types into ANOTHER window (a browser, a password manager) must never reach the server as held keys.
 * The focus gate is defence in depth over the input library: LWJGL's Windows keyboard releases every held key when
 * the window loses focus and only the focused window receives key messages, but focus loss does not touch the mouse
 * (a button's state clears only on its own button-up or a capture loss), and this gate holds whatever the library
 * does. Unpaused combat keeps advancing while the window is inactive, so the sampler does run on those frames.
 * {@link #commandLatchApplies} and {@link CommandLatchScope} scope the held-input latch's command half to the edges
 * that end a text surface (their javadoc = the WHY).
 *
 */
public final class InputLevelGate {

    /** {@link #emptyCause}: the frame is sampled, or an older gate (own read, chat, picker) already empties it. */
    public static final int CAUSE_NONE = 0;
    /** {@link #emptyCause}: the focus gate alone empties the frame (checked before the dialog and codex gates). */
    public static final int CAUSE_UNFOCUSED = 1;
    /** {@link #emptyCause}: the dialog gate empties the frame (the window is active; checked before the codex gate). */
    public static final int CAUSE_DIALOG = 2;
    /** {@link #emptyCause}: the codex gate alone empties the frame (the window is active, no dialog shows). */
    public static final int CAUSE_CODEX = 3;

    private InputLevelGate() {
    }

    /**
     * True = sample the levels this frame; false = every level reads EMPTY. {@code displayActive} is only
     * meaningful when {@code displayCreated} (the caller never asks an uncreated display); an uncreated display
     * fails closed.
     */
    public static boolean sampleAllowed(boolean ownReadable, boolean chatOpen, boolean pickerOpen,
            boolean displayCreated, boolean displayActive, boolean dialogShowing, boolean codexShowing) {
        return ownReadable && !chatOpen && !pickerOpen && displayCreated && displayActive && !dialogShowing
                && !codexShowing;
    }

    /**
     * Which of the three window gates empties the frame, for the plugin's three disjoint meters: {@link #CAUSE_NONE}
     * when the frame is sampled or an older gate already holds (those frames are not window-gate frames), else
     * {@link #CAUSE_UNFOCUSED} when the window is not active (checked first, so a dialog or the codex over an inactive
     * window counts as unfocused), else {@link #CAUSE_DIALOG} when a dialog shows (checked before the codex, so a
     * codex opened over a dialog counts as dialog), else {@link #CAUSE_CODEX} when the codex shows.
     */
    public static int emptyCause(boolean ownReadable, boolean chatOpen, boolean pickerOpen,
            boolean displayCreated, boolean displayActive, boolean dialogShowing, boolean codexShowing) {
        if (!ownReadable || chatOpen || pickerOpen) {
            return CAUSE_NONE;
        }
        if (!displayCreated || !displayActive) {
            return CAUSE_UNFOCUSED;
        }
        if (dialogShowing) {
            return CAUSE_DIALOG;
        }
        return codexShowing ? CAUSE_CODEX : CAUSE_NONE;
    }

    /**
     * Does the held-input latch's COMMAND half (the per-frame ship-command block, plus the movement-mirror mask, for a
     * control key still held from the falling edge) apply to a latch minted after a gated frame with these seven
     * facts? True only for a TEXT cause: the chat box, the picker, a vanilla dialog or the codex showed on that frame.
     * False when only the own-ship read (a respawn, a command-transfer arrival, the own death latch), or only the
     * focus gate, emptied it.
     *
     * <p>WHY: vanilla steers on a movement key held across combat start, a transfer or a refocus. The combat state
     * polls each movement binding every unpaused frame through the bindings table, which reads the raw
     * {@code Keyboard.isKeyDown} / {@code Mouse.isButtonDown} LEVEL, never a press edge it re-arms.
     *So blocking a held W at those edges
     * is a regression the pilot feels: no thrust until the key is pressed again. At a text edge the block stays: the
     * last letters of a typed line, still down as the box closes, are not a press the pilot made.
     *
     * <p>A text surface on the frame decides, whatever else gated it (an unfocused window, a missing own read): such a
     * frame keeps the block, as before. {@code ownReadable}, {@code displayCreated} and {@code displayActive} never
     * decide; the sampler hands the same seven facts to all three verdicts, and the selftest rows pin that those gates
     * alone never apply the command half. The LEVELS half ({@link HeldInputLatch}: the wire keys, buttons and control
     * bits) is untouched by this verdict and drops every edge-held key at EVERY falling edge.
     *
     */
    public static boolean commandLatchApplies(boolean ownReadable, boolean chatOpen, boolean pickerOpen,
            boolean displayCreated, boolean displayActive, boolean dialogShowing, boolean codexShowing) {
        return chatOpen || pickerOpen || dialogShowing || codexShowing;
    }

    /**
     * The command half's scope across one gated stretch and the sampled stretch after it: the LAST gated frame's
     * {@link #commandLatchApplies} verdict, which governs the latch minted at the falling edge that ends the stretch
     * until the next gated frame overwrites it. A new scope reads false: the first sampled frame of a combat is a
     * falling edge with no gated frame before it, so a key held into the combat start steers, as in vanilla. Render
     * thread only, not thread-safe.
     *
     */
    public static final class CommandLatchScope {

        private boolean textEdge;

        /** One gated frame: its seven facts replace any older verdict, so the last gated frame decides. */
        public void gatedFrame(boolean ownReadable, boolean chatOpen, boolean pickerOpen, boolean displayCreated,
                boolean displayActive, boolean dialogShowing, boolean codexShowing) {
            textEdge = commandLatchApplies(ownReadable, chatOpen, pickerOpen, displayCreated, displayActive,
                    dialogShowing, codexShowing);
        }

        /** Does the latch minted at the last falling edge carry its command half? */
        public boolean applies() {
            return textEdge;
        }

        /** A re-initialised sampler: its next falling edge is a combat-start edge again (no gated frame before it). */
        public void reset() {
            textEdge = false;
        }
    }
}
