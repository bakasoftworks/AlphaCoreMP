package cc.starsector.client;

import java.nio.charset.StandardCharsets;

import cc.starsector.server.runtime.WireTextLaw;

import cc.starsector.client.net.ChatWriter;

/**
 * The in-combat chat INPUT LAW: a pure, game-free state machine the
 * predict plugin feeds one keyboard event at a time from {@code processInputPreCoreControls}.
 * No LWJGL/API imports by design (the {@code ChatInputLawSelftest} drives it without a game);
 * time is injected ({@code nowMillis}) so the selftest owns the clock. Render-thread only —
 * no synchronization.
 *
 * <p>States and transitions (the law, RETURN dispositions amended by the SEND-BOTH rule —
 * chat Enter must never block the engine's retreat prompt, so every RETURN handles AND passes
 * through: the caller consumes ONLY on {@link Result#CONSUMED}):
 * <ul>
 *   <li>CLOSED + RETURN fresh press ⇒ OPEN, but the event is NOT consumed ({@link Result#PASS}
 *       — the engine's own Enter consumers still see it). Every other event passes through
 *       untouched ({@link Result#PASS}) — the ship flies normally.</li>
 *   <li>OPEN ⇒ every NON-RETURN keyboard event is consumed (the caller mirrors that with
 *       {@code InputEventAPI.consume()} + the per-frame movement-verb block — the leg-4
 *       adjudicated twin gate; mouse events are never fed, so aim/fire stay live).</li>
 *   <li>OPEN + RETURN fresh press: trimmed-nonblank buffer ⇒ submit through the local token
 *       bucket — token available ⇒ {@link Result#SENT} (read {@link #sentText()}) + close;
 *       bucket empty ⇒ {@link Result#RATE_BLOCKED}, stay open, KEEP the buffer (the user's
 *       line must not be lost to pacing). Blank buffer ⇒ close sans send ({@link Result#PASS}).
 *       All three pass the event through (SEND-BOTH).</li>
 *   <li>OPEN + ESCAPE fresh press ⇒ discard + close (consumed — the pause menu never sees it).</li>
 *   <li>OPEN + TAB fresh press ⇒ discard + close, NOT consumed ({@link Result#PASS} — the engine's
 *       tab-map toggle sees the same press).</li>
 *   <li>OPEN + BACK press/repeat ⇒ delete last char. OPEN + printable char on press/repeat
 *       (KEY_UP chars are engine-zeroed) ⇒ append, gated by the
 *       {@link ChatWriter#validateChatText} caps so the buffer ALWAYS satisfies the wire law:
 *       ≤{@link ChatWriter#TEXT_MAX_CHARS} chars AND ≤{@link ChatWriter#TEXT_BUFFER_BYTES}
 *       UTF-8 bytes, char law {@link WireTextLaw#isPrintableWireChar} (tab/newline/DEL AND the
 *       C1/NEL/LS/PS/bidi smuggle set rejected — the typed path now runs the
 *       same shared law as the wire validators, so a typed U+0085 can no longer mint a CHTU the
 *       server drops at decode).</li>
 *   <li>Held-Enter guard: RETURN acts on FRESH presses only — a KEY_REPEAT Return while open is
 *       consumed but never submits/closes (holding Enter cannot oscillate the box).</li>
 *   <li>Command history: OPEN + UP fresh press shows the last SUBMITTED line
 *       (the live draft is stashed on the first Up), further Ups walk older (stop at the oldest),
 *       DOWN walks newer and past the newest restores the draft; any edit (char, BACK, paste) or a
 *       close detaches the walk. Ring = {@link History} ({@value #HISTORY_DEPTH} deep, slash AND
 *       plain lines, never {@code /auth}, consecutive duplicates collapsed),
 *       shared across lane swaps by {@code ChatLane}.</li>
 *   <li>Caret (a recalled
 *       command must be editable with Left/Right "as normal" while Up/Down keep loading): the
 *       buffer carries a {@link #caret()} (a UTF-16 index, 0..length, never inside a surrogate
 *       pair). OPEN + LEFT/RIGHT press/repeat step one CODE POINT (clamped, no wrap), HOME/END
 *       jump — none of the four is an edit, so none detaches the history walk. DELETE removes
 *       the code point AT the caret, BACK the one BEFORE it (both edits ⇒ detach); a typed char
 *       and a paste INSERT at the caret under the unchanged wire caps, checked on the would-be
 *       buffer. A history step / draft restore puts the caret at the end; a close resets it.</li>
 * </ul>
 *
 * <p>The token bucket mirrors the server's per-session chat bucket EXACTLY
 * ({@code PilotIntentDatagramServer.onChatUp}: burst {@value #BUCKET_BURST}, one token per
 * {@value #TOKEN_REFILL_MILLIS} ms, lazy fractional refill, stamp-every-take) so a legit user is
 * locally blocked strictly before the server would silently drop — the server's
 * {@code chatRateDropped} stays 0 for a conforming client.
 */
public final class ChatInputLaw {

