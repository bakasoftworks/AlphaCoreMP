package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.match.MatchGracePolicy;
import cc.starsector.server.match.MatchSeatAuthority;
import java.util.HashMap;
import java.util.Map;

/**
 * W5 reconnect grace — the serve loop's engine lane:
 * it drains
 * {@link MatchSeatAuthority#pumpGraceTransitions} once per tick on the SIM THREAD (pre-advance,
 * beside the module-seat drain — the {@link ModuleSeatServeLane} shape) and performs each
 * transition's engine verbs through {@link EngineCombatBinding}:
 * <ul>
 * <li>{@code ENTER_GRACE} — drop the departed pilot's STAGED intent frame (the fix:
 *     its per-tick replay is a live command-time path on the vacant hull — it stripped the fresh
 *     AI and kept replaying stale facing/aim/fire), then attach a fresh "cautious"-personality
 *     AI ({@link EngineCombatBinding#graceAttachCautiousAi}) and toast the room;</li>
 * <li>{@code PARK_STAY_AI} — toast only: the cautious AI keeps fighting, the episode stays live
 *     so a later reclaim still restores;</li>
 * <li>{@code PARK_DRIFT} — detach + restore ({@link EngineCombatBinding#graceDetachAi}): the
 *     hull coasts suppressed, the pre-grace personality returns;</li>
 * <li>{@code RECLAIM} — same detach + restore, ONLY when this lane still holds a live episode
 *     (a drift-parked hull was already restored at its park — reclaim is then engine-silent);</li>
 * <li>{@code CLEAR_MOUNT_LOST} — drop the bookkeeping: the hull (and its AI) is gone, the next
 *     fly-in re-establishes the fly-in laws itself.</li>
 * </ul>
 *
 * <p>One lane object per ROUND (the moduleLane precedent): the per-seat saved-personality
 * bookkeeping dies with the round, which IS the wrap reset the authority's emits-nothing wrap
 * law expects. Constructed only when the parsed {@link MatchGracePolicy} is ON — an OFF serve
 * has no lane, no pump call, byte-identical behavior (toggle discipline). Engine-verb THROWs
 * are serve-fatal by the mount-family law (a half-graced hull must not serve). Toast text is
 * server-authored ASCII constants only (chat single-line printable law) — never wire input.
 */
final class SeatGraceLane {

    /** A live grace episode's restore bookkeeping (minted at ENTER_GRACE, dropped when the
     * engine state was restored or the hull was lost). */
    private static final class Episode {
        final String savedPersonalityId;
        /** The room was told about this grace (the dying session had
         * established two-way flow) — the park toast follows the enter toast, never orphaned. */
        final boolean announced;
        /** The tick this grace was entered — the announce
         * toast fires only once the grace has survived {@link #ENTER_TOAST_DEFER_TICKS}. */
        final long enterTick;
        /** The enter toast has been fanned (the park toast may then follow it). */
        boolean toasted;

        Episode(String savedPersonalityId, boolean announced, long enterTick) {
            this.savedPersonalityId = savedPersonalityId;
            this.announced = announced;
            this.enterTick = enterTick;
        }
    }

    /** The enter toast waits this many sim ticks (half a second
     * at {@link cc.starsector.server.match.MatchGracePolicy#SIM_TICKS_PER_SECOND}). A deliberate
     * rejoin (the client's BYE processed one tick before its fresh HELLO) enters and reclaims a
     * grace one tick apart, and the immediate toast was a "disconnected - cautious AI
     * holds 60s" chat line on every ESC → Play. A grace that outlives the
     * window is a real outage and toasts as before; a reclaim inside it stays chat-silent. The
     * engine verbs (attach / detach / restore) are unchanged either way. */
    static final long ENTER_TOAST_DEFER_TICKS = 30L;

    private final MatchSeatAuthority authority;
    private final EngineCombatBinding binding;
    /** The serve step owning the staged-frame map — the vacate drop's surface. */
    private final PersistentPilotIntentCombatServer.PilotIntentStep pilotStep;
    private final PilotIntentDatagramServer door;
    /** seatId → the seat's FIXED wire id (the drain runs with no session in hand). */
    private final Map<Integer, Long> seatWireIdBySeatId;
    private final MatchGracePolicy policy;
    private final int round;
    private final Map<Integer, Episode> episodes = new HashMap<Integer, Episode>();

