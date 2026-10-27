package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The admin {@code /kick} player verb, the {@link VoiceMuteCommand}
 * template: no arg ⇒ a {@link ListingFrameCodec#KIND_KICK} picker listing of every OTHER live
 * session ({@link ListingEmitter#kickEntries}; the picker pastes
 * {@code /kick #<id>}; an empty live set keeps the short {@link #NOBODY} CHTD reply);
 * {@code #<wireId>} ⇒ the live session whose seat's fixed wire id matches; {@code <name>}
 * ⇒ a UNIQUE case-insensitive exact match over the live sessions' own names
 * ({@link Target#name}). The {@link AdminBanCommand}
 * twin of main's design call: the {@code /kick} menu PIN (hull wire id → the session the row
 * showed) binds {@code #<wireId>} whenever one EXISTS (a menu was served this session), so a
 * re-seated hull answers {@link #STALE_MENU}; with no menu served {@code #id} resolves live;
 * a typed {@code <name>} always resolves live. The verdict names the
 * SESSION to kick; the door executes it under its lock with {@code failSessionLocked} — EXACTLY
 * the ops-GUI "Kick selected" semantic ({@code kickSeatSession}):
 * a session RESET, the same cid re-admits (requested as "like kick selected"). The durable
 * ban lane = {@link AdminBanCommand} / {@link BanSet}.
 *
 * <p>Laws: pure and static — the caller (door, reader thread, admin bit ALREADY checked) hands
 * in an immutable snapshot of the live set taken under its lock, so this class holds no session
 * types and cannot race the serve loop; the kicker's identity is the SESSION only, never wire
 * text; replies are constant-shaped (the sanitized seat label is the only variable part, via
 * {@link VoiceMuteCommand#safeLabel}); every line obeys the chat text law by construction
 * (the contract rows validate each).
 */
public final class AdminKickCommand {

    public static final String NOBODY = "nobody to kick";
    public static final String NO_SUCH_PLAYER = "no such player";
    public static final String SELF = "cannot kick yourself";
    /** The pinned hull now holds another session. */
    public static final String STALE_MENU = "that player left or changed ships - reopen /kick";

    /** One live session's identity finals, as the door snapshots them under its lock.
     *  SOURCE OF TRUTH for {@code name} = the session's own
     *  resolved SNMR announce ({@code ""} = nameless; {@code null} normalizes to it), never the
     *  arbiter's per-seat map (put-only, never cleared). */
    public record Target(long sessionId, int seatId, long shipId, String name) {
        public Target {
            name = name == null ? "" : name;
        }
    }

    /** The private reply lines (reading order) + the session to kick ({@code 0} = none).
     *  + {@code menuPin} = the {@code /kick} menu just
     *  served, hull wire id → session id per row (REPLACES the caller's); {@code null} = leave it. */
    public record Result(List<String> replyLines, long kickSessionId, Map<Long, Long> menuPin) {
        public Result(List<String> replyLines, long kickSessionId) {
            this(replyLines, kickSessionId, null);
        }
    }

    private AdminKickCommand() {
    }

    /**
     * The seat-name function is GONE — every name read
     * (the menu label, {@code <name>} matching, the reply label) is {@link Target#name}.
     *
     * @param parsed        a {@code KICK_LIST}/{@code KICK_SET}/{@code KICK_WIRE} verdict
     * @param selfSessionId the kicker's session (never a valid target)
     * @param live          the live sessions (any order; the kicker may be among them)
     * @param menuPinOrNull the caller's {@code /kick} menu pin (hull wire id → session id of the
     *                      last menu served), or {@code null} = no menu served ⇒ {@code #id}
     *                      resolves live
     * @param listingSink   the transport's LSTD emit (fires on {@code KICK_LIST}
     *                      with a non-empty entry set only — the /mute contract)
     */
    public static Result handle(ChatCommands.Parsed parsed, long selfSessionId,
            List<Target> live, Map<Long, Long> menuPinOrNull,
            ChatCommandDispatch.ListingSink listingSink) {
        switch (parsed.kind()) {
            case KICK_LIST: {
                List<ListingFrameCodec.ListingEntry> entries =
                        ListingEmitter.kickEntries(selfSessionId, live);
                if (entries.isEmpty()) {
                    return new Result(List.of(NOBODY), 0L);
                }
                listingSink.emitListing(ListingFrameCodec.KIND_KICK, entries);
                return new Result(List.of(), 0L, menuPin(entries, live));
            }
            case KICK_WIRE: {
                long wire;
                try {
                    wire = Long.parseLong(parsed.arg());
                } catch (NumberFormatException unreachableByGrammar) {
                    return new Result(List.of(NO_SUCH_PLAYER), 0L);
                }
                Target target = null;
                for (Target t : live) {
                    if (t.shipId() == wire) {
                        target = t;
                        break;
                    }
                }
                // A served menu binds its hull to the
                // session its row showed — a re-seated hull refuses, nobody kicked.
                if (target != null && target.sessionId() != selfSessionId && menuPinOrNull != null
                        && !Long.valueOf(target.sessionId()).equals(menuPinOrNull.get(wire))) {
                    return new Result(List.of(STALE_MENU), 0L);
                }
                return kick(selfSessionId, target);
            }
            case KICK_SET: {
                String wanted = parsed.arg();
                Target target = null;
                int matches = 0;
                for (Target t : live) {
                    if (t.name().trim().equalsIgnoreCase(wanted)) {
                        target = t;
                        matches++;
                    }
                }
                return kick(selfSessionId, matches == 1 ? target : null);
            }
            default:
                return new Result(List.of("usage: /kick [player|#id]"), 0L);
        }
    }

    private static Result kick(long selfSessionId, Target target) {
        if (target == null) {
            return new Result(List.of(NO_SUCH_PLAYER), 0L);
        }
        if (target.sessionId() == selfSessionId) {
            return new Result(List.of(SELF), 0L);
        }
        return new Result(List.of("kicked seat " + target.seatId() + " ("
                + ListingEmitter.playerLabel(target.seatId(), target.name()) + ")"), target.sessionId());
    }

    /** The {@code /kick} menu PIN — each served row's
     *  hull wire id → the session that row showed (the FIRST live holder, {@code KICK_WIRE}'s
     *  resolution order). */
    private static Map<Long, Long> menuPin(List<ListingFrameCodec.ListingEntry> served, List<Target> live) {
        Map<Long, Long> sessionByShip = new HashMap<Long, Long>(live.size() * 2);
        for (Target t : live) {
            sessionByShip.putIfAbsent(t.shipId(), t.sessionId());
        }
        Map<Long, Long> pin = new HashMap<Long, Long>(served.size() * 2);
        for (ListingFrameCodec.ListingEntry e : served) {
            pin.put(e.hullWireId(), sessionByShip.get(e.hullWireId()));
        }
        return Map.copyOf(pin);
    }
}
