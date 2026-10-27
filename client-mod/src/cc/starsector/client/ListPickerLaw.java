package cc.starsector.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import cc.starsector.client.net.ClientListingRecord;
import cc.starsector.client.net.ClientListingReader;

/**
 * The large-list picker — the pure INPUT/STATE LAW for the LSTD listing panel.
 * Game-free by design (no LWJGL/API
 * imports; the {@code ListPickerLawSelftest} drives it without a game, time injected) — the
 * {@link ChatInputLaw} boundary verbatim. Render-thread only, no synchronization. The lane
 * ({@code ListPickerLane}) owns LSTD frame assembly, all rendering, geometry and the actual
 * chat-send; this class owns exactly: which listing is HELD, which mode the picker is in, the
 * filter/cursor/scroll arithmetic, and the event→action verdicts.
 *
 * <p><b>Modes (the mode ladder as amended — typing the command is enough to open the panel):
 * a complete listing OPENs the panel
 * DIRECTLY — on arrival when the chat box is closed, else at box close. The deferred-open
 * PROMPT state (passive line + {@link #OPEN_KEY} press) is RETIRED on the open-trigger ONLY;
 * every other design law (Escape-close, idle, collision, consume) is unchanged. The WASD trap the
 * prompt existed to kill stays dead: the panel only ever follows the seat's own typed
 * argument-less listing command — the deliberate text-mode entry already happened in chat.</b>
 * <ul>
 *   <li>{@code IDLE} — nothing held. Every event {@link Result#PASS}es; the picker is invisible.</li>
 *   <li>{@code PARKED} — a listing is held but the CHAT BOX is open: single-slot latest-wins,
 *       nothing rendered, every event PASSes — chat owns the keyboard; {@code tick} OPENs the
 *       panel on box close.</li>
 *   <li>{@code OPEN} — the modal panel: EVERY keyboard event is consumed (the chat-open
 *       contract; the lane additionally blocks engine-polled ship verbs per frame while open).
 *       Printable chars filter, Up/Down/PgUp/PgDn navigate the FILTERED view, BACK edits the
 *       filter (no-op on empty — never an accidental close), Enter selects the highlighted row,
 *       Escape closes.</li>
 * </ul>
 *
 * <p><b>Consume contract:</b> the caller consumes on every verdict EXCEPT {@link Result#PASS}.
 * <b>Declared deviation from chat's SEND-BOTH rule:</b> the picker CONSUMES its RETURN.
 * SEND-BOTH exists because RETURN is chat's own open key — passing it through keeps the engine's
 * retreat prompt reachable from a box the player is always one keystroke from opening. The
 * picker's RETURN acts only inside a modal the player deliberately summoned by TYPING the
 * listing command (the SS405 doctrine); passing it through would DOUBLE-ACT (a selection press
 * also confirming whatever engine prompt sits underneath). Escape consumed = the chat idiom
 * (the pause menu never sees it).
 *
 * <p><b>Other adjudicated details</b> (recorded here because the selftest pins them):
 * {@link #OPEN_KEY} while OPEN is a consumed NO-OP — the key is VESTIGIAL as a trigger since
 * SS405, but a habituated backtick press (or the Console Commands default) must never type
 * into the filter, and close stays Escape-only (the BACK-never-closes spirit). A filter
 * edit resets the cursor to the top of the new view (predictable incremental-search behavior).
 * Latest-wins replaces the held listing in EVERY mode; an arrival while OPEN keeps the panel
 * open but resets filter/cursor/scroll (the content is new — stale navigation must not point at
 * a random row). The {@link #IDLE_TIMEOUT_MILLIS 30 s} idle law runs uniformly in
 * PARKED/OPEN (every kind but {@link #KIND_LOCAL_HELP}) from the last activity stamp (arrival, auto-open, or any consumed
 * keystroke) — a stale listing self-clears (v1 is a static snapshot; the player re-asks).
 * An empty offered listing is ignored (the server never emits one — empty censuses keep their
 * short CHTD reply by L3 law).
 *
 * <p>Key ids pinned from the bytecode of the install's {@code lwjgl.jar} (never from
 * a recon bank). OPEN_KEY = grave/backtick (the retired SS382 trigger, {@code pp-recon/g});
 * kept pinned solely for the while-OPEN swallow above.
 */
public final class ListPickerLaw {

    /** LWJGL key codes, pinned against the install's lwjgl.jar. */
    public static final int OPEN_KEY = 41;      // KEY_GRAVE (retired trigger — see class javadoc)
    /**
     * W12 VOICE L5: the client-local {@code /settings} listing kind. NEVER on
     * the wire — {@code ClientListingReader.validateHeader} refuses it from the server, so a
     * listing of this kind can only be minted by {@code ListPickerLane.openSettings}; its picks
     * are applied locally and never sent.
     */
    public static final int KIND_LOCAL_SETTINGS = 100;
    /**
     * The client-local {@code /help} menu kind ("/help
     * needs to open a scrollable menu like eg. /settings"). NEVER on the wire (the {@link #KIND_LOCAL_SETTINGS}
     * law: {@code ClientListingReader} refuses any kind above {@code KIND_MAX}); only {@code ListPickerLane.openHelp}
     * mints it. Read-only rows: Enter is inert (no command, the menu stays open), no idle auto-close, Esc / Tab
     * close as on every kind, typing filters.
     */
    public static final int KIND_LOCAL_HELP = 101;