    SeatGraceLane(MatchSeatAuthority authority, EngineCombatBinding binding,
            PersistentPilotIntentCombatServer.PilotIntentStep pilotStep,
            PilotIntentDatagramServer door, Map<Integer, Long> seatWireIdBySeatId,
            MatchGracePolicy policy, int round) {
        this.authority = authority;
        this.binding = binding;
        this.pilotStep = pilotStep;
        this.door = door;
        this.seatWireIdBySeatId = seatWireIdBySeatId;
        this.policy = policy;
        this.round = round;
    }

    /** The once-per-tick drain (sim thread, pre-advance — after the module-seat drain: a module
     * seat's dismount clears its pending grace mark BEFORE the pump resolves marks, which is
     * exactly the "the restored station AI IS the module's grace" law). Returns the transition
     * count — a nonzero pump re-shapes the W3 transfer census (the census dirty mark). */
    int pump(long tick) {
        int transitions = 0;
        for (MatchSeatAuthority.SeatGraceTransition transition
                : authority.pumpGraceTransitions(tick)) {
            transitions++;
            int seatId = transition.seatId();
            switch (transition.kind()) {
                case ENTER_GRACE -> enterGrace(seatId, tick);
                case PARK_STAY_AI -> park(seatId, tick, true);
                case PARK_DRIFT -> park(seatId, tick, false);
                case RECLAIM -> reclaim(seatId, tick);
                case CLEAR_MOUNT_LOST -> clearMountLost(seatId, tick);
                case VACATE_BANNED -> banVacate(seatId, tick);
            }
        }
        fireDeferredEnterToasts(tick);
        return transitions;
    }

    /** Fan the enter toast of every announced episode that has now survived
     * {@link #ENTER_TOAST_DEFER_TICKS} (runs after the transition drain, so a same-tick reclaim
     * has already dropped its episode). Chat only — never re-shapes the census. */
    private void fireDeferredEnterToasts(long tick) {
        for (Map.Entry<Integer, Episode> entry : episodes.entrySet()) {
            Episode episode = entry.getValue();
            if (episode.announced && !episode.toasted
                    && tick - episode.enterTick >= ENTER_TOAST_DEFER_TICKS) {
                fanEnterToast(entry.getKey().intValue(), episode, tick);
            }
        }
    }

    private void fanEnterToast(int seatId, Episode episode, long tick) {
        episode.toasted = true;
        System.out.println("npeer-serve grace_toast seat=" + seatId
                + " held_ticks=" + (tick - episode.enterTick)
                + " round=" + round + " tick=" + tick);
        door.fanServerChat(seatId, "seat " + seatId + " disconnected - cautious AI holds "
                + policy.graceSeconds() + "s");
    }

    private void enterGrace(int seatId, long tick) {
        long wireId = wireIdFor(seatId);
        // Fix, drop BEFORE attach: the staged replay is a command-time path on this vacant
        // hull (it stripped the fresh AI two ticks after attach in the flown self-check), and the
        // departed pilot's held bits must never outlive the seat (run-1's post-drop fire).
        boolean stagedDropped = pilotStep.clearStagedIntentOnGraceVacate(wireId);
        EngineCombatBinding.GraceAttachResult attach = binding.graceAttachCautiousAi(wireId);
        // Toast only when the dying session had established two-way flow —
        // the flap's 15 half-open successors each died into a grace_enter, and this
        // toast (there is no other join/leave line) WAS the "reconnecting and disconnecting"
        // chat churn seen in a live session. The engine verbs above are unchanged: a silent grace is
        // still a grace (the seat's hull is still vacant).
        boolean announced = door.lastVacateReachedTwoWay(seatId);
        episodes.put(seatId, new Episode(attach.savedPersonalityId(), announced, tick));
        // The toast itself is deferred ENTER_TOAST_DEFER_TICKS (fireDeferredEnterToasts) —
        // a one-tick deliberate-rejoin grace stays chat-silent.
        System.out.println("npeer-serve grace_enter seat=" + seatId + " wire_id=" + wireId
                + " saved_personality=" + attach.savedPersonalityId()
                + " captain=" + attach.captainPath()
                + " ai_class=" + attach.aiClassName()
                + " staged_dropped=" + stagedDropped
                + " grace_seconds=" + policy.graceSeconds()
                + " announced=" + announced
                + " toast_defer_ticks=" + ENTER_TOAST_DEFER_TICKS
                + " round=" + round + " tick=" + tick);
    }

