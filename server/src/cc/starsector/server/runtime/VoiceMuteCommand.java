package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.LongToIntFunction;

/**
 * The {@code /mute} player verb:
 * no arg ⇒ a {@link ListingFrameCodec#KIND_MUTE} listing of every OTHER live session (occupant
 * name, muted/unmuted mark, the hull wire id the picker pastes back as {@code /mute #<id>});
 * {@code #<wireId>} ⇒ the live session whose seat's fixed wire id matches (the {@code /transfer #}
 * grammar — there is NO {@code #<seatId>} form); {@code <name>} ⇒ case-insensitive exact match
 * over the live sessions' OWN resolved names (the door's lookup). Success TOGGLES the listener's mute of that session in the
 * {@link VoiceRelay} (keyed by the target's SESSION id; the door carries the
 * rows across a same-occupant takeover — {@code VoiceRelay.detachMutes}/{@code attachMutes} —
 * so a rejoin keeps them while a different occupant on the seat never inherits them).
 *
 * <p>Laws: the listener's identity comes from the SESSION only, never wire text; replies are
 * constant-shaped (the sanitized seat label is the only variable part); labels obey the LSTD
 * label laws (≤{@value ListingFrameCodec#OCCUPANT_LABEL_MAX_CHARS} printable ASCII, trimmed —
 * {@link #safeLabel}) so the listing encode can never throw on the reader thread.
 *
 * <p>OBSERVERS (seat 0 / ship 0) list AFTER the hull
 * rows under the {@link AdminBanCommand#OBSERVER_HANDLE_BASE} handle band with the constant
 * {@code observer <slot>} label; the listing's handle → session map is the listener's mute-menu
 * pin in the {@link VoiceRelay} (latest menu wins, purged at the listener's detach), and
 * {@code #<handle>} resolves ONLY through it, re-checked live — mute is per-LISTENER only, an
 * observer's own voice send is untouched. Observer-ness is the door's lock snapshot, never wire
 * text; {@code /mute <name>} stays pilot-only (an observer has no fanned name).
 */
public final class VoiceMuteCommand {

    public static final String UNAVAILABLE = "voice unavailable on this server";
    public static final String NOBODY = "nobody to mute";
    public static final String NO_SUCH_PLAYER = "no such player";
    public static final String SELF = "cannot mute yourself";

    private VoiceMuteCommand() {
    }

    /**
     * Reader thread, the chat rate token already consumed. {@code relay == null} ⇒ voice is off
     * on this serve; {@code ownName == null} (or a null answer) ⇒ no name (label {@code seat N}).
     * {@code ownName} = seat id → that seat's live
     * session's OWN resolved name, captured by the door under its lock — the ONE name source for
     * BOTH the {@code /mute <name>} match and every label (never the arbiter's put-only per-seat
     * record, which leaves a nameless successor wearing its predecessor's name).
     */
    public static List<String> handle(ChatCommands.Parsed parsed, long listenerSessionId,
            int listenerSeatId, VoiceRelay relay, IntFunction<String> ownName,
            ChatCommandDispatch.ListingSink listingSink) {
        return handle(parsed, listenerSessionId, listenerSeatId, relay, ownName, null, listingSink);
    }

    /**
     * + {@code observerOrdinalOrNull} = session id → the door-minted
     * OBSERVER ORDINAL, r4 ({@code < 1} = not an observer), the door's lock snapshot at this
     * dispatch ({@code null} = no observer rows, no handle resolution — the pre-band behavior). The handle is
     * {@code BASE + ordinal} — the ONE "observer N" the /ban and /chatmute menus show too; an admin observer
     * lists here under its own ordinal (voice mute is per-listener, the admin exemption is the ban verbs').
     */
    public static List<String> handle(ChatCommands.Parsed parsed, long listenerSessionId,
            int listenerSeatId, VoiceRelay relay, IntFunction<String> ownName,
            LongToIntFunction observerOrdinalOrNull, ChatCommandDispatch.ListingSink listingSink) {
        if (relay == null) {
            return List.of(UNAVAILABLE);
        }
        VoiceRelay.Endpoint[] live = relay.liveEndpoints();
        switch (parsed.kind()) {
            case MUTE_LIST: {
                List<ListingFrameCodec.ListingEntry> entries = new ArrayList<>();
                for (VoiceRelay.Endpoint ep : live) {
                    if (ep.sessionId() == listenerSessionId || ep.shipId() < 1L) {
                        continue;
                    }
                    String label = labelOf(ep.seatId(), ownName);
                    boolean muted = relay.isMuted(listenerSessionId, ep.sessionId());
                    entries.add(new ListingFrameCodec.ListingEntry("seat" + ep.seatId(),
                            muted ? ListingFrameCodec.MARK_MUTE_ON : ListingFrameCodec.MARK_MUTE_OFF,
                            false, label, label, ep.shipId()));
                }
                // The observer rows + the handle map in the SAME loop —
                // the listener's mute-menu pin; r4: the handle rides the door ordinal, never
                // a loop count.
                Map<Long, Long> handles = new HashMap<>();
                for (VoiceRelay.Endpoint ep : live) {
                    if (observerOrdinalOrNull == null || ep.sessionId() == listenerSessionId || ep.shipId() >= 1L) {
                        continue;
                    }
                    int ordinal = observerOrdinalOrNull.applyAsInt(ep.sessionId());
                    if (ordinal < 1) {
                        continue;
                    }
                    long handle = AdminBanCommand.OBSERVER_HANDLE_BASE + ordinal;
                    String label = ListingEmitter.observerLabel(ordinal);
                    boolean muted = relay.isMuted(listenerSessionId, ep.sessionId());
                    entries.add(new ListingFrameCodec.ListingEntry("obs" + ordinal,
                            muted ? ListingFrameCodec.MARK_MUTE_ON : ListingFrameCodec.MARK_MUTE_OFF,
                            false, label, label, handle));
                    handles.put(handle, ep.sessionId());
                }
                if (entries.isEmpty()) {
                    return List.of(NOBODY);
                }
                relay.setMuteMenuPin(listenerSessionId, handles);
                listingSink.emitListing(ListingFrameCodec.KIND_MUTE, entries);
                return List.of();
            }
            case MUTE_WIRE: {
                long wire;
                try {
                    wire = Long.parseLong(parsed.arg());
                } catch (NumberFormatException unreachableByGrammar) {
                    return List.of(NO_SUCH_PLAYER);
                }
                if (wire >= AdminBanCommand.OBSERVER_HANDLE_BASE) {
                    return toggleObserver(relay, listenerSessionId, wire, live, observerOrdinalOrNull);
                }
                VoiceRelay.Endpoint target = null;
                for (VoiceRelay.Endpoint ep : live) {
                    if (ep.shipId() == wire) {
                        target = ep;
                        break;
                    }
                }
                return toggle(relay, listenerSessionId, target, ownName);
            }
            case MUTE_SET: {
                String wanted = parsed.arg();
                VoiceRelay.Endpoint target = null;
                int matches = 0;
                for (VoiceRelay.Endpoint ep : live) {
                    String name = ownName == null ? null : ownName.apply(ep.seatId());
                    if (name != null && name.trim().equalsIgnoreCase(wanted)) {
                        target = ep;
                        matches++;
                    }
                }
                return toggle(relay, listenerSessionId, matches == 1 ? target : null, ownName);
            }
            default:
                return List.of("usage: /mute [player|#id]");
        }
    }

