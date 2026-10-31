package cc.starsector.server.runtime;

import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.protocol.SeatStateCause;
import cc.starsector.server.runtime.PilotIntentDatagramServer.BanOutcome;
import cc.starsector.server.runtime.PilotIntentDatagramServer.PeerSession;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The replay door's record-driven session verbs, carved VERBATIM out of {@link PilotIntentDatagramServer}
 * (the door file's size tier, not a behaviour change). A door opened by
 * {@link PilotIntentDatagramServer#openReplay} has no peers, so the re-sim feed mints each recorded session
 * ({@link #replayMint}), ends it the way its recorded departure says ({@link #replayDie}), opens the seat's tenure
 * gate ({@link #replayClearTenureGate}) and stages the sessions a replayed kick / ban ends at the command
 * ({@link #replayStageCommandDeaths}; consumed by the door's kick verb and {@link #replayBanAtCommandLocked}).
 * Its OWN class by responsibility: every door builds one, but its feed verbs never run on a live door (the one
 * value a live door reads is {@link #replayCmdNoop}, 0 there). The four feed verbs call {@link #requireReplayDoor}
 * first, inside the door's lock where they take it (a forged session on a reachable door would bypass admission);
 * {@link #noteReplayCmdNoopLocked} and {@link #replayBanAtCommandLocked} are called only from the door's
 * replay-gated kick / admin-arm / ban branches.
 * Every {@code lock} named below is the door's own monitor ({@code door.lock}), taken by the verb itself;
 * {@link #replayClearTenureGate} takes no {@code door.lock} (it flips the seat authority's pick-pending flag). The door
 * keeps public one-line delegates for its callers and hands out only its bound address
 * ({@link PilotIntentDatagramServer#boundSelf}), never its socket.
 *
 */
final class PidsReplayDoor {
    private final PilotIntentDatagramServer door;

    PidsReplayDoor(PilotIntentDatagramServer door) {
        this.door = door;
    }

    /** The bounded shape a replayed departure reason must have before it can reach a death row on stdout
     *  ({@link PilotIntentDatagramServer#failSessionLocked} prints {@code reason=}): a record is an input file, never free text. */
    private static final java.util.regex.Pattern REPLAY_REASON_TOKEN = java.util.regex.Pattern.compile("[a-z0-9_]{1,48}");

    /** Replayed kicks / bans / admin-key arms this replay door answered without its live checks (the key compare,
     *  the source / cid match; written under {@code lock}; 0 on a live door): a replayed kick or ban ends only the
     *  sessions the recording's next frame ends for it ({@link #replayStageCommandDeaths}), at the command, as live
     *  did; a replayed arm carries the redacted text, never a key, and elevates nobody. */
    private volatile long replayCmdNoop;

    long replayCmdNoop() {
        return replayCmdNoop;
    }

    /** The sessions (signed id -> reason token) the recording's next frame ends for a kick / ban, staged at this
     *  frame's session edges and consumed by the replayed verb; replaced every replay frame (guarded by {@code lock}). */
    final LinkedHashMap<Long, String> replayCommandDeaths = new LinkedHashMap<>();

    /**
     * Stage, for this frame's replayed kicks / bans, the sessions the recording's NEXT frame ends with an operator
     * reason ({@code operator_kick} / {@code banned}), replacing the previous frame's (an entry no verb consumed is
     * dropped; its DEAD row still ends the session at the next frame's edges). Why: live ends such a session at the
     * command, after the frame's edges were observed, so its DEAD row lands one frame later; a replay door cannot
     * redo the live key / source match (every replay session shares one source), so the recording names the
     * victims and the replayed verb ends them in the frame live did. Serve thread, replay door only.
     *
     */
    void replayStageCommandDeaths(List<ReplayFeed.CommandDeath> deaths) {
        synchronized (door.lock) {
            requireReplayDoor("replayStageCommandDeaths");
            replayCommandDeaths.clear();
            for (ReplayFeed.CommandDeath d : deaths) {
                replayCommandDeaths.put(d.sessionId(), d.departureReason());
            }
        }
    }

    /** Lock held: count one replayed command whose session action was skipped; the first one prints.
     * */
    void noteReplayCmdNoopLocked(String verb) {
        if (replayCmdNoop++ == 0L) {
            System.out.println("npeer-serve replay replay_cmd_noop first verb=" + verb);
        }
    }

    private void requireReplayDoor(String member) {
        if (!door.isReplay()) {
            throw new IllegalStateException(member + " is replay-door only — a forged session on a reachable"
                    + " door would bypass admission");
        }
    }

    /**
     * Mint the session a recorded SESS LIVE row names, with the RECORDED id (signed, never 0; negative is normal),
     * so the re-sim's own rows — and any lookup keyed on the session id — match the recording. A pilot seat claims
     * its descriptor token exactly as a HELLO admission does ({@code claim(token, true)}: the respawn machine's
     * tenure gate arms the same way — {@link #replayClearTenureGate} opens it); an observer (seat 0, ship 0) holds
     * no token and claims nothing. Skipped by design, because no peer exists: the handshake nonce table, the voice
     * relay's live set, the mute carry, the WELCOME, the squat age, the takeover-key table and the pick / tenure
     * hand-offs. The session is born two-way and provisioned (it never waits on a reader that does not run).
     * {@code IllegalStateException} on a live door (a forged session on a reachable door), a closed door, id 0, a
     * duplicate id (a corrupt record), {@code observer != (seat == 0)}, an observer with a ship, an unknown seat, a
     * ship the descriptor does not bind to the seat (checked BEFORE the claim, so a refused mint never holds the
     * seat), and a claim that is not {@code SEATED} ({@code ALREADY_SEATED} = a previous tenure still holds the seat
     * — the record's DEAD row must be fed first; never papered over).
     */
    PeerSession replayMint(long sessionId, int seat, long shipId, boolean observer, boolean admin, int helloMagicBits,
            long keymapLo, long keymapHi) {
        synchronized (door.lock) {
            requireReplayDoor("replayMint");
            if (door.closed) {
                throw new IllegalStateException("replayMint: the door is closed");
            }
            if (sessionId == 0L) {
                throw new IllegalStateException("replayMint: sessionId 0 is the no-session sentinel");
            }
            if (door.sessionsById.containsKey(sessionId)) {
                throw new IllegalStateException("replayMint: sessionId " + sessionId
                        + " is already minted (a duplicate LIVE row — corrupt record)");
            }
            if (observer != (seat == 0)) {
                throw new IllegalStateException("replayMint: observer=" + observer + " with seat " + seat
                        + " (seat 0 is the observer seat, and only it)");
            }
            if (observer && shipId != 0L) {
                throw new IllegalStateException("replayMint: an observer holds no hull, got shipId " + shipId);
            }
            String token = null;
            if (!observer) {
                token = replaySeatTokenLocked(seat);
                long bound = door.seatAuthority.authorizedShipId(token);
                if (bound != shipId) {
                    throw new IllegalStateException("replayMint: seat " + seat + " is bound to ship " + bound
                            + ", the record says ship " + shipId);
                }
                MatchSeatAuthority.Seating seating = door.seatAuthority.claim(token, true);
                if (seating.status() != MatchSeatAuthority.Status.SEATED) {
                    throw new IllegalStateException("replayMint: seat " + seat + " claim status " + seating.status()
                            + " (a previous tenure still holds the seat — its DEAD row must come first)");
                }
            }
            // The door's own bound address: a send that ever got past the belt could only reach this host's
            // unread socket.
            InetSocketAddress self = door.boundSelf();
            PeerSession session = door.new PeerSession(sessionId, 0L, new byte[0], self, "replay#" + seat, token, seat,
                    shipId, admin, observer, true);
            session.reachedTwoWay = true;
            session.provisioned = true;
            // The recorded HELLO MagicLib reports (SESS flag bits 2-3): the admission block copies them per seat for the
            // mark's FOLLOW predicate exactly as it does for a live HELLO.
            session.helloMagicBits = helloMagicBits & (Handshake13.FLAG_MAGIC_UP | Handshake13.FLAG_MAGIC_REPLICA);
            // The recorded HELLO keymap (the SESS row's two trailing i64): the seat's redirected key reads answer as
            // they did on the live serve; 0/0 (an older reel) = the serve's own map.
            session.helloKeymapLo = keymapLo;
            session.helloKeymapHi = keymapHi;
            session.lastValidArrivalMillis = PilotIntentDatagramServer.nowMillis();
            door.sessionsById.put(sessionId, session);
            door.lock.notifyAll();
            System.out.println("replay-door mint session=" + sessionId + " seat=" + seat + " ship=" + shipId
                    + " observer=" + observer + " magic=" + session.helloMagicBits
                    + " keymap=" + ((keymapLo | keymapHi) != 0L));
            return session;
        }
    }

    /** Lock held: the ONE minted token whose seat is {@code seat} (never printed — a seat token is a secret). */
    private String replaySeatTokenLocked(int seat) {
        String found = null;
        for (String t : door.seatAuthority.mintedTokens()) {
            if (door.seatAuthority.authorizedSeatId(t) == seat) {
                if (found != null) {
                    throw new IllegalStateException("replayMint: seat " + seat + " is bound by two minted tokens");
                }
                found = t;
            }
        }
        if (found == null) {
            throw new IllegalStateException("replayMint: unknown seat " + seat + " (no minted token binds it)");
        }
        return found;
    }

    /**
     * End a replayed session the way its recorded SESS DEAD row says it left, so {@link PeerSession#departureReason}
     * re-emits the same token and the seat is released with the same cause: {@code bye} = the peer-goodbye shape
     * (live set, then the VACATE_BYE release; no echo — there is no peer); {@code displaced} = the takeover /
     * eviction removal (VACATE_DISPLACED, the session leaves the table without a death); {@code banned} = the ban
     * shape (the banned release, then the takeover-key drop + mute stash the ban path runs); any other token = a
     * death carrying that token (VACATE_SILENCE). {@code IllegalStateException} on a live door, an unknown or
     * not-live session, and a reason that is not a bounded {@code [a-z0-9_]{1,48}} token (the reason is never
     * echoed).
     */
    void replayDie(long sessionId, String departureReason) {
        synchronized (door.lock) {
            requireReplayDoor("replayDie");
            PeerSession session = door.sessionsById.get(sessionId);
            if (session == null || !session.isLiveLocked()) {
                throw new IllegalStateException("replayDie: session " + sessionId
                        + (session == null ? " is unknown" : " is not live")
                        + " (a DEAD row with no live session — corrupt record or feed order)");
            }
            if (departureReason == null || !REPLAY_REASON_TOKEN.matcher(departureReason).matches()) {
                throw new IllegalStateException("replayDie: session " + sessionId
                        + " departure reason is not a bounded [a-z0-9_]{1,48} token (length="
                        + (departureReason == null ? -1 : departureReason.length()) + ")");
            }
            switch (departureReason) {
                case "bye" -> {
                    session.peerSaidBye = true;
                    door.voiceRebuildLiveLocked();
                    door.vacateSessionSeatLocked(session, SeatStateCause.VACATE_BYE);
                }
                case "displaced" -> door.removeSessionLocked(session, SeatStateCause.VACATE_DISPLACED);
                case "banned" -> {
                    session.bannedVacate = true;
                    door.failSessionLocked(session, new PilotPeerDisconnectedException("banned",
                            "replayed departure (reason token: banned)"));
                    door.dropTableKeyAndStashMutesLocked(session);
                }
                default -> door.failSessionLocked(session, new PilotPeerDisconnectedException(departureReason,
                        "replayed departure (reason token: " + departureReason + ")"));
            }
            door.lock.notifyAll();
        }
    }

    /**
     * Open the seat's tenure gate — the step a HELLO admission's provisioning exit runs on the reader thread, which a
     * replay door never starts; the re-sim feed calls this at the seat's SESS LIVE row. Outside {@code lock}, like
     * the reader's own clear. Deliberately NOT repeated here: the reader's counters (reader-thread-confined), the
     * variant-override clear (a replay leaves the override store untouched — deploy reads the recorded fly-in
     * override instead) and the {@code TENURE_APPLIED} row (log graders count it per HELLO). One stdout row.
     * {@code IllegalStateException} on a live door.
     */
    void replayClearTenureGate(int seat) {
        requireReplayDoor("replayClearTenureGate");
        boolean wasPending = door.seatAuthority.pickApplyPending(seat);
        if (wasPending) {
            door.seatAuthority.clearPickApplyPending(seat);
        }
        System.out.println("replay-door tenure_clear seat=" + seat + " was_pending=" + wasPending);
    }

    /**
     * Lock held, replay door only: a replayed ban matches no session itself (every replay session shares one source,
     * and a ban-cid from a current reel carries the key's digest), so it ends, evicts and vacates exactly the
     * sessions the recording's next frame ends as banned (staged by {@link #replayStageCommandDeaths}), here at the
     * command as live did, never a frame late. Counted; live-shaped, kicked = the sessions this call ended. A seat already vacated
     * and holding is not parked here (the recording carries no row naming it). The ONE sweep for both replayed ban
     * shapes: a source key (its in-memory entry already added by the caller) and a cid digest (no entry: {@code added},
     * {@code held} and {@code entryWritten} false).
     *
     */
    BanOutcome replayBanAtCommandLocked(BanSet.Kind kind, String key, boolean added, boolean held,
            boolean entryWritten) {
        int ended = 0;
        for (Long id : new ArrayList<>(replayCommandDeaths.keySet())) {
            PeerSession s = door.sessionsById.get(id);
            if ("banned".equals(replayCommandDeaths.get(id)) && s != null && s.isLiveLocked()) {
                replayCommandDeaths.remove(id);
                s.bannedVacate = true;
                door.failSessionLocked(s, new PilotPeerDisconnectedException("banned",
                        "replayed ban (reason token: banned; seat " + s.seatId + ")"));
                door.dropTableKeyAndStashMutesLocked(s);
                ended++;
            }
        }
        noteReplayCmdNoopLocked("ban");
        return new BanOutcome("ok", kind, key, ended, added, held, entryWritten);
    }
}