    private void park(int seatId, long tick, boolean stayAi) {
        long wireId = wireIdFor(seatId);
        // B5: the park toast follows its enter toast (an un-announced grace parks silently; a
        // missing episode keeps the pre-B5 always-toast shape).
        Episode current = episodes.get(seatId);
        boolean announced = current == null || current.announced;
        if (current != null && current.announced && !current.toasted) {
            // A grace shorter than the toast defer that still reached its park (a
            // sub-window graceSeconds) — the enter toast precedes the park toast, never orphaned.
            fanEnterToast(seatId, current, tick);
        }
        if (!stayAi) {
            Episode episode = episodes.remove(seatId);
            if (episode != null) {
                EngineCombatBinding.GraceDetachResult detach =
                        binding.graceDetachAi(wireId, episode.savedPersonalityId);
                System.out.println("npeer-serve grace_park_detach seat=" + seatId
                        + " wire_id=" + wireId
                        + " restored_personality=" + episode.savedPersonalityId
                        + " autofire_off=" + detach.autofireOff()
                        + " shim=" + detach.shimReinstalled()
                        + " round=" + round + " tick=" + tick);
            }
        }
        System.out.println("npeer-serve grace_park seat=" + seatId + " wire_id=" + wireId
                + " policy=" + (stayAi ? "stay-ai" : "drift")
                + " announced=" + announced
                + " round=" + round + " tick=" + tick);
        if (announced) {
            door.fanServerChat(seatId, "seat " + seatId + " grace expired - "
                    + (stayAi ? "AI keeps fighting (seat abandoned)"
                            : "ship adrift (seat abandoned)"));
        }
    }

    private void reclaim(int seatId, long tick) {
        long wireId = wireIdFor(seatId);
        Episode episode = episodes.remove(seatId);
        if (episode == null) {
            // Drift-parked: the park already detached + restored — reclaim is engine-silent.
            System.out.println("npeer-serve grace_reclaim seat=" + seatId + " wire_id=" + wireId
                    + " engine=noop (already restored at drift park)"
                    + " round=" + round + " tick=" + tick);
            return;
        }
        EngineCombatBinding.GraceDetachResult detach =
                binding.graceDetachAi(wireId, episode.savedPersonalityId);
        System.out.println("npeer-serve grace_reclaim seat=" + seatId + " wire_id=" + wireId
                + " restored_personality=" + episode.savedPersonalityId
                + " autofire_off=" + detach.autofireOff()
                + " shim=" + detach.shimReinstalled()
                + " held_ticks=" + (tick - episode.enterTick)
                + " toasted=" + episode.toasted
                + " round=" + round + " tick=" + tick);
    }