    /** The trailing keyword the server's {@code ChatCommands.parseSpawn} folds
     *  (mirrors {@code ChatCommands.SPAWN_OFFICER_KEYWORD}); a FIXED token, never player text. */
    public static final String SPAWN_OFFICER_KEYWORD = "officer";
    /**
     * The W16 BAN / UNBAN term ladders Left/Right steps on a
     * {@code /ban} or {@code /unban} menu row (clamped at both ends, never wrapped). They MIRROR the
     * server's closed {@code BanTerm} tokens ({@code server/src/cc/starsector/server/runtime/BanTerm.java};
     * the {@link #SPAWN_OFFICER_KEYWORD} precedent) — FIXED tokens, never player text. A /ban row pastes
     * {@code /ban #<id> <term>}; an /unban row at {@code lift} (index 0) pastes today's
     * {@code /unban #<n>}, any other term {@code /banned #<n> <term>} (re-time that entry). The row
     * holds its term in {@code Entry.value} (null = the open index).
     */
    public static final String[] BAN_TERMS = {"30m", "1d", "1w", "1mo", "perm"};
    public static final String[] UNBAN_TERMS = {"lift", "30m", "1d", "1w", "1mo", "perm"};
    /** The ban ladder's open index = {@code 1d} (a bare {@code /ban #id} bans for
     *  1d); the unban ladder's = {@link #UNBAN_TERM_OPEN}. */
    public static final int BAN_TERM_OPEN = 1;
    /** The unban ladder's open index = {@code 1d}
     *  ({@code UNBAN_TERMS[UNBAN_TERM_OPEN]}): an untouched
     *  {@code /banned} menu row shows {@code 1d} and Enter RE-TIMES the entry ({@code /banned #<n> 1d});
     *  {@code lift} stays a rung (Left x2 from the open rung pastes {@code /unban #<n>}).
     *  That open rung is the bare-{@code /banned} menu's; a bare-{@code /unban} menu
     *  opens at {@code lift} via {@link #openedAtLift} (the chat lane remembers the verb typed). */
    public static final int UNBAN_TERM_OPEN = 2;
    public static final int KEY_RETURN = 28;
    public static final int KEY_ESCAPE = 1;
    public static final int KEY_BACK = 14;
    public static final int KEY_UP = 200;
    public static final int KEY_DOWN = 208;
    public static final int KEY_LEFT = 203;
    public static final int KEY_RIGHT = 205;
    public static final int KEY_PRIOR = 201;    // PgUp
    public static final int KEY_NEXT = 209;     // PgDn
    /** OPEN + Tab drops the listing like Escape but the press PASSES (the tab
     *  map opens on the same press — the {@code ChatInputLaw} twin; a modal must never make the
     *  engine's own Tab dead). */
    public static final int KEY_TAB = 15;

    /** The idle law: prompt and panel self-clear this long after the last activity. */
    public static final long IDLE_TIMEOUT_MILLIS = 30_000L;

    /** Filter buffer cap — tokens are ≤64 chars and target labels ≤24; 32 typed chars already
     *  over-specifies any row, and the cap bounds the per-keystroke refilter work. */
    public static final int FILTER_MAX_CHARS = 32;

    /** Picker mode (the ladder as amended by SS405 — auto-open, no PROMPT state). */
    public enum Mode { IDLE, PARKED, OPEN }

    /** What one fed keyboard event did. The caller consumes on everything except {@link #PASS}. */
    public enum Result {
        /** Not consumed — IDLE/PARKED always (the panel is the only key-owning mode). */
        PASS,
        /** Swallowed by the panel: consume, no selection. */
        CONSUMED,
        /** A row was selected: consume; {@link #selectedCommand()} carries the send string;
         *  the listing is dropped (auto-close on selection). */
        SELECTED,
        /** Escape closed the panel: consume; the listing is dropped. */
        CLOSED
    }

    private final int visibleRows;

    private Mode mode = Mode.IDLE;
    private int kind;
    private List<ClientListingRecord.Entry> entries = List.of();
    /** Hull id → hull NAME for the offered /ship listing (built once
     *  at landing by {@link #resolveShipHullNames}); the filter matches the names. Empty for every other offer. */
    private Map<String, String> hullNames = Map.of();
    private int totalAvailable;
    private final StringBuilder filter = new StringBuilder();
    private List<ClientListingRecord.Entry> filtered = List.of();
    private int cursor;     // index into the FILTERED view
    private int scrollTop;  // first visible index into the FILTERED view
    private long lastActivityMillis;
    private String selectedCommand;

    /**
     * @param visibleRows the panel's row window (the LANE computes it from measured geometry —
     *        the fewer-rows-when-the-band-runs-short fallback lives there, never here);
     *        clamped to 1..24 so law arithmetic is always sane.
     */
    public ListPickerLaw(int visibleRows) {
        this.visibleRows = Math.max(1, Math.min(24, visibleRows));
    }

    public Mode mode() {
        return mode;
    }

    /** True while the modal panel owns the keyboard (the lane blocks ship verbs per frame). */
    public boolean isOpen() {
        return mode == Mode.OPEN;
    }

    /** The held listing's kind ({@link ClientListingReader#KIND_SHIP} / MODULE / TRANSFER);
     *  meaningful outside IDLE only. */
    public int kind() {
        return kind;
    }

