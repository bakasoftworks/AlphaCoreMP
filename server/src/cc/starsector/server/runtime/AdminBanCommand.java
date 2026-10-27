package cc.starsector.server.runtime;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The admin P27 chat verbs, the
 * {@link AdminKickCommand} template: {@code /ban #<wireId>} resolves the live session whose
 * seat's fixed wire id matches ({@code #id} ONLY — never a bare name, A8); {@code /unban #<n>}
 * resolves slot n of the caller's PINNED ban numbering (below); {@code /bans [page]}
 * lists the CID entries (≤ {@link #PAGE_ROWS} rows a page, a {@code +N more} tail, and a count of
 * the source bans — those are the operator's view, A7).
 *
 * <p>Main's design call:
 * the {@code /ban} menu PIN (hull wire id → the session the row showed) binds whenever one
 * EXISTS — a menu was served this session — so a pick from a PARKED menu after a
 * {@code /transfer} or a rejoin re-seated that hull answers {@link #STALE_MENU}, never a ban of
 * a DIFFERENT player; with no menu ever served, a hand-typed {@code #id} resolves live as it
 * always did. {@code /unban #n} with NO pin (none served yet, or a takeover dropped it) refuses
 * ({@link #NO_SUCH_BAN}) — list first — never the old insertion-order fallback.
 *
 * <p>Laws: pure and static — the door hands in immutable snapshots taken under its lock; the
 * verdict names what to DEPOSIT (the door re-verifies at the serve lane, A4); a chat admin can
 * never ban an elevated session (the shared admin secret must not purge the other admins) nor
 * lift an operator ban ({@link #OPERATOR_BAN}); replies are constants plus server-minted hex
 * abbreviations, never a full cid and never player text (every line ≤
 * {@link ChatFrameCodec#TEXT_MAX_CHARS}, printable ASCII).
 * bare {@code /ban} / {@code /unban} are the {@link ListingFrameCodec#KIND_BAN} /
 * {@link ListingFrameCodec#KIND_UNBAN} picker MENUS (a pick pastes the existing
 * {@code #}-grammar — zero new UP surface); every listing served (the UNBAN menu, a
 * {@code /bans} page) returns its cid KEYS as a pin the door keeps per session, and
 * {@code /unban #n} resolves n against that pin BY KEY, re-checked against the current
 * snapshot; the ban-time seat label rides ONLY the LSTD occupantLabel — never a reply
 * line.
 *
 * <p>The OBSERVER HANDLE band — an observer holds no
 * hull (seat 0 / ship 0), so the {@code /ban} menu names it by a server-minted HANDLE
 * {@link #OBSERVER_HANDLE_BASE}{@code + slot} (slot 1.. in listing order, per menu), label the
 * constant {@code observer <slot>} (never player text). {@code #<wire> >= }
 * {@link #OBSERVER_HANDLE_BASE} resolves ONLY through the caller's menu pin (handle → session id,
 * the ONE numbering {@link ListingEmitter#banEntries(long, List, Map)} minted) and then re-looks
 * that session up in the FRESH live snapshot — dead / not an observer / no pin ⇒
 * {@link #STALE_MENU}, self ⇒ {@link #SELF}, admin ⇒ {@link #TARGET_ADMIN}; below the band the
 * hull compare is unchanged. The deposit is the hull target's shape.
 */
public final class AdminBanCommand {

    /** The first id of the observer HANDLE band (13
     *  digits — inside the {@code #<1..19 digits>} grammar). Every hull holder's wire id is a
     *  descriptor seat ship id (≤ 4096 by the roster position assert; {@code MatchSeatAuthority}
     *  refuses a seat at or above this base), so a handle can never name a pilot. */
    public static final long OBSERVER_HANDLE_BASE = 4_000_000_000_000L;

    public static final String BAN_USAGE = "usage: /ban #id [<term>] (admin; no arg = the menu; 30m 1d 1w 1mo perm)";
    public static final String NOBODY_TO_BAN = "nobody to ban (admins are exempt)";
    public static final String NO_ADMIN_BANS = "no admin bans to lift";
    public static final String SELF = "cannot ban yourself";
    public static final String TARGET_ADMIN = "cannot ban an admin";
    public static final String NO_SUCH_PLAYER = "no such player";
    public static final String OPERATOR_BAN = "operator ban - lift it from the house file";
    public static final String NO_SUCH_BAN = "no such ban (see /bans)";
    /** A retime found no LIVE admin entry (absent, lifted or expired). */
    public static final String NO_SUCH_ADMIN_BAN = "no such admin ban";
    public static final String NO_BANS = "no admin-visible bans";
    public static final String NO_SUCH_PAGE = "no such page (see /bans)";
    /** The pinned hull now holds another session (or none
     *  of the menu's). */
    public static final String STALE_MENU = "that player left or changed ships - reopen /ban";
    /** Rows per {@code /bans} page. */
    public static final int PAGE_ROWS = 12;
    /** Hex chars of a cid key shown in a listing row (never the full key — S3). */
    public static final int KEY_ABBREV_CHARS = 12;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /** One live session's identity as the door snapshots it under its lock (A7: + admin, cid).
     *  SOURCE OF TRUTH for {@code name} = the session's own
     *  resolved SNMR announce ({@code ""} = nameless; {@code null} normalizes to it), never the
     *  arbiter's per-seat map (put-only, never cleared).
     *  + {@code observer} = the session's own OBSERVER
     *  flag (server truth, read under the door lock — never a wire claim); the 6-arg form = a
     *  non-observer. */
    public record Target(long sessionId, int seatId, long shipId, boolean admin, String cid, String name,
            boolean observer) {
        public Target {
            name = name == null ? "" : name;
        }

        public Target(long sessionId, int seatId, long shipId, boolean admin, String cid, String name) {
            this(sessionId, seatId, shipId, admin, cid, name, false);
        }
    }

    /** The private reply lines + the deposit: {@code banTarget} (a {@code /ban}) or
     *  {@code unbanKey} (a {@code /unban}, always a CID key), else both null.
     *  {@code pinKeys} = the caller's ban NUMBERING
     *  after this listing — {@link #mergedPin}: tombstone-stable per session (old slots kept, a
     *  lifted key included; new cid keys appended in insertion order), the ONE {@code #n} the
     *  /unban menu and every /bans page this session sees share, so no earlier view of theirs can
     *  resolve {@code #n} to a DIFFERENT ban — {@code null} = leave the caller's pin alone.
     *  {@code menuPin} = the {@code /ban} menu just served,
     *  hull wire id → session id per row (REPLACES the caller's menu pin); {@code null} = leave it.
     *  An observer row's key is its HANDLE (disjoint from
     *  every hull id by the band), so one map pins both row kinds without collision.
     *  {@code retimeKey} + {@code retimeTerm} = a
     *  {@code /banned #n <term>} deposit (the pinned ADMIN cid key + the canonical term token). */
    public record Result(List<String> replyLines, Target banTarget, String unbanKey, List<String> pinKeys,
            Map<Long, Long> menuPin, String retimeKey, String retimeTerm) {
        public Result(List<String> replyLines, Target banTarget, String unbanKey, List<String> pinKeys,
                Map<Long, Long> menuPin) {
            this(replyLines, banTarget, unbanKey, pinKeys, menuPin, null, null);
        }

        public Result(List<String> replyLines, Target banTarget, String unbanKey, List<String> pinKeys) {
            this(replyLines, banTarget, unbanKey, pinKeys, null);
        }

        public Result(List<String> replyLines, Target banTarget, String unbanKey) {
            this(replyLines, banTarget, unbanKey, null, null);
        }
    }

    private AdminBanCommand() {
    }

    /**
     * + the two menus, the caller's pin and the LSTD
     * sink. The seat-name function is GONE (the row label
     * reads {@link Target#name}); + the caller's {@code /ban} menu pin.
     *
     * @param parsed           a {@code BAN_*}/{@code UNBAN_*}/{@code BANS_LIST} verdict
     * @param selfSessionId    the caller's session (never a valid target)
     * @param live             the live sessions (any order; the caller may be among them)
     * @param snapshot         the door's {@link BanSet#snapshot} (insertion order)
     * @param pinnedKeysOrNull the caller's pin (its tombstone-stable ban numbering, {@link #mergedPin}:
     *                         every key any listing showed this session, slots kept), or
     *                         {@code null} = none ⇒ {@code /unban #n} refuses (list first)
     * @param menuPinOrNull    the caller's {@code /ban} menu pin (hull wire id → session id of the
     *                         last menu served), or {@code null} = no menu served ⇒ {@code #id}
     *                         resolves live
     * @param listingSink      the transport's LSTD emit (fires on a non-empty menu only)
     */
    public static Result handle(ChatCommands.Parsed parsed, long selfSessionId, List<Target> live,
            List<BanSet.Entry> snapshot, List<String> pinnedKeysOrNull, Map<Long, Long> menuPinOrNull,
            ChatCommandDispatch.ListingSink listingSink) {
        return handle(parsed, selfSessionId, live, snapshot, pinnedKeysOrNull, menuPinOrNull, listingSink,
                System.currentTimeMillis());
    }

    /**
     * The same verdict at the seam's ONE {@code wallNow} (epoch
     * ms) — an entry EXPIRED at {@code wallNow} is ABSENT from every read here (the menus, the
     * {@code /bans} pages, the pin merge, an index resolve: one expiry predicate), and
     * {@code /banned #n <term>} resolves EXACTLY like {@code /unban #n} (pinned by KEY, re-checked,
     * ADMIN-only, an operator entry ⇒ {@link #OPERATOR_BAN}) into {@link Result#retimeKey}.
     */
    public static Result handle(ChatCommands.Parsed parsed, long selfSessionId, List<Target> live,
            List<BanSet.Entry> snapshot, List<String> pinnedKeysOrNull, Map<Long, Long> menuPinOrNull,
            ChatCommandDispatch.ListingSink listingSink, long wallNow) {
        List<BanSet.Entry> liveBans = new ArrayList<BanSet.Entry>(snapshot.size());
        for (BanSet.Entry e : snapshot) {
            if (!e.expired(wallNow)) {
                liveBans.add(e);
            }
        }
        snapshot = liveBans;
        switch (parsed.kind()) {
            case BAN_LIST: {
                // The emitter hands back the observer
                // handle → session map it minted — the pin stores THAT map (ONE numbering).
                Map<Long, Long> handles = new HashMap<Long, Long>();
                List<ListingFrameCodec.ListingEntry> entries =
                        ListingEmitter.banEntries(selfSessionId, live, handles);
                if (entries.isEmpty()) {
                    return reply(NOBODY_TO_BAN);
                }
                listingSink.emitListing(ListingFrameCodec.KIND_BAN, entries);
                return new Result(List.of(), null, null, null, menuPin(entries, live, handles));
            }
            case UNBAN_LIST: {
                List<BanSet.Entry> menu = adminCidEntriesNewestFirst(snapshot);
                if (menu.isEmpty()) {
                    return reply(NO_ADMIN_BANS);
                }
                // The rows paste the entry's slot in the
                // caller's tombstone-stable numbering (= the /bans numbering) and pin that list.
                List<String> pin = mergedPin(pinnedKeysOrNull, cidEntries(snapshot));
                listingSink.emitListing(ListingFrameCodec.KIND_UNBAN,
                        ListingEmitter.unbanEntries(menu, pin, wallNow));
                return new Result(List.of(), null, null, pin);
            }
            case BAN_WIRE: {
                long wire;
                try {
                    wire = Long.parseLong(parsed.arg());
                } catch (NumberFormatException unreachableByGrammar) {
                    return reply(NO_SUCH_PLAYER);
                }
                if (wire >= OBSERVER_HANDLE_BASE) {
                    return banObserverHandle(wire, selfSessionId, live, menuPinOrNull);
                }
                Target target = null;
                for (Target t : live) {
                    if (t.shipId() == wire && wire > 0L) {
                        target = t;
                        break;
                    }
                }
                if (target == null) {
                    return reply(NO_SUCH_PLAYER);
                }
                if (target.sessionId() == selfSessionId) {
                    return reply(SELF);
                }
                if (target.admin()) {
                    return reply(TARGET_ADMIN);
                }
                // A served menu binds its hull to the
                // session its row showed — a re-seated hull refuses, nothing deposited.
                if (menuPinOrNull != null && !Long.valueOf(target.sessionId()).equals(menuPinOrNull.get(wire))) {
                    return reply(STALE_MENU);
                }
                return new Result(List.of(), target, null);
            }
            case UNBAN_INDEX:
            case BANNED_RETIME: {
                // N indexes the caller's PIN; the KEY is then re-checked against the
                // CURRENT snapshot (a stale pin resolves by key).
                // NO pin ⇒ refuse — the insertion-order fallback could lift a
                // DIFFERENT ban after a takeover dropped the pin; /bans or /unban mints one.
                if (pinnedKeysOrNull == null) {
                    return reply(NO_SUCH_BAN);
                }
                List<String> keys = pinnedKeysOrNull;
                int index = parseSmall(parsed.arg());
                if (index < 1 || index > keys.size()) {
                    return reply(NO_SUCH_BAN);
                }
                BanSet.Entry entry = cidEntryOrNull(snapshot, keys.get(index - 1));
                if (entry == null) {
                    return reply(NO_SUCH_BAN);
                }
                if (entry.origin() != BanSet.Origin.ADMIN) {
                    return reply(OPERATOR_BAN);
                }
                if (parsed.kind() == ChatCommands.Kind.BANNED_RETIME) {
                    // The /banned deposit (a non-ladder term
                    // is the grammar's usage; re-checked here, fail-closed, for a hand-built verdict).
                    BanTerm term = BanTerm.parseOrNull(parsed.term());
                    return term == null ? reply(ChatCommands.BANNED_USAGE)
                            : new Result(List.of(), null, null, null, null, entry.key(), term.token);
                }
                return new Result(List.of(), null, entry.key());
            }
            case BANS_LIST: {
                // Every page numbers by and pins the caller's tombstone-stable list.
                List<String> pin = mergedPin(pinnedKeysOrNull, cidEntries(snapshot));
                return new Result(listing(snapshot, parsed.arg() == null ? 1 : parseSmall(parsed.arg()), pin),
                        null, null, pin);
            }
            default:
                return reply(BAN_USAGE);
        }
    }

    /**
     * The /unban menu source — the ADMIN-origin CID
     * entries (the only ones a chat admin may lift, A7), {@code atMillis} DESCENDING; ties and
     * {@code 0} stamps by REVERSE insertion order (a stable sort over the reversed snapshot);
     * the newest {@link ListingFrameCodec#MAX_TOTAL_ENTRIES} kept (the codec's existing
     * listing cap — {@code /bans} pages the rest).
     */
    static List<BanSet.Entry> adminCidEntriesNewestFirst(List<BanSet.Entry> snapshot) {
        List<BanSet.Entry> admin = new ArrayList<BanSet.Entry>();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            BanSet.Entry e = snapshot.get(i);
            if (e.kind() == BanSet.Kind.CID && e.origin() == BanSet.Origin.ADMIN) {
                admin.add(e);
            }
        }
        admin.sort((a, b) -> Long.compare(b.atMillis(), a.atMillis())); // List.sort is stable
        return List.copyOf(admin.size() > ListingFrameCodec.MAX_TOTAL_ENTRIES
                ? admin.subList(0, ListingFrameCodec.MAX_TOTAL_ENTRIES) : admin);
    }

    /**
     * The caller's ban NUMBERING, tombstone-stable for
     * the session's life — the old pin's keys keep their slots (a lifted key included, so an older
     * view's {@code #n} can never resolve to a DIFFERENT ban), cid keys not yet pinned append in
     * insertion order; {@code null} old pin = the fresh insertion-order list. Past
     * {@link BanSet#MAX_ENTRIES} slots the fresh list (the ONE renumber; keeps the bound, and the
     * {@code /unban #} grammar's 4 digits ≥ MAX_ENTRIES — a contract row pins that).
     */
    static List<String> mergedPin(List<String> oldPinOrNull, List<BanSet.Entry> cids) {
        if (oldPinOrNull == null) {
            return keysOf(cids);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<String>(oldPinOrNull);
        for (BanSet.Entry e : cids) {
            merged.add(e.key());
        }
        return merged.size() > BanSet.MAX_ENTRIES ? keysOf(cids) : List.copyOf(merged);
    }

    /** The {@code /ban} menu PIN — each served row's
     *  hull wire id → the session that row showed (the FIRST live holder of that hull, the
     *  {@code BAN_WIRE} resolution order). An observer
     *  row (a handle ≥ {@link #OBSERVER_HANDLE_BASE}) pins the emitter's OWN handle → session
     *  map, never a re-derived slot order. */
    private static Map<Long, Long> menuPin(List<ListingFrameCodec.ListingEntry> served, List<Target> live,
            Map<Long, Long> handles) {
        Map<Long, Long> sessionByShip = new HashMap<Long, Long>(live.size() * 2);
        for (Target t : live) {
            sessionByShip.putIfAbsent(t.shipId(), t.sessionId());
        }
        Map<Long, Long> pin = new HashMap<Long, Long>(served.size() * 2);
        for (ListingFrameCodec.ListingEntry e : served) {
            long wire = e.hullWireId();
            pin.put(wire, wire >= OBSERVER_HANDLE_BASE ? handles.get(wire) : sessionByShip.get(wire));
        }
        return Map.copyOf(pin);
    }

    /** {@code /ban #<handle>} — the caller's pin names
     *  the session; the FRESH snapshot decides (the pin is never trusted for liveness,
     *  observer-ness, self or admin). */
    private static Result banObserverHandle(long handle, long selfSessionId, List<Target> live,
            Map<Long, Long> menuPinOrNull) {
        Long pinned = menuPinOrNull == null ? null : menuPinOrNull.get(handle);
        if (pinned == null) {
            return reply(STALE_MENU);
        }
        Target target = null;
        for (Target t : live) {
            if (t.sessionId() == pinned) {
                target = t;
                break;
            }
        }
        if (target == null || !target.observer()) {
            return reply(STALE_MENU);
        }
        if (target.sessionId() == selfSessionId) {
            return reply(SELF);
        }
        if (target.admin()) {
            return reply(TARGET_ADMIN);
        }
        return new Result(List.of(), target, null);
    }

    /** The {@code /bans} page lines (pure; the contract rows pin them); each row's {@code #n} =
     *  the entry's slot in {@code pin} (the caller's tombstone-stable numbering, so a lifted
     *  entry leaves a gap rather than renumbering the rows below it). */
    public static List<String> listing(List<BanSet.Entry> snapshot, int page, List<String> pin) {
        List<BanSet.Entry> cids = cidEntries(snapshot);
        int sources = snapshot.size() - cids.size();
        List<String> lines = new ArrayList<String>(PAGE_ROWS + 2);
        if (cids.isEmpty()) {
            lines.add(NO_BANS);
        } else {
            int pages = (cids.size() + PAGE_ROWS - 1) / PAGE_ROWS;
            if (page < 1 || page > pages) {
                return List.of(NO_SUCH_PAGE);
            }
            int from = (page - 1) * PAGE_ROWS;
            int to = Math.min(cids.size(), from + PAGE_ROWS);
            for (int i = from; i < to; i++) {
                lines.add(row(pin.indexOf(cids.get(i).key()) + 1, cids.get(i)));
            }
            if (to < cids.size()) {
                lines.add("+" + (cids.size() - to) + " more (/bans " + (page + 1) + ")");
            }
        }
        if (sources > 0) {
            lines.add(sources + " source bans (operator view)");
        }
        return List.copyOf(lines);
    }

    /** {@code #<index> cid <first 12 hex>... <op|admin> <yyyy-mm-dd|->} (ASCII; ≤ 50 chars). */
    static String row(int index, BanSet.Entry e) {
        return "#" + index + " " + cidAbbrev(e.key()) + " " + BanSet.originToken(e.origin()) + " " + day(e.atMillis());
    }

    /** {@code cid <first 12 hex>...} (the whole key
     *  when ≤ {@link #KEY_ABBREV_CHARS}) — the ONE chat-line form of a cid key, shared by the
     *  {@code /bans} rows and the serve's lift receipt (ASCII hex; ≤ 19 chars). */
    static String cidAbbrev(String key) {
        String body = CidDigest.body(key); // after the h1: tag, so every shown char is a digest char
        return "cid " + (body.length() > KEY_ABBREV_CHARS ? body.substring(0, KEY_ABBREV_CHARS) + "..." : body);
    }

    /**
     * The serve lane's W16 chat RECEIPTS, composed here (a
     * public home the contract rows reach — the lane is package-private) from constants, counts and
     * server-minted tokens only, each ≤ {@link ChatFrameCodec#TEXT_MAX_CHARS} by construction (
     * an over-long receipt would fault AFTER the ban applied). {@code saveNote} =
     * {@link #saveNote}. The ok-ban receipt names the APPLIED term (only when the entry was ADDED —
     * an existing entry keeps its own), and on a cid ban where the fresh-id caveat now lives:
     * {@code address too} when a source hold rides it, else {@code id only - a fresh id escapes it}.
     */
    public static String banReceipt(String termToken, boolean added, boolean offline, int kicked, boolean cidBan,
            boolean held, String saveNote) {
        return "banned" + (added ? " " + termToken : "") + (offline ? " (offline)" : "") + ": " + kicked
                + " session(s) ended, " + (!cidBan ? "" : held ? "address too, " : "id only - a fresh id escapes it, ")
                + saveNote;
    }

    /** The {@code /banned #n <term>} receipt per
     *  {@link BanSet.RetimeResult} — RETIMED names the key (abbreviated) and its new term. */
    public static String retimeReceipt(BanSet.RetimeResult result, String key, String termToken, String saveNote) {
        return switch (result) {
            case RETIMED -> "ban " + cidAbbrev(key) + " now " + termToken + ", " + saveNote;
            case OPERATOR -> OPERATOR_BAN;
            default -> NO_SUCH_ADMIN_BAN;
        };
    }

    /** The lift receipt (names WHICH ban)
     *  + the r1 tail when the lifted pair's address stays held by another ban. */
    public static String liftReceipt(String cidKeyOrNull, String saveNote, boolean addressStillHeld) {
        return "ban lifted " + (cidKeyOrNull == null ? "" : cidAbbrev(cidKeyOrNull) + " ") + "(" + saveNote + ")"
                + (addressStillHeld ? ", address still held by another ban" : "");
    }

    /** The persist token of the serve's {@code persistBans} → the receipt's save clause. */
    public static String saveNote(String persist) {
        return switch (persist) {
            case "queued" -> "save queued";
            case "none" -> "not saved: no bans file on this server";
            default -> "already on the list";
        };
    }

    /** {@code yyyy-mm-dd} (UTC) of a ban stamp, {@code -} for 0 (unknown).
     *  Shared with {@link ListingEmitter#unbanEntries}. */
    static String day(long atMillis) {
        return atMillis > 0L ? DAY.format(Instant.ofEpochMilli(atMillis)) : "-";
    }

    private static List<String> keysOf(List<BanSet.Entry> entries) {
        List<String> keys = new ArrayList<String>(entries.size());
        for (BanSet.Entry e : entries) {
            keys.add(e.key());
        }
        return List.copyOf(keys);
    }

    private static BanSet.Entry cidEntryOrNull(List<BanSet.Entry> snapshot, String key) {
        for (BanSet.Entry e : snapshot) {
            if (e.kind() == BanSet.Kind.CID && e.key().equals(key)) {
                return e;
            }
        }
        return null;
    }

    private static List<BanSet.Entry> cidEntries(List<BanSet.Entry> snapshot) {
        List<BanSet.Entry> cids = new ArrayList<BanSet.Entry>();
        for (BanSet.Entry e : snapshot) {
            if (e.kind() == BanSet.Kind.CID) {
                cids.add(e);
            }
        }
        return cids;
    }

    private static int parseSmall(String digits) {
        try {
            return digits == null ? -1 : Integer.parseInt(digits);
        } catch (NumberFormatException unreachableByGrammar) {
            return -1; // the grammar admits 1..4 digits only
        }
    }

    private static Result reply(String line) {
        return new Result(List.of(line), null, null);
    }
}