    private static List<String> toggle(VoiceRelay relay, long listenerSessionId,
            VoiceRelay.Endpoint target, IntFunction<String> ownName) {
        if (target == null) {
            return List.of(NO_SUCH_PLAYER);
        }
        if (target.sessionId() == listenerSessionId) {
            return List.of(SELF);
        }
        // REFUSED = the target left the live set after the
        // unlocked snapshot (or the listener after the chat check) — answered like a target never there.
        VoiceRelay.MuteToggle now = relay.toggleMute(listenerSessionId, target.sessionId());
        if (now == VoiceRelay.MuteToggle.REFUSED) {
            return List.of(NO_SUCH_PLAYER);
        }
        // The reply says WHAT was muted (the player's voice) and FOR WHOM (the caller only): the text mute
        // (/chatmute) is a different verb, and hosts confused the two.
        return List.of((now == VoiceRelay.MuteToggle.MUTED ? "muted seat " : "unmuted seat ") + target.seatId()
                + "'s voice for you (" + labelOf(target.seatId(), ownName) + ")");
    }

    /** {@code /mute #<handle>} — the listener's
     *  mute-menu pin names the session, the live set + the door's observer snapshot decide (no
     *  pin / dead / not an observer ⇒ {@link #NO_SUCH_PLAYER}); the reply names the constant
     *  {@code observer <ordinal>} (handle − base, an int: only a minted handle reaches it). */
    private static List<String> toggleObserver(VoiceRelay relay, long listenerSessionId, long handle,
            VoiceRelay.Endpoint[] live, LongToIntFunction observerOrdinalOrNull) {
        Map<Long, Long> pin = relay.muteMenuPin(listenerSessionId);
        Long pinned = pin == null ? null : pin.get(handle);
        if (pinned == null || observerOrdinalOrNull == null || observerOrdinalOrNull.applyAsInt(pinned) < 1) {
            return List.of(NO_SUCH_PLAYER);
        }
        boolean alive = false;
        for (VoiceRelay.Endpoint ep : live) {
            if (ep.sessionId() == pinned) {
                alive = true;
                break;
            }
        }
        if (!alive) {
            return List.of(NO_SUCH_PLAYER);
        }
        if (pinned == listenerSessionId) {
            return List.of(SELF);
        }
        // The pinned observer left the live set after the
        // snapshot above (or the listener after the chat check) — the relay refused, nothing written.
        VoiceRelay.MuteToggle now = relay.toggleMute(listenerSessionId, pinned);
        if (now == VoiceRelay.MuteToggle.REFUSED) {
            return List.of(NO_SUCH_PLAYER);
        }
        // The same WHAT + FOR WHOM wording as the seat reply above (the text-mute confusion):
        // an observer has no name slot, so the constant label stands alone.
        return List.of((now == VoiceRelay.MuteToggle.MUTED ? "muted " : "unmuted ")
                + ListingEmitter.observerLabel((int) (handle - AdminBanCommand.OBSERVER_HANDLE_BASE))
                + "'s voice for you");
    }

    /** The seat's live session's own name made emission-safe, or {@code seat N}. */
    static String labelOf(int seatId, IntFunction<String> ownName) {
        String name = ownName == null ? null : ownName.apply(seatId);
        String safe = name == null ? null : safeLabel(name);
        return safe == null ? "seat " + seatId : safe;
    }

    /** Printable ASCII 0x20-0x7E only, trimmed, ≤ the occupant-label cap; null when nothing survives. */
    static String safeLabel(String raw) {
        StringBuilder kept = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                kept.append(c);
            }
        }
        String trimmed = kept.toString().trim();
        if (trimmed.length() > ListingFrameCodec.OCCUPANT_LABEL_MAX_CHARS) {
            trimmed = trimmed.substring(0, ListingFrameCodec.OCCUPANT_LABEL_MAX_CHARS).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