    /** The kind of the listing the last SELECTED verdict came from. Selection DROPS the listing
     *  (kind() reads 0 after it), so a lane routing on the kind after the verdict must read this —
     *  a past defect: /settings picks were sent as chat because the lane read kind() post-drop. */
    private int selectedKind;
    /** The row ID an in-place Left/Right step was taken on; the next
     *  {@link #offerListing} lands the cursor on that id (-1 = none pending). An ID, never the
     *  filtered-view index: the re-offer clears the filter (review find). */
    private long keepRowId = -1L;
    /** The scroll offset the kept row's re-offer restores (clamped, cursor kept visible). */
    private int keepScrollTop;

    public int selectedKind() {
        return selectedKind;
    }

    /** The last SELECTED verdict's row = the cursor's
     *  FILTERED-view index at the selection (== the listing row while no filter is typed); -1 before
     *  the first. Captured because selection DROPS the listing (cursorIndex() reads 0 after it). */
    private int selectedRow = -1;
    /** The last SELECTED row's term-ladder rung ({@link #rowTerm} — only a ladder constant, never the
     *  raw value) on a /ban or /unban pick; null on every other kind and before the first. */
    private String selectedRung;

    public int selectedRow() {
        return selectedRow;
    }

    public String selectedRung() {
        return selectedRung;
    }

    /** Rows in the held listing (the wire-capped total the panel can browse). */
    public int totalEntries() {
        return entries.size();
    }

    /** The server's full source count before its wire cap — "+N more" renders from this. */
    public int totalAvailable() {
        return totalAvailable;
    }

    /** The live filter string (may be empty, never null). */
    public String filterText() {
        return filter.toString();
    }

    /** The filtered view's size (== totalEntries when the filter is empty). */
    public int filteredCount() {
        return filtered.size();
    }

    /** Cursor index into the FILTERED view (0-based; meaningful while OPEN and non-empty). */
    public int cursorIndex() {
        return cursor;
    }

    /** First visible filtered index — the header's {@code i} in "i–j of N" is this + 1. */
    public int firstVisibleIndex() {
        return scrollTop;
    }

    /** The visible window of the filtered view, in listing order (≤ visibleRows entries). */
    public List<ClientListingRecord.Entry> visibleWindow() {
        int end = Math.min(filtered.size(), scrollTop + visibleRows);
        return List.copyOf(filtered.subList(Math.min(scrollTop, end), end));
    }

    /** The last {@link Result#SELECTED} send string (grammar of record); null before the first. */
    public String selectedCommand() {
        return selectedCommand;
    }

    /**
     * Offer a COMPLETE assembled listing (the lane assembles LSTD frames; a partial listing
     * never reaches the law). Single-slot latest-wins in every mode; {@code chatOpen} parks the
     * arrival (the collision law — the panel OPENs at box close via {@link #tick}), otherwise
     * the panel OPENs directly (typing the command is the trigger). Empty listings
     * are ignored (the server's L3 law keeps empty censuses on their short CHTD reply).
     */
    public void offerListing(int kind, List<ClientListingRecord.Entry> entries,
            int totalAvailable, boolean chatOpen, long nowMillis) {
        offerListing(kind, entries, totalAvailable, chatOpen, nowMillis, Map.of());
    }

