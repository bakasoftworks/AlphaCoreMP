package cc.starsector.server.runtime;

import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.ops.PeerText;
import cc.starsector.server.protocol.HouseControlMessage;
import cc.starsector.server.runtime.PilotIntentDatagramServer.CredentialVerdict;
import cc.starsector.server.runtime.PilotIntentDatagramServer.PeerSession;
import static cc.starsector.server.runtime.PilotIntentDatagramServer.ADMIN_CHAT_BUCKET_BURST;
import static cc.starsector.server.runtime.PilotIntentDatagramServer.ADMIN_CHAT_TOKEN_REFILL_MILLIS;
import static cc.starsector.server.runtime.PilotIntentDatagramServer.CHAT_BUCKET_BURST;
import static cc.starsector.server.runtime.PilotIntentDatagramServer.CHAT_TOKEN_REFILL_MILLIS;
import static cc.starsector.server.runtime.PilotIntentDatagramServer.OBSERVER_BOUNCED_VERBS;
import static cc.starsector.server.runtime.PilotIntentDatagramServer.OBSERVER_VERB_BOUNCE;
import static cc.starsector.server.runtime.PilotIntentDatagramServer.nowMillis;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * The chat-verb TRANSPORT half carved VERBATIM out of
 * {@link PilotIntentDatagramServer} — the CTRL chat entry ({@link #onChatUp}), the slash-command dispatch and the
 * admin kick/ban/switchteams verbs. The decide+state half stays {@link ChatCommandDispatch}.
 * READER thread only; every lock take is the door's own {@code lock}.
 */
final class PidsChatVerbs {
    private final PilotIntentDatagramServer door;

    PidsChatVerbs(PilotIntentDatagramServer door) {
        this.door = door;
    }

    /**
     * The admin {@code /kick} verb — snapshot the live set under the lock, let
     * {@link AdminKickCommand} resolve the target (pure; a no-arg {@code /kick} emits the
     * {@code KIND_KICK} picker listing through {@code listingSink}, which must
     * run OUTSIDE the lock — the sink's encode-outside-the-lock contract), then execute the
     * SAME reset the ops-GUI "Kick selected" button runs ({@link PilotIntentDatagramServer#kickSeatSession}:
     * {@code failSessionLocked}, the same cid re-admits). The target is re-resolved live-checked
     * under the second lock take, so a session that died in between is a no-op, never a double
     * death. Reader thread, the session's admin bit already checked by the caller.
     * Each Target carries the session's OWN resolved name
     * (captured here under the lock — S2-2, never the arbiter's per-seat map), and the session's
     * {@code kickMenuPin} is handed in and replaced by the result's {@code menuPin} (S2-1).
     */
    private List<String> adminKick(PeerSession by, int bySeatId, ChatCommands.Parsed parsed,
            ChatCommandDispatch.ListingSink listingSink) {
        List<AdminKickCommand.Target> live = new ArrayList<AdminKickCommand.Target>();
        synchronized (door.lock) {
            for (PeerSession s : door.sessionsById.values()) {
                if (s.isLiveLocked() && s.seatId >= 0) {
                    live.add(new AdminKickCommand.Target(s.sessionId, s.seatId, s.shipId,
                            door.resolvedSeatNameLocked(s)));
                }
            }
        }
        AdminKickCommand.Result result = AdminKickCommand.handle(parsed, by.sessionId, live,
                by.kickMenuPin, listingSink);
        if (result.menuPin() != null) {
            by.kickMenuPin = result.menuPin();
        }
        if (result.kickSessionId() != 0L) {
            PeerSession noticeTarget;
            synchronized (door.lock) {
                PeerSession t = door.sessionsById.get(result.kickSessionId());
                noticeTarget = t != null && t.isLiveLocked() ? t : null;
            }
            if (noticeTarget != null) {
                // KICKED notice BEFORE the kill, outside the lock (core-send
                // lock-order rule — see sendKickedNoticeBurst); the kill below re-resolves.
                door.sendKickedNoticeBurst(noticeTarget, HouseControlMessage.KICKED_REASON_ADMIN_KICK);
            }
            boolean kicked = false;
            int targetSeat = -1;
            long targetSession = 0L;
            synchronized (door.lock) {
                PeerSession target = door.sessionsById.get(result.kickSessionId());
                if (target != null && target.isLiveLocked()) {
                    door.failSessionLocked(target, new PilotPeerDisconnectedException("admin_kick",
                            "an admin kicked this seat's session (reason token: admin_kick —"
                                    + " /kick by seat " + bySeatId + " session "
                                    + by.sessionId + "; target seat " + target.seatId
                                    + " session " + target.sessionId + ")"));
                    kicked = true;
                    targetSeat = target.seatId;
                    targetSession = target.sessionId;
                }
            }
            if (kicked) {
                System.out.println("pilot-datagram-server admin_kick by_seat=" + bySeatId
                        + " by_session=" + by.sessionId + " target_seat=" + targetSeat
                        + " target_session=" + targetSession);
            }
        }
        return result.replyLines();
    }

    /**
     * The admin {@code /ban #id},
     * {@code /unban #n} and {@code /bans [page]} verbs — the {@link #adminKick} shape: snapshot
     * the live set (with each session's admin bit + cid) under the lock, let
     * {@link AdminBanCommand} resolve (pure), then DEPOSIT for the serve lane with this session
     * as the receipt target: {@code BAN_SESSION "<sessionId> <cidKey>"} (the cid captured HERE is
     * re-verified by {@link PilotIntentDatagramServer#banSession} — A4) or {@code UNBAN "cid <key>"}. The serve mails the
     * real outcome (D5 reply law); refusals answer inline as constants. Reader thread, the
     * session's admin bit already checked by the caller.
     * + the two no-arg MENUS through
     * {@code listingSink} (resolved pure OUTSIDE the lock; the sink emits outside it — the
     * {@link #adminKick} shape) and the session's {@code banListingPin}: handed in, replaced by
     * the result's {@code pinKeys} when non-null; one {@code admin_ban_menu} row per menu served
     * (counts only, never text).
     * Each Target carries the session's OWN resolved name
     * (captured here under the lock — S2-2, never the arbiter's per-seat map), and the session's
     * {@code banMenuPin} is handed in and replaced by the result's {@code menuPin} (S2-1).
     */
    private List<String> adminBan(PeerSession by, int bySeatId, ChatCommands.Parsed parsed,
            ChatCommandDispatch.ListingSink listingSink) {
        List<AdminBanCommand.Target> live = new ArrayList<AdminBanCommand.Target>();
        synchronized (door.lock) {
            for (PeerSession s : door.sessionsById.values()) {
                if (s.isLiveLocked() && s.seatId >= 0) {
                    // + the session's own observer flag
                    // (server truth) — the /ban menu's observer-handle rows key on it.
                    live.add(new AdminBanCommand.Target(s.sessionId, s.seatId, s.shipId, s.admin,
                            s.clientId, door.resolvedSeatNameLocked(s), s.observer, s.observerOrdinal));
                }
            }
        }
        int[] menu = {0, -1}; // {kind, rows} of the listing served, if any
        // ONE wall clock for the snapshot + the resolve (expired = absent).
        long wallNow = System.currentTimeMillis();
        AdminBanCommand.Result result = AdminBanCommand.handle(parsed, by.sessionId, live,
                door.bans.snapshot(wallNow), by.banListingPin, by.banMenuPin, (kind, entries) -> {
                    menu[0] = kind;
                    menu[1] = entries.size();
                    listingSink.emitListing(kind, entries);
                }, wallNow);
        if (result.pinKeys() != null) {
            by.banListingPin = result.pinKeys();
        }
        if (result.menuPin() != null) {
            by.banMenuPin = result.menuPin();
        }
        if (menu[1] >= 0) {
            System.out.println("pilot-datagram-server admin_ban_menu kind="
                    + (menu[0] == ListingFrameCodec.KIND_BAN ? "ban" : "unban") + " by_seat=" + bySeatId
                    + " by_session=" + by.sessionId + " rows=" + menu[1]);
        }
        AdminBanCommand.Target target = result.banTarget();
        if (target != null) {
            String cid = target.cid() == null || target.cid().isBlank() ? ""
                    : " " + BanSet.cidKey(target.cid());
            // The term rides the deposit as `term=<tok>` (the
            // grammar's canonical token; bare /ban = BanTerm.DEFAULT) — the serve lane applies it.
            String term = parsed.term() == null ? BanTerm.DEFAULT.token : parsed.term();
            door.offerAdminCommand(new OperatorCommandFile.Command(OperatorCommandFile.Verb.BAN_SESSION,
                    target.seatId(), 0f, false, target.sessionId() + cid + " " + OperatorCommandFile.TERM_PREFIX
                            + term, by.sessionId));
            System.out.println("pilot-datagram-server admin_ban by_seat=" + bySeatId
                    + " by_session=" + by.sessionId + " term=" + term + " target_seat=" + target.seatId()
                    + " target_session=" + target.sessionId());
        } else if (result.retimeKey() != null) {
            // /banned #n <term> = the BAN_CID retime deposit (its
            // `term=` tail selects the door's retimeKey — never an add); the serve mails the receipt.
            door.offerAdminCommand(new OperatorCommandFile.Command(OperatorCommandFile.Verb.BAN_CID, 0, 0f,
                    false, result.retimeKey() + " " + OperatorCommandFile.TERM_PREFIX + result.retimeTerm(),
                    by.sessionId));
            // F-D-2: the deposit rows print the key as the reel's one-way
            // digest - the serve's own rows do (BanOperatorLane.logKey); the command carries the entry's key, which IS
            // that digest (the bans file's key form), so both print the same.
            System.out.println("pilot-datagram-server admin_retime by_seat=" + bySeatId
                    + " by_session=" + by.sessionId + " key="
                    + PeerText.quote(OperatorCommandFile.digestOfKey(result.retimeKey()))
                    + " term=" + result.retimeTerm());
        } else if (result.unbanKey() != null) {
            door.offerAdminCommand(new OperatorCommandFile.Command(OperatorCommandFile.Verb.UNBAN, 0, 0f,
                    false, "cid " + result.unbanKey(), by.sessionId));
            System.out.println("pilot-datagram-server admin_unban by_seat=" + bySeatId
                    + " by_session=" + by.sessionId + " key="
                    + PeerText.quote(OperatorCommandFile.digestOfKey(result.unbanKey())));
        }
        return result.replyLines();
    }

    /**
     * The admin text-mute verbs ({@code /chatmute}, {@code /chatmute #n}, {@code /chatmute <name>},
     * {@code /chatunmute [page]}, {@code /chatunmute #n}) in the {@link #adminBan} shape: snapshot the
     * live sessions with their mute keys under the lock, let {@link AdminChatMuteCommand} resolve (pure,
     * outside the lock), then apply its one deposit under a second lock take. A mute ADDS an entry (the
     * set re-checks duplicate / cap / no key, so a mute set in between answers its own constant); a lift
     * removes the entry by key pair. The deposit happens HERE, never through the serve loop's command
     * drain: the set lives in memory under the door lock and nothing is written to disk. Receipts name a seat or an observer slot
     * plus counts, never a player's name; the log rows carry no text, no name and no key value. The
     * notices (the target when a mute lands; every live session a lift actually freed) go out AFTER the
     * lock, the kick-notice posture (the core send takes its own locks). Nobody else is told anything: a
     * public line would hand a troll a trophy. Reader thread, the session's admin bit already checked.
     *
     */
    private List<String> adminChatMute(PeerSession by, int bySeatId, ChatCommands.Parsed parsed) {
        List<AdminChatMuteCommand.Target> live = new ArrayList<AdminChatMuteCommand.Target>();
        InetAddress requester = by.peer == null ? null : by.peer.getAddress();
        List<ChatMuteSet.Entry> snapshot;
        long now;
        synchronized (door.lock) {
            for (PeerSession s : door.sessionsById.values()) {
                if (s.isLiveLocked() && s.seatId >= 0) {
                    live.add(new AdminChatMuteCommand.Target(s.sessionId, s.seatId, s.admin(), s.observer,
                            s.observerOrdinal, chatLineName(s), chatMuteIdKeyOrNull(s),
                            chatMuteSrcKeyOrNull(s, requester), s.chatBucketRefillMillis));
                }
            }
            snapshot = door.chatMutes.snapshotNewestFirst();
            now = nowMillis();
        }
        List<ChatMuteSet.Entry> pin = by.chatMutePin;
        AdminChatMuteCommand.Result result = AdminChatMuteCommand.handle(parsed, by.sessionId, live, snapshot,
                pin, by.chatMuteMenuPin, now);
        if (result.menuPin() != null) {
            by.chatMuteMenuPin = result.menuPin();
        }
        if (result.pin() != null) {
            by.chatMutePin = result.pin();
        }
        AdminChatMuteCommand.Target t = result.muteTarget();
        ChatMuteSet.Entry lifting = result.liftEntry();
        if (t == null && lifting == null) {
            return result.replyLines();
        }
        String reply;
        String row = null;
        PeerSession mutedLive = null;
        List<PeerSession> freed = new ArrayList<PeerSession>();
        synchronized (door.lock) {
            if (t != null) {
                ChatMuteSet.Verdict verdict = door.chatMutes.add(new ChatMuteSet.Entry(t.idKeyOrNull(),
                        t.srcKeyOrNull(), ChatMuteSet.Origin.CHAT, AdminChatMuteCommand.entryLabel(t), now));
                reply = switch (verdict) {
                    case ADDED -> AdminChatMuteCommand.muteReceipt(t.seatId(), t.observer(), t.observerOrdinal(),
                            t.idKeyOrNull() != null, t.srcKeyOrNull() != null,
                            othersOnAddressLocked(t.srcKeyOrNull(), t.sessionId()));
                    case ALREADY_MUTED -> AdminChatMuteCommand.ALREADY_MUTED;
                    case CAP_FULL -> AdminChatMuteCommand.CAP_FULL;
                    case NO_KEY -> AdminChatMuteCommand.NO_KEY;
                };
                if (verdict == ChatMuteSet.Verdict.ADDED) {
                    PeerSession target = door.sessionsById.get(t.sessionId());
                    mutedLive = target != null && target.isLiveLocked() ? target : null;
                    row = "admin_chat_mute by_seat=" + bySeatId + " by_session=" + by.sessionId
                            + " target_seat=" + t.seatId() + " target_session=" + t.sessionId()
                            + " observer=" + t.observer() + " keys=" + keysToken(t.idKeyOrNull(), t.srcKeyOrNull())
                            + " entries=" + door.chatMutes.size();
                }
            } else if (!door.chatMutes.lift(lifting)) {
                reply = AdminChatMuteCommand.NO_SUCH_MUTE; // lifted from another surface since the snapshot
            } else {
                ChatMuteSet.Entry stillVia = freedByLiftLocked(List.of(lifting), freed);
                int index = pin == null ? 0 : AdminChatMuteCommand.indexOfKeys(pin, lifting) + 1;
                reply = AdminChatMuteCommand.liftReceipt(index, stillVia, pin);
                row = "admin_chat_unmute by_seat=" + bySeatId + " by_session=" + by.sessionId
                        + " keys=" + keysToken(lifting.idKeyOrNull(), lifting.srcKeyOrNull())
                        + " entries=" + door.chatMutes.size() + " freed=" + freed.size()
                        + " still=" + (stillVia != null);
            }
        }
        if (mutedLive != null) {
            door.sendCtrlToSession(mutedLive, ChatFrameCodec.encodeDown(0, "server", AdminChatMuteCommand.MUTED_NOTICE));
        }
        for (PeerSession s : freed) {
            door.sendCtrlToSession(s, ChatFrameCodec.encodeDown(0, "server", AdminChatMuteCommand.LIFTED_NOTICE));
        }
        if (row != null) {
            System.out.println("pilot-datagram-server " + row);
        }
        return List.of(reply);
    }

    /**
     * Lock held, AFTER the lift: every live non-admin session one of {@code lifted} covered, sorted by whether any
     * stored entry still covers it — none = freed (added to {@code freedOut}; those alone get the lifted notice),
     * else it stays muted and the first such session's newest covering entry is returned ({@code null} = every
     * covered session was freed; the chat receipt's "still muted via"). Judged per SESSION because a covered
     * player's OTHER key may sit in another entry (a shared address). No entry is excepted: the set refuses an entry
     * whose key is already covered, so once {@code lifted} is out of the set no stored entry shares its keys.
     * WHY one helper: the chat lift and the console lift must free exactly the same players.
     *
     */
    private ChatMuteSet.Entry freedByLiftLocked(List<ChatMuteSet.Entry> lifted, List<PeerSession> freedOut) {
        ChatMuteSet.Entry stillVia = null;
        for (PeerSession s : door.sessionsById.values()) {
            if (!s.isLiveLocked() || s.seatId < 0 || s.admin()) {
                continue;
            }
            String id = chatMuteIdKeyOrNull(s);
            String src = chatMuteSrcKeyOrNull(s, null);
            boolean covered = false;
            for (ChatMuteSet.Entry e : lifted) {
                covered |= e.matches(id, src);
            }
            if (covered) {
                List<ChatMuteSet.Entry> covering = door.chatMutes.coveringExcept(id, src, null);
                if (covering.isEmpty()) {
                    freedOut.add(s);
                } else if (stillVia == null) {
                    stillVia = covering.get(0);
                }
            }
        }
        return stillVia;
    }

    /**
     * The house verbs' outcome, every field a bounded token or a count (the serve row prints them all):
     * {@code status} = {@code ok} | {@code already_muted} | {@code cap_full} | {@code no_key} | {@code not_muted}
     * | {@code no_live_session} | {@code target_changed} | {@code target_admin}; {@code keys} = which of the
     * occupant's two mute keys exist ({@code id+src} | {@code id} | {@code src} | {@code none}, never a value);
     * {@code entries} = the mute list's size after the verb (0 to {@link ChatMuteSet#CAP}); {@code lifted} = the
     * entries an unmute removed (0 for a mute); {@code freed} = the live sessions that removal left unmuted.
     *
     */
    record OperatorChatMuteOutcome(String status, String keys, int entries, int lifted, int freed) {
    }

    /**
     * The operator's {@code chat-mute <seat> [<cidkey>]} / {@code chat-unmute <seat> [<cidkey>]} (the house file,
     * the ops console's press, a replay): the seat's LIVE occupant under the kick / ban expected-occupant law — no
     * live session = {@code no_live_session}; a non-empty {@code expectedCidKeyOrEmpty} that is not the occupant's
     * own cid key (or an occupant with no client id) = {@code target_changed}, never compared on a replay door,
     * whose occupants carry the door's own client ids and whose reel carries the key's digest (as
     * {@link PilotIntentDatagramServer#kickSeatSession} and {@code banSession} skip it); an admin occupant =
     * {@code target_admin} (admins are never muted). A mute stores ONE console entry on the occupant's two keys,
     * derived exactly as the chat verb derives them with no requester address (the console has none); an unmute
     * lifts EVERY entry either key matches (a chat mute and a console mute alike). Find, compare, mutate and the
     * freed judgement ride ONE door-lock take, so the seat cannot change hands mid-verb; the notices (the target
     * on a stored mute, each freed session on a lift) go out AFTER the lock, the kick-notice posture (the core
     * send takes its own locks). Nobody else is told anything. Serve (sim) thread, once per operator command.
     *
     */
    OperatorChatMuteOutcome operatorChatMute(int seatId, String expectedCidKeyOrEmpty, boolean mute) {
        String expectedKey = expectedCidKeyOrEmpty == null ? "" : expectedCidKeyOrEmpty;
        PeerSession mutedLive = null;
        List<PeerSession> freed = new ArrayList<PeerSession>();
        String status;
        String keys = keysToken(null, null);
        int lifted = 0;
        int entries;
        synchronized (door.lock) {
            PeerSession target = null;
            for (PeerSession s : door.sessionsById.values()) {
                if (s.isLiveLocked() && s.seatId() == seatId) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                status = "no_live_session";
            } else if (!door.isReplay() && !expectedKey.isEmpty() && (target.clientId == null
                    || !expectedKey.equals(BanSet.cidKey(target.clientId)))) {
                status = "target_changed";
            } else if (target.admin()) {
                status = "target_admin";
            } else {
                AdminChatMuteCommand.Target t = new AdminChatMuteCommand.Target(target.sessionId, target.seatId,
                        target.admin(), target.observer, target.observerOrdinal, chatLineName(target),
                        chatMuteIdKeyOrNull(target), chatMuteSrcKeyOrNull(target, null), target.chatBucketRefillMillis);
                keys = keysToken(t.idKeyOrNull(), t.srcKeyOrNull());
                if (mute) {
                    ChatMuteSet.Verdict verdict = door.chatMutes.add(new ChatMuteSet.Entry(t.idKeyOrNull(),
                            t.srcKeyOrNull(), ChatMuteSet.Origin.CONSOLE, AdminChatMuteCommand.entryLabel(t),
                            nowMillis()));
                    status = switch (verdict) {
                        case ADDED -> "ok";
                        case ALREADY_MUTED -> "already_muted";
                        case CAP_FULL -> "cap_full";
                        case NO_KEY -> "no_key";
                    };
                    mutedLive = verdict == ChatMuteSet.Verdict.ADDED ? target : null;
                } else {
                    List<ChatMuteSet.Entry> gone = door.chatMutes.liftMatching(t.idKeyOrNull(), t.srcKeyOrNull());
                    lifted = gone.size();
                    status = lifted == 0 ? "not_muted" : "ok";
                    freedByLiftLocked(gone, freed);
                }
            }
            entries = door.chatMutes.size();
        }
        if (mutedLive != null) {
            door.sendCtrlToSession(mutedLive, ChatFrameCodec.encodeDown(0, "server", AdminChatMuteCommand.MUTED_NOTICE));
        }
        for (PeerSession s : freed) {
            door.sendCtrlToSession(s, ChatFrameCodec.encodeDown(0, "server", AdminChatMuteCommand.LIFTED_NOTICE));
        }
        return new OperatorChatMuteOutcome(status, keys, entries, lifted, freed.size());
    }

    /**
     * The name a player's chat lines carry: the session's own announced seat name, else the
     * {@code seat-N} fallback. ONE expression for the chat relay, the join line and the mute list, so a
     * {@code /chatmute <name>} match key is exactly the name the room saw on that player's lines.
     *
     */
    static String chatLineName(PeerSession s) {
        SeatNameFrameCodec.SeatNameAnnounce announce = s.seatNameAnnounce;
        return announce != null && !announce.name().isEmpty() ? announce.name() : ("seat-" + s.seatId);
    }

    /**
     * A session's text-mute ID key ({@code null} = none). A token-door PILOT keys on its seat handle
     * (the door's custom-author key {@code seat#N}: one token = one seat for the serve, so rotating the
     * client id never sheds the mute). Everyone else keys on the digest of a NON-BLANK client id only: a
     * blank id digests to a value every key-less session would share. Never the session-table key (on a
     * token door that is the raw seat token) and never an observer's seat handle (every observer is
     * seat 0, so all would share it). Final fields only, so the drop site and the mute list agree.
     *
     */
    String chatMuteIdKeyOrNull(PeerSession s) {
        boolean tokenDoor = door.passwordLedger == null && door.seatAuthority != null;
        if (tokenDoor && !s.observer && s.seatId >= 1) {
            return door.customAuthorKey(s);
        }
        return s.clientId == null || s.clientId.isBlank() ? null : s.cidAuthorDigest;
    }

    /**
     * A session's text-mute ADDRESS key ({@code null} = none): the source hold's own derivation, so the
     * existing source-hold policy knob, its loopback exemption and its self-address guard govern mutes
     * exactly as they govern bans (no knob of its own). {@code requesterOrNull} = the muting admin's
     * address when a mute is being SET (an admin never mutes their own address); {@code null} when a
     * sender is being TESTED.
     *
     */
    String chatMuteSrcKeyOrNull(PeerSession s, InetAddress requesterOrNull) {
        return door.sourceHold.wantedSrcOrNull(true, s.observer, s.peer == null ? null : s.peer.getAddress(),
                requesterOrNull);
    }

    /**
     * Lock held: is this sender's text muted? Admins never are. One {@code isEmpty()} when nobody is
     * muted (every serve with no mute), so the per-line cost stays nil until a host uses the verb.
     *
     */
    boolean chatMutedLocked(PeerSession s) {
        return !s.admin() && !door.chatMutes.isEmpty()
                && door.chatMutes.matches(chatMuteIdKeyOrNull(s), chatMuteSrcKeyOrNull(s, null));
    }

    /** {@link #chatMutedLocked} for a caller that does not hold the door lock (takes it): the join line
     *  and the build-share announce, so a muted player's written names never reach the room.
     * */
    boolean chatMuted(PeerSession s) {
        synchronized (door.lock) {
            return chatMutedLocked(s);
        }
    }

    /** Lock held: the OTHER live non-admin sessions a mute on {@code srcKeyOrNull} silences along with
     *  its target (a household or one shared address) — the receipt's collateral count; 0 for no key.
     * */
    private int othersOnAddressLocked(String srcKeyOrNull, long targetSessionId) {
        if (srcKeyOrNull == null) {
            return 0;
        }
        int n = 0;
        for (PeerSession s : door.sessionsById.values()) {
            if (s.isLiveLocked() && s.seatId >= 0 && !s.admin() && s.sessionId != targetSessionId
                    && srcKeyOrNull.equals(chatMuteSrcKeyOrNull(s, null))) {
                n++;
            }
        }
        return n;
    }

    /** Which keys an entry holds, for the log rows (the kind only, never a key value).
     * */
    private static String keysToken(String idKeyOrNull, String srcKeyOrNull) {
        return idKeyOrNull != null && srcKeyOrNull != null ? "id+src"
                : idKeyOrNull != null ? "id" : srcKeyOrNull != null ? "src" : "none";
    }

    /**
     * The admin {@code /switchteams} verb — the
     * {@link #adminKick} shape: snapshot the live set under the lock, let
     * {@link SwitchTeamsCommand} resolve (pure; the menu rides {@code listingSink} OUTSIDE the
     * lock), then deposit {@code SWITCH_TEAM} for the TARGET seat on the admin queue with this
     * session as the receipt target — the serve loop's {@link SeatTeamLane} owns the effect and
     * mails the REAL outcome (D5 reply law: no optimistic inline ack). The target is a seat id
     * the door itself resolved from a live session — never wire text. Reader thread, the
     * session's admin bit already checked by the caller.
     */
    private List<String> adminSwitchTeams(PeerSession by, int bySeatId,
            ChatCommands.Parsed parsed, ChatCommandDispatch.ListingSink listingSink) {
        List<AdminKickCommand.Target> live = new ArrayList<AdminKickCommand.Target>();
        // The menu label = each live session's OWN
        // resolved name (captured under the lock), fed by seat to the unchanged seat-name
        // parameter — never the arbiter's put-only per-seat map (a nameless successor = seat N).
        HashMap<Integer, String> ownNameBySeat = new HashMap<Integer, String>();
        synchronized (door.lock) {
            for (PeerSession s : door.sessionsById.values()) {
                if (s.isLiveLocked() && s.seatId >= 0) {
                    String name = door.resolvedSeatNameLocked(s);
                    live.add(new AdminKickCommand.Target(s.sessionId, s.seatId, s.shipId, name));
                    if (!name.isEmpty()) {
                        ownNameBySeat.putIfAbsent(s.seatId, name);
                    }
                }
            }
        }
        SwitchTeamsCommand.Result result = SwitchTeamsCommand.handle(parsed, live,
                ownNameBySeat::get, listingSink);
        if (result.switchSeatId() >= 0) {
            String mode = result.respawn() ? SeatTeamLane.MODE_RESPAWN
                    : SeatTeamLane.MODE_INSTANT;
            int tableSide = switchTeamsTableSide(result.switchSeatId());
            door.offerAdminCommand(new OperatorCommandFile.Command(
                    OperatorCommandFile.Verb.SWITCH_TEAM, result.switchSeatId(), 0f, false,
                    mode, by.sessionId));
            System.out.println("pilot-datagram-server admin_switch_team by_seat=" + bySeatId
                    + " by_session=" + by.sessionId + " target_seat=" + result.switchSeatId()
                    + " mode=" + mode + (tableSide >= 0 ? " table_side=" + tableSide : ""));
        }
        return result.replyLines();
    }

    /**
     * The admin switch in the PvP shape: the door's team table is the team truth (the join pick and
     * {@code /team} count by it), so the switch writes it FIRST — the target seat moves to the other
     * team with no cap (an admin may unbalance the teams on purpose) — and sets the holder's pending
     * team side, so a {@code TEAM_PICK} lands after the admin drain and leaves the serve's fly-in side
     * equal to the table whatever the switch's own toggle read. Returns the side written, or
     * {@code -1} off the PvP shape (nothing written: the switch stays the plain toggle).
     *
     */
    private int switchTeamsTableSide(int targetSeatId) {
        synchronized (door.lock) {
            SeatTeamTable teams = door.seatTeams();
            if (teams == null || !teams.pvp()) {
                return -1;
            }
            int now = teams.effectiveSide(targetSeatId);
            if (now != 0 && now != 1) {
                return -1; // not a descriptor seat: the table has nothing to move
            }
            PeerSession holder = null;
            for (PeerSession s : door.sessionsById.values()) {
                if (holdsSeatLocked(s) && s.seatId == targetSeatId) {
                    holder = s;
                    break;
                }
            }
            int side = 1 - now;
            door.readerCounters.teamMemoryEvicted += teams.pick(targetSeatId,
                    holder == null ? null : holder.clientId, side);
            if (holder != null) {
                holder.pendingTeamSide = side;
            }
            return side;
        }
    }

    /** True when this door counts teams by its table (the PvP shape): the {@code /team} verb's gate. */
    private boolean teamsArmed() {
        synchronized (door.lock) {
            SeatTeamTable teams = door.seatTeams();
            return teams != null && teams.pvp();
        }
    }

    /** The {@code /team} refusal on every shape but PvP (the teams follow the match's seats there). */
    static final String TEAMS_FIXED = "Teams are fixed in this match.";

    /** Side index -> the fixed map-edge label the player reads (0 = South, 1 = North). */
    private static String edge(int side) {
        return side == 0 ? "South" : "North";
    }

    /**
     * The player {@code /team} verb, on the caller's OWN door-bound seat. The door decides under its
     * lock against the team table: off the PvP shape the constant refusal; SHOW = the caller's team and
     * both teams' counts; SET = refused when already there or when the asked team has no room for this
     * player (the caller's own seat not counted), else the table records the pick, the session's
     * pending team side is set (repeat SETs before the serve's drain coalesce: the last wins) and the
     * receipt says it lands at the next respawn. The texts carry digits and fixed words only (never
     * player text); one stdout row per committed SET carries the seat and side digits.
     *
     */
    String teamVerb(PeerSession session, int seatId, ChatCommands.Parsed parsed) {
        synchronized (door.lock) {
            SeatTeamTable teams = door.seatTeams();
            int now = teams == null || !teams.pvp() ? -1 : teams.effectiveSide(seatId);
            if (now != 0 && now != 1) {
                return TEAMS_FIXED;
            }
            if (parsed.kind() == ChatCommands.Kind.TEAM_SHOW) {
                return "You are on the " + edge(now) + " team (South " + teams.effectiveCount(0) + " of "
                        + teams.cap(0) + ", North " + teams.effectiveCount(1) + " of " + teams.cap(1) + ").";
            }
            int side = SeatTeamLane.sideOfWordOrNeg(parsed.arg());
            if (side < 0) {
                return ChatCommands.TEAM_USAGE; // the grammar admits only the two words
            }
            if (side == now) {
                return "You are already on the " + edge(side) + " team.";
            }
            if (!teams.hasRoomFor(side, seatId)) {
                return "The " + edge(side) + " team is full (" + teams.effectiveCount(side) + " of "
                        + teams.cap(side) + "). Stay on the " + edge(now) + " team, or wait for a seat and type"
                        + " /team " + SeatTeamLane.wordOfSide(side) + " again.";
            }
            door.readerCounters.teamMemoryEvicted += teams.pick(seatId, session.clientId, side);
            session.pendingTeamSide = side;
            System.out.println("pilot-datagram-server team_set seat=" + seatId + " side=" + side);
            return "Next respawn on the " + edge(side) + " team. Type /suicide to switch now.";
        }
    }

    /**
     * Does {@code session} still own what its chat speaks
     * for? Live ({@link PeerSession#isLiveLocked}: not failed, no peer BYE, door open) AND not
     * seat-vacated. The CTRL delivery head drops a non-live session's CHTU undecoded,
     * so this check is the BELT behind it: a session that died on another thread
     * between the head and this decode, or (unreachable today) a seat vacated on a live session.
     * Door lock held.
     */
    private static boolean holdsSeatLocked(PeerSession session) {
        return session.isLiveLocked() && !session.seatVacated;
    }

    /**
     * The chat USE-TIME gates' predicate
     * ({@link AdminSeatDebugLane#godModeChat}, also {@code teleportCommand} + the chat {@code /podtest}
     * start, each called from the serve's operator switch on the SIM thread at apply time): true iff session {@code sessionId} (signed, never 0) is still in the
     * table, {@link #holdsSeatLocked}, and holds {@code seatId} (1..64) — the door's
     * {@code bound(sessionId, seat)} shape plus the seat-vacated exclusion. It closes the race the
     * {@link #onChatUp} drop cannot: a kick / timeout / takeover between the door check and the
     * drain. Takes {@code door.lock} only; the same switch already takes it (KICK,
     * {@code seatHasLiveSession}), so no new lock-order edge. Never cached.
     */
    boolean sessionHoldsSeat(long sessionId, int seatId) {
        synchronized (door.lock) {
            PeerSession s = door.sessionsById.get(sessionId);
            return s != null && holdsSeatLocked(s) && s.seatId == seatId;
        }
    }

    /** One CTRL chat send — decode (malformed = the family's fail-closed
     * posture via the caller's IOException catch), drop a stale sender (a
     * BYE'd / failed / seat-vacated session, counted, before the bucket — no verb, no fan, no
     * reply; the caller's liveness head drops a non-live session's CHTU before this
     * decode, so the check here is the seat-vacated / lost-race belt), rate-gate (POLICY, not protocol: a drained bucket drops + counts, the session
     * survives — a conforming client mirrors the bucket and never hits it), stamp
     * attribution, then relay to ALL live sessions — EXCEPT a line
     * starting {@code /} (a command) or a LED command line ({@code ChatCommands.ledCommandLine}): NEVER fanned.
     * Not sink-recorded on the UP side (the SNAM posture: session chatter, not gameplay wire
     * truth — the fan's one KIND_DOWN record is the wire-truth stamp). */
    void onChatUp(PeerSession session, byte[] payload) throws IOException {
        ChatFrameCodec.ChatUp chat = ChatFrameCodec.decodeUp(payload);
        int seatId;
        String name;
        boolean muted;
        synchronized (door.lock) {
            if (!holdsSeatLocked(session)) {
                door.counters.chatStaleSessionDropped++;
                return;
            }
            if (session.seatId < 0) {
                // Unbound seat (the legacy null-authority smoke door): CHTD requires a real
                // seat id, so there is nothing truthful to stamp — counted drop, not a death
                // (a seatless peer is a test artifact, not hostile).
                door.counters.chatUnseatedDropped++;
                return;
            }
            long now = nowMillis();
            // The seat's admin bit picks the bucket per send - burst cap
            // AND rate (an admin's host card costs two sends a press). No text is read here. Never a top-up: an
            // untouched bucket holds its initial plain burst and a seat that turns admin later keeps its tokens -
            // either refills at the admin rate toward the admin cap from its next line. The cap clamps every
            // touched send (a bucket carried from an admin predecessor shrinks to the plain burst at once).
            boolean admin = session.admin();
            int burst = admin ? ADMIN_CHAT_BUCKET_BURST : CHAT_BUCKET_BURST;
            long refillMillis = admin ? ADMIN_CHAT_TOKEN_REFILL_MILLIS : CHAT_TOKEN_REFILL_MILLIS;
            if (session.chatBucketRefillMillis != 0L) {
                long elapsed = now - session.chatBucketRefillMillis;
                double refill = elapsed > 0L ? elapsed / (double) refillMillis : 0.0;
                session.chatTokens = Math.min((double) burst, session.chatTokens + refill);
            }
            session.chatBucketRefillMillis = now;
            if (session.chatTokens < 1.0) {
                door.counters.chatRateDropped++;
                return;
            }
            session.chatTokens -= 1.0;
            seatId = session.seatId;
            name = chatLineName(session);
            // The text-mute test sits AFTER the token debit, so the bucket stays
            // the only rate law and the private notice below costs one paid token each (no amplifier).
            muted = chatMutedLocked(session);
        }
        String text = chat.text();
        if (text.startsWith("/")) {
            dispatchChatCommand(session, seatId, text);
            return;
        }
        if (ChatCommands.ledCommandLine(text)) {
            // A command behind an invisible leading run, or a
            // look-alike-slash /auth, may carry the admin secret — never relayed (fanChat sends it to every seat and
            // prints it on the chat_relay row). The sender alone gets a constant; the row below carries NO text.
            // READER thread, the rate token already consumed: one datagram back to an admitted seat (no amplifier).
            door.sendCtrlToSession(session,
                    ChatFrameCodec.encodeDown(seatId, "server", ChatCommands.LED_COMMAND_NOT_SENT));
            synchronized (door.lock) {
                door.counters.chatCommandsHandled++;
            }
            System.out.println("pilot-datagram-server chat_led_command seat=" + seatId
                    + " session=" + session.sessionId);
            return;
        }
        if (muted) {
            // A muted sender's plain line reaches nobody, and every slash
            // command above still ran (a mute stops text reaching others, never control of the player's own
            // game). The sender alone is told, once per dropped line, so it is never a shadow mute; counted, no
            // row per line (the rate-drop posture). The notice goes out after the lock (the core send's order).
            synchronized (door.lock) {
                door.counters.chatMutedDropped++;
            }
            door.sendCtrlToSession(session,
                    ChatFrameCodec.encodeDown(seatId, "server", AdminChatMuteCommand.DROPPED_NOTICE));
            return;
        }
        door.fans.fanChat(seatId, name, text);
    }

    /**
     * W7 B1a chat-command dispatch — READER thread, the rate token already consumed. The
     * decide+state half lives in {@link ChatCommandDispatch}; this
     * side keeps the transport half: the {@code /suicide} deposit (per-SESSION flag, NOT
     * copied on takeover — that law anchors it here), the private CHTD unicast replies
     * stamped {@code "server"}, the handled counter, and the stdout audit line.
     */
    private void dispatchChatCommand(PeerSession session, int seatId, String text) {
        ChatCommands.Parsed parsed = ChatCommands.parse(text);
        // W2b E3 as re-cut by W3 L1b-ii: the arbiter is ctor-threaded on every
        // real serve; MODULE_* additionally gates on the moduleVerbsArmed twin (fortress +
        // bound mount hook) — non-fortress serves reply "unavailable" DESPITE the live
        // arbiter. The transfer census is null unless transfer.enabled armed it
        // — /transfer honestly replies "unavailable" (the pickableVariants pattern).
        ChatCommandDispatch.ListingSink listingSink = (kind, entries) -> {
            // The transport's half of the listing —
            // mint the per-seat listingId (latest-wins), encode pure, then REPLACE
            // the session's drip queue (the serve loop drains ≤8 frames/tick via
            // dripListingFrames). Encode outside the lock (pure CPU); single reader
            // thread ⇒ mint/enqueue never interleave with another mint.
            int listingId;
            synchronized (door.lock) {
                listingId = ++session.lastListingId;
            }
            List<byte[]> frames = ListingEmitter.encodeListing(
                    seatId, kind, listingId, entries);
            synchronized (door.lock) {
                session.listingDrip.clear();
                session.listingDrip.addAll(frames);
                door.counters.listingsEmitted++;
            }
            System.out.println("pilot-datagram-server listing_emit seat=" + seatId
                    + " kind=" + kind + " listing_id=" + listingId
                    + " frames=" + frames.size() + " entries=" + entries.size());
        };
        List<String> replyLines;
        List<String> customPickReply = null;
        Auth13Inputs auth13 = null;
        ChatCommands.Kind parsedKind = parsed.kind();
        if (parsedKind == ChatCommands.Kind.UNMUTE) {
            // /unmute answers EVERY session, before any gate, with the one pointer
            // that names the text verb (admins) and the voice verb (when this serve relays voice; the /help flag).
            replyLines = List.of(ChatCommands.unmutePointer(session.admin(), door.voice != null));
        } else if (session.observer && OBSERVER_BOUNCED_VERBS.contains(parsedKind)) {
            // Seat verbs act on the sender's seat/hull — an observer has
            // neither (seat 0 would reach the seat machine as an unarmed id). Bounce, count.
            door.readerCounters.observerCtrlDrops++;
            replyLines = List.of(OBSERVER_VERB_BOUNCE);
        } else if (parsedKind == ChatCommands.Kind.TEAM_SHOW || parsedKind == ChatCommands.Kind.TEAM_SET) {
            replyLines = List.of(teamVerb(session, seatId, parsed));
        } else if (parsedKind == ChatCommands.Kind.MUTE_LIST || parsedKind == ChatCommands.Kind.MUTE_SET
                || parsedKind == ChatCommands.Kind.MUTE_WIRE) {
            // W12 voice L2: /mute rides the session's identity
            // (never wire text) and the same listing sink; voice==null ⇒ "unavailable".
            // Names = each live session's OWN
            // resolved name captured under the lock (the adminSwitchTeams idiom), fed by seat —
            // never the arbiter's put-only per-seat map (a nameless successor = seat N). This
            // dispatch runs OUTSIDE the lock (onChatUp's block closed before the call).
            // + the live OBSERVER sessions' door ordinals from the same
            // lock snapshot (server truth; r4: the ONE "observer N") — the /mute handle rows;
            // VoiceRelay.Endpoint is not widened.
            HashMap<Integer, String> ownNameBySeat = new HashMap<Integer, String>();
            HashMap<Long, Integer> observerOrdinals = new HashMap<Long, Integer>();
            synchronized (door.lock) {
                for (PeerSession s : door.sessionsById.values()) {
                    if (s.isLiveLocked() && s.seatId >= 0) {
                        String name = door.resolvedSeatNameLocked(s);
                        if (!name.isEmpty()) {
                            ownNameBySeat.putIfAbsent(s.seatId, name);
                        }
                        if (s.observer) {
                            observerOrdinals.put(s.sessionId, s.observerOrdinal);
                        }
                    }
                }
            }
            replyLines = VoiceMuteCommand.handle(parsed, session.sessionId, seatId, door.voice,
                    ownNameBySeat::get, sid -> observerOrdinals.getOrDefault(sid, 0), listingSink);
            // An admin who just muted a player's VOICE for themselves may have meant the text mute, which is
            // a different verb: the pointer rides a successful mute reply only — a seat
            // OR an observer target; no other VoiceMuteCommand line starts with "muted ".
            if (session.admin() && !replyLines.isEmpty() && replyLines.get(0).startsWith("muted ")) {
                replyLines = List.of(replyLines.get(0), "/chatmute stops their text chat for everyone");
            }
        } else if (parsedKind == ChatCommands.Kind.WAVE_LIST) {
            // Bare /wave = the category MENU
            // — a KIND_WAVE picker listing over the fixed set (the
            // bare-/spawnfoe precedent); the pick pastes "/wave <cat>". Admin-gated like every
            // wave verb; emitted regardless of a wave lane (the pick's receipt is the honest
            // "unavailable" on a schedule-less serve, the SET_WAVE_CATEGORY drain's own law).
            if (!session.admin()) {
                replyLines = List.of(ChatCommands.ADMIN_ONLY);
            } else {
                listingSink.emitListing(ListingFrameCodec.KIND_WAVE,
                        ListingEmitter.waveEntries(WaveSchedule.WAVE_CATEGORIES));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_wave verb=list seat=" + seatId
                        + " session=" + session.sessionId);
            }
        } else if (parsedKind == ChatCommands.Kind.WAVE_SKIP
                || parsedKind == ChatCommands.Kind.WAVE_PAUSE
                || parsedKind == ChatCommands.Kind.WAVE_WRAP //
                || parsedKind == ChatCommands.Kind.END_MATCH //
                || parsedKind == ChatCommands.Kind.WAVE_CATEGORY
                || parsedKind == ChatCommands.Kind.WAVE_SET
                || parsedKind == ChatCommands.Kind.ENDLESS_SHOW
                || parsedKind == ChatCommands.Kind.ENDLESS_SET
                || parsedKind == ChatCommands.Kind.WAVE_INFLATE_SHOW
                || parsedKind == ChatCommands.Kind.WAVE_INFLATE_SET
                || parsedKind == ChatCommands.Kind.KNOB_SHOW
                || parsedKind == ChatCommands.Kind.KNOB_SET
                || parsedKind == ChatCommands.Kind.TERRAIN_SHOW //
                || parsedKind == ChatCommands.Kind.TERRAIN_SET) {
            // Admin verb — the SESSION's HELLO-derived bit is the whole gate (never
            // wire text). Deposit for the serve loop; the effect row is the serve loop's own
            // operator_skip_wave / operator_pause_wave / operator_wave_category (the same switch
            // as the house file). The deposit carries this session as the receipt
            // target and the door sends NO inline reply — the serve loop mails the drained verb's
            // REAL effect back (resulting state + effect timing; a door-side "toggled/requested"
            // guess was the owner's meaningless-reply defect). One tick of receipt latency
            // in-round; out-of-round the receipt lands when the effect does (honest timing).
            if (!session.admin()) {
                replyLines = List.of(ChatCommands.ADMIN_ONLY);
            } else if (parsedKind == ChatCommands.Kind.WAVE_CATEGORY) {
                // D2: the grammar already validated the category against the fixed set; the
                // swap itself lands at the serve loop's round boundary.
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.SET_WAVE_CATEGORY, 0, 0f, false, parsed.arg(),
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_wave verb=category arg="
                        + parsed.arg() + " seat=" + seatId + " session=" + session.sessionId);
            } else if (parsedKind == ChatCommands.Kind.WAVE_SET) {
                // The digit is grammar-bounded (1..9); the drain bounds it
                // against the chain's real length and mails the outcome (set or refused).
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.SET_WAVE_NUMBER, 0, 0f, false, parsed.arg(),
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_wave verb=set arg="
                        + parsed.arg() + " seat=" + seatId + " session=" + session.sessionId);
            } else if (parsedKind == ChatCommands.Kind.ENDLESS_SHOW
                    || parsedKind == ChatCommands.Kind.ENDLESS_SET) {
                // Bare /endless deposits the fixed word "show" (a state
                // receipt from the sim thread — the dial lives there); on|off is the folded
                // grammar word. The drain owns the reset-lane refusal.
                String word = parsedKind == ChatCommands.Kind.ENDLESS_SHOW ? "show" : parsed.arg();
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.SET_ENDLESS, 0, 0f, false, word,
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_wave verb=endless arg=" + word
                        + " seat=" + seatId + " session=" + session.sessionId);
            } else if (parsedKind == ChatCommands.Kind.WAVE_INFLATE_SHOW
                    || parsedKind == ChatCommands.Kind.WAVE_INFLATE_SET) {
                // The /endless shape verbatim — bare deposits the fixed word "show"
                // (the dial lives on the sim thread), on|off is the folded grammar word; the
                // drain owns the no-lane refusal and the ON pre-inflate.
                String word = parsedKind == ChatCommands.Kind.WAVE_INFLATE_SHOW ? "show" : parsed.arg();
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.SET_WAVE_INFLATE, 0, 0f, false, word,
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_wave verb=inflate arg=" + word
                        + " seat=" + seatId + " session=" + session.sessionId);
            } else if (parsedKind == ChatCommands.Kind.KNOB_SHOW
                    || parsedKind == ChatCommands.Kind.KNOB_SET) {
                // /knob — the wire-delivered client knob census (WireKnob), the
                // /endless shape: the grammar folded every token against FIXED sets ("list",
                // "<knob> show", "<knob> <word>"); the drain (AdminKnobVerb) sets, re-spools the
                // PSTB block to every live session and mails the REAL effect as the receipt.
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.SET_KNOB, 0, 0f, false, parsed.arg(),
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_knob arg=" + parsed.arg()
                        + " seat=" + seatId + " session=" + session.sessionId);
            } else if (parsedKind == ChatCommands.Kind.TERRAIN_SHOW
                    || parsedKind == ChatCommands.Kind.TERRAIN_SET) {
                // /terrain — the two terrain knobs in one verb, the /knob
                // shape: the grammar folded every token against the knobs' FIXED choice tables
                // ("show", "<word>", "<word> <level>"); the drain (AdminTerrainVerb) sets both,
                // re-spools PSTB, re-applies the stat lane on the live ships, mails ONE receipt.
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.SET_TERRAIN, 0, 0f, false, parsed.arg(),
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_terrain arg=" + parsed.arg()
                        + " seat=" + seatId + " session=" + session.sessionId);
            } else if (parsedKind == ChatCommands.Kind.WAVE_WRAP) {
                // /wave wrap = a chat door to the SAME
                // Verb.END_ROUND arm the house file / ops GUI / web drive (the real wrapRound
                // boundary). This session is the receipt target: the serve drain refuses on the
                // serve's last round and latches a double press, both with constant receipts.
                // Its own branch so it never falls into the else below (non-skip = PAUSE_WAVE).
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.END_ROUND, 0, 0f, false, "",
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_wave verb=wrap seat=" + seatId
                        + " session=" + session.sessionId);
            } else if (parsedKind == ChatCommands.Kind.END_MATCH) {
                // /endmatch = a chat door to the SAME Verb.END_MATCH arm the house file /
                // ops GUI / web drive (the serve ends after that tick with the goodbye fan; wins over
                // end-round). The session rides as receipt target only for the queue-full eviction
                // notice: the drain mails no receipt, the match ending IS the effect every seat sees.
                // Its own branch so it never falls into the else below (non-skip = PAUSE_WAVE).
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.END_MATCH, 0, 0f, false, "",
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_endmatch seat=" + seatId
                        + " session=" + session.sessionId);
            } else {
                boolean skip = parsedKind == ChatCommands.Kind.WAVE_SKIP;
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        skip ? OperatorCommandFile.Verb.SKIP_WAVE
                                : OperatorCommandFile.Verb.PAUSE_WAVE,
                        0, 0f, false, "", session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_wave verb="
                        + (skip ? "skip" : "pause") + " seat=" + seatId
                        + " session=" + session.sessionId);
            }
        } else if (parsedKind == ChatCommands.Kind.HOST_SHIP_LIST
                || parsedKind == ChatCommands.Kind.HOST_SHIP_KEEP
                || parsedKind == ChatCommands.Kind.HOST_SHIP_REMOVE
                || parsedKind == ChatCommands.Kind.HOST_SHIP_RESTORE) {
            // The host's ship-list verbs — the /endmatch deposit shape. The
            // SESSION's admin bit is the whole gate (never the text); the serve's drain classifies the id, does the
            // work and mails the receipts back to this session (no inline reply). The id rides as typed; the row
            // below names only the verb (the chat_command audit row carries the line).
            if (!session.admin()) {
                replyLines = List.of(ChatCommands.ADMIN_ONLY);
            } else {
                OperatorCommandFile.Verb verb = switch (parsedKind) {
                    case HOST_SHIP_LIST -> OperatorCommandFile.Verb.SHIP_LIST;
                    case HOST_SHIP_KEEP -> OperatorCommandFile.Verb.SHIP_KEEP;
                    case HOST_SHIP_REMOVE -> OperatorCommandFile.Verb.SHIP_REMOVE;
                    default -> OperatorCommandFile.Verb.SHIP_RESTORE;
                };
                door.offerAdminCommand(new OperatorCommandFile.Command(verb, 0, 0f, false,
                        parsedKind == ChatCommands.Kind.HOST_SHIP_LIST ? "" : parsed.arg(), session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_ships verb=" + verb + " seat=" + seatId
                        + " session=" + session.sessionId);
            }
        } else if (parsedKind == ChatCommands.Kind.KICK_LIST
                || parsedKind == ChatCommands.Kind.KICK_SET
                || parsedKind == ChatCommands.Kind.KICK_WIRE) {
            replyLines = session.admin() ? adminKick(session, seatId, parsed, listingSink)
                    : List.of(ChatCommands.ADMIN_ONLY);
        } else if (parsedKind == ChatCommands.Kind.BAN_USAGE
                || parsedKind == ChatCommands.Kind.BAN_LIST
                || parsedKind == ChatCommands.Kind.BAN_WIRE
                || parsedKind == ChatCommands.Kind.UNBAN_LIST
                || parsedKind == ChatCommands.Kind.UNBAN_INDEX
                || parsedKind == ChatCommands.Kind.BANNED_RETIME
                || parsedKind == ChatCommands.Kind.BANS_LIST) {
            // The P27 chat verbs, admin-gated
            // exactly like /kick (the SESSION bit, never wire text).
            // + the two no-arg menus (BAN_LIST / UNBAN_LIST ride the LSTD sink).
            replyLines = session.admin() ? adminBan(session, seatId, parsed, listingSink)
                    : List.of(ChatCommands.ADMIN_ONLY);
        } else if (parsedKind == ChatCommands.Kind.CHATMUTE_LIST
                || parsedKind == ChatCommands.Kind.CHATMUTE_INDEX
                || parsedKind == ChatCommands.Kind.CHATMUTE_NAME
                || parsedKind == ChatCommands.Kind.CHATMUTE_USAGE
                || parsedKind == ChatCommands.Kind.CHATUNMUTE_LIST
                || parsedKind == ChatCommands.Kind.CHATUNMUTE_PAGE
                || parsedKind == ChatCommands.Kind.CHATUNMUTE_INDEX) {
            // The text-mute verbs, admin-gated like /kick (the SESSION bit,
            // never wire text); answered HERE because the set and the mute keys are door-owned.
            replyLines = session.admin() ? adminChatMute(session, seatId, parsed)
                    : List.of(ChatCommands.ADMIN_ONLY);
        } else if (parsedKind == ChatCommands.Kind.SWITCHTEAMS_LIST
                || parsedKind == ChatCommands.Kind.SWITCHTEAMS_INSTANT
                || parsedKind == ChatCommands.Kind.SWITCHTEAMS_RESPAWN) {
            replyLines = session.admin() ? adminSwitchTeams(session, seatId, parsed, listingSink)
                    : List.of(ChatCommands.ADMIN_ONLY);
        } else if (parsedKind == ChatCommands.Kind.DEBUGSHIPS_LIST
                || parsedKind == ChatCommands.Kind.DEBUGSHIPS_SET) {
            // The verb BODY (store + monitor discipline) lives in the
            // collaborator; the door keeps the admin gate + the PICK audit row (the
            // exact-fold re-probe mirrors dispatch's own PICK test on the same snapshot).
            PickableVariants debugCatalogue = door.debugVariants;
            if (!session.admin()) {
                replyLines = List.of(ChatCommands.ADMIN_ONLY);
            } else {
                replyLines = door.chatCommands.debugShips(parsed, seatId, debugCatalogue,
                        door.moduleSeats, listingSink);
                String picked = parsedKind == ChatCommands.Kind.DEBUGSHIPS_SET
                        && debugCatalogue != null
                                ? debugCatalogue.canonicalIdOrNull(parsed.arg()) : null;
                if (picked != null) {
                    System.out.println("pilot-datagram-server debug_ships seat=" + seatId
                            + " session=" + session.sessionId + " arg=" + picked);
                }
            }
        } else if (parsedKind == ChatCommands.Kind.SPAWNFOE
                || parsedKind == ChatCommands.Kind.SPAWNFOE_LIST
                || parsedKind == ChatCommands.Kind.SPAWNFRIEND
                || parsedKind == ChatCommands.Kind.SPAWNFRIEND_LIST) {
            // Validate AT THE DOOR — the id must canonicalize against the boot
            // catalogue (or a listed kept copy, below) before it may ride toward the engine (an unresolvable id at spawn
            // would be serve-fatal there; the serve loop's fail-soft try is the second net,
            // this allowlist the first). The deposit rides the admin wave queue onto the sim
            // thread; effect rows are the serve loop's operator_spawn_foe/adhoc_foe_spawn.
            // Bare /spawnfoe emits the full catalogue as a KIND_SPAWNFOE picker
            // listing; a non-exact arg WITHOUT an explicit count (the Parsed 0 sentinel) falls
            // to the substring-FILTER listing (the debugShips UX); with an explicit count the
            // hard unknown-variant reply stands — typing a count states spawn intent.
            // /spawnfriend = this block VERBATIM with the
            // player-side verb, listing kind and log row — the serve's switch reads the verb
            // as the side (SPAWN_FRIEND ⇒ side 0); nothing else differs.
            boolean friendly = parsedKind == ChatCommands.Kind.SPAWNFRIEND
                    || parsedKind == ChatCommands.Kind.SPAWNFRIEND_LIST;
            boolean bareList = parsedKind == ChatCommands.Kind.SPAWNFOE_LIST
                    || parsedKind == ChatCommands.Kind.SPAWNFRIEND_LIST;
            int listingKind = friendly ? ListingFrameCodec.KIND_SPAWNFRIEND
                    : ListingFrameCodec.KIND_SPAWNFOE;
            // Every read below is the catalogue's SPAWN view: the deposit replays re-parsed under the
            // operator-token grammar, so only an id inside it is listed, matched or deposited.
            // Plus the KEPT copies the /ship list holds now, under that same
            // grammar (PickableVariants.spawnListWithKept): the registry copy is taken at boot, before any kept
            // copy registers, so a host's own kept builds were never offered. They lead the rows and match by id,
            // display name or hull; the exact pick still resolves an id only. A kept row carries that name and hull as
            // its labels (display only, sanitized at emission), every other row stays id-only.
            PickableVariants debugCatalogue = door.debugVariants;
            PickableVariants shipCatalogue = door.pickableVariants;
            if (!session.admin()) {
                replyLines = List.of(ChatCommands.ADMIN_ONLY);
            } else if (debugCatalogue == null || debugCatalogue.spawnIds().isEmpty()) {
                replyLines = List.of("debug picking unavailable on this server");
            } else if (bareList) {
                listingSink.emitListing(listingKind, ListingEmitter.keptLabelledSpawnEntries(
                        PickableVariants.spawnListWithKept(debugCatalogue, shipCatalogue, null), friendly,
                        shipCatalogue));
                replyLines = List.of();
            } else {
                String canonical = PickableVariants.spawnCanonicalWithKeptOrNull(debugCatalogue, shipCatalogue,
                        parsed.arg());
                if (canonical == null && parsed.count() == 0) {
                    List<String> matches = PickableVariants.spawnListWithKept(debugCatalogue, shipCatalogue,
                            parsed.arg());
                    if (matches.isEmpty()) {
                        replyLines = List.of("no variants match '"
                                + ChatCommandDispatch.boundEcho(parsed.arg()) + "'");
                    } else {
                        listingSink.emitListing(listingKind,
                                ListingEmitter.keptLabelledSpawnEntries(matches, friendly, shipCatalogue));
                        replyLines = List.of();
                    }
                } else if (canonical == null) {
                    // The spawn view is grammar-filtered, so an id that
                    // /debugships lists can still be unspawnable - the reply names the grammar.
                    replyLines = List.of("unknown variant '"
                            + ChatCommandDispatch.boundEcho(parsed.arg())
                            + "' - not spawnable (id = 1-64 of A-Z a-z 0-9 . _ : -); /debugships lists ids");
                } else {
                    // The D5 reply law — deposit carries this session
                    // as the receipt target, the door sends NO inline ack (the optimistic
                    // "foe spawn requested" hid every post-ack outcome); the serve loop mails
                    // the entry-exhaustion receipt with the REAL outcome, and queue-full /
                    // no-hook refusals mail immediately (AdhocFoeSpawner texts).
                    int count = Math.max(1, parsed.count());
                    // The grammar-validated `@x,y` token rides the deposit
                    // verbatim (the serve's operator switch re-validates + map-clamps it).
                    String poseSuffix = parsed.pose() == null ? "" : " " + parsed.pose();
                    // The grammar's FIXED `officer` keyword rides between count and
                    // pose — text = "<variant> <count>[ officer][ @x,y]", the house-file twin's
                    // exact shape (one serve-side parse, AdminWaveVerbs.spawn).
                    String officerSuffix = parsed.officer()
                            ? " " + ChatCommands.SPAWN_OFFICER_KEYWORD : "";
                    door.offerAdminCommand(new OperatorCommandFile.Command(
                            friendly ? OperatorCommandFile.Verb.SPAWN_FRIEND
                                    : OperatorCommandFile.Verb.SPAWN_FOE,
                            0, 0f, false, canonical + " " + count + officerSuffix + poseSuffix,
                            session.sessionId));
                    replyLines = List.of();
                    System.out.println("pilot-datagram-server "
                            + (friendly ? "admin_spawn_friend" : "admin_spawn_foe")
                            + " seat=" + seatId
                            + " session=" + session.sessionId + " variant=" + canonical
                            + " count=" + count + " officer=" + parsed.officer()
                            + " pose=" + (parsed.pose() == null ? "-"
                                    : parsed.pose().substring(1)));
                }
            }
        } else if (parsedKind == ChatCommands.Kind.DESPAWN) {
            // The SESSION's admin bit is the whole gate
            // (never wire text — the /podtest shape); the grammar-bounded wire id rides the admin
            // queue onto the sim thread as bare digits, where the serve's engine port refuses a
            // seat's hull / a transfer pod / an unknown id and removes any other hull through the
            // engine's own removeObject (the pod-despawn primitive — a vanish, never a kill); the
            // REAL outcome mails back as the receipt (D5 reply law: no optimistic inline ack).
            if (!session.admin()) {
                replyLines = List.of(ChatCommands.ADMIN_ONLY);
            } else {
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.DESPAWN, 0, 0f, false, parsed.arg(),
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_despawn seat=" + seatId
                        + " session=" + session.sessionId + " wire=" + parsed.arg());
            }
        } else if (parsedKind == ChatCommands.Kind.PODTEST) {
            // admin-only pod-scenario driver verb for the CALLER's seat
            // (never a typed seat). The deposit rides the admin queue onto the sim thread with
            // this session as the receipt target — the serve loop mails the start receipt and
            // the verdict (D5 reply law: no optimistic inline ack). parsed.arg() is a fixed-set
            // token the grammar already validated (no user text reaches the engine).
            if (!session.admin()) {
                replyLines = List.of(ChatCommands.ADMIN_ONLY);
            } else {
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        OperatorCommandFile.Verb.POD_TEST, seatId, 0f, false, parsed.arg(),
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_podtest scenario=" + parsed.arg()
                        + " seat=" + seatId + " session=" + session.sessionId);
            }
        } else if (parsedKind == ChatCommands.Kind.GODMODE
                || parsedKind == ChatCommands.Kind.TELEPORT) {
            // The two admin DEBUG verbs — the SESSION's
            // admin bit is the whole gate (never wire text; the /podtest shape), the seat is the
            // CALLER's (never typed), and the deposit rides the admin queue onto the sim thread
            // where AdminSeatDebugLane applies the effect and mails the REAL outcome as the
            // receipt (D5 reply law: no optimistic inline ack). A bare /teleport from an admin
            // answers the usage line (the modded client never sends one — it arms locally).
            // /teleport is admin-only in EVERY match. The former debug-watch carve-out
            // (every non-admin seat of a WELCOME matchFlags bit1 match could teleport) is gone: a debug
            // match opens only screen-side affordances, and anything that reaches the server keeps the
            // SESSION's admin bit as its whole gate.
            // /godmode was always admin-only. Observers still bounce above.
            boolean teleportVerb = parsedKind == ChatCommands.Kind.TELEPORT;
            if (!session.admin()) {
                replyLines = List.of(ChatCommands.ADMIN_ONLY);
            } else if (teleportVerb && parsed.pose() == null) {
                replyLines = List.of(ChatCommands.TELEPORT_USAGE);
            } else {
                boolean god = parsedKind == ChatCommands.Kind.GODMODE;
                door.offerAdminCommand(new OperatorCommandFile.Command(
                        god ? OperatorCommandFile.Verb.GOD_MODE : OperatorCommandFile.Verb.TELEPORT,
                        seatId, 0f, false, god ? parsed.arg() : parsed.pose(),
                        session.sessionId));
                replyLines = List.of();
                System.out.println("pilot-datagram-server admin_debug verb="
                        + (god ? "godmode arg=" + parsed.arg() : "teleport pose="
                                + parsed.pose().substring(1))
                        + " seat=" + seatId + " session=" + session.sessionId);
            }
        } else if (parsedKind == ChatCommands.Kind.AUTH) {
            // mid-game admin elevation = the sealed proof ONLY (auth13Reply).
            // THE FLIP deleted the readable arm (the admin secret compared
            // from chat text): the door admits only version-13 sessions, and each carries a root. A root-less
            // session is a door defect and answers the constant "unavailable" — fail-closed: no compare, no ledger
            // spend, never a readable-password path.
            auth13 = auth13InputsOrNull(session);
            if (auth13 == null) {
                replyLines = List.of(AUTH_UNAVAILABLE);
            } else {
                replyLines = auth13Reply(session, seatId, parsed.arg(), auth13);
                if (replyLines == null) {
                    return; // silent, counted inside: a replayed counter, an over-budget miss, a closed door
                }
            }
        } else if (parsedKind == ChatCommands.Kind.HELP) {
            // /help answers HERE (not dispatch) because the
            // voice gate is door-owned; the three flags mirror the "unavailable" gates above;
            // the admin verbs list only for an admin session.
            replyLines = ChatCommands.helpLines(door.moduleSeats != null && door.moduleVerbsArmed,
                    door.moduleSeats != null && door.transferTargets != null, door.voice != null,
                    session.admin(), door.variantPicks.customsAvailable(), teamsArmed());
        } else if (parsedKind == ChatCommands.Kind.CUSTOMS_LIST) {
            // W15 v0 step 3(f): /customs answers HERE (the /help precedent — the author key is
            // the door's customAuthorKey, never seen by dispatch); own-only law.
            replyLines = door.variantPicks.customsAvailable()
                    ? door.chatCommands.emitOwnCustoms(door.variantPicks.ownCanonicalIds(door.customAuthorKey(session)),
                            seatId, listingSink)
                    : List.of("custom variants unavailable on this server");
            System.out.println("pilot-datagram-server customs_list seat=" + seatId
                    + " session=" + session.sessionId + " own=" + (door.variantPicks.customsAvailable()
                            ? door.variantPicks.ownCanonicalIds(door.customAuthorKey(session)).size() : -1));
        } else if (parsedKind == ChatCommands.Kind.SHARE_LIST
                || parsedKind == ChatCommands.Kind.SHARE_SET
                || parsedKind == ChatCommands.Kind.SHARE_SET_SILENT
                || parsedKind == ChatCommands.Kind.SHARE_AUTO
                || parsedKind == ChatCommands.Kind.SHARE_AUTO_SILENT
                || parsedKind == ChatCommands.Kind.UNSHARE_SET
                || parsedKind == ChatCommands.Kind.BUILDS_LIST) {
            // Peer-build sharing v2: answered HERE (the /customs precedent — ownership is
            // the session's LIVE id, which dispatch never sees).
            replyLines = door.handlePeerBuildShare(session, seatId, parsed, listingSink);
        } else if (parsedKind == ChatCommands.Kind.SHIP_SET
                && (customPickReply = door.variantPicks.shipSetCustomOrNull(session.sessionId, seatId,
                        door.customAuthorKey(session), parsed.arg())) != null) {
            // W15 v0 step 3(c): /ship <id> naming the SESSION's own custom answers
            // HERE (the /help precedent — the author key is the door's customAuthorKey, which the
            // dispatch never sees); an allowlist hit or a plain unknown falls through to it.
            replyLines = customPickReply;
        } else {
            // session.shipId() = the seat's fixed wire id — the wire-form
            // self-ask resolves to the return-home verb under the one-entity pod.
            replyLines = door.chatCommands.dispatch(parsed, seatId, session.shipId(), door.seatAuthority,
                door.pickableVariants, door.moduleSeats, door.moduleVerbsArmed, door.transferTargets, door.approxSimTick,
                (targetWireId, reasonCode) -> {
                    // W3 L4 S3: the dispatch-DENY fan — UNICAST by
                    // the slash-reply lane law (the DENY matters only to the requester; the
                    // remote XFER folds no-op it), never fanTransfer's serve-thread
                    // broadcast. session.shipId() IS the seat's fixed wire id.
                    door.sendCtrlToSession(session, CommandTransferServeLane.encodeTransferFrame(
                            CommandTransferFrameCodec.KIND_DENY, session.shipId(),
                            session.shipId(), targetWireId, 0L, reasonCode));
                    System.out.println("pilot-datagram-server transfer_dispatch_deny seat="
                            + seatId + " target_wire=" + targetWireId
                            + " reason=" + reasonCode);
                },
                listingSink,
                () -> {
                    synchronized (door.lock) {
                        session.pendingSuicide = true;
                    }
                },
                () -> senderHoldsSeatOrCountStale(session, seatId));
        }
        // An auto-corrected verb prepends its ONE fixed-shape note (reading order
        // first line; the grammar's constant + the typed verb's bounded echo, nothing else).
        if (parsed.note() != null) {
            List<String> noted = new ArrayList<>(replyLines.size() + 1);
            noted.add(parsed.note());
            noted.addAll(replyLines);
            replyLines = noted;
        }
        // A multi-line reply is authored in READING order but the
        // vanilla combat message panel stacks the newest line on TOP (flight #10b screenshot:
        // the 3-line /help burst rendered exactly reversed) ⇒ send BOTTOM-UP so it reads top-down.
        for (int i = replyLines.size() - 1; i >= 0; i--) {
            door.sendCtrlToSession(session, ChatFrameCodec.encodeDown(seatId, "server", replyLines.get(i)));
        }
        synchronized (door.lock) {
            door.counters.chatCommandsHandled++;
        }
        System.out.println("pilot-datagram-server chat_command seat=" + seatId
                + " kind=" + parsed.kind() + " text=" + PeerText.quote(ChatCommands.auditText(text))
                + " replyLines=" + replyLines.size()
                + " reply=" + PeerText.quote(ChatCommands.auditReply(parsed, replyLines)));
    }

    /** The ONE "unavailable" reply {@code /auth} answers (a token door inside {@link #auth13Reply}; a root-less session);
     *  the kit's {@code ChatInputLaw.AUTH_MISS_REPLIES} mirrors it byte-identically. */
    private static final String AUTH_UNAVAILABLE = "admin elevation unavailable on this server";

    /**
     * One version-13 session's {@code /auth} inputs, snapshot
     * under the door lock — the session root, {@code b″} and {@code YbAuth} (the session's own arrays, never written
     * after its admit). Constant {@code toString}: no byte ever prints.
     */
    private static final class Auth13Inputs {
        final byte[] root, scalar, share;

        Auth13Inputs(byte[] root, byte[] scalar, byte[] share) {
            this.root = root;
            this.scalar = scalar;
            this.share = share;
        }

        @Override
        public String toString() {
            return "PidsChatVerbs.Auth13Inputs";
        }
    }

    /**
     * The session's {@code /auth} inputs ({@code admitLocked}
     * sets the root, the scalar and the share in ONE lock take), or {@code null} for a session with no root — since
     * THE FLIP no admit path makes one, and the caller answers it fail-closed. Door lock.
     */
    private Auth13Inputs auth13InputsOrNull(PeerSession session) {
        synchronized (door.lock) {
            return session.hs13SessionRoot == null ? null
                    : new Auth13Inputs(session.hs13SessionRoot, session.hs13AuthScalar, session.hs13AuthShare);
        }
    }

    /**
     * The VERSION-13 {@code /auth} door — the argument is a
     * {@link Handshake13Auth} proof, never a password. WHY: version 12 compares the admin secret sent READABLE;
     * version 13 receives a one-shot proof bound to this session's root and the door's {@code YbAuth}, so the secret
     * never crosses the wire. Steps (the design's door order; reader thread):
     * <ol>
     * <li>A TOKEN door ({@code passwordLedger == null}) answers the constant "unavailable", no work. A PASSWORD door
     * runs the SAME path whether or not an admin secret is armed: {@code adminPassword} is never read here (an unarmed
     * door's throwaway share never verifies, so it answers "wrong admin password" after the same work — no
     * armed-or-not tell).</li>
     * <li>Parse, no lock: not a proof (length, charset, counter 0 — a readable password included) = the
     * {@link ChatCommands#AUTH_USAGE} constant, {@code auth13_malformed} + 1; NO compare, NO ledger, NO X25519.</li>
     * <li>Lock: a counter not above the session's {@code hs13AuthHighest} = SILENT ({@code null}),
     * {@code auth13_replayed} + 1, no ledger. Else the guess-ledger KEY admit (the version-12 arm's key and mirror):
     * held = {@link ChatCommands#AUTH_HELD}, {@code auth_held} + 1, the highest UNCHANGED; granted = the highest
     * advances to the counter BEFORE the lock is released, so each {@code sid″} is tested at most once.</li>
     * <li>NO lock: one X25519 + {@code isk} + {@code hkdf} + a constant-time compare ({@link Handshake13Auth#verify}).
     * Every X25519 here sits behind a chat token AND a ledger grant.</li>
     * <li>Lock: {@code closed} re-read first (closed = silent, no verdict); PASS = the {@code PASS_ADMIN} verdict +
     * {@code elevateAdmin}, then {@link ChatCommands#AUTH_ELEVATED} + the {@code admin_auth seat= session=} row;
     * FAIL = the FAIL verdict.</li>
     * <li>FAIL: the credential-fail budget exactly as the version-12 arm — denied = silent,
     * {@code credential_fail_budgeted} + 1; else "wrong admin password".</li>
     * </ol>
     * Returns the reply lines, or {@code null} = answer nothing (each silent exit counted above). No reply, row or
     * exception text carries the proof, the share, the tag, the root or the counter; the audit line stays
     * {@link ChatCommands#auditText}'s redacted form.
     */
    private List<String> auth13Reply(PeerSession session, int seatId, String arg, Auth13Inputs in) {
        if (door.passwordLedger == null) {
            return List.of(AUTH_UNAVAILABLE);
        }
        Handshake13Auth.Proof proof = Handshake13Auth.parseOrNull(arg);
        if (proof == null) {
            door.readerCounters.auth13Malformed++;
            return List.of(ChatCommands.AUTH_USAGE);
        }
        String authKey = DoorSourceBudget.sourceKey(session.peer.getAddress());
        long authNow = nowMillis();
        synchronized (door.lock) {
            if (proof.counter() <= session.hs13AuthHighest) {
                door.readerCounters.auth13Replayed++;
                return null;
            }
            boolean held = !door.guessLedger.admit(authKey, null, authNow).granted();
            door.readerCounters.mirrorGuess(door.guessLedger);
            if (held) {
                door.readerCounters.authHeld++;
                return List.of(ChatCommands.AUTH_HELD);
            }
            session.hs13AuthHighest = proof.counter();
        }
        boolean pass = Handshake13Auth.verify(in.root, in.scalar, in.share, proof);
        synchronized (door.lock) {
            if (door.closed) {
                return null;
            }
            door.recordCredentialVerdictLocked(pass ? CredentialVerdict.PASS_ADMIN : CredentialVerdict.FAIL,
                    authKey, null, authNow);
            if (pass) {
                session.elevateAdmin();
            }
        }
        if (pass) {
            System.out.println("pilot-datagram-server admin_auth seat=" + seatId
                    + " session=" + session.sessionId);
            return List.of(ChatCommands.AUTH_ELEVATED);
        }
        if (!door.credentialFailBudget.admit(session.peer.getAddress().getHostAddress(), nowMillis())) {
            door.readerCounters.credentialFailBudgeted++;
            return null;
        }
        return List.of("wrong admin password");
    }

    /**
     * The {@code /module <slot>} post-write re-check
     * ({@code ChatCommandDispatch}'s {@code senderHoldsSeat}, asked AFTER the arbiter's claim write
     * with no dispatch / arbiter monitor held) — the {@link #sessionHoldsSeat} body for THIS session
     * (signed id, never 0) and {@code seatId} (1..64) in ONE door-lock take. False (since
     * {@link #onChatUp}'s check the session left the table, failed, BYE'd, was seat-vacated or holds
     * another seat) counts {@code chatStaleSessionDropped} inside that same
     * take (the existing {@code chat_stale_drop=} token, ruling R4); dispatch then undoes the claim.
     */
    private boolean senderHoldsSeatOrCountStale(PeerSession session, int seatId) {
        synchronized (door.lock) {
            PeerSession s = door.sessionsById.get(session.sessionId);
            if (s != null && holdsSeatLocked(s) && s.seatId == seatId) {
                return true;
            }
            door.counters.chatStaleSessionDropped++;
            return false;
        }
    }
}