    /** LWJGL key codes, pinned (standard {@code org.lwjgl.input.Keyboard} values; no repo pin). */
    public static final int KEY_RETURN = 28;
    public static final int KEY_ESCAPE = 1;
    public static final int KEY_BACK = 14;
    /** The history arrows (the same LWJGL values {@code ListPickerLaw} pins for its
     *  row-nav; no key sharing to renegotiate: the picker consumes Up/Down only while OPEN, and it
     *  is open only while this box is CLOSED). */
    public static final int KEY_UP = 200;
    public static final int KEY_DOWN = 208;
    /** Tab closes an open chat box: LWJGL
     *  KEY_TAB. OPEN + TAB fresh press ⇒ discard + close like Escape, but the event PASSES (the
     *  engine's own Tab consumer — the warroom / tab map — sees the same press, SEND-BOTH). */
    public static final int KEY_TAB = 15;
    /** The caret keys, LWJGL {@code Keyboard.KEY_LEFT/RIGHT/
     *  HOME/END/DELETE} pinned as ints (the KEY_UP idiom — no LWJGL import in this pure law).
     *  Consumed while OPEN like every non-RETURN key; while CLOSED they pass untouched. */
    public static final int KEY_LEFT = 203;
    public static final int KEY_RIGHT = 205;
    public static final int KEY_HOME = 199;
    public static final int KEY_END = 207;
    public static final int KEY_DELETE = 211;

    /** How many submitted lines the history ring keeps;
     *  past it the oldest line drops. */
    public static final int HISTORY_DEPTH = 32;

    /**
     * The submitted-line ring behind Up/Down. Held OUTSIDE the law instance
     * on the {@code ChatLane.AUTH_STORE} shape: a {@link ChatInputLaw} is per lane and a lane is
     * per combat (and is swapped fresh on a lane death), so the ring lives in ONE static store in
     * {@code ChatLane} and every law instance the lane mints walks the same ring — an in-game
     * rejoin keeps the history. JVM-scoped on purpose, not session-keyed like the auth store: a
     * recalled command is harmless across serves and useful on the next one. Memory only, never
     * logged. An {@code /auth <password>} line is NEVER recorded:
     * this ring is JVM-scoped, so storing it would let Up recall server A's admin
     * password while connected to server B, whereas {@code AuthReplayLaw} is session-scoped by
     * law. Records every non-blank sent line, slash command or plain chat
     * (the history began slash-only: in a live match that guard kept a plain line out
     * of reach and Up recalled the newest slash line instead), skips a consecutive
     * duplicate, drops the oldest past {@link #HISTORY_DEPTH}. Render thread only, like the law.
     */
    public static final class History {
        /** Oldest first; index {@code size()-1} is the newest. */
        private final java.util.ArrayList<String> entries = new java.util.ArrayList<>();

        public int size() {
            return entries.size();
        }

        /** {@code 0} = the newest, {@code size()-1} = the oldest. */
        public String newestMinus(int steps) {
            return entries.get(entries.size() - 1 - steps);
        }

