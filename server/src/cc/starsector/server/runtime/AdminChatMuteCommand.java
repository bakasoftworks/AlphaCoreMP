package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The admin text-chat mute verbs, {@code /chatmute} and {@code /chatunmute}, as a PURE verdict over
 * a snapshot the door takes under its lock (the {@link AdminBanCommand} shape): this class reads
 * no door state, mutates nothing and logs nothing. It answers the private reply lines and, for a
 * mute or a lift that passed every refusal, the DEPOSIT ({@link Result#muteTarget} /
 * {@link Result#liftEntry}) for the caller to apply to its {@link ChatMuteSet} under
 * {@code door.lock} and report with {@link #muteReceipt} / {@link #liftReceipt}, so the receipt
 * states the real outcome. The admin gate is the caller's: this class never sees the session's
 * admin bit.
 *
 * <p>Why a list and a pinned {@code #n}, not a wire id: an observer has no hull id and its picker
 * handle is 13 digits nobody types, so the bare {@code /chatmute} prints a short numbered text list
 * (newest sender first, the likely spammer on top) and {@code #n} names a row of THAT caller's last
 * list; a name works too (observers are reachable that way). {@code /chatunmute} numbers every
 * entry, players who left included (their label was captured at mute time), with a numbering that
 * stays stable for the caller's session so an older view's {@code #n} never lifts a different mute.
 *
 * <p>Wire bound: every line here rides one chat frame ({@link ChatFrameCodec#TEXT_MAX_CHARS} = 120).
 * Receipts and notices carry a seat or observer slot and counts only, never a player name (a
 * 24-char name in a receipt could pass the cap, and the encode throw would fault the ADMIN's own
 * session); list rows carry the name folded to printable ASCII and clipped to 24 chars, and every
 * shown number is clamped, so each template's worst case stays under the cap by construction.
 *
 */
public final class AdminChatMuteCommand {

    /** The first line of every {@code /chatmute} list (a constant, so the command audit row's
     *  first-reply field never carries a listed player's name). */
    public static final String LIST_HEADER = "text chat, newest sender first - /chatmute #n mutes that row";
    public static final String NOBODY = "nobody to mute (admins are exempt)";
    public static final String NO_PIN = "type /chatmute first";
    public static final String STALE = "that player left - type /chatmute again";
    public static final String NO_SUCH_ROW = "no such row - type /chatmute for the list";
    public static final String NO_SUCH_PLAYER = "no such player - type /chatmute for the list";
    public static final String SELF = "cannot mute yourself";
    public static final String TARGET_ADMIN = "cannot mute an admin";
    /** Neither an id key nor an address key: muting would key on nothing (or on a value every
     *  key-less session shares), so the host is pointed at the session-scoped tool. */
    public static final String NO_KEY = "no id to mute - use /kick";
    public static final String ALREADY_MUTED = "already muted (see /chatunmute)";
    public static final String CAP_FULL = "the mute list is full (" + ChatMuteSet.CAP
            + ") - lift one with /chatunmute, or use /ban";
    /** The first line of every {@code /chatunmute} page (a constant, for the same audit reason). */
    public static final String UNMUTE_HEADER = "text chat mutes, newest first - /chatunmute #n lifts one";
    public static final String NO_MUTES = "no text chat mutes";
    public static final String NO_SUCH_PAGE = "no such page (see /chatunmute)";
    public static final String NO_UNMUTE_PIN = "type /chatunmute first";
    public static final String NO_SUCH_MUTE = "no such mute - type /chatunmute for the list";
    /** To the target, once, when the mute lands (only if they are live). */
    public static final String MUTED_NOTICE =
            "an admin muted your text chat until the server stops - you can still play";
    /** To the target, once, when a lift frees them (only if they are live). */
    public static final String LIFTED_NOTICE = "an admin lifted your text chat mute";
    /** To the muted sender, once per dropped line (the chat token bucket bounds the rate). Says
     *  plainly that nobody sees the line, so a mute is never a shadow mute. */
    public static final String DROPPED_NOTICE =
            "your text chat is muted by an admin - nobody sees it; commands still work";

    /** The per-session {@code /chatunmute} numbering's bound: past it the numbering restarts from
     *  the current mutes (the one renumber), so a session's pin stays small. ≥ {@link ChatMuteSet#CAP}. */
    public static final int PIN_MAX = 4 * ChatMuteSet.CAP;
    /** Shown seats and observer slots clamp here ({@code 999+}); counts clamp at {@link #COUNT_SHOWN_MAX}. */
    static final int SLOT_SHOWN_MAX = 999;
    /** r4: the observer ORDINAL clamp. A long public serve mints past 999, so the seat clamp
     *  is too tight; 9,999 is the widest the receipt law allows — {@link #muteReceipt}'s widest shape
     *  ({@code address only … (99+ more on that address)}) is 106 chars + {@code observer 9999+} = exactly
     *  {@link ChatFrameCodec#TEXT_MAX_CHARS}; one more digit breaks the chat line law. */
    static final int ORDINAL_SHOWN_MAX = 9_999;
    static final int COUNT_SHOWN_MAX = 99;
    static final int AGE_DAYS_MAX = 999;

    /**
     * One live session as the door snapshots it under its lock. {@code observer} = the session's own
     * OBSERVER flag; {@code observerOrdinal} = its door-minted ordinal, r4 — the
     * ONE "observer N" the /ban, /mute and /chatmute menus all show, minted at admission, never a
     * position in this list; class law {@code observer == (observerOrdinal > 0)}, both fields from ONE
     * {@code PeerSession}, no wire input reaches this ctor. {@code name} = the name that player's chat
     * lines carry ({@code null} = ""), the {@code <name>} match key. The keys are the mute keys the door
     * derives ({@link ChatMuteSet}; blank = null). {@code lastSendMillis} = the door's per-session chat
     * stamp on its own clock (0 = never sent).
     */
    public record Target(long sessionId, int seatId, boolean admin, boolean observer, int observerOrdinal,
            String name, String idKeyOrNull, String srcKeyOrNull, long lastSendMillis) {
        public Target {
            if (observer != (observerOrdinal > 0)) {
                throw new IllegalArgumentException("Target: observer=" + observer + " with observerOrdinal="
                        + observerOrdinal + " (an observer is born with its ordinal, a pilot has none)");
            }
            name = name == null ? "" : name;
            idKeyOrNull = ChatMuteSet.blankToNull(idKeyOrNull);
            srcKeyOrNull = ChatMuteSet.blankToNull(srcKeyOrNull);
        }

        /** Constant: a Target carries the mute keys and a player-written name; nothing that logs or concatenates
         *  one may print either (the {@code Auth13Inputs} precedent). */
        @Override
        public String toString() {
            return "AdminChatMuteCommand.Target";
        }
    }

    /**
     * The private reply lines + at most one deposit. {@code muteTarget} = a resolved, unrefused
     * {@code /chatmute} target (the caller builds the entry and adds it under the lock);
     * {@code liftEntry} = the CURRENT stored entry a {@code /chatunmute #n} resolved to (lift it
     * under the lock). {@code menuPin} = the {@code /chatmute} rows just listed, in row order (it
     * REPLACES the caller's pin, so {@code #n} resolves against that caller's last list);
     * {@code pin} = the caller's {@code /chatunmute} numbering after a mute listing (replaces it).
     * {@code null} in either pin = leave the caller's alone.
     */
    public record Result(List<String> replyLines, Target muteTarget, ChatMuteSet.Entry liftEntry,
            List<Target> menuPin, List<ChatMuteSet.Entry> pin) {
    }

    private AdminChatMuteCommand() {
    }

    /**
     * The verdict for one admin chat-mute line.
     *
     * @param parsed              a {@code CHATMUTE_*} / {@code CHATUNMUTE_*} / {@code UNMUTE} verdict
     * @param selfSessionId       the caller's session (signed; never a valid target)
     * @param live                the live sessions in the door's session order (the caller may be among them)
     * @param snapshotNewestFirst {@link ChatMuteSet#snapshotNewestFirst} taken under the same lock
     * @param pinOrNull           the caller's {@code /chatunmute} numbering ({@code null} = never listed)
     * @param menuPinOrNull       the caller's last {@code /chatmute} rows ({@code null} = never listed)
     * @param nowMillis           now on the clock of {@link Target#lastSendMillis} and
     *                            {@link ChatMuteSet.Entry#mutedAtMillis} (one clock for both ages)
     */
    public static Result handle(ChatCommands.Parsed parsed, long selfSessionId, List<Target> live,
            List<ChatMuteSet.Entry> snapshotNewestFirst, List<ChatMuteSet.Entry> pinOrNull,
            List<Target> menuPinOrNull, long nowMillis) {
        switch (parsed.kind()) {
            case CHATMUTE_LIST:
                return list(selfSessionId, live, snapshotNewestFirst, nowMillis);
            case CHATMUTE_INDEX: {
                if (menuPinOrNull == null) {
                    return reply(NO_PIN);
                }
                int n = smallIntOrMinus(parsed.arg());
                if (n < 1 || n > menuPinOrNull.size()) {
                    return reply(NO_SUCH_ROW);
                }
                Target current = liveById(live, menuPinOrNull.get(n - 1).sessionId());
                return current == null ? reply(STALE) : resolve(current, selfSessionId, snapshotNewestFirst);
            }
            case CHATMUTE_NAME:
                return byName(parsed.arg(), selfSessionId, live, snapshotNewestFirst);
            case CHATMUTE_USAGE:
                return reply(parsed.reply() != null ? parsed.reply() : ChatCommands.CHATMUTE_USAGE);
            case CHATUNMUTE_LIST:
                return unmuteListing(1, snapshotNewestFirst, pinOrNull, nowMillis);
            case CHATUNMUTE_PAGE:
                return unmuteListing(smallIntOrMinus(parsed.arg()), snapshotNewestFirst, pinOrNull, nowMillis);
            case CHATUNMUTE_INDEX: {
                if (pinOrNull == null) {
                    return reply(NO_UNMUTE_PIN);
                }
                int n = smallIntOrMinus(parsed.arg());
                if (n < 1 || n > pinOrNull.size()) {
                    return reply(NO_SUCH_MUTE);
                }
                ChatMuteSet.Entry pinned = pinOrNull.get(n - 1);
                for (ChatMuteSet.Entry e : snapshotNewestFirst) {
                    if (e.sameKeys(pinned)) {
                        return new Result(List.of(), null, e, null, null);
                    }
                }
                return reply(NO_SUCH_MUTE); // lifted since that listing: the slot stays a gap
            }
            case UNMUTE:
                // This verdict holds no voice flag, so it never advertises the voice verb; the full
                // answer for any session is ChatCommands.unmutePointer with the serve's voice flag.
                return reply(ChatCommands.unmutePointer(true, false));
            default:
                return reply(ChatCommands.CHATMUTE_USAGE);
        }
    }

    /** The bare {@code /chatmute} list: the live sessions that are neither the caller nor an admin,
     *  newest sender first, never-sent last (ties keep the door's order), at most
     *  {@link AdminBanCommand#PAGE_ROWS} rows + a "+N more" line naming the name form. */
    private static Result list(long selfSessionId, List<Target> live, List<ChatMuteSet.Entry> snapshot,
            long nowMillis) {
        List<Target> candidates = new ArrayList<Target>(live.size());
        for (Target t : live) {
            if (t.sessionId() != selfSessionId && !t.admin()) {
                candidates.add(t);
            }
        }
        if (candidates.isEmpty()) {
            return new Result(List.of(NOBODY), null, null, List.of(), null);
        }
        candidates.sort((a, b) -> { // List.sort is stable
            boolean aNever = a.lastSendMillis() == 0L;
            boolean bNever = b.lastSendMillis() == 0L;
            if (aNever != bNever) {
                return aNever ? 1 : -1;
            }
            return Long.compare(b.lastSendMillis(), a.lastSendMillis());
        });
        int shown = Math.min(candidates.size(), AdminBanCommand.PAGE_ROWS);
        List<String> lines = new ArrayList<String>(shown + 2);
        lines.add(LIST_HEADER);
        for (int i = 0; i < shown; i++) {
            Target t = candidates.get(i);
            lines.add(listRow(i + 1, t, covered(t, snapshot), nowMillis));
        }
        if (shown < candidates.size()) {
            lines.add("+" + (candidates.size() - shown) + " more - /chatmute <name> reaches them");
        }
        return new Result(List.copyOf(lines), null, null, List.copyOf(candidates.subList(0, shown)), null);
    }

    /** A unique case-insensitive exact name among the live non-self non-admin sessions; with no
     *  such match the refusal still says WHY when the name is the caller's own or an admin's. */
    private static Result byName(String typed, long selfSessionId, List<Target> live,
            List<ChatMuteSet.Entry> snapshot) {
        if (typed == null || typed.isEmpty()) {
            return reply(NO_SUCH_PLAYER);
        }
        int matches = 0;
        Target hit = null;
        for (Target t : live) {
            if (t.sessionId() != selfSessionId && !t.admin() && t.name().equalsIgnoreCase(typed)) {
                matches++;
                hit = t;
            }
        }
        if (matches > 1) {
            return reply(ambiguous(matches));
        }
        if (hit != null) {
            return resolve(hit, selfSessionId, snapshot);
        }
        for (Target t : live) {
            if (t.name().equalsIgnoreCase(typed)) {
                return reply(t.sessionId() == selfSessionId ? SELF : TARGET_ADMIN);
            }
        }
        return reply(NO_SUCH_PLAYER);
    }

    /** The refusals every resolved target passes, in order: self, admin, no key, already covered,
     *  cap; else the deposit. The caller re-checks under its lock ({@link ChatMuteSet#add}). */
    private static Result resolve(Target t, long selfSessionId, List<ChatMuteSet.Entry> snapshot) {
        if (t.sessionId() == selfSessionId) {
            return reply(SELF);
        }
        if (t.admin()) {
            return reply(TARGET_ADMIN);
        }
        if (t.idKeyOrNull() == null && t.srcKeyOrNull() == null) {
            return reply(NO_KEY);
        }
        if (covered(t, snapshot)) {
            return reply(ALREADY_MUTED);
        }
        if (snapshot.size() >= ChatMuteSet.CAP) {
            return reply(CAP_FULL);
        }
        return new Result(List.of(), t, null, null, null);
    }

    /** One {@code /chatunmute} page (the {@code /bans} page law): rows newest first, each numbered by
     *  its slot in the caller's merged numbering, which the result pins. */
    private static Result unmuteListing(int page, List<ChatMuteSet.Entry> snapshot, List<ChatMuteSet.Entry> pinOrNull,
            long nowMillis) {
        if (snapshot.isEmpty()) {
            return reply(NO_MUTES);
        }
        int rows = AdminBanCommand.PAGE_ROWS;
        int pages = (snapshot.size() + rows - 1) / rows;
        if (page < 1 || page > pages) {
            return reply(NO_SUCH_PAGE);
        }
        List<ChatMuteSet.Entry> pin = mergedPin(pinOrNull, snapshot);
        int from = (page - 1) * rows;
        int to = Math.min(snapshot.size(), from + rows);
        List<String> lines = new ArrayList<String>(to - from + 2);
        lines.add(UNMUTE_HEADER);
        for (int i = from; i < to; i++) {
            ChatMuteSet.Entry e = snapshot.get(i);
            lines.add(unmuteRow(indexOfKeys(pin, e) + 1, e, nowMillis));
        }
        if (to < snapshot.size()) {
            lines.add("+" + (snapshot.size() - to) + " more (/chatunmute " + (page + 1) + ")");
        }
        return new Result(List.copyOf(lines), null, null, null, pin);
    }

    /**
     * The caller's {@code /chatunmute} numbering, stable for the session: the old pin's entries keep
     * their slots (a lifted one stays as a gap, so an older view's {@code #n} can never resolve to a
     * DIFFERENT mute; a re-mute with the same keys reuses its slot), new entries append newest first;
     * {@code null} old pin = the current mutes newest first. Past {@link #PIN_MAX} slots the fresh
     * list (the one renumber, keeping the per-session bound). The {@code /bans} numbering law.
     */
    static List<ChatMuteSet.Entry> mergedPin(List<ChatMuteSet.Entry> oldPinOrNull,
            List<ChatMuteSet.Entry> snapshotNewestFirst) {
        if (oldPinOrNull == null) {
            return List.copyOf(snapshotNewestFirst);
        }
        List<ChatMuteSet.Entry> merged = new ArrayList<ChatMuteSet.Entry>(oldPinOrNull);
        for (ChatMuteSet.Entry e : snapshotNewestFirst) {
            if (indexOfKeys(merged, e) < 0) {
                merged.add(e);
            }
        }
        return merged.size() > PIN_MAX ? List.copyOf(snapshotNewestFirst) : List.copyOf(merged);
    }

    /**
     * The admin's receipt after the mute applied. Names the seat (or the observer's door ordinal, r4
     * — the same "observer N" its row showed; 0 = unknown) and which keys hold it, never the
     * player's name; an id-only mute carries the ban verb's caveat (a fresh id escapes it), an
     * address-only one its twin. {@code moreOnAddress} = the caller's count of OTHER sessions or mutes
     * sharing that address (shown only when an address key holds it; clamped at 99+). Worst case 120
     * chars = {@link ChatFrameCodec#TEXT_MAX_CHARS} exactly ({@link #ORDINAL_SHOWN_MAX}).
     */
    public static String muteReceipt(int seatId, boolean observer, int observerOrdinal, boolean idKey, boolean srcKey,
            int moreOnAddress) {
        String who = observer ? (observerOrdinal > 0 ? "observer " + shown(observerOrdinal, ORDINAL_SHOWN_MAX)
                : "an observer") : "seat " + shown(seatId, SLOT_SHOWN_MAX);
        String keys = idKey && srcKey ? "id and address"
                : idKey ? "id only - a fresh id escapes it"
                : srcKey ? "address only - a new address escapes" : "no key";
        String more = srcKey && moreOnAddress > 0
                ? " (" + shown(moreOnAddress, COUNT_SHOWN_MAX) + " more on that address)" : "";
        return "text chat muted: " + who + " until the server stops - " + keys + more;
    }

    /** A name matched more than one live player (a count only, never the names). */
    public static String ambiguous(int matches) {
        return shown(matches, COUNT_SHOWN_MAX) + " players match - type /chatmute for numbers";
    }

    /** The lift receipt with no numbering in hand: a covering entry is named generically. */
    public static String liftReceipt(int index, ChatMuteSet.Entry stillViaOrNull) {
        return liftReceipt(index, stillViaOrNull, null);
    }

    /**
     * The admin's receipt after a lift: {@code text chat mute #n lifted}, plus, when another entry
     * still covers that player (a shared address), {@code - still muted via #k} with k = that entry's
     * slot in the caller's numbering {@code pinOrNull}, or a pointer to the list when it has none.
     * Never a name. Worst case 76 chars.
     */
    public static String liftReceipt(int index, ChatMuteSet.Entry stillViaOrNull, List<ChatMuteSet.Entry> pinOrNull) {
        String head = "text chat mute #" + shown(index, PIN_MAX) + " lifted";
        if (stillViaOrNull == null) {
            return head;
        }
        int k = pinOrNull == null ? -1 : indexOfKeys(pinOrNull, stillViaOrNull);
        return head + (k >= 0 ? " - still muted via #" + (k + 1)
                : " - still muted via another mute (see /chatunmute)");
    }

    /** The label an entry captures at mute time: {@code seat N <name>} or {@code observer <name>}
     *  (no slot: it would be stale once the list changes), the name folded and clipped to 24 chars. */
    public static String entryLabel(Target t) {
        String safe = t.name().isEmpty() ? null : VoiceMuteCommand.safeLabel(t.name());
        String who = t.observer() ? "observer" : "seat " + shown(t.seatId(), SLOT_SHOWN_MAX);
        return safe == null ? who : who + " " + safe;
    }

    /** {@code #n seat 3 Bob - sent 4s ago (muted)} / {@code #n observer 1 - no chat yet} (the observer's DOOR
     *  ordinal, r4 — rows may skip numbers: an admin observer or the caller holds one too). Worst
     *  case 67 chars (a 24-char name, ordinal 9999+, age 999d). */
    static String listRow(int n, Target t, boolean muted, long nowMillis) {
        String who = t.observer() ? "observer " + shown(t.observerOrdinal(), ORDINAL_SHOWN_MAX)
                : "seat " + shown(t.seatId(), SLOT_SHOWN_MAX);
        String safe = t.name().isEmpty() ? null : VoiceMuteCommand.safeLabel(t.name());
        return "#" + n + " " + who + (safe == null ? "" : " " + safe) + " - "
                + (t.lastSendMillis() == 0L ? "no chat yet" : "sent " + ago(nowMillis - t.lastSendMillis()) + " ago")
                + (muted ? " (muted)" : "");
    }

    /** {@code #n <label> - muted 4m ago} (+ {@code (console)}). Worst case 72 chars (a 40-char label). */
    static String unmuteRow(int slot, ChatMuteSet.Entry e, long nowMillis) {
        String label = e.label().isEmpty() ? "a player" : e.label();
        return "#" + shown(slot, PIN_MAX) + " " + label + " - muted " + ago(nowMillis - e.mutedAtMillis()) + " ago"
                + (e.origin() == ChatMuteSet.Origin.CONSOLE ? " (console)" : "");
    }

    /** A compact age: {@code 4s}, {@code 12m}, {@code 5h} (under 48 h), else days clamped at 999. */
    static String ago(long elapsedMillis) {
        long s = Math.max(0L, elapsedMillis) / 1000L;
        if (s < 60L) {
            return s + "s";
        }
        long m = s / 60L;
        if (m < 60L) {
            return m + "m";
        }
        long h = m / 60L;
        if (h < 48L) {
            return h + "h";
        }
        return Math.min(h / 24L, AGE_DAYS_MAX) + "d";
    }

    private static boolean covered(Target t, List<ChatMuteSet.Entry> snapshot) {
        for (ChatMuteSet.Entry e : snapshot) {
            if (e.matches(t.idKeyOrNull(), t.srcKeyOrNull())) {
                return true;
            }
        }
        return false;
    }

    private static Target liveById(List<Target> live, long sessionId) {
        for (Target t : live) {
            if (t.sessionId() == sessionId) {
                return t;
            }
        }
        return null;
    }

    static int indexOfKeys(List<ChatMuteSet.Entry> pin, ChatMuteSet.Entry e) {
        for (int i = 0; i < pin.size(); i++) {
            if (pin.get(i).sameKeys(e)) {
                return i;
            }
        }
        return -1;
    }

    /** The parser's 1..4-digit index or page as an int; -1 for anything else (a hand-built verdict). */
    private static int smallIntOrMinus(String digits) {
        if (digits == null || digits.isEmpty() || digits.length() > 4) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /** {@code v} as shown in a line: negatives as 0, past {@code max} as {@code max+}. */
    private static String shown(int v, int max) {
        return v < 0 ? "0" : v > max ? max + "+" : Integer.toString(v);
    }

    private static Result reply(String line) {
        return new Result(List.of(line), null, null, null, null);
    }
}
