package cc.starsector.server.runtime;

import java.net.InetAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Seat squat: a credentialed HELLO that
 * claims a seat and then never sends a datagram holds it for the whole join grace (24 h under
 * the ops launcher) at the cost of one datagram per seat — one machine could squat a public
 * fortress. The join grace itself is right (a human sits in the mission brief for minutes),
 * and a per-source cap would refuse a NAT household's third joiner (the client gives up an
 * unanswered HELLO after 15 s), so the door acts ONLY under a full ledger: among the live
 * pilot claims that never reached two-way, take the source holding the MOST of them; if it
 * holds at least two, fail its OLDEST claim (the squat age survives a same-cid re-HELLO,
 * {@code PeerSession#pendingSinceMillis}) and let the fresh joiner take the seat. A source
 * with a single silent claim is never touched (indistinguishable from one human in the
 * brief); a settled session is never touched. Residual: one silent seat per source key (an IPv4
 * address / an IPv6 /56 — {@link #squatKey}).
 * Password mode only (the token door's seats are per token).
 *
 * <p>wq P25: (a) "silent" means one-way for at least
 * {@link PilotIntentDatagramServer#PENDING_SQUAT_MIN_AGE_MILLIS} (the door's volatile floor) —
 * a younger claim is fresh, not silent (a real client is two-way within ~20 ms of admission,
 * see the field), so it is neither counted toward the heaviest source nor evictable; a joiner
 * meeting only fresh claims bounces {@code match_full} and its own retry (the client's 2 s
 * connect backoff) lands past the floor. Keying on IP:port instead was REJECTED: a squatter
 * opens one socket per claim for free, which would make every claim its own source and the
 * count law vacuous — the per-IP count is the whole defence. (b) the victim is TOLD: the door
 * queues it for the HOUS KICKED notice (reason
 * {@link HouseControlMessage#KICKED_REASON_PENDING_EVICTED}), sent after the kill because the
 * kill is what frees the seat for the re-claim inside the locked resolve — that glue (counter,
 * notice queue, EVICT_PENDING log, fail) stays in
 * {@code PilotIntentDatagramServer#evictPendingSquatLocked}; this class is the PURE selection.
 *
 * <p>Carved verbatim out of PilotIntentDatagramServer
 * — zero
 * behaviour change. Caller holds the door lock (the session fields read here are door-locked).
 */
final class PendingSquatLaw {

    private PendingSquatLaw() {
    }

    /** The chosen victim plus its source's silent-claim count (the EVICT_PENDING log field).
     * */
    record Pick(PilotIntentDatagramServer.PeerSession victim, int sourcePending) {
    }

    /**
     * The squat SOURCE = the door-wide source key
     * ({@link DoorSourceBudget#sourceKey}: IPv4 as-is, IPv6 by its /56) — the guess ledger's and
     * ban/hold's key. WHY: the raw host address made each address of one tenant's /64 rotation its
     * own source on a dual-stack bind, so an N-claim squat never reached the count of two.
     * The class's own
     * IP:port rejection (a squatter re-sources per claim for free) applies to a /64 the same way.
     * IPv4 keys are byte-identical to the pre-fix {@code getHostAddress()}. The ONE call both
     * {@link #pickVictim} loops make; package-private for the contract row (reflection).
     */
    static String squatKey(InetAddress address) {
        return DoorSourceBudget.sourceKey(address);
    }

    /**
     * Counts, per source KEY ({@link #squatKey}),
     * the live, non-observer, never-two-way, seated, un-vacated claims aged at least
     * {@code minAge}; takes the heaviest source; returns null unless it holds at least two, else
     * that source's OLDEST such claim. Lock held.
     */
    static Pick pickVictim(Collection<PilotIntentDatagramServer.PeerSession> sessions, long now, long minAge) {
        return pickVictim(sessions, now, minAge, seatId -> true);
    }

    /**
     * {@link #pickVictim(Collection, long, long)} over only the claims whose seat {@code inScope} accepts — the
     * door passes "the seat plays for the side the joiner asked", so a full TEAM frees a seat of that team and
     * never one of the other (which would leave the asked team full and still refuse the joiner). The per-source
     * count runs over the in-scope claims too: a source needs two silent claims on that side to lose one.
     *
     */
    static Pick pickVictim(Collection<PilotIntentDatagramServer.PeerSession> sessions, long now, long minAge,
            java.util.function.IntPredicate inScope) {
        Map<String, Integer> pendingBySource = new HashMap<String, Integer>();
        for (PilotIntentDatagramServer.PeerSession s : sessions) {
            if (s.isLiveLocked() && !s.observer() && !s.reachedTwoWay && s.seatToken() != null && !s.seatVacated
                    && now - s.pendingSinceMillis >= minAge && inScope.test(s.seatId)) {
                // The /56 source key, never the raw host address
                pendingBySource.merge(squatKey(s.peerAddress().getAddress()), 1, Integer::sum);
            }
        }
        String heaviest = null;
        int heaviestCount = 0;
        for (Map.Entry<String, Integer> e : pendingBySource.entrySet()) {
            if (e.getValue() > heaviestCount) {
                heaviest = e.getKey();
                heaviestCount = e.getValue();
            }
        }
        if (heaviest == null || heaviestCount < 2) {
            return null;
        }
        PilotIntentDatagramServer.PeerSession oldest = null;
        for (PilotIntentDatagramServer.PeerSession s : sessions) {
            if (s.isLiveLocked() && !s.observer() && !s.reachedTwoWay && s.seatToken() != null && !s.seatVacated
                    && now - s.pendingSinceMillis >= minAge && inScope.test(s.seatId)
                    && heaviest.equals(squatKey(s.peerAddress().getAddress())) //
                    && (oldest == null || s.pendingSinceMillis < oldest.pendingSinceMillis)) {
                oldest = s;
            }
        }
        if (oldest == null) {
            return null;
        }
        return new Pick(oldest, heaviestCount);
    }
}