    /**
     * A BAN parked this seat at once (the authority's
     * {@code vacateBanned}; never a reconnect hold). The banned
     * pilot's staged frame is dropped FIRST (the law: it must never replay on the vacant
     * hull). Then the hull takes its park end state: STAY-AI keeps a held episode's AI (its grace
     * toast never fires) or attaches the cautious AI with a pre-toasted episode, so a later
     * RECLAIM / takeover restores the personality; DRIFT detaches a held episode, or leaves a
     * live pilot's hull pilotless with its personality untouched. The engine verbs run only while
     * the authority still reads PARKED + mounted — a later transition in this same drain (a hull
     * loss, a fresh claim) then owns the engine state. Chat-silent: the door announces the ban.
     */
    private void banVacate(int seatId, long tick) {
        long wireId = wireIdFor(seatId);
        boolean stagedDropped = pilotStep.clearStagedIntentOnGraceVacate(wireId);
        boolean stayAi = policy.parkPolicy() != MatchGracePolicy.ParkPolicy.DRIFT;
        boolean hullParked = authority.seatGraceState(seatId) == MatchSeatAuthority.SeatGraceState.PARKED
                && authority.isMounted(seatId);
        Episode held = episodes.get(seatId);
        String engine;
        if (!hullParked) {
            engine = "skipped";
        } else if (held != null && stayAi) {
            held.toasted = true;
            engine = "kept";
        } else if (held != null) {
            episodes.remove(seatId);
            binding.graceDetachAi(wireId, held.savedPersonalityId);
            engine = "detached";
        } else if (stayAi) {
            EngineCombatBinding.GraceAttachResult attach = binding.graceAttachCautiousAi(wireId);
            Episode episode = new Episode(attach.savedPersonalityId(), false, tick);
            episode.toasted = true;
            episodes.put(seatId, episode);
            engine = "attached";
        } else {
            engine = "none";
        }
        System.out.println("npeer-serve ban_vacate seat=" + seatId + " wire_id=" + wireId
                + " policy=" + (stayAi ? "stay-ai" : "drift")
                + " episode=" + (held != null ? "held" : "none")
                + " engine=" + engine
                + " staged_dropped=" + stagedDropped
                + " round=" + round + " tick=" + tick);
    }

    /**
     * W3 D5: a command transfer is about to take this PARKED
     * seat's hull — clear the grace footprint BEFORE the re-key. Episode still live (STAY_AI
     * park) ⇒ {@code graceDetachAi}: the grace AI is suppress-WHITELISTED, so
     * left attached it would keep flying the winner's hull; the detach RESTORES the pre-grace
     * personality, which also leaves a sane AI hull if the verb later denies. Drift-parked ⇒
     * the park already detached + restored — engine-silent, bookkeeping only. The caller owns
     * the authority mount-loss record ({@code recordMountLoss} HULL_DEPARTED — the CROSS-seat
     * vacate class; same-seat takeover never vacates, this is not that).
     */
    void clearForTakeover(int seatId, long tick) {
        long wireId = wireIdFor(seatId);
        Episode episode = episodes.remove(seatId);
        if (episode == null) {
            System.out.println("npeer-serve grace_takeover_clear seat=" + seatId
                    + " wire_id=" + wireId + " engine=noop (already restored at drift park)"
                    + " round=" + round + " tick=" + tick);
            return;
        }
        EngineCombatBinding.GraceDetachResult detach =
                binding.graceDetachAi(wireId, episode.savedPersonalityId);
        System.out.println("npeer-serve grace_takeover_clear seat=" + seatId
                + " wire_id=" + wireId
                + " restored_personality=" + episode.savedPersonalityId
                + " autofire_off=" + detach.autofireOff()
                + " shim=" + detach.shimReinstalled()
                + " round=" + round + " tick=" + tick);
    }

    /** W3 L4 S1: does a LIVE grace episode own this seat's AI pin? The
     * transfer lane's transit-park abort belt — an owned episode means the grace machinery
     * (not the park) holds the hull, so the park end must NOT detach. Structurally the
     * double-own is unreachable (transferLane pumps BEFORE graceLane, so any grace-triggering
     * event aborts the transfer before {@code enterGrace} can attach that tick); this is the
     * belt that keeps a future ordering drift loud-safe instead of corrupting a restore. */
    boolean ownsEpisode(int seatId) {
        return episodes.containsKey(seatId);
    }

    private void clearMountLost(int seatId, long tick) {
        episodes.remove(seatId);
        System.out.println("npeer-serve grace_clear seat=" + seatId
                + " reason=mount_lost round=" + round + " tick=" + tick);
    }

    private long wireIdFor(int seatId) {
        Long wireId = seatWireIdBySeatId.get(seatId);
        if (wireId == null) {
            throw new IllegalStateException("grace lane: seat " + seatId + " has no fixed wire"
                    + " id (the descriptor seat map is total by construction)");
        }
        return wireId.longValue();
    }
}