    /** {@link #offerListing(int, List, int, boolean, long)} with the
     *  /ship listing's hull names ({@link #resolveShipHullNames}, resolved once at landing; {@code null} = none),
     *  so the filter also matches a row's hull NAME. Every offer replaces the map (no stale names). */
    public void offerListing(int kind, List<ClientListingRecord.Entry> entries,
            int totalAvailable, boolean chatOpen, long nowMillis, Map<String, String> hullNamesById) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        this.kind = kind;
        this.entries = List.copyOf(entries);
        this.hullNames = hullNamesById == null ? Map.of() : hullNamesById;
        this.totalAvailable = totalAvailable;
        filter.setLength(0);
        refilter();
        if (keepRowId >= 0L) {
            // The stepped row keeps the cursor — found by id in the fresh view; and since toggling a
            // /settings row used to shift the list, the view keeps its scroll too.
            for (int i = 0; i < filtered.size(); i++) {
                if (filtered.get(i).hullWireId() == keepRowId) {
                    scrollTop = Math.max(0, Math.min(keepScrollTop, filtered.size() - visibleRows));
                    moveCursor(i);
                    break;
                }
            }
            keepRowId = -1L;
        }
        lastActivityMillis = nowMillis;
        if (mode == Mode.OPEN) {
            return; // stay open on the fresh content (cursor/filter already reset above)
        }
        mode = chatOpen ? Mode.PARKED : Mode.OPEN;
    }

    /**
     * Per-frame housekeeping: PARKED auto-OPENs at box close (SS405 — chat never re-opens over
     * an OPEN panel: the modal consumes RETURN, so the reverse move is unreachable — TRUE only
     * under the plugin's MODAL-FIRST feed order, the fix: with chat fed first,
     * chat's closed-box RETURN opened the box BEFORE this law's consume could bind), and the
     * uniform {@link #IDLE_TIMEOUT_MILLIS} staleness law (PARKED/OPEN alike — activity
     * = arrival, auto-open, or any consumed keystroke) on every kind but {@link #KIND_LOCAL_HELP}
     * (a reference the player reads, never stale; Esc / Tab / a round reset close it). Call once
     * per render frame, before drawing.
     */
    public void tick(boolean chatOpen, long nowMillis) {
        if (mode == Mode.IDLE) {
            return;
        }
        if (kind != KIND_LOCAL_HELP && nowMillis - lastActivityMillis > IDLE_TIMEOUT_MILLIS) {
            drop();
            return;
        }
        if (mode == Mode.PARKED && !chatOpen) {
            mode = Mode.OPEN;
            lastActivityMillis = nowMillis; // the panel's own 30 s starts at its appearance
        }
    }

    /** Round end / match reset: drop everything (the auto-close-on-round-end law). */
    public void dropForRound() {
        drop();
    }

    /**
     * Feed one keyboard event ({@link ChatInputLaw#onKeyboardEvent} argument contract verbatim:
     * all four reads happen caller-side BEFORE any consume; mouse events are never fed — aim,
     * fire and the mouse shield toggle stay live while the panel is open).
     */
    public Result onKeyboardEvent(boolean keyDown, boolean repeat, int keyCode, char eventChar,
            long nowMillis) {
        if (mode == Mode.IDLE || mode == Mode.PARKED) {
            return Result.PASS;
        }
        // OPEN: the modal owns the keyboard; every branch below consumes.
        lastActivityMillis = nowMillis;
        if (keyDown && !repeat && keyCode == KEY_RETURN) {
            if (kind == KIND_LOCAL_HELP) {
                return Result.CONSUMED; // A help row is text, never a command; the menu stays open
            }
            if (filtered.isEmpty()) {
                return Result.CONSUMED; // nothing to select — the empty-view Enter is inert
            }
            ClientListingRecord.Entry pick = filtered.get(cursor);
            selectedCommand = selectionCommand(kind, pick);
            selectedKind = kind;
            selectedRow = cursor;
            selectedRung = rowTerm(kind, pick);
            // Enter on a settings row keeps its place like Left/Right — the lane re-offers at once
            long keep = kind == KIND_LOCAL_SETTINGS ? pick.hullWireId() : -1L;
            int keepTop = scrollTop;
            drop();
            keepRowId = keep;
            keepScrollTop = keepTop;
            return Result.SELECTED;
        }
        if (keyDown && !repeat && keyCode == KEY_ESCAPE) {
            drop();
            return Result.CLOSED;
        }
        if (keyDown && !repeat && keyCode == KEY_TAB) {
            drop();
            return Result.PASS; // Dropped AND passed — the tab map opens
        }
        if ((keyDown || repeat) && keyCode == KEY_BACK) {
            if (filter.length() > 0) {
                filter.setLength(filter.length() - 1);
                refilter();
            }
            return Result.CONSUMED; // empty-filter BACK is a no-op — never an accidental close
        }
        if ((keyDown || repeat) && (keyCode == KEY_UP || keyCode == KEY_DOWN)) {
            moveCursor(keyCode == KEY_DOWN ? 1 : -1);
            return Result.CONSUMED;
        }
        if (keyDown && !repeat && (keyCode == KEY_LEFT || keyCode == KEY_RIGHT)
                && kind == ClientListingReader.KIND_MUTE) {
            // Left/Right on a /mute row toggles IN PLACE — the
            // same `/mute #id` send Enter makes, WITHOUT the drop, and the row's mark flips
            // locally so the panel shows the new state at once (the server's reply line stays
            // the record; a refused toggle leaves a stale mark the next /mute re-list corrects —
            // client-side at equal fidelity beats a second listing round trip per tap). Never
            // on repeat: each tap is a chat send.
            if (filtered.isEmpty()) {
                return Result.CONSUMED;
            }
            ClientListingRecord.Entry pick = filtered.get(cursor);
            selectedCommand = selectionCommand(kind, pick);
            selectedKind = kind;
            selectedRow = cursor;
            selectedRung = null;
            keepRowId = pick.hullWireId();
            replaceRow(pick, withMuteMarkFlipped(pick));
            return Result.SELECTED;
        }
        if (keyDown && !repeat && (keyCode == KEY_LEFT || keyCode == KEY_RIGHT)
                && kind == KIND_LOCAL_SETTINGS) {
            // Left/Right on a settings ROOT row steps its
            // value IN PLACE — SELECTED without the drop; the lane applies the step and
            // re-offers ROOT, and keepCursor puts the cursor back on the same row. Never on
            // repeat: each step is a settings-file save.
            if (filtered.isEmpty()) {
                return Result.CONSUMED;
            }
            ClientListingRecord.Entry pick = filtered.get(cursor);
            selectedCommand = "settings #" + pick.hullWireId()
                    + (keyCode == KEY_RIGHT ? " +1" : " -1");
            selectedKind = kind;
            selectedRow = cursor;
            selectedRung = null;
            keepRowId = pick.hullWireId();
            keepScrollTop = scrollTop;
            return Result.SELECTED;
        }
        if (keyDown && !repeat && (keyCode == KEY_LEFT || keyCode == KEY_RIGHT)
                && kind == ClientListingReader.KIND_BUILDS) {
            // Left/Right on a /builds row flips its FLY NEXT cell IN PLACE — a
            // LOCAL grammar the lane applies (it holds the one YES); no drop, never sent. Builds rows carry
            // hullWireId 0, so the token is the row identity.
            if (filtered.isEmpty()) {
                return Result.CONSUMED;
            }
            selectedCommand = "flynext " + filtered.get(cursor).token();
            selectedKind = kind;
            selectedRow = cursor;
            selectedRung = null;
            return Result.SELECTED;
        }
        if (keyDown && !repeat && (keyCode == KEY_LEFT || keyCode == KEY_RIGHT)
                && kind == ClientListingReader.KIND_SWITCHTEAMS) {
            // Left/Right flips the row's MODE mark IN PLACE (at respawn ⇄ instant);
            //
            // one listing per player, Enter issues the teamswitch, Escape closes —
            // LOCAL, nothing sent, no drop (the /builds fly-next shape, never the /mute
            // send-in-place one); Enter pastes the mode the row shows, Escape closes as always.
            if (filtered.isEmpty()) {
                return Result.CONSUMED;
            }
            ClientListingRecord.Entry pick = filtered.get(cursor);
            replaceRow(pick, withSwitchModeFlipped(pick));
            return Result.CONSUMED;
        }
        if (keyDown && !repeat && (keyCode == KEY_LEFT || keyCode == KEY_RIGHT)
                && (kind == ClientListingReader.KIND_SPAWNFOE
                        || kind == ClientListingReader.KIND_SPAWNFRIEND)) {
            // An officer / N/A option per ship row:
            //
            // the /switchteams shape verbatim — Left/Right flips the row's OFFICER mark IN PLACE
            // (N/A ⇄ officer, marks 18⇄28 / 23⇄29), LOCAL, nothing sent, no drop; Enter pastes
            // the option the row shows (selectionCommand appends the trailing `officer` keyword).
            if (filtered.isEmpty()) {
                return Result.CONSUMED;
            }
            ClientListingRecord.Entry pick = filtered.get(cursor);
            replaceRow(pick, withSpawnOfficerFlipped(pick));
            return Result.CONSUMED;
        }
        if (keyDown && !repeat && (keyCode == KEY_LEFT || keyCode == KEY_RIGHT)
                && (kind == ClientListingReader.KIND_BAN || kind == ClientListingReader.KIND_UNBAN)) {
            /* The /switchteams shape — Left/Right steps the
             * row's TERM one rung along its ladder IN PLACE (clamped), LOCAL, nothing sent, no drop;
             * Enter pastes the term the row shows. CONSUMED, never the /mute SELECTED shape (that
             * would send a /ban per arrow press). */
            if (filtered.isEmpty()) {
                return Result.CONSUMED;
            }
            ClientListingRecord.Entry pick = filtered.get(cursor);
            replaceRow(pick, withTermStepped(kind, pick, keyCode == KEY_RIGHT ? 1 : -1));
            return Result.CONSUMED;
        }
        if ((keyDown || repeat) && (keyCode == KEY_PRIOR || keyCode == KEY_NEXT)) {
            moveCursor(keyCode == KEY_NEXT ? visibleRows : -visibleRows);
            return Result.CONSUMED;
        }
        if ((keyDown || repeat) && keyCode == OPEN_KEY) {
            return Result.CONSUMED; // the retired trigger never types into the filter (habit presses)
        }
        if ((keyDown || repeat) && eventChar >= 0x20 && eventChar != 0x7f) {
            if (filter.length() < FILTER_MAX_CHARS) {
                filter.append(eventChar);
                refilter();
            }
            return Result.CONSUMED;
        }
        return Result.CONSUMED; // KEY_UP events, modifiers, control chars: swallowed
    }

    /**
     * The selection grammar OF RECORD (selection rides the EXISTING chat commands — zero
     * new UP surface; resolution stays server-authoritative exact-fold): /ship and /module send
     * the row's token verbatim; /transfer sends the {@code #<hullWireId>} form (stem tokens can
     * collide across same-class hulls — the wire id is the collision-proof grammar the client
     * mod already uses programmatically). Static and pure so the selftest pins all three.
     */
    public static String selectionCommand(int kind, ClientListingRecord.Entry entry) {
        if (kind == KIND_LOCAL_HELP) {
            return null; // No command for a help row (wire text) — never the "/ship " fall-through
        }
        if (kind == KIND_LOCAL_SETTINGS) {
            // W12 VOICE L5: a LOCAL grammar the lane parses and applies through
            // VoiceSettingsMenu; it is never queued for send.
            return "settings #" + entry.hullWireId();
        }
        if (kind == ClientListingReader.KIND_TRANSFER) {
            return "/transfer #" + entry.hullWireId();
        }
        if (kind == ClientListingReader.KIND_MUTE) {
            // W12 VOICE L3 (brief D13): the server's VoiceMuteCommand grammar is
            // the same collision-proof `#<hullWireId>` form as /transfer; it toggles mute.
            return "/mute #" + entry.hullWireId();
        }
        if (kind == ClientListingReader.KIND_KICK) {
            // The AdminKickCommand grammar's same #wireId form; admin-gated
            // server-side (the picker only ever pastes — resolution stays the server's).
            return "/kick #" + entry.hullWireId();
        }
        if (kind == ClientListingReader.KIND_BAN) {
            /* The /kick paste verbatim — the EXISTING
             * `/ban #<id>` grammar (zero new UP surface); self/admin refusal stays the server's.
             * + the row's ladder term (an untouched row = BAN_TERM_OPEN, 1d). */
            return "/ban #" + entry.hullWireId() + " " + rowTerm(kind, entry);
        }
        if (kind == ClientListingReader.KIND_UNBAN) {
            /* The EXISTING `/unban #<n>` grammar; n = the
             * entry's slot in the CALLER's pinned ban numbering (the /bans numbering that session
             * sees — NOT the row's position, not monotonic; the server resolves n by KEY with a
             * re-check), so the paste is the served id, never re-derived.
             * The `lift` rung keeps that paste byte-identical; any other rung re-times the
             * entry as `/banned #<n> <term>` — never `/banned #<n> lift`. The row OPENS at
             * UNBAN_TERM_OPEN (1d), so an untouched row's Enter re-times;
             * lifting takes Left x2. */
            String term = rowTerm(kind, entry);
            return UNBAN_TERMS[0].equals(term) ? "/unban #" + entry.hullWireId()
                    : "/banned #" + entry.hullWireId() + " " + term;
        }
        if (kind == ClientListingReader.KIND_SWITCHTEAMS) {
            // The /kick #wireId form + the row's MARK picks the mode (the /share
            // mark-picks-verb idiom); admin-gated server-side, the seat resolved there.
            return "/switchteams #" + entry.hullWireId()
                    + (entry.markCode() == ClientListingReader.MARK_SWITCH_INSTANT
                            ? " instant" : " respawn");
        }
        if (kind == ClientListingReader.KIND_DEBUGSHIP) {
            // The row token pastes back through the DEBUG verb — a registry-only
            // id pasted as /ship would DENY against the mod allowlist (the defect the
            // kind split fixes). Admin-gated server-side like /kick.
            return "/debugships " + entry.token();
        }
        if (kind == ClientListingReader.KIND_SPAWNFOE) {
            // Count stays the typed power path — a selection spawns 1.
            // The row's MARK picks the officer option (the /switchteams idiom) — the
            // server grammar is `<variant> [count] [officer] [@x,y]`, so the keyword rides right
            // after the variant and ChatLane.withSpawnFoeCursorPose still appends the pose LAST.
            return "/spawnfoe " + entry.token()
                    + (entry.markCode() == ClientListingReader.MARK_SPAWNFOE_OFFICER
                            ? " " + SPAWN_OFFICER_KEYWORD : "");
        }
        if (kind == ClientListingReader.KIND_SPAWNFRIEND) {
            // The player-side twin (same count law; cursor pose appended by
            // ChatLane.withSpawnFoeCursorPose like /spawnfoe). The same officer mark law applies.
            return "/spawnfriend " + entry.token()
                    + (entry.markCode() == ClientListingReader.MARK_SPAWNFRIEND_OFFICER
                            ? " " + SPAWN_OFFICER_KEYWORD : "");
        }
        if (kind == ClientListingReader.KIND_WAVE) {
            // The faction row pastes the one-word shorthand — the next wave
            // becomes that faction (admin-gated server-side like every wave verb).
            return "/wave " + entry.token();
        }
        if (kind == ClientListingReader.KIND_SHARE) {
            // Peer-build sharing v2 (variant-maker-peer-builds.md): the row's share bit
            // picks the verb — an OFF row shares, an ON row unshares; the token is the canonical
            // id (the /customs → /ship <token> idiom; ownership re-checked server-side).
            return (entry.markCode() == ClientListingReader.MARK_SHARE_ON ? "/unshare " : "/share ")
                    + entry.token();
        }
        if (kind == ClientListingReader.KIND_BUILDS) {
            // A /builds pick is a LOCAL save (PeerBuilds.saveFromRegistry) — a local
            // grammar the lane applies like `settings #<id>`; it is never queued for send.
            return "savebuild " + entry.token();
        }
        if (kind == ClientListingReader.KIND_MODULE) {
            return "/module " + entry.token();
        }
        return "/ship " + entry.token();
    }

    /** A /ship row's display name — the ship row's OPTIONAL occupantLabel
     *  (marks 0..2, the seat-name law at decode), else null. DISPLAY ONLY: the row's paste stays
     *  {@code /ship <token>} ({@link #selectionCommand}); the picker lane leads the row text with it. */
    public static String shipRowName(int kind, ClientListingRecord.Entry e) {
        return kind == ClientListingReader.KIND_SHIP ? e.occupantLabel() : null;
    }

    /** ASCII case-fold CONTAINS over token + targetLabel (the filter law), plus the occupantLabel
     *  on KIND_UNBAN (an /unban row's NAME lives there) and on a
     *  /ship row, its display name per {@link #shipRowName} and its hull name ({@link #hullNameMatches}); the
     *  fold mirrors the server grammar's ascii-fold — non-ASCII passes through unfolded, which only
     *  ever under-matches the unicode occupant labels every other kind deliberately does not search. */
    private void refilter() {
        String needle = asciiLower(filter.toString());
        if (needle.isEmpty()) {
            filtered = entries;
        } else {
            List<ClientListingRecord.Entry> out = new ArrayList<>();
            for (ClientListingRecord.Entry e : entries) {
                if (asciiLower(e.token()).contains(needle)
                        || (e.targetLabel() != null && asciiLower(e.targetLabel()).contains(needle))
                        // An /unban row's NAME lives
                        // in the occupantLabel (targetLabel = day + key), so the menu is searchable
                        // by the banned player's name — the one kind whose occupant is searched.
                        || (kind == ClientListingReader.KIND_UNBAN && e.occupantLabel() != null
                                && asciiLower(e.occupantLabel()).contains(needle))
                        // A /ship row's optional display name
                        // rides the occupantLabel too, so the list is searchable by name
                        || shipRowName(kind, e) != null && asciiLower(shipRowName(kind, e)).contains(needle)
                        || hullNameMatches(e, needle)) {
                    out.add(e);
                }
            }
            filtered = out;
        }
        cursor = 0;     // a filter edit re-tops the view — predictable incremental search
        scrollTop = 0;
    }

    /** A /ship row's hull NAME (resolved at landing) contains the
     *  needle — the hull id itself rides targetLabel, which the token/targetLabel clause already searches. */
    private boolean hullNameMatches(ClientListingRecord.Entry e, String needle) {
        if (kind != ClientListingReader.KIND_SHIP || e.targetLabel() == null) {
            return false;
        }
        String hull = hullNames.get(e.targetLabel());
        return hull != null && asciiLower(hull).contains(needle);
    }

    /**
     * The /ship listing's hull id → hull NAME map, built ONCE when
     * the listing lands — never per rendered frame (the row text is drawn every frame; a settings lookup per
     * row per frame would ride the render thread for nothing). One {@code hullNameOf} call per DISTINCT hull
     * id among the KIND_SHIP rows that carry one (in targetLabel); a null or blank answer maps the id to
     * itself, so the row still shows something and the filter still matches. {@code hullNameOf} is the
     * caller's fail-soft resolver and must not throw. Any other kind, or no hull on any row = an empty map.
     */
    public static Map<String, String> resolveShipHullNames(int kind, List<ClientListingRecord.Entry> entries,
            Function<String, String> hullNameOf) {
        if (kind != ClientListingReader.KIND_SHIP || entries == null || entries.isEmpty()) {
            return Map.of();
        }
        HashMap<String, String> out = new HashMap<>();
        for (ClientListingRecord.Entry e : entries) {
            String hullId = e.targetLabel();
            if (hullId == null || out.containsKey(hullId)) {
                continue;
            }
            String name = hullNameOf.apply(hullId);
            out.put(hullId, name == null || name.isBlank() ? hullId : name);
        }
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    private void moveCursor(int delta) {
        if (filtered.isEmpty()) {
            return;
        }
        cursor = Math.max(0, Math.min(filtered.size() - 1, cursor + delta));
        if (cursor < scrollTop) {
            scrollTop = cursor;
        } else if (cursor > scrollTop + visibleRows - 1) {
            scrollTop = cursor - visibleRows + 1;
        }
    }

    /** The same row with its mute mark flipped (the in-place toggle; other kinds untouched). */
    static ClientListingRecord.Entry withMuteMarkFlipped(ClientListingRecord.Entry e) {
        int mark = e.markCode() == ClientListingReader.MARK_MUTE_ON
                ? ClientListingReader.MARK_MUTE_OFF : ClientListingReader.MARK_MUTE_ON;
        return new ClientListingRecord.Entry(e.token(), mark, e.claimedNextRespawn(),
                e.targetLabel(), e.occupantLabel(), e.hullWireId());
    }

    /** The same row with its {@code /switchteams} mode mark flipped (the local
     *  toggle: at respawn ⇄ instant; other kinds never reach it). */
    static ClientListingRecord.Entry withSwitchModeFlipped(ClientListingRecord.Entry e) {
        int mark = e.markCode() == ClientListingReader.MARK_SWITCH_INSTANT
                ? ClientListingReader.MARK_SWITCH_RESPAWN : ClientListingReader.MARK_SWITCH_INSTANT;
        return new ClientListingRecord.Entry(e.token(), mark, e.claimedNextRespawn(),
                e.targetLabel(), e.occupantLabel(), e.hullWireId());
    }

    /** The same spawn row with its officer mark flipped (the local toggle: N/A ⇄ officer,
     *  18⇄28 for /spawnfoe rows, 23⇄29 for /spawnfriend rows; other marks pass through unchanged
     *  so an unexpected mark can never be promoted into a spawn grammar it does not carry). Public
     *  and pure so the selftest (another package) pins the closed map. */
    public static ClientListingRecord.Entry withSpawnOfficerFlipped(ClientListingRecord.Entry e) {
        int mark;
        switch (e.markCode()) {
            case ClientListingReader.MARK_SPAWNFOE_PLAIN:
                mark = ClientListingReader.MARK_SPAWNFOE_OFFICER;
                break;
            case ClientListingReader.MARK_SPAWNFOE_OFFICER:
                mark = ClientListingReader.MARK_SPAWNFOE_PLAIN;
                break;
            case ClientListingReader.MARK_SPAWNFRIEND_PLAIN:
                mark = ClientListingReader.MARK_SPAWNFRIEND_OFFICER;
                break;
            case ClientListingReader.MARK_SPAWNFRIEND_OFFICER:
                mark = ClientListingReader.MARK_SPAWNFRIEND_PLAIN;
                break;
            default:
                return e;
        }
        return new ClientListingRecord.Entry(e.token(), mark, e.claimedNextRespawn(),
                e.targetLabel(), e.occupantLabel(), e.hullWireId());
    }

    /** The term a /ban or /unban row shows and pastes: its
     *  {@code Entry.value} when that is a rung of the kind's ladder, else (null, or anything foreign)
     *  the open rung ({@code BAN_TERMS[BAN_TERM_OPEN]} / {@code UNBAN_TERMS[UNBAN_TERM_OPEN]}, both
     *  {@code 1d}); null on every other
     *  kind. Only a ladder constant is ever returned — never the raw value. Public and pure so the
     *  lane's static rowText and the selftest read the same rung the paste does. */
    public static String rowTerm(int kind, ClientListingRecord.Entry e) {
        String[] ladder = termLadder(kind);
        return ladder == null ? null : ladder[termIndex(kind, ladder, e)];
    }

    /** The same KIND_UNBAN rows with every term set
     *  to {@code lift} (index 0) — the shape a bare {@code /unban} opens at, so an untouched Enter LIFTS
     *  ({@code /unban #<n>}); a bare {@code /banned} keeps the served rows (open rung
     *  {@link #UNBAN_TERM_OPEN}, Enter re-times). Both verbs parse to the SAME listing server-side
     *  (UNBAN_LIST), so the opener is client memory: {@code ListPickerLane} applies this at assembly
     *  when the last bare moderation verb typed was {@code /unban}. Pure; any other kind returns as-is. */
    public static List<ClientListingRecord.Entry> openedAtLift(int kind, List<ClientListingRecord.Entry> rows) {
        if (kind != ClientListingReader.KIND_UNBAN) {
            return rows;
        }
        List<ClientListingRecord.Entry> out = new ArrayList<>(rows.size());
        for (ClientListingRecord.Entry e : rows) {
            out.add(new ClientListingRecord.Entry(e.token(), e.markCode(), e.claimedNextRespawn(),
                    e.targetLabel(), e.occupantLabel(), e.hullWireId(), UNBAN_TERMS[0]));
        }
        return out;
    }

    /** The chat lane's memory rule, pure. {@code TRUE} = a bare
     *  {@code /unban} (the next KIND_UNBAN menu opens at {@code lift}), {@code FALSE} = a bare
     *  {@code /banned} (the 1d open rung), {@code null} = any other line (no change); case-insensitive,
     *  trimmed. {@code ListPickerLane.noteSentLine} delegates here so row B9 pins the lane memory. */
    public static Boolean unbanOpenerFor(String sent) {
        String verb = sent == null ? "" : sent.trim();
        if (verb.equalsIgnoreCase("/unban")) {
            return Boolean.TRUE;
        }
        return verb.equalsIgnoreCase("/banned") ? Boolean.FALSE : null;
    }

    /** The same /ban or /unban row stepped one rung along its
     *  ladder ({@code dir} > 0 = Right, < 0 = Left), CLAMPED at both ends (the VoiceSettingsMenu
     *  ladder precedent — a wrap would jump {@code perm} to {@code lift}); returns {@code e} itself
     *  when clamped, when {@code dir} is 0, or on any other kind. The rung rides {@code Entry.value}
     *  (outside equals, so {@code replaceRow} still finds the row). */
    public static ClientListingRecord.Entry withTermStepped(int kind, ClientListingRecord.Entry e, int dir) {
        String[] ladder = termLadder(kind);
        if (ladder == null) {
            return e;
        }
        int from = termIndex(kind, ladder, e);
        int to = Math.max(0, Math.min(ladder.length - 1, from + Integer.signum(dir)));
        if (to == from) {
            return e;
        }
        return new ClientListingRecord.Entry(e.token(), e.markCode(), e.claimedNextRespawn(),
                e.targetLabel(), e.occupantLabel(), e.hullWireId(), ladder[to]);
    }

    private static String[] termLadder(int kind) {
        return kind == ClientListingReader.KIND_BAN ? BAN_TERMS
                : kind == ClientListingReader.KIND_UNBAN ? UNBAN_TERMS : null;
    }

    private static int termIndex(int kind, String[] ladder, ClientListingRecord.Entry e) {
        String v = e.value();
        for (int i = 0; v != null && i < ladder.length; i++) {
            if (ladder[i].equals(v)) {
                return i;
            }
        }
        return kind == ClientListingReader.KIND_BAN ? BAN_TERM_OPEN : UNBAN_TERM_OPEN;
    }

    /** Swap one row in BOTH views without touching the filter, cursor or scroll. */
    private void replaceRow(ClientListingRecord.Entry from, ClientListingRecord.Entry to) {
        List<ClientListingRecord.Entry> all = new ArrayList<>(entries);
        int i = all.indexOf(from);
        if (i >= 0) {
            all.set(i, to);
            entries = List.copyOf(all);
        }
        List<ClientListingRecord.Entry> view = new ArrayList<>(filtered);
        int j = view.indexOf(from);
        if (j >= 0) {
            view.set(j, to);
            filtered = view;
        }
    }

    private void drop() {
        mode = Mode.IDLE;
        kind = 0;
        keepRowId = -1L;
        entries = List.of();
        totalAvailable = 0;
        filter.setLength(0);
        filtered = List.of();
        cursor = 0;
        scrollTop = 0;
    }

    private static String asciiLower(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];
            if (c >= 'A' && c <= 'Z') {
                cs[i] = (char) (c + 0x20);
            }
        }
        return new String(cs);
    }
}