        /** Record a submitted line: any non-blank line (a blank recall is noise), consecutive
         *  duplicates collapsed, never a line the version-13 drain would prove or refuse rather than send as typed
         *  ({@code /auth <password>} and every other secret-bearing shape; see the class javadoc). */
        void record(String line) {
            if (line == null || line.isBlank()) {
                return;
            }
            // A password must not outlive its session in a JVM-scoped ring.
            // Keyed on the version-13
            // drain's class, so the ring keeps out every line that drain proves or refuses — incl. the slash-typo /
            // glued-secret arm (/authpw, \auth pw, / auth pw, ////auth pw) and the forms led by a space or format run,
            // which the old guard (isAuthLine || the display mask's identity test) kept and Up
            // drew in clear.
            // The old guard catches nothing the class misses, so it is not kept beside it: both its tests run INSIDE
            // auth13Class — on the line itself, or on its remainder when a leading run is skipped; on a led line they
            // can hold only when that run is all code points up to U+0020 (isAuthLine's trim) or all ' ' (the mask's skip), and
            // then they hold on the remainder as well.
            if (auth13Class(line) != Auth13Line.PLAIN) {
                return;
            }
            if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(line)) {
                return;
            }
            entries.add(line);
            if (entries.size() > HISTORY_DEPTH) {
                entries.remove(0);
            }
        }
    }

    /** Local mirror of the server bucket constants ({@code CHAT_BUCKET_BURST} et al.). */
    public static final int BUCKET_BURST = 4;
    public static final long TOKEN_REFILL_MILLIS = 1_000L;

    /** Paste processing ceiling — code points EXAMINED (stripped ones included). Bounds our
     *  WORK, not the allocation: the clipboard String was already allocated inside LWJGL before
     *  any cap of ours could run (accepted client-local risk,
     *  zero server surface). */
    public static final int PASTE_MAX_CODE_POINTS = 512;

    /** What one fed keyboard event did. Consume contract (the SEND-BOTH rule): the caller
     *  consumes ONLY on {@link #CONSUMED} — every other result passes the event through, even
     *  when the law acted on it (opened, closed, sent, rate-blocked). */
    public enum Result {
        /** Not consumed. May still have acted: the closed-state opening RETURN and the
         *  open-state blank-close RETURN both land here (handle-and-pass-through). */
        PASS,
        /** Swallowed (open-state non-RETURN event): consume, no send. */
        CONSUMED,
        /** A line was submitted: do NOT consume (SEND-BOTH); {@link #sentText()} carries the
         *  trimmed line. */
        SENT,
        /** Submit refused by the local bucket: do NOT consume (SEND-BOTH), stay open, buffer
         *  kept — show the local-only "chat: too fast" line. */
        RATE_BLOCKED
    }

    private final StringBuilder buffer = new StringBuilder();
    /** The insert point, 0..buffer.length(), never between the
     *  two units of a surrogate pair (moves by code point; {@link #snapCaretOutOfPair} after
     *  every edit). */
    private int caret;
    private boolean open;
    private String sentText;

    private double tokens = BUCKET_BURST;
    private long bucketRefillMillis; // 0 = never stamped (the server's lazy-stamp idiom)

    // The history walk: cursor -1 = DETACHED (the next Up starts at the newest and
    // stashes the live draft); 0..size-1 = the ring entry currently shown, newest first.
    private final History history;
    private int historyCursor = -1;
    private String historyDraft;

    /** A law over its own private ring (selftests; a lane uses the shared static store). */
    public ChatInputLaw() {
        this(new History());
    }

    /** A law walking the given ring (the lane's static store; never null). */
    public ChatInputLaw(History history) {
        if (history == null) {
            throw new IllegalArgumentException("history store required");
        }
        this.history = history;
    }

    /** The ring this law records into and walks (selftest + ChatLane lane swaps). */
    public History history() {
        return history;
    }

    /** True while the chat box is capturing the keyboard (typing label shows; movement blocked). */
    public boolean isOpen() {
        return open;
    }

    /** The live typed buffer (label presentation is the caller's; may be empty, never null). */
    public String bufferText() {
        return buffer.toString();
    }

    /** The caret as a UTF-16 index into {@link #bufferText()},
     *  0..length inclusive (length = after the last char); the renderer draws the bar there. */
    public int caret() {
        return caret;
    }

    /** The last {@link Result#SENT} line (trimmed, law-conforming); null before the first send. */
    public String sentText() {
        return sentText;
    }

    /**
     * Feed one keyboard event (mouse events are never fed — aim/fire stay live by design).
     * {@code keyDown}/{@code repeat} = {@code InputEventAPI.isKeyDownEvent()}/{@code isRepeat()}
     * (a repeat is NOT a key-down in the engine's event model); {@code keyCode} =
     * {@code getEventValue()}; {@code eventChar} = {@code getEventChar()} (engine-zeroed on
     * KEY_UP). The caller must read all four BEFORE consuming — the engine's consumed-event
     * guard throws on post-consume type/value access.
     */
    public Result onKeyboardEvent(boolean keyDown, boolean repeat, int keyCode, char eventChar,
            long nowMillis) {
        if (!open) {
            if (keyDown && !repeat && keyCode == KEY_RETURN) {
                // SEND-BOTH: open the box but let the press through — the engine's
                // own Enter consumers (retreat prompt) must never be starved by chat.
                open = true;
                return Result.PASS;
            }
            return Result.PASS;
        }
        // OPEN: the box owns the keyboard EXCEPT the RETURN dispositions below — those
        // handle-and-pass-through (SEND-BOTH; the caller consumes CONSUMED only).
        if (keyDown && !repeat && keyCode == KEY_RETURN) {
            String line = buffer.toString().trim();
            if (line.isEmpty()) {
                close();
                return Result.PASS;
            }
            if (!takeToken(nowMillis)) {
                return Result.RATE_BLOCKED;
            }
            sentText = line;
            history.record(line); // The SENT path is the one recording seam
            close();
            return Result.SENT;
        }
        if (keyDown && !repeat && keyCode == KEY_ESCAPE) {
            close();
            return Result.CONSUMED;
        }
        if (keyDown && !repeat && keyCode == KEY_TAB) {
            // The player pressed Tab FOR the tab map — get out of the way and
            // let the press through (Escape parity on the buffer: the draft is discarded).
            close();
            return Result.PASS;
        }
        if (keyDown && !repeat && (keyCode == KEY_UP || keyCode == KEY_DOWN)) {
            // Fresh presses only (a held arrow's repeats are swallowed, never walked).
            walkHistory(keyCode == KEY_UP);
            return Result.CONSUMED;
        }
        if ((keyDown || repeat) && (keyCode == KEY_LEFT || keyCode == KEY_RIGHT
                || keyCode == KEY_HOME || keyCode == KEY_END)) {
            // Caret moves are NOT edits — the history walk
            // survives them, so Up/Down still step the ring from where the walk was.
            moveCaret(keyCode);
            return Result.CONSUMED;
        }
        if ((keyDown || repeat) && keyCode == KEY_DELETE) {
            // Forward delete of one code point; an edit.
            if (caret < buffer.length()) {
                buffer.delete(caret, Character.offsetByCodePoints(buffer, caret, 1));
                snapCaretOutOfPair();
            }
            detachHistory();
            return Result.CONSUMED;
        }
        if ((keyDown || repeat) && keyCode == KEY_BACK) {
            // The code point BEFORE the caret (at the end = the old delete-last).
            if (caret > 0) {
                int from = Character.offsetByCodePoints(buffer, caret, -1);
                buffer.delete(from, caret);
                caret = from;
                snapCaretOutOfPair();
            }
            detachHistory(); // an edit makes the shown line the user's own draft again
            return Result.CONSUMED;
        }
        if ((keyDown || repeat) && WireTextLaw.isPrintableWireChar(eventChar)) {
            // The insert gate mirrors validateChatText exactly (the ONE shared WireTextLaw, B7):
            // the buffer can never grow past what the wire law accepts, so send-time
            // validation is a backstop, never a failure. Judged on the would-be
            // buffer with the char inserted AT the caret.
            if (fitsWireCaps(withInsertAtCaret(String.valueOf(eventChar)))) {
                buffer.insert(caret, eventChar);
                caret++;
                snapCaretOutOfPair();
            }
            detachHistory();
            return Result.CONSUMED;
        }
        return Result.CONSUMED; // KEY_UP, modifiers, control chars: swallowed, no state change
    }

    /**
     * One Up ({@code older=true}) or Down step through the ring. Up from DETACHED
     * stashes the live draft and shows the newest entry; further Ups walk older and stop at the
     * oldest (stay put, never wrap). Down walks newer; Down past the newest restores the stashed
     * draft and detaches. Down while detached is a no-op. An empty ring makes Up a no-op.
     */
    private void walkHistory(boolean older) {
        int size = history.size();
        if (older) {
            if (size == 0) {
                return;
            }
            if (historyCursor < 0) {
                historyDraft = buffer.toString();
                historyCursor = 0;
            } else if (historyCursor + 1 < size) {
                historyCursor++;
            } else {
                return; // at the oldest entry: stay put
            }
            showHistoryEntry();
            return;
        }
        if (historyCursor < 0) {
            return; // detached: nothing newer than the user's own draft
        }
        if (historyCursor == 0) {
            String draft = historyDraft;
            detachHistory();
            replaceBuffer(draft == null ? "" : draft);
            return;
        }
        historyCursor--;
        showHistoryEntry();
    }

    private void showHistoryEntry() {
        int size = history.size();
        if (historyCursor >= size) {
            historyCursor = size - 1; // the shared ring can only shrink by the cap; clamp anyway
        }
        replaceBuffer(historyCursor < 0 ? "" : history.newestMinus(historyCursor));
    }

    private void replaceBuffer(String text) {
        buffer.setLength(0);
        buffer.append(text);
        caret = buffer.length(); // A recalled line / restored draft edits from its end
    }

    /** LEFT/RIGHT one code point (a surrogate pair is one
     *  step), clamped at both ends; HOME/END jump. Never an edit, never a detach. */
    private void moveCaret(int keyCode) {
        if (keyCode == KEY_LEFT && caret > 0) {
            caret = Character.offsetByCodePoints(buffer, caret, -1);
        } else if (keyCode == KEY_RIGHT && caret < buffer.length()) {
            caret = Character.offsetByCodePoints(buffer, caret, 1);
        } else if (keyCode == KEY_HOME) {
            caret = 0;
        } else if (keyCode == KEY_END) {
            caret = buffer.length();
        }
    }

    /** The typed path admits lone surrogate units (the wire
     *  char law passes them), so an edit can FUSE a lone high and a lone low into one pair with
     *  the caret between them; step it past the fused pair so the invariant holds. */
    private void snapCaretOutOfPair() {
        if (caret > 0 && caret < buffer.length() && Character.isHighSurrogate(buffer.charAt(caret - 1))
                && Character.isLowSurrogate(buffer.charAt(caret))) {
            caret++;
        }
    }

    /** The buffer as it WOULD read with {@code ins} at the
     *  caret — the cap judge sees the real neighbours (a lone surrogate pairing up changes the
     *  UTF-8 count). */
    private String withInsertAtCaret(String ins) {
        return new StringBuilder(buffer).insert(caret, ins).toString();
    }

    /** The two {@link ChatWriter#validateChatText} caps on a
     *  would-be buffer (char, then byte) — the one judge for typed inserts and pastes. */
    private static boolean fitsWireCaps(String wouldBe) {
        return wouldBe.length() <= ChatWriter.TEXT_MAX_CHARS
                && utf8Bytes(wouldBe) <= ChatWriter.TEXT_BUFFER_BYTES;
    }

    private void detachHistory() {
        historyCursor = -1;
        historyDraft = null;
    }

    /**
     * Ctrl+V paste (built to a design with six security checks):
     * append a pasted string to the OPEN compose buffer BY
     * CODE POINT, each through the exact typed-path append gate (the {@link #onKeyboardEvent}
     * printability predicate + two-clause clamp, here pair-atomic), with the check-3 WIDENED
     * strip — dropped: the chat law's C0/DEL (so no pasted CR/LF/TAB can ever reach buffer or
     * log), the C1 block 0x80–0x9F (U+0085 NEL included), the Unicode line breaks U+2028/U+2029,
     * the bidi controls (U+061C, U+200E/U+200F, U+202A–U+202E, U+2066–U+2069) — on server and
     * client alike that whole set IS the shared
     * {@link WireTextLaw#isPrintableWireChar}, so the typed path and every family validator
     * reject it too; paste additionally drops LONE surrogates (they pass the char predicate but
     * would rot the buffer un-sendable — UTF-8 replacement at encode breaks the byte-identity
     * law). Surrogate PAIRS append or drop atomically, never split. The FIRST cap overflow
     * STOPS the whole paste (silent clamp — the typing-at-cap UX; deliberately stop-not-skip,
     * so a truncated paste is always a prefix). Null/empty ⇒ no-op; closed box ⇒ no-op. At most
     * {@link #PASTE_MAX_CODE_POINTS} code points are examined. NO wire-surface
     * inventory row: same frame family, same codec, same send law — client-local intake only;
     * this javadoc states the deliberate reuse so that claim stays
     * auditable. The clipboard read itself lives capture-side ({@code ChatLane}) — this class
     * keeps its no-LWJGL purity + selftest boundary. The clip
     * lands AT the caret (the caret ends after it; at the end = the old append), the clamp judges
     * the would-be buffer; the name is kept for its one caller.
     *
     * <p>SPLIT, not REFUSE: a paste with a VERB line ({@link #pasteHasVerbLine}) ENDS at
     * its first dropped line break once anything sits before the insertion point ({@code caret > 0}: text this paste
     * landed, or text typed before it) — the prefix rule above, so a verb line never joins other text ACROSS A LINE
     * BREAK (only that: a one-line clip {@code notes/auth pw} and a verb line pasted at caret 0 in front of typed text
     * still join — the same-line residual). SPLIT keeps a
     * copied {@code /auth <secret>} + a trailing line break working (REFUSE would land nothing); a paste with no verb
     * line keeps today's join (ordinary chat unchanged).
     */
    public void appendPasted(String clip) {
        if (!open || clip == null || clip.isEmpty()) {
            return;
        }
        detachHistory(); // A paste is an edit
        boolean verbPaste = pasteHasVerbLine(clip);
        int examined = 0;
        int i = 0;
        while (i < clip.length() && examined < PASTE_MAX_CODE_POINTS) {
            int cp = clip.codePointAt(i);
            i += Character.charCount(cp);
            examined++;
            if (!pasteAllowed(cp)) {
                if (verbPaste && caret > 0 && pasteLineBreak(cp)) {
                    return; // a verb paste's line break ends it (SPLIT): no join of a verb line across the break
                }
                continue; // stripped (or a lone surrogate): dropped, keep scanning
            }
            String ch = new String(Character.toChars(cp));
            // The typed-path two-clause clamp verbatim, pair-atomic (char cap, then byte cap).
            if (!fitsWireCaps(withInsertAtCaret(ch))) {
                return; // first overflow stops the paste — the silent typing-at-cap clamp
            }
            buffer.insert(caret, ch);
            caret += ch.length();
        }
    }

    /** The paste char law: the shared {@link WireTextLaw#isPrintableWireChar} (C0/DEL + the
     *  widened set — C1/NEL, LS/PS, bidi — ONE definition since
     *  the shared char-law change, mirrored on the typed path too) PLUS the paste-only lone-surrogate drop.
     *  Supplementary code points (≥ U+10000) are printable by construction (no control lives
     *  there; the pair's units are surrogates, checked below only when LONE). */
    private static boolean pasteAllowed(int cp) {
        if (cp <= 0xffff && !WireTextLaw.isPrintableWireChar((char) cp)) {
            return false; // the shared wire law: C0 (CR/LF/TAB) + DEL + C1/NEL + LS/PS + bidi
        }
        if (cp >= 0xd800 && cp <= 0xdfff) {
            return false; // LONE surrogate (codePointAt yields the bare unit when unpaired)
        }
        return true;
    }

    /**
     * True iff a line of {@code clip} (the first included; within the {@link #PASTE_MAX_CODE_POINTS} examined) opens,
     * after its leading run of space / FORMAT code points and of chars the paste drops, with {@code '/'} or a
     * {@link #slashLike} char — the SPLIT trigger of {@link #appendPasted}. WHY:
     * the paste drops every line break and kept scanning, so {@code notes} + LF + {@code /auth <secret>} landed as ONE
     * line {@code notes/auth <secret>} — PLAIN to {@link #auth13Class}, drawn in clear, kept in the history ring and
     * relayed by the door to every seat; the reverse order proved a wrong merged secret and spent a guess.
     *
     */
    private static boolean pasteHasVerbLine(String clip) {
        boolean lineStart = true;
        for (int i = 0, examined = 0; i < clip.length() && examined < PASTE_MAX_CODE_POINTS; examined++) {
            int cp = clip.codePointAt(i);
            i += Character.charCount(cp);
            if (pasteLineBreak(cp)) {
                lineStart = true;
            } else if (lineStart && pasteAllowed(cp) && !Character.isSpaceChar(cp)
                    && Character.getType(cp) != Character.FORMAT) {
                if (cp == '/' || (cp <= 0xFFFF && slashLike((char) cp))) {
                    return true;
                }
                lineStart = false;
            }
        }
        return false;
    }

    /** A line break the paste drops: LF, VT, FF, CR, NEL (U+0085), LINE / PARAGRAPH SEPARATOR (U+2028 / U+2029) —
     *  Unicode's mandatory breaks. */
    private static boolean pasteLineBreak(int cp) {
        return (cp >= 0x0A && cp <= 0x0D) || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }

    private void close() {
        open = false;
        buffer.setLength(0);
        caret = 0;
        detachHistory(); // A re-opened box starts its next Up from the newest entry
    }

    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The server's bucket verbatim (lazy fractional refill, stamp every take, floor 1.0). */
    private boolean takeToken(long now) {
        if (bucketRefillMillis != 0L) {
            long elapsed = now - bucketRefillMillis;
            if (elapsed > 0L) {
                tokens = Math.min((double) BUCKET_BURST,
                        tokens + elapsed / (double) TOKEN_REFILL_MILLIS);
            }
        }
        bucketRefillMillis = now;
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }

    // ---- The /auth replay's two pure predicates (ChatLane calls them; public
    // here because ChatLane is package-private and ChatInputLawSelftest rows A1-A3 cover them) --

    /** MIRRORED non-elevated {@code /auth} replies (server {@code ChatCommands.AUTH_USAGE} + the
     *  door's two constants). Drift here only stops promotions (fail-safe), never elevation. */
    static final String[] AUTH_MISS_REPLIES = {
        "wrong admin password", "usage: /auth <password>", "admin elevation unavailable on this server"};

    /** MIRRORED: the door's constant for an in-session
     *  {@code /auth} the {@code DoorGuessLedger} KEY path HELD before any compare (server
     *  {@code PilotIntentDatagramServer}, the AUTH branch — must stay byte-identical). Not a miss:
     *  the seat is admitted and the guess was never judged, so {@link AuthReplayLaw} retires the
     *  in-flight line AND, when the held line was the one-shot re-admit REPLAY, retries it once.
     *  Drift here only stops that retry (fail-safe), never elevation. */
    public static final String AUTH_HELD_REPLY = "admin auth held - retry in a moment";

    /** The server grammar's {@code /auth <password>} shape — the verb token case-insensitive, at
     *  least one further token (a bare {@code /auth} is a usage bounce the door never elevates
     *  on, so it carries no password to keep out of the history ring).
     * A verb the server's
     *  slash autocorrect would turn INTO {@code auth} counts too ({@code /aut pw}, {@code /auht
     *  pw}, {@code /authx pw}) — the server's nearest-verb match within {@link #osaDistance} 1
     *  for a typed name of ≤ 4 chars, 2 otherwise ({@code ChatCommands.nearestVerbOrNull},
     *  mirrored here as {@link #isAuthVerb}). Such a line's tail is a password, so it never
     *  enters the history ring. The in-flight hold uses the STRICT shape only
     *  ({@link #isStrictAuthLine}): {@code auth} is SUGGEST_ONLY, so the server
     *  merely NAMES a typo'd verb ("did you mean /auth?") and never runs or elevates it.
     *  Javadoc tail. */
    public static boolean isAuthLine(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        if (t.length() < 2 || t.charAt(0) != '/') {
            return false;
        }
        int end = 1;
        while (end < t.length() && !Character.isWhitespace(t.charAt(end))) {
            end++;
        }
        return end < t.length() && isAuthVerb(t.substring(1, end))
                && !t.substring(end).trim().isEmpty();
    }

    /** The STRICT {@code /auth <password>} shape (the predicate before autocorrect typos counted): the exact verb
     *  {@code auth}, case-insensitive, after the {@code /} prefix of the trimmed line, plus at
     *  least one non-blank tail token. {@link AuthReplayLaw#noteSend} counts in flight ONLY this
     *  shape: a typo'd verb is merely NAMED by the server ({@code ChatCommands.SUGGEST_ONLY},
     *  "did you mean /auth?") and never answered with an elevation or a miss reply, so a wide
     *  hold would pin the in-flight count and block the next real promotion. The WIDE
     *  {@link #isAuthLine} stays inside the history-ring guard ({@link #auth13Class}; a password tail never
     *  enters the ring).
     * */
    public static boolean isStrictAuthLine(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        if (t.length() < 2 || t.charAt(0) != '/') {
            return false;
        }
        int end = 1;
        while (end < t.length() && !Character.isWhitespace(t.charAt(end))) {
            end++;
        }
        return end < t.length() && "auth".equalsIgnoreCase(t.substring(1, end))
                && !t.substring(end).trim().isEmpty();
    }

    /**
     * The typing line's DISPLAY of the live buffer with an {@code /auth} argument masked: only
     * the drawn text changes, the buffer is untouched. Leading spaces are skipped; when the next character is
     * {@code '/'}, the verb is the maximal ASCII letter/digit run after it — the server's own audit key
     * ({@code ChatCommands.auditText}), NOT {@link #isAuthLine}'s whitespace split, which a U+00A0 separator slips
     * past. When {@link #isAuthVerb} holds for that run (the exact verb or an autocorrect typo of it), every character
     * after the run that is not {@code ' '} draws as {@code '*'}; any other text is returned as-is (the same object).
     * LENGTH-PRESERVING per UTF-16 unit, so the caller's caret index stays valid. Logs nothing; a plain line allocates
     * nothing, a slash line pays the verb test, a masked line one builder. Known limit (the ruling's shape): a password
     * typed with NO separator joins the verb run ({@code /authpw}) and is drawn as typed.
     *
     */
    public static String maskAuthForDisplay(String t) {
        if (t == null) {
            return null;
        }
        int n = t.length();
        int i = 0;
        while (i < n && t.charAt(i) == ' ') {
            i++;
        }
        if (i >= n || t.charAt(i) != '/') {
            return t;
        }
        int end = i + 1;
        while (end < n) {
            char c = t.charAt(end);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                break;
            }
            end++;
        }
        if (end == n || !isAuthVerb(t.substring(i + 1, end))) {
            return t; // no argument yet, or not an /auth verb
        }
        StringBuilder shown = new StringBuilder(n).append(t, 0, end);
        for (int k = end; k < n; k++) {
            shown.append(t.charAt(k) == ' ' ? ' ' : '*');
        }
        return shown.toString();
    }

    /** The version-13 chat drain's three classes of a lawful line ({@link #auth13Class}). */
    public enum Auth13Line {
        /** Not an {@code /auth} line in any reading: sent as typed. */
        PLAIN,
        /** EXACTLY what the server parses as AUTH: its secret ({@link #auth13SecretOrNull}) is proven, never sent. */
        PROVE,
        /** Any other line whose tail may be a password: refused locally — never sent readable, never an attempt. */
        REFUSE
    }

    /** The local line a version-13 REFUSE shows (the server's {@code AUTH_USAGE} text; never authored "server"). */
    public static final String AUTH13_REFUSED_LOCAL = "usage: /auth <password>";
    /** MIRRORED: the local line a version-13 REFUSE of a LED line shows — the door's {@code
     *  ChatCommands.LED_COMMAND_NOT_SENT} byte for byte (a constant: no echo of any typed character). */
    public static final String AUTH13_LED_REFUSED_LOCAL =
            "not sent: a command starts with / as the very first character (nothing was shown to other players)";

    /**
     * The local reply a version-13 REFUSE of {@code line} shows ({@code ChatLane.drainAuth13}): a LED line (a leading
     * space / format run, {@link #leadingSpaceOrFormatEnd} > 0) or a line whose opener is a slash LOOK-ALIKE
     * ({@code \auth pw}, a wide slash) gets {@link #AUTH13_LED_REFUSED_LOCAL}, any other
     * {@link #AUTH13_REFUSED_LOCAL}. WHY: the usage text answered a paste that carried U+FEFF or U+200B before an exact
     * {@code /auth <secret>} with the very usage it already typed, on every re-paste — the invisible lead is what to
     * fix. The look-alike arm:
     * the door answers {@code LED_COMMAND_NOT_SENT} to a look-alike opener too
     * ({@code ChatCommands.ledCommandLine}), and before the kit refused these lines that door text is what the player
     * saw — the kit's reply matches the door's. Pure; null = the usage line. Rows QV2r.
     *
     */
    public static String auth13RefusedReply(String line) {
        if (line == null) {
            return AUTH13_REFUSED_LOCAL;
        }
        int lead = leadingSpaceOrFormatEnd(line);
        boolean lookAlikeOpener = lead < line.length() && WireTextLaw.isSlashLookAlike(line.charAt(lead));
        return lead > 0 || lookAlikeOpener ? AUTH13_LED_REFUSED_LOCAL : AUTH13_REFUSED_LOCAL;
    }

    /** The local line a version-13 proof that cannot be made shows. */
    public static final String AUTH13_UNAVAILABLE_LOCAL = "admin auth unavailable on this connection";

    /**
     * The version-13 drain's class of one line that already passed {@code ChatWriter.validateChatText}:
     * {@link Auth13Line#PROVE} iff {@link #auth13SecretOrNull} finds the server's
     * exact AUTH shape; else {@link Auth13Line#REFUSE} when {@link #isAuthLine} or {@link #maskAuthForDisplay} catches
     * it — that covers the evasion shapes ({@code /aut pw}, {@code /auht pw}, {@code //auth pw} [isAuthLine only:
     * the mask's run after the first {@code /} is empty], {@code /auth-x pw}, a U+00A0-glued secret, a third token);
     * or when the line opens with a {@link #slashLike} character and the text after the leading run of slash-likes,
     * space characters ({@code Character.isSpaceChar}: U+0020, U+00A0, U+3000) and FORMAT code points (U+200B, U+2060,
     * U+FEFF) starts with {@code auth} — any case, a full-width form read as its ASCII twin ({@link #authAt})
     * (it catches the secret glued to the verb, {@code /authpw} — the mask's known limit — and
     * the slash typos past the autocorrect radius, {@code ////auth pw}, {@code / auth pw}, {@code \auth pw}; no other
     * server verb starts with {@code auth}); else {@link Auth13Line#PLAIN} — the bare {@code /auth} included (no tail,
     * the server's usage bounce). NOT caught, by design: a typo'd verb with the secret glued on ({@code /atuhpw}) — no
     * client predicate separates it from any unknown command. Pure; null = PLAIN.
     *
     *
     * <p>A LED line (the pre-flip scan's kit-dial-auth-1): the classes above are read on what follows
     * the line's leading run of space and format code points ({@link #leadingSpaceOrFormatEnd}). WHY: the secret must
     * never ride ordinary chat. The submit's {@code String.trim} strips only code points up to U+0020, so a line opening with
     * U+00A0, U+3000, U+2000-U+200A, U+200B, U+2060 or U+FEFF before {@code /auth <secret>} (a paste, an IME, a stray
     * key) reaches here intact, and the server's {@code ChatCommands.parse} tests {@code startsWith("/")} on the raw
     * text: there it is NOT a command but chat, relayed to every seat — so classing it PLAIN sent the admin secret
     * readable to all of them. A led line whose remainder is not PLAIN is {@link Auth13Line#REFUSE}, never PROVE: the
     * server never parses it as AUTH, and the drain's secret reader ({@link #auth13SecretOrNull}, that parse's mirror)
     * reads no secret from it. Real text before the slash ({@code hi /auth pw}) is no run: a sentence, still PLAIN.
     *
     */
    public static Auth13Line auth13Class(String line) {
        if (line == null) {
            return Auth13Line.PLAIN;
        }
        int lead = leadingSpaceOrFormatEnd(line);
        if (lead > 0) {
            return auth13ClassUnled(line.substring(lead)) == Auth13Line.PLAIN ? Auth13Line.PLAIN : Auth13Line.REFUSE;
        }
        return auth13ClassUnled(line);
    }

    /**
     * The end index of {@code line}'s LEADING run of code points that {@code Character.isSpaceChar} (U+0020, U+00A0,
     * U+2000-U+200A, U+3000, ...), {@code Character.isWhitespace} (the ASCII tab / line controls) or whose type is
     * {@code Character.FORMAT} (U+200B, U+2060, U+FEFF, the other zero-width controls, the supplementary tag
     * characters): 0 = no run, {@code line.length()} = the whole line. Read per CODE POINT, so a supplementary format
     * character (a surrogate pair, which the wire law admits) is skipped as one. The skip set of {@link #auth13Class}.
     *
     */
    private static int leadingSpaceOrFormatEnd(String line) {
        int k = 0;
        while (k < line.length()) {
            int cp = line.codePointAt(k);
            if (!Character.isSpaceChar(cp) && !Character.isWhitespace(cp) && Character.getType(cp) != Character.FORMAT) {
                break;
            }
            k += Character.charCount(cp);
        }
        return k;
    }

    /** {@link #auth13Class} of a non-null line with no leading space/format run: PROVE, then REFUSE (the wide and
     *  mask catchers, then the slash-like arm), else PLAIN — the classes its javadoc lists.
     * */
    private static Auth13Line auth13ClassUnled(String line) {
        if (auth13SecretOrNull(line) != null) {
            return Auth13Line.PROVE;
        }
        // IDENTITY, not equals: a tail of only '*' and ' ' masks to EQUAL text ("/aut***").
        if (isAuthLine(line) || maskAuthForDisplay(line) != line) {
            return Auth13Line.REFUSE;
        }
        if (!line.isEmpty() && slashLike(line.charAt(0)) && !"/auth".equalsIgnoreCase(line)) {
            // The run also skips FORMAT code points (U+200B, U+2060, U+FEFF, the tag characters; per CODE POINT, a tag
            // character is a surrogate pair): "/" + U+200B + " auth pw" classed PLAIN — sent readable, and behind a
            // leading run relayed to every seat (rows QV2).
            int verb = 1;
            while (verb < line.length()) {
                int cp = line.codePointAt(verb);
                if (!(cp <= 0xFFFF && slashLike((char) cp)) && !Character.isSpaceChar(cp)
                        && Character.getType(cp) != Character.FORMAT) {
                    break;
                }
                verb += Character.charCount(cp);
            }
            if (authAt(line, verb)) {
                return Auth13Line.REFUSE;
            }
        }
        return Auth13Line.PLAIN;
    }

    /**
     * {@code auth} at {@code k} in any case, a FULL-WIDTH form (U+FF01..U+FF5E: an IME's wide mode) read as its ASCII
     * twin; {@code k + 4 > length} = false. The kit's private twin of the server's {@code ChatCommands.authAt} (same
     * shape, same bounds check). WHY its own copy: the kit must class the line BEFORE it is sent, and
     * {@code ChatCommands} is not kit-folded ({@code client-mod/build.ps1 $pilotClasses}). The {@code regionMatches}
     * it replaces never folds a wide letter, so a slash-like opener + a wide {@code auth} + a space + the secret
     * classed PLAIN and was sent readable (the door's half; rows QV2w).
     *
     */
    private static boolean authAt(String text, int k) {
        if (k + 4 > text.length()) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            char c = text.charAt(k + i);
            if (c >= (char) 0xFF01 && c <= (char) 0xFF5E) {
                c = (char) (c - 0xFEE0);
            }
            if (Character.toLowerCase(c) != "auth".charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** What a hand reaching for the command slash can land on: {@code '/'} or a slash LOOK-ALIKE — none of which is a
     *  command to the server, so {@code \auth pw} would be relayed to every player as chat.
     *  The look-alike set is {@link WireTextLaw#isSlashLookAlike}, the ONE predicate the door's
     *  {@code ChatCommands.slashLookAlike} also reads: this kit's own copy held only the
     *  backslash and U+FF0F, so U+FF3C / U+2215 / U+2044 / U+FE68 + {@code auth <secret>} classed PLAIN and left the kit
     *  readable while the door already caught them (rows QV2s). */
    private static boolean slashLike(char c) {
        return c == '/' || WireTextLaw.isSlashLookAlike(c);
    }

    /**
     * The secret of a line the server parses as EXACTLY {@code AUTH}, else {@code null} — a mirror of
     * {@code ChatCommands.parse} (server tree): NO trim (it tests {@code startsWith("/")} on the raw text — the
     * wire law {@code ChatWriter.validateChatText} refuses an untrimmed line, and the drain
     * validates first, {@code ChatLane.sendPendingChats}); {@code split(" ", -1)}; the verb =
     * {@code asciiLower(tokens[0].substring(1))} (only {@code A-Z} fold, so {@code /AUTH} and
     * {@code /Auth} are AUTH); then case {@code "auth"}: {@code emptyToken || tokens.length != 2} is
     * USAGE, so exactly two tokens, the second non-empty. Every other verb (a typo the autocorrect would name — auth is
     * SUGGEST_ONLY) never parses as AUTH.
     *
     */
    public static String auth13SecretOrNull(String line) {
        if (line == null || !line.startsWith("/")) {
            return null;
        }
        String[] tokens = line.split(" ", -1);
        if (tokens.length != 2 || tokens[1].isEmpty() || tokens[0].length() != 5) {
            return null;
        }
        for (int i = 1; i < 5; i++) {
            char c = tokens[0].charAt(i);
            if ((c >= 'A' && c <= 'Z' ? (char) (c + 32) : c) != "/auth".charAt(i)) {
                return null;
            }
        }
        return tokens[1];
    }

    /** Server verb names are ASCII-folded and read at most this long before the autocorrect
     *  (the server's {@code UNKNOWN_NAME_ECHO_CHARS}); a longer token is never {@code auth}. */
    static final int AUTH_VERB_MAX_CHARS = 24;

    /** {@code auth} itself, or a typo the server autocorrect maps onto it:
     *  ASCII-folded, OSA distance ≤ 1 for a ≤ 4-char name, ≤ 2 otherwise. No other table verb
     *  sits inside that radius ({@code mute} is at 2 with 4 chars, outside the ≤ 4-char limit). */
    static boolean isAuthVerb(String verbToken) {
        if (verbToken == null || verbToken.isEmpty() || verbToken.length() > AUTH_VERB_MAX_CHARS) {
            return false;
        }
        StringBuilder folded = new StringBuilder(verbToken.length());
        for (int i = 0; i < verbToken.length(); i++) {
            char c = verbToken.charAt(i);
            folded.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        String name = folded.toString();
        if ("auth".equals(name)) {
            return true;
        }
        int limit = name.length() <= 4 ? 1 : 2;
        return osaDistance(name, "auth") <= limit;
    }

    /** Optimal string alignment (restricted Damerau-Levenshtein) distance — the server's
     *  {@code ChatCommands.osaDistance} verbatim. */
    public static int osaDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    /** A server-authored line that ANSWERS an {@code /auth} without elevating. */
    public static boolean isAuthMissReply(String text) {
        if (text == null) {
            return false;
        }
        for (String miss : AUTH_MISS_REPLIES) {
            if (miss.equals(text)) {
                return true;
            }
        }
        return false;
    }
}
