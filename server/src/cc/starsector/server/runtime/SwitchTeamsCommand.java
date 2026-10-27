package cc.starsector.server.runtime;

import java.util.List;
import java.util.function.IntFunction;

/**
 * The admin {@code /switchteams} player verb, the
 * {@link AdminKickCommand} template: no arg ⇒ a {@link ListingFrameCodec#KIND_SWITCHTEAMS} MENU
 * of every live player, the CALLER included (an admin switches their own team too), ONE row per
 * player whose mark is the mode — {@code instant} (flip the live hull now) / {@code respawn} (the
 * seat's next fly-in arrives on the other team; the default) — toggled LOCALLY by the client's
 * Left/Right; {@code <text>} ⇒ the same menu pre-filtered to the names containing
 * that text (the {@code /ship <text>} shape); {@code #<wireId> instant|respawn} ⇒ the menu's
 * paste form, resolved against the live session whose seat's fixed wire id matches. The verdict
 * names the SEAT to switch; the door deposits it on the admin queue and the serve loop's
 * {@link SeatTeamLane} owns the effect + the honest receipt (the D5 reply law — no optimistic
 * inline ack here).
 *
 * <p>Laws: pure and static — the caller (door, reader thread, admin bit ALREADY checked) hands in
 * an immutable snapshot of the live set taken under its lock; replies are constant-shaped (the
 * bounded filter echo is the only variable part); every line obeys the chat text law by
 * construction (the contract rows validate each).
 */
public final class SwitchTeamsCommand {

    public static final String NOBODY = "nobody to switch";
    public static final String NO_SUCH_PLAYER = "no such player";

    /** The private reply lines (reading order) + the seat to switch ({@code -1} = none) and
     * its mode ({@code respawn} false = instant). */
    public record Result(List<String> replyLines, int switchSeatId, boolean respawn) {}

    private SwitchTeamsCommand() {
    }

    /**
     * @param parsed      a {@code SWITCHTEAMS_LIST}/{@code _INSTANT}/{@code _RESPAWN} verdict
     * @param live        the live sessions (any order; the caller is among them)
     * @param seatName    seat id → announced name or {@code null}
     * @param listingSink the transport's LSTD emit (fires on a non-empty menu only)
     */
    public static Result handle(ChatCommands.Parsed parsed, List<AdminKickCommand.Target> live,
            IntFunction<String> seatName, ChatCommandDispatch.ListingSink listingSink) {
        switch (parsed.kind()) {
            case SWITCHTEAMS_LIST: {
                List<ListingFrameCodec.ListingEntry> entries =
                        ListingEmitter.switchTeamEntries(live, seatName, parsed.arg());
                if (entries.isEmpty()) {
                    return new Result(List.of(parsed.arg() == null ? NOBODY
                            : "no players match '" + ChatCommandDispatch.boundEcho(parsed.arg())
                                    + "'"), -1, false);
                }
                listingSink.emitListing(ListingFrameCodec.KIND_SWITCHTEAMS, entries);
                return new Result(List.of(), -1, false);
            }
            case SWITCHTEAMS_INSTANT:
            case SWITCHTEAMS_RESPAWN: {
                long wire;
                try {
                    wire = Long.parseLong(parsed.arg());
                } catch (NumberFormatException unreachableByGrammar) {
                    return new Result(List.of(NO_SUCH_PLAYER), -1, false);
                }
                for (AdminKickCommand.Target t : live) {
                    if (t.shipId() == wire) {
                        return new Result(List.of(), t.seatId(),
                                parsed.kind() == ChatCommands.Kind.SWITCHTEAMS_RESPAWN);
                    }
                }
                return new Result(List.of(NO_SUCH_PLAYER), -1, false);
            }
            default:
                return new Result(List.of(ChatCommands.SWITCHTEAMS_USAGE), -1, false);
        }
    }
}
